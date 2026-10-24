package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Firefox over WebDriver BiDi (Playwright's moz-firefox channel) against an installed Firefox.
 * Set FIREFOX_EXECUTABLE to the firefox binary; with PLAYWRIGHT4J_REQUIRE_FIREFOX=true a missing
 * Firefox fails the test instead of skipping it. Close steps must finish promptly (no timeouts).
 */
public class FirefoxBiDiSmokeTest {
    private static final long CLOSE_BUDGET_MILLIS = 10_000;
    private static final long PROCESS_EXIT_GRACE_MILLIS = 5_000;

    @Test
    void launchInstalledFirefoxEvaluateAndClose() throws Throwable {
        runGuarded(FirefoxBiDiSmokeTest::runSmoke);
    }

    // The path GPT Desktop uses: a persistent profile directory that survives the browser, with a
    // visible window by default (FIREFOX_HEADLESS=true to run it headless). A headed start is what
    // exercises the Windows launcher-process handoff (see -wait-for-browser in the runtime).
    @Test
    void launchPersistentContextWithInstalledFirefox() throws Throwable {
        runGuarded(FirefoxBiDiSmokeTest::runPersistentSmoke);
    }

    private interface Smoke {
        void run(String executable) throws Exception;
    }

    private static void runGuarded(Smoke smoke) throws Throwable {
        String configured = System.getenv("FIREFOX_EXECUTABLE");
        boolean available = configured != null && !configured.trim().isEmpty()
                && Files.isRegularFile(Paths.get(configured));
        if (!available && "true".equalsIgnoreCase(System.getenv("PLAYWRIGHT4J_REQUIRE_FIREFOX"))) {
            fail("PLAYWRIGHT4J_REQUIRE_FIREFOX=true but FIREFOX_EXECUTABLE does not point to a file: " + configured);
        }
        Assumptions.assumeTrue(available, "Set FIREFOX_EXECUTABLE to an installed Firefox to run this smoke.");

        Set<Long> firefoxBefore = firefoxPids();
        try {
            smoke.run(configured);
        } catch (Throwable failure) {
            // Also check for leftovers when the smoke itself failed, so a hang does not hide a leak.
            try {
                assertNoFirefoxLeftBehind(firefoxBefore);
            } catch (Throwable leak) {
                failure.addSuppressed(leak);
            }
            throw failure;
        }
        assertNoFirefoxLeftBehind(firefoxBefore);
    }

    private static void runSmoke(String configured) {
        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setChannel("moz-firefox")
                    .setExecutablePath(Paths.get(configured))
                    .setHeadless(true);
            Browser browser = playwright.firefox().launch(options);
            try {
                for (int round = 0; round < 2; round++) {
                    BrowserContext context = browser.newContext();
                    Page page = context.newPage();
                    page.setContent("<html><body><h1>Firefox BiDi smoke</h1></body></html>");
                    assertEquals(2, ((Number) page.evaluate("1 + 1")).intValue());
                    assertEquals("Firefox BiDi smoke", page.textContent("h1"));
                    // The BiDi profile must not expose test-only interfaces to web pages.
                    assertEquals("undefined", page.evaluate("typeof TestUtils"));
                    PlaywrightException gc = assertThrows(PlaywrightException.class, page::requestGC);
                    assertTrue(gc.getMessage().contains("dom.testing.testutils.enabled"), gc.getMessage());
                    long start = System.currentTimeMillis();
                    context.close();
                    assertClosedPromptly("context.close()", start);
                }
            } finally {
                long start = System.currentTimeMillis();
                browser.close();
                assertClosedPromptly("browser.close()", start);
            }
        }
    }

    private static void runPersistentSmoke(String configured) throws Exception {
        Path userDataDir = Files.createTempDirectory("playwright4j-firefox-profile");
        try (Playwright playwright = Playwright.create()) {
            for (int round = 0; round < 2; round++) {
                BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                        .setChannel("moz-firefox")
                        .setExecutablePath(Paths.get(configured))
                        .setHeadless("true".equalsIgnoreCase(System.getenv("FIREFOX_HEADLESS")));
                BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir, options);
                try {
                    Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                    page.navigate("data:text/html,<title>persistent</title>");
                    assertEquals("persistent", page.title());
                    page.setContent("<html><body><h1>Firefox persistent smoke</h1></body></html>");
                    assertEquals(2, ((Number) page.evaluate("1 + 1")).intValue());
                    assertEquals("Firefox persistent smoke", page.textContent("h1"));
                } finally {
                    long start = System.currentTimeMillis();
                    context.close();
                    assertClosedPromptly("persistent context.close()", start);
                }
                // The profile must stay on disk so the next launch reuses it.
                assertTrue(Files.isRegularFile(userDataDir.resolve("prefs.js")),
                        "Firefox profile was not written to " + userDataDir);
            }
        } finally {
            deleteRecursively(userDataDir);
        }
    }

    private static void deleteRecursively(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    // Every Firefox process (main + content processes) started by this test must be gone shortly
    // after browser.close(); a survivor means the process exit was not observed or not enforced.
    private static void assertNoFirefoxLeftBehind(Set<Long> before) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PROCESS_EXIT_GRACE_MILLIS;
        Set<Long> leftovers;
        do {
            leftovers = firefoxPids();
            leftovers.removeAll(before);
            if (leftovers.isEmpty()) {
                return;
            }
            Thread.sleep(200);
        } while (System.currentTimeMillis() < deadline);
        fail("Firefox processes survived browser.close(): " + leftovers);
    }

    private static Set<Long> firefoxPids() {
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(p -> p.info().command()
                        .map(c -> Paths.get(c).getFileName().toString().toLowerCase(Locale.ROOT))
                        .map(n -> n.equals("firefox") || n.equals("firefox.exe") || n.equals("firefox-bin"))
                        .orElse(false))
                .map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }

    private static void assertClosedPromptly(String step, long startMillis) {
        long elapsed = System.currentTimeMillis() - startMillis;
        assertTrue(elapsed < CLOSE_BUDGET_MILLIS, step + " took " + elapsed + " ms");
    }
}
