# Known limitations

The upstream Playwright Java suite (1.62.0, 1688 tests) is a release gate for Chrome, Edge and
Firefox (`knownLimitationsCheck`, see [`docs/testing.md`](docs/testing.md#upstream-suite)). Only
the failures listed in `playwright-java-contract-tests/known-limitations/<browser>.txt` may fail;
each entry names one of the categories below. Measured on Linux (Chrome for Testing 153, Firefox
158.0.1): Chrome 1648 passed / 6 failed / 34 skipped, Firefox 1513 passed / 111 failed / 64
skipped.

Historical, already-solved diagnosis (Tracing, Download stream, HAR zip, ClientCertificates/TLS,
the node.exe launcher, etc.) has been moved to [`docs/history/problems-archive.md`](docs/history/problems-archive.md).

## KNOWN_UPSTREAM_PARITY — `TestBrowserTypeConnect.shouldRecordTraceWithSources`

Over `browserType.connect`, trace `sources` are not embedded (`expected: <1> but was: <0>`).
The **official Microsoft node driver fails identically** in this configuration
(`BROWSER_CHANNEL=chrome`, local launch-server, `PLAYWRIGHT_JAVA_SRC` set), verified 2026-07-14 by
running the same test against the official driver. **This is parity, not a playwright4j bug** — a
"fix" would diverge from upstream. Do not fix, do not skip, do not revert the `PLAYWRIGHT_JAVA_SRC`
harness env.

Mechanism: over connect the stack collection is split across processes — `tracingStarted`/`zip` run
on the client driver's localUtils while `addStackToTracingNoReply` is routed (via jsonPipe) to the
launch-server's localUtils, which has no session for that stacksId, so the stacks are dropped and
the client-side `zip(includeSources)` sees empty call stacks. Local (non-connect) tracing works
(`TestTracing.shouldCollectSources` green) because it uses a single connection/localUtils.

**Revisit only if** Microsoft changes the official connect-sources behaviour (same config → official
driver returns `1`).

## OS_ENVIRONMENT — `TestPageEmulateMedia.shouldDefaultToLight`

The test expects `prefers-color-scheme: light` as the default; on a Windows host in **Dark Mode**
Chrome inherits `dark` without an override. Environmental, not a driver defect. Do not force Chrome
globally to light (that would diverge from official behaviour).

## DELIBERATE_DIVERGENCE — Firefox `TestUtils` / `page.requestGC()`

Playwright's BiDi Firefox profile enables `dom.testing.testutils.enabled`, which exposes a
`TestUtils` global to every web page; Playwright needs it only for `page.requestGC()`. playwright4j
turns the pref off by default, so web pages do not see that global (see
[`docs/browser-signals.md`](docs/browser-signals.md)). As a consequence `page.requestGC()` on
Firefox fails with a message naming the pref. Callers that need it pass
`firefoxUserPrefs {"dom.testing.testutils.enabled": true}`. The official driver keeps the pref on.

## DELIBERATE_DIVERGENCE — Firefox user activation on page load

On every new document Playwright's BiDi layer evaluates `1 + 1` in its utility world only to create
that world, and sends it with `userActivation: true`. Firefox then reports the page as activated by
the user (`navigator.userActivation.hasBeenActive`) although no input event happened; a normal
Firefox, a minimal BiDi client and Chrome over CDP do not. playwright4j sends this one call with
`userActivation: false` (see [`docs/browser-signals.md`](docs/browser-signals.md)). Evaluations and
actions the caller asks for keep `userActivation: true`, so `page.evaluate` and clicks still
activate the page. The official driver activates every page on load.

## DELIBERATE_DIVERGENCE — `APIRequestContext` proxy schemes

The `proxy` option of `APIRequest.newContext` (and of a browser context's `request()`) is honoured,
including `bypass` and Basic credentials; https targets go through a `CONNECT` tunnel. The requests
are sent by `java.net.http`, which speaks only plain HTTP to a proxy, so a `socks4://`, `socks5://`
or `https://` proxy server fails the request with `Proxy scheme "…" is not supported for API
requests` instead of connecting directly. Credentials are sent when the proxy answers `407`, not
preemptively as Playwright on Node does. The driver JVM enables Basic for `CONNECT`
(`jdk.http.auth.tunneling.disabledSchemes`) unless the application sets that property.

## UNSUPPORTED_ENGINE — WebKit

playwright4j drives Chrome, Edge and Firefox. Fixtures that request WebKit
(`TestFixtureDeviceOption` — device "iPhone 14", whose default browser type is webkit — and
`TestFixtureOptions` — `setBrowserName("webkit")`) fail fast with `Unsupported browser engine`
instead of hanging.

## UPSTREAM_BIDI — Firefox over WebDriver BiDi

About a hundred upstream tests fail on Firefox because Playwright's BiDi backend or Firefox's
WebDriver BiDi does not implement what they check yet. Each of them fails the same way with
Microsoft's own Node.js driver on the same Firefox (`-Pplaywright4j.referenceDriver=official`,
compared test by test on Firefox 158.0.1). The main groups: touch and `tap`, drag and drop,
double and triple click, keyboard `keyCode` values, worker realms and console arguments,
WebSocket events, `emulateMedia` and the color-scheme/contrast/touch context options, cookie
`sameSite` defaults and partitioning, request post data and `Referer`, `serverAddr()` of page
responses, client certificates, abort error codes, HAR post data, and the deprecated
`BrowserChannel` enum, which has no `moz-firefox`.

Some of these do not fail on every run with either driver, so an `UPSTREAM_BIDI` entry may also
pass:

- `page.frame(name)` returns null for a frame whose name Playwright's BiDi page has not read yet:
  it asks Firefox for the frame element only after reporting the frame
  (`TestPageBasic.pageFrameShouldRespectName`, `framePressShouldWork`,
  `frameDragAndDropShouldWork`, `TestElementHandleBoundingBox.shouldHandleNestedFrames`).
- `TestPageScreenshot.shouldNotCaptureCssAnimationsInShadowDOM` fails with the official driver
  when it runs with its class and passes alone.
- `TestFrameNavigate.shouldContinueAfterClientRedirect` depends on whether the client redirect
  commits before the test's timeout.

8 tests that fail with the official driver pass with playwright4j: selector engines registered for
the main and the isolated world, and the atomic `getAttribute`/`innerHTML`/`innerText`/
`textContent` reads, which playwright4j runs in Playwright's utility world.

Revisit the list when the Playwright or the Firefox version changes: a listed test that now passes
on every run belongs off the list.
