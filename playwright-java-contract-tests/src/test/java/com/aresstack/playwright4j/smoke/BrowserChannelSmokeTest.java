package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Minimal browser-channel smoke for CI: launch the configured Chromium channel
 * (chrome / msedge), open a page, set content, evaluate a trivial expression, close.
 *
 * <p>Channel is taken from the {@code BROWSER_CHANNEL} environment variable (default
 * {@code chrome}). This is intentionally tiny — it proves the native launcher + channel
 * resolution + CDP round-trip work for a given channel, without a full sweep.
 */
public class BrowserChannelSmokeTest {

    @Test
    void launchEvaluateClose() {
        String channel = System.getenv().getOrDefault("BROWSER_CHANNEL", "chrome");
        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setChannel(channel)
                    .setHeadless(true);
            try (Browser browser = playwright.chromium().launch(options)) {
                Page page = browser.newPage();
                page.setContent("<html><body><h1>playwright4j smoke</h1></body></html>");
                Object result = page.evaluate("1 + 1");
                assertEquals(2, ((Number) result).intValue(),
                        "evaluate(1 + 1) should be 2 on channel '" + channel + "'");
                page.close();
            }
        }
    }
}
