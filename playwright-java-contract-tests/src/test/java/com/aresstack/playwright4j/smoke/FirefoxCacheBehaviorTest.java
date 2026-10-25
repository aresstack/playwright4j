package com.aresstack.playwright4j.smoke;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request interception in one context must not change how other contexts use the HTTP cache
 * (aresstack/playwright4j#49). Playwright's BiDi browser sent network.setCacheBehavior "bypass"
 * for the whole browser as soon as any context routed; Firefox then reloads every resource of
 * every tab with Pragma/Cache-Control: no-cache.
 */
public class FirefoxCacheBehaviorTest {
    @Test
    void routeInOneContextKeepsTheCacheOfOthers() throws Throwable {
        runGuarded(FirefoxCacheBehaviorTest::run);
    }

    private static void run(String configured) throws IOException {
        List<String> assetRequests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/page.html", exchange -> respond(exchange, "text/html", "no-store",
                "<html><head><script src=\"/asset.js\"></script></head><body>cache</body></html>"));
        server.createContext("/asset.js", exchange -> {
            assetRequests.add(String.valueOf(exchange.getRequestHeaders().getFirst("Cache-Control"))
                    + " | " + exchange.getRequestHeaders().getFirst("Pragma"));
            respond(exchange, "text/javascript", "max-age=3600", "window.assetLoaded = true;");
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/page.html";
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions()
                    .setChannel("moz-firefox")
                    .setExecutablePath(Paths.get(configured))
                    .setHeadless(true));
            try {
                BrowserContext routing = browser.newContext();
                routing.route("**/never-requested/**", route -> route.abort());
                routing.newPage();

                Page page = browser.newContext().newPage();
                page.navigate(url);
                page.navigate(url);
                assertEquals(Boolean.TRUE, page.evaluate("window.assetLoaded"));
                // A cacheable asset is fetched once; the second navigation takes it from the cache.
                assertEquals(1, assetRequests.size(), "requests for /asset.js: " + assetRequests);
                assertTrue(!assetRequests.get(0).contains("no-cache"), "request headers: " + assetRequests);
            } finally {
                browser.close();
            }
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, String type, String cacheControl, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", cacheControl);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
