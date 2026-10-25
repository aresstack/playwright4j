package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Camoufox (a Firefox build with fingerprint configuration) over WebDriver BiDi: the configuration
 * Camoufox reads from CAMOU_CONFIG_1..n reaches the browser through setEnv. Opt-in: point
 * CAMOUFOX_EXECUTABLE at camoufox(.exe) or camoufox-bin; PLAYWRIGHT4J_REQUIRE_CAMOUFOX=true fails
 * instead of skipping. A second page needs a Camoufox with the two WebDriver BiDi fixes described in
 * docs/camoufox.md; set CAMOUFOX_BIDI_FIXES=true to check it too.
 */
public class CamoufoxFingerprintTest {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    // The official Camoufox launchers split the JSON into chunks of this size.
    private static final int CHUNK = WINDOWS ? 2047 : 32767;
    private static final String PLATFORM = "Playwright4jTest";
    private static final int CORES = 3;
    private static final String FINGERPRINT = "navigator.platform + ' / ' + navigator.hardwareConcurrency";

    @Test
    void persistentContextAppliesCamouConfigFromSetEnv() throws Exception {
        String executable = executable();
        Path userDataDir = Files.createTempDirectory("playwright4j-camoufox");
        // setEnv replaces the environment (Playwright semantics): pass the inherited one along.
        Map<String, String> env = new HashMap<>(System.getenv());
        env.putAll(camouConfig("{\"navigator.platform\":\"" + PLATFORM + "\",\"navigator.hardwareConcurrency\":" + CORES + "}"));
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir, options(executable).setEnv(env));
            try {
                Page first = context.pages().get(0);
                first.navigate("data:text/html,<title>first</title>");
                assertEquals(PLATFORM + " / " + CORES, first.evaluate(FINGERPRINT), "first page");
                if ("true".equalsIgnoreCase(System.getenv("CAMOUFOX_BIDI_FIXES"))) {
                    Page second = context.newPage();
                    second.navigate("data:text/html,<title>second</title>");
                    assertEquals(PLATFORM + " / " + CORES, second.evaluate(FINGERPRINT), "second page");
                }
            } finally {
                context.close();
            }
        } finally {
            FirefoxTestSupport.deleteRecursively(userDataDir);
        }
    }

    @Test
    void withoutSetEnvTheConfigIsAbsent() throws Exception {
        String executable = executable();
        Assumptions.assumeTrue(System.getenv("CAMOU_CONFIG_1") == null && System.getenv("CAMOU_CONFIG") == null,
                "the test JVM itself carries a Camoufox configuration");
        Path userDataDir = Files.createTempDirectory("playwright4j-camoufox");
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir, options(executable));
            try {
                Page first = context.pages().get(0);
                first.navigate("data:text/html,<title>first</title>");
                assertNotEquals(PLATFORM + " / " + CORES, first.evaluate(FINGERPRINT));
            } finally {
                context.close();
            }
        } finally {
            FirefoxTestSupport.deleteRecursively(userDataDir);
        }
    }

    private static Map<String, String> camouConfig(String json) {
        Map<String, String> chunks = new HashMap<>();
        for (int i = 0; i < json.length(); i += CHUNK) {
            chunks.put("CAMOU_CONFIG_" + (i / CHUNK + 1), json.substring(i, Math.min(json.length(), i + CHUNK)));
        }
        return chunks;
    }

    private static BrowserType.LaunchPersistentContextOptions options(String executable) {
        return new BrowserType.LaunchPersistentContextOptions()
                .setChannel("moz-firefox")
                .setExecutablePath(Paths.get(executable))
                .setHeadless(FirefoxTestSupport.headless());
    }

    private static String executable() {
        String configured = System.getenv("CAMOUFOX_EXECUTABLE");
        boolean available = configured != null && !configured.trim().isEmpty() && Files.isRegularFile(Paths.get(configured));
        if (!available && "true".equalsIgnoreCase(System.getenv("PLAYWRIGHT4J_REQUIRE_CAMOUFOX"))) {
            fail("PLAYWRIGHT4J_REQUIRE_CAMOUFOX=true but CAMOUFOX_EXECUTABLE does not point to a file: " + configured);
        }
        Assumptions.assumeTrue(available, "Set CAMOUFOX_EXECUTABLE to a Camoufox build to run this test.");
        return configured;
    }
}
