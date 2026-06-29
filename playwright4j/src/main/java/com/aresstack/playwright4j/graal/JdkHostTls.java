package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SNIHostName;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-backed TLS record layer (via {@link SSLEngine}) used by Playwright's BrowserContext
 * client-certificate support (socksClientCertificatesInterceptor.js). The interceptor runs a local
 * SOCKS5 proxy and, per connection, terminates the browser's TLS with a dummy self-signed server
 * cert and re-originates TLS to the real server presenting the client certificate. We supply the
 * Node tls primitives those handshakes need.
 *
 * Each engine is a pure, synchronous byte transform: {@link #pump} feeds bytes received from the
 * peer (driving the handshake and decrypting application data) and {@link #wrap} encrypts
 * application data to send. No threads are involved, so this fits the single-threaded driver pump.
 * Results are returned as small JSON objects with Base64 byte fields.
 */
public final class JdkHostTls {

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final JdkTlsClientFactory tlsFactory = new JdkTlsClientFactory();
    private final Map<String, Engine> engines = new ConcurrentHashMap<String, Engine>();
    private final AtomicLong ids = new AtomicLong(1);

    private static final class Engine {
        final SSLEngine engine;
        byte[] pending = new byte[0]; // encrypted bytes received but not yet consumed by unwrap
        boolean established;
        boolean closed;

        Engine(SSLEngine engine) {
            this.engine = engine;
        }
    }

    @HostAccess.Export
    public String newClientEngine(String tlsOptions, String servername, String alpnCsv, boolean rejectUnauthorized) {
        try {
            // The effective rejectUnauthorized comes from the tls.connect options (ignoreHTTPSErrors),
            // not from the secureContext (whose tls-options JSON carries the client cert but defaults
            // rejectUnauthorized to true). Force the requested value so trust-all is applied when the
            // context was created with ignoreHTTPSErrors.
            SSLContext context = tlsFactory.buildSslContext(withRejectUnauthorized(tlsOptions, rejectUnauthorized));
            SSLEngine engine = context.createSSLEngine();
            engine.setUseClientMode(true);
            SSLParameters parameters = engine.getSSLParameters();
            applyAlpn(parameters, alpnCsv);
            if (servername != null && !servername.isEmpty()) {
                parameters.setServerNames(List.<javax.net.ssl.SNIServerName>of(new SNIHostName(servername)));
            }
            parameters.setEndpointIdentificationAlgorithm(rejectUnauthorized ? "HTTPS" : null);
            engine.setSSLParameters(parameters);
            engine.beginHandshake();
            String id = register(engine);
            Playwright4JDebug.log("[pw4j-tls] client engine " + id + " sni=" + servername + " alpn=" + alpnCsv + " reject=" + rejectUnauthorized);
            return id;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create TLS client engine: " + exception.getMessage(), exception);
        }
    }

    @HostAccess.Export
    public String newServerEngine(String keyPem, String certPem, String alpnCsv) {
        try {
            SSLContext context = buildServerContext(keyPem, certPem);
            SSLEngine engine = context.createSSLEngine();
            engine.setUseClientMode(false);
            engine.setNeedClientAuth(false);
            engine.setWantClientAuth(false);
            SSLParameters parameters = engine.getSSLParameters();
            applyAlpn(parameters, alpnCsv);
            engine.setSSLParameters(parameters);
            engine.beginHandshake();
            String id = register(engine);
            Playwright4JDebug.log("[pw4j-tls] server engine " + id + " alpn=" + alpnCsv);
            return id;
        } catch (Exception exception) {
            Playwright4JDebug.log("[pw4j-tls] server engine FAILED: " + exception);
            throw new IllegalStateException("Cannot create TLS server engine: " + exception.getMessage(), exception);
        }
    }

    /**
     * Feeds encrypted bytes received from the peer. Drives the handshake and decrypts any
     * application data. Returns {@code {"net":..,"app":..,"established":..,"alpn":..,"closed":..}}
     * where net = encrypted bytes to send to the peer, app = decrypted application bytes.
     */
    @HostAccess.Export
    public String pump(String engineId, String base64Inbound) {
        Engine state = engines.get(engineId);
        if (state == null) {
            return error("unknown engine");
        }
        try {
            if (base64Inbound != null && !base64Inbound.isEmpty()) {
                state.pending = concat(state.pending, Base64.getDecoder().decode(base64Inbound));
            }
            ByteArrayOutputStream net = new ByteArrayOutputStream();
            ByteArrayOutputStream app = new ByteArrayOutputStream();
            ByteBuffer inbound = ByteBuffer.wrap(state.pending);

            boolean progress = true;
            while (progress && !state.closed) {
                progress = false;
                HandshakeStatus status = state.engine.getHandshakeStatus();

                if (status == HandshakeStatus.NEED_TASK) {
                    Runnable task;
                    while ((task = state.engine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    progress = true;
                } else if (status == HandshakeStatus.NEED_WRAP) {
                    SSLEngineResult result = wrapInto(state, EMPTY, net);
                    progress = afterResult(state, result);
                } else if (status == HandshakeStatus.NEED_UNWRAP || status == HandshakeStatus.NEED_UNWRAP_AGAIN
                        || (state.established && inbound.hasRemaining())) {
                    if (!inbound.hasRemaining()) {
                        break; // need more bytes from peer
                    }
                    SSLEngineResult result = unwrapInto(state, inbound, app);
                    if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        break; // incomplete record; wait for more inbound
                    }
                    progress = afterResult(state, result);
                }
            }

            // Keep whatever encrypted bytes we could not yet consume for the next pump.
            byte[] leftover = new byte[inbound.remaining()];
            inbound.get(leftover);
            state.pending = leftover;

            return result(net, app, state);
        } catch (Exception exception) {
            state.closed = true;
            String detail;
            try {
                javax.net.ssl.SSLSession session = state.engine.getSession();
                detail = " proto=" + session.getProtocol() + " cipher=" + session.getCipherSuite();
            } catch (Exception ignored) {
                detail = "";
            }
            Playwright4JDebug.log("[pw4j-tls] pump " + engineId + " ERROR " + exception + detail);
            return error(exception.getMessage() == null ? "tls error" : exception.getMessage());
        }
    }

    /** Encrypts application bytes to send to the peer. Returns {@code {"net":..,"closed":..}}. */
    @HostAccess.Export
    public String wrap(String engineId, String base64App) {
        Engine state = engines.get(engineId);
        if (state == null) {
            return error("unknown engine");
        }
        try {
            ByteArrayOutputStream net = new ByteArrayOutputStream();
            ByteBuffer source = ByteBuffer.wrap(base64App == null || base64App.isEmpty()
                    ? new byte[0] : Base64.getDecoder().decode(base64App));
            do {
                wrapInto(state, source, net);
            } while (source.hasRemaining() && !state.closed);
            return result(net, new ByteArrayOutputStream(), state);
        } catch (Exception exception) {
            state.closed = true;
            return error(exception.getMessage() == null ? "tls error" : exception.getMessage());
        }
    }

    @HostAccess.Export
    public void closeEngine(String engineId) {
        Engine state = engines.remove(engineId);
        if (state != null) {
            try {
                state.engine.closeOutbound();
            } catch (Exception ignored) {
                // Best effort.
            }
        }
    }

    // --- internals ---

    private static String withRejectUnauthorized(String tlsOptions, boolean rejectUnauthorized) {
        if (tlsOptions == null || tlsOptions.isEmpty()) {
            return "{\"rejectUnauthorized\":" + rejectUnauthorized + "}";
        }
        String stripped = tlsOptions.trim();
        // Drop any existing rejectUnauthorized entry (with its adjacent comma, whichever side), then
        // inject the requested one.
        stripped = stripped
                .replaceAll("\"rejectUnauthorized\"\\s*:\\s*(true|false)\\s*,", "")
                .replaceAll(",\\s*\"rejectUnauthorized\"\\s*:\\s*(true|false)", "")
                .replaceAll("\"rejectUnauthorized\"\\s*:\\s*(true|false)", "");
        if (stripped.equals("{}") || stripped.equals("{ }")) {
            return "{\"rejectUnauthorized\":" + rejectUnauthorized + "}";
        }
        return stripped.substring(0, stripped.length() - 1)
                + ",\"rejectUnauthorized\":" + rejectUnauthorized + "}";
    }

    private String register(SSLEngine engine) {
        String id = "tls-" + ids.getAndIncrement();
        engines.put(id, new Engine(engine));
        return id;
    }

    private SSLEngineResult wrapInto(Engine state, ByteBuffer source, ByteArrayOutputStream net) throws Exception {
        ByteBuffer out = ByteBuffer.allocate(state.engine.getSession().getPacketBufferSize());
        SSLEngineResult result = state.engine.wrap(source, out);
        out.flip();
        byte[] bytes = new byte[out.remaining()];
        out.get(bytes);
        net.write(bytes, 0, bytes.length);
        if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
            state.closed = true;
        }
        return result;
    }

    private SSLEngineResult unwrapInto(Engine state, ByteBuffer inbound, ByteArrayOutputStream app) throws Exception {
        ByteBuffer out = ByteBuffer.allocate(state.engine.getSession().getApplicationBufferSize());
        SSLEngineResult result = state.engine.unwrap(inbound, out);
        if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            out = ByteBuffer.allocate(state.engine.getSession().getApplicationBufferSize() * 2);
            result = state.engine.unwrap(inbound, out);
        }
        out.flip();
        byte[] bytes = new byte[out.remaining()];
        out.get(bytes);
        app.write(bytes, 0, bytes.length);
        if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
            state.closed = true;
        }
        return result;
    }

    private boolean afterResult(Engine state, SSLEngineResult result) {
        if (result.getHandshakeStatus() == HandshakeStatus.FINISHED
                || state.engine.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING) {
            state.established = true;
        }
        return result.getStatus() != SSLEngineResult.Status.BUFFER_UNDERFLOW;
    }

    private static void applyAlpn(SSLParameters parameters, String alpnCsv) {
        if (alpnCsv == null || alpnCsv.isEmpty()) {
            return;
        }
        parameters.setApplicationProtocols(alpnCsv.split(","));
    }

    private SSLContext buildServerContext(String keyPem, String certPem) throws Exception {
        PrivateKey privateKey = parsePrivateKeyPem(keyPem);
        Certificate[] chain = parseCertificateChain(certPem);
        char[] password = "playwright4j".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry("dummy", privateKey, password, chain);
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);
        return context;
    }

    private static Certificate[] parseCertificateChain(String pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certificates =
                factory.generateCertificates(new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        List<Certificate> chain = new ArrayList<Certificate>(certificates);
        return chain.toArray(new Certificate[0]);
    }

    /** Parses an RSA private key from PEM, accepting both PKCS#8 and PKCS#1 (BEGIN RSA PRIVATE KEY). */
    private static PrivateKey parsePrivateKeyPem(String pem) throws Exception {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String base64 = pem
                .replaceAll("-----BEGIN (?:RSA )?PRIVATE KEY-----", "")
                .replaceAll("-----END (?:RSA )?PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        if (pkcs1) {
            der = wrapPkcs1AsPkcs8(der);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    // Wrap a PKCS#1 RSAPrivateKey DER in the PKCS#8 PrivateKeyInfo structure so KeyFactory accepts it.
    private static byte[] wrapPkcs1AsPkcs8(byte[] pkcs1) {
        // PrivateKeyInfo ::= SEQUENCE { version INTEGER(0), algorithm AlgorithmIdentifier, privateKey OCTET STRING }
        byte[] version = new byte[] { 0x02, 0x01, 0x00 };
        // AlgorithmIdentifier { OID 1.2.840.113549.1.1.1 (rsaEncryption), NULL }
        byte[] algorithm = new byte[] {
                0x30, 0x0d,
                0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        byte[] octetString = derTagged(0x04, pkcs1);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(version, 0, version.length);
        body.write(algorithm, 0, algorithm.length);
        body.write(octetString, 0, octetString.length);
        return derTagged(0x30, body.toByteArray());
    }

    private static byte[] derTagged(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            byte[] lengthBytes = java.math.BigInteger.valueOf(length).toByteArray();
            int start = lengthBytes.length > 1 && lengthBytes[0] == 0 ? 1 : 0;
            int count = lengthBytes.length - start;
            out.write(0x80 | count);
            out.write(lengthBytes, start, count);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        if (first.length == 0) {
            return second;
        }
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String safeAlpn(Engine state) {
        try {
            String negotiated = state.engine.getApplicationProtocol();
            return negotiated == null ? "" : negotiated;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String result(ByteArrayOutputStream net, ByteArrayOutputStream app, Engine state) {
        String alpn = safeAlpn(state);
        return "{\"net\":\"" + Base64.getEncoder().encodeToString(net.toByteArray())
                + "\",\"app\":\"" + Base64.getEncoder().encodeToString(app.toByteArray())
                + "\",\"established\":" + state.established
                + ",\"alpn\":\"" + alpn
                + "\",\"closed\":" + state.closed + "}";
    }

    private static String error(String message) {
        String safe = message == null ? "tls error" : message.replace("\\", " ").replace("\"", "'").replace("\n", " ");
        return "{\"error\":\"" + safe + "\",\"closed\":true}";
    }
}
