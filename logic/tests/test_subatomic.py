import unittest

import numpy as np

import genesis_pb2 as pb
from engine import fill_subatomic
from quantumlab.subatomic import SubatomicExperiment, nuclear_pair


class SubatomicTest(unittest.TestCase):
    def test_yukawa_force_is_the_gradient_of_the_potential(self):
        for unlike in (True, False):
            for r in (.5, .9, 1.8, 3.0, 5.0):
                h = 1e-5
                _, force = nuclear_pair(r, unlike)
                gradient = -(nuclear_pair(r + h, unlike)[0] -
                             nuclear_pair(r - h, unlike)[0]) / (2 * h)
                self.assertAlmostEqual(force, gradient, delta=1e-5)

    def test_pn_verlet_is_bound_oscillatory_and_conserves_momentum(self):
        s = SubatomicExperiment()
        self.assertLess(s.samples[0]['total'], 0)
        separations = [np.linalg.norm(frame['positions'][0]-frame['positions'][1])
                       for frame in s.samples]
        self.assertLess(min(separations), 1.8)
        self.assertGreater(max(separations), 2.7)
        self.assertLess(s.max_energy_error, 1e-3)
        self.assertGreater(s.samples[0]['binding_margin'], 0)
        self.assertEqual(s.samples[0]['bound_count'], 2)
        self.assertEqual(len(s.samples[0]['pairs']), 1)
        for frame in s.samples[::40]:
            momentum = (s.mass[:, None] * frame['velocities']).sum(axis=0)
            np.testing.assert_allclose(momentum, 0, atol=1e-10)

    def test_identical_pairs_scatter_and_stronger_pn_impulse_unbinds(self):
        s = SubatomicExperiment()
        s.set_counts(protons=2, neutrons=0)
        self.assertGreater(s.samples[0]['total'], 0)
        self.assertLess(s.samples[0]['binding_margin'], 0)
        self.assertGreater(np.linalg.norm(s.samples[-1]['positions'][0] -
                                          s.samples[-1]['positions'][1]), 8)
        s.set_counts(protons=1, neutrons=1)
        s.set_impulse(15)
        self.assertGreater(s.samples[0]['total'], 0)
        self.assertGreater(np.linalg.norm(s.samples[-1]['positions'][0] -
                                          s.samples[-1]['positions'][1]), 20)

    def test_tritium_geometry_is_calculated_and_cluster_remains_bound(self):
        s = SubatomicExperiment()
        s.set_counts(protons=1, neutrons=2, electrons=1)
        self.assertGreater(min(sample['binding_margin'] for sample in s.samples), 0)
        self.assertTrue(all(sample['bound_count'] == 3 for sample in s.samples))
        self.assertLess(s.max_energy_error, 1e-3)
        self.assertAlmostEqual(s.electron_radius_pm, 52.9, delta=.1)
        self.assertGreater(np.linalg.norm(s.samples[0]['positions']-
                                          s.samples[100]['positions']), .02)

    def test_electron_is_orbital_density_and_run_is_sent_once(self):
        s = SubatomicExperiment()
        nuclear_run = s.run
        nuclear_samples = s.samples
        s.set_counts(electrons=1)
        self.assertEqual(s.run, nuclear_run)
        self.assertIs(s.samples, nuclear_samples)
        self.assertAlmostEqual(s.electron_radius_pm, 52.9177, delta=.02)
        self.assertAlmostEqual(s.electron_binding_ev, 13.6057, delta=.01)
        sent = {}
        state = pb.SubatomicState()
        fill_subatomic(state, s, sent)
        self.assertEqual(len(state.samples), s.FRAMES)
        self.assertEqual(len(state.radial_fm), len(state.potential_mev))
        self.assertGreater(len(state.radial_fm), 100)
        self.assertEqual([p.kind for p in state.samples[0].particles],
                         [pb.SubatomicParticle.PROTON, pb.SubatomicParticle.NEUTRON])
        self.assertEqual(len(state.samples[0].pairs), 1)
        self.assertGreater(state.samples[0].binding_margin_mev, 0)
        next_state = pb.SubatomicState()
        fill_subatomic(next_state, s, sent)
        self.assertEqual(len(next_state.samples), 0)
        s.set_counts(electrons=2)
        next_state = pb.SubatomicState()
        fill_subatomic(next_state, s, sent)
        self.assertEqual(len(next_state.samples), 0)
        self.assertEqual(next_state.electrons, 2)
        self.assertNotEqual(next_state.electron_radius_pm, state.electron_radius_pm)
        s.set_impulse(1)
        fill_subatomic(next_state, s, sent)
        self.assertEqual(len(next_state.samples), s.FRAMES)
        replacement = SubatomicExperiment()
        self.assertNotEqual(s.run, replacement.run)
        fill_subatomic(pb.SubatomicState(), replacement, sent)
        self.assertEqual(sent['subatomic_run'], replacement.run)

    def test_user_impulse_restarts_from_sample_and_recalculates_all_pairs(self):
        s = SubatomicExperiment()
        before = s.samples[84]
        prior_run = s.run
        s.perturb(84, 1, (0, .045, 0))
        self.assertGreater(s.run, prior_run)
        np.testing.assert_allclose(s.samples[0]['positions'], before['positions'])
        np.testing.assert_allclose(s.samples[0]['velocities'][1],
                                   before['velocities'][1] + [0, .045, 0])
        self.assertLess(s.max_energy_error, .005)
        self.assertEqual(len(s.samples[0]['pairs']), 1)
        with self.assertRaises(ValueError):
            s.perturb(0, 0, (.3, 0, 0))

    def test_stream_actions_enter_recalculate_and_leave_the_new_laboratory(self):
        from engine import UniverseEngineServicer
        from simulator.repository import ElementRepository
        from simulator.simulation import Session
        s = Session(ElementRepository(mongo_uri=None))
        action = lambda kind, value=0: pb.UserAction(type=kind, value=value)
        UniverseEngineServicer._apply(s, action(pb.UserAction.SUBATOMIC_START))
        self.assertIsNotNone(s.subatomic)
        first = s.subatomic.run
        UniverseEngineServicer._apply(s, action(pb.UserAction.SUBATOMIC_SET_PROTONS, 2))
        self.assertEqual(s.subatomic.protons, 2)
        self.assertGreater(s.subatomic.run, first)
        UniverseEngineServicer._apply(s, action(pb.UserAction.SUBATOMIC_SET_ELECTRONS, 1))
        self.assertEqual(s.subatomic.electrons, 1)
        run = s.subatomic.run
        kick = pb.UserAction(type=pb.UserAction.SUBATOMIC_KICK, value=0,
                             amount=20, vector=[0, .02, 0])
        UniverseEngineServicer._apply(s, kick)
        self.assertGreater(s.subatomic.run, run)
        UniverseEngineServicer._apply(s, action(pb.UserAction.SUBATOMIC_STOP))
        self.assertIsNone(s.subatomic)


if __name__ == '__main__':
    unittest.main()
