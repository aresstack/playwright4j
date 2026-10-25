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
  the fingerprint via setEnv (#34, #35). The release gate runs the same checks.
- The driver JVM receives the application's TLS, proxy and VLC system properties, and
  `playwright4j.driver.jvmArgs` / `playwright4j.driver.classpath` (#32).

### Fixed

- TLS hostname verification stays on unless `ignoreHTTPSErrors` is set; `crypto.randomBytes` and
  `randomUUID` use `SecureRandom` (#25).
- `setEnv` reaches the browser process for `launch()` and `launchPersistentContext()`, as in
  Playwright: it replaces the inherited environment (#30).
- Playwright's force-kill reaches the real browser process tree; `child.kill()` escalates after
  5 seconds; a failed start ends the whole tree (#33).
- The driver starts from Spring Boot executable jars, application servers and custom class
  loaders (#32).
- Firefox no longer enables `dom.testing.testutils.enabled` and does not activate a page on load
  without input (#26, [`docs/browser-signals.md`](docs/browser-signals.md)).
- Firefox keeps the `user.js` of a persistent profile (#28).
- `newPage()` after the last Firefox tab was closed no longer adopts Firefox's replacement tab (#31).

### Known limitations

- Firefox is covered by smoke tests only; the upstream contract suite runs against Chromium (#11).
- CI runs on Windows only.
- Not yet addressed, planned after 0.3.0: #41 to #43 and #48.
- Signals by which a page can tell Playwright's Firefox BiDi automation from a manual session are
  tracked in #36 to #45.
- See [`problems.md`](problems.md) for the documented divergences.

## 0.2.1

- Fixes Firefox `launchPersistentContext()`, which fails in `0.2.0`.

## 0.2.0

- Firefox over WebDriver BiDi (channel `moz-firefox`), `launch-server` without Node.js, Maven POM
  resolvable (`org.graalvm.polyglot:js` declared with `<type>pom</type>`).
