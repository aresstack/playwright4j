# Offene Punkte (problems.md)

Verbleibende Restinseln. Jeweils mit aktuellem Stand, Diagnose und Verdacht.

Reihenfolge/Priorität:
1. ~~Download stream: TestDownload.shouldExposeStream~~ — **GELÖST**
2. TestBrowserTypeConnect: node.exe-Testinfrastruktur ersetzen — **offen** (s.u.)
3. ~~HAR Zip Export: großer Stream/Zip/Finalize-Block~~ — **GELÖST**
4. ~~Tracing (TestTracing / TestChromiumTracing)~~ — **GELÖST**

## ~~Tracing~~ — GELÖST

**Status:** `TestTracing` **10/0/1** (Skip = PLAYWRIGHT_JAVA_SRC-Assumption),
`TestChromiumTracing` **7/0**. Cluster 16 → 0.

**Gelöst durch:** fs.appendFile + fs.promises.open (FileHandle), zlib-Konstruktor-
Formen (`new DeflateRaw`), Extraktion der Trace-Viewer-Assets in `Driver.driverDir()`,
`Readable.readableLength` (StreamDispatcher-Chunk-Größe; behob den
1MB-ArrayIndexOutOfBounds), und **zero-copy `Readable.read`-Split** (subarray-Views
statt `Buffer.from`-Kopie pro Chunk) — der letzte Test war kein Hänger, sondern
O(n²)-langsam beim Lesen eines großen, ausführlichen Trace-Artefakts.

## ~~TestDownload.shouldExposeStream~~ — GELÖST

**Status:** Grün. TestDownload ist **22/22**.

**Root Cause war:** Playwrights StreamDispatcher ruft `stream.read(size)` mit
`size = NaN` auf. Unser `Readable.read` behandelte das wie „Teilmenge lesen"
(`pending.subarray(0, NaN)` → leerer Buffer) statt wie Node „alles Verfügbare
zurückgeben". Fix: `read(size)` gibt bei nicht-positivem/NaN/fehlendem `size`
den kompletten gepufferten Inhalt zurück (`!(size > 0)`).

## ~~HAR-Zip-Export / -Import (Insel 3)~~ — GELÖST

**Status:** Grün. `TestBrowserContextHar` **26/0**, `TestHar` **11/0**.

**Root Causes (mehrere, der Reihe nach):**
1. **Buffer.copy/write fehlten** → das (yazl-)Schreiben warf beim Bauen von
   Central Directory / EOCD (`comment.copy(...)`) innerhalb eines verschluckten
   `setImmediate`-Callbacks → `outputStream.end()` lief nie → WRITE-Hang.
2. **fd-basierte fs-Ops fehlten** (`open`/`read`/`fstat`/`close`) → der Zip-Reader
   (yauzl) konnte das Archiv nicht öffnen (`fs.open is not a function`).
3. **EventEmitter + Readable/Writable/Transform/PassThrough waren ES6-Klassen** →
   gebündelte CommonJS-Libs erben sie via `util.inherits` + `EventEmitter.call(this)`
   /`Readable.call(this)`, was ES6-Klassen verbieten ("cannot be invoked without
   'new'"). Auf **Funktions-Konstruktoren** umgestellt (weiterhin `new`-, `.call`-
   und `extends`-fähig).
4. **`Readable._read` wurde nie aufgerufen** → pull-basierte Quellen (fd-slicer)
   lieferten nie Daten. `_read()` wird jetzt im Flowing-Modus getrieben.
5. **`Readable._readableState.highWaterMark` und `Buffer.allocUnsafe` fehlten** →
   fd-slicers `_read` warf darauf. Beide ergänzt.

Damit läuft Record→Zip→Schreiben **und** Lesen→Inflate→routeFromHAR vollständig
(verifiziert: deflate/​inflate-Roundtrip, Central Directory, EOCD).

## ~~Screencast + Video~~ — GELÖST

**Status:** `TestScreencast` **12/0**, `TestVideo` **1/0** (war 7/5 bzw. 0/1).

