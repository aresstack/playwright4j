package com.aresstack.playwright4j.graal;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The proxy of a Node request's agent (Playwright's {@code proxy} option for APIRequestContext).
 * java.net.http only speaks plain HTTP to a proxy, so SOCKS and https:// proxies are rejected
 * rather than sending the request directly.
 */
final class ProxyServer {

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    private ProxyServer(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    static ProxyServer parse(String proxy) {
        URI uri = URI.create(proxy);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme)) {
            throw new IllegalArgumentException("Proxy scheme \"" + scheme + ":\" is not supported for API requests; use an http:// proxy");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid proxy server: " + uri.getScheme() + "://" + uri.getRawAuthority());
        }
        String rawUserInfo = uri.getRawUserInfo();
        String username = null;
        String password = null;
        if (rawUserInfo != null) {
            int colon = rawUserInfo.indexOf(':');
            username = decode(colon < 0 ? rawUserInfo : rawUserInfo.substring(0, colon));
            password = colon < 0 ? "" : decode(rawUserInfo.substring(colon + 1));
        }
        return new ProxyServer(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 80, username, password);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    String key() {
        return host + ":" + port + "\n" + username + "\n" + password;
    }

    void configure(HttpClient.Builder builder) {
        builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(host, port)));
        if (username != null) {
            // Answers the proxy's 407, for plain requests and the CONNECT for https targets alike.
            // Playwright sends the same Basic credentials, but preemptively.
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return getRequestorType() == RequestorType.PROXY
                            ? new PasswordAuthentication(username, password.toCharArray())
                            : null;
                }
            });
        }
    }
}
