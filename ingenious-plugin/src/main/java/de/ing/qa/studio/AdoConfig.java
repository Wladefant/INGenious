package de.ing.qa.studio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Azure DevOps this installation belongs to — read, never compiled in.
 *
 * <p>The organisation, project, test plan and Entra tenant are <em>site</em> configuration.
 * This source is public, so none of them may appear in it: a repository that names the
 * organisation has published it whether or not anybody meant to. They come from one file,
 * written once by whoever sets the machine up:
 *
 * <pre>
 *   %LOCALAPPDATA%\IngQaAutopilot\ado-config.json      (Windows)
 *   ~/.IngQaAutopilot/ado-config.json                  (anywhere else)
 *
 *   { "org": "...", "project": "...", "planId": 1234567, "tenantId": "&lt;guid&gt;" }
 * </pre>
 *
 * <p>Byte-identical to {@code CONFIG_FILE} in {@code ing-qa-recorder/mvp/ado-automark.mjs}:
 * one file, both languages, so the panel and the tools it starts can never disagree about
 * which Azure DevOps they are talking to. See {@code tools/README-ado-config.md}.
 *
 * <p><b>No defaults, on purpose.</b> A default would either be somebody's real organisation
 * (which is the thing being avoided) or a wrong one, and a wrong one fails against a real
 * Azure DevOps with a 404 that reads like a broken test case rather than a missing setting.
 * {@link #require} therefore throws with the name of the setting and the path of the file.
 *
 * <p>Deliberately hand-parsed. The plugin has exactly one compile dependency — the Studio's
 * own API, {@code provided} — and a JSON library added for four flat string keys would have
 * to be shaded into the JAR that Studio loads. The file is written by our own install, and a
 * value this parser cannot read is reported as missing rather than guessed.
 */
public final class AdoConfig {

    /** Environment variable that overrides {@code org}; same name the Node tools read. */
    public static final String ENV_ORG = "ADO_ORG";
    /** Environment variable that overrides {@code project}. */
    public static final String ENV_PROJECT = "ADO_PROJECT";
    /** Environment variable that overrides {@code planId}. */
    public static final String ENV_PLAN = "ADO_TEST_PLAN_ID";
    /** Environment variable that overrides {@code tenantId}. */
    public static final String ENV_TENANT = "ADO_TENANT_ID";

    /** Overrides the whole file's location; for tests, and for an install that keeps it elsewhere. */
    public static final String ENV_FILE = "ING_ADO_CONFIG";

    private AdoConfig() {
    }

    /** Where the settings are read from. Mirrors {@code CONFIG_FILE} in {@code ado-automark.mjs}. */
    public static Path configPath() {
        String explicit = System.getenv(ENV_FILE);
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit.trim());
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Paths.get(local.trim(), "IngQaAutopilot", "ado-config.json");
        }
        return Paths.get(System.getProperty("user.home", "."), ".IngQaAutopilot", "ado-config.json");
    }

    /**
     * One setting, or empty — never a guess.
     *
     * @param env environment variable that wins if set
     * @param key key in {@code ado-config.json}
     * @return the configured value with surrounding whitespace removed, or empty if neither
     *         the environment nor the file carries it
     */
    public static java.util.Optional<String> find(String env, String key) {
        String fromEnv = System.getenv(env);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return java.util.Optional.of(fromEnv.trim());
        }
        String fromFile = readKey(configPath(), key);
        if (fromFile != null && !fromFile.isBlank()) {
            return java.util.Optional.of(fromFile.trim());
        }
        return java.util.Optional.empty();
    }

    /**
     * One setting, or an exception naming it.
     *
     * @param env environment variable that wins if set
     * @param key key in {@code ado-config.json}
     * @return the configured value
     * @throws IllegalStateException with the German sentence to put on screen unchanged
     */
    public static String require(String env, String key) {
        return find(env, key).orElseThrow(() -> new IllegalStateException(missingMessage(env, key)));
    }

    /** The Entra tenant an {@code az login} must be scoped to. */
    public static String tenantId() {
        return require(ENV_TENANT, "tenantId");
    }

    /** The Azure DevOps organisation, or empty when this machine has not been set up. */
    public static java.util.Optional<String> org() {
        return find(ENV_ORG, "org");
    }

    /** The Azure DevOps project, or empty when this machine has not been set up. */
    public static java.util.Optional<String> project() {
        return find(ENV_PROJECT, "project");
    }

    /** The Azure DevOps test plan, or empty when this machine has not been set up. */
    public static java.util.Optional<String> planId() {
        return find(ENV_PLAN, "planId");
    }

    /**
     * What to say when a setting is missing: what is missing, where to put it, and what to read.
     *
     * @param env the environment variable that would also have supplied it
     * @param key the key in the file
     * @return a sentence in the tester's language, with no value in it
     */
    public static String missingMessage(String env, String key) {
        return "Nicht eingerichtet: " + key + " fehlt. Bitte " + configPath()
            + " anlegen (Schluessel \"" + key + "\") oder die Umgebungsvariable " + env
            + " setzen. Siehe tools/README-ado-config.md.";
    }

    // ------------------------------------------------------------------ the parser

    /**
     * The value of one flat key of a small JSON object, or null.
     *
     * <p>Accepts a quoted string or a bare number, which is every shape our own install
     * writes. Anything else — nesting, arrays, a missing file, an unreadable one — is
     * reported as absent, and absent is a case every caller already handles.
     */
    static String readKey(Path file, String key) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        Matcher m = Pattern.compile(
            "\"" + Pattern.quote(key) + "\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9]+(?:\\.[0-9]+)?))",
            Pattern.CASE_INSENSITIVE).matcher(text);
        if (!m.find()) {
            return null;
        }
        String quoted = m.group(1);
        if (quoted != null) {
            return quoted;
        }
        String number = m.group(2);
        // "1234567.0" would be a legal JSON number and an illegal plan id; keep the integer part.
        int dot = number.indexOf('.');
        return dot < 0 ? number : number.substring(0, dot);
    }

    /** Lower-cased, for messages that must not depend on the machine's locale. */
    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
