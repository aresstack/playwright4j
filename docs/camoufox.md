# Camoufox over WebDriver BiDi

[Camoufox](https://github.com/daijro/camoufox) is a Firefox build with fingerprint configuration.
This page records whether playwright4j can drive it over WebDriver BiDi (Playwright's `moz-firefox`
channel), what fails and why, and how to reproduce the measurement.

**Status (25 October 2026, master `4cb607f`, Playwright Core 1.59): not usable yet.** Camoufox
starts and a persistent context opens on the tab Camoufox started with, but every further
`newPage()` and every `newContext()` fails because of two
Camoufox behaviours described below. Nothing in this page is part of a release.

## What was measured

| Camoufox build | `application.ini` | Remote Agent (WebDriver BiDi) in `omni.ja` |
| --- | --- | --- |
| `158.0-beta.40`, Linux x86_64 | `Name=Camoufox`, Firefox's application ID | yes |
| `156.0.1-beta.36`, Linux x86_64 | `Name=Camoufox`, Firefox's application ID | yes |

Started with `--remote-debugging-port=0`, Camoufox prints `WebDriver BiDi listening on ws://…` like
Firefox. It reports `browserName: "camoufox"` in `session.new`.

playwright4j launch options (headed, Linux, Xvfb):

```java
playwright.firefox().launchPersistentContext(userDataDir,
    new BrowserType.LaunchPersistentContextOptions()
        .setChannel("moz-firefox")
        .setExecutablePath(Paths.get("/path/to/camoufox/camoufox-bin"))
        .setHeadless(false));
```

Results with Camoufox 158.0-beta.40 (`firefoxSmoke` task, `FIREFOX_EXECUTABLE` pointing at
`camoufox-bin`):

| Test | Result | Cause |
| --- | --- | --- |
| `FirefoxBiDiSmokeTest.launchPersistentContextWithInstalledFirefox` (launch, first page = the start tab, `setContent`, `evaluate`, close) | passes | |
| `FirefoxBiDiSmokeTest.launchInstalledFirefoxEvaluateAndClose` (`launch()` + `newPage()`) | fails | new windows |
| `FirefoxPersistentWindowTest.theFirstPageIsTheTabFirefoxStartedWith` | passes | |
| `FirefoxPersistentWindowTest.firefoxEndedFromOutsideClosesTheContextAndTheProfileStartsAgain` | passes | |
| `FirefoxPersistentWindowTest.persistentContextUsesTheStartTabAndOpensPagesAsTabs` | hangs in `newPage()` | foreground tabs |
| `FirefoxPersistentWindowTest.lastTabClosedInFirefoxLeavesTheContextOpenWithoutPages` | hangs | foreground tabs |
| `FirefoxPersistentWindowTest.anotherRunningFirefoxIsLeftAlone` | hangs | foreground tabs |
| `FirefoxPersistentWindowTest.newContextGetsOneWindowAndItsPagesOpenAsTabs` | fails | new windows |

The window tests find Firefox processes and X11 windows by the names `firefox` / `firefox-bin`.
Camoufox's are called `camoufox` / `camoufox-bin`, so for this run the names were extended locally.
Without that, `FirefoxPersistentWindowTest` sees no windows, and the leftover-process check of every
Firefox smoke test checks nothing.

## Camoufox behaviour 1: no new windows

`browsingContext.create` with `type: "window"` fails:

```
unsupported operation: openWindow() not supported in Camoufox
```

Firefox's Remote Agent opens a window in `WindowManager.openBrowserWindow()`
(`chrome://remote/content/shared/WindowManager.sys.mjs`) only inside
`switch (AppInfo.name) { case "Firefox": … }`. Camoufox renames the application, so every other name
ends in this error. Tabs are not affected by the name: `TabManager` checks the application ID, which
Camoufox keeps.

Playwright's BiDi layer opens a window for every page of a non-persistent context, so `launch()` +
`newPage()` and `newContext()` + `newPage()` fail.

Fix options:

1. Camoufox accepts its own name there (`case "Camoufox":` next to `case "Firefox":`). One line in
   Camoufox; playwright4j stays unchanged.
2. playwright4j opens a tab in an existing window instead when this error comes back. This departs
   from the model in `FirefoxPersistentWindowTest` (a new context gets its own window).

## Camoufox behaviour 2: background tabs stay "visible"

In Camoufox a tab that loses the foreground keeps `document.visibilityState === "visible"`. Firefox
reports `"hidden"`. The Remote Agent waits for exactly that state change:

- `browsingContext.create` with `type: "tab"` and without `background: true` creates the tab (the
  `browsingContext.contextCreated` event arrives) but never answers, because it waits for the
  previously selected tab to become hidden;
- `browsingContext.activate` never answers for the same reason.

Raw WebDriver BiDi, same Xvfb display, fresh profile, no Playwright involved:

| Command | Firefox 158.0.1 | Camoufox 158.0-beta.40 |
| --- | --- | --- |
| `browsingContext.create {type:"tab", referenceContext, background:true}` | answers in ~70 ms | answers in ~80 ms |
| `browsingContext.create {type:"tab", referenceContext}` | answers in ~70 ms | no answer (15 s timeout) |
| `browsingContext.activate` on a background tab | answers in ~30 ms | no answer (10 s timeout) |
| `visibilityState` of the previous tab after the switch | `hidden` | `visible` |
| `browsingContext.create {type:"window"}` | answers | `openWindow() not supported in Camoufox` |

The preference `focusmanager.testmode` does not change this (checked both ways on both browsers).

In a persistent context playwright4j's first `newPage()` takes over the start tab; every further
`newPage()` opens a foreground tab (Playwright behaviour) and hangs; `page.bringToFront()` would hang the same way.

Fix options:

1. Camoufox reports background tabs as hidden, as Firefox does.
2. playwright4j opens tabs with `background: true` and selects them some other way for Camoufox. The
   new page would then not be the selected tab, and `bringToFront()` still could not work. This is a
   departure from Playwright behaviour.

## Fingerprint configuration

Camoufox reads its fingerprint in C++ from environment variables `CAMOU_CONFIG_1`, `CAMOU_CONFIG_2`,
… (concatenated to one JSON object; `CAMOU_CONFIG` as a fallback), and startup preferences from
`CAMOU_PREFS_1`, … . This is independent of the automation protocol: started over BiDi with
`CAMOU_CONFIG_1={"navigator.platform":"P4JTest","navigator.hardwareConcurrency":3}`, a page reads
`P4JTest` and `3`.

playwright4j does not pass `BrowserType.LaunchOptions.setEnv` to the browser process yet, so this
configuration cannot be set through Playwright's API today. The variables have to be in the
environment of the JVM that starts the driver.

## Juggler instead of BiDi (comparison)

Camoufox's own launchers drive it over Juggler, Playwright's Firefox protocol. Several Camoufox
features live in its Juggler code and do not exist under BiDi: `page.evaluate()` in an isolated
world, no synthetic user activation, per-context fingerprint setters, humanized mouse movement.

Juggler talks over file descriptors 3 and 4 (`-juggler-pipe`); on Windows the browser takes the pipe
handles from `PW_PIPE_READ` / `PW_PIPE_WRITE`. Java's `ProcessBuilder` passes only stdin, stdout and
stderr, so playwright4j cannot provide those pipes as it stands. Possible without shipping native
binaries: two FIFOs and `/bin/sh` on Linux and macOS; on Windows only inheritable pipe handles
created through the Foreign Function & Memory API (final in Java 22) or a PowerShell relay process.
Juggler has no version negotiation, so the embedded Playwright Core has to match the Camoufox build.
This route is not planned at the moment.

## Reproducing

1. Download a Camoufox build from <https://github.com/daijro/camoufox/releases> and unpack it.
2. Run the Firefox smoke tests against it (Linux needs an X server, e.g. `xvfb-run`, and `xdotool`
   for the window counts):

   ```bash
   FIREFOX_EXECUTABLE=/path/to/camoufox/camoufox-bin \
     xvfb-run -a ./gradlew :playwright-java-contract-tests:firefoxSmoke
   ```

   On Windows use `camoufox.exe`.
3. Raw BiDi check of behaviour 2: start
   `camoufox-bin -no-remote --remote-debugging-port=0 -profile <empty dir> about:blank`, connect to
   `<printed ws url>/session`, send `session.new`, `browsingContext.getTree`, then
   `browsingContext.create` with `{"type":"tab","referenceContext":"<first context>"}`. Firefox
   answers; Camoufox creates the tab and does not answer.
