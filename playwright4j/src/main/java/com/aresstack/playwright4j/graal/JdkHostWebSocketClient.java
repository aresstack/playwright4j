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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdkHostWebSocketClient implements HostWebSocketClient {

    private static final Pattern MESSAGE_ID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\d+)");

    private final HttpClient client;
    private final ExecutorService executor;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public JdkHostWebSocketClient() {
        this.executor = Executors.newCachedThreadPool(new DaemonThreadFactory("playwright4j-ws"));
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(executor)
                .build();
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public String open(String url) {
        Connection connection = new Connection();
        WebSocket webSocket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(url), connection)
                .join();
        String id = UUID.randomUUID().toString();
        connection.attach(webSocket);
        connections.put(id, connection);
        return id;
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public String sendAndWait(String connectionId, String message) {
        Connection connection = connection(connectionId);
        String requestId = messageId(message);
        connection.webSocket.sendText(message, true).join();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<String> messages = new ArrayList<>();

        while (System.nanoTime() < deadline) {
            String response = connection.poll(deadline);

            if (response == null) {
                break;
            }

            messages.add(response);

            if (requestId == null || requestId.equals(messageId(response))) {
                drainBriefly(connection, messages);
                return String.join("\u001e", messages);
            }
        }

        throw new IllegalStateException("Timed out waiting for WebSocket response to message id " + requestId);
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

        if (connection != null) {
            connection.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "playwright4j").join();
        }
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

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private int sequence;

        private DaemonThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, namePrefix + "-" + (++sequence));
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class Connection implements WebSocket.Listener {

        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private volatile WebSocket webSocket;

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
            messages.offer(data.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            messages.offer("{\"error\":{\"message\":" + quoteJson(error.getMessage()) + "}}");
        }

        private static String quoteJson(String value) {
            if (value == null) {
                return "null";
            }

            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
}
