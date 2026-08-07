package com.ing.datalib.settings;

/**
 * Project-level settings for the recorder.
 *
 * <p>Today the recorder opens a blank page and every recording starts by typing the
 * application's address by hand. A project is written against one application, so the address
 * belongs to the project, not to the person recording. The same holds for the browser the
 * recording runs in: which installed browser a project has to be recorded with is a property of
 * the application, not of the tester.
 *
 * <p>Empty is the default and stays valid: a project that sets nothing behaves exactly as it
 * did before.
 */
public class RecorderSettings extends AbstractPropSettings {
    private static final String START_URL = "StartUrl";

    private static final String BROWSER_CHANNEL = "BrowserChannel";

    private static final String BROWSER_USER_DATA_DIR = "BrowserUserDataDir";

    public RecorderSettings(String location) {
        super(location, "RecorderSettings");
    }

    /**
     * The page the recorder opens when a recording starts.
     *
     * @return the URL, or an empty string when the project has not set one
     */
    public String getStartUrl() {
        return getProperty(START_URL, "").trim();
    }

    /**
     * Sets the page the recorder opens.
     *
     * @param value the URL; {@code null} or blank clears it
     */
    public void setStartUrl(String value) {
        setProperty(START_URL, value == null ? "" : value.trim());
    }

    /**
     * The installed browser distribution the recorder runs in, such as {@code chrome},
     * {@code msedge} or {@code chrome-beta}.
     *
     * <p>The recorder otherwise uses the browser build Playwright downloads for itself. That
     * build is a plain browser with no relation to the machine it runs on, so it cannot present
     * whatever identity an installed, managed browser presents. A project whose application
     * requires that identity names the installed distribution here instead.
     *
     * @return the channel, or an empty string when the project has not set one
     */
    public String getBrowserChannel() {
        return getProperty(BROWSER_CHANNEL, "").trim();
    }

    /**
     * Sets the installed browser distribution the recorder runs in.
     *
     * @param value the channel; {@code null} or blank restores Playwright's own browser build
     */
    public void setBrowserChannel(String value) {
        setProperty(BROWSER_CHANNEL, value == null ? "" : value.trim());
    }

    /**
     * The directory holding the browser profile the recorder reuses between recordings.
     *
     * <p>Without one, every recording starts from an empty profile: no cookies, no sign-in, no
     * certificates, nothing the browser stored last time. Pointing at a directory keeps that
     * state on disk, so a recording resumes where the previous one left off instead of signing
     * in again.
     *
     * @return the directory, or an empty string when the project has not set one
     */
    public String getBrowserUserDataDir() {
        return getProperty(BROWSER_USER_DATA_DIR, "").trim();
    }

    /**
     * Sets the directory holding the browser profile the recorder reuses.
     *
     * @param value the directory; {@code null} or blank restores a fresh profile per recording
     */
    public void setBrowserUserDataDir(String value) {
        setProperty(BROWSER_USER_DATA_DIR, value == null ? "" : value.trim());
    }
}
