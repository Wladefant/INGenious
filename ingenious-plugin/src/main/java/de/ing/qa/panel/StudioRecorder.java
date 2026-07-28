package de.ing.qa.panel;

import java.awt.Frame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Future;

/**
 * Presses Studio's own Record button from inside a plugin panel — and knows which way it went.
 *
 * <p>There is no API for this. {@code StudioPanelApi} hands a plugin a rectangle of the
 * window and nothing else, and adding a "start a recording" contract to the INGenious core
 * is not this plugin's business. So the last step of the guided flow reaches the running
 * Studio the only way available from outside: it finds the live {@code AppMainFrame} among
 * the AWT frames and invokes the very method the toolbar invokes —
 * {@code getTestDesign().getTestCaseComp().record()}, the same chain
 * {@code harness/StudioRecordDriver.java} drives.
 *
 * <p><b>{@code record()} is a toggle, and that cost us a bug.</b> In
 * {@code TestCaseComponent.record()} the first statement is
 * {@code if (toolBar.isRecording()) { stopPlaywrightRecording(); return; }} — so calling it a
 * second time <em>ends</em> the recording. The panel used to report "✔ Die Aufnahme wurde
 * gestartet" either way, which turned a second press into a silent stop under a success
 * banner. That is why nothing here calls {@code record()} blind any more: this class reads
 * Studio's recording state first, refuses the call that would do the opposite of what was
 * asked, and re-reads the state afterwards to see what actually happened. The rule it serves
 * is the one this project keeps re-learning: <em>nothing may report success it did not
 * achieve</em>.
 *
 * <p><b>And when the state cannot be read, the toggle is not sent at all.</b> That was the
 * last way back in for the same bug: an unreadable Studio used to get the call anyway,
 * followed by an honest "we do not know what happened" — but the call is still the toggle, and
 * on a Studio that happened to be recording it reached {@code stopPlaywrightRecording()},
 * killed the recorder's browser and finalised the file. Not knowing is not permission. There
 * is exactly one state in which {@code record()} is invoked to start ({@link State#IDLE}) and
 * exactly one in which it is invoked to stop ({@link State#RECORDING}); every other answer,
 * including both flavours of "cannot tell", refuses and says why.
 *
 * <p>Everything is reflective and matched by name, so this class compiles and loads with no
 * Studio on the classpath at all — which is exactly the situation in the headless harness.
 * The names are not guesses; each is checked against the built
 * {@code ingenious-ide-3.0.0.jar} by {@code harness/StudioContractHarness.java}, which fails
 * loudly if the core renames one.
 *
 * <p><b>Failure is a first-class outcome, never an exception and never silence.</b> A
 * different Studio version, no project open, a renamed method: each comes back as
 * {@link Result#ok()} false plus a German sentence telling the tester what to do instead
 * (press Record in the toolbar — the test case is already set, so no dialog will ask
 * again). A button that silently does nothing is the one failure mode this whole flow
 * exists to remove; a button that silently does the opposite is worse.
 */
final class StudioRecorder {

    /** Studio's main window, matched by class name because we cannot import it. */
    private static final String FRAME_CLASS = "com.ing.ide.main.mainui.AppMainFrame";

    /** What to tell the tester when we could not start it for them. */
    static final String FALLBACK =
        "Die Aufnahme konnte von hier aus nicht gestartet werden. "
            + "Bitte oben in der Werkzeugleiste auf die Aufnahme-Schaltfläche klicken — "
            + "der Testfall ist bereits gesetzt, es wird nicht noch einmal nachgefragt.";

    /**
     * What the tester is told when this Studio build will not say what the recorder is doing.
     *
     * <p>Kept here rather than in the panel so the refusal and the line beside the button say
     * the same thing in the same words — the sentence names the reason for the refusal,
     * because "geht nicht" without a reason is what makes a tester press again.
     */
    static final String UNREADABLE_HELP =
        "Diese Studio-Version meldet nicht, ob eine Aufnahme läuft. Deshalb wird von hier aus "
            + "nichts gestartet: derselbe Befehl würde eine laufende Aufnahme beenden, und dass "
            + "gerade keine läuft, lässt sich hier nicht feststellen. Bitte oben in der "
            + "Werkzeugleiste auf die Aufnahme-Schaltfläche klicken — der Testfall ist bereits "
            + "gesetzt, es wird nicht noch einmal nachgefragt.";

