import de.ing.qa.ado.AdoCache;
import de.ing.qa.ado.AdoTestCase;
import de.ing.qa.ado.Json;
import de.ing.qa.studio.SelectedTestCase;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The guard on {@code selected-testcase.json}: every key one side writes, the other side
 * reads — or the disagreement is declared here, in writing, and this harness says so.
 *
 * <pre>
 *   bash ingenious-plugin/harness/run-selection-contract-harness.sh
 * </pre>
 *
 * <p><b>The defect it exists for.</b> {@code SelectedTestCase.read()} has always read a
 * {@code "startUrl"} key. {@code AdoCache.writeSelection()} — the only production writer, the
 * one the chooser panel calls — has never written one; it writes {@code "url"}, which is
 * something else entirely (the ADO work item's browser link). So the four-argument
 * {@code RecordingTarget} branch in {@code AdoRecordingTarget} was unreachable on every tester
 * machine for its whole life, while its javadoc said it "removes that as a blocker for
 * testers". A reader with no writer produces a feature that silently never works, and
 * everything looks fine.
 *
 * <p><b>Why no existing harness caught it — and this is the transferable part.</b> Three
 * harnesses exercise the selection file, and all three <em>hand-roll the JSON</em>:
 * {@code ChainHarness.selectionJson()}, {@code RecordingTargetHarness.chosen()},
 * {@code unreadable/StartAddressHarness.writeSelection()}. A fixture that fabricates a file
 * format proves the reader works against a file the writer does not produce. It cannot fail
 * when the two drift, because the writer is not in the test. **A fixture that hand-rolls a
 * file format hides writer/reader drift by construction.** This harness is the opposite: it
 * calls the real writer and the real reader, and nothing in between.
 *
 * <p><b>How it stays honest as the record changes.</b> The components are enumerated by
 * reflection over {@link SelectedTestCase}, not by a list somebody has to remember to extend.
 * A new component with no verdict in {@link #CONTRACT} is itself a failure — so the contract
 * cannot be silently outgrown. Same on the other side: a key the writer emits that no
 * component names must appear in {@link #WRITE_ONLY} with a reason.
 *
 * <p><b>Proven to go red, in all three directions</b> (2026-07-28, mutation-tested against a
 * throwaway build from {@code git archive HEAD}; a check that can only come out one way proves
 * nothing, and this suite has already found eight of those):
 *
 * <ol>
 *   <li>writer starts emitting {@code startUrl} → 2 failures, and the message tells the author
 *       to correct the javadocs that say it cannot happen;
 *   <li>writer emits an undeclared key → 1 failure naming the key;
 *   <li>{@link SelectedTestCase} grows a fifth component → 1 failure, "undeclared".
 * </ol>
 *
 * <p>Exit 0 green, 1 red, 2 it could not run (which is never green).
 */
public class SelectionContractHarness {

    /** What the two sides are declared to agree on, per {@link SelectedTestCase} component. */
    private enum Verdict {
        /** The writer emits it and the reader gets the value back. Anything else is a bug. */
        ROUND_TRIP,
        /**
         * The reader reads it, nothing writes it, and that is a known, documented state —
         * not an accident. Asserted to stay empty, so wiring a writer turns this harness red
         * and forces the javadocs that describe the feature to be corrected in the same
         * commit. That is the whole point: the last time this drifted, nothing went red.
         */
        DORMANT
    }

    private static final Map<String, Verdict> CONTRACT = new LinkedHashMap<>();

    static {
        CONTRACT.put("adoId", Verdict.ROUND_TRIP);
        CONTRACT.put("title", Verdict.ROUND_TRIP);
        CONTRACT.put("suiteName", Verdict.ROUND_TRIP);
        // No production source for a per-case recorder address exists: ADO returns none, and
        // the only URL on AdoTestCase is the work item's own link. See AdoRecordingTarget's
        // class javadoc for what would have to change. Until then: dormant, and declared.
        CONTRACT.put("startUrl", Verdict.DORMANT);
    }

    /**
     * Keys the writer emits that no reader here consumes. Legitimate — a selection file is a
     * file format, and provenance is worth writing down — but each one is named on purpose,
     * so a key that appears by accident is caught rather than absorbed.
     */
    private static final Map<String, String> WRITE_ONLY = Map.of(
        "_note", "provenance for a human opening the file by hand",
        "url", "the ADO work item's browser link; consumed by tools outside this plugin",
        "chosenAt", "when the tester took the case; diagnostics only",
        "source", "which panel wrote the file; diagnostics only");

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        String env = System.getenv(AdoCache.ENV_SELECTION);
        if (env == null || env.isBlank()) {
            System.out.println("!! " + AdoCache.ENV_SELECTION + " is not set — refusing to run"
                + " against a tester's real selection file.");
            System.exit(2);
        }
        Path selection = Path.of(env);
        Files.deleteIfExists(selection);

        System.out.println("== the selection file's writer and reader agree ==");
        System.out.println("   file: " + selection);

        // Neutral values. Nothing here may name a real environment or a real customer.
        AdoTestCase sample = new AdoTestCase(
            "3951650",
            "Partner-Suche + Kunde-360",
            "Set1",
            "Ready",
            "Passed",
            "Beschreibung",
            "Voraussetzungen",
            "Custom.Voraussetzungen",
            List.of("Schritt 1", "Schritt 2"),
            "https://dev.azure.com/example/Example/_workitems/edit/3951650",
            AdoTestCase.UrlSource.ADO);

        // THE REAL WRITER. Not a fixture — that is the entire reason this file exists.
        Path written = AdoCache.writeSelection(sample);
        check("the writer wrote a file", Files.isRegularFile(written), written.toString());

        Object root = Json.parse(Files.readString(written, StandardCharsets.UTF_8));
        if (!(root instanceof Map<?, ?> raw)) {
            System.out.println("!! the writer produced something that is not a JSON object");
            System.exit(1);
        }
        Map<String, Object> onDisk = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) root).entrySet()) {
            onDisk.put(String.valueOf(e.getKey()), e.getValue());
        }
        System.out.println("   keys written: " + new TreeSet<>(onDisk.keySet()));

        // THE REAL READER.
        SelectedTestCase readBack = SelectedTestCase.read();
        check("the reader read it back", readBack != null, String.valueOf(readBack));
        if (readBack == null) {
            done();
        }

        // ---------------------------------------------------------------- reader -> writer
        for (RecordComponent rc : SelectedTestCase.class.getRecordComponents()) {
            String name = rc.getName();
            Verdict verdict = CONTRACT.get(name);
            if (verdict == null) {
                check("component '" + name + "' has a declared verdict in CONTRACT", false,
                    "undeclared — add ROUND_TRIP or DORMANT and say why");
                continue;
            }
            Object value = rc.getAccessor().invoke(readBack);
            String got = value == null ? "" : String.valueOf(value);
            boolean onFile = onDisk.containsKey(name);
            switch (verdict) {
                case ROUND_TRIP -> {
                    check("'" + name + "' is written", onFile, "keys=" + onDisk.keySet());
                    check("'" + name + "' survives the round trip",
                        onFile && got.equals(String.valueOf(onDisk.get(name))),
                        "on disk=" + onDisk.get(name) + " read=" + got);
                }
                case DORMANT -> {
                    // Fails the day a writer appears. Deliberately: the docs that describe
                    // the dormant feature have to be corrected in that same commit.
                    check("'" + name + "' is declared DORMANT and no writer emits it", !onFile,
                        onFile ? "the writer now emits '" + name + "' — a feature just became"
                            + " real. Change the verdict to ROUND_TRIP and correct the javadocs"
                            + " in AdoRecordingTarget and SelectedTestCase, which currently"
                            + " tell the reader this cannot happen."
                            : "absent, as declared");
                    check("'" + name + "' therefore reads empty, never null", got.isEmpty(),
                        "read=" + got);
                }
                default -> throw new IllegalStateException(String.valueOf(verdict));
            }
        }

        // ---------------------------------------------------------------- writer -> reader
        for (String key : onDisk.keySet()) {
            if (CONTRACT.containsKey(key)) {
                continue;
            }
            String why = WRITE_ONLY.get(key);
            check("written key '" + key + "' is declared write-only", why != null,
                why != null ? why
                    : "the writer emits a key nothing reads and nothing declares. Either read"
                        + " it, or name it in WRITE_ONLY with the reason it is written.");
        }

        done();
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println("  " + (ok ? "ok  " : "FAIL") + "   " + what
            + (ok ? "" : System.lineSeparator() + "         " + detail));
    }

    private static void done() {
        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) {
            System.out.println("RESULT: RED — the selection file's two ends disagree.");
        }
        System.exit(failures == 0 ? 0 : 1);
    }
}
