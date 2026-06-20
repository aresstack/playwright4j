package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-backed TCP server/socket facility for Node's net.createServer, used by Playwright's
 * BrowserServer (browser.bind). All socket I/O happens on dedicated threads; events are pushed
 * onto a queue and delivered to the single GraalJS thread via {@link #drainEvents()} from the
 * driver pump, never by calling into the guest directly.
 */
public final class JdkHostNetServer {

    private static final char UNIT = '';

    private final Map<String, ServerSocket> servers = new ConcurrentHashMap<String, ServerSocket>();
    private final Map<String, Socket> sockets = new ConcurrentHashMap<String, Socket>();
    private final Map<String, OutputStream> socketOutputs = new ConcurrentHashMap<String, OutputStream>();
    private final BlockingQueue<String> events = new LinkedBlockingQueue<String>();
    private final AtomicLong ids = new AtomicLong(1);

    /**
     * Binds a TCP server to host:port (port 0 = ephemeral). Returns "serverIdactualPort" so
     * the caller can build its endpoint. Accepts connections on a background thread.
     */
    @HostAccess.Export
    public String listen(String host, int port) {
        try {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(host == null || host.isEmpty() ? "127.0.0.1" : host, port));
            String serverId = "server-" + ids.getAndIncrement();
            servers.put(serverId, serverSocket);
            startAcceptThread(serverId, serverSocket);
            return serverId + UNIT + serverSocket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot bind net server to " + host + ":" + port, exception);
        }
    }

    @HostAccess.Export
    public void closeServer(String serverId) {
        ServerSocket serverSocket = servers.remove(serverId);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    @HostAccess.Export
    public void write(String socketId, String base64) {
        OutputStream output = socketOutputs.get(socketId);
        if (output == null) {
            return;
        }
        try {
            output.write(Base64.getDecoder().decode(base64));
            output.flush();
        } catch (IOException exception) {
            closeSocket(socketId);
        }
    }

    @HostAccess.Export
    public void closeSocket(String socketId) {
        Socket socket = sockets.remove(socketId);
        socketOutputs.remove(socketId);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    /** Drains pending socket events as a single newline-separated batch, or "" if none. */
    @HostAccess.Export
    public String drainEvents() {
        if (events.isEmpty()) {
            return "";
        }
        StringBuilder batch = new StringBuilder();
        String event;
        while ((event = events.poll()) != null) {
            if (batch.length() > 0) {
                batch.append('\n');
            }
            batch.append(event);
        }
        return batch.toString();
    }

    public void closeAll() {
        for (String serverId : new java.util.ArrayList<String>(servers.keySet())) {
            closeServer(serverId);
        }
        for (String socketId : new java.util.ArrayList<String>(sockets.keySet())) {
            closeSocket(socketId);
        }
    }

    private void startAcceptThread(final String serverId, final ServerSocket serverSocket) {
        Thread acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        String socketId = "socket-" + ids.getAndIncrement();
                        sockets.put(socketId, socket);
                        socketOutputs.put(socketId, socket.getOutputStream());
                        // event: connection<UNIT>serverId<UNIT>socketId
                        events.add("connection" + UNIT + serverId + UNIT + socketId);
                        startReadThread(socketId, socket);
                    } catch (IOException exception) {
                        return;
                    }
                }
            }
        }, "playwright4j-net-accept-" + serverId);
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void startReadThread(final String socketId, final Socket socket) {
        Thread readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[8192];
                try (InputStream input = socket.getInputStream()) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        String base64 = Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(buffer, count));
                        // event: data<UNIT>socketId<UNIT>base64
                        events.add("data" + UNIT + socketId + UNIT + base64);
                    }
                } catch (IOException ignored) {
                    // Connection closed.
                } finally {
                    events.add("close" + UNIT + socketId);
                    closeSocket(socketId);
                }
            }
        }, "playwright4j-net-read-" + socketId);
        readThread.setDaemon(true);
        readThread.start();
    }
}
