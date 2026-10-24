package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real, isolated Firefox probe for the existing Playwright4J Java/GraalJS driver.
 * No Selenium, WD4J, bundled browser or browser installation is used.
 * This is intentionally on an experiment branch until Firefox support passes.
 */
public class FirefoxPlaywright4jProbeTest {

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void installedFirefoxCanLaunchAndEvaluateThroughPlaywright4j() {
        Path firefox = installedFirefox();
        System.out.println("Firefox binary: " + firefox);
        System.out.println("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1");

        try (Playwright playwright = Playwright.create(
                new Playwright.CreateOptions().setEnv(Map.of(
                        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1",
                        "PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS", "1")))) {
            BrowserType type = playwright.firefox();
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setExecutablePath(firefox)
                    .setHeadless(true)
                    .setTimeout(30000);
            // Directly try the installed, unmodified Firefox. A failure here
            // identifies a Playwright4J/Firefox compatibility issue, not ChatGPT.
            try (Browser browser = type.launch(options)) {
                Page page = browser.newPage();
                page.setContent("<html><body><h1>Firefox Playwright4J probe</h1></body></html>");
                assertEquals(42, ((Number) page.evaluate("6 * 7")).intValue());
                System.out.println("FIREFOX_PLAYWRIGHT4J_SUPPORTED: launch + DOM + JS passed");
                page.close();
            }
        } catch (RuntimeException error) {
            System.err.println("FIREFOX_PLAYWRIGHT4J_UNSUPPORTED_OR_BROKEN: " + error.getMessage());
            throw error;
        }
    }

    private static Path installedFirefox() {
        String configured = System.getenv("FIREFOX_EXECUTABLE");
        if (configured != null && !configured.isBlank()) {
            Path binary = Path.of(configured);
            if (Files.isRegularFile(binary)) {
                return binary;
            }
            throw new IllegalStateException("FIREFOX_EXECUTABLE not found: " + configured);
        }

        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            for (String root : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String home = System.getenv(root);
                if (home != null) {
                    Path candidate = Path.of(home, "Mozilla Firefox", "firefox.exe");
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                }
            }
        } else {
            for (String pathname : List.of(
                    "/usr/bin/firefox", "/usr/local/bin/firefox",
                    "/Applications/Firefox.app/Contents/MacOS/firefox")) {
                Path candidate = Path.of(pathname);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException(
                "No pre-installed Mozilla Firefox found. Probe does NOT install/download browsers. "
                + "Set FIREFOX_EXECUTABLE to an existing Firefox binary.");
    }
}
