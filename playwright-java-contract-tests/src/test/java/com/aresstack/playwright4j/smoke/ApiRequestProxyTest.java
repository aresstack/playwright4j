package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Proxy;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * APIRequestContext with Playwright's proxy option must send its requests through the proxy, as
 * Playwright on Node does: plain HTTP to the proxy, a CONNECT tunnel for https targets, Basic
 * proxy credentials, and direct connections only for hosts matched by bypass. The proxy is a
 * stub that records each request head; target hosts under .invalid cannot be reached directly.
 */
public class ApiRequestProxyTest {
    private static final String CREDENTIALS = "Basic " + Base64.getEncoder()
            .encodeToString("user:p@ss:word".getBytes(StandardCharsets.UTF_8));

    private static StubProxy proxy;
    private static HttpServer origin;
    private static final AtomicInteger originRequests = new AtomicInteger();
    private static HttpsServer tlsOrigin;
    private static Playwright playwright;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws IOException {
        proxy = new StubProxy();
        origin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        origin.createContext("/", exchange -> {
            originRequests.incrementAndGet();
            byte[] body = "direct".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();
        tlsOrigin = startTlsOrigin();
        playwright = Playwright.create();
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (playwright != null) {
            playwright.close();
        }
        if (origin != null) {
            origin.stop(0);
        }
        if (tlsOrigin != null) {
            tlsOrigin.stop(0);
        }
        if (proxy != null) {
            proxy.close();
        }
    }

    @BeforeEach
    void reset() {
        proxy.heads.clear();
        proxy.credentialsRequired = false;
        proxy.tunnelTarget = null;
        originRequests.set(0);
    }

    @Test
    void httpRequestGoesThroughTheProxyWithCredentials() {
        proxy.credentialsRequired = true;
        APIRequestContext request = newContext(new Proxy(proxy.server()).setUsername("user").setPassword("p@ss:word"));
        try {
            APIResponse response = request.get("http://playwright4j.invalid/hello?x=1");

            assertEquals(200, response.status());
            assertEquals("from-proxy", response.text());
            assertFalse(proxy.heads.isEmpty());
            for (String head : proxy.heads) {
                assertTrue(head.startsWith("GET http://playwright4j.invalid/hello?x=1 HTTP/1.1\r\n"), head);
            }
            String last = proxy.heads.get(proxy.heads.size() - 1);
            assertTrue(last.toLowerCase().contains("proxy-authorization: " + CREDENTIALS.toLowerCase()), last);
        } finally {
            request.dispose();
        }
    }

    @Test
    void httpsRequestOpensATunnelThroughTheProxy() {
        proxy.credentialsRequired = true;
        APIRequestContext request = newContext(new Proxy(proxy.server()).setUsername("user").setPassword("p@ss:word"));
        try {
            // The stub refuses the authenticated tunnel, so the request fails after the CONNECT.
            assertThrows(PlaywrightException.class, () -> request.get("https://playwright4j.invalid/secure"));

            assertFalse(proxy.heads.isEmpty());
            for (String head : proxy.heads) {
                assertTrue(head.startsWith("CONNECT playwright4j.invalid:443 HTTP/1.1\r\n"), head);
            }
            String last = proxy.heads.get(proxy.heads.size() - 1);
            assertTrue(last.toLowerCase().contains("proxy-authorization: " + CREDENTIALS.toLowerCase()), last);
        } finally {
            request.dispose();
        }
    }

    @Test
    void httpsResponseArrivesThroughTheTunnel() {
        proxy.credentialsRequired = true;
        proxy.tunnelTarget = tlsOrigin.getAddress();
        APIRequestContext request = playwright.request().newContext(new APIRequest.NewContextOptions()
                .setProxy(new Proxy(proxy.server()).setUsername("user").setPassword("p@ss:word"))
                .setIgnoreHTTPSErrors(true)
                .setTimeout(15_000));
        try {
            APIResponse response = request.get("https://playwright4j.invalid/secure");

            assertEquals(200, response.status());
            assertEquals("tls-origin /secure", response.text());
            String last = proxy.heads.get(proxy.heads.size() - 1);
            assertTrue(last.startsWith("CONNECT playwright4j.invalid:443 HTTP/1.1\r\n"), last);
            assertTrue(last.toLowerCase().contains("proxy-authorization: " + CREDENTIALS.toLowerCase()), last);
        } finally {
            request.dispose();
        }
    }

    @Test
    void bypassedHostIsRequestedDirectly() {
        APIRequestContext request = newContext(new Proxy(proxy.server()).setBypass("127.0.0.1"));
        try {
            APIResponse response = request.get("http://127.0.0.1:" + origin.getAddress().getPort() + "/");

            assertEquals("direct", response.text());
            assertEquals(1, originRequests.get());
            assertEquals(List.of(), proxy.heads);
        } finally {
            request.dispose();
        }
    }

    @Test
    void unsupportedProxySchemeFailsInsteadOfConnectingDirectly() {
        APIRequestContext request = newContext(new Proxy("socks5://127.0.0.1:" + proxy.port()));
        try {
            PlaywrightException error = assertThrows(PlaywrightException.class,
                    () -> request.get("http://127.0.0.1:" + origin.getAddress().getPort() + "/"));

            assertTrue(error.getMessage().contains("not supported"), error.getMessage());
            assertEquals(0, originRequests.get());
        } finally {
            request.dispose();
        }
    }

    /** An https server with a fresh self-signed certificate that echoes the request path. */
    private static HttpsServer startTlsOrigin() throws IOException {
        try {
            char[] password = "changeit".toCharArray();
            File keyStoreFile = tempDir.resolve("tls-origin.p12").toFile();
            String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA",
                    "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost", "-storetype", "PKCS12",
                    "-keystore", keyStoreFile.getAbsolutePath(), "-storepass", new String(password), "-keypass", new String(password))
                    .redirectErrorStream(true).start();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "keytool timed out");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream input = new FileInputStream(keyStoreFile)) {
                keyStore.load(input, password);
            }
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, null);

            HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(context));
            server.createContext("/", exchange -> {
                byte[] body = ("tls-origin " + exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Could not start the TLS origin", exception);
        }
    }

    private static APIRequestContext newContext(Proxy proxySettings) {
        return playwright.request().newContext(new APIRequest.NewContextOptions().setProxy(proxySettings).setTimeout(15_000));
    }

    /**
     * Asks for credentials on a request without them, as a real proxy does. With them, it answers
     * absolute-form requests itself and tunnels CONNECT to {@code tunnelTarget}, or refuses it
     * without one. Records every request head.
     */
    private static final class StubProxy implements AutoCloseable {
        final List<String> heads = new CopyOnWriteArrayList<>();
        volatile boolean credentialsRequired;
        volatile InetSocketAddress tunnelTarget;
        private final ServerSocket socket;

        StubProxy() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::acceptLoop, "stub-proxy");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        String server() {
            return "http://127.0.0.1:" + port();
        }

        private void acceptLoop() {
            while (!socket.isClosed()) {
                try {
                    Socket connection = socket.accept();
                    Thread handler = new Thread(() -> handle(connection), "stub-proxy-connection");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException closed) {
                    // The test closed the socket.
                }
            }
        }

        private void handle(Socket connection) {
            boolean tunnelled = false;
            try {
                String head = readHead(connection.getInputStream());
                if (head.isEmpty()) {
                    return;
                }
                heads.add(head);
                OutputStream output = connection.getOutputStream();
                InetSocketAddress target = tunnelTarget;
                if (credentialsRequired && !head.toLowerCase().contains("proxy-authorization:")) {
                    output.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"stub\"\r\n"
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                } else if (head.startsWith("CONNECT ") && target != null) {
                    // Whatever host the CONNECT names, the tunnel leads to the local TLS server.
                    Socket upstream = new Socket(target.getAddress(), target.getPort());
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    pipe(connection, upstream);
                    pipe(upstream, connection);
                    tunnelled = true;
                } else if (head.startsWith("CONNECT ")) {
                    output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                } else {
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 10\r\n"
                            + "Connection: close\r\n\r\nfrom-proxy").getBytes(StandardCharsets.US_ASCII));
                }
                output.flush();
            } catch (IOException closed) {
                // The client went away mid-request.
            } finally {
                if (!tunnelled) {
                    closeQuietly(connection);
                }
            }
        }

        private static void pipe(Socket from, Socket to) {
            Thread copier = new Thread(() -> {
                try {
                    from.getInputStream().transferTo(to.getOutputStream());
                } catch (IOException closed) {
                    // Either side closed the tunnel.
                } finally {
                    closeQuietly(from);
                    closeQuietly(to);
                }
            }, "stub-proxy-tunnel");
            copier.setDaemon(true);
            copier.start();
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }

        private static String readHead(InputStream input) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int next;
            while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n") && (next = input.read()) != -1) {
                head.write(next);
            }
            return head.toString(StandardCharsets.ISO_8859_1);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
