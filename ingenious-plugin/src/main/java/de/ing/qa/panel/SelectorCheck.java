package de.ing.qa.panel;

import de.ing.qa.ado.AdoCache;
import de.ing.qa.ado.Json;
import de.ing.qa.studio.AdoRunWatcher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tells the tester, while they are still sitting in front of the recording, which of its steps
 * match more than one element on the page.
 *
 * <h2>Why this is not at record time, which is where it belongs</h2>
 *
 * <p>The obvious home for this check is the moment codegen emits a line: the page is open, the
 * element is under the cursor, and {@code Locator.count()} is one round-trip that cannot throw.
 * That was the recommendation in
 * <a href="https://github.com/Wladefant/ing-qa-automation/blob/main/docs/reference/SELECTOR-UNIQUENESS.md">SELECTOR-UNIQUENESS.md</a>
 * §5, and it rests on a premise — <em>"a live {@code Page} is available"</em> — which is false.
 * It was measured rather than argued:
 *
 * <ul>
 *   <li>{@code TestCaseComponent.startPlaywrightProcess} builds the string
 *       {@code java -cp "lib/*;." com.microsoft.playwright.CLI codegen …} and hands it to
 *       {@code cmd /c}. The recorder is a <b>separate operating-system process running a
 *       separate JVM</b>. Studio's JVM never constructs a Playwright object at all — the whole
 *       IDE module mentions {@code com.microsoft.playwright} exactly twice, both times inside a
 *       command-line string.</li>
 *   <li>Studio learns what was recorded by <b>tailing the file codegen writes</b>
 *       ({@code startLiveRecordingWatcher} re-reads it in a loop). Text goes in, Object-Repository
 *       entries come out. There is no page handle anywhere on that path, so there is nothing for
 *       a plugin to borrow after {@code parseLinesToSteps} either.</li>
 *   <li>The browser codegen drives is launched with <b>{@code --remote-debugging-pipe}</b>, not
 *       {@code --remote-debugging-port} — verified on the running process tree of a real
 *       Studio-shaped recording. A pipe is a pair of inherited file descriptors held by the
 *       driver process; it is not a network endpoint. Nothing in the tree listens on a TCP port
 *       and {@code http://127.0.0.1:9222/json/version} is refused. So the recorder's browser
 *       cannot be attached to from outside either.</li>
 *   <li>The one place in the product where a live {@code Page} does exist in-process is the
 *       Engine's {@code RecordFromHere} action — and the only channel the Engine offers the IDE,
 *       {@code LiveRecordingHook}, carries a {@code TestCase} and an {@code int}. No page, by
 *       construction: its own javadoc records that the Engine module cannot depend on the IDE.</li>
 * </ul>
 *
 * <p>Opening a <em>second</em> browser on the same address during the recording was the remaining
 * candidate and is worse than nothing: it is a different page state — for a banking application,
 * an unauthenticated one — so its counts describe a page the tester is not on. A check that
 * answers about the wrong page is the kind of check this repository has just removed seventeen of.
 *
 * <p><b>So this runs at the nearest point that works: immediately after the recording stops.</b>
 * The tester is still there, the application is still open, the address is the one the recorder
 * itself used, and the Object Repository has just been written. Seconds later instead of never,
 * and the answer is about a page that really existed.
 *
 * <h2>What it does not do</h2>
 *
 * <p><b>It does not count anything.</b> Rebuilding a locator the way {@code AutomationObject}
 * does — the attribute precedence, the {@code ROLE;name} split, the {@code ;}-chained frame walk,
 * and the knowledge that {@code ChainedLocator} and {@code JSPath} must be reported rather than
 * guessed at — already exists once, in
 * {@code tools/selector-uniqueness.mjs}. A second implementation would not stay in step with the
 * first, and the two would disagree about which selectors are ambiguous. This class starts that
 * tool as a child process and reports its answer, exactly as {@link HandoffPack} does with
 * {@code handoff-pack.mjs}.
 *
 * <h2>The rule that shapes every outcome below</h2>
 *
 * <p>Uniqueness is a property of <em>(selector, page state)</em>, never of a selector alone. The
 * tool therefore has an exit code that means <em>could not tell</em>, and reaching exit 0 is
 * deliberately hard: an object that is not on the probed page was not checked. That distinction
 * survives into this class — {@link Outcome#CANNOT_TELL} has its own colour, its own sentence and
 * its own {@link Result#ok()} of {@code false}. It is never folded into the green one, because an
 * untested step that reads as verified is worse than no check at all.
 *
 * <p>Never call {@link #check} on the Swing event dispatch thread: it starts a browser.
 */
final class SelectorCheck {

    /** The probe. Repo-relative, like every other tool the panels shell out to. */
    static final String TOOL_REL = "tools/selector-uniqueness.mjs";

    /**
     * Where the answer is left behind, inside the project itself.
     *
     * <p>In the project rather than beside it for two reasons: it travels with
     * {@code handoff-pack.mjs}, so the engineer who opens the package can see what was checked
     * and when; and the manifest field {@code selectorUniqueness} reads this exact file, so the
     * probe and the package agree without either knowing about the other.
     */
    static final String RECEIPT_NAME = "selector-uniqueness.json";

    /** Opening a page and counting is quick; a slow or hung application must not be. */
    private static final long TIMEOUT_SECONDS = 5 * 60;

    /** How many ambiguous entries are named in the sentence before it says "und N weitere". */
    private static final int NAMED = 3;

    /**
     * What became of one check. Every one of these is said out loud on screen, and only the
     * first is green.
     */
    enum Outcome {
        /** Exit 0: every object was tested on this page and every one matched exactly one element. */
        UNIQUE,
        /** Exit 1: at least one object matched two or more elements. */
        AMBIGUOUS,
        /**
         * Exit 2: something could not be tested here — an object absent from this page state, an
         * attribute the probe does not reimplement, an unreachable address. <b>Not a pass.</b>
         */
        CANNOT_TELL,
        /** Exit 3: the probe rejected its arguments. A defect here, not in the recording. */
        REJECTED,
        /** No INGenious project open, so there is no Object Repository to check. */
        NO_PROJECT,
        /** No address to open. Without a page there is no page state, and no question to ask. */
        NO_URL,
        /** {@code selector-uniqueness.mjs} is not on this machine. */
        NO_TOOL,
        /** Node is not on this machine, so the probe cannot run at all. */
        NO_NODE,
        /** The probe ran and did not finish, or finished in a way it does not document. */
        FAILED
    }

    /**
     * One check, always populated, never an exception.
     *
     * @param outcome which of the nine things happened
     * @param message the German sentence for the tester — what was found, and what to do
     * @param detail the probe's own words, for the tooltip and the log
     * @param receipt the written answer, when one was written
     * @param ambiguous the entries that matched more than one element, most alarming first
     * @param silent how many of those the engine will <b>not</b> catch at replay
     */
    record Result(Outcome outcome, String message, String detail, Path receipt,
            List<String> ambiguous, int silent) {

        /**
         * Whether this recording was checked and found clean.
         *
         * <p>True for exactly one outcome. {@link Outcome#CANNOT_TELL} is not a pass and must
         * never be treated as one — that is the whole reason the probe distinguishes it.
         */
        boolean ok() {
            return outcome == Outcome.UNIQUE;
        }

        /** Whether the probe reached a verdict at all — clean or not. */
        boolean decided() {
            return outcome == Outcome.UNIQUE || outcome == Outcome.AMBIGUOUS;
        }
    }

    private SelectorCheck() {
    }

    // ------------------------------------------------------------------ what we check

    /**
     * The INGenious project Studio has open, or {@code null} when there is none.
     *
     * <p>Read through {@link HandoffPack#projectDir()} rather than by a second copy of the same
     * reflection, so the check and the package can never disagree about which project is open.
     */
    static Path projectDir() {
        return HandoffPack.projectDir();
    }

    /** {@code selector-uniqueness.mjs}, or {@code null} when this machine does not carry it. */
    static Path tool() {
        Path repo = AdoCache.repoRoot();
        if (repo == null) {
            return null;
        }
        Path tool = repo.resolve(TOOL_REL);
        return Files.isRegularFile(tool) ? tool : null;
    }

    /** Where the answer for a given project is left. */
    static Path receiptFor(Path project) {
        return project.resolve(RECEIPT_NAME);
    }

    // ------------------------------------------------------------------ the check

    /**
     * Checks the open project's recorded selectors against a page that is really open.
     *
     * <p>Blocking; never call this on the event dispatch thread.
     *
     * @param url the address to probe — normally the one the recorder itself opened, so the page
     *     state is the one the recording was taken against
     * @param page the Object-Repository page to restrict the check to, or {@code null} for all of
     *     them. After a recording this is the page the recorder just wrote, so the tester is told
     *     about their own steps rather than about every object the project has ever held.
     * @param storageState a Playwright storage state for an application behind a login, or
     *     {@code null}
     * @return what was found, in words a tester can act on — never {@code null}, never a throw
     */
    static Result check(String url, String page, Path storageState) {
        Path project = projectDir();
        if (project == null) {
            return plain(Outcome.NO_PROJECT,
                "○ NICHT geprüft: bitte zuerst das Projekt öffnen, in dem Sie aufgenommen haben. "
                    + "Studio meldet zurzeit kein geöffnetes Projekt.",
                "HandoffPack.projectDir() == null (" + AdoRunWatcher.ENV_PROJECT
                    + " ist nicht gesetzt und kein Projekt geöffnet).");
        }
        if (url == null || url.isBlank()) {
            // Without an address there is no page, and without a page the question has no
            // answer — "unique" is not a property a selector has on its own.
            return plain(Outcome.NO_URL,
                "○ NICHT geprüft: bitte oben eine Adresse eintragen und auf \""
                    + GuidedFlowPanel.BTN_START_URL + "\" klicken. Ohne die Seite, auf der Sie "
                    + "aufgenommen haben, lässt sich nicht feststellen, ob ein Schritt eindeutig "
                    + "ist — dieselbe Angabe trifft auf der einen Seite genau ein Element und auf "
                    + "der nächsten vier.",
                "Keine Startadresse: RecorderSettings.getStartUrl ist leer oder nicht lesbar.");
        }
        Path tool = tool();
        if (tool == null) {
            return plain(Outcome.NO_TOOL,
                "○ NICHT geprüft: bitte bei der Testautomatisierung melden. Die Prüfung ist auf "
                    + "diesem Rechner nicht eingerichtet.",
                TOOL_REL + " wurde nicht gefunden; " + AdoCache.ENV_REPO + " zeigt nicht auf das "
                    + "Repo-Verzeichnis.");
        }

        Path receipt = receiptFor(project);
        List<String> command = new ArrayList<>(List.of(node(), tool.toString(),
            "--project", project.toString(),
            "--url", url,
            "--json", receipt.toString()));
        if (page != null && !page.isBlank()) {
            command.add("--page");
            command.add(page.trim());
        }
        if (storageState != null) {
            command.add("--storage-state");
            command.add(storageState.toString());
        }

        // A stale receipt from an earlier check must not be able to describe this one. If the
        // probe never gets far enough to write a new one, "nothing was written" is the truth.
        try {
            Files.deleteIfExists(receipt);
        } catch (IOException ignored) {
            // A receipt we cannot delete is one we also will not read: written() re-checks
            // that the file is newer than this attempt.
        }
        long startedAt = System.currentTimeMillis();

        Path repo = tool.getParent() == null ? null : tool.getParent().getParent();
        StringBuilder captured = new StringBuilder();
        int exit;
        try {
            exit = run(repo, command, captured);
        } catch (IOException ex) {
            return plain(Outcome.NO_NODE,
                "○ NICHT geprüft: bitte bei der Testautomatisierung melden. Auf diesem Rechner "
                    + "fehlt Node.js, das für die Prüfung gebraucht wird.",
                "node konnte nicht gestartet werden: " + ex.getMessage());
        }
        String output = captured.toString();
        Path written = written(receipt, startedAt);

        return switch (exit) {
            case 0 -> clean(output, written);
            case 1 -> ambiguous(output, written);
            case 2 -> cannotTell(output, written);
            case 3 -> plain(Outcome.REJECTED,
                "○ NICHT geprüft: bitte bei der Testautomatisierung melden. Die Prüfung wurde "
                    + "falsch aufgerufen und hat nichts geprüft.",
                "Exit 3 (Argumente abgelehnt)" + System.lineSeparator() + output);
            case -1 -> plain(Outcome.FAILED,
                "○ NICHT geprüft: bitte bei der Testautomatisierung melden. Die Prüfung wurde "
                    + "nach " + (TIMEOUT_SECONDS / 60) + " Minuten abgebrochen.",
                lastLine(output));
            default -> plain(Outcome.FAILED,
                "○ NICHT geprüft: bitte bei der Testautomatisierung melden. Die Prüfung ist "
                    + "fehlgeschlagen (" + lastLine(output) + ").",
                "Exit " + exit + System.lineSeparator() + output);
        };
    }

    // ------------------------------------------------------------------ the four verdicts

    /**
     * Exit 0: everything the project holds for this page was tested here, and each entry matched
     * exactly one element.
     *
     * <p>The only green sentence in this class, and it says what was covered rather than merely
     * "in Ordnung" — a tester who reads "geprüft" without a number has no way of noticing that
     * the check saw two steps out of twenty.
     */
    private static Result clean(String output, Path receipt) {
        Receipt r = Receipt.read(receipt);
        String scope = r == null ? "" : " " + r.total + " Angabe(n) aus dieser Aufnahme wurden auf "
            + "der Seite gefunden und jede trifft genau ein Element.";
        return new Result(Outcome.UNIQUE,
            "✔ Geprüft: diese Aufnahme ist auf dieser Seite eindeutig." + scope,
            output, receipt, List.of(), 0);
    }

    /**
     * Exit 1: at least one entry matched two or more elements.
     *
     * <p>The instruction comes before the explanation, for the reason the hand-off refusal in
     * {@link HandoffPack} records: the line clips rather than wraps, so whatever survives the
     * clip must be the action.
     *
     * <p><b>The silent ones are named first and separately.</b> An ambiguous {@code css} selector
     * inside a frame is resolved by the engine with a trailing {@code .first()}
     * (<a href="https://github.com/ing-bank/INGenious/issues/320">INGenious #320</a>), so it does
     * not fail at replay — it clicks whichever element happened to be first and the run goes
     * green. Every other ambiguity announces itself the next time the test runs; this one never
     * does, which makes the recording the only place it can still be caught, and this sentence
     * the only warning anybody will get.
     */
    private static Result ambiguous(String output, Path receipt) {
        Receipt r = Receipt.read(receipt);
        if (r == null) {
            // The probe said "ambiguous" and left nothing behind to say where. Reporting clean
            // here is unthinkable; reporting the finding without the detail is merely poor.
            return new Result(Outcome.AMBIGUOUS,
                "✖ Bitte diese Aufnahme nachbessern und bei der Testautomatisierung melden: die "
                    + "Prüfung hat mehrdeutige Schritte gefunden, konnte aber nicht aufschreiben, "
                    + "welche.",
                output, null, List.of(), 0);
        }
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < Math.min(NAMED, r.ambiguous.size()); i++) {
            where.append(i == 0 ? "" : ", ").append(r.ambiguous.get(i));
        }
        if (r.ambiguous.size() > NAMED) {
            where.append(" und ").append(r.ambiguous.size() - NAMED).append(" weitere");
        }
        String silentWarning = r.silent == 0 ? ""
            : " " + r.silent + " davon fällt beim Abspielen NICHT auf: der Test wird grün und "
                + "klickt trotzdem das falsche Element.";
        // The names come before the explanation, and that order was decided by measuring rather
        // than by taste. Rendered at 900 pixels the first draft clipped at "1 davon fällt beim
        // Abs…" — a tester was told to fix their recording and never got as far as which step.
        // The action first, then WHICH, then WHY; the whole sentence stays in the tooltip.
        return new Result(Outcome.AMBIGUOUS,
            "✖ Bitte diese Aufnahme nachbessern: " + r.ambiguous.size() + " Schritt(e) treffen auf "
                + "dieser Seite mehr als ein Element — " + where + "."
                + silentWarning
                + " Der Testfall ist so nicht verlässlich.",
            output, receipt, r.ambiguous, r.silent);
    }

    /**
     * Exit 2: something was not testable here.
     *
     * <p>The most common outcome in real use, and the one that must never look like the green
     * one. A recording usually walks several screens; the probe opens one, so the entries
     * belonging to the later screens come back "not present here" — which is the truthful answer
     * and not a complaint. The sentence therefore says what <em>was</em> decided as well as what
     * was not, so a tester can tell "I need to check the other screens too" apart from "this did
     * not work".
     *
     * <p>Nothing here looks for ambiguous entries, and that is not an oversight: the probe exits
     * 1 the moment it has any, before it ever considers partial coverage. A branch here for "exit
     * 2 but ambiguous anyway" could not fire, and a branch that cannot fire is a claim nobody has
     * tested.
     */
    private static Result cannotTell(String output, Path receipt) {
        Receipt r = Receipt.read(receipt);
        if (r == null) {
            // No receipt at all: the probe never got a page open. It says why on its last line,
            // and that reason is the whole message — there is nothing else known.
            return new Result(Outcome.CANNOT_TELL,
                "○ NICHT geprüft: bitte prüfen, ob die Seite im Browser erreichbar ist, und es "
                    + "dann noch einmal versuchen. Es wurde kein einziger Schritt geprüft — das "
                    + "ist kein \"in Ordnung\". (" + lastLine(output) + ")",
                output, null, List.of(), 0);
        }
        return new Result(Outcome.CANNOT_TELL,
            "○ TEILWEISE geprüft: bitte die Prüfung auch auf den anderen Bildschirmen dieses "
                + "Testfalls starten. " + r.decided + " von " + r.total + " Angabe(n) konnten "
                + "hier entschieden werden und sind eindeutig, " + r.notTested + " kommen auf "
                + "dieser Seite nicht vor und wurden damit NICHT geprüft.",
            output, receipt, List.of(), 0);
    }

    private static Result plain(Outcome outcome, String message, String detail) {
        return new Result(outcome, message, detail, null, List.of(), 0);
    }

    // ------------------------------------------------------------------ the receipt

    /**
     * The probe's own answer, read back from the file it wrote.
     *
     * <p>Read from the receipt rather than scraped out of the console text on purpose: the
     * numbers are the thing the sentence turns on, and a report format is allowed to change
     * wording in a way a machine-readable file is not.
     */
    private record Receipt(int total, int decided, int notTested, int silent,
            List<String> ambiguous) {

        /** @return the parsed receipt, or {@code null} when there is not one to trust */
        static Receipt read(Path file) {
            if (file == null || !Files.isRegularFile(file)) {
                return null;
            }
            try {
                Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (!(root instanceof Map<?, ?> map)) {
                    return null;
                }
                if (!(map.get("summary") instanceof Map<?, ?> summary)) {
                    return null;
                }
                List<String> names = new ArrayList<>();
                if (map.get("results") instanceof List<?> results) {
                    // Silent first: it is the one the engine will never raise, so it is the one
                    // the tester must see even when the sentence only has room for three.
                    addAmbiguous(results, names, true);
                    addAmbiguous(results, names, false);
                }
                return new Receipt(
                    number(summary.get("total")),
                    number(summary.get("decided")),
                    number(summary.get("notTested")),
                    number(summary.get("silent")),
                    List.copyOf(names));
            } catch (IOException | RuntimeException ex) {
                // An unreadable receipt costs detail in a sentence, never a verdict: the exit
                // code already decided that, and it is the probe's, not ours.
                return null;
            }
        }

        private static void addAmbiguous(List<?> results, List<String> into, boolean silent) {
            for (Object item : results) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                String verdict = String.valueOf(row.get("verdict"));
                if (!"AMBIGUOUS".equals(verdict) && !"AMBIGUOUS_FRAME".equals(verdict)) {
                    continue;
                }
                if (Boolean.TRUE.equals(row.get("silentAtReplay")) != silent) {
                    continue;
                }
                String name = String.valueOf(row.get("element"));
                int count = number(row.get("count"));
                into.add(count > 0 ? name + " (" + count + "×)" : name);
            }
        }

        private static int number(Object value) {
            return value instanceof Number n ? n.intValue() : 0;
        }
    }

    /**
     * The receipt this attempt wrote, or {@code null}.
     *
     * <p>Checked against the moment the probe was started rather than merely for existence: a
     * receipt left by yesterday's check describes yesterday's page, and reading it as this
     * attempt's answer would put a stale verdict on screen under a fresh timestamp.
     */
    private static Path written(Path receipt, long startedAt) {
        try {
            if (!Files.isRegularFile(receipt)) {
                return null;
            }
            return Files.getLastModifiedTime(receipt).toMillis() >= startedAt - 1000
                ? receipt : null;
        } catch (IOException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------------- plumbing

    private static String node() {
        String explicit = System.getenv(AdoCache.ENV_NODE);
        return explicit == null || explicit.isBlank() ? "node" : explicit.trim();
    }

    private static String lastLine(String output) {
        if (output == null || output.isBlank()) {
            return "keine Ausgabe";
        }
        String[] lines = output.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].trim();
            }
        }
        return "keine Ausgabe";
    }

    /**
     * Runs the probe to completion, capturing stdout and stderr together — the {@code CANNOT
     * TELL} explanations are printed on stderr and are the most important thing this method
     * carries back.
     *
     * @return the exit code, or {@code -1} when it timed out or was interrupted
     * @throws IOException when the child could not be started at all, which on this device means
     *     Node is missing and is reported as such rather than as a failure of the recording
     */
    private static int run(Path workingDir, List<String> command, StringBuilder capture)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null && Files.isDirectory(workingDir)) {
            pb.directory(workingDir.toFile());
        }
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        Thread drain = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                capture.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Losing the log only costs detail in the message.
            }
        }, "selector-uniqueness-output");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return -1;
            }
            drain.join(2000);
            return proc.exitValue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
