package com.aresstack.playwright4j.graal;

public final class NoopHostDriverPipe implements HostDriverPipe {

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public boolean writeOut(String chunk) {
        return true;
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public boolean writeErr(String chunk) {
        return true;
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public boolean writeMessage(String message) {
        return true;
    }
}
