package unreadable;

import static unreadable.PanelHarnessKit.check;
import static unreadable.PanelHarnessKit.noWindows;
import static unreadable.PanelHarnessKit.poll;
import static unreadable.PanelHarnessKit.probeStart;
import static unreadable.PanelHarnessKit.probeState;
import static unreadable.PanelHarnessKit.settle;
import static unreadable.PanelHarnessKit.shoot;

import com.ing.ide.main.mainui.AppMainFrame;
import com.ing.ide.main.mainui.components.testdesign.testcase.TestCaseComponent;
import de.ing.qa.ado.AdoCache;
import de.ing.qa.panel.GuidedFlowPanel;
import java.io.File;
import java.nio.file.Files;
import javax.swing.JComponent;

/**
 * The quiet way the same bug could have come back: a Studio that answers, and says nothing.
 *
 * <p>Nothing is renamed here and nothing throws. {@code isRecording()} is there and returns
 * {@code null} — a boxed return that has not made its mind up. The panel read that answer as
 * {@code Boolean.TRUE.equals(…)}, which turns "no idea" into "no": a live recording was
 * reported as {@code IDLE}, the button offered a start, and the start sent the toggle that
 * ends it. No exception, no missing name, nothing red anywhere — the state simply came out
 * wrong, which is the one shape of this failure that a refusal-on-exception would not have
 * caught.
 *
 * <p>Its own JVM, because its Studio double carries the same class names as the other one's.
 */
public class RecorderNonBooleanHarness {

    public static void main(String[] args) throws Exception {
        PanelHarnessKit.shotDir = new File(args.length > 0 ? args[0] : "target/harness-guided");
        PanelHarnessKit.shotDir.mkdirs();
        System.out.println("=== RecorderNonBooleanHarness: isRecording() antwortet null ===");

        Files.deleteIfExists(AdoCache.selectionPath());
        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = PanelHarnessKit.build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();
        PanelHarnessKit.toStep3(flow, view);

        AppMainFrame studio = new AppMainFrame();
        TestCaseComponent core = studio.getTestDesign().getTestCaseComp();
        core.record();
        core.simulateRecorderReady();
        check("Auf diesem Studio laeuft wirklich eine Aufnahme", core.recordingLive(),
            "recordingLive=" + core.recordingLive());
        int afterSetup = core.recordCalls();
        poll(flow);

        check("Eine Antwort, die kein Ja und kein Nein ist, gilt als UNREADABLE",
            "UNREADABLE".equals(probeState()), probeState());
        check("… und ausdruecklich NICHT als IDLE", !"IDLE".equals(probeState()), probeState());
        check("Die Zeile behauptet nicht, es laufe keine Aufnahme",
            !flow.recorderStateText().contains("Zurzeit läuft keine"), flow.recorderStateText());
        check("Der Knopf ist gesperrt", !flow.recordButtonEnabled(),
            "enabled=" + flow.recordButtonEnabled());
        noWindows("nicht-boolesche Antwort");
        shoot(view, "43-zustand-unlesbar-kein-boolean");

        flow.pressRecord();
        settle();
        String start = probeStart();
        System.out.println("  Startanfrage bei nicht-boolescher Antwort: " + start);
        check("Weder Knopf noch Anfrage rufen record() auf", core.recordCalls() == afterSetup,
            "recordCalls=" + core.recordCalls());
        check("Die Anfrage meldet KEINEN Start", start.startsWith("NEIN|"), start);
        check("Die Aufnahme laeuft unveraendert weiter", core.recordingLive(),
            "recordingLive=" + core.recordingLive());

        // The counter-check, again: this double really can lose the recording.
        core.record();
        settle();
        check("Gegenprobe: record() beendet die Aufnahme auf diesem Studio wirklich",
            !core.recordingLive(), "recordingLive=" + core.recordingLive());

        studio.dispose();
        System.exit(PanelHarnessKit.verdict());
    }
}
