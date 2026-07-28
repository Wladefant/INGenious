package de.ing.qa.panel;

import com.ing.ingenious.api.contract.ui.StudioPanelApi;
import de.ing.qa.ado.AdoCache;
import de.ing.qa.ado.AdoTestCase;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;

/**
 * Studio screen for picking the ADO test case to work on next.
 *
 * <p>This closes a regression: the retired companion app had a searchable ADO chooser
 * (issue #82) and the Studio plugin did not.
 *
 * <p>Three things it deliberately does not do:
 *
 * <ul>
 *   <li><b>It never calls ADO.</b> It reads the cache written by
 *       {@code tools/ado-testcases.mjs}; "Aus ADO aktualisieren" shells out to that
 *       same tool. See {@link AdoCache} for why the one working Entra flow is not
 *       duplicated in Java.
 *   <li><b>It never blocks the EDT.</b> Panels can be built while the Studio starts,
 *       so both the initial read and the refresh run on a {@link SwingWorker}.
 *   <li><b>It never throws.</b> No cache, no node, no ADO — each becomes a German
 *       sentence in the status line and the panel stays usable.
 * </ul>
 *
 * <p>Choosing a case writes {@code selected-testcase.json} (path from
 * {@code ING_TESTCASE_SELECTION}, default beside the cache); the Testfall-Übersicht
 * panel and the rest of the tester flow read the id from there.
 *
 * <p><b>Why the confirmation is so loud.</b> The first version wrote the file and put a
 * single grey sentence in the status line at the bottom of a full-screen window. It
 * worked — the file was verifiably written on the tester machine — and the tester still
 * reported "nothing happens", because nothing they were looking at changed. Silent
 * success and a dead button look identical. So a take now changes four things at once:
 * a green confirmation banner over the detail pane, a ✔ marker that stays on the row,
 * the take button turning into "✔ Bereits übernommen", and the status line.
 */
public class TestCaseChooserPanel implements StudioPanelApi {

    static final String BTN_TAKE = "Diesen Testfall übernehmen";
    static final String BTN_TAKEN = "✔ Bereits übernommen";
    static final String BTN_REFRESH = "Aus ADO aktualisieren";
    static final String BTN_SEARCH = "Suchen";
    static final String BTN_RESET = "Zurücksetzen";
    static final String BTN_ADO = "In Azure DevOps öffnen";

    private static final Color OK_BG = new Color(0xE3, 0xF6, 0xE3);
    private static final Color OK_FG = new Color(0x1B, 0x5E, 0x20);
    private static final Color ERR_BG = new Color(0xFD, 0xE7, 0xE7);
    private static final Color ERR_FG = new Color(0xB7, 0x1C, 0x1C);
    private static final Color TAKEN_BG = new Color(0xE3, 0xF6, 0xE3);
    private static final Color LINK_FG = new Color(0x0B, 0x53, 0x94);

    private final List<AdoTestCase> all = new ArrayList<>();
    /** Set once a background load has settled, either with cases or with a problem. */
    private volatile boolean settled;
    /** The id currently written to the selection file — what makes the ✔ marker stick. */
    private String takenId;
    /** Notified with the case that was taken, once the selection file really was written. */
    private Consumer<AdoTestCase> onTaken;

    private JTextField search;
    private JList<AdoTestCase> list;
    private DefaultListModel<AdoTestCase> model;
    private JTextArea detail;
    private JLabel status;
    private JLabel confirm;
    private JLabel adoLink;
    private JButton take;
    private JButton refresh;
    private JButton openAdo;
    private JPanel root;

    @Override
    public String getTitle() {
        return "Testfall wählen";
    }

    @Override
    public String getTooltip() {
        return "ADO-Testfall suchen und für den Ablauf übernehmen";
    }

