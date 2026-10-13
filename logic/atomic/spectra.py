"""Niveaux d'énergie, raies spectrales et photons.

Ce que la physique permet de CALCULER exactement, et ce qu'elle ne permet pas :

  - Un système à UN électron (H, He+, Li2+...) suit exactement la formule de
    Bohr/Rydberg. On la calcule, correction de masse réduite comprise : le
    noyau n'est pas infiniment lourd, il recule. Hα en sort à 656,28 nm dans
    l'air, comme au laboratoire.

  - Un atome à PLUSIEURS électrons n'a pas de formule fermée : la répulsion
    entre électrons rend le problème insoluble analytiquement. Plutôt que
    d'inventer des raies, on s'appuie sur les longueurs d'onde MESURÉES
    (base NIST) et sur les énergies d'ionisation mesurées. Le modèle de
    Slater reste affiché à côté, avec son écart à la mesure.

Conventions : longueurs d'onde dans l'air au-dessus de 200 nm, dans le vide
en dessous (usage spectroscopique standard).
"""

import math

from scipy import constants as sc

# Constantes CODATA, lues dans scipy.constants plutôt que recopiées.
HC_EV_NM = sc.h * sc.c / sc.e * 1e9                                         # h c, en eV·nm
RYDBERG_EV = sc.physical_constants["Rydberg constant times hc in eV"][0]  # masse du noyau infinie
ELECTRON_MASS_U = sc.physical_constants["electron mass in u"][0]
PROTON_MASS_U = sc.physical_constants["proton mass in u"][0]
NEUTRON_MASS_U = sc.physical_constants["neutron mass in u"][0]
MEV_PER_U = sc.physical_constants["atomic mass constant energy equivalent in MeV"][0]

VISIBLE_MIN_NM = 380.0
VISIBLE_MAX_NM = 780.0

SERIES = {1: "Lyman", 2: "Balmer", 3: "Paschen", 4: "Brackett", 5: "Pfund", 6: "Humphreys"}
GREEK = ["α", "β", "γ", "δ", "ε", "ζ"]

# Premières énergies d'ionisation mesurées, en eV (NIST ASD).
IONIZATION_EV = {
    1: 13.598, 2: 24.587, 3: 5.392, 4: 9.323, 5: 8.298, 6: 11.260, 7: 14.534,
    8: 13.618, 9: 17.423, 10: 21.565, 11: 5.139, 12: 7.646, 13: 5.986, 14: 8.152,
    15: 10.487, 16: 10.360, 17: 12.968, 18: 15.760, 19: 4.341, 20: 6.113,
    21: 6.561, 22: 6.828, 23: 6.746, 24: 6.767, 25: 7.434, 26: 7.902, 27: 7.881,
    28: 7.640, 29: 7.726, 30: 9.394, 31: 5.999, 32: 7.899, 33: 9.789, 34: 9.752,
    35: 11.814, 36: 14.000, 37: 4.177, 38: 5.695, 39: 6.217, 40: 6.634,
    41: 6.759, 42: 7.092, 43: 7.28, 44: 7.361, 45: 7.459, 46: 8.337, 47: 7.576,
    48: 8.994, 49: 5.786, 50: 7.344, 51: 8.608, 52: 9.010, 53: 10.451,
    54: 12.130, 55: 3.894, 56: 5.212, 79: 9.226, 80: 10.438, 82: 7.417,
    86: 10.749, 92: 6.194,
}

