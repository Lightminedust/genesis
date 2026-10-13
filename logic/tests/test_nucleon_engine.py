import unittest

import genesis_pb2 as pb
from engine import fill_nucleon
from simulator.repository import ElementRepository
from simulator.simulation import Session


class NucleonEngineTest(unittest.TestCase):
    def test_frame_carries_the_run_once_and_the_strike(self):
        session = Session(ElementRepository(mongo_uri=None), seed=1)
        session.start_nucleon()
        sent = {}
        first = pb.NucleonState()
        fill_nucleon(first, session.nucleon, session.nucleon_strike, sent)
        self.assertTrue(first.active)
        self.assertEqual(first.hadron, 'proton')
        self.assertGreater(len(first.samples), 100)
        self.assertEqual(len(first.samples[0].quarks), 3)
        self.assertAlmostEqual(sum(q.charge for q in first.samples[0].quarks), 1.0, places=9)
        again = pb.NucleonState()
        fill_nucleon(again, session.nucleon, session.nucleon_strike, sent)
        self.assertEqual(len(again.samples), 0)          # même tir : pas de renvoi
        self.assertEqual(again.run, first.run)

        session.strike_nucleon(1, 4500.0, [1.0, 0.0, 0.0])
        struck = pb.NucleonState()
        fill_nucleon(struck, session.nucleon, session.nucleon_strike, sent)
        self.assertNotEqual(struck.run, first.run)
        self.assertGreater(len(struck.samples), 100)
        self.assertEqual(struck.struck, 1)
        self.assertAlmostEqual(struck.momentum_mev, 4500.0)
        self.assertGreater(struck.q2_mev2, 0)
        self.assertLess(struck.energy_error_mev, 1.0)
        last = struck.samples[-1]
        self.assertGreater(len(last.clusters), 1)
        self.assertEqual(len(struck.breaks), len(struck.breaks))

    def test_refused_blow_keeps_previous_strike(self):
        session = Session(ElementRepository(mongo_uri=None), seed=1)
        session.start_nucleon()
        events = session.strike_nucleon(0, 99999.0, [1.0, 0.0, 0.0])
        self.assertEqual(session.nucleon_strike['momentum'], 0.0)
        self.assertTrue(events)

    def test_neutron_switch_and_leaving(self):
        session = Session(ElementRepository(mongo_uri=None), seed=1)
        session.start_nucleon()
        session.set_nucleon_hadron(1)
        self.assertEqual(session.nucleon.hadron, 'neutron')
        session.stop_nucleon()
        self.assertIsNone(session.nucleon)


if __name__ == '__main__':
    unittest.main()


class PartonEngineTest(unittest.TestCase):
    def test_partons_open_on_the_nucleon_and_ship_once(self):
        from engine import fill_partons
        session = Session(ElementRepository(mongo_uri=None), seed=1)
        self.assertEqual(session.start_partons(), [])          # sans nucléon ouvert : rien
        self.assertIsNone(session.partons)
        session.start_nucleon()
        session.strike_nucleon(0, 4500.0, [1.0, 0.0, 0.0])
        session.start_partons()
        self.assertAlmostEqual(session.partons_view['q2'], session.nucleon.q['q2'] / 1e6, places=6)   # le Q² du coup
        sent = {}
        first = pb.PartonState()
        fill_partons(first, session.partons, session.partons_view, sent)
        self.assertTrue(first.active)
        self.assertEqual(len(first.curves), 4)
        self.assertGreater(len(first.partons), 3)
        self.assertAlmostEqual(first.momentum_total, 1.0, delta=0.006)
        self.assertGreater(first.alpha_s, 0)
        again = pb.PartonState()
        fill_partons(again, session.partons, session.partons_view, sent)
        self.assertEqual(len(again.partons), 0)
        session.set_partons(100.0, 1e-4)
        third = pb.PartonState()
        fill_partons(third, session.partons, session.partons_view, sent)
        self.assertGreater(len(third.partons), len(first.partons))
        self.assertEqual(third.q2_gev2, 100.0)
        for event in third.events:
            self.assertLess(abs(event.dx), 1e-9)
            self.assertLess(event.dcolour, 1e-9)

    def test_refused_resolution_and_leaving(self):
        session = Session(ElementRepository(mongo_uri=None), seed=1)
        session.start_nucleon()
        session.start_partons(10.0, 1e-3)
        before = session.partons_view['run']
        events = session.set_partons(1e7, 1e-3)
        self.assertTrue(events)
        self.assertEqual(session.partons.q2, 10.0)
        session.set_nucleon_hadron(1)
        self.assertEqual(session.partons.hadron, 'neutron')
        session.stop_partons()
        self.assertIsNone(session.partons)
        session.stop_nucleon()
        self.assertIsNone(session.nucleon)
