package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The environment a browser process starts with, for launch() and launchPersistentContext() of
 * Chromium and Firefox (moz-firefox). The "browser" is a script that records its environment and
 * exits, so the launch itself fails; no real browser is needed.
 * <p>
 * Playwright semantics: without setEnv the browser inherits the driver's environment; with setEnv
 * the given map is the browser's complete environment (it replaces, it does not merge). Firefox
 * additionally gets Playwright's MOZ_CRASHREPORTER* variables in both cases.
 */
public class LaunchEnvironmentTest {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final String INHERITED = "PLAYWRIGHT4J_ENV_INHERITED";
    private static final String TEST_VARIABLE = "PLAYWRIGHT4J_ENV_TEST";
    private static final String TEST_VALUE = "harmless value with spaces = and equals";

    private static Path directory;
    private static Path record;
    private static Path fakeBrowser;
    private static Playwright playwright;

    @BeforeAll
    static void setUp() throws IOException {
        directory = Files.createTempDirectory("playwright4j-launch-env");
        record = directory.resolve("environment.txt");
        if (WINDOWS) {
            fakeBrowser = directory.resolve("fake-browser.cmd");
            Files.write(fakeBrowser, ("@set > \"" + record + "\"\r\n@exit /b 1\r\n").getBytes(StandardCharsets.US_ASCII));
        } else {
            fakeBrowser = directory.resolve("fake-browser.sh");
            Files.write(fakeBrowser, ("#!/bin/sh\n/usr/bin/env > '" + record + "'\nexit 1\n").getBytes(StandardCharsets.US_ASCII));
            assertTrue(fakeBrowser.toFile().setExecutable(true));
        }
        playwright = Playwright.create();
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (playwright != null) {
            playwright.close();
        }
        FirefoxTestSupport.deleteRecursively(directory);
    }

    @Test
    void chromiumLaunchWithoutSetEnvInheritsTheEnvironment() throws IOException {
        assertInherited(recordLaunch(playwright.chromium(), null));
    }

    @Test
    void chromiumLaunchWithSetEnvGetsExactlyThatEnvironment() throws IOException {
        assertReplaced(recordLaunch(playwright.chromium(), onlyTestVariable()));
    }

    @Test
    void chromiumPersistentLaunchWithSetEnv() throws IOException {
        assertReplaced(recordPersistentLaunch(playwright.chromium(), onlyTestVariable()));
    }

    @Test
    void chromiumLaunchCanOverrideAnInheritedVariable() throws IOException {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put(INHERITED, "overridden");
        Map<String, String> recorded = recordLaunch(playwright.chromium(), environment);
        assertEquals("overridden", recorded.get(INHERITED));
        assertTrue(recorded.containsKey(pathName(recorded)), "PATH kept when the caller passes it on");
    }

    @Test
    void firefoxLaunchWithoutSetEnvInheritsTheEnvironment() throws IOException {
        Map<String, String> recorded = recordLaunch(playwright.firefox(), null);
        assertInherited(recorded);
        assertEquals("1", recorded.get("MOZ_CRASHREPORTER"));
    }

    @Test
    void firefoxLaunchWithSetEnvGetsThatEnvironmentPlusPlaywrightsFirefoxVariables() throws IOException {
        Map<String, String> recorded = recordLaunch(playwright.firefox(), onlyTestVariable());
        assertReplaced(recorded);
        assertEquals("1", recorded.get("MOZ_CRASHREPORTER"));
    }

    @Test
    void firefoxPersistentLaunchWithSetEnv() throws IOException {
        Map<String, String> recorded = recordPersistentLaunch(playwright.firefox(), onlyTestVariable());
        assertReplaced(recorded);
        assertEquals("1", recorded.get("MOZ_CRASHREPORTER_NO_REPORT"));
    }

    private static Map<String, String> onlyTestVariable() {
        Map<String, String> environment = new HashMap<>();
        environment.put(TEST_VARIABLE, TEST_VALUE);
        return environment;
    }

    private static void assertInherited(Map<String, String> recorded) {
        assertEquals("from-test-jvm", recorded.get(INHERITED), "the driver's environment reaches the browser");
        assertNull(recorded.get(TEST_VARIABLE));
        assertTrue(recorded.containsKey(pathName(recorded)), "PATH inherited");
    }

    private static void assertReplaced(Map<String, String> recorded) {
        assertEquals(TEST_VALUE, recorded.get(TEST_VARIABLE), "setEnv reaches the browser");
        assertFalse(recorded.containsKey(INHERITED), "setEnv replaces the inherited environment (Playwright semantics)");
    }

    private static String pathName(Map<String, String> recorded) {
        // Windows spells it "Path".
        for (String name : recorded.keySet()) {
            if (name.equalsIgnoreCase("PATH")) {
                return name;
            }
        }
        return "PATH";
    }

    private static Map<String, String> recordLaunch(BrowserType browserType, Map<String, String> environment) throws IOException {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setExecutablePath(fakeBrowser).setTimeout(30_000);
        configure(browserType, options::setChannel);
        if (environment != null) {
            options.setEnv(environment);
        }
        return recordFailedLaunch(() -> browserType.launch(options).close());
    }

    private static Map<String, String> recordPersistentLaunch(BrowserType browserType, Map<String, String> environment) throws IOException {
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setExecutablePath(fakeBrowser).setTimeout(30_000);
        configure(browserType, options::setChannel);
        if (environment != null) {
            options.setEnv(environment);
        }
        Path profile = Files.createTempDirectory(directory, "profile");
        return recordFailedLaunch(() -> browserType.launchPersistentContext(profile, options).close());
    }

    private static void configure(BrowserType browserType, Consumer<String> channel) {
        if ("firefox".equals(browserType.name())) {
            channel.accept("moz-firefox");
        }
    }

    private static Map<String, String> recordFailedLaunch(Runnable launch) throws IOException {
        Files.deleteIfExists(record);
        assertThrows(PlaywrightException.class, launch::run, "the fake browser exits, so the launch fails");
        assertTrue(Files.exists(record), "the fake browser was started");
        Map<String, String> recorded = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(record, StandardCharsets.ISO_8859_1);
        for (String line : lines) {
            int equals = line.indexOf('=', 1);
            if (equals > 0) {
                recorded.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return recorded;
    }
}
