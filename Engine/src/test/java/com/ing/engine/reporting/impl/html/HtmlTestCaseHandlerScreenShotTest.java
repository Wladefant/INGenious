package com.ing.engine.reporting.impl.html;

import static org.assertj.core.api.Assertions.assertThat;

import com.ing.ingenious.api.status.Status;
import org.testng.annotations.Test;

/**
 * The rule that decides whether a step gets a picture.
 *
 * <p>
 * The case that matters is {@code DONE} under {@code Both}: every acting step of a recorded flow
 * reports {@code DONE}, so while that combination answered {@code false} such a run produced no
 * step image at all and the evidence document built from those images was empty.
 * </p>
 */
public class HtmlTestCaseHandlerScreenShotTest {

    @Test
    public void anActingStepIsPhotographedUnderBoth() {
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.DONE, "Both")).isTrue();
    }

    @Test
    public void anActingStepIsPhotographedUnderPass() {
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.DONE, "Pass")).isTrue();
    }

    @Test
    public void anActingStepIsNotPhotographedUnderFail() {
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.DONE, "Fail")).isFalse();
    }

    @Test
    public void anAssertionKeepsItsPictureUnderBothAndPass() {
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.PASS, "Both")).isTrue();
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.PASS, "Pass")).isTrue();
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.PASS, "Fail")).isFalse();
    }

    @Test
    public void aFailedStepKeepsItsPictureUnderBothAndFail() {
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.FAIL, "Both")).isTrue();
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.FAIL, "Fail")).isTrue();
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.FAIL, "Pass")).isFalse();
    }

    @Test
    public void aStepThatAskedToStayUnphotographedStaysUnphotographed() {
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.PASSNS, "Both")).isFalse();
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.FAILNS, "Both")).isFalse();
    }

    @Test
    public void anUnsetOrUnknownSettingTakesNothing() {
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.DONE, null)).isFalse();
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.PASS, "")).isFalse();
        assertThat(HtmlTestCaseHandler.wantsScreenShot(Status.FAIL, "None")).isFalse();
    }
}
