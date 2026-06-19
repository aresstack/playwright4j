package com.aresstack.playwright4j.graal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class JdkHostHttpClient implements HostHttpClient {

    private static final String ENTRY_SEPARATOR = "";

    // java.net.http does not expose HTTP/1.1 reason phrases, so derive the common ones from
    // the status code. Playwright assertions depend on these (e.g. "404 Not Found").
    private static final Map<Integer, String> REASON_PHRASES = buildReasonPhrases();

    // Headers java.net.http forbids callers from setting; skipping them avoids exceptions.
    // accept-encoding is skipped on purpose so responses arrive uncompressed (the runtime's
    // zlib shim does not decompress).
    private static final List<String> RESTRICTED_HEADERS = List.of(
            "connection", "content-length", "host", "upgrade", "accept-encoding",
            "transfer-encoding", "expect", "date");

    private final HttpClient client;

    public JdkHostHttpClient() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public HostHttpResponse request(String method, String url, String headers, String bodyBase64) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30));

        applyHeaders(builder, headers);

        // The body arrives Base64-encoded so binary/form/multipart payloads survive intact.
        byte[] body = bodyBase64 == null || bodyBase64.isEmpty()
                ? new byte[0]
                : Base64.getDecoder().decode(bodyBase64);
        if (body.length == 0) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }

        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Playwright4JDebug.log("[pw4j-http] " + method + " " + url + " -> " + response.statusCode()
                    + " ct=" + response.headers().firstValue("content-type").orElse("")
                    + " bodyBytes=" + (response.body() == null ? 0 : response.body().length));
            return new HostHttpResponse(
                    response.statusCode(),
                    reasonPhrase(response.statusCode()),
                    response.body(),
                    flattenHeaders(response));
        } catch (IOException exception) {
            Playwright4JDebug.log("[pw4j-http] ERROR " + method + " " + url + " -> "
                    + exception.getClass().getName() + ": " + exception.getMessage());
            return mapNetworkError(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return HostHttpResponse.error("Request interrupted", "ECONNRESET");
        }
    }

    // Translates a Java HTTP failure into the Node-style error the bundled fetch expects, so
    // Playwright rejects with a meaningful message and retries on ECONNRESET.
    private HostHttpResponse mapNetworkError(IOException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);

        if (exception instanceof java.net.http.HttpTimeoutException) {
            return HostHttpResponse.error("timeout", "ETIMEDOUT");
        }
        if (exception instanceof java.net.ConnectException || lower.contains("connection refused")) {
            return HostHttpResponse.error("connect ECONNREFUSED", "ECONNREFUSED");
        }
        if (exception instanceof java.net.UnknownHostException || lower.contains("unknown host") || lower.contains("no such host")) {
            return HostHttpResponse.error("getaddrinfo ENOTFOUND", "ENOTFOUND");
        }
        // Server closed the connection before sending a response: Node reports "socket hang up".
        if (lower.contains("no bytes") || lower.contains("goaway") || lower.contains("connection reset")
                || lower.contains("connection was closed") || lower.contains("eof")) {
            return HostHttpResponse.error("socket hang up", "ECONNRESET");
        }
        // Response truncated mid-body: Node reports the request was "aborted".
        if (lower.contains("fixed content-length") || lower.contains("bytes received")
                || lower.contains("premature") || lower.contains("cancelled")) {
            return HostHttpResponse.error("aborted", "ECONNRESET");
        }
        return HostHttpResponse.error(message.isEmpty() ? "network error" : message, "ECONNRESET");
    }

    private void applyHeaders(HttpRequest.Builder builder, String headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }

        String[] entries = headers.split(ENTRY_SEPARATOR, -1);
        for (int index = 0; index + 1 < entries.length; index += 2) {
            String name = entries[index];
            String value = entries[index + 1];
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            try {
                builder.header(name, value == null ? "" : value);
            } catch (IllegalArgumentException ignored) {
                // java.net.http rejected the header; skip it rather than fail the request.
            }
        }
    }

    private String flattenHeaders(HttpResponse<?> response) {
        List<String> flattened = new ArrayList<String>();
        for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
            for (String value : entry.getValue()) {
                flattened.add(entry.getKey().toLowerCase(Locale.ROOT));
                flattened.add(value);
            }
        }
        return String.join(ENTRY_SEPARATOR, flattened);
    }

    private String reasonPhrase(int statusCode) {
        String phrase = REASON_PHRASES.get(statusCode);
        return phrase != null ? phrase : "";
    }

    private static Map<Integer, String> buildReasonPhrases() {
        Map<Integer, String> phrases = new java.util.HashMap<Integer, String>();
        phrases.put(200, "OK");
        phrases.put(201, "Created");
        phrases.put(202, "Accepted");
        phrases.put(204, "No Content");
        phrases.put(301, "Moved Permanently");
        phrases.put(302, "Found");
        phrases.put(303, "See Other");
        phrases.put(304, "Not Modified");
        phrases.put(307, "Temporary Redirect");
        phrases.put(308, "Permanent Redirect");
        phrases.put(400, "Bad Request");
        phrases.put(401, "Unauthorized");
        phrases.put(403, "Forbidden");
        phrases.put(404, "Not Found");
        phrases.put(405, "Method Not Allowed");
        phrases.put(408, "Request Timeout");
        phrases.put(409, "Conflict");
        phrases.put(410, "Gone");
        phrases.put(500, "Internal Server Error");
        phrases.put(501, "Not Implemented");
        phrases.put(502, "Bad Gateway");
        phrases.put(503, "Service Unavailable");
        phrases.put(504, "Gateway Timeout");
        return phrases;
    }
}
