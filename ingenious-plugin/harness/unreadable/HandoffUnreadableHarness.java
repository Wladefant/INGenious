package unreadable;

import static unreadable.PanelHarnessKit.check;
import static unreadable.PanelHarnessKit.noWindows;
import static unreadable.PanelHarnessKit.poll;
import static unreadable.PanelHarnessKit.probeState;
import static unreadable.PanelHarnessKit.settle;
import static unreadable.PanelHarnessKit.shoot;

import com.ing.ide.main.mainui.AppMainFrame;
import com.ing.ide.main.mainui.components.testdesign.testcase.TestCaseComponent;
import de.ing.qa.ado.AdoCache;
import de.ing.qa.panel.GuidedFlowPanel;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import javax.swing.JComponent;

/**
 * What the <em>abgeben</em> button does in front of a Studio whose recording state cannot be
 * read — the same gap as the record button's, one layer over.
 *
 * <p>The lane that split {@code UNKNOWN} into {@code NOT_READY} and {@code UNREADABLE} flagged
 * this rather than fixing it in somebody else's feature: {@code pressHandoffButton} refused
 * packaging for {@code RECORDING} and {@code STARTING} only, so on a Studio it could not read
 * it packaged anyway. That is the worse half of the same fault. A recorder writes steps into
 * {@code TestPlan/} while the tester clicks; a package taken across that is a zip whose
 * manifest hashes describe a moment that has already passed — handed to an engineer under
 * <em>"✔ Fertig: bitte die Datei … senden"</em>. Nobody finds out until the engineer cannot
 * reproduce the run.
 *
 * <p>So the refusal is constructed here, not reasoned about, on the same double the record
 * button's proof uses: {@code isRecording()} is gone from the toolbar, {@code record()} is
 * still the toggle, and a recording is live. The double counts every entry into
 * {@code record()}, so nothing here can quietly end the recording it claims to be protecting.
 *
 * <p><b>The check that keeps the rest honest comes first, not last.</b> Before any Studio
 * exists, the very same button is pressed and really does write a package into the very same
 * folder. Without that, "no package was written" would be a statement about a fixture that
 * could not have produced one anyway — and every refusal below would pass on a broken harness.
 *
 * <pre>
 *   bash ingenious-plugin/harness/unreadable/run-handoff-unreadable-harness.sh
 * </pre>
 */
public class HandoffUnreadableHarness {

    /** Amber on this panel: nothing happened, and that is fine — come back in a moment. */
    private static final String AMBER = "#fff4d8";

    /** Red: nothing happened, and something has to be done about it. */
    private static final String RED = "#fde7e7";

    /** The button's own label, copied like {@code GuidedFlowHarness} does: it is not public. */
    private static final String BTN_HANDOFF = "Aufnahme abgeben";

