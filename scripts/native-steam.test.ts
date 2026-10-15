#!/usr/bin/env bun
import { test, expect } from 'bun:test';
import { runInNewContext } from 'node:vm';

const source = await Bun.file(new URL('../client/src/steam/NativeSteamScripts.scala', import.meta.url)).text();
const scripts = Object.fromEntries([...source.matchAll(/val (\w+) = """([\s\S]*?)"""/g)].map(m => [m[1], m[2]]));
function fixture() {
  const calls: string[] = [];
  const install = { eInstallState: 7, rgApps: [{ nAppID: 814380 }], nDiskSpaceRequired: 200, nDiskSpaceAvailable: 300, iInstallFolder: 0 };
  const local = { installed: true, display_status: 11, clientid: 'local' };
  const overview = { update_appid: 814380, update_bytes_downloaded: 10, update_bytes_to_download: 100, update_network_bytes_per_second: 5 };
  const item = { appid: 814380, downloaded_bytes: 10, total_bytes: 100, active: true, completed: false, queue_index: 0 };
  const app = { local_per_client_data: local, selected_clientid: 'other-computer', GetGameID: () => '814380' };
  const details = { strLaunchOptions: 'PROTON_LOG=1 %command% --custom' };
  const env: any = {
    setTimeout, clearTimeout,
    loginStore: { loginState: 5, accountName: 'alice' },
    App: { m_CurrentUser: { strSteamID: 'account-id' }, BHasCurrentUser: () => true, GetServicesInitialized: () => true },
    appStore: { GetAppOverviewByAppID: () => app },
    appDetailsStore: { RequestAppDetails: async () => details },
    downloadsStore: { LocalDownloadOverview: overview, GetDownloadItemForOverview(a: any) { expect(a.selected_clientid).toBe('local'); return item; } },
    SteamClient: {
      Downloads: {
        PauseAppUpdate: (...args: any[]) => calls.push(JSON.stringify(['pause', ...args])),
        ResumeAppUpdate: (...args: any[]) => calls.push(JSON.stringify(['resume', ...args])),
      },
      Apps: {
        RegisterForGameActionShowError: () => ({}), GetActiveGameActions: async () => [],
        CancelGameAction: (id: number) => calls.push(`cancel-launch:${id}`),
        RunGame: (...args: any[]) => calls.push(JSON.stringify(args)),
        SetAppLaunchOptions: (id: number, options: string) => { calls.push('set-options'); details.strLaunchOptions = options; },
        RegisterForAppDetails: (id: number, callback: (d: any) => void) => { callback({ unAppID: id, ...details }); return { unregister() {} }; },
        LoadEula: async () => [{ id: 'publisher', version: 2 }],
        MarkEulaAccepted: () => calls.push('accept'),
      },
      InstallFolder: { GetInstallFolders: async () => [{ nFolderIndex: 0, strFolderPath: '/games', bIsMounted: true }] },
      Installs: {
        GetInstallManagerInfo: async () => install, OpenInstallWizard: () => calls.push('prepare'),
        SetInstallFolder: () => calls.push('folder'), SetCreateShortcuts: (...args: any[]) => calls.push(JSON.stringify(args)),
        ContinueInstall: () => calls.push('continue'), CancelInstall: () => calls.push('cancel'),
        OpenUninstallWizard: (...args: any[]) => calls.push(JSON.stringify(['uninstall', ...args])),
      },
    },
  };
  env.window = env;
  const invoke = (name: string, ...args: any[]) => runInNewContext(`(${scripts[name]})(...args)`, { ...env, args });
  return { calls, install, local, item, details, env, invoke };
}

test('local downloads and running status use native client fields, with completed wizard cleared', async () => {
  const f = fixture(); f.local.display_status = 4; f.install.eInstallState = 14;
  const s = await f.invoke('snapshot', [814380]);
  expect(s.games[0].running).toBe(true); expect(s.games[0].percent).toBe(10);
  expect(s.games[0].bytesTotal).toBe(100); expect(s.install).toBeNull();
});

