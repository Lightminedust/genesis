package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeScheme;
import com.studio.genesis.grpc.MoleculeSchemeStep;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Les étapes d'un schéma qui se suivent : l'étape 2 commence où l'étape 1 finit (mêmes atomes, mêmes numéros, images
 * alignées), le relais entre elles, la lecture de tout le schéma, ses hauteurs, le temps d'une barrière, le relief.
 */
class SchemeWholeTest {
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

    /** Étape 1 : deux carbones (1, 2) et un hydrogène (3) ; trois images ; la liaison C1–C2 se forme. */
    private static MoleculeSchemeStep first() {
        MoleculeSchemeStep.Builder b = MoleculeSchemeStep.newBuilder().addNumbers(6).addNumbers(6).addNumbers(1)
                .addIds(1).addIds(2).addIds(3).setComputed(true).setConfirmed(true);
        double[][][] frames = {
                {{-2.0, 0, 0}, {2.0, 0, 0}, {-2.0, 1.1, 0}},
                {{-1.2, 0.3, 0}, {1.2, -0.3, 0}, {-1.2, 1.4, 0}},
                {{-0.77, 0.1, 0}, {0.77, -0.1, 0}, {-0.77, 1.2, 0}}};
        for (double[][] frame : frames) for (double[] p : frame) for (double v : p) b.addPath((float) v);
        b.addEnergies(0).addEnergies(0.6).addEnergies(-1.0).setScanStart(0).setScanEnd(3);
        b.addChanges(0).addChanges(1).addChanges(0).addChanges(1);
        b.addOrders(0.02f).addOrders(0.4f).addOrders(0.98f);
        b.setDeltaEv(-1.0).setBarrierEv(0.6).setStartEv(-0.1).setEndEv(0.0).setTemperature(300);
        b.addRadii(.75f).addRadii(.75f).addRadii(.32f);
        return b.build();
    }

    /**
     * Étape 2 : les mêmes C1, C2 (dans un autre ordre, tournés de 90° et déplacés : une autre image du calcul), un
     * oxygène qui arrive (4) ; H3 ne continue pas (un sous-produit).
     */
    private static MoleculeSchemeStep second() {
        MoleculeSchemeStep.Builder b = MoleculeSchemeStep.newBuilder().addNumbers(6).addNumbers(8).addNumbers(6)
                .addIds(2).addIds(4).addIds(1).setComputed(true).setConfirmed(true);
        // La dernière image de l'étape 1, C1 (−0,77, 0,1, 0) et C2 (0,77, −0,1, 0), tournée de 90° autour de z et
        // déplacée de (5, 5, 5) : (x, y) → (−y, x).
        double[][][] frames = {
                {{5.1, 5.77, 5}, {5, 3, 5}, {4.9, 4.23, 5}},
                {{5.1, 5.77, 5}, {5, 4.2, 5}, {4.9, 4.23, 5}},
                {{5.1, 5.77, 5}, {5, 4.9, 5}, {4.9, 4.23, 5}}};
        for (double[][] frame : frames) for (double[] p : frame) for (double v : p) b.addPath((float) v);
        b.addEnergies(0).addEnergies(0.3).addEnergies(0.5).setScanStart(0).setScanEnd(3);
        b.addChanges(1).addChanges(2).addChanges(0).addChanges(1);
        b.addOrders(0f).addOrders(0.5f).addOrders(0.9f);
        b.setDeltaEv(0.5).setBarrierEv(0.5).setStartEv(0.05).setTransferEv(0.2).setTemperature(300);
        b.addRadii(.75f).addRadii(.66f).addRadii(.75f);
        return b.build();
    }

    private static MoleculeScheme scheme() {
        return MoleculeScheme.newBuilder().setRequest(1).setDone(true).addSteps(first()).addSteps(second()).build();
    }

    @Test
    void theNextStepStartsWhereThePreviousEnded() {
        StageRenderer.Path a = SchemeStage.path(first()), b = SchemeStage.path(second());
        assertEquals("C1", a.name(0));
        assertEquals("C1", b.name(2));                     // le même atome, le même numéro, à un autre rang
        double before = StageRenderer.dist(b.frames[2][1], b.frames[2][2]);
        SchemeStage.align(b, a);
        // C2 et C1 de l'étape 2, dans sa première image, sont là où l'étape 1 les a laissés.
        double[][] last = a.frames[2], firstB = b.frames[0];
        assertEquals(0, StageRenderer.dist(firstB[0], last[1]), 1e-5);
        assertEquals(0, StageRenderer.dist(firstB[2], last[0]), 1e-5);
        // Un seul bloc : les distances de chaque image restent celles du calcul.
        assertEquals(before, StageRenderer.dist(b.frames[2][1], b.frames[2][2]), 1e-5);
    }

