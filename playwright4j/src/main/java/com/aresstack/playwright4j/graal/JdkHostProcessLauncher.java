package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class JdkHostProcessLauncher implements HostProcessLauncher {

    private static final String ARGUMENT_SEPARATOR = "";
    private static final String REMOTE_DEBUGGING_PIPE = "--remote-debugging-pipe";
    private static final String REMOTE_DEBUGGING_PORT = "--remote-debugging-port=0";
    private static final long DEVTOOLS_PORT_TIMEOUT_SECONDS = 30L;
    private static final java.util.regex.Pattern DEVTOOLS_STDERR_PATTERN =
            java.util.regex.Pattern.compile("DevTools listening on (ws://\\S+)");
    private static final int STDERR_RING_BUFFER_LINES = 80;

    private final Map<String, Process> processes = new ConcurrentHashMap<String, Process>();

    @Override
    @HostAccess.Export
    public String launchChromium(String command, String arguments, String workingDirectory) {
        List<String> browserArguments = browserArguments(arguments);
        Path userDataDirectory = userDataDirectory(browserArguments);

        List<String> commandLine = new ArrayList<String>();
        commandLine.add(command);
        commandLine.addAll(browserArguments);
        Playwright4JDebug.log("[pw4j-launcher] command=" + commandLine);
        Playwright4JDebug.log("[pw4j-launcher] userDataDir=" + userDataDirectory);

        OutputRingBuffer stderrBuffer = new OutputRingBuffer(STDERR_RING_BUFFER_LINES);
        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
            if (workingDirectory != null && !workingDirectory.trim().isEmpty()) {
                processBuilder.directory(new File(workingDirectory));
            }
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            processBuilder.redirectErrorStream(false);
            process = processBuilder.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start Chromium process: " + commandLine, exception);
        }

        // Always consume stderr so Chromium never blocks on a full output pipe.
        startOutputGobbler(process.getErrorStream(), stderrBuffer);

        String processId = UUID.randomUUID().toString();
        processes.put(processId, process);

        String endpoint = readWebSocketEndpoint(commandLine, userDataDirectory, process, stderrBuffer);
        Playwright4JDebug.log("[pw4j-launcher] endpoint=" + endpoint);
        return processId + ARGUMENT_SEPARATOR + endpoint;
    }

    @Override
    @HostAccess.Export
    public void close(String processId) {
        Process process = processes.remove(processId);
        if (process != null) {
            // Destroy the whole tree: Chromium spawns renderer/gpu child processes that must
            // be terminated too, otherwise they leak (Process.destroy only targets the parent).
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    public void closeAll() {
        for (String processId : new ArrayList<String>(processes.keySet())) {
            close(processId);
        }
    }

    private List<String> browserArguments(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Collections.singletonList(REMOTE_DEBUGGING_PORT);
        }

        String[] rawArguments = arguments.split(ARGUMENT_SEPARATOR, -1);
        // A caller may pass an explicit --remote-debugging-port (e.g. to connectOverCDP on a
        // known port). Respect it: drop the pipe and never add a competing port=0 flag.
        boolean callerSetPort = containsRemoteDebuggingPort(java.util.Arrays.asList(rawArguments));
        List<String> result = new ArrayList<String>();

        for (String argument : rawArguments) {
            if (REMOTE_DEBUGGING_PIPE.equals(argument)) {
                // Chromium speaks CDP over the chosen port + DevToolsActivePort, not the pipe.
                if (!callerSetPort) {
                    result.add(REMOTE_DEBUGGING_PORT);
                }
                continue;
            }
            result.add(argument);
        }

        if (!containsRemoteDebuggingPort(result)) {
            result.add(REMOTE_DEBUGGING_PORT);
        }

        return result;
    }

    private boolean containsRemoteDebuggingPort(List<String> arguments) {
        for (String argument : arguments) {
            if (argument.startsWith("--remote-debugging-port")) {
                return true;
            }
        }
        return false;
    }

    private Path userDataDirectory(List<String> arguments) {
        for (String argument : arguments) {
            if (argument.startsWith("--user-data-dir=")) {
                return Paths.get(argument.substring("--user-data-dir=".length()));
            }
        }

        try {
            return Files.createTempDirectory("playwright4j-chromium-");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create Chromium profile directory.", exception);
        }
    }

    private String readWebSocketEndpoint(List<String> commandLine, Path userDataDirectory, Process process, OutputRingBuffer stderrBuffer) {
        Path activePortFile = userDataDirectory.resolve("DevToolsActivePort");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEVTOOLS_PORT_TIMEOUT_SECONDS);

        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        "Chromium exited (code " + process.exitValue() + ") before the DevTools endpoint became available."
                                + describeFailure(commandLine, userDataDirectory, activePortFile, stderrBuffer));
            }

            // Auto-assigned ports (port=0) are reported via DevToolsActivePort; an explicit
            // --remote-debugging-port=<n> is only announced on Chromium's stderr. Accept either.
            String endpoint = tryReadEndpoint(activePortFile);
            if (endpoint == null) {
                endpoint = tryReadEndpointFromStderr(stderrBuffer);
            }
            if (endpoint != null) {
                return endpoint;
            }

            sleepBriefly();
        }

        process.destroy();
        throw new IllegalStateException(
                "Timed out after " + DEVTOOLS_PORT_TIMEOUT_SECONDS + "s waiting for the Chromium DevTools endpoint."
                        + describeFailure(commandLine, userDataDirectory, activePortFile, stderrBuffer));
    }

    private String tryReadEndpointFromStderr(OutputRingBuffer stderrBuffer) {
        java.util.regex.Matcher matcher = DEVTOOLS_STDERR_PATTERN.matcher(stderrBuffer.snapshot());
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String tryReadEndpoint(Path activePortFile) {
        if (!Files.isRegularFile(activePortFile)) {
            return null;
        }

        try {
            List<String> lines = Files.readAllLines(activePortFile, StandardCharsets.UTF_8);
            if (lines.size() >= 2 && !lines.get(0).trim().isEmpty()) {
                return "ws://127.0.0.1:" + lines.get(0).trim() + lines.get(1).trim();
            }
        } catch (IOException exception) {
            // The file may be mid-write; retry on the next iteration.
        }

        return null;
    }

    private String describeFailure(List<String> commandLine, Path userDataDirectory, Path activePortFile, OutputRingBuffer stderrBuffer) {
        StringBuilder builder = new StringBuilder();
        builder.append("\n  command: ").append(commandLine);
        builder.append("\n  userDataDir: ").append(userDataDirectory);
        builder.append("\n  activePortFile: ").append(activePortFile);
        String recentStderr = stderrBuffer.snapshot();
        if (!recentStderr.isEmpty()) {
            builder.append("\n  recent stderr:\n").append(recentStderr);
        }
        return builder.toString();
    }

    private void startOutputGobbler(InputStream stream, OutputRingBuffer ringBuffer) {
        Thread gobbler = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        ringBuffer.add(line);
                    }
                } catch (IOException exception) {
                    // Process ended or stream closed; nothing to do.
                }
            }
        }, "playwright4j-chromium-stderr");
        gobbler.setDaemon(true);
        gobbler.start();
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(25L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Chromium process.", exception);
        }
    }

    private static final class OutputRingBuffer {

        private final int maxLines;
        private final Deque<String> lines = new ArrayDeque<String>();

        private OutputRingBuffer(int maxLines) {
            this.maxLines = maxLines;
        }

        synchronized void add(String line) {
            if (lines.size() >= maxLines) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }

        synchronized String snapshot() {
            StringBuilder builder = new StringBuilder();
            for (String line : lines) {
                builder.append("    ").append(line).append('\n');
            }
            return builder.toString();
        }
    }
}
