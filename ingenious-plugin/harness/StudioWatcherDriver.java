import java.awt.Frame;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.swing.SwingUtilities;

/**
 * Answers the one link in the Studio upload chain that no other proof reaches: can
 * {@code AdoRunWatcher.resultsRoot()} reflectively find the open project — {@code
 * AppMainFrame.getProject().getLocation()} — <b>from inside the plugin's own class loader</b>,
 * in a real running Studio?
 *
 * <p><b>Why this is a real question and not a formality.</b> {@code PluginLoader} gives every
 * plugin folder its own <em>child-first</em> class loader, deliberately isolated from the
 * application's. {@code AdoRunWatcher} therefore cannot import {@code AppMainFrame} and reaches
 * it entirely by name: {@code Frame.getFrames()}, a class-name match, then {@code getMethod}
 * and {@code invoke}. Each of those steps has a way to fail that only shows up in a real
 * process — a frame class the plugin loader resolves to a different {@code Class}, a
 * non-public declaring class making {@code Method.invoke} throw {@code IllegalAccessException},
 * or simply no {@code AppMainFrame} at all. The downstream half of the chain is already proven
 * against the real organisation (ADO run 25518995); this is what stands between a finished run
 * and that proof.
 *
 * <p><b>What it does.</b> Starts Studio through its own {@code Main}, opens a project, then
 * asks the question twice, from two directions:
 *
 * <ol>
 *   <li><b>Directly.</b> Takes the plugin class loader from the {@code Class} objects
 *       {@code PluginLoader} itself handed to Studio, loads {@code AdoRunWatcher} <em>through
 *       that loader</em>, and invokes {@code resultsRoot()}. The answer is compared against the
 *       project location read independently from the application side. This is the verdict.
 *   <li><b>Through the production path.</b> Activates the guided-flow panel exactly as Studio's
 *       toolbar does — {@code StudioPanelPlugins.Panel.activate}, which calls
 *       {@code createPanel()}, which calls {@code AdoRunWatcher.arm()} — and captures what the
 *       watcher's own daemon thread logs. A watcher that says {@code Watching &lt;path&gt;} has
 *       resolved the project from inside the plugin, unaided, in the real arrangement.
 * </ol>
 *
 * <p><b>Guard rails.</b> {@link #ENV_PROJECT} short-circuits {@code resultsRoot()} with an
 * explicit path, so its presence would make the whole run meaningless — the driver refuses to
 * start when it is set. {@code ING_ADO_UPLOAD} must be {@code 0}: activating the panel arms a
 * watcher that really uploads, and a proof must never write to a live banking system.
 *
 * <pre>
 *   java -cp "ingenious-ide-3.0.0.jar;lib/*;lib/clib/*;." StudioWatcherDriver &lt;project&gt;
 * </pre>
 *
 * <p>Exit code: {@code 0} proven, {@code 4} disproven, {@code 3} inconclusive (the question was
 * never actually put — no Studio, no plugin, no project).
 */
public class StudioWatcherDriver {

    /** Studio's main window — the same name {@code AdoRunWatcher} matches on. */
    private static final String FRAME_CLASS = "com.ing.ide.main.mainui.AppMainFrame";
    private static final String WATCHER_CLASS = "de.ing.qa.studio.AdoRunWatcher";
    private static final String PANEL_CLASS = "de.ing.qa.panel.GuidedFlowPanel";
    private static final String PLUGINS_CLASS = "com.ing.ide.main.mainui.plugins.StudioPanelPlugins";
    private static final String LOADER_CLASS = "com.ing.engine.plugin.loader.PluginLoader";

    /** The override that would make this proof vacuous. Must not be set. */
    private static final String ENV_PROJECT = "ING_INGENIOUS_PROJECT";

    /** Everything the plugin logged, captured across the class-loader boundary. */
    private static final List<String> pluginLog = new CopyOnWriteArrayList<>();

    /** Held so the log manager cannot collect the logger we attached the handler to. */
    private static Logger pluginLogger;

    private static Object frame;
    /** The question itself: invoking the real method in the real plugin loader. */
    private static int checks;
    private static int failed;
    /** The same answer arrived at a second way. Supports the verdict; cannot overturn it. */
    private static int corroborations;
    private static int corroborationsFailed;

