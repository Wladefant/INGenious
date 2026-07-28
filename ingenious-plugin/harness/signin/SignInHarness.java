package de.ing.qa.studio;

import de.ing.qa.ado.AdoCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Headless proof for the invisible sign-in
 * (<a href="https://github.com/Wladefant/ing-qa-automation/issues/128">#128</a>).
 *
 * <p>The defect was never a crash: an expired Entra token made {@code ado-automark.mjs} open an
 * interactive {@code az login} on Studio's pipes, so the prompt went into a log file while the
 * panel said "ADO-Upload läuft…" for five minutes. What has to be proven is therefore not that
 * something works, but <b>when the tester is told</b> — before the wait, in their own language,
 * and never claiming progress that is not being made.
 *
 * <p><b>Nothing here talks to Azure DevOps or to a real {@code az}.</b> A fake {@code az} is put
 * first on {@code PATH} by the runner and records every invocation, exactly as
 * {@code ado-automark.mjs}'s own selftest does; the uploader is a stub in a fake repo. Two
 * consequences worth stating plainly: no ADO run can be created, and no browser prompt can
 * appear on anybody's screen. What a scenario cannot decide it reports as {@code UNGEPRUEFT}
 * (exit 3) rather than passing.
 *
 * <p>Each scenario runs in its own JVM: the environment decides everything here, and a running
 * JVM cannot change its own.
 *
 * <p>In {@code de.ing.qa.studio} for the same reason {@code AdoUploadProbe} is: the two things
 * worth asserting on — the shape of the login command and the tenant it is scoped to — are
 * package-private, and making them public so a harness can read them would be widening an API
 * for a test.
 */
public final class SignInHarness {

    private static final List<String> FAILURES = new ArrayList<>();
    private static final List<AdoUploadStatus.Event> EVENTS = new CopyOnWriteArrayList<>();

    private SignInHarness() {
    }

    public static void main(String[] args) {
        String scenario = args.length == 0 ? "" : args[0];
        System.out.println("== " + scenario + " ==");
        AdoUploadProbe.reset();
        AdoUploadStatus.addListener(EVENTS::add);
        switch (scenario) {
            case "probe-bearer" -> probeBearer();
            case "probe-cached" -> probeCached();
            case "probe-signed-out" -> probeSignedOut();
            case "signin-window" -> signInWindow();
            case "signin-headless" -> signInHeadless();
            case "upload-signin-required" -> uploadSignInRequired();
            case "upload-token-ok" -> uploadTokenOk();
            case "upload-outcome-failed" -> uploadOutcomeFailed();
            case "refresh-signin-required" -> refreshSignInRequired();
            case "refresh-token-ok" -> refreshTokenOk();
            default -> {
                System.out.println("unknown scenario: " + scenario);
                System.exit(2);
            }
        }
        if (FAILURES.isEmpty()) {
            System.out.println("RESULT: GREEN");
            System.exit(0);
        }
        FAILURES.forEach(f -> System.out.println("  RED: " + f));
        System.out.println("RESULT: RED — " + FAILURES.size() + " check(s) failed.");
        System.exit(1);
    }

    // ------------------------------------------------------------------ scenarios

    /**
     * An injected {@code ADO_BEARER} is honoured and {@code az} is never spawned — the same
     * precedence {@code ado-automark.mjs} proves for itself, asserted here so the two cannot
     * drift apart. The recording of the fake {@code az} must stay empty.
     */
    private static void probeBearer() {
        AdoSignIn.Check check = AdoSignIn.check();
        is("ADO_BEARER answers the question", check.state() == AdoSignIn.State.OK, check.message());
        is("the sentence names no token", !check.message().contains("eyJ"), check.message());
        noAzWasSpawned("ADO_BEARER spawns no az");
    }

    /** A live token cache answers on its own: no az, no network, no prompt. */
    private static void probeCached() {
        AdoSignIn.Check check = AdoSignIn.check();
        is("a valid cached token answers the question", check.state() == AdoSignIn.State.OK,
            check.message());
        noAzWasSpawned("a valid cached token spawns no az");
    }

    /**
     * The heart of it: logged out, the question is answered in seconds, the answer is
     * SIGN_IN_REQUIRED, and the probe itself never opened a login. A probe that could open one
     * would be the defect again, one layer up.
     */
    private static void probeSignedOut() {
        long start = System.currentTimeMillis();
        AdoSignIn.Check check = AdoSignIn.check();
        long millis = System.currentTimeMillis() - start;
        is("logged out is recognised", check.state() == AdoSignIn.State.SIGN_IN_REQUIRED,
            check.state() + " — " + check.message());
        is("the tester is addressed, not az", check.message().contains("Azure DevOps")
            && !check.message().contains("az "), check.message());
        is("answered in seconds, not minutes", millis < 60_000, millis + " ms");
        String asked = read(shimLog());
        is("az was asked for a token", asked.contains("get-access-token"), asked);
        is("az was NOT asked to log in", !asked.matches("(?s).*(^|\\s)login(\\s|$).*"), asked);
    }

    /**
     * The visible sign-in actually runs the login, and its verdict comes from re-asking whether a
     * token can be had — not from az's exit code, because the question was never "did az exit 0".
     */
    private static void signInWindow() {
        List<String> command = AdoSignIn.loginCommand();
        String flat = String.join(" ", command);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            is("the login gets its own window", flat.contains("start") && flat.contains("/wait"), flat);
            is("the login is tenant-scoped", flat.contains(AdoConfig.tenantId()), flat);
        } else {
            System.out.println("  (not windows: no separate console to assert on)");
        }
        AdoSignIn.Check after = AdoSignIn.signIn();
        String asked = read(shimLog());
        is("az was told to log in", asked.contains("login"), asked);
        is("the sign-in is reported as successful", after.state() == AdoSignIn.State.OK,
            after.state() + " — " + after.message());
        is("the verdict was re-checked, not assumed", asked.contains("get-access-token"), asked);
    }

    /**
     * A process with no screen opens no window — the guard that keeps every OTHER harness in
     * this repo from putting a real Azure DevOps prompt on a developer's laptop, now that
     * {@code AdoCache.refresh} settles the sign-in before it spawns the tool. Asserted with the
     * fake az still on PATH: if the guard were missing, its recording would carry a login.
     */
    private static void signInHeadless() {
        is("the harness really is headless", java.awt.GraphicsEnvironment.isHeadless(),
            "java.awt.headless=" + System.getProperty("java.awt.headless"));
        AdoSignIn.Check check = AdoSignIn.signIn();
        is("a screenless process does not open a login",
            check.state() == AdoSignIn.State.UNKNOWN, check.state() + " — " + check.message());
        is("az was never asked to log in", !read(shimLog()).contains("login"), read(shimLog()));
        is("and it says so, rather than reporting a sign-in that did not happen",
            check.message().contains("Bildschirm"), check.message());
        // The line above is the whole point of this scenario and it is an ABSENCE. The shim
        // writes `%*` to the log before it looks at anything, so a mechanism that records a
        // probe records a login too — the control below is therefore a control for this
        // assertion as well as for the file's existence.
        azLogIsLive();
    }

    /**
     * The whole Studio path, logged out: the tester is told a sign-in is needed <em>before</em>
     * any progress message, the login window is opened, and when it comes to nothing the upload
     * is not attempted — nothing is claimed, and the recording is named so it is not lost.
     *
     * <p><b>The two publishes are two different states, and this is where that is pinned.</b>
     * Both used to be {@link AdoUploadStatus.State#SIGN_IN_REQUIRED} and this harness asserted
     * it — "it ends on a state a panel stops on: SIGN_IN_REQUIRED", "it is neither a success nor
     * a failure". Both sentences were true of the <em>first</em> publish, which asks a question
     * the tester can answer, and false of the last one, which says the upload gave up and
     * nothing reached Azure DevOps. The panel had one arm for the one state, so it painted the
     * give-up in the amber of "bitte im geöffneten Fenster anmelden": a tester whose result had
     * just been dropped was told to wait. Nothing here noticed, because this file asserted the
     * defect. The ask is still SIGN_IN_REQUIRED and still first; the end is
     * {@link AdoUploadStatus.State#FAILED}, and that is asserted through the real publisher —
     * {@link AdoUpload#forRun} against a logged-out fake {@code az} — rather than through a
     * probe that could only repeat the harness's own opinion.
     */
    private static void uploadSignInRequired() {
        List<AdoUpload.Result> results = AdoUpload.forRun(runDir());
        is("one result per test case", results.size() == 1, String.valueOf(results.size()));
        List<AdoUploadStatus.Event> events = new ArrayList<>(EVENTS);
        is("something was published", !events.isEmpty(), states(events));
        boolean askedFirst = !events.isEmpty()
            && events.get(0).state() == AdoUploadStatus.State.SIGN_IN_REQUIRED;
        is("the FIRST thing said is that a sign-in is needed", askedFirst,
            events.isEmpty() ? "none" : events.get(0).state().name());
        is("no progress was ever claimed",
            events.stream().noneMatch(e -> e.state() == AdoUploadStatus.State.RUNNING),
            states(events));
        AdoUploadStatus.Event last = events.isEmpty() ? null : events.get(events.size() - 1);
        is("it ends on a state a panel stops on",
            last != null && last.state() == AdoUploadStatus.State.FAILED
                && last.state().isTerminal(), last == null ? "none" : last.state().name());
        is("the tester is told nothing was uploaded",
            last != null && last.message().contains("NICHTS"), last == null ? "" : last.message());
        is("the recording is named so it is not lost",
            last != null && last.message().contains(runDir().getFileName().toString()),
            last == null ? "" : last.message());
        // Not "neither a success nor a failure" any more, which is what this said while both
        // publishes carried one state. Nothing reached Azure DevOps and nothing is going to:
        // the sign-in window has been and gone. That is a failure, and it is the ONLY thing
        // that stops the panel painting it as a question still worth waiting for.
        is("the end is a failure, not a question", last != null && last.state().isTerminal()
            && !last.state().isSuccess() && last.state() == AdoUploadStatus.State.FAILED,
            last == null ? "" : last.state().name());
        // The ask and the give-up are now two different states — asserted as a difference, so
        // that publishing one value for both again is red here rather than amber on a panel.
        is("the ask and the give-up are not the same state",
            events.size() >= 2 && events.get(0).state() != events.get(events.size() - 1).state(),
            states(events));
        // The claim of #128, and an absence: run last, with its own control.
        noToolWasStarted("the uploader was never started", stubLog(),
            AdoUpload.UPLOAD_REL);
    }

    /**
     * The ordinary path is untouched: with a live token nobody is asked anything, the upload runs
     * as it always did, and the child is told not to open a login of its own — because Studio
     * would have opened it, visibly, before ever getting here.
     */
    private static void uploadTokenOk() {
        List<AdoUpload.Result> results = AdoUpload.forRun(runDir());
        List<AdoUploadStatus.Event> events = new ArrayList<>(EVENTS);
        is("one result per test case", results.size() == 1, String.valueOf(results.size()));
        is("no sign-in was asked for",
            events.stream().noneMatch(e -> e.state() == AdoUploadStatus.State.SIGN_IN_REQUIRED),
            states(events));
        is("the ordinary progress message is still shown",
            events.stream().anyMatch(e -> e.state() == AdoUploadStatus.State.RUNNING), states(events));
        AdoUploadStatus.Event last = events.isEmpty() ? null : events.get(events.size() - 1);
        is("it ends OK", last != null && last.state() == AdoUploadStatus.State.OK,
            last == null ? "none" : last.state() + " — " + last.message());
        is("the uploader was started", Files.exists(stubLog()), "stub log: " + stubLog());
        is("the child may not open a login of its own",
            read(stubLog()).contains("ADO_NONINTERACTIVE=1"), read(stubLog()));
        noAzWasSpawned("no az was needed at all");
    }

    /**
     * The safety property, from the Java side: a run that did NOT pass must reach the uploader
     * as {@code --outcome failed}, because {@code ado-automark} marks Bestanden and only
     * Bestanden. Nothing observed that argument before — invert the ternary in
     * {@link AdoUpload} and every harness in the project stayed green.
     *
     * <p>The same scenario uploads twice in a row, which is how the per-second log stamp was
     * caught: two uploads finishing inside one second used to leave one file where there should
     * have been two.
     */
    private static void uploadOutcomeFailed() {
        AdoUpload.forRun(runDir());
        AdoUpload.forRun(runDir());
        String handed = read(stubLog());
        is("a failed run is handed to the uploader AS failed", handed.contains("--outcome failed"),
            handed);
        is("it is never dressed up as passed", !handed.contains("--outcome passed"), handed);
        is("the uploader's refusal is what the tester is shown",
            EVENTS.stream().anyMatch(e -> e.state() == AdoUploadStatus.State.SKIPPED),
            states(new ArrayList<>(EVENTS)));
        long logs = logCount();
        is("two uploads in the same second leave two logs", logs == 2, logs + " log file(s)");
    }

    /**
     * "Aus ADO aktualisieren", logged out. The identical defect lived here: {@code
     * ado-testcases.mjs} imports the same token function, so the button used to spend five
     * minutes on an {@code az login} whose prompt went into a pipe. The tester must now be told
     * in one sentence, and the tool must not have been started at all.
     */
    private static void refreshSignInRequired() {
        long start = System.currentTimeMillis();
        AdoCache.RefreshResult result = AdoCache.refresh();
        long millis = System.currentTimeMillis() - start;
        is("the refresh reports failure", !result.ok(), result.message());
        is("and says a sign-in is what is missing", result.message().contains("Anmeldung")
            && result.message().contains("Azure DevOps"), result.message());
        is("the tester is told nothing was updated",
            result.message().contains("keine Testfaelle"), result.message());
        is("answered in seconds, not minutes", millis < 120_000, millis + " ms");
        is("az WAS asked for a login", read(shimLog()).contains("login"), read(shimLog()));
        noToolWasStarted("the tool was never started", refreshLog(), REFRESH_TOOL_REL);
    }

    /** Signed in: the refresh runs as it always did, and the child may open no login of its own. */
    private static void refreshTokenOk() {
        AdoCache.RefreshResult result = AdoCache.refresh();
        is("the refresh succeeds", result.ok(), result.message());
        is("the tool was started", Files.exists(refreshLog()), read(refreshLog()));
        is("the child may not open a login of its own",
            read(refreshLog()).contains("ADO_NONINTERACTIVE=1"), read(refreshLog()));
        noAzWasSpawned("no az was needed at all");
    }

    // --------------------------------------------------------- assertions of ABSENCE
    //
    // AN ASSERTION THAT SOMETHING DID NOT HAPPEN IS WORTHLESS UNLESS THE SAME MECHANISM IS
    // SHOWN CAPABLE OF OBSERVING IT HAPPENING.  (2026-07-28)
    //
    // Five of this file's strongest claims — "no az was needed at all", "the uploader was
    // never started", "the tool was never started" — were `!Files.exists(someLog())`. Each one
    // is satisfied by the recording never having worked in the first place. The fake `az` not
    // reaching PATH in that scenario's `run`, an UPLOAD_STUB_LOG the child could not append to,
    // a stub written to a fake repo the code under test never looks in: all of them silently
    // produce the passing answer, and the scenario prints "ok" for the wrong reason. That is
    // the #128 defect itself — a five-minute invisible `az login` — reported as fixed by a
    // check that could not have seen it.
    //
    // The shell half of this harness already had the shape of the cure and it was not applied
    // here: `check_tc "no invisible az login was opened"` sits directly above `check_tc "a
    // token WAS attempted (so the refusal is real, not a skipped step)"`, both reading the same
    // log. The pair cannot hold vacuously — an empty log fails the second.
    //
    // So each absence below is followed by a POSITIVE CONTROL against the same file, through
    // the same route the code under test uses: spawn the thing on purpose, then require the
    // recording to appear. The control runs AFTER the assertion it protects, always, so it can
    // never be the reason that assertion passes.

    /** The argument the positive controls pass, so their own line is unmistakable in the log. */
    private static final String CONTROL = "harness-positive-control";

    /** Where {@code AdoCache} looks for the refresh tool, relative to {@code ING_QA_REPO}. */
    private static final String REFRESH_TOOL_REL = "tools/ado-testcases.mjs";

    /** "no az ran" — asserted, and then shown to have been an assertion at all. */
    private static void noAzWasSpawned(String what) {
        is(what, !Files.exists(shimLog()), read(shimLog()));
        azLogIsLive();
    }

    /**
     * The control for every {@code az} absence: run {@link AdoSignIn#probeCommand()} — the very
     * command the code under test spawns, {@code cmd.exe /c az …} and all — and require this
     * scenario's {@code AZ_SHIM_LOG} to have grown a line.
     *
     * <p>Reusing {@code probeCommand()} rather than writing {@code new ProcessBuilder("az")} is
     * the point: PATH resolution, the {@code cmd.exe} indirection Windows needs for an
     * {@code az.cmd}, and the inherited environment are then the same ones the absence is a
     * claim about. A control that took a different route would prove a different scenario.
     */
    private static void azLogIsLive() {
        List<String> command = new ArrayList<>(AdoSignIn.probeCommand());
        command.add(CONTROL);
        String how = spawn(command);
        is("positive control: an az really would have been recorded here",
            read(shimLog()).contains(CONTROL), how + "  ->  " + read(shimLog()));
    }

    /**
     * "the child process was never started" — asserted, then controlled by starting it.
     *
     * @param what   the assertion's own words
     * @param log    the file the child appends to when it runs
     * @param relTool where the tool sits under {@code ING_QA_REPO} — the same relative path the
     *                code under test resolves, so a stub written to the wrong place is caught
     */
    private static void noToolWasStarted(String what, Path log, String relTool) {
        is(what, !Files.exists(log), "log: " + read(log));
        String repo = System.getenv("ING_QA_REPO");
        if (repo == null || repo.isBlank()) {
            is("positive control: " + what + " (ING_QA_REPO names the fake repo)", false,
                "ING_QA_REPO is unset — the control cannot start the tool it is a control for");
            return;
        }
        // --outcome failed makes the uploader stub take its refusal path and the refresh stub
        // ignore the flag; either way it appends the line this control is looking for, and
        // neither can reach Azure DevOps — there is no network code in either stub.
        String how = spawn(List.of(node(), Paths.get(repo).resolve(relTool).toString(),
            "--outcome", "failed"));
        is("positive control: this scenario's log really would have recorded a run",
            Files.exists(log), how + "  ->  " + read(log));
    }

    /** {@code ING_NODE} or plain {@code node} — the same choice {@code AdoUpload} makes. */
    private static String node() {
        String explicit = System.getenv("ING_NODE");
        return explicit == null || explicit.isBlank() ? "node" : explicit.trim();
    }

    /** Runs a control to completion and returns what it was, for the log line. */
    private static String spawn(List<String> command) {
        String flat = String.join(" ", command);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "control TIMED OUT: " + flat;
            }
            return "control ran: " + flat;
        } catch (Exception ex) {
            return "control COULD NOT RUN (" + ex + "): " + flat;
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Path refreshLog() {
        return Paths.get(System.getenv("REFRESH_STUB_LOG"));
    }

    /** How many {@code studio-upload-*.log} files this scenario left behind. */
    private static long logCount() {
        try (var files = Files.list(Paths.get(System.getenv("ING_ADO_UPLOAD_LOGS")))) {
            return files.filter(p -> p.getFileName().toString().startsWith("studio-upload-")).count();
        } catch (Exception ex) {
            return -1;
        }
    }

    private static void is(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + "  [" + oneLine(detail) + "]");
        if (!ok) {
            FAILURES.add(what + " — " + oneLine(detail));
        }
    }

    private static String states(List<AdoUploadStatus.Event> events) {
        return events.stream().map(e -> e.state().name()).reduce((a, b) -> a + " -> " + b).orElse("none");
    }

    private static Path runDir() {
        return Paths.get(System.getenv("SIGNIN_RUN_DIR"));
    }

    private static Path shimLog() {
        return Paths.get(System.getenv("AZ_SHIM_LOG"));
    }

    private static Path stubLog() {
        return Paths.get(System.getenv("UPLOAD_STUB_LOG"));
    }

    private static String read(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path).trim() : "(nichts)";
        } catch (Exception ex) {
            return "(nicht lesbar: " + ex.getMessage() + ")";
        }
    }

    private static String oneLine(String text) {
        String s = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }
}
