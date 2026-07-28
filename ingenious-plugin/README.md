# ING Tester Panel — INGenious Studio plugin

The ING-specific tester surface, living **inside** INGenious rather than beside it.

It rides on the `StudioPanelApi` extension point added to our INGenious fork
(branch `feat/studio-panel-plugins`), so nothing ING-specific has to enter the
INGenious core — and the core change is generic enough to offer upstream.

## What it does today

**Ablauf** — the whole tester job on one screen, in the order a tester actually does
it: **Testfall → Kunde → Aufnahme**. Asked for in these words:

> *"people choose the test case first and then based on the test case — so they need
> to see it somewhere — they choose the Kunde and then they start recording, that is
> it."*

Every piece below already existed as its own screen; what did not exist was the
**sequence**. A tester had to know that *Testfall wählen* comes before *Testdaten*,
that *Testfall-Übersicht* is where the requirements are, and that the Record button in
the toolbar is the end of it. This panel knows that order so nobody has to.

| Schritt | What the tester sees | What unlocks the next one |
|---|---|---|
| 1 — Testfall wählen | the full chooser: search, detail, ADO link | *Diesen Testfall übernehmen* |
| 2 — Kunde wählen | **the case's own Voraussetzungen text on the left, the customer picker on the right** | *Kontonummer kopieren* — or *Dieser Testfall braucht keinen Testkunden* |
| 3 — Aufnahme starten | a plain-German summary of both choices, the recorder's live state, and the address it will open | *Aufnahme starten* — and, once it runs, *Aufnahme beenden* in the same place |

**Ablauf is the only screen Studio offers.** The manifest lists one
`StudioPanelApi` entry class. *Testdaten*, *Testfall wählen* and *Testfall-Übersicht*
used to sit beside it in the toolbar although all three are steps of it, so the tester
handout had to spend a paragraph saying "ignore those three". They are still in the JAR
and still built; an engineer who works on one singly sets `ING_QA_PANELS=alle` and gets
all four back as tabs.

- It **reuses, it does not re-implement**: step 1 embeds the real `TestCaseChooserPanel`
  and step 2 the real `TestDataPanel`, each reporting back through one listener. Same
  search, same ADO link, same blocklist, same loud confirmations.
- **Where you are is never in doubt**: three step markers (blue = here, green ✔ = done,
  grey = locked), a "Schritt 2 von 3" headline, an instruction line, and a coloured
  banner — every state change moves more than one of them.
- **A locked step says why it is locked**, on screen, not only in a tooltip. Clicking one
  never silently does nothing.
