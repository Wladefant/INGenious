# INGenious mit den ING-Panels einrichten und aktuell halten

**Für wen:** eine Kollegin oder ein Kollege aus der Testautomatisierung, die/der das Ganze auf
dem **eigenen ING-Windows-Gerät** einrichtet. Technischer Hintergrund vorausgesetzt,
Administratorrechte **nicht**.

**Das ist die einzige Anleitung, die Sie brauchen.** Fünf ältere Seiten beschreiben denselben
Weg in Teilen; sie stehen unten unter [Was diese Seite ersetzt](#was-diese-seite-ersetzt).
*(Die fünf verweisen noch nicht von sich aus hierher — das steht noch aus.)*

> ## ⚠ Was heute noch nicht geht
> Die **Werkzeuge** (Kapitel 0) liegen bis heute nur im **internen, nicht öffentlichen**
> Repository. Diese Anleitung setzt voraus, dass Sie darauf Zugriff haben. Der Quelltext von
> Studio selbst ist öffentlich und braucht keine Anmeldung — aber eine Einrichtung **allein
> aus dem öffentlichen Teil ist noch nicht möglich**, weil in fünf Werkzeug-Dateien
> ING-interne Kennungen im Quelltext stehen, die dort nicht hingehören. Das ist bekannt und
> wird getrennt gelöst. **Bitte zuerst Frage 1 in Kapitel 6 klären** — davon hängt ab, ob
> Kapitel 4 für Sie überhaupt funktioniert.

> ## Die wichtigste Regel
> Nach jedem Schritt steht, **was Sie sehen müssen**, wenn es geklappt hat. Sehen Sie etwas
> anderes: aufhören und melden. Nicht raten. Genau die Stellen, an denen Sie ins Grübeln
> kommen, sind die Fehler dieser Anleitung — und die wollen wir hören.

**Zeichen:** ✅ = auf einem ING-Gerät wirklich so gesehen · ❓ = noch **nicht** auf einem
zweiten Gerät gesehen; wenn es bei Ihnen anders aussieht, liegt das nicht an Ihnen.

**Dauer:** einmalig 30–60 Minuten, davon die Hälfte Wartezeit beim Bauen.

---

## 0. Was am Ende dasteht

Ein Startsymbol, ein Doppelklick, und Studio geht auf — mit der Schaltfläche **`Ablauf`**
rechts in der Werkzeugleiste, die durch Testfall, Testkunde und Aufnahme führt.

Dahinter stecken **drei Teile, die getrennt voneinander veralten können**, und genau daran ist
diese Anleitung entlanggebaut:

| Teil | Was es ist |
|---|---|
| **Studio** | INGenious selbst, aus dem Quelltext gebaut |
| **Die Werkzeuge** | eine Sammlung kleiner Programme, die die Panels im Hintergrund aufrufen |
| **Das Plugin** | die ING-Schaltflächen in Studio, eine einzelne Datei in der Installation |

Wenn eines davon älter ist als die anderen, sieht man das dem Programm nicht an — bis eine
Schaltfläche grau ist und niemand sagt, warum. **Genau das ist am 28. Juli passiert.** Deshalb
gibt es Kapitel 4 (ein Befehl, der alle drei aktualisiert) und Kapitel 5 (Studio sagt es Ihnen
von selbst).

---

## 1. Was Sie auf dem Gerät brauchen — und woher

**Diese Tabelle ist der wichtigste Teil der Seite.** Sie sagt auch, was wir **nicht** wissen.
Bitte bei jedem ❓ kurz nachsehen und uns das Ergebnis geben — das ist in fünf Minuten erledigt
und erspart uns beiden eine Stunde am Einrichtungstermin.

| Was | Wofür | Auf einem ING-Standardgerät |
|---|---|---|
| **Java 17** | Studio läuft darauf | ❓ Auf dem uns bekannten Gerät liegt es unter „Program Files / Java". **Achtung:** das voreingestellte Java ist **1.8** und zu alt. Wo Ihres liegt, sagt Ihnen Kapitel 2, Schritt 1 |
| **Maven 3.9** | baut Studio und das Plugin | ❓ Voraussichtlich **nicht** vorhanden. Kein Installationsprogramm nötig — ein entpackter Ordner im eigenen Benutzerverzeichnis reicht. Bitte bei uns anfordern |
| **git** | holt die Werkzeuge und die Aktualisierungen | ❓ Auf dem uns bekannten Gerät ist es benutzerweit installiert, ohne Administratorrechte. Ob es auf Ihrem liegt: bitte prüfen |
| **Node 20 oder neuer** | die Werkzeuge sind Node-Programme | ❓ Ohne Node laufen „Aufnahme prüfen" und „Aufnahme abgeben" nicht. Ob es auf Ihrem Gerät liegt: bitte prüfen |
| **Die Werkzeuge** | siehe Kapitel 0 | Aus dem internen Git-Repository. **Ob Sie darauf Zugriff haben, wissen wir nicht** — siehe Kapitel 6, Frage 1 |
| **INGenious-Quelltext** | daraus wird Studio gebaut | Öffentlich, ohne Anmeldung erreichbar. Kein Zugangsproblem |
| **Testdaten-Datei** | die Kundenliste im Panel „Testdaten" | Bekommen Sie von uns auf einem internen Weg. **Sie liegt in keinem Repository und darf auch in keines** |
| **Testfall-Datei aus Azure DevOps** | die Liste im Panel „Testfall wählen" | Erzeugen Sie selbst mit Ihrer eigenen ADO-Anmeldung, oder Sie bekommen sie von uns |

**Nichts davon braucht Administratorrechte, und es wird keine `.exe` verteilt.** Alles ist
entweder schon da, kommt aus dem Company Portal oder ist ein entpackter Ordner in Ihrem
eigenen Benutzerverzeichnis.

---

## 2. Einrichten

### Schritt 1 — Kann dieses Gerät INGenious überhaupt starten?

Diese Frage zuerst, weil sie in einer Minute beantwortet ist und weil eine falsche Antwort
alles Weitere sinnlos macht.

Sie brauchen dafür die Werkzeuge (Kapitel 1). Holen Sie sie sich mit `git clone` an eine
Stelle in Ihrem Benutzerverzeichnis, und rufen Sie dann im Ordner `tools` auf:

```
ingenious-launch.cmd -Check
```

**Das sehen Sie, wenn es geklappt hat** ✅

```
Java      : 17.0.12   (C:\...\jdk-17)
INGenious : C:\...\ingenious-playwright-...

OK - dieses Gerat kann INGenious starten.
```

Die Zeile `Java` ist die Antwort auf die erste ❓-Zeile der Tabelle: **hier steht, wo auf
Ihrem Gerät ein Java 17 liegt.** Das Programm rät das nicht — es fragt jedes gefundene Java
selbst nach seiner Version.

**Das sehen Sie, wenn kein Java 17 da ist** ✅
Eine rote Meldung, darunter eine Liste *„Gefunden, aber zu alt"* und die Stellen, an denen
gesucht wurde. Bitte diese Liste vollständig melden — sie enthält bereits die Antwort.

**Das sehen Sie, wenn nur Studio fehlt** ✅
`Kein INGenious gefunden` — das ist an dieser Stelle **richtig und kein Fehler**. Studio bauen
Sie in Schritt 2.

### Schritt 2 — Studio bauen

Es gibt keine fertige Datei zum Herunterladen: die Fassung mit den Panel-Schnittstellen gibt
es nur als Quelltext. Der Quelltext ist öffentlich; Sie brauchen keine Anmeldung.

Rufen Sie im Ordner `tools` auf:

```
powershell -NoProfile -ExecutionPolicy Bypass -File setup-ingenious-laptop.ps1 ^
  -SrcDir      "%USERPROFILE%\development\INGenious-src" ^
  -InstallRoot "%USERPROFILE%\ingenious" ^
  -JavaHome    "<der Java-Pfad aus Schritt 1>" ^
  -Mvn         "<Pfad zu mvn.cmd>"
```

Das dauert beim ersten Mal einige Minuten, weil einmalig alle Bibliotheken geladen werden.

**Das sehen Sie, wenn es geklappt hat** ✅
Zeilen mit Uhrzeit, zuletzt `OK -> ...`. Im entstandenen Ordner liegt eine Datei
`INSTALL-VERSION.txt` — **das ist der einzige verlässliche Hinweis darauf, welcher Stand das
ist.** Der Ordnername und die Versionsnummer im Programm sagen beide `3.0.0`, obwohl es der
3.1er Entwicklungsstand ist. Nicht nach der Zahl urteilen.

**Das sehen Sie, wenn es nicht geklappt hat** ✅
`FAILED: ...` mit dem Grund in derselben Zeile. Häufigster Fall: Maven wurde nicht gefunden.

### Schritt 3 — Alles zusammensetzen

Ein Befehl, im Ordner `tools`:

```
ing-update.cmd
```

Er holt den aktuellen Stand der Werkzeuge, baut das Plugin, setzt es in die Studio-Installation
und sagt zum Schluss, worauf alle drei Teile jetzt stehen.

**Das sehen Sie, wenn es geklappt hat** ✅

```
== Kontrolle - sind die Werkzeuge wirklich da?
     vorhanden : tools\selector-uniqueness.mjs
     ... (fünf Zeilen)
   Alle 5 Werkzeuge sind vorhanden.

================================================================
 Stand nach diesem Lauf
================================================================
  Werkzeuge : a1b2c3d4  (<zweig>)
  Studio    : C:\...\ingenious-playwright-...
              e2aa28a4
  Plugin    : a1b2c3d4...
  Java      : C:\...\jdk-17

Alles aktuell. Bitte Studio einmal neu starten.
```

**Die letzten sechs Zeilen sind der Punkt.** Sie sagen nicht „fertig", sondern **worauf Sie
stehen** — und die Zeile `Plugin` und die Zeile `Werkzeuge` müssen dieselbe Zahl zeigen. Tun
sie das nicht, sagt Studio es Ihnen später von selbst (Kapitel 5).

### Schritt 4 — Die beiden Dateien, die wir Ihnen geben

Zwei Dinge kann kein Befehl herstellen, weil sie **echte Daten** enthalten:

- die **Testdaten-Datei** mit den Testkunden,
- die **Testfall-Datei** mit den Testfällen aus Azure DevOps.

Die erste bekommen Sie von uns auf einem internen Weg. Die zweite können Sie sich mit Ihrer
eigenen Anmeldung selbst erzeugen — in Studio über die Schaltfläche **„Aus ADO
aktualisieren"** im Panel. Das dauert etwa zwölf Minuten und muss nur einmal gemacht werden. ❓

Beide Dateien werden Studio über eine Startdatei bekannt gemacht, die wir Ihnen mitgeben. Sie
liegt neben Ihrer Studio-Installation und ist das, was Sie künftig doppelklicken.

### Schritt 5 — Der Beweis

Nicht die Versionsnummer, sondern ein echter Lauf.

1. Studio über die Startdatei starten.
2. Ein Projekt öffnen.
3. Rechts in der Werkzeugleiste die Schaltfläche **`Ablauf`** anklicken.

**Das sehen Sie, wenn es geklappt hat** ✅
Ein Fenster mit drei Schritten oben — `1. Testfall wählen`, `2. Kunde wählen`,
`3. Aufnahme starten` — und **kein gelber Hinweisstreifen** darüber. Kein Streifen heißt:
alle drei Teile passen zusammen.

**Das sehen Sie, wenn ein Teil veraltet ist** ✅
Ein gelber Streifen ganz oben. Was er bedeutet, steht in Kapitel 5.

---

## 3. Was wir Ihnen abgenommen haben

Damit Sie es nicht als Fehler melden, wenn Sie es woanders lesen:

- **Java gerade rücken müssen Sie nicht.** Die Startdatei sucht selbst ein Java 17 und
  verwendet es nur für dieses eine Programm. Am Gerät wird nichts verändert. Ältere
  Anleitungen sagen, man solle `PATH` umstellen — das ist überholt.
- **Studio nicht über `ingenious.bat` starten.** Diese Datei nimmt das voreingestellte Java
  1.8, und dann blinkt das schwarze Fenster nur kurz auf.

---

## 4. Aktuell bleiben — ein Befehl

```
ing-update.cmd
```

Derselbe Befehl wie beim Einrichten. Er kümmert sich um **alle drei** Teile:

1. holt den neuen Stand der Werkzeuge und **nennt namentlich, welche sich geändert haben**,
2. baut Studio neu, **wenn** ein anderer Stand verlangt wird als installiert ist,
3. baut das Plugin und setzt es ein,
4. prüft zum Schluss nach, ob die fünf Werkzeuge wirklich auf der Platte liegen,
5. und schreibt hin, worauf alles jetzt steht.

**Nur nachsehen, ohne etwas zu verändern:**

```
ing-update.cmd -Check
```

**Der Befehl ist absichtlich laut.** Er endet mit einer roten Liste und einem Fehlercode, wenn
etwas nicht geklappt hat, und mit einer gelben Liste, wenn nichts fehlgeschlagen ist, aber
auch nicht alles aktuell ist. Er beschönigt nichts:

- eigene, ungesicherte Änderungen im Arbeitsverzeichnis → er holt **nichts** und sagt es. Er
  legt Ihre Arbeit nicht beiseite; ein „Beiseitegelegtes" findet man später nicht wieder;
- kein Maven gefunden → er sagt, dass gebaut werden müsste und es nicht kann;
- ein Werkzeug fehlt auch **nach** dem Holen → das steht mit Namen da.

Nach jedem Lauf **Studio einmal neu starten**. Ein laufendes Studio hat die alte Datei
geöffnet.

---

## 5. Studio sagt selbst, wenn etwas veraltet ist

Das ist der Teil, der einen zweiten 28. Juli verhindern soll — denn eine Anleitung, die
niemand noch einmal liest, hilft dann nicht.

Oben im Fenster `Ablauf` kann ein **gelber Streifen** stehen. Es gibt drei Sätze:

| Was da steht | Was es heißt | Was Sie tun |
|---|---|---|
| **„Nicht auf dem neuesten Stand … Bis dahin fehlt: …"** | Ein Werkzeug, das eine Schaltfläche braucht, liegt nicht auf dem Gerät | `ing-update.cmd`, dann Studio neu starten |
| **„Nicht auf dem neuesten Stand … Die Werkzeuge auf diesem Rechner sind älter als dieses Plugin."** | Das Plugin wurde aus einem Stand gebaut, den Ihr Gerät nicht hat | `ing-update.cmd`, dann Studio neu starten |
| **„Nicht eingerichtet …"** | Studio findet die Werkzeuge gar nicht | Melden. Vermutlich zeigt die Startdatei auf den falschen Ordner |

Am Ende jeder Zeile steht, was fehlt; **wenn Sie mit der Maus darauf zeigen, erscheint die
vollständige Fassung** mit den Dateinamen — die brauchen wir, wenn Sie es melden.

> **Kein Streifen ist eine Aussage.** Der Streifen erscheint **nur** bei etwas, das das
> Programm sicher weiß: eine Datei ist nicht da, oder Ihr Arbeitsstand enthält den Stand
> nicht, aus dem das Plugin gebaut wurde. Es wird **nichts** aus Datumsangaben geraten. Wo das
> Programm es nicht sicher sagen kann, sagt es **gar nichts** — lieber einmal zu wenig warnen
> als einen Hinweis, den man nach einer Woche wegklickt.

---

## 6. Was wir über Ihr Gerät noch nicht wissen

Ehrlich aufgeschrieben, weil eine selbstbewusste Anleitung, die auf Ihrem Gerät scheitert,
schlechter ist als eine Liste offener Fragen. **Diese sieben Punkte bitte vor dem
Einrichtungstermin klären** — die meisten in wenigen Minuten.

1. **Kommen Sie an das interne Git-Repository mit den Werkzeugen?** Die offene Frage mit den
   größten Folgen. Wenn ja, ist Kapitel 4 genau das, was der Auftraggeber wollte: ein Befehl,
   den Sie selbst ausführen. Wenn nein, müssen wir Ihnen die Werkzeuge als Ordner geben — und
   dann gibt es kein Selbst-Aktualisieren mehr, sondern nur „jemand schickt es neu".
   *Das ist die Frage, die zuerst beantwortet gehört.*
2. **Liegt auf Ihrem Gerät ein Java 17, und wo?** Kapitel 2, Schritt 1 beantwortet das. Der
   Pfad des uns bekannten Geräts gilt ausdrücklich nicht für Ihres.
3. **Ist git da?** Ohne git gibt es weder Kapitel 2 noch Kapitel 4.
4. **Ist Node da?** Ohne Node erscheinen die Schaltflächen, arbeiten aber nicht — Studio sagt
   das dann auch.
5. **Wie kommen Sie an Maven?** Auf dem uns bekannten Gerät ist es ein entpackter Ordner im
   Benutzerverzeichnis. Ob das Company Portal es anbietet, wissen wir nicht.
6. **Funktioniert „Aus ADO aktualisieren" mit Ihrer Anmeldung?** Der Weg ist an ING erprobt,
   aber bisher **nur von einer Kommandozeile aus und nur mit einem Konto**. Aus Studio heraus
   hat ihn noch niemand ausgeführt.
7. **Verhält sich Ihr Gerät bei der Absicherung anders?** Vom Regelwerk her sollten Java,
   Maven und Node im eigenen Benutzerverzeichnis erlaubt sein. Geprüft ist das auf **einem**
   Gerät.

Bitte melden Sie jede Abweichung wörtlich — ein Bildschirmfoto reicht. Jeder Stolperstein bei
Ihnen ist ein Absatz, den die nächste Kollegin nicht mehr erlebt.

---

## 7. Wenn es klemmt

| Was Sie sehen | Woran es liegt | Was hilft |
|---|---|---|
| Schwarzes Fenster blitzt auf und schließt sich | Studio wurde über `ingenious.bat` gestartet, also mit Java 1.8 | Über die Startdatei starten (Kapitel 2, Schritt 1) |
| `UnsupportedClassVersionError` | dasselbe | dasselbe |
| Der Bau bricht bei `prettier` ab | bekannter Windows-Fehler | Das Bau-Skript umgeht das bereits. Bitte melden, wenn es trotzdem auftritt |
| Der Bau findet eine Bibliothek nicht | die erste Bau-Stufe fehlt | Das Bau-Skript macht beide Stufen. Von Hand: erst `ingenious-api`, dann den Rest |
| Ordnername und Version sagen `3.0.0` | so ist es gewollt; die Nummer wurde nie hochgesetzt | `INSTALL-VERSION.txt` lesen |
| `ing-update` sagt, es könne nichts holen | Ihr Zweig folgt keinem Zweig auf dem Server | Melden — das ist ein Einrichtungsfehler, keiner Ihrer |
| Eine Schaltfläche ist grau | ein Werkzeug fehlt | Der gelbe Streifen oben sagt, welches. Kapitel 5 |
| Die Schaltfläche `Ablauf` fehlt ganz | das Plugin liegt nicht in der Installation | `ing-update.cmd`, dann Studio neu starten |

Bekannte Fehler im Programm selbst, die nicht an Ihrem Gerät liegen, stehen in
[INGENIOUS-UPSTREAM-ISSUES.md](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/reference/INGENIOUS-UPSTREAM-ISSUES.md).

---

## Was diese Seite ersetzt

Diese fünf Seiten beschreiben Teile desselben Weges. Sie bleiben als Nachschlagewerk bestehen
— die **Reihenfolge** steht nur hier. *(Ein Verweis von dort hierher steht noch aus; bis
dahin ist diese Seite die maßgebliche.)*

| Seite | Was dort noch steht |
|---|---|
| [SETUP-INGENIOUS-31-LOCAL.md](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/ops/SETUP-INGENIOUS-31-LOCAL.md) | die Bau-Wege im Detail, und was auf Linux/macOS anders ist |
| [INGENIOUS-31-LAPTOP-INSTALL.md](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/ops/INGENIOUS-31-LAPTOP-INSTALL.md) | wie eine neue Installation die Einstellungen der alten übernimmt |
| [INGENIOUS-DEVELOPMENT-BUILD.md](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/ops/INGENIOUS-DEVELOPMENT-BUILD.md) | der Bau auf einem Entwicklungsrechner |
| [TEAM-INSTALL-WINDOWS-MAC-DE.md](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/ops/TEAM-INSTALL-WINDOWS-MAC-DE.md) | der macOS-Weg (von uns nicht durchgeführt) |
| [LAPTOP-CHECK-PANELS.md](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/ops/LAPTOP-CHECK-PANELS.md) | die Klick-für-Klick-Abnahme der Panels |

[SETUP-Arbeitslaptop.md](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/ops/SETUP-Arbeitslaptop.md)
gehört zum älteren Recorder-Werkzeug und nicht zu INGenious.

Für Kolleginnen und Kollegen aus dem **Fachbereich**, die nur aufnehmen und nichts einrichten:
[FACHBEREICH-INGENIOUS-HANDOUT-DE.md](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/ops/FACHBEREICH-INGENIOUS-HANDOUT-DE.md).
