package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Target/acceptance smoke for Firefox support (issue #10): launch Firefox via Playwright's
 * Juggler pipe transport, open a page, evaluate a trivial expression, close.
 *
 * <p>Currently {@code @Disabled}: Firefox uses the {@code -juggler-pipe} fd 3/4 pipe transport,
 * which is not yet implemented (playwright4j fast-fails non-Chromium engines). Remove the
 * {@code @Disabled} once the native fd 3/4 pipe bridge + JS spawn wiring land — see
 * {@code docs/firefox-juggler-transport.md}. Do not make this pass by faking Firefox with Chromium.
 */
@Disabled("Firefox Juggler pipe transport not implemented yet — see issue #10 / docs/firefox-juggler-transport.md")
public class FirefoxSmokeTest {

    @Test
    void launchEvaluateClose() {
        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
            try (Browser browser = playwright.firefox().launch(options)) {
                Page page = browser.newPage();
                page.setContent("<html><body><h1>playwright4j firefox smoke</h1></body></html>");
                Object result = page.evaluate("1 + 1");
                assertEquals(2, ((Number) result).intValue(), "evaluate(1 + 1) should be 2 on Firefox");
                page.close();
            }
        }
    }
}
