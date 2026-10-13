package org.genesis.fusion;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.genesis.fusion.FusionField.*;

public class PlasmaInsetTest {
    @Test void debyeLengthFollowsTemperatureAndDensity() {
        // Plasma D–T à 15 keV, 10²⁰ ions/m³ : électrons et ions, λ_D = √(ε₀kT / e²(n_e + Σn_iZ_i²)) ≈ 64 µm.
        double debye = PlasmaInset.debyeLength(15, 1e20, 1, 1, false);
        assertEquals(6.44e-5, debye, .01 * 6.44e-5);
        assertEquals(debye / 2, PlasmaInset.debyeLength(15, 4e20, 1, 1, false), 1e-12, "quatre fois plus dense : deux fois plus court");
        assertEquals(debye * 2, PlasmaInset.debyeLength(60, 1e20, 1, 1, false), 1e-12, "quatre fois plus chaud : deux fois plus long");
        assertTrue(PlasmaInset.debyeLength(15, 1e20, 38, 15, false) < debye / 10, "des ions très chargés écrantent bien plus court");
    }

    @Test void plasmaFrequencyAndThermalSpeeds() {
        assertEquals(8.98e10, PlasmaInset.plasmaFrequency(1e20), .01 * 8.98e10, "f_p = 8,98 √n_e Hz");
        double electron = PlasmaInset.thermalSpeed(15, PlasmaInset.ME), deuteron = PlasmaInset.thermalSpeed(15, 2.014 * PlasmaInset.AMU);
        assertEquals(Math.sqrt(2.014 * PlasmaInset.AMU / PlasmaInset.ME), electron / deuteron, 2, "les électrons vont √(m_i/m_e) fois plus vite");
        assertTrue(PlasmaInset.thermalSpeed(5000, PlasmaInset.ME) < PlasmaInset.C, "jamais plus vite que la lumière");
    }

    @Test void particleColourFollowsItsOwnEnergyAndGridPitchFollowsTheBox() {
        // Du bleu profond (0,1 keV) au blanc (100 keV) : la luminosité croît avec l'énergie.
        double cold = brightness(PlasmaInset.energyColor(.15)), warm = brightness(PlasmaInset.energyColor(1.5));
        double hot = brightness(PlasmaInset.energyColor(150));
        assertTrue(cold < warm && warm < hot, "une particule plus énergique est plus claire");
        assertEquals(PlasmaInset.energyColor(1e-9), PlasmaInset.energyColor(1e-6), "bornée en bas");
        // Le pas de grille : 1, 2 ou 5 × une puissance de dix, jamais plus grand que la cible.
        assertEquals(2e-6, PlasmaInset.gridPitch(3.1e-6), 1e-18);
        assertEquals(1e-12, PlasmaInset.gridPitch(1.9e-12), 1e-24);
        assertEquals(5e-15, PlasmaInset.gridPitch(9e-15), 1e-27);
        assertTrue(PlasmaInset.gridPitch(7e-6) <= 7e-6);
    }

    private static double brightness(javafx.scene.paint.Color c) {
        return .3 * c.getRed() + .59 * c.getGreen() + .11 * c.getBlue();
    }

    @Test void stoppingPowerGrowsWithChargeAndFallsWithSpeed() {
        double debye = PlasmaInset.debyeLength(15, 1e20, 1, 1, false);
        double slowHeavy = PlasmaInset.stoppingPower(38, 1.8e5, 2.6e21, debye);
        double fastLight = PlasmaInset.stoppingPower(1, 1.3e7, 2.6e21, debye);
        assertTrue(slowHeavy > 1e3 * fastLight, "dE/dx ∝ Z²/v² : un fragment lourd et lent ionise bien davantage");
        // Quatre fois plus de charge : un peu moins de seize fois plus (lnΛ décroît quand Z monte, b_min ∝ Z) ;
        // deux fois plus vite : près de quatre fois moins (lnΛ croît un peu avec v).
        double byCharge = PlasmaInset.stoppingPower(4, 1e6, 1e20, debye) / PlasmaInset.stoppingPower(1, 1e6, 1e20, debye);
        assertTrue(byCharge > 12 && byCharge < 16, "entre 12 et 16, pas exactement Z² : " + byCharge);
        double bySpeed = PlasmaInset.stoppingPower(1, 1e6, 1e20, debye) / PlasmaInset.stoppingPower(1, 2e6, 1e20, debye);
        assertTrue(bySpeed > 3 && bySpeed < 4, "entre 3 et 4, pas exactement 1/v² : " + bySpeed);
        assertEquals(0, PlasmaInset.stoppingPower(0, 1e7, 1e20, debye), "un neutre n'ionise pas");
        assertEquals(0, PlasmaInset.stoppingPower(2, 1e7, 0, debye), "dans le vide, rien à ioniser");
    }

    @Test void screenedFieldReducesToCoulombInVacuumAndFallsFasterInAPlasma() {
        Body proton = new Body(1, 1, "p", ZERO, ZERO, ZERO, .84, 2.79);
        V point = new V(10, 0, 0);
        assertEquals(electric(point, List.of(proton)).x(), electric(point, List.of(proton), Double.POSITIVE_INFINITY).x(), 0);
        double ratio = electric(point, List.of(proton), 10).x() / electric(point, List.of(proton)).x();
        assertEquals(Math.exp(-1) * 2, ratio, 1e-9, "Debye–Hückel : E ∝ e^(−r/λ)(1 + r/λ)/r²");
    }
}
