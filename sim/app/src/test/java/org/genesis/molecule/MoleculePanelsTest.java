package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeLabState;
import com.studio.genesis.grpc.MoleculeOrbital;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Les panneaux de lecture : le bilan d'énergie, la cible des niveaux, la carte de l'atome, la vue d'une orbitale. */
class MoleculePanelsTest {
    @Test
    void theBalanceTermsAddUpToTheBindingEnergy() {
        // L'eau telle que le moteur la calcule (eV) : la bande lie, le reste coûte, la somme est l'énergie de liaison.
        MoleculeLabState water = MoleculeLabState.newBuilder()
                .setBand(-120.20).setBandAtoms(-101.03).setCoulomb2(1.40).setCoulomb3(.02).setCoulombAtoms(.06)
                .setRepulsion(1.03).setDispersion(-.004).setHalogen(0).setEntropy(-.10).setEntropyAtoms(-.199)
                .setBinding(-16.685).build();
        List<EnergyBalance.Term> terms = EnergyBalance.terms(water);
        double sum = 0;
        for (EnergyBalance.Term t : terms) sum += t.value();
        assertEquals(-120.20 + 101.03 + 1.40 + .02 - .06 + 1.03 - .004 - .10 + .199, sum, 1e-12);
        assertEquals(water.getBinding(), sum, 1e-9);
        // Rangés par leur signe, du plus liant au plus coûteux ; la liaison halogène, nulle, n'apparaît pas.
        assertEquals("orbitales qui se recouvrent", terms.get(0).name());
        assertTrue(terms.get(0).value() < 0 && terms.get(terms.size() - 1).value() > 0);
        for (int k = 1; k < terms.size(); k++) {
            boolean bothBind = terms.get(k - 1).value() < 0 && terms.get(k).value() < 0;
            if (bothBind) assertTrue(terms.get(k - 1).value() <= terms.get(k).value());
        }
        assertTrue(terms.stream().noneMatch(t -> t.name().equals("liaison halogène")));
        // Au₆ : l'électrostatique y lie ; elle passe du côté de ce qui lie.
        MoleculeLabState gold = water.toBuilder().setCoulomb2(-1.70).setCoulomb3(.08).setCoulombAtoms(0).build();
        assertTrue(EnergyBalance.terms(gold).stream()
                .anyMatch(t -> t.name().equals("électrostatique des charges") && t.colour().getBlue() > t.colour().getRed()));
    }

    private static MoleculeOrbital level(double ev, double electrons) {
        return MoleculeOrbital.newBuilder().setEnergyEv(ev).setOccupation(electrons).build();
    }

    @Test
    void theTargetGroupsDegenerateLevelsAndNamesTheirRingByTheHomo() {
        // Une HOMO deux fois dégénérée (comme la π du benzène), une LUMO aussi, et un niveau vide très haut.
        List<MoleculeOrbital> orbitals = new ArrayList<>(List.of(level(-20, 2), level(-15, 2), level(-12, 2), level(-12.005, 2),
                level(-5, 0), level(-4.99, 0), level(30, 0)));
        EnergyTarget target = new EnergyTarget();
        target.layout(orbitals, 3, 270, 264);
        List<EnergyTarget.Ring> rings = target.rings();
        assertEquals(4, rings.size());                              // −20, −15, −12 ×2, −5 ×2 ; +30 eV reste dehors
        assertEquals(2, rings.get(2).count());
        assertEquals(4, rings.get(2).electrons(), 1e-12);
        // Le rayon suit l'énergie, linéairement : deux écarts égaux, deux pas égaux.
        double step = (rings.get(1).radius() - rings.get(0).radius()) / 5;
        assertEquals(step * 3, rings.get(2).radius() - rings.get(1).radius(), .02);
        // Le cercle de la HOMO s'appelle HOMO (pas HOMO−1), et un clic dessus montre la HOMO.
        assertEquals(3, target.representative(rings.get(2)));
        assertEquals("HOMO", EnergyTarget.name(target.representative(rings.get(2)), 3));
        assertEquals(4, target.representative(rings.get(3)));
        assertEquals(3, target.pick(135 + rings.get(2).radius(), 132));
        assertEquals(-1, target.pick(135, 132));                    // le cœur : la lecture, pas un niveau
    }

