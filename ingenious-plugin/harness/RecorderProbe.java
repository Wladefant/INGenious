package de.ing.qa.panel;

/**
 * Lets the harness call {@link StudioRecorder} directly.
 *
 * <p>{@code StudioRecorder} is package-private, which is right: nothing outside these panels
 * has any business starting a recording. But the single most important guarantee in this
 * plugin — <em>a start request that arrives while a recording is running must not call
 * {@code record()}, because that call would stop it</em> — cannot be reached through the
 * button, which never sends one. So the harness asks for it here, in the same package, and
 * proves it against the call counter on the Studio double: not merely that the message was
 * refused, but that the core method was never entered.
 *
 * <p>Each verb runs the attempt exactly once and returns both halves of the answer, so a
 * test cannot accidentally record twice by asking two questions.
 */
public final class RecorderProbe {

    private RecorderProbe() {
    }

    /** The recorder state as the panel reads it, as a plain name. */
    public static String state() {
        return StudioRecorder.state().name();
    }

    /** Attempts a start once. Returns {@code "OK|<Satz>"} or {@code "NEIN|<Satz>"}. */
    public static String start() {
        return render(StudioRecorder.start());
    }

    /** Attempts a stop once. Returns {@code "OK|<Satz>"} or {@code "NEIN|<Satz>"}. */
    public static String stop() {
        return render(StudioRecorder.stop());
    }

    /** The configured recorder start address: {@code null}, {@code ""}, or a URL. */
    public static String startUrl() {
        return StudioRecorder.projectStartUrl();
    }

    private static String render(StudioRecorder.Result result) {
        return (result.ok() ? "OK|" : "NEIN|") + result.message();
    }
}
