package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A request event that reaches a page's BidiNetworkManager for a frame the page does not know.
 * Firefox holds an intercepted request until it is answered; each test records the answers sent.
 */
final class NodeCompatFirefoxUnattributedRequestTest {

    private GraalPlaywrightRuntime runtime;

    @BeforeEach
    void start() {
        Playwright4JHost host = new Playwright4JHost(new FixedHostEnvironment(), new LocalHostFileSystem(), new RecordingMissingHostFunctionReporter());
        runtime = new GraalPlaywrightRuntime(host, new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
        evaluate("(function () {\n"
                + "  const { BidiNetworkManager } = (require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle.internals);\n"
                + "  const sent = [];\n"
                + "  const manager = Object.create(BidiNetworkManager.prototype);\n"
                + "  Object.assign(manager, {\n"
                + "    _session: { sendMayFail: async (method, params) => { sent.push(method + ':' + params.request); } },\n"
                + "    _requests: new Map(),\n"
                + "    _page: { frameManager: { frame: () => null } }\n"
                + "  });\n"
                + "  globalThis.__unattributedTest = {\n"
                + "    manager, sent,\n"
                + "    request: (id, blocked) => ({ request: { request: id, url: 'http://localhost/' + id, method: 'GET', headers: [] },\n"
                + "      context: 'GONE', redirectCount: 0, isBlocked: blocked, intercepts: blocked ? ['I1'] : undefined })\n"
                + "  };\n"
                + "})();");
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    @Test
    void anInterceptedRequestOfAnUnknownFrameIsContinuedOnce() {
        assertEquals("network.continueRequest:R1", replay("manager._onBeforeRequestSent(request('R1', true));"));
    }

    @Test
    void aRequestThatIsNotInterceptedIsLeftAlone() {
        assertEquals("", replay("manager._onBeforeRequestSent(request('R2', false));"));
    }

    private String replay(String step) {
        evaluate("(function () { const { manager, request } = __unattributedTest; " + step + " })(); ''");
        evaluate("''");
        return evaluate("__unattributedTest.sent.join(',')");
    }

    private String evaluate(String source) {
        return runtime.evaluate("unattributed-request-test.js", source).asString();
    }
}
