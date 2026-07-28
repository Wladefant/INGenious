package de.ing.qa.studio;

import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Notices that an INGenious run has finished, and sends its evidence to Azure DevOps.
 *
 * <p><b>Why a watcher and not a callback.</b> There is no callback to use. Studio runs the
 * engine in-process ({@code EngineConfig.runProject} → {@code Control.call}) on its own thread,
 * and the plugin API offers three contribution points — {@code StudioPanelApi},
 * {@code RecordingTargetApi} and the engine's action plugins — none of which is told that a run
 * ended. The engine's own {@code SummaryReport.register} is public, but
 * {@code Control.resetAll()} clears every registered handler after each run and the built-ins
 * are re-registered by a constructor a plugin cannot reach, so a handler registered once would
 * be silently dropped after the first run. The honest options were a watcher or a new contract
 * in the INGenious core; the core is another lane's, so this is the watcher. The recommended
 * core hook is written up in the plugin README.
 *
 * <p><b>What it watches.</b> {@code <project>/Results}, the directory
 * {@code AppResourcePath.getResultsPath()} builds every run under —
 * {@code Results/TestDesign/<Scenario>/<TestCase>/<date time>} for a single test case, and
 * {@code Results/TestExecution/<Release>/<TestSet>/<date time>} for a test set. A directory
 * counts as a finished run once it holds a report file ({@code data.js} or a {@code *-v2.html})
 * and has stopped changing.
 *
 * <p><b>What it never does.</b> It never uploads history: everything already on disk when a
 * project is first seen is recorded as seen and skipped. It never uploads the same directory
 * twice. It never touches the Swing event dispatch thread — one daemon thread does all of it,
 * and a failure anywhere ends as a log line, never as an exception into Studio.
 */
public final class AdoRunWatcher {

    private static final Logger LOG = Logger.getLogger(AdoRunWatcher.class.getName());

    /** Overrides the project directory, for a headless harness or an unusual install. */
    public static final String ENV_PROJECT = "ING_INGENIOUS_PROJECT";

    /** The engine's own name for the directory it writes every run under. */
    public static final String RESULTS_FOLDER = "Results";

    /** Studio's main window, matched by class name because the plugin cannot import it. */
    private static final String FRAME_CLASS = "com.ing.ide.main.mainui.AppMainFrame";

    private static final long POLL_MS = 5000;
    /** A run directory is finished when nothing in it has changed for this long. */
    private static final long SETTLE_MS = 4000;
    /** Run directories are four levels under Results; a little slack costs nothing. */
    private static final int MAX_DEPTH = 6;

    private static Thread thread;

    private AdoRunWatcher() {
    }

