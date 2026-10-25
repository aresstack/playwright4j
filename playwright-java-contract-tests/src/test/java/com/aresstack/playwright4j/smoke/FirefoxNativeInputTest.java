package com.aresstack.playwright4j.smoke;

import com.aresstack.playwright4j.graal.Playwright4JHost;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Firefox over WebDriver BiDi with and without {@code -Dplaywright4j.bidi.nativeBehavior=true}:
 * how {@code fill} enters text (#41) and how an action scrolls its target into view (#42). The
 * result is the same either way; only the events the page sees differ.
 */
public class FirefoxNativeInputTest {
    private static final String PAGE = "<input id=field value=old><textarea id=area></textarea>"
            + "<div id=editable contenteditable>old</div>"
            + "<div style='height: 3000px'></div><button id=below onclick='window.__clicked = true'>below</button>"
            + "<div style='height: 3000px'></div>";
    // Records the type and trust of every input and scroll event, in order.
    private static final String RECORD_EVENTS = "() => { window.__events = [];"
            + " for (const type of ['beforeinput', 'input', 'wheel', 'scroll'])"
            + "  addEventListener(type, e => window.__events.push(type + ':' + e.isTrusted + (e.inputType === undefined ? '' : ':' + e.inputType)), { capture: true, passive: true }); }";

    // What Firefox's editor reports for execCommand("insertText"): no beforeinput, which the Input
    // Events spec leaves out for execCommand (docs/browser-signals.md).
    private static final List<String> TRUSTED_INSERT = List.of("input:true:insertText");

    private final String configured = System.getProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY);

    @AfterEach
    void restoreProperty() {
        if (configured == null) {
            System.clearProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY);
        } else {
            System.setProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY, configured);
        }
    }

    @Test
    void withoutThePropertyFillDispatchesASyntheticInputEvent() throws Throwable {
        System.setProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY, "false");
        withPage(page -> {
            page.fill("#field", "Größe");
            assertEquals("Größe", page.inputValue("#field"));
            assertEquals(List.of("input:false:"), events(page));
        });
    }

    @Test
    void withThePropertyFillEntersTextThroughTheEditor() throws Throwable {
        System.setProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY, "true");
        withPage(page -> {
            page.fill("#field", "Größe");
            assertEquals("Größe", page.inputValue("#field"));
            assertEquals(TRUSTED_INSERT, events(page));

            page.fill("#area", "one\ntwo");
            assertEquals("one\ntwo", page.inputValue("#area"));
            assertEquals(TRUSTED_INSERT, events(page));

            page.fill("#editable", "neu");
            assertEquals("neu", page.textContent("#editable"));
            assertEquals(TRUSTED_INSERT, events(page));

            page.keyboard().insertText("!");
            assertEquals("neu!", page.textContent("#editable"));
            assertEquals(TRUSTED_INSERT, events(page));

            page.fill("#field", "");
            assertEquals("", page.inputValue("#field"));
        });
    }

    @Test
    void withoutThePropertyAClickScrollsBelowTheFoldWithoutWheelEvents() throws Throwable {
        System.setProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY, "false");
        withPage(page -> {
            page.click("#below");
            assertEquals(true, page.evaluate("window.__clicked"));
            List<String> events = events(page);
            assertTrue(events.stream().noneMatch(event -> event.startsWith("wheel:")), events.toString());
        });
    }

    @Test
    void withThePropertyAClickScrollsBelowTheFoldWithTheMouseWheel() throws Throwable {
        System.setProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY, "true");
        withPage(page -> {
            page.click("#below");
            assertEquals(true, page.evaluate("window.__clicked"));
            List<String> events = events(page);
            assertEquals("wheel:true", events.get(0), events.toString());
            assertTrue(events.contains("scroll:true"), events.toString());

            page.locator("#field").scrollIntoViewIfNeeded();
            assertTrue((Boolean) page.evaluate("() => document.getElementById('field').getBoundingClientRect().top >= 0"));
            List<String> upward = events(page);
            assertEquals("wheel:true", upward.get(0), upward.toString());
        });
    }

    @Test
    void withThePropertyTheWheelReachesNestedContainersAndFrames() throws Throwable {
        System.setProperty(Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY, "true");
        withPage(page -> {
            page.setContent("<div style='height: 2000px'></div>"
                    + "<div id=box style='height: 200px; overflow: auto'><div style='height: 2000px'></div>"
                    + "<button id=inner onclick='window.__clicked = true'>inner</button></div>"
                    + "<div style='height: 2000px'></div>"
                    + "<iframe style='height: 200px' srcdoc=\"<div style='height: 2000px'></div>"
                    + "<button onclick='window.__clicked = true'>framed</button>\"></iframe>"
                    + "<div style='height: 2000px'></div>");
            page.evaluate(RECORD_EVENTS);
            page.click("#inner");
            assertEquals(true, page.evaluate("window.__clicked"));
            List<String> nested = events(page);
            assertEquals("wheel:true", nested.get(0), nested.toString());
            assertTrue((Boolean) page.evaluate("() => document.getElementById('box').scrollTop > 0"), "the inner container scrolled");

            com.microsoft.playwright.Frame frame = page.frames().get(1);
            frame.evaluate(RECORD_EVENTS);
            frame.click("button");
            assertEquals(true, frame.evaluate("window.__clicked"));
            List<String> framed = events(page);
            assertEquals("wheel:true", framed.get(0), framed.toString());
            List<String> inside = (List<String>) frame.evaluate("() => window.__events.splice(0)");
            assertEquals("wheel:true", inside.get(0), inside.toString());
        });
    }

    private interface PageCheck {
        void run(Page page) throws Exception;
    }

    private static void withPage(PageCheck check) throws Throwable {
        runGuarded(executable -> {
            try (Playwright playwright = Playwright.create();
                 Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions()
                         .setChannel("moz-firefox")
                         .setExecutablePath(Paths.get(executable))
                         .setHeadless(FirefoxTestSupport.headless()))) {
                Page page = browser.newContext(new Browser.NewContextOptions().setViewportSize(800, 600)).newPage();
                page.setContent(PAGE);
                page.evaluate(RECORD_EVENTS);
                check.run(page);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> events(Page page) {
        List<String> events = (List<String>) page.evaluate("() => window.__events.splice(0)");
        return events;
    }
}
