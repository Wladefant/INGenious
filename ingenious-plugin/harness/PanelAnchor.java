import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;

/**
 * Finds the guided-flow screen inside a Swing tree — <b>by a marker the panel puts there on
 * purpose</b>, and only failing that by the shape of a German sentence.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Twice now, {@code StudioChainDriver} has been silently moved by a change made somewhere
 * else in the panel, and both times it invented a product failure.
 *
 * <p>The second time, on 2026-07-28, is the one that named the disease. The driver located the
 * screen by climbing from the one button only that screen has
 * ({@code "Diesen Testfall übernehmen"}) to the <em>innermost</em> ancestor that also carried a
 * label containing the loose substring {@code "Schritt "}. The panel's layout is
 *
 * <pre>
 *   root ─┬─ NORTH   header   → the chips, the step headline, the banner
 *         ├─ CENTER  cardHost ─┬─ card 0   step 1, and the "übernehmen" button
 *         │                    ├─ card 1   step 2
 *         │                    └─ card 2   step 3
 *         └─ SOUTH   nav
 * </pre>
 *
 * <p>A hint added to card 2 — <i>"…ob jeder <b>Schritt</b> genau ein Element trifft…"</i> —
 * made {@code cardHost} the innermost ancestor holding both the button and a matching label, so
 * the climb stopped one level <em>below</em> the header. Every read of a chip or the banner then
 * addressed a subtree the panel does not keep them in and came back {@code null}; two links
 * turned that {@code null} into <b>BROKEN</b> and a third into COULD NOT TEST. Three verdicts
 * were false, and the screenshot saved two lines earlier showed the ticked chip and the green
 * confirmation banner the driver had just called missing.
 *
 * <p>The immediate fix was to match the headline <em>by its shape</em>,
 * {@code Schritt \d+ von \d+}, which {@code GuidedFlowPanel.update()} writes into exactly one
 * label. That is a better guess. It is not a guarantee: the next innocuous German sentence
 * containing "Schritt 2 von 3" moves the driver again, and nothing in the product forbids one.
 *
 * <p><b>A relied-on instrument must not be locatable by prose.</b> So the locator asks for a
 * marker instead — {@link #MARKER_KEY} = {@link #GUIDED_FLOW}, a client property on the screen's
 * root — and treats the headline climb as a named, reported fallback rather than as the way it
 * works.
 *
 * <h2>The contract, in one line</h2>
 *
 * <pre>
 *   root.putClientProperty("de.ing.qa.panel", "guided-flow");   // in GuidedFlowPanel.createPanel()
 * </pre>
 *
 * <p>Nothing else. It is invisible to the tester, unaffected by translation, unaffected by any
 * rearrangement of the header, the cards or the nav bar, and a layout change cannot move it
 * because it is attached to the component rather than inferred from its contents.
 *
 * <h2>What this class will never do</h2>
 *
 * <p>Fall back <em>silently</em>. Every result carries {@link Result#how()} and a sentence
 * saying which mechanism answered; a caller that prints the sentence makes the difference
 * between "the driver knows where the screen is" and "the driver guessed from a label" visible
 * in the transcript, on every single run, rather than only in the post-mortem after the guess
 * goes wrong.
 */
final class PanelAnchor {

    /** The client-property key the guided-flow screen's root is asked to carry. */
    static final String MARKER_KEY = "de.ing.qa.panel";

    /** Its value on {@code GuidedFlowPanel}'s root. */
    static final String GUIDED_FLOW = "guided-flow";

    /**
     * The panel's step headline, by its shape — {@code "Schritt 2 von 3 — Kunde wählen"}.
     *
     * <p>{@code GuidedFlowPanel.update()} writes {@code "Schritt " + n + " von 3 — " + title}
     * into exactly one label. Shape rather than substring is what stopped the 2026-07-28 break
     * from recurring immediately; it is still prose, which is why it is the fallback.
     */
    static final Pattern STEP_HEADLINE = Pattern.compile("Schritt\\s+\\d+\\s+von\\s+\\d+");

    /** Which mechanism answered. */
    enum How {
        /** The panel's own marker. A layout change cannot move this. */
        STRUCTURAL,
        /** The marker is absent; the screen was found by climbing to the step headline. */
        FALLBACK_HEADLINE,
        /** Neither mechanism found the screen. */
        NOT_FOUND
    }

    /**
     * Where the screen is, how that was decided, and — always — a sentence a transcript can
     * print. {@code note} is never empty and is never merely decorative: on a fallback it says
     * so in as many words.
     */
    record Result(JComponent panel, How how, String note) {

        boolean found() {
            return panel != null;
        }

        /** True when the answer rests on a German sentence rather than on a marker. */
        boolean guessed() {
            return how == How.FALLBACK_HEADLINE;
        }
    }

    private PanelAnchor() {
    }

