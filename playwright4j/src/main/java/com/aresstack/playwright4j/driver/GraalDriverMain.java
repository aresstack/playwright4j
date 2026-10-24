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
        // java.net.http.HttpClient always performs TLS hostname verification and ignores
        // SSLParameters.setEndpointIdentificationAlgorithm(null); the only switch is this property,
        // which must be set before the HttpClient class initialises. It lets ignoreHTTPSErrors /
        // rejectUnauthorized:false reach servers whose cert SAN does not match the address (e.g. a
        // client-certificate test server reached via 127.0.0.1 while its cert is for localhost).
        // Certificate-chain trust is still governed per-client by the TLS options (trust-all only
        // when rejectUnauthorized is false); this disables the hostname check only.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", Boolean.TRUE.toString());

        Map<String, String> environment = new LinkedHashMap<String, String>(System.getenv());
        environment.putIfAbsent("PW_LANG_NAME", "java");
        // Playwright-Core builds its default User-Agent as "... <PW_LANG_NAME>/<PW_LANG_NAME_VERSION>".
        // Upstream Playwright-Java sets these on the driver process so the UA carries "java/<major>";
        // mirror that here (the value is read via process.env in the bundled core).
        environment.putIfAbsent("PW_LANG_NAME_VERSION", majorJavaVersion());

        PlaywrightDriverBundleSource driverBundleSource = new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader());
        JdkHostProcessLauncher processLauncher = new JdkHostProcessLauncher();

        // Guarantee the browser process tree is torn down whenever this driver JVM shuts down for
        // any orderly reason (stdin EOF, System.exit, parent closing the pipe). Without this a
        // Chromium tree could be orphaned if the parent abandons a hung command and closes the
        // pipe before the normal closeAll() in the finally block below runs.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                processLauncher.closeAll();
            }
        }, "playwright4j-driver-shutdown"));
        // --node-compat runs the bundled cli.js as a Node CLI (e.g. `cli.js launch-server ...`),
        // spawned by tests that invoke <driverDir>/node.exe directly. There is no length-prefixed
        // protocol on stdout here; process.stdout is the caller's real stdout (the ws:// endpoint).
        boolean nodeCompat = args.length > 0 && "--node-compat".equals(args[0]);

        if (nodeCompat) {
            installNodeCompatTerminationHandler(processLauncher);
        }

        HostDriverPipe driverPipe = nodeCompat
                ? new RawIoDriverPipe(System.out, System.err)
                : new StandardIoDriverPipe(System.out, System.err);
        Playwright4JHost host = new Playwright4JHost(
                new FixedHostEnvironment(platform(), architecture(), Paths.get("").toAbsolutePath().toString(), environment),
                new LocalHostFileSystem(),
                new JdkHostHttpClient(),
                new JdkHostWebSocketClient(),
                driverPipe,
                processLauncher,
                new RecordingMissingHostFunctionReporter());

        // Node.js terminates on an unhandled rejection; here they are reported on stderr (default),
        // or terminate the driver like Node with -Dplaywright4j.unhandledRejections=strict (CI).
        UnhandledRejections unhandledRejections = new UnhandledRejections(
                UnhandledRejections.Mode.fromSystemProperty(UnhandledRejections.Mode.WARN), System.err);
        try (GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(host, driverBundleSource, unhandledRejections)) {
            runtime.loadNodeCompatibilityLayer();
            if (nodeCompat) {
                runNodeCompat(runtime, driverBundleSource, args);
            } else {
                runtime.setProcessArguments(processArguments(driverBundleSource, args));
                runtime.evaluateCommonJsEntry(driverBundleSource.cliScriptResourceName(), driverBundleSource.readCliScript());
                pumpInput(runtime);
            }
        } finally {
            processLauncher.closeAll();
        }

        // The client closed our stdin: terminate promptly so the parent process is not kept
        // alive by lingering non-daemon threads (e.g. java.net.http selector threads).
        System.out.flush();
        System.err.flush();
        System.exit(unhandledRejections.terminationRequested() ? 1 : 0);
    }

    /**
     * Runs the bundled cli.js as a Node CLI. process.argv is {@code [node, <cli.js path>, <args...>]};
     * the bundled cli script is evaluated (its require() resolution uses the packaged resources). The
     * pump keeps the event loop alive so a long-running command (launch-server's ws server, browser
     * CDP, timers) is serviced until the process is killed or the guest calls process.exit.
     */
    private static void runNodeCompat(GraalPlaywrightRuntime runtime, PlaywrightDriverBundleSource driverBundleSource, String[] args) {
        runtime.evaluate("playwright4j-node-compat.js", "globalThis.__playwright4jNodeCompat = true;");
        // An unhandled rejection before the command produced any stdout (launch-server: before the
        // ws:// endpoint) means the command failed: exit 1, as Node.js would. Later rejections are
        // only recorded and reported (see UnhandledRejections), so a running server is not torn
        // down by a stray one - except in strict mode, which terminates like Node.js.
        runtime.evaluate("playwright4j-node-compat-rejections.js", ""
                + "(function () {\n"
                + "  let produced = false;\n"
                + "  const write = process.stdout.write;\n"
                + "  process.stdout.write = function () { produced = true; return write.apply(this, arguments); };\n"
                + "  ['log', 'info'].forEach(function (name) {\n"
                + "    const original = console[name];\n"
                + "    console[name] = function () { produced = true; return original.apply(this, arguments); };\n"
                + "  });\n"
                + "  Graal.setUnhandledPromiseRejectionHandler(function (reason) {\n"
                + "    __playwright4jUnhandledRejections.record(reason && reason.stack ? String(reason.stack) : String(reason));\n"
                + "    if (!produced || __playwright4jUnhandledRejections.terminationRequested()) {\n"
                + "      globalThis.__playwright4jExitCode = 1;\n"
                + "      globalThis.__playwright4jExitRequested = true;\n"
                + "    }\n"
                + "  });\n"
                + "})();");

        String[] nodeArgv = new String[args.length];
        nodeArgv[0] = "node";
        System.arraycopy(args, 1, nodeArgv, 1, args.length - 1);
        runtime.setProcessArguments(nodeArgv);

        runtime.evaluateCommonJsEntry(driverBundleSource.cliScriptResourceName(), driverBundleSource.readCliScript());

        while (true) {
            if (nodeCompatExitRequested(runtime) || runtime.unhandledRejections().terminationRequested()) {
                break;
            }
            int fired = runtime.runDueTimers();
            int drained = runtime.drainTransports();
            if (fired == 0 && drained == 0) {
                sleepQuietly(5L);
            }
        }

        System.out.flush();
        System.err.flush();
        // Strict mode also terminates on an uncaught exception in a timer or socket listener.
        System.exit(runtime.unhandledRejections().terminationRequested() ? 1 : nodeCompatExitCode(runtime));
    }

    /**
     * Like Node's launch-server (Playwright's processLauncher): on SIGTERM close the browsers and
     * exit 0, on SIGINT close them and exit 130. The JVM default would exit with 143/130 without
     * closing the browsers first. Callers that stop the server via Process.destroy() (e.g.
     * TestBrowserTypeConnect on Linux/macOS) thus see a clean exit. On Windows Process.destroy() is
     * TerminateProcess, which runs no handler; Ctrl+C in a console still arrives as INT.
     */
    private static void installNodeCompatTerminationHandler(final JdkHostProcessLauncher processLauncher) {
        installNodeCompatSignalHandler("TERM", 0, processLauncher);
        installNodeCompatSignalHandler("INT", 130, processLauncher);
    }

    private static void installNodeCompatSignalHandler(String name, final int exitCode, final JdkHostProcessLauncher processLauncher) {
        try {
            sun.misc.Signal.handle(new sun.misc.Signal(name), signal -> {
                try {
                    processLauncher.closeAll();
                } finally {
                    System.out.flush();
                    System.err.flush();
                    Runtime.getRuntime().halt(exitCode);
                }
            });
        } catch (IllegalArgumentException unsupported) {
            // Signal not supported by this JVM/platform; keep the JVM default.
        }
    }

    private static boolean nodeCompatExitRequested(GraalPlaywrightRuntime runtime) {
        org.graalvm.polyglot.Value value = runtime.readGlobal("__playwright4jExitRequested");
        return value != null && !value.isNull() && value.isBoolean() && value.asBoolean();
    }

    private static int nodeCompatExitCode(GraalPlaywrightRuntime runtime) {
        org.graalvm.polyglot.Value value = runtime.readGlobal("__playwright4jExitCode");
        return value != null && !value.isNull() && value.fitsInInt() ? value.asInt() : 0;
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
            if (runtime.unhandledRejections().terminationRequested()) {
                // Strict mode: terminate like Node.js; main() exits with code 1.
                return;
            }
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

    // Mirrors upstream Driver.getMajorJavaVersion(): "1.8.0_x" -> "8", "21.0.1" -> "21".
    private static String majorJavaVersion() {
        String version = System.getProperty("java.version", "");
        if (version.startsWith("1.")) {
            return version.substring(2, 3);
        }
        int dot = version.indexOf('.');
        if (dot != -1) {
            return version.substring(0, dot);
        }
        return version;
    }
}
