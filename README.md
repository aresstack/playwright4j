# Playwright4J

Playwright4J is an experimental Java/GraalVM research runtime for loading and progressively hosting Playwright Core without starting a Node.js runtime.

The first milestone is intentionally small: a test-driven compatibility harness loads a Node-compatibility bootstrap script, then loads the official Microsoft Playwright Java driver entry script from the Maven Central `com.microsoft.playwright:driver-bundle` artifact and exposes Java host services to JavaScript through GraalVM.

## Coordinates

```text
groupId:    com.aresstack
artifactId: playwright4j
package:    com.aresstack.playwright4j
```

## Browser support

This release is a Chromium milestone.

Supported and validated:
- Google Chrome via `BROWSER_CHANNEL=chrome`

Expected / planned Chromium channel:
- Microsoft Edge via `BROWSER_CHANNEL=msedge` — same Chromium path; pending release smoke
  validation (see the `channelSmoke` job in CI).

Not supported:
- Firefox
- WebKit

Firefox and WebKit currently fail fast with a clear unsupported-engine error instead of
hanging. Firefox support is planned for a separate branch/version because it requires
Playwright's Firefox/Juggler pipe transport rather than the Chromium CDP/WebSocket path
used by this release.

## Current scope

This repository does **not** claim full Playwright compatibility yet. The current goal is to discover and replace Node.js runtime dependencies incrementally:

1. Load `node-compat-bootstrap.js`.
2. Load `driver/<platform>/package/cli.js` from `com.microsoft.playwright:driver-bundle`.
3. Capture missing Node/host functionality as explicit test failures.
4. Replace loud JavaScript stubs with Java-backed host adapters.
5. Promote proven adapters into a stable `HostPlatform` boundary.

## Test status

Latest controlled run of the imported official Microsoft Playwright Java upstream test
suite (`upstreamTest`, Chromium via the `chrome` channel, per-method timeout 45s):

```text
1606 passed / 6 failed / 35 skipped   (144 test classes, ran to completion)
no hangs, no leaked playwright4j processes
```

All 6 failures are documented, non-blocking known limitations — **no known real
playwright4j runtime bug remains**:

- **connect trace sources** — `TestBrowserTypeConnect.shouldRecordTraceWithSources`
  yields 0 embedded sources over `browserType.connect`. The **official Microsoft node
  driver fails identically** in this configuration (verified), so this is upstream
  parity, not a playwright4j defect. See `problems.md`.
- **unsupported browser engines** (4 fixture tests) — WebKit/Firefox are not supported;
  such launches **fail fast with a clear error** instead of hanging.
- **OS dark-mode default** — `TestPageEmulateMedia.shouldDefaultToLight` reflects the
  host OS (Windows Dark Mode) that Chrome inherits without an override; environmental.

Supported browser: **Chromium** (via the `chrome` / `msedge` channels). WebKit and
Firefox are intentionally not implemented.

> CI note: a raw `upstreamTest` run reports `BUILD FAILED` because of the 6 known reds.
> That is honest — do not add silent excludes to the normal test path. For release CI use
> a separate reported measurement run or an explicit known-limitations check.

## Architecture sketch

```text
JUnit tests
  -> GraalPlaywrightRuntime
     -> node-compat-bootstrap.js
        -> JavaScript require/process/Buffer facade
           -> Playwright4JHost
              -> Java host services
```

## Important files

```text
src/main/java/com/aresstack/playwright4j/graal/GraalPlaywrightRuntime.java
src/main/java/com/aresstack/playwright4j/graal/Playwright4JHost.java
src/main/resources/com/aresstack/playwright4j/runtime/node-compat-bootstrap.js
src/main/java/com/aresstack/playwright4j/graal/PlaywrightDriverBundleSource.java
src/test/java/com/aresstack/playwright4j/graal/PlaywrightCoreLoadTest.java
THIRD-PARTY-NOTICES.md
```

`PlaywrightCoreLoadTest` intentionally evaluates the real Playwright CLI entry script from the official Maven Central driver bundle. The current expected failure is the first unsupported CommonJS module boundary, not a JavaScript syntax error.

## Run

```bash
./gradlew test
```

or, without wrapper:

```bash
gradle test
```

## Upstream Playwright source

The test dependency is pinned in `build.gradle`:

```groovy
testRuntimeOnly 'com.microsoft.playwright:driver-bundle:1.59.0'
```

See `THIRD-PARTY-NOTICES.md` for attribution and license notes.
