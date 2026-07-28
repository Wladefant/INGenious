import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * Walks the tester's whole job through the <b>real</b> {@code GuidedFlowPanel} in a <b>real</b>
 * running Studio, and says of every link whether it is proven, broken, or could not be tested.
 *
 * <p><b>Why this exists.</b> Every link in the chain — choose ADO test case, choose customer,
 * resolve the recording target without a prompt, finish a run, assemble evidence, invoke the
 * upload, show the status — has been proven <em>in isolation</em>, most of them against a Swing
 * frame that was never shown and a Studio that was a double. What had never happened is the
 * whole sequence, once, in one Studio, in one sitting. {@code StudioWatcherDriver} proved that a
 * driver can start Studio through its own {@code Main}, reach the genuine {@code
 * PluginClassLoader} and activate a panel as the toolbar does; this takes that foundation and
 * walks the rest of the way.
 *
 * <p><b>What it asserts on.</b> What a tester would see: the labels, the buttons, the banner,
 * the clipboard, the files on disk. Never an internal call count. The panel component is reached
 * through Studio's own {@code AppMainFrame.showPluginPanel} — the exact method the toolbar
 * button's action ends in — so the screen under test is the screen a tester gets, laid out and
 * showing in the real window.
 *
 * <p><b>Three rules it keeps, because this project has already paid for each of them.</b>
 *
 * <ol>
 *   <li><b>A check that cannot run reports {@link Verdict#COULD_NOT_TEST}, never a pass and
 *       never a failure.</b> A harness that cannot reach a method is a fact about the harness.
 *       The last lane reported "DISPROVEN" on five passing checks for exactly that reason.
 *   <li><b>Dialogs are judged from {@link Window#getWindows()}.</b> A modal Swing dialog pumps
 *       the event dispatch thread, so "the EDT still answers, therefore nothing popped" is
 *       false and has already fooled an agent here. Windows are also <em>classified</em>:
 *       Studio's own recorder console is not a prompt, and calling it one would be the mirror
 *       mistake.
 *   <li><b>An alarming measurement is verified before it is reported.</b> Every BROKEN verdict
 *       below carries the observation it rests on, so the reader can tell a defect from a
 *       driver that looked in the wrong place.
 * </ol>
 *
 * <p><b>Guard rails.</b> {@code ING_INGENIOUS_PROJECT} must be unset — it short-circuits {@code
 * AdoRunWatcher.resultsRoot()}, which is one of the links under test. {@code ING_ADO_UPLOAD}
 * must be {@code 0}: a finished run really does invoke the uploader, and a proof must never
 * write to a live banking system.
 *
 * <pre>
 *   java -cp "ingenious-ide-3.0.0.jar;lib/*;lib/clib/*;." StudioChainDriver &lt;project&gt; &lt;workdir&gt;
 * </pre>
 *
 * <p>Exit code: {@code 0} when nothing is broken, {@code 4} when something is, {@code 3} when
 * the run never got far enough to ask the questions at all.
 */
public class StudioChainDriver {

    // ------------------------------------------------------------------ the names we reflect on

    private static final String FRAME_CLASS = "com.ing.ide.main.mainui.AppMainFrame";
    private static final String MAIN_CLASS = "com.ing.ide.main.Main";
    private static final String PANEL_CLASS = "de.ing.qa.panel.GuidedFlowPanel";
    private static final String TARGET_CLASS = "de.ing.qa.studio.AdoRecordingTarget";
    private static final String WATCHER_CLASS = "de.ing.qa.studio.AdoRunWatcher";
    private static final String PLUGINS_CLASS = "com.ing.ide.main.mainui.plugins.StudioPanelPlugins";
    private static final String TARGETS_CLASS = "com.ing.ide.main.mainui.plugins.RecordingTargetPlugins";
    private static final String LOADER_CLASS = "com.ing.engine.plugin.loader.PluginLoader";

    /** The override that would make the watcher link vacuous. Must not be set. */
    private static final String ENV_PROJECT = "ING_INGENIOUS_PROJECT";

    // --------------------------------------------------------------- what a tester sees

    private static final String BTN_TAKE = "Diesen Testfall übernehmen";
    private static final String BTN_COPY = "Kontonummer kopieren";
    private static final String BTN_RECORD = "▶  Aufnahme starten";
    private static final String BTN_STOP = "■  Aufnahme beenden";

    /** The ADO case the fixture is built around. */
    private static final String ADO_ID = "3951650";


    // ------------------------------------------------------ the watcher's cadence, mirrored

    /**
     * {@code AdoRunWatcher.POLL_MS} and {@code SETTLE_MS}, restated here because L9b's wait has
     * to be <em>justified</em> rather than guessed. They are private over there, so this is a
     * copy: if they ever change, the numbers printed in L9b's evidence stop matching the
     * watcher's real cadence and the wait must be re-derived.
     */
    private static final long WATCHER_POLL_MS = 5000;

    /** How long a run directory must be unchanged before the watcher counts it as finished. */
    private static final long WATCHER_SETTLE_MS = 4000;

    /**
     * How many scans must pass over the engine's copy before "exactly one detection" is allowed
     * to mean anything. Fewer than this and a green L9b would only be saying the driver did not
     * wait long enough, which is worse than saying nothing.
     */
    private static final int MIN_POLLS_OVER_THE_COPY = 4;

    // ------------------------------------------------------------------ state

    /**
     * The terminal, captured before Studio can take it away.
     *
     * <p>Studio's console dialog replaces {@code System.out} when a recording or a run starts
     * ({@code ConsoleDialog.start()}). The third run of this driver therefore printed its entire
     * verdict table into a Swing window inside the application under test, and the terminal
     * showed nothing after "triggering Studio's own Run action" — a report that exists and
     * cannot be read is a report nobody has.
     */
    private static final java.io.PrintStream OUT = System.out;

    private static void say(String line) {
        OUT.println(line);
    }

    private static Object frame;
    private static JComponent panel;
    private static File shots;
    private static int shotIndex;

    /** Everything the plugin logged, captured across the class-loader boundary. */
    private static final List<String> pluginLog = new CopyOnWriteArrayList<>();
    /** Everything Studio's own classes logged — where the recorder says what it is doing. */
    private static final List<String> studioLog = new CopyOnWriteArrayList<>();
    private static final List<Logger> loggers = new ArrayList<>();

    /** The windows Studio had open before we started pressing things. */
    private static Set<String> windowBaseline = new LinkedHashSet<>();

    /**
     * Whether step 1 really ended with a test case taken.
     *
     * <p>Everything downstream reads the selection file, so a downstream link asked after a
     * failed step 1 measures the driver and not the product — which is exactly how this
     * driver's first run reported the recording-target link as broken when the only thing
     * wrong was that no case had been chosen.
     */
    private static boolean caseChosen;

    /**
     * The engine copies already on disk when the run was triggered — see
     * {@link #engineCopiesHoldingReports}. A copy in this set is one the watcher swept into its
     * history at start-up, so its silence about it says nothing about the duplicate-run filter.
     */
    private static Set<String> copiesBeforeTheRun = new LinkedHashSet<>();

    private static final List<Link> links = new ArrayList<>();

    enum Verdict {
        /** A tester would see this work. */
        PROVEN,
        /** A tester would see this fail. This is the interesting one. */
        BROKEN,
        /** The question could not be put. Says nothing about the product either way. */
        COULD_NOT_TEST
    }

    /**
     * One link of the chain and what became of it.
     *
     * @param id short handle, so the report and the console agree
     * @param what the link, in the terms a tester would use
     * @param verdict see {@link Verdict}
     * @param evidence what was actually observed — the sentence the verdict rests on
     */
    record Link(String id, String what, Verdict verdict, String evidence) {
    }

    // ------------------------------------------------------------------ main

    /**
     * How much of the walk to do.
     *
     * <p><b>chain</b> is the whole tester job. <b>runonly</b> skips straight to the run and lets
     * Studio execute whatever test case the project opens with — which exists because the case
     * the guided flow records is <em>empty</em>: recording against the real application needs an
     * interactive single-sign-on session, so nothing gets recorded into it, and an empty run
     * never reaches the engine's report-copying step. The one-run-one-upload question can only
     * be put by a run that executes a step, so it needs a case that already has some.
     */
    private static String mode = "chain";

    public static void main(String[] args) throws Exception {
        String project = args.length > 0 ? args[0] : new File("Projects/CLIDemo").getAbsolutePath();
        shots = new File(args.length > 1 ? args[1] : ".", "shots");
        shots.mkdirs();
        if (args.length > 2) {
            mode = args[2];
        }

        guardRails();
        captureLogs();

        say("[driver] project     : " + project);
        say("[driver] plugin path : " + System.getenv("INGENIOUS_PLUGIN_PATH"));
        say("[driver] selection   : " + System.getenv("ING_TESTCASE_SELECTION"));
        say("[driver] screenshots : " + shots.getAbsolutePath());
        say("");

        // ------------------------------------------------------------------ Studio, for real
        say("[driver] starting Studio…");
        Class.forName(MAIN_CLASS).getMethod("main", String[].class)
            .invoke(null, (Object) new String[0]);

        frame = awaitFrame(180_000);
        if (frame == null) {
            say("[driver] no AppMainFrame appeared — nothing could be asked.");
            Runtime.getRuntime().halt(3);
        }
        say("[driver] Studio is up: " + frame.getClass().getName());

        say("[driver] opening project " + project);
        invokeOnEdt(frame, "loadProject", String.class, project);
        Thread.sleep(10_000);
        shoot("00-studio-project-open");

        linkStudioAndPlugin(project);
        String identity = linkPanelDiscovered();
        linkPanelOpened(identity);

        // Everything from here is a press or a read on the screen a tester is looking at.
        windowBaseline = windowCensus();
        say("[driver] window baseline: " + windowBaseline);

        // The report is the deliverable, so nothing may cost it: a link that throws, a modal
        // dialog that will not go away, an engine that never returns. The second run of this
        // driver ended without a table at all, which is the one outcome that helps nobody.
        watchdog(20 * 60_000);
        try {
            if (!"runonly".equals(mode)) {
                linkChooseTestCase();
                linkChooseCustomer();
                linkSummary();
                linkRecordingTarget();
                linkRecordingStarted();
            } else {
                say("[driver] runonly: skipping the panel walk; Studio runs the test case the");
                say("[driver] project opens with, which — unlike a freshly recorded one — has steps.");
            }
            linkRunAndUpload();
        } catch (Throwable ex) {
            add("!!", "The driver itself ran into trouble", Verdict.COULD_NOT_TEST,
                "The walk stopped at " + describe(ex) + ". Everything above it still stands; "
                    + "everything below it was never asked.");
            ex.printStackTrace(OUT);
        }
        report();
    }

    /** Prints whatever has been established and stops, however stuck everything else is. */
    private static void watchdog(long millis) {
        Thread guard = new Thread(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                return;
            }
            add("!!", "The driver ran out of time", Verdict.COULD_NOT_TEST,
                "The whole walk was given " + (millis / 60_000) + " minutes and did not finish. "
                    + "The links above are what had been established by then.");
            report();
        }, "chain-watchdog");
        guard.setDaemon(true);
        guard.start();
    }

    // ------------------------------------------------------------------ the links

    /**
     * The ground the rest stands on: a real Studio with a project open, and this plugin loaded
     * into a class loader of its own — which is what makes every reflective hop in the plugin a
     * real question rather than a formality.
     */
    private static void linkStudioAndPlugin(String project) {
        String projectLocation = expectedProjectLocation();
        if (projectLocation == null) {
            add("L1", "Studio runs with the project open and the plugin in its own class loader",
                Verdict.BROKEN,
                "Studio started and " + FRAME_CLASS + " exists, but getProject() answered null "
                    + "after loadProject(" + project + ") — no project is open, so nothing "
                    + "downstream has anything to work on.");
            return;
        }
        ClassLoader pluginLoader = pluginClassLoader();
        if (pluginLoader == null) {
            add("L1", "Studio runs with the project open and the plugin in its own class loader",
                Verdict.COULD_NOT_TEST,
                "PluginLoader handed out no de.ing.qa entry class. Check "
                    + "INGENIOUS_PLUGIN_PATH and the jar's pluginEntryClasses — with no plugin "
                    + "loaded there is no chain to walk.");
            return;
        }
        boolean own = pluginLoader != StudioChainDriver.class.getClassLoader();
        add("L1", "Studio runs with the project open and the plugin in its own class loader",
            own ? Verdict.PROVEN : Verdict.BROKEN,
            "project=" + projectLocation + "; plugin loader=" + pluginLoader
                + "; driver loader=" + StudioChainDriver.class.getClassLoader());
    }

    /**
     * Whether the tester is offered <em>one</em> way in. Four toolbar buttons of which three are
     * steps of the fourth is the defect this panel's manifest was narrowed to fix, so the count
     * is part of the check, not a detail.
     *
     * @return the panel identity Studio dispatches on, or {@code null} when it was not found
     */
    private static String linkPanelDiscovered() {
        try {
            Object panels = Class.forName(PLUGINS_CLASS).getMethod("load").invoke(null);
            if (!(panels instanceof List<?> list)) {
                add("L2", "The guided flow is offered as a Studio screen", Verdict.COULD_NOT_TEST,
                    "StudioPanelPlugins.load() did not answer with a List but with " + panels);
                return null;
            }
            List<String> titles = new ArrayList<>();
            String identity = null;
            String title = null;
            for (Object p : list) {
                String id = String.valueOf(p.getClass().getMethod("getIdentity").invoke(p));
                String t = String.valueOf(p.getClass().getMethod("getTitle").invoke(p));
                titles.add(t + " [" + id + "]");
                if (id.contains("GuidedFlow")) {
                    identity = id;
                    title = t;
                }
            }
            if (identity == null) {
                add("L2", "The guided flow is offered as a Studio screen", Verdict.BROKEN,
                    "Studio discovered " + list.size() + " panel(s) and none of them is the "
                        + "guided flow: " + titles);
                return null;
            }
            // The count is the check, exactly as the javadoc above says — and it used to be
            // computed into `single`, printed as a NOTE, and never allowed near the verdict.
            // Studio offering the four toolbar buttons again — the regression this panel's
            // manifest was narrowed to fix — read PROVEN.
            boolean single = list.size() == 1;
            add("L2", "The guided flow is offered as a Studio screen",
                single ? Verdict.PROVEN : Verdict.BROKEN,
                "Studio offers " + list.size() + " plugin screen(s): " + titles
                    + (single ? " — one way in, as intended."
                        : " — the tester is offered MORE THAN ONE way in. The manifest was "
                            + "narrowed to a single screen precisely because four toolbar "
                            + "buttons, three of them steps of the fourth, is the defect.")
                    + " Title as the tester reads it: \"" + title + "\".");
            return identity;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            add("L2", "The guided flow is offered as a Studio screen", Verdict.COULD_NOT_TEST,
                "Could not reach StudioPanelPlugins: " + describe(ex));
            return null;
        }
    }

    /**
     * Opens the screen the way the toolbar button does — {@code AppMainFrame.showPluginPanel},
     * which is where {@code AppActionListener} ends up for a {@code Plugin Panel:} action — and
     * checks it is really on screen afterwards. A panel that builds but never becomes the
     * visible slide is a panel no tester ever sees.
     */
    private static void linkPanelOpened(String identity) throws Exception {
        if (identity == null) {
            add("L3", "Pressing the toolbar button opens the screen", Verdict.COULD_NOT_TEST,
                "The panel was never discovered (see L2), so there was no identity to open.");
            return;
        }
        Method show;
        try {
            show = frame.getClass().getMethod("showPluginPanel", String.class);
        } catch (NoSuchMethodException ex) {
            add("L3", "Pressing the toolbar button opens the screen", Verdict.COULD_NOT_TEST,
                "This Studio build has no AppMainFrame.showPluginPanel(String) — the toolbar "
                    + "path could not be exercised. That is a fact about the installed jar, "
                    + "not about the panel.");
            return;
        }
        AtomicReference<String> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                show.invoke(frame, identity);
            } catch (Exception ex) {
                failure.set(describe(ex.getCause() != null ? ex.getCause() : ex));
            }
        });
        settle();
        Thread.sleep(4000);

        // Two independent ways of getting hold of the screen, because the first one has
        // already lied once. Asking Panel.activate to hand the component back depends on a
        // signature that differs between the installed jar and the source tree; finding the
        // screen inside the Studio window does not depend on anything but its being there.
        JComponent viaApi = activatedComponent(identity);
        JComponent inWindow = panelInWindow();
        panel = inWindow != null ? inWindow : viaApi;

        if (panel == null) {
            add("L3", "Pressing the toolbar button opens the screen", Verdict.BROKEN,
                "showPluginPanel(\"" + identity + "\") produced no screen"
                    + (failure.get() == null ? " and did not throw" : " and threw " + failure.get())
                    + ", and no component carrying \"" + BTN_TAKE + "\" is anywhere in the Studio "
                    + "window. A tester pressing the toolbar button would get nothing.");
            return;
        }
        boolean showing = onEdt(() -> panel.isShowing());
        boolean inFrame = onEdt(() -> SwingUtilities.isDescendingFrom(panel, (Component) frame));
        shoot("01-panel-open");
        add("L3", "Pressing the toolbar button opens the screen",
            showing && inFrame ? Verdict.PROVEN : Verdict.BROKEN,
            "showPluginPanel(\"" + identity + "\") — the screen is "
                + (showing ? "showing" : "NOT showing") + " and is "
                + (inFrame ? "inside" : "NOT inside") + " the Studio window; headline on it: "
                + quote(headlineIn(panel)) + "; found "
                + (inWindow != null ? "in the window" : "only through Panel.activate")
                + (viaApi == null
                    ? " (Panel.activate could not hand it back on this build: " + activateSignature()
                        + " — a fact about the installed jar, not about the panel)"
                    : "")
                + anchorEvidence());
    }

    /** Step 1: the real chooser, the real list, the real "übernehmen" button. */
    private static void linkChooseTestCase() throws Exception {
        if (panel == null) {
            add("L4", "Step 1 — choose the ADO test case", Verdict.COULD_NOT_TEST,
                "The screen never opened (see L3).");
            return;
        }
        JList<?> list = (JList<?>) besideButton(BTN_TAKE, JList.class);
        if (list == null) {
            add("L4", "Step 1 — choose the ADO test case", Verdict.BROKEN,
                "Step 1 has no test-case list on it at all. Every JList in the Studio window: "
                    + census(JList.class));
            return;
        }
        int rows = awaitRows(() -> list.getModel().getSize(), 60_000);
        if (rows == 0) {
            String status = labelTextContaining(panel, "Testfälle");
            add("L4", "Step 1 — choose the ADO test case", Verdict.COULD_NOT_TEST,
                disagreement("Testfälle geladen", status, JList.class)
                    + "The test-case list stayed empty for 60s. Status line: " + quote(status)
                    + ". With no cases to choose from, the step cannot be exercised — check "
                    + "ING_ADO_CACHE. Every JList in the Studio window: " + census(JList.class));
            return;
        }
        int index = indexOfAdoCase(list, ADO_ID);
        if (index < 0) {
            add("L4", "Step 1 — choose the ADO test case", Verdict.COULD_NOT_TEST,
                rows + " cases loaded but none carries ADO id " + ADO_ID
                    + " — the fixture this driver expects is not the one that was loaded.");
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        });
        settle();
        click(panel, BTN_TAKE);
        settle();
        Thread.sleep(800);
        shoot("02-testfall-gewaehlt");

        String chip = chipText("Testfall wählen");
        String banner = bannerText();
        String selectionFile = System.getenv("ING_TESTCASE_SELECTION");
        String written = selectionFile == null ? null : readIfExists(Path.of(selectionFile));
        List<String> popped = newWindows();

        boolean chipTicked = chip != null && chip.startsWith("✔");
        boolean bannerNamesCase = banner != null && banner.contains(ADO_ID);
        boolean persisted = written != null && written.contains(ADO_ID);

        caseChosen = persisted;
        if (chipTicked && bannerNamesCase && persisted && popped.isEmpty()) {
            add("L4", "Step 1 — choose the ADO test case", Verdict.PROVEN,
                "Selected row " + index + " of " + rows + " and pressed \"" + BTN_TAKE
                    + "\". Chip now reads \"" + chip + "\"; banner: " + quote(banner)
                    + "; " + selectionFile + " holds ADO id " + ADO_ID + "; no dialog opened.");
        } else {
            add("L4", "Step 1 — choose the ADO test case", Verdict.BROKEN,
                "After pressing \"" + BTN_TAKE + "\": chip=\"" + chip + "\" (ticked="
                    + chipTicked + "), banner=" + quote(banner) + " (names the case="
                    + bannerNamesCase + "), selection file written=" + persisted
                    + " (" + selectionFile + "), windows that opened=" + popped);
        }
    }

    /** Step 2: the real customer table, the real copy button, the real system clipboard. */
    private static void linkChooseCustomer() throws Exception {
        if (panel == null) {
            add("L5", "Step 2 — choose the customer, Kontonummer to the clipboard",
                Verdict.COULD_NOT_TEST, "The screen never opened (see L3).");
            return;
        }
        JTable table = (JTable) besideButton(BTN_COPY, JTable.class);
        if (table == null) {
            add("L5", "Step 2 — choose the customer, Kontonummer to the clipboard",
                Verdict.BROKEN, "Step 2 has no customer table on it at all. Every JTable in the "
                    + "Studio window: " + census(JTable.class));
            return;
        }
        int rows = awaitRows(table::getRowCount, 30_000);
        if (rows == 0) {
            String status = labelTextContaining(panel, "Testkunden");
            add("L5", "Step 2 — choose the customer, Kontonummer to the clipboard",
                Verdict.COULD_NOT_TEST,
                disagreement("Testkunden geladen", status, JTable.class)
                    + "The customer table stayed empty for 30s — check ING_TESTDATA_CSV. The "
                    + "panel says: " + quote(status) + ". Every JTable in the Studio window: "
                    + census(JTable.class));
            return;
        }
        // Selecting first is not politeness: copySelected() opens a JOptionPane when nothing is
        // selected, and a driver that tripped it would be measuring its own mistake.
        SwingUtilities.invokeAndWait(() -> table.setRowSelectionInterval(0, 0));
        settle();

        // The clipboard is emptied to a sentinel first. Comparing "after" against "before"
        // is not enough: the previous run of this driver left the same Kontonummer there, so
        // a perfectly good copy read as "nothing changed" and the step was reported broken.
        String sentinel = "leer-" + System.currentTimeMillis();
        setClipboard(sentinel);
        String expected = accountInRow(table, 0);

        click(panel, BTN_COPY);
        settle();
        Thread.sleep(800);
        shoot("03-kunde-gewaehlt");

        String account = clipboard();
        String chip = chipText("Kunde wählen");
        String banner = bannerText();
        List<String> popped = newWindows();

        boolean copied = account != null && !account.equals(sentinel) && account.matches("\\d{6,}");
        // NOT `expected == null || …`. accountInRow() answers null both when the table has no
        // column whose caption contains "kontonummer" and on any exception, and either made the
        // cross-check unconditionally true while the PROVEN evidence below went on claiming
        // "which is the Kontonummer of the row that was selected". One line in the label map
        // renaming that caption is all it would take. A cross-check that could not be made is
        // reported as not made, not folded into the pass.
        boolean rightOne = expected != null && expected.equals(account);
        boolean chipTicked = chip != null && chip.startsWith("✔");
        boolean bannerNamesAccount = banner != null && account != null && banner.contains(account);

        if (expected == null) {
            add("L5", "Step 2 — choose the customer, Kontonummer to the clipboard",
                Verdict.COULD_NOT_TEST,
                "The selected row's own Kontonummer could not be read out of the table — no "
                    + "column caption contains \"Kontonummer\", or reading the cell threw — so "
                    + "there is nothing to compare the clipboard against. The clipboard holds "
                    + quote(account) + " (emptied to " + quote(sentinel) + " beforehand), but "
                    + "whether that is the row the tester is LOOKING at, rather than row 0 of "
                    + "the file, is exactly what this link is for and it was not established.");
            return;
        }

        if (copied && rightOne && chipTicked && bannerNamesAccount && popped.isEmpty()) {
            add("L5", "Step 2 — choose the customer, Kontonummer to the clipboard",
                Verdict.PROVEN,
                rows + " customers offered; row 0 selected and \"" + BTN_COPY + "\" pressed. The "
                    + "clipboard was emptied to \"" + sentinel + "\" first and now holds "
                    + account + ", which is the Kontonummer of the row that was selected ("
                    + expected + "); chip reads \"" + chip
                    + "\"; banner: " + quote(banner) + "; no dialog opened.");
        } else {
            add("L5", "Step 2 — choose the customer, Kontonummer to the clipboard",
                Verdict.BROKEN,
                "After \"" + BTN_COPY + "\": clipboard=" + quote(account) + " (emptied to "
                    + quote(sentinel) + " beforehand; the selected row's Kontonummer is "
                    + quote(expected) + "), chip=\"" + chip + "\", banner=" + quote(banner)
                    + ", windows that opened=" + popped);
        }

        // The durable half of the same press: the profile written onto the test case through
        // Studio's real ProjectTestDataApi. Reported separately because the clipboard working
        // and the profile landing are two different promises to the tester.
        linkProfilePersisted(banner);
    }

    /**
     * The customer profile on the test case — the half of step 2 that outlives the clipboard.
     *
     * <p>Judged from two independent places: the sentence the panel puts in front of the tester,
     * and the sheet on disk. Either alone could mislead — the sentence is the panel quoting
     * itself, and a file could have been there already.
     */
    private static void linkProfilePersisted(String banner) {
        String location = expectedProjectLocation();
        File sheet = location == null ? null : new File(location, "TestData/Testkunde.csv");
        boolean onDisk = sheet != null && sheet.isFile();
        String content = onDisk ? readIfExists(sheet.toPath()) : null;
        boolean claimsWritten = banner != null && banner.contains("wurden am Testfall vermerkt");
        boolean claimsNotWritten = banner != null && banner.contains("NICHTS vermerkt");

        // Whether Studio can even offer the handle. The write goes through
        // StudioPanelApi.setProjectTestData, which Panel.activate only calls on a build whose
        // activate takes the handle. On an older jar the profile CANNOT be written, and calling
        // that a plugin defect would be reporting the install as the product.
        boolean handoverExists = activateSignature().contains("ProjectTestDataApi");

        if (claimsWritten && onDisk) {
            add("L5b", "The customer profile is recorded on the test case", Verdict.PROVEN,
                "The panel says the profile was recorded and " + sheet + " exists ("
                    + (content == null ? "?" : content.split("\\R").length) + " line(s), first: "
                    + quote(firstLine(content)) + ").");
        } else if (claimsNotWritten && !handoverExists) {
            add("L5b", "The customer profile is recorded on the test case",
                Verdict.COULD_NOT_TEST,
                "This install cannot carry out the write at all, so the link could not be put to "
                    + "the test: the Studio jar's only activation method is "
                    + activateSignature() + ", which never calls "
                    + "StudioPanelApi.setProjectTestData, so the plugin is never handed the "
                    + "project's test data. The panel says so honestly rather than silently: "
                    + quote(banner) + ". The source tree's Panel.activate does take the handle — "
                    + "the installed jar simply predates it.");
        } else if (claimsNotWritten) {
            add("L5b", "The customer profile is recorded on the test case", Verdict.BROKEN,
                "Studio does hand the plugin the project test data on this build ("
                    + activateSignature() + ") and the panel still told the tester nothing was "
                    + "recorded: " + quote(banner) + ". Sheet on disk: "
                    + (onDisk ? String.valueOf(sheet) : "absent."));
        } else if (claimsWritten) {
            add("L5b", "The customer profile is recorded on the test case", Verdict.BROKEN,
                "The panel says the profile was recorded, but " + sheet + " does not exist. A "
                    + "confirmation a tester would believe, with nothing behind it.");
        } else {
            add("L5b", "The customer profile is recorded on the test case", Verdict.COULD_NOT_TEST,
                "The banner said neither — " + quote(banner) + " — so there is nothing to "
                    + "compare the disk against. Sheet: "
                    + (onDisk ? String.valueOf(sheet) : "absent"));
        }
    }

    /** Step 3: what the tester is told before pressing the one button that matters. */
    private static void linkSummary() throws Exception {
        if (panel == null) {
            add("L6", "Step 3 — the summary names the case, the customer and the address",
                Verdict.COULD_NOT_TEST, "The screen never opened (see L3).");
            return;
        }
        if (!caseChosen) {
            // Step 3 is only rendered once step 1 and step 2 are done — the panel refuses to
            // advance and says so. Reading its empty summary after a failed step 1 measures
            // the driver, exactly as pressing "Aufnahme starten" would (see L8), and this link
            // reported BROKEN for that reason on the runs of 2026-07-28.
            add("L6", "Step 3 — the summary names the case, the customer and the address",
                Verdict.COULD_NOT_TEST,
                "No test case was taken in step 1 (see L4), so the panel never advanced to step "
                    + "3 and there is no summary to read. An empty summary here would be the "
                    + "correct answer to \"nothing was chosen\", not a defect.");
            return;
        }
        settle();
        Thread.sleep(1500);
        shoot("04-schritt-3");
        String summary = summaryText();
        String recordButton = buttonText(panel, BTN_RECORD) != null ? BTN_RECORD
            : (buttonText(panel, BTN_STOP) != null ? BTN_STOP : null);
        String recorderState = labelTextContaining(panel, "Aufnahme");
        String startUrl = labelTextContaining(panel, "Der Browser öffnet");
        String noStartUrl = labelTextContaining(panel, "keine Start-Adresse");
        String account = clipboard();

        if (summary == null) {
            add("L6", "Step 3 — the summary names the case, the customer and the address",
                Verdict.BROKEN, "Step 3 shows no summary at all.");
            return;
        }
        boolean namesCase = summary.contains(ADO_ID);
        boolean namesAccount = account != null && summary.contains(account);
        boolean buttonOffersStart = BTN_RECORD.equals(recordButton);

        if (namesCase && namesAccount && buttonOffersStart) {
            add("L6", "Step 3 — the summary names the case, the customer and the address",
                Verdict.PROVEN,
                "Summary names ADO " + ADO_ID + " and Kontonummer " + account
                    + "; the button reads \"" + recordButton + "\"; recorder line: "
                    + quote(recorderState) + "; start address line: "
                    + quote(startUrl != null ? startUrl : noStartUrl));
        } else {
            add("L6", "Step 3 — the summary names the case, the customer and the address",
                Verdict.BROKEN,
                "namesCase=" + namesCase + ", namesAccount=" + namesAccount + " (" + account
                    + "), button=\"" + recordButton + "\". Summary begins: "
                    + quote(firstLines(summary, 4)));
        }
    }

    /**
     * The link no other proof reaches from the outside: Studio asking the plugins where the
     * recording goes, and getting an answer, so {@code RecordingTargetDialog} never opens.
     *
     * <p>This calls {@code RecordingTargetPlugins.currentTarget()} — the exact expression at
     * {@code TestCaseComponent.record()}'s decision point. A non-null answer there is what makes
     * the {@code else} branch, which is the dialog, unreachable.
     */
    private static void linkRecordingTarget() {
        if (!caseChosen) {
            add("L7", "The recording target is resolved without asking the tester again",
                Verdict.COULD_NOT_TEST,
                "Step 1 never wrote a selection (see L4), and currentTarget() answers from that "
                    + "file. Asking it here would measure the driver, not the product — a null "
                    + "answer would be the correct answer to \"no case chosen\".");
            return;
        }
        Object target;
        try {
            target = Class.forName(TARGETS_CLASS).getMethod("currentTarget").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            add("L7", "The recording target is resolved without asking the tester again",
                Verdict.COULD_NOT_TEST,
                "Could not reach RecordingTargetPlugins.currentTarget(): " + describe(ex)
                    + ". This Studio build may predate the extension point.");
            return;
        }
        if (target == null) {
            add("L7", "The recording target is resolved without asking the tester again",
                Verdict.BROKEN,
                "currentTarget() answered null even though a test case is chosen — Studio would "
                    + "open its own target chooser and the tester would pick a second time, with "
                    + "nothing stopping the two answers disagreeing.");
            return;
        }
        try {
            String scenario = String.valueOf(
                target.getClass().getMethod("getScenarioName").invoke(target));
            String testCase = String.valueOf(
                target.getClass().getMethod("getTestCaseName").invoke(target));
            boolean carriesId = testCase.startsWith(ADO_ID);
            add("L7", "The recording target is resolved without asking the tester again",
                carriesId ? Verdict.PROVEN : Verdict.BROKEN,
                "currentTarget() = " + scenario + " / " + testCase
                    + (carriesId
                        ? " — the name carries the ADO id, so a later run is publishable back to"
                            + " the case it came from, and RecordingTargetDialog is unreachable."
                        : " — the name does NOT start with the chosen ADO id " + ADO_ID
                            + ", so the run could not be traced back to ADO."));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            add("L7", "The recording target is resolved without asking the tester again",
                Verdict.COULD_NOT_TEST,
                "currentTarget() answered " + target + " but its accessors could not be read: "
                    + describe(ex));
        }
    }

    /**
     * Presses the button the tester presses, and reads what Studio did about it.
     *
     * <p>The recording itself cannot be completed here: the application under test is reached
     * through an interactive single-sign-on session that must not be automated, and this
     * install has no Playwright browsers. What <em>can</em> be observed, and is the whole of
     * what this link is about, is everything Studio does before the browser: it resolves the
     * target without a prompt, creates the test case that carries the ADO id, and says on its
     * own console where the recording is going.
     */
    private static void linkRecordingStarted() throws Exception {
        if (panel == null) {
            add("L8", "Pressing \"Aufnahme starten\" sets the recording up, with no prompt",
                Verdict.COULD_NOT_TEST, "The screen never opened (see L3).");
            return;
        }
        if (!caseChosen) {
            add("L8", "Pressing \"Aufnahme starten\" sets the recording up, with no prompt",
                Verdict.COULD_NOT_TEST,
                "No test case was taken in step 1 (see L4). Studio would then correctly open its "
                    + "own target chooser — that is the documented fall-back, not a defect — so "
                    + "pressing the button here would measure the driver, not the product.");
            return;
        }
        if (buttonText(panel, BTN_RECORD) == null) {
            add("L8", "Pressing \"Aufnahme starten\" sets the recording up, with no prompt",
                Verdict.COULD_NOT_TEST,
                "The button does not read \"" + BTN_RECORD + "\" — nothing was pressed.");
            return;
        }
        int studioLogBefore = studioLog.size();
        click(panel, BTN_RECORD);
        // The core does the target resolution and the test-case creation on the press, then
        // hands the browser launch to a CompletableFuture. Twenty seconds is far more than the
        // first half needs and short enough that a missing browser does not hold the run.
        Thread.sleep(20_000);
        settle();
        shoot("05-aufnahme-gedrueckt");

        List<String> since = new ArrayList<>(
            studioLog.subList(Math.min(studioLogBefore, studioLog.size()), studioLog.size()));
        List<String> popped = newWindows();
        List<String> prompts = prompts(popped);
        String created = createdTestCaseFile();
        String recorderLine = labelTextContaining(panel, "Aufnahme");
        String banner = bannerText();

        if (!prompts.isEmpty()) {
            add("L8", "Pressing \"Aufnahme starten\" sets the recording up, with no prompt",
                Verdict.BROKEN,
                "A prompt opened in front of the tester: " + prompts + ". Other windows: "
                    + popped + ". Banner: " + quote(banner));
            // Dismissed so it cannot hold the event dispatch thread and turn every later link
            // into a second casualty of this one.
            dismissDialogs();
            return;
        }
        if (created != null) {
            add("L8", "Pressing \"Aufnahme starten\" sets the recording up, with no prompt",
                Verdict.PROVEN,
                "No prompt opened (windows that appeared: " + popped + " — Studio's own recorder"
                    + " console, not a question). Studio created the test case on disk: "
                    + created + ". Panel now says: " + quote(recorderLine) + ". Banner: "
                    + quote(banner));
        } else {
            add("L8", "Pressing \"Aufnahme starten\" sets the recording up, with no prompt",
                Verdict.COULD_NOT_TEST,
                "No prompt opened, and nothing was rejected, but no test case carrying ADO id "
                    + ADO_ID + " appeared under the project's TestPlan within 20s, so the "
                    + "set-up half could not be confirmed. Panel says: " + quote(recorderLine)
                    + ". Banner: " + quote(banner) + ". Studio logged since the press: "
                    + tail(since, 8));
        }

        // Leave nothing running: a live recorder would keep a browser and a watcher thread up
        // while the next link measures the Results directory.
        if (buttonText(panel, BTN_STOP) != null) {
            click(panel, BTN_STOP);
            settle();
            Thread.sleep(3000);
            shoot("06-aufnahme-beendet");
        }
    }

    /**
     * The last two links together, because one causes the other: a run that finishes, and the
     * evidence of it reaching the tester's screen.
     *
     * <p>The run is a real INGenious run — {@code TestCaseComponent}'s own {@code Run} action,
     * the engine in-process, a report written under {@code Results/}. Nothing is planted there:
     * planting a directory would prove that the watcher can read a directory, which was never
     * the question. What follows is the plugin's, unaided: the watcher notices, {@code
     * parse-report.mjs} reads the report, {@code ado-upload.mjs} is invoked, and its answer has
     * to arrive on the panel's own upload line.
     *
     * <p>{@code ING_ADO_UPLOAD=0}, so the uploader answers {@code ADO-UPLOAD AUS} and touches
     * nothing in Azure DevOps. That is a real invocation with a real answer — the one thing it
     * cannot prove is the ADO write itself, which is already proven elsewhere.
     */
    private static void linkRunAndUpload() throws Exception {
        String location = expectedProjectLocation();
        if (location == null) {
            add("L9", "A finished run is noticed and its evidence assembled",
                Verdict.COULD_NOT_TEST, "No project is open.");
            add("L10", "The upload is invoked and its outcome is on the tester's screen",
                Verdict.COULD_NOT_TEST, "No run could be started.");
            return;
        }
        Path results = Path.of(location, "Results");
        int before = countRunDirs(results);
        // Taken before the run so L9b can tell an armed trap from a dud one: a "Latest" that
        // was already there is one the watcher took as history and will never re-detect.
        copiesBeforeTheRun = engineCopiesHoldingReports(results);
        say("[driver] engine copies already holding a report before the run: "
            + (copiesBeforeTheRun.isEmpty() ? "none — the duplicate question can be put"
                : copiesBeforeTheRun));

        Object comp = testCaseComponent();
        if (comp == null) {
            add("L9", "A finished run is noticed and its evidence assembled",
                Verdict.COULD_NOT_TEST,
                "getTestDesign().getTestCaseComp() could not be reached, so no run could be "
                    + "triggered from here.");
            add("L10", "The upload is invoked and its outcome is on the tester's screen",
                Verdict.COULD_NOT_TEST, "No run could be started (see L9).");
            return;
        }

        boolean armed = watcherArmed();
        say("[driver] AdoRunWatcher.isArmed() before the run = " + armed);

        say("[driver] triggering Studio's own Run action…");
        AtomicReference<String> failure = new AtomicReference<>();
        try {
            Method perform = comp.getClass().getMethod("actionPerformed", ActionEvent.class);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    perform.invoke(comp, new ActionEvent(comp, ActionEvent.ACTION_PERFORMED, "Run"));
                } catch (Exception ex) {
                    failure.set(describe(ex.getCause() != null ? ex.getCause() : ex));
                }
            });
        } catch (NoSuchMethodException ex) {
            add("L9", "A finished run is noticed and its evidence assembled",
                Verdict.COULD_NOT_TEST,
                "This Studio build's TestCaseComponent has no actionPerformed(ActionEvent).");
            add("L10", "The upload is invoked and its outcome is on the tester's screen",
                Verdict.COULD_NOT_TEST, "No run could be started (see L9).");
            return;
        }

        // The engine runs on its own thread and the watcher polls every five seconds; the
        // uploader is then a child process. Three minutes covers all of it with room to spare.
        Path fresh = awaitNewRun(results, before, 180_000);
        shoot("07-lauf");

        if (fresh == null) {
            String why = failure.get() != null
                ? "the Run action threw " + failure.get()
                : "no new run directory with a report appeared under " + results
                    + " within 180s (before=" + before + ", now=" + countRunDirs(results) + ")";
            add("L9", "A finished run is noticed and its evidence assembled",
                Verdict.COULD_NOT_TEST,
                "No run could be completed here: " + why + ". The recorded test case is empty — "
                    + "recording against the real banking application needs an interactive "
                    + "single-sign-on session and must not be automated — so there may have been "
                    + "nothing for the engine to execute.");
            add("L10", "The upload is invoked and its outcome is on the tester's screen",
                Verdict.COULD_NOT_TEST, "No finished run to upload (see L9).");
            return;
        }

        // Wait for the engine to have FINISHED writing, not merely started: the report file
        // appears before data.js and before the engine's own Latest copy, and judging a
        // half-written run would measure how fast this driver polls.
        boolean complete = awaitRunComplete(fresh, 120_000);
        say("[driver] run directory complete (data.js or Latest present): " + complete);

        String noticed = awaitPluginLog("Finished run detected", 60_000);
        add("L9", "A finished run is noticed and its evidence assembled",
            noticed != null ? Verdict.PROVEN : Verdict.BROKEN,
            noticed != null
                ? "The engine wrote " + fresh + " and the watcher said so by itself: "
                    + quote(noticed)
                : "The engine wrote " + fresh + " but the watcher never reported it within 60s. "
                    + "Watcher armed=" + watcherArmed() + ". Everything the plugin logged: "
                    + tail(pluginLog, 10));

        // Wait for the upload to have been attempted at all before counting anything: the
        // watcher polls every five seconds and the uploader is a child process.
        String published = awaitPluginLog("ADO-Upload", 120_000);
        linkOneRunOneUpload(fresh, complete);

        // The tester's own evidence: the line on the panel, not a log nobody reads.
        String uploadLine = awaitLabelContaining(panel, "ADO-Upload", 60_000);
        shoot("08-upload-status");

        if (uploadLine == null) {
            String still = labelTextContaining(panel, "Azure DevOps");
            add("L10", "The upload is invoked and its outcome is on the tester's screen",
                published == null ? Verdict.COULD_NOT_TEST : Verdict.BROKEN,
                published == null
                    ? "The uploader was never invoked for this run, so there was no outcome to "
                        + "put on screen. The plugin logged: " + tail(pluginLog, 6)
                    : "The uploader ran and said " + quote(published) + ", and the panel's upload "
                        + "line never changed — it still reads " + quote(still) + ". The tester "
                        + "would be left believing nothing had happened.");
            return;
        }
        // Matched against the documented status word, not against a bare substring: the line is
        // "ADO-Upload <CODE> — <text>", and "AUS" or "ÜBERSPRUNGEN" anywhere in a German
        // sentence would otherwise decide this.
        boolean off = uploadLine.contains("ADO-Upload AUS");
        boolean skipped = uploadLine.contains("ADO-Upload ÜBERSPRUNGEN")
            || uploadLine.contains("ADO-Upload UEBERSPRUNGEN");
        if (!off && !skipped) {
            // The verdict used to be the literal Verdict.PROVEN and these three branches chose
            // only the prose, so "ADO-Upload FEHLER — kein Token" was a pass with a NOTE on it.
            // guardRails() refuses to start unless ING_ADO_UPLOAD=0, so AUS and ÜBERSPRUNGEN are
            // the only two answers this configuration permits; anything else means the uploader
            // did not do what was asked of it, and that is not something to report as proven.
            add("L10", "The upload is invoked and its outcome is on the tester's screen",
                Verdict.COULD_NOT_TEST,
                "A line reached the screen — " + quote(uploadLine) + " — but it is neither the "
                    + "switched-off answer nor the skipped one, and this driver only ever runs "
                    + "with ING_ADO_UPLOAD=0. So the uploader did not complete the path it was "
                    + "asked to take, and whether its OUTCOME is rendered correctly cannot be "
                    + "read off a run that did not produce the expected outcome."
                    + (published == null ? "" : " Plugin log: " + quote(published)));
            return;
        }
        add("L10", "The upload is invoked and its outcome is on the tester's screen",
            Verdict.PROVEN,
            "The uploader ran for real and its answer reached the screen: " + quote(uploadLine)
                + (off
                    ? " — ING_ADO_UPLOAD=0, so it answered AUS and wrote nothing to Azure DevOps."
                    : " — the run's test case carries no ADO number, so there was nothing to "
                        + "upload. That is the SKIPPED path, told apart from off and from "
                        + "failed, which is what this line exists for. The switched-off path "
                        + "(ADO-Upload AUS) was therefore not the one exercised here.")
                + (published == null ? "" : " Plugin log: " + quote(published)));
    }

    /**
     * One run, one upload — checked because the engine writes every run twice.
     *
     * <p>{@code Results/…/&lt;TestCase&gt;/&lt;date time&gt;} is the run, and
     * {@code Results/…/&lt;TestCase&gt;/Latest} is the engine's copy of the same one. Both hold
     * a {@code data.js} and a {@code *-v2.html}, so without {@code AdoRunWatcher.isEngineCopy}
     * both satisfy {@code finishedRuns} and one finished test becomes two uploads and two
     * Bestanden marks in Azure DevOps.
     *
     * <p><b>Why this method was rewritten.</b> Until 2026-07-28 it had <em>no reachable PROVEN
     * branch once the copy exists</em> — and the copy always exists, because the engine writes
     * it at the end of every run. The only verdicts reachable in the one scenario the check is
     * about were BROKEN and COULD NOT TEST, so the fix
     * (<a href="https://github.com/Wladefant/ing-qa-automation/commit/4cd7e35">4cd7e35</a>)
     * working could never be confirmed, only its absence denounced. A check that can only ever
     * deliver bad news is read as noise and then ignored.
     *
     * <p><b>What "proven" is allowed to mean here.</b> Not merely "one detection". A single
     * detection is also what a dead watcher, a copy that was never really there, a window too
     * short to poll twice, and a copy the watcher had already swept into its history all look
     * like. So PROVEN requires the trap to have been demonstrably <em>armed</em>: the copy on
     * disk before and after, the watcher thread alive before and after, the copy absent from
     * the watcher's start-up history, and a wait long enough for at least
     * {@value #MIN_POLLS_OVER_THE_COPY} scans over it. Each of those, failing, gives COULD NOT
     * TEST — never a pass.
     *
     * <p>Two independent sides, as the BROKEN branch always had: the live watcher's own log
     * lines, and the product's {@code finishedRuns} asked directly with the copy on disk. The
     * second is what still decides when the first is masked, and it is what makes a reverted
     * fix show up as BROKEN rather than as a timing question.
     */
    private static void linkOneRunOneUpload(Path fresh, boolean complete) throws Exception {
        Path family = fresh.getParent();
        Path latest = family == null ? null : family.resolve("Latest");

        // The engine's copy is written last of all. Counting before it is there would report
        // "one upload" for a run whose second one has not been made yet — the pleasant version
        // of the same measurement mistake.
        boolean copyOnDisk = latest != null && holdsReport(latest);
        if (complete && !copyOnDisk && latest != null) {
            copyOnDisk = awaitCondition(() -> holdsReport(latest), 30_000);
        }

        boolean armedBefore = watcherArmed();
        long window = WATCHER_SETTLE_MS + (MIN_POLLS_OVER_THE_COPY + 1L) * WATCHER_POLL_MS;
        if (copyOnDisk) {
            // Timed from the copy being SEEN, not from its file stamps: the engine copies the
            // report files and may carry their modification times over, which would make the
            // copy look settled before it existed. Waiting from the observation is the bound
            // that cannot be wrong in the direction that matters.
            say("[driver] the engine's copy is at " + latest + "; waiting " + (window / 1000)
                + "s so the watcher scans it at least " + MIN_POLLS_OVER_THE_COPY + " times "
                + "(poll " + (WATCHER_POLL_MS / 1000) + "s, settle " + (WATCHER_SETTLE_MS / 1000)
                + "s)");
            Thread.sleep(window);
        }
        boolean armedAfter = watcherArmed();
        boolean copyStillThere = latest != null && holdsReport(latest);
        boolean masked = latest != null && copiesBeforeTheRun.contains(latest.toString());

        // Detections and upload receipts belonging to THIS run — the timestamped directory and
        // its copy are the only two members of that family. Counting every detection of the
        // session would let an unrelated earlier run decide this verdict.
        String prefix = family == null ? null : family.toString();
        List<String> detected = new ArrayList<>();
        List<String> detectedElsewhere = new ArrayList<>();
        List<String> receipts = new ArrayList<>();
        List<String> uploads = new ArrayList<>();
        for (String line : pluginLog) {
            if (line.contains("Finished run detected")) {
                String dir = line.substring(line.indexOf("detected:") + 9).trim();
                if (prefix == null || dir.startsWith(prefix)) {
                    detected.add(dir);
                } else {
                    detectedElsewhere.add(dir);
                }
            }
            if (line.contains("AdoRunWatcher") && line.contains("] ")
                && line.contains("ADO-Upload")) {
                receipts.add(line.substring(line.indexOf('[')).trim());
            }
            if (line.contains("AdoUpload") && line.contains("ADO-Upload")) {
                uploads.add(line);
            }
        }
        boolean copyWasUploaded = receipts.stream().anyMatch(r -> r.startsWith("[Latest]"));

        // What the product itself would hand the watcher, right now, with the copy on disk.
        // Independent of every timing question above: if this list holds the copy, the filter
        // is not doing its job whatever the log happens to show.
        List<String> offered = finishedRunsNow();
        boolean oracleOffersCopy = offered != null && latest != null
            && offered.contains(latest.toString());
        String oracle = offered == null
            ? "AdoRunWatcher.finishedRuns could not be called on this build, so only the live "
                + "watcher's log speaks here"
            : "asked directly, AdoRunWatcher.finishedRuns() offers " + offered.size()
                + " run(s) with the copy on disk: " + offered;

        String seen = "Detections under " + family + ": " + detected
            + (detectedElsewhere.isEmpty() ? "" : " (elsewhere, not counted: " + detectedElsewhere + ")")
            + ". Upload receipts: " + receipts + ". " + oracle + ".";

        if (!complete && !copyOnDisk) {
            add("L9b", "One finished run causes exactly one upload", Verdict.COULD_NOT_TEST,
                "This run never finished writing its report while the driver was watching — "
                    + fresh + " holds no data.js and the engine's own \"Latest\" copy was never "
                    + "made — so there was never a second directory for the watcher to trip "
                    + "over and the question could not be put. It can only be put by a run that "
                    + "executes at least one step; the case recorded in this walk has none, "
                    + "because recording against the real application needs an interactive "
                    + "single-sign-on session. " + seen);
            return;
        }
        if (!copyOnDisk) {
            // Never PROVEN. This branch is entered exactly when the engine's "Latest" copy —
            // the trap this whole link exists for — is NOT on disk, and the evidence string
            // said so in as many words while the verdict said PROVEN. That contradicted this
            // method's own rule ("PROVEN requires the trap to have been demonstrably armed …
            // never a pass") and it is the LIKELIEST branch, because PASS 1's recorded test
            // case is empty by design. One run and one detection with no copy present says the
            // watcher does not invent runs; it says nothing about whether it rejects the
            // engine's copy, which is the property whose failure costs two Bestanden marks in
            // Azure DevOps for one run. PASS 2 (runonly) exists to put this question properly.
            add("L9b", "One finished run causes exactly one upload", Verdict.COULD_NOT_TEST,
                (detected.size() == 1
                    ? "The run finished, the engine made no second copy of it, and exactly one "
                        + "upload attempt followed — so the watcher does not invent runs. But "
                        + "with no copy on disk the duplicate question was never put at all: "
                        + "this says nothing about whether the engine's copy is rejected. "
                    : detected.size() + " run directories were detected and the engine made no "
                        + "copy, which is not a shape this check can judge. ")
                    + "Use the runonly pass, on a project copy with no Results directory. "
                    + seen);
            return;
        }

        // ---- the copy is on disk: the trap the fix exists for is in place ----

        if (detected.size() > 1 || copyWasUploaded || oracleOffersCopy) {
            add("L9b", "One finished run causes exactly one upload", Verdict.BROKEN,
                "One run produced " + detected.size() + " \"finished run\" detection(s), "
                    + uploads.size() + " upload attempt(s)"
                    + (copyWasUploaded ? " — one of them for the copy itself — " : " ")
                    + (oracleOffersCopy
                        ? "and AdoRunWatcher.finishedRuns offers the engine's copy \"" + latest
                            + "\" as a run in its own right. "
                        : "")
                    + "The cause is structural, not incidental: HtmlSummaryHandler.createLatest() "
                    + "copies the whole run directory to \"" + latest + "\" at the end of every "
                    + "run, so the copy holds the same data.js and -v2.html and satisfies "
                    + "finishedRuns unless it is filtered out by name. On a test case named from "
                    + "ADO that is TWO uploads and two Bestanden marks in Azure DevOps for one "
                    + "run. Seen from two independent sides: the watcher's own log lines, and "
                    + "the directories on disk. " + seen);
            return;
        }
        if (detected.isEmpty()) {
            add("L9b", "One finished run causes exactly one upload", Verdict.COULD_NOT_TEST,
                "The engine made its copy at " + latest + ", but the watcher reported no "
                    + "finished run under " + family + " at all, so there is no \"exactly one\" "
                    + "to confirm — see L9 for why. " + seen);
            return;
        }
        if (!armedBefore || !armedAfter) {
            add("L9b", "One finished run causes exactly one upload", Verdict.COULD_NOT_TEST,
                "Exactly one detection was logged, but the watcher thread was "
                    + (armedBefore ? "alive before the wait and dead after it" : "not alive "
                        + "before the wait") + ", so \"only one\" may mean only that nothing was "
                    + "looking. A dead watcher and a correct one produce the same count. " + seen);
            return;
        }
        if (!copyStillThere) {
            add("L9b", "One finished run causes exactly one upload", Verdict.COULD_NOT_TEST,
                "The engine's copy was at " + latest + " when the wait began and is not there "
                    + "now, so the watcher may never have scanned the tree with the trap in it. "
                    + seen);
            return;
        }
        if (masked) {
            add("L9b", "One finished run causes exactly one upload", Verdict.COULD_NOT_TEST,
                "The engine's copy at " + latest + " already held a report before this run was "
                    + "triggered, so AdoRunWatcher's start-up sweep took it as history and would "
                    + "not have re-detected it however the filter behaves. One detection here is "
                    + "therefore not evidence about the filter. Run this against a project copy "
                    + "with no Results directory. " + seen);
            return;
        }
        add("L9b", "One finished run causes exactly one upload", Verdict.PROVEN,
            "The engine really did make its copy — " + latest + " holds a report, the same shape "
                + "as the run itself — and the watcher still detected exactly one finished run "
                + "and made exactly one upload attempt for it, naming the timestamped directory "
                + "and never the copy. The trap was demonstrably armed: the copy was on disk "
                + "before and after the wait, it was absent from the watcher's start-up history "
                + "so a re-detection was possible, the watcher thread was alive throughout, and "
                + "the wait was " + (window / 1000) + "s — enough for at least "
                + MIN_POLLS_OVER_THE_COPY + " scans at the watcher's " + (WATCHER_POLL_MS / 1000)
                + "s poll and " + (WATCHER_SETTLE_MS / 1000) + "s settle, so a second detection "
                + "had every opportunity to happen. " + seen);
    }

    /**
     * What {@code AdoRunWatcher.finishedRuns} offers right now, asked of the class in the
     * plugin's own loader — the product's own answer, with the engine's copy on disk.
     *
     * @return the run directories it would hand the uploader, or {@code null} when this build
     *     does not expose the method, which is a fact about the build and not a verdict
     */
    private static List<String> finishedRunsNow() {
        String location = expectedProjectLocation();
        ClassLoader loader = pluginClassLoader();
        if (location == null || loader == null) {
            return null;
        }
        try {
            Object list = Class.forName(WATCHER_CLASS, true, loader)
                .getMethod("finishedRuns", Path.class, long.class)
                .invoke(null, Path.of(location, "Results"), WATCHER_SETTLE_MS);
            if (!(list instanceof List<?> paths)) {
                return null;
            }
            List<String> found = new ArrayList<>();
            for (Object p : paths) {
                found.add(String.valueOf(p));
            }
            found.sort(String::compareTo);
            return found;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            say("[driver] could not ask AdoRunWatcher.finishedRuns directly: " + describe(ex));
            return null;
        }
    }

    /**
     * The engine copies that already hold a report — taken before the run is triggered.
     *
     * <p>{@code AdoRunWatcher.watch} sweeps everything present when it first resolves the
     * project into {@code seen} and never reports it. A copy that was already there is
     * therefore a dud trap: the watcher would stay silent about it whether or not it filters
     * copies out, and "one detection" would prove nothing at all.
     */
    private static Set<String> engineCopiesHoldingReports(Path results) {
        Set<String> found = new LinkedHashSet<>();
        if (!Files.isDirectory(results)) {
            return found;
        }
        try (var walk = Files.walk(results, 6)) {
            walk.filter(Files::isDirectory)
                .filter(d -> d.getFileName() != null
                    && "Latest".equalsIgnoreCase(d.getFileName().toString()))
                .filter(StudioChainDriver::holdsReport)
                .forEach(d -> found.add(d.toString()));
        } catch (Exception ignored) {
            // Results need not exist before the first run; that is the normal starting state.
        }
        return found;
    }

    /** Whether a directory holds what the watcher counts as a report. */
    private static boolean holdsReport(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        try (var entries = Files.list(dir)) {
            return entries.anyMatch(e -> {
                String name = e.getFileName().toString().toLowerCase();
                return Files.isRegularFile(e)
                    && (name.equals("data.js") || name.endsWith("-v2.html"));
            });
        } catch (Exception ex) {
            return false;
        }
    }

    /** Whether the engine got as far as finishing this run: data.js, or its own Latest copy. */
    private static boolean awaitRunComplete(Path fresh, long timeoutMillis) throws Exception {
        return awaitCondition(() -> Files.isRegularFile(fresh.resolve("data.js"))
            || (fresh.getParent() != null && holdsReport(fresh.getParent().resolve("Latest"))),
            timeoutMillis);
    }

    private static boolean awaitCondition(java.util.function.BooleanSupplier condition,
                                          long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(1000);
        }
        return condition.getAsBoolean();
    }

    // ------------------------------------------------------------------ the report

    private static void report() {
        say("");
        say("================================================================");
        say("  THE CHAIN, LINK BY LINK");
        say("================================================================");
        int broken = 0;
        int untested = 0;
        for (Link link : links) {
            if (link.verdict() == Verdict.BROKEN) {
                broken++;
            }
            if (link.verdict() == Verdict.COULD_NOT_TEST) {
                untested++;
            }
            say("");
            say(link.id() + "  " + pad(link.verdict()) + "  " + link.what());
            say("      " + wrap(link.evidence()));
        }
        say("");
        say("----------------------------------------------------------------");
        say("  " + links.size() + " links: "
            + count(Verdict.PROVEN) + " proven, "
            + broken + " broken, " + untested + " could not be tested");
        say("  screenshots: " + shots.getAbsolutePath());
        // How the screen was found belongs in the summary, not only in the transcript: a walk
        // located by prose can be wrong about everything above, and a reader who only sees the
        // totals would never know which kind of walk this was.
        if (anchor != null) {
            say("  " + (anchor.guessed() ? "!! " : "") + anchor.note());
        }
        if (untested > 0) {
            say("  !! " + untested + " link(s) were NEVER PUT. Nothing below them is a pass.");
        }
        say("----------------------------------------------------------------");
        say("");
        say("[driver] --- everything de.ing.qa logged ---");
        for (String line : pluginLog) {
            say("[plugin] " + line);
        }
        say("");
        say("[driver] --- the last of what Studio itself logged ---");
        say("[studio] " + tail(studioLog, 25).replace(" | ", "\n[studio] "));
        OUT.flush();
        // halt, not exit: Studio's shutdown hooks would prompt about the open project.
        //
        // Three answers, not two. `untested` used to be counted, printed and then thrown away:
        // halt(broken > 0 ? 4 : 0) meant a walk in which EVERY link was COULD_NOT_TEST exited
        // 0, and a caller reads 0 as "no link is broken". Two paths made that routine rather
        // than theoretical — the catch(Throwable) around the walk turns any exception into a
        // COULD_NOT_TEST link and falls into report(), and the 20-minute watchdog reports from
        // a daemon thread — so a driver that crashed, and a driver that HUNG, both exited 0.
        // Every sibling harness here already keeps the third answer apart (AdoHarness 3,
        // GuidedFlowHarness 4, StudioContractHarness 4, StudioRecordDriver 3); this is the one
        // driver that walks the whole tester chain in a real Studio, and it did not.
        //
        // 4 is already BROKEN here, so UNGEPRUEFT is 5.
        Runtime.getRuntime().halt(broken > 0 ? 4 : untested > 0 ? 5 : 0);
    }

    private static long count(Verdict verdict) {
        return links.stream().filter(l -> l.verdict() == verdict).count();
    }

    private static void add(String id, String what, Verdict verdict, String evidence) {
        links.add(new Link(id, what, verdict, evidence));
        say("");
        say(">>> " + id + " " + pad(verdict) + " " + what);
        say("    " + evidence);
    }

    private static String pad(Verdict verdict) {
        return switch (verdict) {
            case PROVEN -> "[ PROVEN        ]";
            case BROKEN -> "[ BROKEN        ]";
            default -> "[ COULD NOT TEST]";
        };
    }

    // ------------------------------------------------------------------ guard rails

    private static void guardRails() {
        String override = System.getenv(ENV_PROJECT);
        if (override != null && !override.isBlank()) {
            say("[driver] ABORT: " + ENV_PROJECT + "=" + override);
            say("[driver] That short-circuits AdoRunWatcher.resultsRoot(), which is");
            say("[driver] one of the links under test. The answer would mean nothing.");
            Runtime.getRuntime().halt(3);
        }
        if (!"0".equals(System.getenv("ING_ADO_UPLOAD"))) {
            say("[driver] ABORT: ING_ADO_UPLOAD must be 0.");
            say("[driver] A finished run really does invoke the uploader, and a proof");
            say("[driver] must never write to a live banking system.");
            Runtime.getRuntime().halt(3);
        }
        say("[driver] " + ENV_PROJECT + " is unset — the watcher must find the project itself.");
        say("[driver] ING_ADO_UPLOAD=0 — nothing can reach ADO.");
    }

    // ------------------------------------------------------------------ the plugin side

    /**
     * The class loader {@code PluginLoader} built for our plugin folder, taken from the very
     * {@code Class} objects it handed Studio.
     */
    private static ClassLoader pluginClassLoader() {
        try {
            Object classes = Class.forName(LOADER_CLASS)
                .getMethod("loadAllPluginsEntryClasses").invoke(null);
            if (!(classes instanceof List<?> list)) {
                return null;
            }
            ClassLoader any = null;
            for (Object item : list) {
                if (!(item instanceof Class<?> type)) {
                    continue;
                }
                say("[driver]   entry class " + type.getName()
                    + "  <- " + type.getClassLoader());
                if (PANEL_CLASS.equals(type.getName()) || TARGET_CLASS.equals(type.getName())) {
                    return type.getClassLoader();
                }
                if (type.getName().startsWith("de.ing.qa.")) {
                    any = type.getClassLoader();
                }
            }
            return any;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            say("[driver] could not reach PluginLoader: " + describe(ex));
            return null;
        }
    }

    /** Whether the watcher thread is running, asked of the class in the PLUGIN's loader. */
    private static boolean watcherArmed() {
        try {
            ClassLoader loader = pluginClassLoader();
            if (loader == null) {
                return false;
            }
            return Boolean.TRUE.equals(
                Class.forName(WATCHER_CLASS, true, loader).getMethod("isArmed").invoke(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * The component Studio built for the panel, without activating anything a second time.
     *
     * <p>{@code Panel.activate} gained a {@code ProjectTestDataApi} parameter after the jar in
     * this install was built, so <b>both shapes are accepted</b>. Assuming one of them is how
     * this driver's first run reported a working screen as broken: the no-argument method was
     * skipped, the search fell off the end, and {@code null} read as "the panel did not open".
     * Nothing here may assume the source tree and the installed jar agree.
     */
    private static JComponent activatedComponent(String identity) {
        try {
            Object p = Class.forName(PLUGINS_CLASS)
                .getMethod("find", String.class).invoke(null, identity);
            if (p == null) {
                say("[driver] StudioPanelPlugins.find(\"" + identity + "\") = null");
                return null;
            }
            Method best = null;
            for (Method m : p.getClass().getMethods()) {
                if ("activate".equals(m.getName()) && m.getParameterCount() <= 1
                    && (best == null || m.getParameterCount() > best.getParameterCount())) {
                    best = m;
                }
            }
            if (best == null) {
                say("[driver] no Panel.activate(..) on this build");
                return null;
            }
            // Idempotent: activationAttempted is already true, so this returns the cached
            // component and builds nothing.
            Object c = best.invoke(p, best.getParameterCount() == 0
                ? new Object[0] : new Object[] { null });
            return c instanceof JComponent jc ? jc : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            say("[driver] could not read back the activated panel: " + describe(ex));
            return null;
        }
    }

    /** The {@code Panel.activate} this install really has — quoted whenever it matters. */
    private static String activateSignature() {
        try {
            List<String> found = new ArrayList<>();
            for (Method m : Class.forName(PLUGINS_CLASS + "$Panel").getMethods()) {
                if ("activate".equals(m.getName())) {
                    found.add(m.toString());
                }
            }
            return found.isEmpty() ? "no activate(..) at all" : String.join(" / ", found);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return "could not be read: " + describe(ex);
        }
    }

    /**
     * The guided-flow screen as it sits in the Studio window — found by its own contents, so no
     * signature, cache or accessor can come between the driver and what is actually there.
     *
     * <p>Climbs from the one button only this screen has to the <b>innermost</b> ancestor that
     * also carries the step headline. Innermost, not outermost: Studio's slide show holds every
     * screen at once, so the outermost match is the slide show itself — and searching from
     * there finds another slide's empty list and reports the customer step as unreachable while
     * the panel's own status line says two test cases are loaded. That is what happened on the
     * second run of this driver.
     *
     * <p><b>Both mechanisms now live in {@link PanelAnchor}</b>, which prefers the panel's own
     * marker — client property {@code de.ing.qa.panel=guided-flow} — and only falls back to the
     * headline climb when the marker is absent, saying so in as many words. Read that class for
     * why: a relied-on instrument must not be locatable by prose, and this one was, twice.
     *
     * <p>The short version. Until 2026-07-28 the climb matched the loose substring "Schritt ",
     * and the panel's layout is {@code header (chips, headline, banner) NORTH / cardHost
     * CENTER}: the take button lives in card 0, and the "Aufnahme prüfen" hint added by
     * <a href="https://github.com/Wladefant/ing-qa-automation/commit/8a4d03a">8a4d03a</a> —
     * "…ob jeder <b>Schritt</b> genau ein Element trifft…" — lives in card 2. The innermost
     * ancestor holding both is {@code cardHost}, one level BELOW the header, so the climb
     * stopped there and every check that reads a chip or the banner was reading a subtree the
     * panel does not keep them in. The chain report then called L4 and L5 BROKEN and L5b
     * untestable while the screenshot beside it showed the ticked chip and the green
     * confirmation banner. A product string is allowed to contain any word it likes; a driver
     * that binds to prose is a driver that will be broken again by the next sentence.
     */
    private static JComponent panelInWindow() {
        PanelAnchor.Result found = PanelAnchor.locate((Component) frame, BTN_TAKE);
        anchor = found;
        say("[driver] " + found.note());
        return found.panel();
    }

    /**
     * How the screen was located on this run — carried into L3's evidence so a transcript never
     * has to be trusted to have been read. Never null once {@link #panelInWindow()} has run.
     */
    private static PanelAnchor.Result anchor;

    /**
     * L3's sentence about the anchor. A fallback is stated as a qualification on every verdict
     * below it, not as a footnote: on 2026-07-28 the fallback was wrong and three links were
     * reported broken that were not.
     */
    private static String anchorEvidence() {
        if (anchor == null) {
            return "; anchor: not established";
        }
        return "; " + anchor.note()
            + (anchor.guessed()
                ? " EVERY VERDICT BELOW RESTS ON THAT GUESS — the panel does not yet carry the "
                    + "marker, so this walk is only as trustworthy as one German sentence."
                : "");
    }

    /**
     * The panel's own step headline — {@code "Schritt 2 von 3 — Kunde wählen"} — wherever it is
     * under {@code c}, or {@code null} if this subtree does not carry one.
     *
     * <p>Shape, not substring: {@code GuidedFlowPanel.update()} writes exactly
     * {@code "Schritt " + n + " von 3 — " + title} into one label, and no prose the panel shows
     * elsewhere has that form. Kept in {@link PanelAnchor} so the driver and the headless
     * regression check cannot drift apart about what the headline is.
     */
    private static String headlineIn(Component c) {
        return PanelAnchor.headlineIn(c);
    }

    // --------------------------------------------------- reading the RIGHT widget, provably

    /**
     * The widget belonging to the picker that owns a given button — never merely the first one
     * of its type on the screen.
     *
     * <p><b>Why this exists.</b> {@code find(panel, JList.class, null)} answers with the first
     * list in a depth-first walk, which is only the right one as long as the root it is given is
     * exactly the picker. On 2026-07-28 that assumption failed twice in a live Studio:
     * {@code panelInWindow} resolved to Studio's slide show, which holds every screen at once, so
     * the first list found belonged to a different slide and was empty — while the chooser's own
     * status line, further down the same tree, correctly read "2 Testfälle geladen". The chain
     * report called that a product failure for L4, L5 and L6.
     *
     * <p>Climbing from the button instead binds the widget to the picker structurally: the take
     * button and the case list share the chooser's own root, the copy button and the customer
     * table share the picker's own root, and no other slide can come between them.
     */
    private static Component besideButton(String buttonText, Class<?> widget) {
        Component button = find(panel, AbstractButton.class, buttonText);
        if (button == null) {
            say("[driver] no button \"" + buttonText + "\" under the panel root");
            return null;
        }
        for (Component c = button.getParent(); c != null; c = c.getParent()) {
            Component found = find(c, widget, null);
            if (found != null) {
                say("[driver] " + widget.getSimpleName() + " for \"" + buttonText + "\" found "
                    + "under " + c.getClass().getSimpleName() + " — " + ancestry(found));
                return found;
            }
            if (c == panel) {
                break;
            }
        }
        return null;
    }

    /**
     * The sentence that turns a broken measurement into a statement about the measurement.
     *
     * <p>A screen cannot both say it loaded rows and own a widget with none: if the panel's own
     * status line disagrees with the widget this driver read, the driver is reading something the
     * panel does not own, and that is a fact about the driver. Saying so in the verdict is the
     * check that the runs of 2026-07-28 lacked — they reported the contradiction as evidence
     * against the product and cost a day of doubt about a screen that was working.
     *
     * @return the warning, or "" when there is no contradiction to report
     */
    private static String disagreement(String loadedPhrase, String status, Class<?> widget) {
        boolean statusClaimsRows = status != null
            && status.matches(".*(?<![1-9])[1-9]\\d*\\s+" + loadedPhrase + ".*");
        boolean somethingElseHasRows = false;
        for (Component c : all((Component) frame)) {
            if (widget.isInstance(c) && rowsIn(c) > 0) {
                somethingElseHasRows = true;
                break;
            }
        }
        if (!statusClaimsRows && !somethingElseHasRows) {
            return "";
        }
        return "THIS IS A FACT ABOUT THE DRIVER, NOT A VERDICT ON THE PRODUCT. "
            + (statusClaimsRows
                ? "The panel's own status line reads " + quote(status) + " while the widget this "
                    + "driver measured holds none. "
                : "")
            + (somethingElseHasRows
                ? "Another " + widget.getSimpleName() + " in the same window does hold rows. "
                : "")
            + "A screen cannot have loaded rows and own none, so the driver read a component the "
            + "picker does not own — check that the panel root is the picker and not a container "
            + "holding several screens. ";
    }

    /** Every widget of a type in the whole window, with its size and where it sits. */
    private static String census(Class<?> widget) {
        List<String> seen = new ArrayList<>();
        for (Component c : all((Component) frame)) {
            if (widget.isInstance(c)) {
                seen.add(rowsIn(c) + " row(s) at " + ancestry(c));
            }
        }
        return seen.isEmpty() ? "there is none at all" : String.join("  ||  ", seen);
    }

    private static int rowsIn(Component c) {
        if (c instanceof JList<?> list) {
            return list.getModel().getSize();
        }
        if (c instanceof JTable table) {
            return table.getRowCount();
        }
        return -1;
    }

    /** A component's place in the tree, close ancestors first — enough to tell slides apart. */
    private static String ancestry(Component c) {
        StringBuilder sb = new StringBuilder(c.getClass().getSimpleName());
        Component parent = c.getParent();
        for (int i = 0; i < 6 && parent != null; i++, parent = parent.getParent()) {
            sb.append(" < ").append(parent.getClass().getSimpleName());
            if (parent == panel) {
                sb.append("(=panel root)");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ the app side

    /** The open project's location, read straight from the application. */
    private static String expectedProjectLocation() {
        try {
            Object project = frame.getClass().getMethod("getProject").invoke(frame);
            if (project == null) {
                return null;
            }
            Object location = project.getClass().getMethod("getLocation").invoke(project);
            return location == null ? null : String.valueOf(location);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private static Object testCaseComponent() {
        try {
            Object testDesign = frame.getClass().getMethod("getTestDesign").invoke(frame);
            return testDesign == null ? null
                : testDesign.getClass().getMethod("getTestCaseComp").invoke(testDesign);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            say("[driver] could not reach TestCaseComponent: " + describe(ex));
            return null;
        }
    }

    /** The test case Studio was asked to record into, if it really created it on disk. */
    private static String createdTestCaseFile() {
        String location = expectedProjectLocation();
        if (location == null) {
            return null;
        }
        Path plan = Path.of(location, "TestPlan");
        try (var walk = Files.walk(plan, 4)) {
            return walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().startsWith(ADO_ID))
                .map(Path::toString)
                .findFirst()
                .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ windows

    /**
     * Every showing window, by class and title. A modal Swing dialog pumps the event dispatch
     * thread, so a responsive EDT proves nothing; this list is the evidence.
     */
    private static Set<String> windowCensus() {
        Set<String> showing = new LinkedHashSet<>();
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) {
                continue;
            }
            String title = w instanceof java.awt.Dialog d ? d.getTitle()
                : (w instanceof Frame f ? f.getTitle() : "");
            showing.add(w.getClass().getName() + (title == null || title.isBlank() ? "" : " \"" + title + "\""));
        }
        return showing;
    }

    /** Closes anything that opened on top of Studio, so one stuck dialog cannot end the walk. */
    private static void dismissDialogs() {
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (Window w : Window.getWindows()) {
                    if (w.isShowing() && w instanceof java.awt.Dialog dialog) {
                        say("[driver] dismissing " + dialog.getClass().getName());
                        dialog.setVisible(false);
                        dialog.dispose();
                    }
                }
            });
        } catch (Exception ex) {
            say("[driver] could not dismiss a dialog: " + describe(ex));
        }
    }

    private static List<String> newWindows() {
        List<String> fresh = new ArrayList<>(windowCensus());
        fresh.removeAll(windowBaseline);
        return fresh;
    }

    /**
     * Which of those windows is a <em>question put to the tester</em>.
     *
     * <p>Not every window is a prompt. Studio opens its own recorder console on a recording,
     * which is information, not a question — calling it a prompt would be the same category
     * error as calling a prompt harmless.
     */
    private static List<String> prompts(List<String> windows) {
        List<String> asking = new ArrayList<>();
        for (String w : windows) {
            String lower = w.toLowerCase();
            if (lower.contains("recordingtargetdialog") || lower.contains("joptionpane")
                || lower.contains("optionpane") || lower.contains("chooser")) {
                asking.add(w);
            }
        }
        return asking;
    }

    // ------------------------------------------------------------------ reading the screen

    private static String chipText(String stepTitle) {
        JLabel label = labelContaining(panel, stepTitle);
        return label == null ? null : label.getText();
    }

    private static String bannerText() {
        // The banner is the only HTML label at the top that carries a colour; it is found by
        // its content rather than by position, which no layout change can invalidate.
        for (JLabel label : allLabels(panel)) {
            String text = label.getText();
            if (label.isVisible() && label.isOpaque() && text != null && text.startsWith("<html>")
                && text.length() > 20) {
                return strip(text);
            }
        }
        return null;
    }

    private static String summaryText() {
        for (Component c : all(panel)) {
            if (c instanceof JTextArea area && area.getText() != null
                && area.getText().contains("SO GEHT ES JETZT WEITER")) {
                return area.getText();
            }
        }
        return null;
    }

    private static int indexOfAdoCase(JList<?> list, String adoId) {
        for (int i = 0; i < list.getModel().getSize(); i++) {
            Object element = list.getModel().getElementAt(i);
            if (element == null) {
                continue;
            }
            try {
                Object id = element.getClass().getMethod("adoId").invoke(element);
                if (adoId.equals(String.valueOf(id))) {
                    return i;
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                if (String.valueOf(element).contains(adoId)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void setClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text), null);
        } catch (RuntimeException ex) {
            say("[driver] could not empty the clipboard: " + describe(ex));
        }
    }

    /** The Kontonummer of one displayed row, so the clipboard can be checked against it. */
    private static String accountInRow(JTable table, int row) {
        try {
            return onEdt(() -> {
                for (int c = 0; c < table.getColumnCount(); c++) {
                    if (String.valueOf(table.getColumnName(c)).toLowerCase().contains("kontonummer")) {
                        Object value = table.getValueAt(row, c);
                        return value == null ? null : String.valueOf(value).trim();
                    }
                }
                return null;
            });
        } catch (Exception ex) {
            return null;
        }
    }

    private static String clipboard() {
        try {
            return String.valueOf(Toolkit.getDefaultToolkit().getSystemClipboard()
                .getData(DataFlavor.stringFlavor)).trim();
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ pressing things

    private static void click(Component root, String text) throws Exception {
        AbstractButton button = (AbstractButton) find(root, AbstractButton.class, text);
        if (button == null) {
            say("[driver] button not found: " + text);
            return;
        }
        say("[driver] pressing \"" + text + "\"");
        SwingUtilities.invokeAndWait(button::doClick);
    }

    private static String buttonText(Component root, String text) {
        AbstractButton button = (AbstractButton) find(root, AbstractButton.class, text);
        return button == null ? null : button.getText();
    }

    @SuppressWarnings("unused")
    private static void clickLabel(Component root, String text) throws Exception {
        JLabel label = labelContaining(root, text);
        if (label == null) {
            return;
        }
        SwingUtilities.invokeAndWait(() -> label.dispatchEvent(new MouseEvent(
            label, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)));
    }

    // ------------------------------------------------------------------ waiting

    private static int awaitRows(java.util.function.IntSupplier count, long timeoutMillis)
        throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int rows = 0;
        while (System.currentTimeMillis() < deadline) {
            rows = onEdt(count::getAsInt);
            if (rows > 0) {
                return rows;
            }
            Thread.sleep(500);
        }
        return rows;
    }

    private static String awaitPluginLog(String needle, long timeoutMillis)
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

    private static String awaitLabelContaining(Component root, String needle, long timeoutMillis)
        throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String text = labelTextContaining(root, needle);
            if (text != null) {
                return text;
            }
            Thread.sleep(1000);
        }
        return null;
    }

    private static Path awaitNewRun(Path results, int before, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            List<Path> dirs = runDirs(results);
            if (dirs.size() > before) {
                return dirs.get(dirs.size() - 1);
            }
            Thread.sleep(2000);
        }
        return null;
    }

    /** Directories holding a report file — the engine's own mark of a finished run. */
    private static List<Path> runDirs(Path results) {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(results)) {
            return found;
        }
        try (var walk = Files.walk(results, 6)) {
            walk.filter(Files::isDirectory).forEach(dir -> {
                try (var entries = Files.list(dir)) {
                    boolean report = entries.anyMatch(e -> {
                        String name = e.getFileName().toString().toLowerCase();
                        return Files.isRegularFile(e)
                            && (name.equals("data.js") || name.endsWith("-v2.html"));
                    });
                    if (report) {
                        found.add(dir);
                    }
                } catch (Exception ignored) {
                    // A directory that cannot be listed simply is not a finished run yet.
                }
            });
        } catch (Exception ignored) {
            // Results may not exist before the first run; that is the normal starting state.
        }
        found.sort(java.util.Comparator.comparing(Path::toString));
        return found;
    }

    private static int countRunDirs(Path results) {
        return runDirs(results).size();
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

    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(150);
        }
    }

    // ------------------------------------------------------------------ screenshots

    /**
     * The real Studio window, rendered.
     *
     * <p>{@code printAll} rather than a {@code Robot} screen grab of the window's bounds: the
     * first run of this driver finished a test, INGenious opened the report in the default
     * browser, and the "screenshot of Studio" was a photograph of Chrome. A render of the live
     * component hierarchy is the window's own contents and cannot be covered by anything.
     */
    private static void shoot(String name) {
        File out = new File(shots, String.format("%02d-%s.png", shotIndex++, name));
        try {
            if (!(frame instanceof Frame f) || f.getWidth() <= 0 || f.getHeight() <= 0) {
                javax.imageio.ImageIO.write(new java.awt.Robot().createScreenCapture(
                    new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())), "png", out);
            } else {
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                    f.getWidth(), f.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                SwingUtilities.invokeAndWait(() -> {
                    var g = image.createGraphics();
                    f.printAll(g);
                    g.dispose();
                });
                javax.imageio.ImageIO.write(image, "png", out);
            }
            say("[driver] screenshot: " + out.getAbsolutePath());
        } catch (Exception ex) {
            say("[driver] screenshot failed: " + describe(ex));
        }
    }

    // ------------------------------------------------------------------ logging

    /**
     * Captures what the plugin and Studio log. {@code LogManager} is JVM-wide and lives in the
     * platform loader, so a handler attached here sees records published from inside the
     * plugin's own class loader — which is how the watcher thread can speak for itself.
     */
    private static void captureLogs() {
        attach("de.ing.qa", pluginLog);
        attach("com.ing.ide", studioLog);
        attach("com.ing.engine", studioLog);
    }

    private static void attach(String name, List<String> sink) {
        Logger logger = Logger.getLogger(name);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            private final SimpleFormatter fmt = new SimpleFormatter();

            @Override
            public void publish(LogRecord record) {
                sink.add(record.getLevel() + " " + record.getLoggerName() + ": "
                    + fmt.formatMessage(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        // Held so the log manager cannot collect the logger we just configured.
        loggers.add(logger);
    }

    // ------------------------------------------------------------------ swing plumbing

    private static <T> T onEdt(java.util.concurrent.Callable<T> body) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ref.set(body.call());
            } catch (Exception ex) {
                failure.set(ex);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return ref.get();
    }

    private static void invokeOnEdt(Object target, String method, Class<?> type, Object arg)
        throws Exception {
        Method m = target.getClass().getMethod(method, type);
        SwingUtilities.invokeAndWait(() -> {
            try {
                m.invoke(target, arg);
            } catch (Exception ex) {
                say("[driver] " + method + " threw: " + describe(ex));
            }
        });
    }

    private static Component find(Component c, Class<?> type, String text) {
        if (type.isInstance(c)) {
            if (text == null) {
                return c;
            }
            if (c instanceof AbstractButton b && text.equals(b.getText())) {
                return c;
            }
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = find(child, type, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<Component> all(Component c) {
        List<Component> found = new ArrayList<>();
        collect(c, found);
        return found;
    }

    private static void collect(Component c, List<Component> into) {
        if (c == null) {
            return;
        }
        into.add(c);
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, into);
            }
        }
    }

    private static List<JLabel> allLabels(Component c) {
        List<JLabel> found = new ArrayList<>();
        for (Component component : all(c)) {
            if (component instanceof JLabel label) {
                found.add(label);
            }
        }
        return found;
    }

    private static JLabel labelContaining(Component c, String text) {
        for (JLabel label : allLabels(c)) {
            if (label.getText() != null && strip(label.getText()).contains(text)) {
                return label;
            }
        }
        return null;
    }

    private static String labelTextContaining(Component c, String text) {
        JLabel label = labelContaining(c, text);
        return label == null || !label.isVisible() ? null : strip(label.getText());
    }

    // ------------------------------------------------------------------ text

    private static String strip(String html) {
        return html == null ? null
            : html.replaceAll("<br>", " ").replaceAll("<[^>]+>", "").trim();
    }

    private static String quote(String text) {
        return text == null ? "(nothing)" : "\"" + text.replace("\n", " / ") + "\"";
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    private static String firstLines(String text, int count) {
        String[] lines = text.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(count, lines.length); i++) {
            sb.append(lines[i]).append(" / ");
        }
        return sb.toString();
    }

    private static String tail(List<String> lines, int count) {
        List<String> last = lines.subList(Math.max(0, lines.size() - count), lines.size());
        return String.join(" | ", last);
    }

    private static String readIfExists(Path path) {
        try {
            return Files.isRegularFile(path)
                ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String wrap(String text) {
        StringBuilder sb = new StringBuilder();
        int column = 0;
        for (String word : text.split(" ")) {
            if (column + word.length() > 88) {
                sb.append("\n      ");
                column = 0;
            }
            sb.append(word).append(' ');
            column += word.length() + 1;
        }
        return sb.toString().trim();
    }

    private static String describe(Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        return cause.getClass().getSimpleName()
            + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
