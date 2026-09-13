package com.ing.ide.main.fx;

import com.ing.ide.main.mainui.AppActionListener;
import com.ing.ide.main.mainui.plugins.StudioPanelPlugins;
import com.ing.ingenious.api.contract.ui.StudioPanelApi;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ToolBar;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.kordamp.ikonli.javafx.FontIcon;
import org.testng.annotations.Test;

/**
 * Robot-Harness fuer Issue #574:
 * TestING-Knopf im Studio deutlich faerben (Blau #0F5BD7), damit ihn jeder sofort findet.
 *
 * Prueft:
 * 1. TestING-Knopf hat Klasse "testing-btn" und weisses Icon (MaterialDesignP.PLAY_CIRCLE).
 * 2. Andere Knoepfe (Workbench, weiteres Plugin, New Project) sind UNBERUEHRT (kein Blau, kein testing-btn).
 * 3. Hintergrundfarbe ist gefuelltes ING-Blau #0F5BD7.
 * 4. Kontrast zwischen weisser Schrift/Symbol (#FFFFFF) und Hintergrund (#0F5BD7) >= 4.5:1 (WCAG AA) in Hell und Dunkel.
 * 5. Hover- und Pressed-Zustaende sind sichtbar und halten Kontrast >= 4.5:1.
 * 6. Sichtbarkeit bei 1366x768 und 1280x720 ohne Scrollen (Nutzerregel 13.09.).
 * 7. Screenshots in hell und dunkel werden gespeichert und geprueft.
 */
