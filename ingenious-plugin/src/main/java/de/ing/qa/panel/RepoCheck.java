package de.ing.qa.panel;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Whether the repository this plugin shells out to is actually the one it was built against —
 * asked <em>before</em> a tester presses a button, rather than discovered afterwards from a
 * greyed control.
 *
 * <h2>The failure this exists to make impossible</h2>
 *
 * <p>On 2026-07-28 a machine's checkout of this repository was a month old. Five of the tools
 * the panels start as child processes — {@code selector-uniqueness.mjs}, {@code handoff-pack.mjs},
 * {@code parse-report.mjs}, {@code ado-upload.mjs} and {@code ado-testcases.mjs} — did not exist
 * on it. The plugin JAR beside them had been rebuilt and redeployed many times; the repository
 * had not, and <b>nothing anywhere said so</b>. The observable symptom was a single greyed
 * button, whose own honest sentence — <i>"auf diesem Rechner nicht eingerichtet"</i> — reads as
 * "this feature was never set up here" and not as "your checkout is a month behind". The person
 * testing concluded, reasonably, that the feature was broken.
 *
 * <p>Three things can drift apart independently on a tester machine: this repository, the built
 * Studio, and the plugin JAR inside that Studio. Two of the three are visible from inside the
 * plugin, and those two are what this class reports.
 *
 * <h2>Why it cannot cry wolf</h2>
 *
 * <p>A stale warning that appears when nothing is wrong is ignored within a week, and is then
 * worse than no warning at all. So every rule here is a fact a machine is certain of, and
 * everything else is <b>silence plus a written reason</b> ({@link State#UNKNOWN}), never a guess
 * shown to a tester:
 *
 * <ul>
 *   <li><b>No dates. Anywhere.</b> Nothing in this class reads a modification time, compares a
 *       build date with a file date, or has an age threshold. "Older than" is not a question
 *       asked of a clock here — the two questions are "is the file there" and "does this
 *       checkout contain that commit", and both have exact answers.
 *   <li><b>A missing tool is proof.</b> {@link #REQUIRED} is the list of scripts this JAR really
 *       starts as child processes; two of the entries are the very constants the shell-out uses
 *       ({@link SelectorCheck#TOOL_REL}, {@link HandoffPack#TOOL_REL}). If one is absent, the
 *       feature behind it cannot work — that is not an inference.
 *   <li><b>The commit rule only fires on a published commit.</b> The build stamps
 *       {@value #MANIFEST_ATTR} into the JAR manifest, and stamps a real commit id <em>only</em>
 *       when the working tree was clean and that commit was already reachable from a remote (see
 *       {@code tools/ing-update.ps1}). So "this checkout does not have that commit" can only mean
 *       "this checkout has not been updated" — never "the person who built it had not pushed
 *       yet". Any other stamp value is a word, not a hex id, and is treated as UNKNOWN with the
 *       word repeated in the detail.
 *   <li><b>Present is enough.</b> The commit test asks git whether the object <em>exists</em>
 *       here, not whether it is on the current branch. A fetched-but-not-merged commit therefore
 *       says nothing, which is the conservative direction on purpose: this class would rather
 *       miss a stale checkout than invent one.
 *   <li><b>No git, no verdict.</b> If git is not on the machine, or refuses, or takes longer
 *       than {@link #GIT_TIMEOUT_SECONDS}, the answer is UNKNOWN. It is never read as "behind".
 * </ul>
 *
 * <p>The consequence is deliberate and worth stating plainly: <b>this check is quiet on a
 * healthy machine and quiet on a machine it cannot judge.</b> The only three sentences it can
 * put on screen each name something that is certainly wrong right now.
 *
 * <h2>Two phases, because one of them starts a process</h2>
 *
 * <p>{@link #files()} is a handful of {@code Files.isRegularFile} calls and is safe on the event
 * dispatch thread. {@link #history(Result)} starts git and must not be. The panel therefore
 * paints the file answer immediately and upgrades it from a background thread — a tester sees
 * the certain half at once rather than an empty header for a second.
 */
final class RepoCheck {

    /** Repo root, as the panels' own tools resolve it. Same name {@code AdoCache} uses. */
    static final String ENV_REPO = "ING_QA_REPO";

    /** JAR manifest attribute carrying the commit this plugin was built from. */
    static final String MANIFEST_ATTR = "Ing-Qa-Repo-Commit";

    /**
     * Overrides the manifest stamp.
     *
     * <p>A system property and not only a manifest entry because the harness has to be able to
     * put this class in every one of its states, and it cannot rebuild a JAR to do it. A switch
     * no test can flip is a switch nobody has seen work.
     */
    static final String PROP_COMMIT = "ing.qa.plugin.commit";

    /** git is asked one cheap question; a git that has not answered by then is not an answer. */
    private static final long GIT_TIMEOUT_SECONDS = 20;

    /**
     * One script this plugin starts as a child process, and the tester-facing feature that dies
     * without it.
     *
     * @param rel where it sits under the repository root, in repository notation
     * @param feature what a tester loses when it is absent, in the words the panel uses
     */
    record Tool(String rel, String feature) {
    }

    /**
     * Everything this JAR shells out to.
     *
     * <p>Two of the five are taken from the constants the shell-out itself uses, so they cannot
     * drift. The other three name files whose constants live in other packages
     * ({@code de.ing.qa.ado.AdoCache}, {@code de.ing.qa.studio.AdoUpload}) and are therefore
     * repeated here as literals — a rename there and not here would make this check look for a
     * file nobody uses and report a healthy machine as broken, which is the exact disease this
     * class exists to cure. {@code RepoCheckHarness} reads those three constants back out of
     * their own classes by reflection and fails if they and this list have gone out of step.
     */
    static final List<Tool> REQUIRED = List.of(
        new Tool(SelectorCheck.TOOL_REL, GuidedFlowPanel.BTN_CHECK),
        new Tool(HandoffPack.TOOL_REL, GuidedFlowPanel.BTN_HANDOFF),
        // de.ing.qa.ado.AdoCache.TOOL_REL — "Aus ADO aktualisieren"
        new Tool("tools/ado-testcases.mjs", "Testfälle aus Azure DevOps holen"),
        // de.ing.qa.studio.AdoUpload.PARSE_REL
        new Tool("tools/parse-report.mjs", "Testergebnis nach Azure DevOps melden"),
        // de.ing.qa.studio.AdoUpload.UPLOAD_REL
        new Tool("ing-qa-recorder/mvp/ado-upload.mjs", "Testergebnis nach Azure DevOps melden"));

    /** What was found. Only the middle three are ever said out loud. */
    enum State {
        /** Repository found, every tool present, and nothing contradicts the build stamp. */
        OK,
        /** No repository is configured on this machine at all. Certain. */
        NOT_CONFIGURED,
        /** The repository is there and at least one required tool is not. Certain. */
        TOOLS_MISSING,
        /** git says this checkout does not contain the published commit the plugin was built
         *  from. Certain, and it means exactly one thing: nobody has updated it. */
        BEHIND,
        /**
         * Not decidable here — no stamp, no git, or git would not answer. <b>Never shown.</b>
         * The reason is carried in {@link Result#detail()} so a support request can quote it.
         */
        UNKNOWN
    }

    /**
     * One answer, always populated, never an exception.
     *
     * @param state which of the five things is true
     * @param message the German sentence for the tester, or {@code ""} when there is nothing to
     *     say. Action first — the line clips rather than wraps at a narrow window width
     * @param detail the technical wording for the tooltip, the log and a support request; always
     *     present, including for {@link State#OK} and {@link State#UNKNOWN}
     * @param missing the tools that are absent, in {@link #REQUIRED} order; empty otherwise
     * @param repo the repository this answer is about, or {@code null} when none was found
     */
    record Result(State state, String message, String detail, List<String> missing, Path repo) {

        /** Whether there is something certain enough to put in front of a tester. */
        boolean speak() {
            return state == State.NOT_CONFIGURED || state == State.TOOLS_MISSING
                || state == State.BEHIND;
        }
    }

    private RepoCheck() {
    }

    // ------------------------------------------------------------------ where the repo is

    /**
     * The repository this machine is configured to use, or {@code null}.
     *
     * <p>Deliberately <b>not</b> {@code AdoCache.repoRoot()}, and the difference is the whole
     * point of this class. That method returns null both when nothing is configured and when the
     * configured folder no longer carries {@code tools/ado-testcases.mjs} — so on the very
     * machine this class was written for, a month-old checkout that had lost that file would have
     * been reported as "nothing is set up here". Recognising the folder must not depend on any
     * file that can go missing, so it depends on the shape of the checkout instead.
     */
    static Path repo() {
        String explicit = System.getenv(ENV_REPO);
        if (explicit != null && !explicit.isBlank()) {
            try {
                return Paths.get(explicit.trim());
            } catch (InvalidPathException ex) {
                // A value that is not even a path names nothing; fall through to the walk.
                return null;
            }
        }
        Path dir;
        try {
            dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        } catch (InvalidPathException ex) {
            return null;
        }
        for (int depth = 0; dir != null && depth < 8; depth++) {
            if (Files.isDirectory(dir.resolve("tools"))
                && Files.isDirectory(dir.resolve("ingenious-plugin"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    // ------------------------------------------------------------------ phase 1: the files

    /**
     * What is on this disk. No subprocess, no clock, safe on the event dispatch thread.
     *
     * @return {@link State#NOT_CONFIGURED}, {@link State#TOOLS_MISSING} or {@link State#OK}
     */
    static Result files() {
        Path repo = repo();
        if (repo == null) {
            return new Result(State.NOT_CONFIGURED,
                "⚠ Nicht eingerichtet: bitte bei der Testautomatisierung melden. \""
                    + GuidedFlowPanel.BTN_CHECK + "\" und \"" + GuidedFlowPanel.BTN_HANDOFF
                    + "\" können auf diesem Rechner nicht arbeiten.",
                ENV_REPO + " ist nicht gesetzt, und oberhalb des Arbeitsverzeichnisses wurde kein "
                    + "Verzeichnis mit tools/ und ingenious-plugin/ gefunden.",
                names(REQUIRED), null);
        }
        if (!Files.isDirectory(repo)) {
            return new Result(State.NOT_CONFIGURED,
                "⚠ Nicht eingerichtet: bitte bei der Testautomatisierung melden. \""
                    + GuidedFlowPanel.BTN_CHECK + "\" und \"" + GuidedFlowPanel.BTN_HANDOFF
                    + "\" können auf diesem Rechner nicht arbeiten.",
                ENV_REPO + " zeigt auf " + repo + " — dieses Verzeichnis gibt es nicht.",
                names(REQUIRED), repo);
        }

        List<String> missing = new ArrayList<>();
        for (Tool tool : REQUIRED) {
            if (!Files.isRegularFile(repo.resolve(tool.rel().replace('/', File.separatorChar)))) {
                missing.add(tool.rel());
            }
        }
        if (missing.isEmpty()) {
            return new Result(State.OK, "",
                "Alle " + REQUIRED.size() + " Werkzeuge vorhanden unter " + repo + ".",
                List.of(), repo);
        }

        // WHICH features are gone, before HOW MANY files are: a tester acts on the first and
        // reports the second. The names of the files are in the tooltip, not in this sentence —
        // a path in a sentence a tester reads is a path they cannot do anything with.
        List<String> features = new ArrayList<>();
        for (Tool tool : REQUIRED) {
            if (missing.contains(tool.rel()) && !features.contains(tool.feature())) {
                features.add(tool.feature());
            }
        }
        return new Result(State.TOOLS_MISSING,
            "⚠ Nicht auf dem neuesten Stand: bitte \"ING aktualisieren\" ausführen und Studio neu "
                + "starten. Bis dahin fehlt: " + String.join(", ", features) + ".",
            missing.size() + " von " + REQUIRED.size() + " Werkzeugen fehlen unter " + repo + ": "
                + String.join(", ", missing),
            List.copyOf(missing), repo);
    }

    // ------------------------------------------------------------------ phase 2: the commit

    /**
     * Asks git whether this checkout contains the commit the plugin was built from.
     *
     * <p>Starts a child process; never call this on the event dispatch thread.
     *
     * @param filesResult the answer from {@link #files()}. Anything other than {@link State#OK}
     *     is returned unchanged — a machine that is already missing tools has been told the
     *     louder and more certain of the two things, and a second warning under it would only
     *     compete with the first
     * @return the same result, or a {@link State#BEHIND} / {@link State#UNKNOWN} one
     */
    static Result history(Result filesResult) {
        if (filesResult == null || filesResult.state() != State.OK) {
            return filesResult;
        }
        Path repo = filesResult.repo();
        String stamp = builtFrom();
        if (stamp == null || stamp.isBlank()) {
            return unknown(filesResult, "Dieses Plugin trägt keine Herkunftsangabe (Manifest-"
                + "Eintrag " + MANIFEST_ATTR + " fehlt), also lässt sich der Stand des "
                + "Arbeitsverzeichnisses hier nicht beurteilen.");
        }
        if (!stamp.matches("[0-9a-f]{40}")) {
            // The build writes a word instead of an id when it refuses to make a claim — a dirty
            // working tree, or a commit that is in no repository yet. Repeat the word rather
            // than paraphrase it: it is the build's own reason and it belongs in a bug report.
            return unknown(filesResult, "Dieses Plugin wurde ohne beurteilbare Herkunft gebaut ("
                + MANIFEST_ATTR + "=" + stamp + "), also wird der Stand nicht bewertet.");
        }

        // NOT `<sha>^{commit}`, and that is a measurement rather than a preference. Measured on
        // git 2.49 (Windows), asking for the peeled form:
        //
        //   object present     -> 0
        //   object ABSENT      -> 128   "fatal: Not a valid object name"
        //   not a repository   -> 128   "fatal: not a git repository"
        //
        // Two completely different situations behind one code, so the peeled form cannot tell
        // "this checkout has not been updated" from "this folder is not a checkout at all" —
        // and this class would have shouted the first at every machine that is the second.
        // Unpeeled, the same three cases answer 0, 1 and 128: exactly the split this needs, so
        // only git's own "no such object" can produce a warning.
        List<String> command = List.of("git", "-C", repo.toString(), "cat-file", "-e", stamp);
        int exit;
        try {
            exit = run(command);
        } catch (IOException ex) {
            return unknown(filesResult, "git ist auf diesem Rechner nicht startbar ("
                + ex.getMessage() + "), also wurde der Stand nicht geprüft.");
        }
        if (exit == 0) {
            return new Result(State.OK, "",
                filesResult.detail() + " Der Stand, aus dem dieses Plugin gebaut wurde ("
                    + stamp.substring(0, 8) + "), liegt hier vor.",
                List.of(), repo);
        }
        if (exit != 1) {
            // 1 is git's own "no such object" and the ONLY code that may produce a warning.
            // Everything else — 128 (this is not a checkout), -1 (timed out), anything a future
            // git invents — is a failure of the question, not an answer to it.
            return unknown(filesResult, "git konnte die Frage nicht beantworten (Rückgabewert "
                + exit + "), also wurde der Stand nicht bewertet.");
        }
        return new Result(State.BEHIND,
            "⚠ Nicht auf dem neuesten Stand: bitte \"ING aktualisieren\" ausführen und Studio neu "
                + "starten. Die Werkzeuge auf diesem Rechner sind älter als dieses Plugin.",
            "Das Plugin wurde aus Stand " + stamp + " gebaut; dieser Stand ist in " + repo
                + " nicht vorhanden. Der Stand war beim Bauen bereits veröffentlicht, also fehlt "
                + "hier eine Aktualisierung.",
            List.of(), repo);
    }

    /** The whole answer, in the order the panel wants it. Blocking; never on the EDT. */
    static Result full() {
        return history(files());
    }

    private static Result unknown(Result from, String why) {
        return new Result(State.UNKNOWN, "", from.detail() + " " + why, List.of(), from.repo());
    }

    // ------------------------------------------------------------------ the build stamp

    /**
     * The commit this plugin was built from, as the build wrote it down — or {@code null}.
     *
     * <p>System property first so the harness can put this class in each of its states without
     * building a JAR; then the manifest of the JAR this class was loaded from. Running from a
     * classes directory — every harness, and every developer — there is no manifest, and the
     * honest answer is that nothing is known.
     */
    static String builtFrom() {
        String override = System.getProperty(PROP_COMMIT);
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        try {
            var source = RepoCheck.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            URI uri = source.getLocation().toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            File file = new File(uri);
            if (!file.isFile()) {
                return null;
            }
            try (JarFile jar = new JarFile(file)) {
                Manifest manifest = jar.getManifest();
                if (manifest == null) {
                    return null;
                }
                return manifest.getMainAttributes().getValue(MANIFEST_ATTR);
            }
        } catch (Exception ex) {
            // A JAR we cannot open costs a check, never a verdict.
            return null;
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static List<String> names(List<Tool> tools) {
        List<String> out = new ArrayList<>();
        for (Tool tool : tools) {
            out.add(tool.rel());
        }
        return List.copyOf(out);
    }

    /**
     * Runs one short git command to completion.
     *
     * @return its exit code, or {@code -1} when it timed out or was interrupted
     * @throws IOException when git could not be started at all
     */
    private static int run(List<String> command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        Thread drain = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                // Read to EOF and discard: a full pipe buffer would block the child forever, and
                // git's answer here is its exit code, not its output.
                byte[] buffer = new byte[4096];
                while (in.read(buffer) >= 0) {
                    // discard
                }
            } catch (IOException ignored) {
                // Nothing here needs the text.
            }
        }, "repo-check-git");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
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
