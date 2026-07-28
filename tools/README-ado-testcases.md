# ado-testcases.mjs

Pull the **real** test cases of an Azure DevOps Test Plan and emit them as a
companion queue JSON the Tester UI (`ui.TesterWindow`) can load and search.
Node.js built-ins only. **Read-only against ADO** — only GETs.

## What it does

1. `GET _apis/testplan/Plans/{plan}/suites` (follows `x-ms-continuationtoken`).
2. Per suite, `GET .../Suites/{id}/TestPoint` (also paginated) → collects the
   **unique** test cases (id, name, suiteId, suiteName, outcome/state).
3. `GET _apis/wit/workitems?ids=…&fields=System.Title,Microsoft.VSTS.TCM.Steps`
   → parses the HTML-encoded Steps XML into plain step strings
   (`action → Erwartet: expected`).
4. Writes a companion queue to `--out` **and** a panel cache to `--cache`.

## Two outputs, one fetch

| Flag | File | Shape | Consumer |
|---|---|---|---|
| `--out` | `companion/queue/queue.ado.json` | execution: `adoId`, `title`, `steps`, `mapping`, `testset` | the loop engine |
| `--cache` | `%LOCALAPPDATA%\IngQaAutopilot\ado-testcases.json` | reading: `+ suiteName`, `state`, `outcome`, `description`, `preconditions`, `preconditionField` | the INGenious Studio panels *Testfall wählen* / *Testfall-Übersicht* |

The panels **never call ADO** — they only read that cache, which is why they work on
a machine with no ADO reachability. Override the location with `--cache <path>` or
the `ING_ADO_CACHE` environment variable (the Java side resolves the same default).
`--json` prints the cache to stdout and writes nothing.

### "Voraussetzungen" is not a standard ADO field

Naming a non-existent field in `fields=` makes ADO reject the whole batch, so the
work-item batch is fetched **without** a field filter and the precondition field is
discovered by reference name (`*voraussetzung*`, `*vorbedingung*`, `*precondition*`,
`*prerequisit*`), falling back to `Microsoft.VSTS.TCM.SystemInfo`. Override with
`--precondition-field <ReferenceName>`. `--fields-only` restores the original narrow
fetch; the queue still works, the cache then simply has no preconditions — empty, never
invented.

The emitted cache records `preconditionField` per case plus a `preconditionFieldsSeen`
summary, so an empty box is never silently presented as "no preconditions". **Which
field the real ING process uses is unverified** — this machine cannot reach ADO.

Each emitted case carries `mapping: "default"` — an **honest** marker that no
per-case INGenious testset mapping exists yet, so every case points at
`Release1`/`Set1` of the shipped Beispielanwendung project (`projectLocation` =
aus `ING_QA_PROJECT_LOCATION`, sonst `<nicht eingerichtet>`) until real
mappings are authored. `acceptanceCriteria` is emitted empty (ADO steps carry
action+expected, not separate criteria).

## Usage

```bash
# Auth precondition — a Conditional-Access-compliant ING machine (the laptop):
az login --tenant 00000000-0000-0000-0000-000000000000      # once per ~50 min

node tools/ado-testcases.mjs                                 # defaults below
# or explicit:
node tools/ado-testcases.mjs --org beispiel-org --project BeispielProjekt --plan 1234567 \
     --out companion/queue/queue.ado.json
```

Defaults: `--org beispiel-org`, `--project BeispielProjekt`, `--plan 1234567`,
`--out companion/queue/queue.ado.json`. Prints a per-suite count summary.

## Auth (shared with ado-automark)

The Entra-bearer token logic is **imported** from
`ing-qa-recorder/mvp/ado-automark.mjs` (the source of truth): resource
`499b84ac-1321-427f-aa17-267ca6975798`, tenant
`00000000-0000-0000-0000-000000000000`, ~50-min cache at
`%LOCALAPPDATA%\IngQaAutopilot\token.json`, auto `az login --tenant` fallback.
No PAT, ever. A bare `az login` **without** `--tenant` does not work at ING.
This machine cannot reach ADO (Conditional Access) — the live fetch is verified
on the laptop only.

## Selftest (offline)

```bash
node tools/ado-testcases.mjs --selftest    # exit 0
```

Feeds committed fixtures under `tools/fixtures/ado-testcases/` (a two-page suites
response, per-suite test points incl. a cross-suite duplicate, a work-items
batch with real Steps XML, and an all-fields batch) through the **same**
parsing/emission code path — no `az`, no network. Asserts continuation-token
pagination, cross-suite de-duplication, Steps-XML parsing (entity decode + tag
strip), the emitted queue shape, and the panel cache: custom-field precondition
discovery, the `SystemInfo` fallback, HTML→plain conversion, and `--fields-only`
degrading to an empty precondition.

```bash
# Regenerate the plugin's sample cache from the fixtures (no real data):
node tools/ado-testcases.mjs --selftest --cache ingenious-plugin/sample/ado-testcases-beispiel.json
```
