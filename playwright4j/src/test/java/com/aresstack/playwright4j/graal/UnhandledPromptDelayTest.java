package com.aresstack.playwright4j.graal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnhandledPromptDelayTest {
    @Test
    void unsetMeansNoDelay() {
        assertNull(UnhandledPromptDelay.parse(null));
        assertNull(UnhandledPromptDelay.parse(" "));
    }

    @Test
    void drawsWithinTheRange() {
        UnhandledPromptDelay delay = UnhandledPromptDelay.parse("600-2000");
        for (int i = 0; i < 1000; i++) {
            int millis = delay.nextMillis();
            assertTrue(millis >= 600 && millis <= 2000, "drew " + millis);
        }
        assertEquals(800, UnhandledPromptDelay.parse("800").nextMillis());
        assertEquals(0, UnhandledPromptDelay.parse(" 0 - 0 ").nextMillis());
    }

    @Test
    void rejectsMalformedAndUnboundedValues() {
        for (String value : new String[] {"abc", "-5", "900-600", "0-10001", "1s", "100-"}) {
            assertThrows(IllegalArgumentException.class, () -> UnhandledPromptDelay.parse(value), value);
        }
    }
}
