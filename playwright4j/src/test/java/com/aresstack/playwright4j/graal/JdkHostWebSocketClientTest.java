package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

final class JdkHostWebSocketClientTest {

    private static final Pattern KEY_HEADER = Pattern.compile("(?im)^Sec-WebSocket-Key:\\s*(\\S+)\\s*$");

    /**
     * A launch-server that is killed (TerminateProcess on Windows) drops the TCP connection without a
     * close frame. Like Node's ws, sending on such a socket must not throw: the message is dropped and
     * the close is reported through isClosed(), so the transport surfaces onclose, Playwright emits
     * 'disconnected' and rejects the pending call, instead of the call failing with an I/O error
     * before the disconnect has been dispatched.
     */
    @Test
    void sendAfterAbruptPeerDisconnectDoesNotThrowAndReportsClosed() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Socket> accepted = CompletableFuture.supplyAsync(() -> acceptHandshake(server));
            JdkHostWebSocketClient client = new JdkHostWebSocketClient();
            String id = client.open("ws://127.0.0.1:" + server.getLocalPort() + "/");

            // Abort the connection: RST, no close frame.
            Socket peer = accepted.get(10, TimeUnit.SECONDS);
            peer.setSoLinger(true, 0);
            peer.close();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!client.isClosed(id) && System.nanoTime() < deadline) {
                assertDoesNotThrow(() -> client.send(id, "{\"id\":1,\"method\":\"Page.title\"}"));
                Thread.sleep(5);
            }
            assertTrue(client.isClosed(id), "abrupt peer disconnect was never reported");
            assertDoesNotThrow(() -> client.send(id, "{\"id\":2,\"method\":\"Page.title\"}"));

            client.close(id);
            assertDoesNotThrow(() -> client.send(id, "{\"id\":3,\"method\":\"Page.title\"}"));
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
}
