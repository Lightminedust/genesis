"""Le simulateur : une horloge, des règles, et ce qui en découle.

Rien n'est scénarisé. À chaque pas de temps, la session applique les règles
de la physique et produit des ÉVÉNEMENTS, chacun accompagné du calcul qui le
justifie :

  - un électron excité redescend au bout d'un temps aléatoire, tiré selon sa
    durée de vie réelle (coefficients d'Einstein pour un système à un
    électron, durées mesurées sinon), en suivant l'un des chemins permis ;
  - un noyau instable se désintègre au bout d'un temps aléatoire, tiré selon
    sa demi-vie ; le noyau fils continue sa propre vie (chaînes radioactives) ;
  - un photon interagit selon la section efficace et le profil naturel de
    raie, avec un faisceau gaussien ; le continuum hydrogénoïde 1s est
    calculé, les autres continuums sont explicitement non modélisés ;
  - un électron ajouté n'est retenu que si l'ion qui en résulte existe.

Le temps de la simulation défile à une vitesse réglable : une nanoseconde
par seconde pour voir la lumière, mille ans par seconde pour voir le
carbone 14 se désintégrer.
"""

import math
import random

from atomic import atomic_data
from atomic import hydrogen
from atomic import light_interaction as light
from nuclear import nuclear_data
from nuclear import neutrons
from atomic import spectra
from atomic.atom import AtomicState
from atomic.electron_config import configuration
from nuclear.halflife import UNBOUND_HALF_LIFE_S, half_life
from nuclear.nucleus import _DECAYS, fission_fragment, resolve_decay
from atomic.orbitals import hydrogenic_orbital
from atomic.periodic_table import MAX_Z
from atomic.slater import removal_energy_ev
from common.units import format_duration

YEAR_S = 365.25 * 86400.0

# Vitesses du temps simulé, en secondes simulées par seconde réelle.
SPEEDS = [
    (1e-9, "1 nanoseconde / s"),
    (1e-6, "1 microseconde / s"),
    (1e-3, "1 milliseconde / s"),
    (1.0, "temps réel"),
    (3600.0, "1 heure / s"),
    (86400.0, "1 jour / s"),
    (YEAR_S, "1 an / s"),
    (1e3 * YEAR_S, "1 000 ans / s"),
    (1e6 * YEAR_S, "1 million d'années / s"),
    (1e9 * YEAR_S, "1 milliard d'années / s"),
]

# Trajet visible du photon jusqu'à l'atome, en secondes RÉELLES : la lumière
# traverse un atome en 10⁻¹⁸ s, on la ralentit pour qu'on la voie arriver.
PHOTON_FLIGHT_REAL_S = 2.0

# Garde-fou : nombre d'événements traités par pas (chaînes très rapides).
MAX_EVENTS_PER_STEP = 16


def _number(value, digits):
    """Un nombre à la française (virgule décimale), avec `digits` chiffres significatifs."""
    return f"{value:.{digits}g}".replace(".", ",")


def _event(kind, time_s, title, formula="", detail="", **extra):
    event = {"kind": kind, "time_s": time_s, "title": title, "formula": formula, "detail": detail}
    event.update(extra)
    return event


