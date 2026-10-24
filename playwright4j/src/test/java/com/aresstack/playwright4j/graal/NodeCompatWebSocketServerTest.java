package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BooleanSupplier;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the server side of Playwright's WebSocket endpoint (launch-server /
 * BrowserServer) under GraalJS: Playwright's own WSServer and the bundled ws library run on the
 * node-compat net/http/stream layer and a host TCP server, and real WebSocket clients connect.
 *
 * <p>ws reads Node stream state on close ({@code socket._readableState.endEmitted},
 * {@code receiver._writableState.finished/errorEmitted}). Without it the server WebSocket never
 * emitted 'close', so Playwright never ran its per-connection cleanup, and the TypeError ended up
 * as an unhandled promise rejection. Every test therefore checks that the server WebSocket closed
 * exactly once with the expected code, that no socket or WebSocket stays registered, and that no
 * unhandled rejection occurred.
 */
final class NodeCompatWebSocketServerTest {

    private static final long TIMEOUT_MILLIS = 30_000;

    private static final String SERVER_SCRIPT = ""
            + "(function () {\n"
            + "  const { WSServer } = require('playwright-core/lib/server/utils/wsServer');\n"
            + "  const test = globalThis.__wsTest = { endpoint: null, events: [], sockets: {}, connections: 0 };\n"
            + "  const server = new WSServer({\n"
            + "    onRequest: function (request, response) { response.end('not a websocket'); },\n"
            + "    onHeaders: function () {},\n"
            + "    onUpgrade: function () { return undefined; },\n"
            + "    onConnection: function (request, url, ws, id) {\n"
            + "      test.connections++;\n"
            + "      test.sockets[id] = ws;\n"
            + "      test.lastId = id;\n"
            + "      test.events.push('connection:' + id);\n"
            + "      ws.on('message', function (data) { ws.send('echo:' + data.toString()); });\n"
            + "      ws.on('error', function (error) { test.events.push('error:' + id + ':' + error.message); });\n"
            + "      ws.on('close', function (code) {\n"
            + "        test.events.push('close:' + id + ':' + code);\n"
            + "        delete test.sockets[id];\n"
            + "      });\n"
            + "      return { close: function () { return Promise.resolve(); } };\n"
            + "    }\n"
            + "  });\n"
            + "  test.server = server;\n"
            + "  server.listen(0, '127.0.0.1', '/ws').then(\n"
            + "    function (endpoint) { test.endpoint = endpoint; },\n"
            + "    function (error) { test.events.push('listen-error:' + error.message); });\n"
            + "  // Raw socket errors (ws handles them itself and closes the WebSocket with 1006).\n"
            + "  server.server.on('connection', function (socket) {\n"
            + "    socket.on('error', function (error) { test.events.push('socket-error:' + error.code); });\n"
            + "  });\n"
            + "})();";

    private Playwright4JHost host;
    private GraalPlaywrightRuntime runtime;
    private String endpoint;

