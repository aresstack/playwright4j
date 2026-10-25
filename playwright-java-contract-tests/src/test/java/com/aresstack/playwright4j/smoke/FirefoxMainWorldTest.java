package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Playwright's own code must stay out of the page's main world in Firefox over WebDriver BiDi
 * (issues #36, #39, #50): no globals, no InjectedScript initialisation, and no calls of page-hookable
 * prototypes for input, frame offsets and handle previews. The caller's evaluations stay there.
 */
public class FirefoxMainWorldTest {
    // Records what a page script can observe of foreign code in its main world.
    private static final String TRAPS = "<script>"
            + "window.__seen = [];"
            + "Object.defineProperty(window, '__pwClock', { configurable: true, get() { __seen.push('__pwClock'); } });"
            + "addEventListener('__playwright_global_listeners_check__', () => __seen.push('listeners-check'), true);"
            + "const rect = Element.prototype.getBoundingClientRect;"
            + "Element.prototype.getBoundingClientRect = function () { __seen.push('rect:' + this.tagName); return rect.apply(this, arguments); };"
            + "const rects = Element.prototype.getClientRects;"
            + "Element.prototype.getClientRects = function () { __seen.push('rects:' + this.tagName); return rects.apply(this, arguments); };"
            // Reported through the DOM: the test's own page.evaluate would run Playwright's
            // UtilityScript in the main world, which the API needs for the caller's evaluations.
            + "setInterval(() => {"
            + "  document.documentElement.dataset.seen = __seen.join(',');"
            + "  document.documentElement.dataset.globals = Object.getOwnPropertyNames(window)"
            + "    .filter(name => /pw|playwright/i.test(name) && name !== '__pwClock').join(',');"
            + "}, 20);"
            + "</script>";

    @Test
    void playwrightLeavesNoTraceInTheMainWorld() throws Throwable {
        runGuarded(FirefoxMainWorldTest::run);
    }

    private static void run(String executable) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions()
                    .setChannel("moz-firefox")
                    .setExecutablePath(Paths.get(executable))
                    .setHeadless(true));
            try {
                Page page = browser.newPage();
                String inner = TRAPS + "<input id='inner'>";
                page.setContent(TRAPS + "<h1>main world</h1><input id='outer'>"
                        + "<iframe srcdoc=\"" + inner.replace("\"", "&quot;") + "\"></iframe>");
                Frame frame = page.frames().get(1);
                frame.waitForLoadState();

                page.click("#outer");
                page.keyboard().insertText("Größe");
                page.locator("#outer").type("→✓");
                FrameLocator iframe = page.frameLocator("iframe");
                iframe.locator("#inner").click();
                iframe.locator("#inner").type("Größe");
                assertNotNull(iframe.locator("#inner").boundingBox());
                ElementHandle heading = page.waitForSelector("h1");
                assertNotNull(heading);
                assertNotNull(page.locator("h1").elementHandle());

                assertEquals("Größe→✓", page.inputValue("#outer"));
                assertEquals("Größe", frame.inputValue("#inner"));
                page.waitForTimeout(200);
                assertEquals("", page.getAttribute("html", "data-globals"), "page globals");
                assertEquals("", frame.getAttribute("html", "data-globals"), "iframe globals");
                assertEquals("", page.getAttribute("html", "data-seen"), "main world of the page");
                assertEquals("", frame.getAttribute("html", "data-seen"), "main world of the iframe");
            } finally {
                browser.close();
            }
        }
    }
}
