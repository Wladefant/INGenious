package com.ing.engine.drivers;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tells whether a browser a run is configured for can actually be launched on this machine.
 *
 * <p>Two things make a configured browser unusable before Playwright even tries it:
 * <ul>
 *   <li>a Chrome/Edge channel ({@code setChannel=chrome}, {@code msedge}, ...) whose program is
 *       not installed, and</li>
 *   <li>a Chrome/Edge channel that company policy locks for automation:
 *       {@code RemoteDebuggingAllowed=0} under {@code SOFTWARE\Policies\Google\Chrome} or
 *       {@code SOFTWARE\Policies\Microsoft\Edge} (HKLM first, then HKCU). Playwright drives the
 *       browser over the DevTools protocol, which that policy switches off; the launch then dies
 *       with "DevTools remote debugging is disallowed by the system admin".</li>
 * </ul>
 * The bundled Chromium reads neither key (measured 2026-09-23: Playwright's Chromium 145 with
 * {@code HKCU\SOFTWARE\Policies\Chromium\RemoteDebuggingAllowed=0} starts normally and
 * {@code chrome://policy} shows it as not set), so it is the fallback and is never judged by the
 * registry. Firefox and WebKit are only usable when Playwright's copy of them was installed —
 * the ING tester package ships Chromium alone.
 *
 * <p>Same rules as the recorder panel ({@code BrowserVerfuegbarkeit}) and the installer's
 * channel check ({@code TestING-ZURUECKSETZEN.ps1 -Kanalpflege}).
 */
public final class ChannelAvailability {
    private static final Logger LOG = Logger.getLogger(ChannelAvailability.class.getName());

    /** Reads {@code RemoteDebuggingAllowed} under a policy key; {@code null} when not set. */
    static Function<String, Integer> policyReader = ChannelAvailability::readPolicyFromRegistry;
    /** Environment lookup, replaceable in tests. */
    static Function<String, String> env = System::getenv;
    /** Whether this is Windows; replaceable in tests. */
    static boolean windows = System
        .getProperty("os.name", "")
        .toLowerCase(Locale.ROOT)
        .contains("win");

    private ChannelAvailability() {}

    /**
     * Why the given Playwright channel cannot be used here, as one plain German sentence for the
     * tester — or {@code null} when nothing speaks against it. Unknown channels and anything off
     * Windows return {@code null}: this check only removes what it knows to be dead.
     */
    public static String unusableReason(String channel) {
        if (channel == null || !windows) {
            return null;
        }
        String c = channel.trim().toLowerCase(Locale.ROOT);
        String name = displayName(c);
        String exe = executable(c);
        if (name == null || exe == null) {
            return null;
        }
        String key = policyKey(c);
        if (key != null) {
            for (String hive : List.of("HKLM", "HKCU")) {
                Integer value = policyReader.apply(hive + "\\SOFTWARE\\Policies\\" + key);
                if (value != null) {
                    if (value == 0) {
                        return (
                            name +
                            " ist auf diesem Rechner für die Automatisierung gesperrt (Firmenrichtlinie)."
                        );
                    }
                    break;
                }
            }
        }
        if (!installed(exe)) {
            return name + " ist auf diesem Rechner nicht installiert.";
        }
        return null;
    }

    /**
     * The Playwright browsers worth offering in a run menu on this machine. Firefox and WebKit
     * run only when Playwright's own copy of them is installed; the ING tester package ships
     * Chromium alone, so offering them there only leads to a run that fails at launch. Chromium
     * and "No Browser" are always offered: the bundled Chromium is what every run can fall back
     * to (see {@link #unusableReason(String)}).
     */
    public static List<String> offeredBrowsers(List<String> all) {
        List<String> offered = new ArrayList<>();
        for (String browser : all) {
            boolean bundledOnly =
                PlaywrightDriverFactory.Browser.Firefox.getBrowserValue().equals(browser) ||
                PlaywrightDriverFactory.Browser.WebKit.getBrowserValue().equals(browser);
            if (bundledOnly && !bundledInstalled(browser)) {
                LOG.info(
                    () -> "Run menu: " + browser + " is not installed here and is not offered."
                );
                continue;
            }
            offered.add(browser);
        }
        return offered;
    }

    /**
     * Whether Playwright's own copy of a browser ({@code firefox}, {@code webkit},
     * {@code chromium}) is present in the browsers folder. {@code true} when the folder itself
     * cannot be found: then nothing is known and nothing is hidden.
     */
    public static boolean bundledInstalled(String browser) {
        if (browser == null) {
            return true;
        }
        Path root = browsersPath();
        if (root == null || !Files.isDirectory(root)) {
            return true;
        }
        String prefix = browser.trim().toLowerCase(Locale.ROOT) + "-";
        try (Stream<Path> entries = Files.list(root)) {
            return entries.anyMatch(
                p ->
                    Files.isDirectory(p) &&
                    p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(prefix)
            );
        } catch (Exception ex) {
            return true;
        }
    }

    static Path browsersPath() {
        String configured = env.apply("PLAYWRIGHT_BROWSERS_PATH");
        if (configured != null && !configured.isBlank() && !"0".equals(configured.trim())) {
            return Paths.get(configured.trim());
        }
        if (!windows) {
            return null;
        }
        String local = env.apply("LOCALAPPDATA");
        return local == null || local.isBlank() ? null : Paths.get(local, "ms-playwright");
    }

    private static String displayName(String c) {
        if (c.startsWith("chrome")) {
            return "Google Chrome";
        }
        if (c.startsWith("msedge")) {
            return "Microsoft Edge";
        }
        return null;
    }

    private static String policyKey(String c) {
        if (c.startsWith("chrome")) {
            return "Google\\Chrome";
        }
        if (c.startsWith("msedge")) {
            return "Microsoft\\Edge";
        }
        return null;
    }

    /** Install location below LOCALAPPDATA / Program Files, as Playwright looks for it. */
    private static String executable(String c) {
        switch (c) {
            case "chrome":
                return "Google\\Chrome\\Application\\chrome.exe";
            case "chrome-beta":
                return "Google\\Chrome Beta\\Application\\chrome.exe";
            case "chrome-dev":
                return "Google\\Chrome Dev\\Application\\chrome.exe";
            case "chrome-canary":
                return "Google\\Chrome SxS\\Application\\chrome.exe";
            case "msedge":
                return "Microsoft\\Edge\\Application\\msedge.exe";
            case "msedge-beta":
                return "Microsoft\\Edge Beta\\Application\\msedge.exe";
            case "msedge-dev":
                return "Microsoft\\Edge Dev\\Application\\msedge.exe";
            case "msedge-canary":
                return "Microsoft\\Edge SxS\\Application\\msedge.exe";
            default:
                return null;
        }
    }

    private static boolean installed(String relative) {
        for (String root : List.of("LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)")) {
            String base = env.apply(root);
            if (base != null && !base.isBlank() && Files.isRegularFile(Paths.get(base, relative))) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern DWORD = Pattern.compile(
        "RemoteDebuggingAllowed\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)"
    );

    private static Integer readPolicyFromRegistry(String key) {
        String out = run(
            new ProcessBuilder("reg", "query", key, "/v", "RemoteDebuggingAllowed")
            .redirectErrorStream(true),
            REGISTRY_TIMEOUT_MS
        );
        if (out == null) {
            return null;
        }
        Matcher m = DWORD.matcher(out);
        return m.find() ? Integer.parseInt(m.group(1), 16) : null;
    }

    /** Deadline per {@code reg query}; measured 28–35 ms per query (review of ing-qa-automation#890). */
    static final long REGISTRY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    /**
     * Runs a child process and returns its output — {@code null} when it cannot start, exits
     * non-zero or outlives {@code timeoutMs}. Output is drained on its own thread and the wait is
     * on the process, so the deadline actually applies: reading to end of stream first (as this
     * class did until the review of ing-qa-automation#890) blocks for as long as the child keeps
     * its output open, and {@code waitFor} only started afterwards. A child that outlives the
     * deadline is killed together with its descendants.
     */
    static String run(ProcessBuilder pb, long timeoutMs) {
        Process p;
        try {
            p = pb.start();
        } catch (Exception ex) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(
            () -> {
                try (InputStream in = p.getInputStream()) {
                    in.transferTo(out);
                } catch (Exception ex) {
                    // stream closed because the process was killed: keep what was read
                }
            },
            "channel-availability-output"
        );
        reader.setDaemon(true);
        reader.start();
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                LOG.warning(
                    () ->
                        String.join(" ", pb.command()) +
                        " ran longer than " +
                        timeoutMs +
                        " ms and was killed"
                );
                return null;
            }
            reader.join(TimeUnit.SECONDS.toMillis(1));
            return p.exitValue() == 0 ? out.toString(Charset.defaultCharset()) : null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        }
    }
}
