package de.ing.qa.panel;

import com.ing.ingenious.api.contract.data.ProjectTestDataApi;
import com.ing.ingenious.api.contract.ui.StudioPanelApi;
import de.ing.qa.ado.AdoTestCase;
import de.ing.qa.studio.AdoRunWatcher;
import de.ing.qa.studio.AdoUploadStatus;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * The whole tester job on one screen, in the order a tester actually does it:
 * <b>Testfall → Kunde → Aufnahme</b>.
 *
 * <p>Asked for in exactly those words: <em>"people choose the test case first and then
 * based on the test case — so they need to see it somewhere — they choose the Kunde and
 * then they start recording, that is it."</em> Every piece already existed as a separate
 * screen; what did not exist was the <em>sequence</em>. A tester had to know that
 * "Testfall wählen" comes before "Testdaten", that "Testfall-Übersicht" is where the
 * requirements are, and that the Record button in the toolbar is the end of it. This panel
 * knows that order so nobody has to.
 *
 * <p><b>It reuses, it does not re-implement.</b> Step 1 embeds the real
 * {@link TestCaseChooserPanel} and step 2 the real {@link TestDataPanel} — same search,
 * same ADO link, same plain-German labels, same loud confirmations, same blocklist. They
 * tell this panel what happened through one listener each; nothing else was needed.
 *
 * <p><b>What it deliberately does not do.</b> It does not narrow the customer list from the
 * test case's Voraussetzungen. Those are free prose inside {@code System.Description} on
 * 4,377 of 6,609 cases and extracting them is unsolved
 * (<a href="https://github.com/Wladefant/ing-qa-automation/issues/100">#100</a>). So step 2
 * <em>shows</em> the requirement text beside the picker and lets the tester filter
 * knowingly. Pretending to understand the prose and hiding customers on that basis would
 * hide the wrong ones without anybody noticing.
 *
 * <p><b>Nothing may look dead.</b> Every state change here is visible in more than one
 * place at once — the step chips, the headline, a coloured banner, and the buttons — because
 * this project has already lost a day to a button that worked perfectly and reported it in
 * one grey sentence at the bottom of a full-screen window.
 */
public class GuidedFlowPanel implements StudioPanelApi {

    static final String BTN_BACK = "◀ Zurück";
    static final String BTN_NEXT = "Weiter ▶";
    static final String BTN_RECORD = "▶  Aufnahme starten";
    static final String BTN_STOP = "■  Aufnahme beenden";
    static final String BTN_NO_CUSTOMER = "Dieser Testfall braucht keinen Testkunden";
    static final String BTN_HANDOFF = "Aufnahme abgeben";
    static final String BTN_CHECK = "Aufnahme prüfen";
    static final String BTN_START_URL = "Adresse übernehmen";

    /**
     * Set this to {@code alle} to get the three single screens back as extra tabs.
     *
     * <p>A tester needs one way in, and four toolbar buttons where three are steps of the
     * fourth is three chances to start in the wrong place. The engineers who work on the
     * pieces individually still get them, without the tester ever seeing them, and without
     * the JAR having to be rebuilt to switch: the manifest lists only this panel as a Studio
     * screen, and everything else hangs off here.
     *
     * <p>Read as a system property first and an environment variable second, so a test can
     * set it — {@code System.getenv} cannot be written to from inside the JVM, and a switch
     * that no test can flip is a switch nobody has seen work.
     */
    static final String ENV_PANELS = "ING_QA_PANELS";

    static final String[] STEP_TITLES = {
        "Testfall wählen", "Kunde wählen", "Aufnahme starten"
    };
    private static final String[] STEP_HINTS = {
        "Suchen Sie den Testfall, den Sie testen möchten, und klicken Sie auf "
            + "\"" + TestCaseChooserPanel.BTN_TAKE + "\".",
        "Links steht, was der Testfall verlangt. Rechts wählen Sie einen passenden "
            + "Testkunden und klicken auf \"Kontonummer kopieren\".",
        "Alles bereit. Aufnahme hier starten — und hier auch wieder beenden. Nach dem "
            + "Testfall wird nicht noch einmal gefragt."
    };
    /**
     * Why the next step is still locked — one short line that always fits on screen, and a
     * full explanation for the banner and the tooltips. Split because the short one lives in
     * a single-line label: a sentence that gets clipped to "… keinen Te…" tells nobody
     * anything.
     */
    private static final String[] LOCK_SHORT = {
        "Noch kein Testfall übernommen.",
        "Noch kein Testkunde ausgewählt."
    };
    private static final String[] LOCK_HELP = {
        "Noch kein Testfall übernommen — bitte einen Testfall auswählen und auf \""
            + TestCaseChooserPanel.BTN_TAKE + "\" klicken.",
        "Noch kein Testkunde ausgewählt — bitte eine Kontonummer kopieren, oder angeben, "
            + "dass dieser Testfall keinen Testkunden braucht."
    };

    private static final Color OK_BG = new Color(0xE3, 0xF6, 0xE3);
    private static final Color OK_FG = new Color(0x1B, 0x5E, 0x20);
    private static final Color WARN_BG = new Color(0xFF, 0xF4, 0xD8);
    private static final Color WARN_FG = new Color(0x7A, 0x4F, 0x01);
    private static final Color LIVE_BG = new Color(0xFD, 0xE7, 0xE7);
    private static final Color LIVE_FG = new Color(0xB3, 0x14, 0x12);
    private static final Color ACTIVE_BG = new Color(0x0B, 0x53, 0x94);
    private static final Color ACTIVE_FG = Color.WHITE;
    private static final Color LOCKED_BG = new Color(0xEC, 0xEC, 0xEC);
    private static final Color LOCKED_FG = new Color(0x8A, 0x8A, 0x8A);

    private static final Logger LOG = Logger.getLogger(GuidedFlowPanel.class.getName());

    private final TestCaseChooserPanel chooser = new TestCaseChooserPanel();
    private final TestDataPanel customers = new TestDataPanel();

    /** 0, 1, 2 — the step on screen. */
    private int step;
    private AdoTestCase chosenCase;
    private String account;
    /** Set when the tester states this case needs no customer, which is a valid answer. */
    private boolean customerNotNeeded;

    private JPanel root;
    private CardLayout cards;
    private JPanel cardHost;
    private final JLabel[] chips = new JLabel[3];
    private JLabel headline;
    private JLabel hint;
    private JLabel lock;
    private JLabel banner;
    /**
     * Whether the tools this panel shells out to are the ones it was built against.
     *
     * <p>Its own label, above everything else and outside the step flow, because the thing it
     * reports is true of the whole screen rather than of a step — and because {@link #banner} is
     * rewritten by {@link #update()} on every navigation, so a warning put there would be gone
     * the moment the tester clicked anything. Hidden unless {@link RepoCheck.Result#speak()}.
     */
    private JLabel repoState;
    /**
     * Set once the git half of {@link RepoCheck} has reported. The harness waits on it; without
     * it a test would be timing a background thread.
     */
    private volatile boolean repoStateSettled;
    private JButton back;
    private JButton next;
    private JButton record;
    private JButton noCustomer;
    private JTextArea caseText;
    private JTextArea summary;
    private JLabel customerNote;
    /** What Studio is doing with the recorder, in words, beside the button that does it. */
    private JLabel recorderState;
    /** What address the recorder will open, and which setting decided it. */
    private JLabel startUrlNote;
    /** Where the address is typed, so nobody has to hand-edit a properties file. */
    private javax.swing.JTextField startUrlField;
    /** Stores what was typed into the project's own recorder settings. */
    private JButton startUrlSave;
    /** Whether the address was stored, where, and what will be opened from now on. */
    private JLabel startUrlSaveState;
    /** The row holding field and button — hidden when there is no project to write to. */
    private JPanel startUrlEditor;
    /**
     * Whether the remembered address has already been offered back to this project.
     *
     * <p>The note is repainted every second. Without this the restore would be attempted every
     * second on a project that refuses the write, rewriting the setting and repainting its own
     * answer over whatever the tester was in the middle of reading.
     */
    private boolean startUrlRestoreTried;
    /** Whether the finished run's evidence reached Azure DevOps — the last step of the job. */
    private JLabel uploadState;
    /**
     * Counts how many elements each recorded step actually matches, on the page it was recorded
     * against.
     *
     * <p>This belongs at record time and cannot be there — see {@link SelectorCheck} for the
     * measurements. So it sits here, one button before the hand-off: the tester is still in
     * front of the application, and a step that matches three elements can be re-recorded in
     * seconds. The same fact in a report next week is a bug hunt.
     */
    private JButton check;
    /** What the check found, or why it could not find anything — never silence. */
    private JLabel checkState;
    /** True while the probe is running; the harness waits on it. */
    private volatile boolean checkRunning;
    /** Set once a check has reported, so re-entering step 3 cannot wipe the answer. */
    private boolean checkReported;
    /** Turns the finished recording into one file the tester can send. */
    private JButton handoff;
    /** Whether a package was created, and where it is — or why none was. */
    private JLabel handoffState;
    /** True while the packaging child process is running; the harness waits on it. */
    private volatile boolean handoffRunning;
    /** The last package written, so a test can open it and a tester can be told its name. */
    private java.nio.file.Path handoffZip;
    /** Set once an attempt has reported, so re-entering step 3 cannot wipe the answer. */
    private boolean handoffReported;
    /** Kept so re-opening the screen replaces its subscription instead of stacking another. */
    private AdoUploadStatus.Listener uploadListener;
    /** Re-reads Studio's recording state, so the button cannot go stale. */
    private javax.swing.Timer recorderPoll;

    /**
     * Arms the run watcher — here, because this is the earliest point in a Studio session a
     * plugin can reach.
     *
     * <p>A tester who reopens Studio and presses <b>F6</b> to re-run an existing test case
     * never opens this screen at all. Arming from {@code createPanel()} therefore produced a
     * green run and no record of it anywhere in Azure DevOps. True plugin load is not
     * available: {@code PluginLoader} finds entry classes with {@code ClassLoader.loadClass},
     * which per the JLS does not run static initialisers, and the registries that instantiate
     * them are lazy — <em>except</em> {@code StudioPanelPlugins.discover}, which constructs
     * every {@code StudioPanelApi} entry class at Studio <b>startup</b> just to read its title,
     * before any project is open and whichever screen the tester later chooses. So this
     * constructor is equivalent to plugin load, and is the only place that is.
     *
     * <p>Which is also why it does nothing else: Studio builds this object to ask it one
     * question, so no Swing component is touched here, nothing blocks, and nothing may be
     * thrown — a constructor that threw would cost the tester the whole screen, and
     * {@code arm()} itself is idempotent and non-blocking by design.
     */
    public GuidedFlowPanel() {
        try {
            AdoRunWatcher.arm();
        } catch (RuntimeException | LinkageError ex) {
            LOG.log(Level.WARNING, "ADO run watcher could not be armed: " + ex, ex);
        }
    }

    @Override
    public String getTitle() {
        return "Ablauf";
    }

    @Override
    public String getTooltip() {
        return "In drei Schritten: Testfall wählen, Kunde wählen, Aufnahme starten";
    }

    /**
     * Passes Studio's project test-data handle straight to the embedded picker.
     *
     * <p>Forwarded rather than ignored so this panel works on its own terms: whichever of
     * the two screens Studio hands the handle to, the customer profile still lands on the
     * test case.
     */
    @Override
    public void setProjectTestData(ProjectTestDataApi testData) {
        customers.setProjectTestData(testData);
    }

    @Override
    public JComponent createPanel() {
        root = new JPanel(new BorderLayout(8, 8));
        // The live-Studio driver needs to find this screen. Until now it inferred it, by
        // climbing to the innermost ancestor whose text contained "Schritt " — so a hint added
        // to a different card, reading "…ob jeder Schritt genau ein Element trifft…", moved the
        // answer one level down. Every chip and banner read came back null, and the driver
        // reported two working links as BROKEN while its own screenshot showed them working.
        // A relied-on instrument should not be locatable by prose: this gives it something to
        // ask for. Invisible to the tester, unaffected by translation, and attached to the
        // component rather than derived from its contents, so no layout change can move it.
        root.putClientProperty("de.ing.qa.panel", "guided-flow");
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        root.add(buildHeader(), BorderLayout.NORTH);

        cards = new CardLayout();
        cardHost = new JPanel(cards);
        cardHost.add(buildStepCase(), "0");
        cardHost.add(buildStepCustomer(), "1");
        cardHost.add(buildStepRecord(), "2");
        root.add(cardHost, BorderLayout.CENTER);

        root.add(buildNav(), BorderLayout.SOUTH);

        chooser.setOnTaken(this::caseTaken);
        customers.setOnAccountChosen(this::accountChosen);

        // Subscribing here rather than in the constructor is deliberate: the label only exists
        // once the screen is built, and AdoUploadStatus replays the last event on subscription,
        // so an upload that happened while this screen was closed still lands on it. Every
        // event is marshalled — listeners are called on the uploader's own daemon thread.
        if (uploadListener != null) {
            AdoUploadStatus.removeListener(uploadListener);
        }
        uploadListener = event -> SwingUtilities.invokeLater(() -> showUpload(event));
        AdoUploadStatus.addListener(uploadListener);

        // The recording can end without this panel being told: the tester can press Studio's
        // own toolbar button, or simply close the recorder's browser window. A button that
        // trusted its own memory would then offer "Aufnahme beenden" for a recording that is
        // over, and pressing it would start a new one — the same bug, mirrored. So the state
        // is re-read, cheaply, for as long as the panel exists.
        recorderPoll = new javax.swing.Timer(1000, e -> {
            if (step == 2 && root.isDisplayable()) {
                refreshRecorderState();
            }
        });
        recorderPoll.start();

        startRepoCheck();

        goTo(0);
        return withEngineerTabs(root);
    }

    /**
     * Says, before anything is pressed, whether this machine's tools are the ones this plugin
     * was built against.
     *
     * <p>Two phases because one of them starts git: the file half is a handful of stat calls and
     * is painted here, synchronously, so the answer is on screen in the first frame; the commit
     * half runs on a daemon thread and repaints if it has more to say. A tester never sees an
     * empty header waiting for a subprocess, and the event dispatch thread never waits for one.
     *
     * <p>Ordered so the certain half cannot be overwritten by the uncertain one:
     * {@link RepoCheck#history} returns anything that is not {@link RepoCheck.State#OK}
     * unchanged, so a missing-tools warning painted here stays exactly as it is.
     */
    private void startRepoCheck() {
        RepoCheck.Result files = RepoCheck.files();
        showRepoState(files);
        Thread worker = new Thread(() -> {
            RepoCheck.Result full;
            try {
                full = RepoCheck.history(files);
            } catch (RuntimeException | LinkageError ex) {
                // A check that throws may never cost the tester the screen it is warning about.
                LOG.log(Level.WARNING, "repository check failed: " + ex, ex);
                repoStateSettled = true;
                return;
            }
            SwingUtilities.invokeLater(() -> {
                showRepoState(full);
                repoStateSettled = true;
            });
        }, "ing-repo-check");
        worker.setDaemon(true);
        worker.start();
    }

    /** Paints the repository answer, or hides the line when there is nothing certain to say. */
    private void showRepoState(RepoCheck.Result result) {
        if (repoState == null) {
            return;
        }
        LOG.log(Level.INFO, "ING repository check: {0} — {1}",
            new Object[] {result.state(), result.detail()});
        if (!result.speak()) {
            repoState.setVisible(false);
            return;
        }
        // The whole sentence AND the technical wording go in the tooltip: the line clips, so the
        // tooltip is where the rest of the instruction lives, and whoever the tester reports to
        // needs the file names and the commit that are deliberately not in the sentence.
        paintOneLine(repoState, WARN_BG, WARN_FG, result.message());
        repoState.setToolTipText("<html>" + result.message() + "<br><br>"
            + result.detail().replace("; ", "<br>") + "</html>");
    }

    /**
     * The single entry point, unless an engineer asked for the pieces.
     *
     * <p>Studio's toolbar showed four buttons — <em>Ablauf</em>, <em>Testdaten</em>,
     * <em>Testfall wählen</em>, <em>Testfall-Übersicht</em> — of which three are steps of the
     * first. The handout had to spend a paragraph telling testers to ignore them, which is
     * documentation standing in for a fix. Only this panel is a Studio screen now (see the
     * JAR manifest's {@code pluginEntryClasses}), so there is one way in and it is the right
     * one.
     *
     * <p>Nothing was deleted. The automation engineers who work on the single screens set
     * {@code ING_QA_PANELS=alle} and get them back as tabs beside the flow — a runtime
     * switch, because rebuilding a JAR to see a screen is not a workflow. Fresh instances,
     * because a Swing component lives in one container at a time and the flow's own copies
     * are in use.
     */
    private JComponent withEngineerTabs(JComponent flow) {
        if (!"alle".equalsIgnoreCase(env(ENV_PANELS)) && !"all".equalsIgnoreCase(env(ENV_PANELS))) {
            return flow;
        }
        javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
        tabs.addTab(getTitle(), flow);
        for (StudioPanelApi panel : new StudioPanelApi[] {
            new TestCaseChooserPanel(), new TestDataPanel(), new TestCaseOverviewPanel()
        }) {
            try {
                tabs.addTab(panel.getTitle(), panel.createPanel());
            } catch (RuntimeException ex) {
                // An extra tab is a convenience for engineers; it may never cost the flow.
                tabs.addTab(panel.getTitle(), new JLabel("<html>Diese Einzelansicht konnte "
                    + "nicht geöffnet werden: " + ex + "</html>"));
            }
        }
        return tabs;
    }

    /** Overridable in tests; {@code System.getenv} is otherwise unmockable. */
    private static String env(String name) {
        String value = System.getProperty(name);
        return value != null ? value : System.getenv(name);
    }

    // ------------------------------------------------------------------ header

    private JComponent buildHeader() {
        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));

        // Above the steps, because it is about the machine and not about the step — and above
        // everything, because a tester who reads it here never gets as far as the greyed button
        // that used to be the only symptom.
        //
        // ONE LINE THAT CLIPS, like every other answer line on this panel, and that was decided
        // by rendering it rather than by taste. The first version was an HTML label that wrapped.
        // At 1500 pixels it looked right; rendered at 900 — a perfectly ordinary Studio window —
        // BoxLayout had already given the label the height of ONE line, computed at the wide
        // width, so the wrapped second line was drawn half over the step chips and its last
        // words were simply gone. The tester was told their machine is out of date and not what
        // to do about it. Clipping is honest about the same shortage of room, the tooltip keeps
        // the whole sentence, and the message puts the instruction first so that whatever
        // survives the clip is the action.
        repoState = new JLabel(" ");
        repoState.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        repoState.setOpaque(true);
        repoState.setVisible(false);
        repoState.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        repoState.setFont(repoState.getFont().deriveFont(Font.BOLD));
        head.add(repoState);

        JPanel chipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        chipRow.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        for (int i = 0; i < chips.length; i++) {
            chips[i] = chip(i);
            chipRow.add(chips[i]);
            if (i < chips.length - 1) {
                JLabel arrow = new JLabel("▶");
                arrow.setForeground(LOCKED_FG);
                chipRow.add(arrow);
            }
        }
        head.add(chipRow);

        headline = new JLabel(" ");
        headline.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        headline.setBorder(BorderFactory.createEmptyBorder(8, 2, 0, 2));
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize2D() + 5f));
        head.add(headline);

        hint = new JLabel(" ");
        hint.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        hint.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        head.add(hint);

        lock = new JLabel(" ");
        lock.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        lock.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 2));
        lock.setForeground(WARN_FG);
        lock.setFont(lock.getFont().deriveFont(Font.BOLD));
        head.add(lock);

        banner = new JLabel(" ");
        banner.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        banner.setOpaque(true);
        banner.setVisible(false);
        banner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        banner.setFont(banner.getFont().deriveFont(Font.BOLD));
        head.add(banner);

        return head;
    }

    /** One step marker. Clickable once its step is reachable — going back must be easy. */
    private JLabel chip(int index) {
        JLabel label = new JLabel();
        label.setOpaque(true);
        label.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                goTo(index);
            }
        });
        return label;
    }

    // ------------------------------------------------------------------ steps

    /** Step 1 is the existing chooser, whole. */
    private JComponent buildStepCase() {
        JPanel card = new JPanel(new BorderLayout());
        card.add(chooser.createPanel(), BorderLayout.CENTER);
        return card;
    }

    /**
     * Step 2 puts the two things side by side that a tester previously had to hold in their
     * head across two screens: what the case requires, and who is available.
     */
    private JComponent buildStepCustomer() {
        caseText = new JTextArea();
        caseText.setEditable(false);
        caseText.setLineWrap(true);
        caseText.setWrapStyleWord(true);
        caseText.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.add(sectionTitle("Das verlangt der Testfall"), BorderLayout.NORTH);
        left.add(new JScrollPane(caseText), BorderLayout.CENTER);

        customerNote = new JLabel(" ");
        customerNote.setOpaque(true);
        customerNote.setVisible(false);
        customerNote.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));

        noCustomer = new JButton(BTN_NO_CUSTOMER);
        noCustomer.setToolTipText(
            "Für manche Testfälle wird kein bestimmter Kunde gebraucht. Dann hier klicken.");
        noCustomer.addActionListener(e -> skipCustomer());

        JPanel below = new JPanel(new BorderLayout(0, 4));
        below.add(customerNote, BorderLayout.NORTH);
        JPanel skipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        skipRow.add(noCustomer);
        below.add(skipRow, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(0, 4));
        right.add(sectionTitle("Dazu passenden Testkunden auswählen"), BorderLayout.NORTH);
        right.add(customers.createPanel(), BorderLayout.CENTER);
        right.add(below, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.4);
        split.setBorder(BorderFactory.createEmptyBorder());
        // The real export has seventeen columns, and a table that wide asks for more width
        // than the window has — which pushed the requirement text to ZERO pixels and left
        // step 2 showing only half of what it exists to show. Minimum widths stop the
        // table winning outright; the divider is then placed by proportion once the split
        // actually has a size, because setDividerLocation(double) is a no-op before that.
        left.setMinimumSize(new Dimension(300, 80));
        right.setMinimumSize(new Dimension(360, 80));
        split.addComponentListener(new ComponentAdapter() {
            private boolean placed;

            @Override
            public void componentResized(ComponentEvent event) {
                if (!placed && split.getWidth() > 0) {
                    placed = true;
                    split.setDividerLocation(0.4);
                }
            }
        });
        return split;
    }

    /**
     * Step 3 is a summary and one button — the same button that ends the recording again.
     *
     * <p>It used to be one button that only ever said "starten", and the stop lived on
     * another screen: the tester started here and had to find Test Design at the bottom left
     * to finish. The handout needed a chapter for that, and the rehearsal named it the most
     * likely place to give up. Whatever the recorder is doing is now said in words next to
     * the button, and the button does the other half of it.
     */
    private JComponent buildStepRecord() {
        summary = new JTextArea();
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        record = new JButton(BTN_RECORD);
        record.setFont(record.getFont().deriveFont(Font.BOLD, record.getFont().getSize2D() + 4f));
        record.setPreferredSize(new Dimension(280, 48));
        record.addActionListener(e -> pressRecordButton());

        recorderState = new JLabel(" ");
        recorderState.setOpaque(true);
        recorderState.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        recorderState.setFont(recorderState.getFont().deriveFont(Font.BOLD));

        startUrlNote = new JLabel(" ");
        startUrlNote.setOpaque(true);
        startUrlNote.setVisible(false);
        startUrlNote.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));

        startUrlField = new javax.swing.JTextField(38);
        startUrlField.setToolTipText("Die Adresse der Anwendung, die aufgezeichnet werden soll — "
            + "vollständig, mit http:// oder https:// am Anfang.");
        startUrlSave = new JButton(BTN_START_URL);
        startUrlSave.setToolTipText("Speichert die Adresse in den Einstellungen dieses Projekts. "
            + "Sie gilt dann für jede Aufnahme in diesem Projekt.");
        startUrlSave.addActionListener(e -> pressStartUrlSaveButton());
        // Enter in the field does the same thing as the button. A text field that swallows Enter
        // is read as broken, and the alternative — the tester leaving the field and looking for
        // the button — is the step this whole panel exists to remove.
        startUrlField.addActionListener(e -> pressStartUrlSaveButton());

        startUrlEditor = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        startUrlEditor.add(new JLabel("Start-Adresse:"));
        startUrlEditor.add(startUrlField);
        startUrlEditor.add(startUrlSave);
        startUrlEditor.setVisible(false);

        startUrlSaveState = new JLabel(" ");
        startUrlSaveState.setOpaque(true);
        startUrlSaveState.setVisible(false);
        startUrlSaveState.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        startUrlSaveState.setFont(startUrlSaveState.getFont().deriveFont(Font.BOLD));

        JPanel startUrlBlock = new JPanel();
        startUrlBlock.setLayout(new BoxLayout(startUrlBlock, BoxLayout.Y_AXIS));
        startUrlNote.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        startUrlEditor.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        startUrlSaveState.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        startUrlBlock.add(startUrlNote);
        startUrlBlock.add(startUrlEditor);
        startUrlBlock.add(startUrlSaveState);

        uploadState = new JLabel(" ");
        uploadState.setOpaque(true);
        uploadState.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        uploadState.setFont(uploadState.getFont().deriveFont(Font.BOLD));
        // Said before anything has happened, so the line is never an empty grey strip whose
        // meaning a tester has to guess — and so its silence later cannot be mistaken for "no
        // upload was even attempted".
        paintOneLine(uploadState, LOCKED_BG, Color.BLACK,
            "Nach dem Testlauf steht hier, ob das Ergebnis in Azure DevOps angekommen ist.");

        handoff = new JButton(BTN_HANDOFF);
        handoff.setToolTipText("Packt die fertige Aufnahme in eine einzige Datei, die Sie an "
            + "die Testautomatisierung senden können. Kontonummern, Ihre gespeicherte "
            + "Anmeldung und die Ergebnis-Dateien bleiben dabei auf diesem Rechner.");
        handoff.addActionListener(e -> pressHandoffButton());

        handoffState = new JLabel(" ");
        handoffState.setOpaque(true);
        handoffState.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        handoffState.setFont(handoffState.getFont().deriveFont(Font.BOLD));

        // BorderLayout, not FlowLayout, and this is not a preference. FlowLayout hands every
        // component its PREFERRED width, so the recorder state — which in the unreadable case
        // is a long sentence — did not fit beside the button in a 900-pixel Studio window and
        // was moved to a second row. The row's height had already been measured for one row,
        // so that second row was not merely clipped: it was not painted at all. Rendered at
        // 900 pixels the tester saw a greyed-out "Aufnahme starten" and no word anywhere
        // saying why it could not be pressed — the same defect the HTML labels had, one
        // container up, and worse, because it removed the whole answer instead of half of it.
        // CENTER gives the line the width that is left and lets it clip, which is what every
        // other answer line on this panel does.
        JPanel buttonRow = new JPanel(new BorderLayout(8, 0));
        buttonRow.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        buttonRow.add(record, BorderLayout.WEST);
        buttonRow.add(recorderState, BorderLayout.CENTER);

        JPanel handoffRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        handoffRow.add(handoff);

        check = new JButton(BTN_CHECK);
        check.setToolTipText("Zählt für jeden aufgenommenen Schritt, wie viele Elemente er auf "
            + "dieser Seite wirklich trifft. Trifft ein Schritt mehr als eines, ist nicht "
            + "festgelegt, welches davon der Test später anklickt.");
        check.addActionListener(e -> pressCheckButton());

        checkState = new JLabel(" ");
        checkState.setOpaque(true);
        checkState.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        checkState.setFont(checkState.getFont().deriveFont(Font.BOLD));

        JPanel checkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        checkRow.add(check);

        JPanel checkBlock = new JPanel(new BorderLayout(0, 4));
        checkBlock.add(checkRow, BorderLayout.NORTH);
        checkBlock.add(checkState, BorderLayout.SOUTH);

        JPanel handoffBlock = new JPanel(new BorderLayout(0, 4));
        handoffBlock.add(handoffRow, BorderLayout.NORTH);
        handoffBlock.add(handoffState, BorderLayout.SOUTH);

        // The upload line, the prüfen button and the abgeben button sit under the recording
        // button in the order the tester meets them: record, then what became of the run, then
        // check what was recorded, then hand it over. Checking comes BEFORE handing over on
        // purpose — an ambiguous step is cheap to fix while the application is still open and
        // expensive once it is somebody else's package. Each answer line is full width and
        // clips rather than wraps — see paintOneLine.
        JPanel tail = new JPanel(new BorderLayout(0, 4));
        tail.add(uploadState, BorderLayout.NORTH);
        tail.add(checkBlock, BorderLayout.CENTER);
        tail.add(handoffBlock, BorderLayout.SOUTH);

        JPanel below = new JPanel(new BorderLayout(0, 4));
        below.add(startUrlBlock, BorderLayout.NORTH);
        below.add(buttonRow, BorderLayout.CENTER);
        below.add(tail, BorderLayout.SOUTH);

        refreshCheckState();
        refreshHandoffState();

        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.add(sectionTitle("Ihre Auswahl"), BorderLayout.NORTH);
        card.add(new JScrollPane(summary), BorderLayout.CENTER);
        card.add(below, BorderLayout.SOUTH);
        return card;
    }

    private static JComponent sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + 1f));
        label.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
        return label;
    }

    private JComponent buildNav() {
        back = new JButton(BTN_BACK);
        back.addActionListener(e -> goTo(step - 1));
        next = new JButton(BTN_NEXT);
        next.addActionListener(e -> goTo(step + 1));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.add(back);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.add(next);

        JPanel nav = new JPanel(new BorderLayout());
        nav.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        nav.add(left, BorderLayout.WEST);
        nav.add(Box.createHorizontalStrut(8), BorderLayout.CENTER);
        nav.add(right, BorderLayout.EAST);
        return nav;
    }

    // ------------------------------------------------------------------ flow

    /** A step is reachable when everything before it is done. Step 1 always is. */
    private boolean reachable(int index) {
        if (index <= 0) {
            return true;
        }
        if (index == 1) {
            return chosenCase != null;
        }
        return chosenCase != null && (account != null || customerNotNeeded);
    }

    /** The earliest step that still has nothing to show for it. */
    private int firstUnfinished() {
        return done(0) ? 1 : 0;
    }

    private boolean done(int index) {
        return switch (index) {
            case 0 -> chosenCase != null;
            case 1 -> account != null || customerNotNeeded;
            default -> false;
        };
    }

    private void goTo(int index) {
        if (index < 0 || index > 2) {
            return;
        }
        if (!reachable(index)) {
            // Never a silent no-op: name the FIRST step still missing — jumping to step 3
            // with nothing done at all must complain about the test case, not the customer.
            show(WARN_BG, WARN_FG, LOCK_HELP[firstUnfinished()]);
            return;
        }
        step = index;
        cards.show(cardHost, String.valueOf(index));
        if (index == 1) {
            caseText.setText(chosenCase == null ? "" : TestCaseOverviewPanel.render(chosenCase));
            caseText.setCaretPosition(0);
            updateCustomerNote();
        }
        if (index == 2) {
            summary.setText(renderSummary());
            summary.setCaretPosition(0);
            refreshRecorderState();
            refreshHandoffState();
        }
        update();
    }

    private void update() {
        for (int i = 0; i < chips.length; i++) {
            boolean isDone = done(i);
            String text = (isDone ? "✔ " : (i + 1) + ". ") + STEP_TITLES[i];
            chips[i].setText(text);
            if (i == step) {
                chips[i].setBackground(ACTIVE_BG);
                chips[i].setForeground(ACTIVE_FG);
                chips[i].setFont(chips[i].getFont().deriveFont(Font.BOLD));
                chips[i].setToolTipText("Sie sind hier.");
            } else if (isDone) {
                chips[i].setBackground(OK_BG);
                chips[i].setForeground(OK_FG);
                chips[i].setFont(chips[i].getFont().deriveFont(Font.PLAIN));
                chips[i].setToolTipText("Erledigt — anklicken, um es noch einmal zu ändern.");
            } else if (reachable(i)) {
                chips[i].setBackground(LOCKED_BG);
                chips[i].setForeground(Color.BLACK);
                chips[i].setFont(chips[i].getFont().deriveFont(Font.PLAIN));
                chips[i].setToolTipText("Anklicken, um zu diesem Schritt zu springen.");
            } else {
                chips[i].setBackground(LOCKED_BG);
                chips[i].setForeground(LOCKED_FG);
                chips[i].setFont(chips[i].getFont().deriveFont(Font.PLAIN));
                chips[i].setToolTipText(LOCK_HELP[firstUnfinished()]);
            }
            chips[i].setCursor(Cursor.getPredefinedCursor(
                reachable(i) ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        }

        headline.setText("Schritt " + (step + 1) + " von 3 — " + STEP_TITLES[step]);
        hint.setText(STEP_HINTS[step]);
        back.setEnabled(step > 0);
        boolean canGoOn = step < 2 && reachable(step + 1);
        next.setEnabled(canGoOn);
        next.setToolTipText(canGoOn
            ? "Weiter zu Schritt " + (step + 2) + ": " + STEP_TITLES[step + 1]
            : (step == 2 ? "Das ist der letzte Schritt." : LOCK_HELP[step]));
        // The reason a step is blocked lives on screen, not only in a tooltip nobody hovers.
        boolean blocked = step < 2 && !canGoOn;
        lock.setText(blocked ? "→ " + LOCK_SHORT[step] : " ");
        lock.setToolTipText(blocked ? LOCK_HELP[step] : null);
    }

    private void caseTaken(AdoTestCase testCase) {
        chosenCase = testCase;
        account = null;
        customerNotNeeded = false;
        show(OK_BG, OK_FG, "✔ Testfall " + testCase.adoId() + " übernommen: " + testCase.title());
        goTo(1);
    }

    private void accountChosen(String kontonummer) {
        account = kontonummer;
        customerNotNeeded = false;
        // The picker's own status line goes off screen with this step change, so whatever it
        // said about writing the profile onto the test case is repeated here. A write that
        // failed used to be reported only there — that is, nowhere the tester would see it.
        String note = customers.profileNote();
        boolean recorded = customers.profileRecorded();
        show(recorded ? OK_BG : WARN_BG, recorded ? OK_FG : WARN_FG,
            "✔ Kontonummer " + kontonummer
                + " kopiert — sie liegt in der Zwischenablage und kann mit Strg+V eingefügt "
                + "werden.\n" + note);
        goTo(2);
    }

    private void skipCustomer() {
        customerNotNeeded = true;
        account = null;
        show(WARN_BG, WARN_FG,
            "Weiter ohne Testkunden — für diesen Testfall wurde kein Kunde ausgewählt.");
        goTo(2);
    }

    /**
     * Says out loud when the customer list cannot be trusted, instead of letting an empty
     * or shrunken table look like "there is nobody like that".
     */
    private void updateCustomerNote() {
        if (customerNote == null) {
            return;
        }
        int offered = customers.offeredRowCount();
        int withheld = customers.skippedRowCount();
        if (offered == 0) {
            customerNote.setText("Es stehen keine Testkunden zur Verfügung. Bitte bei der "
                + "Testautomatisierung melden — oder unten angeben, dass dieser Testfall "
                + "keinen Testkunden braucht.");
            customerNote.setBackground(WARN_BG);
            customerNote.setForeground(WARN_FG);
            customerNote.setVisible(true);
        } else if (withheld > 0) {
            // A correctly converted export has none of these. A count above zero therefore
            // points at the FILE — converted with an older tool — and not at the data, which
            // is what the first wording accidentally implied.
            customerNote.setText("<html>" + withheld + " von " + (offered + withheld)
                + " Zeilen der Testdaten-Datei haben nicht die erwartete Form und werden hier "
                + "nicht angeboten. Das deutet auf eine veraltet erzeugte Datei hin — bitte "
                + "bei der Testautomatisierung eine neue anfordern.</html>");
            customerNote.setBackground(WARN_BG);
            customerNote.setForeground(WARN_FG);
            customerNote.setVisible(true);
        } else {
            customerNote.setVisible(false);
        }
    }

    private String renderSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("SO GEHT ES JETZT WEITER\n");
        sb.append("1. Auf \"Aufnahme starten\" klicken.\n");
        if (account != null) {
            sb.append("2. Die Kontonummer mit Strg+V dort einfügen, wo die Anwendung danach fragt.\n");
            sb.append("3. Den Testfall ganz normal durchführen — alles wird mitgeschnitten.\n");
            sb.append("4. Zum Schluss hier auf \"Aufnahme beenden\" klicken. Der Knopf steht dann "
                + "an derselben Stelle; Sie müssen den Bildschirm nicht wechseln.\n");
        } else {
            sb.append("2. Den Testfall ganz normal durchführen — alles wird mitgeschnitten.\n");
            sb.append("3. Zum Schluss hier auf \"Aufnahme beenden\" klicken. Der Knopf steht dann "
                + "an derselben Stelle; Sie müssen den Bildschirm nicht wechseln.\n");
        }

        sb.append("\nIHRE AUSWAHL\n");
        if (chosenCase == null) {
            sb.append("Testfall:     (keiner)\n");
        } else {
            sb.append("Testfall:     ").append(chosenCase.adoId()).append(" — ")
                .append(chosenCase.title()).append('\n');
            sb.append("Bereich:      ")
                .append(chosenCase.suiteName().isBlank() ? "(keiner)" : chosenCase.suiteName())
                .append('\n');
        }
        if (account != null) {
            sb.append("Kontonummer:  ").append(account).append("   (liegt in der Zwischenablage)\n");
        } else if (customerNotNeeded) {
            sb.append("Kontonummer:  keine — Sie haben angegeben, dass dieser Testfall keinen "
                + "Testkunden braucht.\n");
        }

        sb.append("\nWAS DABEI PASSIERT\n");
        sb.append("Die Aufnahme wird direkt in diesem Testfall gespeichert. Es wird nicht noch "
            + "einmal gefragt, wohin sie gehört.\n");

        if (chosenCase != null && !chosenCase.preconditions().isBlank()) {
            sb.append("\nNICHT VERGESSEN — DAS VERLANGT DER TESTFALL\n");
            sb.append(chosenCase.preconditions()).append('\n');
        }
        return sb.toString();
    }

    /**
     * The one recording button, doing whichever of the two things is actually possible.
     *
     * <p>The predecessor of this method called {@link StudioRecorder#start()} and then said
     * "✔ Die Aufnahme wurde gestartet" whatever came back. Studio's {@code record()} is a
     * toggle, so a second press ended the recording — under a success banner, with no way
     * for the tester to tell. Three things stop that happening again: the decision of which
     * call to make comes from Studio's live state rather than from this panel's memory; the
     * message comes from the {@link StudioRecorder.Result}, which is only {@code ok} when the
     * state really moved; and the state is read once more afterwards, so the screen shows
     * what is, not what was requested.
     *
     * <p>The attempt runs after the banner has been put up, so the screen has already changed
     * before anything can block.
     */
    private void pressRecordButton() {
        boolean stopping = StudioRecorder.state() == StudioRecorder.State.RECORDING;
        show(WARN_BG, WARN_FG, stopping ? "Aufnahme wird beendet…" : "Aufnahme wird gestartet…");
        record.setEnabled(false);
        SwingUtilities.invokeLater(() -> {
            StudioRecorder.Result result = stopping ? StudioRecorder.stop() : StudioRecorder.start();
            if (result.ok()) {
                show(OK_BG, OK_FG, result.message());
            } else {
                show(WARN_BG, WARN_FG, result.message());
                banner.setToolTipText(result.detail());
            }
            record.setEnabled(true);
            // One event later: stopping queues setRecordingState(false) with invokeLater, so
            // the truth is only readable after that has run. Re-reading here is what turns
            // "was requested" into "is".
            SwingUtilities.invokeLater(this::refreshRecorderState);
        });
    }

    /**
     * Puts Studio's recording state on screen — on the button, and in words beside it.
     *
     * <p>Called on every step change, after every press, and once a second while step 3 is
     * on screen. The button's label is a promise about what pressing it will do, so it is
     * derived from the same state the press is decided from and from nothing else.
     */
    private void refreshRecorderState() {
        if (record == null || recorderState == null) {
            return;
        }
        StudioRecorder.Reading reading = StudioRecorder.look();
        StudioRecorder.State state = reading.state();
        // Cleared before, not after: only two branches below put a technical reason here, and a
        // reason left standing from a state that is over is a reason about nothing.
        recorderState.setToolTipText(null);
        switch (state) {
            case RECORDING -> {
                record.setText(BTN_STOP);
                record.setEnabled(true);
                record.setToolTipText("Beendet die laufende Aufnahme — hier, nicht in Test Design.");
                // A status word. Nothing behind it to lose, so no reordering is called for.
                paintOneLine(recorderState, LIVE_BG, LIVE_FG,
                    "● Die Aufnahme läuft. Alles wird mitgeschnitten.");
            }
            case STARTING -> {
                record.setText(BTN_RECORD);
                record.setEnabled(false);
                record.setToolTipText("Die Aufnahme wird gerade gestartet.");
                paintOneLine(recorderState, WARN_BG, WARN_FG,
                    "Die Aufnahme wird gestartet — der Browser öffnet sich gleich.");
            }
            case IDLE -> {
                record.setText(BTN_RECORD);
                record.setEnabled(true);
                record.setToolTipText("Startet die Aufnahme in diesem Testfall.");
                paintOneLine(recorderState, OK_BG, OK_FG, "Zurzeit läuft keine Aufnahme.");
            }
            case UNREADABLE -> {
                // The button is not offered at all here, and that is the point. It cannot start
                // a recording without sending the toggle, and the toggle on a Studio whose state
                // is unreadable is a coin flip whose tails side ends a running recording and
                // kills the recorder's browser. A button that cannot keep its promise should not
                // make it — so it says why, next to itself, in the sentence the refusal uses.
                record.setText(BTN_RECORD);
                record.setEnabled(false);
                record.setToolTipText(StudioRecorder.UNREADABLE_HELP);
                // The line clips, so the step the tester can take comes first and the reason
                // second: narrowing must cost the explanation, never the way out.
                String unreadable = "✖ Aufnahme von hier aus nicht möglich — bitte die "
                    + "Aufnahme-Schaltfläche oben in der Werkzeugleiste benutzen. Diese "
                    + "Studio-Version meldet den Aufnahme-Zustand nicht.";
                paintOneLine(recorderState, LIVE_BG, LIVE_FG, unreadable);
                // Nothing was attempted here, so there is no banner and no result to carry the
                // technical half. Whoever the tester rings up needs to know WHICH name this
                // build is missing, and this line is the only place left to put it. It goes
                // BEHIND the sentence rather than over it — the tooltip is also where the half
                // of the sentence a narrow window ate has to remain readable.
                recorderState.setToolTipText(unreadable + "  [" + reading.detail() + "]");
            }
            case NOT_READY -> {
                // Transient by construction: Studio is still building itself, or answered with
                // an exception this once. The panel re-reads every second, so the button comes
                // back by itself and the tester is told to wait rather than to give up.
                record.setText(BTN_RECORD);
                record.setEnabled(false);
                record.setToolTipText(StudioRecorder.NOT_READY_HELP);
                String notReady = "Bitte einen Moment warten — Studio ist noch nicht so weit; "
                    + "der Aufnahme-Zustand ist gerade nicht lesbar.";
                paintOneLine(recorderState, WARN_BG, WARN_FG, notReady);
                recorderState.setToolTipText(notReady + "  [" + reading.detail() + "]");
            }
            default -> {
                // No Studio at all: the harness, or a panel opened outside Studio. Claiming
                // "keine Aufnahme" here would be a statement about something we cannot see.
                record.setText(BTN_RECORD);
                record.setEnabled(true);
                record.setToolTipText(StudioRecorder.FALLBACK);
                // A statement, not an instruction: there is nothing for the tester to do about
                // a missing Studio from inside a panel that is not in one. The verdict is
                // already at the front, so the clip only ever eats the restatement.
                paintOneLine(recorderState, WARN_BG, WARN_FG,
                    "Kein Studio-Fenster gefunden — der Aufnahme-Zustand ist von hier aus "
                        + "nicht ablesbar.");
            }
        }
        updateStartUrlNote();
    }

    /**
     * Says which address the recorder will open, where that address comes from — and offers to
     * set it, which nothing else in the product does.
     *
     * <p>{@code RecorderSettings.setStartUrl} has no caller anywhere in the INGenious core and
     * {@code ProjectSettings.save()} does not save the recorder settings either, so on a fresh
     * install the value is empty and a properties file edited by hand was the only way to fill
     * it. The tester pressed <em>Aufnahme starten</em>, got a blank browser, and the handout
     * answered with a sentence telling them to report it — a problem nobody they could reach
     * was able to fix. The field below this line is that fix: same setting, same file, typed
     * instead of hand-edited.
     *
     * <p><b>The source is named, not just the address.</b> The core prefers an address supplied
     * for the individual test case over the project's, so an address that arrived that way
     * cannot be changed here — and a tester who was not told that would change the project
     * setting, see no difference, and report the wrong thing.
     *
     * <p>With no Studio to ask, this says nothing at all and offers nothing: there is no
     * project in view to make a claim about and nowhere to write to.
     */
    private void updateStartUrlNote() {
        if (startUrlNote == null) {
            return;
        }
        StartAddress.Effective address = StartAddress.effective();
        if (address.source() == StartAddress.Source.UNKNOWN) {
            startUrlNote.setVisible(false);
            startUrlEditor.setVisible(false);
            return;
        }
        if (address.source() == StartAddress.Source.NONE) {
            address = restoreRememberedStartUrl();
        }
        switch (address.source()) {
            // The binding fact leads, the address follows, the explanation goes last: a tester
            // who reads only the beginning of this line must still learn that typing below will
            // not change what the browser opens — that is the thing they would otherwise report
            // as a defect.
            case TEST_CASE -> paintOneLine(startUrlNote, OK_BG, OK_FG,
                "Adresse des Testfalls — hat Vorrang. Der Browser öffnet: " + address.url()
                    + (address.projectUrl() == null || address.projectUrl().isBlank()
                        ? " — die Projekt-Adresse unten wird für diesen Testfall nicht verwendet."
                        : " — die Projekt-Adresse (" + address.projectUrl() + ") wird für diesen "
                            + "Testfall nicht verwendet."));
            case PROJECT -> paintOneLine(startUrlNote, OK_BG, OK_FG,
                "Der Browser öffnet: " + address.url() + " — im Projekt hinterlegt, gilt für "
                    + "jede Aufnahme in diesem Projekt.");
            // The one branch that asks the tester for something. Rendered at 900 pixels the
            // instruction used to sit on a second line that was sliced through the middle, so
            // it now stands in front of the reason it exists.
            default -> paintOneLine(startUrlNote, WARN_BG, WARN_FG,
                "Bitte hier eintragen — für die Aufnahme ist keine Start-Adresse hinterlegt: "
                    + "sonst öffnet sich der Browser leer und Sie müssen die Adresse jedes Mal "
                    + "selbst eingeben.");
        }
        startUrlNote.setVisible(true);
        // Seeded only while untouched: this runs once a second, and overwriting half-typed text
        // would make the field unusable.
        if (startUrlField.getText().isEmpty() && address.projectUrl() != null) {
            startUrlField.setText(address.projectUrl());
        }
        startUrlEditor.setVisible(true);
    }

    /**
     * Puts back the address the panel stored last time, when the project has lost it.
     *
     * <p><b>This is the other half of the half-successful write.</b> {@code PropUtils} cannot
     * report a failed save (<a href="https://github.com/ing-bank/INGenious/issues/322">#322</a>),
     * so a tester who typed an address can be told, truthfully, that it is in force now and gone
     * after the next restart. Three answers were possible and this is the third: <em>refusing</em>
     * the write would discard a value the recorder really is about to use, which is its own false
     * report; <em>telling the tester to retype it</em> is the excuse the observation sheet is
     * about; <em>putting it back</em> is a step, and it is one the tool can take instead of the
     * tester. So the panel keeps its own copy and reconciles here.
     *
     * <p><b>Only into an empty setting.</b> A project that has an address of its own is never
     * overwritten — the project is the authority and a remembered value must not fight it. This
     * therefore runs exactly when the address really did evaporate.
     *
     * <p><b>Once per panel.</b> This is on a one-second poll; a project that refuses the write
     * every time would otherwise be rewritten every second, and would repaint its own answer over
     * whatever the tester was reading.
     *
     * <p>And it reports what it achieved, not what it attempted: the restore goes through the
     * same verified write, so it can come back as stored, as in force for this session only, or
     * as not applied at all, and each says so.
     *
     * @return what the recorder will open after the attempt — re-read, never assumed
     */
    private StartAddress.Effective restoreRememberedStartUrl() {
        StartAddress.Effective before = StartAddress.effective();
        if (startUrlRestoreTried) {
            return before;
        }
        String key = startUrlMemoryKey();
        String remembered = StartAddressMemory.remembered(key);
        if (remembered == null || StartAddress.problem(remembered) != null) {
            return before;
        }
        startUrlRestoreTried = true;
        StudioRecorder.Write write = StudioRecorder.setProjectStartUrl(remembered);
        LOG.log(Level.INFO, "Start address restore {0}: {1}",
            new Object[] { write.store(), write.detail() });
        String machine = StartAddress.host(remembered);
        switch (write.store()) {
            case PROJECT_FILE -> paintOneLine(startUrlSaveState, OK_BG, OK_FG,
                "✔ Rechner: " + machine + " — Start-Adresse wieder eingetragen, Sie müssen "
                    + "nichts tun. Bitte einmal melden, dass die Adresse im Projekt nicht "
                    + "stehen bleibt: sie war beim Öffnen verschwunden und wurde vom Ablauf "
                    + "erneut gesetzt (" + remembered + ").");
            case PROJECT_SAVED_UNVERIFIED, PROJECT_MEMORY -> paintOneLine(startUrlSaveState,
                WARN_BG, WARN_FG,
                "○ Rechner: " + machine + " — Start-Adresse wieder gesetzt, für diese Sitzung; "
                    + "Sie müssen nichts tun, der Ablauf trägt sie bei jedem Start erneut ein. "
                    + "Bitte einmal bei der Testautomatisierung melden: dauerhaft speichern "
                    + "lässt sich " + remembered + " in diesem Projekt nicht.");
            default -> paintOneLine(startUrlSaveState, WARN_BG, WARN_FG,
                "○ Start-Adresse ließ sich nicht wieder eintragen — unten erneut eintragen und "
                    + "auf „Adresse übernehmen“ klicken; hilft das nicht, die Aufnahme starten "
                    + "und die Adresse einmal von Hand in die Adresszeile des Browsers "
                    + "eintippen. Bitte melden. Zuletzt eingetragen war " + remembered + ".");
        }
        startUrlSaveState.setToolTipText(
            startUrlSaveState.getText() + "  [" + write.detail() + "]");
        return StartAddress.effective();
    }

    /**
     * The name this panel files a remembered start address under: the open project's settings
     * file, or {@code ""} when this build will not name it.
     *
     * <p><b>No fallback name on purpose.</b> A shared entry for "the project that would not say
     * which file it writes" would hand one project's address to a different one — and the
     * restore writes, so that is not a cosmetic mix-up. Without a project identity there is no
     * remembering and no restoring, and {@link #keptSentence} says so out loud instead of
     * claiming a copy that was never filed.
     */
    private static String startUrlMemoryKey() {
        String location = StudioRecorder.projectSettingsLocation();
        return location == null ? "" : location.trim();
    }

    /**
     * What the panel can say about the machine in an address, given only what it stored before.
     *
     * <p>It cannot say which environment a hostname is — that needs a hostname-to-environment
     * list, and there is none on any machine this runs on (see {@link StartAddress}). What it
     * can say is whether the machine changed since last time, and the third answer is the
     * honest one: nothing was stored here before, so there is nothing to compare against.
     */
    private static String machineVerdict(StartAddress.Machine verdict, String previous) {
        return switch (verdict) {
            case SAME -> "gleicher Rechner wie zuletzt.";
            case CHANGED -> "ACHTUNG: anderer Rechner als zuletzt (zuletzt: "
                + StartAddress.host(previous) + ").";
            default -> "hier noch nie eine Adresse gespeichert, kein Vergleich möglich.";
        };
    }

    /**
     * What follows from that comparison, separated from it on purpose.
     *
     * <p>These lines clip rather than wrap, and this project has already found what that costs:
     * the verdict and the step have to be at the front, so the advice goes at the back where
     * losing it costs an elaboration and never an answer. It is still in the tooltip, which
     * carries the whole line.
     */
    private static String machineAdvice(StartAddress.Machine verdict) {
        return switch (verdict) {
            case SAME -> "";
            case CHANGED -> " Wenn Sie die Umgebung nicht absichtlich gewechselt haben: die "
                + "vorherige Adresse wieder eintragen und noch einmal übernehmen.";
            default -> " Welche Umgebung ein Rechnername ist, kann das Programm nicht "
                + "feststellen — bitte einmal selbst prüfen.";
        };
    }

    /**
     * What to say about the panel's own copy of a half-stored address — and only after the copy
     * has been read back off disk.
     *
     * <p>The whole point of the copy is to survive a machine whose settings file cannot be
     * written; a state directory that cannot be written either is therefore not a theoretical
     * case, and claiming "the panel has memorised it" there would be the same defect one level
     * down. So {@link StartAddressMemory#remember} verifies, and this says whichever of the two
     * actually happened.
     *
     * @param kept what {@code remember} returned
     * @param whenKept the sentence describing what the panel will do with the copy
     */
    private static String keptSentence(boolean kept, String whenKept) {
        return kept ? whenKept
            : "Der Ablauf konnte sie sich auch nicht selbst merken — nach einem Neustart von "
                + "Studio müssen Sie sie hier erneut eintragen.";
    }

    /** The same two answers, short enough for the banner's single sliceable line. */
    private static String keptBanner(boolean kept) {
        return kept ? "der Ablauf trägt sie beim nächsten Start wieder ein. Bitte melden."
            : "nach einem Neustart von Studio bitte erneut eintragen. Bitte melden.";
    }

    /**
     * Stores what was typed as the project's recorder start address.
     *
     * <p>Checked before it is stored, and the check is about shape only: an absolute
     * {@code http}/{@code https} address with a host. Storing anything else would produce a
     * setting that reads as configured and still opens a blank page, because the core applies
     * the same rule when it starts the recorder and silently ignores what fails it.
     *
     * <p><b>The machine is compared, not merely displayed.</b> This line used to name the host
     * and then hand the whole question to the tester — <em>"bitte prüfen, ob das die richtige
     * Umgebung ist"</em> — which is an instruction with no reference point: the environments
     * differ by hostname alone and the tester has nothing on screen to check the hostname
     * against. The panel now supplies the one reference it can have without configuration it
     * does not possess: the machine it stored here last time. Same, different, or nothing to
     * compare against — and the third is said as such. What it still cannot do is name which
     * environment a hostname belongs to; that limitation is documented in {@link StartAddress}
     * and is a limitation, not a step withheld.
     *
     * <p><b>The machine leads the line.</b> This line clips rather than wraps, so whatever is
     * at the end is what a narrow Studio window eats. The host used to sit mid-sentence behind
     * the full URL; it now comes first, where clipping cannot reach it.
     *
     * <p>No dialog, in either direction. A refusal and a success are both a coloured line that
     * is already on screen, which is how everything else on this panel reports.
     */
    private void pressStartUrlSaveButton() {
        String typed = startUrlField.getText() == null ? "" : startUrlField.getText().trim();
        String problem = StartAddress.problem(typed);
        if (problem != null) {
            paintOneLine(startUrlSaveState, LIVE_BG, LIVE_FG,
                "✖ Nicht übernommen: " + problem + " Es wurde nichts geändert. Beispiel: "
                    + "https://rechnername/pfad");
            show(LIVE_BG, LIVE_FG, "✖ Die Start-Adresse wurde NICHT übernommen: " + problem);
            return;
        }

        // Read BEFORE the write, or the comparison would be against the value just stored.
        String key = startUrlMemoryKey();
        String previous = StartAddressMemory.remembered(key);
        StartAddress.Machine changed = StartAddress.compareMachine(previous, typed);
        String machineNote = machineVerdict(changed, previous);
        String advice = machineAdvice(changed);

        StudioRecorder.Write write = StudioRecorder.setProjectStartUrl(typed);
        LOG.log(Level.INFO, "Start address write {0}: {1}",
            new Object[] { write.store(), write.detail() });
        String machine = StartAddress.host(typed);
        switch (write.store()) {
            case PROJECT_FILE -> {
                StartAddressMemory.remember(key, typed);
                // Green only when the machine was actually confirmed unchanged. A tester scans
                // colour before words, and a green line saying "ACHTUNG: anderer Rechner" is a
                // difference made easy to miss — which is the whole defect. Amber is this
                // panel's family for "nothing failed, look at this anyway", and it is also the
                // honest colour for the first save in a project, where there was nothing to
                // compare against and so nothing was checked. The ✔ and the word "gespeichert"
                // stay, so no failure is implied.
                boolean confirmed = changed == StartAddress.Machine.SAME;
                Color bg = confirmed ? OK_BG : WARN_BG;
                Color fg = confirmed ? OK_FG : WARN_FG;
                // Deliberately NOT "der Browser öffnet ab jetzt …". This line reports what was
                // stored; what will actually open is the line above, and the two are not always
                // the same — an address belonging to the chosen test case wins over the project
                // one. Rendered, the old wording sat in green immediately under a note saying
                // the test case's address had precedence, and the green one is the one a tester
                // believes. A line may only claim what it achieved.
                paintOneLine(startUrlSaveState, bg, fg,
                    "✔ Rechner: " + machine + " — " + machineNote + " Als Projekt-Adresse "
                        + "gespeichert: " + typed + "." + advice + " Welche Adresse der Browser "
                        + "wirklich öffnet, steht in der Zeile darüber. Gespeichert in "
                        + write.location());
                show(bg, fg, "✔ Projekt-Adresse gespeichert — Rechner: " + machine
                    + " — " + machineNote);
            }
            case PROJECT_SAVED_UNVERIFIED -> {
                boolean kept = StartAddressMemory.remember(key, typed);
                paintOneLine(startUrlSaveState, WARN_BG, WARN_FG,
                    "○ Rechner: " + machine + " — " + machineNote + " "
                        + keptSentence(kept, "Fehlt die Adresse beim nächsten Studio-Start, "
                            + "trägt der Ablauf sie von allein wieder ein — Sie müssen dafür "
                            + "nichts tun.")
                        + " Bitte einmal bei der Testautomatisierung melden: die Adresse wurde "
                        + "an das Projekt übergeben, aber es ließ sich nicht nachlesen, ob sie "
                        + "in den Projekt-Einstellungen wirklich angekommen ist." + advice);
                show(WARN_BG, WARN_FG, "○ Übergeben, aber nicht nachweisbar gespeichert — "
                    + keptBanner(kept));
            }
            case PROJECT_MEMORY -> {
                boolean kept = StartAddressMemory.remember(key, typed);
                paintOneLine(startUrlSaveState, WARN_BG, WARN_FG,
                    "○ Rechner: " + machine + " — " + machineNote + " Für diese Sitzung "
                        + "übernommen. "
                        + keptSentence(kept, "Beim nächsten Studio-Start ist sie im Projekt "
                            + "wieder weg; der Ablauf trägt sie dann von allein erneut ein — "
                            + "Sie müssen dafür nichts tun.")
                        + " Bitte einmal bei der Testautomatisierung melden: in die "
                        + "Projekt-Datei ließ sich " + typed + " nicht schreiben." + advice);
                show(WARN_BG, WARN_FG, "○ Im Projekt nur bis Studio geschlossen wird — "
                    + keptBanner(kept));
            }
            default -> {
                // The one refusal on this panel that used to end in "melden" and nothing else.
                // There is a step, and it is the one the whole product did before this field
                // existed: type the address into the browser once, by hand. The recording does
                // not care where the browser started.
                //
                // The step comes FIRST, ahead of the reason, because this line clips rather
                // than wraps: what is at the end is what a narrow window eats, and losing the
                // step would put the dead end straight back.
                paintOneLine(startUrlSaveState, LIVE_BG, LIVE_FG,
                    "✖ NICHT übernommen — Aufnahme trotzdem starten und die Adresse einmal von "
                        + "Hand in die Adresszeile des Browsers eintippen; aufgezeichnet wird "
                        + "alles. Vorher prüfen, ob unter Test Design überhaupt ein Projekt "
                        + "geöffnet ist, und noch einmal auf „Adresse übernehmen“ klicken. Die "
                        + "Adresse ließ sich in diesem Projekt nicht ablegen — bitte melden.");
                // One line, verdict then step. HTML with line breaks would re-wrap here and be
                // sliced through the middle, which this panel has already paid for once.
                show(LIVE_BG, LIVE_FG, "✖ Start-Adresse NICHT übernommen — Aufnahme trotzdem "
                    + "starten und die Adresse im Browser von Hand eintippen. Bitte melden.");
            }
        }
        // The technical half goes on the tooltip, as everywhere else on this panel: the sentence
        // is for the tester, the reason is for whoever they ring up.
        startUrlSaveState.setToolTipText(startUrlSaveState.getText() + "  [" + write.detail() + "]");
        updateStartUrlNote();
    }

    /**
     * Puts one ADO upload outcome on screen — in step 3, and in the banner above every step.
     *
     * <p>This is the last step of the tester's job and it used to be the only one that reported
     * nowhere: {@code AdoUpload} wrote a line into Studio's {@code log.txt} and the handout had
     * to tell testers, in as many words, to go and look in Azure DevOps themselves. So the
     * outcome is now said twice at once, exactly like every other state on this panel — because
     * the three defects this project has paid for were all a true message said too quietly.
     *
     * <p><b>Every state in {@link AdoUploadStatus.State} has its own arm here, and that is a
     * rule rather than a coincidence.</b> No count is given, because a count is a thing that
     * goes stale and this javadoc has been wrong about it once already. A silent success is
     * indistinguishable from a feature that never ran, an upload that was switched off is not an
     * error, and "there was nothing to upload" is neither — a tester who cannot tell those apart
     * has not been told anything. {@link AdoUploadStatus.State#RUNNING} arrives <em>before</em>
     * the wait, which may be seven minutes long, so it says so rather than leaving the screen
     * looking finished.
     *
     * <p>{@code default} is therefore not a bucket for states nobody got round to. It is the
     * failure arm, and every state that lands there by accident is shown to the tester as
     * <em>"Ihr Ergebnis steht NICHT in Azure DevOps"</em>. That has now cost two defects, both
     * the same shape and both the opposite of a silent success — a true state painted red:
     * <ul>
     *   <li>{@link AdoUploadStatus.State#DRY_RUN} — a {@code --dry-run} writes nothing
     *       <em>by design</em>, and the panel told the tester in bold German that their
     *       morning's work had not reached Azure DevOps.
     *   <li>{@link AdoUploadStatus.State#SIGN_IN_REQUIRED} — the upload has stopped to ask
     *       somebody a question and <em>resumes by itself</em> once they answer it. Painting
     *       that red sends a tester to fetch an engineer for a problem that is resolving while
     *       they read, and it is the one arm here that deliberately does <b>not</b> say the
     *       result is missing from Azure DevOps, because it is on its way.
     * </ul>
     * Both are shown in the family the "nothing has gone wrong" states use — amber, and a
     * {@code ○} or {@code 🔑} rather than a {@code ✖}.
     *
     * <p>Always called on the event dispatch thread; the listener marshals.
     */
    private void showUpload(AdoUploadStatus.Event event) {
        if (uploadState == null || event == null) {
            return;
        }
        String detail = event.message();
        String verdict;
        Color background;
        Color foreground;
        switch (event.state()) {
            case RUNNING -> {
                background = WARN_BG;
                foreground = WARN_FG;
                verdict = "⏳ ADO-Upload läuft — das kann einige Minuten dauern.";
                detail = "⏳ " + detail + " Das kann einige Minuten dauern — Sie können hier "
                    + "warten.";
            }
            case OK -> {
                background = OK_BG;
                foreground = OK_FG;
                verdict = "✔ ADO-Upload OK — Ihr Ergebnis ist in Azure DevOps angekommen.";
                detail = "✔ " + detail + " Ihr Ergebnis ist in Azure DevOps angekommen.";
            }
            case SKIPPED -> {
                background = WARN_BG;
                foreground = WARN_FG;
                verdict = "○ ADO-Upload ÜBERSPRUNGEN — es wurde nichts nach Azure DevOps "
                    + "hochgeladen.";
                detail = "○ " + detail + " Für diesen Testfall wurde nichts nach Azure DevOps "
                    + "hochgeladen.";
            }
            case OFF -> {
                // Weighed and changed rather than left alone. It is genuinely not an error and
                // it stays amber — but "the upload is switched off" is a state with no
                // consequence and no step, and the consequence is the one thing about it a
                // tester needs: their result is not in Azure DevOps and their test case is not
                // ticked off there. Whether that is fine is a question only they can answer,
                // so the step is conditional rather than an instruction to report.
                background = WARN_BG;
                foreground = WARN_FG;
                verdict = "○ ADO-Upload AUS — kein Fehler; Ihr Ergebnis steht deshalb nicht in "
                    + "Azure DevOps. Soll es dort stehen: bitte melden.";
                detail = "○ " + detail + " Das ist kein Fehler — der Upload ist ausgeschaltet. "
                    + "Ihr Ergebnis steht deshalb nicht in Azure DevOps. Soll Ihr Testfall dort "
                    + "abgehakt werden, bitte melden — dann muss der Upload eingeschaltet "
                    + "werden. Sonst nichts zu tun.";
            }
            case DRY_RUN -> {
                background = WARN_BG;
                foreground = WARN_FG;
                verdict = "○ ADO-Upload PROBELAUF — kein Fehler, es wurde bewusst nichts "
                    + "hochgeladen.";
                detail = "○ " + detail + " Das ist kein Fehler — bei einem Probelauf wird "
                    + "absichtlich nichts nach Azure DevOps geschrieben.";
            }
            case SIGN_IN_REQUIRED -> {
                // No "Ihr Ergebnis steht NICHT in Azure DevOps" here, on purpose: the upload
                // continues by itself as soon as the tester signs in, so saying the result is
                // missing would send them chasing a problem that is fixing itself while they
                // read it. The message from the hook is already tester-ready and names the test
                // case, so nothing is added to it beyond the key.
                background = WARN_BG;
                foreground = WARN_FG;
                verdict = "🔑 Anmeldung bei Azure DevOps nötig — bitte im geöffneten Fenster "
                    + "anmelden.";
                detail = "🔑 " + detail;
            }
            default -> {
                background = LIVE_BG;
                foreground = LIVE_FG;
                verdict = "✖ ADO-Upload FEHLER — Ihr Ergebnis steht NICHT in Azure DevOps. "
                    + "Bitte bei der Testautomatisierung melden.";
                detail = "✖ " + detail + " Ihr Ergebnis steht NICHT in Azure DevOps — bitte bei "
                    + "der Testautomatisierung melden.";
            }
        }
        paintOneLine(uploadState, background, foreground, detail);
        // The banner gets the verdict and not the detail, deliberately: it is one HTML line that
        // re-wraps in a narrow window while keeping its one-line height, so a long sentence
        // there comes out sliced in half. The hook's own words — run id, exit code, reason —
        // live on the step-3 line above, which clips cleanly and carries the tooltip.
        show(background, foreground, verdict);
    }

    /**
     * Says what the abgeben button is for — and says beforehand when it cannot work.
     *
     * <p>Called when step 3 is built and whenever it is opened, but never after an attempt has
     * reported: overwriting the answer with the idle sentence would take the outcome off the
     * screen, which is the defect this panel has already paid for three times.
     *
     * <p>The one thing worth checking before the press is whether the packaging tool is on the
     * machine at all — that is a file test, not a process, and a tester who is going to be told
     * "not possible here" should be told it before they finish their work rather than after.
     * Node itself can only be found out by starting it, which happens on the press.
     */
    private void refreshCheckState() {
        if (checkState == null || checkReported) {
            return;
        }
        if (SelectorCheck.tool() == null) {
            check.setEnabled(false);
            paintOneLine(checkState, LOCKED_BG, Color.BLACK,
                "○ Prüfen ist auf diesem Rechner nicht eingerichtet. Die Aufnahme lässt sich "
                    + "trotzdem abgeben.");
            return;
        }
        paintOneLine(checkState, LOCKED_BG, Color.BLACK,
            "Wenn die Aufnahme fertig ist: hier prüfen, ob jeder Schritt genau ein Element "
                + "trifft — solange die Anwendung noch offen ist.");
    }

    /**
     * The prüfen button: one press, one answer about the page that is open right now.
     *
     * <p>The probe runs on its own daemon thread because it starts a browser and loads a page.
     *
     * <p>A recording that may still be running is refused for the same reason the hand-off
     * refuses it, one layer over: the recorder writes Object-Repository entries as the tester
     * clicks, so a check taken across a live recording answers about half a recording and says
     * "geprüft" while doing it. The two buttons therefore use the same refusal states, so they
     * cannot say different things about the same Studio.
     *
     * <p>{@link StudioRecorder.State#NO_STUDIO} deliberately does not refuse: it is the state an
     * engineer's own machine and the harness are in, and there is no recorder of ours writing
     * into that project.
     */
    private void pressCheckButton() {
        StudioRecorder.Reading reading = StudioRecorder.look();
        String refusal = switch (reading.state()) {
            case RECORDING, STARTING -> "○ NICHT geprüft: bitte zuerst auf \"" + BTN_STOP
                + "\" klicken und danach noch einmal auf \"" + BTN_CHECK + "\". Die Aufnahme "
                + "läuft noch — solange kommen weitere Schritte dazu, die dann nicht geprüft "
                + "wären.";
            case NOT_READY -> "○ NICHT geprüft: bitte einen Moment warten und dann noch einmal "
                + "auf \"" + BTN_CHECK + "\" klicken. Studio meldet gerade nicht, ob noch eine "
                + "Aufnahme läuft.";
            case UNREADABLE -> "○ NICHT geprüft: bitte oben in der Werkzeugleiste prüfen, ob die "
                + "Aufnahme-Schaltfläche rot ist, die Aufnahme dort beenden und danach noch "
                + "einmal auf \"" + BTN_CHECK + "\" klicken. Diese Studio-Version meldet den "
                + "Aufnahme-Zustand nicht.";
            default -> null;
        };
        if (refusal != null) {
            checkReported = true;
            paintOneLine(checkState, WARN_BG, WARN_FG, refusal);
            checkState.setToolTipText(refusal + "  [" + reading.detail() + "]");
            LOG.log(Level.INFO, "Selector check refused: {0}", reading.detail());
            show(WARN_BG, WARN_FG, "○ Es wurde nichts geprüft.");
            return;
        }

        // The address the recorder itself opened, so the page state is the one the recording was
        // taken against. Null is not substituted for: SelectorCheck answers NO_URL and says why,
        // because a count taken on a page nobody chose is a count about the wrong page.
        String url = StudioRecorder.projectStartUrl();
        // The page this recording wrote, when Studio will say. When it will not, every page is
        // checked instead — broader, and broader here only means more honest "not present here".
        String page = StudioRecorder.liveRecordingPageName();

        checkRunning = true;
        check.setEnabled(false);
        paintOneLine(checkState, WARN_BG, WARN_FG,
            "⏳ Die Aufnahme wird geprüft — die Seite wird dafür einmal geöffnet.");
        show(WARN_BG, WARN_FG, "⏳ Die Aufnahme wird geprüft …");

        Thread worker = new Thread(() -> {
            SelectorCheck.Result result;
            try {
                result = SelectorCheck.check(url, page, null);
            } catch (RuntimeException | LinkageError ex) {
                LOG.log(Level.WARNING, "Selector check failed: " + ex, ex);
                result = new SelectorCheck.Result(SelectorCheck.Outcome.FAILED,
                    "○ NICHT geprüft: die Prüfung ist unerwartet fehlgeschlagen. Bitte bei der "
                        + "Testautomatisierung melden.",
                    String.valueOf(ex), null, java.util.List.of(), 0);
            }
            SelectorCheck.Result outcome = result;
            SwingUtilities.invokeLater(() -> {
                showCheck(outcome);
                check.setEnabled(true);
                // Flipped last, and inside the same event: whoever waits on this must find the
                // screen already updated when it clears.
                checkRunning = false;
            });
        }, "selector-check");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * One check on screen, in step 3 and in the banner above every step.
     *
     * <p>Only {@link SelectorCheck.Outcome#UNIQUE} is painted green, and that is the whole point
     * of the colours here. The probe distinguishes "everything was checked and is unique" from
     * "something could not be checked", and the second one is the ordinary outcome — a recording
     * walks several screens and the probe opens one. Painting it green would tell a tester that
     * steps nobody looked at are fine.
     */
    private void showCheck(SelectorCheck.Result result) {
        if (checkState == null || result == null) {
            return;
        }
        checkReported = true;
        LOG.log(Level.INFO, "Selector check {0}: {1}",
            new Object[] { result.outcome(), result.detail() });
        String verdict = switch (result.outcome()) {
            case UNIQUE -> "✔ Geprüft — jeder Schritt trifft genau ein Element.";
            case AMBIGUOUS -> "✖ " + result.ambiguous().size() + " Schritt(e) treffen mehr als "
                + "ein Element — bitte nachbessern.";
            case CANNOT_TELL -> "○ Nur teilweise geprüft — das ist kein \"in Ordnung\".";
            default -> "○ Es wurde nichts geprüft.";
        };
        Color background = switch (result.outcome()) {
            case UNIQUE -> OK_BG;
            case AMBIGUOUS -> LIVE_BG;
            default -> WARN_BG;
        };
        Color foreground = switch (result.outcome()) {
            case UNIQUE -> OK_FG;
            case AMBIGUOUS -> LIVE_FG;
            default -> WARN_FG;
        };
        paintOneLine(checkState, background, foreground, result.message());
        show(background, foreground, verdict);
    }

    private void refreshHandoffState() {
        if (handoffState == null || handoffReported) {
            return;
        }
        if (HandoffPack.tool() == null) {
            handoff.setEnabled(false);
            paintOneLine(handoffState, LIVE_BG, LIVE_FG,
                "✖ Abgabe hier nicht möglich: bitte den Ordner NICHT selbst zippen, sondern "
                    + "bei der Testautomatisierung melden. Die Abgabe ist auf diesem Rechner "
                    + "nicht eingerichtet.");
            return;
        }
        paintOneLine(handoffState, LOCKED_BG, Color.BLACK,
            "Wenn die Aufnahme fertig ist: hier ein Paket erstellen und die erzeugte Datei an "
                + "die Testautomatisierung senden. Den Ordner bitte nicht selbst zippen.");
    }

    /**
     * The abgeben button: one press, one package, one sentence about what happened.
     *
     * <p>Two things are deliberate. The packaging runs on its own daemon thread, because it
     * walks the whole project and hashes every file in it — the same mistake was found and
     * fixed in the ADO lifecycle, which ran on the event dispatch thread until a lane moved it
     * off. And a recording that may still be running is refused here rather than packaged
     * half-finished: the steps are still being written into the project while the recorder is
     * live.
     */
    private void pressHandoffButton() {
        if (refuseHandoff(StudioRecorder.look())) {
            return;
        }

        handoffRunning = true;
        handoff.setEnabled(false);
        // Said before the wait, not after it: a screen that says nothing while a project is
        // walked and hashed is a screen that looks broken.
        paintOneLine(handoffState, WARN_BG, WARN_FG,
            "⏳ Das Paket wird erstellt — bei großen Aufnahmen dauert das einen Moment.");
        show(WARN_BG, WARN_FG, "⏳ Das Paket wird erstellt …");

        Thread worker = new Thread(() -> {
            HandoffPack.Result result;
            try {
                result = HandoffPack.pack();
            } catch (RuntimeException | LinkageError ex) {
                LOG.log(Level.WARNING, "Handoff packaging failed: " + ex, ex);
                result = new HandoffPack.Result(HandoffPack.Outcome.FAILED,
                    "✖ Es wurde kein Paket erstellt: die Abgabe ist unerwartet fehlgeschlagen. "
                        + "Bitte den Ordner NICHT selbst zippen, sondern bei der "
                        + "Testautomatisierung melden.",
                    String.valueOf(ex), null);
            }
            HandoffPack.Result outcome = result;
            SwingUtilities.invokeLater(() -> {
                showHandoff(outcome);
                handoff.setEnabled(true);
                // Flipped last, and inside the same event: whoever waits on this must find the
                // screen already updated when it clears.
                handoffRunning = false;
            });
        }, "handoff-pack");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Whether the recorder's state forbids packaging — and, when it does, says so instead.
     *
     * <p>Packaging walks the project and hashes every file in it, so it is only truthful about a
     * project nobody is still writing into. A live recorder writes steps into
     * {@code TestPlan/} as the tester clicks, and a package taken across that produces a zip
     * whose manifest hashes describe a moment that no longer exists — handed over under
     * <em>"✔ Fertig"</em>, which is the one sentence this panel may never earn falsely.
     *
     * <p><b>The two states that mean "cannot tell" refuse for the same reason the record button
     * refuses them.</b> This used to check {@link StudioRecorder.State#RECORDING} and
     * {@link StudioRecorder.State#STARTING} only, so on a Studio whose state could not be read
     * it packaged — and the state it could not read is exactly the one that decides whether a
     * recorder is writing into the folder being packed. Not knowing is not permission: the same
     * rule, one layer over. And the split is the same split, so the two buttons cannot say
     * different things about the same Studio — {@link StudioRecorder.State#NOT_READY} is worth
     * waiting a moment for, {@link StudioRecorder.State#UNREADABLE} is worth reporting.
     *
     * <p>{@link StudioRecorder.State#NO_STUDIO} deliberately does <em>not</em> refuse. It is not
     * a Studio that will not answer, it is no Studio at all — no window, therefore no recorder
     * of ours writing into that project — and it is the state an engineer's own machine and the
     * harness are in. Refusing there would block the packaging of exactly the projects that are
     * finished.
     *
     * @return {@code true} when nothing was packaged and the screen already says why
     */
    private boolean refuseHandoff(StudioRecorder.Reading reading) {
        switch (reading.state()) {
            case RECORDING, STARTING -> {
                refusedHandoff(WARN_BG, WARN_FG,
                    "○ Kein Paket erstellt: bitte zuerst auf \"" + BTN_STOP + "\" klicken und "
                        + "danach noch einmal auf \"" + BTN_HANDOFF + "\". Die Aufnahme läuft "
                        + "noch — solange ist der Testfall nicht fertig.",
                    "○ Die Aufnahme läuft noch — es wurde kein Paket erstellt.",
                    reading.detail());
                return true;
            }
            case NOT_READY -> {
                // Transient: the panel re-reads Studio every second, so the honest instruction
                // is to wait rather than to go and do something else.
                refusedHandoff(WARN_BG, WARN_FG,
                    "○ Kein Paket erstellt: bitte einen Moment warten und dann noch einmal auf "
                        + "\"" + BTN_HANDOFF + "\" klicken. Studio meldet gerade nicht, ob noch "
                        + "eine Aufnahme läuft — solange lässt sich nicht sagen, ob der Testfall "
                        + "wirklich fertig ist.",
                    "○ Studio ist noch nicht so weit — es wurde kein Paket erstellt.",
                    reading.detail());
                return true;
            }
            case UNREADABLE -> {
                // Permanent for this Studio: waiting will not fix it, so the instruction is the
                // one thing that can — read the truth off Studio's own toolbar button — plus
                // the sentence that gets the build reported to somebody who can replace it.
                refusedHandoff(LIVE_BG, LIVE_FG,
                    "✖ KEIN Paket erstellt: bitte oben in der Werkzeugleiste prüfen, ob die "
                        + "Aufnahme-Schaltfläche rot ist, die Aufnahme dort beenden und danach "
                        + "noch einmal auf \"" + BTN_HANDOFF + "\" klicken. Diese Studio-Version "
                        + "meldet den Aufnahme-Zustand nicht — ein Paket aus einem Testfall, in "
                        + "den vielleicht noch geschrieben wird, ist unbrauchbar. Bitte bei der "
                        + "Testautomatisierung melden.",
                    "✖ Kein Paket erstellt — diese Studio-Version meldet den Aufnahme-Zustand "
                        + "nicht.",
                    reading.detail());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * One refusal on screen: the step-3 line, the banner, and the technical reason behind them.
     *
     * <p>The reason is appended to the line's own tooltip rather than replacing it. Nothing was
     * attempted, so there is no {@link HandoffPack.Result} to carry it and no log line to look
     * up — and whoever the tester rings up needs to know <em>which</em> name this Studio build
     * is missing, not merely that something was unreadable.
     */
    private void refusedHandoff(Color background, Color foreground, String line, String verdict,
            String detail) {
        // Latched like every other answer on this line: the idle hint must not paint over a
        // report of something that did not happen.
        handoffReported = true;
        paintOneLine(handoffState, background, foreground, line);
        handoffState.setToolTipText(line + "  [" + detail + "]");
        LOG.log(Level.INFO, "Handoff refused: {0}", detail);
        show(background, foreground, verdict);
    }

    /**
     * One packaging outcome, on screen — in step 3, and in the banner above every step.
     *
     * <p>Every outcome other than {@code OK} is red rather than amber, because the tester's work
     * has <em>not</em> been handed over: amber on this panel means "nothing happened, and that is
     * fine", and none of these is that. The running recording, which is genuinely "come back in a
     * minute", is the amber one.
     *
     * <p>Always called on the event dispatch thread.
     */
    private void showHandoff(HandoffPack.Result result) {
        if (handoffState == null || result == null) {
            return;
        }
        handoffReported = true;
        handoffZip = result.zip();
        LOG.log(Level.INFO, "Handoff {0}: {1}",
            new Object[] { result.outcome(), result.detail() });
        String verdict = switch (result.outcome()) {
            case OK -> "✔ Das Paket wurde erstellt — bitte diese eine Datei an die "
                + "Testautomatisierung senden.";
            case NO_NODE -> "✖ Es wurde kein Paket erstellt — die Abgabe ist auf diesem Rechner "
                + "nicht möglich.";
            case NO_TOOL -> "✖ Es wurde kein Paket erstellt — die Abgabe ist auf diesem Rechner "
                + "nicht eingerichtet.";
            case NO_PROJECT -> "✖ Es wurde kein Paket erstellt — es ist kein Projekt geöffnet.";
            default -> "✖ Es wurde kein Paket erstellt — die Abgabe ist fehlgeschlagen.";
        };
        Color background = result.ok() ? OK_BG : LIVE_BG;
        Color foreground = result.ok() ? OK_FG : LIVE_FG;
        paintOneLine(handoffState, background, foreground, result.message());
        show(background, foreground, verdict);
    }

    /**
     * <b>Every</b> coloured answer line on this panel. It clips rather than wraps.
     *
     * <p>An HTML label re-wraps to whatever width it is given, but its height was computed for
     * one line, so in a narrow Studio window the second line is sliced in half — rendered at
     * 900 pixels the hand-off verdict read "…bitte bei der" above a band of chopped letters. A
     * verdict a tester has to decipher is barely better than one they never saw.
     *
     * <p>There used to be a second helper, {@code paint}, that wrapped its text in
     * {@code <html>} — and the two lines still going through it, the recorder state and the
     * start-address note, carried the identical defect: at 900 pixels
     * <em>"Für die Aufnahme ist keine Start-Adresse hinterlegt…"</em> lost the half-line that
     * told the tester what to do about it. The same fault twice in the same file is not two
     * faults, it is one helper too many, so there is now only this one. Plain text cannot wrap,
     * so a JLabel truncates it with an ellipsis instead of painting a line it has no room for.
     *
     * <p>Two rules follow from clipping, and both are checked by the harnesses:
     *
     * <ul>
     *   <li><b>The instruction comes before the explanation.</b> Narrowing must cost the reason
     *       and never the step — a status word with nothing behind it needs no reordering, a
     *       line that asks the tester for something does.
     *   <li><b>The full sentence stays on the tooltip.</b> Set here for every line; the two
     *       states that also carry a technical reason append it rather than overwrite.
     * </ul>
     */
    private static void paintOneLine(JLabel label, Color background, Color foreground, String text) {
        label.setText(text);
        label.setToolTipText(text);
        label.setBackground(background);
        label.setForeground(foreground);
        label.setVisible(true);
        label.revalidate();
        label.repaint();
    }

    /** The banner. Always shows something after an action — never nothing. */
    private void show(Color background, Color foreground, String text) {
        if (banner == null) {
            return;
        }
        banner.setText("<html>" + text.replace("\n", "<br>") + "</html>");
        banner.setBackground(background);
        banner.setForeground(foreground);
        banner.setToolTipText(null);
        banner.setVisible(true);
        banner.revalidate();
        banner.repaint();
    }

    // ------------------------------------------------------------------ test seam

    /** Harness seam: waits for step 1's test-case load to settle. Never call from the EDT. */
    public boolean awaitReady(long timeoutMillis) {
        return chooser.awaitSettled(timeoutMillis);
    }

    /** Harness seam: the embedded chooser, so a test can drive the real buttons. */
    public TestCaseChooserPanel chooser() {
        return chooser;
    }

    /** Harness seam: the embedded customer picker. */
    public TestDataPanel customers() {
        return customers;
    }

    /** Harness seam: which step is on screen, 0-based. */
    public int currentStep() {
        return step;
    }

    /** Harness seam: whether a step counts as completed. */
    public boolean stepDone(int index) {
        return done(index);
    }

    /** Harness seam: the big headline naming the current step. */
    public String headlineText() {
        return headline == null ? "" : headline.getText();
    }

    /** Harness seam: the instruction under the headline. */
    public String hintText() {
        return hint == null ? "" : hint.getText();
    }

    /** Harness seam: the line naming what is still missing before the next step. */
    public String lockText() {
        return lock == null ? "" : lock.getText();
    }

    /** Harness seam: the coloured banner, or "" while it is hidden. */
    public String bannerText() {
        return banner == null || !banner.isVisible() ? "" : banner.getText();
    }

    /**
     * Harness seam: the repository-staleness line, or {@code ""} while it is hidden.
     *
     * <p>{@code ""} is the whole point of most runs — a healthy machine says nothing here — so a
     * test that asserts silence and a test that asserts a sentence read the same method.
     */
    public String repoStateText() {
        return repoState == null || !repoState.isVisible() ? "" : repoState.getText();
    }

    /** Harness seam: the technical detail behind the repository line, tooltip and all. */
    public String repoStateTooltip() {
        return repoState == null ? "" : String.valueOf(repoState.getToolTipText());
    }

    /**
     * Harness seam: whether the repository line has real height on screen.
     *
     * <p>Asked separately from its text for the reason the recorder state line records: a label
     * carrying the right sentence and laid out at zero height is invisible, and a harness that
     * read only {@code getText()} would call that a pass.
     */
    public boolean repoStateLaidOut() {
        return repoState != null && repoState.isVisible() && repoState.getHeight() > 0
            && repoState.getWidth() > 0;
    }

    /**
     * Harness seam: how tall the repository line really is, in pixels.
     *
     * <p>Separate from {@link #repoStateLaidOut()} because "has some height" and "has the height
     * of one line" are different questions, and only the second catches the wrap: a label that
     * grows a second row is drawn over the step chips, where its tail is lost.
     */
    public int repoStateHeight() {
        return repoState == null ? 0 : repoState.getHeight();
    }

    /**
     * Harness seam: waits for the git half of the repository check to report.
     *
     * @return true if it reported within the timeout; false means the answer on screen is still
     *     only the file half, and a test must say so rather than assume
     */
    public boolean awaitRepoStateSettled(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!repoStateSettled && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return repoStateSettled;
            }
        }
        return repoStateSettled;
    }

    /** Harness seam: the label on a step chip, including its ✔ once done. */
    public String chipText(int index) {
        return chips[index] == null ? "" : chips[index].getText();
    }

    /** Harness seam: the requirement text shown beside the customer picker in step 2. */
    public String caseRequirementText() {
        return caseText == null ? "" : caseText.getText();
    }

    /** Harness seam: the summary shown in step 3. */
    public String summaryText() {
        return summary == null ? "" : summary.getText();
    }

    /** Harness seam: whether "Weiter" is offered right now. */
    public boolean nextEnabled() {
        return next != null && next.isEnabled();
    }

    /** Harness seam: presses the recording button exactly as the tester does. */
    public void pressRecord() {
        record.doClick();
    }

    /**
     * Harness seam: the label on the recording button right now.
     *
     * <p>The label is the promise the button makes. A test that only checks the banner would
     * have passed the toggle bug too — the banner said "gestartet" and the button still said
     * "starten" while a recording was being ended underneath both.
     */
    public String recordButtonText() {
        return record == null ? "" : record.getText();
    }

    /** Harness seam: whether the recording button can be pressed at all. */
    public boolean recordButtonEnabled() {
        return record != null && record.isEnabled();
    }

    /** Harness seam: the line beside the button saying what Studio is doing. */
    public String recorderStateText() {
        return recorderState == null ? "" : recorderState.getText();
    }

    /**
     * Harness seam: the technical reason behind that line, or {@code ""} when there is none.
     *
     * <p>Only the two "cannot be read" states carry one, and in those states nothing else on
     * the screen does — the button is not pressable, so no attempt and no banner.
     */
    public String recorderStateTooltip() {
        String tip = recorderState == null ? null : recorderState.getToolTipText();
        return tip == null ? "" : tip;
    }

    /**
     * Harness seam: whether the state line was really laid out inside the row that holds it.
     *
     * <p>Not the same question as {@code isVisible()}, and the difference is the whole point.
     * A component can be visible, carry the right text and still be positioned below the
     * bottom edge of its parent — which is what a {@code FlowLayout} row did to this line in a
     * 900-pixel window until 2026-07-28: it wrapped the label onto a second row inside a
     * container whose height had been measured for one, and the sentence explaining why the
     * recording button was greyed out was never painted at all. Every string assertion in the
     * suite passed while the tester saw nothing.
     */
    public boolean recorderStateLaidOut() {
        if (recorderState == null || recorderState.getParent() == null) {
            return false;
        }
        Rectangle bounds = recorderState.getBounds();
        return bounds.width > 0 && bounds.height > 0
            && new Rectangle(recorderState.getParent().getSize()).contains(bounds);
    }

    /** Harness seam: the start-address line, or "" while it is hidden. */
    public String startUrlNoteText() {
        return startUrlNote == null || !startUrlNote.isVisible() ? "" : startUrlNote.getText();
    }

    /**
     * Harness seam: the whole start-address sentence, which the line itself may have clipped.
     *
     * <p>The point of the tooltip on a clipping line is that nothing is unreachable, so it is
     * worth being able to ask.
     */
    public String startUrlNoteTooltip() {
        String tip = startUrlNote == null ? null : startUrlNote.getToolTipText();
        return tip == null ? "" : tip;
    }

    /** Harness seam: whether the address can be typed at all right now. */
    public boolean startUrlEditorVisible() {
        return startUrlEditor != null && startUrlEditor.isVisible();
    }

    /** Harness seam: what stands in the address field. */
    public String startUrlFieldText() {
        return startUrlField == null ? "" : startUrlField.getText();
    }

    /** Harness seam: types an address, exactly as the tester does. */
    public void typeStartUrl(String text) {
        startUrlField.setText(text);
    }

    /** Harness seam: presses the address button, exactly as the tester does. */
    public void pressStartUrlSave() {
        startUrlSave.doClick();
    }

    /** Harness seam: the line saying whether the address was stored, or {@code ""} while hidden. */
    public String startUrlSaveStateText() {
        return startUrlSaveState == null || !startUrlSaveState.isVisible()
            ? "" : startUrlSaveState.getText();
    }

    /**
     * Harness seam: the colour that line is painted in, as {@code #rrggbb}.
     *
     * <p>Checked as well as the words for the reason the other two answer lines are: a refusal
     * printed in the success green reads as a pass to a string comparison and as "done" to a
     * tester whose recorder is still going to open a blank page.
     */
    public String startUrlSaveStateColour() {
        return startUrlSaveState == null ? "" : String.format("#%06x",
            startUrlSaveState.getBackground().getRGB() & 0xFFFFFF);
    }

    /** Harness seam: the technical reason behind that line. */
    public String startUrlSaveStateTooltip() {
        String tip = startUrlSaveState == null ? null : startUrlSaveState.getToolTipText();
        return tip == null ? "" : tip;
    }

    /** Harness seam: the line saying whether the run's evidence reached Azure DevOps. */
    public String uploadStateText() {
        return uploadState == null || !uploadState.isVisible() ? "" : uploadState.getText();
    }

    /**
     * Harness seam: the colour that line is painted in, as {@code #rrggbb}.
     *
     * <p>The words are only half of the message. A test that read the text alone would pass a
     * regression that printed a failure in the success green — which is the same "said too
     * quietly" defect in a different costume.
     */
    public String uploadStateColour() {
        return uploadState == null ? "" : String.format("#%06x",
            uploadState.getBackground().getRGB() & 0xFFFFFF);
    }

    /** Harness seam: the whole upload message, which the label itself may only be able to clip. */
    public String uploadStateTooltip() {
        String tip = uploadState == null ? null : uploadState.getToolTipText();
        return tip == null ? "" : tip;
    }

    /** Harness seam: re-reads Studio's state, as the once-a-second poll does. */
    public void pollRecorderState() {
        refreshRecorderState();
    }

    /** Harness seam: presses the hand-off button exactly as the tester does. */
    public void pressHandoff() {
        handoff.doClick();
    }

    /** Harness seam: whether the hand-off button can be pressed at all. */
    public boolean handoffButtonEnabled() {
        return handoff != null && handoff.isEnabled();
    }

    /** Harness seam: the line saying whether a package exists, and where. */
    public String handoffStateText() {
        return handoffState == null || !handoffState.isVisible() ? "" : handoffState.getText();
    }

    /**
     * Harness seam: the colour that line is painted in, as {@code #rrggbb}.
     *
     * <p>Asserted as well as the words, for the same reason the upload line is: a refusal
     * printed in the success green would read as a pass to a test that only compared strings,
     * and as "you are done" to a tester who has not handed anything over.
     */
    public String handoffStateColour() {
        return handoffState == null ? "" : String.format("#%06x",
            handoffState.getBackground().getRGB() & 0xFFFFFF);
    }

    /** Harness seam: the whole hand-off message, which the label itself may only be able to clip. */
    public String handoffStateTooltip() {
        String tip = handoffState == null ? null : handoffState.getToolTipText();
        return tip == null ? "" : tip;
    }

    /** Harness seam: the package that was written, or {@code ""} when none was. */
    public String handoffZipPath() {
        return handoffZip == null ? "" : handoffZip.toString();
    }

    /**
     * Harness seam: waits for a running packaging attempt to finish reporting.
     *
     * <p>Never call from the event dispatch thread — the packaging thread publishes its result
     * through {@code invokeLater}, so waiting on the EDT would deadlock against the very event
     * this waits for.
     *
     * @return {@code true} when the attempt has reported, {@code false} on timeout — a caller
     *     that gets {@code false} has not observed a failure, it has failed to observe
     */
    public boolean awaitHandoff(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (handoffRunning && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !handoffRunning;
    }
}
