"""La réaction en chaîne : le transport confronté aux références analytiques et aux sphères critiques mesurées."""

import math
import time
import unittest

import numpy as np

import engine
from chainlab import cascade, kinetics, library, materials, sandbox, transport
from chainlab.lab import ChainLab
from simulator.repository import ElementRepository
from simulator.simulation import Session


def one_group(total, scatter, nu_fission, fission):
    return {"total": np.array([total]), "scatter": np.array([[scatter]]), "nu_fission": np.array([nu_fission]),
            "fission": np.array([fission]), "absorption": np.array([total - scatter]),
            "capture": np.array([total - scatter - fission]), "chi": np.array([[1.0]]),
            "inverse_speed": np.array([1e-9])}


def k_of(material, radius, reflector=None, thickness=0.0, compression=1.0):
    composition = material.number_densities(compression)
    mats = [library.macroscopic(composition, chord_cm=4 * radius / 3)]
    if reflector is not None:
        mats.append(library.macroscopic(reflector.number_densities(), None))
    radii, region = transport.shells(radius, thickness, core_shells=16, reflector_shells=14)
    return transport.solve(radii, region, mats)


def wait(lab, limit=120):
    start = time.time()
    time.sleep(0.05)
    while lab.computing and time.time() - start < limit:
        time.sleep(0.02)


class TransportTest(unittest.TestCase):
    def test_probabilities_conserve_neutrons_and_reciprocity(self):
        radii, _ = transport.shells(5.0, 3.0)
        sigma = np.vstack([np.where(np.arange(len(radii)) < 10, 0.4, 0.15), np.full(len(radii), 2.0)])
        p, escape, volume = transport.collision_probabilities(radii, sigma)
        self.assertLess(np.abs(p.sum(axis=2) + escape - 1).max(), 1e-12)
        exact = 4 / 3 * math.pi * (radii ** 3 - np.concatenate(([0], radii[:-1])) ** 3)
        self.assertLess(np.abs(volume / exact - 1).max(), 1e-6)
        for g in range(2):
            flow = p[g] * (sigma[g] * volume)[:, None]
            self.assertLess(np.abs(flow - flow.T).max() / flow.max(), 1e-9)   # Σ_i V_i P_ij = Σ_j V_j P_ji

    def test_analytical_critical_sphere(self):
        # LA-13511, cas Ua-1-0-SP : un groupe, diffusion isotrope, r_c = 7,428998 cm.
        m = one_group(0.32640, 0.248064, 2.70 * 0.065280, 0.065280)
        radii, region = transport.shells(7.428998, core_shells=60)
        self.assertAlmostEqual(transport.solve(radii, region, [m]).k, 1.0, delta=5e-4)

    def test_godiva_and_jezebel_are_critical(self):
        cores = {c.key: c for c in materials.cores()}
        godiva = k_of(cores["HEU"], 8.7407)
        jezebel = k_of(cores["WGPU"], 6.3849)
        self.assertAlmostEqual(godiva.k, 1.0, delta=0.01)
        self.assertAlmostEqual(jezebel.k, 1.0, delta=0.01)
        # Rossi-α mesuré à la criticité : Λ ≈ 6 ns pour Godiva, 3 ns pour Jezebel.
        self.assertTrue(4e-9 < godiva.generation_time < 8e-9, godiva.generation_time)
        self.assertTrue(2e-9 < jezebel.generation_time < 5e-9, jezebel.generation_time)

    def test_flattop_reflector(self):
        heu = materials.Material("x", "", "", materials._weights_to_atoms({"U234": 1.0, "U235": 93.24, "U238": 5.76}),
                                 18.62)
        natural = next(r for r in materials.reflectors() if r.key == "UNAT")
        natural.density = 19.0
        s = k_of(heu, 6.1156, natural, 24.1242 - 6.1156)
        self.assertAlmostEqual(s.k, 1.0, delta=0.015)
        self.assertTrue(10e-9 < s.generation_time < 25e-9, s.generation_time)   # mesuré ≈ 17 ns


class CriticalMassTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lab = ChainLab(1)
        wait(cls.lab)

    @classmethod
    def tearDownClass(cls):
        cls.lab.close()

    def critical(self, key, compression=1.0, reflector=None, thickness=0.0):
        core = next(c for c in self.lab.cores if c.key == key)
        crit = self.lab._critical(core, compression, reflector, thickness)
        r = crit["critical_radius"]
        return crit["k_infinity"], (core.density * compression * 4 / 3 * math.pi * r ** 3 / 1000 if r else None)

    def test_published_bare_critical_masses(self):
        for key, low, high in (("U235", 42, 52), ("Pu239", 9, 11.5), ("U233", 14, 17)):
            _, mass = self.critical(key)
            self.assertTrue(low < mass < high, (key, mass))

    def test_fertile_and_depleted_never_diverge(self):
        for key in ("U238", "Th232", "UNAT"):
            k_inf, mass = self.critical(key)
            self.assertLess(k_inf, 1)
            self.assertIsNone(mass)

    def test_compression_divides_critical_mass_by_density_squared(self):
        _, nominal = self.critical("U235")
        _, squeezed = self.critical("U235", compression=2.0)
        self.assertAlmostEqual(squeezed / nominal, 0.25, delta=0.01)

    def test_reflector_lowers_critical_mass(self):
        _, bare = self.critical("U235")
        beryllium = self.lab.reflectors[0]
        _, reflected = self.critical("U235", reflector=beryllium, thickness=10.0)
        self.assertLess(reflected, 0.5 * bare)


class KineticsTest(unittest.TestCase):
    def test_inhour_regimes(self):
        lambdas = [0.0124, 0.0305, 0.111, 0.301, 1.14, 3.01]
        betas = np.array([0.033, 0.219, 0.196, 0.395, 0.115, 0.042]) * 0.0065
        slow = kinetics.inhour(0.002, 6e-9, betas, lambdas)
        prompt = kinetics.inhour(0.0165, 6e-9, betas, lambdas)
        self.assertTrue(1 < 1 / slow < 100, 1 / slow)              # quelques secondes : neutrons retardés
        self.assertAlmostEqual(prompt, (0.0165 - 0.0065) / 6e-9, delta=0.02 * prompt)
        self.assertLess(kinetics.inhour(-0.01, 6e-9, betas, lambdas), 0)

    def test_terrell_multiplicity(self):
        for nu in (2.43, 2.88, 3.2):
            p = cascade.terrell(nu)
            self.assertAlmostEqual(p.sum(), 1.0, places=12)
            self.assertAlmostEqual(float(np.dot(np.arange(len(p)), p)), nu, places=8)
            self.assertGreater(p[2] + p[3], 0.5)


class LabTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lab = ChainLab(2)
        wait(cls.lab)

    @classmethod
    def tearDownClass(cls):
        cls.lab.close()

    def test_validation_recalculates_godiva(self):
        self.assertIn("Godiva", self.lab.validation)

    def test_subcritical_chain_dies(self):
        self.lab.set_core(0)
        self.lab.set_radius(4.0)        # k ≈ 0,5 : dix neutrons s'éteignent bien avant douze générations
        wait(self.lab)
        self.assertEqual(self.lab.result["regime"], "sous-critique")
        self.assertLess(self.lab.result["k"], 0.6)
        self.assertEqual(self.lab.result["divergence"], 0)
        self.lab.fire(count=10)
        wait(self.lab)
        self.assertEqual(self.lab.shot["counts"][-1], 0)
        self.assertLess(self.lab.excursion["energy"], 1e-6)

    def test_shot_starts_where_clicked(self):
        self.lab.set_core(0)
        self.lab.set_radius(7.0)
        wait(self.lab)
        self.lab.fire(position=[3.0, -1.0, 2.0], count=2)
        wait(self.lab)
        sources = [t for t in self.lab.shot["tracks"] if t["parent"] < 0]
        self.assertEqual(len(sources), 2)
        for track in sources:
            self.assertEqual(list(track["points"][0][:3]), [3.0, -1.0, 2.0])
            self.assertEqual(track["kinds"][0], cascade.BIRTH)
            self.assertEqual(len(track["kinds"]), len(track["points"]))

    def test_supercritical_sphere_disperses(self):
        self.lab.set_core(0)
        self.lab.set_radius(9.5)
        wait(self.lab)
        self.assertEqual(self.lab.result["regime"], "critique prompt")
        self.lab.fire()
        wait(self.lab)
        shot, excursion = self.lab.shot, self.lab.excursion
        self.assertEqual(self.lab.excursion_run, self.lab.run)
        self.assertTrue(excursion["dispersed"])
        self.assertGreater(excursion["energy"], 1e8)
        self.assertGreater(excursion["peak_temperature_k"], 1e4)
        for track in shot["tracks"]:
            start = np.array(track["points"][0][:3])
            if track["parent"] < 0:
                self.assertLess(np.linalg.norm(start), 9.5)
            if track["end"] == "fuite":
                self.assertGreater(np.linalg.norm(track["points"][-1][:3]), 9.5)
            times = [p[3] for p in track["points"]]
            self.assertEqual(times, sorted(times))


