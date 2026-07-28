package com.ing.ide.main.mainui.components.testdesign;

import com.ing.ide.main.mainui.components.testdesign.testcase.TestCaseComponent;

/** The getter chain, answering normally — the failure in this build is one level further down. */
public class TestDesign {

    private final TestCaseComponent testCaseComp = new TestCaseComponent();

    public TestCaseComponent getTestCaseComp() {
        return testCaseComp;
    }
}
