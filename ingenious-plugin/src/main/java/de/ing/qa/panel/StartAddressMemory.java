package de.ing.qa.panel;

import de.ing.qa.ado.AdoCache;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * The panel's own copy of the start address it last stored, one entry per project.
 *
 * <p><b>Why a second copy of a setting the product already has.</b> Two of the four outcomes of
 * {@link StudioRecorder#setProjectStartUrl} are half-successes: the value is on the open project
 * and the recorder will use it, but it is not in the file the next Studio start reads
 * ({@link StudioRecorder.Store#PROJECT_MEMORY}), or it may or may not be and this build will not
 * say ({@link StudioRecorder.Store#PROJECT_SAVED_UNVERIFIED}). The product cannot report a failed
 * write at all — {@code PropUtils.saveProperties} catches its own {@code IOException} and returns
 * normally, and it truncates the file on open, so a failure part-way through destroys the previous
 * good content while still reporting success
 * (<a href="https://github.com/ing-bank/INGenious/issues/322">ing-bank/INGenious#322</a>). That is
 * theirs to fix and not ours.
 *
 * <p>What was ours was the sentence we put on screen about it: <em>"gilt nur bis Studio geschlossen
 * wird … Sie können für heute weiterarbeiten … bitte melden."</em> True, and a trap — the tester
 * closes Studio, the address is gone, and the reassurance was the last thing they read. A copy the
 * panel controls turns that into a step it performs itself: on the next start, if the project has
 * no address and this file has one, the panel puts it back and says so.
 *
 * <p><b>It is also the only reference the machine check has.</b> The six environments of the same
 * application differ by hostname alone, and nothing on this machine knows which hostname is which
 * environment. What it can know is which hostname was stored here last time, which is enough to
 * say "the machine changed" — see {@link StartAddress#compareMachine}.
 *
 * <p><b>A write is only reported once it has been read back out of the file.</b> Same rule as
 * everywhere else here: {@link #remember} returns {@code false} unless the value is on disk, so a
 * screen may say "the panel has memorised it" only when the panel really has.
 */
final class StartAddressMemory {

    /** Overrides the file, so a harness can point this at its own temp copy. */
    static final String ENV_FILE = "ING_START_ADDRESS_MEMORY";

    private static final String FILE_NAME = "start-address.properties";

    private StartAddressMemory() {
    }

    /** The file the copies live in — beside the plugin's other per-user state. */
    static Path file() {
        String explicit = System.getenv(ENV_FILE);
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit.trim());
        }
        Path dir = AdoCache.cachePath().getParent();
        return dir == null ? Paths.get(FILE_NAME) : dir.resolve(FILE_NAME);
    }

    /**
     * The address last stored for this project, or {@code null} when there is none.
     *
     * @param projectKey the project's settings file, or any stable name for it; {@code null} and
     *     blank are answered with {@code null} rather than with some other project's address
     */
    static String remembered(String projectKey) {
        if (projectKey == null || projectKey.isBlank()) {
            return null;
        }
        String value = load().getProperty(projectKey.trim());
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Keeps this address as the one last stored for this project.
     *
     * @return {@code true} only when it was afterwards read back out of the file — an unwritable
     *     state directory is exactly the case this class exists to survive, so it must not be the
     *     case it reports as success
     */
    static boolean remember(String projectKey, String url) {
        if (projectKey == null || projectKey.isBlank() || url == null || url.isBlank()) {
            return false;
        }
        Properties props = load();
        props.setProperty(projectKey.trim(), url.trim());
        Path file = file();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Zuletzt aus dem Ablauf-Panel gesetzte Start-Adressen, je Projekt.");
            }
        } catch (IOException | RuntimeException ex) {
            return false;
        }
        return url.trim().equals(remembered(projectKey));
    }

    /**
     * The file's contents, or an empty set when there are none.
     *
     * <p>An unreadable file reads as "nothing remembered". That is the safe direction: the panel
     * then offers no restore and claims no comparison, instead of acting on half a file.
     */
    private static Properties load() {
        Properties props = new Properties();
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException | RuntimeException ex) {
            return new Properties();
        }
        return props;
    }
}
