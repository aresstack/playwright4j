package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public final class HostHttpResponse {

    private final int statusCode;
    private final String body;

    public HostHttpResponse(int statusCode, String body) {
        this.statusCode = statusCode;
        this.body = body;
    }

    @HostAccess.Export
    public int statusCode() {
        return statusCode;
    }

    @HostAccess.Export
    public String body() {
        return body;
    }
}
