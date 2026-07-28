package unreadable;

import static unreadable.PanelHarnessKit.check;
import static unreadable.PanelHarnessKit.noWindows;
import static unreadable.PanelHarnessKit.poll;
import static unreadable.PanelHarnessKit.settle;
import static unreadable.PanelHarnessKit.shoot;

import com.ing.datalib.settings.RecorderSettings;
import com.ing.ide.main.mainui.AppMainFrame;
import de.ing.qa.ado.AdoCache;
import de.ing.qa.panel.GuidedFlowPanel;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import javax.swing.JComponent;

/**
 * Setting the recorder's start address from the panel, instead of by hand-editing a file.
 *
 * <p><b>The defect.</b> The tester handout carried a sentence telling testers that if the
 * browser opens on a blank page they should report it, because "the project's start address is
 * missing". That is a sentence excusing a quirk instead of naming a step: nobody in the room
 * could fix it either. {@code RecorderSettings.setStartUrl} has no caller anywhere in the core
 * — no Settings screen writes it — and {@code ProjectSettings.save()} does not even list the
 * recorder settings among the twelve groups it saves. A properties file edited by hand was the
 * only way in.
 *
 * <p><b>And it is reachable.</b> The per-test-case address the core prefers
 * ({@code RecordingTarget.getStartUrl()}) has no producer in this plugin either: the only
 * writer of {@code selected-testcase.json}, {@code AdoCache.writeSelection}, does not write a
 * {@code startUrl} key at all. So in the guided flow as shipped, the per-case address is always
 * absent, the project setting is empty on a fresh install, and the browser opens blank. The
 * handout sentence describes the default, not an edge case.
 *
 * <p><b>What this proves.</b> Not that a label changed — that a value typed into the panel is in
 * the properties file the recorder reads at start-up. The file is read here, by this harness,
 * with {@code java.util.Properties}, independently of anything the panel says about itself; and
 * a fresh settings object is constructed afterwards to stand in for the next Studio start.
 *
 * <p>All four outcomes of a write are reached, including the two that must not be reported as
 * success: a {@code save()} that swallows its own failure (which is what the product's
 * {@code PropUtils} really does with an {@code IOException}) and a {@code save()} that refuses.
 * A check that could only ever come out one way proves nothing, and this project has already
 * found eight of those.
 *
 * <pre>
 *   bash ingenious-plugin/harness/unreadable/run-start-address-harness.sh
 * </pre>
 */
public class StartAddressHarness {

    /** Neutral hosts. Nothing here may name a real environment. */
    private static final String GOOD = "https://app.example.org/start";
    private static final String PER_CASE = "https://case.example.org/start";
    private static final String LATER = "https://app.example.net/start";

