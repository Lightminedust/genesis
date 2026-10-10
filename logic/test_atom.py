"""Tests de la machine à états atomique.

Exécution : python -m unittest discover logic
Aucune dépendance externe : le dépôt tombe en repli mémoire si MongoDB est absent.
"""

import unittest

from atom import AtomicState, binding_energy, distribute_electrons, shell_capacity
from repository import ElementRepository


class ShellRulesTest(unittest.TestCase):

    def test_capacity_follows_2n_squared(self):
        self.assertEqual([shell_capacity(n) for n in range(1, 5)], [2, 8, 18, 32])

    def test_oxygen_fills_two_shells(self):
        self.assertEqual(distribute_electrons(8), [2, 6])

    def test_sodium_opens_a_third_shell(self):
        self.assertEqual(distribute_electrons(11), [2, 8, 1])

    def test_no_electron_means_no_shell(self):
        self.assertEqual(distribute_electrons(0), [])

    def test_distribution_conserves_electrons(self):
        for total in range(0, 61):
            self.assertEqual(sum(distribute_electrons(total)), total, f"pour {total} électrons")


class BindingEnergyTest(unittest.TestCase):

    def test_single_nucleon_has_no_binding(self):
        self.assertEqual(binding_energy(1, 0)[0], 0.0)

    def test_iron56_sits_near_the_peak(self):
        # Le fer-56 est le sommet de la courbe d'Aston : ~8,8 MeV/nucléon.
        _, per_nucleon, _ = binding_energy(26, 30)
        self.assertAlmostEqual(per_nucleon, 8.8, delta=0.3)

    def test_carbon12_binding_is_physical(self):
        total, per_nucleon, terms = binding_energy(6, 6)
        self.assertAlmostEqual(total, 92.2, delta=5.0)   # valeur mesurée : 92,16 MeV
        self.assertGreater(per_nucleon, 7.0)
        self.assertGreater(terms["volume"], 0)           # le volume lie
        self.assertLess(terms["coulomb"], 0)             # la répulsion délie


