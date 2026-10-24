package com.aresstack.playwright4j.smoke;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Shared setup of the Firefox (moz-firefox, WebDriver BiDi) smoke tests. */
final class FirefoxTestSupport {
    static final long CLOSE_BUDGET_MILLIS = 10_000;
    private static final long PROCESS_EXIT_GRACE_MILLIS = 5_000;

    interface Smoke {
        void run(String executable) throws Exception;
    }

    private FirefoxTestSupport() {
    }

    /**
     * Runs the smoke against FIREFOX_EXECUTABLE (skipped without it, failed with
     * PLAYWRIGHT4J_REQUIRE_FIREFOX=true) and checks that no Firefox process it started survives.
     */
    static void runGuarded(Smoke smoke) throws Throwable {
        String configured = System.getenv("FIREFOX_EXECUTABLE");
        boolean available = configured != null && !configured.trim().isEmpty()
                && Files.isRegularFile(Paths.get(configured));
        if (!available && "true".equalsIgnoreCase(System.getenv("PLAYWRIGHT4J_REQUIRE_FIREFOX"))) {
            fail("PLAYWRIGHT4J_REQUIRE_FIREFOX=true but FIREFOX_EXECUTABLE does not point to a file: " + configured);
        }
        Assumptions.assumeTrue(available, "Set FIREFOX_EXECUTABLE to an installed Firefox to run this smoke.");

        Set<Long> firefoxBefore = firefoxPids();
        try {
            smoke.run(configured);
        } catch (Throwable failure) {
            // Also check for leftovers when the smoke itself failed, so a hang does not hide a leak.
            try {
                assertNoFirefoxLeftBehind(firefoxBefore);
            } catch (Throwable leak) {
                failure.addSuppressed(leak);
            }
            throw failure;
        }
        assertNoFirefoxLeftBehind(firefoxBefore);
    }

    static boolean headless() {
        return "true".equalsIgnoreCase(System.getenv("FIREFOX_HEADLESS"));
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    // Every Firefox process (main + content processes) started by this test must be gone shortly
    // after browser.close(); a survivor means the process exit was not observed or not enforced.
    private static void assertNoFirefoxLeftBehind(Set<Long> before) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PROCESS_EXIT_GRACE_MILLIS;
        Set<Long> leftovers;
        do {
            leftovers = firefoxPids();
            leftovers.removeAll(before);
            if (leftovers.isEmpty()) {
                return;
            }
            Thread.sleep(200);
        } while (System.currentTimeMillis() < deadline);
        fail("Firefox processes survived browser.close(): " + leftovers);
    }

    static Set<Long> firefoxPids() {
        return firefoxProcesses(ProcessHandle.allProcesses())
                .map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }

    static Stream<ProcessHandle> firefoxProcesses(Stream<ProcessHandle> processes) {
        return processes
                .filter(ProcessHandle::isAlive)
                .filter(p -> p.info().command()
                        .map(c -> Paths.get(c).getFileName().toString().toLowerCase(Locale.ROOT))
                        .map(n -> n.equals("firefox") || n.equals("firefox.exe") || n.equals("firefox-bin"))
                        .orElse(false));
    }

    static void assertClosedPromptly(String step, long startMillis) {
        long elapsed = System.currentTimeMillis() - startMillis;
        assertTrue(elapsed < CLOSE_BUDGET_MILLIS, step + " took " + elapsed + " ms");
    }
}
