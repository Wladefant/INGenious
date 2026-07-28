package de.ing.qa.panel;

import de.ing.qa.studio.SelectedTestCase;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Which address the recorder will open, where that address came from, and whether a typed one
 * may be stored at all.
 *
 * <p><b>Why this exists.</b> The recorder's start address is a project-level property,
 * {@code StartUrl} in {@code Settings/RecorderSettings.Properties}, and nothing in the product
 * writes it: {@code RecorderSettings.setStartUrl} has no caller anywhere in the core, and
 * {@code ProjectSettings.save()} does not even list the recorder settings among the things it
 * saves. So on every fresh install the recorder opens a blank page, and the only way out was
 * to hand-edit a properties file — which is not something a tester can be asked to do. The
 * tester handout carried a sentence telling them to report it instead, i.e. to report a
 * problem nobody in the room could fix. This class is the other half of removing that
 * sentence: the panel can now set the address itself.
 *
 * <p><b>Two addresses, and they must never be confused.</b> The core prefers a plugin-supplied
 * per-test-case address ({@code RecordingTarget.getStartUrl()}) over the project setting — see
 * {@code TestCaseComponent.resolveRecordingStartUrl}. A tester who cannot tell "this test case
 * brings its own address" apart from "the project is configured like this" will change the
 * wrong one and then report that the change had no effect. So {@link #effective()} answers
 * both questions in one object: what will open, and which of the two decided it.
 *
 * <p><b>What can be checked about the machine, and what cannot.</b> The environments of the same
 * application differ by hostname alone, so a well-formed address pointing at the wrong one looks
 * exactly right on screen and records against the wrong system. Three things were weighed:
 *
 * <ul>
 *   <li><b>Naming which environment a hostname belongs to — not done, and not doable here.</b>
 *       It needs a list mapping hostname to environment, and there is none: no such list ships
 *       with the plugin, none is configured on any tester machine, and the repository's own
 *       {@code umgebungen.yaml} is a proposal rather than a file. Guessing from a substring
 *       would produce a screen that names an environment confidently and is wrong whenever the
 *       naming scheme changes — a false statement in the expensive direction. Should such a
 *       list ever exist, this is the place for it; until it does, the limitation stands as a
 *       limitation.
 *   <li><b>Comparing against the machine last stored here — done.</b> It needs no configuration
 *       at all, only {@link StartAddressMemory}, and it converts an instruction the tester had
 *       no reference point for ("read the machine name") into an answer the tool produces:
 *       {@link #compareMachine}.
 *   <li><b>Putting the machine where a difference cannot be missed — done by the caller</b>,
 *       which leads its line with the host rather than burying it mid-sentence, because that
 *       line clips rather than wraps and the tail is what a narrow window eats.
 * </ul>
 *
 * <p>Beyond that this refuses anything that is not an absolute {@code http}/{@code https} URL
 * with a host, and hands the caller the {@link #host(String) host} on its own.
 */
final class StartAddress {

    /** Which of the two configured places decided the address the recorder will open. */
    enum Source {
        /** The chosen test case brings its own address; it wins over the project setting. */
        TEST_CASE,
        /** The project setting decides, which is the normal case. */
        PROJECT,
        /** Neither is set: the recorder opens a blank page. */
        NONE,
        /** No Studio to ask — not the same as "nothing is configured" and never shown as one. */
        UNKNOWN
    }

    /**
     * What the recorder will open and why.
     *
     * @param source which place decided it
     * @param url the address that will open, or {@code ""} when none will
     * @param projectUrl the project setting as it stands, {@code ""} when unset, {@code null}
     *     when it could not be read — kept even when {@link #source} is {@link Source#TEST_CASE}
     *     so the screen can say that editing it changes nothing for this test case
     */
    record Effective(Source source, String url, String projectUrl) {
    }

    private StartAddress() {
    }

    /**
     * Why this text cannot be stored as a start address, or {@code null} when it can.
     *
     * <p>Deliberately the same rule the core applies in {@code isUsableStartUrl} and the plugin
     * applies in {@code AdoRecordingTarget.usableStartUrl}: absolute, {@code http} or
     * {@code https}, with a host. Storing something the core will then refuse would produce a
     * setting that reads as configured and still opens a blank page — the original defect with
     * an extra step in front of it.
     *
     * @param candidate whatever was typed, possibly {@code null} or blank
     * @return a German sentence naming the problem, or {@code null} when there is none
     */
    static String problem(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "Es wurde keine Adresse eingegeben.";
        }
        String trimmed = candidate.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            return "Das ist keine gültige Web-Adresse.";
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || scheme == null) {
            return "Die Adresse muss mit http:// oder https:// beginnen.";
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return "Nur http:// und https:// sind möglich — hier steht " + scheme + ":.";
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return "In der Adresse fehlt der Rechnername hinter http:// bzw. https://.";
        }
        return null;
    }

    /**
     * The machine part of an address, so the screen can name it separately from the whole URL.
     *
     * @param url an address that {@link #problem} accepted
     * @return the host, or {@code ""} when it cannot be read
     */
    static String host(String url) {
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? "" : host;
        } catch (URISyntaxException | RuntimeException ex) {
            return "";
        }
    }

    /** How the machine in a new address compares with the one stored here last. */
    enum Machine {
        /** Nothing to compare against — said out loud, never passed off as "unchanged". */
        FIRST,
        /** The same machine as last time. */
        SAME,
        /** A different machine from last time, which is the one worth interrupting for. */
        CHANGED
    }

    /**
     * Whether this address points at the same machine as the one last stored for this project.
     *
     * <p>This is not a verdict about the environment being right — nothing here knows which
     * hostname is which environment. It is the one comparison that needs no configuration, and
     * it reaches all three of its answers: an empty history is {@link Machine#FIRST} and must be
     * shown as "there is nothing to compare against", not as agreement.
     *
     * @param previousUrl the address stored here last time, or {@code null} when there is none
     * @param candidate the address about to be stored
     */
    static Machine compareMachine(String previousUrl, String candidate) {
        String previous = host(previousUrl == null ? "" : previousUrl);
        String now = host(candidate == null ? "" : candidate);
        if (previous.isEmpty() || now.isEmpty()) {
            return Machine.FIRST;
        }
        return previous.equalsIgnoreCase(now) ? Machine.SAME : Machine.CHANGED;
    }

    /**
     * What the recorder will open right now, and which setting decided it.
     *
     * <p>The per-test-case address is read from the same selection file
     * {@code AdoRecordingTarget} reads at the moment a recording starts, and is subjected to
     * the same shape check — an unusable one falls through there, so it must fall through here
     * too, or the screen would name an address the recorder is about to ignore.
     *
     * <p>With no Studio to ask, this answers {@link Source#UNKNOWN} and a {@code null} project
     * value rather than guessing. "Nothing is configured" is a claim about a project, and
     * there is no project in view to make it about.
     */
    static Effective effective() {
        String projectUrl = StudioRecorder.projectStartUrl();
        if (projectUrl == null) {
            return new Effective(Source.UNKNOWN, "", null);
        }
        String caseUrl = fromSelectedTestCase();
        if (caseUrl != null) {
            return new Effective(Source.TEST_CASE, caseUrl, projectUrl);
        }
        if (projectUrl.isBlank()) {
            return new Effective(Source.NONE, "", "");
        }
        return new Effective(Source.PROJECT, projectUrl.trim(), projectUrl.trim());
    }

    /** The chosen test case's own address, or {@code null} when it has none that is usable. */
    private static String fromSelectedTestCase() {
        SelectedTestCase selected;
        try {
            selected = SelectedTestCase.read();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
        if (selected == null) {
            return null;
        }
        String candidate = selected.startUrl();
        return problem(candidate) == null ? candidate.trim() : null;
    }
}
