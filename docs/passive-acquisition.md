# Passive data acquisition (prototype)

Status: prototype, not part of a release. It explores reading page data from Firefox over WebDriver
BiDi without running script in the page.

## Read-only BiDi access through `newCDPSession`

Firefox accepts one WebDriver BiDi session, and Playwright holds it: a second client gets
`session not created: Maximum number of active sessions`. On Firefox,
`BrowserContext.newCDPSession(page)` therefore returns a session that sends commands on Playwright's
own BiDi session and forwards its events (`network.*`, `browsingContext.*`, ...) for that page.
Chromium keeps its CDP session unchanged.

Only read-only commands are passed through: `browsingContext.getTree`,
`browsingContext.locateNodes`, `network.getData`, `network.addDataCollector`,
`network.removeDataCollector`, `network.disownData`, `storage.getCookies`. Anything else, in
particular `script.*`, is refused. `playwright4j.context` answers the page's browsing context id.

```java
CDPSession bidi = context.newCDPSession(page);
String browsingContext = bidi.send("playwright4j.context").get("context").getAsString();

JsonObject params = new JsonObject();
params.addProperty("context", browsingContext);
JsonObject locator = new JsonObject();
locator.addProperty("type", "css");
locator.addProperty("value", "main");
params.add("locator", locator);
JsonObject serialization = new JsonObject();
serialization.addProperty("maxDomDepth", 64); // the Java client drops JSON nulls, so "no limit" cannot be sent
params.add("serializationOptions", serialization);
JsonObject nodes = bidi.send("browsingContext.locateNodes", params);

bidi.on("network.responseCompleted", event -> { /* event.request.request is the id for network.getData */ });
```

## Measurement

`./gradlew :playwright-java-contract-tests:passiveAcquisition` with `FIREFOX_EXECUTABLE` set runs
`PassiveAcquisitionProbe`: a local page with a cached script (`max-age`), a revalidated style sheet
(`ETag`, 304), a fetched JSON, a redirect, and a service worker that answers one request itself and
passes another to the network. It reads every response with `Response.body()` and with
`network.getData`, reads the DOM with `browsingContext.locateNodes`, `page.content()` and a locator,
and records what the page itself observes after each read (user activation, focus, events, DOM
mutations, new globals) and the request heads the server receives. The report is written to
`build/reports/passive-acquisition/report.txt`.

Results with Firefox 158.0.1 and Camoufox 156.0.1-beta.36 (with the BiDi fixes), Linux, headed:

| Read | Result |
| --- | --- |
| `Response.body()` | Works for 200 responses, cache hits and service worker responses (20-70 ms). Fails for 304 and every other 3xx with "Response body is unavailable for redirect responses". `fromServiceWorker()` is always false over BiDi. |
| `network.getData` | Returns a body for every completed response, including 304 (the cached body) and redirects. No multi-second wait on 304 was seen against a local server. |
| `browsingContext.locateNodes` | Returns the current DOM including script-rendered text (44 ms, 6 KB for the test page). The page observes no change. |
| `page.content()` | Sets `navigator.userActivation.hasBeenActive` and `isActive` to true. |
| Passive monitoring | No cache-bypass headers; the cached script is not requested again; the style sheet is revalidated with `If-None-Match`. |

Every page already has the global `__pw_bidiInsertText` before any read; it comes from Playwright's
page setup, not from these reads.
