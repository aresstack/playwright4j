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

- Chrome, Edge and Firefox smokes pass on Windows CI (PR #29).
- Firefox: 1.62 extends the injected script of every new document right away, with
  `userActivation: true`; these setup evaluations are now sent without activation (#26 rule).
- `util.formatWithOptions` added: without it any `DEBUG=pw:*` line ended the driver.
- Upstream Playwright Java tests imported from v1.62.0; `TestInstall` (Node.js driver
  installer) is excluded.
- Fixed: the Chromium hang. The bundle defines `PipeTransport` (driver protocol) and
  `PipeTransport2` (CDP over `--remote-debugging-pipe`); only the first was replaced, so
  Chrome kept using fd 3/4. `patchCoreBundleSource` now replaces both, plus
  `WebSocketTransport`, and re-applies the BiDi patches (#26/#27/#28) at the bundle's first
  use of `BidiFirefox`, `BidiBrowser` and `BidiPage`.
- Shim tests reach bundle-internal classes through
  `globalThis.__playwright4jCoreBundle.internals` instead of the removed modules.
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
