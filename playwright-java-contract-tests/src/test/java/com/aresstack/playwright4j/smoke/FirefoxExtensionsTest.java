package com.aresstack.playwright4j.smoke;

import com.aresstack.playwright4j.bidi.FirefoxBidi;
import com.aresstack.playwright4j.bidi.FirefoxExtensions;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
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

import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.deleteRecursively;
import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.headless;
import static com.aresstack.playwright4j.smoke.FirefoxTestSupport.runGuarded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FirefoxExtensions} installs an unsigned add-on into the installed Firefox as a temporary
 * add-on over Playwright's BiDi session: its content script runs in the page, and after uninstall
 * it no longer does.
 */
public class FirefoxExtensionsTest {
    private static final String ADDON_ID = "playwright4j-test@example.org";
    private static final String MANIFEST = "{\"manifest_version\":2,\"name\":\"playwright4j test\",\"version\":\"1.0\","
            + "\"browser_specific_settings\":{\"gecko\":{\"id\":\"" + ADDON_ID + "\"}},"
            + "\"content_scripts\":[{\"matches\":[\"<all_urls>\"],\"js\":[\"content.js\"],\"run_at\":\"document_end\"}]}";
    private static final String CONTENT = "document.documentElement.setAttribute('data-p4j-addon', 'loaded');";

    @Test
    void installsATemporaryAddon() throws Throwable {
        runGuarded(FirefoxExtensionsTest::run);
    }

    private static void run(String configured) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/page.html", exchange -> {
            byte[] body = "<!doctype html><html><body><p>page</p></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/page.html";
        Path profile = Files.createTempDirectory("playwright4j-firefox-extension");
        Path addon = Files.createTempDirectory("playwright4j-addon");
        Files.write(addon.resolve("manifest.json"), MANIFEST.getBytes(StandardCharsets.UTF_8));
        Files.write(addon.resolve("content.js"), CONTENT.getBytes(StandardCharsets.UTF_8));
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.firefox().launchPersistentContext(profile,
                    new BrowserType.LaunchPersistentContextOptions()
                            .setChannel("moz-firefox")
                            .setExecutablePath(Paths.get(configured))
                            .setHeadless(headless()));
            try {
                Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                String id = FirefoxExtensions.install(page, addon);
                assertEquals(ADDON_ID, id);

                page.navigate(url);
                awaitMarker(page, 1);
                // A new tab runs the add-on too: it belongs to the browser, not to the page.
                Page second = context.newPage();
                second.navigate(url);
                awaitMarker(second, 1);

                FirefoxExtensions.uninstall(page, id);
                page.navigate(url);
                page.waitForTimeout(1_000);
                try (FirefoxBidi bidi = FirefoxBidi.open(page)) {
                    assertEquals(0, bidi.css("html[data-p4j-addon='loaded']").size());
                }
            } finally {
                context.close();
            }
        } finally {
            server.stop(0);
            deleteRecursively(profile);
            deleteRecursively(addon);
        }
    }

    private static void awaitMarker(Page page, int expected) {
        long end = System.currentTimeMillis() + 15_000;
        try (FirefoxBidi bidi = FirefoxBidi.open(page)) {
            while (bidi.css("html[data-p4j-addon='loaded']").size() != expected) {
                assertTrue(System.currentTimeMillis() < end, "the add-on's content script did not run");
                page.waitForTimeout(100);
            }
        }
    }
}