class AtomicStateTest(unittest.TestCase):

    def setUp(self):
        # mongo_uri=None => repli mémoire, le test ne dépend pas de Docker.
        self.repo = ElementRepository(mongo_uri=None)

    def evaluate(self, z, n, e):
        return AtomicState(z, n, e).evaluate(self.repo)

    def test_no_proton_is_not_an_atom(self):
        self.assertEqual(self.evaluate(0, 3, 0).stability, "NOT_AN_ATOM")

    def test_protons_alone_define_the_element(self):
        # Même Z, N et E très différents : c'est toujours du carbone.
        self.assertEqual(self.evaluate(6, 6, 6).element["name"], "Carbone")
        self.assertEqual(self.evaluate(6, 9, 2).element["name"], "Carbone")

    def test_carbon12_is_stable(self):
        ev = self.evaluate(6, 6, 6)
        self.assertEqual(ev.stability, "STABLE")
        self.assertEqual(ev.mass_number, 12)
        self.assertEqual(ev.decay_mode, "")

    def test_carbon14_is_unstable_and_beta_minus(self):
        ev = self.evaluate(6, 8, 6)
        self.assertEqual(ev.stability, "UNSTABLE")
        self.assertEqual(ev.decay_mode, "beta-")

    def test_proton_rich_nucleus_decays_the_other_way(self):
        ev = self.evaluate(6, 3, 6)  # carbone-9 : mesuré β+ (NUBASE)
        self.assertEqual(ev.stability, "UNSTABLE")
        self.assertEqual(ev.decay_mode, "beta+")

    def test_charge_and_ion_labels(self):
        self.assertEqual(self.evaluate(11, 12, 11).ion_label, "Neutre")
        self.assertEqual(self.evaluate(11, 12, 10).ion_label, "Cation 1+")
        self.assertEqual(self.evaluate(17, 18, 18).ion_label, "Anion 1-")
        self.assertEqual(self.evaluate(11, 12, 10).net_charge, 1)

    def test_neon_satisfies_the_octet(self):
        ev = self.evaluate(10, 10, 10)
        self.assertEqual(ev.shells, [2, 8])
        self.assertEqual(ev.valence_electrons, 8)
        self.assertTrue(ev.octet_satisfied)

    def test_helium_satisfies_the_duet(self):
        ev = self.evaluate(2, 2, 2)
        self.assertEqual(ev.shells, [2])
        self.assertTrue(ev.octet_satisfied)

    def test_transition_metal_follows_klechkowski(self):
        # Le remplissage 2n^2 naïf donnerait [2, 8, 16] pour le fer neutre.
        ev = self.evaluate(26, 30, 26)
        self.assertEqual(ev.shells, [2, 8, 14, 2])
        self.assertEqual(ev.valence_electrons, 2)
        self.assertIn("3d6 4s2", ev.configuration_text)

    def test_cation_loses_its_outermost_electrons_first(self):
        # Fe2+ perd ses 4s, pas ses 3d : [Ar] 3d6.
        ev = self.evaluate(26, 30, 24)
        self.assertEqual(ev.shells, [2, 8, 14])
        self.assertTrue(ev.configuration_text.endswith("3d6"))

    def test_iron_three_plus_reaches_the_half_filled_d(self):
        ev = self.evaluate(26, 30, 23)
        self.assertTrue(ev.configuration_text.endswith("3d5"))

    def test_isoelectronic_ions_share_a_configuration(self):
        sodium_cation = self.evaluate(11, 12, 10)
        oxide_anion = self.evaluate(8, 8, 10)
        self.assertEqual(sodium_cation.shells, oxide_anion.shells)
        self.assertEqual(sodium_cation.shells, [2, 8])

    def test_valence_above_eight_does_not_claim_a_negative_need(self):
        # Garde-fou : un ancien bug affichait "tend à capter -8 e-".
        for z, n, e in ((26, 30, 24), (30, 35, 28), (29, 34, 27)):
            ev = self.evaluate(z, n, e)
            valence_step = next(step for step in ev.calculations
                                if step["label"] == "Couche de valence")
            self.assertNotIn("-8", valence_step["result"])
            if ev.valence_electrons > 8:
                self.assertIn("hors règle de l'octet", valence_step["result"])

    def test_light_nuclei_flag_the_model_limits(self):
        # He-2 sort une énergie de liaison absurde : il faut le signaler.
        steps = {step["label"]: step for step in self.evaluate(2, 0, 2).calculations}
        binding = steps["Énergie de liaison (Weizsäcker)"]
        self.assertEqual(binding["severity"], "warn")
        self.assertIn("hors domaine de validité", binding["note"].lower())

    def test_heavy_nuclei_do_not_carry_the_warning(self):
        steps = {step["label"]: step for step in self.evaluate(26, 30, 26).calculations}
        self.assertEqual(steps["Énergie de liaison (Weizsäcker)"]["severity"], "info")

    def test_sodium_does_not_satisfy_the_octet(self):
        ev = self.evaluate(11, 12, 11)
        self.assertEqual(ev.valence_electrons, 1)
        self.assertFalse(ev.octet_satisfied)

    def test_nucleon_layout_is_deterministic_and_complete(self):
        first = self.evaluate(6, 6, 6).nucleons
        second = self.evaluate(6, 6, 6).nucleons
        self.assertEqual(first, second)
        self.assertEqual(len(first), 12)
        self.assertEqual(sum(1 for kind, *_ in first if kind == "proton"), 6)

    def test_every_evaluation_shows_its_work(self):
        labels = [step["label"] for step in self.evaluate(6, 6, 6).calculations]
        for expected in ["Identité chimique", "Nombre de masse", "Charge nette",
                         "Test de stabilité", "Couche de valence"]:
            self.assertIn(expected, labels)

    def test_load_element_builds_a_neutral_stable_atom(self):
        state = AtomicState()
        state.load_element(self.repo.find(8))
        ev = state.evaluate(self.repo)
        self.assertEqual((ev.protons, ev.neutrons, ev.electrons), (8, 8, 8))
        self.assertEqual(ev.stability, "STABLE")
        self.assertEqual(ev.net_charge, 0)

    def test_counters_never_go_negative(self):
        state = AtomicState()
        state.remove_proton()
        state.remove_neutron()
        state.remove_electron()
        self.assertEqual((state.protons, state.neutrons, state.electrons), (0, 0, 0))


