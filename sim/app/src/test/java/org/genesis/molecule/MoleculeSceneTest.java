package org.genesis.molecule;

import com.google.protobuf.ByteString;
import com.studio.genesis.grpc.MoleculeGrid;
import org.genesis.common.LabCamera;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoleculeSceneTest {
    @Test
    void theEngineEncodingIsUndone() {
        // ρ = peak · (q / 65535)⁴ : q = 32768 → peak / 16 ; Δρ = peak · signe · (|q| / 32767)² : −16384 → −peak / 4.
        ByteBuffer density = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        for (int q : new int[]{0, 65535, 32768, 65535, 0, 0, 0, 0}) density.putShort((short) q);
        MoleculeGrid message = MoleculeGrid.newBuilder().setKind(0).setPeak(2.0).setSpacing(.5).setVersion(1)
                .addOrigin(0).addOrigin(0).addOrigin(0).addCounts(2).addCounts(2).addCounts(2)
                .setData(ByteString.copyFrom(density.array())).build();
        DensityGrid grid = DensityGrid.of(message);
        assertEquals(2.0, grid.values[1], 1e-6);
        assertEquals(2.0 / 16, grid.values[2], 1e-3);
        ByteBuffer signed = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        for (int q : new int[]{-16384, 32767, 0, 0, 0, 0, 0, 0}) signed.putShort((short) q);
        DensityGrid deformation = DensityGrid.of(message.toBuilder().setKind(1).setData(ByteString.copyFrom(signed.array())).build());
        assertEquals(-0.5, deformation.values[0], 1e-3);
        assertEquals(2.0, deformation.values[1], 1e-6);
    }

    @Test
    void samplingInterpolatesBetweenGridPoints() {
        float[] values = new float[8];
        values[4] = 8f;                                   // le point (1, 0, 0)
        DensityGrid grid = DensityGrid.of(0, 2, 2, 2, 0, 0, 0, 1.0, values);
        assertEquals(4f, grid.sample(.5, 0, 0), 1e-6);
        assertEquals(2f, grid.sample(.5, .5, 0), 1e-6);
        assertEquals(0f, grid.sample(-.1, 0, 0));          // hors de la grille
    }

    @Test
    void anAtomIsPickedWhereItIsDrawnAndDraggedInTheScreenPlane() {
        MoleculeScene scene = new MoleculeScene();
        scene.positions = new double[][]{{0, 0, 0}, {1.5, 0, 0}};
        scene.z = new int[]{8, 1};
        scene.camera.setup(1000, 800, 500, 400, scene.camera.distance());
        double[] p = scene.camera.project(1.5, 0, 0);
        assertEquals(1, scene.pick(p[0], p[1]));
        assertEquals(-1, scene.pick(5, 5));
        double[] point = scene.unproject(p[0] + 30, p[1], scene.positions[1]);
        double[] f = scene.camera.forward();
        double depth = (point[0] - 1.5) * f[0] + point[1] * f[1] + point[2] * f[2];
        assertEquals(0, depth, 1e-9);                      // reste dans le plan face à la caméra
    }

    @Test
    void refiningKeepsTheEnginePointsAndSmoothsBetweenThem() {
        float[] values = new float[4 * 4 * 4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) for (int k = 0; k < 4; k++) values[(i * 4 + j) * 4 + k] = i * i;
        DensityGrid grid = DensityGrid.of(0, 4, 4, 4, 0, 0, 0, 1.0, values);
        DensityGrid fine = grid.refined();
        assertEquals(7, fine.nx);
        assertEquals(.5, fine.spacing, 1e-12);
        assertEquals(4f, fine.sample(2, 1, 1), 1e-5);                 // un point du moteur
        assertEquals(2.25f, fine.sample(1.5, 1, 1), 1e-5);            // Catmull–Rom exact pour x²
    }

    @Test
    void theHaloEdgeIsWhereTheProjectedCloudReachesTheContour() {
        // Un nuage d'essai : P⁰(d) = 3 e^(−d/0,3 Å) e/Å² pour 6 électrons. Un atome qui en garde N a le nuage (N/6)·P⁰ :
        // son halo est un disque de rayon 0,3 ln(3·N/6 / σ₀) — plus grand s'il est δ−, plus petit s'il est δ+.
        float[] projected = new float[141];
        for (int i = 0; i < projected.length; i++) projected[i] = (float) (3 * Math.exp(-i * .05 / .3));
        MoleculeHalo halo = new MoleculeHalo();
        halo.cloud(8, new MoleculeHalo.Cloud(6, .05, projected));
        LabCamera camera = new LabCamera();
        camera.setup(1000, 800, 500, 400, 12);
        double[] center = camera.project(0, 0, 0);
        double last = Double.MAX_VALUE;
        for (double q : new double[]{-.5, 0, .5}) {
            halo.prepare(camera, 1000, 800, new double[][]{{0, 0, 0}}, new int[]{8}, new double[]{q}, new int[]{0});
            double expected = .3 * Math.log(3 * (6 - q) / 6 / MoleculeHalo.COLUMN) * center[3];
            for (double[] d : new double[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {.6, .8}}) {
                assertEquals(expected, halo.edge(0, center[0], center[1], d[0], d[1]), 2.0);
            }
            assertTrue(expected < last);
            last = expected;
        }
        assertEquals(3, halo.column(center[0], center[1], 0) * 6 / 5.5, 1e-3);   // au centre : N/6 · P⁰(0), N = 5,5
    }

    @Test
    void electronRichIsMagentaAndElectronPoorIsCyan() {
        float[] rich = MoleculeScene.chargeColour(-MoleculeScene.CHARGE_SCALE);
        float[] poor = MoleculeScene.chargeColour(MoleculeScene.CHARGE_SCALE);
        float[] neutral = MoleculeScene.chargeColour(0);
        assertTrue(rich[0] > rich[1] && rich[2] > rich[1]);           // magenta : rouge et bleu, peu de vert
        assertTrue(poor[1] > poor[0] && poor[2] > poor[0]);           // cyan : vert et bleu, peu de rouge
        assertTrue(neutral[2] > neutral[0] && neutral[2] > neutral[1]);
    }

    @Test
    void aBondLineFadesWithItsOrderInsteadOfSnappingOff() {
        assertEquals(0, MoleculeScene.fade(MoleculeScene.FADE_FROM), 1e-12);
        assertEquals(1, MoleculeScene.fade(1.0), 1e-12);
        double previous = 0;
        for (double order = .12; order <= .55; order += .01) {
            double f = MoleculeScene.fade(order);
            assertTrue(f >= previous && f - previous < .1, "order " + order);   // continue, sans saut
            previous = f;
        }
    }

    @Test
    void isotopesAreWrittenWithTheirMassNumberAsASuperscript() {
        assertEquals("H", MoleculeView.nuclide("H", 0));
        assertEquals("²H", MoleculeView.nuclide("H", 2));
        assertEquals("¹³C", MoleculeView.nuclide("C", 13));
        assertEquals("²³⁵U", MoleculeView.nuclide("U", 235));
    }

    @Test
    void anEllipsoidProjectsToItsExactSilhouette() {
        double[] right = {1, 0, 0}, up = {0, 1, 0};
        // Une sphère : un cercle de même rayon, sous tous les angles.
        double s = Math.sqrt(.5);
        double[] sphere = MoleculeScene.silhouette(new double[][]{{.4, 0, 0}, {0, .4 * s, .4 * s}, {0, -.4 * s, .4 * s}}, right, up);
        assertEquals(.4, sphere[0], 1e-12);
        assertEquals(.4, sphere[1], 1e-12);
        // Un lobe allongé le long de l'écran (0,6), haut de 0,3 et profond de 0,2 : la profondeur ne se voit pas.
        double[] lobe = MoleculeScene.silhouette(new double[][]{{.6, 0, 0}, {0, .3, 0}, {0, 0, .2}}, right, up);
        assertArrayEquals(new double[]{.6, .3, 0}, lobe, 1e-12);
        // Le même, tourné de 30° dans le plan de l'écran (vers le bas de l'écran : angle positif).
        double c = Math.cos(Math.PI / 6), n = Math.sin(Math.PI / 6);
        double[] turned = MoleculeScene.silhouette(new double[][]{{.6 * c, -.6 * n, 0}, {.3 * n, .3 * c, 0}, {0, 0, .2}}, right, up);
        assertArrayEquals(new double[]{.6, .3, Math.PI / 6}, turned, 1e-12);
    }

    /** Une grille du moteur, d'un genre et d'une orbitale donnés (valeurs nulles). */
    private static DensityGrid engineGrid(int kind, int orbital, int version) {
        MoleculeGrid message = MoleculeGrid.newBuilder().setKind(kind).setOrbital(orbital).setVersion(version).setPeak(1)
                .setSpacing(.5).addOrigin(0).addOrigin(0).addOrigin(0).addCounts(2).addCounts(2).addCounts(2)
                .setData(ByteString.copyFrom(new byte[16])).build();
        return DensityGrid.of(message);
    }

    @Test
    void theChosenViewDecidesWhatIsDrawnNotTheLastGridReceived() {
        // Le défaut d'avant : revenu à la vue ρ, la scène gardait la grille Δρ ou ψ et la dessinait, figée.
        MoleculeScene scene = new MoleculeScene();
        scene.mode(MoleculeScene.DEFORMATION, -1);
        scene.setGrid(engineGrid(DensityGrid.ORBITAL, 3, 1), null, null);
        assertNull(scene.grid());                                   // une grille ψ n'est pas pour la vue Δρ
        DensityGrid deformation = engineGrid(DensityGrid.DEFORMATION, -1, 2);
        scene.setGrid(deformation, null, null);
        assertSame(deformation, scene.grid());
        scene.mode(MoleculeScene.DENSITY, -1);
        assertNull(scene.grid());                                   // retour à ρ : plus rien de Δρ
        assertEquals(MoleculeScene.DENSITY, scene.mode());
        // ψ : l'orbitale qu'on vient de demander, pas une autre.
        scene.mode(MoleculeScene.ORBITAL, 5);
        scene.setGrid(engineGrid(DensityGrid.ORBITAL, 6, 3), null, null);
        assertNull(scene.grid());
        DensityGrid five = engineGrid(DensityGrid.ORBITAL, 5, 4);
        scene.setGrid(five, null, null);
        assertSame(five, scene.grid());
        scene.mode(MoleculeScene.ORBITAL, 6);
        assertNull(scene.grid());                                   // l'orbitale 6 demandée : la 5 s'efface
    }

    @Test
    void contourLinesOfARoundHillAreClosedCircles() {
        // f = exp(−r²) sur un réseau de pas 0,05 : le niveau 0,5 est le cercle r = √(ln 2).
        int n = 81;
        float[] f = new float[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double x = (i - 40) * .05, y = (j - 40) * .05;
                f[j * n + i] = (float) Math.exp(-(x * x + y * y));
            }
        }
        List<double[]> lines = DeformationMap.contour(f, n, n, .5);
        assertEquals(1, lines.size());
        double[] line = lines.get(0);
        assertEquals(line[0], line[line.length - 2], 1e-12);        // fermée : elle revient à son premier point
        assertEquals(line[1], line[line.length - 1], 1e-12);
        for (int k = 0; k < line.length; k += 2) {
            double r = Math.hypot((line[k] - 40) * .05, (line[k + 1] - 40) * .05);
            assertEquals(Math.sqrt(Math.log(2)), r, .01);
        }
        assertTrue(DeformationMap.contour(f, n, n, 2).isEmpty());   // au-dessus du sommet : rien
    }

    @Test
    void theDeformationMapIntegratesAlongTheLineOfSight() {
        // Δρ = 2 e/Å³ dans une boîte de 4 Å de côté : vue de face, la colonne vaut 2 × 4 = 8 e/Å².
        float[] values = new float[9 * 9 * 9];
        java.util.Arrays.fill(values, 2f);
        DensityGrid grid = DensityGrid.of(DensityGrid.DEFORMATION, 9, 9, 9, 0, 0, 0, .5, values);
        assertEquals(8, DeformationMap.column(grid, new double[]{2, 2, -10, 0, 0, 1}), 1e-6);
        assertEquals(0, DeformationMap.column(grid, new double[]{9, 9, -10, 0, 0, 1}), 1e-12);   // à côté
    }

    @Test
    void theDotsAreDrawnFromPsiSquaredAndNeverOnTheNode() {
        // ψ = x e^(−r²) : deux lobes, un nœud dans le plan x = 0. Les points suivent |ψ|² : aucun sur le nœud, la
        // couleur de chacun dit le signe de ψ, et ⟨x²⟩ vaut celui de la loi (3/4 pour x² e^(−2x²)).
        int n = 41;
        double h = .15;
        float[] values = new float[n * n * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) for (int k = 0; k < n; k++) {
            double x = (i - 20) * h, y = (j - 20) * h, z = (k - 20) * h;
            values[(i * n + j) * n + k] = (float) (x * Math.exp(-(x * x + y * y + z * z)));
        }
        DensityGrid psi = DensityGrid.of(DensityGrid.ORBITAL, n, n, n, -3, -3, -3, h, values);
        float[] dots = OrbitalDots.sample(psi, 4000, 7);
        assertEquals(4 * 4000, dots.length);
        double x2 = 0;
        for (int d = 0; d < 4000; d++) {
            float x = dots[4 * d];
            assertTrue(Math.abs(x) > 1e-4, "un point sur le nœud");
            assertEquals(Math.signum(x), dots[4 * d + 3], 0);                       // bleu x > 0, orange x < 0
            x2 += x * x / 4000;
        }
        assertEquals(.75, x2, .05);
        // La même suite au hasard d'une grille à l'autre : une orbitale à peine changée garde ses points.
        float[] again = OrbitalDots.sample(psi, 4000, 7);
        assertArrayEquals(dots, again);
        // Vue le long de l'axe où le nuage est le plus mince : jamais le long de l'axe des deux lobes (x).
        double[] axis = MoleculeScene.thinnestCloudAxis(psi);
        assertTrue(Math.abs(axis[0]) < .05, "axe " + java.util.Arrays.toString(axis));
    }

    @Test
    void grainsAreDrawnFromTheProfileBetweenTheBubbles() {
        // Sans profil : uniformes entre les bulles.
        double[] uniform = MoleculeScene.profileLaw(new float[0], .2, .8);
        assertEquals(.2 + .6 * .25, MoleculeScene.sample(uniform, .2, .8, .25), 1e-12);
        // Toute la paire dans la quatrième tranche sur dix (0,3–0,4) : les grains y tombent tous.
        float[] profile = new float[10];
        profile[3] = 1;
        double[] law = MoleculeScene.profileLaw(profile, .25, .75);
        for (double x = .01; x < 1; x += .07) {
            double t = MoleculeScene.sample(law, .25, .75, x);
            assertTrue(t >= .3 - 1e-12 && t <= .4 + 1e-12, "t = " + t);
        }
        // La part d'une tranche coupée par une bulle compte au prorata de ce qui en dépasse.
        float[] two = new float[10];
        two[2] = .5f;
        two[5] = .5f;
        law = MoleculeScene.profileLaw(two, .25, .75);                // la tranche 0,2–0,3 n'a que 0,25–0,3 de libre
        assertEquals(1 / 3.0, law[3], 1e-6);
    }

    @Test
    void multipleBondsAreFlatStrandsOrTwistedOnes() {
        MoleculeScene scene = new MoleculeScene();
        scene.z = new int[]{6, 6};
        scene.positions = new double[][]{{0, 0, 0}, {1.33, 0, 0}};
        scene.covalent = new double[]{.75, .75};
        double[] u = {1, 0, 0}, start = {.34, 0, 0};
        // Éthylène : sa part π d'un seul côté (la normale z) — deux brins à plat, côte à côte dans le plan, sans torsion.
        MoleculeScene.Bond ethene = new MoleculeScene.Bond(0, 1, 2.0, 1, 1, .5, new double[]{0, 0, 1}, new double[]{1, .03},
                new float[0], 1);
        double[][][] flat = scene.strands(ethene, start, u, .65, 2);
        for (double[][] path : flat) for (double[] q : path) assertEquals(0, q[2], 1e-9);
        int middle = MoleculeScene.STRAND_POINTS / 2;
        assertTrue(flat[0][middle][1] * flat[1][middle][1] < 0, "de part et d'autre de l'axe");
        // Acétylène : sa part π tout autour — trois brins qui font un demi-tour d'un bout à l'autre.
        MoleculeScene.Bond yne = new MoleculeScene.Bond(0, 1, 3.0, 1, 2, .5, new double[]{0, 0, 1}, new double[]{1, 1},
                new float[0], 1);
        double[][][] twisted = scene.strands(yne, start, u, .45, 3);
        assertEquals(3, twisted.length);
        for (double[][] path : twisted) {
            double[] a = path[0], b = path[path.length - 1];
            assertTrue(a[1] * b[1] + a[2] * b[2] < 0, "un demi-tour : le brin passe de l'autre côté de l'axe");
        }
        // Une simple : un brin, sur l'axe.
        double[][][] single = scene.strands(new MoleculeScene.Bond(0, 1, 1.0, 1, 0, .5), start, u, .7, 1);
        for (double[] q : single[0]) assertEquals(0, Math.hypot(q[1], q[2]), 1e-12);
    }

    @Test
    void aNeutralAtomAndAPositiveOneNoLongerLookAlike() {
        javafx.scene.paint.Color neutral = MoleculeScene.rimColour(0), plus = MoleculeScene.rimColour(.15),
                minus = MoleculeScene.rimColour(-.15);
        // Un neutre presque gris ; δ+ cyan et δ− magenta, nets dès 0,15 e.
        assertTrue(neutral.getSaturation() < .2, "neutre : argent, pas bleu (" + neutral.getSaturation() + ")");
        assertTrue(plus.getSaturation() > .25 && plus.getHue() > 170 && plus.getHue() < 210, plus.toString());
        assertTrue(minus.getSaturation() > .25 && (minus.getHue() > 280 || minus.getHue() < 20), minus.toString());
        assertEquals(1, MoleculeScene.chargeTint(1), 1e-12);
        assertEquals(0, MoleculeScene.chargeTint(0), 1e-12);
    }

    @Test
    void theFreeCameraTurnsAboutTheScreenAxesAndFollowsTheHand() {
        // Ce qui est devant la cible (côté œil) suit la main : à droite quand on glisse à droite, en bas vers le bas.
        for (int axis = 0; axis < 2; axis++) {
            LabCamera camera = new LabCamera();
            camera.free = true;
            camera.setup(800, 600, 400, 300, 10);
            double[] f = camera.forward(), front = {-.5 * f[0], -.5 * f[1], -.5 * f[2]};
            camera.drag(axis == 0 ? 40 : 0, axis == 1 ? 40 : 0);
            camera.setup(800, 600, 400, 300, 10);
            double[] p = camera.project(front[0], front[1], front[2]);
            if (axis == 0) assertTrue(p[0] > 401 && Math.abs(p[1] - 300) < 1e-6, "à droite : " + p[0] + ", " + p[1]);
            else assertTrue(p[1] > 301 && Math.abs(p[0] - 400) < 1e-6, "en bas : " + p[0] + ", " + p[1]);
        }
        // Sans butée ni pôle : on passe par-dessous, la base reste orthonormée.
        LabCamera camera = new LabCamera();
        camera.free = true;
        boolean below = false;
        for (int k = 0; k < 400; k++) {
            camera.drag(0, 5);
            camera.setup(800, 600, 400, 300, 10);
            double[] f = camera.forward(), u = camera.up(), r = camera.right();
            below |= f[1] > .5;
            assertEquals(1, Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]), 1e-9);
            assertEquals(0, f[0] * u[0] + f[1] * u[1] + f[2] * u[2], 1e-9);
            assertEquals(0, f[0] * r[0] + f[1] * r[1] + f[2] * r[2], 1e-9);
        }
        assertTrue(below, "la vue regarde aussi la molécule par-dessous");
        // Une vue visée (orient) est rejointe en douceur, comme l'orbite habituelle.
        camera.orient(.3, .5);
        for (int k = 0; k < 600; k++) camera.tick(1 / 60.0, false, -5, 5);
        camera.setup(800, 600, 400, 300, 10);
        double[] f = camera.forward();
        assertEquals(-Math.cos(.5) * Math.sin(.3), f[0], 1e-6);
        assertEquals(-Math.sin(.5), f[1], 1e-6);
        assertEquals(-Math.cos(.5) * Math.cos(.3), f[2], 1e-6);
    }

    @Test
    void theWheelKeepsThePointedAtomUnderTheCursorAndTheViewCanBeMovedCentredAndReset() {
        MoleculeScene scene = new MoleculeScene();
        scene.positions = new double[][]{{-3, 0, 0}, {3, 0, 0}, {3, 1.2, 0}};
        scene.z = new int[]{8, 8, 1};
        double dt = 1 / 60.0;
        scene.aim(1000, 800, dt, false);
        // La molette sur l'atome de droite : il grossit et reste sous le curseur.
        double[] p = scene.camera.project(3, 0, 0);
        scene.zoomAt(p[0], p[1], 3);
        for (int k = 0; k < 120; k++) scene.aim(1000, 800, dt, false);
        double[] q = scene.camera.project(3, 0, 0);
        assertEquals(p[0], q[0], .5);
        assertEquals(p[1], q[1], .5);
        assertTrue(q[3] > 2 * p[3], "plus près : " + p[3] + " → " + q[3]);
        // Glisser la vue : ce qui est au point visé suit la main, pixel pour pixel.
        double[] target = scene.center(), before = scene.camera.project(target[0], target[1], target[2]);
        scene.panBy(100, -40);
        scene.aim(1000, 800, 0, false);
        double[] after = scene.camera.project(target[0], target[1], target[2]);
        assertEquals(before[0] + 100, after[0], .5);
        assertEquals(before[1] - 40, after[1], .5);
        // Centrer un atome : il vient au centre de la vue et y reste quand il bouge.
        scene.focusOn(new int[]{0}, false);
        for (int k = 0; k < 300; k++) scene.aim(1000, 800, dt, false);
        double[] c = scene.camera.project(-3, 0, 0);
        assertEquals(scene.camera.centerX(), c[0], 1);
        assertEquals(scene.camera.centerY(), c[1], 1);
        scene.positions[0] = new double[]{-3, 1, 0};
        for (int k = 0; k < 300; k++) scene.aim(1000, 800, dt, false);
        c = scene.camera.project(-3, 1, 0);
        assertEquals(scene.camera.centerX(), c[0], 1);
        assertTrue(scene.navigated);
        // Tout voir : la vue revient sur le centre de la boîte de toutes les molécules (pas leur barycentre).
        scene.showAll();
        for (int k = 0; k < 400; k++) scene.aim(1000, 800, dt, false);
        double[] all = scene.camera.project(0, .6, 0);
        assertEquals(scene.camera.centerX(), all[0], 1);
        assertEquals(scene.camera.centerY(), all[1], 1);
        assertFalse(scene.navigated);
        assertEquals(0, scene.focus().length);
    }

    @Test
    void thePistonSaysWhereItGoesAndWhenItIsThere() {
        // Loin sous la consigne, la paroi se resserre ; loin au-dessus, elle s'écarte ; à 25 % près, c'est l'équilibre
        // (deux ou trois molécules : la pression fluctue autour de sa moyenne).
        assertTrue(MoleculeView.pistonMotion(9000, 20000).contains("resserre"));
        assertTrue(MoleculeView.pistonMotion(30000, 20000).contains("écarte"));
        assertTrue(MoleculeView.pistonMotion(17520, 20000).contains("équilibre"));
        // Le manomètre et les chevrons de la sphère disent la même chose que la phrase.
        assertEquals(-1, MoleculeScene.pistonDirection(9000, 20000));
        assertEquals(1, MoleculeScene.pistonDirection(30000, 20000));
        assertEquals(0, MoleculeScene.pistonDirection(17520, 20000));
        assertEquals(0, MoleculeScene.pistonDirection(500, 0));                  // pas de piston : rien ne bouge
        // L'échelle du cadran : logarithmique, 10 à 30 000 atm ; chaque décade y prend la même place.
        assertEquals(0, MoleculeScene.gaugeFraction(5), 1e-12);
        assertEquals(1, MoleculeScene.gaugeFraction(30000), 1e-12);
        assertEquals(MoleculeScene.gaugeFraction(1000) - MoleculeScene.gaugeFraction(100),
                MoleculeScene.gaugeFraction(10000) - MoleculeScene.gaugeFraction(1000), 1e-12);
    }

    @Test
    void theEnclosureStaysStillWhileTheMoleculesMoveInsideIt() {
        // Dans une enceinte, la vue se pose sur son centre (l'origine, où est la paroi) : quand les molécules se
        // déplacent dedans, la sphère ne bouge pas à l'écran. Sans paroi, la vue suit les molécules.
        MoleculeScene scene = new MoleculeScene();
        scene.positions = new double[][]{{2, 0, 0}, {3, 0, 0}};
        scene.z = new int[]{1, 1};
        scene.wall = 5;
        double dt = 1 / 60.0;
        for (int k = 0; k < 200; k++) scene.aim(1000, 800, dt, false);
        double[] before = scene.camera.project(0, 0, 0);
        scene.positions = new double[][]{{-3, 1, 0}, {-2, 1, 0}};
        for (int k = 0; k < 200; k++) scene.aim(1000, 800, dt, false);
        double[] after = scene.camera.project(0, 0, 0);
        assertEquals(before[0], after[0], .5);
        assertEquals(before[1], after[1], .5);
        assertEquals(scene.camera.centerX(), after[0], 1);
        scene.wall = 0;
        for (int k = 0; k < 300; k++) scene.aim(1000, 800, dt, false);
        double[] middle = scene.camera.project(-2.5, 1, 0);
        assertEquals(scene.camera.centerX(), middle[0], 1);
    }
}
