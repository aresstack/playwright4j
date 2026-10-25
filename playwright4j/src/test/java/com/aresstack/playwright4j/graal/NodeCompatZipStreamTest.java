package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bundled yazl/yauzl stream pipelines on the node-compat stream layer, as Playwright uses them
 * for traces (write a zip from files, then append to it by re-reading its entries). They exercise
 * pipe(dest, { end: false }), Transform chains, pull-based readers and finish/close ordering.
 */
final class NodeCompatZipStreamTest {

    private static final long TIMEOUT_MILLIS = 30_000;

    @TempDir
    Path tempDir;

    private Playwright4JHost host;
    private GraalPlaywrightRuntime runtime;

    @BeforeEach
    void start() {
        host = new Playwright4JHost(new FixedHostEnvironment(), new LocalHostFileSystem(), new RecordingMissingHostFunctionReporter());
        runtime = new GraalPlaywrightRuntime(host, new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    @Test
    void writesAndRewritesAZipArchive() throws Exception {
        Path first = Files.write(tempDir.resolve("first.txt"), "first entry ".repeat(500).getBytes(StandardCharsets.UTF_8));
        Path second = Files.write(tempDir.resolve("second.txt"), "second".getBytes(StandardCharsets.UTF_8));
        Path zip = tempDir.resolve("trace.zip");
        Path rewritten = tempDir.resolve("trace2.zip");

        runtime.evaluate("zip-test.js", ""
                + "(function () {\n"
                + "  const fs = require('fs');\n"
                + "  const { yazl, yauzl } = require('playwright-core/lib/utilsBundle');\n"
                + "  const test = globalThis.__zipTest = { state: 'writing', entries: {} };\n"
                + "  const fail = function (error) { test.state = 'error: ' + (error && error.stack || error); };\n"
                + "  // Like Tracing._exportZip: files compressed, outputStream piped after end().\n"
                + "  const zipFile = new yazl.ZipFile();\n"
                + "  zipFile.on('error', fail);\n"
                + "  zipFile.addFile(" + js(first) + ", 'first.txt');\n"
                + "  zipFile.addFile(" + js(second) + ", 'second.txt');\n"
                + "  zipFile.end();\n"
                + "  zipFile.outputStream.pipe(fs.createWriteStream(" + js(zip) + ")).on('close', function () {\n"
                + "    test.state = 'rewriting';\n"
                + "    // Like LocalUtils.zip in append mode: re-read every entry into a new archive.\n"
                + "    yauzl.open(" + js(zip) + ", function (error, inZipFile) {\n"
                + "      if (error) { fail(error); return; }\n"
                + "      const outZipFile = new yazl.ZipFile();\n"
                + "      let pending = inZipFile.entryCount;\n"
                + "      inZipFile.on('entry', function (entry) {\n"
                + "        inZipFile.openReadStream(entry, function (error2, readStream) {\n"
                + "          if (error2) { fail(error2); return; }\n"
                + "          outZipFile.addReadStream(readStream, entry.fileName);\n"
                + "          if (--pending === 0) {\n"
                + "            outZipFile.end(undefined, function () {\n"
                + "              outZipFile.outputStream.pipe(fs.createWriteStream(" + js(rewritten) + ")).on('close', function () {\n"
                + "                test.state = 'reading';\n"
                + "                readAll(" + js(rewritten) + ");\n"
                + "              });\n"
                + "            });\n"
                + "          }\n"
                + "        });\n"
                + "      });\n"
                + "    });\n"
                + "  });\n"
                + "  function readAll(path) {\n"
                + "    yauzl.open(path, { lazyEntries: true }, function (error, zip) {\n"
                + "      if (error) { fail(error); return; }\n"
                + "      zip.on('entry', function (entry) {\n"
                + "        zip.openReadStream(entry, function (error2, stream) {\n"
                + "          if (error2) { fail(error2); return; }\n"
                + "          const chunks = [];\n"
                + "          stream.on('data', function (chunk) { chunks.push(chunk); });\n"
                + "          stream.on('end', function () {\n"
                + "            test.entries[entry.fileName] = Buffer.concat(chunks).toString('utf8');\n"
                + "            zip.readEntry();\n"
                + "          });\n"
                + "        });\n"
                + "      });\n"
                + "      zip.on('end', function () { test.state = 'done'; });\n"
                + "      zip.readEntry();\n"
                + "    });\n"
                + "  }\n"
                + "})();");

        pumpUntil("the zip round trip", () -> {
            String state = runtime.evaluate("zip-test-query.js", "__zipTest.state").asString();
            if (state.startsWith("error")) {
                fail(state);
            }
            return state.equals("done");
        });
        assertEquals("first entry ".repeat(500), runtime.evaluate("zip-test-query.js", "__zipTest.entries['first.txt']").asString());
        assertEquals("second", runtime.evaluate("zip-test-query.js", "__zipTest.entries['second.txt']").asString());
        assertEquals(List.of(), runtime.unhandledRejections().recorded());
    }

    private static String js(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private void pumpUntil(String description, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for " + description + "; state: "
                        + runtime.evaluate("zip-test-query.js", "__zipTest.state").asString()
                        + "; recorded errors: " + runtime.unhandledRejections().recorded());
            }
            int fired = runtime.runDueTimers();
            int drained = runtime.drainTransports();
            if (fired == 0 && drained == 0) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
        }
    }
}
