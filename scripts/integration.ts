#!/usr/bin/env bun
import { strict as assert } from "node:assert";
import { mkdir, rm, readFile } from "node:fs/promises";
import { openSync, closeSync } from "node:fs";
import { resolve, join } from "node:path";
import { homedir } from "node:os";

const root = process.cwd();
const temporary = resolve(root, ".runtime", `integration-${crypto.randomUUID()}`);
await mkdir(temporary, { recursive: true });
await mkdir("artifacts", { recursive: true });
const probe = Bun.serve({ port: 0, hostname: "127.0.0.1", fetch: () => new Response("reserved") });
const port = probe.port!;
await probe.stop(true);
const base = `http://127.0.0.1:${port}`;
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
    return new Response(`<!doctype html><html><head><title>Fixture ${path}</title></head><body><h1>${path}</h1></body></html>`, {
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
  },
  stdout: logFd, stderr: logFd, detached: true,
});

async function waitFor<A>(label: string, fn: () => Promise<A | false>, timeout = 30000): Promise<A> {
  const deadline = Date.now() + timeout;
  let error: unknown;
  while (Date.now() < deadline) {
    try { const value = await fn(); if (value !== false) return value; } catch (e) { error = e; }
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
const jsValue = async (tab: unknown, script: string) => (await evalJS(tab, script)).Value.value;
const goto = (tab: unknown, url: string) => rpc("chromium", "tab.goto", { tab, navigation: { url, waitUntil: "DOMContentLoaded", timeoutMs: 15000 } });
const execute = (instance: string, name: string, input: unknown, timeoutMs = 15000, freeFunction: string | null = null) => ({
  instance, invocation: { dll: resolve("dist/native/example-adapter.dll"), function: name, arguments: input, freeFunction }, options: { timeoutMs },
});
let passed = false;
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
  await goto(uiTab, base);
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
  await rpc("chromium", "instance.delete", { instance: ui.id });
  assert.deepEqual(await rpc("chromium", "instance.list"), []);
  assert.deepEqual(await rpc("wine", "instance.list"), []);
  console.log("✓ actual Scala.js/Laminar UI mounts, decodes typed APIs, creates/deletes browsers and switches modules");
  passed = true;
} finally {
  try { process.kill(-service.pid, "SIGTERM"); } catch { service.kill(); }
  await Promise.race([service.exited, Bun.sleep(10000)]);
  if (service.exitCode === null) { try { process.kill(-service.pid, "SIGKILL"); } catch {} }
  await fixture.stop(true);
  closeSync(logFd);
  if (!passed) console.error(await readFile(log, "utf8").catch(() => "No server log"));
  if (passed) await rm(temporary, { recursive: true, force: true });
  else console.error(`Diagnostic files: ${temporary}`);
}
