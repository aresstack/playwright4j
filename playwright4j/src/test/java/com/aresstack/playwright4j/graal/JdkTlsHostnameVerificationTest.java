package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

/**
 * The server certificate is issued for "localhost" only, so https://127.0.0.1 is a hostname
 * mismatch. A client that trusts the certificate must still reject that address (hostname
 * verification stays on), while ignoreHTTPSErrors (rejectUnauthorized:false) must reach it.
 */
final class JdkTlsHostnameVerificationTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    @TempDir
    static Path tempDir;

    private static HttpsServer server;
    private static KeyStore keyStore;

    @BeforeAll
    static void startServer() throws Exception {
        File keyStoreFile = tempDir.resolve("localhost.p12").toFile();
        String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-storetype", "PKCS12", "-keystore", keyStoreFile.getAbsolutePath(),
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD))
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "keytool timed out");
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        keyStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream input = new FileInputStream(keyStoreFile)) {
            keyStore.load(input, PASSWORD);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void ignoreHttpsErrorsReachesServerWhoseCertificateDoesNotMatchTheHost() throws Exception {
        HttpClient client = new JdkTlsClientFactory().clientFor("{\"rejectUnauthorized\":false}", HttpClient.newHttpClient());

        assertEquals(200, get(client, "https://127.0.0.1:" + port() + "/").statusCode());
    }

    @Test
    void trustedCertificateIsRejectedOnHostnameMismatch() throws Exception {
        HttpClient trusting = HttpClient.newBuilder().sslContext(trusting()).build();

        assertEquals(200, get(trusting, "https://localhost:" + port() + "/").statusCode());
        IOException mismatch = assertThrows(IOException.class, () -> get(trusting, "https://127.0.0.1:" + port() + "/"));
        assertTrue(mismatch instanceof SSLHandshakeException || mismatch.getCause() instanceof SSLHandshakeException,
                "expected a TLS handshake failure, got " + mismatch);
    }

    @Test
    void driverDoesNotDisableHostnameVerificationJvmWide() {
        // GraalDriverMain used to set this for ignoreHTTPSErrors; it disabled hostname checks for
        // every request in the driver, including APIRequestContext without ignoreHTTPSErrors.
        assertEquals(null, System.getProperty("jdk.internal.httpclient.disableHostnameVerification"));
    }

    private static SSLContext trusting() throws Exception {
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keyStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }

    private static int port() {
        return server.getAddress().getPort();
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
