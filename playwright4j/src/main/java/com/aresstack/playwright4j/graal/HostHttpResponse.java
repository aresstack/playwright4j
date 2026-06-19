package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public final class HostHttpResponse {

    private final int statusCode;
    private final String statusText;
    private final String body;
    private final String[] rawHeaders;

    public HostHttpResponse(int statusCode, String statusText, String body, String[] rawHeaders) {
        this.statusCode = statusCode;
        this.statusText = statusText;
        this.body = body;
        this.rawHeaders = rawHeaders;
    }

    @HostAccess.Export
    public int statusCode() {
        return statusCode;
    }

    @HostAccess.Export
    public String statusText() {
        return statusText;
    }

    @HostAccess.Export
    public String body() {
        return body;
    }

    /**
     * Response headers as a flat array of alternating lower-case name / value entries,
     * matching Node's {@code rawHeaders} convention closely enough for the bundled fetch.
     */
    @HostAccess.Export
    public String[] rawHeaders() {
        return rawHeaders;
    }
}
