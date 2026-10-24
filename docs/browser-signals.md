# Browser automation signals

Sites with bot protection (for example Cloudflare in front of a login) look at what a page and the
server can observe about the browser. This page records which of those signals a browser launched
through playwright4j shows, how they compare to the official Playwright Node.js driver and to a
browser started without any automation, and how to reproduce the measurement.

The goal is that playwright4j behaves like a browser launched by official Playwright. The one
deliberate exception is described under [Assessment](#assessment). It is not a goal to hide
automation from sites or to get around their checks.

## Measuring

`BrowserSignalsProbe` (contract tests, `com.aresstack.playwright4j.signals`) serves a neutral page
from a local HTTP/1.1 server on 127.0.0.1. It records:

- every request head exactly as the browser sent it (header order and case kept),
- what an ordinary page script observes (`navigator.webdriver`, user agent, languages, plugins,
  screen and window sizes, WebGL, globals with automation-like names, test-only interfaces such
  as `TestUtils`),
- the browser's main process and, where the OS reports them (Linux, not Windows), its arguments
  and selected environment variables,
- the `user.js` that Playwright wrote into a Firefox profile.

Nothing is read through `page.evaluate`, so the values are what any site's own script sees.

```bash
# Firefox over WebDriver BiDi (moz-firefox), persistent and headed like GPT Desktop
FIREFOX_EXECUTABLE=/path/to/firefox ./gradlew :playwright-java-contract-tests:browserSignals

# Chrome (or msedge) over CDP
PROBE_BROWSER=chromium PROBE_CHANNEL=chrome ./gradlew :playwright-java-contract-tests:browserSignals
```

The report goes to `playwright-java-contract-tests/build/reports/browser-signals/playwright4j.txt`
(or `PROBE_OUT`). Further switches: `PROBE_HEADLESS`, `PROBE_PERSISTENT`, `PROBE_NO_VIEWPORT`,
`PROBE_EXPOSE_BINDING`, `PROBE_FIREFOX_PREFS`, `PROBE_ARGS`, `PROBE_IGNORE_DEFAULT_ARGS` and
`PROBE_PLAIN=true` (starts the browser binary from `PROBE_EXECUTABLE` directly, without Playwright,
as a baseline). On Linux run headed browsers under `xvfb-run`.

### Reference run with the official driver

The probe only uses the public Playwright Java API, Gson and the JDK. For a reference run, compile
the single source file against the official artifacts (`com.microsoft.playwright:playwright`
1.59.0, which brings `driver`, `driver-bundle` and `gson`) in a scratch directory outside this
project and start its `main` with the same environment variables. Then `diff` the two reports. The
Node.js driver is used only in that scratch setup and never becomes part of playwright4j.

With the official driver, `PROBE_CHANNEL=none` and no `FIREFOX_EXECUTABLE` run Playwright's own
Firefox build (Juggler, for example `firefox-1511` in the Playwright cache). Juggler Firefox was not
available where the results below were taken, so it is not part of them.

Official Playwright 1.59 cannot use `launchPersistentContext` with `moz-firefox` against current
Firefox: it waits forever for the initial `about:blank` load, which playwright4j fixes. Use
`PROBE_PERSISTENT=false` for the official BiDi reference and compare it with a non-persistent
playwright4j run.

## Results (October 2026)

Firefox 158.0.1 and Chrome for Testing 153 on Linux, headed under Xvfb, Playwright 1.59.0.

### Firefox

| Signal | Firefox without automation | Official driver, moz-firefox, non-persistent | playwright4j 0.2.1, non-persistent | playwright4j 0.2.1, persistent | playwright4j with this change |
|---|---|---|---|---|---|
| `navigator.webdriver` | `false` | `true` | `true` | `true` | `true` |
| `window.TestUtils` | undefined | object | object | object | undefined |
| `screen` | 1280x1024 | 1280x720 | 1280x720 | 1280x720 | 1280x720 |
| inner / outer window size | 1152x836 / 1152x921 | 1280x720 / 1152x921 | 1280x720 / 1152x921 | 1280x720 / 1152x648 | as before (both modes) |
| `document.hasFocus()` | `false` (background window) | `true` | `true` | `true` | `true` |
| Globals with automation-like names | none | none | none | none | none |
| Request headers (order, values) | baseline | same as baseline | same as baseline | same as baseline | same as baseline |
| User agent, languages, plugins, WebGL | baseline | same | same | same | same |
| Process env `MOZ_CRASHREPORTER*` | n/a | set | not set | not set | not set |
| Extra command-line flags | n/a | none | `-wait-for-browser -no-remote` | `-wait-for-browser -no-remote` | `-wait-for-browser -no-remote` |

Official driver and playwright4j in the same mode report identical page signals and identical
request headers.

With `page.exposeBinding(...)` (as GPT Desktop uses) every page additionally sees
`__playwright__binding__` and `__playwright__binding__controller__`. Playwright installs these in
all engines (`PageBinding` in `server/page.js`).

With `setViewportSize(null)` (no viewport emulation) screen and window sizes match the
non-automated baseline (`screen` 1280x1024, inner 1152x836 inside outer 1152x921).

### Chrome

| Signal | Chrome without automation | Official driver | playwright4j 0.2.1 |
|---|---|---|---|
| `navigator.webdriver` | `false` | `true` | `true` |
| `navigator.languages` | `["en-US"]` | `["en-US","en"]` | `["en-US","en"]` |
| `screen` / inner | 1280x1024 / 1050x917 | 1280x720 / 1280x720 | 1280x720 / 1280x720 |
| WebGL | unavailable | SwiftShader (`--enable-unsafe-swiftshader`) | same as official |
| `history.length` | 1 | 2 (initial `about:blank`) | 2 |
| Request header values | baseline | same | same |
| Request header order | baseline | `Accept-Language` is the last header of every request (on subresources `User-Agent` moves up into its place) | same as official |
| User agent, client hints, `window.chrome` | baseline | same | same |
| Command line | n/a | Playwright switches incl. `--enable-automation`, `--remote-debugging-pipe` | identical switches, but `--remote-debugging-port=0` instead of the pipe |

Removing `--enable-automation` (`ignoreDefaultArgs`) does not change `navigator.webdriver` in
Chrome. This was measured with both the official driver (pipe) and playwright4j (port).

## Assessment

**Inherited from Playwright's BiDi Firefox profile, changed here on purpose**

- `window.TestUtils`: Playwright's BiDi Firefox profile (`bidi/third_party/firefoxPrefs.js`, taken
  from Puppeteer) enables `dom.testing.testutils.enabled` only for `page.requestGC()`. That exposes
  a test-only global to every web page. Neither a normal Firefox nor Playwright's Juggler profile
  (`playwright.cfg`) sets this pref. playwright4j now turns it off unless `firefoxUserPrefs` sets it.
  This differs from the official driver, which still exposes `TestUtils`. `page.requestGC()` on
  Firefox then fails with a message that names the pref to set.

