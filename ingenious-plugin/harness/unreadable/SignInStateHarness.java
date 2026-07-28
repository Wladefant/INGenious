package unreadable;

import static unreadable.PanelHarnessKit.check;
import static unreadable.PanelHarnessKit.noWindows;
import static unreadable.PanelHarnessKit.settle;
import static unreadable.PanelHarnessKit.shoot;

import de.ing.qa.ado.AdoCache;
import de.ing.qa.panel.GuidedFlowPanel;
import de.ing.qa.studio.SignInStatusProbe;
import java.io.File;
import java.nio.file.Files;
import javax.swing.JComponent;

/**
 * What the panel says when the upload stops to ask the tester to sign in.
 *
 * <p><b>Why this is its own run.</b> {@link de.ing.qa.studio.AdoUploadStatus.State#SIGN_IN_REQUIRED}
 * is the one upload state with no status code — it is decided before {@code ado-upload.mjs}
 * starts — so {@code State.of} cannot produce it and the existing upload scenarios, which feed
 * the uploader's own stdout, cannot reach it at all. It arrived in {@code showUpload}'s
 * {@code default} arm, which paints red and tells the tester their result is NOT in Azure
 * DevOps. That is the {@code DRY_RUN} defect again with the sign flipped: the upload resumes by
 * itself once they sign in, so red sends somebody to fetch an engineer for a problem that is
 * resolving while they read about it.
 *
 * <p><b>The colour is the assertion, not decoration.</b> A test comparing only the words would
 * pass a regression that printed this state in the failure red — which is precisely the bug
 * being fixed.
 *
 * <p><b>And the other half of the same step.</b> {@code AdoUpload}'s sign-in has two outcomes:
 * the question above, and the sign-in that never came, after which nothing was uploaded and
 * nothing more will be. Both published {@code SIGN_IN_REQUIRED} until 2026-07-28, so the second
 * came out amber, telling a tester whose result had been dropped to wait for a window that had
 * closed. That is fixed at the publisher — the give-up publishes
 * {@link de.ing.qa.studio.AdoUploadStatus.State#FAILED}, pinned against the real
 * {@code AdoUpload} in {@code harness/signin/SignInHarness} — and this run asserts the half
 * that harness cannot reach: on screen it is red, and it is not amber.
 *
 * <pre>
 *   bash ingenious-plugin/harness/unreadable/run-sign-in-state-harness.sh
 * </pre>
 */
public class SignInStateHarness {

    /** The amber family: "nothing has gone wrong, and nothing is finished either". */
    private static final String AMBER = "#fff4d8";
    /** The failure red, asserted as the answer this state must NOT get. */
    private static final String RED = "#fde7e7";

    /**
     * The first of {@code AdoUpload}'s two sign-in messages, quoted from its own composition
     * ({@code "ADO-Anmeldung nötig — …"}), with the id this run uses.
     */
    private static final String ASK =
        "ADO-Anmeldung nötig — Die Anmeldung bei Azure DevOps ist abgelaufen. Es öffnet sich "
            + "dafür ein Fenster; bitte dort anmelden. Danach wird das Ergebnis von Testfall "
            + "3951650 automatisch hochgeladen.";

    /** Its second, published when the sign-in did not happen and the upload gave up. */
    private static final String GAVE_UP =
        "ADO-Anmeldung nicht abgeschlossen — Die Anmeldung wurde nicht innerhalb von 5 Minuten "
            + "abgeschlossen. Es wurde NICHTS nach Azure DevOps hochgeladen. Die Aufnahme liegt "
            + "weiterhin unter <Lauf> und kann nach der Anmeldung erneut hochgeladen werden.";

