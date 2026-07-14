# Playwright4J

Playwright4J is an experimental Java/GraalVM runtime for running the official
Microsoft Playwright Core JavaScript driver inside the JVM, without requiring a
Node.js runtime process for the main driver.

The project keeps the public Playwright Java API model and executes the upstream
Playwright Core driver bundle on GraalVM. Missing Node.js and operating-system
primitives are provided by Java host adapters, such as process launching,
WebSocket transport, HTTP, filesystem access, streams, crypto, zlib, TLS, and
selected Node compatibility APIs.

This release is a Chromium milestone. It focuses on Chrome and Microsoft Edge
through Playwright's Chromium browser type and channel support.

## Coordinates

```text
groupId:    com.aresstack
artifactId: playwright4j
package:    com.aresstack.playwright4j
```

## Browser support

This release supports Chromium-based browsers through Playwright's Chromium
browser type.

Supported and validated:

- Google Chrome via `BROWSER_CHANNEL=chrome`
- Microsoft Edge via `BROWSER_CHANNEL=msedge`

Not supported in this release:

- Firefox
- WebKit

Firefox and WebKit currently fail fast with a clear unsupported-engine error
instead of hanging. Firefox support is planned for a separate branch/version
because it requires Playwright's Firefox/Juggler pipe transport rather than the
Chromium CDP/WebSocket path used by this release.

## Current scope

The current release validates the GraalVM-based Playwright driver runtime against
a broad upstream Playwright Java contract-test suite for the Chromium browser
type.

The runtime currently includes Java-backed compatibility for major Node and host
facilities needed by Playwright Core, including:

- Chromium process launch and lifecycle handling
- WebSocket and CDP transport
- HTTP/HTTPS client behavior
- filesystem and path operations
- streams and buffers
- crypto and zlib primitives
- TLS/client-certificate handling
- tracing, HAR, screenshots, downloads, video, and artifact transfer paths

The project is not yet a complete multi-browser Playwright replacement. Firefox
and WebKit require additional pipe-based browser transports and are intentionally
out of scope for this first release.

## Test status

The final controlled upstream contract-test sweep for this milestone completed
with:

```text
1606 passed
6 known failed
35 skipped
144 test classes completed
```

The known failures are documented limitations:

- `TestBrowserTypeConnect.shouldRecordTraceWithSources`
  - known upstream-parity behavior in the tested configuration (the official
    Microsoft node driver fails identically); see `problems.md`
- `TestPageEmulateMedia.shouldDefaultToLight`
  - host OS color-scheme environment dependency
- four WebKit fixture tests
  - unsupported-engine fast-fail behavior

No known Playwright4J runtime regression remains in the supported Chrome/Edge
scope of this milestone.

> CI note: a raw `upstreamTest` run reports `BUILD FAILED` because of the 6 known
> reds. That is honest — do not add silent excludes to the normal test path. The
> `knownLimitationsCheck` gate is green iff exactly those known reds fail.

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

`PlaywrightCoreLoadTest` evaluates the real Playwright CLI entry script from the official Maven Central driver bundle on GraalVM. The broad upstream contract coverage lives in the `playwright-java-contract-tests` module (`upstreamTest` / `knownLimitationsCheck`).

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
