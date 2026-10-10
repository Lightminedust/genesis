"""Le simulateur à règles, confronté aux valeurs réelles.

Les tirages au hasard sont graines fixées : chaque test est reproductible.
"""

import math
import random
import statistics
import unittest
from unittest.mock import patch

import atomic_data
import hydrogen
import nuclear_data
from repository import ElementRepository
from simulation import PHOTON_FLIGHT_REAL_S, Session


def kinds(events):
    return [event["kind"] for event in events]


class EinsteinCoefficientsTest(unittest.TestCase):
    """Durées de vie de l'hydrogène : valeurs de référence (Wiese & Fuhr, NIST)."""

    def test_2p_lifetime(self):
        self.assertAlmostEqual(hydrogen.lifetime_s(1, (2, 1)) * 1e9, 1.596, delta=0.005)

    def test_3p_and_3d_lifetimes(self):
        self.assertAlmostEqual(hydrogen.lifetime_s(1, (3, 1)) * 1e9, 5.27, delta=0.03)
        self.assertAlmostEqual(hydrogen.lifetime_s(1, (3, 2)) * 1e9, 15.6, delta=0.2)

    def test_3p_branching_ratio(self):
        channels = hydrogen.decay_channels(1, (3, 1))
        total = sum(rate for _, rate, _ in channels)
        to_1s = sum(rate for state, rate, _ in channels if state == (1, 0))
        self.assertAlmostEqual(to_1s / total, 0.882, delta=0.002)

    def test_2s_is_metastable(self):
        # Aucune transition à un photon : seule la désintégration à deux photons, 0,12 s.
        self.assertAlmostEqual(hydrogen.lifetime_s(1, (2, 0)), 0.1215, delta=0.001)

    def test_selection_rule(self):
        self.assertEqual(hydrogen.einstein_a(1, (3, 0), (1, 0)), 0.0)   # Δl = 0
        self.assertEqual(hydrogen.einstein_a(1, (3, 2), (1, 0)), 0.0)   # Δl = 2

    def test_rates_scale_as_z4_for_ions(self):
        ratio = hydrogen.einstein_a(2, (2, 1), (1, 0)) / hydrogen.einstein_a(1, (2, 1), (1, 0))
        self.assertAlmostEqual(ratio, 16, delta=0.01)


class NuclearDataTest(unittest.TestCase):
    """Les bases évaluées remplacent les tables recopiées à la main."""

    def test_q_values_from_measured_masses(self):
        self.assertAlmostEqual(nuclear_data.q_value_mev(6, 14, "beta-"), 0.1565, delta=0.001)
        self.assertAlmostEqual(nuclear_data.q_value_mev(92, 238, "alpha"), 4.270, delta=0.002)
        self.assertAlmostEqual(nuclear_data.q_value_mev(4, 7, "CE"), 0.862, delta=0.002)

    def test_branching_ratios(self):
        channels = dict((mode, fraction) for mode, fraction, _ in nuclear_data.info(83, 212)["channels"])
        self.assertAlmostEqual(channels["beta-"], 0.64, delta=0.01)
        self.assertAlmostEqual(channels["alpha"], 0.36, delta=0.01)

    def test_unbound_nuclei_are_known(self):
        self.assertEqual(nuclear_data.info(2, 5)["channels"][0][0], "n")
        self.assertLess(nuclear_data.info(2, 5)["half_life_s"], 1e-20)

    def test_successive_ionization_energies(self):
        self.assertAlmostEqual(atomic_data.ionization_energy_ev(26, 0), 7.902, delta=0.001)
        self.assertAlmostEqual(atomic_data.ionization_energy_ev(26, 1), 16.199, delta=0.001)
        # Le dernier électron du fer : hydrogénoïde, 13,6 × 26² eV à la relativité près.
        self.assertAlmostEqual(atomic_data.ionization_energy_ev(26, 25), 9277.7, delta=1)


