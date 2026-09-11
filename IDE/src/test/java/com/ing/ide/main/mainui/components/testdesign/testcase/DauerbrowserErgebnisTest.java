package com.ing.ide.main.mainui.components.testdesign.testcase;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.IOException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class DauerbrowserErgebnisTest {

    @DataProvider(name = "nichtBereit")
    public Object[][] nichtBereit() {
        // Timeout/Verbindungsfehler, Argumentfehler, Abbruch, unerwartetes sauberes Ende.
        return new Object[][] { { 1 }, { 2 }, { -1 }, { 0 }, { 137 } };
    }

    @Test(dataProvider = "nichtBereit")
    public void unklarerDaemonZustandStartetKeinenCodegen(int code) {
        IOException error = expectThrows(
            IOException.class,
            () -> TestCaseComponent.dauerbrowserErgebnis(false, code)
        );
        assertTrue(error.getMessage().contains("kein zweiter"));
        assertTrue(error.getMessage().contains("Code " + code));
    }

    @Test
    public void nurExpliziterStartschutzErlaubtRueckfall() throws IOException {
        assertFalse(TestCaseComponent.dauerbrowserErgebnis(false, 3));
    }

    @Test(dataProvider = "nichtBereit")
    public void beendeteLaufendeAufnahmeStartetKeinenCodegen(int code) throws IOException {
        assertTrue(TestCaseComponent.dauerbrowserErgebnis(true, code));
    }
}