class PeriodicTableTest(unittest.TestCase):

    def setUp(self):
        self.repo = ElementRepository(mongo_uri=None)
        self.elements = self.repo.all()

    def test_table_is_complete(self):
        self.assertEqual(len(self.elements), 118)
        self.assertEqual([e["z"] for e in self.elements], list(range(1, 119)))

    def test_no_two_elements_share_a_cell(self):
        cells = {(e["table_row"], e["table_col"]) for e in self.elements}
        self.assertEqual(len(cells), 118)

    def test_layout_places_the_landmarks(self):
        by_symbol = {e["symbol"]: (e["table_row"], e["table_col"]) for e in self.elements}
        self.assertEqual(by_symbol["H"], (1, 1))
        self.assertEqual(by_symbol["He"], (1, 18))
        self.assertEqual(by_symbol["C"], (2, 14))
        self.assertEqual(by_symbol["Fe"], (4, 8))
        self.assertEqual(by_symbol["La"], (9, 3))    # lanthanides sur leur ligne
        self.assertEqual(by_symbol["Ac"], (10, 3))   # actinides sur la leur
        self.assertEqual(by_symbol["Hf"], (6, 4))    # reprise du bloc d apres les lanthanides
        self.assertEqual(by_symbol["Og"], (7, 18))

    def test_elements_without_stable_isotopes(self):
        empty = {e["symbol"] for e in self.elements if not e["stable_isotopes"]}
        # Technetium et promethium n'en ont aucun, comme tout ce qui depasse le plomb.
        self.assertIn("Tc", empty)
        self.assertIn("Pm", empty)
        self.assertIn("U", empty)
        self.assertNotIn("Pb", empty)
        self.assertNotIn("Fe", empty)

    def test_every_isotope_of_technetium_is_unstable(self):
        for neutrons in range(40, 70):
            ev = AtomicState(43, neutrons, 43).evaluate(self.repo)
            self.assertEqual(ev.stability, "UNSTABLE", f"Tc-{43 + neutrons}")
            self.assertNotEqual(ev.decay_mode, "")

    def test_loading_an_unstable_element_still_builds_an_atom(self):
        state = AtomicState()
        state.load_element(self.repo.find(92))  # Uranium, aucun isotope stable
        ev = state.evaluate(self.repo)
        self.assertEqual(ev.protons, 92)
        self.assertGreater(ev.neutrons, 0)
        self.assertEqual(ev.stability, "UNSTABLE")


class OrbitalShapeTest(unittest.TestCase):
    """La vue quantique doit changer d'un élément à l'autre, et pour de bonnes raisons."""

    def setUp(self):
        self.repo = ElementRepository(mongo_uri=None)

    def orbital(self, z, e=None):
        return AtomicState(z, z, z if e is None else e).evaluate(self.repo).orbital

    @staticmethod
    def at(orbital, fx, fz):
        """Densité au point (fx, fz), en fractions de la demi-largeur."""
        size = orbital["size"]
        column = min(size - 1, int((fx + 1) / 2 * size))
        row = min(size - 1, int((1 - fz) / 2 * size))
        return orbital["grid"][row * size + column]

    def test_valence_subshell_is_the_last_one_filled(self):
        self.assertEqual(self.orbital(1)["label"], "1s1")
        self.assertEqual(self.orbital(5)["label"], "2p1")
        self.assertEqual(self.orbital(26)["label"], "3d6")   # 3d, remplie après 4s

    def test_radial_nodes_follow_n_minus_l_minus_1(self):
        self.assertEqual(self.orbital(11)["radial_nodes"], 2)  # 3s
        self.assertEqual(self.orbital(13)["radial_nodes"], 1)  # 3p
        self.assertEqual(self.orbital(26)["radial_nodes"], 0)  # 3d

    def test_p_orbital_has_a_nodal_plane(self):
        # Bore, 2p1 : une seule orbitale p_z. Le plan z = 0 est un plan nodal,
        # la densité s'y effondre alors qu'elle est forte sur l'axe z. (La
        # grille, de taille paire, n'a pas de ligne exactement sur z = 0 :
        # on vérifie un rapport, pas un zéro.)
        boron = self.orbital(5)
        in_plane = self.at(boron, 0.3, 0.0)
        on_axis = self.at(boron, 0.0, 0.3)
        self.assertGreater(on_axis, 0.1)
        self.assertLess(in_plane, 0.01 * on_axis)

    def test_half_filled_subshell_is_round(self):
        # Azote, 2p3 : Hund met un électron par orbitale, la somme est sphérique
        # (théorème d'Unsöld). Même rayon, même densité, dans toutes les directions.
        nitrogen = self.orbital(7)
        along_x = self.at(nitrogen, 0.3, 0.0)
        along_z = self.at(nitrogen, 0.0, 0.3)
        self.assertAlmostEqual(along_x, along_z, delta=0.05 * along_z)

    def test_hund_puts_one_electron_per_orbital_before_pairing(self):
        from orbitals import hund_occupation
        self.assertEqual(hund_occupation(1, 3), [1, 1, 1])
        self.assertEqual(hund_occupation(1, 4), [2, 1, 1])
        self.assertEqual(hund_occupation(2, 6), [2, 1, 1, 1, 1])

    def test_view_differs_between_elements(self):
        # Le reproche fait à l'ancienne vue : toujours la même image.
        hydrogen, boron, iron = self.orbital(1), self.orbital(5), self.orbital(26)
        self.assertNotEqual(hydrogen["grid"], boron["grid"])
        self.assertNotEqual(boron["grid"], iron["grid"])
        self.assertNotAlmostEqual(hydrogen["extent_pm"], iron["extent_pm"], delta=1.0)

    def test_empty_atom_has_no_orbital(self):
        self.assertIsNone(AtomicState(0, 0, 0).evaluate(self.repo).orbital)


