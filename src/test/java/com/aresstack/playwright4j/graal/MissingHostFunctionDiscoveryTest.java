package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

final class MissingHostFunctionDiscoveryTest {

    @Test
    void recordsMissingHostFunctionWhenBrowserLaunchPathIsReached() {
        RecordingMissingHostFunctionReporter reporter = new RecordingMissingHostFunctionReporter();
        Playwright4JHost host = new Playwright4JHost(
                new FixedHostEnvironment(),
                new EmptyHostFileSystem(),
                reporter);

        try (GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(host)) {
            runtime.loadNodeCompatibilityLayer();
            runtime.loadPlaywrightCoreBundle();

            runtime.evaluate(
                    "connect-over-cdp.js",
                    "globalThis.playwright.chromium.connectOverCDP('http://localhost:9222')");

            fail("Expected a missing host implementation.");
        } catch (PolyglotException exception) {
            assertTrue(exception.getMessage().contains("Missing Playwright4J host implementation"));
            assertTrue(reporter.missingFunctions().contains("child_process.spawn"));
        }
    }
}
