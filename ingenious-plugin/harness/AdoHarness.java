import de.ing.qa.ado.AdoCache;
import de.ing.qa.panel.TestCaseChooserPanel;
import de.ing.qa.panel.TestCaseOverviewPanel;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Headless proof for the two ADO panels. Drives the REAL Swing components through
 * the REAL user actions — types into the search field, clicks the buttons, reads the
 * rendered text back — so the evidence is the panel's own behaviour, not a stub of it.
 *
 * <p>Scenarios (argv[0]), each run in its own JVM because the paths come from the
 * environment, which a running JVM cannot change:
 *
 * <ul>
 *   <li>{@code cache} — fixture cache present: list, filter, select, persist, render,
 *       confirm the take visibly, keep it sticky across a panel rebuild, and resolve the
 *       Azure-DevOps link both ways (ADO's own href and the constructed fallback).
 *   <li>{@code empty} — no cache, no reachable tool: both panels must report and survive.
 *   <li>{@code toolfail} — tool present but exits non-zero: refresh must report and survive.
 *   <li>{@code nourl} — cache without per-case url AND without org/project: the ADO
 *       action must be disabled with a German reason, never open a guessed URL.
 *   <li>{@code writefail} — the selection path cannot be written: the panel must SAY so.
 * </ul>
 */
public class AdoHarness {

    private static int failures;
    private static int checks;

    /**
     * Why the scenario could not be run here, or {@code null} when it ran.
     *
     * <p>{@code badpath} is only meaningful on a filesystem that rejects the path it builds,
     * which is Windows. Until 2026-07-28 the not-applicable path simply {@code return}ed, and
     * the summary below then printed <b>"RESULT: GREEN — 0 checks passed"</b> and exited 0 —
     * a scenario that asked nothing reporting as a scenario that passed. Skipping is a third
     * answer and has to print and exit as one.
     */
    private static String notApplicable;

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "cache";
        System.out.println("=== AdoHarness scenario: " + scenario + " ===");
        System.out.println("ING_ADO_CACHE        = " + System.getenv("ING_ADO_CACHE"));
        System.out.println("ING_TESTCASE_SELECTION = " + System.getenv("ING_TESTCASE_SELECTION"));
        System.out.println("ING_QA_REPO          = " + System.getenv("ING_QA_REPO"));
        System.out.println();

        switch (scenario) {
            case "cache" -> scenarioCache();
            case "empty" -> scenarioEmpty();
            case "toolfail" -> scenarioToolFail();
            case "nourl" -> scenarioNoUrl();
            case "writefail" -> scenarioWriteFail();
            case "badpath" -> scenarioBadPath();
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
        if (notApplicable != null) {
            System.out.println("RESULT: NICHT ANWENDBAR — " + notApplicable
                + " Nothing was proved here; this is not a pass.");
            System.exit(3);
        }
        if (checks == 0) {
            System.out.println("RESULT: NICHTS GEPRUEFT — the scenario ran no checks at all, "
                + "so there is nothing to be green about.");
            System.exit(3);
        }
        System.out.println("RESULT: GREEN — " + checks + " checks passed");
        System.exit(0);
    }

    // ------------------------------------------------------------------ scenarios

    /** Fixture cache present: the chooser lists, filters, selects and persists. */
    private static void scenarioCache() throws Exception {
        Files.deleteIfExists(AdoCache.selectionPath());

        TestCaseChooserPanel chooser = new TestCaseChooserPanel();
        check("Chooser-Titel", "Testfall wählen".equals(chooser.getTitle()), chooser.getTitle());
        JComponent panel = chooser.createPanel();
        check("Chooser baut ein Panel", panel != null, panel == null ? "null" : panel.getClass().getSimpleName());
        check("Chooser-Laden abgeschlossen", chooser.awaitSettled(10_000), "settled");

        JList<?> list = (JList<?>) find(panel, JList.class, null);
        check("Chooser hat eine Liste", list != null, String.valueOf(list != null));
        System.out.println("  Status: " + statusText(panel));
        System.out.println("  Liste (ungefiltert):");
        for (String row : rows(list)) {
            System.out.println("    " + row);
        }
        check("Liste zeigt beide Testfaelle", rows(list).size() == 2, "rows=" + rows(list).size());
        check("Liste zeigt ID, Titel und Suite",
            rows(list).get(0).contains("3951650")
                && rows(list).get(0).contains("Partner-Suche")
                && rows(list).get(0).contains("[Partner-Suche Suite]"),
            rows(list).get(0));

        // --- filter by free text -------------------------------------------------
        typeSearch(panel, "Ueberweisung");
        clickButton(panel, "Suchen");
        System.out.println("  Suche 'Ueberweisung' -> " + rows(list));
        check("Suche filtert auf einen Treffer", rows(list).size() == 1, "rows=" + rows(list).size());
        check("Der Treffer ist 3951651", rows(list).get(0).contains("3951651"), rows(list).get(0));

        // Multi-token search: every token must hit, across id/title/suite.
        typeSearch(panel, "partner 360");
        clickButton(panel, "Suchen");
        System.out.println("  Suche 'partner 360' -> " + rows(list));
        check("Mehrwort-Suche findet 3951650", rows(list).size() == 1 && rows(list).get(0).contains("3951650"),
            String.valueOf(rows(list)));

        typeSearch(panel, "3951651");
        clickButton(panel, "Suchen");
        check("Suche nach ADO-ID trifft", rows(list).size() == 1 && rows(list).get(0).contains("3951651"),
            String.valueOf(rows(list)));

        typeSearch(panel, "gibtesnicht");
        clickButton(panel, "Suchen");
        check("Suche ohne Treffer leert die Liste, ohne zu crashen", rows(list).isEmpty(),
            "rows=" + rows(list).size() + " status=" + statusText(panel));

        clickButton(panel, "Zurücksetzen");
        check("Zuruecksetzen zeigt wieder alle", rows(list).size() == 2, "rows=" + rows(list).size());

        // --- detail pane ---------------------------------------------------------
        selectRow(list, 0);
        String detail = textAreaText(panel);
        check("Detail zeigt Voraussetzungen", detail.contains("Voraussetzungen")
            && detail.contains("aktives Girokonto"), firstLines(detail, 3));

        // --- take the case -------------------------------------------------------
        typeSearch(panel, "Ueberweisung");
        clickButton(panel, "Suchen");
        selectRow(list, 0);
        clickButton(panel, "Diesen Testfall übernehmen");
        Path selection = AdoCache.selectionPath();
        check("Auswahl wurde gespeichert", Files.isRegularFile(selection), selection.toString());
        String saved = Files.isRegularFile(selection)
            ? Files.readString(selection, StandardCharsets.UTF_8) : "";
        System.out.println("  " + selection + ":");
        System.out.println(indent(saved));
        check("Gespeicherte ADO-ID ist 3951651", saved.contains("\"adoId\": \"3951651\""), firstLines(saved, 6));
        check("AdoCache liest die Auswahl zurueck", "3951651".equals(AdoCache.readSelectedId()),
            String.valueOf(AdoCache.readSelectedId()));
        check("Statuszeile bestaetigt die Uebernahme", statusText(panel).contains("3951651"), statusText(panel));

        // --- DEFECT 1: the take must be UNMISTAKABLE, not merely successful ------
        // The tester reported "nothing happens" while the file was demonstrably being
        // written. So a silent success is a failure: assert every visible signal.
        String confirmation = chooser.confirmationMessage();
        System.out.println("  Bestaetigungs-Banner: " + confirmation);
        check("Ein Bestaetigungs-Banner erscheint", !confirmation.isBlank(), confirmation);
        check("Das Banner nennt die uebernommene ADO-ID", confirmation.contains("3951651"), confirmation);
        check("Das Banner nennt den naechsten Schritt (Testfall-Uebersicht)",
            confirmation.contains("Testfall-Übersicht"), confirmation);
        check("Statuszeile nennt ebenfalls den naechsten Schritt",
            statusText(panel).contains("Testfall-Übersicht"), statusText(panel));
        check("Die Statuszeile ist auf Deutsch und sagt 'uebernommen'",
            statusText(panel).contains("übernommen"), statusText(panel));
        check("Der Uebernehmen-Knopf zeigt jetzt 'Bereits übernommen'",
            buttonText(panel, "✔ Bereits übernommen") != null, "gefunden");
        String rendered = renderedRow(list, indexOf(rows(list), "3951651"));
        System.out.println("  Gerenderte Zeile: " + rendered);
        check("Die Liste markiert die uebernommene Zeile mit einem Haken",
            rendered.startsWith("✔"), rendered);
        check("Das Detail nennt den Testfall als uebernommen",
            textAreaText(panel).contains("ÜBERNOMMEN"), firstLines(textAreaText(panel), 1));

        // --- DEFECT 2: the Azure-DevOps link ------------------------------------
        // 3951651 carries NO url in the cache -> constructed from org/project.
        check("ADO-Aktion ist fuer die Auswahl aktiv", chooser.adoActionEnabled(),
            "enabled=" + chooser.adoActionEnabled());
        System.out.println("  ADO-Link (konstruiert): " + chooser.selectedWebUrl());
        check("Konstruierter Link nutzt Org und Projekt aus der Datei",
            "https://dev.azure.com/beispiel-org/BeispielProjekt/_workitems/edit/3951651"
                .equals(chooser.selectedWebUrl()), String.valueOf(chooser.selectedWebUrl()));
        check("Der Link steht auch im Detailtext", textAreaText(panel).contains("ADO-Link:"),
            "ADO-Link im Detail");
        check("Die gespeicherte Auswahl traegt den Link mit",
            saved.contains("_workitems/edit/3951651"), firstLines(saved, 8));

        // 3951650 carries ADO's OWN href -> it must be used verbatim, not rebuilt.
        clickButton(panel, "Zurücksetzen");
        selectRow(list, 0);
        System.out.println("  ADO-Link (von ADO geliefert): " + chooser.selectedWebUrl());
        check("Von ADO gelieferter Link wird unveraendert uebernommen",
            "https://dev.azure.com/beispiel-org/11111111-1111-1111-1111-111111111111/_workitems/edit/3951650"
                .equals(chooser.selectedWebUrl()), String.valueOf(chooser.selectedWebUrl()));

        // --- the selection survives a rebuild of the panel ------------------------
        TestCaseChooserPanel rebuilt = new TestCaseChooserPanel();
        JComponent rebuiltPanel = rebuilt.createPanel();
        check("Neu gebautes Panel geladen", rebuilt.awaitSettled(10_000), "settled");
        System.out.println("  Nach Neuaufbau: " + statusText(rebuiltPanel));
        check("Neu gebautes Panel zeigt die Uebernahme weiterhin an",
            statusText(rebuiltPanel).contains("3951651"), statusText(rebuiltPanel));
        check("Neu gebautes Panel zeigt das Banner erneut",
            rebuilt.confirmationMessage().contains("3951651"), rebuilt.confirmationMessage());

        // --- overview renders exactly that case ----------------------------------
        TestCaseOverviewPanel overview = new TestCaseOverviewPanel();
        check("Overview-Titel", "Testfall-Übersicht".equals(overview.getTitle()), overview.getTitle());
        JComponent op = overview.createPanel();
        check("Overview-Laden abgeschlossen", overview.awaitSettled(10_000), "settled");
        String head = headerText(op);
        String bodyText = textAreaText(op);
        System.out.println("  Overview-Kopf: " + head);
        System.out.println(indent(bodyText));
        check("Overview zeigt den uebernommenen Testfall", head.contains("3951651")
            && head.contains("4-Augen-Prinzip"), head);
        check("Overview fuehrt mit VORAUSSETZUNGEN", bodyText.startsWith("VORAUSSETZUNGEN"), firstLines(bodyText, 1));
        check("Overview zeigt den Voraussetzungs-Text",
            bodyText.contains("ueberweisungsberechtigt") && bodyText.contains("Tageslimit"),
            firstLines(bodyText, 3));
        check("Overview nennt das ADO-Feld der Voraussetzungen",
            bodyText.contains("Microsoft.VSTS.TCM.SystemInfo"), firstLines(bodyText, 5));
        check("Overview zeigt Beschreibung und Schritte",
            bodyText.contains("BESCHREIBUNG") && bodyText.contains("SCHRITTE")
                && bodyText.contains("Ueberweisung erfassen"), "sections");
        check("Overview zeigt die Einordnung", bodyText.contains("Suite:") && bodyText.contains("Kunde-360 Suite"),
            "einordnung");
        check("Overview bietet die ADO-Aktion an", overview.adoActionEnabled(),
            "enabled=" + overview.adoActionEnabled());
        check("Overview kennt denselben konstruierten Link",
            "https://dev.azure.com/beispiel-org/BeispielProjekt/_workitems/edit/3951651".equals(overview.webUrl()),
            String.valueOf(overview.webUrl()));

        // --- the overview must pick up a NEW selection when it is shown again -----
        // This is the CardLayout case: the panel is built once and only made visible
        // again, so it has to re-read the file rather than keep what it rendered.
        typeSearch(panel, "Partner");
        clickButton(panel, "Suchen");
        selectRow(list, 0);
        clickButton(panel, "Diesen Testfall übernehmen");
        check("Zweite Uebernahme gespeichert", "3951650".equals(AdoCache.readSelectedId()),
            String.valueOf(AdoCache.readSelectedId()));
        overview.simulateBecameVisible();
        check("Overview-Neuladen abgeschlossen", overview.awaitSettled(10_000), "settled");
        String head2 = headerText(op);
        System.out.println("  Overview-Kopf nach Wechsel: " + head2);
        check("Overview zeigt nach dem Wechsel den NEUEN Testfall",
            head2.contains("3951650") && !head2.contains("3951651"), head2);
        check("Overview zeigt jetzt den von ADO gelieferten Link",
            "https://dev.azure.com/beispiel-org/11111111-1111-1111-1111-111111111111/_workitems/edit/3951650"
                .equals(overview.webUrl()), String.valueOf(overview.webUrl()));
    }

    /**
     * A cache with neither a per-case url nor org/project. Nothing can be built, so the
     * action must be OFF and say why — a guessed link that 404s is worse than no link.
     */
    private static void scenarioNoUrl() throws Exception {
        TestCaseChooserPanel chooser = new TestCaseChooserPanel();
        JComponent panel = chooser.createPanel();
        check("Chooser-Laden abgeschlossen", chooser.awaitSettled(10_000), "settled");
        JList<?> list = (JList<?>) find(panel, JList.class, null);
        check("Testfall geladen", rows(list).size() == 1, "rows=" + rows(list).size());
        selectRow(list, 0);
        System.out.println("  ADO-Link: " + chooser.selectedWebUrl());
        check("Kein Link vorhanden -> kein geratener Link", chooser.selectedWebUrl() == null,
            String.valueOf(chooser.selectedWebUrl()));
        check("Die ADO-Aktion ist deaktiviert", !chooser.adoActionEnabled(),
            "enabled=" + chooser.adoActionEnabled());
        String tip = chooser.adoActionTooltip();
        System.out.println("  Tooltip: " + tip);
        check("Der Tooltip erklaert es auf Deutsch",
            tip.contains("Kein Azure-DevOps-Link ermittelbar") && tip.contains("url"), tip);
        check("Der Tooltip nennt den Ausweg", tip.contains("Aus ADO aktualisieren"), tip);

        clickButton(panel, "Diesen Testfall übernehmen");
        TestCaseOverviewPanel overview = new TestCaseOverviewPanel();
        JComponent op = overview.createPanel();
        check("Overview-Laden abgeschlossen", overview.awaitSettled(10_000), "settled");
        check("Overview deaktiviert die ADO-Aktion ebenfalls", !overview.adoActionEnabled(),
            "enabled=" + overview.adoActionEnabled());
        check("Overview erklaert es auf Deutsch",
            overview.adoActionTooltip().contains("Kein Azure-DevOps-Link ermittelbar"),
            overview.adoActionTooltip());
        System.out.println("  Overview-Kopf: " + headerText(op));
    }

    /**
     * The write cannot succeed (the selection path is a non-empty directory). The panel
     * must show a RED banner naming the reason and the path — never a silent no-op.
     */
    private static void scenarioWriteFail() throws Exception {
        TestCaseChooserPanel chooser = new TestCaseChooserPanel();
        JComponent panel = chooser.createPanel();
        check("Chooser-Laden abgeschlossen", chooser.awaitSettled(10_000), "settled");
        JList<?> list = (JList<?>) find(panel, JList.class, null);
        selectRow(list, 0);
        clickButton(panel, "Diesen Testfall übernehmen");
        String banner = chooser.confirmationMessage();
        String status = statusText(panel);
        System.out.println("  Banner: " + banner);
        System.out.println("  Status: " + status);
        check("Der Fehlschlag erscheint als Banner", !banner.isBlank(), banner);
        check("Der Banner sagt auf Deutsch, dass NICHT gespeichert wurde",
            banner.contains("konnte NICHT gespeichert werden"), banner);
        // Named, not merely "an Exception". Almost every java.nio/java.io throwable's simple
        // name ends in "Exception", so contains("Exception") was true for any failure whatever,
        // which is not what "nennt den Grund" claims. The selection path here is a NON-EMPTY
        // DIRECTORY, so Files.move raises DirectoryNotEmptyException — the one reason this
        // scenario constructs. scenarioBadPath already does it this way.
        check("Der Banner nennt den Grund", banner.contains("DirectoryNotEmptyException"), banner);
        check("Der Banner nennt den Pfad", banner.contains("Datei: ")
            && banner.contains(String.valueOf(AdoCache.selectionPath().getFileName())), banner);
        check("Die Statuszeile sagt dasselbe", status.contains("konnte NICHT gespeichert werden"), status);
        check("Nichts wurde stillschweigend uebernommen", AdoCache.readSelectedId() == null,
            String.valueOf(AdoCache.readSelectedId()));
    }

    /**
     * The regression that made the button look genuinely dead: an illegal
     * {@code ING_TESTCASE_SELECTION} throws {@link java.nio.file.InvalidPathException},
     * which is UNCHECKED. {@code catch (IOException)} never saw it, so it escaped into
     * the EDT — the tester clicked and absolutely nothing appeared. Must now be a banner.
     */
    private static void scenarioBadPath() throws Exception {
        boolean throwsOnPath;
        try {
            AdoCache.selectionPath();
            throwsOnPath = false;
        } catch (RuntimeException ex) {
            throwsOnPath = true;
            System.out.println("  Pfad ist ungueltig: " + ex.getClass().getSimpleName());
        }
        if (!throwsOnPath) {
            notApplicable = "this filesystem accepts the path AdoCache.selectionPath() builds, "
                + "so there is no rejection for the panel to report. The scenario is "
                + "Windows-only by design.";
            System.out.println("  (Dieses Dateisystem akzeptiert den Pfad — Szenario hier nicht "
                + "anwendbar.)");
            return;
        }
        TestCaseChooserPanel chooser = new TestCaseChooserPanel();
        JComponent panel = chooser.createPanel();
        check("Chooser-Laden abgeschlossen", chooser.awaitSettled(10_000), "settled");
        JList<?> list = (JList<?>) find(panel, JList.class, null);
        selectRow(list, 0);
        clickButton(panel, "Diesen Testfall übernehmen");
        String banner = chooser.confirmationMessage();
        String status = statusText(panel);
        System.out.println("  Banner: " + banner);
        check("Auch ein UNGEPRUEFTER Fehler wird gemeldet, nicht verschluckt",
            banner.contains("konnte NICHT gespeichert werden"), banner);
        check("Der Banner nennt die Ausnahme", banner.contains("InvalidPathException"), banner);
        check("Die Statuszeile sagt dasselbe", status.contains("konnte NICHT gespeichert werden"), status);
        check("Das Panel lebt weiter", !rows(list).isEmpty(), "rows=" + rows(list).size());
    }

    /** No cache and no reachable tool: a message, a usable panel, and no exception. */
    private static void scenarioEmpty() throws Exception {
        TestCaseChooserPanel chooser = new TestCaseChooserPanel();
        JComponent panel = chooser.createPanel();
        check("Chooser baut auch ohne Cache ein Panel", panel != null, "ok");
        check("Chooser-Laden abgeschlossen", chooser.awaitSettled(10_000), "settled");
        String status = statusText(panel);
        System.out.println("  Chooser-Status: " + status);
        check("Chooser meldet fehlende Datei auf Deutsch",
            status.contains("Noch keine ADO-Testfaelle vorhanden"), status);
        check("Chooser nennt den Ausweg (Aktualisieren)", status.contains("Aus ADO aktualisieren"), status);
        JList<?> list = (JList<?>) find(panel, JList.class, null);
        check("Chooser-Liste ist leer statt kaputt", rows(list).isEmpty(), "rows=" + rows(list).size());

        // The panel must still respond: filtering an empty list is a no-op, not a throw.
        typeSearch(panel, "egal");
        clickButton(panel, "Suchen");
        check("Chooser bleibt nach Suche bedienbar", rows(list).isEmpty(), "rows=" + rows(list).size());

        // Refresh with no reachable tool.
        clickButton(panel, "Aus ADO aktualisieren");
        check("Refresh-Laden abgeschlossen", chooser.awaitSettled(30_000), "settled");
        String afterRefresh = statusText(panel);
        System.out.println("  Nach Aktualisieren: " + afterRefresh);
        check("Refresh meldet fehlendes Tool auf Deutsch",
            afterRefresh.contains("Aktualisieren nicht moeglich")
                && afterRefresh.contains("ING_QA_REPO"), afterRefresh);

        TestCaseOverviewPanel overview = new TestCaseOverviewPanel();
        JComponent op = overview.createPanel();
        check("Overview baut auch ohne Cache ein Panel", op != null, "ok");
        check("Overview-Laden abgeschlossen", overview.awaitSettled(10_000), "settled");
        String head = headerText(op);
        String bodyText = textAreaText(op);
        System.out.println("  Overview-Kopf: " + head);
        System.out.println(indent(bodyText));
        check("Overview meldet fehlende Datei auf Deutsch",
            bodyText.contains("Noch keine ADO-Testfaelle vorhanden"), bodyText);
        check("Overview-Kopf bleibt sprechend", head.contains("Kein Testfall"), head);
    }

    /**
     * Tool present but exits non-zero: the failure reaches the status line, nothing throws.
     *
     * <p><b>The sign-in state must be pinned by the caller.</b> Since
     * <a href="https://github.com/Wladefant/ing-qa-automation/issues/128">#128</a> the refresh
     * settles the Azure DevOps sign-in before it starts the tool, so on a signed-out machine
     * the tool is never started and there is no tool stderr to find. That made this scenario's
     * verdict a property of the developer's laptop rather than of the code. The runner sets
     * {@code ADO_BEARER} to open the gate deterministically; the first check below fails by
     * NAME if it ever stops doing so, rather than leaving a reader to work out why a German
     * sentence about signing in turned up where a tool error was expected.
     */
    private static void scenarioToolFail() throws Exception {
        String bearer = System.getenv("ADO_BEARER");
        check("Voraussetzung gesetzt: ADO_BEARER oeffnet das Anmelde-Gatter",
            bearer != null && !bearer.isBlank(),
            bearer == null || bearer.isBlank()
                ? "ADO_BEARER ist nicht gesetzt — ohne diese Vorbedingung entscheidet die "
                    + "Anmeldelage des Rechners ueber das Ergebnis, nicht der Code"
                : "gesetzt");
        TestCaseChooserPanel chooser = new TestCaseChooserPanel();
        JComponent panel = chooser.createPanel();
        check("Chooser-Laden abgeschlossen", chooser.awaitSettled(10_000), "settled");
        clickButton(panel, "Aus ADO aktualisieren");
        check("Refresh-Laden abgeschlossen", chooser.awaitSettled(60_000), "settled");
        String status = statusText(panel);
        System.out.println("  Nach fehlgeschlagenem Tool-Lauf: " + status);
        check("Fehlerausgabe des Tools landet in der Statuszeile",
            status.startsWith("Aktualisieren fehlgeschlagen"), status);
        check("Die Meldung enthaelt den Grund des Tools",
            status.contains("ADO nicht erreichbar"), status);
        JList<?> list = (JList<?>) find(panel, JList.class, null);
        check("Panel bleibt nach dem Fehlschlag bedienbar", list != null && rows(list).isEmpty(), "ok");
    }

    // ------------------------------------------------------------------ helpers

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what + "  ->  " + detail);
    }

    private static Component find(Component root, Class<?> type, String buttonText) {
        if (type.isInstance(root)) {
            if (buttonText == null
                || (root instanceof AbstractButton b && buttonText.equals(b.getText()))) {
                return root;
            }
        }
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                Component hit = find(child, type, buttonText);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static void collectLabels(Component root, List<String> out) {
        if (root instanceof JLabel l && l.getText() != null) {
            out.add(l.getText());
        }
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                collectLabels(child, out);
            }
        }
    }

    /** The status line: the LAST label, which is the one both panels put at the bottom. */
    private static String statusText(Component panel) throws Exception {
        AtomicReference<String> ref = new AtomicReference<>("");
        SwingUtilities.invokeAndWait(() -> {
            List<String> labels = new ArrayList<>();
            collectLabels(panel, labels);
            ref.set(labels.isEmpty() ? "" : labels.get(labels.size() - 1));
        });
        return ref.get();
    }

    /** The header line: the FIRST label of the overview panel. */
    private static String headerText(Component panel) throws Exception {
        AtomicReference<String> ref = new AtomicReference<>("");
        SwingUtilities.invokeAndWait(() -> {
            List<String> labels = new ArrayList<>();
            collectLabels(panel, labels);
            ref.set(labels.isEmpty() ? "" : labels.get(0));
        });
        return ref.get();
    }

    private static String textAreaText(Component panel) throws Exception {
        AtomicReference<String> ref = new AtomicReference<>("");
        SwingUtilities.invokeAndWait(() -> {
            JTextArea ta = (JTextArea) find(panel, JTextArea.class, null);
            ref.set(ta == null ? "" : ta.getText());
        });
        return ref.get();
    }

    private static List<String> rows(JList<?> list) throws Exception {
        List<String> out = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            if (list == null) {
                return;
            }
            for (int i = 0; i < list.getModel().getSize(); i++) {
                Object v = list.getModel().getElementAt(i);
                out.add(String.valueOf(v == null ? "" : invokeListLabel(v)));
            }
        });
        return out;
    }

    /** The model holds AdoTestCase; its listLabel() is what the renderer shows. */
    private static String invokeListLabel(Object v) {
        try {
            return String.valueOf(v.getClass().getMethod("listLabel").invoke(v));
        } catch (ReflectiveOperationException ex) {
            return String.valueOf(v);
        }
    }

    /** The text the REAL cell renderer paints for a row — where the ✔ marker lives. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static String renderedRow(JList<?> list, int index) throws Exception {
        AtomicReference<String> ref = new AtomicReference<>("");
        SwingUtilities.invokeAndWait(() -> {
            if (list == null || index < 0 || index >= list.getModel().getSize()) {
                return;
            }
            Component cell = ((javax.swing.ListCellRenderer) list.getCellRenderer())
                .getListCellRendererComponent(list, list.getModel().getElementAt(index), index, false, false);
            ref.set(cell instanceof JLabel l ? String.valueOf(l.getText()).trim() : String.valueOf(cell));
        });
        return ref.get();
    }

    private static int indexOf(List<String> rows, String needle) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    /** Finds a button by its exact label; null when no button carries it. */
    private static AbstractButton buttonText(Component panel, String text) throws Exception {
        AtomicReference<AbstractButton> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set((AbstractButton) find(panel, AbstractButton.class, text)));
        return ref.get();
    }

    private static void typeSearch(Component panel, String text) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField f = (JTextField) find(panel, JTextField.class, null);
            if (f != null) {
                f.setText(text);
            }
        });
    }

    private static void clickButton(Component panel, String text) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AbstractButton b = (AbstractButton) find(panel, AbstractButton.class, text);
            if (b == null) {
                throw new IllegalStateException("Button nicht gefunden: " + text);
            }
            b.doClick();
        });
    }

    private static void selectRow(JList<?> list, int index) throws Exception {
        SwingUtilities.invokeAndWait(() -> list.setSelectedIndex(index));
    }

    private static String firstLines(String s, int n) {
        String[] lines = s.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            sb.append(lines[i].trim()).append(i + 1 < Math.min(n, lines.length) ? " / " : "");
        }
        return sb.toString();
    }

    private static String indent(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\\R")) {
            sb.append("    | ").append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