    public static void main(String[] args) throws Exception {
        PanelHarnessKit.shotDir = new File(args.length > 0 ? args[0] : "target/harness-guided");
        PanelHarnessKit.shotDir.mkdirs();
        System.out.println("=== HandoffUnreadableHarness: Abgabe bei unlesbarem Studio ===");

        Path out = env("ING_HANDOFF_OUT");
        Path project = env("ING_INGENIOUS_PROJECT");

        // --- the two things the runner must have put here, and what it means when it has not.
        //
        // These are PRECONDITIONS, not verdicts. A missing fixture says nothing whatever about
        // the hand-off button, so reporting RED here would be inventing an answer about the
        // product from a fact about the environment — and a red run sends somebody looking for
        // a defect in a Swing panel over an unset variable. UNGEPRUEFT (exit 4) is the sentence
        // that is actually true, and the suite already knows how to read it.
        //
        // The run STOPS here, because every check below is measured against these two: without
        // the folder there is nothing for "genau ein Paket" to be counted in, and thirty
        // downstream failures would bury the one line that says why.
        if (project == null || !Files.isDirectory(project.resolve("TestPlan"))) {
            PanelHarnessKit.unproven("Ein aufgenommenes Projekt liegt bereit",
                "ING_INGENIOUS_PROJECT -> " + project);
            System.exit(PanelHarnessKit.verdict());
        }
        // zips() answers -1 for a folder it cannot look into. "Leer" and "nicht da" are
        // different answers, and only the first one is this harness's premise.
        long before = zips(out);
        if (before < 0) {
            PanelHarnessKit.unproven("Der Abgabe-Ordner ist lesbar",
                "ING_HANDOFF_OUT -> " + out + " (nicht vorhanden oder nicht lesbar)");
            System.exit(PanelHarnessKit.verdict());
        }
        check("Der Abgabe-Ordner ist leer, bevor irgendetwas gedrueckt wurde",
            before == 0, out + " -> " + before);

        Files.deleteIfExists(AdoCache.selectionPath());
        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = PanelHarnessKit.build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();
        PanelHarnessKit.toStep3(flow, view);
        settle();

        // --- the counter-check, before anything can be blamed on the fixture --------------
        // No Studio yet, so nothing forbids packaging: the real tools/handoff-pack.mjs runs and
        // a real zip lands in the folder every refusal below is measured against.
        check("Vor dem ersten Druck ist der Zustand NO_STUDIO", "NO_STUDIO".equals(probeState()),
            probeState());
        flow.pressHandoff();
        check("Die Abgabe hat geantwortet", flow.awaitHandoff(180_000), "gemeldet");
        settle();
        System.out.println("  Abgabe-Zeile ohne Studio: " + flow.handoffStateText());
        check("Gegenprobe: derselbe Knopf erstellt hier wirklich ein Paket",
            flow.handoffStateText().startsWith("✔"), flow.handoffStateText());
        check("… und es liegt genau ein Paket im Ordner", zips(out) == 1, "zips=" + zips(out));
        String packed = flow.handoffZipPath();
        check("… das der Testerin auch genannt wird", packed.endsWith(".zip"), packed);
        noWindows("nach der echten Abgabe");
        shoot(view, "44-abgabe-ohne-studio-gegenprobe");

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

        flow.pressHandoff();
        settle();
        String line = flow.handoffStateText();
        System.out.println("  Abgabe-Zeile bei unlesbarem Zustand: " + line);
        check("Bei unlesbarem Zustand entsteht KEIN zweites Paket", zips(out) == 1,
            "zips=" + zips(out));
        check("… und es wird auch kein neues genannt", packed.equals(flow.handoffZipPath()),
            flow.handoffZipPath());
        check("Es wird kein Erfolg gemeldet", !line.startsWith("✔"), line);
        check("Die Zeile sagt, dass kein Paket erstellt wurde", line.contains("KEIN Paket"), line);
        check("… sie nennt den Grund, statt nur abzulehnen",
            line.contains("meldet den Aufnahme-Zustand nicht"), line);
        check("… sie sagt, wo die Wahrheit steht: an der Schaltflaeche in der Werkzeugleiste",
            line.contains("Werkzeugleiste"), line);
        check("… und wie es danach weitergeht",
            line.contains(BTN_HANDOFF) && line.contains("Testautomatisierung"),
            line);
        // The line clips rather than wraps, so whatever survives a narrow window must be the
        // instruction — the same rule the refusal message on this panel already lives under.
        check("Die Handlungsanweisung steht vorn, nicht hinter der Erklaerung",
            line.indexOf("Werkzeugleiste") < line.indexOf("Studio-Version"),
            line.indexOf("Werkzeugleiste") + " < " + line.indexOf("Studio-Version"));
        check("Rot, nicht bernstein: hier muss die Testerin etwas tun",
            RED.equals(flow.handoffStateColour()), flow.handoffStateColour());
        check("Der technische Grund nennt die fehlende Methode",
            flow.handoffStateTooltip().contains("isRecording"), flow.handoffStateTooltip());
        check("Der Knopf bleibt benutzbar — die Testerin soll es gleich nochmal versuchen",
            flow.handoffButtonEnabled(), "enabled=" + flow.handoffButtonEnabled());
        check("Die Aufnahme laeuft unveraendert weiter", core.recordingLive(),
            "recordingLive=" + core.recordingLive());
        check("Und record() wurde dabei nicht angefasst", core.recordCalls() == afterSetup,
            "recordCalls=" + core.recordCalls());
        noWindows("unlesbarer Zustand");
        shoot(view, "45-abgabe-verweigert-unlesbar");

        // --- a Studio that is merely not finished yet is a different answer --------------
        studio.getTestDesign().setBuilt(false);
        poll(flow);
        check("Ein noch nicht fertiges Studio ist NOT_READY, nicht UNREADABLE",
            "NOT_READY".equals(probeState()), probeState());
        flow.pressHandoff();
        settle();
        String waiting = flow.handoffStateText();
        System.out.println("  Abgabe-Zeile bei NOT_READY: " + waiting);
        check("Auch hier entsteht kein Paket", zips(out) == 1, "zips=" + zips(out));
        check("… und es wird zum Warten geraten, nicht zum Melden",
            waiting.contains("einen Moment warten"), waiting);
        check("… mit dem Knopf, auf den danach zu druecken ist",
            waiting.contains(BTN_HANDOFF), waiting);
        check("Bernstein, nicht rot: es ist gleich vorbei",
            AMBER.equals(flow.handoffStateColour()), flow.handoffStateColour());
        check("Die beiden Faelle sagen ausdruecklich NICHT dasselbe",
            !waiting.equals(line), waiting);
        check("Der Knopf bleibt auch hier benutzbar", flow.handoffButtonEnabled(),
            "enabled=" + flow.handoffButtonEnabled());
        noWindows("Studio im Aufbau");
        shoot(view, "46-abgabe-wartet-not-ready");

        // --- and the record button says the same thing about the same Studio -------------
        // Two buttons that disagree about one Studio are worse than either being wrong.
        studio.getTestDesign().setBuilt(true);
        poll(flow);
        check("Beide Knoepfe sehen denselben Zustand", "UNREADABLE".equals(probeState()),
            probeState());
        check("Der Aufnahme-Knopf ist gesperrt …", !flow.recordButtonEnabled(),
            "enabled=" + flow.recordButtonEnabled());
        // Pressed again on purpose. The recorder line follows the once-a-second poll, the
        // abgeben line reports the last press and nothing else — so it still carries the
        // NOT_READY sentence from a moment ago, which is correct (it is a report, not a state)
        // and would make a comparison against the live line meaningless.
        flow.pressHandoff();
        settle();
        check("… und nach einem frischen Druck nennen beide denselben Grund",
            flow.recorderStateText().contains("meldet den Aufnahme-Zustand nicht")
                && flow.handoffStateText().contains("meldet den Aufnahme-Zustand nicht"),
            flow.handoffStateText());
        check("… ohne dass dabei doch noch ein Paket entsteht", zips(out) == 1,
            "zips=" + zips(out));

        // --- the premise, checked: this recording really was losable ---------------------
        core.record();
        settle();
        check("Gegenprobe: ein record() auf DIESEM Studio beendet die Aufnahme wirklich",
            !core.recordingLive(), "recordingLive=" + core.recordingLive());
        check("… und der Zaehler zeigt genau diesen einen Aufruf",
            core.recordCalls() == afterSetup + 1, "recordCalls=" + core.recordCalls());

        studio.dispose();
        System.exit(PanelHarnessKit.verdict());
    }

    /**
     * How many packages are in the hand-off folder — the count a refusal must not raise.
     *
     * <p><b>A folder that is not there answers {@code -1}, never {@code 0}.</b> Those are two
     * different sentences — "no package was written" and "I looked in the wrong place" — and
     * until 2026-07-28 this method said the first one for both. The first is the PASSING
     * answer of the very first check in {@code main}: <em>"Der Abgabe-Ordner ist leer, bevor
     * irgendetwas gedrueckt wurde"</em> held just as well for an {@code ING_HANDOFF_OUT} that
     * had been misspelt, moved, or never created — and every later {@code == 1} would then
     * fail for a reason nobody would connect to it.
     *
     * <p>{@code -1} points away from every passing answer this harness has ({@code == 0} and
     * {@code == 1} alike), which is the only direction an error return may point. The
     * unreadable {@code Files.list} case already answered {@code -1}; a missing directory is
     * the same class of not-knowing and now says so too.
     */
    private static long zips(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return -1;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".zip")).count();
        } catch (Exception ex) {
            return -1;
        }
    }

    private static Path env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Paths.get(value.trim());
    }
}
