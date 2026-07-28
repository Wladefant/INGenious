package com.ing.ide.main.mainui.components.testdesign.testcase;

import java.util.concurrent.CompletableFuture;

/**
 * The same toggle as {@code harness/studio-double}'s, on a build whose toolbar getter was
 * renamed.
 *
 * <p>Branch for branch it is still
 * {@code INGenious/IDE/.../TestCaseComponent.record()} lines 675-690 — the first statement
 * still ends a running recording. Only the read the <em>panel</em> uses has moved out of
 * reach; the read {@code record()} itself does has not, because it is a field access inside
 * the same class and no rename can break it. That asymmetry is the whole point: on this build
 * the panel cannot see the recording, and the toggle can still end it.
 *
 * <p>In the real core that first branch is {@code stopPlaywrightRecording()}, which destroys
 * the recorder's browser process tree and finalises the file. There is no undo behind it.
 */
public class TestCaseComponent {

    private final TestCaseToolBar toolBar = new TestCaseToolBar();
    private CompletableFuture<Void> launchPlaywrightTask;

    /** How often the toggle was entered — the count that proves a call did NOT happen. */
    private int recordCalls;

    public TestCaseToolBar getToolBar() {
        return toolBar;
    }

    /** The toggle, branch for branch, reading the state under its new name. */
    public void record() {
        recordCalls++;
        if (toolBar.isRecordingNow()) {
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

    /** What {@code onRecorderReady()} does when the browser is finally up. */
    public void simulateRecorderReady() {
        if (launchPlaywrightTask != null) {
            launchPlaywrightTask.complete(null);
        }
        toolBar.setRecordingState(true);
    }

    /** Whether a recording is live — asked by the harness, not reachable by the panel. */
    public boolean recordingLive() {
        return toolBar.isRecordingNow();
    }

    public int recordCalls() {
        return recordCalls;
    }
}
