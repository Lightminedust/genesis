import unittest

import numpy as np

from quantumlab import nucleon as N


class NucleonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.proton = N.Nucleon('proton')

    def charge(self, quarks):
        return sum(N.charge_of(q['flavor'], q['anti']) for q in quarks)

    def test_fermat_point_is_the_steiner_tree(self):
        rng = np.random.default_rng(4)
        for _ in range(100):
            x = rng.normal(size=(3, 3)) * 0.6
            j, length, legs = N.fermat(x)
            if min(legs) < 1e-9:
                # angle d'au moins 120° : la jonction est un sommet, la corde est la somme des deux côtés
                k = int(np.argmin(legs))
                self.assertAlmostEqual(length, sum(np.linalg.norm(x[i] - x[k]) for i in range(3) if i != k), places=9)
                continue
            total = sum((x[i] - j) / legs[i] for i in range(3))     # trois jambes à 120° : ΣU = 0
            self.assertLess(np.linalg.norm(total), 1e-9)
            for _ in range(20):                                      # aucun autre point ne fait mieux
                trial = j + rng.normal(size=3) * 0.05
                self.assertGreaterEqual(sum(np.linalg.norm(x[i] - trial) for i in range(3)), length - 1e-12)

    def test_force_is_the_exact_gradient_of_the_potential(self):
        rng = np.random.default_rng(2)
        for _ in range(100):
            x = rng.normal(size=(3, 3)) * 0.6
            _, grad, _, _ = N.baryon_potential(x)
            for i in range(3):
                for c in range(3):
                    step = np.zeros((3, 3))
                    step[i, c] = 1e-6
                    numeric = (N.baryon_potential(x + step)[0] - N.baryon_potential(x - step)[0]) / 2e-6
                    self.assertAlmostEqual(numeric, grad[i, c], delta=max(2e-3, 2e-4 * abs(grad[i, c])))
        d = np.array([[0.0, 0, 0], [0.8, 0.1, 0]])
        _, g, _ = N.meson_potential(d)
        step = np.zeros((2, 3))
        step[1, 0] = 1e-6
        self.assertAlmostEqual((N.meson_potential(d + step)[0] - N.meson_potential(d - step)[0]) / 2e-6, g[1, 0], delta=1e-3)

    def test_ground_state_has_a_hadronic_size(self):
        # La taille variationnelle : un demi-femtomètre pour ce modèle (le rayon de charge mesuré est 0,84 fm).
        self.assertTrue(0.35 < self.proton.b < 0.7)
        self.assertGreater(self.proton.e_ground, 3 * N.MASS['u'])

    def test_an_untouched_nucleon_stays_whole_and_conserves_energy(self):
        self.proton.calculate(struck=0, momentum_mev=0.0, seed=5)
        self.assertEqual(len(self.proton.breaks), 0)
        self.assertLess(self.proton.max_energy_error, 0.5)
        last = self.proton.samples[-1]
        self.assertEqual([c['name'] for c in last['clusters']], ['proton'])
        self.assertAlmostEqual(self.charge(last['quarks']), 1)
        neutron = N.Nucleon('neutron')
        neutron.calculate(seed=5)
        self.assertEqual(neutron.samples[-1]['clusters'][0]['name'], 'neutron')
        self.assertAlmostEqual(self.charge(neutron.samples[-1]['quarks']), 0)

    def test_a_gentle_blow_below_the_pion_threshold_never_breaks_the_string(self):
        # 300 MeV/c : la corde ne s'étire pas au-delà de ~0,5 fm, aucune paire ne peut être payée, quelle que soit la graine
        for seed in range(4):
            self.proton.calculate(struck=seed % 3, momentum_mev=300, direction=(1, 0, 0), seed=seed)
            self.assertEqual(len(self.proton.breaks), 0)

    def test_a_hard_blow_breaks_the_string_and_conserves_everything(self):
        broke = False
        for seed in range(1, 6):
            self.proton.calculate(struck=0, momentum_mev=4500, direction=(1, 0.3, 0.1), seed=seed)
            self.assertLess(self.proton.max_energy_error, 1.0)
            last = self.proton.samples[-1]
            quarks, clusters = last['quarks'], last['clusters']
            self.assertAlmostEqual(self.charge(quarks), 1)
            self.assertEqual(sum(-1 if q['anti'] else 1 for q in quarks), 3, 'nombre baryonique conservé')
            for c in clusters:
                members = [quarks[i] for i in c['ids']]
                if c['kind'] == 'baryon':
                    self.assertEqual(sorted(m['color'] for m in members), [0, 1, 2], 'un baryon est incolore : R + V + B')
                    self.assertFalse(any(m['anti'] for m in members))
                else:
                    quark = [m for m in members if not m['anti']]
                    anti = [m for m in members if m['anti']]
                    self.assertEqual((len(quark), len(anti)), (1, 1))
                    self.assertEqual(quark[0]['color'], anti[0]['color'], 'un méson est incolore : c + anti-c')
            if self.proton.breaks:
                broke = True
                self.assertGreaterEqual(len(clusters), 2)
                self.assertTrue(any(c['kind'] == 'meson' for c in clusters))
                self.assertTrue(any(c['kind'] == 'baryon' for c in clusters))
                event = self.proton.breaks[0]
                self.assertTrue(event['separation'] > 0.1 and event['released'] > 0)
        self.assertTrue(broke, 'à 4,5 GeV/c, au moins une des cinq graines doit rompre')

    def test_the_same_seed_gives_the_same_collision(self):
        def run():
            self.proton.calculate(struck=1, momentum_mev=3000, direction=(0, 1, 0), seed=11)
            return ([(round(s['time'], 9), round(s['total'], 6)) for s in self.proton.samples[::20]],
                    [b['hadrons'] for b in self.proton.breaks])
        self.assertEqual(run(), run())

    def test_strange_quarks_are_suppressed_by_their_mass(self):
        # exp(−π m²/σ) : l'étrange (500 MeV) est ~10 fois moins probable que u ou d (336 MeV)
        self.assertTrue(0.05 < N.FLAVOR_WEIGHT['s'] / N.FLAVOR_WEIGHT['u'] < 0.15)
        self.assertAlmostEqual(N.FLAVOR_WEIGHT['u'], N.FLAVOR_WEIGHT['d'])

    def test_hadrons_are_named_by_their_quark_content(self):
        self.assertEqual(N.baryon_name(['u', 'u', 'd'])[0], 'proton')
        self.assertEqual(N.baryon_name(['d', 'u', 'd'])[0], 'neutron')
        self.assertEqual(N.baryon_name(['u', 'd', 's'])[0], 'Λ')
        self.assertEqual(N.meson_name('u', 'd')[0], 'π⁺')
        self.assertEqual(N.meson_name('d', 'u')[0], 'π⁻')
        self.assertEqual(N.meson_name('u', 's')[0], 'K⁺')

    def test_refuses_what_it_cannot_calculate(self):
        with self.assertRaises(ValueError):
            self.proton.calculate(struck=3)
        with self.assertRaises(ValueError):
            self.proton.calculate(momentum_mev=-1)
        with self.assertRaises(ValueError):
            self.proton.calculate(momentum_mev=N.MAX_KICK_MEV + 1)
        with self.assertRaises(ValueError):
            N.Nucleon('electron')


if __name__ == '__main__':
    unittest.main()