    /**
     * Starts watching, once per Studio session.
     *
     * <p>Safe to call from anywhere, including the event dispatch thread and before a project is
     * open: it only starts a daemon thread, and the thread re-resolves the project on every poll
     * so opening or switching a project is picked up without anybody notifying it. Idempotent —
     * calling it from several places is the intended way to use it, not a mistake.
     *
     * <p><b>Where this must be called from, and why it cannot be "plugin load".</b> Arming only
     * when a particular screen is opened is a trap: a tester who reopens Studio and presses F6
     * to re-run an existing case gets a green run and no record of it. The obvious fix — arm
     * when the plugin loads — is not available. {@code PluginLoader} finds entry classes with
     * {@code ClassLoader.loadClass}, which by the JLS does <em>not</em> run static initialisers,
     * and every registry that instantiates them is lazy ({@code RecordingTargetPlugins} builds
     * its list on the first recording) except one.
     *
     * <p>That one is {@code StudioPanelPlugins.discover}, which constructs every
     * {@code StudioPanelApi} entry class at Studio startup to read its title — before any
     * project is open and whichever screen the tester later chooses. A panel <b>constructor</b>
     * is therefore the earliest reachable point, and is equivalent to plugin load for every
     * purpose that matters here. Calling it from {@code createPanel()} is not, because that
     * only runs when the tester opens that screen.
     */
    public static synchronized void arm() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        thread = new Thread(() -> watch(AdoRunWatcher::uploadAndLog, POLL_MS, SETTLE_MS, -1),
            "ado-run-watcher");
        thread.setDaemon(true);
        thread.start();
        LOG.log(Level.INFO, "ADO run watcher armed; Results directory: {0}", resultsRoot());
    }

    /**
     * Whether the watcher is running, so a caller can arm defensively and a panel can show
     * whether finished runs are being noticed at all.
     *
     * <p>Worth exposing because the alternative is guessing. A tester whose upload never
     * happened has no way to tell an armed watcher that found nothing from a watcher that was
     * never started — the same invisible state that made this feature look broken before.
     *
     * @return {@code true} when the watching thread is alive
     */
    public static synchronized boolean isArmed() {
        return thread != null && thread.isAlive();
    }

    private static void uploadAndLog(Path runDir) {
        for (AdoUpload.Result result : AdoUpload.forRun(runDir)) {
            // The receipt and the ledger are ado-upload.mjs's; this is the line in Studio's
            // own log, so a run is diagnosable without leaving the application.
            LOG.log(Level.INFO, "[{0}] {1}", new Object[] { runDir.getFileName(), result.status() });
        }
    }

    /**
     * The polling loop. Parameterised so the harness can drive it fast, against a directory of
     * its own, and for a bounded number of scans.
     *
     * @param sink what to do with each finished run directory
     * @param pollMs how long to sleep between scans
     * @param settleMs how long a directory must be unchanged before it counts as finished
     * @param maxPolls how many scans to make, or a negative number to run until interrupted
     */
    public static void watch(Consumer<Path> sink, long pollMs, long settleMs, int maxPolls) {
        Set<String> seen = new HashSet<>();
        Path knownRoot = null;
        for (int poll = 0; maxPolls < 0 || poll < maxPolls; poll++) {
            try {
                Path root = resultsRoot();
                if (root != null && !root.equals(knownRoot)) {
                    // A newly opened project: take everything already there as history.
                    seen.clear();
                    for (Path dir : finishedRuns(root, 0)) {
                        seen.add(dir.toString());
                    }
                    knownRoot = root;
                    LOG.log(Level.INFO, "Watching {0} ({1} earlier run(s) ignored)",
                        new Object[] { root, seen.size() });
                }
                if (root != null) {
                    for (Path dir : finishedRuns(root, settleMs)) {
                        if (seen.add(dir.toString())) {
                            LOG.log(Level.INFO, "Finished run detected: {0}", dir);
                            sink.accept(dir);
                        }
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                // The watcher outliving one bad scan matters more than the scan.
                LOG.log(Level.WARNING, "Run scan failed, continuing: " + ex, ex);
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Every finished run directory under {@code root}.
     *
     * @param root the {@code Results} directory
     * @param settleMs how long the newest file in a directory must be untouched; {@code 0}
     *     accepts a directory however fresh, which is what the initial history sweep wants
     * @return the directories, in no particular order, empty when there are none
     */
    /**
     * The engine's own name for the copy it makes of the run it has just written.
     *
     * <p>{@code HtmlSummaryHandler.createLatest()} deletes and re-copies the entire run
     * directory here at the end of every run, as a sibling of the timestamped original. The
     * copy carries the same report files, so without this it is accepted as a run in its own
     * right — and one finished test produces two uploads, and two results in test management.
     *
     * <p>Matching the name is the only robust discriminator: real run directories are named
     * with a localised timestamp ({@code 28-Juli-2026 02-12-42} in a German locale), so no
     * pattern over the original names can identify the copy. Nothing else in the engine
     * creates a second report directory.
     */
    public static final String LATEST_FOLDER = "Latest";

    /** Whether this directory is the engine's copy rather than a run of its own. */
    private static boolean isEngineCopy(Path dir) {
        Path name = dir.getFileName();
        return name != null && LATEST_FOLDER.equalsIgnoreCase(name.toString());
    }

    public static List<Path> finishedRuns(Path root, long settleMs) {
        List<Path> found = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return found;
        }
        long now = System.currentTimeMillis();
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            walk.filter(Files::isDirectory).filter(dir -> !isEngineCopy(dir)).forEach(dir -> {
                long newest = reportStamp(dir);
                if (newest > 0 && now - newest >= settleMs) {
                    found.add(dir);
                }
            });
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.WARNING, "Could not read " + root + ": " + ex.getMessage());
        }
        return found;
    }

    /**
     * When a directory last looked like a finished run.
     *
     * <p>Public because {@link AdoSubmission} has to pick the <em>newest</em> run of a test case
     * and there is nothing else to sort by: the engine names run directories with a localised
     * timestamp ({@code 28-Juli-2026 02-12-42}), so their names sort by neither time nor
     * anything else. It is also the timestamp a tester is asked to quote when they hand a
     * recording over, so the same number ends up on screen.
     *
     * @param dir a candidate run directory
     * @return the newest modification time among the report files directly in {@code dir}, or
     *     {@code 0} when it holds none and is therefore not a run directory at all
     */
    public static long reportStamp(Path dir) {
        long newest = 0;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                String name = entry.getFileName().toString().toLowerCase();
                boolean isReport = name.equals("data.js") || name.endsWith("-v2.html");
                if (!isReport || !Files.isRegularFile(entry)) {
                    continue;
                }
                newest = Math.max(newest, Files.getLastModifiedTime(entry).toMillis());
            }
        } catch (IOException | RuntimeException ex) {
            return 0;
        }
        return newest;
    }

    /**
     * The {@code Results} directory of the project Studio has open, or {@code null} when there
     * is none to watch yet.
     *
     * <p>{@link #ENV_PROJECT} wins when set. Otherwise the open project is found the same way
     * {@code de.ing.qa.panel.StudioRecorder} reaches the recorder: the live
     * {@code AppMainFrame} among the AWT frames, then {@code getProject().getLocation()}.
     * Everything is matched by name, so this class compiles and loads with no Studio on the
     * classpath at all — which is exactly the situation in the harness.
     */
    public static Path resultsRoot() {
        String explicit = System.getenv(ENV_PROJECT);
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit.trim()).resolve(RESULTS_FOLDER);
        }
        try {
            for (Frame frame : Frame.getFrames()) {
                if (!FRAME_CLASS.equals(frame.getClass().getName())) {
                    continue;
                }
                Object project = frame.getClass().getMethod("getProject").invoke(frame);
                if (project == null) {
                    return null;
                }
                Object location = project.getClass().getMethod("getLocation").invoke(project);
                if (location == null) {
                    return null;
                }
                return Paths.get(String.valueOf(location)).resolve(RESULTS_FOLDER);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            // A renamed method, a headless JVM, no project: all mean "nothing to watch yet",
            // and the next poll asks again.
            LOG.log(Level.FINE, "No open project to watch: " + ex);
        }
        return null;
    }
}
