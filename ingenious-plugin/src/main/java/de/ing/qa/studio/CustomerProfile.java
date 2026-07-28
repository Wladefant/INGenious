package de.ing.qa.studio;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which <em>kind</em> of test customer a test case needs — never which customer was used.
 *
 * <p>A row of the test-data export is two different things at once. The Kontonummer is a
 * working value: the tester pastes it into the application while recording, and it is only
 * valid until the test data is refreshed. Everything else — {@code Part Partnertyp Kz},
 * {@code Produktvariante Pzm}, {@code Verf Bez}, {@code MDJ_KND}, {@code EZB},
 * {@code Variante_KND}, {@code Kbo5 Bonitaet S} and the rest of the attribute columns —
 * describes the customer as a category, and decides which variant of the screen appears.
 *
 * <p>Only the second half is a profile. It is reproducible after a data refresh, it is
 * meaningful to whoever reads the test case a year later, and it puts no account number into
 * the project or any repository the project is committed to.
 *
 * <p>Two rules keep account numbers out, not one. The column is dropped by name, as the picker
 * finds it; and any value that merely <em>looks</em> like an account number is dropped as well,
 * whatever column it sits in, because a renamed or duplicated column must not become a leak.
 */
public final class CustomerProfile {

    /** Long digit runs are account numbers, whatever the column is called. */
    private static final int ACCOUNT_LIKE_DIGITS = 8;

    private final Map<String, String> settings;

    private CustomerProfile(Map<String, String> settings) {
        this.settings = Collections.unmodifiableMap(settings);
    }

    /**
     * Reads a profile out of one row of the picker's table.
     *
     * @param headers the export's header row
     * @param row the chosen customer's row, cell for cell against {@code headers}
     * @return the profile; empty when the row carries nothing worth recording
     */
    public static CustomerProfile of(List<String> headers, List<String> row) {
        Map<String, String> settings = new LinkedHashMap<>();
        if (headers == null || row == null) {
            return new CustomerProfile(settings);
        }
        for (int column = 0; column < headers.size() && column < row.size(); column++) {
            String name = trim(headers.get(column));
            String value = trim(row.get(column));
            if (name.isEmpty() || value.isEmpty()) {
                continue;
            }
            if (isAccountColumn(name) || looksLikeAnAccountNumber(value)) {
                continue;
            }
            settings.put(name, value);
        }
        return new CustomerProfile(settings);
    }

    /**
     * The columns to record and what to record in them, in the export's own order.
     *
     * @return the profile, never {@code null}
     */
    public Map<String, String> settings() {
        return settings;
    }

    /**
     * @return {@code true} when there is nothing to record
     */
    public boolean isEmpty() {
        return settings.isEmpty();
    }

    /**
     * The same rule the picker uses to find the account column: matched on the header text,
     * because the export's column order changes between releases but the name does not.
     */
    private static boolean isAccountColumn(String name) {
        return name.toLowerCase().replace(" ", "").contains("kontonummer");
    }

    private static boolean looksLikeAnAccountNumber(String value) {
        int digits = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '0' && character <= '9') {
                digits++;
            } else if (character != ' ' && character != '.' && character != '-') {
                return false;
            }
        }
        return digits >= ACCOUNT_LIKE_DIGITS;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return "CustomerProfile" + settings;
    }
}
