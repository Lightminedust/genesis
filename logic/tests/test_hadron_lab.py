import math
import unittest

import numpy as np

from quantumlab import hadron_data as data
from quantumlab import hadron_lab as lab
from quantumlab import standard_model as sm


def slots(content, colours=None):
    colours = colours or lab.default_colours(content)
    return [(c.lower(), c.isupper(), k) for c, k in zip(content, colours)]


class FilterTest(unittest.TestCase):
    def test_proton_is_valid_and_charge_is_summed_in_thirds(self):
        r = lab.check(slots('uud', [0, 1, 2]))
        self.assertTrue(r['valid'])
        self.assertAlmostEqual(r['charge'], 1.0, places=12)
        self.assertAlmostEqual(r['baryon'], 1.0, places=12)
        self.assertAlmostEqual(lab.check(slots('uuu', [0, 1, 2]))['charge'], 2.0)
        self.assertAlmostEqual(lab.check(slots('uD', [0, 0]))['charge'], 1.0)

    def test_colour_neutrality_rgb_or_colour_anticolour(self):
        self.assertTrue(lab.check(slots('uud', [2, 0, 1]))['valid'])
        self.assertTrue(lab.check(slots('uD', [1, 1]))['valid'])
        self.assertFalse(lab.check(slots('uD', [1, 2]))['valid'], 'vert + antibleu')
        bad = lab.check(slots('uuu', [0, 0, 0]))
        self.assertFalse(bad['valid'])
        self.assertTrue(any('Pauli' in r for r in bad['reasons']))
        self.assertTrue(any('neutre' in r for r in bad['reasons']))

    def test_quark_number_modulo_three(self):
        self.assertFalse(lab.check(slots('ud', [0, 1]))['valid'])
        self.assertFalse(lab.check([('u', False, 0)])['valid'])
        self.assertTrue(lab.check(slots('uudcC', [0, 1, 2, 0, 0]))['valid'], 'pentaquark colorié correctement')

    def test_leptons_are_not_hadron_constituents(self):
        r = lab.check([('u', False, 0), ('e', False, 0)])
        self.assertFalse(r['valid'])

    def test_default_colouring_is_neutral(self):
        for content in ('uud', 'uD', 'uudcC', 'UUD', 'sS'):
            self.assertTrue(lab.check(slots(content))['valid'], content)


class MassTest(unittest.TestCase):
    def test_baryon_masses_within_seventy_mev_of_the_measured_ones(self):
        errors = []
        for h in data.HADRONS.values():
            if len(h['content']) != 3 or h['content'].lower() != h['content'] or 't' in h['content']:
                continue
            states = lab.spectrum(h['content'])
            near = [m for spin, m in states if abs(spin - h['J']) < .01]
            if near:
                errors.append(min(abs(m - h['mass']) for m in near))
        self.assertGreater(len(errors), 15)
        self.assertLess(max(errors), 70)
        self.assertLess(float(np.sqrt(np.mean(np.square(errors)))), 35)

    def test_nucleon_delta_and_lambda_sigma_ordering(self):
        uud = dict(lab.spectrum('uud'))
        self.assertAlmostEqual(uud[0.5], 967, delta=40)
        self.assertGreater(uud[1.5] - uud[0.5], 250, 'Δ − N ≈ 300 MeV')
        uds = lab.spectrum('uds')
        self.assertLess(uds[0][1], uds[1][1], 'Λ plus léger que Σ⁰')
        self.assertAlmostEqual(uds[0][1], 1115.7, delta=10)
        self.assertEqual({round(s[0], 1) for s in uds}, {.5, 1.5})

    def test_pauli_removes_the_spin_one_half_state_of_three_identical_quarks(self):
        sss = lab.spectrum('sss')
        self.assertEqual([s[0] for s in sss], [1.5], 'sss : seulement Ω⁻ (3/2)')

    def test_mesons_pseudoscalar_below_vector(self):
        for content in ('uD', 'uS', 'cU', 'cD'):
            states = lab.spectrum(content)
            self.assertLess(states[0][1], states[1][1])
            self.assertEqual([s[0] for s in states], [0, 1])