    /** What the tester is told while Studio is there but not yet answering. */
    static final String NOT_READY_HELP =
        "Studio ist noch nicht so weit — der Aufnahme-Zustand ist gerade nicht lesbar. Es wurde "
            + "nichts gestartet und nichts beendet. Bitte einen Moment warten; sobald Studio "
            + "antwortet, geht es hier weiter.";

    /**
     * What Studio is doing right now, as far as it can be read from outside.
     *
     * <p>{@link #STARTING} exists because it is a real state with its own answer: between
     * pressing Record and the browser appearing, {@code toolBar.isRecording()} is still
     * false while {@code launchPlaywrightTask} is in flight, and a second {@code record()}
     * in that window does nothing at all (the core logs "Playwright recorder is already
     * running."). Collapsing it into {@link #IDLE} would make the panel offer a start that
     * cannot happen, and collapsing it into {@link #RECORDING} would make it offer a stop
     * that cannot happen either.
     *
     * <p><b>There are two ways of not knowing, and they are not the same.</b> A Studio that is
     * still building itself will answer in a second ({@link #NOT_READY}); a Studio whose core
     * no longer carries the names this class reads will never answer at all
     * ({@link #UNREADABLE}). One is worth waiting for and the other is worth saying out loud.
     * They agree on the only thing that matters: neither may reach {@code record()}.
     */
    enum State {
        /** No Studio window — the harness, or a panel opened outside Studio. */
        NO_STUDIO,
        /**
         * A Studio window is there but is not answering yet: half-built, no test case
         * component, or a getter that threw this once. Transient — the panel re-reads every
         * second and this resolves by itself.
         */
        NOT_READY,
        /**
         * This Studio build cannot be read at all: a name the state is read through is gone,
         * changed shape, or answered with something that is neither yes nor no. Waiting does
         * not help — it will answer the same way for as long as this Studio runs.
         */
        UNREADABLE,
        /** Nothing is recording, and nothing is on its way up. */
        IDLE,
        /** Record was pressed; the recorder is coming up but is not live yet. */
        STARTING,
        /** A recording is live. Pressing Record again would END it. */
        RECORDING
    }

    /** Outcome of an attempt: a flag, a sentence for the screen, and the technical reason. */
    record Result(boolean ok, State after, String message, String detail) {
    }

    /**
     * One reading of Studio: what it says, and — when it will not say — why not.
     *
     * <p>The reason travels with the state because it cannot be recovered later: by then the
     * exception is gone. It is the only thing that tells whoever the tester rings up
     * <em>which</em> name this Studio build is missing, and in the states where the button is
     * not pressable it is the only channel there is — no attempt means no result and no
     * banner.
     */
    record Reading(State state, String detail) {
    }

    private StudioRecorder() {
    }

    /**
     * What Studio is doing, read fresh every time.
     *
     * <p>Read rather than remembered on purpose. The tester can end a recording from
     * Studio's own toolbar, and the recorder can end by itself when the browser is closed —
     * a panel that trusted its own memory would then offer "Aufnahme beenden" for a
     * recording that is long over, and pressing it would start a new one. Same bug, mirrored.
     *
     * @return the current state, never {@code null}
     */
    static State state() {
        return read().state();
    }

    /**
     * The same read, with the technical reason attached — for the screen that has to explain
     * itself without an attempt having been made.
     */
    static Reading look() {
        return read();
    }

