import com.ing.datalib.component.Project;
import com.ing.datalib.plugin.ProjectTestData;
import com.ing.datalib.testdata.TestDataFactory;
import com.ing.ingenious.api.contract.data.ProjectTestDataApi;
import com.ing.ingenious.api.contract.data.TestDataViewApi;
import de.ing.qa.studio.CustomerProfile;
import de.ing.qa.studio.TestCaseProfile;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Proves issue #126 end to end against a real INGenious project on disk: a customer profile
 * chosen in the picker is written onto a test case, no account number goes with it, and the
 * profile is still there after the project is reopened.
 *
 * <p>Runs headless. Compile and run it against this plugin's classes plus INGenious's
 * ingenious-api, Datalib and "TestData - Csv" module classes and their dependencies — the CSV
 * test-data provider has to be on the class path or a project cannot be opened at all:
 *
 * <pre>{@code
 * mvn -q dependency:build-classpath -Dmdep.outputFile=deps.txt -pl Datalib   # in INGenious/
 * javac -cp "<api>;<datalib>;<csv>;<plugin>;$(cat deps.txt)" -d out harness/ProfileHarness.java
 * java  -cp "out;<same>" ProfileHarness
 * }</pre>
 */
public class ProfileHarness {

    /** The nine header columns below, less KONTONUMMER and the blank one. */
    private static final int SETTINGS_COLUMNS = 7;

    private static final String KONTONUMMER = "1234567890";
    private static final String SCENARIO = "ADO Testfaelle";
    private static final String TEST_CASE = "3951650 - Partner-Suche pruefen";

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        List<String> headers = List.of(
            "KONTONUMMER",
            "Part Partnertyp Kz",
            "Produktvariante Pzm",
            "Verf Bez",
            "MDJ_KND",
            "EZB",
            "Variante_KND",
            "Kbo5 Bonitaet S",
            "Leer"
        );
        List<String> row = List.of(
            KONTONUMMER,
            "P",
            "GIRO",
            "Einzelverfuegung",
            "2019",
            "J",
            "03",
            "12",
            ""
        );

        CustomerProfile profile = CustomerProfile.of(headers, row);
        System.out.println("profile=" + profile);
        check("Kontonummer column is not in the profile", !profile.settings().containsKey("KONTONUMMER"));
        check(
            "no value in the profile is the account number",
            profile.settings().values().stream().noneMatch(KONTONUMMER::equals)
        );
        check("blank columns are left out", !profile.settings().containsKey("Leer"));
        check("the settings columns are kept", profile.settings().size() == SETTINGS_COLUMNS);
        check("Part Partnertyp Kz kept", "P".equals(profile.settings().get("Part Partnertyp Kz")));
        check("Kbo5 Bonitaet S kept", "12".equals(profile.settings().get("Kbo5 Bonitaet S")));

        TestDataFactory.load();
        Path temporary = Files.createTempDirectory("profile-harness-");
        Project project = new Project("Sample", temporary.toAbsolutePath().toString(), "csv")
            .createProject();
        ProjectTestDataApi testData = new ProjectTestData(() -> project);

        check(
            "the profile is written",
            TestCaseProfile.save(testData, SCENARIO, TEST_CASE, profile)
        );
        project.save();

        File sheet = new File(
            project.getLocation(),
            "TestData" + File.separator + TestCaseProfile.SHEET + ".csv"
        );
        check("the sheet exists on disk", sheet.exists());
        String written = Files.readString(sheet.toPath());
        System.out.println("--- " + sheet.getAbsolutePath() + " ---");
        System.out.println(written.trim());
        System.out.println("---");
        check("no account number is in the file", !written.contains(KONTONUMMER));

        // Reopen from the directory alone, as Studio does.
        Project reopened = new Project(project.getLocation());
        ProjectTestDataApi reread = new ProjectTestData(() -> reopened);
        TestDataViewApi back = reread.testCase(TestCaseProfile.SHEET, SCENARIO, TEST_CASE);
        check("the test case is found again", back != null);
        // Counted, because a loop of checks over an empty collection puts no questions and
        // reports no failures — it just prints nothing and lets the run stay green. Every
        // other check here would have to break at once for that to happen silently, but
        // "would have to" is how the last few of these were argued too.
        int rechecked = 0;
        for (Map.Entry<String, String> setting : profile.settings().entrySet()) {
            check(
                "after reopening, " + setting.getKey() + "=" + setting.getValue(),
                setting.getValue().equals(back.getField(setting.getKey()))
            );
            rechecked++;
        }
        check("the reopen loop actually put its questions", rechecked == SETTINGS_COLUMNS);

        // Nothing to write to is not a failure a tester should ever see.
        check(
            "no Studio handle is shrugged off",
            !TestCaseProfile.save(null, SCENARIO, TEST_CASE, profile)
        );

        System.out.println(failures == 0 ? "RESULT: all checks passed" : "RESULT: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
