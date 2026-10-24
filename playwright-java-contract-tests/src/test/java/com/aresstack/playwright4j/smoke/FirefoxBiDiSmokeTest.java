package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Firefox over WebDriver BiDi (Playwright's moz-firefox channel) against an installed Firefox.
 * Set FIREFOX_EXECUTABLE to the firefox binary; with PLAYWRIGHT4J_REQUIRE_FIREFOX=true a missing
 * Firefox fails the test instead of skipping it. Close steps must finish promptly (no timeouts).
 */
public class FirefoxBiDiSmokeTest {
    private static final long CLOSE_BUDGET_MILLIS = 10_000;

    @Test
    void launchInstalledFirefoxEvaluateAndClose() {
        String configured = System.getenv("FIREFOX_EXECUTABLE");
        boolean available = configured != null && !configured.trim().isEmpty()
                && Files.isRegularFile(Paths.get(configured));
        if (!available && "true".equalsIgnoreCase(System.getenv("PLAYWRIGHT4J_REQUIRE_FIREFOX"))) {
            fail("PLAYWRIGHT4J_REQUIRE_FIREFOX=true but FIREFOX_EXECUTABLE does not point to a file: " + configured);
        }
        Assumptions.assumeTrue(available, "Set FIREFOX_EXECUTABLE to an installed Firefox to run this smoke.");

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

    private static void assertClosedPromptly(String step, long startMillis) {
        long elapsed = System.currentTimeMillis() - startMillis;
        assertTrue(elapsed < CLOSE_BUDGET_MILLIS, step + " took " + elapsed + " ms");
    }
}
