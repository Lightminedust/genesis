import math
import unittest

import numpy as np

from quantumlab import bag
from quantumlab import collision_events
import genesis_pb2 as pb
from quantumlab import hadron_events
from quantumlab import hadronic
from quantumlab import nucleon as nuc
from quantumlab import scattering as sc
from engine import fill_probe
from simulator.repository import ElementRepository
from simulator.simulation import Session


def _charge(quarks):
    return sum(nuc.charge_of(q['flavor'], q['anti']) for q in quarks if q.get('cluster', 0) >= 0)


class ChargedLeptonTest(unittest.TestCase):
    def test_massive_formula_reduces_to_the_electron_one(self):
        for e, a in ((1000, 30), (5000, 20)):
            th = math.radians(a)
            old = sc.elastic_cross_section(e, th)['xs']
            self.assertAlmostEqual(sc.elastic_massive(e, th, 'proton', sc.M_E)['xs'] / old, 1.0, delta=1e-5)
            jac = e and (1232.0 / (sc.M_P + 2 * e * math.sin(th / 2) ** 2))
            self.assertAlmostEqual(sc.em_inelastic(e, th, 1232.0, 'proton', sc.M_E, 'delta')
                                   / (float(sc.delta_cross_section(e, th, 1232.0)) * jac), 1.0, delta=1e-4)

    def test_the_muon_joins_the_electron_at_high_energy(self):
        ratios = [sc.elastic_massive(e, math.radians(20), 'proton', sc.M_MU)['xs']
                  / sc.elastic_massive(e, math.radians(20), 'proton', sc.M_E)['xs'] for e in (500, 2000, 8000)]
        self.assertGreater(ratios[0] - 1, ratios[1] - 1)
        self.assertAlmostEqual(ratios[2], 1.0, delta=0.002, msg='la masse ne compte plus quand E ≫ m')

    def test_positron_has_the_same_probabilities_but_is_pushed_away(self):
        e, p = sc.Collision(2000, 40, 'proton', 'e-'), sc.Collision(2000, 40, 'proton', 'e+')
        for a, b in zip(e.channels, p.channels):
            self.assertAlmostEqual(a['probability'], b['probability'], places=12)
        a = bag.density_length()
        attracted, _ = bag.coulomb_path(1000, .5, a, charge=-1)
        repelled, _ = bag.coulomb_path(1000, .5, a, charge=1)
        self.assertLess(attracted[-1][0], .5)
        self.assertGreater(repelled[-1][0], .5)

    def test_every_lepton_conserves_charge_with_the_lepton_counted(self):
        for proj, tgt, e, ang in (('mu-', 'proton', 3000, 30), ('e+', 'neutron', 2000, 40), ('nu', 'proton', 3000, 20),
                                  ('nu', 'neutron', 1000, 30), ('nubar', 'proton', 1000, 30), ('nubar', 'neutron', 5000, 15)):
            col = sc.Collision(e, ang, tgt, proj)
            for force, ch in enumerate(col.channels):
                if ch['probability'] <= 0:
                    continue
                r = collision_events.build(col, 3, force)
                first, last = r['nucleon'].samples[0], r['nucleon'].samples[-1]
                self.assertAlmostEqual(col.beam['charge'] + _charge(first['quarks']),
                                       col.beam['out_charge'] + _charge(last['quarks']), places=9, msg=(proj, tgt, ch['key']))


class AngleTest(unittest.TestCase):
    def test_the_physics_chooses_small_angles_for_a_photon_and_wide_ones_for_a_w(self):
        theta, median, beyond = sc.sample_angle(2000, 'proton', 'e-', .5)
        self.assertAlmostEqual(theta, median, places=9)
        self.assertLess(median, 9, 'un photon (1/Q⁴) favorise énormément les petits angles')
        self.assertLess(beyond, .02)
        _, median_nu, beyond_nu = sc.sample_angle(3000, 'proton', 'nu', .5)
        self.assertGreater(median_nu, 2 * median, 'le W, lourd, ne privilégie pas les petits Q²')
        self.assertGreater(beyond_nu, .15)
        for u in (0.0, .3, .999):
            self.assertTrue(5 <= sc.sample_angle(2000, 'proton', 'e-', u)[0] <= 170)


