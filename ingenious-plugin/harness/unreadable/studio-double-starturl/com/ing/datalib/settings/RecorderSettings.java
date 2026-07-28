package com.ing.datalib.settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * The recorder's start address, backed by a real properties file — because the whole question
 * this harness answers is whether a typed address reaches the file the recorder reads.
 *
 * <p><b>Why this double is file-backed and the other one is not.</b>
 * {@code harness/studio-double}'s version keeps the value in a field, which is all its harness
 * needs: it only ever <em>reads</em> the address. Here the panel writes one, and an in-memory
 * setter that worked is indistinguishable from a file that was never written until Studio is
 * restarted. So this one behaves like the real {@code RecorderSettings}, whose
 * {@code getStartUrl}/{@code setStartUrl} are its own and whose {@code save()} and
 * {@code getLocation()} come from {@code AbstractPropSettings}
 * ({@code INGenious/Datalib/src/main/java/com/ing/datalib/settings/}).
 *
 * <p><b>The two failure switches are not inventions either.</b>
 * <ul>
 *   <li>{@link #simulateUnwritableSettingsFile()} — the real {@code PropUtils.saveProperties}
 *       wraps its whole body in {@code catch (IOException) { LOGGER.severe }} and returns
 *       normally, so a settings file that cannot be written produces a {@code save()} that
 *       succeeds and changes nothing. That is the case the panel must not report as stored.
 *   <li>{@link #simulateSaveRefused()} — a Studio build whose settings object will not take the
 *       save at all, whether because the method is gone or because it throws. The panel treats
 *       both the same way and for the same reason: the value is on the open project, so this
 *       session will use it, and it is not stored, so the next one will not.
 * </ul>
 */
public class RecorderSettings {

    /** Where the harness points the project. Same shape as the product: a Settings directory. */
    public static final String DIR_PROPERTY = "harness.project.settings";

    private final Properties props = new Properties();
    private final String location;
    private boolean writesSilentlyFail;
    private boolean saveRefused;
    private boolean settingRejected;

    public RecorderSettings() {
        String dir = System.getProperty(DIR_PROPERTY, "target/harness-start-address/Settings");
        new File(dir).mkdirs();
        location = new File(dir, "RecorderSettings.Properties").getPath();
        File file = new File(location);
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (Exception ignored) {
                // An unreadable file is an unconfigured project, exactly as in the product.
            }
        }
    }

    /** Trimmed and defaulted to {@code ""}, as the real one is. */
    public String getStartUrl() {
        return props.getProperty("StartUrl", "").trim();
    }

    public void setStartUrl(String value) {
        if (settingRejected) {
            throw new UnsupportedOperationException(
                "Dieses Studio nimmt die Start-Adresse nicht entgegen.");
        }
        props.setProperty("StartUrl", value == null ? "" : value.trim());
    }

    /** The settings file, as {@code AbstractPropSettings.getLocation()} composes it. */
    public String getLocation() {
        return location;
    }

    public void save() {
        if (saveRefused) {
            throw new UnsupportedOperationException(
                "Dieses Studio nimmt die Projekt-Einstellung nicht an.");
        }
        if (writesSilentlyFail) {
            // Precisely what PropUtils.saveProperties does when the file cannot be written:
            // logs and returns. Nothing thrown, nothing written.
            return;
        }
        try (OutputStream out = new FileOutputStream(location)) {
            props.store(out, "harness");
        } catch (Exception ignored) {
            // Same swallow as the product's.
        }
    }

    /** From here on {@code save()} succeeds and writes nothing. */
    public void simulateUnwritableSettingsFile() {
        writesSilentlyFail = true;
    }

    /** From here on {@code save()} refuses outright. */
    public void simulateSaveRefused() {
        saveRefused = true;
    }

    /**
     * From here on {@code setStartUrl} refuses — a build where the setting cannot be reached at
     * all, which is what {@code StudioRecorder.Store.NONE} means with a Studio present.
     *
     * <p>Without this, {@code NONE} was only ever reachable by having no Studio at all, and the
     * restore path could not be shown failing at all: a check that can only come out one way
     * proves nothing.
     */
    public void simulateSettingRejected() {
        settingRejected = true;
    }

    /** Undoes both switches — the harness's stand-in for a machine where writing works again. */
    public void simulateSaveWorks() {
        writesSilentlyFail = false;
        saveRefused = false;
        settingRejected = false;
    }
}
