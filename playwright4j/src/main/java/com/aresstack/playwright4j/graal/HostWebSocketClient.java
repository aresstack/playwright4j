package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public interface HostWebSocketClient {

    @HostAccess.Export
    String open(String url);

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
}
