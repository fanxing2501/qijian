# HTPC Next

Scala 3 / JDK 25 HTTP capability service and Scala.js + Laminar management UI.

## Modules

- **Chromium**: independently owned headless browser processes/profiles, tabs, navigation, history, JavaScript, screenshots and complete Cookie records. Direct CDP implementation, no Node/Playwright service.
- **Wine**: isolated win64 prefixes and long-lived Windows DLL hosts. Requests are serialized per instance; timeout terminates the failed worker rather than leaving an unbounded call running.
- **Sway**: direct Unix IPC, typed snapshots/window references, focus/fullscreen/move/close, workspaces, output power/modes and compositor events.
- **Frontend**: resource management, interactive CDP/DLL console, Cookie editing, screenshots and display controls. Business composition belongs here.

Kiosk browser startup and Steam integration are outside this foundation release.

## Development

Requirements: Groot, Scala CLI 1.17.1+, Bun, JDK 25 through `groot use-jdk`. All ordinary tasks run through `jobs.yaml`:

```sh
groot jobs build
groot jobs native-build
groot jobs integration-test
groot jobs run
```

Open **http://127.0.0.1:8787**. `groot jobs run` builds the frontend and runs the backend, using `.runtime/dev` for development profiles and automatically discovering the locally extracted Wine runtime. To expose it on another interface, set `HTPC_HOST` explicitly.

Configuration:

| Environment variable | Default / meaning |
| --- | --- |
| `HTPC_HOST`, `HTPC_PORT` | `127.0.0.1`, `8787` |
| `HTPC_DATA` | `~/.local/share/htpc-next` |
| `HTPC_WEB` | `dist/web` |
| `HTPC_CHROMIUM` | executable discovered on PATH |
| `HTPC_WINE`, `HTPC_WINESERVER` | executable paths; both must belong to the same Wine installation |
| `HTPC_DLL_HOST` | `dist/native/dll-host.exe` |
| `SWAYSOCK` | existing Sway IPC socket |

For a cached Chrome for Testing executable, point `HTPC_CHROMIUM` at its full path. Chromium runs with its sandbox enabled. Instances use new, service-owned data directories, not your desktop browser's profile.

`groot jobs wine-runtime` optionally extracts Ubuntu 26.04 Wine packages into `.tools/wine`; it does not install system packages. It prints the executable paths to use for development. `native-build` downloads checksum-pinned Zig 0.15.2 and cross-compiles the x64 Windows host and example DLL. Runtime and compiler downloads stay in ignored `.tools/`.

## Type-safe protocol

`shared/src/Protocol.scala` is compiled for JVM and JavaScript:

```scala
val op: Request[ChromiumError, Vector[TabInfo]] =
  ChromiumOp.ListTab(browserId)

api.call(op) // Future[Either[CallError[ChromiumError], Vector[TabInfo]]]
```

`ChromiumId`, `TabId` and `WineId` have distinct phantom resource kinds. Each request constructor carries error/result codecs. Decoding produces an existential `PackedRequest` retaining those witnesses; the interpreter dispatches without `Any` or unchecked protocol casts.

Arbitrary JS and DLL output is checked at the runtime boundary:

```scala
api.eval(tab, JsProgram[Account](source, summon[Decoder[Account]]))
api.execute(instance, signFunction, SignInput(...))
```

The compiler checks the typed program/function and input; decoders validate the external result. JavaScript `undefined` is distinct from JSON `null`. Non-JSON results (e.g. BigInt/functions/cyclic objects) are rejected rather than silently coerced.

## HTTP

```
POST /api/v1/system
POST /api/v1/chromium
POST /api/v1/wine
POST /api/v1/sway
GET  /api/v1/events
GET  /api/v1/health
```

Envelope:

```json
{"id":"request-1","op":"tab.goto","args":{"tab":{"instance":"browser-id","tab":"target-id"},"navigation":{"url":"https://example.com","waitUntil":"DOMContentLoaded","timeoutMs":30000}}}
```

Responses use `outcome: "result" | "error" | "protocol-error"` and `payload`. Domain failures preserve their module-specific error type. Malformed requests/unknown operations return HTTP 400; valid requests return a typed result/error envelope. Request IDs correlate replies; mutating calls are **not automatically retried**.

### Chromium operations

| `op` | `args` |
| --- | --- |
| `instance.list` | `{}` |
| `instance.new` | `{name, profile, proxy, language, width, height}` |
| `instance.get`, `instance.delete`, `tab.list`, `tab.new`, `cookie.list` | `{instance}` |
| `tab.delete`, `tab.back`, `tab.forward`, `tab.reload`, `tab.screenshot` | `{tab}` |
| `tab.goto` | `{tab, navigation: {url, waitUntil, timeoutMs}}` |
| `tab.eval` | `{tab, script, options: {awaitPromise, timeoutMs}}` |
| `cookie.set` | `{instance, cookies: [...]}` |
| `cookie.delete` | `{instance, selector: {name, domain, path, partitionKey}}` |

