package org.genesis.molecule;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** L'assembleur 2D (ses règles, son dessin, ce qu'il envoie) et les noms des espèces dans la scène. */
class MoleculeAssemblerTest {
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

    private static MoleculeAssembler assembler(List<double[]> sent) {
        return new MoleculeAssembler(new MoleculeAssembler.Host() {
            @Override public void build(double[] vector, int charge, boolean enclosed) { sent.add(vector); }
            @Override public void assess(int request, double[] vector, boolean deep) { }
            @Override public void close() { }
            @Override public void pickElement() { }
        });
    }

    @Test
    void valencesFollowTheChargeAndAllowHypervalentAtoms() {
        assertEquals(4, MoleculeAssembler.usual(6, 0));
        assertEquals(2, MoleculeAssembler.usual(8, 0));
        assertEquals(3, MoleculeAssembler.usual(8, 1));                 // H₃O⁺
        assertEquals(1, MoleculeAssembler.usual(8, -1));                // OH⁻
        assertEquals(4, MoleculeAssembler.usual(7, 1));                 // NH₄⁺
        assertEquals(1, MoleculeAssembler.maximum(1, 0));
        assertEquals(4, MoleculeAssembler.maximum(6, 0));               // pas de carbone pentavalent
        assertEquals(5, MoleculeAssembler.maximum(15, 0));              // PCl₅
        assertEquals(6, MoleculeAssembler.maximum(16, 0));              // SF₆
        assertEquals(0, MoleculeAssembler.maximum(10, 0));              // le néon ne se lie pas
    }

    @Test
    void waterIsDrawnBentWithBondsOfOneUnit() {
        double[][] p = MoleculeAssembler.arrange(new int[]{8, 1, 1}, List.of(new int[]{0, 1, 1}, new int[]{0, 2, 1}), null);
        double ax = p[1][0] - p[0][0], ay = p[1][1] - p[0][1], bx = p[2][0] - p[0][0], by = p[2][1] - p[0][1];
        double la = Math.hypot(ax, ay), lb = Math.hypot(bx, by);
        double angle = Math.toDegrees(Math.acos((ax * bx + ay * by) / (la * lb)));
        assertTrue(angle > 95 && angle < 140, "un V, pas une ligne : " + angle);
        assertEquals(la, lb, .05);
    }

    @Test
    void theDrawingGoesToTheEngineAndComesBackUnchanged() throws Exception {
        onFx(() -> {
            List<double[]> sent = new ArrayList<>();
            MoleculeAssembler a = assembler(sent);
            // Le méthanal : C=O et ses deux H.
            double[] drawing = {4, 6, 0, 0, 0, 8, 1, 0, 0, 1, -.5, .87, 0, 1, -.5, -.87, 0, 3, 0, 1, 2, 0, 2, 1, 0, 3, 1};
            a.load(drawing, 1);
            assertArrayEquals(drawing, a.vector(), 1e-12);
            assertEquals(4, a.used(0));
            assertEquals(0, a.open(0));
            // Les H retirés : deux valences libres sur le carbone ; les compléter les rend.
            a.load(new double[]{2, 6, 0, 0, 0, 8, 1, 0, 0, 1, 0, 1, 2}, 2);
            assertEquals(2, a.open(0));
            a.completeHydrogens();
            assertEquals(4, a.atoms.size());
            assertEquals(0, a.open(0));
            assertEquals(0, a.open(1));
        });
    }

    @Test
    void aSpeciesThatFormsIsSignalledButNotOneThatWasLoaded() {
        MoleculeScene scene = new MoleculeScene();
        scene.z = new int[]{8, 1, 1};
        scene.species(List.of(new int[]{0, 1}, new int[]{2}), List.of("HO · radical hydroxyle", "H · atome d'hydrogène"));
        assertTrue(scene.species.stream().allMatch(s -> s.born() == Double.NEGATIVE_INFINITY), "chargé : rien ne se forme");
        scene.species(List.of(new int[]{0, 1, 2}), List.of("H₂O · eau"));
        assertTrue(Double.isFinite(scene.species.get(0).born()), "l'eau vient de se former");
        // Deux eaux qui échangent un proton : les mêmes espèces, rien de neuf à signaler.
        scene.z = new int[]{8, 1, 1, 8, 1, 1};
        scene.species(List.of(new int[]{0, 1, 2}, new int[]{3, 4, 5}), List.of("H₂O · eau", "H₂O · eau"));
        scene.species(List.of(new int[]{0, 1, 5}, new int[]{2, 3, 4}), List.of("H₂O · eau", "H₂O · eau"));
        assertTrue(scene.species.stream().allMatch(s -> s.born() == Double.NEGATIVE_INFINITY));
    }

