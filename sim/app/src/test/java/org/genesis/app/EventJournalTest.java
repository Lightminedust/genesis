package org.genesis.app;

import org.genesis.mascot.GenesisMascot;

import com.studio.genesis.grpc.SimEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Le journal (catégories, événement à expliquer) et la façon dont Genesis explique. */
public class EventJournalTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); }); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    private static SimEvent event(String kind, String title, double time) {
        return SimEvent.newBuilder().setKind(kind).setTitle(title).setTimeS(time).build();
    }

    @Test void eachEventHasItsCategory() {
        assertEquals(EventJournal.Category.LIGHT, EventJournal.category(event("absorbed", "", 0)));
        assertEquals(EventJournal.Category.LIGHT, EventJournal.category(event("passed", "", 0)));
        assertEquals(EventJournal.Category.ELECTRON, EventJournal.category(event("captured", "", 0)));
        assertEquals(EventJournal.Category.NUCLEUS, EventJournal.category(event("decay", "", 0)));
        assertEquals(EventJournal.Category.INFO, EventJournal.category(event("info", "", 0)));
    }

    @Test void theMostImportantEventOfTheLastInstantIsExplained() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            EventJournal journal = new EventJournal();
            assertNull(journal.latest());
            journal.add(event("absorbed", "Absorption", 1));
            journal.add(event("photon_fired", "Photon tiré", 1.5));          // pas un événement : son sort en est un
            assertEquals(1, journal.size());
            // Une désintégration et ses suites arrivent au même instant : c'est le noyau qu'on explique.
            journal.add(event("info", "Saut dans le temps : 4 Ga", 5));
            journal.add(event("decay", "U-238 → Th-234 : désintégration α", 5));
            journal.add(event("rejected", "Un électron s'échappe", 5));
            assertEquals("decay", journal.latest().getKind());
            journal.add(event("info", "Une information", 6));
            journal.add(event("info", "Une information", 6));                // répétée : une seule ligne
            assertEquals(5, journal.size());
            journal.setCompact(true);
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    @Test void genesisExplainsInThreeSimpleSteps() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            GenesisMascot g = new GenesisMascot(150, 184);
            g.setManual(true);
            g.explainNow(SimEvent.newBuilder().setKind("absorbed").setTitle("Absorption Lyman α").setWavelengthNm(121.567f).build());
            String said = g.saying();
            assertTrue(said.startsWith("L'atome a avalé ton photon"), said);
            assertTrue(said.contains("122 nm") && said.contains("ultraviolet"), said);
            assertTrue(said.contains("\nPourquoi :") && said.contains("\nEt maintenant :"), said);
            g.explainNow(SimEvent.newBuilder().setKind("decay").setDecayMode("alpha").setTitle("U-238 → Th-234").build());
            assertTrue(g.saying().contains("2 protons et 2 neutrons") && g.saying().contains("hasard"), g.saying());
            g.explainNow(SimEvent.newBuilder().setKind("passed").setWavelengthNm(500).build());
            assertTrue(g.saying().contains("\nEssaie :"), g.saying());
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }
}
