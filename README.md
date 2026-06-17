# Playwright4J

Playwright4J is an experimental Java/GraalVM research runtime for loading and progressively hosting Playwright Core without starting a Node.js runtime.

The first milestone is intentionally small: a test-driven compatibility harness loads a Node-compatibility bootstrap script, then loads the official Microsoft Playwright Java driver entry script from the Maven Central `com.microsoft.playwright:driver-bundle` artifact and exposes Java host services to JavaScript through GraalVM.

## Coordinates

```text
groupId:    com.aresstack
artifactId: playwright4j
package:    com.aresstack.playwright4j
```

## Current scope

This repository does **not** claim full Playwright compatibility yet. The current goal is to discover and replace Node.js runtime dependencies incrementally:

1. Load `node-compat-bootstrap.js`.
2. Load `driver/<platform>/package/cli.js` from `com.microsoft.playwright:driver-bundle`.
3. Capture missing Node/host functionality as explicit test failures.
4. Replace loud JavaScript stubs with Java-backed host adapters.
5. Promote proven adapters into a stable `HostPlatform` boundary.

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
