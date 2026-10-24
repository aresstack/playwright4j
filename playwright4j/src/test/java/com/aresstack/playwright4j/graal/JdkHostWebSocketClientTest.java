package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

final class JdkHostWebSocketClientTest {

    private static final Pattern KEY_HEADER = Pattern.compile("(?im)^Sec-WebSocket-Key:\\s*(\\S+)\\s*$");

    /**
     * A killed launch-server (TerminateProcess on Windows) resets the TCP connection, and
     * java.net.http fails the write ("Output closed") before the listener's onError/onClose runs.
     * The failed send must already be reflected by isClosed(), so the connect transport can drop it
     * and surface onclose (Browser 'disconnected') instead of rejecting the call with an I/O error.
     */
    @Test
    void failedSendMarksTheConnectionClosedBeforeTheListenerRuns() {
        JdkHostWebSocketClient client = new JdkHostWebSocketClient();
        String id = client.register(new JdkHostWebSocketClient.Connection(), new OutputClosedWebSocket());
        assertFalse(client.isClosed(id));

        // Still thrown: the launched browser's CDP pipe relies on the error.
        assertThrows(CompletionException.class, () -> client.send(id, "{\"id\":1,\"method\":\"Page.title\"}"));
        assertTrue(client.isClosed(id), "a failed send must mark the connection closed");
    }

    /** The same invariant against a real socket aborted with an RST (no close frame). */
    @Test
    void abruptPeerDisconnectIsReportedAndFailedSendsAgreeWithIsClosed() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            JdkHostWebSocketClient client = new JdkHostWebSocketClient();
            for (int attempt = 0; attempt < 20; attempt++) {
                CompletableFuture<Socket> accepted = CompletableFuture.supplyAsync(() -> acceptHandshake(server));
                String id = client.open("ws://127.0.0.1:" + server.getLocalPort() + "/");
                Socket peer = accepted.get(10, TimeUnit.SECONDS);
                peer.setSoLinger(true, 0);
                peer.close();

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!client.isClosed(id) && System.nanoTime() < deadline) {
                    try {
                        client.send(id, "{\"id\":1,\"method\":\"Page.title\"}");
                    } catch (CompletionException expected) {
                        assertTrue(client.isClosed(id), "send failed but the connection is not reported closed");
                    }
                }
                assertTrue(client.isClosed(id), "abrupt peer disconnect was never reported");
                client.close(id);
            }
        }
    }

    private static Socket acceptHandshake(ServerSocket server) {
        try {
            Socket socket = server.accept();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            while (!request.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
                int b = in.read();
                if (b < 0) {
                    throw new IllegalStateException("handshake truncated");
                }
                request.write(b);
            }
            Matcher key = KEY_HEADER.matcher(request.toString("ISO-8859-1"));
            if (!key.find()) {
                throw new IllegalStateException("no Sec-WebSocket-Key in " + request);
            }
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(
                    (key.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1));
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + Base64.getEncoder().encodeToString(digest) + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return socket;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A WebSocket whose output was closed by a connection reset the listener has not seen yet. */
    private static final class OutputClosedWebSocket implements WebSocket {

        private static <T> CompletableFuture<T> outputClosed() {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(new IOException("Output closed"));
            return future;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return outputClosed();
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return outputClosed();
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return outputClosed();
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return outputClosed();
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return outputClosed();
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return true;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }
    }
}
