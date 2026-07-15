# Firefox support — Juggler pipe transport (issue #10)

Feature branch: `firefox-support`. Goal: a minimal Firefox smoke (launch → newPage →
setContent → evaluate(1+1) → close) on Windows, then the full upstream Firefox suite. This
document captures the investigation and the implementation plan. **No runtime code changes yet on
this branch beyond the target test + this doc.**

## How Playwright Core launches Firefox (verified in driver-bundle 1.59.0)

- `lib/server/firefox/firefox.js` `defaultArgs`: Firefox is launched with
  `-headless -profile <userDataDir> -juggler-pipe` and Playwright waits for
  `"Juggler listening to the pipe"` on **stderr**.
- `lib/server/utils/processLauncher.js:106`: for a pipe-transport browser the child is spawned with

  ```js
  stdio: ["ignore", "pipe", "pipe", "pipe", "pipe"]   // 5 fds
  childProcess.spawn(command, args, { stdio, ... })
  ```

  i.e. fd0=ignore, fd1=stdout, fd2=stderr, **fd3=pipe, fd4=pipe**.
- `lib/server/browserType.js:265-269`: for Firefox (`supportsPipeTransport()` true, no `cdpPort`):

  ```js
  const stdio = launchedProcess.stdio;
  transport = new PipeTransport(stdio[3], stdio[4]);   // Juggler over fd 3/4
  ```

