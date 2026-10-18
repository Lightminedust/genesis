package org.genesis.subatomic;

import org.genesis.common.LabCamera;
import org.genesis.app.I18n;

import com.studio.genesis.grpc.CollisionChannel;
import com.studio.genesis.grpc.CollisionCurve;
import com.studio.genesis.grpc.CollisionState;
import com.studio.genesis.grpc.CollisionStep;
import com.studio.genesis.grpc.HadronLabState;
import com.studio.genesis.grpc.HadronProduct;
import com.studio.genesis.grpc.NucleonCluster;
import com.studio.genesis.grpc.NucleonQuark;
import com.studio.genesis.grpc.NucleonSample;
import com.studio.genesis.grpc.NucleonState;
import com.studio.genesis.grpc.SmParticle;
import com.studio.genesis.grpc.StandardModelState;
import com.studio.genesis.grpc.UniverseFrame;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class QuantumLabTest {
    private static I18n.Language previousLanguage;

    @BeforeAll static void toolkit() throws Exception {
        previousLanguage = I18n.language();
        I18n.setLanguage(I18n.Language.FR);
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); }); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @AfterAll static void restoreLanguage() {
        I18n.setLanguage(previousLanguage);
    }

    @Test void captionsKeepGreekLettersAndCombiningBars() {
        assertEquals("P R O T O N", DocStyle.caps("proton"));
        assertTrue(DocStyle.caps("π⁰").startsWith("π"), "π ne devient pas Π");
        assertEquals("Ū D", DocStyle.caps("ūd"), "la barre reste collée à sa lettre");
    }

    @Test void tableAndBenchSpellPhysicsCorrectly() {
        assertEquals("2,16 MeV", SmTable.mass(2.16));
        assertEquals("173 GeV", SmTable.mass(172570));
        assertEquals("511 keV", SmTable.mass(.511));
        assertEquals("+2/3", SmTable.charge(2 / 3.0));
        assertEquals("−1", SmTable.charge(-1));
        assertEquals("½", SmTable.spin(.5));
        assertEquals("ud̄", HadronBench.pretty("uD"));
        assertEquals("p + e⁻ + ν̄e", HadronBench.pretty("p + e- + ν̄e"));
        assertEquals("K⁻ + π⁺", HadronBench.pretty("K- + π+"));
        assertEquals("neutron", CollisionScene.pretty("n"));
        assertEquals("π⁰", CollisionScene.pretty("π0"));
    }

    @Test void viewRayThroughAProjectedPointPassesThroughIt() {
        LabCamera camera = new LabCamera();
        camera.yaw = -Math.PI / 2 + .6;
        camera.pitch = .3;
        camera.setup(1366, 586, 683, 293, 6);
        double[] p = {1.9, -.4, -2.5};
        double[] s = camera.project(p[0], p[1], p[2]);
        double[] ray = camera.ray(s[0], s[1]);
        double[] d = {p[0] - ray[0], p[1] - ray[1], p[2] - ray[2]};
        double t = d[0] * ray[3] + d[1] * ray[4] + d[2] * ray[5];
        double miss = Math.sqrt(Math.pow(d[0] - t * ray[3], 2) + Math.pow(d[1] - t * ray[4], 2) + Math.pow(d[2] - t * ray[5], 2));
        assertEquals(1, Math.hypot(Math.hypot(ray[3], ray[4]), ray[5]), 1e-12);
        assertTrue(t > 0, "le point est devant la caméra");
        assertEquals(0, miss, 1e-9, "le disque de champ est tracé là où les objets sont projetés");
    }

    @Test void theFilmSlowsDownAroundTheHit() {
        assertTrue(CollisionScene.rate(.5) < .3, "ralenti au moment du choc");
        assertEquals(1.0, CollisionScene.rate(-10), 1e-9);
        assertEquals(1.0, CollisionScene.rate(12), 1e-9);
    }

    @Test void partonsMoveAtTheSpeedOfLightAndStayInsideTheNucleon() {
        double[] start = {.3, -.2, .1};
        for (int id = 0; id < 50; id++) {
            double[] d = PartonView.direction(id);
            assertEquals(1.0, Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-9);
            double[] p = PartonView.billiard(start, d, 7.3, 1.08);
            assertTrue(Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]) <= 1.08 + 1e-9, "confiné dans le sac");
        }
        double[] small = PartonView.billiard(new double[]{0, 0, 0}, new double[]{1, 0, 0}, .5, 1.08);
        assertEquals(.5, small[0], 1e-12, "en ligne droite à c tant qu'il ne touche pas la paroi");
        double[] bounced = PartonView.billiard(new double[]{0, 0, 0}, new double[]{1, 0, 0}, 1.5, 1.0);
        assertEquals(.5, bounced[0], 1e-9, "réfléchi sur la paroi : 1 fm aller, 0,5 fm retour");
    }

    @Test void benchPlacesQuarksAroundTheCentreWithoutOverlap() {
        for (int n = 1; n <= 5; n++) {
            double[] centre = new double[3];
            for (int i = 0; i < n; i++) {
                double[] p = HadronBench.slotPosition(i, n);
                for (int k = 0; k < 3; k++) centre[k] += p[k] / n;
                for (int j = 0; j < i; j++) assertTrue(SubRender.norm(SubRender.sub(p, HadronBench.slotPosition(j, n))) > .4, n + " quarks");
            }
            if (n != 5) assertEquals(0, SubRender.norm(centre), 1e-9, "centré");
        }
    }

    @Test void vectorHelpers() {
        double[] u = {0, 0, 1}, n = SubRender.perpendicular(u);
        assertEquals(0, n[0] * u[0] + n[1] * u[1] + n[2] * u[2], 1e-12);
        assertEquals(1, SubRender.norm(n), 1e-12);
        assertArrayEquals(new double[]{.5, 1, 1.5}, SubRender.lerp(new double[]{0, 0, 0}, new double[]{1, 2, 3}, .5), 1e-12);
    }

    @Test void energyBendsSpaceLikeAnEinsteinLens() throws Exception {
        // la loi : θ_E² = (4GE/c⁴)·D, amplifiée d'un seul facteur ; pour 1 GeV et D = 1 fm, 4GE/c⁴ = 5,3·10⁻³⁹ fm
        assertEquals(SubRender.THETA_1GEV_FM * SubRender.THETA_1GEV_FM / 5.295e-39, SubRender.LENS_GAIN, SubRender.LENS_GAIN * 1e-3);
        assertEquals(Math.sqrt(2), SubRender.einsteinRadius(2000) / SubRender.einsteinRadius(1000), 1e-12, "θ_E ∝ √E");
        assertEquals("3·10³⁸", CollisionScene.lensGain());
        FutureTask<Void> task = new FutureTask<>(() -> {
            javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(800, 600);
            SubRender render = new SubRender();
            assertFalse(render.warp, "la déformation de l'espace est coupée par défaut (à la demande)");
            render.warp = true;                                        // la loi reste disponible : on la vérifie
            render.begin(canvas.getGraphicsContext2D(), 800, 600, 0, 800, 600, new double[]{0, 0, 0}, 30, false);
            render.lens(new double[]{0, 0, 0}, 1000, 1);
            render.commitLens(canvas);
            assertInstanceOf(javafx.scene.effect.DisplacementMap.class, canvas.getEffect());
            double[] centre = render.project(new double[]{0, 0, 0});
            int i = (int) (centre[0] / SubRender.LENS_CELL) + 200 / SubRender.LENS_CELL, j = (int) (centre[1] / SubRender.LENS_CELL);
            double[] one = render.lensShift(i, j, 800, 600);
            assertTrue(one[0] < 0, "on lit plus près du centre : le fond est repoussé vers l'extérieur");
            render.begin(canvas.getGraphicsContext2D(), 800, 600, 0, 800, 600, new double[]{0, 0, 0}, 30, false);
            render.lens(new double[]{0, 0, 0}, 2000, 1);
            render.commitLens(canvas);
            double[] two = render.lensShift(i, j, 800, 600);
            assertEquals(2, two[0] / one[0], .15, "loin du cœur, la déviation va comme E");
            render.begin(canvas.getGraphicsContext2D(), 800, 600, 0, 800, 600, new double[]{0, 0, 0}, 30, false);
            render.commitLens(canvas);
            assertNull(canvas.getEffect(), "sans énergie, pas de lentille");
            return null;
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }

    @Test void photonColourFollowsItsEnergy() {
        javafx.scene.paint.Color soft = SubRender.photonColour(60), hard = SubRender.photonColour(9000);
        assertTrue(soft.getRed() > soft.getBlue(), "un photon mou est chaud (orangé)");
        assertTrue(hard.getBlue() > .9 && hard.getRed() > .85, "un photon dur est blanc bleuté");
    }

    @Test void whiteMixIsExactlyColourNeutrality() {
        // la synthèse additive (antirouge = cyan…) donne du blanc si et seulement si chaque couleur a le même compte net
        java.util.Random rng = new java.util.Random(7);
        for (int trial = 0; trial < 4000; trial++) {
            int n = 1 + rng.nextInt(5);
            java.util.List<HadronBench.Slot> slots = new java.util.ArrayList<>();
            int[] net = new int[3];
            for (int i = 0; i < n; i++) {
                boolean anti = rng.nextBoolean();
                int c = rng.nextInt(3);
                slots.add(new HadronBench.Slot("u", anti, c, i));
                net[c] += anti ? -1 : 1;
            }
            boolean neutral = net[0] == net[1] && net[1] == net[2];
            assertEquals(neutral, HadronBench.white(HadronBench.mix(slots)), slots.toString());
        }
        assertEquals("2/3 — pas un entier : il manque des quarks", HadronBench.baryonText(2 / 3.0));
        assertEquals("1 (baryon)", HadronBench.baryonText(1));
        assertEquals("3/2", HadronBench.spinText(1.5));
    }

    @Test void aRefusalFollowedByAVerdictDrawsWithoutError() throws Exception {
        // un électron repoussé, puis un nouveau verdict du moteur, puis des images : aucune opacité hors de [0, 1]
        FutureTask<Void> task = new FutureTask<>(() -> {
            HadronBench bench = new HadronBench(new SmTable(), new HadronBench.Actions() {
                @Override public void test(int spinIndex, double[] triples) {}
                @Override public void decay() {}
                @Override public void clear() {}
            });
            bench.getNode().resize(1200, 700);
            for (int k = 0; k < 30; k++) bench.tick(1 / 30.0);
            bench.drop("e", 600, 350);
            for (int k = 0; k < 30; k++) bench.tick(1 / 30.0);
            bench.load("uud");
            bench.markTested();
            bench.apply(com.studio.genesis.grpc.HadronLabState.newBuilder().setActive(true).setRun(7).setValid(true).build());
            for (int k = 0; k < 200; k++) bench.tick(1 / 30.0);
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    @Test void aStretchedStringBreaksIntoAPairAndTheHadronStaysWhite() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            HadronBench bench = new HadronBench(new SmTable(), new HadronBench.Actions() {
                @Override public void test(int spinIndex, double[] triples) {}
                @Override public void decay() {}
                @Override public void clear() {}
            });
            bench.getNode().resize(1200, 700);
            bench.load("uud");
            for (int k = 0; k < 10; k++) bench.tick(1 / 30.0);
            bench.stretch(0, new double[]{.2, 0, 0});                       // σ·ΔL bien en dessous de 672 MeV : rien ne casse
            assertEquals(0, bench.flightsCount());
            bench.stretch(0, new double[]{0, 2.5, 0});                      // ≈ 2 fm de plus : la corde casse
            assertEquals(1, bench.flightsCount(), "un méson s'en va");
            assertEquals(3, bench.slots().size(), "il reste un baryon");
            assertTrue(HadronBench.white(HadronBench.mix(bench.slots())), "le baryon qui reste est blanc");
            assertTrue("uds".contains(bench.slots().get(0).id()), "le quark arraché est remplacé par le quark de la paire");
            for (int k = 0; k < 120; k++) bench.tick(1 / 30.0);
            assertEquals("π⁺", HadronBench.mesonName("u", "d"));
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    @Test void neutralColouringFollowsTheEngineRule() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            HadronBench bench = new HadronBench(new SmTable(), new HadronBench.Actions() {
                @Override public void test(int spinIndex, double[] triples) {}
                @Override public void decay() {}
                @Override public void clear() {}
            });
            bench.load("uud");
            assertEquals(java.util.Set.of(0, 1, 2), java.util.Set.copyOf(bench.slots().stream().map(HadronBench.Slot::colour).toList()));
            bench.load("uD");
            assertEquals(bench.slots().get(0).colour(), bench.slots().get(1).colour(), "méson : couleur + anticouleur");
            assertTrue(bench.slots().get(1).anti());
            bench.conjugate();
            assertTrue(bench.slots().get(0).anti());
            return null;
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }

    @Test void labRendersACollisionAndAVerdict() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            double[] sent = new double[1];
            QuantumLab lab = new QuantumLab(new QuantumLab.Actions() {
                @Override public void back() {}
                @Override public void collide(int target, double energyMev, double angleDeg, int projectile) { sent[0] = energyMev; }
                @Override public void resample() {}
                @Override public void test(int spinIndex, double[] triples) {}
                @Override public void decay() {}
                @Override public void clear() {}
                @Override public void partons(boolean open) {}
                    @Override public void force(int channel) {}
            });
            new Scene(lab.getNode(), 1300, 760);
            lab.getNode().applyCss();
            lab.getNode().layout();
            NucleonQuark.Builder q = NucleonQuark.newBuilder().setFlavor("u").setCharge(2 / 3.0).setMassMev(336)
                    .addPositionFm(0).addPositionFm(0).addPositionFm(0).addVelocityC(0).addVelocityC(0).addVelocityC(0);
            NucleonSample sample = NucleonSample.newBuilder().setTimeFmc(-1)
                    .addQuarks(q.setColor(0)).addQuarks(q.setColor(1)).addQuarks(q.setFlavor("d").setColor(2))
                    .addClusters(NucleonCluster.newBuilder().setKind("baryon").addQuarks(0).addQuarks(1).addQuarks(2)
                            .setName("proton").setMassMev(938).addJunctionFm(0).addJunctionFm(0).addJunctionFm(0)).build();
            NucleonState nucleon = NucleonState.newBuilder().setActive(true).setRun(4).addSamples(sample)
                    .addSamples(sample.toBuilder().setTimeFmc(3)).build();
            CollisionState collision = CollisionState.newBuilder().setActive(true).setRun(1).setEnergyMev(1000).setAngleDeg(30)
                    .addChannels(CollisionChannel.newBuilder().setKey("elastic").setName("élastique").setProbability(.9))
                    .addChannels(CollisionChannel.newBuilder().setKey("delta").setName("Δ").setProbability(.1))
                    .addSpectrum(CollisionCurve.newBuilder().setName("delta").addWMev(1100).addWMev(1300).addXs(1).addXs(2))
                    .setWMev(938).setWavelengthFm(.4).setQ2Gev2(.23).setEOutMev(875)
                    .addPhotonFromFm(.5).addPhotonFromFm(0).addPhotonFromFm(0)
                    .addPhotonToFm(0).addPhotonToFm(0).addPhotonToFm(0)
                    .addOutDirection(.5).addOutDirection(0).addOutDirection(.87).setWMaxMev(1650)
                    .addSteps(CollisionStep.newBuilder().setTimeFmc(-1).setTitle("Le faisceau").setFormula("γ = E/m").setValues("E = 1000").setResult("…"))
                    .build();
            StandardModelState table = StandardModelState.newBuilder()
                    .addParticles(SmParticle.newBuilder().setId("u").setSymbol("u").setName("up").setFamily("quark").setCharge(2 / 3.0)
                            .setSpin(.5).setMassMev(2.16).addLines("charge : +2/3 e")).build();
            HadronLabState verdict = HadronLabState.newBuilder().setActive(true).setRun(1).setValid(true).setContent("udd")
                    .setFateKind("weak").setFateSummary("Le neutron se désintègre.").setLifetimeS(944).setName("n")
                    .addProducts(HadronProduct.newBuilder().setName("p").setMassMev(938).setContent("uud").addMomentumMev(1)
                            .addMomentumMev(0).addMomentumMev(0).setEnergyMev(938))
                    .addProducts(HadronProduct.newBuilder().setName("e-").setMassMev(.511).setFromW(true).addMomentumMev(-1)
                            .addMomentumMev(0).addMomentumMev(0).setEnergyMev(1.1)).build();
            lab.applyFrame(UniverseFrame.newBuilder().setNucleon(nucleon).setCollision(collision).setStandardModel(table)
                    .setHadronLab(verdict).build());
            for (int k = 0; k < 20; k++) lab.tick(1 / 30.0);
            lab.showBench();
            lab.bench().load("udd");
            lab.bench().markTested();
            for (int k = 0; k < 120; k++) lab.tick(1 / 30.0);
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }
}
