package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #66: a frame's utility world must belong to a live realm. Firefox answers the evaluate that
 * creates it from the sandbox realm the context holds at that moment; during a navigation that can be
 * a short-lived realm, destroyed before the answer arrives. Each test replays one BiDi event order
 * against BidiPage with a scripted session and reads which utility realm the frame keeps.
 */
final class NodeCompatFirefoxUtilityWorldTest {

    private static final long TIMEOUT_MILLIS = 30_000;

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

    @Test
    void aUtilityWorldAnsweredByARealmThatIsAlreadyDestroyedIsNotKept() {
        assertEquals("none", utilityRealmAfter("page._onRealmDestroyed({ realm: 'R3' });"));
    }

    @Test
    void aUtilityWorldOfALiveRealmIsKept() {
        assertEquals("R3", utilityRealmAfter(""));
    }

    // Window realm R2 is announced; Firefox answers the utility evaluate from sandbox realm R3 after
    // the given events ran.
    private String utilityRealmAfter(String eventsBeforeAnswer) {
        evaluate("(function () {\n"
                + "  const { BidiPage } = (require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle.internals);\n"
                + "  const test = globalThis.__utilityWorldTest = { state: 'running', utility: 'none' };\n"
                + "  const realms = new Map();\n"
                + "  let answer;\n"
                + "  const session = { send: () => new Promise(resolve => { answer = resolve; }) };\n"
                + "  let main = null;\n"
                + "  const frame = { _page: { delegate: {} }, attribution: {}, instrumentation: {},\n"
                + "    mainContext: async () => main,\n"
                + "    contextCreated(world, context) { if (world === 'main') main = context; else test.utility = 'R3'; },\n"
                + "    contextDestroyed(context) { if (context === main) main = null; else test.utility = 'none'; }\n"
                + "  };\n"
                + "  const page = Object.create(BidiPage.prototype);\n"
                + "  Object.assign(page, { _session: session, _contextIdToContext: realms, _realmToWorkerContext: new Map(),\n"
                + "    _page: { frameManager: { frame: () => frame } } });\n"
                + "  page._onRealmCreated({ realm: 'R2', type: 'window', context: 'C1', origin: 'null' });\n"
                + "  " + eventsBeforeAnswer + "\n"
                + "  answer({ realm: 'R3', result: { type: 'number', value: 2 } });\n"
                + "  setTimeout(() => { test.state = 'done'; }, 0);\n"
                + "})();");
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!evaluate("__utilityWorldTest.state").equals("done")) {
            if (System.currentTimeMillis() > deadline) {
                fail("utility world replay did not finish");
            }
            runtime.runDueTimers();
        }
        return evaluate("__utilityWorldTest.utility");
    }

    private String evaluate(String source) {
        return runtime.evaluate("utility-world-test.js", source).asString();
    }
}
