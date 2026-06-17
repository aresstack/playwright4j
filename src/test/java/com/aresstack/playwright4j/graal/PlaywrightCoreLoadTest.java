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

        try (GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(host, driverBundleSource)) {
            runtime.loadNodeCompatibilityLayer();
            runtime.evaluateCommonJsEntry(driverBundleSource.cliScriptResourceName(), driverBundleSource.readCliScript());
        } catch (PolyglotException exception) {
            String message = exception.getMessage();

            assertFalse(message.contains("SyntaxError"), message);
            assertFalse(reporter.missingFunctions().contains("require(./lib/cli/programWithTestStub)"), reporter.missingFunctions().toString());
            assertFalse(reporter.missingFunctions().contains("require(readline)"), reporter.missingFunctions().toString());
            assertFalse(message.contains("Cannot read property 'version' of undefined"), message);
        }
    }
}
