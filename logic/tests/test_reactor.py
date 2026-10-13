"""Le réacteur : confronté aux valeurs publiées et aux lois de conservation."""
import math
import unittest

import numpy as np
import scipy.constants as sc

from fusionlab import reactor


class Uniform:
    """Un champ uniforme, pour vérifier l'intégrateur contre la formule du rayon de Larmor."""

    def __init__(self, b):
        self.b = b

    def field_at(self, _x, _y, _z):
        return 0.0, 0.0, self.b


class CrossSectionTest(unittest.TestCase):
    def test_dt_peak(self):
        # Le maximum de la section efficace D-T : ≈ 5,0 b vers 64 keV (centre de masse).
        self.assertAlmostEqual(float(reactor.cross_section_b("T(d,n)4He", 64.0)), 5.0, delta=0.15)

    def test_dt_reactivity(self):
        # Bosch & Hale (1992), table VIII : ⟨σv⟩ = 1,136·10⁻¹⁶ cm³/s à 10 keV, 4,330·10⁻¹⁶ à 20 keV.
        fuel = reactor.FUELS[0]
        self.assertAlmostEqual(reactor.reactivity(fuel, 10.0) / 1.136e-22, 1.0, delta=0.02)
        self.assertAlmostEqual(reactor.reactivity(fuel, 20.0) / 4.330e-22, 1.0, delta=0.02)

    def test_dd_much_rarer(self):
        # À 10 keV, D-D est environ cent fois plus lent que D-T.
        ratio = reactor.reactivity(reactor.FUELS[0], 10.0) / reactor.reactivity(reactor.FUELS[1], 10.0)
        self.assertTrue(50 < ratio < 200, ratio)


class TokamakTest(unittest.TestCase):
    def setUp(self):
        self.tok = reactor.Tokamak()

    def test_safety_factor_like_iter(self):
        self.assertAlmostEqual(self.tok.safety_factor(0.05), 1.0, delta=0.03)
        self.assertTrue(2.7 < self.tok.safety_factor(0.95) < 3.2)

    def test_divergence_free(self):
        rng = np.random.default_rng(1)
        h = 1e-5
        for _ in range(20):
            p = self.tok.point(rng.random(), rng.uniform(0, 2 * math.pi), rng.uniform(0, 2 * math.pi))
            div = 0.0
            for k in range(3):
                e = np.zeros(3)
                e[k] = h
                div += (self.tok.field_at(*(p + e))[k] - self.tok.field_at(*(p - e))[k]) / (2 * h)
            self.assertLess(abs(div), 1e-6)

    def test_field_strength(self):
        b = np.linalg.norm(self.tok.field_at(self.tok.R0, 0.0, 0.0))
        self.assertAlmostEqual(b, 5.3, places=6)


class BorisTest(unittest.TestCase):
    def test_larmor_radius(self):
        m = reactor.nuclear_mass_mev(1, 2) / reactor.MEV_PER_U * reactor.U_KG
        qm = sc.e / m
        v = 1.0e6
        b = 5.3
        period = 2 * math.pi / (qm * b)
        (t, path), _, v_end = reactor.boris(Uniform(b), (0, 0, 0), (v, 0, 0), qm, period / 200, 200)
        diameter = np.max(path[:, 1]) - np.min(path[:, 1])
        self.assertAlmostEqual(diameter / 2 / (m * v / (sc.e * b)), 1.0, delta=0.005)
        self.assertAlmostEqual(np.linalg.norm(v_end) / v, 1.0, delta=1e-12)

    def test_time_reversal(self):
        # Le passé calculé à rebours se rejoue à l'endroit jusqu'au point de départ.
        tok = reactor.Tokamak()
        qm = sc.e / (reactor.nuclear_mass_mev(1, 3) / reactor.MEV_PER_U * reactor.U_KG)
        start = tok.point(0.4, 1.0, 0.3)
        v0 = np.array([4e5, -9e5, 3e5])
        dt = 1e-9
        (_, back), _, v_back = reactor.boris(tok, start, -v0, -qm, dt, 3000)
        (_, forth), _, _ = reactor.boris(tok, back[-1], -v_back, qm, dt, 3000)
        self.assertLess(np.linalg.norm(forth[-1] - start), 1e-6)


class ShotTest(unittest.TestCase):
    def test_dt_shot(self):
        r = reactor.Reactor(rng_seed=3)
        r.fire()
        s = r.shot
        # Les deux trajectoires finissent au même point.
        for reactant in s["reactants"]:
            self.assertLess(np.linalg.norm(reactant["path"][1][-1] - s["meet"]), 1e-9)
        # Énergie : celle du choc + Q, plus le mouvement du centre de masse.
        masses = [reactor.nuclear_mass_mev(p["z"], p["a"]) for p in s["products"]]
        kinetic = sum(p["kinetic_mev"] for p in s["products"])
        self.assertAlmostEqual(kinetic, s["q_mev"] + s["e_kev"] / 1000, delta=1e-6)
        # Quantité de mouvement : les produits emportent celle du centre de masse.
        total = sum(m / reactor.MEV_PER_U * reactor.U_KG * p["v"] for m, p in zip(masses, s["products"]))
        before = sum(reactor.nuclear_mass_mev(p["z"], p["a"]) / reactor.MEV_PER_U * reactor.U_KG * p["v"]
                     for p in s["reactants"])
        scale = masses[1] / reactor.MEV_PER_U * reactor.U_KG * 5e7
        self.assertLess(np.linalg.norm(total - before) / scale, 2e-2)
        # Q de D + T → He-4 + n : 17,59 MeV (masses mesurées).
        self.assertAlmostEqual(s["q_mev"], 17.59, delta=0.01)
        alpha, neutron = s["products"]
        self.assertTrue(alpha["confined"])
        self.assertTrue(0.1 < neutron["wall_m"] < 12)
        self.assertAlmostEqual(neutron["kinetic_mev"], 14.06, delta=0.4)
        # Spins parallèles pour la résonance 3/2⁺.
        a, b = (reactant["spin"] for reactant in s["reactants"])
        self.assertAlmostEqual(float(np.dot(a, b)), 1.0, places=9)

    def test_deuteron_separation(self):
        self.assertAlmostEqual(reactor.deuteron_separation_fm(), 1.55, delta=0.05)


if __name__ == "__main__":
    unittest.main()
