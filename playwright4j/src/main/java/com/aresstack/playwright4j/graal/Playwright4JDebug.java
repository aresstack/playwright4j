package com.aresstack.playwright4j.graal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Central gate for Playwright4J diagnostic output. Enable with the system property
 * {@code -Dplaywright4j.debug=true} or the environment variable {@code PLAYWRIGHT4J_DEBUG=true}.
 *
 * <p>When enabled, messages are written to {@code stderr} and appended to a log file
 * (default {@code ${java.io.tmpdir}/playwright4j-driver.log}, overridable with
 * {@code -Dplaywright4j.debug.file=...}). The driver runs as a child process whose stderr is
 * usually swallowed by the Playwright Java client, so the file is the reliable channel.
 */
public final class Playwright4JDebug {

    private static final boolean ENABLED =
            Boolean.getBoolean("playwright4j.debug")
                    || "true".equalsIgnoreCase(System.getenv("PLAYWRIGHT4J_DEBUG"));

    private static final Path LOG_FILE = resolveLogFile();

    private Playwright4JDebug() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static synchronized void log(String message) {
        if (!ENABLED) {
            return;
        }
        System.err.println(message);
        if (LOG_FILE != null) {
            try {
                Files.write(LOG_FILE, (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Diagnostics must never break the run.
            }
        }
    }

    private static Path resolveLogFile() {
        if (!ENABLED) {
            return null;
        }
        String configured = System.getProperty("playwright4j.debug.file", "");
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "playwright4j-driver.log");
    }
}