    public static void main(String[] args) throws Exception {
        PanelHarnessKit.shotDir = new File(args.length > 0 ? args[0] : "target/harness-guided");
        PanelHarnessKit.shotDir.mkdirs();
        System.out.println("=== SignInStateHarness: ADO-Anmeldung noetig, nicht ADO-Fehler ===");

        Files.deleteIfExists(AdoCache.selectionPath());
        SignInStatusProbe.reset();

        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = PanelHarnessKit.build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();
        PanelHarnessKit.toStep3(flow, view);

        // --- the recoverable half: sign in, and the upload goes on by itself ---------------
        SignInStatusProbe.signInRequired("3951650", "TC_3951650", ASK);
        settle();

        check("Die Anmeldung wird als Anmeldung gemeldet, nicht als Fehler",
            flow.uploadStateText().contains("ADO-Anmeldung nötig"), flow.uploadStateText());
        check("… und die Zeile ist bernsteinfarben, nicht rot",
            AMBER.equals(flow.uploadStateColour()), flow.uploadStateColour());
        // The check that makes the one above mean something: this exact colour is the answer a
        // regression would give, so it has to be nameable and it has to be different.
        check("… und ausdruecklich NICHT das Fehler-Rot", !RED.equals(flow.uploadStateColour()),
            flow.uploadStateColour());
        check("… und behauptet NICHT, das Ergebnis stehe nicht in Azure DevOps",
            !flow.uploadStateText().contains("NICHT in Azure DevOps"), flow.uploadStateText());
        check("… und schickt niemanden zur Testautomatisierung",
            !flow.uploadStateText().contains("Testautomatisierung melden"),
            flow.uploadStateText());
        check("Der Text des Produkts steht unveraendert auf der Zeile",
            flow.uploadStateText().contains("automatisch hochgeladen"), flow.uploadStateText());
        check("Das Banner sagt, was zu tun ist, und wo",
            flow.bannerText().contains("Anmeldung bei Azure DevOps nötig")
                && flow.bannerText().contains("geöffneten Fenster"), flow.bannerText());
        noWindows("Anmeldung noetig");
        shoot(view, "60-ado-anmeldung-noetig");

        // --- for contrast, so "amber" is a finding and not the only answer available --------
        // A real failure through the same line must still be red. Without this the amber check
        // above would pass on a panel that painted every upload state amber.
        de.ing.qa.studio.AdoUploadProbe.finished("3951650", "TC_3951650",
            "FEHLER: ado-upload.mjs ist mit Code 1 beendet worden", 1);
        settle();
        check("Gegenprobe: ein echter Fehler ist auf derselben Zeile weiterhin rot",
            RED.equals(flow.uploadStateColour()), flow.uploadStateColour());
        check("… und sagt weiterhin, dass das Ergebnis NICHT in Azure DevOps steht",
            flow.uploadStateText().contains("NICHT in Azure DevOps"), flow.uploadStateText());

        // --- back to amber, so the check below is a movement and not a leftover --------------
        // Without this the red asserted further down would also be produced by a line that had
        // simply stayed red since the Gegenprobe. The line has to be amber immediately before
        // the give-up arrives, or the give-up's colour proves nothing about the give-up.
        SignInStatusProbe.signInRequired("3951650", "TC_3951650", ASK);
        settle();
        check("Vor der Aufgabe steht die Zeile wieder auf Bernstein",
            AMBER.equals(flow.uploadStateColour()), flow.uploadStateColour());

        // --- the sign-in that did not happen, which is the END of the upload ----------------
        // AdoUpload's sign-in step has two outcomes and it published SIGN_IN_REQUIRED for both
        // until 2026-07-28: once to ask — recoverable, the upload resumes by itself — and once
        // to say the sign-in never came and NOTHING was uploaded, which resumes nothing. One
        // state has one arm on the panel, so the second came out in the amber of "bitte im
        // geöffneten Fenster anmelden": the tester's result had been dropped and the screen
        // told them to keep waiting.
        //
        // A panel could only have separated them by reading the German prose, which is what
        // AdoUploadStatus.State.of refuses to do and has already paid for once. So it was fixed
        // at the publisher: the give-up publishes FAILED. THAT the publisher does so is pinned
        // against the real AdoUpload in harness/signin/SignInHarness (scenario
        // upload-signin-required, logged-out fake az); what is pinned HERE is the half that
        // harness cannot reach — what the state looks like on screen.
        SignInStatusProbe.signInGaveUp("3951650", "TC_3951650", GAVE_UP);
        settle();
        check("Die Aufgabe-Meldung erreicht den Bildschirm im Wortlaut des Produkts",
            flow.uploadStateText().contains("NICHTS nach Azure DevOps hochgeladen"),
            flow.uploadStateText());
        // The colour IS the assertion. The words alone would pass on a panel that printed this
        // in the amber of "still working", which is the whole defect.
        check("… und die Zeile ist rot, weil hier nichts mehr passiert",
            RED.equals(flow.uploadStateColour()), flow.uploadStateColour());
        check("… und ausdruecklich NICHT das Bernstein von \"bitte anmelden\"",
            !AMBER.equals(flow.uploadStateColour()), flow.uploadStateColour());
        check("… und sagt, dass das Ergebnis NICHT in Azure DevOps steht",
            flow.uploadStateText().contains("NICHT in Azure DevOps"), flow.uploadStateText());
        check("… und schickt die Testperson zur Testautomatisierung",
            flow.uploadStateText().contains("Testautomatisierung melden"),
            flow.uploadStateText());
        check("Das Banner fordert nicht mehr zum Anmelden auf",
            !flow.bannerText().contains("geöffneten Fenster"), flow.bannerText());
        noWindows("Anmeldung nicht abgeschlossen");
        shoot(view, "61-ado-anmeldung-nicht-abgeschlossen");

        System.exit(PanelHarnessKit.verdict());
    }
}
