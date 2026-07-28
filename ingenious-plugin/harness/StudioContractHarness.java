import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Checks that every name {@code StudioRecorder} reflects on still exists — against the
 * <em>built</em> INGenious jars, not against a memory of them.
 *
 * <p>The panel starts and stops recordings by walking
 * {@code AppMainFrame.getTestDesign().getTestCaseComp()} and reading
 * {@code getToolBar().isRecording()}, because the core exposes no API for any of it. That
 * makes a rename in the core a silent breakage here: reflection fails at runtime, the panel
 * falls back to "press the toolbar button", and nobody notices until a tester does. This
 * harness turns that into a build-time failure with the missing name printed.
 *
 * <p>It also pins the reason the panel exists at all — that {@code record()} is a toggle
 * whose first branch stops a running recording. That is a property of the source, not of a
 * signature, so it is checked in the source, at
 * {@code INGenious/IDE/.../testcase/TestCaseComponent.java}. If someone ever makes
 * {@code record()} start-only, this check fails and the panel's careful state handling can
 * be simplified rather than left as folklore.
 *
 * <p>Run it through its own runner, which resolves the Studio install and prints which one it
 * chose — the class itself takes whatever it is handed and says nothing about where it came
 * from:
 *
 * <pre>
 *   bash ingenious-plugin/harness/run-studio-contract-harness.sh
 *   java -cp target/harness-kontrakt StudioContractHarness &lt;repo-root&gt; [studio-lib-dir…]
 * </pre>
 */
public class StudioContractHarness {

    private static int checks;
    private static int failures;
    private static int unprovable;

    /**
     * Whether the two modules that actually carry the names below were built on this machine.
     *
     * <p>When they were not, a {@code ClassNotFoundException} says nothing about the core: the
     * class was never on the classpath to begin with. Reporting that as a failed check is the
     * harness claiming a verdict it cannot reach — and it did, on the first CI run, where a
     * checkout with only {@code ingenious-api} installed produced nineteen red lines that read
     * as "the core renamed nineteen things". See {@link #unprovable}.
     */
    private static boolean coreBuilt;