class ElectroweakTest(unittest.TestCase):
    def test_top_lives_less_than_it_takes_to_hadronise(self):
        t = sm.HBAR_MEV_S / sm.top_width()
        self.assertAlmostEqual(sm.top_width(), 1480, delta=60)
        self.assertLess(t, sm.hadronisation_time())
        r = lab.fate_of([('t', False, 0), ('t', True, 0)])
        self.assertEqual(r['fate']['kind'], 'top')

    def test_neutron_lifetime_from_fermi_theory(self):
        tau, f = lab.neutron_lifetime()
        self.assertAlmostEqual(f, 1.636, delta=.01)
        self.assertGreater(tau, 800)
        self.assertLess(tau, 1000)          # 878 s mesuré ; sans correction coulombienne : ≈ 944 s

    def test_pi0_and_muon_and_w(self):
        self.assertAlmostEqual(lab.pi0_width() * 1e6, 7.8, delta=.3)          # eV
        self.assertAlmostEqual(lab.lepton_lifetime('mu'), 2.197e-6, delta=.03e-6)
        self.assertAlmostEqual(lab.lepton_lifetime('tau'), 2.9e-13, delta=.7e-13)
        w = lab.w_boson_channels()
        self.assertAlmostEqual(w['width'], 2085, delta=100)
        self.assertAlmostEqual(sum(c['branching'] for c in w['channels']), 1.0, places=12)
        self.assertAlmostEqual(w['channels'][0]['branching'], .108, delta=.01)

    def test_heavy_quark_lifetimes_by_the_spectator_model(self):
        c, b = lab.heavy_quark_decay('c'), lab.heavy_quark_decay('b')
        self.assertTrue(3e-13 < c['lifetime'] < 3e-12, c['lifetime'])        # D⁰ 0,41 ps, D⁺ 1,03 ps
        self.assertTrue(5e-13 < b['lifetime'] < 8e-12, b['lifetime'])        # B 1,5 ps
        self.assertAlmostEqual(sum(x['branching'] for x in c['channels']), 1.0, places=12)
        cs = [x for x in c['channels'] if x['target'] == 's']
        self.assertGreater(sum(x['branching'] for x in cs), .9, 'c → s domine (|V_cs| ≈ 0,975)')
        self.assertGreater(lab.phase_space_factor(0.0), lab.phase_space_factor(.1))

    def test_hierarchy_of_lifetimes_across_the_generations(self):
        self.assertLess(sm.HBAR_MEV_S / sm.top_width(), lab.heavy_quark_decay('b')['lifetime'])
        self.assertLess(lab.heavy_quark_decay('b')['lifetime'], lab.neutron_lifetime()[0])


class DecayTableTest(unittest.TestCase):
    def test_every_tabulated_decay_conserves_and_is_open(self):
        for name, h in data.HADRONS.items():
            parent = lab.hadron_numbers(h['content'])
            for branching, products in h['decays']:
                ok, delta = lab.conserves(parent, products)
                self.assertTrue(ok, f'{name} → {products}')
                masses = sum(data.quantum_numbers(p)['mass'] for p in products)
                self.assertLess(masses, h['mass'] + 1e-9, f'{name} → {products} sous le seuil')
                kind = lab.interaction_of(parent, products)
                if h.get('width') and not h.get('tau') and h['name'] != 'η':
                    self.assertIn(kind, ('forte', 'électromagnétique'), f'{name} est large : désintégration forte ou EM')
                if any(delta):
                    self.assertEqual(kind, 'faible', f'{name} → {products} change la saveur')

    def test_strange_baryons_decay_weakly_and_delta_strongly(self):
        self.assertEqual(lab.interaction_of(lab.hadron_numbers('uds'), ['p', 'π-']), 'faible')
        self.assertEqual(lab.interaction_of(lab.hadron_numbers('uud'), ['p', 'π0']), 'forte')
        self.assertEqual(lab.interaction_of(lab.hadron_numbers('uds'), ['Λ', 'γ']), 'électromagnétique')

    def test_branching_ratios_do_not_exceed_one(self):
        for name, h in data.HADRONS.items():
            self.assertLessEqual(sum(b for b, _ in h['decays']), 1.0005, name)


class FateTest(unittest.TestCase):
    def fate(self, content, spin=None):
        return lab.fate_of(slots(content), spin)

    def test_the_proton_is_the_only_stable_baryon(self):
        self.assertEqual(self.fate('uud')['fate']['kind'], 'stable')
        self.assertEqual(self.fate('udd')['fate']['kind'], 'weak')
        for content, kind in (('uds', 'weak'), ('uuu', 'strong'), ('sss', 'weak'), ('udc', 'weak-heavy'), ('uD', 'weak')):
            self.assertEqual(self.fate(content)['fate']['kind'], kind, content)

    def test_delta_decays_strongly_with_its_measured_scale(self):
        f = self.fate('uuu')['fate']
        self.assertEqual(f['kind'], 'strong')
        self.assertAlmostEqual(f['width'], 117, delta=8, msg='calibrée sur Δ → N π')

    def test_exotic_five_quark_state_is_given_its_breakup_threshold(self):
        f = self.fate('uudcC')['fate']
        self.assertEqual(f['kind'], 'fall-apart')
        self.assertAlmostEqual(f['threshold'], 938.27 + 2983.9, delta=2)

    def test_refused_assembly_explains_itself(self):
        r = lab.fate_of(slots('uuu', [0, 0, 0]))
        self.assertEqual(r['fate']['kind'], 'refused')
        self.assertIn('Pauli', r['fate']['summary'])


class KinematicsTest(unittest.TestCase):
    def test_two_and_three_body_decays_conserve_energy_and_momentum(self):
        rng = np.random.default_rng(1)
        for mass, masses in ((1115.683, [938.272, 139.57]), (497.6, [139.57, 139.57]), (939.565, [938.272, .511, 0.0]),
                             (5279.3, [1869.66, .511, 0.0]), (493.677, [134.98, 105.66, 0.0])):
            for _ in range(20):
                momenta = lab.decay_kinematics(mass, masses, rng)
                np.testing.assert_allclose(sum(momenta), 0, atol=1e-8)
                energy = sum(math.sqrt(m ** 2 + p @ p) for m, p in zip(masses, momenta))
                self.assertAlmostEqual(energy, mass, delta=1e-6)

    def test_two_body_momentum_matches_the_delta_decay(self):
        self.assertAlmostEqual(lab.two_body_momentum(1232, 938.272, 139.57), 227, delta=2)


if __name__ == '__main__':
    unittest.main()
