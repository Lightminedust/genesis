"""Le laboratoire de fusion, confronté à des valeurs réelles."""
import math
import unittest

import numpy as np

from fusionlab import fusion


def lab(a, b, kev, seed=1):
    l = fusion.FusionLab(rng_seed=seed)
    l.a, l.b = a, b
    l.set_energy(kev)
    return l


class FusionLabTest(unittest.TestCase):

    def test_catalog_has_every_known_isotope(self):
        catalog = fusion.catalog()
        self.assertEqual(len(catalog), 118)
        self.assertGreater(sum(len(e["isotopes"]) for e in catalog), 3000)
        gold = next(e for e in catalog if e["z"] == 79)
        self.assertEqual(gold["default_a"], 197)

    def test_barriers_of_known_systems(self):
        # Barrières de fusion mesurées : C+C ≈ 6 MeV, O+O ≈ 10 MeV.
        self.assertAlmostEqual(fusion.Pair((6, 12), (6, 12)).barrier, 6.0, delta=1.0)
        self.assertAlmostEqual(fusion.Pair((8, 16), (8, 16)).barrier, 10.5, delta=1.2)

    def test_tunneling_grows_with_energy(self):
        pair = fusion.Pair((1, 2), (1, 3))
        low, mid, high = pair.tunneling(0.005), pair.tunneling(0.05), pair.tunneling(1.0)
        self.assertLess(low, mid)
        self.assertLess(mid, high)
        self.assertGreater(high, 0.9)

    def test_dt_measured_channel(self):
        l = lab((1, 2), (1, 3), 20)
        l.fire(True)
        r = l.result
        self.assertTrue(r["fused"])
        self.assertAlmostEqual(r["q"], 17.589, places=2)
        neutron = next(p for p in r["products"] if p["kind"] == "neutron")
        self.assertAlmostEqual(neutron["kinetic"], 14.07, delta=0.05)

    def test_helium_on_carbon_makes_oxygen(self):
        # α + C-12 → O-16 + γ : c'est ainsi que les étoiles font l'oxygène.
        l = lab((2, 4), (6, 12), 3000)
        products, share, q, source, weak, kind = l.options()[0]
        self.assertEqual(kind, "capture")
        self.assertIn((8, 16), products)
        self.assertAlmostEqual(q, 7.162, places=2)

    def test_heavy_compound_evaporates_neutrons(self):
        # Li-6 + Au-197 → Pb-203* : un noyau lourd évapore surtout des neutrons (voies xn).
        l = lab((3, 6), (79, 197), 30000)
        products, share, *_ = l.options()[0]
        self.assertGreaterEqual(products.count((0, 1)), 2)
        self.assertLessEqual(products.count((2, 4)), 1)

    def test_cascade_conserves_energy(self):
        l = lab((26, 56), (26, 56), 100000, seed=3)
        l.fire(True)
        r = l.result
        kinetic = sum(p["kinetic"] for p in r["products"])
        available = r["q"] + r["shot_energy"]
        self.assertAlmostEqual(kinetic, available, delta=0.02 * available)

    def test_superheavy_needs_energy_then_fissions(self):
        cold = lab((20, 48), (97, 249), 20)
        self.assertEqual(cold.options(), [])
        self.assertGreater(cold.threshold_mev(), 100)
        hot = lab((20, 48), (97, 249), 215000)
        kinds = {option[5] for option in hot.options()}
        self.assertIn("fission", kinds)

    def test_rebound_follows_rutherford(self):
        # Choc frontal sous la barrière : demi-tour au point de rebroussement k·Z₁Z₂e²/E.
        l = lab((1, 2), (1, 3), 20)
        pair = l.pair()
        points, jump, closest, duration = pair.trajectory(0.02, 0.0, False)
        self.assertAlmostEqual(closest, pair.turning_point(0.02), delta=0.02 * closest)
        self.assertEqual(jump, -1)
        self.assertGreater(duration, 0)

    def test_hydrogen_cloud_has_the_right_size(self):
        # 1s : rayon moyen ⟨r⟩ = 1,5 a₀ = 79 pm.
        positions, signs, shells, labels, radius = fusion.electron_cloud(1)
        mean = float(np.mean(np.linalg.norm(positions, axis=1)))
        self.assertAlmostEqual(mean, 79.4, delta=6)
        self.assertEqual(labels, ["1s1"])

    def test_p_orbital_has_two_signs(self):
        # Une orbitale p a deux lobes de signes opposés : l'onde change de signe.
        positions, signs, shells, labels, radius = fusion.electron_cloud(6)
        p_signs = {s for s, shell in zip(signs, shells) if labels[shell].startswith("2p")}
        self.assertEqual(p_signs, {1, -1})

    def test_plasma_fusions_come_from_the_fast_tail(self):
        # Dans un plasma à kT = 20 keV, les chocs D + T qui fusionnent sont bien plus énergiques que kT :
        # c'est le pic de Gamow (~49 keV), et la chance moyenne dépasse celle d'un faisceau à 20 keV.
        l = lab((1, 2), (1, 3), 20, seed=5)
        energies = []
        for _ in range(40):
            l.fire(True)
            energies.append(l.result["shot_energy"] * 1000)
        self.assertGreater(float(np.median(energies)), 35)
        self.assertAlmostEqual(l.gamow_peak_mev() * 1000, 49, delta=2)
        beam = lab((1, 2), (1, 3), 20)
        beam.set_thermal(False)
        self.assertGreater(l.probability(), beam.probability())

    def test_beam_keeps_its_energy(self):
        l = lab((1, 2), (1, 3), 20)
        l.set_thermal(False)
        l.fire(False)
        self.assertAlmostEqual(l.result["shot_energy"], 0.020, places=9)

    def test_lorentz_and_spin_are_tiny_but_real(self):
        # q·v×B dans le champ du triton : réelle, mais elle ne dévie la trajectoire que d'une fraction de fm,
        # et le spin du deutéron n'a pas le temps de tourner (choc de ~10⁻²⁰ s).
        l = lab((1, 2), (1, 3), 20)
        l.set_thermal(False)
        l.fire(False)
        r = l.result
        self.assertGreater(r["lorentz_max"], 0)
        self.assertLess(r["deflection"], 0.1)
        self.assertLess(math.degrees(r["precession"]), 1)

    def test_interpretations_speak_plainly(self):
        l = lab((1, 2), (1, 3), 20)
        l.fire(True)
        quantities = [q for q, _, _ in l.interpretations()]
        self.assertIn("En Soleils", quantities)
        self.assertIn("1 gramme de ce mélange", quantities)
        self.assertTrue(any("fois l'énergie d'une réaction chimique" in m for _, _, m in l.interpretations()))


if __name__ == "__main__":
    unittest.main()
