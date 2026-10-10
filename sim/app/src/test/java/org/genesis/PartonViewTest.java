package org.genesis;

import com.studio.genesis.grpc.PartonCurve;
import com.studio.genesis.grpc.PartonEvent;
import com.studio.genesis.grpc.PartonRecord;
import com.studio.genesis.grpc.PartonState;
import com.studio.genesis.grpc.UniverseFrame;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartonViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); }); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    private static PartonRecord parton(int id, String kind, String flavor, boolean valence, double born, double died) {
        return PartonRecord.newBuilder().setId(id).setKind(kind).setFlavor(flavor).setX(.1).setBornFmc(born).setDiedFmc(died)
                .setValence(valence).addPositionFm(0).addPositionFm(0).addPositionFm(0).build();
    }

    private static final List<PartonRecord> POPULATION = List.of(
            parton(0, "q", "u", true, 0, -1), parton(1, "g", "", false, 0, .2), parton(2, "g", "", false, .2, -1),
            parton(3, "q", "d", false, .2, -1), parton(4, "qbar", "d", false, .2, -1));

    @Test void aPartonLivesBetweenItsBirthAndItsDeath() {
        PartonRecord gluon = POPULATION.get(1), child = POPULATION.get(2);
        assertTrue(PartonView.alive(gluon, .1));
        assertFalse(PartonView.alive(gluon, .2), "mort à l'instant de l'événement");
        assertFalse(PartonView.alive(child, .1));
        assertTrue(PartonView.alive(child, .3));
        assertEquals(1.0, PartonView.opacity(POPULATION.get(0), 5, .1), 1e-12, "ceux du départ sont là d'emblée");
        assertEquals(.5, PartonView.opacity(child, .25, .1), 1e-12, "fondu d'apparition");
        assertEquals(.5, PartonView.opacity(gluon, .15, .1), 1e-12, "fondu de disparition");
    }

    @Test void populationsAndEventTalliesFollowTheClock() {
        assertArrayEquals(new int[]{1, 0, 0, 1}, PartonView.counts(POPULATION, .1));
        assertArrayEquals(new int[]{1, 1, 1, 1}, PartonView.counts(POPULATION, .3));
        List<PartonEvent> events = List.of(
                PartonEvent.newBuilder().setTimeFmc(.1).setKind("g->gg").build(),
                PartonEvent.newBuilder().setTimeFmc(.2).setKind("g->qqbar").build(),
                PartonEvent.newBuilder().setTimeFmc(.4).setKind("qqbar->g").build());
        assertArrayEquals(new int[]{1, 0, 1, 0, 0, 0}, PartonView.eventCounts(events, .3));
        assertArrayEquals(new int[]{1, 1, 1, 0, 0, 0}, PartonView.eventCounts(events, 1));
        assertEquals("g → q q̄", PartonView.name("g->qqbar"));
    }

    @Test void colourAndAnticolourAreDistinctOnScreen() {
        for (int c = 0; c < 3; c++) assertNotEquals(PartonView.colorOf(c, false), PartonView.colorOf(c, true));
        assertEquals(PartonView.colorOf(0, false), PartonView.colorOf(3, false), "indices modulo 3");
    }

    @Test void viewConsumesAComputedRun() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            var view = new PartonView(new PartonView.Actions() {
                @Override public void back() {}
                @Override public void resolution(double q2, double xMin) {}
                @Override public void lastStrike(double xMin) {}
                @Override public void reset() {}
            });
            new Scene(view.getNode(), 1200, 760);
            view.getNode().applyCss();
            view.getNode().layout();
            var curve = PartonCurve.newBuilder().setName("gluon").addX(1e-3).addX(.1).addXf(2).addXf(.3).build();
            var state = PartonState.newBuilder().setActive(true).setRun(1).setHadron("proton").setQ2Gev2(10).setXMin(1e-3)
                    .setAlphaS(.27).setTauFmc(.062).setDurationFmc(.8).setMomentumValence(.37).setMomentumSea(.1).setMomentumGluon(.53)
                    .setMomentumTotal(1).setNGluon(40).setNSeaQuark(7).addCurves(curve).addAllPartons(POPULATION)
                    .addEvents(PartonEvent.newBuilder().setTimeFmc(.2).setKind("g->qqbar").addParents(1).addChildren(3).addChildren(4)).build();
            view.applyFrame(UniverseFrame.newBuilder().setPartons(state).build());
            view.seek(.25);
            view.tick(.016);
            return null;
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }
}
