# Offene Punkte (problems.md)

Verbleibende Restinseln. Jeweils mit aktuellem Stand, Diagnose und Verdacht.

Reihenfolge/Priorität:
1. ~~Download stream: TestDownload.shouldExposeStream~~ — **GELÖST** (s.u.)
2. TestBrowserTypeConnect: node.exe-Testinfrastruktur ersetzen
3. HAR Zip Export: großer Stream/Zip/Finalize-Block

## ~~TestDownload.shouldExposeStream~~ — GELÖST

**Status:** Grün. TestDownload ist jetzt **22/22**.

**Root Cause war:** Playwrights StreamDispatcher ruft `stream.read(size)` mit
`size = NaN` auf. Unser `Readable.read` behandelte das wie „Teilmenge lesen"
(`pending.subarray(0, NaN)` → leerer Buffer) statt wie Node „alles Verfügbare
zurückgeben". Fix: `read(size)` gibt bei nicht-positivem/NaN/fehlendem `size`
den kompletten gepufferten Inhalt zurück (`!(size > 0)`). Kein breiter
Stream-Umbau.

## TestBrowserTypeConnect (Insel 2) — Test-Infrastruktur braucht node.exe

**Status:** Nicht grün (`initializationError` im Test-Setup). `TestBrowserBind`
ist durch Paket 7 grün (2/0).

**Symptom:** `java.io.IOException: Cannot run program ".../playwright4j-driver/
node.exe": CreateProcess error=2`.

**Diagnose:** Das `@BeforeAll`/Setup von `TestBrowserTypeConnect` startet einen
**echten externen Playwright-Server als separaten `node.exe`-Prozess**, gegen den
sich der Test dann via `connectOverWS`/connect verbindet. In der GraalVM-Variante
gibt es kein `node.exe` — der Treiber IST GraalDriverMain (JVM), und dieser
Server-Start-Pfad der Test-Infrastruktur ist auf `node.exe` festverdrahtet.

**Einordnung:** Das ist eine **Abhängigkeit der Test-Infrastruktur von Node**, kein
fehlendes Treiber-Primitiv. `net.createServer`/`http.createServer` (das eigentliche
Ziel von Paket 7) funktionieren — `browser.bind` ist grün. Für
`TestBrowserTypeConnect` müsste der separate Server-Start ebenfalls über die
GraalDriverMain-Ersetzung laufen (Driver-Spawn-Pfad der Connect-Server-Seite),
nicht über `node.exe`. Separat zu betrachten.

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
