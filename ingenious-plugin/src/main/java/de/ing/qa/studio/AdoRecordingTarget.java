package de.ing.qa.studio;

import com.ing.ingenious.api.contract.ui.RecordingTarget;
import com.ing.ingenious.api.contract.ui.RecordingTargetApi;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Answers Studio's "where does this recording go?" with the ADO case the tester already chose.
 *
 * <p>Without this the tester picks twice: once in <em>Testfall wählen</em>, and again in the
 * recorder's target chooser — where nothing stops the two disagreeing. Here the second question
 * is not asked, and the recorded steps land in a test case whose name carries the ADO id
 * ({@link AdoNaming}), so a later run can be published back to the case it came from.
 *
 * <p>Nothing is cached. Studio calls this each time a recording starts, and each call re-reads
 * {@code selected-testcase.json}, so switching cases in the panel takes effect on the very next
 * recording with no notification, no listener and nothing to invalidate.
 *
 * <p>With no case chosen this returns {@code null} and the stock chooser opens — so installing
 * this plugin never takes the normal recorder flow away from anyone.
 *
 * <p><b>The recorder's start address, per test case — DORMANT, and this paragraph used to say
 * otherwise.</b> {@code RecordingTarget} carries a {@code startUrl} and
 * {@code TestCaseComponent.resolveRecordingStartUrl} prefers a plugin's value over the project
 * setting, so the mechanism below works. <b>Nothing in production supplies a value for it.</b>
 * The sole writer of the selection file, {@code AdoCache.writeSelection}, emits
 * {@code adoId}, {@code title}, {@code suiteName}, {@code url}, {@code chosenAt} and
 * {@code source} — no {@code startUrl}. The only writers of that key anywhere in the repository
 * are harness fixtures. So {@code selected.startUrl()} is always {@code ""} on a tester's
 * machine, {@code usableStartUrl("")} always returns {@code null}, and the four-argument branch
 * below is unreachable outside {@code ChainHarness}.
 *
 * <p>Until 2026-07-28 this paragraph claimed the opposite — that a per-case address "removes
 * [the missing project setting] as a blocker for testers". It removes nothing, because no
 * screen supplies one. What actually removed that blocker is the panel's project-level start
 * address ({@code GuidedFlowPanel} → {@code StudioRecorder.setProjectStartUrl}, commits
 * 9481cad and f6defba), which superseded this path before it ever acquired a writer. <b>The
 * project-level setting is the whole story for a tester today.</b>
 *
 * <p><b>What it would take to make this real, and why it has not been done.</b> One writer, in
 * {@code AdoCache.writeSelection} — and a source for the value, which is the part that does not
 * exist. ADO hands back no environment or start address for a test case: {@code AdoTestCase}
 * carries exactly one URL, {@code webUrl}, and that is the work item's own browser link
 * ({@code _links.html.href}, or one built from org/project). Writing <em>that</em> into
 * {@code startUrl} would open dev.azure.com in the recorder instead of the application under
 * test. Copying the project-level address into the per-case key would be worse than useless: it
 * would win over the project setting for ever after, so changing the project address would
 * silently stop taking effect for every case chosen before the change. Until a real per-case
 * source exists, this branch stays dormant and says so.
 *
 * <p>The contract between the writer and the reader is guarded by
 * {@code ingenious-plugin/harness/SelectionContractHarness.java}, which drives the real writer
 * and the real reader and fails if either grows a key the other does not have. It fails, by
 * design, on the day someone wires {@code startUrl} without correcting this paragraph.
 *
 * <p>It is also where {@link AdoRunWatcher} is armed. Studio asks this question at the moment
 * the tester presses <em>Aufnahme starten</em>, which is the last point in the guided flow the
 * plugin is told about at all — and it is early enough, because the run whose evidence has to
 * reach ADO cannot happen before the recording does. Arming here needs no change to the panel
 * and no contract that does not already exist.
 */
public class AdoRecordingTarget implements RecordingTargetApi {

