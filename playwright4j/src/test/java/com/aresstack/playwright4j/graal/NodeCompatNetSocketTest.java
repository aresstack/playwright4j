package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Socket lifecycle of the node-compat net module (host TCP sockets behind a stream.Duplex), as
 * Node implements it and as ws and Playwright's client-certificate proxy rely on: half-close in
 * both directions, 'end' before 'close', writes queued until an outbound connection is
 * established, and a throwing listener that does not take the other connections down.
 */
final class NodeCompatNetSocketTest {

    private static final long TIMEOUT_MILLIS = 30_000;

    private static final String SERVER_SCRIPT = ""
            + "(function () {\n"
            + "  const net = require('net');\n"
            + "  const test = globalThis.__netTest = { port: 0, events: [], connections: 0 };\n"
            + "  const server = net.createServer(function (socket) {\n"
            + "    const id = ++test.connections;\n"
            + "    socket.on('data', function (data) {\n"
            + "      const text = data.toString();\n"
            + "      test.events.push('data:' + id + ':' + text);\n"
            + "      if (text === 'boom') {\n"
            + "        throw new Error('listener failure on connection ' + id);\n"
            + "      }\n"
            + "      socket.write('echo:' + text);\n"
            + "    });\n"
            + "    socket.on('end', function () {\n"
            + "      test.events.push('end:' + id + ':endEmitted=' + socket._readableState.endEmitted);\n"
            + "      socket.write('bye');\n"
            + "    });\n"
            + "    socket.on('finish', function () { test.events.push('finish:' + id); });\n"
            + "    socket.on('close', function () { test.events.push('close:' + id); });\n"
            + "  });\n"
            + "  test.server = server;\n"
            + "  server.listen(0, '127.0.0.1', function () { test.port = server.address().port; });\n"
            + "})();";

    private Playwright4JHost host;
    private GraalPlaywrightRuntime runtime;
    private int port;

