import com.ing.datalib.component.Project;
import com.ing.datalib.plugin.ProjectTestData;
import com.ing.datalib.testdata.TestDataFactory;
import com.ing.ingenious.api.contract.data.ProjectTestDataApi;
import com.ing.ingenious.api.contract.ui.RecordingTarget;
import de.ing.qa.studio.AdoNaming;
import de.ing.qa.studio.AdoRecordingTarget;
import de.ing.qa.studio.AdoRunWatcher;
import de.ing.qa.studio.AdoUpload;
import de.ing.qa.studio.AdoUploadStatus;
import de.ing.qa.studio.CustomerProfile;
import de.ing.qa.studio.SelectedTestCase;
import de.ing.qa.studio.StudioTestData;
import de.ing.qa.studio.TestCaseProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * The whole Studio → Azure DevOps chain, end to end, in one run — so that no future change can
 * quietly break a seam between two lanes' work.
 *
 * <p>Every link is proven somewhere. What was not proven is that they still fit together: each
 * piece has its own harness, each harness supplies its own inputs, and a seam can rot without
 * any of them noticing. This drives one test case id through all six links and asserts that the
 * id which comes out of the last one is the id that went into the first.
 *
 * <pre>
 *   1  a test case is chosen            SelectedTestCase.read()
 *   2  a customer profile is persisted  TestCaseProfile.saveForSelectedTestCase(..)
 *   3  a recording target is resolved   AdoRecordingTarget.getRecordingTarget()
 *   4  a finished run is detected       AdoRunWatcher.watch(..)
 *   5  evidence is assembled            tools/parse-report.mjs
 *   6  the upload is invoked            ing-qa-recorder/mvp/ado-upload.mjs
 *   7  the outcome is announced         AdoUploadStatus
 * </pre>
 *
 * <p><b>Nothing here is a mock.</b> The project is a real INGenious project, copied so the
 * original is never written to, loaded with the engine's own {@code new Project(location)}. The
 * test-data handle is Studio's own {@code com.ing.datalib.plugin.ProjectTestData} — the very
 * class {@code AppMainFrame} hands plugins — not a stand-in for it. The run is real INGenious
 * output from {@code artifacts/}: real {@code data.js}, real screenshots, real traces. The
 * report reader and the uploader are the real Node tools, spawned as real child processes.
 *
 * <p><b>The one stub is the last one.</b> {@code ING_ADO_UPLOAD=0} stops {@code ado-upload.mjs}
 * at the network boundary and nowhere earlier, so everything up to and including the decision
 * to upload is exercised for real while a harness never writes into a live banking system. The
 * upload past that point is proven separately against the real organisation (ADO run 25518995).
 *
 * <p><b>It fails loudly.</b> Every link is an assertion; a broken one prints what was expected
 * and what happened, and the process exits non-zero. A link that cannot even be attempted is a
 * failure too — a guard that skips quietly is worse than no guard, because it reports success.
 */
public class ChainHarness {

    /** The one id that must survive all six links. */
    private static final String ADO_ID = "3951650";
    private static final String TITLE = "Beispielanwendung SYSTEMTEST: Partner-Suche + Kunde-360 (Set1)";
    private static final String SUITE = "Partner-Suche Suite";

    /** A real INGenious run that passed, from this repo's collected artifacts. */
    private static final String FIXTURE = "artifacts/TC-3951253/run-20260721-130758";
    /** The same run, committed, so a clean checkout can run this guard. See {@link #fixture()}. */
    private static final String COMMITTED_FIXTURE = "ingenious-plugin/sample/lauf-bestanden";
    /** The test case that run really carried, before the guided flow's naming is applied. */
    private static final String ORIGINAL_CASE = "Pay money to an existing contact";

    /** The real project this drives, copied before it is touched. */
    private static final String SOURCE_PROJECT = "Projects/Tutorial";

    /**
     * How long link 4 waits after the watcher is armed before the run appears.
     *
     * <p>{@code AdoRunWatcher} polls every five seconds and takes its history snapshot on the
     * first of those polls. Six seconds is one whole poll period plus slack, on a thread that
     * has already been started — a bounded wait for a scheduling decision, not a hope that a
     * race goes the right way. See the block in link 4 for what happens without it.
     */
    private static final long HISTORY_SWEEP_MS = 6_000;

