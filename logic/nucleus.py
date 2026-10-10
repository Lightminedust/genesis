"""Physique du noyau : énergie de liaison, taille, agencement, désintégrations.

Toutes les grandeurs sont en unités physiques : femtomètres (1 fm = 1e-15 m)
et MeV. Le client JavaFX place le noyau à sa vraie échelle, cent mille fois
plus petit que le nuage électronique.
"""

import math

# Formule de Weizsäcker (modèle de la goutte liquide), en MeV
A_VOLUME = 15.75
A_SURFACE = 17.80
A_COULOMB = 0.711
A_ASYMMETRY = 23.70
A_PAIRING = 11.18

R0_FM = 1.2                 # rayon nucléaire : R = r0 · A^(1/3), mesuré par diffusion d'électrons
NUCLEON_RADIUS_FM = 0.84    # rayon de charge du proton (CODATA 2018 : 0,8409 fm)
STRONG_FORCE_RANGE_FM = 2.0 # portée de l'interaction forte entre nucléons

NEUTRON_HYDROGEN_GAP_MEV = 0.78235   # (m_n - m_H) c² : moteur de la désintégration β-
TWO_ELECTRON_MASSES_MEV = 1.021998   # 2 m_e c² : seuil de la désintégration β+
HELIUM4_BINDING_MEV = 28.2957        # énergie de liaison mesurée de la particule α

TERM_DESCRIPTIONS = {
    "volume": ("Volume", "force forte : chaque nucléon est lié à ses voisins"),
    "surface": ("Surface", "les nucléons de surface ont moins de voisins"),
    "coulomb": ("Coulomb", "répulsion électrique entre protons"),
    "asymmetry": ("Asymétrie", "principe de Pauli : N ≠ Z coûte de l'énergie"),
    "pairing": ("Appariement", "les nucléons appariés sont plus liés"),
}


def binding_energy(z, n):
    """Énergie de liaison du noyau par la formule semi-empirique de masse.

    Retourne (B en MeV, B/A en MeV, termes détaillés) pour pouvoir afficher
    le calcul et pas seulement son résultat.
    """
    a = z + n
    terms = {"volume": 0.0, "surface": 0.0, "coulomb": 0.0, "asymmetry": 0.0, "pairing": 0.0}
    if a < 2 or z < 0 or n < 0:
        # Un nucléon isolé n'a rien à quoi se lier.
        return 0.0, 0.0, terms

    terms["volume"] = A_VOLUME * a
    terms["surface"] = -A_SURFACE * a ** (2.0 / 3.0)
    terms["coulomb"] = -A_COULOMB * z * (z - 1) / a ** (1.0 / 3.0)
    terms["asymmetry"] = -A_ASYMMETRY * (a - 2 * z) ** 2 / a

    if z % 2 == 0 and n % 2 == 0:
        terms["pairing"] = A_PAIRING / math.sqrt(a)
    elif z % 2 == 1 and n % 2 == 1:
        terms["pairing"] = -A_PAIRING / math.sqrt(a)

    total = sum(terms.values())
    return total, total / a, terms


def nuclear_radius_fm(a):
    """R = r0 · A^(1/3) : le volume du noyau est proportionnel au nombre de
    nucléons, comme une goutte de liquide incompressible."""
    return R0_FM * a ** (1.0 / 3.0) if a > 0 else 0.0


def _halton(index, base):
    """Suite de Halton : des points bien répartis, et déterministes."""
    fraction, result = 1.0, 0.0
    while index > 0:
        fraction /= base
        result += fraction * (index % base)
        index //= base
    return result


def _interleave(protons, neutrons):
    """Répartit les protons régulièrement parmi les nucléons (Bresenham)."""
    total = protons + neutrons
    kinds = []
    for index in range(total):
        before = (index * protons) // total
        after = ((index + 1) * protons) // total
        kinds.append("proton" if after > before else "neutron")
    return kinds


