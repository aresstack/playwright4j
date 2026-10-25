# Camoufox over WebDriver BiDi

[Camoufox](https://github.com/daijro/camoufox) is a Firefox build with fingerprint configuration.
This page records whether playwright4j can drive it over WebDriver BiDi (Playwright's `moz-firefox`
channel), what fails and why, and how to reproduce the measurement.

**Status (25 October 2026, master after #29, Playwright Core 1.62).** A released Camoufox starts
and a persistent context opens on the tab Camoufox started with, but every further `newPage()` and
every `newContext()` fails because of two Camoufox behaviours described below. With two small fixes
to Camoufox's Remote Agent code (applied to `omni.ja`, see [Fixes](#fixes)) launch, persistent
contexts, new pages and new contexts work. The fingerprint configuration reaches Camoufox through
`setEnv` (since #30). Compared by hand on Windows with Camoufox's own launcher (Juggler), the same
fingerprint gives the same page values and the same network-side signals over BiDi, and a ChatGPT
login by hand works over BiDi ([Comparison](#comparison-with-camoufoxs-own-launcher-manual)).
Nothing in this page is part of a release.

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

Cause: Camoufox registers its Juggler window actor on every start, also without `-juggler-pipe`
(`additions/juggler/components/Juggler.js` calls `ActorManagerParent.addJSWindowActors` at module
level). Juggler's content script (`additions/juggler/content/main.js`) therefore runs in every page
and sets `docShell.forceActiveState = true` and `docShell.overrideHasFocus = true`. A docShell forced
active never becomes hidden, so no `visibilitychange` comes.

In a persistent context playwright4j's first `newPage()` takes over the start tab; every further
`newPage()` opens a foreground tab (Playwright behaviour) and hangs; `page.bringToFront()` would hang the same way.

Fix options:

1. Camoufox reports background tabs as hidden, as Firefox does.
2. playwright4j opens tabs with `background: true` and selects them some other way for Camoufox. The
   new page would then not be the selected tab, and `bringToFront()` still could not work. This is a
   departure from Playwright behaviour.

## Fixes

Reported to Camoufox as [daijro/camoufox#951](https://github.com/daijro/camoufox/issues/951), with
the fixes proposed in [daijro/camoufox#952](https://github.com/daijro/camoufox/pull/952).
Both fixes change only Remote Agent JavaScript. Pages see no difference: a background tab still
reports `visible` and `document.hasFocus()` stays `true`, as in released Camoufox. For Firefox nothing
changes (its application name is `Firefox`, and `docShell.forceActiveState` does not exist there).

1. `remote/shared/WindowManager.sys.mjs`: open windows for `Camoufox` as for `Firefox`.

   ```diff
        switch (lazy.AppInfo.name) {
   +      // Camoufox is Firefox under another application name.
   +      case "Camoufox":
          case "Firefox": {
   ```

2. `remote/webdriver-bidi/modules/windowglobal/browsingContext.sys.mjs`, `_awaitVisibilityState`:
   do not wait for `hidden` when the docShell is forced active.

   ```diff
   +    if (value === "hidden" && win.docShell?.forceActiveState) {
   +      // Camoufox: Juggler forces the docShell active, so this document stays
   +      // "visible" while in the background and no visibilitychange will come.
   +      // Do not wait for it; the page-visible state is left unchanged.
   +      return;
   +    }
   +
        try {
          // Otherwise, wait for the next visibilitychange event.
   ```

The fixes are meant for Camoufox upstream. To try them without building Camoufox,
[`camoufox/hotpatch_omni.py`](camoufox/hotpatch_omni.py) applies them to a copy of an installed
Camoufox (it keeps `omni.ja.orig`):

```bash
python docs/camoufox/hotpatch_omni.py <copy of the Camoufox folder> window visibility
```

Start the patched copy with a fresh profile (or once with `-purgecaches`). The texts it replaces are
the same in Camoufox 156.0.1-beta.36 and 158.0-beta.40.

## Results with the fixes

Camoufox 156.0.1-beta.36 with both fixes, persistent context headed:

| Run | Result |
| --- | --- |
| Linux, `FirefoxPersistentWindowTest` (process and window names extended to `camoufox` locally) | 5 of 6 pass |
| Linux, this branch on master after #29 (Core 1.62), `firefoxSmoke` | 9 of 9 pass (Firefox 158.0.1: 9 of 9) |
| Windows (Angelo, master `4cb607f`, fixes reconstructed from the description, process name extended locally) | `FirefoxBiDiSmokeTest` 2 of 2; `firefoxSmoke` 6 of 8 |
| Linux and Windows, `camoufoxSmoke` (fingerprint through `setEnv`, two pages) | Linux: passes; Windows (Angelo, the script's hotpatch, master after #30 and #33): 2 of 2 pass |

The two failures of the earlier runs, both fixed in this branch:

- `persistentContextUsesTheStartTabAndOpensPagesAsTabs` (Linux and Windows): `prefs.js` after close
  lacked `user_pref("browser.startup.page", 0);`. Camoufox already has 0 as its default
  (`settings/camoufox.cfg`), and Firefox writes to `prefs.js` only values that differ from the
  default. The preference applied; the test's way of proving it did not hold for Camoufox. The test
  now checks that the profile's own value (3) did not win.
- `lastTabClosedInFirefoxLeavesTheContextOpenWithoutPages` (Windows): after Ctrl+W on the last tab
  and `newPage()`, navigating to a `data:` URL was refused (`browsingContext.navigate`: unsupported
  operation). The cause is in playwright4j, not in Camoufox. Firefox replaces the closed tab with a
  tab that shows `about:blank` until `about:newtab` commits, once the process for that privileged
  page is up: on Linux 74 to 182 ms after the close, on Angelo's machine evidently later than the
  test's wait of at least 1.5 s (not measured there). A `newPage()` in that time took the tab over
  as it was; `about:newtab` then loaded into the page, and Firefox refuses `data:` there. Now only
  the blank tab playwright4j opens itself before closing the last page is taken over as it is; in
  place of Firefox's replacement a fresh tab is opened. The test variant
  `lastTabClosedInFirefoxWhileItsChildProcessesStartSlowly` delays Firefox's child processes
  (`MOZ_DEBUG_CHILD_PAUSE=3`): without the fix it fails with the same error on Linux with Firefox
  158, with the fix it passes.

## Fingerprint configuration

Camoufox reads its fingerprint in C++ from environment variables `CAMOU_CONFIG_1`, `CAMOU_CONFIG_2`,
… (concatenated to one JSON object; `CAMOU_CONFIG` as a fallback), and startup preferences from
`CAMOU_PREFS_1`, … . This is independent of the automation protocol: started over BiDi with
`CAMOU_CONFIG_1={"navigator.platform":"P4JTest","navigator.hardwareConcurrency":3}`, a page reads
`P4JTest` and `3`.

Since #30 playwright4j passes `setEnv` to the browser process. As in Playwright, `setEnv` replaces
the whole environment, so pass the inherited one along (on Windows the browser needs variables such
as `SystemRoot`):

```java
Map<String, String> env = new HashMap<>(System.getenv());
env.put("CAMOU_CONFIG_1", configJson);   // split into CAMOU_CONFIG_1, _2, ... above 2047 characters on Windows
BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir,
    new BrowserType.LaunchPersistentContextOptions()
        .setChannel("moz-firefox")
        .setExecutablePath(Paths.get(camoufoxExecutable))
        .setEnv(env));
```

The official Camoufox launchers split the JSON into chunks of 2047 characters on Windows and 32767
elsewhere. `CamoufoxFingerprintTest` (task `camoufoxSmoke`) checks that a configured
`navigator.platform` and `navigator.hardwareConcurrency` reach the first page (and, with
`CAMOUFOX_BIDI_FIXES=true`, a second page), and that they are absent without `setEnv`. Measured on
Linux with Camoufox 156.0.1-beta.36, released and with the fixes: passes.

## Comparison with Camoufox's own launcher (manual)

The acceptance check compares two runs of the same Camoufox copy with the same fingerprint:

- **A**: Camoufox's own launcher (npm package `camoufox` 0.5.8 with `playwright-core` 1.62.0), which
  drives Camoufox over Juggler. This is the reference (100 points at amiabot.app).
- **B**: playwright4j over WebDriver BiDi.

Two tools start both runs with identical launch options: `docs/camoufox/juggler-reference.mjs` for
A (Node only as a tool for the reference run, not part of playwright4j) and the Gradle task
`camoufoxManual` (`CamoufoxManualSessionTest`) for B. Both take the options from one JSON file,
start a persistent context headed without viewport and media emulation (as Camoufox's launcher does
when the fingerprint sets the window size), print what the page sees (user agent, platform, cores,
screen, window, time zone, colour scheme, focus, visibility, WebGL renderer) and keep the browser
open until its window is closed. Neither prints values from the environment.

Measured on Linux with Camoufox 156.0.1-beta.36 and both fixes, one fingerprint drawn by the
launcher (`{"os":"linux"}`): A and B print identical values (user agent `Firefox/156.0`, platform
`Linux x86_64`, 4 cores, screen 1366x768, window 1366x741, `navigator.webdriver` false, `hasFocus()`
true, WebGL renderer from the fingerprint). amiabot.app and ChatGPT are not reachable from that
environment.

Measured on Windows (Angelo, 25 October 2026, Camoufox 156.0.1-beta.36 patched with
`hotpatch_omni.py`, SHA-256 of both changed files checked, one saved fingerprint for both runs,
fresh profiles, headed at `about:blank`): the 13 printed values are identical in A and B (user
agent, platform, `navigator.webdriver` false, cores, languages, screen, window, device pixel ratio,
time zone, colour scheme, focus, visibility, WebGL renderer); no browser process was left over.

### Steps (Windows)

1. Camoufox 156.0.1-beta.36 copy, e.g. `G:\camoufox-156`, patched with the script from this
   repository: `python docs\camoufox\hotpatch_omni.py G:\camoufox-156 window visibility`. Both fixes
   change only Remote Agent code, which a Juggler run does not use, so A and B use the same copy.
2. A folder with the reference launcher: `npm install camoufox@0.5.8 playwright-core@1.62.0`, and a
   copy of `docs/camoufox/juggler-reference.mjs` next to `node_modules`.
3. Draw one fingerprint for both runs. Put the Camoufox options of the usual reference run (at least
   `os`; without it the launcher may pick another operating system) into a file, e.g.
   `G:\cmp\camoufox-options.json` with `{"os":"windows"}`, then:

   ```powershell
   node juggler-reference.mjs save G:\cmp\launch.json G:\camoufox-156\camoufox.exe G:\cmp\camoufox-options.json
   ```

   `launch.json` holds the fingerprint (`CAMOU_CONFIG_n`), startup preferences (`CAMOU_PREFS_n`),
   Firefox preferences and arguments, nothing else from the environment. Keep it out of the
   repository.
4. Run A with a new profile folder:

   ```powershell
   node juggler-reference.mjs run G:\cmp\launch.json G:\camoufox-156\camoufox.exe G:\cmp\profile-a https://amiabot.app/
   ```

5. Run B with another new profile folder, from the playwright4j checkout:

   ```powershell
   $env:CAMOUFOX_MANUAL = 'true'
   $env:CAMOUFOX_EXECUTABLE = 'G:\camoufox-156\camoufox.exe'
   $env:CAMOUFOX_LAUNCH_OPTIONS = 'G:\cmp\launch.json'
   $env:CAMOUFOX_PROFILE = 'G:\cmp\profile-b'
   $env:CAMOUFOX_START_URL = 'https://amiabot.app/'
   .\gradlew.bat :playwright-java-contract-tests:camoufoxManual
   ```

   The task inherits the environment and adds the variables from `launch.json` (`setEnv` replaces the
   environment, see above). It waits at most 30 minutes (`CAMOUFOX_MANUAL_MINUTES`).
6. In each run record: the printed values, the amiabot.app score and its individual findings
   (screenshot), then open chatgpt.com in a new tab and log in **by hand** (no credentials in code,
   no autofill) and note ok, challenge or blocked. Close the browser window to end the run.

| | A (launcher, Juggler) | B (playwright4j, BiDi) |
| --- | --- | --- |
| printed values | the same 13 values as B | the same 13 values as A |
| amiabot.app score | 75/100, also after reloading (the first load hung while collecting signals) | 75/100, then 100/100 after reloading |
| amiabot.app findings | one deduction, `behavior_score_low` (no pointer or scroll events recorded); `likely_human`, risk low | first load: the same single deduction; after reloading none (pointer and scroll events recorded); `likely_human`, risk low |
| Cloudflare bot management score (amiabot.app report) | 99 | 99 |
| Google search | no CAPTCHA | no CAPTCHA |
| ChatGPT login by hand | not tested | signed in (with two-factor authentication), a new conversation sent and answered |

Measured on Windows on 25 October 2026 (Angelo, both browsers operated by hand, same saved
fingerprint). The 25-point difference is amiabot.app's behaviour score: it depends on the input
during the run (no events in A, pointer movement and scrolling in B after reloading), not on the
protocol. Both runs report no deduction for WebDriver, fingerprint, network or WebRTC. Known and
open, to be looked at separately: the first load of amiabot.app hung while collecting signals (in
the Juggler run; a reload helped). Angelo accepted the Camoufox browser path over playwright4j and
BiDi on that basis; the JavaScript hooks and the Browser Event Hub connection of GPT Desktop are
not covered by it.

If A and B differ, the remaining differences are on the automation side, not in the fingerprint:
Camoufox's Juggler-only features (see below), the Remote Agent being active under BiDi, and the
Firefox preferences each Playwright backend writes into the profile. Compare `prefs.js` and
`user.js` of `profile-a` and `profile-b` first.

On Linux the launcher also sets `FONTCONFIG_FILE` to a configuration that points to the `fonts`
folder next to the Camoufox executable. A copy without that folder crashes at startup (SIGSEGV
after "Failed to initialize shared font list") in both runs.

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

Fingerprint through `setEnv` (Windows: `camoufox.exe`):

```bash
CAMOUFOX_EXECUTABLE=/path/to/camoufox/camoufox-bin CAMOUFOX_BIDI_FIXES=true \
  ./gradlew :playwright-java-contract-tests:camoufoxSmoke
```

Window and tab behaviour:

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
