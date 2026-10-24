package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exceptions thrown by stream listeners and callbacks on the node-compat stream layer. The base
 * streams run their callbacks synchronously, so a throwing 'finish'/'close'/'error' listener or
 * write callback runs inside the stream's own bookkeeping. Node reports such an exception as
 * uncaught; here it must reach the caller (and from there the event loop's error reporting)
 * instead of disappearing, and the stream must still finish and close.
 */
final class NodeCompatStreamTest {

    private GraalPlaywrightRuntime runtime;

    @BeforeEach
    void start() {
        runtime = new GraalPlaywrightRuntime(Playwright4JHost.createDefault(),
                new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    @Test
    void aThrowingFinishListenerPropagatesAndTheStreamStillCloses() {
        assertEquals("thrown=finish listener failure;events=write,final,end-callback,finish,close", run(""
                + "const w = new Writable({\n"
                + "  write(chunk, encoding, callback) { events.push('write'); callback(); },\n"
                + "  final(callback) { events.push('final'); callback(); }\n"
                + "});\n"
                + "w.on('finish', function () { events.push('finish'); throw new Error('finish listener failure'); });\n"
                + "w.on('close', function () { events.push('close'); });\n"
                + "w.write('a');\n"
                + "try { w.end(function () { events.push('end-callback'); }); } catch (e) { thrown = e.message; }\n"));
    }

    @Test
    void aThrowingWriteCallbackPropagatesAndLaterWritesStillWork() {
        assertEquals("thrown=write callback failure;events=write:a,write:b,finish,close", run(""
                + "const w = new Writable({\n"
                + "  write(chunk, encoding, callback) { events.push('write:' + chunk.toString()); callback(); }\n"
                + "});\n"
                + "w.on('finish', function () { events.push('finish'); });\n"
                + "w.on('close', function () { events.push('close'); });\n"
                + "try { w.write('a', function () { throw new Error('write callback failure'); }); } catch (e) { thrown = e.message; }\n"
                + "w.end('b');\n"));
    }

    @Test
    void aThrowingWriteCallbackDoesNotStrandTheChunksBehindIt() {
        assertEquals("thrown=corked callback failure;events=write:a,write:b,finish,close", run(""
                + "const w = new Writable({\n"
                + "  write(chunk, encoding, callback) { events.push('write:' + chunk.toString()); callback(); }\n"
                + "});\n"
                + "w.on('finish', function () { events.push('finish'); });\n"
                + "w.on('close', function () { events.push('close'); });\n"
                + "w.cork();\n"
                + "w.write('a', function () { throw new Error('corked callback failure'); });\n"
                + "w.write('b');\n"
                + "try { w.uncork(); } catch (e) { thrown = e.message; }\n"
                + "w.end();\n"));
    }

    @Test
    void destroyWithAnErrorNobodyHandlesPropagatesAndStillCloses() {
        assertEquals("thrown=unhandled stream error;events=close", run(""
                + "const r = new Readable({ read() {} });\n"
                + "r.on('close', function () { events.push('close'); });\n"
                + "try { r.destroy(new Error('unhandled stream error')); } catch (e) { thrown = e.message; }\n"));
    }

    @Test
    void aThrowingCloseListenerPropagates() {
        assertEquals("thrown=close listener failure;events=close", run(""
                + "const d = new Duplex({ read() {}, write(chunk, encoding, callback) { callback(); } });\n"
                + "d.on('close', function () { events.push('close'); throw new Error('close listener failure'); });\n"
                + "try { d.destroy(); } catch (e) { thrown = e.message; }\n"));
    }

    private String run(String body) {
        return runtime.evaluate("stream-test.js", ""
                + "(function () {\n"
                + "  const { Readable, Writable, Duplex } = require('stream');\n"
                + "  const events = [];\n"
                + "  let thrown = null;\n"
                + body
                + "  return 'thrown=' + thrown + ';events=' + events.join(',');\n"
                + "})();").asString();
    }
}
