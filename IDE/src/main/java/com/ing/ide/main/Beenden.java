package com.ing.ide.main;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Das Ende des Studio-Prozesses, und zwar verbindlich (#677).
 *
 * <p>Studio kann sich nicht von selbst beenden. {@code Main.launchUI()} startet den
 * JavaFX-Werkzeugkasten ({@code new JFXPanel()}) und schaltet mit
 * {@code Platform.setImplicitExit(false)} genau das ab, was JavaFX beim letzten geschlossenen
 * Fenster sonst tun wuerde. Danach laufen drei Threads, die keine Daemon-Threads sind - gemessen
 * am 16.09.2026 an einem laufenden Studio mit {@code jcmd <pid> Thread.print}:
 *
 * <pre>
 * "AWT-EventQueue-0"           waiting on condition
 * "JavaFX Application Thread"  runnable  com.sun.glass.ui.win.WinApplication._runLoop
 * "AWT-Shutdown"               in Object.wait()
 * </pre>
 *
 * <p>Solange einer davon lebt, lebt die JVM. Das Fenster zu schliessen genuegt also nicht: der
 * Prozess bleibt als kopfloses {@code javaw} stehen, haelt die Studio-Jars gesperrt, und die
 * naechste Aktualisierung scheitert daran (#629). Genau das meldete PC28GR am 16.09.2026 - drei
 * kopflose {@code javaw} neben dem sichtbaren Studio, waehrend das Protokoll jedes Mal
 * "INGenious Playwright Studio has been Terminated" sagte. Diese Zeile kommt aus
 * {@link Main#finish()} und stoppt nur eine Stoppuhr; beendet hat sie nie etwas.
 *
 * <p>Beendet wurde bis dahin allein von {@code JFrame.processWindowEvent}: das schiebt unter
 * {@code EXIT_ON_CLOSE} ein {@code System.exit(0)} nach - aber erst, nachdem alle
 * {@code WindowListener} gelaufen sind. Wirft einer davon, faellt das {@code System.exit}
 * ersatzlos aus. Und selbst wenn es laeuft, ist es nicht fertig: {@code System.exit} arbeitet
 * zuerst alle Abschluss-Haken ab ({@code Runtime.addShutdownHook}) - den Engine-Haken aus
 * {@code Control.initRun()}, die Haken des Playwright-Treibers - und bleibt stehen, solange einer
 * davon steht. Beide Wege enden im selben kopflosen javaw.
 *
 * <p>Deshalb diese Klasse: das Beenden wird angefordert, und es wird zu Ende gebracht. Ein
 * Daemon-Wachhund zieht nach {@link #FRIST_MS} den Stecker, wenn der geordnete Ausgang nicht
 * fertig wird - und sagt es auf {@code System.err}, damit die naechste Diagnose nicht blind ist.
 */
public final class Beenden {
    /**
     * Gnadenfrist fuer die Abschluss-Haken: lang genug, dass ein laufender Bericht noch
     * geschrieben wird, kurz genug, dass "Fenster zu" und "Prozess weg" derselbe Moment bleiben.
     */
    public static final long FRIST_MS = 5_000L;

    private static final AtomicBoolean ANGEFORDERT = new AtomicBoolean();

    private Beenden() {}

    /**
     * Beendet den Studio-Prozess. Kehrt im Regelfall nicht zurueck; ein zweiter Aufruf ist kein
     * zweites Beenden und kehrt sofort zurueck.
     */
    public static void jetzt() {
        if (!ANGEFORDERT.compareAndSet(false, true)) {
            return;
        }
        try {
            javafx.application.Platform.exit();
        } catch (Throwable ignored) {
            // JavaFX might not be initialized or present
        }
        Thread wachhund = new Thread(Beenden::stecker, "ing-beenden-wachhund");
        // Daemon: der Wachhund darf niemals selbst der Grund sein, dass die JVM weiterlaeuft.
        wachhund.setDaemon(true);
        wachhund.start();
        System.exit(0);
    }

    private static void stecker() {
        try {
            Thread.sleep(FRIST_MS);
        } catch (InterruptedException unterbrochen) {
            Thread.currentThread().interrupt();
            return;
        }
        // Nicht java.util.logging: an dieser Stelle laeuft die Abschluss-Folge der JVM schon,
        // und der LogManager schliesst seine Handler in einem eigenen Abschluss-Haken. Gemessen
        // am 16.09.2026 an einem echten Studio mit haengendem Haken: die Zeile fehlte im
        // Live-Protokoll vollstaendig. System.err ueberlebt - der Starter leitet ihn nach
        // %TEMP%\ingenious-launch-<pid>.log.err um.
        System.err.println(
            "Beenden: der geordnete Ausgang war nach " +
            FRIST_MS +
            " ms nicht fertig - ein Abschluss-Haken haengt. Der Prozess wird jetzt hart beendet," +
            " damit kein kopfloses javaw zurueckbleibt."
        );
        System.err.flush();
        Runtime.getRuntime().halt(0);
    }
}
