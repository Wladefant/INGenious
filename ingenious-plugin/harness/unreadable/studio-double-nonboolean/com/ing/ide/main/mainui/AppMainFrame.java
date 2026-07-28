package com.ing.ide.main.mainui;

import com.ing.ide.main.mainui.components.testdesign.TestDesign;
import javax.swing.JFrame;

/** The Studio window the panel finds, on the build whose recording state is unreadable. */
public class AppMainFrame extends JFrame {

    private final TestDesign testDesign = new TestDesign();

    public TestDesign getTestDesign() {
        return testDesign;
    }
}
