package de.ing.qa.panel;

import com.ing.ingenious.api.contract.ui.StudioPanelApi;
import de.ing.qa.ado.AdoCache;
import de.ing.qa.ado.AdoTestCase;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Color;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;

/**
 * Studio screen answering one question: <em>what do I need to know before I start
 * this test case?</em>
 *
 * <p>It shows the test case currently chosen in "Testfall wählen" and leads with
 * <b>Voraussetzungen</b> — the preconditions describing what the customer must be
 * able to do. That block is what decides whether the test data at hand fits at all,
 * so it comes before the description and before the steps, not buried under them.
 *
 * <p>Because "Voraussetzungen" is not a standard ADO field, the panel also names the
 * field the text came from. A tester must be able to tell "this case states no
 * preconditions" apart from "we read the wrong field" — the second is a bug in the
 * tooling, and silently showing an empty box would hide it.
 *
 * <p>Same posture as the chooser: reads the local cache only, never calls ADO, does
 * its I/O on a {@link SwingWorker}, and turns every failure into a German sentence.
 *
 * <p><b>How it notices a new selection.</b> The Studio keeps every plugin screen in a
 * {@code CardLayout} and switches between them with {@code setVisible} — the panel is
 * built once and never re-added. So this listens on the hierarchy for
 * {@link HierarchyEvent#SHOWING_CHANGED} and re-reads the selection file every time it
 * actually becomes visible. Nothing is cached across that boundary; the file on disk is
 * the single source of truth.
 */
public class TestCaseOverviewPanel implements StudioPanelApi {

    static final String BTN_RELOAD = "Neu laden";
    static final String BTN_ADO = "In Azure DevOps öffnen";
    private static final Color LINK_FG = new Color(0x0B, 0x53, 0x94);
    private static final String NO_SELECTION =
        "Noch kein Testfall übernommen.\n\n"
            + "Bitte im Reiter \"Testfall wählen\" einen Testfall auswählen und auf\n"
            + "\"" + TestCaseChooserPanel.BTN_TAKE + "\" klicken.";

    private JLabel header;
    private JLabel adoLink;
    private JTextArea body;
    private JLabel status;
    private JButton openAdo;
    private JPanel root;
    private volatile boolean settled;
    /** URL of the case currently rendered, or null when there is nothing to open. */
    private volatile String webUrl;

    @Override
    public String getTitle() {
        return "Testfall-Übersicht";
    }

    @Override
    public String getTooltip() {
        return "Voraussetzungen und Schritte des übernommenen Testfalls";
    }

