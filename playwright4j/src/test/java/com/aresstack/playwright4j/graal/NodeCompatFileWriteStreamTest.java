package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * fs.createWriteStream on the node-compat layer writes through to the host file, as in Node.
 * Playwright's DEBUG_FILE logger writes to such a stream and never ends it, so a stream that only
 * flushed on end() would leave the log empty.
 */
final class NodeCompatFileWriteStreamTest {

    @TempDir
    Path tempDir;

    private GraalPlaywrightRuntime runtime;

    @BeforeEach
    void start() {
        Playwright4JHost host = new Playwright4JHost(new FixedHostEnvironment(), new LocalHostFileSystem(),
                new RecordingMissingHostFunctionReporter());
        runtime = new GraalPlaywrightRuntime(host, new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    @Test
    void chunksReachTheFileBeforeTheStreamEnds() throws Exception {
        Path log = tempDir.resolve("protocol.log");
        Files.write(log, "stale".getBytes(StandardCharsets.UTF_8));

        runtime.evaluate("write-stream-test.js", ""
                + "(function () {\n"
                + "  const stream = require('fs').createWriteStream(" + js(log) + ");\n"
                + "  stream.write('first line\\n');\n"
                + "  stream.write('second line\\n');\n"
                + "})();");

        assertEquals("first line\nsecond line\n", new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
    }

    @Test
    void appendFlagsKeepTheExistingContent() throws Exception {
        Path log = tempDir.resolve("appended.log");
        Files.write(log, "kept\n".getBytes(StandardCharsets.UTF_8));

        runtime.evaluate("write-stream-test.js", ""
                + "(function () {\n"
                + "  const stream = require('fs').createWriteStream(" + js(log) + ", { flags: 'a' });\n"
                + "  stream.end('added\\n');\n"
                + "})();");

        assertEquals("kept\nadded\n", new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
    }

    private static String js(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
