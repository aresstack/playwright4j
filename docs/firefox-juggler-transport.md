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
  transport = new PipeTransport(stdio[3], stdio[4]);   // Juggler protocol over fd 3/4
  ```

  fd3 = readable (browser → Playwright), fd4 = writable (Playwright → browser). This is
  length-prefixed JSON, distinct from Chromium's CDP/WebSocket.

Contrast: **Chromium** is launched with `--remote-debugging-*`; playwright4j reroutes it to a
CDP-over-port/WebSocket transport (`launchChromium` + `HostBackedWebSocketTransport`). Firefox has
**no port/WebSocket option** — Juggler speaks only over fd 3/4.

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

1. **Baseline (investigation task #1).** Run the minimal Firefox smoke against the **official**
   Microsoft node driver on Windows (Firefox is installed locally at `ms-playwright/firefox-1511`)
   and capture the exact command line, stdio, and Juggler handshake — a reference for our bridge.
2. **Native fd 3/4 launcher.** A Windows helper (extend the `node-launcher.c` approach) that:
   creates two anonymous pipes, marks the child ends inheritable, builds the MSVCRT
   inherited-handle block so Firefox sees them as fd 3/4, `CreateProcess`es Firefox under a Job
   Object, and exposes the parent pipe ends back to Java.
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