class NeutrinoTest(unittest.TestCase):
    def _qe_total(self, e, anti):
        tgt, fin = sc.qe_targets(anti)
        lo = sc.kinematics(e, 0.0, sc.target_mass(fin), sc.target_mass(tgt), 0.0, sc.M_MU)
        hi = sc.kinematics(e, math.pi, sc.target_mass(fin), sc.target_mass(tgt), 0.0, sc.M_MU)
        qs = np.linspace(lo[2], hi[2], 500)
        return np.trapezoid([sc.llewellyn_smith(e, q, anti) for q in qs], qs) * sc.MEV2_TO_CM2

    def test_quasi_elastic_matches_the_measured_plateau(self):
        nu, nubar = self._qe_total(2000, False), self._qe_total(2000, True)
        self.assertGreater(nu, 0.8e-38)
        self.assertLess(nu, 1.2e-38)               # mesuré ≈ 0,9–1,0·10⁻³⁸ cm² par neutron
        self.assertLess(nubar, nu, 'l\'antineutrino interagit moins (signe du terme V–A)')

    def test_total_cross_section_grows_like_the_energy(self):
        nu = (sc.neutrino_total(10000, 'proton') + sc.neutrino_total(10000, 'neutron')) / 2 / 10 / 1e-38
        nubar = (sc.neutrino_total(10000, 'proton', True) + sc.neutrino_total(10000, 'neutron', True)) / 2 / 10 / 1e-38
        self.assertAlmostEqual(nu, 0.68, delta=0.1)      # PDG : σ/E ≈ 0,68·10⁻³⁸ cm²/GeV
        self.assertAlmostEqual(nubar, 0.33, delta=0.07)  # PDG : ≈ 0,33

    def test_charge_forbids_quasi_elastic_on_a_proton(self):
        col = sc.Collision(1000, 30, 'proton', 'nu')
        self.assertEqual(col.channels[0]['probability'], 0.0)
        self.assertLess(col.interaction_probability, 1e-11, 'il traverse presque toujours sans rien voir')

    def test_the_w_changes_the_flavour_of_the_quark_it_hits(self):
        col = sc.Collision(1000, 30, 'neutron', 'nu')
        r = collision_events.build(col, 2, 0)                    # quasi-élastique : n → p
        self.assertEqual(r['nucleon'].samples[-1]['clusters'][0]['name'], 'proton')
        flavors = sorted(q['flavor'] for q in r['nucleon'].samples[-1]['quarks'])
        self.assertEqual(flavors, ['d', 'u', 'u'])
        self.assertEqual(sorted(q['flavor'] for q in r['nucleon'].samples[0]['quarks']), ['d', 'd', 'u'], 'avant : udd')
        path = np.asarray(r['tracks']['path'])
        self.assertTrue(np.allclose(path[:, 0], path[0, 0]) and np.allclose(path[:, 1], path[0, 1]), 'rien ne dévie un neutrino')


