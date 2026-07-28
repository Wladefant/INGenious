import com.ing.ingenious.api.contract.ui.RecordingTarget;
import de.ing.qa.studio.AdoNaming;
import de.ing.qa.studio.AdoRecordingTarget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Headless proof for the recording-target contribution: the thing that stops the recorder
 * asking a question the tester has already answered.
 *
 * <p>Two scenarios (argv[0]), each in its own JVM because the selection path comes from the
 * environment:
 *
 * <ul>
 *   <li>{@code chosen} — a selection file exists: a target is proposed, its names are legal
 *       INGenious names, and the ADO id survives the round trip out of the test case name.
 *   <li>{@code none} — no selection file: the plugin must answer {@code null} so Studio's own
 *       chooser still opens. This is the direction that must never regress; it is the whole
 *       promise that installing the plugin does not take the stock flow away.
 * </ul>
 */
public class RecordingTargetHarness {

    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "chosen";
        System.out.println("== scenario: " + scenario + " ==");

        switch (scenario) {
            case "chosen":
                chosen();
                break;
            case "none":
                none();
                break;
            case "naming":
                naming();
                break;
            default:
                System.out.println("unknown scenario " + scenario);
                System.exit(2);
        }

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** A case has been taken: the recorder must be told where to put the steps. */
    private static void chosen() throws Exception {
        Path selection = Path.of(System.getenv("ING_TESTCASE_SELECTION"));
        Files.createDirectories(selection.getParent());
        Files.writeString(
            selection,
            "{\n"
                + "  \"adoId\": \"3951650\",\n"
                + "  \"title\": \"Beispielanwendung SYSTEMTEST: Partner-Suche + Kunde-360 (Set1)\",\n"
                + "  \"suiteName\": \"Partner-Suche Suite\"\n"
                + "}\n",
            StandardCharsets.UTF_8);

        RecordingTarget target = new AdoRecordingTarget().getRecordingTarget();
        check("a target is proposed", target != null);
        if (target == null) {
            return;
        }
        System.out.println("   target: " + target);
        check("scenario is the ADO suite", "Partner-Suche Suite".equals(target.getScenarioName()));
        check("test case starts with the ADO id", target.getTestCaseName().startsWith("3951650 - "));
        check("not a reusable scenario", !target.isReusableScenario());
        check(
            "the ADO id is recoverable from the name",
            "3951650".equals(AdoNaming.adoIdFromTestCaseName(target.getTestCaseName())));
        check("colons are gone", !target.getTestCaseName().contains(":"));
        check("brackets are gone", !target.getTestCaseName().contains("("));
        check("still readable", target.getTestCaseName().contains("Partner-Suche"));
    }

    /** Nothing taken: Studio must fall back to asking the user, exactly as it always has. */
    private static void none() {
        Path selection = Path.of(System.getenv("ING_TESTCASE_SELECTION"));
        check("no selection file exists", !Files.isRegularFile(selection));
        RecordingTarget target = new AdoRecordingTarget().getRecordingTarget();
        check("no target is proposed, so the stock chooser opens", target == null);
    }

    /** The naming convention itself, including the German text that made it necessary. */
    private static void naming() {
        check(
            "umlauts are transliterated, not dropped",
            "Vollmacht pruefen".equals(AdoNaming.scenarioName("Vollmacht prüfen")));
        check(
            "eszett survives as ss",
            "Strasse".equals(AdoNaming.scenarioName("Straße")));
        check(
            "a blank suite still lands somewhere",
            AdoNaming.DEFAULT_SCENARIO.equals(AdoNaming.scenarioName("   ")));
        check(
            "a title-less case is just its id",
            "3951650".equals(AdoNaming.testCaseName("3951650", "")));
        check(
            "a hand-made test case is not mistaken for an ADO one",
            AdoNaming.adoIdFromTestCaseName("Login Happy Path") == null);
        check(
            "digits glued to a word are not an id",
            AdoNaming.adoIdFromTestCaseName("360GradSicht") == null);
        boolean threw = false;
        try {
            AdoNaming.testCaseName("", "Titel ohne Id");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("an id-less selection is refused rather than made up", threw);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