class SessionTest(unittest.TestCase):
    def setUp(self):
        self.repo = ElementRepository(mongo_uri=None)
        self.session = Session(self.repo, seed=11)

    def run_for(self, real_seconds, step=0.05):
        events = []
        for _ in range(int(real_seconds / step)):
            events += self.session.advance(step)
        return events

    def hydrogen(self):
        self.session.add_proton()
        self.session.add_electron()
        self.session.electron_state = (1, 0)

    # --- Lumière ----------------------------------------------------------
    def test_captured_electron_cascades_to_ground_state(self):
        self.session.add_proton()
        events = self.session.add_electron()
        self.assertIn("captured", kinds(events))
        self.assertNotEqual(self.session.electron_state, (1, 0))
        events = []
        for _ in range(10):
            events += self.session.next_event()
            if self.session.electron_state == (1, 0):
                break
        self.assertEqual(self.session.electron_state, (1, 0))
        self.assertIn("emitted", kinds(events))

    def test_resonant_photon_is_absorbed(self):
        self.hydrogen()
        self.session.fire_photon(self.session.absorbable_lines()[0])
        with patch.object(self.session.rng, "random", return_value=0.0):
            events = self.run_for(PHOTON_FLIGHT_REAL_S + 0.3)
        self.assertIn("absorbed", kinds(events))
        self.assertEqual(self.session.electron_state, (2, 1))

    def test_off_resonance_photon_passes_through(self):
        self.hydrogen()
        self.session.fire_photon(500.0)
        events = self.run_for(PHOTON_FLIGHT_REAL_S + 0.3)
        self.assertIn("passed", kinds(events))
        self.assertEqual(self.session.electron_state, (1, 0))

    def test_energetic_photon_ionizes_with_the_right_kinetic_energy(self):
        self.hydrogen()
        self.session.fire_photon(50.0)
        with patch.object(self.session.rng, "random", return_value=0.0):
            events = self.run_for(PHOTON_FLIGHT_REAL_S + 0.3)
        ionized = [event for event in events if event["kind"] == "photo_ionized"][0]
        self.assertAlmostEqual(ionized["kinetic_ev"], 1239.84 / 50.0 - 13.598, delta=0.01)
        self.assertEqual(self.session.atom.electrons, 0)

    def test_sodium_absorbs_and_reemits_the_yellow_line(self):
        self.session.load_element(11)
        self.session.fire_photon(588.995)
        with patch.object(self.session.rng, "random", return_value=0.0):
            self.run_for(PHOTON_FLIGHT_REAL_S + 0.3)
        self.assertIsNotNone(self.session.excited)
        events = self.session.next_event()
        emitted = [event for event in events if event["kind"] == "emitted"][0]
        self.assertAlmostEqual(emitted["wavelength_nm"], 588.995, delta=0.01)

    def test_flame_shows_measured_lines_only(self):
        self.session.load_element(11)
        lines = {wavelength for wavelength, _ in __import__("spectra").REFERENCE_LINES[11]}
        for _ in range(20):
            self.session.heat()
            emitted = [e for e in self.session.next_event() if e["kind"] == "emitted"][0]
            self.assertIn(emitted["wavelength_nm"], lines)

    def test_2p_decay_times_follow_the_measured_lifetime(self):
        rng = random.Random(3)
        waits = []
        for _ in range(400):
            session = Session(self.repo, seed=rng.random())
            session.add_proton()
            session.add_electron()
            session.electron_state = (2, 1)
            session.set_speed(0)                    # 1 ns par seconde
            elapsed = 0.0
            while session.electron_state != (1, 0):
                session.advance(0.01)
                elapsed += 0.01
            waits.append(session.time_s)
        self.assertAlmostEqual(statistics.mean(waits) * 1e9, 1.596, delta=0.2)

    # --- Électrons ---------------------------------------------------------
    def test_neon_does_not_keep_an_extra_electron(self):
        self.session.load_element(10)
        events = self.session.add_electron()
        self.assertEqual(kinds(events), ["rejected"])
        self.assertEqual(self.session.atom.electrons, 10)

    def test_chlorine_anion_but_no_dianion(self):
        self.session.load_element(17)
        events = self.session.add_electron()
        self.assertIn("captured", kinds(events))
        self.assertEqual(self.session.atom.electrons, 18)
        events = self.session.add_electron()
        self.assertEqual(kinds(events), ["rejected"])
        self.assertEqual(self.session.atom.electrons, 18)

    def test_lost_protons_release_electrons(self):
        self.session.load_element(6)
        events = []
        for _ in range(3):
            events += self.session.remove_proton()
        # B, Be, Li... avec 6 électrons : il n'en reste que Z + 1 au plus.
        self.assertIn("rejected", kinds(events))
        self.assertLessEqual(self.session.atom.electrons, self.session.atom.protons + 1)

    # --- Noyau --------------------------------------------------------------
    def test_carbon14_decays_to_nitrogen14(self):
        self.session.load_element(6)
        self.session.add_neutron()
        self.session.add_neutron()
        events = self.session.next_event()
        decay = [event for event in events if event["kind"] == "decay"][0]
        self.assertEqual(decay["decay_mode"], "beta-")
        self.assertEqual((self.session.atom.protons, self.session.atom.neutrons), (7, 7))
        self.assertEqual(self.session.nuclear_rate(), 0.0)

    def test_bare_beryllium7_cannot_capture_an_electron(self):
        self.session.load_element(4)
        self.session.remove_neutron()
        self.session.remove_neutron()
        self.assertGreater(self.session.nuclear_rate(), 0)
        for _ in range(4):
            self.session.remove_electron()
        self.assertEqual(self.session.nuclear_rate(), 0.0)

    def test_uranium238_chain_ends_on_lead206(self):
        self.session.load_element(92)
        self.assertEqual(self.session.nuclide_label(), "U-238")
        for _ in range(40):
            self.session.next_event()
            if self.session.nuclear_rate() == 0:
                break
        self.assertEqual(self.session.nuclide_label(), "Pb-206")

    def test_decay_rate_matches_half_life(self):
        self.session.load_element(6)
        self.session.add_neutron()
        self.session.add_neutron()
        self.assertAlmostEqual(math.log(2) / self.session.nuclear_rate() / 3.15576e7, 5700, delta=30)

    # --- Spin et analyse ------------------------------------------------------
    def test_step_mode_stops_time_after_each_event(self):
        self.hydrogen()
        self.session.electron_state = (3, 1)
        self.session.set_speed(3)                  # temps réel : tout arrive dans le pas
        events = self.session.advance(0.05)
        self.assertEqual(kinds(events), ["emitted"])  # une seule marche de la cascade
        self.assertTrue(self.session.paused)

    def test_photon_does_not_flip_the_electron_spin(self):
        self.session.add_proton()
        self.session.add_electron()
        spin = self.session.electron_spin
        self.session.step_mode = False
        for _ in range(10):
            self.session.next_event()
        self.assertEqual(self.session.electron_state, (1, 0))
        self.assertEqual(self.session.electron_spin, spin)

    def test_sodium_doublet_is_explained_by_spin(self):
        self.session.load_element(11)
        self.session.fire_photon(589.592)
        with patch.object(self.session.rng, "random", return_value=0.0), \
                patch.object(self.session.rng, "uniform", side_effect=lambda a, b: (a + b) / 2):
            absorbed = [e for e in self.run_for(PHOTON_FLIGHT_REAL_S + 0.3) if e["kind"] == "absorbed"][0]
        self.assertIn("3p₁/₂", absorbed["detail"])
        self.assertIn("spin", absorbed["detail"])

    def test_nuclear_spin_and_measured_binding(self):
        self.assertEqual(nuclear_data.spin_parity(6, 14), "0+")
        self.assertEqual(nuclear_data.spin_parity(11, 23), "3/2+")
        _, _, binding = nuclear_data.measured_binding(26, 56)
        self.assertAlmostEqual(binding / 56, 8.790, delta=0.002)   # le noyau le plus lié par nucléon

    def test_particle_zoo_uses_codata(self):
        from particles import PARTICLES
        zoo = {particle["key"]: particle for particle in PARTICLES}
        self.assertAlmostEqual(zoo["electron"]["mass_mev"], 0.51099895, places=7)
        self.assertAlmostEqual(zoo["neutron"]["mass_mev"] - zoo["proton"]["mass_mev"], 1.2933, places=3)
        self.assertEqual(zoo["photon"]["spin"], "1")
        self.assertEqual(zoo["electron"]["spin"], "1/2")

    def test_next_event_on_a_stable_atom_does_nothing(self):
        self.session.load_element(6)
        events = self.session.next_event()
        self.assertEqual(kinds(events), ["info"])
        self.assertEqual(self.session.time_s, 0.0)


if __name__ == "__main__":
    unittest.main()
