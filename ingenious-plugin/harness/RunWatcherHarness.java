import de.ing.qa.ado.Json;
import de.ing.qa.studio.AdoRunWatcher;
import de.ing.qa.studio.AdoUpload;
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
import java.util.Map;
import java.util.stream.Stream;

/**
 * Headless proof for the Studio-side ADO evidence upload — the trigger that replaces the
 * retiring companion's {@code EngineLoopFacade.confirmOutcome}
 * (<a href="https://github.com/Wladefant/ing-qa-automation/issues/124">#124</a>).
 *
 * <p>Nothing here is a mock. The fixtures are <b>real INGenious run output</b> copied out of
 * {@code artifacts/}: real {@code data.js}, real {@code summary-v2.html}, real screenshots,
 * logs and Playwright traces. The only edit is the test case <em>name</em>, rewritten to carry
 * an ADO id exactly as {@code AdoRecordingTarget} would have named it. The report reader and
 * the uploader are the real {@code tools/parse-report.mjs} and
 * {@code ing-qa-recorder/mvp/ado-upload.mjs}, spawned as real child processes.
 *
 * <p>Scenarios (argv[0]):
 *
 * <ul>
 *   <li>{@code entdeckt} — the watcher ignores everything already on disk, then notices exactly
 *       one new finished run, exactly once, and only after it has stopped changing.
 *   <li>{@code kopie} — one finished run causes exactly one detection, even though the engine
 *       writes every run twice.
 *   <li>{@code kette} — a finished run becomes an upload: the ADO id is recovered from the test
 *       case name, {@code ado-upload.mjs} is really invoked, its machine-readable status line is
 *       really read back, and a log is left behind. Runs with {@code ING_ADO_UPLOAD=0}, so the
 *       chain is proven up to — and not across — the ADO boundary.
 *   <li>{@code durchgefallen} — a real failed run is never dressed up as Bestanden. The chain
 *       half runs with {@code ING_ADO_UPLOAD=0} and proves only that nothing was uploaded; the
 *       refusal itself is proven separately, with uploading ON and the ADO boundary stubbed by
 *       {@code --dry-run}, out of the receipt. See {@link #refusesFailedOutcome}.
 *   <li>{@code zeile} — the status-line contract itself.
 * </ul>
 */
public class RunWatcherHarness {

    private static int failures;
    private static int checks;

    /** The ADO case the fixtures are renamed to — the canary case used throughout this repo. */
    private static final String ADO_ID = "3951650";
    private static final String CASE_NAME = ADO_ID + " - Partner-Suche pruefen";
    /** The test case name inside the real fixture that gets rewritten. */
    private static final String ORIGINAL_CASE = "Pay money to an existing contact";

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "entdeckt";
        System.out.println("== scenario: " + scenario + " ==");

        switch (scenario) {
            case "entdeckt" -> discovery();
            case "kopie" -> engineCopy();
            case "kette" -> chain();
            case "durchgefallen" -> failedRun();
            case "echt" -> real();
            case "zeile" -> statusLine();
            default -> {
                System.out.println("unknown scenario " + scenario);
                System.exit(2);
            }
        }

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ scenarios

