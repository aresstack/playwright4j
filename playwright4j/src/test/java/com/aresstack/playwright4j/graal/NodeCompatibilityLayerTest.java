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

    private static boolean accessible(GraalPlaywrightRuntime runtime, String path) {
        return runtime.evaluate("fs-access.js",
                "(function () { try { require('fs').accessSync('" + path + "'); return true; } catch (e) { return false; } })()")
                .asBoolean();
    }
}
