package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Frame events whose order Firefox does not guarantee. Each test feeds BiDi messages through
 * Playwright's BidiConnection into its BidiBrowser and BidiPage handlers, with a scripted frame
 * manager that records every navigation event of a frame: each new-document commit and each internal
 * navigation. Navigation waiters listen to these; the ones marked public, and every commit, reach the
 * client as FrameNavigated on the page and the browser context. The client updates a frame's name
 * only from them.
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
                + "  const { BidiBrowser, BidiPage, BidiConnection } = (require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle.internals);\n"
                + "  const sent = [];\n"
                + "  const transport = { send: message => sent.push(message), close() {} };\n"
                + "  const connection = new BidiConnection(transport, () => {}, () => {}, { recentLogs: () => [] });\n"
                + "  const frames = new Map();\n"
                + "  const navigations = [];\n"
                + "  const frameManager = {\n"
                + "    frame: id => frames.get(id) || null,\n"
                + "    frameAttached(id, parentId) {\n"
                + "      const frame = { _id: id, _name: '', _url: '', parentFrame: () => frames.get(parentId) || null };\n"
                + "      frames.set(id, frame);\n"
                + "      return frame;\n"
                + "    },\n"
                + "    frameCommittedNewDocumentNavigation(id, url, name) { navigations.push(id + ':' + name + ':' + url); },\n"
                + "    _fireInternalFrameNavigation(frame, event) { navigations.push(frame._id + ':' + event.name + ':' + event.url); }\n"
                + "  };\n"
                + "  const page = Object.create(BidiPage.prototype);\n"
                + "  Object.assign(page, {\n"
                + "    _page: { frameManager },\n"
                + "    _session: connection.createMainFrameBrowsingContextSession('MAIN'),\n"
                + "    _browserContext: { doGrantGlobalPermissionsForURL: async () => {} }\n"
                + "  });\n"
                + "  page._session.on('browsingContext.navigationCommitted', params => page._onNavigationCommitted(params));\n"
                + "  frameManager.frameAttached('MAIN', null);\n"
                + "  const browser = Object.create(BidiBrowser.prototype);\n"
                + "  Object.assign(browser, { _connection: connection, _bidiPages: new Map([['MAIN', page]]) });\n"
                + "  connection.browserSession.on('browsingContext.contextCreated', event => browser._onBrowsingContextCreated(event));\n"
                + "  const receive = message => transport.onmessage(message);\n"
                + "  const answered = new Set();\n"
                + "  const lookupFor = context => sent.find(message => message.method === 'browsingContext.locateNodes'\n"
                + "      && message.params.locator.value.context === context && !answered.has(message.id));\n"
                + "  globalThis.__frameOrderTest = {\n"
                + "    page, navigations,\n"
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
    // attribute (#72 gate). The client must get the name with the frame's one navigation, not with a
    // second navigation that did not happen.
    @Test
    void anIframeNameThatArrivesAfterItsCommitComesWithTheOneNavigation() {
        assertEquals("CHILD:frame1:" + FRAME_URL, replay(
                "created('CHILD');",
                "committed('CHILD');",
                "named('CHILD', 'frame1');"));
    }

    @Test
    void anIframeNameThatArrivesBeforeItsCommitComesWithTheOneNavigation() {
        assertEquals("CHILD:frame1:" + FRAME_URL, replay(
                "created('CHILD');",
                "named('CHILD', 'frame1');",
                "committed('CHILD');"));
    }

    // A frame created while another frame's name is outstanding starts its own lookup once that
    // answer is in; each commit carries its own frame's name, in Firefox's order.
    @Test
    void twoIframesEachNavigateOnceWithTheirOwnName() {
        assertEquals("A:first:" + FRAME_URL + "|B:second:" + FRAME_URL, replay(
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
        assertEquals("CHILD::" + FRAME_URL, replay(
                "created('CHILD');",
                "committed('CHILD');",
                "lookupFailed('CHILD');"));
    }

    // A commit for a frame Playwright does not know (already detached, or never reported) is ignored
    // like the other navigation events, instead of throwing in the event handler.
    @Test
    void aCommitForAnUnknownFrameIsIgnored() {
        assertEquals("ignored", evaluate("(function () {\n"
                + "  const { page, navigations } = __frameOrderTest;\n"
                + "  try {\n"
                + "    page._onNavigationCommitted({ context: 'GONE', url: 'http://localhost/', navigation: 'N2' });\n"
                + "  } catch (error) {\n"
                + "    return String(error && error.message);\n"
                + "  }\n"
                + "  return navigations.length === 0 ? 'ignored' : 'reported';\n"
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
        return evaluate("__frameOrderTest.navigations.join('|')");
    }

    private String evaluate(String source) {
        return runtime.evaluate("frame-event-order-test.js", source).asString();
    }
}
