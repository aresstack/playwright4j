# Testing

This page collects the test setup and test reports of Playwright4J. The README only
summarizes it.

## Test layers

| Layer | Where | Blocking |
| --- | --- | --- |
| Unit and runtime tests | `./gradlew :playwright4j:test` | yes |
| Chrome and Edge channel smokes (launch, page, evaluate, close) | Windows CI | yes, release gate |
| Firefox WebDriver BiDi smoke | Windows CI | yes, release gate |
| `TestBrowserTypeConnect` (launch-server, connect, reconnect, disconnect) for Chrome and Edge | CI | yes |
| Jar check: no native binaries in the artifact | CI, release | yes |
| `consumer-check/`: resolves the documented setup twice, with Gradle (`build.gradle`) and with Maven from the published POM (`pom.xml`); both fail if `driver` or `driver-bundle` reaches the classpath, the Gradle check also fails on a native file in the jar | CI, release | yes |
| Full upstream Playwright Java contract suite (`upstreamTest`) | `playwright-java-contract-tests` | no, audit |
| `knownLimitationsCheck`: green iff exactly the known reds fail | `playwright-java-contract-tests` | no, audit |
| `browserSignals`: page-observable automation signals (`navigator.webdriver`, headers, injected globals), see [browser-signals.md](browser-signals.md) | `playwright-java-contract-tests` | no, diagnostic |

A raw `upstreamTest` run reports `BUILD FAILED` because of the known reds. That is
intentional: no silent excludes are added to the normal test path.

The upstream contract suite targets the Chromium browser type (Chrome and Edge). Firefox
is only covered by the BiDi smoke test so far; it has not been run against the full
contract suite.

## Historical upstream contract-test sweep

The final controlled upstream contract-test sweep for this milestone completed
with:

```text
1606 passed
6 known failed
35 skipped
144 test classes completed
```

The known failures are documented limitations:

- `TestBrowserTypeConnect.shouldRecordTraceWithSources`
  - known upstream-parity behavior in the tested configuration (the official
    Microsoft node driver fails identically); see `problems.md`
- `TestPageEmulateMedia.shouldDefaultToLight`
  - host OS color-scheme environment dependency
- four WebKit fixture tests
  - unsupported-engine fast-fail behavior

No known Playwright4J runtime regression remains in the supported Chrome/Edge
scope of this milestone.

> CI note: the **release gate** is the fast Chrome + Edge channel smokes (launch →
> page → evaluate → close) plus the Firefox BiDi smoke, which run on Windows CI and
> must pass before publishing. CI additionally runs `TestBrowserTypeConnect`
> (launch-server, connect, reconnect, disconnect) for Chrome and Edge and checks that
> the jar contains no native binaries.
> The full upstream compatibility suite runs separately as a **non-blocking audit**
> (`knownLimitationsCheck`): green iff exactly the six known reds fail. A raw
> `upstreamTest` run reports `BUILD FAILED` because of those reds — that is honest;
> no silent excludes are added to the normal test path.

These numbers describe that earlier milestone, not the current branch. See
[`../problems.md`](../problems.md) for the details of each known limitation.
