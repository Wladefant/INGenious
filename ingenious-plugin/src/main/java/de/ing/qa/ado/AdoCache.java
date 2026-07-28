package de.ing.qa.ado;

import de.ing.qa.studio.AdoSignIn;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Everything the panels know about ADO — which is: a file.
 *
 * <p><b>The panels never call ADO.</b> The Entra-bearer flow that actually works at
 * this bank (tenant-scoped {@code az login}, ~50-minute token cache, no PAT) exists
 * once, in {@code ing-qa-recorder/mvp/ado-automark.mjs}, and is used by
 * {@code tools/ado-testcases.mjs}. Re-implementing it in Java would mean maintaining
 * a second copy of the one flow known to survive Conditional Access. So the Node tool
 * writes a cache file and the panels read it; refreshing means shelling out to that
 * same tool.
 *
 * <p>Consequence, and it is a feature: the panels work offline. A tester machine with
 * no ADO reachability still shows the last snapshot.
 *
 * <p>Paths (all overridable, all with a documented default):
 *
 * <ul>
 *   <li>{@code ING_ADO_CACHE} — the cache written by {@code ado-testcases.mjs}.
 *       Default {@code %LOCALAPPDATA%\IngQaAutopilot\ado-testcases.json}, i.e. beside
 *       the existing token cache; {@code ~/.IngQaAutopilot/} where LOCALAPPDATA is unset.
 *   <li>{@code ING_TESTCASE_SELECTION} — where the chosen test-case id is written.
 *       Default {@code selected-testcase.json} next to the cache.
 *   <li>{@code ING_QA_REPO} — repo root, so "Aus ADO aktualisieren" can find
 *       {@code tools/ado-testcases.mjs}. Falls back to walking up from the working
 *       directory.
 *   <li>{@code ING_NODE} — node executable, default {@code node} from PATH.
 * </ul>
 */
public final class AdoCache {

    public static final String ENV_CACHE = "ING_ADO_CACHE";
    public static final String ENV_SELECTION = "ING_TESTCASE_SELECTION";
    public static final String ENV_REPO = "ING_QA_REPO";
    public static final String ENV_NODE = "ING_NODE";

    private static final String TOOL_REL = "tools/ado-testcases.mjs";
    /**
     * A refresh spawns node and hits ADO. It no longer triggers an interactive {@code az login}
     * down there: the sign-in is settled first, in a window the tester can see, and the child is
     * told not to open one (<a href="https://github.com/Wladefant/ing-qa-automation/issues/128">#128</a>).
     * The five minutes are now only ADO's own answer time.
     */
    private static final long REFRESH_TIMEOUT_SECONDS = 300;

    private AdoCache() {
    }

    /**
     * A cache read that always succeeds. Either {@code cases} carries the snapshot, or
     * {@code problem} carries a German sentence to put on screen — never an exception.
     *
     * <p>{@code org}/{@code project} are the ADO coordinates the cache was generated
     * against. They are the reason the panels can offer "In Azure DevOps öffnen" for a
     * cache written before the tool started storing per-case links.
     */
    public record Snapshot(List<AdoTestCase> cases, String generatedAt, Path source, String problem,
        String org, String project) {

        public boolean ok() {
            return problem == null;
        }

        private static Snapshot failed(Path source, String problem) {
            return new Snapshot(List.of(), "", source, problem, "", "");
        }
    }

    /** Outcome of a refresh attempt: a flag and a sentence fit for a status line. */
    public record RefreshResult(boolean ok, String message) {
    }

    // ------------------------------------------------------------------ paths

    public static Path cachePath() {
        String explicit = env(ENV_CACHE);
        if (explicit != null) {
            return Paths.get(explicit);
        }
        String local = env("LOCALAPPDATA");
        if (local != null) {
            return Paths.get(local, "IngQaAutopilot", "ado-testcases.json");
        }
        return Paths.get(System.getProperty("user.home", "."), ".IngQaAutopilot", "ado-testcases.json");
    }