- `lib/server/pipeTransport.js` — the exact contract (constructor is `(pipeWrite, pipeRead)`, so
  **`pipeWrite = stdio[3]`, `pipeRead = stdio[4]`**):
  - **fd 3 = command channel: Playwright WRITES, Firefox READS.** (`_pipeWrite.write(...)`)
  - **fd 4 = event channel: Firefox WRITES, Playwright READS.** (`pipeRead.on("data", ...)`)
  - **Framing = `\0`-delimited JSON:** send is `write(JSON.stringify(msg)); write("\0")`; receive
    splits on `buffer.indexOf("\0")`. Not length-prefixed, not newline. (This differs from the
    driver's own length-prefixed stdio protocol and from Chromium CDP.)

Contrast: **Chromium** is launched with `--remote-debugging-*`; playwright4j reroutes it to a
CDP-over-port/WebSocket transport (`launchChromium` + `HostBackedWebSocketTransport`). Firefox has
**no port/WebSocket option** — Juggler speaks only over fd 3/4.

## Slice 1 — baseline against the official node driver (verified 2026-07-15)

Ran `firefox.launch()` → `newPage` → `evaluate("1 + 1")` → `close` with the **real playwright-core
node driver** (bundled `node.exe` + `driver/win32_x64/package`) against the installed
`firefox-1511`, `DEBUG=pw:browser`/`pw:protocol`. Result: **works** — `version=Firefox/148.0.2`,
`EVAL_RESULT=2`, clean close.

- Executable: `ms-playwright\firefox-1511\firefox\firefox.exe` (Playwright-managed, not system Firefox).
- Full launch command:

  ```text
  firefox.exe -no-remote -headless -profile <TEMP>\playwright_firefoxdev_profile-XXXX -juggler-pipe -silent
  ```

  (note the extra `-no-remote` and `-silent` beyond firefox.js `defaultArgs`).
- stdio confirmed: `["ignore", "pipe", "pipe", "pipe", "pipe"]`; transport = `PipeTransport(stdio[3], stdio[4])`.
- fd3 = Playwright→Firefox (commands), fd4 = Firefox→Playwright (events); `\0`-delimited JSON (as above).
- Ready signal: `Juggler listening to the pipe` on the child's **stdout**.
- First handshake: `SEND {"method":"Browser.enable",...,"id":1}` `SEND {"method":"Browser.getInfo","id":2}`
  → `RECV {"id":1}` `RECV {"id":2,"result":{"version":"Firefox/148.0.2",...}}` → `Browser.createBrowserContext` …
- Teardown: graceful close (`Browser.removeBrowserContext` etc. over the pipe) → `<process did exit:
  exitCode=0>`; no force-kill needed. Launched Firefox exited cleanly (0).

Reproduce: extract `driver/win32_x64/**` from the driver-bundle jar, then
`PLAYWRIGHT_BROWSERS_PATH=%LOCALAPPDATA%\ms-playwright DEBUG=pw:browser node.exe fx-smoke.js`
(`fx-smoke.js` = `require('./driver/win32_x64/package').firefox.launch(...)`).

**Conclusion:** the environment/binary/bundle are fine; Firefox 1511 + `-juggler-pipe` works with the
official driver. The only missing piece is our fd 3/4 pipe bridge — Slice 2 is unblocked.

## The gap in playwright4j

- JS shim (`node-compat-bootstrap.js`): `child_process.spawn` detects `-juggler-pipe` /
  `--inspector-pipe` via `detectUnsupportedBrowserEngine` and **fast-fails** with
  "Unsupported browser engine". `createSpawnedProcess` only fakes `stdio[3]/[4]` as endpoint
  placeholders for the Chromium→WebSocket reroute — there is no real fd 3/4 byte pipe.
- Java (`JdkHostProcessLauncher`): uses `ProcessBuilder`, which exposes **only** stdin/stdout/stderr.
  **Java's `ProcessBuilder` cannot give a child extra inheritable file descriptors (fd 3/4).**

So Firefox cannot work until we can hand the Firefox child process two extra pipes as fd 3/4 and
bridge them to Java/JS.

## Missing primitive

A way to launch Firefox with two extra **inheritable** pipes wired to its fd 3 and fd 4, and to
read fd3 / write fd4 from Java. On Windows this is not a ProcessBuilder capability:

- Node implements `stdio: [...,'pipe','pipe']` on Windows by creating anonymous pipes and passing
  their child-side handles through the **MSVCRT inherited-handle block** (`STARTUPINFO.lpReserved2`),
  which the child's C runtime maps to fd 3/4. Firefox (built against a CRT) picks up fd 3/4 there.
- We already ship a tiny native Windows launcher (`node-launcher.c`) that does `CreateProcess` with
  handle inheritance + a Job Object. The Firefox launcher is the same shape plus the fd3/4 pipe
  setup and the MSVCRT inherited-handle block.

## Implementation plan (slices)

1. **Baseline (investigation task #1). — DONE** (see "Slice 1 — baseline" above). The official
   driver launches `firefox-1511` with `-no-remote -headless -profile <dir> -juggler-pipe -silent`,
   stdio `["ignore","pipe","pipe","pipe","pipe"]`, `\0`-delimited JSON over fd3 (write) / fd4 (read),
   ready on stdout, clean graceful close. Environment/binary confirmed good.
2. **Native fd 3/4 launcher.**
   - **2a. Standalone spike — DONE & verified 2026-07-15** (`playwright4j/src/main/native/firefox-fd-spike.c`,
     not built/wired). It creates two anonymous pipes (child ends inheritable, parent ends not),
     builds the MSVCRT inherited-handle block in `STARTUPINFO.lpReserved2` for fds 0–4
     (fd3 = `FOPEN|FPIPE` child-read; fd4 = `FOPEN|FPIPE` child-write), `CreateProcessA`es Firefox
     under a `KILL_ON_JOB_CLOSE` Job Object, writes `Browser.enable`+`Browser.getInfo` (\0-terminated)
     to fd3 and reads fd4. Result: `Juggler listening to the pipe`, `RECV {"id":1}`,
     `RECV {"id":2,"result":{"version":"Firefox/148.0.2",...}}`, `HANDSHAKE_OK`, exit 0, reproducible,
     **no leak** (Job Object kills Firefox on close). The hard Windows fd 3/4 inheritance is proven.
   - **2b. Productionize** into the real launcher (extend `node-launcher.c`'s Job Object + quoting):
     expose the parent pipe ends back to Java (write→fd3, read←fd4). Byte-transparent — framing (`\0`)
     is handled in JS by Playwright's PipeTransport; the bridge just moves raw bytes.
3. **Java host bridge.** `HostProcessLauncher` gains a `launchPipeBrowser(command, args, workdir)`
   that returns a process id plus readable(fd3)/writable(fd4) channels (mirroring the existing
   launcher's stdout/stderr pump), with clean teardown (Job Object) and no leaks.
4. **JS spawn shim.** For a `-juggler-pipe` launch, stop fast-failing: route to the pipe-browser
   launcher and expose real `child.stdio[3]` (Readable from fd3) and `child.stdio[4]` (Writable to
   fd4) so Playwright's `PipeTransport(stdio[3], stdio[4])` works. Keep the fast-fail for engines we
   still do not support.
5. **Firefox smoke green + lifecycle.** Make `FirefoxSmokeTest` pass; verify close/error lifecycle
   and zero process leaks. Only then narrow/remove the unsupported-engine fast-fail for firefox.
6. **Full upstream Firefox suite** as a later step.

## Constraints (from issue #10)

No faking Firefox with Chromium; no silent fallback to Chrome/Edge; keep the unsupported-engine
fast-fail until real transport works; do not change upstream tests; do not destabilize the
validated Chrome/Edge path; no process leaks after close.
