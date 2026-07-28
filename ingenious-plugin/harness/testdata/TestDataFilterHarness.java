package testdata;

import de.ing.qa.panel.TestDataPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/**
 * Every column of the test-data file has a control, and every control does what it says.
 *
 * <p>The defect this exists for was reported by the tester in one sentence: <em>"I liked the
 * previous implementation for the test-data choosing much more, where people could choose
 * everything. It seems like Bonitaet Zusatzinfo is missing for example."</em> They were right.
 * A column got a dropdown only if it had between 2 and 25 distinct values; the other seven of
 * seventeen got <b>nothing</b>, and nothing on screen distinguished "this column cannot be
 * filtered" from "this column is not in the data".
 *
 * <p>So the first check here is not about any one column: it is that the list of columns with
 * a control and the list of columns in the file are the same list. Everything after it is
 * about the controls being the right kind and telling the truth.
 *
 * <p>Two rules this harness will not bend, both learnt the expensive way in this repo:
 *
 * <ul>
 *   <li><b>Judge a dialog from {@link Window#getWindows()}, never from a responsive EDT.</b>
 *       A modal dialog pumps the event queue, so an EDT that still answers proves nothing.
 *   <li><b>Rendered, not merely constructed.</b> The panel is packed, laid out and captured
 *       with {@code printAll}, at a comfortable width AND at narrow ones — a control that is
 *       laid out to zero height, or scrolled out of reach with no scrollbar to say so, is
 *       exactly the invisible absence this whole change removes, and string assertions about
 *       it all pass.
 * </ul>
 *
 * <p>Exit codes: <b>0 green, 4 UNGEPRUEFT, 1 red</b> — the suite's three.
 */
public class TestDataFilterHarness {

    private static int checks;
    private static int failures;
    private static int unproven;
    private static File shotDir;
    private static JFrame frame;
    private static JComponent view;
    private static TestDataPanel panel;
    /** Prefix on the screenshot names, so the small file cannot overwrite the real one. */
    private static String tag = "";

