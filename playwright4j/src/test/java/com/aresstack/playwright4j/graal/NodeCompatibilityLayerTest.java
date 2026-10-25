package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NodeCompatibilityLayerTest {

    @Test
    void delegatesEnvironmentAndFileSystemCallsToJavaHost() {
        try (GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(Playwright4JHost.createDefault())) {
            runtime.loadNodeCompatibilityLayer();

            assertEquals("win32", runtime.evaluate("platform.js", "process.platform").asString());
            assertEquals("x64", runtime.evaluate("arch.js", "process.arch").asString());
            assertFalse(runtime.evaluate("fs-exists.js", "require('fs').existsSync('/package/package.json')").asBoolean());
        }
    }

    @Test
    void reportsTheRegistryFfmpegAsAccessibleWithoutANativeBinary() {
        try (GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(Playwright4JHost.createDefault())) {
            runtime.loadNodeCompatibilityLayer();

            assertTrue(accessible(runtime, "C:\\\\Users\\\\me\\\\AppData\\\\Local/ms-playwright/ffmpeg-1011/ffmpeg-win64.exe"));
            assertTrue(accessible(runtime, "/home/me/.cache/ms-playwright/ffmpeg-1011/ffmpeg-linux"));
            assertTrue(accessible(runtime, "/Users/me/Library/Caches/ms-playwright/ffmpeg-1011/ffmpeg-mac"));
            assertFalse(accessible(runtime, "/home/me/.cache/ms-playwright/ffmpeg-1011/other-binary"));
            assertFalse(accessible(runtime, "/home/me/.cache/ms-playwright/chromium-1243/chrome-linux/chrome"));
        }
    }

    @Test
    void urlEncodesAssignedCredentialsLikeNode() {
        try (GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(Playwright4JHost.createDefault())) {
            runtime.loadNodeCompatibilityLayer();

            // Playwright sets proxy credentials this way; the href must stay a parseable URL.
            assertEquals("http://us%20er:p%40ss%3Aw%C3%B6rd@127.0.0.1:3128/", runtime.evaluate("url-credentials.js",
                    "(function () { const url = new URL('http://127.0.0.1:3128');"
                            + " url.username = 'us er'; url.password = 'p@ss:w\u00f6rd'; return url.href; })()").asString());
            assertEquals("p%40ss", runtime.evaluate("url-parsed-credentials.js",
                    "new URL('http://user:p%40ss@127.0.0.1/').password").asString());
        }
    }

    private static boolean accessible(GraalPlaywrightRuntime runtime, String path) {
        return runtime.evaluate("fs-access.js",
                "(function () { try { require('fs').accessSync('" + path + "'); return true; } catch (e) { return false; } })()")
                .asBoolean();
    }
}