    /**
     * The read itself, with the reason kept.
     *
     * <p>Every {@code null} in the chain is a Studio that is not finished rather than a Studio
     * that is wrong: {@code AppMainFrame.getTestDesign()}, {@code TestDesign.getTestCaseComp()}
     * and {@code TestCaseComponent.getToolBar()} all return {@code final} fields assigned in
     * their constructors, so on a matching build none of them can be null once the object is
     * built. {@code Frame.getFrames()} however lists a frame from the moment {@code Frame}'s
     * own constructor has run — which is before {@code AppMainFrame}'s body has assigned
     * {@code testDesign}. That is the window this answers for, and it closes by itself.
     *
     * <p>The last step is deliberately strict about the answer's <em>type</em>. The old code
     * read {@code Boolean.TRUE.equals(...)}, so anything that was not exactly {@code TRUE} —
     * including a {@code null} from a core that had changed the return to boxed
     * {@code Boolean} — came out as {@link State#IDLE}, i.e. as the positive claim "nothing is
     * recording" derived from an answer nobody understood. The next press would then have
     * ended a live recording. An answer that is not a {@code Boolean} is not a "no".
     */
    private static Reading read() {
        Object frame = findStudioFrame();
        if (frame == null) {
            return new Reading(State.NO_STUDIO,
                "Kein " + FRAME_CLASS + " unter den offenen Fenstern gefunden.");
        }
        Object recording;
        Object testCaseComp;
        try {
            testCaseComp = testCaseComp(frame);
            if (testCaseComp == null) {
                return new Reading(State.NOT_READY,
                    "getTestDesign()/getTestCaseComp() lieferte null — Studio ist noch im "
                        + "Aufbau.");
            }
            Object toolBar = call(testCaseComp, "getToolBar");
            if (toolBar == null) {
                return new Reading(State.NOT_READY, "getToolBar() lieferte null.");
            }
            recording = call(toolBar, "isRecording");
        } catch (Exception | LinkageError ex) {
            return classify(ex);
        }
        if (!(recording instanceof Boolean live)) {
            return new Reading(State.UNREADABLE, "isRecording() lieferte "
                + (recording == null ? "null" : recording.getClass().getName())
                + " statt eines boolean.");
        }
        if (live) {
            return new Reading(State.RECORDING, "toolBar.isRecording() == true.");
        }
        return launchInFlight(testCaseComp)
            ? new Reading(State.STARTING, "launchPlaywrightTask läuft noch.")
            : new Reading(State.IDLE, "toolBar.isRecording() == false, kein Start in Arbeit.");
    }

    /**
     * Whether a failed read is a moment or this build.
     *
     * <p>An {@link java.lang.reflect.InvocationTargetException} means the method was there and
     * threw — a Studio having a bad second, worth asking again. Everything else is a property
     * of the build: a name that is not there ({@link NoSuchMethodException}), one that may not
     * be called ({@link IllegalAccessException}), a signature that changed underneath us (the
     * {@link LinkageError}s). Those answer identically for as long as this Studio runs, and
     * telling a tester to "wait a moment" for one of them would be a lie with a wait attached.
     *
     * <p>Getting the split wrong costs a sentence, never a recording: both answers refuse to
     * call {@code record()}. All this decides is what the tester reads and whether the button
     * is expected back.
     */
    private static Reading classify(Throwable ex) {
        boolean momentary = ex instanceof java.lang.reflect.InvocationTargetException;
        return new Reading(momentary ? State.NOT_READY : State.UNREADABLE, describe(ex));
    }

