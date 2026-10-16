#!/usr/bin/env bun
/** Physical regression: window placement must preserve Chromium fullscreen before JS handoff. */
import { strict as assert } from 'node:assert';
import { openSync, closeSync } from 'node:fs';
import { openCdp } from './cdp-client';

const base = process.env.HTPC_TEST_URL || 'http://127.0.0.1:8787';
async function rpc(module: string, op: string, args = {}) {
  const result = await (await fetch(`${base}/api/v1/${module}`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: crypto.randomUUID(), op, args }) })).json();
  assert.equal(result.outcome, 'result', JSON.stringify(result)); return result.payload;
}
const snapshot = () => rpc('sway', 'snapshot');
const initial = await snapshot();
assert(!initial.windows.some((w: any) => w.workspace === 'game'), 'Run this test when no game is playing');
const kiosk = initial.windows.find((w: any) => w.appId === 'htpc-next'); assert(kiosk?.fullscreen);
const target = (await (await fetch('http://127.0.0.1:9228/json/list')).json()).find((t: any) => t.type === 'page' && t.url.startsWith(base));
assert(target);
const browser = await openCdp(target.webSocketDebuggerUrl);
const env = { ...process.env, ...Object.fromEntries((await Bun.file(`${process.env.HOME}/.config/htpc-next/desktop.env`).text()).trim().split('\n').map(s => s.split('=', 2))), SDL_VIDEODRIVER: 'x11' };
const ffplay = Bun.which('ffplay'); assert(ffplay);
const pattern = [ffplay, '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=30', '-fs'];
async function unchanged() {
  const state = await snapshot();
  const shell = state.windows.find((w: any) => w.ref.container === kiosk.ref.container);
  assert(shell?.fullscreen && shell.workspace === 'home', 'kiosk fullscreen must survive another window mapping');
  const { bounds } = await browser.call('Browser.getWindowForTarget', { targetId: target.id });
  assert.equal(bounds.windowState, 'fullscreen', 'Chromium must keep its browser chrome hidden');
  assert(await browser.evaluate('innerHeight === screen.height && innerWidth === screen.width'), 'kiosk viewport must cover the whole TV');
  return state;
}
async function until(label: string, test: () => Promise<boolean>, timeout = 15000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await test()) return; await Bun.sleep(100); }
  throw Error('Timed out: ' + label);
}
await Bun.write('artifacts/fullscreen-live.log', '');
const log = openSync('artifacts/fullscreen-live.log', 'a');
let processHandle: ReturnType<typeof Bun.spawn> | undefined;
try {
  await unchanged();
  const plan = await rpc('system', 'game-display.prepare', { application: 'fullscreen-acceptance', settings: {
    enabled: true, width: 1600, height: 900, fps: 60, scaling: 'Linear', sharpness: 5, hud: false,
  } });
  for (const kind of ['gamescope', 'native-game', 'steam-management']) {
    const args = kind === 'gamescope' ? [...plan.prefix, '/usr/bin/env', 'SDL_VIDEODRIVER=x11', ...pattern] : pattern;
    const appId = kind === 'native-game' ? 'steam_app_999990' : 'steam';
    processHandle = Bun.spawn(args, { env: { ...env, ...(kind === 'gamescope' ? {} : { SDL_VIDEO_X11_WMCLASS: appId }) },
      stdout: log, stderr: log, detached: true });
    let window: any;
    await until(`${kind} maps to its assigned workspace`, async () => {
      const state = await unchanged();
      window = state.windows.find((w: any) => kind === 'gamescope' ? w.application === 'fullscreen-acceptance' : w.pid === processHandle!.pid);
      if (!window) { assert.equal(processHandle!.exitCode, null, 'fixture must remain running'); return false; }
      assert.equal(window.workspace, kind === 'steam-management' ? '__background' : 'game');
      assert(window.fullscreen);
      return true;
    });
    if (kind !== 'steam-management') {
      // Deliberately no window.move/fullscreen call: Sway must place it correctly at birth.
      await rpc('sway', 'window.focus', { window: window.ref });
      await unchanged();
      await rpc('sway', 'workspace.activate', { workspace: 'home' });
      await until('return home while game is still alive', async () => {
        const state = await unchanged();
        return state.windows.some((w: any) => w.ref.container === kiosk.ref.container && w.focused) && await browser.evaluate('document.hasFocus()');
      });
      await rpc('sway', 'window.focus', { window: window.ref });
    } else assert(!(await snapshot()).windows.find((w: any) => w.ref.container === window.ref.container).focused, 'Steam management must stay hidden');
    // Catch asynchronous X11/Wayland fullscreen requests after initial mapping.
    for (let i = 0; i < 10; i++) { await Bun.sleep(100); await unchanged(); }
    process.kill(-processHandle.pid, 'SIGTERM'); await processHandle.exited; processHandle = undefined;
    await until('fixture closes', async () => !(await snapshot()).windows.some((w: any) => w.ref.container === window.ref.container));
    await rpc('sway', 'workspace.activate', { workspace: 'home' });
    await unchanged();
    console.log(`✓ ${kind}: assigned before fullscreen, kiosk/browser viewport preserved during launch, return and exit`);
  }
} finally {
  if (processHandle) { try { process.kill(-processHandle.pid, 'SIGTERM'); } catch {} await processHandle.exited; }
  closeSync(log);
  await rpc('sway', 'workspace.activate', { workspace: 'home' });
  // Emergency cleanup only; the assertions above must pass without repairing kiosk state.
  await rpc('sway', 'window.fullscreen', { window: kiosk.ref, enabled: true });
  await rpc('sway', 'window.focus', { window: kiosk.ref });
  browser.close();
}