class Session:
    """Un atome qui vit : son état, son horloge, son hasard."""

    def __init__(self, repository, seed=None):
        self.repo = repository
        self.atom = AtomicState()
        self.rng = random.Random(seed)
        self.time_s = 0.0
        self.speed_index = 0
        self.paused = False
        # Mode analyse : le temps s'arrête après chaque événement, pour qu'on
        # ait le temps de regarder ce qui s'est passé et pourquoi.
        self.step_mode = True
        self.real_clock = 0.0
        # Système à un électron : son état (n, l). Plusieurs électrons : état excité éventuel.
        self.electron_state = None
        self.electron_spin = "+1/2"   # m_s de l'électron unique : ne change pas lors d'un saut
        self.excited = None
        self.photons_in_flight = []
        # État isomère du noyau : "" (fondamental) ou "m" (Pa-234m...).
        self.isomer = ""
        # Échelle de la matière (dynamique moléculaire), quand on dézoome au-delà de l'atome.
        self.matter = None
        # Échelle de la molécule : l'atome et un partenaire, en chimie quantique.
        self.molecule = None
        # Laboratoire de fusion 3D (écran à part).
        self.lab = None
        # Cœur d'étoile (écran à part).
        self.star = None
        # Réacteur à fusion (tokamak, écran à part).
        self.reactor = None
        # La réaction en chaîne : masse critique, k_eff, cascade de neutrons (écran à part).
        self.chain = None
        # Le laboratoire moléculaire : des molécules entières, leurs électrons (GFN1-xTB) et leurs forces.
        self.molecule_lab = None
        # Laboratoire few-body indépendant de l'atome construit à l'accueil.
        self.subatomic = None
        # Le nucléon de l'intérieur : trois quarks dans une corde de couleur.
        self.nucleon = None
        self.nucleon_strike = dict(struck=0, momentum=0.0, direction=(1.0, 0.0, 0.0), seed=1)
        # La mer de partons : la même résolution se lit sur le nucléon ouvert.
        self.partons = None
        self.partons_view = dict(q2=10.0, x_min=1e-3, seed=1, run=0)
        # La collision électron–nucléon et l'atelier de hadrons.
        self.collision = None
        self.hadron_lab = None

    # ------------------------------------------------------------------
    # Lecture de l'état
    # ------------------------------------------------------------------
    @property
    def speed(self):
        return SPEEDS[self.speed_index][0]

    @property
    def speed_label(self):
        return SPEEDS[self.speed_index][1]

    def one_electron(self):
        return self.atom.electrons == 1 and self.atom.protons >= 1

    def _mu(self):
        from nuclear.nucleus import binding_energy
        z, n = self.atom.protons, self.atom.neutrons
        return spectra.reduced_mass_factor(z, n, binding_energy(z, n)[0])

    def _level_ev(self, n):
        return spectra.hydrogenic_level_ev(self.atom.protons, n, self._mu())

    def state_label(self):
        if self.one_electron():
            return hydrogen.label(*self.electron_state)
        if self.excited:
            return "excité"
        return "fondamental" if self.atom.electrons else ""

    def electron_rate(self):
        """Probabilité par seconde que l'électron excité redescende."""
        if self.one_electron() and self.electron_state != (1, 0):
            return sum(rate for _, rate, _ in hydrogen.decay_channels(self.atom.protons, self.electron_state))
        if self.excited:
            return 1.0 / self.excited["lifetime_s"]
        return 0.0

    def nuclear_decay(self):
        """Comment le noyau peut-il se désintégrer ? None s'il est stable.

        Retourne {half_life_s, source, channels: [(mode, part, (Z, A, état))], rate}.
        Noyau observé : données mesurées, toutes voies et branchements compris.
        Jamais observé : voie prédite par la vallée de stabilité, demi-vie estimée.
        """
        z, n = self.atom.protons, self.atom.neutrons
        a = z + n
        if a == 0:
            return None
        if z == 0 and n > 1:
            # Plusieurs neutrons sans proton : rien ne les lie.
            data = {"half_life_s": UNBOUND_HALF_LIFE_S, "source": "non lié : ~10⁻²¹ s",
                    "channels": [("n", 1.0, (0, a - 1, ""))]}
        else:
            data = nuclear_data.info(z, a, self.isomer)
            if data is None:
                data = self._estimated_decay(z, n)
            if data is None or data["stable"] or not data["half_life_s"] or not data["channels"]:
                return None
            data = dict(data)
        channels = data["channels"]
        if self.atom.electrons == 0:
            # La capture électronique avale un électron du cortège : un noyau
            # nu ne peut pas la faire (le Be-7 totalement ionisé est stable).
            channels = [channel for channel in channels if channel[0] != "CE"]
        share = sum(fraction for _, fraction, _ in channels)
        if share <= 0 or not math.isfinite(data["half_life_s"]):
            return None
        data["channels"] = channels
        data["rate"] = math.log(2) / data["half_life_s"] * share
        return data

    def _estimated_decay(self, z, n):
        element = self.repo.find(z)
        stability = AtomicState._stability(z, z + n, element)
        if stability != "UNSTABLE":
            return None
        resolved = resolve_decay(z, n, AtomicState._decay_mode(z, n, z + n, element, stability))
        if resolved is None:
            return None
        mode = resolved["mode"]
        seconds, source = half_life(z, n, mode)
        daughter = (z + resolved["dz"], z + n + resolved["dz"] + resolved["dn"], "")
        return {"stable": False, "half_life_s": seconds, "source": source + " · noyau jamais observé",
                "channels": [(mode, 1.0, daughter)]}

    def decay_chain(self, limit=40):
        """La chaîne la plus probable, du noyau actuel jusqu'au noyau stable.

        À chaque pas on suit la voie dominante (part mesurée). Le premier pas
        utilise exactement ce que le moteur applique (capture électronique
        impossible sans électron, noyaux jamais observés estimés) ; les
        suivants, les données des noyaux (ICRP-107, NUBASE2020). La chaîne
        s'arrête au noyau stable, à une fission, ou à un noyau inconnu.
        Retourne [{z, a, state, half_life_s, mode, fraction, stable, estimated}].
        """
        z, a = self.atom.protons, self.atom.protons + self.atom.neutrons
        if a == 0:
            return []
        chain = []
        state = self.isomer
        decay = self.nuclear_decay()
        for step in range(limit):
            if step > 0:
                data = nuclear_data.info(z, a, state)
                if data is None:
                    chain.append({"z": z, "a": a, "state": state, "half_life_s": 0.0, "mode": "",
                                  "fraction": 0.0, "stable": False, "estimated": True, "unknown": True})
                    break
                decay = None if data["stable"] or not data["half_life_s"] or not data["channels"] else data
            if decay is None:
                chain.append({"z": z, "a": a, "state": state, "half_life_s": 0.0, "mode": "",
                              "fraction": 1.0, "stable": True, "estimated": False})
                break
            channels = [c for c in decay["channels"] if c[1] > 0] or decay["channels"]
            mode, fraction, daughter = max(channels, key=lambda c: c[1])
            total = sum(c[1] for c in decay["channels"]) or 1.0
            chain.append({"z": z, "a": a, "state": state, "half_life_s": float(decay["half_life_s"]),
                          "mode": mode, "fraction": fraction / total, "stable": False,
                          "estimated": "estim" in decay.get("source", "") or "jamais" in decay.get("source", "")})
            if daughter is None:
                break      # fission : plus de chaîne unique
            z, a, state = daughter
            if a <= 0:
                break
        return chain

    def nuclear_rate(self):
        decay = self.nuclear_decay()
        return decay["rate"] if decay else 0.0

    def nuclide_label(self):
        z, a = self.atom.protons, self.atom.protons + self.atom.neutrons
        return nuclear_data.format_name(z, a, self.isomer) if a else ""

    def absorbable_lines(self):
        """Longueurs d'onde (nm) qu'un photon peut avoir pour être absorbé maintenant."""
        z = self.atom.protons
        if self.one_electron():
            lines = []
            n, l = self.electron_state
            for target in hydrogen.absorption_targets(z, self.electron_state):
                if target[0] > n:
                    line = spectra.hydrogenic_transition(z, target[0], n, self._mu())["wavelength_nm"]
                    if line not in lines:
                        lines.append(line)
            return lines
        if self.atom.electrons == z and z > 0 and not self.excited:
            return list(atomic_data.resonance_lines(z))
        return []

    # ------------------------------------------------------------------
    # Actions de l'utilisateur
    # ------------------------------------------------------------------
    def set_speed(self, index):
        self.speed_index = max(0, min(len(SPEEDS) - 1, index))
        return [_event("info", self.time_s, f"Vitesse du temps : {self.speed_label}")]

    # ------------------------------------------------------------------
    # Échelle de la matière
    # ------------------------------------------------------------------
    def start_matter(self):
        from molecular.matter import Matter
        self._close_chain()
        self.subatomic = self.nucleon = None
        self.molecule = None
        z, a = self.atom.protons, self.atom.protons + self.atom.neutrons
        mass = nuclear_data.atomic_mass_u(z, a) if z else None
        self.matter = Matter(z if z else 18, mass, rng_seed=self.rng.randrange(1 << 30))
        m = self.matter
        substitute = "" if m.z == z else (" (pas de paramètres pour cet élément : on montre l'argon)" if z else "")
        return [_event("structure", self.time_s, f"Échelle de la matière : {m.n} atomes de {m.name.lower()}{substitute}",
                       f"V(r) = 4ε[(σ/r)¹² − (σ/r)⁶] · ε/k = {m.epsilon_k:.1f} K · σ = {m.sigma_a:.2f} Å",
                       f"Paramètres : {m.source}. Chaque atome n'est plus qu'une sphère ; seule compte la force "
                       f"entre voisins. Solide, liquide ou gaz : ce sera mesuré, pas décidé.")]

    def start_fusion(self):
        from fusionlab.fusion import FusionLab
        self._close_chain()
        self.subatomic = self.nucleon = None
        self.lab = FusionLab(rng_seed=self.rng.randrange(1 << 30))
        self.star = None
        self.matter = None
        self.molecule = None
        return [_event("structure", 0.0, "Laboratoire de fusion",
                       "glisse deux atomes, règle l'énergie, lance",
                       "Répulsion électrique, effet tunnel, force forte et E = Δm·c² décident de l'issue.")]

    def stop_fusion(self):
        self.lab = None
        return [_event("structure", self.time_s, "Retour à l'atome")]

    def set_nucleus(self, which, code):
        if self.lab is not None:
            self.lab.set_nucleus(which, code)
        return []

    def set_collision_energy(self, kev):
        if self.lab is not None:
            self.lab.set_energy(kev)
        return []

    def set_thermal(self, on):
        if self.lab is not None:
            self.lab.set_thermal(on)
        return []

    def set_impact(self, fm):
        if self.lab is not None:
            self.lab.set_impact(fm)
        return []

    def collide(self, until_fusion):
        if self.lab is None:
            return []
        return [_event(e["kind"], self.time_s, e["title"], e["formula"], e["detail"])
                for e in self.lab.fire(bool(until_fusion))]

    def start_reactor(self):
        from fusionlab.fusion_dynamics import FusionExperiment
        self._close_chain()
        self.subatomic = self.nucleon = None
        self.reactor = FusionExperiment(rng_seed=self.rng.randrange(1 << 30))
        self.lab = None
        self.star = None
        self.matter = None
        self.molecule = None
        return [_event("structure", 0.0, "Laboratoire de collisions nucléaires",
                       "Choisir deux éléments et leurs isotopes, l'énergie du faisceau et sa visée.",
                       "Noyaux entièrement ionisés : trajectoires intégrées et canaux de réaction mesurés ou modélisés, avec leurs limites.")]

    def stop_reactor(self):
        self.reactor = None
        return [_event("structure", self.time_s, "Retour à l'atome")]

    # ------------------------------------------------------------------
    # La réaction en chaîne
    # ------------------------------------------------------------------
    def start_chain(self):
        from chainlab.lab import ChainLab
        self._close_chain()
        self.reactor = self.lab = self.star = self.matter = self.molecule = None
        self.subatomic = self.nucleon = None
        self.chain = ChainLab(rng_seed=self.rng.randrange(1 << 30))
        return [_event("structure", self.time_s, "Laboratoire de la réaction en chaîne",
                       "k_eff = neutrons d'une génération / neutrons de la précédente",
                       "Une sphère de matière fissile et son réflecteur : le transport des neutrons (ENDF/B-VIII.0) "
                       "dit si la chaîne s'éteint, se maintient ou diverge.")]

    def stop_chain(self):
        self._close_chain()
        return [_event("structure", self.time_s, "Retour à l'atome")]

    def _close_chain(self):
        """Arrête les laboratoires qui calculent dans leur propre fil (réaction en chaîne, molécules)."""
        if self.chain is not None:
            self.chain.close()
            self.chain = None
        if self.molecule_lab is not None:
            self.molecule_lab.close()
            self.molecule_lab = None

    # ------------------------------------------------------------------
    # Le laboratoire moléculaire
    # ------------------------------------------------------------------
    def start_molecule_lab(self):
        from moleculelab.lab import MoleculeLab
        self._close_chain()
        self.reactor = self.lab = self.star = self.matter = self.molecule = None
        self.subatomic = self.nucleon = None
        self.molecule_lab = MoleculeLab(rng_seed=self.rng.randrange(1 << 30))
        return [_event("structure", self.time_s, "Laboratoire moléculaire",
                       "F = −∇E, E calculée par GFN1-xTB",
                       "Des atomes, leurs électrons de valence et les forces qui les lient : la géométrie, les "
                       "liaisons et les réactions sortent du calcul.")]

    def stop_molecule_lab(self):
        self._close_chain()
        return [_event("structure", self.time_s, "Retour à l'atome")]

    def start_subatomic(self):
        from quantumlab.subatomic import SubatomicExperiment
        self._close_chain()
        self.nucleon = None
        self.subatomic = SubatomicExperiment()
        self.reactor = self.lab = self.star = self.matter = self.molecule = None
        return [_event("structure", self.time_s, "Laboratoire subatomique",
                       "p + n : F = -dV_eff/dr ; intégration velocity-Verlet",
                       "La paire liée oscille sans dissipation. Les électrons sont des densités orbitales à une autre échelle.")]

    # ------------------------------------------------------------------
    # Le nucléon de l'intérieur
    # ------------------------------------------------------------------
    def start_nucleon(self):
        from quantumlab.nucleon import Nucleon
        self._close_chain()
        self.subatomic = None
        self.nucleon = Nucleon('proton')
        self.partons = None
        self.collision = None
        self.hadron_lab = None
        self.nucleon_strike = dict(struck=0, momentum=0.0, direction=(1.0, 0.0, 0.0), seed=1)
        self.reactor = self.lab = self.star = self.matter = self.molecule = None
        self.nucleon.calculate(**self._strike_arguments())
        return [_event("structure", self.time_s, "Le nucléon de l'intérieur",
                       "V = σ·L_Y − (2/3)α_s ħc Σ 1/r · σ = 0,18 GeV²",
                       "Trois quarks de valence dans une corde de couleur en Y. Frappe un quark : la corde s'étire, se rompt, "
                       "et le champ de couleur crée une paire quark–antiquark — un baryon et un méson en sortent.")]

    def _strike_arguments(self):
        s = self.nucleon_strike
        return dict(struck=s['struck'], momentum_mev=s['momentum'], direction=s['direction'], seed=s['seed'])

    def stop_nucleon(self):
        self.nucleon = None
        self.partons = None
        self.collision = None
        self.hadron_lab = None
        return [_event("structure", self.time_s, "Retour à l'atome")]

    def set_nucleon_hadron(self, which):
        if self.nucleon is None:
            return []
        self.collision = None
        from quantumlab.nucleon import Nucleon
        self.nucleon = Nucleon('neutron' if which else 'proton')
        self.nucleon.calculate(**self._strike_arguments())
        if self.partons is not None:
            return self._compute_partons()
        return []

    def strike_nucleon(self, struck, momentum_mev, direction):
        if self.nucleon is None:
            return []
        self.collision = None
        previous = dict(self.nucleon_strike)
        self.nucleon_strike.update(struck=int(struck), momentum=float(momentum_mev),
                                   direction=tuple(direction) if len(direction) == 3 else (1.0, 0.0, 0.0),
                                   seed=self.rng.randrange(1, 1 << 30))
        try:
            self.nucleon.calculate(**self._strike_arguments())
        except ValueError as error:
            self.nucleon_strike = previous
            return [_event("info", self.time_s, "Coup refusé", detail=str(error))]
        return []

    def start_partons(self, q2=0.0, x_min=0.0):
        """Ouvre la mer de partons du nucléon courant ; sans Q² donné, on prend celui du dernier coup."""
        if self.nucleon is None:
            return []
        struck = (self.nucleon.q or {}).get('q2', 0.0) / 1e6 if hasattr(self.nucleon, 'q') else 0.0
        self.partons_view.update(q2=q2 if q2 > 0 else (struck if struck >= 0.5 else 10.0),
                                 x_min=x_min if x_min > 0 else 1e-3, seed=self.rng.randrange(1, 1 << 30))
        return self._compute_partons()

    def set_partons(self, q2, x_min):
        if self.nucleon is None:
            return []
        previous = dict(self.partons_view)
        self.partons_view.update(q2=float(q2), x_min=float(x_min), seed=self.rng.randrange(1, 1 << 30))
        events = self._compute_partons()
        if events:                       # résolution refusée : on garde la précédente
            self.partons_view = previous
        return events

    # ------------------------------------------------------------------
    # La collision e⁻ + nucléon
    # ------------------------------------------------------------------
    def run_collision(self, target, energy_mev, angle_deg, projectile=0):
        """Calcule les cas possibles à (E, θ) pour ce projectile, tire un événement et construit son film."""
        if self.nucleon is None:
            self.start_nucleon()
        from quantumlab import hadronic
        from quantumlab import scattering
        code = scattering.PROJECTILE_CODES[int(projectile)] if 0 <= int(projectile) < len(scattering.PROJECTILE_CODES) else 'e-'
        nucleon = 'neutron' if target else 'proton'
        try:
            if code in hadronic.HADRON_BEAMS:
                col = hadronic.HadronCollision(float(energy_mev), nucleon, code)
            elif float(angle_deg) <= 0:
                # l'angle tiré par la physique : selon dσ/dΩ sur tous les angles, pas choisi au curseur
                angle, median, beyond = scattering.sample_angle(float(energy_mev), nucleon, code, self.rng.random())
                col = scattering.Collision(float(energy_mev), angle, nucleon, code)
                col.angle_sampled, col.angle_median, col.angle_beyond_30 = True, median, beyond
            else:
                col = scattering.Collision(float(energy_mev), float(angle_deg), nucleon, code)
        except ValueError as error:
            return [_event("info", self.time_s, "Collision refusée", detail=str(error))]
        seed = self.rng.randrange(1, 1 << 30)
        self._install_collision(col, self._build_collision(col, seed), seed)
        return []

    @staticmethod
    def _build_collision(col, seed, force=None):
        from quantumlab import collision_events
        from quantumlab import hadron_events
        builder = hadron_events.build if col.kind == 'had' else collision_events.build
        return builder(col, seed) if force is None else builder(col, seed, force)

    def resample_collision(self):
        if self.collision is None:
            return []
        from quantumlab import collision_events
        col = self.collision['col']
        seed = self.rng.randrange(1, 1 << 30)
        self._install_collision(col, self._build_collision(col, seed), seed)
        return []

    def force_collision(self, index):
        """Un événement du cas choisi sur le spectre (élastique, Δ, résonances, profond), à la même énergie et au même angle."""
        if self.collision is None:
            return []
        from quantumlab import collision_events
        col = self.collision['col']
        if not 0 <= int(index) < len(col.channels) or col.channels[int(index)]['probability'] <= 0:
            return [_event("info", self.time_s, "Cas impossible ici",
                           detail="Ce cas a une probabilité nulle à cette énergie et cet angle : change le faisceau ou l'angle.")]
        seed = self.rng.randrange(1, 1 << 30)
        self._install_collision(col, self._build_collision(col, seed, int(index)), seed)
        return []

    def _install_collision(self, col, result, seed):
        run = (self.collision or {}).get('run', 0) + 1
        self.collision = dict(col=col, result=result, seed=seed, run=run)
        self.nucleon = result['nucleon']
        self.nucleon_strike = result['strike']
        self.partons = None

    def reset_partons(self):
        if self.partons is None:
            return []
        self.partons_view['seed'] = self.rng.randrange(1, 1 << 30)
        return self._compute_partons()

    # ------------------------------------------------------------------
    # L'atelier de hadrons
    # ------------------------------------------------------------------
    _SLOT_CODES = {0: 'u', 1: 'd', 2: 's', 3: 'c', 4: 'b', 5: 't', 10: 'e', 11: 'μ', 12: 'τ', 13: 'νe', 14: 'νμ', 15: 'ντ',
                   20: 'g', 21: 'γ', 22: 'Z', 23: 'W', 24: 'H'}

    def test_hadron(self, spin_index, triples):
        """Analyse l'assemblage posé : `triples` = suite (code de particule, antiparticule ?, couleur)."""
        if self.nucleon is None:
            self.start_nucleon()
        from quantumlab import hadron_lab
        slots = []
        for i in range(0, len(triples) - 2, 3):
            code = int(triples[i])
            slots.append((self._SLOT_CODES.get(code, '?'), bool(int(triples[i + 1])), int(triples[i + 2]) % 3))
        seed = self.rng.randrange(1, 1 << 30)
        result = hadron_lab.full_analysis(slots, max(0, int(spin_index)), seed)
        run = (self.hadron_lab or {}).get('run', 0) + 1
        self.hadron_lab = dict(slots=slots, spin=int(spin_index), seed=seed, run=run, result=result)
        return []

    def redecay_hadron(self):
        if self.hadron_lab is None:
            return []
        from quantumlab import hadron_lab
        h = self.hadron_lab
        h['seed'] = self.rng.randrange(1, 1 << 30)
        h['result'] = hadron_lab.full_analysis(h['slots'], h['spin'], h['seed'])
        h['run'] += 1
        return []

    def clear_hadron(self):
        self.hadron_lab = None
        return []

    def stop_partons(self):
        self.partons = None
        return []

    def _compute_partons(self):
        from quantumlab.parton_cloud import Cloud
        view = self.partons_view
        try:
            cloud = Cloud(self.nucleon.hadron, view['q2'], view['x_min'])
        except ValueError as error:
            return [_event("info", self.time_s, "Résolution refusée", detail=str(error))]
        cloud.sample(view['seed'])
        view['run'] += 1
        self.partons = cloud
        return []

    def reset_nucleon(self):
        if self.nucleon is None:
            return []
        self.collision = None
        self.nucleon_strike['seed'] = self.rng.randrange(1, 1 << 30)
        self.nucleon.calculate(**self._strike_arguments())
        return []

    def stop_subatomic(self):
        self.subatomic = None
        return [_event("structure", self.time_s, "Retour à l'atome")]

    def set_subatomic_counts(self, kind, value):
        if self.subatomic is None:
            return []
        try:
            self.subatomic.set_counts(**{kind: value})
        except ValueError as error:
            return [_event("info", self.time_s, "Configuration refusée", detail=str(error))]
        return []

    def set_subatomic_impulse(self, energy):
        if self.subatomic is None:
            return []
        try:
            self.subatomic.set_impulse(energy)
        except ValueError as error:
            return [_event("info", self.time_s, "Énergie refusée", detail=str(error))]
        return []

    def kick_subatomic(self, frame_index, particle_index, vector):
        if self.subatomic is None:
            return []
        try:
            self.subatomic.perturb(frame_index, particle_index, vector)
        except ValueError as error:
            return [_event("info", self.time_s, "Impulsion refusée", detail=str(error))]
        return []

    def fire_reactor(self, force=False):
        if self.reactor is None:
            return []
        return [_event(e["kind"], self.time_s, e["title"], e.get("formula", ""), e.get("detail", ""))
                for e in self.reactor.fire(force)]

    def set_reactor_fuel(self, index):
        if self.reactor is not None:
            self.reactor.set_fuel(index)
        return []

    def set_reactor_temperature(self, kev):
        if self.reactor is not None:
            self.reactor.set_temperature(kev)
        return []

    def start_star(self):
        from fusionlab import star
        from fusionlab.star import Star
        self._close_chain()
        self.subatomic = self.nucleon = None
        star.warm_up()          # la bibliothèque REACLIB se lit à la première ouverture de l'étoile
        self.star = Star(rng_seed=self.rng.randrange(1 << 30))
        self.lab = None
        self.matter = None
        self.molecule = None
        self.paused = False
        return [_event("structure", 0.0, "Cœur d'étoile",
                       "règle la température et la densité ; le réseau nucléaire fait le reste",
                       "Taux REACLIB (pynucastro), ionisation de Saha, pression de Fermi-Dirac, E = Δm·c².")]

    def stop_star(self):
        self.star = None
        return [_event("structure", self.time_s, "Retour à l'atome")]

    def set_star_preset(self, index):
        if self.star is not None:
            from fusionlab import star as star_module
            self.star.set_preset(index)
            chosen = star_module.PRESETS[self.star.preset]
            return [_event("structure", 0.0, chosen["name"], chosen["about"])]
        return []

    def set_star_temperature(self, kelvin):
        if self.star is not None:
            self.star.set_temperature(kelvin)
        return []

    def set_star_density(self, g_cm3):
        if self.star is not None:
            self.star.set_density(g_cm3)
        return []

    def set_star_speed(self, index):
        if self.star is not None:
            self.star.set_speed(index)
        return []

    def start_chemistry(self, partner_z):
        from molecular import chemistry
        z = self.atom.protons or 1
        self.matter = None
        partner = self.repo.find(partner_z)
        if partner is None:
            return [_event("info", self.time_s, f"Aucun élément Z = {partner_z}")]
        if not chemistry.AVAILABLE:
            return [_event("info", self.time_s, "Calcul quantique indisponible sur ce moteur",
                           "PySCF n'existe que pour Linux et macOS : lance le moteur avec Docker")]
        self.molecule = chemistry.Molecule(z, partner_z)
        mine = self.repo.find(z)
        self.molecule.pair.names = (mine["name"] if mine else self.molecule.pair.names[0], partner["name"])
        self.molecule.start()
        return [_event("structure", self.time_s,
                       f"Échelle de la molécule : {self.molecule.pair.names[0]} + {partner['name']}",
                       f"Pour chaque distance R, la chimie quantique résout les électrons · {chemistry.METHOD}",
                       "Le calcul prend de quelques secondes à une minute la première fois ; il est ensuite "
                       "gardé en mémoire.")]

    def stop_chemistry(self):
        self.molecule = None
        return [_event("structure", self.time_s, "Retour à l'atome seul")]

    def hold_bond(self, distance):
        return [] if self.molecule is None else self.molecule.hold(distance)

    def release_bond(self):
        if self.molecule is None:
            return []
        return [_event(e["kind"], self.molecule.time_fs * 1e-15, e["title"], e["formula"], e["detail"])
                for e in self.molecule.release()]

    def stop_matter(self):
        self.matter = None
        return [_event("structure", self.time_s, "Retour à l'atome seul")]

    def set_wall_temperature(self, kelvin):
        if self.matter is None:
            return []
        self.matter.set_wall_temperature(kelvin)
        return [_event("info", self.time_s, f"Parois réglées à {kelvin:.0f} K",
                       "la chaleur entre (ou sort) par les chocs des atomes sur les parois")]

    def set_piston(self, fraction):
        if self.matter is not None:
            self.matter.set_piston(fraction)
        return []

    def set_step_mode(self, on):
        self.step_mode = bool(on)
        return [_event("info", self.time_s,
                       "Analyse pas à pas : le temps s'arrêtera après chaque événement" if on
                       else "Le temps s'écoule sans s'arrêter")]

    def toggle_pause(self):
        self.paused = not self.paused
        return [_event("info", self.time_s, "Temps suspendu" if self.paused else "Le temps reprend")]

    def add_proton(self):
        if self.atom.protons >= MAX_Z:
            # Au-delà de l'oganesson, aucun noyau n'a été observé : ni masse, ni configuration à calculer.
            return [_event("rejected", self.time_s, "Le proton n'est pas ajouté",
                           f"Z = {MAX_Z} est le dernier élément connu (oganesson)",
                           "Aucun noyau à plus de 118 protons n'a jamais été produit.", particle="proton")]
        self.atom.protons += 1
        return self._after_structure(_event(
            "nucleon", self.time_s, "Un proton rejoint le noyau",
            f"Z = {self.atom.protons} : le nombre de protons fixe l'élément", particle="proton"))

    def remove_proton(self):
        if self.atom.protons == 0:
            return []
        self.atom.protons -= 1
        return self._after_structure(_event("nucleon", self.time_s, "Un proton quitte le noyau",
                                            f"Z = {self.atom.protons}", particle="proton"))

    def add_neutron(self):
        self.atom.neutrons += 1
        return self._after_structure(_event(
            "nucleon", self.time_s, "Un neutron rejoint le noyau",
            f"A = Z + N = {self.atom.protons} + {self.atom.neutrons}", particle="neutron"))

    def remove_neutron(self):
        if self.atom.neutrons == 0:
            return []
        self.atom.neutrons -= 1
        return self._after_structure(_event("nucleon", self.time_s, "Un neutron quitte le noyau",
                                            f"A = {self.atom.protons + self.atom.neutrons}",
                                            particle="neutron"))

    def load_element(self, z):
        element = self.repo.find(z)
        if element is None:
            return [_event("info", self.time_s, f"Aucun élément connu pour Z = {z}")]
        self.atom.load_element(element)
        self.isomer = ""
        self._ground()
        decay = self.nuclear_decay()
        abundance = nuclear_data.abundance(z, self.atom.protons + self.atom.neutrons)
        percent = f"{abundance:.4g}".replace(".", ",") if abundance else ""
        if abundance and decay:
            isotope = (f"isotope naturel le plus abondant, {self.nuclide_label()} ({percent} %), "
                       f"radioactif (t½ = {format_duration(decay['half_life_s'])})")
        elif abundance:
            isotope = f"isotope naturel le plus abondant, {self.nuclide_label()} ({percent} %)"
        elif decay:
            isotope = (f"aucune abondance naturelle tabulée : isotope le plus durable, {self.nuclide_label()} (t½ = "
                       f"{format_duration(decay['half_life_s'])})")
        else:
            isotope = f"isotope {self.nuclide_label()}"
        return [_event("structure", self.time_s, f"{element['name']} chargé",
                       f"{isotope} ; atome neutre : Z = E = {z}")]

    def reset(self):
        self.atom.reset()
        self.excited = None
        self.electron_state = None
        self.photons_in_flight = []
        self.isomer = ""
        return [_event("structure", self.time_s, "Chambre vide")]

    def add_electron(self):
        """Un électron est lancé vers l'atome : sera-t-il retenu ?"""
        z, e = self.atom.protons, self.atom.electrons
        if z == 0:
            return [_event("rejected", self.time_s, "L'électron n'est pas retenu",
                           "aucun noyau chargé pour l'attirer", particle="electron")]
        charge_after = z - (e + 1)
        name = self._name(z)
        if charge_after <= -2:
            return [_event("rejected", self.time_s, "L'électron est repoussé",
                           f"{name} porte déjà une charge négative : il repousse l'électron",
                           "Aucun atome isolé ne retient deux électrons de plus : "
                           "les dianions atomiques n'existent pas.", particle="electron")]
        if charge_after == -1:
            bound = atomic_data.anion_is_bound(z)
            if bound is False:
                return [_event("rejected", self.time_s, f"{name}⁻ n'existe pas : l'électron repart",
                               "affinité électronique ≤ 0 (mesurée)",
                               "La couche externe ne gagne rien à accueillir cet électron.",
                               particle="electron")]
            self.atom.electrons += 1
            self._ground()
            affinity = atomic_data.ELECTRON_AFFINITY_EV.get(z)
            if not affinity:
                return [_event("captured", self.time_s, f"L'électron est capturé : ion {name}⁻",
                               "affinité électronique non tabulée : capture admise",
                               particle="electron")]
            wavelength = spectra.wavelength_from_energy(affinity)
            return [_event("captured", self.time_s, f"L'électron est capturé : ion {name}⁻",
                           f"affinité électronique : {affinity:.3f} eV libérés (mesure)",
                           particle="electron", to_state=f"{name}⁻"),
                    _event("emitted", self.time_s, "Photon d'attachement",
                           f"λ = h·c / E = 1239,84 / {affinity:.3f} = {wavelength:.0f} nm",
                           "L'énergie gagnée par l'électron en se liant part en lumière.",
                           wavelength_nm=wavelength, energy_ev=affinity)]

        self.atom.electrons += 1
        self._ground()
        if self.one_electron():
            # Attrapé loin du noyau ; il descendra de lui-même, en cascade.
            self.electron_state = hydrogen.capture_state(self.rng, z)
            self.electron_spin = self.rng.choice(["+1/2", "−1/2"])
            state = hydrogen.label(*self.electron_state)
            return [_event("captured", self.time_s, f"L'électron est capturé en {state}",
                           f"il est attiré par +{z} e et tombe dans un niveau élevé",
                           "Il va redescendre seul vers 1s en émettant des photons.",
                           particle="electron", to_state=state)]
        subshells = configuration(z, self.atom.electrons)
        n, l, _ = subshells[-1]
        measured = atomic_data.ionization_energy_ev(z, z - self.atom.electrons)
        energy = measured or removal_energy_ev(z, subshells, n, l)
        source = "mesure NIST" if measured else "modèle de Slater"
        wavelength = spectra.wavelength_from_energy(energy) if energy > 0 else 0.0
        events = [_event("captured", self.time_s, f"L'électron est capturé en {n}{'spdf'[l]}",
                         f"énergie libérée = énergie de liaison = {energy:.2f} eV ({source})",
                         particle="electron", to_state=f"{n}{'spdf'[l]}")]
        if energy > 0:
            events.append(_event("emitted", self.time_s, "Rayonnement de recombinaison",
                                 f"λ = h·c / E = 1239,84 / {energy:.2f} = {wavelength:.1f} nm",
                                 "L'énergie gagnée en se liant part sous forme de lumière.",
                                 wavelength_nm=wavelength, energy_ev=energy))
        return events

    def remove_electron(self):
        z, e = self.atom.protons, self.atom.electrons
        if e == 0:
            return []
        energy = self.ionization_energy()
        self.atom.electrons -= 1
        self._ground()
        return self._enforce_binding() + [_event(
            "ionized", self.time_s, "Un électron est arraché",
            f"il a fallu fournir {energy:.2f} eV" if energy else "", particle="electron")]

    def heat(self):
        """Agitation thermique (flamme, décharge) : une collision excite l'atome."""
        z, e = self.atom.protons, self.atom.electrons
        if e == 0:
            return [_event("info", self.time_s, "Aucun électron à exciter")]
        if self.one_electron():
            n = self.rng.choice([2, 2, 3, 3, 4])
            l = self.rng.randrange(n)
            self.electron_state = (n, l)
            life = format_duration(hydrogen.lifetime_s(z, (n, l)))
            return [_event("excited", self.time_s, f"Collision : l'électron passe en {hydrogen.label(n, l)}",
                           f"énergie reçue = E({n}) − E(1) = {self._level_ev(n) - self._level_ev(1):.2f} eV",
                           f"durée de vie de {hydrogen.label(n, l)} : {life}. Une collision n'obéit pas "
                           "à Δl = ±1 : tous les états sont accessibles.",
                           to_state=hydrogen.label(n, l))]
        if e != z:
            return [_event("info", self.time_s, "Spectre des ions à plusieurs électrons non tabulé",
                           "aucune couleur ne peut être montrée sans inventer de raie")]
        lines = spectra.REFERENCE_LINES.get(z, [])
        if not lines:
            return [_event("info", self.time_s, f"Aucune raie mesurée de {self._name(z)} dans le simulateur",
                           "sa couleur ne peut pas être montrée sans l'inventer")]
        total = sum(weight for _, weight in lines)
        pick = self.rng.uniform(0, total)
        for wavelength, weight in lines:
            pick -= weight
            if pick <= 0:
                break
        lifetime_ns = atomic_data.resonance_lines(z).get(wavelength) or atomic_data.TYPICAL_LIFETIME_NS
        self.excited = {"line": wavelength, "lifetime_s": lifetime_ns * 1e-9, "cause": "collision"}
        return [_event("excited", self.time_s, "Collision : l'atome est excité",
                       f"il émettra une de ses raies mesurées (tirée selon leur intensité)",
                       f"durée de vie de l'état : {lifetime_ns:.3g} ns", to_state="excité")]

    def fire_photon(self, wavelength_nm):
        """Un photon part vers l'atome ; son sort se joue à l'arrivée."""
        if not math.isfinite(wavelength_nm) or wavelength_nm <= 0:
            return []
        energy = spectra.photon_energy_ev(wavelength_nm)
        self.photons_in_flight.append((self.real_clock + PHOTON_FLIGHT_REAL_S, wavelength_nm))
        return [_event("photon_fired", self.time_s, f"Photon envoyé : λ = {wavelength_nm:.2f} nm",
                       f"E = h·c / λ = 1239,84 / {wavelength_nm:.2f} = {energy:.3f} eV",
                       detail=f"Photon monochromatique · faisceau gaussien de rayon {light.beam_waist_m(wavelength_nm) * 1e6:.3g} µm · atome immobile au centre",
                       wavelength_nm=wavelength_nm, energy_ev=energy)]

    def fire_neutron(self):
        """Un neutron thermique (0,0253 eV) touche le noyau : diffusion, capture, fission, (n,α) ou (n,p),
        tirés au prorata des sections efficaces mesurées."""
        z, a = self.atom.protons, self.atom.protons + self.atom.neutrons
        if a == 0:
            return [_event("info", self.time_s, "Aucun noyau à frapper",
                           "Ajouter des protons ou choisir un élément dans la table.")]
        target = self.nuclide_label()
        result = neutrons.outcome(self.rng, z, a) if z > 0 else None
        if result is None:
            return [_event("info", self.time_s, f"Pas de section efficace mesurée pour {target}",
                           "Genesis ne devine pas ce que ferait le neutron.",
                           "La table de Sears (1992) couvre les isotopes naturels et quelques actinides "
                           "(U-233, U-235, U-238, Pu-239…), pas la plupart des noyaux radioactifs.")]
        sigma, total = result["sigma"], result["total"]
        names = {"scattering": "diffusion", "capture": "capture (n,γ)", "fission": "fission",
                 "alpha": "(n,α)", "proton": "(n,p)"}
        shares = " · ".join(f"{names[key]} {_number(sigma[key], 3)} b ({_number(100 * sigma[key] / total, 3)} %)"
                            for key in names if sigma[key] > 0)
        incoming = _event("nucleon", self.time_s, f"Un neutron lent arrive sur {target}",
                          f"E = {_number(neutrons.THERMAL_EV, 3)} eV (v = 2 200 m/s) : sans charge, aucune "
                          "barrière ne l'arrête",
                          f"Sections efficaces thermiques ({neutrons.SOURCE}, ENDF/B-VIII.0) : {shares}. "
                          "Un neutron lent traverse presque toujours un atome seul sans rien toucher : "
                          "on montre le cas où il interagit.", particle="neutron")
        chance = f"Voie suivie par {_number(100 * result['probability'], 3)} % des neutrons qui interagissent"
        channel = result["channel"]
        if channel == "scattering":
            return [incoming, _event("nucleon", self.time_s, "Le neutron rebondit et quitte le noyau",
                                     f"diffusion élastique : σ = {_number(sigma['scattering'], 3)} b (noyau seul)",
                                     f"{target} reste {target}. {chance}.", particle="neutron")]

        q = result["q_mev"]
        q_text = (f"Q = {_number(q, 4)} MeV (masses mesurées, AME2020)" if q is not None
                  else "Q inconnu : une masse n'a jamais été mesurée")
        residual, emitted = result["residual"], result["emitted"]
        new = nuclear_data.format_name(*residual, "")
        if channel == "capture":
            mode, particles = "n_capture", ["photon γ"]
            title = f"{target} + n → {new} : capture radiative (n,γ)"
            formula = (f"Q = S_n = [m({target}) + m_n − m({new})]·c² = {_number(q, 4)} MeV, emportés par des photons γ"
                       if q is not None else q_text)
            detail = f"Le neutron reste : même élément, un neutron de plus. {chance}."
            if channel == "capture" and z in (92, 94) and sigma["fission"] == 0:
                detail += " Ce noyau ne fissionne pas avec un neutron lent (barrière de fission trop haute)."
            elif sigma["fission"] == 0 and (z, a) not in neutrons.OTHER_CHANNELS:
                detail += " Les voies (n,p) et (n,α), quand elles existent ici, pèsent quelques pour cent au plus : comptées comme capture."
        elif channel == "fission":
            nu = result["nu"]
            light = nuclear_data.format_name(*emitted, "")
            mode, particles = "n_fission", [f"fragment {light}"] + ["neutron"] * nu
            title = f"{target} + n → {new} + {light} + {nu} n : fission"
            formula = q_text
            detail = (f"Le noyau composé {nuclear_data.format_name(z, a + 1, '')} se scinde. "
                      f"ν̄ = {_number(neutrons.NU_BAR.get((z, a), 2.5), 3)} neutrons en moyenne (ENDF/B-VIII.0) ; "
                      f"fragment lourd tiré autour de A ≈ 140, charge au prorata de la masse. Le fragment lourd reste "
                      f"ici, le léger s'éloigne. {chance}.")
        else:
            particle = nuclear_data.format_name(*emitted, "")
            mode = "n_alpha" if channel == "alpha" else "n_proton"
            particles = ["particule α"] if channel == "alpha" else ["proton"]
            title = f"{target} + n → {new} + {'α' if channel == 'alpha' else 'p'} : réaction {names[channel]}"
            formula = q_text
            detail = f"Le neutron est absorbé et le noyau éjecte un {particle}. {chance}."
        self.atom.protons, self.atom.neutrons = residual[0], residual[1] - residual[0]
        self.isomer = ""
        self._ground()
        event = _event("decay", self.time_s, title, formula, detail, decay_mode=mode, particles=particles,
                       q_mev=q or 0.0, from_state=target, to_state=self.nuclide_label())
        if channel == "alpha" and q is not None and q > 0:
            # Le neutron lent n'apporte presque pas d'élan : α et noyau résiduel se partagent Q à impulsions opposées.
            event["kinetic_ev"] = q * residual[1] / (residual[1] + 4) * 1e6
        return [incoming, event] + self._enforce_binding()

    def next_event(self):
        """Avance le temps jusqu'au prochain événement que les règles prévoient."""
        rate_e = self.electron_rate()
        rate_n = self.nuclear_rate()
        if rate_e == 0 and rate_n == 0:
            return [_event("info", self.time_s, "Rien ne peut arriver",
                           "électron au repos et noyau stable : l'atome est éternel")]
        wait = self.rng.expovariate(rate_e + rate_n)
        events = [_event("info", self.time_s, f"Saut dans le temps : {format_duration(wait)}",
                         "attente tirée au hasard selon les durées de vie")]
        # Le hasard déjà tiré fixe le moment ; on rejoue le pas jusqu'à lui.
        events += self._simulate(wait * (1 + 1e-9), forced_wait=wait)
        return events

    # ------------------------------------------------------------------
    # Le temps
    # ------------------------------------------------------------------
    def advance(self, real_dt):
        self.real_clock += real_dt
        if self.lab is not None or self.reactor is not None or self.chain is not None or self.molecule_lab is not None:
            return []          # les laboratoires n'avancent qu'à chaque tir, ou dans leur propre fil
        if self.star is not None:
            return self._advance_star(real_dt)
        if self.molecule is not None:
            if self.paused:
                return []
            return [_event(e["kind"], self.molecule.time_fs * 1e-15, e["title"], e["formula"], e["detail"])
                    for e in self.molecule.advance(real_dt)]
        if self.matter is not None:
            # À l'échelle de la matière, c'est elle qui avance ; l'atome seul attend.
            if self.paused:
                return []
            events = [_event(e["kind"], self.matter.time_ps() * 1e-12, e["title"], e["formula"], e["detail"])
                      for e in self.matter.advance(real_dt)]
            if events and self.step_mode:
                self.paused = True
            return events
        events = self._resolve_photons()
        if events and self.step_mode:
            self.paused = True
        if self.paused:
            return events
        return events + self._simulate(real_dt * self.speed)

    def _advance_star(self, real_dt):
        from fusionlab import star as star_module
        s = self.star
        s.advance(real_dt, self.paused)
        events = []
        for species, j in s.firsts:
            nucleus = s.net.nuclei[species]
            rate = s.net.rates[j]
            events.append(_event("structure", s.time_s,
                                 f"Élément créé : {star_module.label_of(nucleus)} ({star_module.name_of(nucleus)})",
                                 star_module.reaction_label(rate),
                                 f"Premier noyau de {star_module.label_of(nucleus)} dans la fenêtre, "
                                 f"Q = {rate.Q:.3f} MeV ({s.chain_of(rate)})."))
        s.firsts = []
        return events

    def _simulate(self, sim_dt, forced_wait=None):
        """Avance le temps simulé de sim_dt en appliquant les règles.

        Les attentes sont tirées selon une loi exponentielle : c'est la loi
        d'un phénomène sans mémoire, comme la désexcitation d'un électron ou
        la désintégration d'un noyau. Tirer de nouveau à chaque pas est
        légitime pour la même raison.
        """
        events = []
        remaining = sim_dt
        for _ in range(MAX_EVENTS_PER_STEP):
            rate_e = self.electron_rate()
            rate_n = self.nuclear_rate()
            total = rate_e + rate_n
            if total <= 0:
                break
            wait = forced_wait if forced_wait is not None else self.rng.expovariate(total)
            forced_wait = None
            if wait > remaining:
                break
            remaining -= wait
            self.time_s += wait
            # Lequel des deux processus a eu lieu ? Chacun au prorata de son taux.
            if self.rng.uniform(0, total) < rate_e:
                events += self._electron_decays()
            else:
                events += self._nucleus_decays()
            if self.step_mode:
                # On s'arrête juste après l'événement : le temps restant n'est pas consommé.
                self.paused = True
                remaining = 0.0
                break
        self.time_s += max(0.0, remaining)
        return events

    def _electron_decays(self):
        z = self.atom.protons
        if self.one_electron():
            upper = self.electron_state
            channels = hydrogen.decay_channels(z, upper)
            total = sum(rate for _, rate, _ in channels)
            pick = self.rng.uniform(0, total)
            for lower, rate, kind in channels:
                pick -= rate
                if pick <= 0:
                    break
            self.electron_state = lower
            share = rate / total * 100
            if kind == "deux photons":
                gap = self._level_ev(upper[0]) - self._level_ev(lower[0])
                split = self.rng.uniform(0.15, 0.85)
                first = spectra.wavelength_from_energy(gap * split)
                second = spectra.wavelength_from_energy(gap * (1 - split))
                return [_event("emitted", self.time_s, "2s → 1s : DEUX photons à la fois",
                               f"ΔE = {gap:.2f} eV partagée : {first:.0f} nm + {second:.0f} nm",
                               "Δl = 0 est interdit pour un photon : seule l'émission double est "
                               "possible, d'où une durée de vie de 0,12 s.",
                               wavelength_nm=first, energy_ev=gap * split,
                               extra_wavelengths_nm=[second], from_state="2s", to_state="1s")]
            line = spectra.hydrogenic_transition(z, upper[0], lower[0], self._mu())
            return [_event(
                "emitted", self.time_s,
                f"{hydrogen.label(*upper)} → {hydrogen.label(*lower)} : un photon de {line['wavelength_nm']:.2f} nm",
                f"ΔE = E({upper[0]}) − E({lower[0]}) = {line['energy_ev']:.3f} eV ; λ = h·c / ΔE",
                f"{line['label']} · chemin pris dans {share:.0f} % des cas · A = {rate:.3g} s⁻¹ · "
                f"le photon (spin 1) emporte Δl = {lower[1] - upper[1]:+d} ; le spin de l'électron "
                f"({self.electron_spin}) ne change pas",
                wavelength_nm=line["wavelength_nm"], energy_ev=line["energy_ev"],
                from_state=hydrogen.label(*upper), to_state=hydrogen.label(*lower))]

        line = self.excited["line"]
        energy = spectra.photon_energy_ev(line)
        cause = self.excited.get("cause", "")
        self.excited = None
        term = atomic_data.resonance_term(z, line)
        detail = "raie mesurée (NIST)" + (" · fluorescence de résonance" if cause == "photon" else "")
        if term:
            detail = f"{term[0]} · {detail} · {term[1]}"
        return [_event("emitted", self.time_s, f"L'atome se désexcite : photon de {line:.2f} nm",
                       f"E = h·c / λ = 1239,84 / {line:.2f} = {energy:.3f} eV",
                       detail,
                       wavelength_nm=line, energy_ev=energy, from_state="excité", to_state="fondamental")]

    def _nucleus_decays(self):
        decay = self.nuclear_decay()
        if decay is None:
            return []
        z, n = self.atom.protons, self.atom.neutrons
        a = z + n
        # Quelle voie ? Chacune au prorata de son rapport de branchement mesuré.
        channels = decay["channels"]
        pick = self.rng.uniform(0, sum(fraction for _, fraction, _ in channels))
        for mode, fraction, daughter in channels:
            pick -= fraction
            if pick <= 0:
                break
        spec = _DECAYS.get(mode)
        if spec is None:
            return []
        parent = self.nuclide_label()

        q = nuclear_data.q_value_mev(z, a, mode)
        q_source = "masses mesurées (AME2020)"
        if q is None:
            q = spec["estimate"](z, n)
            q_source = "estimation Weizsäcker, ± quelques MeV"
        if mode == "beta+" and nuclear_data.q_value_mev(z, a, "beta+") is not None and q <= 0:
            # Pas assez d'énergie pour créer un positon : seule la capture reste.
            mode, spec = "CE", _DECAYS["CE"]
            q = nuclear_data.q_value_mev(z, a, "CE")

        emitted = list(spec["emitted"])
        if mode == "SF":
            self.atom.protons, self.atom.neutrons = fission_fragment(z, n)
            self.isomer = ""
        else:
            self.atom.protons, self.atom.neutrons = daughter[0], daughter[1] - daughter[0]
            self.isomer = daughter[2]
        self.atom.electrons = max(0, self.atom.electrons + spec["de"])
        self.excited = None
        self.electron_state = (1, 0) if self.one_electron() else None

        formula = spec["rule"]
        kinetic = None
        if mode == "alpha" and q > 0:
            # Le recul du noyau fils emporte une petite part : E_α = Q (A − 4) / A.
            kinetic = q * (a - 4) / a
            formula += f" · Q = {q:.3f} MeV ({q_source}) ; E_α = Q·(A−4)/A = {kinetic:.3f} MeV"
        elif mode in ("beta-", "beta+"):
            formula += (f" · Q = {q:.3f} MeV ({q_source}), partagés au hasard entre "
                        f"l'{'électron' if mode == 'beta-' else 'positon'} et le neutrino")
        elif z == 0:
            formula += " · aucune force ne lie des neutrons seuls"
        elif mode not in ("IT", "SF"):
            formula += f" · Q = {q:.3f} MeV ({q_source})"
        detail = f"demi-vie de {parent} : {format_duration(decay['half_life_s'])} ({decay['source']})"
        if len(channels) > 1:
            detail += f" · voie suivie dans {fraction * 100:.3g} % des cas"
        event = _event("decay", self.time_s,
                       f"{parent} → {self.nuclide_label()} : désintégration {self._mode_label(mode)}",
                       formula, detail, decay_mode=mode, particles=emitted, q_mev=q,
                       from_state=parent, to_state=self.nuclide_label())
        if kinetic is not None:
            event["kinetic_ev"] = kinetic * 1e6
        return [event] + self._enforce_binding()

    @staticmethod
    def _mode_label(mode):
        return {"beta-": "β⁻", "beta+": "β⁺", "CE": "par capture électronique",
                "alpha": "α", "n": "par émission de neutron", "p": "par émission de proton",
                "2n": "par émission de deux neutrons", "2p": "par émission de deux protons",
                "beta-n": "β⁻ suivie d'un neutron", "beta+p": "β⁺ suivie d'un proton",
                "IT": "γ (transition isomérique)", "SF": "par fission spontanée"}.get(mode, mode)

    # ------------------------------------------------------------------
    # Photons : absorption, ionisation, ou rien
    # ------------------------------------------------------------------
    def _resolve_photons(self):
        events = []
        arrived = [photon for photon in self.photons_in_flight if photon[0] <= self.real_clock]
        self.photons_in_flight = [photon for photon in self.photons_in_flight if photon[0] > self.real_clock]
        for _, wavelength in arrived:
            events += self._photon_arrives(wavelength)
        return events

    def ionization_energy(self):
        z, e = self.atom.protons, self.atom.electrons
        if e == 0:
            return 0.0
        if self.one_electron():
            return -self._level_ev(self.electron_state[0])
        measured = atomic_data.ionization_energy_ev(z, z - e)
        if measured:
            # Excité, l'électron a déjà reçu l'énergie de la raie : il en faut moins.
            spent = spectra.photon_energy_ev(self.excited["line"]) if self.excited else 0.0
            return measured - spent
        subshells = configuration(z, e)
        n, l, _ = subshells[-1]
        return removal_energy_ev(z, subshells, n, l)

    def _photon_arrives(self, wavelength):
        z, e = self.atom.protons, self.atom.electrons
        energy = spectra.photon_energy_ev(wavelength)
        waist = light.beam_waist_m(wavelength)
        if e == 0:
            return [_event("passed", self.time_s, "Le photon traverse : aucun électron pour l'absorber",
                           wavelength_nm=wavelength, energy_ev=energy)]

        threshold = self.ionization_energy()
        if threshold > 0 and energy >= threshold:
            if not self.one_electron() or self.electron_state != (1, 0):
                return [_event("info", self.time_s, "Photoionisation : section efficace non disponible",
                               detail="Le seuil est connu, mais il ne suffit pas à prédire une probabilité. "
                               "Le modèle de continuum actuel couvre uniquement les ions hydrogénoïdes 1s.",
                               wavelength_nm=wavelength, energy_ev=energy)]
            sigma = light.hydrogen_1s_photoionization_m2(z, energy, threshold, self._mu())
            probability = light.interaction_probability(sigma, waist)
            if self.rng.random() >= probability:
                return [_event("passed", self.time_s, "Le photon traverse sans ioniser",
                               f"σ = {sigma:.3g} m² ; P = σ / (πw²/2) = {probability:.3g}",
                               f"Dépasser le seuil rend l'ionisation possible, pas certaine ; w = {waist * 1e6:.3g} µm.",
                               wavelength_nm=wavelength, energy_ev=energy)]
            kinetic = energy - threshold
            self.atom.electrons -= 1
            self._ground()
            return [_event("photo_ionized", self.time_s, "Effet photoélectrique : l'électron est arraché",
                           f"E cinétique = E photon − E ionisation = {energy:.2f} − {threshold:.2f} "
                           f"= {kinetic:.2f} eV",
                           f"Continuum hydrogénoïde 1s · σ = {sigma:.3g} m² · P = {probability:.3g} · w = {waist * 1e6:.3g} µm.",
                           wavelength_nm=wavelength, energy_ev=energy, kinetic_ev=kinetic,
                           particle="electron")] + self._enforce_binding()

        if self.one_electron():
            n, l = self.electron_state
            candidates = []
            mu = self._mu()
            lower_rate = sum(rate for _, rate, _ in hydrogen.decay_channels(z, (n, l))) * mu
            for target in hydrogen.absorption_targets(z, self.electron_state):
                line = spectra.hydrogenic_transition(z, target[0], n, self._mu())
                upper_rate = sum(rate for _, rate, _ in hydrogen.decay_channels(z, target)) * mu
                sigma = light.resonance_cross_section_m2(wavelength, line["wavelength_nm"],
                            hydrogen.einstein_a(z, target, (n, l)) * mu, upper_rate, lower_rate,
                            (2 * target[1] + 1) / (2 * l + 1))
                candidates.append((target, sigma, line))
            total_sigma = sum(sigma for _, sigma, _ in candidates)
            probability = light.interaction_probability(total_sigma, waist)
            if total_sigma > 0 and self.rng.random() < probability:
                pick = self.rng.uniform(0, total_sigma)
                for target, weight, line in candidates:
                    pick -= weight
                    if pick <= 0:
                        break
                before = hydrogen.label(n, l)
                self.electron_state = target
                return [_event("absorbed", self.time_s,
                               f"Photon absorbé : {before} → {hydrogen.label(*target)}",
                               f"E photon = {energy:.3f} eV = E({target[0]}) − E({n}) "
                               f"= {self._level_ev(target[0]) - self._level_ev(n):.3f} eV",
                               f"σ = {total_sigma:.3g} m² · P = {probability:.3g} · profil naturel · "
                               f"Le photon (spin 1) apporte une unité de moment cinétique : seuls les "
                               f"sauts Δl = ±1 l'absorbent ; le spin de l'électron ({self.electron_spin}) "
                               f"ne change pas. Durée de vie de "
                               f"{hydrogen.label(*target)} : {format_duration(hydrogen.lifetime_s(z, target))}.",
                               wavelength_nm=wavelength, energy_ev=energy,
                               from_state=before, to_state=hydrogen.label(*target))]
            return [_event("passed", self.time_s, "Le photon traverse l'atome sans être absorbé",
                           f"σ = {total_sigma:.3g} m² ; P = {probability:.3g} depuis {hydrogen.label(n, l)}",
                           f"Profil naturel · faisceau gaussien de rayon {waist * 1e6:.3g} µm.",
                           wavelength_nm=wavelength, energy_ev=energy)]

        # Sans les forces d'oscillateur et les sous-niveaux, approximation à
        # deux niveaux explicitement indiquée ; aucune tolérance rectangulaire.
        candidates = []
        if not self.excited and e == z:
            for line_nm, lifetime_ns in atomic_data.resonance_lines(z).items():
                if lifetime_ns:
                    rate = 1 / (lifetime_ns * 1e-9)
                    sigma = light.resonance_cross_section_m2(wavelength, line_nm, rate, rate,
                                                             degeneracy_ratio=1.0)
                    candidates.append((line_nm, sigma))
        total_sigma = sum(sigma for _, sigma in candidates)
        probability = light.interaction_probability(total_sigma, waist)
        line = None
        if total_sigma > 0 and self.rng.random() < probability:
            pick = self.rng.uniform(0, total_sigma)
            for line_nm, sigma in candidates:
                pick -= sigma
                if pick <= 0:
                    line = line_nm
                    break
        if line:
            lifetime_ns = atomic_data.resonance_lines(z)[line] or atomic_data.TYPICAL_LIFETIME_NS
            self.excited = {"line": line, "lifetime_s": lifetime_ns * 1e-9, "cause": "photon"}
            term = atomic_data.resonance_term(z, line)
            detail = (f"durée de vie : {lifetime_ns:.3g} ns · approximation à deux niveaux non polarisés · "
                      f"σ = {total_sigma:.3g} m² · P = {probability:.3g} · w = {waist * 1e6:.3g} µm")
            if term:
                # À l'absorption, l'électron monte : la flèche va vers le niveau excité.
                detail = f"{term[0].replace('←', '→')} · {term[1]} · {detail}"
            return [_event("absorbed", self.time_s, f"Photon absorbé : raie de résonance {line:.2f} nm",
                           f"E = {energy:.3f} eV = écart mesuré entre le fondamental et un niveau excité",
                           detail,
                           wavelength_nm=wavelength, energy_ev=energy, from_state="fondamental",
                           to_state="excité")]
        reason = ("l'atome est déjà excité" if self.excited else
                  "ion : raies non tabulées" if e != z else
                  f"profil naturel : P = {probability:.3g} ; raies sans durée de vie non modélisées")
        return [_event("passed", self.time_s, "Le photon traverse l'atome sans être absorbé",
                       f"{energy:.3f} eV : {reason}", wavelength_nm=wavelength, energy_ev=energy)]

    # ------------------------------------------------------------------
    # Cohérence : un atome ne garde que les électrons qu'il peut retenir
    # ------------------------------------------------------------------
    def _ground(self):
        """Toute modification du cortège ou du noyau : retour au fondamental."""
        self.excited = None
        self.electron_state = (1, 0) if self.one_electron() else None

    def _after_structure(self, event):
        self.isomer = ""
        self._ground()
        return [event] + self._enforce_binding()

    def _enforce_binding(self):
        """Éjecte les électrons qu'aucun atome réel ne retiendrait."""
        reasons = []
        lost = 0
        while self.atom.electrons > 0:
            z, e = self.atom.protons, self.atom.electrons
            charge = z - e
            if z == 0:
                reason = "plus aucun proton pour retenir les électrons"
            elif charge <= -2:
                reason = "un atome isolé ne retient jamais deux électrons de plus"
            elif charge == -1 and atomic_data.anion_is_bound(z) is False:
                reason = f"l'ion {self._name(z)}⁻ n'existe pas (affinité électronique ≤ 0)"
            else:
                break
            self.atom.electrons -= 1
            lost += 1
            if reason not in reasons:
                reasons.append(reason)
        if not reasons:
            return []
        self._ground()
        return [_event("rejected", self.time_s,
                       "Un électron s'échappe" if lost == 1 else f"{lost} électrons s'échappent",
                       " ; ".join(reasons), "Le cortège s'ajuste à la charge du noyau.",
                       particle="electron", count=lost)]

    def _name(self, z):
        element = self.repo.find(z)
        return element["symbol"] if element else f"Z={z}"

    # ------------------------------------------------------------------
    def evaluate(self, action_label=None):
        """Photographie évaluée, avec l'état excité courant."""
        if self.one_electron():
            self.atom.excitation = self.electron_state[0] - 1
        else:
            self.atom.excitation = 1 if self.excited else 0
        evaluation = self.atom.evaluate(self.repo, action_label=action_label)
        if self.one_electron():
            evaluation.orbital = hydrogenic_orbital(self.atom.protons, self.electron_state, self._mu())
        elif self.excited:
            # Les raies tabulées ne déterminent pas la densité de l'état excité.
            evaluation.orbital = None
        return evaluation
