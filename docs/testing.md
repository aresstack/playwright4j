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
| Full upstream Playwright Java contract suite (`upstreamTest`) | `playwright-java-contract-tests` | no, audit |
| `knownLimitationsCheck`: green iff exactly the known reds fail | `playwright-java-contract-tests` | no, audit |
| `browserSignals`: page-observable automation signals (`navigator.webdriver`, headers, injected globals), see [browser-signals.md](browser-signals.md) | `playwright-java-contract-tests` | no, diagnostic |
| `passiveAcquisition`: response bodies and BiDi DOM reads against page-visible side effects, see [passive-acquisition.md](passive-acquisition.md) | `playwright-java-contract-tests` | no, prototype |

A raw `upstreamTest` run reports `BUILD FAILED` because of the known reds. That is
intentional: no silent excludes are added to the normal test path.

The upstream contract suite targets the Chromium browser type (Chrome and Edge). Firefox
is only covered by the BiDi smoke test so far; it has not been run against the full
contract suite.

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
