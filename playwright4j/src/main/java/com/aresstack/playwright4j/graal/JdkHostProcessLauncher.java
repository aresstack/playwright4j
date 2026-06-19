package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class JdkHostProcessLauncher implements HostProcessLauncher {

    private static final String ARGUMENT_SEPARATOR = "\u001e";
    private static final String REMOTE_DEBUGGING_PIPE = "--remote-debugging-pipe";
    private static final String REMOTE_DEBUGGING_PORT = "--remote-debugging-port=0";

    private final Map<String, Process> processes = new ConcurrentHashMap<String, Process>();

    @Override
    @HostAccess.Export
    public String launchChromium(String command, String arguments, String workingDirectory) {
        try {
            List<String> browserArguments = browserArguments(arguments);
            Path userDataDirectory = userDataDirectory(browserArguments);
            System.err.println("[pw4j-launcher] command=" + command);
            System.err.println("[pw4j-launcher] userDataDir=" + userDataDirectory);
            Process process = startProcess(command, browserArguments, workingDirectory);
            String processId = UUID.randomUUID().toString();
            processes.put(processId, process);
            return processId + ARGUMENT_SEPARATOR + readWebSocketEndpoint(userDataDirectory, process);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot launch Chromium process.", exception);
        }
    }

    @Override
    @HostAccess.Export
    public void close(String processId) {
        Process process = processes.remove(processId);
        if (process != null) {
            process.destroy();
        }
    }

    public void closeAll() {
        for (String processId : new ArrayList<String>(processes.keySet())) {
            close(processId);
        }
    }

    private Process startProcess(String command, List<String> browserArguments, String workingDirectory) throws IOException {
        List<String> commandLine = new ArrayList<String>();
        commandLine.add(command);
        commandLine.addAll(browserArguments);

        ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
        if (workingDirectory != null && !workingDirectory.trim().isEmpty()) {
            processBuilder.directory(new File(workingDirectory));
        }
        return processBuilder.start();
    }

    private List<String> browserArguments(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Collections.singletonList(REMOTE_DEBUGGING_PORT);
        }

        String[] rawArguments = arguments.split(ARGUMENT_SEPARATOR, -1);
        List<String> result = new ArrayList<String>();
        boolean replacedRemoteDebuggingPipe = false;

        for (String argument : rawArguments) {
            if (REMOTE_DEBUGGING_PIPE.equals(argument)) {
                result.add(REMOTE_DEBUGGING_PORT);
                replacedRemoteDebuggingPipe = true;
                continue;
            }
            result.add(argument);
        }

        if (!replacedRemoteDebuggingPipe) {
            result.add(REMOTE_DEBUGGING_PORT);
        }

        return result;
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

    private String readWebSocketEndpoint(Path userDataDirectory, Process process) throws IOException {
        Path activePortFile = userDataDirectory.resolve("DevToolsActivePort");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);

        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("Chromium process exited before DevTools endpoint became available.");
            }

            if (Files.isRegularFile(activePortFile)) {
                List<String> lines = Files.readAllLines(activePortFile, StandardCharsets.UTF_8);
                if (lines.size() >= 2) {
                    String endpoint = "ws://127.0.0.1:" + lines.get(0).trim() + lines.get(1).trim();
                    System.err.println("[pw4j-launcher] endpoint=" + endpoint);
                    return endpoint;
                }
            }

            sleepBriefly();
        }

        System.err.println("[pw4j-launcher] timeout activePortFile=" + activePortFile);
        throw new IllegalStateException("Timed out waiting for Chromium DevTools endpoint at " + activePortFile);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(25L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Chromium process.", exception);
        }
    }
}
