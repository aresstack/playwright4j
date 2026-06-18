package com.aresstack.playwright4j.graal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class JdkHostHttpClient implements HostHttpClient {

    private final HttpClient client;

    public JdkHostHttpClient() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public HostHttpResponse request(String method, String url, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10));

        if (body == null || body.isEmpty()) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }

        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HostHttpResponse(response.statusCode(), response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("HTTP request failed: " + method + " " + url, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while performing HTTP request: " + method + " " + url, exception);
        }
    }
}
