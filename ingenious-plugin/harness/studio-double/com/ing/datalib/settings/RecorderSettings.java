package com.ing.datalib.settings;

/**
 * The recorder's start address.
 *
 * <p>The real one defaults to the empty string, because {@code setStartUrl} has no caller
 * anywhere in the INGenious sources — no Settings field ever writes it. An unconfigured
 * install therefore reads {@code ""} here, which is the case the panel has to warn about,
 * so that is this double's default too.
 */
public class RecorderSettings {

    private String startUrl = "";

    public String getStartUrl() {
        return startUrl;
    }

    public void setStartUrl(String value) {
        startUrl = value == null ? "" : value;
    }
}
