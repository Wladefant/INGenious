import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/**
 * Drives a REAL running Studio to answer one question in both directions: does pressing
 * Record still ask where the steps should go?
 *
 * <p>It starts Studio through its own {@code Main}, finds the live {@code AppMainFrame},
 * opens a project, and invokes the very method the Record button invokes. The signal is the
 * Event Dispatch Thread itself: {@code RecordingTargetDialog} is modal, so if it opens, the
 * EDT stops answering. A marker task that comes back means no dialog was shown; a marker task
 * that does not means one was — no screen-scraping, no guessing.
 *
 * <pre>
 *   java -cp "ingenious-ide-3.0.0.jar;lib/*;lib/clib/*;." StudioRecordDriver &lt;project&gt; &lt;expect&gt;
 * </pre>
 *
 * <p>Run it from the install root, so Studio's plugin discovery looks in the right place.
 *
 * <p><b>The expectation is an argument, and the exit code is the verdict.</b> Until
 * 2026-07-28 this driver computed {@code chooserShown}, printed it, and then called
 * {@code halt(0)} unconditionally — so the regression it exists to catch (the chooser coming
 * back when a case is chosen) and the fix working left byte-identical exit codes, and the
 * runner's {@code [ $RC_CHOSEN -eq 0 ] && [ $RC_NONE -eq 0 ]} was green either way. Worse, a
 * {@code record()} that threw was caught, logged, and produced no dialog — which in the
 * {@code chosen} scenario <em>is</em> the desired answer. Total failure of the thing under
 * test read as success.
 *
 * <p>So the caller now says which answer is the right one, and this driver decides:
 *
 * <ul>
 *   <li>{@code chosen} — a selection exists, so {@code AdoRecordingTarget} answers and the
 *       chooser must NOT open;
 *   <li>{@code none} — no selection, so Studio's own chooser MUST open. That is the
 *       documented fall-back, and its absence would mean recording silently went nowhere.
 * </ul>
 *
 * <p>Exit code: {@code 0} the expected answer, {@code 4} the other one, {@code 3} when the
 * question could not be put at all — no expectation given, {@code record()} threw, or Studio
 * never came up. Never {@code 0} for a run that did not decide.
 */
public class StudioRecordDriver {

    /** Title of Studio's own target chooser — its presence is the whole verdict. */
    private static final String CHOOSER_TITLE = "Choose Recording Target";

    private static Object frame;

    /** What {@code record()} did when it was invoked, or {@code null} when it returned. */
    private static volatile String recordFailure;