    @Test
    void theRelayFadesWhatLeavesAndBringsWhatArrives() {
        StageRenderer.Path a = SchemeStage.path(first()), b = SchemeStage.path(second());
        SchemeStage.align(b, a);
        StageRenderer.Path relay = SchemeStage.relay(a, b);
        assertTrue(relay.relay);
        assertEquals(4, relay.z.length);                   // C1, C2, H3 (qui part), O4 (qui arrive)
        assertEquals(3, relay.number(2));
        assertEquals(4, relay.number(3));
        assertArrayEquals(new double[]{1, 0}, new double[]{relay.alpha[0][2], relay.alpha[1][2]});   // H3 s'en va
        assertArrayEquals(new double[]{0, 1}, new double[]{relay.alpha[0][3], relay.alpha[1][3]});   // O4 arrive
        PathMotion motion = new PathMotion(relay);
        assertEquals(1, motion.at(0).alpha[0], 1e-9);
        assertEquals(1, motion.at(1).alpha[0], 1e-9);      // C1 reste
        assertEquals(0, motion.at(1).alpha[2], 1e-9);
        assertTrue(motion.at(.25).alpha[2] < 1 && motion.at(.25).alpha[3] == 0, "le départ d'abord, l'arrivée ensuite");
        assertEquals(0, StageRenderer.dist(motion.at(1).p[3], b.frames[0][1]), 1e-9);
    }

    @Test
    void theLevelsAddTheStepsOfTheScheme() {
        SchemeStage.Levels levels = new SchemeStage.Levels(scheme());
        assertEquals(0, levels.reactants[0], 1e-12);
        assertEquals(-0.1, levels.base[0], 1e-12);
        assertEquals(0.5, levels.peak[0], 1e-6);           // −0,1 + 0,6
        assertEquals(-1.0, levels.products[0], 1e-12);
        assertEquals(-0.8, levels.reactants[1], 1e-12);    // −1,0 + 0,2 (le changement de milieu)
        assertEquals(-0.3, levels.end(), 1e-12);
        assertEquals(0, levels.slowest);                   // 0,6 eV contre 0,5
    }

    @Test
    void aBarrierIsATime() {
        // Eyring à 300 K : 1 eV, ≈ 1,9 h ; 0,5 eV, ≈ 0,3 ms ; 4 eV, plus que l'âge de l'Univers.
        assertEquals(6.97e3, SchemeStage.halfLife(1.0, 300), 70);
        assertTrue(SchemeStage.halfLife(0.5, 300) < 1e-3);
        assertTrue(SchemeStage.halfLife(4.0, 300) > 4.3e17);
        assertTrue(SchemeStage.halfLife(1.3, 300) > SchemeStage.YEAR, "1,3 eV à 300 K : plus d un an");
        assertTrue(SchemeStage.duration(SchemeStage.halfLife(4.0, 300)).contains("10"));
    }

    @Test
    void theWholeSchemePlaysOnWithoutStopping() throws Exception {
        onFx(() -> {
            SchemeStage stage = new SchemeStage(new SchemeStage.Actions() {
                public void openScene(int step, double position, boolean running, double speed) { }
                public void openEditor() { }
            });
            stage.getNode().resize(1200, 760);
            stage.update(scheme());
            stage.show(true, SchemeStage.WHOLE, 0, true);
            assertEquals(0, stage.step());
            assertTrue(stage.running());
            boolean sawRelay = false;
            // 2 images + le relais (2) + 2 images, à 2,5 images par seconde : 2,4 s.
            for (int k = 0; k < 60 * 3; k++) {
                stage.tick(1 / 60.0);
                sawRelay |= stage.view().path() != null && stage.view().path().relay;
            }
            assertTrue(sawRelay, "le relais entre les deux étapes est joué");
            assertEquals(1, stage.step());                 // arrivé au bout de l'étape 2, sans s'arrêter entre
            assertFalse(stage.running());
        });
    }

