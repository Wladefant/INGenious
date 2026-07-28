package de.ing.qa.studio;

import de.ing.qa.ado.AdoCache;
import de.ing.qa.ado.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The ADO case the tester has taken on, read back out of {@code selected-testcase.json}.
 *
 * <p>{@link AdoCache#readSelectedId()} answers "which id", which is all the overview panel
 * needs. Deriving an INGenious test case name needs the title and the suite as well, so this
 * reads the same file whole. Same file, same writer ({@code TestCaseChooserPanel}), same
 * location ({@link AdoCache#selectionPath()}) — only more of it.
 *
 * <p>The file, not a field in memory, is the channel on purpose. Studio loads the panel JAR
 * once per contribution point, and a plugin cannot rely on two of its own classes sharing
 * statics across those loads. It also means the selection survives a Studio restart, which is
 * what a tester expects of "diesen Testfall übernehmen".
 *
 * <p><b>Three of these four components round-trip; {@code startUrl} does not.</b> The writer
 * emits {@code adoId}, {@code title}, {@code suiteName}, {@code url}, {@code chosenAt} and
 * {@code source}. It has never emitted {@code startUrl}, so that component is always {@code ""}
 * outside a harness — see {@link AdoRecordingTarget} for why there is no source for a per-case
 * address and what would have to change. It is a reader with no writer, which is the quietest
 * kind of broken: everything looks configured and the feature simply never happens.
 *
 * <p>The opposite gap exists too: {@code url} is written into every selection file and read by
 * nobody here. Keep it — it is the ADO work item's browser link, part of a file format other
 * tools may consume — but do <em>not</em> "resolve" the {@code startUrl} gap by pointing this
 * reader at it. It is a dev.azure.com link, and feeding it to the recorder would open the work
 * item instead of the application under test.
 *
 * <p>Both directions are guarded by {@code ingenious-plugin/harness/SelectionContractHarness}:
 * it drives the real writer and this real reader and fails when either grows a key the other
 * lacks, or when a component declared dormant here starts round-tripping.
 */
public record SelectedTestCase(String adoId, String title, String suiteName, String startUrl) {

    /**
     * A selection with no address of its own, which is what every selection is.
     *
     * <p>Kept so adding {@code startUrl} did not change the shape every existing caller
     * constructs — and, since no writer ever appeared, this is also the only shape any
     * production caller has ever needed.
     */
    public SelectedTestCase(String adoId, String title, String suiteName) {
        this(adoId, title, suiteName, null);
    }

    /**
     * Reads the current selection.
     *
     * <p>Never throws: an absent file means nothing has been chosen, and a half-written or
     * damaged one means the same. Both leave the recorder asking the user, which is the safe
     * direction to fail in.
     *
     * @return the selection, or {@code null} when there is none to read
     */
    public static SelectedTestCase read() {
        Path path = AdoCache.selectionPath();
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            Object root = Json.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> map)) {
                return null;
            }
            String id = str(map.get("adoId"));
            if (id.isBlank()) {
                return null;
            }
            // startUrl stays exactly as written: whether it is usable is
            // AdoRecordingTarget's decision, made in one place, not this reader's.
            // "As written" is currently "not at all" — no production writer emits this key.
            // Left in rather than dropped because dropping it would also delete the only
            // place the gap is visible; SelectionContractHarness asserts it stays empty.
            return new SelectedTestCase(id, str(map.get("title")), str(map.get("suiteName")),
                str(map.get("startUrl")));
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return "";
        }
        // Json parses unquoted numbers as Double; an id must not come back as "3951650.0".
        if (value instanceof Double d && d == Math.floor(d) && !d.isInfinite()) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }
}
