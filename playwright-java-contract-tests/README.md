# Playwright Java contract tests

This module is intentionally not a release artifact. It verifies the published Microsoft Playwright Java API against the local `:playwright4j` driver replacement.

The module depends on the official `com.microsoft.playwright:playwright` jar and excludes the Microsoft `driver` and `driver-bundle` artifacts from that dependency. It then adds `project(':playwright4j')`, whose replacement `com.microsoft.playwright.impl.driver.Driver` starts the GraalVM-backed driver process.

Do not publish this module to Maven Central. It is for local/CI verification only.

## Smoke tests

The default `test` task contains local smoke tests for the replacement driver. The Chrome-based smoke is gated with `-Pplaywright4j.officialApiSpike=true`.

## Upstream Microsoft tests

Selected official Microsoft Playwright Java tests live under:

```text
src/upstreamTest/java/com/microsoft/playwright
```

Currently imported from `microsoft/playwright-java`:

```text
Utils.java
TestPlaywrightCreate.java
```

These files keep the upstream Apache-2.0 header. `Server.java` is currently a tiny local compile stub for unused helper methods in `Utils`; replace it with the upstream server fixture when importing network/server tests.

Run the selected upstream contract tests with:

```bash
gradlew :playwright-java-contract-tests:upstreamTest
```

Add more upstream tests incrementally instead of importing the entire suite at once, because many official tests require browser launch, server fixtures, downloads, tracing, screenshots, HAR, or non-Chromium browsers.