**Root Cause war:** Video-Recording spawnt den **ffmpeg**-Encoder
(`ms-playwright/ffmpeg-1011/ffmpeg-win64.exe`, liegt vor) und pipet MJPEG-Frames
in dessen stdin. Unser `child_process.spawn` schickte **jeden** Spawn durch
`launchChromium` → `--remote-debugging-port` angehängt + Warten auf DevTools-
Endpoint → ffmpeg starb sofort (`Chromium exited (-1414549496)`).

**Fix:** `spawn` behandelt nur Launches mit `--remote-debugging*`-Flag als Chromium;
alles andere ist ein allgemeiner Subprozess. Neue Host-API
`spawnProcess/writeStdin/endStdin/drainProcessEvents` (stdin-Pipe für Frames,
stdout/stderr/exit über den Pump). Kein Fake-Video — echtes ffmpeg erzeugt .webm.

## TestPageEmulateMedia.shouldDefaultToLight — UMGEBUNG (OS Dark Mode), kein Treiber-Bug

**Status:** `TestPageEmulateMedia` 8/1 auf dieser Maschine; **9/0 auf Light-Mode-OS/CI**.

**Befund (verifiziert):** Der Test sendet drei `Emulation.setEmulatedMedia` —
`light` (Context-Default), `dark` (`emulateMedia(DARK)`), `""` (`emulateMedia(null)`
= Override entfernen). Genau identisch zu Upstream. Zeilen 65–70 (light, dark) sind
grün; nur Zeile 73 (nach Override-Entfernen erwartet `light`) schlägt fehl, weil
echtes Chrome (headless, channel=chrome) ohne Override das **OS-Theme** erbt.
`AppsUseLightTheme=0`/`SystemUsesLightTheme=0` → Windows ist im **Dark Mode** →
`prefers-color-scheme: dark`.

**Einordnung:** Kein Playwright4J-Treiberdefekt — die CDP-Kommandos sind byte-gleich
zu Upstream. Der Test setzt voraus, dass der OS-/Chrome-No-Override-Default `light`
ist (wie auf Upstream-CI). Auf einer Light-Mode-Umgebung grün. Bewusst **kein**
erzwungener Chrome-Flag (würde für alle Contexts von Upstream abweichen und einen
Nicht-Bug verdecken).

## TestClientCertificates — Cluster B gelöst, Cluster A offen (5/4)

**Status:** `TestClientCertificates` **5/4** (war 1/8).

**Cluster B — APIRequestContext — GELÖST (4 Tests + http):** Die Node-https-TLS-
Optionen (cert/key/pfx/passphrase/rejectUnauthorized) werden jetzt über die
Host-Grenze gereicht (`extractTlsOptions` behandelt Node-Array- und
`{pem}`/`{buf}`-Wrapper) und `JdkHostHttpClient` baut pro Config einen
gecachten `HttpClient` via `JdkTlsClientFactory`: PEM (X.509-Chain + PKCS#8 →
in-memory PKCS12 → KeyManager), PFX (PKCS12 mit Passphrase), `ignoreHTTPSErrors`
→ trust-all + Hostname-Check aus (nur für diesen Request), und ein
`ForcingKeyManager` präsentiert das Client-Cert immer (self-signed → 403 statt
401). Grün: passWith…/…Pfx, shouldFail…, shouldThrow…, shouldKeepSupportingHttp.

**Cluster A — BrowserContext clientCertificates — OFFEN (4 Tests):**
`shouldWorkWithBrowserNewContext/NewPage/PersistentContext/AsContent`. Braucht
weiterhin den Node-TLS-Proxy (`tls.createSecureContext` + net/tls-Server). Siehe
unten — großer eigener Slice, zusammen mit dem net/tls-Server-Stack.

## TestClientCertificates — Cluster A Details

**Status:** `TestClientCertificates` **1/8**. Zwei getrennte Root Causes (je 4):

**Cluster A — Browser-Context-Client-Certs (4):** `shouldWorkWithBrowserNewContext/
NewPage/PersistentContext/AsContent`. Stack:
`CRBrowser.newContext → ClientCertificatesProxy._initSecureContexts →
tls.createSecureContext`. Fehler: `import_tls.default.createSecureContext is not a
function` (unser `modules.tls` ist leer). Playwright startet pro Context einen
**in-driver TLS-terminierenden Proxy**, der dem Origin-Server das Client-Cert
präsentiert. Benötigt einen größeren Node-TLS-Stack: `tls.createSecureContext`,
TLS-Server/`tls.connect`/`TLSSocket`, Weiterleitung über `net.createServer`.
**Großer Block** (vergleichbar mit net.createServer/launch-server).

