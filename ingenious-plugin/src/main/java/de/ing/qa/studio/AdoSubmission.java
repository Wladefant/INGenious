package de.ing.qa.studio;

import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Azure DevOps half of <em>Aufnahme abgeben</em>: what is in test management for this
 * recording, and — when nothing is — the one repair that puts it there.
 *
 * <h2>The question this class answers, and the one it refuses to</h2>
 *
 * <p>Two features were built for the end of a tester's job and never joined up. The upload
 * ({@link AdoUpload} → {@code ado-upload.mjs} → {@code ado-automark.mjs}: a test run, the
 * evidence, the comment, <em>Bestanden</em>) fires by itself the moment a run finishes, from
 * {@link AdoRunWatcher}. The hand-off package ({@code tools/handoff-pack.mjs}) is on the
 * abgeben button and goes tester → automation engineer. So a tester who pressed the one button
 * their handout calls "finished" got a zip and heard nothing at all about the half that decides
 * whether their morning counted. That is this project's oldest failure wearing new clothes: a
 * true thing said too quietly, or in this case said somewhere else.
 *
 * <p><b>Abgeben does not create an Azure DevOps result out of a recording.</b> It is worth
 * being exact about why, because the obvious reading of "one button that finishes the test
 * case" is that the button marks the case Bestanden. A result in a bank's test management is a
 * claim that the test <em>ran and passed</em>, and the evidence for that claim is a run report
 * on disk. A recording is not a run: {@code parse-report.mjs} has nothing to read, there is no
 * outcome to pass, and {@code ado-automark} marks Bestanden and only Bestanden. A button that
 * marked the case anyway would be inventing the one fact the whole chain exists to carry. So
 * when there is no run this class says so, in a sentence that names the keystroke that fixes it
 * ({@link AdoUploadStatus.State#NO_RUN}).
 *
 * <h2>Why the automatic trigger stays, and what the button adds</h2>
 *
 * <p>The tester's documented order is: stop and save, <b>run it once (F6)</b>, look at the
 * result in Azure DevOps, then hand over. By the time abgeben is pressed the upload has
 * therefore already had its chance, and the honest thing for the button to do in the ordinary
 * case is to <em>report</em> — with the run id and the link — rather than to repeat work that is
 * done. Driving the upload from the button instead would move the result minutes later for no
 * gain, would upload only the last of several runs, and would replace a path proven against the
 * real organisation (runs
 * <a href="https://dev.azure.com/beispiel-org/BeispielProjekt/_testManagement/runs?runId=25518817">25518817</a>
 * and 25518995) with an unproven one.
 *
 * <p>What the button adds is the <b>repair</b>, and it is not hypothetical. There are three
 * documented ways for a finished run to leave nothing in Azure DevOps: the watcher was never
 * armed (the tester restarted Studio and pressed F6 without opening the guided flow — the
 * handout says this outright), the Entra sign-in had expired and the tester answered it too
 * late or not at all, or the upload simply failed. In all three the tester's only recovery today
 * is to ring somebody who has a command line. Abgeben is the last thing they do, so it is the
 * right place to notice, and the repair is not a new mechanism: it is {@link AdoUpload#forRun}
 * on the same run directory, the same call the watcher makes.
 *
 * <h2>One press can never produce two runs</h2>
 *
 * <p>Three locks, in the order they are consulted, none of them new state invented here:
 *
 * <ol>
 *   <li><b>The receipt.</b> {@code ado-upload.mjs} writes one JSON per attempt carrying the
 *       {@code evidenceFolder} it uploaded — the run directory itself. If the newest receipt for
 *       this run says {@code OK}, the run is published, and this class reports it and starts
 *       nothing. It survives a Studio restart, which memory does not. See {@link AdoReceipts}.
 *   <li><b>This session's memory.</b> {@link AdoUpload#completedStatus} covers the one case the
 *       receipt cannot: a receipt that could not be written.
 *   <li><b>The claim.</b> {@link AdoUpload#forRun} locks a run directory while it uploads it, so
 *       a press four seconds after a run — inside the watcher's own upload — is refused by the
 *       uploader rather than by a check up here that could be raced.
 * </ol>
 *
 * <p>Two duplicates have already been paid for in this repository (the companion's second
 * trigger, and the engine's {@code Latest} copy counted as a second run). A third would not be
 * bad luck.
 *
 * <h2>What it publishes</h2>
 *
 * <p>Everything, through {@link AdoUploadStatus}, so the panel renders this with the vocabulary
 * and the colours it already has and there is one painter for the ADO line. When it decides to
 * upload it publishes <em>nothing</em> and lets {@link AdoUpload} narrate — see
 * {@code AdoUploadStatus.publish} for where the line between the two publishers runs.
 *
 * <p>Never call {@link #finish()} on the Swing event dispatch thread. In the ordinary case it
 * returns in milliseconds; in the repair case it waits on {@code az} and on Azure DevOps.
 */
public final class AdoSubmission {

    private static final Logger LOG = Logger.getLogger(AdoSubmission.class.getName());

    /** How a run's time is written for a tester — the same one they are asked to quote. */
    private static final String WHEN = "dd.MM.yyyy HH:mm";

    private AdoSubmission() {
    }

    /**
     * What became of the Azure DevOps half of one abgeben press.
     *
     * @param state the state that was published; the panel already renders every value of it
     * @param adoId the test case this is about, or {@code null} when none was taken on
     * @param runDir the run this is about, or {@code null} when there is none
     * @param runId the Azure DevOps test run, read out of the receipt of an upload that had
     *     already happened; {@code null} after an upload this press performed, whose run id
     *     lives in {@link #message} exactly as the uploader worded it and is not re-parsed out
     *     of it here — a number picked out of a sentence is a number that changes when somebody
     *     rewords the sentence
     * @param runUrl the browser link to that run, when the receipt carried one
     * @param uploadedNow whether this press caused the upload — {@code false} means the result
     *     was already there, which is the ordinary case and not a lesser one
     * @param message the German one-liner, already published, ready for a log or a tooltip
     */
    public record Report(AdoUploadStatus.State state, String adoId, Path runDir, String runId,
                         String runUrl, boolean uploadedNow, String message) {

        /** Whether the tester's result is in Azure DevOps now, however it got there. */
        public boolean inAdo() {
            return state == AdoUploadStatus.State.OK;
        }
    }

    /**
     * Makes sure the tester's result is in Azure DevOps, and says what it found or did.
     *
     * <p>Blocking. Never throws: every path returns a {@link Report} and publishes exactly one
     * terminal state, because a half that says nothing is indistinguishable from a half that
     * never ran.
     *
     * @return what is in Azure DevOps for the test case the tester took on, never {@code null}
     */
    public static Report finish() {
        try {
            return submit();
        } catch (RuntimeException | LinkageError ex) {
            // The abgeben press must survive anything this half does. A package the tester can
            // send is worth more than a stack trace they cannot read.
            LOG.log(Level.WARNING, "Submission check failed: " + ex, ex);
            return publish(AdoUploadStatus.State.FAILED, null, null,
                "Es konnte nicht geprüft werden, ob Ihr Ergebnis in Azure DevOps steht ("
                    + ex + "). Bitte bei der Testautomatisierung melden.");
        }
    }

    private static Report submit() {
        SelectedTestCase selection = SelectedTestCase.read();
        String adoId = selection == null ? null : selection.adoId();
        if (adoId == null || adoId.isBlank()) {
            return publish(AdoUploadStatus.State.NO_RUN, null, null,
                "Es ist kein Testfall übernommen — deshalb lässt sich nicht sagen, ob in Azure "
                    + "DevOps ein Ergebnis steht. Bitte einen Testfall übernehmen und danach "
                    + "noch einmal abgeben. Auf das Paket hat das keinen Einfluss.");
        }

        Path runDir = newestRunFor(adoId);
        if (runDir == null) {
            // Not an error and not a skip: the tester left out "einmal laufen lassen", and one
            // keystroke fixes it. The instruction is the message, because a state with no step
            // is a state nobody can act on.
            return publish(AdoUploadStatus.State.NO_RUN, adoId, null,
                "Zu Testfall " + adoId + " gibt es auf diesem Rechner keinen fertigen Lauf — in "
                    + "Azure DevOps steht deshalb kein Ergebnis. Bitte den Testfall einmal "
                    + "laufen lassen (Taste F6) und danach noch einmal abgeben. Das Paket können "
                    + "Sie trotzdem schon abschicken.");
        }
        String when = when(runDir);

        Report known = alreadySettled(runDir, adoId, when);
        if (known != null) {
            return known;
        }

        // Nothing says this run reached Azure DevOps, so it is uploaded — by the same call the
        // watcher makes, on the same directory, with the same rules. Publishing is left entirely
        // to AdoUpload from here on.
        LOG.log(Level.INFO, "abgeben: uploading {0} for test case {1}",
            new Object[] { runDir, adoId });
        List<AdoUpload.Result> results = AdoUpload.forRun(runDir);
        if (results.isEmpty()) {
            // The watcher got there first and is inside its own upload. Saying so is the whole
            // job here; the outcome will arrive on the same line from the thread that owns it.
            return publish(AdoUploadStatus.State.RUNNING, adoId, runDir,
                "ADO-Upload läuft bereits für den Lauf vom " + when + " (Testfall " + adoId
                    + "). Es wurde kein zweiter Upload gestartet.");
        }
        AdoUpload.Result result = results.stream()
            .filter(r -> adoId.equals(r.adoId()))
            .findFirst()
            .orElse(results.get(0));
        return new Report(AdoUploadStatus.State.of(result.status()), adoId, runDir,
            null, null, true, result.status());
    }

    /**
     * Whether this run's question is already answered, and by what.
     *
     * <p>Three of the five recorded outcomes settle it for good and are reported rather than
     * repeated: {@code OK} (it is in Azure DevOps), {@code PROBELAUF} (a rehearsal wrote nothing
     * and was not meant to) and {@code UEBERSPRUNGEN} (the run did not pass, and
     * {@code ado-automark} marks Bestanden and only Bestanden — running it again would produce
     * the same refusal, correctly, and cost a tester five minutes to be told so).
     *
     * <p>The other two do not settle anything and deliberately fall through to the upload.
     * {@code FEHLER} is the case the repair exists for. {@code AUS} is the case that looks
     * settled and is not: {@code ING_ADO_UPLOAD} may have been off during the run and on now,
     * and re-asking costs nothing — {@code ado-upload.mjs} answers {@code AUS} again in
     * milliseconds without touching {@code az} or the network if it is still off.
     *
     * @return the report, or {@code null} when the run must be uploaded
     */
    private static Report alreadySettled(Path runDir, String adoId, String when) {
        AdoReceipts.Receipt receipt = AdoReceipts.newestFor(runDir, adoId);
        if (receipt != null) {
            switch (receipt.status()) {
                case "OK" -> {
                    String run = receipt.runId() == null ? "" : "ADO-Lauf " + receipt.runId() + " ";
                    String link = receipt.runUrl() == null ? "" : " " + receipt.runUrl();
                    return published(AdoUploadStatus.State.OK, adoId, runDir, receipt.runId(),
                        receipt.runUrl(), run + "zum Lauf vom " + when
                            + " — bereits beim Laufende hochgeladen." + link);
                }
                case "PROBELAUF" -> {
                    return publish(AdoUploadStatus.State.DRY_RUN, adoId, runDir,
                        "Lauf vom " + when + ": " + receipt.message());
                }
                case "UEBERSPRUNGEN" -> {
                    return publish(AdoUploadStatus.State.SKIPPED, adoId, runDir,
                        "Lauf vom " + when + ": " + receipt.message());
                }
                default -> {
                    LOG.log(Level.INFO, "abgeben: receipt for {0} says {1} — uploading again",
                        new Object[] { runDir, receipt.status() });
                }
            }
        }

        // No receipt says it is done — but this session might remember doing it, which is the
        // only cover for a receipt that could not be written at all.
        String remembered = AdoUpload.completedStatus(runDir, adoId);
        if (remembered == null) {
            return null;
        }
        AdoUploadStatus.State state = AdoUploadStatus.State.of(remembered);
        if (state != AdoUploadStatus.State.OK && state != AdoUploadStatus.State.DRY_RUN
            && state != AdoUploadStatus.State.SKIPPED) {
            return null;
        }
        return publish(state, adoId, runDir, "Lauf vom " + when
            + " wurde in dieser Sitzung bereits hochgeladen — " + withoutCode(remembered)
            + (receipt == null ? " (kein Beleg auf der Platte; bitte melden.)" : ""));
    }

    // ------------------------------------------------------------------ which run

    /**
     * The newest finished run of one test case, or {@code null}.
     *
     * <p>The test case is recognised the way everything else in this package recognises it —
     * from the directory name, which the engine takes from the INGenious test case name, which
     * {@link AdoNaming} put the ADO id at the front of. A run started from a <em>test set</em>
     * ({@code Results/TestExecution/…}) is therefore not matched: its directory carries the set's
     * name and not the case's, and the only way to know what ran inside it is to read the report
     * with {@code parse-report.mjs}, once per candidate directory, which is a minute of child
     * processes for a button press. The tester's own flow records and runs a single case, so the
     * miss is reported as {@link AdoUploadStatus.State#NO_RUN} — "no run for this case" — rather
     * than guessed at. Guessing which run belongs to which case is the one mistake this whole
     * feature exists to avoid.
     */
    private static Path newestRunFor(String adoId) {
        Path results = AdoRunWatcher.resultsRoot();
        if (results == null) {
            return null;
        }
        return AdoRunWatcher.finishedRuns(results, 0).stream()
            .filter(dir -> adoId.equals(AdoNaming.adoIdFromTestCaseName(caseName(dir))))
            .max(Comparator.comparingLong(AdoRunWatcher::reportStamp))
            .orElse(null);
    }

    /** The test case directory a run sits in: {@code …/<TestCase>/<date time>}. */
    private static String caseName(Path runDir) {
        Path parent = runDir.getParent();
        return parent == null || parent.getFileName() == null
            ? "" : parent.getFileName().toString();
    }

    /** When the run finished, in the tester's own date format. */
    private static String when(Path runDir) {
        long stamp = AdoRunWatcher.reportStamp(runDir);
        return stamp <= 0 ? "unbekannt" : new SimpleDateFormat(WHEN).format(new Date(stamp));
    }

    /** {@code "ADO-Upload OK — ADO-Lauf 1 angelegt"} without the code the panel adds back. */
    private static String withoutCode(String statusLine) {
        int dash = statusLine == null ? -1 : statusLine.indexOf('—');
        return dash < 0 ? String.valueOf(statusLine) : statusLine.substring(dash + 1).trim();
    }

    private static Report publish(AdoUploadStatus.State state, String adoId, Path runDir,
                                  String message) {
        return published(state, adoId, runDir, null, null, message);
    }

    /**
     * Says it on the status line and answers the caller with the same words — one event, one
     * message, no chance of the screen and the return value describing different things.
     */
    private static Report published(AdoUploadStatus.State state, String adoId, Path runDir,
                                    String runId, String runUrl, String message) {
        LOG.log(Level.INFO, "abgeben: {0} — {1}", new Object[] { state, message });
        AdoUploadStatus.publish(adoId, runDir == null ? null : caseName(runDir), state, message);
        return new Report(state, adoId, runDir, runId, runUrl, false, message);
    }
}