    public static void main(String[] args) throws Exception {
        String project = args.length > 0
            ? args[0]
            : new File("Projects/Tutorial").getAbsolutePath();

        // ------------------------------------------------------------------ guard rails
        String override = System.getenv(ENV_PROJECT);
        if (override != null && !override.isBlank()) {
            System.out.println("[driver] ABORT: " + ENV_PROJECT + "=" + override);
            System.out.println("[driver] That short-circuits resultsRoot(); the reflective path");
            System.out.println("[driver] would never run and the answer would mean nothing.");
            Runtime.getRuntime().halt(3);
        }
        if (!"0".equals(System.getenv("ING_ADO_UPLOAD"))) {
            System.out.println("[driver] ABORT: ING_ADO_UPLOAD must be 0.");
            System.out.println("[driver] Activating the panel arms a watcher that really uploads.");
            Runtime.getRuntime().halt(3);
        }
        System.out.println("[driver] " + ENV_PROJECT + " is unset — the reflective path is the only path.");
        System.out.println("[driver] ING_ADO_UPLOAD=0 — nothing can reach ADO.");
        System.out.println("[driver] plugin path : " + System.getenv("INGENIOUS_PLUGIN_PATH"));
        System.out.println("[driver] project     : " + project);

        capturePluginLog();

        // ------------------------------------------------------------------ real Studio
        System.out.println("[driver] starting Studio…");
        Class.forName("com.ing.ide.main.Main")
            .getMethod("main", String[].class).invoke(null, (Object) new String[0]);

        frame = awaitFrame(120_000);
        if (frame == null) {
            System.out.println("[driver] INCONCLUSIVE: no AppMainFrame appeared.");
            Runtime.getRuntime().halt(3);
        }
        System.out.println("[driver] Studio is up: " + frame.getClass().getName());

        System.out.println("[driver] opening project " + project);
        invokeOnEdt(frame, "loadProject", String.class, project);
        Thread.sleep(8000);

        // What the application itself says the project is. Read from the app class loader,
        // with no reflection games, so it is an independent answer to compare against.
        String expected = expectedResultsRoot();
        System.out.println("[driver] project location (application side): " + expected);
        if (expected == null) {
            System.out.println("[driver] INCONCLUSIVE: Studio has no project open, so there is");
            System.out.println("[driver] nothing for the watcher to find and nothing to compare.");
            Runtime.getRuntime().halt(3);
        }

        // ------------------------------------------------- 1. the plugin's own class loader
        ClassLoader pluginLoader = pluginClassLoader();
        if (pluginLoader == null) {
            System.out.println("[driver] INCONCLUSIVE: the plugin was never loaded — check");
            System.out.println("[driver] INGENIOUS_PLUGIN_PATH and the jar's pluginEntryClasses.");
            Runtime.getRuntime().halt(3);
        }
        ClassLoader appLoader = StudioWatcherDriver.class.getClassLoader();
        System.out.println();
        System.out.println("[driver] plugin class loader : " + pluginLoader);
        System.out.println("[driver] driver class loader : " + appLoader);
        check("the plugin really is in a class loader of its own", pluginLoader != appLoader);

        Class<?> watcher = Class.forName(WATCHER_CLASS, true, pluginLoader);
        check("AdoRunWatcher was loaded by the PLUGIN loader, not the application's",
            watcher.getClassLoader() == pluginLoader);

        // ------------------------------------------------------------- 2. THE QUESTION
        System.out.println();
        System.out.println("[driver] === invoking AdoRunWatcher.resultsRoot() in the plugin loader ===");
        Object answer = null;
        Throwable thrown = null;
        try {
            answer = watcher.getMethod("resultsRoot").invoke(null);
        } catch (Throwable ex) {
            thrown = ex.getCause() != null ? ex.getCause() : ex;
        }
        if (thrown != null) {
            System.out.println("[driver] resultsRoot() THREW: " + thrown);
            thrown.printStackTrace(System.out);
        }
        System.out.println("[driver] resultsRoot() = " + answer);
        System.out.println("[driver] expected      = " + expected);

        check("resultsRoot() did not throw from inside the plugin loader", thrown == null);
        check("resultsRoot() found a project at all (not null)", answer != null);
        check("and it is the project Studio actually has open",
            answer != null && expected.equals(String.valueOf(answer)));

        // ------------------------------------- 3. the cold start: is it armed on its own?
        // Studio has started and a project is open, and NO panel has been opened. This is the
        // tester who reopens Studio and presses F6 to re-run an existing case. If the watcher
        // is not armed here, that run's evidence reaches nobody.
        System.out.println();
        System.out.println("[driver] === cold start: armed before any panel was opened? ===");
        boolean coldArmed = (Boolean) watcher.getMethod("isArmed").invoke(null);
        System.out.println("[driver] AdoRunWatcher.isArmed() = " + coldArmed);
        System.out.println("[driver] " + (coldArmed
            ? "F6 on a freshly opened Studio WOULD be uploaded."
            : "F6 on a freshly opened Studio would upload NOTHING (#107 B)."));
        // Routed through the verdict table rather than left as prose. This is the only place
        // the cold-start question is put in a REAL Studio, and until 2026-07-28 coldArmed was
        // printed and then dropped: it reached neither check() nor corroborate(), so the
        // driver printed a decisive claim about the product ("F6 … would upload NOTHING") and
        // exited 0 either way. A corroboration, not a check, because #107 B is a known-open
        // defect and corroborations deliberately cannot overturn the verdict — but it is now
        // counted, and a silent regression here shows up in the corroboration line.
        corroborate("the watcher is armed on a cold start, before any panel was opened "
            + "(#107 B — F6 on a reopened Studio reaches the uploader)", coldArmed);

        // ---------------------------------------------- 4. the production path, for real
        System.out.println();
        System.out.println("[driver] === activating the guided-flow panel (production arm() path) ===");
        boolean activated = activateGuidedFlowPanel();
        corroborate("the guided-flow panel activated, so createPanel() ran arm() for real",
            activated);

        // The watcher's own daemon thread re-resolves the project every poll and says so.
        // Its log line is the plugin speaking for itself, from its own thread.
        System.out.println("[driver] waiting for the watcher thread to report…");
        String watching = awaitLogContaining("Watching ", 30_000);
        System.out.println("[driver] watcher said: " + watching);
        corroborate("the watcher thread resolved the project by itself", watching != null);
        corroborate("and named the project Studio has open",
            watching != null && watching.contains(trimResults(expected)));

        System.out.println();
        System.out.println("[driver] --- everything de.ing.qa logged ---");
        for (String line : pluginLog) {
            System.out.println("[plugin] " + line);
        }

        screenshot(new File("watcher-driver-screenshot.png"));

        System.out.println();
        System.out.println("[driver] question   : " + checks + " checks, " + failed + " failed");
        System.out.println("[driver] corroborate: " + corroborations + " checks, "
            + corroborationsFailed + " failed");
        System.out.println("[driver] VERDICT: reflection from the plugin class loader to the open"
            + " project is " + (failed == 0 ? "PROVEN" : "DISPROVEN"));
        if (failed == 0 && corroborationsFailed > 0) {
            // Said explicitly, because a harness that cannot drive the panel is a fact about
            // the harness. Letting it read as a product verdict is exactly the kind of
            // invisible-state mistake this whole chain exists to stop.
            System.out.println("[driver] NOTE: the corroborating production path did not run to"
                + " completion. That is a limitation of this harness against this Studio build,"
                + " not evidence against the answer above — the answer above came from invoking"
                + " the real method in the real plugin loader.");
        }
        // halt, not exit: Studio's shutdown hooks would prompt about the open project.
        Runtime.getRuntime().halt(failed == 0 ? 0 : 4);
    }

