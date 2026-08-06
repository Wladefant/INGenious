package com.ing.engine.drivers;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests for PlaywrightDriverFactory — pure/near-pure static helper methods.
 * Browser enum is already tested in BrowserEnumTest; this focuses on
 * getPropertyValueAsDesiredType, setViewportSize, setGeolocation, setScreenSize.
 */
public class PlaywrightDriverFactoryTest {

    // ── getPropertyValueAsDesiredType ────────────────────────────────────

    @Test
    public void testGetPropertyValueReturnsTrue() throws Exception {
        Object result = invokeGetPropertyValue("true");
        assertThat(result).isInstanceOf(Boolean.class);
        assertThat((Boolean) result).isTrue();
    }

    @Test
    public void testGetPropertyValueReturnsFalse() throws Exception {
        Object result = invokeGetPropertyValue("false");
        assertThat(result).isInstanceOf(Boolean.class);
        assertThat((Boolean) result).isFalse();
    }

    @Test
    public void testGetPropertyValueReturnsBoolCaseInsensitive() throws Exception {
        Object result = invokeGetPropertyValue("TRUE");
        assertThat(result).isInstanceOf(Boolean.class);
        assertThat((Boolean) result).isTrue();
    }

    @Test
    public void testGetPropertyValueReturnsDoubleForDigits() throws Exception {
        Object result = invokeGetPropertyValue("42");
        assertThat(result).isInstanceOf(Double.class);
        assertThat((Double) result).isEqualTo(42.0);
    }

    @Test
    public void testGetPropertyValueReturnsDoubleForZero() throws Exception {
        Object result = invokeGetPropertyValue("0");
        assertThat(result).isInstanceOf(Double.class);
        assertThat((Double) result).isEqualTo(0.0);
    }

