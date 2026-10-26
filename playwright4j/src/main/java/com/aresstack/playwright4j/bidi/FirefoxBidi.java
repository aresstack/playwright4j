package com.aresstack.playwright4j.bidi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Read-only WebDriver BiDi access to a Firefox page that Playwright drives: the current DOM through
 * {@code browsingContext.locateNodes}, response bodies through {@code network.getData}, cookies, and
 * the page's {@code network.*} and {@code browsingContext.*} events.
 *
 * <p>None of these reads runs script in the page, so they leave no trace the page can observe (no
 * user activation, events, globals or DOM changes), unlike {@code page.content()} or locator reads.
 * Commands go over Playwright's own BiDi session, the only one Firefox accepts; only commands that
 * neither run script nor change Playwright's state are sent.
 *
 * <p>Use it from the thread that uses the page, like the rest of Playwright Java. Events are
 * delivered while Playwright waits for the browser, e.g. in {@code page.waitForTimeout}, not while
 * the thread sleeps.
 *
 * <p>Chromium has no WebDriver BiDi session in Playwright: {@link #open} throws
 * {@link UnsupportedOperationException} there; use {@code BrowserContext.newCDPSession} (for
 * example {@code DOMSnapshot.captureSnapshot}) instead.
 */
public final class FirefoxBidi implements AutoCloseable {
    /** Depth for whole-document reads: the Java client drops JSON nulls, so "unlimited" cannot be sent. */
    public static final int DEFAULT_MAX_DOM_DEPTH = 64;
    private static final Duration DEFAULT_DATA_TIMEOUT = Duration.ofSeconds(10);

    private final CDPSession session;
    private final String browsingContext;

    private FirefoxBidi(CDPSession session, String browsingContext) {
        this.session = session;
        this.browsingContext = browsingContext;
    }

    /** Opens read-only BiDi access to {@code page}. Close it to stop its event forwarding. */
    public static FirefoxBidi open(Page page) {
        CDPSession session;
        try {
            session = page.context().newCDPSession(page);
        } catch (PlaywrightException e) {
            throw unsupported(e);
        }
        try {
            String context = session.send("playwright4j.bidi.attach").get("context").getAsString();
            return new FirefoxBidi(session, context);
        } catch (PlaywrightException e) {
            // Chromium returned a real CDP session, which does not know the attach command.
            session.detach();
            throw unsupported(e);
        }
    }

    private static UnsupportedOperationException unsupported(PlaywrightException cause) {
        return new UnsupportedOperationException("FirefoxBidi needs Firefox driven over WebDriver BiDi "
                + "(channel moz-firefox). On Chromium use BrowserContext.newCDPSession(page) instead.", cause);
    }

    /** The page's top-level browsing context id, as BiDi commands and events name it. */
    public String browsingContext() {
        return browsingContext;
    }

    /** Nodes matching a CSS selector, each serialized with its descendants up to {@link #DEFAULT_MAX_DOM_DEPTH}. */
    public JsonArray css(String selector) {
        return locateNodes(locator("css", selector), 0, DEFAULT_MAX_DOM_DEPTH);
    }

    /** Nodes matching an XPath expression, each serialized with its descendants up to {@link #DEFAULT_MAX_DOM_DEPTH}. */
    public JsonArray xpath(String expression) {
        return locateNodes(locator("xpath", expression), 0, DEFAULT_MAX_DOM_DEPTH);
    }

    /**
     * {@code browsingContext.locateNodes} on the page: {@code locator} is a BiDi locator (css, xpath,
     * innerText, accessibility), {@code maxNodeCount} 0 means no limit, {@code maxDomDepth} how many
     * levels of descendants each node carries.
     */
    public JsonArray locateNodes(JsonObject locator, int maxNodeCount, int maxDomDepth) {
        JsonObject params = new JsonObject();
        params.addProperty("context", browsingContext);
        params.add("locator", locator);
        if (maxNodeCount > 0) {
            params.addProperty("maxNodeCount", maxNodeCount);
        }
        JsonObject serialization = new JsonObject();
        serialization.addProperty("maxDomDepth", maxDomDepth);
        serialization.addProperty("includeShadowTree", "open");
        params.add("serializationOptions", serialization);
        return session.send("browsingContext.locateNodes", params).getAsJsonArray("nodes");
    }

    /** The text of serialized nodes in document order, without script and style contents. */
    public static String text(JsonArray nodes) {
        StringBuilder text = new StringBuilder();
        appendText(nodes, text);
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private static void appendText(JsonArray nodes, StringBuilder text) {
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
            String name = value.has("localName") ? value.get("localName").getAsString() : "";
            if (!"script".equals(name) && !"style".equals(name) && !"noscript".equals(name)) {
                appendText(value.getAsJsonArray("children"), text);
            }
        }
    }

    /** {@code browsingContext.getTree} for this page's context and its frames. */
    public JsonObject getTree() {
        JsonObject params = new JsonObject();
        params.addProperty("root", browsingContext);
        return session.send("browsingContext.getTree", params);
    }

    /** The body of a response, by the {@code request.request} id of a network event, within 10 s. */
    public byte[] responseBody(String requestId) {
        return responseBody(requestId, DEFAULT_DATA_TIMEOUT);
    }

    /**
     * The body of a response collected by Playwright's data collector (bodies up to 20 MB). Also
     * answers for redirects and 304 revalidations. Fails after {@code timeout}.
     */
    public byte[] responseBody(String requestId, Duration timeout) {
        JsonObject params = new JsonObject();
        params.addProperty("request", requestId);
        params.addProperty("dataType", "response");
        params.addProperty("timeout", timeout.toMillis());
        JsonObject bytes = session.send("network.getData", params).getAsJsonObject("bytes");
        String value = bytes.get("value").getAsString();
        return "base64".equals(bytes.get("type").getAsString())
                ? Base64.getDecoder().decode(value) : value.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code storage.getCookies} for this page's context; {@code filter} may be null. */
    public JsonArray cookies(JsonObject filter) {
        JsonObject params = new JsonObject();
        if (filter != null) {
            params.add("filter", filter);
        }
        JsonObject partition = new JsonObject();
        partition.addProperty("type", "context");
        partition.addProperty("context", browsingContext);
        params.add("partition", partition);
        return session.send("storage.getCookies", params).getAsJsonArray("cookies");
    }

    /** Listens to a {@code network.*} or {@code browsingContext.*} event of this page, e.g. network.responseCompleted. */
    public void on(String event, Consumer<JsonObject> listener) {
        session.on(event, listener);
    }

    /** Stops listening. */
    public void off(String event, Consumer<JsonObject> listener) {
        session.off(event, listener);
    }

    @Override
    public void close() {
        session.detach();
    }

    private static JsonObject locator(String type, String value) {
        JsonObject locator = new JsonObject();
        locator.addProperty("type", type);
        locator.addProperty("value", value);
        return locator;
    }
}
