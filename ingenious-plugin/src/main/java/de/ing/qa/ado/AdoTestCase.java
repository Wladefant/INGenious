package de.ing.qa.ado;

import java.util.List;

/**
 * One ADO test case as the panels need to read it — never as ADO returns it.
 *
 * <p>{@code preconditionField} names the ADO field {@code preconditions} was read
 * from. It is deliberately carried all the way into the UI: "Voraussetzungen" is not
 * a standard ADO field, so a tester must be able to tell "this case has no
 * preconditions" apart from "we looked in the wrong field".
 *
 * <p>{@code webUrl}/{@code webUrlSource} carry the browser link for "In Azure DevOps
 * öffnen". Same honesty rule: {@code webUrlSource} says whether ADO handed the link out
 * itself ({@code ADO}) or whether it was built from the org/project in the cache
 * ({@code KONSTRUIERT}); {@code null} means no link could be determined at all and the
 * action must stay disabled rather than opening a guess that 404s.
 *
 * @param adoId ADO work-item id, the id the rest of the flow keys on
 * @param title System.Title
 * @param suiteName test suite the case was found in (first suite wins)
 * @param state work-item state (Design/Ready/Closed), not the run outcome
 * @param outcome last run outcome of the test point, if any
 * @param description System.Description as plain text
 * @param preconditions the Voraussetzungen block as plain text, may be empty
 * @param preconditionField ADO reference name of the field above, or null
 * @param steps action/expected pairs, already flattened by the Node tool
 * @param webUrl browser URL of the work item, or null when none could be determined
 * @param webUrlSource where {@code webUrl} came from, or null when there is none
 */
public record AdoTestCase(
    String adoId,
    String title,
    String suiteName,
    String state,
    String outcome,
    String description,
    String preconditions,
    String preconditionField,
    List<String> steps,
    String webUrl,
    UrlSource webUrlSource
) {

    /** Where the browser URL came from — shown to the tester, never hidden. */
    public enum UrlSource {
        /** ADO's own {@code _links.html.href}, copied verbatim into the cache. */
        ADO,
        /** Built from the org/project the cache was generated against. */
        KONSTRUIERT
    }

    /** True when "In Azure DevOps öffnen" has something real to open. */
    public boolean hasWebUrl() {
        return webUrl != null && !webUrl.isBlank();
    }

    /** One line for the chooser list: id, title, and where the case lives. */
    public String listLabel() {
        StringBuilder sb = new StringBuilder();
        sb.append(adoId).append("  ").append(title);
        if (suiteName != null && !suiteName.isBlank()) {
            sb.append("   [").append(suiteName).append(']');
        }
        return sb.toString();
    }

    /** True if the free-text needle matches id, title or suite. */
    public boolean matches(String needle) {
        if (needle == null || needle.isBlank()) {
            return true;
        }
        String haystack = (adoId + " " + title + " " + suiteName).toLowerCase();
        // Every whitespace-separated token must hit, so "partner suche" narrows.
        for (String token : needle.toLowerCase().trim().split("\\s+")) {
            if (!haystack.contains(token)) {
                return false;
            }
        }
        return true;
    }
}