    /**
     * Starts a recording in the running Studio. Call on the Event Dispatch Thread — the
     * method it ends up invoking is the toolbar's own and expects to be there.
     *
     * <p>Never calls {@code record()} when a recording is already running: that call would
     * stop it. The tester is told a recording is running instead, which is the truth and is
     * also what they need to know.
     *
     * <p><b>And never when the state could not be read.</b> That used to be the one way back
     * in for the original bug: the panel sent the toggle anyway and then admitted it did not
     * know what had happened — but if a recording <em>was</em> running, that call reached
     * {@code stopPlaywrightRecording()}, which kills the recorder's browser process tree and
     * finalises the file. The tester's morning ends, under a sentence that says only "Studio
     * meldet nicht zurück". Refusing costs one press on Studio's own toolbar button, which is
     * two centimetres away and is the ground truth this class is trying to guess. Asking in a
     * dialog was the other candidate and is worse twice over: it puts the question to someone
     * whose only way of answering is to look at that same toolbar button, and a modal dialog
     * pumps the event queue underneath a panel that is required never to open one.
     *
     * <p>So {@code record()} is reached from here in exactly one state, {@link State#IDLE},
     * and the read that establishes it and the call that follows it happen inside a single
     * event on the Event Dispatch Thread — the core sets {@code isRecording} from
     * {@code onRecorderReady()} via {@code invokeLater}, so nothing can turn a recording on
     * between the look and the press.
     *
     * @return whether the recording was started, and what to say either way
     */
    static Result start() {
        Reading before = read();
        switch (before.state()) {
            case NO_STUDIO:
                return new Result(false, before.state(), FALLBACK, before.detail());
            case RECORDING:
                // The whole point of this class. record() here would STOP the recording.
                return new Result(false, before.state(),
                    "Es läuft bereits eine Aufnahme — es wurde nichts neu gestartet. "
                        + "Alles, was Sie tun, wird weiterhin aufgezeichnet. "
                        + "Zum Beenden auf \"" + GuidedFlowPanel.BTN_STOP + "\" klicken.",
                    before.detail() + " record() wurde bewusst NICHT aufgerufen.");
            case STARTING:
                return new Result(false, before.state(),
                    "Die Aufnahme wird gerade gestartet — bitte einen Moment warten, bis sich "
                        + "der Browser öffnet. Es wurde nichts neu gestartet.",
                    before.detail() + " record() wurde bewusst NICHT aufgerufen.");
            case UNREADABLE:
                // Not knowing is not permission. record() here is the toggle, and the state it
                // would toggle is exactly the one that could not be read.
                return new Result(false, before.state(), UNREADABLE_HELP,
                    before.detail() + " record() wurde bewusst NICHT aufgerufen.");
            case NOT_READY:
                return new Result(false, before.state(), NOT_READY_HELP,
                    before.detail() + " record() wurde bewusst NICHT aufgerufen.");
            default:
                break;
        }

        String detail;
        try {
            detail = invokeRecord();
        } catch (Exception | LinkageError ex) {
            // Reflection against a host we do not compile against: a renamed method is a
            // NoSuchMethodException, a changed signature a LinkageError. Both mean the same
            // thing to the tester, and neither may reach the Event Dispatch Thread.
            return new Result(false, state(), FALLBACK, describe(ex));
        }

        State after = state();
        if (after == State.STARTING || after == State.RECORDING) {
            return new Result(true, after,
                "✔ Die Aufnahme wurde gestartet. Der Browser öffnet sich; alles, was Sie dort "
                    + "tun, wird in diesem Testfall aufgezeichnet. Zum Beenden hier auf \""
                    + GuidedFlowPanel.BTN_STOP + "\" klicken.",
                detail + " Zustand danach: " + after);
        }
        if (after != State.IDLE) {
            // Readable before the call, unreadable after it — Studio was closed underneath us,
            // or answered differently this time. The call went out, so "nicht gestartet" would
            // be as much of an invention as "gestartet".
            return new Result(false, after,
                "Der Startbefehl wurde an Studio geschickt, aber Studio meldet jetzt nicht mehr, "
                    + "ob die Aufnahme läuft. Bitte an der Aufnahme-Schaltfläche in der "
                    + "Werkzeugleiste prüfen, ob sie rot ist.",
                detail + " Zustand danach nicht lesbar: " + after);
        }
        // record() came back and Studio is still idle: it declined, and said so somewhere we
        // cannot read. Saying "gestartet" here is exactly the lie this class was written for.
        return new Result(false, after,
            "Studio hat die Aufnahme nicht gestartet. Bitte oben in der Werkzeugleiste auf die "
                + "Aufnahme-Schaltfläche klicken — der Testfall ist bereits gesetzt.",
            detail + " Zustand danach: " + after + " (unverändert).");
    }

