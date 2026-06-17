package com.aresstack.playwright4j.spike;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aresstack.playwright4j.graal.EmptyHostFileSystem;
import com.aresstack.playwright4j.graal.FixedHostEnvironment;
import com.aresstack.playwright4j.graal.GraalPlaywrightRuntime;
import com.aresstack.playwright4j.graal.Playwright4JHost;
import com.aresstack.playwright4j.graal.PlaywrightDriverBundleSource;
import com.aresstack.playwright4j.graal.RecordingMissingHostFunctionReporter;

import java.nio.file.Path;
import java.util.Collections;

import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

final class GraalInstalledChromePlaywrightCoreSpikeTest {

    @Test
    void startsInstalledChromeAndLoadsPlaywrightCorePackageInGraalVm() {
        assumeTrue(Boolean.getBoolean("playwright4j.graalChromeSpike"),
                "Enable with -Pplaywright4j.graalChromeSpike=true");

        Path chromeExecutable = new InstalledChromeLocator().locateChromeExecutable();
        RecordingMissingHostFunctionReporter reporter = new RecordingMissingHostFunctionReporter();
        Playwright4JHost host = new Playwright4JHost(
                new FixedHostEnvironment("win32", "x64", "/", Collections.singletonMap("PW_LANG_NAME", "java")),
                new EmptyHostFileSystem(),
                reporter);
        PlaywrightDriverBundleSource driverBundleSource = new PlaywrightDriverBundleSource(
                Thread.currentThread().getContextClassLoader());

        try (RemoteDebuggingChrome chrome = RemoteDebuggingChrome.start(chromeExecutable);
             GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(host, driverBundleSource)) {
            runtime.loadNodeCompatibilityLayer();
            Value playwright = runtime.evaluateCommonJsEntryAsGlobal(
                    "__playwright4jPlaywright",
                    driverBundleSource.packageResourceName("index.js"),
                    driverBundleSource.readPackageResource("index.js"));

            assertTrue(playwright.hasMember("chromium"), "Playwright package entry must expose chromium.");

            try {
                runtime.evaluate(
                        "connect-over-cdp-spike.js",
                        "globalThis.__playwright4jConnectOverCdpResult = "
                                + "globalThis.__playwright4jPlaywright.chromium.connectOverCDP('" + chrome.cdpEndpoint() + "');");
            } catch (PolyglotException exception) {
                String message = exception.getMessage();

                assertFalse(message.contains("SyntaxError"), message);
                assertFalse(reporter.missingFunctions().isEmpty(), "The spike should expose the next missing host boundary.");
            }
        }
    }
}
