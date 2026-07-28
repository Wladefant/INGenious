package com.ing.ide.main.mainui.components.testdesign.testcase;

/**
 * The toolbar of a Studio build that no longer answers {@code isRecording()}.
 *
 * <p>The state is still there and is still what {@code record()} branches on — only the name
 * the panel reads it through is gone. That is the dangerous shape of the problem, and not an
 * invented one: {@code StudioRecorder} reaches the recording state by reflection on
 * {@code isRecording}, while it reaches the toggle by reflection on {@code record}. Nothing
 * makes those two names travel together, so a core that renames the first while keeping the
 * second leaves the panel able to press the button and unable to see what it is pressing.
 *
 * <p>{@code StudioContractHarness} is the guard that turns exactly this into a red build
 * against the real jars. This double is what the panel must do on the machine of a tester who
 * has that build in front of them anyway.
 */
public class TestCaseToolBar {

    private boolean isRecording;

    /** The same state, under the name a renaming core gave it. Reflection on "isRecording" fails. */
    public boolean isRecordingNow() {
        return isRecording;
    }

    public void setRecordingState(boolean recording) {
        isRecording = recording;
    }
}
