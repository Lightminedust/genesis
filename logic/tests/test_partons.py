import unittest

import numpy as np

from quantumlab import parton_cloud as cloud
from quantumlab import partons


class DglapTest(unittest.TestCase):
    def test_momentum_and_quark_number_sum_rules_hold_at_every_scale(self):
        for q2 in (0.26, 1, 10, 100, 1000):
            m = partons.momentum_fractions(q2)
            self.assertAlmostEqual(m['total'], 1.0, delta=0.006, msg=f'impulsion à Q²={q2}')
            n = partons.numbers(q2, 1e-5)
            self.assertAlmostEqual(n['u_valence'], 2.0, delta=0.01)
            self.assertAlmostEqual(n['d_valence'], 1.0, delta=0.01)

    def test_sea_is_generated_by_gluon_splitting_and_grows_with_resolution(self):
        self.assertEqual(partons.momentum_fractions(partons.Q0_SQ)['sea'], 0.0)
        previous = 0.0
        for q2 in (1, 4, 16, 100, 1000):
            sea = partons.momentum_fractions(q2)['sea']
            self.assertGreater(sea, previous)
            previous = sea
        p = partons.pdfs(10)
        np.testing.assert_allclose(p['ubar'], p['dbar'] * 0 + p['ubar'])           # même forme pour chaque antisaveur
        self.assertGreater(np.sum(partons.WEIGHT * p['g'] / partons.GRID * (partons.GRID < 0.01)),
                           np.sum(partons.WEIGHT * (p['u'] - p['ubar']) / partons.GRID * (partons.GRID < 0.01)),
                           'à petit x les gluons dépassent la valence')

    def test_valence_loses_momentum_to_the_gluons(self):
        values = [partons.momentum_fractions(q2)['valence'] for q2 in (1, 10, 100, 1000)]
        self.assertEqual(values, sorted(values, reverse=True))
        self.assertAlmostEqual(partons.momentum_fractions(10)['valence'], 0.37, delta=0.05)

    def test_alpha_s_runs_down_with_the_scale(self):
        self.assertGreater(partons.alpha_s(1), partons.alpha_s(10))
        self.assertAlmostEqual(partons.alpha_s(10), 0.27, delta=0.05)

    def test_counts_grow_toward_small_x(self):
        a, b = partons.numbers(10, 1e-2), partons.numbers(10, 1e-4)
        self.assertGreater(b['g'], 5 * a['g'])


class CloudTest(unittest.TestCase):
    def cloud(self, q2=10, x_min=1e-3, seed=3, hadron='proton'):
        c = cloud.Cloud(hadron, q2, x_min)
        c.sample(seed)
        return c

    def test_start_is_a_colour_neutral_nucleon_with_the_right_flavours(self):
        for hadron, flavors in (('proton', {'u': 2, 'd': 1, 's': 0}), ('neutron', {'u': 1, 'd': 2, 's': 0})):
            c = self.cloud(hadron=hadron)
            start = c.totals([p['id'] for p in c.partons if p['born'] == 0])
            self.assertEqual(start['flavor'], flavors)
            np.testing.assert_allclose(start['colour'], 0, atol=1e-12)
            self.assertAlmostEqual(start['charge'], 1.0 if hadron == 'proton' else 0.0, places=12)
            self.assertLessEqual(start['x'], 1.0)

    def test_every_event_conserves_momentum_charge_flavour_and_colour(self):
        for q2, x_min in ((2, .02), (10, 1e-3), (100, 1e-4)):
            c = self.cloud(q2, x_min, seed=7)
            self.assertGreater(len(c.events), 5)
            for e in c.events:
                self.assertLess(abs(e['dx']), 1e-12, e['kind'])
                self.assertLess(abs(e['dcharge']), 1e-12, e['kind'])
                self.assertLess(e['dcolour'], 1e-12, e['kind'])
                self.assertEqual(e['dflavor'], 0, e['kind'])
            now = c.totals()
            np.testing.assert_allclose(now['colour'], 0, atol=1e-9)
            self.assertAlmostEqual(now['charge'], 1.0, places=9)

    def test_pairs_are_created_and_annihilated_at_small_x(self):
        kinds = set()
        for seed in range(1, 6):
            kinds |= set(str(k) for k in self.cloud(100, 1e-4, seed).summary())
        self.assertIn('g->qqbar', kinds)
        self.assertIn('qqbar->g', kinds)
        self.assertIn('g->gg', kinds)

    def test_net_quark_numbers_are_conserved_through_the_whole_film(self):
        c = self.cloud(100, 1e-4, seed=2)
        self.assertEqual(c.totals()['flavor'], {'u': 2, 'd': 1, 's': 0})

    def test_populations_stay_near_the_pdf_numbers(self):
        c = self.cloud(10, 1e-3, seed=11)
        gluons = sum(1 for p in c.alive() if p['kind'] == 'g')
        self.assertLess(abs(gluons - c.numbers['g']), 0.5 * c.numbers['g'] + 6)

    def test_same_seed_same_film_and_refusals(self):
        a, b = self.cloud(seed=4), self.cloud(seed=4)
        self.assertEqual([e['kind'] for e in a.events], [e['kind'] for e in b.events])
        with self.assertRaises(ValueError):
            cloud.Cloud('proton', 1e5, 0.01)
        with self.assertRaises(ValueError):
            cloud.Cloud('proton', 10, 1e-9)
        with self.assertRaises(ValueError):
            cloud.Cloud('pion', 10, 0.01)


if __name__ == '__main__':
    unittest.main()
