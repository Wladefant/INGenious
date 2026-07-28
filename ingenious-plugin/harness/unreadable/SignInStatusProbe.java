package de.ing.qa.studio;

/**
 * Publishes the one upload state that has no status code, so a harness can put it on screen.
 *
 * <p>{@code AdoUploadStatus.publish} is package-private and right to be, and
 * {@code harness/AdoUploadProbe} reaches it for the five states that {@code ado-upload.mjs}
 * prints a code for. {@link AdoUploadStatus.State#SIGN_IN_REQUIRED} is not one of them: it is
 * decided by {@code AdoSignIn} <em>before</em> the tool is started, so {@code State.of} cannot
 * produce it and no stdout line can be fed in to reach it. Without this, the state that must
 * not be painted red is the one state no test could paint at all.
 *
 * <p>This file lives in {@code harness/unreadable/} and declares the product package on
 * purpose: {@code harness/AdoUploadProbe.java} belongs to another lane this week, and javac
 * compiles a source file passed by name regardless of the directory it sits in. If the two ever
 * merge, this one goes and {@code AdoUploadProbe} gains the method.
 *
 * <p>The German text is passed in rather than invented, because {@code AdoUpload} composes it
 * inline at each of its two publish sites; a caller quotes the product's own sentence so a
 * reworded message shows up as a failing check rather than as a probe passing on its own copy.
 */
public final class SignInStatusProbe {

    private SignInStatusProbe() {
    }

    /**
     * Announces that the upload is waiting for a sign-in.
     *
     * @param adoId the Azure DevOps test case id
     * @param testCaseName the INGenious test case name
     * @param message the German one-liner the sign-in step published, verbatim
     * @return the message that was published, so a test asserts on it without composing it twice
     */
    public static String signInRequired(String adoId, String testCaseName, String message) {
        AdoUploadStatus.publish(adoId, testCaseName,
            AdoUploadStatus.State.SIGN_IN_REQUIRED, message);
        return message;
    }

    /**
     * Announces the OTHER outcome of the sign-in step: it did not happen, the upload gave up,
     * and nothing reached Azure DevOps.
     *
     * <p>{@link AdoUploadStatus.State#FAILED}, not {@code SIGN_IN_REQUIRED} — and the state is
     * not this probe's opinion. {@link de.ing.qa.studio.AdoUpload} published one value for both
     * halves of its sign-in step until 2026-07-28; that the give-up now publishes {@code FAILED}
     * is pinned against the real publisher in {@code harness/signin/SignInHarness}, which drives
     * {@code AdoUpload.forRun} with a logged-out fake {@code az}. What cannot be reached from
     * there is a Swing panel, which is why this exists: the same state, put on screen, so the
     * colour can be asserted.
     *
     * @param adoId the Azure DevOps test case id
     * @param testCaseName the INGenious test case name
     * @param message the German one-liner the sign-in step published, verbatim
     * @return the message that was published, so a test asserts on it without composing it twice
     */
    public static String signInGaveUp(String adoId, String testCaseName, String message) {
        AdoUploadStatus.publish(adoId, testCaseName, AdoUploadStatus.State.FAILED, message);
        return message;
    }

    /** Forgets every retained event, so a scenario starts from a known nothing. */
    public static void reset() {
        AdoUploadStatus.reset();
    }
}