**Cluster B — APIRequestContext (4):** `passWithTrustedClientCertificates(+Pfx)`,
`shouldFailWithNoClientCertificatesProvided`, `shouldThrowWithUntrustedClientCerts`.
Fehler: `PKIX path building failed` aus unserem **Java** `JdkHostHttpClient`. Der
Fetch erreicht den TLS-Handshake, präsentiert aber kein Client-Cert und vertraut
der Server-CA nicht. **Mittel**: Cert/Key/CA/PFX/Passphrase + ignoreHTTPSErrors
von den Request-Optionen durch die Host-HTTP-API zu `JdkHostHttpClient` plumben
und dort einen `SSLContext` (KeyManager aus PEM/PKCS12, TrustManager) bauen.

**Einordnung:** Echte TLS-Feature-Arbeit, kein kleiner gemeinsamer Primitive.
Empfehlung: Cluster A wie `TestBrowserTypeConnect` als größeren eigenen Slice
behandeln; Cluster B (Java-SSLContext-Plumbing) ist der kleinere, testbare Teil.
Alternativ zuerst Screencast/Video (sauberer Medien-Cluster), dann TLS gebündelt.

## TestBrowserTypeConnect (Insel 2) — harter node.exe-Launcher-Blocker

**Status:** Nicht grün (`initializationError` im `@BeforeAll`). ~20 Tests
betroffen. `TestBrowserBind` bleibt durch Paket 7 grün (2/0).

**Symptom:** `java.io.IOException: Cannot run program
".../playwright4j-driver/node.exe": CreateProcess error=2`.

**Wer startet was (Diagnose):** `TestBrowserTypeConnect.launchBrowserServer()`
(Upstream-Test, **nicht änderbar**) baut die Commandline selbst und umgeht
bewusst unseren `Driver.createProcessBuilder()`:

```java
Path dir   = Driver.ensureDriverInstalled(...).driverDir(); // <tmp>/playwright4j-driver
String node  = dir.resolve("node.exe").toString();          // bzw. "node" auf Unix
String cliJs = dir.resolve("package/cli.js").toString();
new ProcessBuilder(node, cliJs, "launch-server", "--browser", browserType.name());
wsEndpoint = process.getInputStream().readLine();           // erwartet "ws://..." (stdout, Zeile 1)
browser    = browserType.connect(wsEndpoint);               // echte WebSocket-Browser-Session
```

- Commandline **vorher (Original-Playwright):** `<driverDir>/node.exe
  <driverDir>/package/cli.js launch-server --browser chromium`, liest
  `ws://...` von stdout.
- Commandline **nachher (gewünscht):** müsste `java … GraalDriverMain
  launch-server --browser chromium` sein — aber der Test ruft hart
  `driverDir/node.exe` auf, **nicht** `createProcessBuilder()`.

**Harter Blocker (verifiziert):** Es gibt keinen sauberen Weg, auf **Windows**
ein lauffähiges `node.exe` bereitzustellen, ohne eine native Binärdatei
auszuliefern:
- `ProcessBuilder("…node.exe")` ruft `CreateProcess` → braucht ein echtes
  PE-Executable. Ein umbenanntes Skript/Batch läuft nicht.
- `java.exe` nach `node.exe` kopieren scheitert doppelt: (a) `java.exe` läuft
  außerhalb seines JDK-Layouts nicht (Test: `exit=127`); (b) selbst wenn,
  interpretiert `java.exe <pfad>/cli.js …` den `.js`-Pfad als **Main-Klassen-
  Namen** (Source-File-Mode triggert nur bei `.java`/`--source`).
- Ein `node`-Shell-Skript mit Shebang wäre nur auf **Unix** lauffähig
  (dort wird der Test über `node` statt `node.exe` gestartet).

**Was außerdem nötig wäre (über den Launcher hinaus):**
1. `GraalDriverMain`-`launch-server`-Modus: `cli.js` mit `launch-server`-argv
   laufen lassen, den `ws://`-Endpoint auf stdout schreiben (statt des
   length-prefixed Treiberprotokolls), und nicht den stdin-Pump fahren.