    /**
     * Ends the running recording. Call on the Event Dispatch Thread.
     *
     * <p>Never calls {@code record()} when nothing is recording: that call would start one.
     *
     * <p>The stop itself is not confirmed here. {@code finalizeLiveRecording()} queues
     * {@code toolBar.setRecordingState(false)} with {@code invokeLater}, so on the Event
     * Dispatch Thread the state still reads {@link State#RECORDING} the instant this
     * returns. The caller re-reads it one event later — see
     * {@link GuidedFlowPanel#refreshRecorderState()} — which is why the message below says
     * what was asked for, not what was achieved.
     *
     * @return whether the stop was sent, and what to say either way
     */
    static Result stop() {
        Reading before = read();
        switch (before.state()) {
            case NO_STUDIO:
                return new Result(false, before.state(),
                    "Die Aufnahme kann von hier aus nicht beendet werden. Bitte oben in der "
                        + "Werkzeugleiste auf die Aufnahme-Schaltfläche klicken.",
                    before.detail());
            case IDLE:
                // record() here would START a recording nobody asked for.
                return new Result(false, before.state(),
                    "Es läuft gerade keine Aufnahme — es wurde nichts beendet.",
                    before.detail() + " record() wurde bewusst NICHT aufgerufen.");
            case STARTING:
                return new Result(false, before.state(),
                    "Die Aufnahme wird gerade gestartet und kann noch nicht beendet werden. "
                        + "Bitte warten, bis sich der Browser geöffnet hat.",
                    before.detail() + " record() wurde bewusst NICHT aufgerufen.");
            case UNREADABLE:
                return new Result(false, before.state(),
                    "Diese Studio-Version meldet nicht, ob eine Aufnahme läuft. Es wurde nichts "
                        + "beendet — bitte die Aufnahme-Schaltfläche oben in der Werkzeugleiste "
                        + "benutzen.",
                    before.detail() + " record() wurde bewusst NICHT aufgerufen.");
            case NOT_READY:
                return new Result(false, before.state(), NOT_READY_HELP,
                    before.detail() + " record() wurde bewusst NICHT aufgerufen.");
            default:
                break;
        }

        try {
            String detail = invokeRecord();
            return new Result(true, state(),
                "Die Aufnahme wird beendet. Die aufgezeichneten Schritte stehen im Testfall.",
                detail);
        } catch (Exception | LinkageError ex) {
            return new Result(false, state(),
                "Die Aufnahme konnte von hier aus nicht beendet werden. Bitte oben in der "
                    + "Werkzeugleiste auf die Aufnahme-Schaltfläche klicken.",
                describe(ex));
        }
    }

