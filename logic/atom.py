"""Machine à états d'un atome unique.

Volontairement sans dépendance gRPC ni MongoDB : ce module ne contient que
la physique, ce qui le rend directement testable. `engine.py` se charge de
traduire une `Evaluation` en message Protobuf.

Transitions de l'état :
    ajout / retrait de proton, neutron, électron
    excitation   : l'électron de valence absorbe de l'énergie et monte d'un niveau
    désexcitation: il redescend en émettant un ou plusieurs photons
    désintégration : le noyau instable se transforme (β-, β+, capture, α)
"""

from dataclasses import dataclass, field

import spectra
from electron_config import (
    configuration,
    format_configuration,
    shell_configurations,
    shells_from_subshells,
)
from nucleus import (
    TERM_DESCRIPTIONS,
    binding_energy,
    fission_fragment,
    nuclear_radius_fm,
    nucleon_layout_3d,
    resolve_decay,
    target_ratio,
)
from nuclides import lookup as lookup_nuclide
from nuclides import longest_lived_isotope, particle_bound
from orbitals import SUBSHELL_LETTERS, hund_occupation, valence_orbital
from slater import (
    BOHR_RADIUS_PM,
    effective_charge,
    group_key,
    orbital_radius_pm,
    removal_energy_ev,
    total_energy_ev,
)

# Noms historiques des couches électroniques
SHELL_NAMES = ["K", "L", "M", "N", "O", "P", "Q"]

# Au-delà, l'électron d'un système hydrogénoïde est considéré comme arraché.
HIGHEST_LEVEL = 7

# Réexportés : les tests et le moteur les importent d'ici.
__all__ = ["AtomicState", "Evaluation", "binding_energy", "shell_capacity",
           "distribute_electrons", "SHELL_NAMES", "MEV_PER_U", "target_ratio"]

MEV_PER_U = spectra.MEV_PER_U


def shell_capacity(n):
    """Capacité maximale de la couche n : règle 2n carré."""
    return 2 * n * n


def distribute_electrons(total_electrons):
    """Répartition naïve par capacité 2n^2, couche après couche.

    Conservée pour la pédagogie (c'est la règle simple qu'on enseigne) et
    pour les tests. L'évaluation réelle passe par `electron_config`, qui suit
    l'ordre de Klechkowski et ses exceptions.

    Exemple : 8 électrons donnent [2, 6], 11 donnent [2, 8, 1].
    """
    shells = []
    remaining = max(0, total_electrons)
    n = 1
    while remaining > 0 and n <= len(SHELL_NAMES):
        capacity = shell_capacity(n)
        placed = min(remaining, capacity)
        shells.append(placed)
        remaining -= placed
        n += 1
    return shells


def subshell_label(n, l):
    return f"{n}{SUBSHELL_LETTERS[l]}"


@dataclass
class Evaluation:
    """Photographie complète et évaluée de l'atome."""
    protons: int
    neutrons: int
    electrons: int
    mass_number: int
    net_charge: int
    stability: str                 # STABLE | UNSTABLE | NOT_AN_ATOM | UNKNOWN
    ion_label: str
    isotope_label: str
    decay_mode: str
    headline: str
    binding_total: float
    binding_per_nucleon: float
    mass_defect_u: float
    valence_electrons: int
    octet_satisfied: bool
    ratio: float
    shells: list
    nucleons: list
    configuration_text: str
    shell_configurations: dict
    subshells: list = field(default_factory=list)
    levels: list = field(default_factory=list)
    spectrum: list = field(default_factory=list)
    spectrum_source: str = ""
    orbital: dict = None
    ionization_model_ev: float = 0.0
    ionization_measured_ev: float = None
    one_electron: bool = False
    nuclear_radius_fm: float = 0.0
    nuclear_terms: list = field(default_factory=list)
    excitation_level: int = 0
    excited_radius_pm: float = 0.0
    photons: list = field(default_factory=list)
    event: dict = None
    half_life: str = ""
    element: dict = None
    calculations: list = field(default_factory=list)
    console_lines: list = field(default_factory=list)


