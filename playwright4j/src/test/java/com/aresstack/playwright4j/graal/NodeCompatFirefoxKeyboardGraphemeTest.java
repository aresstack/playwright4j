package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * keyboard.down/up/press over WebDriver BiDi with a single grapheme outside the US layout (ä, ß, a
 * combining sequence, an emoji with modifier) sends it as a key action instead of failing with
 * Playwright's "Unknown key". The test drives the bundle's Keyboard with a BiDi raw keyboard whose
 * performActions call is recorded.
 */
final class NodeCompatFirefoxKeyboardGraphemeTest {

    private GraalPlaywrightRuntime runtime;

    @BeforeEach
    void start() {
        Playwright4JHost host = new Playwright4JHost(new FixedHostEnvironment(), new LocalHostFileSystem(), new RecordingMissingHostFunctionReporter());
        runtime = new GraalPlaywrightRuntime(host, new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader()));
        runtime.loadNodeCompatibilityLayer();
        evaluate("(function () {\n"
                + "  const { Keyboard, RawKeyboardImpl2 } = (require('playwright-core/lib/coreBundle'), globalThis.__playwright4jCoreBundle.internals);\n"
                + "  const actions = [];\n"
                + "  const raw = new RawKeyboardImpl2({});\n"
                + "  raw._performActions = async (progress, list) => { list.forEach(a => actions.push(a.type + ':' + a.value)); };\n"
                + "  globalThis.__graphemeTest = { keyboard: new Keyboard(raw, {}), actions, progress: {} };\n"
                + "})();");
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    @Test
    void downAndUpAcceptAGraphemeOutsideTheUsLayout() {
        assertEquals("keyDown:ä, keyUp:ä", run("await keyboard.down(progress, 'ä'); await keyboard.up(progress, 'ä');"));
    }

    @Test
    void pressAcceptsGraphemesOfSeveralCodePoints() {
        assertEquals("keyDown:ß, keyUp:ß, keyDown:é, keyUp:é, keyDown:👍🏽, keyUp:👍🏽",
                run("await keyboard.press(progress, 'ß'); await keyboard.press(progress, 'e\\u0301'); await keyboard.press(progress, '\\uD83D\\uDC4D\\uD83C\\uDFFD');"));
    }

    @Test
    void modifiersCombineWithSuchAGrapheme() {
        assertEquals("keyDown:\uE008, keyDown:ü, keyUp:ü, keyUp:\uE008", run("await keyboard.press(progress, 'Shift+ü');"));
    }

    @Test
    void usLayoutKeysAreUnchanged() {
        assertEquals("keyDown:a, keyUp:a, keyDown:\uE006, keyUp:\uE006", run("await keyboard.press(progress, 'a'); await keyboard.press(progress, 'Enter');"));
    }

    @Test
    void severalGraphemesAndControlCharactersAreStillUnknownKeys() {
        for (String key : new String[] {"äö", "\\u0001", "\\uE007", "", "ab"}) {
            PolyglotException refused = Assertions.assertThrows(PolyglotException.class, () -> run("await keyboard.press(progress, '" + key + "');"));
            assertTrue(refused.getMessage().contains("Unknown key"), key + ": " + refused.getMessage());
        }
        assertEquals("", evaluate("__graphemeTest.actions.join(', ')"));
    }

    // Runs the steps to completion: the raw keyboard answers at once, so the promise jobs settle
    // when the evaluation ends; a rejection is rethrown by the next evaluation.
    private String run(String steps) {
        evaluate("(async function () { const { keyboard, progress } = __graphemeTest; " + steps + " })()"
                + ".then(() => { __graphemeTest.failure = null; }, e => { __graphemeTest.failure = e; }); ''");
        evaluate("if (__graphemeTest.failure) throw __graphemeTest.failure; ''");
        return evaluate("__graphemeTest.actions.join(', ')");
    }

    private String evaluate(String source) {
        return runtime.evaluate("keyboard-grapheme-test.js", source).asString();
    }
}