    public static void main(String[] args) throws Exception {
        PanelHarnessKit.shotDir = new File(args.length > 0 ? args[0] : "target/harness-guided");
        PanelHarnessKit.shotDir.mkdirs();
        System.out.println("=== StartAddressHarness: die Start-Adresse aus dem Panel setzen ===");

        Path settingsFile = Path.of(System.getProperty(RecorderSettings.DIR_PROPERTY),
            "RecorderSettings.Properties");
        Files.deleteIfExists(settingsFile);
        Files.deleteIfExists(AdoCache.selectionPath());

        GuidedFlowPanel flow = new GuidedFlowPanel();
        JComponent view = PanelHarnessKit.build(flow);
        check("Testfaelle geladen", flow.awaitReady(15_000), "settled");
        settle();
        PanelHarnessKit.toStep3(flow, view);

        // --- no Studio: nothing is claimed and nothing is offered -----------------------
        check("Ohne Studio steht keine Start-Adresse da", flow.startUrlNoteText().isEmpty(),
            flow.startUrlNoteText());
        check("Ohne Studio wird das Eingabefeld gar nicht erst angeboten",
            !flow.startUrlEditorVisible(), "sichtbar=" + flow.startUrlEditorVisible());
        shoot(view, "50-startadresse-ohne-studio");

        // A press that gets through anyway must not claim anything either. This is the NONE
        // outcome: there is no project to write to, so nothing was written.
        flow.typeStartUrl(GOOD);
        flow.pressStartUrlSave();
        settle();
        check("Ohne Projekt wird NICHT behauptet, die Adresse sei uebernommen",
            flow.startUrlSaveStateText().startsWith("✖")
                && flow.startUrlSaveStateText().contains("NICHT übernommen"),
            flow.startUrlSaveStateText());
        check("… und die Zeile ist rot, nicht gruen",
            "#fde7e7".equals(flow.startUrlSaveStateColour()), flow.startUrlSaveStateColour());
        check("… und es entstand keine Einstellungsdatei", !Files.isRegularFile(settingsFile),
            settingsFile.toString());
        noWindows("Druck ohne Studio");

        // --- a Studio with an unconfigured project --------------------------------------
        AppMainFrame studio = new AppMainFrame();
        RecorderSettings settings = studio.getTestDesign().getProject().getProjectSettings()
            .getRecorderSettings();
        poll(flow);
        check("Die fehlende Start-Adresse wird VOR der Aufnahme gemeldet",
            flow.startUrlNoteText().contains("keine Start-Adresse hinterlegt"),
            flow.startUrlNoteText());
        check("… und sagt jetzt dazu, dass man sie hier eintragen kann",
            flow.startUrlNoteText().contains("hier eintragen"), flow.startUrlNoteText());
        check("Das Eingabefeld ist jetzt da", flow.startUrlEditorVisible(),
            "sichtbar=" + flow.startUrlEditorVisible());
        shoot(view, "51-startadresse-fehlt-eingabe-moeglich");

        // --- a well-shaped-looking address that is not one -------------------------------
        // The realistic mistake: the bare machine name, without http:// in front of it.
        flow.typeStartUrl("app.example.org");
        flow.pressStartUrlSave();
        settle();
        check("Eine Adresse ohne http:// wird abgelehnt",
            flow.startUrlSaveStateText().contains("Nicht übernommen")
                && flow.startUrlSaveStateText().contains("http://"),
            flow.startUrlSaveStateText());
        check("… rot, nicht gruen", "#fde7e7".equals(flow.startUrlSaveStateColour()),
            flow.startUrlSaveStateColour());
        check("… und es wurde wirklich nichts gespeichert", settings.getStartUrl().isEmpty(),
            "StartUrl=\"" + settings.getStartUrl() + "\"");
        check("… und die Zeile darueber meldet weiterhin: keine Adresse",
            flow.startUrlNoteText().contains("keine Start-Adresse hinterlegt"),
            flow.startUrlNoteText());
        noWindows("abgelehnte Adresse");
        shoot(view, "52-startadresse-abgelehnt");

        // The other two refusal reasons, which have their own sentences.
        flow.typeStartUrl("ftp://app.example.org/start");
        flow.pressStartUrlSave();
        settle();
        check("Ein anderes Protokoll wird benannt, nicht nur abgelehnt",
            flow.startUrlSaveStateText().contains("ftp:"), flow.startUrlSaveStateText());
        flow.typeStartUrl("https:///start");
        flow.pressStartUrlSave();
        settle();
        check("Eine Adresse ohne Rechnernamen wird als solche benannt",
            flow.startUrlSaveStateText().contains("Rechnername"), flow.startUrlSaveStateText());
        check("Nach drei Ablehnungen steht immer noch nichts in den Einstellungen",
            settings.getStartUrl().isEmpty(), "StartUrl=\"" + settings.getStartUrl() + "\"");

        // --- the address that works ------------------------------------------------------
        flow.typeStartUrl(GOOD);
        flow.pressStartUrlSave();
        settle();
        check("Die Adresse wird als uebernommen gemeldet",
            flow.startUrlSaveStateText().startsWith("✔"), flow.startUrlSaveStateText());
        // Amber, not green, and on purpose: nothing was compared, so nothing was confirmed.
        // Green here would be the panel agreeing with a check it never ran.
        check("… gelb, weil beim ersten Mal nichts geprueft werden konnte",
            "#fff4d8".equals(flow.startUrlSaveStateColour()), flow.startUrlSaveStateColour());
        check("… und die Meldung nennt den Rechner einzeln, weil sich die Umgebungen nur "
            + "darin unterscheiden",
            flow.startUrlSaveStateText().contains("Rechner: app.example.org"),
            flow.startUrlSaveStateText());
        // The machine now leads the line. This is not cosmetic: the line clips rather than
        // wraps, so anything behind the URL is what a narrow window eats — and the machine is
        // the only part that distinguishes the six environments from one another.
        check("… und der Rechner steht VORNE, wo ein schmales Fenster ihn nicht abschneidet",
            flow.startUrlSaveStateText().startsWith("✔ Rechner: app.example.org"),
            flow.startUrlSaveStateText());
        // FIRST: nothing was ever stored here, so there is nothing to compare against — and
        // that is said, not passed off as agreement.
        check("Beim ersten Mal wird gesagt, dass es keinen Vergleich gibt",
            flow.startUrlSaveStateText().contains("noch nie eine Adresse gespeichert")
                && flow.startUrlSaveStateText().contains("kann das Programm nicht feststellen"),
            flow.startUrlSaveStateText());
        check("… und es wird KEINE Umgebung benannt, die niemand kennt",
            !flow.startUrlSaveStateText().contains("Abnahme")
                && !flow.startUrlSaveStateText().contains("Produktion"),
            flow.startUrlSaveStateText());
        check("Die Zeile darueber nennt jetzt die Adresse und woher sie kommt",
            flow.startUrlNoteText().contains(GOOD)
                && flow.startUrlNoteText().contains("im Projekt hinterlegt"),
            flow.startUrlNoteText());
        noWindows("uebernommene Adresse");
        shoot(view, "53-startadresse-uebernommen");

        // The claim is checked against the file, not against the panel's own memory.
        check("Die Adresse steht in der Einstellungsdatei des Projekts",
            GOOD.equals(inFile(settingsFile)),
            settingsFile + " -> " + inFile(settingsFile));
        // And against a settings object built from scratch, which is what the next Studio
        // start does. An in-memory setter that worked looks identical until exactly here.
        check("… und ein neu geladenes Projekt liest sie wieder",
            GOOD.equals(new RecorderSettings().getStartUrl()),
            new RecorderSettings().getStartUrl());

        // --- an address that belongs to the test case wins, and says so ------------------
        writeSelection(PER_CASE);
        poll(flow);
        check("Eine Adresse am Testfall hat Vorrang und wird auch so genannt",
            flow.startUrlNoteText().contains(PER_CASE)
                && flow.startUrlNoteText().contains("Vorrang"),
            flow.startUrlNoteText());
        check("… und die Projekt-Adresse wird ausdruecklich als ungenutzt bezeichnet",
            flow.startUrlNoteText().contains(GOOD)
                && flow.startUrlNoteText().contains("nicht verwendet"),
            flow.startUrlNoteText());
        // This line clips like every other answer on the panel, and it is the longest of the
        // three start-address lines: two addresses and a rule. What a narrow window must never
        // eat is the rule — a tester who reads only "Der Browser öffnet: …" and then types into
        // the field below watches nothing change and reports the wrong defect.
        check("Die Zeile ist kein umbrechendes HTML",
            !flow.startUrlNoteText().contains("<html>"), flow.startUrlNoteText());
        check("… und der Vorrang steht VORNE, vor beiden Adressen",
            flow.startUrlNoteText().startsWith("Adresse des Testfalls — hat Vorrang"),
            flow.startUrlNoteText());
        check("… waehrend der ganze Satz im Tooltip erhalten bleibt",
            flow.startUrlNoteTooltip().equals(flow.startUrlNoteText())
                && flow.startUrlNoteTooltip().contains(GOOD),
            flow.startUrlNoteTooltip());
        shoot(view, "54-startadresse-vom-testfall");
        // Narrow, because "the rule survives the clip" is a claim about a width this harness
        // otherwise never lays the panel out at.
        PanelHarnessKit.resizeTo(900, 780);
        shoot(view, "54c-startadresse-vom-testfall-schmales-fenster");
        PanelHarnessKit.resizeTo(1500, 950);

        // Changing the project address while a test-case address is in force stores the new
        // project value and must NOT claim the browser will open it.
        flow.typeStartUrl(LATER);
        flow.pressStartUrlSave();
        settle();
        check("Die neue Projekt-Adresse wird gespeichert", LATER.equals(inFile(settingsFile)),
            String.valueOf(inFile(settingsFile)));
        check("… aber die Zeile sagt weiterhin, dass der Testfall gewinnt",
            flow.startUrlNoteText().contains(PER_CASE)
                && flow.startUrlNoteText().contains("Vorrang"),
            flow.startUrlNoteText());
        // Found by looking at the render, not at the strings: the success line used to say
        // "der Browser öffnet ab jetzt <Projekt-Adresse>" in green, one line under a note
        // saying the test case's address had precedence. Two adjacent lines contradicting each
        // other, and the green one is the one a tester believes.
        check("… und die Erfolgsmeldung behauptet NICHT, der Browser oeffne die Projekt-Adresse",
            !flow.startUrlSaveStateText().contains("öffnet ab jetzt"),
            flow.startUrlSaveStateText());
        check("… sondern sagt nur, was gespeichert wurde",
            flow.startUrlSaveStateText().contains("Als Projekt-Adresse gespeichert"),
            flow.startUrlSaveStateText());
        // CHANGED: a different machine from the one stored last time. This is the answer the
        // panel could not previously give at all — it named the host and handed the whole
        // question to the tester, who had nothing on screen to check it against.
        check("Ein anderer Rechner als zuletzt wird als solcher gemeldet",
            flow.startUrlSaveStateText().contains("ACHTUNG: anderer Rechner als zuletzt")
                && flow.startUrlSaveStateText().contains("zuletzt: app.example.org"),
            flow.startUrlSaveStateText());
        // Gerendert, nicht behauptet: eine gruene Zeile mit "ACHTUNG" darin ist ein Unterschied,
        // den man uebersieht — der Tester liest die Farbe vor dem Satz.
        check("… und die Zeile ist gelb, nicht gruen",
            "#fff4d8".equals(flow.startUrlSaveStateColour()), flow.startUrlSaveStateColour());
        check("… und sie behauptet trotzdem nicht, es sei etwas schiefgegangen",
            flow.startUrlSaveStateText().startsWith("✔"), flow.startUrlSaveStateText());
        shoot(view, "54b-startadresse-projekt-trotz-testfall");

        // SAME: the third answer, so the comparison is not a one-way check that always fires.
        flow.typeStartUrl(LATER);
        flow.pressStartUrlSave();
        settle();
        check("Derselbe Rechner wie zuletzt wird als derselbe gemeldet",
            flow.startUrlSaveStateText().contains("gleicher Rechner wie zuletzt")
                && !flow.startUrlSaveStateText().contains("ACHTUNG"),
            flow.startUrlSaveStateText());
        // The other side of the colour rule: green is reachable, and only here — when the
        // machine really was compared and really was the same.
        check("… und NUR dann ist die Zeile gruen",
            "#e3f6e3".equals(flow.startUrlSaveStateColour()), flow.startUrlSaveStateColour());

        // An unusable address at the test case falls through, exactly as the recorder does
        // with it — the screen must not name an address the recorder is going to ignore.
        writeSelection("kaputt");
        poll(flow);
        check("Eine unbrauchbare Adresse am Testfall wird nicht angezeigt",
            !flow.startUrlNoteText().contains("kaputt"), flow.startUrlNoteText());
        check("… und die Projekt-Adresse gilt wieder",
            flow.startUrlNoteText().contains(LATER)
                && flow.startUrlNoteText().contains("im Projekt hinterlegt"),
            flow.startUrlNoteText());
        Files.deleteIfExists(AdoCache.selectionPath());
        poll(flow);

        // --- the save that swallows its own failure --------------------------------------
        // The product's PropUtils.saveProperties catches IOException and returns normally, so
        // a settings file that cannot be written produces a save() that "worked". The panel is
        // only allowed to report what it can read back out of the file.
        settings.simulateUnwritableSettingsFile();
        flow.typeStartUrl(GOOD);
        flow.pressStartUrlSave();
        settle();
        check("Ein stiller Schreibfehler wird NICHT als Erfolg gemeldet",
            !flow.startUrlSaveStateText().startsWith("✔"), flow.startUrlSaveStateText());
        check("… sondern als: uebergeben, aber nicht nachweisbar gespeichert",
            flow.startUrlSaveStateText().contains("wirklich angekommen"),
            flow.startUrlSaveStateText());
        check("… und die Datei enthaelt weiterhin die alte Adresse",
            LATER.equals(inFile(settingsFile)), String.valueOf(inFile(settingsFile)));
        noWindows("stiller Schreibfehler");
        shoot(view, "55-startadresse-nicht-nachweisbar");

        // --- the save that refuses --------------------------------------------------------
        settings.simulateSaveRefused();
        flow.typeStartUrl(GOOD);
        flow.pressStartUrlSave();
        settle();
        check("Eine verweigerte Speicherung wird als Sitzungswert gemeldet",
            flow.startUrlSaveStateText().contains("Für diese Sitzung übernommen")
                && flow.startUrlSaveStateText().contains("nicht schreiben"),
            flow.startUrlSaveStateText());
        // The sentence a tester used to be sent away with was "you can keep working for today,
        // please report" — true, and a trap: the value is gone the moment they close Studio and
        // the reassurance is the last thing they read. It now says what the tool will do about
        // it, and it may only say that once the copy is really on disk.
        check("… und sie sagt, dass der Ablauf sie beim naechsten Start selbst wieder eintraegt",
            flow.startUrlSaveStateText().contains("trägt sie dann von allein erneut ein"),
            flow.startUrlSaveStateText());
        check("… und der technische Grund steht am Tooltip",
            flow.startUrlSaveStateTooltip().contains("save()"),
            flow.startUrlSaveStateTooltip());
        check("Die eigene Kopie liegt wirklich in der Datei, nicht nur in der Behauptung",
            GOOD.equals(memoryFor(settings.getLocation())),
            String.valueOf(memoryFor(settings.getLocation())));
        noWindows("verweigerte Speicherung");
        shoot(view, "56-startadresse-nur-sitzung");

        // --- the next Studio start: the panel puts its own copy back ---------------------
        // A fresh GuidedFlowPanel is the next Studio start as far as this behaviour goes: the
        // project has lost the address, and the once-per-panel restore has not run yet.
        settings.simulateSaveWorks();
        settings.setStartUrl("");
        retire(view);
        GuidedFlowPanel next = new GuidedFlowPanel();
        JComponent nextView = PanelHarnessKit.build(next);
        check("Zweites Panel geladen", next.awaitReady(15_000), "settled");
        settle();
        PanelHarnessKit.toStep3(next, nextView);
        poll(next);
        check("Die verlorene Adresse wird beim naechsten Start von allein wieder eingetragen",
            next.startUrlSaveStateText().startsWith("✔ Rechner: app.example.org — Start-Adresse wieder "
                + "eingetragen, Sie müssen nichts tun")
                && next.startUrlSaveStateText().contains(GOOD),
            next.startUrlSaveStateText());
        check("… und das wird erst behauptet, wenn es in der Datei steht",
            GOOD.equals(inFile(settingsFile)), String.valueOf(inFile(settingsFile)));
        check("… und die Zeile darueber meldet die Adresse wieder als hinterlegt",
            next.startUrlNoteText().contains(GOOD)
                && next.startUrlNoteText().contains("im Projekt hinterlegt"),
            next.startUrlNoteText());
        check("… und der Tester bekommt keine Aufgabe, die er nicht braucht",
            next.startUrlSaveStateText().contains("Sie müssen nichts tun"),
            next.startUrlSaveStateText());
        noWindows("wiedereingetragene Adresse");
        shoot(nextView, "57-startadresse-wieder-eingetragen");

        // --- a project that will not keep it at all: restored for the session, and said so ---
        settings.setStartUrl("");
        settings.simulateSaveRefused();
        retire(nextView);
        // The chooser relabels its button once a test case is already taken, and the previous
        // panel took one — so the walk to step 3 starts from a clean selection, as a fresh
        // Studio would.
        Files.deleteIfExists(AdoCache.selectionPath());
        GuidedFlowPanel third = new GuidedFlowPanel();
        JComponent thirdView = PanelHarnessKit.build(third);
        check("Drittes Panel geladen", third.awaitReady(15_000), "settled");
        settle();
        PanelHarnessKit.toStep3(third, thirdView);
        poll(third);
        check("Laesst sie sich dauerhaft nicht ablegen, gilt sie wenigstens wieder fuer die Sitzung",
            third.startUrlSaveStateText().startsWith("○ Rechner: app.example.org — Start-Adresse wieder "
                + "gesetzt, für diese Sitzung")
                && third.startUrlSaveStateText().contains("bei jedem Start erneut ein"),
            third.startUrlSaveStateText());
        check("… und der Browser oeffnet sie ab jetzt wirklich",
            GOOD.equals(settings.getStartUrl()), settings.getStartUrl());
        check("… und das wird NICHT als dauerhaft gespeichert ausgegeben",
            !third.startUrlSaveStateText().startsWith("✔"), third.startUrlSaveStateText());
        noWindows("nur fuer die Sitzung wiederhergestellt");
        shoot(thirdView, "58-startadresse-sitzungsweise-wiederhergestellt");

        // --- and the restore that does not work either ------------------------------------
        // Without this the restore could only ever be shown succeeding, which proves nothing.
        settings.simulateSaveWorks();
        settings.setStartUrl("");
        settings.simulateSettingRejected();
        retire(thirdView);
        Files.deleteIfExists(AdoCache.selectionPath());
        GuidedFlowPanel fourth = new GuidedFlowPanel();
        JComponent fourthView = PanelHarnessKit.build(fourth);
        check("Viertes Panel geladen", fourth.awaitReady(15_000), "settled");
        settle();
        PanelHarnessKit.toStep3(fourth, fourthView);
        poll(fourth);
        check("Ein gescheitertes Wiedereintragen wird NICHT als Erfolg gemeldet",
            !fourth.startUrlSaveStateText().startsWith("✔")
                && fourth.startUrlSaveStateText().startsWith("○ Start-Adresse ließ sich nicht wieder eintragen"),
            fourth.startUrlSaveStateText());
        check("… und nennt einen Schritt statt nur zu melden",
            fourth.startUrlSaveStateText().contains("von Hand in die Adresszeile"),
            fourth.startUrlSaveStateText());
        noWindows("gescheitertes Wiedereintragen");
        shoot(fourthView, "59-startadresse-wiedereintragen-gescheitert");

        // --- the refusal that used to be a dead end ---------------------------------------
        // "✖ Die Adresse wurde NICHT übernommen — melden." was the only refusal on this panel
        // with no step behind it. With the setting rejected there IS a Studio, so this is the
        // NONE arm reached with a project in view, which the no-Studio press at the top cannot
        // reach.
        fourth.typeStartUrl(GOOD);
        fourth.pressStartUrlSave();
        settle();
        check("Die Ablehnung nennt jetzt einen Schritt, nicht nur „melden“",
            fourth.startUrlSaveStateText().contains("Aufnahme trotzdem starten")
                && fourth.startUrlSaveStateText().contains("von Hand in die Adresszeile"),
            fourth.startUrlSaveStateText());
        check("… und der Schritt steht VORNE, wo ein schmales Fenster ihn nicht abschneidet",
            fourth.startUrlSaveStateText().startsWith("✖ NICHT übernommen — Aufnahme trotzdem"),
            fourth.startUrlSaveStateText());
        check("… und der Banner ist eine Zeile, kein umbrechendes HTML",
            !fourth.bannerText().contains("<br>"), fourth.bannerText());
        check("… und der Banner nennt den Schritt ebenfalls",
            fourth.bannerText().contains("von Hand eintippen"), fourth.bannerText());
        noWindows("Ablehnung mit Schritt");
        shoot(fourthView, "60-startadresse-abgelehnt-mit-schritt");
        // Rendered narrow, because "the step comes first" is a claim about what survives
        // clipping and 1500 pixels never tests it. 60b is the proof.
        PanelHarnessKit.resizeTo(900, 780);
        shoot(fourthView, "60b-startadresse-abgelehnt-schmales-fenster");
        PanelHarnessKit.resizeTo(1500, 950);

        // --- and the copy that could not be filed either -----------------------------------
        // The panel's own store is the thing that makes the two half-successes survivable, so
        // a store that cannot be written is not a theoretical case. Claiming "the panel has
        // memorised it" there would be the same defect one level down.
        Path memory = Path.of(System.getenv("ING_START_ADDRESS_MEMORY"));
        Files.deleteIfExists(memory);
        Files.createDirectories(memory);
        settings.simulateSaveWorks();
        settings.simulateSaveRefused();
        fourth.typeStartUrl(LATER);
        fourth.pressStartUrlSave();
        settle();
        check("Kann sich der Ablauf die Adresse nicht merken, behauptet er es auch nicht",
            fourth.startUrlSaveStateText().contains("konnte sie sich auch nicht selbst merken")
                && fourth.startUrlSaveStateText().contains("erneut eintragen"),
            fourth.startUrlSaveStateText());
        check("… und es wird NICHT versprochen, dass er sie wieder eintraegt",
            !fourth.startUrlSaveStateText().contains("von allein erneut ein"),
            fourth.startUrlSaveStateText());
        noWindows("nicht merkbare Adresse");
        shoot(fourthView, "61-startadresse-nicht-gemerkt");

        // Not asserted after this: Frame.getFrames() keeps returning a disposed frame until it
        // is collected, so "no Studio any more" is not observable here. The no-Studio answer is
        // proved at the top of this run instead, before any frame exists.
        studio.dispose();
        System.exit(PanelHarnessKit.verdict());
    }

