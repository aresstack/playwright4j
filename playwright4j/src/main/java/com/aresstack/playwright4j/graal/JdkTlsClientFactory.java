package com.aresstack.playwright4j.graal;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.security.Principal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds (and caches) {@link HttpClient}s configured with a client certificate and/or relaxed
 * trust, derived from the Node https TLS options Playwright sets for APIRequestContext client
 * certificates. The TLS options arrive as a small JSON object with Base64 fields:
 * {@code {"rejectUnauthorized":bool,"pfx":..,"passphrase":..,"cert":..,"key":..,"ca":..}}.
 * cert/key are Base64 of the PEM text (PKCS#8 key); pfx is Base64 of the PKCS12 bytes.
 */
final class JdkTlsClientFactory {

    private static final char[] IN_MEMORY_PASSWORD = "playwright4j".toCharArray();

    private final Map<String, HttpClient> cache = new ConcurrentHashMap<String, HttpClient>();

    HttpClient clientFor(String tlsOptions, HttpClient defaultClient) {
        if (tlsOptions == null || tlsOptions.isEmpty()) {
            return defaultClient;
        }
        HttpClient cached = cache.get(tlsOptions);
        if (cached != null) {
            return cached;
        }
        try {
            HttpClient built = build(tlsOptions);
            cache.put(tlsOptions, built);
            return built;
        } catch (Exception exception) {
            System.err.println("[playwright4j-tls] Failed to apply client certificate options: " + exception);
            return defaultClient;
        }
    }

    /**
     * Builds an {@link SSLContext} from the same Base64 TLS-options JSON used by the APIRequestContext
     * client. Reused by the BrowserContext client-certificate TLS-MITM engine ({@link JdkHostTls}).
     * Forces presentation of the configured client certificate, and trusts all servers when
     * rejectUnauthorized is false.
     */
    SSLContext buildSslContext(String tlsOptions) throws Exception {
        boolean rejectUnauthorized = jsonBoolean(tlsOptions, "rejectUnauthorized", true);
        KeyManager[] keyManagers = buildKeyManagers(tlsOptions);

        // Force presentation of the configured client certificate regardless of the server's
        // accepted-CA list. Node always presents the configured cert (so a self-signed client cert
        // reaches the server and is rejected at the application layer with 403); the default Java
        // KeyManager would instead withhold it, yielding 401.
        if (keyManagers != null) {
            for (int index = 0; index < keyManagers.length; index++) {
                if (keyManagers[index] instanceof X509ExtendedKeyManager) {
                    keyManagers[index] = new ForcingKeyManager((X509ExtendedKeyManager) keyManagers[index]);
                }
            }
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");
        TrustManager[] trustManagers = rejectUnauthorized ? null : new TrustManager[] { TRUST_ALL };
        sslContext.init(keyManagers, trustManagers, null);
        return sslContext;
    }

    static boolean rejectUnauthorizedOf(String tlsOptions) {
        return jsonBoolean(tlsOptions, "rejectUnauthorized", true);
    }

    private HttpClient build(String tlsOptions) throws Exception {
        boolean rejectUnauthorized = jsonBoolean(tlsOptions, "rejectUnauthorized", true);
        SSLContext sslContext = buildSslContext(tlsOptions);

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .sslContext(sslContext);

        if (!rejectUnauthorized) {
            // Trust-all also requires skipping hostname verification (ignoreHTTPSErrors).
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm(null);
            builder.sslParameters(parameters);
        }
        return builder.build();
    }

    private KeyManager[] buildKeyManagers(String tlsOptions) throws Exception {
        String pfx = jsonString(tlsOptions, "pfx");
        String passphrase = jsonString(tlsOptions, "passphrase");
        if (pfx != null) {
            char[] password = passphrase != null ? passphrase.toCharArray() : new char[0];
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new ByteArrayInputStream(Base64.getDecoder().decode(pfx)), password);
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore, password);
            return factory.getKeyManagers();
        }

        String cert = jsonString(tlsOptions, "cert");
        String key = jsonString(tlsOptions, "key");
        if (cert == null || key == null) {
            return null;
        }
        X509Certificate[] chain = parseCertificateChain(Base64.getDecoder().decode(cert));
        PrivateKey privateKey = parsePrivateKey(new String(Base64.getDecoder().decode(key), StandardCharsets.UTF_8));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, IN_MEMORY_PASSWORD);
        keyStore.setKeyEntry("client", privateKey, IN_MEMORY_PASSWORD, chain);
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, IN_MEMORY_PASSWORD);
        return factory.getKeyManagers();
    }

    private static X509Certificate[] parseCertificateChain(byte[] pemBytes) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certificates =
                factory.generateCertificates(new ByteArrayInputStream(pemBytes));
        List<X509Certificate> chain = new ArrayList<X509Certificate>();
        for (Certificate certificate : certificates) {
            chain.add((X509Certificate) certificate);
        }
        return chain.toArray(new X509Certificate[0]);
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String body = pem
                .replaceAll("-----BEGIN (?:RSA |EC )?PRIVATE KEY-----", "")
                .replaceAll("-----END (?:RSA |EC )?PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String algorithm : new String[] { "RSA", "EC", "DSA" }) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (Exception ignored) {
                // Try the next algorithm.
            }
        }
        throw new IllegalStateException("Unsupported private key format (expected PKCS#8)");
    }

    // Minimal extraction for the known, Base64/boolean-valued TLS option fields.
    private static String jsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    private static boolean jsonBoolean(String json, String key, boolean fallback) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            return fallback;
        }
        start += marker.length();
        return json.startsWith("true", start);
    }

    /**
     * Delegating key manager that always offers the configured client certificate, ignoring the
     * server's accepted-issuer list (which the default Java key manager honours, withholding a
     * self-signed client cert).
     */
    private static final class ForcingKeyManager extends X509ExtendedKeyManager {

        private final X509ExtendedKeyManager delegate;

        private ForcingKeyManager(X509ExtendedKeyManager delegate) {
            this.delegate = delegate;
        }

        private String forcedAlias(String[] keyTypes) {
            if (keyTypes == null) {
                return null;
            }
            for (String keyType : keyTypes) {
                String[] aliases = delegate.getClientAliases(keyType, null);
                if (aliases != null && aliases.length > 0) {
                    return aliases[0];
                }
            }
            return null;
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            String alias = forcedAlias(keyTypes);
            return alias != null ? alias : delegate.chooseClientAlias(keyTypes, issuers, socket);
        }

        @Override
        public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
            String alias = forcedAlias(keyTypes);
            return alias != null ? alias : delegate.chooseEngineClientAlias(keyTypes, issuers, engine);
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return delegate.getClientAliases(keyType, issuers);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return delegate.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return delegate.chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return delegate.chooseEngineServerAlias(keyType, issuers, engine);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return delegate.getCertificateChain(alias);
        }

        @Override
        public java.security.PrivateKey getPrivateKey(String alias) {
            return delegate.getPrivateKey(alias);
        }
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
