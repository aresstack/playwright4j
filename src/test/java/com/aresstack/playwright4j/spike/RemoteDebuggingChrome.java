package com.aresstack.playwright4j.spike;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

final class RemoteDebuggingChrome implements AutoCloseable {

    private final Process process;
    private final Path userDataDirectory;
    private final int remoteDebuggingPort;

    private RemoteDebuggingChrome(Process process, Path userDataDirectory, int remoteDebuggingPort) {
        this.process = process;
        this.userDataDirectory = userDataDirectory;
        this.remoteDebuggingPort = remoteDebuggingPort;
    }

    static RemoteDebuggingChrome start(Path chromeExecutable) {
        int port = findFreePort();
        Path userDataDirectory = createUserDataDirectory();

        ProcessBuilder processBuilder = new ProcessBuilder(
                chromeExecutable.toString(),
                "--remote-debugging-address=127.0.0.1",
                "--remote-debugging-port=" + port,
                "--user-data-dir=" + userDataDirectory,
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-networking",
                "about:blank");

        try {
            Process process = processBuilder.start();
            RemoteDebuggingChrome chrome = new RemoteDebuggingChrome(process, userDataDirectory, port);
            chrome.awaitDevTools();
            return chrome;
        } catch (IOException exception) {
            deleteDirectoryQuietly(userDataDirectory);
            throw new IllegalStateException("Could not start installed Chrome: " + chromeExecutable, exception);
        }
    }

    String cdpEndpoint() {
        return "http://127.0.0.1:" + remoteDebuggingPort;
    }

    private void awaitDevTools() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
        URI versionUri = URI.create(cdpEndpoint() + "/json/version");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        RuntimeException lastFailure = null;

        while (System.nanoTime() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(versionUri)
                        .timeout(Duration.ofMillis(500))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200 && response.body().contains("webSocketDebuggerUrl")) {
                    return;
                }
            } catch (IOException exception) {
                lastFailure = new IllegalStateException("Chrome DevTools endpoint is not ready yet.", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Chrome DevTools endpoint.", exception);
            }

            sleepBriefly();
        }

        throw new IllegalStateException("Chrome DevTools endpoint did not become ready at " + versionUri, lastFailure);
    }

    private static int findFreePort() {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            return serverSocket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not allocate a free local debugging port.", exception);
        }
    }

    private static Path createUserDataDirectory() {
        try {
            return Files.createTempDirectory("playwright4j-chrome-");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create temporary Chrome user data directory.", exception);
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Chrome.", exception);
        }
    }

    @Override
    public void close() {
        process.destroy();

        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            deleteDirectoryQuietly(userDataDirectory);
        }
    }

    private static void deleteDirectoryQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }

        try {
            Files.walk(directory)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Ignore cleanup failures in spike tests.
                        }
                    });
        } catch (IOException ignored) {
            // Ignore cleanup failures in spike tests.
        }
    }
}
