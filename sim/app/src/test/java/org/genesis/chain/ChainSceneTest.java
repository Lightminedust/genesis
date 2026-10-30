package org.genesis.chain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChainSceneTest {
    @Test
    void oneDrawnNucleusStandsForAFixedNumberOfRealOnes() {
        ChainScene scene = new ChainScene();
        scene.setNuclei(1.29e26);                        // 50 kg d'uranium 235
        assertEquals(1290, scene.nucleusCount());
        assertEquals(1e23, scene.atomsPerPoint());
        scene.setNuclei(2e28);                           // une tonne de fer : on change d'échelle, pas de forme
        assertTrue(scene.nucleusCount() <= ChainScene.MAX_POINTS);
        assertEquals(1e26, scene.atomsPerPoint(), 1e10);
    }

    @Test
    void squeezingKeepsTheNucleiAndShrinksTheSphere() {
        ChainScene scene = new ChainScene();
        scene.setNuclei(5e25);
        int before = scene.nucleusCount();
        scene.previewScale = 0.8;                        // le geste : même masse, rayon × 0,8
        scene.setNuclei(5e25);
        assertEquals(before, scene.nucleusCount());
        assertEquals(scene.coreRadius * 0.8, scene.displayedRadius(), 1e-12);
    }

    @Test
    void aClickInsideTheSphereGivesAPointInsideIt() {
        ChainScene scene = new ChainScene();
        scene.coreRadius = 8;
        scene.camera.setup(1000, 800, 500, 400, scene.camera.distance());
        double[] point = scene.pointUnder(500, 400);
        assertNotNull(point);
        assertTrue(Math.sqrt(point[0] * point[0] + point[1] * point[1] + point[2] * point[2]) < 8);
        assertNull(scene.pointUnder(5, 5));
    }

    @Test
    void followingANeutronSelectsItsWholeLineage() {
        ChainScene scene = new ChainScene();
        int[] kinds = {0, 4};
        int[] groups = {5};
        scene.setTracks(List.of(
                new ChainScene.Track(0, -1, new float[]{0, 0, 0, 0, 1, 0, 0, 1}, kinds, groups, "fission", 2),
                new ChainScene.Track(1, 0, new float[]{1, 0, 0, 1, 2, 0, 0, 2}, kinds, groups, "fuite", 0),
                new ChainScene.Track(1, 0, new float[]{1, 0, 0, 1, 1, 1, 0, 2}, kinds, groups, "fission", 1),
                new ChainScene.Track(2, 2, new float[]{1, 1, 0, 2, 1, 1, 1, 3}, kinds, groups, "capture", 0)));
        scene.select(3);
        assertEquals(3, scene.selected);
        assertEquals(0, scene.generationAt(0.5));
        assertEquals(1, scene.generationAt(1.5));
        assertEquals(2, scene.generationAt(2.5));
    }
}