    public static Path selectionPath() {
        String explicit = env(ENV_SELECTION);
        if (explicit != null) {
            return Paths.get(explicit);
        }
        Path cache = cachePath();
        Path dir = cache.getParent();
        return dir == null ? Paths.get("selected-testcase.json") : dir.resolve("selected-testcase.json");
    }

    /**
     * Repo root holding {@code tools/ado-testcases.mjs}: the env var if set, otherwise
     * the nearest ancestor of the working directory that contains the tool. Returns
     * null when neither finds it — the caller then says so in plain German rather than
     * spawning a process that cannot work.
     */
    public static Path repoRoot() {
        String explicit = env(ENV_REPO);
        if (explicit != null) {
            Path p = Paths.get(explicit);
            return Files.isRegularFile(p.resolve(TOOL_REL)) ? p : null;
        }
        Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int depth = 0; dir != null && depth < 8; depth++) {
            if (Files.isRegularFile(dir.resolve(TOOL_REL))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    // ------------------------------------------------------------------ reading

    /**
     * Last parse, keyed on file identity. The real cache is 13 MB / 6609 cases and the
     * overview panel re-reads it every time it becomes visible; without this, switching
     * to "Testfall-Übersicht" stalls for seconds and looks like nothing happened —
     * exactly the impression we are fixing. Invalidated by size or mtime, so a refresh
     * is still picked up.
     */
    private static Path memoPath;
    private static long memoModified;
    private static long memoSize;
    private static Snapshot memo;

    private static synchronized Snapshot memoized(Path path) {
        try {
            if (memo != null && path.equals(memoPath)
                && Files.getLastModifiedTime(path).toMillis() == memoModified
                && Files.size(path) == memoSize) {
                return memo;
            }
        } catch (IOException ignored) {
            // Cannot stat it — fall through and read it properly.
        }
        return null;
    }

    private static synchronized void remember(Path path, Snapshot snapshot) {
        try {
            memoPath = path;
            memoModified = Files.getLastModifiedTime(path).toMillis();
            memoSize = Files.size(path);
            memo = snapshot;
        } catch (IOException ignored) {
            memo = null;
        }
    }

    /** Reads the cache. Every failure mode becomes a German sentence, not a throw. */
    public static Snapshot load() {
        Path path = cachePath();
        Snapshot cached = memoized(path);
        if (cached != null) {
            return cached;
        }
        if (!Files.isRegularFile(path)) {
            return Snapshot.failed(path,
                "Noch keine ADO-Testfaelle vorhanden. Bitte auf \"Aus ADO aktualisieren\" klicken "
                    + "(oder " + TOOL_REL + " ausfuehren). Erwartete Datei: " + path);
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return Snapshot.failed(path, "Testfall-Datei konnte nicht gelesen werden: " + ex.getMessage());
        }
        try {
            Object root = Json.parse(text);
            if (!(root instanceof Map<?, ?> map)) {
                return Snapshot.failed(path, "Testfall-Datei hat ein unerwartetes Format: " + path);
            }
            Object list = map.get("testCases");
            if (!(list instanceof List<?> items)) {
                return Snapshot.failed(path, "Testfall-Datei enthaelt keine Testfaelle: " + path);
            }
            String org = str(map.get("org"));
            String project = str(map.get("project"));
            List<AdoTestCase> cases = new ArrayList<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?> m) {
                    cases.add(toCase(m, org, project));
                }
            }
            if (cases.isEmpty()) {
                return Snapshot.failed(path, "Testfall-Datei enthaelt keine Testfaelle: " + path);
            }
            Snapshot snapshot = new Snapshot(
                Collections.unmodifiableList(cases), str(map.get("generatedAt")), path, null, org, project);
            remember(path, snapshot);
            return snapshot;
        } catch (RuntimeException ex) {
            // Includes Json.JsonException: a half-written or corrupted cache.
            return Snapshot.failed(path, "Testfall-Datei konnte nicht gelesen werden: " + ex.getMessage());
        }
    }

