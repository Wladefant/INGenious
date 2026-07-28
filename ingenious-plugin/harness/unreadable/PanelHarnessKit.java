package unreadable;

import de.ing.qa.panel.GuidedFlowPanel;
import de.ing.qa.panel.RecorderProbe;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/**
 * The parts of {@code GuidedFlowHarness} that are about driving a Swing panel rather than
 * about any one scenario, so the two unreadable-Studio harnesses beside this file can share
 * them.
 *
 * <p>They are copied rather than called: {@code GuidedFlowHarness}'s versions are private, it
 * is another lane's file this week, and these harnesses need their own classpath anyway — a
 * Studio double whose class names collide with the good one cannot be in the same JVM as it.
 * The two rules they encode are the same two, and they are the reason this is a copy and not
 * a shortcut:
 *
 * <ul>
 *   <li><b>Judge from the visible windows, never from a responsive Event Dispatch Thread.</b>
 *       A modal dialog pumps the event queue, so an EDT that still answers proves nothing.
 *   <li><b>The panel is rendered, not merely constructed</b> — packed, laid out, and captured
 *       with {@code printAll}, so this runs beside somebody who is using the machine.
 * </ul>
 */
final class PanelHarnessKit {

    static int checks;
    static int failures;
    static int unproven;
    static File shotDir;
    private static JFrame frame;

    private PanelHarnessKit() {
    }

    /**
     * Prints the verdict and returns the exit code: <b>0 green, 4 UNGEPRUEFT, 1 red</b> — the
     * same three the suite uses everywhere else, and for the same reason.
     *
     * <p>Until 2026-07-28 this returned only 0 or 1, and the harnesses on top of it therefore
     * had no way to say <em>"nothing failed; I never got to ask"</em>. A missing fixture came
     * out as a RED verdict about the product, which is a false statement in the expensive
     * direction: somebody goes looking for a defect in a panel over an environment variable.
     * 4 is the vocabulary for that, and {@code run-all.sh} already reads it — it greps the log
     * for {@code UNGEPRUEFT} and files the run under UNPROVED rather than FAILED.
     *
     * <p>A failure still outranks a not-asked: if anything really did fail, that is the more
     * important sentence and 1 is returned.
     */
    static int verdict() {
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

    /**
     * A question this run could not ask — a fixture that is not there, an environment variable
     * that names nothing. Deliberately NOT a failed {@code check}: the product was never put to
     * the test, so nothing about it was learnt, and saying "RED" would be inventing a verdict.
     */
    static void unproven(String what, String why) {
        unproven++;
        System.out.println("  UNGEPRUEFT " + what + "   [" + why + "]");
    }

    static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + "   [" + detail + "]");
    }

    /** Builds the panel on the EDT and packs it into a frame that is never shown. */
    static JComponent build(GuidedFlowPanel flow) throws Exception {
        AtomicReference<JComponent> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JComponent view = flow.createPanel();
            frame = new JFrame("harness (never shown)");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(view);
            frame.pack();
            frame.setSize(1500, 950);
            frame.validate();
            ref.set(view);
        });
        return ref.get();
    }

    /** Lets every queued Swing task run — the flow uses invokeLater on purpose. */
    static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(120);
        }
    }

    /**
     * Fails if ANY window is showing. A modal dialog keeps the Event Dispatch Thread pumping,
     * so liveness is no evidence — the window list is.
     */
    static void noWindows(String where) {
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

    static void shoot(JComponent view, String name) throws Exception {
        File out = new File(shotDir, name + ".png");
        SwingUtilities.invokeAndWait(() -> {
            // An unshown frame has no RepaintManager pumping revalidate(), so a line that just
            // became visible would still have zero height. Lay it out by hand.
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

    /** Walks the flow to step 3 the way a tester does, so the recording step is on screen. */
    static void toStep3(GuidedFlowPanel flow, JComponent view) throws Exception {
        JList<?> list = (JList<?>) find(view, JList.class, null);
        select(list, 0);
        click(view, "Diesen Testfall übernehmen");
        settle();
        JTable table = (JTable) find(view, JTable.class, null);
        SwingUtilities.invokeAndWait(() -> table.setRowSelectionInterval(0, 0));
        click(view, "Kontonummer kopieren");
        settle();
        check("Schritt 3 ist erreicht", flow.currentStep() == 2, "step=" + flow.currentStep());
    }

    /**
     * Lays the panel out at another window size, because a clipped line reads differently.
     *
     * <p>The lines that clip rather than wrap are only safe if what matters is at the front,
     * and that is a claim about a width nobody here runs at by default. It has to be rendered
     * narrow to be a claim about anything.
     */
    static void resizeTo(int width, int height) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (frame != null) {
                frame.setSize(width, height);
                frame.validate();
            }
        });
        settle();
    }

    /** Re-reads Studio's state on the EDT, as the once-a-second poll does. */
    static void poll(GuidedFlowPanel flow) throws Exception {
        SwingUtilities.invokeAndWait(flow::pollRecorderState);
        settle();
    }

    /** The recorder state as the panel reads it, on the EDT. */
    static String probeState() throws Exception {
        AtomicReference<String> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(RecorderProbe.state()));
        return out.get();
    }

    /** One start attempt through the package-private recorder, on the EDT where it belongs. */
    static String probeStart() throws Exception {
        AtomicReference<String> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(RecorderProbe.start()));
        return out.get();
    }

    /** One stop attempt through the package-private recorder. */
    static String probeStop() throws Exception {
        AtomicReference<String> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(RecorderProbe.stop()));
        return out.get();
    }

    static void select(JList<?> list, int index) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        });
    }

    static void click(Component root, String text) throws Exception {
        AbstractButton button = (AbstractButton) find(root, AbstractButton.class, text);
        if (button == null) {
            check("Knopf \"" + text + "\" existiert", false, "nicht gefunden");
            return;
        }
        SwingUtilities.invokeAndWait(button::doClick);
    }

    static Component find(Component c, Class<?> type, String text) {
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
}
