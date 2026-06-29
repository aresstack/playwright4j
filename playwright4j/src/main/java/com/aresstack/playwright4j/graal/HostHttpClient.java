package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public interface HostHttpClient {

    /**
     * Performs an HTTP request.
     *
     * @param method  the HTTP method
     * @param url     the absolute request URL
     * @param headers request headers as a flat array of alternating name / value entries
     *                joined by {@code }; may be empty
     * @param body    the request body, or an empty string for none
     */
    @HostAccess.Export
    HostHttpResponse request(String method, String url, String headers, String body);

    /**
     * Starts a non-blocking HTTP request and returns an opaque request id. The result is
     * retrieved later via {@link #pollRequest(String)} so the single GraalJS thread is never
     * blocked and Playwright's progress timeouts can fire while the request is in flight.
     */
    @HostAccess.Export
    String startRequest(String method, String url, String headers, String body, String tlsOptions);

    /**
     * Returns the completed {@link HostHttpResponse} for the given request id, or {@code null}
     * if it is still in flight. The response carries either the result or a Node-style error.
     */
    @HostAccess.Export
    HostHttpResponse pollRequest(String requestId);

    /**
     * Cancels an in-flight request (e.g. when Playwright aborts it on timeout).
     */
    @HostAccess.Export
    void cancelRequest(String requestId);
}
