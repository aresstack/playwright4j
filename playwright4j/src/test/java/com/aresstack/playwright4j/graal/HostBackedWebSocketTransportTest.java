package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;

import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.Test;

final class HostBackedWebSocketTransportTest {

    /**
     * BrowserType.connect: when the host send fails because the peer is gone (killed launch-server),
     * the transport drops the message like ws does, and the next drain surfaces onclose exactly once,
     * so Playwright emits 'disconnected' and rejects the pending call with the close reason.
     */
    @Test
    void dropsSendOnBrokenConnectionAndSurfacesCloseOnNextDrain() {
        FakeWebSocketClient client = new FakeWebSocketClient();
        try (GraalPlaywrightRuntime runtime = connectedRuntime(client)) {
            client.failSendsAndClose = true;
            assertEquals("ok", send(runtime, 1));
            assertEquals(1, client.sendAttempts);

            runtime.drainTransports();
            runtime.drainTransports();
            assertEquals(1, runtime.evaluate("closes.js", "globalThis.__closes").asInt());
            assertEquals("server gone", runtime.evaluate("reason.js", "globalThis.__closeReason").asString());

            // Closed transport: the send never reaches the host.
            assertEquals("ok", send(runtime, 2));
            assertEquals(1, client.sendAttempts);
        }
    }

    /** A send failure on a connection the host still reports open is not swallowed. */
    @Test
    void reportsSendFailureOnOpenConnection() {
        FakeWebSocketClient client = new FakeWebSocketClient();
        try (GraalPlaywrightRuntime runtime = connectedRuntime(client)) {
            client.failSendsOnly = true;
            assertEquals("threw", send(runtime, 1));
            runtime.drainTransports();
            assertEquals(0, runtime.evaluate("closes.js", "globalThis.__closes").asInt());
        }
    }

    private static GraalPlaywrightRuntime connectedRuntime(FakeWebSocketClient client) {
        Playwright4JHost host = new Playwright4JHost(
                new FixedHostEnvironment("win32", "x64", "/", Collections.emptyMap()),
                new EmptyHostFileSystem(),
                new JdkHostHttpClient(),
                client,
                new RecordingMissingHostFunctionReporter());
        PlaywrightDriverBundleSource driverBundleSource = new PlaywrightDriverBundleSource(
                Thread.currentThread().getContextClassLoader());
        GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(host, driverBundleSource);
        runtime.loadNodeCompatibilityLayer();
        runtime.evaluateCommonJsEntry(driverBundleSource.packageResourceName("playwright4j-transport-test.js"),
                "const { WebSocketTransport } = (require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle.internals);"
                        + "globalThis.__closes = 0;"
                        + "WebSocketTransport.connect(undefined, 'ws://127.0.0.1:1/fake').then((transport) => {"
                        + "  transport.onclose = (reason) => { globalThis.__closes++; globalThis.__closeReason = reason; };"
                        + "  globalThis.__transport = transport;"
                        + "});");
        runtime.evaluate("connected.js", "if (!globalThis.__transport) throw new Error('transport not connected');");
        return runtime;
    }

    private static String send(GraalPlaywrightRuntime runtime, int id) {
        return runtime.evaluate("send.js",
                "(function () { try { globalThis.__transport.send({ id: " + id + ", method: 'Page.title' });"
                        + " return 'ok'; } catch (e) { return 'threw'; } })()").asString();
    }

    public static final class FakeWebSocketClient implements HostWebSocketClient {

        volatile boolean failSendsAndClose;
        volatile boolean failSendsOnly;
        volatile boolean closed;
        volatile int sendAttempts;

        @Override
        @HostAccess.Export
        public String open(String url) {
            return "fake-connection";
        }

        @Override
        @HostAccess.Export
        public String open(String url, String headers) {
            return "fake-connection";
        }

        @Override
        @HostAccess.Export
        public String sendAndWait(String connectionId, String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        @HostAccess.Export
        public void send(String connectionId, String message) {
            sendAttempts++;
            if (failSendsAndClose) {
                // Like JdkHostWebSocketClient: the failed write marks the connection closed.
                closed = true;
                throw new IllegalStateException("Output closed");
            }
            if (failSendsOnly) {
                throw new IllegalStateException("send failed");
            }
        }

        @Override
        @HostAccess.Export
        public String drain(String connectionId, int milliseconds) {
            return "";
        }

        @Override
        @HostAccess.Export
        public void close(String connectionId) {
            closed = true;
        }

        @Override
        @HostAccess.Export
        public boolean isClosed(String connectionId) {
            return closed;
        }

        @Override
        @HostAccess.Export
        public String closeReason(String connectionId) {
            return "server gone";
        }
    }
}
