"""L'échelle de la matière : des centaines d'atomes, et la thermodynamique qui en émerge.

Au-dessus de l'atome, on ne regarde plus les électrons : chaque atome devient
une sphère, et seule compte la force entre deux atomes voisins. Elle est
décrite par le potentiel de Lennard-Jones :

    V(r) = 4 ε [ (σ/r)¹² − (σ/r)⁶ ]

  - le terme en r⁻⁶ attire (forces de van der Waals : les nuages
    électroniques se polarisent l'un l'autre) ;
  - le terme en r⁻¹² repousse violemment à courte distance (les nuages
    électroniques ne peuvent pas s'interpénétrer : Pauli).

Avec cette seule règle, intégrée pas à pas (Verlet vitesse), solide, liquide
et gaz apparaissent d'eux-mêmes selon la température. Rien n'est imposé :
l'état de la matière est mesuré, pas décidé.

Choix tirés du réel :
  - 3D : en 3D, Lennard-Jones avec les paramètres de l'argon fond et bout à
    quelques kelvins des valeurs mesurées ; en 2D, ce serait faux ;
  - pas de gravité : à l'échelle de quelques nanomètres, m·g·h est des
    milliards de fois plus petit que k·T ; une goutte flotte ;
  - parois thermiques : la chaleur entre par les parois (un atome qui les
    touche repart avec une vitesse tirée à leur température), donc un cristal
    chauffé fond depuis les bords ;
  - pression : mesurée par les chocs des atomes sur les parois.

Paramètres ε et σ : bibliothèque `chemicals` (tables de Magalhães et al.
2013 et de Poling et al. 2001), sinon estimés par les relations de Bird,
Stewart et Lightfoot à partir de la température d'ébullition mesurée.
Températures de référence (fusion, ébullition, point critique) : mendeleev.

Unités réduites en interne : σ = 1, ε = 1, m = 1, k = 1.
"""

import math

import numpy as np
from chemicals.lennard_jones import Stockmayer, Stockmayer_methods, molecular_diameter, molecular_diameter_methods
from scipy import constants as sc
from scipy.spatial import cKDTree

from common import elements_db

_DB = elements_db.DB   # verrouillée : plusieurs threads la lisent

CUTOFF = 2.5            # portée du potentiel, en σ (au-delà, < 1,6 % du puits)
SKIN = 0.4              # marge de la liste de voisins
DT = 0.004              # pas de temps, en unités réduites τ = σ √(m/ε)
STEPS_PER_SECOND = 400  # pas d'intégration par seconde réelle (≈ 1,6 τ/s : on voit bouger)
WALL_MARGIN = 0.5       # les centres restent à σ/2 des parois
NEIGHBOR_SHELL = 1.5    # voisins comptés jusqu'à 1,5 σ (premier pic de g(r))
# Point critique de ce modèle (Lennard-Jones tronqué à 2,5 σ) : T* = 1,085
# (Smit, J. Chem. Phys. 96, 8639, 1992). Au-delà, liquide et gaz ne se
# distinguent plus : il n'y a qu'un fluide supercritique.
CRITICAL_T = 1.085
_SHIFT = 4 * (CUTOFF ** -12 - CUTOFF ** -6)

PREFERRED_SOURCES = ["Magalhães, Lito, Da Silva, and Silva (2013)", "Poling et al. (2001)"]


# ----------------------------------------------------------------------
# Paramètres de l'élément
# ----------------------------------------------------------------------
def element_data(z):
    row = _DB.execute("select symbol, name, cas, atomic_weight, vdw_radius from elements where atomic_number=?",
                      (z,)).fetchone()
    transitions = _DB.execute("select melting_point, boiling_point, critical_temperature, triple_point_temperature "
                              "from phasetransitions where atomic_number=?", (z,)).fetchone()
    return row, transitions


