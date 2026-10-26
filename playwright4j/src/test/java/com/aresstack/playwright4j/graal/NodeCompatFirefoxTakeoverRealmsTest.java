package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A taken-over Firefox tab reports its window realms from a script.getRealms snapshot. Firefox
 * collects that snapshot from the tab's window globals asynchronously, so realm events of the same
 * tab can be handled before the answer arrives. Each test replays one such order and records the
 * realm of the frame's main world.
 */
final class NodeCompatFirefoxTakeoverRealmsTest {

    private static final int SETTLE_EVALUATIONS = 5;

    private GraalPlaywrightRuntime runtime;

    @BeforeEach
    void start() {
        Playwright4JHost host = new Playwright4JHost(new FixedHostEnvironment(), new LocalHostFileSystem(), new RecordingMissingHostFunctionReporter());
        runtime = new GraalPlaywrightRuntime(host, new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
        evaluate("(function () {\n"
                + "  const core = (require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle);\n"
                + "  const { BidiPage } = core.internals;\n"
                + "  const realms = new Map();\n"
                + "  const snapshots = [];\n"
                + "  const session = { sessionId: 'C1', send: (method) => new Promise(resolve => {\n"
                + "    if (method === 'script.getRealms') { snapshots.push(list => resolve({ realms: list })); }\n"
                + "  }) };\n"
                + "  const worlds = new Map([['main', { context: null }], ['utility', { context: null }]]);\n"
                + "  const frame = { _id: 'C1', _page: { delegate: {} }, attribution: {}, instrumentation: {}, _contextData: worlds,\n"
                + "    mainContext: async () => worlds.get('main').context,\n"
                + "    contextCreated(world, context) {\n"
                + "      const data = worlds.get(world);\n"
                + "      if (data.context) { data.context.contextDestroyed('replaced'); }\n"
                + "      data.context = context;\n"
                + "    },\n"
                + "    contextDestroyed(context) {\n"
                + "      context.contextDestroyed('destroyed');\n"
                + "      for (const data of worlds.values()) { if (data.context === context) { data.context = null; } }\n"
                + "    }\n"
                + "  };\n"
                + "  const page = Object.create(BidiPage.prototype);\n"
                + "  Object.assign(page, { _session: session, _contextIdToContext: realms, _realmToWorkerContext: new Map(),\n"
                + "    _page: { mainFrame: () => frame, frameManager: { frame: () => frame, frameLifecycleEvent() {} } } });\n"
                + "  globalThis.__takeoverTest = {\n"
                + "    core, page, snapshots,\n"
                + "    windowRealm: realm => ({ realm, type: 'window', context: 'C1', origin: 'null' }),\n"
                + "    mainRealm() {\n"
                + "      for (const [realm, context] of realms) { if (context === worlds.get('main').context) { return realm; } }\n"
                + "      return 'none';\n"
                + "    }\n"
                + "  };\n"
                + "})();");
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    // The snapshot still lists a realm whose destruction was already handled.
    @Test
    void aRealmDestroyedBeforeTheSnapshotArrivesDoesNotBecomeTheMainWorld() {
        assertEquals("none", replay(
                "core.restoreTakenOverPage(page);",
                "page._onRealmDestroyed({ realm: 'R1' });",
                "snapshots.shift()([{ realm: 'R1', type: 'window', origin: 'null' }]);"));
    }

    // The tab navigated during the takeover: the new document's realm was reported before the
    // snapshot, which still lists the previous document's realm. The main world must stay the new
    // document's when the previous realm is then destroyed.
    @Test
    void aSnapshotOlderThanAReportedRealmDoesNotReplaceTheMainWorld() {
        assertEquals("R2", replay(
                "core.restoreTakenOverPage(page);",
                "page._onRealmCreated(windowRealm('R2'));",
                "snapshots.shift()([{ realm: 'R1', type: 'window', origin: 'null' }, { realm: 'R2', type: 'window', origin: 'null' }]);",
                "page._onRealmDestroyed({ realm: 'R1' });"));
    }

    // Each step is its own script evaluation; the promise jobs it starts settle before the next one
    // (GraalJS runs them when an evaluation ends).
    private String replay(String... steps) {
        for (String step : steps) {
            evaluate("(function () { const { core, page, snapshots, windowRealm } = __takeoverTest; " + step + " })(); ''");
            for (int turn = 0; turn < SETTLE_EVALUATIONS; turn++) {
                evaluate("''");
            }
        }
        return evaluate("__takeoverTest.mainRealm()");
    }

    private String evaluate(String source) {
        return runtime.evaluate("takeover-realms-test.js", source).asString();
    }
}
