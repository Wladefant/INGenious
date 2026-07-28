import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The guard on plugin target identity: a plugin says "this recording belongs to test case
 * <em>X</em>", and recording <em>X</em> again has to land in <em>X</em> — not in {@code X_1}.
 *
 * <p><b>What it is guarding against, precisely.</b> Upstream
 * <a href="https://github.com/ing-bank/INGenious/pull/302">#302</a> fixes a real bug — a user
 * who types the same name into the recording-target dialog twice used to overwrite their own
 * first recording — by making {@code createOrResolveTarget} <em>never reuse</em> a test case.
 * It now always creates a uniquely suffixed one. <b>The method kept its name, its signature
 * and its callers. Only its meaning changed.</b> Our plugin path routes the plugin-chosen
 * target through that exact method, and its entire point is stable identity — which is what
 * makes an ADO-id-keyed tester loop (propose &rarr; data &rarr; do &rarr; save-to-id &rarr;
 * next) work at all. After #302 the same call produces {@code X}, {@code X_1}, {@code X_2},
 * the merge is clean, the build is green and nothing warns.
 *
 * <p><b>Why a double exists at all.</b> Same reason the record-button toggle has one: the
 * behaviour only shows itself against a Studio that is already recording, which needs
 * Playwright and a browser and a desktop nobody is sharing. So the resolution step — and only
 * the resolution step — is reproduced here, from both sides of the drift, transcribed rather
 * than invented:
 *
 * <ul>
 *   <li>{@link BaseCore} — our pin {@code e2aa28a4},
 *       {@code TestCaseComponent.java:1332-1355}: {@code getTestCaseByName}, create only if
 *       absent.
 *   <li>{@link UpstreamCore} — upstream head {@code 7bc763b6},
 *       {@code TestCaseComponent.java:1221-1259}: {@code resolveUniqueTestCaseName} then an
 *       unconditional {@code addTestCase}, plus {@code hasTestCaseNameIgnoreCase}. Copied
 *       line for line off {@code git show up/release/3.1.0:IDE/.../TestCaseComponent.java}.
 * </ul>
 *
 * <p><b>Both directions, or it proves nothing.</b> A guard that has only ever been seen green
 * is what this project has spent a night removing. So the harness is run seven ways and the
 * runner asserts the whole matrix, including the one square that MUST be red:
 *
 * <pre>
 *   base                 our pin      + today's plugin path   -&gt; identity holds   exit 0
 *   after-302            upstream head+ today's plugin path   -&gt; identity BREAKS  exit 1
 *   after-302-repaired   upstream head+ the repair            -&gt; identity holds   exit 0
 *   repaired             our pin      + the repair            -&gt; identity holds   exit 0
 *   dialog               upstream head, dialog path untouched -&gt; still uniquifies exit 0
 * </pre>
 *
 * <p>If {@code after-302} ever passes, the double has stopped reproducing #302 and this guard
 * has stopped guarding — the runner treats that as red, loudly, rather than as one more green
 * line.
 *
 * <p><b>And one check that is not a double at all.</b> {@code source} reads the real
 * {@code TestCaseComponent.java} out of the {@code INGenious} submodule as it is checked out
 * right now, and asserts that the plugin path still lands in the same test case — carried
 * EITHER by upstream's implementation still resolving, OR by our repair consulting
 * {@code findExistingTarget} first. That is the check that fires on a pin move, on CI, with
 * nobody remembering to run anything. {@code source-after-302} is that same check aimed at a
 * post-#302 file, so the one check that has to fire is known to fire.
 */
public class PluginTargetIdentityHarness {

    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "base";
        System.out.println("== scenario: " + scenario + " ==");