    /**
     * An invented account number — this repository never holds a real one. It exists to be
     * looked for and NOT found: the guard asserts it does not reach the project.
     */
    private static final String ACCOUNT = "1234567890";

    private static final List<AdoUploadStatus.Event> events = new CopyOnWriteArrayList<>();

    private static int checks;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path work = work();
        Path repo = repo();
        System.out.println("== the Studio -> ADO chain, end to end ==");
        System.out.println("repo    : " + repo);
        System.out.println("work    : " + work);
        System.out.println("ADO id  : " + ADO_ID);
        require("ING_ADO_UPLOAD is 0, so nothing can reach the real organisation",
            "0".equals(System.getenv("ING_ADO_UPLOAD")));

        // The upload announces itself to any listener; subscribe before anything can happen.
        AdoUploadStatus.reset();
        AdoUploadStatus.addListener(events::add);

        // ------------------------------------------------------------------ 1. the choice
        System.out.println();
        System.out.println("-- 1. a test case is chosen --------------------------------------");
        Path selection = Paths.get(String.valueOf(System.getenv("ING_TESTCASE_SELECTION")));
        Files.createDirectories(selection.getParent());
        Files.writeString(selection, selectionJson(null), StandardCharsets.UTF_8);

        SelectedTestCase selected = SelectedTestCase.read();
        check("the selection is read back", selected != null);
        check("and carries the id the tester chose",
            selected != null && ADO_ID.equals(selected.adoId()));

        String scenario = AdoNaming.scenarioName(SUITE);
        String testCase = AdoNaming.testCaseName(ADO_ID, TITLE);
        System.out.println("   scenario  : " + scenario);
        System.out.println("   test case : " + testCase);
        check("the test case name carries the id, so a run can be traced back to it",
            ADO_ID.equals(AdoNaming.adoIdFromTestCaseName(testCase)));

        // ------------------------------------------------------- 2. the customer profile
        System.out.println();
        System.out.println("-- 2. a customer profile is persisted ----------------------------");
        Path projectDir = work.resolve("projekt");
        copyTree(installRoot().resolve(SOURCE_PROJECT), projectDir);
        System.out.println("   project copy: " + projectDir);

        // Studio's own implementation over the engine's own Project. The plugin is handed
        // exactly this object in a real session; nothing here stands in for it.
        // TestDataFactory.load() first, exactly as com.ing.ide.main.Main does at startup:
        // the data providers are found by annotation scanning, and a Project loaded before
        // that scan has run cannot read its own test data.
        TestDataFactory.load();
        Project project = new Project(projectDir.toString());
        ProjectTestDataApi testData = new ProjectTestData(() -> project);
        StudioTestData.set(testData);
        check("Studio's test-data handle is the real ProjectTestData",
            StudioTestData.get() instanceof ProjectTestData);

        List<String> headers = List.of("KONTONUMMER", "Partnertyp", "Produktvariante");
        List<String> row = List.of(ACCOUNT, "P", "Giro");
        boolean saved = TestCaseProfile.saveForSelectedTestCase(headers, row);
        check("the profile is written onto the chosen test case", saved);

        Path sheet = findSheet(projectDir, TestCaseProfile.SHEET);
        check("and a " + TestCaseProfile.SHEET + " sheet really exists on disk", sheet != null);
        if (sheet != null) {
            String text = Files.readString(sheet, StandardCharsets.UTF_8);
            System.out.println("   sheet: " + sheet.getFileName());
            check("the sheet names the test case the tester chose", text.contains(testCase));
            check("and records what KIND of customer it needs", text.contains("Giro"));
            // The account number is a working value, valid only until the test data is
            // refreshed, and it must never reach a project that gets committed. CustomerProfile
            // drops it twice over — by column name, and by any value that merely looks like
            // one. This is the guard on that, not an incidental assertion.
            check("and NO account number reached the project", !text.contains(ACCOUNT));
        }