class HadronTest(unittest.TestCase):
    def test_delta_formation_peak(self):
        peak = max((hadronic.HadronCollision(p, 'proton', 'pi+').sigma_total, p) for p in range(200, 500, 5))
        self.assertAlmostEqual(peak[0], 200, delta=20)          # mesuré ≈ 200 mb vers p_π ≈ 300 MeV/c
        self.assertAlmostEqual(peak[1], 300, delta=40)
        minus = hadronic.HadronCollision(peak[1], 'proton', 'pi-').sigma_delta
        self.assertAlmostEqual(minus / hadronic.HadronCollision(peak[1], 'proton', 'pi+').sigma_delta, 1 / 3, delta=0.01,
                               msg='Clebsch–Gordan : π⁻p n\'a qu\'un tiers d\'isospin 3/2')

    def test_proton_proton_at_ten_gev(self):
        c = hadronic.HadronCollision(10000, 'proton', 'p')
        self.assertAlmostEqual(c.sigma_total, 40, delta=4)      # mesuré ≈ 40 mb
        self.assertAlmostEqual(c.sigma_el, 10, delta=2.5)       # mesuré ≈ 10 mb
        self.assertAlmostEqual(sum(ch['probability'] for ch in c.channels), 1.0, places=12)

    def test_every_hadronic_case_conserves_everything(self):
        for beam, p in (('p', 4000), ('pi+', 300), ('pi-', 3000)):
            for tgt in ('proton', 'neutron'):
                col = hadronic.HadronCollision(p, tgt, beam)
                for force, ch in enumerate(col.channels):
                    if ch['probability'] <= 0:
                        continue
                    r = hadron_events.build(col, 5, force)
                    first, last = r['nucleon'].samples[0], r['nucleon'].samples[-1]
                    self.assertAlmostEqual(_charge(first['quarks']), _charge(last['quarks']), places=9)
                    # vu du centre de masse, à chaque instant : Σ E = √s et Σ p = 0
                    for sample in r['nucleon'].samples:
                        e_sum = sum(c['energy'] for c in sample['clusters'])
                        p_sum = sum((np.asarray(c['p_vec']) for c in sample['clusters']), np.zeros(3))
                        self.assertAlmostEqual(e_sum, col.sqrt_s, delta=1e-6, msg=(beam, tgt, ch['key'], sample['time']))
                        self.assertLess(float(np.linalg.norm(p_sum)), 1e-6)
                    self.assertLess(first['time'], 0, 'le projectile est montré avant le contact')
                    if ch['key'] == 'formation':
                        self.assertEqual(sum(1 for q in last['quarks'] if q['cluster'] < 0), 2, 'la paire annihilée est cachée')

    def test_a_string_break_is_an_exact_two_body_split_along_the_string(self):
        """À chaque rupture : p₁ + p₂ = P et E₁ + E₂ = E du parent, les autres hadrons inchangés, et les morceaux partent le
        long de la corde (seul le p_T de la paire, loi de Schwinger tronquée à p*, les en écarte)."""
        col = hadronic.HadronCollision(6000, 'proton', 'p')
        strings = next(i for i, c in enumerate(col.channels) if c['key'] == 'strings')
        checked = 0
        for seed in (1, 2, 3):
            r = hadron_events.build(col, seed, strings)
            previous = None
            for sample in r['nucleon'].samples:
                now = {frozenset(c['ids']): (c['energy'], np.asarray(c['p_vec'])) for c in sample['clusters']}
                if previous is not None:
                    for key, (e, p) in now.items():
                        if key in previous:
                            self.assertLess(float(np.linalg.norm(p - previous[key][1])), 1e-6, 'un spectateur ne bouge pas')
                    gone = [k for k in previous if k not in now]
                    new = [k for k in now if k not in previous]
                    if gone and new and sample['time'] > 0:
                        self.assertAlmostEqual(sum(previous[k][0] for k in gone), sum(now[k][0] for k in new), delta=1e-6)
                        dp = sum((previous[k][1] for k in gone), np.zeros(3)) - sum((now[k][1] for k in new), np.zeros(3))
                        self.assertLess(float(np.linalg.norm(dp)), 1e-6)
                        checked += 1
                previous = now
            for b in r['nucleon'].breaks:
                d = b['decay']
                self.assertLessEqual(d['pt'], d['p_star'] + 1e-9)
                along = math.sqrt(max(0.0, d['p_star'] ** 2 - d['pt'] ** 2))
                self.assertAlmostEqual(abs(float(d['direction'] @ b['axis'])) * d['p_star'], along, delta=1e-6)
        self.assertGreater(checked, 0)

    def test_a_break_happens_where_its_hadron_is_shown(self):
        """Le point d'une rupture est dans le repère de l'écran (le décalage de son parent compris) : sur les quarks montrés."""
        col = hadronic.HadronCollision(6000, 'proton', 'p')
        r = hadron_events.build(col, 3, next(i for i, c in enumerate(col.channels) if c['key'] == 'strings'))
        samples = r['nucleon'].samples
        for b in r['nucleon'].breaks:
            s = min((x for x in samples if x['time'] >= b['time']), key=lambda x: x['time'])
            live = [i for i, q in enumerate(s['quarks']) if q.get('cluster', 0) >= 0]
            self.assertLess(min(float(np.linalg.norm(s['x'][i] - b['position'])) for i in live), .6)

    def test_a_neutrino_flies_straight_until_the_w(self):
        col = sc.Collision(3000, 20, 'proton', 'nu')
        dis = next(i for i, c in enumerate(col.channels) if c['key'] == 'dis')
        r = collision_events.build(col, 2, dis)
        path = np.asarray(r['tracks']['path'])
        self.assertGreater(len(path), 5)
        self.assertLess(float(np.ptp(path[:, 0])) + float(np.ptp(path[:, 1])), 1e-12, 'aucune courbure : charge 0')
        self.assertEqual(r['tracks']['deflection'], 0.0)

    def test_refusals(self):
        with self.assertRaises(ValueError):
            hadronic.HadronCollision(500, 'proton', 'p')
        with self.assertRaises(ValueError):
            hadronic.HadronCollision(20000, 'proton', 'pi+')