    @Test
    void distantMoleculesAreDrawnCloserInTheSameOrder() {
        // Trois molécules sur une ligne, à 12 liaisons l'une de l'autre : le schéma les resserre sans les déranger.
        double[][] p = {{-12, 0}, {-11, 0}, {0, 0}, {1, 0}, {12, 0}, {13, 0}};
        boolean[] drawn = {true, true, true, true, true, true};
        MoleculeSketch.pack(p, drawn, List.of(new int[]{0, 1}, new int[]{2, 3}, new int[]{4, 5}));
        assertEquals(1, p[1][0] - p[0][0], 1e-9);                     // chaque molécule garde sa forme
        assertTrue(p[0][0] < p[2][0] && p[2][0] < p[4][0]);             // et sa place
        double gap = p[2][0] - p[1][0];
        assertTrue(gap < 4 && gap > 1, "vide resserré : " + gap);
        for (double[] q : p) assertEquals(0, q[1], 1e-9);
    }
    @Test
    void theSelectionTakesChargesDeletesAndDuplicatesTogether() throws Exception {
        List<double[]> sent = new ArrayList<>();
        onFx(() -> {
            MoleculeAssembler a = assembler(sent);
            // Méthanol dessiné à la main : C0–O1, O1–H2, trois H sur C0 (atomes 3, 4, 5), puis un N seul (atome 6).
            a.load(new double[]{7, 6, 0, 0, 0, 8, 1, 0, 0, 1, 2, 0, 0, 1, -1, 0, 0, 1, 0, 1, 0, 1, 0, -1, 0, 7, 4, 0, 0,
                    5, 0, 1, 1, 1, 2, 1, 0, 3, 1, 0, 4, 1, 0, 5, 1}, 1);
            // H2 est lié : un H chargé n'a plus de liaison possible, il refuse ; le N seul accepte.
            a.selection.addAll(List.of(2, 6));
            a.charge(new ArrayList<>(a.selection), -1);
            assertEquals(0, a.atoms.get(2).charge, "H⁻ lié : refusé");
            assertEquals(-1, a.atoms.get(6).charge);
            a.charge(List.of(1), 1);
            assertEquals(1, a.atoms.get(1).charge, "O⁺ porte jusqu'à trois liaisons : accepté");
            // Dupliquer la molécule entière (ses liaisons internes suivent), puis effacer l'original.
            a.selection.clear();
            a.selection.addAll(a.molecule(0));
            a.duplicate();
            assertEquals(13, a.atoms.size());
            assertEquals(10, a.links.size(), "les cinq liaisons sont copiées");
            assertEquals(6, a.selection.size(), "la copie devient la sélection");
            a.selection.clear();
            a.selection.addAll(List.of(0, 1, 2, 3, 4, 5));
            a.deleteSelection();
            assertEquals(7, a.atoms.size());
            assertEquals(5, a.links.size());
            for (MoleculeAssembler.Link l : a.links) assertTrue(l.a < 7 && l.b < 7, "liaisons renumérotées");
            assertEquals(7, a.atoms.get(0).z, "le N seul est maintenant l'atome 0");
            assertEquals(2, a.molecules().size());
        });
        assertEquals(0, MoleculeAssembler.nextCharge(-1));
        assertEquals(1, MoleculeAssembler.nextCharge(0));
        assertEquals(-1, MoleculeAssembler.nextCharge(1));
    }

    @Test
    void aLinearCarbonIsWrittenAndSmallMoleculesKeepTheirHydrogens() {
        // C=C=C : le carbone du milieu, à 180°, s'écrit ; sinon la chaîne paraît n'avoir que deux carbones.
        boolean[] linear = MoleculeSketch.linear(3, List.of(new int[]{0, 1, 2}, new int[]{1, 2, 2}));
        assertArrayEquals(new boolean[]{false, true, false}, linear);
        assertFalse(MoleculeSketch.linear(3, List.of(new int[]{0, 1, 1}, new int[]{1, 2, 2}))[1], "C–C=C est coudé");
        assertTrue(MoleculeSketch.linear(3, List.of(new int[]{0, 1, 1}, new int[]{1, 2, 3}))[1], "C–C≡C est droit");
        // H₂O à côté de C₃ : l'eau reste petite (H–O–H), même si la scène a quatre atomes lourds.
        int[] z = {6, 6, 6, 8, 1, 1};
        List<MoleculeScene.Bond> bonds = List.of(new MoleculeScene.Bond(0, 1, 2, 1, 1, .5), new MoleculeScene.Bond(1, 2, 2, 1, 1, .5),
                new MoleculeScene.Bond(3, 4, 1, 1, 0, .5), new MoleculeScene.Bond(3, 5, 1, 1, 0, .5));
        assertArrayEquals(new boolean[]{false, false, false, true, true, true}, MoleculeSketch.small(z, bonds));
    }

    @Test
    void aDeltaSignLeavesTheLabelsAndTheBonds() {
        // L'angle libre : à l'opposé des deux liaisons d'un O coudé, vers le haut à égalité.
        double free = MoleculeSketch.freeAngle(List.of(Math.toRadians(30), Math.toRadians(150)));
        assertEquals(-1, Math.sin(free), 1e-6);                         // y vers le bas à l'écran : −1, le haut
        // Une liaison sort de l'étiquette par son bord : boîte de ±6 px, trait horizontal.
        assertEquals(7.5, MoleculeSketch.cut(new double[]{-6, -6, 6, 6}, new double[]{0, 0}, 1, 0), 1e-9);
        assertEquals(0, MoleculeSketch.cut(null, new double[]{0, 0}, 1, 0), 1e-9);
        // La place du signe ne touche aucune boîte prise.
        List<double[]> taken = List.of(new double[]{-6, -6, 6, 6});
        double[] spot = MoleculeSketch.clear(0, 0, 0, -1, 5, 5, taken);
        assertTrue(spot[1] + 5 < -6 - 1 + 1e-9, "au-dessus de l'étiquette : " + spot[1]);
    }
}
