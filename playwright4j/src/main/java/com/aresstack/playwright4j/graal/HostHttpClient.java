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
}