class AtomicState:
    """Les trois compteurs qui définissent tout, et leurs transitions."""

    def __init__(self, protons=0, neutrons=0, electrons=0):
        self.protons = protons
        self.neutrons = neutrons
        self.electrons = electrons
        self.excitation = 0      # nombre de niveaux au-dessus du fondamental
        self.emissions = 0       # compteur de désexcitations, pour varier les raies

    # ------------------------------------------------------------------
    # Transitions de structure : toute modification du cortège ou du noyau
    # ramène l'atome à son état fondamental.
    # ------------------------------------------------------------------
    def _structural(self):
        self.excitation = 0

    def reset(self):
        self.protons = self.neutrons = self.electrons = 0
        self._structural()

    def add_proton(self):
        self.protons += 1
        self._structural()

    def remove_proton(self):
        self.protons = max(0, self.protons - 1)
        self._structural()

    def add_neutron(self):
        self.neutrons += 1
        self._structural()

    def remove_neutron(self):
        self.neutrons = max(0, self.neutrons - 1)
        self._structural()

    def add_electron(self):
        self.electrons += 1
        self._structural()

    def remove_electron(self):
        self.electrons = max(0, self.electrons - 1)
        self._structural()

    def load_element(self, element):
        """Charge l'isotope stable le plus léger d'un élément, atome neutre.

        Les éléments sans isotope stable (technétium, prométhium, tout au-delà
        du plomb) reçoivent l'isotope le plus proche de la vallée de stabilité,
        pour que l'atome soit affichable même s'il est radioactif.
        """
        z = element["z"]
        self.protons = z
        isotopes = element.get("stable_isotopes")
        if isotopes:
            self.neutrons = max(0, min(isotopes) - z)
        elif longest_lived_isotope(z):
            self.neutrons = longest_lived_isotope(z) - z
        else:
            self.neutrons = max(0, round(z * target_ratio(z)))
        self.electrons = z
        self._structural()

    # ------------------------------------------------------------------
    # Lumière : excitation et désexcitation
    # ------------------------------------------------------------------
    def is_one_electron(self):
        return self.electrons == 1 and self.protons >= 1

    def excite(self):
        """L'électron de valence absorbe de l'énergie et monte d'un niveau.

        Un système à un électron peut monter jusqu'à n = 7, chaque niveau étant
        calculé exactement. Un atome à plusieurs électrons ne monte que d'un
        cran : au-delà, le modèle ne dirait plus rien de fiable.
        """
        if self.electrons == 0 or self.protons == 0:
            return False
        if self.is_one_electron():
            if 1 + self.excitation >= HIGHEST_LEVEL:
                return False
            self.excitation += 1
            return True
        if self.excitation >= 1:
            return False
        self.excitation = 1
        return True

    def relax(self, repository):
        """L'électron redescend : retourne les photons émis."""
        if self.excitation == 0:
            return []
        photons = self._emission_photons(repository)
        self.excitation = 0
        self.emissions += 1
        return photons

    def _emission_photons(self, repository):
        z, n = self.protons, self.neutrons
        if self.is_one_electron():
            # Cascade pas à pas vers le fondamental : c'est l'un des chemins
            # possibles (depuis n=3, l'électron peut aussi tomber directement
            # sur n=1). Chaque marche est calculée exactement.
            mu = spectra.reduced_mass_factor(z, n, binding_energy(z, n)[0])
            upper = 1 + self.excitation
            photons = []
            for level in range(upper, 1, -1):
                photons.append(spectra.hydrogenic_transition(z, level, level - 1, mu))
            return photons

        # Atome neutre dont on connaît les raies : il émet une raie réelle.
        if self.electrons == self.protons and spectra.REFERENCE_LINES.get(z):
            ordered = sorted(spectra.reference_spectrum(z), key=lambda line: -line["intensity"])
            line = dict(ordered[self.emissions % len(ordered)])
            line["label"] = f"raie mesurée {line['wavelength_nm']:.2f} nm"
            return [line]

        # Sinon, estimation de Slater, présentée comme telle.
        subshells = configuration(z, self.electrons)
        n_v, _, _ = subshells[-1]
        excited_config = self._excited_configuration(subshells)
        energy = total_energy_ev(z, excited_config) - total_energy_ev(z, subshells)
        if energy <= 0:
            return []
        return [{
            "wavelength_nm": spectra.wavelength_from_energy(energy),
            "energy_ev": energy,
            "from_n": n_v + 1,
            "to_n": n_v,
            "label": "estimation Slater",
            "source": "estimation Slater (aucune raie de référence)",
        }]

    @staticmethod
    def _excited_configuration(subshells):
        """Configuration avec l'électron de valence promu d'une couche."""
        excited = [list(entry) for entry in subshells]
        n_v, _, _ = excited[-1]
        excited[-1][2] -= 1
        excited.append([n_v + 1, 0, 1])
        return [tuple(entry) for entry in excited if entry[2] > 0]

    # ------------------------------------------------------------------
    # Désintégration
    # ------------------------------------------------------------------
    def decay(self, repository):
        """Désintègre le noyau s'il est instable. Retourne l'évènement, ou None."""
        z, n = self.protons, self.neutrons
        element = repository.find(z)
        stability = self._stability(z, z + n, element)
        if stability not in ("UNSTABLE", "NOT_AN_ATOM") or (z == 0 and n == 0):
            return None
        predicted = self._decay_mode(z, n, z + n, element, stability)
        event = resolve_decay(z, n, predicted)
        if event is None:
            return None

        parent = self._nuclide_label(z, z + n, element)
        if event["mode"] == "SF":
            self.protons, self.neutrons = fission_fragment(z, n)
        else:
            self.protons = max(0, z + event["dz"])
            self.neutrons = max(0, n + event["dn"])
        self.electrons = max(0, self.electrons + event["de"])
        self._structural()

        daughter_element = repository.find(self.protons)
        event["parent"] = parent
        event["daughter"] = self._nuclide_label(
            self.protons, self.protons + self.neutrons, daughter_element)
        return event

    @staticmethod
    def _nuclide_label(z, a, element):
        if z == 0:
            return "neutron libre" if a == 1 else f"{a} neutrons"
        name = element["name"] if element else f"Z={z}"
        return f"{name}-{a}"

    # ------------------------------------------------------------------
    # L'évaluation : c'est ici que les règles physiques sont appliquées.
    # ------------------------------------------------------------------
    def evaluate(self, repository, action_label=None, photons=None, event=None):
        z, n, e = self.protons, self.neutrons, self.electrons
        a = z + n
        charge = z - e
        element = repository.find(z)

        stability = self._stability(z, a, element)
        ratio = (n / z) if z else 0.0
        total_b, per_nucleon_b, terms = binding_energy(z, n)

        # Le défaut de masse est l'énergie de liaison vue comme une masse :
        # le noyau pèse moins que la somme de ses nucléons séparés.
        mass_defect = total_b / MEV_PER_U

        subshells = configuration(z, e)
        shells = shells_from_subshells(subshells)
        valence = shells[-1] if shells else 0
        octet = (valence == 2 and e <= 2) or valence == 8

        name = element["name"] if element else "Élément inconnu"
        isotope_label = f"{name}-{a}" if element and a else name
        decay = self._decay_mode(z, n, a, element, stability)
        measured_nuclide = lookup_nuclide(z, a) if stability == "UNSTABLE" else None

        evaluation = Evaluation(
            protons=z, neutrons=n, electrons=e,
            mass_number=a, net_charge=charge,
            stability=stability,
            ion_label=self._ion_label(charge),
            isotope_label=isotope_label,
            decay_mode=measured_nuclide["mode"] if measured_nuclide else decay,
            headline=self._headline(z, element, isotope_label, stability, charge),
            binding_total=total_b,
            binding_per_nucleon=per_nucleon_b,
            mass_defect_u=mass_defect,
            valence_electrons=valence,
            octet_satisfied=octet,
            ratio=ratio,
            shells=shells,
            nucleons=nucleon_layout_3d(z, n),
            configuration_text=format_configuration(subshells),
            shell_configurations=shell_configurations(subshells),
            one_electron=self.is_one_electron(),
            nuclear_radius_fm=nuclear_radius_fm(a),
            nuclear_terms=[
                {"key": key, "name": TERM_DESCRIPTIONS[key][0],
                 "description": TERM_DESCRIPTIONS[key][1], "mev": value}
                for key, value in terms.items()
            ] if a >= 2 else [],
            photons=list(photons or []),
            event=event,
            half_life=measured_nuclide["half_life"] if measured_nuclide else "",
            element=element,
        )

        self._fill_electronic_structure(evaluation, subshells)
        evaluation.calculations = self._calculations(evaluation, terms)
        evaluation.console_lines = self._console_lines(evaluation, action_label)
        return evaluation

    # ------------------------------------------------------------------
    # Structure électronique : sous-couches, niveaux, spectre, orbitale
    # ------------------------------------------------------------------
    def _fill_electronic_structure(self, ev, subshells):
        z, n = self.protons, self.neutrons
        if z == 0 or not subshells:
            ev.spectrum_source = "aucun électron" if not subshells else "aucun noyau"
            return

        mu = spectra.reduced_mass_factor(z, n, ev.binding_total)

        if ev.one_electron:
            self._fill_one_electron(ev, z, mu)
        else:
            self._fill_many_electrons(ev, z, subshells)

        ev.orbital = valence_orbital(z, subshells)

    def _fill_one_electron(self, ev, z, mu):
        """Système hydrogénoïde : tout est exact."""
        current = 1 + self.excitation
        # Rayon de Bohr corrigé de la masse réduite : a_μ = a0 / μ.
        radius = lambda level: level * level * BOHR_RADIUS_PM / (z * mu)

        ev.subshells = [{
            "n": 1, "l": 0, "electrons": 1 if current == 1 else 0, "capacity": 2,
            "radius_pm": radius(1), "z_eff": float(z),
            "energy_ev": spectra.hydrogenic_level_ev(z, 1, mu),
            "label": "1s", "occupation": [1 if current == 1 else 0],
        }]
        ev.levels = [{
            "n": level, "label": f"n={level}",
            "energy_ev": spectra.hydrogenic_level_ev(z, level, mu),
            "electrons": 1 if level == current else 0,
            "source": "exact (Bohr, masse réduite)",
            "measured_ev": None,
        } for level in range(1, HIGHEST_LEVEL + 1)]

        if current > 1:
            ev.excitation_level = current
            ev.excited_radius_pm = radius(current)

        ev.spectrum = spectra.hydrogenic_spectrum(z, mu, HIGHEST_LEVEL)
        ev.spectrum_source = "calcul exact (formule de Rydberg, masse réduite)"
        ev.ionization_model_ev = -spectra.hydrogenic_level_ev(z, 1, mu)
        if z == 1:
            ev.ionization_measured_ev = spectra.IONIZATION_EV[1]

    def _fill_many_electrons(self, ev, z, subshells):
        """Plusieurs électrons : Slater pour la structure, NIST pour la lumière."""
        details = []
        # Quand l'atome est excité, l'électron promu a quitté sa case : il ne
        # doit pas y être dessiné en plus de son orbite excitée.
        drawn = [list(entry) for entry in subshells]
        if self.excitation:
            drawn[-1][2] -= 1
        for (n_s, l_s, _), (_, _, occupancy) in zip(subshells, drawn):
            details.append({
                "n": n_s, "l": l_s, "electrons": occupancy,
                "capacity": 2 * (2 * l_s + 1),
                "radius_pm": orbital_radius_pm(z, subshells, n_s, l_s),
                "z_eff": effective_charge(z, subshells, n_s, l_s),
                # Énergie de liaison : ce que mesure la spectroscopie de
                # photoélectrons, réorganisation des autres électrons comprise.
                "energy_ev": -removal_energy_ev(z, subshells, n_s, l_s),
                "label": subshell_label(n_s, l_s),
                "occupation": hund_occupation(l_s, occupancy),
            })
        ev.subshells = details

        # Un niveau par groupe de Slater : 2s et 2p y partagent la même énergie.
        groups = {}
        for detail in details:
            key = group_key(detail["n"], detail["l"])
            groups.setdefault(key, []).append(detail)
        valence_key = group_key(subshells[-1][0], subshells[-1][1])
        neutral = self.electrons == self.protons
        measured = spectra.IONIZATION_EV.get(z) if neutral else None

        levels = []
        for key in sorted(groups):
            members = groups[key]
            electrons = sum(member["electrons"] for member in members)
            is_valence = key == valence_key
            if is_valence and self.excitation:
                electrons -= 1
            levels.append({
                "n": key[0],
                "label": " ".join(member["label"] for member in members),
                "energy_ev": members[0]["energy_ev"],
                "electrons": electrons,
                "source": "estimation Slater",
                "measured_ev": -measured if (is_valence and measured) else None,
            })

        n_v, l_v, _ = subshells[-1]
        if self.excitation:
            excited_config = self._excited_configuration(subshells)
            levels.append({
                "n": n_v + 1,
                "label": f"{n_v + 1}s (excité)",
                "energy_ev": -removal_energy_ev(z, excited_config, n_v + 1, 0),
                "electrons": 1,
                "source": "estimation Slater",
                "measured_ev": None,
            })
            ev.excitation_level = n_v + 1
            ev.excited_radius_pm = orbital_radius_pm(z, excited_config, n_v + 1, 0)
        ev.levels = levels

        ev.ionization_model_ev = removal_energy_ev(z, subshells, n_v, l_v)
        ev.ionization_measured_ev = measured

        if neutral and spectra.REFERENCE_LINES.get(z):
            ev.spectrum = spectra.reference_spectrum(z)
            ev.spectrum_source = "raies mesurées (NIST)"
        elif neutral:
            ev.spectrum_source = ("aucune raie de référence pour cet élément ; aucune formule "
                                  "ne prédit fidèlement le spectre d'un atome à plusieurs électrons")
        else:
            ev.spectrum_source = ("ion à plusieurs électrons : spectre non tabulé dans le simulateur")

    # ------------------------------------------------------------------
    @staticmethod
    def _stability(z, a, element):
        if z == 0:
            return "NOT_AN_ATOM"
        if element is None:
            return "UNKNOWN"
        return "STABLE" if a in element.get("stable_isotopes", []) else "UNSTABLE"

    @staticmethod
    def _ion_label(charge):
        if charge == 0:
            return "Neutre"
        if charge > 0:
            return f"Cation {charge}+"
        return f"Anion {abs(charge)}-"

    @staticmethod
    def _headline(z, element, isotope_label, stability, charge):
        if z == 0:
            return "Aucun proton, pas encore un atome"
        if element is None:
            return f"Z={z} hors de la table chargée"
        suffix = {"STABLE": "Stable", "UNSTABLE": "Instable (radioactif)"}.get(stability, "Indéterminé")
        if charge != 0:
            suffix += f" - ion {charge:+d}"
        return f"{isotope_label} - {suffix}"

    @staticmethod
    def _decay_mode(z, n, a, element, stability):
        """Prédit la voie de désintégration d'un noyau hors vallée de stabilité."""
        if z == 0:
            return "beta-" if n > 0 else ""
        if stability != "UNSTABLE" or element is None:
            return ""

        # Au-delà des « drip lines » (connues jusqu'au néon), le noyau n'est
        # pas lié : il perd un nucléon bien avant toute désintégration β.
        # Le modèle de Weizsäcker ne suffit pas à le prédire (11 cas sur 18),
        # d'où les données mesurées.
        if particle_bound(z, a) is False:
            isotopes = element.get("stable_isotopes", [])
            neutron_rich = bool(isotopes) and a > max(isotopes)
            return "n" if neutron_rich else "p"

        if z > 82:
            return "alpha"

        isotopes = element.get("stable_isotopes", [])
        if not isotopes:
            # Technétium, prométhium : aucun isotope stable n'existe.
            return "beta-" if n / z > target_ratio(z) else "beta+ / CE"
        if a > max(isotopes):
            return "beta-"        # Trop de neutrons : n donne p + electron + antineutrino
        if a < min(isotopes):
            return "beta+ / CE"   # Trop de protons : p donne n + positron + neutrino
        # Dans l'intervalle mais dans une lacune : on vise l'isotope le plus proche.
        nearest = min(isotopes, key=lambda stable: abs(stable - a))
        return "beta-" if a > nearest else "beta+ / CE"

    # ------------------------------------------------------------------
    # La trace des calculs : la preuve de chaque verdict.
    # ------------------------------------------------------------------
    def _calculations(self, ev, terms):
        z, n, e, a = ev.protons, ev.neutrons, ev.electrons, ev.mass_number
        steps = []

        if ev.element:
            identity, severity = f"{ev.element['name']} ({ev.element['symbol']})", "ok"
        elif z == 0:
            identity, severity = "aucun élément", "warn"
        else:
            identity, severity = f"Z={z} hors table", "warn"
        steps.append(_step("Identité chimique", "Z = nombre de protons", f"Z = {z}", identity,
                           "Le nombre de protons définit strictement l'élément.", severity))

        steps.append(_step("Nombre de masse", "A = Z + N", f"A = {z} + {n}", f"{a} nucléons",
                           "Les électrons, 1836 fois plus légers, ne comptent pas.", "info"))

        steps.append(_step("Charge nette", "q = (Z x +e) + (E x -e)", f"q = ({z} x +e) + ({e} x -e)",
                           f"{ev.net_charge:+d} e   ({ev.ion_label})",
                           "Un atome neutre satisfait Z = E.",
                           "ok" if ev.net_charge == 0 else "warn"))

        if a >= 1:
            steps.append(_step("Rayon du noyau", "R = r0 · A^(1/3),  r0 = 1,2 fm",
                               f"R = 1,2 x {a}^(1/3)", f"{ev.nuclear_radius_fm:.2f} fm",
                               "Volume proportionnel au nombre de nucléons : le noyau est "
                               "une goutte de matière incompressible.", "info"))

        if z:
            target = target_ratio(z)
            deviation = abs(ev.ratio - target)
            steps.append(_step("Ratio neutrons / protons", "N / Z", f"{n} / {z}",
                               f"{ev.ratio:.2f}   (cible {target:.2f})",
                               "La répulsion entre protons exige des neutrons en renfort.",
                               # Un noyau stable reste stable, quel que soit l'écart à la
                               # règle empirique (H-1, He-3...).
                               "ok" if ev.stability == "STABLE" or deviation <= 0.15
                               else ("warn" if deviation <= 0.4 else "danger")))

        if ev.element:
            isotopes = ev.element.get("stable_isotopes", [])
            listing = ", ".join(str(value) for value in isotopes) or "aucun"
            verdict = {"STABLE": "STABLE", "UNSTABLE": "INSTABLE"}.get(ev.stability, "INDETERMINE")
            steps.append(_step("Test de stabilité", "A parmi les isotopes stables de Z ?",
                               f"{a} dans [{listing}]",
                               verdict + (f"  ->  {ev.decay_mode}" if ev.decay_mode else ""),
                               f"Demi-vie mesurée : {ev.half_life}." if ev.half_life else
                               "Isotopes stables observés dans la nature.",
                               "ok" if ev.stability == "STABLE" else "danger"))

        if e and ev.subshells:
            valence = ev.subshells[-1]
            steps.append(_step("Configuration électronique", "ordre de Klechkowski (n + l croissant)",
                               ev.configuration_text, str(ev.shells),
                               "La 4s se remplit avant la 3d : c'est l'énergie qui commande, "
                               "pas le numéro de couche.", "info"))
            if not ev.one_electron:
                steps.append(_step("Charge effective (Slater)", "Z_eff = Z - S",
                                   f"Z_eff({valence['label']}) = {z} - {z - valence['z_eff']:.2f}",
                                   f"{valence['z_eff']:.2f}",
                                   "Les électrons internes masquent le noyau à l'électron de valence.",
                                   "info"))

        if e:
            missing = 8 - ev.valence_electrons
            if ev.octet_satisfied:
                verdict, severity = "octet satisfait", "ok"
            elif ev.valence_electrons > 8:
                verdict, severity = f"{ev.valence_electrons} e- externes, hors règle de l'octet", "info"
            elif ev.valence_electrons <= 3:
                verdict, severity = f"tend à céder {ev.valence_electrons} e-", "warn"
            else:
                verdict, severity = f"tend à capter {missing} e-", "warn"
            steps.append(_step("Couche de valence", "valence = électrons de la dernière couche",
                               f"valence = {ev.valence_electrons}", verdict,
                               "Règle de l'octet : 8 électrons externes (2 pour la couche K).",
                               severity))

        if ev.ionization_model_ev:
            if ev.ionization_measured_ev:
                gap = (ev.ionization_model_ev - ev.ionization_measured_ev) / ev.ionization_measured_ev
                result = (f"modèle {ev.ionization_model_ev:.2f} eV  /  mesure "
                          f"{ev.ionization_measured_ev:.2f} eV  (écart {gap * 100:+.0f} %)")
            else:
                result = f"{ev.ionization_model_ev:.2f} eV (non mesuré ici)"
            formula = ("E_n = -13,6 Z² (μ/m_e) / n²" if ev.one_electron
                       else "E_i = E(ion) - E(atome),  E = Σ -13,6 (Z_eff / n*)²")
            steps.append(_step("Énergie d'ionisation", formula, "énergie pour arracher l'électron de valence",
                               result, "Exact pour un électron seul ; approché au-delà.",
                               "ok" if ev.one_electron else "info"))

        if a >= 2:
            light = a < 16
            steps.append(_step(
                "Énergie de liaison (Weizsäcker)",
                "B = aV.A - aS.A^(2/3) - aC.Z(Z-1)/A^(1/3) - aA.(A-2Z)^2/A + delta",
                f"{terms['volume']:.1f} {terms['surface']:+.1f} {terms['coulomb']:+.1f} "
                f"{terms['asymmetry']:+.1f} {terms['pairing']:+.1f}",
                f"{ev.binding_total:.2f} MeV   ({ev.binding_per_nucleon:.2f} MeV/nucléon)",
                ("Hors domaine de validité sous A = 16 : indicatif seulement. " if light else "")
                + "Maximum vers le fer-56 : au-delà, la fusion ne libère plus d'énergie.",
                "warn" if light else "info"))
            steps.append(_step("Défaut de masse", "dm = B / c²   (1 u = 931,494 MeV/c²)",
                               f"dm = {ev.binding_total:.2f} / 931,494", f"{ev.mass_defect_u:.4f} u",
                               "La masse manquante est devenue l'énergie qui lie le noyau.", "info"))

        for photon in ev.photons:
            steps.append(_step("Photon émis", "E = h c / λ",
                               f"λ = {photon['wavelength_nm']:.2f} nm",
                               f"{photon['energy_ev']:.3f} eV  ({spectra.region(photon['wavelength_nm'])})",
                               photon["source"], "info"))

        if ev.event:
            q = ev.event["q_mev"]
            steps.append(_step("Désintégration", ev.event["rule"],
                               f"{ev.event['parent']} -> {ev.event['daughter']}",
                               f"Q = {q:+.3f} MeV", ev.event["q_source"],
                               "danger" if ev.event.get("model_fails") else "info"))

        return steps

    # ------------------------------------------------------------------
    # Le flux de la console : la narration de ce qui vient de se passer.
    # ------------------------------------------------------------------
    def _console_lines(self, ev, action_label):
        z, n, e, a = ev.protons, ev.neutrons, ev.electrons, ev.mass_number
        lines = []
        if action_label:
            lines.append(action_label)

        if ev.event:
            event = ev.event
            lines.append(f"DÉSINTÉGRATION {event['mode']} : {event['parent']} -> {event['daughter']}.")
            lines.append(f"Règle : {event['rule']}.")
            lines.append(f"Émis : {', '.join(event['emitted'])}.")
            lines.append(f"Énergie libérée Q = {event['q_mev']:+.3f} MeV ({event['q_source']}).")
            if event.get("half_life"):
                lines.append(f"Demi-vie mesurée du noyau père : {event['half_life']}.")
            if event.get("model_fails"):
                lines.append("ALERTE : le modèle de Weizsäcker donne Q négatif alors que le noyau "
                             "se désintègre bien ; il est trop imprécis pour ce noyau.")

        for photon in ev.photons:
            lines.append(f"Photon émis : λ = {photon['wavelength_nm']:.2f} nm, "
                         f"E = {photon['energy_ev']:.3f} eV ({spectra.region(photon['wavelength_nm'])}) "
                         f"- {photon['label']} - {photon['source']}.")

        if z == 0:
            lines.append("Aucun proton : l'objet n'est pas encore un élément chimique.")
            if n:
                lines.append(f"{n} neutron(s) libre(s). Un neutron isolé se désintègre en "
                             "10,2 minutes (demi-vie), par β-.")
            return lines

        if ev.element:
            lines.append(f"Z = {z} -> {ev.element['name']} ({ev.element['symbol']}), "
                         f"{ev.element['category'].lower()}.")
        lines.append(f"A = Z + N = {z} + {n} = {a} nucléons.")
        lines.append(f"q = ({z} x +e) + ({e} x -e) = {ev.net_charge:+d} [{ev.ion_label}].")

        if e:
            lines.append(f"Configuration : {ev.configuration_text}  ->  couches {ev.shells}.")
        if ev.excitation_level:
            lines.append(f"État excité : l'électron occupe le niveau n = {ev.excitation_level}.")

        # L'écart à la vallée n'explique quelque chose que si le noyau est
        # réellement instable : l'hydrogène-1 (N/Z = 0) ou l'hélium-3 sont
        # parfaitement stables, un proton seul n'a aucune répulsion à compenser.
        target = target_ratio(z)
        if ev.stability == "UNSTABLE" and abs(ev.ratio - target) > 0.4:
            lines.append(f"ALERTE : ratio N/Z = {ev.ratio:.2f} contre {target:.2f} attendu. "
                         "Le noyau est hors de la vallée de stabilité.")

        if ev.stability == "STABLE":
            lines.append(f"STABLE : A={a} figure parmi les isotopes stables de l'élément.")
        elif ev.stability == "UNSTABLE":
            half_life = f" Demi-vie mesurée : {ev.half_life}." if ev.half_life else ""
            lines.append(f"INSTABLE : désintégration attendue {ev.decay_mode}.{half_life}")

        if a >= 2:
            lines.append(f"Défaut de masse {ev.mass_defect_u:.4f} u converti en "
                         f"{ev.binding_total:.2f} MeV d'énergie de liaison "
                         f"({ev.binding_per_nucleon:.2f} MeV/nucléon).")
        return lines


def _step(label, formula, substitution, result, note, severity):
    return {"label": label, "formula": formula, "substitution": substitution,
            "result": result, "note": note, "severity": severity}
