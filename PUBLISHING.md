# Publishing to Maven Central

Playwright4J is released to Maven Central **purely via Gradle** through the Sonatype Central
Portal (the `com.gradleup.nmcp` aggregation plugin), mirroring the aresstack/msdos-game-browser
setup. Only the `playwright4j` module is published; `playwright-java-contract-tests` is not.

## What gets published

`com.aresstack:playwright4j:<version>` with:

- the main jar (Java classes, `node-compat-bootstrap.js` and the repackaged Playwright Core
  JavaScript package under `driver/package/`; **no native binaries**),
- `-sources.jar`,
- `-javadoc.jar`,
- a POM with name/description/url, Apache-2.0 license, developer, and SCM metadata,
- GPG signatures for all of the above.

## How a release happens

1. The release runs from [`.github/workflows/release.yml`](.github/workflows/release.yml) on a
   pushed tag matching `v*` (for example `v0.1.0`). The version is derived from the tag
   (`v0.1.0` → `0.1.0`) and passed to Gradle via `-Pversion`.
2. It runs on **windows-2022**, where Chrome, Edge and Firefox are preinstalled. No C toolchain
   is needed; the project contains no native code.
3. **Release gate:** the Chrome + Edge channel smokes (`channelSmoke`, launch → page →
   evaluate → close), the Firefox WebDriver BiDi smoke (`firefoxSmoke`) and the upstream suite
   (jobs `upstream-gate` and `upstream-gate-check`: `knownLimitationsCheck` for Chrome, Edge and
   Firefox on Windows, Chrome and Firefox on Linux) must pass before anything is published.
4. A `publishToMavenLocal` sanity assembles the jar / sources / javadoc / POM.
5. Gradle builds, signs (in-memory PGP key), and publishes via
   `publishAggregationToCentralPortal`.

The `upstream-gate` jobs run the suite in eight shards per browser (about 5 to 15 minutes each)
and upload their test reports; `upstream-gate-check` evaluates the known limitations over all
shards of a browser.

## Credentials (already set as organization GitHub secrets)

The workflow maps these org secrets to Gradle properties:

```text
CENTRAL_USERNAME  -> ORG_GRADLE_PROJECT_centralUsername
CENTRAL_PASSWORD  -> ORG_GRADLE_PROJECT_centralPassword
GPG_PRIVATE_KEY   -> ORG_GRADLE_PROJECT_signingInMemoryKey
GPG_PASSPHRASE    -> ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
```

Never put these in `gradle.properties` in the repo.

**These are organization secrets — they must be granted to this repository.** In GitHub → the
`aresstack` org → Settings → Secrets and variables → Actions, each of the four secrets must have
`aresstack/playwright4j` in its repository access (or be visible to all repos). If they are not
granted, `${{ secrets.* }}` resolves to an empty string and the release fails with
`signing task ... has no configured signatory` (and, later, Central Portal auth errors).

`publishingType = 'AUTOMATIC'` means the deployment is uploaded to the Central Portal **and
released automatically** — no manual step in the portal UI.

## Local validation (no credentials, no signing)

```bash
./gradlew :playwright4j:publishToMavenLocal -Pversion=0.1.0
```

This produces the jar / sources / javadoc / POM in your local `~/.m2` without contacting the
Central Portal.

To check the result like CI does (no native file in the playwright4j jar, no `driver`/`driver-bundle` for
consumers, POM resolvable by Maven):

```bash
./gradlew -p consumer-check checkConsumerDependencies -Pplaywright4jVersion=0.1.0
mvn -B -f consumer-check/pom.xml verify -Dplaywright4j.version=0.1.0
```

## Before cutting a release

- Confirm the push/PR CI (`ci.yml`: Chrome + Edge `channelSmoke`) is green. The release
  workflow re-runs the same smokes as its gate before publishing.
- A manual run of the release workflow (`workflow_dispatch`) runs every gate, including
  `upstream-gate`, without publishing.
- Then create the `v<version>` tag. Tagging is a deliberate manual step; the tag push runs the
  release workflow, which uploads the deployment to the Central Portal and auto-releases it.