The Scala client supplies complete options including defaults. String enums are case-sensitive: `Started`, `DOMContentLoaded`, `Load`. The screenshot result contains a PNG MIME type and base64 data. Cookie fields preserve HttpOnly, SameSite, expiration and partition identity.

An instance exclusively owns its profile. `profile: null` is temporary; deletion cleans it up. A named profile is retained for reuse. Closing an instance requests a graceful browser exit first, so persisted cookies can flush. No instances are automatically relaunched after backend restart; named profiles remain on disk. Operation timeouts cover active execution, not time spent waiting for the instance lock.

### Wine operations

`instance.list`, `instance.new` (`{name, profile}`), `instance.get`, `instance.delete`, `execute`:

```json
{
  "id":"dll-1",
  "op":"execute",
  "args":{
    "instance":"wine-id",
    "invocation":{
      "dll":"/absolute/path/to/example-adapter.dll",
      "function":"echo_json",
      "arguments":{"hello":"中文"},
      "freeFunction":"free_json"
    },
    "options":{"timeoutMs":15000}
  }
}
```

The supplied **json-utf8-v1 adapter ABI** is:

```c
const char * __cdecl invoke(const char *utf8_json);
void __cdecl release(const char *result); // optional, if the DLL owns an allocation
```

`native/example-adapter.c` demonstrates it. This ABI is explicit: a vendor DLL with a different signature or a .NET API needs a thin adapter implementing these exports. The actual proprietary music-signing DLL is not included or reverse-engineered. Supply its ABI/adapter to integrate it.

The host caches loaded modules, supports Unicode DLL paths and redirects DLL stdout to its log. It uses bounded binary frames; returned JSON is validated by the JVM and optionally by the typed client. Unknown exports and invalid JSON preserve the worker; a timeout/crash marks it failed. Calls are never automatically retried. Delete/recreate failed instances explicitly. Each service-owned prefix has at most one live managed instance; prefix-scoped wineserver cleanup does not stop other Wine instances.

### Sway operations

`snapshot`, `window.focus`, `window.fullscreen`, `window.move`, `window.close`, `workspace.activate`, `output.power`, `output.mode`.

Window references include compositor session identity and container ID. Stale sessions/missing windows are rejected. Output modes must appear in the output's advertised modes. Commands return an IPC success acknowledgement, not proof of a rendered frame.

### Events

SSE connections begin with `event: sync`; the client refreshes snapshots on each connection/reconnection. `event: change` carries `{epoch, sequence, module, payload}`. Events are invalidation hints, not a persistent event log. The frontend subscribes before taking snapshots and debounces invalidations. Events do not contain Cookie or DLL argument values.

## Verification

- JVM tests check compile-time GADT/resource constraints, malformed wire input, error/result decoding, cookie fidelity, profile validation and actual Unix socket framing.
- Integration tests launch an isolated HTTP backend and actual headless Chromium against a local fixture site. They check navigation/history, JS/Promise errors/timeouts, cookie isolation/persistence, screenshot capture, and the built Laminar UI creating/deleting real instances.
- When Wine is installed or the local runtime has been extracted, integration tests execute the compiled DLL, verify Unicode values and memory release, and exercise error/timeout cleanup.
- Sway transport is tested against Unix socket fixtures. Connecting to a physical compositor requires `SWAYSOCK`.

`artifacts/test-ui.png` is an integration screenshot. Failed test diagnostics are retained under `.runtime/integration-*`; successful test instances and profiles are removed.

## Packaging and delivery

```sh
groot jobs package
groot jobs upload
groot jobs deploy
groot jobs status
```

The package includes the JVM assembly, static frontend and Windows DLL host/example. SHA-256 of the reproducible archive is the artifact version; metadata is written to `artifacts/release.env`. Upload/download URLs live in `config.env`. `deploy` downloads from S3, verifies SHA-256, and installs a user unit named `groot-htpc-next.service` pointing at that immutable release. Deployment is a separate, explicit job.

Set runtime executable paths and `SWAYSOCK` in `~/.config/htpc-next/runtime.env` before deploying. For Ubuntu's Chromium snap, set `HTPC_DATA` to a snap-accessible location such as `/home/your-user/snap/chromium/common/htpc-next`. That file is not packaged or overwritten. The deployment job does not configure a display session, kiosk browser, system unit, or user lingering.

## Repository layout

```
shared/        platform-independent protocol and models
server/        HTTP server, CDP, Wine lifecycle, Sway IPC
client/        Laminar UI and typed HTTP client
native/        Windows DLL host and example adapter
scripts/       jobs, integration and delivery tools
jobs.yaml      build/test/package/upload/deploy graph
config.env     JDK, artifact endpoints and deployment defaults
```

This project was initialized in a new worktree (`feat/foundation`); there was no upstream branch to pull. The existing Hearth project is independent.
