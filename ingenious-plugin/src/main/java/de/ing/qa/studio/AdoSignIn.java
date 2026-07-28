package de.ing.qa.studio;

import de.ing.qa.ado.Json;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Whether the tester is signed in to Azure DevOps — asked <em>before</em> the wait, and
 * answered in a window they can see.
 *
 * <p><b>Why this exists.</b> When the cached Entra token has expired, {@code ado-automark.mjs}
 * opens an interactive {@code az login}. That is the correct and proven behaviour on a tester's
 * laptop — but under Studio the child runs on Java's pipes, so everything {@code az} prints goes
 * into a log file nobody opens while the panel reads "ADO-Upload läuft…" for up to five minutes.
 * Nothing deadlocks; it simply spends five minutes claiming to make progress while waiting for a
 * person who was never asked. That is
 * <a href="https://github.com/Wladefant/ing-qa-automation/issues/128">#128</a>, and it is this
 * project's recurring fault in its slowest form: the display not matching reality.
 *
 * <p><b>What this does instead.</b> {@link #check()} answers "is a sign-in needed?" without ever
 * opening a prompt, in about a second, so the question can be asked before a recording is handed
 * to the uploader. {@link #signIn()} then opens the login in its <em>own console window</em>,
 * where the tester can see it and where {@code az} puts its own instructions, and waits for that
 * window to close.
 *
 * <p><b>The auth rules are not re-decided here.</b> {@link #check()} reads exactly the precedence
 * {@code ado-automark.mjs} uses — {@code ADO_BEARER} first, then the ~50-minute token cache, then
 * {@code az} — and asks {@code az} the same non-interactive question the marker asks first
 * ({@code az account get-access-token}). It never mints anything of its own, never writes the
 * token cache, and never holds a token: the answer it keeps is a boolean. No PAT is involved in
 * any path (ADR-0007).
 *
 * <p><b>Duplication, deliberately bounded.</b> Two GUIDs, the cache location and the four
 * "you are logged out" markers are repeated from {@code ado-automark.mjs}. Everything that
 * decides an ADO <em>write</em> stays in the marker; what is repeated here is only the local
 * question "does a token exist", which cannot be answered by shelling out to the marker without
 * risking the very login this class exists to make visible.
 *
 * <p>Never call this on the Swing event dispatch thread: {@link #check()} may wait seconds on a
 * child process and {@link #signIn()} may wait minutes on a human.
 */
public final class AdoSignIn {

    private static final Logger LOG = Logger.getLogger(AdoSignIn.class.getName());

    /** The ADO resource a token must be minted for. Same GUID as {@code ado-automark.mjs}. */
    static final String ADO_RESOURCE_ID = "499b84ac-1321-427f-aa17-267ca6975798";
    /**
     * The tenant the login must be scoped to — <b>configuration, never a literal</b>.
     *
     * <p>It used to be a constant here. It is an ING GUID and this source is public, so it now
     * comes from the same {@code ado-config.json} the Node tools read (see {@link AdoConfig}),
     * and an install that has not been set up gets a sentence saying which setting is missing
     * instead of a login scoped to somebody else's tenant.
     *
     * @return the configured tenant
     * @throws IllegalStateException naming the setting, when the machine has not been set up
     */
    static String tenantId() {
        return AdoConfig.tenantId();
    }

    /** Asking az for a token is a local call plus one Entra round trip; 40s is generous. */
    private static final long PROBE_TIMEOUT_SECONDS = 40;
    /** The same five minutes {@code ado-automark.mjs} allows its own login. */
    private static final long LOGIN_TIMEOUT_SECONDS = 5 * 60;
    /** Shorter than any real token, longer than any stray newline — see {@link #runAz}. */
    private static final int PLAUSIBLE_TOKEN_CHARS = 20;

    private AdoSignIn() {
    }

    /**
     * Whether a sign-in is needed.
     *
     * <p>{@link #UNKNOWN} is a first-class answer, not a failure: {@code az} may be absent or
     * unanswerable, and a probe that guessed in that case would either send a signed-in tester
     * to a login they do not need or promise an upload that cannot happen. A caller that gets
     * {@link #UNKNOWN} must behave exactly as it did before this class existed.
     */
    public enum State {
        /** A token is available, or can be minted without asking anyone anything. */
        OK,
        /** There is no session: an interactive login is the only way on. */
        SIGN_IN_REQUIRED,
        /** The question could not be answered — say so, change nothing. */
        UNKNOWN
    }

    /**
     * The answer plus the German sentence explaining it, ready to put on screen unchanged.
     *
     * @param state what was established
     * @param message why, in the tester's language — never a command line, never a token
     */
    public record Check(State state, String message) {

        /** Whether the upload can proceed without anyone signing in. */
        public boolean ok() {
            return state == State.OK;
        }
    }

    // ------------------------------------------------------------------ the question

    /**
     * Asks whether a sign-in is needed, without ever opening one.
     *
     * <p>Follows {@code ado-automark.mjs}'s precedence exactly, and stops at the first step that
     * answers: an injected {@code ADO_BEARER} (a pipeline agent's token) means no {@code az} is
     * consulted at all, a live token cache means the same, and only then is {@code az} asked —
     * with {@code account get-access-token}, which prints an error when logged out and never
     * waits for anybody.
     *
     * @return the state and a German sentence; never {@code null}, never throws
     */
    public static Check check() {
        String bearer = System.getenv("ADO_BEARER");
        if (bearer != null && !bearer.isBlank()) {
            return new Check(State.OK, "Ein Azure-DevOps-Token wurde bereitgestellt (ADO_BEARER).");
        }
        if (cachedTokenValid()) {
            return new Check(State.OK, "Die Azure-DevOps-Anmeldung ist gültig (Token im Zwischenspeicher).");
        }
        AzResult probe = runAz(probeCommand(), PROBE_TIMEOUT_SECONDS);
        if (probe.notFound()) {
            // az is routinely installed where PATH does not reach it — ado-automark.mjs carries
            // the same three candidate locations for exactly this reason, and a probe that gave
            // up here would quietly answer UNKNOWN on the one machine that matters.
            Path installed = installedAz();
            if (installed != null) {
                candidateAz = installed;
                probe = runAz(candidateProbeCommand(installed), PROBE_TIMEOUT_SECONDS);
            }
        }
        if (probe.exit() == 0 && probe.gotToken()) {
            return new Check(State.OK, "Die Azure-DevOps-Anmeldung ist gültig.");
        }
        if (probe.notFound()) {
            return new Check(State.UNKNOWN, "Die Azure CLI (az) wurde auf diesem Rechner nicht "
                + "gefunden — die Anmeldung konnte nicht geprüft werden.");
        }
        if (saysLoggedOut(probe.errors())) {
            return new Check(State.SIGN_IN_REQUIRED,
                "Sie müssen sich einmal bei Azure DevOps anmelden.");
        }
        return new Check(State.UNKNOWN, "Die Anmeldung bei Azure DevOps konnte nicht geprüft "
            + "werden: " + firstLine(probe.errors()));
    }

    /**
     * Opens the sign-in in a window the tester can see, and waits for it to be finished.
     *
     * <p>The mechanism is the point. {@code az login} run as a plain child of Studio inherits
     * Java's pipes: its instructions land in a log file and the tester is left looking at a
     * progress message. So on Windows the login is started through {@code cmd /c start … /wait},
     * which gives it its own console — the tester sees a window titled "Azure DevOps Anmeldung",
     * {@code az} prints into it, and the browser prompt it opens now has something explaining
     * itself behind it. {@code /wait} is what lets this method know when the tester is done
     * instead of polling {@code az} once every few seconds.
     *
     * <p>{@code || pause} keeps the window open when the login fails, so the reason stays on
     * screen rather than vanishing with the console.
     *
     * <p><b>No screen, no window.</b> A JVM started headless — every harness in this repo, and
     * anything running in a pipeline — has nowhere to put a console and nobody in front of it,
     * so this refuses rather than opening a login that would be answered by nobody. Studio is a
     * Swing application and is never headless, so the tester's path is untouched; what this
     * removes is the possibility that a test run, on somebody's laptop, opens a real Azure
     * DevOps prompt on their screen. The answer is {@link State#UNKNOWN}, which every caller
     * already treats as "change nothing".
     *
     * @return {@link State#OK} only when a token can be obtained afterwards — the login's own
     *     exit code is not trusted, because the question was never "did az exit 0" but "can we
     *     upload now"
     */
    public static Check signIn() {
        if (GraphicsEnvironment.isHeadless()) {
            return new Check(State.UNKNOWN, "Dieser Vorgang läuft ohne Bildschirm; es wurde kein "
                + "Anmeldefenster geöffnet. Bitte im INGenious Studio anmelden.");
        }
        List<String> command;
        try {
            command = loginCommand();
        } catch (IllegalStateException notConfigured) {
            // No tenant configured: there is nothing to scope a login to. Say which setting is
            // missing rather than open a login that would sign the tester into the wrong place.
            return new Check(State.UNKNOWN, notConfigured.getMessage());
        }
        LOG.log(Level.INFO, "Opening a visible Azure DevOps sign-in: {0}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            drain(proc.getInputStream(), "ado-signin-window");
            if (!proc.waitFor(LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return new Check(State.SIGN_IN_REQUIRED, "Die Anmeldung wurde nicht innerhalb von "
                    + (LOGIN_TIMEOUT_SECONDS / 60) + " Minuten abgeschlossen.");
            }
        } catch (IOException ex) {
            return new Check(State.UNKNOWN,
                "Das Anmeldefenster konnte nicht geöffnet werden: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Check(State.UNKNOWN, "Die Anmeldung wurde unterbrochen.");
        }
        Check after = check();
        if (after.state() == State.OK) {
            return new Check(State.OK, "Die Anmeldung bei Azure DevOps war erfolgreich.");
        }
        if (after.state() == State.UNKNOWN) {
            return after;
        }
        return new Check(State.SIGN_IN_REQUIRED,
            "Die Anmeldung bei Azure DevOps wurde nicht abgeschlossen.");
    }

    // ------------------------------------------------------------------ the commands

    /**
     * Where {@link #check()} found {@code az} when PATH did not reach it, so the login is opened
     * with the same executable the token was asked for. Null until a probe has had to fall back;
     * a memo of what was learned, never a configuration.
     */
    private static volatile Path candidateAz;

    /** The three places az installs to on these machines. Copied from {@code ado-automark.mjs}. */
    private static Path installedAz() {
        Path home = Paths.get(System.getProperty("user.home", "."));
        List<Path> candidates = List.of(
            home.resolve("azure-cli").resolve("bin").resolve("az.cmd"),
            Paths.get("C:\\Program Files (x86)\\Microsoft SDKs\\Azure\\CLI2\\wbin\\az.cmd"),
            Paths.get("C:\\Program Files\\Microsoft SDKs\\Azure\\CLI2\\wbin\\az.cmd"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The same question, asked of an az that PATH does not reach. */
    private static List<String> candidateProbeCommand(Path az) {
        List<String> command = new ArrayList<>();
        command.add(az.toString());
        command.addAll(List.of("account", "get-access-token",
            "--resource", ADO_RESOURCE_ID, "--query", "accessToken", "-o", "tsv"));
        return List.copyOf(command);
    }

    /** The non-interactive question. Prints an error when logged out; never waits for a person. */
    static List<String> probeCommand() {
        List<String> command = new ArrayList<>();
        if (windows()) {
            // `az` on Windows is az.cmd, which CreateProcess will not resolve from PATH on its
            // own — ado-automark.mjs passes shell:true for the same reason.
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add("az");
        command.addAll(List.of("account", "get-access-token",
            "--resource", ADO_RESOURCE_ID, "--query", "accessToken", "-o", "tsv"));
        return List.copyOf(command);
    }

    /**
     * The visible login. Package-private so a harness can assert on its shape without a tester's
     * browser being opened to prove it.
     */
    static List<String> loginCommand() {
        if (!windows()) {
            // No Studio ships on anything but Windows; there is no separate-console equivalent
            // worth inventing here, so this is the plain child — honest, and never reached.
            return List.of("az", "login", "--tenant", tenantId());
        }
        // The title must carry a space: ProcessBuilder quotes an argument that contains one, and
        // `start` reads its first UNQUOTED token as a program to run rather than as a title.
        Path az = candidateAz;
        if (az != null) {
            // az was found off PATH, so name it. `start` runs it directly — there is no inner
            // `cmd /c` to hang a `|| pause` on, so a failed login's window closes on its own;
            // the tester is told by the panel either way.
            return List.of("cmd.exe", "/c", "start", "Azure DevOps Anmeldung", "/wait",
                az.toString(), "login", "--tenant", tenantId());
        }
        return List.of("cmd.exe", "/c", "start", "Azure DevOps Anmeldung", "/wait",
            "cmd.exe", "/c", "az login --tenant " + tenantId() + " || pause");
    }

    // ------------------------------------------------------------------ plumbing

    /** Where {@code ado-automark.mjs} keeps its ~50-minute token cache. */
    static Path tokenCachePath() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local == null || local.isBlank()
            ? Paths.get(System.getProperty("user.home", "."))
            : Paths.get(local.trim());
        return base.resolve("IngQaAutopilot").resolve("token.json");
    }

    /**
     * Whether the cache holds a token that is still good in a minute's time — the same margin
     * {@code ado-automark.mjs} applies, so the two cannot disagree about the same file.
     *
     * <p>Only {@code expires_at} is read. The token itself is never loaded into a variable that
     * could reach a log, a status line or a stack trace.
     */
    private static boolean cachedTokenValid() {
        Path path = tokenCachePath();
        try {
            if (!Files.isRegularFile(path)) {
                return false;
            }
            Object root = Json.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> map)) {
                return false;
            }
            if (!(map.get("access_token") instanceof String token) || token.isBlank()) {
                return false;
            }
            double expiresAt = number(map.get("expires_at"));
            return expiresAt > (System.currentTimeMillis() / 1000d) + 60;
        } catch (IOException | RuntimeException ex) {
            // A missing, half-written or unreadable cache simply means "not from here".
            return false;
        }
    }

    private static double number(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        try {
            return value == null ? 0 : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * The four sentences a logged-out {@code az} answers with — copied from
     * {@code ado-automark.mjs}'s {@code needsLogin}, so the marker and the probe cannot come to
     * different conclusions about the same stderr.
     */
    private static boolean saysLoggedOut(String stderr) {
        String e = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        return e.contains("az login") || e.contains("please run")
            || e.contains("no subscription") || e.contains("aadsts");
    }

    /**
     * What one {@code az} call answered.
     *
     * @param exit the exit code, {@code -1} when it never started or never answered
     * @param gotToken whether stdout carried something token-shaped — a boolean on purpose, see
     *     {@link #runAz}
     * @param errors stderr, for classification and for the sentence shown when it is unexpected
     * @param notFound whether {@code az} could not be found at all
     */
    private record AzResult(int exit, boolean gotToken, String errors, boolean notFound) {
    }

    /**
     * Runs one {@code az} call to completion.
     *
     * <p>stdout is reduced to a yes/no <em>inside the drain thread</em> and never kept: on the
     * probe call that stream is the access token itself, and a token that reaches a variable is
     * a token that can reach a log line. stderr is kept, because it is what tells a logged-out
     * session apart from a broken installation.
     */
    private static AzResult runAz(List<String> command, long timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException ex) {
            return new AzResult(-1, false, String.valueOf(ex.getMessage()), true);
        }
        boolean[] gotToken = { false };
        StringBuilder errors = new StringBuilder();
        Thread out = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                gotToken[0] = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .trim().length() >= PLAUSIBLE_TOKEN_CHARS;
            } catch (IOException ignored) {
                // No output read means no token seen, which is the safe reading.
            }
        }, "ado-signin-token");
        out.setDaemon(true);
        out.start();
        Thread err = new Thread(() -> {
            try (InputStream in = proc.getErrorStream()) {
                errors.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Losing stderr only costs us the reason in the UNKNOWN sentence.
            }
        }, "ado-signin-error");
        err.setDaemon(true);
        err.start();
        try {
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return new AzResult(-1, false, "az hat nicht geantwortet.", false);
            }
            out.join(2000);
            err.join(2000);
            String stderr = errors.toString();
            boolean missing = stderr.toLowerCase(Locale.ROOT).contains("not recognized")
                || stderr.toLowerCase(Locale.ROOT).contains("nicht gefunden")
                || stderr.toLowerCase(Locale.ROOT).contains("not found");
            return new AzResult(proc.exitValue(), gotToken[0], stderr, missing);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            return new AzResult(-1, false, "Die Prüfung wurde unterbrochen.", false);
        }
    }

    /** Reads the login window's console output to EOF so it can never fill its pipe. */
    private static void drain(InputStream stream, String name) {
        Thread thread = new Thread(() -> {
            try (InputStream in = stream) {
                byte[] swallowed = in.readAllBytes();
                if (swallowed.length > 0 && LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "sign-in window said {0} bytes", swallowed.length);
                }
            } catch (IOException ignored) {
                // The window's own text is on the tester's screen; this copy is a courtesy.
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "(keine Meldung)";
        }
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return "(keine Meldung)";
    }
}
