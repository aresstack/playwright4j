package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotCaret;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading a page in Firefox over WebDriver BiDi leaves no trace the page can observe: no user
 * activation, no Playwright DOM events, no DOM changes during a screenshot except the caret
 * (aresstack/playwright4j#37, #38, #40).
 */
public class FirefoxReadSideEffectsTest {
    // The page reports its own activation state into the DOM, so reading it needs no main-world
    // evaluation (that one is the caller's and keeps userActivation: true).
    // allTextContents() and allInnerTexts() are such evaluations (evaluateAll in the client), and
    // locator.boundingBox() resolves an element handle in the main world first (#50).
    private static final String ACTIVATION_PAGE = "<html><body><h1 id='title'>Title</h1><p>one</p><p>two</p>"
            + "<input id='field' value='text'><span id='active'>unknown</span><script>"
            + "setInterval(() => { document.getElementById('active').textContent ="
            + " String(navigator.userActivation.hasBeenActive); }, 20);</script></body></html>";

    @Test
    void readOperationsDoNotActivateThePage() throws Throwable {
        runGuarded(firefox -> withPage(firefox, page -> {
            page.setContent(ACTIVATION_PAGE);
            Locator active = page.locator("#active");
            waitForText(page, active, "false");
            Map<String, Runnable> reads = new LinkedHashMap<>();
            reads.put("content", page::content);
            reads.put("textContent", () -> page.textContent("#title"));
            reads.put("innerText", () -> page.innerText("#title"));
            reads.put("getAttribute", () -> page.getAttribute("#field", "value"));
            reads.put("inputValue", () -> page.inputValue("#field"));
            reads.put("isVisible", () -> page.isVisible("#title"));
            reads.put("count", () -> page.locator("p").count());
            reads.put("title", page::title);
            reads.put("screenshot", page::screenshot);
            for (Map.Entry<String, Runnable> read : reads.entrySet()) {
                read.getValue().run();
                page.waitForTimeout(60);
                assertEquals("false", active.textContent(), read.getKey() + "() activated the page");
            }
        }));
    }

    @Test
    void locatorActionsDispatchNoPlaywrightEvents() throws Throwable {
        runGuarded(firefox -> withPage(firefox, page -> {
            page.setContent("<html><body><button id='b'>Go</button><p>x</p><script>window.__events = [];"
                    + "for (const type of ['__playwright_mark_target__', '__playwright_reset_targets__'])"
                    + " document.addEventListener(type, e => window.__events.push(e.type), true);</script></body></html>");
            page.locator("#b").hover();
            page.locator("#b").click();
            page.locator("p").textContent();
            page.locator("p").count();
            page.locator("p").evaluateAll("elements => elements.length");
            page.locator("#b").isEnabled();
            assertEquals(0, ((List<?>) page.evaluate("window.__events")).size(),
                    "Playwright events reached the page: " + page.evaluate("window.__events"));
        }));
    }

    @Test
    void screenshotChangesOnlyTheCaretStyle() throws Throwable {
        runGuarded(firefox -> withPage(firefox, page -> {
            page.setContent("<html><head><style>@keyframes spin { to { transform: rotate(1turn); } }"
                    + " #s { animation: spin 1s infinite; width: 20px; height: 20px; background: red; }</style></head>"
                    + "<body><div id='s'></div><input value='caret'><script>window.__mutations = [];"
                    + "new MutationObserver(records => { for (const r of records) window.__mutations.push("
                    + "r.type + ':' + r.target.nodeName + ':' + (r.attributeName || '') + ':' + [...r.addedNodes, ...r.removedNodes]"
                    + ".map(n => n.nodeName + (n.textContent || '').includes('caret-color')).join(','));"
                    + "}).observe(document, { subtree: true, childList: true, attributes: true, characterData: true });"
                    + "</script></body></html>");
            page.screenshot(new Page.ScreenshotOptions().setCaret(ScreenshotCaret.INITIAL));
            page.waitForTimeout(100);
            assertEquals(0, ((List<?>) page.evaluate("window.__mutations")).size(),
                    "screenshot changed the DOM: " + page.evaluate("window.__mutations"));
            page.screenshot();
            page.waitForTimeout(100);
            @SuppressWarnings("unchecked")
            List<String> mutations = (List<String>) page.evaluate("window.__mutations");
            for (String mutation : mutations) {
                // hideCaret sets caret-color in the style attribute of every editable element.
                assertEquals("attributes:INPUT:style:", mutation, "screenshot changed more than the caret: " + mutations);
            }
        }));
    }

    private static void withPage(String firefox, Consumer<Page> test) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions()
                    .setChannel("moz-firefox")
                    .setExecutablePath(Paths.get(firefox))
                    .setHeadless(true));
            try {
                test.accept(browser.newPage());
            } finally {
                browser.close();
            }
        }
    }

    private static void waitForText(Page page, Locator locator, String expected) {
        for (int i = 0; i < 50 && !expected.equals(locator.textContent()); i++) {
            page.waitForTimeout(20);
        }
        assertEquals(expected, locator.textContent());
    }
}