def nucleon_layout_3d(protons, neutrons):
    """Positions 3D des nucléons, en femtomètres, remplissant une sphère de
    rayon R = r0 · A^(1/3) avec une densité uniforme.

    Déterministe : le même noyau donne toujours la même image, et ajouter un
    nucléon ne bouleverse pas les autres (propriété de préfixe de Halton).
    """
    total = protons + neutrons
    if total == 0:
        return []
    kinds = _interleave(protons, neutrons)
    if total == 1:
        return [(kinds[0], 0.0, 0.0, 0.0)]

    inner = max(0.0, nuclear_radius_fm(total) - NUCLEON_RADIUS_FM * 0.55)
    layout = []
    for index, kind in enumerate(kinds):
        u = _halton(index + 1, 2)
        v = _halton(index + 1, 3)
        w = _halton(index + 1, 5)
        radius = inner * u ** (1.0 / 3.0)        # u^(1/3) : densité uniforme en volume
        cos_theta = 1.0 - 2.0 * v
        sin_theta = math.sqrt(max(0.0, 1.0 - cos_theta * cos_theta))
        phi = 2.0 * math.pi * w
        layout.append((kind,
                       radius * sin_theta * math.cos(phi),
                       radius * cos_theta,
                       radius * sin_theta * math.sin(phi)))
    return layout


def target_ratio(z):
    """Ratio N/Z visé par la vallée de stabilité.

    Environ 1.0 pour les éléments légers, puis croissant car la répulsion
    coulombienne entre protons doit être compensée par plus de neutrons.
    """
    if z <= 20:
        return 1.0
    return 1.0 + 0.0075 * (z - 20)


# ----------------------------------------------------------------------
# Désintégrations
# ----------------------------------------------------------------------
def q_beta_minus(z, n):
    """n -> p + e- + antineutrino. Q = (m_n - m_H) c² + B(Z+1) - B(Z)."""
    if n < 1:
        return -math.inf
    return NEUTRON_HYDROGEN_GAP_MEV + binding_energy(z + 1, n - 1)[0] - binding_energy(z, n)[0]


def q_electron_capture(z, n):
    """p + e- -> n + neutrino. Q = B(Z-1) - B(Z) - (m_n - m_H) c²."""
    if z < 1:
        return -math.inf
    return binding_energy(z - 1, n + 1)[0] - binding_energy(z, n)[0] - NEUTRON_HYDROGEN_GAP_MEV


def q_beta_plus(z, n):
    """p -> n + e+ + neutrino : il faut en plus créer un positon, d'où 2 m_e c²."""
    return q_electron_capture(z, n) - TWO_ELECTRON_MASSES_MEV


def q_alpha(z, n):
    """Émission d'un noyau d'hélium 4. Q = B(Z-2, N-2) + B(α) - B(Z, N)."""
    if z < 3 or n < 3:
        return -math.inf
    return binding_energy(z - 2, n - 2)[0] + HELIUM4_BINDING_MEV - binding_energy(z, n)[0]


_DECAYS = {
    "beta-": {
        "dz": +1, "dn": -1, "de": 0,
        "emitted": ["électron (β-)", "antineutrino électronique"],
        "rule": "un neutron se change en proton : n → p + e⁻ + ν̄",
        "estimate": q_beta_minus,
    },
    "beta+": {
        "dz": -1, "dn": +1, "de": 0,
        "emitted": ["positon (β+)", "neutrino électronique"],
        "rule": "un proton se change en neutron : p → n + e⁺ + ν",
        "estimate": q_beta_plus,
    },
    "CE": {
        "dz": -1, "dn": +1, "de": -1,
        "emitted": ["neutrino électronique", "rayon X (réarrangement du cortège)"],
        "rule": "capture électronique : p + e⁻ → n + ν",
        "estimate": q_electron_capture,
    },
    "alpha": {
        "dz": -2, "dn": -2, "de": 0,
        "emitted": ["particule α (noyau d'hélium 4)"],
        "rule": "le noyau éjecte 2 protons et 2 neutrons liés",
        "estimate": q_alpha,
    },
}


def q_neutron_emission(z, n):
    """Q = -S_n : l'énergie libérée quand le noyau non lié perd un neutron."""
    if n < 1:
        return -math.inf
    return binding_energy(z, n - 1)[0] - binding_energy(z, n)[0]


def q_proton_emission(z, n):
    """Q = -S_p, à la différence de masse neutron/hydrogène près."""
    if z < 1:
        return -math.inf
    return binding_energy(z - 1, n)[0] - binding_energy(z, n)[0]


