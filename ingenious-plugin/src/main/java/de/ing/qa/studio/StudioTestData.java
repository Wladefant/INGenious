package de.ing.qa.studio;

import com.ing.ingenious.api.contract.data.ProjectTestDataApi;

/**
 * The handle on the open project's test data, as Studio hands it to this plugin.
 *
 * <p>Studio gives the handle to a panel through {@code StudioPanelApi.setProjectTestData} —
 * that is, to <em>one</em> of this plugin's entry classes. Every other class in the plugin has
 * to be able to reach it, so it is parked here.
 *
 * <p>A static field is only a working channel because a plugin folder now keeps a single class
 * loader (INGenious PR #314). Before that, each lookup of the plugin produced a fresh class
 * loader and therefore a fresh copy of this class, so what one entry class stored the next
 * could not see — the reason {@link SelectedTestCase} goes through a file instead. That
 * detour is not needed for a handle whose lifetime is the session's.
 */
public final class StudioTestData {

    private static volatile ProjectTestDataApi testData;

    private StudioTestData() {}

    /**
     * Records the handle Studio supplied.
     *
     * @param supplied the open project's test data, may be {@code null}
     */
    public static void set(ProjectTestDataApi supplied) {
        testData = supplied;
    }

    /**
     * The handle, or {@code null} when this plugin is running somewhere that has not offered
     * one — an older Studio, or a harness. Callers treat that as "nothing to write to", never
     * as an error worth interrupting the tester for.
     *
     * @return the handle, or {@code null}
     */
    public static ProjectTestDataApi get() {
        return testData;
    }
}
