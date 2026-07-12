package com.aresstack.playwright4j.graal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdkHostWebSocketClient implements HostWebSocketClient {

    private static final Pattern MESSAGE_ID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\d+)");

    private final HttpClient client;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public JdkHostWebSocketClient() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public String open(String url) {
        return open(url, null);
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public String open(String url, String headers) {
        Connection connection = new Connection();
        WebSocket.Builder builder = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5));
        applyHandshakeHeaders(builder, headers);
        WebSocket webSocket = builder
                .buildAsync(URI.create(url), connection)
                .join();
        String id = UUID.randomUUID().toString();
        connection.attach(webSocket);
        connections.put(id, connection);
        Playwright4JDebug.log("[pw4j-cdp] OPEN " + url);
        return id;
    }

    private static final String HEADER_SEPARATOR = "\u001e";

    private void applyHandshakeHeaders(WebSocket.Builder builder, String headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        String[] entries = headers.split(HEADER_SEPARATOR, -1);
        for (int index = 0; index + 1 < entries.length; index += 2) {
            String name = entries[index];
            String value = entries[index + 1];
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            try {
                builder.header(name, value == null ? "" : value);
            } catch (IllegalArgumentException ignored) {
                // The JDK forbids setting a few protocol-managed headers (e.g. Connection,
                // Upgrade, Sec-WebSocket-*); skip those rather than failing the connect.
                Playwright4JDebug.log("[pw4j-cdp] skipped handshake header " + name);
            }
        }
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public String sendAndWait(String connectionId, String message) {
        Connection connection = connection(connectionId);
        String requestId = messageId(message);
        Playwright4JDebug.log("[pw4j-cdp] SEND " + truncate(message));
        connection.webSocket.sendText(message, true).join();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<String> messages = new ArrayList<>();

        while (System.nanoTime() < deadline) {
            String response = connection.poll(deadline);

            if (response == null) {
                break;
            }

            messages.add(response);
            Playwright4JDebug.log("[pw4j-cdp] RECV " + truncate(response));

            if (requestId == null || requestId.equals(messageId(response))) {
                drainBriefly(connection, messages);
                return String.join("\u001e", messages);
            }
        }

        Playwright4JDebug.log("[pw4j-cdp] TIMEOUT waiting for id " + requestId);
        throw new IllegalStateException("Timed out waiting for WebSocket response to message id " + requestId);
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 300 ? message : message.substring(0, 300) + "...";
    }

    private static void drainBriefly(Connection connection, List<String> messages) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);

        while (System.nanoTime() < deadline) {
            String response = connection.poll(deadline);

            if (response == null) {
                return;
            }

            messages.add(response);
        }
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public void send(String connectionId, String message) {
        Connection connection = connection(connectionId);
        Playwright4JDebug.log("[pw4j-cdp] SEND " + truncate(message));
        connection.webSocket.sendText(message, true).join();
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public String drain(String connectionId, int milliseconds) {
        Connection connection = connection(connectionId);
        List<String> messages = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);

        while (System.nanoTime() < deadline) {
            String response = connection.poll(deadline);

            if (response == null) {
                break;
            }

            messages.add(response);
        }

        return String.join("\u001e", messages);
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public void close(String connectionId) {
        Connection connection = connections.remove(connectionId);

        if (connection != null && connection.webSocket != null) {
            try {
                connection.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "playwright4j")
                        .orTimeout(2, TimeUnit.SECONDS)
                        .exceptionally(throwable -> null)
                        .join();
            } catch (Exception ignored) {
                // The browser may have already torn down the socket; nothing to do.
            }
        }
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public boolean isClosed(String connectionId) {
        Connection connection = connections.get(connectionId);
        return connection == null || connection.closed;
    }

    private Connection connection(String connectionId) {
        Connection connection = connections.get(connectionId);

        if (connection == null) {
            throw new IllegalStateException("Unknown WebSocket connection: " + connectionId);
        }

        return connection;
    }

    private static String messageId(String message) {
        Matcher matcher = MESSAGE_ID_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static final class Connection implements WebSocket.Listener {

        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partialFrame = new StringBuilder();
        private volatile WebSocket webSocket;
        private volatile boolean closed;

        void attach(WebSocket webSocket) {
            this.webSocket = webSocket;
            this.webSocket.request(1);
        }

        String poll(long deadlineNanos) {
            long remainingNanos = deadlineNanos - System.nanoTime();

            if (remainingNanos <= 0) {
                return null;
            }

            try {
                return messages.poll(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for WebSocket message.", exception);
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialFrame.append(data);
            if (last) {
                messages.offer(partialFrame.toString());
                partialFrame.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // The peer closed the WebSocket (e.g. the launch-server was closed or killed). Mark the
            // connection closed so the transport can surface onclose -> Playwright fires disconnected.
            closed = true;
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed = true;
        }
    }
}
