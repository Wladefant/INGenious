package unreadable;

import static unreadable.PanelHarnessKit.check;
import static unreadable.PanelHarnessKit.noWindows;
import static unreadable.PanelHarnessKit.poll;
import static unreadable.PanelHarnessKit.probeStart;
import static unreadable.PanelHarnessKit.probeState;
import static unreadable.PanelHarnessKit.probeStop;
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
 * What the recording button does in front of a Studio whose recording state cannot be read.
 *
 * <p>The documentation lane asked the question this answers. The handout still warns testers
 * not to press <em>Aufnahme starten</em> twice, because {@code record()} is a toggle and the
 * second press used to end the recording under a green banner. That was fixed by making the
 * panel read the state first — and the objection was precise: in the state where the panel
 * <em>cannot</em> read it, the button still said "starten" and a press still went through. A
 * warning cannot be deleted on a proof with a hole in it.
 *
 * <p>So the hole is constructed here rather than reasoned about. The Studio in this JVM is a
 * build whose toolbar getter was renamed: {@code isRecording()} is gone, {@code record()} is
 * the same toggle as ever, and a recording is <em>live</em>. If a press reaches
 * {@code record()}, the recording ends — and the double counts every entry, so a refusal that
 * still called the method cannot pass as one.
 *
 * <p>The last check is the one that keeps the rest honest: it calls {@code record()} directly
 * and shows the recording really does end that way on this double. Without it, "the recording
 * survived" would be a statement about a double that could not have ended it anyway.
 *
 * <pre>
 *   bash ingenious-plugin/harness/unreadable/run-unreadable-harness.sh
 * </pre>
 */
public class RecorderUnreadableHarness {

