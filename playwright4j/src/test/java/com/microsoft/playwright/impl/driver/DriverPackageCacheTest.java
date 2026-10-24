package com.microsoft.playwright.impl.driver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverPackageCacheTest {

    @Test
    void packageIdIsAContentHash() {
        assertTrue(Driver.packageId().matches("[0-9a-f]{32}"), Driver.packageId());
    }

    @Test
    void extractsCliAndTraceViewerAssets(@TempDir Path root) {
        Path dir = Driver.driverDirFor(root, Driver.packageId());
        Driver.ensurePackageExtracted(dir);

        assertTrue(Files.isRegularFile(dir.resolve("package/cli.js")));
        assertTrue(Files.isRegularFile(dir.resolve("package/lib/vite/traceViewer/index.html")));
        assertTrue(Files.exists(dir.resolve("package/.playwright4j-extracted")));
    }

    @Test
    void upgradeDoesNotReuseAnOlderExtraction(@TempDir Path root) throws Exception {
        // Simulate a cache written by an older playwright4j version with different package content.
        Path oldDir = Driver.driverDirFor(root, "0000000000000000");
        Files.createDirectories(oldDir.resolve("package/lib/vite/traceViewer"));
        Files.write(oldDir.resolve("package/lib/vite/traceViewer/index.html"), "stale".getBytes("UTF-8"));
        Files.write(oldDir.resolve("package/.playwright4j-extracted"), new byte[0]);

        Path newDir = Driver.driverDirFor(root, Driver.packageId());
        assertNotEquals(oldDir, newDir);
        Driver.ensurePackageExtracted(newDir);

        String traceViewer = new String(Files.readAllBytes(newDir.resolve("package/lib/vite/traceViewer/index.html")), "UTF-8");
        assertNotEquals("stale", traceViewer);
        assertTrue(Files.isRegularFile(newDir.resolve("package/cli.js")));
    }

    @Test
    void replacesAnInterruptedExtraction(@TempDir Path root) throws Exception {
        Path dir = Driver.driverDirFor(root, Driver.packageId());
        Files.createDirectories(dir.resolve("package"));
        Files.write(dir.resolve("package/partial.js"), new byte[0]); // no marker: incomplete

        Driver.ensurePackageExtracted(dir);

        assertFalse(Files.exists(dir.resolve("package/partial.js")));
        assertTrue(Files.isRegularFile(dir.resolve("package/cli.js")));
        try (java.util.stream.Stream<Path> siblings = Files.list(dir)) {
            assertEquals(1, siblings.count(), "staging directory left behind");
        }
    }
}