    @Override
    public JComponent createPanel() {
        root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        header = new JLabel(" ");
        header.setFont(header.getFont().deriveFont(Font.BOLD, header.getFont().getSize2D() + 3f));
        header.setAlignmentX(JComponent.LEFT_ALIGNMENT);

        adoLink = new JLabel(" ");
        adoLink.setForeground(LINK_FG);
        adoLink.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        adoLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        adoLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openInAdo();
            }
        });

        body = new JTextArea();
        body.setEditable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        status = new JLabel(" ");

        JPanel titleBlock = new JPanel();
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        titleBlock.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        titleBlock.add(header);
        titleBlock.add(adoLink);

        JPanel top = new JPanel(new BorderLayout());
        top.add(titleBlock, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        openAdo = new JButton(BTN_ADO);
        openAdo.addActionListener(e -> openInAdo());
        JButton reload = new JButton(BTN_RELOAD);
        reload.addActionListener(e -> reload());
        buttons.add(openAdo);
        buttons.add(reload);
        top.add(buttons, BorderLayout.EAST);

        root.add(top, BorderLayout.NORTH);
        root.add(new JScrollPane(body), BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);

        // The chooser writes the selection to a file; picking it up when this panel
        // becomes visible is what makes "choose, then switch tab" work without the two
        // panels having to know about each other. SHOWING_CHANGED — not an
        // AncestorListener alone — because the Studio's CardLayout only toggles
        // visibility on a screen it added long ago.
        root.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
                && root.isShowing()) {
                reload();
            }
        });

        reload();
        return root;
    }

    // ------------------------------------------------------------------ loading

    private void reload() {
        settled = false;
        status.setText("Wird geladen…");
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
                        show(null, "", "Testfälle konnten nicht geladen werden: " + ex.getMessage(), "");
                        return;
                    }
                    if (!snap.ok()) {
                        show(null, "Kein Testfall verfügbar", snap.problem(), "");
                        return;
                    }
                    // Always re-read the file: this method runs on every switch back to
                    // the screen, and the whole point is to see the newest selection.
                    String chosen = AdoCache.readSelectedId();
                    if (chosen == null) {
                        show(null, "Kein Testfall übernommen", NO_SELECTION,
                            snap.cases().size() + " Testfälle stehen zur Auswahl");
                        return;
                    }
                    AdoTestCase match = find(snap.cases(), chosen);
                    if (match == null) {
                        show(null, "Testfall " + chosen + " nicht gefunden",
                            "Der übernommene Testfall " + chosen + " steht nicht in der lokalen "
                                + "Testfall-Datei.\n\nMöglicherweise wurde er in ADO entfernt oder die "
                                + "Datei ist veraltet.\nBitte im Reiter \"Testfall wählen\" auf \""
                                + TestCaseChooserPanel.BTN_REFRESH + "\" klicken.",
                            "Datei: " + snap.source());
                        return;
                    }
                    show(match, match.adoId() + " — " + match.title(), render(match),
                        "Stand der Daten: "
                            + (snap.generatedAt().isBlank() ? "unbekannt" : snap.generatedAt()));
                } finally {
                    settled = true;
                }
            }
        }.execute();
    }

    private static AdoTestCase find(List<AdoTestCase> cases, String adoId) {
        for (AdoTestCase c : cases) {
            if (adoId.equals(c.adoId())) {
                return c;
            }
        }
        return null;
    }

    private void show(AdoTestCase c, String headerText, String bodyText, String statusText) {
        header.setText(headerText);
        body.setText(bodyText);
        body.setCaretPosition(0);
        status.setText(statusText == null || statusText.isBlank() ? " " : statusText);
        webUrl = c != null && c.hasWebUrl() ? c.webUrl() : null;
        boolean canOpen = webUrl != null;
        openAdo.setEnabled(canOpen);
        if (canOpen) {
            openAdo.setToolTipText("Öffnet " + webUrl + " im Browser");
            adoLink.setText("<html><a href=''>ADO-ID " + c.adoId()
                + " in Azure DevOps öffnen</a></html>");
            adoLink.setToolTipText(webUrl
                + (c.webUrlSource() == AdoTestCase.UrlSource.ADO
                    ? "  (Link von ADO geliefert)"
                    : "  (Link aus Organisation/Projekt der Testfall-Datei gebildet)"));
            adoLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        } else {
            String why = c == null
                ? "Es ist kein Testfall übernommen, der in Azure DevOps geöffnet werden könnte."
                : "Kein Azure-DevOps-Link ermittelbar: die Testfall-Datei enthält für diesen "
                    + "Testfall kein Feld \"url\" und keine Angaben zu Organisation/Projekt. "
                    + "Bitte im Reiter \"Testfall wählen\" auf \""
                    + TestCaseChooserPanel.BTN_REFRESH + "\" klicken.";
            openAdo.setToolTipText(why);
            adoLink.setText(" ");
            adoLink.setToolTipText(why);
            adoLink.setCursor(Cursor.getDefaultCursor());
        }
    }

    private void openInAdo() {
        String url = webUrl;
        if (url == null) {
            status.setText(String.valueOf(openAdo.getToolTipText()));
            return;
        }
        Browse.open(root, url, text -> status.setText(text));
    }

    /**
     * Voraussetzungen first — that block decides whether the tester can start at all.
     *
     * <p>Package-visible because the guided flow shows the very same text beside the
     * customer picker: the tester must read the requirements in the moment they choose,
     * and two renderings that could drift apart would be worse than one shared one.
     */
    static String render(AdoTestCase c) {
        StringBuilder sb = new StringBuilder();

        sb.append("VORAUSSETZUNGEN\n");
        if (c.preconditions().isBlank()) {
            sb.append("Für diesen Testfall sind in ADO keine Voraussetzungen hinterlegt.\n")
                .append("Achtung: das kann auch bedeuten, dass sie in einem Feld stehen, das noch nicht\n")
                .append("ausgelesen wird. Im Zweifel den Testfall in ADO öffnen.\n");
        } else {
            sb.append(c.preconditions()).append('\n');
            if (c.preconditionField() != null) {
                sb.append("(ADO-Feld: ").append(c.preconditionField()).append(")\n");
            }
        }

        sb.append("\nBESCHREIBUNG\n");
        sb.append(c.description().isBlank() ? "(keine Beschreibung in ADO)\n" : c.description() + "\n");

        sb.append("\nSCHRITTE\n");
        if (c.steps().isEmpty()) {
            sb.append("(keine Schritte in ADO hinterlegt)\n");
        } else {
            for (int i = 0; i < c.steps().size(); i++) {
                sb.append(i + 1).append(". ").append(c.steps().get(i)).append('\n');
            }
        }

        sb.append("\nEINORDNUNG\n");
        sb.append("Suite:    ").append(c.suiteName().isBlank() ? "(keine)" : c.suiteName()).append('\n');
        sb.append("Status:   ").append(c.state().isBlank() ? "(unbekannt)" : c.state()).append('\n');
        sb.append("Ergebnis: ").append(c.outcome().isBlank() ? "(noch nicht ausgeführt)" : c.outcome())
            .append('\n');
        if (c.hasWebUrl()) {
            sb.append("ADO-Link: ").append(c.webUrl()).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ test seam

    /** See {@link TestCaseChooserPanel#awaitSettled(long)}. Harness only, never the EDT. */
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

    /**
     * Harness seam: re-reads the selection exactly as the Studio's CardLayout switch
     * does, so a test can prove "choose, then switch tab" without a real window.
     */
    public void simulateBecameVisible() {
        reload();
    }

    /** Harness seam: the browser URL of the rendered case, or null when there is none. */
    public String webUrl() {
        return webUrl;
    }

    /** Harness seam: whether "In Azure DevOps öffnen" is offered. */
    public boolean adoActionEnabled() {
        return openAdo != null && openAdo.isEnabled();
    }

    /** Harness seam: the German explanation shown when the ADO action is unavailable. */
    public String adoActionTooltip() {
        return openAdo == null ? "" : String.valueOf(openAdo.getToolTipText());
    }
}
