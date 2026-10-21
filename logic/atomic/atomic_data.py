"""Données mesurées des atomes à plusieurs électrons.

Pour ces atomes, aucune formule ne prédit fidèlement les niveaux ni les
probabilités de transition. Le simulateur s'appuie donc sur des mesures,
et le dit.

AFFINITÉ ÉLECTRONIQUE (eV) : énergie libérée quand l'atome neutre capture un
électron supplémentaire. Positive : l'anion X⁻ existe. Absente de la table
ou nulle : l'atome ne retient pas d'électron de plus (He, Be, N, Ne, Mg...).
Aucun atome isolé ne retient DEUX électrons supplémentaires : les dianions
atomiques n'existent pas en phase gazeuse (O²⁻ n'existe que dans un cristal).
Source : NIST / Hotop & Lineberger, lues dans la base de mendeleev.

ÉNERGIES D'IONISATION : celles de chaque ion, de l'atome neutre au noyau
nu (Fe : 7,90 eV, puis 16,2, 30,7, 54,9...), NIST via la base mendeleev.

RAIES DE RÉSONANCE : raies dont le niveau bas est le fondamental. Ce sont les
seules qu'un atome au repos peut ABSORBER. Durée de vie du niveau haut (ns)
quand elle est mesurée.
"""

from common import elements_db

_DB = elements_db.DB   # verrouillée : plusieurs threads la lisent

# Affinités électroniques mesurées (NIST, via mendeleev). Négative : l'anion
# n'est pas lié (N : -1,4 eV ; He : -19,7 eV). Absente : non mesurée.
ELECTRON_AFFINITY_EV = {z: ea for z, ea in _DB.execute(
    "select atomic_number, electron_affinity from elements where electron_affinity is not null")}

# Gaz rares, alcalino-terreux à sous-couche pleine... dont l'anion n'existe pas
# même si la base ne donne pas de valeur chiffrée.
NO_STABLE_ANION = {2, 4, 7, 10, 12, 18, 25, 30, 36, 48, 54, 80, 86}

# (Z, charge de l'ion) -> énergie pour lui arracher un électron (NIST ASD).
_IONIZATION = {(z, q): energy for z, q, energy in _DB.execute(
    "select atomic_number, ion_charge, ionization_energy from ionizationenergies "
    "where ionization_energy is not null")}


def ionization_energy_ev(z, charge):
    """Énergie mesurée pour arracher un électron à l'ion de charge `charge`
    (0 : atome neutre, 1 : ion +1...). Pour un anion, c'est l'affinité.
    None si la mesure n'existe pas."""
    if charge == -1:
        affinity = ELECTRON_AFFINITY_EV.get(z)
        return affinity if affinity and affinity > 0 else None
    return _IONIZATION.get((z, charge))


# Z -> {longueur d'onde (nm, air) : durée de vie du niveau haut en ns, ou None}
RESONANCE_LINES = {
    3: {670.78: 27.1},
    4: {234.86: 1.8},
    5: {249.68: None},
    11: {588.995: 16.2, 589.592: 16.3},
    12: {285.21: 2.0},
    13: {394.40: 6.8},
    19: {766.49: 26.2, 769.90: 26.5},
    20: {422.67: 4.6},
    24: {425.43: None},
    25: {403.08: None},
    26: {371.99: None},
    29: {324.75: 7.0, 327.40: 7.2},
    30: {213.86: 1.4},
    31: {403.30: None},
    37: {780.03: 26.2, 794.76: 27.7},
    38: {460.73: 5.2},
    47: {328.07: 6.7, 338.29: 7.2},
    48: {228.80: 1.7},
    49: {410.18: None},
    55: {852.11: 30.5, 894.35: 34.8},
    56: {553.55: 8.4},
    79: {242.80: None, 267.60: None},
    80: {253.65: 125.0},
    81: {377.57: None},
}

# Doublets de structure fine des atomes à un électron de valence : la même
# transition np -> ns se dédouble parce que le spin de l'électron (1/2)
# s'ajoute (j = l + 1/2) ou se retranche (j = l - 1/2) à son moment orbital.
# Le couplage spin-orbite sépare les deux niveaux : c'est le spin, rendu visible.
SPIN_ORBIT = "doublet du spin : spin de l'électron parallèle (j = 3/2) ou opposé (j = 1/2) à son orbite"
_DOUBLETS = {
    11: {588.995: "3s ← 3p₃/₂", 589.592: "3s ← 3p₁/₂"},
    19: {766.49: "4s ← 4p₃/₂", 769.90: "4s ← 4p₁/₂"},
    29: {324.75: "4s ← 4p₃/₂", 327.40: "4s ← 4p₁/₂"},
    37: {780.03: "5s ← 5p₃/₂", 794.76: "5s ← 5p₁/₂"},
    47: {328.07: "5s ← 5p₃/₂", 338.29: "5s ← 5p₁/₂"},
    55: {852.11: "6s ← 6p₃/₂", 894.35: "6s ← 6p₁/₂"},
}


def resonance_term(z, wavelength_nm):
    """(niveaux, explication) si la raie appartient à un doublet de spin, sinon None."""
    label = _DOUBLETS.get(z, {}).get(wavelength_nm)
    return (label, SPIN_ORBIT) if label else None


# Durée de vie retenue quand elle n'est pas mesurée : ordre de grandeur des
# niveaux excités permis (quelques ns à quelques dizaines de ns).
TYPICAL_LIFETIME_NS = 10.0


def anion_is_bound(z):
    """True si X⁻ existe, False s'il n'existe pas, None si la table l'ignore."""
    if ELECTRON_AFFINITY_EV.get(z) is not None:
        return ELECTRON_AFFINITY_EV[z] > 0
    if z in NO_STABLE_ANION:
        return False
    return None


def resonance_lines(z):
    return RESONANCE_LINES.get(z, {})


def matching_resonance(z, wavelength_nm, tolerance_nm):
    """Raie de résonance la plus proche, si elle est dans la tolérance."""
    best = None
    for line in resonance_lines(z):
        gap = abs(line - wavelength_nm)
        if gap <= tolerance_nm and (best is None or gap < abs(best - wavelength_nm)):
            best = line
    return best
