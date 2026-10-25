package com.aresstack.playwright4j.graal;

import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code -Dplaywright4j.bidi.unhandledPromptDelay=MIN-MAX} (milliseconds, or a single value): over
 * WebDriver BiDi, a dialog that no handler takes and an HTTP auth challenge without matching
 * credentials are answered after a random delay in that range instead of at once. Playwright's
 * answer itself (dismiss, accept for beforeunload, cancel) is unchanged. Unset means no delay.
 */
public final class UnhandledPromptDelay {
    public static final String PROPERTY = "playwright4j.bidi.unhandledPromptDelay";

    /** Kept well below Playwright's default 30 s timeout, so a delayed answer never times an action out. */
    public static final int MAX_MILLIS = 10_000;

    private final int min;
    private final int max;

    private UnhandledPromptDelay(int min, int max) {
        this.min = min;
        this.max = max;
    }

    /** Reads the property; {@code null} when unset. A malformed or out-of-range value fails loudly. */
    public static UnhandledPromptDelay fromSystemProperty() {
        return parse(System.getProperty(PROPERTY));
    }

    static UnhandledPromptDelay parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        int dash = trimmed.indexOf('-');
        try {
            int min = Integer.parseInt((dash < 0 ? trimmed : trimmed.substring(0, dash)).trim());
            int max = dash < 0 ? min : Integer.parseInt(trimmed.substring(dash + 1).trim());
            if (min >= 0 && min <= max && max <= MAX_MILLIS) {
                return new UnhandledPromptDelay(min, max);
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new IllegalArgumentException("-D" + PROPERTY + " must be MIN-MAX or a single value in milliseconds, 0 <= MIN <= MAX <= "
                + MAX_MILLIS + ", was: " + value);
    }

    public int nextMillis() {
        return min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
