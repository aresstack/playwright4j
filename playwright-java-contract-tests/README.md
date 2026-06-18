# Playwright Java contract tests

This module is intentionally not a release artifact. It verifies the published Microsoft Playwright Java API against the local `:playwright4j` driver replacement.

The module depends on the official `com.microsoft.playwright:playwright` jar and excludes the Microsoft `driver` and `driver-bundle` artifacts from that dependency. It then adds `project(':playwright4j')`, whose replacement `com.microsoft.playwright.impl.driver.Driver` starts the GraalVM-backed driver process.

Do not publish this module to Maven Central. It is for local/CI verification only.

Future upstream Microsoft Playwright Java tests should be added here as contract tests, preserving their upstream Apache-2.0 notices where copied or vendored.
