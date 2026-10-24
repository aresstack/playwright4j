package com.aresstack.playwright4j.smoke;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Shared setup of the Firefox (moz-firefox, WebDriver BiDi) smoke tests. */
final class FirefoxTestSupport {
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    static final long CLOSE_BUDGET_MILLIS = 10_000;
    private static final long PROCESS_EXIT_GRACE_MILLIS = 15_000;
    private static final long SLOW_EXIT_MILLIS = 5_000;

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
    // close() returns once the browser's main process has ended. On the Windows runner some of its
    // processes were seen still running 5 s later and gone a moment after, so the exit window is
    // PROCESS_EXIT_GRACE_MILLIS, and any process running longer than SLOW_EXIT_MILLIS is named in
    // the log with its parent and command line.
    private static void assertNoFirefoxLeftBehind(Set<Long> before) throws InterruptedException {
        long start = System.currentTimeMillis();
        long deadline = start + PROCESS_EXIT_GRACE_MILLIS;
        Map<Long, FirefoxProcess> slow = new LinkedHashMap<>();
        Set<Long> leftovers;
        while (true) {
            leftovers = firefoxPids();
            leftovers.removeAll(before);
            if (leftovers.isEmpty()) {
                break;
            }
            long now = System.currentTimeMillis();
            if (now >= deadline) {
                fail("Firefox processes survived browser.close() by " + PROCESS_EXIT_GRACE_MILLIS + " ms: "
                        + describe(leftovers, firefoxProcessDetails()));
            }
            if (now - start >= SLOW_EXIT_MILLIS && !slow.keySet().containsAll(leftovers)) {
                for (FirefoxProcess process : describe(leftovers, firefoxProcessDetails())) {
                    slow.putIfAbsent(process.pid, process);
                }
            }
            Thread.sleep(200);
        }
        if (!slow.isEmpty()) {
            System.out.println("Firefox processes still ran " + SLOW_EXIT_MILLIS + " ms after close() and ended within "
                    + (System.currentTimeMillis() - start) + " ms: " + slow.values());
        }
    }

    private static List<FirefoxProcess> describe(Set<Long> pids, Map<Long, FirefoxProcess> running) {
        List<FirefoxProcess> described = new ArrayList<>();
        for (Long pid : pids) {
            described.add(running.getOrDefault(pid, new FirefoxProcess(pid, -1, "(ended before it could be described)")));
        }
        return described;
    }

    /** A running Firefox process with its parent and its full command line. */
    static final class FirefoxProcess {
        final long pid;
        final long parent;
        final String commandLine;

        FirefoxProcess(long pid, long parent, String commandLine) {
            this.pid = pid;
            this.parent = parent;
            this.commandLine = commandLine == null ? "" : commandLine;
        }

        @Override
        public String toString() {
            return "pid " + pid + " (parent " + parent + "): " + commandLine;
        }
    }

    /**
     * All running Firefox processes by pid. On Windows the parent pid stays set after the parent has
     * ended, and the command line comes from WMI (ProcessHandle.Info has no arguments there).
     */
    static Map<Long, FirefoxProcess> firefoxProcessDetails() throws InterruptedException {
        Map<Long, FirefoxProcess> details = new HashMap<>();
        if (WINDOWS) {
            try {
                String output = FirefoxWindows.powerShell("Get-CimInstance Win32_Process -Filter \"Name='firefox.exe'\" | "
                        + "ForEach-Object { \"$($_.ProcessId)`t$($_.ParentProcessId)`t$($_.CommandLine)\" }");
                for (String line : output.split("\\R")) {
                    String[] fields = line.split("\t", 3);
                    if (fields.length == 3) {
                        long pid = Long.parseLong(fields[0].trim());
                        details.put(pid, new FirefoxProcess(pid, Long.parseLong(fields[1].trim()), fields[2]));
                    }
                }
                return details;
            } catch (IOException | NumberFormatException wmiUnavailable) {
                details.clear();
            }
        }
        firefoxProcesses(ProcessHandle.allProcesses()).forEach(p -> details.put(p.pid(),
                new FirefoxProcess(p.pid(), p.parent().map(ProcessHandle::pid).orElse(-1L), commandLine(p))));
        return details;
    }

    private static String commandLine(ProcessHandle process) {
        try {
            // Linux: the arguments exactly as started, NUL-separated.
            byte[] raw = Files.readAllBytes(Paths.get("/proc", Long.toString(process.pid()), "cmdline"));
            return new String(raw, StandardCharsets.UTF_8).replace('\0', ' ').trim();
        } catch (IOException | RuntimeException noProc) {
            return process.info().commandLine().orElse("(unknown)");
        }
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
