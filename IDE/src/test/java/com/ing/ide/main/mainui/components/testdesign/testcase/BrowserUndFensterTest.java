package com.ing.ide.main.mainui.components.testdesign.testcase;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Issue #312: Tests for browser selection (Chromium, Chrome, Edge) and
 * viewport/window size option in Playwright codegen launcher.
 */
public class BrowserUndFensterTest {

    @Test
    public void bundledChromiumProducesNoChannelArgument() {
        assertEquals(TestCaseComponent.browserChannelArgs(null), "");
        assertEquals(TestCaseComponent.browserChannelArgs(""), "");
        assertEquals(TestCaseComponent.browserChannelArgs("   "), "");
        assertEquals(TestCaseComponent.browserChannelArgs("chromium"), "");
        assertEquals(TestCaseComponent.browserChannelArgs("bundled"), "");
        assertEquals(TestCaseComponent.browserChannelArgs("default"), "");
    }

    @Test
    public void googleChromeProducesChromeChannelArgument() {
        assertEquals(TestCaseComponent.browserChannelArgs("chrome"), " --channel chrome");
        assertEquals(TestCaseComponent.browserChannelArgs("Google Chrome"), " --channel chrome");
        assertEquals(TestCaseComponent.browserChannelArgs("google-chrome"), " --channel chrome");
        assertEquals(TestCaseComponent.browserChannelArgs("CHROME"), " --channel chrome");
    }

    @Test
    public void microsoftEdgeProducesMsedgeChannelArgument() {
        assertEquals(TestCaseComponent.browserChannelArgs("msedge"), " --channel msedge");
        assertEquals(TestCaseComponent.browserChannelArgs("edge"), " --channel msedge");
        assertEquals(TestCaseComponent.browserChannelArgs("Microsoft Edge"), " --channel msedge");
        assertEquals(TestCaseComponent.browserChannelArgs("microsoft-edge"), " --channel msedge");
        assertEquals(TestCaseComponent.browserChannelArgs("MSEDGE"), " --channel msedge");
    }

    @Test
    public void customBrowserChannelIsPassedThrough() {
        assertEquals(TestCaseComponent.browserChannelArgs("chrome-beta"), " --channel chrome-beta");
        assertEquals(TestCaseComponent.browserChannelArgs("msedge-dev"), " --channel msedge-dev");
        assertEquals(
            TestCaseComponent.browserChannelArgs("custom_browser.1"),
            " --channel custom_browser.1"
        );
    }

    @Test
    public void unsafeBrowserChannelIsRejected() {
        assertEquals(TestCaseComponent.browserChannelArgs("msedge; rm -rf /"), "");
        assertEquals(TestCaseComponent.browserChannelArgs("chrome`calc`"), "");
        assertEquals(TestCaseComponent.browserChannelArgs("edge$(whoami)"), "");
        assertEquals(TestCaseComponent.browserChannelArgs("channel%20test"), "");
        assertEquals(TestCaseComponent.browserChannelArgs("browser&calc"), "");
    }

    @Test
    public void viewportArgsProducesFormattedDimensionOption() {
        String viewport = TestCaseComponent.viewportArgs();
        assertTrue(
            viewport.startsWith(" --viewport-size \""),
            "Expected --viewport-size option, got: " + viewport
        );
        assertTrue(viewport.endsWith("\""), "Expected trailing quote, got: " + viewport);
        String inside = viewport.substring(" --viewport-size \"".length(), viewport.length() - 1);
        String[] parts = inside.split(",");
        assertEquals(parts.length, 2, "Expected width and height in viewport option: " + viewport);
        int w = Integer.parseInt(parts[0].trim());
        int h = Integer.parseInt(parts[1].trim());
        assertTrue(w >= 800, "Expected reasonable width: " + w);
        assertTrue(h >= 600, "Expected reasonable height: " + h);
    }

    @Test
    public void bundledChromiumIsAlwaysConsideredInstalled() {
        assertTrue(TestCaseComponent.isBrowserInstalled(null));
        assertTrue(TestCaseComponent.isBrowserInstalled(""));
        assertTrue(TestCaseComponent.isBrowserInstalled("chromium"));
        assertTrue(TestCaseComponent.isBrowserInstalled("bundled"));
        assertTrue(TestCaseComponent.isBrowserInstalled("default"));
    }

    @Test
    public void missingBrowserIsDetected() {
        assertFalse(TestCaseComponent.isBrowserInstalled("nicht_vorhandener_browser_xyz_12345"));
    }

