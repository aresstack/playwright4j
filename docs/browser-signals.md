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
as a baseline) and `PROBE_RAW_BIDI=true` (Firefox with its Remote Agent, driven by a minimal BiDi
client that only sends `session.new` and navigates the start tab, like enterprise-ai-client does;
`PROBE_RAW_CAPABILITIES`, `PROBE_RAW_SUBSCRIBE`). On Linux run headed browsers under `xvfb-run`.

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

### Playwright's BiDi layer vs. a minimal BiDi client

Both columns use WebDriver BiDi on the same Firefox with a persistent profile. The minimal client
does what enterprise-ai-client (wd4j) does: start `firefox -profile <dir> -no-remote
--remote-debugging-port=<port>` with five first-run prefs in `user.js`, send `session.new` with
`{"alwaysMatch":{"browserName":"firefox"}}`, navigate the start tab. No subscriptions, intercepts,
preload scripts or emulation.

| Signal | Firefox without automation | Minimal BiDi client | playwright4j 0.2.1 (with `exposeBinding`) | playwright4j with this change |
|---|---|---|---|---|
| `navigator.webdriver` | `false` | `true` | `true` | `true` |
| `navigator.userActivation` at load, no input events | not active | not active | **active** (`hasBeenActive`, `isActive`) | not active |
| `window.TestUtils` | undefined | undefined | object | undefined |
| `screen` / inner / outer | 1280x1024 / 1152x836 / 1152x921 | same as without automation | 1280x720 / 1280x720 / 1152x648 | unchanged (use `setViewportSize(null)`) |
| `document.hasFocus()` (unfocused window under Xvfb) | `false` | `false` | `true` | `true` |
| Globals with automation-like names | none | none | `__playwright__binding__`, `__playwright__binding__controller__` | unchanged (from `exposeBinding`) |
| Request headers (order, values) | baseline | same | same | same |

BiDi commands playwright4j (Playwright 1.59) sends for `launchPersistentContext` and one
navigation, recorded with `DEBUG=pw:protocol`, compared with the minimal client:

| Playwright's BiDi layer | Minimal client (enterprise-ai-client) |
|---|---|
| `session.new` with `unhandledPromptBehavior: ignore`, `webSocketUrl`, `goog:prerenderingDisabled` | `session.new` with `browserName: firefox` |
| `session.subscribe` to all of `browsingContext`, `network`, `log`, `script`, `input`, globally | none |
| `network.addIntercept` for `authRequired`, globally | none |
| `network.addDataCollector` for response bodies up to 20 MB | none |
| `browser.setDownloadBehavior` (allowed, temp folder) | none |
| `browsingContext.setViewport` 1280x720, `emulation.setScreenSettingsOverride` 1280x720, `emulation.setScreenOrientationOverride` | none |
| `browsingContext.create` `type: window` for the first page | reuses the start tab |
| `script.evaluate "1 + 1"` in a sandbox on every new document, with `userActivation: true` | `script.evaluate` in the main realm only on demand, `userActivation: false` |
| `script.addPreloadScript` into the main world for bindings (with `exposeBinding`) | none (injects a few globals on demand) |
| about 90 prefs in `user.js` on every launch (see `bidi/third_party/firefoxPrefs.js`) | 5 first-run prefs, written once |

