package com.ing.ide.main;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Prueft das Beenden an einem echten Prozess (#677).
 *
 * <p>Jeder Test startet eine eigene JVM, die sich wie Studio aufstellt: JavaFX-Werkzeugkasten
 * hochgefahren, {@code Platform.setImplicitExit(false)} gesetzt, ein sichtbares Fenster mit
 * {@code DO_NOTHING_ON_CLOSE} - so wie {@code Main.launchUI()} es tut. Dann wird das Fenster
 * geschlossen. Was danach mit dem Prozess passiert, ist die Behauptung, um die es hier geht; sie
 * wird an {@code Process.waitFor} gemessen und nicht an einem Protokolleintrag.
 */
public class BeendenTest {
    private static final String MARKE_BEREIT = "MARKE: bereit";
    private static final String MARKE_ZU = "MARKE: fenster-zu";

    @BeforeClass
    public void nurMitBildschirm() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new SkipException(
                "Ohne Bildschirm gibt es kein Studio-Fenster und damit nichts zu schliessen."
            );
        }
    }

    /**
     * Der Befund aus #677: ein geschlossenes Fenster beendet Studio nicht. Ohne diesen Test ist
     * der Rest nur eine Behauptung darueber, was Swing und JavaFX von sich aus tun.
     */
    @Test(timeOut = 120_000)
    public void geschlossenesFensterAlleinBeendetDieJvmNicht() throws Exception {
        Process studio = starte("nur-schliessen");
        try (BufferedReader ausgabe = leser(studio)) {
            wartetAufMarke(studio, ausgabe, MARKE_ZU);
            assertFalse(
                studio.waitFor(6, TimeUnit.SECONDS),
                "Ein Studio ohne Beenden.jetzt() muesste als kopfloses javaw stehen bleiben - " +
                "tut es das nicht mehr, beschreibt dieser Test den Fehler #677 nicht mehr."
            );
        } finally {
            studio.destroyForcibly().waitFor(20, TimeUnit.SECONDS);
        }
    }

    /** Mit {@link Beenden#jetzt()} ist der Prozess weg, und zwar geordnet statt per halt. */
    @Test(timeOut = 120_000)
    public void beendenBeendetDieJvm() throws Exception {
        Process studio = starte("beenden");
        try (BufferedReader ausgabe = leser(studio)) {
            wartetAufMarke(studio, ausgabe, MARKE_ZU);
            long begonnen = System.nanoTime();
            assertTrue(
                studio.waitFor(30, TimeUnit.SECONDS),
                "Beenden.jetzt() hat den Prozess nicht beendet."
            );
            long dauerMs = (System.nanoTime() - begonnen) / 1_000_000L;
            assertEquals(studio.exitValue(), 0, "Studio soll sich ohne Fehlercode beenden.");
            assertTrue(
                dauerMs < Beenden.FRIST_MS,
                "Ohne haengenden Abschluss-Haken muss der geordnete Ausgang vor der Frist von " +
                Beenden.FRIST_MS +
                " ms fertig sein, gebraucht hat er " +
                dauerMs +
                " ms."
            );
        } finally {
            studio.destroyForcibly().waitFor(20, TimeUnit.SECONDS);
        }
    }

    /**
     * Der Fall, an dem {@code System.exit} allein scheitert: ein Abschluss-Haken, der nicht fertig
     * wird (im Produkt der Engine-Haken aus {@code Control.initRun()} oder ein Haken des
     * Playwright-Treibers). Der Wachhund muss den Prozess trotzdem zu Ende bringen.
     */
    @Test(timeOut = 120_000)
    public void haengenderAbschlussHakenVerhindertDasBeendenNicht() throws Exception {
        Process studio = starte("beenden", "haengender-haken");
        try (BufferedReader ausgabe = leser(studio)) {
            wartetAufMarke(studio, ausgabe, MARKE_ZU);
            long begonnen = System.nanoTime();
            assertTrue(
                studio.waitFor(Beenden.FRIST_MS + 30_000, TimeUnit.MILLISECONDS),
                "Ein haengender Abschluss-Haken haelt den Prozess fest - der Wachhund hat nicht " +
                "den Stecker gezogen."
            );
            long dauerMs = (System.nanoTime() - begonnen) / 1_000_000L;
            assertEquals(studio.exitValue(), 0, "Auch hart beendet soll der Code 0 sein.");
            assertTrue(
                dauerMs >= Beenden.FRIST_MS / 2,
                "So schnell kann nur der geordnete Ausgang fertig geworden sein - dann haengt der " +
                "Abschluss-Haken dieses Pruefstands nicht mehr und der Test prueft den " +
                "Wachhund nicht: " +
                dauerMs +
                " ms."
            );
        } finally {
            studio.destroyForcibly().waitFor(20, TimeUnit.SECONDS);
        }
    }

    private static Process starte(String... argumente) throws IOException {
        List<String> befehl = new ArrayList<>();
        befehl.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        befehl.add("-cp");
        befehl.add(System.getProperty("java.class.path"));
        befehl.add(StudioDoppelgaenger.class.getName());
        befehl.addAll(Arrays.asList(argumente));
        return new ProcessBuilder(befehl).redirectErrorStream(true).start();
    }

    private static BufferedReader leser(Process studio) {
        return new BufferedReader(
            new InputStreamReader(studio.getInputStream(), StandardCharsets.UTF_8)
        );
    }

    private static void wartetAufMarke(Process studio, BufferedReader ausgabe, String marke)
        throws IOException {
        StringBuilder gesehen = new StringBuilder();
        String zeile;
        while ((zeile = ausgabe.readLine()) != null) {
            gesehen.append(zeile).append(System.lineSeparator());
            if (zeile.contains(marke)) {
                return;
            }
        }
        throw new AssertionError(
            "Der Pruefstand hat \"" +
            marke +
            "\" nie gemeldet. Ausgabe:" +
            System.lineSeparator() +
            gesehen
        );
    }

    /**
     * Ein Studio im Kleinen: genau die Aufstellung aus {@code Main.launchUI()}, die verhindert,
     * dass sich die JVM von selbst beendet. Laeuft als eigener Prozess.
     */
    public static final class StudioDoppelgaenger {

        private StudioDoppelgaenger() {}

        public static void main(String[] argumente) throws Exception {
            List<String> args = Arrays.asList(argumente);
            if (args.contains("haengender-haken")) {
                CountDownLatch nieFertig = new CountDownLatch(1);
                Thread haken = new Thread(
                    () -> {
                        try {
                            nieFertig.await();
                        } catch (InterruptedException unterbrochen) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    "haengender-abschluss-haken"
                );
                Runtime.getRuntime().addShutdownHook(haken);
            }

            JFrame[] fenster = new JFrame[1];
            SwingUtilities.invokeAndWait(
                () -> {
                    new JFXPanel(); // startet den JavaFX-Werkzeugkasten, wie Main.launchUI()
                    Platform.setImplicitExit(false);
                    JFrame studio = new JFrame("Studio-Doppelgaenger");
                    studio.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    studio.setSize(240, 120);
                    studio.setLocation(-4000, -4000); // niemandem ins Bild springen
                    studio.setVisible(true);
                    fenster[0] = studio;
                }
            );

            CountDownLatch javafxLaeuft = new CountDownLatch(1);
            Platform.runLater(javafxLaeuft::countDown);
            if (!javafxLaeuft.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX-Werkzeugkasten kam nicht hoch.");
            }
            melde(MARKE_BEREIT);

            SwingUtilities.invokeAndWait(fenster[0]::dispose);
            melde(MARKE_ZU);

            if (args.contains("beenden")) {
                Beenden.jetzt();
            }
            // Ohne Beenden.jetzt() endet dieser Prozess nie von selbst - das ist der Befund.
            Thread.sleep(TimeUnit.MINUTES.toMillis(5));
        }

        private static void melde(String marke) {
            System.out.println(marke);
            System.out.flush();
        }
    }
}
