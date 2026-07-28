import com.ing.ide.main.mainui.AppMainFrame;
import com.ing.ide.main.mainui.components.testdesign.testcase.TestCaseComponent;
import de.ing.qa.ado.AdoCache;
import de.ing.qa.ado.Json;
import de.ing.qa.panel.GuidedFlowPanel;
import de.ing.qa.panel.RecorderProbe;
import de.ing.qa.studio.AdoRunWatcher;
import de.ing.qa.studio.AdoUpload;
import de.ing.qa.studio.AdoUploadProbe;
import de.ing.qa.studio.CustomerProfile;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/**
 * Proof for the guided flow, driven through the real Swing components: it selects a row,
 * clicks the real buttons, reads the rendered text back, and writes a PNG of every step.
 *
 * <p>Two rules learned on this project the hard way:
 *
 * <ul>
 *   <li><b>Judge from the visible windows, never from a responsive Event Dispatch Thread.</b>
 *       A modal Swing dialog pumps the event queue, so an EDT that still answers proves
 *       nothing. Every step here enumerates {@link Window#getWindows()} and fails if anything
 *       is showing — the panel must never pop a dialog at a tester.
 *   <li><b>The panel is rendered, not merely constructed.</b> The frame is packed but never
 *       shown, and the screenshots come from {@code printAll} — so this can run while somebody
 *       is using the machine, and still proves the layout really paints.
 * </ul>
 *
 * <p>Scenarios (argv[0]): {@code flow} — the whole Testfall → Kunde → Aufnahme run;
 * {@code dirty} — a test-data file with off-shape rows, which must be withheld and counted;
 * {@code nocustomer} — a case the tester declares needs no customer;
 * {@code adostatus} — the ADO upload outcome on screen, including one that finished before the
 * panel existed; {@code wachhund} — the run watcher armed by the constructor alone.
 */
public class GuidedFlowHarness {

    /**
     * The two labels the button carries, spelled out here rather than imported.
     *
     * <p>They are what the tester reads and what the handout quotes, so a test that took
     * them from the panel's own constants would keep passing through a rename that breaks
     * the documentation.
     */
    private static final String BTN_RECORD = "▶  Aufnahme starten";
    private static final String BTN_STOP = "■  Aufnahme beenden";
    private static final String BTN_HANDOFF = "Aufnahme abgeben";

    private static int checks;
    private static int failures;
    /**
     * Checks that could not be run at all.
     *
     * <p>Counted separately and reported separately, because "could not test" is neither a
     * pass nor a failure. The hand-off scenarios need a {@code node} on the machine; without
     * one they must say so — reporting green would claim a proof nobody has, and reporting red
     * would blame the panel for a missing runtime.
     */
    private static int skipped;
    private static File shotDir;
    /** The frame that is packed but never shown — kept only to lay the panel out on demand. */
    private static JFrame frame;

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "flow";
        shotDir = new File(args.length > 1 ? args[1] : "target/harness-guided");
        shotDir.mkdirs();

        System.out.println("=== GuidedFlowHarness scenario: " + scenario + " ===");
        System.out.println("ING_ADO_CACHE          = " + System.getenv("ING_ADO_CACHE"));
        System.out.println("ING_TESTCASE_SELECTION = " + System.getenv("ING_TESTCASE_SELECTION"));
        System.out.println("ING_TESTDATA_CSV       = " + System.getenv("ING_TESTDATA_CSV"));
        System.out.println();

        switch (scenario) {
            case "flow" -> scenarioFlow();
            case "dirty" -> scenarioDirty();
            case "nocustomer" -> scenarioNoCustomer();
            case "labels" -> scenarioLabels();
            case "persist" -> scenarioPersist();
            case "aufnahme" -> scenarioRecording();
            case "einstieg" -> scenarioEntryPoint();
            case "adostatus" -> scenarioAdoStatus();
            case "wachhund" -> scenarioArmed();
            case "abgabe" -> scenarioHandoff();
            case "ohne-werkzeug" -> scenarioHandoffNoTool();
            case "ohne-node" -> scenarioHandoffNoNode();
            case "anker" -> scenarioAnchor();
            default -> {
                System.out.println("unknown scenario: " + scenario);
                System.exit(2);
            }
        }

