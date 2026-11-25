package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeSchemeStep;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** La vue 2.5D du chemin d'une étape (le replay) : ce qu'elle lit du moteur, sa vue calculée, ses nombres. */
class SchemeStageTest {
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

    /**
     * Deux carbones qui s'approchent dans le plan z = 0 (trois images, la liaison se forme), un hydrogène au-dessus du
     * premier ; un saut de branche entre les images 1 et 2 ; une flèche courbe du doublet de C1 vers C2.
     */
    private static MoleculeSchemeStep step() {
        MoleculeSchemeStep.Builder b = MoleculeSchemeStep.newBuilder().addNumbers(6).addNumbers(6).addNumbers(1);
        double[][][] frames = {
                {{-2.0, 0, 0}, {2.0, 0, 0}, {-2.0, 1.1, 0}},
                {{-1.2, 0.4, 0}, {1.2, -0.4, 0}, {-1.2, 1.5, 0}},
                {{-0.77, 0.1, 0}, {0.77, -0.1, 0}, {-0.77, 1.2, 0}}};
        for (double[][] frame : frames) for (double[] p : frame) for (double v : p) b.addPath((float) v);
        double[][] charges = {{0, 0, 0}, {-0.1, 0.05, 0.05}, {-0.2, 0.1, 0.1}};
        for (double[] row : charges) for (double q : row) b.addCharges((float) q);
        b.addEnergies(0).addEnergies(0.4).addEnergies(-1.0).setScanStart(0).setScanEnd(3);
        b.addChanges(0).addChanges(1).addChanges(0).addChanges(1);
        b.addOrders(0.02f).addOrders(0.4f).addOrders(0.98f);
        b.addJumps(1);
        b.addArrows(0).addArrows(-1).addArrows(1).addArrows(-1);
        // Les liaisons de chaque image : C1–H3 (0,97 partout) et C1–C2 (0,02 → 0,4 → 0,98).
        b.addBondPairs(0).addBondPairs(2).addBondPairs(0).addBondPairs(1);
        for (double[] row : new double[][]{{.97, .02}, {.97, .4}, {.97, .98}}) for (double o : row) b.addBondOrders((float) o);
        b.addRadii(.75f).addRadii(.75f).addRadii(.32f);
        return b.build();
    }

    @Test
    void thePathIsReadFromTheStep() {
        StageRenderer.Path path = SchemeStage.path(step());
        assertEquals(3, path.count());
        assertArrayEquals(new int[]{6, 6, 1}, path.z);
        assertEquals(0.77, path.frames[2][1][0], 1e-6);
        assertEquals(-0.2, path.charges[2][0], 1e-6);
        assertEquals(0.98, path.orders[2][0], 1e-6);
        assertEquals(1, StageRenderer.Path.kind(path.changes[0]));          // se forme
        assertEquals(0, path.change(1, 0));
        assertEquals(-1, path.change(0, 2));
        assertTrue(path.jumps.contains(1));
        assertTrue(path.reacting[0] && path.reacting[1] && !path.reacting[2]);
        assertArrayEquals(new int[]{0, -1, 1, -1}, path.curly[0]);
    }

    @Test
    void thePathIsReplayedFromItsOwnImages() {
        // Sans le moteur : aux images, la géométrie, les charges et les liaisons du calcul ; entre deux, interpolées.
        StageRenderer.Path path = SchemeStage.path(step());
        assertEquals(2, path.bondPairs.length);
        assertEquals(.4, path.bondOrders[1][1], 1e-6);
        assertEquals(.32, path.radii[2], 1e-6);
        PathMotion motion = new PathMotion(path);
        StageRenderer.Pose first = motion.at(0);
        assertEquals(-2.0, first.p[0][0], 1e-9);
        assertEquals(0.75, first.radius[0], 1e-6);
        assertEquals(.4, motion.energy(1), 1e-9);
        assertEquals(.2, motion.energy(.5), 1e-9);                     // entre deux images : interpolée
        // Entre les images 1 et 2, un saut de branche : aucune géométrie inventée, l'une puis l'autre.
        assertEquals(1.2, motion.at(1.3).p[1][0], 1e-6);
        assertEquals(.77, motion.at(1.7).p[1][0], 1e-6);
        // L'indice de Mayer de C1–C2 (un changement) : celui du chemin, interpolé entre les images 0 et 1.
        StageRenderer.Pose half = motion.at(.5);
        double m = half.bonds.stream().filter(b -> b.a() == 0 && b.b() == 1).mapToDouble(MoleculeScene.Bond::order).findFirst().orElse(0);
        assertEquals(.21, m, 1e-6);
        assertEquals(-.05, half.q[0], 1e-6);
    }

    @Test
    void aRigidBodyTurnsInsteadOfCrossingItself() {
        // Un CO qui se retourne d'une image à l'autre : à mi-chemin, l'oxygène tourne autour du centre au lieu de passer
        // à travers le carbone (la longueur C–O reste celle des deux images).
        double[][][] frames = {{{0, 0, 0}, {1.13, 0, 0}}, {{1.13, 0, 0}, {0, 0, 0}}};
        StageRenderer.Path path = new StageRenderer.Path(new int[]{6, 8}, frames, new double[0][], new double[]{0, 0}, 0, 2,
                new int[0][], new double[2][0], new int[0][], new java.util.HashSet<>(), new int[0][], new double[0][],
                new double[]{.75, .63});
        double[][] p = new PathMotion(path).at(.5).p;
        assertEquals(1.13, StageRenderer.dist(p[0], p[1]), 1e-6);
    }