    private static AdoTestCase toCase(Map<?, ?> m, String org, String project) {
        List<String> steps = new ArrayList<>();
        if (m.get("steps") instanceof List<?> raw) {
            for (Object s : raw) {
                String v = str(s);
                if (!v.isBlank()) {
                    steps.add(v);
                }
            }
        }
        String field = str(m.get("preconditionField"));
        String adoId = str(m.get("adoId"));
        String url = str(m.get("url"));
        AdoTestCase.UrlSource source = null;
        if (!url.isBlank()) {
            source = AdoTestCase.UrlSource.ADO;
        } else {
            url = constructWebUrl(org, project, adoId);
            if (url != null) {
                source = AdoTestCase.UrlSource.KONSTRUIERT;
            }
        }
        return new AdoTestCase(
            adoId,
            str(m.get("title")),
            str(m.get("suiteName")),
            str(m.get("state")),
            str(m.get("outcome")),
            str(m.get("description")),
            str(m.get("preconditions")),
            field.isBlank() ? null : field,
            List.copyOf(steps),
            url,
            source);
    }

    /**
     * Fallback link for a cache written before the tool stored ADO's own href.
     *
     * <p>Verified against the real plan on 2026-07-27: ADO's {@code _links.html.href}
     * uses the project GUID, this one uses the project name — and
     * {@code https://dev.azure.com/beispiel-org/BeispielProjekt/_workitems/edit/4502263}
     * answered HTTP 200, so both routes resolve. Returns null when org, project or id
     * is missing: an action that cannot be built must be disabled, not guessed.
     */
    static String constructWebUrl(String org, String project, String adoId) {
        if (org == null || org.isBlank() || project == null || project.isBlank()
            || adoId == null || adoId.isBlank()) {
            return null;
        }
        return "https://dev.azure.com/" + org.trim() + "/" + project.trim()
            + "/_workitems/edit/" + adoId.trim();
    }

    private static String str(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof Double d && d == Math.floor(d) && !d.isInfinite()) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(o);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    // ------------------------------------------------------------------ selection

    /**
     * Persists the chosen test case so the rest of the tester flow can pick it up.
     * Written whole to a temp file and moved into place: the overview panel may be
     * reading it at the same moment.
     *
     * @return the file it was written to, so the caller can name it on screen
     * @throws IOException when the write did not happen — callers must SAY so; a
     *     selection that silently did not persist is indistinguishable from a dead button
     */
    public static Path writeSelection(AdoTestCase testCase) throws IOException {
        Path path = selectionPath();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = "{\n"
            + "  \"_note\": \"Vom INGenious-Studio-Panel 'Testfall waehlen' geschrieben. "
            + "Quelle der Testfaelle: " + Json.escape(cachePath().toString()) + "\",\n"
            + "  \"adoId\": \"" + Json.escape(testCase.adoId()) + "\",\n"
            + "  \"title\": \"" + Json.escape(testCase.title()) + "\",\n"
            + "  \"suiteName\": \"" + Json.escape(testCase.suiteName()) + "\",\n"
            + "  \"url\": \"" + Json.escape(testCase.webUrl() == null ? "" : testCase.webUrl()) + "\",\n"
            + "  \"chosenAt\": \"" + Instant.now() + "\",\n"
            + "  \"source\": \"TestCaseChooserPanel\"\n"
            + "}\n";
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        return path;
    }

    /**
     * The currently chosen ADO id, or null when nothing has been chosen yet.
     *
     * <p>{@code selectionPath()} is inside the guard on purpose: an illegal
     * {@code ING_TESTCASE_SELECTION} makes {@code Paths.get} throw the UNCHECKED
     * {@link java.nio.file.InvalidPathException}. It used to escape from here into the
     * panel's {@code SwingWorker.done()}, which aborted the whole load — the panel then
     * showed neither test cases nor an error. "Nothing chosen" is the right answer to a
     * selection file we cannot even name.
     */
    public static String readSelectedId() {
        try {
            Path path = selectionPath();
            if (!Files.isRegularFile(path)) {
                return null;
            }
            Object root = Json.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (root instanceof Map<?, ?> map) {
                String id = str(map.get("adoId"));
                return id.isBlank() ? null : id;
            }
        } catch (IOException | RuntimeException ignored) {
            // A missing, unnameable or damaged selection means "nothing chosen".
        }
        return null;
    }

