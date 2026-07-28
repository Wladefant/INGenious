package de.ing.qa.panel;

import com.ing.ingenious.api.contract.data.ProjectTestDataApi;
import com.ing.ingenious.api.contract.ui.StudioPanelApi;
import de.ing.qa.studio.StudioTestData;
import de.ing.qa.studio.TestCaseProfile;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

/**
 * Studio screen that lets a tester describe the customer they need and get one back,
 * without ever opening the test-data spreadsheet.
 *
 * <p>The requirement comes verbatim from the test-automation lead: <em>"wo man dann einfach
 * nur noch eingeben kann, ich brauche Einzelkunden, der Boni 12 hat, spuck den mal aus"</em>.
 *
 * <p>Two deliberate design choices:
 *
 * <ul>
 *   <li><b>Generic over the file.</b> The panel reads a CSV export of the test-data workbook
 *       and derives its filters from the header row, so no column names are hardcoded. The
 *       real workbook is keyed by Kontonummer and carries dozens of columns that differ per
 *       release; hardcoding them would break on the next export.
 *   <li><b>Plain German, never codes.</b> Testers are customer-service colleagues:
 *       <em>"nur weil jemand eine Boni 21 hat, heißt es nicht, dass jeder weiß, dass das
 *       unbekannt verzogen bedeutet"</em>. An optional label file maps raw values to what
 *       they mean, and the UI shows the meaning.
 * </ul>
 *
 * <p>Configuration, both optional:
 *
 * <ul>
 *   <li>{@code ING_TESTDATA_CSV} — path to the CSV export. The file stays outside the repo:
 *       it contains customer data.
 *   <li>{@code ING_TESTDATA_LABELS} — properties file of {@code column.value=plain German}
 *       entries, e.g. {@code Boni.21=unbekannt verzogen}.
 * </ul>
 *
 * <p>Read-only by design. It selects existing test customers; it never creates or mutates
 * one, so it cannot damage the shared securities customer.
 */
public class TestDataPanel implements StudioPanelApi {

    private static final String ENV_CSV = "ING_TESTDATA_CSV";
    private static final String ENV_LABELS = "ING_TESTDATA_LABELS";
    /** The meanings shipped inside the JAR, so an unconfigured install still reads German. */
    private static final String BUNDLED_LABELS = "testdaten-labels.properties";
    /** Key prefix for a column's own caption, as opposed to one of its values. */
    private static final String CAPTION_PREFIX = "_spalte.";
    /** Guardrail: never hand out these accounts, whatever the filter says. */
    private static final List<String> BLOCKLIST = List.of("9999999999");

    private static final java.awt.Color OK_BG = new java.awt.Color(0xE3, 0xF6, 0xE3);
    private static final java.awt.Color OK_FG = new java.awt.Color(0x1B, 0x5E, 0x20);
    private static final java.awt.Color WARN_BG = new java.awt.Color(0xFF, 0xF4, 0xD8);
    private static final java.awt.Color WARN_FG = new java.awt.Color(0x7A, 0x4F, 0x01);

    private final List<String> headers = new ArrayList<>();
    private final List<List<String>> rows = new ArrayList<>();
    /**
     * The raw rows currently on screen, in screen order.
     *
     * <p>This exists because the table shows two things that are not the same: a
     * <em>subset</em> of the rows (a filter is usually active) and a <em>translated</em>
     * view of them ({@link #plain}). Neither the selected index nor the displayed cells
     * can therefore be used to answer "which customer is this, really" — the index points
     * into this list, and the values come from here, never from the table model.
     */
    private final List<List<String>> visible = new ArrayList<>();
    private final Properties labels = new Properties();
    /** Rows read from the file but withheld because their shape makes them unreadable. */
    private int skipped;

    private JTable table;
    private DefaultTableModel model;
    private JLabel status;
    /**
     * One entry per column of the file, in the file's own order — never fewer.
     *
     * <p>Until 2026-07-28 a column whose values were too many or too few for a dropdown got
     * <em>no control at all</em>, and nothing on screen said so. Seven of the seventeen
     * columns on the tester's machine were in that state, "Bonität: Zusatzinfo" among them,
     * and a tester had no way to tell "cannot be filtered" from "is not in the data". The
     * kind of control varies now; the presence of one does not.
     */
    private final List<ColumnFilter> columnFilters = new ArrayList<>();
    private JTextField search;
    /** Notified with the account number whenever a copy actually succeeded. */
    private Consumer<String> onAccountChosen;
    /**
     * What happened to the customer profile on the last copy, in one German sentence.
     *
     * <p>Kept because the guided flow leaves this screen the moment a customer is copied:
     * a message that lives only in this panel's status line is gone before it can be read,
     * which is precisely how a failed write became invisible.
     */
    private String profileNote = "";
    /** Whether that last copy's profile really reached the test case. */
    private boolean profileRecorded;

    @Override
    public String getTitle() {
        return "Testdaten";
    }

    /**
     * Takes Studio's handle on the open project's test data and parks it where the profile
     * writer can find it.
     *
     * <p>Studio hands this to every panel it discovers, before or after the panel is built.
     * The handle is kept in {@link StudioTestData} rather than in a field here because the
     * writer is reached from the guided flow as well, and a plugin folder now gets one class
     * loader — so one static really is one value.
     */
    @Override
    public void setProjectTestData(ProjectTestDataApi testData) {
        StudioTestData.set(testData);
    }

    @Override
    public String getTooltip() {
        return "Passenden Testkunden auswählen - ohne Excel";
    }

