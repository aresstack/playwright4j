# Playwright4J

Playwright4J is an experimental Java/GraalVM runtime for running the official
Microsoft Playwright Core JavaScript driver inside the JVM, without requiring a
Node.js runtime process for the main driver.

The project keeps the public Playwright Java API model and executes the upstream
Playwright Core driver bundle on GraalVM. Missing Node.js and operating-system
primitives are provided by Java host adapters, such as process launching,
WebSocket transport, HTTP, filesystem access, streams, crypto, zlib, TLS, and
selected Node compatibility APIs.

Playwright4J ships no native helper binaries: no Node.js, no `node.exe` launcher,
no geckodriver. The Playwright Core JavaScript package is repackaged into the jar
without the Node.js executables that Microsoft's `driver-bundle` contains.

## Coordinates

```text
groupId:    com.aresstack
artifactId: playwright4j
package:    com.aresstack.playwright4j
```

## Using Playwright4J in an application

Use the official Playwright Java API together with Playwright4J, and exclude Microsoft's
Node-based driver from the API dependency. `driver` would shadow Playwright4J's
`com.microsoft.playwright.impl.driver.Driver`, and `driver-bundle` (about 200 MB) contains
`node`/`node.exe` for every platform. Without these excludes the Node binaries are downloaded
again, even though Playwright4J never runs them.

Gradle:

```groovy
implementation 'com.aresstack:playwright4j:<version>'
implementation('com.microsoft.playwright:playwright:1.59.0') {
    exclude group: 'com.microsoft.playwright', module: 'driver'
    exclude group: 'com.microsoft.playwright', module: 'driver-bundle'
}
```

Maven:

```xml
<dependency>
  <groupId>com.aresstack</groupId>
  <artifactId>playwright4j</artifactId>
  <version>VERSION</version>
</dependency>
<dependency>
  <groupId>com.microsoft.playwright</groupId>
  <artifactId>playwright</artifactId>
  <version>1.59.0</version>
  <exclusions>
    <exclusion><groupId>com.microsoft.playwright</groupId><artifactId>driver</artifactId></exclusion>
    <exclusion><groupId>com.microsoft.playwright</groupId><artifactId>driver-bundle</artifactId></exclusion>
  </exclusions>
</dependency>
```

`consumer-check/` resolves exactly this setup from the Maven repository twice, once with
Gradle (`build.gradle`) and once with Maven from the published POM (`pom.xml`). Both fail if
Microsoft's `driver` or `driver-bundle` reaches the classpath; the Gradle check also fails if
the playwright4j jar contains a native file. CI and the release workflow run both.

Note: GraalVM's own `truffle-api` jar (a transitive dependency of GraalJS) contains the
`libtruffleattach` native library, which the Truffle runtime may load on a stock JDK. It is
part of GraalVM, not of Playwright4J, and is neither downloaded separately nor used to control
a browser.

## Browser support

Playwright4J drives locally installed browsers; it does not download browsers.

| Browser | How | Protocol |
| --- | --- | --- |
| Google Chrome | `chromium` browser type, channel `chrome` | CDP |
| Microsoft Edge | `chromium` browser type, channel `msedge` | CDP |
| Mozilla Firefox | `firefox` browser type, channel `moz-firefox` (installed Firefox) | WebDriver BiDi |

Chrome and Edge stay on CDP for now. Firefox runs over WebDriver BiDi via
Playwright's own BiDi implementation; Firefox exposes its BiDi WebSocket itself,
so no geckodriver is involved. WebKit is not supported and fails fast with a
clear unsupported-engine error.

`BrowserType.connect()` against a `launch-server` works without Node.js: the
server is the same Java GraalJS driver started in `--node-compat` mode
(`java ... com.aresstack.playwright4j.driver.GraalDriverMain --node-compat cli.js launch-server ...`).

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

The project is not yet a complete multi-browser Playwright replacement. The
upstream contract suite below targets Chrome/Edge; Firefox is validated by a
dedicated WebDriver BiDi smoke test. WebKit is out of scope.

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

> CI note: the **release gate** is the fast Chrome + Edge channel smokes (launch →
> page → evaluate → close) plus the Firefox BiDi smoke, which run on Windows CI and
> must pass before publishing. CI additionally runs `TestBrowserTypeConnect`
> (launch-server, connect, reconnect, disconnect) for Chrome and Edge, checks that the
> playwright4j jar contains no native binaries, and runs the Gradle and Maven consumer checks.
> The full upstream compatibility suite runs separately as a **non-blocking audit**
> (`knownLimitationsCheck`): green iff exactly the six known reds fail. A raw
> `upstreamTest` run reports `BUILD FAILED` because of those reds — that is honest;
> no silent excludes are added to the normal test path.

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

`PlaywrightCoreLoadTest` evaluates the real Playwright CLI entry script (repackaged from the official Maven Central driver bundle) on GraalVM. The broad upstream contract coverage lives in the `playwright-java-contract-tests` module (`upstreamTest` / `knownLimitationsCheck`).

## Run

```bash
./gradlew test
```

or, without wrapper:

```bash
gradle test
```

## Upstream Playwright source

The upstream driver is pinned in `playwright4j/build.gradle` as a build-time-only input:

```groovy
playwrightDriverBundle 'com.microsoft.playwright:driver-bundle:1.59.0'
```

The `repackageDriverPackage` task copies only its platform-independent JavaScript package
into the jar as `driver/package/**`; the Node.js executables in that artifact are dropped
and the build fails if any native file ends up in the package. `driver-bundle` is not a
published dependency of `com.aresstack:playwright4j`.

See `THIRD-PARTY-NOTICES.md` for attribution and license notes.
