# INGenious mit den ING-Panels einrichten und aktuell halten

**Für wen:** eine Kollegin oder ein Kollege aus der Testautomatisierung, die/der das Ganze auf
dem **eigenen Windows-Gerät** einrichtet. Technischer Hintergrund vorausgesetzt,
Administratorrechte **nicht**.

**Das ist die einzige Anleitung, die Sie brauchen.** Fünf ältere Seiten beschreiben denselben
Weg in Teilen; sie stehen unten unter [Was diese Seite ersetzt](#was-diese-seite-ersetzt).

> ## Die wichtigste Regel
> Nach jedem Schritt steht, **was Sie sehen müssen**, wenn es geklappt hat. Sehen Sie etwas
> anderes: aufhören und melden. Nicht raten. Genau die Stellen, an denen Sie ins Grübeln
> kommen, sind die Fehler dieser Anleitung — und die wollen wir hören.

**Zeichen:** ✅ = am 28.07.2026 auf einem Windows-Gerät aus einem **frischen Klon dieses
Repositories** wirklich so gesehen · ❓ = noch **nicht** auf einem zweiten Gerät gesehen; wenn
es bei Ihnen anders aussieht, liegt das nicht an Ihnen.

**Dauer:** einmalig 30–60 Minuten, davon die Hälfte Wartezeit beim Bauen.

---

## Was jetzt schon geht, und was noch offen ist

**Alles, was diese Anleitung verlangt, liegt in *einem* öffentlichen Repository**, das ohne
Anmeldung erreichbar ist — Studio, die Werkzeuge, das Plugin und diese Seite. Das war bis zum
28.07.2026 nicht so und ist der Grund, warum ältere Fassungen dieser Seite hier eine Warnung
trugen.

Was **auf unserem Prüfgerät nicht geprüft werden konnte** und deshalb ❓ bleibt:

| Was | Warum nicht geprüft |
|---|---|
| Anmeldung an der Testanwendung (SSO) und eine **echte Aufnahme im Browser** | braucht eine interaktive Anmeldung an der internen Anwendung. Bis genau dorthin ist die Kette bewiesen: Testfall wählen, Kunde wählen, Kontonummer in die Zwischenablage, Profil am Testfall, Aufnahme eingerichtet, Browser geöffnet |
| **Hochladen nach Azure DevOps** (`az login`, echter Testlauf) | es wurde bewusst kein Azure-DevOps-Lauf erzeugt. Der Upload-Weg selbst wurde ausgeführt und hat geantwortet — mit „übersprungen", weil der Beispiel-Testfall keine ADO-Nummer trägt |
| Verhalten auf einem **abgesicherten Firmengerät** | geprüft wurde auf einem Windows-Gerät ohne die Absicherung Ihres Arbeitsplatzes |

---

## 0. Was am Ende dasteht

Ein Startsymbol, ein Doppelklick, und Studio geht auf — mit der Schaltfläche **`Ablauf`**
rechts in der Werkzeugleiste, die durch Testfall, Testkunde und Aufnahme führt.

Dahinter stecken **drei Teile, die getrennt voneinander veralten können**, und genau daran ist
diese Anleitung entlanggebaut:

| Teil | Was es ist |
|---|---|
| **Studio** | INGenious selbst, aus dem Quelltext dieses Klons gebaut |
| **Die Werkzeuge** | eine Sammlung kleiner Programme unter `tools/`, die die Panels im Hintergrund aufrufen |
| **Das Plugin** | die ING-Schaltflächen in Studio, eine einzelne Datei in der Installation |

Wenn eines davon älter ist als die anderen, sieht man das dem Programm nicht an — bis eine
Schaltfläche grau ist und niemand sagt, warum. **Genau das ist am 28. Juli passiert.** Deshalb
gibt es Kapitel 4 (ein Befehl, der alle drei aktualisiert) und Kapitel 5 (Studio sagt es Ihnen
von selbst).

> **Wichtig:** Studio muss **aus diesem Klon** gebaut werden. Nur diese Fassung hat die
> Schnittstelle, über die ein Plugin überhaupt eine Schaltfläche beisteuern kann. Ein Studio
> aus einer anderen Quelle startet zwar, zeigt `Ablauf` aber nie — und sagt Ihnen auch nicht,
> warum. Das Bau-Skript in Kapitel 2 baut deshalb von sich aus genau diesen Klon.

---

## 1. Was Sie auf dem Gerät brauchen — und woher

**Diese Tabelle ist der wichtigste Teil der Seite.** Sie sagt auch, was wir **nicht** wissen.
Bitte bei jedem ❓ kurz nachsehen und uns das Ergebnis geben — das ist in fünf Minuten erledigt
und erspart uns beiden eine Stunde am Einrichtungstermin.

| Was | Wofür | Auf einem Standard-Firmengerät |
|---|---|---|
| **Java 17** | Studio läuft darauf | ❓ Auf dem uns bekannten Gerät liegt es unter „Program Files / Java". **Achtung:** das voreingestellte Java ist **1.8** und zu alt. Wo Ihres liegt, sagt Ihnen Kapitel 2, Schritt 1 |
| **Maven 3.9** | baut Studio und das Plugin | ❓ Voraussichtlich **nicht** vorhanden. Kein Installationsprogramm nötig — ein entpackter Ordner im eigenen Benutzerverzeichnis reicht. Bitte bei uns anfordern |
| **git** | holt den Quelltext und die Aktualisierungen | ❓ Auf dem uns bekannten Gerät ist es benutzerweit installiert, ohne Administratorrechte. Ob es auf Ihrem liegt: bitte prüfen |
| **Node 20 oder neuer** | die Werkzeuge sind Node-Programme | ❓ Ohne Node laufen „Aufnahme prüfen" und „Aufnahme abgeben" nicht. Ob es auf Ihrem Gerät liegt: bitte prüfen |
| **Quelltext (dieser Klon)** | daraus wird alles gebaut | Öffentlich, ohne Anmeldung erreichbar. Kein Zugangsproblem |
| **Ihre Azure-DevOps-Angaben** | Organisation, Projekt, Testplan, Mandant | Legen Sie selbst an — Kapitel 2, Schritt 4. Sie stehen **in keinem Repository** und dürfen auch in keines |
| **Testdaten-Datei** | die Kundenliste im Panel „Testdaten" | Bekommen Sie von uns auf einem internen Weg. **Sie liegt in keinem Repository und darf auch in keines** |

**Nichts davon braucht Administratorrechte, und es wird keine `.exe` verteilt.** Alles ist
entweder schon da, kommt aus dem Company Portal oder ist ein entpackter Ordner in Ihrem
eigenen Benutzerverzeichnis.

---

## 2. Einrichten

### Schritt 0 — Den Quelltext holen

An eine Stelle in Ihrem Benutzerverzeichnis, mit einem **kurzen** Pfad:

```
cd %USERPROFILE%
git clone --branch feat/studio-panel-plugins https://github.com/Wladefant/INGenious.git
```

**Das sehen Sie, wenn es geklappt hat** ✅
`Cloning into 'INGenious'...`, danach eine Eingabeaufforderung ohne Fehlermeldung. Im Ordner
`INGenious` liegen unter anderem `tools\`, `ingenious-plugin\` und `ingenious-api\`.

**Wenn `Filename too long` kommt** ✅ — einmalig, ohne Administratorrechte:

```
git config --global core.longpaths true
```

und den Klon wiederholen. Ursache ist Windows' Pfadlängengrenze, nicht Ihr Gerät; ein kurzer
Zielpfad (`%USERPROFILE%\INGenious`) genügt in aller Regel schon.

### Schritt 1 — Kann dieses Gerät INGenious überhaupt starten?

Diese Frage zuerst, weil sie in einer Minute beantwortet ist und weil eine falsche Antwort
alles Weitere sinnlos macht. Im Ordner `tools`:

```
cd %USERPROFILE%\INGenious\tools
.\ingenious-launch.cmd -Check
```

> Das `.\` davor ist kein Tippfehler. Auf manchen abgesicherten Geräten sucht die
> Eingabeaufforderung Programme **nicht** im aktuellen Ordner; ohne `.\` kommt dann
> `ist nicht als interner oder externer Befehl erkannt`, obwohl die Datei danebenliegt ✅.
> Per Doppelklick im Explorer stellt sich die Frage nicht.

**Das sehen Sie, wenn es geklappt hat** ✅

```
Java      : 17.0.12   (C:\Program Files\Java\jdk-17)
INGenious : C:\Users\<Sie>\ingenious\ingenious-playwright-...

OK - dieses Gerat kann INGenious starten. / This machine can start INGenious.
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
es nur als Quelltext. Im Ordner `tools`:

```
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup-ingenious-laptop.ps1
```

**Ohne weitere Angaben.** Das Skript baut **diesen Klon** — nicht irgendeine andere Quelle —,
sucht sich das Java 17 mit demselben Finder wie Schritt 1 und Maven auf `PATH`, in
`MAVEN_HOME` und in den üblichen entpackten Ordnern unter Ihrem Benutzerverzeichnis. Es holt
nichts und checkt nichts aus, kann also an Ihrem Arbeitsstand nichts verändern. Nur wenn eines
davon **nicht** gefunden wird, geben Sie es an: `-JavaHome "<Pfad>"` bzw. `-Mvn "<Pfad>"`.

Das dauert beim ersten Mal einige Minuten, weil einmalig alle Bibliotheken geladen werden
(mit gefülltem Maven-Zwischenspeicher danach etwa zwei Minuten ✅).

**Das sehen Sie, wenn es geklappt hat** ✅

```
[12:45:20] built ingenious-playwright-3.0.0-setup.zip (435 MB)
[12:45:20] extracting to staging
[12:45:27] OK -> C:\Users\<Sie>\ingenious\ingenious-playwright-3.1.0dev-<8 Zeichen>
```

Im entstandenen Ordner liegt eine Datei `INSTALL-VERSION.txt` — **das ist der einzige
verlässliche Hinweis darauf, welcher Stand das ist.** Der Ordnername und die Versionsnummer im
Programm sagen beide `3.0.0`, obwohl es der 3.1er Entwicklungsstand ist. Nicht nach der Zahl
urteilen.

**Das sehen Sie, wenn es nicht geklappt hat** ✅
`FAILED: ...` mit dem Grund in derselben Zeile. Häufigster Fall: Maven wurde nicht gefunden.

### Schritt 3 — Die Werkzeuge einsatzbereit machen

Ein einziges Werkzeug braucht ein Paket von außen (`playwright`, für „Aufnahme prüfen"). Alle
anderen kommen mit Node allein aus. Im Ordner `tools`:

```
npm install
```

**Das sehen Sie, wenn es geklappt hat** ❓
`added N packages` und ein neuer Ordner `tools\node_modules`. Der erste Lauf lädt zusätzlich
einen Browser herunter und dauert einige Minuten.

**Wenn Sie das überspringen:** alles andere funktioniert; nur „Aufnahme prüfen" antwortet dann
mit *„CANNOT TELL — the `playwright` package is not resolvable"* und nennt genau diesen Befehl.
Nichts wird still falsch.

### Schritt 4 — Sagen, welches Azure DevOps gemeint ist

Organisation, Projekt, Testplan und Mandant stehen **nicht im Quelltext** — dieses Repository
ist öffentlich. Sie legen sie einmalig lokal an, außerhalb des Repositories. In PowerShell:

```powershell
$dir = Join-Path $env:LOCALAPPDATA 'IngQaAutopilot'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
@{
  org      = '<Ihre ADO-Organisation>'
  project  = '<Ihr ADO-Projekt>'
  planId   = 1234567
  tenantId = '<Ihre Entra-Mandanten-GUID>'
} | ConvertTo-Json | Set-Content (Join-Path $dir 'ado-config.json') -Encoding UTF8
```

Wo Sie die vier Angaben finden, steht in
[`tools/README-ado-config.md`](../../tools/README-ado-config.md).

**Das sehen Sie, wenn etwas fehlt** ✅ — überall derselbe Satz, mit dem Namen der fehlenden
Einstellung:

```
Nicht eingerichtet: org fehlt. Bitte C:\Users\<Sie>\AppData\Local\IngQaAutopilot\ado-config.json
anlegen (Schluessel "org") oder die Umgebungsvariable ADO_ORG setzen.
```

Es gibt bewusst **keine Voreinstellung**: eine falsche Organisation scheitert an Azure DevOps
mit einem 404, der wie ein kaputter Testfall aussieht statt wie eine fehlende Einstellung.

**Ohne diesen Schritt** starten Studio und alle Panels trotzdem; nur die Azure-DevOps-Funktionen
(„Aus ADO aktualisieren", das Hochladen) sagen, dass sie nicht eingerichtet sind.

### Schritt 5 — Alles zusammensetzen

Ein Befehl, im Ordner `tools`:

```
.\ing-update.cmd
```

Er holt den aktuellen Stand der Werkzeuge, baut das Plugin, setzt es in die Studio-Installation
und sagt zum Schluss, worauf alle drei Teile jetzt stehen.

**Das sehen Sie, wenn es geklappt hat** ✅

```
== 3 von 3 - Panel-Plugin (die Knoepfe in Studio)
   baue Plugin...
   Plugin eingebaut: C:\Users\<Sie>\ingenious\ingenious-playwright-...\plugins\ing-tester-panel\ing-tester-panel-0.1.0.jar

== Kontrolle - sind die Werkzeuge wirklich da?
     vorhanden : tools\selector-uniqueness.mjs
     vorhanden : tools\handoff-pack.mjs
     vorhanden : tools\ado-testcases.mjs
     vorhanden : tools\parse-report.mjs
     vorhanden : ing-qa-recorder\mvp\ado-upload.mjs
   Alle 5 Werkzeuge sind vorhanden.

================================================================
 Stand nach diesem Lauf
================================================================
  Werkzeuge : a1b2c3d4  (feat/studio-panel-plugins)
  Studio    : C:\Users\<Sie>\ingenious\ingenious-playwright-3.1.0dev-a1b2c3d4
              a1b2c3d4
  Plugin    : a1b2c3d4
  Java      : C:\Program Files\Java\jdk-17

Alles aktuell. Bitte Studio einmal neu starten.
```

**Die letzten sechs Zeilen sind der Punkt.** Sie sagen nicht „fertig", sondern **worauf Sie
stehen** — und die Zeilen `Werkzeuge`, `Studio` und `Plugin` müssen dieselbe Zahl zeigen. Tun
sie das nicht, sagt Studio es Ihnen später von selbst (Kapitel 5).

> **`Plugin : dirty`** heißt: in Ihrem Klon liegen eigene Änderungen, deshalb kann das Plugin
> nicht sagen, aus welchem Stand es gebaut wurde — und Studio hält dann bewusst den Mund,
> statt zu raten. Das ist kein Fehler, nur eine Auskunft weniger.

### Schritt 6 — Die Datei, die wir Ihnen geben

Eines kann kein Befehl herstellen, weil es **echte Daten** enthält: die **Testdaten-Datei** mit
den Testkunden. Die bekommen Sie von uns auf einem internen Weg.

Die **Testfall-Liste** aus Azure DevOps erzeugen Sie sich dagegen mit Ihrer eigenen Anmeldung
selbst — in Studio über die Schaltfläche **„Aus ADO aktualisieren"** im Panel. Das dauert etwa
zwölf Minuten und muss nur einmal gemacht werden. ❓

Beide Dateien werden Studio über eine Startdatei bekannt gemacht, die wir Ihnen mitgeben. Sie
liegt neben Ihrer Studio-Installation und ist das, was Sie künftig doppelklicken.

### Schritt 7 — Der Beweis

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

**Was danach nachweislich funktioniert** ✅ — auf einem frischen Klon durchgespielt: Testfall
wählen, Kunde wählen (die Kontonummer liegt danach in der Zwischenablage), die Eigenschaften
des Testkunden werden am Testfall vermerkt, die Zusammenfassung nennt Testfall und Kunde,
„Aufnahme starten" legt den Testfall an und öffnet den Browser — **ohne eine einzige
Zwischenfrage**. Ab dort brauchen Sie Ihre Anmeldung an der Testanwendung; das ist der Teil,
den wir nicht für Sie prüfen konnten.

---

## 3. Was wir Ihnen abgenommen haben

Damit Sie es nicht als Fehler melden, wenn Sie es woanders lesen:

- **Java gerade rücken müssen Sie nicht.** Die Startdatei sucht selbst ein Java 17 und
  verwendet es nur für dieses eine Programm. Am Gerät wird nichts verändert. Ältere
  Anleitungen sagen, man solle `PATH` umstellen — das ist überholt.
- **Studio nicht über `ingenious.bat` starten.** Diese Datei nimmt das voreingestellte Java
  1.8, und dann blinkt das schwarze Fenster nur kurz auf.
- **Den Quelltext nicht zweimal holen.** Das Bau-Skript baut den Klon, in dem es liegt.

---

## 4. Aktuell bleiben — ein Befehl

```
.\ing-update.cmd
```

Derselbe Befehl wie beim Einrichten. Er kümmert sich um **alle drei** Teile:

1. holt den neuen Stand der Werkzeuge und **nennt namentlich, welche sich geändert haben**,
2. baut Studio neu, **wenn** ein anderer Stand verlangt wird als installiert ist,
3. baut das Plugin und setzt es ein,
4. prüft zum Schluss nach, ob die fünf Werkzeuge wirklich auf der Platte liegen,
5. und schreibt hin, worauf alles jetzt steht.

**Nur nachsehen, ohne etwas zu verändern:**

```
.\ing-update.cmd -Check
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
schlechter ist als eine Liste offener Fragen. **Diese Punkte bitte vor dem Einrichtungstermin
klären** — die meisten in wenigen Minuten.

1. **Liegt auf Ihrem Gerät ein Java 17, und wo?** Kapitel 2, Schritt 1 beantwortet das. Der
   Pfad des uns bekannten Geräts gilt ausdrücklich nicht für Ihres.
2. **Ist git da?** Ohne git gibt es weder Kapitel 2 noch Kapitel 4.
3. **Ist Node da, und kommt `npm install` durch?** Ohne Node erscheinen die Schaltflächen,
   arbeiten aber nicht — Studio sagt das dann auch. Ob `npm install` hinter dem Firmen-Proxy
   funktioniert, wissen wir nicht.
4. **Wie kommen Sie an Maven?** Auf dem uns bekannten Gerät ist es ein entpackter Ordner im
   Benutzerverzeichnis. Ob das Company Portal es anbietet, wissen wir nicht.
5. **Funktioniert „Aus ADO aktualisieren" mit Ihrer Anmeldung?** Der Weg ist erprobt, aber
   bisher **nur von einer Kommandozeile aus und nur mit einem Konto**. Aus Studio heraus hat
   ihn noch niemand vollständig ausgeführt.
6. **Verhält sich Ihr Gerät bei der Absicherung anders?** Vom Regelwerk her sollten Java,
   Maven und Node im eigenen Benutzerverzeichnis erlaubt sein. Geprüft ist das auf **einem**
   Gerät, und das war kein abgesichertes.

Bitte melden Sie jede Abweichung wörtlich — ein Bildschirmfoto reicht. Jeder Stolperstein bei
Ihnen ist ein Absatz, den die nächste Kollegin nicht mehr erlebt.

---

## 7. Wenn es klemmt

| Was Sie sehen | Woran es liegt | Was hilft |
|---|---|---|
| `Filename too long` beim Klonen | Windows-Pfadlängengrenze | `git config --global core.longpaths true`, oder einen kürzeren Zielpfad wählen |
| `ingenious-launch.cmd ist nicht als interner oder externer Befehl erkannt` | die Eingabeaufforderung sucht nicht im aktuellen Ordner | `.\ingenious-launch.cmd -Check` schreiben |
| Schwarzes Fenster blitzt auf und schließt sich | Studio wurde über `ingenious.bat` gestartet, also mit Java 1.8 | Über die Startdatei starten (Kapitel 2, Schritt 1) |
| `UnsupportedClassVersionError` | dasselbe | dasselbe |
| Der Bau bricht bei `prettier` ab | bekannter Windows-Fehler | Das Bau-Skript umgeht das bereits. Bitte melden, wenn es trotzdem auftritt |
| Der Bau findet eine Bibliothek nicht | die erste Bau-Stufe fehlt | Das Bau-Skript macht beide Stufen. Von Hand: erst `ingenious-api`, dann den Rest |
| Ordnername und Version sagen `3.0.0` | so ist es gewollt; die Nummer wurde nie hochgesetzt | `INSTALL-VERSION.txt` lesen |
| `ing-update` sagt, es könne nichts holen | Ihr Zweig folgt keinem Zweig auf dem Server | Melden — das ist ein Einrichtungsfehler, keiner Ihrer |
| `ing-update` sagt „eigene Aenderungen", obwohl Sie nichts geändert haben | Sie haben tatsächlich eine Datei bearbeitet — reine Zeilenenden aus dem Bau zählen seit dem 28.07.2026 nicht mehr dazu | `git status` ansehen; die genannte Datei ist wirklich verändert |
| „CANNOT TELL — the `playwright` package is not resolvable" | Kapitel 2, Schritt 3 wurde übersprungen | `npm install` im Ordner `tools` |
| „Nicht eingerichtet: org fehlt" o. Ä. | Kapitel 2, Schritt 4 wurde übersprungen | `ado-config.json` anlegen |
| Eine Schaltfläche ist grau | ein Werkzeug fehlt | Der gelbe Streifen oben sagt, welches. Kapitel 5 |
| Die Schaltfläche `Ablauf` fehlt ganz | das Plugin liegt nicht in der Installation — **oder** Studio kam nicht aus diesem Klon | `ing-update.cmd`, dann Studio neu starten. Bleibt sie weg: Kapitel 2, Schritt 2 wiederholen |

---

## Was diese Seite ersetzt

Diese fünf Seiten beschreiben Teile desselben Weges. Sie liegen im internen Repository und
beschreiben teils den älteren Aufbau mit zwei getrennten Repositories — die **Reihenfolge**
steht nur hier:

| Seite | Was dort noch steht |
|---|---|
| SETUP-INGENIOUS-31-LOCAL.md | die Bau-Wege im Detail, und was auf Linux/macOS anders ist |
| INGENIOUS-31-LAPTOP-INSTALL.md | wie eine neue Installation die Einstellungen der alten übernimmt |
| INGENIOUS-DEVELOPMENT-BUILD.md | der Bau auf einem Entwicklungsrechner |
| TEAM-INSTALL-WINDOWS-MAC-DE.md | der macOS-Weg (von uns nicht durchgeführt) |
| LAPTOP-CHECK-PANELS.md | die Klick-für-Klick-Abnahme der Panels |

Hier im Repository liegen daneben:

- [`tools/README-ado-config.md`](../../tools/README-ado-config.md) — die vier
  Azure-DevOps-Angaben und wo Sie sie finden
- [`ingenious-plugin/README.md`](../../ingenious-plugin/README.md) — was die Panels tun und
  wie sie geprüft werden
- [`tools/README-ingenious-launch.md`](../../tools/README-ingenious-launch.md) — der Starter
  im Detail
