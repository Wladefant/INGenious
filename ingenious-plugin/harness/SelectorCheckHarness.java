package de.ing.qa.panel;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * What the selector check tells a tester — against a real page, with a real browser, in a real
 * label.
 *
 * <p>Every scenario below is a genuine run of {@code tools/selector-uniqueness.mjs} against
 * {@code tools/fixtures/ambiguous-selectors}, served over loopback. Nothing is stubbed and no
 * exit code is asserted from a table: the probe opens Chromium, counts with {@code
 * Locator.count()} and answers, and this harness reports what a tester would then read.
 *
 * <h2>Why the fixture's own exit code is not a verdict</h2>
 *
 * <p>The probe project is deliberately <b>not</b> a passing project. Six of its test cases exist
 * to be ambiguous, and one of them <em>passes under the real engine because the engine is
 * wrong</em> — an ambiguous {@code css} inside a frame is resolved with a trailing {@code
 * .first()}, so the run goes green on the wrong element
 * (<a href="https://github.com/ing-bank/INGenious/issues/320">INGenious #320</a>). Reading "the
 * fixture exits 1" as a failure of this harness would therefore be exactly backwards. What is
 * asserted here is the <b>mapping</b>: which sentence a tester sees for each answer the probe can
 * give, and which of those sentences is allowed to look like a pass.
 *
 * <h2>The assertion that matters most</h2>
 *
 * <p>Exactly one outcome may report {@code ok()}. The probe has an exit code that means <em>I
 * could not tell</em>, and it is the most common one in real use: a recording walks several
 * screens, the probe opens one, and the entries belonging to the other screens were not checked.
 * If that came back looking like "geprüft", every untested step in the project would read as
 * verified — which is worse than having no check. So this harness fails if {@code ok()} is ever
 * true for anything but a clean, complete run, and it fails if a not-checked sentence carries the
 * green tick.
 *
 * <h2>And it renders</h2>
 *
 * <p>Each message is put into the same kind of label the panel uses and measured against a 900
 * pixel Studio window, because these lines clip rather than wrap. That is not hypothetical: the
 * hand-off refusal once reached a tester as <em>"Kontonummern dü…"</em> — a sentence that said
 * their work had not been handed over and not one word about what to do. Whatever survives the
 * clip must be the instruction, so the surviving prefix is printed for every scenario and checked
 * for the word that carries the action.
 */
public final class SelectorCheckHarness {

    /** The width the clip is measured against — a Studio window on a laptop screen. */
    private static final int PANEL_WIDTH = 900;

    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        String url = required("ING_HARNESS_FIXTURE_URL");
        Path project = Paths.get(required("ING_INGENIOUS_PROJECT"));

        System.out.println("################################################################");
        System.out.println("# selector check — real probe, real page, real label");
        System.out.println("################################################################");
        System.out.println("fixture url : " + url);
        System.out.println("project     : " + project);
        System.out.println("tool        : " + SelectorCheck.tool());
        System.out.println("receipt     : " + SelectorCheck.receiptFor(project));
        System.out.println();

        if (SelectorCheck.tool() == null) {
            fail("the probe was not found — ING_QA_REPO does not point at the repository");
            report();
            return;
        }

        // ---------------------------------------------------------------- the four answers
        SelectorCheck.Result unique = scenario(
            "eindeutig", "every object present and unique",
            () -> SelectorCheck.check(url, "UniqueOnly", null));
        expect(unique.outcome() == SelectorCheck.Outcome.UNIQUE,
            "a page whose objects are all unique must come back UNIQUE, got " + unique.outcome());
        expect(unique.ok(), "the clean run is the one outcome that may report ok()");
        expect(unique.receipt() != null && Files.isRegularFile(unique.receipt()),
            "a clean run must still leave a receipt — the package has to be able to say what was "
                + "checked, not only that nothing was wrong");

        SelectorCheck.Result ambiguous = scenario(
            "mehrdeutig", "a page carrying deliberate ambiguity",
            () -> SelectorCheck.check(url, "AmbiguityPage", null));
        expect(ambiguous.outcome() == SelectorCheck.Outcome.AMBIGUOUS,
            "the ambiguous page must come back AMBIGUOUS, got " + ambiguous.outcome());
        expect(!ambiguous.ok(), "an ambiguous recording must never report ok()");
        expect(!ambiguous.ambiguous().isEmpty(),
            "the tester must be told WHICH step is ambiguous, not merely that one is");
        expect(ambiguous.silent() > 0,
            "the fixture carries an ambiguous css inside a frame, so the silent-at-replay count "
                + "must be greater than zero — that is the case the engine will never raise");
        expect(ambiguous.message().contains("NICHT auf"),
            "the silent case must be spelled out: a run that goes green on the wrong element is "
                + "the one thing a replay report will never tell anybody");

        // An application that is simply not up: the ordinary way a tester meets CANNOT_TELL,
        // and deliberately not simulated by removing the tool or the project.
        SelectorCheck.Result partial = scenario(
            "nicht-erreichbar", "the application is not up",
            () -> SelectorCheck.check("http://127.0.0.1:1/", "AmbiguityPage", null));
        expect(partial.outcome() == SelectorCheck.Outcome.CANNOT_TELL,
            "an unreachable page must come back CANNOT_TELL, got " + partial.outcome());
        expect(!partial.ok(), "CANNOT_TELL is not a pass and must never report ok()");
        expect(!partial.decided(), "CANNOT_TELL reached no verdict, so decided() must be false");

        SelectorCheck.Result noUrl = scenario(
            "ohne-adresse", "no address to open",
            () -> SelectorCheck.check("  ", "AmbiguityPage", null));
        expect(noUrl.outcome() == SelectorCheck.Outcome.NO_URL,
            "no address must come back NO_URL, got " + noUrl.outcome());
        expect(!noUrl.ok(), "a check that never ran must never report ok()");
        expect(noUrl.receipt() == null,
            "a check that never ran must leave no receipt behind to be read as one");

        // ---------------------------------------------------------------- the shared rules
        List<SelectorCheck.Result> all = List.of(unique, ambiguous, partial, noUrl);
        for (SelectorCheck.Result r : all) {
            expect(r.ok() == (r.outcome() == SelectorCheck.Outcome.UNIQUE),
                "ok() must be true for UNIQUE and for nothing else — " + r.outcome()
                    + " reported ok() == " + r.ok());
            expect(r.ok() || !r.message().contains("✔"),
                "only a pass may carry the green tick; " + r.outcome() + " does: " + r.message());
            expect(!r.message().isBlank(), r.outcome() + " said nothing at all");
        }

        // A receipt describes one page state. The partial run could not write one, and the
        // stale receipt from the previous scenario must not be handed back as its answer.
        expect(partial.receipt() == null,
            "an unreachable page wrote no receipt, so none may be reported — otherwise the "
                + "previous scenario's answer becomes this one's");

        renderAll(all);
        reachable();
        report();
    }

    /**
     * That a tester can actually get at this.
     *
     * <p>Everything above proves the check answers correctly. None of it proves the button
     * exists — and a correct check nobody can press is worth exactly nothing. So the real
     * guided-flow panel is built and its component tree walked for the button and for the line
     * that reports its answer.
     *
     * <p>The line is asserted to be non-blank <em>before anything has been pressed</em>. An
     * empty grey strip is not a neutral state: it is a screen element whose meaning the tester
     * has to guess, and later, when the check has genuinely not run, its silence would be
     * indistinguishable from an answer nobody noticed.
     */
    private static void reachable() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            // Recorded, not returned from. renderAll() below skips headless too and SAYS so;
            // this one used to vanish without a line, so on a screenless machine the two
            // checks that prove the tester can reach any of this simply stopped existing and
            // the run still printed GREEN. A check that disappears when a precondition is
            // absent is a check that cannot fail — the same thing as no check, with the
            // camouflage of a passing suite.
            System.out.println("!! UNGEPRUEFT — no desktop session, so the panel was never "
                + "built and the button was never looked for.");
            FAILURES.add("UNGEPRUEFT: the check's button was not looked for (headless)");
            return;
        }
        System.out.println("── erreichbar — die Schaltfläche im echten Panel");
        SwingUtilities.invokeAndWait(() -> {
            GuidedFlowPanel flow = new GuidedFlowPanel();
            JFrame frame = new JFrame("selector-check reachability (never shown)");
            frame.setContentPane(flow.createPanel());
            frame.pack();
            frame.setSize(1500, 950);
            frame.validate();

            List<java.awt.Component> found = new ArrayList<>();
            walk(frame.getContentPane(), found);
            boolean button = found.stream().anyMatch(c ->
                c instanceof javax.swing.JButton b
                    && GuidedFlowPanel.BTN_CHECK.equals(b.getText()));
            expect(button, "the guided flow shows no \"" + GuidedFlowPanel.BTN_CHECK
                + "\" button — the check is correct and unreachable");
            System.out.println("   Schaltfläche \"" + GuidedFlowPanel.BTN_CHECK + "\": "
                + (button ? "vorhanden" : "FEHLT"));

            String idle = found.stream()
                .filter(c -> c instanceof JLabel l && l.getText() != null
                    && l.getText().contains("prüfen"))
                .map(c -> ((JLabel) c).getText())
                .findFirst().orElse("");
            expect(!idle.isBlank(),
                "the check's line says nothing before anything is pressed — a blank strip is a "
                    + "state whose meaning the tester has to guess");
            System.out.println("   Zeile davor : " + idle);
            frame.dispose();
        });
        System.out.println();
    }

    private static void walk(java.awt.Component c, List<java.awt.Component> into) {
        into.add(c);
        if (c instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                walk(child, into);
            }
        }
    }

    // ------------------------------------------------------------------ scenarios

    private interface Attempt {
        SelectorCheck.Result run();
    }

    private static SelectorCheck.Result scenario(String id, String what, Attempt attempt) {
        System.out.println("── " + id + " — " + what);
        long started = System.currentTimeMillis();
        SelectorCheck.Result result = attempt.run();
        long took = System.currentTimeMillis() - started;
        System.out.println("   outcome : " + result.outcome() + "   ok=" + result.ok()
            + "   decided=" + result.decided() + "   (" + took + " ms)");
        System.out.println("   receipt : " + result.receipt());
        if (!result.ambiguous().isEmpty()) {
            System.out.println("   betroffen: " + String.join(", ", result.ambiguous())
                + "   davon still: " + result.silent());
        }
        System.out.println("   sagt    : " + result.message());
        System.out.println();
        return result;
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Puts every message into the panel's kind of label and reports what survives the clip.
     *
     * <p>Headless is a skip, not a pass: the clip is a property of a real font at a real size,
     * and asserting it against a synthesised headless font would prove something about a screen
     * nobody has.
     */
    private static void renderAll(List<SelectorCheck.Result> results) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("!! UNGEPRUEFT — no desktop session, so the messages were never "
                + "measured against a real font. The clip was not checked.");
            FAILURES.add("UNGEPRUEFT: rendering not measured (headless)");
            return;
        }
        System.out.println("── gerendert — was in einem 900-Pixel-Fenster übrig bleibt");
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("selector-check");
            JLabel line = new JLabel(" ");
            line.setOpaque(true);
            line.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            line.setFont(line.getFont().deriveFont(Font.BOLD));
            frame.getContentPane().add(line);
            // Packed, never shown — the same thing the guided-flow harness does, for the same
            // reason: this must run on a build machine without taking over a desktop.
            frame.pack();

            for (SelectorCheck.Result r : results) {
                line.setText(r.message());
                String visible = clip(line, r.message());
                System.out.println("   " + pad(r.outcome().toString()) + " │ " + visible);
                expect(line.getText().equals(r.message()),
                    "the label must carry the whole message even when it cannot show it — the "
                        + "tooltip and the log read it back");
                expect(carriesAction(visible),
                    r.outcome() + ": what survives the clip says nothing the tester can act on — "
                        + "\"" + visible + "\"");
                // A tester told to fix their recording needs to know WHICH step. The first
                // draft of the ambiguous sentence put the explanation before the names and
                // clipped at "1 davon fällt beim Abs…" — an instruction with no target.
                expect(r.ambiguous().isEmpty() || namesAStep(visible, r.ambiguous()),
                    r.outcome() + ": the clip names no affected step, so the tester is told to "
                        + "fix something and not which one — \"" + visible + "\"");
            }
            frame.dispose();
        });
        System.out.println();
    }

    /** What the label can actually paint at {@link #PANEL_WIDTH}, ellipsis and all. */
    private static String clip(JLabel label, String text) {
        FontMetrics fm = label.getFontMetrics(label.getFont());
        int room = PANEL_WIDTH - 20;
        if (fm.stringWidth(text) <= room) {
            return text;
        }
        int width = fm.stringWidth("…");
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            width += fm.charWidth(text.charAt(i));
            if (width > room) {
                break;
            }
            shown.append(text.charAt(i));
        }
        return shown + "…";
    }

    /**
     * Whether the visible part of a line tells the tester what to do or what was found.
     *
     * <p>Deliberately generous: it looks for the words this class actually leads with, so it
     * fails when a future edit buries the instruction behind an explanation, and not merely when
     * the wording changes.
     */
    private static boolean carriesAction(String visible) {
        return visible.contains("Bitte") || visible.contains("bitte")
            || visible.contains("Geprüft") || visible.contains("NICHT geprüft")
            || visible.contains("TEILWEISE geprüft");
    }

    /** Whether the visible part still names at least one of the steps the tester must fix. */
    private static boolean namesAStep(String visible, List<String> ambiguous) {
        for (String entry : ambiguous) {
            // The receipt spells them "WeiterButton (2×)"; the name alone is what has to survive.
            String name = entry.contains(" (") ? entry.substring(0, entry.indexOf(" (")) : entry;
            if (visible.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static String pad(String s) {
        return s.length() >= 12 ? s : s + " ".repeat(12 - s.length());
    }

    // ------------------------------------------------------------------ bookkeeping

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set — the runner must set it");
        }
        return value.trim();
    }

    private static void expect(boolean condition, String whatItProves) {
        checks++;
        if (!condition) {
            FAILURES.add(whatItProves);
            System.out.println("   !! " + whatItProves);
        }
    }

    private static void fail(String why) {
        checks++;
        FAILURES.add(why);
        System.out.println("!! " + why);
    }

    private static void report() {
        System.out.println("################################################################");
        if (FAILURES.isEmpty()) {
            System.out.println("RESULT: GREEN — " + checks + " assertion(s), all against a real "
                + "probe run on a real page.");
            System.exit(0);
        }
        boolean onlyUnproved = FAILURES.stream().allMatch(f -> f.startsWith("UNGEPRUEFT"));
        System.out.println((onlyUnproved ? "RESULT: UNGEPRUEFT" : "RESULT: RED") + " — "
            + FAILURES.size() + " of " + checks + " did not hold:");
        for (String f : FAILURES) {
            System.out.println("  - " + f);
        }
        System.exit(onlyUnproved ? 4 : 1);
    }
}
