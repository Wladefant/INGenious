package com.ing.ide.main.mainui.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Exercises the start-screen switch: which screen Studio brings to the front after the first
 * project load, and every way that answer can be "none".
 *
 * <p>The configuration file here is a real file written with the shape
 * {@code INSTALLIEREN.ps1} produces — a flat object of string values, Windows paths with
 * escaped backslashes among them — because that file is the whole input of
 * {@link Startansicht#wunsch()}.
 */
public class StartansichtTest {
    private static final String IDENTITY = "ing-tester-panel";
    private static final String TITLE = "TestING";
    private static final String SLIDE = "Plugin:ing-tester-panel";

    private Path temporaryDirectory;

    @BeforeMethod
    public void setUp() throws IOException {
        temporaryDirectory = Files.createTempDirectory("startansicht-test-");
        System.clearProperty(Startansicht.SYS_WUNSCH);
        // Point the reader at this test's own file, so a konfiguration.json that happens to
        // exist on the machine running the build cannot decide the outcome.
        System.setProperty(
            Startansicht.SYS_DATEI,
            temporaryDirectory.resolve("konfiguration.json").toString()
        );
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        System.clearProperty(Startansicht.SYS_WUNSCH);
        System.clearProperty(Startansicht.SYS_DATEI);
        if (temporaryDirectory != null && Files.exists(temporaryDirectory)) {
            Files
                .walk(temporaryDirectory)
                .sorted(Collections.reverseOrder())
                .forEach(
                    path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // A leftover temp file is not a test failure.
                        }
                    }
                );
        }
    }

    @Test
    public void defaultsToTheTesterScreenWhenNothingIsConfigured() {
        assertThat(Startansicht.wunsch()).isEqualTo("testing");
    }

    @Test
    public void readsTheSwitchFromTheInstallationsConfiguration() throws IOException {
        writeConfiguration(
            "{\n    \"ziel\": \"C:\\\\Users\\\\pc\\\\ING\",\n" +
            "    \"startansicht\": \"TestING\"\n}"
        );

        assertThat(Startansicht.wunsch()).isEqualTo("TestING");
    }

    @Test
    public void anOffValueInTheConfigurationLeavesStudioWhereItWas() throws IOException {
        writeConfiguration("{\"startansicht\": \"aus\"}");

        assertThat(Startansicht.wunsch()).isNull();
    }

    @Test
    public void studioIsAnOffValueToo() throws IOException {
        writeConfiguration("{\"startansicht\": \"Studio\"}");

        assertThat(Startansicht.wunsch()).isNull();
    }

    @Test
    public void aSystemPropertyOverridesTheConfiguration() throws IOException {
        writeConfiguration("{\"startansicht\": \"testing\"}");
        System.setProperty(Startansicht.SYS_WUNSCH, "aus");

        assertThat(Startansicht.wunsch()).isNull();
    }

    @Test
    public void aBlankValueIsNotAnAnswerAndTheDefaultStands() throws IOException {
        writeConfiguration("{\"startansicht\": \"   \"}");

        assertThat(Startansicht.wunsch()).isEqualTo("testing");
    }

    @Test
    public void readsTheFileInTheShapePowerShellActuallyWrites() throws IOException {
        // What INSTALLIEREN.ps1 leaves behind: ConvertTo-Json piped into Set-Content, so CRLF
        // line endings, four-space indentation, escaped Windows paths, the switch somewhere in
        // the middle. Every other test here writes LF, which is not what any tester's machine
        // has - the reader has to be indifferent to that, and this is where it is shown.
        writeConfiguration(
            "\uFEFF{\r\n" +
            "    \"ziel\": \"C:\\\\Users\\\\PC28GR\\\\AppData\\\\Local\\\\ING-Testautomatisierung\",\r\n" +
            "    \"javaHome\": \"C:\\\\Program Files\\\\Microsoft\\\\jdk-17\",\r\n" +
            "    \"startansicht\": \"testing\",\r\n" +
            "    \"zuletzt\": \"2026-09-11 09:12\"\r\n" +
            "}\r\n"
        );

        assertThat(Startansicht.wunsch()).isEqualTo("testing");
    }

    @Test
    public void aSwitchThatIsNotAStringLeavesTheDefaultStanding() throws IOException {
        // Somebody edits the file by hand and writes a number or a boolean. There is no value
        // to read, so nothing was said - and the neighbouring keys must not be misread as the
        // answer either.
        writeConfiguration("{\"startansicht\": 1, \"ziel\": \"C:\\\\ING\"}");

        assertThat(Startansicht.wunsch()).isEqualTo("testing");
    }

    @Test
    public void anUnreadableConfigurationCostsNobodyTheStart() throws IOException {
        // Truncated mid-string: the file exists, is not JSON, and must read as "nothing said".
        writeConfiguration("{\"startansicht\": \"tes");

        assertThat(Startansicht.wunsch()).isEqualTo("testing");
    }

    @Test
    public void aMissingConfigurationReadsAsNothingSaid() {
        assertThat(Files.exists(temporaryDirectory.resolve("konfiguration.json"))).isFalse();

        assertThat(Startansicht.wunsch()).isEqualTo("testing");
    }

    @Test
    public void anotherKeysValueIsNeverMistakenForTheSwitch() throws IOException {
        writeConfiguration(
            "{\"ziel\": \"startansicht\", \"java\": \"C:\\\\jdk\\\\bin\\\\java.exe\"}"
        );

        assertThat(Startansicht.wunsch()).isEqualTo("testing");
    }

    @Test
    public void aNestedObjectDoesNotSupplyTheSwitch() throws IOException {
        writeConfiguration("{\"alt\": {\"startansicht\": \"aus\"}, \"ziel\": \"C:\\\\ING\"}");

        assertThat(Startansicht.wunsch()).isEqualTo("testing");
    }

    @Test
    public void umlautsAndEscapesInNeighbouringValuesSurvive() throws IOException {
        // The umlaut is written as a Java unicode escape on purpose: this module compiles with
        // the platform encoding, so a literal one would make the assertion depend on the
        // machine rather than on the reader under test.
        String muellersPfad = "C:\\Users\\M\u00fcller\\ING";
        writeConfiguration(
            "{\"ziel\": \"C:\\\\Users\\\\M\u00fcller\\\\ING\",\"startansicht\": \"testing\"}"
        );

        assertThat(Startansicht.wunsch()).isEqualTo("testing");
        // And the same value once more with the umlaut arriving as a JSON \\u escape, which is
        // what a writer other than ConvertTo-Json may well produce.
        assertThat(
                Startansicht.zeichenkette(
                    "{\"ziel\": \"C:\\\\Users\\\\M\\u00fcller\\\\ING\"}",
                    "ziel"
                )
            )
            .isEqualTo(muellersPfad);
    }

    @Test
    public void theTitleNamesTheScreen() {
        assertThat(Startansicht.zuOeffnen("testing", "TestDesign", panels(), false))
            .isEqualTo(IDENTITY);
    }

    @Test
    public void thePluginIdNamesTheScreenAsWell() {
        assertThat(Startansicht.zuOeffnen("ing-tester-panel", "TestDesign", panels(), false))
            .isEqualTo(IDENTITY);
    }

    @Test
    public void anUnknownNameOpensNothing() {
        assertThat(Startansicht.zuOeffnen("gibtsnicht", "TestDesign", panels(), false)).isNull();
    }

    @Test
    public void aScreenThatIsAlreadyInFrontIsNotOpenedTwice() {
        assertThat(Startansicht.zuOeffnen("testing", SLIDE, panels(), false)).isNull();
    }

    @Test
    public void theSecondProjectOfASessionLeavesTheTesterWhereTheyAre() {
        assertThat(Startansicht.zuOeffnen("testing", "TestDesign", panels(), true)).isNull();
    }

    @Test
    public void theSwitchedOffCaseOpensNothing() {
        assertThat(Startansicht.zuOeffnen(null, "TestDesign", panels(), false)).isNull();
    }

    @Test
    public void withoutAnyPluginScreenNothingIsOpened() {
        assertThat(Startansicht.zuOeffnen("testing", "TestDesign", Collections.emptyList(), false))
            .isNull();
    }

    @Test
    public void theRightScreenIsPickedOutOfSeveral() {
        List<Startansicht.Kandidat> viele = Arrays.asList(
            new Startansicht.Kandidat("beispiel-plugin", "Beispiel", "Plugin:beispiel-plugin"),
            new Startansicht.Kandidat(IDENTITY, TITLE, SLIDE)
        );

        assertThat(Startansicht.zuOeffnen("TESTING", "TestDesign", viele, false))
            .isEqualTo(IDENTITY);
    }

    private static List<Startansicht.Kandidat> panels() {
        return Collections.singletonList(new Startansicht.Kandidat(IDENTITY, TITLE, SLIDE));
    }

    private void writeConfiguration(String json) throws IOException {
        Files.write(
            temporaryDirectory.resolve("konfiguration.json"),
            json.getBytes(StandardCharsets.UTF_8)
        );
    }
}