    public static void main(String[] args) throws Exception {
        shotDir = new File(args.length > 0 ? args[0] : ".");
        shotDir.mkdirs();
        String mode = args.length > 1 ? args[1] : "voll";

        String csv = System.getenv("ING_TESTDATA_CSV");
        if (csv == null || csv.isBlank() || !new File(csv).isFile()) {
            unproven("Die Testdatei ist da", "ING_TESTDATA_CSV zeigt auf nichts: " + csv);
            System.exit(verdict());
        }

        build();
        noWindows("beim Aufbau");

        if ("klein".equals(mode)) {
            small();
            System.exit(verdict());
        }

        List<String> headers = panel.headersForTest();
        List<String> controlled = panel.filterColumnsForTest();

        // ------------------------------------------------------------------ the defect
        check("Jede Spalte der Datei hat ein Bedienelement",
            controlled.equals(headers),
            headers.size() + " Spalten, " + controlled.size() + " Bedienelemente"
                + (controlled.equals(headers) ? "" : "; ohne: " + missing(headers, controlled)));

        check("\"Kbo5 Zusatzinfo\" ist wieder da — die gemeldete Spalte",
            !panel.filterKindForTest("Kbo5 Zusatzinfo").isEmpty(),
            "kind=" + panel.filterKindForTest("Kbo5 Zusatzinfo"));

        // ------------------------------------------------------------------ the right kind
        // A tester filters by WHAT KIND of customer they need and receives WHO that customer
        // is. So the split is by what a column is for, not by how many values it has — which
        // is the only rule that can put Kontonummer (25,181 distinct) and Kbo5 Zusatzinfo
        // (14,072 distinct) on opposite sides, as the tester asked for.
        kind("Part Partnertyp Kz", "auswahl");
        kind("Kbo5 Bonitaet S", "auswahl");
        kind("Produktvariante Pzm", "auswahl");
        kind("MDJ_KND", "auswahl");
        kind("EZB", "auswahl");
        kind("Legi Status Kz", "auswahl");
        kind("Verf Bez", "auswahl");
        kind("Vest Bez", "auswahl");
        kind("ZUDA APP", "auswahl");
        kind("ZUDA M_TAN", "auswahl");
        // Eleventh, and the one that changed when the file stopped being a 6% sample: see
        // the block under "the two verdicts a sample got wrong" below.
        kind("ZUDA TAN_LISTE", "auswahl");

        kind("Variante_KND", "text");
        kind("Kbo5 Zusatzinfo", "text");

        // "Kontonummer for sure not" — verbatim. The other three identify a customer just as
        // squarely: a partner number, a customer number and a date of birth are all answers
        // to "who is this", never to "what kind of customer do I need".
        kind("Kontonummer", "identitaet");
        kind("Part Nr", "identitaet");
        kind("Kbo5 Personen Nr", "identitaet");
        kind("Part Geburtsdatum", "identitaet");

        int auswahl = 0;
        int text = 0;
        int identitaet = 0;
        int ohne = 0;
        for (String column : controlled) {
            switch (panel.filterKindForTest(column)) {
                case "auswahl" -> auswahl++;
                case "text" -> text++;
                case "identitaet" -> identitaet++;
                default -> ohne++;
            }
        }
        check("Die Aufteilung ist 11 Auswahl / 2 Text / 4 Identitaet / 0 ohne Aussage",
            auswahl == 11 && text == 2 && identitaet == 4 && ohne == 0,
            auswahl + " / " + text + " / " + identitaet + " / " + ohne);

        check("Kein Filter auf der Kontonummer — sie ist die Antwort, nicht die Frage",
            "identitaet".equals(panel.filterKindForTest("Kontonummer")),
            panel.filterKindForTest("Kontonummer"));

        // ------------------------------------------------------------------ saying why
        String account = panel.filterReasonForTest("Kontonummer");
        check("Die Kontonummer sagt trotzdem, wo sie zu finden ist",
            account.contains("Tabelle") && account.contains("Kontonummer kopieren"),
            "\"" + account + "\"");

        check("Eine Spalte, auf die man wirklich filtern kann, hat keine solche Begruendung",
            panel.filterReasonForTest("Kbo5 Zusatzinfo").isEmpty(),
            "\"" + panel.filterReasonForTest("Kbo5 Zusatzinfo") + "\"");

        // --------------------------------------------- the two verdicts a sample got wrong
        // Both of these columns were called mute when the file on the tester's machine was
        // its first 2,999 rows — 6% of it, and by our own converter bug. On all 50,015 they
        // are nothing of the sort, and "this column says nothing" is exactly as damaging a
        // lie as the missing control that started this: the tester stops looking.
        //
        // Kbo5 Personen Nr: not empty. 8,492 different customer numbers, on 37% of the rows.
        // It stays an IDENTITY column — a customer number answers "who", not "what kind" —
        // but it must not claim to be empty, and the number must be in the table to copy.
        String personen = panel.filterReasonForTest("Kbo5 Personen Nr");
        check("Die Personennummer behauptet nicht mehr, leer zu sein",
            !personen.contains("bei jeder Zeile leer") && personen.contains("WER der Kunde ist"),
            "\"" + personen + "\"");

        // Not empty is not the same as filled. Two rows in three have no customer number, and
        // a control that flatly promises one sends the tester hunting for a value the file
        // never had — the same wasted search as a missing control, from the other end.
        check("Sie sagt aber auch, wie oft sie fehlt",
            personen.contains("ist das Feld leer")
                && personen.contains("von " + panel.offeredRowCount() + " Zeilen"),
            "\"" + personen + "\"");

        check("Eine lueckenlose Identitaetsspalte sagt das nicht",
            !account.contains("ist das Feld leer") && account.contains("bei jedem Treffer"),
            "\"" + account + "\"");

        int personenColumn = headers.indexOf("Kbo5 Personen Nr");
        int filled = 0;
        for (int r = 0; r < panel.visibleRowCount(); r++) {
            if (!panel.visibleRowForTest(r).get(personenColumn).isBlank()) {
                filled++;
            }
        }
        check("Und sie steht wirklich in der Tabelle — nicht nur als Ueberschrift",
            filled > 0 && filled < panel.visibleRowCount(),
            filled + " von " + panel.visibleRowCount() + " Zeilen tragen eine Personennummer");

        // ZUDA TAN_LISTE: one value, yes — but 2,696 rows leave it empty, and empty is a
        // state a tester can ask for. So it is a dropdown, and picking "(leer)" really has
        // to narrow the table. The old verdict said this column could narrow nothing.
        List<String> tan = panel.filterChoicesForTest("ZUDA TAN_LISTE");
        check("ZUDA TAN_LISTE ist keine stumme Spalte, sondern eine mit zwei Zustaenden",
            tan.contains("(egal)") && tan.contains("nein (N)") && tan.contains("(leer)")
                && tan.size() == 3,
            String.valueOf(tan));

        check("Auf die Spalte, die frueher nichts konnte, laesst sich wirklich filtern",
            apply("ZUDA TAN_LISTE", "(leer)")
                && panel.visibleRowCount() > 0
                && panel.visibleRowCount() < panel.offeredRowCount(),
            panel.visibleRowCount() + " von " + panel.offeredRowCount() + " Zeilen ohne Eintrag");
        reset();

        check("In dieser Datei ist keine einzige Spalte ohne Aussage", ohne == 0,
            ohne + " Spalten sagen nichts aus");

        // The row the file itself gets wrong: one account number of five digits. It is
        // withheld — and said out loud, because a silently dropped customer is a customer
        // the tester will look for and not find.
        check("Die eine unbrauchbare Zeile wird zurueckgehalten UND genannt",
            panel.skippedRowCount() == 1 && panel.statusText().contains("unvollständig"),
            panel.skippedRowCount() + " zurueckgehalten; \"" + panel.statusText() + "\"");

        // Not filtered is not the same as not shown. The account number is the whole point of
        // the screen, so it had better still be a column of the table.
        List<String> shown = new ArrayList<>();
        JTable table = (JTable) find(view, JTable.class);
        for (int c = 0; table != null && c < table.getColumnCount(); c++) {
            shown.add(String.valueOf(table.getColumnName(c)));
        }
        check("Jede Identitaetsspalte steht weiterhin in der Tabelle",
            shown.contains("Kontonummer") && shown.contains("Partnernummer")
                && shown.contains("Personennummer") && shown.contains("Geburtsdatum"),
            String.valueOf(shown));

        // ------------------------------------------------------------------ the dropdowns
        List<String> partner = panel.filterChoicesForTest("Part Partnertyp Kz");
        check("Die Auswahl zeigt jeden Wert der Spalte, auf Deutsch",
            partner.contains("(egal)") && partner.contains("Einzelkunde (P)")
                && partner.contains("Gemeinschaftskunde (G)") && partner.contains("J")
                && partner.size() == 4,
            String.valueOf(partner));

        // Legi Status Kz, not Verf Bez: on the whole file the access method is filled in
        // every single row, while the legitimation status is empty in 96% of them. A
        // "(leer)" check on a column that has no empty fields proves nothing at all.
        List<String> legi = panel.filterChoicesForTest("Legi Status Kz");
        check("Eine Spalte mit leeren Feldern bietet \"(leer)\" an",
            legi.contains("(leer)"), String.valueOf(legi));

        check("Eine Spalte ohne leere Felder bietet es nicht an",
            !panel.filterChoicesForTest("Verf Bez").contains("(leer)"),
            String.valueOf(panel.filterChoicesForTest("Verf Bez")));

        // ------------------------------------------------------------------ it really filters
        int all = panel.offeredRowCount();
        check("Ohne Filter sind alle Zeilen da", panel.visibleRowCount() == all,
            panel.visibleRowCount() + " von " + all);

        // A number, because the column really holds numbers: 14,072 different ones, up to
        // six digits. A tester with a Bonität-Zusatzinfo in hand types digits, not words.
        boolean set = apply("Kbo5 Zusatzinfo", "10042");
        int narrowed = panel.visibleRowCount();
        check("Ein Textfilter auf einer vielwertigen Spalte schraenkt wirklich ein",
            set && narrowed > 0 && narrowed < all,
            narrowed + " von " + all + " Zeilen, gesetzt=" + set);

        int column = headers.indexOf("Kbo5 Zusatzinfo");
        List<String> wrong = new ArrayList<>();
        for (int r = 0; r < panel.visibleRowCount(); r++) {
            String value = panel.visibleRowForTest(r).get(column);
            if (!value.contains("10042")) {
                wrong.add(value);
            }
        }
        check("Jede uebrige Zeile enthaelt den gesuchten Text wirklich",
            wrong.isEmpty(), wrong.isEmpty() ? narrowed + " Zeilen geprueft" : String.valueOf(wrong));

        check("Die Tabelle zeigt genau diese Zeilen — nicht nur die Zaehlung",
            tableRowCount() == narrowed, tableRowCount() + " Tabellenzeilen");

        // Case travels on the other text column, because the first one holds digits and
        // digits have no case: a case check on a number is a check that cannot fail.
        reset();
        apply("Variante_KND", "VAR0042");
        int upper = panel.visibleRowCount();
        apply("Variante_KND", "var0042");
        check("Gross- und Kleinschreibung sind egal",
            upper > 0 && panel.visibleRowCount() == upper,
            panel.visibleRowCount() + " statt " + upper);

        reset();
        check("Zuruecksetzen bringt alle Zeilen zurueck", panel.visibleRowCount() == all,
            panel.visibleRowCount() + " von " + all);

        apply("Part Partnertyp Kz", "Gemeinschaftskunde (G)");
        int chosen = panel.visibleRowCount();
        int wrongType = 0;
        int typeColumn = headers.indexOf("Part Partnertyp Kz");
        for (int r = 0; r < chosen; r++) {
            if (!"G".equals(panel.visibleRowForTest(r).get(typeColumn))) {
                wrongType++;
            }
        }
        check("Eine Auswahl filtert auf genau ihren Wert",
            chosen > 0 && chosen < all && wrongType == 0,
            chosen + " von " + all + " Zeilen, " + wrongType + " davon falsch");

        reset();
        applyFreeText("Einzelkunde");
        check("Der Freitext sucht auch das, was auf dem Bildschirm steht",
            panel.visibleRowCount() > 0 && panel.visibleRowCount() < all,
            panel.visibleRowCount() + " von " + all + " Zeilen fuer \"Einzelkunde\"");

        reset();
        check("Auf eine Identitaetsspalte laesst sich gar nicht filtern",
            !apply("Kontonummer", "5000012345") && panel.visibleRowCount() == all,
            panel.visibleRowCount() + " von " + all + " Zeilen");

        // The way out for the tester who already HAS a number and only wants to know whether
        // it is in the pool. The identity control's own tooltip promises this, so it is
        // checked rather than promised.
        reset();
        applyFreeText("5000012345");
        check("Der Freitext findet eine Kontonummer trotzdem",
            panel.visibleRowCount() > 0 && panel.visibleRowCount() < all,
            panel.visibleRowCount() + " von " + all + " Zeilen fuer eine Kontonummer");

        reset();
        noWindows("nach dem Filtern");

        // ------------------------------------------------------------------ on the screen
        render("bequem", 1500, 950);
        render("schmal", 900, 700);
        render("sehr schmal", 700, 620);
        // 480 is not an arbitrary third width. It is roughly what the guided flow leaves this
        // panel in the right half of its split pane, and it is the first width at which the
        // free-text row wraps at all — so without it the clipping check above can only ever
        // return one of its two answers, which makes it not a check.
        render("wie im gefuehrten Flow", 480, 620);

        noWindows("nach dem Rendern");

        System.exit(verdict());
    }

