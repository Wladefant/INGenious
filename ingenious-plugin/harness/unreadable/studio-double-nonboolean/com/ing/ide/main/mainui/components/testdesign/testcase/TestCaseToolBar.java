package com.ing.ide.main.mainui.components.testdesign.testcase;

/**
 * The toolbar of a Studio build that answers {@code isRecording()} with something that is
 * neither yes nor no.
 *
 * <p>The name is still there, so nothing throws — and that is what made this the quiet one.
 * The panel used to read the answer as {@code Boolean.TRUE.equals(…)}, which turns "no idea"
 * into "no": a live recording came out as {@code IDLE}, the button offered a start, and the
 * start sent the toggle that ends it. A boxed return that is momentarily {@code null} is the
 * cheapest way for a core to produce that, and it needs no rename and no error anywhere.
 */
public class TestCaseToolBar {

    private boolean live;

    /** Boxed, and not yet decided. Reflection succeeds; the answer says nothing. */
    public Boolean isRecording() {
        return null;
    }

    public void setRecordingState(boolean recording) {
        live = recording;
    }

    /** What record() branches on, and what the harness checks survived. */
    public boolean live() {
        return live;
    }
}
