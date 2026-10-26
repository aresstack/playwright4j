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
 * The profile's user.js across launches: Playwright's BidiFirefox.prepareUserDataDir rewrites it on
 * every launch, the node-compat layer keeps the profile's own lines in a marked block before
 * Playwright's. Only that block counts as the profile's own on the next launch, so firefoxUserPrefs
 * of an earlier launch never stick, and the profile's own lines are never dropped.
 */
final class NodeCompatFirefoxUserJsTest {

    private static final long TIMEOUT_MILLIS = 30_000;
    private static final String BEGIN = "// playwright4j: the lines below are kept from this profile's own user.js on every launch.";
    private static final String END = "// playwright4j: end of the kept lines; Playwright's preferences follow.";
    private static final String IMAGES_OFF = "user_pref(\"permissions.default.image\", 2);";
    private static final String PLAYWRIGHTS_FIRST = "user_pref(\"app.normandy.api_url\", \"\");";

    @TempDir
    Path profile;

    private GraalPlaywrightRuntime runtime;

    @BeforeEach
    void start() {
        // The profile is a real directory, so path.join must use the host's separator.
        String platform = System.getProperty("os.name").toLowerCase().contains("win") ? "win32" : "linux";
        Playwright4JHost host = new Playwright4JHost(
                new FixedHostEnvironment(platform, "x64", "/", java.util.Collections.<String, String>emptyMap()),
                new LocalHostFileSystem(),
                new RecordingMissingHostFunctionReporter());
        runtime = new GraalPlaywrightRuntime(host, new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    @Test
    void firefoxUserPrefsOfAnEarlierLaunchDoNotStickInAProfileWithoutUserJs() throws Exception {
        launch("{ 'permissions.default.image': 2 }");
        List<String> first = userJs();
        assertEquals(List.of(), keptBlock(first), "nothing of the profile's own to keep yet");
        assertTrue(first.contains(IMAGES_OFF), first.toString());

        launch("{}");
        List<String> second = userJs();
        assertEquals(List.of(), keptBlock(second), second.toString());
        assertFalse(second.contains(IMAGES_OFF), "the earlier launch's firefoxUserPrefs stuck: " + second);
    }

    @Test
    void ownLinesStayWhenALaunchSetsTheSamePreference() throws Exception {
        List<String> own = Arrays.asList(
                "user_pref(\"playwright4j.test.own\", \"kept\");",
                "user_pref(\"permissions.default.image\", 1);",
                "user_pref(\"dom.testing.testutils.enabled\", true);");
        Files.write(profile.resolve("user.js"), own, StandardCharsets.ISO_8859_1);

        launch("{ 'permissions.default.image': 2 }");
        List<String> first = userJs();
        assertEquals(own, keptBlock(first));
        assertTrue(first.indexOf(IMAGES_OFF) > first.indexOf(END), "firefoxUserPrefs come after the profile's own lines and win: " + first);
        assertTrue(first.indexOf("user_pref(\"dom.testing.testutils.enabled\", false);") > first.indexOf(END), first.toString());

        launch("{}");
        launch("{}");
        List<String> third = userJs();
        assertEquals(own, keptBlock(third), "the profile's own image setting applies again once no launch sets one");
        assertFalse(third.contains(IMAGES_OFF), third.toString());
    }

    @Test
    void aUserJsPlaywrightWroteIsNotTakenOver() throws Exception {
        // As playwright4j up to 0.2.1 left it: Playwright's preferences and that launch's firefoxUserPrefs.
        run("(require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle.internals).createProfile("
                + "{ path: " + js(profile) + ", preferences: { 'permissions.default.image': 2 } })");
        assertTrue(userJs().contains(IMAGES_OFF));

        launch("{}");
        List<String> lines = userJs();
        assertEquals(List.of(), keptBlock(lines), "an earlier Playwright launch counts as the profile's own");
        assertFalse(lines.contains(IMAGES_OFF), lines.toString());
    }

    @Test
    void aUserJsPlaywrightWroteThatTheProfileCommentedOutIsTakenOver() throws Exception {
        run("(require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle.internals).createProfile("
                + "{ path: " + js(profile) + ", preferences: {} })");
        List<String> own = new java.util.ArrayList<>(userJs());
        own.add(0, "/* Playwright's preferences, switched off:");
        own.add("*/");
        Files.write(profile.resolve("user.js"), own, StandardCharsets.ISO_8859_1);

        launch("{}");
        assertEquals(own, keptBlock(userJs()));
    }

    @Test
    void aUserJsWithSomeOfPlaywrightsPreferencesIsTakenOver() throws Exception {
        List<String> own = Arrays.asList(
                "// hand-written, with two preferences Playwright also sets",
                PLAYWRIGHTS_FIRST,
                "user_pref(\"browser.startup.page\", 3);",
                "user_pref(\"playwright4j.test.own\", 1);");
        Files.write(profile.resolve("user.js"), own, StandardCharsets.ISO_8859_1);

        launch("{}");
        assertEquals(own, keptBlock(userJs()));
    }

    @Test
    void anOpenCommentOrStatementAtTheEndOfTheOwnLinesIsClosedOffBeforePlaywrightsLines() throws Exception {
        assertEquals(List.of(PLAYWRIGHTS_FIRST), betweenEndAndPlaywright("user_pref(\"a\", 1);"));
        assertEquals(List.of("*/", PLAYWRIGHTS_FIRST), betweenEndAndPlaywright("user_pref(\"a\", 1);\n/* user_pref(\"b\", 2);"));
        assertEquals(List.of("user_pref;", PLAYWRIGHTS_FIRST), betweenEndAndPlaywright("user_pref(\"a\", 1)"));
        assertEquals(List.of("\"", "user_pref;", PLAYWRIGHTS_FIRST), betweenEndAndPlaywright("user_pref(\"a\", \"it's);"));
        // A single-quoted string ends at the apostrophe of the END marker.
        assertEquals(List.of("user_pref;", PLAYWRIGHTS_FIRST), betweenEndAndPlaywright("user_pref(\"a\", 'abc);"));
        assertEquals(List.of(PLAYWRIGHTS_FIRST), betweenEndAndPlaywright("user_pref(\"a\", \"/* # // ' \\\" \"); // /*\n# \"\nuser_pref(\"b\", 2); /* */"));
        // A '/' that starts no comment takes the next character with it.
        assertEquals(List.of("user_pref;", PLAYWRIGHTS_FIRST), betweenEndAndPlaywright("user_pref(\"a\", 1);\n/\""));
    }

    @Test
    void theOwnLinesEndAtANulByteOutsideAComment() throws Exception {
        // Firefox reads nothing after it, from the profile's own file either (a UTF-16 user.js has one
        // after its first character); before Playwright's lines it would hide them.
        assertEquals(List.of(PLAYWRIGHTS_FIRST), betweenEndAndPlaywright("user_pref(\"a\", 1);\0\nuser_pref(\"b\", 2);\n"));
        assertEquals(List.of("user_pref(\"a\", 1);"), keptBlock(userJs()));
        assertEquals(List.of("\"", "user_pref;", PLAYWRIGHTS_FIRST), betweenEndAndPlaywright("user_pref(\"a\", \"x\0y\");\n"));
        assertEquals(List.of("user_pref(\"a\", \"x"), keptBlock(userJs()));

        // In a // comment it only ends the comment.
        betweenEndAndPlaywright("user_pref(\"a\", 1);\n// x\0user_pref(\"b\", 2);\n");
        assertEquals(List.of("user_pref(\"a\", 1);", "// x\0user_pref(\"b\", 2);"), keptBlock(userJs()));
    }

    private List<String> betweenEndAndPlaywright(String own) throws Exception {
        Files.write(profile.resolve("user.js"), own.getBytes(StandardCharsets.ISO_8859_1));
        launch("{}");
        List<String> lines = userJs();
        int end = lines.indexOf(END);
        return lines.subList(end + 1, lines.indexOf(PLAYWRIGHTS_FIRST) + 1);
    }

    private void launch(String firefoxUserPrefs) {
        // Loaded through the server index like the driver does; on its own the module hits an import cycle.
        run("(require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle.internals).BidiFirefox"
                + ".prototype.prepareUserDataDir.call(null, { firefoxUserPrefs: " + firefoxUserPrefs + " }, " + js(profile) + ")");
    }

    private void run(String promise) {
        runtime.evaluate("user-js-run.js", ""
                + "(function () {\n"
                + "  const test = globalThis.__userJsTest = { state: 'running' };\n"
                + "  Promise.resolve().then(function () { return " + promise + "; })\n"
                + "    .then(function () { test.state = 'done'; }, function (error) { test.state = 'error: ' + (error && error.stack || error); });\n"
                + "})();");
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (true) {
            String state = runtime.evaluate("user-js-query.js", "__userJsTest.state").asString();
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

    private static List<String> keptBlock(List<String> lines) {
        int begin = lines.indexOf(BEGIN);
        int end = lines.indexOf(END);
        assertTrue(begin == 0 && end > begin, "user.js does not start with the marked block, it starts with: "
                + lines.subList(0, Math.min(3, lines.size())));
        return lines.subList(begin + 1, end);
    }

    private static String js(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