    @Override
    public JComponent createPanel() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        status = new JLabel(" ");
        model = new DefaultTableModel(0, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        loadData();

        root.add(buildFilterBar(), BorderLayout.NORTH);
        root.add(new JScrollPane(table), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        applyFilters();
        return root;
    }

    // ------------------------------------------------------------------ data

    /** Loads the CSV and the label map. Any failure becomes a visible message, never a crash. */
    private void loadData() {
        String csv = System.getenv(ENV_CSV);
        if (csv == null || csv.isBlank()) {
            setStatus(false,
                "Keine Testdaten-Datei gesetzt. Bitte " + ENV_CSV + " auf die CSV-Datei zeigen lassen."
            );
            return;
        }
        Path path = Paths.get(csv);
        if (!Files.isRegularFile(path)) {
            setStatus(false, "Testdaten-Datei nicht gefunden: " + path);
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                setStatus(false, "Testdaten-Datei ist leer: " + path);
                return;
            }
            char sep = detectSeparator(lines.get(0));
            headers.addAll(splitCsv(lines.get(0), sep));
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) {
                    continue;
                }
                List<String> cells = splitCsv(lines.get(i), sep);
                if (cells.stream().anyMatch(BLOCKLIST::contains)) {
                    continue;
                }
                if (!usable(cells)) {
                    skipped++;
                    continue;
                }
                rows.add(cells);
            }
            setStatus(rows.size() + " Testkunden geladen" + skippedNote());
        } catch (IOException ex) {
            setStatus(false, "Testdaten konnten nicht gelesen werden: " + ex.getMessage());
        }

        loadLabels();
    }

    /**
     * The plain-German meanings: the map shipped with the plugin first, then whatever
     * {@code ING_TESTDATA_LABELS} points at on top.
     *
     * <p>Shipped first and overlaid rather than replaced, for two reasons. A tester who
     * installs the plugin and sets nothing at all still reads "Einzelkunde" instead of
     * "P" — the meanings are not customer data, so they can live in the JAR. And a site
     * that needs to correct one code does not have to re-state the other forty.
     *
     * <p>Both are read as UTF-8 explicitly. {@code Properties.load(InputStream)} is
     * defined to decode ISO-8859-1, which turned "Minderjährig" into "MinderjÃ¤hrig" on
     * screen — in a file whose entire job is spelling things out in German.
     */
    private void loadLabels() {
        try (var in = TestDataPanel.class.getResourceAsStream(BUNDLED_LABELS)) {
            if (in != null) {
                labels.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
            // A missing bundled map only costs readability, never function.
        }
        String labelFile = System.getenv(ENV_LABELS);
        if (labelFile != null && !labelFile.isBlank() && Files.isRegularFile(Paths.get(labelFile))) {
            try (var in = Files.newBufferedReader(Paths.get(labelFile), StandardCharsets.UTF_8)) {
                labels.load(in);
            } catch (IOException | RuntimeException ignored) {
                // Same: a broken override leaves the shipped meanings in place.
            }
        }
    }

    /** Excel exports are semicolon-separated in a German locale and comma-separated elsewhere. */
    private static char detectSeparator(String header) {
        return header.chars().filter(c -> c == ';').count() >= header.chars().filter(c -> c == ',').count()
            ? ';'
            : ',';
    }

    /** Minimal CSV split honouring quoted fields, which real exports do contain. */
    private static List<String> splitCsv(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == sep && !quoted) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    /**
     * Whether a row can honestly be offered as a customer.
     *
     * <p>A row that lost or gained a separator has its cells shifted, so every property
     * shown against it belongs to a different column. Handing such a row to a tester as "a
     * customer with Bonität 12" would be a lie the tester cannot detect, so the row is
     * withheld and counted rather than displayed.
     *
     * <p><b>Against a correctly converted export this count is one</b> — measured on
     * 2026-07-28 over the whole sheet: {@code BON_KUNDE} converts to 50,015 uniform rows, of
     * which the panel offers 50,014. The one it withholds is not a conversion fault at all —
     * that row's Kontonummer is five digits long, and an account number this panel cannot
     * hand over is a customer it must not offer. An earlier reading of "41% broken" was an
     * artefact of a
     * separator split that ignored quoting, and the real 5.4% was a converter bug (trailing
     * empty cells dropped), fixed in
     * <a href="https://github.com/Wladefant/ing-qa-automation/commit/24b5b14">24b5b14</a>.
     * So a non-zero count here now means <em>the file was produced by an older or different
     * converter</em>, not that the test data is unreliable — and that is what the screen
     * says.
     *
     * <p>Two rules, both about shape and neither about content:
     *
     * <ul>
     *   <li>the row must have exactly as many cells as the header — otherwise the columns
     *       do not line up at all;
     *   <li>where the file has a Kontonummer column, the value must look like an account
     *       number (digits only, at least six) — the account number is the one value that
     *       leaves this panel, and a shifted row usually shows a word there.
     * </ul>
     */
    private boolean usable(List<String> cells) {
        if (cells.size() != headers.size()) {
            return false;
        }
        int c = accountColumn();
        if (c < 0) {
            return true;
        }
        String account = cells.get(c).replace(" ", "");
        return account.length() >= 6 && account.chars().allMatch(Character::isDigit);
    }

    /** The withheld rows, said out loud — never silently dropped. */
    private String skippedNote() {
        if (skipped == 0) {
            return "";
        }
        return skipped == 1
            ? "  ·  1 Zeile ist unvollständig und wird deshalb nicht angeboten"
            : "  ·  " + skipped + " Zeilen sind unvollständig und werden deshalb nicht angeboten";
    }

    /** Plain-German meaning of a value, falling back to the raw value. */
    private String label(String column, String value) {
        String plain = labels.getProperty(column + "." + value);
        return plain == null || plain.isBlank() ? value : plain + " (" + value + ")";
    }

    /**
     * Plain-German caption for a column, falling back to the export's own header.
     *
     * <p>Translating the values alone was not enough: the header above them said
     * {@code Kbo5 Bonitaet S} and {@code MDJ_KND}, which is the same jargon problem one
     * row higher up. The raw header stays the internal key — it is what
     * {@link #accountColumn()} matches on and what the label file is keyed by — so this
     * is a display name and nothing more.
     */
    private String caption(String column) {
        String plain = labels.getProperty(CAPTION_PREFIX + column);
        return plain == null || plain.isBlank() ? column : plain;
    }

    /** The header row as the tester reads it. */
    private Object[] captions() {
        Object[] out = new Object[headers.size()];
        for (int c = 0; c < headers.size(); c++) {
            out[c] = caption(headers.get(c));
        }
        return out;
    }

    // ------------------------------------------------------------------ ui

    /** The dropdown entry that means "do not filter on this column". */
    private static final String ANY = "(egal)";
    /** The dropdown entry that means "this field is empty in the row". */
    private static final String BLANK = "(leer)";
    /**
     * How many entries a dropdown shows at once. Set on every box, so "the whole list is
     * visible without scrolling" is literally true of a column with this many values or
     * fewer — which is the only thing {@link #choiceLimit()} then borrows it for.
     */
    private static final int POPUP_ROWS = 12;
    /** Rows of controls the filter area shows before it starts scrolling. */
    private static final int VISIBLE_FILTER_ROWS = 6;

    /**
     * A control for every column — but only a <em>filter</em> where filtering is the question.
     *
     * <p>Two rules got this wrong in turn, and the second was mine. The first offered a
     * dropdown only for 2..25 distinct values and <b>nothing at all</b> otherwise, which
     * silently removed seven of the seventeen columns on the tester's machine — "Bonität:
     * Zusatzinfo" among them — with nothing on screen to distinguish "cannot be filtered"
     * from "not in the file". The second, over-correcting, gave every column a filter,
     * including the account number. The tester's answer to that: <em>"not every single one
     * needs to be chosen, Kontonummer for sure not"</em>.
     *
     * <p>They are right, and the reason is the shape of the whole screen: <b>a tester filters
     * by what kind of customer they need and receives who that customer is.</b> The account
     * number is the answer this panel gives; nobody types in the answer to find it. So a
     * column is sorted by what it is <em>for</em>, not by how many values it happens to have:
     *
     * <ul>
     *   <li><b>Property — dropdown</b> when the values are a closed set worth listing. See
     *       {@link #choiceLimit()}. Entries carry the plain-German meaning, sorted, plus a
     *       {@code (leer)} entry where some rows leave the field empty.
     *   <li><b>Property — text box</b> when there are too many values to list. "Bonität:
     *       Zusatzinfo" has 14,072 of them and is exactly the column that was reported
     *       missing, so this case is not optional. Contains-match, case-insensitive, against
     *       the string the table shows.
     *   <li><b>Identity — named, not filtered.</b> Kontonummer, Partnernummer,
     *       Personennummer, Geburtsdatum. Shown in the table as always; here they appear only
     *       so that "there is no filter for this" cannot be mistaken for "there is no such
     *       column". The free-text box below still searches them, for the tester who has a
     *       number in hand and wants to know whether it is in the pool.
     *   <li><b>Property that says nothing in this file</b> — every row identical, or empty
     *       throughout. Shown, disabled, and saying which. "Alle Zeilen: nein (N)" is a fact
     *       worth being told rather than left to infer from an absence.
     * </ul>
     */
    private JComponent buildFilterBar() {
        columnFilters.clear();

        JPanel choices = new WrapPanel();
        JPanel texts = new WrapPanel();
        JPanel identity = new WrapPanel();
        JPanel inert = new WrapPanel();
        int limit = choiceLimit();
        for (int c = 0; c < headers.size(); c++) {
            String column = headers.get(c);
            ColumnStats stats = measure(c);
            ColumnFilter f;
            JPanel group;
            if (isIdentity(column)) {
                f = identityFilter(c, column, stats);
                group = identity;
            } else if (stats.states() < 2) {
                f = inertFilter(c, column, stats);
                group = inert;
            } else if (stats.values.size() <= limit) {
                f = choiceFilter(c, column, stats);
                group = choices;
            } else {
                f = textFilter(c, column, stats);
                group = texts;
            }
            columnFilters.add(f);
            group.add(cell(column, f.control()));
        }

        FilterArea area = new FilterArea();
        int headings = 0;
        headings += addGroup(area, "Auswählen", choices);
        headings += addGroup(area, "Text suchen (enthält)", texts);
        headings += addGroup(area,
            "Wer der Kunde ist — steht in der Tabelle, danach sucht man nicht", identity);
        headings += addGroup(area, "Sagt in dieser Datei nichts aus", inert);

        JPanel bar = new JPanel(new BorderLayout(0, 2));
        bar.add(filterScroller(area, bar, headings), BorderLayout.CENTER);
        bar.add(searchRow(), BorderLayout.SOUTH);
        return bar;
    }

    /**
     * Column-name fragments that mean "this identifies the customer", not "this describes
     * them". Normalised the way {@link #normalised} does it, so spacing and case in the
     * export cannot matter.
     *
     * <p>Names rather than measurements, deliberately, and the class's own rule about not
     * hardcoding column names is bent here on purpose — because no measurement can tell the
     * two apart. Over the whole sheet Kontonummer has 25,181 distinct values and Kbo5
     * Zusatzinfo has 14,072: on every number they look alike, and one must be filterable
     * while the other must not. Only the name carries the difference.
     *
     * <p>{@link #accountColumn()} already matched on a name for the same reason. The
     * fallback direction is chosen with care: a column whose name is not on this list is
     * treated as a <b>property</b> and gets a filter. When the next export renames something,
     * the cost is a filter nobody wanted — never a column that vanished, which is the failure
     * this panel exists to prevent.
     */
    private static final List<String> IDENTITY = List.of(
        "kontonummer", "partnr", "partnernummer", "personennr", "personennummer",
        "geburtsdatum");

    private static boolean isIdentity(String column) {
        String name = normalised(column);
        for (String fragment : IDENTITY) {
            if (name.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String normalised(String column) {
        return column.toLowerCase().replace(" ", "").replace("_", "").replace("-", "");
    }

    /**
     * Where a dropdown stops being the better control, taken from the file rather than picked.
     *
     * <p>The number this replaces was 25, and 25 was a guess. The rule now has two halves and
     * both are statements about the file in front of it:
     *
     * <ul>
     *   <li><b>√rows.</b> A dropdown costs the tester one read of the whole list and leaves
     *       them, on average, {@code rows / distinct} rows. Once {@code distinct} exceeds
     *       {@code rows / distinct} — that is, once {@code distinct > √rows} — the list is
     *       longer than the result it produces, and typing three characters beats scrolling.
     *       On the 50,015-row export the tester now has, that is 224.
     *   <li><b>{@link #POPUP_ROWS}.</b> Under that, any list that fits in the popup without
     *       scrolling is worth offering anyway, however small the file. Without this floor a
     *       six-row sample would have no dropdowns at all, which is absurd for six rows.
     * </ul>
     *
     * <p>It decides between two controls for a column that is <em>already</em> known to be
     * worth filtering — it no longer has to tell a property from an identifier, which is a
     * question no count can answer and which the name now settles. On the tester's file the
     * limit is 224 and the property columns sit at 1, 2, 3, 4, 5, 7, 14 and 30, then jump to
     * 1,804 and 14,072; nothing lands near the line, so neither half of the rule is deciding
     * anything by a hair. Those counts are the whole sheet's, not a sample's: read from the
     * first 2,999 rows the same columns showed 1, 2…17 and 136, and two of them looked mute
     * when they are not.
     */
    private int choiceLimit() {
        return Math.max(POPUP_ROWS, (int) Math.round(Math.sqrt(rows.size())));
    }

    /** What one column holds, measured over every loaded row. The only input to the choice. */
    private static final class ColumnStats {
        /** Distinct non-blank values, in the order the file first shows them. */
        final List<String> values = new ArrayList<>();
        /**
         * How many rows leave this column empty.
         *
         * <p>Counted rather than merely noticed, because "some rows are empty" and "most
         * rows are empty" read the same to a boolean and not at all to a tester. The
         * customer number is filled on 37% of the export's rows; a control that says the
         * value stands in the table, full stop, is wrong about the other 63%.
         */
        int blanks;

        boolean hasBlank() {
            return blanks > 0;
        }

        /** Distinct states a tester could pick between, empty included. */
        int states() {
            return values.size() + (hasBlank() ? 1 : 0);
        }
    }

    private ColumnStats measure(int index) {
        ColumnStats stats = new ColumnStats();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (List<String> row : rows) {
            String value = index < row.size() ? row.get(index) : "";
            if (value.isBlank()) {
                stats.blanks++;
            } else {
                seen.add(value);
            }
        }
        stats.values.addAll(seen);
        return stats;
    }

    private ColumnFilter choiceFilter(int index, String column, ColumnStats stats) {
        JComboBox<String> box = new JComboBox<>();
        box.setMaximumRowCount(POPUP_ROWS);
        box.addItem(ANY);
        List<String> sorted = new ArrayList<>(stats.values);
        sorted.sort(byLabel(column));
        for (String value : sorted) {
            box.addItem(label(column, value));
        }
        if (stats.hasBlank()) {
            box.addItem(BLANK);
        }
        box.setToolTipText(caption(column) + ": " + stats.values.size()
            + " verschiedene Werte in dieser Datei"
            + (stats.hasBlank() ? ", dazu " + stats.blanks + " Zeilen mit leerem Feld" : "") + ".");
        box.addActionListener(e -> applyFilters());
        return ColumnFilter.choice(index, column, box);
    }

    private ColumnFilter textFilter(int index, String column, ColumnStats stats) {
        JTextField field = new JTextField(10);
        field.setToolTipText(caption(column) + ": " + stats.values.size()
            + " verschiedene Werte — zu viele für eine Liste. Text eintippen; gezeigt werden"
            + " die Zeilen, die ihn enthalten.");
        liveFilter(field);
        return ColumnFilter.text(index, column, field);
    }

    /**
     * The entry for a column that says <em>who</em> the customer is — named, never filtered.
     *
     * <p>Not a filter, and not silence either. Both would be wrong: filtering on the account
     * number asks the tester to type the answer they came here for, and leaving the column
     * out of the bar entirely is the very thing that was reported. So it is listed, disabled,
     * and it says where the value can be found instead.
     *
     * <p>And where it is <em>not</em> found. "It stands in the table" is true of the account
     * number, which every offered customer has, and false of the customer number, which only
     * 37% of the export's rows carry. Promising a value that two rows in three do not have
     * sends a tester looking for a column that is doing nothing wrong — the same wasted
     * search as a control that is missing, arrived at from the other direction. So a partly
     * filled column says how often it is empty, in figures.
     */
    private ColumnFilter identityFilter(int index, String column, ColumnStats stats) {
        boolean account = index == accountColumn();
        String shortText;
        String why;
        if (rows.isEmpty()) {
            shortText = "keine Zeilen";
            why = "Aus dieser Datei ist keine einzige Zeile geladen.";
        } else if (stats.values.isEmpty()) {
            shortText = "in dieser Datei leer";
            why = "Diese Spalte sagt, WER der Kunde ist — danach wird hier nicht gefiltert."
                + " In dieser Datei ist sie ausserdem bei jeder Zeile leer.";
        } else {
            // The box is twelve columns wide, so whatever matters has to come first: a
            // caption that begins "steht in der Tabelle…" and then qualifies itself is
            // qualified off the right-hand edge.
            shortText = stats.blanks == 0
                ? "steht in der Tabelle"
                : "nur bei " + Math.round(100.0 * (rows.size() - stats.blanks) / rows.size())
                    + "% der Zeilen";
            why = "Diese Spalte sagt, WER der Kunde ist, nicht was für einer. Sie ist das"
                + " Ergebnis der Suche, nicht die Suche"
                + (account ? " — unten mit \"Kontonummer kopieren\" zu holen." : ".")
                + (stats.blanks == 0
                    ? " In der Tabelle steht sie bei jedem Treffer;"
                    : " In der Tabelle steht sie, wo der Kunde eine hat — bei " + stats.blanks
                        + " von " + rows.size() + " Zeilen ist das Feld leer;")
                + " das Feld \"Freitext\" unten"
                + " durchsucht sie mit, wenn eine Nummer schon vorliegt.";
        }
        JTextField field = new JTextField(shortText, 12);
        field.setEditable(false);
        field.setEnabled(false);
        field.setToolTipText(why);
        return ColumnFilter.identity(index, column, field, why);
    }

    /**
     * The control for a column that cannot narrow anything — present, disabled, and saying so.
     *
     * <p>It is here rather than left out because "there is no filter for this" and "there is
     * no such column" look identical when both are absent, and only one of them is true.
     */
    private ColumnFilter inertFilter(int index, String column, ColumnStats stats) {
        String shortText;
        String why;
        if (rows.isEmpty()) {
            shortText = "keine Zeilen";
            why = "Aus dieser Datei ist keine einzige Zeile geladen — es gibt nichts zu filtern.";
        } else if (stats.values.isEmpty()) {
            shortText = "in dieser Datei leer";
            why = "Diese Spalte ist in dieser Datei bei jeder Zeile leer — es gibt nichts"
                + " auszuwählen.";
        } else {
            String only = label(column, stats.values.get(0));
            shortText = "alle Zeilen: " + only;
            why = "Alle " + rows.size() + " Zeilen haben denselben Wert (" + only + ")"
                + " — ein Filter darauf würde nichts einschränken.";
        }
        JTextField field = new JTextField(shortText, 12);
        field.setEditable(false);
        field.setEnabled(false);
        field.setToolTipText(why);
        return ColumnFilter.none(index, column, field, why);
    }

    /** Numbers by size, everything else by its German label — a long list has to be findable. */
    private Comparator<String> byLabel(String column) {
        return (a, b) -> {
            if (isNumber(a) && isNumber(b)) {
                return Long.compare(Long.parseLong(a), Long.parseLong(b));
            }
            return label(column, a).compareToIgnoreCase(label(column, b));
        };
    }

    private static boolean isNumber(String value) {
        return !value.isEmpty() && value.length() < 19
            && value.chars().allMatch(Character::isDigit);
    }

    /**
     * Re-filters shortly after the tester stops typing.
     *
     * <p>Seventeen boxes each demanding a press of Enter would be a worse screen than the one
     * this replaces. The delay is there so a 50,000-row file is not rebuilt once per keystroke;
     * Enter still applies immediately, for anybody who does not want to wait for it.
     */
    private void liveFilter(JTextField field) {
        javax.swing.Timer debounce = new javax.swing.Timer(250, e -> applyFilters());
        debounce.setRepeats(false);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                debounce.restart();
            }
        });
        field.addActionListener(e -> {
            debounce.stop();
            applyFilters();
        });
    }

    private JPanel cell(String column, JComponent control) {
        JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        cell.add(new JLabel(caption(column) + ":"));
        cell.add(control);
        return cell;
    }

    /**
     * Adds a named group, and only when it has something in it — an empty heading lies.
     *
     * @return 1 when a heading really went on screen, so the cap below can budget for it
     */
    private static int addGroup(JPanel area, String heading, JPanel group) {
        if (group.getComponentCount() == 0) {
            return 0;
        }
        JLabel title = new JLabel(heading);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        title.setForeground(new java.awt.Color(0x44, 0x44, 0x44));
        title.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 0));
        area.add(title);
        area.add(group);
        return 1;
    }

    /**
     * The filter area, capped so seventeen controls cannot push the table off the screen.
     *
     * <p>Two caps, and the smaller wins. The first is in <em>control rows</em>, measured from
     * a real combo box rather than written down in pixels, so it survives another font or
     * look-and-feel. The second is a share of the height this panel has actually been given,
     * because the panel is not always a full Studio tab: the guided flow puts it in the right
     * half of a split pane, where six rows of filters left the customer table about a hundred
     * pixels — filters winning space from the thing they exist to filter. That was measured on
     * screen, not reasoned about.
     *
     * <p>{@code within} is asked for its height rather than the window, and its height is
     * imposed by its own parent, so nothing here can feed back into itself.
     */
    private static JScrollPane filterScroller(FilterArea area, JComponent within, int headings) {
        int rowHeight = new JComboBox<String>().getPreferredSize().height + 8;
        // The headings are counted rather than assumed. Three was written down here while
        // there were three groups; the fourth arrived and its heading sat permanently cut in
        // half at the bottom edge — a number that was right once and then quietly was not.
        int cap = rowHeight * VISIBLE_FILTER_ROWS
            + headings * (new JLabel("X").getPreferredSize().height + 4);
        JScrollPane scroller = new JScrollPane(area,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override
            public Dimension getPreferredSize() {
                Dimension want = super.getPreferredSize();
                int limit = cap;
                Component root = within.getParent();
                if (root != null && root.getHeight() > 0) {
                    limit = Math.min(limit, Math.max(2 * rowHeight, root.getHeight() * 2 / 5));
                }
                return new Dimension(want.width, Math.min(want.height, limit));
            }
        };
        scroller.setBorder(BorderFactory.createEmptyBorder());
        scroller.getVerticalScrollBar().setUnitIncrement(16);
        return scroller;
    }

    /**
     * The free text over all columns, and the two buttons.
     *
     * <p>On a {@code WrapPanel} rather than a {@code FlowLayout} for the reason the whole
     * filter area is: at 900 pixels inside the guided flow the two buttons wrapped onto a
     * second line that the row had not reserved height for, and were drawn cut in half. That
     * was a pre-existing clip, invisible until the label beside them grew.
     */
    private JComponent searchRow() {
        JPanel searchRow = new WrapPanel();
        search = new JTextField(24);
        liveFilter(search);
        JButton go = new JButton("Suchen");
        go.addActionListener(e -> applyFilters());
        JButton reset = new JButton("Zurücksetzen");
        reset.addActionListener(e -> {
            for (ColumnFilter f : columnFilters) {
                f.clear();
            }
            search.setText("");
            applyFilters();
        });
        searchRow.add(new JLabel("Freitext (alle Spalten):"));
        searchRow.add(search);
        searchRow.add(go);
        searchRow.add(reset);
        return searchRow;
    }

    /**
     * The filter controls, laid out for the width they are really given.
     *
     * <p>A plain {@code FlowLayout} wraps when it paints but reports a preferred size for a
     * single row, so everything past the first row is laid out below the height its container
     * was granted and is simply never drawn. With six controls that was survivable; with
     * seventeen it would have re-created, in the filter bar, exactly the defect this panel
     * exists to remove. {@link WrapLayout} reports the height wrapping really needs.
     */
    private static final class FilterArea extends JPanel implements Scrollable {
        FilterArea() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    // A new width means a new number of rows, which means a new height.
                    revalidate();
                }
            });
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /**
     * A row of controls that wraps, and is honest about how tall wrapping made it.
     *
     * <p>The three things that have to travel together, or the wrap is a clip:
     * {@link WrapLayout} to compute the height, a maximum size that lets a {@code BoxLayout}
     * stretch it to the width the wrap was computed for, and a revalidate when the width
     * changes — because the number of rows is a function of the width and nothing else asks
     * the question again.
     */
    private static final class WrapPanel extends JPanel {
        WrapPanel() {
            super(new WrapLayout(10, 4));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    revalidate();
                }
            });
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /**
     * Left-to-right placement that wraps at the container's width and — the point of it —
     * reports the height that wrapping actually needs.
     */
    private static final class WrapLayout implements LayoutManager {

        private final int hgap;
        private final int vgap;

        WrapLayout(int hgap, int vgap) {
            this.hgap = hgap;
            this.vgap = vgap;
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
            // Nothing to remember: the order of getComponents() is the order on screen.
        }

        @Override
        public void removeLayoutComponent(Component comp) {
            // Likewise.
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return walk(parent, false);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return walk(parent, false);
        }

        @Override
        public void layoutContainer(Container parent) {
            walk(parent, true);
        }

        private Dimension walk(Container parent, boolean place) {
            Insets in = parent.getInsets();
            int limit = available(parent) - in.left - in.right;
            int x = in.left;
            int y = in.top;
            int rowHeight = 0;
            int used = 0;
            for (Component comp : parent.getComponents()) {
                if (!comp.isVisible()) {
                    continue;
                }
                Dimension size = comp.getPreferredSize();
                if (x > in.left && x - in.left + size.width > limit) {
                    x = in.left;
                    y += rowHeight + vgap;
                    rowHeight = 0;
                }
                if (place) {
                    comp.setBounds(x, y, size.width, size.height);
                }
                x += size.width + hgap;
                used = Math.max(used, x - in.left - hgap);
                rowHeight = Math.max(rowHeight, size.height);
            }
            return new Dimension(Math.min(used, limit) + in.left + in.right,
                y + rowHeight + in.bottom);
        }

        /**
         * The width to wrap at: the container's own once it has one, else the first ancestor
         * that does. Before the first layout nothing has a width at all, and the fallback only
         * decides the very first pass — the resize listener on {@link FilterArea} corrects it.
         */
        private static int available(Container parent) {
            int width = parent.getWidth();
            for (Container up = parent.getParent(); width <= 0 && up != null; up = up.getParent()) {
                width = up.getWidth();
            }
            return width > 0 ? width : 900;
        }
    }

    /** Which control a column got, and everything needed to ask it what it is filtering for. */
    private static final class ColumnFilter {

        private final int index;
        private final String column;
        private final String kind;
        private final JComboBox<String> box;
        private final JTextField field;
        /** For an inert column: the sentence explaining why nothing can be chosen. */
        private final String why;

        private ColumnFilter(int index, String column, String kind,
                JComboBox<String> box, JTextField field, String why) {
            this.index = index;
            this.column = column;
            this.kind = kind;
            this.box = box;
            this.field = field;
            this.why = why;
        }

        static ColumnFilter choice(int index, String column, JComboBox<String> box) {
            return new ColumnFilter(index, column, "auswahl", box, null, "");
        }

        static ColumnFilter text(int index, String column, JTextField field) {
            return new ColumnFilter(index, column, "text", null, field, "");
        }

        static ColumnFilter none(int index, String column, JTextField field, String why) {
            return new ColumnFilter(index, column, "ohne", null, field, why);
        }

        /** Says who the customer is. Listed so it cannot be missed; never filtered on. */
        static ColumnFilter identity(int index, String column, JTextField field, String why) {
            return new ColumnFilter(index, column, "identitaet", null, field, why);
        }

        JComponent control() {
            return box != null ? box : field;
        }

        void clear() {
            if (box != null) {
                box.setSelectedIndex(0);
            } else if ("text".equals(kind)) {
                field.setText("");
            }
        }
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        JButton copy = new JButton("Kontonummer kopieren");
        copy.addActionListener(e -> copySelected());
        footer.add(status, BorderLayout.CENTER);
        footer.add(copy, BorderLayout.EAST);
        footer.add(Box.createVerticalStrut(4), BorderLayout.NORTH);
        return footer;
    }

    /** Re-filters the table from the current dropdown + free-text state. */
    private void applyFilters() {
        model.setDataVector(new Object[0][0], captions());
        visible.clear();
        if (headers.isEmpty()) {
            return;
        }
        String needle = search == null ? "" : search.getText().trim().toLowerCase();
        int shown = 0;
        for (List<String> row : rows) {
            if (!matches(row, needle)) {
                continue;
            }
            visible.add(row);
            model.addRow(plain(row));
            shown++;
        }
        if (!rows.isEmpty()) {
            setStatus(shown + " von " + rows.size() + " Testkunden passen" + skippedNote());
        }
        fitColumns();
    }

    /**
     * Widens every column to what it actually holds.
     *
     * <p>The table does not auto-resize (the real export has dozens of columns and squeezing
     * them all into the viewport makes every one unreadable), so the default 75 pixels cut
     * "gute Bonität (12)" down to "gute Bonitä…" — the plain-German text that is the entire
     * point of this panel. Measured over the first rows only; a 370k-row workbook must not
     * pay for a cosmetic.
     */
    private void fitColumns() {
        if (table == null || model.getColumnCount() == 0) {
            return;
        }
        int sampled = Math.min(model.getRowCount(), 200);
        for (int c = 0; c < model.getColumnCount(); c++) {
            var column = table.getColumnModel().getColumn(c);
            int width = width(table.getTableHeader().getDefaultRenderer()
                .getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, c));
            for (int r = 0; r < sampled; r++) {
                width = Math.max(width, width(table.prepareRenderer(table.getCellRenderer(r, c), r, c)));
            }
            column.setPreferredWidth(Math.min(width + 14, 320));
        }
    }

    private static int width(java.awt.Component renderer) {
        return renderer == null ? 0 : renderer.getPreferredSize().width;
    }

    /**
     * A row as the tester reads it: every value that has a meaning is shown as that meaning.
     *
     * <p>The dropdowns were translated from the start, but the table itself still showed the
     * raw export — so the tester picked "Boni 12" in plain German and then had to read
     * {@code 12} back out of the result. The rule has no exception:
     * <em>"nur weil jemand eine Boni 21 hat, heißt es nicht, dass jeder weiß, dass das
     * unbekannt verzogen bedeutet"</em>. The code stays in brackets behind the meaning,
     * because a tester who has to quote the row to the test-data owner still needs it.
     */
    private Object[] plain(List<String> row) {
        // The Kontonummer is never translated: it is the one value that leaves this panel,
        // and copySelected() reads it straight back out of the table.
        int account = accountColumn();
        Object[] cells = new Object[row.size()];
        for (int c = 0; c < row.size(); c++) {
            cells[c] = c < headers.size() && c != account ? label(headers.get(c), row.get(c)) : row.get(c);
        }
        return cells;
    }

    private boolean matches(List<String> row, String needle) {
        for (ColumnFilter filter : columnFilters) {
            if (!accepts(filter, row)) {
                return false;
            }
        }
        if (needle.isEmpty()) {
            return true;
        }
        // Searched against what the table SHOWS, not the raw export: a tester who reads
        // "unbekannt verzogen" in a cell and types it must find that cell. The label always
        // carries the raw code in brackets, so this can only ever find more, never less.
        for (int c = 0; c < row.size(); c++) {
            String shown = c < headers.size() ? label(headers.get(c), row.get(c)) : row.get(c);
            if (shown.toLowerCase().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Whether one column's control lets this row through. */
    private boolean accepts(ColumnFilter filter, List<String> row) {
        String raw = filter.index < row.size() ? row.get(filter.index) : "";
        if (filter.box != null) {
            Object selected = filter.box.getSelectedItem();
            if (selected == null || ANY.equals(selected)) {
                return true;
            }
            if (BLANK.equals(selected)) {
                return raw.isBlank();
            }
            // The dropdown shows the plain-German label, so compare against the label too.
            return selected.equals(label(filter.column, raw));
        }
        if (!"text".equals(filter.kind)) {
            return true;
        }
        String needle = filter.field.getText().trim().toLowerCase();
        return needle.isEmpty() || label(filter.column, raw).toLowerCase().contains(needle);
    }

    /**
     * Index of the account-number column, or {@code -1} when the file has none.
     *
     * <p>Matched on the header text rather than a fixed position: the export's column order
     * changes between releases, but the name does not.
     */
    private int accountColumn() {
        for (int c = 0; c < headers.size(); c++) {
            if (headers.get(c).toLowerCase().replace(" ", "").contains("kontonummer")) {
                return c;
            }
        }
        return -1;
    }

    /**
     * The selected customer as they stand in the file — raw codes, nothing translated.
     *
     * <p>Two traps live here, and both were reported before this method existed. The
     * selected <em>row index</em> is an index into what is on screen, and a filter is
     * almost always active, so using it against {@link #rows} yields a different customer
     * entirely. And the table's own cells have already been through {@link #plain}, so
     * reading them back gives "Einzelkunde (P)" where the file says {@code P}. Anything
     * that leaves this panel — the clipboard, the profile written onto the test case —
     * must come from here.
     *
     * @return the raw row, or {@code null} when nothing is selected
     */
    private List<String> selectedRawRow() {
        int r = table == null ? -1 : table.getSelectedRow();
        if (r < 0) {
            return null;
        }
        int index = table.convertRowIndexToModel(r);
        return index >= 0 && index < visible.size() ? visible.get(index) : null;
    }

    /**
     * Copies the selected customer's account number, and records what kind of customer
     * they were on the chosen test case.
     *
     * <p>Only the Kontonummer goes to the clipboard because the clipboard is a working tool:
     * the number is what the tester pastes into the application while recording. The rest of
     * the row describes <em>which kind</em> of customer this is, which belongs on the test case
     * as a durable record — not in a paste buffer that the next copy overwrites. That record is
     * what {@link de.ing.qa.studio.TestCaseProfile} writes, and it deliberately keeps the
     * profile without the identity: the account number is dropped on the way in.
     *
     * <p>The profile is written <em>after</em> the clipboard, and its failure is never the
     * tester's problem — they clicked this button to get a number, and they get it either way.
     * <b>But it is always said out loud.</b> The confirmation used to be appended only when
     * the write succeeded, so "nothing was recorded" and "nothing looked" produced the same
     * sentence; the one case where a tester needs to know was the one case that said nothing.
     * Both outcomes now name themselves, and the failing one is coloured.
     */
    private void copySelected() {
        List<String> row = selectedRawRow();
        if (row == null) {
            JOptionPane.showMessageDialog(table, "Bitte zuerst einen Testkunden auswählen.");
            return;
        }
        int c = accountColumn();
        if (c < 0) {
            setStatus(false, "Keine Spalte \"Kontonummer\" in der Testdaten-Datei gefunden.");
            return;
        }
        String account = c < row.size() ? row.get(c).trim() : "";
        if (account.isEmpty()) {
            setStatus(false, "Dieser Testkunde hat keine Kontonummer.");
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(account), null);
        boolean saved = TestCaseProfile.saveForSelectedTestCase(headers, row);
        profileRecorded = saved;
        profileNote = saved
            ? "Die Eigenschaften des Testkunden wurden am Testfall vermerkt."
            : "Am Testfall wurde NICHTS vermerkt: " + whyNotSaved();
        setStatus(saved, "Kontonummer " + account + " kopiert. " + profileNote);
        if (onAccountChosen != null) {
            onAccountChosen.accept(account);
        }
    }

    /**
     * Why the profile did not reach the test case, in the tester's words.
     *
     * <p>{@link TestCaseProfile#saveForSelectedTestCase} answers only yes or no — its
     * reasons go to the log, which is not a place a tester looks. The two reasons that are
     * actually the tester's business are readable from here, from the same two sources that
     * method consults, so they are named rather than left as "es hat nicht geklappt".
     */
    private static String whyNotSaved() {
        if (de.ing.qa.studio.SelectedTestCase.read() == null) {
            return "Es ist noch kein Testfall übernommen — bitte zuerst einen Testfall wählen.";
        }
        if (de.ing.qa.studio.StudioTestData.get() == null) {
            return "Dieses Fenster ist mit keinem geöffneten Studio-Projekt verbunden.";
        }
        return "Der Testfall konnte nicht beschrieben werden. Bitte bei der "
            + "Testautomatisierung melden.";
    }

    /**
     * The status line, in a colour that matches what it says.
     *
     * <p>Everything the panel reports goes through here, so a warning colour set by one
     * message cannot survive into the next — which is how a green line ends up over a red
     * sentence.
     */
    private void setStatus(boolean ok, String text) {
        if (status == null) {
            return;
        }
        status.setText(text);
        status.setOpaque(true);
        status.setBackground(ok ? OK_BG : WARN_BG);
        status.setForeground(ok ? OK_FG : WARN_FG);
        status.revalidate();
        status.repaint();
    }

    /** A statement of fact with no verdict attached — counts, paths, load errors. */
    private void setStatus(String text) {
        if (status == null) {
            return;
        }
        status.setText(text);
        status.setOpaque(false);
        status.setForeground(java.awt.Color.BLACK);
        status.revalidate();
        status.repaint();
    }

    // ------------------------------------------------------------------ seams

    /**
     * Registers a listener told which account number was copied.
     *
     * <p>This is how the guided flow ({@link GuidedFlowPanel}) knows the customer step is
     * done without duplicating the picker: the panel keeps doing exactly what it does
     * standalone, and says so afterwards. Only a copy that really reached the clipboard
     * fires it.
     */
    public void setOnAccountChosen(Consumer<String> listener) {
        this.onAccountChosen = listener;
    }

    /** Harness/flow seam: how many customers are actually on offer. */
    public int offeredRowCount() {
        return rows.size();
    }

    /** Harness/flow seam: how many rows were withheld as off-shape. See {@link #usable}. */
    public int skippedRowCount() {
        return skipped;
    }

    /** Harness/flow seam: whatever the status line currently says. */
    public String statusText() {
        return status == null ? "" : status.getText();
    }

    /**
     * What became of the customer profile on the last copy — success or failure, always a
     * sentence.
     *
     * <p>Read by {@link GuidedFlowPanel}, which jumps to the next step the instant a
     * customer is copied and would otherwise carry this panel's answer off screen before
     * anybody saw it.
     *
     * @return the German sentence, or {@code ""} before the first copy
     */
    public String profileNote() {
        return profileNote;
    }

    /** Whether the last copy's profile really reached the test case. See {@link #profileNote()}. */
    public boolean profileRecorded() {
        return profileRecorded;
    }

    /**
     * Harness seam: the selected customer's raw row.
     *
     * <p>Public so a test can prove the thing that is easy to get wrong and impossible to
     * see — that a selection made in a <em>filtered</em> table resolves to the customer the
     * tester is actually looking at, carrying the file's own codes rather than their German
     * meanings.
     */
    public List<String> selectedRawRowForTest() {
        return selectedRawRow();
    }

    /**
     * Harness seam: types into the free-text box and filters, exactly as the tester does.
     *
     * <p>A seam rather than a component lookup because the guided flow keeps every step in
     * a CardLayout — the chooser's own search box is still in the tree while step 2 is on
     * screen, and "find the JTextField" would just as happily find the wrong one.
     */
    public void applyFreeTextForTest(String needle) {
        if (search != null) {
            search.setText(needle);
        }
        applyFilters();
    }

    /** Harness seam: the export's header row, as the profile is keyed by. */
    public List<String> headersForTest() {
        return List.copyOf(headers);
    }

    /**
     * Harness seam: the columns that really have a control, in screen order.
     *
     * <p>The one check worth having about this panel's filters is that this list and
     * {@link #headersForTest()} hold the same names — a column that quietly has no control is
     * the defect that was reported, and it is invisible from anything the tester can see.
     */
    public List<String> filterColumnsForTest() {
        List<String> out = new ArrayList<>();
        for (ColumnFilter f : columnFilters) {
            out.add(f.column);
        }
        return out;
    }

    /** Harness seam: the controls themselves, parallel to {@link #filterColumnsForTest()}. */
    public List<JComponent> filterControlsForTest() {
        List<JComponent> out = new ArrayList<>();
        for (ColumnFilter f : columnFilters) {
            out.add(f.control());
        }
        return out;
    }

    /**
     * Harness seam: which control a column got — {@code auswahl}, {@code text},
     * {@code identitaet}, {@code ohne}, or {@code ""} when the panel has no such column.
     */
    public String filterKindForTest(String column) {
        for (ColumnFilter f : columnFilters) {
            if (f.column.equals(column)) {
                return f.kind;
            }
        }
        return "";
    }

    /**
     * Harness seam: what a column with no filter says about itself — identity or inert —
     * or {@code ""} for one that really does filter.
     *
     * <p>Read by a check rather than left to a screenshot because "the control is there" and
     * "the control explains itself" are two claims, and only the second is the point.
     */
    public String filterReasonForTest(String column) {
        for (ColumnFilter f : columnFilters) {
            if (f.column.equals(column)) {
                return f.why;
            }
        }
        return "";
    }

    /** Harness seam: the entries a dropdown offers, {@code (egal)} included. */
    public List<String> filterChoicesForTest(String column) {
        for (ColumnFilter f : columnFilters) {
            if (f.column.equals(column) && f.box != null) {
                List<String> out = new ArrayList<>();
                for (int i = 0; i < f.box.getItemCount(); i++) {
                    out.add(f.box.getItemAt(i));
                }
                return out;
            }
        }
        return List.of();
    }

    /**
     * Harness seam: sets one column's filter and re-filters, as the tester does.
     *
     * @return {@code false} when the column has no live control, or the value is not on offer
     */
    public boolean applyColumnFilterForTest(String column, String value) {
        for (ColumnFilter f : columnFilters) {
            if (!f.column.equals(column)) {
                continue;
            }
            if (f.box != null) {
                f.box.setSelectedItem(value);
                applyFilters();
                return value.equals(f.box.getSelectedItem());
            }
            if ("text".equals(f.kind)) {
                f.field.setText(value);
                applyFilters();
                return true;
            }
            return false;
        }
        return false;
    }

    /** Harness seam: clears every filter, as the "Zurücksetzen" button does. */
    public void resetFiltersForTest() {
        for (ColumnFilter f : columnFilters) {
            f.clear();
        }
        if (search != null) {
            search.setText("");
        }
        applyFilters();
    }

    /** Harness seam: how many customers the table is showing right now. */
    public int visibleRowCount() {
        return visible.size();
    }

    /** Harness seam: the raw row behind a visible table line. */
    public List<String> visibleRowForTest(int index) {
        return index >= 0 && index < visible.size() ? List.copyOf(visible.get(index)) : List.of();
    }
}