    /**
     * Takes a panel out of service before the next one is built.
     *
     * <p>Not tidiness. The panel re-reads Studio once a second for as long as its root is
     * displayable, and the restore is deliberately once per panel — so a previous panel left
     * alive would race the new one for the same restore, and whichever won would decide what
     * the assertions saw. Disposing the window stops the poll at its own condition.
     */
    private static void retire(JComponent view) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            java.awt.Container top = view.getTopLevelAncestor();
            if (top instanceof java.awt.Window window) {
                window.dispose();
            }
        });
        settle();
    }

    /** The panel's own copy for this project, read out of its file rather than from the panel. */
    private static String memoryFor(String projectLocation) throws Exception {
        Path file = Path.of(System.getenv("ING_START_ADDRESS_MEMORY"));
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            props.load(in);
        }
        return props.getProperty(projectLocation);
    }

    /** The stored value, read out of the file itself rather than out of any object. */
    private static String inFile(Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            props.load(in);
        }
        return props.getProperty("StartUrl");
    }

    /** A selection carrying its own start address, in the shape {@code SelectedTestCase} reads. */
    private static void writeSelection(String startUrl) throws Exception {
        Files.writeString(AdoCache.selectionPath(),
            "{\n  \"adoId\": \"1234567\",\n  \"title\": \"Beispiel\",\n"
                + "  \"suiteName\": \"Beispielbereich\",\n"
                + "  \"startUrl\": \"" + startUrl + "\"\n}\n", StandardCharsets.UTF_8);
    }
}
