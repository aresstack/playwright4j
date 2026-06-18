# Playwright Java contract tests

This module is intentionally not a release artifact. It verifies the published Microsoft Playwright Java API against the local `:playwright4j` driver replacement.

The module depends on the official `com.microsoft.playwright:playwright` jar and excludes the Microsoft `driver` and `driver-bundle` artifacts from that dependency. It then adds `project(':playwright4j')`, whose replacement `com.microsoft.playwright.impl.driver.Driver` starts the GraalVM-backed driver process.

Do not publish this module to Maven Central. It is for local/CI verification only.

## Smoke tests

The default `test` task contains local smoke tests for the replacement driver. The Chrome-based smoke is gated with `-Pplaywright4j.officialApiSpike=true`.

## Upstream Microsoft tests

The complete upstream Playwright Java test tree is vendored under:

```text
src/upstreamTest/java/com/microsoft/playwright
```

Refresh it from the configured upstream repository with:

```bash
gradlew :playwright-java-contract-tests:syncUpstreamPlaywrightJavaTests
```

By default this uses:

```text
https://github.com/Miguel0888/playwright-java/archive/refs/heads/main.zip
```

Override it with:

```bash
gradlew :playwright-java-contract-tests:syncUpstreamPlaywrightJavaTests -PplaywrightJavaTestsZipUrl=<zip-url>
```

Run the imported upstream suite with:

```bash
gradlew :playwright-java-contract-tests:upstreamTest
```

The upstream tests must match the Playwright Java API version used by this module. The default dependency is `com.microsoft.playwright:playwright:1.59.0`; override it with:

```bash
gradlew :playwright-java-contract-tests:upstreamTest -PupstreamPlaywrightVersion=<version>
```

If the upstream branch is newer than the Maven Central Playwright Java jar, compilation can fail on newer API symbols. That is expected and means the upstream test branch and API jar version need to be aligned.

All copied upstream test files retain their Apache-2.0 headers.