    @BeforeEach
    void startServer() {
        host = Playwright4JHost.createDefault();
        runtime = new GraalPlaywrightRuntime(host, new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
        runtime.evaluate("ws-server-test.js", SERVER_SCRIPT);
        pumpUntil("the server endpoint", () -> !js("__wsTest.endpoint").isNull());
        endpoint = js("__wsTest.endpoint").asString();
        assertTrue(endpoint.startsWith("ws://127.0.0.1:"), endpoint);
    }

    @AfterEach
    void stopServer() {
        try {
            if (runtime != null) {
                runtime.evaluate("ws-server-test-close.js",
                        "__wsTest.server.close().then(function () { __wsTest.serverClosed = true; });");
                pumpUntil("the server to close", () -> js("__wsTest.serverClosed === true").asBoolean());
                assertEquals(0, host.netServer().openServerCount(), "listening host servers after close");
            }
        } finally {
            if (runtime != null) {
                runtime.close();
            }
            host.netServer().closeAll();
        }
    }

    @Test
    void closesTheServerWebSocketWhenTheClientClosesAndAcceptsReconnects() {
        for (int cycle = 1; cycle <= 3; cycle++) {
            JdkClient client = JdkClient.connect(this, endpoint);
            String id = awaitConnection(cycle);

            assertEquals("echo:hello " + cycle, client.roundTrip(this, "hello " + cycle));

            client.closeNormally(this, 1000, "bye");
            awaitServerClose(id, 1000);
            assertEquals(1000, client.awaitClose(this));
            assertNoLeftovers();
        }
        assertEquals(3, js("__wsTest.connections").asInt());
    }

    @Test
    void closesBothSidesWhenTheServerClosesTheWebSocket() {
        JdkClient client = JdkClient.connect(this, endpoint);
        String id = awaitConnection(1);
        assertEquals("echo:ping", client.roundTrip(this, "ping"));

        runtime.evaluate("ws-server-close.js", "__wsTest.sockets['" + id + "'].close(1001, 'server shutdown');");

        assertEquals(1001, client.awaitClose(this));
        awaitServerClose(id, 1001);
        assertNoLeftovers();
    }

    @Test
    void closesTheServerWebSocketWhenTheServerTerminatesIt() {
        JdkClient client = JdkClient.connect(this, endpoint);
        String id = awaitConnection(1);
        assertEquals("echo:ping", client.roundTrip(this, "ping"));

        runtime.evaluate("ws-server-terminate.js", "__wsTest.sockets['" + id + "'].terminate();");

        awaitServerClose(id, 1006);
        client.awaitCloseOrError(this);
        assertNoLeftovers();
    }

    @Test
    void closesTheServerWebSocketWhenTheClientDisconnectsWithoutACloseFrame() {
        JdkClient client = JdkClient.connect(this, endpoint);
        String id = awaitConnection(1);
        assertEquals("echo:ping", client.roundTrip(this, "ping"));

        client.webSocket.abort();

        awaitServerClose(id, 1006);
        assertNoLeftovers();
    }

    @Test
    void echoesAMessageThatSpansManyTcpChunks() {
        JdkClient client = JdkClient.connect(this, endpoint);
        String id = awaitConnection(1);
        // 1 MiB arrives in many host reads (Receiver writes with backpressure) and goes back out
        // through the socket's write queue in one large frame.
        String large = "0123456789abcdef".repeat(64 * 1024);

        assertEquals("echo:" + large, client.roundTrip(this, large));
        assertEquals("echo:after", client.roundTrip(this, "after"));

        client.closeNormally(this, 1000, "");
        awaitServerClose(id, 1000);
        assertNoLeftovers();
    }

    @Test
    void closesTheServerWebSocketWhenTheClientResetsTheConnection() throws IOException {
        try (RawClient client = RawClient.connect(endpoint)) {
            client.write(client.upgradeRequest());
            pumpUntil("the upgrade response", client::hasUpgradeResponse);
            String id = awaitConnection(1);

            // TCP RST instead of FIN: the host read fails, the socket is destroyed with ECONNRESET
            // (ws handles the socket error) and the server WebSocket closes with 1006.
            client.reset();
            awaitServerClose(id, 1006);
            assertTrue(events().contains("socket-error:ECONNRESET"), events().toString());
        }
        assertNoLeftovers();
    }

    @Test
    void deliversAFrameThatArrivesTogetherWithTheUpgradeRequest() throws IOException {
        try (RawClient client = RawClient.connect(endpoint)) {
            // One TCP write: HTTP upgrade request immediately followed by a masked text frame. The
            // frame bytes reach ws as the upgrade 'head' and must be fed back via socket.unshift().
            client.write(concat(client.upgradeRequest(), maskedFrame(0x1, "early".getBytes(StandardCharsets.UTF_8))));
            pumpUntil("the upgrade response", client::hasUpgradeResponse);
            String id = awaitConnection(1);

            pumpUntil("the echo of the pipelined frame", () -> client.hasFrame());
            Frame echo = client.nextFrame();
            assertEquals(0x1, echo.opcode);
            assertEquals("echo:early", new String(echo.payload, StandardCharsets.UTF_8));

            client.write(maskedFrame(0x8, closePayload(1000, "")));
            pumpUntil("the server close frame", () -> client.hasFrame());
            Frame close = client.nextFrame();
            assertEquals(0x8, close.opcode);
            assertEquals(1000, close.closeCode());
            // After both close frames ws ends the socket: a half-close (FIN), the server keeps
            // reading until the client closes the TCP connection as well.
            pumpUntil("the server FIN", client::hasSeenEof);
            client.close();
            awaitServerClose(id, 1000);
        }
        assertNoLeftovers();
    }

    @Test
    void closesWithAProtocolErrorWhenTheClientSendsAnInvalidFrame() throws IOException {
        try (RawClient client = RawClient.connect(endpoint)) {
            client.write(client.upgradeRequest());
            pumpUntil("the upgrade response", client::hasUpgradeResponse);
            String id = awaitConnection(1);

            // RSV2 set without a negotiated extension: ws's Receiver fails the write with a
            // RangeError (built with Error.captureStackTrace), ws answers with close code 1002 and
            // ends the socket, and must still emit 'close' once the client is gone - with 1006,
            // as in Node, because no close frame was received.
            byte[] invalid = maskedFrame(0x1, "x".getBytes(StandardCharsets.UTF_8));
            invalid[0] |= 0x20;
            client.write(invalid);

            pumpUntil("the server close frame", () -> client.hasFrame());
            Frame close = client.nextFrame();
            assertEquals(0x8, close.opcode);
            assertEquals(1002, close.closeCode());
            pumpUntil("the server FIN", client::hasSeenEof);
            client.close();
            awaitServerClose(id, 1006);
            assertTrue(events().contains("error:" + id + ":Invalid WebSocket frame: RSV2 and RSV3 must be clear"), events().toString());
        }
        assertNoLeftovers();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String awaitConnection(int expectedConnections) {
        pumpUntil("server connection #" + expectedConnections, () -> js("__wsTest.connections").asInt() >= expectedConnections);
        return js("__wsTest.lastId").asString();
    }

    private void awaitServerClose(String id, int code) {
        String expected = "close:" + id + ":" + code;
        pumpUntil("server WebSocket 'close' " + expected, () -> events().stream().anyMatch(event -> event.startsWith("close:" + id + ":")));
        List<String> closes = new ArrayList<String>();
        for (String event : events()) {
            if (event.startsWith("close:" + id + ":")) {
                closes.add(event);
            }
        }
        assertEquals(List.of(expected), closes, "server WebSocket 'close' events");
    }

    private void assertNoLeftovers() {
        pumpUntil("all sockets to be released", () -> host.netServer().openSocketCount() == 0
                && js("__playwright4jOpenNetSockets()").asInt() == 0
                && js("__wsTest.server._wsServer.clients.size").asInt() == 0);
        assertEquals(0, js("Object.keys(__wsTest.sockets).length").asInt(), "server WebSockets without 'close'");
        assertEquals(List.of(), runtime.unhandledRejections().recorded(), "unhandled promise rejections");
    }

    private List<String> events() {
        Value events = js("__wsTest.events");
        List<String> result = new ArrayList<String>();
        for (long index = 0; index < events.getArraySize(); index++) {
            result.add(events.getArrayElement(index).asString());
        }
        return result;
    }

    private Value js(String expression) {
        return runtime.evaluate("ws-server-test-query.js", expression);
    }

    /** Runs the guest event loop (timers, host socket events) until the condition holds. */
    void pumpUntil(String description, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for " + description + "; events: " + events()
                        + "; unhandled rejections: " + runtime.unhandledRejections().recorded());
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

    /** The JDK WebSocket client (java.net.http), driven while the test thread pumps the server. */
    private static final class JdkClient implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<String>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<Integer>();
        private final StringBuilder partial = new StringBuilder();
        private WebSocket webSocket;

        static JdkClient connect(NodeCompatWebSocketServerTest test, String endpoint) {
            JdkClient client = new JdkClient();
            CompletableFuture<WebSocket> future = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(endpoint), client);
            test.pumpUntil("the client handshake", future::isDone);
            client.webSocket = future.join();
            return client;
        }

        String roundTrip(NodeCompatWebSocketServerTest test, String text) {
            CompletableFuture<WebSocket> sent = webSocket.sendText(text, true);
            test.pumpUntil("the reply to '" + text + "'", () -> sent.isDone() && !messages.isEmpty());
            sent.join();
            return messages.poll();
        }

        void closeNormally(NodeCompatWebSocketServerTest test, int code, String reason) {
            CompletableFuture<WebSocket> sent = webSocket.sendClose(code, reason);
            test.pumpUntil("the client close frame to be sent", sent::isDone);
            sent.join();
        }

        int awaitClose(NodeCompatWebSocketServerTest test) {
            test.pumpUntil("the client to receive the close frame", closed::isDone);
            return closed.join();
        }

        void awaitCloseOrError(NodeCompatWebSocketServerTest test) {
            test.pumpUntil("the client to notice the closed connection", closed::isDone);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(Long.MAX_VALUE);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.complete(-1);
        }
    }

