# Changelog

## 0.3.0

Built on Playwright Core `1.62.0`. Use it with Playwright Java `1.62.0`.

### Changed

- Playwright Core upgraded from `1.59.0` to `1.62.0` (#29). The Core package is now taken from
  Microsoft's `driver` artifact at build time.
- Firefox pages are tabs (#27): `launchPersistentContext()` adopts the start tab instead of opening
  another one, `newPage()` opens a tab, and closing the last page leaves `pages()` empty as in Chrome.

### Added

- Camoufox over WebDriver BiDi, with documentation and opt-in checks (#31,
  [`docs/camoufox.md`](docs/camoufox.md)).
- Windows CI now checks Camoufox over WebDriver BiDi: one native window on a persistent start and
  the fingerprint via setEnv (#34, #35).
- Opt-in `-Dplaywright4j.bidi.nativeBehavior=true` (Firefox and Camoufox over WebDriver BiDi): keeps
  the browser's own preferences instead of Playwright's test values (popup blocker, timers, focus,
  cookie and tracking protection, HTTPS-First, and `remote.prefs.recommended=false`), no default
  viewport, screenshot caret untouched, and a warning on main-world preload scripts. Default off;
  see [`docs/browser-signals.md`](docs/browser-signals.md) (#40, #44 to #47, #51).
- The driver JVM receives the application's TLS, proxy and VLC system properties, and
  `playwright4j.driver.jvmArgs` / `playwright4j.driver.classpath` (#32).
- Under `nativeBehavior`, `fill()` and `keyboard.insertText()` enter text through Firefox's editor
  (trusted `input` event), and scrolling into view uses the mouse wheel (#41, #42, #61).
- Opt-in `-Dplaywright4j.bidi.naturalPointer=true` (Firefox and Camoufox over WebDriver BiDi):
  mouse moves without `steps` follow a curved, Fitts-timed path, and clicks without `delay` pause
  before the press and hold the button (#43, #64).
- Opt-in `-Dplaywright4j.bidi.unhandledPromptDelay=MIN-MAX` (milliseconds, at most 10000): dialogs
  no handler takes and HTTP auth challenges without matching credentials get Playwright's usual
  answer after a random delay in that range (#48, #63).
- `com.aresstack.playwright4j.bidi.FirefoxBidi`: read-only WebDriver BiDi access on Firefox (DOM,
  response bodies, cookies, network and browsing-context events) without running script in the
  page (#56, [`docs/passive-acquisition.md`](docs/passive-acquisition.md)).
- Firefox over BiDi: `keyboard.down`, `up` and `press` accept a single grapheme outside the US
  layout (`ё`, `😊`, `é`) and send it as a key action (#73).
- `docs/camoufox/hotpatch_omni.py` installs a pinned Camoufox release with both WebDriver BiDi
  fixes (archive and file SHA-256 checked) (#60).
- The full upstream Playwright Java suite (`1.62.0`) is a release gate for Chrome, Edge and Firefox
  on Windows and Linux; tolerated failures are listed per browser in
  `playwright-java-contract-tests/known-limitations/` (#11, #72).

### Fixed

- TLS hostname verification stays on unless `ignoreHTTPSErrors` is set; `crypto.randomBytes` and
  `randomUUID` use `SecureRandom` (#25).
- `APIRequestContext` honours the `proxy` option: HTTP proxies, CONNECT tunnels for https and Basic
  proxy credentials; SOCKS and `https://` proxies fail with a clear error instead of connecting
  directly (#53).
- `setEnv` reaches the browser process for `launch()` and `launchPersistentContext()`, as in
  Playwright: it replaces the inherited environment (#30).
- Playwright's force-kill reaches the real browser process tree; `child.kill()` escalates after
  5 seconds; a failed start ends the whole tree (#33).
- The driver starts from Spring Boot executable jars, application servers and custom class
  loaders (#32).
- Firefox no longer enables `dom.testing.testutils.enabled` and does not activate a page on load
  without input (#26, [`docs/browser-signals.md`](docs/browser-signals.md)).
- Firefox keeps the `user.js` of a persistent profile (#28).
- Firefox: `route()` in one context no longer makes the whole browser bypass the HTTP cache; the
  bypass now applies only to the routing pages (#49).
- Firefox: Playwright's own code no longer runs in the page's main world. There is no
  `window.__pw_bidiInsertText` global anymore, and `insertText`, iframe bounding boxes and element
  handle previews run in the utility world (#36, #39, part of #50).
- Firefox: reading a page no longer activates it (utility-world evaluations without
  `userActivation`), Playwright's `__playwright_mark_target__`/`__playwright_reset_targets__` events
  are only dispatched while a trace records snapshots, and screenshots skip the style toggle (#37,
  #38, #40).
- `newPage()` after the last Firefox tab was closed no longer adopts Firefox's replacement tab (#31).
- Under `nativeBehavior`, main-world evaluations do not give the page user activation, and file
  drops run outside the main world (#37, #50, #62).
- `Response.body()` of a 304 revalidation on Firefox returns the cached body (#56).
- Firefox: `title()` and other utility-world calls no longer fail with an empty result after a new
  document replaced the previous one (#66, #67).
- Firefox: BiDi events that arrive in an order Playwright's backend did not expect (late iframe
  name, commit or intercepted request of an unknown frame, destroyed realms, stale realm list at
  tab takeover) no longer break frames or requests (#74).
- Found by the upstream gate: WebAuthn with P-256/ECDSA keys, `APIResponse.serverAddr()`, Firefox
  element handle previews and `Dialog.accept()` for `beforeunload` (#72).
- On Windows, ending the browser no longer kills unrelated processes whose parent process ID was
  reused (#72).

### Known limitations

- Without `nativeBehavior`, Firefox `allTextContents()`, `allInnerTexts()` and
  `locator.boundingBox()` still activate the page, because they evaluate in the main world (#50).
- Signals by which a page can tell Playwright's Firefox BiDi automation from a manual session are
  tracked in #36 to #45.
- See [`problems.md`](problems.md) for the documented divergences.

## 0.2.1

- Fixes Firefox `launchPersistentContext()`, which fails in `0.2.0`.

## 0.2.0

- Firefox over WebDriver BiDi (channel `moz-firefox`), `launch-server` without Node.js, Maven POM
  resolvable (`org.graalvm.polyglot:js` declared with `<type>pom</type>`).
