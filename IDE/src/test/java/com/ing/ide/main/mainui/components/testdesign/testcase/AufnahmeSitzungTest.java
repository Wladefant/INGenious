package com.ing.ide.main.mainui.components.testdesign.testcase;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.Test;

/**
 * #293 — a second recording reuses the signed-in session instead of starting
 * signed out, and ending a recording does not skip the write that makes that
 * possible.
 *
 * <p>These methods are static on purpose: launching a real codegen would need
 * a Studio, a project and a browser. What we can prove here is the contract
 * the next launch reads and the stop path that used to make that contract
 * unreachable.
 */
public class AufnahmeSitzungTest {

    @Test
    public void loadAndSaveShareTheSameConfiguredFile() throws Exception {
        Path project = Files.createTempDirectory("aufnahme-sitzung-");
        Path state = project.resolve("session-state.json");
        Files.writeString(state, "{\"cookies\":[]}", StandardCharsets.UTF_8);
        String stored = forward(state);
        writeContext(project, true, stored);

        String load = TestCaseComponent.storageStateArgs(project.toString());
        String save = TestCaseComponent.saveStorageArgs(project.toString());
        String escaped = stored.replace("\\", "\\\\").replace("\"", "\\\"");

        assertTrue(load.contains("--load-storage"), load);
        assertTrue(load.contains(stored) || load.contains(escaped), load);
        assertTrue(save.contains("--save-storage"), save);
        assertTrue(save.contains(stored) || save.contains(escaped), save);
        assertEquals(TestCaseComponent.storageStatePath(project.toString(), true), stored);
    }

    @Test
    public void firstRecordingStillSavesWhenTheFileDoesNotExistYet() throws Exception {
        Path project = Files.createTempDirectory("aufnahme-sitzung-missing-");
        Path state = project.resolve("session-state.json");
        writeContext(project, true, forward(state));

        assertEquals(TestCaseComponent.storageStateArgs(project.toString()), "");
        String save = TestCaseComponent.saveStorageArgs(project.toString());
        assertTrue(save.contains("--save-storage"), save);
        assertTrue(save.contains(state.getFileName().toString()), save);
    }

    @Test
    public void checkboxOffMeansNeitherLoadNorSave() throws Exception {
        Path project = Files.createTempDirectory("aufnahme-sitzung-off-");
        Path state = project.resolve("session-state.json");
        Files.writeString(state, "{}", StandardCharsets.UTF_8);
        writeContext(project, false, forward(state));

        assertEquals(TestCaseComponent.storageStateArgs(project.toString()), "");
        assertEquals(TestCaseComponent.saveStorageArgs(project.toString()), "");
        assertEquals(TestCaseComponent.storageStatePath(project.toString(), false), "");
    }

    @Test
    public void softStopLetsTheProcessExitWithoutForce() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
            javaBinary(),
            "-cp",
            System.getProperty("java.class.path"),
            AufnahmeSitzungTest.class.getName() + "$ShortLived"
        );
        builder.redirectErrorStream(true);
        Process process = builder.start();
        assertTrue(process.isAlive() || process.waitFor(2, TimeUnit.SECONDS));
        boolean ended = TestCaseComponent.endPlaywrightProcess(process, 2_000);
        assertTrue(ended, "a process that exits on destroy() must not be force-killed");
        assertFalse(process.isAlive());
    }

    @Test
    public void hungProcessIsStillForceKilled() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
            javaBinary(),
            "-cp",
            System.getProperty("java.class.path"),
            AufnahmeSitzungTest.class.getName() + "$IgnoresDestroy"
        );
        builder.redirectErrorStream(true);
        Process process = builder.start();
        waitUntilAlive(process);
        long started = System.nanoTime();
        TestCaseComponent.endPlaywrightProcess(process, 200);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertFalse(process.isAlive(), "a hung process must still be destroyed");
        assertTrue(
            waitedMs < 3_000,
            "force-kill must not wait the old two-second hang: " + waitedMs
        );
    }

    @Test
    public void secondLaunchArgsReuseTheFileTheFirstOneWrote() throws Exception {
        Path project = Files.createTempDirectory("aufnahme-sitzung-roundtrip-");
        Path state = project.resolve("session-state.json");
        writeContext(project, true, forward(state));

        assertEquals(TestCaseComponent.storageStateArgs(project.toString()), "");
        assertTrue(
            TestCaseComponent.saveStorageArgs(project.toString()).contains("--save-storage")
        );

        Files.writeString(
            state,
            "{\"cookies\":[{\"name\":\"sid\",\"value\":\"kept\"}]}",
            StandardCharsets.UTF_8
        );
        String second = TestCaseComponent.storageStateArgs(project.toString());
        assertTrue(second.contains("--load-storage"), second);
        assertTrue(second.contains("session-state.json"), second);
    }

    private static void writeContext(Path project, boolean use, String statePath)
        throws IOException {
        Path dir = project.resolve("Settings").resolve("BrowserContexts");
        Files.createDirectories(dir);
        Files.writeString(
            dir.resolve("default.properties"),
            "useStorageState=" + use + "\nstorageStatePath=" + statePath + "\n",
            StandardCharsets.UTF_8
        );
    }

    /** Properties files treat \\ as an escape; the product writes forward slashes. */
    private static String forward(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void waitUntilAlive(Process process) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(process.isAlive(), "fixture process never started");
    }

    /** Exits promptly when asked — the soft-stop success case. */
    public static final class ShortLived {

        public static void main(String[] args) throws Exception {
            Thread.sleep(8_000);
        }
    }

    /**
     * Stays alive through {@code destroy()} the way a wedged codegen would.
     * {@code destroyForcibly()} is what has to reach it.
     */
    public static final class IgnoresDestroy {

        public static void main(String[] args) throws Exception {
            AtomicBoolean keep = new AtomicBoolean(true);
            Runtime
                .getRuntime()
                .addShutdownHook(
                    new Thread(
                        () -> {
                            // swallow SIGTERM-shaped shutdown so only a forcible kill ends us
                            while (keep.get()) {
                                try {
                                    Thread.sleep(50);
                                } catch (InterruptedException ignored) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                        }
                    )
                );
            while (true) {
                Thread.sleep(50);
            }
        }
    }
}