2. Echtes HTTP-Request-/WebSocket-Upgrade-Parsing im `http.createServer`
   (in Paket 7 bewusst gestubbt, weil bei `browser.bind` kein Client
   connectet). Hier connectet der Java-Client per WS → der gebündelte
   PlaywrightServer braucht funktionierende `net`/`http`/`ws`-Upgrades.

**Einordnung:** Das ist **kein** einfacher Spawn-Redirect und **kein** fehlendes
Node-Primitive, sondern (a) ein Windows-Native-Launcher-Problem + (b) ein
großer WS-Server-Serving-Block. Realistisch eigener, größerer Slice; auf Windows
ohne mitgelieferten Launcher-Binary nicht grün zu bekommen. Empfehlung: zusammen
mit/nach HAR-Zip betrachten oder als bekannte Windows-Limitierung führen.

---

## TestHar / TestBrowserContextHar — HAR-Zip-Export (Insel 3) — ~11 Tests

**Status:** Nicht grün. Read/Replay-HAR ist grün (8d). Reiner Zip-EXPORT
hängt. Betroffen: `shouldProduceExtractedZip`, `shouldRoundTripHarZip`,
`shouldRoundTrip*Zip`, `shouldUpdateHarZip*`, `shouldRoundTripHarWithPostData`,
`shouldDisambiguateByHeader`, `shouldIgnoreAbortedRequests`, `TestHar.shouldAttachContent`.

**Was funktioniert (in 8e implementiert):**
- `zlib.deflateRaw`/`inflateRaw`/`gzip`/`gunzip` (Host-gestützt, `Deflater`/`Inflater`
  nowrap=raw) — verifiziert: Deflate läuft (`in=4668 out=1068`).
- Node-`Buffer`-Integer-Accessor (`writeUInt32LE`/`writeUInt16LE`/`readUInt32LE`/…,
  `writeBigUInt64LE`) — der Zip-Writer baut damit erfolgreich die Local-/Central-
  Directory-Header.
- Binär-korrektes `fs.writeFile`/`writeFileSync` (Buffer statt `String(...)`).
- `stream.Readable.from`, `stream.pipeline`/`finished` (+ `stream.promises`),
  "sticky" Lifecycle-Events (`open`/`ready`/`finish`/`close`) für spät
  angehängte Listener.

**Genauer Befund (per Debug-Trace):** Beim Export schreibt die gebündelte
Zip-Bibliothek das KOMPLETTE Zip (alle 12 Chunks inkl. Central Directory +
EOCD) per `pipe` in den `.har`-`createWriteStream` — aber **beendet ihren
Output-Stream nie** (`push(null)`/`end()` wird nicht aufgerufen). Damit ruft
der Pipe nie `dest.end()` → `_final` → `writeFileBase64` auf, der
`harExport`-Befehl settlet nie, und der Test läuft in den (8f-)Timeout.

**Verdacht:** Die interne async-Choreografie der gebündelten Zip-Lib (yazl-artig)
finalisiert ihren Output-Stream über einen Mechanismus (per-Entry
Deflate-/CRC-Transform-`end`, Entry-Pump-Counter, `setImmediate`-Kette o.ä.),
der mit unserem Stream-Layer nicht exakt zusammenspielt. Ohne den
gebündelten Lib-Quelltext nicht eindeutig lokalisierbar.

**Hinweis/Trade-off:** Durch 8e schlagen die Zip-Export-Tests jetzt per Timeout
fehl (vorher schneller `deflateRaw is not a function`-Fehler). Der Build
terminiert dank 8f weiterhin sauber (0 Leaks), nur langsamer für die
HAR-Export-Tests.

**Nächster Schritt (wenn wieder aufgegriffen):** Per-Entry-Pipeline der Zip-Lib
nachstellen (welche Transform-Kette, wie wird der Output-Stream beendet?),
ggf. `Transform`-`end`/`finish`-Reihenfolge und Backpressure (`write()`-Rückgabe,
`drain`) exakt an Node angleichen. Eng am Stream-Layer, der von Download (8c)
mitbenutzt wird — Regressionen vermeiden.

---

