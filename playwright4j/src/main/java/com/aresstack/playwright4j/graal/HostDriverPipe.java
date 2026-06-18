package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public interface HostDriverPipe {

    @HostAccess.Export
    boolean writeOut(String chunk);

    @HostAccess.Export
    boolean writeErr(String chunk);

    @HostAccess.Export
    boolean writeMessage(String message);
}