    public static void main(String[] args) throws Exception {
        File repo = new File(args.length > 0 ? args[0] : ".").getCanonicalFile();
        System.out.println("=== StudioContractHarness against " + repo + " ===");

        List<URL> jars = new ArrayList<>();
        boolean ide = false;
        boolean datalib = false;
        for (String module : new String[] { "IDE", "Datalib", "Common", "Engine", "ingenious-api" }) {
            File dir = new File(repo, "INGenious/" + module + "/target");
            File[] found = dir.listFiles((d, name) -> name.endsWith(".jar"));
            if (found != null && found.length > 0) {
                if ("IDE".equals(module)) {
                    ide = true;
                } else if ("Datalib".equals(module)) {
                    datalib = true;
                }
                for (File jar : found) {
                    jars.add(jar.toURI().toURL());
                    System.out.println("  jar: " + jar.getName());
                }
            }
        }
        coreBuilt = ide && datalib;
        // Reading a method off a class loads every type in every signature that class
        // declares, so the third-party libraries have to be there too. A Studio install's
        // lib folder is the honest source for them; argv[1] onwards names any that were
        // found. Without them the members that mention a missing type cannot be checked —
        // which is reported as such, never as a pass.
        int built = jars.size();
        for (int i = 1; i < args.length; i++) {
            File dir = new File(args[i]);
            File[] found = dir.listFiles((d, name) -> name.endsWith(".jar"));
            if (found != null) {
                for (File jar : found) {
                    jars.add(jar.toURI().toURL());
                }
                System.out.println("  lib: " + found.length + " jar(s) from " + dir);
            }
        }
        // The BUILT jars, counted separately from the install's lib folder, and counted PER
        // MODULE — because the number alone has now been wrong twice, in opposite directions.
        //
        //   !jars.isEmpty()  was satisfied by the hundreds of third-party jars any Studio
        //                    install contributes above, so on every machine this actually ran
        //                    on it could not fail.
        //   built > 0        was satisfied on a hosted runner by ingenious-api-3.0.jar alone —
        //                    the one module CI installs — and every class below then failed to
        //                    load. Nineteen red lines that read "the core renamed nineteen
        //                    things" when the truth was "the core was never built here". That
        //                    is this harness claiming a verdict it cannot reach, in a class
        //                    whose whole point is saying what it could and could not read.
        //
        // The names below live in IDE (com.ing.ide.*) and Datalib (com.ing.datalib.*). Those
        // two are the question; the rest of the classpath is scenery.
        String detail = built + " gebaute + " + (jars.size() - built) + " aus lib/"
            + "   IDE=" + (ide ? "ja" : "NEIN") + " Datalib=" + (datalib ? "ja" : "NEIN");
        if (coreBuilt) {
            check("Die gebauten INGenious-Kernmodule liegen vor", true, detail);
        } else {
            unprovable("Die gebauten INGenious-Kernmodule liegen vor",
                new IllegalStateException("IDE und Datalib sind hier nicht gebaut (" + detail
                    + ") — run mvn -B -DskipTests package -f INGenious/pom.xml"));
        }

        try (URLClassLoader loader = new URLClassLoader(jars.toArray(new URL[0]), null)) {
            Class<?> frame = load(loader, "com.ing.ide.main.mainui.AppMainFrame");
            Class<?> testDesign = load(loader,
                "com.ing.ide.main.mainui.components.testdesign.TestDesign");
            Class<?> testCase = load(loader,
                "com.ing.ide.main.mainui.components.testdesign.testcase.TestCaseComponent");
            Class<?> toolBar = load(loader,
                "com.ing.ide.main.mainui.components.testdesign.testcase.TestCaseToolBar");
            Class<?> project = load(loader, "com.ing.datalib.component.Project");
            Class<?> settings = load(loader, "com.ing.datalib.settings.ProjectSettings");
            Class<?> recorder = load(loader, "com.ing.datalib.settings.RecorderSettings");

            // The chain the panel walks to start and stop a recording.
            method(frame, "getTestDesign", testDesign);
            method(testDesign, "getTestCaseComp", testCase);
            method(testCase, "record", void.class);
            method(testCase, "getToolBar", toolBar);
            method(toolBar, "isRecording", boolean.class);
            method(toolBar, "setRecordingState", void.class, boolean.class);

            // The gap between "Record pressed" and "recorder live", which has no getter.
            field(testCase, "launchPlaywrightTask", Future.class);

            // The chain the panel walks to read the recorder's start address.
            method(testDesign, "getProject", project);
            method(project, "getProjectSettings", settings);
            method(settings, "getRecorderSettings", recorder);
            method(recorder, "getStartUrl", String.class);
            method(recorder, "setStartUrl", void.class, String.class);

            // …and the two the WRITE depends on, which RecorderSettings does not declare.
            //
            // setStartUrl alone changes an object in memory. ProjectSettings.save() does not
            // list the recorder settings among the twelve groups it writes, so the value only
            // survives a Studio restart because StudioRecorder calls RecorderSettings.save()
            // itself; and it is only reported as stored because the panel then re-reads the
            // file that getLocation() names. Both are inherited from AbstractPropSettings, so
            // getDeclaredMethod — which every pin above uses — does not see them, and they sat
            // unpinned while the code that needs them shipped. An upstream rename would have
            // left this harness green and the panel silently back to "übergeben, aber nicht
            // nachweisbar gespeichert", which reads to a tester as a fault of their machine
            // rather than as a rename in the core.
            //
            // Verified against this install's lib/ingenious-datalib-3.0.0.jar with javap:
            //   public void save();
            //   public java.lang.String getLocation();
            // both declared on com.ing.datalib.settings.AbstractPropSettings.
            inherited(recorder, "save", void.class);
            inherited(recorder, "getLocation", String.class);
        }

        // The behaviour the whole fix is about, pinned where it actually lives.
        Path source = repo.toPath().resolve(
            "INGenious/IDE/src/main/java/com/ing/ide/main/mainui/components/testdesign/"
                + "testcase/TestCaseComponent.java");
        check("Die Quelle von record() ist da", Files.isRegularFile(source), source.toString());
        if (Files.isRegularFile(source)) {
            String text = Files.readString(source, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
            check("record() ist weiterhin ein Umschalter: laufende Aufnahme wird beendet",
                text.contains("if (toolBar.isRecording()) { stopPlaywrightRecording(); return; }"),
                "erste Verzweigung in record()");
            check("setRecordingState(true) haengt an onRecorderReady(), nicht an record()",
                text.contains("private void onRecorderReady() { recorderReadySignaled = true;"),
                "der Zustand wird erst gesetzt, wenn der Recorder wirklich laeuft");
            // Counted once, and the unreadable files counted with it: this check asserts a
            // ZERO, and every file that could not be read used to count silently toward that
            // zero — the error path led straight to the passing answer.
            int[] callers = callersOfSetStartUrl(repo.toPath());
            if (callers[1] > 0) {
                unprovable("setStartUrl hat weiterhin keinen Aufrufer im Kern",
                    new java.io.IOException(callers[1] + " von " + callers[2] + " .java-Dateien "
                        + "konnten nicht gelesen werden — ein ungelesener Aufrufer zaehlt sonst "
                        + "als kein Aufrufer"));
            } else {
                check("setStartUrl hat weiterhin keinen Aufrufer im Kern", callers[0] == 0,
                    callers[0] + " Aufrufer in " + callers[2] + " gelesenen .java-Dateien");
            }
        }

        System.out.println();
        String skipped = unprovable == 0 ? "" : " (" + unprovable + " hier nicht prüfbar)";
        if (failures > 0) {
            System.out.println("RESULT: RED — " + failures + " of " + checks
                + " checks failed" + skipped);
            System.exit(1);
        }
        // This class exists so that a rename in the Studio core is a build-time failure rather
        // than a surprise in front of a tester. That promise is void for every signature this
        // machine could not read — and until 2026-07-28 the summary printed GREEN and exited 0
        // regardless, contradicting the rule unprovable() itself states: "Not a pass. A missing
        // third-party jar means this machine cannot read the signature, which is a different
        // statement from 'the name is still there', and the two must never print the same way."
        // The per-check output kept them apart; the exit code did not, and the exit code is the
        // only thing the runner reads.
        if (unprovable > 0) {
            System.out.println("RESULT: UNGEPRUEFT — " + unprovable + " of " + checks
                + " contract checks could not be read on this machine, so the core-rename guard "
                + "does not hold for them. Nothing failed, but nothing was proved either. "
                + (coreBuilt
                    ? "The core is built; what is missing is a Studio install's lib/ — reading a "
                        + "signature loads every type it mentions, and javafx/jackson live there. "
                        + "Point ING_STUDIO_LIB at the install the rest of the harnesses use."
                    : "INGenious/{IDE,Datalib}/target/*.jar are absent — nothing was built here "
                        + "to read. Run mvn -B -DskipTests package -f INGenious/pom.xml."));
            System.exit(4);
        }
        System.out.println("RESULT: GREEN — " + checks + " checks passed");
        System.exit(0);
    }

    /**
     * How many places in the whole INGenious tree write the recorder start URL.
     *
     * <p>Zero is the finding behind the panel's warning: the value exists as a properties
     * key and nothing in the product ever sets it, so an install nobody hand-edited records
     * against a blank browser. The day someone adds the Settings field, this count goes to
     * one and this check fails — which is the moment to replace the warning with a pointer
     * to that field.
     *
     * <p>The unreadable files are counted too, and separately. The check asserts a zero, so a
     * file that cannot be read used to be indistinguishable from a file with no caller in it:
     * the error path returned {@code false}, which is the answer that makes the check pass.
     * The tree missing altogether returned {@code 0} for the same reason. Neither may look
     * like evidence of absence.
     *
     * @return {@code {callers, unreadable, filesRead}}
     */
    private static int[] callersOfSetStartUrl(Path repo) throws Exception {
        Path root = repo.resolve("INGenious");
        if (!Files.isDirectory(root)) {
            // Not "no callers": nothing was looked at. Reported as unreadable so the caller
            // says "could not test" rather than "still zero".
            return new int[] { 0, 1, 0 };
        }
        int[] tally = new int[3];
        try (var paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("RecorderSettings.java"))
                .forEach(p -> {
                    try {
                        String text = Files.readString(p, StandardCharsets.UTF_8);
                        tally[2]++;
                        if (text.contains(".setStartUrl(")) {
                            tally[0]++;
                        }
                    } catch (Exception ex) {
                        tally[1]++;
                    }
                });
        }
        return tally;
    }

