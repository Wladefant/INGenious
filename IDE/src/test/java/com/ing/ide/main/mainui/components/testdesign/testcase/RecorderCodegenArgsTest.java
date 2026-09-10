package com.ing.ide.main.mainui.components.testdesign.testcase;

import static org.testng.Assert.assertEquals;

import java.io.File;
import org.testng.annotations.Test;

/**
 * Tests the codegen command line the recorder is started with.
 *
 * <p>The point of each case is that an unset option leaves the command exactly as it was before
 * the option existed, so an existing project keeps recording the way it always has.
 *
 * <p>The channel, the viewport and {@code --save-storage} arrive here already assembled — by
 * {@link TestCaseComponent#browserChannelArgs(String)} and its neighbours, each with its own
 * validation and its own leading space. This method only decides the order and what a profile
 * displaces.
 */
public class RecorderCodegenArgsTest {
    private static final File OUTPUT = new File("/tmp/recording/Recorded.java");
    private static final String STORAGE = " --load-storage \"/tmp/state/default.json\"";
    private static final String CHANNEL = " --channel msedge";

    private String output() {
        // Escaped like the command itself escapes it: on Windows an absolute path is full of
        // backslashes, and an expected string that skipped the escaping would never match.
        return (
            "codegen --target java --output \"" +
            OUTPUT.getAbsolutePath().replace("\\", "\\\\") +
            "\""
        );
    }

    @Test
    public void testNeitherOptionSet() {
        assertEquals(
            TestCaseComponent.buildCodegenArgs(OUTPUT, null, null, null, null),
            output(),
            "with nothing configured the command must be the one the recorder always ran"
        );
    }

    @Test
    public void testEmptyOptionsAreTreatedAsUnset() {
        assertEquals(TestCaseComponent.buildCodegenArgs(OUTPUT, null, "", "", ""), output());
    }

    @Test
    public void testOptionArgsOnly() {
        assertEquals(
            TestCaseComponent.buildCodegenArgs(OUTPUT, null, CHANNEL, null, null),
            output() + CHANNEL
        );
    }

    @Test
    public void testUserDataDirOnly() {
        assertEquals(
            TestCaseComponent.buildCodegenArgs(OUTPUT, null, null, "/home/tester/profile", null),
            output() + " --user-data-dir \"/home/tester/profile\""
        );
    }

    @Test
    public void testAssembledOptionsKeepTheirOrderBeforeTheProfile() {
        assertEquals(
            TestCaseComponent.buildCodegenArgs(
                OUTPUT,
                null,
                CHANNEL + " --viewport-size 1280,720",
                "/home/tester/profile",
                null
            ),
            output() +
            CHANNEL +
            " --viewport-size 1280,720" +
            " --user-data-dir \"/home/tester/profile\""
        );
    }

    @Test
    public void testSavedSessionWithoutProfileIsPassedOn() {
        // The pre-profile behaviour: the saved session travels into the recorder unchanged.
        assertEquals(
            TestCaseComponent.buildCodegenArgs(OUTPUT, null, null, null, STORAGE),
            output() + STORAGE
        );
    }

    @Test
    public void testSavedSessionWithChannelIsPassedOn() {
        // A channel alone still launches a fresh profile, so the state file stays useful.
        assertEquals(
            TestCaseComponent.buildCodegenArgs(OUTPUT, null, CHANNEL, null, STORAGE),
            output() + CHANNEL + STORAGE
        );
    }

    @Test
    public void testProfileWinsOverSavedSession() {
        // A stale state file must not overwrite the profile's live session, so the
        // profile drops the --load-storage option entirely.
        assertEquals(
            TestCaseComponent.buildCodegenArgs(
                OUTPUT,
                null,
                CHANNEL,
                "/home/tester/profile",
                STORAGE
            ),
            output() + CHANNEL + " --user-data-dir \"/home/tester/profile\""
        );
    }

    @Test
    public void testOptionsPrecedeTheAddress() {
        // The address is positional, so it has to stay last.
        assertEquals(
            TestCaseComponent.buildCodegenArgs(
                OUTPUT,
                "https://example.org/app",
                CHANNEL,
                "/home/tester/profile",
                null
            ),
            output() +
            CHANNEL +
            " --user-data-dir \"/home/tester/profile\"" +
            " \"https://example.org/app\""
        );
    }

    @Test
    public void testSavedSessionPrecedesTheAddress() {
        assertEquals(
            TestCaseComponent.buildCodegenArgs(
                OUTPUT,
                "https://example.org/app",
                null,
                null,
                STORAGE
            ),
            output() + STORAGE + " \"https://example.org/app\""
        );
    }

    @Test
    public void testProfileDirectoryWithSpacesStaysQuoted() {
        assertEquals(
            TestCaseComponent.buildCodegenArgs(
                OUTPUT,
                null,
                null,
                "/Users/tester/Library/Application Support/recorder-profile",
                null
            ),
            output() +
            " --user-data-dir \"/Users/tester/Library/Application Support/recorder-profile\""
        );
    }

    @Test
    public void testBackslashesInProfileDirectoryAreEscapedLikeTheOutputPath() {
        assertEquals(
            TestCaseComponent.buildCodegenArgs(
                OUTPUT,
                null,
                null,
                "C:\\Users\\tester\\profile",
                null
            ),
            output() + " --user-data-dir \"C:\\\\Users\\\\tester\\\\profile\""
        );
    }
}
