package com.ing.ide.main.mainui;

import com.ing.ide.main.mainui.components.testdesign.TestDesign;
import javax.swing.JFrame;

/**
 * A window with the one class name {@code StudioRecorder} matches on.
 *
 * <p>It is never shown. {@code Frame.getFrames()} lists every frame that has been
 * constructed, shown or not, which is exactly what the panel searches — so the harness can
 * put a Studio in front of the panel without putting a window in front of whoever is using
 * the machine.
 */
public class AppMainFrame extends JFrame {

    private final TestDesign testDesign = new TestDesign();

    public TestDesign getTestDesign() {
        return testDesign;
    }
}