_DECAYS["n"] = {
    "dz": 0, "dn": -1, "de": 0,
    "emitted": ["neutron"],
    "rule": "noyau non lié : un neutron s'en échappe directement",
    "estimate": q_neutron_emission,
}
_DECAYS["p"] = {
    "dz": -1, "dn": 0, "de": 0,
    "emitted": ["proton"],
    "rule": "noyau non lié : un proton s'en échappe directement",
    "estimate": q_proton_emission,
}
_DECAYS["2n"] = {
    "dz": 0, "dn": -2, "de": 0,
    "emitted": ["neutron", "neutron"],
    "rule": "noyau non lié : deux neutrons s'en échappent ensemble",
    "estimate": lambda z, n: q_neutron_emission(z, n) + q_neutron_emission(z, n - 1),
}
_DECAYS["2p"] = {
    "dz": -2, "dn": 0, "de": 0,
    "emitted": ["proton", "proton"],
    "rule": "noyau non lié : deux protons s'en échappent ensemble",
    "estimate": lambda z, n: q_proton_emission(z, n) + q_proton_emission(z - 1, n),
}
_DECAYS["beta-n"] = {
    "dz": +1, "dn": -2, "de": 0,
    "emitted": ["électron (β-)", "antineutrino électronique", "neutron"],
    "rule": "β⁻ vers un état du fils non lié, qui expulse aussitôt un neutron",
    "estimate": q_beta_minus,
}
_DECAYS["beta+p"] = {
    "dz": -2, "dn": +1, "de": 0,
    "emitted": ["positon (β+)", "neutrino électronique", "proton"],
    "rule": "β⁺ vers un état du fils non lié, qui expulse aussitôt un proton",
    "estimate": q_beta_plus,
}
_DECAYS["IT"] = {
    "dz": 0, "dn": 0, "de": 0,
    "emitted": ["photon γ"],
    "rule": "transition isomérique : le noyau excité retombe à son état fondamental en émettant un γ",
    "estimate": lambda z, n: 0.0,
}
_DECAYS["SF"] = {
    "dz": 0, "dn": 0, "de": 0,
    "emitted": ["deux fragments de fission", "neutrons"],
    "rule": "fission spontanée : la répulsion électrique l'emporte, le noyau se scinde",
    "estimate": lambda z, n: 0.0,
}


def fission_fragment(z, n):
    """Fragment lourd d'une fission spontanée : partage asymétrique typique
    des actinides (pic lourd vers A ≈ 140, ~58 % de la masse), même rapport
    N/Z que le parent, deux neutrons prompts."""
    a = z + n - 2
    heavy_a = round(a * 0.58)
    heavy_z = round(z * heavy_a / a)
    return heavy_z, heavy_a - heavy_z


def resolve_decay(z, n, predicted_mode):
    """Transforme le mode prédit en désintégration concrète, avec son bilan.

    Priorité à la mesure : si le nucléide est tabulé, son mode, son énergie
    libérée et sa demi-vie sont réels. Sinon le mode vient de la position du
    noyau par rapport à la vallée de stabilité, et Q de Weizsäcker, affiché
    comme estimation.

    Retourne un dict, ou None si le noyau est stable.
    """
    # Import local : nuclides n'a besoin de rien ici, et l'inverse non plus.
    from nuclides import lookup

    measured = lookup(z, z + n)
    if measured and measured["mode"] in _DECAYS:
        mode = measured["mode"]
    elif z == 0 and n > 0:
        mode = "beta-"
    elif predicted_mode == "beta+ / CE":
        # Sans mesure, on tranche avec l'estimation : si Q ne suffit pas à
        # créer un positon, seule la capture électronique reste possible.
        mode = "beta+" if q_beta_plus(z, n) > 0 else "CE"
    elif predicted_mode in _DECAYS:
        mode = predicted_mode
    else:
        return None

    spec = _DECAYS[mode]
    event = {
        "mode": mode,
        "dz": spec["dz"], "dn": spec["dn"], "de": spec["de"],
        "emitted": list(spec["emitted"]),
        "rule": spec["rule"],
    }
    if measured and measured["q_mev"] is not None:
        event.update(q_mev=measured["q_mev"], q_source="masses mesurées (AME2020)",
                     half_life=measured["half_life"], model_fails=False)
    else:
        estimate = spec["estimate"](z, n)
        event.update(q_mev=estimate, q_source="estimation Weizsäcker (± quelques MeV)",
                     half_life=measured["half_life"] if measured else "",
                     # Le noyau est hors de la vallée, il se désintègre donc
                     # bien : un Q estimé négatif est un échec du modèle.
                     model_fails=estimate <= 0)
    return event
