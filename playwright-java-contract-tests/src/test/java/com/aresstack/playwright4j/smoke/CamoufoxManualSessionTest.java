package com.aresstack.playwright4j.smoke;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Manual session: opens Camoufox over WebDriver BiDi, persistent and headed without viewport and
 * media emulation, with a given launch configuration, prints what a page sees, and waits until the person
 * closes the browser. For comparing against Camoufox's own (Juggler) launcher by hand, see
 * docs/camoufox.md and docs/camoufox/juggler-reference.mjs. Runs only with CAMOUFOX_MANUAL=true and
 * CAMOUFOX_EXECUTABLE.
 * <p>
 * CAMOUFOX_LAUNCH_OPTIONS (optional) is a JSON file with "env" (added to the inherited environment,
 * e.g. CAMOU_CONFIG_1..n), "firefoxUserPrefs" and "args", as Camoufox's launchOptions() returns them.
 * Values from it are never printed, only the names of the variables. CAMOUFOX_PROFILE (optional) is
 * the profile directory, CAMOUFOX_START_URL the first URL, CAMOUFOX_MANUAL_MINUTES the longest wait.
 */
public class CamoufoxManualSessionTest {
    private static final String PROBE = "(() => { const r = {"
            + " userAgent: navigator.userAgent, platform: navigator.platform, webdriver: navigator.webdriver,"
            + " hardwareConcurrency: navigator.hardwareConcurrency, languages: navigator.languages,"
            + " screen: screen.width + 'x' + screen.height, window: outerWidth + 'x' + outerHeight + ' / inner ' + innerWidth + 'x' + innerHeight,"
            + " devicePixelRatio: devicePixelRatio, timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,"
            + " colorScheme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',"
            + " hasFocus: document.hasFocus(), visibilityState: document.visibilityState };"
            + " try { const gl = document.createElement('canvas').getContext('webgl');"
            + " const ext = gl && gl.getExtension('WEBGL_debug_renderer_info');"
            + " r.webglRenderer = ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : null; } catch (e) { r.webglRenderer = String(e); }"
            + " return JSON.stringify(r, null, 2); })()";

    @Test
    void manualSession() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CAMOUFOX_MANUAL")), "Set CAMOUFOX_MANUAL=true for a manual session.");
        String executable = System.getenv("CAMOUFOX_EXECUTABLE");
        Assumptions.assumeTrue(executable != null && Files.isRegularFile(Paths.get(executable)), "Set CAMOUFOX_EXECUTABLE to a Camoufox build.");

        // setEnv replaces the environment (Playwright semantics): start from the inherited one.
        Map<String, String> env = new HashMap<>(System.getenv());
        Map<String, Object> prefs = new HashMap<>();
        List<String> args = new ArrayList<>();
        String launchOptions = System.getenv("CAMOUFOX_LAUNCH_OPTIONS");
        if (launchOptions != null && !launchOptions.trim().isEmpty()) {
            JsonObject json = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(launchOptions)), StandardCharsets.UTF_8)).getAsJsonObject();
            // Camoufox's launchers put the whole process environment into "env"; only the Camoufox
            // variables are named in the output, never any value.
            TreeSet<String> camoufoxVariables = new TreeSet<>();
            if (json.has("env")) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("env").entrySet()) {
                    if (!entry.getValue().isJsonNull()) {
                        env.put(entry.getKey(), entry.getValue().getAsString());
                        if (entry.getKey().startsWith("CAMOU_") || entry.getKey().startsWith("FONTCONFIG")) {
                            camoufoxVariables.add(entry.getKey());
                        }
                    }
                }
            }
            // camoufox-js returns firefoxUserPrefs, the Python package firefox_user_prefs.
            String prefsKey = json.has("firefoxUserPrefs") ? "firefoxUserPrefs" : "firefox_user_prefs";
            if (json.has(prefsKey)) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject(prefsKey).entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                        prefs.put(entry.getKey(), value.getAsBoolean());
                    } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                        prefs.put(entry.getKey(), value.getAsNumber().intValue());
                    } else if (value.isJsonPrimitive()) {
                        prefs.put(entry.getKey(), value.getAsString());
                    }
                }
            }
            if (json.has("args")) {
                json.getAsJsonArray("args").forEach(arg -> args.add(arg.getAsString()));
            }
            System.out.println("[camoufox] launch options: Camoufox variables " + camoufoxVariables + ", "
                    + prefs.size() + " Firefox preferences, arguments " + args);
        }

        String profile = System.getenv("CAMOUFOX_PROFILE");
        Path userDataDir = profile != null && !profile.trim().isEmpty() ? Paths.get(profile) : Files.createTempDirectory("playwright4j-camoufox-manual");
        String startUrl = System.getenv().getOrDefault("CAMOUFOX_START_URL", "about:blank");
        double minutes = Double.parseDouble(System.getenv().getOrDefault("CAMOUFOX_MANUAL_MINUTES", "30"));
        System.out.println("[camoufox] profile " + userDataDir);

        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setChannel("moz-firefox")
                .setExecutablePath(Paths.get(executable))
                .setHeadless(false)
                .setViewportSize(null)
                // Like Camoufox's launcher: no media emulation, the browser answers from the host.
                .setColorScheme(null)
                .setReducedMotion(null)
                .setForcedColors(null)
                .setContrast(null)
                .setEnv(env);
        if (!prefs.isEmpty()) {
            options.setFirefoxUserPrefs(prefs);
        }
        if (!args.isEmpty()) {
            options.setArgs(args);
        }
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir, options);
            boolean[] closed = {false};
            context.onClose(c -> closed[0] = true);
            Page page = context.pages().get(0);
            if (!"about:blank".equals(startUrl)) {
                page.navigate(startUrl);
            }
            System.out.println("[camoufox] what the page sees:\n" + page.evaluate(PROBE));
            // Closing the last tab keeps the window (browser.tabs.closeWindowWithLastTab=false, see
            // docs/testing.md); closing the window ends the browser and the context.
            System.out.println("[camoufox] browser is open; close its window to end (at most " + minutes + " min)");
            try {
                context.waitForCondition(() -> closed[0], new BrowserContext.WaitForConditionOptions().setTimeout(minutes * 60_000));
            } catch (PlaywrightException e) {
                // The wait also ends with an error when the context closes before the condition is checked.
                if (!closed[0]) {
                    System.out.println("[camoufox] time is up, closing");
                    context.close();
                }
            }
            System.out.println("[camoufox] browser closed");
        }
    }
}
