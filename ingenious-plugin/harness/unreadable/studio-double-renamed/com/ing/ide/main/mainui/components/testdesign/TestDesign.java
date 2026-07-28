package com.ing.ide.main.mainui.components.testdesign;

import com.ing.ide.main.mainui.components.testdesign.testcase.TestCaseComponent;

/**
 * The one getter the panel walks through, plus the two ways it can fail to answer.
 *
 * <p>Both are constructed rather than argued about. In the real core {@code testcaseComp} is a
 * {@code final} field assigned in the constructor, so it is only ever {@code null} while that
 * constructor is still running — which is reachable from outside, because
 * {@code Frame.getFrames()} lists an {@code AppMainFrame} from the moment {@code Frame}'s own
 * constructor has run. {@link #setBuilt} reproduces that window. {@link #setThrowing}
 * reproduces the other half: a getter that exists and throws, which arrives at the panel as an
 * {@code InvocationTargetException} rather than a {@code NoSuchMethodException} and means
 * something different.
 */
public class TestDesign {

    private final TestCaseComponent testCaseComp = new TestCaseComponent();

    private boolean built = true;
    private boolean throwing;

    /** False = Studio is still assembling itself and this getter has nothing to hand back. */
    public void setBuilt(boolean value) {
        built = value;
    }

    /** True = the getter is there and blows up, the way a Studio in a bad moment does. */
    public void setThrowing(boolean value) {
        throwing = value;
    }

    public TestCaseComponent getTestCaseComp() {
        if (throwing) {
            throw new IllegalStateException("Kein Projekt geladen");
        }
        return built ? testCaseComp : null;
    }

    /** Reachable by the harness whatever the getter is currently pretending. */
    public TestCaseComponent core() {
        return testCaseComp;
    }
}