    // --------------------------------------------------------------------- the plugin side

    /**
     * The class loader {@code PluginLoader} built for our plugin folder, taken from the very
     * {@code Class} objects it handed Studio — not rebuilt here, so this is the loader the
     * application is really using.
     */
    private static ClassLoader pluginClassLoader() {
        try {
            Object classes = Class.forName(LOADER_CLASS)
                .getMethod("loadAllPluginsEntryClasses").invoke(null);
            if (!(classes instanceof List<?> list)) {
                return null;
            }
            System.out.println("[driver] plugin entry classes: " + list.size());
            ClassLoader any = null;
            for (Object item : list) {
                if (!(item instanceof Class<?> type)) {
                    continue;
                }
                System.out.println("[driver]   " + type.getName() + "  <- " + type.getClassLoader());
                if (PANEL_CLASS.equals(type.getName())) {
                    return type.getClassLoader();
                }
                if (type.getName().startsWith("de.ing.qa.")) {
                    any = type.getClassLoader();
                }
            }
            return any;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            System.out.println("[driver] could not reach PluginLoader: " + ex);
            return null;
        }
    }

    /**
     * Activates the guided-flow panel the way Studio's toolbar does, so {@code createPanel()}
     * — and with it {@code AdoRunWatcher.arm()} — runs in production conditions, on the event
     * dispatch thread, inside the plugin loader.
     */
    private static boolean activateGuidedFlowPanel() {
        try {
            Class<?> plugins = Class.forName(PLUGINS_CLASS);
            Object panels = plugins.getMethod("load").invoke(null);
            if (!(panels instanceof List<?> list) || list.isEmpty()) {
                System.out.println("[driver] no Studio panels discovered");
                return false;
            }
            Object target = null;
            for (Object panel : list) {
                String identity = String.valueOf(
                    panel.getClass().getMethod("getIdentity").invoke(panel));
                String title = String.valueOf(
                    panel.getClass().getMethod("getTitle").invoke(panel));
                System.out.println("[driver] panel: " + identity + "  \"" + title + "\"");
                if (identity.contains("GuidedFlow") || identity.contains("guided")) {
                    target = panel;
                }
            }
            if (target == null) {
                System.out.println("[driver] the guided-flow panel is not among them");
                return false;
            }
            // activate() gained a ProjectTestDataApi parameter after this Studio build, so
            // both shapes are accepted rather than assuming the source tree matches the jar.
            Method activate = null;
            for (Method m : target.getClass().getMethods()) {
                if ("activate".equals(m.getName()) && m.getParameterCount() <= 1) {
                    activate = m;
                }
            }
            if (activate == null) {
                System.out.println("[driver] Panel.activate(..) not found; available:");
                for (Method m : target.getClass().getMethods()) {
                    System.out.println("[driver]   " + m);
                }
                return false;
            }
            System.out.println("[driver] activating via " + activate);
            final Method call = activate;
            final Object panel = target;
            final Object[] args = call.getParameterCount() == 0
                ? new Object[0]
                : new Object[] { null };
            final Object[] result = new Object[1];
            // On the EDT: createPanel() builds Swing components, exactly as in Studio.
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = call.invoke(panel, args);
                } catch (Throwable ex) {
                    System.out.println("[driver] activate threw: "
                        + (ex.getCause() != null ? ex.getCause() : ex));
                }
            });
            if (result[0] == null) {
                return false;
            }
            System.out.println("[driver] panel component: " + result[0].getClass().getName()
                + "  <- " + result[0].getClass().getClassLoader());
            return true;
        } catch (Exception | LinkageError ex) {
            System.out.println("[driver] panel activation failed: " + ex);
            return false;
        }
    }

    // --------------------------------------------------------------------- the app side

    /**
     * The project location read straight from the application, with no name-based reflection,
     * so it is an independent answer rather than the same code path twice.
     */
    private static String expectedResultsRoot() {
        try {
            Object project = frame.getClass().getMethod("getProject").invoke(frame);
            if (project == null) {
                return null;
            }
            Object location = project.getClass().getMethod("getLocation").invoke(project);
            if (location == null) {
                return null;
            }
            return new File(String.valueOf(location), "Results").getPath();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            System.out.println("[driver] application side could not read the project: " + ex);
            return null;
        }
    }

    private static String trimResults(String resultsRoot) {
        File f = new File(resultsRoot);
        return f.getParent() == null ? resultsRoot : f.getParent();
    }

    // --------------------------------------------------------------------- plumbing

    /**
     * Captures what the plugin logs. {@code LogManager} is JVM-wide and lives in the platform
     * loader, so a handler attached here sees records published from inside the plugin's own
     * class loader — which is how the watcher thread can speak for itself.
     */
    private static void capturePluginLog() {
        pluginLogger = Logger.getLogger("de.ing.qa");
        pluginLogger.setLevel(Level.ALL);
        pluginLogger.addHandler(new Handler() {
            private final SimpleFormatter fmt = new SimpleFormatter();

            @Override
            public void publish(LogRecord record) {
                pluginLog.add(record.getLevel() + " " + record.getLoggerName() + ": "
                    + fmt.formatMessage(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    private static String awaitLogContaining(String needle, long timeoutMillis)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (String line : pluginLog) {
                if (line.contains(needle)) {
                    return line;
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    private static Object awaitFrame(long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (Frame f : Frame.getFrames()) {
                if (FRAME_CLASS.equals(f.getClass().getName())) {
                    return f;
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    private static void invokeOnEdt(Object target, String method, Class<?> type, Object arg)
        throws Exception {
        Method m = target.getClass().getMethod(method, type);
        SwingUtilities.invokeAndWait(() -> {
            try {
                m.invoke(target, arg);
            } catch (Exception ex) {
                System.out.println("[driver] " + method + " threw: " + ex);
            }
        });
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failed++;
        }
        System.out.println("  " + (ok ? "ok  " : "FAIL") + "   " + what);
    }

    private static void corroborate(String what, boolean ok) {
        corroborations++;
        if (!ok) {
            corroborationsFailed++;
        }
        System.out.println("  " + (ok ? "ok  " : "n/a ") + "   " + what + "  (corroboration)");
    }

    private static void screenshot(File out) {
        try {
            java.awt.Rectangle area = new java.awt.Rectangle(
                java.awt.Toolkit.getDefaultToolkit().getScreenSize());
            if (frame instanceof Frame f) {
                try {
                    f.toFront();
                } catch (RuntimeException ignored) {
                    // Focus is a hint on Windows; an unraised window still screenshots.
                }
                Thread.sleep(1000);
                if (f.getWidth() > 0 && f.getHeight() > 0) {
                    area = f.getBounds();
                }
            }
            javax.imageio.ImageIO.write(
                new java.awt.Robot().createScreenCapture(area), "png", out);
            System.out.println("[driver] screenshot: " + out.getAbsolutePath());
        } catch (Exception ex) {
            System.out.println("[driver] screenshot failed: " + ex);
        }
    }
}