        switch (scenario) {
            case "base":
                identity(new BaseCore(), PluginPath.AS_SHIPPED, "our pin e2aa28a4");
                break;
            case "after-302":
                identity(new UpstreamCore(), PluginPath.AS_SHIPPED, "upstream head 7bc763b6");
                break;
            case "after-302-repaired":
                identity(new UpstreamCore(), PluginPath.REPAIRED, "upstream head 7bc763b6");
                break;
            case "repaired":
                identity(new BaseCore(), PluginPath.REPAIRED, "our pin e2aa28a4");
                noRegression(new BaseCore());
                break;
            case "dialog":
                dialogStillUniquifies();
                break;
            case "source":
                source();
                break;
            case "source-after-302":
                sourceAfter302();
                break;
            default:
                System.out.println("unknown scenario " + scenario);
                System.exit(2);
        }

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------
    // THE CONTRACT
    // ------------------------------------------------------------------------------------

    /**
     * Record twice into the target a plugin named. Both recordings must land in one test case.
     *
     * <p>This is the whole promise. A tester takes ADO case 3951650, records, finds a step
     * wrong, records again — and expects to be editing the same case, because the ADO id is
     * the key everything else is filed under.
     */
    private static void identity(Core core, PluginPath path, String coreLabel) {
        System.out.println("   core: " + coreLabel + " · plugin path: " + path);
        Project project = new Project();

        String scenarioName = "Partner-Suche Suite";
        String testCaseName = "3951650 - Partner-Suche + Kunde-360";

        TestCase first = path.resolve(core, project, scenarioName, testCaseName, false);
        check("the first recording gets a target", first != null);
        if (first == null) {
            return;
        }
        check("the first recording lands in the name the plugin asked for",
            testCaseName.equals(first.getName()));

        TestCase second = path.resolve(core, project, scenarioName, testCaseName, false);
        check("the second recording gets a target", second != null);
        if (second == null) {
            return;
        }

        // The finding, stated as the assertion it is. Not "the names look similar" — the same
        // object, because a second TestCase carrying the same steps is a fork, not an update.
        check("re-recording lands in the SAME test case, not a suffixed copy",
            second == first);
        check("the plugin's name is a name, not a prefix — no _1 appeared",
            testCaseName.equals(second.getName()));
        check("the scenario still holds exactly one test case",
            project.scenarioNamed(scenarioName, false).getTestCases().size() == 1);

        if (second != first) {
            System.out.println("   the plugin asked for : " + testCaseName);
            System.out.println("   and the second recording went to : " + second.getName());
            System.out.println("   test cases now in the scenario   : "
                + project.scenarioNamed(scenarioName, false).names());
        }
    }

    /**
     * What the repair must NOT cost. Calling {@code findExistingTarget} first is only safe if
     * everything that used to be created is still created.
     */
    private static void noRegression(Core core) {
        System.out.println();
        System.out.println("   -- and nothing the recorder used to do may have stopped --");
        Project project = new Project();

        TestCase fresh = PluginPath.REPAIRED.resolve(core, project, "Neue Suite", "4711 - Neu", false);
        check("a target that does not exist yet is still created", fresh != null);
        check("its scenario was created too",
            project.scenarioNamed("Neue Suite", false) != null);

        TestCase reusable =
            PluginPath.REPAIRED.resolve(core, project, "Bausteine", "Anmeldung", true);
        check("a reusable target is created in the reusable half", reusable != null);
        check("and not in the ordinary half",
            project.scenarioNamed("Bausteine", false) == null);
        check("the reusable half has it",
            project.scenarioNamed("Bausteine", true) != null);

        // Same name, two halves. findExistingTarget takes `reusable` for this reason, and a
        // repair that dropped the flag would quietly make the two collide.
        TestCase ordinarySame =
            PluginPath.REPAIRED.resolve(core, project, "Bausteine", "Anmeldung", false);
        check("a reusable target is not mistaken for an ordinary one of the same name",
            ordinarySame != reusable);

        TestCase again = PluginPath.REPAIRED.resolve(core, project, "Bausteine", "Anmeldung", true);
        check("and the reusable one is still found on the second pass", again == reusable);
    }

