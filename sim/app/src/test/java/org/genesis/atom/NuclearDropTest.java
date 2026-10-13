package org.genesis.atom;

import com.studio.genesis.grpc.Nucleon;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** La goutte nucléaire : vibrations de Bohr–Mottelson, échanges de pions qui conservent la charge. */
public class NuclearDropTest {

    @Test void yukawaRangeAndLifetimeOfTheVirtualPion() {
        assertEquals(1.414, NuclearDrop.PION_RANGE_FM, 0.005);
        assertEquals(4.72e-24, NuclearDrop.PION_TIME_S, 0.02e-24);
    }

    @Test void leadVibratesAtAboutAnMeV() {
        NuclearDrop.Mode quadrupole = NuclearDrop.mode(2, 82, 208);
        assertTrue(quadrupole.stable());
        assertEquals(1.54, quadrupole.hbarOmegaMev(), 0.05);
        assertTrue(quadrupole.betaRms() > 0.1 && quadrupole.betaRms() < 0.3, "β = " + quadrupole.betaRms());
        assertEquals(2 * Math.PI * NuclearDrop.HBAR_MEV_S / quadrupole.hbarOmegaMev(), quadrupole.periodSeconds(), 1e-30);
        // L'octupôle est plus raide que le quadrupôle.
        assertTrue(NuclearDrop.mode(3, 82, 208).hbarOmegaMev() > quadrupole.hbarOmegaMev());
    }

    @Test void tooManyProtonsMakeTheDropFissile() {
        // La répulsion des protons l'emporte sur la tension de surface : raideur négative.
        assertFalse(NuclearDrop.mode(2, 130, 300).stable());
    }

    private static List<Nucleon> nucleus(int protons, int neutrons, long seed) {
        Random random = new Random(seed);
        List<Nucleon> list = new ArrayList<>();
        double radius = 1.2 * Math.cbrt(protons + neutrons);
        while (list.size() < protons + neutrons) {
            double x = (random.nextDouble() * 2 - 1) * radius;
            double y = (random.nextDouble() * 2 - 1) * radius;
            double z = (random.nextDouble() * 2 - 1) * radius;
            if (x * x + y * y + z * z <= radius * radius) {
                list.add(Nucleon.newBuilder().setX((float) x).setY((float) y).setZ((float) z)
                        .setKind(list.size() < protons ? Nucleon.Kind.PROTON : Nucleon.Kind.NEUTRON).build());
            }
        }
        return list;
    }

    @Test void pionsSwapIdentitiesButNeverTheCharge() {
        NuclearDrop drop = new NuclearDrop();
        drop.sync(nucleus(26, 30, 7), 1.2 * Math.cbrt(56));
        boolean[] start = new boolean[drop.count()];
        for (int i = 0; i < start.length; i++) {
            start[i] = drop.isProton(i);
        }
        for (int step = 0; step < 60 * 90; step++) {
            drop.tick(1 / 60.0);
            assertEquals(26, drop.protonsNow() + drop.pionCharge(), "la charge totale ne change pas");
        }
        assertTrue(drop.swaps() > 0 && drop.neutralExchanges() > 0);
        int changed = 0;
        for (int i = 0; i < start.length; i++) {
            changed += drop.isProton(i) != start[i] ? 1 : 0;
        }
        assertTrue(changed > 0, "des protons sont devenus neutrons, et inversement");
        // p–n : chargé deux fois sur trois ; p–p et n–n : toujours π⁰. Il y a donc des deux.
        assertTrue(drop.chargedExchanges() > 0);
    }

    @Test void theSurfaceVibratesAroundItsRadius() {
        NuclearDrop drop = new NuclearDrop();
        drop.sync(nucleus(82, 126, 3), 1.2 * Math.cbrt(208));
        double[] u = new double[3];
        double radius = drop.radiusFm();
        double spread = 0;
        for (int step = 0; step < 240; step++) {
            drop.tick(1 / 30.0);
            double mean = 0;
            double low = Double.MAX_VALUE;
            double high = 0;
            for (int i = 0; i < 2000; i++) {
                OrbitalCloud.fibonacci(i, 2000, u);
                double r = drop.surface(u[0], u[1], u[2]);
                mean += r / 2000;
                low = Math.min(low, r);
                high = Math.max(high, r);
            }
            // ∫ Y_λμ dΩ = 0 : en moyenne, la surface reste à R (au premier ordre).
            assertEquals(radius, mean, 0.002 * radius);
            spread = Math.max(spread, (high - low) / radius);
        }
        assertTrue(spread > 0.02 && spread < 0.4, "la goutte se déforme sans éclater : " + spread);
        assertTrue(drop.betaNow() <= NuclearDrop.SHOWN_BETA_MAX * 1.3);
    }
}
