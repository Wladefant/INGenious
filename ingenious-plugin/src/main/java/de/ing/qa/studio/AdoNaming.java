package de.ing.qa.studio;

/**
 * The one convention that ties an ADO test case to an INGenious test case: its name.
 *
 * <p>INGenious has no field for foreign keys. A {@code TestCase} is a name inside a
 * {@code Scenario}, and both become directories and files on disk — there is nowhere to hang
 * an ADO id. So the id lives in the name, and {@link #adoIdFromTestCaseName(String)} is the
 * documented way back. That is what makes a later run publishable to the right ADO case
 * without a side-car database.
 *
 * <p>Sanitising is deliberately harsher than INGenious requires. {@code Validator.isValidName}
 * only blocks what Windows and the reference syntax cannot survive ({@code , # $ { } ^ [ ] %}
 * and the usual path characters); this reduces to ASCII letters, digits, space, hyphen and
 * underscore. ADO titles at a German bank carry umlauts, slashes, parentheses and colons, and
 * a name that is merely <em>legal</em> still has to be typed into a CLI, matched in a report
 * and read in a tree. Umlauts are transliterated rather than dropped, so
 * "Partner-Suche prüfen" stays readable as "Partner-Suche pruefen".
 */
public final class AdoNaming {

    /** Used when the ADO case names no suite; a test case must still land somewhere. */
    public static final String DEFAULT_SCENARIO = "ADO Testfaelle";

    /** Between id and title. Chosen so the id is still recoverable from a title with hyphens. */
    private static final String SEPARATOR = " - ";

    private static final int MAX_SCENARIO = 60;
    private static final int MAX_TEST_CASE = 80;

    private AdoNaming() {}

    /**
     * The scenario an ADO case belongs in — its suite, so a suite worked through in one
     * sitting collects into one scenario.
     *
     * @param suiteName the ADO suite name, may be {@code null} or blank
     * @return a usable scenario name, never blank
     */
    public static String scenarioName(String suiteName) {
        String cleaned = sanitize(suiteName, MAX_SCENARIO);
        return cleaned.isEmpty() ? DEFAULT_SCENARIO : cleaned;
    }

    /**
     * The test case name carrying the ADO id: {@code "<id> - <title>"}.
     *
     * @param adoId the ADO work-item id
     * @param title the ADO title, may be {@code null} or blank
     * @return a usable test case name, never blank
     * @throws IllegalArgumentException when {@code adoId} has no digits at all — without an id
     *     the name would not be traceable back to ADO, which is the whole point of it
     */
    public static String testCaseName(String adoId, String title) {
        String id = sanitize(adoId, 20);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("adoId must not be null or blank");
        }
        String cleaned = sanitize(title, MAX_TEST_CASE - id.length() - SEPARATOR.length());
        return cleaned.isEmpty() ? id : id + SEPARATOR + cleaned;
    }

    /**
     * The inverse of {@link #testCaseName(String, String)}: the ADO id a test case was
     * recorded for.
     *
     * <p>Reads the leading digit run, so it survives a test case renamed from
     * "3951650 - Partner-Suche" to "3951650 - Partner-Suche (neu)" but correctly declines a
     * test case that was never created from an ADO case.
     *
     * @param testCaseName an INGenious test case name, may be {@code null}
     * @return the ADO id, or {@code null} when the name does not start with one
     */
    public static String adoIdFromTestCaseName(String testCaseName) {
        if (testCaseName == null) {
            return null;
        }
        String trimmed = testCaseName.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        // "12345Foo" is a name that happens to start with digits, not an id-prefixed name.
        if (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
            return null;
        }
        return trimmed.substring(0, end);
    }

    /**
     * Reduces free ADO text to a name that is legal, portable and still readable.
     *
     * @param text the raw text, may be {@code null}
     * @param maxLength the longest result to return, in characters
     * @return the sanitised text, possibly empty, never {@code null}
     */
    static String sanitize(String text, int maxLength) {
        if (text == null || maxLength <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case 'ä':
                    sb.append("ae");
                    break;
                case 'ö':
                    sb.append("oe");
                    break;
                case 'ü':
                    sb.append("ue");
                    break;
                case 'Ä':
                    sb.append("Ae");
                    break;
                case 'Ö':
                    sb.append("Oe");
                    break;
                case 'Ü':
                    sb.append("Ue");
                    break;
                case 'ß':
                    sb.append("ss");
                    break;
                default:
                    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '-' || c == '_') {
                        sb.append(c);
                    } else {
                        // Everything else — punctuation, umlauts we did not name, newlines —
                        // becomes a space and is collapsed below.
                        sb.append(' ');
                    }
                    break;
            }
        }
        String collapsed = sb.toString().replaceAll("\\s+", " ").trim();
        if (collapsed.length() > maxLength) {
            collapsed = collapsed.substring(0, maxLength).trim();
        }
        // A trailing hyphen or underscore reads as a truncation artefact; a trailing dot or
        // space is illegal on Windows and cannot survive the trim above alone.
        while (!collapsed.isEmpty() && "-_. ".indexOf(collapsed.charAt(collapsed.length() - 1)) >= 0) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
    }
}
