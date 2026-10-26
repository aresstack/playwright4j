package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #66: a frame's utility world must belong to a live realm of its current document. Each test
 * replays one BiDi event order against BidiPage with a scripted session (every utility evaluate is
 * answered by the test) and records which sandbox realm the frame's utility world is in after each
 * step.
 */
final class NodeCompatFirefoxUtilityWorldTest {

    private static final int SETTLE_EVALUATIONS = 5;

    private GraalPlaywrightRuntime runtime;

    @BeforeEach
    void start() {
        Playwright4JHost host = new Playwright4JHost(new FixedHostEnvironment(), new LocalHostFileSystem(), new RecordingMissingHostFunctionReporter());
        runtime = new GraalPlaywrightRuntime(host, new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    // Firefox can answer the evaluate that creates the utility world from a short-lived sandbox realm
    // that is destroyed before the answer arrives.
    @Test
    void aUtilityWorldAnsweredByARealmThatIsAlreadyDestroyedIsNotKept() {
        assertEquals("none", replay(
                "page._onRealmCreated(windowRealm('R2'));",
                "page._onRealmDestroyed({ realm: 'S2' }); answers.shift()('S2');"));
    }

    @Test
    void aUtilityWorldOfALiveRealmIsKept() {
        assertEquals("S2", replay(
                "page._onRealmCreated(windowRealm('R2'));",
                "answers.shift()('S2');"));
    }

    // Firefox reports the previous document's realms destroyed only when its window global is torn
    // down, which can be after the new document loaded; the new document's sandbox is announced only
    // after load too. Until the new world exists, utility evaluations must not run in the old one.
    @Test
    void aNewDocumentDropsThePreviousDocumentsUtilityWorld() {
        assertEquals("S1, none, S2", replay(
                "page._onRealmCreated(windowRealm('R1'));",
                "answers.shift()('S1');",
                "page._onRealmCreated(windowRealm('R2'));",
                "answers.shift()('S2');"));
    }

    // Each step is its own script evaluation; the promise jobs it starts settle before the next one
    // (GraalJS runs them when an evaluation ends). The result lists the utility realm after every step
    // but the first.
    private String replay(String... steps) {
        evaluate("(function () {\n"
                + "  const { BidiPage } = (require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle.internals);\n"
                + "  const realms = new Map();\n"
                + "  const answers = [];\n"
                + "  const session = { send: () => new Promise(resolve => answers.push(realm => resolve({ realm, result: { type: 'number', value: 2 } }))) };\n"
                + "  const worlds = new Map([['main', { context: null }], ['utility', { context: null }]]);\n"
                + "  const frame = { _page: { delegate: {} }, attribution: {}, instrumentation: {}, _contextData: worlds,\n"
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
                + "    _page: { frameManager: { frame: () => frame } } });\n"
                + "  globalThis.__utilityWorldTest = {\n"
                + "    page, answers,\n"
                + "    windowRealm: realm => ({ realm, type: 'window', context: 'C1', origin: 'null' }),\n"
                + "    utilityRealm() {\n"
                + "      for (const [realm, context] of realms) { if (context === worlds.get('utility').context) { return realm; } }\n"
                + "      return 'none';\n"
                + "    }\n"
                + "  };\n"
                + "})();");
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < steps.length; i++) {
            evaluate("(function () { const { page, answers, windowRealm } = __utilityWorldTest; " + steps[i] + " })(); ''");
            // Playwright's handlers chain a few awaits; let each settle in a later evaluation.
            for (int turn = 0; turn < SETTLE_EVALUATIONS; turn++) {
                evaluate("''");
            }
            if (i > 0) {
                seen.add(evaluate("__utilityWorldTest.utilityRealm()"));
            }
        }
        return String.join(", ", seen);
    }

    private String evaluate(String source) {
        return runtime.evaluate("utility-world-test.js", source).asString();
    }
}
