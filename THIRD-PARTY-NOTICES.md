# Third-party notices

This project does not vendor Microsoft Playwright driver files in `src/main/resources`.
The current tests resolve the official Microsoft Playwright Java driver bundle from Maven Central at test runtime.

## Microsoft Playwright Java Driver Bundle

```text
Group:    com.microsoft.playwright
Artifact: driver-bundle
Version:  1.59.0
Scope:    testRuntimeOnly
Source:   Maven Central
License:  Apache License 2.0
```

The driver bundle contains the Playwright Java driver resources under paths such as:

```text
driver/<platform>/package/cli.js
```

Playwright4J currently loads `driver/<platform>/package/cli.js` from the dependency classpath and evaluates that JavaScript source in a GraalVM JavaScript context after installing `node-compat-bootstrap.js`.

No Playwright driver files are modified or redistributed by the project at this stage.
If Playwright assets are bundled into a published Playwright4J artifact later, preserve the upstream license and copyright notices and include the complete Apache License 2.0 text required by the upstream distribution.