    @Test
    void aPairPushesItsTwoAtomsOppositeWays() {
        MoleculeScene scene = new MoleculeScene();
        scene.positions = new double[][]{{0, 0, 0}, {1, 0, 0}};
        MoleculeScene.Pair sent = new MoleculeScene.Pair(0, 1, 1, 1, .5, -.2, 0, .3, new double[]{.3, .1, 0});
        assertArrayEquals(new double[]{.3, .1, 0}, AtomMap.pairForce(scene, sent, 0), 1e-12);
        assertArrayEquals(new double[]{-.3, -.1, 0}, AtomMap.pairForce(scene, sent, 1), 1e-12);
        // Sans le vecteur (un moteur plus ancien) : la seule part le long de l'axe, + vers l'autre atome.
        MoleculeScene.Pair axial = new MoleculeScene.Pair(0, 1, 1, 1, .5, -.2, 0, .3, null);
        assertArrayEquals(new double[]{.3, 0, 0}, AtomMap.pairForce(scene, axial, 0), 1e-12);
        assertArrayEquals(new double[]{-.3, 0, 0}, AtomMap.pairForce(scene, axial, 1), 1e-12);
    }

    @Test
    void theSketchLayoutIsATextbookDrawingNotAProjection() {
        // Un cycle à six chaînons tordu (vu de biais, une liaison longue) et un substituant : le schéma le redresse —
        // toutes les liaisons égales, le cycle hexagone régulier (120°), le substituant à 120° de ses deux voisins.
        double[][] start = new double[7][];
        for (int k = 0; k < 6; k++) {
            double a = Math.PI / 3 * k;
            start[k] = new double[]{1.4 * Math.cos(a) * (k == 2 ? 1.3 : 1), .6 * Math.sin(a)};
        }
        start[6] = new double[]{2.9, .2};
        java.util.List<int[]> edges = new java.util.ArrayList<>();
        for (int k = 0; k < 6; k++) edges.add(new int[]{k, (k + 1) % 6, k % 2 == 0 ? 2 : 1});
        edges.add(new int[]{0, 6, 1});
        boolean[] drawn = new boolean[7];
        java.util.Arrays.fill(drawn, true);
        double[][] p = MoleculeSketch.layout(start, drawn, edges);
        for (int[] e : edges) assertEquals(1, Math.hypot(p[e[1]][0] - p[e[0]][0], p[e[1]][1] - p[e[0]][1]), .03);
        for (int k = 0; k < 6; k++) {
            double[] a = p[(k + 5) % 6], b = p[k], c = p[(k + 1) % 6];
            double angle = Math.toDegrees(Math.acos(((a[0] - b[0]) * (c[0] - b[0]) + (a[1] - b[1]) * (c[1] - b[1]))
                    / (Math.hypot(a[0] - b[0], a[1] - b[1]) * Math.hypot(c[0] - b[0], c[1] - b[1]))));
            assertEquals(120, angle, 3);
        }
        double[] o = p[0], s = p[6], r = p[1];
        double angle = Math.toDegrees(Math.acos(((s[0] - o[0]) * (r[0] - o[0]) + (s[1] - o[1]) * (r[1] - o[1]))
                / (Math.hypot(s[0] - o[0], s[1] - o[1]) * Math.hypot(r[0] - o[0], r[1] - o[1]))));
        assertEquals(120, angle, 4);
    }

