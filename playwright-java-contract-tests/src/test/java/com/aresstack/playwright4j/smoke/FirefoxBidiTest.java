package com.aresstack.playwright4j.smoke;

import com.aresstack.playwright4j.bidi.FirefoxBidi;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.deleteRecursively;
import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.headless;
import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FirefoxBidi} reads the current DOM, response bodies and cookies of a Firefox page without
 * leaving a trace the page can see, and Response.body() answers for a 304 revalidation. The page
 * reports its own user activation to the server, so nothing is read through page.evaluate.
 */
public class FirefoxBidiTest {
    private static final String PAGE = "<!doctype html><html><head><link rel=stylesheet href=/style.css></head><body>"
            + "<div id=app>empty</div><script>"
            + "fetch('/data.json').then(r => r.json()).then(d => { document.getElementById('app').textContent = 'rendered:' + d.items.join(','); });"
            + "setInterval(() => fetch('/report', { method: 'POST', cache: 'no-store', body: JSON.stringify({"
            + " hasBeenActive: navigator.userActivation.hasBeenActive, app: document.getElementById('app').textContent }) }), 100);"
            + "</script></body></html>";
    private static final String STYLE = "h1 { color: rgb(1, 2, 3); }";
    private static final String DATA = "{\"items\":[\"alpha\",\"beta\"]}";

    @Test
    void readsWithoutTraceInThePage() throws Throwable {
        runGuarded(FirefoxBidiTest::run);
    }

    private static void run(String configured) throws IOException {
        AtomicReference<String> report = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/page.html", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=bidi; Path=/");
            respond(exchange, 200, "text/html", "no-store", PAGE);
        });
        server.createContext("/style.css", exchange -> {
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            boolean revalidation = "\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"));
            respond(exchange, revalidation ? 304 : 200, "text/css", "no-cache", revalidation ? null : STYLE);
        });
        server.createContext("/data.json", exchange -> respond(exchange, 200, "application/json", "no-store", DATA));
        server.createContext("/report", exchange -> {
            report.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 204, null, "no-store", null);
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/page.html";
        Path profile = Files.createTempDirectory("playwright4j-firefox-bidi");
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.firefox().launchPersistentContext(profile,
                    new BrowserType.LaunchPersistentContextOptions()
                            .setChannel("moz-firefox")
                            .setExecutablePath(Paths.get(configured))
                            .setHeadless(headless()));
            try {
                Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);

                // Without FirefoxBidi, Firefox still has no CDP session.
                PlaywrightException cdp = assertThrows(PlaywrightException.class,
                        () -> context.newCDPSession(page).send("browsingContext.getTree"));
                assertTrue(cdp.getMessage().contains("FirefoxBidi"), cdp.getMessage());

                try (FirefoxBidi bidi = FirefoxBidi.open(page)) {
                    Map<String, String> completed = new ConcurrentHashMap<>();
                    bidi.on("network.responseCompleted", event -> {
                        JsonObject request = event.getAsJsonObject("request");
                        completed.put(request.get("url").getAsString(), request.get("request").getAsString());
                    });

                    page.navigate(url);
                    awaitReport(page, report, "rendered:alpha,beta");
                    assertTrue(report.get().contains("\"hasBeenActive\":false"), report.get());

                    // The current DOM, with the text the page's script rendered.
                    JsonArray app = bidi.css("#app");
                    assertEquals("rendered:alpha,beta", FirefoxBidi.text(app));
                    assertEquals(1, bidi.xpath("//div[@id='app']").size());
                    assertTrue(FirefoxBidi.text(bidi.css("body")).contains("rendered:alpha,beta"));
                    assertNotNull(bidi.getTree().getAsJsonArray("contexts"));

                    // Response bodies by the id of a network event.
                    String dataRequest = completed.get(url.replace("/page.html", "/data.json"));
                    assertNotNull(dataRequest, "network.responseCompleted for /data.json: " + completed);
                    assertEquals(DATA, new String(bidi.responseBody(dataRequest), StandardCharsets.UTF_8));

                    JsonArray cookies = bidi.cookies(null);
                    assertTrue(cookies.toString().contains("\"session\""), cookies.toString());

                    // None of the reads activated the page.
                    report.set("");
                    awaitReport(page, report, "rendered:alpha,beta");
                    assertTrue(report.get().contains("\"hasBeenActive\":false"), report.get());
                }

                // A revalidated style sheet (304) still has its body.
                Response style = page.waitForResponse(r -> r.url().endsWith("/style.css"), () -> page.navigate(url));
                assertEquals(304, style.status());
                assertEquals(STYLE, new String(style.body(), StandardCharsets.UTF_8));
            } finally {
                context.close();
            }
        } finally {
            server.stop(0);
            deleteRecursively(profile);
        }
    }

    private static void awaitReport(Page page, AtomicReference<String> report, String expected) {
        long end = System.currentTimeMillis() + 15_000;
        while (!report.get().contains(expected)) {
            assertTrue(System.currentTimeMillis() < end, "page report: " + report.get());
            page.waitForTimeout(100);
        }
    }

    private static void respond(HttpExchange exchange, int status, String type, String cacheControl, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (type != null) {
            exchange.getResponseHeaders().add("Content-Type", type);
        }
        exchange.getResponseHeaders().add("Cache-Control", cacheControl);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