    @Test
    public void missingBrowserMessageGivesClearGermanSentence() {
        String chromeMsg = TestCaseComponent.missingBrowserMessage("chrome");
        assertEquals(
            chromeMsg,
            "Der Browser \"Google Chrome\" konnte auf diesem Rechner nicht gefunden werden. " +
            "Bitte Google Chrome installieren oder ohne Kanal aufnehmen (Standard-Chromium)."
        );

        String edgeMsg = TestCaseComponent.missingBrowserMessage("msedge");
        assertEquals(
            edgeMsg,
            "Der Browser \"Microsoft Edge\" konnte auf diesem Rechner nicht gefunden werden. " +
            "Bitte Microsoft Edge installieren oder ohne Kanal aufnehmen (Standard-Chromium)."
        );

        String customMsg = TestCaseComponent.missingBrowserMessage("phantom-browser");
        assertEquals(
            customMsg,
            "Der Browser \"phantom-browser\" konnte auf diesem Rechner nicht gefunden werden. " +
            "Bitte phantom-browser installieren oder ohne Kanal aufnehmen (Standard-Chromium)."
        );
    }

    @Test
    public void readsRememberedBrowserFromJson() throws IOException {
        Path tempFile = Files.createTempFile("browser-test-", ".json");
        String prev = System.getProperty("ING_QA_BROWSER_DATEI");
        try {
            String json =
                "{\n" +
                "  \"C:/Projects/Calimero\": \"chrome\",\n" +
                "  \"C:/Projects/Banking\": \"msedge\",\n" +
                "  \"default\": \"chromium\"\n" +
                "}";
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            System.setProperty("ING_QA_BROWSER_DATEI", tempFile.toString());

            assertEquals(TestCaseComponent.readRememberedBrowser("C:/Projects/Calimero"), "chrome");
            assertEquals(
                TestCaseComponent.readRememberedBrowser("C:\\Projects\\Calimero"),
                "chrome"
            );
            assertEquals(TestCaseComponent.readRememberedBrowser("C:/Projects/Banking"), "msedge");
            assertEquals(
                TestCaseComponent.readRememberedBrowser("C:\\Projects\\Banking"),
                "msedge"
            );
            // Miss returns default or empty
            assertEquals(
                TestCaseComponent.readRememberedBrowser("C:/Projects/Unknown"),
                "chromium"
            );
        } finally {
            if (prev != null) {
                System.setProperty("ING_QA_BROWSER_DATEI", prev);
            } else {
                System.clearProperty("ING_QA_BROWSER_DATEI");
            }
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * The 16.09.2026 recurring defect: the tester had to pick a browser on every Studio
     * start, and the log lied about why.
     *
     * <p>On the laptop the key of the new installation held an <em>empty</em> value while the
     * old one held {@code "chrome"} — and {@link TestCaseComponent#readRememberedBrowser}
     * reported "Kein Eintrag" for a key it printed in its own "Vorhandene Schluessel" list.
     * Present-but-empty and absent are two different states; only the first one means
     * "nobody has chosen here yet".
     */
    @Test
    public void emptyEntryIsTreatedAsNoChoiceNotAsMissingEntry() throws IOException {
        Path tempFile = Files.createTempFile("browser-leer-test-", ".json");
        String prev = System.getProperty("ING_QA_BROWSER_DATEI");
        try {
            // Exactly the laptop state of 15.09.2026, minus the same-named sibling project.
            String json = "{\n" + "  \"C:/Projects/Calimero\": \"\"\n" + "}";
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            System.setProperty("ING_QA_BROWSER_DATEI", tempFile.toString());

            assertEquals(TestCaseComponent.readRememberedBrowser("C:/Projects/Calimero"), "");
            assertEquals(TestCaseComponent.readRememberedBrowser("C:\\Projects\\Calimero"), "");
            // The empty entry is present — that is what separates it from a missing one.
            assertTrue(
                TestCaseComponent.extractKeysFromJson(json).contains("C:/Projects/Calimero")
            );
            assertFalse(
                TestCaseComponent.extractKeysFromJson(json).contains("C:/Projects/Banking")
            );
        } finally {
            if (prev != null) {
                System.setProperty("ING_QA_BROWSER_DATEI", prev);
            } else {
                System.clearProperty("ING_QA_BROWSER_DATEI");
            }
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Changing the installation directory changes the project key, not the project: the same
     * {@code Calimero} first lived under {@code nachweis-ziel}, then under
     * {@code ING-Testautomatisierung}. Both lines sit in the same file, so the existing
     * choice is adopted instead of recording with Chromium.
     */
    @Test
    public void adoptsChoiceFromSameNamedProject() throws IOException {
        Path tempFile = Files.createTempFile("browser-uebernahme-test-", ".json");
        String prev = System.getProperty("ING_QA_BROWSER_DATEI");
        String alt = "C:/Users/PC28GR/nachweis-ziel/studio/Projects/Calimero";
        String neu =
            "C:/Users/PC28GR/AppData/Local/ING-Testautomatisierung/studio/Projects/Calimero";
        try {
            // The laptop's file verbatim, as of 15.09.2026 10:52.
            String json =
                "{\n" + "  \"" + alt + "\": \"chrome\",\n" + "  \"" + neu + "\": \"\"\n" + "}";
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            System.setProperty("ING_QA_BROWSER_DATEI", tempFile.toString());

            assertEquals(TestCaseComponent.readRememberedBrowser(neu), "chrome");
            assertEquals(TestCaseComponent.readRememberedBrowser(alt), "chrome");
            assertEquals(
                TestCaseComponent.browserChannelArgs(TestCaseComponent.readRememberedBrowser(neu)),
                " --channel chrome"
            );
        } finally {
            if (prev != null) {
                System.setProperty("ING_QA_BROWSER_DATEI", prev);
            } else {
                System.clearProperty("ING_QA_BROWSER_DATEI");
            }
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void doesNotAdoptChoiceFromDifferentlyNamedProject() throws IOException {
        Path tempFile = Files.createTempFile("browser-fremd-test-", ".json");
        String prev = System.getProperty("ING_QA_BROWSER_DATEI");
        try {
            String json =
                "{\n" +
                "  \"C:/alt/studio/Projects/Banking\": \"msedge\",\n" +
                "  \"C:/neu/studio/Projects/Calimero\": \"\"\n" +
                "}";
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            System.setProperty("ING_QA_BROWSER_DATEI", tempFile.toString());

            assertEquals(
                TestCaseComponent.readRememberedBrowser("C:/neu/studio/Projects/Calimero"),
                ""
            );
        } finally {
            if (prev != null) {
                System.setProperty("ING_QA_BROWSER_DATEI", prev);
            } else {
                System.clearProperty("ING_QA_BROWSER_DATEI");
            }
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void projektNameReturnsLastPathSegment() {
        assertEquals(TestCaseComponent.projektName("C:/neu/studio/Projects/Calimero"), "Calimero");
        assertEquals(TestCaseComponent.projektName("C:\\alt\\Projects\\Calimero"), "Calimero");
        assertEquals(TestCaseComponent.projektName("C:/alt/Projects/Calimero/"), "Calimero");
        assertEquals(TestCaseComponent.projektName("ohne-projekt"), "ohne-projekt");
        assertEquals(TestCaseComponent.projektName(""), "");
        assertEquals(TestCaseComponent.projektName(null), "");
    }

    @Test
    public void resolveRecordingStartModusMatchesAcrossSlashVariations() throws IOException {
        Path tempFile = Files.createTempFile("aufnahme-start-test-", ".json");
        String prev = System.getProperty("ING_QA_AUFNAHME_START_DATEI");
        try {
            String json =
                "{\n" +
                "  \"C:/Projects/Calimero\": \"weiter\",\n" +
                "  \"C:/Projects/Banking\": \"startadresse\"\n" +
                "}";
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            System.setProperty("ING_QA_AUFNAHME_START_DATEI", tempFile.toString());

            // Both forward and backward slashes in project location resolve correctly
            assertEquals(
                TestCaseComponent.resolveRecordingStartModus("C:/Projects/Calimero"),
                "weiter"
            );
            assertEquals(
                TestCaseComponent.resolveRecordingStartModus("C:\\Projects\\Calimero"),
                "weiter"
            );
            assertEquals(
                TestCaseComponent.resolveRecordingStartModus("C:/Projects/Banking"),
                "startadresse"
            );
            assertEquals(
                TestCaseComponent.resolveRecordingStartModus("C:\\Projects\\Banking"),
                "startadresse"
            );

            // Miss falls back to startadresse and logs keys
            assertEquals(
                TestCaseComponent.resolveRecordingStartModus("C:/Projects/Unbekannt"),
                "startadresse"
            );
        } finally {
            if (prev != null) {
                System.setProperty("ING_QA_AUFNAHME_START_DATEI", prev);
            } else {
                System.clearProperty("ING_QA_AUFNAHME_START_DATEI");
            }
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void extractKeysFromJsonExtractsAllKeys() {
        String json =
            "{\n" +
            "  \"C:/Projects/Calimero\": \"weiter\",\n" +
            "  \"C:\\\\Projects\\\\Banking\": \"startadresse\",\n" +
            "  \"quoted\\\"key\": \"value\"\n" +
            "}";
        var keys = TestCaseComponent.extractKeysFromJson(json);
        assertTrue(keys.contains("C:/Projects/Calimero"), "Keys: " + keys);
        assertTrue(keys.contains("C:\\Projects\\Banking"), "Keys: " + keys);
        assertTrue(keys.contains("quoted\"key"), "Keys: " + keys);
    }
}
