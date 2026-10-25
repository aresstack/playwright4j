package com.aresstack.playwright4j.passive;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnostic, not a pass/fail gate: prints what {@link PassiveAcquisitionProbe} measured for the
 * Firefox (or Camoufox) build in FIREFOX_EXECUTABLE / PROBE_EXECUTABLE.
 */
public class PassiveAcquisitionReportTest {

    @Test
    void reportPassiveAcquisition() throws Exception {
        Assumptions.assumeTrue(System.getenv("FIREFOX_EXECUTABLE") != null || System.getenv("PROBE_EXECUTABLE") != null,
                "Set FIREFOX_EXECUTABLE (or PROBE_EXECUTABLE) to measure passive acquisition.");
        String report = new PassiveAcquisitionProbe().run();
        System.out.println(report);
        assertTrue(report.contains("locateNodes(css html, depth"), "The probe did not reach the DOM reads");
    }
}
