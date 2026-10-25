# Playwright 1.62 upgrade checkpoint (2026-10-25)

Branch: `feat/playwright-1.62-upgrade`. **Work in progress: NOT release ready.**

## Changes made

- Updated the Playwright Java contract test dependency to `1.62.0`.
- Updated the Playwright Core build-time dependency to `com.microsoft.playwright:driver:1.62.0`.
  Playwright 1.62 stores Core directly at `driver/package/**`; the old
  `driver-bundle` package has only Node binaries and is no longer a Core source.
- Repackage only JavaScript resources, never Microsoft's `node.exe` or its
  driver classes.
- Added Node/GraalJS compatibility scaffolding for new bundled dependencies,
  and patched the in-bundle PipeTransport and WebSocketTransport rather than
  relying on the removed standalone internal modules.
- Updated consumer dependency-check version references.

## Local publication and consumer

```powershell
./gradlew.bat :playwright4j:publishToMavenLocal -Pversion=0.2.2-SNAPSHOT
```

Publication succeeded; only `~/.m2/repository/com/aresstack/playwright4j/0.2.2-SNAPSHOT`
was written. The separately checked-out GPT Desktop branch
`feat/playwright-1.62-local` now consumes this version through `mavenLocal()`,
alongside the Playwright Java API `1.62.0`. Both its main and test Java sources
compile successfully.

## Known failures / investigation

- **End-to-end Chromium browser smoke test is still failing/hanging.**
  Playwright Core 1.62 successfully receives and answers `initialize`,
  and Chrome launches and exposes a CDP WebSocket endpoint; the transport
  does not complete the browser connection yet. Do not claim runtime
  compatibility from the successful publication or compilation.
- Existing Playwright4J tests that import the old unbundled
  `lib/server/transport`, `utils/wsServer` and `utils/crypto` modules
  need migrating to supported entry points. The previous run reported
  13 failures among 41 tests, including these references.
- New optional Node modules that are not used for browser automation
  (e.g. inspector, vm, fetch Request/Response, Web TransformStream) have
  explicit fail-fast stubs to permit Core initialization. These are NOT
  full API implementations and must be reviewed before releasing.
- The standalone `consumer-check` task was inadvertently run under Java 17;
  Playwright4J targets Java 21. Re-run using a Java 21 Gradle process.

## Safety / disk space

The user's C: volume reached under 1 MB free during the upgrade. It had
approximately 3.8 GiB free at the latest check. Gradle, ms-playwright and
Camoufox browser caches take multiple GiB. Preserve the Camoufox reference
browser and normal browser profiles. Isolated stuck upgrade test processes
were terminated. Do not start further unbounded tests while disk is tight.

No Node.js binary is bundled or required by the Java desktop build. Do not
merge this branch into `master` or publish externally until smoke and
regression tests pass.