    @Override
    public JComponent createPanel() {
        root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        status = new JLabel("Testfälle werden geladen…");
        model = new DefaultListModel<>();
        list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((jlist, value, index, selected, focused) -> {
            boolean taken = value != null && takenId != null && takenId.equals(value.adoId());
            JLabel label = new JLabel(value == null ? "" : (taken ? "✔  " : "     ") + value.listLabel());
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            if (selected) {
                label.setBackground(jlist.getSelectionBackground());
                label.setForeground(jlist.getSelectionForeground());
            } else {
                label.setBackground(taken ? TAKEN_BG : jlist.getBackground());
                label.setForeground(taken ? OK_FG : jlist.getForeground());
            }
            if (taken) {
                label.setFont(label.getFont().deriveFont(Font.BOLD));
            }
            return label;
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail(list.getSelectedValue());
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    takeSelected();
                }
            }
        });

        detail = new JTextArea();
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            new JScrollPane(list), buildDetailSide());
        split.setResizeWeight(0.55);

        root.add(buildSearchBar(), BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);

        reload();
        return root;
    }

    // ------------------------------------------------------------------ ui

    private JComponent buildSearchBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        search = new JTextField(28);
        search.setToolTipText("Sucht in ADO-ID, Titel und Suite");
        search.addActionListener(e -> applyFilter());
        JButton go = new JButton(BTN_SEARCH);
        go.addActionListener(e -> applyFilter());
        JButton reset = new JButton(BTN_RESET);
        reset.addActionListener(e -> {
            search.setText("");
            applyFilter();
        });
        refresh = new JButton(BTN_REFRESH);
        refresh.addActionListener(e -> refreshFromAdo());
        bar.add(new JLabel("Suche:"));
        bar.add(search);
        bar.add(go);
        bar.add(reset);
        bar.add(refresh);
        return bar;
    }

    /** Detail pane plus the two things a tester must not have to hunt for: the
     *  confirmation of their last take, and the way into ADO. */
    private JComponent buildDetailSide() {
        JPanel side = new JPanel(new BorderLayout(0, 6));

        confirm = new JLabel(" ");
        confirm.setOpaque(true);
        confirm.setVisible(false);
        confirm.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        confirm.setFont(confirm.getFont().deriveFont(Font.BOLD));

        adoLink = new JLabel(" ");
        adoLink.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        adoLink.setForeground(LINK_FG);
        adoLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        adoLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openInAdo();
            }
        });

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(confirm, BorderLayout.NORTH);
        top.add(adoLink, BorderLayout.SOUTH);

        side.add(top, BorderLayout.NORTH);
        side.add(new JScrollPane(detail), BorderLayout.CENTER);
        return side;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        take = new JButton(BTN_TAKE);
        take.addActionListener(e -> takeSelected());
        openAdo = new JButton(BTN_ADO);
        openAdo.addActionListener(e -> openInAdo());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(openAdo);
        buttons.add(take);
        footer.add(status, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    /** Live search over the loaded snapshot. Typing filters; it never re-reads the file. */
    private void applyFilter() {
        String needle = search == null ? "" : search.getText();
        AdoTestCase previous = list.getSelectedValue();
        model.clear();
        for (AdoTestCase c : all) {
            if (c.matches(needle)) {
                model.addElement(c);
            }
        }
        if (previous != null && model.contains(previous)) {
            list.setSelectedValue(previous, true);
        } else if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        } else {
            showDetail(null);
        }
        if (!all.isEmpty()) {
            status.setText(model.size() + " von " + all.size() + " Testfällen passen");
        }
    }

    private void showDetail(AdoTestCase c) {
        if (detail == null) {
            return;
        }
        updateAdoAction(c);
        if (c == null) {
            detail.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (takenId != null && takenId.equals(c.adoId())) {
            sb.append("✔ DIESER TESTFALL IST ÜBERNOMMEN\n\n");
        }
        sb.append(c.title()).append("\n\n");
        sb.append("ADO-ID:   ").append(c.adoId()).append('\n');
        if (!c.suiteName().isBlank()) {
            sb.append("Suite:    ").append(c.suiteName()).append('\n');
        }
        if (!c.state().isBlank()) {
            sb.append("Status:   ").append(c.state()).append('\n');
        }
        if (!c.outcome().isBlank()) {
            sb.append("Ergebnis: ").append(c.outcome()).append('\n');
        }
        if (c.hasWebUrl()) {
            sb.append("ADO-Link: ").append(c.webUrl()).append('\n');
        }
        if (!c.preconditions().isBlank()) {
            sb.append("\nVoraussetzungen\n").append(c.preconditions()).append('\n');
        }
        if (!c.description().isBlank()) {
            sb.append("\nBeschreibung\n").append(c.description()).append('\n');
        }
        if (!c.steps().isEmpty()) {
            sb.append("\nSchritte\n");
            for (int i = 0; i < c.steps().size(); i++) {
                sb.append(i + 1).append(". ").append(c.steps().get(i)).append('\n');
            }
        }
        detail.setText(sb.toString());
        detail.setCaretPosition(0);
    }

    /** Keeps the link label, the ADO button and the take button honest about the row. */
    private void updateAdoAction(AdoTestCase c) {
        boolean canOpen = c != null && c.hasWebUrl();
        if (openAdo != null) {
            openAdo.setEnabled(canOpen);
            openAdo.setToolTipText(canOpen
                ? "Öffnet " + c.webUrl() + " im Browser"
                : disabledAdoReason(c));
        }
        if (adoLink != null) {
            if (canOpen) {
                adoLink.setText("<html><a href=''>ADO-ID " + c.adoId()
                    + " in Azure DevOps öffnen</a></html>");
                adoLink.setToolTipText(c.webUrl()
                    + (c.webUrlSource() == AdoTestCase.UrlSource.ADO
                        ? "  (Link von ADO geliefert)"
                        : "  (Link aus Organisation/Projekt der Testfall-Datei gebildet)"));
                adoLink.setEnabled(true);
                adoLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            } else {
                adoLink.setText(c == null ? " " : "ADO-ID " + c.adoId() + " — kein Link verfügbar");
                adoLink.setToolTipText(disabledAdoReason(c));
                adoLink.setEnabled(false);
                adoLink.setCursor(Cursor.getDefaultCursor());
            }
        }
        if (take != null && c != null) {
            boolean already = takenId != null && takenId.equals(c.adoId());
            take.setText(already ? BTN_TAKEN : BTN_TAKE);
            take.setToolTipText((already ? "Dieser Testfall steht bereits in " : "Schreibt die ADO-ID nach ")
                + selectionPathText());
        }
    }

    /**
     * The selection path as text, never a throw. {@code ING_TESTCASE_SELECTION} can hold
     * something {@code Paths.get} rejects outright, and an unchecked exception raised
     * while building a tooltip would take the whole click with it.
     */
    private static String selectionPathText() {
        try {
            return String.valueOf(AdoCache.selectionPath());
        } catch (RuntimeException ex) {
            return "(ungültiger Pfad in " + AdoCache.ENV_SELECTION + ": " + ex.getMessage() + ")";
        }
    }

    /** Never a guessed URL: when we cannot build one, we say exactly why. */
    private static String disabledAdoReason(AdoTestCase c) {
        if (c == null) {
            return "Bitte zuerst einen Testfall auswählen.";
        }
        return "Kein Azure-DevOps-Link ermittelbar: die Testfall-Datei enthält für diesen "
            + "Testfall kein Feld \"url\" und keine Angaben zu Organisation/Projekt. "
            + "Bitte \"" + BTN_REFRESH + "\" ausführen — die neue Datei enthält den Link von ADO.";
    }

    // ------------------------------------------------------------------ actions

    /** Reads the cache off the EDT and repopulates. Called at build time and after a refresh. */
    private void reload() {
        settled = false;
        setBusy(true, "Testfälle werden geladen…");
        new SwingWorker<AdoCache.Snapshot, Void>() {
            @Override
            protected AdoCache.Snapshot doInBackground() {
                return AdoCache.load();
            }

            @Override
            protected void done() {
                try {
                    AdoCache.Snapshot snap;
                    try {
                        snap = get();
                    } catch (Exception ex) {
                        // AdoCache.load() reports rather than throws, so this is defence
                        // in depth: even an interrupted worker must not kill the panel.
                        setBusy(false, "Testfälle konnten nicht geladen werden: " + ex.getMessage());
                        return;
                    }
                    all.clear();
                    if (!snap.ok()) {
                        model.clear();
                        showDetail(null);
                        setBusy(false, snap.problem());
                        return;
                    }
                    all.addAll(snap.cases());
                    takenId = AdoCache.readSelectedId();
                    applyFilter();
                    setBusy(false, all.size() + " Testfälle geladen"
                        + (snap.generatedAt().isBlank() ? "" : " (Stand " + snap.generatedAt() + ")"));
                    preselectFromFile();
                } finally {
                    settled = true;
                }
            }
        }.execute();
    }

    /** Highlights the case already chosen, so reopening the panel shows where you are. */
    private void preselectFromFile() {
        String chosen = takenId;
        if (chosen == null) {
            return;
        }
        for (int i = 0; i < model.size(); i++) {
            if (chosen.equals(model.get(i).adoId())) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                showDetail(model.get(i));
                banner(false, "✔ Testfall " + chosen + " ist übernommen — in \"Testfall-Übersicht\" ansehen");
                status.setText("Aktuell übernommen: Testfall " + chosen);
                return;
            }
        }
    }

    /**
     * Writes the chosen id where the rest of the flow reads it, then makes that
     * unmistakable on screen. Catches {@link Exception}, not just {@code IOException}: a
     * malformed {@code ING_TESTCASE_SELECTION} throws {@code InvalidPathException}, which
     * is unchecked — it used to escape into the EDT and produce exactly the "nothing
     * happens" the tester reported.
     */
    private void takeSelected() {
        AdoTestCase c = list.getSelectedValue();
        if (c == null) {
            banner(true, "Bitte zuerst einen Testfall in der Liste auswählen.");
            status.setText("Bitte zuerst einen Testfall auswählen.");
            return;
        }
        try {
            Path written = AdoCache.writeSelection(c);
            takenId = c.adoId();
            String message = "Testfall " + c.adoId() + " übernommen — jetzt in \"Testfall-Übersicht\" ansehen";
            banner(false, "✔ " + message);
            confirm.setToolTipText("Gespeichert in " + written);
            status.setText(message);
            status.setToolTipText("Gespeichert in " + written);
            showDetail(c);
            list.repaint();
            if (onTaken != null) {
                onTaken.accept(c);
            }
        } catch (Exception ex) {
            String where = selectionPathText();
            String message = "Testfall " + c.adoId() + " konnte NICHT gespeichert werden: "
                + ex.getClass().getSimpleName()
                + (ex.getMessage() == null ? "" : ": " + ex.getMessage())
                + " — Datei: " + where;
            banner(true, message);
            status.setText(message);
        }
    }

    /** Green for a take that worked, red for one that did not. Never nothing. */
    private void banner(boolean error, String text) {
        if (confirm == null) {
            return;
        }
        confirm.setText(text);
        confirm.setBackground(error ? ERR_BG : OK_BG);
        confirm.setForeground(error ? ERR_FG : OK_FG);
        confirm.setToolTipText(null);
        confirm.setVisible(true);
        confirm.revalidate();
        confirm.repaint();
    }

    private void openInAdo() {
        AdoTestCase c = list == null ? null : list.getSelectedValue();
        if (c == null || !c.hasWebUrl()) {
            status.setText(disabledAdoReason(c));
            return;
        }
        Browse.open(root, c.webUrl(), text -> status.setText(text));
    }

    /** Re-runs the Node tool off the EDT, then reloads. Offline this reports and stops. */
    private void refreshFromAdo() {
        settled = false;
        setBusy(true, "Testfälle werden aus ADO geladen… (das kann eine Minute dauern)");
        new SwingWorker<AdoCache.RefreshResult, Void>() {
            @Override
            protected AdoCache.RefreshResult doInBackground() {
                return AdoCache.refresh();
            }

            @Override
            protected void done() {
                AdoCache.RefreshResult result;
                try {
                    result = get();
                } catch (Exception ex) {
                    setBusy(false, "Aktualisieren fehlgeschlagen: " + ex.getMessage());
                    settled = true;
                    return;
                }
                if (!result.ok()) {
                    // Offline is the expected case on a machine without ADO reach: say so
                    // and keep whatever snapshot is already on screen.
                    setBusy(false, result.message());
                    settled = true;
                    return;
                }
                reload();
            }
        }.execute();
    }

    private void setBusy(boolean busy, String message) {
        if (status != null) {
            status.setText(message);
            status.setToolTipText(null);
        }
        if (refresh != null) {
            refresh.setEnabled(!busy);
        }
        if (take != null) {
            take.setEnabled(!busy);
        }
        if (openAdo != null && busy) {
            openAdo.setEnabled(false);
        }
    }

    // ------------------------------------------------------------------ seams

    /**
     * Registers a listener told which case was taken.
     *
     * <p>This is how the guided flow ({@link GuidedFlowPanel}) reuses this chooser whole —
     * search, detail, ADO link, loud confirmation and all — instead of growing a second
     * copy of it. Fired only after {@link AdoCache#writeSelection} really wrote the file,
     * so a failed take never advances anything.
     */
    public void setOnTaken(Consumer<AdoTestCase> listener) {
        this.onTaken = listener;
    }

    // ------------------------------------------------------------------ test seam

    /**
     * Blocks until the panel's background load has settled — loaded or reported as a
     * problem — or the timeout expires. Exists for the headless harness; the Studio
     * never calls it, and it must never be called from the EDT.
     *
     * @return true if the load settled within the timeout
     */
    public boolean awaitSettled(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (settled) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return settled;
    }

    /** Harness seam: the confirmation banner's text, or "" when it is hidden. */
    public String confirmationMessage() {
        return confirm == null || !confirm.isVisible() ? "" : confirm.getText();
    }

    /** Harness seam: the browser URL of the current row, or null when there is none. */
    public String selectedWebUrl() {
        AdoTestCase c = list == null ? null : list.getSelectedValue();
        return c == null || !c.hasWebUrl() ? null : c.webUrl();
    }

    /** Harness seam: whether "In Azure DevOps öffnen" is offered for the current row. */
    public boolean adoActionEnabled() {
        return openAdo != null && openAdo.isEnabled();
    }

    /** Harness seam: the German explanation shown when the ADO action is unavailable. */
    public String adoActionTooltip() {
        return openAdo == null ? "" : String.valueOf(openAdo.getToolTipText());
    }
}