# Raies d'émission fortes des atomes neutres, longueur d'onde dans l'air (nm)
# et intensité relative indicative. Source : NIST ASD, raies persistantes.
# Ce sont les raies qu'on observe au test de flamme ou dans une lampe à
# décharge : le sodium jaune, le néon rouge-orangé, le mercure bleu-vert.
REFERENCE_LINES = {
    2: [(587.56, 1.0), (667.82, 0.5), (706.52, 0.4), (501.57, 0.5),
        (492.19, 0.3), (471.31, 0.3), (447.15, 0.6), (388.86, 0.5)],
    3: [(670.78, 1.0), (610.36, 0.3), (460.29, 0.1)],
    4: [(234.86, 1.0)],
    5: [(249.77, 1.0), (249.68, 0.8)],
    6: [(247.86, 1.0), (193.09, 0.8)],
    7: [(746.83, 1.0), (744.23, 0.7), (742.36, 0.5)],
    8: [(777.19, 1.0), (777.42, 0.8), (777.54, 0.6), (844.64, 0.6)],
    9: [(685.60, 1.0), (703.75, 0.6), (690.25, 0.5)],
    10: [(640.22, 1.0), (585.25, 0.6), (703.24, 0.6), (614.31, 0.5), (633.44, 0.4),
         (650.65, 0.4), (692.95, 0.4), (659.90, 0.3)],
    11: [(588.995, 1.0), (589.592, 0.9), (330.24, 0.1), (568.82, 0.1), (498.28, 0.05)],
    12: [(285.21, 1.0), (383.83, 0.5), (518.36, 0.5), (517.27, 0.4), (516.73, 0.3)],
    13: [(396.15, 1.0), (394.40, 0.9), (309.27, 0.6), (308.22, 0.5)],
    14: [(251.61, 1.0), (288.16, 0.9)],
    18: [(763.51, 1.0), (811.53, 0.9), (750.39, 0.8), (696.54, 0.6), (706.72, 0.5),
         (738.40, 0.5), (772.38, 0.5), (794.82, 0.5)],
    19: [(766.49, 1.0), (769.90, 0.9), (404.41, 0.2), (404.72, 0.15)],
    20: [(422.67, 1.0), (445.48, 0.3), (558.88, 0.2), (616.22, 0.2), (643.91, 0.2)],
    24: [(425.43, 1.0), (427.48, 0.9), (428.97, 0.8)],
    25: [(403.08, 1.0), (403.31, 0.8), (403.45, 0.6)],
    26: [(371.99, 1.0), (373.49, 0.8), (385.99, 0.7), (404.58, 0.5), (438.35, 0.5),
         (440.48, 0.3)],
    28: [(341.48, 1.0), (352.45, 0.9)],
    29: [(324.75, 1.0), (327.40, 0.8), (521.82, 0.4), (510.55, 0.3), (515.32, 0.3)],
    30: [(213.86, 1.0), (481.05, 0.5), (334.50, 0.4), (472.22, 0.4), (468.01, 0.3),
         (636.23, 0.3)],
    31: [(417.20, 1.0), (403.30, 0.8)],
    36: [(811.29, 1.0), (760.15, 0.8), (587.09, 0.6), (557.03, 0.5), (431.96, 0.3)],
    37: [(780.03, 1.0), (794.76, 0.9), (420.18, 0.2), (421.55, 0.15)],
    38: [(460.73, 1.0), (707.00, 0.2)],
    47: [(328.07, 1.0), (338.29, 0.8), (520.91, 0.3), (546.55, 0.3)],
    48: [(228.80, 1.0), (643.85, 0.6), (508.58, 0.5), (479.99, 0.4)],
    49: [(451.13, 1.0), (410.18, 0.8)],
    54: [(823.16, 1.0), (881.94, 0.8), (467.12, 0.5), (462.43, 0.4)],
    55: [(852.11, 1.0), (894.35, 0.9), (455.53, 0.2), (459.32, 0.1)],
    56: [(553.55, 1.0)],
    79: [(242.80, 1.0), (267.60, 0.9)],
    80: [(253.65, 1.0), (546.07, 0.9), (435.83, 0.8), (404.66, 0.5), (576.96, 0.4),
         (579.07, 0.4)],
    81: [(535.05, 1.0), (377.57, 0.8)],
    82: [(405.78, 1.0), (368.35, 0.6)],
}


def air_refractive_index(vacuum_nm):
    """Indice de l'air sec standard, formule d'Edlén (1966)."""
    sigma_sq = (1000.0 / vacuum_nm) ** 2  # nombre d'onde en µm^-1, au carré
    return 1.0 + 1e-8 * (8342.13 + 2406030.0 / (130.0 - sigma_sq)
                         + 15997.0 / (38.9 - sigma_sq))


