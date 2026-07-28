package com.ing.datalib.settings;

/** Only the one getter the start-address probe walks through. */
public class ProjectSettings {

    private final RecorderSettings recorderSettings = new RecorderSettings();

    public RecorderSettings getRecorderSettings() {
        return recorderSettings;
    }
}
