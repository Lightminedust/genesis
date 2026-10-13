"""L'échelle de la matière : Lennard-Jones, parois thermiques, états émergents."""

import unittest

from molecular import matter
from simulator.repository import ElementRepository
from simulator.simulation import Session


class LennardJonesParametersTest(unittest.TestCase):
    def test_argon_comes_from_the_tables(self):
        epsilon, sigma, source = matter.lj_parameters(18)
        self.assertAlmostEqual(epsilon, 123.6, delta=0.2)
        self.assertAlmostEqual(sigma, 3.41, delta=0.01)
        self.assertIn("chemicals", source)

    def test_inconsistent_table_value_is_rejected(self):
        # Magalhães donne 512 K pour le krypton, incompatible avec son ébullition à 120 K.
        epsilon, _, source = matter.lj_parameters(36)
        self.assertLess(epsilon, 250)
        self.assertIn("Poling", source)

    def test_metals_are_flagged_as_estimates(self):
        _, _, source = matter.lj_parameters(26)
        self.assertIn("estimation", source)


class EmergentStatesTest(unittest.TestCase):
    def run_at(self, sample, kelvin, ticks):
        sample.set_wall_temperature(kelvin)
        events = []
        for _ in range(ticks):
            events += sample.advance(0.05)
        return events

    def test_cold_argon_stays_solid(self):
        sample = matter.Matter(18, 39.948, rng_seed=2)
        self.run_at(sample, 30, 150)
        self.assertGreater(sample.fractions[0], 0.6)

    def test_heated_argon_melts_by_itself(self):
        sample = matter.Matter(18, 39.948, rng_seed=3)
        events = self.run_at(sample, 110, 300)
        self.assertGreater(sample.fractions[1] + sample.fractions[2], 0.6)
        self.assertTrue(any("fond" in event["title"] for event in events))

    def test_heat_enters_through_the_walls(self):
        cold = matter.Matter(18, 39.948, rng_seed=4)
        hot = matter.Matter(18, 39.948, rng_seed=4)
        self.run_at(cold, 30, 120)
        self.run_at(hot, 120, 120)
        self.assertGreater((hot.temperature() - cold.temperature()) * hot.epsilon_k, 30)
        self.assertGreater(hot.pressure_bar(), cold.pressure_bar())

    def test_above_the_critical_point_there_is_only_one_fluid(self):
        sample = matter.Matter(18, 39.948, rng_seed=6)
        self.run_at(sample, 300, 200)
        self.assertTrue(sample.supercritical())
        self.assertGreater(sample.temperature() * sample.epsilon_k, 1.085 * sample.epsilon_k)

    def test_piston_compresses_the_box(self):
        sample = matter.Matter(18, 39.948, rng_seed=5)
        sample.set_piston(0.3)
        self.assertTrue((sample.pos[:, 0] <= sample.box[0]).all())


class SessionMatterTest(unittest.TestCase):
    def test_session_switches_scale_and_frame_carries_atoms(self):
        import engine
        session = Session(ElementRepository(mongo_uri=None), seed=1)
        session.load_element(18)
        events = session.start_matter()
        self.assertIn("argon", events[0]["title"])
        session.advance(0.05)
        frame = engine.session_frame(1, session, session.evaluate(), "test", [], "", True)
        self.assertTrue(frame.matter.active)
        self.assertEqual(len(frame.matter.x), frame.matter.count)
        self.assertAlmostEqual(frame.matter.epsilon_k, 123.55, delta=0.1)
        session.stop_matter()
        self.assertIsNone(session.matter)


if __name__ == "__main__":
    unittest.main()