- It does **not** narrow the customer list from the case's `Voraussetzungen`. That prose
  lives in `System.Description` on 4,377 of 6,609 cases and extracting it is unsolved
  ([#100](https://github.com/Wladefant/ing-qa-automation/issues/100)). Step 2 *shows* the
  text so the tester filters knowingly; guessing would hide the wrong customers silently.
- Step 3 reaches Studio's own recorder reflectively (`StudioRecorder`) — there is no core
  API for starting a recording and none was added. When that fails for any reason, the
  banner says so in German and points at the toolbar button, because the test case is
  already set either way.
- **The button never claims a start it did not make.** `TestCaseComponent.record()` is a
  toggle whose first branch *stops* a running recording, and this panel used to call it
  blind and print "✔ Die Aufnahme wurde gestartet" either way — so a second press ended
  the recording under a success banner. `StudioRecorder` now reads Studio's state first,
  refuses the call that would do the opposite of what was asked, and re-reads the state
  afterwards; "gestartet" is only ever printed when the state really moved.
- **Started here, ended here.** While a recording runs the same button reads *Aufnahme
  beenden* and a red line beside it says the recording is live. The stop no longer lives
  on another screen. The state is re-read once a second, so a recording ended from Test
  Design or by closing the browser puts the button back to *Aufnahme starten* by itself.
- **The recorder's start address is named before the tester presses anything — and can be
  set there.** The line above the button says which address the browser will open and where
  it came from, because an address supplied for the chosen test case beats the project's and
  a tester who was not told that would change the project setting, see no difference and
  report the wrong thing. When the project has none — still the default on a fresh install —
  the tester types it into *Start-Adresse* and presses *Adresse übernehmen*. It goes through
  the product's own `RecorderSettings.setStartUrl`, is saved with `RecorderSettings.save()`
  because `ProjectSettings.save()` does not cover the recorder settings, and is reported as
  stored only after the value has been read back out of the properties file — an in-memory
  setter that worked and a file that was never written look identical from the object and
  differ by one Studio restart.

  *Correction (2026-07-28): this bullet used to end "Core needs a field in Settings for it;
  the warning does not have to wait for that." The warning is no longer the whole answer.
  `setStartUrl` still has no caller anywhere in the INGenious core — that is pinned by
  `StudioContractHarness` and is why any of this exists — but "hand-edited or nothing"
  stopped being true of the tester's side.*

**Testdaten** — a tester describes the customer they need and gets one back, without
opening the spreadsheet. The requirement, verbatim from the test-automation lead:

> *"wo man dann einfach nur noch eingeben kann, ich brauche Einzelkunden, der Boni 12
> hat, spuck den mal aus"*

- Filters are **derived from the CSV header**, not hardcoded — the real workbook has
  dozens of columns that change per export.
- Values are shown in **plain German**, never as codes: *"nur weil jemand eine Boni 21
  hat, heißt es nicht, dass jeder weiß, dass das unbekannt verzogen bedeutet"*. This
  holds **in the table too**, not only in the dropdowns — the code stays in brackets
  behind the meaning (`unbekannt verzogen (21)`) so a row can still be quoted to the
  test-data owner. The Kontonummer is never translated: it is the value that leaves the
  panel.
- **Off-shape rows are withheld, and counted out loud.** A row whose cells no longer line
  up with the header shows every property against the wrong column — a lie the tester
  cannot detect. Rows must have the header's cell count and, where the file has one, a
  Kontonummer that looks like an account number; the rest are not offered, and the status
  line says how many. **Against a correctly converted export that count is zero**
  (`BON_KUNDE` → 50,015 of 50,015 uniform rows, measured 2026-07-28), so a non-zero count
  means the CSV came from an older converter — which is what the warning says. The
  guardrail stays regardless: it costs nothing and it is the difference between a wrong
  customer and no customer.

### Plain German — the shipped meanings

> *"nur weil jemand eine Boni 21 hat, heißt es nicht, dass jeder weiß, dass das unbekannt
> verzogen bedeutet"*

The mechanism for that existed before; the **map** did not, so the table still rendered
`P`, `12`, `INTB`, `N`. It now ships inside the plugin at
`src/main/resources/de/ing/qa/panel/testdaten-labels.properties`:

- **Loaded by default.** No `ING_TESTDATA_LABELS`, no configuration, still German. When
  the variable *is* set, that file is overlaid on top rather than replacing the shipped
  one, so a site can correct one code without restating the rest.
- **Column captions too** (`_spalte.<Spalte>=…`) — `Kbo5 Bonitaet S` above the values is
  the same jargon one row higher up. 15 of the 17 real columns now have a caption.
- **Only what is evidenced gets a label**, and each entry carries its source: René's own
  words for `Boni 21`; our documentation for `Partnertyp P`/`G`; a measurement over 71,759
  rows for `Verf Bez` (INTB rows carry an Internetbanking status and PBTN rows a Postbox
  status, without one exception); the J/N convention for the yes/no flags.
- **Everything unconfirmed stays a raw code**, and stands in the file as a commented-out
  line with its record count, ready for a human to fill in: 31 Bonität codes, 9
  Legitimationsstatus codes, 7 EZB codes, and `Partnertyp J`. A wrong German label is worse
  than a raw code — the raw code gets questioned, the label gets believed.
- One measured correction worth keeping: **`Partnertyp J` is not "minderjährig"** — all 13
  J rows carry `MDJ = N`. The sample file had been claiming otherwise.

Coverage over the real `BON_KUNDE` sheet: **75.8% of coded cells** (298,467 of 393,995)
now show a meaning. The remainder is concentrated in three columns nobody has answered
yet — `EZB` (0%), `Kbo5 Bonitaet S` (13%, only code 21), `Legi Status Kz` (0%).

### What gets recorded on the test case

After the Kontonummer reaches the clipboard, the customer's **properties** are written
onto the ADO-selected test case by `de.ing.qa.studio.TestCaseProfile`
([#126](https://github.com/Wladefant/ing-qa-automation/issues/126)) — the profile, never
the identity: the account number is dropped on the way in.

The row handed over is the **raw** one. The table shows a *filtered* subset in a
*translated* view, so neither the selected index nor the displayed cells identify the
customer: the panel keeps the raw rows in screen order and resolves the selection through
that. Labels are display only; persistence bypasses them entirely.
- **Read-only.** It selects existing customers; it never creates or mutates one, so it
  cannot damage the shared Wertpapierkunde.
- Blocklisted accounts are dropped before anything is shown.

**Testfall wählen** — a searchable list of the ADO test cases: ID, title, and the
suite the case lives in. Search matches ID, title and suite, and every typed word
must hit (`partner 360` narrows). Selecting a case shows its detail; *Diesen Testfall
übernehmen* writes the chosen ID to `selected-testcase.json`, which the Übersicht
panel and the rest of the tester flow read.

Taking a case changes **four** things at once, on purpose:

- a green banner over the detail pane: *"✔ Testfall 12345 übernommen — jetzt in
  »Testfall-Übersicht« ansehen"*,
- a **✔ marker that stays** on the row, so the state is obvious when you come back,
- the button turning into *✔ Bereits übernommen*,
- the status line saying the same thing.

> **Why so loud.** The first version wrote the file and put one grey sentence in the
> status line at the bottom of a full-screen window. It *worked* — the file was
> verifiably written on the tester machine — and the tester still reported *"nothing
> happens when I click Diesen Testfall übernehmen"*. Silent success and a dead button
> look identical. A take that **fails** is now equally loud: a red banner naming the
> exception and the file it tried to write.

**In Azure DevOps öffnen** — both ADO panels open the selected work item in the
browser. See *Opening a test case in Azure DevOps* below.

**Testfall-Übersicht** — what the tester needs *before* starting the chosen case,
led by **Voraussetzungen**: the preconditions describing what the customer must be
able to do. That block decides whether the test data at hand fits at all, so it comes
before description and steps rather than under them. The panel also names the ADO
field the preconditions were read from — see the caveat below.

It re-reads `selected-testcase.json` **every time it becomes visible**. The Studio
keeps plugin screens in a `CardLayout` and only toggles their visibility, so the panel
listens for `HierarchyEvent.SHOWING_CHANGED` rather than relying on being re-added.
Nothing is cached across that boundary — the file on disk is the single source of
truth.

**Aufnahme ohne zweite Frage** — the chosen ADO case is now also what the **recorder**
records into. Previously the tester picked a case in *Testfall wählen* and then INGenious
asked *again* ("Choose Recording Target") when Record was pressed, with nothing keeping
the two answers in step.

`de.ing.qa.studio.AdoRecordingTarget` implements the core `RecordingTargetApi`
([PLUGIN-SYSTEM.md §3a](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/reference/PLUGIN-SYSTEM.md)):
Studio asks it where a recording belongs, and it answers from `selected-testcase.json`.

- **Scenario** = the ADO suite (`Partner-Suche Suite`), so a suite worked through in one
  sitting collects into one scenario.
- **Test case** = `<ADO-ID> - <Titel>`, e.g.
  `3951650 - Beispielanwendung SYSTEMTEST Partner-Suche Kunde-360 Set1`. INGenious has no field for
  a foreign key — a test case *is* a name in a folder — so the id lives in the name and
  `AdoNaming.adoIdFromTestCaseName()` is the documented way back, which is what makes a
  later run publishable to the right ADO case.
- **Nothing chosen → nothing changes.** The provider returns `null` and Studio's own
  chooser opens exactly as before. Installing the plugin never takes the stock flow away.
- **Nothing is cached.** The file is re-read on every recording, so switching cases in the
  panel takes effect on the very next one.
- Names are reduced to ASCII letters, digits, space, hyphen and underscore — harsher than
  INGenious requires, because a name also has to be typed into a CLI and read in a tree.
  Umlauts are transliterated (`prüfen` → `pruefen`), not dropped.

Both ADO panels:

- **never call ADO.** They read a local cache written by
  `tools/ado-testcases.mjs`; *Aus ADO aktualisieren* shells out to that same tool.
  The Entra-bearer flow that survives Conditional Access here exists once, in Node,
  and stays there.
- **work offline.** No cache, no `node`, no ADO reachability — each becomes a German
  sentence in the status line and the panel stays usable. Nothing throws.
- **do their I/O off the EDT** (`SwingWorker`), because panels can be built while the
  Studio is starting.

## Build

```
mvn -f ingenious-plugin/pom.xml package
```

Then copy the JAR into the install:

```
<install>/plugins/ing-tester-panel/ing-tester-panel-0.1.0.jar
```

## Prove it

Everything under `harness/` in one command:

```
JAVA_HOME="/c/Program Files/Java/jdk-17" bash ingenious-plugin/harness/run-all.sh
```

It prints PASS / FAIL / SKIP for each harness and, for every skip, **what is missing and
why**. Its exit code does not fold a skip into green: `0` only when everything ran and
passed, `1` on a failure, `4` when nothing failed but something was never put — which is what
a default run returns, because the three `studio-*-driver` harnesses open a real Studio
window and are opt-in (`ING_HARNESS_STUDIO=1`).

Which harness proves what, and the list of checks that had never run at all, is in
[docs/reference/HARNESS-INDEX.md](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/reference/HARNESS-INDEX.md).

## Configure (all optional, all with a documented default)

| Variable | Meaning |
|---|---|
| `ING_TESTDATA_CSV` | CSV export of the test-data workbook. **Keep it out of the repo** — it contains customer data. |
| `ING_TESTDATA_LABELS` | Properties file mapping `Spalte.Wert=Klartext`, e.g. `Boni.21=unbekannt verzogen`. |
| `ING_ADO_CACHE` | ADO test-case cache. Default `%LOCALAPPDATA%\IngQaAutopilot\ado-testcases.json` (beside the existing token cache); `~/.IngQaAutopilot/…` where `LOCALAPPDATA` is unset. |
| `ING_TESTCASE_SELECTION` | Where the chosen test-case ID is written. Default `selected-testcase.json` next to the cache. |
| `ING_QA_REPO` | Repo root, so *Aus ADO aktualisieren* can find `tools/ado-testcases.mjs`. Falls back to walking up from the working directory. |
| *(not an env var)* `StartUrl` in `<project>/Settings/RecorderSettings.Properties` | Page the recorder opens instead of `about:blank`. Project-level, core feature, works without this plugin. **Settable from the panel since 2026-07-28:** *Start-Adresse* + *Adresse übernehmen* in step 3 validates the shape, writes it through `RecorderSettings.setStartUrl`, saves it with `RecorderSettings.save()` and re-reads the file before reporting success. *(This row read **"Hand-edited or nothing"** until then, and that was true: `RecorderSettings.setStartUrl` has no caller anywhere in the INGenious core — no Settings field ever writes it — so a fresh install with no plugin still records against a blank browser. The zero is pinned by `StudioContractHarness`; the Settings field itself still belongs in core.)* |
| `ING_QA_PANELS` | `alle` puts *Testdaten*, *Testfall wählen* and *Testfall-Übersicht* back as tabs beside *Ablauf*, for engineers who use them singly. Unset (the default) shows the guided flow alone. Also readable as a system property, so a test can flip it. |
| `ING_NODE` | Node executable. Default `node` from `PATH`. |
| `ING_ADO_UPLOAD` | `0`/`off`/`false`/`nein` switches the evidence upload off. **On by default** — read and owned by `ado-upload.mjs`, not by this plugin. |
| `ING_ADO_UPLOAD_LOGS` | Where receipts, the ledger and the upload logs go. Default `~/ingenious/companion-logs` (`COMPANION_LOGS_DIR` is honoured too, so both surfaces write one trail). |
| `ING_INGENIOUS_PROJECT` | Project directory whose `Results` are watched. Default: the project Studio has open. |

`sample/` holds a small example of each, shaped like the real thing and free of real
data, used by the headless harnesses.

## ADO cache — where it comes from and how to refresh it

```
node tools/ado-testcases.mjs                       # writes BOTH outputs
node tools/ado-testcases.mjs --cache <path>        # cache elsewhere
node tools/ado-testcases.mjs --json                # cache to stdout, writes nothing
```

One fetch, two files: the companion queue (`--out`, execution shape) and the panel
cache (`--cache`, reading shape — suite, state, description, Voraussetzungen). The
tool needs a Conditional-Access-compliant ING machine and a tenant-scoped
`az login --tenant 00000000-0000-0000-0000-000000000000`; the panels do not.

Inside the Studio, *Aus ADO aktualisieren* runs exactly that command and reloads.
Between refreshes the panels serve the cached snapshot, and the Übersicht shows its
`generatedAt` so a stale snapshot is visible as stale.

## Opening a test case in Azure DevOps

Both ADO panels carry an **In Azure DevOps öffnen** button, and the ADO-ID itself is
rendered as a link in the detail/overview view.

The URL is **never guessed**. Resolution order:

1. **`url` from the cache** — `tools/ado-testcases.mjs` now asks ADO for
   `$expand=links` and stores `_links.html.href` verbatim per case. This is ADO's own
   link and is always preferred.
2. **Constructed from the cache's `org`/`project`** — for a cache written before the
   tool stored links (the 6609-case snapshot on the laptop is one), the panel builds
   `https://dev.azure.com/<org>/<project>/_workitems/edit/<id>`.
3. **Nothing** — the action is **disabled** with a German tooltip explaining that the
   file has neither a `url` field nor org/project, and pointing at *Aus ADO
   aktualisieren*. A guessed link that 404s is worse than no link.

The two shapes verified against the real plan on 2026-07-27 (`beispiel-org/BeispielProjekt`,
work item 4502263, over a live Entra bearer):

| Source | URL |
|---|---|
| ADO's `_links.html.href` | `https://dev.azure.com/beispiel-org/11111111-1111-1111-1111-111111111111/_workitems/edit/4502263` |
| Constructed by the panel | `https://dev.azure.com/beispiel-org/BeispielProjekt/_workitems/edit/4502263` |

ADO returns the **project GUID**, not the project name. Both routes resolve — the
constructed one answered `HTTP 200` on the same authenticated request. The tooltip
always says which of the two you are about to open.

Opening is guarded: `Desktop.isDesktopSupported()` **and**
`isSupported(Action.BROWSE)` are checked, the launch happens off the EDT (starting the
default browser can block for seconds), and any failure shows the URL in a
pre-selected, copyable field so it can be pasted by hand.

### Which field is "Voraussetzungen" — answered against real ADO (2026-07-27)

**There is no Voraussetzungen field. There is no custom field at all.**

Measured on the laptop against Test Plan **1234567**, `beispiel-org/BeispielProjekt` —
**6609 real test cases**, work items fetched with `$expand=all`:

- The `Test Case` work item type in this project carries **only stock fields**. Across
  a 25-case probe every reference name was `System.*`, `Microsoft.VSTS.Common.*` or
  `Microsoft.VSTS.TCM.*`. Not one `Custom.*` field exists.
- `Microsoft.VSTS.TCM.SystemInfo` — the documented fallback — is **empty on all 6609
  cases**.
- Discovery by name therefore found nothing, and the tool reported it honestly:
  `0/6609 case(s) have Voraussetzungen — NO precondition field found`.

**Where the preconditions actually are:** inside `System.Description`, as a
free-text heading in prose migrated out of HPQC. 4833 of 6609 cases have a
description, and within them:

| Heading | Occurrences |
|---|---|
| `Voraussetzungen:` | 4377 |
| `Voraussetzung:` | 50 |

So roughly **two thirds of the plan states preconditions**, and none of it is
machine-addressable as a field. A real description looks like this:

```
Zusätzliche Informationen aus HPQC:
Test ID HPQC: 220919
Orgeinheit: Voice Solutions
Testobjekt: Beispielanwendung
Testaufgabe: Livetest
Testpaket: Livestellungstest
--------------------------------------------------------------------------------
Beschreibung und Ziel:
Getestet wird die Änderung der Zahlungsverbindung bei einem Extrakonto und das
Vier-Augen-Prinzip

Voraussetzungen:
Rolle Cal KD Kundenbetreuer oder Cal KD Kredit oder Cal KD Wertpapier
```

**Consequence, and what NOT to do.** `--precondition-field System.Description` would
"work" in the sense that the panel fills up — and it would be wrong: it would label
the HPQC header, the org unit and the test objective as preconditions. The correct fix
is to extract the `Voraussetzungen:` section out of `System.Description` (from the
heading to the next blank line or heading), not to point the field selector at the
whole description. **That extraction is not implemented yet**, so until it is, the
Übersicht panel correctly reports "keine Voraussetzungen hinterlegt" for every case
even though two thirds of them do state some.

The cache still records which field text came from and the Übersicht prints it, so
"this case states no preconditions" stays distinguishable from "we read the wrong
field".

## After the run — the evidence goes to ADO by itself

The last link of the chain the tester never sees: **Testfall wählen → Kunde wählen →
Aufnahme → Lauf → Nachweise, Kommentar und Ergebnis stehen in Azure DevOps.**

`AdoRunWatcher` watches `<project>/Results` — the directory the engine builds every run
under — and hands each newly finished run directory to `AdoUpload`, which invokes two
proven Node tools and re-implements neither:

| Step | Tool | What it decides |
|---|---|---|
| which cases ran, and did they pass | `tools/parse-report.mjs` | the report format is its problem |
| ADO id from the test case name | `AdoNaming.adoIdFromTestCaseName` | the same rule the recorder named the case by |
| evidence, receipt, ledger, ADO lifecycle | `ing-qa-recorder/mvp/ado-upload.mjs` | ranking, caps, `ado-automark`, the Test Run |

- **On by default.** Switch it off with `ING_ADO_UPLOAD=0`, and even then the receipt says
  so — "off" and "broken" must never look the same ([#82](https://github.com/Wladefant/ing-qa-automation/issues/82)).
- **A failed upload never fails the run.** The run is finished and on disk before any of
  this starts; `--hook` makes the child exit 0 whatever happens.
- **Nothing on the event dispatch thread.** One daemon thread does all of it.
- **The comment carries the test case id and nothing else.** No `--comment` is passed, so
  no Kontonummer and no customer detail can reach a live banking system.
- **History is never uploaded.** Everything already in `Results` when a project is first
  seen is recorded as seen; a directory is handed over once, never twice.
- **A case with no ADO id in its name is reported, not guessed at.** Uploading a run to
  whichever case merely happens to be selected is the failure this exists to prevent.

**There is no callback to hook, and this is the honest consequence.** Studio runs the
engine in-process (`EngineConfig.runProject` → `Control.call`) and no plugin contract —
`StudioPanelApi`, `RecordingTargetApi`, or the engine's action plugins — is told a run
ended. The engine's own `SummaryReport.register` is public, but `Control.resetAll()`
clears every registered handler after each run, so a handler registered once is silently
dropped from the second run on. **The clean fix is a core one:** a `RunCompletionApi`
contract in `ingenious-api` called from `Control.endExecution()`, carrying the run
directory and the per-case outcome. Until that exists, the watcher is the truthful option.

The watcher is armed from `AdoRecordingTarget` — the moment Studio asks where a recording
belongs, which is the guided flow's *Aufnahme starten*. **One line in `GuidedFlowPanel`
would make it independent of recording at all:** `AdoRunWatcher.arm();` in `createPanel()`
would arm it when the panel opens, so a session that only *runs* an already-recorded case
also uploads. `arm()` is idempotent and non-blocking, so calling it from both places is
safe.

## Verified

**A second press on *Aufnahme starten* can no longer end the recording** (2026-07-28,
`run-guided-flow-harness.sh`, scenario `aufnahme`, 36 checks). The panel is driven
against a Studio whose Record method is the real toggle, and the proof is a call
counter, not a message: a start request arriving during a live recording is refused
*and* `record()` is never entered, so the recording survives it. The mirror case is
checked too — a stop request with nothing running never starts one. Rendered at
`target/harness-guided/12-…17-…png`.

Every name that proof reflects on is pinned against the **built** `ingenious-ide-3.0.0`
and `ingenious-datalib-3.0.0` jars by `StudioContractHarness` (26 checks), together with
the two facts the panel's behaviour depends on: that `record()`'s first branch still
stops a running recording, and that `setStartUrl` still has zero callers in the core. A
rename or a fix on either side turns that check red instead of leaving folklore behind.

*Added 2026-07-28 (24 checks → 26): `RecorderSettings.save()` and `getLocation()`, the two
the start-address write depends on. Both are inherited from `AbstractPropSettings`, so the
`getDeclaredMethod` every other pin uses could not see them and they shipped unpinned;
`inherited()` asks with `getMethod`, the same search the plugin's reflective call performs.*

**A finished INGenious run really reached Azure DevOps from the Studio code path**
(2026-07-28, a managed Windows laptop, Entra bearer via `az`, no PAT):

```
ADO-Upload OK — ADO-Lauf 25518995 angelegt, 12 Datei(en) angehängt.
https://dev.azure.com/beispiel-org/BeispielProjekt/_TestManagement/Runs?runId=25518995
```

Run **25518995** on test case **3951650**, twelve files attached — `summary-v2.html`,
`detailed-v2.html`, both per-case reports, `data.js`, two Playwright traces, two step
screenshots, two logs and `console.txt`. The receipt records `"comment": "3951650"`: the
id and nothing else. The input was a real INGenious run directory, and Java called the
real `parse-report.mjs` and the real `ado-upload.mjs` as child processes.

```
bash ingenious-plugin/harness/run-run-watcher-harness.sh   # 24 checks, 0 failed
```

Four headless scenarios against **real INGenious run output** from `artifacts/` — only the
test case name is rewritten to carry an ADO id, exactly as `AdoRecordingTarget` would have
named it. `zeile` (the status-line contract), `entdeckt` (history ignored, a new run found
once, a half-written run left alone), `kette` (id recovered, `ado-upload.mjs` really
invoked, receipt + ledger + log on disk), `durchgefallen` (a real failed run is never
dressed up as Bestanden). The fifth scenario, `echt`, is the one above and is deliberately
not in the script: it writes to a live banking system.

**The recorder no longer asks twice — proven in a real running Studio, both directions**
(2026-07-27, local Windows 11 build of `adb85441`). `harness/StudioRecordDriver.java` starts
Studio through its own `Main`, opens a project and invokes the very method the Record button
invokes; the verdict is whether Studio's `Choose Recording Target` window is showing:

| Selection file | Visible dialogs | Test case opened |
|---|---|---|
| present (ADO 3951650) | `Console` only — **no chooser** | `Partner-Suche Suite / 3951650 - Beispielanwendung SYSTEMTEST Partner-Suche Kunde-360 Set1` |
| absent | `Choose Recording Target` (modal) | unchanged — stock flow intact |

The Studio console in the first case reads:

```
🎬 Playwright Recording is being initiated...
🎯 Recording into Partner-Suche Suite / 3951650 - Beispielanwendung SYSTEMTEST Partner-Suche Kunde-360 Set1
🌐 Opening https://example.org/beispielanwendung?mandant=1&view=partner
```

The start URL came from the project's `RecorderSettings.Properties` with no plugin
involvement, and reached a real browser: a Playwright Inspector window opened titled with
that exact URL, `&` intact.

```
bash ingenious-plugin/harness/run-recording-target-harness.sh   # 17 checks, 0 failed
bash ingenious-plugin/harness/run-studio-record-driver.sh <install-root>
```

Against a real Azure DevOps and a running Studio, on a managed Windows laptop (2026-07-27):

- `node tools/ado-testcases.mjs` fetched **6609 unique test cases** from Test Plan
  1234567 across ~200 suites, wrote a 13.4 MB panel cache, and parsed steps for 6544 of
  them. Auth was the Entra bearer minted non-interactively by `az` — no login prompt.
- A running Studio (INGenious `7eebdc36`) discovered **all three panels at startup**:
  `Studio panel discovery finished: 3 panel(s)`, before a project was even opened.
- The **Testdaten** panel was exercised in a running Studio against a 3000-row slice of
  the real workbook: table renders, filters narrow it, reset restores.

**The guided flow, rendered and driven** (2026-07-28, local Windows 11):

```
bash ingenious-plugin/harness/run-guided-flow-harness.sh    # 92 checks, 0 failed
```

92 checks over five scenarios — `flow` (42), `dirty` (7), `nocustomer` (4), `labels` (23),
`persist` (16) — clicking
the real buttons and reading the rendered text back, plus a PNG of every step in
`ingenious-plugin/target/harness-guided/`. Proven there: the case text is on screen at
the moment the customer is chosen; taking a case advances the flow and writes
`selected-testcase.json`; the copied Kontonummer really reaches the system clipboard;
the summary names both choices and no file path; a locked step explains itself; going
back keeps both answers; **no dialog opens at any point** — judged from
`Window.getWindows()`, never from a responsive Event Dispatch Thread, because a modal
dialog pumps the event queue and liveness proves nothing.

`labels` runs with `ING_TESTDATA_LABELS` **deliberately unset**, against the real column
names, and pins both halves of the rule: `P` reads *Einzelkunde (P)* and `21` reads
*unbekannt verzogen (21)*, while the unconfirmed `Boni 4` and `Kundenart J` stay raw.
Umlauts are checked in the captions, the values, the filter row and the summary.

`persist` filters down to a customer who is **not** first in the file, selects it, and
proves the recorded row is that customer in raw codes (`P`, `J`, `66`) while the screen
beside it reads *Einzelkunde (P)* and *ja (J)* — the case where an index into the backing
list, or a read of the displayed cells, would silently record the wrong thing.

Not covered by that harness: `Aufnahme starten` reaching a real Studio, and the final
write through Studio's own project handle (proved by the persistence lane against a real
project). Without a Studio the harness proves the honest fallbacks — an amber banner
pointing at the toolbar button, and a copy that still delivers the Kontonummer.

Headless, with committed sample data:

```
mvn -f ingenious-plugin/pom.xml package
bash ingenious-plugin/harness/run-ado-harness.sh
bash ingenious-plugin/harness/run-recording-target-harness.sh
node tools/ado-testcases.mjs --selftest
```

- **Testdaten** — 6 columns, **5 of 6 sample rows**: the blocklisted account is
  correctly withheld. Discovered by INGenious's own `PluginLoader`
  (`Loaded Studio panel plugin: Testdaten` → slide `Plugin:Testdaten`).
- **ADO panels, 85 checks green** over six scenarios, driving the real Swing
  components through real clicks:
  - `cache` (47) — lists ID/title/suite; filters by free text, multiple tokens and
    ADO-ID; empty result does not break; reset restores; *übernehmen* persists the ID
    and reads back; the confirmation banner, the ✔ row marker rendered by the real cell
    renderer, the *Bereits übernommen* button and the status line all name the case; the
    selection survives a full rebuild of the panel; the Übersicht renders that case
    leading with Voraussetzungen and naming its source field, and picks up a **new**
    selection when it is shown again; the ADO link resolves from `_links.html.href` for
    one case and from org/project for the other.
  - `empty` (12) — no cache, no reachable tool: German message in both panels, empty
    list, still responds to search, refresh names the missing `ING_QA_REPO`.
  - `toolfail` (5) — tool present but exiting non-zero: its error reaches the status
    line, the panel stays usable.
  - `nourl` (9) — cache with neither `url` nor org/project: the ADO action is disabled
    in both panels with a German reason; no link is invented.
  - `writefail` (7) — the selection file cannot be written (`DirectoryNotEmptyException`):
    red banner naming the exception **and** the path, and nothing is reported as taken.
  - `badpath` (5) — an illegal `ING_TESTCASE_SELECTION` throws the *unchecked*
    `InvalidPathException`. This is the regression that made the button look genuinely
    dead: it escaped `catch (IOException)` into the EDT, aborting the load with no
    message at all. Now it is a red banner and the panel keeps working.
- `ado-testcases.mjs --selftest` covers the cache shape over an all-fields fixture:
  custom-field discovery, `SystemInfo` fallback, HTML→plain text, the verbatim
  `_links.html.href` (and an *empty* url where ADO gave none — never a guess), and
  `--fields-only` degrading to an honestly empty precondition.

## Not verified / not done

- **Nobody has pressed *Aufnahme starten* in a running Studio.** `StudioRecorder` invokes
  the same method chain `harness/StudioRecordDriver.java` already drove successfully
  (`getTestDesign().getTestCaseComp().record()`), but from inside a plugin panel rather
  than from a driver, and reflectively. If the plugin classloader or a Studio version
  disagrees, the tester gets the amber fallback instead of a recording.
- **Three quarters of the codes have a meaning; a quarter do not.** `EZB`,
  `Legi Status Kz` and 31 of the 32 Bonität codes are unanswered and therefore render raw.
  They are listed, with record counts, in the label file and in
  [RENE-QUESTIONS-2026-07-22.md](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/meetings/RENE-QUESTIONS-2026-07-22.md).
  Filling them in is a conversation, not a code change.
- **The earlier "41% off-shape rows" figure was wrong** — an artefact of a quote-blind
  split, and the real 5.4% was our own converter dropping trailing empty cells, fixed in
  [24b5b14](https://github.com/Wladefant/ing-qa-automation/commit/24b5b14). A correctly
  converted `BON_KUNDE` is 50,015 of 50,015 uniform rows.
- **`GuidedFlowPanel` has not been through a Maven build here** — no Maven on the
  workstation this was written on; it is compiled with `javac` by the harness script. The
  only `pom.xml` change is one class name added to `pluginEntryClasses`.
- **Voraussetzungen are not extracted from `System.Description` yet** — see the section
  above. The data is there for ~4400 real cases; the tool does not parse it out, so the
  panel shows none of it.
- **The live refresh path is still unproven.** `Aus ADO aktualisieren` inside Studio has
  never been clicked against real ADO. The same command run from a shell on the laptop
  works (that is how the cache above was produced), but Studio spawning it is a
  different path.
- **Suite/state values are real now, outcomes are not exercised.** 6544 of 6609 cases
  parsed their steps; the 65 without are cases whose Steps XML is empty in ADO.
- **Nobody has watched a run finish inside a real Studio.** The upload is proven from a
  real run directory on the laptop (run 25518995 above), and the watcher is proven against
  a real `Results` tree — but the two have not been joined by pressing Run in Studio and
  watching ADO fill in. What is unproven is exactly one link: that `AdoRunWatcher`
  reflectively finds the open project (`AppMainFrame.getProject().getLocation()`) inside
  the plugin classloader. Everything downstream of that path is proven.
- **The watcher only arms once a recording has been started** in the session, because
  `AdoRecordingTarget` is where the plugin is called. A session that only re-runs an
  existing case uploads nothing until the one-line `GuidedFlowPanel` hook above lands.
- **A test set uploads its whole run directory as each case's evidence.** For the guided
  flow that is exact — a single-case run has its own directory. For a set of five cases,
  all five get the set's evidence. Honest, and noisier than it needs to be.
- **No one has clicked *In Azure DevOps öffnen* in a running Studio yet.** The URL
  shapes are verified (both answered HTTP 200 over the API), and the browser launch is
  covered by guards and a copyable fallback — but whether the ING browser accepts the
  link without a fresh sign-in is a human check.
- **The 6609-case cache on the laptop predates per-case links**, so every case there
  currently uses the *constructed* URL. Re-running `tools/ado-testcases.mjs` fills in
  ADO's own hrefs.
