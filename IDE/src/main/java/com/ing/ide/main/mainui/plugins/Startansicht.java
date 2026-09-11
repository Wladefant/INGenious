package com.ing.ide.main.mainui.plugins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Welcher Bildschirm beim Studio-Start vorne liegt.
 *
 * <p>Ohne das hier beginnt jede Sitzung auf <em>Test Design</em>, und ein Tester, der nur
 * aufnehmen und abgeben will, muss erst den richtigen Knopf in der Werkzeugleiste finden.
 * Der Schalter heisst {@code startansicht} und nennt den Bildschirm beim Namen: steht dort
 * {@code testing}, liegt nach dem Start das Panel <em>TestING</em> vorne; steht dort
 * {@code aus} (oder {@code studio}), aendert sich nichts am bisherigen Verhalten.
 *
 * <p><b>Wo der Schalter steht.</b> In der {@code konfiguration.json} der Installation,
 * derselben Datei, in der das Installationsprogramm sich schon Zielordner und Java-Pfad
 * merkt:
 *
 * <pre>{@code %APPDATA%\ING-Testautomatisierung\konfiguration.json}</pre>
 *
 * <p>Davor gehen eine System-Property und eine Umgebungsvariable ({@value #SYS_WUNSCH} /
 * {@value #ENV_WUNSCH}) — dieselbe Reihenfolge, die der Kern schon fuer die anderen
 * ING-Zustandsdateien benutzt (siehe {@code TestCaseComponent}). Fehlt alles, gilt
 * {@value #STANDARD}: der Standard ist an, weil der Tester die Voreinstellung ist.
 *
 * <p><b>Warum kein JSON-Parser.</b> Gelesen wird genau ein Zeichenketten-Wert aus einer
 * flachen Datei, die {@code INSTALLIEREN.ps1} mit {@code ConvertTo-Json} schreibt. Dafuer
 * eine Bibliothek in den Startpfad zu haengen, waere teurer als der Leser unten — und die
 * kopflose Harness des Plugins uebersetzt diese Klasse mit blankem {@code javac}, ohne
 * Klassenpfad. Alles, was hier nicht gelesen werden kann, ist "nicht gesagt" und faellt auf
 * die naechste Quelle zurueck; eine unlesbare Datei kostet niemanden den Start.
 */
public final class Startansicht {
    /** System-Property, die den Schalter der Datei vorgeht. */
    public static final String SYS_WUNSCH = "ing.startansicht";

    /** Umgebungsvariable, die der Datei vorgeht, aber der Property nachsteht. */
    public static final String ENV_WUNSCH = "ING_STARTANSICHT";

    /** Zeigt auf eine andere {@code konfiguration.json} — fuer Pruefstaende. */
    public static final String SYS_DATEI = "ING_QA_KONFIGURATION";

    /** Der Schluessel in der {@code konfiguration.json}. */
    public static final String SCHLUESSEL = "startansicht";

    /** Gilt, wenn niemand etwas anderes sagt. */
    public static final String STANDARD = "testing";

    private static final Logger LOG = Logger.getLogger(Startansicht.class.getName());

    /**
     * Werte, die "kein Panel nach vorn" bedeuten. {@code studio} ist dabei, weil das die
     * Antwort ist, die ein Tester selbst hinschreibt, wenn er wieder auf Studios eigenem
     * Bildschirm anfangen will.
     */
    private static final List<String> AUS = Arrays.asList(
        "aus",
        "nein",
        "off",
        "false",
        "0",
        "keine",
        "studio"
    );

    private Startansicht() {}

    /**
     * Der Bildschirm, der beim Start vorne liegen soll.
     *
     * @return der Name (Titel oder Plugin-Id) des gewuenschten Bildschirms, oder
     *     {@code null}, wenn die Startansicht abgeschaltet ist
     */
    public static String wunsch() {
        String gesagt = ersteAngabe();
        if (gesagt == null) {
            gesagt = STANDARD;
        }
        if (AUS.contains(gesagt.toLowerCase(Locale.ROOT))) {
            LOG.log(Level.INFO, "Startansicht abgeschaltet (startansicht={0})", gesagt);
            return null;
        }
        return gesagt;
    }

    /**
     * Welches Panel jetzt geoeffnet werden soll — die ganze Entscheidung an einer Stelle, damit
     * sie ohne Fenster geprueft werden kann.
     *
     * @param wunsch der Name aus {@link #wunsch()}, {@code null} wenn abgeschaltet
     * @param aktuelleFolie die Folie, die gerade vorne liegt (siehe
     *     {@code AppMainFrame#getCurrentSlide()})
     * @param kandidaten die gefundenen Plugin-Bildschirme
     * @param schonGezeigt {@code true}, wenn diese Sitzung die Startansicht schon angeboten hat
     * @return die Plugin-Identitaet, die geoeffnet werden soll, oder {@code null}, wenn nichts
     *     zu tun ist
     */
    public static String zuOeffnen(
        String wunsch,
        String aktuelleFolie,
        List<Kandidat> kandidaten,
        boolean schonGezeigt
    ) {
        if (schonGezeigt) {
            // Nur beim Start. Wer spaeter ein anderes Projekt oeffnet, hat inzwischen selbst
            // gewaehlt, wo er arbeitet - ihn dorthin zurueckzuwerfen waere ein Fehler.
            return null;
        }
        if (wunsch == null || wunsch.trim().isEmpty()) {
            return null;
        }
        if (kandidaten == null || kandidaten.isEmpty()) {
            LOG.log(Level.INFO, "Startansicht {0}: kein Plugin-Bildschirm installiert", wunsch);
            return null;
        }
        Kandidat treffer = suche(wunsch.trim(), kandidaten);
        if (treffer == null) {
            LOG.log(
                Level.INFO,
                "Startansicht {0}: kein Bildschirm dieses Namens gefunden, es bleibt bei Studios eigenem",
                wunsch
            );
            return null;
        }
        if (treffer.folie() != null && treffer.folie().equals(aktuelleFolie)) {
            // Liegt schon vorne: ein zweites showPluginPanel waere kein Fehler, aber es
            // wuerde das Fenster nach vorn reissen, ohne dass sich etwas aendert.
            LOG.log(Level.INFO, "Startansicht {0} liegt bereits vorne", treffer.titel());
            return null;
        }
        return treffer.identitaet();
    }

    /**
     * Der Bildschirm, den dieser Name meint. Verglichen wird gegen den Titel <em>und</em> gegen
     * die Plugin-Id, damit sowohl {@code testing} (der Titel "TestING") als auch
     * {@code ing-tester-panel} (die Id) funktionieren.
     */
    private static Kandidat suche(String wunsch, List<Kandidat> kandidaten) {
        for (Kandidat kandidat : kandidaten) {
            if (
                wunsch.equalsIgnoreCase(trimmed(kandidat.titel())) ||
                wunsch.equalsIgnoreCase(trimmed(kandidat.identitaet()))
            ) {
                return kandidat;
            }
        }
        return null;
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    /** Property, dann Umgebung, dann Datei — der erste, der etwas sagt, gilt. */
    private static String ersteAngabe() {
        String property = nichtLeer(System.getProperty(SYS_WUNSCH));
        if (property != null) {
            return property;
        }
        String umgebung = nichtLeer(System.getenv(ENV_WUNSCH));
        if (umgebung != null) {
            return umgebung;
        }
        return nichtLeer(ausDatei());
    }

    /**
     * Der Wert des Schalters aus der {@code konfiguration.json}, oder {@code null}.
     *
     * <p>Jeder Fehlschlag — Datei fehlt, Datei unlesbar, Schluessel nicht drin — ist hier
     * derselbe Fall: es wurde nichts gesagt.
     */
    private static String ausDatei() {
        Path datei = datei();
        if (datei == null || !Files.isRegularFile(datei)) {
            return null;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(datei), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.INFO, "konfiguration.json nicht lesbar: " + datei, ex);
            return null;
        }
        return zeichenkette(text, SCHLUESSEL);
    }

    /** Die Konfigurationsdatei — per Property/Umgebung umlenkbar, sonst die der Installation. */
    static Path datei() {
        String explizit = nichtLeer(System.getProperty(SYS_DATEI));
        if (explizit == null) {
            explizit = nichtLeer(System.getenv(SYS_DATEI));
        }
        if (explizit != null) {
            return Paths.get(explizit);
        }
        String appdata = nichtLeer(System.getenv("APPDATA"));
        if (appdata != null) {
            return Paths.get(appdata, "ING-Testautomatisierung", "konfiguration.json");
        }
        String heim = nichtLeer(System.getProperty("user.home"));
        if (heim == null) {
            return null;
        }
        return Paths.get(heim, ".ING-Testautomatisierung", "konfiguration.json");
    }

    /**
     * Der Wert eines Zeichenketten-Schluessels aus einem flachen JSON-Objekt.
     *
     * <p>Gelesen wird zeichenweise und mit Ruecksicht auf Anfuehrungszeichen: ein Backslash in
     * einem Windows-Pfad ({@code "ziel": "C:\\Users\\..."}) beendet keine Zeichenkette, und ein
     * Schluesselname, der in einem <em>Wert</em> vorkommt, wird nicht als Schluessel gelesen.
     * Verschachtelte Objekte werden uebersprungen; dieser Schalter steht auf der obersten Ebene.
     *
     * @param text der Dateiinhalt
     * @param schluessel der gesuchte Schluessel
     * @return der Wert, oder {@code null}, wenn der Schluessel fehlt oder keine Zeichenkette ist
     */
    static String zeichenkette(String text, String schluessel) {
        if (text == null) {
            return null;
        }
        int tiefe = 0;
        int i = 0;
        String letzterSchluessel = null;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                tiefe++;
                i++;
            } else if (c == '}' || c == ']') {
                tiefe--;
                i++;
            } else if (c == '"') {
                StringBuilder wert = new StringBuilder();
                int ende = lies(text, i, wert);
                if (ende < 0) {
                    return null;
                }
                boolean istGesuchterWert =
                    letzterSchluessel != null && tiefe == 1 && letzterSchluessel.equals(schluessel);
                if (istGesuchterWert) {
                    return wert.toString();
                }
                letzterSchluessel = folgtDoppelpunkt(text, ende) ? wert.toString() : null;
                i = ende;
            } else {
                if (c == ',') {
                    letzterSchluessel = null;
                }
                i++;
            }
        }
        return null;
    }

    /**
     * Liest die Zeichenkette, die an {@code start} mit einem Anfuehrungszeichen beginnt.
     *
     * @return der Index hinter dem schliessenden Anfuehrungszeichen, oder {@code -1}, wenn die
     *     Zeichenkette nicht geschlossen wird
     */
    private static int lies(String text, int start, StringBuilder ziel) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (i + 1 >= text.length()) {
                    return -1;
                }
                char naechstes = text.charAt(i + 1);
                switch (naechstes) {
                    case 'n':
                        ziel.append('\n');
                        break;
                    case 'r':
                        ziel.append('\r');
                        break;
                    case 't':
                        ziel.append('\t');
                        break;
                    case 'b':
                        ziel.append('\b');
                        break;
                    case 'f':
                        ziel.append('\f');
                        break;
                    case 'u':
                        if (i + 5 >= text.length()) {
                            return -1;
                        }
                        try {
                            ziel.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                        } catch (NumberFormatException ex) {
                            return -1;
                        }
                        i += 4;
                        break;
                    default:
                        ziel.append(naechstes);
                        break;
                }
                i += 2;
            } else if (c == '"') {
                return i + 1;
            } else {
                ziel.append(c);
                i++;
            }
        }
        return -1;
    }

    /** {@code true}, wenn hinter {@code index} (nur Leerraum dazwischen) ein Doppelpunkt steht. */
    private static boolean folgtDoppelpunkt(String text, int index) {
        for (int i = index; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ':') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return false;
    }

    private static String nichtLeer(String value) {
        if (value == null) {
            return null;
        }
        String getrimmt = value.trim();
        return getrimmt.isEmpty() ? null : getrimmt;
    }

    /**
     * Ein gefundener Plugin-Bildschirm, so weit die Entscheidung ihn kennen muss.
     *
     * <p>Absichtlich kein {@code StudioPanelPlugins.Panel}: diese Klasse traegt einen
     * Konstruktor aus dem Plugin-Klassenlader und laesst sich nicht ohne Studio bauen. Drei
     * Zeichenketten lassen sich in jeder Pruefung hinstellen.
     */
    public static final class Kandidat {
        private final String identitaet;
        private final String titel;
        private final String folie;

        /**
         * @param identitaet die Plugin-Id oder der Klassenname, mit dem
         *     {@code AppMainFrame#showPluginPanel(String)} aufgerufen wird
         * @param titel der angezeigte Name des Bildschirms
         * @param folie der Folienname, unter dem der Bildschirm liegt, wenn er schon gebaut ist
         */
        public Kandidat(String identitaet, String titel, String folie) {
            this.identitaet = identitaet;
            this.titel = titel;
            this.folie = folie;
        }

        public String identitaet() {
            return identitaet;
        }

        public String titel() {
            return titel;
        }

        public String folie() {
            return folie;
        }
    }
}