**Nächster Schritt (wenn wieder aufgegriffen):** Mit `-i` + Debug die
fs-/Stream-Aufrufe für genau diesen Pfad tracen (welche Methode öffnet den
Strom, welcher Pfad, ob `hostReadFile` aufgerufen wird), und die
`read()`/`'readable'`-Sequenz gegen den konkreten StreamDispatcher des
gebündelten Treibers abgleichen. Eng begrenzt auf den Download-Stream,
nicht breit am Stream-Layer umbauen (der wird von HAR/8e mitbenutzt).

---

## Block net/tls/connect/server — Phase 0 Bestandsaufnahme (Stand commit eeaf709)

**Phase 3 erledigt (commit eeaf709):** TestChromium 5/0 — connect/connectOverCDP
reichen jetzt Custom-Handshake-Header durch (`HostWebSocketClient.open(url, headers)`
+ JS-Transport flattet `options.headers`), und ein non-CDP-Frame killt nicht mehr
den Treiber (Transport schließt sich bei JSON.parse-Fehler selbst → connect rejectet
schnell statt zu hängen/crashen). `executionError` (@AfterAll-Folgefehler) verschwunden.

**Noch rot — zwei unabhängige Inseln:**

### ClientCertificates A (4 Tests) — voller TLS-MITM fehlt
Fehler: `import_tls.default.createSecureContext is not a function`
(`ClientCertificatesProxy._initSecureContexts`, `socksClientCertificatesInterceptor.js`).
Playwright fährt einen lokalen SOCKS5-Proxy hoch, lenkt Chrome dorthin und macht pro
Verbindung einen TLS-Man-in-the-Middle. Benötigte (im Bundle verifizierte) Primitive:
- `tls.createSecureContext({pfx[],key[],cert[]})` — Java SSLContext (wie JdkTlsClientFactory).
- `tls.connect({socket, host, port, ALPNProtocols, servername, secureContext, rejectUnauthorized}, cb)`
  = TLS-CLIENT über bestehende Duplex (SSLEngine client-mode + ALPN-Aushandlung).
- `tls.createServer({key,cert,ALPNProtocols})` + `emit('connection', socket)`
  → `'secureConnection'(tlsSocket)` = TLS-SERVER-Handshake über Duplex (SSLEngine server-mode).
- `net.isIP` (vorhanden), `stream.Duplex` (vorhanden), `generateSelfSignedCertificate` (utils — prüfen).
- `http2.performServerHandshake` optional (im Bundle per `'... in http2'` geguarded).
- Harte Nebenlücke: `net.createConnection` ist aktuell `unsupported`, wird aber von
  `happyEyeballs.createSocket` (Outbound-Connect des SOCKS-Proxy) gebraucht.

### TestBrowserTypeConnect — node.exe Launcher fehlt
`initializationError`: Test ruft DIREKT
`ProcessBuilder("<driverDir>/node.exe", "package/cli.js", "launch-server", "--browser", "chromium")`
und liest die `ws://`-Zeile von stdout. driverDir hat `package/`, aber kein `node.exe`
→ IOException ("Failed to launch server"). **Architektur-Entscheidung offen:** node.exe muss
eine echte Windows-PE-Datei sein (ProcessBuilder ist nicht abfangbar; ein Skript namens
node.exe läuft nicht). Optionen: (A) winziger nativer Launcher als committete Bin-Ressource
(re-exec auf `java … GraalDriverMain --node-compat <argv>`), (B) Build-Zeit-C-Kompilat,
(C) zurückstellen + als nativer Blocker dokumentieren. Danach Phase 2: net.Server-Konstruktor,
net.createConnection, http.createServer + WS-Upgrade, Socket-Lifecycle.

**Reihenfolge-Empfehlung:** Phase 4 (TLS) ist unabhängig vom node.exe-Thema und macht
4 reale Tests grün → vorziehbar. Phase 1/2/5 (launch-server) hängen alle am node.exe-PE.

---

## KNOWN LIMITATION (kein playwright4j-Bug): `shouldRecordTraceWithSources` über connect

**NICHT FIXEN. NICHT SKIPPEN. NICHT `PLAYWRIGHT_JAVA_SRC` (a3f61f1) zurückdrehen.**

`TestBrowserTypeConnect.shouldRecordTraceWithSources` schlägt über `browserType.connect`
fehl: `expected: <1> but was: <0>` (0 eingebettete `resources/src@*.txt`).

