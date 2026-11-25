package org.genesis.app;

import com.studio.genesis.grpc.UniverseFrame;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L'écran d'attente d'un laboratoire : le moteur change de laboratoire en quelques millisecondes, l'écran doit donc
 * couvrir tout de suite (on ne voit jamais l'ancien laboratoire ni l'atome entre les deux) et sa carte n'apparaître
 * que si l'attente dure.
 */
public class LabLoaderTest {
    private static final double STEP = 1 / 60.0;

    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); }); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    private static void onFx(Runnable body) throws Exception {
        FutureTask<Void> task = new FutureTask<>(body, null);
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    private static void run(LabLoader loader, double seconds) {
        for (int k = 0; k < Math.round(seconds / STEP); k++) loader.tick(STEP);
    }

    @Test void itCoversTheScreenAtOnce() throws Exception {
        onFx(() -> {
            LabLoader loader = new LabLoader();
            loader.show("Ouverture", "…", f -> false, null);
            // Avant toute image : déjà opaque, rien ne se voit à travers.
            assertTrue(loader.getNode().isVisible());
            assertEquals(1, loader.getNode().getOpacity(), 1e-12);
            loader.tick(0.1);                                 // un premier pas long (la capture, un labo qui se prépare)
            assertEquals(1, loader.getNode().getOpacity(), 1e-12);
            assertEquals(0, loader.cardForTest(), 1e-12);
        });
    }

    @Test void aQuickAnswerIsAPlainFadeWithoutTheCard() throws Exception {
        onFx(() -> {
            LabLoader loader = new LabLoader();
            loader.show("Ouverture", "…", f -> true, null);
            run(loader, 0.03);                                // le moteur répond en 30 ms (mesuré : 1 à 50 ms)
            loader.onFrame(UniverseFrame.getDefaultInstance());
            assertFalse(loader.isShowing());
            double previous = loader.getNode().getOpacity();
            for (int k = 0; k < 40; k++) {
                loader.tick(STEP);
                double now = loader.getNode().getOpacity();
                assertTrue(now <= previous, "le fondu de sortie ne revient pas en arrière");
                previous = now;
                assertEquals(0, loader.cardForTest(), 1e-12, "la carte ne clignote pas pour une attente brève");
            }
            assertFalse(loader.getNode().isVisible());
        });
    }

    @Test void theCardAppearsWhenTheEngineTakesItsTime() throws Exception {
        onFx(() -> {
            LabLoader loader = new LabLoader();
            loader.show("Ouverture du laboratoire moléculaire", "…", f -> false, null);
            run(loader, LabLoader.CARD_DELAY - 0.05);
            assertEquals(0, loader.cardForTest(), 1e-12);
            run(loader, LabLoader.CARD_FADE + 0.1);
            assertEquals(1, loader.cardForTest(), 1e-12);
            assertEquals(1, loader.getNode().getOpacity(), 1e-12);
            // Un écran en remplace un autre pendant l'attente : la carte reste, rien ne clignote.
            loader.show("Retour à l'atome", "…", f -> false, null);
            loader.tick(STEP);
            assertEquals(1, loader.cardForTest(), 1e-12);
        });
    }

    @Test void withoutAWindowThereIsNoStillImage() throws Exception {
        onFx(() -> {
            LabLoader loader = new LabLoader();
            loader.show("Ouverture", "…", f -> false, null);
            assertEquals(0, loader.frozenForTest(), 1e-12);   // aperçus hors fenêtre : le fond seul
        });
    }
}
