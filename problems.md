# Offene Punkte (problems.md)

Dinge, die im Rahmen der Arbeitspakete 8a–8e / 7 nicht vollständig gelöst werden
konnten. Jeweils mit aktuellem Stand, Diagnose und Verdacht.

## TestDownload.shouldExposeStream (Paket 8c) — 1 Test

**Status:** Nicht grün. TestDownload ist sonst 21/22 (vorher 2/20).

**Symptom:** `download.createReadStream()` liefert einen leeren Strom —
`expected <Hello world> but was <>`. Vorher (vor den 8c-Stream-Änderungen)
lief der Test in einen 25s-Timeout; nach Einführung von pull-basiertem
`Readable.read(size)` + `'readable'`-Replay schlägt er jetzt schnell fehl
(kein Hänger mehr), aber mit leerem Inhalt.

**Diagnose/Verdacht:** Der StreamDispatcher von Playwright Core liest den
Download-Artefakt-Strom (`artifact.createReadStream`). Unsere
`fs.createReadStream` + `Readable` (pull-Modus) liefern offenbar beim ersten
`read(size)` des Dispatchers noch keine Bytes bzw. der Dispatcher beendet,
bevor die im Microtask gepushten Bytes konsumiert werden — d.h. ein
Reihenfolge-/Timing-Problem zwischen dem Microtask in `createReadStream`
(push(buffer)/push(null)) und dem Zeitpunkt, zu dem der Dispatcher seine
`'readable'`/`'end'`-Listener registriert und `read()` aufruft. Möglich ist
auch, dass der Download-Strom NICHT über `fs.createReadStream`, sondern über
einen CDP-/Browser-gestützten Strom läuft, dessen Daten bei uns leer
ankommen.

**Nächster Schritt (wenn wieder aufgegriffen):** Mit `-i` + Debug die
fs-/Stream-Aufrufe für genau diesen Pfad tracen (welche Methode öffnet den
Strom, welcher Pfad, ob `hostReadFile` aufgerufen wird), und die
`read()`/`'readable'`-Sequenz gegen den konkreten StreamDispatcher des
gebündelten Treibers abgleichen. Eng begrenzt auf den Download-Stream,
nicht breit am Stream-Layer umbauen (der wird von HAR/8e mitbenutzt).