        // ------------------------------------------------------- 3. the recording target
        System.out.println();
        System.out.println("-- 3. a recording target is resolved -----------------------------");
        // The other half of the arming check below. AdoRunWatcher.arm() is static, so "it is
        // armed now" is only evidence about THIS link if it was not armed a moment ago —
        // otherwise something earlier in the JVM could have done it and the check would hold
        // even with the arming removed from AdoRecordingTarget. Nothing before this line
        // constructs GuidedFlowPanel or asks for a recording target, so this must be false.
        check("nothing has armed the watcher yet", !AdoRunWatcher.isArmed());
        RecordingTarget target = new AdoRecordingTarget().getRecordingTarget();
        check("Studio is told where the recording goes", target != null);
        if (target != null) {
            System.out.println("   target: " + target);
            check("into the same scenario the profile went to",
                scenario.equals(target.getScenarioName()));
            check("and the same test case", testCase.equals(target.getTestCaseName()));
        }
        check("arming happened on the way past, so a finished run will be noticed",
            AdoRunWatcher.isArmed());
        check("with no address of its own it defers to the project setting",
            target != null && target.getStartUrl() == null);

        // The recorder start URL, per test case. Whatever is returned here WINS over the
        // project setting, and the Beispielanwendung environments differ only by hostname — so the
        // guard is that a good address is carried and a bad one is not, rather than that the
        // feature merely exists.
        String good = "https://beispielanwendung-test1.example.com/kunde360";
        Files.writeString(selection, selectionJson(good), StandardCharsets.UTF_8);
        RecordingTarget withUrl = new AdoRecordingTarget().getRecordingTarget();
        check("a valid per-test-case address is handed to the recorder",
            withUrl != null && good.equals(withUrl.getStartUrl()));

        Files.writeString(selection, selectionJson("beispielanwendung-test1"), StandardCharsets.UTF_8);
        RecordingTarget bogus = new AdoRecordingTarget().getRecordingTarget();
        check("a hostname with no scheme is refused, not guessed at",
            bogus != null && bogus.getStartUrl() == null);
        check("and refusing it does not cost the recording its target",
            bogus != null && testCase.equals(bogus.getTestCaseName()));

        // Back to the selection the rest of the chain runs on.
        Files.writeString(selection, selectionJson(null), StandardCharsets.UTF_8);

        // ------------------------------------------- 4 + 5 + 6. the run, and the upload
        System.out.println();
        System.out.println("-- 4/5/6. a finished run is detected, read and uploaded ----------");
        // NOTHING below is called by hand. The watcher armed in link 3 is the only thing
        // driving this, exactly as in a real session: a run appears in the project's Results
        // directory and every remaining link follows from that alone. Calling AdoUpload
        // directly here would have tested the pieces while leaving the seam between them —
        // the watcher actually reaching the uploader — unexercised, which is the one thing
        // this guard exists for.
        Path results = projectDir.resolve(AdoRunWatcher.RESULTS_FOLDER);
        Path runDir = results.resolve("TestDesign").resolve(scenario).resolve(testCase)
            .resolve("28 Jul 2026 00 00 00");

        // THE RACE THIS WAIT CLOSES, AND HOW CI FOUND IT  (2026-07-28)
        //
        // The watcher's first poll takes everything already in Results as history — it must,
        // or opening a project would upload every run in it. That snapshot happens on poll
        // zero, microseconds after arm() starts the thread in link 3. This link then created
        // the run about fifty milliseconds later. Fifty milliseconds is not an ordering; it
        // is a coin flip, and on a laptop it had always landed the same way.
        //
        // On windows-latest it landed the other way, in run 30330593244: the snapshot ran
        // AFTER the copy, took this run for history, and never looked at it again. Every link
        // from 4 to 7 failed and the harness said CHAIN BROKEN — a fact about thread
        // scheduling, reported as a fact about the product.
        //
        // It is provable rather than suspected, by counting. The log said
        //
        //     Watching …/projekt/Results (1 earlier run(s) ignored)
        //
        // and Projects/Tutorial in a freshly built Dist/release contains no Results directory
        // and no report file anywhere. There was exactly one report-bearing directory in
        // existence for that sweep to have found, and this link had just made it.
        //
        // So: give the thread that has already been started a bounded, generous window to take
        // its snapshot of an EMPTY Results, and assert that it really was empty. A test whose
        // preconditions are read from the machine is not a test — that lesson is written down
        // three times in docs/reference/HARNESS-INDEX.md, and this is a fourth instance of it.
        Files.createDirectories(results);
        Thread.sleep(HISTORY_SWEEP_MS);
        List<Path> alreadyThere = AdoRunWatcher.finishedRuns(results, 0);
        check("nothing counts as a finished run yet, so the one below is genuinely new",
            alreadyThere.isEmpty());
        for (Path stale : alreadyThere) {
            System.out.println("   already a finished run before this link started: " + stale);
        }

