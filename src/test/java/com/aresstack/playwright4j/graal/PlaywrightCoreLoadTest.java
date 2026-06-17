package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

final class PlaywrightCoreLoadTest {

    @Test
    void loadsMicrosoftPlaywrightCliScriptFromDriverBundleIntoGraalVm() {
        RecordingMissingHostFunctionReporter reporter = new RecordingMissingHostFunctionReporter();
        Playwright4JHost host = new Playwright4JHost(
                new FixedHostEnvironment(),
                new EmptyHostFileSystem(),
                reporter);
        PlaywrightDriverBundleSource driverBundleSource = new PlaywrightDriverBundleSource(
                Thread.currentThread().getContextClassLoader());

        try (GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(host)) {
            runtime.loadNodeCompatibilityLayer();
            runtime.evaluate(driverBundleSource.cliScriptResourceName(), driverBundleSource.readCliScript());

            fail("Expected the real Playwright CLI script to reach the next unsupported module boundary.");
        } catch (PolyglotException exception) {
            String message = exception.getMessage();

            assertFalse(message.contains("SyntaxError"), message);
            assertTrue(message.contains("Unsupported Playwright4J module"), message);
            assertTrue(reporter.missingFunctions().contains("require(./lib/cli/programWithTestStub)"), reporter.missingFunctions().toString());
        }
    }
}
