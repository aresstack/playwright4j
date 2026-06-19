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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GraalDriverMain {

    private GraalDriverMain() {
    }

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

    private static void pumpInput(GraalPlaywrightRuntime runtime) {
        // A dedicated thread performs the blocking stdin reads so the (single) GraalJS thread
        // can keep draining the browser CDP transports between protocol messages. Without this,
        // asynchronous CDP events (console, lifecycle, dialogs, popups, ...) that arrive while
        // the Java client merely waits would never be delivered, and the client would hang.
        BlockingQueue<String> inbox = new LinkedBlockingQueue<String>();
        AtomicBoolean inputClosed = new AtomicBoolean(false);
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readMessagesInto(inbox, inputClosed);
            }
        }, "playwright4j-driver-stdin");
        reader.setDaemon(true);
        reader.start();

        // One uniform loop: deliver a pending protocol message if there is one, otherwise run a
        // single timer/transport tick. Crucially the inbox is checked every iteration, so an
        // incoming dispose/close can abort a still-pending asynchronous operation (e.g. an
        // in-flight fetch) promptly instead of waiting for it to settle.
        while (true) {
            String message = inbox.poll();
            if (message != null) {
                if (Playwright4JDebug.enabled()) {
                    Playwright4JDebug.log("[pw4j-protocol] IN " + (message.length() <= 300 ? message : message.substring(0, 300) + "..."));
                }
                runtime.readGlobal("__playwright4jDriverPipeDeliver").execute(message);
                continue;
            }

            // No protocol message pending: keep the event loop alive so asynchronous work
            // (CDP events, async HTTP responses, due timers) is delivered to Playwright.
            int fired = runtime.runDueTimers();
            int drained = runtime.drainTransports();
            if (fired == 0 && drained == 0) {
                if (inputClosed.get() && inbox.isEmpty()) {
                    return;
                }
                sleepQuietly(5L);
            }
        }
    }

    private static void readMessagesInto(BlockingQueue<String> inbox, AtomicBoolean inputClosed) {
        DataInputStream input = new DataInputStream(new BufferedInputStream(System.in));
        while (true) {
            try {
                inbox.put(readLengthPrefixedMessage(input));
            } catch (EOFException exception) {
                inputClosed.set(true);
                return;
            } catch (IOException exception) {
                inputClosed.set(true);
                return;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                inputClosed.set(true);
                return;
            }
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