    private static Class<?> load(ClassLoader loader, String name) {
        try {
            Class<?> type = Class.forName(name, false, loader);
            check("Klasse " + name, true, "gefunden");
            return type;
        } catch (Throwable ex) {
            // "Not on the classpath" is only evidence of a rename when the classpath was
            // supposed to have it. Without a built IDE/Datalib there is nothing to have read.
            if (coreBuilt) {
                check("Klasse " + name, false, String.valueOf(ex));
            } else {
                unprovable("Klasse " + name, ex);
            }
            return null;
        }
    }

    private static void method(Class<?> owner, String name, Class<?> returns, Class<?>... params) {
        if (owner == null) {
            if (coreBuilt) {
                check("Methode " + name, false, "Klasse fehlt");
            } else {
                unprovable("Methode " + name,
                    new IllegalStateException("die Klasse ist hier nicht gebaut"));
            }
            return;
        }
        try {
            Method m = owner.getDeclaredMethod(name, params);
            boolean ok = returns == null || m.getReturnType().getName().equals(returns.getName());
            check(owner.getSimpleName() + "." + name + "()", ok, m.getReturnType().getName());
        } catch (NoClassDefFoundError ex) {
            // A library missing from this machine, not a name missing from the core. The
            // check did not run; saying so is the only honest option.
            unprovable(owner.getSimpleName() + "." + name + "()", ex);
        } catch (Throwable ex) {
            check(owner.getSimpleName() + "." + name + "()", false, String.valueOf(ex));
        }
    }

