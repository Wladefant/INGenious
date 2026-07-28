# ingenious-launch

Start INGenious Playwright Studio, or run the INGenious CLI, on a JDK 17+ that is **found
rather than assumed** — so nobody has to remember to prefix `JAVA_HOME\bin` again.

Board card: https://github.com/Wladefant/ing-qa-automation/issues/105 ·
upstream defect: [ing-bank/INGenious#310](https://github.com/ing-bank/INGenious/issues/310)

## The problem it removes

ING managed devices ship **Java 1.8 on `PATH`**, users cannot reorder `PATH` without admin
rights, and `ingenious.bat` invokes bare `java` / `javaw`. Studio therefore dies with

```
UnsupportedClassVersionError ... class file version 61.0
```

— a black window that blinks and vanishes, and a stack trace that tells a tester nothing.

## Usage

```
tools\ingenious-launch.cmd                       # Studio (GUI)
tools\ingenious-launch.cmd -Check                # can this machine run INGenious? exit 0 = yes
tools\ingenious-launch.cmd <any ingenious.bat arguments>
```

```powershell
# Engineer: run a hand-off package headlessly (legacy CLI, works on 3.0 and 3.1)
tools\ingenious-launch.cmd -project_location "Projects\HandoffProof" -release Release1 `
    -testset HandoffSet -browser Chromium -run -quit -dont_launch_report
```

`ingenious-launch.cmd` is a three-line wrapper around `ingenious-launch.ps1`; call the `.ps1`
directly from PowerShell if you prefer. Double-clicking the `.cmd` starts Studio and, if
anything failed, keeps the window open so the message can be read.

| Option | Meaning |
|---|---|
| `-Install <dir>` | the INGenious folder (the one holding `ingenious.bat`). Default: found — see below |
| `-JavaHome <dir>` | force one JDK. Still version-checked; a too-old one is refused, not used |
| `-Check` | report the JDK and install that would be used, launch nothing |
| everything else | passed to `ingenious.bat` unchanged |

Environment: `INGENIOUS_JAVA_HOME` and `INGENIOUS_HOME` override discovery without touching
the command line.

Exit codes: `0` ok · `2` no install / arguments name a project or test set that does not
exist · `3` no usable JDK · otherwise INGenious' own.

## How it picks a JDK

1. `-JavaHome`, else `INGENIOUS_JAVA_HOME`, `JAVA_HOME`, then every JDK-shaped folder under
   `Program Files\{Java,Microsoft,Eclipse Adoptium,Amazon Corretto,Zulu,Semeru}`,
   `Program Files (x86)\Java`, `%LOCALAPPDATA%\Programs\{Java,Eclipse Adoptium}`,
   `%USERPROFILE%\.jdks`, and finally every `java.exe` on `PATH`.
   Per-user roots are searched because a no-admin device is exactly where an unzipped JDK
   ends up inside the user's own profile.
2. Every candidate is verified by **running `java -version`** and parsing the answer —
   `jdk-17` is a directory name a human typed; the JVM is the thing that runs the bytecode.
3. **Lowest major ≥ 17, newest patch within it.** INGenious is built on 17, so the closest
   thing to 17 is the least surprising choice; a JDK 25 that happens to be installed is not
   what anyone tested. Verified: with 11, 16, 17.0.11, 17.0.12, 21.0.1, 21.0.10 and 21.0.11
   present on one machine it selects **17.0.12**.
4. When nothing qualifies it prints an actionable sentence in German and English, lists the
   JDKs it *did* find with their versions, says where it looked, and exits 3.

## It changes nothing outside itself

`JAVA_HOME` and `PATH` are set **in the launcher's own process**, which the launched
INGenious inherits and nothing else does. No system or user `PATH` edit, no registry write,
no admin rights. `-ExecutionPolicy Bypass` in the `.cmd` is process-scoped for the same
reason.

## Why it delegates to `ingenious.bat`

It does not rebuild the java command line, because the command line differs by version:
INGenious **3.0** routes both GUI and CLI to `com.ing.ide.main.Main`, while **3.1** routes
the CLI to `com.ing.engine.core.Control` and keeps the legacy `-project_location` bridge.
Delegating means the launcher does not need to know which build it is talking to — the only
thing it owns is *which java runs it*.

## Two measured INGenious behaviours it works around

- **A misspelled test set produces a stack trace and exit code 0.**
  `-testset GibtEsNicht` yields
  `NullPointerException ... ProjectRunner.getTestSet() is null` and `%ERRORLEVEL% = 0`
  (measured on 3.0.0-preview). The launcher therefore checks `-project_location`,
  `-release` and `-testset` before starting the JVM and lists what *does* exist. Only a
  positively proven miss stops the run; argument shapes it does not recognise are passed
  through untouched.
  **`ingenious.bat`'s exit code is not a pass/fail signal** — read the run summary, or
  [`tools/parse-report.mjs`](https://github.com/Wladefant/ing-qa-automation/blob/main/tools/README-parse-report.md).
- **The GUI detaches, so "it started" proves nothing.** `ingenious.bat` launches Studio via
  `start javaw` and returns at once. The launcher diffs the `javaw` processes around the
  call and reports the PID, or says plainly that Studio exited immediately and where the log
  is. Its child's stdin/stdout/stderr are redirected to files so the detached `javaw` holds
  no handle of a caller that reads this launcher's output through a pipe.

## Evidence

Proven on `DESKTOP-5SDUT2N` against `ingenious-playwright-3.0.0-preview`, JDK 17.0.12:

| What | Result |
|---|---|
| `-Check` | `Java 17.0.12 (C:\Program Files\Java\jdk-17)` · install auto-found · exit 0 |
| Studio, no arguments | `Studio läuft (PID 180848)` after **7.5 s**, exit 0, process still alive |
| 42-step case, legacy CLI | `Executed 1 · Passed 1 · Failed 0`, **42/42 steps**, 17 s |
| same, project path containing spaces | `Passed 1 · Failed 0` — arguments survive the `.cmd` → PowerShell → `.bat` chain |
| `-JavaHome` pointed at JDK 11 | refused, exit 3, `Java 11.0.24 ... found but too old` |
| no JDK ≥ threshold at all | refused, exit 3, all 8 installed JDKs listed with versions |
| `-testset GibtEsNicht` | refused, exit 2, `Vorhanden / available: Release1/HandoffSet` |
| `-project_location Projects\Nixda` | refused, exit 2, the four real projects listed |

**Not yet confirmed on a managed device:** the run above used a machine where
JDK 17 sits in `C:\Program Files\Java\jdk-17`. On a managed device it may be `jdk-17.0.19`, with `PATH` java
is 1.8 — the case the launcher exists for, and the one still to be observed. Nothing in the
discovery logic is specific to either path.