    /**
     * Upstream's own bug fix, untouched by the repair.
     *
     * <p>The repair must not be a rollback. #302 exists because a person typing a name into
     * the dialog twice lost their first recording; the dialog path does not consult
     * {@code findExistingTarget} and must keep uniquifying. If our repair ever reached this
     * path, it would restore the overwrite that #302 fixed.
     */
    private static void dialogStillUniquifies() {
        Core core = new UpstreamCore();
        Project project = new Project();
        System.out.println("   core: upstream head 7bc763b6 · path: the dialog (unchanged)");

        TestCase first = core.createOrResolveTarget(project, "Suite", "Anmeldung", false);
        TestCase second = core.createOrResolveTarget(project, "Suite", "Anmeldung", false);

        check("a person typing the same name twice does NOT overwrite the first recording",
            second != first);
        check("the second one is suffixed", "Anmeldung_1".equals(second.getName()));
        check("both survive", project.scenarioNamed("Suite", false).getTestCases().size() == 2);
        System.out.println("   test cases: " + project.scenarioNamed("Suite", false).names());
    }

    // ------------------------------------------------------------------------------------
    // THE CHECK THAT FIRES ON A PIN MOVE
    // ------------------------------------------------------------------------------------

    /**
     * Read the real Studio source at whatever commit the submodule is checked out at, and
     * assert the identity contract is carried by SOMETHING.
     *
     * <p>Two limbs, either of which is enough:
     *
     * <ul>
     *   <li><b>A</b> — {@code createOrResolveTarget} still resolves before it creates. True at
     *       our pin. This limb is upstream's property, not ours, and #302 removed it.
     *   <li><b>B</b> — the plugin branch of {@code record()} consults {@code findExistingTarget}
     *       first and falls back. This limb is ours, and it survives a pin move.
     * </ul>
     *
     * <p>Neither limb, and re-recording forks the case: red. Only limb A, and the contract is
     * resting on an upstream implementation detail that upstream has already changed once —
     * that is not a failure today, but it is said out loud every run.
     */
    private static void source() throws Exception {
        String src = readStudioSource();
        if (src == null) {
            return;
        }
        Limbs limbs = analyse(src);

        check("the plugin still gets to name the recording target at all", limbs.hasPluginPath);
        if (!limbs.hasPluginPath) {
            System.out.println("   this source has no plugin recording-target path — it looks");
            System.out.println("   like unmodified upstream. Nothing below can be true.");
            return;
        }
        check("createOrResolveTarget is still the method the plugin path goes through",
            limbs.hasMethod);
        if (!limbs.hasMethod) {
            return;
        }
        limbs.print();
        check("re-recording a plugin-named target lands in the same test case", limbs.carried());

        if (limbs.limbA && !limbs.limbB) {
            System.out.println();
            System.out.println("   NOTE — this holds on limb A alone, which is UPSTREAM'S code and not ours.");
            System.out.println("   Upstream #302 removes limb A. The moment the submodule pin moves to");
            System.out.println("   7bc763b6 or later, this check goes red and stays red until the repair in");
            System.out.println("   ingenious-plugin/harness/repair/plugin-target-identity.patch is applied.");
            System.out.println("   That is the intended behaviour of this check, not a warning to silence.");
        }
        if (limbs.limbB) {
            System.out.println();
            System.out.println("   The repair is in place: identity no longer depends on upstream keeping");
            System.out.println("   createOrResolveTarget's old meaning.");
        }
    }

