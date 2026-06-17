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
     * Executes test to launch Chrome and navigate using Playwright
     */
    @Test
    void launchesInstalledChromeAndNavigatesWithOfficialPlaywrightJavaBaseline() {
        assumeTrue(Boolean.getBoolean("playwright4j.chromeSpike"),
                "Enable with -Pplaywright4j.chromeSpike=true");

        Path chromeExecutable = locateChromeExecutable();
        String url = System.getProperty("playwright4j.chrome.url");

        assertTrue(Files.isRegularFile(chromeExecutable), "Chrome executable must exist: " + chromeExecutable);
        assertFalse(url == null || url.isBlank(), "Spike URL must not be blank.");

        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");

        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(environment))) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setExecutablePath(chromeExecutable)
                    .setHeadless(Boolean.getBoolean("playwright4j.chrome.headless"));

            try (Browser browser = playwright.chromium().launch(launchOptions)) {
                Page page = browser.newPage();
                page.navigate(url);

                assertEquals("Playwright4J Chrome Spike", page.title());
            }
        }
    }

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

    private List<Path> chromeCandidates() {
        List<Path> candidates = new ArrayList<Path>();
        addCandidate(candidates, System.getenv("PROGRAMFILES"), "Google", "Chrome", "Application", "chrome.exe");
        addCandidate(candidates, System.getenv("PROGRAMFILES(X86)"), "Google", "Chrome", "Application", "chrome.exe");
        addCandidate(candidates, System.getenv("LOCALAPPDATA"), "Google", "Chrome", "Application", "chrome.exe");
        return candidates;
    }

    private void addCandidate(List<Path> candidates, String root, String... children) {
        if (root == null || root.isBlank()) {
            return;
        }

        Path path = Paths.get(root, children);
        candidates.add(path);
    }
}