**Das ist KEIN playwright4j-Fehler — es ist Parität mit dem offiziellen Microsoft-Driver.**
Verifiziert 2026-07-14 (Slice 5b): Derselbe Test gegen den **offiziellen** Microsoft-Node-Driver
(`com.microsoft.playwright:playwright + driver + driver-bundle 1.59.0`, ohne playwright4j;
Upstream-Testquellen gegen den offiziellen Driver neu kompiliert), gleiche Konfiguration
(`BROWSER_CHANNEL=chrome`, lokaler launch-server, `PLAYWRIGHT_JAVA_SRC` gesetzt) → **der
offizielle Driver scheitert IDENTISCH** mit `expected: <1> but was: <0>`. Ein „Fix" in
playwright4j würde also vom Upstream-Verhalten ABWEICHEN (Parität verletzen).

**Mechanismus (bei beiden Drivern):** Über connect ist die Stack-Sammlung auf zwei Prozesse
gesplittet. Der Java-Client (`TracingImpl`) ruft `tracingStarted`/`zip(includeSources)` über
die localUtils der **MAIN-Connection** (`L_main`, Client) — `BrowserTypeImpl.connect` erzeugt die
Remote-Connection mit `this.connection.localUtils`. Aber `addStackToTracingNoReply`
(`Connection.java` 172-178) geht an guid `"localUtils"` **auf der Remote-Connection** → jsonPipe
→ localUtils des **launch-servers**, wo keine Session mit dieser `stacksId` existiert → Stacks
verworfen. Client-`zip` sieht `callStacks==0` (belegt: `zip mode=append sessions=1 inclSrc=true
cs=0`); der Server-`SerializedFS.zip` bettet nie `.txt`-Sources ein. Lokal (nicht-connect)
funktioniert es, weil eine Connection/eine localUtils. Upstream-CI läuft vermutlich in anderer
Konfiguration (heruntergeladenes Chromium statt channel=chrome) und ist dort grün.

**Wann wieder ansehen:** Falls Microsoft das offizielle connect-Sources-Verhalten ändert
(gleiche Konfiguration → offizieller Driver liefert `1`), dann — und erst dann — ist ein
playwright4j-seitiger Angleich sinnvoll. Solange der offizielle Driver hier `0` liefert:
ehrlich rot lassen, als Upstream-Parität einstufen.

**Orthogonale, bereits gelandete Fixes:** `542c88e` (Buffer.writeUIntBE — Remote-Video saveAs,
echter playwright4j-Bug, grün), `a3f61f1` (PLAYWRIGHT_JAVA_SRC-Harness, lokale Sources grün),
`2701560` (Fast-Fail nicht unterstützter Engines).

## KNOWN LIMITATION: nicht unterstützte Browser-Engines (WebKit/Firefox)

**NICHT FIXEN (kein Feature-Fix), NICHT SKIPPEN.** Ehrlich rot, aber kein Hänger.

playwright4j ist **Chromium-only** (Channels `chrome`/`msedge`). Fixtures, die WebKit oder
Firefox anfordern, scheitern **schnell mit klarer Meldung** statt zu hängen (Commit `2701560`):
`TestFixtureDeviceOption.testPredefinedDeviceParameters` (Device „iPhone 14" → defaultBrowserType
webkit) und `TestFixtureOptions.*` (`setBrowserName("webkit")`). Meldung: `Unsupported browser
engine: webkit. playwright4j runs a Chromium-only runtime …`. Der Fast-Fail ist bewusst so — er
verhindert den früheren Fixture-Setup-Hang, der ganze Sweeps blockierte. Erst wenn WebKit/Firefox
tatsächlich implementiert werden sollen, ist hier Arbeit nötig.

## KNOWN LIMITATION: `TestPageEmulateMedia.shouldDefaultToLight` (OS-Theme)

**KEIN Treiberdefekt.** Der Test erwartet `prefers-color-scheme: light` als Default; auf einem
Windows-Host mit **Dark Mode** erbt Chrome ohne Override `dark`. Environment-abhängig. Nicht
versuchen, Chrome global auf light zu zwingen (das würde vom offiziellen Verhalten abweichen).