    /**
     * The watcher's whole job: know what is new. A tester who opens a project with a year of
     * results in it must not have a year of results uploaded to ADO.
     */
    private static void discovery() throws Exception {
        Path project = work().resolve("projekt");
        Path results = project.resolve(AdoRunWatcher.RESULTS_FOLDER);
        Path history = results.resolve("TestDesign/Payment Operations/" + CASE_NAME
            + "/21-Juli-2026 13-07-58");
        copyFixture(fixture(), history);

        check("the project's Results directory is found",
            results.equals(AdoRunWatcher.resultsRoot()),
            String.valueOf(AdoRunWatcher.resultsRoot()));
        check("a real run directory is recognised as finished",
            AdoRunWatcher.finishedRuns(results, 0).equals(List.of(history)),
            String.valueOf(AdoRunWatcher.finishedRuns(results, 0)));
        // Everything was copied a moment ago, so nothing in it can be a minute old.
        check("a run still being written is left alone",
            AdoRunWatcher.finishedRuns(results, 60_000).isEmpty(),
            String.valueOf(AdoRunWatcher.finishedRuns(results, 60_000)));

        List<Path> found = new ArrayList<>();
        Thread watcher = new Thread(() -> AdoRunWatcher.watch(found::add, 150, 0, -1), "watch");
        watcher.setDaemon(true);
        watcher.start();
        Thread.sleep(700);
        check("history is not uploaded", found.isEmpty(), String.valueOf(found));

        // A finished run appears while the watcher is running — the real case.
        Path fresh = results.resolve("TestDesign/Payment Operations/" + CASE_NAME
            + "/27-Juli-2026 22-30-00");
        copyFixture(fixture(), fresh);
        for (int i = 0; i < 40 && found.isEmpty(); i++) {
            Thread.sleep(150);
        }
        check("the new run is found", found.equals(List.of(fresh)), String.valueOf(found));

        // Several more polls: a directory is handed over once, not once per poll.
        Thread.sleep(900);
        check("found exactly once, not once per poll", found.size() == 1, String.valueOf(found));
        watcher.interrupt();
    }

    /**
     * One finished run, one detection — although the engine writes every run <b>twice</b>.
     *
     * <p><b>The defect this exists for.</b> {@code HtmlSummaryHandler.createLatest()} deletes
     * and re-copies the whole run directory to {@code AppResourcePath.getLatestResultsLocation()}
     * — {@code …/<TestCase>/Latest} — at the end of every run. The copy is a sibling of the
     * timestamped run directory and carries the same {@code data.js} and the same
     * {@code *-v2.html}, so {@link AdoRunWatcher#finishedRuns} accepts it as a finished run in
     * its own right and {@code AdoUpload.forRun} is invoked a second time. On a test case named
     * from ADO, with uploading switched on, that is <b>two Azure DevOps runs and two Bestanden
     * marks for one test</b>. It was seen twice in a live Studio by
     * {@code StudioChainDriver}'s L9b, on an ADO-named case and on {@code APIBasics/CreatePost}.
     *
     * <p><b>Why the existing scenarios cannot see it.</b> {@code entdeckt} builds its run
     * directories by hand and never makes the sibling copy, so the shape that causes the second
     * detection is not on disk. Nothing in the harnesses had ever run the engine.
     *
     * <p>The layout is written out here exactly as the engine writes it, taken from a real
     * Studio run of 2026-07-28 — the timestamped directory is German-localised
     * ({@code 28-Juli-2026 02-12-42}), which is also why a run directory cannot be recognised by
     * the shape of its name: the month is whatever the tester's locale calls it. The copy can
     * only be told from the run by the engine's own constant name for it.
     */
    private static void engineCopy() throws Exception {
        Path project = work().resolve("projekt-kopie");
        Path results = project.resolve(AdoRunWatcher.RESULTS_FOLDER);
        Path caseDir = results.resolve("TestDesign/Payment Operations/" + CASE_NAME);
        Path run = caseDir.resolve("21-Juli-2026 13-07-58");
        Path copy = caseDir.resolve("Latest");

        check("the harness watches the project it just built",
            results.equals(AdoRunWatcher.resultsRoot()),
            "ING_INGENIOUS_PROJECT must point at " + project + ", not "
                + AdoRunWatcher.resultsRoot());

        // The watcher starts before either directory exists, exactly as it does in Studio:
        // arm() runs at panel construction, the run happens afterwards.
        List<Path> found = new ArrayList<>();
        Thread watcher = new Thread(() -> AdoRunWatcher.watch(found::add, 150, 0, -1), "watch");
        watcher.setDaemon(true);
        watcher.start();
        Thread.sleep(500);
        check("nothing is detected before the run", found.isEmpty(), String.valueOf(found));

        // The engine writes the run, then copies the whole of it to "Latest".
        copyFixture(fixture(), run);
        copyFixture(fixture(), copy);

        check("the premise holds: the engine's copy also carries a report",
            !AdoRunWatcher.finishedRuns(results, 0).isEmpty()
                && Files.isRegularFile(copy.resolve("data.js")),
            "no data.js in " + copy);

        List<Path> scanned = AdoRunWatcher.finishedRuns(results, 0);
        check("a scan of one finished run answers with one directory",
            scanned.size() == 1, scanned.size() + ": " + scanned);
        check("and it is the run, not the engine's copy of it",
            scanned.size() == 1 && run.equals(scanned.get(0)),
            String.valueOf(scanned));

        // Several polls, so a second detection has every chance to arrive.
        for (int i = 0; i < 40 && found.isEmpty(); i++) {
            Thread.sleep(150);
        }
        Thread.sleep(900);
        check("ONE finished run causes ONE detection", found.size() == 1,
            found.size() + " detections: " + found);
        check("the detected directory is the run itself",
            found.size() == 1 && run.equals(found.get(0)), String.valueOf(found));

        // A second run of the same test case. The engine deletes "Latest" and copies the new
        // run over it, so the copy is not new *as a path* — which is why "we have seen this
        // path already" is not on its own a defence, and why the second run must still be
        // detected exactly once rather than not at all.
        Path second = caseDir.resolve("28-Juli-2026 02-12-42");
        copyFixture(fixture(), second);
        copyFixture(fixture(), copy);
        for (int i = 0; i < 40 && found.size() < 2; i++) {
            Thread.sleep(150);
        }
        Thread.sleep(900);
        check("a second run is detected, and also only once", found.size() == 2,
            found.size() + " detections after two runs: " + found);
        check("both detections are runs, neither is the copy",
            found.stream().noneMatch(p -> "Latest".equalsIgnoreCase(p.getFileName().toString())),
            String.valueOf(found));
        watcher.interrupt();
    }

