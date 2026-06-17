# Playwright4J

Playwright4J is an experimental Java/GraalVM research runtime for loading and progressively hosting Playwright Core without starting a Node.js runtime.

The first milestone is intentionally small: a test-driven compatibility harness loads a Node-compatibility bootstrap script, loads a bundled Playwright-Core entry script, and exposes Java host services to JavaScript through GraalVM.

## Coordinates

```text
groupId:    com.aresstack
artifactId: playwright4j
package:    com.aresstack.playwright4j
```

## Current scope

This repository does **not** claim full Playwright compatibility yet. The current goal is to discover and replace Node.js runtime dependencies incrementally:

1. Load `node-compat-bootstrap.js`.
2. Load `playwright-core-bundle.js`.
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
src/main/resources/com/aresstack/playwright4j/runtime/playwright-core-bundle.js
src/test/java/com/aresstack/playwright4j/graal/PlaywrightCoreLoadTest.java
```

`playwright-core-bundle.js` is currently a tiny placeholder that behaves like the first external seam. Replace it later with a bundled Playwright-Core artifact and let the tests drive the next missing compatibility symbols.

## Run

```bash
./gradlew test
```

or, without wrapper:

```bash
gradle test
```

## Publishing note

Before publishing publicly, replace the short `LICENSE` placeholder with the full Apache-2.0 license text and add third-party notices for bundled Playwright assets.
