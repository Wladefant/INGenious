import de.ing.qa.panel.TestDataPanel;
import javax.swing.JComponent;
import javax.swing.JTable;
import java.awt.Component;
import java.awt.Container;

/**
 * A DUMP of what {@code TestDataPanel} renders — not a check, and deliberately not something
 * whose exit code can be read as one.
 *
 * <p>It asserts nothing: zero columns, zero rows, all-null cells and a table belonging to the
 * wrong panel all print happily. Its only failure path was "no table at all", so every other
 * outcome exited 0 — and 0 is what a caller reads as "passed". Nothing in this directory runs
 * it, so the blast radius was a human reading {@code $?} by hand, but that is exactly how this
 * project has been bitten before.
 *
 * <p>It therefore <b>never</b> exits 0. Use {@link GuidedFlowHarness} for verdicts about this
 * panel; use this to look at it.
 */
public class Harness {
    static JTable findTable(Component c) {
        if (c instanceof JTable t) return t;
        if (c instanceof Container ct) {
            for (Component ch : ct.getComponents()) {
                JTable f = findTable(ch);
                if (f != null) return f;
            }
        }
        return null;
    }
    public static void main(String[] a) {
        TestDataPanel p = new TestDataPanel();
        System.out.println("title=" + p.getTitle());
        JComponent panel = p.createPanel();
        System.out.println("panel=" + (panel != null ? panel.getClass().getSimpleName() : "NULL"));
        JTable t = findTable(panel);
        if (t == null) { System.out.println("FAIL: no table"); System.exit(1); }
        System.out.println("columns=" + t.getColumnCount() + " rows=" + t.getRowCount());
        for (int c = 0; c < t.getColumnCount(); c++) System.out.print(t.getColumnName(c) + " | ");
        System.out.println();
        for (int r = 0; r < t.getRowCount(); r++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < t.getColumnCount(); c++) sb.append(t.getValueAt(r, c)).append(" | ");
            System.out.println("  " + sb);
        }
        System.out.println();
        System.out.println("This is a dump, not a check — nothing above was asserted. Exiting 2 "
            + "so no caller can read a verdict out of it. For verdicts: GuidedFlowHarness.");
        System.exit(2);
    }
}