    public static void main(String[] args) throws Exception {
        PanelHarnessKit.shotDir = new File(args.length > 0 ? args[0] : "target/harness-guided");
        PanelHarnessKit.shotDir.mkdirs();
        System.out.println("=== RecorderUnreadableHarness: Studio mit umbenanntem isRecording() ===");

        Files.deleteIfExists(AdoCache.selectionPath());
        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = PanelHarnessKit.build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();
        PanelHarnessKit.toStep3(flow, view);

        // --- a Studio appears whose state cannot be read, and it IS recording -------------
        AppMainFrame studio = new AppMainFrame();
        TestCaseComponent core = studio.getTestDesign().core();
        core.record();
        core.simulateRecorderReady();
        check("Auf diesem Studio laeuft wirklich eine Aufnahme", core.recordingLive(),
            "recordingLive=" + core.recordingLive());
        int afterSetup = core.recordCalls();
        poll(flow);

        check("Der Zustand ist UNREADABLE, nicht IDLE", "UNREADABLE".equals(probeState()),
            probeState());
        check("Die Zeile sagt, dass diese Studio-Version den Zustand nicht meldet",
            flow.recorderStateText().contains("meldet den Aufnahme-Zustand nicht"),
            flow.recorderStateText());
        check("… und behauptet nicht, es laufe keine Aufnahme",
            !flow.recorderStateText().contains("Zurzeit läuft keine"), flow.recorderStateText());
        check("Der Knopf laesst sich gar nicht erst druecken", !flow.recordButtonEnabled(),
            "enabled=" + flow.recordButtonEnabled());
        // Nothing is pressable here, so no attempt and no banner: the line's own tooltip is the
        // only place the missing name can still be read by whoever the tester rings up.
        check("Der technische Grund nennt die fehlende Methode",
            flow.recorderStateTooltip().contains("isRecording"), flow.recorderStateTooltip());
        // The technical half used to REPLACE the sentence on the tooltip, which was harmless
        // while the line wrapped and is not now that it clips: the clipped-off half has to stay
        // readable somewhere, and this is the only somewhere there is.
        check("… und der ganze Satz steht dort ebenfalls noch",
            flow.recorderStateTooltip().contains("Werkzeugleiste benutzen"),
            flow.recorderStateTooltip());
        // This is the longest line the panel ever paints beside the button, so it is the one
        // that decides whether clipping was the right answer for it. It is not HTML — an HTML
        // label re-wraps in a narrow window while keeping its one-line height and gets sliced
        // through the middle — and what survives the clip is the step, not the diagnosis.
        check("Die Zustands-Zeile ist kein umbrechendes HTML",
            !flow.recorderStateText().contains("<html>"), flow.recorderStateText());
        check("Was ein schmales Fenster uebrig laesst, ist die Anweisung",
            flow.recorderStateText().indexOf("Werkzeugleiste") < 100,
            "at " + flow.recorderStateText().indexOf("Werkzeugleiste"));
        noWindows("unlesbarer Zustand");
        shoot(view, "40-zustand-unlesbar-umbenannt");
        // Narrow, because the claim above is a claim about a width this harness otherwise
        // never runs at. 900 pixels is the Studio window on a laptop screen.
        PanelHarnessKit.resizeTo(900, 780);
        // …and what that render found was worse than a clipped sentence. The button row was a
        // FlowLayout, which gives every component its preferred width, so this line did not fit
        // beside the button, was wrapped onto a second row, and that row lay below the bottom
        // edge of a container measured for one — so the only sentence saying why the recording
        // button is greyed out WAS NOT PAINTED AT ALL. Every string check above passed the
        // whole time. Asking the label where it ended up is the question that can tell the
        // difference; it answered "no" against the FlowLayout and "yes" against the BorderLayout
        // that replaced it.
        check("Die Zustands-Zeile steht im schmalen Fenster wirklich in ihrer Zeile",
            flow.recorderStateLaidOut(), "laidOut=" + flow.recorderStateLaidOut());
        shoot(view, "40b-zustand-unlesbar-schmales-fenster");
        PanelHarnessKit.resizeTo(1500, 950);

        // --- the press the handout warns about ------------------------------------------
        flow.pressRecord();
        settle();
        check("Ein Druck auf den Knopf ruft record() NICHT auf", core.recordCalls() == afterSetup,
            "recordCalls=" + core.recordCalls());
        check("Die Aufnahme laeuft danach unveraendert weiter", core.recordingLive(),
            "recordingLive=" + core.recordingLive());

        // --- and the request the button cannot even send --------------------------------
        String start = probeStart();
        System.out.println("  Startanfrage bei unlesbarem Zustand: " + start);
        check("Eine Startanfrage meldet KEINEN Start", start.startsWith("NEIN|"), start);
        check("… sie nennt den Grund, statt nur abzulehnen",
            start.contains("meldet nicht, ob eine Aufnahme läuft"), start);
        check("… sie sagt, was der Tester stattdessen tun soll",
            start.contains("Werkzeugleiste"), start);
        check("… und ruft record() NICHT auf — sonst haette sie die Aufnahme beendet",
            core.recordCalls() == afterSetup, "recordCalls=" + core.recordCalls());
        check("Die Aufnahme laeuft auch danach weiter", core.recordingLive(),
            "recordingLive=" + core.recordingLive());

        String stop = probeStop();
        check("Eine Stoppanfrage beendet ebenfalls nichts blind",
            stop.startsWith("NEIN|") && core.recordCalls() == afterSetup, stop);
        noWindows("nach den Anfragen");

        // --- a Studio that is merely not finished yet is a different answer --------------
        studio.getTestDesign().setBuilt(false);
        poll(flow);
        check("Ein noch nicht fertiges Studio ist NOT_READY, nicht UNREADABLE",
            "NOT_READY".equals(probeState()), probeState());
        check("Die Zeile sagt, dass es gleich weitergeht",
            flow.recorderStateText().contains("noch nicht so weit"), flow.recorderStateText());
        // The only thing to do here is nothing, for a moment — so that is what stands in front,
        // where a narrow window cannot take it.
        check("… und was zu tun ist, steht vor der Erklaerung",
            flow.recorderStateText().startsWith("Bitte einen Moment warten"),
            flow.recorderStateText());
        check("Der Knopf ist auch hier gesperrt", !flow.recordButtonEnabled(),
            "enabled=" + flow.recordButtonEnabled());
        String whileBuilding = probeStart();
        check("Auch hier wird nichts gestartet",
            whileBuilding.startsWith("NEIN|") && core.recordCalls() == afterSetup, whileBuilding);
        check("… und es wird zum Warten geraten, nicht zum Aufgeben",
            whileBuilding.contains("einen Moment warten"), whileBuilding);
        noWindows("Studio im Aufbau");
        shoot(view, "41-studio-noch-nicht-bereit");

        // …and a getter that exists and throws is the same answer, from the other direction.
        studio.getTestDesign().setBuilt(true);
        studio.getTestDesign().setThrowing(true);
        poll(flow);
        check("Ein Getter, der fliegt, ist ebenfalls NOT_READY",
            "NOT_READY".equals(probeState()), probeState());
        String whileThrowing = probeStart();
        check("… und startet nichts",
            whileThrowing.startsWith("NEIN|") && core.recordCalls() == afterSetup, whileThrowing);
        check("Der Grund nennt die echte Ausnahme, nicht die Reflection-Huelle",
            flow.recorderStateTooltip().contains("IllegalStateException")
                && !flow.recorderStateTooltip().contains("InvocationTargetException"),
            flow.recorderStateTooltip());

        // --- transient means transient: it comes back by itself --------------------------
        studio.getTestDesign().setThrowing(false);
        poll(flow);
        check("Sobald Studio wieder antwortet, folgt der Zustand",
            "UNREADABLE".equals(probeState()), probeState());
        check("Die Aufnahme hat all das ueberlebt", core.recordingLive(),
            "recordingLive=" + core.recordingLive());
        shoot(view, "42-zustand-wieder-unlesbar");

        // --- the check that keeps the others honest --------------------------------------
        // Everything above says "record() was not called, and the recording survived". That is
        // only evidence if calling record() here really would have ended it.
        core.record();
        settle();
        check("Gegenprobe: ein record() auf DIESEM Studio beendet die Aufnahme wirklich",
            !core.recordingLive(), "recordingLive=" + core.recordingLive());
        check("… und der Zaehler zeigt genau diesen einen Aufruf",
            core.recordCalls() == afterSetup + 1, "recordCalls=" + core.recordCalls());

        studio.dispose();
        System.exit(PanelHarnessKit.verdict());
    }
}
