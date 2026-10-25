package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.assertClosedPromptly;
import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.deleteRecursively;
import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Window and tab handling of Firefox over WebDriver BiDi, above all in a persistent context (the
 * GPT Desktop path). Firefox already shows a window with one tab when its BiDi endpoint starts
 * listening: launchPersistentContext must take that tab over as its first page instead of opening
 * a second window, and newPage() must open a tab in that window. Counts the real top-level windows
 * through the operating system ({@link FirefoxWindows}), not only context.pages(). Runs headed;
 * with FIREFOX_HEADLESS=true it runs headless and skips the window counts. On Linux the window
 * counts need an X display and xdotool, otherwise they are skipped.
 */
public class FirefoxPersistentWindowTest {
    private static final long WINDOW_SETTLE_MILLIS = 15_000;
    private static final long WINDOW_STABLE_MILLIS = 1_500;
    // How long Firefox's child processes wait before they start, for the slow-start variant.
    private static final long CHILD_PROCESS_PAUSE_SECONDS = 3;

    private static final List<String> OWN_USER_JS = List.of(
            "// Einstellungen f\u00fcr diesen Test",
            "user_pref(\"playwright4j.test.kept\", \"\u00e4\u00f6\u00fc\");",
            "user_pref(\"browser.startup.page\", 3);",
            // Firefox accepts a comment left open at the end of a file; it must not swallow
            // Playwright's lines that follow the profile's own.
            "/* switched off, and the comment is never closed: user_pref(\"playwright4j.test.off\", 1);",
            "");

    private static final String FORM = "<input><button onclick=\"document.getElementById('out').textContent = "
            + "'clicked: ' + document.querySelector('input').value\">go</button><div id=out></div>";

    // Cold start with a fresh profile, newPage() as a tab, closing pages down to none, and a restart
    // with the same profile: one window throughout.
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void persistentContextUsesTheStartTabAndOpensPagesAsTabs() throws Throwable {
        runGuarded(FirefoxPersistentWindowTest::coldStartNewPageAndRestart);
    }

    // Firefox opens its home page in the start tab: the first page of the persistent context is that
    // very tab, not a tab opened next to it.
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void theFirstPageIsTheTabFirefoxStartedWith() throws Throwable {
        runGuarded(FirefoxPersistentWindowTest::startTabWithHomePage);
    }

    // A Firefox that is already running (own profile, remote enabled as a user's Firefox would be)
    // keeps its windows, and the persistent context still gets exactly one window of its own.
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void anotherRunningFirefoxIsLeftAlone() throws Throwable {
        runGuarded(FirefoxPersistentWindowTest::besideAnotherFirefox);
    }

    // The user closes the last tab in Firefox's UI (Ctrl+W): Firefox keeps the window with a blank
    // tab (browser.tabs.closeWindowWithLastTab=false), the context stays open without pages, and
    // newPage() opens a page in that window in place of the blank tab. Needs a desktop that takes
    // the keystroke (xdotool on Linux); see skipOrFail for when it cannot be delivered.
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void lastTabClosedInFirefoxLeavesTheContextOpenWithoutPages() throws Throwable {
        Assumptions.assumeTrue(countWindows(), "needs a headed Firefox and OS window access");
        runGuarded(executable -> lastTabClosedByUser(executable, false));
    }

    // The same while Firefox starts its child processes slowly (MOZ_DEBUG_CHILD_PAUSE), as on
    // Windows when the process for the new tab page starts only on demand: Firefox's replacement
    // tab shows its initial about:blank until about:newtab commits seconds later. newPage() must
    // not hand that tab out, or the page turns into the privileged new tab page, which refuses
    // data: URLs.
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void lastTabClosedInFirefoxWhileItsChildProcessesStartSlowly() throws Throwable {
        Assumptions.assumeTrue(countWindows(), "needs a headed Firefox and OS window access");
        runGuarded(executable -> lastTabClosedByUser(executable, true));
    }

    // Firefox ending without context.close() (closed by the user or killed): the context reports
    // the close promptly, and the same profile starts again right away with one window. Restarting
    // is up to the application, as a persistent context ends with its browser.
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void firefoxEndedFromOutsideClosesTheContextAndTheProfileStartsAgain() throws Throwable {
        runGuarded(FirefoxPersistentWindowTest::endedFromOutsideAndRestarted);
    }

