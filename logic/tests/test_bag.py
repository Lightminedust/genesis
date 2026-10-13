import math
import unittest

import numpy as np

from quantumlab import bag
from quantumlab import collision_events
from quantumlab import scattering as sc
from quantumlab import standard_model as sm


class BagTest(unittest.TestCase):
    def test_charge_radius_from_the_slope_of_g_e(self):
        r = bag.charge_radius()
        self.assertAlmostEqual(r, 0.86, delta=0.02)          # mesuré : 0,84 fm (hydrogène muonique) à 0,88 fm (diffusion)
        h = 1e-4
        slope = (sc.form_factors(h * 1e6)[0] - sc.form_factors(0.0)[0]) / h       # dG_E/dQ² en GeV⁻²
        self.assertAlmostEqual(math.sqrt(-6 * slope) * sm.HBAR_C / 1000, r, delta=0.01)

    def test_mit_bag_constant_comes_out_where_the_mit_group_fitted_it(self):
        b = bag.bag()
        self.assertAlmostEqual(b['radius'], math.sqrt(5 / 3) * b['charge_radius'], places=12)
        self.assertGreater(b['b_quarter'], 140)
        self.assertLess(b['b_quarter'], 155)                  # MIT : 145 MeV
        self.assertAlmostEqual(b['tube_radius'], 0.46, places=12)
        self.assertAlmostEqual(bag.bag_radius(2, b['radius']), b['radius'] * (2 / 3) ** .25, places=12)

    def test_coulomb_well_of_an_extended_proton(self):
        a = bag.density_length()
        self.assertAlmostEqual(float(bag.potential(0.0, a)), -sm.ALPHA * sm.HBAR_C / (2 * a), places=9)
        far = float(bag.potential(50.0, a))
        self.assertAlmostEqual(far, -sm.ALPHA * sm.HBAR_C / 50.0, delta=1e-9)   # loin : point de charge
        for level in (-0.5, -1.0, -2.0):
            self.assertAlmostEqual(float(bag.potential(bag.radius_for(level, a), a)), level, delta=1e-6)

    def test_coulomb_alone_cannot_give_large_angles(self):
        a = bag.density_length()
        one, five = bag.classical_max_deflection(1000, a), bag.classical_max_deflection(5000, a)
        self.assertLess(math.degrees(one), 1.0, 'à 1 GeV, le champ du proton ne dévie pas de plus d’un degré')
        self.assertAlmostEqual(one / five, 5.0, delta=0.05, msg='la déviation classique va comme 1/E')
        path, angle = bag.coulomb_path(1000, .5, a)
        self.assertLess(path[-1][0], path[0][0], 'l’électron est attiré vers le proton')
        self.assertLess(angle, one)


class HadronMotionTest(unittest.TestCase):
    def test_hadrons_move_with_the_velocity_of_their_four_momentum(self):
        col = sc.Collision(5000, 20)
        r = collision_events.build(col, 9, 3)
        samples = [s for s in r['nucleon'].samples if s['time'] > 0]
        last, before = samples[-1], samples[-11]
        dt = last['time'] - before['time']
        for c in last['clusters']:
            ids = c['ids']
            match = [b for b in before['clusters'] if set(b['ids']) == set(ids)]
            if not match:
                continue
            moved = last['x'][ids].mean(axis=0) - before['x'][ids].mean(axis=0)
            velocity = np.asarray(c['p_vec']) / c['energy']
            self.assertLess(float(np.linalg.norm(moved / dt - velocity)), .08, c['name'])


if __name__ == '__main__':
    unittest.main()
