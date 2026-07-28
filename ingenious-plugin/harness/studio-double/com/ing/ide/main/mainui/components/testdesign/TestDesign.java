package com.ing.ide.main.mainui.components.testdesign;

import com.ing.datalib.component.Project;
import com.ing.ide.main.mainui.components.testdesign.testcase.TestCaseComponent;

/** The two getters the panel walks through to reach the recorder and the project settings. */
public class TestDesign {

    private final TestCaseComponent testCaseComp = new TestCaseComponent();
    private final Project project = new Project();

    public TestCaseComponent getTestCaseComp() {
        return testCaseComp;
    }

    public Project getProject() {
        return project;
    }
}