    // ------------------------------------------------------------------ refresh

    /**
     * Re-runs {@code tools/ado-testcases.mjs} to rewrite the cache. Blocking on
     * purpose — callers run it on a {@link javax.swing.SwingWorker}, never on the EDT.
     *
     * <p>Offline, without node, or without the repo, this returns {@code ok=false} and
     * a sentence a tester can act on. That is the normal case on a machine that cannot
     * reach ADO, not an error worth a stack trace.
     *
     * <p><b>The sign-in comes first.</b> {@code ado-testcases.mjs} imports the same token
     * function the marker uses, so an expired Entra token used to make it open an
     * {@code az login} on this process's pipes — five minutes of "wird aktualisiert…" and then
     * a failure the tester could not explain
     * (<a href="https://github.com/Wladefant/ing-qa-automation/issues/128">#128</a>). Now
     * {@link de.ing.qa.studio.AdoSignIn} settles that here, in a window, before the child is
     * started; the reach across into the studio package is deliberate, because a second probe
     * would be a second place for "signed in" to mean something slightly different.
     */
    public static RefreshResult refresh() {
        Path repo = repoRoot();
        if (repo == null) {
            return new RefreshResult(false,
                "Aktualisieren nicht moeglich: " + TOOL_REL + " wurde nicht gefunden. "
                    + "Bitte die Umgebungsvariable " + ENV_REPO + " auf das Repo-Verzeichnis setzen.");
        }
        AdoSignIn.Check auth = AdoSignIn.check();
        if (auth.state() == AdoSignIn.State.SIGN_IN_REQUIRED) {
            auth = AdoSignIn.signIn();
            if (auth.state() != AdoSignIn.State.OK) {
                return new RefreshResult(false, "Anmeldung bei Azure DevOps noetig: "
                    + auth.message() + " Es wurden keine Testfaelle aktualisiert.");
            }
        }
        String node = env(ENV_NODE) != null ? env(ENV_NODE) : "node";
        Path cache = cachePath();
        ProcessBuilder pb = new ProcessBuilder(
            node, repo.resolve(TOOL_REL).toString(), "--cache", cache.toString());
        pb.directory(repo.toFile());
        // Set only when the sign-in was actually established: under UNKNOWN the child keeps the
        // environment — and the behaviour — it has always had.
        if (auth.state() == AdoSignIn.State.OK) {
            pb.environment().put("ADO_NONINTERACTIVE", "1");
        }
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            // Drained on a separate thread: reading to EOF on this one would block past
            // the timeout, and a full pipe buffer would deadlock the child anyway.
            StringBuilder output = new StringBuilder();
            Thread drain = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    output.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // Losing the log only costs detail in the status line.
                }
            }, "ado-refresh-output");
            drain.setDaemon(true);
            drain.start();
            if (!proc.waitFor(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return new RefreshResult(false,
                    "Aktualisieren abgebrochen: ADO hat nicht innerhalb von "
                        + REFRESH_TIMEOUT_SECONDS + " Sekunden geantwortet.");
            }
            drain.join(2000);
            String log = output.toString().trim();
            if (proc.exitValue() != 0) {
                return new RefreshResult(false, "Aktualisieren fehlgeschlagen: " + lastLine(log));
            }
            return new RefreshResult(true, "Testfaelle aus ADO aktualisiert. " + lastLine(log));
        } catch (IOException ex) {
            return new RefreshResult(false,
                "Aktualisieren fehlgeschlagen: node konnte nicht gestartet werden (" + ex.getMessage()
                    + "). Bitte Node.js installieren oder " + ENV_NODE + " setzen.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new RefreshResult(false, "Aktualisieren wurde unterbrochen.");
        }
    }

    /** Tool output can be many lines; a status line has room for the last meaningful one. */
    private static String lastLine(String output) {
        if (output == null || output.isBlank()) {
            return "(keine Ausgabe)";
        }
        String[] lines = output.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].trim();
            }
        }
        return "(keine Ausgabe)";
    }
}
