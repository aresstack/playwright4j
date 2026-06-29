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

## TestClientCertificates — zwei echte TLS-Cluster (1/8)

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
