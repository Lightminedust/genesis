package org.genesis.fusion;

import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.genesis.fusion.FusionField.*;

/** Garde-fou : un effet calculé mais invisible à l'écran ne sert à rien. Chaque mesure compare la même image avec et
 * sans l'effet, et exige qu'il change assez de pixels pour se voir. */
public class VisibilityProbeTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); }); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }
    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }
    private static int[] pixels(Canvas c) {
        WritableImage i = c.snapshot(new SnapshotParameters(), null);
        int w = (int) i.getWidth(), h = (int) i.getHeight();
        int[] out = new int[w * h];
        i.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), out, 0, w);
        return out;
    }
    private static double report(String tag, int[] off, int[] on) {
        int changed = 0, maxDelta = 0;
        long sum = 0;
        for (int i = 0; i < on.length; i++) {
            int d = Math.abs(((on[i] >> 16) & 255) - ((off[i] >> 16) & 255))
                  + Math.abs(((on[i] >> 8) & 255) - ((off[i] >> 8) & 255))
                  + Math.abs((on[i] & 255) - (off[i] & 255));
            if (d > 0) changed++;
            maxDelta = Math.max(maxDelta, d);
            sum += d;
        }
        System.out.printf("%s pixels=%d changed=%d (%.2f%%) maxDelta=%d meanDelta=%.3f%n",
                tag, on.length, changed, 100.0 * changed / on.length, maxDelta, (double) sum / on.length);
        assertTrue(maxDelta > 60, tag + " : trop peu contrasté pour être vu (" + maxDelta + ")");
        return 100.0 * changed / on.length;
    }

    @Test void howVisibleAreTheIonisationTracks() throws Exception {
        var products = com.studio.genesis.grpc.CollisionRun.newBuilder().setContactFm(4).setEnergyKev(64)
                .setReducedMassMev(1125).setBarrierMev(.18).setFieldT(5.3);
        for (int k = 0; k <= 12; k++) {
            double t = k * 1e-22, x = k * 3.0;
            products.addSamples(com.studio.genesis.grpc.CollisionSample.newBuilder().setTimeS(t).setPhase("products")
                    .addBodies(com.studio.genesis.grpc.CollisionBody.newBuilder().setZ(2).setA(4).setRadiusFm(1.7).setMassMev(3727)
                            .addPosition(-x).addPosition(x * .3).addPosition(0).addVelocity(-1.3e7).addVelocity(4e6).addVelocity(0))
                    .addBodies(com.studio.genesis.grpc.CollisionBody.newBuilder().setZ(0).setA(1).setRadiusFm(.84).setMassMev(939.6)
                            .addPosition(x * 4).addPosition(-x * 1.2).addPosition(0).addVelocity(5.2e7).addVelocity(-1.6e7).addVelocity(0)));
        }
        var run = products.build();
        var timeline = new CollisionTimeline(run);
        fx(() -> {
            var r = new FusionRenderer();
            var canvas = new Canvas(900, 600);
            r.mapEnabled = false;
            r.debyeFm = 6.4e10;
            r.electronDensityM3 = 0;
            r.paint(canvas.getGraphicsContext2D(), 900, 600, run, timeline, .98);
            int[] off = pixels(canvas);
            r.electronDensityM3 = 2e20;
            r.paint(canvas.getGraphicsContext2D(), 900, 600, run, timeline, .98);
            assertTrue(report("TRACKS", off, pixels(canvas)) > .3, "les traces d'ionisation doivent se voir");
            return null;
        });
    }

    @Test void howVisibleIsTheFieldGrid() throws Exception {
        fx(() -> {
            var r = new FusionRenderer();
            var canvas = new Canvas(900, 600);
            var a = new Body(1, 2, "D", new V(-40, 0, 0), ZERO, ZERO, 2.77, .857);
            var b = new Body(1, 3, "T", new V(40, 0, 0), ZERO, ZERO, 2.27, 2.979);
            var state = new FusionRenderer.Snapshot(0, "ready", List.of(a, b), 0, 0, 0, 0);
            r.paintPortrait(canvas.getGraphicsContext2D(), 900, 600, state, 0);
            int[] off = pixels(canvas);
            r.paintPortrait(canvas.getGraphicsContext2D(), 900, 600, state, 5.3);
            int[] on = pixels(canvas);
            int changed = 0, maxDelta = 0;
            long sum = 0;
            for (int i = 0; i < on.length; i++) {
                int d = Math.abs(((on[i] >> 16) & 255) - ((off[i] >> 16) & 255))
                      + Math.abs(((on[i] >> 8) & 255) - ((off[i] >> 8) & 255))
                      + Math.abs((on[i] & 255) - (off[i] & 255));
                if (d > 0) changed++;
                maxDelta = Math.max(maxDelta, d);
                sum += d;
            }
            System.out.printf("GRID pixels=%d changed=%d (%.2f%%) maxDelta=%d meanDelta=%.3f%n",
                    on.length, changed, 100.0 * changed / on.length, maxDelta, (double) sum / on.length);
            // Volontairement discrète (demande du 2026-10-05) : présente, sans charger la scène.
            double share = 100.0 * changed / on.length;
            assertTrue(share > .2 && share < 2.5, "la trame du champ B : présente mais légère (" + share + " %)");
            return null;
        });
    }
}
