package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public interface HostWebSocketClient {

    @HostAccess.Export
    String open(String url);

    /**
     * Opens a WebSocket, applying the given request headers to the opening handshake. Headers are a
     * flat string of alternating name / value entries joined by the U+001E record separator (may
     * be empty). Used
     * by browserType.connect/connectOverCDP, which forward caller-supplied headers (e.g. a custom
     * User-Agent or x-playwright-* headers) into the handshake.
     */
    @HostAccess.Export
    String open(String url, String headers);

    @HostAccess.Export
    String sendAndWait(String connectionId, String message);

    /**
     * Sends a message without waiting for a response. The response (and any other incoming
     * messages) are retrieved later via {@link #drain(String, int)}, one at a time, so the
     * guest can fully drain its microtask queue between messages (mirroring Node's pipe).
     */
    @HostAccess.Export
    void send(String connectionId, String message);

    @HostAccess.Export
    String drain(String connectionId, int milliseconds);

    @HostAccess.Export
    void close(String connectionId);

    /** Whether the peer has closed the connection or it errored (so the transport can emit onclose). */
    @HostAccess.Export
    boolean isClosed(String connectionId);
}
