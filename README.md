# HTPC Next

Scala 3 / JDK 25 HTTP capability service and a Scala.js + Laminar television frontend.

## Modules

- **Chromium**: independently owned headless browser processes/profiles, tabs, navigation, history, JavaScript, screenshots and complete Cookie records. Direct CDP implementation, no Node/Playwright service.
- **serverStorage**: provider-neutral, namespaced JSON KV with atomic owner-only persistence, revision-based compare-and-set and direct transfers to/from Chromium isolated worlds.
- **Wine**: isolated win64 prefixes and long-lived Windows DLL hosts. Requests are serialized per instance; timeout terminates the failed worker rather than leaving an unbounded call running.
- **Sway**: direct Unix IPC, typed snapshots/window references, focus/fullscreen/move/close, workspaces, output power/modes and compositor events.
- **Host services**: configured user-service lifecycle and loopback Chromium/CEF target discovery/WebSockets. Native application adaptation belongs to the frontend App.
- **Frontend**: Android TV–inspired discovery screen, application dock, recommendation rows, directional focus navigation, YouTube and Steam applications. The capability console remains available at `/console`. Business composition belongs here.

The desktop release adds a dedicated kiosk, a DRM Sway session and a hidden official Steam client. Native-client QR login, installation, pause/resume and Sekiro startup have been exercised against the real client; phone approval is required for first login.

## Development

Requirements: Groot, Scala CLI 1.17.1+, Bun, JDK 25 through `groot use-jdk`. All ordinary tasks run through `jobs.yaml`:

```sh
groot jobs build
groot jobs native-build
groot jobs integration-test
groot jobs run
```

Open **http://127.0.0.1:8787** or **http://<HTPC-LAN-IP>:8787** from another computer. `groot jobs run` builds the frontend and runs the backend, listening on `0.0.0.0`, using `.runtime/dev` for development profiles and automatically discovering the locally extracted Wine runtime.

Configuration:

| Environment variable | Default / meaning |
| --- | --- |
| `HTPC_HOST`, `HTPC_PORT` | `0.0.0.0`, `8787` |
| `HTPC_DATA` | `~/.local/share/htpc-next` |
| `HTPC_WEB` | `dist/web` |
| `HTPC_CHROMIUM` | executable discovered on PATH |
| `HTPC_WINE`, `HTPC_WINESERVER` | executable paths; both must belong to the same Wine installation |
| `HTPC_DLL_HOST` | `dist/native/dll-host.exe` |
| `SWAYSOCK` | existing Sway IPC socket |
| `HTPC_HOST_SERVICES` | `~/.config/htpc-next/host-services.json` |

For a cached Chrome for Testing executable, point `HTPC_CHROMIUM` at its full path. Chromium runs with its sandbox enabled. Instances use new, service-owned data directories, not your desktop browser's profile.

`groot jobs wine-runtime` optionally extracts Ubuntu 26.04 Wine packages into `.tools/wine`; it does not install system packages. It prints the executable paths to use for development. `native-build` downloads checksum-pinned Zig 0.15.2 and cross-compiles the x64 Windows host and example DLL. Runtime and compiler downloads stay in ignored `.tools/`.

## Television applications

### Visual system

The UI is **Scala.js + Laminar**, styled with **Tailwind CSS 4.3.3**. `client/styles/tv.css` defines the palette, reusable TV components and responsive behavior; utility classes can also be written directly in Scala `cls` strings. Tailwind explicitly scans `client/src`, so use complete utility names rather than interpolated fragments. `groot jobs css-build` generates the local, minified `dist/web/tv.css`; no CDN or runtime CSS compiler is used. `bun.lock` pins the build dependencies (`groot jobs lock` updates it; normal installation is frozen).

The design follows a **10-foot, content-first interface**: dark charcoal surfaces, warm-white type, restrained amber focus rings, cinematic imagery, horizontal video rows and portrait game covers. Focus is always visible for remote/gamepad input, with fixed controller hints and reduced-motion support. Sizing scales from 720p/1080p to 4K; narrow screens keep navigation and login controls reachable. The infrastructure console uses its own HTML/CSS entry to avoid Tailwind Preflight changing its layout.

