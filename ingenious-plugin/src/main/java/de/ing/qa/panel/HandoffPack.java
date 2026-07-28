package de.ing.qa.panel;

import de.ing.qa.ado.AdoCache;
import de.ing.qa.studio.AdoRunWatcher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Hands a finished recording over — by calling the tool that already knows how.
 *
 * <p>This is the Studio half of issue
 * <a href="https://github.com/Wladefant/ing-qa-automation/issues/127">#127</a>. Everything that
 * makes a hand-off <em>safe</em> — leaving the saved browser session behind, leaving the
 * hundreds of megabytes of
 * {@code Results/} behind, writing the manifest that tells the engineer which ADO case, which
 * customer profile and which build — lives in {@code tools/handoff-pack.mjs}. A Fachbereich
 * colleague cannot run a Node CLI, so until now they could not hand over their own recording:
 * a team member had to do it for them, and the handout had to say <em>"do not zip the folder
 * yourself"</em> with three reasons why. A correct instruction, and an open tool defect.
 *
 * <p><b>Nothing here re-implements any of that.</b> {@code handoff-pack.mjs} is invoked as a
 * child process and its answer is used verbatim, exactly the way {@link
 * de.ing.qa.studio.AdoUpload} invokes {@code ado-upload.mjs}. Its rules were established
 * empirically — it performed a real round trip, packed on one machine and unpacked on another
 * with identical hashes and 42/42 steps green, and what must travel was found by deleting each
 * part and re-running (see
 * <a href="https://github.com/Wladefant/ing-qa-automation/blob/main/docs/reference/HANDOFF-TESTER-TO-ENGINEER.md">HANDOFF-TESTER-TO-ENGINEER.md</a>).
 * A second implementation would have to re-earn all of it.
 *
 * <p><b>There is no account-number refusal any more</b>
 * (<a href="https://github.com/Wladefant/ing-qa-automation/commit/52ce917">52ce917</a>). The
 * hand-off runs tester → automation engineer inside the organisation and the destination test
 * management system is internal too, so the package crosses no boundary — and the scan fired on
 * ADO test-case ids and on INGenious' own shipped sample data, which made the hand-off
 * impossible on every project we create. {@code pack} therefore no longer exits 2, and nothing
 * here maps that code any more. What still keeps an account number off a test case is
 * {@link de.ing.qa.studio.CustomerProfile}, which is a different question and untouched.
 *
 * <p>Never call {@link #pack()} on the Swing event dispatch thread: it walks the whole project
 * and hashes every file in it.
 */
final class HandoffPack {

    /** The packaging tool. Repo-relative, like every other tool the panels shell out to. */
    static final String TOOL_REL = "tools/handoff-pack.mjs";

    /** Where the finished package is written. Overridable so a harness can point it elsewhere. */
    static final String ENV_OUT = "ING_HANDOFF_OUT";

    /** The folder the tester is sent to. Plain, spellable, and the same one every time. */
    static final String OUT_FOLDER = "INGenious-Uebergabe";

    /** Walking and hashing a project is local work, but a big project is a lot of it. */
    private static final long TIMEOUT_SECONDS = 10 * 60;

    /** What became of one attempt. Every one of these is said out loud on screen. */
    enum Outcome {
        /** A package was written. {@link Result#zip()} names it. */
        OK,
        /** No INGenious project to package. */
        NO_PROJECT,
        /** {@code handoff-pack.mjs} is not on this machine. */
        NO_TOOL,
        /** Node is not on this machine, so the tool cannot run at all. */
        NO_NODE,
        /** The tool ran and did not finish, or finished badly. */
        FAILED
    }

    /**
     * One attempt, always populated, never an exception.
     *
     * @param outcome which of the five things happened
     * @param message the German sentence for the tester — what happened, and what to do
     * @param detail the tool's own words, for the tooltip and the log
     * @param zip the package, only on {@link Outcome#OK}
     */
    record Result(Outcome outcome, String message, String detail, Path zip) {

        boolean ok() {
            return outcome == Outcome.OK;
        }
    }

    private HandoffPack() {
    }

    // ------------------------------------------------------------------ what we package

    /**
     * The INGenious project Studio has open, or {@code null} when there is none.
     *
     * <p>Read through {@link AdoRunWatcher#resultsRoot()} rather than by a second copy of the
     * same reflection: that method already resolves {@code ING_INGENIOUS_PROJECT} first and the
     * open project's own location second, and {@code <project>/Results} has exactly one parent.
     */
    static Path projectDir() {
        Path results = AdoRunWatcher.resultsRoot();
        return results == null ? null : results.getParent();
    }

    /** {@code handoff-pack.mjs}, or {@code null} when this machine does not carry it. */
    static Path tool() {
        Path repo = AdoCache.repoRoot();
        if (repo == null) {
            return null;
        }
        Path tool = repo.resolve(TOOL_REL);
        return Files.isRegularFile(tool) ? tool : null;
    }

    /**
     * Where the package is written.
     *
     * <p>The tester has to find this file to send it, so the location is fixed and printed in
     * full rather than guessed at: {@code Desktop} is not a reliable path on a machine with
     * redirected folders — both {@code ~/Desktop} and {@code ~/OneDrive/Desktop} can exist, and
     * writing to the wrong one is worse than a plain path the message names outright.
     */
    static Path outDir() {
        String explicit = System.getenv(ENV_OUT);
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit.trim());
        }
        return Paths.get(System.getProperty("user.home", "."), OUT_FOLDER);
    }

    // ------------------------------------------------------------------ the attempt

    /**
     * Packages the open project. Blocking; never call this on the event dispatch thread.
     *
     * @return what happened, in words a tester can act on — never {@code null}, never a throw
     */
    static Result pack() {
        Path project = projectDir();
        if (project == null) {
            return new Result(Outcome.NO_PROJECT,
                "✖ KEIN Paket erstellt: bitte zuerst das Projekt öffnen, in dem Sie "
                    + "aufgenommen haben. Studio meldet zurzeit kein geöffnetes Projekt.",
                "AdoRunWatcher.resultsRoot() == null (" + AdoRunWatcher.ENV_PROJECT
                    + " ist nicht gesetzt und kein Projekt geöffnet).", null);
        }
        Path tool = tool();
        if (tool == null) {
            return new Result(Outcome.NO_TOOL,
                "✖ KEIN Paket erstellt: bitte den Ordner NICHT selbst zippen, sondern bei der "
                    + "Testautomatisierung melden. Das Abgabe-Werkzeug ist auf diesem Rechner "
                    + "nicht vorhanden.",
                TOOL_REL + " wurde nicht gefunden; " + AdoCache.ENV_REPO + " zeigt nicht auf "
                    + "das Repo-Verzeichnis.", null);
        }
        Path out = outDir();
        Path repo = tool.getParent() == null ? null : tool.getParent().getParent();

        List<String> command = List.of(node(), tool.toString(), "pack",
            "--project", project.toString(),
            "--out", out.toString());

        StringBuilder captured = new StringBuilder();
        int exit;
        try {
            exit = run(repo, command, captured);
        } catch (IOException ex) {
            // The one case that means the fix is incomplete on this device: no Node at all.
            return new Result(Outcome.NO_NODE,
                "✖ KEIN Paket erstellt: bitte den Ordner NICHT selbst zippen, sondern bei der "
                    + "Testautomatisierung melden. Auf diesem Rechner fehlt Node.js, das für "
                    + "die Abgabe gebraucht wird.",
                "node konnte nicht gestartet werden: " + ex.getMessage(), null);
        }
        String output = captured.toString();

        if (exit == 0) {
            return packed(output, out);
        }
        if (exit == -1) {
            return new Result(Outcome.FAILED,
                "✖ KEIN Paket erstellt: bitte bei der Testautomatisierung melden. Die Abgabe "
                    + "wurde nach " + (TIMEOUT_SECONDS / 60) + " Minuten abgebrochen.",
                lastLine(output), null);
        }
        return new Result(Outcome.FAILED,
            "✖ KEIN Paket erstellt: bitte den Ordner NICHT selbst zippen, sondern bei der "
                + "Testautomatisierung melden. Die Abgabe ist fehlgeschlagen ("
                + lastLine(output) + ").",
            "Exit " + exit + System.lineSeparator() + output, null);
    }

    /** Exit 0: the tool wrote a package and printed where. */
    private static Result packed(String output, Path out) {
        String zip = field(output, "zip");
        Path path = zip.isEmpty() ? null : Paths.get(zip);
        String name = path == null ? "" : String.valueOf(path.getFileName());
        String folder = path == null || path.getParent() == null
            ? out.toString() : path.getParent().toString();
        if (path == null) {
            // The tool exited 0 without naming a file. Claiming success here is the one thing
            // this panel may never do.
            return new Result(Outcome.FAILED,
                "✖ Es wurde kein Paket gemeldet, obwohl die Abgabe ohne Fehler endete. Bitte "
                    + "bei der Testautomatisierung melden.",
                output, null);
        }
        // Same rule as the refusal: what the tester must DO comes first, because the line
        // clips. The file name is part of the instruction — it is what they have to attach.
        return new Result(Outcome.OK,
            "✔ Fertig: bitte die Datei " + name + " an die Testautomatisierung senden. Sie "
                + "liegt im Ordner " + folder + ". Ihre gespeicherte Anmeldung und die "
                + "Ergebnis-Dateien sind absichtlich nicht enthalten.",
            output, path);
    }

    /** One of {@code pack}'s aligned report lines: {@code "  zip        C:\\…"}. */
    private static String field(String output, String name) {
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith(name + " ")) {
                return line.substring(name.length()).trim();
            }
        }
        return "";
    }

    private static String lastLine(String output) {
        if (output == null || output.isBlank()) {
            return "keine Ausgabe";
        }
        String[] lines = output.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].trim();
            }
        }
        return "keine Ausgabe";
    }

    // ---------------------------------------------------------------------- plumbing

    private static String node() {
        String explicit = System.getenv(AdoCache.ENV_NODE);
        return explicit == null || explicit.isBlank() ? "node" : explicit.trim();
    }

    /**
     * Runs the tool to completion, capturing stdout and stderr together — the refusal is
     * printed on stderr and is the single most important thing this method carries back.
     *
     * @return the exit code, or {@code -1} when it timed out or was interrupted
     * @throws IOException when the child could not be started at all, which on this device
     *     means Node is missing and is reported as such rather than as a failure
     */
    private static int run(Path workingDir, List<String> command, StringBuilder capture)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null && Files.isDirectory(workingDir)) {
            pb.directory(workingDir.toFile());
        }
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        // Drained on its own thread: a full pipe buffer would deadlock the child, and reading
        // to EOF here would block past the timeout.
        Thread drain = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                capture.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Losing the log only costs detail in the message.
            }
        }, "handoff-pack-output");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return -1;
            }
            drain.join(2000);
            return proc.exitValue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
