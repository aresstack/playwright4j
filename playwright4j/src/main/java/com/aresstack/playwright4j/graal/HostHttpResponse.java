package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class HostHttpResponse {

    private final int statusCode;
    private final String statusText;
    private final String bodyBase64;
    private final String rawHeaders;

    public HostHttpResponse(int statusCode, String statusText, byte[] body, String rawHeaders) {
        this.statusCode = statusCode;
        this.statusText = statusText;
        this.bodyBase64 = Base64.getEncoder().encodeToString(body == null ? new byte[0] : body);
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

    /**
     * The raw response body as a Base64 string. The JS runtime decodes it into a Node-style
     * Buffer so binary bodies survive the interop boundary intact.
     */
    @HostAccess.Export
    public String bodyBase64() {
        return bodyBase64;
    }

    /**
     * Convenience UTF-8 view of the body, for callers that only need text.
     */
    @HostAccess.Export
    public String body() {
        return new String(Base64.getDecoder().decode(bodyBase64), StandardCharsets.UTF_8);
    }

    /**
     * Response headers as alternating lower-case name / value entries joined by U+001E.
     * A delimited string is used instead of an array because GraalJS does not reliably
     * expose {@code .length} on a Java {@code String[]} to the guest.
     */
    @HostAccess.Export
    public String rawHeaders() {
        return rawHeaders;
    }
}
