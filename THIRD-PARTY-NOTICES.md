# Third-party notices

Playwright4J is licensed under the Apache License, Version 2.0 (see `LICENSE`).
It uses and imports the following third-party materials, all under the Apache License 2.0.

## Microsoft Playwright Java driver bundle (runtime dependency)

```text
Group:    com.microsoft.playwright
Artifact: driver-bundle
Version:  1.59.0
Source:   Maven Central
License:  Apache License 2.0
```

Playwright4J loads `driver/<platform>/package/cli.js` (and related package resources) from this
dependency on the classpath and evaluates that JavaScript in a GraalVM JavaScript context after
installing `node-compat-bootstrap.js`. The driver bundle's own resources are not modified.

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
