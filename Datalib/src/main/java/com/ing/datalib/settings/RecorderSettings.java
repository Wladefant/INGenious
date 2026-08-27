package com.ing.datalib.settings;

/**
 * Project-level settings for the recorder.
 *
 * <p>Today the recorder opens a blank page and every recording starts by typing the
 * application's address by hand. A project is written against one application, so the address
 * belongs to the project, not to the person recording.
 *
 * <p>Empty is the default and stays valid: a project that sets nothing behaves exactly as it
 * did before.
 */
public class RecorderSettings extends AbstractPropSettings {
    private static final String START_URL = "StartUrl";
    private static final String BROWSER = "Browser";

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
     * The browser or channel the recorder uses (e.g. {@code "chrome"}, {@code "msedge"}, or
     * {@code ""} for Playwright's bundled Chromium).
     *
     * @return the browser name or channel, or an empty string when using the default bundled Chromium
     */
    public String getBrowser() {
        return getProperty(BROWSER, "").trim();
    }

    /**
     * Sets the browser or channel the recorder uses.
     *
     * @param value the browser or channel; {@code null} or blank clears it to default bundled Chromium
     */
    public void setBrowser(String value) {
        setProperty(BROWSER, value == null ? "" : value.trim());
    }
}