    @Test
    void theSketchLiesFlatAndWritesWhatAChemistWrites() {
        // Les axes propres d'une matrice symétrique (Jacobi), croissants.
        double[][] axes = new double[3][3];
        double[] values = AtomMap.eigen(new double[][]{{2, 1, 0}, {1, 2, 0}, {0, 0, 5}}, axes);
        assertArrayEquals(new double[]{1, 3, 5}, values, 1e-9);
        assertEquals(1, Math.abs(axes[2][2]), 1e-9);
        // L'éthylène dans le plan xz : le schéma est ce plan (sa normale, y, en est absente), vu de face.
        double[][] ethylene = {{-.67, 0, 0}, {.67, 0, 0}, {-1.23, 0, .92}, {-1.23, 0, -.92}, {1.23, 0, .92}, {1.23, 0, -.92}};
        MoleculeSketch sketch = new MoleculeSketch();
        sketch.plane(ethylene, "C2H4:6");
        double[][] flat = sketch.flatten(ethylene);
        assertEquals(1.34, Math.hypot(flat[1][0] - flat[0][0], flat[1][1] - flat[0][1]), 1e-6);
        assertEquals(1.84, Math.hypot(flat[2][0] - flat[3][0], flat[2][1] - flat[3][1]), 1e-6);
        // Les hydrogènes rassemblés dans l'étiquette de leur atome, du bon côté : CH₃ / H₃C, OH / HO, NH₂.
        java.util.List<MoleculeScene.Bond> bonds = java.util.List.of(new MoleculeScene.Bond(0, 1, 1, 1, 0, .5),
                new MoleculeScene.Bond(0, 2, 1, 1, 0, .5), new MoleculeScene.Bond(0, 3, 1, 1, 0, .5),
                new MoleculeScene.Bond(0, 4, 1, 1, 0, .5), new MoleculeScene.Bond(4, 5, 1, 1, 0, .5));
        int[] methanol = {6, 1, 1, 1, 8, 1};
        assertArrayEquals(new int[]{3, 0, 0, 0, 1, 0}, MoleculeSketch.hydrogens(methanol, bonds));
        assertEquals("CH₃", MoleculeSketch.label("C", 3, false));
        assertEquals("H₃C", MoleculeSketch.label("C", 3, true));
        assertEquals("OH", MoleculeSketch.label("O", 1, false));
        assertEquals("H₂N", MoleculeSketch.label("N", 2, true));
        assertEquals("N", MoleculeSketch.label("N", 0, false));
        // Le schéma suit la vue : tourné de 90° et retourné en miroir, il retrouve l'angle et le miroir.
        double[][] sheet = {{0, 0}, {1, 0}, {1.5, .87}, {-.5, .87}};
        double[][] view = new double[4][];
        for (int i = 0; i < 4; i++) view[i] = new double[]{-sheet[i][1] * 1.2, -sheet[i][0] * 1.2};   // miroir puis 90°
        boolean[] all = {true, true, true, true};
        double[] fitted = MoleculeSketch.fit(sheet, view, all);
        assertEquals(1, fitted[1], 0);
        double[] plain = MoleculeSketch.fit(sheet, new double[][]{{0, 0}, {0, 1}, {-.87, 1.5}, {-.87, -.5}}, all);
        assertEquals(0, plain[1], 0);
        assertEquals(Math.PI / 2, plain[0], 1e-9);
        // Un, deux ou trois traits selon l'indice de Mayer ; le second d'une liaison conjuguée en tirets.
        assertEquals(1, MoleculeScene.strokes(.97));
        assertEquals(2, MoleculeScene.strokes(1.44));
        assertTrue(MoleculeScene.partial(1.44));
        assertEquals(2, MoleculeScene.strokes(2.04));
        assertFalse(MoleculeScene.partial(2.04));
        assertEquals(3, MoleculeScene.strokes(2.9));
    }

