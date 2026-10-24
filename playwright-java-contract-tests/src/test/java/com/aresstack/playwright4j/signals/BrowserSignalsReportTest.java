package com.aresstack.playwright4j.signals;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnostic, not a pass/fail gate: prints the automation signals of a browser launched through
 * playwright4j (see {@link BrowserSignalsProbe}) and writes them to {@code PROBE_OUT}, so they can
 * be diffed against a reference run with the official Playwright driver. Runs only when a browser
 * is configured: FIREFOX_EXECUTABLE (or PROBE_EXECUTABLE) for Firefox, PROBE_BROWSER=chromium with
 * PROBE_CHANNEL or PROBE_EXECUTABLE for Chrome/Edge.
 */
public class BrowserSignalsReportTest {

    @Test
    void reportBrowserSignals() throws Exception {
        BrowserSignalsProbe.Config config = BrowserSignalsProbe.Config.fromEnvironment();
        boolean configured = "chromium".equals(config.browser)
                ? config.channel != null || config.executable != null
                : config.executable != null && Files.isRegularFile(Paths.get(config.executable));
        Assumptions.assumeTrue(configured, "Set FIREFOX_EXECUTABLE (or PROBE_BROWSER=chromium with "
                + "PROBE_CHANNEL/PROBE_EXECUTABLE) to collect browser signals.");

        String report = new BrowserSignalsProbe().run(config);
        System.out.println(report);
        assertTrue(report.contains("page.navigator.userAgent = "), "The probe page did not report back");
    }
}
