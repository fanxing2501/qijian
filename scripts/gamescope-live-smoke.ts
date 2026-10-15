#!/usr/bin/env bun
/** Explicit physical-display acceptance for both non-Steam and Steam applications. */
import { strict as assert } from 'node:assert';
import { openSync, closeSync } from 'node:fs';
import { openCdp } from './cdp-client';

const base = process.env.HTPC_TEST_URL || 'http://127.0.0.1:8787';
const appId = Number(process.env.HTPC_TEST_APP || '814380');
async function rpc(module: string, op: string, args = {}) {
  const r = await (await fetch(`${base}/api/v1/${module}`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: crypto.randomUUID(), op, args }) })).json();
  assert.equal(r.outcome, 'result', JSON.stringify(r)); return r.payload;
}
async function until<T>(read: () => Promise<T | false>, timeout = 20000): Promise<T> {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const value = await read(); if (value) return value; await Bun.sleep(200); }
  throw Error('Gamescope live acceptance timed out');
}
const support = await rpc('system', 'game-display.status');
assert(support.gamescope.available && support.mangoapp.available);
const initial = await rpc('sway', 'snapshot');
assert(!initial.windows.some((w: any) => w.workspace === 'game'), 'Run acceptance when no game is playing');
const shell = initial.windows.find((w: any) => w.appId === 'htpc-next'); assert(shell);
const env = Object.fromEntries((await Bun.file(`${process.env.HOME}/.config/htpc-next/desktop.env`).text()).trim().split('\n').map(s => s.split('=', 2)));
const capture = process.env.HTPC_CAPTURE_TOOL || Bun.which('grim');
const profile = { enabled: true, width: 1600, height: 900, fps: 30, scaling: 'Fsr', sharpness: 5, hud: true };
const plan = await rpc('system', 'game-display.prepare', { application: 'acceptance-local', settings: profile });
assert(Bun.which('ffplay'), 'Install ffplay for the non-Steam animated acceptance demo');
await Bun.write('artifacts/gamescope-local.log', '');
const fd = openSync('artifacts/gamescope-local.log', 'a');
const local = Bun.spawn([...plan.prefix, '/usr/bin/env', 'SDL_VIDEODRIVER=x11', Bun.which('ffplay')!, '-hide_banner', '-loglevel', 'error',
  '-f', 'lavfi', '-i', 'testsrc2=size=1600x900:rate=120', '-fs'], { env: { ...process.env, ...env }, stdout: fd, stderr: fd, detached: true });
try {
  const window = await until(async () => (await rpc('sway', 'snapshot')).windows.find((w: any) => w.application === 'acceptance-local') || false);
  await rpc('sway', 'window.move', { window: window.ref, workspace: 'game' });
  await rpc('sway', 'window.fullscreen', { window: window.ref, enabled: true });
  await rpc('sway', 'window.focus', { window: window.ref });
  await Bun.sleep(6000);
  assert.equal(local.exitCode, null);
  const args = (await Bun.file(`/proc/${window.pid}/cmdline`).text()).split('\0');
  assert(args.includes('fsr') && args.includes('--mangoapp') && args.includes('1600'));
  if (capture) assert.equal(await Bun.spawn([capture, 'artifacts/gamescope-local.png'], { env: { ...process.env, ...env } }).exited, 0);
  console.log('✓ non-Steam animated application: labelled Gamescope window, FSR, 900p → TV output, 30 FPS, MangoApp');
} finally {
  try { process.kill(-local.pid, 'SIGTERM'); } catch {}
  await local.exited; closeSync(fd);
  await rpc('sway', 'workspace.activate', { workspace: 'home' });
  await rpc('sway', 'window.fullscreen', { window: shell.ref, enabled: true });
  await rpc('sway', 'window.focus', { window: shell.ref });
}

