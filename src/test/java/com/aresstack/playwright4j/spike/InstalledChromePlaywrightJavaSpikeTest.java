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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        // Führe den Test nur weiter aus, wenn die Bedingung true ist.
        // Wenn die Bedingung false ist, schlägt der Test nicht fehl, sondern wird als übersprungen / aborted markiert.
        assumeTrue(Boolean.getBoolean("playwright4j.chromeSpike"),
                "Enable with -Pplaywright4j.chromeSpike=true");

        Path chromeExecutable = locateChromeExecutable();
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

    /**
     * Locates Chrome executable from system properties or predefined paths
     */
    private Path locateChromeExecutable() {
        String configuredPath = System.getProperty("playwright4j.chrome.executablePath", "");

        if (!configuredPath.isBlank()) {
            return Paths.get(configuredPath);
        }

        for (Path candidate : chromeCandidates()) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("Could not locate Google Chrome. Provide -Pplaywright4j.chrome.executablePath=<path-to-chrome.exe>.");
    }

    /**
     * Builds list of potential Chrome executable paths
     */
    private List<Path> chromeCandidates() {
        List<Path> candidates = new ArrayList<Path>();
        addCandidate(candidates, System.getenv("PROGRAMFILES"), "Google", "Chrome", "Application", "chrome.exe");
        addCandidate(candidates, System.getenv("PROGRAMFILES(X86)"), "Google", "Chrome", "Application", "chrome.exe");
        addCandidate(candidates, System.getenv("LOCALAPPDATA"), "Google", "Chrome", "Application", "chrome.exe");
        return candidates;
    }

    /**
     * Adds candidate executable path if root is valid
     */
    private void addCandidate(List<Path> candidates, String root, String... children) {
        if (root == null || root.isBlank()) {
            return;
        }

        Path path = Paths.get(root, children);
        candidates.add(path);
    }
}
