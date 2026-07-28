package de.ing.qa.studio;

/**
 * Lets the harness publish an upload outcome the way {@link AdoUpload} does.
 *
 * <p>{@code AdoUploadStatus.publish} is package-private, which is right: a second publisher
 * would let the screen disagree with the ledger. But the behaviour that matters most on the
 * panel side — <em>a listener that subscribes after an upload has already finished still shows
 * its outcome</em> — cannot be reached by running a real upload, because a real upload needs a
 * finished run, a repo and a network. So the harness announces one from inside the package.
 *
 * <p>What it does <b>not</b> do is invent the German text or decide the state. Both come from
 * {@link AdoUpload#statusLine} and {@link AdoUploadStatus.State#of}, the same two functions the
 * uploader itself uses, fed with the literal stdout {@code ado-upload.mjs} prints. A probe that
 * wrote its own strings would keep passing after the real hook changed its wording.
 */
public final class AdoUploadProbe {

    private AdoUploadProbe() {
    }

    /** Forgets every retained event, so a scenario starts from a known nothing. */
    public static void reset() {
        AdoUploadStatus.reset();
    }

    /**
     * Announces the terminal outcome the hook's last stdout line describes.
     *
     * @param adoId the Azure DevOps test case id
     * @param testCaseName the INGenious test case name
     * @param hookStdout what {@code ado-upload.mjs} printed, verbatim
     * @param exit the hook's exit code
     * @return the German line that was published, so a test can assert on what it expects to
     *     read on screen without composing it a second time
     */
    public static String finished(String adoId, String testCaseName, String hookStdout, int exit) {
        String status = AdoUpload.statusLine(hookStdout, exit);
        AdoUploadStatus.publish(adoId, testCaseName, AdoUploadStatus.State.of(status), status);
        return status;
    }

    /**
     * Announces the start of an upload, word for word as {@code AdoUpload.upload} does before
     * it begins its seven-minute wait.
     *
     * @param adoId the Azure DevOps test case id
     * @param testCaseName the INGenious test case name
     * @return the German line that was published
     */
    public static String running(String adoId, String testCaseName) {
        String status = "ADO-Upload läuft… (Testfall " + adoId + ")";
        AdoUploadStatus.publish(adoId, testCaseName, AdoUploadStatus.State.RUNNING, status);
        return status;
    }

    /** The state the last published event carried, as a plain name, or {@code "-"}. */
    public static String lastState() {
        AdoUploadStatus.Event last = AdoUploadStatus.last();
        return last == null ? "-" : last.state().name();
    }
}
