#!/usr/bin/env bun
/** Opt-in acceptance: launches an already installed game on the actual TV, then exits it. */
import { strict as assert } from 'node:assert';
import { openCdp } from './cdp-client';

const base = process.env.HTPC_TEST_URL || 'http://127.0.0.1:8787';
const appId = Number(process.env.HTPC_TEST_APP || '814380');
const resume = process.env.HTPC_TEST_RESUME === '1';
const isGame = (w: any) => w.appId === `steam_app_${appId}` || w.application === `steam-${appId}`;
assert(Number.isSafeInteger(appId) && appId > 0);
async function waitFor<T>(name: string, read: () => Promise<T | false>, timeout = 180000): Promise<T> {
  const end = Date.now() + timeout;
  let error: unknown;
  while (Date.now() < end) {
    try { const value = await read(); if (value) return value; } catch (e) { error = e; }
    await Bun.sleep(1000);
  }
  throw Error(`Timed out: ${name}${error ? `; ${error}` : ''}`);
}
async function display() {
  const r = await (await fetch(base + '/api/v1/sway', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: crypto.randomUUID(), op: 'snapshot', args: {} }) })).json();
  assert.equal(r.outcome, 'result'); return r.payload;
}
const targets = await (await fetch('http://127.0.0.1:9228/json/list')).json();
const kiosk = targets.find((t: any) => t.type === 'page' && t.url.startsWith(base));
assert(kiosk, 'TV kiosk must be running');
assert(resume || !(await display()).windows.some(isGame), 'Game must not already be running');
const ui = await openCdp(kiosk.webSocketDebuggerUrl);
let launched = resume;
async function click(key: string) {
  return ui.evaluate(`(()=>{const b=document.querySelector('[data-tv-focus="steam:local-${key}"]');if(!b?.offsetWidth||b.disabled)return false;b.scrollIntoView({block:'center'});b.focus();b.click();return true})()`);
}
try {
  const route = '/app/steam/' + encodeURIComponent(JSON.stringify({ Game: { appId } }));
  if (resume) assert(kiosk.url.includes(route), 'Resume only this test\'s existing game page');
  else {
    await ui.call('Page.navigate', { url: base + '/?display=1#' + route });
    await waitFor('installed game ready, including Steam updates', () => ui.evaluate(`(()=>{const b=document.querySelector('[data-tv-focus="steam:local-play"]');return !!b?.offsetWidth&&!b.disabled})()`), 600000);
    assert(await click('play')); launched = true;
  }
  const running = await waitFor('fullscreen game on game workspace (first-run shader compilation may take several minutes)', async () => {
    const state = await display();
    const game = state.windows.find(isGame);
    return game?.focused && game.fullscreen && game.workspace === 'game' && state;
  }, 900000);
  assert(running.windows.filter((w: any) => w.appId?.toLowerCase() === 'steam').every((w: any) => w.workspace === '__background' && !w.focused));
  if (process.env.HTPC_EXPECT_GAMESCOPE === '1') {
    const outer = running.windows.find((w: any) => w.application === `steam-${appId}`); assert(outer, 'labelled outer Gamescope window');
    const args = (await Bun.file(`/proc/${outer.pid}/cmdline`).text()).split('\0');
    assert(args.includes('fsr') && args.includes('--mangoapp'), 'real Gamescope FSR and HUD arguments');
    console.log('✓ Steam game runs inside its own labelled Gamescope instance with FSR and MangoApp');
  }
  assert.equal(await ui.evaluate('document.hasFocus()'), false);
  console.log('✓ real game owns fullscreen/focus; Steam remains hidden; kiosk loses input focus');
  // Let startup movies/shader preparation finish before capturing the real compositor.
  await Bun.sleep(45000);
  const capture = process.env.HTPC_CAPTURE_TOOL || Bun.which('grim');
  if (capture) {
    const env = Object.fromEntries((await Bun.file(`${process.env.HOME}/.config/htpc-next/desktop.env`).text()).trim().split('\n').map(s => s.split('=', 2)));
    const p = Bun.spawn([capture, `artifacts/game-${appId}-physical.png`], { env: { ...process.env, ...env }, stdout: 'inherit', stderr: 'inherit', timeout: 10000 });
    assert.equal(await p.exited, 0, 'physical compositor capture');
  }
  // Do not focus the hidden stop button: exit must restore the original Play selection.
  assert(await ui.evaluate(`(()=>{const b=document.querySelector('[data-tv-focus="steam:local-stop"]');if(!b?.offsetWidth||b.disabled)return false;b.click();return true})()`));
  await waitFor('original Play focus after game exit', () => ui.evaluate(`document.hasFocus()&&document.activeElement?.getAttribute('data-tv-focus')==='steam:local-play'`));
  const home = await display(), shell = home.windows.find((w: any) => w.appId === 'htpc-next');
  assert(shell?.focused && shell.fullscreen && shell.workspace === 'home');
  assert(!home.windows.some(isGame));
  launched = false;
  console.log('✓ game exited, home fullscreen restored, original Play control focused');
  const shot = await ui.call('Page.captureScreenshot');
  await Bun.write(`artifacts/game-${appId}-returned.png`, Buffer.from(shot.data, 'base64'));
} finally {
  if (launched) await ui.evaluate(`(()=>{for(const key of ['stop','cancel-launch']){const b=document.querySelector('[data-tv-focus="steam:local-'+key+'"]');if(b?.offsetWidth&&!b.disabled){b.click();break}}return true})()`).catch(() => {});
  ui.close();
}
