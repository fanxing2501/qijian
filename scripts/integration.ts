#!/usr/bin/env bun
import { strict as assert } from "node:assert";
import { mkdir, rm, readFile } from "node:fs/promises";
import { openSync, closeSync } from "node:fs";
import { resolve, join } from "node:path";
import { homedir } from "node:os";
import { youtubeFixture } from "./youtube-fixture";
import { accountFixture, auditCredentials } from "./account-fixture";
import { infrastructureTest } from "./infrastructure-test";
import { hostTest } from "./host-test";
import { openCdp } from "./cdp-client";
import { playerTest } from "./player-test";

const root = process.cwd();
const temporary = resolve(root, ".runtime", `integration-${crypto.randomUUID()}`);
await mkdir(temporary, { recursive: true });
await mkdir("artifacts", { recursive: true });
const probe = Bun.serve({ port: 0, hostname: "127.0.0.1", fetch: () => new Response("reserved") });
const port = probe.port!;
await probe.stop(true);
const base = `http://127.0.0.1:${port}`;
const hostConfig = join(temporary, 'host-services.json');
await Bun.write(hostConfig, '{}');
let chromium = process.env.HTPC_CHROMIUM || Bun.which("chromium") || Bun.which("google-chrome");
if (!chromium) {
  const cache = join(homedir(), ".cache/puppeteer/chrome");
  for await (const path of new Bun.Glob("*/chrome-linux64/chrome").scan({ cwd: cache, absolute: true })) {
    chromium = path;
  }
}
const localWine = resolve(".tools/wine/usr/lib/x86_64-linux-gnu/wine/wine");
const localWineServer = resolve(".tools/wine/usr/lib/x86_64-linux-gnu/wine/wineserver");
const wine = process.env.HTPC_WINE || ((await Bun.file(localWine).exists()) ? localWine : undefined);
const wineServer = process.env.HTPC_WINESERVER || ((await Bun.file(localWineServer).exists()) ? localWineServer : undefined);
const fixture = Bun.serve({
  port: 0, hostname: "127.0.0.1",
  fetch(request) {
    const path = new URL(request.url).pathname;
    return new Response(`<!doctype html><html><head><title>Fixture ${path}</title></head><body><h1>${path}</h1><form onsubmit="event.preventDefault();document.body.dataset.submitted='yes'"><input id="text"><button>Submit</button></form><div style="height:3000px"></div></body></html>`, {
      headers: {
        "Content-Type": "text/html; charset=utf-8",
        ...(path === "/login" ? { "Set-Cookie": "htpc-session=fixture-secret; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600" } : {}),
      },
    });
  },
});
const fixtureUrl = `http://127.0.0.1:${fixture.port}`;
const log = join(temporary, "server.log");
const logFd = openSync(log, "w");
const service = Bun.spawn(["groot", "use-jdk", "25", "--", "java", "-jar", resolve("dist/htpc-next.jar")], {
  cwd: root,
  env: {
    ...process.env,
    HTPC_HOST: "127.0.0.1", HTPC_PORT: String(port), HTPC_DATA: join(temporary, "data"),
    HTPC_WEB: resolve("dist/web"), HTPC_DLL_HOST: resolve("dist/native/dll-host.exe"),
    ...(chromium ? { HTPC_CHROMIUM: chromium } : {}),
    ...(wine ? { HTPC_WINE: wine } : {}),
    ...(wineServer ? { HTPC_WINESERVER: wineServer } : {}),
    SWAYSOCK: "",
    HTPC_HOST_SERVICES: hostConfig,
  },
  stdout: logFd, stderr: logFd, detached: true,
});

async function waitFor<A>(label: string, fn: () => Promise<A | false>, timeout = 30000): Promise<A> {
  const deadline = Date.now() + timeout;
  let error: unknown;
  while (Date.now() < deadline) {
    // Optional DOM queries return undefined until the component mounts.
    try { const value = await fn(); if (value !== false && value != null) return value; } catch (e) { error = e; }
    await Bun.sleep(100);
  }
  throw new Error(`Timed out: ${label}. ${error || ""}`);
}

