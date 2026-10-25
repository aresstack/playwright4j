package com.aresstack.playwright4j.smoke;

import com.aresstack.playwright4j.graal.Playwright4JHost;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotCaret;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.deleteRecursively;
import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Firefox over WebDriver BiDi with and without {@code -Dplaywright4j.bidi.nativeBehavior=true}:
 * the popup blocker, the default viewport and the screenshot caret. Without the property the
 * behaviour is Playwright's.
 */
public class FirefoxNativeBehaviorTest {
    // Runs while the page loads, without user activation, so only the popup blocker decides. A
    // navigation, not setContent: setContent writes the document with user activation.
    private static final String POPUP_PAGE = "data:text/html,<script>window.__opened = window.open('about:blank') !== null;</script>"
            + "<input id=field autofocus>";
    private static final String WATCH_FIELD_STYLE = "() => { window.__styleChanges = 0;"
            + " new MutationObserver(records => window.__styleChanges += records.length)"
            + ".observe(document.getElementById('field'), { attributes: true, attributeFilter: ['style'] }); }";

    @AfterEach
    void clearProperty() {
        System.clearProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY);
    }

    @Test
    void withoutThePropertyPlaywrightsBehaviourStays() throws Throwable {
        runGuarded(executable -> {
            try (Playwright playwright = Playwright.create();
                 Browser browser = playwright.firefox().launch(launchOptions(executable))) {
                Page page = browser.newContext().newPage();
                assertEquals(1280, page.viewportSize().width);
                assertEquals(720, page.viewportSize().height);
                page.navigate(POPUP_PAGE);
                assertEquals(true, page.evaluate("window.__opened"), "Playwright turns the popup blocker off");
                assertTrue(styleChangesDuringScreenshot(page, null) > 0, "Playwright hides the caret through the element's style");
            }
        });
    }

    @Test
    void withThePropertyFirefoxsOwnBehaviourStays() throws Throwable {
        System.setProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY, "true");
        runGuarded(executable -> {
            try (Playwright playwright = Playwright.create();
                 Browser browser = playwright.firefox().launch(launchOptions(executable))) {
                Page page = browser.newContext().newPage();
                assertNull(page.viewportSize(), "no default viewport emulation");
                page.navigate(POPUP_PAGE);
                assertEquals(false, page.evaluate("window.__opened"), "Firefox's popup blocker is on");
                assertEquals(0, styleChangesDuringScreenshot(page, null));
                assertTrue(styleChangesDuringScreenshot(page, ScreenshotCaret.HIDE) > 0, "caret HIDE is still honoured");

                Page sized = browser.newContext(new Browser.NewContextOptions().setViewportSize(800, 600)).newPage();
                ViewportSize viewport = sized.viewportSize();
                assertEquals(800, viewport.width, "a caller's viewport is kept");
                assertEquals(600, viewport.height);
            }
        });
    }

    @Test
    void withThePropertyAPersistentProfileGetsNoTestPreferences() throws Throwable {
        System.setProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY, "true");
        runGuarded(executable -> {
            Path userDataDir = Files.createTempDirectory("playwright4j-native-behavior");
            try (Playwright playwright = Playwright.create()) {
                BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                        .setChannel("moz-firefox")
                        .setExecutablePath(Paths.get(executable))
                        .setHeadless(true)
                        .setFirefoxUserPrefs(java.util.Collections.singletonMap("dom.security.https_first", false));
                try (BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir, options)) {
                    assertNull(context.pages().get(0).viewportSize());
                }
                List<String> userJs = Files.readAllLines(userDataDir.resolve("user.js"), StandardCharsets.ISO_8859_1);
                assertFalse(userJs.stream().anyMatch(line -> line.startsWith("user_pref(\"dom.disable_open_during_load\",")), userJs.toString());
                assertFalse(userJs.stream().anyMatch(line -> line.startsWith("user_pref(\"focusmanager.testmode\",")), userJs.toString());
                assertTrue(userJs.contains("user_pref(\"dom.security.https_first\", false);"), "firefoxUserPrefs still win: " + userJs);
            } finally {
                deleteRecursively(userDataDir);
            }
        });
    }

    private static BrowserType.LaunchOptions launchOptions(String executable) {
        return new BrowserType.LaunchOptions()
                .setChannel("moz-firefox")
                .setExecutablePath(Paths.get(executable))
                .setHeadless(true);
    }

    private static int styleChangesDuringScreenshot(Page page, ScreenshotCaret caret) {
        page.evaluate(WATCH_FIELD_STYLE);
        page.screenshot(new Page.ScreenshotOptions().setCaret(caret));
        // MutationObserver callbacks run as a microtask after the change; one more task lets them land.
        return ((Number) page.evaluate("() => new Promise(resolve => setTimeout(() => resolve(window.__styleChanges), 0))")).intValue();
    }
}