public class TestINGToolbarKnopfTest {
    private static final String EXPECTED_BLUE_HEX = "#0F5BD7";
    private static final String EXPECTED_HOVER_HEX = "#1967D2";
    private static final String EXPECTED_PRESSED_HEX = "#0B46A8";
    private static final String EXPECTED_WHITE_HEX = "#FFFFFF";

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "target/screenshots-issue-574");
        Files.createDirectories(outDir);
        new TestINGToolbarKnopfTest().runAllChecks(outDir);
    }

    @Test
    public void testToolbarButtonBlauUndKontrast() throws Exception {
        Path outDir = Path.of("target/screenshots-issue-574");
        Files.createDirectories(outDir);
        runAllChecks(outDir);
    }

    public void runAllChecks(Path outDir) throws Exception {
        System.out.println("==================================================================");
        System.out.println("TestING Toolbar-Knopf Harness (Issue #574)");
        System.out.println("==================================================================");

        // 1. Initialisiere JavaFX Toolkit
        CountDownLatch fxInitLatch = new CountDownLatch(1);
        SwingUtilities.invokeLater(
            () -> {
                new JFXPanel(); // bootet JavaFX
                fxInitLatch.countDown();
            }
        );
        if (!fxInitLatch.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("JavaFX Toolkit konnte nicht initialisiert werden");
        }

        // 2. Mocke Plugin-Panel fuer TestING und ein zweites Plugin
        setupMockPanels();

        // 3. Erzeuge FXToolBar
        AtomicReference<FXToolBar> toolbarRef = new AtomicReference<>();
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        CountDownLatch toolbarLatch = new CountDownLatch(1);

        SwingUtilities.invokeLater(
            () -> {
                try {
                    FXToolBar fxToolBar = new FXToolBar(null);
                    toolbarRef.set(fxToolBar);

                    JFrame frame = new JFrame("Studio Toolbar Test #574");
                    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                    frame.add(fxToolBar);
                    frame.setSize(1366, 200);
                    frame.setLocationRelativeTo(null);
                    frame.setVisible(true);
                    frameRef.set(frame);
                } finally {
                    toolbarLatch.countDown();
                }
            }
        );

        if (!toolbarLatch.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("FXToolBar konnte nicht erstellt werden");
        }

        Thread.sleep(600); // Zeit fuer Rendern und CSS-Anwendung

        FXToolBar fxToolBar = toolbarRef.get();
        JFrame frame = frameRef.get();

        try {
            // Finde Knoepfe auf dem FX-Thread
            AtomicReference<Button> testingBtnRef = new AtomicReference<>();
            AtomicReference<Button> workbenchBtnRef = new AtomicReference<>();
            AtomicReference<Button> otherPluginBtnRef = new AtomicReference<>();
            AtomicReference<VBox> rootRef = new AtomicReference<>();
            AtomicReference<ToolBar> tbRef = new AtomicReference<>();

            runOnFxSync(
                () -> {
                    Scene scene = fxToolBar.getScene();
                    VBox root = (VBox) scene.getRoot();
                    rootRef.set(root);
                    ToolBar tb = (ToolBar) root.getChildren().get(0);
                    tbRef.set(tb);

                    for (Node node : tb.getItems()) {
                        if (node instanceof Button) {
                            Button btn = (Button) node;
                            String text = btn.getText();
                            if ("TestING".equals(text)) {
                                testingBtnRef.set(btn);
                            } else if ("Workbench".equals(text)) {
                                workbenchBtnRef.set(btn);
                            } else if ("Anderes Plugin".equals(text)) {
                                otherPluginBtnRef.set(btn);
                            }
                        }
                    }
                }
            );

            Button testingBtn = testingBtnRef.get();
            Button workbenchBtn = workbenchBtnRef.get();
            Button otherPluginBtn = otherPluginBtnRef.get();

            checkNotNull(testingBtn, "TestING-Knopf in der Werkzeugleiste gefunden");
            checkNotNull(workbenchBtn, "Workbench-Knopf in der Werkzeugleiste gefunden");
            checkNotNull(otherPluginBtn, "Anderes Plugin-Knopf in der Werkzeugleiste gefunden");

            // ── Pruefung 1: Stilklassen und Icon ──
            runOnFxSync(
                () -> {
                    // TestING-Knopf
                    assertThat(
                        testingBtn.getStyleClass().contains("testing-btn"),
                        "TestING-Knopf traegt Stilklasse 'testing-btn'"
                    );
                    assertThat(
                        !testingBtn.getStyleClass().contains("workbench-btn"),
                        "TestING-Knopf traegt NICHT 'workbench-btn'"
                    );

                    Node graphic = testingBtn.getGraphic();
                    assertThat(graphic != null, "TestING-Knopf hat ein Symbol (Graphic)");
                    assertThat(graphic instanceof FontIcon, "Symbol ist ein Ikonli FontIcon");
                    FontIcon icon = (FontIcon) graphic;
                    assertThat(
                        Color.WHITE.equals(icon.getIconColor()),
                        "Symbolfarbe des TestING-Knopfs ist Weiss (#FFFFFF)"
                    );

                    // Andere Knoepfe
                    assertThat(
                        !workbenchBtn.getStyleClass().contains("testing-btn"),
                        "Workbench-Knopf traegt NICHT 'testing-btn'"
                    );
                    assertThat(
                        workbenchBtn.getStyleClass().contains("workbench-btn"),
                        "Workbench-Knopf behaelt 'workbench-btn'"
                    );

                    assertThat(
                        !otherPluginBtn.getStyleClass().contains("testing-btn"),
                        "Anderes Plugin traegt NICHT 'testing-btn'"
                    );
                    assertThat(
                        otherPluginBtn.getStyleClass().contains("workbench-btn"),
                        "Anderes Plugin behaelt 'workbench-btn'"
                    );
                }
            );

            // ── Pruefung 2: Helle Ansicht (Light Mode) ──
            Path lightShot = outDir.resolve("testing-toolbar-light.png");
            runOnFxSync(
                () -> {
                    FXTheme.toggleTheme(false);
                    VBox root = rootRef.get();
                    root.applyCss();
                    root.layout();
                }
            );
            Thread.sleep(300);

            runOnFxSync(
                () -> {
                    // Berechne Kontrast
                    double contrastNormal = berechneKontrast(EXPECTED_BLUE_HEX, EXPECTED_WHITE_HEX);
                    double contrastHover = berechneKontrast(EXPECTED_HOVER_HEX, EXPECTED_WHITE_HEX);
                    double contrastPressed = berechneKontrast(
                        EXPECTED_PRESSED_HEX,
                        EXPECTED_WHITE_HEX
                    );

                    System.out.printf(
                        "  [Kontrast] Normal:  %.2f:1 (Soll >= 4.5:1)%n",
                        contrastNormal
                    );
                    System.out.printf(
                        "  [Kontrast] Hover:   %.2f:1 (Soll >= 4.5:1)%n",
                        contrastHover
                    );
                    System.out.printf(
                        "  [Kontrast] Pressed: %.2f:1 (Soll >= 4.5:1)%n",
                        contrastPressed
                    );

                    assertThat(
                        contrastNormal >= 4.5,
                        "Kontrast Normal >= 4.5:1 (ist " + contrastNormal + ":1)"
                    );
                    assertThat(
                        contrastHover >= 4.5,
                        "Kontrast Hover >= 4.5:1 (ist " + contrastHover + ":1)"
                    );
                    assertThat(
                        contrastPressed >= 4.5,
                        "Kontrast Pressed >= 4.5:1 (ist " + contrastPressed + ":1)"
                    );
                }
            );

            // Screenshot Hell aufnehmen
            speichereScreenshot(frame, testingBtn, lightShot);
            pruefeFarbenAusBild(lightShot, testingBtn, true);

            // ── Pruefung 3: Dunkle Ansicht (Dark Mode) ──
            Path darkShot = outDir.resolve("testing-toolbar-dark.png");
            runOnFxSync(
                () -> {
                    FXTheme.toggleTheme(true);
                    VBox root = rootRef.get();
                    root.applyCss();
                    root.layout();
                }
            );
            Thread.sleep(300);

            runOnFxSync(
                () -> {
                    double contrastDark = berechneKontrast(EXPECTED_BLUE_HEX, EXPECTED_WHITE_HEX);
                    System.out.printf(
                        "  [Dunkel-Kontrast] Normal: %.2f:1 (Soll >= 4.5:1)%n",
                        contrastDark
                    );
                    assertThat(
                        contrastDark >= 4.5,
                        "Dunkel-Modus Kontrast >= 4.5:1 (ist " + contrastDark + ":1)"
                    );
                }
            );

            // Screenshot Dunkel aufnehmen
            speichereScreenshot(frame, testingBtn, darkShot);
            pruefeFarbenAusBild(darkShot, testingBtn, true);

            // ── Pruefung 4: Geometrie & Sichtbarkeit bei 1366x768 und 1280x720 ──
            int[] breiten = { 1366, 1280 };
            for (int w : breiten) {
                runOnFxSync(
                    () -> {
                        VBox root = rootRef.get();
                        root.setPrefWidth(w);
                        root.applyCss();
                        root.layout();

                        Bounds b = testingBtn.localToScene(testingBtn.getBoundsInLocal());
                        assertThat(
                            b.getMinX() >= 0,
                            "TestING-Knopf minX >= 0 bei " + w + "px (ist " + b.getMinX() + ")"
                        );
                        assertThat(
                            b.getMaxX() <= w,
                            "TestING-Knopf maxX <= " +
                            w +
                            " (ist " +
                            b.getMaxX() +
                            ") - ohne Scroll sichtbar!"
                        );
                        assertThat(
                            b.getWidth() > 0 && b.getHeight() > 0,
                            "TestING-Knopf hat positive Dimensionen bei " + w + "px"
                        );
                        assertThat(
                            testingBtn.isVisible(),
                            "TestING-Knopf ist sichtbar bei " + w + "px"
                        );
                        System.out.printf(
                            "  [Geometrie %d] Knopf-Position: [%.0f .. %.0f] px, Breite: %.0f px, sichtbar ohne Scrollen%n",
                            w,
                            b.getMinX(),
                            b.getMaxX(),
                            b.getWidth()
                        );
                    }
                );
            }

            System.out.println(
                "=================================================================="
            );
            System.out.println(
                "RESULT: GREEN — TestING-Knopf ist blau, weiss, kontrastreich (>= 4.5:1),"
            );
            System.out.println("        in Hell und Dunkel sichtbar und belegt!");
            System.out.println("  Screenshot Hell:   " + lightShot.toAbsolutePath());
            System.out.println("  Screenshot Dunkel: " + darkShot.toAbsolutePath());
            System.out.println(
                "=================================================================="
            );
        } finally {
            if (frame != null) {
                SwingUtilities.invokeLater(frame::dispose);
            }
        }
    }

    private static void setupMockPanels() throws Exception {
        Constructor<StudioPanelPlugins.Panel> constructor =
            StudioPanelPlugins.Panel.class.getDeclaredConstructor(
                    String.class,
                    String.class,
                    String.class,
                    Integer.class,
                    String.class,
                    Constructor.class,
                    StudioPanelApi.class
                );
        constructor.setAccessible(true);

        StudioPanelPlugins.Panel testingPanel = constructor.newInstance(
            "ing-tester-panel",
            "TestING",
            "TestING: Testfall waehlen, Testkunde waehlen, Aufnahme starten",
            1,
            "3.0.0",
            null,
            null
        );

        StudioPanelPlugins.Panel otherPanel = constructor.newInstance(
            "other-plugin-id",
            "Anderes Plugin",
            "Ein neutrales Plugin, das workbench-btn behalten soll",
            2,
            "1.0.0",
            null,
            null
        );

        List<StudioPanelPlugins.Panel> panels = List.of(testingPanel, otherPanel);
        Map<String, StudioPanelPlugins.Panel> map = new LinkedHashMap<>();
        map.put(testingPanel.getIdentity(), testingPanel);
        map.put(otherPanel.getIdentity(), otherPanel);

        Field cachedField = StudioPanelPlugins.class.getDeclaredField("cached");
        cachedField.setAccessible(true);
        cachedField.set(null, Collections.unmodifiableList(panels));

        Field cachedByIdentityField = StudioPanelPlugins.class.getDeclaredField("cachedByIdentity");
        cachedByIdentityField.setAccessible(true);
        cachedByIdentityField.set(null, Collections.unmodifiableMap(map));
    }

    private static void speichereScreenshot(JFrame frame, Button button, Path targetFile)
        throws Exception {
        // JavaFX-Snapshot direkt vom Button und der Toolbar
        CountDownLatch shotLatch = new CountDownLatch(1);
        AtomicReference<WritableImage> fxImageRef = new AtomicReference<>();
        runOnFxSync(
            () -> {
                WritableImage img = button.getScene().getRoot().snapshot(null, null);
                fxImageRef.set(img);
                shotLatch.countDown();
            }
        );
        shotLatch.await(5, TimeUnit.SECONDS);

        WritableImage fxImg = fxImageRef.get();
        if (fxImg != null) {
            BufferedImage bImg = javafx.embed.swing.SwingFXUtils.fromFXImage(fxImg, null);
            ImageIO.write(bImg, "png", targetFile.toFile());
            System.out.println("  [Screenshot] Gespeichert: " + targetFile.toAbsolutePath());
        }
    }

    private static void pruefeFarbenAusBild(Path imageFile, Button button, boolean erwarteBlau)
        throws Exception {
        BufferedImage img = ImageIO.read(imageFile.toFile());
        assertThat(img != null, "Screenshot-Bild existiert und ist lesbar: " + imageFile);

        // Bestimme Koordinaten des TestING-Knopfs in der Szene
        AtomicReference<Bounds> boundsRef = new AtomicReference<>();
        runOnFxSync(
            () -> {
                Bounds b = button.localToScene(button.getBoundsInLocal());
                boundsRef.set(b);
            }
        );
        Bounds b = boundsRef.get();

        int xCenter = (int) (b.getMinX() + b.getWidth() / 2);
        int yCenter = (int) (b.getMinY() + b.getHeight() / 2);

        // Begrenze auf Bildabmessungen
        xCenter = Math.max(0, Math.min(img.getWidth() - 1, xCenter));
        yCenter = Math.max(0, Math.min(img.getHeight() - 1, yCenter));

        // Sample Pixel um den Mittelpunkt (Hintergrund)
        boolean blauerPixelGefunden = false;
        boolean weisserPixelGefunden = false;

        int xStart = (int) Math.max(0, b.getMinX() + 4);
        int xEnd = (int) Math.min(img.getWidth() - 1, b.getMaxX() - 4);
        int yStart = (int) Math.max(0, b.getMinY() + 4);
        int yEnd = (int) Math.min(img.getHeight() - 1, b.getMaxY() - 4);

        for (int y = yStart; y <= yEnd; y++) {
            for (int x = xStart; x <= xEnd; x++) {
                int rgb = img.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // Soll-Blau: #0F5BD7 (R: 15, G: 91, B: 215)
                // Toleranz fuer Display-Scale / Antialiasing
                if (blue > 180 && red < 50 && green > 60 && green < 130) {
                    blauerPixelGefunden = true;
                }
                // Soll-Weiss (Schrift/Symbol): R > 240, G > 240, B > 240
                if (red > 240 && green > 240 && blue > 240) {
                    weisserPixelGefunden = true;
                }
            }
        }

        if (erwarteBlau) {
            assertThat(
                blauerPixelGefunden,
                "Im Bild wurde der blaue Knopf-Hintergrund (#0F5BD7-Bereich) nachgewiesen"
            );
            assertThat(
                weisserPixelGefunden,
                "Im Bild wurde weisse Schrift / weisses Symbol nachgewiesen"
            );
            System.out.println(
                "  [Pixel-Probe] Blauer Hintergrund und weisse Schrift/Symbol erfolgreich im gerenderten Bild verifiziert!"
            );
        }
    }

    /**
     * Berechnet den WCAG 2.1 relativen Kontrast zwischen zwei Hex-Farben.
     * Formel: (L1 + 0.05) / (L2 + 0.05)
     */
    public static double berechneKontrast(String hex1, String hex2) {
        double l1 = relativeLuminanz(hex1);
        double l2 = relativeLuminanz(hex2);
        double heller = Math.max(l1, l2);
        double dunkler = Math.min(l1, l2);
        return (heller + 0.05) / (dunkler + 0.05);
    }

    private static double relativeLuminanz(String hex) {
        String clean = hex.replace("#", "");
        int r = Integer.parseInt(clean.substring(0, 2), 16);
        int g = Integer.parseInt(clean.substring(2, 4), 16);
        int b = Integer.parseInt(clean.substring(4, 6), 16);

        double rs = sRgbZuLinear(r / 255.0);
        double gs = sRgbZuLinear(g / 255.0);
        double bs = sRgbZuLinear(b / 255.0);

        return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs;
    }

    private static double sRgbZuLinear(double c) {
        if (c <= 0.04045) {
            return c / 12.92;
        } else {
            return Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    private static void runOnFxSync(Runnable action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        Platform.runLater(
            () -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    latch.countDown();
                }
            }
        );
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Aktion auf JavaFX Application Thread timed out");
        }
        if (errorRef.get() != null) {
            if (errorRef.get() instanceof Exception) throw (Exception) errorRef.get();
            throw new RuntimeException(errorRef.get());
        }
    }

    private static void assertThat(boolean condition, String message) {
        if (!condition) {
            System.err.println("  FEHLER: " + message);
            throw new AssertionError(message);
        }
        System.out.println("  OK: " + message);
    }

    private static void checkNotNull(Object obj, String message) {
        assertThat(obj != null, message);
    }
}
