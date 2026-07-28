package com.ing.ide.main.mainui;

import com.ing.ide.main.mainui.components.testdesign.TestDesign;
import javax.swing.JFrame;

/**
 * A Studio window with the one class name {@code StudioRecorder} matches on — belonging to a
 * build the panel cannot read.
 *
 * <p>Never shown, like its sibling in {@code harness/studio-double}:
 * {@code Frame.getFrames()} lists every frame that has been constructed, shown or not, so a
 * Studio can be put in front of the panel without putting a window in front of whoever is
 * using the machine.
 */
public class AppMainFrame extends JFrame {

    private final TestDesign testDesign = new TestDesign();

    public TestDesign getTestDesign() {
        return testDesign;
    }
}