        copyTree(fixture(), runDir);
        renameCase(runDir.resolve("data.js"), testCase);
        System.out.println("   run: " + runDir);
        System.out.println("   waiting for the armed watcher to notice it…");

        // Waiting two minutes for something that cannot arrive is not thoroughness. If an
        // earlier link has already failed, the upload was never going to happen and the run
        // should say so now rather than after a long silence.
        long budget = failed == 0 ? 120_000 : 5_000;
        AdoUploadStatus.Event terminal = awaitTerminal(ADO_ID, budget);
        check("the armed watcher noticed the run and drove the upload, unaided",
            terminal != null);
        if (terminal == null) {
            // Eight FAIL lines and no reason is what this looked like the first time. Say the
            // three things that separate "the watcher is broken" from "the watcher was never
            // going to see this": is it still running, does the run look finished to the same
            // code the watcher uses, and is it the only one there.
            System.out.println("   watcher still armed        : " + AdoRunWatcher.isArmed());
            System.out.println("   run counts as finished     : "
                + AdoRunWatcher.finishedRuns(results, 0).contains(runDir));
            System.out.println("   finished runs under Results: "
                + AdoRunWatcher.finishedRuns(results, 0));
        }
        if (terminal != null) {
            System.out.println("   " + terminal.state() + "  " + terminal.message());
            check("the id that came out is the id that went in", ADO_ID.equals(terminal.adoId()));
            // NOT startsWith("ADO-Upload "). Every return path of AdoUpload.statusLine() —
            // including the one for "the hook printed nothing at all" — is hard-coded to begin
            // with that prefix, so the old check asserted a string literal in the plugin's own
            // formatter and could not go red for any input, crashed node included. What the
            // hook's contract actually says is that its LAST stdout line is
            // "ADO-UPLOAD <CODE> <text>"; the code is the part that travelled, so assert on a
            // code that could only have come from the tool.
            check("ado-upload.mjs's own status CODE came through, not the plugin's fallback",
                terminal.message().startsWith("ADO-Upload AUS")
                    && terminal.message().contains("ING_ADO_UPLOAD"));
            check("and said it was switched off rather than pretending to succeed",
                terminal.state() == AdoUploadStatus.State.OFF);
        }
        check("the run was uploaded exactly once, not once per poll",
            events.stream().filter(e -> e.state() == AdoUploadStatus.State.RUNNING
                && ADO_ID.equals(e.adoId())).count() == 1);
        check("an attempt left a log a human can find",
            count(logsDir(), "studio-upload-" + ADO_ID) > 0);
        check("ado-upload.mjs wrote its own receipt too",
            count(logsDir(), "ado-upload-TC" + ADO_ID) > 0);
        check("and appended to the ledger",
            Files.isRegularFile(logsDir().resolve("ado-upload.log")));

        // ------------------------------------------------------------ 7. the announcement
        System.out.println();
        System.out.println("-- 7. the outcome is announced to the screen ---------------------");
        for (AdoUploadStatus.Event e : events) {
            System.out.println("   " + e.state() + "  " + e.message());
        }
        check("the tester is told the upload started",
            events.stream().anyMatch(e -> e.state() == AdoUploadStatus.State.RUNNING));
        check("and told how it ended", terminal != null);
        // A panel built after the fact must still be able to show it — the whole point of
        // the replay, and the thing that makes a status hook useful to a real screen.
        List<AdoUploadStatus.Event> late = new ArrayList<>();
        AdoUploadStatus.addListener(late::add);
        check("a panel that subscribes late is still told the last outcome",
            late.size() == 1 && late.get(0).state().isTerminal());

