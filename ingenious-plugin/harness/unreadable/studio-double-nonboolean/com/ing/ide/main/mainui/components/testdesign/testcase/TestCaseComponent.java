package com.ing.ide.main.mainui.components.testdesign.testcase;

import java.util.concurrent.CompletableFuture;

/** The same toggle again, branching on the state the panel is being told nothing about. */
public class TestCaseComponent {

    private final TestCaseToolBar toolBar = new TestCaseToolBar();
    private CompletableFuture<Void> launchPlaywrightTask;
    private int recordCalls;

    public TestCaseToolBar getToolBar() {
        return toolBar;
    }

    public void record() {
        recordCalls++;
        if (toolBar.live()) {
            javax.swing.SwingUtilities.invokeLater(() -> {
                toolBar.setRecordingState(false);
                launchPlaywrightTask = null;
            });
            return;
        }
        if (launchPlaywrightTask != null && !launchPlaywrightTask.isDone()) {
            return;
        }
        launchPlaywrightTask = new CompletableFuture<>();
    }

    public void simulateRecorderReady() {
        if (launchPlaywrightTask != null) {
            launchPlaywrightTask.complete(null);
        }
        toolBar.setRecordingState(true);
    }

    public boolean recordingLive() {
        return toolBar.live();
    }

    public int recordCalls() {
        return recordCalls;
    }
}