def vacuum_to_reported_nm(vacuum_nm):
    """Longueur d'onde telle que la publient les tables : air au-delà de 200 nm."""
    if vacuum_nm < 200.0:
        return vacuum_nm
    return vacuum_nm / air_refractive_index(vacuum_nm)


def reported_to_vacuum_nm(reported_nm):
    """Inverse approché (l'indice varie trop peu pour qu'une itération ne suffise pas)."""
    if reported_nm < 200.0:
        return reported_nm
    return reported_nm * air_refractive_index(reported_nm)


def photon_energy_ev(reported_nm):
    """E = h c / λ, λ ramené dans le vide."""
    return HC_EV_NM / reported_to_vacuum_nm(reported_nm)


def region(reported_nm):
    if reported_nm < VISIBLE_MIN_NM:
        return "UV"
    if reported_nm > VISIBLE_MAX_NM:
        return "IR"
    return "visible"


def nuclear_mass_u(z, n, binding_mev):
    """Masse du noyau : nucléons séparés, moins le défaut de masse."""
    return z * PROTON_MASS_U + n * NEUTRON_MASS_U - binding_mev / MEV_PER_U


def reduced_mass_factor(z, n, binding_mev):
    """μ / m_e = 1 / (1 + m_e / M). Pour l'hydrogène : 0,999456.

    Ignorer ce facteur décalerait Hα de 0,36 nm : visible sur un vrai
    spectromètre, donc pas négligeable pour un simulateur qui se veut exact.
    """
    if z + n == 0:
        return 1.0
    return 1.0 / (1.0 + ELECTRON_MASS_U / nuclear_mass_u(z, n, binding_mev))


def hydrogenic_level_ev(z, level, mu_factor):
    """E_n = -R_∞ Z² (μ/m_e) / n² : exact pour un système à un électron."""
    return -RYDBERG_EV * z * z * mu_factor / (level * level)


def line_label(z, upper, lower):
    """Nom usuel : Hα pour la raie rouge de l'hydrogène, sinon 'Lyman β'..."""
    series = SERIES.get(lower, f"série n={lower}")
    order = upper - lower - 1
    greek = GREEK[order] if order < len(GREEK) else f"{upper}→{lower}"
    if z == 1 and lower == 2:
        return f"H{greek}"
    return f"{series} {greek}"


def hydrogenic_transition(z, upper, lower, mu_factor):
    """Photon émis entre deux niveaux d'un système à un électron."""
    energy = hydrogenic_level_ev(z, upper, mu_factor) - hydrogenic_level_ev(z, lower, mu_factor)
    vacuum_nm = HC_EV_NM / energy
    return {
        "wavelength_nm": vacuum_to_reported_nm(vacuum_nm),
        "energy_ev": energy,
        "from_n": upper,
        "to_n": lower,
        "label": line_label(z, upper, lower),
        "source": "calcul exact (Rydberg)",
    }


def hydrogenic_spectrum(z, mu_factor, highest=7):
    """Toutes les raies entre niveaux 1..highest, séries de Lyman à Humphreys."""
    lines = []
    for lower in range(1, highest):
        for upper in range(lower + 1, highest + 1):
            line = hydrogenic_transition(z, upper, lower, mu_factor)
            line["intensity"] = 1.0
            lines.append(line)
    return lines


def reference_spectrum(z):
    """Raies mesurées de l'atome neutre, si la table en contient."""
    lines = []
    for reported_nm, intensity in REFERENCE_LINES.get(z, []):
        lines.append({
            "wavelength_nm": reported_nm,
            "energy_ev": photon_energy_ev(reported_nm),
            "intensity": intensity,
            "label": f"{reported_nm:.2f} nm",
            "source": "mesure NIST",
            "from_n": 0,
            "to_n": 0,
        })
    return lines


def wavelength_from_energy(energy_ev):
    """Longueur d'onde publiée d'un photon d'énergie donnée."""
    if energy_ev <= 0:
        return math.inf
    return vacuum_to_reported_nm(HC_EV_NM / energy_ev)