    /**
     * The same panel against a tiny file in which two columns really do say nothing.
     *
     * <p>Why a second file at all: on the whole 50,015-row export <em>no</em> column is mute
     * any more, so the control that says "this column says nothing here" would have no test —
     * and it is exactly the control that told the tester a lie when it was decided from 6% of
     * the rows. It keeps a file where its answer is the true one.
     *
     * <p>Eight rows also put the {@code POPUP_ROWS} floor under test: √8 is 3, so a dropdown
     * over four products can only exist because of the floor, not because of the square root.
     */
    private static void small() throws Exception {
        tag = "klein-";
        List<String> headers = panel.headersForTest();
        check("Auch in einer winzigen Datei hat jede Spalte ein Bedienelement",
            panel.filterColumnsForTest().equals(headers),
            headers.size() + " Spalten, " + panel.filterColumnsForTest().size() + " Elemente");

        kind("ZUDA M_TAN", "ohne");
        String empty = panel.filterReasonForTest("ZUDA M_TAN");
        check("Eine in dieser Datei leere Spalte sagt, dass sie leer ist",
            empty.contains("leer"), "\"" + empty + "\"");

        kind("ZUDA TAN_LISTE", "ohne");
        String same = panel.filterReasonForTest("ZUDA TAN_LISTE");
        check("Eine Spalte mit nur einem Wert nennt diesen Wert im Klartext",
            same.contains("denselben Wert") && same.contains("nein (N)"), "\"" + same + "\"");

        // The empty-identity sentence, which the full file no longer produces either.
        kind("Kbo5 Personen Nr", "identitaet");
        String personen = panel.filterReasonForTest("Kbo5 Personen Nr");
        check("Eine leere Identitaetsspalte sagt AUCH, dass sie leer ist",
            personen.contains("bei jeder Zeile leer"), "\"" + personen + "\"");

        check("Eine lebende Spalte traegt keine solche Begruendung",
            panel.filterReasonForTest("Part Partnertyp Kz").isEmpty(),
            "\"" + panel.filterReasonForTest("Part Partnertyp Kz") + "\"");

        // Four values over eight rows: more than √8, so this dropdown exists only because a
        // list that fits in the popup is worth offering however small the file.
        List<String> produkt = panel.filterChoicesForTest("Produktvariante Pzm");
        check("Der POPUP_ROWS-Boden gibt auch einer winzigen Datei Auswahllisten",
            "auswahl".equals(panel.filterKindForTest("Produktvariante Pzm"))
                && produkt.size() == 5,
            panel.filterKindForTest("Produktvariante Pzm") + " " + produkt);

        check("Eine stumme Spalte laesst sich auch nicht heimlich setzen",
            !apply("ZUDA TAN_LISTE", "nein (N)")
                && panel.visibleRowCount() == panel.offeredRowCount(),
            panel.visibleRowCount() + " von " + panel.offeredRowCount() + " Zeilen");

        render("kleine Datei", 900, 700);
        noWindows("nach dem Rendern der kleinen Datei");
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Lays the panel out at one window size and asks the three questions a screenshot alone
     * cannot answer: is every control laid out at all, is every control reachable, and was
     * anything actually painted where the layout says the control is.
     */
    private static void render(String what, int width, int height) throws Exception {
        resizeTo(width, height);
        BufferedImage image = shoot("testdaten-filter-" + tag + width);

        List<String> columns = panel.filterColumnsForTest();
        List<JComponent> controls = panel.filterControlsForTest();
        List<String> zero = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        List<String> blank = new ArrayList<>();
        int onScreen = 0;
        int part = 0;

        for (int i = 0; i < controls.size(); i++) {
            JComponent control = controls.get(i);
            if (control.getWidth() <= 0 || control.getHeight() <= 0) {
                zero.add(columns.get(i) + " " + control.getWidth() + "x" + control.getHeight());
                continue;
            }
            // computeVisibleRect, NOT the control's own bounds against the panel's: the
            // filter area sits in a viewport, so a control can be laid out at a perfectly
            // ordinary position inside the panel and still be clipped away entirely. Asking
            // the panel's rectangle instead said "17 of 17 visible" at 700px while the last
            // two were scrolled out of sight — and then found the TABLE's pixels underneath
            // them and called that "painted". Two false greens from one wrong rectangle.
            Rectangle seen = new Rectangle();
            control.computeVisibleRect(seen);
            if (seen.isEmpty()) {
                if (!scrollable(control)) {
                    // Off screen with nothing saying there is more: the same invisible
                    // absence in a new place.
                    unreachable.add(columns.get(i));
                }
                continue;
            }
            if (seen.width == control.getWidth() && seen.height == control.getHeight()) {
                onScreen++;
            } else {
                // Reachable, but only partly drawn: counted apart from "visible", because
                // reporting a half-clipped control as visible is the sort of generosity this
                // whole check exists to refuse.
                part++;
            }
            Rectangle inView = SwingUtilities.convertRectangle(control, seen, view);
            if (!painted(image, inView)) {
                blank.add(columns.get(i));
            }
        }

        check("Jedes Bedienelement ist ueberhaupt gelegt (" + what + ", " + width + "px)",
            zero.isEmpty(), zero.isEmpty() ? controls.size() + " Elemente" : String.valueOf(zero));
        check("Jedes Bedienelement ist erreichbar (" + what + ", " + width + "px)",
            unreachable.isEmpty(),
            unreachable.isEmpty()
                ? onScreen + " von " + controls.size() + " ganz sichtbar, " + part
                    + " angeschnitten, der Rest hinter einer sichtbaren Bildlaufleiste"
                : "unerreichbar: " + unreachable);
        check("Wo ein sichtbares Element liegt, ist auch etwas gemalt ("
                + what + ", " + width + "px)",
            blank.isEmpty(), blank.isEmpty() ? (onScreen + part) + " Elemente geprueft" : String.valueOf(blank));

        // The free-text row is not in the scroll area, so nothing can say "there is more" on
        // its behalf: if it wraps, whatever wrapped is simply cut in half. That is what the
        // two buttons did at 900px inside the guided flow, and no string assertion noticed.
        List<String> clipped = new ArrayList<>();
        for (String text : new String[] {"Freitext (alle Spalten):", "Suchen", "Zurücksetzen"}) {
            Component control = labelled(view, text);
            if (control == null) {
                clipped.add(text + " NICHT GEFUNDEN");
                continue;
            }
            Rectangle seen = new Rectangle();
            ((JComponent) control).computeVisibleRect(seen);
            if (seen.width < control.getWidth() || seen.height < control.getHeight()) {
                clipped.add(text + " " + seen.width + "x" + seen.height
                    + " statt " + control.getWidth() + "x" + control.getHeight());
            }
        }
        check("Die Freitext-Zeile ist ganz zu sehen (" + what + ", " + width + "px)",
            clipped.isEmpty(), clipped.isEmpty() ? "Feld und beide Knoepfe" : String.valueOf(clipped));

        // The TABLE's own height is its 50,014 rows, which it has inside the viewport whether
        // the viewport is 400 pixels tall or 0. The question is how much of it a tester can
        // see, and only the viewport can answer that.
        JTable table = (JTable) find(view, JTable.class);
        Rectangle visibleTable = new Rectangle();
        if (table != null) {
            table.computeVisibleRect(visibleTable);
        }
        int lines = table == null || table.getRowHeight() == 0
            ? 0 : visibleTable.height / table.getRowHeight();
        check("Die Tabelle behaelt Platz fuer mindestens 5 Kunden (" + what + ", " + width + "px)",
            lines >= 5, lines + " Zeilen sichtbar, " + visibleTable.width + "x"
                + visibleTable.height + " px");
    }

    /**
     * Whether anything at all was drawn in that rectangle of the rendered image.
     *
     * <p>More than one colour means a border, a caret, a triangle, a letter — something. One
     * colour everywhere means the layout put a control there and the paint never happened,
     * which is the failure that has slipped past every string assertion in this repo.
     */
    private static boolean painted(BufferedImage image, Rectangle rect) {
        Set<Integer> colours = new HashSet<>();
        int x1 = Math.min(rect.x + rect.width, image.getWidth());
        int y1 = Math.min(rect.y + rect.height, image.getHeight());
        for (int y = Math.max(rect.y, 0); y < y1; y++) {
            for (int x = Math.max(rect.x, 0); x < x1; x++) {
                colours.add(image.getRGB(x, y));
                if (colours.size() > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a scrollbar is on screen saying this control can be reached by scrolling. */
    private static boolean scrollable(Component control) {
        for (Container up = control.getParent(); up != null; up = up.getParent()) {
            if (up instanceof JScrollPane pane && pane.getVerticalScrollBar().isVisible()) {
                return true;
            }
        }
        return false;
    }

    private static int tableRowCount() throws Exception {
        AtomicReference<Integer> out = new AtomicReference<>(-1);
        SwingUtilities.invokeAndWait(() -> {
            JTable table = (JTable) find(view, JTable.class);
            out.set(table == null ? -1 : table.getRowCount());
        });
        return out.get();
    }

    // ------------------------------------------------------------------ driving

    private static void build() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel = new TestDataPanel();
            view = panel.createPanel();
            frame = new JFrame("harness (never shown)");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(view);
            frame.pack();
            frame.setSize(1500, 950);
            frame.validate();
        });
        settle();
    }

    private static void resizeTo(int width, int height) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame.setSize(width, height);
            frame.validate();
        });
        settle();
        // The wrap layout answers with a new height once it has been given the new width, and
        // that answer only reaches the frame on the next pass. One pass is not enough.
        SwingUtilities.invokeAndWait(() -> frame.validate());
        settle();
    }

    private static BufferedImage shoot(String name) throws Exception {
        AtomicReference<BufferedImage> out = new AtomicReference<>();
        File file = new File(shotDir, name + ".png");
        SwingUtilities.invokeAndWait(() -> {
            frame.validate();
            BufferedImage image = new BufferedImage(
                Math.max(view.getWidth(), 100), Math.max(view.getHeight(), 100),
                BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            view.printAll(g);
            g.dispose();
            out.set(image);
            try {
                ImageIO.write(image, "png", file);
            } catch (Exception ex) {
                System.out.println("  screenshot failed: " + ex);
            }
        });
        System.out.println("  screenshot: " + file.getAbsolutePath());
        return out.get();
    }

    private static boolean apply(String column, String value) throws Exception {
        AtomicReference<Boolean> out = new AtomicReference<>(false);
        SwingUtilities.invokeAndWait(() -> out.set(panel.applyColumnFilterForTest(column, value)));
        settle();
        return out.get();
    }

    private static void applyFreeText(String needle) throws Exception {
        SwingUtilities.invokeAndWait(() -> panel.applyFreeTextForTest(needle));
        settle();
    }

    private static void reset() throws Exception {
        SwingUtilities.invokeAndWait(panel::resetFiltersForTest);
        settle();
    }

    /**
     * Lets every queued Swing task run — including the debounce timer behind the text boxes,
     * which is why this waits longer than it queues.
     */
    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(150);
        }
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** The button or label carrying exactly this text. */
    private static Component labelled(Component c, String text) {
        if (c instanceof javax.swing.AbstractButton b && text.equals(b.getText())) {
            return c;
        }
        if (c instanceof javax.swing.JLabel l && text.equals(l.getText())) {
            return c;
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = labelled(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Component find(Component c, Class<?> type) {
        if (type.isInstance(c)) {
            return c;
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ verdicts

    private static void kind(String column, String expected) {
        String actual = panel.filterKindForTest(column);
        check("\"" + column + "\" bekommt " + expected, expected.equals(actual),
            actual.isEmpty() ? "GAR KEIN Bedienelement" : actual);
    }

    private static List<String> missing(List<String> all, List<String> got) {
        List<String> out = new ArrayList<>(all);
        out.removeAll(got);
        return out;
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + "   [" + detail + "]");
    }

    private static void unproven(String what, String why) {
        unproven++;
        System.out.println("  UNGEPRUEFT " + what + "   [" + why + "]");
    }

    /** Fails if ANY window is showing — a modal dialog keeps the EDT answering. */
    private static void noWindows(String where) {
        List<String> showing = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) {
                showing.add(w.getClass().getSimpleName()
                    + (w instanceof java.awt.Dialog d ? " \"" + d.getTitle() + "\"" : ""));
            }
        }
        check("Kein Dialog oeffnet sich (" + where + ")", showing.isEmpty(),
            String.valueOf(showing));
    }

    private static int verdict() {
        System.out.println();
        if (failures > 0) {
            System.out.println("RESULT: RED — " + failures + " of " + checks + " checks failed");
            return 1;
        }
        if (unproven > 0) {
            System.out.println("RESULT: UNGEPRUEFT — " + unproven + " Frage(n) konnten nicht"
                + " gestellt werden; " + checks + " geprueft. Das ist KEIN Bestanden.");
            return 4;
        }
        System.out.println("RESULT: GREEN — " + checks + " checks passed");
        return 0;
    }
}
