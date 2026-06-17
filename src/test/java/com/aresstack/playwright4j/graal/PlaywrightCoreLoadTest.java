package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

final class PlaywrightCoreLoadTest {

    @Test
    void loadsBundledPlaywrightCoreWithCompatibilityLayer() {
        try (GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(Playwright4JHost.createDefault())) {
            runtime.loadNodeCompatibilityLayer();
            runtime.loadPlaywrightCoreBundle();

            Value playwright = runtime.readGlobal("playwright");
            assertNotNull(playwright);
            assertTrue(playwright.hasMember("chromium"));
            assertTrue(playwright.hasMember("__playwright4jProbe"));
            assertEquals("win32", playwright.getMember("__playwright4jProbe").getMember("platform").asString());
            assertFalse(playwright.getMember("__playwright4jProbe").getMember("packageMetadataExists").asBoolean());
        }
    }
}
