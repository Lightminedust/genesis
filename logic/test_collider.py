"""La chambre de collision : chaque règle confrontée à une valeur mesurée ou établie."""

import unittest

import collider


def shoot(a, b, kev, until=True, seed=1):
    collision = collider.Collision(rng_seed=seed)
    collision.a, collision.b, collision.energy_kev = a, b, kev
    events = collision.fire(until)
    return collision, collision.result, events


class CollisionTest(unittest.TestCase):
    def test_coulomb_barrier_and_turning_point(self):
        collision, _, _ = shoot((1, 2), (1, 3), 20)
        self.assertAlmostEqual(collision.barrier_mev(), 0.444, delta=0.005)
        self.assertAlmostEqual(collision.turning_point_fm(), 72.0, delta=0.5)   # 1,44 MeV·fm / 20 keV

    def test_tunneling_grows_with_energy_and_saturates_above_the_barrier(self):
        low = shoot((1, 2), (1, 3), 5)[0].tunneling()
        high = shoot((1, 2), (1, 3), 50)[0].tunneling()
        self.assertLess(low, high)
        self.assertEqual(shoot((1, 2), (1, 3), 1000)[0].tunneling(), 1.0)

    def test_deuterium_tritium_releases_17_6_mev(self):
        _, result, events = shoot((1, 2), (1, 3), 20)
        self.assertTrue(result["fused"])
        self.assertAlmostEqual(result["q"], 17.589, delta=0.005)
        kinetic = {(p["z"], p["a"]): p for p in result["products"]}
        self.assertAlmostEqual(kinetic[(0, 1)]["kinetic"], 14.06, delta=0.02)    # le neutron emporte 80 %
        self.assertAlmostEqual(kinetic[(0, 1)]["speed_c"], 0.171, delta=0.002)
        self.assertIn("He-5", events[0]["detail"])                               # noyau composé, mesuré

    def test_deuterium_deuterium_has_both_measured_branches(self):
        seen = set()
        for seed in range(12):
            _, result, _ = shoot((1, 2), (1, 2), 50, seed=seed)
            seen.add(tuple(sorted((p["z"], p["a"]) for p in result["products"])))
        self.assertEqual(seen, {((1, 1), (1, 3)), ((0, 1), (2, 3))})

    def test_proton_proton_needs_the_weak_force(self):
        _, result, _ = shoot((1, 1), (1, 1), 10)
        self.assertTrue(result["weak"])
        self.assertTrue(result["never"])

    def test_boron_proton_gives_three_alphas(self):
        _, result, events = shoot((1, 1), (5, 11), 600)
        self.assertTrue(result["fused"])
        self.assertEqual(result["chain"][0]["daughter"], (2, 4))     # Be-8 se brise : 3 α en tout
        self.assertIn("Be-8", events[0]["detail"])

    def test_helium_capture_on_carbon_makes_oxygen(self):
        _, result, _ = shoot((2, 4), (6, 12), 2000)
        self.assertTrue(result["fused"])
        self.assertEqual((result["products"][0]["z"], result["products"][0]["a"]), (8, 16))

    def test_single_shot_usually_rebounds_at_low_energy(self):
        outcomes = [shoot((1, 2), (1, 3), 10, until=False, seed=s)[1]["fused"] for s in range(20)]
        self.assertLess(sum(outcomes), 3)

    def test_endothermic_fusion_is_announced(self):
        _, result, events = shoot((2, 4), (2, 4), 500)
        self.assertLess(result["q"], 0)
        self.assertIn("CONSOMME", events[0]["formula"])


if __name__ == "__main__":
    unittest.main()