    @BeforeEach
    void startServer() {
        host = Playwright4JHost.createDefault();
        runtime = new GraalPlaywrightRuntime(host, new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
        runtime.evaluate("net-test.js", SERVER_SCRIPT);
        pumpUntil("the server to listen", () -> js("__netTest.port").asInt() > 0);
        port = js("__netTest.port").asInt();
    }

    @AfterEach
    void stopServer() {
        try {
            if (runtime != null) {
                runtime.evaluate("net-test-close.js", "__netTest.server.close();");
            }
        } finally {
            if (runtime != null) {
                runtime.close();
            }
            host.netServer().closeAll();
        }
    }

    @Test
    void halfClosesLikeNode() throws IOException {
        try (Client client = Client.connect(port)) {
            client.write("ping");
            pumpUntil("the echo", () -> client.received().equals("echo:ping"));

            // Client FIN: the server sees 'end' (endEmitted already true), can still write, and
            // then ends its own side (allowHalfOpen=false), so the client reads "bye" and EOF.
            client.socket.shutdownOutput();
            pumpUntil("the server FIN", client::hasSeenEof);
            assertEquals("echo:pingbye", client.received());
            // Both sides are done: the server socket closes without the client closing first.
            pumpUntil("the server socket 'close'", () -> events().contains("close:1"));
        }
        assertEquals(List.of("data:1:ping", "end:1:endEmitted=true", "finish:1", "close:1"), events());
        assertNoLeftovers();
        assertEquals(List.of(), runtime.unhandledRejections().recorded());
    }

    @Test
    void reportsAThrowingListenerWithoutAffectingOtherConnections() throws IOException {
        try (Client first = Client.connect(port); Client second = Client.connect(port)) {
            first.write("hello");
            pumpUntil("the first echo", () -> first.received().equals("echo:hello"));
            second.write("hello");
            pumpUntil("the second echo", () -> second.received().equals("echo:hello"));

            first.write("boom");
            pumpUntil("the listener failure to be reported", () -> runtime.unhandledRejections().count() > 0);
            List<String> recorded = runtime.unhandledRejections().recorded();
            assertEquals(1, recorded.size(), recorded.toString());
            assertTrue(recorded.get(0).startsWith(UnhandledRejections.UNCAUGHT_LOG_PREFIX + "net data event: Error: listener failure on connection 1"),
                    recorded.get(0));

            // Node would have terminated the whole process; here both connections keep working.
            second.write("still there");
            pumpUntil("the second connection to answer", () -> second.received().equals("echo:helloecho:still there"));
            first.write("again");
            pumpUntil("the first connection to answer", () -> first.received().equals("echo:helloecho:again"));
        }
        pumpUntil("both server sockets to close", () -> events().contains("close:1") && events().contains("close:2"));
        assertNoLeftovers();
    }

    @Test
    void queuesWritesUntilAnOutboundConnectionIsEstablished() throws Exception {
        try (ServerSocket peer = new ServerSocket(0)) {
            CompletableFuture<String> accepted = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = peer.accept()) {
                    String request = readToEof(socket.getInputStream());
                    socket.getOutputStream().write(("reply to " + request).getBytes(StandardCharsets.UTF_8));
                    return request;
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            // write() and end() before 'connect': Node buffers both until the connection is up.
            runtime.evaluate("net-test-connect.js", ""
                    + "(function () {\n"
                    + "  const test = globalThis.__netTest;\n"
                    + "  const socket = require('net').createConnection({ host: '127.0.0.1', port: " + peer.getLocalPort() + " });\n"
                    + "  test.client = { connecting: socket.connecting, data: '' };\n"
                    + "  socket.on('connect', function () { test.events.push('client-connect'); });\n"
                    + "  socket.on('data', function (data) { test.client.data += data.toString(); });\n"
                    + "  socket.on('end', function () { test.events.push('client-end'); });\n"
                    + "  socket.on('close', function () { test.events.push('client-close'); });\n"
                    + "  socket.write('early');\n"
                    + "  socket.end(' and final');\n"
                    + "})();");
            assertTrue(js("__netTest.client.connecting").asBoolean());
            pumpUntil("the client socket to close", () -> events().contains("client-close"));
            assertEquals("early and final", accepted.get());
            assertEquals("reply to early and final", js("__netTest.client.data").asString());
            assertEquals(List.of("client-connect", "client-end", "client-close"), events());
        }
        assertNoLeftovers();
        assertEquals(List.of(), runtime.unhandledRejections().recorded());
    }

    @Test
    void closesAnUnreadOutboundSocketWhenThePeerCloses() throws Exception {
        try (ServerSocket peer = new ServerSocket(0)) {
            CompletableFuture<Void> closedByPeer = CompletableFuture.runAsync(() -> {
                try (Socket socket = peer.accept()) {
                    // FIN right away, before the client has sent or read anything.
                    socket.shutdownOutput();
                    readToEof(socket.getInputStream());
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            // Only 'close' and 'error' listeners, no consumer (like the client-certificate proxy's
            // server socket before the browser's first bytes): Node still emits 'end', ends its
            // side (allowHalfOpen=false) and closes.
            runtime.evaluate("net-test-unread.js", ""
                    + "(function () {\n"
                    + "  const test = globalThis.__netTest;\n"
                    + "  const socket = require('net').createConnection({ host: '127.0.0.1', port: " + peer.getLocalPort() + " });\n"
                    + "  socket.once('close', function () { test.events.push('client-close'); });\n"
                    + "  socket.once('error', function (error) { test.events.push('client-error:' + error.message); });\n"
                    + "})();");
            pumpUntil("the unread client socket to close", () -> events().contains("client-close"));
            closedByPeer.get();
            assertEquals(List.of("client-close"), events());
        }
        assertNoLeftovers();
        assertEquals(List.of(), runtime.unhandledRejections().recorded());
    }

    @Test
    void answersAWebSocketUpgradeWithoutUpgradeListenerAsANormalRequest() throws IOException {
        // Node (since v10) hands an upgrade request nobody takes to the 'request' handler, so the
        // client gets an answer and the socket is not left paused (e.g. the trace viewer server).
        runtime.evaluate("http-test.js", ""
                + "(function () {\n"
                + "  const test = globalThis.__netTest;\n"
                + "  test.httpServer = require('http').createServer(function (request, response) {\n"
                + "    test.events.push('request:' + request.url);\n"
                + "    response.statusCode = 404;\n"
                + "    response.end('no websocket here');\n"
                + "  });\n"
                + "  test.httpServer.on('connection', function (socket) {\n"
                + "    socket.on('close', function () { test.events.push('http-socket-close'); });\n"
                + "  });\n"
                + "  test.httpServer.listen(0, '127.0.0.1', function () { test.httpPort = test.httpServer.address().port; });\n"
                + "})();");
        pumpUntil("the http server to listen", () -> js("__netTest.httpPort > 0").asBoolean());
        try (Client client = Client.connect(js("__netTest.httpPort").asInt())) {
            client.write("GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n");
            pumpUntil("the 404 answer", () -> client.received().endsWith("no websocket here"));
            assertTrue(client.received().startsWith("HTTP/1.1 404"), client.received());
            client.socket.shutdownOutput();
            pumpUntil("the server FIN", client::hasSeenEof);
            pumpUntil("the server socket 'close'", () -> events().contains("http-socket-close"));
        } finally {
            runtime.evaluate("http-test-close.js", "__netTest.httpServer.close();");
        }
        assertEquals(List.of("request:/ws", "http-socket-close"), events());
        assertNoLeftovers();
        assertEquals(List.of(), runtime.unhandledRejections().recorded());
    }

    @Test
    void reportsAResetDuringTheTlsHandshakeOnTheTlsSocketOnly() throws Exception {
        try (ServerSocket peer = new ServerSocket(0)) {
            CompletableFuture<Integer> resetByPeer = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = peer.accept()) {
                    // Take the ClientHello, then reset instead of answering (e.g. a filtering
                    // middlebox).
                    int read = socket.getInputStream().read(new byte[16 * 1024]);
                    socket.setSoLinger(true, 0);
                    return read;
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            // Like the client-certificate proxy: tls.connect over a connected net socket. In Node
            // the read error belongs to the TLSSocket; the wrapped socket emits no 'error'.
            runtime.evaluate("net-test-tls.js", ""
                    + "(function () {\n"
                    + "  const test = globalThis.__netTest;\n"
                    + "  const raw = require('net').createConnection({ host: '127.0.0.1', port: " + peer.getLocalPort() + " });\n"
                    + "  raw.on('error', function (error) { test.events.push('raw-error:' + error.code); });\n"
                    + "  raw.on('close', function () { test.events.push('raw-close'); });\n"
                    + "  raw.on('connect', function () {\n"
                    + "    const tlsSocket = require('tls').connect({ socket: raw, servername: 'localhost', rejectUnauthorized: false });\n"
                    + "    tlsSocket.on('secureConnect', function () { test.events.push('tls-secure'); });\n"
                    + "    tlsSocket.on('error', function (error) { test.events.push('tls-error:' + error.code); });\n"
                    + "    tlsSocket.on('close', function () { test.events.push('tls-close'); });\n"
                    + "  });\n"
                    + "})();");
            pumpUntil("the peer to reset the connection", resetByPeer::isDone);
            assertTrue(resetByPeer.get() > 0, "the peer received no ClientHello");
            pumpUntil("both sockets to close", () -> events().contains("raw-close") && events().contains("tls-close"));
            List<String> events = events();
            assertTrue(events.contains("tls-error:ECONNRESET"), events.toString());
            assertTrue(events.stream().noneMatch(event -> event.startsWith("raw-error") || event.equals("tls-secure")), events.toString());
        }
        assertNoLeftovers();
        assertEquals(0, host.tls().openEngineCount(), "TLS engines left open");
        assertEquals(List.of(), runtime.unhandledRejections().recorded());
    }

    @Test
    void endsAServerTlsSocketWithCloseNotifyLikeNode() throws Exception {
        // Like the client-certificate proxy facing the browser: a TLS server socket over a
        // connection, answering with end(data). Node sends close_notify and ends the transport, so
        // the peer reads the data and then the end of the TLS stream instead of waiting.
        runtime.evaluate("net-test-tls-server.js", ""
                + "(function () {\n"
                + "  const test = globalThis.__netTest;\n"
                + "  const { key, cert } = require('playwright-core/lib/server/utils/crypto').generateSelfSignedCertificate();\n"
                + "  const tlsServer = require('tls').createServer({ key: key, cert: cert });\n"
                + "  tlsServer.on('secureConnection', function (tlsSocket) {\n"
                + "    tlsSocket.on('data', function (data) { test.events.push('tls-data:' + data); tlsSocket.end('bye'); });\n"
                + "    tlsSocket.on('finish', function () { test.events.push('tls-finish'); });\n"
                + "    tlsSocket.on('close', function () { test.events.push('tls-close'); });\n"
                + "  });\n"
                + "  test.tlsServer = require('net').createServer(function (raw) {\n"
                + "    raw.on('close', function () { test.events.push('raw-close'); });\n"
                + "    tlsServer.emit('connection', raw);\n"
                + "  });\n"
                + "  test.tlsServer.listen(0, '127.0.0.1', function () { test.tlsPort = test.tlsServer.address().port; });\n"
                + "})();");
        pumpUntil("the TLS server to listen", () -> js("__netTest.tlsPort > 0").asBoolean());
        int tlsPort = js("__netTest.tlsPort").asInt();
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new TrustAllManager()}, null);
            try (SSLSocket client = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", tlsPort)) {
                client.setSoTimeout((int) TIMEOUT_MILLIS);
                CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> {
                    try {
                        client.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
                        client.getOutputStream().flush();
                        // Returns at the server's close_notify; without it the read times out.
                        return readToEof(client.getInputStream());
                    } catch (IOException exception) {
                        return "failed: " + exception;
                    }
                });
                pumpUntil("the client to read the end of the TLS stream", answer::isDone);
                assertEquals("bye", answer.get());
            }
            pumpUntil("both server sockets to close", () -> events().contains("raw-close") && events().contains("tls-close"));
            List<String> events = events();
            assertEquals(List.of("tls-data:hello", "tls-finish"), events.subList(0, 2), events.toString());
            assertEquals(4, events.size(), events.toString());
        } finally {
            runtime.evaluate("net-test-tls-server-close.js", "__netTest.tlsServer.close();");
        }
        assertNoLeftovers();
        assertEquals(0, host.tls().openEngineCount(), "TLS engines left open");
        assertEquals(List.of(), runtime.unhandledRejections().recorded());
    }

