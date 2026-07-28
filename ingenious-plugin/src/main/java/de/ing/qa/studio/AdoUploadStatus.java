package de.ing.qa.studio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the ADO upload is doing, in a form a panel can put on screen.
 *
 * <p><b>Why this exists.</b> {@link AdoUpload} told nobody. It wrote a line to Studio's
 * {@code log.txt} and a file next to the ledger, and that was the whole of it — so the tester
 * handout had to say, in as many words, that the program will not tell you whether your result
 * was recorded and you should go and look in Azure DevOps. For the one step that decides
 * whether a morning's testing counted, that is not a documentation problem, it is a defect.
 *
 * <p><b>Both outcomes, always.</b> Every terminal state is published — {@link State#OK},
 * {@link State#FAILED}, {@link State#SKIPPED}, {@link State#OFF} and {@link State#DRY_RUN}
 * alike. This project's
 * recurring failure is invisible state: a button that worked but said so too quietly, a
 * scheduled task reporting success while running nothing, a record button claiming "started"
 * when it had stopped. Success that is silent is indistinguishable from a feature that never
 * ran, and that is exactly how a working auto-mark came to look broken in
 * <a href="https://github.com/Wladefant/ing-qa-automation/issues/82">#82</a>.
 *
 * <p><b>Late subscribers still see it.</b> Uploads are triggered by {@link AdoRunWatcher} from
 * a daemon thread whenever a run finishes — which may well be while the tester is on another
 * screen, or before the panel that displays this has ever been built. A listener that only ever
 * saw <em>future</em> events would therefore miss the very upload it exists to report. So the
 * last event is retained and {@link #addListener} replays it immediately.
 *
 * <p><b>Threading.</b> Listeners are called on whatever thread the upload runs on — never the
 * event dispatch thread. A Swing listener must marshal:
 *
 * <pre>{@code
 * AdoUploadStatus.addListener(event ->
 *     SwingUtilities.invokeLater(() -> label.setText(event.message())));
 * }</pre>
 *
 * <p>This is the same contract the companion's {@code LoopFacade.confirmOutcome} used, kept
 * deliberately: the two surfaces described the same outcome the same way. A listener that
 * throws is logged and dropped from the notification, never propagated into the uploader — a
 * broken status label must not cost a tester their upload.
 */
public final class AdoUploadStatus {

    private static final Logger LOG = Logger.getLogger(AdoUploadStatus.class.getName());

    /** How many events to keep for a panel built after the fact. A session's worth is plenty. */
    private static final int HISTORY = 50;

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Deque<Event> HISTORY_LOG = new ArrayDeque<>();

    private AdoUploadStatus() {
    }

    /**
     * Where one upload has got to.
     *
     * <p>{@link #RUNNING} is not terminal; every other value is. They are kept apart rather
     * than folded into a boolean because "off", "failed" and "there was nothing to upload"
     * must never look alike to a tester — that distinction is the entire point of this class.
     *
     * <p>There is exactly one value per status code {@code ado-upload.mjs} can print on its
     * last stdout line ({@code OK}, {@code FEHLER}, {@code AUS}, {@code UEBERSPRUNGEN},
     * {@code PROBELAUF}). That correspondence is deliberate and load-bearing: while it held
     * for four of the five and not the fifth, a {@code --dry-run} — which touches nothing at
     * all — painted the tester's panel red and told them their morning's work had not reached
     * Azure DevOps. The same disease as a silent success, only inverted: the screen not
     * matching reality. A code with no value here is a defect in this enum, not a state a
     * tester should be shown.
     *
     * <p>{@link #SIGN_IN_REQUIRED} is the one value with no status code, and deliberately so:
     * it is decided before {@code ado-upload.mjs} is started, by asking whether the tester is
     * signed in at all.
     */
    public enum State {
        /** The upload has started. Exactly one terminal event follows. */
        RUNNING,
        /** The evidence reached Azure DevOps. */
        OK,
        /** It did not, and the tester's result is not recorded there. */
        FAILED,
        /** Nothing to upload: the test case carries no ADO number. */
        SKIPPED,
        /** Uploading is switched off ({@code ING_ADO_UPLOAD=0}). Not an error, not a success. */
        OFF,
        /**
         * A rehearsal ({@code --dry-run}): everything ran except the writing. Nothing reached
         * Azure DevOps, and nothing was meant to.
         *
         * <p>Neither {@link #OK} nor {@link #FAILED}, and it must be shown as neither.
         * {@code ado-automark --dry-run} invents run id {@code 99999}, so calling it a success
         * would put a convincing fake run id in front of a tester — which is why
         * {@code ado-upload.mjs} labels it {@code PROBELAUF} rather than {@code OK}. Calling it
         * a failure is the mistake this value exists to end: nothing was attempted, so nothing
         * failed, and a tester told "your result is NOT in Azure DevOps — report this" has been
         * sent to fetch an engineer over a run that was never supposed to write anything.
         */
        DRY_RUN,
        /**
         * The upload is waiting for the tester to sign in to Azure DevOps.
         *
         * <p>The one state that is not decided by {@code ado-upload.mjs}, and the only one with
         * no status code in {@link #of}: it is established by {@link AdoSignIn} <em>before</em>
         * the tool is started at all. That is the whole point of
         * <a href="https://github.com/Wladefant/ing-qa-automation/issues/128">#128</a> — an
         * expired Entra token used to be discovered five minutes into a progress message,
         * because the {@code az login} that would have fixed it printed into a pipe nobody
         * reads.
         *
         * <p>Counted as terminal, and it is not a lie: the upload has stopped and is waiting on
         * a person, so a panel must stop showing progress. When the tester completes the
         * sign-in, a fresh {@link #RUNNING} follows and the upload goes on. It is neither
         * {@link #OK} nor {@link #FAILED}: nothing reached Azure DevOps, and nothing failed —
         * somebody was simply asked a question.
         *
         * <p><b>Only the question, never the refusal.</b> The sign-in step has two outcomes and
         * {@link AdoUpload} published this value for both of them until 2026-07-28 — the second
         * being <em>"Anmeldung nicht abgeschlossen … Es wurde NICHTS nach Azure DevOps
         * hochgeladen"</em>, which is the end of the upload and not a wait. One state cannot be
         * rendered two ways, so the give-up landed in the amber "bitte im geöffneten Fenster
         * anmelden" and a tester whose result had been dropped was told to keep waiting. A
         * panel could only have separated them by reading the German prose, which is precisely
         * what {@link #of} refuses to do; so the give-up now publishes {@link #FAILED} and this
         * value means one thing again. Proved by {@code harness/unreadable/SignInStateHarness}.
         */
        SIGN_IN_REQUIRED,

        /**
         * There is no finished run to upload, so nothing is in Azure DevOps and nothing is on
         * its way there.
         *
         * <p>Published by {@link AdoSubmission} when the tester presses <em>Aufnahme abgeben</em>
         * and no run directory on this machine belongs to the test case they took on. A
         * recording that was never run is the ordinary way to arrive here — the tester skipped
         * <em>"Einmal laufen lassen"</em> — and it is the one state on this list a tester fixes
         * alone, with one keystroke.
         *
         * <p><b>Neither a failure nor a skip, and it must be shown as neither.</b> Failure sends
         * them to fetch an engineer for something they can do themselves. {@link #SKIPPED} means
         * the opposite thing — the case carries no ADO number, so nothing was ever going to be
         * uploaded and there is nothing to do. Here there <em>is</em> something to do and it is
         * small, so the message says what: run it, then press abgeben again.
         *
         * <p>It carries no status code in {@link #of} because {@code ado-upload.mjs} is never
         * started: there would be nothing to point {@code --evidence} at. The upload is not
         * switched off, has not failed and is not waiting on anybody — it has no subject yet.
         */
        NO_RUN;

        /**
         * The state carried by one of {@link AdoUpload#statusLine}'s German status lines.
         *
         * <p>Matched on the status <em>code</em> {@code ado-upload.mjs} prints, not on the
         * German prose after it — the prose is free text that may quote another code back
         * ({@code UEBERSPRUNGEN}'s message contains the words "ADO-Upload übersprungen"), and
         * a bucket decided by free text is a bucket that changes when someone rewords a
         * sentence.
         *
         * <p>{@code AN} is not listed: it is what {@code --state} prints to say uploading is
         * switched on, never the outcome of an upload, and no caller here passes {@code
         * --state}. Should one ever start to, it lands in {@link #FAILED} with the rest of the
         * unrecognised — the safe direction, since it is not an outcome at all.
         *
         * @param statusLine the line, e.g. {@code "ADO-Upload OK — …"}
         * @return the state, defaulting to {@link #FAILED} for anything unrecognised, because
         *     an upload whose outcome cannot be read is not one a tester should trust
         */
        public static State of(String statusLine) {
            String s = statusLine == null ? "" : statusLine.trim();
            String prefix = "ADO-Upload ";
            if (!s.startsWith(prefix)) {
                return FAILED;
            }
            String rest = s.substring(prefix.length()).trim();
            int end = rest.indexOf(' ');
            String code = end < 0 ? rest : rest.substring(0, end);
            return switch (code) {
                case "OK" -> OK;
                case "AUS" -> OFF;
                case "ÜBERSPRUNGEN", "UEBERSPRUNGEN" -> SKIPPED;
                case "PROBELAUF" -> DRY_RUN;
                default -> FAILED;
            };
        }

        /** Whether this state ends an upload, so a panel knows to stop showing progress. */
        public boolean isTerminal() {
            return this != RUNNING;
        }

        /** Whether the tester's evidence is now in Azure DevOps. */
        public boolean isSuccess() {
            return this == OK;
        }
    }

    /**
     * One thing that happened to one upload.
     *
     * @param adoId the Azure DevOps test case id, or {@code null} when none could be established
     * @param testCaseName the INGenious test case name, or {@code null} when unknown
     * @param state where the upload has got to
     * @param message the German one-liner, ready to put on screen unchanged
     * @param at {@code System.currentTimeMillis()} when it happened
     */
    public record Event(String adoId, String testCaseName, State state, String message, long at) {
    }

    /** Told when an upload starts, and again when it ends. */
    @FunctionalInterface
    public interface Listener {
        /**
         * @param event what happened; never {@code null}
         */
        void onAdoUpload(Event event);
    }

    /**
     * Subscribes, and immediately replays the most recent event if there is one.
     *
     * <p>The replay is the point: a panel built after a run finished would otherwise show
     * nothing at all about an upload that had already happened, which is the invisible state
     * this class exists to remove.
     *
     * @param listener the listener; {@code null} is ignored
     */
    public static void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        LISTENERS.add(listener);
        Event last = last();
        if (last != null) {
            deliver(listener, last);
        }
    }

    /**
     * @param listener the listener to stop notifying; {@code null} or unknown is ignored
     */
    public static void removeListener(Listener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    /**
     * @return the most recent event, or {@code null} when no upload has been attempted yet
     */
    public static synchronized Event last() {
        return HISTORY_LOG.peekLast();
    }

    /**
     * @return every retained event, oldest first — for a panel that shows a session's history;
     *     never {@code null}
     *
     * @deprecated <b>Zero callers as of 2026-07-28</b>, verified by exact-string search over
     *     the whole repository including {@code ::} method-reference form. Its sibling
     *     {@link #last()} is read; this one never acquired the panel it was written for. It is
     *     the inverse of the {@code startUrl} defect one package over — a writer with no
     *     reader — and the cost is not the method, it is {@link #HISTORY}: {@code publish()}
     *     keeps and trims a 50-event deque for a consumer that does not exist, where a single
     *     field would do. Kept rather than removed (the audit's rule (P): merely unused, and a
     *     history panel is a real want), but marked so the next reader is not misled into
     *     thinking something depends on it. Removing it should take {@code HISTORY} and the
     *     deque with it.
     */
    @Deprecated
    public static synchronized List<Event> recent() {
        return new ArrayList<>(HISTORY_LOG);
    }

    /** Forgets everything. For tests and harnesses; a Studio session never needs it. */
    public static synchronized void reset() {
        HISTORY_LOG.clear();
    }

    // ------------------------------------------------------------------ published by AdoUpload

    /**
     * Records and announces one event. Package-private, and with a line drawn through this
     * package that is worth stating: <b>{@link AdoUpload} publishes every upload that happens,
     * and {@link AdoSubmission} publishes only the ones that do not.</b>
     *
     * <p>The rule used to be "AdoUpload is the only publisher", for the reason that a second one
     * would let the screen disagree with the ledger. That reason is intact and so is the rule
     * behind it — {@code AdoSubmission} never publishes an outcome of an upload. When it decides
     * a run must be uploaded it hands the run to {@link AdoUpload#forRun} and stays silent
     * throughout, so {@link State#RUNNING} and the terminal state both come from the one place
     * that watched the child process. What it publishes instead are the states in which
     * <em>nothing was started</em>: the evidence is already in Azure DevOps and the receipt says
     * so ({@link State#OK}), there is no run to upload ({@link State#NO_RUN}), the last attempt
     * settled the question by itself ({@link State#SKIPPED}, {@link State#DRY_RUN}), or another
     * thread holds the upload ({@link State#RUNNING}). None of those is an event {@code
     * AdoUpload} could ever emit, because in none of them does it run.
     */
    static Event publish(String adoId, String testCaseName, State state, String message) {
        Event event = new Event(adoId, testCaseName, state,
            message == null ? "" : message, System.currentTimeMillis());
        synchronized (AdoUploadStatus.class) {
            HISTORY_LOG.addLast(event);
            while (HISTORY_LOG.size() > HISTORY) {
                HISTORY_LOG.removeFirst();
            }
        }
        for (Listener listener : LISTENERS) {
            deliver(listener, event);
        }
        return event;
    }

    private static void deliver(Listener listener, Event event) {
        try {
            listener.onAdoUpload(event);
        } catch (RuntimeException | LinkageError ex) {
            // A status label that throws must not cost a tester their upload.
            LOG.log(Level.WARNING, "ADO upload listener failed: " + ex, ex);
        }
    }
}
