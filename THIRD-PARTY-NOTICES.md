# Third-party notices

Playwright4J is licensed under the Apache License, Version 2.0 (see `LICENSE`).
It uses and imports the following third-party materials.

## Microsoft Playwright Java driver bundle (repackaged JavaScript package)

```text
Group:    com.microsoft.playwright
Artifact: driver-bundle
Version:  1.59.0
Source:   Maven Central
License:  Apache License 2.0
```

At build time Playwright4J copies the platform-independent `driver/<platform>/package/**` tree
(Playwright Core JavaScript) from this artifact into its own jar as `driver/package/**`,
unmodified, and evaluates `cli.js` in a GraalVM JavaScript context after installing
`node-compat-bootstrap.js`. Playwright Core itself is under the Apache License 2.0; it bundles
third-party components under their own licenses (MIT, ISC, BSD and others), listed in
`driver/package/ThirdPartyNotices.txt`. That file, `driver/package/LICENSE` and
`driver/package/NOTICE` ship unmodified in the Playwright4J jar.

`driver-bundle` is only a build-time input: the Playwright4J build resolves it to extract the
JavaScript package. The Node.js executables it contains are not copied into the jar, not
executed, and `driver-bundle` is not a dependency of the published artifact, so users of
Playwright4J never download it.

## Imported Microsoft Playwright Java upstream test sources and resources

This repository **vendors/imports** Microsoft Playwright Java upstream **test** sources and test
resources under:

```text
playwright-java-contract-tests/src/upstreamTest/java/**
playwright-java-contract-tests/src/upstreamTest/resources/**
```

They are imported (via `scripts/download-upstream-playwright-java-tests.ps1` / the
`syncUpstreamPlaywrightJavaTests` Gradle task) from the official
[microsoft/playwright-java](https://github.com/microsoft/playwright-java) repository (v1.59.0) and
used as contract tests to validate the playwright4j driver replacement against the official Java
API. They remain under the upstream Apache License 2.0, with upstream copyright and notices intact.

### Test-only certificate fixtures

The following files are **upstream test fixtures only** (self-signed / test certificates used by
Playwright's client-certificate tests). They are not secrets and are not used at runtime:

```text
playwright-java-contract-tests/src/upstreamTest/resources/client-certificates/**   (*.pem, *.p12)
playwright-java-contract-tests/src/upstreamTest/resources/keys/keystore.jks
```

## Playwright NOTICE / attribution

Playwright and Playwright Java are:

```text
Copyright (c) Microsoft Corporation.
Licensed under the Apache License, Version 2.0.
```

Playwright contains code derived from the Puppeteer project
(https://github.com/puppeteer/puppeteer), available under the Apache License 2.0. See the upstream
NOTICE and LICENSE for the authoritative text:

- https://github.com/microsoft/playwright/blob/main/LICENSE
- https://github.com/microsoft/playwright/blob/main/NOTICE
- https://github.com/microsoft/playwright-java/blob/main/LICENSE

The Microsoft copyright above applies to the imported upstream materials; it is **not** claimed as
the copyright of the Playwright4J project itself (see `LICENSE`).
