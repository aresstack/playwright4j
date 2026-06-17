package com.aresstack.playwright4j.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

final class InstalledChromePlaywrightJavaSpikeTest {

    /**
     * Tests the launch of an installed Chrome browser using the official Playwright Java baseline.
     * This test assumes that the system property `playwright4j.chromeSpike` is set to true,
     * and it requires valid configurations for the Chrome executable path and navigation URL.
     *
     * The test performs the following steps:
     * 1. Checks if the prerequisite system property is enabled.
     * 2. Locates the Chrome executable using predefined paths or a specified system property.
     * 3. Validates that both the Chrome executable and the navigation URL are valid.
     * 4. Configures and launches Chromium with the specified options, including the path to the Chrome executable.
     * 5. Navigates to the specified URL and asserts that the page title matches the expected value.
     */
    @Test
    void launchesInstalledChromeAndNavigatesWithOfficialPlaywrightJavaBaseline() {
        assumeTrue(Boolean.getBoolean("playwright4j.chromeSpike"),
                "Enable with -Pplaywright4j.chromeSpike=true");

        Path chromeExecutable = new InstalledChromeLocator().locateChromeExecutable();
        String url = System.getProperty("playwright4j.chrome.url");

        assertTrue(Files.isRegularFile(chromeExecutable), "Chrome executable must exist: " + chromeExecutable);
        assertFalse(url == null || url.isBlank(), "Spike URL must not be blank.");

        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");

        // Configures and launches Chromium with specified options; ensures valid Chrome executable and URL
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(environment))) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setExecutablePath(chromeExecutable)
                    .setHeadless(Boolean.getBoolean("playwright4j.chrome.headless"));

            // Launches browser, navigates URL, asserts page title
            try (Browser browser = playwright.chromium().launch(launchOptions)) {
                Page page = browser.newPage();
                page.navigate(url);

                assertEquals("Playwright4J Chrome Spike", page.title());
            }
        }
    }

}
