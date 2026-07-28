package de.ing.qa.studio;

import com.ing.ingenious.api.contract.data.ProjectTestDataApi;
import com.ing.ingenious.api.contract.data.TestDataViewApi;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records on a test case which kind of test customer it needs.
 *
 * <p>The requirement, stated plainly, was that the chosen customer be <em>saved</em> and not
 * only copied — "so we always know what was used in every test case and don't have to think
 * about it". Copying answers the moment; a test case reopened next month has to answer for
 * itself. So the picker's Kontonummer goes to the clipboard and the profile
 * ({@link CustomerProfile}) goes into the project's test data, where a tester sees it in Test
 * Design beside the test case and where reopening the project brings it back.
 *
 * <p>All of it lives in one sheet, {@value #SHEET}, whose columns are the export's own column
 * names. Nothing about this vocabulary reaches INGenious: the core knows only that a plugin
 * writes columns on a test case.
 *
 * <p>Nothing here can stop a tester working. No handle from Studio, no test case chosen, an
 * empty profile, a project that will not take the write — each is logged and shrugged off. The
 * account number still reached the clipboard, which is what the tester was waiting for.
 */
public final class TestCaseProfile {

    /** The sheet the profile is kept in. Named for what a tester would look for. */
    public static final String SHEET = "Testkunde";

    private static final Logger LOG = Logger.getLogger(TestCaseProfile.class.getName());

    private TestCaseProfile() {}

    /**
     * Records a profile on the test case the tester has taken on.
     *
     * <p>Which test case that is comes from the same selection the recorder uses
     * ({@link SelectedTestCase} through {@link AdoNaming}), so the profile lands on the test
     * case the next recording will fill — not on whatever happens to be selected in a tree.
     *
     * @param headers the export's header row
     * @param row the chosen customer's row
     * @return {@code true} when a profile was written
     */
    public static boolean saveForSelectedTestCase(List<String> headers, List<String> row) {
        SelectedTestCase selected = SelectedTestCase.read();
        if (selected == null) {
            LOG.log(Level.INFO, "No test case chosen, so no customer profile was recorded");
            return false;
        }
        try {
            return save(
                StudioTestData.get(),
                AdoNaming.scenarioName(selected.suiteName()),
                AdoNaming.testCaseName(selected.adoId(), selected.title()),
                CustomerProfile.of(headers, row)
            );
        } catch (IllegalArgumentException ex) {
            LOG.log(Level.WARNING, "Unusable test-case selection: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Records a profile on a named test case.
     *
     * @param testData Studio's handle on the open project's test data, may be {@code null}
     * @param scenario the scenario the test case belongs to
     * @param testCase the test case name
     * @param profile what to record
     * @return {@code true} when the profile was written
     */
    public static boolean save(
        ProjectTestDataApi testData,
        String scenario,
        String testCase,
        CustomerProfile profile
    ) {
        if (testData == null) {
            // An older Studio, or a harness. Not worth a dialog: the clipboard still worked.
            LOG.log(Level.INFO, "Studio offered no test data, so no customer profile was recorded");
            return false;
        }
        if (profile == null || profile.isEmpty()) {
            LOG.log(Level.INFO, "Nothing to record for {0}", testCase);
            return false;
        }

        try {
            if (!testData.addSheet(SHEET)) {
                LOG.log(Level.WARNING, "Could not open or create the {0} sheet", SHEET);
                return false;
            }
            for (String column : profile.settings().keySet()) {
                if (!testData.addColumn(SHEET, column)) {
                    LOG.log(Level.WARNING, "Could not add the column {0}", column);
                    return false;
                }
            }
            TestDataViewApi view = testData.testCase(SHEET, scenario, testCase);
            if (view == null) {
                LOG.log(Level.WARNING, "No test data record for {0} / {1}", new Object[] { scenario, testCase });
                return false;
            }
            for (Map.Entry<String, String> setting : profile.settings().entrySet()) {
                view.update(setting.getKey(), setting.getValue());
            }
            if (!testData.save(SHEET)) {
                LOG.log(Level.WARNING, "Could not write the {0} sheet", SHEET);
                return false;
            }
            LOG.log(
                Level.INFO,
                "Recorded a customer profile of {0} setting(s) on {1} / {2}",
                new Object[] { profile.settings().size(), scenario, testCase }
            );
            return true;
        } catch (RuntimeException ex) {
            // The project belongs to Studio; a plugin failing to write to it must not take
            // Studio down with it.
            LOG.log(Level.WARNING, "Could not record the customer profile", ex);
            return false;
        }
    }
}
