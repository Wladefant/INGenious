# handoff-pack

Hand a recorded INGenious project from a tester to an automation engineer: one zip that says
what it is, leaves run output and saved sessions behind, and can be verified file-for-file
on arrival.

Tests: `node tools/test-handoff-pack.mjs` (Node stdlib only, no install needed).

Convention, evidence and the honest list of what an engineer still does by hand:
[docs/reference/HANDOFF-TESTER-TO-ENGINEER.md](https://github.com/Wladefant/ing-qa-automation/blob/main/docs/reference/HANDOFF-TESTER-TO-ENGINEER.md)
(issue https://github.com/Wladefant/ing-qa-automation/issues/105).

## Usage

```
node tools/handoff-pack.mjs pack   --project "<install>\Projects\<Name>" [--out <dir>]
                                   [--tester <name>] [--install <ingenious install>]
                                   [--name <file.zip>] [--note <text>]
node tools/handoff-pack.mjs inspect <package.zip>
node tools/handoff-pack.mjs unpack  <package.zip> --into "<install>\Projects" [--name <NewName>]
```

No dependencies (Node 18+). The zip is a standard deflate archive — Windows Explorer and
`Expand-Archive` open it, so the receiving side does not need this tool; `unpack` only adds
the collision check, the hash check and the run command.

**pack**

- Packs the project tree minus run output and local state: `Results/`, `Recording/`,
  `media/`, `.migration-backup/`, saved browser sessions (`login.json`, `*storageState*.json`),
  `.har`/`.trace`/`.webm`/`.mp4`/nested `.zip`, and `*.bak*`/`*.tmp`/`~$*`.
- Does **not** screen the project's numbers. It once refused on any run of ≥8 digits, which
  is the shape of the ADO test-case ids our own naming puts in file names, object names and
  test-case titles — so it refused the projects we produce. Removed 2026-07-28; the hand-off
  stays inside the organisation.
- Warns (does not modify) about credential keys in `Settings/` and absolute paths.
- `--install` points at the INGenious install whose version should be recorded; when the
  project sits under `<install>\Projects\`, it is found automatically.

**unpack**

- Refuses to overwrite an existing project of the same name and suggests a suffixed one.
- Verifies every extracted file against the sha256 recorded at pack time (exit 3 on
  mismatch).
- Prints the customer profile, the warnings, and the exact legacy CLI command for each test
  set, filled in for the receiving install.

## The manifest (`handoff.json`, inside the project folder)

`schema`, `packedAt`, `recordedAt` (newest change under `TestPlan/`), `packedBy`, `packedOn`,
`toolCommit` (repo commit + dirty flag + commit URL), `ingenious` (version of the install it
came from), `adoTestCases` (ids parsed from test case names), `contents` (scenarios, test
cases, releases, test sets), `customerProfiles` (the `Testkunde` sheet as it stands — the
Studio plugin writes settings columns onto it, not the account number), `files`
(path/size/sha256), `excluded`, `warnings` (credential keys, absolute paths), `runs`
(legacy CLI arguments per test set).

## Example

```
$ node tools/handoff-pack.mjs pack --project "$HOME/ingenious/ingenious-playwright-3.0.0-preview/Projects/HandoffProof" --out ./out
handoff-pack: packed HandoffProof
  zip        ...\out\HandoffProof_wkiri_20260728-0104.zip
  size       12.4 KiB zipped, 10.7 KiB of project files
  files      30 packed, 2 paths left behind
  sha256     f2ac3cb83a86fceddc3bbc8b09f042efac5bb677e3a2f2d487ac11efcc7351f3
  ADO cases  9999999
  INGenious  3.0.0-preview
  tooling    3ee1ccbd1967bd1cd9b6488e118b3e2e9ee3bfad
  ! 4 credential setting(s) travel with the project — see warnings in handoff.json
```