    /**
     * The address the recorder will open, as the open project has it configured.
     *
     * <p>Reading it is the whole point: {@code RecorderSettings.setStartUrl} has no caller
     * anywhere in the core, so the value can only ever have come from the project's
     * properties file, and an install where nobody edited that file records against a blank
     * browser. That is worth saying <em>before</em> the tester presses the button rather than
     * leaving them to work it out from an empty window
     * (<a href="https://github.com/Wladefant/ing-qa-automation/issues/107">#107</a>).
     *
     * @return the configured address, {@code ""} when the project has none, or {@code null}
     *     when there is no Studio to ask or it would not answer — which is not the same
     *     thing and must not be reported as one
     */
    static String projectStartUrl() {
        Object frame = findStudioFrame();
        if (frame == null) {
            return null;
        }
        try {
            Object testDesign = call(frame, "getTestDesign");
            Object project = testDesign == null ? null : call(testDesign, "getProject");
            Object settings = project == null ? null : call(project, "getProjectSettings");
            Object recorder = settings == null ? null : call(settings, "getRecorderSettings");
            Object url = recorder == null ? null : call(recorder, "getStartUrl");
            return url == null ? null : String.valueOf(url).trim();
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }

    /**
     * The recorder-settings file of the open project, or {@code ""} when it cannot be named.
     *
     * <p>Read without writing anything, because {@link StartAddressMemory} needs a stable name
     * for "this project" <em>before</em> a write is attempted — both to compare the machine
     * against the one stored last time and to know, on a later start, whose address it is
     * holding. A build that will not say gets {@code ""}, and the caller falls back to a single
     * shared entry rather than inventing a project identity.
     */
    static String projectSettingsLocation() {
        Object frame = findStudioFrame();
        if (frame == null) {
            return "";
        }
        try {
            Object testDesign = call(frame, "getTestDesign");
            Object project = testDesign == null ? null : call(testDesign, "getProject");
            Object projectSettings = project == null ? null : call(project, "getProjectSettings");
            Object settings = projectSettings == null
                ? null : call(projectSettings, "getRecorderSettings");
            return settings == null ? "" : location(settings);
        } catch (Exception | LinkageError ex) {
            return "";
        }
    }

    /**
     * Where a written start address ended up. Four answers, because they are four different
     * things to tell a tester and only the first one is "done".
     */
    enum Store {
        /** In the project's own settings file, and read back out of that file to prove it. */
        PROJECT_FILE,
        /** Handed to the project's settings and saved, but this build would not say where to. */
        PROJECT_SAVED_UNVERIFIED,
        /**
         * Set on the open project, but the save was refused — no such method on this build, or
         * it threw. This session will use the address; the next one will not.
         */
        PROJECT_MEMORY,
        /** Nowhere. */
        NONE
    }

    /**
     * The outcome of one write.
     *
     * @param store where the value actually landed — never a guess
     * @param detail the technical reason, for whoever the tester rings up
     * @param location the settings file it was written to, or {@code ""} when unknown
     */
    record Write(Store store, String detail, String location) {

        /** Only a value proven to be in the file the recorder reads counts as stored. */
        boolean ok() {
            return store == Store.PROJECT_FILE;
        }
    }

    /**
     * Writes the project's recorder start address — through the product's own setting, which is
     * the one the core reads.
     *
     * <p>This is the whole point of doing it reflectively rather than inventing a plugin-side
     * store: {@code TestCaseComponent.resolveRecordingStartUrl} falls back to
     * {@code getProjectSettings().getRecorderSettings().getStartUrl()} and to nothing else, so a
     * value kept anywhere else would be a setting the recorder never reads — a screen reporting
     * a success it did not achieve.
     *
     * <p><b>Saved explicitly, because the product's own save does not cover it.</b>
     * {@code ProjectSettings.save()} saves twelve settings groups and the recorder settings are
     * not among them; {@code RecorderSettings} inherits its own {@code save()} from
     * {@code AbstractPropSettings}, and that is what is called here.
     *
     * <p>And then read back out of the file rather than out of the object. An in-memory setter
     * that worked and a file that was never written look identical from the object, and they
     * differ by exactly one Studio restart.
     *
     * @param url an address {@link StartAddress#problem} has already accepted
     * @return where it landed, which may be nowhere
     */
    static Write setProjectStartUrl(String url) {
        Object frame = findStudioFrame();
        if (frame == null) {
            return new Write(Store.NONE,
                "Kein " + FRAME_CLASS + " unter den offenen Fenstern gefunden.", "");
        }
        Object settings;
        try {
            Object testDesign = call(frame, "getTestDesign");
            Object project = testDesign == null ? null : call(testDesign, "getProject");
            Object projectSettings = project == null ? null : call(project, "getProjectSettings");
            settings = projectSettings == null
                ? null : call(projectSettings, "getRecorderSettings");
        } catch (Exception | LinkageError ex) {
            return new Write(Store.NONE, describe(ex), "");
        }
        if (settings == null) {
            return new Write(Store.NONE,
                "getRecorderSettings() lieferte null — vermutlich ist kein Projekt geöffnet.", "");
        }
        try {
            Method setter = settings.getClass().getMethod("setStartUrl", String.class);
            setter.setAccessible(true);
            setter.invoke(settings, url);
        } catch (Exception | LinkageError ex) {
            return new Write(Store.NONE, "setStartUrl(String): " + describe(ex), "");
        }

        String location = location(settings);
        try {
            call(settings, "save");
        } catch (Exception | LinkageError ex) {
            // The value is on the open project and the recorder will use it for this session,
            // which is worth saying — but it is not stored, and must not be reported as if it
            // were: the next Studio start reads the file, and the file is unchanged.
            return new Write(Store.PROJECT_MEMORY, "save(): " + describe(ex), location);
        }
        if (location.isEmpty()) {
            return new Write(Store.PROJECT_SAVED_UNVERIFIED,
                "save() lief, aber getLocation() ist auf diesem Build nicht lesbar — die Datei "
                    + "konnte nicht nachgelesen werden.", "");
        }
        String inFile = fileValue(location);
        if (!url.equals(inFile)) {
            return new Write(Store.PROJECT_SAVED_UNVERIFIED,
                "save() lief, aber in " + location + " steht "
                    + (inFile == null ? "kein StartUrl" : "\"" + inFile + "\""), location);
        }
        return new Write(Store.PROJECT_FILE, "StartUrl in " + location + " nachgelesen.",
            location);
    }

    /** The settings file this settings object writes to, or {@code ""} when it will not say. */
    private static String location(Object settings) {
        try {
            Object value = call(settings, "getLocation");
            return value == null ? "" : String.valueOf(value);
        } catch (Exception | LinkageError ex) {
            return "";
        }
    }

    /**
     * The stored {@code StartUrl}, read out of the properties file itself.
     *
     * @return the value on disk, or {@code null} when there is none to read
     */
    private static String fileValue(String location) {
        java.io.File file = new java.io.File(location);
        if (!file.isFile()) {
            return null;
        }
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            props.load(in);
        } catch (Exception ex) {
            return null;
        }
        String value = props.getProperty("StartUrl");
        return value == null ? null : value.trim();
    }

