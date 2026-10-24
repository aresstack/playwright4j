# Browser automation signals

Sites with bot protection (for example Cloudflare in front of a login) look at what a page can
observe about the browser. This page records which of those signals a browser launched through
playwright4j shows, how they compare to the official Playwright Node.js driver and to a browser
started without any automation, and how to reproduce the measurement.

The goal is that playwright4j behaves exactly like a browser launched by official Playwright. It is
not a goal to hide automation from sites or to get around their checks.

## Measuring

`BrowserSignalsProbe` (contract tests, `com.aresstack.playwright4j.signals`) serves a neutral page
from a local HTTP/1.1 server on 127.0.0.1. It records:

- every request head exactly as the browser sent it (header order and case kept),
- what an ordinary page script observes (`navigator.webdriver`, user agent, languages, plugins,
  screen and window sizes, WebGL, injected globals, test-only interfaces such as `TestUtils`),
- the real browser command line, selected environment variables (Linux) and the `user.js` that
  Playwright wrote into a Firefox profile.

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
`PROBE_PLAIN=true` (starts the browser binary directly, without Playwright, as a baseline). On Linux
run headed browsers under `xvfb-run`.

### Reference run with the official driver

The probe only uses the public Playwright Java API, Gson and the JDK. For a reference run, compile
the single source file against the official artifacts (`com.microsoft.playwright:playwright`,
`driver`, `driver-bundle` 1.59.0, `gson`) in a scratch directory outside this project and start its
`main` with the same environment variables. Then `diff` the two reports. The Node.js driver is used
only in that scratch setup and never becomes part of playwright4j.

`PROBE_CHANNEL=none` (with `FIREFOX_EXECUTABLE` unset) runs Playwright's own Firefox build (Juggler,
for example `firefox-1511` in the Playwright cache) for comparison with older setups that used it.
Note that official Playwright 1.59 cannot use `launchPersistentContext` with `moz-firefox` against
current Firefox (it waits forever for the initial `about:blank` load, which playwright4j fixes), so
use `PROBE_PERSISTENT=false` for the official BiDi reference.

## Results (October 2026)

Firefox 158.0.1 and Chrome for Testing 153 on Linux, headed under Xvfb, Playwright 1.59.0.
Header lists were identical in all Playwright runs of the same browser and are omitted.

### Firefox

| Signal | Firefox without automation | Official driver, moz-firefox (BiDi) | playwright4j 0.2.1, moz-firefox (BiDi) | playwright4j with this change |
|---|---|---|---|---|
| `navigator.webdriver` | `false` | `true` | `true` | `true` |
| `window.TestUtils` | undefined | object | object | undefined |
| `screen` | 1280x1024 | 1280x720 | 1280x720 | 1280x720 |
| `innerWidth`x`innerHeight` / `outer...` | 1152x836 / 1152x921 | 1280x720 / 1152x648 | 1280x720 / 1152x648 | 1280x720 / 1152x648 |
| `document.hasFocus()` | `false` (background window) | `true` | `true` | `true` |
| Injected globals | none | none | none | none |
| Request headers (order, values) | baseline | same as baseline | same as baseline | same as baseline |
| User agent, languages, plugins, WebGL | baseline | same | same | same |
| Process env `MOZ_CRASHREPORTER*` | n/a | set | not set | not set |
| Extra command-line flags | n/a | none | `-wait-for-browser -no-remote` | `-wait-for-browser -no-remote` |

With `page.exposeBinding(...)` (as GPT Desktop uses) every page additionally sees
`__playwright__binding__` and `__playwright__binding__controller__`. Playwright installs these in
all engines (`PageBinding` in `server/page.js`), so they are the same with the official driver and
with Juggler.

With `setViewportSize(null)` (no viewport emulation) screen and window sizes match the
non-automated baseline (`screen` 1280x1024, inner 1152x836 inside outer 1152x921).

### Chrome

| Signal | Chrome without automation | Official driver | playwright4j 0.2.1 |
|---|---|---|---|
| `navigator.webdriver` | `false` | `true` | `true` |
| `screen` / inner | 1280x1024 / 1050x917 | 1280x720 / 1280x720 | 1280x720 / 1280x720 |
| Request headers (order, values) | baseline | same | same |
| User agent, client hints, `window.chrome` | baseline | same | same |
| Command line | n/a | Playwright switches incl. `--enable-automation`, `--remote-debugging-pipe` | identical switches, but `--remote-debugging-port=0` instead of the pipe |

Removing `--enable-automation` alone (`ignoreDefaultArgs`) does not change `navigator.webdriver`
in Chrome while it is driven over the DevTools protocol.

## Assessment

**Caused by playwright4j and corrected here**

- `window.TestUtils`: Playwright's BiDi Firefox profile (`bidi/third_party/firefoxPrefs.js`, taken
  from Puppeteer) enables `dom.testing.testutils.enabled` only for `page.requestGC()`. That exposes
  a test-only global to every web page, which neither a normal Firefox nor Playwright's Juggler
  Firefox has. playwright4j now turns the pref off unless `firefoxUserPrefs` sets it, so
  `page.requestGC()` on Firefox needs `dom.testing.testutils.enabled=true` explicitly. The official
  driver still exposes it; this is a deliberate difference.

**Caused by playwright4j, not page-observable**

- The GraalJS runtime starts Chrome with `--remote-debugging-port=0` instead of a pipe, because a
  JVM cannot hand file descriptors 3/4 to a child process. The port is random and bound to
  127.0.0.1.
- `-wait-for-browser -no-remote` for Firefox (needed on Windows) and the missing
  `MOZ_CRASHREPORTER*` variables (the runtime does not pass `options.env` yet) are invisible to
  pages.

**Inherent to WebDriver BiDi with an installed Firefox**

- `navigator.webdriver` is `true` as soon as Firefox's Remote Agent is listening. This is what the
  W3C WebDriver specification requires, and Firefox has no preference to turn it off
  (`dom.webdriver.enabled` was tested and has no effect). Playwright's own Firefox build talks to
  Playwright over its Juggler pipe instead and does not start the Remote Agent, which is why
  setups based on it report `false`. This is the most likely reason a login protected by bot
  detection worked with that build and challenges the BiDi path.
- Firefox shows the remote-control indicator (striped address bar, robot icon) for the same
  reason. It is not visible to pages.
- With the default 1280x720 viewport, Firefox over BiDi emulates the viewport without resizing the
  window, so `innerWidth`/`innerHeight` exceed `outerWidth`/`outerHeight` and `screen` reports the
  viewport size. Applications that drive a visible window can launch with
  `setViewportSize(null)`; this is public Playwright API and leaves the real window geometry.

**Same for every Playwright runtime**

- In Chrome, `navigator.webdriver` is `true` with the official driver as well; the GraalJS runtime
  does not change any page-observable signal.
- Bindings from `page.exposeBinding` and init scripts from `addInitScript` are visible to pages in
  all engines.
