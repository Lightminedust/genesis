"""Le plasma et le critère de Lawson : confrontés aux valeurs publiées."""
import unittest

import numpy as np

from fusionlab import lawson
from fusionlab import reactor
from fusionlab.fusion_dynamics import FusionExperiment


class LawsonTest(unittest.TestCase):
    def test_dt_reactivity_matches_bosch_hale_table(self):
        self.assertAlmostEqual(lawson.reactivity((1, 2), (1, 3), 10.0) / 1.136e-22, 1.0, delta=0.02)

    def test_dt_ignition_triple_product(self):
        # Le minimum classique : nτT ≈ 3·10²¹ keV·s/m³ vers 14 keV.
        p = lawson.lawson((1, 2), (1, 3), 15.0, 1e20, 3.0)
        self.assertTrue(2.5e21 < p["best_triple"] < 3.5e21, p["best_triple"])
        self.assertTrue(11 < p["best_t"] < 18, p["best_t"])
        self.assertAlmostEqual(p["charged"], 3.52 / 17.59, delta=0.01)

    def test_harder_fuels_need_more(self):
        dt = lawson.lawson((1, 2), (1, 3), 15.0, 1e20, 3.0)["best_triple"]
        dhe3 = lawson.lawson((1, 2), (2, 3), 15.0, 1e20, 3.0)["best_triple"]
        dd = lawson.lawson((1, 2), (1, 2), 15.0, 1e20, 3.0)["best_triple"]
        self.assertGreater(dhe3, 10 * dt)
        self.assertGreater(dd, 10 * dhe3)

    def test_impossible_fuels_are_said_so(self):
        self.assertEqual(lawson.lawson((1, 1), (1, 1), 15.0, 1e20, 3.0)["best_triple"], 0)
        self.assertLess(lawson.lawson((26, 56), (82, 208), 15.0, 1e20, 3.0)["q"], 0)
        self.assertEqual(lawson.lawson((82, 208), (82, 208), 15.0, 1e20, 3.0)["fusion"], 0)

    def test_sub_barrier_reactivity_rises_with_temperature(self):
        values = [lawson.reactivity((6, 12), (6, 12), t) for t in (10, 100, 1000)]
        self.assertTrue(values[0] < values[1] < values[2])
        # C + C à 3 MeV : quelques 10⁻⁸ b mesurés ; le modèle doit rester dans le bon ordre de grandeur.
        ls, _ = lawson.log_sigma((6, 12), (6, 12))
        sigma = float(np.exp(np.interp(np.log(3000.0), np.log(lawson.GRID), ls)))
        self.assertTrue(1e-9 < sigma < 1e-6, sigma)

    def test_gamow_peak_sampling(self):
        rng = np.random.default_rng(3)
        energies = [lawson.sample_energy((1, 2), (1, 3), 15.0, rng, True)[0] for _ in range(400)]
        # Le pic de Gamow D-T à 15 keV est vers 40 à 50 keV (bien au-dessus de kT).
        self.assertTrue(30 < np.median(energies) < 70, np.median(energies))

    def test_thermal_forced_shot(self):
        r = FusionExperiment(5)
        r.set_thermal(True)
        r.set_temperature(15)
        r.fire(True)
        self.assertFalse(r.error)
        self.assertTrue(r.shot["fused"])
        self.assertGreater(r.shot["energy"], 15)
        self.assertLess(r.shot["rarity"], 0)


if __name__ == "__main__":
    unittest.main()
