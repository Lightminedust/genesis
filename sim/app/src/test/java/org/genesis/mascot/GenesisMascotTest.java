package org.genesis.mascot;

import com.studio.genesis.grpc.SimEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GenesisMascotTest {
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

    @Test void herBodyIsSolidAndAlive() {
        GenesisBlob blob = new GenesisBlob();
        GenesisBlob.Input in = new GenesisBlob.Input();
        for (int k = 0; k < 1200; k++) {
            blob.step(1 / 60.0, in);
            assertTrue(Math.hypot(blob.x(), blob.y()) < .3, "elle flotte au milieu de la boule");
            assertTrue(blob.tall() > .8 && blob.tall() < 1.25, "sa boule reste ronde, à peu près : " + blob.tall());
            assertTrue(blob.ear(0) < 0 && blob.ear(1) > 0, "ses deux oreilles restent chacune de son côté");
        }
        // la souris à droite : elle penche vers elle
        double before = blob.x();
        in.pointer = true;
        in.pointerX = .6;
        in.pointerY = 0;
        for (int k = 0; k < 180; k++) blob.step(1 / 60.0, in);
        assertTrue(blob.x() > before + .05 && blob.x() > .12, "elle penche vers la souris : " + blob.x());
        // elle parle : son cœur de lumière bat
        in.pointer = false;
        in.talking = true;
        double brightest = 0;
        for (int k = 0; k < 120; k++) {
            blob.step(1 / 60.0, in);
            brightest = Math.max(brightest, blob.core());
        }
        assertTrue(brightest > 1.15, "son cœur bat quand elle parle : " + brightest);
        // elle dort : elle se pose au fond, ses oreilles tombent, son cœur baisse
        in.talking = false;
        in.sleep = true;
        for (int k = 0; k < 480; k++) blob.step(1 / 60.0, in);
        assertTrue(blob.y() > .3, "elle s'est posée au fond : " + blob.y());
        assertTrue(blob.ear(0) < -1 && blob.ear(1) > 1, "ses oreilles sont tombées : " + blob.ear(0) + ", " + blob.ear(1));
        assertTrue(blob.core() < .6, "son cœur a baissé : " + blob.core());
    }

    @Test void herMatterDividesAndGathers() {
        GenesisFlask flask = new GenesisFlask();
        for (int i = 0; i < GenesisFlask.N; i++) flask.spine(i, .3 - .05 * i, 0, .1 - .005 * i);
        flask.step(1 / 60.0);
        assertEquals(1, flask.pieces());
        flask.transform();
        int most = 1;
        double darkest = 0;
        for (int k = 0; k < 280; k++) {
            flask.step(1 / 60.0);
            most = Math.max(most, flask.pieces());
            darkest = Math.max(darkest, flask.dark());
        }
        assertTrue(most >= 2, "elle se divise : " + most);
        assertTrue(darkest > .95, "elle devient matière noire : " + darkest);
        assertTrue(flask.limbs() < .05, "ses pattes et ses branchies sont rentrées");
        for (int k = 0; k < 420; k++) flask.step(1 / 60.0);
        assertEquals(1, flask.pieces(), "elle s'est rassemblée");
        assertTrue(flask.dark() < .05 && !flask.morphing(), "elle a retrouvé son corps de gel");
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
            for (int k = 0; k < 300; k++) g.tick(1 / 30.0);                     // si elle s'était changée d'elle-même, elle a fini
            assertEquals(1, g.pieces(), "elle est entière");
            g.poke();
            int most = 1;
            for (int k = 0; k < 150; k++) { g.tick(1 / 30.0); most = Math.max(most, g.pieces()); }
            assertTrue(most >= 2, "un clic : elle se divise en masses de matière noire : " + most);
            for (int k = 0; k < 3000; k++) g.tick(1 / 30.0);                    // rien pendant 100 s : elle s'endort
            assertEquals(GenesisMascot.Mood.SLEEPY, g.mood());
            assertEquals(GenesisMascot.Activity.SLEEPING, g.activity());
            return null;
        });
        Platform.runLater(task);
        task.get(60, TimeUnit.SECONDS);
    }
}
