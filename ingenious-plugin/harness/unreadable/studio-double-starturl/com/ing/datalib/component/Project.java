package com.ing.datalib.component;

import com.ing.datalib.settings.ProjectSettings;

/** Only the one getter the start-address probe walks through. */
public class Project {

    private final ProjectSettings projectSettings = new ProjectSettings();

    public ProjectSettings getProjectSettings() {
        return projectSettings;
    }
}