**Caused by playwright4j, not page-observable**

- The GraalJS runtime starts Chrome with `--remote-debugging-port=0` instead of a pipe, because a
  JVM cannot hand file descriptors 3/4 to a child process. The port is random and bound to
  127.0.0.1. No page signal or request header differs because of it.
- `-wait-for-browser -no-remote` for Firefox (needed on Windows) and the missing
  `MOZ_CRASHREPORTER*` variables (the runtime does not pass `options.env` yet) are invisible to
  pages.

**Not specific to playwright4j or to WebDriver BiDi**

- `navigator.webdriver` is `true` in every automated run. Over BiDi, Firefox sets it while its
  Remote Agent is listening, as the W3C WebDriver specification requires, and no preference turns
  it off (`dom.webdriver.enabled` was tested and has no effect). Playwright's own Firefox build
  (Juggler) does not start the Remote Agent, but its patch
  (`browser_patches/firefox/patches/bootstrap.diff`) has made `Navigator::Webdriver()` return
  `true` since Playwright 1.47, so current Juggler builds such as `firefox-1511` report `true` as
  well (from the patch source, not measured here). In Chrome it is `true` with the official driver
  too.
- Firefox shows the remote-control indicator (striped address bar, robot icon) while the Remote
  Agent runs. It is not visible to pages.
- With the default 1280x720 viewport, `innerWidth` (1280) exceeds `outerWidth` (1152) in every
  Firefox BiDi run, and `screen` reports the viewport size. In persistent contexts the reported
  outer size also drops to 1152x648, below the inner size. Applications that drive a visible window
  can launch with `setViewportSize(null)` (public Playwright API); the real window geometry then
  stays.
- In Chrome driven by Playwright, `Accept-Language` is sent last in every request, and
  `navigator.languages`, history length and WebGL differ as listed above. All of these are the same
  with the official driver, so they come from Playwright itself (its launch switches and CDP
  setup), not from the GraalJS runtime.
- Bindings from `page.exposeBinding` and scripts from `addInitScript` are visible to pages in all
  engines.

**Not covered**

- Juggler Firefox (Playwright's own build) was not measured; see the reference run above.
- TLS and HTTP/2 fingerprints are not captured, because the probe server speaks plain HTTP/1.1.
  They come from the browser's own network stack, which playwright4j does not touch.