    /** True when a Studio window is there at all — used to explain the button before it is pressed. */
    static boolean studioPresent() {
        return findStudioFrame() != null;
    }

    /**
     * The Object-Repository page the last recording wrote into, or {@code null}.
     *
     * <p>Read so a check can be about <em>this</em> recording rather than about every object the
     * project has ever held. {@code PlaywrightRecordingParser.createLiveRecordingPage} makes one
     * page per recording and {@code TestCaseComponent} keeps its name in a private field; unlike
     * the other live-recording fields it is deliberately <b>not</b> cleared by
     * {@code finalizeLiveRecording}, so it is still there after the recorder has stopped — which
     * is the only moment anybody wants it.
     *
     * <p>There is no getter, so this is reflective and best-effort, exactly like
     * {@link #launchInFlight}. {@code null} is a perfectly good answer and the caller must treat
     * it as one: checking every page instead of one costs breadth, and breadth here means more
     * objects reported as "not present on this page" — a wider honest answer, never a narrower
     * false one.
     */
    static String liveRecordingPageName() {
        Object frame = findStudioFrame();
        if (frame == null) {
            return null;
        }
        try {
            Object testCaseComp = testCaseComp(frame);
            if (testCaseComp == null) {
                return null;
            }
            Field field = testCaseComp.getClass().getDeclaredField("liveRecordingPageName");
            field.setAccessible(true);
            Object value = field.get(testCaseComp);
            String name = value == null ? null : String.valueOf(value).trim();
            return name == null || name.isEmpty() ? null : name;
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ reflection

    /** Invokes the toolbar's own method and returns what to write in the detail line. */
    private static String invokeRecord() throws Exception {
        Object frame = findStudioFrame();
        if (frame == null) {
            throw new IllegalStateException("Studio-Fenster verschwunden.");
        }
        Object testCaseComp = testCaseComp(frame);
        if (testCaseComp == null) {
            throw new IllegalStateException(
                "getTestCaseComp() lieferte null — vermutlich ist kein Projekt geöffnet.");
        }
        call(testCaseComp, "record");
        return "record() aufgerufen auf " + testCaseComp.getClass().getName() + ".";
    }

    private static Object testCaseComp(Object frame) throws Exception {
        Object testDesign = call(frame, "getTestDesign");
        return testDesign == null ? null : call(testDesign, "getTestCaseComp");
    }

    /**
     * Whether a recorder launch is on its way up.
     *
     * <p>Reads the private {@code launchPlaywrightTask} field, because it is the only
     * evidence of the gap between "Record pressed" and "recorder live" and the core exposes
     * no getter for it. Read-only, best-effort, and a failure here is answered with
     * "not in flight" — the worst that costs is that the panel offers a start that the core
     * then declines, which it reports honestly anyway.
     */
    private static boolean launchInFlight(Object testCaseComp) {
        try {
            Field field = testCaseComp.getClass().getDeclaredField("launchPlaywrightTask");
            field.setAccessible(true);
            Object task = field.get(testCaseComp);
            return task instanceof Future<?> future && !future.isDone();
        } catch (Exception | LinkageError ex) {
            return false;
        }
    }

    private static Object findStudioFrame() {
        try {
            for (Frame f : Frame.getFrames()) {
                if (FRAME_CLASS.equals(f.getClass().getName())) {
                    return f;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Headless, or an AWT without frames: no Studio, which is an answer.
        }
        return null;
    }

    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /**
     * The technical reason in one line — unwrapped, because {@code InvocationTargetException}
     * on its own says nothing at all about what actually went wrong.
     */
    private static String describe(Throwable ex) {
        Throwable real = ex instanceof java.lang.reflect.InvocationTargetException
            && ex.getCause() != null ? ex.getCause() : ex;
        return real.getClass().getSimpleName()
            + (real.getMessage() == null ? "" : ": " + real.getMessage());
    }
}
