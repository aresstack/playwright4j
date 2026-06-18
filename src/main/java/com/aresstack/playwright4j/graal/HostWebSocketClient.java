package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public interface HostWebSocketClient {

    @HostAccess.Export
    String open(String url);

    @HostAccess.Export
    String sendAndWait(String connectionId, String message);

    @HostAccess.Export
    String drain(String connectionId, int milliseconds);

    @HostAccess.Export
    void close(String connectionId);
}
