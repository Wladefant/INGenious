# selector-uniqueness.mjs

Opens a real page and counts how many elements each Object-Repository entry of an INGenious
project actually matches. Node built-ins plus the `playwright` package.

The reasoning behind it — what the recorder produces, what the engine already catches, and why
no static check is possible — is in
[SELECTOR-UNIQUENESS.md](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/reference/SELECTOR-UNIQUENESS.md).

## The problem it solves

A recorded step whose selector matches more than one element does not fail at record time. It
fails later, on someone else's machine, and reads as flakiness rather than as a defect in the
recording.

The engine does fail loudly on most of these at **replay** — Playwright strict mode raises and
INGenious copies the whole message into the report, matches listed. Two gaps remain:

1. **Replay is too late.** By then the recording is saved and possibly handed over.
2. **One branch of the engine is not strict.**
   [`AutomationObject.java:507`](https://github.com/ing-bank/INGenious/blob/15274331756a9accb1cfe742da11c0b7045de2d6/Engine/src/main/java/com/ing/engine/drivers/AutomationObject.java#L507)
   builds `framelocator.locator("css=" + value)`**`.first()`**. Inside a frame, an ambiguous
   `css` selector silently clicks the first match and the test **passes**. Filed as
   [UPSTREAM-ISSUES #11](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/reference/INGENIOUS-UPSTREAM-ISSUES.md#11--inside-a-frame-an-ambiguous-css-selector-silently-resolves-to-first--the-run-passes-on-the-wrong-element).

This tool counts with `Locator.count()` instead of acting. `count()` returns a number where
strict mode throws, which is what lets it see case 2 — the engine never will. Those rows are
marked `AMBIGUOUS (SILENT AT REPLAY)`.

## Usage

```bash
node tools/selector-uniqueness.mjs --project <dir> --url <url> [options]
node tools/selector-uniqueness.mjs --selftest
```

| Flag | Meaning |
|---|---|
| `--project <dir>` | INGenious project root (the folder holding `ObjectRepository/`) |
| `--url <url>` | the page to probe, opened once |
| `--page <name>` | probe only this OR page; repeatable, default all |
| `--storage-state <file>` | Playwright storageState JSON, for a target behind a login |
| `--browser <name>` | `chromium` (default), `firefox`, `webkit` |
| `--timeout-ms <n>` | page load timeout, default 30000 |
| `--settle-ms <n>` | wait after load before counting, default 500 |
| `--json <file>` | write the full result |
| `--headed` | show the browser |
| `--selftest` | probe the bundled fixture end to end, offline |

## Exit contract

| Exit | Meaning |
|---:|---|
| 0 | **every** object was tested and every one matched exactly one element |
| 1 | at least one object matched 2 or more elements |
| 2 | `CANNOT_TELL` — something could not be tested here |
| 3 | arguments rejected |

**Exit 0 is deliberately hard to reach.** An object that is not present on the probed page was
not checked, so partial coverage exits 2 with a count of what was and was not decided. A tool
that reported green for five untested objects out of six would be worse than no tool.

Exit 2 covers: an object absent from this page state, an attribute the probe does not
reimplement, a frame selector that matches zero or several iframes, an unreachable page, and a
missing `playwright` package. In every one of those the message says what was *not* checked.

## Fidelity to the engine

The locator is rebuilt the way
[`AutomationObject`](https://github.com/ing-bank/INGenious/blob/15274331756a9accb1cfe742da11c0b7045de2d6/Engine/src/main/java/com/ing/engine/drivers/AutomationObject.java)
builds it, not the way it looks in the YAML:

- **Attribute precedence.** `getElementsInternal` iterates `WebOR.OBJECT_PROPS` —
  `Role, Text, Label, Placeholder, xpath, css, AltText, Title, TestId, ChainedLocator, JSPath` —
  and breaks on the first non-empty one. Later attributes are never used by the engine; the tool
  probes the same one it will and lists the ignored ones as a note.
- **`Role`** splits on `;` into role and accessible name (`createRoleLocator`).
- **`frame`** is a `;`-separated chain walked with `frameLocator` (`switchFrame`). A frame
  selector matching several iframes is itself reported as ambiguity rather than resolved.
- **`ChainedLocator` / `JSPath`** are **not** probed. The engine parses these with a bespoke
  chain grammar; guessing at it could probe a locator the engine never builds. Reported as not
  tested.

## Proof

`tools/fixtures/ambiguous-selectors/` is a real page whose ambiguity is deliberate and mirrors
shapes the ING application is documented to have: same-named controls per panel, and a legacy
iframe carrying controls that also exist in the parent document. `AmbiguityProbe/` is a real
INGenious project over it, runnable with `tools/ingenious-run.mjs` — the engine's own verdict on
the same page, for comparison.

```bash
node tools/fixtures/ambiguous-selectors/serve.mjs --port 8731 &
node tools/selector-uniqueness.mjs \
     --project tools/fixtures/ambiguous-selectors/AmbiguityProbe \
     --url http://127.0.0.1:8731/
# → 8 object(s): 5 ambiguous (1 silent at replay), 3 unique, 0 not tested   [exit 1]
```

`--selftest` runs the same probe with its own server and asserts nine expectations derived from
the markup — including that `role` inside a frame is **not** flagged silent, keeping the
frame-only claim narrow.