Since the change for Firefox tabs and windows (PR #27, not released yet), playwright4j no longer
creates a window for the first page of a persistent context: like the minimal client, it takes over
the start tab, and further pages open as tabs.

### Playwright's own code and the page's main world (Firefox)

Upstream Playwright 1.62 runs some of its own code in the page's main world in Firefox, where page
scripts see it: every document gets a `window.__pw_bidiInsertText` global; handle previews create
the InjectedScript there (which reads `window.__pwClock` and dispatches
`__playwright_global_listeners_check__`); and the offsets for input inside an `<iframe>` call
`getClientRects`/`getBoundingClientRect` there, which a page can hook. playwright4j runs all three
in Playwright's utility world instead (issues #36, #39, #50), as do its own checks of a taken-over
start tab. Unchanged, because the Playwright API defines them: the caller's `evaluate` and its
main-world handles (`page.$`, `waitForSelector`, `elementHandle()`, `frameElement()`) run in the
main world, and each evaluation there creates Playwright's UtilityScript in it.
`FirefoxMainWorldTest` (part of `firefoxSmoke`) guards this.

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

**Added by Playwright's BiDi layer, changed here on purpose**

- User activation: on every new document Playwright evaluates `1 + 1` in its utility world only to
  create that world, with `userActivation: true`. Firefox then reports the page as activated by the
  user (`navigator.userActivation.hasBeenActive` and `isActive`) without any input event. A normal
  Firefox, the minimal BiDi client and Chrome over CDP (measured) do not. playwright4j sends this
  one call with `userActivation: false`. Evaluations and actions the caller asks for keep
  Playwright's behaviour (`userActivation: true`, like `userGesture` over CDP), so a caller's
  `page.evaluate` or `locator(...).click()` still activates the page. This differs from the official
  driver.

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

## Native behavior (opt-in)

`-Dplaywright4j.bidi.nativeBehavior=true` (default `false`) keeps the controlled browser's own
behaviour where Playwright changes it for testing. It applies to Firefox over WebDriver BiDi
(`moz-firefox`), Camoufox included; Chrome and Edge over CDP are unchanged, and so is the Java
API. Set it in the application JVM before `Playwright.create()`; the driver JVM receives it. It
does not make automation undetectable: `navigator.webdriver` stays `true`, and nothing here
spoofs a value or works around a challenge. Without the property everything stays as Playwright
does it.

What it changes, each part independent of the others:

| Area | Playwright | With `nativeBehavior` | Caller's override |
| --- | --- | --- | --- |
| Preferences (#44, #45, #46) | Writes test values to `user.js`: `dom.disable_open_during_load=false` (popup blocker off), `dom.min_background_timeout_value*=0`, `dom.timeout.enable_budget_timer_throttling=false`, `layout.testing.top-level-always-active=true` (background timers and rendering not throttled), `focusmanager.testmode=true`, `browser.contentblocking.features.standard` without Total Cookie Protection and fingerprinting/cryptomining lists, `dom.security.https_first=false`, `network.http.speculative-parallel-limit=0`, `network.manage-offline-status=false`, `geo.provider.testing=true` | These lines are left out of `user.js`, so the browser's defaults apply: Firefox's, or for Camoufox the values `camoufox.cfg` sets (it keeps the popup blocker, HTTPS-First and cookie partitioning on and sets `focusmanager.testmode` and `geo.provider.testing` itself). | `firefoxUserPrefs` still sets any of them. |
| Remote Agent preferences | Firefox's Remote Agent sets its recommended test preferences at startup for every preference without a user value (`remote/shared/RecommendedPreferences.sys.mjs`), the ones above among them | `remote.prefs.recommended=false`, so it sets none. Measured: without this the popup blocker stays off even when Playwright's line is gone. | `firefoxUserPrefs` `{"remote.prefs.recommended": true}`. |
| Default viewport (#47) | 1280x720 at device scale factor 1, `screen` set to the same size, which replaces the window and screen the browser or Camoufox's fingerprint reports | No viewport emulation, as with `setViewportSize(null)`; `page.viewportSize()` is `null`. Applies to `newContext()` and `launchPersistentContext()`. | A caller's `viewport`, `deviceScaleFactor` or `isMobile` is kept, with Playwright's checks. |
| Screenshot caret (#40) | `caret: "hide"` by default, which writes `caret-color` into the focused element's style | `caret: "initial"` by default; the element's style is not touched. | `setCaret(ScreenshotCaret.HIDE)` still hides it. |
| Text input (#41) | `fill()` and `keyboard.insertText()` assign the field's value and dispatch an untrusted `input` event (`isTrusted` `false`, no `inputType`) | The text goes through Firefox's editor (`execCommand("insertText")` on the focused field, from the utility world): one trusted `input` event with `inputType` `insertText`, as for text a user enters without keys. Measured with Firefox 158: Firefox fires no `beforeinput` for it, as the Input Events spec leaves out for `execCommand`; typing or pasting would fire one. A field the editor refuses gets Playwright's way. `fill("")` still presses Delete, and no key events are sent for the text, as in Playwright. | `pressSequentially()` types with key events. |
| Scrolling into view (#42) | Before an action, and in `scrollIntoViewIfNeeded()`, a script calls `scrollIntoView({block: "center"})`; the page sees `scroll` events without any `wheel` | Firefox's mouse wheel (`input.performActions`, wheel source) scrolls the innermost container that hides the target, over its visible part, in steps of at most 120 px towards centring the target, and frames before their content; trusted `wheel` events come before the `scroll` events. A target already fully in view is not scrolled. If the wheel does not bring it into view in 8 rounds (a container that ignores the wheel, a transformed frame), Playwright's `scrollIntoView` runs, logged at debug level. | - |
| Preload scripts (#51) | `exposeBinding`/`exposeFunction`, `addInitScript`, `clock`, `routeWebSocket` and tracing snapshots install scripts or globals in the page's main world | Unchanged, because they are what the caller asked for. The first one per context logs a warning on the driver's stderr that names the property. | - |

What it does not change:

- `navigator.webdriver` and the remote-control indicator (see above).
- Scripts Playwright itself runs in the page's main world, user activation and `__playwright_*`
  events during reads, and the style toggle during screenshots (#36-#39, #50): they are fixed or
  tracked separately, independent of this property.
- The other Playwright profile preferences (telemetry, updates, Safe Browsing, first-run pages and
  similar) that keep the browser from reaching out on its own.
- Context options the caller sets, such as `locale`, `timezoneId`, `userAgent` or `geolocation`,
  which override Camoufox's own values; leave them unset with Camoufox.
- `selectOption()` and `fill()` on date, time, color and range inputs, which
  set the value and dispatch untrusted `input`/`change` events (#41); Firefox's editor does not
  handle these controls.
- Mouse movement, which jumps to the target (#43), and dialogs or HTTP authentication answered
  without a handler (#48).

Persistent profiles: Firefox stores the values `user.js` sets in `prefs.js`, so a profile once
launched without the property keeps Playwright's values. A `prefs.js` line is removed only when it
is exactly a line Playwright wrote in the previous `user.js` (after playwright4j's end marker):
that value was applied by `user.js` on every launch. A value the user changed afterwards, or any
value in a profile without the marker (written by playwright4j 0.2.1 or earlier or by plain
Playwright), stays; set it in `about:config` or through `firefoxUserPrefs`. A value that the user
set to exactly Playwright's value cannot be told apart and is removed as well, so before the first
removal playwright4j copies the file to `prefs.js.playwright4j-backup` in the profile. Later cleanups
keep that copy; to restore, close the browser and copy it back over `prefs.js`.

Why `remote.prefs.recommended` matters: Firefox's Remote Agent, which serves WebDriver BiDi,
applies its recommended preferences at startup to every preference that has no user value and
resets them at shutdown. That list repeats most of Playwright's test values
(`dom.disable_open_during_load=false`, `focusmanager.testmode=true`, `geo.provider.testing=true`,
`network.manage-offline-status=false`, ...) and adds more, such as `dom.permissions.testing.enabled`
and `dom.input_events.security.minNumTicks=0`. Measured with Firefox 158 over BiDi: with
Playwright's lines removed but the Remote Agent's preferences on, a `window.open` during page load
still opened a popup; with `remote.prefs.recommended=false` Firefox blocked it. Camoufox does not
set `remote.prefs.recommended` in `camoufox.cfg`, and its values there are `defaultPref`, so they
apply once neither Playwright nor the Remote Agent sets a user value.

Measured on Linux with the property set: `firefoxSmoke` (including `FirefoxNativeBehaviorTest`) passes
with Firefox 158.0.1 and with Camoufox 156.0.1-beta.36 with both BiDi fixes
(`hotpatch_omni.py`), and so does `camoufoxSmoke` (fingerprint through `setEnv`, second page with
the fixes). With Camoufox the popup blocker stays on, as `camoufox.cfg` leaves it. The window-count
checks of `FirefoxPersistentWindowTest` need `xdotool` and were skipped there. `FirefoxNativeInputTest`
(text input and wheel scrolling, page, nested container and frame) passes with Firefox 158.0.1 on
Linux, headless. CI on Windows runs
`firefoxSmoke` with Firefox and with Camoufox, and `camoufoxSmoke`, with and without the property.

The property may become the default once its compatibility with the Playwright API is shown;
until then it is opt-in.

**Not covered**

- Juggler Firefox (Playwright's own build) was not measured; see the reference run above.
- TLS and HTTP/2 fingerprints are not captured, because the probe server speaks plain HTTP/1.1.
  They come from the browser's own network stack, which playwright4j does not touch.