References: [Android TV design foundations](https://developer.android.com/design/ui/tv/guides/foundations/design-for-tv), [focus states](https://developer.android.com/design/ui/tv/guides/styles/focus-system). The bundled lake photograph comes from [Unsplash](https://images.unsplash.com/photo-1470770841072-f978cf4d019e) and is served locally with the release.

The extension contract in `client/src/tv/App.scala` is:

```scala
trait App:
  def metadata: AppMetadata
  def feeds: LazyList[FeedItem]
  def mainPage: Page
```

`FeedItem` describes a **horizontal recommendation row**; `FeedCard` is an individual video/card. `LazyList` makes definitions lazy, while `FeedSource.open()` creates an independently disposable asynchronous feed session. Loading starts near the viewport; pagination uses typed cursors, deduplication, empty/error states and retry. Navigating away closes owned tabs. Register applications in `AppRegistry` in `TvShell.scala`.

`Page.render(PageContext)` creates a fresh Laminar view for each navigation. `RoutedApp` pairs an associated `Route` type with its codec and resolver; serialized hash routes support reload/deep links. Standard Gamepad mapping supports D-pad/left stick, A (south button) to confirm, B (east button) to return and Start to open home. Held directions repeat; confirmation is edge-triggered. Search has an on-screen alphanumeric keyboard and Chinese topic shortcuts. Keyboard arrows/Enter/Escape also work. Stable focus IDs restore asynchronously loaded cards across multiple batches. Use `http://localhost:8787` on the HTPC for Gamepad API access; another computer needs HTTPS for gamepad input because browsers restrict this API to secure contexts.

### YouTube

- **Login** uses Google's official OAuth Device Authorization flow. The TV displays a locally generated QR code for the exact Google verification URL, the case-sensitive user code and expiry countdown. The user scans with a phone, enters the code and authorizes; the TV updates automatically. Login has no embedded browser, password input or Cookie import.
- The **frontend App** implements device authorization, polling intervals/`slow_down`, token renewal and API composition. It executes HTTP requests inside server Chromium through CDP. `serverStorage` injects client configuration/grants into an isolated execution world and captures private results directly; only public data/status returns to the TV browser. Credentials live under `HTPC_DATA/storage/youtube`, survive restarts, and never enter TV localStorage/sessionStorage. Disconnecting an account writes a versioned deletion without revoking other installations sharing a Google project.
- Signed-in content uses the YouTube Data API: account/channel identity and the latest upload from each subscribed channel, with subscription pagination. Google's public API does not expose the personalized YouTube homepage recommendation algorithm; the UI labels these rows as subscriptions.
- Public discovery/search, topic shortcuts, video links and continuation loading use frontend-owned extraction in an independent `htpc-youtube-public` headless profile. API requests use a separate `htpc-youtube-account` runtime. The extractor handles classic renderers and current lockup view models; extraction is centralized in `client/src/youtube/YouTubeData.scala`.
- Playback uses the shared full-screen `video-player` with an official YouTube iframe backend. Device OAuth does not log the iframe's browser into youtube.com. Restricted/private video playback may require a separate playback-browser session. Embed restrictions and player errors are shown explicitly.

### Internal video-player

`client/src/player` provides one **full-screen-only** playback page and a typed controller for every video App. It is a reusable capability, not a home-screen application: it has no AppRegistry entry, home tile or feed. The calling App keeps its own typed route and returns `VideoPlayer.page(request)` from its resolver.

```scala
import htpc.client.player.*

val request = VideoRequest(
  parts = episodes.map { episode =>
    VideoPart(episode.id, episode.title, signal =>
      resolveEpisode(episode.id, signal).map(url => MediaSource(url, MediaFormat.Hls)))
  },
  startIndex = 0,
  startPosition = 0,
  autoNext = true
)
val page = VideoPlayer.page(request)
```

- `VideoRequest` supplies an explicitly ordered, nonempty part list and optional starting part/time. IDs identify **parts**, so repeated videos in a playlist may have distinct part IDs. `YouTubePlayer.part` and `YouTubePlayer.request(videos, startIndex)` adapt single videos and ordered YouTube lists. A single-video request ends after that video; unrelated recommendations are not treated as parts.
- `VideoPart.resolve(AbortSignal)` runs only when that part is opened/retried. Apps own authentication, entitlement checks and fresh-source resolution; server-side credentials remain with their existing provider runtimes. Resolved media URLs stay in memory rather than navigation hashes or persistent storage. The abort signal and a generation fence prevent an old resolution or engine callback from replacing a newer part or restarting playback after exit.
- `VideoSource.create(host, report)` supplies a `VideoBackend`. Construction is inert; `mount()` starts it after the controller takes ownership. Backends implement play/pause/seek/volume/speed/close and report typed `VideoEvent` values, including actual EOF. `Playback.state` exposes the part, phase, position/duration, capabilities, settings and error message. Callers may use the controller independently of the standard page.
- Built-in backends are browser-native file playback (`MediaSource`, MP4/WebM subject to browser codec support), HLS/DASH through locally packaged **Shaka Player 5.2.13**, and YouTube's official IFrame API. Provider-specific or native playback engines can implement the same backend interface. Network media must be reachable with suitable CORS or a provider-managed same-origin media endpoint; the player does not extract or bypass protected streams.
- A real EOF advances **once** to the next part when auto-next is enabled. The final part stops without looping. Pausing, buffering, errors and leaving the page do not advance the queue. Errors retain the current part and expose retry, which resolves a fresh source. Previous/next, part selection, auto-next toggle, seek ±10s, speed and volume share one controller interface.
- Playback occupies the entire viewport (the TV kiosk already owns the physical fullscreen surface). Controls and the horizontal part selector overlay the picture. Controls hide after inactivity and reappear on keyboard/gamepad/pointer input or a non-playing state. Focus is confined to the player; B/back closes the page, disposes the engine and restores the caller's focus. Part switches do not create browser-history entries.

`groot jobs integration-test` includes a separate, **unshipped** Scala.js player fixture. Chromium verifies real file/HLS/DASH decoding, pause/seek, auto-next and final EOF, start position, settings across part changes, controller wake-up, focus containment/restoration, deferred/cancelled resolutions, duplicate/late EOF, fresh-source retry and the YouTube loader/adapter contract. `ffmpeg` creates local media fixtures; tests use isolated, muted Chromium playback and do not drive the physical television. `groot jobs player-test` runs just these checks against an existing capability server (`HTPC_PLAYER_TEST_BASE`, default `http://127.0.0.1:8787`), creating and deleting its own ephemeral browser instance.

#### Configure Google TV credentials once

1. Create/select a project in [Google Cloud Console](https://console.cloud.google.com/), and enable **YouTube Data API v3**.
2. Under **Google Auth Platform → Branding**, set the application name and contact email. Under **Audience**, add your Google account as a test user while the application is in Testing.
3. Under **Clients → Create client**, choose **TVs and Limited Input devices**. Request the read-only scope `https://www.googleapis.com/auth/youtube.readonly`.
4. Store the client JSON with `StorageOp.Put(StorageKey("youtube", "client"), json)` (`POST /api/v1/storage`, op `put`). Accepted formats are Google's `installed` object, flat `client_id`/`client_secret`, and flat `clientId`/`clientSecret`. Reopen the login page; no server restart is needed. The deployment's one-time `migrate-storage.ts` imports the previous `google-tv-client.json` and `oauth/*.json` files into absent KV entries, retaining originals and never overwriting current entries or sign-out tombstones.

This is developer configuration, not an end-user setup step. A missing client shows a clear setup screen instead of a nonfunctional QR code. See [Google's device authorization documentation](https://developers.google.com/identity/protocols/oauth2/limited-input-device).

### Steam

`client/src/steam/Steam.scala` implements `RoutedApp` (and the same `App` contract). Steam appears in the application dock, top navigation and home feeds.

- **Login:** choose “扫码登录 Steam” on the Steam homepage, then use **Steam Mobile App → Steam Guard** to scan and confirm. The frontend shows the real QR challenge, scan/confirmation state, refresh and expiry. This uses Steam's `IAuthenticationService/BeginAuthSessionViaQR` and `PollAuthSessionStatus` as a WebBrowser session. No API key, password entry, Steam desktop client or Big Picture mode is required.
- `client/src/steam/SteamLoginSession.scala` implements polling intervals, challenge rotation, cancellation and five-minute local expiry (Steam may expire earlier). All site traffic runs in `htpc-steam-account` Chromium via the instance WebSocket. Tokens are persisted in `serverStorage` at `steam/grant`. WebBrowser renewal is frontend-authored logic using `jwt/finalizelogin`, validated community-cookie transfer and server-side CDP cookie capture. The JVM has no Steam provider, endpoint or token logic. Signing out clears the saved session and pending logins.
- **Library:** `IPlayerService/GetOwnedGames/v1` is called for the authenticated account, with app information, played free games, free subscriptions and unvetted apps included. This is the account's owned-game library, including uninstalled games; family-borrowed licenses are not requested. The Scala.js service decodes, deduplicates and sorts the complete snapshot by `rtime_last_played` descending before slicing it into feed batches. Unplayed/unknown-timestamp games come last, with name/AppID as deterministic tie-breakers. There is no “recent games only” limit or fixed library cap.
- Game cards show cover art, last-played date and total playtime. Selecting a card opens details and local install/download/play controls; returning restores the selected card across feed batches. The page also supports refresh, empty/error states, account switching and logout. Cached library data is invalidated when the account changes.
- Steam's authentication endpoints are not a stable public third-party OAuth contract. Protocol references: [Steam authentication protobufs](https://github.com/SteamDatabase/Protobufs/blob/master/steam/steammessages_auth.steamclient.proto), [player service](https://github.com/SteamDatabase/Protobufs/blob/master/webui/service_player.proto), and [steam-session's WebBrowser renewal behavior](https://github.com/DoctorMcKay/node-steam-session#getwebcookies). WebBrowser authorization is separate from native-client authorization.

#### Per-application Gamescope profiles

Game details include **画面设置**, navigable entirely with the controller. Enable Gamescope, choose native/720p/900p/1080p/1440p/4K render resolution, a frame cap, linear/FSR 1/integer scaling, FSR sharpness (0 sharpest–20 softest), and the MangoApp FPS/frame-time HUD. Save once; settings take effect on the next launch from 栖间. Output dimensions follow the active television output. The profile advertises the game's virtual display size; games with saved graphics settings may need the matching resolution and fullscreen mode selected in-game. FSR 1 is spatial upscaling, not frame generation; choose a render resolution lower than the output to reduce GPU work.

Install `gamescope` and optional `mangoapp` through the host's package manager. `groot jobs deploy` ships the generic `qijian-gamescope` launcher through the verified S3 release and links it under `~/.local/bin`. The system API exposes `game-display.status` and `game-display.prepare` with typed `GameDisplaySettings`/`GameDisplayLaunch`; prepare returns an argv prefix, without launching a process. `GameDisplayProfiles` persists per-application profiles in typed serverStorage (`game-display/<application-key>`), using revision CAS. Apps append their own executable and arguments to the returned prefix.

Steam's frontend adapter wraps the client's existing launch options, keeps an account-specific recovery record, rejects concurrent edits, and restores the original options on the next 栖间 launch with Gamescope disabled. Steam still owns Proton, cloud saves, Steam Input and process lifetime. The outer Gamescope window is identified by its process's `HTPC_APPLICATION` label in Sway snapshots, rather than a shared `gamescope` title. This works across frontend reconnections and keeps the existing fullscreen/return-to-home coordination.

The launcher is also usable for non-Steam applications, for example:

```sh
qijian-gamescope local-game --backend sdl -f \
  -w 1280 -h 720 -W 1920 -H 1080 -r 60 --framerate-limit 60 -F fsr \
  -- /absolute/path/to/game
```

Profiles use the SDL nested backend over Sway's XWayland. Both the direct Wayland backend and SDL's Wayland driver left some applications invisible on this host; the SDL X11 driver was verified with Proton. Sway remains the physical compositor and continues to manage 栖间 and other applications. `HTPC_GAMESCOPE` and `HTPC_GAMESCOPE_LAUNCHER` can override the runtime paths; `HTPC_GAMESCOPE_SDL_DRIVER` overrides the launcher's default `x11` driver.

`groot jobs gamescope-smoke` is an explicit physical-display acceptance job. It runs a non-Steam animated `ffplay` test pattern inside Gamescope, checks application/window identity, then operates the real game-detail controls to save and reload a 900p/30 FPS/FSR/HUD profile and runs the native launch/exit/focus smoke test. It requires the television to be available and leaves the tested profile saved. `HTPC_TEST_APP` selects an installed Steam game (default 814380); `HTPC_CAPTURE_TOOL` optionally supplies `grim` for compositor screenshots. The ordinary build/integration jobs never launch games on the TV.

Native Wayland clients can opt into `--expose-wayland` (verified with `weston-simple-egl`), but this Gamescope/MangoApp combination had HUD compatibility problems with that mode. Managed profiles use XWayland for clients and the outer compositor window.

#### Native client operation

“本机下载与运行” opens the native client's QR in 栖间. The official client retains its own credentials in the HTPC user's Steam profile; only the QR image, public account identity and game state are returned over the generic CDP bridge. Expired/blurred placeholder QRs are withheld, and “刷新二维码” operates the native refresh control. The Steam homepage distinguishes library authorization from native-client login. No Big Picture interface is used. `NativeSteamScripts.scala` adapts the private `SteamClient` APIs, so Steam updates can require adapter changes.

Game details offer disk selection, download/pause/resume, license text with controller-readable pages, launch/stop and explicit uninstall confirmation. First-run component/shader preparation shows progress and can be cancelled inside 栖间; preparation is distinct from a running game. The native account must match the library account. Sway keeps class `steam` windows on `__background`, 栖间 on `home`, and `steam_app_<appid>` windows fullscreen on `game`. The kiosk (`?display=1`) coordinates the return to `home` using client running state and Sway windows; the Steam launcher PID is not treated as the game lifetime. Gamepad navigation pauses during gameplay. Games that do not identify their windows using `steam_app_<appid>` need additional matching support.

`groot jobs desktop-deploy` packages the `desktop/` scripts/configuration through S3 and installs `groot-htpc-desktop.service`, `groot-htpc-steam.service` and `groot-htpc-kiosk.service`. Sway owns the physical DRM output through the system's existing seatd service. `HTPC_DRM_DEVICE` in `config.env` identifies the stable GPU PCI symlink, resolved before passing it to wlroots. On this host, deployment stops/disables the superseded `groot-hearth-display.service` and `groot-hearth-compositor.service`; other Hearth services remain independent. Startup readiness publishes `desktop.env` before dependent programs start. Server Chromium and the kiosk use separate processes/profiles.

Sway `assign` rules place kiosk, native Steam games, Gamescope and Steam management windows into their destination workspaces **before mapping**. A fullscreen Gamescope window initially mapped on `home` would cancel the kiosk's compositor fullscreen (and expose Chromium's browser bars); moving it later from frontend polling or `for_window` is too late. Late class changes still have `for_window` rules. `groot jobs fullscreen-smoke` launches disposable real Gamescope/SDL windows, including a hidden Steam-management fixture, and asserts kiosk fullscreen and the browser viewport throughout creation, switching home while a game is alive, and exit. Run this physical-display check when the TV is idle.

#### HTPC-only audio

The desktop release also installs `groot-htpc-audio.service`: a PipeWire loopback sink named `qijian.audio` / **栖间 HTPC**. The kiosk and background Steam launchers set `PULSE_SINK=qijian.audio`; Proton and other PulseAudio clients launched by Steam inherit that route. The bus feeds the output recorded in `~/.config/htpc-next/audio.json`, initially the existing default speaker, without changing the system default or its master volume. The virtual sink has low selection priority and its output has fallback disabled to avoid self-routing. First setup uses a private volume of **30%**; subsequent launches preserve the configured value. Other applications, Windows USB audio and Jarvis retain their existing routes and levels.

```sh
groot jobs audio-status
HTPC_VOLUME=30 groot jobs audio-volume  # only the HTPC bus; 0–100
groot jobs audio-smoke                 # silent kiosk/PulseAudio routing and isolation checks
```

HTPC volume shortcuts must target this bus, **not** `@DEFAULT_AUDIO_SINK@`. Applications explicitly selecting ALSA or a different output need their own route configuration. `audio-smoke` verifies actual kiosk and PulseAudio links and checks that the physical device's level, default sink and existing Windows USB playback levels remain unchanged.

Prerequisites on Ubuntu are `steam-installer`, i386 Mesa/Vulkan libraries, `sway`, `xwayland` and a usable seatd seat. The bootstrap job verifies Ubuntu's pinned Steam archive; the official client subsequently updates itself. User lingering is required for unattended startup. This host's Mi TV runs at 1920×1080/60 Hz. Physical controller acceptance requires a connected controller; the ordinary integration suite does not download paid games.

For unattended seatd sessions, `steam-devices`' logind `uaccess` tags alone do not grant device access. Run `groot jobs desktop-input-prepare`, then the printed `sudo` command once to install the S3-delivered `71-qijian-gamepad.rules`. It grants the existing `plugdev` group read/write access to joystick input devices and Steam Input's `/dev/uinput`; the TV user must belong to that group. Reconnect the controller after installation. Browser navigation may work through `/dev/input/js*` even when Steam/Proton cannot open `/dev/input/event*`, so verify native Steam detection as well as the browser Gamepad API.

`groot jobs desktop-smoke` is an explicit live acceptance job: it waits for the installed `HTPC_TEST_APP` (default Sekiro/814380), launches it through the real 栖间 UI, checks fullscreen/focus and hidden Steam windows, then stops it and verifies the original Play focus returns. It allows up to 15 minutes for first-run shader compilation. It refuses to start if that game's window already exists; `HTPC_TEST_RESUME=1` explicitly resumes the same test's interrupted launch without reloading the kiosk page. An optional `HTPC_CAPTURE_TOOL` path to `grim` records the physical output; returned-UI screenshots are saved under `artifacts/`. Run it only when the TV is available for a real launch/exit test.

Integration tests intercept only external HTTP responses inside Chromium. The actual Scala.js login state machines, CDP WebSocket, KV bridge, renewal, credential isolation, denial/cancel/expiry/logout, library pagination and focus restoration run unchanged. Real account contents require the user to scan and approve.

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
POST /api/v1/storage
POST /api/v1/host
GET  /api/v1/chromium/:instance/cdp   (WebSocket)
GET  /api/v1/host/:service/targets/:target/cdp   (WebSocket)
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
| `instance.get`, `instance.socket`, `instance.delete`, `tab.list`, `tab.new`, `cookie.list`, `cookie.clear` | `{instance}` |
| `tab.delete`, `tab.back`, `tab.forward`, `tab.reload`, `tab.screenshot`, `tab.capture`, `tab.activate` | `{tab}` |
| `tab.goto` | `{tab, navigation: {url, waitUntil, timeoutMs}}` |
| `tab.eval` | `{tab, script, options: {awaitPromise, timeoutMs}}` |
| `tab.click` | `{tab, position: {x, y}}` (CSS viewport pixels) |
| `tab.insertText` | `{tab, text}` (up to 4096 characters) |
| `tab.pressKey` | `{tab, key}` (`Enter`, `Tab`, `Backspace`, `Delete`, `Escape`, arrows, `Home`, `End`, `SelectAll`) |
| `tab.scroll` | `{tab, deltaY}` (bounded wheel delta) |
| `cookie.set` | `{instance, cookies: [...]}` |
| `cookie.delete` | `{instance, selector: {name, domain, path, partitionKey}}` |

The Scala client supplies complete options including defaults. String enums are case-sensitive: `Started`, `DOMContentLoaded`, `Load`. `tab.screenshot` returns PNG; `tab.capture` returns a JPEG plus URL/title and CSS viewport dimensions for scaled pointer mapping. Cookie fields preserve HttpOnly, SameSite, expiration and partition identity.

An instance exclusively owns its profile. `profile: null` is temporary; deletion cleans it up. A named profile is retained for reuse. Closing an instance requests a graceful browser exit first, so persisted cookies can flush. No instances are automatically relaunched after backend restart; named profiles remain on disk. Operation timeouts cover active execution, not time spent waiting for the instance lock.

### Chromium WebSocket and serverStorage

`instance.socket` returns a same-origin WebSocket path for a **Running** instance. Each frontend connection owns an independent upstream CDP connection, supporting flattened sessions and asynchronous events without command-ID collisions. Deleting/stopping the instance closes sockets and rejects pending requests. Slow consumers are disconnected rather than accumulating unlimited events. Browser requests are not silently replayed on reconnect.

`client/src/infra/BrowserRuntime.scala` opens an ephemeral browser context with `disposeOnDetach`, creates an empty document at each requested origin via CDP interception, and executes frontend-authored requests in a named isolated world. This avoids cross-origin requests without disabling Chromium's web security. Each origin gets its own page; remote site JavaScript is never loaded into credential runtime documents. Reload/disconnect disposes those contexts; the App reconnects on the next operation and reloads server KV state.

`StorageOp[A]` is a GADT using the same error/result witnesses as Chromium/Wine/Sway. The server knows only JSON; `ServerKey[A]` adds each App's Codec in Scala.js.

| `op` | Arguments | Result |
| --- | --- | --- |
| `get` | `{key:{namespace,key}}` | `{present,value,revision}` |
| `inspect` | `{key}` | `{present,revision}` only |
| `put` | `{key,value,expected:null|revision}` | stamp |
| `delete` | `{key,expected:null|revision}` | tombstone stamp |
| `readInto` | `{key,world:{tab,name},binding}` | stamp; injects the value directly into server Chromium |
| `writeFrom` | `{key,world,expression,expected:revision}` | stamp; captures a JSON expression directly from server Chromium |
| `captureCdp` | `{key,instance,method,params,expected:revision}` | stamp; stores a CDP result without returning its data to the UI (e.g. HttpOnly cookies) |

Namespaces/keys accept 1–100 alphanumeric, underscore or hyphen characters. Values are limited to 1 MiB; files are atomic `0600`, directories `0700`. Revisions enable compare-and-set, including after deletion, so stale login/refresh results cannot resurrect an account after sign-out. Missing values and stored JSON null are distinct. Ordinary `get` is available for application data; credential code uses only `inspect` and browser transfers. Namespace separation is organizational, not an authorization boundary for mutually untrusted plugins.

`AuthOp` now exists only as a **frontend-local** typed command in `client/src/accounts`. There is no `/api/v1/auth` route or JVM provider interpreter. The generic system `qr` operation converts text to an SVG `Screenshot`. Adding a website App requires frontend code and KV configuration, not JVM changes.

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

### Host operations

`service.status`, `service.start`, `service.stop` and `cdp.targets` accept `{service}`; `cdp.socket` accepts `{service,target}`. Service names resolve through the server-owned configuration:

```json
{"steam":{"unit":"groot-htpc-steam.service","cdp":"http://127.0.0.1:8080"}}
```

Only configured `groot-*.service` user units are controlled. CDP discovery must use an explicit loopback HTTP address; discovered WebSockets must retain that exact loopback host/port. A client cannot supply its own upstream URL. Each bridge connection has its own upstream socket. Target disappearance and service failures are typed `HostError` results.

### Events

SSE connections begin with `event: sync`; the client refreshes snapshots on each connection/reconnection. `event: change` carries `{epoch, sequence, module, payload}`. Events are invalidation hints, not a persistent event log. The frontend subscribes before taking snapshots and debounces invalidations. Events do not contain Cookie or DLL argument values.

## Verification

- JVM tests check compile-time GADT/resource constraints, malformed wire input, error/result decoding, cookie fidelity, profile validation and actual Unix socket framing.
- Integration tests launch an isolated HTTP backend and actual headless Chromium against a local fixture site. They check navigation/history, JS/Promise errors/timeouts, cookie isolation/persistence, screenshot capture, and the built Laminar UI creating/deleting real instances.
- TV integration tests use the actual built Laminar application, backend and CDP input. An isolated YouTube document fixture verifies discovery/search/pagination and tab cleanup. Browser-level OAuth responses verify device-code UI, phone approval, cancellation and logout; injected standard Gamepad states verify D-pad, A/B and analog-stick behavior. No real account is authenticated by these tests.
- Authentication fixture tests exercise frontend polling, renewal, expiry/refusal/cancellation, and credential isolation; JVM storage tests cover persistence, revisions and tombstones.
- Host integration tests verify missing-service failures, live target discovery, independent CDP connections and foreign/stale target rejection. Native adapter fixtures cover install/account/disk races, license versions and launch-state persistence; these do not replace authenticated real-client acceptance.
- When Wine is installed or the local runtime has been extracted, integration tests execute the compiled DLL, verify Unicode values and memory release, and exercise error/timeout cleanup.
- Sway transport is tested against Unix socket fixtures. Connecting to a physical compositor requires `SWAYSOCK`.

`artifacts/test-ui.png` and `artifacts/test-tv-fixture.png` are integration screenshots. Failed test diagnostics are retained under `.runtime/integration-*`; successful test instances and profiles are removed.

## Packaging and delivery

```sh
groot jobs package
groot jobs upload
groot jobs deploy
groot jobs status
```

The package includes the JVM assembly, static frontend and Windows DLL host/example. SHA-256 of the reproducible archive is the artifact version; metadata is written to `artifacts/release.env`. Upload/download URLs live in `config.env`. `deploy` downloads from S3, verifies SHA-256, and installs a user unit named `groot-htpc-next.service` pointing at that immutable release. HTML entrypoints are uncached and reference content-versioned JS/CSS because reproducible archive mtimes cannot serve as asset versions.

Set runtime executable paths and `SWAYSOCK` in `~/.config/htpc-next/runtime.env` before deploying. For Ubuntu's Chromium snap, set `HTPC_DATA` to a snap-accessible location such as `/home/your-user/snap/chromium/common/htpc-next`. That file is not packaged or overwritten. The deployment job does not configure a display session, kiosk browser, system unit, or user lingering.

## Repository layout

```
shared/        platform-independent protocol and models
server/        HTTP server, CDP, Wine lifecycle, Sway IPC
client/        Laminar UI and typed HTTP client
native/        Windows DLL host and example adapter
desktop/       Sway, kiosk and background client bootstrap
scripts/       jobs, integration and delivery tools
jobs.yaml      build/test/package/upload/deploy graph
config.env     JDK, artifact endpoints and deployment defaults
```

This project was initialized in a new worktree (`feat/foundation`); there was no upstream branch to pull. The existing Hearth project is independent.
