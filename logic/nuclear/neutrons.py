"""Un neutron lent frappe un noyau : ce que la mesure dit qu'il peut arriver.

Le neutron est thermique : 0,0253 eV, soit 2 200 m/s, la vitesse à laquelle
toutes les sections efficaces de référence sont mesurées. Sans charge, il ne
sent pas la barrière de Coulomb : même lent, il atteint le noyau.

Sections efficaces : diffusion et absorption mesurées isotope par isotope,
table de V. F. Sears, « Neutron scattering lengths and cross sections »,
Neutron News 3 (1992) 26, embarquée par le NIST dans `periodictable`. Sears
donne la diffusion d'un noyau LIÉ ; pour un noyau seul, elle se divise par
((A + 1)/A)² (proton : 82 b lié, 20,5 b libre, comme mesuré).

L'absorption regroupe toutes les voies où le neutron reste. Quand une autre
voie que la capture radiative (n,γ) domine, sa part vient des évaluations
thermiques ENDF/B-VIII.0 (Mughabghab, Atlas of Neutron Resonances) : fission
de U-233, U-235, Pu-239 ; (n,α) de Li-6 et B-10 ; (n,p) de He-3 et N-14.
Ailleurs, les voies chargées pèsent moins de quelques pour cent : tout
l'absorbé est compté comme capture, ce qui est dit dans le détail.

Toutes les énergies libérées Q viennent des masses atomiques mesurées
(AME2020) ; si une masse manque, Q n'est pas inventé.
"""

import math

from nuclear import nuclear_data

THERMAL_EV = 0.0253
THERMAL_SPEED_M_S = 2200.0
SOURCE = "Sears 1992 (NIST)"

# Part de l'absorption qui n'est pas une capture radiative, d'après les sections efficaces
# thermiques évaluées (barns) : {(Z, A): (voie, σ de la voie, σ de capture (n,γ))}.
OTHER_CHANNELS = {
    (92, 233): ("fission", 531.2, 45.5),
    (92, 235): ("fission", 585.1, 98.7),
    (94, 239): ("fission", 747.4, 270.3),
    (3, 6): ("alpha", 940.0, 0.0385),     # Li-6 + n → H-3 + α
    (5, 10): ("alpha", 3840.0, 0.5),      # B-10 + n → Li-7 + α
    (2, 3): ("proton", 5333.0, 3e-5),     # He-3 + n → H-3 + p
    (7, 14): ("proton", 1.83, 0.075),     # N-14 + n → C-14 + p
}

# Nombre moyen de neutrons libérés par une fission thermique (ENDF/B-VIII.0).
NU_BAR = {(92, 233): 2.49, (92, 235): 2.43, (94, 239): 2.87}

# Fragments de fission : le pic lourd reste vers A ≈ 140 quel que soit l'actinide
# (couches fermées Z = 50, N = 82 près du fragment lourd) ; largeur ≈ 5 nucléons.
HEAVY_PEAK_A = 139.5
HEAVY_PEAK_WIDTH = 5.0


def cross_sections(z, a):
    """Sections efficaces thermiques du noyau (Z, A) seul, en barns, ou None si elles ne sont pas mesurées.

    Clés : `scattering`, `capture`, `fission`, `alpha`, `proton` (les voies absentes valent 0).
    """
    try:
        import periodictable
        neutron = periodictable.elements[z][a].neutron
    except (ImportError, KeyError, IndexError, AttributeError):
        return None
    bound = neutron.total
    absorption = neutron.absorption
    if bound is None or absorption is None or not math.isfinite(bound) or not math.isfinite(absorption):
        return None
    sigma = {"scattering": bound * (a / (a + 1)) ** 2, "capture": absorption,
             "fission": 0.0, "alpha": 0.0, "proton": 0.0}
    other = OTHER_CHANNELS.get((z, a))
    if other:
        channel, sigma_other, sigma_capture = other
        share = sigma_other / (sigma_other + sigma_capture)
        sigma[channel] = absorption * share
        sigma["capture"] = absorption * (1 - share)
    return sigma


