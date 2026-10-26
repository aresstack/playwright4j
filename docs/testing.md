# Testing

This page collects the test setup and test reports of Playwright4J. The README only
summarizes it.

## Test layers

| Layer | Where | Blocking |
| --- | --- | --- |
| Unit and runtime tests | `./gradlew :playwright4j:test` | yes |
| Chrome and Edge channel smokes (launch, page, evaluate, close) | Windows CI | yes, release gate |
| Firefox WebDriver BiDi smoke | Windows CI | yes, release gate |
| Firefox windows and tabs, counted through the OS (`FirefoxPersistentWindowTest`, part of `firefoxSmoke`) | Windows CI, headed | yes, release gate |
| `APIRequestContext` through a proxy: plain HTTP, `CONNECT` for https, credentials, `bypass` (`apiRequestProxyTest`, stub proxy, no browser) | CI | yes |
| `TestBrowserTypeConnect` (launch-server, connect, reconnect, disconnect) for Chrome and Edge | CI | yes |
| Jar check: no native binaries in the artifact | CI, release | yes |
| `consumer-check/`: resolves the documented setup twice, with Gradle (`build.gradle`) and with Maven from the published POM (`pom.xml`); both fail if `driver` or `driver-bundle` reaches the classpath, the Gradle check also fails on a native file in the jar | CI, release | yes |
| Full upstream Playwright Java suite (`knownLimitationsCheck`): Chrome, Edge and Firefox on Windows, Chrome and Firefox on Linux; green iff only the listed known limitations fail | `release.yml`, jobs `upstream-gate` (8 shards per browser) and `upstream-gate-check` | yes, release gate |
| `browserSignals`: page-observable automation signals (`navigator.webdriver`, headers, injected globals), see [browser-signals.md](browser-signals.md) | `playwright-java-contract-tests` | no, diagnostic |

## Upstream suite

`playwright-java-contract-tests/src/upstreamTest` holds Microsoft's Playwright Java tests of the
pinned version (`syncUpstreamPlaywrightJavaTests` imports them). The import passes `BROWSER` and
`BROWSER_CHANNEL` to every browser the tests launch themselves, because playwright4j drives only
installed browsers. Run it with:

```bash
./gradlew :playwright-java-contract-tests:knownLimitationsCheck                                   # Chrome
./gradlew :playwright-java-contract-tests:knownLimitationsCheck -Pplaywright4j.browserChannel=msedge
./gradlew :playwright-java-contract-tests:knownLimitationsCheck -Pplaywright4j.browser=firefox     # moz-firefox
```

CI splits the suite with `-Pplaywright4j.shard=<i>/<n>` (every n-th test class, sorted by name)
and runs `knownLimitationsCheck` once over the results of all shards.

The check also fails when an upstream test class reported no results. It fails on any failure
that is not listed in
`playwright-java-contract-tests/known-limitations/<browser>.txt` and on a listed failure that
passes, except for entries that depend on the host or on upstream timing (`OS_ENVIRONMENT`,
`UPSTREAM_BIDI`). Each entry names its category; [`../problems.md`](../problems.md) explains them.
A raw `upstreamTest` run reports `BUILD FAILED` because of the known reds: no silent excludes are
added to the normal test path.

To tell a playwright4j defect from upstream behaviour, run the same tests against Microsoft's
Node.js driver (local diagnosis only, never in CI):

```bash
./gradlew :playwright-java-contract-tests:upstreamTestKnownLimitations -Pplaywright4j.browser=firefox \
    -Pplaywright4j.referenceDriver=official --tests '*TestPageBasic'
```

Measured on Linux with Firefox 158.0.1 and Chrome for Testing 153 (1688 tests): Chrome 1648
passed, 6 failed, 34 skipped; Firefox 1513 passed, 111 failed, 64 skipped. 108 of the Firefox
failures fail the same way with Microsoft's driver on the same Firefox, and 8 tests that fail
there pass with playwright4j (selector engines and atomic reads, which run in the utility world).

## Firefox windows and tabs

`FirefoxPersistentWindowTest` runs headed and counts the real top-level Firefox windows of the
processes Playwright started, not only `context.pages()`: on Windows with `EnumWindows` (C#
compiled in memory by PowerShell's `Add-Type`), on Linux with `xdotool` (needs an X display,
e.g. `xvfb-run`, and Firefox on X11; the `firefoxSmoke` task sets `MOZ_ENABLE_WAYLAND=0`).
Without either, or with `FIREFOX_HEADLESS=true`, the window counts are skipped and only the page
assertions run. Camoufox processes and windows count as Firefox (see [camoufox.md](camoufox.md)).
It checks:

- `launchPersistentContext` takes over the tab Firefox opens at startup: one window, one page,
  after a cold start and after a restart with the same profile; with a home page set, the first
  page shows it, so it is that very tab and not one opened next to it;
- `newPage()` opens a tab in that window; the page in the background tab still takes input;
- closing the last page leaves the context without pages while the window keeps a blank tab
  (Firefox never closes its last tab over WebDriver BiDi); the next `newPage()` uses that tab
  (its document is older than the `newPage()` call);
- Ctrl+W on the last tab in Firefox's UI keeps the window and the context
  (`browser.tabs.closeWindowWithLastTab=false` in a persistent profile). The keystroke is only
  sent once the Firefox window has the keyboard focus; if that fails, or the tab is still shown
  afterwards, the test fails with `PLAYWRIGHT4J_REQUIRE_FIREFOX=true` (CI) and is skipped
  otherwise;
- the same while Firefox starts its child processes slowly (`MOZ_DEBUG_CHILD_PAUSE=3`): Firefox's
  replacement tab shows `about:blank` until its `about:newtab` commits seconds later, and the page
  `newPage()` returns still navigates to a `data:` URL after that (Firefox refuses `data:` in the
  privileged new tab page);
- Firefox ending without `context.close()` closes the context, and the profile starts again;
- another running Firefox keeps its windows;
- `browser.newContext()`: the first page gets a window, further pages of that context are tabs;
- a `user.js` the profile already has keeps its own lines byte for byte across launches, before
  Playwright's preferences, which win for the same preference; a comment the profile leaves open
  at the end of its lines does not swallow Playwright's preferences (checked in the `prefs.js`
  Firefox saves on exit).

Every Firefox smoke test also checks that no Firefox process started during the test still runs
15 s after the browser was closed. A survivor fails the test with its pid, parent pid and command
line; a process still running after 5 s that ends within the 15 s is named the same way in the
test output. The CI job uploads the `firefoxSmoke` reports and prints failures in the log.

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

These numbers describe that earlier milestone, not the current branch. See
[`../problems.md`](../problems.md) for the details of each known limitation.