const target = (await (await fetch('http://127.0.0.1:9228/json/list')).json()).find((t: any) => t.type === 'page');
const ui = await openCdp(target.webSocketDebuggerUrl);
async function click(key: string) {
  assert(await ui.evaluate(`(()=>{const b=document.querySelector('[data-tv-focus="display:steam-${appId}:${key}"]');if(!b?.offsetWidth||b.disabled)return false;b.scrollIntoView({block:'center'});b.focus();b.click();return true})()`), `display control ${key} is ready`);
}
async function setOption(key: string, expected: string) {
  for (let n = 0; n < 8; n++) {
    if ((await ui.evaluate(`document.querySelector('[data-tv-focus="display:steam-${appId}:${key}"]')?.textContent||''`)).includes(expected)) return;
    await click(key);
  }
  throw Error('Could not select ' + key);
}
try {
  const token = crypto.randomUUID();
  await ui.call('Page.navigate', { url: base + '/?display=1&acceptance=' + token + '#/app/steam/' + encodeURIComponent(JSON.stringify({ Game: { appId } })) });
  await until(() => ui.evaluate(`location.search.includes('${token}')&&!!document.querySelector('[data-tv-focus="display:steam-${appId}:open"]')`));
  await click('open');
  await until(() => ui.evaluate(`!document.querySelector('[data-tv-focus="display:steam-${appId}:enabled"]').disabled`));
  // Exercise the same gamepad router used by a physical controller, including row transitions.
  await ui.evaluate(`(()=>{window.__acceptancePad={connected:true,mapping:'standard',axes:[0,0],buttons:Array.from({length:17},()=>({pressed:false}))};Object.defineProperty(navigator,'getGamepads',{value:()=>[window.__acceptancePad],configurable:true});document.querySelector('[data-tv-focus="display:steam-${appId}:open"]').focus();return true})()`);
  async function pad(button: number) {
    await ui.evaluate(`new Promise(resolve=>{window.__acceptancePad.buttons[${button}].pressed=true;setTimeout(()=>{window.__acceptancePad.buttons[${button}].pressed=false;setTimeout(()=>resolve(true),100)},100)})`);
  }
  for (const key of ['enabled', 'resolution', 'fps', 'scaling', 'sharpness', 'hud']) {
    await pad(13);
    assert.equal(await ui.evaluate(`document.activeElement?.getAttribute('data-tv-focus')`), `display:steam-${appId}:${key}`, 'D-pad down reaches every display control');
  }
  await pad(13);
  if ((await ui.evaluate(`document.activeElement?.getAttribute('data-tv-focus')`)).endsWith(':reload')) await pad(14);
  assert.equal(await ui.evaluate(`document.activeElement?.getAttribute('data-tv-focus')`), `display:steam-${appId}:save`);
  await setOption('enabled', '开启'); await setOption('resolution', '1600 × 900');
  await setOption('fps', '30 FPS'); await setOption('scaling', 'FSR 1'); await setOption('hud', '开启');
  await ui.evaluate(`document.querySelector('[data-tv-focus="display:steam-${appId}:save"]').focus();true`);
  await pad(0);
  await until(() => ui.evaluate(`document.querySelector('.game-display-options .steam-message')?.textContent.includes('已保存')`));
  await until(() => ui.evaluate(`document.activeElement?.getAttribute('data-tv-focus')==='display:steam-${appId}:save'`));
  const stored = await rpc('storage', 'get', { key: { namespace: 'game-display', key: `steam-${appId}` } });
  assert.deepEqual(stored.value, profile);
  if (capture) assert.equal(await Bun.spawn([capture, 'artifacts/gamescope-settings.png'], { env: { ...process.env, ...env } }).exited, 0);
  const oldDocument = await ui.evaluate('performance.timeOrigin');
  await ui.call('Page.reload', { ignoreCache: true });
  await until(() => ui.evaluate(`performance.timeOrigin!==${oldDocument}&&document.querySelector('[data-tv-focus="display:steam-${appId}:open"]')?.textContent.includes('1600 × 900')`));
  console.log('✓ actual Scala.js settings save, typed serverStorage persistence and reload');
} finally { ui.close(); }
const smoke = Bun.spawn(['bun', 'scripts/native-live-smoke.ts'], { env: { ...process.env, HTPC_EXPECT_GAMESCOPE: '1' }, stdout: 'inherit', stderr: 'inherit' });
assert.equal(await smoke.exited, 0, 'Steam game launch/exit/return acceptance');