    /**
     * Run directory in, ADO upload out — through the real Node tools.
     *
     * <p>{@code ING_ADO_UPLOAD=0} is set by the runner script, so the proven answer is
     * "ADO-Upload AUS": every link up to the ADO boundary is real, and the boundary itself is
     * not crossed from a harness.
     */
    private static void chain() throws Exception {
        Path runDir = work().resolve("lauf");
        copyFixture(fixture(), runDir);
        renameCase(runDir.resolve("data.js"));

        long started = System.currentTimeMillis();
        List<AdoUpload.Result> results = AdoUpload.forRun(runDir);
        long took = System.currentTimeMillis() - started;
        for (AdoUpload.Result r : results) {
            System.out.println("  " + r.testCaseName() + " -> " + r.adoId() + " / " + r.status());
        }

        AdoUpload.Result withId = results.stream()
            .filter(r -> ADO_ID.equals(r.adoId())).findFirst().orElse(null);
        check("the ADO id is recovered from the test case name", withId != null,
            String.valueOf(results));
        if (withId != null) {
            check("the real run's PASS travels as passed", withId.passed(), "passed=false");
            check("ado-upload.mjs answered on its documented status line",
                withId.status().startsWith("ADO-Upload AUS"), withId.status());
            check("and said why it is off",
                withId.status().contains("ING_ADO_UPLOAD"), withId.status());
        }

        AdoUpload.Result withoutId = results.stream()
            .filter(r -> r.adoId() == null).findFirst().orElse(null);
        check("a case with no ADO id in its name is reported, not guessed at", withoutId != null,
            String.valueOf(results));
        if (withoutId != null) {
            check("and is skipped rather than uploaded",
                withoutId.status().startsWith("ADO-Upload ÜBERSPRUNGEN"), withoutId.status());
        }

        Path logs = AdoUpload.logsDir();
        check("an attempt leaves a log a human can find",
            logs.toFile().isDirectory() && list(logs, "studio-upload-" + ADO_ID) > 0,
            "keine studio-upload-*.log in " + logs);
        check("ado-upload.mjs wrote its own receipt too", list(logs, "ado-upload-TC" + ADO_ID) > 0,
            "keine ado-upload-TC*.json in " + logs);
        check("and appended to the ledger", Files.isRegularFile(logs.resolve("ado-upload.log")),
            "keine ado-upload.log in " + logs);

        System.out.println("  (Kette in " + took + " ms)");
    }