class SlaterTest(unittest.TestCase):

    def test_sodium_valence_charge(self):
        from slater import effective_charge
        from electron_config import configuration
        # Valeur classique des manuels : Z_eff(3s, Na) = 11 - 8,8 = 2,2
        self.assertAlmostEqual(effective_charge(11, configuration(11, 11), 3, 0), 2.2, places=6)

    def test_d_electrons_are_screened_by_everything_to_their_left(self):
        from slater import effective_charge
        from electron_config import configuration
        # Fe 3d : 18 électrons à gauche (x1,00) + 5 dans le groupe (x0,35) -> 6,25
        self.assertAlmostEqual(effective_charge(26, configuration(26, 26), 3, 2), 6.25, places=6)

    def test_s_and_p_of_the_same_shell_form_one_group(self):
        from slater import effective_charge
        from electron_config import configuration
        # Valeurs des manuels. Un ancien bug retirait « l'électron lui-même »
        # une fois pour 2s et une fois pour 2p, donnant 3,60 pour le carbone.
        self.assertAlmostEqual(effective_charge(6, configuration(6, 6), 2, 1), 3.25, places=6)
        self.assertAlmostEqual(effective_charge(7, configuration(7, 7), 2, 1), 3.90, places=6)
        self.assertAlmostEqual(effective_charge(8, configuration(8, 8), 2, 1), 4.55, places=6)

    def test_ionization_uses_the_total_energy_difference(self):
        # E(ion) - E(atome) : 11,46 eV pour le carbone, mesure 11,26 eV.
        # L'énergie orbitale seule aurait donné 36 eV (+219 %).
        repo = ElementRepository(mongo_uri=None)
        carbon = AtomicState(6, 6, 6).evaluate(repo)
        self.assertAlmostEqual(carbon.ionization_model_ev, carbon.ionization_measured_ev,
                               delta=0.05 * carbon.ionization_measured_ev)
        oxygen = AtomicState(8, 8, 8).evaluate(repo)
        self.assertAlmostEqual(oxygen.ionization_model_ev, oxygen.ionization_measured_ev,
                               delta=0.05 * oxygen.ionization_measured_ev)

    def test_ionization_estimate_is_honest_about_its_error(self):
        repo = ElementRepository(mongo_uri=None)
        ev = AtomicState(11, 12, 11).evaluate(repo)
        self.assertAlmostEqual(ev.ionization_measured_ev, 5.139, places=3)
        # Slater surestime : le simulateur doit afficher les deux, pas cacher l'écart.
        self.assertGreater(ev.ionization_model_ev, ev.ionization_measured_ev)