        System.out.println();
        if (failures > 0) {
            System.out.println("RESULT: RED — " + failures + " of " + checks + " checks failed");
            System.exit(1);
        }
        if (skipped > 0) {
            System.out.println("RESULT: UNGEPRUEFT — " + checks + " checks passed, " + skipped
                + " could not be run on this machine");
            System.exit(4);
        }
        System.out.println("RESULT: GREEN — " + checks + " checks passed");
        System.exit(0);
    }

    // ------------------------------------------------------------------ scenarios

    /**
     * The check that would have caught the 2026-07-28 anchor drift — headlessly, in seconds,
     * on the commit that caused it.
     *
     * <h2>What went wrong, and why nothing here noticed</h2>
     *
     * <p>{@code StudioChainDriver} finds the guided-flow screen inside the Studio window by
     * climbing from the "übernehmen" button to the innermost ancestor that also carries the step
     * headline. A hint added to card 2 — <i>"…ob jeder <b>Schritt</b> genau ein Element
     * trifft…"</i> — made that ancestor {@code cardHost} rather than {@code root}, one level
     * below the header, where the chips and the banner live. Every chip and banner read came
     * back {@code null} and two links reported <b>BROKEN</b> against a screen that was working.
     *
     * <p>It was said afterwards that this harness could not have caught it, because it reads the
     * panel through its own test seam ({@code flow.headlineText()}, {@code flow.chipText(i)})
     * rather than through the Swing tree. That is true of the <em>other</em> scenarios and it is
     * not true of the machine they run on: {@link #build} puts the real panel into a real
     * {@link JFrame}, packs it and validates it. The whole Swing tree the live driver walks is
     * sitting right here, unshown and free. Nothing was missing except a scenario that pointed
     * the driver's own locator at it.
     *
     * <h2>The two checks, and why both</h2>
     *
     * <ol>
     *   <li><b>The locator resolves to exactly the component {@code createPanel()} returned.</b>
     *       This is the consequence. On the broken commit it answered {@code cardHost} and this
     *       check goes red — which is the whole point: it fails on the change that causes the
     *       damage, not three days later on a live Studio walk that takes minutes and a screen.
     *   <li><b>Exactly one label in the entire tree matches the headline's shape.</b> This is the
     *       <em>cause</em>, and it is the more valuable of the two, because it fails on the
     *       sentence rather than on its effect. The panel already carries a near miss —
     *       {@code "Weiter zu Schritt 2: Kunde wählen"} on the nav bar. One person writing
     *       "Weiter zu Schritt 2 von 3" moves the fallback again; this check catches that
     *       wording the moment it is typed, while the fallback's answer is still correct.
     * </ol>
     *
     * <p>Neither check makes the fallback safe. They make it <em>watched</em>. The fix that makes
     * it unnecessary is a marker on the panel's root — {@link PanelAnchor#MARKER_KEY} —
     * which is a change to {@code de/ing/qa/panel} and belongs to whoever owns that. Until it
     * lands, this scenario prints, on every run, which mechanism answered.
     */
    private static void scenarioAnchor() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());
        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = build(flow);
        check("Panel wurde gebaut", view != null,
            view == null ? "null" : view.getClass().getSimpleName());
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();

        // From the frame, exactly as the live driver searches from the Studio window — not from
        // the panel root, which would beg the question this scenario exists to ask.
        PanelAnchor.Result found = PanelAnchor.locate(frame, "Diesen Testfall übernehmen");
        System.out.println("  NOTE " + found.note());

        check("Der Treiber findet den Ablauf-Bildschirm ueberhaupt", found.found(),
            found.how().toString());
        check("… und zwar GENAU die Wurzel, die createPanel() zurueckgegeben hat",
            found.panel() == view,
            found.panel() == view
                ? "root"
                : "aufgeloest auf " + PanelAnchor.describe(found.panel())
                    + " statt auf " + PanelAnchor.describe(view)
                    + " — genau der Bruch vom 28.07.2026: Chips und Banner liegen im header, "
                    + "nicht darunter, und jede Ablesung kam null zurueck");

        // The cause, not the consequence. Counted over the whole tree, every card included:
        // the fallback climb is only unambiguous while this is 1.
        List<String> headlines = new ArrayList<>();
        for (Component c : descendants(view)) {
            if (c instanceof JLabel label && label.getText() != null) {
                String text = label.getText().replaceAll("<br>", " ").replaceAll("<[^>]+>", "").trim();
                if (PanelAnchor.STEP_HEADLINE.matcher(text).find()) {
                    headlines.add(text);
                }
            }
        }
        // Only a failure while the fallback is what locates the screen. Once the marker is
        // there, a second sentence of that shape is harmless, and failing on it would be this
        // project's own disease in miniature: a red that does not mean anything is wrong.
        if (found.how() == PanelAnchor.How.STRUCTURAL) {
            System.out.println("  NOTE Texte der Form \"Schritt N von M\": " + headlines.size()
                + " " + headlines + " — ohne Belang, solange der Marker den Anker traegt.");
        } else {
            check("Genau EIN Text auf dem ganzen Panel hat die Form \"Schritt N von M\"",
                headlines.size() == 1,
                headlines.size() + ": " + headlines
                    + (headlines.size() > 1
                        ? " — ein zweiter Satz dieser Form verschiebt den Treiber erneut; "
                            + "entweder umformulieren oder den Marker " + PanelAnchor.MARKER_KEY
                            + " setzen"
                        : ""));
        }

        // Whether the anchor is structural yet is the third state, not a pass and not a failure:
        // the one-line change is asked for and has not been made. Green would claim a proof
        // nobody has; red would blame the panel for a change that was deliberately routed
        // elsewhere. UNGEPRUEFT is what this project invented the state for.
        if (found.how() == PanelAnchor.How.STRUCTURAL) {
            check("Der Anker ist strukturell, nicht Prosa", true,
                "Marker " + PanelAnchor.MARKER_KEY + "=" + PanelAnchor.GUIDED_FLOW + " gesetzt");
        } else {
            skip("Der Anker ist strukturell, nicht Prosa",
                "noch nicht: der Bildschirm wird ueber die Ueberschrift gefunden. Solange das so "
                    + "ist, haengt jeder Live-Studio-Durchlauf an einem deutschen Satz. Es fehlt "
                    + "eine Zeile in GuidedFlowPanel.createPanel(): root.putClientProperty(\""
                    + PanelAnchor.MARKER_KEY + "\", \"" + PanelAnchor.GUIDED_FLOW + "\")");
        }

        noWindows("anker");
        shoot(view, "anker");
    }

    /** Every component under {@code c}, itself included. */
    private static List<Component> descendants(Component c) {
        List<Component> found = new ArrayList<>();
        if (c == null) {
            return found;
        }
        found.add(c);
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                found.addAll(descendants(child));
            }
        }
        return found;
    }

    /** The whole run: choose a case, see what it demands, choose a customer, record. */
    private static void scenarioFlow() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());
        GuidedFlowPanel flow = new GuidedFlowPanel();
        check("Titel des Reiters", "Ablauf".equals(flow.getTitle()), flow.getTitle());
        JComponent view = build(flow);
        check("Panel wurde gebaut", view != null, view == null ? "null" : view.getClass().getSimpleName());
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();

        // --- step 1 ---------------------------------------------------------------
        check("Startet bei Schritt 1", flow.currentStep() == 0, "step=" + flow.currentStep());
        check("Ueberschrift nennt Schritt 1",
            flow.headlineText().contains("Schritt 1 von 3")
                && flow.headlineText().contains("Testfall wählen"),
            flow.headlineText());
        check("Schritt 2 ist noch nicht erledigt", !flow.stepDone(1), flow.chipText(1));
        check("\"Weiter\" ist gesperrt, solange kein Testfall uebernommen ist",
            !flow.nextEnabled(), "enabled=" + flow.nextEnabled());
        check("Der Grund steht auf dem Bildschirm, nicht nur im Tooltip",
            flow.lockText().contains("Noch kein Testfall übernommen"), flow.lockText());
        check("Die Anleitung wird nicht abgeschnitten",
            flow.hintText().endsWith("\".") && flow.hintText().length() < 120, flow.hintText());
        noWindows("Schritt 1");
        shoot(view, "01-schritt1-testfall");

        // A locked step must SAY it is locked, not silently ignore the click.
        clickLabelContaining(view, "3. Aufnahme starten");
        settle();
        check("Klick auf einen gesperrten Schritt bleibt bei Schritt 1",
            flow.currentStep() == 0, "step=" + flow.currentStep());
        check("… und erklaert warum",
            flow.bannerText().contains("Noch kein Testfall übernommen"), flow.bannerText());
        shoot(view, "02-gesperrter-schritt-erklaert");

        // --- take a case ----------------------------------------------------------
        JList<?> list = (JList<?>) find(view, JList.class, null);
        check("Schritt 1 zeigt die Testfall-Liste", list != null && list.getModel().getSize() == 2,
            "rows=" + (list == null ? "n/a" : list.getModel().getSize()));
        select(list, 0);
        click(view, "Diesen Testfall übernehmen");
        settle();

        check("Nach dem Uebernehmen steht der Ablauf auf Schritt 2",
            flow.currentStep() == 1, "step=" + flow.currentStep());
        check("Schritt 1 ist als erledigt markiert",
            flow.stepDone(0) && flow.chipText(0).startsWith("✔"), flow.chipText(0));
        check("Die Bestaetigung nennt den Testfall",
            flow.bannerText().contains("3951650"), flow.bannerText());
        check("Der Testfall wurde wirklich gespeichert",
            "3951650".equals(AdoCache.readSelectedId()), String.valueOf(AdoCache.readSelectedId()));
        check("Ueberschrift nennt jetzt Schritt 2",
            flow.headlineText().contains("Schritt 2 von 3"), flow.headlineText());
        noWindows("Schritt 2");

        // --- step 2: the case text is visible WHILE the customer is chosen --------
        String requirement = flow.caseRequirementText();
        System.out.println("  Anforderungstext (Auszug): " + firstLines(requirement, 6));
        check("Der Testfall-Text steht neben der Kundenauswahl",
            requirement.contains("VORAUSSETZUNGEN"), firstLines(requirement, 1));
        check("… und enthaelt die echten Voraussetzungen",
            requirement.contains("aktives Girokonto") && requirement.contains("Bonitaet 12"),
            firstLines(requirement, 6));
        check("… samt Beschreibung und Schritten",
            requirement.contains("BESCHREIBUNG") && requirement.contains("SCHRITTE"),
            "sections");

        JTable table = (JTable) find(view, JTable.class, null);
        check("Schritt 2 zeigt Testkunden", table != null && table.getRowCount() == 5,
            "rows=" + (table == null ? "n/a" : table.getRowCount()));
        check("Der gesperrte Kunde wird nicht angeboten",
            flow.customers().offeredRowCount() == 5, "offered=" + flow.customers().offeredRowCount());
        check("Die Spalten stehen in Klartext da",
            table != null && tableRow(table, 0).contains("Einzelkunde")
                && tableRow(table, 0).contains("gute Bonität"),
            table == null ? "n/a" : tableRow(table, 0));
        shoot(view, "03-schritt2-testfall-text-und-kunde");

        // --- choose the customer ---------------------------------------------------
        table.setRowSelectionInterval(0, 0);
        click(view, "Kontonummer kopieren");
        settle();

        check("Nach dem Kopieren steht der Ablauf auf Schritt 3",
            flow.currentStep() == 2, "step=" + flow.currentStep());
        check("Schritt 2 ist als erledigt markiert",
            flow.stepDone(1) && flow.chipText(1).startsWith("✔"), flow.chipText(1));
        check("Die Bestaetigung nennt die Kontonummer",
            flow.bannerText().contains("1000000001"), flow.bannerText());
        check("Die Kontonummer liegt wirklich in der Zwischenablage",
            "1000000001".equals(clipboard()), String.valueOf(clipboard()));
        noWindows("Schritt 3");

        String summary = flow.summaryText();
        System.out.println("  Zusammenfassung:");
        System.out.println(indent(summary));
        check("Die Zusammenfassung nennt den Testfall",
            summary.contains("3951650") && summary.contains("Partner-Suche"), firstLines(summary, 12));
        check("Die Zusammenfassung nennt die Kontonummer",
            summary.contains("1000000001") && summary.contains("Zwischenablage"), "ok");
        check("Die Zusammenfassung sagt, dass nicht noch einmal gefragt wird",
            summary.contains("nicht noch") && summary.contains("einmal gefragt"), "ok");
        check("Die Voraussetzungen stehen auch hier noch einmal",
            summary.contains("aktives Girokonto"), "ok");
        check("Kein Dateipfad in der Zusammenfassung",
            !summary.contains("\\") && !summary.contains(".json"), "ok");
        shoot(view, "04-schritt3-aufnahme");

        // --- press record without a Studio ----------------------------------------
        flow.pressRecord();
        settle();
        check("Ohne Studio bleibt der Knopf nicht stumm",
            flow.bannerText().contains("Werkzeugleiste"), flow.bannerText());
        check("… und nennt keinen technischen Fehler im Klartext",
            !flow.bannerText().contains("Exception"), flow.bannerText());
        check("Der Knopf ist danach wieder benutzbar", buttonEnabled(view, "▶  Aufnahme starten"),
            "enabled");
        noWindows("nach Aufnahme starten");
        shoot(view, "05-aufnahme-ohne-studio");

        // --- going back keeps the answers -----------------------------------------
        clickLabelContaining(view, "Testfall wählen");
        settle();
        check("Zurueck zu Schritt 1 funktioniert", flow.currentStep() == 0, "step=" + flow.currentStep());
        check("… und die erledigten Schritte bleiben erledigt",
            flow.stepDone(0) && flow.stepDone(1), flow.chipText(0) + " | " + flow.chipText(1));
        check("… \"Weiter\" ist jetzt frei", flow.nextEnabled(), "enabled");
        shoot(view, "06-zurueck-mit-erhaltenem-fortschritt");
    }

    /** Off-shape rows must be withheld from the picker and counted out loud. */
    private static void scenarioDirty() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());
        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();

        System.out.println("  Status Testdaten: " + flow.customers().statusText());
        check("Nur die brauchbaren Zeilen werden angeboten",
            flow.customers().offeredRowCount() == 2, "offered=" + flow.customers().offeredRowCount());
        check("Die kaputten Zeilen werden gezaehlt",
            flow.customers().skippedRowCount() == 3, "skipped=" + flow.customers().skippedRowCount());
        check("Die Statuszeile sagt es",
            flow.customers().statusText().contains("unvollständig"), flow.customers().statusText());

        JList<?> list = (JList<?>) find(view, JList.class, null);
        select(list, 0);
        click(view, "Diesen Testfall übernehmen");
        settle();
        check("Schritt 2 warnt sichtbar vor den unvollstaendigen Daten",
            labelTextContaining(view, "nicht die erwartete Form") != null,
            String.valueOf(labelTextContaining(view, "nicht die erwartete Form")));
        check("Die Warnung zeigt auf die Datei, nicht auf die Testdaten selbst",
            String.valueOf(labelTextContaining(view, "nicht die erwartete Form"))
                .contains("veraltet erzeugte Datei"),
            String.valueOf(labelTextContaining(view, "nicht die erwartete Form")));
        noWindows("dirty");
        shoot(view, "07-unvollstaendige-testdaten");
    }

    /** A case that needs no customer must not dead-end the tester. */
    private static void scenarioNoCustomer() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());
        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();

        JList<?> list = (JList<?>) find(view, JList.class, null);
        select(list, 0);
        click(view, "Diesen Testfall übernehmen");
        settle();
        click(view, "Dieser Testfall braucht keinen Testkunden");
        settle();

        check("Der Ablauf geht auch ohne Kunden weiter",
            flow.currentStep() == 2, "step=" + flow.currentStep());
        check("Die Zusammenfassung sagt ehrlich, dass kein Kunde gewaehlt wurde",
            flow.summaryText().contains("keine — Sie haben angegeben"), firstLines(flow.summaryText(), 14));
        noWindows("nocustomer");
        shoot(view, "08-ohne-testkunden");
    }

    /**
     * The shipped label map, against the real column names, with NOTHING configured.
     *
     * <p>{@code ING_TESTDATA_LABELS} is deliberately unset here: a tester who installs the
     * plugin and sets nothing must still read German. It also pins the other half of the
     * rule — a code nobody has confirmed stays raw. Inventing "Bonität 62 = solvent" would
     * be believed, and would be a guess.
     */
    private static void scenarioLabels() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());
        check("Kein ING_TESTDATA_LABELS gesetzt", System.getenv("ING_TESTDATA_LABELS") == null,
            String.valueOf(System.getenv("ING_TESTDATA_LABELS")));

        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();

        JList<?> list = (JList<?>) find(view, JList.class, null);
        select(list, 0);
        click(view, "Diesen Testfall übernehmen");
        settle();

        JTable table = (JTable) find(view, JTable.class, null);
        check("Die echten Spalten werden geladen", table != null && table.getRowCount() == 5,
            "rows=" + (table == null ? "n/a" : table.getRowCount()));

        List<String> captions = new ArrayList<>();
        for (int c = 0; c < table.getColumnCount(); c++) {
            captions.add(String.valueOf(table.getColumnName(c)));
        }
        System.out.println("  Spaltenüberschriften: " + captions);
        check("Spalte \"Part Partnertyp Kz\" heisst jetzt Kundenart",
            captions.contains("Kundenart"), String.valueOf(captions));
        check("Spalte \"MDJ_KND\" heisst jetzt Minderjährig — mit korrektem Umlaut",
            captions.contains("Minderjährig"), String.valueOf(captions));
        check("Spalte \"Kbo5 Bonitaet S\" heisst jetzt Bonität (Code)",
            captions.contains("Bonität (Code)"), String.valueOf(captions));
        check("Spalte \"Verf Bez\" heisst jetzt Zugangsverfahren",
            captions.contains("Zugangsverfahren"), String.valueOf(captions));
        check("Roher Spaltenname verschwunden", !captions.contains("MDJ_KND"),
            String.valueOf(captions));

        String row0 = tableRow(table, 0);
        String row3 = tableRow(table, 3);
        System.out.println("  Zeile 1: " + row0);
        System.out.println("  Zeile 4: " + row3);
        check("P steht als Einzelkunde da", row0.contains("Einzelkunde (P)"), row0);
        check("INTB steht als Internetbanking da", row0.contains("Internetbanking (INTB)"), row0);
        check("Boni 21 steht als \"unbekannt verzogen\" da — Renés eigene Worte",
            row0.contains("unbekannt verzogen (21)"), row0);
        check("N steht als nein da", row0.contains("nein (N)"), row0);
        check("J steht als ja da", tableRow(table, 2).contains("ja (J)"), tableRow(table, 2));
        check("PBTN steht als Postbox da", tableRow(table, 1).contains("Postbox (PBTN)"),
            tableRow(table, 1));

        // The other half of the rule: unconfirmed codes must NOT acquire a meaning.
        //
        // Read the CELL, for the same reason as the Kundenart check below. This used to be
        // tableRow(table, 1).contains("62") && !tableRow(table, 1).contains("62)") over the
        // whole concatenated row, so it was satisfied by a "62" anywhere in it and forbade
        // exactly one rendering — the trailing "62)". A gloss of the shape "leicht erhöht [62]",
        // or one placed in front, passed. The cell must be the bare code and nothing else.
        String boni = cell(table, 1, "Bonität (Code)");
        check("Unbestätigter Bonitäts-Code 62 bleibt roh — kein erfundener Klartext",
            "62".equals(boni), "Bonität in Zeile 1 = " + quoted(boni));
        // Read the Kundenart CELL, not the whole concatenated row.
        //
        // This check used to be `!row3.contains("(J)") || !row3.contains("Minderjährig (J)")`,
        // which collapses to `!row3.contains("Minderjährig (J)")` — and "Minderjährig" is a
        // COLUMN CAPTION produced by the label map, never a cell value, so tableRow() (which
        // concatenates cell values only) could not produce that string under any rendering of
        // the product. The check was unconditionally true. It was also aimed at the wrong
        // column: three lines above, MDJ_KND is proven to translate J -> "ja (J)", so row3
        // contains "(J)" in every healthy run. A regression that glossed the Kundenart code J
        // as e.g. "Jugendlicher (J)" — exactly what this check names — sailed through.
        String kundenart = cell(table, 3, "Kundenart");
        check("Unbestätigte Kundenart bleibt roh — kein erfundener Klartext",
            kundenart != null && !kundenart.matches(".*\\(.+\\).*"),
            "Kundenart in Zeile 3 = " + quoted(kundenart));
        // Same again. row0.contains("5") && !row0.contains("legitimiert") searched the whole
        // concatenated row for a "5" — of which V001 happens to have exactly one today, so it
        // was decidable by luck — and forbade a single German word. The regression it is named
        // after, a new "Legi Status Kz.5=Ausweis geprüft" entry rendering the cell as
        // "Ausweis geprüft (5)", contains no "legitimiert" and passed.
        String legi = cell(table, 0, "Legitimationsstatus (Code)");
        check("Unbestätigter Legitimationsstatus bleibt roh — kein erfundener Klartext",
            "5".equals(legi), "Legitimationsstatus in Zeile 0 = " + quoted(legi));

        // Umlauts everywhere they can appear, not only in the table.
        check("Umlaute im Produktnamen aus der Datei",
            tableRow(table, 4).contains("Annuitäten Darlehen"), tableRow(table, 4));
        check("Umlaute in der Filter-Beschriftung",
            labelContaining(view, "Bonität (Code):") != null, "Filterzeile");
        check("Der gesperrte Kunde wird weiterhin nicht angeboten",
            flow.customers().offeredRowCount() == 5, "offered=" + flow.customers().offeredRowCount());
        check("Keine unvollständigen Zeilen im korrekt konvertierten Export",
            flow.customers().skippedRowCount() == 0, "skipped=" + flow.customers().skippedRowCount());

        noWindows("labels");
        shoot(view, "09-echte-spalten-in-klartext");

        // …and into the summary, which is where the umlauts previously died.
        table.setRowSelectionInterval(0, 0);
        click(view, "Kontonummer kopieren");
        settle();
        check("Die Zusammenfassung nennt die Kontonummer roh, nicht uebersetzt",
            flow.summaryText().contains("1000000001"), firstLines(flow.summaryText(), 10));
        shoot(view, "10-zusammenfassung-echte-spalten");
    }

    /**
     * The customer that gets written onto the test case, chosen from a FILTERED table.
     *
     * <p>This is the one case where the obvious implementation is wrong twice over, so it
     * is the one case worth its own scenario:
     *
     * <ul>
     *   <li>the selected row index is an index into what is <em>on screen</em>. With a
     *       filter active, row 0 on screen is not row 0 of the file — using it against the
     *       backing list records a different customer than the tester is looking at;
     *   <li>the cells on screen have been translated. Persisting them would write
     *       "Einzelkunde (P)" where the file says {@code P}, and the recorded profile would
     *       carry German prose instead of the codes the application actually keys on.
     * </ul>
     *
     * <p>The filter used here selects a customer that is deliberately NOT first in the
     * file, so a regression to either bug fails this scenario rather than passing quietly.
     */
    private static void scenarioPersist() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());
        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();

        JList<?> list = (JList<?>) find(view, JList.class, null);
        select(list, 0);
        click(view, "Diesen Testfall übernehmen");
        settle();

        JTable table = (JTable) find(view, JTable.class, null);
        check("Ungefiltert stehen alle fuenf Testkunden da", table.getRowCount() == 5,
            "rows=" + table.getRowCount());
        String unfilteredFirst = tableRow(table, 0);
        check("Ungefiltert ist der erste Kunde 1000000001", unfilteredFirst.contains("1000000001"),
            unfilteredFirst);

        // Narrow to a customer that is NOT the first row of the file: V003 / 1000000003.
        SwingUtilities.invokeAndWait(() -> flow.customers().applyFreeTextForTest("Rahmenkredit"));
        settle();
        check("Der Filter laesst genau einen Testkunden uebrig", table.getRowCount() == 1,
            "rows=" + table.getRowCount());
        System.out.println("  Gefiltert, Zeile 1 (Anzeige): " + tableRow(table, 0));

        table.setRowSelectionInterval(0, 0);
        settle();

        List<String> raw = flow.customers().selectedRawRowForTest();
        List<String> headers = flow.customers().headersForTest();
        System.out.println("  Rohzeile: " + raw);
        check("Es gibt ueberhaupt eine Rohzeile", raw != null, String.valueOf(raw));
        check("Es ist der Kunde, den die Testerin ansieht — nicht Zeile 0 der Datei",
            raw.contains("1000000003") && !raw.contains("1000000001"), String.valueOf(raw));

        int boni = headers.indexOf("Kbo5 Bonitaet S");
        int typ = headers.indexOf("Part Partnertyp Kz");
        int mdj = headers.indexOf("MDJ_KND");
        check("Die Bonitaet ist der rohe Code 66, nicht der Klartext",
            "66".equals(raw.get(boni)), raw.get(boni));
        check("Die Kundenart ist der rohe Code P, nicht \"Einzelkunde (P)\"",
            "P".equals(raw.get(typ)), raw.get(typ));
        check("Das Minderjaehrig-Kennzeichen ist J, nicht \"ja (J)\"",
            "J".equals(raw.get(mdj)), raw.get(mdj));

        // The same row, as the table shows it — the separation, stated as a check.
        String shown = tableRow(table, 0);
        check("Auf dem Bildschirm steht derweil der Klartext",
            shown.contains("Einzelkunde (P)") && shown.contains("ja (J)"), shown);

        // And what would actually be persisted, through the real profile type.
        CustomerProfile profile = CustomerProfile.of(headers, raw);
        System.out.println("  Profil: " + profile.settings());
        check("Das Profil traegt die rohen Codes",
            "66".equals(profile.settings().get("Kbo5 Bonitaet S"))
                && "P".equals(profile.settings().get("Part Partnertyp Kz")),
            String.valueOf(profile.settings()));
        check("Das Profil traegt KEINEN Klartext",
            !String.valueOf(profile.settings()).contains("Einzelkunde"),
            String.valueOf(profile.settings()));
        check("Das Profil traegt KEINE Kontonummer",
            !String.valueOf(profile.settings()).contains("1000000003"),
            String.valueOf(profile.settings()));

        // The copy action itself must still work, and must not throw without a Studio handle.
        click(view, "Kontonummer kopieren");
        settle();
        check("Die Kontonummer des GEFILTERTEN Kunden liegt in der Zwischenablage",
            "1000000003".equals(clipboard()), String.valueOf(clipboard()));
        check("Ohne Studio-Projekt bleibt es bei der Kontonummer-Meldung",
            flow.customers().statusText().contains("1000000003"),
            flow.customers().statusText());
        noWindows("persist");
        shoot(view, "11-gefilterte-auswahl-rohzeile");
    }

    /**
     * The recording button, against a Studio whose Record method is the real toggle.
     *
     * <p>This scenario exists for one sentence in the bug report: <em>a second press
     * silently ends the recording under a success banner</em>. It is proved twice over —
     * through the button, which must never be able to send that second start; and through
     * {@link de.ing.qa.panel.RecorderProbe}, which sends one anyway and shows that
     * {@code record()} was not entered. The counter is the evidence: a refusal that still
     * called the method would have stopped the recording just the same.
     */
    private static void scenarioRecording() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());
        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();

        JList<?> list = (JList<?>) find(view, JList.class, null);
        select(list, 0);
        click(view, "Diesen Testfall übernehmen");
        settle();
        JTable table = (JTable) find(view, JTable.class, null);
        table.setRowSelectionInterval(0, 0);
        click(view, "Kontonummer kopieren");
        settle();
        check("Schritt 3 ist erreicht", flow.currentStep() == 2, "step=" + flow.currentStep());

        // --- no Studio: the panel must not pretend to know anything ------------------
        check("Ohne Studio sagt der Zustand genau das",
            flow.recorderStateText().contains("Kein Studio-Fenster gefunden"),
            flow.recorderStateText());
        check("… und behauptet nicht, es laufe keine Aufnahme",
            !flow.recorderStateText().contains("Zurzeit läuft keine"), flow.recorderStateText());
        check("Ohne Studio steht keine Start-Adresse da, statt einer geratenen",
            flow.startUrlNoteText().isEmpty(), flow.startUrlNoteText());
        shoot(view, "12-aufnahme-ohne-studio-zustand");

        // --- a Studio appears, idle --------------------------------------------------
        AppMainFrame studio = new AppMainFrame();
        TestCaseComponent core = studio.getTestDesign().getTestCaseComp();
        poll(flow);
        check("Der Zustand folgt Studio, ohne dass etwas gedrueckt wurde",
            flow.recorderStateText().contains("Zurzeit läuft keine Aufnahme"),
            flow.recorderStateText());
        check("Der Knopf bietet den Start an", BTN_RECORD.equals(flow.recordButtonText()),
            flow.recordButtonText());
        check("Die fehlende Start-Adresse wird VOR der Aufnahme gemeldet",
            flow.startUrlNoteText().contains("keine Start-Adresse hinterlegt"),
            flow.startUrlNoteText());
        shoot(view, "13-studio-bereit-ohne-startadresse");

        // --- the two lines that used to be HTML --------------------------------------
        // An HTML label re-wraps when it is narrowed but keeps the height it was measured at
        // for one line, so the second line is sliced through the middle. This was live on this
        // very line until 2026-07-28 and is visible in the 60b render of the day before. The
        // property that makes it impossible is that the text is not markup at all: a plain
        // JLabel cannot wrap, so it truncates with an ellipsis instead.
        check("Die Start-Adress-Zeile ist kein umbrechendes HTML",
            !flow.startUrlNoteText().contains("<html>"), flow.startUrlNoteText());
        check("Die Zustands-Zeile daneben ebenfalls nicht",
            !flow.recorderStateText().contains("<html>"), flow.recorderStateText());
        // Clipping only helps if the front of the line is the half worth keeping. This is the
        // one start-address line that asks the tester to do something, so the request stands
        // in front of the reason for it.
        check("Die Aufforderung steht ganz vorn, nicht hinter der Erklaerung",
            flow.startUrlNoteText().startsWith("Bitte hier eintragen"),
            flow.startUrlNoteText());
        check("… und der ganze Satz bleibt im Tooltip erreichbar",
            flow.startUrlNoteTooltip().equals(flow.startUrlNoteText())
                && flow.startUrlNoteTooltip().contains("Browser leer"),
            flow.startUrlNoteTooltip());
        // Rendered narrow, because that is where the defect was: at 1500 pixels the sliced
        // second line does not exist and the render proves nothing about it.
        resizeTo(900, 780);
        // The short status word survives narrowing whatever the layout does, so it is the wrong
        // line to ask this of — but it costs nothing to ask, and the row it sits in is the one
        // that dropped the long unreadable-Studio sentence off its own bottom edge.
        check("Die Zustands-Zeile bleibt im schmalen Fenster in ihrer Zeile",
            flow.recorderStateLaidOut(), "laidOut=" + flow.recorderStateLaidOut());
        shoot(view, "13b-startadresse-fehlt-schmales-fenster");
        resizeTo(1500, 950);

        // --- press once --------------------------------------------------------------
        flow.pressRecord();
        settle();
        check("Der erste Druck ruft record() genau einmal auf", core.recordCalls() == 1,
            "recordCalls=" + core.recordCalls());
        check("Die Meldung sagt, dass gestartet wurde",
            flow.bannerText().contains("Aufnahme wurde gestartet"), flow.bannerText());
        check("… und sagt gleich dazu, wo sie beendet wird",
            flow.bannerText().contains("Aufnahme beenden"), flow.bannerText());
        check("Solange der Browser hochkommt, ist der Knopf gesperrt",
            !flow.recordButtonEnabled(), "enabled=" + flow.recordButtonEnabled());
        check("… und der Zustand sagt warum",
            flow.recorderStateText().contains("wird gestartet"), flow.recorderStateText());
        noWindows("nach dem ersten Druck");
        shoot(view, "14-aufnahme-startet");

        // A press in that window must not reach record() at all.
        flow.pressRecord();
        settle();
        check("Ein Druck waehrend des Startens ruft record() NICHT erneut auf",
            core.recordCalls() == 1, "recordCalls=" + core.recordCalls());
        String whileStarting = probeStart();
        check("Eine Startanfrage waehrend des Startens wird abgelehnt",
            whileStarting.startsWith("NEIN|"), whileStarting);
        check("… ohne record() anzufassen", core.recordCalls() == 1,
            "recordCalls=" + core.recordCalls());

        // --- the recorder is live ----------------------------------------------------
        core.simulateRecorderReady();
        poll(flow);
        check("Der Knopf wird zum Stopp-Knopf, sobald die Aufnahme laeuft",
            BTN_STOP.equals(flow.recordButtonText()), flow.recordButtonText());
        check("Der Zustand sagt, dass aufgezeichnet wird",
            flow.recorderStateText().contains("Die Aufnahme läuft"), flow.recorderStateText());
        check("Der Stopp-Knopf ist benutzbar", flow.recordButtonEnabled(), "enabled");
        noWindows("waehrend der Aufnahme");
        shoot(view, "15-aufnahme-laeuft-mit-stopp-knopf");

        // --- THE bug: a start request during a running recording ---------------------
        int before = core.recordCalls();
        String duringRecording = probeStart();
        System.out.println("  Startanfrage waehrend der Aufnahme: " + duringRecording);
        check("Eine Startanfrage waehrend der Aufnahme meldet KEINEN Start",
            duringRecording.startsWith("NEIN|"), duringRecording);
        check("… sie sagt, dass bereits aufgezeichnet wird",
            duringRecording.contains("Es läuft bereits eine Aufnahme"), duringRecording);
        check("… und ruft record() NICHT auf — sonst haette sie die Aufnahme beendet",
            core.recordCalls() == before, "recordCalls=" + core.recordCalls());
        check("Die Aufnahme laeuft danach unveraendert weiter",
            studio.getTestDesign().getTestCaseComp().getToolBar().isRecording(), "isRecording=true");

        // …and the mirror image: a stop request when nothing is running.
        core.simulateStoppedElsewhere();
        int beforeStop = core.recordCalls();
        String stopWhenIdle = probeStop();
        check("Eine Stoppanfrage ohne laufende Aufnahme startet keine",
            stopWhenIdle.startsWith("NEIN|") && core.recordCalls() == beforeStop, stopWhenIdle);
        core.simulateRecorderReady();
        poll(flow);

        // --- the stop, pressed on THIS screen ----------------------------------------
        int beforeRealStop = core.recordCalls();
        flow.pressRecord();
        settle();
        check("Der Stopp-Knopf ruft record() genau einmal auf",
            core.recordCalls() == beforeRealStop + 1, "recordCalls=" + core.recordCalls());
        check("Die Meldung spricht vom Beenden, nicht vom Starten",
            flow.bannerText().contains("beendet") && !flow.bannerText().contains("gestartet"),
            flow.bannerText());
        check("Studio zeichnet danach wirklich nicht mehr auf",
            !core.getToolBar().isRecording(), "isRecording=false");
        check("Der Knopf bietet wieder den Start an",
            BTN_RECORD.equals(flow.recordButtonText()), flow.recordButtonText());
        check("… und der Zustand ebenfalls",
            flow.recorderStateText().contains("Zurzeit läuft keine Aufnahme"),
            flow.recorderStateText());
        noWindows("nach dem Beenden");
        shoot(view, "16-aufnahme-beendet");

        // --- the recording ends somewhere else ---------------------------------------
        core.record();
        core.simulateRecorderReady();
        poll(flow);
        check("Der Knopf folgt einer anderswo gestarteten Aufnahme",
            BTN_STOP.equals(flow.recordButtonText()), flow.recordButtonText());
        core.simulateStoppedElsewhere();
        poll(flow);
        check("… und folgt ihr auch, wenn sie in Test Design beendet wird",
            BTN_RECORD.equals(flow.recordButtonText()), flow.recordButtonText());
        check("Der Zustand ist dann wieder ehrlich",
            flow.recorderStateText().contains("Zurzeit läuft keine Aufnahme"),
            flow.recorderStateText());

        // --- a configured start address is named, not just its absence ---------------
        studio.getTestDesign().getProject().getProjectSettings().getRecorderSettings()
            .setStartUrl("https://beispielanwendung.example.com/start");
        poll(flow);
        check("Eine hinterlegte Start-Adresse steht im Klartext da",
            flow.startUrlNoteText().contains("beispielanwendung.example.com"), flow.startUrlNoteText());
        shoot(view, "17-startadresse-hinterlegt");

        studio.dispose();
    }

    /**
     * One way in for the tester, all four for the engineer.
     *
     * <p>Studio builds its toolbar from the JAR manifest's {@code pluginEntryClasses}, so the
     * count of screens a tester is offered is decided in the POM and nowhere else — checking
     * the rendered panel would prove nothing about it. Both halves are checked here: that the
     * declaration names exactly one screen, and that the switch which brings the other three
     * back actually brings them back.
     */
    private static void scenarioEntryPoint() throws Exception {
        // shotDir is <plugin>/target/harness-guided, so the POM is two levels up — the
        // working directory is whatever the caller happened to be in.
        Path pom = shotDir.getCanonicalFile().toPath().getParent().getParent().resolve("pom.xml");
        check("Die POM ist da", Files.isRegularFile(pom), pom.toString());
        String declared = "";
        if (Files.isRegularFile(pom)) {
            String xml = Files.readString(pom, java.nio.charset.StandardCharsets.UTF_8);
            int from = xml.indexOf("<pluginEntryClasses>");
            int to = xml.indexOf("</pluginEntryClasses>");
            declared = from < 0 ? "" : xml.substring(from + 20, to).trim();
        }
        System.out.println("  pluginEntryClasses = " + declared);
        List<String> screens = new ArrayList<>();
        for (String name : declared.split(",")) {
            if (name.contains(".panel.")) {
                screens.add(name.trim());
            }
        }
        check("Studio bekommt genau EINEN Bildschirm angeboten", screens.size() == 1,
            String.valueOf(screens));
        check("… und zwar den Ablauf",
            screens.contains("de.ing.qa.panel.GuidedFlowPanel"), String.valueOf(screens));
        check("Der Aufnahme-Empfänger bleibt angemeldet",
            declared.contains("de.ing.qa.studio.AdoRecordingTarget"), declared);

        // Default: nothing but the flow.
        System.clearProperty("ING_QA_PANELS");
        GuidedFlowPanel plain = new GuidedFlowPanel();
        JComponent plainView = build(plain);
        check("Ohne Schalter gibt es keine Reiter",
            find(plainView, javax.swing.JTabbedPane.class, null) == null,
            plainView.getClass().getSimpleName());
        check("Testfaelle geladen", plain.awaitReady(15_000), "settled");
        settle();
        shoot(plainView, "18-einstieg-nur-ablauf");

        // The engineers' switch.
        System.setProperty("ING_QA_PANELS", "alle");
        GuidedFlowPanel all = new GuidedFlowPanel();
        JComponent allView = build(all);
        check("Testfaelle geladen", all.awaitReady(15_000), "settled");
        settle();
        javax.swing.JTabbedPane tabs =
            (javax.swing.JTabbedPane) find(allView, javax.swing.JTabbedPane.class, null);
        check("Mit ING_QA_PANELS=alle stehen die Einzelbildschirme wieder da",
            tabs != null && tabs.getTabCount() == 4,
            tabs == null ? "keine Reiter" : "tabs=" + tabs.getTabCount());
        List<String> titles = new ArrayList<>();
        for (int i = 0; tabs != null && i < tabs.getTabCount(); i++) {
            titles.add(tabs.getTitleAt(i));
        }
        System.out.println("  Reiter: " + titles);
        check("Der Ablauf steht zuerst", !titles.isEmpty() && "Ablauf".equals(titles.get(0)),
            String.valueOf(titles));
        check("Testdaten, Testfall wählen und Testfall-Übersicht sind wieder erreichbar",
            titles.containsAll(List.of("Testdaten", "Testfall wählen", "Testfall-Übersicht")),
            String.valueOf(titles));
        noWindows("einstieg");
        shoot(allView, "19-einstieg-mit-einzelbildschirmen");
        System.clearProperty("ING_QA_PANELS");
    }

    /**
     * The ADO upload outcome, on screen, in every state it can end in.
     *
     * <p>The first half is the one that silently regresses: the upload runs on the watcher's
     * daemon thread whenever a run finishes, which may be long before this screen is ever
     * opened — so the panel here is built <b>after</b> the upload has already finished, and
     * must still show what happened. That is what {@code AdoUploadStatus.addListener}'s replay
     * is for, and a listener wired to future events only would fail exactly this check while
     * passing every other one.
     *
     * <p>The second half is that the five states stay distinguishable. Colour is asserted as
     * well as wording: a failure printed in the success green is the same "said too quietly"
     * defect this panel exists to remove, and it would read as a pass to a test that only
     * compared strings.
     */
    private static void scenarioAdoStatus() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());
        AdoUploadProbe.reset();

        // --- an upload that finishes while no panel exists ---------------------------
        AdoUploadProbe.running(ADO_ID, ADO_CASE);
        String ok = AdoUploadProbe.finished(ADO_ID, ADO_CASE, hook("OK",
            "ADO-Lauf 25518995 angelegt, 4 Datei(en) angehängt."), 0);
        System.out.println("  vor dem Panel veroeffentlicht: " + ok);
        check("Der Ausgang ist bekannt, bevor es ein Panel gibt",
            "OK".equals(AdoUploadProbe.lastState()), AdoUploadProbe.lastState());

        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();

        check("Vor jedem Lauf steht da, wofuer die Zeile gut ist — statt einer leeren Flaeche",
            flow.uploadStateText().contains("Azure DevOps"), flow.uploadStateText());
        // The banner says it too, and says it the moment the screen opens — the tester need not
        // already be on step 3 to learn what became of the run. It is the panel's "what just
        // happened" line, so a later action replaces it; the step-3 line below is the one that
        // keeps the outcome until the next upload.
        check("Beim Oeffnen meldet auch das Banner den nachgereichten Ausgang",
            flow.bannerText().contains("ADO-Upload OK"), flow.bannerText());

        toStep3(flow, view);
        settle();

        System.out.println("  Upload-Zeile: " + flow.uploadStateText());
        check("Ein vor dem Panel beendeter Upload steht trotzdem auf dem Bildschirm",
            flow.uploadStateText().contains("ADO-Upload OK"), flow.uploadStateText());
        check("… und sagt im Klartext, dass das Ergebnis angekommen ist",
            flow.uploadStateText().contains("in Azure DevOps angekommen"), flow.uploadStateText());
        check("… mit der ADO-Lauf-Nummer aus dem Hook",
            flow.uploadStateText().contains("25518995"), flow.uploadStateText());
        String okColour = flow.uploadStateColour();
        System.out.println("  Farbe bei OK: " + okColour);
        check("Erfolg ist gruen hinterlegt", "#e3f6e3".equals(okColour), okColour);
        noWindows("nach dem nachgereichten Upload");
        shoot(view, "20-ado-upload-nachtraeglich-sichtbar");

        // --- a fresh upload starts: seven minutes must not look like nothing ---------
        AdoUploadProbe.running(ADO_ID, ADO_CASE);
        settle();
        check("Der Beginn des Uploads ist zu sehen, nicht erst sein Ende",
            flow.uploadStateText().contains("ADO-Upload läuft"), flow.uploadStateText());
        check("… und die Zeile behauptet nicht mehr, es sei schon fertig",
            !flow.uploadStateText().contains("ADO-Upload OK"), flow.uploadStateText());
        check("… und sagt, dass es dauern kann",
            flow.uploadStateText().contains("einige Minuten"), flow.uploadStateText());
        String runningColour = flow.uploadStateColour();
        check("Laufend sieht anders aus als fertig", !runningColour.equals(okColour),
            runningColour + " vs " + okColour);
        noWindows("waehrend des Uploads");
        shoot(view, "21-ado-upload-laeuft");

        // --- it fails ----------------------------------------------------------------
        String failed = AdoUploadProbe.finished(ADO_ID, ADO_CASE, hook("FEHLER",
            "ADO-Upload fehlgeschlagen (kein Token, Exit 3). Details im Beleg."), 3);
        settle();
        System.out.println("  Upload-Zeile: " + flow.uploadStateText());
        check("Ein Fehlschlag steht genauso laut da wie ein Erfolg",
            flow.uploadStateText().contains("ADO-Upload FEHLER"), flow.uploadStateText());
        check("… und sagt der Testerin, dass ihr Ergebnis NICHT in ADO steht",
            flow.uploadStateText().contains("NICHT in Azure DevOps"), flow.uploadStateText());
        check("… und wen sie fragen soll",
            flow.uploadStateText().contains("Testautomatisierung"), flow.uploadStateText());
        String failColour = flow.uploadStateColour();
        System.out.println("  Farbe bei FEHLER: " + failColour);
        check("Ein Fehlschlag ist NICHT gruen hinterlegt", !failColour.equals(okColour),
            failColour + " vs " + okColour);
        check("Er ist rot hinterlegt", "#fde7e7".equals(failColour), failColour);
        check("Auch das Banner traegt den Fehlschlag",
            flow.bannerText().contains("FEHLER"), flow.bannerText());
        // Compares the PANEL's rendering against the product's formatter.
        //
        // It used to be failed.equals(AdoUpload.statusLine(hook(…), 3)) — and `failed` is what
        // AdoUploadProbe.finished() returned, which is literally AdoUpload.statusLine(hook(…), 3)
        // for the same literal input. statusLine is a pure function of (stdout, exit): no clock,
        // no state, no I/O. So it was x.equals(x), true on every machine and every build,
        // including one where statusLine returned "" for everything. The one assertion in this
        // file claiming the harness is not grading its own homework could not fail.
        //
        // The property is real — AdoUploadProbe's own javadoc states it: "A probe that wrote its
        // own strings would keep passing after the real hook changed its wording." So the two
        // sides must come from different places: the left from the Swing panel, the right from
        // AdoUpload.statusLine. The panel embeds the status line verbatim between a glyph and
        // its own advice sentence, so a change of wording on either side breaks this.
        String formattedByProduct = AdoUpload.statusLine(hook("FEHLER",
            "ADO-Upload fehlgeschlagen (kein Token, Exit 3). Details im Beleg."), 3);
        check("Der Text auf dem Schirm kommt aus AdoUpload.statusLine, nicht aus dem Harness",
            flow.uploadStateText().contains(formattedByProduct),
            flow.uploadStateText() + "  ||  erwartet enthalten: " + formattedByProduct);
        // `failed` deliberately not compared against formattedByProduct: AdoUploadProbe.finished
        // RETURNS AdoUpload.statusLine(...), so that comparison is the tautology this replaced.
        noWindows("nach dem Fehlschlag");
        shoot(view, "22-ado-upload-fehlgeschlagen");

        // A JLabel clips instead of wrapping, and the panel is not always 1500 pixels wide. The
        // verdict therefore comes FIRST, so a narrow window loses the explanation and never the
        // answer — and the whole sentence stays reachable as a tooltip.
        check("Das Urteil steht am Anfang der Zeile, nicht am Ende",
            flow.uploadStateText().startsWith("✖ ADO-Upload FEHLER"), flow.uploadStateText());
        // HTML here would re-wrap in a narrow window while keeping its one-line height, and the
        // second line would be sliced through the middle. Rendered proof: 22b.
        check("Die Zeile ist kein HTML, damit sie schneidet statt halb umzubrechen",
            !flow.uploadStateText().contains("<html>"), flow.uploadStateText());
        check("Die ganze Meldung bleibt im Tooltip erhalten",
            flow.uploadStateTooltip().contains("Testautomatisierung"), flow.uploadStateTooltip());
        resizeTo(900, 760);
        shoot(view, "22b-ado-upload-fehlgeschlagen-schmales-fenster");
        resizeTo(1500, 950);

        // --- nothing to upload, and switched off: neither is a success or a failure ---
        AdoUploadProbe.finished(null, ADO_CASE, hook("UEBERSPRUNGEN",
            "Ergebnis \"failed\" — ADO-Upload übersprungen; ado-automark markiert "
                + "ausschließlich Bestanden."), 0);
        settle();
        System.out.println("  Upload-Zeile: " + flow.uploadStateText());
        check("\"Nichts hochzuladen\" wird als solches gemeldet",
            flow.uploadStateText().contains("ADO-Upload ÜBERSPRUNGEN"), flow.uploadStateText());
        check("… und sieht weder nach Erfolg noch nach Fehler aus",
            !flow.uploadStateColour().equals(okColour)
                && !flow.uploadStateColour().equals(failColour),
            flow.uploadStateColour());
        check("… und behauptet nicht, etwas sei angekommen",
            !flow.uploadStateText().contains("angekommen"), flow.uploadStateText());
        shoot(view, "23-ado-upload-uebersprungen");

        AdoUploadProbe.finished(ADO_ID, ADO_CASE, hook("AUS",
            "ING_ADO_UPLOAD=0 — es wurde nichts nach ADO hochgeladen."), 0);
        settle();
        System.out.println("  Upload-Zeile: " + flow.uploadStateText());
        check("Ein abgeschalteter Upload sagt, dass er abgeschaltet ist",
            flow.uploadStateText().contains("ADO-Upload AUS"), flow.uploadStateText());
        check("… und nennt das ausdruecklich keinen Fehler",
            flow.uploadStateText().contains("kein Fehler"), flow.uploadStateText());
        check("… und ist nicht rot", !flow.uploadStateColour().equals(failColour),
            flow.uploadStateColour());
        noWindows("nach dem abgeschalteten Upload");
        shoot(view, "24-ado-upload-aus");

        // --- a rehearsal: nothing was written, and that was the point -----------------
        // The sixth state, and the one that used to fall through to the red default. A
        // --dry-run writes nothing BY DESIGN, and the panel told the tester in bold German
        // that their morning's work had NOT reached Azure DevOps and to fetch an engineer.
        // The message here is the one ado-upload.mjs really prints for a dry run.
        AdoUploadProbe.finished(ADO_ID, ADO_CASE, hook("PROBELAUF",
            "Probelauf: 4 Datei(en) wären angehängt worden — es wurde NICHTS nach ADO "
                + "geschrieben."), 0);
        settle();
        System.out.println("  Upload-Zeile: " + flow.uploadStateText());
        check("Ein Probelauf sagt, dass er ein Probelauf war",
            flow.uploadStateText().contains("ADO-Upload PROBELAUF"), flow.uploadStateText());
        check("… und nennt das ausdruecklich keinen Fehler",
            flow.uploadStateText().contains("kein Fehler"), flow.uploadStateText());
        check("… und ist NICHT rot — genau das war der Defekt",
            !flow.uploadStateColour().equals(failColour), flow.uploadStateColour());
        check("… und sieht aus wie AUS und UEBERSPRUNGEN, die anderen beiden \"nichts "
            + "passiert, und das ist in Ordnung\"-Zustaende",
            "#fff4d8".equals(flow.uploadStateColour()), flow.uploadStateColour());
        check("… und behauptet nicht, etwas sei angekommen",
            !flow.uploadStateText().contains("angekommen"), flow.uploadStateText());
        check("… und fordert die Testerin nicht auf, jemanden zu holen",
            !flow.uploadStateText().contains("Testautomatisierung melden"),
            flow.uploadStateText());
        check("Auch das Banner nennt es einen Probelauf und keinen Fehler",
            flow.bannerText().contains("PROBELAUF") && !flow.bannerText().contains("FEHLER"),
            flow.bannerText());
        noWindows("nach dem Probelauf");
        shoot(view, "24b-ado-upload-probelauf");

        // --- the tester is on another step when the upload lands ---------------------
        clickLabelContaining(view, "Testfall wählen");
        settle();
        check("Zurueck auf Schritt 1", flow.currentStep() == 0, "step=" + flow.currentStep());
        AdoUploadProbe.finished(ADO_ID, ADO_CASE, hook("OK",
            "ADO-Lauf 25518996 angelegt, 4 Datei(en) angehängt."), 0);
        settle();
        check("Ein Upload erreicht die Testerin auch auf einem anderen Schritt",
            flow.bannerText().contains("ADO-Upload OK"), flow.bannerText());
        check("… und die Einzelheiten warten auf Schritt 3 auf sie",
            flow.uploadStateText().contains("25518996"), flow.uploadStateText());
        noWindows("auf Schritt 1");
        shoot(view, "25-ado-upload-auf-schritt-1");
    }

    /**
     * The run watcher, armed by construction alone — no screen opened, no project loaded.
     *
     * <p>The gap this closes: a tester who reopens Studio and presses <b>F6</b> to re-run an
     * existing test case never opens this screen, so arming from {@code createPanel()} left the
     * run unnoticed and nothing reached Azure DevOps. Studio constructs every panel entry class
     * at startup to read its title ({@code StudioPanelPlugins.discover}), so the constructor is
     * as early as a plugin gets — and this asserts the arming really happens there, before any
     * {@code createPanel()} call, and that the constructor survives having no project.
     */
    private static void scenarioArmed() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());
        check("Der Waechter ist beim Start nicht scharf", !AdoRunWatcher.isArmed(),
            "isArmed=" + AdoRunWatcher.isArmed());
        check("Es ist kein Projekt offen — genau die Lage beim Start von Studio",
            AdoRunWatcher.resultsRoot() == null, String.valueOf(AdoRunWatcher.resultsRoot()));

        // Exactly what StudioPanelPlugins.discover does: construct it, and ask for the title.
        GuidedFlowPanel flow = new GuidedFlowPanel();
        String title = flow.getTitle();
        check("Der Konstruktor allein macht den Waechter scharf — ohne createPanel()",
            AdoRunWatcher.isArmed(), "isArmed=" + AdoRunWatcher.isArmed());
        check("… und ohne offenes Projekt zu brauchen", "Ablauf".equals(title), title);
        noWindows("nach dem blossen Konstruktor");

        // The second call must be gone, not merely harmless: two call sites read as "opening
        // the screen is what arms it", which is the belief that caused the bug.
        Path source = shotDir.getCanonicalFile().toPath().getParent().getParent()
            .resolve("src/main/java/de/ing/qa/panel/GuidedFlowPanel.java");
        check("Die Quelle ist da", Files.isRegularFile(source), source.toString());
        String src = Files.readString(source, java.nio.charset.StandardCharsets.UTF_8);
        int calls = src.split("AdoRunWatcher\\.arm\\(\\)", -1).length - 1;
        int armAt = src.indexOf("AdoRunWatcher.arm()");
        int ctorAt = src.indexOf("public GuidedFlowPanel()");
        int createAt = src.indexOf("public JComponent createPanel()");
        System.out.println("  arm()-Aufrufe: " + calls + "  (Konstruktor@" + ctorAt
            + ", arm@" + armAt + ", createPanel@" + createAt + ")");
        check("arm() wird genau einmal aufgerufen", calls == 1, "calls=" + calls);
        check("… und zwar im Konstruktor, vor createPanel()",
            ctorAt > 0 && armAt > ctorAt && createAt > armAt, "ctor<arm<createPanel");

        // And the screen still builds afterwards, with the watcher still running.
        JComponent view = build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();
        check("Der Bildschirm laesst sich danach ganz normal oeffnen",
            flow.headlineText().contains("Schritt 1 von 3"), flow.headlineText());
        check("Der Waechter laeuft weiter", AdoRunWatcher.isArmed(),
            "isArmed=" + AdoRunWatcher.isArmed());
        noWindows("wachhund");
        shoot(view, "26-wachhund-scharf-ohne-bildschirm");
    }

    /**
     * The hand-off: a tester turns their own recording into one file, without a command line.
     *
     * <p>Proves the whole button, not the panel's opinion of it: the real
     * {@code tools/handoff-pack.mjs} is invoked, the package it writes is opened again with the
     * tool's own {@code inspect}, and the manifest is read to show that the three things that
     * make a hand-off safe survived the trip through a Swing button — the saved browser session
     * stayed behind, {@code Results/} stayed behind, and the manifest names the ADO case and the
     * customer profile the engineer will need.
     *
     * <p>Without a {@code node} on the machine none of that can be tested, and this says so
     * rather than passing.
     */
    private static void scenarioHandoff() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());
        Path project = envPath("ING_INGENIOUS_PROJECT");
        Path out = envPath("ING_HANDOFF_OUT");
        check("Ein aufgenommenes Projekt liegt bereit",
            project != null && Files.isDirectory(project.resolve("TestPlan")),
            String.valueOf(project));
        check("Der Abgabe-Ordner ist leer, bevor irgendetwas gedrueckt wurde",
            out != null && zipCount(out) == 0, out + " -> " + zipCount(out));

        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();
        toStep3(flow, view);
        settle();

        // --- the button is there, before it is pressed -------------------------------
        System.out.println("  Abgabe-Zeile vorher: " + flow.handoffStateText());
        check("Der Abgabe-Knopf steht auf Schritt 3, wo die Testerin ohnehin schon ist",
            buttonEnabled(view, "Aufnahme abgeben"), "vorhanden und benutzbar");
        check("Vor dem Druck steht da, wofuer der Knopf gut ist",
            flow.handoffStateText().contains("an die Testautomatisierung senden"),
            flow.handoffStateText());
        check("… und dass der Ordner nicht selbst gezippt werden soll",
            flow.handoffStateText().contains("nicht selbst zippen"), flow.handoffStateText());
        noWindows("vor der Abgabe");
        shoot(view, "27-abgabe-knopf");

        if (!nodePresent()) {
            skip("Die Abgabe selbst", "kein node auf diesem Rechner — nicht pruefbar");
            return;
        }

        // --- press it ----------------------------------------------------------------
        flow.pressHandoff();
        check("Die Abgabe meldet sich, BEVOR gewartet wird",
            flow.handoffStateText().contains("wird erstellt"), flow.handoffStateText());
        check("… und der Knopf ist waehrenddessen gesperrt", !flow.handoffButtonEnabled(),
            "enabled=" + flow.handoffButtonEnabled());
        check("Die Abgabe hat geantwortet", flow.awaitHandoff(180_000), "gemeldet");
        settle();

        System.out.println("  Abgabe-Zeile: " + flow.handoffStateText());
        check("Ein Paket wurde erstellt", flow.handoffStateText().startsWith("✔"),
            flow.handoffStateText());
        check("… die Zeile nennt die Datei beim Namen",
            flow.handoffStateText().contains(".zip"), flow.handoffStateText());
        check("… und den Ordner, in dem sie liegt",
            flow.handoffStateText().contains(out.toString()), flow.handoffStateText());
        check("… und sagt, was damit zu tun ist",
            flow.handoffStateText().contains("an die Testautomatisierung senden"),
            flow.handoffStateText());
        // The line clips rather than wraps, and Studio is not always 1500 pixels wide. Whatever
        // survives the clip has to be the instruction, not the explanation.
        check("… und die Anweisung steht so weit vorn, dass ein schmales Fenster sie nicht "
            + "abschneidet",
            flow.handoffStateText().indexOf("senden") < 120,
            "at " + flow.handoffStateText().indexOf("senden"));
        check("… und dass Anmeldung und Ergebnis-Dateien nicht mitgehen",
            flow.handoffStateText().contains("nicht enthalten"), flow.handoffStateText());
        check("Erfolg ist gruen hinterlegt", "#e3f6e3".equals(flow.handoffStateColour()),
            flow.handoffStateColour());
        check("Auch das Banner meldet die Abgabe",
            flow.bannerText().contains("Paket wurde erstellt"), flow.bannerText());
        check("Die ganze Meldung bleibt im Tooltip erhalten",
            flow.handoffStateTooltip().contains(out.toString()), flow.handoffStateTooltip());
        check("Der Knopf ist danach wieder benutzbar", flow.handoffButtonEnabled(), "enabled");
        noWindows("nach der Abgabe");
        shoot(view, "28-abgabe-paket-erstellt");

        // --- the package really exists, where the tester was told ---------------------
        Path zip = flow.handoffZipPath().isEmpty() ? null : Paths.get(flow.handoffZipPath());
        check("Die genannte Datei liegt wirklich da", zip != null && Files.isRegularFile(zip),
            String.valueOf(zip));
        check("… und zwar in dem Ordner, den die Zeile nennt",
            zip != null && out.equals(zip.getParent()), String.valueOf(zip));
        check("Es entstand genau EIN Paket", zipCount(out) == 1, "zips=" + zipCount(out));

        // --- and the tool itself accepts it ------------------------------------------
        String[] inspected = runNode("inspect", zip.toString());
        System.out.println("  inspect exit=" + inspected[0]);
        check("handoff-pack inspect nimmt das Paket an", "0".equals(inspected[0]),
            inspected[0] + " / " + firstLines(inspected[1], 2));

        Object root = Json.parse(inspected[1]);
        Map<?, ?> manifest = root instanceof Map<?, ?> m ? m : Map.of();
        check("Das Paket traegt das erwartete Manifest",
            "ing-qa/handoff@1".equals(String.valueOf(manifest.get("schema"))),
            String.valueOf(manifest.get("schema")));
        check("Das Manifest nennt die ADO-Nummer, an der die Testerin gearbeitet hat",
            String.valueOf(manifest.get("adoTestCases")).contains("3951650"),
            String.valueOf(manifest.get("adoTestCases")));
        check("… und das Kundenprofil, das der Testfall braucht",
            String.valueOf(manifest.get("customerProfiles")).contains("Partnertyp"),
            firstLines(String.valueOf(manifest.get("customerProfiles")), 1));
        check("… und den INGenious-Stand, gegen den aufgenommen wurde",
            manifest.get("ingenious") != null, String.valueOf(manifest.get("ingenious")));

        List<String> packed = paths(manifest.get("files"));
        List<String> left = paths(manifest.get("excluded"));
        System.out.println("  eingepackt:      " + packed);
        System.out.println("  zurueckgelassen: " + left);
        check("Die gespeicherte Anmeldung bleibt auf dem Rechner der Testerin",
            !packed.contains("login.json") && left.contains("login.json"),
            String.valueOf(left));
        check("Die Ergebnis-Dateien bleiben ebenfalls zurueck",
            packed.stream().noneMatch(p -> p.startsWith("Results/")) && left.contains("Results/"),
            String.valueOf(left));
        check("Der Testfall selbst reist mit",
            packed.stream().anyMatch(p -> p.startsWith("TestPlan/")), String.valueOf(packed));

        // --- a running recording is not a finished one -------------------------------
        AppMainFrame studio = new AppMainFrame();
        TestCaseComponent core = studio.getTestDesign().getTestCaseComp();
        core.record();
        core.simulateRecorderReady();
        poll(flow);
        check("Es laeuft jetzt eine Aufnahme", core.getToolBar().isRecording(), "isRecording=true");
        flow.pressHandoff();
        settle();
        System.out.println("  Abgabe-Zeile waehrend der Aufnahme: " + flow.handoffStateText());
        check("Waehrend einer laufenden Aufnahme wird nichts abgegeben",
            flow.handoffStateText().contains("Aufnahme läuft noch"), flow.handoffStateText());
        check("… und es steht da, was zuerst zu tun ist",
            flow.handoffStateText().contains("Aufnahme beenden"), flow.handoffStateText());
        check("… es entsteht kein zweites Paket", zipCount(out) == 1, "zips=" + zipCount(out));
        check("… und der Knopf bleibt benutzbar", flow.handoffButtonEnabled(), "enabled");
        noWindows("waehrend der Aufnahme");
        shoot(view, "29-abgabe-waehrend-der-aufnahme");
        studio.dispose();
    }

    /**
     * A Fachbereich device as it is documented today: an install folder and a launcher, and
     * no repo checkout — so no {@code tools/handoff-pack.mjs} to call.
     *
     * <p>Not a hypothetical. {@code tools/ingenious-launch.ps1}, the launcher the Fachbereich
     * handout tells a colleague to double-click, sets {@code JAVA_HOME} and {@code PATH} and
     * nothing else; {@code ING_QA_REPO} is set only by the team laptop's own
     * {@code start-studio-panels.cmd}. A button that looked ready and then failed on the press
     * would be the fourth invisible state this project has paid for, so it says so first.
     */
    private static void scenarioHandoffNoTool() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());
        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();
        toStep3(flow, view);
        settle();

        System.out.println("  Abgabe-Zeile: " + flow.handoffStateText());
        check("Ein Knopf, der nicht kann, sieht auch nicht benutzbar aus",
            !flow.handoffButtonEnabled(), "enabled=" + flow.handoffButtonEnabled());
        check("… und die Zeile sagt es, BEVOR die Testerin drueckt",
            flow.handoffStateText().contains("nicht möglich"), flow.handoffStateText());
        check("… sie sagt, dass der Ordner NICHT selbst gezippt werden soll",
            flow.handoffStateText().contains("NICHT selbst zippen"), flow.handoffStateText());
        check("… und an wen es zu melden ist",
            flow.handoffStateText().contains("Testautomatisierung"), flow.handoffStateText());
        check("Sie ist rot — nicht das beruhigende Gelb der harmlosen Zustaende",
            "#fde7e7".equals(flow.handoffStateColour()), flow.handoffStateColour());

        flow.pressHandoff();
        settle();
        check("Ein Druck auf den gesperrten Knopf tut nichts und behauptet nichts",
            flow.handoffZipPath().isEmpty() && !flow.handoffStateText().contains("wird erstellt"),
            flow.handoffStateText());
        noWindows("ohne Werkzeug");
        shoot(view, "31-abgabe-nicht-eingerichtet");
    }

    /**
     * The other half of the same device question: the tool is there, {@code node} is not.
     *
     * <p>Node is documented for the team's own working laptop and for the ADO runbook, never
     * for a Fachbereich device — and it cannot be found out without starting it, so this is the
     * one failure that can only appear on the press. {@code ING_NODE} points at an executable
     * that does not exist, which is exactly what a machine without node does to
     * {@code ProcessBuilder.start()}.
     */
    private static void scenarioHandoffNoNode() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());
        Path out = envPath("ING_HANDOFF_OUT");
        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();
        toStep3(flow, view);
        settle();

        check("Das Werkzeug ist da, also ist der Knopf benutzbar",
            flow.handoffButtonEnabled(), "enabled");
        flow.pressHandoff();
        check("Die Abgabe hat geantwortet", flow.awaitHandoff(60_000), "gemeldet");
        settle();

        String line = flow.handoffStateText();
        System.out.println("  Abgabe-Zeile: " + line);
        check("Ohne node entsteht kein Paket — und der Knopf wirkt trotzdem nicht kaputt",
            line.startsWith("✖ KEIN Paket erstellt"), line);
        check("… es steht da, dass der Ordner NICHT selbst gezippt werden soll",
            line.contains("NICHT selbst zippen"), line);
        check("… und woran es liegt, ohne dass die Testerin es reparieren soll",
            line.contains("Node.js"), line);
        check("… kein Stapelspeicherauszug auf dem Bildschirm",
            !line.contains("Exception") && !line.contains("CreateProcess"), line);
        check("Sie ist rot", "#fde7e7".equals(flow.handoffStateColour()),
            flow.handoffStateColour());
        check("Es liegt nichts im Abgabe-Ordner", out != null && zipCount(out) == 0,
            out + " -> zips=" + zipCount(out));
        check("Der Knopf ist danach wieder benutzbar", flow.handoffButtonEnabled(), "enabled");
        noWindows("ohne node");
        shoot(view, "32-abgabe-ohne-node");
    }

    // ------------------------------------------------------------------ plumbing

    /** An environment path, or {@code null} when it is unset — never a guess. */
    private static Path envPath(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Paths.get(value.trim());
    }

    /** How many packages are lying in the hand-off folder right now. */
    private static int zipCount(Path dir) {
        // No directory to look in is NOT "the directory is empty". ING_HANDOFF_OUT unset gave
        // dir == null and this answered 0, which is the passing answer for every "nothing was
        // handed off" check in this file — a harness condition wearing a product result. A
        // directory that does not exist yet does mean nothing was written, and still answers 0.
        if (dir == null) {
            return -1;
        }
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(p -> p.getFileName().toString().endsWith(".zip")).count();
        } catch (Exception ex) {
            return -1;
        }
    }

    /** The {@code path} field of every entry in a manifest list. */
    private static List<String> paths(Object list) {
        List<String> out = new ArrayList<>();
        if (list instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> entry) {
                    out.add(String.valueOf(entry.get("path")));
                }
            }
        }
        return out;
    }

    /** Whether this machine has a node at all — the one thing the button cannot work without. */
    private static boolean nodePresent() {
        try {
            Process proc = new ProcessBuilder(node(), "--version").redirectErrorStream(true).start();
            proc.waitFor();
            return proc.exitValue() == 0;
        } catch (Exception ex) {
            System.out.println("  node nicht startbar: " + ex);
            return false;
        }
    }

    private static String node() {
        String explicit = System.getenv("ING_NODE");
        return explicit == null || explicit.isBlank() ? "node" : explicit.trim();
    }

    /** Runs the real hand-off tool. Returns {@code {exit, output}}. */
    private static String[] runNode(String... args) throws Exception {
        Path repo = shotDir.getCanonicalFile().toPath().getParent().getParent().getParent();
        List<String> command = new ArrayList<>(List.of(node(),
            repo.resolve("tools/handoff-pack.mjs").toString()));
        command.addAll(List.of(args));
        Process proc = new ProcessBuilder(command).directory(repo.toFile()).start();
        String output = new String(proc.getInputStream().readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        proc.waitFor();
        return new String[] { String.valueOf(proc.exitValue()), output };
    }

    /** The test case and its ADO id, as the fixture spells them. */
    private static final String ADO_ID = "3951650";
    private static final String ADO_CASE = "TC_" + ADO_ID + "_Partner-Suche";

    /** One line of {@code ado-upload.mjs} stdout, in the shape the hook really prints it. */
    private static String hook(String code, String message) {
        return "{\"status\":\"" + code + "\"}\nADO-UPLOAD " + code + " " + message + "\n";
    }

    /** Lays the panel out at another window size, because a clipped line reads differently. */
    private static void resizeTo(int width, int height) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame.setSize(width, height);
            frame.validate();
        });
        settle();
    }

    /** Walks the flow to step 3 the way a tester does, so the recording step is on screen. */
    private static void toStep3(GuidedFlowPanel flow, JComponent view) throws Exception {
        JList<?> list = (JList<?>) find(view, JList.class, null);
        select(list, 0);
        click(view, "Diesen Testfall übernehmen");
        settle();
        JTable table = (JTable) find(view, JTable.class, null);
        table.setRowSelectionInterval(0, 0);
        click(view, "Kontonummer kopieren");
        settle();
        check("Schritt 3 ist erreicht", flow.currentStep() == 2, "step=" + flow.currentStep());
    }

    /** Re-reads Studio's state on the Event Dispatch Thread, as the once-a-second poll does. */
    private static void poll(GuidedFlowPanel flow) throws Exception {
        SwingUtilities.invokeAndWait(flow::pollRecorderState);
        settle();
    }

    /** One start attempt through the package-private recorder, on the EDT where it belongs. */
    private static String probeStart() throws Exception {
        AtomicReference<String> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(RecorderProbe.start()));
        return out.get();
    }

    /** One stop attempt through the package-private recorder. */
    private static String probeStop() throws Exception {
        AtomicReference<String> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(RecorderProbe.stop()));
        return out.get();
    }


    /** Builds the panel on the EDT and packs it into a frame that is never shown. */
    private static JComponent build(GuidedFlowPanel flow) throws Exception {
        AtomicReference<JComponent> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JComponent view = flow.createPanel();
            frame = new JFrame("harness (never shown)");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(view);
            // Lays the hierarchy out for real without ever putting a window on screen.
            frame.pack();
            frame.setSize(1500, 950);
            frame.validate();
            ref.set(view);
        });
        return ref.get();
    }

    /** Lets every queued Swing task run, twice — the flow uses invokeLater on purpose. */
    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(120);
        }
    }

    /**
     * Fails if ANY window is showing. A modal dialog keeps the Event Dispatch Thread
     * pumping, so liveness is no evidence — the window list is.
     */
    private static void noWindows(String where) {
        List<String> showing = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) {
                showing.add(w.getClass().getSimpleName()
                    + (w instanceof java.awt.Dialog d ? " \"" + d.getTitle() + "\"" : ""));
            }
        }
        check("Kein Dialog oeffnet sich (" + where + ")", showing.isEmpty(), String.valueOf(showing));
    }

    private static void shoot(JComponent view, String name) throws Exception {
        File out = new File(shotDir, name + ".png");
        SwingUtilities.invokeAndWait(() -> {
            // An unshown frame has no RepaintManager pumping revalidate(), so a banner that
            // just became visible would still have zero height. Lay it out by hand, or the
            // screenshots quietly all look the same — which is how one nearly shipped.
            if (frame != null) {
                frame.validate();
            }
            BufferedImage image = new BufferedImage(
                Math.max(view.getWidth(), 100), Math.max(view.getHeight(), 100),
                BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            view.printAll(g);
            g.dispose();
            try {
                ImageIO.write(image, "png", out);
            } catch (Exception ex) {
                System.out.println("  screenshot failed: " + ex);
            }
        });
        System.out.println("  screenshot: " + out.getAbsolutePath());
    }

    private static String clipboard() {
        try {
            return String.valueOf(java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .getData(DataFlavor.stringFlavor));
        } catch (Exception ex) {
            return "(nicht lesbar: " + ex + ")";
        }
    }

    private static void select(JList<?> list, int index) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        });
    }

    private static void click(Component root, String text) throws Exception {
        AbstractButton button = (AbstractButton) find(root, AbstractButton.class, text);
        if (button == null) {
            check("Knopf \"" + text + "\" existiert", false, "nicht gefunden");
            return;
        }
        SwingUtilities.invokeAndWait(button::doClick);
    }

    private static boolean buttonEnabled(Component root, String text) {
        AbstractButton button = (AbstractButton) find(root, AbstractButton.class, text);
        return button != null && button.isEnabled();
    }

    /** Clicks a step chip the way a tester does — the chips are labels, not buttons. */
    private static void clickLabelContaining(Component root, String text) throws Exception {
        JLabel label = labelContaining(root, text);
        if (label == null) {
            check("Schritt-Markierung \"" + text + "\" existiert", false, "nicht gefunden");
            return;
        }
        SwingUtilities.invokeAndWait(() -> label.dispatchEvent(new MouseEvent(
            label, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)));
    }

    private static JLabel labelContaining(Component c, String text) {
        if (c instanceof JLabel l && l.getText() != null && l.getText().contains(text)) {
            return l;
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                JLabel found = labelContaining(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String labelTextContaining(Component c, String text) {
        JLabel label = labelContaining(c, text);
        return label == null || !label.isVisible() ? null : label.getText();
    }

    private static Component find(Component c, Class<?> type, String text) {
        if (type.isInstance(c)) {
            if (text == null) {
                return c;
            }
            if (c instanceof AbstractButton b && text.equals(b.getText())) {
                return c;
            }
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = find(child, type, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * One cell, addressed by the caption a tester reads — so a check about a column is really
     * about that column, and not about any digit or bracket anywhere in the row.
     *
     * @return the cell's text, or {@code null} when no column carries that caption, which is a
     *     fact about the harness and must not read as a passing value
     */
    private static String cell(JTable table, int row, String caption) {
        if (table == null || row >= table.getRowCount()) {
            return null;
        }
        for (int c = 0; c < table.getColumnCount(); c++) {
            if (caption.equals(String.valueOf(table.getColumnName(c)))) {
                Object value = table.getValueAt(row, c);
                return value == null ? "" : String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static String quoted(String text) {
        return text == null ? "(keine solche Spalte)" : "\"" + text + "\"";
    }

    private static String tableRow(JTable table, int row) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < table.getColumnCount(); c++) {
            sb.append(table.getValueAt(row, c)).append(" | ");
        }
        return sb.toString();
    }

    private static String firstLines(String text, int count) {
        String[] lines = text.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(count, lines.length); i++) {
            sb.append(lines[i]).append(i + 1 < Math.min(count, lines.length) ? " / " : "");
        }
        return sb.toString();
    }

    private static String indent(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\R")) {
            sb.append("    ").append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Records that something could not be tested here.
     *
     * <p>Never a pass and never a failure: a harness that cannot run a check has learned
     * nothing, and both of the other verdicts would claim it learned something.
     */
    private static void skip(String what, String why) {
        skipped++;
        System.out.println("  SKIP " + what + "   [" + why + "]");
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + "   [" + detail + "]");
    }
}
