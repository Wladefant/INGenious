package com.ing.ide.main.mainui.components.testdesign.testcase;

import java.util.concurrent.CompletableFuture;

/**
 * The recorder's toggle, reproduced exactly, so a test can press it twice.
 *
 * <p><b>Why a double exists at all.</b> The bug being fixed only shows itself against a
 * Studio that is <em>already recording</em>, and the real recorder gets there by launching
 * Playwright and a browser. That is not something a panel harness can reach, and the one
 * machine with a live Studio is in use by the tester this work is for. So the toggle — and
 * only the toggle — is reproduced here.
 *
 * <p><b>It is not an invention.</b> Every method name, and the branch order in
 * {@link #record()}, is copied from
 * {@code INGenious/IDE/src/main/java/com/ing/ide/main/mainui/components/testdesign/testcase/TestCaseComponent.java}
 * lines 675-690:
 *
 * <pre>
 *   public void record() throws IOException {
 *       if (toolBar.isRecording()) { stopPlaywrightRecording(); return; }
 *       if (launchPlaywrightTask != null &amp;&amp; !launchPlaywrightTask.isDone()) {
 *           logPlaywright("Playwright recorder is already running."); ... return;
 *       }
 *       ...
 *       launchPlaywrightTask = CompletableFuture.runAsync(...);
 *   }
 * </pre>
 *
 * <p>and {@code toolBar.setRecordingState(true)} is reached from {@code onRecorderReady()}
 * (line 1046), i.e. only once the recorder has actually written something — which is why
 * {@link #simulateRecorderReady()} is a separate step here rather than part of
 * {@link #record()}. That gap is a real state the panel has to answer for.
 *
 * <p>That the names still exist on the shipped core is not assumed either:
 * {@code StudioContractHarness} checks every one of them against the built
 * {@code ingenious-ide-3.0.0.jar} and fails if one was renamed.
 */
public class TestCaseComponent {

    private final TestCaseToolBar toolBar = new TestCaseToolBar();
    private CompletableFuture<Void> launchPlaywrightTask;

    /** How often the real toggle was entered — the count that proves a call did NOT happen. */
    private int recordCalls;

    public TestCaseToolBar getToolBar() {
        return toolBar;
    }

    /** The toggle, branch for branch. */
    public void record() {
        recordCalls++;
        if (toolBar.isRecording()) {
            // stopPlaywrightRecording() → finalizeLiveRecording(), which queues
            // setRecordingState(false) with invokeLater. Same here: the caller must not be
            // able to read the new state before its own event has finished.
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

    /** What a tester pressing Stop in Test Design does, behind this panel's back. */
    public void simulateStoppedElsewhere() {
        toolBar.setRecordingState(false);
        launchPlaywrightTask = null;
    }

    public int recordCalls() {
        return recordCalls;
    }
}
