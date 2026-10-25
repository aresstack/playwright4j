package com.aresstack.playwright4j.passive;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Prototype measurement for passive data acquisition from Firefox over WebDriver BiDi: response
 * bodies through {@link Response#body()} (network.getData on Playwright's data collector), the
 * current DOM through browsingContext.locateNodes (via the read-only BiDi pass-through behind
 * {@code BrowserContext.newCDPSession(page)} on Firefox), and the page-visible side effects of each
 * read compared with {@code page.content()} and a locator read.
 *
 * <p>The page reports what it observes (user activation, focus, events, DOM mutations, globals)
 * to the local server every 200 ms; nothing is read through page.evaluate. The server logs every
 * request head, so cache hits (no request), revalidation (304) and cache bypass (no-cache request
 * headers) are visible. Configuration: FIREFOX_EXECUTABLE (or PROBE_EXECUTABLE), PROBE_HEADLESS,
 * PROBE_LABEL, PROBE_OUT.
 */
public final class PassiveAcquisitionProbe {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8><title>passive</title>"
            + "<link rel=stylesheet href=/etag.css><script src=/cached.js></script></head><body>"
            + "<h1 id=server>Server HTML</h1><div id=app>empty</div><div id=redirected></div><div id=sw></div>"
            + "<script>\n"
            + "(function () {\n"
            + "  const baseline = new Set(Object.getOwnPropertyNames(window));\n"
            + "  const state = { events: {}, dispatched: {}, mutations: 0, swData: null, swNetwork: null };\n"
            + "  const count = (map, key) => { map[key] = (map[key] || 0) + 1; };\n"
            + "  ['focus','blur','focusin','focusout','visibilitychange','pointerdown','pointerup','pointermove',"
            + "'mousedown','mouseup','mousemove','click','keydown','keyup','input','scroll','resize','pagehide','pageshow',"
            + "'selectionchange','copy','beforeinput'].forEach(n => {\n"
            + "    window.addEventListener(n, () => count(state.events, n), true);\n"
            + "    if (n === 'selectionchange' || n === 'visibilitychange') document.addEventListener(n, () => count(state.events, 'document.' + n), true);\n"
            + "  });\n"
            + "  const dispatch = EventTarget.prototype.dispatchEvent;\n"
            + "  EventTarget.prototype.dispatchEvent = function (event) { count(state.dispatched, event.type); return dispatch.apply(this, arguments); };\n"
            + "  let observing = false;\n"
            + "  new MutationObserver(records => { if (observing) state.mutations += records.length; })"
            + ".observe(document, { subtree: true, childList: true, attributes: true, characterData: true });\n"
            + "  const json = url => fetch(url).then(r => r.json());\n"
            + "  const data = json('/api/data.json').then(d => { document.getElementById('app').textContent = 'rendered:' + d.items.join(','); });\n"
            + "  const redirect = json('/redirect').then(d => { document.getElementById('redirected').textContent = 'target:' + d.target; });\n"
            + "  const sw = new Promise(resolve => {\n"
            + "    const viaWorker = () => Promise.all([json('/sw-data.json'), json('/sw-network.json')]).then(([a, b]) => {\n"
            + "      state.swData = a; state.swNetwork = b; document.getElementById('sw').textContent = 'sw:' + a.fromSw; resolve();\n"
            + "    });\n"
            + "    if (navigator.serviceWorker.controller) { viaWorker(); return; }\n"
            + "    navigator.serviceWorker.addEventListener('controllerchange', viaWorker, { once: true });\n"
            + "    navigator.serviceWorker.register('/sw.js').catch(e => { state.swError = String(e); resolve(); });\n"
            + "  });\n"
            + "  Promise.all([data, redirect, sw]).then(() => setTimeout(() => { observing = true; state.ready = true; }, 100));\n"
            + "  const report = () => {\n"
            + "    const ua = navigator.userActivation;\n"
            + "    const added = Object.getOwnPropertyNames(window).filter(n => !baseline.has(n));\n"
            + "    const body = JSON.stringify(Object.assign({}, state, {\n"
            + "      hasBeenActive: ua ? ua.hasBeenActive : null, isActive: ua ? ua.isActive : null,\n"
            + "      hasFocus: document.hasFocus(), visibilityState: document.visibilityState,\n"
            + "      activeElement: document.activeElement ? document.activeElement.tagName : null,\n"
            + "      selection: String(getSelection()), globalsAdded: added, scrollY: window.scrollY,\n"
            + "      cachedScript: document.documentElement.dataset.cached || null, controlled: !!navigator.serviceWorker.controller }));\n"
            + "    fetch('/report', { method: 'POST', body: body, cache: 'no-store' }).catch(() => {});\n"
            + "  };\n"
            + "  setInterval(report, 200);\n"
            + "})();\n"
            + "</script></body></html>";

    private static final String SERVICE_WORKER = "self.addEventListener('install', e => self.skipWaiting());\n"
            + "self.addEventListener('activate', e => e.waitUntil(clients.claim()));\n"
            + "self.addEventListener('fetch', e => {\n"
            + "  const path = new URL(e.request.url).pathname;\n"
            + "  if (path === '/sw-data.json') e.respondWith(new Response(JSON.stringify({ fromSw: true }), { headers: { 'content-type': 'application/json' } }));\n"
            + "  else if (path === '/sw-network.json') e.respondWith(fetch(e.request));\n"
            + "});\n";

    private final List<String> requestLog = new CopyOnWriteArrayList<>();
    private final AtomicReference<JsonObject> lastReport = new AtomicReference<>();
    private final Map<String, Integer> hits = new ConcurrentHashMap<>();
    private volatile String phase = "setup";

    public static void main(String[] args) throws Exception {
        System.out.println(new PassiveAcquisitionProbe().run());
    }

    public String run() throws Exception {
        String executable = env("PROBE_EXECUTABLE", env("FIREFOX_EXECUTABLE", null));
        boolean headless = Boolean.parseBoolean(env("PROBE_HEADLESS", "false"));
        String label = env("PROBE_LABEL", "firefox");
        StringBuilder out = new StringBuilder("# Passive acquisition: ").append(label).append("\n\n");
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> serve(server), "passive-probe-server");
            acceptor.setDaemon(true);
            acceptor.start();
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/";
            Path profile = Files.createTempDirectory("passive-probe-profile");
            try (Playwright playwright = Playwright.create()) {
                BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                        .setChannel("moz-firefox").setHeadless(headless).setViewportSize(null);
                if (executable != null) {
                    options.setExecutablePath(Paths.get(executable));
                }
                BrowserContext context = playwright.firefox().launchPersistentContext(profile, options);
                try {
                    out.append("browser: ").append(context.browser() != null ? context.browser().version() : "?")
                            .append(" headless=").append(headless).append("\n");
                    probe(context, url, out);
                } finally {
                    context.close();
                }
            } finally {
                deleteRecursively(profile);
            }
        }
        out.append("\n## Server request log (phase, request line, cache-relevant headers)\n\n");
        for (String line : requestLog) {
            out.append(line).append('\n');
        }
        String text = out.toString();
        String file = env("PROBE_OUT", null);
        if (file != null) {
            Path path = Paths.get(file);
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        }
        return text;
    }

    private void probe(BrowserContext context, String url, StringBuilder out) throws Exception {
        Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
        List<Response> responses = new CopyOnWriteArrayList<>();
        page.onResponse(response -> {
            if (!response.url().endsWith("/report")) {
                responses.add(response);
            }
        });

        CDPSession bidi = context.newCDPSession(page);
        String browsingContext = bidi.send("playwright4j.context").get("context").getAsString();
        out.append("pass-through: browsing context ").append(browsingContext).append("\n");
        List<JsonObject> completed = new CopyOnWriteArrayList<>();
        bidi.on("network.responseCompleted", event -> {
            if (!event.getAsJsonObject("request").get("url").getAsString().endsWith("/report")) {
                completed.add(event);
            }
        });

        for (String load : new String[]{"load1", "load2"}) {
            phase = load;
            responses.clear();
            completed.clear();
            lastReport.set(null);
            page.navigate(url);
            JsonObject ready = awaitReport(r -> r.has("ready") && r.get("ready").getAsBoolean(), 30_000);
            // Playwright Java dispatches events only while it waits for the driver, not in Thread.sleep.
            page.waitForTimeout(800);
            out.append("\n## ").append(load).append(": Response.body() for every response\n\n");
            out.append("page ready: ").append(ready != null).append(", report ").append(lastReport.get()).append("\n");
            for (Response response : new ArrayList<>(responses)) {
                out.append(describeBody(response)).append('\n');
            }
            out.append("\n## ").append(load).append(": network.getData through the pass-through, per responseCompleted event\n\n");
            for (JsonObject event : new ArrayList<>(completed)) {
                out.append(describeGetData(bidi, event)).append('\n');
            }
        }

        phase = "reads";
        JsonObject before = settledReport();
        out.append("\n## Reads and page-visible side effects\n\nbaseline: ").append(sideEffects(before)).append("\n");

        JsonObject locate = new JsonObject();
        locate.addProperty("context", browsingContext);
        JsonObject locator = new JsonObject();
        locator.addProperty("type", "css");
        locator.addProperty("value", "html");
        locate.add("locator", locator);
        JsonObject serialization = new JsonObject();
        // The Java client drops JSON nulls, so "no limit" (null) cannot be sent; 64 levels cover the page.
        serialization.addProperty("maxDomDepth", 64);
        serialization.addProperty("includeShadowTree", "open");
        locate.add("serializationOptions", serialization);
        long start = System.nanoTime();
        JsonObject nodes = bidi.send("browsingContext.locateNodes", locate);
        long locateMs = (System.nanoTime() - start) / 1_000_000;
        String tree = GSON.toJson(nodes);
        StringBuilder text = new StringBuilder();
        collectText(nodes.getAsJsonArray("nodes"), text);
        out.append(String.format(Locale.ROOT, "locateNodes(css html, depth 64): %d ms, %d bytes JSON, text=%s%n",
                locateMs, tree.length(), abbreviate(text.toString().replaceAll("\\s+", " ").trim(), 300)));
        out.append("  contains JS-rendered text 'rendered:alpha,beta': ").append(text.indexOf("rendered:alpha,beta") >= 0)
                .append(", 'target:true': ").append(text.indexOf("target:true") >= 0)
                .append(", 'sw:true': ").append(text.indexOf("sw:true") >= 0).append('\n');
        JsonObject afterLocate = settledReport();
        out.append("after locateNodes: ").append(diff(before, afterLocate)).append('\n');

        locator.addProperty("type", "xpath");
        locator.addProperty("value", "//div[@id='app']");
        serialization.addProperty("maxDomDepth", 1);
        JsonObject app = bidi.send("browsingContext.locateNodes", locate);
        out.append("locateNodes(xpath #app, depth 1): ").append(abbreviate(GSON.toJson(app), 400)).append('\n');
        JsonObject afterXpath = settledReport();
        out.append("after xpath locateNodes: ").append(diff(afterLocate, afterXpath)).append('\n');

        start = System.nanoTime();
        String html = page.content();
        out.append(String.format(Locale.ROOT, "page.content(): %d ms, %d chars%n", (System.nanoTime() - start) / 1_000_000, html.length()));
        JsonObject afterContent = settledReport();
        out.append("after page.content(): ").append(diff(afterXpath, afterContent)).append('\n');

        String appText = page.locator("#app").textContent();
        out.append("locator(#app).textContent(): ").append(appText).append('\n');
        JsonObject afterLocator = settledReport();
        out.append("after locator textContent: ").append(diff(afterContent, afterLocator)).append('\n');

        bidi.detach();
    }

    private JsonObject settledReport() throws InterruptedException {
        Thread.sleep(700);
        lastReport.set(null);
        return awaitReport(r -> true, 5_000);
    }

    private JsonObject awaitReport(java.util.function.Predicate<JsonObject> condition, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            JsonObject report = lastReport.get();
            if (report != null && condition.test(report)) {
                return report;
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static String sideEffects(JsonObject report) {
        if (report == null) {
            return "no report";
        }
        return "hasBeenActive=" + report.get("hasBeenActive") + " isActive=" + report.get("isActive")
                + " hasFocus=" + report.get("hasFocus") + " visibility=" + report.get("visibilityState")
                + " events=" + report.get("events") + " dispatched=" + report.get("dispatched")
                + " mutations=" + report.get("mutations") + " globalsAdded=" + report.get("globalsAdded")
                + " activeElement=" + report.get("activeElement") + " selection=" + report.get("selection");
    }

    private static String diff(JsonObject before, JsonObject after) {
        if (before == null || after == null) {
            return "no report";
        }
        List<String> changes = new ArrayList<>();
        for (String key : new String[]{"hasBeenActive", "isActive", "hasFocus", "visibilityState", "events",
                "dispatched", "mutations", "globalsAdded", "activeElement", "selection", "scrollY"}) {
            JsonElement a = before.get(key);
            JsonElement b = after.get(key);
            if (a == null ? b != null : !a.equals(b)) {
                changes.add(key + ": " + a + " -> " + b);
            }
        }
        return changes.isEmpty() ? "unchanged" : String.join("; ", changes);
    }

    private static String describeBody(Response response) {
        long start = System.nanoTime();
        String result;
        try {
            byte[] body = response.body();
            result = "ok " + body.length + " bytes";
        } catch (RuntimeException e) {
            result = "FAILED " + abbreviate(e.getMessage().replaceAll("\\s+", " "), 220);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        return String.format(Locale.ROOT, "  %-28s %3d sw=%-5s %5d ms  %s", path(response.url()), response.status(),
                response.fromServiceWorker(), ms, result);
    }

    private static String describeGetData(CDPSession bidi, JsonObject event) {
        JsonObject request = event.getAsJsonObject("request");
        JsonObject response = event.getAsJsonObject("response");
        JsonObject params = new JsonObject();
        params.addProperty("request", request.get("request").getAsString());
        params.addProperty("dataType", "response");
        long start = System.nanoTime();
        String result;
        try {
            JsonObject data = bidi.send("network.getData", params);
            JsonObject bytes = data.getAsJsonObject("bytes");
            result = "ok " + bytes.get("type").getAsString() + " " + bytes.get("value").getAsString().length() + " chars";
        } catch (RuntimeException e) {
            result = "FAILED " + abbreviate(e.getMessage().replaceAll("\\s+", " "), 220);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        return String.format(Locale.ROOT, "  %-28s %3d fromCache=%-5s %5d ms  %s", path(request.get("url").getAsString()),
                response.get("status").getAsInt(), response.has("fromCache") && response.get("fromCache").getAsBoolean(), ms, result);
    }

    private static void collectText(JsonArray nodes, StringBuilder text) {
        if (nodes == null) {
            return;
        }
        for (JsonElement element : nodes) {
            JsonObject value = element.getAsJsonObject().getAsJsonObject("value");
            if (value == null) {
                continue;
            }
            if (value.has("nodeType") && value.get("nodeType").getAsInt() == 3 && value.has("nodeValue")) {
                text.append(value.get("nodeValue").getAsString()).append(' ');
            }
            if (value.has("localName") && "script".equals(value.get("localName").getAsString())) {
                continue;
            }
            collectText(value.getAsJsonArray("children"), text);
        }
    }

    private void serve(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread handler = new Thread(() -> handle(socket), "passive-probe-request");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            while (true) {
                String head = readHead(in);
                if (head == null) {
                    return;
                }
                String[] lines = head.split("\r\n");
                String[] requestLine = lines[0].split(" ");
                String path = requestLine.length > 1 ? requestLine[1] : "/";
                Map<String, String> headers = new java.util.LinkedHashMap<>();
                for (int i = 1; i < lines.length; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon > 0) {
                        headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
                    }
                }
                byte[] body = new byte[0];
                if (headers.containsKey("content-length")) {
                    body = in.readNBytes(Integer.parseInt(headers.get("content-length")));
                }
                if (!"/report".equals(path)) {
                    hits.merge(path, 1, Integer::sum);
                    List<String> cacheHeaders = new ArrayList<>();
                    for (String name : new String[]{"cache-control", "pragma", "if-none-match", "if-modified-since", "service-worker"}) {
                        if (headers.containsKey(name)) {
                            cacheHeaders.add(name + ": " + headers.get(name));
                        }
                    }
                    requestLog.add(phase + "  " + lines[0] + "  " + cacheHeaders);
                }
                respond(s.getOutputStream(), path, headers, body);
            }
        } catch (IOException ignored) {
            // Connection closed by the browser.
        }
    }

    private void respond(OutputStream out, String path, Map<String, String> headers, byte[] body) throws IOException {
        switch (path) {
            case "/":
                write(out, 200, "text/html; charset=utf-8", "Cache-Control: no-store\r\n", PAGE);
                break;
            case "/cached.js":
                write(out, 200, "text/javascript", "Cache-Control: max-age=3600\r\n",
                        "document.documentElement.dataset.cached = 'yes';");
                break;
            case "/etag.css":
                if ("\"v1\"".equals(headers.get("if-none-match"))) {
                    write(out, 304, null, "ETag: \"v1\"\r\nCache-Control: no-cache\r\n", null);
                } else {
                    write(out, 200, "text/css", "ETag: \"v1\"\r\nCache-Control: no-cache\r\n", "h1 { color: rgb(1, 2, 3); }");
                }
                break;
            case "/api/data.json":
                write(out, 200, "application/json", "Cache-Control: no-store\r\n", "{\"items\":[\"alpha\",\"beta\"]}");
                break;
            case "/redirect":
                write(out, 302, null, "Location: /api/target.json\r\nCache-Control: no-store\r\n", null);
                break;
            case "/api/target.json":
                write(out, 200, "application/json", "Cache-Control: no-store\r\n", "{\"target\":true}");
                break;
            case "/sw-network.json":
                write(out, 200, "application/json", "Cache-Control: no-store\r\n", "{\"fromNetwork\":true}");
                break;
            case "/sw.js":
                write(out, 200, "text/javascript", "Cache-Control: no-store\r\n", SERVICE_WORKER);
                break;
            case "/report":
                lastReport.set(GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class));
                write(out, 204, null, "Cache-Control: no-store\r\n", null);
                break;
            default:
                write(out, 404, "text/plain", "", "not found");
        }
    }

    private static void write(OutputStream out, int status, String type, String extraHeaders, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (type != null) {
            head.append("Content-Type: ").append(type).append("\r\n");
        }
        head.append(extraHeaders).append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 302: return "Found";
            case 304: return "Not Modified";
            default: return "Not Found";
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) {
                return head.toString(StandardCharsets.ISO_8859_1.name());
            }
        }
        return null;
    }

    private static String path(String url) {
        int slash = url.indexOf('/', url.indexOf("//") + 2);
        return slash < 0 ? url : url.substring(slash);
    }

    private static String abbreviate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.forEach(paths::add);
        }
        Collections.reverse(paths);
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