    /**
     * The same chain, across the ADO boundary — a real Test Run in the real organisation.
     *
     * <p>Only runs where an Entra token can be had ({@code az} on an in-perimeter, compliant
     * machine): the ING work laptop, not a harness on a developer's desktop. It writes to the
     * live system, so it is a separate scenario nobody invokes by accident, and it targets the
     * canary case 3951650 that this repo has always used for exactly this.
     */
    private static void real() throws Exception {
        Path runDir = work().resolve("echt");
        copyFixture(fixture(), runDir);
        renameCase(runDir.resolve("data.js"));

        List<AdoUpload.Result> results = AdoUpload.forRun(runDir);
        AdoUpload.Result withId = results.stream()
            .filter(r -> ADO_ID.equals(r.adoId())).findFirst().orElse(null);
        check("the ADO id is recovered from the test case name", withId != null,
            String.valueOf(results));
        if (withId == null) {
            return;
        }
        System.out.println("  " + withId.status());
        check("the upload really reached ADO", withId.status().startsWith("ADO-Upload OK"),
            withId.status());
        check("and reported the run it created", withId.status().contains("ADO-Lauf"),
            withId.status());
    }

    /** ado-automark marks Bestanden and only Bestanden; a real failed run must never reach it. */
    private static void failedRun() throws Exception {
        Path runDir = work().resolve("durchgefallen");
        copyFixture(failedFixture(), runDir);
        renameCase(runDir.resolve("data.js"));

        List<AdoUpload.Result> results = AdoUpload.forRun(runDir);
        AdoUpload.Result withId = results.stream()
            .filter(r -> ADO_ID.equals(r.adoId())).findFirst().orElse(null);
        check("the failed run is recognised", withId != null, String.valueOf(results));
        if (withId != null) {
            System.out.println("  " + withId.status());
            check("FAIL never travels as passed", !withId.passed(), "passed=true");
            // Named for what it actually observes. The runner sets ING_ADO_UPLOAD=0, so this
            // says "nothing was uploaded" and NOTHING about why — see refusesFailedOutcome
            // for the part that is about the refusal.
            check("with uploading switched off, nothing was uploaded at all",
                withId.status().startsWith("ADO-Upload AUS"), withId.status());
        }
        refusesFailedOutcome(runDir);
    }