    /**
     * The analyser above, aimed at the file the pin move would produce — so that the check
     * which is supposed to fire is known to fire, and not merely believed to.
     *
     * <p><b>Why this square exists.</b> {@link #source()} is the only check here that touches
     * the real dependency, and until this scenario existed it had only ever been seen green.
     * A source check that has never been shown red is a regular expression somebody hopes is
     * right. So the post-#302 file is CONSTRUCTED — the pinned source with upstream head's
     * {@code createOrResolveTarget} body spliced in — and the analyser is required to call it
     * broken.
     *
     * <p><b>It is not the only evidence, and it is the weaker one.</b> The real three-way
     * merge was done out of band on 2026-07-28 and {@code source} was run against its output:
     *
     * <pre>
     *   git merge-base HEAD up/release/3.1.0        -> 15274331
     *   git merge-file ours base upstream           -> rc 0, no conflicts
     *   -> merged file: our plugin branch at line 690, upstream's uniquifier at 1349
     *   -> PluginTargetIdentityHarness source, ING_STUDIO_SRC=merged.java
     *      limb A false · limb B false · FAIL · exit 1
     * </pre>
     *
     * That used no working tree, no ref and no object write. It is the better evidence because
     * the file was produced by git rather than by this harness — but it needs an
     * {@code up/release/3.1.0} that a CI checkout does not have, so it cannot be a square. The
     * splice below can, and it locks the analyser against quietly going soft.
     */
    private static void sourceAfter302() throws Exception {
        String src = readStudioSource();
        if (src == null) {
            return;
        }
        String body = methodBody(src, "private TestCase createOrResolveTarget(");
        if (body == null) {
            System.out.println("!! createOrResolveTarget not found — nothing to splice");
            System.exit(2);
        }
        String spliced = src.replace(body, UPSTREAM_302_BODY);

        // A splice that changed nothing would make every assertion below vacuously true, which
        // is the exact shape of failure this square exists to rule out.
        check("the splice actually replaced the method body", !spliced.equals(src));
        check("the spliced source carries upstream's uniquifier",
            spliced.contains("resolveUniqueTestCaseName(scenario, testCaseName, reusable)"));

        Limbs limbs = analyse(spliced);
        check("the plugin path is still there — only the helper changed", limbs.hasPluginPath);
        check("and it still goes through createOrResolveTarget", limbs.hasMethod);
        limbs.print();

        // The point of the whole square.
        check("the analyser calls limb A gone", !limbs.limbA);
        check("the analyser reports the identity contract as NOT carried", !limbs.carried());
        System.out.println();
        System.out.println("   So `source` goes RED on the pin move, and it is this run that says so —");
        System.out.println("   not a comment claiming it would.");
    }

    /** Upstream head 7bc763b6, {@code TestCaseComponent.java:1221-1241}, verbatim body. */
    private static final String UPSTREAM_302_BODY =
        "{\n"
            + "        Scenario scenario = findScenarioByName(scenarioName, reusable);\n"
            + "        if (scenario == null) {\n"
            + "            scenario =\n"
            + "                reusable\n"
            + "                    ? testDesign.getProject().addReusableScenario(scenarioName)\n"
            + "                    : testDesign.getProject().addScenario(scenarioName);\n"
            + "        }\n"
            + "        if (scenario == null) {\n"
            + "            return null;\n"
            + "        }\n"
            + "\n"
            + "        String resolvedName = resolveUniqueTestCaseName(scenario, testCaseName, reusable);\n"
            + "        TestCase testCase = scenario.addTestCase(resolvedName);\n"
            + "\n"
            + "        registerTargetInTree(testCase, reusable);\n"
            + "        return testCase;\n"
            + "    }";

    /** Which limb, if any, is carrying the identity contract in a given source text. */
    static final class Limbs {
        boolean hasPluginPath;
        boolean hasMethod;
        /** {@code createOrResolveTarget} still resolves before it creates — upstream's. */
        boolean limbA;
        /** the plugin branch asks {@code findExistingTarget} first — ours. */
        boolean limbB;

        boolean carried() {
            return limbA || limbB;
        }

        void print() {
            System.out.println("   limb A — createOrResolveTarget resolves before creating : " + limbA);
            System.out.println("   limb B — the plugin path asks findExistingTarget first   : " + limbB);
        }
    }

