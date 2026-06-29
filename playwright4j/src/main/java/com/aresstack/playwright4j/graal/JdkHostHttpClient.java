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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class JdkHostHttpClient implements HostHttpClient {

    private static final String ENTRY_SEPARATOR = "";

    // java.net.http does not expose HTTP/1.1 reason phrases, so derive the common ones from
    // the status code. Playwright assertions depend on these (e.g. "404 Not Found").
    private static final Map<Integer, String> REASON_PHRASES = buildReasonPhrases();

    // Headers java.net.http forbids callers from setting; skipping them avoids exceptions.
    private static final List<String> RESTRICTED_HEADERS = List.of(
            "connection", "content-length", "host", "upgrade",
            "transfer-encoding", "expect", "date");

    // Safety net so an in-flight request the guest never polls/cancels cannot leak forever.
    private static final Duration MAX_REQUEST_DURATION = Duration.ofSeconds(120);

    private final HttpClient client;
    private final JdkTlsClientFactory tlsClientFactory = new JdkTlsClientFactory();
    private final Map<String, CompletableFuture<HostHttpResponse>> pendingRequests =
            new ConcurrentHashMap<String, CompletableFuture<HostHttpResponse>>();

    public JdkHostHttpClient() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public HostHttpResponse request(String method, String url, String headers, String bodyBase64) {
        try {
            HttpResponse<byte[]> response = client.send(buildRequest(method, url, headers, bodyBase64),
                    HttpResponse.BodyHandlers.ofByteArray());
            return toResponse(response);
        } catch (IOException exception) {
            return mapNetworkError(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return HostHttpResponse.error("Request interrupted", "ECONNRESET");
        }
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public String startRequest(String method, String url, String headers, String bodyBase64, String tlsOptions) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<HostHttpResponse> future;
        try {
            HttpClient requestClient = tlsClientFactory.clientFor(tlsOptions, client);
            future = requestClient.sendAsync(buildRequest(method, url, headers, bodyBase64),
                            HttpResponse.BodyHandlers.ofByteArray())
                    .handle((response, throwable) -> {
                        if (throwable != null) {
                            return mapThrowable(throwable);
                        }
                        return toResponse(response);
                    });
        } catch (RuntimeException exception) {
            // e.g. IllegalArgumentException for a malformed URI: surface as a network error.
            future = CompletableFuture.completedFuture(
                    HostHttpResponse.error(exception.getMessage() == null ? "network error" : exception.getMessage(), "ECONNRESET"));
        }
        pendingRequests.put(requestId, future);
        return requestId;
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public HostHttpResponse pollRequest(String requestId) {
        CompletableFuture<HostHttpResponse> future = pendingRequests.get(requestId);
        if (future == null || !future.isDone()) {
            return null;
        }
        pendingRequests.remove(requestId);
        try {
            return future.get();
        } catch (Exception exception) {
            return HostHttpResponse.error(exception.getMessage() == null ? "network error" : exception.getMessage(), "ECONNRESET");
        }
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public void cancelRequest(String requestId) {
        CompletableFuture<HostHttpResponse> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.cancel(true);
        }
    }

    private HttpRequest buildRequest(String method, String url, String headers, String bodyBase64) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(MAX_REQUEST_DURATION);

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
        return builder.build();
    }

    private HostHttpResponse toResponse(HttpResponse<byte[]> response) {
        Playwright4JDebug.log("[pw4j-http] " + response.uri() + " -> " + response.statusCode()
                + " bodyBytes=" + (response.body() == null ? 0 : response.body().length));
        return new HostHttpResponse(
                response.statusCode(),
                reasonPhrase(response.statusCode()),
                response.body(),
                flattenHeaders(response));
    }

    private HostHttpResponse mapThrowable(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null && !(cause instanceof IOException) && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof IOException) {
            return mapNetworkError((IOException) cause);
        }
        String message = throwable.getMessage() == null ? "network error" : throwable.getMessage();
        Playwright4JDebug.log("[pw4j-http] ERROR " + throwable.getClass().getName() + ": " + message);
        return HostHttpResponse.error(message, "ECONNRESET");
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
