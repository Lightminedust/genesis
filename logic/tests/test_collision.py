import math
import unittest

import numpy as np

from quantumlab import collision_events
import genesis_pb2 as pb
from quantumlab import hadron_data as data
from quantumlab import hadron_lab as lab
from quantumlab import scattering as sc
from engine import fill_probe as fill_collision, fill_hadron, fill_nucleon, fill_standard_model
from simulator.repository import ElementRepository
from simulator.simulation import Session


class ScatteringTest(unittest.TestCase):
    def test_kelly_form_factors_and_the_falling_ratio(self):
        ge, gm = sc.form_factors(0.0)
        self.assertAlmostEqual(ge, 1.0, places=12)
        self.assertAlmostEqual(gm, sc.MU_P, places=12)
        ratios = [sc.MU_P * ge / gm for ge, gm in (sc.form_factors(q2 * 1e6) for q2 in (1, 2.5, 5.2, 8.5))]
        self.assertEqual(ratios, sorted(ratios, reverse=True), 'μ G_E/G_M décroît avec Q² (polarisation, JLab)')
        self.assertAlmostEqual(ratios[2], 0.38, delta=0.08)

    def test_delta_photon_width_and_peak(self):
        self.assertAlmostEqual(sc.delta_photon_width(), 0.65, delta=0.05)
        peak_mb = sc.delta_sigma_t(1232.0, 0.0) * sc.MEV2_TO_NB / 1e6
        self.assertGreater(peak_mb, 0.3)
        self.assertLess(peak_mb, 0.6)                 # mesuré ≈ 0,5 mb

    def test_elastic_kinematics(self):
        el = sc.elastic_cross_section(1000.0, math.radians(30))
        self.assertAlmostEqual(el['nu'], el['q2'] / (2 * sc.M_P), delta=1e-6)
        w = math.sqrt(sc.M_P ** 2 + 2 * sc.M_P * el['nu'] - el['q2'])
        self.assertAlmostEqual(w, sc.M_P, delta=1e-6)

    def test_channels_sum_to_one_and_move_with_q2(self):
        low, high = sc.Collision(1000, 30), sc.Collision(6000, 40)
        for c in (low, high):
            self.assertAlmostEqual(sum(ch['probability'] for ch in c.channels), 1.0, places=12)
        self.assertGreater(low.channels[0]['probability'], .8, 'à petit Q² le proton reste entier')
        self.assertGreater(high.channels[3]['probability'], .9, 'à grand Q² le photon casse le proton')

    def test_refusals(self):
        for args in ((50, 30), (20000, 30), (1000, 1), (1000, 179)):
            with self.assertRaises(ValueError):
                sc.Collision(*args)


class EventTest(unittest.TestCase):
    def test_every_channel_conserves_charge_and_baryon_number(self):
        seen = set()
        for energy, angle in ((1000, 30), (1500, 60), (5000, 20)):
            col = sc.Collision(energy, angle)
            for seed in range(1, 7):
                r = collision_events.build(col, seed)
                seen.add(r['event']['channel'])
                for s in r['nucleon'].samples[::20]:
                    q = sum(data.QUARK_CHARGE_THIRDS[k['flavor']] * (-1 if k['anti'] else 1) for k in s['quarks'])
                    b = sum(-1 if k['anti'] else 1 for k in s['quarks'])
                    self.assertEqual(q, 3)
                    self.assertEqual(b, 3)
                self.assertLess(r['nucleon'].samples[0]['time'], 0, 'le nucléon est montré avant le choc')
                self.assertTrue(r['steps'])
        self.assertTrue({'elastic', 'dis'} <= seen | {'dual'}, seen)

    def test_strike_reproduces_the_electron_invariant_mass(self):
        col = sc.Collision(5000, 20)
        for seed in range(1, 6):
            r = collision_events.build(col, seed)
            if r['event']['channel'] in ('dis', 'dual'):
                self.assertAlmostEqual(r['info']['w_model'], r['event']['w'], delta=2.0)
                return
        self.fail('aucun événement inélastique tiré')

    def test_delta_decay_products_have_the_two_body_momentum(self):
        col = sc.Collision(1000, 30)
        for seed in range(1, 40):
            r = collision_events.build(col, seed)
            if r['event']['channel'] == 'delta':
                info = r['info']
                w = r['event']['delta_mass']
                m_n, m_pi = data.HADRONS[info['nucleon']]['mass'], data.HADRONS[info['pion']]['mass']
                self.assertAlmostEqual(info['p_star'], lab.two_body_momentum(w, m_n, m_pi), places=9)
                names = sorted(c['name'] for c in r['nucleon'].samples[-1]['clusters'])
                self.assertEqual(names, sorted(collision_events.DISPLAY[n] for n in (info['nucleon'], info['pion'])))
                return
        self.fail('aucun Δ tiré')