    private static Limbs analyse(String src) {
        Limbs limbs = new Limbs();

        // The plugin branch has to be there in the first place. If the submodule were ever
        // re-pinned to raw upstream, our whole recording-target contribution would be gone and
        // limb B could never hold — a different regression, caught here rather than at runtime.
        int pluginAt = src.indexOf("RecordingTargetPlugins.currentTarget()");
        limbs.hasPluginPath = pluginAt >= 0;
        if (pluginAt >= 0) {
            String branch = src.substring(pluginAt, Math.min(src.length(), pluginAt + 1200));
            int elseAt = branch.indexOf("} else {");
            if (elseAt > 0) {
                branch = branch.substring(0, elseAt);
            }
            limbs.limbB = branch.contains("findExistingTarget");
        }

        String body = methodBody(src, "private TestCase createOrResolveTarget(");
        limbs.hasMethod = body != null;
        if (body != null) {
            limbs.limbA =
                body.contains("getTestCaseByName(") && !body.contains("resolveUniqueTestCaseName");
        }
        return limbs;
    }

    /** The pinned source, or {@code null} after saying why it could not be read. */
    private static String readStudioSource() throws Exception {
        Path file = studioSource();
        if (file == null) {
            System.out.println("!! could not find TestCaseComponent.java — set ING_STUDIO_SRC");
            System.out.println("!! COULD NOT TEST: this says nothing about the pinned source.");
            System.exit(2);
        }
        System.out.println("   reading: " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** {@code INGenious/IDE/src/.../TestCaseComponent.java}, by env var or by walking up. */
    private static Path studioSource() {
        String explicit = System.getenv("ING_STUDIO_SRC");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            return Files.isRegularFile(p) ? p : null;
        }
        String rel = "INGenious/IDE/src/main/java/com/ing/ide/main/mainui/components/"
            + "testdesign/testcase/TestCaseComponent.java";
        Path here = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && here != null; i++, here = here.getParent()) {
            Path candidate = here.resolve(rel);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Brace-counted body of a method, so a nested {@code {} } cannot end it early. */
    private static String methodBody(String src, String signature) {
        int at = src.indexOf(signature);
        if (at < 0) {
            return null;
        }
        int open = src.indexOf('{', src.indexOf(')', at));
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------
    // THE TWO CORES, TRANSCRIBED
    // ------------------------------------------------------------------------------------

    interface Core {
        /** {@code TestCaseComponent.createOrResolveTarget} — whatever it means at this ref. */
        TestCase createOrResolveTarget(Project project, String scenarioName, String testCaseName,
            boolean reusable);

        /**
         * {@code TestCaseComponent.findExistingTarget} — identical at both refs, and at
         * upstream head it has ZERO callers. That is the tool the repair picks back up.
         */
        default TestCase findExistingTarget(Project project, String scenarioName,
            String testCaseName, boolean reusable) {
            Scenario scenario = project.scenarioNamed(scenarioName, reusable);
            return scenario == null ? null : scenario.getTestCaseByName(testCaseName);
        }
    }

    /**
     * Our pin {@code e2aa28a4}, {@code TestCaseComponent.java:1332}:
     *
     * <pre>
     *   TestCase testCase = scenario.getTestCaseByName(testCaseName);
     *   if (testCase == null) {
     *       testCase = scenario.addTestCase(testCaseName);
     *   }
     * </pre>
     */
    static final class BaseCore implements Core {
        @Override
        public TestCase createOrResolveTarget(Project project, String scenarioName,
            String testCaseName, boolean reusable) {
            Scenario scenario = project.scenarioNamed(scenarioName, reusable);
            if (scenario == null) {
                scenario = reusable
                    ? project.addReusableScenario(scenarioName)
                    : project.addScenario(scenarioName);
            }
            if (scenario == null) {
                return null;
            }
            TestCase testCase = scenario.getTestCaseByName(testCaseName);
            if (testCase == null) {
                testCase = scenario.addTestCase(testCaseName);
            }
            return testCase;
        }
    }

    /**
     * Upstream head {@code 7bc763b6}, {@code TestCaseComponent.java:1221}:
     *
     * <pre>
     *   String resolvedName = resolveUniqueTestCaseName(scenario, testCaseName, reusable);
     *   TestCase testCase = scenario.addTestCase(resolvedName);
     * </pre>
     *
     * with {@code resolveUniqueTestCaseName} (line 1244) and
     * {@code hasTestCaseNameIgnoreCase} (line 1260) below it, both transcribed.
     */
    static final class UpstreamCore implements Core {
        @Override
        public TestCase createOrResolveTarget(Project project, String scenarioName,
            String testCaseName, boolean reusable) {
            Scenario scenario = project.scenarioNamed(scenarioName, reusable);
            if (scenario == null) {
                scenario = reusable
                    ? project.addReusableScenario(scenarioName)
                    : project.addScenario(scenarioName);
            }
            if (scenario == null) {
                return null;
            }
            String resolvedName = resolveUniqueTestCaseName(scenario, testCaseName, reusable);
            return scenario.addTestCase(resolvedName);
        }

        private String resolveUniqueTestCaseName(Scenario scenario, String requestedName,
            boolean reusable) {
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
    }

    // ------------------------------------------------------------------------------------
    // THE PLUGIN BRANCH OF record(), BEFORE AND AFTER
    // ------------------------------------------------------------------------------------

    /**
     * The two shapes the plugin branch of {@code TestCaseComponent.record()} can have.
     *
     * <p>{@link #REPAIRED} is not a sketch of the repair — it is the repair, the same two
     * statements the patch inserts at {@code TestCaseComponent.java:691}.
     */
    enum PluginPath {
        /** What ships at our pin: straight through the shared helper. Line 694. */
        AS_SHIPPED {
            @Override
            TestCase resolve(Core core, Project project, String s, String t, boolean r) {
                return core.createOrResolveTarget(project, s, t, r);
            }
        },
        /** The repair: a plugin naming a target MEANS it, so look for it before making one. */
        REPAIRED {
            @Override
            TestCase resolve(Core core, Project project, String s, String t, boolean r) {
                TestCase existing = core.findExistingTarget(project, s, t, r);
                return existing != null ? existing : core.createOrResolveTarget(project, s, t, r);
            }
        };

        abstract TestCase resolve(Core core, Project project, String s, String t, boolean r);
    }

    // ------------------------------------------------------------------------------------
    // The project model, reduced to the four methods the resolution step touches.
    // ------------------------------------------------------------------------------------

    static final class TestCase {
        private final String name;

        TestCase(String name) {
            this.name = name;
        }

        String getName() {
            return name;
        }
    }

    static final class Scenario {
        private final String name;
        private final List<TestCase> testCases = new ArrayList<>();

        Scenario(String name) {
            this.name = name;
        }

        String getName() {
            return name;
        }

        List<TestCase> getTestCases() {
            return testCases;
        }

        /** com.ing.datalib.component.Scenario#getTestCaseByName — case sensitive, as it is. */
        TestCase getTestCaseByName(String testCaseName) {
            for (TestCase tc : testCases) {
                if (tc.getName().equals(testCaseName)) {
                    return tc;
                }
            }
            return null;
        }

        TestCase addTestCase(String testCaseName) {
            TestCase tc = new TestCase(testCaseName);
            testCases.add(tc);
            return tc;
        }

        String names() {
            StringBuilder sb = new StringBuilder();
            for (TestCase tc : testCases) {
                sb.append(sb.length() == 0 ? "" : ", ").append(tc.getName());
            }
            return sb.toString();
        }
    }

    static final class Project {
        private final List<Scenario> scenarios = new ArrayList<>();
        private final List<Scenario> reusableScenarios = new ArrayList<>();

        /** findScenarioByName, transcribed: equalsIgnoreCase, and the two halves are separate. */
        Scenario scenarioNamed(String scenarioName, boolean reusable) {
            for (Scenario s : reusable ? reusableScenarios : scenarios) {
                if (s.getName().equalsIgnoreCase(scenarioName)) {
                    return s;
                }
            }
            return null;
        }

        Scenario addScenario(String scenarioName) {
            Scenario s = new Scenario(scenarioName);
            scenarios.add(s);
            return s;
        }

        Scenario addReusableScenario(String scenarioName) {
            Scenario s = new Scenario(scenarioName);
            reusableScenarios.add(s);
            return s;
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
