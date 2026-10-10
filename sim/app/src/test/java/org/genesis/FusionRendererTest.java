package org.genesis;

import com.studio.genesis.grpc.CollisionBody;
import com.studio.genesis.grpc.CollisionRun;
import com.studio.genesis.grpc.CollisionSample;
import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.genesis.FusionField.*;

class FusionRendererTest {
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

    private CollisionSample pair(double t, double separation, String phase) {
        var sample = CollisionSample.newBuilder().setTimeS(t).setPhase(phase).setKineticMev(.04).setPotentialMev(.02).setCoulombN(.1);
        for (int sign : new int[]{-1, 1}) sample.addBodies(CollisionBody.newBuilder().setZ(1).setA(sign < 0 ? 2 : 3)
                .setRadiusFm(2).setMagneticMoment(1).setSpinParity("1+").setMassMev(sign < 0 ? 1875.6 : 2808.9)
                .addPosition(sign * separation / 2).addPosition(0).addPosition(0).addSpin(0).addSpin(0).addSpin(1));
        return sample.build();
    }

    private CollisionRun run() {
        return CollisionRun.newBuilder().setContactFm(4).setEnergyKev(64).setReducedMassMev(1125).setBarrierMev(.18)
                .addSamples(pair(-2, 80, "approach")).addSamples(pair(-1, 30, "approach"))
                .addSamples(pair(1, 30, "scattered")).addSamples(pair(2, 80, "scattered")).build();
    }