def lj_parameters(z):
    """(ε/k en K, σ en Å, source) pour l'élément Z.

    Une valeur tabulée n'est retenue que si elle est cohérente avec la
    température d'ébullition mesurée (0,8 < ε / k·T_éb < 2,6) : la table de
    Magalhães donne par exemple 512 K pour le krypton, ce qui est aberrant.
    """
    row, transitions = element_data(z)
    if row is None:
        return None
    symbol, name, cas, weight, vdw = row
    boiling = transitions[1] if transitions else None
    if cas:
        for method in PREFERRED_SOURCES:
            if method not in Stockmayer_methods(cas) or method not in molecular_diameter_methods(cas):
                continue
            epsilon = Stockmayer(cas, method=method)
            sigma = molecular_diameter(cas, method=method)
            # (jusqu'à 2,6 pour l'hélium, fluide quantique qui bout anormalement bas)
            if epsilon and sigma and (boiling is None or 0.8 < epsilon / boiling < 2.6):
                note = " (molécule diatomique)" if z in (1, 7, 8, 9, 17, 35, 53) else ""
                return epsilon, sigma, f"{method}{note}, via chemicals"
    if boiling and vdw:
        # Bird, Stewart & Lightfoot : ε/k ≈ 1,15 T_éb ; σ tiré du rayon de van der Waals
        # (le minimum du potentiel, 2^(1/6) σ, vaut deux rayons).
        return 1.15 * boiling, 2 * vdw / 100 / 2 ** (1 / 6), (
            "estimation : ε/k = 1,15 T_éb (Bird-Stewart-Lightfoot), σ du rayon de van der Waals — "
            "Lennard-Jones décrit mal les liaisons métalliques ou covalentes")
    return None


