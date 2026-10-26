package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Frame events whose order Firefox does not guarantee. Each test feeds BiDi messages through
 * Playwright's BidiConnection into its BidiBrowser and BidiPage handlers and Playwright's own
 * FrameManager and Frame. It records what a client is told the way FrameDispatcher decides it: a
 * frame navigation marked public and without error becomes the client's navigated event, which is
 * the only source of the client's frame name and of FrameNavigated on the page and the browser
 * context. A navigation waiter is Frame.waitForNavigation, started when the client is told the
 * frame navigated.
 */
final class NodeCompatFirefoxFrameEventOrderTest {

    private static final int SETTLE_EVALUATIONS = 5;
    private static final String FRAME_URL = "http://localhost/frame.html";

    private GraalPlaywrightRuntime runtime;

    @BeforeEach
    void start() {
        Playwright4JHost host = new Playwright4JHost(new FixedHostEnvironment(), new LocalHostFileSystem(), new RecordingMissingHostFunctionReporter());
        runtime = new GraalPlaywrightRuntime(host, new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
        evaluate("(function () {\n"
                + "  const { BidiBrowser, BidiPage, BidiConnection, FrameManager } = (require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle.internals);\n"
                + "  const sent = [];\n"
                + "  const transport = { send: message => sent.push(message), close() {} };\n"
                + "  const connection = new BidiConnection(transport, () => {}, () => {}, { recentLogs: () => [] });\n"
                + "  const navigated = [];\n"
                + "  const waiters = [];\n"
                + "  const server = {\n"
                + "    attribution: { playwright: { options: { isInternalPlaywright: true } } },\n"
                + "    instrumentation: {},\n"
                + "    browserContext: { emit() {} },\n"
                + "    emit() {},\n"
                + "    frameNavigatedToNewDocument() {},\n"
                + "    mainFrame: () => server.frameManager.mainFrame()\n"
                + "  };\n"
                + "  server.frameManager = new FrameManager(server);\n"
                + "  server.frameManager.frameAttached('MAIN', null);\n"
                + "  const watch = frame => frame.on('internalnavigation', event => {\n"
                + "    if (!event.isPublic || event.error)\n"
                + "      return;\n"
                + "    navigated.push(frame._id + ':' + event.name + ':' + event.url);\n"
                + "    if (waiters.some(waiter => waiter.frame === frame))\n"
                + "      return;\n"
                + "    const waiter = { frame, state: 'pending' };\n"
                + "    waiters.push(waiter);\n"
                + "    frame.waitForNavigation({ log() {}, race: promise => promise }, false, { waitUntil: 'commit' })\n"
                + "        .then(() => waiter.state = 'resolved', () => waiter.state = 'failed');\n"
                + "  });\n"
                + "  const page = Object.create(BidiPage.prototype);\n"
                + "  Object.assign(page, {\n"
                + "    _page: server,\n"
                + "    _session: connection.createMainFrameBrowsingContextSession('MAIN'),\n"
                + "    _browserContext: { doGrantGlobalPermissionsForURL: async () => {} }\n"
                + "  });\n"
                + "  page._session.on('browsingContext.navigationCommitted', params => page._onNavigationCommitted(params));\n"
                + "  const browser = Object.create(BidiBrowser.prototype);\n"
                + "  Object.assign(browser, { _connection: connection, _bidiPages: new Map([['MAIN', page]]) });\n"
                + "  connection.browserSession.on('browsingContext.contextCreated', event => {\n"
                + "    browser._onBrowsingContextCreated(event);\n"
                + "    watch(server.frameManager.frame(event.context));\n"
                + "  });\n"
                + "  const receive = message => transport.onmessage(message);\n"
                + "  const answered = new Set();\n"
                + "  const lookupFor = context => sent.find(message => message.method === 'browsingContext.locateNodes'\n"
                + "      && message.params.locator.value.context === context && !answered.has(message.id));\n"
                + "  globalThis.__frameOrderTest = {\n"
                + "    page, navigated, waiters,\n"
                + "    created: context => receive({ type: 'event', method: 'browsingContext.contextCreated',\n"
                + "        params: { context, parent: 'MAIN', url: 'about:blank', children: null, userContext: 'default' } }),\n"
                + "    committed: context => receive({ type: 'event', method: 'browsingContext.navigationCommitted',\n"
                + "        params: { context, navigation: 'N-' + context, url: '" + FRAME_URL + "', timestamp: 0 } }),\n"
                + "    named(context, name) {\n"
                + "      const lookup = lookupFor(context);\n"
                + "      answered.add(lookup.id);\n"
                + "      receive({ type: 'success', id: lookup.id, result: { nodes: [{ type: 'node', value: { attributes: { name } } }] } });\n"
                + "    },\n"
                + "    lookupFailed(context) {\n"
                + "      const lookup = lookupFor(context);\n"
                + "      answered.add(lookup.id);\n"
                + "      receive({ type: 'error', id: lookup.id, error: 'no such frame', message: 'gone' });\n"
                + "    }\n"
                + "  };\n"
                + "})();");
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    // The iframe's navigationCommitted can arrive before the locateNodes answer that carries its name
    // attribute (#72 gate). The client must get the name with the frame's one navigation: no second
    // navigated event, and a waiter started on the first one is not completed by the name.
    @Test
    void anIframeNameThatArrivesAfterItsCommitComesWithTheOneNavigation() {
        assertEquals("CHILD:frame1:" + FRAME_URL + " waiters CHILD:pending", replay(
                "created('CHILD');",
                "committed('CHILD');",
                "named('CHILD', 'frame1');"));
    }

    @Test
    void anIframeNameThatArrivesBeforeItsCommitComesWithTheOneNavigation() {
        assertEquals("CHILD:frame1:" + FRAME_URL + " waiters CHILD:pending", replay(
                "created('CHILD');",
                "named('CHILD', 'frame1');",
                "committed('CHILD');"));
    }

    // A frame created while another frame's name is outstanding starts its own lookup once that
    // answer is in; each commit carries its own frame's name, in Firefox's order.
    @Test
    void twoIframesEachNavigateOnceWithTheirOwnName() {
        assertEquals("A:first:" + FRAME_URL + "|B:second:" + FRAME_URL + " waiters A:pending,B:pending", replay(
                "created('A');",
                "created('B');",
                "committed('A');",
                "committed('B');",
                "named('A', 'first');",
                "named('B', 'second');"));
    }

    // Without a name the frame still navigates once, and nothing stays held.
    @Test
    void aFailedNameLookupStillReportsTheNavigation() {
        assertEquals("CHILD::" + FRAME_URL + " waiters CHILD:pending", replay(
                "created('CHILD');",
                "committed('CHILD');",
                "lookupFailed('CHILD');"));
    }

    // A commit for a frame Playwright does not know (already detached, or never reported) is ignored
    // like the other navigation events, instead of throwing in the event handler.
    @Test
    void aCommitForAnUnknownFrameIsIgnored() {
        assertEquals("ignored", evaluate("(function () {\n"
                + "  const { page, navigated } = __frameOrderTest;\n"
                + "  try {\n"
                + "    page._onNavigationCommitted({ context: 'GONE', url: 'http://localhost/', navigation: 'N2' });\n"
                + "  } catch (error) {\n"
                + "    return String(error && error.message);\n"
                + "  }\n"
                + "  return navigated.length === 0 ? 'ignored' : 'reported';\n"
                + "})()"));
    }

    // Each step is its own script evaluation, as each BiDi message is its own pump step; the promise
    // jobs it starts settle before the next one (GraalJS runs them when an evaluation ends).
    private String replay(String... steps) {
        for (String step : steps) {
            evaluate("(function () { const { created, committed, named, lookupFailed } = __frameOrderTest; " + step + " })(); ''");
            for (int turn = 0; turn < SETTLE_EVALUATIONS; turn++) {
                evaluate("''");
            }
        }
        return evaluate("(function () { const { navigated, waiters } = __frameOrderTest;"
                + " return navigated.join('|') + ' waiters ' + waiters.map(waiter => waiter.frame._id + ':' + waiter.state).join(','); })()");
    }

    private String evaluate(String source) {
        return runtime.evaluate("frame-event-order-test.js", source).asString();
    }
}
