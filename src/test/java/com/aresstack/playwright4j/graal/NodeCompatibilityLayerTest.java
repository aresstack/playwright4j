package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
