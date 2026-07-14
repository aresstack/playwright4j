# Publishing to Maven Central

Playwright4J is released to Maven Central **purely via Gradle** through the Sonatype Central
Portal (the `com.gradleup.nmcp` aggregation plugin), mirroring the aresstack/msdos-game-browser
setup. Only the `playwright4j` module is published; `playwright-java-contract-tests` is not.

## What gets published

`com.aresstack:playwright4j:<version>` with:

- the main jar (includes the native `node.exe` launcher resource and `node-compat-bootstrap.js`),
- `-sources.jar` (without the compiled launcher binary),
- `-javadoc.jar`,
- a POM with name/description/url, Apache-2.0 license, developer, and SCM metadata,
- GPG signatures for all of the above.

## How a release happens

1. The release runs from [`.github/workflows/release.yml`](.github/workflows/release.yml) on a
   pushed tag matching `v*` (for example `v0.1.0`). The version is derived from the tag
   (`v0.1.0` → `0.1.0`) and passed to Gradle via `-Pversion`.
2. It runs on **windows-2022** so the native `node.exe` launcher is compiled (MinGW) into the
   published jar. The workflow fails fast if the launcher was not built.
3. Gradle builds, signs (in-memory PGP key), and publishes via
   `publishAggregationToCentralPortal`.

## Credentials (already set as organization GitHub secrets)

The workflow maps these org secrets to Gradle properties:

```text
CENTRAL_USERNAME  -> ORG_GRADLE_PROJECT_centralUsername
CENTRAL_PASSWORD  -> ORG_GRADLE_PROJECT_centralPassword
GPG_PRIVATE_KEY   -> ORG_GRADLE_PROJECT_signingInMemoryKey
GPG_PASSPHRASE    -> ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
```

Never put these in `gradle.properties` in the repo.

`publishingType = 'USER_MANAGED'` means the deployment is uploaded to the Central Portal and then
**released manually** from the portal UI (it is not auto-published).

## Local validation (no credentials, no signing)

```bash
./gradlew :playwright4j:publishToMavenLocal -Pversion=0.1.0
```

This produces the jar / sources / javadoc / POM in your local `~/.m2` and verifies the layout
(including that `node.exe` is in the main jar but not in the sources jar) without contacting the
Central Portal.

## Before cutting a release

- Confirm the CI smoke (`ci.yml`: Chrome + Edge `channelSmoke`) is green.
- Optionally run the known-limitations gate (`known-limitations-check.yml`, or
  `./gradlew :playwright-java-contract-tests:knownLimitationsCheck`) and confirm only the six
  documented known reds fail.
- Then create the `v<version>` tag / GitHub Release. Tagging is a deliberate manual step.
