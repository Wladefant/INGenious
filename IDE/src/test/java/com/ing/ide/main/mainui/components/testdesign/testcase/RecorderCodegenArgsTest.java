package com.ing.ide.main.mainui.components.testdesign.testcase;

import static org.testng.Assert.assertEquals;

import java.io.File;
import org.testng.annotations.Test;

/**
 * Tests the codegen command line the recorder is started with.
 *
 * <p>The point of each case is that an unset option leaves the command exactly as it was before
 * the option existed, so an existing project keeps recording the way it always has.
 */
public class RecorderCodegenArgsTest {
    private static final File OUTPUT = new File("/tmp/recording/Recorded.java");
    private static final String STORAGE = " --load-storage \"/tmp/state/default.json\"";

    private String output() {
        return "codegen --target java --output \"" + OUTPUT.getAbsolutePath() + "\"";
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
    public void testChannelOnly() {
        assertEquals(
            TestCaseComponent.buildCodegenArgs(OUTPUT, null, "msedge", null, null),
            output() + " --channel msedge"
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
    public void testBothOptionsSet() {
        assertEquals(
            TestCaseComponent.buildCodegenArgs(
                OUTPUT,
                null,
                "msedge",
                "/home/tester/profile",
                null
            ),
            output() + " --channel msedge --user-data-dir \"/home/tester/profile\""
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
            TestCaseComponent.buildCodegenArgs(OUTPUT, null, "msedge", null, STORAGE),
            output() + " --channel msedge" + STORAGE
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
                "msedge",
                "/home/tester/profile",
                STORAGE
            ),
            output() + " --channel msedge --user-data-dir \"/home/tester/profile\""
        );
    }

    @Test
    public void testOptionsPrecedeTheAddress() {
        // The address is positional, so it has to stay last.
        assertEquals(
            TestCaseComponent.buildCodegenArgs(
                OUTPUT,
                "https://example.org/app",
                "msedge",
                "/home/tester/profile",
                null
            ),
            output() +
            " --channel msedge --user-data-dir \"/home/tester/profile\"" +
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
