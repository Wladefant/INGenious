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
import com.ing.datalib.settings.RecorderSettings;
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
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.KeyEventPostProcessor;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
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

    /** Anfang bei der hinterlegten Start-Adresse — die Vorgabe. */
    static final String DAUERBROWSER_STARTADRESSE = "startadresse";

    /** Anfang dort, wo der offen gehaltene Browser gerade steht. */
    static final String DAUERBROWSER_WEITER = "weiter";

    /**
     * Exit-Code des Dauerbrowsers, wenn sein Startschutz angeschlagen hat.
     *
     * <p>Er benutzt interne Playwright-APIs und ist an eine gemessene Version gepinnt. Ist eine
     * dieser Stellen umgezogen, endet er mit genau diesem Code, nachdem er einen deutschen
     * Erklärsatz ausgegeben hat — und die Aufnahme läuft hier wie bisher über codegen weiter.
     */
    static final int DAUERBROWSER_STARTSCHUTZ = 3;

    /** Characters a shell reads even from inside a double-quoted argument. */
    private static final String UNSAFE_ARGUMENT_CHARS = "\"%$`\n\r";

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
        final String projectLocation = testDesign != null && testDesign.getProject() != null
            ? testDesign.getProject().getLocation()
            : null;
        final String resolvedBrowser = resolveRecordingBrowser(projectLocation);
        if (!isBrowserInstalled(resolvedBrowser)) {
            String missingMsg = missingBrowserMessage(resolvedBrowser);
            logPlaywrightError(missingMsg);
            JOptionPane.showMessageDialog(
                this,
                missingMsg,
                "Browser nicht gefunden",
                JOptionPane.WARNING_MESSAGE
            );
            SwingUtilities.invokeLater(() -> toolBar.enableRecordButton());
            return;
        }
        toolBar.setConsoleVisible(true);
        consoleDialog.clear();
        consoleDialog.showConsole();
        logPlaywright("🎬 Playwright Recording is being initiated...");
        // The user was not asked where this goes, so the console has to say it.
        if (pluginTarget != null) {
            logPlaywright(
                "Recording into " + target.getScenario().getName() + " / " + target.getName()
            );
        }
        if (startUrl != null) {
            logPlaywright("Opening " + startUrl);
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
     * <p><b>Zwei Wege, und der zweite ist kein Notbehelf.</b> Bevorzugt läuft die Aufnahme im
     * Dauerbrowser ({@code tools/aufnahme-dauerbrowser.mjs}): ein Browser, der über den
     * Testfallwechsel hinweg offen bleibt, damit ein sieben Klicks tiefer Zustand nicht für
     * jeden Fall neu aufgebaut werden muss. Der Dauerbrowser benutzt interne Playwright-APIs
     * und ist an eine gemessene Version gepinnt; sein Startschutz meldet mit Exit-Code 3, wenn
     * eine dieser Stellen fehlt oder umgezogen ist.
     *
     * <p>Genau dann — und ebenso, wenn kein Node oder kein Werkzeugordner da ist — läuft die
     * Aufnahme wie bisher über {@code codegen}, mit einem eigenen Fenster pro Testfall. Eine
     * Testerin darf nie aufnahmeunfähig sein, weil eine interne Schnittstelle umgezogen ist;
     * der Grund steht dann in einem deutschen Satz in der Konsole, nicht in einem Stacktrace.
     *
     * @param outputFile file the recorder writes the recorded Java script to
     * @param startUrl page to open, or {@code null} for a blank page
     * @throws IOException when neither recorder can be started
     */
    public void launchPlaywright(File outputFile, String startUrl) throws IOException {
        if (launchDauerbrowser(outputFile, startUrl)) {
            logPlaywright(
                "============================== Playwright Log Ended =============================="
            );
            return;
        }
        launchCodegen(outputFile, startUrl);
    }

    /** Der bisherige Weg: ein {@code codegen}-Fenster pro Testfall. Der Rückfall. */
    private void launchCodegen(File outputFile, String startUrl) throws IOException {
        String projectLocation = sMainFrame.getProject().getLocation();

        String browser = resolveRecordingBrowser(projectLocation);
        String channelArgs = browserChannelArgs(browser);
        String viewportArgs = viewportArgs();
        String userDataDir = resolveRecorderUserDataDir();

        String storageStateArgs = storageStateArgs(projectLocation);
        // Said out loud on every launch: a recorder that silently did or did not carry the
        // sign-in over is the exact ambiguity these settings exist to remove.
        if (userDataDir != null) {
            // buildCodegenArgs drops the state file when a profile is configured; the log has
            // to tell the same story, or the tester reads a sign-in promise that was not kept.
            logPlaywright(
                storageStateArgs.isEmpty()
                    ? "Recorder profile in use. Sign-in state comes from the profile."
                    : "Recorder profile in use. The saved browser session is not passed on:" +
                    " the profile carries its own."
            );
        } else {
            logPlaywright(
                storageStateArgs.isEmpty()
                    ? "No saved browser session configured. The recorder starts signed out."
                    : "Saved browser session reused:" + storageStateArgs
            );
        }
        logPlaywright(
            channelArgs.isEmpty()
                ? "Recorder browser: bundled Chromium (default)"
                : "Recorder browser channel:" + channelArgs
        );
        logPlaywright("Recorder viewport:" + viewportArgs);

        String processArgs = buildCodegenArgs(
            outputFile,
            startUrl,
            channelArgs + viewportArgs + saveStorageArgs(projectLocation),
            userDataDir,
            storageStateArgs
        );
        runPlaywrightProcess(processArgs);
        logPlaywright(
            "============================== Playwright Log Ended =============================="
        );
    }

    /**
     * Nimmt im Dauerbrowser auf — ein Browser, der über den Testfallwechsel hinweg offen bleibt.
     *
     * <p>Der Client-Prozess bleibt für die Dauer der Aufnahme am Leben und hält eine offene
     * Verbindung zum Daemon; {@link #stopPlaywrightRecording()} beendet ihn wie bisher, und die
     * geschlossene Verbindung ist für den Daemon das Signal, den Belegsatz zu schreiben. Der
     * Daemon selbst ist bewusst KEIN Nachkomme dieses Prozesses (er wird über einen sofort
     * endenden Zwischenschritt gestartet), sonst risse ihn {@link #endPlaywrightProcess} mit —
     * genau das nimmt es dem Browser, offen zu bleiben.
     *
     * @return {@code true}, wenn die Aufnahme im Dauerbrowser gelaufen ist; {@code false},
     *     wenn stattdessen auf {@code codegen} zurückgefallen werden muss
     */
    private boolean launchDauerbrowser(File outputFile, String startUrl) throws IOException {
        File werkzeug = findeDauerbrowserWerkzeug();
        if (werkzeug == null) {
            logPlaywright(
                "Dauerbrowser nicht gefunden (tools/aufnahme-dauerbrowser.mjs) — " +
                "diese Aufnahme öffnet wie bisher ihr eigenes Fenster."
            );
            return false;
        }
        String projectLocation = sMainFrame.getProject().getLocation();
        String modus = resolveRecordingStartModus(projectLocation);
        File belege = belegOrdnerFuerAufnahme(outputFile);

        FortsetzAuftrag fortsetzen = leseUndLoescheFortsetzenAuftrag();
        if (fortsetzen != null) {
            String targetName = liveRecordingTarget != null ? liveRecordingTarget.getName() : "";
            if (fortsetzen.passtZuTestfall(targetName)) {
                if (fortsetzen.belegeOrdner != null && !fortsetzen.belegeOrdner.isBlank()) {
                    File alterOrdner = new File(fortsetzen.belegeOrdner);
                    if (alterOrdner.isDirectory() || alterOrdner.mkdirs()) {
                        belege = alterOrdner;
                    }
                }
                modus = "letzteSeite";
                if (fortsetzen.letzteUrl != null && !fortsetzen.letzteUrl.isBlank()) {
                    startUrl = fortsetzen.letzteUrl.trim();
                }
            } else {
                // Der Auftrag gehoert einem anderen Testfall — er bleibt dessen Auftrag.
                gibFortsetzenAuftragZurueck(fortsetzen, null);
                logPlaywright(
                    "Fortsetzen-Auftrag bleibt liegen: Testfall \"" +
                    fortsetzen.testCaseId +
                    "\" passt nicht zum Ziel \"" +
                    targetName +
                    "\". Starte reguläre Aufnahme."
                );
                fortsetzen = null;
            }
        }

        List<String> command = new ArrayList<>();
        command.add("node");
        command.add(werkzeug.getAbsolutePath());
        command.add("--aufnehmen");
        command.add("--ausgabe");
        command.add(outputFile.getAbsolutePath());
        command.add("--belege");
        command.add(belege.getAbsolutePath());
        command.add("--modus");
        command.add(modus);
        command.add("--besitzer-pid");
        command.add(String.valueOf(ProcessHandle.current().pid()));
        if (liveRecordingTarget != null) {
            command.add("--fall");
            command.add(liveRecordingTarget.getName());
            if (liveRecordingTarget.getScenario() != null) {
                command.add("--szenario");
                command.add(liveRecordingTarget.getScenario().getName());
            }
        }
        // Dieselbe Kanal-Auflösung wie codegen: eine Aufnahme darf nicht in einem anderen
        // Browser laufen, nur weil sie einen anderen Weg nimmt.
        String kanal = browserChannelArgs(resolveRecordingBrowser(projectLocation))
            .replace(" --channel ", "")
            .trim();
        if (!kanal.isEmpty()) {
            command.add("--kanal");
            command.add(kanal);
        }
        boolean hatStartUrl = startUrl != null && !startUrl.isBlank();
        if (hatStartUrl) {
            command.add("--start-url");
            command.add(startUrl.trim());
        }
        if (fortsetzen != null) {
            command.add("--teil");
            command.add(String.valueOf(fortsetzen.teil));
            command.add("--schritt-offset");
            command.add(String.valueOf(fortsetzen.schrittOffset));
        }
        // Drei Lagen, drei Sätze. Vorher stand hier in allen Fällen „die Aufnahme beginnt bei
        // der Start-Adresse." — auch dann, wenn das Projekt gar keine hinterlegt hat und
        // resolveRecordingStartUrl deshalb null lieferte. Ein Protokoll, das etwas behauptet,
        // was nicht passiert, kostet bei der nächsten Diagnose mehr als es hier spart.
        if (fortsetzen != null) {
            logPlaywright(
                "Dauerbrowser: Fortsetzen von Teil " +
                fortsetzen.teil +
                " in Belegordner " +
                belege.getAbsolutePath() +
                " ab Schritt " +
                (fortsetzen.schrittOffset + 1) +
                (hatStartUrl ? " auf " + startUrl.trim() : ".")
            );
        } else {
            logPlaywright(
                "Dauerbrowser: " +
                (
                    DAUERBROWSER_WEITER.equals(modus)
                        ? "die Aufnahme läuft dort weiter, wo der Browser gerade steht."
                        : hatStartUrl
                            ? "derselbe Aufnahme-Tab geht auf die Start-Adresse zurück (" +
                            startUrl.trim() +
                            ") — die Anmeldung bleibt erhalten."
                            : "für dieses Projekt ist keine Start-Adresse hinterlegt; die Aufnahme " +
                            "beginnt dort, wo der Browser gerade steht."
                )
            );
        }
        logPlaywright("Belege: " + belege.getAbsolutePath());

        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException ex) {
            if (fortsetzen != null) {
                throw fortsetzenStartFehlgeschlagen(
                    fortsetzen,
                    "Node ließ sich nicht starten (" + ex.getMessage() + ")"
                );
            }
            logPlaywright(
                "Node ließ sich nicht starten (" +
                ex.getMessage() +
                ") — " +
                "diese Aufnahme öffnet wie bisher ihr eigenes Fenster."
            );
            return false;
        }
        activePlaywrightProcess = process;

        boolean scharf = false;
        try {
            try (
                BufferedReader out = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
                )
            ) {
                String line;
                while ((line = out.readLine()) != null) {
                    logPlaywright(line);
                    if (!scharf && line.startsWith("Dauerbrowser bereit")) {
                        scharf = true;
                        // Erst hier ist der Auftrag erfüllt: die Aufnahme läuft wirklich.
                        verbraucheFortsetzenAuftrag(fortsetzen);
                        if (!recorderReadySignaled) {
                            onRecorderReady();
                        }
                    }
                }
            } catch (IOException ex) {
                logPlaywrightError("Dauerbrowser-Ausgabe abgebrochen: " + ex.getMessage());
            }

            int code = -1;
            try {
                code = process.waitFor();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (!scharf && fortsetzen != null) {
                // Startschutz oder Abbruch vor der ersten Seite: kein Rückfall auf codegen,
                // denn der würde mit einem frischen Belegsatz bei Teil 1 anfangen und die
                // Nacht von gestern stehen lassen.
                throw fortsetzenStartFehlgeschlagen(
                    fortsetzen,
                    "Dauerbrowser endete mit Code " + code + ", bevor die Aufnahme bereit war"
                );
            }
            boolean abgeschlossen = dauerbrowserErgebnis(scharf, code);
            if (!abgeschlossen) {
                logPlaywright("Aufnahme startet stattdessen mit einem eigenen Fenster (codegen).");
            }
            return abgeschlossen;
        } finally {
            if (!scharf) {
                // Jeder andere Ausgang (auch eine geworfene Ausnahme) lässt den Auftrag offen.
                gibFortsetzenAuftragZurueck(fortsetzen, "Aufnahme wurde nicht gestartet");
            }
        }
    }

    /**
     * Der Fortsetz-Auftrag bleibt offen, und die Aufnahme beginnt NICHT von vorn.
     *
     * <p>Ein Rückfall auf {@code codegen} bekäme die Projekt-Startadresse und einen frischen
     * Belegordner mit {@code teil=1} — der Teil der Nacht bliebe verwaist, unter einem
     * Erfolgsbanner. Darum endet dieser Weg mit einer Ausnahme: {@code record()} protokolliert
     * sie, das Panel meldet den Fehlschlag, und der Auftrag liegt für den nächsten Versuch
     * wieder als {@code fortsetzen.json} bereit.
     */
    private IOException fortsetzenStartFehlgeschlagen(FortsetzAuftrag fortsetzen, String grund) {
        boolean zurueck = gibFortsetzenAuftragZurueck(fortsetzen, grund);
        logPlaywrightError(
            grund +
            " — die Aufnahme wird NICHT fortgesetzt und es wird kein neuer Belegsatz begonnen."
        );
        logPlaywright(
            zurueck
                ? "Fortsetzen bleibt offen: Teil " +
                fortsetzen.teil +
                " in Belegordner " +
                fortsetzen.belegeOrdner +
                " ab Schritt " +
                (fortsetzen.schrittOffset + 1) +
                ". Ursache beheben und \"Aufnahme starten\" erneut drücken."
                : "Fortsetz-Auftrag konnte nicht zurückgelegt werden; in Schritt 3 erneut auf " +
                "\"Diesen Anlauf hier fortsetzen\" klicken."
        );
        return new IOException(
            grund +
            ". Die Aufnahme wurde nicht fortgesetzt; der Fortsetz-Auftrag für Teil " +
            fortsetzen.teil +
            " bleibt offen. Es wurde kein neuer Belegsatz angelegt."
        );
    }

    /**
     * Nur ein ausdrücklicher Startschutz erlaubt den Rückfall. Ein Timeout, ein
     * abgebrochener Client oder ein anderer Fehler sagt NICHT, dass der abgetrennte
     * Dauerbrowser beendet ist. Codegen würde dann einen zweiten Browser öffnen.
     */
    static boolean dauerbrowserErgebnis(boolean scharf, int code) throws IOException {
        if (scharf) {
            // Auch ein von Studio hart beendeter Client zählt als gelaufene Aufnahme: der
            // Belegsatz entsteht im Daemon, nicht hier.
            return true;
        }
        if (code == DAUERBROWSER_STARTSCHUTZ) {
            return false;
        }
        throw new IOException(
            "Dauerbrowser endete mit Code " +
            code +
            ", bevor die Aufnahme bereit war. " +
            "Die vorhandene Browser-Sitzung bleibt unverändert; es wird kein zweiter " +
            "Rekorder geöffnet. Bitte Aufnahme-Protokoll prüfen und danach erneut starten."
        );
    }

    /**
     * Der Belegordner dieser Aufnahme: neben der Java-Datei, unter dem Namen der Java-Datei.
     *
     * <p>Damit liegt der Beweissatz dort, wo die Aufnahme selbst liegt, und ein zweiter Testfall
     * überschreibt den ersten nicht — {@code prepareLiveRecordingOutputFile} vergibt pro
     * Aufnahme einen eigenen Dateinamen.
     */
    private static File belegOrdnerFuerAufnahme(File outputFile) {
        String name = outputFile.getName().replaceFirst("\\.[^.]*$", "");
        File parent = outputFile.getParentFile();
        File belege = new File(parent == null ? new File(".") : parent, name + "-belege");
        belege.mkdirs();
        return belege;
    }

    /**
     * Wo eine Aufnahme anfängt: {@code startadresse} (Vorgabe) oder {@code weiter}.
     *
     * <p>Dieselbe Reihenfolge wie {@code AufnahmeStartWahl.gewaehlt()} im Plugin — erst
     * {@code ING_AUFNAHME_MODUS}, dann die gemerkte Wahl dieses Projekts, dann die Vorgabe.
     * Zwei Leser, eine Reihenfolge; sonst zeigte die Tafel etwas anderes an, als der Browser tut.
     */
    public static String resolveRecordingStartModus(String projectLocation) {
        String env = System.getenv("ING_AUFNAHME_MODUS");
        if (env == null || env.isBlank()) {
            env = System.getProperty("ING_AUFNAHME_MODUS");
        }
        if (env != null && !env.isBlank()) {
            return DAUERBROWSER_WEITER.equalsIgnoreCase(env.trim())
                ? DAUERBROWSER_WEITER
                : DAUERBROWSER_STARTADRESSE;
        }
        Path file;
        String propFile = System.getProperty("ING_QA_AUFNAHME_START_DATEI");
        String envFile = System.getenv("ING_QA_AUFNAHME_START_DATEI");
        String local = System.getenv("LOCALAPPDATA");
        if (propFile != null && !propFile.isBlank()) {
            file = Path.of(propFile.trim());
        } else if (envFile != null && !envFile.isBlank()) {
            file = Path.of(envFile.trim());
        } else if (local != null && !local.isBlank()) {
            file = Path.of(local.trim(), "IngQaAutopilot", "aufnahme-start.json");
        } else {
            file =
                Path.of(
                    System.getProperty("user.home", "."),
                    ".IngQaAutopilot",
                    "aufnahme-start.json"
                );
        }
        if (!Files.isRegularFile(file)) {
            return DAUERBROWSER_STARTADRESSE;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (content.startsWith("{")) {
                String key = projectLocation == null
                    ? ""
                    : projectLocation.trim().replace("\\", "/");
                String value = extractBrowserFromJson(content, key);
                if (!value.isEmpty()) {
                    if (DAUERBROWSER_WEITER.equalsIgnoreCase(value)) {
                        return DAUERBROWSER_WEITER;
                    }
                    return DAUERBROWSER_STARTADRESSE;
                }
                List<String> keys = extractKeysFromJson(content);
                String msg =
                    "Aufnahme-Start: Kein Eintrag für Projektschlüssel \"" +
                    key +
                    "\" in " +
                    file +
                    " gefunden. Vorhandene Schlüssel: " +
                    keys +
                    ". Verwende Vorgabe (Startadresse).";
                System.out.println(msg);
                Logger.getLogger(TestCaseComponent.class.getName()).log(Level.INFO, msg);
            }
        } catch (IOException | RuntimeException ex) {
            String msg =
                "Aufnahme-Start: Konnte " +
                file +
                " nicht lesen (" +
                ex.getMessage() +
                "). Verwende Vorgabe (Startadresse).";
            System.out.println(msg);
            Logger.getLogger(TestCaseComponent.class.getName()).log(Level.WARNING, msg, ex);
        }
        return DAUERBROWSER_STARTADRESSE;
    }

    /**
     * Findet {@code tools/aufnahme-dauerbrowser.mjs}.
     *
     * <p>Dieselben Orte, die {@code WerkzeugPfad} im Plugin absucht, in derselben Reihenfolge:
     * neben dem Installationsverzeichnis, im Tester-Paket, und als Entwickler-Ausnahme
     * {@code ING_QA_REPO}. Der Kern kann die Plugin-Klasse nicht benutzen — er kennt das Plugin
     * nicht —, also steht die Suche hier noch einmal, aber klein und ohne eigene Konvention.
     */
    private static File findeDauerbrowserWerkzeug() {
        String rel = "tools" + File.separator + "aufnahme-dauerbrowser.mjs";
        List<String> wurzeln = new ArrayList<>();
        String env = System.getenv("ING_QA_REPO");
        if (env == null || env.isBlank()) {
            env = System.getProperty("ing.qa.repo");
        }
        if (env != null && !env.isBlank()) {
            wurzeln.add(env.trim());
        }
        String userDir = System.getProperty("user.dir", ".");
        wurzeln.add(userDir);
        wurzeln.add(userDir + File.separator + "repo");
        wurzeln.add(userDir + File.separator + "..");
        wurzeln.add(userDir + File.separator + ".." + File.separator + "repo");
        wurzeln.add(userDir + File.separator + ".." + File.separator + "..");
        for (String wurzel : wurzeln) {
            File kandidat = new File(wurzel, rel);
            if (kandidat.isFile()) {
                return kandidat;
            }
        }
        return null;
    }

    /**
     * Ein geliehener Fortsetz-Auftrag.
     *
     * <p><b>Geliehen, nicht verbraucht.</b> Der Auftrag wird beim Lesen aus
     * {@code fortsetzen.json} in eine prozess-eigene Claim-Datei umbenannt — damit kann ihn
     * keine zweite Studio-Instanz ebenfalls bekommen. Verbraucht ist er erst, wenn die
     * Aufnahme wirklich laeuft ({@link #verbraucheFortsetzenAuftrag}); scheitert der Start,
     * geht er unveraendert zurueck ({@link #gibFortsetzenAuftragZurueck}). Vorher wurde er
     * beim Lesen geloescht, und ein gescheiterter Start hat die Nacht-Aufnahme still
     * verloren: codegen begann mit einem frischen Belegsatz bei Teil 1.
     */
    static class FortsetzAuftrag {
        final String testCaseId;
        final String belegeOrdner;
        final String letzteUrl;
        final int teil;
        final int schrittOffset;
        final String belegsatzId;
        /** Die prozess-eigene Claim-Datei, solange der Auftrag geliehen ist. */
        final Path claimDatei;
        /** Der Wortlaut der Auftragsdatei — so geht er beim Zurueckgeben zurueck. */
        final String rohJson;

        FortsetzAuftrag(
            String testCaseId,
            String belegeOrdner,
            String letzteUrl,
            int teil,
            int schrittOffset,
            String belegsatzId
        ) {
            this(testCaseId, belegeOrdner, letzteUrl, teil, schrittOffset, belegsatzId, null, "");
        }

        FortsetzAuftrag(
            String testCaseId,
            String belegeOrdner,
            String letzteUrl,
            int teil,
            int schrittOffset,
            String belegsatzId,
            Path claimDatei,
            String rohJson
        ) {
            this.testCaseId = testCaseId == null ? "" : testCaseId.trim();
            this.belegeOrdner = belegeOrdner == null ? "" : belegeOrdner.trim();
            this.letzteUrl = letzteUrl == null ? "" : letzteUrl.trim();
            this.teil = teil > 0 ? teil : 1;
            this.schrittOffset = Math.max(0, schrittOffset);
            this.belegsatzId = belegsatzId == null ? "" : belegsatzId.trim();
            this.claimDatei = claimDatei;
            this.rohJson = rohJson == null ? "" : rohJson;
        }

        boolean passtZuTestfall(String targetName) {
            if (testCaseId.isBlank() || targetName == null || targetName.isBlank()) {
                return false;
            }
            String t = targetName.trim();
            if (t.equals(testCaseId)) {
                return true;
            }
            String fallIdTarget = fallId(t);
            String fallIdAuftrag = fallId(testCaseId);
            return !fallIdTarget.isBlank() && fallIdTarget.equals(fallIdAuftrag);
        }

        private static String fallId(String name) {
            java.util.regex.Matcher m = java
                .util.regex.Pattern.compile(
                    "^(?:TC[-_]?)?(\\d+)(?:\\s+-\\s+.*)?$",
                    java.util.regex.Pattern.CASE_INSENSITIVE
                )
                .matcher(name.trim());
            return m.matches() ? m.group(1) : "";
        }
    }

    static Path fortsetzenDatei() {
        String propFile = System.getProperty("ING_QA_FORTSETZEN_DATEI");
        if (propFile == null || propFile.isBlank()) {
            propFile = System.getProperty("ing.qa.fortsetzen.datei");
        }
        String envFile = System.getenv("ING_QA_FORTSETZEN_DATEI");
        String local = System.getenv("LOCALAPPDATA");
        if (propFile != null && !propFile.isBlank()) {
            return Path.of(propFile.trim());
        } else if (envFile != null && !envFile.isBlank()) {
            return Path.of(envFile.trim());
        } else if (local != null && !local.isBlank()) {
            return Path.of(local.trim(), "IngQaAutopilot", "fortsetzen.json");
        } else {
            return Path.of(
                System.getProperty("user.home", "."),
                ".IngQaAutopilot",
                "fortsetzen.json"
            );
        }
    }

    /**
     * Holt den Fortsetz-Auftrag — geliehen, nicht verbraucht.
     *
     * <p>Der Name ist der alte, weil die Wirkung nach aussen die alte ist:
     * {@code fortsetzen.json} ist nach diesem Aufruf weg, ein zweiter Aufruf liefert
     * {@code null}, und ein spaeterer {@code record()}-Klick ohne neuen Fortsetzen-Klick
     * nimmt wieder normal auf. Der Unterschied liegt im Scheitern: der Wortlaut des Auftrags
     * liegt bis zum Beweis der laufenden Aufnahme in einer Claim-Datei und geht zurueck,
     * statt verloren zu gehen.
     */
    static FortsetzAuftrag leseUndLoescheFortsetzenAuftrag() {
        Path file = fortsetzenDatei();
        if (!Files.isRegularFile(file)) {
            // Ein Studio, das mitten im Start gestorben ist, hat den Auftrag in seiner
            // Claim-Datei liegen lassen. Er gehoert dann wieder niemandem.
            holeVerwaisteClaimsZurueck(file);
            if (!Files.isRegularFile(file)) {
                return null;
            }
        }

        // Atomarer Claim: verschiebe fortsetzen.json in eine prozess-eigene Claim-Datei.
        // Nur wenn das atomare Umbenennen gelingt, gehoert der Auftrag diesem Prozess.
        // Verhindert Replay und Race-Conditions zwischen mehreren Studio-Instanzen.
        long pid = ProcessHandle.current().pid();
        Path claimFile = file.resolveSibling(
            file.getFileName().toString() + "." + pid + "." + System.nanoTime() + ".claim"
        );
        try {
            Files.move(file, claimFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            return null;
        }

        try {
            String content = Files.readString(claimFile, StandardCharsets.UTF_8).trim();
            Object parsed = org.json.simple.JSONValue.parse(content);
            if (parsed instanceof org.json.simple.JSONObject) {
                org.json.simple.JSONObject json = (org.json.simple.JSONObject) parsed;
                Object tcIdObj = json.get("testCaseId");
                String tcId = tcIdObj instanceof String ? (String) tcIdObj : "";
                Object belegeObj = json.get("belegeOrdner");
                String belege = belegeObj instanceof String ? (String) belegeObj : "";
                Object letzteUrlObj = json.get("letzteUrl");
                String letzteUrl = letzteUrlObj instanceof String ? (String) letzteUrlObj : "";
                Object teilObj = json.get("teil");
                int teil = teilObj instanceof Number ? ((Number) teilObj).intValue() : 1;
                Object offsetObj = json.get("schrittOffset");
                int offset = offsetObj instanceof Number ? ((Number) offsetObj).intValue() : 0;
                Object belegsatzIdObj = json.get("belegsatzId");
                String belegsatzId = belegsatzIdObj instanceof String
                    ? (String) belegsatzIdObj
                    : "";
                return new FortsetzAuftrag(
                    tcId,
                    belege,
                    letzteUrl,
                    teil,
                    offset,
                    belegsatzId,
                    claimFile,
                    content
                );
            }
            // Unlesbarer Inhalt: nichts zum Fortsetzen, also auch nichts zu bewahren.
            Files.deleteIfExists(claimFile);
        } catch (Exception ex) {
            try {
                Files.deleteIfExists(claimFile);
            } catch (Exception ignored) {}
            Logger
                .getLogger(TestCaseComponent.class.getName())
                .log(
                    Level.WARNING,
                    "Fehler beim Lesen von fortsetzen.json: " + ex.getMessage(),
                    ex
                );
        }
        return null;
    }

    /** Die Aufnahme laeuft: der Auftrag ist erfuellt und die Claim-Datei kann weg. */
    static void verbraucheFortsetzenAuftrag(FortsetzAuftrag auftrag) {
        if (auftrag == null || auftrag.claimDatei == null) {
            return;
        }
        try {
            Files.deleteIfExists(auftrag.claimDatei);
        } catch (IOException e) {
            Logger
                .getLogger(TestCaseComponent.class.getName())
                .log(Level.WARNING, "Konnte Claim-Datei nicht loeschen: " + e.getMessage());
        }
    }

    /**
     * Die Aufnahme lief nicht: der Auftrag geht zurueck und bleibt offen.
     *
     * <p>Mit {@code grund} traegt die zurueckgeschriebene Datei ein Feld
     * {@code startFehlgeschlagen}; daran erkennt das Panel, dass hier nicht ein Start
     * laeuft, sondern einer gescheitert ist, und sagt es der Testerin.
     *
     * @return {@code true}, wenn der Auftrag wieder als {@code fortsetzen.json} liegt
     */
    static boolean gibFortsetzenAuftragZurueck(FortsetzAuftrag auftrag, String grund) {
        if (auftrag == null || auftrag.claimDatei == null) {
            return false;
        }
        if (!Files.exists(auftrag.claimDatei)) {
            // Schon verbraucht oder schon zurueckgegeben — beides ist ein Endzustand.
            return false;
        }
        Path ziel = fortsetzenDatei();
        Path tmp = ziel.resolveSibling(
            ziel.getFileName().toString() +
            "." +
            ProcessHandle.current().pid() +
            "." +
            System.nanoTime() +
            ".rueck"
        );
        try {
            if (Files.exists(ziel)) {
                // Ein neuerer Auftrag liegt schon da; der zaehlt, nicht der alte.
                Files.deleteIfExists(auftrag.claimDatei);
                return false;
            }
            String inhalt = auftrag.rohJson;
            if (grund != null && !grund.isBlank()) {
                Object parsed = org.json.simple.JSONValue.parse(inhalt);
                if (parsed instanceof org.json.simple.JSONObject) {
                    org.json.simple.JSONObject json = (org.json.simple.JSONObject) parsed;
                    json.put("startFehlgeschlagen", grund.trim());
                    inhalt = json.toJSONString();
                }
            }
            Files.writeString(tmp, inhalt, StandardCharsets.UTF_8);
            Files.move(tmp, ziel, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(auftrag.claimDatei);
            return true;
        } catch (Exception ex) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {}
            Logger
                .getLogger(TestCaseComponent.class.getName())
                .log(
                    Level.WARNING,
                    "Konnte Fortsetz-Auftrag nicht zurueckgeben: " + ex.getMessage(),
                    ex
                );
            return false;
        }
    }

    /**
     * Gibt Auftraege zurueck, deren Studio waehrend des Starts gestorben ist.
     *
     * <p>Die Claim-Datei traegt die PID ihres Prozesses. Lebt der nicht mehr, ist der
     * Auftrag herrenlos — der juengste kommt zurueck, die aelteren gehen (die letzte
     * Absicht gilt). Gleiche PID-Pruefung wie {@code AufnahmeErholung} im Plugin.
     */
    private static void holeVerwaisteClaimsZurueck(Path ziel) {
        Path ordner = ziel.getParent();
        if (ordner == null) {
            return;
        }
        String praefix = ziel.getFileName().toString() + ".";
        File[] kandidaten = ordner
            .toFile()
            .listFiles((dir, name) -> name.startsWith(praefix) && name.endsWith(".claim"));
        if (kandidaten == null || kandidaten.length == 0) {
            return;
        }
        File juengster = null;
        List<File> tote = new ArrayList<>();
        for (File claim : kandidaten) {
            if (lebtBesitzerVonClaim(claim.getName(), praefix)) {
                continue;
            }
            tote.add(claim);
            if (juengster == null || claim.lastModified() > juengster.lastModified()) {
                juengster = claim;
            }
        }
        if (juengster == null) {
            return;
        }
        try {
            Files.move(juengster.toPath(), ziel, StandardCopyOption.ATOMIC_MOVE);
            Logger
                .getLogger(TestCaseComponent.class.getName())
                .log(
                    Level.INFO,
                    "Fortsetz-Auftrag eines beendeten Studios zurueckgeholt: {0}",
                    juengster.getName()
                );
        } catch (Exception ex) {
            return;
        }
        for (File alt : tote) {
            if (!alt.equals(juengster)) {
                alt.delete();
            }
        }
    }

    /** {@code fortsetzen.json.<pid>.<nanos>.claim} — lebt der Prozess mit dieser PID noch? */
    private static boolean lebtBesitzerVonClaim(String claimName, String praefix) {
        String rest = claimName.substring(praefix.length());
        int punkt = rest.indexOf('.');
        String pidText = punkt > 0 ? rest.substring(0, punkt) : rest;
        try {
            long pid = Long.parseLong(pidText);
            if (pid == ProcessHandle.current().pid()) {
                return true;
            }
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (NumberFormatException ex) {
            // Kein erkennbarer Besitzer: nicht anfassen.
            return true;
        }
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

    /**
     * Resolves which browser or channel the recorder should launch:
     * 1. Environment variable {@code ING_AUFNAHME_BROWSER}
     * 2. Project setting {@code RecorderSettings.getBrowser()}
     * 3. Saved per-project browser choice in {@code %LOCALAPPDATA%\IngQaAutopilot\browser-wahl.json}
     * 4. Empty string (bundled Chromium default)
     *
     * @param projectLocation location of the open project
     * @return the browser name or channel, or {@code ""} for default bundled Chromium
     */
    String resolveRecordingBrowser(String projectLocation) {
        String env = System.getenv("ING_AUFNAHME_BROWSER");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String fromProject = "";
        try {
            if (
                testDesign != null &&
                testDesign.getProject() != null &&
                testDesign.getProject().getProjectSettings() != null
            ) {
                fromProject =
                    testDesign.getProject().getProjectSettings().getRecorderSettings().getBrowser();
            }
        } catch (RuntimeException ex) {
            Logger
                .getLogger(TestCaseComponent.class.getName())
                .log(Level.WARNING, "Unable to read the project's recorder browser setting", ex);
        }
        if (fromProject != null && !fromProject.isBlank()) {
            return fromProject.trim();
        }
        String fromState = readRememberedBrowser(projectLocation);
        if (fromState != null && !fromState.isBlank()) {
            return fromState.trim();
        }
        return "";
    }

    /**
     * Assembles the {@code --channel <name>} argument for the given browser choice.
     *
     * @param browser the configured browser (e.g. "chrome", "msedge", "chromium", or "")
     * @return {@code " --channel <name>"} or {@code ""} for bundled Chromium
     */
    static boolean isUsableChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return false;
        }
        return channel.trim().matches("^[a-zA-Z0-9._-]+$");
    }

    public static String browserChannelArgs(String browser) {
        if (browser == null || browser.isBlank()) {
            return "";
        }
        String b = browser.trim().toLowerCase(Locale.ROOT);
        if ("chromium".equals(b) || "bundled".equals(b) || "default".equals(b)) {
            return "";
        }
        if ("chrome".equals(b) || "google-chrome".equals(b) || "google chrome".equals(b)) {
            return " --channel chrome";
        }
        if (
            "msedge".equals(b) ||
            "edge".equals(b) ||
            "microsoft-edge".equals(b) ||
            "microsoft edge".equals(b)
        ) {
            return " --channel msedge";
        }
        if (isUsableChannel(browser.trim())) {
            return " --channel " + browser.trim();
        }
        Logger
            .getLogger(TestCaseComponent.class.getName())
            .warning("Unsafe or invalid browser channel rejected: " + browser);
        return "";
    }

    /**
     * Derives the recorder viewport size from the actual screen size so that wide applications
     * are not constrained to the default 1280x720 window box (#312).
     *
     * @return {@code " --viewport-size \"<width>, <height>\""}
     */
    public static String viewportArgs() {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
                int width = (int) screenSize.getWidth();
                int height = (int) screenSize.getHeight();
                if (width > 0 && height > 0) {
                    return " --viewport-size \"" + width + ", " + height + "\"";
                }
            }
        } catch (RuntimeException ex) {
            // Headless fallback
        }
        return " --viewport-size \"1920, 1080\"";
    }

    /**
     * Checks whether the chosen browser is installed on this system before launching Playwright.
     *
     * @param browser the browser name or channel (empty = bundled Chromium)
     * @return {@code true} if the browser executable is available, {@code false} otherwise
     */
    static boolean isBrowserInstalled(String browser) {
        if (browser == null || browser.isBlank()) {
            return true;
        }
        String b = browser.trim().toLowerCase(Locale.ROOT);
        if ("chromium".equals(b) || "bundled".equals(b) || "default".equals(b)) {
            return true;
        }
        if ("chrome".equals(b) || "google-chrome".equals(b) || "google chrome".equals(b)) {
            return isChromeInstalled();
        }
        if (
            "msedge".equals(b) ||
            "edge".equals(b) ||
            "microsoft-edge".equals(b) ||
            "microsoft edge".equals(b)
        ) {
            return isEdgeInstalled();
        }
        return isExecutableAvailable(browser);
    }

    static boolean isChromeInstalled() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String pf = System.getenv("ProgramFiles");
            String pfx86 = System.getenv("ProgramFiles(x86)");
            String local = System.getenv("LocalAppData");
            String[] candidates = {
                (pf != null ? pf : "C:\\Program Files") +
                "\\Google\\Chrome\\Application\\chrome.exe",
                (pfx86 != null ? pfx86 : "C:\\Program Files (x86)") +
                "\\Google\\Chrome\\Application\\chrome.exe",
                (local != null ? local : "") + "\\Google\\Chrome\\Application\\chrome.exe"
            };
            for (String p : candidates) {
                if (!p.isEmpty() && new File(p).isFile()) {
                    return true;
                }
            }
            return isExecutableOnPath("chrome.exe");
        } else if (os.contains("mac")) {
            return (
                new File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome").isFile() ||
                isExecutableOnPath("google-chrome")
            );
        } else {
            String[] paths = {
                "/opt/google/chrome/chrome",
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser"
            };
            for (String p : paths) {
                if (new File(p).isFile()) {
                    return true;
                }
            }
            return (
                isExecutableOnPath("google-chrome") ||
                isExecutableOnPath("google-chrome-stable") ||
                isExecutableOnPath("chromium")
            );
        }
    }

    static boolean isEdgeInstalled() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String pf = System.getenv("ProgramFiles");
            String pfx86 = System.getenv("ProgramFiles(x86)");
            String local = System.getenv("LocalAppData");
            String[] candidates = {
                (pfx86 != null ? pfx86 : "C:\\Program Files (x86)") +
                "\\Microsoft\\Edge\\Application\\msedge.exe",
                (pf != null ? pf : "C:\\Program Files") +
                "\\Microsoft\\Edge\\Application\\msedge.exe",
                (local != null ? local : "") + "\\Microsoft\\Edge\\Application\\msedge.exe"
            };
            for (String p : candidates) {
                if (!p.isEmpty() && new File(p).isFile()) {
                    return true;
                }
            }
            return isExecutableOnPath("msedge.exe");
        } else if (os.contains("mac")) {
            return (
                new File("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge")
                .isFile() ||
                isExecutableOnPath("microsoft-edge")
            );
        } else {
            String[] paths = {
                "/opt/microsoft/msedge/msedge",
                "/usr/bin/microsoft-edge",
                "/usr/bin/microsoft-edge-stable",
                "/usr/bin/microsoft-edge-dev"
            };
            for (String p : paths) {
                if (new File(p).isFile()) {
                    return true;
                }
            }
            return (
                isExecutableOnPath("microsoft-edge") || isExecutableOnPath("microsoft-edge-stable")
            );
        }
    }

    private static boolean isExecutableOnPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return false;
        }
        String[] dirs = pathEnv.split(File.pathSeparator);
        for (String dir : dirs) {
            File f = new File(dir, executable);
            if (f.isFile() && f.canExecute()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExecutableAvailable(String nameOrPath) {
        if (new File(nameOrPath).isFile()) {
            return true;
        }
        return isExecutableOnPath(nameOrPath) || isExecutableOnPath(nameOrPath + ".exe");
    }

    static String browserDisplayName(String browser) {
        if (browser == null || browser.isBlank()) {
            return "Chromium";
        }
        String b = browser.trim().toLowerCase(Locale.ROOT);
        if ("chrome".equals(b) || "google-chrome".equals(b) || "google chrome".equals(b)) {
            return "Google Chrome";
        }
        if (
            "msedge".equals(b) ||
            "edge".equals(b) ||
            "microsoft-edge".equals(b) ||
            "microsoft edge".equals(b)
        ) {
            return "Microsoft Edge";
        }
        if ("chromium".equals(b) || "bundled".equals(b) || "default".equals(b)) {
            return "Chromium";
        }
        return browser.trim();
    }

    static String missingBrowserMessage(String browser) {
        String name = browserDisplayName(browser);
        return (
            "Der Browser \"" +
            name +
            "\" konnte auf diesem Rechner nicht gefunden werden. " +
            "Bitte " +
            name +
            " installieren oder ohne Kanal aufnehmen (Standard-Chromium)."
        );
    }

    public static String readRememberedBrowser(String projectLocation) {
        String propFile = System.getProperty("ING_QA_BROWSER_DATEI");
        String envFile = System.getenv("ING_QA_BROWSER_DATEI");
        String local = System.getenv("LOCALAPPDATA");
        Path file;
        if (propFile != null && !propFile.isBlank()) {
            file = Path.of(propFile.trim());
        } else if (envFile != null && !envFile.isBlank()) {
            file = Path.of(envFile.trim());
        } else if (local != null && !local.isBlank()) {
            file = Path.of(local.trim(), "IngQaAutopilot", "browser-wahl.json");
        } else {
            file =
                Path.of(
                    System.getProperty("user.home", "."),
                    ".IngQaAutopilot",
                    "browser-wahl.json"
                );
        }
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (content.startsWith("{")) {
                String key = projectLocation == null
                    ? ""
                    : projectLocation.trim().replace("\\", "/");
                String value = extractBrowserFromJson(content, key);
                if (!value.isEmpty()) {
                    return value;
                }
                if (key != null && !key.isEmpty()) {
                    List<String> keys = extractKeysFromJson(content);
                    String uebernommen = wahlAusGleichnamigemProjekt(keys, key, content);
                    if (!uebernommen.isEmpty()) {
                        meldeEinmal(
                            key,
                            "Browser-Wahl: Für Projektschlüssel \"" +
                            key +
                            "\" steht in " +
                            file +
                            " keine Wahl; übernommen wird \"" +
                            uebernommen +
                            "\" von einem gleichnamigen Projekt."
                        );
                        return uebernommen;
                    }
                    if (keys.contains(key)) {
                        meldeEinmal(
                            key,
                            "Browser-Wahl: Der Eintrag für Projektschlüssel \"" +
                            key +
                            "\" in " +
                            file +
                            " ist leer — es wurde noch kein Browser gewählt. " +
                            "Aufgenommen wird mit dem mitgelieferten Chromium."
                        );
                    } else if (!keys.isEmpty()) {
                        meldeEinmal(
                            key,
                            "Browser-Wahl: Kein Eintrag für Projektschlüssel \"" +
                            key +
                            "\" in " +
                            file +
                            " gefunden. Vorhandene Schlüssel: " +
                            keys +
                            "."
                        );
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            // ignore
        }
        return "";
    }

    /**
     * Die Wahl eines gleichnamigen Projekts, wenn für diesen Schlüssel keine gespeichert ist.
     *
     * <p>Ein Wechsel des Installationsverzeichnisses ändert den Projektschlüssel, nicht das
     * Projekt: derselbe {@code Calimero} lag erst unter {@code nachweis-ziel}, dann unter
     * {@code ING-Testautomatisierung}. Beide Zeilen stehen in derselben Datei, und ohne diese
     * Übernahme nimmt die neue Installation mit Chromium auf, obwohl zwei Zeilen darüber
     * {@code chrome} steht. Verglichen wird der letzte Pfadabschnitt.
     *
     * @return die übernommene Marke, oder {@code ""} wenn es keine gleichnamige gibt
     */
    static String wahlAusGleichnamigemProjekt(List<String> keys, String projectKey, String json) {
        if (keys == null || projectKey == null) {
            return "";
        }
        String name = projektName(projectKey);
        if (name.isEmpty()) {
            return "";
        }
        for (String fremd : keys) {
            if (fremd == null || fremd.equals(projectKey) || !name.equals(projektName(fremd))) {
                continue;
            }
            String wert = extractBrowserFromJson(json, fremd);
            if (!wert.isEmpty()) {
                return wert;
            }
        }
        return "";
    }

    /** Der letzte Pfadabschnitt eines Projektschlüssels, z. B. {@code Calimero}. */
    static String projektName(String projectKey) {
        String k = projectKey == null ? "" : projectKey.trim().replace("\\", "/");
        while (k.endsWith("/")) {
            k = k.substring(0, k.length() - 1);
        }
        int slash = k.lastIndexOf('/');
        return slash < 0 ? k : k.substring(slash + 1);
    }

    /**
     * Sagt einen Satz zur Browser-Wahl genau einmal je Projektschlüssel.
     *
     * <p>{@link #readRememberedBrowser} wird pro Aufnahmestart mehrfach gefragt, und bis zum
     * 16.09.2026 stand dieselbe Zeile deshalb doppelt und dreifach im Protokoll — laut genug,
     * um wie ein Fehler zu wirken, und zu oft, um gelesen zu werden.
     */
    private static void meldeEinmal(String projectKey, String msg) {
        if (!BROWSER_WAHL_GEMELDET.add(projectKey + "|" + msg)) {
            return;
        }
        System.out.println(msg);
        Logger.getLogger(TestCaseComponent.class.getName()).log(Level.INFO, msg);
    }

    private static final Set<String> BROWSER_WAHL_GEMELDET = ConcurrentHashMap.newKeySet();

    public static List<String> extractKeysFromJson(String json) {
        List<String> keys = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return keys;
        }
        try {
            int pos = 0;
            while (pos < json.length()) {
                int keyStart = json.indexOf('"', pos);
                if (keyStart < 0) break;
                int keyEnd = json.indexOf('"', keyStart + 1);
                while (keyEnd > 0 && json.charAt(keyEnd - 1) == '\\') {
                    keyEnd = json.indexOf('"', keyEnd + 1);
                }
                if (keyEnd < 0) break;
                int colon = json.indexOf(':', keyEnd + 1);
                if (colon >= 0) {
                    String between = json.substring(keyEnd + 1, colon).trim();
                    if (between.isEmpty()) {
                        String rawKey = json.substring(keyStart + 1, keyEnd);
                        String unescaped = rawKey.replace("\\\"", "\"").replace("\\\\", "\\");
                        keys.add(unescaped);
                        int valStart = json.indexOf('"', colon + 1);
                        if (valStart >= 0) {
                            int valEnd = json.indexOf('"', valStart + 1);
                            while (valEnd > 0 && json.charAt(valEnd - 1) == '\\') {
                                valEnd = json.indexOf('"', valEnd + 1);
                            }
                            if (valEnd > 0) {
                                pos = valEnd + 1;
                                continue;
                            }
                        }
                    }
                }
                pos = keyEnd + 1;
            }
        } catch (RuntimeException ex) {
            // ignore
        }
        return keys;
    }

    public static String extractBrowserFromJson(String json, String projectKey) {
        try {
            if (projectKey != null && !projectKey.isEmpty()) {
                String searchKey = projectKey.replace('\\', '/');
                String search = "\"" + searchKey.replace("\"", "\\\"") + "\"";
                int idx = json.indexOf(search);
                int keyLen = search.length();
                if (idx < 0 && searchKey.contains("/")) {
                    String backslashSearch =
                        "\"" + searchKey.replace("/", "\\\\").replace("\"", "\\\"") + "\"";
                    idx = json.indexOf(backslashSearch);
                    keyLen = backslashSearch.length();
                }
                if (idx >= 0) {
                    int colon = json.indexOf(':', idx + keyLen);
                    if (colon >= 0) {
                        int valStart = json.indexOf('"', colon + 1);
                        if (valStart >= 0) {
                            int valEnd = json.indexOf('"', valStart + 1);
                            while (valEnd > 0 && json.charAt(valEnd - 1) == '\\') {
                                valEnd = json.indexOf('"', valEnd + 1);
                            }
                            if (valEnd > valStart) {
                                return json.substring(valStart + 1, valEnd);
                            }
                        }
                    }
                }
            }
            int defIdx = json.indexOf("\"default\"");
            if (defIdx >= 0) {
                int colon = json.indexOf(':', defIdx + 9);
                if (colon >= 0) {
                    int valStart = json.indexOf('"', colon);
                    if (valStart >= 0) {
                        int valEnd = json.indexOf('"', valStart + 1);
                        while (valEnd > 0 && json.charAt(valEnd - 1) == '\\') {
                            valEnd = json.indexOf('"', valEnd + 1);
                        }
                        if (valEnd > valStart) {
                            return json.substring(valStart + 1, valEnd);
                        }
                    }
                }
            }
        } catch (RuntimeException ex) {
            // ignore
        }
        return "";
    }

    /**
     * Assembles the codegen command line.
     *
     * <p>Options come before the address because the address is a positional argument.
     * Everything but the output file is optional, and leaving all of it out produces exactly
     * the command the recorder has always run.
     *
     * <p>A persistent profile and a saved-session file answer the same question — where does
     * the sign-in come from — so when both are configured the profile wins and the state file
     * is dropped: Playwright accepts the pair, but a stale state file would overwrite the
     * profile's live session, which is the very sign-in the profile exists to keep.
     *
     * @param outputFile file codegen writes the recorded script to
     * @param startUrl page to open, or {@code null} for codegen's blank page
     * @param optionArgs the options already assembled elsewhere — browser channel, viewport and
     *        {@code --save-storage} — each with its leading space, or {@code ""}
     * @param userDataDir profile directory to reuse, or {@code null}/empty for a fresh profile
     * @param storageStateArgs {@code --load-storage} option as built by
     *        {@link #storageStateArgs(String)}, leading space included, or {@code ""}
     * @return the arguments to hand to the Playwright CLI
     */
    static String buildCodegenArgs(
        File outputFile,
        String startUrl,
        String optionArgs,
        String userDataDir,
        String storageStateArgs
    ) {
        StringBuilder args = new StringBuilder("codegen --target java --output \"")
            .append(escapeQuotedArgument(outputFile.getAbsolutePath()))
            .append('"');
        boolean persistentProfile = userDataDir != null && !userDataDir.isEmpty();
        if (optionArgs != null && !optionArgs.isEmpty()) {
            // Already escaped and formatted by the methods that built them, leading space
            // included: a channel that passed isUsableChannel needs no quoting at all.
            args.append(optionArgs);
        }
        if (persistentProfile) {
            args
                .append(" --user-data-dir \"")
                .append(escapeQuotedArgument(userDataDir))
                .append('"');
        }
        if (!persistentProfile && storageStateArgs != null && !storageStateArgs.isEmpty()) {
            // Escaped and formatted by storageStateArgs(), leading space included.
            args.append(storageStateArgs);
        }
        if (startUrl != null) {
            // Quoted: the command is handed to cmd/bash as one string, and an unquoted query
            // string would be cut at its first '&'. Validation upstream has already ruled out
            // anything that could break out of these quotes.
            args.append(" \"").append(startUrl).append('"');
        }
        return args.toString();
    }

    /**
     * Escapes a value for the double-quoted argument it is placed in.
     *
     * @param value the raw value
     * @return the value with backslashes and quotes escaped
     */
    private static String escapeQuotedArgument(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
     * The profile directory the recording reuses, or nothing — which is a fresh profile per
     * recording, i.e. the behaviour every existing project already has.
     *
     * @return a usable directory, or {@code null} to record with a fresh profile
     */
    private String resolveRecorderUserDataDir() {
        String configured = readRecorderSetting(RecorderSettings::getBrowserUserDataDir);
        if (configured.isEmpty()) {
            return null;
        }
        if (!isUsableShellArgument(configured)) {
            logPlaywright("Ignoring unusable recorder profile directory: " + configured);
            return null;
        }
        logPlaywright("Using the browser profile in " + configured);
        return configured;
    }

    /**
     * Reads one value from the project's recorder settings, treating an unreadable project as
     * an unconfigured one.
     *
     * @param reader the accessor for the wanted value
     * @return the configured value, or an empty string
     */
    private String readRecorderSetting(Function<RecorderSettings, String> reader) {
        try {
            return reader.apply(testDesign.getProject().getProjectSettings().getRecorderSettings());
        } catch (RuntimeException ex) {
            Logger
                .getLogger(TestCaseComponent.class.getName())
                .log(Level.WARNING, "Unable to read the project's recorder settings", ex);
            return "";
        }
    }

    /**
     * A value that survives being placed inside a double-quoted argument of the recorder
     * command.
     *
     * <p>The command is assembled as one string and handed to a shell, and quotes alone do not
     * stop every shell from reading a value: a percent sign is what a Windows shell expands,
     * and a dollar sign or a backtick is what a POSIX shell expands, inside double quotes as
     * much as outside them. A value carrying one of those is refused with a note in the console
     * rather than silently mangled or, worse, executed.
     *
     * @param value the configured value
     * @return {@code true} when it is safe to pass to the recorder
     */
    private boolean isUsableShellArgument(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (UNSAFE_ARGUMENT_CHARS.indexOf(value.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
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