        // ------------------------------------------------------------------- the verdict
        System.out.println();
        System.out.println("=================================================================");
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed == 0) {
            System.out.println("CHAIN INTACT: " + ADO_ID + " survived all six links.");
        } else {
            System.out.println("CHAIN BROKEN: " + failed + " link(s) failed. See FAIL above.");
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    /**
     * Waits for the armed watcher to carry one test case all the way to a terminal outcome.
     *
     * @param adoId the id that must come back out
     * @param timeoutMillis how long to wait; the watcher polls every five seconds and only
     *     accepts a run that has been unchanged for four, so this has to be generous
     * @return the terminal event, or {@code null} when it never arrived — which is a failure,
     *     never a reason to skip a check
     */
    private static AdoUploadStatus.Event awaitTerminal(String adoId, long timeoutMillis)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (AdoUploadStatus.Event event : events) {
                if (event.state().isTerminal() && adoId.equals(event.adoId())) {
                    return event;
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    // ------------------------------------------------------------------------- assertions

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failed++;
        }
        System.out.println("  " + (ok ? "ok  " : "FAIL") + "   " + what);
    }

    /** A precondition. Failing it means the guard would prove nothing, so it stops here. */
    private static void require(String what, boolean ok) {
        System.out.println("  " + (ok ? "ok  " : "STOP") + "   " + what);
        if (!ok) {
            System.out.println("Refusing to run: the guard would not be testing what it claims.");
            System.exit(2);
        }
    }

    /**
     * The selection file the chooser panel writes.
     *
     * @param startUrl the per-test-case recorder address, or {@code null} to leave it out
     *     entirely — which is what a selection normally looks like
     */
    private static String selectionJson(String startUrl) {
        return "{\n"
            + "  \"adoId\": \"" + ADO_ID + "\",\n"
            + "  \"title\": \"" + TITLE + "\",\n"
            + "  \"suiteName\": \"" + SUITE + "\""
            + (startUrl == null ? "" : ",\n  \"startUrl\": \"" + startUrl + "\"")
            + "\n}\n";
    }

    // --------------------------------------------------------------------------- plumbing

    private static Path repo() {
        String env = System.getenv("ING_QA_REPO");
        return Paths.get(env == null || env.isBlank() ? "." : env).toAbsolutePath();
    }

    /**
     * The real run this guard replays: the full collected copy when this checkout has one, the
     * committed copy otherwise.
     *
     * <p>{@code artifacts/} is gitignored, so reading only from there made this guard pass on
     * one desk and abort with "Fixture fehlt" everywhere else, CI included. The committed copy
     * under {@code ingenious-plugin/sample/lauf-bestanden} is the same real INGenious run
     * without the Playwright traces and the report viewer's assets — megabytes no link in this
     * chain reads. Which one was used is printed, because a silently swapped fixture is a
     * result nobody can reproduce.
     */
    private static Path fixture() {
        Path full = repo().resolve(FIXTURE);
        if (Files.isDirectory(full)) {
            return full;
        }
        System.out.println("   (Fixture: " + COMMITTED_FIXTURE + " — " + FIXTURE
            + " ist in diesem Checkout nicht vorhanden)");
        return repo().resolve(COMMITTED_FIXTURE);
    }

    private static Path work() {
        String env = System.getenv("ING_HARNESS_WORK");
        return Paths.get(env == null || env.isBlank() ? "." : env).toAbsolutePath();
    }

    private static Path installRoot() {
        String env = System.getenv("ING_INGENIOUS_HOME");
        if (env == null || env.isBlank()) {
            throw new IllegalStateException("ING_INGENIOUS_HOME is not set");
        }
        return Paths.get(env).toAbsolutePath();
    }

    private static Path logsDir() {
        return AdoUpload.logsDir();
    }

    private static long count(Path dir, String prefix) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        } catch (IOException ex) {
            return 0;
        }
    }

    /** The {@code Testkunde} sheet, wherever this project keeps its test data. */
    private static Path findSheet(Path projectDir, String sheet) {
        try (Stream<Path> walk = Files.walk(projectDir)) {
            return walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(sheet + ".") || n.equals(sheet);
                })
                .findFirst().orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            throw new IOException("Missing: " + from);
        }
        Files.createDirectories(to);
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path targetPath = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(source, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    // Windows carries the source timestamp across; a run "written just now"
                    // is exactly what link 4 is testing.
                    Files.setLastModifiedTime(targetPath, FileTime.from(Instant.now()));
                }
            }
        }
    }

    /** Gives the real run the name the guided flow would have given it. Nothing else changes. */
    private static void renameCase(Path dataJs, String name) throws IOException {
        String text = Files.readString(dataJs, StandardCharsets.UTF_8);
        if (!text.contains(ORIGINAL_CASE)) {
            throw new IOException("Fixture no longer contains \"" + ORIGINAL_CASE + "\": " + dataJs);
        }
        Files.writeString(dataJs, text.replace(ORIGINAL_CASE, name), StandardCharsets.UTF_8);
    }

    /** Kept so an unused-import warning cannot hide a real dependency. */
    @SuppressWarnings("unused")
    private static final Class<?> USES = CustomerProfile.class;
}