class EngineTest(unittest.TestCase):
    def test_session_fills_collision_hadron_and_table(self):
        s = Session(ElementRepository(mongo_uri=None), seed=1)
        s.start_nucleon()
        s.run_collision(0, 2000.0, 40.0)
        sent = {}
        msg = pb.CollisionState()
        fill_collision(msg, s.collision, sent)
        self.assertTrue(msg.active)
        self.assertEqual(len(msg.channels), 4)
        self.assertGreater(len(msg.spectrum), 0)
        self.assertGreater(len(msg.steps), 3)
        again = pb.CollisionState()
        fill_collision(again, s.collision, sent)
        self.assertEqual(len(again.spectrum), 0, 'le spectre ne part qu\'une fois par tirage')
        nuc = pb.NucleonState()
        fill_nucleon(nuc, s.nucleon, s.nucleon_strike, {})
        self.assertGreater(len(nuc.samples), 100)
        table = pb.StandardModelState()
        fill_standard_model(table, {})
        self.assertEqual(len(table.particles), 17)
        top = next(p for p in table.particles if p.id == 't')
        self.assertTrue(any('τ' in line for line in top.lines))
        s.test_hadron(0, [0, 0, 0, 0, 0, 1, 1, 0, 2])        # u u d, rouge vert bleu
        h = pb.HadronLabState()
        fill_hadron(h, s.hadron_lab)
        self.assertTrue(h.valid)
        self.assertAlmostEqual(h.charge, 1.0)
        self.assertEqual(h.fate_kind, 'stable')
        s.test_hadron(0, [0, 0, 0, 0, 0, 0, 0, 0, 0])        # u u u, trois rouges
        bad = pb.HadronLabState()
        fill_hadron(bad, s.hadron_lab)
        self.assertFalse(bad.valid)
        self.assertTrue(any('Pauli' in r for r in bad.reasons))

    def test_a_case_chosen_on_the_spectrum_is_drawn_in_that_case_only(self):
        s = Session(ElementRepository(mongo_uri=None), seed=4)
        s.start_nucleon()
        s.run_collision(0, 1000.0, 30.0)
        for index in (0, 1, 2):
            self.assertEqual(s.force_collision(index), [])
            event = s.collision['result']['event']
            self.assertEqual(event['index'], index)
            self.assertTrue(event['forced'])
        refused = s.force_collision(3)            # inélastique profond impossible à 1 GeV, 30°
        self.assertTrue(refused)
        self.assertEqual(s.collision['result']['event']['index'], 2, 'le cas précédent reste affiché')

    def test_random_heavy_decays_conserve_everything(self):
        for content in ('cD', 'cU', 'uB', 'dB', 'udb', 'sB', 'cS', 'udc', 'UDB'):
            colours = lab.default_colours(content)
            slots = [(c.lower(), c.isupper(), k) for c, k in zip(content, colours)]
            parent = lab.hadron_numbers(content)
            for seed in range(1, 12):
                d = lab.full_analysis(slots, 0, seed)['decay']
                if not d:
                    continue
                q = sum(data.quantum_numbers(n)['charge'] for n, _, _ in d['products'])
                b = sum(data.quantum_numbers(n)['baryon'] for n, _, _ in d['products'])
                lep = [sum(data.quantum_numbers(n)['lepton'][i] for n, _, _ in d['products']) for i in range(3)]
                self.assertAlmostEqual(q, parent['charge'], places=9)
                self.assertAlmostEqual(b, parent['baryon'], places=9)
                self.assertEqual(lep, [0, 0, 0])
                np.testing.assert_allclose(sum(p for _, _, p in d['products']), 0, atol=1e-6)


if __name__ == '__main__':
    unittest.main()