    private static int[] pixels(Canvas canvas) {
        WritableImage image = canvas.snapshot(new SnapshotParameters(), null);
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] out = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), out, 0, w);
        return out;
    }

    @Test void approachZoomsInThenHoldsTheFrameWhereItBrakes() throws Exception {
        // De 400 fm jusqu'à 30 fm (le point le plus serré) : zone de freinage sous 2,5 × 30 = 75 fm.
        CollisionRun run = CollisionRun.newBuilder(run()).clearSamples()
                .addSamples(pair(-3, 400, "approach")).addSamples(pair(-2, 200, "approach")).addSamples(pair(-1, 80, "approach"))
                .addSamples(pair(0, 30, "approach")).addSamples(pair(1, 80, "scattered")).addSamples(pair(2, 400, "scattered")).build();
        CollisionTimeline timeline = new CollisionTimeline(run);
        fx(() -> {
            var renderer = new FusionRenderer();
            var canvas = new Canvas(400, 300);
            renderer.paint(canvas.getGraphicsContext2D(), 400, 300, run, timeline, .02);
            var early = FusionRenderer.snapshot(run, timeline.timeAt(.02)).bodies();
            double extent = renderer.extent;
            double gap = Math.abs(renderer.project(early.get(0).position())[0] - renderer.project(early.get(1).position())[0]);
            renderer.paint(canvas.getGraphicsContext2D(), 400, 300, run, timeline, .3);
            var late = FusionRenderer.snapshot(run, timeline.timeAt(.3)).bodies();
            double later = Math.abs(renderer.project(late.get(0).position())[0] - renderer.project(late.get(1).position())[0]);
            assertTrue(renderer.extent < extent * .8, "loin l'un de l'autre, la vue zoome à mesure qu'ils se rapprochent");
            assertTrue(later < gap - 10, "à l'écran, on doit encore les voir se rapprocher");
            renderer.paint(canvas.getGraphicsContext2D(), 400, 300, run, timeline, .5);
            double braking = renderer.extent;
            renderer.paint(canvas.getGraphicsContext2D(), 400, 300, run, timeline, .54);
            assertTrue(renderer.extent <= braking + 1e-9, "le cadre ne recule jamais pendant l'approche : il se resserre jusqu'au rebroussement");
            assertTrue(renderer.extent < 55, "au point le plus serré (30 fm), le cadre est serré lui aussi : " + renderer.extent);
            return null;
        });
    }

    @Test void coulombPolarizationCompressesEachNucleusAlongTheAxis() {
        // Goutte liquide dans le champ de l'autre : β₂ < 0 (comprimé le long de l'axe), minuscule pour un noyau léger.
        assertEquals(-.006, FusionRenderer.coulombBeta2(1, 3, 92, 13), .001);
        assertEquals(-.186, FusionRenderer.coulombBeta2(26, 56, 82, 13), .005);
        assertEquals(-.3, FusionRenderer.coulombBeta2(82, 208, 82, 15), 1e-12);
        assertTrue(FusionRenderer.coulombBeta2(26, 56, 82, 40) > FusionRenderer.coulombBeta2(26, 56, 82, 13));
    }

    @Test void trueScaleKeepsSizesAndDistancesProportional() {
        var renderer = new FusionRenderer();
        var small = new Body(2, 4, "He-4", new V(-10, 0, 0), ZERO, ZERO, 2, 0);
        var large = new Body(26, 56, "Fe-56", new V(10, 0, 0), ZERO, ZERO, 4.6, 0);
        assertEquals(1, renderer.nuclearMagnification());
        assertEquals(4.6 / 2, renderer.displayRadius(large) / renderer.displayRadius(small), 1e-12);
    }

    @Test void speciesDeterminePaletteAndNotTheirSide() {
        assertEquals(FusionRenderer.bodyColor(6, 12), FusionRenderer.bodyColor(6, 12));
        assertNotEquals(FusionRenderer.bodyColor(6, 12), FusionRenderer.bodyColor(8, 16));
        assertNotEquals(FusionRenderer.bodyColor(1, 2), FusionRenderer.bodyColor(1, 3));
    }

    @Test void renderingIsReproducibleForTheSameState() throws Exception {
        CollisionRun run = run();
        CollisionTimeline timeline = new CollisionTimeline(run);
        fx(() -> {
            var renderer = new FusionRenderer();
            var canvas = new Canvas(320, 240);
            renderer.paint(canvas.getGraphicsContext2D(), 320, 240, run, timeline, .3);
            int[] first = pixels(canvas);
            renderer.paint(canvas.getGraphicsContext2D(), 320, 240, run, timeline, .3);
            assertArrayEquals(first, pixels(canvas));
            return null;
        });
    }

    @Test void reportCarriesTheRepulsionAndTheWave() throws Exception {
        CollisionRun run = run();
        CollisionTimeline timeline = new CollisionTimeline(run);
        fx(() -> {
            var renderer = new FusionRenderer();
            var canvas = new Canvas(400, 300);
            renderer.paint(canvas.getGraphicsContext2D(), 400, 300, run, timeline, .3);
            var r = renderer.report;
            assertEquals("approach", r.stage);
            assertTrue(r.coulomb > 0);
            assertTrue(r.eta > 0 && r.eta < 3, "D + T à 64 keV : η < 1, une onde");
            assertEquals(2, r.tags.size());
            return null;
        });
    }

    @Test void productsAreReadInSlowMotionRightAfterTheRupture() {
        // La lecture des produits suit u^2,2 : la première moitié de la lecture couvre le cinquième du temps physique.
        var fused = CollisionRun.newBuilder(run()).clearSamples().setContactFm(4)
                .addSamples(pair(-2, 80, "approach")).addSamples(pair(0, 20, "approach"))
                .addSamples(CollisionSample.newBuilder().setTimeS(0).setPhase("products").addBodies(CollisionBody.newBuilder().setZ(2).setA(4).addPosition(0).addPosition(0).addPosition(0)))
                .addSamples(CollisionSample.newBuilder().setTimeS(1e-20).setPhase("products").addBodies(CollisionBody.newBuilder().setZ(2).setA(4).addPosition(5).addPosition(0).addPosition(0)))
                .build();
        var timeline = new CollisionTimeline(fused);
        double start = timeline.phaseStart("products"), span = 1 - start;
        double half = timeline.timeAt(start + span / 2);
        assertEquals(1e-20 * Math.pow(.5, CollisionTimeline.RUPTURE_EASE), half, 1e-24);
        assertTrue(half < .25e-20, "les premiers instants de la rupture prennent le plus de temps de lecture");
        assertEquals(1e-20, timeline.timeAt(1), 1e-30);
        // L'aller-retour reste cohérent : chercher un instant y ramène la même fraction.
        assertEquals(start + span / 2, timeline.fractionAt(half), 1e-9);
    }

    @Test void onlyChargedProductsLeaveAnIonisationTrack() throws Exception {
        // Deux produits nés ensemble : l'hélion ionise le plasma traversé, le neutron ne peut rien arracher.
        var products = CollisionRun.newBuilder(run()).clearSamples().setContactFm(4)
                .addSamples(pair(-2, 80, "approach")).addSamples(pair(0, 20, "approach"));
        for (int k = 0; k < 2; k++) {
            double t = k * 1e-21, x = k * 40;
            products.addSamples(CollisionSample.newBuilder().setTimeS(t).setPhase("products")
                    .addBodies(CollisionBody.newBuilder().setZ(2).setA(4).setRadiusFm(1.7).setMassMev(3727)
                            .addPosition(-x).addPosition(0).addPosition(0).addVelocity(-1.3e7).addVelocity(0).addVelocity(0))
                    .addBodies(CollisionBody.newBuilder().setZ(0).setA(1).setRadiusFm(.84).setMassMev(939.6)
                            .addPosition(x).addPosition(0).addPosition(0).addVelocity(5.2e7).addVelocity(0).addVelocity(0)));
        }
        CollisionRun run = products.build();
        CollisionTimeline timeline = new CollisionTimeline(run);
        fx(() -> {
            var renderer = new FusionRenderer();
            var canvas = new Canvas(400, 300);
            renderer.mapEnabled = false;
            renderer.electronDensityM3 = 2e20;
            renderer.debyeFm = 6.4e10;
            renderer.paint(canvas.getGraphicsContext2D(), 400, 300, run, timeline, 1);
            assertEquals("products", renderer.report.stage);
            assertEquals(1, renderer.report.tracks.size(), "seul le corps chargé laisse une trace");
            double[] track = renderer.report.tracks.get(0);
            assertEquals(2, track[0], "c'est l'hélion");
            assertTrue(track[2] > 0, "dE/dx strictement positif dans le plasma");
            renderer.electronDensityM3 = 0;
            renderer.paint(canvas.getGraphicsContext2D(), 400, 300, run, timeline, 1);
            assertTrue(renderer.report.tracks.isEmpty(), "dans le vide, rien n'est ionisé");
            return null;
        });
    }

    @Test void theMapHoldsAFixedCentreAndKeepsTheComputedDirections() throws Exception {
        // Deux produits partis dans des directions opposées, très loin : la carte les ramène sans changer leur cap.
        var products = CollisionRun.newBuilder(run()).clearSamples().setContactFm(4).setCompoundZ(2).setCompoundA(4);
        for (int k = 0; k <= 6; k++) {
            double t = k * 1e-21, x = k * 900;
            products.addSamples(CollisionSample.newBuilder().setTimeS(t).setPhase("products")
                    .addBodies(CollisionBody.newBuilder().setZ(2).setA(4).setRadiusFm(1.7).setMassMev(3727)
                            .addPosition(-x * .2).addPosition(0).addPosition(0).addVelocity(-2.6e6).addVelocity(0).addVelocity(0))
                    .addBodies(CollisionBody.newBuilder().setZ(0).setA(1).setRadiusFm(.84).setMassMev(939.6)
                            .addPosition(x).addPosition(x).addPosition(0).addVelocity(5.2e7).addVelocity(5.2e7).addVelocity(0)));
        }
        CollisionRun run = products.build();
        CollisionTimeline timeline = new CollisionTimeline(run);
        fx(() -> {
            var renderer = new FusionRenderer();
            var canvas = new Canvas(900, 600);
            renderer.mapEnabled = true;
            renderer.paint(canvas.getGraphicsContext2D(), 900, 600, run, timeline, 1);
            assertTrue(renderer.mapCompression > 1, "les distances sont comprimées");
            assertEquals(2, renderer.report.tags.size(), "les deux corps restent à l'écran");
            FusionRenderer.Tag centre = null, other = null;
            for (FusionRenderer.Tag t : renderer.report.tags) {
                if (t.body().z() == 2) centre = t;
                else other = t;
            }
            assertNotNull(centre);
            assertNotNull(other);
            // Les deux sont dans le cadre : c'est tout l'objet de la carte.
            for (FusionRenderer.Tag t : renderer.report.tags) {
                assertTrue(t.x() > 0 && t.x() < 900, "dans le cadre en x : " + t.x());
                assertTrue(t.y() > 0 && t.y() < 600, "dans le cadre en y : " + t.y());
            }
            return null;
        });
    }

    @Test void clickingABodyZoomsOnItWithoutMovingTheMapCentre() throws Exception {
        var products = CollisionRun.newBuilder(run()).clearSamples().setContactFm(4).setCompoundZ(2).setCompoundA(4);
        for (int k = 0; k <= 6; k++) {
            double t = k * 1e-21, x = k * 900;
            products.addSamples(CollisionSample.newBuilder().setTimeS(t).setPhase("products")
                    .addBodies(CollisionBody.newBuilder().setZ(2).setA(4).setRadiusFm(1.7).setMassMev(3727)
                            .addPosition(-x * .2).addPosition(0).addPosition(0).addVelocity(-2.6e6).addVelocity(0).addVelocity(0))
                    .addBodies(CollisionBody.newBuilder().setZ(0).setA(1).setRadiusFm(.84).setMassMev(939.6)
                            .addPosition(x).addPosition(x).addPosition(0).addVelocity(5.2e7).addVelocity(5.2e7).addVelocity(0)));
        }
        CollisionRun run = products.build();
        CollisionTimeline timeline = new CollisionTimeline(run);
        fx(() -> {
            var renderer = new FusionRenderer();
            var canvas = new Canvas(900, 600);
            renderer.paint(canvas.getGraphicsContext2D(), 900, 600, run, timeline, 1);
            double whole = renderer.extent, compression = renderer.report.mapCompression;
            // Un clic sur le neutron : la vue s'approche (l'étendue baisse), et le centre n'est pas redéfini.
            renderer.mapFocusZ = 0;
            renderer.mapFocusA = 1;
            for (int i = 0; i < 90; i++) {
                renderer.anim += 1 / 30.0;
                renderer.paint(canvas.getGraphicsContext2D(), 900, 600, run, timeline, 1);
            }
            assertTrue(renderer.extent < whole * .5, "la vue a zoomé sur le neutron : " + renderer.extent + " contre " + whole);
            assertEquals(compression, renderer.report.mapCompression, 1e-9, "la carte n'est pas recalculée");
            // Un clic dans le vide : retour à la carte entière.
            renderer.mapFocusZ = renderer.mapFocusA = -1;
            for (int i = 0; i < 120; i++) {
                renderer.anim += 1 / 30.0;
                renderer.paint(canvas.getGraphicsContext2D(), 900, 600, run, timeline, 1);
            }
            assertEquals(whole, renderer.extent, whole * .02, "la vue revient à la carte entière");
            return null;
        });
    }

    @Test void theMapEngagesJustBeforeTheFirstEjectionAndNotBefore() throws Exception {
        var fused = CollisionRun.newBuilder(run()).clearSamples().setContactFm(4)
                .addSamples(pair(-2, 80, "approach")).addSamples(pair(0, 20, "approach"))
                .addSamples(CollisionSample.newBuilder().setTimeS(0).setPhase("products").addBodies(CollisionBody.newBuilder().setZ(2).setA(4).setRadiusFm(1.7).addPosition(0).addPosition(0).addPosition(0)))
                .addSamples(CollisionSample.newBuilder().setTimeS(1e-20).setPhase("products").addBodies(CollisionBody.newBuilder().setZ(2).setA(4).setRadiusFm(1.7).addPosition(5).addPosition(0).addPosition(0)))
                .build();
        CollisionTimeline timeline = new CollisionTimeline(fused);
        fx(() -> {
            var renderer = new FusionRenderer();
            var canvas = new Canvas(600, 400);
            renderer.paint(canvas.getGraphicsContext2D(), 600, 400, fused, timeline, .05);
            assertFalse(renderer.report.map, "pendant l'approche, la scène est la vraie");
            renderer.paint(canvas.getGraphicsContext2D(), 600, 400, fused, timeline, 1);
            assertTrue(renderer.report.map, "une fois les produits partis, la carte est là");
            renderer.mapEnabled = false;
            renderer.paint(canvas.getGraphicsContext2D(), 600, 400, fused, timeline, 1);
            assertFalse(renderer.report.map, "l'utilisateur peut l'éteindre");
            return null;
        });
    }

    @Test void tunnelFramesTheContactNotTheFarTurningPoint() throws Exception {
        // Sous la barrière, le rebroussement est loin (80 fm) : le passage descend vers le contact (4 fm), pas au large.
        var fused = CollisionRun.newBuilder(run()).clearSamples().setContactFm(4)
                .addSamples(pair(-2, 160, "approach")).addSamples(pair(0, 80, "approach"))
                .addSamples(CollisionSample.newBuilder().setTimeS(0).setPhase("merge")
                        .addBodies(CollisionBody.newBuilder().setZ(1).setA(2).setRadiusFm(2).addPosition(-2).addPosition(0).addPosition(0))
                        .addBodies(CollisionBody.newBuilder().setZ(1).setA(3).setRadiusFm(2).addPosition(2).addPosition(0).addPosition(0)))
                .addSamples(CollisionSample.newBuilder().setTimeS(1e-21).setPhase("products").addBodies(CollisionBody.newBuilder().setZ(2).setA(4).setRadiusFm(2).addPosition(0).addPosition(0).addPosition(0)))
                .build();
        CollisionTimeline timeline = new CollisionTimeline(fused);
        assertTrue(timeline.tunnels());
        final double start = timeline.phaseStart("tunnel");
        double tunnelEnd = start;
        for (var st : timeline.stages()) if (st.name().equals("tunnel")) tunnelEnd = st.end();
        final double end = tunnelEnd;
        fx(() -> {
            var renderer = new FusionRenderer();
            var canvas = new Canvas(400, 300);
            renderer.paint(canvas.getGraphicsContext2D(), 400, 300, fused, timeline, start + 1e-4);
            double wide = renderer.extent;
            renderer.paint(canvas.getGraphicsContext2D(), 400, 300, fused, timeline, end - 1e-4);
            assertTrue(renderer.extent < wide * .5, "le cadre se resserre pendant le passage : " + renderer.extent + " contre " + wide);
            assertTrue(renderer.extent < 14, "à la fin du passage, le cadre tient sur le contact : " + renderer.extent);
            return null;
        });
    }

    @Test void timelineInsertsTunnelAndMergeOnlyWhenTheyFused() {
        var elastic = new CollisionTimeline(run());
        assertFalse(elastic.tunnels());
        assertEquals(-1, elastic.flashFraction());
        var fused = CollisionRun.newBuilder(run()).clearSamples().setContactFm(4)
                .addSamples(pair(-2, 80, "approach")).addSamples(pair(0, 20, "approach"))
                .addSamples(CollisionSample.newBuilder().setTimeS(0).setPhase("products").addBodies(CollisionBody.newBuilder().setZ(2).setA(4).addPosition(0).addPosition(0).addPosition(0)))
                .addSamples(CollisionSample.newBuilder().setTimeS(1e-20).setPhase("products").addBodies(CollisionBody.newBuilder().setZ(2).setA(4).addPosition(5).addPosition(0).addPosition(0)))
                .build();
        var timeline = new CollisionTimeline(fused);
        assertTrue(timeline.tunnels());
        assertTrue(timeline.flashFraction() > 0);
        assertEquals(List.of("approach", "tunnel", "merge", "products"), timeline.stages().stream().map(CollisionTimeline.Stage::name).toList());
        assertEquals(1e-20, timeline.timeAt(1), 1e-30);
    }

    @Test void electricMapIsVisibleAndRespondsToTheSuppliedState() throws Exception {
        fx(() -> {
            var renderer = new FusionRenderer();
            renderer.showMagnetic = renderer.showWave = false;
            var canvas = new Canvas(400, 300);
            var a = new Body(3, 7, "Li", new V(-12, 0, 0), ZERO, ZERO, 2.8, 0);
            var b = new Body(118, 294, "Og", new V(12, 0, 0), ZERO, ZERO, 8.6, 0);
            var state = new FusionRenderer.Snapshot(0, "ready", List.of(a,b), 0,0,0,0);
            renderer.showElectric = false;
            renderer.paintPortrait(canvas.getGraphicsContext2D(),400,300,state,0);
            int[] without = pixels(canvas);
            renderer.showElectric = true;
            renderer.paintPortrait(canvas.getGraphicsContext2D(),400,300,state,0);
            int[] with = pixels(canvas);
            int changed = 0;
            for(int i=0;i<with.length;i++) if(with[i]!=without[i]) changed++;
            assertTrue(changed>with.length/4,"le PixelBuffer doit réellement apparaître sur le Canvas");
            var displaced = new Body(118,294,"Og",new V(5,8,0),ZERO,ZERO,8.6,0);
            renderer.paintPortrait(canvas.getGraphicsContext2D(),400,300,
                    new FusionRenderer.Snapshot(0,"ready",List.of(a,displaced),0,0,0,0),0);
            assertFalse(java.util.Arrays.equals(with,pixels(canvas)),"la carte suit les positions reçues");
            return null;
        });
    }

    @Test void electricMapUsesTheCatalogColorOfEachIsotope() throws Exception {
        fx(() -> {
            FusionRenderer.registerColor(79, "#FFD123");
            FusionRenderer.registerColor(92, "#008FFF");
            var renderer = new FusionRenderer();
            renderer.showMagnetic = renderer.showWave = false;
            var canvas = new Canvas(480, 320);
            var gold = new Body(79, 197, "Au", new V(-25, 0, 0), ZERO, ZERO, 7, 0);
            var uranium = new Body(92, 238, "U", new V(25, 0, 0), ZERO, ZERO, 7.5, 0);
            var state = new FusionRenderer.Snapshot(0, "ready", List.of(gold, uranium), 0, 0, 0, 0);
            Field imageField = FusionRenderer.class.getDeclaredField("pressureImage");
            imageField.setAccessible(true);
            renderer.paintPortrait(canvas.getGraphicsContext2D(), 480, 320, state, 0);
            WritableImage map = (WritableImage) imageField.get(renderer);
            int[] before = new int[(int) (map.getWidth() * map.getHeight())];
            map.getPixelReader().getPixels(0, 0, (int) map.getWidth(), (int) map.getHeight(),
                    PixelFormat.getIntArgbInstance(), before, 0, (int) map.getWidth());
            FusionRenderer.registerColor(79, "#008FFF");
            FusionRenderer.registerColor(92, "#FFD123");
            renderer.paintPortrait(canvas.getGraphicsContext2D(), 480, 320, state, 0);
            int[] after = new int[before.length];
            map.getPixelReader().getPixels(0, 0, (int) map.getWidth(), (int) map.getHeight(),
                    PixelFormat.getIntArgbInstance(), after, 0, (int) map.getWidth());
            assertFalse(java.util.Arrays.equals(before, after), "la grille du champ doit reprendre les couleurs envoyées pour Au et U");
            FusionRenderer.registerColor(79, "#FFD123");
            FusionRenderer.registerColor(92, "#008FFF");
            return null;
        });
    }

    @Test void electricMapKeepsWeakAndStrongFieldsOnOneScale() throws Exception {
        fx(() -> {
            var renderer = new FusionRenderer();
            renderer.showMagnetic = renderer.showWave = false;
            var canvas = new Canvas(720, 480);
            var hydrogen = new Body(1, 2, "D", new V(-30, 0, 0), ZERO, ZERO, 2.77, 0);
            var uranium = new Body(92, 238, "U", new V(30, 0, 0), ZERO, ZERO, 8.04, 0);
            renderer.paintPortrait(canvas.getGraphicsContext2D(), 720, 480,
                    new FusionRenderer.Snapshot(0, "ready", List.of(hydrogen, uranium), 0, 0, 0, 0), 0);
            Field imageField = FusionRenderer.class.getDeclaredField("pressureImage");
            imageField.setAccessible(true);
            WritableImage map = (WritableImage) imageField.get(renderer);
            var small = renderer.report.sources.get(0);
            var large = renderer.report.sources.get(1);
            double dx = small.x() - large.x(), dy = small.y() - large.y(), norm = Math.hypot(dx, dy);
            assertTrue(norm > 0);
            int light = fieldAlpha(map, small.x() + dx / norm * 1.5 * small.radius() / small.fmPerPx(),
                    small.y() + dy / norm * 1.5 * small.radius() / small.fmPerPx(), 720, 480);
            int heavy = fieldAlpha(map, large.x() - dx / norm * 1.5 * large.radius() / large.fmPerPx(),
                    large.y() - dy / norm * 1.5 * large.radius() / large.fmPerPx(), 720, 480);
            assertTrue(heavy > light + 30, "à leur surface, U doit montrer un champ plus intense que D sur la même échelle");
            return null;
        });
    }

    @Test void potentialMapFindsTheComputedColBetweenTwoCharges() throws Exception {
        fx(() -> {
            var renderer = new FusionRenderer();
            renderer.showMagnetic = renderer.showWave = false;
            var canvas = new Canvas(720, 480);
            var strontium = new Body(38, 88, "Sr", new V(-20, 0, 0), ZERO, ZERO, 5.9, 0);
            var phosphorus = new Body(15, 31, "P", new V(20, 0, 0), ZERO, ZERO, 4.3, 0);
            renderer.paintPortrait(canvas.getGraphicsContext2D(), 720, 480,
                    new FusionRenderer.Snapshot(0, "ready", List.of(strontium, phosphorus), 0, 0, 0, 0), 0);
            assertEquals(1, renderer.report.saddles.size(), "un seul col entre deux charges");
            double[] col = renderer.report.saddles.get(0);
            var a = renderer.report.sources.get(0);
            var b = renderer.report.sources.get(1);
            double r1 = Math.hypot(col[0] - a.x(), col[1] - a.y()) * a.fmPerPx(), r2 = Math.hypot(col[0] - b.x(), col[1] - b.y()) * b.fmPerPx();
            // Au col, E₁ + E₂ = 0 : Z₁/r₁² = Z₂/r₂², et U = eφ = ke²(Z₁/r₁ + Z₂/r₂).
            assertEquals(Math.sqrt(38 / 15.0), r1 / r2, .02);
            assertEquals(1.43996 * (38 / r1 + 15 / r2), col[2], .01 * col[2]);
            assertTrue(renderer.report.potentialTop > col[2], "le col monte quand les surfaces se touchent");
            return null;
        });
    }

    private static int fieldAlpha(WritableImage map, double x, double y, double width, double height) {
        int px = Math.max(0, Math.min((int) map.getWidth() - 1, (int) (x * map.getWidth() / width)));
        int py = Math.max(0, Math.min((int) map.getHeight() - 1, (int) (y * map.getHeight() / height)));
        return map.getPixelReader().getArgb(px, py) >>> 24;
    }

    @Test void magneticRenderingIncludesMovingSpinZeroNuclei() throws Exception {
        fx(() -> {
            var renderer = new FusionRenderer();
            renderer.showElectric = renderer.showWave = false;
            var canvas = new Canvas(400,300);
            var resting = new Body(8,16,"O",ZERO,ZERO,ZERO,3,0);
            renderer.paintPortrait(canvas.getGraphicsContext2D(),400,300,
                    new FusionRenderer.Snapshot(0,"ready",List.of(resting),0,0,0,0),0);
            int[] still = pixels(canvas);
            var moving = new Body(8,16,"O",ZERO,new V(2e7,0,0),ZERO,3,0);
            renderer.paintPortrait(canvas.getGraphicsContext2D(),400,300,
                    new FusionRenderer.Snapshot(0,"ready",List.of(moving),0,0,0,0),0);
            assertFalse(java.util.Arrays.equals(still,pixels(canvas)),"B dû à v×E existe même si le spin est nul");
            return null;
        });
    }

    @Test void magneticFieldMarkersMoveWithoutDrawingFixedOrbits() throws Exception {
        fx(() -> {
            var renderer = new FusionRenderer();
            renderer.showElectric = renderer.showWave = false;
            var canvas = new Canvas(480, 320);
            var tritium = new Body(1, 3, "T", new V(-12, 0, 0), ZERO, new V(0, 0, 1), 2.27, 2.98);
            var gold = new Body(79, 197, "Au", new V(12, 0, 0), ZERO, new V(0, 1, 0), 7.58, .15);
            var state = new FusionRenderer.Snapshot(0, "ready", List.of(tritium, gold), 0, 0, 0, 0);
            renderer.anim = 0;
            renderer.paintPortrait(canvas.getGraphicsContext2D(), 480, 320, state, 5.3);
            int[] first = pixels(canvas);
            renderer.anim = .1;
            renderer.paintPortrait(canvas.getGraphicsContext2D(), 480, 320, state, 5.3);
            assertFalse(java.util.Arrays.equals(first, pixels(canvas)),
                    "B doit être réévalué et ses repères se déplacer même quand les noyaux ne bougent pas");
            return null;
        });
    }

    private CollisionSample fragments(double t) {
        var sample = CollisionSample.newBuilder().setTimeS(t).setPhase("evaporation");
        for (int sign : new int[]{-1, 1}) sample.addBodies(CollisionBody.newBuilder()
                .setZ(sign < 0 ? 91 : 80).setA(sign < 0 ? 235 : 200).setRadiusFm(7)
                .setMassMev(200000).setKineticMev(200)
                .addPosition(sign * (8 + 1.5e7 * t * 1e15)).addPosition(0).addPosition(0)
                .addVelocity(sign * 1.5e7).addVelocity(0).addVelocity(0));
        return sample.build();
    }

    @Test void sameCountScissionKeepsComputedRecoilVisible() throws Exception {
        var contact = pair(0, 16, "contact");
        var born = fragments(0);
        assertTrue(FusionRenderer.isEmission(contact, born), "deux noyaux deviennent deux fragments : c'est une rupture");
        assertFalse(FusionRenderer.isEmission(born, fragments(1e-21)), "le transport des mêmes fragments n'est pas une nouvelle rupture");
        var run = CollisionRun.newBuilder().setContactFm(16).setCompoundA(435).setCompoundZ(171)
                .addSamples(pair(-2e-21, 80, "approach")).addSamples(pair(-1e-21, 16, "approach"))
                .addSamples(pair(-1e-21, 16, "contact")).addSamples(contact)
                .addSamples(born).addSamples(fragments(1e-21)).addSamples(fragments(1e-20)).build();
        var timeline = new CollisionTimeline(run);
        fx(() -> {
            var renderer = new FusionRenderer();
            var canvas = new Canvas(640, 480);
            double birth = timeline.phaseStart("evaporation");
            renderer.paint(canvas.getGraphicsContext2D(), 640, 480, run, timeline, birth + .15 / timeline.durationSeconds());
            assertTrue(renderer.report.recoil);
            assertEquals(0, renderer.camera.targetX, 1e-9, "observer la rupture sans annuler visuellement le recul");
            assertEquals(-1.5e7, renderer.report.tags.stream().filter(t -> t.body().z() == 91)
                    .findFirst().orElseThrow().body().velocity().x(), 1e-6);
            renderer.paint(canvas.getGraphicsContext2D(), 640, 480, run, timeline, birth + .7 / timeline.durationSeconds());
            assertTrue(renderer.report.recoil, "la caméra doit laisser les fragments s'éloigner du point de rupture");
            assertEquals(0, renderer.camera.targetX, 1e-9);
            return null;
        });
    }

    private CollisionRun gammaRun(double gammaMev) {
        var emitted = CollisionSample.newBuilder().setPhase("evaporation").setTimeS(1e-21);
        for (int sign : new int[]{-1, 1}) emitted.addBodies(CollisionBody.newBuilder()
                .setZ(sign < 0 ? 91 : 80).setA(sign < 0 ? 235 : 200).setRadiusFm(7).setMassMev(200000)
                .addPosition(sign * 8).addPosition(0).addPosition(0)
                .addVelocity(sign * 1.5e7).addVelocity(0).addVelocity(0));
        emitted.addBodies(CollisionBody.newBuilder().setKind("gamma").setKineticMev(gammaMev)
                .addPosition(0).addPosition(0).addPosition(0)
                .addVelocity(0).addVelocity(0).addVelocity(299792458));
        return CollisionRun.newBuilder().setContactFm(16).setCompoundA(435).setCompoundZ(171)
                .addSamples(pair(-2e-21, 80, "approach"))
                .addSamples(pair(0, 16, "contact"))
                .addSamples(emitted)
                .addSamples(CollisionSample.newBuilder(emitted.build()).setTimeS(2e-21))
                .build();
    }

    @Test void radiativeExposureRequiresAComputedGammaEnergy() throws Exception {
        fx(() -> {
            var canvas = new Canvas(360, 270);
            var dark = gammaRun(0);
            var darkTime = new CollisionTimeline(dark);
            var renderer = new FusionRenderer();
            double fraction = darkTime.phaseStart("evaporation") + .1 / darkTime.durationSeconds();
            renderer.paint(canvas.getGraphicsContext2D(), 360, 270, dark, darkTime, fraction);
            int[] noPhotonEnergy = pixels(canvas);
            var bright = gammaRun(10);
            renderer = new FusionRenderer();
            renderer.paint(canvas.getGraphicsContext2D(), 360, 270, bright, new CollisionTimeline(bright), fraction);
            assertFalse(java.util.Arrays.equals(noPhotonEnergy, pixels(canvas)),
                    "le photon doit changer le champ lumineux selon son énergie, sans front dessiné pour une scission seule");
            return null;
        });
    }
}