    @Test
    void theComputedViewLooksAcrossTheReactionPlaneAndMayerIsTheEnginesOwn() throws Exception {
        onFx(() -> {
            StageRenderer r = new StageRenderer();
            StageRenderer.Path path = SchemeStage.path(step());
            r.setPath(path);
            // Les atomes qui réagissent bougent dans le plan z = 0 : l'œil regarde le long de z.
            double[] eye = r.basis()[2];
            assertEquals(1.0, Math.abs(eye[2]), 1e-9);
            double[][] p = {{-1, 0, 0}, {1, 0, 0}, {-1, 1.1, 0}};
            r.setPose(SchemeSchemeTestSupport.pose(path, p, 0.42));
            assertEquals(0.42, r.mayer(0), 1e-12);                          // l'indice calculé à la géométrie montrée
            r.setPose(SchemeSchemeTestSupport.pose(path, p, 0));
            assertEquals(0.0, r.mayer(0), 1e-12);                           // sous 0,05 : le moteur ne l'envoie pas
            r.getNode().resize(900, 600);
            r.render();
            double[] a = r.screenOf(0), b = r.screenOf(1);
            assertNotNull(a);
            // La liaison qui se forme est dans le plan de l'écran : ses deux atomes, bien écartés (2 Å à l'écran).
            assertTrue(Math.hypot(a[0] - b[0], a[1] - b[1]) > 1.5 * r.zoom(), "C1 et C2 ne doivent pas se confondre");
            assertTrue(r.pick(a[0], a[1]) == 0);
        });
    }

    @Test
    void theFilamentFollowsTheMayerIndex() {
        // L'éclat d'une liaison qui se forme ou se rompt : nul sous PAIR (un trait fin relie la paire), m / SOLID
        // entre, plein dès SOLID (un tube).
        assertEquals(0, StageRenderer.strength(0), 1e-12);
        assertEquals(0, StageRenderer.strength(StageRenderer.PAIR / 2), 1e-12);
        assertEquals(.3 / StageRenderer.SOLID, StageRenderer.strength(.3), 1e-12);
        assertEquals(1, StageRenderer.strength(StageRenderer.SOLID), 1e-12);
        assertEquals(1, StageRenderer.strength(1.4), 1e-12);
        assertTrue(StageRenderer.strength(.5) > StageRenderer.strength(.4));
    }

    @Test
    void theGlassIsComputedPixelByPixel() {
        // Une sphère : opaque au cœur, transparente hors du disque ; le reflet en haut à gauche, plus clair que le bas
        // à droite ; un contour sombre juste dehors.
        javafx.scene.image.WritableImage s = StageRenderer.sphere(StageRenderer.tone(8), 0, 30);
        javafx.scene.image.PixelReader px = s.getPixelReader();
        int size = (int) s.getWidth(), c = size / 2;
        assertEquals(0, px.getArgb(0, 0) >>> 24, "hors du disque : transparent");
        assertEquals(255, px.getArgb(c, c) >>> 24, "le cœur : opaque");
        int glint = px.getArgb(c - 9, c - 11), shade = px.getArgb(c - 9, c + 13);
        assertTrue(brightness(glint) > brightness(shade) + .3, "le reflet de la lumière principale");
        int ring = px.getArgb(c + 30, c);
        assertTrue((ring >>> 24) > 40 && brightness(ring) < .1, "le contour sombre, dehors");
        // Un tube : transparent dans ses marges, couvert au milieu.
        javafx.scene.image.WritableImage t = StageRenderer.strip(StageRenderer.tone(6), 0, 0);
        assertEquals(0, t.getPixelReader().getArgb(0, 0) >>> 24);
        assertTrue((t.getPixelReader().getArgb(0, (int) t.getHeight() / 2) >>> 24) > 150);
    }

    private static double brightness(int argb) {
        return ((argb >> 16 & 255) + (argb >> 8 & 255) + (argb & 255)) / 765.0;
    }

    @Test
    void chargesUseTheLaboratoryColours() {
        assertEquals(MoleculeScene.NEUTRAL, StageRenderer.chargeColour(0));
        assertEquals(MoleculeScene.RICH, StageRenderer.chargeColour(-1));
        assertEquals(MoleculeScene.POOR, StageRenderer.chargeColour(1));
    }

    /** Une géométrie montrée, avec ou sans la liaison C1–C2 calculée. */
    private static final class SchemeSchemeTestSupport {
        static StageRenderer.Pose pose(StageRenderer.Path path, double[][] p, double order) {
            java.util.List<MoleculeScene.Bond> bonds = new java.util.ArrayList<>();
            bonds.add(new MoleculeScene.Bond(0, 2, .97, .97, 0, .5));
            if (order > 0) bonds.add(new MoleculeScene.Bond(0, 1, order, order, 0, .5));
            return new StageRenderer.Pose(p, new double[]{-.1, .05, .05}, new double[]{.75, .75, .32}, bonds, 1.0);
        }
    }
}