    /**
     * Locates the guided-flow screen under {@code root}.
     *
     * <p>Structural first. When the marker is present <em>and</em> the headline climb also
     * answers, the two are compared and any disagreement is reported — that comparison is the
     * cheapest available early warning that the fallback has drifted again, and it costs one
     * tree walk on a driver that already spends minutes opening a Studio.
     *
     * @param root       the window, or any ancestor of the screen
     * @param buttonText the button only this screen has, used by the fallback climb
     */
    static Result locate(Component root, String buttonText) {
        if (root == null) {
            return new Result(null, How.NOT_FOUND, "no root to search: the Studio window is null");
        }

        List<JComponent> marked = marked(root);
        JComponent climbed = climbToHeadline(root, buttonText);

        if (marked.size() == 1) {
            JComponent found = marked.get(0);
            String note = "anchor: STRUCTURAL — the screen carries client property "
                + MARKER_KEY + "=" + GUIDED_FLOW + ", so no sentence anywhere on the panel can "
                + "move this driver.";
            if (climbed != null && climbed != found) {
                note += " NOTE: the old headline climb answers with a DIFFERENT component ("
                    + describe(climbed) + " rather than " + describe(found) + "). The marker is "
                    + "authoritative and was used; the disagreement is reported because it is "
                    + "exactly the drift that produced three false BROKEN verdicts on "
                    + "2026-07-28, and it is worth knowing that it has happened again even "
                    + "though it no longer costs anything.";
            }
            return new Result(found, How.STRUCTURAL, note);
        }

        if (marked.size() > 1) {
            // Two marked roots is not a thing to paper over: the driver would be choosing.
            List<String> where = new ArrayList<>();
            for (JComponent c : marked) {
                where.add(describe(c));
            }
            return new Result(null, How.NOT_FOUND,
                "anchor: AMBIGUOUS — " + marked.size() + " components in the window carry "
                    + MARKER_KEY + "=" + GUIDED_FLOW + " (" + String.join(", ", where) + "). "
                    + "The marker is supposed to identify exactly one screen. Refusing to pick "
                    + "one: a driver that chooses between two candidates is back to guessing.");
        }

        if (climbed != null) {
            return new Result(climbed, How.FALLBACK_HEADLINE,
                "anchor: FALLBACK — no component in the Studio window carries client property "
                    + MARKER_KEY + "=" + GUIDED_FLOW + ", so the screen was located the old way: "
                    + "by climbing from \"" + buttonText + "\" to the innermost ancestor holding "
                    + "a label matching /" + STEP_HEADLINE.pattern() + "/. That is prose. A "
                    + "German sentence added anywhere on this panel can move it, and on "
                    + "2026-07-28 one did — three links reported BROKEN that were not. Every "
                    + "verdict in this run rests on that guess being right. Resolved to "
                    + describe(climbed) + ".");
        }

        return new Result(null, How.NOT_FOUND,
            "anchor: NOT FOUND — no component carries " + MARKER_KEY + "=" + GUIDED_FLOW + ", and "
                + "no component under a button \"" + buttonText + "\" carries a label matching /"
                + STEP_HEADLINE.pattern() + "/. Either the screen is not open, or both ways of "
                + "recognising it have been changed at once.");
    }

    /** Every component under {@code root} carrying the guided-flow marker. */
    private static List<JComponent> marked(Component root) {
        List<JComponent> found = new ArrayList<>();
        for (Component c : all(root)) {
            if (c instanceof JComponent jc && GUIDED_FLOW.equals(jc.getClientProperty(MARKER_KEY))) {
                found.add(jc);
            }
        }
        return found;
    }

    /**
     * The pre-marker way: from the button only this screen has, up to the <b>innermost</b>
     * ancestor that also carries the step headline.
     *
     * <p>Innermost, not outermost: Studio's slide show holds every screen at once, so the
     * outermost match is the slide show itself, and searching from there finds another slide's
     * empty list and reports the customer step unreachable while the panel's own status line
     * says two test cases are loaded. That happened too, on 2026-07-28, in the other direction.
     */
    private static JComponent climbToHeadline(Component root, String buttonText) {
        Component button = button(root, buttonText);
        if (button == null) {
            return null;
        }
        for (Component c = button; c != null && c != root; c = c.getParent()) {
            if (c instanceof javax.swing.JRootPane) {
                break;
            }
            if (c instanceof JComponent jc && hasHeadline(jc)) {
                return jc;
            }
        }
        return null;
    }

    /** Whether this subtree carries a label of the headline's shape. */
    static boolean hasHeadline(Component c) {
        return headlineIn(c) != null;
    }

    /** The step headline's text somewhere under {@code c}, or {@code null}. */
    static String headlineIn(Component c) {
        for (Component component : all(c)) {
            if (component instanceof JLabel label) {
                String text = strip(label.getText());
                if (text != null && STEP_HEADLINE.matcher(text).find()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static Component button(Component c, String text) {
        if (c instanceof AbstractButton b && text.equals(b.getText())) {
            return c;
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = button(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<Component> all(Component c) {
        List<Component> found = new ArrayList<>();
        collect(c, found);
        return found;
    }

    private static void collect(Component c, List<Component> into) {
        if (c == null) {
            return;
        }
        into.add(c);
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, into);
            }
        }
    }

    /** A component, said in a way a transcript reader can act on. */
    static String describe(Component c) {
        if (c == null) {
            return "nothing";
        }
        StringBuilder path = new StringBuilder(c.getClass().getSimpleName());
        int depth = 0;
        for (Component p = c.getParent(); p != null && depth < 4; p = p.getParent(), depth++) {
            path.append(" < ").append(p.getClass().getSimpleName());
        }
        return path.toString();
    }

    private static String strip(String html) {
        return html == null ? null
            : html.replaceAll("<br>", " ").replaceAll("<[^>]+>", "").trim();
    }
}
