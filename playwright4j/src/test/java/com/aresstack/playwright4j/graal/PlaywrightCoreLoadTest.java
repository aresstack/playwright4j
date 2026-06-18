package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Collections;

import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

final class PlaywrightCoreLoadTest {

    @Test
    void loadsPlaywrightJavaRunDriverEntryFromDriverBundleIntoGraalVm() {
        RecordingMissingHostFunctionReporter reporter = new RecordingMissingHostFunctionReporter();
        Playwright4JHost host = new Playwright4JHost(
                new FixedHostEnvironment("win32", "x64", "/", Collections.singletonMap("PW_LANG_NAME", "java")),
                new EmptyHostFileSystem(),
                reporter);
        PlaywrightDriverBundleSource driverBundleSource = new PlaywrightDriverBundleSource(
                Thread.currentThread().getContextClassLoader());

        try (GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(host, driverBundleSource)) {
            runtime.loadNodeCompatibilityLayer();
            runtime.setProcessArguments("node", driverBundleSource.cliScriptResourceName(), "run-driver");

            try {
                runtime.evaluateCommonJsEntry(driverBundleSource.cliScriptResourceName(), driverBundleSource.readCliScript());
            } catch (PolyglotException exception) {
                String message = exception.getMessage();

                assertFalse(message.contains("SyntaxError"), message);
                assertFalse(message.contains("Cannot read property 'version' of undefined"), message);
            }

            assertFalse(reporter.missingFunctions().contains("require(./lib/cli/programWithTestStub)"), reporter.missingFunctions().toString());
            assertFalse(reporter.missingFunctions().contains("require(readline)"), reporter.missingFunctions().toString());
            assertFalse(reporter.missingFunctions().contains("require(http2)"), reporter.missingFunctions().toString());
            assertFalse(reporter.missingFunctions().contains("require(dns)"), reporter.missingFunctions().toString());
        }
    }
}
