"""Le cœur d'étoile, confronté à des valeurs réelles."""
import math
import unittest

import numpy as np

from fusionlab import star

try:
    import pynucastro  # noqa: F401
    HAVE_PYNUCASTRO = True
except ImportError:
    HAVE_PYNUCASTRO = False


def ready_star(preset):
    s = star.Star(rng_seed=3, preset=preset)
    s.net = star.network()
    s.advance(0.0, True)
    return s


@unittest.skipUnless(HAVE_PYNUCASTRO, "pynucastro absent")
class StarTest(unittest.TestCase):

    def test_rates_match_pynucastro(self):
        import pynucastro as pyna
        from pynucastro.screening import screen5
        s = ready_star(0)
        net = s.net
        s.coeffs = net.coefficients(s.rho, s.temperature, s.y)
        x = np.maximum(s.y * net.a, 1e-30)
        comp = pyna.Composition(net.nuclei)
        comp.set_array(x / x.sum())
        reference = net.collection.evaluate_ydots(pyna.ThermoState(rho=s.rho, T=s.temperature, comp=comp),
                                                  screen_func=screen5)
        mine = net.net.T @ s.rates_now()
        for name in ("p", "he4", "c12", "n14"):
            i = net.index[name]
            self.assertAlmostEqual(mine[i] / reference[net.nuclei[i]], 1.0, places=4)

    def test_solar_core_pressure_and_ionization(self):
        # Modèle solaire standard : P_c = 2,34 × 10¹⁶ Pa, plasma entièrement ionisé.
        s = ready_star(0)
        conditions = s.thermo()
        self.assertAlmostEqual(conditions["pressure"] / 2.34e16, 1.0, delta=0.1)
        self.assertGreater(conditions["ionized"], 0.99)
        # Les noyaux y sont plus serrés que la taille d'un atome d'hydrogène : ionisés par la pression.
        self.assertGreater(conditions["lowering_ev"], 13.6)

    def test_solar_core_power(self):
        # Une fois la chaîne p-p à l'équilibre : ~ 1,7 mW/kg (17 erg/g/s) au centre du Soleil.
        s = ready_star(0)
        for _ in range(20):
            s.advance(0.05, False)
        power = s.power_w_kg()
        self.assertGreater(power, 0.8e-3)
        self.assertLess(power, 3e-3)

    def test_pp_chain_energy_per_helium(self):
        # 4 p → He-4 : 26,73 MeV, dont ~2 % emportés par les neutrinos.
        s = ready_star(1)
        s.set_speed(len(star.SPEEDS) - 1)
        for _ in range(10):
            s.advance(0.1, False)
        created, _ = s.created_destroyed_per_kg()
        helium = created[s.net.index["he4"]] - s.created_destroyed_per_kg()[1][s.net.index["he4"]]   # net
        consumed_he3 = s.created_destroyed_per_kg()[1][s.net.index["he3"]]
        self.assertGreater(helium, 0)
        per_helium = s.energy_j_kg / helium / star.MEV_J
        self.assertGreater(per_helium, 24.0)
        self.assertLess(per_helium, 27.0)
        self.assertGreater(consumed_he3, 0)
        # La masse est conservée.
        self.assertAlmostEqual(float(s.mass_fractions().sum()), 1.0, places=4)

    def test_reactor_is_transparent(self):
        s = ready_star(5)
        conditions = s.thermo()
        self.assertEqual(conditions["p_rad"], 0.0)
        # 10²⁰ ions par m³ à 150 millions de K : quelques atmosphères.
        self.assertGreater(conditions["pressure"], 2e5)
        self.assertLess(conditions["pressure"], 8e5)
        for _ in range(10):
            s.advance(0.05, False)
        self.assertGreater(s.power_w_kg(), 1e11)

    def test_photosphere_keeps_its_atoms(self):
        s = ready_star(6)
        conditions = s.thermo()
        self.assertLess(conditions["ionized"], 0.01)
        self.assertAlmostEqual(math.log10(conditions["pressure"]), 4.0, delta=0.5)
        s.advance(0.1, False)
        self.assertEqual(s.power_w_kg(), 0.0)

    def test_window_reactions_conserve_nucleons(self):
        s = ready_star(2)
        net = s.net
        for _ in range(30):
            s.advance(0.05, False)
            for event in s.take_events():
                rate = net.rates[event["rate"]]
                self.assertEqual(sum(n.A for n in rate.reactants), sum(n.A for n in rate.products))
                self.assertEqual(len(event["created"]), len(rate.products))

    def test_degenerate_electrons_in_red_giant_core(self):
        s = ready_star(3)
        self.assertGreater(s.thermo()["eta"], 0.0)

    def test_beta_neutrino_estimate(self):
        # N-13 : spectre β⁺ de 1,199 MeV ; la valeur mesurée est 0,706 MeV.
        self.assertAlmostEqual(star.beta_mean_neutrino(1.1985), 0.706, delta=0.07)


if __name__ == "__main__":
    unittest.main()