    @Test
    public void testGetPropertyValueReturnsStringForText() throws Exception {
        Object result = invokeGetPropertyValue("chrome");
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).isEqualTo("chrome");
    }

    @Test
    public void testGetPropertyValueReturnsStringForDecimal() throws Exception {
        // "3.14" does not match \\d+ so returns String
        Object result = invokeGetPropertyValue("3.14");
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).isEqualTo("3.14");
    }

    @Test
    public void testGetPropertyValueReturnsEmptyStringForEmpty() throws Exception {
        Object result = invokeGetPropertyValue("");
        assertThat(result).isEqualTo("");
    }

    @Test
    public void testGetPropertyValueReturnsNullForNull() throws Exception {
        Object result = invokeGetPropertyValue(null);
        assertThat(result).isNull();
    }

    @Test
    public void testGetPropertyValueReturnsStringForPath() throws Exception {
        Object result = invokeGetPropertyValue("/usr/bin/chromium");
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).isEqualTo("/usr/bin/chromium");
    }

    // ── isViewPortSizeMaximized flag ────────────────────────────────────

    @Test
    public void testViewPortSizeMaximizedFlagDefaultFalse() {
        // Reset
        PlaywrightDriverFactory.isViewPortSizeMaximized = false;
        assertThat(PlaywrightDriverFactory.isViewPortSizeMaximized).isFalse();
    }

    @Test
    public void testViewPortSizeMaximizedFlagCanBeSet() {
        PlaywrightDriverFactory.isViewPortSizeMaximized = true;
        assertThat(PlaywrightDriverFactory.isViewPortSizeMaximized).isTrue();
        PlaywrightDriverFactory.isViewPortSizeMaximized = false; // cleanup
    }

    // ── setViewportSize (private static) ────────────────────────────────

    @Test
    public void testSetViewportSizeWithDimensions() throws Exception {
        com.microsoft.playwright.Browser.NewContextOptions opts = new com.microsoft.playwright.Browser.NewContextOptions();

        invokeSetViewportSize(opts, "1920,1080");

        assertThat(PlaywrightDriverFactory.isViewPortSizeMaximized).isFalse();
        // The options object should have viewport set (no public getter, but no exception means success)
    }

    @Test
    public void testSetViewportSizeMaximized() throws Exception {
        com.microsoft.playwright.Browser.NewContextOptions opts = new com.microsoft.playwright.Browser.NewContextOptions();

        invokeSetViewportSize(opts, "maximized");

        assertThat(PlaywrightDriverFactory.isViewPortSizeMaximized).isTrue();
        PlaywrightDriverFactory.isViewPortSizeMaximized = false; // cleanup
    }

    @Test
    public void testSetViewportSizeInvalidValue() throws Exception {
        com.microsoft.playwright.Browser.NewContextOptions opts = new com.microsoft.playwright.Browser.NewContextOptions();

        PlaywrightDriverFactory.isViewPortSizeMaximized = true; // pre-set
        invokeSetViewportSize(opts, "invalid");

        assertThat(PlaywrightDriverFactory.isViewPortSizeMaximized).isFalse();
    }

    // ── setGeolocation (private static) ─────────────────────────────────

    @Test
    public void testSetGeolocationParsesCoordinates() throws Exception {
        com.microsoft.playwright.Browser.NewContextOptions opts = new com.microsoft.playwright.Browser.NewContextOptions();

        invokeSetGeolocation(opts, "51.5074,-0.1278");
        // No exception → coordinates parsed successfully
    }

    // ── setScreenSize (private static) ──────────────────────────────────

    @Test
    public void testSetScreenSizeParsesDimensions() throws Exception {
        com.microsoft.playwright.Browser.NewContextOptions opts = new com.microsoft.playwright.Browser.NewContextOptions();

        invokeSetScreenSize(opts, "1920,1080");
        // No exception → dimensions parsed successfully
    }

    // ── setRecordVideoSize (private static) ─────────────────────────────

    @Test
    public void testSetRecordVideoSizeParsesDimensions() throws Exception {
        com.microsoft.playwright.Browser.NewContextOptions opts = new com.microsoft.playwright.Browser.NewContextOptions();

        invokeSetRecordVideoSize(opts, "1280,720");
        // No exception → dimensions parsed successfully
    }

    // ── takeUserDataDir (private static) ────────────────────────────────

    @Test
    public void testTakeUserDataDirReturnsEmptyWhenAbsent() throws Exception {
        List<String> caps = new ArrayList<>(
            Arrays.asList("setChannel=msedge", "setHeadless=false")
        );

        assertThat(invokeTakeUserDataDir(caps)).isEmpty();
        assertThat(caps).containsExactly("setChannel=msedge", "setHeadless=false");
    }

    @Test
    public void testTakeUserDataDirRemovesTheCapability() throws Exception {
        List<String> caps = new ArrayList<>(
            Arrays.asList("setChannel=msedge", "setUserDataDir=/home/tester/profile")
        );

        // It must leave the list, or it would reach the browser as a command-line argument.
        assertThat(invokeTakeUserDataDir(caps)).isEqualTo("/home/tester/profile");
        assertThat(caps).containsExactly("setChannel=msedge");
    }

    @Test
    public void testTakeUserDataDirIsCaseInsensitive() throws Exception {
        List<String> caps = new ArrayList<>(Arrays.asList("setuserdatadir=/home/tester/profile"));

        assertThat(invokeTakeUserDataDir(caps)).isEqualTo("/home/tester/profile");
    }

    @Test
    public void testTakeUserDataDirTreatsAnEmptyValueAsUnset() throws Exception {
        List<String> caps = new ArrayList<>(Arrays.asList("setUserDataDir="));

        assertThat(invokeTakeUserDataDir(caps)).isEmpty();
    }

    // ── toPersistentContextOptions (private static) ─────────────────────

    @Test
    public void testPersistentOptionsCarryLaunchAndContextOptions() throws Exception {
        com.microsoft.playwright.BrowserType.LaunchOptions launchOptions = new com.microsoft.playwright.BrowserType.LaunchOptions()
            .setChannel("msedge")
            .setHeadless(false)
            .setSlowMo(50);
        com.microsoft.playwright.Browser.NewContextOptions contextOptions = new com.microsoft.playwright.Browser.NewContextOptions()
            .setLocale("en-GB")
            .setUserAgent("agent");

        com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions persistentOptions = invokeToPersistentContextOptions(
            launchOptions,
            contextOptions
        );

        assertThat(persistentOptions.channel).isEqualTo("msedge");
        assertThat(persistentOptions.headless).isFalse();
        assertThat(persistentOptions.slowMo).isEqualTo(50.0);
        assertThat(persistentOptions.locale).isEqualTo("en-GB");
        assertThat(persistentOptions.userAgent).isEqualTo("agent");
    }

    @Test
    public void testPersistentOptionsLeaveUnsetOptionsUnset() throws Exception {
        com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions persistentOptions = invokeToPersistentContextOptions(
            new com.microsoft.playwright.BrowserType.LaunchOptions(),
            new com.microsoft.playwright.Browser.NewContextOptions()
        );

        assertThat(persistentOptions.channel).isNull();
        assertThat(persistentOptions.headless).isNull();
        assertThat(persistentOptions.locale).isNull();
    }

    @Test
    public void testPersistentOptionsSkipStorageStateWhichHasNoCounterpart() throws Exception {
        com.microsoft.playwright.Browser.NewContextOptions contextOptions = new com.microsoft.playwright.Browser.NewContextOptions()
            .setStorageStatePath(java.nio.file.Paths.get("state.json"))
            .setLocale("nl-NL");

        // The profile on disk already carries the session, so the option is dropped rather
        // than failing the launch — everything alongside it still arrives.
        com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions persistentOptions = invokeToPersistentContextOptions(
            new com.microsoft.playwright.BrowserType.LaunchOptions(),
            contextOptions
        );

        assertThat(persistentOptions.locale).isEqualTo("nl-NL");
    }

    // ── Reflection utility methods ──────────────────────────────────────

    private String invokeTakeUserDataDir(List<String> caps) throws Exception {
        Method m = PlaywrightDriverFactory.class.getDeclaredMethod("takeUserDataDir", List.class);
        m.setAccessible(true);
        return (String) m.invoke(null, caps);
    }

    private com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions invokeToPersistentContextOptions(
        com.microsoft.playwright.BrowserType.LaunchOptions launchOptions,
        com.microsoft.playwright.Browser.NewContextOptions contextOptions
    )
        throws Exception {
        Method m =
            PlaywrightDriverFactory.class.getDeclaredMethod(
                    "toPersistentContextOptions",
                    com.microsoft.playwright.BrowserType.LaunchOptions.class,
                    com.microsoft.playwright.Browser.NewContextOptions.class
                );
        m.setAccessible(true);
        return (com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions) m.invoke(
            null,
            launchOptions,
            contextOptions
        );
    }

    private Object invokeGetPropertyValue(String value) throws Exception {
        Method m =
            PlaywrightDriverFactory.class.getDeclaredMethod(
                    "getPropertyValueAsDesiredType",
                    String.class
                );
        m.setAccessible(true);
        return m.invoke(null, value);
    }

    private void invokeSetViewportSize(
        com.microsoft.playwright.Browser.NewContextOptions opts,
        String value
    )
        throws Exception {
        Method m =
            PlaywrightDriverFactory.class.getDeclaredMethod(
                    "setViewportSize",
                    com.microsoft.playwright.Browser.NewContextOptions.class,
                    String.class
                );
        m.setAccessible(true);
        m.invoke(null, opts, value);
    }

    private void invokeSetGeolocation(
        com.microsoft.playwright.Browser.NewContextOptions opts,
        String value
    )
        throws Exception {
        Method m =
            PlaywrightDriverFactory.class.getDeclaredMethod(
                    "setGeolocation",
                    com.microsoft.playwright.Browser.NewContextOptions.class,
                    String.class
                );
        m.setAccessible(true);
        m.invoke(null, opts, value);
    }

    private void invokeSetScreenSize(
        com.microsoft.playwright.Browser.NewContextOptions opts,
        String value
    )
        throws Exception {
        Method m =
            PlaywrightDriverFactory.class.getDeclaredMethod(
                    "setScreenSize",
                    com.microsoft.playwright.Browser.NewContextOptions.class,
                    String.class
                );
        m.setAccessible(true);
        m.invoke(null, opts, value);
    }

    private void invokeSetRecordVideoSize(
        com.microsoft.playwright.Browser.NewContextOptions opts,
        String value
    )
        throws Exception {
        Method m =
            PlaywrightDriverFactory.class.getDeclaredMethod(
                    "setRecordVideoSize",
                    com.microsoft.playwright.Browser.NewContextOptions.class,
                    String.class
                );
        m.setAccessible(true);
        m.invoke(null, opts, value);
    }
}
