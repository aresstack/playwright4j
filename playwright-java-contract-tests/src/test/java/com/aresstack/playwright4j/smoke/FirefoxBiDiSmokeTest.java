package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.assertClosedPromptly;
import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.deleteRecursively;
import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Firefox over WebDriver BiDi (Playwright's moz-firefox channel) against an installed Firefox.
 * Set FIREFOX_EXECUTABLE to the firefox binary; with PLAYWRIGHT4J_REQUIRE_FIREFOX=true a missing
 * Firefox fails the test instead of skipping it. Close steps must finish promptly (no timeouts).
 */
public class FirefoxBiDiSmokeTest {
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
                        .setHeadless(FirefoxTestSupport.headless());
                BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir, options);
                try {
                    Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                    // What the page goes through, for the message if it does not show the document.
                    List<String> events = new ArrayList<>();
                    events.add("start " + page.url());
                    page.onFrameNavigated(frame -> events.add("navigated " + (frame == page.mainFrame() ? "" : "frame ") + frame.url()));
                    page.onDOMContentLoaded(p -> events.add("domcontentloaded"));
                    page.onLoad(p -> events.add("load"));
                    context.onPage(p -> events.add("page " + p.url()));
                    Response response = page.navigate("data:text/html,<title>persistent</title>");
                    events.add("navigate returned, response " + (response == null ? null : response.url()));
                    int launch = round;
                    assertEquals("persistent", page.title(), () -> "launch " + launch + ", " + events
                            + ", now url " + page.url() + ", title " + page.title()
                            + ", readyState " + page.evaluate("document.readyState")
                            + ", visibility " + page.evaluate("document.visibilityState")
                            + ", pages " + context.pages().size());
                    // Loading a page must not grant it user activation without any input (the page
                    // records the state itself; page.title() is read only after that).
                    page.navigate("data:text/html,<script>setTimeout(() => document.title = 'activation:'"
                            + " + navigator.userActivation.hasBeenActive, 500)</script>");
                    page.waitForTimeout(1500);
                    assertEquals("activation:false", page.title());
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
}
