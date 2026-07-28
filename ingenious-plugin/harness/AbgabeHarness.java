import de.ing.qa.ado.AdoCache;
import de.ing.qa.ado.AdoTestCase;
import de.ing.qa.studio.AdoReceipts;
import de.ing.qa.studio.AdoRunWatcher;
import de.ing.qa.studio.AdoSubmission;
import de.ing.qa.studio.AdoUpload;
import de.ing.qa.studio.AdoUploadStatus;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Headless proof for the Azure DevOps half of <em>Aufnahme abgeben</em>
 * ({@link AdoSubmission}) — and above all for the one property it must never lose:
 * <b>one press, at most one Azure DevOps run.</b>
 *
 * <p>Nothing here mocks the thing under test. The run directories are <b>real INGenious run
 * output</b> from {@code artifacts/} (or its committed copy under
 * {@code ingenious-plugin/sample/}), the report reader is the real {@code parse-report.mjs},
 * the uploader is the real {@code ado-upload.mjs}, and the receipts the "already published"
 * scenarios read are written by that real tool rather than typed out here — because a check
 * that reads a receipt this harness invented proves only that this harness agrees with itself.
 *
 * <p>Scenarios (argv[0]):
 *
 * <ul>
 *   <li>{@code kein-lauf} — a recording that was never run is reported as
 *       {@link AdoUploadStatus.State#NO_RUN}, with the keystroke that fixes it, and nothing at
 *       all is started. This is the state that would otherwise be an invented Bestanden.
 *   <li>{@code nachholen} — a finished run that nothing ever uploaded IS uploaded, through the
 *       same {@link AdoUpload#forRun} the watcher uses, and abgeben reports the tool's own
 *       verdict instead of one of its own. Runs under {@code ING_ADO_UPLOAD=0}, so the chain is
 *       proven up to — and never across — the ADO boundary.
 *   <li>{@code bereits} — a run that HAS been uploaded is reported and <b>not uploaded again</b>:
 *       no second receipt, no second log, no second run. The duplicate guard, at the level a
 *       tester would meet it.
 *   <li>{@code doppelt} — the same guarantee while an upload is actually in flight: the claim in
 *       {@code AdoUpload} refuses the second caller deterministically, not by luck of timing.
 * </ul>
 */
public class AbgabeHarness {

    private static int failures;
    private static int checks;

    /** The canary case this repository has always used for exactly this. */
    private static final String ADO_ID = "3951650";
    private static final String CASE_NAME = ADO_ID + " - Partner-Suche pruefen";
    /** The test case name inside the real fixture that gets rewritten. */
    private static final String ORIGINAL_CASE = "Pay money to an existing contact";

    private static final List<AdoUploadStatus.Event> EVENTS = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "kein-lauf";
        System.out.println("== scenario: " + scenario + " ==");
        AdoUploadStatus.addListener(EVENTS::add);

        switch (scenario) {
            case "kein-lauf" -> noRun();
            case "nachholen" -> repair();
            case "bereits" -> alreadyThere();
            case "doppelt" -> inFlight();
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
     * The two ways there is nothing to report — and the proof that neither invents one.
     *
     * <p>A tester who records but never runs is the ordinary way into this. The temptation the
     * whole design refuses is to mark the case Bestanden anyway: there is no run report, so
     * there is no outcome, so a mark would be a claim nothing backs. What abgeben owes them
     * instead is the sentence naming the key that fixes it — and no child process, no sign-in
     * window, and above all no test run in a live banking system.
     */
    private static void noRun() throws Exception {
        Path logs = AdoUpload.logsDir();
        Files.createDirectories(logs);
        Path project = work().resolve("projekt");
        Files.createDirectories(project.resolve(AdoRunWatcher.RESULTS_FOLDER));

        // (1) nothing taken on at all.
        Files.deleteIfExists(AdoCache.selectionPath());
        EVENTS.clear();
        AdoSubmission.Report noCase = AdoSubmission.finish();
        System.out.println("  " + noCase.state() + " — " + noCase.message());
        check("with no test case taken on, the answer is NO_RUN and not a failure",
            noCase.state() == AdoUploadStatus.State.NO_RUN, String.valueOf(noCase.state()));
        check("and it says what to do about it",
            noCase.message().contains("Testfall übernehmen"), noCase.message());
        check("the tester is told on screen, not only in a log",
            EVENTS.size() == 1 && EVENTS.get(0).state() == AdoUploadStatus.State.NO_RUN,
            String.valueOf(EVENTS));

        // (2) a case taken on, no run anywhere.
        writeSelection();
        EVENTS.clear();
        AdoSubmission.Report noneRun = AdoSubmission.finish();
        System.out.println("  " + noneRun.state() + " — " + noneRun.message());
        check("a recording that was never run is NO_RUN",
            noneRun.state() == AdoUploadStatus.State.NO_RUN, String.valueOf(noneRun.state()));
        check("it names the test case it looked for",
            noneRun.message().contains(ADO_ID), noneRun.message());
        check("and it names the keystroke that fixes it, rather than sending them to report it",
            noneRun.message().contains("F6") && !noneRun.message().contains("melden"),
            noneRun.message());
        check("it is NOT reported as being in Azure DevOps", !noneRun.inAdo(), "inAdo");
        check("and it did not upload anything on the way to saying so",
            !noneRun.uploadedNow(), "uploadedNow");

        check("nothing was started: no receipt was written", count(logs, "ado-upload-TC") == 0,
            count(logs, "ado-upload-TC") + " receipts in " + logs);
        check("nothing was started: no upload log was written",
            count(logs, "studio-upload-") == 0,
            count(logs, "studio-upload-") + " logs in " + logs);
    }

    /**
     * The hole abgeben exists to close: a run finished, nothing uploaded it, and until now the
     * tester's only recovery was somebody else's command line.
     *
     * <p>The runner sets {@code ING_ADO_UPLOAD=0}, so the proven answer is {@code AUS} — every
     * link is real up to the ADO boundary and the boundary is not crossed. What is being proved
     * is that abgeben really drove the real uploader over the real run and then reported
     * <em>its</em> verdict: a button that invented "OK" here would pass a weaker check and be
     * the worst defect in the file.
     */
    private static void repair() throws Exception {
        Path logs = AdoUpload.logsDir();
        Files.createDirectories(logs);
        writeSelection();
        Path runDir = plantRun("21-Juli-2026 13-07-58");

        EVENTS.clear();
        AdoSubmission.Report report = AdoSubmission.finish();
        System.out.println("  " + report.state() + " — " + report.message());

        check("the run of the taken-on test case was found", report.runDir() != null,
            String.valueOf(report.runDir()));
        check("and it is the run that was planted",
            runDir.equals(report.runDir()), String.valueOf(report.runDir()));
        check("abgeben really uploaded it rather than only looking",
            report.uploadedNow(), "uploadedNow=false");
        check("and reported the uploader's own verdict, not one of its own",
            report.state() == AdoUploadStatus.State.OFF, String.valueOf(report.state()));
        check("which says why it is off", report.message().contains("ING_ADO_UPLOAD"),
            report.message());
        check("the tester saw a start and an end", EVENTS.size() >= 2
            && EVENTS.get(0).state() == AdoUploadStatus.State.RUNNING
            && EVENTS.get(EVENTS.size() - 1).state().isTerminal(), String.valueOf(EVENTS));
        check("the attempt left a log a human can find", count(logs, "studio-upload-") == 1,
            count(logs, "studio-upload-") + " logs in " + logs);
        check("and the real tool left its own receipt", count(logs, "ado-upload-TC") == 1,
            count(logs, "ado-upload-TC") + " receipts in " + logs);

        // A receipt saying AUS settles nothing: the switch may be on by the time they press
        // again, and re-asking costs a millisecond because the tool answers AUS without
        // touching az or the network.
        EVENTS.clear();
        AdoSubmission.Report second = AdoSubmission.finish();
        check("a switched-off receipt does not count as an answer — pressing again re-asks",
            second.uploadedNow() && count(logs, "ado-upload-TC") == 2,
            second.uploadedNow() + " / " + count(logs, "ado-upload-TC") + " receipts");
    }

    /**
     * The duplicate guard where a tester meets it: press abgeben after the automatic upload
     * already did the work.
     *
     * <p>The receipt is written by the <b>real</b> {@code ado-upload.mjs}, invoked here with
     * {@code --dry-run} so that not one request can leave the machine
     * ({@code ado-automark.mjs}'s {@code DRY} path answers from canned data and never spawns
     * {@code az}). That gives a genuine receipt, in the real format, naming the real run
     * directory in {@code evidenceFolder} — which is the field the whole guard turns on.
     *
     * <p>The {@code OK} half then patches exactly two fields of that same real receipt — the
     * status code and the run id the ADO boundary would have filled in — because {@code OK} is
     * by definition unreachable without writing to the live organisation. Everything the guard
     * actually reads (the file name, the {@code evidenceFolder}, the format) is still the real
     * tool's own output.
     */
    private static void alreadyThere() throws Exception {
        Path logs = AdoUpload.logsDir();
        Files.createDirectories(logs);
        writeSelection();
        Path runDir = plantRun("27-Juli-2026 22-30-00");

        Path receipt = realDryRunReceipt(runDir);
        check("the real uploader wrote a receipt to read back", receipt != null,
            "no ado-upload-TC*.json in " + logs);
        if (receipt == null) {
            return;
        }
        AdoReceipts.Receipt parsed = AdoReceipts.newestFor(runDir, ADO_ID);
        check("and it is found by the run directory it names", parsed != null,
            "AdoReceipts.newestFor(" + runDir + ") == null");
        check("a receipt for a DIFFERENT run is not accepted as this run's",
            AdoReceipts.newestFor(runDir.resolveSibling("ein-anderer-lauf"), ADO_ID) == null,
            "a foreign receipt matched");

        long receiptsBefore = count(logs, "ado-upload-TC");
        long logsBefore = count(logs, "studio-upload-");

        // (1) PROBELAUF — a rehearsal wrote nothing and was not meant to. Re-running it would
        // produce the same rehearsal, so it is reported rather than repeated.
        EVENTS.clear();
        AdoSubmission.Report dry = AdoSubmission.finish();
        System.out.println("  " + dry.state() + " — " + dry.message());
        check("a rehearsal is reported as a rehearsal, not as a failure",
            dry.state() == AdoUploadStatus.State.DRY_RUN, String.valueOf(dry.state()));
        check("and nothing was uploaded to establish that", !dry.uploadedNow(), "uploadedNow");

        // (2) OK — the case the whole guard is for.
        patchToOk(receipt);
        EVENTS.clear();
        AdoSubmission.Report ok = AdoSubmission.finish();
        System.out.println("  " + ok.state() + " — " + ok.message());
        check("a run that is already in Azure DevOps is reported as being there", ok.inAdo(),
            String.valueOf(ok.state()));
        check("with the run number the tester can look up", "25518817".equals(ok.runId()),
            String.valueOf(ok.runId()));
        check("and the link to it",
            ok.runUrl() != null && ok.message().contains(ok.runUrl()), String.valueOf(ok.runUrl()));
        check("the message says it was uploaded when the run finished, not now",
            ok.message().contains("bereits"), ok.message());
        check("nothing was uploaded a second time", !ok.uploadedNow(), "uploadedNow");
        check("the tester was told once, and told the truth", EVENTS.size() == 1
            && EVENTS.get(0).state() == AdoUploadStatus.State.OK, String.valueOf(EVENTS));

        // The proof itself: two presses, and the evidence on disk did not move.
        check("NO second receipt was written — no second ADO run",
            count(logs, "ado-upload-TC") == receiptsBefore,
            receiptsBefore + " -> " + count(logs, "ado-upload-TC"));
        check("and no second upload was even attempted",
            count(logs, "studio-upload-") == logsBefore,
            logsBefore + " -> " + count(logs, "studio-upload-"));
    }

    /**
     * The same guarantee under the timing that actually threatens it.
     *
     * <p>The watcher notices a finished run four seconds after it settles and is then allowed
     * seven minutes; a tester who presses abgeben in that window is asking for a run that is
     * being uploaded right now. Nothing on disk says so yet — the receipt is written at the end
     * — so this is the case a receipt check cannot cover, and it is covered by the claim inside
     * {@link AdoUpload#forRun} instead.
     *
     * <p>Deterministic, not raced: the runner points {@code ING_QA_REPO} at a stub uploader that
     * announces it has started and then waits for a file this harness creates. So "the upload is
     * in flight" is a fact here rather than a hope about a scheduler.
     */
    private static void inFlight() throws Exception {
        Path logs = AdoUpload.logsDir();
        Files.createDirectories(logs);
        writeSelection();
        Path runDir = plantRun("28-Juli-2026 02-12-42");
        Path started = Paths.get(required("STUB_STARTED"));
        Path gate = Paths.get(required("STUB_GATE"));
        Path calls = Paths.get(required("STUB_LOG"));
        Files.deleteIfExists(started);
        Files.deleteIfExists(gate);
        Files.writeString(calls, "", StandardCharsets.UTF_8);

        List<AdoUpload.Result> first = new ArrayList<>();
        Thread watcher = new Thread(() -> first.addAll(AdoUpload.forRun(runDir)), "wachhund");
        watcher.setDaemon(true);
        watcher.start();

        for (int i = 0; i < 200 && !Files.exists(started); i++) {
            Thread.sleep(50);
        }
        check("the stub upload really is in flight", Files.exists(started),
            "no " + started + " after 10s");

        EVENTS.clear();
        AdoSubmission.Report pressed = AdoSubmission.finish();
        System.out.println("  " + pressed.state() + " — " + pressed.message());
        check("abgeben during an upload reports progress, not a second upload",
            pressed.state() == AdoUploadStatus.State.RUNNING, String.valueOf(pressed.state()));
        check("and says so as a wait rather than as an error",
            pressed.message().contains("läuft bereits"), pressed.message());
        check("no second upload was started", !pressed.uploadedNow(), "uploadedNow");

        Files.writeString(gate, "los", StandardCharsets.UTF_8);
        watcher.join(TimeUnit.SECONDS.toMillis(60));
        check("the upload that owned the run finished it", !first.isEmpty(),
            String.valueOf(first));

        long invocations = Files.readAllLines(calls).stream()
            .filter(line -> line.startsWith("upload")).count();
        check("the uploader was invoked exactly ONCE for one run directory", invocations == 1,
            invocations + " invocations: " + Files.readString(calls).replace('\n', ' '));

        // And once it is over, the claim is gone: the run is uploadable again, which is what
        // makes a retry after a genuine failure possible at all.
        check("the claim is released when the upload ends",
            !AdoUpload.forRun(runDir).isEmpty(), "forRun still refuses after the upload ended");
    }

    // ------------------------------------------------------------------- fixtures

    /** The selection file the guided flow writes when a tester takes a case on. */
    private static void writeSelection() throws IOException {
        AdoCache.writeSelection(new AdoTestCase(ADO_ID, "Partner-Suche prüfen", "Beispielanwendung",
            "Design", null, "", "", null, List.of(), null, null));
    }

    /** A real finished run of the taken-on case, where Studio would have written it. */
    private static Path plantRun(String stamp) throws IOException {
        Path runDir = work().resolve("projekt").resolve(AdoRunWatcher.RESULTS_FOLDER)
            .resolve("TestDesign").resolve("Payment Operations").resolve(CASE_NAME).resolve(stamp);
        copyFixture(fixture(), runDir);
        renameCase(runDir.resolve("data.js"));
        return runDir;
    }

    /**
     * Runs the real {@code ado-upload.mjs} once, with uploading ON and the ADO boundary stubbed
     * by {@code --dry-run}, so that a genuine receipt exists to read back.
     *
     * @return the receipt file, or {@code null} when none was written
     */
    private static Path realDryRunReceipt(Path runDir) throws Exception {
        Path tool = realRepo().resolve("ing-qa-recorder/mvp/ado-upload.mjs");
        if (!Files.isRegularFile(tool)) {
            check("the real uploader is on disk", false, String.valueOf(tool));
            return null;
        }
        ProcessBuilder pb = new ProcessBuilder(node(), tool.toString(),
            "--test-case", ADO_ID,
            "--outcome", "passed",
            "--evidence", runDir.toString(),
            "--dry-run",
            "--hook");
        pb.directory(work().toFile());
        pb.redirectErrorStream(true);
        // ON for this call — that is the point — with every route to the organisation stubbed:
        // --dry-run above, and no interactive login possible from here.
        pb.environment().put("ING_ADO_UPLOAD", "1");
        pb.environment().put("ING_ADO_UPLOAD_LOGS", AdoUpload.logsDir().toString());
        pb.environment().put("ADO_NONINTERACTIVE", "1");
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Only costs detail below.
            }
        }, "beleg");
        drain.setDaemon(true);
        drain.start();
        if (!proc.waitFor(180, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            check("ado-upload.mjs answered within three minutes", false, "Timeout");
            return null;
        }
        drain.join(2000);
        try (Stream<Path> s = Files.list(AdoUpload.logsDir())) {
            return s.filter(p -> p.getFileName().toString().startsWith("ado-upload-TC"))
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .max(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                .orElse(null);
        }
    }

    /**
     * Turns the real rehearsal receipt into the one thing a harness cannot obtain honestly: a
     * receipt for an upload that reached Azure DevOps. Two fields, both of them ones the ADO
     * boundary itself fills in.
     */
    private static void patchToOk(Path receipt) throws IOException {
        String json = Files.readString(receipt, StandardCharsets.UTF_8)
            .replace("\"status\": \"PROBELAUF\"", "\"status\": \"OK\"")
            .replace("\"runId\": null", "\"runId\": \"25518817\"")
            .replace("\"runUrl\": null", "\"runUrl\": \"https://dev.azure.com/beispiel-org/"
                + "BeispielProjekt/_testManagement/runs?runId=25518817\"");
        Files.writeString(receipt, json, StandardCharsets.UTF_8);
        if (!json.contains("\"status\": \"OK\"") || !json.contains("25518817")) {
            check("the receipt could be patched to OK — the tool's format has not moved", false,
                json.length() > 400 ? json.substring(0, 400) : json);
        }
    }

    /** A real INGenious run that passed. */
    private static Path fixture() {
        Path full = realRepo().resolve("artifacts/TC-3951253/run-20260721-130758");
        if (Files.isDirectory(full)) {
            return full;
        }
        System.out.println("  (Fixture: ingenious-plugin/sample/lauf-bestanden — artifacts/ ist "
            + "in diesem Checkout nicht vorhanden)");
        return realRepo().resolve("ingenious-plugin/sample/lauf-bestanden");
    }

    /**
     * The repository the fixtures and the real uploader live in.
     *
     * <p>Read from {@code ING_REAL_REPO} and not from {@code ING_QA_REPO}, because the
     * {@code doppelt} scenario points the latter at a stub repository on purpose — and the
     * fixtures still have to come from the real one.
     */
    private static Path realRepo() {
        String env = System.getenv("ING_REAL_REPO");
        if (env == null || env.isBlank()) {
            env = System.getenv("ING_QA_REPO");
        }
        return Paths.get(env == null || env.isBlank() ? "." : env).toAbsolutePath();
    }

    private static Path work() {
        String env = System.getenv("ING_HARNESS_WORK");
        return Paths.get(env == null || env.isBlank() ? "." : env).toAbsolutePath();
    }

    private static String node() {
        String explicit = System.getenv("ING_NODE");
        return explicit == null || explicit.isBlank() ? "node" : explicit.trim();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set — see run-abgabe-harness.sh");
        }
        return value;
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
                    // Windows carries the source's timestamp across, and "which run is newest"
                    // is decided by exactly that timestamp.
                    Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
                }
            }
        }
    }

    /** Gives the real run's test case the name the guided flow would have given it. */
    private static void renameCase(Path dataJs) throws IOException {
        String text = Files.readString(dataJs, StandardCharsets.UTF_8);
        if (!text.contains(ORIGINAL_CASE)) {
            throw new IOException("Fixture enthaelt \"" + ORIGINAL_CASE + "\" nicht: " + dataJs);
        }
        Files.writeString(dataJs, text.replace(ORIGINAL_CASE, CASE_NAME), StandardCharsets.UTF_8);
    }

    private static long count(Path dir, String prefix) {
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