    @Test
    void theInspectorSaysWhyTwoAtomsPushOrPull() {
        // La caféine, N6 et ses voisins non liés (calculés) : C7 — tout est recouvrement, population antiliante.
        MoleculeScene.Pair steric = new MoleculeScene.Pair(5, 6, 2.25, .05, -1.455, -.003, -.012, -1.47, null, -1.454, -.001, -.047);
        assertEquals("gêne stérique : leurs couches pleines se touchent", AtomMap.cause(steric, -.40, -.01));
        // Deux charges de même signe dont l'électrostatique domine.
        MoleculeScene.Pair like = new MoleculeScene.Pair(0, 1, 2.8, .01, -.9, 0, 0, -.9, null, -.1, -.8, -.01);
        assertTrue(AtomMap.cause(like, -.5, -.4).startsWith("charges de même signe"));
        // Une liaison hydrogène : O···H s'attirent par leurs charges opposées.
        MoleculeScene.Pair hydrogen = new MoleculeScene.Pair(0, 1, 1.9, .05, .3, -.05, .02, .27, null, .05, .25, .01);
        assertTrue(AtomMap.cause(hydrogen, -.6, .3).startsWith("charges opposées"));
        MoleculeScene.Pair london = new MoleculeScene.Pair(0, 1, 3.5, 0, .005, 0, .03, .035, null, .005, 0, 0);
        assertEquals("London (van der Waals)", AtomMap.cause(london, 0, 0));
        // La nature d'une liaison suit sa part π : C11–N6 de la caféine (π 0,33) est en partie double.
        MoleculeScene.Bond conjugated = new MoleculeScene.Bond(5, 10, 1.28, .95, .33, .54, null, new double[2], new float[0], 1);
        assertEquals("liaison en partie double (conjuguée)", AtomMap.character(conjugated));
        assertEquals("liaison double", AtomMap.character(new MoleculeScene.Bond(0, 1, 2.04, 1, 1.03, .5, null, new double[2], new float[0], 1)));
        assertEquals("liaison simple", AtomMap.character(new MoleculeScene.Bond(0, 1, .97, .97, 0, .5, null, new double[2], new float[0], 1)));
        // Deux voisins presque dans la même direction s'écartent juste assez ; les autres ne bougent pas.
        double[] angles = {.10, .15, 2.0};
        AtomMap.spread(angles, .5);
        assertEquals(.5, Math.abs(angles[1] - angles[0]), 1e-9);
        assertEquals(.125, (angles[0] + angles[1]) / 2, 1e-9);
        assertEquals(2.0, angles[2], 1e-12);
    }

