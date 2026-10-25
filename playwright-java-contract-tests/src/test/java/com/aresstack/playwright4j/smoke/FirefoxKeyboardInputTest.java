package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.deleteRecursively;
import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text outside the US keyboard layout (umlauts, ß, accents, emoji) and all text of insertText/fill
 * reach the page as trusted key events over WebDriver BiDi, and Playwright installs no
 * window.__pw_bidiInsertText in the page (issue #36). Runs against FIREFOX_EXECUTABLE (Firefox or
 * Camoufox) with a persistent context, which both support.
 */
public class FirefoxKeyboardInputTest {
    // Every input-related event the page sees, recorded by the page itself.
    private static final String RECORDER = "<script>"
            + "window.seen = [];"
            + "for (const type of ['keydown', 'keypress', 'beforeinput', 'input', 'keyup'])"
            + "  document.addEventListener(type, e => seen.push(type + ':' + e.isTrusted + ':' + (e.key || e.data || '')), true);"
            + "</script>";

    @Test
    void textOutsideTheUsLayoutIsTypedAsTrustedKeyEvents() throws Throwable {
        runGuarded(FirefoxKeyboardInputTest::run);
    }

    // fill() sets the content and never submits: a line break is refused for a single-line input
    // before anything is typed, is Enter in a textarea and Shift+Enter in a rich-text editor. An
    // explicit Enter still submits.
    @Test
    void fillWithLineBreaksNeverSubmits() throws Throwable {
        runGuarded(executable -> withPage(executable, page -> {
            page.setContent("<html><body>"
                    + "<form id=form><input id=s type=search><button>Go</button></form>"
                    + "<textarea id=t></textarea><div id=e contenteditable></div>"
                    + "<script>window.submits = 0; window.enters = [];"
                    + "form.addEventListener('submit', e => { e.preventDefault(); submits++; });"
                    + "document.addEventListener('keydown', e => { if (e.key === 'Enter') enters.push(e.target.id + ':' + e.shiftKey + ':' + e.isTrusted); }, true);"
                    + "</script></body></html>");

            PlaywrightException refused = assertThrows(PlaywrightException.class, () -> page.fill("#s", "foo\nbar"));
            assertTrue(refused.getMessage().contains("single-line <input>"), refused.getMessage());
            assertThrows(PlaywrightException.class, () -> page.fill("#s", "foo\r\nbar"));
            assertEquals("", page.inputValue("#s"));
            assertEquals(0, ((Number) page.evaluate("submits")).intValue());

            page.fill("#t", "foo\nbar");
            assertEquals("foo\nbar", page.inputValue("#t"));

            page.fill("#e", "foo\nbar");
            assertEquals("foo\nbar", page.evaluate("document.getElementById('e').innerText").toString().replace("\r", ""));

            page.fill("#s", "Größe");
            assertEquals("Größe", page.inputValue("#s"));
            assertEquals(0, ((Number) page.evaluate("submits")).intValue());
            page.keyboard().press("Enter");
            assertEquals(1, ((Number) page.evaluate("submits")).intValue());

            assertEquals(Arrays.asList("t:false:true", "e:true:true", "s:false:true"), page.evaluate("enters"));
        }));
    }

    private interface PageCheck {
        void run(Page page) throws Exception;
    }

    private static void withPage(String executable, PageCheck check) throws Exception {
        Path userDataDir = Files.createTempDirectory("playwright4j-firefox-keyboard");
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir,
                    new BrowserType.LaunchPersistentContextOptions()
                            .setChannel("moz-firefox")
                            .setExecutablePath(Paths.get(executable))
                            .setHeadless(FirefoxTestSupport.headless()));
            try {
                check.run(context.pages().isEmpty() ? context.newPage() : context.pages().get(0));
            } finally {
                context.close();
            }
        } finally {
            deleteRecursively(userDataDir);
        }
    }

    private static void run(String executable) throws Exception {
        withPage(executable, page -> {
            page.setContent("<html><body>" + RECORDER
                    + "<input id=i><textarea id=t></textarea><div id=e contenteditable></div>"
                    + "<iframe id=f srcdoc=\"<input id=fi>" + RECORDER.replace("\"", "&quot;") + "\"></iframe>"
                    + "</body></html>");

            assertEquals("undefined", page.evaluate("typeof window.__pw_bidiInsertText"));
            assertEquals(0, ((List<?>) page.evaluate(
                    "Object.getOwnPropertyNames(window).filter(n => /^__pw|playwright/i.test(n))")).size());

            // keyboard.type: US-layout characters are pressed, the others go through sendText.
            page.locator("#i").click();
            page.keyboard().type("Grüße, Ärger, Öl, café");
            assertEquals("Grüße, Ärger, Öl, café", page.inputValue("#i"));

            // fill: the whole text goes through sendText; in a textarea a line break is Enter.
            page.fill("#t", "Zeile 1\nStraße");
            assertEquals("Zeile 1\nStraße", page.inputValue("#t"));

            // insertText into contenteditable, with a grapheme of several code points.
            page.locator("#e").click();
            page.keyboard().insertText("Maß 👍🏽");
            assertEquals("Maß 👍🏽", page.textContent("#e"));

            assertOnlyTrustedKeyInput(page, "ü", "ß", "Ä", "Ö", "é", "👍🏽");

            // Inside an iframe the key events go to the focused element of that frame. focus(), not
            // click(): in headless Camoufox a click into the iframe does not move the focus there,
            // with or without this change, and this test is about the key events.
            FrameLocator frame = page.frameLocator("#f");
            frame.locator("#fi").focus();
            page.keyboard().insertText("Größe");
            assertEquals("Größe", frame.locator("#fi").inputValue());
            assertEquals("undefined", page.frames().get(1).evaluate("typeof window.__pw_bidiInsertText"));
            List<?> frameSeen = (List<?>) page.frames().get(1).evaluate("seen");
            assertTrue(frameSeen.contains("keydown:true:ö"), "frame events: " + frameSeen);
            assertTrue(frameSeen.stream().allMatch(e -> String.valueOf(e).contains(":true:")), "frame events: " + frameSeen);
        });
    }

    private static void assertOnlyTrustedKeyInput(Page page, String... keys) {
        List<?> seen = (List<?>) page.evaluate("seen");
        assertTrue(seen.stream().allMatch(e -> String.valueOf(e).contains(":true:")), "untrusted event in " + seen);
        for (String key : keys) {
            assertTrue(seen.contains("keydown:true:" + key), "no trusted keydown for " + key + " in " + seen);
            assertTrue(seen.contains("keyup:true:" + key), "no trusted keyup for " + key + " in " + seen);
        }
        assertTrue(seen.stream().anyMatch(e -> String.valueOf(e).startsWith("input:true:")), "no trusted input event in " + seen);
    }
}
