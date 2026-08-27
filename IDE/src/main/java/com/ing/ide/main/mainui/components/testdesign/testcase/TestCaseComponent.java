package com.ing.ide.main.mainui.components.testdesign.testcase;

import static com.ing.datalib.component.TestStep.HEADERS.Description;

import com.ing.datalib.component.ReusableRef;
import com.ing.datalib.component.Scenario;
import com.ing.datalib.component.TestCase;
import com.ing.datalib.component.TestStep;
import com.ing.datalib.component.TestStep.HEADERS;
import com.ing.datalib.component.utils.SaveListener;
import com.ing.datalib.or.web.WebOR;
import com.ing.datalib.or.web.WebORPage;
import com.ing.engine.constants.SystemDefaults;
import com.ing.engine.core.LiveRecordingHook;
import com.ing.engine.core.LiveRecordingService;
import com.ing.engine.core.RunManager;
import com.ing.engine.support.methodInf.MethodInfoManager;
import com.ing.ide.main.mainui.AppMainFrame;
import com.ing.ide.main.mainui.EngineConfig;
import com.ing.ide.main.mainui.components.testdesign.ReusableComponentDialog;
import com.ing.ide.main.mainui.components.testdesign.TestDesign;
import com.ing.ide.main.mainui.plugins.RecordingTargetPlugins;
import com.ing.ide.main.playwrightrecording.InspectorWindowController;
import com.ing.ide.main.playwrightrecording.LiveRecordingParser;
import com.ing.ide.main.playwrightrecording.PlaywrightRecordingParser;
import com.ing.ide.main.playwrightrecording.RecordingTargetDialog;
import com.ing.ide.main.utils.AppIcon;
import com.ing.ide.main.utils.ConsolePanel;
import com.ing.ide.main.utils.MenuScroller;
import com.ing.ide.main.utils.Utils;
import com.ing.ide.main.utils.keys.Keystroke;
import com.ing.ide.main.utils.table.TableColumnManager;
import com.ing.ide.main.utils.table.XTable;
import com.ing.ide.util.Canvas;
import com.ing.ide.util.Notification;
import com.ing.ide.util.Notification;
import com.ing.ide.util.WindowMover;
import com.ing.ingenious.api.contract.ui.RecordingTarget;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.KeyEventPostProcessor;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

/**
 * Main UI component for creating, editing, validating, and executing
 * test cases within the Test Design module.
 * <p>
 * {@code TestCaseComponent} manages the test case table, toolbars,
 * popup menus, auto‑suggest systems, validations, breakpoints, comment
 * toggling, and history tracking. It also integrates execution and debug
 * workflows, invokes Playwright recording, handles table actions such as
 * insert/delete/move/replicate steps, supports reusable creation, and
 * synchronizes navigation to objects and test data.
 * </p>
 *
 * <p>
 * The component orchestrates multiple sub‑dialogs (console, debugger,
 * recorder), manages runner threads, ensures save lifecycle handling,
 * and provides a unified environment for building and running automated
 * test cases.
 * </p>
 *
 * <h2>Aufnahme-Lebenszyklus — #293</h2>
 *
 * <p>
 * Heute endet eine Aufnahme so: {@link #record()} ist ein Umschalter. Läuft
 * bereits eine Aufnahme, ruft die erste Verzweigung
 * {@code stopPlaywrightRecording()} auf. Die beendete bis #293 den
 * Codegen-Prozess <em>und den ganzen Nachkommenbaum</em> mit
 * {@code destroyForcibly()} — inklusive „Google Chrome for Testing".
 * {@code finalizeLiveRecording()} speichert die YAML-Schritte
 * ({@code liveRecordingTarget.save()}) und setzt
 * {@code toolBar.setRecordingState(false)}. Der Browser ist weg.
 * </p>
 *
 * <p>
 * Die Sitzung überlebt das heute nur, wenn
 * {@code Settings/BrowserContexts/default.properties} bereits
 * {@code useStorageState=true} und einen existierenden
 * {@code storageStatePath} trägt: {@link #storageStateArgs(String)} hängt dann
 * {@code --load-storage} an den nächsten Codegen-Start. Geschrieben wird diese
 * Datei von der Aufnahme selbst <em>nicht</em>. Playwright 1.54.1
 * ({@code npx playwright codegen --help}, gemessen 2026-08-27) kann
 * {@code --save-storage} — und schreibt den Speicherzustand erst beim
 * <em>normalen</em> Ende des CLI-Prozesses. {@code destroyForcibly()} überspringt
 * genau das. Deshalb muss der Tester nach jedem Testfall neu anmelden: der
 * nächste Codegen startet ohne gespeicherte Cookies.
 * </p>
 *
 * <p>
 * <b>Warum nicht ein Browser über zwei Testfälle?</b> Codegen ist ein eigener
 * OS-Prozess ({@code java -cp "lib/*;." com.microsoft.playwright.CLI codegen
 * …}). Studio hängt an seinem stdout ({@link #runPlaywrightProcess}); das
 * CLI hat keinen Schalter „Ausgabe-Datei wechseln, Browser offen lassen".
 * Einen zweiten Codegen gegen denselben Browser zu hängen ginge nur über
 * {@code --user-data-dir} — und der belegt das Profil exklusiv, solange der
 * erste Prozess lebt. Gemessen (Playwright-Bibliothek, Chromium headless,
 * 2026-08-27): ein Kontext mit vorhandenem Speicherzustand startet in
 * {@code 312 ms} und trägt {@code localStorage.signedIn=yes} weiter; ein
 * frischer Kontext braucht {@code 1240 ms} und ist leer. Speichern selbst
 * kostet {@code 6 ms}. Die Sitzung wiederzuverwenden, indem sie auf die
 * Platte geschrieben und beim nächsten Start geladen wird, ist deshalb das
 * Nächste, das der CLI hergibt — und entfernt die Neu-Anmeldung.
 * </p>
 *
 * <p>
 * Ablauf nach #293: Aufnahme endet → YAML wird gespeichert → Codegen bekommt
 * ein sanftes {@code destroy()} und höchstens zwei Sekunden, damit
 * {@code --save-storage} den Speicherzustand schreiben kann → erst dann fällt
 * der Prozessbaum. Der nächste Testfall startet denselben Browser-Typ, aber
 * bereits angemeldet, weil {@code --load-storage} dieselbe Datei liest. Das
 * Fenster ist ein neues Fenster; die Sitzung ist dieselbe.
 * </p>
 */
public class TestCaseComponent extends JPanel implements ActionListener {
    private static final String PLAYWRIGHT_INSTALL_HINT =
        "mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args=\"install\"";

    private final TestDesign testDesign;

    private final TestCaseToolBar toolBar;

    private final ConsoleDialog consoleDialog;

    private final DebugDialog debugDialog;

    private final RecorderDialog recorderDialog;

    private final TestCasePopupMenu popupMenu;

    private final TestCaseValidator validator;

    private TestCaseAutoSuggest tcAutoSuggest;

    private final XTable testCaseTable;

    private SaveListener saveListener;

    private Thread runner;

    TableColumnManager tableColumnManager;

    private final TCHistory testCaseHistory;

    private final AppMainFrame sMainFrame;

    private CompletableFuture<Void> launchPlaywrightTask;

    private volatile Process activePlaywrightProcess;

    private volatile Thread liveRecordingWatcherThread;

    private volatile boolean recorderReadySignaled;

    private volatile boolean liveRecordingFinalized;

    private volatile boolean stopRequested;

    private volatile File liveRecordingOutputFile;

    private volatile LiveRecordingParser liveRecordingParser;

    private volatile TestCase liveRecordingTarget;

    private volatile String liveRecordingPageName;

    public static long INSTANCE_START_TIME;

    private boolean globalShortcutsRegistered = false;

    public TestCaseComponent(TestDesign testDesign, AppMainFrame sMainFrame) {
        this.testDesign = testDesign;
        this.sMainFrame = sMainFrame;
        toolBar = new TestCaseToolBar(this);
        popupMenu = new TestCasePopupMenu(this);
        testCaseTable = new XTable();
        tableColumnManager = new TableColumnManager(testCaseTable);
        consoleDialog = new ConsoleDialog();
        debugDialog = new DebugDialog();
        recorderDialog = new RecorderDialog(testDesign);
        testCaseHistory = new TCHistory();
        validator = new TestCaseValidator(testCaseTable);
        init();
        LiveRecordingService.setHook(new RecordFromHereHook());
    }

    private void init() {
        setLayout(new BorderLayout());
        add(toolBar, BorderLayout.NORTH);
        add(new JScrollPane(testCaseTable), BorderLayout.CENTER);
        testCaseTable.setComponentPopupMenu(popupMenu);
        initTableListeners();
        initRunner();
        initTestCaseAccelerators();
    }