    // Non-persistent contexts: the first page of a context gets a window, further pages of that
    // context open as tabs in it (Firefox's own start window stays as it is).
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void newContextGetsOneWindowAndItsPagesOpenAsTabs() throws Throwable {
        runGuarded(FirefoxPersistentWindowTest::contextsWithTabs);
    }

    private static void coldStartNewPageAndRestart(String executable) throws Exception {
        Path userDataDir = Files.createTempDirectory("playwright4j-firefox-windows");
        // A user.js the profile already has: its own lines survive Playwright's rewrite byte for byte
        // (here in ISO-8859-1, as an older editor saves it), and for a preference Playwright sets
        // (start page) Playwright's value wins, also over the comment the profile leaves open.
        Path userJs = userDataDir.resolve("user.js");
        Files.write(userJs, String.join("\r\n", OWN_USER_JS).getBytes(StandardCharsets.ISO_8859_1));
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = launch(playwright, executable, userDataDir);
            try {
                assertUserJs(userJs);
                assertEquals(1, context.pages().size(), "pages after a cold start");
                expectWindows("cold start", Collections.emptySet(), 1);
                Page first = context.pages().get(0);
                assertEquals("about:blank", first.url(), "the start tab is the first page");
                first.navigate("data:text/html,<title>first</title>");
                first.setContent("<title>first</title>" + FORM);
                expectWindows("navigating the start tab", Collections.emptySet(), 1);

                Page second = context.newPage();
                assertEquals(2, context.pages().size(), "pages after newPage()");
                expectWindows("newPage()", Collections.emptySet(), 1);
                second.navigate("data:text/html,<title>second</title>");
                assertEquals("second", second.title());
                // The first page is a background tab now; it must still take input and run scripts.
                first.fill("input", "background tab");
                first.click("button");
                assertEquals("clicked: background tab", first.textContent("#out"));
                assertEquals(2, ((Number) first.evaluate("1 + 1")).intValue());

                long start = System.currentTimeMillis();
                second.close();
                assertClosedPromptly("closing the second tab", start);
                assertEquals(1, context.pages().size(), "pages after closing the second tab");
                expectWindows("closing the second tab", Collections.emptySet(), 1);

                // Firefox never closes its very last tab over BiDi. Closing the last page must still
                // finish promptly and leave the context without pages, as in Playwright, while the
                // window keeps a blank tab; the next newPage() uses that tab.
                start = System.currentTimeMillis();
                first.close();
                long closedAt = System.currentTimeMillis();
                assertClosedPromptly("closing the last page", start);
                assertTrue(first.isClosed(), "the last page is closed");
                assertTrue(context.pages().isEmpty(), "pages after closing the last page: " + context.pages().size());
                expectWindows("closing the last page", Collections.emptySet(), 1);

                Thread.sleep(1_500);
                Page again = context.newPage();
                assertEquals(1, context.pages().size(), "pages after newPage() on a context without pages");
                assertEquals("about:blank", again.url());
                // The page is the blank tab kept while closing the last page, not a tab opened next to
                // it (which would leave the kept tab behind): its document is older than newPage().
                double createdAt = ((Number) again.evaluate("performance.timeOrigin")).doubleValue();
                assertTrue(createdAt < closedAt + 750, "newPage() opened another tab instead of using the kept one: "
                        + "document created " + (long) (createdAt - closedAt) + " ms after the last page closed");
                expectWindows("newPage() on a context without pages", Collections.emptySet(), 1);
                again.navigate("data:text/html,<title>again</title>");
                assertEquals("again", again.title());
                context.newPage().navigate("data:text/html,<title>another tab</title>");
                assertEquals(2, context.pages().size(), "pages after another newPage()");
                expectWindows("another newPage()", Collections.emptySet(), 1);
            } finally {
                long start = System.currentTimeMillis();
                context.close();
                assertClosedPromptly("persistent context.close()", start);
            }
            assertPrefsApplied(userDataDir);

            context = launch(playwright, executable, userDataDir);
            try {
                assertUserJs(userJs);
                assertEquals(1, context.pages().size(), "pages after a restart with the same profile");
                expectWindows("restart with the same profile", Collections.emptySet(), 1);
                Page page = context.pages().get(0);
                page.navigate("data:text/html,<title>restart</title>");
                assertEquals("restart", page.title());
                context.newPage().navigate("data:text/html,<title>restart tab</title>");
                expectWindows("newPage() after the restart", Collections.emptySet(), 1);
            } finally {
                long start = System.currentTimeMillis();
                context.close();
                assertClosedPromptly("persistent context.close() after the restart", start);
            }
        } finally {
            deleteRecursively(userDataDir);
        }
    }

    private static void lastTabClosedByUser(String executable, boolean slowChildProcesses) throws Exception {
        Path userDataDir = Files.createTempDirectory("playwright4j-firefox-windows");
        BrowserType.LaunchPersistentContextOptions options = options(executable);
        if (slowChildProcesses) {
            // setEnv replaces the environment: pass the inherited one along. On Linux the fork
            // server would start content processes without the pause.
            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("MOZ_DEBUG_CHILD_PAUSE", String.valueOf(CHILD_PROCESS_PAUSE_SECONDS));
            env.put("MOZ_DISABLE_FORKSERVER", "1");
            options.setEnv(env);
        }
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir, options);
            boolean[] contextClosed = {false};
            context.onClose(c -> contextClosed[0] = true);
            try {
                Page page = context.pages().get(0);
                page.navigate("data:text/html,<title>closed by the user</title>");
                expectWindows("before the user closes the tab", Collections.emptySet(), 1);
                List<FirefoxWindows.Window> windows = FirefoxWindows.ownedBy(playwrightFirefoxPids(Collections.emptySet()));
                assertEquals(1, windows.size(), "Firefox windows: " + windows);
                if (!FirefoxWindows.closeTabWithKeyboard(windows.get(0).pid)) {
                    skipOrFail("could not give the Firefox window the keyboard focus to press Ctrl+W");
                }
                try {
                    context.waitForCondition(page::isClosed, new BrowserContext.WaitForConditionOptions().setTimeout(15_000));
                } catch (PlaywrightException notClosed) {
                    // Firefox shows the title of the selected tab in its window title. Still there: the
                    // keystroke never reached Firefox. Gone: the runtime missed the close.
                    List<FirefoxWindows.Window> now = FirefoxWindows.ownedBy(playwrightFirefoxPids(Collections.emptySet()));
                    if (now.stream().noneMatch(w -> w.title.contains("closed by the user"))) {
                        throw new AssertionError("Firefox closed the tab, but its page did not close; windows: " + now, notClosed);
                    }
                    skipOrFail("Ctrl+W did not reach Firefox; windows: " + now);
                }
                assertTrue(context.pages().isEmpty(), "pages after the user closed the last tab: " + context.pages().size());
                assertTrue(!contextClosed[0], "the context closed with the last tab");
                if (slowChildProcesses) {
                    // Firefox's replacement tab is there, its about:newtab comes only after the pause.
                    Thread.sleep(1_000);
                } else {
                    expectWindows("after the user closed the last tab", Collections.emptySet(), 1);
                }

                Page again = context.newPage();
                assertEquals(1, context.pages().size(), "pages after newPage()");
                expectWindows("newPage() after the user closed the last tab", Collections.emptySet(), 1);
                if (slowChildProcesses) {
                    // Long enough for the replacement tab's about:newtab to have loaded.
                    Thread.sleep(TimeUnit.SECONDS.toMillis(CHILD_PROCESS_PAUSE_SECONDS) + 2_000);
                }
                again.navigate("data:text/html,<title>after the user</title>");
                assertEquals("after the user", again.title());
            } finally {
                long start = System.currentTimeMillis();
                context.close();
                assertClosedPromptly("persistent context.close()", start);
            }
        } finally {
            deleteRecursively(userDataDir);
        }
    }

    private static void startTabWithHomePage(String executable) throws Exception {
        Path userDataDir = Files.createTempDirectory("playwright4j-firefox-windows");
        Path home = Files.createTempFile("playwright4j-start-tab", ".html");
        try (Playwright playwright = Playwright.create()) {
            Files.write(home, "<title>start tab</title>".getBytes(StandardCharsets.UTF_8));
            BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir, options(executable)
                    .setFirefoxUserPrefs(Map.of(
                            "browser.startup.page", 1,
                            "browser.startup.homepage", home.toUri().toString())));
            try {
                assertEquals(1, context.pages().size(), "pages after a start with a home page");
                expectWindows("start with a home page", Collections.emptySet(), 1);
                // Only the tab Firefox started with shows the home page.
                context.pages().get(0).waitForFunction("document.title === 'start tab'", null,
                        new Page.WaitForFunctionOptions().setTimeout(15_000));
            } finally {
                long start = System.currentTimeMillis();
                context.close();
                assertClosedPromptly("persistent context.close()", start);
            }
        } finally {
            deleteRecursively(userDataDir);
            Files.deleteIfExists(home);
        }
    }

    private static void endedFromOutsideAndRestarted(String executable) throws Exception {
        Path userDataDir = Files.createTempDirectory("playwright4j-firefox-windows");
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = launch(playwright, executable, userDataDir);
            boolean[] closed = {false};
            context.onClose(c -> closed[0] = true);
            try {
                context.pages().get(0).navigate("data:text/html,<title>before</title>");
                expectWindows("before Firefox ends", Collections.emptySet(), 1);
                List<ProcessHandle> firefox = FirefoxTestSupport.firefoxProcesses(ProcessHandle.current().descendants())
                        .collect(Collectors.toList());
                assertTrue(!firefox.isEmpty(), "no Firefox process below this JVM");
                // Graceful on Linux (SIGTERM, like closing the last window), forcible on Windows.
                firefox.forEach(ProcessHandle::destroy);
                try {
                    context.waitForCondition(() -> closed[0], new BrowserContext.WaitForConditionOptions().setTimeout(15_000));
                } catch (PlaywrightException contextClosedWhileWaiting) {
                    // waitForCondition ends with "Target closed" once the context closes.
                }
                assertTrue(closed[0], "the context reported no close after Firefox ended");
            } finally {
                context.close();
            }

            context = launch(playwright, executable, userDataDir);
            try {
                assertEquals(1, context.pages().size(), "pages after starting the profile again");
                expectWindows("starting the profile again", Collections.emptySet(), 1);
                Page page = context.pages().get(0);
                page.navigate("data:text/html,<title>after</title>");
                assertEquals("after", page.title());
            } finally {
                long start = System.currentTimeMillis();
                context.close();
                assertClosedPromptly("persistent context.close() after the restart", start);
            }
        } finally {
            deleteRecursively(userDataDir);
        }
    }

    private static void contextsWithTabs(String executable) throws Exception {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions()
                    .setChannel("moz-firefox")
                    .setExecutablePath(Paths.get(executable))
                    .setHeadless(FirefoxTestSupport.headless()));
            try {
                // Firefox's start window (no page of any context).
                expectWindows("launch", Collections.emptySet(), 1);
                BrowserContext first = browser.newContext();
                Page a = first.newPage();
                expectWindows("first page of a context", Collections.emptySet(), 2);
                a.setContent("<title>a</title>" + FORM);
                Page b = first.newPage();
                expectWindows("second page of the context", Collections.emptySet(), 2);
                b.setContent("<title>b</title>");
                // a is a background tab now.
                a.fill("input", "context tab");
                a.click("button");
                assertEquals("clicked: context tab", a.textContent("#out"));

                BrowserContext second = browser.newContext();
                Page c = second.newPage();
                expectWindows("first page of another context", Collections.emptySet(), 3);
                assertEquals(2, ((Number) c.evaluate("1 + 1")).intValue());

                long start = System.currentTimeMillis();
                first.close();
                assertClosedPromptly("context.close() with two tabs", start);
                expectWindows("closing a context", Collections.emptySet(), 2);
                second.close();
            } finally {
                long start = System.currentTimeMillis();
                browser.close();
                assertClosedPromptly("browser.close()", start);
            }
        }
    }

    private static void besideAnotherFirefox(String executable) throws Exception {
        Path otherProfile = Files.createTempDirectory("playwright4j-firefox-other");
        Path userDataDir = Files.createTempDirectory("playwright4j-firefox-windows");
        Process other = null;
        try {
            // Quiet first start of the other profile (no welcome page, no default-browser question).
            Files.write(otherProfile.resolve("user.js"), String.join("\n",
                    "user_pref(\"browser.shell.checkDefaultBrowser\", false);",
                    "user_pref(\"browser.aboutwelcome.enabled\", false);",
                    "user_pref(\"startup.homepage_welcome_url\", \"about:blank\");",
                    "user_pref(\"browser.startup.homepage_override.mstone\", \"ignore\");",
                    "user_pref(\"datareporting.policy.dataSubmissionPolicyBypassNotification\", true);",
                    "user_pref(\"toolkit.telemetry.reportingpolicy.firstRun\", false);",
                    "").getBytes(StandardCharsets.UTF_8));
            List<String> command = new java.util.ArrayList<>(List.of(executable, "-profile", otherProfile.toString(),
                    // Keeps the started process alive on Windows (launcher process), so the browser
                    // stays in this process tree and its windows can be told apart.
                    "-wait-for-browser"));
            if (FirefoxTestSupport.headless()) {
                command.add("-headless");
            }
            command.add("data:text/html,<title>other session</title>");
            other = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Set<Long> otherRoots = Set.of(other.pid());
            int otherWindows = awaitOtherWindows(otherRoots);

            try (Playwright playwright = Playwright.create()) {
                BrowserContext context = launch(playwright, executable, userDataDir);
                try {
                    assertEquals(1, context.pages().size(), "pages beside another Firefox");
                    expectWindows("launch beside another Firefox", otherRoots, 1);
                    expectOtherWindows("launch beside another Firefox", otherRoots, otherWindows);
                    context.pages().get(0).navigate("data:text/html,<title>persistent</title>");
                    context.newPage();
                    expectWindows("newPage() beside another Firefox", otherRoots, 1);
                    expectOtherWindows("newPage() beside another Firefox", otherRoots, otherWindows);
                } finally {
                    long start = System.currentTimeMillis();
                    context.close();
                    assertClosedPromptly("persistent context.close() beside another Firefox", start);
                }
            }
            assertTrue(other.isAlive(), "closing the persistent context ended the other Firefox");
            expectOtherWindows("after closing the persistent context", otherRoots, otherWindows);
        } finally {
            if (other != null) {
                stopProcessTree(other);
            }
            deleteRecursively(userDataDir);
            deleteRecursively(otherProfile);
        }
    }

    private static void assertUserJs(Path userJs) throws java.io.IOException {
        // Read byte for byte: the profile's own lines are ISO-8859-1, Playwright's are UTF-8.
        List<String> lines = List.of(new String(Files.readAllBytes(userJs), StandardCharsets.ISO_8859_1).split("\r?\n"));
        for (String own : OWN_USER_JS.subList(0, OWN_USER_JS.size() - 1)) {
            assertEquals(1, lines.stream().filter(own::equals).count(), "the profile's own line is kept once: " + own + " in " + lines);
        }
        // The profile's own start page stays, so it applies again on a launch that no longer sets one;
        // Playwright's comes after it and wins.
        assertEquals(List.of(OWN_USER_JS.get(2), "user_pref(\"browser.startup.page\", 0);"),
                lines.stream().filter(l -> l.contains("\"browser.startup.page\"")).collect(Collectors.toList()),
                "the profile's own lines come before Playwright's, which win: " + lines);
        assertTrue(lines.contains("user_pref(\"browser.aboutwelcome.enabled\", false);"), "welcome page off: " + lines);
        assertTrue(lines.contains("user_pref(\"browser.tabs.closeWindowWithLastTab\", false);"),
                "closing the last tab in the UI keeps the window: " + lines);
    }

    private static void assertPrefsApplied(Path userDataDir) throws java.io.IOException {
        // What Firefox applied, as it saved it on exit.
        String prefs = new String(Files.readAllBytes(userDataDir.resolve("prefs.js")), StandardCharsets.ISO_8859_1);
        assertTrue(prefs.contains(OWN_USER_JS.get(1)), "the profile's own preference applies: " + prefs);
        // Playwright's start page (0) wins over the profile's own (3). Firefox saves only values that
        // differ from the build's default, so 0 is listed for Firefox but not for Camoufox, whose
        // default it already is.
        String startPage = "user_pref(\"browser.startup.page\", ";
        assertTrue(!prefs.contains(startPage) || prefs.contains(startPage + "0);") && !prefs.contains(startPage + "3);"),
                "Playwright's start page applies: " + prefs);
        assertTrue(prefs.contains("user_pref(\"browser.tabs.closeWindowWithLastTab\", false);"),
                "Playwright's preferences after the profile's open comment apply: " + prefs);
        assertFalse(prefs.contains("playwright4j.test.off"), "a line inside the profile's open comment applies: " + prefs);
    }

    private static BrowserContext launch(Playwright playwright, String executable, Path userDataDir) {
        return playwright.firefox().launchPersistentContext(userDataDir, options(executable));
    }

    private static BrowserType.LaunchPersistentContextOptions options(String executable) {
        return new BrowserType.LaunchPersistentContextOptions()
                .setChannel("moz-firefox")
                .setExecutablePath(Paths.get(executable))
                .setHeadless(FirefoxTestSupport.headless());
    }

    // With PLAYWRIGHT4J_REQUIRE_FIREFOX=true (CI, release gate) a check that cannot run fails, so it
    // is never skipped unnoticed; otherwise it is skipped.
    private static void skipOrFail(String reason) {
        if ("true".equalsIgnoreCase(System.getenv("PLAYWRIGHT4J_REQUIRE_FIREFOX"))) {
            fail(reason);
        }
        Assumptions.abort(reason);
    }

    private static boolean countWindows() {
        return !FirefoxTestSupport.headless() && FirefoxWindows.available();
    }

    /**
     * Waits until the Firefox processes started through Playwright (descendants of this JVM, minus
     * the trees of otherRoots) show exactly the expected number of windows, stable for a moment.
     */
    private static void expectWindows(String step, Set<Long> otherRoots, int expected) throws Exception {
        if (!countWindows()) {
            return;
        }
        awaitWindowCount(step, () -> playwrightFirefoxPids(otherRoots), expected);
    }

    private static void expectOtherWindows(String step, Set<Long> otherRoots, int expected) throws Exception {
        if (!countWindows()) {
            return;
        }
        awaitWindowCount(step + " (other Firefox)", () -> treePids(otherRoots), expected);
    }

    private static int awaitOtherWindows(Set<Long> otherRoots) throws Exception {
        if (!countWindows()) {
            return 0;
        }
        long deadline = System.currentTimeMillis() + 3 * WINDOW_SETTLE_MILLIS;
        List<FirefoxWindows.Window> windows;
        do {
            windows = FirefoxWindows.ownedBy(treePids(otherRoots));
            if (!windows.isEmpty()) {
                Thread.sleep(WINDOW_STABLE_MILLIS);
                return FirefoxWindows.ownedBy(treePids(otherRoots)).size();
            }
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        fail("The other Firefox did not show a window; all Firefox windows: " + FirefoxWindows.all());
        return 0;
    }

    private interface PidSource {
        Set<Long> pids();
    }

    private static void awaitWindowCount(String step, PidSource pids, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + WINDOW_SETTLE_MILLIS;
        List<FirefoxWindows.Window> windows;
        do {
            windows = FirefoxWindows.ownedBy(pids.pids());
            if (windows.size() == expected) {
                // A window that is about to appear (or vanish) must not slip through.
                Thread.sleep(WINDOW_STABLE_MILLIS);
                windows = FirefoxWindows.ownedBy(pids.pids());
                if (windows.size() == expected) {
                    return;
                }
            }
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        fail(step + ": expected " + expected + " Firefox window(s) but found " + windows.size() + ": " + windows
                + "; all Firefox windows: " + FirefoxWindows.all());
    }

    // Firefox processes below this JVM (Playwright launches Firefox from here or from its driver
    // child), without the separately started Firefox.
    private static Set<Long> playwrightFirefoxPids(Set<Long> otherRoots) {
        Set<Long> excluded = treePids(otherRoots);
        return FirefoxTestSupport.firefoxProcesses(ProcessHandle.current().descendants())
                .map(ProcessHandle::pid)
                .filter(pid -> !excluded.contains(pid))
                .collect(Collectors.toSet());
    }

    private static Set<Long> treePids(Set<Long> roots) {
        Set<Long> pids = new HashSet<>(roots);
        for (Long root : roots) {
            ProcessHandle.of(root).ifPresent(handle -> handle.descendants().forEach(d -> pids.add(d.pid())));
        }
        return pids;
    }

    private static void stopProcessTree(Process process) throws InterruptedException {
        List<ProcessHandle> tree = process.toHandle().descendants().collect(Collectors.toList());
        tree.forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        long deadline = System.currentTimeMillis() + 5_000;
        for (ProcessHandle handle : tree) {
            while (handle.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
        process.waitFor(5, TimeUnit.SECONDS);
    }
}