    private static final Logger LOG = Logger.getLogger(AdoRecordingTarget.class.getName());

    @Override
    public RecordingTarget getRecordingTarget() {
        // Idempotent, non-blocking, and outside the try: whether a target can be proposed has
        // no bearing on whether a finished run should reach ADO.
        AdoRunWatcher.arm();
        SelectedTestCase selected = SelectedTestCase.read();
        if (selected == null) {
            return null;
        }
        try {
            String scenario = AdoNaming.scenarioName(selected.suiteName());
            String testCase = AdoNaming.testCaseName(selected.adoId(), selected.title());
            String startUrl = usableStartUrl(selected.startUrl());
            if (startUrl == null) {
                // No address of our own: the three-argument constructor leaves startUrl null,
                // and the core falls back to the project-level RecorderSettings. Falling
                // through is the correct answer, never a failure.
                //
                // THIS IS THE ONLY BRANCH PRODUCTION TAKES. Nothing writes the startUrl key
                // (class javadoc), so the line below is the tester's path 100% of the time and
                // the four-argument return is reached only by ChainHarness. Read as "the
                // project setting decides", not as "usually the project setting decides".
                return new RecordingTarget(scenario, testCase);
            }
            LOG.log(Level.INFO, "Recording {0} against {1}", new Object[] { testCase, startUrl });
            return new RecordingTarget(scenario, testCase, false, startUrl);
        } catch (IllegalArgumentException ex) {
            // A selection file with an unusable id. Say so once and let the user choose;
            // silently recording into the wrong place would be worse than the dialog.
            LOG.log(Level.WARNING, "Ignoring unusable test-case selection: " + ex.getMessage());
            return null;
        }
    }

    /**
     * The address this recording should open, or {@code null} to let the project decide.
     *
     * <p><b>In production this is only ever called with {@code ""}</b> and only ever answers
     * {@code null} — see the class javadoc: nothing writes the {@code startUrl} key. It is a
     * validator waiting for a producer, not a feature testers have. Kept because the day a
     * producer exists this is the check that has to be in front of it, and because
     * {@code de.ing.qa.panel.StartAddress.problem} deliberately mirrors it — the screen must
     * refuse exactly what this refuses, or it would name an address the recorder ignores.
     *
     * <p><b>Why this is strict.</b> {@code RecordingTarget} already carries a {@code startUrl}
     * and {@code TestCaseComponent.resolveRecordingStartUrl} prefers a plugin's value over the
     * project setting — so whatever is returned here wins, and a wrong value wins silently. The
     * Beispielanwendung environments differ only by hostname
     * ({@code beispielanwendung-test1}, {@code test4}, {@code qa}, {@code maintenance}, {@code entw1},
     * {@code entw4}), so a tester recording against the wrong one sees a screen that looks
     * exactly right. A stale or mistaken address is therefore worse than no address at all, and
     * anything short of certain falls through to the project setting.
     *
     * <p>What can be checked here is shape, not correctness: an absolute {@code http}/{@code
     * https} URL with a host. That a well-formed address is the <em>right</em> address is not
     * knowable from here, which is why the value taken is also logged — an address that is
     * never shown is one nobody can catch being wrong.
     *
     * @param candidate the value from the selection file, possibly blank or {@code null}
     * @return the address to record against, or {@code null} when there is nothing usable
     */
    static String usableStartUrl(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String trimmed = candidate.trim();
        try {
            java.net.URI uri = new java.net.URI(trimmed);
            String scheme = uri.getScheme();
            boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!uri.isAbsolute() || !web || uri.getHost() == null || uri.getHost().isBlank()) {
                LOG.log(Level.WARNING,
                    "Ignoring an unusable recording start URL, falling back to the project"
                        + " setting: {0}", trimmed);
                return null;
            }
            return trimmed;
        } catch (java.net.URISyntaxException ex) {
            LOG.log(Level.WARNING,
                "Ignoring a malformed recording start URL, falling back to the project"
                    + " setting: " + trimmed);
            return null;
        }
    }
}
