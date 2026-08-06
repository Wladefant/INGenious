# tools

Node- und PowerShell-Werkzeuge rund um INGenious: Aufnahmen prüfen, Objekte und
Selektoren katalogisieren, Läufe auswerten, Ergebnisse und Nachweise nach Azure
DevOps melden, ein Tester-Paket aktuell halten.

Sie ergänzen INGenious, sie ersetzen nichts daran. Das Java-Produkt in diesem
Repository bleibt unberührt.

## Voraussetzungen

| | |
|---|---|
| Node | 18+, **keine Abhängigkeiten** — ausschließlich Node-Standardbibliothek, kein `package.json`, kein `node_modules` |
| PowerShell | 5.1 für `ingenious-launch.ps1` und `ing-update.ps1` |
| Playwright | optional; nur `selector-uniqueness.mjs` und `session-anmelden.mjs` brauchen einen Browser und melden sonst ehrlich `CANNOT TELL` |

Die Abhängigkeitsfreiheit ist Absicht, keine Sparsamkeit: diese Werkzeuge laufen
auf gesperrten Firmengeräten, auf denen nichts installiert werden darf.

## Konfiguration

Kein Werkzeug kennt eine Organisation, eine Adresse oder einen Testplan. Alles
Umgebungsspezifische kommt von außen — Umgebungsvariable, `$ING_CONFIG`,
`ing-config.json` neben den Werkzeugen oder `%LOCALAPPDATA%\IngQaAutopilot`.

`ing-config.example.json` ist die Vorlage. Ein fehlender Wert wird **nicht
geraten**: das Werkzeug hält bei der ersten Benutzung an und nennt Schlüssel und
Datei. Ein Werkzeug, das eine Organisation errät, schreibt in fremde Vorgänge.

## Selbsttests

Jedes Werkzeug prüft sich selbst, offline, ohne Netz und ohne Azure DevOps:

```
node <werkzeug>.mjs --selftest
```

Die Fixturen unter `fixtures/` sind erfunden und modelliert, nicht kopiert. Sie
sind der Grund, warum die Behauptungen dieser Werkzeuge nachprüfbar sind statt
geglaubt werden zu müssen — `selector-uniqueness.mjs` etwa behauptet, dass
INGenious bei einem mehrdeutigen CSS-Selektor **innerhalb eines Frames** still
`.first()` nimmt und ein Test dadurch grün wird, obwohl das falsche Bedienelement
angeklickt wurde. `fixtures/ambiguous-selectors/` reproduziert das in 4 KB.

## Stand

Erstveröffentlichung. Die Werkzeuge laufen produktiv, die hier abgelegte Fassung
ist von organisationsspezifischen Bezeichnern befreit worden. Zwei Dinge sind
noch offen und stehen hier, statt entdeckt zu werden:

- **Dokumentation.** Die ausführlichen `README-<werkzeug>.md` sind noch nicht
  mitveröffentlicht.
