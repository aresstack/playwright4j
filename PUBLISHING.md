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
3. **Release gate:** the fast Chrome + Edge channel smokes (`channelSmoke`, launch → page →
   evaluate → close) must pass before anything is published.
4. A `publishToMavenLocal` sanity assembles the jar / sources / javadoc / POM.
5. Gradle builds, signs (in-memory PGP key), and publishes via
   `publishAggregationToCentralPortal`.

The full upstream compatibility suite (`knownLimitationsCheck`) is **not** a blocking release
gate — it is heavy and environment-sensitive on the standard runner. It runs separately as a
non-blocking audit (`.github/workflows/known-limitations-check.yml`, on `workflow_dispatch` and
after a published release) and always uploads its test reports.

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

- Confirm the push/PR CI (`ci.yml`: Chrome + Edge `channelSmoke`) is green. The release
  workflow re-runs the same smokes as its gate before publishing.
- Optionally trigger the non-blocking audit (`known-limitations-check.yml` via
  `workflow_dispatch`, or locally `./gradlew :playwright-java-contract-tests:knownLimitationsCheck`)
  and review the uploaded reports — but this does not block the release.
- Then create the `v<version>` tag. Tagging is a deliberate manual step; the tag push runs the
  release workflow, which uploads the deployment to the Central Portal for manual release.
