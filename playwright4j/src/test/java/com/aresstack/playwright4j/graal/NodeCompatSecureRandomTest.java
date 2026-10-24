package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NodeCompatSecureRandomTest {

    @Test
    void randomBytesAndRandomUuidDoNotUseMathRandom() {
        try (GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(Playwright4JHost.createDefault())) {
            runtime.loadNodeCompatibilityLayer();
            // With Math.random pinned, a Math.random-based generator would return all zero bytes.
            runtime.evaluate("pin-math-random.js", "Math.random = function () { return 0; };");

            String first = runtime.evaluate("random-1.js", "require('crypto').randomBytes(32).toString('hex')").asString();
            String second = runtime.evaluate("random-2.js", "require('crypto').randomBytes(32).toString('hex')").asString();
            String uuid = runtime.evaluate("uuid.js", "require('crypto').randomUUID()").asString();

            assertEquals(64, first.length());
            assertNotEquals("0".repeat(64), first);
            assertNotEquals(first, second);
            assertTrue(uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), uuid);
            assertNotEquals("00000000-0000-4000-8000-000000000000", uuid);
            assertEquals(0, runtime.evaluate("random-0.js", "require('crypto').randomBytes(0).length").asInt());
        }
    }
}
