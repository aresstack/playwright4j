package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The preferences {@code playwright4j.bidi.nativeBehavior} leaves to the browser: without the
 * property Playwright's test values are written to user.js; with it they are left out of user.js
 * and prefs.js and the Remote Agent's recommended preferences are off, unless firefoxUserPrefs
 * sets them.
 */
final class NodeCompatFirefoxNativeBehaviorTest {

    private static final long TIMEOUT_MILLIS = 30_000;
    private static final String POPUPS_ALLOWED = "user_pref(\"dom.disable_open_during_load\", false);";
    private static final String TEST_FOCUS = "user_pref(\"focusmanager.testmode\", true);";
    private static final String NO_HTTPS_FIRST = "user_pref(\"dom.security.https_first\", false);";
    private static final String TRACKING_OFF = "user_pref(\"browser.contentblocking.features.standard\", \"-tp,tpPrivate,cookieBehavior0,-cm,-fp\");";
    private static final String NO_REMOTE_AGENT_PREFS = "user_pref(\"remote.prefs.recommended\", false);";
    private static final String TESTUTILS_OFF = "user_pref(\"dom.testing.testutils.enabled\", false);";

    @TempDir
    Path profile;

    private GraalPlaywrightRuntime runtime;

    @BeforeEach
    void start() {
        Playwright4JHost host = new Playwright4JHost(new FixedHostEnvironment(), new LocalHostFileSystem(), new RecordingMissingHostFunctionReporter());
        runtime = new GraalPlaywrightRuntime(host, new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
    }

    @AfterEach
    void stop() {
        System.clearProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY);
        runtime.close();
    }

    @Test
    void withoutThePropertyPlaywrightsTestPreferencesAreWritten() throws Exception {
        launch("{}");
        List<String> lines = userJs();
        assertTrue(lines.containsAll(Arrays.asList(POPUPS_ALLOWED, TEST_FOCUS, NO_HTTPS_FIRST, TRACKING_OFF)), lines.toString());
        assertFalse(lines.contains(NO_REMOTE_AGENT_PREFS), lines.toString());
    }

    @Test
    void withThePropertyTheBrowsersOwnValuesApply() throws Exception {
        System.setProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY, "true");
        launch("{}");
        List<String> lines = userJs();
        for (String name : Arrays.asList(
                "browser.contentblocking.features.standard",
                "dom.disable_open_during_load",
                "dom.min_background_timeout_value",
                "dom.min_background_timeout_value_without_budget_throttling",
                "dom.security.https_first",
                "dom.timeout.enable_budget_timer_throttling",
                "focusmanager.testmode",
                "geo.provider.testing",
                "layout.testing.top-level-always-active",
                "network.http.speculative-parallel-limit",
                "network.manage-offline-status")) {
            assertFalse(lines.stream().anyMatch(line -> line.startsWith("user_pref(\"" + name + "\",")), name + " in " + lines);
        }
        // The Remote Agent would set most of them again.
        assertTrue(lines.contains(NO_REMOTE_AGENT_PREFS), lines.toString());
        // Everything else Playwright and playwright4j write stays.
        assertTrue(lines.contains(TESTUTILS_OFF), lines.toString());
        assertTrue(lines.contains("user_pref(\"app.normandy.api_url\", \"\");"), lines.toString());
    }

    @Test
    void firefoxUserPrefsStillSetThem() throws Exception {
        System.setProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY, "true");
        launch("{ 'dom.disable_open_during_load': false, 'focusmanager.testmode': true }");
        List<String> lines = userJs();
        assertTrue(lines.contains(POPUPS_ALLOWED), lines.toString());
        assertTrue(lines.contains(TEST_FOCUS), lines.toString());
        assertFalse(lines.contains(NO_HTTPS_FIRST), lines.toString());

        launch("{ 'remote.prefs.recommended': true }");
        assertTrue(userJs().contains("user_pref(\"remote.prefs.recommended\", true);"));
        assertFalse(userJs().contains(NO_REMOTE_AGENT_PREFS));
    }

    @Test
    void onlyPlaywrightsValuesFromTheLastLaunchAreRemovedFromPrefsJs() throws Exception {
        launch("{}");
        // Firefox saved Playwright's user.js values; the user changed two prefs in about:config.
        List<String> saved = Arrays.asList(
                "// Mozilla User Preferences",
                POPUPS_ALLOWED,
                TEST_FOCUS,
                "user_pref(\"dom.security.https_first\", true);",
                "user_pref(\"geo.provider.testing\", false);",
                "user_pref(\"playwright4j.test.own\", 1);");
        Files.write(profile.resolve("prefs.js"), saved, StandardCharsets.ISO_8859_1);

        launch("{}");
        assertEquals(saved, prefsJs(), "without the property prefs.js stays as Firefox wrote it");

        System.setProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY, "true");
        launch("{ 'focusmanager.testmode': true }");
        assertEquals(Arrays.asList(
                "// Mozilla User Preferences",
                TEST_FOCUS,
                "user_pref(\"dom.security.https_first\", true);",
                "user_pref(\"geo.provider.testing\", false);",
                "user_pref(\"playwright4j.test.own\", 1);"), prefsJs());
    }

    @Test
    void prefsJsOfAProfileWithoutPlaywright4jsMarkersStays() throws Exception {
        // As playwright4j up to 0.2.1 or plain Playwright left it: the origin of a value is unknown.
        Files.write(profile.resolve("user.js"), Arrays.asList(POPUPS_ALLOWED, TEST_FOCUS), StandardCharsets.ISO_8859_1);
        List<String> saved = Arrays.asList("// Mozilla User Preferences", POPUPS_ALLOWED, TEST_FOCUS);
        Files.write(profile.resolve("prefs.js"), saved, StandardCharsets.ISO_8859_1);

        System.setProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY, "true");
        launch("{}");
        assertEquals(saved, prefsJs());
    }

    private void launch(String firefoxUserPrefs) {
        // Loaded through the server index like the driver does; on its own the module hits an import cycle.
        run("(require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle.internals).BidiFirefox"
                + ".prototype.prepareUserDataDir.call(null, { firefoxUserPrefs: " + firefoxUserPrefs + " }, "
                + "'" + profile.toString().replace("\\", "\\\\").replace("'", "\\'") + "')");
    }

    private void run(String promise) {
        runtime.evaluate("native-behavior-run.js", ""
                + "(function () {\n"
                + "  const test = globalThis.__nativeBehaviorTest = { state: 'running' };\n"
                + "  Promise.resolve().then(function () { return " + promise + "; })\n"
                + "    .then(function () { test.state = 'done'; }, function (error) { test.state = 'error: ' + (error && error.stack || error); });\n"
                + "})();");
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (true) {
            String state = runtime.evaluate("native-behavior-query.js", "__nativeBehaviorTest.state").asString();
            if (state.equals("done")) {
                return;
            }
            if (state.startsWith("error")) {
                fail(state);
            }
            if (System.currentTimeMillis() > deadline) {
                fail("user.js preparation did not finish; recorded errors: " + runtime.unhandledRejections().recorded());
            }
            if (runtime.runDueTimers() == 0 && runtime.drainTransports() == 0) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
        }
    }

    private List<String> userJs() throws Exception {
        return Files.readAllLines(profile.resolve("user.js"), StandardCharsets.ISO_8859_1);
    }

    private List<String> prefsJs() throws Exception {
        return Files.readAllLines(profile.resolve("prefs.js"), StandardCharsets.ISO_8859_1);
    }
}