test('current CEF download progress sums network bytes across content types', async () => {
  const f = fixture(), item: any = f.item;
  f.env.downloadsStore.LocalDownloadOverview = {};
  delete item.downloaded_bytes; delete item.total_bytes;
  item.update_type_info = [
    { progress: [{}, { bytes_total: 999 }, { bytes_in_progress: 100, bytes_total: 500 }] },
    { progress: [{}, {}, { bytes_in_progress: 20, bytes_total: 50 }] },
  ];
  const s = await f.invoke('snapshot', [814380]);
  expect(s.loggedIn).toBe(true); expect(s.games[0].bytesDone).toBe(120); expect(s.games[0].bytesTotal).toBe(550);
});

test('active progress wins over zeroed native download-item counters', async () => {
  const f = fixture();
  f.env.downloadsStore.LocalDownloadOverview = { update_appid: 814380, progress: [{}, {}, { bytes_in_progress: 200, bytes_total: 1000 }] };
  f.item.downloaded_bytes = 0; f.item.total_bytes = 0;
  const s = await f.invoke('snapshot', [814380]);
  expect(s.games[0].bytesDone).toBe(200); expect(s.games[0].bytesTotal).toBe(1000);
});

test('installation rejects account mismatch, changed task and insufficient disk space', async () => {
  const f = fixture();
  expect((await f.invoke('action', 'install', 814380, 0, 'bob')).ok).toBe(false);
  expect(f.calls).toHaveLength(0);
  f.install.rgApps = [{ nAppID: 123 }];
  expect((await f.invoke('action', 'install', 814380, 0, 'alice')).ok).toBe(false);
  expect((await f.invoke('action', 'cancel', 814380, 0, 'alice')).ok).toBe(false);
  expect(f.calls).toHaveLength(0);
  f.install.rgApps = [{ nAppID: 814380 }]; f.install.nDiskSpaceAvailable = 100;
  expect((await f.invoke('action', 'install', 814380, 0, 'alice')).ok).toBe(false);
  expect(f.calls).not.toContain('continue');
});

test('installation resumes the selected task without creating desktop shortcuts', async () => {
  const f = fixture();
  expect((await f.invoke('action', 'install', 814380, 0, 'ALICE')).ok).toBe(true);
  expect(f.calls).toEqual(['folder', '[false,false]', 'continue']);
});

test('download and uninstall controls explicitly address this HTPC rather than a remote client', async () => {
  const f = fixture();
  for (const op of ['pause', 'resume', 'uninstall']) expect((await f.invoke('action', op, 814380, 0, 'alice')).ok).toBe(true);
  expect(f.calls).toEqual(['["pause",814380,"0"]', '["resume",814380,"0"]', '["uninstall",[814380],"0",true]']);
});

test('changed license versions cannot be accepted using stale displayed text', async () => {
  const f = fixture(); f.install.eInstallState = 8;
  expect((await f.invoke('acceptEulas', 814380, [{ id: 'publisher', version: 1 }], 'alice')).ok).toBe(false);
  expect(f.calls).toHaveLength(0);
  expect((await f.invoke('acceptEulas', 814380, [{ id: 'publisher', version: 2 }], 'alice')).ok).toBe(true);
  expect(f.calls).toEqual(['accept', 'continue']);
});

test('launch state is visible to the kiosk and survives frontend reconnection', async () => {
  const f = fixture(); await f.invoke('snapshot', []);
  expect((await f.invoke('action', 'play', 814380, 0, 'alice')).ok).toBe(true);
  expect(f.calls).toEqual(['["814380","",-1,100]']);
  f.local.display_status = 4;
  const s = await f.invoke('snapshot', []);
  expect(s.games[0].appId).toBe(814380); expect(s.launch.seenRunning).toBe(true);
});

