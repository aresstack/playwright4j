package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Frame events whose order Firefox does not guarantee. Each test replays one BiDi event order against
 * Playwright's BidiBrowser and BidiPage handlers with a scripted frame manager and records what the
 * client would be told.
 */
final class NodeCompatFirefoxFrameEventOrderTest {

    private static final int SETTLE_EVALUATIONS = 5;

    private GraalPlaywrightRuntime runtime;

    @BeforeEach
    void start() {
        Playwright4JHost host = new Playwright4JHost(new FixedHostEnvironment(), new LocalHostFileSystem(), new RecordingMissingHostFunctionReporter());
        runtime = new GraalPlaywrightRuntime(host, new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
        evaluate("(function () {\n"
                + "  const { BidiBrowser, BidiPage } = (require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle.internals);\n"
                + "  const frames = new Map();\n"
                + "  const reportedNames = [];\n"
                + "  const nodeAnswers = [];\n"
                + "  const frameManager = {\n"
                + "    frame: id => frames.get(id) || null,\n"
                + "    frameAttached(id, parentId) { const frame = { _id: id, _name: '', _url: '' }; frames.set(id, frame); return frame; },\n"
                + "    frameCommittedNewDocumentNavigation(id, url, name) { frames.get(id)._name = name; reportedNames.push(name); },\n"
                + "    _fireInternalFrameNavigation(frame, event) { reportedNames.push(event.name); }\n"
                + "  };\n"
                + "  const page = Object.create(BidiPage.prototype);\n"
                + "  Object.assign(page, {\n"
                + "    _page: { frameManager },\n"
                + "    _session: { addFrameBrowsingContext() {} },\n"
                + "    _browserContext: { doGrantGlobalPermissionsForURL: async () => {} },\n"
                + "    _getFrameNode: () => new Promise(resolve => nodeAnswers.push(resolve))\n"
                + "  });\n"
                + "  frameManager.frameAttached('MAIN', null);\n"
                + "  const browser = Object.create(BidiBrowser.prototype);\n"
                + "  Object.assign(browser, { _bidiPages: new Map([['MAIN', page]]) });\n"
                + "  globalThis.__frameOrderTest = { browser, page, frames, reportedNames, nodeAnswers };\n"
                + "})();");
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    // The iframe's navigationCommitted can arrive before the locateNodes answer that carries its name
    // attribute; the client must still end up with the name (page.frame(name), #72 gate).
    @Test
    void anIframeNameThatArrivesAfterItsCommitReachesTheClient() {
        assertEquals("frame1", replay(
                "browser._onBrowsingContextCreated({ context: 'CHILD', parent: 'MAIN', url: 'about:blank' });",
                "page._onNavigationCommitted({ context: 'CHILD', url: 'http://localhost/frame.html', navigation: 'N1' });",
                "nodeAnswers.shift()({ value: { attributes: { name: 'frame1' } } });",
                "lastReportedName"));
    }

    @Test
    void anIframeNameThatArrivesBeforeItsCommitReachesTheClient() {
        assertEquals("frame1", replay(
                "browser._onBrowsingContextCreated({ context: 'CHILD', parent: 'MAIN', url: 'about:blank' });",
                "nodeAnswers.shift()({ value: { attributes: { name: 'frame1' } } });",
                "page._onNavigationCommitted({ context: 'CHILD', url: 'http://localhost/frame.html', navigation: 'N1' });",
                "lastReportedName"));
    }

    // A commit for a frame Playwright does not know (already detached, or never reported) is ignored
    // like the other navigation events, instead of throwing in the event handler.
    @Test
    void aCommitForAnUnknownFrameIsIgnored() {
        assertEquals("ignored", evaluate("(function () {\n"
                + "  const { page, reportedNames } = __frameOrderTest;\n"
                + "  try {\n"
                + "    page._onNavigationCommitted({ context: 'GONE', url: 'http://localhost/', navigation: 'N2' });\n"
                + "  } catch (error) {\n"
                + "    return String(error && error.message);\n"
                + "  }\n"
                + "  return reportedNames.length === 0 ? 'ignored' : 'reported';\n"
                + "})()"));
    }

    // Each step is its own script evaluation; the promise jobs it starts settle before the next one
    // (GraalJS runs them when an evaluation ends).
    private String replay(String... steps) {
        for (int i = 0; i < steps.length - 1; i++) {
            evaluate("(function () { const { browser, page, nodeAnswers } = __frameOrderTest; " + steps[i] + " })(); ''");
            for (int turn = 0; turn < SETTLE_EVALUATIONS; turn++) {
                evaluate("''");
            }
        }
        return evaluate("(function () { const names = __frameOrderTest.reportedNames; return names.length ? names[names.length - 1] : 'none'; })()");
    }

    private String evaluate(String source) {
        return runtime.evaluate("frame-event-order-test.js", source).asString();
    }
}
