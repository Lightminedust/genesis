package org.genesis.app;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryTest {
    /** Les préférences, en mémoire. */
    static final class Memory implements Telemetry.Store {
        final Map<String, String> values = new HashMap<>();
        @Override public String get(String key) { return values.get(key); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
    }

    /** Le serveur : retient ce qu'il reçoit ; `online` faux, il ne répond pas. */
    static final class Server implements Telemetry.Sender {
        final List<String> received = new ArrayList<>();
        boolean online = true;
        @Override public synchronized boolean send(String json, Duration timeout) {
            if (online) received.add(json);
            return online;
        }
    }

    @Test
    void theIdentifierIsCreatedOnceAndKeptAcrossLaunches() {
        Memory prefs = new Memory();
        String first = new Telemetry(prefs, new Server(), "0.4.1", true).install();
        assertTrue(Telemetry.isUuid(first));
        // Une mise à jour, une réinstallation : les mêmes préférences, le même identifiant.
        assertEquals(first, new Telemetry(prefs, new Server(), "0.5.0", true).install());
    }

    @Test
    void aSessionSendsItsOpeningThenItsLengthAndItsLaboratories() {
        Memory prefs = new Memory();
        prefs.put(Telemetry.NOTICE, "vu");
        Server server = new Server();
        Telemetry t = new Telemetry(prefs, server, "0.4.1", true);
        t.context("language", "fr");
        t.start();
        t.tick("atom", 30, true);
        t.tick("molecule", 50, true);
        t.tick("molecule", 20, false);
        t.tick("atom", 10, true);
        t.stop(Duration.ofSeconds(5));
        assertEquals(2, server.received.size(), server.received.toString());
        String start = server.received.get(0), end = server.received.get(1);
        assertTrue(start.contains("\"event\":\"start\"") && start.contains("\"language\":\"fr\""), start);
        assertTrue(end.contains("\"event\":\"end\"") && end.contains("\"seconds\":110") && end.contains("\"active_seconds\":90"), end);
        // L'atome ouvert deux fois (40 s), la molécule une fois (70 s).
        assertTrue(end.contains("{\"lab\":\"atom\",\"opens\":2,\"seconds\":40}"), end);
        assertTrue(end.contains("{\"lab\":\"molecule\",\"opens\":1,\"seconds\":70}"), end);
        assertTrue(end.contains("\"clean\":true"), end);
        assertNull(prefs.get(Telemetry.QUEUE));
    }

    @Test
    void refusingAtTheFirstLaunchSendsNothingEver() {
        Memory prefs = new Memory();
        Server server = new Server();
        Telemetry t = new Telemetry(prefs, server, "0.4.1", true);
        assertTrue(t.noticeNeeded());
        t.start();
        t.tick("atom", 5, true);
        t.answer(false);                                    // la carte : « Refuser l'envoi »
        t.tick("atom", 5, true);
        t.stop(Duration.ofSeconds(2));
        assertTrue(server.received.isEmpty(), server.received.toString());
        assertNull(prefs.get(Telemetry.QUEUE));
        // Les lancements suivants non plus, et la carte ne revient pas.
        Telemetry later = new Telemetry(prefs, server, "0.4.1", true);
        assertFalse(later.noticeNeeded());
        later.start();
        later.stop(Duration.ofSeconds(2));
        assertTrue(server.received.isEmpty());
    }

    @Test
    void withoutAnAnswerTheFirstSessionWaitsThenLeavesAtClosing() {
        Memory prefs = new Memory();
        Server server = new Server();
        Telemetry t = new Telemetry(prefs, server, "0.4.1", true);
        t.start();
        t.tick("atom", 12, true);
        t.flush(Duration.ofSeconds(2));
        assertTrue(server.received.isEmpty(), "rien ne part tant que la carte n'a pas de réponse");
        t.stop(Duration.ofSeconds(2));                      // pas de refus : l'envoi par défaut s'applique
        assertEquals(2, server.received.size());
        assertTrue(server.received.get(0).contains("\"first_run\":true"));
    }

    @Test
    void offlineEventsWaitAndACrashedSessionIsSentAtTheNextLaunch() {
        Memory prefs = new Memory();
        prefs.put(Telemetry.NOTICE, "vu");
        Server server = new Server();
        server.online = false;
        Telemetry t = new Telemetry(prefs, server, "0.4.1", true);
        t.start();
        t.tick("fusion", Telemetry.CHECKPOINT_S + 1, true);  // la fin de session notée
        assertNotNull(prefs.get(Telemetry.PENDING));
        // Arrêt brutal : pas de stop. Au lancement suivant, en ligne : la file, la fin perdue, puis la nouvelle ouverture.
        server.online = true;
        Telemetry next = new Telemetry(prefs, server, "0.4.1", true);
        next.start();
        next.flush(Duration.ofSeconds(2));
        assertEquals(3, server.received.size(), server.received.toString());
        assertTrue(server.received.get(0).contains("\"event\":\"start\""));
        assertTrue(server.received.get(1).contains("\"event\":\"end\"") && server.received.get(1).contains("\"clean\":false")
                && server.received.get(1).contains("\"lab\":\"fusion\""));
        assertTrue(server.received.get(2).contains("\"event\":\"start\""));
        assertNull(prefs.get(Telemetry.PENDING));
    }

    @Test
    void aDevelopmentBuildNeverSendsAndTheQueueStaysUnderThePreferenceLimit() {
        Memory prefs = new Memory();
        Server server = new Server();
        Telemetry dev = new Telemetry(prefs, server, null, false);
        assertFalse(dev.enabled());
        assertFalse(dev.noticeNeeded());
        dev.start();
        dev.stop(Duration.ofSeconds(1));
        assertTrue(server.received.isEmpty());
        // Hors ligne longtemps : la file garde les plus récents sous 8 000 caractères.
        prefs.put(Telemetry.NOTICE, "vu");
        server.online = false;
        for (int k = 0; k < 40; k++) {
            Telemetry t = new Telemetry(prefs, server, "0.4.1", true);
            t.start();
            t.tick("atom", 3, true);
            t.stop(Duration.ofMillis(200));
        }
        assertTrue(prefs.get(Telemetry.QUEUE).length() <= Telemetry.QUEUE_CHARS);
    }

    @Test
    void textIsEscapedInTheJson() {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("a", "x\"y\\z\n");
        value.put("b", java.util.Arrays.asList(1, true, null));
        assertEquals("{\"a\":\"x\\\"y\\\\z\\n\",\"b\":[1,true,null]}", Telemetry.json(value));
    }
}
