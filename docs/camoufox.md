# Camoufox over WebDriver BiDi

[Camoufox](https://github.com/daijro/camoufox) is a Firefox build with fingerprint configuration.
This page records whether playwright4j can drive it over WebDriver BiDi (Playwright's `moz-firefox`
channel), what fails and why, and how to reproduce the measurement.

**Status (25 October 2026, Playwright Core 1.62).** A released Camoufox starts and a persistent
context opens on the tab Camoufox started with, but every further `newPage()` and every
`newContext()` fails because of two Camoufox behaviours described below. Two small fixes to
Camoufox's Remote Agent code remove both ([Fixes](#fixes)); they are proposed to Camoufox but in no
Camoufox release yet. [Installing Camoufox with the fixes](#installing-camoufox-with-the-fixes) sets
up a pinned Camoufox release with both fixes on Windows and Linux; with it launch, persistent
contexts, new pages and new contexts work. The fingerprint configuration reaches Camoufox through
`setEnv` (since #30). Compared by hand on Windows with Camoufox's own launcher (Juggler), the same
fingerprint gives the same page values and the same network-side signals over BiDi, and a ChatGPT
login by hand works over BiDi ([Comparison](#comparison-with-camoufoxs-own-launcher-manual)).
playwright4j does not ship Camoufox.

## Installing Camoufox with the fixes

[`camoufox/hotpatch_omni.py`](camoufox/hotpatch_omni.py) downloads a pinned Camoufox release from
<https://github.com/daijro/camoufox/releases>, checks the archive's SHA-256, unpacks it and applies
both [fixes](#fixes) to its `omni.ja`. It also checks the SHA-256 of both changed files before and
after the change, so it patches only the builds listed here and produces exactly the files listed
here. It needs Python 3 and nothing beyond its standard library, writes only into the folder it is
given, and does not build Camoufox.

Pinned releases, x86_64. The default is `156.0.1-beta.36`, the build measured on Linux and Windows
and accepted by hand ([Comparison](#comparison-with-camoufoxs-own-launcher-manual)).

| Release | Archive | SHA-256 of the archive |
| --- | --- | --- |
| `156.0.1-beta.36` | `camoufox-156.0.1-beta.36-win.x86_64.zip` | `096fd4832a7a32f2aa7f7bdd07fe6253dda296cbe783acd7bc2c86cf5ae62031` |
| `156.0.1-beta.36` | `camoufox-156.0.1-beta.36-lin.x86_64.zip` | `72637885ee67c93466ad377831c647192f8f08fb5c6fd9279de1320b49dd2b53` |
| `158.0-beta.40` | `camoufox-158.0-beta.40-win.x86_64.zip` | `2d3e33207c2d5853e817e8317f0e33124ffb7054bd5102c8b84546e3d15a6447` |
| `158.0-beta.40` | `camoufox-158.0-beta.40-lin.x86_64.zip` | `2ead498d4765394addd3278d6a663962f3f99b189a99d3f966de56656cefc823` |

The two changed files in `omni.ja`, the same in the Windows and the Linux archive of a release:

| File in `omni.ja` | Release | SHA-256 as released | SHA-256 with the fix |
| --- | --- | --- | --- |
| `chrome/remote/content/shared/WindowManager.sys.mjs` | `156.0.1-beta.36` | `6a4a8106b53909e93fecb0ae5c4505aa99325fd35b273487f61ae8d8b6b44fb6` | `047cca48c67255dc76523faa054487a641d1103e188c62ed2b06cd7756b0d46b` |
| `chrome/remote/content/shared/WindowManager.sys.mjs` | `158.0-beta.40` | `3fc4ff2e3585b173ca99c04895aa1616222eb785cd5aa3a14c07a3d7209719c1` | `42df8aeb4f98f33a9076f6178435700ecd9f05d5029161a4bdaa2f6ed3c019ec` |
| `chrome/remote/content/webdriver-bidi/modules/windowglobal/browsingContext.sys.mjs` | both | `26b244cc760a7dd8197566c90f6fdc9a4dd264e651d7bfb7fc3c2febdbc3bdd2` | `4202f76e04e88059c1e6b9797228f4a04f9ed1d210984d5818f00ccc790df460` |

The archive is about 1.3 GB and the unpacked browser about 2.5 GB. The script downloads into the
new folder and deletes the archive afterwards, so the drive of the new folder needs about 4 GB
during the installation.

### Windows (PowerShell)

From the playwright4j checkout:

```powershell
python docs\camoufox\hotpatch_omni.py --install G:\camoufox-156
python docs\camoufox\hotpatch_omni.py --check G:\camoufox-156
```

The last line of `--install` names the executable, here `G:\camoufox-156\camoufox.exe`. `--check`
prints `window: in place` and `visibility: in place` and exits with 0, otherwise with 1. To use an
archive downloaded before (for example with a browser), add
`--archive $HOME\Downloads\camoufox-156.0.1-beta.36-win.x86_64.zip`; it is accepted only with a
pinned SHA-256. `--release 158.0-beta.40` installs the other pinned release.

### Linux

```bash
python3 docs/camoufox/hotpatch_omni.py --install ~/camoufox-156
python3 docs/camoufox/hotpatch_omni.py --check ~/camoufox-156
```

The executable is `~/camoufox-156/camoufox-bin`. Keep the folder whole: it holds the `fonts` and
`fontconfig` folders Camoufox needs on Linux (see the end of
[Comparison](#comparison-with-camoufoxs-own-launcher-manual)). A headed browser needs an X server;
without a display run under `xvfb-run -a`.

### Using it

Pass the executable to `setExecutablePath` with the `moz-firefox` channel, as in
[What was measured](#what-was-measured); for the fingerprint see
[Fingerprint configuration](#fingerprint-configuration). Start the patched browser with a new
profile. A profile used with the released build before needs one start with `-purgecaches` (as an
argument in `setArgs`), which clears the script cache Firefox keeps in the profile.

### An already unpacked Camoufox

`python3 docs/camoufox/hotpatch_omni.py <folder>` (on Windows `python`) patches a Camoufox unpacked
before (the folder that holds `omni.ja` and the executable), with the same checks of the two files.
Close every Camoufox started from that folder first. The released file stays as `omni.ja.orig`;
running the script again changes nothing. Patch a copy of its own rather than an installation
another tool keeps up to date: an update of such an installation brings back the released
`omni.ja`, and the script has to run again.

### Undoing

Delete the folder, or put the released file back (Windows, then Linux):

```powershell
Copy-Item G:\camoufox-156\omni.ja.orig G:\camoufox-156\omni.ja -Force
```

```bash
cp ~/camoufox-156/omni.ja.orig ~/camoufox-156/omni.ja
```

### When the script refuses

It exits with a code other than 0 and leaves `omni.ja` as it was:

| Message | Meaning |
| --- | --- |
| `archive SHA-256 … is not a pinned Camoufox release` | the downloaded or given archive is not one of the archives above, for example because a release asset was replaced |
| `unknown Camoufox build (…)` | `omni.ja` comes from a build that is not listed above |
| `the text to replace is not in this Camoufox build` | the Remote Agent code changed and the fix has to be adapted |
| `the fix is present but the file is not one this script produced` | the build already contains a fix, for example a Camoufox release with the fixes ([Camoufox upstream](#camoufox-upstream)) |

### Other Camoufox builds

For a build that is not listed, `--unverified` applies the same changes without the file checks
(`python3 docs/camoufox/hotpatch_omni.py --unverified <folder>`, then
`python3 docs/camoufox/hotpatch_omni.py --check --unverified <folder>`; on Windows `python`). Run
the checks of [Reproducing](#reproducing) against it before relying on it. To pin it, add the archive and file hashes to `RELEASES` and `KNOWN_FILES` in the
script, together with the test results.

## Camoufox upstream

The two behaviours are reported to Camoufox as
[daijro/camoufox#951](https://github.com/daijro/camoufox/issues/951) and the fixes proposed in
[daijro/camoufox#952](https://github.com/daijro/camoufox/pull/952) (open on 25 October 2026).
playwright4j does not wait for that outcome:

- The fixes are kept in this repository. [`camoufox/patches/`](camoufox/patches) holds both as
  Camoufox patches (for Camoufox's `browser/patches/`, applied by its `make dir`), and
  `hotpatch_omni.py` carries the same changes for released builds.
- The fork [aresstack/camoufox](https://github.com/aresstack/camoufox/tree/claude/project-thread-ot6uky),
  branch `claude/project-thread-ot6uky`, holds them as commits `ec5f72b` and `8640374` on Camoufox
  `e2c542a` ([aresstack/camoufox#1](https://github.com/aresstack/camoufox/pull/1)), together with the
  patch guard `browser/tests/playwright/patches/webdriver-bidi-tabs.py`: it fails on a released
  build and passes with both patches. #952 is opened from that branch, which stays as the reference.
- The Windows CI jobs `firefox-smoke (camoufox, nativeBehavior=false|true)` install the default
  pinned release through the script, so they test exactly the files listed above, whatever happens
  upstream.

| Upstream | Effect on playwright4j |
| --- | --- |
| merges #952, or fixes the behaviours another way | only new Camoufox releases change; the pinned releases stay as they are. A release with the fixes needs no patching. `--install` accepts it only once its archive is added to `RELEASES`, and `--check` recognises the fixes only in the form above; the checks of [Reproducing](#reproducing) show whether such a release behaves the same. |
| asks for changes, or closes #952 | none |
| replaces a pinned release archive | `--install` stops at the archive's SHA-256 |
| removes a pinned release | the download fails; an archive kept from before still installs with `--archive` |

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

At the time of this run the window tests found Firefox processes and X11 windows only by the names
`firefox` / `firefox-bin`, so the Camoufox names were added locally. Since #31 the tests
count `camoufox`, `camoufox.exe` and `camoufox-bin` processes and X11 windows of class `camoufox` as
Firefox; no local change is needed.

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

In a persistent context the start tab becomes the first page when `launchPersistentContext`
returns; every `newPage()` opens a foreground tab (Playwright behaviour) and hangs, and
`page.bringToFront()` would hang the same way.

Fix options:

1. Camoufox reports background tabs as hidden, as Firefox does.
2. playwright4j opens tabs with `background: true` and selects them some other way for Camoufox. The
   new page would then not be the selected tab, and `bringToFront()` still could not work. This is a
   departure from Playwright behaviour.

## Fixes

Proposed to Camoufox in [daijro/camoufox#952](https://github.com/daijro/camoufox/pull/952) (see
[Camoufox upstream](#camoufox-upstream)). Both fixes change only Remote Agent JavaScript. Pages see no difference: a background tab still
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

In Camoufox's patch format they are
[`camoufox/patches/webdriver-bidi-app-name.patch`](camoufox/patches/webdriver-bidi-app-name.patch)
and
[`camoufox/patches/webdriver-bidi-force-active.patch`](camoufox/patches/webdriver-bidi-force-active.patch).
`hotpatch_omni.py` makes the same changes in a released Camoufox
([Installing](#installing-camoufox-with-the-fixes)); the texts it replaces are the same in Camoufox
156.0.1-beta.36 and 158.0-beta.40.

## Results with the fixes

Camoufox 156.0.1-beta.36 with both fixes, persistent context headed:

| Run | Result |
| --- | --- |
| Linux, `FirefoxPersistentWindowTest` (process and window names extended to `camoufox` locally) | 5 of 6 pass |
| Linux, #31 on master after #29 (Core 1.62), `firefoxSmoke` | 9 of 9 pass (Firefox 158.0.1: 9 of 9) |
| Windows (Angelo, master `4cb607f`, fixes reconstructed from the description, process name extended locally) | `FirefoxBiDiSmokeTest` 2 of 2; `firefoxSmoke` 6 of 8 |
| Linux and Windows, `camoufoxSmoke` (fingerprint through `setEnv`, two pages) | Linux: passes; Windows (Angelo, the script's hotpatch, master after #30 and #33): 2 of 2 pass |

The two failures of the earlier runs, both fixed in #31:

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
`CAMOUFOX_BIDI_FIXES=true`, a second page), and that they are absent without `setEnv`. With a
display it also counts the native windows: the persistent start, the first navigation and the second
page leave exactly one Camoufox window (issue #34; without the start-tab takeover a second window
opens while `pages()` still reports one page). Measured on Linux with Camoufox 156.0.1-beta.36,
released and with the fixes: passes.

The Windows CI jobs `firefox-smoke (camoufox, nativeBehavior=false|true)` install the default
pinned release with `hotpatch_omni.py --install` (the archive and both changed files checked, see
[Installing](#installing-camoufox-with-the-fixes)) and run `firefoxSmoke` and `camoufoxSmoke`
against it, once without and once with `playwright4j.bidi.nativeBehavior=true`
([browser signals](browser-signals.md)).

## Comparison with Camoufox's own launcher (manual)

The acceptance check compares two runs of the same Camoufox copy with the same fingerprint:

- **A**: Camoufox's own launcher (npm package `camoufox` 0.5.8 with `playwright-core` 1.62.0), which
  drives Camoufox over Juggler. This is the reference: the launcher GPT Desktop used before (its
  earlier reference test reached 100 points at amiabot.app; in the comparison below it scored 75
  without pointer or scroll input).
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

1. Camoufox 156.0.1-beta.36 with the fixes, e.g. in `G:\camoufox-156`:
   `python docs\camoufox\hotpatch_omni.py --install G:\camoufox-156`
   ([Installing](#installing-camoufox-with-the-fixes)). Both fixes change only Remote Agent code,
   which a Juggler run does not use, so A and B use the same copy.
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

It runs headed; on Linux without a display prefix it with `xvfb-run -a` or set
`FIREFOX_HEADLESS=true`.

Window and tab behaviour:

1. Install Camoufox with the fixes ([Installing](#installing-camoufox-with-the-fixes)). For the
   released behaviour, unpack an archive from <https://github.com/daijro/camoufox/releases> instead.
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
