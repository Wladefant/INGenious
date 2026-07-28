package de.ing.qa.studio;

import de.ing.qa.ado.AdoCache;
import de.ing.qa.ado.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One finished INGenious run — one upload to Azure DevOps.
 *
 * <p>This is the Studio half of issue
 * <a href="https://github.com/Wladefant/ing-qa-automation/issues/124">#124</a>. The upload
 * itself already existed and is proven against the real organisation
 * ({@code ing-qa-recorder/mvp/ado-upload.mjs}, ADO run 25518817); what did not exist was
 * anything calling it from the surface the testers are actually getting. It was called from
 * {@code companion/}'s {@code EngineLoopFacade.confirmOutcome} — the application being retired
 * (<a href="https://github.com/Wladefant/ing-qa-automation/issues/93">#93</a>). A tester in the
 * guided flow therefore got no upload at all.
 *
 * <p><b>Nothing here re-implements the upload.</b> Two proven Node tools are invoked as child
 * processes and their answers are used verbatim:
 *
 * <ol>
 *   <li>{@code tools/parse-report.mjs} — reads an INGenious run directory and says which test
 *       cases ran and whether each passed. The report format is its problem, not this class's.
 *   <li>{@code ing-qa-recorder/mvp/ado-upload.mjs} — evidence ranking, caps, the receipt, the
 *       append-only ledger, and the ADO lifecycle through {@code ado-automark.mjs}.
 * </ol>
 *
 * <p>The properties the companion established are kept exactly:
 *
 * <ul>
 *   <li><b>On by default.</b> Nothing here checks a flag; {@code ado-upload.mjs} owns that
 *       decision and switches off only for {@code ING_ADO_UPLOAD=0}. An opt-in flag is how a
 *       working feature came to look broken in
 *       <a href="https://github.com/Wladefant/ing-qa-automation/issues/82">#82</a>.
 *   <li><b>Never fatal.</b> {@code --hook} makes the child exit 0 whatever happens, and every
 *       path here returns a {@link Result} instead of throwing. A broken ADO cannot cost a
 *       tester their run — the run is already finished and on disk before this starts.
 *   <li><b>Never silent.</b> Every attempt leaves a log file next to the ledger, even the ones
 *       that failed before {@code ado-upload.mjs} could write its own receipt. "Off", "failed"
 *       and "did nothing" must never look alike. Every outcome is also published to
 *       {@link AdoUploadStatus}, so a panel can put it in front of the tester instead of the
 *       handout having to say "the program will not tell you; go and look".
 *   <li><b>A sign-in is asked for, never waited on in silence.</b> {@link AdoSignIn} settles
 *       whether the tester is signed in to Azure DevOps <em>before</em> the progress message,
 *       and opens the login in a window they can see. An expired Entra token used to be
 *       discovered five minutes into "ADO-Upload läuft…", because the {@code az login} that
 *       would have fixed it printed into Studio's pipes
 *       (<a href="https://github.com/Wladefant/ing-qa-automation/issues/128">#128</a>).
 *   <li><b>The comment carries the test case id and nothing else.</b> {@code --comment} is
 *       deliberately not passed: the execution record lives in a live banking system, and
 *       {@code ado-upload.mjs} defaults the comment to the bare id.
 * </ul>
 *
 * <p>Never call this on the Swing event dispatch thread. It waits on child processes that talk
 * to ADO; {@link AdoRunWatcher} runs it on its own daemon thread.
 */
public final class AdoUpload {

    private static final Logger LOG = Logger.getLogger(AdoUpload.class.getName());

    /** The report reader. Repo-relative, like every other tool the panels shell out to. */
    static final String PARSE_REL = "tools/parse-report.mjs";
    /** The upload. Treated as a fixed contract — see the class javadoc. */
    static final String UPLOAD_REL = "ing-qa-recorder/mvp/ado-upload.mjs";

    /** Reading a report is local file work; a minute is already generous. */
    private static final long PARSE_TIMEOUT_SECONDS = 60;
    /** {@code --state} prints one line and touches neither az nor the network. */
    private static final long STATE_TIMEOUT_SECONDS = 60;
    /** ado-upload gives ado-automark six minutes; this has to outlast that. */
    private static final long UPLOAD_TIMEOUT_SECONDS = 7 * 60;

    /**
     * The run directories an upload is running for right now — the lock that makes one finished
     * run one Azure DevOps run, whoever asks.
     *
     * <p>There are two callers now. {@link AdoRunWatcher} still uploads a run the moment it
     * finishes, and {@link AdoSubmission} uploads one that was never published when the tester
     * presses <em>Aufnahme abgeben</em>. Both are right, and both can want the same directory at
     * the same moment: the watcher's settle delay is four seconds, and a tester who presses
     * abgeben straight after a run lands inside the seven minutes the first upload is allowed.
     *
     * <p>This project has already produced two Azure DevOps runs for one test twice — once
     * because the companion had a trigger of its own
     * (<a href="https://github.com/Wladefant/ing-qa-automation/commit/32a6ee4">32a6ee4</a>), once
     * because the engine's own {@code Latest} copy was counted as a second run
     * ({@link AdoRunWatcher#LATEST_FOLDER}). Both were fixed where the second <em>caller</em>
     * was, which fixes that caller and nothing else. The lock is here instead: a duplicate is
     * now impossible through this door regardless of who knocks on it, and a third caller
     * inherits the guarantee rather than having to re-earn it.
     */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * What this Studio session has already finished uploading: {@code <run dir>|<ado id>} to the
     * status line it ended with.
     *
     * <p>A second, weaker guard than {@link AdoReceipts}, and it exists for the one case the
     * receipts cannot cover: {@code ado-upload.mjs} could not write its receipt at all — a full
     * disk, a logs directory somebody removed. It says so loudly on stderr when that happens, so
     * it is not silent, but "loud" is not the same as "remembered", and a second press of
     * abgeben would otherwise re-upload a run that had already reached Azure DevOps.
     *
     * <p>Keyed by run directory <em>and</em> test case, not by test case alone: a test set holds
     * several cases and a tester may run one case twice, and both of those must stay separable.
     * Lost on restart by design — after a restart the receipts are the answer, and if there are
     * none there is genuinely nothing to know.
     */
    private static final Map<String, String> COMPLETED = new ConcurrentHashMap<>();

    /**
     * What happened for one test case of one run — always populated, never an exception.
     *
     * @param adoId the ADO test case id, or {@code null} when none could be established
     * @param testCaseName the INGenious test case name the id was read from
     * @param passed whether the run passed, which decides {@code --outcome}
     * @param status the German one-liner, either the tool's own status line or ours
     */
    public record Result(String adoId, String testCaseName, boolean passed, String status) {
    }

    private AdoUpload() {
    }

    /**
     * Uploads the evidence of one finished run directory.
     *
     * <p>A run directory may hold more than one test case (a test set), so this returns one
     * result per case. A case whose name carries no ADO id is reported as such rather than
     * guessed at: uploading a run to the case that merely happens to be selected in the panel
     * is the one failure this whole feature exists to avoid.
     *
     * <p><b>One run directory, one upload at a time.</b> A caller that asks for a directory
     * another thread is already uploading gets an empty list and nothing else happens — see
     * {@link #IN_FLIGHT}. That is the whole of the duplicate defence between the two callers,
     * and it is deliberately a claim rather than a check: a check would leave the window
     * between asking and starting open, which is exactly the window a tester pressing abgeben
     * four seconds after a run lands in.
     *
     * @param runDir the INGenious run directory, e.g. {@code Results/TestDesign/<Scenario>/<Case>/<date time>}
     * @return one result per test case found, never {@code null}; <b>empty</b> — and only ever
     *     empty — when an upload for this exact directory is already running, in which case the
     *     upload that owns it reports for both
     */
    public static List<Result> forRun(Path runDir) {
        String claim = key(runDir);
        if (!IN_FLIGHT.add(claim)) {
            // No publish: the upload that holds the claim has already published RUNNING and
            // will publish the outcome. A second voice on one status line is how a screen comes
            // to disagree with itself.
            LOG.log(Level.INFO, "An upload is already running for {0}; not starting a second.",
                runDir);
            return List.of();
        }
        try {
            List<Result> results = forRunClaimed(runDir);
            for (Result result : results) {
                if (result.adoId() != null) {
                    COMPLETED.put(claim + "|" + result.adoId(), result.status());
                }
            }
            return results;
        } finally {
            IN_FLIGHT.remove(claim);
        }
    }

    /**
     * How this session's uploads are remembered, so a later press of abgeben can tell an upload
     * that already happened from one that never did.
     *
     * @param runDir the run directory
     * @param adoId the test case id
     * @return the status line that upload ended with, or {@code null} when this session has not
     *     uploaded that case from that run
     */
    public static String completedStatus(Path runDir, String adoId) {
        if (runDir == null || adoId == null) {
            return null;
        }
        return COMPLETED.get(key(runDir) + "|" + adoId);
    }

    /** One spelling per directory, so two callers cannot hold two claims on one run. */
    private static String key(Path runDir) {
        try {
            return runDir.toAbsolutePath().normalize().toString();
        } catch (RuntimeException ex) {
            return String.valueOf(runDir);
        }
    }

    /** The upload itself, run with the claim on {@code runDir} held. */
    private static List<Result> forRunClaimed(Path runDir) {
        List<Result> results = new ArrayList<>();
        Path repo = AdoCache.repoRoot();
        if (repo == null) {
            String status = "ADO-Upload FEHLER — Repo nicht gefunden (" + AdoCache.ENV_REPO
                + " setzen); " + UPLOAD_REL + " konnte nicht aufgerufen werden.";
            writeLog("unbekannt", -1, status + System.lineSeparator() + "Lauf: " + runDir);
            LOG.log(Level.WARNING, status);
            AdoUploadStatus.publish(null, null, AdoUploadStatus.State.FAILED, status);
            results.add(new Result(null, null, false, status));
            return results;
        }

        Map<String, Boolean> cases = readReport(repo, runDir);
        if (cases.isEmpty()) {
            String status = "ADO-Upload FEHLER — im Lauf " + runDir.getFileName()
                + " wurde kein Testfall mit ADO-Nummer erkannt; nichts hochgeladen.";
            writeLog("unbekannt", -1, status + System.lineSeparator() + "Lauf: " + runDir);
            LOG.log(Level.WARNING, status);
            AdoUploadStatus.publish(null, null, AdoUploadStatus.State.FAILED, status);
            results.add(new Result(null, null, false, status));
            return results;
        }

        // Asked once per run, and only when something would actually be uploaded: a sign-in the
        // tester does not need is as unwelcome as a wait they were never told about. Whether
        // uploading is on at all is not re-decided here — ado-upload.mjs's own --state answers
        // it, so the switch cannot mean two different things in two places.
        boolean anyUpload = cases.keySet().stream()
            .anyMatch(name -> AdoNaming.adoIdFromTestCaseName(name) != null);
        boolean checkSignIn = anyUpload && uploadSwitchedOn(repo);

        for (Map.Entry<String, Boolean> entry : cases.entrySet()) {
            String name = entry.getKey();
            boolean passed = Boolean.TRUE.equals(entry.getValue());
            String adoId = AdoNaming.adoIdFromTestCaseName(name);
            if (adoId == null) {
                // Not an error: a project holds test cases that never came from ADO.
                String status = "ADO-Upload ÜBERSPRUNGEN — \"" + name
                    + "\" traegt keine ADO-Nummer im Namen.";
                LOG.log(Level.INFO, status);
                AdoUploadStatus.publish(null, name, AdoUploadStatus.State.SKIPPED, status);
                results.add(new Result(null, name, passed, status));
                continue;
            }
            results.add(upload(repo, runDir, adoId, name, passed, checkSignIn));
        }
        return results;
    }

    /**
     * Whether {@code ado-upload.mjs} would upload at all, taken from its own {@code --state}
     * contract rather than from a second reading of {@code ING_ADO_UPLOAD} here.
     *
     * <p>{@code --state} prints one line and exits: no az, no network, no ADO. It is asked
     * because everything the sign-in check does — spawning az, and possibly opening a login
     * window — must not happen on a machine where uploading is switched off. A duplicate of the
     * flag's own rules in Java would be a second place for "off" to mean something slightly
     * different, which is precisely the class of defect this file keeps paying for.
     *
     * @return {@code true} unless the tool said {@code AUS}; also {@code false} when the tool
     *     could not be asked at all, because opening a sign-in window on the strength of a
     *     question that was never answered is exactly the overreach to avoid — the upload
     *     itself then reports whatever is really wrong
     */
    private static boolean uploadSwitchedOn(Path repo) {
        StringBuilder out = new StringBuilder();
        int exit = run(repo, List.of(node(), repo.resolve(UPLOAD_REL).toString(), "--state"),
            out, STATE_TIMEOUT_SECONDS, Map.of());
        if (exit != 0) {
            LOG.log(Level.INFO, "ado-upload --state could not be asked (exit {0}); "
                + "the sign-in check is skipped.", exit);
            return false;
        }
        for (String raw : out.toString().split("\n")) {
            String line = raw.trim();
            if (line.startsWith("ADO-UPLOAD ")) {
                return !line.substring("ADO-UPLOAD ".length()).trim().startsWith("AUS");
            }
        }
        return false;
    }

    // ----------------------------------------------------------------- the report

    /**
     * Which test cases ran, and whether each passed, straight out of
     * {@code tools/parse-report.mjs}.
     *
     * <p>Falls back to the run directory's own path when the report cannot be read or names
     * nothing usable: an INGenious single-case run lands in
     * {@code Results/TestDesign/<Scenario>/<TestCase>/<date time>}, so the test case name — and
     * with it the ADO id — is in the path itself. The status is then unknown, and unknown is
     * treated as not-passed, because {@code ado-automark} marks Bestanden and only Bestanden.
     *
     * @return test case name to passed-flag, insertion-ordered, possibly empty
     */
    private static Map<String, Boolean> readReport(Path repo, Path runDir) {
        Map<String, Boolean> cases = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder();
        int exit = run(repo, List.of(node(), repo.resolve(PARSE_REL).toString(),
            "--run-dir", runDir.toString()), out, PARSE_TIMEOUT_SECONDS, Map.of());
        if (exit == 0) {
            try {
                Object root = Json.parse(out.toString());
                if (root instanceof Map<?, ?> doc && doc.get("testCases") instanceof List<?> list) {
                    for (Object item : list) {
                        if (!(item instanceof Map<?, ?> tc)) {
                            continue;
                        }
                        String name = shortName(String.valueOf(tc.get("name")));
                        if (name.isBlank()) {
                            continue;
                        }
                        boolean passed = "PASS".equals(String.valueOf(tc.get("status")));
                        // A case that appears twice (iterations) counts as passed only if
                        // every appearance passed.
                        cases.merge(name, passed, (a, b) -> a && b);
                    }
                }
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Report of " + runDir + " could not be read: " + ex);
            }
        } else {
            LOG.log(Level.WARNING, "parse-report exited " + exit + " for " + runDir);
        }
        if (cases.isEmpty()) {
            Path parent = runDir.getParent();
            String fromPath = parent == null ? "" : parent.getFileName().toString();
            if (AdoNaming.adoIdFromTestCaseName(fromPath) != null) {
                LOG.log(Level.INFO, "Falling back to the run directory''s own name: {0}", fromPath);
                cases.put(fromPath, false);
            }
        }
        return cases;
    }

    /** parse-report names a case {@code "<scenario>:<testcase>"}; the id lives in the second half. */
    public static String shortName(String reported) {
        if (reported == null) {
            return "";
        }
        int colon = reported.lastIndexOf(':');
        return (colon < 0 ? reported : reported.substring(colon + 1)).trim();
    }

    // ----------------------------------------------------------------- the upload

    private static Result upload(Path repo, Path runDir, String adoId, String name, boolean passed,
                                 boolean checkSignIn) {
        // The sign-in is settled BEFORE the progress message, not five minutes into it. An
        // expired Entra token used to make ado-automark open an `az login` on Studio's pipes:
        // the prompt went into a log file, the panel said "ADO-Upload läuft…", and the tester
        // watched a progress message for five minutes while the program waited on them (#128).
        boolean signInSettled = false;
        if (checkSignIn) {
            AdoSignIn.Check auth = AdoSignIn.check();
            if (auth.state() == AdoSignIn.State.SIGN_IN_REQUIRED) {
                String ask = "ADO-Anmeldung nötig — " + auth.message()
                    + " Es öffnet sich dafür ein Fenster; bitte dort anmelden. Danach wird das "
                    + "Ergebnis von Testfall " + adoId + " automatisch hochgeladen.";
                LOG.log(Level.INFO, ask);
                AdoUploadStatus.publish(adoId, name, AdoUploadStatus.State.SIGN_IN_REQUIRED, ask);
                auth = AdoSignIn.signIn();
                if (auth.state() != AdoSignIn.State.OK) {
                    String stop = "ADO-Anmeldung nicht abgeschlossen — " + auth.message()
                        + " Es wurde NICHTS nach Azure DevOps hochgeladen. Die Aufnahme liegt "
                        + "weiterhin unter " + runDir + " und kann nach der Anmeldung erneut "
                        + "hochgeladen werden.";
                    writeLog(adoId, -1, "Lauf: " + runDir + System.lineSeparator()
                        + "Testfall: " + name + System.lineSeparator() + stop);
                    LOG.log(Level.WARNING, stop);
                    // FAILED, and NOT SIGN_IN_REQUIRED — although the sign-in is what did not
                    // happen. The two publishes above and here carried the same state and meant
                    // opposite things: the first says "sign in and the upload goes on", this one
                    // says the upload has given up and nothing reached Azure DevOps. A panel
                    // shows one state one way, so it painted this in the amber of "bitte im
                    // geöffneten Fenster anmelden" — a tester whose result had just been dropped
                    // was told to wait for a window that had already closed.
                    //
                    // Fixed here rather than in the panel on purpose. Telling the two apart on
                    // screen would mean bucketing on the German prose, which is exactly what
                    // AdoUploadStatus.State.of refuses to do and for a reason it has already
                    // paid for: a bucket decided by a sentence changes when somebody rewords the
                    // sentence. The publisher is the only place that knows which of the two this
                    // is, so the publisher says so.
                    AdoUploadStatus.publish(adoId, name, AdoUploadStatus.State.FAILED, stop);
                    return new Result(adoId, name, passed, stop);
                }
                LOG.log(Level.INFO, auth.message());
            }
            // UNKNOWN is not treated as either answer: az could not be reached, so the child is
            // left exactly the environment it had before this check existed.
            signInSettled = auth.state() == AdoSignIn.State.OK;
        }

        // Announced before the wait, not after it: ado-upload.mjs is allowed seven minutes, and
        // a screen that says nothing for seven minutes is a screen that looks broken.
        AdoUploadStatus.publish(adoId, name, AdoUploadStatus.State.RUNNING,
            "ADO-Upload läuft… (Testfall " + adoId + ")");
        StringBuilder out = new StringBuilder();
        // No --comment: ado-upload.mjs then defaults the comment to the bare test case id,
        // which is the whole of what may travel into a live banking system.
        List<String> cmd = List.of(node(), repo.resolve(UPLOAD_REL).toString(),
            "--test-case", adoId,
            "--outcome", passed ? "passed" : "failed",
            "--evidence", runDir.toString(),
            "--hook");
        // Studio owns the sign-in, so the child must never open one of its own: an `az login`
        // down there would print into this process's pipes, which is the whole defect. Set only
        // when the question was actually answered — under UNKNOWN the child keeps the proven
        // behaviour it has always had. ADO_NONINTERACTIVE is ado-automark's own guard
        // (https://github.com/Wladefant/ing-qa-automation/commit/976586c); it makes a missing
        // token fail in about a second with an instruction instead of waiting five minutes.
        Map<String, String> childEnv = signInSettled
            ? Map.of("ADO_NONINTERACTIVE", "1") : Map.of();
        int exit = run(repo, cmd, out, UPLOAD_TIMEOUT_SECONDS, childEnv);
        String status = statusLine(out.toString(), exit);
        writeLog(adoId, exit, "Lauf: " + runDir + System.lineSeparator()
            + "Testfall: " + name + System.lineSeparator() + out);
        LOG.log(Level.INFO, status);
        AdoUploadStatus.publish(adoId, name, AdoUploadStatus.State.of(status), status);
        return new Result(adoId, name, passed, status);
    }

    /**
     * The hook's contract is its LAST stdout line: {@code ADO-UPLOAD <STATUS> <text>}. Anything
     * else — a node that would not start, a crash before the line — is reported as such rather
     * than read as success.
     *
     * <p>Deliberately the same reader as {@code companion/src/ui/EngineLoopFacade.statusLine}:
     * while both surfaces exist they must describe the same outcome the same way, and the
     * plugin cannot depend on the companion's classes.
     *
     * @param stdout everything the child printed
     * @param exit the child's exit code, {@code -1} when it never started
     * @return a German one-liner fit for a status bar
     */
    public static String statusLine(String stdout, int exit) {
        String marker = null;
        for (String raw : (stdout == null ? "" : stdout).split("\n")) {
            String line = raw.trim();
            if (line.startsWith("ADO-UPLOAD ")) {
                marker = line.substring("ADO-UPLOAD ".length()).trim();
            }
        }
        if (marker == null) {
            return "ADO-Upload FEHLER — " + UPLOAD_REL + " hat nichts gemeldet (Exit " + exit
                + "). Laeuft node? Siehe Log.";
        }
        int space = marker.indexOf(' ');
        String code = space < 0 ? marker : marker.substring(0, space);
        String text = space < 0 ? "" : marker.substring(space + 1).trim();
        if ("UEBERSPRUNGEN".equals(code)) {
            code = "ÜBERSPRUNGEN";
        }
        return "ADO-Upload " + code + (text.isEmpty() ? "" : " — " + text);
    }

    // ---------------------------------------------------------------------- plumbing

    private static String node() {
        String explicit = System.getenv(AdoCache.ENV_NODE);
        return explicit == null || explicit.isBlank() ? "node" : explicit.trim();
    }

    /**
     * Runs a child process to completion, capturing stdout and stderr together.
     *
     * @param extraEnv variables added on top of Studio's own environment, which is otherwise
     *     inherited unchanged — so whatever the launcher exported still reaches the marker
     * @return the exit code, or {@code -1} when the child could not be started, timed out or
     *     was interrupted — all three mean "no answer", which is what the caller reports
     */
    private static int run(Path workingDir, List<String> command, StringBuilder capture,
                           long timeoutSeconds, Map<String, String> extraEnv) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.environment().putAll(extraEnv);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            // Drained on its own thread: a full pipe buffer would deadlock the child, and
            // reading to EOF here would block past the timeout.
            Thread drain = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    capture.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // Losing the log only costs detail in the status line.
                }
            }, "ado-upload-output");
            drain.setDaemon(true);
            drain.start();
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return -1;
            }
            drain.join(2000);
            return proc.exitValue();
        } catch (IOException ex) {
            capture.append("node konnte nicht gestartet werden: ").append(ex.getMessage());
            return -1;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** Same logs directory {@code ado-upload.mjs} writes its receipts and ledger into. */
    public static Path logsDir() {
        String explicit = System.getenv("ING_ADO_UPLOAD_LOGS");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv("COMPANION_LOGS_DIR");
        }
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit.trim());
        }
        return Paths.get(System.getProperty("user.home", "."), "ingenious", "companion-logs");
    }

    /**
     * Leaves the raw output beside the receipt. {@code ado-upload.mjs} writes its own receipt
     * whenever it runs at all — this is the copy that survives the case where it never did.
     */
    private static void writeLog(String adoId, int exit, String output) {
        try {
            Path dir = logsDir();
            Files.createDirectories(dir);
            // Milliseconds, and then a counter if even that collides. A per-second stamp
            // silently overwrote one log with another: two uploads finishing in the same
            // second — a test set, or a sign-in refused for two cases at once — left one
            // file where there should have been two. Losing evidence to a filename is a
            // poor way to lose it, and the loss was invisible.
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
            Path log = dir.resolve("studio-upload-" + adoId + "-" + stamp + ".log");
            for (int n = 2; Files.exists(log) && n < 100; n++) {
                log = dir.resolve("studio-upload-" + adoId + "-" + stamp + "-" + n + ".log");
            }
            Files.writeString(log, "exit=" + exit + System.lineSeparator() + output,
                StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.WARNING, "Could not write the upload log: " + ex.getMessage());
        }
    }
}
