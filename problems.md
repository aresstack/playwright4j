# Known limitations

Final state of the imported upstream contract suite (`upstreamTest`, Chromium via the `chrome`
channel): **1606 passed / 6 failed / 35 skipped**, 144 classes, ran to completion, no hangs, no
leaked playwright4j processes. All 6 failures are documented, non-blocking known limitations —
**no known real playwright4j runtime bug remains**.

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

## UNSUPPORTED_ENGINE_FAST_FAIL — Firefox / WebKit

Playwright4J is **Chromium-only** (channels `chrome` / `msedge`). Fixtures that request WebKit or
Firefox (e.g. `TestFixtureDeviceOption` — device "iPhone 14" → defaultBrowserType webkit — and
`TestFixtureOptions` — `setBrowserName("webkit")`) **fail fast with a clear error**
(`Unsupported browser engine: …`) instead of hanging. This is deliberate. Firefox support is planned
for a separate branch/version (it needs Playwright's Firefox/Juggler pipe transport rather than the
Chromium CDP/WebSocket path used today).