class SpectrumTest(unittest.TestCase):
    """Chaque raie calculée est confrontée à sa valeur de laboratoire."""

    def setUp(self):
        self.repo = ElementRepository(mongo_uri=None)

    def test_balmer_series_matches_the_laboratory(self):
        from spectra import hydrogenic_transition, reduced_mass_factor
        mu = reduced_mass_factor(1, 0, 0.0)
        for upper, measured in [(3, 656.28), (4, 486.13), (5, 434.05), (6, 410.17)]:
            computed = hydrogenic_transition(1, upper, 2, mu)["wavelength_nm"]
            self.assertAlmostEqual(computed, measured, delta=0.02, msg=f"Balmer {upper}->2")

    def test_lyman_alpha_is_ultraviolet(self):
        from spectra import hydrogenic_transition, reduced_mass_factor, region
        line = hydrogenic_transition(1, 2, 1, reduced_mass_factor(1, 0, 0.0))
        self.assertAlmostEqual(line["wavelength_nm"], 121.57, delta=0.02)
        self.assertEqual(region(line["wavelength_nm"]), "UV")

    def test_hydrogen_ionization_is_exact(self):
        ev = AtomicState(1, 0, 1).evaluate(self.repo)
        self.assertAlmostEqual(ev.ionization_model_ev, 13.598, delta=0.001)

    def test_helium_ion_line(self):
        from spectra import hydrogenic_transition, reduced_mass_factor
        from atom import binding_energy
        mu = reduced_mass_factor(2, 2, binding_energy(2, 2)[0])
        # He II 4->3, raie de Fowler mesurée à 468,57 nm
        self.assertAlmostEqual(hydrogenic_transition(2, 4, 3, mu)["wavelength_nm"], 468.57, delta=0.03)

    def test_reduced_mass_is_not_neglected(self):
        from spectra import reduced_mass_factor
        # Sans correction, Hα serait décalée de 0,36 nm : visible au spectromètre.
        self.assertAlmostEqual(reduced_mass_factor(1, 0, 0.0), 0.999456, places=6)

    def test_sodium_shows_its_measured_d_lines(self):
        ev = AtomicState(11, 12, 11).evaluate(self.repo)
        wavelengths = [line["wavelength_nm"] for line in ev.spectrum]
        self.assertIn(588.995, wavelengths)
        self.assertIn(589.592, wavelengths)
        self.assertIn("NIST", ev.spectrum_source)

    def test_many_electron_ion_does_not_invent_a_spectrum(self):
        ev = AtomicState(11, 12, 10).evaluate(self.repo)   # Na+
        self.assertEqual(ev.spectrum, [])
        self.assertIn("non tabulé", ev.spectrum_source)


class LightEmissionTest(unittest.TestCase):

    def setUp(self):
        self.repo = ElementRepository(mongo_uri=None)

    def test_hydrogen_cascade_emits_h_alpha_then_lyman_alpha(self):
        state = AtomicState(1, 0, 1)
        self.assertTrue(state.excite())
        self.assertTrue(state.excite())                   # n = 3
        excited = state.evaluate(self.repo)
        self.assertEqual(excited.excitation_level, 3)
        # Rayon de Bohr n² a0 : l'électron excité est 9 fois plus loin.
        self.assertAlmostEqual(excited.excited_radius_pm / excited.subshells[0]["radius_pm"], 9.0, places=6)

        photons = state.relax(self.repo)
        self.assertEqual([(p["from_n"], p["to_n"]) for p in photons], [(3, 2), (2, 1)])
        self.assertAlmostEqual(photons[0]["wavelength_nm"], 656.28, delta=0.02)   # rouge
        self.assertAlmostEqual(photons[1]["wavelength_nm"], 121.57, delta=0.02)   # UV
        self.assertEqual(state.excitation, 0)

    def test_energy_is_conserved_through_the_cascade(self):
        from spectra import hydrogenic_level_ev, reduced_mass_factor
        state = AtomicState(1, 0, 1)
        for _ in range(4):
            state.excite()                                # n = 5
        photons = state.relax(self.repo)
        mu = reduced_mass_factor(1, 0, 0.0)
        gap = hydrogenic_level_ev(1, 5, mu) - hydrogenic_level_ev(1, 1, mu)
        self.assertAlmostEqual(sum(p["energy_ev"] for p in photons), gap, places=9)

    def test_sodium_emits_its_real_yellow_line(self):
        state = AtomicState(11, 12, 11)
        state.excite()
        photons = state.relax(self.repo)
        self.assertEqual(len(photons), 1)
        self.assertAlmostEqual(photons[0]["wavelength_nm"], 588.995, places=3)
        self.assertIn("NIST", photons[0]["source"])

    def test_excited_electron_leaves_its_box(self):
        # Sodium excité : la case 3s est vide, l'électron est sur l'orbite excitée.
        state = AtomicState(11, 12, 11)
        state.excite()
        ev = state.evaluate(self.repo)
        valence = ev.subshells[-1]
        self.assertEqual(valence["label"], "3s")
        self.assertEqual(valence["electrons"], 0)
        self.assertEqual(ev.excitation_level, 4)
        self.assertEqual(sum(detail["electrons"] for detail in ev.subshells), 10)

    def test_structural_change_cancels_the_excitation(self):
        state = AtomicState(1, 0, 1)
        state.excite()
        state.add_neutron()
        self.assertEqual(state.excitation, 0)
        self.assertEqual(state.relax(self.repo), [])

    def test_cannot_excite_an_atom_without_electrons(self):
        self.assertFalse(AtomicState(6, 6, 0).excite())


