package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FirefoxBiDiSmokeTest {
    @Test
    void launchInstalledFirefoxEvaluateAndClose() {
        String configured = System.getenv("FIREFOX_EXECUTABLE");
        Assumptions.assumeTrue(configured != null && !configured.isBlank()
                        && Files.isRegularFile(Path.of(configured)),
                "Install Firefox locally and set FIREFOX_EXECUTABLE to run this smoke test.");
        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setChannel("moz-firefox")
                    .setExecutablePath(Path.of(configured))
                    .setHeadless(true);
            try (Browser browser = playwright.firefox().launch(options)) {
                Page page = browser.newPage();
                page.setContent("<html><body><h1>Firefox BiDi smoke</h1></body></html>");
                Object value = page.evaluate("1 + 1");
                assertEquals(2, ((Number) value).intValue());
                page.close();
            }
        }
    }
}