    /**
     * A public member callable on {@code owner}, wherever in the hierarchy it is declared.
     *
     * <p>{@link #method} uses {@code getDeclaredMethod}, which is right for the names the panel
     * reaches on the class that declares them: it fails when a method moves, and a move is worth
     * knowing about. It is wrong for a member the panel only ever calls through a subclass —
     * {@code RecorderSettings.save()} lives on {@code AbstractPropSettings}, so a declared-only
     * pin would report it missing today, on a core where nothing is wrong. A check that cannot
     * pass is not a check.
     *
     * <p>So this asks the question the plugin actually asks: {@code getMethod}, which searches
     * the hierarchy exactly as {@code StudioRecorder}'s reflective call does. The declaring
     * class is printed rather than asserted — moving it up or down the hierarchy breaks nothing;
     * removing or renaming it breaks the write, and that is a {@code NoSuchMethodException}
     * here.
     */
    private static void inherited(Class<?> owner, String name, Class<?> returns,
                                  Class<?>... params) {
        if (owner == null) {
            if (coreBuilt) {
                check("Methode " + name, false, "Klasse fehlt");
            } else {
                unprovable("Methode " + name,
                    new IllegalStateException("die Klasse ist hier nicht gebaut"));
            }
            return;
        }
        try {
            Method m = owner.getMethod(name, params);
            boolean ok = returns == null || m.getReturnType().getName().equals(returns.getName());
            check(owner.getSimpleName() + "." + name + "()", ok,
                m.getReturnType().getName() + ", geerbt von " + m.getDeclaringClass().getName());
        } catch (NoClassDefFoundError ex) {
            // A library missing from this machine, not a name missing from the core.
            unprovable(owner.getSimpleName() + "." + name + "()", ex);
        } catch (Throwable ex) {
            check(owner.getSimpleName() + "." + name + "()", false, String.valueOf(ex));
        }
    }

    private static void field(Class<?> owner, String name, Class<?> assignableTo) {
        if (owner == null) {
            if (coreBuilt) {
                check("Feld " + name, false, "Klasse fehlt");
            } else {
                unprovable("Feld " + name,
                    new IllegalStateException("die Klasse ist hier nicht gebaut"));
            }
            return;
        }
        try {
            Field f = owner.getDeclaredField(name);
            boolean ok = assignableTo.getName().equals(f.getType().getName())
                || assignableTo.isAssignableFrom(f.getType());
            check(owner.getSimpleName() + "." + name, ok, f.getType().getName());
        } catch (Throwable ex) {
            check(owner.getSimpleName() + "." + name, false, String.valueOf(ex));
        }
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + "   [" + detail + "]");
    }

    /**
     * A check that could not be made here — loudly, and counted.
     *
     * <p>Not a pass. A missing third-party jar means this machine cannot read the signature,
     * which is a different statement from "the name is still there", and the two must never
     * print the same way.
     */
    private static void unprovable(String what, Throwable why) {
        checks++;
        unprovable++;
        // The remedy has to match the cause. A line that says "point at a Studio lib" on a
        // machine where nothing was built sends the reader after the wrong missing thing.
        System.out.println("  SKIP " + what + "   [hier nicht prüfbar: " + why + " — "
            + (coreBuilt ? "Studio-lib-Verzeichnis angeben" : "INGenious/{IDE,Datalib} bauen")
            + "]");
    }
}