    private static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }

        int closeCode() {
            return ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
        }
    }

    /** A raw TCP WebSocket client, for byte layouts the JDK client never produces. */
    private static final class RawClient implements AutoCloseable {
        private final Socket socket;
        private final OutputStream output;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final Thread reader;
        private volatile boolean eof;
        private final String path;
        private final String hostHeader;
        private int consumed;

        private RawClient(Socket socket, String hostHeader, String path) throws IOException {
            this.socket = socket;
            this.output = socket.getOutputStream();
            this.hostHeader = hostHeader;
            this.path = path;
            InputStream input = socket.getInputStream();
            this.reader = new Thread(() -> {
                byte[] buffer = new byte[4096];
                try {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        synchronized (received) {
                            received.write(buffer, 0, count);
                        }
                    }
                    eof = true;
                } catch (IOException ignored) {
                    // closed
                }
            }, "raw-websocket-client");
            reader.setDaemon(true);
            reader.start();
        }

        static RawClient connect(String endpoint) throws IOException {
            URI uri = URI.create(endpoint);
            return new RawClient(new Socket(uri.getHost(), uri.getPort()), uri.getHost() + ":" + uri.getPort(), uri.getPath());
        }

        byte[] upgradeRequest() {
            return ("GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        }

        void write(byte[] bytes) throws IOException {
            output.write(bytes);
            output.flush();
        }

        boolean hasUpgradeResponse() {
            byte[] bytes = snapshot();
            int end = indexOfHeaderEnd(bytes);
            if (end < 0) {
                return false;
            }
            String head = new String(bytes, 0, end, StandardCharsets.ISO_8859_1);
            assertTrue(head.startsWith("HTTP/1.1 101 "), head);
            consumed = end + 4;
            return true;
        }

        boolean hasSeenEof() {
            return eof;
        }

        boolean hasFrame() {
            return parseFrame(snapshot(), consumed) != null;
        }

        Frame nextFrame() {
            byte[] bytes = snapshot();
            int[] length = new int[1];
            Frame frame = parseFrame(bytes, consumed, length);
            consumed += length[0];
            return frame;
        }

        private byte[] snapshot() {
            synchronized (received) {
                return received.toByteArray();
            }
        }

        private static Frame parseFrame(byte[] bytes, int offset) {
            return parseFrame(bytes, offset, new int[1]);
        }

        private static Frame parseFrame(byte[] bytes, int offset, int[] frameLength) {
            if (bytes.length - offset < 2) {
                return null;
            }
            int opcode = bytes[offset] & 0x0f;
            int length = bytes[offset + 1] & 0x7f;
            int header = 2;
            if (length == 126) {
                if (bytes.length - offset < 4) {
                    return null;
                }
                length = ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
                header = 4;
            }
            if (bytes.length - offset < header + length) {
                return null;
            }
            byte[] payload = new byte[length];
            System.arraycopy(bytes, offset + header, payload, 0, length);
            frameLength[0] = header + length;
            return new Frame(opcode, payload);
        }

        private static int indexOfHeaderEnd(byte[] bytes) {
            for (int index = 0; index + 3 < bytes.length; index++) {
                if (bytes[index] == '\r' && bytes[index + 1] == '\n' && bytes[index + 2] == '\r' && bytes[index + 3] == '\n') {
                    return index;
                }
            }
            return -1;
        }

        /** Closes with SO_LINGER 0, so the peer gets a RST instead of a FIN. */
        void reset() throws IOException {
            socket.setSoLinger(true, 0);
            socket.close();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static byte[] maskedFrame(int opcode, byte[] payload) {
        assertTrue(payload.length < 126);
        byte[] mask = {0x11, 0x22, 0x33, 0x44};
        byte[] frame = new byte[2 + 4 + payload.length];
        frame[0] = (byte) (0x80 | opcode);
        frame[1] = (byte) (0x80 | payload.length);
        System.arraycopy(mask, 0, frame, 2, 4);
        for (int index = 0; index < payload.length; index++) {
            frame[6 + index] = (byte) (payload[index] ^ mask[index % 4]);
        }
        return frame;
    }

    private static byte[] closePayload(int code, String reason) {
        byte[] text = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + text.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(text, 0, payload, 2, text.length);
        return payload;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
