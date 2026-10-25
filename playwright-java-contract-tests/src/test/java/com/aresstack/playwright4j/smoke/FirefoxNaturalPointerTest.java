package com.aresstack.playwright4j.smoke;

import com.aresstack.playwright4j.graal.Playwright4JHost;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Firefox over WebDriver BiDi with and without {@code -Dplaywright4j.bidi.naturalPointer=true}
 * (aresstack/playwright4j#43): without it Playwright moves the mouse in one jump; with it a move
 * follows a path and a click holds the button, unless the caller sets {@code steps} or {@code delay}.
 */
public class FirefoxNaturalPointerTest {
    // The button's centre is (400, 300).
    private static final String POINTER_PAGE = "<html><body style='margin:0'>"
            + "<button id='target' style='position:absolute;left:350px;top:280px;width:100px;height:40px'>go</button>"
            + "<script>window.__events = [];"
            + "for (const type of ['mousemove', 'mousedown', 'mouseup', 'click'])"
            + " document.addEventListener(type, e => window.__events.push({ type, x: e.clientX, y: e.clientY, t: e.timeStamp }));"
            + "</script></body></html>";

    private final String configured = System.getProperty(Playwright4JHost.NATURAL_POINTER_PROPERTY);

    @AfterEach
    void restoreProperty() {
        if (configured == null) {
            System.clearProperty(Playwright4JHost.NATURAL_POINTER_PROPERTY);
        } else {
            System.setProperty(Playwright4JHost.NATURAL_POINTER_PROPERTY, configured);
        }
    }

    @Test
    void withoutThePropertyTheMouseJumps() throws Throwable {
        System.setProperty(Playwright4JHost.NATURAL_POINTER_PROPERTY, "false");
        runGuarded(firefox -> withPage(firefox, page -> {
            page.click("#target");
            List<Map<String, Object>> events = events(page);
            assertEquals(1, count(events, "mousemove"), events.toString());
            assertEquals(1, count(events, "click"), events.toString());
        }));
    }

    @Test
    void withThePropertyAClickMovesAlongAPathAndHoldsTheButton() throws Throwable {
        System.setProperty(Playwright4JHost.NATURAL_POINTER_PROPERTY, "true");
        runGuarded(firefox -> withPage(firefox, page -> {
            page.click("#target");
            List<Map<String, Object>> events = events(page);
            assertTrue(count(events, "mousemove") >= 3, "intermediate moves: " + events);
            Map<String, Object> down = first(events, "mousedown");
            Map<String, Object> up = first(events, "mouseup");
            assertEquals(400, number(down, "x"), events.toString());
            assertEquals(300, number(down, "y"), events.toString());
            assertTrue(number(up, "t") - number(down, "t") >= 50, "button held: " + events);
            assertEquals(1, count(events, "click"), events.toString());
        }));
    }

    @Test
    void withThePropertyTheCallersStepsAndDelayStay() throws Throwable {
        System.setProperty(Playwright4JHost.NATURAL_POINTER_PROPERTY, "true");
        runGuarded(firefox -> withPage(firefox, page -> {
            page.mouse().move(400, 300, new Mouse.MoveOptions().setSteps(1));
            assertEquals(1, count(events(page), "mousemove"), events(page).toString());
            // Longer than any hold the property chooses itself.
            page.mouse().click(400, 300, new Mouse.ClickOptions().setDelay(300));
            List<Map<String, Object>> events = events(page);
            assertTrue(number(first(events, "mouseup"), "t") - number(first(events, "mousedown"), "t") >= 300, events.toString());
            assertEquals(1, count(events, "click"), events.toString());

            // An explicit delay of 0 keeps Playwright's immediate press and release.
            page.evaluate("window.__events = []");
            page.mouse().click(400, 300, new Mouse.ClickOptions().setDelay(0));
            events = events(page);
            assertTrue(number(first(events, "mouseup"), "t") - number(first(events, "mousedown"), "t") < 50, events.toString());
            assertEquals(1, count(events, "click"), events.toString());
        }));
    }

    private static void withPage(String firefox, Consumer<Page> test) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions()
                    .setChannel("moz-firefox")
                    .setExecutablePath(Paths.get(firefox))
                    .setHeadless(true));
            try {
                Page page = browser.newPage();
                page.setContent(POINTER_PAGE);
                test.accept(page);
            } finally {
                browser.close();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Page page) {
        return (List<Map<String, Object>>) page.evaluate("window.__events");
    }

    private static long count(List<Map<String, Object>> events, String type) {
        return events.stream().filter(event -> type.equals(event.get("type"))).count();
    }

    private static Map<String, Object> first(List<Map<String, Object>> events, String type) {
        return events.stream().filter(event -> type.equals(event.get("type"))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + ": " + events));
    }

    private static double number(Map<String, Object> event, String key) {
        return ((Number) event.get(key)).doubleValue();
    }
}
