package com.ing.datalib.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Tests for RecorderSettings — the project-level recorder settings, all of them optional.
 */
public class RecorderSettingsTest {
    private Path tempDir;

    @BeforeMethod
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("recorder-settings-test");
    }

    @AfterMethod
    public void tearDown() throws IOException {
        Files
            .walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
    }

    private RecorderSettings settings() {
        return new RecorderSettings(tempDir.toString());
    }

    @Test
    public void testDefaultsAreEmpty() {
        RecorderSettings settings = settings();
        assertThat(settings.getStartUrl()).isEmpty();
        assertThat(settings.getBrowser()).isEmpty();
        assertThat(settings.getBrowserUserDataDir()).isEmpty();
    }

    @Test
    public void testFileName() {
        assertThat(settings().getLocation())
            .isEqualTo(tempDir.toString() + File.separator + "RecorderSettings.Properties");
    }

    @Test
    public void testBrowserRoundTrip() {
        RecorderSettings settings = settings();
        settings.setBrowser("msedge");
        settings.save();

        assertThat(settings().getBrowser()).isEqualTo("msedge");
    }

    @Test
    public void testBrowserUserDataDirRoundTrip() {
        RecorderSettings settings = settings();
        settings.setBrowserUserDataDir("/home/tester/recorder-profile");
        settings.save();

        assertThat(settings().getBrowserUserDataDir()).isEqualTo("/home/tester/recorder-profile");
    }

    @Test
    public void testValuesAreTrimmed() {
        RecorderSettings settings = settings();
        settings.setBrowser("  msedge  ");
        settings.setBrowserUserDataDir("  /home/tester/profile  ");

        assertThat(settings.getBrowser()).isEqualTo("msedge");
        assertThat(settings.getBrowserUserDataDir()).isEqualTo("/home/tester/profile");
    }

    @Test
    public void testNullClearsTheValue() {
        RecorderSettings settings = settings();
        settings.setBrowser("msedge");
        settings.setBrowser(null);

        assertThat(settings.getBrowser()).isEmpty();
    }

    @Test
    public void testSettingsAreIndependent() {
        RecorderSettings settings = settings();
        settings.setBrowser("chrome");

        assertThat(settings.getStartUrl()).isEmpty();
        assertThat(settings.getBrowserUserDataDir()).isEmpty();
    }
}