class InertTest(unittest.TestCase):
    """Le fer : aucune fission évaluée, la matière éteint toute chaîne, quelle que soit la masse."""

    def test_iron_never_multiplies(self):
        lab = ChainLab(6)
        try:
            wait(lab)
            lab.set_core([c.key for c in lab.cores].index("Fe56"))
            lab.set_radius(30.0)
            wait(lab)
            result = lab.result
            self.assertEqual(result["k"], 0)
            self.assertEqual(result["regime"], "inerte")
            self.assertAlmostEqual(result["leakage"] + result["capture"], 1, delta=0.02)
            lab.fire(count=5)
            wait(lab)
            ends = {t["end"] for t in lab.shot["tracks"]}
            self.assertNotIn("fission", ends)
            self.assertEqual(lab.shot["counts"][1], 0)
            self.assertEqual(lab.excursion["time_s"], [])
        finally:
            lab.close()

    def test_divergence_probability(self):
        self.assertEqual(cascade.divergence_probability(0.95, 2.6), 0)
        low, high = cascade.divergence_probability(1.02, 2.6), cascade.divergence_probability(1.5, 2.6)
        self.assertTrue(0.005 < low < 0.05, low)       # ≈ 2(k − 1)/(ν̄ · ⟨ν(ν−1)⟩/ν̄²)
        self.assertGreater(high, 5 * low)


class SandboxTest(unittest.TestCase):
    def setUp(self):
        self.lab = ChainLab(3)

    def tearDown(self):
        self.lab.close()

    def test_iron_is_the_wall(self):
        e = sandbox.explore(26, 56, self.lab._bare_critical)
        self.assertLess(e["fission"][0], 0)
        self.assertLess(e["fusion"][0], 0)
        self.assertFalse(e["chain"]["fission_data"])
        self.assertAlmostEqual(e["binding_per_nucleon"], 8.790, places=2)

    def test_light_nuclei_gain_by_fusion_heavy_by_fission(self):
        carbon = sandbox.explore(6, 12, self.lab._bare_critical)
        self.assertLess(carbon["fission"][0], 0)
        self.assertGreater(carbon["fusion"][0], 0)
        lead = sandbox.explore(82, 208, self.lab._bare_critical)
        self.assertGreater(lead["fission"][0], 0)
        uranium = sandbox.explore(92, 235, self.lab._bare_critical)
        self.assertGreater(uranium["chain"]["k_infinity"], 2)

    def test_binding_curve_peaks_near_iron(self):
        a, z, ba = max(sandbox.binding_curve(), key=lambda p: p[2])
        self.assertIn((z, a), [(28, 62), (26, 58), (26, 56)])

    def test_comparison_table(self):
        rows = {r["label"]: r for r in sandbox.comparison(self.lab._bare_critical)}
        self.assertTrue(rows["U-235"]["possible"])
        self.assertFalse(rows["Fe-56"]["possible"])
        self.assertAlmostEqual(rows["D + T"]["q_mev"], 17.59, delta=0.01)
        self.assertGreater(rows["D + T"]["j_per_kg"], rows["U-235"]["j_per_kg"])


class EngineTest(unittest.TestCase):
    def test_actions_and_frame(self):
        session = Session(ElementRepository(), seed=4)
        apply = engine.UniverseEngineServicer._apply
        apply(session, engine.Action(type=engine.Action.CHAIN_START))
        self.assertIsNotNone(session.chain)
        apply(session, engine.Action(type=engine.Action.CHAIN_SET_RADIUS, amount=8.0))
        wait(session.chain)
        frame = engine.genesis_pb2.UniverseFrame()
        from chainlab import frame as chain_frame
        sent = {}
        chain_frame.fill(frame.chain, session.chain, sent)
        self.assertTrue(frame.chain.active and frame.chain.ready)
        self.assertEqual(len(frame.chain.cores), len(session.chain.cores))
        self.assertGreater(frame.chain.k_eff, 0.9)
        second = engine.genesis_pb2.UniverseFrame()
        chain_frame.fill(second.chain, session.chain, sent)
        self.assertEqual(len(second.chain.cores), 0)          # le catalogue ne part qu'une fois
        apply(session, engine.Action(type=engine.Action.CHAIN_STOP))
        self.assertIsNone(session.chain)


if __name__ == "__main__":
    unittest.main()
