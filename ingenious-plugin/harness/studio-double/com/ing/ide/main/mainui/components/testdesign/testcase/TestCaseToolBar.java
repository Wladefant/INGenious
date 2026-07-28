package com.ing.ide.main.mainui.components.testdesign.testcase;

/** The two methods the panel reads the recording state through. Real names, real semantics. */
public class TestCaseToolBar {

    private boolean isRecording;

    public boolean isRecording() {
        return isRecording;
    }

    public void setRecordingState(boolean recording) {
        isRecording = recording;
    }
}
