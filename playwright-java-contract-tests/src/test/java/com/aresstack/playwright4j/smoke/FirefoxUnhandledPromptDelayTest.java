package com.aresstack.playwright4j.smoke;

import com.aresstack.playwright4j.graal.UnhandledPromptDelay;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * -Dplaywright4j.bidi.unhandledPromptDelay (aresstack/playwright4j#48): a dialog nobody handles and
 * an HTTP auth challenge without credentials get Playwright's usual answer, but only after the
 * configured delay. Handlers and httpCredentials the caller set keep answering at once.
 */
public class FirefoxUnhandledPromptDelayTest {
    private static final String TIMED_CONFIRM =
            "() => { const t = performance.now(); const r = confirm('Continue?'); return [r, performance.now() - t]; }";
    private static final String TIMED_PROMPT =
            "() => { const t = performance.now(); const r = prompt('Name?', 'x'); return [r, performance.now() - t]; }";

    @Test
    void withoutThePropertyDialogsAndAuthAreAnsweredAtOnce() throws Throwable {
        runGuarded(configured -> run(configured, null));
    }

    @Test
    void withThePropertyUnhandledDialogsAndAuthWait() throws Throwable {
        // Well above the 3.7-4.2 s the wrong-credentials navigation itself takes on Camoufox/Windows.
        runGuarded(configured -> run(configured, "9000-9500"));
    }

    private static void run(String configured, String delay) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/protected", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            // Basic user:secret
            if (authorization != null && !authorization.equals("Basic dXNlcjpzZWNyZXQ=")) {
                authorization = null;
            }
            byte[] body = (authorization == null ? "login required" : "welcome").getBytes(StandardCharsets.UTF_8);
            if (authorization == null) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"test\"");
            }
            exchange.sendResponseHeaders(authorization == null ? 401 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/protected";
        String previous = System.getProperty(UnhandledPromptDelay.PROPERTY);
        setProperty(delay);
        // Forwarded to the driver JVM when Playwright starts it.
        try (Playwright playwright = Playwright.create()) {
            setProperty(previous);
            Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions()
                    .setChannel("moz-firefox")
                    .setExecutablePath(Paths.get(configured))
                    .setHeadless(true));
            try {
                Page page = browser.newPage();
                page.setContent("<p>dialogs</p>");

                List<?> confirmed = (List<?>) page.evaluate(TIMED_CONFIRM);
                assertEquals(Boolean.FALSE, confirmed.get(0), "an unhandled confirm is dismissed");
                List<?> prompted = (List<?>) page.evaluate(TIMED_PROMPT);
                assertNull(prompted.get(0), "an unhandled prompt is dismissed");
                long started = System.nanoTime();
                Response unauthorized = page.navigate(url);
                long authMillis = (System.nanoTime() - started) / 1_000_000;
                assertEquals(401, unauthorized.status(), "a challenge without credentials is cancelled");
                if (delay == null) {
                    assertTrue(millis(confirmed) < 1000, "confirm took " + confirmed.get(1) + " ms");
                    assertTrue(millis(prompted) < 1000, "prompt took " + prompted.get(1) + " ms");
                } else {
                    assertTrue(millis(confirmed) >= 8990, "confirm took " + confirmed.get(1) + " ms");
                    assertTrue(millis(prompted) >= 8990, "prompt took " + prompted.get(1) + " ms");
                    assertTrue(authMillis >= 9000, "auth was cancelled after " + authMillis + " ms");
                }

                // The caller's own handler decides, and when: the delay never applies to it.
                page.onceDialog(dialog -> dialog.accept());
                List<?> handled = (List<?>) page.evaluate(TIMED_CONFIRM);
                assertEquals(Boolean.TRUE, handled.get(0));
                assertTrue(millis(handled) < 1000, "handled confirm took " + handled.get(1) + " ms");

                BrowserContext withCredentials = browser.newContext(new Browser.NewContextOptions()
                        .setHttpCredentials("user", "secret"));
                Response authorized = withCredentials.newPage().navigate(url);
                assertEquals(200, authorized.status());
                withCredentials.close();

                // Wrong credentials: Playwright tries them once and cancels the next challenge at once.
                BrowserContext wrongCredentials = browser.newContext(new Browser.NewContextOptions()
                        .setHttpCredentials("user", "wrong"));
                Page rejecting = wrongCredentials.newPage();
                started = System.nanoTime();
                Response rejected = rejecting.navigate(url);
                long rejectedMillis = (System.nanoTime() - started) / 1_000_000;
                assertEquals(401, rejected.status());
                if (delay != null) {
                    // Relative to the delayed 401 navigation above (navigation + 9000 ms): Windows runners need
                    // seconds for the navigation itself, the delay would add 9000 ms on top.
                    assertTrue(rejectedMillis < authMillis - 2000,
                            "wrong credentials were cancelled after " + rejectedMillis + " ms, the delayed 401 took " + authMillis + " ms");
                }
                wrongCredentials.close();

                // A dialog whose page closes during the delay is left alone, and closing does not wait for it.
                Page closing = browser.newPage();
                closing.navigate("data:text/html,<p>closing</p>");
                closing.evaluate("() => { setTimeout(() => alert('late'), 0); }");
                closing.waitForTimeout(200);
                started = System.nanoTime();
                closing.close();
                long closeMillis = (System.nanoTime() - started) / 1_000_000;
                assertTrue(closeMillis < 5_000, "page.close() took " + closeMillis + " ms");
            } finally {
                browser.close();
            }
        } finally {
            setProperty(previous);
            server.stop(0);
        }
    }

    private static double millis(List<?> timed) {
        return ((Number) timed.get(1)).doubleValue();
    }

    private static void setProperty(String value) {
        if (value == null) {
            System.clearProperty(UnhandledPromptDelay.PROPERTY);
        } else {
            System.setProperty(UnhandledPromptDelay.PROPERTY, value);
        }
    }
}