# ----------------------------------------------------------------------
# La simulation
# ----------------------------------------------------------------------
class Matter:
    """Un gaz, un liquide ou un solide de N atomes dans une boîte à parois thermiques."""

    def __init__(self, z, mass_u, rng_seed=None, cells=4):
        params = lj_parameters(z)
        self.z = z
        if params is None:
            # Faute de données pour cet élément, on montre l'argon et on le dit.
            self.z = 18
            params = lj_parameters(18)
        self.epsilon_k, self.sigma_a, self.source = params
        row, transitions = element_data(self.z)
        self.symbol, self.name = row[0], row[1]
        self.mass_u = mass_u if (mass_u and self.z == z) else row[3]
        self.melting_k, self.boiling_k, self.critical_k, self.triple_k = (
            transitions if transitions else (None, None, None, None))
        self.rng = np.random.default_rng(rng_seed)

        # Un cristal cubique à faces centrées, comme l'argon solide, posé contre les
        # parois du récipient (sinon, flottant dans le vide, il ne recevrait jamais
        # leur chaleur). Le côté droit est vide : c'est là qu'il pourra s'évaporer.
        lattice = (4 / 1.05) ** (1 / 3)
        base = np.array([[0, 0, 0], [0.5, 0.5, 0], [0.5, 0, 0.5], [0, 0.5, 0.5]])
        sites = [(np.array([i, j, k]) + b) * lattice
                 for i in range(cells) for j in range(cells) for k in range(cells) for b in base]
        positions = np.array(sites) + WALL_MARGIN + 0.05
        side = positions[:, 1].max() + lattice / 2 + WALL_MARGIN
        self.box_max = np.array([3.2 * side, side, side])
        self.box = self.box_max.copy()
        self.box[0] = 2.0 * side
        self.pos = positions
        self.n = len(positions)
        self.wall_t = 0.45 * 1.0          # consigne des parois, en ε/k (solide au départ)
        self.vel = self.rng.normal(0, math.sqrt(self.wall_t), (self.n, 3))
        self.vel -= self.vel.mean(axis=0)
        self.atom_t = np.full(self.n, self.wall_t)
        self.time = 0.0
        self.impulse = 0.0
        self.pressure = 0.0
        self.hits = []
        self.phase = np.zeros(self.n, dtype=int)
        self.fractions = (1.0, 0.0, 0.0)
        self.dominant = "solide"
        self.snapshot = self.pos.copy()
        self.snapshot_time = 0.0
        self._rebuild()
        self.force = self._forces()

    # --- réglages ----------------------------------------------------------
    def set_wall_temperature(self, kelvin):
        self.wall_t = max(0.02, kelvin / self.epsilon_k)

    def set_piston(self, fraction):
        """Le mur de droite avance ou recule : la boîte change de volume."""
        self.box[0] = self.box_max[0] * min(1.0, max(0.25, fraction))
        limit = self.box[0] - WALL_MARGIN
        pushed = self.pos[:, 0] > limit
        self.pos[pushed, 0] = limit
        self.vel[pushed, 0] = -np.abs(self.vel[pushed, 0])
        self._rebuild()

    # --- forces --------------------------------------------------------------
    def _rebuild(self):
        tree = cKDTree(self.pos)
        pairs = tree.query_pairs(CUTOFF + SKIN, output_type="ndarray")
        self.pair_i = pairs[:, 0] if len(pairs) else np.zeros(0, dtype=int)
        self.pair_j = pairs[:, 1] if len(pairs) else np.zeros(0, dtype=int)
        self.reference = self.pos.copy()

    def _forces(self):
        delta = self.pos[self.pair_i] - self.pos[self.pair_j]
        r2 = np.einsum("ij,ij->i", delta, delta)
        near = r2 < CUTOFF * CUTOFF
        delta, r2 = delta[near], np.maximum(r2[near], 0.36)
        inv6 = r2 ** -3
        magnitude = 24 * (2 * inv6 * inv6 - inv6) / r2
        f = delta * magnitude[:, None]
        force = np.zeros_like(self.pos)
        i, j = self.pair_i[near], self.pair_j[near]
        for axis in range(3):
            force[:, axis] += np.bincount(i, f[:, axis], self.n) - np.bincount(j, f[:, axis], self.n)
        return force

    def _walls(self):
        """Parois thermiques : un atome qui touche une paroi repart avec une vitesse
        tirée à la température de la paroi (réflexion diffuse)."""
        lower = self.pos < WALL_MARGIN
        upper = self.pos > self.box - WALL_MARGIN
        if not (lower.any() or upper.any()):
            return
        sigma_v = math.sqrt(self.wall_t)
        for axis in range(3):
            for side, bound in ((0, WALL_MARGIN), (1, self.box[axis] - WALL_MARGIN)):
                out = lower[:, axis] if side == 0 else upper[:, axis]
                if not out.any():
                    continue
                index = np.nonzero(out)[0]
                old_normal = np.abs(self.vel[index, axis])
                new_normal = self.rng.rayleigh(sigma_v, len(index))
                self.vel[index] = self.rng.normal(0, sigma_v, (len(index), 3))
                self.vel[index, axis] = new_normal if side == 0 else -new_normal
                self.pos[index, axis] = 2 * bound - self.pos[index, axis]
                self.impulse += float(np.sum(old_normal + new_normal))
                for k in index[:4]:
                    if len(self.hits) < 60:
                        self.hits.append(self.pos[k].copy())

    def step(self, count):
        for _ in range(count):
            self.vel += 0.5 * DT * self.force
            self.pos += DT * self.vel
            self._walls()
            if np.max(np.sum((self.pos - self.reference) ** 2, axis=1)) > (SKIN / 2) ** 2:
                self._rebuild()
            self.force = self._forces()
            self.vel += 0.5 * DT * self.force
            # Température de chaque atome, lissée sur ~0,2 τ pour qu'elle se lise.
            self.atom_t += 0.02 * (np.einsum("ij,ij->i", self.vel, self.vel) / 3 - self.atom_t)
            self.time += DT

    # --- mesures ---------------------------------------------------------------
    def temperature(self):
        return float(np.mean(np.einsum("ij,ij->i", self.vel, self.vel)) / 3)

    def _classify(self):
        """Chaque atome : gaz (presque pas de voisins), liquide (entouré, mais ses
        voisins changent) ou solide (il garde les mêmes voisins : il ne fait que
        vibrer à sa place). Le critère suit les voisins, pas le déplacement :
        un cristal qui dérive d'un bloc reste un cristal."""
        tree = cKDTree(self.pos)
        pairs = tree.query_pairs(NEIGHBOR_SHELL, output_type="ndarray")
        coordination = np.zeros(self.n, dtype=int)
        if len(pairs):
            coordination += np.bincount(pairs[:, 0], minlength=self.n) + np.bincount(pairs[:, 1], minlength=self.n)
        if not hasattr(self, "kept"):
            self.kept = np.ones(self.n)
            self.snapshot_pairs = pairs
        if self.time - self.snapshot_time >= 1.5 and len(self.snapshot_pairs):
            old = self.snapshot_pairs
            distance = np.sqrt(np.sum((self.pos[old[:, 0]] - self.pos[old[:, 1]]) ** 2, axis=1))
            still = (distance < NEIGHBOR_SHELL + 0.1).astype(float)
            total = np.bincount(old[:, 0], minlength=self.n) + np.bincount(old[:, 1], minlength=self.n)
            stayed = (np.bincount(old[:, 0], still, self.n) + np.bincount(old[:, 1], still, self.n))
            self.kept = np.where(total > 0, stayed / np.maximum(total, 1), 0.0)
            self.snapshot_pairs = pairs
            self.snapshot_time = self.time
        phase = np.where(coordination < 4, 0, np.where((self.kept >= 0.85) & (coordination >= 7), 2, 1))
        self.phase = phase
        counts = np.bincount(phase, minlength=3) / self.n
        return float(counts[2]), float(counts[1]), float(counts[0]), coordination

    def advance(self, real_dt):
        """Avance de real_dt secondes réelles ; retourne les changements d'état (événements)."""
        steps = max(1, int(round(real_dt * STEPS_PER_SECOND)))
        start_impulse, start_time = self.impulse, self.time
        self.hits = []
        self.step(steps)
        area = 2 * (self.box[0] * self.box[1] + self.box[1] * self.box[2] + self.box[0] * self.box[2])
        instant = (self.impulse - start_impulse) / max(1e-9, (self.time - start_time) * area)
        self.pressure += 0.1 * (instant - self.pressure)
        solid, liquid, gas = self._classify()[:3]
        self.fractions = (solid, liquid, gas)
        return self._phase_change(solid, liquid, gas)

    def supercritical(self):
        """Au-delà du point critique ? Avec ±3 % d'hystérésis : près de T_c, la
        température fluctue, et l'état ne doit pas clignoter."""
        temperature = self.temperature()
        current = getattr(self, "_supercritical", False)
        if current and temperature < 0.97 * CRITICAL_T:
            current = False
        elif not current and temperature > 1.03 * CRITICAL_T:
            current = True
        self._supercritical = current
        return current

    def _phase_change(self, solid, liquid, gas):
        if self.supercritical():
            names = {"solide": solid, "fluide supercritique": liquid + gas}
        else:
            names = {"solide": solid, "liquide": liquid, "gaz": gas}
        leader = max(names, key=names.get)
        if names[leader] < 0.6 or leader == self.dominant:
            return []
        before, self.dominant = self.dominant, leader
        verbs = {("solide", "liquide"): "Le solide fond", ("liquide", "gaz"): "Le liquide s'évapore",
                 ("gaz", "liquide"): "Le gaz se condense en liquide", ("liquide", "solide"): "Le liquide gèle",
                 ("solide", "gaz"): "Le solide se sublime", ("gaz", "solide"): "Le gaz se condense en solide",
                 ("liquide", "fluide supercritique"): "Au-delà du point critique",
                 ("gaz", "fluide supercritique"): "Au-delà du point critique",
                 ("fluide supercritique", "liquide"): "Sous le point critique, un liquide réapparaît",
                 ("fluide supercritique", "gaz"): "Sous le point critique, le fluide redevient gaz",
                 ("solide", "fluide supercritique"): "Le solide fond en fluide supercritique",
                 ("fluide supercritique", "solide"): "Le fluide se solidifie"}
        temperature = self.temperature() * self.epsilon_k
        if leader == "fluide supercritique" or before == "fluide supercritique":
            critical = self.critical_k
            real = (f" · point critique du modèle : {CRITICAL_T * self.epsilon_k:.0f} K"
                    + (f" ; {self.name} réel : {critical:.1f} K" if critical else ""))
        else:
            reference = {"liquide": self.melting_k if before == "solide" else self.boiling_k,
                         "gaz": self.boiling_k, "solide": self.melting_k}.get(leader)
            real = (f" · {self.name} réel : {'fusion' if leader != 'gaz' else 'ébullition'} à "
                    f"{reference:.1f} K (1 bar)" if reference else "")
        return [{
            "kind": "phase",
            "title": f"{verbs.get((before, leader), 'Changement d état')} : {names[leader] * 100:.0f} % {leader}",
            "formula": f"T = {temperature:.1f} K · P = {self.pressure_bar():.1f} bar{real}",
            "detail": ("Personne ne l'a décidé : l'agitation thermique a vaincu (ou cédé à) l'attraction "
                       "de van der Waals entre voisins, V(r) = 4ε[(σ/r)¹² − (σ/r)⁶]."),
        }]

    # --- conversions vers les unités réelles ----------------------------------------
    def pressure_bar(self):
        epsilon_j = self.epsilon_k * sc.k
        sigma_m = self.sigma_a * 1e-10
        return self.pressure * epsilon_j / sigma_m ** 3 / 1e5

    def density_kg_m3(self):
        volume = float(np.prod(self.box - 2 * WALL_MARGIN + 1)) * (self.sigma_a * 1e-10) ** 3
        return self.n * self.mass_u * sc.atomic_mass / volume

    def time_ps(self):
        tau = self.sigma_a * 1e-10 * math.sqrt(self.mass_u * sc.atomic_mass / (self.epsilon_k * sc.k))
        return self.time * tau * 1e12
