# parse-report.mjs

Parses one INGenious test-run output directory into a normalized JSON results document. Node.js built-ins only (no npm deps).

## Usage

```bash
node tools/parse-report.mjs --run-dir "<path-to-run-dir>"
node tools/parse-report.mjs --run-dir "<path-to-run-dir>" --json out.json
```

## Input shapes

- **data-js** — root `data.js` with `var DATA={...};` and an `EXECUTIONS` array.
- **per-tc-html** — no `data.js`; each test case is a `*_*-v2.html` file with embedded `var DATA = {...};` (empty files → status `unknown`).

## Output

Top-level: `runDir`, `parsedAt`, `shape` (`data-js` | `per-tc-html`), `testCases[]`, `totals` (`cases`, `passed`, `failed`).

Each test case: `name` (`scenario:testcase`), `status` (`PASS` | `FAIL` | `unknown`), `steps` (`{passed, failed, total}` or `null`), `durationSeconds` (or `null`), `screenshots` (paths from the STEPS tree), `reportFile` (HTML basename or `null`).