    /**
     * Registers keyboard shortcuts for the TestCase panel.
     * <p>
     * Global shortcuts (Record, Run, Debug) use a keyboard focus manager key event
     * post-processor that fires regardless of focused child component.
     * Focus-dependent shortcuts use WHEN_ANCESTOR_OF_FOCUSED_COMPONENT so they only
     * fire when focus is inside this panel.
     */
    private void initTestCaseAccelerators() {
        registerGlobalShortcuts();

        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(Keystroke.SAVE, "Save");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(Keystroke.F5, "Reload");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(Keystroke.UP, "MoveUp");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(Keystroke.DOWN, "MoveDown");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(Keystroke.OPEN, "Open");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(Keystroke.FIND, "Search");

        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(Keystroke.COMMENT, "Comment");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(Keystroke.BREAKPOINT, "BreakPoint");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(Keystroke.INSERT_ROW, "Insert");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(Keystroke.ADD_ROW, "Add");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(Keystroke.ADD_ROWX, "Add");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(Keystroke.REMOVE_ROW, "Delete");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(Keystroke.REMOVE_ROWX, "Delete");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(Keystroke.REPLICATE_ROW, "Replicate");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(Keystroke.COPY_ABOVE, "Copy Above");
    }

    /**
     * Registers global shortcuts for Record, Run, and Debug.
     * These are intentionally not table-bound so they work even when focus is in
     * toolbar/search/other child components inside the main frame.
     */
    private void registerGlobalShortcuts() {
        if (globalShortcutsRegistered) {
            return;
        }
        globalShortcutsRegistered = true;

        KeyEventPostProcessor processor = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED) {
                return false;
            }
            if (!isMainFrameFocused()) {
                return false;
            }
            if (!sMainFrame.isTestDesign()) {
                return false;
            }

            int code = e.getKeyCode();
            int mods = e.getModifiersEx();

            boolean isCtrlF6 = code == KeyEvent.VK_F6 && (mods & KeyEvent.CTRL_DOWN_MASK) != 0;
            boolean isCmdF6 = code == KeyEvent.VK_F6 && (mods & KeyEvent.META_DOWN_MASK) != 0;

            if (isCtrlF6 || isCmdF6) {
                debug();
                return true;
            }

            if (code == KeyEvent.VK_F6 && mods == 0) {
                run();
                return true;
            }

            boolean isCtrlAltR =
                code == KeyEvent.VK_R &&
                (mods & KeyEvent.CTRL_DOWN_MASK) != 0 &&
                (mods & KeyEvent.ALT_DOWN_MASK) != 0;

            boolean isCmdAltR =
                code == KeyEvent.VK_R &&
                (mods & KeyEvent.META_DOWN_MASK) != 0 &&
                (mods & KeyEvent.ALT_DOWN_MASK) != 0;

            if (isCtrlAltR || isCmdAltR) {
                try {
                    record();
                } catch (IOException ex) {
                    Logger.getLogger(TestCaseComponent.class.getName()).log(Level.SEVERE, null, ex);
                }
                return true;
            }

