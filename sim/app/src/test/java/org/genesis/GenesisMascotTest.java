package org.genesis;

import com.studio.genesis.grpc.SimEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GenesisMascotTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); }); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @Test void sheNamesTheLightFromItsWavelength() {
        assertTrue(GenesisMascot.lightName(656).contains("rouge"));
        assertTrue(GenesisMascot.lightName(486).contains("bleue"));
        assertTrue(GenesisMascot.lightName(121).contains("ultraviolet"));
        assertTrue(GenesisMascot.lightName(1875).contains("infrarouge"));
    }

    @Test void eachActivityHasItsOwnLight() {
        java.util.Set<String> colours = new java.util.HashSet<>();
        for (GenesisMascot.Activity a : GenesisMascot.Activity.values()) colours.add(GenesisMascot.stateColour(a).toString());
        assertEquals(GenesisMascot.Activity.values().length, colours.size(), "un état, une couleur");
    }

    @Test void herMatterSplitsAndRejoins() {
        // deux lobes proches : un seul morceau ; éloignés : deux
        java.util.List<GenesisMascot.Lobe> close = java.util.List.of(new GenesisMascot.Lobe(0, 0, .3), new GenesisMascot.Lobe(.35, 0, .15));
        java.util.List<GenesisMascot.Lobe> far = java.util.List.of(new GenesisMascot.Lobe(0, 0, .3), new GenesisMascot.Lobe(.7, 0, .15));
        assertEquals(1, GenesisMascot.outline(close, -1, -1, 101, 101, .02).size());
        assertEquals(2, GenesisMascot.outline(far, -1, -1, 101, 101, .02).size());
    }

    @Test void sheExplainsEachEventWithAMood() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            GenesisMascot g = new GenesisMascot(104, 112);
            g.setManual(true);
            for (int k = 0; k < 600; k++) g.tick(1 / 30.0);                     // elle a fini de se présenter
            g.explain(SimEvent.newBuilder().setKind("emitted").setTitle("Retombée n = 3 → 2").setWavelengthNm(656.3f).setEnergyEv(1.889f).build());
            assertEquals(GenesisMascot.Mood.HAPPY, g.mood());
            assertTrue(g.saying().contains("656 nm") && g.saying().contains("rouge"), g.saying());
            for (int k = 0; k < 600; k++) g.tick(1 / 30.0);
            g.explain(SimEvent.newBuilder().setKind("passed").setTitle("Le photon traverse").setWavelengthNm(500).build());
            assertEquals(GenesisMascot.Mood.CURIOUS, g.mood());
            // au fil du temps, son corps se sépare puis se recolle
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int k = 0; k < 1800; k++) { g.tick(1 / 30.0); seen.add(g.pieces()); }
            assertTrue(seen.size() >= 2 && java.util.Collections.min(seen) <= 2, "des morceaux qui partent et reviennent : " + seen);
            for (int k = 0; k < 3000; k++) g.tick(1 / 30.0);                    // rien pendant 100 s : elle s'endort
            assertEquals(GenesisMascot.Mood.SLEEPY, g.mood());
            assertEquals(GenesisMascot.Activity.SLEEPING, g.activity());
            return null;
        });
        Platform.runLater(task);
        task.get(60, TimeUnit.SECONDS);
    }
}