    /**
     * The check that stands between this project and a failed test marked <b>Bestanden</b> in a
     * live banking system — asked in a way that can actually answer it.
     *
     * <p><b>What was here before.</b>
     * {@code status().contains("ÜBERSPRUNGEN") || status().contains("AUS")}. The scenario runs
     * under {@code ING_ADO_UPLOAD=0}, and {@code ado-upload.mjs} returns {@code AUS} at its
     * first branch — <em>before</em> it looks at {@code --outcome} at all. So the
     * {@code ÜBERSPRUNGEN} half was unreachable and the {@code AUS} half was true no matter
     * what the tool decided. Delete the {@code outcome !== 'passed'} guard from
     * {@code ado-upload.mjs} and that check still printed ok. It was the only thing claiming to
     * cover the single most important safety property in the repo, and it covered nothing.
     *
     * <p><b>Why the receipt, and why it is not enough on its own.</b> The suggestion was to read
     * the decision out of the receipt {@code ado-upload-TC3951650*.json}. Under
     * {@code ING_ADO_UPLOAD=0} that does not work either: the enable flag short-circuits first
     * and the receipt records {@code "status": "AUS"}, with no field carrying the outcome it was
     * asked to act on. A receipt from a switched-off run cannot testify about the guard.
     *
     * <p><b>What is asked instead.</b> The tool is invoked directly with uploading <b>ENABLED</b>
     * and {@code --outcome failed}, and the receipt must say {@code "enabled": true} together
     * with {@code "status": "UEBERSPRUNGEN"}. Only the outcome guard can produce that pair: with
     * uploading on, every other path leads past it. {@code automarkExit} must also still be
     * {@code null} — proof that {@code ado-automark.mjs}, the thing that does the marking, was
     * never even started.
     *
     * <p><b>Why this cannot reach Azure DevOps.</b> {@code --dry-run} is passed, which sets
     * {@code DRY} in {@code ado-automark.mjs}: {@code getOrFetchToken()} returns a literal
     * {@code DRY-RUN-TOKEN} without spawning {@code az}, and {@code adoFetch()} answers from
     * {@code cannedFor()} without calling {@code fetch}. {@code ADO_NONINTERACTIVE} is set as
     * well, so no interactive {@code az login} can be opened even on a path that reached the
     * real token code. In the green direction none of that is exercised at all — the guard
     * returns before {@code ado-automark} is spawned. It matters in the RED direction: with the
     * guard removed, the run proceeds, and it must proceed into a stub rather than into the
     * organisation.
     *
     * <p><b>Proven red.</b> On 2026-07-28 the {@code outcome !== 'passed'} guard was deleted in a
     * scratch copy of {@code ado-upload.mjs} and this scenario was re-run against it via
     * {@code ING_ADO_UPLOAD_TOOL}: the receipt came back {@code "status": "PROBELAUF"} with
     * {@code automarkExit: 0}, and both checks below went FAIL. The real
     * {@code ing-qa-recorder/mvp/ado-upload.mjs} was not touched.
     */
    private static void refusesFailedOutcome(Path evidence) throws Exception {
        Path tool = uploadTool();
        System.out.println("  (Guard geprueft an: " + tool + ")");
        check("the upload tool this check is about is on disk", Files.isRegularFile(tool),
            String.valueOf(tool));
        if (!Files.isRegularFile(tool)) {
            return;
        }

        Map<?, ?> refused = uploadReceipt(tool, evidence, "failed", "beleg-durchgefallen");
        check("a receipt was written for the refusal", refused != null,
            "kein ado-upload-TC" + ADO_ID + "*.json geschrieben");
        if (refused != null) {
            System.out.println("  Beleg(failed): status=" + refused.get("status")
                + " enabled=" + refused.get("enabled")
                + " automarkExit=" + refused.get("automarkExit"));
            // The two halves are only meaningful together: "enabled" rules out "it was off",
            // "UEBERSPRUNGEN" is then the outcome guard and nothing else.
            check("the refusal happened with uploading ENABLED, so it is the outcome and not the flag",
                Boolean.TRUE.equals(refused.get("enabled")), String.valueOf(refused.get("enabledReason")));
            check("a failed run is refused before ADO: status UEBERSPRUNGEN",
                "UEBERSPRUNGEN".equals(refused.get("status")), String.valueOf(refused.get("status")));
            check("and ado-automark — the thing that marks Bestanden — was never started",
                refused.get("automarkExit") == null && refused.get("runId") == null,
                "automarkExit=" + refused.get("automarkExit") + " runId=" + refused.get("runId"));
        }

        // The other direction, so "UEBERSPRUNGEN" cannot become the tool's answer to everything:
        // the same invocation with a PASSED outcome must get past the guard. PROBELAUF is
        // ado-upload.mjs's own label for "the dry run went through and NOTHING was written to
        // ADO" — a real OK is impossible here because --dry-run never lets a request out.
        Map<?, ?> allowed = uploadReceipt(tool, evidence, "passed", "beleg-bestanden");
        check("a receipt was written for the passing run too", allowed != null,
            "kein ado-upload-TC" + ADO_ID + "*.json geschrieben");
        if (allowed != null) {
            System.out.println("  Beleg(passed): status=" + allowed.get("status")
                + " enabled=" + allowed.get("enabled")
                + " automarkExit=" + allowed.get("automarkExit"));
            check("a PASSED run is NOT refused — the guard reads the outcome, it is not a constant",
                !"UEBERSPRUNGEN".equals(allowed.get("status")), String.valueOf(allowed.get("status")));
            check("and it really did get as far as ado-automark",
                allowed.get("automarkExit") != null, "automarkExit=" + allowed.get("automarkExit"));
            check("…in a dry run, so nothing was written to Azure DevOps",
                "PROBELAUF".equals(allowed.get("status")) && Boolean.TRUE.equals(allowed.get("dryRun")),
                String.valueOf(allowed.get("status")));
        }
    }