class DecayTest(unittest.TestCase):
    """Les désintégrations s'appuient sur la mesure quand elle existe."""

    def setUp(self):
        self.repo = ElementRepository(mongo_uri=None)

    def test_carbon14_becomes_nitrogen14(self):
        state = AtomicState(6, 8, 6)
        event = state.decay(self.repo)
        self.assertEqual(event["mode"], "beta-")
        self.assertEqual((state.protons, state.neutrons), (7, 7))
        self.assertEqual(event["daughter"], "Azote-14")
        # Q mesuré, pas l'estimation de Weizsäcker qui donnerait -1,96 MeV.
        self.assertAlmostEqual(event["q_mev"], 0.156476, places=6)
        self.assertEqual(event["half_life"], "5 700 ans")
        self.assertFalse(event["model_fails"])

    def test_beta_minus_leaves_a_cation(self):
        # L'électron β part du noyau : le cortège, lui, n'a pas bougé.
        state = AtomicState(6, 8, 6)
        state.decay(self.repo)
        self.assertEqual(state.electrons, 6)
        self.assertEqual(state.evaluate(self.repo).net_charge, 1)

    def test_uranium238_emits_an_alpha(self):
        state = AtomicState(92, 146, 92)
        event = state.decay(self.repo)
        self.assertEqual(event["mode"], "alpha")
        self.assertEqual((state.protons, state.neutrons), (90, 144))   # thorium-234
        self.assertAlmostEqual(event["q_mev"], 4.2699, places=4)

    def test_beryllium7_can_only_capture(self):
        state = AtomicState(4, 3, 4)
        event = state.decay(self.repo)
        self.assertEqual(event["mode"], "CE")
        self.assertEqual(state.electrons, 3)       # l'électron capturé a disparu du cortège

    def test_free_neutron_decays_into_hydrogen(self):
        state = AtomicState(0, 1, 0)
        event = state.decay(self.repo)
        self.assertEqual((state.protons, state.neutrons), (1, 0))
        self.assertAlmostEqual(event["q_mev"], 0.78235, places=5)

    def test_helium5_is_unbound_and_sheds_a_neutron(self):
        # Trois clics sur « + neutron » depuis l'hydrogène donnent l'hélium-5 :
        # il n'est même pas lié, et perd un neutron en ~1e-21 s, pas par β-.
        state = AtomicState(2, 3, 2)
        self.assertEqual(state.evaluate(self.repo).decay_mode, "n")
        event = state.decay(self.repo)
        self.assertEqual(event["mode"], "n")
        self.assertEqual((state.protons, state.neutrons), (2, 2))    # hélium-4
        self.assertAlmostEqual(event["q_mev"], 0.735, places=3)

    def test_lithium5_sheds_a_proton(self):
        state = AtomicState(3, 2, 3)
        event = state.decay(self.repo)
        self.assertEqual(event["mode"], "p")
        self.assertEqual((state.protons, state.neutrons), (2, 2))    # hélium-4

    def test_beryllium8_splits_into_alphas(self):
        state = AtomicState(4, 4, 4)
        event = state.decay(self.repo)
        self.assertEqual(event["mode"], "alpha")
        self.assertAlmostEqual(event["q_mev"], 0.0918, places=4)

    def test_bound_neutron_rich_nuclei_still_decay_by_beta(self):
        # Li-8 est lié : il fait du β-. Weizsäcker seul l'aurait déclaré non lié.
        self.assertEqual(AtomicState(3, 5, 3).evaluate(self.repo).decay_mode, "beta-")
        # C-9 est lié côté protons : β+, pas émission de proton.
        self.assertIn(AtomicState(6, 3, 6).evaluate(self.repo).decay_mode, ("beta+ / CE", "beta+"))

    def test_oxygen_neutron_drip_line(self):
        # O-24 est le dernier oxygène lié ; O-25 perd un neutron.
        self.assertEqual(AtomicState(8, 16, 8).evaluate(self.repo).decay_mode, "beta-")
        self.assertEqual(AtomicState(8, 17, 8).evaluate(self.repo).decay_mode, "n")

    def test_stable_nucleus_refuses_to_decay(self):
        state = AtomicState(6, 6, 6)
        self.assertIsNone(state.decay(self.repo))
        self.assertEqual((state.protons, state.neutrons), (6, 6))

    def test_unmeasured_nucleus_is_flagged_as_estimate(self):
        state = AtomicState(50, 92, 50)               # étain-142, jamais observé
        event = state.decay(self.repo)
        self.assertEqual(event["mode"], "beta-")
        self.assertIn("estimation", event["q_source"])

    def test_nuclear_radius_matches_electron_scattering(self):
        from nucleus import nuclear_radius_fm
        # Plomb 208 : rayon de charge rms 5,50 fm, soit une sphère équivalente
        # de rayon sqrt(5/3) x 5,50 = 7,10 fm.
        self.assertAlmostEqual(nuclear_radius_fm(208), 7.10, delta=0.05)

    def test_nucleus_is_three_dimensional_and_fits_its_radius(self):
        from nucleus import nuclear_radius_fm
        ev = AtomicState(26, 30, 26).evaluate(self.repo)
        radius = nuclear_radius_fm(56)
        depths = {round(z, 3) for _, _, _, z in ev.nucleons}
        self.assertGreater(len(depths), 20)                    # vraiment en 3D
        for _, x, y, z in ev.nucleons:
            self.assertLessEqual((x * x + y * y + z * z) ** 0.5, radius)