    @Test
    void resultsOfAnOldDrawingNeverComeBack() throws Exception {
        // Un exemple chargé après un calcul : les cartes de l'ancien schéma (et leur « Voir le chemin ») revenaient à
        // l'image suivante du moteur, qui republie le même numéro de demande.
        onFx(() -> {
            try {
                SchemeEditor editor = new SchemeEditor(new SchemeEditor.Host() {
                    public void compute(int request, String json) { }
                    public void smiles(int request, String text) { }
                    public void play(int step) { }
                    public void close() { }
                });
                com.studio.genesis.grpc.MoleculeSchemeDrawing drawing = com.studio.genesis.grpc.MoleculeSchemeDrawing.newBuilder()
                        .addAtoms(6).addAtoms(0).addAtoms(0).addAtoms(0).addAtoms(6).addAtoms(3).addAtoms(0).addAtoms(0)
                        .addArrows(1).addArrows(0).addArrows(2).addArrows(0).addAbove("").addBelow("").build();
                editor.load(drawing);
                java.lang.reflect.Method compute = SchemeEditor.class.getDeclaredMethod("compute");
                compute.setAccessible(true);
                compute.invoke(editor);
                com.studio.genesis.grpc.MoleculeLabState state = com.studio.genesis.grpc.MoleculeLabState.getDefaultInstance();
                MoleculeScheme done = scheme();
                editor.update(state, done);
                assertTrue(editor.resultCards() > 0, "les résultats du calcul");
                editor.load(drawing);                       // un autre exemple
                assertEquals(0, editor.resultCards());
                editor.update(state, done);                 // le moteur republie le même calcul
                editor.update(state, done.toBuilder().build());
                assertEquals(0, editor.resultCards(), "les résultats d'un dessin remplacé ne reviennent pas");
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void theSlowRemarkFitsTheReaction() {
        // Un H qui passe d'un atome à l'autre : catalyse acide ou basique possible. Sinon (le complexe au cobalt) : le
        // chemin concerté n'est sans doute pas le mécanisme, à découper en étapes.
        assertEquals(SchemeStage.SLOW_CONCERTED, SchemeStage.slowText(SchemeStage.path(first())));
        MoleculeSchemeStep proton = first().toBuilder().clearChanges().addChanges(0).addChanges(2).addChanges(0).addChanges(1).build();
        assertEquals(SchemeStage.SLOW, SchemeStage.slowText(SchemeStage.path(proton)));
        MoleculeSchemeStep gas = first().toBuilder().setEntropyEv(-0.43).build();
        assertTrue(SchemeStage.entropyText(gas).contains("−0") && SchemeStage.entropyText(gas).contains("1.43")
                || SchemeStage.entropyText(gas).contains("1,43"), SchemeStage.entropyText(gas));
        assertEquals("", SchemeStage.entropyText(first()));
    }

    @Test
    void aWheelNotchZoomsByTwelvePercentAndSettlesQuickly() {
        org.genesis.common.LabCamera camera = new org.genesis.common.LabCamera();
        camera.zoom = 1;
        camera.zoomBy(-MoleculeScene.ZOOM_STEP);
        camera.zoomBy(-MoleculeScene.ZOOM_STEP);          // deux crans : ×1,12², sans élan après
        for (int k = 0; k < 15; k++) camera.tick(1 / 60.0, false, 0, 3);      // 250 ms
        assertEquals(1 - 2 * Math.log10(1.12), camera.zoom, 2e-3);
        camera.zoomBy(-10);                                 // au-delà de la borne : tenu à la borne
        for (int k = 0; k < 60; k++) camera.tick(1 / 60.0, false, Math.log10(3), 3);
        assertEquals(Math.log10(3), camera.zoom, 1e-9);
        camera.zoomBy(MoleculeScene.ZOOM_STEP);           // le cran suivant repart de la borne
        for (int k = 0; k < 30; k++) camera.tick(1 / 60.0, false, Math.log10(3), 3);
        assertEquals(Math.log10(3) + MoleculeScene.ZOOM_STEP, camera.zoom, 2e-3);
    }

    @Test
    void theTitleOfAPlayedStepIsTranslatedWhole() {
        // Le titre de la scène 3D qui joue une étape : « Étape 1 » restait en français, et « acétone → C₁₀H₁₂O₂ »
        // passait pour un seul nom.
        org.genesis.app.I18n.Language before = org.genesis.app.I18n.language();
        try {
            org.genesis.app.I18n.setLanguage(org.genesis.app.I18n.Language.EN);
            assertEquals("Step 1 · C₇H₆O · benzaldehyde + C₃H₆O · acetone → C₁₀H₁₂O₂ · 4-hydroxy-4-phenylbutan-2-one",
                    MoleculeFormat.names("Étape 1 · C₇H₆O · benzaldéhyde + C₃H₆O · acétone → C₁₀H₁₂O₂ · "
                            + "4-hydroxy-4-phénylbutan-2-one"));
        } finally {
            org.genesis.app.I18n.setLanguage(before);
        }
    }

    @Test
    void theReliefDrawsBothEyesAndStillPicksTheAtoms() throws Exception {
        onFx(() -> {
            StageRenderer r = new StageRenderer();
            StageRenderer.Path path = SchemeStage.path(first());
            r.setPath(path);
            r.setPose(new PathMotion(path).at(2));
            r.getNode().resize(900, 600);
            for (StageRenderer.Stereo mode : StageRenderer.Stereo.values()) {
                r.stereo(mode);
                r.render();
                double[] a = r.screenOf(0);
                assertNotNull(a, mode.name());
                double x = mode == StageRenderer.Stereo.SIDE ? a[0] + 450 : a[0];   // la moitié droite répète la gauche
                assertEquals(0, r.pick(x, a[1]), mode.name());
            }
        });
    }
}