async function raw(module: string, op: string, args: unknown = {}) {
  const id = crypto.randomUUID();
  const response = await fetch(`${base}/api/v1/${module}`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ id, op, args }),
    signal: AbortSignal.timeout(150000),
  });
  const value = await response.json() as any;
  assert.equal(value.id, id);
  return { ...value, status: response.status };
}
async function rpc(module: string, op: string, args: unknown = {}) {
  const response = await raw(module, op, args);
  assert.equal(response.outcome, "result", `${module}/${op}: ${JSON.stringify(response)}`);
  return response.payload;
}
const options = (name: string, profile: string | null = null) => ({ name, profile, proxy: null, language: "zh-CN", width: 1280, height: 800 });
const evalJS = (tab: unknown, script: string, timeoutMs = 15000) => rpc("chromium", "tab.eval", { tab, script, options: { awaitPromise: true, timeoutMs } });
const jsValue = async (tab: unknown, script: string) => (await evalJS(tab, script)).Value?.value;
const goto = (tab: unknown, url: string) => rpc("chromium", "tab.goto", { tab, navigation: { url, waitUntil: "DOMContentLoaded", timeoutMs: 15000 } });
const execute = (instance: string, name: string, input: unknown, timeoutMs = 15000, freeFunction: string | null = null) => ({
  instance, invocation: { dll: resolve("dist/native/example-adapter.dll"), function: name, arguments: input, freeFunction }, options: { timeoutMs },
});
let passed = false;
let uiControl: Awaited<ReturnType<typeof openCdp>> | undefined;
try {
  await waitFor("HTTP startup", async () => (await fetch(`${base}/api/v1/health`)).ok);
  assert.equal((await fetch(base)).status, 200);
  assert.match(await (await fetch(`${base}/app.js`)).text(), /Laminar|laminar/);
  const caps = await rpc("system", "capabilities");
  assert.equal(caps.chromium.available, true);
  console.log("✓ HTTP, static assets, typed capabilities");

  const invalid = await raw("chromium", "missing.operation");
  assert.equal(invalid.status, 400);
  assert.equal(invalid.outcome, "protocol-error");
  const invalidId = await raw("chromium", "instance.get", { instance: "../../escape" });
  assert.equal(invalidId.outcome, "protocol-error");
  console.log("✓ malformed commands and identifiers rejected at protocol boundary");
  assert.equal((await fetch(base+'/api/v1/auth',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).status,404);
  await infrastructureTest(base,rpc);
  await hostTest(base,hostConfig,rpc,raw);

  const eventsAbort = new AbortController();
  const eventsResponse = await fetch(`${base}/api/v1/events`, { signal: eventsAbort.signal });
  assert.match(eventsResponse.headers.get("content-type") || "", /text\/event-stream/);
  const reader = eventsResponse.body!.getReader();
  const firstEvent = await reader.read();
  assert.match(new TextDecoder().decode(firstEvent.value), /event: sync/);
  eventsAbort.abort();
  console.log("✓ SSE connection starts with a resynchronization signal");

  const b = await rpc("chromium", "instance.new", options("Integration browser", "integration-profile"));
  const tab = (await rpc("chromium", "tab.new", { instance: b.id })).ref;
  const login = await goto(tab, `${fixtureUrl}/login`);
  assert.equal(login.title, "Fixture /login");
  const cookies = await rpc("chromium", "cookie.list", { instance: b.id });
  assert(cookies.some((c: any) => c.name === "htpc-session" && c.httpOnly && c.value === "fixture-secret"));
  assert.equal(await jsValue(tab, "document.cookie"), "");
  assert.deepEqual(await jsValue(tab, "Promise.resolve({answer: 42})"), { answer: 42 });
  assert.deepEqual(await evalJS(tab, "undefined"), { Undefined: {} });
  const throwing = await raw("chromium", "tab.eval", { tab, script: "throw new Error('expected failure')", options: { awaitPromise: true, timeoutMs: 15000 } });
  assert.equal(throwing.payload.code, "Execution");
  console.log("✓ real Chromium navigation, Promise evaluation, JS errors, undefined and HttpOnly cookies");

  await goto(tab, `${fixtureUrl}/second`);
  assert.equal((await rpc("chromium", "tab.back", { tab })).url, `${fixtureUrl}/login`);
  assert.equal((await rpc("chromium", "tab.forward", { tab })).url, `${fixtureUrl}/second`);
  await rpc("chromium", "tab.reload", { tab });
  const shot = await rpc("chromium", "tab.screenshot", { tab });
  assert.equal(Buffer.from(shot.base64, "base64").subarray(1, 4).toString(), "PNG");
  console.log("✓ back, forward, reload, PNG screenshot");

  const frame = await rpc("chromium", "tab.capture", { tab });
  assert.equal(frame.url, `${fixtureUrl}/second`);
  assert.equal(frame.image.mimeType, "image/jpeg");
  assert(frame.width > 300 && frame.height > 200);
  const position = await jsValue(tab, "(()=>{const r=document.querySelector('#text').getBoundingClientRect();return {x:r.x+10,y:r.y+10}})()");
  await rpc("chromium", "tab.click", { tab, position });
  await rpc("chromium", "tab.insertText", { tab, text: "test 输入" });
  assert.equal(await jsValue(tab, "document.querySelector('#text').value"), "test 输入");
  await rpc("chromium", "tab.pressKey", { tab, key: "SelectAll" });
  await rpc("chromium", "tab.insertText", { tab, text: "replacement" });
  await rpc("chromium", "tab.pressKey", { tab, key: "Enter" });
  assert.equal(await jsValue(tab, "document.body.dataset.submitted"), "yes");
  assert.equal(await jsValue(tab, "document.querySelector('#text').value"), "replacement");
  await rpc("chromium", "tab.scroll", { tab, deltaY: 500 });
  await waitFor("remote scroll", async () => (await jsValue(tab, "scrollY")) > 0);
  assert.equal((await raw("chromium", "tab.click", { tab, position: { x: -1, y: 0 } })).payload.code, "InvalidArgument");
  assert.equal((await raw("chromium", "tab.pressKey", { tab, key: "NotAKey" })).outcome, "protocol-error");
  console.log("✓ remote JPEG frame, pointer coordinates, Unicode input, select-all, Enter submission, scroll and invalid input");

  const duplicateProfile = await raw("chromium", "instance.new", options("Duplicate", "integration-profile"));
  assert.equal(duplicateProfile.payload.code, "Conflict");
  const isolated = await rpc("chromium", "instance.new", options("Isolated browser"));
  assert.equal((await rpc("chromium", "cookie.list", { instance: isolated.id })).length, 0);
  const forged = await raw("chromium", "tab.eval", { tab: { instance: isolated.id, tab: tab.tab }, script: "1", options: { awaitPromise: true, timeoutMs: 1000 } });
  assert.equal(forged.payload.code, "NotFound");
  const timeout = await raw("chromium", "tab.eval", { tab, script: "new Promise(() => {})", options: { awaitPromise: true, timeoutMs: 250 } });
  assert.equal(timeout.payload.code, "Timeout");
  assert.equal(await jsValue(tab, "21 * 2"), 42);
  await rpc("chromium", "instance.delete", { instance: isolated.id });
  await rpc("chromium", "instance.delete", { instance: b.id });
  const restored = await rpc("chromium", "instance.new", options("Restored", "integration-profile"));
  assert((await rpc("chromium", "cookie.list", { instance: restored.id })).some((c: any) => c.name === "htpc-session"));
  await rpc("chromium", "cookie.delete", { instance: restored.id, selector: { name: "htpc-session", domain: "127.0.0.1", path: "/", partitionKey: null } });
  assert(!(await rpc("chromium", "cookie.list", { instance: restored.id })).some((c: any) => c.name === "htpc-session"));
  await rpc("chromium", "cookie.set", { instance: restored.id, cookies });
  assert((await rpc("chromium", "cookie.list", { instance: restored.id })).some((c: any) => c.name === "htpc-session"));
  await rpc("chromium", "cookie.clear", { instance: restored.id });
  assert.deepEqual(await rpc("chromium", "cookie.list", { instance: restored.id }), []);
  await rpc("chromium", "instance.delete", { instance: restored.id });
  console.log("✓ profile exclusivity, instance isolation, forged tab rejection, timeout recovery, persistent cookies, cookie set/delete");

  if (wine) {
    assert.equal(caps.wine.available, true, caps.wine.detail);
    const w = await rpc("wine", "instance.new", { name: "Integration DLL", profile: null });
    const value = { message: "UTF-8 中文", nested: [1, true, null] };
    assert.deepEqual(await rpc("wine", "execute", execute(w.id, "echo_json", value, 15000, "free_json")), value);
    const unknown = await raw("wine", "execute", execute(w.id, "not_an_export", {}));
    assert.equal(unknown.payload.code, "Execution");
    const broken = await raw("wine", "execute", execute(w.id, "invalid_json", {}));
    assert.equal(broken.payload.code, "Serialization");
    const timed = await raw("wine", "execute", execute(w.id, "slow_json", {}, 250));
    assert.equal(timed.payload.code, "Timeout");
    assert.equal((await rpc("wine", "instance.get", { instance: w.id })).state, "Failed");
    await rpc("wine", "instance.delete", { instance: w.id });
    console.log("✓ real Wine x64 DLL call, UTF-8, memory release, missing export, invalid JSON, timeout termination and instance cleanup");
  } else console.log("- Wine runtime unavailable; run groot jobs wine-runtime to enable the real DLL integration test");

  const ui = await rpc("chromium", "instance.new", options("UI verification"));
  const uiTab = (await rpc("chromium", "tab.new", { instance: ui.id })).ref;
  // Physical controllers must not drive the headless fixture during a TV session.
  const endpoint = await rpc('chromium', 'instance.socket', { instance: ui.id });
  uiControl = await openCdp(base.replace(/^http/, 'ws') + endpoint.path);
  const uiSession = (await uiControl.call('Target.attachToTarget', { targetId: uiTab.tab, flatten: true })).sessionId;
  await uiControl.call('Page.addScriptToEvaluateOnNewDocument', {
    source: "Object.defineProperty(navigator,'getGamepads',{value:()=>[],configurable:true})",
  }, uiSession);
  await goto(uiTab, `${base}/console`);
  await waitFor("Laminar mount", async () => await jsValue(uiTab, "document.querySelector('h1')?.textContent === '本机能力工作台'"));
  await waitFor("Laminar typed client", async () => (await jsValue(uiTab, "document.querySelectorAll('.instance').length")) > 0);
  await jsValue(uiTab, "[...document.querySelectorAll('button')].find(b => b.textContent === '＋ 创建 Chromium').click(); true");
  await waitFor("UI creates actual browser", async () => (await rpc("chromium", "instance.list")).length === 2);
  await waitFor("UI operation complete", async () => await jsValue(uiTab, "document.querySelector('.status').textContent.includes('创建浏览器完成')"));
  const uiScreenshot = await rpc("chromium", "tab.screenshot", { tab: uiTab });
  await Bun.write("artifacts/test-ui.png", Buffer.from(uiScreenshot.base64, "base64"));
  await jsValue(uiTab, "[...document.querySelectorAll('button')].find(b => b.textContent === '删除实例').click(); true");
  await waitFor("UI deletes actual browser", async () => (await rpc("chromium", "instance.list")).length === 1);
  await jsValue(uiTab, "[...document.querySelectorAll('.nav-item')].find(b => b.textContent.includes('Wine')).click(); true");
  assert(await jsValue(uiTab, "document.body.textContent.includes('DLL 执行')"));
  await jsValue(uiTab, "[...document.querySelectorAll('.nav-item')].find(b => b.textContent.includes('Sway')).click(); true");
  assert(await jsValue(uiTab, "document.body.textContent.includes('尚未连接合成器')"));
  console.log("✓ actual Scala.js/Laminar console mounts, decodes typed APIs, creates/deletes browsers and switches modules");

  const yt = await rpc("chromium", "instance.new", options("YouTube fixture", "htpc-youtube-public"));
  const site = await youtubeFixture(join(temporary, "data/chromium/profiles/htpc-youtube-public"));
  const googleBrowser = await rpc('chromium','instance.new',options('Google account fixture','htpc-youtube-account'));
  const steamBrowser = await rpc('chromium','instance.new',options('Steam account fixture','htpc-steam-account'));
  const google = await accountFixture(base,googleBrowser.id,'youtube');
  const steam = await accountFixture(base,steamBrowser.id,'steam');
  async function noFlow(namespace:string) {
    const dir=join(temporary,'data/storage',namespace);
    for await(const path of new Bun.Glob('flow-*.json').scan({cwd:dir,absolute:true}))if((await Bun.file(path).json()).value!==null)return false;
    return true;
  }
  try {
    const focus = () => jsValue(uiTab, "document.activeElement?.getAttribute('data-tv-focus')");
    const key = (key: string) => rpc("chromium", "tab.pressKey", { tab: uiTab, key });
    const click = (id: string) => waitFor(`click ${id}`, () => jsValue(uiTab, `(()=>{const e=document.querySelector('[data-tv-focus="${id}"]');if(!e)return false;e.click();return true})()`));
    await goto(uiTab, base);
    await waitFor("TV home", async () => await focus() === "home:youtube");
    await jsValue(uiTab, "(()=>{window.__uiErrors=[];const log=console.error;console.error=(...args)=>{window.__uiErrors.push(args.map(String).join(' '));log(...args)};window.addEventListener('error',e=>window.__uiErrors.push(e.message));window.addEventListener('unhandledrejection',e=>window.__uiErrors.push(String(e.reason)));return true})()");
    await key("ArrowDown");
    assert.equal(await focus(), "app:youtube");
    await key("ArrowRight");
    assert.equal(await focus(), "app:steam");
    await key("ArrowRight");
    assert.equal(await focus(), "app:tools");
    await key("Enter");
    await waitFor("tools app", async () => await jsValue(uiTab, "location.hash === '#/app/tools'"));
    await key("Escape");
    await waitFor("focus restoration", async () => await focus() === "app:tools");
    await click("app:youtube");
    await waitFor("YouTube feed", async () => (await jsValue(uiTab, "document.querySelectorAll('.tv-card').length")) >= 12);
    await jsValue(uiTab, "document.querySelector('.tv-card').focus();true");
    await key("ArrowRight");
    assert.equal(await focus(), "youtube:home:testvid0001");
    await click("youtube:home:more");
    await waitFor("cached feed pagination", async () => (await jsValue(uiTab, "document.querySelector('[data-feed-id=\"youtube:home\"]').querySelectorAll('.tv-card').length")) === 18);
    await click("youtube:home:more");
    await waitFor("remote continuation pagination", async () => (await jsValue(uiTab, "document.querySelector('[data-feed-id=\"youtube:home\"]').querySelectorAll('.tv-card').length")) === 30);
    await jsValue(uiTab, "document.querySelector('[data-tv-focus=\"youtube:home:testvid0025\"]').focus();true");
    await key("Enter");
    await waitFor("paginated video route", async () => await jsValue(uiTab, "!!document.querySelector('.video-player-page')"));
    await key("Escape");
    await waitFor("focus restoration across multiple batches", async () => await focus() === "youtube:home:testvid0025");
    await click("youtube:search");
    await waitFor("search input focused", async () => await focus() === "search:query");
    for (const letter of "nature") await click(`keyboard:${letter}`);
    assert.equal(await jsValue(uiTab, "document.querySelector('.yt-search-form input').value"), "nature");
    await jsValue(uiTab, "document.querySelector('.yt-search-form input').focus();true");
    await key("Enter");
    await waitFor("search results", async () => (await jsValue(uiTab, "document.querySelectorAll('.tv-card').length")) === 12);
    await jsValue(uiTab, "document.querySelectorAll('.tv-card')[1].focus();true");
    await key("Enter");
    await waitFor("typed video route", async () => await jsValue(uiTab, "!!document.querySelector('.video-player-page')"));
    await key("Escape");
    await waitFor("async feed focus restoration", async () => await focus() === "youtube:search:nature:testvid0001");
    await click("nav:account");
    await waitFor("unconfigured device login", async () => await jsValue(uiTab, "document.querySelector('.yt-device-status').textContent.includes('尚未配置')"));
    assert.equal(await jsValue(uiTab, "document.querySelectorAll('.yt-remote-image,.yt-cookie-import').length"), 0);
    await jsValue(uiTab, auditCredentials);
    await rpc('storage','put',{key:{namespace:'youtube',key:'client'},value:{clientId:'fixture-client',clientSecret:'fixture-secret'},expected:null});
    await key("Escape");
    await waitFor("leave unconfigured login", async () => await jsValue(uiTab, "!document.querySelector('.yt-device-page')"));
    await click("nav:account");
    await waitFor("QR and case-sensitive device code", async () => await jsValue(uiTab, "document.querySelector('.yt-device-code')?.textContent === 'AbCD-WXYZ'"));
    assert(await jsValue(uiTab, "document.querySelector('.yt-device-qr img').src.startsWith('data:image/svg+xml')"));
    await key("Escape");
    await waitFor("device flow cancelled on back", () => noFlow('youtube'));
    await click("nav:account");
    await waitFor("new device code", async () => await jsValue(uiTab, "!!document.querySelector('.yt-device-code')"));
    google.state.approved=true;
    await waitFor("phone authorization reflected on TV", async () => await jsValue(uiTab, "document.querySelector('.yt-device-status').textContent.includes('连接成功')"));
    await click("nav:youtube");
    await waitFor("signed-in account", async () => await jsValue(uiTab, "document.querySelector('.yt-signed-in')?.textContent.includes('Fixture account')"));
    assert.equal(google.state.refreshes,1);
    assert.equal((await rpc('storage','inspect',{key:{namespace:'youtube',key:'grant'}})).present,true);
    assert.deepEqual(await jsValue(uiTab,'window.__credentialLeaks'),[]);
    await click("youtube:signout");
    await waitFor("sign-out clears account", async () => !(await rpc('storage','inspect',{key:{namespace:'youtube',key:'grant'}})).present);
    await waitFor("signed-out account", async () => await jsValue(uiTab, "document.querySelector('.yt-account-status').textContent.includes('未登录')"));
    await click("nav:home");
    await waitFor("home feeds loaded", async () => (await jsValue(uiTab, "document.querySelectorAll('.tv-card').length")) >= 12);
    await jsValue(uiTab, "(()=>{window.__pad={connected:true,mapping:'standard',axes:[0,0],buttons:Array.from({length:17},()=>({pressed:false}))};Object.defineProperty(navigator,'getGamepads',{value:()=>[window.__pad],configurable:true});document.querySelector('[data-tv-focus=\"app:youtube\"]').focus();return true})()");
    const pad = (button: number) => jsValue(uiTab, `new Promise(resolve=>{window.__pad.buttons[${button}].pressed=true;setTimeout(()=>{window.__pad.buttons[${button}].pressed=false;setTimeout(()=>resolve(true),80)},90)})`);
    await pad(15);
    assert.equal(await focus(), "app:steam");
    await pad(15);
    assert.equal(await focus(), "app:tools");
    await pad(0);
    await waitFor("gamepad A confirms", async () => await jsValue(uiTab, "location.hash === '#/app/tools'"));
    await pad(1);
    await waitFor("gamepad B returns and restores focus", async () => await focus() === "app:tools");
    await jsValue(uiTab, "window.__pad.axes[0]=-1;true");
    await waitFor("left analog stick", async () => await focus() === "app:youtube");
    await jsValue(uiTab, "window.__pad.axes[0]=0;window.__pad.connected=false;true");
    const tvShot = await rpc("chromium", "tab.screenshot", { tab: uiTab });
    await Bun.write("artifacts/test-tv-fixture.png", Buffer.from(tvShot.base64, "base64"));
    assert.deepEqual(await jsValue(uiTab, "window.__uiErrors"), []);
    assert.deepEqual(site.errors, []);
    await goto(uiTab, `${base}/console`);
    await waitFor("feed sessions disposed", async () => (await rpc("chromium", "tab.list", { instance: yt.id })).length === 1);
    console.log("✓ TV navigation/restore, screen keyboard, paging, device-code/QR states, phone authorization/cancel/logout, gamepad D-pad/A/B/analog stick and tab disposal (fixtures)");

    await goto(uiTab, base);
    await waitFor("Steam application registered", async () => await jsValue(uiTab, "!!document.querySelector('[data-tv-focus=\"app:steam\"]')"));
    await jsValue(uiTab, auditCredentials);
    await click("app:steam");
    await waitFor("Steam home login", async () => await focus() === "steam:login");
    await key("Enter");
    await waitFor("Steam QR", async () => await jsValue(uiTab, "!!document.querySelector('.steam-login .yt-device-qr img')"));
    assert.equal(await focus(), "steam:qr-primary");
    steam.state.scanned=true;
    await waitFor("Steam scanned state", async () => await jsValue(uiTab, "document.querySelector('.steam-login-status').textContent.includes('已扫码')"));
    await key("Escape");
    await waitFor("Steam cancellation", () => noFlow('steam'));
    await click("steam:login");
    await waitFor("fresh Steam QR", async () => steam.state.starts===2);
    steam.state.approved=true;
    await waitFor("Steam phone approval", async () => await jsValue(uiTab, "document.querySelector('[data-tv-focus=\"steam:qr-primary\"]').textContent==='查看游戏库'"));
    await click("steam:qr-primary");
    const steamIds = () => jsValue(uiTab, "Array.from(document.querySelectorAll('[data-feed-id=\"steam:library\"] .tv-card')).map(e=>Number(e.getAttribute('data-tv-focus').split(':').pop()))");
    await waitFor("Steam first batch", async () => (await steamIds()).length === 12);
    assert.deepEqual(await steamIds(), Array.from({length:12},(_,i)=>1036-i));
    for(const n of [24,36,37]) { await click("steam:library:more");await waitFor("Steam complete pagination", async () => (await steamIds()).length===n); }
    assert.deepEqual(await steamIds(), [...Array.from({length:35},(_,i)=>1036-i),1000,1001]);
    const steamShot = await rpc("chromium", "tab.screenshot", { tab: uiTab });
    await Bun.write("artifacts/test-steam-library.png", Buffer.from(steamShot.base64, "base64"));
    assert.equal(steam.state.libraryCalls, 1);
    assert.equal(steam.state.refreshes,1);
    assert.deepEqual(await jsValue(uiTab,'window.__credentialLeaks'),[]);
    await jsValue(uiTab, "document.querySelector('[data-tv-focus=\"steam:library:1008\"]').focus();true");
    await key("Enter");
    await waitFor("Steam game details", async () => await jsValue(uiTab, "document.querySelector('.steam-detail h1')?.textContent==='Game 08'"));
    await waitFor("Steam detail focus restored", async () => await focus() === "steam:detail-back");
    await key("Escape");
    await waitFor("Steam multi-batch focus restoration", async () => await focus() === "steam:library:1008");
    await click("nav:home");
    await jsValue(uiTab, "document.querySelector('[data-feed-id=\"steam:library\"]').scrollIntoView();true");
    await waitFor("Steam feed on system home", async () => (await steamIds()).length === 12);
    await click("nav:steam");
    steam.state.failLibrary=true;
    await click("steam:refresh");
    await waitFor("Steam library error", async () => await jsValue(uiTab, "document.body.textContent.includes('Steam 游戏库请求失败')"));
    steam.state.failLibrary=false;steam.state.empty=true;
    await click("steam:library:retry");
    await waitFor("Steam empty library", async () => await jsValue(uiTab, "!!document.querySelector('.steam-page .tv-feed-empty')"));
    await click("steam:signout");
    await waitFor("Steam logout removes library", async () => !(await rpc('storage','inspect',{key:{namespace:'steam',key:'grant'}})).present&&await jsValue(uiTab, "!document.querySelector('[data-feed-id=\"steam:library\"]')"));
    steam.state.approved=false;steam.state.denied=true;
    await click("steam:login");
    await waitFor("Steam refusal state", async () => await jsValue(uiTab, "document.querySelector('.steam-login-status').textContent.includes('拒绝')"));
    steam.state.denied=false;steam.state.expired=true;
    await click("steam:qr-primary");
    await waitFor("Steam expiry state", async () => await jsValue(uiTab, "document.querySelector('.steam-login-status').textContent.includes('过期')"));
    assert.deepEqual(await jsValue(uiTab,'window.__credentialLeaks'),[]);
    await goto(uiTab, `${base}/console`);
    await waitFor("Steam and home cleanup", async () => (await rpc("chromium", "tab.list", { instance: yt.id })).length === 1);
    await waitFor('WebSocket detach disposes all credential runtime tabs',async()=>(await rpc('chromium','tab.list',{instance:steamBrowser.id})).length===1);
    assert.deepEqual(google.errors,[]);assert.deepEqual(steam.errors,[]);
    console.log("✓ Steam QR/scan/approval/cancel/denial/expiry, complete owned library sorted by last played, paging, details/focus, home feed, retry/empty/logout");
    await playerTest(rpc, uiTab);
  } catch (error) {
    console.error("TV diagnostic:", await jsValue(uiTab, "({url:location.href,focus:document.activeElement?.getAttribute('data-tv-focus'),text:document.body.innerText})").catch(String));
    console.error("Fixture errors:", site.errors,google.errors,steam.errors);
    for (const info of await rpc("chromium", "tab.list", { instance: yt.id })) {
      console.error("YouTube document:", await jsValue(info.ref, "({url:location.href,scrollY,visibility:document.visibilityState,videos:window.ytInitialData?.contents?.length})").catch(String));
    }
    throw error;
  } finally { await site.close();await google.close();await steam.close(); }
  await rpc('chromium','instance.delete',{instance:googleBrowser.id});
  await rpc('chromium','instance.delete',{instance:steamBrowser.id});
  await rpc("chromium", "instance.delete", { instance: yt.id });
  await rpc("chromium", "instance.delete", { instance: ui.id });
  assert.deepEqual(await rpc("chromium", "instance.list"), []);
  assert.deepEqual(await rpc("wine", "instance.list"), []);
  passed = true;
} finally {
  uiControl?.close();
  try { process.kill(-service.pid, "SIGTERM"); } catch { service.kill(); }
  await Promise.race([service.exited, Bun.sleep(10000)]);
  if (service.exitCode === null) { try { process.kill(-service.pid, "SIGKILL"); } catch {} }
  await fixture.stop(true);
  closeSync(logFd);
  if (!passed) console.error(await readFile(log, "utf8").catch(() => "No server log"));
  if (passed) await rm(temporary, { recursive: true, force: true });
  else console.error(`Diagnostic files: ${temporary}`);
}
