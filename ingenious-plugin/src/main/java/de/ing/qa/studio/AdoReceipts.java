package de.ing.qa.studio;

import de.ing.qa.ado.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Reads back what {@code ing-qa-recorder/mvp/ado-upload.mjs} already wrote down.
 *
 * <p><b>Why this exists.</b> {@link AdoSubmission} has to answer one question before it may do
 * anything — <em>is this run already in Azure DevOps?</em> — and it must answer it after a
 * Studio restart, when nothing is left in memory. The answer is on disk and has been all along:
 * every attempt writes {@code ado-upload-TC&lt;id&gt;-&lt;stamp&gt;.json} next to the
 * append-only ledger {@code ado-upload.log}. This class reads that, and invents no state of its
 * own. Inventing state is how a second upload gets started.
 *
 * <p><b>The key is the evidence folder, not the test case.</b> The receipt carries
 * {@code evidenceFolder}, which for a Studio upload is the INGenious run directory verbatim
 * ({@code AdoUpload.upload} passes {@code --evidence <runDir>}). Matching on that answers "this
 * run" rather than "this case" — and it has to, because a tester who runs the same case twice
 * has two runs and expects two results. Keying on the test case id would have made the second
 * run look already-published and silently dropped it.
 *
 * <p><b>The ledger is deliberately not read.</b> {@code ado-upload.log} is one tab-separated
 * line per attempt — {@code at, status, TC-<id>, runId, message} — and it has no
 * {@code evidenceFolder} column, so it cannot say <em>which run</em> a line is about. It stays
 * what it was built to be: the thing a human points at. The JSON receipts are what a program
 * reads.
 *
 * <p>Never throws. A receipts directory that does not exist, a half-written JSON, a file
 * somebody edited — all mean "nothing known about this run", which sends the caller down the
 * upload path. That is the safe direction: a missing receipt costs at worst one repeated
 * upload attempt that the tool itself then reports on, whereas a receipt invented here would
 * cost a result that never reaches Azure DevOps and says it did.
 */
public final class AdoReceipts {

    private static final Logger LOG = Logger.getLogger(AdoReceipts.class.getName());

    /** The prefix {@code writeReceipt()} gives every file it writes. */
    static final String PREFIX = "ado-upload-TC";

    private AdoReceipts() {
    }

    /**
     * One attempt, as {@code ado-upload.mjs} recorded it.
     *
     * @param status the tool's own code — {@code OK}, {@code FEHLER}, {@code AUS},
     *     {@code UEBERSPRUNGEN} or {@code PROBELAUF}; never translated here, because the one
     *     place that turns a code into a state for the screen is
     *     {@link AdoUploadStatus.State#of}
     * @param adoId the test case the attempt was for
     * @param runId the Azure DevOps test run it created, or {@code null} when it created none
     * @param runUrl the browser link to that run, or {@code null}
     * @param message the tool's German one-liner, ready for the screen
     * @param at the ISO timestamp the tool stamped it with
     * @param evidenceFolder the INGenious run directory the evidence came from, or {@code null}
     * @param file the receipt itself, for a tooltip and for whoever has to diagnose it
     */
    public record Receipt(String status, String adoId, String runId, String runUrl,
                          String message, String at, Path evidenceFolder, Path file) {

        /** Whether this attempt actually created a run in Azure DevOps. */
        public boolean reachedAdo() {
            return "OK".equals(status);
        }
    }

    /**
     * The newest receipt written for one INGenious run directory.
     *
     * <p>Newest by the tool's own {@code at} stamp, which is ISO 8601 and therefore sorts
     * lexically; the file name is the tie-break, and it carries the same stamp. A run that was
     * attempted, failed, and attempted again must be judged by its second attempt.
     *
     * @param evidenceFolder the run directory, as handed to {@code --evidence}
     * @param adoId the test case id, used only to narrow the file listing; {@code null} scans
     *     every receipt, which is correct but slower
     * @return the receipt, or {@code null} when no attempt was ever recorded for this run
     */
    public static Receipt newestFor(Path evidenceFolder, String adoId) {
        if (evidenceFolder == null) {
            return null;
        }
        Path wanted = normalise(evidenceFolder);
        Path dir = AdoUpload.logsDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        String prefix = adoId == null || adoId.isBlank() ? PREFIX : PREFIX + adoId.trim() + "-";
        List<Path> candidates;
        try (Stream<Path> files = Files.list(dir)) {
            candidates = files
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().startsWith(prefix))
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .toList();
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.WARNING, "Could not list " + dir + ": " + ex.getMessage());
            return null;
        }

        Receipt newest = null;
        for (Path file : candidates) {
            Receipt receipt = read(file);
            if (receipt == null || receipt.evidenceFolder() == null) {
                continue;
            }
            if (!wanted.equals(normalise(receipt.evidenceFolder()))) {
                continue;
            }
            if (newest == null || compare(receipt, newest) > 0) {
                newest = receipt;
            }
        }
        return newest;
    }

    /**
     * @param file one {@code ado-upload-TC*.json}
     * @return the receipt, or {@code null} when the file cannot be read as one
     */
    static Receipt read(Path file) {
        try {
            Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> map)) {
                return null;
            }
            String folder = str(map.get("evidenceFolder"));
            return new Receipt(
                str(map.get("status")),
                str(map.get("adoId")),
                blankToNull(str(map.get("runId"))),
                blankToNull(str(map.get("runUrl"))),
                str(map.get("message")),
                str(map.get("at")),
                folder.isBlank() ? null : Paths.get(folder),
                file);
        } catch (IOException | RuntimeException ex) {
            // A receipt that cannot be read is one we do not have. Said at FINE and not
            // WARNING: ado-upload.mjs writes these while it runs, so a half-written file is a
            // normal race rather than news.
            LOG.log(Level.FINE, "Receipt unreadable: " + file + " (" + ex + ")");
            return null;
        }
    }

    private static int compare(Receipt a, Receipt b) {
        int byStamp = a.at().compareTo(b.at());
        return byStamp != 0 ? byStamp
            : a.file().getFileName().toString().compareTo(b.file().getFileName().toString());
    }

    /** Absolute and without {@code .} or {@code ..}, so two spellings of one path compare equal. */
    private static Path normalise(Path path) {
        try {
            return path.toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            return path;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String str(Object value) {
        if (value == null) {
            return "";
        }
        // Json parses unquoted numbers as Double; a run id must not come back as "25518817.0".
        if (value instanceof Double d && d == Math.floor(d) && !d.isInfinite()) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }
}
