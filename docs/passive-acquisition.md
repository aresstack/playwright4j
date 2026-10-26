# Reading pages without script: FirefoxBidi

Playwright reads page content through scripts it runs in the page: `page.content()`, locators and
`evaluate`. On Firefox, playwright4j runs Playwright's own reads in an isolated world without user
activation (see [browser-signals.md](browser-signals.md)), but they still execute script in the
page's process. `com.aresstack.playwright4j.bidi.FirefoxBidi` reads over WebDriver BiDi instead,
without running any script in the page, and also reaches what the Playwright API does not offer:
response bodies by network event, including redirects, and cookies by browsing context.

| Method | BiDi command | Returns |
| --- | --- | --- |
| `css(selector)`, `xpath(expression)`, `locateNodes(locator, maxNodeCount, maxDomDepth)` | `browsingContext.locateNodes` | Matching nodes with their attributes and descendants, as the page has them now |
| `FirefoxBidi.text(nodes)` | – | Their text, without script and style contents |
| `getTree()` | `browsingContext.getTree` | The page's browsing context and its frames |
| `on(event, listener)`, `off(event, listener)` | `network.*`, `browsingContext.*` events | Requests, responses, navigations of this page |
| `responseBody(requestId)`, `responseBody(requestId, timeout)` | `network.getData` | A response body by the `request.request` id of a network event |
| `cookies(filter)` | `storage.getCookies` | Cookies of the page's context |

```java
try (FirefoxBidi bidi = FirefoxBidi.open(page)) {
    Map<String, String> requests = new ConcurrentHashMap<>();
    bidi.on("network.responseCompleted", event -> {
        JsonObject request = event.getAsJsonObject("request");
        requests.put(request.get("url").getAsString(), request.get("request").getAsString());
    });

    page.navigate("https://example.org/search?q=java");
    page.waitForLoadState();

    String results = FirefoxBidi.text(bidi.css("#results"));
    byte[] json = bidi.responseBody(requests.get("https://example.org/api/search?q=java"));
}
```

`FirefoxBidi` only sends commands that run no script and leave Playwright's own state alone. It
uses Playwright's BiDi session: Firefox accepts one session, so a second client next to Playwright
is refused (`session not created: Maximum number of active sessions`). Use it from the thread that
uses the page. Events arrive while Playwright waits for the browser (`page.waitForTimeout`,
`page.waitForLoadState`, any other call), not while the thread sleeps.

On Chrome and Edge, `FirefoxBidi.open` throws `UnsupportedOperationException`; Playwright drives
them over CDP, where `BrowserContext.newCDPSession(page)` gives the equivalent access (for example
`DOMSnapshot.captureSnapshot`, which also returns layout and styles).

## Limits

- **DOM**: nodes, attributes and text. No layout, computed styles or visibility, no property-only
  state such as the current value of a typed-in input field, and no closed shadow roots. The
  default depth is 64 levels below each matched node; the Playwright Java client drops JSON nulls,
  so "no limit" cannot be requested.
- **Response bodies**: what Playwright's data collector keeps, bodies up to 20 MB of the page's
  requests. Network data is what the server sent, not what the page made of it. `responseBody`
  fails after 10 s by default: Firefox has been reported to hold `network.getData` for seconds on
  304 revalidations.
- **Service workers**: their responses arrive and have bodies, but Firefox does not mark them as
  service worker responses, so `Response.fromServiceWorker()` is always false.

## Response.body() for 304

Over BiDi, `Response.body()` of a 304 revalidation returns the body Firefox took from its cache.
Playwright treats every 3xx status as a redirect without a body; for Firefox playwright4j answers
304 like a 200. Redirects (301, 302, 303, 307, 308) still have no body in `Response.body()`;
`FirefoxBidi.responseBody` returns what Firefox received for them.

## What a page sees

`./gradlew :playwright-java-contract-tests:passiveAcquisition` with `FIREFOX_EXECUTABLE` set runs
`PassiveAcquisitionProbe`: a local page with a cached script (`max-age`), a revalidated style sheet
(`ETag`, 304), a fetched JSON, a redirect, and a service worker that answers one request itself and
passes another to the network. It reads every response with `Response.body()` and with
`FirefoxBidi.responseBody`, reads the DOM with `FirefoxBidi`, `page.content()` and a locator, and
records what the page observes after each read (user activation, focus, events, DOM mutations, new
globals) and the request heads the server receives. The report is written to
`build/reports/passive-acquisition/report.txt`.

Firefox 158.0.1 and Camoufox 156.0.1-beta.36 (with the BiDi fixes, see [camoufox.md](camoufox.md))
give the same results:

- `FirefoxBidi` reads (whole document, 64 levels, about 45 ms for the test page) change nothing the
  page can observe; the text rendered by the page's scripts is included. `page.content()` and
  locator reads leave no observable trace either.
- Reading response bodies changes no request: no cache-bypass headers, the cached script is not
  requested again, the style sheet is revalidated with `If-None-Match`.
- `FirefoxBidiTest` (part of `firefoxSmoke`) checks the DOM, response body, cookie and 304 reads and
  that the page stays without user activation.