class LobeTest(unittest.TestCase):
    def test_delta_decay_laws_have_their_measured_shapes(self):
        """⟨cos²θ*⟩ : 1 + 3cos²θ (formé par un pion) → 7/15 ; 5 − 3cos²θ (M1, photon) → 4/15 ; isotrope → 1/3."""
        rng = np.random.default_rng(4)
        axis = np.array([0.0, 0.0, 1.0])
        for kind, expected in (('had', 7 / 15), ('em', 4 / 15), ('cc', 1 / 3)):
            law = collision_events.decay_law(kind)
            c2 = np.mean([float(collision_events.decay_direction(rng, axis, law) @ axis) ** 2 for _ in range(20000)])
            self.assertAlmostEqual(c2, expected, delta=.01, msg=kind)

    def test_every_event_carries_its_angular_law(self):
        col = sc.Collision(1500, 60, 'proton', 'e-')
        r = collision_events.build(col, 3, 1)                      # excitation Δ : dσ/dΩ et la désintégration
        labels = [lb['label'] for lb in r['lobes']]
        self.assertTrue(labels[0].startswith('dσ/dΩ'))
        self.assertAlmostEqual(r['lobes'][0]['chosen'], 60.0, places=6)
        self.assertAlmostEqual(max(r['lobes'][0]['density']), 1.0, places=12)
        self.assertTrue(any(lb.startswith('désintégration') for lb in labels))
        thetas, values = sc.angular_table(1500, 'proton', 'e-')
        self.assertGreater(values[0], 1e4 * values[-1], "dσ/dΩ chute de plusieurs décades de 5° à 170°")
        hc = hadronic.HadronCollision(6000, 'proton', 'p')
        elastic = next(i for i, c in enumerate(hc.channels) if c['key'] == 'elastic_h')
        lobe = hadron_events.build(hc, 3, elastic)['lobes'][0]
        self.assertIn('diffraction', lobe['label'])
        self.assertLess(lobe['chosen'], 30.0, "la diffraction est vers l'avant")


class EngineTest(unittest.TestCase):
    def test_session_runs_every_projectile(self):
        s = Session(ElementRepository(mongo_uri=None), seed=2)
        s.start_nucleon()
        for code, energy in enumerate((2000, 2000, 3000, 3000, 3000, 3000, 4000, 3000, 300)):
            self.assertEqual(s.run_collision(0, float(energy), 30.0, code), [], code)
            msg = pb.CollisionState()
            fill_probe(msg, s.collision, {})
            self.assertEqual(msg.projectile, sc.PROJECTILE_CODES[code])
            self.assertTrue(msg.projectile_label)
            self.assertIn(msg.kind, ('em', 'cc', 'had'))
            self.assertGreater(len(msg.steps), 2)
            if msg.kind == 'cc':
                self.assertEqual(msg.boson, 'W⁺' if code == 4 else 'W⁻')
                self.assertGreater(msg.interaction_probability, 0)
            if msg.kind == 'had':
                self.assertEqual(len(msg.electron_path_fm), 0, 'un hadron n\'a pas de trajectoire de lepton')


if __name__ == '__main__':
    unittest.main()