            return false;
        };

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventPostProcessor(processor);
    }

    /** @return true if the main frame or one of its children currently has focus */
    private boolean isMainFrameFocused() {
        KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        java.awt.Component focusOwner = kfm.getFocusOwner();

        return (
            kfm.getFocusedWindow() == sMainFrame ||
            (focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, sMainFrame))
        );
    }

    public void loadTableModelForSelection(Object obj) {
        if (obj != null && obj instanceof TestCase) {
            // Save the current test case before switching to a new one
            TestCase currentTestCase = getCurrentTestCase();
            if (currentTestCase != null && !currentTestCase.isSaved()) {
                currentTestCase.save();
            }

            testCaseHistory.log();
            TestCase tc = (TestCase) obj;
            tc.setSaveListener(saveListener);
            getTestCaseTable().setModel(testDesign.getProject().getTableModelFor(tc));
            tcAutoSuggest.installForTestCase();
            validator.initValidations();
            changeSave(tc.isSaved());
            refreshTitle();

            // Check if migration occurred and show notification
            int migratedCount = tc.getMigratedReferencesCount();
            if (migratedCount > 0) {
                Notification.show(
                    String.format(
                        "Migrated %d object reference%s to explicit scope prefix in '%s'",
                        migratedCount,
                        migratedCount > 1 ? "es" : "",
                        tc.getName()
                    )
                );
            }
        }
    }

    public void resetTable() {
        getTestCaseTable().setModel(new DefaultTableModel());
        changeSave(true);
        toolBar.setPlaceHolderText("", null);
    }

    public void refreshTitle() {
        String scText = getCurrentTestCase().getScenario().getName();
        if (scText.length() > 20) {
            scText = scText.substring(0, 20) + "...";
        }
        String tcText = getCurrentTestCase().getName();
        if (tcText.length() > 20) {
            tcText = tcText.substring(0, 20) + "...";
        }
        String scopeLabel = getCurrentTestCase().getScenario().getScopeLabel();
        //        String toolTip
        //                = getCurrentTestCase().getScenario().getName()
        //                + " - "
        //                + getCurrentTestCase().getName();
        toolBar.setPlaceHolderText(scText + " - " + tcText + " (" + scopeLabel + ")", null);
    }

    public void load() {
        tcAutoSuggest = new TestCaseAutoSuggest(testDesign.getProject(), testCaseTable, testDesign);
        testCaseHistory.clear();
        loadBrowsers();
    }

    public void loadBrowsers() {
        java.util.List<String> names = new java.util.ArrayList<>(
            testDesign.getProject().getProjectSettings().getEmulators().getEmulatorNames()
        );
        for (String d : testDesign
            .getProject()
            .getProjectSettings()
            .getDevices()
            .getDeviceNames()) {
            if (!names.contains(d)) names.add(d);
        }
        toolBar.loadBrowsers(names);
    }

    private void initTableListeners() {
        testCaseTable.setActionFor(
            "Comment",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    toggleComment();
                }
            }
        );

        testCaseTable.setActionFor(
            "BreakPoint",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    toggleBreakPoint();
                }
            }
        );

        testCaseTable.setActionFor(
            "Insert",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    insertRow();
                }
            }
        );
        testCaseTable.setActionFor(
            "Add",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    addRow();
                }
            }
        );
        testCaseTable.setActionFor(
            "Delete",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    deleteSelectedRows();
                }
            }
        );

        testCaseTable.setActionFor(
            "Clear",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent ae) {
                    clearValues();
                }
            }
        );

        testCaseTable.setActionFor(
            "Replicate",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    replicateRow();
                }
            }
        );
        testCaseTable.setActionFor(
            "Save",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    save();
                }
            }
        );
        testCaseTable.setActionFor(
            "Reload",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    reload();
                }
            }
        );
        testCaseTable.setActionFor(
            "Open",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    openWithSystemEditor();
                }
            }
        );
        testCaseTable.setActionFor(
            "Search",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    toolBar.focusSearch();
                }
            }
        );

        testCaseTable.setActionFor(
            "Copy Above",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    copyAbove();
                }
            }
        );

        testCaseTable.setActionFor(
            "MoveUp",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    moveRowUp();
                }
            }
        );
        testCaseTable.setActionFor(
            "MoveDown",
            new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    moveRowDown();
                }
            }
        );

        saveListener =
            new SaveListener() {

                @Override
                public void onSave(Boolean bln) {
                    changeSave(bln);
                    refreshTreeValidation();
                }
            };

        testCaseTable.setTransferHandler(new TestCaseTableDnD());
        testCaseTable.addMouseListener(
            new MouseAdapter() {

                @Override
                public void mouseClicked(MouseEvent me) {
                    if (SwingUtilities.isLeftMouseButton(me) && me.isAltDown()) {
                        goToSelectedReusable();
                    } else if (SwingUtilities.isLeftMouseButton(me) && me.isShiftDown()) {
                        goToObject();
                    } else if (SwingUtilities.isLeftMouseButton(me)) {
                        addLastRow();
                    }
                }
            }
        );
    }

    private void initRunner() {
        runner =
            new Thread(
                () -> {
                    toolBar.setConsoleVisible(true);
                    toolBar.stopMode();
                    consoleDialog.start();
                    RunManager
                        .getGlobalSettings()
                        .setFor(getCurrentTestCase(), toolBar.getSelectedBrowser());
                    EngineConfig.runProject(testDesign.getProject());
                    debugDialog.setVisible(false);
                    toolBar.startMode();
                }
            );
    }

    private void changeSave(Boolean bln) {
        toolBar.setSave(!bln);
        popupMenu.setSave(!bln);
    }

    @Override
    public void actionPerformed(ActionEvent ae) {
        switch (ae.getActionCommand()) {
            case "Record":
                {
                    try {
                        record();
                    } catch (IOException ex) {
                        Logger
                            .getLogger(TestCaseComponent.class.getName())
                            .log(Level.SEVERE, null, ex);
                    }
                }
                break;
            case "Open with System Editor":
                openWithSystemEditor();
                break;
            case "Add Row":
                insertRowBelow();
                break;
            case "Delete Rows":
                deleteSelectedRows();
                break;
            case "Save":
                save();
                break;
            case "Reload":
                reload();
                break;
            case "Search":
                testCaseTable.searchFor(((JTextField) ae.getSource()).getText());
                break;
            case "GoToNextSearch":
                testCaseTable.goToNextSearch();
                break;
            case "GoToPrevoiusSearch":
                testCaseTable.goToPrevoiusSearch();
                break;
            case "Cut":
            case "Copy":
            case "Paste":
                ccp(ae.getActionCommand());
                break;
            case "Create Reusable":
                createReusable();
                break;
            case "Move Rows Up":
                moveRowUp();
                break;
            case "Move Rows Down":
                moveRowDown();
                break;
            case "Run":
                run();
                break;
            case "Debug":
                debug();
                break;
            case "StopRun":
                stopExecution();
                break;
            case "Toggle BreakPoint":
                toggleBreakPoint();
                break;
            case "Toggle Comment":
                toggleComment();
                break;
            case "Console":
                consoleDialog.showConsole();
                break;
            case "Go To Reusable":
                goToSelectedReusable();
                break;
            case "Go To Object":
                goToObject();
                break;
            case "Go To TestData":
                goToTestData();
                break;
            case "Toggle Validation":
                validator.toggleValidation();
                break;
            case "Parameterize":
                parameterizeSelectedSteps();
                break;
            case "Hard Assertion":
                setHardAssertion(true);
                break;
            case "Soft Assertion":
                setHardAssertion(false);
                break;
            case "Up One Level":
                loadTableModelForSelection(testCaseHistory.visit());
                break;
            default:
                throw new UnsupportedOperationException(ae.getActionCommand());
        }
    }

    public TestCase getCurrentTestCase() {
        if (getTestCaseTable().getModel() instanceof TestCase) {
            return (TestCase) getTestCaseTable().getModel();
        }
        return null;
    }

    public void record() throws IOException {
        if (toolBar.isRecording()) {
            stopPlaywrightRecording();
            return;
        }

        if (launchPlaywrightTask != null && !launchPlaywrightTask.isDone()) {
            logPlaywright("Playwright recorder is already running.");
            SwingUtilities.invokeLater(() -> toolBar.enableRecordButton());
            return;
        }

        // A plugin that already knows what the user is working on answers here, and the target
        // chooser never opens. No plugin, or no answer, and the dialog behaves exactly as before.
        RecordingTarget pluginTarget = RecordingTargetPlugins.currentTarget();

        TestCase target;
        if (pluginTarget != null) {
            // A plugin naming a target MEANS it. The name is an identity — a test case id the
            // rest of the tester loop is filed under — so re-recording it has to land in it,
            // not beside it. Look for the target before making one.
            //
            // createOrResolveTarget cannot answer that, because it is shared with the dialog,
            // where the opposite is true: a person who types the same name twice must not
            // overwrite their own first recording. That is a real bug and it is fixed by
            // uniquifying there. findExistingTarget is the other half of the pair, and it is
            // the half this path needs — and the half upstream left with no callers.
            target =
                findExistingTarget(
                    pluginTarget.getScenarioName(),
                    pluginTarget.getTestCaseName(),
                    pluginTarget.isReusableScenario()
                );
            if (target == null) {
                target =
                    createOrResolveTarget(
                        pluginTarget.getScenarioName(),
                        pluginTarget.getTestCaseName(),
                        pluginTarget.isReusableScenario()
                    );
            }
        } else {
            RecordingTargetDialog.Selection selection = RecordingTargetDialog.showDialog(
                this,
                testDesign.getProject(),
                getCurrentTestCase()
            );
            if (selection == null) {
                SwingUtilities.invokeLater(() -> toolBar.enableRecordButton());
                return;
            }
            target = resolveRecordingTarget(selection);
        }

        if (target == null) {
            JOptionPane.showMessageDialog(
                this,
                "Unable to resolve recording target.",
                "Playwright Recorder",
                JOptionPane.WARNING_MESSAGE
            );
            SwingUtilities.invokeLater(() -> toolBar.enableRecordButton());
            return;
        }

        loadTableModelForSelection(target);
        liveRecordingTarget = target;
        liveRecordingFinalized = false;
        stopRequested = false;
        recorderReadySignaled = false;
        INSTANCE_START_TIME = System.currentTimeMillis();

        int firstInsertIndex = firstEmptyRowIndex(target);
        PlaywrightRecordingParser baseParser = new PlaywrightRecordingParser(sMainFrame);
        WebORPage objectPage = baseParser.createLiveRecordingPage(target.getName());
        liveRecordingPageName = baseParser.getLiveRecordingPageName();
        String reference = "[Project] " + liveRecordingPageName;
        liveRecordingParser =
            new LiveRecordingParser(baseParser, target, firstInsertIndex, reference, objectPage);

        liveRecordingOutputFile = prepareLiveRecordingOutputFile();
        final String startUrl = resolveRecordingStartUrl(pluginTarget);

        toolBar.setConsoleVisible(true);
        consoleDialog.clear();
        consoleDialog.showConsole();
        logPlaywright("🎬 Playwright Recording is being initiated...");
        // The user was not asked where this goes, so the console has to say it.
        if (pluginTarget != null) {
            logPlaywright(
                "🎯 Recording into " + target.getScenario().getName() + " / " + target.getName()
            );
        }
        if (startUrl != null) {
            logPlaywright("🌐 Opening " + startUrl);
        }
        logPlaywright(
            "============================== Playwright Log Started =============================="
        );

        startLiveRecordingWatcher();

        launchPlaywrightTask =
            CompletableFuture.runAsync(
                () -> {
                    try {
                        launchPlaywright(liveRecordingOutputFile, startUrl);
                    } catch (IOException ex) {
                        logPlaywrightError("Error launching Playwright: " + ex.getMessage());
                        Logger
                            .getLogger(TestCaseComponent.class.getName())
                            .log(Level.SEVERE, "Error launching Playwright", ex);
                    } finally {
                        finalizeLiveRecording();
                    }
                }
            );
    }

    /**
     * Live recording hook used by the Engine's {@code RecordFromHere} action. When a running test
     * case reaches a {@code RecordFromHere} step, the Engine enables the Playwright recorder on the
     * live browser context and notifies this hook so the recorded steps are appended into the
     * editor in real time (highlighted green) from the current step onwards.
     */
    private class RecordFromHereHook implements LiveRecordingHook {

        @Override
        public String onRecordingStarted(TestCase engineTestCase, int insertAfterStepIndex) {
            final TestCase target = resolveHookTarget(engineTestCase);
            if (target == null) {
                Logger
                    .getLogger(TestCaseComponent.class.getName())
                    .log(Level.WARNING, "RecordFromHere: unable to resolve editable test case.");
                return null;
            }

            final int firstInsertIndex = Math.max(insertAfterStepIndex + 1, 0);
            final java.util.concurrent.atomic.AtomicReference<File> fileRef = new java.util.concurrent.atomic.AtomicReference<>();

            Runnable setup = () -> {
                try {
                    loadTableModelForSelection(target);
                    liveRecordingTarget = target;
                    liveRecordingFinalized = false;
                    stopRequested = false;
                    recorderReadySignaled = false;
                    INSTANCE_START_TIME = System.currentTimeMillis();

                    PlaywrightRecordingParser baseParser = new PlaywrightRecordingParser(
                        sMainFrame
                    );
                    WebORPage objectPage = resolveExistingProjectPage(target);
                    boolean preserveExistingObjects = false;
                    if (objectPage != null) {
                        liveRecordingPageName = objectPage.getName();
                        preserveExistingObjects = true;
                    } else {
                        objectPage = baseParser.createLiveRecordingPage(target.getName());
                        liveRecordingPageName = baseParser.getLiveRecordingPageName();
                    }
                    String reference = "[Project] " + liveRecordingPageName;
                    liveRecordingParser =
                        new LiveRecordingParser(
                            baseParser,
                            target,
                            firstInsertIndex,
                            reference,
                            objectPage,
                            preserveExistingObjects
                        );

                    liveRecordingOutputFile = prepareLiveRecordingOutputFile();

                    toolBar.setConsoleVisible(true);
                    consoleDialog.clear();
                    consoleDialog.showConsole();
                    logPlaywright("🎬 Recording from current step...");
                    startLiveRecordingWatcher();
                    fileRef.set(liveRecordingOutputFile);
                } catch (Exception ex) {
                    Logger
                        .getLogger(TestCaseComponent.class.getName())
                        .log(Level.SEVERE, "Unable to start live recording for RecordFromHere", ex);
                }
            };

            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    setup.run();
                } else {
                    SwingUtilities.invokeAndWait(setup);
                }
            } catch (Exception ex) {
                Logger
                    .getLogger(TestCaseComponent.class.getName())
                    .log(Level.WARNING, "RecordFromHere setup failed", ex);
                return null;
            }

            File file = fileRef.get();
            return file == null ? null : file.getAbsolutePath();
        }

        @Override
        public void onRecordingReady() {
            if (!recorderReadySignaled) {
                onRecorderReady();
            }
        }

        @Override
        public void onRecordingStopped() {
            finalizeLiveRecording();
        }
    }

    /**
     * Maps the Engine's (copied) running test case back to the editable project test case so
     * recorded steps and saves apply to the persistent model shown in the editor.
     */
    private TestCase resolveHookTarget(TestCase engineTestCase) {
        if (engineTestCase == null) {
            return null;
        }

        Scenario engineScenario = engineTestCase.getScenario();
        String scenarioName = engineScenario != null ? engineScenario.getName() : null;
        String testCaseName = engineTestCase.getName();
        if (scenarioName == null || testCaseName == null) {
            return null;
        }

        boolean reusable = engineScenario.isReusableScenario();
        Scenario scenario = reusable
            ? testDesign.getProject().getReusableScenarioByName(scenarioName)
            : testDesign.getProject().getScenarioByName(scenarioName);
        if (scenario == null) {
            return null;
        }
        return scenario.getTestCaseByName(testCaseName);
    }

    public Process startPlaywrightProcess(String processArgs) {
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            String classpath;
            if (osName.contains("win")) {
                String userHome = System.getProperty("user.home");
                String printDepsDir = userHome + "\\AppData\\Local\\ms-playwright\\winldd-1007";
                String printDepsPath = printDepsDir + "\\PrintDeps.exe";
                File printDeps = new File(printDepsPath);
                if (!printDeps.exists()) {
                    new File(printDepsDir).mkdirs();

                    try (
                        InputStream in = getClass()
                            .getResourceAsStream("/Engine/winldd-1007/PrintDeps.exe")
                    ) {
                        if (in == null) {
                            throw new FileNotFoundException(
                                "PrintDeps.exe not found in resources!"
                            );
                        }
                        Files.copy(in, Path.of(printDepsPath), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                classpath = "lib/*;."; // Windows
            } else {
                classpath = "lib/*:."; // Mac
            }

            String javaCommand = String.format(
                "java -cp \"%s\" com.microsoft.playwright.CLI %s",
                classpath,
                processArgs
            );

            String[] command = osName.contains("windows")
                ? new String[] { "cmd", "/c", javaCommand }
                : new String[] { "bash", "-l", "-c", javaCommand };

            return new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (Exception ex) {
            logPlaywrightError("Error starting Playwright process: " + ex.getMessage());
        }

        return null;
    }

    //    public void initialization(PlaywrightSpinner playwrightSpinnerGUI){
    //        try{
    //            String[] command = new String[0];
    //            String osName = System.getProperty("os.name").toLowerCase();
    //            if (osName.contains("windows")) {
    //                // Windows command
    //
    //                command = new String[]{"cmd", "/c", "mvn initialize -f engine/pom.xml"};
    //            } else if (osName.contains("mac")) {
    //                // Mac command
    //                command = new String[]{"bash", "-l", "-c", "mvn initialize -f engine/pom.xml"};
    //            }
    //           Runtime.getRuntime().exec(command);
    //       }catch (Exception ex){
    //         System.out.println(ex.getMessage());
    //         //playwrightSpinnerGUI.appendLog(ex.getMessage());
    //       }
    //    }

    public void launchPlaywright(File outputFile) throws IOException {
        launchPlaywright(outputFile, null);
    }

    /**
     * Starts the Playwright recorder, optionally on a given page.
     *
     * @param outputFile file codegen writes the recorded script to
     * @param startUrl page to open, or {@code null} for codegen's blank page
     * @throws IOException when the recorder process cannot be started
     */
    public void launchPlaywright(File outputFile, String startUrl) throws IOException {
        String escapedPath = outputFile
            .getAbsolutePath()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
        String processArgs = "codegen --target java --output \"" + escapedPath + "\"";
        String projectLocation = sMainFrame.getProject().getLocation();
        String storageStateArgs = storageStateArgs(projectLocation);
        // Said out loud on every launch: a recorder that silently did or did not carry the
        // sign-in over is the exact ambiguity this setting exists to remove.
        logPlaywright(
            storageStateArgs.isEmpty()
                ? "No saved browser session configured. The recorder starts signed out."
                : "Saved browser session reused:" + storageStateArgs
        );
        // --save-storage first so a later --load-storage of the same file is still an
        // option, and both sit before the positional start URL. Playwright writes the
        // file only when the CLI process ends normally — see stopPlaywrightRecording.
        processArgs += saveStorageArgs(projectLocation);
        processArgs += storageStateArgs;
        if (startUrl != null) {
            // Quoted: the command is handed to cmd/bash as one string, and an unquoted query
            // string would be cut at its first '&'. Validation upstream has already ruled out
            // anything that could break out of these quotes.
            processArgs += " \"" + startUrl + "\"";
        }
        runPlaywrightProcess(processArgs);
        logPlaywright(
            "============================== Playwright Log Ended =============================="
        );
    }

    /**
     * The codegen option that starts the recorder from the browser session the project has saved.
     *
     * <p>
     * Recording used to always begin at the application's login page while a run of the same
     * application began signed in, because {@code Settings/BrowserContexts/default.properties} was
     * only ever read by the engine. Reading the same two keys here is what makes the one switch
     * govern both.
     * </p>
     *
     * <p>
     * The file is read on every launch instead of through the project's settings object, because
     * the browser-context panel writes it while Studio is running and a value taken at project-open
     * time would be the previous one.
     * </p>
     *
     * @param projectLocation location of the open project
     * @return {@code --load-storage "<path>"} including its leading space, or {@code ""} when the
     *         project has no usable saved session to <em>load</em>. A missing file is not a
     *         reason to skip {@code --save-storage}: the first recording of a session has to
     *         create that file, or the second one cannot load it.
     */
    static String storageStateArgs(String projectLocation) {
        if (projectLocation == null) {
            return "";
        }
        File contextFile = new File(
            projectLocation +
            File.separator +
            "Settings" +
            File.separator +
            "BrowserContexts" +
            File.separator +
            "default.properties"
        );
        try (InputStream contextStream = new FileInputStream(contextFile)) {
            Properties contextDetails = new Properties();
            contextDetails.load(contextStream);
            if (!Boolean.parseBoolean(contextDetails.getProperty("useStorageState"))) {
                return "";
            }
            String storageStatePath = contextDetails.getProperty("storageStatePath", "").trim();
            if (storageStatePath.isEmpty()) {
                return "";
            }
            // Codegen aborts on a state file it cannot open, which would mean no recorder at all;
            // landing on the login page is the better of the two failures. The engine skips a
            // missing file for the same reason, so both stay silent about it in the same way.
            // --save-storage is still passed (see saveStorageArgs) so the first recording can
            // create the file the next one loads.
            if (!new File(storageStatePath).exists()) {
                return "";
            }
            // Escaped like --output above: this ends up inside double quotes in a single command
            // string, so a Windows path's backslashes have to survive the shell.
            return (
                " --load-storage \"" +
                storageStatePath.replace("\\", "\\\\").replace("\"", "\\\"") +
                "\""
            );
        } catch (IOException | RuntimeException ex) {
            // Unreadable settings must not cost the tester the recording itself.
            System.err.println(
                "Could not read " + contextFile + ", recording without a saved session: " + ex
            );
            return "";
        }
    }

    /**
     * The codegen option that writes the signed-in session when the recorder ends.
     *
     * <p>
     * Complementary to {@link #storageStateArgs}: that one loads what is already there, this
     * one writes what the tester just signed in as. Same two keys, same file, read fresh
     * every launch. {@code useStorageState=false} is a deliberate "do not carry this" and
     * wins — a leftover {@code --save-storage} would quietly undo the checkbox.
     * </p>
     *
     * <p>
     * A missing file is not a reason to skip the flag. Playwright creates it on a normal
     * CLI exit; that is how the first recording of a morning produces the session the
     * second one reuses. An empty {@code storageStatePath} is.
     * </p>
     *
     * @param projectLocation location of the open project
     * @return {@code --save-storage "<path>"} including its leading space, or {@code ""}
     */
    static String saveStorageArgs(String projectLocation) {
        String path = storageStatePath(projectLocation, false);
        if (path.isEmpty()) {
            return "";
        }
        return (" --save-storage \"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    }

    /**
     * The project's configured storage-state file, or {@code ""} when the setting is off
     * or unusable.
     *
     * @param projectLocation location of the open project
     * @param mustExist {@code true} when the file has to be there already (load); {@code false}
     *        when codegen is allowed to create it (save)
     */
    static String storageStatePath(String projectLocation, boolean mustExist) {
        if (projectLocation == null) {
            return "";
        }
        File contextFile = new File(
            projectLocation +
            File.separator +
            "Settings" +
            File.separator +
            "BrowserContexts" +
            File.separator +
            "default.properties"
        );
        try (InputStream contextStream = new FileInputStream(contextFile)) {
            Properties contextDetails = new Properties();
            contextDetails.load(contextStream);
            if (!Boolean.parseBoolean(contextDetails.getProperty("useStorageState"))) {
                return "";
            }
            String storageStatePath = contextDetails.getProperty("storageStatePath", "").trim();
            if (storageStatePath.isEmpty()) {
                return "";
            }
            if (mustExist && !new File(storageStatePath).exists()) {
                return "";
            }
            return storageStatePath;
        } catch (IOException | RuntimeException ex) {
            return "";
        }
    }

    private Process runPlaywrightProcess(String processArgs) throws IOException {
        Process process = startPlaywrightProcess(processArgs);
        if (process == null) {
            return null;
        }

        activePlaywrightProcess = process;

        boolean codegenCommand = processArgs.trim().startsWith("codegen");

        try (
            BufferedReader processOutput = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            )
        ) {
            String line;
            while ((line = processOutput.readLine()) != null) {
                logPlaywright(line);
                if (codegenCommand && !recorderReadySignaled) {
                    onRecorderReady();
                }
                if (codegenCommand && line.contains(PLAYWRIGHT_INSTALL_HINT)) {
                    waitForProcess(process, "Playwright codegen");
                    logPlaywright("Playwright browser binaries are missing. Starting install...");
                    Process installProcess = runPlaywrightProcess("install");
                    waitForProcess(installProcess, "Playwright install");
                    logPlaywright("Playwright install completed. Restarting recorder...");
                    return runPlaywrightProcess(processArgs);
                }
            }
        }

        return process;
    }

    private void waitForProcess(Process process, String processName) {
        if (process == null) {
            return;
        }

        try {
            process.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logPlaywrightError(processName + " wait interrupted: " + ex.getMessage());
        }
    }

    private void logPlaywright(String message) {
        System.out.println(message);
        consoleDialog.appendLine(message);
    }

    private void logPlaywrightError(String message) {
        System.err.println(message);
        consoleDialog.appendErrorLine(message);
    }

    private void onRecorderReady() {
        recorderReadySignaled = true;
        SwingUtilities.invokeLater(
            () -> {
                consoleDialog.setVisible(false);
                toolBar.setRecordingState(true);
                toolBar.enableRecordButton();
            }
        );
        CompletableFuture.runAsync(() -> InspectorWindowController.minimizeInspectorBestEffort());
    }

    private void stopPlaywrightRecording() {
        stopRequested = true;
        Process process = activePlaywrightProcess;
        if (process != null && process.isAlive()) {
            // Soft first: --save-storage only writes on a normal CLI exit.
            // destroyForcibly() used to skip that, which is why the next test case
            // always opened signed out. Two seconds is enough for a storage-state
            // write (measured 6 ms in-process) and short enough that a hung codegen
            // does not pin the toolbar.
            endPlaywrightProcess(process, 2_000);
        }
        finalizeLiveRecording();
    }

    /**
     * Ends the Playwright process tree, preferring a normal exit so {@code --save-storage}
     * can write the signed-in session.
     *
     * <p>The codegen CLI spawns "Google Chrome for Testing" as a child. Destroying only
     * the parent used to leave that window open — that is why the old path collected
     * descendants and killed them all. The same collection still happens, but only after
     * {@code destroy()} has had {@code waitMillis} to let the CLI flush. A process that
     * is still alive then is treated as hung and force-killed, same as before.
     */
    static boolean endPlaywrightProcess(Process process, long waitMillis) {
        if (process == null) {
            return false;
        }
        if (!process.isAlive()) {
            return true;
        }
        try {
            List<ProcessHandle> descendants = process
                .descendants()
                .collect(java.util.stream.Collectors.toList());
            process.destroy();
            boolean exited = process.waitFor(
                waitMillis,
                java.util.concurrent.TimeUnit.MILLISECONDS
            );
            if (exited) {
                return true;
            }
            process.destroyForcibly();
            for (ProcessHandle handle : descendants) {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            }
            process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            return !process.isAlive();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process);
            return false;
        } catch (Exception ex) {
            Logger
                .getLogger(TestCaseComponent.class.getName())
                .log(Level.WARNING, "Unable to terminate Playwright browser process tree", ex);
            return false;
        }
    }

    /**
     * Forcibly terminates the Playwright process and all of its descendants. Kept as the
     * last resort when a soft stop is interrupted: a leftover Chrome window is worse than
     * losing one storage-state write.
     */
    static void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }
        try {
            List<ProcessHandle> descendants = process
                .descendants()
                .collect(java.util.stream.Collectors.toList());
            process.destroyForcibly();
            for (ProcessHandle handle : descendants) {
                handle.destroyForcibly();
            }
        } catch (Exception ex) {
            Logger
                .getLogger(TestCaseComponent.class.getName())
                .log(Level.WARNING, "Unable to terminate Playwright browser process tree", ex);
        }
    }

    private void finalizeLiveRecording() {
        synchronized (this) {
            if (liveRecordingFinalized) {
                return;
            }
            liveRecordingFinalized = true;
        }

        // Parse any remaining recorder output before shutting down watcher/parser state.
        flushPendingLiveRecordingLines();

        stopLiveRecordingWatcher();

        if (liveRecordingParser != null && liveRecordingTarget != null) {
            try {
                Runnable finalizeTask = () -> {
                    int updates = liveRecordingParser.finalizeDeferredInputs();
                    liveRecordingTarget.save();
                    testCaseTable.revalidate();
                    testCaseTable.repaint();
                    if (updates > 0) {
                        logPlaywright("Updated " + updates + " deferred text input step(s).");
                    }
                };

                if (SwingUtilities.isEventDispatchThread()) {
                    finalizeTask.run();
                } else {
                    SwingUtilities.invokeAndWait(finalizeTask);
                }
            } catch (Exception ex) {
                Logger
                    .getLogger(TestCaseComponent.class.getName())
                    .log(Level.WARNING, "Unable to finalize live recording", ex);
            }
        }

        activePlaywrightProcess = null;
        liveRecordingParser = null;
        liveRecordingTarget = null;
        liveRecordingOutputFile = null;
        recorderReadySignaled = false;

        SwingUtilities.invokeLater(
            () -> {
                toolBar.setRecordingState(false);
                toolBar.enableRecordButton();
            }
        );
    }

    private void startLiveRecordingWatcher() {
        if (liveRecordingOutputFile == null || liveRecordingParser == null) {
            return;
        }

        liveRecordingWatcherThread =
            new Thread(
                () -> {
                    while (!liveRecordingFinalized && !Thread.currentThread().isInterrupted()) {
                        try {
                            if (liveRecordingOutputFile.exists()) {
                                List<String> lines = Files.readAllLines(
                                    liveRecordingOutputFile.toPath()
                                );
                                if (!recorderReadySignaled && lines.size() > 0) {
                                    onRecorderReady();
                                }
                                syncLiveRecording(lines);
                            }
                            Thread.sleep(300);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception ex) {
                            Logger
                                .getLogger(TestCaseComponent.class.getName())
                                .log(Level.WARNING, "Live recording watcher iteration failed", ex);
                        }
                    }
                },
                "playwright-live-recording-watcher"
            );
        liveRecordingWatcherThread.setDaemon(true);
        liveRecordingWatcherThread.start();
    }

    private void stopLiveRecordingWatcher() {
        Thread watcher = liveRecordingWatcherThread;
        if (watcher != null) {
            watcher.interrupt();
        }
        liveRecordingWatcherThread = null;
    }

    private void flushPendingLiveRecordingLines() {
        if (liveRecordingOutputFile == null || !liveRecordingOutputFile.exists()) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(liveRecordingOutputFile.toPath());
            syncLiveRecording(lines);
        } catch (Exception ex) {
            Logger
                .getLogger(TestCaseComponent.class.getName())
                .log(Level.WARNING, "Unable to flush pending live recording lines", ex);
        }
    }

    private void syncLiveRecording(List<String> lines) {
        if (liveRecordingParser == null || lines == null) {
            return;
        }

        Runnable parserTask = () -> {
            if (liveRecordingParser != null && liveRecordingTarget != null) {
                boolean changed = liveRecordingParser.syncFromLines(lines, this::logPlaywright);
                if (changed) {
                    liveRecordingTarget.save();
                    testCaseTable.revalidate();
                    testCaseTable.repaint();
                    testDesign.getObjectRepo().refreshWebOR(liveRecordingPageName);
                }
            }
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                parserTask.run();
            } else {
                SwingUtilities.invokeAndWait(parserTask);
            }
        } catch (Exception ex) {
            Logger
                .getLogger(TestCaseComponent.class.getName())
                .log(Level.WARNING, "Unable to sync live recording", ex);
        }
    }

    /**
     * Decides which page the recorder opens: what the plugin asked for, else what the project
     * configured, else nothing — which is codegen's blank page, i.e. the behaviour every
     * existing project already has.
     *
     * @param pluginTarget the plugin's target, or {@code null} when the user chose by hand
     * @return a usable URL, or {@code null} to start on a blank page
     */
    private String resolveRecordingStartUrl(RecordingTarget pluginTarget) {
        String fromPlugin = pluginTarget == null ? null : pluginTarget.getStartUrl();
        if (fromPlugin != null) {
            if (isUsableStartUrl(fromPlugin)) {
                return fromPlugin.trim();
            }
            logPlaywright("Ignoring unusable recording URL from plugin: " + fromPlugin);
        }

        String fromProject = "";
        try {
            fromProject =
                testDesign.getProject().getProjectSettings().getRecorderSettings().getStartUrl();
        } catch (RuntimeException ex) {
            Logger
                .getLogger(TestCaseComponent.class.getName())
                .log(Level.WARNING, "Unable to read the project's recorder settings", ex);
        }
        if (!fromProject.isEmpty()) {
            if (isUsableStartUrl(fromProject)) {
                return fromProject.trim();
            }
            logPlaywright("Ignoring unusable recording URL in project settings: " + fromProject);
        }

        return null;
    }

    /**
     * An absolute http(s) address and nothing else.
     *
     * <p>The recorder command is assembled as one string and handed to a shell, so a value that
     * is not a plain URL must not reach it. Rejecting here means a mistyped setting starts a
     * blank recording with a note in the console, rather than a broken or surprising command.
     *
     * @param value the configured value
     * @return {@code true} when it is safe to pass to the recorder
     */
    private boolean isUsableStartUrl(String value) {
        if (value == null) {
            return false;
        }
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.indexOf('"') >= 0 || candidate.indexOf('%') >= 0) {
            // '%' is legal in a URL but is what a Windows shell expands, so a percent-encoded
            // address is refused rather than silently mangled on the way to the recorder.
            return false;
        }
        try {
            java.net.URI uri = new java.net.URI(candidate);
            String scheme = uri.getScheme();
            return (
                uri.isAbsolute() &&
                uri.getHost() != null &&
                ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
            );
        } catch (java.net.URISyntaxException ex) {
            return false;
        }
    }

    private TestCase resolveRecordingTarget(RecordingTargetDialog.Selection selection) {
        if (selection == null) {
            return null;
        }

        switch (selection.getMode()) {
            case NEW_TEST_SCENARIO:
                return createOrResolveTarget(
                    selection.getScenarioName(),
                    selection.getTestCaseName(),
                    false
                );
            case NEW_REUSABLE_SCENARIO:
                return createOrResolveTarget(
                    selection.getScenarioName(),
                    selection.getTestCaseName(),
                    true
                );
            default:
                return null;
        }
    }

    private TestCase createOrResolveTarget(
        String scenarioName,
        String testCaseName,
        boolean reusable
    ) {
        Scenario scenario = findScenarioByName(scenarioName, reusable);
        if (scenario == null) {
            scenario =
                reusable
                    ? testDesign.getProject().addReusableScenario(scenarioName)
                    : testDesign.getProject().addScenario(scenarioName);
        }
        if (scenario == null) {
            return null;
        }

        String resolvedName = resolveUniqueTestCaseName(scenario, testCaseName, reusable);
        TestCase testCase = scenario.addTestCase(resolvedName);

        registerTargetInTree(testCase, reusable);
        return testCase;
    }

    private String resolveUniqueTestCaseName(
        Scenario scenario,
        String requestedName,
        boolean reusable
    ) {
        String baseName = (requestedName == null || requestedName.trim().isEmpty())
            ? (reusable ? "LiveRecordingReusableTestCase" : "LiveRecordingTestCase")
            : requestedName.trim();
        String candidate = baseName;
        int counter = 1;
        while (hasTestCaseNameIgnoreCase(scenario, candidate)) {
            candidate = baseName + "_" + counter;
            counter++;
        }
        return candidate;
    }

    private boolean hasTestCaseNameIgnoreCase(Scenario scenario, String name) {
        for (TestCase existing : scenario.getTestCases()) {
            if (existing.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Registers a newly created/resolved recording target in the project tree so it becomes
     * visible immediately without requiring a full project reload.
     */
    private void registerTargetInTree(TestCase testCase, boolean reusable) {
        if (testCase == null) {
            return;
        }
        SwingUtilities.invokeLater(
            () -> {
                try {
                    if (reusable) {
                        testDesign.getReusableTree().getTreeModel().addTestCase(testCase);
                    } else {
                        testDesign.getProjectTree().getTreeModel().addTestCase(testCase);
                    }
                } catch (Exception ex) {
                    Logger
                        .getLogger(TestCaseComponent.class.getName())
                        .log(Level.WARNING, "Unable to register recording target in tree", ex);
                }
            }
        );
    }

    private TestCase findExistingTarget(
        String scenarioName,
        String testCaseName,
        boolean reusable
    ) {
        Scenario scenario = findScenarioByName(scenarioName, reusable);
        return scenario == null ? null : scenario.getTestCaseByName(testCaseName);
    }

    private Scenario findScenarioByName(String scenarioName, boolean reusable) {
        List<Scenario> scenarios = reusable
            ? testDesign.getProject().getReusableScenarios()
            : testDesign.getProject().getScenarios();

        for (Scenario scenario : scenarios) {
            if (scenario.getName().equalsIgnoreCase(scenarioName)) {
                return scenario;
            }
        }
        return null;
    }

    private int firstEmptyRowIndex(TestCase testCase) {
        if (testCase == null) {
            return 0;
        }

        List<TestStep> steps = testCase.getTestSteps();
        for (int i = 0; i < steps.size(); i++) {
            TestStep step = steps.get(i);
            if (isStepBlank(step)) {
                return i;
            }
        }
        return steps.size();
    }

    private boolean isStepBlank(TestStep step) {
        return (
            step == null ||
            (
                isBlank(step.getObject()) &&
                isBlank(step.getAction()) &&
                isBlank(step.getInput()) &&
                isBlank(step.getCondition()) &&
                isBlank(step.getReference()) &&
                isBlank(step.getDescription())
            )
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private WebORPage resolveExistingProjectPage(TestCase target) {
        if (
            target == null ||
            sMainFrame == null ||
            sMainFrame.getProject() == null ||
            sMainFrame.getProject().getObjectRepository() == null
        ) {
            return null;
        }

        WebOR webOR = sMainFrame.getProject().getObjectRepository().getWebOR();
        if (webOR == null) {
            return null;
        }

        for (TestStep step : target.getTestSteps()) {
            String pageName = extractProjectPageName(step == null ? null : step.getReference());
            if (pageName == null) {
                continue;
            }
            WebORPage page = webOR.getPageByName(pageName);
            if (page != null) {
                return page;
            }
        }
        return null;
    }

    private String extractProjectPageName(String reference) {
        if (reference == null) {
            return null;
        }
        String trimmed = reference.trim();
        String prefix = "[Project]";
        if (!trimmed.startsWith(prefix)) {
            return null;
        }
        String pageName = trimmed.substring(prefix.length()).trim();
        return pageName.isEmpty() ? null : pageName;
    }

    private File prepareLiveRecordingOutputFile() throws IOException {
        File recordingDir = new File(
            sMainFrame.getProject().getLocation() + File.separator + "Recording"
        );
        if (!recordingDir.exists()) {
            recordingDir.mkdirs();
        }
        File output = new File(recordingDir, "live_recording_" + INSTANCE_START_TIME + ".java");
        if (!output.exists()) {
            output.createNewFile();
        }
        return output;
    }

    private void stopCellEditing() {
        if (testCaseTable.getCellEditor() != null) {
            testCaseTable.getCellEditor().stopCellEditing();
        }
    }

    private void insertRow() {
        stopCellEditing();
        if (testCaseTable.getSelectedRow() != -1) {
            getCurrentTestCase().addNewStepAt(testCaseTable.getSelectedRow());
        }
    }

    public TestStep getSelectedStep() {
        if (testCaseTable.getSelectedRow() != -1) {
            return getCurrentTestCase().getTestSteps().get(testCaseTable.getSelectedRow());
        }
        if (testCaseTable.getRowCount() > 0) {
            return getCurrentTestCase().getTestSteps().get(testCaseTable.getRowCount() - 1);
        }
        return null;
    }

    public TestStep getLastStep() {
        if (testCaseTable.getRowCount() > 0) {
            return getCurrentTestCase().getTestSteps().get(testCaseTable.getRowCount() - 1);
        }
        return null;
    }

    public TestStep insertRowBelow() {
        stopCellEditing();
        if (
            testCaseTable.getSelectedRow() != -1 &&
            testCaseTable.getSelectedRow() + 1 < testCaseTable.getRowCount()
        ) {
            return getCurrentTestCase().addNewStepAt(testCaseTable.getSelectedRow() + 1);
        } else {
            return getCurrentTestCase().addNewStep();
        }
    }

    private void addLastRow() {
        int row = testCaseTable.getSelectedRow();
        int column = testCaseTable.getSelectedColumn();
        if (
            row == testCaseTable.getRowCount() - 1 && column == testCaseTable.getColumnCount() - 1
        ) {
            addRow();
        }
    }

    public TestStep addRow() {
        stopCellEditing();
        return getCurrentTestCase().addNewStep();
    }

    private void replicateRow() {
        stopCellEditing();
        if (testCaseTable.getSelectedRow() != -1) {
            getCurrentTestCase().replicateStepAt(testCaseTable.getSelectedRow());
        }
    }

    private void copyAbove() {
        stopCellEditing();
        int row = testCaseTable.getSelectedRow();
        if (row > 0) {
            for (int col : testCaseTable.getSelectedColumns()) {
                String value = Objects.toString(testCaseTable.getValueAt(row - 1, col), "");
                testCaseTable.setValueAt(value, row, col);
            }
        }
    }

    private void moveRowUp() {
        stopCellEditing();
        if (testCaseTable.getSelectedRows().length > 0) {
            List<Integer> rows = Utils.getSorted(testCaseTable.getSelectedRows());
            int from = rows.get(0);
            int to = rows.get(rows.size() - 1);
            if (getCurrentTestCase().moveRowsUp(from, to)) {
                testCaseTable.getSelectionModel().setSelectionInterval(from - 1, to - 1);
            }
        }
    }

    private void moveRowDown() {
        stopCellEditing();
        if (testCaseTable.getSelectedRows().length > 0) {
            List<Integer> rows = Utils.getSorted(testCaseTable.getSelectedRows());
            int from = rows.get(0);
            int to = rows.get(rows.size() - 1);
            if (getCurrentTestCase().moveRowsDown(from, to)) {
                testCaseTable.getSelectionModel().setSelectionInterval(from + 1, to + 1);
            }
        }
    }

    private void clearValues() {
        stopCellEditing();
        if (testCaseTable.getSelectedRowCount() > 0) {
            getCurrentTestCase()
                .clearValues(testCaseTable.getSelectedRows(), testCaseTable.getSelectedColumns());
        }
    }

    private void deleteSelectedRows() {
        stopCellEditing();
        if (testCaseTable.getSelectedRows().length > 0) {
            getCurrentTestCase()
                .removeSteps(Utils.getReverseSorted(testCaseTable.getSelectedRows()));
        }
    }

    private void parameterizeSelectedSteps() {
        stopCellEditing();
        if (testCaseTable.getSelectedRows().length > 0) {
            List<Integer> rows = Utils.getSorted(testCaseTable.getSelectedRows());
            int from = rows.get(0);
            int to = rows.get(rows.size() - 1);
            TestStep fstep = getCurrentTestCase().getTestSteps().get(from);
            TestStep tstep = getCurrentTestCase().getTestSteps().get(to);
            if (fstep.getCondition().isEmpty()) {
                fstep.setCondition("Start Param");
            } else if (!fstep.getCondition().equals("Start Param")) {
                insertFiller(from).setCondition("Start Param");
                to++;
            }
            if (tstep.getCondition().isEmpty()) {
                tstep.setCondition("End Param");
            } else if (!tstep.getCondition().contains("End Param")) {
                insertFiller(++to).setCondition("End Param");
            }
        }
    }

    private TestStep insertFiller(int row) {
        return getCurrentTestCase().addNewStepAt(row).setObject("Browser").setAction("filler");
    }

    private void toggleComment() {
        stopCellEditing();
        if (testCaseTable.getSelectedRows().length > 0) {
            getCurrentTestCase().toggleComment(testCaseTable.getSelectedRows());
        }
    }

    private void toggleBreakPoint() {
        stopCellEditing();
        if (testCaseTable.getSelectedRows().length > 0) {
            getCurrentTestCase().toggleBreakPoint(testCaseTable.getSelectedRows());
        }
    }

    private void setHardAssertion(boolean hard) {
        stopCellEditing();
        if (testCaseTable.getSelectedRows().length > 0) {
            getCurrentTestCase().setHardAssertion(testCaseTable.getSelectedRows(), hard);
        }
    }

    private void openWithSystemEditor() {
        save();
        Utils.openWithSystemEditor(getCurrentTestCase().getLocation());
    }

    private void save() {
        stopCellEditing();
        populateDescription();
        TestCase current = getCurrentTestCase();
        clearNewlyRecordedFlags(current);
        current.save();
    }

    /**
     * Repaints the Test Plan and Reusable Component trees so that scenario and
     * test-case nodes are (re)marked in red whenever their validation state
     * changes due to an edit or save.
     */
    private void refreshTreeValidation() {
        if (testDesign.getProjectTree() != null) {
            testDesign.getProjectTree().getTree().repaint();
        }
        if (testDesign.getReusableTree() != null) {
            testDesign.getReusableTree().getTree().repaint();
        }
    }

    /**
     * Clears the transient "newly recorded" highlight so steps captured during live recording
     * revert to the default colour once the user explicitly saves.
     */
    private void clearNewlyRecordedFlags(TestCase testCase) {
        if (testCase == null) {
            return;
        }
        boolean cleared = false;
        for (TestStep testStep : testCase.getTestSteps()) {
            if (testStep.isNewlyRecorded()) {
                testStep.setNewlyRecorded(false);
                cleared = true;
            }
        }
        if (cleared) {
            testCaseTable.repaint();
        }
    }

    private void populateDescription() {
        int i = 0;
        for (TestStep testStep : getCurrentTestCase().getTestSteps()) {
            if (!testStep.getAction().isEmpty() && testStep.getDescription().isEmpty()) {
                String desc = MethodInfoManager.getDescriptionFor(testStep.getAction());
                testCaseTable.setValueAt(desc, i, Description.getIndex());
            }
            i++;
        }
    }

    public void reload() {
        stopCellEditing();
        getCurrentTestCase().reload();
        tableColumnManager.reset();
        tcAutoSuggest.installForTestCase();
        validator.initValidations();
    }

    private void ccp(String operation) {
        switch (operation) {
            case "Cut":
                testCaseTable.cut();
                break;
            case "Copy":
                testCaseTable.copy();
                break;
            case "Paste":
                testCaseTable.paste();
                break;
        }
    }

    private void createReusable() {
        if (testCaseTable.getSelectedRowCount() > 0) {
            int from = testCaseTable.getSelectedRows()[0];
            int to = testCaseTable.getSelectedRows()[testCaseTable.getSelectedRowCount() - 1];
            TestCase current = getCurrentTestCase();
            ReusableComponentDialog.Result result = ReusableComponentDialog.prompt(
                this,
                current.getProject()
            );
            if (result != null) {
                Scenario targetScenario;
                if (result.isSharedScope()) {
                    targetScenario =
                        current
                            .getProject()
                            .getSharedReusableScenarioByName(result.getScenarioName());
                    if (targetScenario == null) {
                        targetScenario =
                            current
                                .getProject()
                                .addSharedReusableScenario(result.getScenarioName());
                    }
                } else {
                    targetScenario =
                        current.getProject().getReusableScenarioByName(result.getScenarioName());
                    if (targetScenario == null) {
                        targetScenario =
                            current.getProject().addReusableScenario(result.getScenarioName());
                    }
                }
                TestCase reusable = current.createAsReusable(
                    targetScenario,
                    result.getReusableName(),
                    from,
                    to
                );
                if (reusable != null) {
                    current.save();
                    if (result.isSharedScope()) {
                        testDesign.getSharedReusableTree().getTreeModel().addTestCase(reusable);
                    } else {
                        testDesign.getReusableTree().getTreeModel().addTestCase(reusable);
                    }
                } else {
                    Notification.show("Couldn't Create Reusable - " + result.getReusableName());
                }
            }
        }
    }

    public XTable getTestCaseTable() {
        return testCaseTable;
    }

    private void debug() {
        run(true);
    }

    private void run() {
        run(false);
    }

    private void run(Boolean debugMode) {
        if (!runner.isAlive()) {
            save();
            getCurrentTestCase().getProject().save();
            stopCellEditing();
            SystemDefaults.debugMode.set(debugMode);
            initRunner();
            runner.start();
            if (debugMode) {
                debugDialog.showDebugDialog();
            }
        } else {
            JOptionPane.showMessageDialog(null, "Already Running");
        }
    }

    private void stopExecution() {
        if (runner.isAlive()) {
            SystemDefaults.pauseExecution.set(false);
            SystemDefaults.stopCurrentIteration.set(true);
            SystemDefaults.stopExecution.set(true);
        }
    }

    private void pauseExecution() {
        if (runner.isAlive()) {
            SystemDefaults.pauseExecution.set(true);
        }
    }

    private void continueExecution() {
        if (runner.isAlive()) {
            SystemDefaults.pauseExecution.set(false);
        }
    }

    private void nextStepExecution() {
        if (runner.isAlive()) {
            SystemDefaults.nextStepflag.set(false);
        }
    }

    private void goToSelectedReusable() {
        if (testCaseTable.getSelectedRow() != -1) {
            TestStep tStep = getCurrentTestCase()
                .getTestSteps()
                .get(testCaseTable.getSelectedRow());

            // Go To Reusable is only available for PROJECT and SHARED scope reusables
            if (!tStep.isReusableStep()) {
                Notification.showWarning("Selected step is not a reusable step.");
                return;
            }

            String[] reusableData = tStep.getReusableData();
            if (reusableData != null) {
                ReusableRef ref;
                try {
                    ref = tStep.getEffectiveReusableRef();
                } catch (IllegalArgumentException ex) {
                    ref =
                        new ReusableRef(
                            ReusableRef.Scope.UNSCOPED,
                            reusableData[0],
                            reusableData[1]
                        );
                }
                if (ref == null) {
                    ref =
                        new ReusableRef(
                            ReusableRef.Scope.UNSCOPED,
                            reusableData[0],
                            reusableData[1]
                        );
                }

                // Only allow navigation for PROJECT and SHARED scoped reusables
                if (ref.getScope() == ReusableRef.Scope.UNSCOPED) {
                    Notification.showWarning(
                        "Cannot navigate to unscoped reusable. Please explicitly scope the reference as [Project] or [Shared] in the Action column."
                    );
                    return;
                }

                Scenario scenario = null;
                if (ref.getScope() == ReusableRef.Scope.PROJECT) {
                    scenario =
                        testDesign.getProject().getReusableScenarioByName(ref.getScenarioName());
                } else if (ref.getScope() == ReusableRef.Scope.SHARED) {
                    scenario =
                        testDesign
                            .getProject()
                            .getSharedReusableScenarioByName(ref.getScenarioName());
                }

                if (scenario != null) {
                    TestCase testCase = scenario.getTestCaseByName(ref.getTestCaseName());
                    if (testCase != null) {
                        testDesign.loadTableModelForSelection(testCase);
                    } else {
                        Notification.show(
                            "TestCase [" +
                            ref.getTestCaseName() +
                            "] not present in the Scenario [" +
                            ref.getScenarioName() +
                            "]"
                        );
                    }
                } else {
                    Notification.show(
                        "Scenario [" +
                        ref.getScenarioName() +
                        "] not present in " +
                        ref.getScope() +
                        " reusable scope"
                    );
                }
            }
        }
    }

    private void goToTestData() {
        if (testCaseTable.getSelectedRow() != -1) {
            TestStep tStep = getCurrentTestCase()
                .getTestSteps()
                .get(testCaseTable.getSelectedRow());
            String[] tdFromInput = tStep.getTestDataFromInput();
            if (tdFromInput != null) {
                if (
                    !testDesign.getTestDatacomp().navigateToTestData(tdFromInput[0], tdFromInput[1])
                ) {
                    Notification.show(
                        "Test Data [" +
                        tdFromInput[0] +
                        ":" +
                        tdFromInput[1] +
                        "] not found in Test Data"
                    );
                }
            }
        }
    }

    private void goToObject() {
        if (testCaseTable.getSelectedRow() != -1) {
            TestStep tStep = getCurrentTestCase()
                .getTestSteps()
                .get(testCaseTable.getSelectedRow());
            String[] objectPage = tStep.getPageObject();
            if (objectPage != null) {
                if (!testDesign.getObjectRepo().navigateToObject(objectPage[0], objectPage[1])) {
                    Notification.show(objectPage[0] + " - Object not found in Object Repository");
                }
            }
        }
    }

    public String getDefaultBrowser() {
        return toolBar.getSelectedBrowser();
    }

    public TCHistory getTestCaseHistory() {
        return testCaseHistory;
    }

    public TestCaseToolBar getToolBar() {
        return toolBar;
    }

    public TestDesign getTestDesign() {
        return testDesign;
    }

    class ConsoleDialog extends JDialog {
        private final ConsolePanel cPanel;

        public ConsoleDialog() {
            super(new JFrame());
            setAlwaysOnTop(true);
            setLayout(new BorderLayout());
            cPanel = new ConsolePanel();
            add(cPanel, BorderLayout.CENTER);
            setTitle("Console");
            AppIcon.applyTo(this);
            setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            setModalExclusionType(ModalExclusionType.APPLICATION_EXCLUDE);
            getRootPane()
                .registerKeyboardAction(
                    e -> dispose(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW
                );
        }

        public void showConsole() {
            if (!isVisible()) {
                pack();
                setSize(690, 400);
                setLocationRelativeTo(null);
                setVisible(true);
            } else {
                toFront();
            }
        }

        public void start() {
            cPanel.start();
        }

        public void clear() {
            cPanel.clear();
        }

        public void appendLine(String message) {
            cPanel.appendLine(message);
        }

        public void appendErrorLine(String message) {
            cPanel.appendErrorLine(message);
        }
    }

    class DebugDialog extends JDialog implements ActionListener {

        public DebugDialog() {
            super(new JFrame());
            init();
            setUndecorated(true);
        }

        private void init() {
            JToolBar toolBar = new JToolBar();
            toolBar.setFloatable(false);
            JButton drag = new JButton("   ");

            toolBar.add(drag);
            registerDrag(drag);

            toolBar.add(create("Show Console", "console"));
            toolBar.add(create("Continue Execution", "continue"));
            toolBar.add(create("Go to Next Step", "stepover"));
            toolBar.add(create("Pause the Execution", "pause"));
            toolBar.add(create("Stop the Execution", "stop"));

            add(toolBar);
        }

        private JButton create(String tooltip, String icon) {
            JButton button = new JButton();
            button.setActionCommand(tooltip);
            button.setToolTipText(tooltip);
            button.setIcon(Utils.getIconByResourceName("/ui/resources/testdesign/debug/" + icon));
            button.addActionListener(this);
            return button;
        }

        private void registerDrag(JButton drag) {
            drag.setContentAreaFilled(false);
            drag.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            WindowMover.register(this, drag, WindowMover.MOVE_BOTH);
        }

        void showDebugDialog() {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice defaultScreen = ge.getDefaultScreenDevice();
            Rectangle rect = defaultScreen.getDefaultConfiguration().getBounds();
            pack();
            setLocation((int) rect.getCenterX(), Canvas.Window.winStart.y);
            setAlwaysOnTop(true);
            setVisible(true);
        }

        @Override
        public void actionPerformed(ActionEvent ae) {
            switch (ae.getActionCommand()) {
                case "Show Console":
                    consoleDialog.showConsole();
                    break;
                case "Continue Execution":
                    continueExecution();
                    break;
                case "Go to Next Step":
                    nextStepExecution();
                    break;
                case "Pause the Execution":
                    pauseExecution();
                    break;
                case "Stop the Execution":
                    stopExecution();
                    break;
            }
        }
    }

    class TCHistory extends JMenu implements ActionListener {
        private final LinkedList<String> historyList = new LinkedList<>();

        private final int max = 20;

        private Boolean allowed = false;

        public TCHistory() {
            setText("Recent TestCases");
            MenuScroller.setScrollerFor(this, 10);
        }

        public void log() {
            if (getCurrentTestCase() != null) {
                String val =
                    getCurrentTestCase().getScenario().getName() +
                    ":" +
                    getCurrentTestCase().getName();
                log(val);
            }
        }

        public void log(String val) {
            if (allowed) {
                if (historyList.contains(val)) {
                    int index = historyList.indexOf(val);
                    historyList.remove(index);
                    remove(index);
                }
                if (historyList.size() == max) {
                    historyList.removeLast();
                    remove(getItemCount() - 1);
                }
                historyList.push(val);
                insert(val, 0);
            } else {
                allowed = true;
            }
        }

        @Override
        public void insert(String string, int i) {
            super.insert(string.split(":")[1], i);
            getItem(i).setToolTipText(string);
            getItem(i).setActionCommand(string);
            getItem(i).addActionListener(this);
        }

        public TestCase visit() {
            if (!historyList.isEmpty()) {
                String[] val = historyList.peek().split(":");
                Scenario scenario = testDesign.getProject().getScenarioByName(val[0]);
                if (scenario != null) {
                    return scenario.getTestCaseByName(val[1]);
                }
            }
            return null;
        }

        public void clear() {
            historyList.clear();
            allowed = false;
        }

        @Override
        public void actionPerformed(ActionEvent ae) {
            log(ae.getActionCommand());
            loadTableModelForSelection(visit());
        }
    }
}