    public static void main(String[] args) throws Exception {
        String project = args.length > 0
            ? args[0]
            : new File("Projects/Tutorial").getAbsolutePath();
        String expect = args.length > 1 ? args[1] : null;
        if (!"chosen".equals(expect) && !"none".equals(expect)) {
            System.out.println("[driver] INCONCLUSIVE: no expectation given. Usage: "
                + "StudioRecordDriver <project> chosen|none — without it this driver can "
                + "report what it saw but cannot say whether that was right, and an exit code "
                + "that means nothing is worse than no exit code.");
            System.exit(3);
        }
        System.out.println("[driver] expecting        : " + ("chosen".equals(expect)
            ? "NO chooser — a case is chosen, so the plugin must supply the target"
            : "the chooser TO OPEN — nothing is chosen, so Studio must ask"));

        String selection = System.getenv("ING_TESTCASE_SELECTION");
        System.out.println("[driver] selection file : " + selection);
        System.out.println("[driver] selection exists : "
            + (selection != null && new File(selection).isFile()));
        System.out.println("[driver] starting Studio…");

        Class<?> main = Class.forName("com.ing.ide.main.Main");
        main.getMethod("main", String[].class).invoke(null, (Object) new String[0]);

        frame = awaitFrame(120_000);
        if (frame == null) {
            System.out.println("[driver] INCONCLUSIVE: no AppMainFrame appeared — nothing "
                + "could be asked. This says nothing about the product.");
            System.exit(3);
        }
        System.out.println("[driver] Studio is up: " + frame.getClass().getName());

        System.out.println("[driver] opening project " + project);
        invokeOnEdt(frame, "loadProject", String.class, project);
        Thread.sleep(8000);

        Object testDesign = call(frame, "getTestDesign");
        Object testCaseComp = call(testDesign, "getTestCaseComp");
        System.out.println("[driver] test design ready");

        // Press Record, exactly as the toolbar does.
        System.out.println("[driver] invoking record()");
        SwingUtilities.invokeLater(() -> {
            try {
                testCaseComp.getClass().getMethod("record").invoke(testCaseComp);
            } catch (Throwable ex) {
                // Kept, not merely printed. A record() that threw opens no dialog either, and
                // "no dialog" is the PASSING answer in the chosen scenario — so swallowing this
                // made a total failure of the thing under test indistinguishable from success.
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                recordFailure = cause.getClass().getName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
                System.out.println("[driver] record() threw: " + recordFailure);
            }
        });

        // Give record() time to either open the chooser or get on with recording. A modal
        // Swing dialog pumps the event queue, so the EDT staying responsive proves nothing —
        // the visible windows do.
        pingEdt(12_000);
        Thread.sleep(3000);

        boolean chooserShown = false;
        System.out.println();
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w instanceof java.awt.Dialog) {
                java.awt.Dialog d = (java.awt.Dialog) w;
                System.out.println(
                    "[driver] visible dialog: \"" + d.getTitle() + "\"  modal=" + d.isModal());
                if (CHOOSER_TITLE.equals(d.getTitle())) {
                    chooserShown = true;
                }
            }
        }
        System.out.println("[driver] => target chooser shown: " + chooserShown);

        String openCase = "<none>";
        if (!chooserShown) {
            Object current = call(testCaseComp, "getCurrentTestCase");
            if (current != null) {
                Object scenario = call(current, "getScenario");
                openCase = call(scenario, "getName") + " / " + call(current, "getName");
            }
            System.out.println("[driver] open test case: " + openCase);
        }

        screenshot(new File("driver-screenshot.png"));

        // ------------------------------------------------------------------ the verdict
        boolean wanted = "none".equals(expect);
        int code;
        String verdict;
        if (recordFailure != null) {
            code = 3;
            verdict = "INCONCLUSIVE — record() threw " + recordFailure + ", so no dialog could "
                + "have opened whatever the plugin does. \"No chooser\" here is a fact about "
                + "the throw, not about the target resolution, and must never read as a pass.";
        } else if (chooserShown != wanted) {
            code = 4;
            verdict = "DISPROVEN — expected " + (wanted ? "the chooser to open" : "no chooser")
                + " and the chooser was " + (chooserShown ? "shown" : "NOT shown") + ". "
                + (wanted
                    ? "With nothing chosen, Studio must fall back to asking; a recording that "
                        + "starts anyway went somewhere nobody picked."
                    : "With a case chosen, AdoRecordingTarget must answer and the tester must "
                        + "not be asked a second time.");
        } else if (!chooserShown && "<none>".equals(openCase)) {
            code = 3;
            verdict = "INCONCLUSIVE — no chooser opened, which is the expected answer, but "
                + "Studio has no current test case either, so record() may simply have done "
                + "nothing. Suppressing the question and never asking it look the same from "
                + "outside, and only one of them is the product working.";
        } else {
            code = 0;
            verdict = "PROVEN — the chooser was " + (chooserShown ? "shown" : "not shown")
                + ", which is what \"" + expect + "\" requires"
                + (chooserShown ? "." : ", and Studio is recording into " + openCase + ".");
        }
        System.out.println();
        System.out.println("[driver] RESULT (" + expect + "): " + verdict);
        // halt, not exit: Studio's shutdown hooks would prompt about the open project.
        Runtime.getRuntime().halt(code);
    }

    private static Object awaitFrame(long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (Frame f : Frame.getFrames()) {
                if (f.getClass().getName().equals("com.ing.ide.main.mainui.AppMainFrame")) {
                    return f;
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    /** True when a task posted to the EDT comes back — i.e. no modal dialog is holding it. */
    private static boolean pingEdt(long timeoutMillis) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(latch::countDown);
        return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
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

    private static void screenshot(File out) {
        try {
            // The evidence is what Studio is showing, so raise it and frame the shot on it.
            java.awt.Rectangle area = new java.awt.Rectangle(
                java.awt.Toolkit.getDefaultToolkit().getScreenSize());
            if (frame instanceof Frame) {
                Frame f = (Frame) frame;
                try {
                    f.setAlwaysOnTop(true);
                    f.toFront();
                    f.requestFocus();
                } catch (RuntimeException ignored) {
                    // Focus is a hint on Windows; an unraised window still screenshots.
                }
                Thread.sleep(1500);
                if (f.getWidth() > 0 && f.getHeight() > 0) {
                    area = f.getBounds();
                }
            }
            java.awt.image.BufferedImage image = new java.awt.Robot().createScreenCapture(area);
            javax.imageio.ImageIO.write(image, "png", out);
            System.out.println("[driver] screenshot: " + out.getAbsolutePath());
        } catch (Exception ex) {
            System.out.println("[driver] screenshot failed: " + ex);
        }
    }
}
