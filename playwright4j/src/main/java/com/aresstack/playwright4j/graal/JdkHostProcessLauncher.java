package com.aresstack.playwright4j.graal;

import com.aresstack.playwright4j.video.VideoBackends;
import com.aresstack.playwright4j.video.VideoEncoderBackend;
import com.aresstack.playwright4j.video.VideoEncoderHandle;
import com.aresstack.playwright4j.video.VideoEncoderRequest;
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
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class JdkHostProcessLauncher implements HostProcessLauncher {

    private static final String ARGUMENT_SEPARATOR = "";
    private static final String REMOTE_DEBUGGING_PIPE = "--remote-debugging-pipe";
    private static final String REMOTE_DEBUGGING_PORT = "--remote-debugging-port=0";
    // Playwright's default launch timeout; a cold first start on a CI runner can take longer than 30 s.
    private static final long DEVTOOLS_PORT_TIMEOUT_SECONDS = 180L;
    private static final java.util.regex.Pattern DEVTOOLS_STDERR_PATTERN =
            java.util.regex.Pattern.compile("DevTools listening on (ws://\\S+)");
    private static final int STDERR_RING_BUFFER_LINES = 80;
    /** How long {@link #close} waits after the polite terminate before it kills forcibly. */
    static final long DEFAULT_CLOSE_GRACE_MILLIS = 5000L;

    private final Map<String, Process> processes = new ConcurrentHashMap<String, Process>();
    private final Map<String, Instant> startTimes = new ConcurrentHashMap<String, Instant>();
    private final Map<String, java.io.OutputStream> processStdin = new ConcurrentHashMap<String, java.io.OutputStream>();
    private final Map<String, VideoEncoderHandle> videoEncoders = new ConcurrentHashMap<String, VideoEncoderHandle>();
    private final java.util.concurrent.BlockingQueue<String> processEvents =
            new java.util.concurrent.LinkedBlockingQueue<String>();
    private static final char UNIT = '';
    private final long closeGraceMillis;

    public JdkHostProcessLauncher() {
        this(DEFAULT_CLOSE_GRACE_MILLIS);
    }

    JdkHostProcessLauncher(long closeGraceMillis) {
        this.closeGraceMillis = closeGraceMillis;
    }

    @Override
    @HostAccess.Export
    public String spawnProcess(String command, String arguments, String workingDirectory) {
        return spawnProcessWithEnvironment(command, arguments, workingDirectory, null);
    }

    @Override
    @HostAccess.Export
    public String spawnProcessWithEnvironment(String command, String arguments, String workingDirectory, String environment) {
        List<String> argumentList = new ArrayList<String>();
        if (arguments != null && !arguments.isEmpty()) {
            for (String argument : arguments.split(ARGUMENT_SEPARATOR, -1)) {
                argumentList.add(argument);
            }
        }

        // The ffmpeg spawn (video recording) is delegated to a Java video-encoding backend
        // (JCodec by default) instead of running a native encoder. The original Playwright JS is
        // unchanged; from its point of view a process was spawned, fed MJPEG on stdin, and exits 0.
        if (isFfmpeg(command)) {
            return startVideoEncoder(argumentList);
        }

        List<String> commandLine = new ArrayList<String>();
        commandLine.add(command);
        commandLine.addAll(argumentList);
        Playwright4JDebug.log("[pw4j-launcher] spawn=" + commandLine);
        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
            if (workingDirectory != null && !workingDirectory.trim().isEmpty()) {
                processBuilder.directory(new File(workingDirectory));
            }
            applyEnvironment(processBuilder, environment);
            process = processBuilder.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot spawn process: " + commandLine, exception);
        }

        final String processId = UUID.randomUUID().toString();
        processes.put(processId, process);
        process.toHandle().info().startInstant().ifPresent(start -> startTimes.put(processId, start));
        processStdin.put(processId, process.getOutputStream());
        startProcessReader(processId, process.getInputStream(), "stdout");
        startProcessReader(processId, process.getErrorStream(), "stderr");
        startExitWatcher(processId, process, null);
        return processId;
    }

    /**
     * Gives the child exactly the given environment (Playwright/Node semantics: {@code spawn}'s
     * {@code env} replaces the inherited one), or leaves it inherited for {@code null}. Values are
     * never logged: they may carry secrets (e.g. a Camoufox fingerprint in CAMOU_CONFIG_*).
     */
    static void applyEnvironment(ProcessBuilder processBuilder, String environment) {
        if (environment == null) {
            return;
        }
        Map<String, String> childEnvironment = processBuilder.environment();
        childEnvironment.clear();
        if (environment.isEmpty()) {
            return;
        }
        for (String entry : environment.split(ARGUMENT_SEPARATOR, -1)) {
            int equals = entry.indexOf('=', 1);
            if (equals > 0) {
                try {
                    childEnvironment.put(entry.substring(0, equals), entry.substring(equals + 1));
                } catch (IllegalArgumentException invalidOnThisPlatform) {
                    // e.g. a NUL character, or a Windows "=C:" drive variable on a POSIX host.
                }
            }
        }
        Playwright4JDebug.log("[pw4j-launcher] environment=" + childEnvironment.size() + " variables (values not logged)");
    }

    private static boolean isFfmpeg(String command) {
        if (command == null) {
            return false;
        }
        String normalized = command.toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return basename.contains("ffmpeg");
    }

    private String startVideoEncoder(List<String> argumentList) {
        final String processId = UUID.randomUUID().toString();
        VideoEncoderRequest request = VideoEncoderRequest.fromFfmpegArguments(argumentList);
        VideoEncoderBackend backend = VideoBackends.select();
        Playwright4JDebug.log("[pw4j-launcher] video-encoder backend=" + backend.getClass().getSimpleName()
                + " out=" + request.outputPath() + " " + request.width() + "x" + request.height() + "@" + request.fps());
        VideoEncoderHandle handle = backend.start(request, new Runnable() {
            @Override
            public void run() {
                videoEncoders.remove(processId);
                processEvents.add("exit" + UNIT + processId + UNIT + "0");
            }
        });
        videoEncoders.put(processId, handle);
        return processId;
    }

    @Override
    @HostAccess.Export
    public void writeStdin(String processId, String base64) {
        VideoEncoderHandle encoder = videoEncoders.get(processId);
        if (encoder != null) {
            encoder.write(java.util.Base64.getDecoder().decode(base64));
            return;
        }
        java.io.OutputStream stdin = processStdin.get(processId);
        if (stdin == null) {
            return;
        }
        try {
            stdin.write(java.util.Base64.getDecoder().decode(base64));
            stdin.flush();
        } catch (IOException exception) {
            // Process ended or pipe broke; ignore (exit event will follow).
        }
    }

    @Override
    @HostAccess.Export
    public void endStdin(String processId) {
        VideoEncoderHandle encoder = videoEncoders.get(processId);
        if (encoder != null) {
            encoder.finish();
            return;
        }
        java.io.OutputStream stdin = processStdin.remove(processId);
        if (stdin != null) {
            try {
                stdin.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    @Override
    @HostAccess.Export
    public String drainProcessEvents() {
        if (processEvents.isEmpty()) {
            return "";
        }
        StringBuilder batch = new StringBuilder();
        String event;
        while ((event = processEvents.poll()) != null) {
            if (batch.length() > 0) {
                batch.append('\n');
            }
            batch.append(event);
        }
        return batch.toString();
    }

    private void startProcessReader(final String processId, final InputStream stream, final String channel) {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[16384];
                try {
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (count == 0) {
                            continue;
                        }
                        String base64 = java.util.Base64.getEncoder()
                                .encodeToString(java.util.Arrays.copyOf(buffer, count));
                        processEvents.add("data" + UNIT + processId + UNIT + channel + UNIT + base64);
                    }
                } catch (IOException ignored) {
                    // Stream closed.
                } finally {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                        // Nothing to do.
                    }
                }
            }
        }, "playwright4j-proc-" + channel + "-" + processId);
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Reports the process's real exit to the JS side and forgets the process. A profile directory
     * the launcher created itself ({@code ownedProfile}) is removed once the browser is gone.
     */
    private void startExitWatcher(final String processId, final Process process, final Path ownedProfile) {
        Thread watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                int code;
                try {
                    code = process.waitFor();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    code = -1;
                }
                processStdin.remove(processId);
                processes.remove(processId);
                startTimes.remove(processId);
                if (ownedProfile != null) {
                    deleteDirectory(ownedProfile);
                }
                processEvents.add("exit" + UNIT + processId + UNIT + code);
            }
        }, "playwright4j-proc-exit-" + processId);
        watcher.setDaemon(true);
        watcher.start();
    }

    @Override
    @HostAccess.Export
    public String launchChromium(String command, String arguments, String workingDirectory) {
        return launchChromiumWithEnvironment(command, arguments, workingDirectory, null);
    }

    @Override
    @HostAccess.Export
    public String launchChromiumWithEnvironment(String command, String arguments, String workingDirectory, String environment) {
        List<String> browserArguments = browserArguments(arguments);
        Path callerUserDataDirectory = callerUserDataDirectory(browserArguments);
        Path ownedProfile = callerUserDataDirectory == null ? createProfileDirectory() : null;
        Path userDataDirectory = callerUserDataDirectory != null ? callerUserDataDirectory : ownedProfile;

        List<String> commandLine = new ArrayList<String>();
        commandLine.add(command);
        commandLine.addAll(browserArguments);
        Playwright4JDebug.log("[pw4j-launcher] command=" + commandLine);
        Playwright4JDebug.log("[pw4j-launcher] userDataDir=" + userDataDirectory);

        // A persistent context reuses the same --user-data-dir across launches. Chromium does not
        // always remove its DevToolsActivePort on exit, so a stale file from a previous launch can
        // still be present here. Remove it before starting, otherwise readWebSocketEndpoint may read
        // the previous run's (now dead) port/GUID before the new Chromium rewrites the file, leaving
        // us connected to a defunct endpoint. For normal launches (fresh temp dir) this is a no-op.
        deleteStaleDevToolsActivePort(userDataDirectory);

        OutputRingBuffer stderrBuffer = new OutputRingBuffer(STDERR_RING_BUFFER_LINES);
        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
            if (workingDirectory != null && !workingDirectory.trim().isEmpty()) {
                processBuilder.directory(new File(workingDirectory));
            }
            applyEnvironment(processBuilder, environment);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            processBuilder.redirectErrorStream(false);
            process = processBuilder.start();
        } catch (IOException exception) {
            if (ownedProfile != null) {
                deleteDirectory(ownedProfile);
            }
            throw new IllegalStateException("Cannot start Chromium process: " + commandLine, exception);
        }

        // Always consume stderr so Chromium never blocks on a full output pipe.
        startOutputGobbler(process.getErrorStream(), stderrBuffer);

        String processId = UUID.randomUUID().toString();
        processes.put(processId, process);
        process.toHandle().info().startInstant().ifPresent(start -> startTimes.put(processId, start));
        startExitWatcher(processId, process, ownedProfile);

        String endpoint;
        try {
            endpoint = readWebSocketEndpoint(commandLine, userDataDirectory, process, stderrBuffer);
        } catch (RuntimeException failure) {
            // No endpoint, no Playwright-side handle: nobody else would ever end this browser.
            kill(processId);
            throw failure;
        }
        Playwright4JDebug.log("[pw4j-launcher] endpoint=" + endpoint);
        return processId + ARGUMENT_SEPARATOR + endpoint;
    }

    @Override
    @HostAccess.Export
    public long pid(String processId) {
        Process process = processes.get(processId);
        return process == null ? -1L : process.pid();
    }

    /**
     * Node's {@code child.kill()}: a polite terminate first (SIGTERM on POSIX; on Windows the JDK
     * has no polite variant), then, off the JS thread, a forced kill of the process and every
     * descendant still alive after the grace period. The descendants are captured before the
     * terminate because a dead parent no longer reports its children.
     */
    @Override
    @HostAccess.Export
    public void close(String processId) {
        VideoEncoderHandle encoder = videoEncoders.remove(processId);
        if (encoder != null) {
            encoder.kill();
        }
        final Process process = processes.get(processId);
        if (process == null) {
            return;
        }
        final Instant start = startTimes.get(processId);
        final List<ProcessHandle> descendants = descendantsOf(process, start);
        process.destroy();
        Thread reaper = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    process.waitFor(closeGraceMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                destroyForcibly(process, start, descendants);
            }
        }, "playwright4j-proc-close-" + processId);
        reaper.setDaemon(true);
        reaper.start();
    }

    /**
     * Playwright's force kill: the whole tree at once. Chromium and Firefox run renderer/content
     * processes that must go too, otherwise they leak (Process.destroy only targets the parent).
     * The exit watcher reports the exit and forgets the process.
     */
    @Override
    @HostAccess.Export
    public void kill(String processId) {
        VideoEncoderHandle encoder = videoEncoders.remove(processId);
        if (encoder != null) {
            encoder.kill();
        }
        Process process = processes.get(processId);
        if (process != null) {
            Playwright4JDebug.log("[pw4j-launcher] force kill pid=" + process.pid());
            Instant start = startTimes.get(processId);
            destroyForcibly(process, start, descendantsOf(process, start));
        }
    }

    /**
     * The process tree below {@code process}. Not {@link Process#descendants()}: once the parent has
     * exited, the JDK no longer knows its start time and returns every process whose recorded parent
     * id merely equals the old pid. On Windows that id outlives the parent and pids are reused, so
     * unrelated processes (the CI runner itself) would be killed. A child counts only if it started
     * after its parent; without the parent's start time nothing is collected.
     */
    private static List<ProcessHandle> descendantsOf(Process process, Instant start) {
        if (start == null) {
            return Collections.emptyList();
        }
        List<ProcessEntry> all = new ArrayList<ProcessEntry>();
        try {
            ProcessHandle.allProcesses().forEach(handle -> {
                Instant started = handle.info().startInstant().orElse(null);
                Long parent = handle.parent().map(ProcessHandle::pid).orElse(null);
                if (started != null && parent != null) {
                    all.add(new ProcessEntry(handle, handle.pid(), parent, started));
                }
            });
        } catch (RuntimeException ignored) {
            // The process table is not readable: nothing more to collect.
        }
        List<ProcessHandle> descendants = new ArrayList<ProcessHandle>();
        for (ProcessEntry entry : descendantsAmong(process.pid(), start, all)) {
            descendants.add(entry.handle);
        }
        return descendants;
    }

    static List<ProcessEntry> descendantsAmong(long pid, Instant start, List<ProcessEntry> all) {
        Map<Long, List<ProcessEntry>> byParent = new HashMap<Long, List<ProcessEntry>>();
        for (ProcessEntry entry : all) {
            byParent.computeIfAbsent(entry.parentPid, key -> new ArrayList<ProcessEntry>()).add(entry);
        }
        List<ProcessEntry> descendants = new ArrayList<ProcessEntry>();
        collectChildren(pid, start, byParent, descendants);
        return descendants;
    }

    private static void collectChildren(long pid, Instant start, Map<Long, List<ProcessEntry>> byParent,
            List<ProcessEntry> descendants) {
        for (ProcessEntry child : byParent.getOrDefault(pid, Collections.<ProcessEntry>emptyList())) {
            if (!child.start.isBefore(start) && !descendants.contains(child)) {
                descendants.add(child);
                collectChildren(child.pid, child.start, byParent, descendants);
            }
        }
    }

    static final class ProcessEntry {
        final ProcessHandle handle;
        final long pid;
        final long parentPid;
        final Instant start;

        ProcessEntry(ProcessHandle handle, long pid, long parentPid, Instant start) {
            this.handle = handle;
            this.pid = pid;
            this.parentPid = parentPid;
            this.start = start;
        }
    }

    private static void destroyForcibly(Process process, Instant start, List<ProcessHandle> capturedDescendants) {
        // Captured children first, then any born since; the parent last.
        for (ProcessHandle descendant : capturedDescendants) {
            descendant.destroyForcibly();
        }
        for (ProcessHandle descendant : descendantsOf(process, start)) {
            descendant.destroyForcibly();
        }
        process.destroyForcibly();
    }

    public void closeAll() {
        for (String processId : new ArrayList<String>(videoEncoders.keySet())) {
            VideoEncoderHandle encoder = videoEncoders.remove(processId);
            if (encoder != null) {
                encoder.kill();
            }
        }
        for (String processId : new ArrayList<String>(processes.keySet())) {
            kill(processId);
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
            result.add(unwrapQuotedFlagValue(argument));
        }

        if (!containsRemoteDebuggingPort(result)) {
            result.add(REMOTE_DEBUGGING_PORT);
        }

        return result;
    }

    /**
     * Playwright wraps some flag values in literal double quotes to protect embedded spaces from a
     * shell, e.g. {@code --host-resolver-rules="MAP * ~NOTFOUND , EXCLUDE 127.0.0.1"} (added for a
     * SOCKS proxy, as with BrowserContext client certificates). Node's Windows argument quoting
     * copes with such embedded quotes, but Java's ProcessBuilder mangles an argv element that
     * contains both spaces and quotes, so Chromium ends up seeing the value split into extra
     * positional arguments and aborts with "Multiple targets are not supported". Strip the wrapping
     * quotes so the value travels as a single argv element; ProcessBuilder then quotes the whole
     * argument itself.
     */
    private static String unwrapQuotedFlagValue(String argument) {
        int quoteStart = argument.indexOf("=\"");
        if (quoteStart >= 0 && argument.endsWith("\"") && argument.length() > quoteStart + 2) {
            return argument.substring(0, quoteStart + 1)
                    + argument.substring(quoteStart + 2, argument.length() - 1);
        }
        return argument;
    }

    private boolean containsRemoteDebuggingPort(List<String> arguments) {
        for (String argument : arguments) {
            if (argument.startsWith("--remote-debugging-port")) {
                return true;
            }
        }
        return false;
    }

    private static Path callerUserDataDirectory(List<String> arguments) {
        for (String argument : arguments) {
            if (argument.startsWith("--user-data-dir=")) {
                return Paths.get(argument.substring("--user-data-dir=".length()));
            }
        }
        return null;
    }

    private static Path createProfileDirectory() {
        try {
            return Files.createTempDirectory("playwright4j-chromium-");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create Chromium profile directory.", exception);
        }
    }

    /** Removes a directory tree; retried briefly because Windows may still hold files of a just-ended process. */
    static void deleteDirectory(Path directory) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
                List<Path> paths = new ArrayList<Path>();
                walk.forEach(paths::add);
                Collections.reverse(paths);
                for (Path path : paths) {
                    Files.deleteIfExists(path);
                }
                return;
            } catch (java.nio.file.NoSuchFileException gone) {
                return;
            } catch (IOException | java.io.UncheckedIOException retry) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        Playwright4JDebug.log("[pw4j-launcher] could not remove profile " + directory);
    }

    private void deleteStaleDevToolsActivePort(Path userDataDirectory) {
        if (userDataDirectory == null) {
            return;
        }
        try {
            Files.deleteIfExists(userDataDirectory.resolve("DevToolsActivePort"));
        } catch (IOException ignored) {
            // Best effort: if we cannot remove it, readWebSocketEndpoint still falls back to stderr.
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
