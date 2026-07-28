package de.ing.qa.panel;

import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * What the tester sees when the repository behind the panel is stale — and, just as much, what
 * they see when it is not.
 *
 * <pre>
 *   bash ingenious-plugin/harness/run-repo-check-harness.sh
 * </pre>
 *
 * <p>Exit <b>0</b> green · <b>1</b> red · <b>4</b> UNGEPRUEFT (it ran, nothing failed, and some
 * question could not be put here — for instance because git is not on this machine).
 *
 * <h2>What is real here</h2>
 *
 * <ul>
 *   <li>The scenarios run against <b>real git repositories</b>, created by {@code git init} and
 *       a real commit. Nothing about git's answer is stubbed: the "up to date" scenario names a
 *       commit that genuinely exists in the fixture, and the stale one names a 40-hex id that
 *       genuinely does not.
 *   <li>The real {@link GuidedFlowPanel} is <b>rendered</b> — built on the event dispatch
 *       thread, packed into a frame, laid out and captured with {@code printAll} — not merely
 *       constructed. A label carrying the right sentence at zero height is invisible, so
 *       {@link GuidedFlowPanel#repoStateLaidOut()} is asked as well as its text.
 *   <li>Whether a dialog appeared is judged from {@link Window#getWindows()}, never from a
 *       responsive event dispatch thread. A modal dialog pumps the event queue, so an EDT that
 *       still answers proves nothing.
 * </ul>
 *
 * <h2>The check this harness exists for</h2>
 *
 * <p>Three of the five scenarios assert <b>silence</b>. That is not padding: a warning that
 * appears when nothing is wrong is ignored within a week and is then worse than no warning, so
 * "says nothing on a healthy machine" and "says nothing on a machine it cannot judge" are the
 * two properties most worth losing sleep over. Between them the five scenarios show the check
 * reaching <em>both</em> answers on real inputs, which is the only thing that distinguishes a
 * working detector from one that is wired to a constant.
 */
public final class RepoCheckHarness {

    private static int checks;
    private static int failures;
    private static int unproven;
    private static File shotDir;
    private static JFrame frame;

    private RepoCheckHarness() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "abgleich";
        shotDir = new File(args.length > 1 ? args[1] : ".");
        shotDir.mkdirs();

        System.out.println("################################################################");
        System.out.println("# repo-check — " + scenario);
        System.out.println("################################################################");
        System.out.println("ING_QA_REPO            = " + System.getenv(RepoCheck.ENV_REPO));
        System.out.println(RepoCheck.PROP_COMMIT + "     = "
            + System.getProperty(RepoCheck.PROP_COMMIT));
        System.out.println();

        switch (scenario) {
            case "abgleich" -> scenarioDrift();
            case "gesund" -> scenarioHealthy();
            case "werkzeug-fehlt" -> scenarioToolMissing();
            case "veraltet" -> scenarioBehind();
            case "nicht-eingerichtet" -> scenarioNotConfigured();
            case "unbeurteilbar" -> scenarioUndecidable();
            default -> {
                System.out.println("unknown scenario: " + scenario);
                System.exit(2);
            }
        }

        System.out.println();
        // System.exit on EVERY path, green included. GuidedFlowPanel starts a once-a-second
        // javax.swing.Timer and this harness builds a JFrame, so the AWT event thread is alive
        // and is not a daemon: falling off the end of main leaves the JVM running forever. The
        // first run of this harness hung there, after printing GREEN — which a caller reading
        // $? never sees, because there is no $? until the process ends.
        if (failures > 0) {
            System.out.println("RESULT: RED — " + failures + " of " + checks + " checks failed");
            System.exit(1);
        }
        if (unproven > 0) {
            System.out.println("RESULT: UNGEPRUEFT — " + unproven + " Frage(n) konnten hier nicht"
                + " gestellt werden; " + checks + " geprueft. Das ist KEIN Bestanden.");
            System.exit(4);
        }
        System.out.println("RESULT: GREEN — " + checks + " checks passed");
        System.exit(0);
    }

    // ------------------------------------------------------------------ the drift guard

    /**
     * The list this check looks for, against the constants the panels really shell out to.
     *
     * <p>{@link RepoCheck#REQUIRED} repeats three paths as literals because their constants live
     * in other packages. A rename there and not here would make the check look for a file nobody
     * uses — it would report a perfectly healthy machine as stale, which is the exact disease
     * this whole feature exists to cure, and it would do it silently. So the constants are read
     * back out of their own classes by reflection and compared. Headless, no panel, no git: this
     * one is a fact about the source and runs anywhere.
     */
    private static void scenarioDrift() throws Exception {
        Set<String> declared = new LinkedHashSet<>();
        for (RepoCheck.Tool tool : RepoCheck.REQUIRED) {
            declared.add(tool.rel());
        }
        System.out.println("  RepoCheck.REQUIRED = " + declared);

        Set<String> real = new LinkedHashSet<>();
        real.add(SelectorCheck.TOOL_REL);
        real.add(HandoffPack.TOOL_REL);
        real.add(constant("de.ing.qa.ado.AdoCache", "TOOL_REL"));
        real.add(constant("de.ing.qa.studio.AdoUpload", "PARSE_REL"));
        real.add(constant("de.ing.qa.studio.AdoUpload", "UPLOAD_REL"));
        System.out.println("  wirklich aufgerufen = " + real);

        List<String> unwatched = new ArrayList<>(real);
        unwatched.removeAll(declared);
        check("Jedes Werkzeug, das die Panels starten, wird ueberwacht",
            unwatched.isEmpty(), "nicht ueberwacht: " + unwatched);

        List<String> phantom = new ArrayList<>(declared);
        phantom.removeAll(real);
        // The dangerous direction: a path in the list that nothing calls any more would make
        // this check warn about a file whose absence costs the tester nothing.
        check("Kein Eintrag ueberwacht eine Datei, die niemand mehr aufruft",
            phantom.isEmpty(), "verwaist: " + phantom);

        for (RepoCheck.Tool tool : RepoCheck.REQUIRED) {
            check("… und \"" + tool.rel() + "\" nennt die Funktion, die ohne sie fehlt",
                tool.feature() != null && !tool.feature().isBlank(), tool.feature());
        }
    }

    /** A package-private or private constant, read off the class that owns it. */
    private static String constant(String className, String fieldName) throws Exception {
        Field field = Class.forName(className).getDeclaredField(fieldName);
        field.setAccessible(true);
        return String.valueOf(field.get(null));
    }

    // ------------------------------------------------------------------ the five machines

    /** A checkout with every tool, and a build stamp naming a commit it really contains. */
    private static void scenarioHealthy() throws Exception {
        RepoCheck.Result result = decide();
        check("Ein vollstaendiger, aktueller Arbeitsstand gilt als in Ordnung",
            result.state() == RepoCheck.State.OK, result.state() + " — " + result.detail());
        silent("bei einem gesunden Rechner", "10-repo-gesund");
    }

    /** One tool deleted from an otherwise healthy checkout. The 2026-07-28 incident, minimised. */
    private static void scenarioToolMissing() throws Exception {
        RepoCheck.Result result = decide();
        check("Ein fehlendes Werkzeug wird erkannt",
            result.state() == RepoCheck.State.TOOLS_MISSING,
            result.state() + " — " + result.detail());
        check("… und es wird namentlich genannt",
            result.missing().contains(SelectorCheck.TOOL_REL), String.valueOf(result.missing()));

        String line = speaks("bei einem fehlenden Werkzeug", "11-repo-werkzeug-fehlt");
        check("Die Zeile sagt, dass der Rechner nicht aktuell ist",
            line.contains("Nicht auf dem neuesten Stand"), line);
        clipOrder(line, "aktualisieren", "Bis dahin fehlt");
        check("… und nennt die Funktion, die die Testerin verliert",
            line.contains(GuidedFlowPanel.BTN_CHECK), line);
        check("… und Studio muss neu gestartet werden, das steht auch da",
            line.contains("neu starten"), line);
        // A path in a sentence a tester reads is a path they cannot act on. The file names
        // belong in the tooltip, where the person they report to can find them.
        check("Kein Dateipfad in dem Satz, den die Testerin liest",
            !line.contains(".mjs"), line);
        check("… aber im Tooltip stehen sie",
            tooltip().contains(SelectorCheck.TOOL_REL), tooltip());
    }

    /** Every tool present, and a build stamp naming a commit this checkout has never seen. */
    private static void scenarioBehind() throws Exception {
        RepoCheck.Result result = decide();
        if (result.state() == RepoCheck.State.UNKNOWN) {
            // Named rather than swallowed: on a machine without git this question cannot be put,
            // and reporting it as a pass would be inventing an answer.
            unproven("Der veraltete Arbeitsstand", result.detail());
            return;
        }
        check("Ein Arbeitsstand ohne den Stand des Plugins gilt als veraltet",
            result.state() == RepoCheck.State.BEHIND, result.state() + " — " + result.detail());

        String line = speaks("bei einem veralteten Arbeitsstand", "12-repo-veraltet");
        check("Die Zeile sagt, dass die Werkzeuge aelter sind als das Plugin",
            line.contains("älter als"), line);
        clipOrder(line, "aktualisieren", "älter als");
        check("Der Grund steht vollstaendig im Tooltip",
            tooltip().contains("nicht vorhanden"), tooltip());
    }

    /** No repository configured at all: a Fachbereich device with an install and nothing else. */
    private static void scenarioNotConfigured() throws Exception {
        RepoCheck.Result result = decide();
        check("Ein Rechner ohne Arbeitsstand wird erkannt",
            result.state() == RepoCheck.State.NOT_CONFIGURED,
            result.state() + " — " + result.detail());

        String line = speaks("ohne eingerichteten Arbeitsstand", "13-repo-nicht-eingerichtet");
        check("Die Zeile sagt, dass nichts eingerichtet ist",
            line.contains("Nicht eingerichtet"), line);
        check("… und nennt beide Funktionen, die dadurch tot sind",
            line.contains(GuidedFlowPanel.BTN_CHECK) && line.contains(GuidedFlowPanel.BTN_HANDOFF),
            line);
        check("… und an wen man sich wendet", line.contains("Testautomatisierung"), line);
        clipOrder(line, "Testautomatisierung", GuidedFlowPanel.BTN_CHECK);
    }

    /**
     * The instruction has to come before the explanation, because the line clips rather than
     * wraps.
     *
     * <p>An index rule, and said out loud as one: it does not measure pixels, it asserts that
     * whatever a narrow window has room for is the sentence's action rather than its reasoning.
     * The first draft of this line did wrap, and rendered at 900 pixels its second row was drawn
     * half over the step chips with its last words gone — a tester told their machine is out of
     * date and not what to do about it. The number is 110 characters, which is roughly what a
     * 900-pixel Studio window fits.
     */
    private static void clipOrder(String line, String action, String explanation) {
        int a = line.indexOf(action);
        int e = line.indexOf(explanation);
        check("… und die Anweisung steht VOR der Erklaerung, weil die Zeile abschneidet",
            a >= 0 && e >= 0 && a < e && a < 110, action + "@" + a + " " + explanation + "@" + e);
    }

    /**
     * A healthy checkout and a build that refused to make a claim about itself.
     *
     * <p>The most important silence of the five. The run script points this at a real checkout
     * and passes a stamp that is a word rather than an id — which is what
     * {@code tools/ing-update.ps1} writes for a dirty tree or an unpublished commit. The check
     * must say <b>nothing at all</b>: it does not know, and a guess put on a tester's screen is
     * how a warning stops being read.
     */
    private static void scenarioUndecidable() throws Exception {
        RepoCheck.Result result = decide();
        check("Ohne beurteilbare Herkunft wird kein Urteil gefaellt",
            result.state() == RepoCheck.State.UNKNOWN, result.state() + " — " + result.detail());
        String stamp = System.getProperty(RepoCheck.PROP_COMMIT);
        if (stamp == null || stamp.isBlank()) {
            // The commonest state in the world: every developer build, and every JAR built by a
            // plain `mvn package`. It must be the quiet one.
            check("… und der Grund nennt den fehlenden Manifest-Eintrag",
                result.detail().contains(RepoCheck.MANIFEST_ATTR), result.detail());
        } else {
            check("… und der Grund wiederholt wortwoertlich, was der Bau hinterlassen hat",
                result.detail().contains(stamp), result.detail());
        }
        silent("wenn der Stand nicht beurteilbar ist",
            stamp == null || stamp.isBlank() ? "15-repo-ohne-stempel" : "14-repo-unbeurteilbar");
    }

    // ------------------------------------------------------------------ asking, and looking

    /** The verdict, computed the way the panel computes it — files first, then git. */
    private static RepoCheck.Result decide() {
        RepoCheck.Result result = RepoCheck.full();
        System.out.println("  Zustand : " + result.state());
        System.out.println("  Detail  : " + result.detail());
        System.out.println("  Satz    : " + (result.message().isEmpty() ? "(keiner)"
            : result.message()));
        return result;
    }

    /** Renders the panel and asserts the line is there, returning its text. */
    private static String speaks(String where, String shot) throws Exception {
        GuidedFlowPanel flow = render();
        String text = strip(flow.repoStateText());
        check("Der Hinweis steht auf dem Bildschirm (" + where + ")", !text.isEmpty(),
            "repoStateText=\"" + text + "\"");
        check("… und er hat wirklich Hoehe und Breite, ist also sichtbar",
            flow.repoStateLaidOut(), "laidOut=" + flow.repoStateLaidOut());
        noWindows(where);
        shoot(shot);
        // Narrow, because that is where this line failed the first time it was rendered and
        // where every other message on this screen has failed before it. The label clips, so
        // what is asserted here is that it is still ONE line of the height it started at —
        // a wrapped second row is drawn over the step chips and its tail is simply lost.
        int tall = height();
        resizeTo(900, 780);
        shoot(shot + "b-schmales-fenster");
        check("… auch im schmalen Fenster steht er da", flow.repoStateLaidOut()
            && !strip(flow.repoStateText()).isEmpty(), flow.repoStateText());
        check("… und er bleibt einzeilig, statt in die Schritt-Leiste zu laufen",
            height() == tall, "breit=" + tall + "px schmal=" + height() + "px");
        check("Die ganze Anweisung bleibt im Tooltip erhalten",
            strip(flow.repoStateTooltip()).contains(text), flow.repoStateTooltip());
        resizeTo(1500, 950);
        lastTooltip = strip(flow.repoStateTooltip());
        return text;
    }

    /** Renders the panel and asserts there is no warning at all. */
    private static void silent(String where, String shot) throws Exception {
        GuidedFlowPanel flow = render();
        String text = strip(flow.repoStateText());
        check("Es wird NICHTS gewarnt (" + where + ")", text.isEmpty(),
            "repoStateText=\"" + text + "\"");
        check("… und die Zeile nimmt keinen Platz weg", !flow.repoStateLaidOut(),
            "laidOut=" + flow.repoStateLaidOut());
        noWindows(where);
        shoot(shot);
    }

    private static String lastTooltip = "";

    private static String tooltip() {
        return lastTooltip;
    }

    /**
     * Builds the real panel on the event dispatch thread and waits for the git half to report.
     *
     * <p>The wait is asserted rather than assumed: a run whose background check never came back
     * is looking at the file half only, and would silently pass every "says nothing" check for
     * the wrong reason.
     */
    private static GuidedFlowPanel render() throws Exception {
        GuidedFlowPanel flow = new GuidedFlowPanel();
        panel = flow;
        AtomicReference<JComponent> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JComponent view = flow.createPanel();
            frame = new JFrame("harness (never shown)");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(view);
            frame.pack();
            frame.setSize(1500, 950);
            frame.validate();
            ref.set(view);
        });
        view = ref.get();
        check("Die Herkunftspruefung hat geantwortet", flow.awaitRepoStateSettled(60_000),
            "settled");
        settle();
        return flow;
    }

    private static JComponent view;
    private static GuidedFlowPanel panel;

    /** The repository line's height right now, on the panel this scenario rendered. */
    private static int height() {
        return panel == null ? 0 : panel.repoStateHeight();
    }

    private static void resizeTo(int width, int height) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (frame != null) {
                frame.setSize(width, height);
                frame.validate();
            }
        });
        settle();
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(120);
        }
    }

    /**
     * Fails if ANY window is showing. A modal dialog keeps the event dispatch thread pumping, so
     * liveness is no evidence — the window list is.
     */
    private static void noWindows(String where) {
        List<String> showing = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) {
                showing.add(w.getClass().getSimpleName()
                    + (w instanceof java.awt.Dialog d ? " \"" + d.getTitle() + "\"" : ""));
            }
        }
        check("Kein Dialog oeffnet sich (" + where + ")", showing.isEmpty(),
            String.valueOf(showing));
    }

    private static void shoot(String name) throws Exception {
        if (view == null) {
            return;
        }
        File out = new File(shotDir, name + ".png");
        SwingUtilities.invokeAndWait(() -> {
            if (frame != null) {
                frame.validate();
            }
            BufferedImage image = new BufferedImage(
                Math.max(view.getWidth(), 100), Math.max(view.getHeight(), 100),
                BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            view.printAll(g);
            g.dispose();
            try {
                ImageIO.write(image, "png", out);
            } catch (Exception ex) {
                System.out.println("  screenshot failed: " + ex);
            }
        });
        System.out.println("  screenshot: " + out.getAbsolutePath());
    }

    private static String strip(String html) {
        if (html == null || "null".equals(html)) {
            return "";
        }
        return html.replaceAll("<br>", " ").replaceAll("<[^>]+>", "").trim();
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + "   [" + detail + "]");
    }

    private static void unproven(String what, String why) {
        unproven++;
        System.out.println("  UNGEPRUEFT " + what + "   [" + why + "]");
    }
}