test('Gamescope launch options preserve custom flags, wrappers and shell argument boundaries', async () => {
  const f = fixture(), prefix = ['/bin/sh', "/path with spaces/it's/qijian-gamescope", 'steam-814380', '--'];
  const wrapped = await f.invoke('composeLaunch', 'PROTON_LOG=1 %command% --custom', prefix);
  expect(wrapped).toStartWith('PROTON_LOG=1 '); expect(wrapped).toEndWith(' %command% --custom');
  const p = Bun.spawn(['/bin/sh', '-c', 'printf "%s\\n" ' + wrapped.replace(/^PROTON_LOG=1 /, '')], { stdout: 'pipe' });
  expect((await new Response(p.stdout).text()).trim().split('\n')).toEqual([...prefix, '%command%', '--custom']);
  expect(await f.invoke('composeLaunch', '--custom', prefix)).toEndWith(' %command% --custom');
  expect(await f.invoke('composeLaunch', 'PROTON_LOG=1 %command% --custom', [])).toBe('PROTON_LOG=1 %command% --custom');
  expect(() => f.invoke('composeLaunch', 'gamescope -- %command%', prefix)).toThrow();
  expect(() => f.invoke('composeLaunch', '%command% %command%', prefix)).toThrow();
});

test('Steam options are compared before change; a concurrent edit prevents launch', async () => {
  const f = fixture(); await f.invoke('snapshot', []);
  expect((await f.invoke('action', 'play', 814380, 0, 'alice', { expected: '', replacement: 'new' })).ok).toBe(false);
  expect(f.calls).toHaveLength(0);
  const change = { expected: f.details.strLaunchOptions, replacement: 'wrapped %command% --custom' };
  expect((await f.invoke('action', 'play', 814380, 0, 'alice', change)).ok).toBe(true);
  expect(f.calls).toEqual(['set-options', '["814380","",-1,100]']);
  expect(f.details.strLaunchOptions).toBe(change.replacement);
});

test('shader preparation is visible and cancellation addresses only the selected launch', async () => {
  const f = fixture();
  f.env.SteamClient.Apps.GetActiveGameActions = async () => [
    { nGameActionID: 1, gameid: '123', strActionName: 'LaunchApp', strTaskName: 'ProcessingShaderCache' },
    { nGameActionID: 2, gameid: '814380', strActionName: 'LaunchApp', strTaskName: 'ProcessingShaderCache', strNumDone: '63', strNumTotal: '100' },
  ];
  const s = await f.invoke('snapshot', [814380]);
  expect(s.games[0].taskDone).toBe('63'); expect(s.games[0].taskTotal).toBe('100');
  expect((await f.invoke('action', 'cancel-launch', 814380, 0, 'alice')).ok).toBe(true);
  expect(f.calls).toEqual(['cancel-launch:2']);
});

test('expired Steam QR placeholder is never fetched or displayed; refresh clicks the native retry control', async () => {
  let clicked = 0, fetched = 0;
  const button = { disabled: false, click: () => clicked++ };
  const container = { querySelector: () => button };
  const wrapper = { parentElement: container };
  const image = { src: 'blob:expired-store-homepage', width: 160, height: 160, parentElement: wrapper };
  const context = {
    document: { images: [image] },
    getComputedStyle: (e: any) => ({ filter: e === wrapper ? 'blur(5px)' : 'none', visibility: 'visible', display: 'block' }),
    fetch: () => { fetched++; throw Error('placeholder must not be read'); },
  };
  const expired = await runInNewContext(`(${scripts.qr})(false)`, context);
  expect(expired.state).toBe('expired'); expect(expired.image).toBeNull();
  expect(fetched).toBe(0); expect(clicked).toBe(0);
  const refreshing = await runInNewContext(`(${scripts.qr})(true)`, context);
  expect(refreshing.state).toBe('loading'); expect(refreshing.image).toBeNull();
  expect(fetched).toBe(0); expect(clicked).toBe(1);
});

test('Steam loading overlay cannot leak its placeholder QR even without a refresh button', async () => {
  const container = { querySelector: () => null };
  const wrapper = { parentElement: container };
  const image = { src: 'blob:loading', width: 160, height: 160, parentElement: wrapper };
  const result = await runInNewContext(`(${scripts.qr})(false)`, {
    document: { images: [image] },
    getComputedStyle: (e: any) => ({ filter: e === wrapper ? 'blur(5px)' : 'none', visibility: 'visible', display: 'block' }),
    fetch: () => { throw Error('loading placeholder must not be read'); },
  });
  expect(result.state).toBe('loading'); expect(result.image).toBeNull();
});
