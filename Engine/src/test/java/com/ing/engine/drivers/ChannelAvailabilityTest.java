package com.ing.engine.drivers;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserType.LaunchOptions;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Which configured browser channel a run may use: a channel whose program is missing, or which
 * company policy locks for automation, must give way to the bundled Chromium instead of ending
 * the run before its first step.
 */
public class ChannelAvailabilityTest {
    private Function<String, Integer> savedPolicy;
    private Function<String, String> savedEnv;
    private boolean savedWindows;
    private Path root;
    private final Map<String, Integer> policies = new HashMap<>();
    private final Map<String, String> environment = new HashMap<>();

    @BeforeMethod
    public void setUp() throws Exception {
        savedPolicy = ChannelAvailability.policyReader;
        savedEnv = ChannelAvailability.env;
        savedWindows = ChannelAvailability.windows;
        root = Files.createTempDirectory("channel-availability-");
        policies.clear();
        environment.clear();
        environment.put("LOCALAPPDATA", root.resolve("local").toString());
        environment.put("ProgramFiles", root.resolve("pf").toString());
        ChannelAvailability.policyReader = policies::get;
        ChannelAvailability.env = environment::get;
        ChannelAvailability.windows = true;
    }

    @AfterMethod
    public void tearDown() throws Exception {
        ChannelAvailability.policyReader = savedPolicy;
        ChannelAvailability.env = savedEnv;
        ChannelAvailability.windows = savedWindows;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private void install(String base, String relative) throws Exception {
        Path exe = root.resolve(base).resolve(relative);
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "stand-in");
    }

    private static LaunchOptions launch(String... caps) throws Exception {
        Method m =
            PlaywrightDriverFactory.class.getDeclaredMethod(
                    "addLaunchOptions",
                    LaunchOptions.class,
                    List.class
                );
        m.setAccessible(true);
        return (LaunchOptions) m.invoke(null, new LaunchOptions(), Arrays.asList(caps));
    }

    @Test
    public void installedAndUnlockedEdgeIsUsed() throws Exception {
        install("pf", "Microsoft\\Edge\\Application\\msedge.exe");
        assertThat(ChannelAvailability.unusableReason("msedge")).isNull();
        assertThat(launch("setChannel=msedge").channel).isEqualTo("msedge");
    }

    @Test
    public void edgeLockedByPolicyFallsBackToBundledChromium() throws Exception {
        install("pf", "Microsoft\\Edge\\Application\\msedge.exe");
        policies.put("HKCU\\SOFTWARE\\Policies\\Microsoft\\Edge", 0);
        assertThat(ChannelAvailability.unusableReason("msedge"))
            .isEqualTo(
                "Microsoft Edge ist auf diesem Rechner für die Automatisierung gesperrt (Firmenrichtlinie)."
            );
        assertThat(launch("setChannel=msedge", "setHeadless=false").channel).isNull();
    }

    @Test
    public void machinePolicyWinsOverUserPolicy() throws Exception {
        install("local", "Google\\Chrome\\Application\\chrome.exe");
        policies.put("HKLM\\SOFTWARE\\Policies\\Google\\Chrome", 0);
        policies.put("HKCU\\SOFTWARE\\Policies\\Google\\Chrome", 1);
        assertThat(ChannelAvailability.unusableReason("chrome")).contains("Firmenrichtlinie");
        policies.put("HKLM\\SOFTWARE\\Policies\\Google\\Chrome", 1);
        policies.put("HKCU\\SOFTWARE\\Policies\\Google\\Chrome", 0);
        assertThat(ChannelAvailability.unusableReason("chrome")).isNull();
    }

    @Test
    public void policyForTheOtherBrowserDoesNotLockThisOne() throws Exception {
        install("pf", "Microsoft\\Edge\\Application\\msedge.exe");
        policies.put("HKLM\\SOFTWARE\\Policies\\Google\\Chrome", 0);
        policies.put("HKCU\\SOFTWARE\\Policies\\Chromium", 0);
        assertThat(ChannelAvailability.unusableReason("msedge")).isNull();
    }

    @Test
    public void missingChromeFallsBackToBundledChromium() throws Exception {
        assertThat(ChannelAvailability.unusableReason("chrome"))
            .isEqualTo("Google Chrome ist auf diesem Rechner nicht installiert.");
        assertThat(launch("setChannel=chrome").channel).isNull();
    }

    @Test
    public void unknownChannelsAndOtherSystemsAreLeftAlone() throws Exception {
        assertThat(ChannelAvailability.unusableReason("chromium")).isNull();
        assertThat(ChannelAvailability.unusableReason("some-future-channel")).isNull();
        ChannelAvailability.windows = false;
        assertThat(ChannelAvailability.unusableReason("msedge")).isNull();
        assertThat(launch("setChannel=msedge").channel).isEqualTo("msedge");
    }

    @Test
    public void bundledBrowsersAreFoundInThePlaywrightFolder() throws Exception {
        Path browsers = root.resolve("ms-playwright");
        Files.createDirectories(browsers.resolve("chromium-1234"));
        Files.createDirectories(browsers.resolve("ffmpeg-1011"));
        environment.put("PLAYWRIGHT_BROWSERS_PATH", browsers.toString());
        assertThat(ChannelAvailability.bundledInstalled("Chromium")).isTrue();
        assertThat(ChannelAvailability.bundledInstalled("Firefox")).isFalse();
        assertThat(ChannelAvailability.bundledInstalled("WebKit")).isFalse();
        Files.createDirectories(browsers.resolve("webkit-2200"));
        assertThat(ChannelAvailability.bundledInstalled("WebKit")).isTrue();
    }

    @Test
    public void withoutABrowsersFolderNothingIsHidden() {
        environment.put("PLAYWRIGHT_BROWSERS_PATH", root.resolve("gibt-es-nicht").toString());
        assertThat(ChannelAvailability.bundledInstalled("Firefox")).isTrue();
    }

    @Test
    public void runMenusOfferOnlyBrowsersThatCanStart() throws Exception {
        Path browsers = root.resolve("ms-playwright");
        Files.createDirectories(browsers.resolve("chromium-1234"));
        environment.put("PLAYWRIGHT_BROWSERS_PATH", browsers.toString());
        assertThat(
                ChannelAvailability.offeredBrowsers(
                    PlaywrightDriverFactory.Browser.getValuesAsList()
                )
            )
            .containsExactly("Chromium", "No Browser");
        Files.createDirectories(browsers.resolve("firefox-1500"));
        assertThat(
                ChannelAvailability.offeredBrowsers(
                    PlaywrightDriverFactory.Browser.getValuesAsList()
                )
            )
            .containsExactly("Chromium", "Firefox", "No Browser");
    }
}
