package org.genesis.app;

import java.time.Duration;

/**
 * Un aller-retour réel avec un serveur gemme-stats (npm run demo) : TelemetryProbe http://127.0.0.1:3090/genesis/stats. Une
 * ouverture, deux laboratoires, une fermeture, envoyés exactement comme le logiciel les envoie.
 */
public final class TelemetryProbe {
    public static void main(String[] args) {
        System.setProperty("genesis.telemetry.endpoint", args[0]);
        TelemetryTest.Memory prefs = new TelemetryTest.Memory();
        prefs.put(Telemetry.NOTICE, "vu");
        Telemetry t = new Telemetry(prefs, Telemetry.http("0.4.1"), "0.4.1", true);
        t.context("screen", "1920x1080");
        t.context("language", "fr");
        t.start();
        t.tick("atom", 42, true);
        t.tick("molecule", 600, true);
        t.tick("molecule", 30, false);
        t.stop(Duration.ofSeconds(5));
        System.out.println("installation " + t.install() + " · file restante : " + prefs.get(Telemetry.QUEUE));
    }
}
