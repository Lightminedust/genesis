"""Un neutron lent sur un noyau : sections efficaces mesurées, bilans conservés, Q des masses mesurées."""

import random
import unittest

import engine
from nuclear import neutrons
from simulator.repository import ElementRepository
from simulator.simulation import Session


class CrossSectionTest(unittest.TestCase):
    def test_free_proton_scatters_like_the_measurement(self):
        # Sears donne 82 b pour le proton lié ; seul, il diffuse 20,4 b (mesure du proton libre).
        self.assertAlmostEqual(neutrons.cross_sections(1, 1)["scattering"], 20.5, delta=0.2)

    def test_uranium_235_fissions_most_of_the_time(self):
        sigma = neutrons.cross_sections(92, 235)
        absorbed = sigma["capture"] + sigma["fission"]
        self.assertAlmostEqual(absorbed, 680.9, delta=0.1)                    # absorption de Sears
        self.assertAlmostEqual(sigma["fission"] / absorbed, 0.856, delta=0.01)  # σ_f/σ_a thermique
        self.assertEqual(neutrons.cross_sections(92, 238)["fission"], 0.0)    # U-238 : pas avec un neutron lent

    def test_unmeasured_nucleus_gives_nothing(self):
        self.assertIsNone(neutrons.cross_sections(43, 98))


class OutcomeTest(unittest.TestCase):
    def test_every_reaction_keeps_charge_and_nucleons(self):
        rng = random.Random(4)
        for z, a in ((92, 235), (94, 239), (5, 10), (3, 6), (2, 3), (7, 14), (79, 197)):
            for _ in range(60):
                result = neutrons.outcome(rng, z, a)
                residual, emitted, nu = result["residual"], result["emitted"] or (0, 0), result["nu"]
                self.assertEqual(residual[0] + emitted[0], z)
                self.assertEqual(residual[1] + emitted[1] + nu, a + 1 if result["channel"] != "scattering" else a)

    def test_fission_releases_about_two_hundred_mev(self):
        rng = random.Random(7)
        energies = [r["q_mev"] for r in (neutrons.outcome(rng, 92, 235) for _ in range(300))
                    if r["channel"] == "fission"]
        self.assertGreater(len(energies), 200)
        self.assertTrue(all(150 < q < 220 for q in energies), (min(energies), max(energies)))

    def test_known_q_values(self):
        # B-10(n,α)Li-7 : 2,79 MeV ; capture sur U-238 : S_n(U-239) = 4,81 MeV ; Li-6(n,t)α : 4,78 MeV.
        self.assertAlmostEqual(neutrons.q_value(5, 10, [(3, 7), (2, 4)]), 2.790, delta=0.002)
        self.assertAlmostEqual(neutrons.q_value(92, 238, [(92, 239)]), 4.806, delta=0.002)
        self.assertAlmostEqual(neutrons.q_value(3, 6, [(1, 3), (2, 4)]), 4.783, delta=0.002)


class SessionNeutronTest(unittest.TestCase):
    def setUp(self):
        self.repo = ElementRepository()

    def test_boron_10_becomes_lithium_7(self):
        session = Session(self.repo, seed=2)
        session.atom.protons, session.atom.neutrons, session.atom.electrons = 5, 5, 5
        events = session.fire_neutron()
        self.assertEqual([e["kind"] for e in events][:2], ["nucleon", "decay"])
        self.assertEqual(events[1]["decay_mode"], "n_alpha")
        self.assertEqual((session.atom.protons, session.atom.neutrons), (3, 4))
        self.assertGreater(events[1]["kinetic_ev"], 1.4e6)                    # α : 1,78 MeV (Q partagé avec Li-7)

    def test_uranium_238_captures(self):
        captured = 0
        for seed in range(30):
            session = Session(self.repo, seed=seed)
            session.load_element(92)
            events = session.fire_neutron()
            if events[1]["kind"] == "decay":
                self.assertEqual(events[1]["decay_mode"], "n_capture")
                self.assertEqual(session.nuclide_label(), "U-239")
                captured += 1
        self.assertGreater(captured, 0)

    def test_no_data_no_invented_outcome(self):
        session = Session(self.repo, seed=1)
        session.load_element(43)                                              # Tc-98
        events = session.fire_neutron()
        self.assertEqual([e["kind"] for e in events], ["info"])
        self.assertEqual(session.nuclide_label(), "Tc-98")

    def test_engine_action(self):
        session = Session(self.repo, seed=3)
        session.load_element(26)
        events = engine.UniverseEngineServicer._apply(session, engine.Action(type=engine.Action.FIRE_NEUTRON))
        self.assertEqual(events[0]["particle"], "neutron")


if __name__ == "__main__":
    unittest.main()