    @Test
    void theGlassLobesAreClosedAndHoldTheirShare() {
        // ψ = x e^(−r²) : deux lobes, + pour x > 0, − pour x < 0, autour du nœud x = 0.
        DensityGrid p = orbital(new java.util.function.DoubleUnaryOperator[]{
                x -> x * Math.exp(-x * x), y -> Math.exp(-y * y), z -> Math.exp(-z * z)});
        double level = OrbitalSurface.level(p, .85), inside = 0, total = 0;
        for (float v : p.values) {
            total += v * v;
            if (Math.abs(v) >= level) inside += v * v;
        }
        assertEquals(.85, inside / total, .01);
        OrbitalSurface.Mesh mesh = OrbitalSurface.build(p, .85);
        assertTrue(mesh.count() > 500);
        // Fermée : chaque arête est partagée par deux triangles, et χ = V − E + F = 2 par lobe (deux sphères : 4).
        java.util.Map<Long, Integer> edges = new java.util.HashMap<>();
        int[] tri = mesh.triangles();
        for (int k = 0; k < mesh.count(); k++) {
            for (int a = 0; a < 3; a++) {
                long u = tri[3 * k + a], v = tri[3 * k + (a + 1) % 3];
                edges.merge(Math.min(u, v) * 1_000_000L + Math.max(u, v), 1, Integer::sum);
            }
        }
        assertTrue(edges.values().stream().allMatch(c -> c == 2), "une arête au bord : la surface a un trou");
        assertEquals(4, mesh.vertices() - edges.size() + mesh.count());
        float[] pts = mesh.points(), normals = mesh.normals();
        for (int v = 0; v < mesh.vertices(); v++) {
            double x = pts[3 * v], y = pts[3 * v + 1], z = pts[3 * v + 2];
            assertEquals(mesh.phase()[v] > 0, x > 0);                       // le lobe bleu du côté où ψ > 0
            double cx = mesh.phase()[v] > 0 ? .7 : -.7;                       // le centre de chaque lobe
            assertTrue(normals[3 * v] * (x - cx) + normals[3 * v + 1] * y + normals[3 * v + 2] * z > 0, "normale rentrante");
            assertEquals(level, Math.abs(x * Math.exp(-(x * x + y * y + z * z))), .08 * level);
        }
        // Chaque triangle tourne sa face avant vers l'extérieur (pour le masquage des faces arrière) ; ceux presque sans
        // aire (un sommet sur un coin de maille), invisibles, n'ont pas de sens mesurable.
        for (int k = 0; k < mesh.count(); k++) {
            int a = tri[3 * k], b = tri[3 * k + 1], c = tri[3 * k + 2];
            double ux = pts[3 * b] - pts[3 * a], uy = pts[3 * b + 1] - pts[3 * a + 1], uz = pts[3 * b + 2] - pts[3 * a + 2];
            double vx = pts[3 * c] - pts[3 * a], vy = pts[3 * c + 1] - pts[3 * a + 1], vz = pts[3 * c + 2] - pts[3 * a + 2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            if (Math.sqrt(nx * nx + ny * ny + nz * nz) < 1e-4) continue;
            assertTrue(nx * normals[3 * a] + ny * normals[3 * a + 1] + nz * normals[3 * a + 2] > 0, "triangle " + k);
        }
    }

    private static DensityGrid orbital(java.util.function.DoubleUnaryOperator[] f) {
        int n = 41;
        double h = .2;
        float[] values = new float[n * n * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) for (int k = 0; k < n; k++) {
            double x = (i - 20) * h, y = (j - 20) * h, z = (k - 20) * h;
            values[(i * n + j) * n + k] = (float) (f[0].applyAsDouble(x) * f[1].applyAsDouble(y) * f[2].applyAsDouble(z));
        }
        return DensityGrid.of(DensityGrid.ORBITAL, n, n, n, -4, -4, -4, h, values);
    }

    @Test
    void aPiOrbitalSeenFaceOnIsTiltedSoItsPhasesSeparate() {
        // ψ = z e^(−r²) : vue le long de z, chaque ligne de visée traverse le lobe + puis le lobe − ; le long de x, une seule.
        DensityGrid p = orbital(new java.util.function.DoubleUnaryOperator[]{
                x -> Math.exp(-x * x), y -> Math.exp(-y * y), z -> z * Math.exp(-z * z)});
        assertTrue(MoleculeScene.phaseOverlap(p, new double[]{0, 0, 1}) > .9);
        assertTrue(MoleculeScene.phaseOverlap(p, new double[]{1, 0, 0}) < .1);
        // Une π de cycle (comme celle du benzène) : étalée dans le plan xy, plus le long de x, signe ± au-dessus et
        // au-dessous. Le nuage le plus mince est le long de z : vue de face, les phases se mêleraient ; la vue
        // s'incline de 40° autour du grand axe (x).
        DensityGrid ring = orbital(new java.util.function.DoubleUnaryOperator[]{
                x -> x * Math.exp(-x * x / 4), y -> Math.exp(-y * y / 4), z -> z * Math.exp(-z * z)});
        double[] view = MoleculeScene.cloudView(ring);
        assertEquals(Math.cos(Math.toRadians(40)), Math.abs(view[2]), .02);
        assertEquals(Math.sin(Math.toRadians(40)), Math.abs(view[1]), .02);
        assertEquals(0, view[0], .02);
        // Une σ dans le plan xy, vue de face : rien à incliner.
        DensityGrid sigma = orbital(new java.util.function.DoubleUnaryOperator[]{
                x -> x * Math.exp(-x * x / 2), y -> Math.exp(-y * y / 2), z -> Math.exp(-2 * z * z)});
        double[] flat = MoleculeScene.cloudView(sigma);
        assertEquals(1, Math.abs(flat[2]), .01);
    }
}
