#!/usr/bin/env bun
// Test-only CDP interception. Production frontend and backend run unchanged;
// external Google/YouTube documents are replaced inside one isolated profile.
import { readFile } from "node:fs/promises";
import { join } from "node:path";

export async function youtubeFixture(profile: string) {
  const [port, path] = (await readFile(join(profile, "DevToolsActivePort"), "utf8")).trim().split("\n");
  const socket = new WebSocket(`ws://127.0.0.1:${port}${path}`);
  let next = 0;
  const pending = new Map<number, { resolve: (v: any) => void; reject: (e: unknown) => void; timer: Timer }>();
  const errors: unknown[] = [];
  const send = (method: string, params: unknown = {}, sessionId?: string): Promise<any> => new Promise((resolve, reject) => {
    const id = ++next;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`Fixture CDP timeout: ${method}`)); }, 10000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params, sessionId }));
  });
  async function event(message: any) {
    if (message.method === "Target.attachedToTarget") {
      const session = message.params.sessionId;
      await send("Fetch.enable", { patterns: [
        { urlPattern: "https://www.youtube.com/*", resourceType: "Document" },
        { urlPattern: "https://accounts.google.com/*", resourceType: "Document" },
      ] }, session);
      await send("Runtime.runIfWaitingForDebugger", {}, session);
    }
    if (message.method === "Fetch.requestPaused") {
      const { requestId, request } = message.params;
      const url = new URL(request.url);
      const cookies = await send("Storage.getCookies");
      const signedIn = url.pathname === "/signin" || cookies.cookies.some((c: any) => c.name === "fixture-youtube-session");
      const page = url.hostname === "accounts.google.com"
        ? `<!doctype html><title>Google fixture</title><style>body{font:24px sans-serif;padding:60px}input,button{font:24px sans-serif;padding:15px;margin:20px}</style><h1>Google sign-in fixture</h1><form action="https://www.youtube.com/signin"><input name="identifier" aria-label="Email" placeholder="Email"><button>Next</button></form>`
        : `<!doctype html><title>YouTube fixture</title><script>
          const video = i => ({videoRenderer:{videoId:'testvid'+String(i).padStart(4,'0'), title:{simpleText:'Fixture video '+i}, ownerText:{simpleText:'Fixture channel'},lengthText:{simpleText:'4:20'},thumbnail:{thumbnails:[]}}});
          window.ytcfg={get:key=>({LOGGED_IN:${signedIn},ACCOUNT_NAME:'Fixture account'})[key]};
          window.ytInitialData={contents:Array.from({length:18},(_,i)=>video(i)),more:{continuationItemRenderer:{}}};
          window.addEventListener('scroll',()=>{window.ytInitialData.contents=Array.from({length:30},(_,i)=>video(i));delete window.ytInitialData.more;document.querySelector('ytd-continuation-item-renderer')?.remove()});
          </script><h1>YouTube fixture</h1><div style="height:1800px"></div><ytd-continuation-item-renderer>More</ytd-continuation-item-renderer>`;
      await send("Fetch.fulfillRequest", {
        requestId, responseCode: 200,
        responseHeaders: [{ name: "Content-Type", value: "text/html; charset=utf-8" },
          ...(url.pathname === "/signin" ? [{ name: "Set-Cookie", value: "fixture-youtube-session=yes; Domain=.youtube.com; Path=/; Secure; HttpOnly; Max-Age=3600" }] : [])],
        body: Buffer.from(page).toString("base64"),
      }, message.sessionId);
    }
  }
  socket.addEventListener("message", ({ data }) => {
    const message = JSON.parse(String(data));
    const call = pending.get(message.id);
    if (call) {
      pending.delete(message.id); clearTimeout(call.timer);
      if (message.error) call.reject(message.error); else call.resolve(message.result);
    } else void event(message).catch(error => errors.push(error));
  });
  await new Promise<void>((resolve, reject) => { socket.onopen = () => resolve(); socket.onerror = reject; });
  await send("Target.setAutoAttach", { autoAttach: true, waitForDebuggerOnStart: true, flatten: true });
  return {
    errors,
    async close() {
      await send("Target.setAutoAttach", { autoAttach: false, waitForDebuggerOnStart: false, flatten: true });
      for (const call of pending.values()) { clearTimeout(call.timer); call.reject(new Error("Fixture closed")); }
      pending.clear(); socket.close();
    },
  };
}