    /**
     * {@code ing-qa-recorder/mvp/ado-upload.mjs}, or whatever {@code ING_ADO_UPLOAD_TOOL} names.
     *
     * <p>A harness seam, and the only one: it is how a scratch copy with the guard removed is
     * put under the same checks, so this scenario has been seen red and not only green. The
     * path is printed on every run, because a check whose subject is chosen by an environment
     * variable and never named is a check nobody can reproduce.
     */
    private static Path uploadTool() {
        String explicit = System.getenv("ING_ADO_UPLOAD_TOOL");
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit.trim()).toAbsolutePath();
        }
        return repo().resolve("ing-qa-recorder/mvp/ado-upload.mjs");
    }

    /**
     * Runs {@code ado-upload.mjs} once with uploading on and reads back the receipt it wrote.
     *
     * @param outcome what the run is claimed to have done — the input the guard judges
     * @param logs a receipts directory of this call's own, so "the newest receipt" is
     *     unambiguous and one call can never be read as another's evidence
     * @return the parsed receipt, or {@code null} when none was written
     */
    private static Map<?, ?> uploadReceipt(Path tool, Path evidence, String outcome, String logs)
            throws Exception {
        Path dir = work().resolve(logs);
        // Cleared first: a receipt left by an earlier run would otherwise be read as this one's.
        deleteTree(dir);
        Files.createDirectories(dir);
        Path cwd = work().resolve(logs + "-cwd");
        Files.createDirectories(cwd);

        String node = System.getenv("ING_NODE");
        ProcessBuilder pb = new ProcessBuilder(
            node == null || node.isBlank() ? "node" : node.trim(),
            tool.toString(),
            "--test-case", ADO_ID,
            "--outcome", outcome,
            "--evidence", evidence.toString(),
            "--dry-run",
            "--hook");
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        // The upload is ON for this call — that is the whole point — and every route to the real
        // organisation is stubbed instead: --dry-run above, and no interactive login from here.
        pb.environment().put("ING_ADO_UPLOAD", "1");
        pb.environment().put("ING_ADO_UPLOAD_LOGS", dir.toString());
        pb.environment().put("ADO_NONINTERACTIVE", "1");
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        Thread drain = new Thread(() -> {
            try {
                out.append(new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Only costs detail in the message below.
            }
        }, "upload-probe");
        drain.setDaemon(true);
        drain.start();
        if (!proc.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            check("ado-upload.mjs answered within three minutes (" + outcome + ")", false, "Timeout");
            return null;
        }
        drain.join(2000);

        Path receipt;
        try (Stream<Path> s = Files.list(dir)) {
            receipt = s.filter(p -> p.getFileName().toString().startsWith("ado-upload-TC" + ADO_ID))
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .max(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                .orElse(null);
        }
        if (receipt == null) {
            System.out.println("  (kein Beleg; Ausgabe: "
                + out.toString().replace('\n', ' ').trim() + ")");
            return null;
        }
        Object parsed = Json.parse(Files.readString(receipt, StandardCharsets.UTF_8));
        return parsed instanceof Map<?, ?> map ? map : null;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** The one line the Java side reads out of the Node tool. */
    private static void statusLine() {
        check("OK is read", AdoUpload.statusLine(
            "noise\nADO-UPLOAD OK ADO-Lauf 25518817 angelegt\n", 0)
            .equals("ADO-Upload OK — ADO-Lauf 25518817 angelegt"), "");
        check("the umlaut is restored", AdoUpload.statusLine(
            "ADO-UPLOAD UEBERSPRUNGEN Ergebnis failed\n", 0).startsWith("ADO-Upload ÜBERSPRUNGEN"),
            "");
        check("silence is a failure, not a success",
            AdoUpload.statusLine("", -1).startsWith("ADO-Upload FEHLER"), "");
        check("the last line wins", AdoUpload.statusLine(
            "ADO-UPLOAD FEHLER erster Versuch\nADO-UPLOAD OK zweiter\n", 0)
            .startsWith("ADO-Upload OK"), "");
        check("the id is taken from the test case, not the scenario",
            "3951650 - Partner-Suche".equals(
                AdoUpload.shortName("ADO Testfaelle:3951650 - Partner-Suche")), "");
        check("a name without a scenario survives whole",
            "3951650 - Partner-Suche".equals(AdoUpload.shortName("3951650 - Partner-Suche")), "");
    }

    // ------------------------------------------------------------------- fixtures

    /** A real INGenious run that passed. */
    private static Path fixture() {
        return fixture("artifacts/TC-3951253/run-20260721-130758",
            "ingenious-plugin/sample/lauf-bestanden");
    }

    /** A real INGenious run that failed. */
    private static Path failedFixture() {
        return fixture("artifacts/TC-3951253/run-20260720-232003",
            "ingenious-plugin/sample/lauf-durchgefallen");
    }

    /**
     * The full collected run when this checkout has one, the committed copy otherwise.
     *
     * <p><b>Why two.</b> {@code artifacts/} is gitignored, so a harness that could only read from
     * there passed on the machine it was written on and died with "Fixture fehlt" on a
     * colleague's checkout and in CI — a proof that exists nowhere but one desk. The committed
     * copy under {@code ingenious-plugin/sample/} is the same real INGenious run with the
     * Playwright traces and the report viewer's own assets left out: megabytes that nothing in
     * this chain reads. Which one was used is printed, because a fixture swapped silently is a
     * result nobody can reproduce.
     */
    private static Path fixture(String collected, String committed) {
        Path full = repo().resolve(collected);
        if (Files.isDirectory(full)) {
            return full;
        }
        Path shipped = repo().resolve(committed);
        System.out.println("  (Fixture: " + committed + " — " + collected + " ist in diesem "
            + "Checkout nicht vorhanden)");
        return shipped;
    }

    private static Path repo() {
        String env = System.getenv("ING_QA_REPO");
        return Paths.get(env == null || env.isBlank() ? "." : env).toAbsolutePath();
    }

    private static Path work() {
        String env = System.getenv("ING_HARNESS_WORK");
        return Paths.get(env == null || env.isBlank() ? "." : env).toAbsolutePath();
    }

    private static void copyFixture(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            throw new IOException("Fixture fehlt: " + from);
        }
        Files.createDirectories(to);
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    // Windows' CopyFileEx carries the source's timestamp across even without
                    // COPY_ATTRIBUTES, and a run "written just now" is the thing being tested.
                    Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
                }
            }
        }
    }

    /**
     * Gives the real run's test case the name the guided flow would have given it — an ADO id
     * followed by the title. Nothing else about the report is touched.
     */
    private static void renameCase(Path dataJs) throws IOException {
        String text = Files.readString(dataJs, StandardCharsets.UTF_8);
        if (!text.contains(ORIGINAL_CASE)) {
            throw new IOException("Fixture enthaelt \"" + ORIGINAL_CASE + "\" nicht: " + dataJs);
        }
        Files.writeString(dataJs, text.replace(ORIGINAL_CASE, CASE_NAME), StandardCharsets.UTF_8);
    }

    private static long list(Path dir, String prefix) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        } catch (IOException ex) {
            return 0;
        }
    }

    // ---------------------------------------------------------------------- checks

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        }
    }
}