class ConsoleTest(unittest.TestCase):

    def setUp(self):
        self.repo = ElementRepository(mongo_uri=None)

    def lines(self, z, n, e, label=None):
        return AtomicState(z, n, e).evaluate(self.repo, action_label=label).console_lines

    def test_action_label_opens_the_log(self):
        lines = self.lines(6, 6, 6, label="+ 1 proton.")
        self.assertEqual(lines[0], "+ 1 proton.")

    def test_log_shows_the_arithmetic(self):
        lines = self.lines(6, 7, 5)
        self.assertIn("A = Z + N = 6 + 7 = 13 nucléons.", lines)
        self.assertIn("q = (6 x +e) + (5 x -e) = +1 [Cation 1+].", lines)

    def test_unstable_nucleus_announces_its_decay(self):
        joined = " ".join(self.lines(6, 8, 6))
        self.assertIn("INSTABLE", joined)
        self.assertIn("beta-", joined)

    def test_critical_ratio_raises_an_alert(self):
        joined = " ".join(self.lines(3, 0, 3))  # lithium sans aucun neutron
        self.assertIn("ALERTE", joined)

    def test_stable_light_nuclei_raise_no_false_alarm(self):
        # Un ancien bug alertait sur l'hydrogène-1 (N/Z = 0) : un proton seul
        # n'a aucune répulsion à compenser, il est parfaitement stable.
        for z, n in ((1, 0), (2, 1)):          # hydrogène-1, hélium-3
            joined = " ".join(self.lines(z, n, z))
            self.assertNotIn("ALERTE", joined, f"Z={z} N={n}")

    def test_mass_defect_is_reported(self):
        joined = " ".join(self.lines(6, 6, 6))
        self.assertIn("Défaut de masse", joined)
        self.assertIn("MeV", joined)

    def test_empty_chamber_says_so(self):
        joined = " ".join(self.lines(0, 0, 0))
        self.assertIn("pas encore un élément", joined)


class MassDefectTest(unittest.TestCase):

    def setUp(self):
        self.repo = ElementRepository(mongo_uri=None)

    def test_carbon12_mass_defect(self):
        ev = AtomicState(6, 6, 6).evaluate(self.repo)
        # B ~ 92 MeV / 931,494 MeV par u -> ~0,099 u
        self.assertAlmostEqual(ev.mass_defect_u, 0.0962, delta=0.01)

    def test_mass_defect_tracks_binding_energy(self):
        from atom import MEV_PER_U
        ev = AtomicState(26, 30, 26).evaluate(self.repo)
        self.assertAlmostEqual(ev.mass_defect_u, ev.binding_total / MEV_PER_U, places=6)


if __name__ == "__main__":
    unittest.main()
