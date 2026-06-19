package com.aresstack.playwright4j.driver;

import com.aresstack.playwright4j.graal.*;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class GraalDriverMain {

    private GraalDriverMain() {
    }

    private static final long PUMP_HARD_DEADLINE_MILLIS = 60_000L;
    private static final long PUMP_QUIET_WINDOW_MILLIS = 40L;

    public static void main(String[] args) throws IOException {
        Map<String, String> environment = new LinkedHashMap<String, String>(System.getenv());
        environment.putIfAbsent("PW_LANG_NAME", "java");

        PlaywrightDriverBundleSource driverBundleSource = new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader());
        JdkHostProcessLauncher processLauncher = new JdkHostProcessLauncher();
        Playwright4JHost host = new Playwright4JHost(
                new FixedHostEnvironment(platform(), architecture(), Paths.get("").toAbsolutePath().toString(), environment),
                new LocalHostFileSystem(),
                new JdkHostHttpClient(),
                new JdkHostWebSocketClient(),
                new StandardIoDriverPipe(System.out, System.err),
                processLauncher,
                new RecordingMissingHostFunctionReporter());

        try (GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(host, driverBundleSource)) {
            runtime.loadNodeCompatibilityLayer();
            runtime.setProcessArguments(processArguments(driverBundleSource, args));
            runtime.evaluateCommonJsEntry(driverBundleSource.cliScriptResourceName(), driverBundleSource.readCliScript());
            pumpInput(runtime);
        } finally {
            processLauncher.closeAll();
        }

        // The client closed our stdin: terminate promptly so the parent process is not kept
        // alive by lingering non-daemon threads (e.g. java.net.http selector threads).
        System.out.flush();
        System.exit(0);
    }

    private static String[] processArguments(PlaywrightDriverBundleSource driverBundleSource, String[] args) {
        String[] processArguments = new String[args.length + 2];
        processArguments[0] = "node";
        processArguments[1] = driverBundleSource.cliScriptResourceName();
        System.arraycopy(args, 0, processArguments, 2, args.length);
        return processArguments;
    }

    private static void pumpInput(GraalPlaywrightRuntime runtime) throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(System.in));

        while (true) {
            String message;
            try {
                message = readLengthPrefixedMessage(input);
            } catch (EOFException exception) {
                return;
            }

            if (Playwright4JDebug.enabled()) {
                Playwright4JDebug.log("[pw4j-protocol] IN " + (message.length() <= 300 ? message : message.substring(0, 300) + "..."));
            }

            runtime.readGlobal("__playwright4jDriverPipeDeliver").execute(message);
            pumpUntilIdle(runtime);
        }
    }

    /**
     * Drives the single-threaded GraalJS event loop until the work triggered by the last
     * delivered protocol message has settled: it repeatedly fires due timers and drains the
     * browser CDP transports. This is what lets an asynchronous {@code launch()} (or any
     * command that waits on browser events) complete, instead of stalling because no further
     * input arrives. The loop is strictly bounded so it always terminates.
     */
    private static void pumpUntilIdle(GraalPlaywrightRuntime runtime) {
        long hardDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PUMP_HARD_DEADLINE_MILLIS);
        long quietDeadline = -1L;
        long totalFired = 0;
        long totalDrained = 0;
        long iterations = 0;

        while (true) {
            iterations++;
            int fired = runtime.runDueTimers();
            int drained = runtime.drainTransports();
            totalFired += fired;
            totalDrained += drained;

            if (fired > 0 || drained > 0) {
                quietDeadline = -1L;
                if (System.nanoTime() > hardDeadline) {
                    logPumpSummary("hard-deadline-active", iterations, totalFired, totalDrained);
                    return;
                }
                continue;
            }

            // Something is still waiting on a future timer (e.g. a Playwright progress
            // timeout). Keep ticking so that timer can fire and resolve or reject the work.
            if (runtime.hasPendingTimers()) {
                if (System.nanoTime() > hardDeadline) {
                    logPumpSummary("hard-deadline-pending-timers", iterations, totalFired, totalDrained);
                    return;
                }
                sleepQuietly(5L);
                continue;
            }

            // No browser activity and no scheduled timers: settle after a short quiet window.
            long now = System.nanoTime();
            if (quietDeadline < 0L) {
                quietDeadline = now + TimeUnit.MILLISECONDS.toNanos(PUMP_QUIET_WINDOW_MILLIS);
            }
            if (now >= quietDeadline || now > hardDeadline) {
                logPumpSummary("idle", iterations, totalFired, totalDrained);
                return;
            }
            sleepQuietly(2L);
        }
    }

    private static void logPumpSummary(String reason, long iterations, long totalFired, long totalDrained) {
        if (Playwright4JDebug.enabled()) {
            Playwright4JDebug.log("[pw4j-pump] exit reason=" + reason + " iterations=" + iterations
                    + " timersFired=" + totalFired + " messagesDrained=" + totalDrained);
        }
    }

    private static void sleepQuietly(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readLengthPrefixedMessage(DataInputStream input) throws IOException {
        int length = readIntLE(input);
        byte[] bytes = new byte[length];
        input.readFully(bytes, 0, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readIntLE(DataInputStream input) throws IOException {
        int ch1 = input.read();
        int ch2 = input.read();
        int ch3 = input.read();
        int ch4 = input.read();

        if ((ch1 | ch2 | ch3 | ch4) < 0) {
            throw new EOFException();
        }

        return (ch4 << 24) + (ch3 << 16) + (ch2 << 8) + ch1;
    }

    private static String platform() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            return "win32";
        }
        if (osName.contains("mac")) {
            return "darwin";
        }
        return "linux";
    }

    private static String architecture() {
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        return osArch.contains("aarch64") || osArch.contains("arm64") ? "arm64" : "x64";
    }
}
