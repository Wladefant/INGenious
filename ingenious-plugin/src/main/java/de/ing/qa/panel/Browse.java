package de.ing.qa.panel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.net.URI;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Opens a URL in the tester's browser — or, when it cannot, hands the URL over in a
 * form they can actually use.
 *
 * <p>Three deliberate properties:
 *
 * <ul>
 *   <li><b>Guarded.</b> {@link Desktop#isDesktopSupported()} and
 *       {@code isSupported(Action.BROWSE)} are both checked. On a locked-down bank
 *       device either can be false, and calling {@code browse()} blindly throws.
 *   <li><b>Off the EDT.</b> Launching the default browser can block for seconds while
 *       Windows resolves and starts it; on the EDT that freezes the whole Studio.
 *   <li><b>Never a dead end.</b> Any failure shows the URL in a selectable, pre-selected
 *       text field so it can be copied and pasted by hand.
 * </ul>
 */
final class Browse {

    private Browse() {
    }

    /** True when this JVM can hand a URL to a browser at all. */
    static boolean supported() {
        try {
            return Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
        } catch (RuntimeException ex) {
            // Headless, or a desktop implementation that objects to being asked.
            return false;
        }
    }

    /**
     * Opens {@code url}, reporting progress through {@code status} (called on the EDT).
     *
     * @param parent component the fallback dialog is centred on, may be null
     * @param url the URL to open
     * @param status receives a German sentence for the panel's status line
     */
    static void open(Component parent, String url, Consumer<String> status) {
        if (url == null || url.isBlank()) {
            status.accept("Kein Azure-DevOps-Link fuer diesen Testfall vorhanden.");
            return;
        }
        if (!supported()) {
            status.accept("Der Browser kann von hier nicht gestartet werden — Link zum Kopieren:");
            showCopyable(parent, url,
                "Dieses Java kann keinen Browser oeffnen (Desktop.BROWSE nicht verfuegbar).");
            return;
        }
        status.accept("Azure DevOps wird geoeffnet: " + url);
        Thread worker = new Thread(() -> {
            try {
                Desktop.getDesktop().browse(new URI(url));
                SwingUtilities.invokeLater(() -> status.accept("Azure DevOps geoeffnet: " + url));
            } catch (Exception ex) {
                // IOException, URISyntaxException, SecurityException, and whatever a
                // managed device throws: all of them end the same way — give the tester
                // the URL instead of a stack trace they cannot act on.
                SwingUtilities.invokeLater(() -> {
                    status.accept("Browser konnte nicht geoeffnet werden — Link zum Kopieren:");
                    showCopyable(parent, url, "Grund: " + ex.getClass().getSimpleName()
                        + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
                });
            }
        }, "ado-browse");
        worker.setDaemon(true);
        worker.start();
    }

    /** A modal dialog whose URL field is selectable, pre-selected and ready for Ctrl+C. */
    private static void showCopyable(Component parent, String url, String reason) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html>" + escape(reason)
            + "<br>Bitte den Link kopieren (Strg+C) und im Browser einfuegen:</html>"),
            BorderLayout.NORTH);
        JTextField field = new JTextField(url);
        field.setEditable(false);
        field.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        field.setPreferredSize(new Dimension(560, 26));
        panel.add(field, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> {
            field.requestFocusInWindow();
            field.selectAll();
        });
        JOptionPane.showMessageDialog(parent, panel, "Azure-DevOps-Link",
            JOptionPane.INFORMATION_MESSAGE);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
