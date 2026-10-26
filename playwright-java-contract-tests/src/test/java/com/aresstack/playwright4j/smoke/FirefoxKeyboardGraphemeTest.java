package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * keyboard.down/up/press with a single grapheme outside the US layout (umlauts, ß, a combining
 * sequence, an emoji with modifier) over WebDriver BiDi: real trusted key events whose text the
 * browser generates, as for a person with such a layout, instead of Playwright's "Unknown key".
 * Runs against FIREFOX_EXECUTABLE (Firefox or Camoufox).
 */
public class FirefoxKeyboardGraphemeTest {
    // Every key and input event the page sees, recorded by the page itself.
    private static final String RECORDER = "<script>"
            + "window.seen = [];"
            + "for (const type of ['keydown', 'keypress', 'input', 'keyup'])"
            + "  document.addEventListener(type, e => seen.push(type + ':' + e.isTrusted + ':' + (e.key || e.data || '')), true);"
            + "</script>";

    @Test
    void downUpAndPressAcceptKeysOutsideTheUsLayout() throws Throwable {
        runGuarded(executable -> {
            try (Playwright playwright = Playwright.create();
                 Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions()
                         .setChannel("moz-firefox")
                         .setExecutablePath(Paths.get(executable))
                         .setHeadless(FirefoxTestSupport.headless()))) {
                Page page = browser.newPage();
                page.setContent("<html><body>" + RECORDER + "<input id=i></body></html>");
                page.locator("#i").click();

                page.keyboard().down("ä");
                page.keyboard().up("ä");
                page.keyboard().press("ß");
                page.keyboard().press("Ö");
                page.keyboard().down("é");
                page.keyboard().up("é");
                page.keyboard().press("👍🏽");
                page.keyboard().press("Shift+ü");

                assertEquals("äßÖé👍🏽ü", page.inputValue("#i"));
                List<?> seen = (List<?>) page.evaluate("seen");
                assertTrue(seen.stream().allMatch(e -> String.valueOf(e).contains(":true:")), "untrusted event in " + seen);
                for (String key : new String[] {"ä", "ß", "Ö", "é", "👍🏽", "ü"}) {
                    assertTrue(seen.contains("keydown:true:" + key), "no trusted keydown for " + key + " in " + seen);
                    assertTrue(seen.contains("keyup:true:" + key), "no trusted keyup for " + key + " in " + seen);
                }

                // Several graphemes and control characters are still unknown keys, as in Playwright.
                PlaywrightException refused = assertThrows(PlaywrightException.class, () -> page.keyboard().press("äö"));
                assertTrue(refused.getMessage().contains("Unknown key"), refused.getMessage());
                assertEquals("äßÖé👍🏽ü", page.inputValue("#i"));
            }
        });
    }
}
