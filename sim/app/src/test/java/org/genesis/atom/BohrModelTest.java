package org.genesis.atom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** La vue de Bohr : les électrons par couche suivent le moteur, et aucun n'apparaît ni ne disparaît d'un coup. */
class BohrModelTest {
    @Test void loadingAnElementPlacesEveryElectronAtOnce() {
        BohrModel bohr = new BohrModel();
        bohr.sync(new int[]{2, 8, 1}, true);                       // sodium, chargé : pas d'animation
        assertArrayEquals(new int[]{2, 8, 1, 0, 0, 0, 0}, bohr.counts());
        assertEquals(0, bohr.movingCount());
    }

    @Test void anExcitedElectronJumpsInsteadOfBeingRecreated() {
        BohrModel bohr = new BohrModel();
        bohr.sync(new int[]{2, 8, 1}, true);
        bohr.sync(new int[]{2, 8, 0, 1}, false);                    // 3s → 4s : le même électron change d'anneau
        assertArrayEquals(new int[]{2, 8, 0, 1, 0, 0, 0}, bohr.counts());
        assertEquals(1, bohr.movingCount());
        for (int i = 0; i < 120; i++) bohr.tick(1 / 60.0);
        assertEquals(0, bohr.movingCount());
    }

    @Test void lostElectronsFlyAwayThenVanish() {
        BohrModel bohr = new BohrModel();
        bohr.sync(new int[]{2, 8, 18, 32, 21, 9, 2}, true);         // uranium
        bohr.sync(new int[]{2, 8, 18, 32, 21, 8, 2}, false);        // une désintégration : un électron de trop s'en va
        assertEquals(91, sum(bohr.counts()));
        assertEquals(1, bohr.movingCount());                        // il part, il n'a pas disparu
        for (int i = 0; i < 120; i++) bohr.tick(1 / 60.0);
        assertEquals(0, bohr.movingCount());
    }

    @Test void crowdedShellsGoByPairs() {
        // 12 au plus : places régulières ; au-delà, deux électrons par place (spins opposés).
        assertEquals(2 * Math.PI / 12, BohrModel.slotAngle(1, 12), 1e-12);
        double first = BohrModel.slotAngle(0, 32), partner = BohrModel.slotAngle(1, 32), next = BohrModel.slotAngle(2, 32);
        assertTrue(Math.abs(partner - first) < 0.2, "paire serrée");
        assertTrue(next - partner > 0.05, "paire suivante séparée");
    }

    @Test void valenceCoreIsTheNobleGasBelow() {
        assertEquals("[Rn]", BohrModel.nobleSymbol(BohrModel.nobleCore(92)));
        assertEquals(86, BohrModel.nobleElectrons(BohrModel.nobleCore(92)));
        assertEquals("[Kr]", BohrModel.nobleSymbol(BohrModel.nobleCore(39)));
        assertEquals("[Xe]", BohrModel.nobleSymbol(BohrModel.nobleCore(86)));  // le radon garde sa couche externe visible
        assertEquals("", BohrModel.nobleSymbol(BohrModel.nobleCore(2)));
    }

    @Test void heavyAtomsCondenseTheirCoreButKeepEveryValenceShell() {
        // Oganesson : [Rn] 86 + 5f¹⁴ 6d¹⁰ 7s² 7p⁶ → O, P et Q restent dessinées (pas seulement Q).
        BohrModel bohr = new BohrModel();
        bohr.sync(new int[]{2, 8, 18, 32, 32, 18, 8}, true);
        int core = bohr.core(false);                                 // automatique au-delà du xénon
        assertEquals("[Rn]", BohrModel.nobleSymbol(core));
        assertEquals(4, bohr.firstShell(core));                      // O
        BohrModel magnesium = new BohrModel();
        magnesium.sync(new int[]{2, 8, 2}, true);
        assertEquals(-1, magnesium.core(false));                     // léger : tout se voit
        assertEquals("[Ne]", BohrModel.nobleSymbol(magnesium.core(true)));
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int v : values) total += v;
        return total;
    }
}
