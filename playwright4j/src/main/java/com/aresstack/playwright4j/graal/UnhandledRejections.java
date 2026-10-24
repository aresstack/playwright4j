package com.aresstack.playwright4j.graal;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.polyglot.HostAccess;

/**
 * Collects the guest's unhandled promise rejections and uncaught exceptions. Node.js terminates on
 * both (on unhandled rejections by default since Node 15) and Playwright-Core never installs a
 * handler, so under GraalJS every occurrence is a bug: typically a Node API the compatibility layer
 * emulates incompletely, whose TypeError is thrown from an event listener that runs in a microtask
 * ({@code process.nextTick} is promise-based here), a timer callback or a host socket event. Such
 * errors used to be dropped silently (rejections) or to tear down the whole driver (socket events).
 *
 * <p>Every occurrence is recorded. What else happens is the {@link Mode}, configured for the driver
 * with {@code -Dplaywright4j.unhandledRejections=ignore|warn|strict} (default {@code warn}).
 */
public final class UnhandledRejections {

    public static final String SYSTEM_PROPERTY = "playwright4j.unhandledRejections";
    public static final String LOG_PREFIX = "[playwright4j] unhandled promise rejection: ";
    public static final String UNCAUGHT_LOG_PREFIX = "[playwright4j] uncaught exception: ";

    private static final int MAX_RECORDED = 100;

    public enum Mode {
        /** Record only. */
        IGNORE,
        /** Record and report on stderr. */
        WARN,
        /** Record, report, and terminate like Node.js does (exit code 1); for tests and CI. */
        STRICT;

        public static Mode fromSystemProperty(Mode defaultMode) {
            String value = System.getProperty(SYSTEM_PROPERTY, "").trim();
            if (value.isEmpty()) {
                return defaultMode;
            }
            try {
                return Mode.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("-D" + SYSTEM_PROPERTY + " must be ignore, warn or strict, was: " + value);
            }
        }
    }

    private final Mode mode;
    private final PrintStream log;
    private final AtomicInteger count = new AtomicInteger();
    private final List<String> recorded = Collections.synchronizedList(new ArrayList<String>());

    public UnhandledRejections(Mode mode, PrintStream log) {
        this.mode = mode;
        this.log = log;
    }

    public Mode mode() {
        return mode;
    }

    /** Called by the guest's unhandled-rejection handler with the reason's stack (or string form). */
    @HostAccess.Export
    public void record(String description) {
        record(LOG_PREFIX, description);
    }

    /**
     * Called by the compatibility layer for an exception thrown by a timer callback or a listener of
     * a host event, with its origin and stack.
     */
    @HostAccess.Export
    public void recordUncaughtException(String description) {
        record(UNCAUGHT_LOG_PREFIX, description);
    }

    private void record(String prefix, String description) {
        count.incrementAndGet();
        synchronized (recorded) {
            if (recorded.size() < MAX_RECORDED) {
                recorded.add(prefix + description);
            }
        }
        if (mode != Mode.IGNORE) {
            log.println(prefix + description);
            log.flush();
        }
    }

    public int count() {
        return count.get();
    }

    /** The first {@value #MAX_RECORDED} occurrences with their log prefix, oldest first. */
    public List<String> recorded() {
        synchronized (recorded) {
            return new ArrayList<String>(recorded);
        }
    }

    /** Whether the driver should terminate now (strict mode, at least one rejection). */
    @HostAccess.Export
    public boolean terminationRequested() {
        return mode == Mode.STRICT && count.get() > 0;
    }
}
