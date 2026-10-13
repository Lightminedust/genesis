"""Références physiques et intégration moteur → contrat → rendu."""
import math
import unittest
from unittest.mock import patch

from scipy import constants as sc

from atomic import hydrogen
from atomic import light_interaction as light
from atomic import spectra
from atomic.orbitals import hydrogenic_orbital, radial
from simulator.repository import ElementRepository
from simulator.simulation import Session


class CrossSectionTest(unittest.TestCase):
    def test_closed_dipole_peak_cross_section(self):
        sigma = light.resonance_cross_section_m2(121.567, 121.567, 6.265e8, 6.265e8)
        self.assertAlmostEqual(sigma / (3 * (121.567e-9) ** 2 / (2 * math.pi)), 1)

    def test_natural_linewidth_half_maximum(self):
        rate = 6.265e8
        line = 121.567
        # HWHM = Γ/(4π) en Hz.
        detuned = spectra.vacuum_to_reported_nm(sc.c / (light.frequency_hz(line) + rate / (4 * math.pi)) * 1e9)
        peak = light.resonance_cross_section_m2(line, line, rate, rate)
        wing = light.resonance_cross_section_m2(detuned, line, rate, rate)
        self.assertAlmostEqual(wing / peak, 0.5, places=7)

    def test_branching_reduces_peak(self):
        closed = light.resonance_cross_section_m2(500, 500, 1e8, 1e8)
        branching = light.resonance_cross_section_m2(500, 500, 2e7, 1e8)
        self.assertAlmostEqual(branching / closed, 0.2)

    def test_ground_state_photoionization_threshold(self):
        sigma = light.hydrogen_1s_photoionization_m2(1, 13.6, 13.6)
        self.assertAlmostEqual(sigma / 6.304e-22, 1, delta=0.001)
        self.assertEqual(light.hydrogen_1s_photoionization_m2(1, 13, 13.6), 0)

    def test_photoionization_scales_with_charge_and_decreases_with_energy(self):
        h = light.hydrogen_1s_photoionization_m2(1, 13.6, 13.6)
        he = light.hydrogen_1s_photoionization_m2(2, 54.4, 54.4)
        self.assertAlmostEqual(h / he, 4)
        self.assertLess(light.hydrogen_1s_photoionization_m2(1, 100, 13.6), h)

    def test_probability_tracks_beam_area(self):
        self.assertAlmostEqual(light.interaction_probability(1e-15, 2e-6)
                               / light.interaction_probability(1e-15, 1e-6), 0.25)
        with self.assertRaises(ValueError):
            light.interaction_probability(1e-12, 1e-8)


class QuantumStateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo = ElementRepository(mongo_uri=None)

    def session(self):
        s = Session(self.repo, seed=123)
        s.atom.protons, s.atom.neutrons, s.atom.electrons = 1, 0, 1
        s.electron_state = (1, 0)
        return s

    def test_2s_and_2p_have_their_own_density_and_nodes(self):
        s = self.session()
        s.electron_state = (2, 0)
        orbital_s = s.evaluate().orbital
        s.electron_state = (2, 1)
        orbital_p = s.evaluate().orbital
        self.assertEqual(orbital_s['label'], '2s1')
        self.assertEqual(orbital_p['label'], '2p1')
        self.assertEqual(orbital_s['radial_nodes'], 1)
        self.assertEqual(orbital_p['radial_nodes'], 0)
        self.assertNotEqual(orbital_s['grid'], orbital_p['grid'])
        self.assertAlmostEqual(radial(2, 0, 1, 2 * 52.9177210903), 0)

    def test_unresolved_m_is_isotropic_and_supports_high_l(self):
        o = hydrogenic_orbital(1, (6, 5))
        self.assertEqual(o['label'], '6h1')
        self.assertEqual(o['orbital_names'], ['m non résolu'])
        n = o['size']
        for i in (0, 15, 37):
            self.assertAlmostEqual(o['grid'][i * n + n // 2], o['grid'][(n // 2) * n + i])

    def test_reduced_mass_and_ion_charge_rescale_cloud(self):
        h = hydrogenic_orbital(1, (2, 1), 1)
        he = hydrogenic_orbital(2, (2, 1), 1)
        light_nucleus = hydrogenic_orbital(1, (2, 1), 0.999456)
        self.assertAlmostEqual(h['extent_pm'] / he['extent_pm'], 2)
        self.assertGreater(light_nucleus['extent_pm'], h['extent_pm'])

    def test_excited_multielectron_density_is_not_invented(self):
        s = self.session()
        s.load_element(11)
        s.heat()
        self.assertIsNone(s.evaluate().orbital)

    def test_resonant_photon_can_pass_and_ionization_can_fail(self):
        s = self.session()
        with patch.object(s.rng, 'random', return_value=0.99):
            self.assertEqual(s._photon_arrives(s.absorbable_lines()[0])[0]['kind'], 'passed')
            self.assertEqual(s._photon_arrives(50)[0]['kind'], 'passed')
        self.assertEqual(s.electron_state, (1, 0))
        self.assertEqual(s.atom.electrons, 1)

    def test_unknown_continuum_reports_missing_model(self):
        s = self.session()
        s.electron_state = (2, 1)
        self.assertEqual(s._photon_arrives(50)[0]['kind'], 'info')
        self.assertEqual(s.atom.electrons, 1)

    def test_absorption_statistics_match_cross_section(self):
        s = self.session()
        wavelength = s.absorbable_lines()[0]
        rate = 1 / hydrogen.lifetime_s(1, (2, 1)) * s._mu()
        sigma = light.resonance_cross_section_m2(wavelength, wavelength, rate, rate)
        probability = light.interaction_probability(sigma, light.beam_waist_m(wavelength))
        count = 0
        trials = 5000
        for _ in range(trials):
            s.electron_state = (1, 0)
            count += s._photon_arrives(wavelength)[0]['kind'] == 'absorbed'
        self.assertGreater(count, 0)
        self.assertAlmostEqual(count, trials * probability,
                               delta=5 * math.sqrt(trials * probability * (1 - probability)))

    def test_frame_keeps_natural_line_precision(self):
        from engine import build_frame
        import genesis_pb2
        s = self.session()
        wavelength = s.absorbable_lines()[0]
        frame = build_frame(0, s.evaluate(), 'test')
        frame.absorbable_nm.extend(s.absorbable_lines())
        decoded = genesis_pb2.UniverseFrame.FromString(frame.SerializeToString())
        self.assertEqual(decoded.absorbable_nm[0], wavelength)
        self.assertEqual(decoded.orbital.label, '1s1')


if __name__ == '__main__':
    unittest.main()