def _mass(z, a):
    return 0.0 if z == 0 and a == 0 else nuclear_data.atomic_mass_u(z, a)


def q_value(z, a, products):
    """Q (MeV) de n + (Z, A) → produits, chacun (Z, A), d'après les masses atomiques mesurées ; None si l'une manque."""
    masses = [_mass(pz, pa) for pz, pa in products]
    target = _mass(z, a)
    neutron = nuclear_data.atomic_mass_u(0, 1)
    if target is None or neutron is None or any(m is None for m in masses):
        return None
    return (target + neutron - sum(masses)) * nuclear_data.MEV_PER_U


def fission_split(rng, z, a, tries=40):
    """Une fission du noyau composé (Z, A + 1) : (fragment lourd, fragment léger, ν), fragments de masse mesurée.

    ν est tiré autour de sa moyenne mesurée ; le fragment lourd autour du pic A ≈ 140 ; la charge se partage
    au prorata de la masse (densité de charge uniforme).
    """
    compound = a + 1
    nu_bar = NU_BAR.get((z, a), 2.5)
    for _ in range(tries):
        nu = math.floor(nu_bar) + (1 if rng.random() < nu_bar - math.floor(nu_bar) else 0)
        rest = compound - nu
        heavy_a = int(round(rng.gauss(HEAVY_PEAK_A, HEAVY_PEAK_WIDTH)))
        heavy_a = max(rest // 2 + 1, min(rest - 70, heavy_a))
        heavy_z = int(round(z * heavy_a / rest))
        light_a, light_z = rest - heavy_a, z - heavy_z
        if nuclear_data.atomic_mass_u(heavy_z, heavy_a) and nuclear_data.atomic_mass_u(light_z, light_a):
            return (heavy_z, heavy_a), (light_z, light_a), nu
    return None


def outcome(rng, z, a):
    """Tire ce qui arrive quand un neutron thermique touche (Z, A) ; None si les sections efficaces manquent.

    Retourne {voie, sigma, total, probability, residual (Z, A), emitted (Z, A) ou None, nu, q_mev}.
    """
    sigma = cross_sections(z, a)
    if sigma is None:
        return None
    total = sum(sigma.values())
    if total <= 0:
        return None
    pick = rng.uniform(0, total)
    for channel in ("scattering", "capture", "fission", "alpha", "proton"):
        pick -= sigma[channel]
        if pick <= 0 and sigma[channel] > 0:
            break
    result = {"channel": channel, "sigma": sigma, "total": total,
              "probability": sigma[channel] / total, "residual": (z, a), "emitted": None, "nu": 0, "q_mev": None}
    if channel == "capture":
        result["residual"] = (z, a + 1)
        result["q_mev"] = q_value(z, a, [(z, a + 1)])
    elif channel == "alpha":
        result["residual"], result["emitted"] = (z - 2, a - 3), (2, 4)
        result["q_mev"] = q_value(z, a, [(z - 2, a - 3), (2, 4)])
    elif channel == "proton":
        result["residual"], result["emitted"] = (z - 1, a), (1, 1)
        result["q_mev"] = q_value(z, a, [(z - 1, a), (1, 1)])
    elif channel == "fission":
        split = fission_split(rng, z, a)
        if split is None:
            # Aucun partage aux masses mesurées : on ne montre pas une fission inventée.
            result["channel"], result["residual"] = "capture", (z, a + 1)
            result["probability"] = sigma["capture"] / total
            result["q_mev"] = q_value(z, a, [(z, a + 1)])
            return result
        heavy, light, nu = split
        result["residual"], result["emitted"], result["nu"] = heavy, light, nu
        result["q_mev"] = q_value(z, a, [heavy, light] + [(0, 1)] * nu)
    return result