    @Test
    void endsAClientTlsSocketWithCloseNotifyLikeNode() {
        // tls.connect over a net socket ends with close_notify and a FIN. The server side reads the
        // data and 'end', then ends its own side (a TLSSocket over a net socket is not half-open),
        // so the client sees 'end' too and both close, all engines released.
        runtime.evaluate("net-test-tls-client.js", ""
                + "(function () {\n"
                + "  const test = globalThis.__netTest;\n"
                + "  const tls = require('tls');\n"
                + "  const { key, cert } = require('playwright-core/lib/server/utils/crypto').generateSelfSignedCertificate();\n"
                + "  const tlsServer = tls.createServer({ key: key, cert: cert });\n"
                + "  tlsServer.on('secureConnection', function (tlsSocket) {\n"
                + "    tlsSocket.on('data', function (data) { test.events.push('server-data:' + data); });\n"
                + "    tlsSocket.on('end', function () { test.events.push('server-end'); });\n"
                + "    tlsSocket.on('close', function () { test.events.push('server-close'); });\n"
                + "  });\n"
                + "  test.tlsServer = require('net').createServer(function (raw) { tlsServer.emit('connection', raw); });\n"
                + "  test.tlsServer.listen(0, '127.0.0.1', function () {\n"
                + "    const raw = require('net').createConnection({ host: '127.0.0.1', port: test.tlsServer.address().port });\n"
                + "    raw.on('connect', function () {\n"
                + "      const client = tls.connect({ socket: raw, servername: 'localhost', rejectUnauthorized: false });\n"
                + "      client.on('secureConnect', function () { client.end('ping'); });\n"
                + "      client.on('data', function (data) { test.events.push('client-data:' + data); });\n"
                + "      client.on('end', function () { test.events.push('client-end'); });\n"
                + "      client.on('error', function (error) { test.events.push('client-error:' + error.message); });\n"
                + "      client.on('close', function () { test.events.push('client-close'); });\n"
                + "    });\n"
                + "  });\n"
                + "})();");
        try {
            pumpUntil("both TLS sockets to close", () -> events().contains("client-close") && events().contains("server-close"));
            List<String> events = events();
            assertTrue(events.indexOf("server-data:ping") >= 0 && events.indexOf("server-data:ping") < events.indexOf("server-end"), events.toString());
            assertTrue(events.contains("client-end"), events.toString());
            assertTrue(events.stream().noneMatch(event -> event.startsWith("client-error") || event.startsWith("client-data")), events.toString());
        } finally {
            runtime.evaluate("net-test-tls-client-close.js", "__netTest.tlsServer.close();");
        }
        assertNoLeftovers();
        assertEquals(0, host.tls().openEngineCount(), "TLS engines left open");
        assertEquals(List.of(), runtime.unhandledRejections().recorded());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void assertNoLeftovers() {
        pumpUntil("all sockets to be released", () -> host.netServer().openSocketCount() == 0
                && js("__playwright4jOpenNetSockets()").asInt() == 0);
    }

    private List<String> events() {
        Value events = js("__netTest.events");
        List<String> result = new ArrayList<String>();
        for (long index = 0; index < events.getArraySize(); index++) {
            result.add(events.getArrayElement(index).asString());
        }
        return result;
    }

    private Value js(String expression) {
        return runtime.evaluate("net-test-query.js", expression);
    }

    /** Runs the guest event loop (timers, host socket events) until the condition holds. */
    private void pumpUntil(String description, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for " + description + "; events: " + events()
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

    private static String readToEof(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Test peer for the shim's self-signed certificate. */
    private static final class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** A plain TCP client whose input is collected on a background thread. */
    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private volatile boolean eof;

        private Client(Socket socket) throws IOException {
            this.socket = socket;
            InputStream input = socket.getInputStream();
            Thread reader = new Thread(() -> {
                byte[] buffer = new byte[1024];
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
            }, "net-test-client");
            reader.setDaemon(true);
            reader.start();
        }

        static Client connect(int port) throws IOException {
            return new Client(new Socket("127.0.0.1", port));
        }

        void write(String text) throws IOException {
            socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }

        String received() {
            synchronized (received) {
                return new String(received.toByteArray(), StandardCharsets.UTF_8);
            }
        }

        boolean hasSeenEof() {
            return eof;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
