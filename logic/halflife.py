"""Demi-vie des noyaux instables : mesurée quand on la connaît, estimée sinon.

Le simulateur a besoin d'une demi-vie pour CHAQUE noyau instable : c'est elle
qui fixe la probabilité de désintégration par seconde, λ = ln 2 / t½.

  - Mesurée pour les 3 500 nucléides observés (ICRP-107, NUBASE2020 : voir
    nuclear_data.py). Les estimations ci-dessous ne servent qu'aux noyaux
    que personne n'a jamais observés.
  - α : loi de Viola-Seaborg (1966), forme moderne de la loi de Geiger-Nuttall.
        log10(t½ / s) = (a Z + b) / √Q + c Z + d
    Elle explique pourquoi quelques MeV de différence font passer la demi-vie
    de la microseconde au milliard d'années : l'α traverse la barrière
    électrique par effet tunnel, et la probabilité de passage est
    exponentiellement sensible à Q.
  - β et capture : règle de Sargent. Pour une transition « permise », le
    produit f·t½ vaut environ 10^5,7 s, et f croît comme Q⁵ : un Q dix fois plus
    grand rend la désintégration cent mille fois plus rapide.
  - Émission de nucléon : le noyau n'est pas lié, il se défait en ~10⁻²¹ s.

Les estimations sont des ordres de grandeur et sont présentées comme tels.
"""

import math

from scipy import constants as sc
from scipy.integrate import quad

from nucleus import q_alpha, q_beta_minus, q_electron_capture
from nuclides import UNBOUND_MODES, lookup
from units import format_duration  # noqa: F401  (réexporté)

YEAR_S = 365.25 * 86400.0
UNBOUND_HALF_LIFE_S = 1e-21
# log ft médian des désintégrations β connues ; la structure nucléaire (spin,
# parité) le fait varier de 3 à 10, d'où une estimation à ~x1000 près.
SARGENT_LOG_FT = 6.5

# Viola-Seaborg (Sobiczewski et al.), noyaux pair-pair ; t en secondes, Q en MeV
_VS_A, _VS_B, _VS_C, _VS_D = 1.66175, -8.5166, -0.20228, -33.9069


def viola_seaborg_s(z, q_mev):
    return 10 ** ((_VS_A * z + _VS_B) / math.sqrt(q_mev) + _VS_C * z + _VS_D)


ELECTRON_MASS_MEV = sc.physical_constants["electron mass energy equivalent in MeV"][0]
FINE_STRUCTURE = sc.fine_structure
MIRROR_LOG_FT = 3.3   # transitions super-permises entre noyaux miroirs


def fermi_function(z_daughter, energy, momentum, positron):
    """Fonction de Fermi non relativiste : le noyau attire l'électron émis
    (et repousse le positon), ce qui modifie l'espace de phase disponible."""
    eta = (-1 if positron else 1) * z_daughter * FINE_STRUCTURE * energy / momentum
    x = 2 * math.pi * eta
    if abs(x) < 1e-9:
        return 1.0
    return x / (1 - math.exp(-x))


def phase_space(q_mev, z_daughter=0, positron=False):
    """Facteur f = ∫ p W (W0 - W)² F(Z, W) dW, énergies en unités de m_e c².

    C'est le volume disponible pour se partager Q entre l'électron et le
    neutrino. Il croît comme Q⁵ à haute énergie, beaucoup moins vite à basse.
    """
    w0 = 1 + q_mev / ELECTRON_MASS_MEV
    if w0 <= 1:
        return 0.0

    def integrand(w):
        p = math.sqrt(max(w * w - 1, 1e-30))
        return p * w * (w0 - w) ** 2 * fermi_function(z_daughter, w, p, positron)

    return quad(integrand, 1, w0, limit=200)[0]


def is_mirror(z, n):
    """Noyaux miroirs : A impair, N et Z ne diffèrent que d'une unité."""
    return abs(n - z) == 1 and (z + n) % 2 == 1 and z + n <= 41


def beta_half_life_s(z, n, q_mev, mode):
    """t½ = 10^(log ft) / f  — log ft ≈ 6,5 en médiane, ≈ 3,3 entre noyaux
    miroirs (transition super-permise)."""
    log_ft = MIRROR_LOG_FT if is_mirror(z, n) else SARGENT_LOG_FT
    if mode == "beta-":
        f = phase_space(q_mev, z + 1, positron=False)
    else:
        # β+ et capture électronique sont en compétition : on additionne.
        f = phase_space(max(0.0, q_mev - 2 * ELECTRON_MASS_MEV), z - 1, positron=True)
        k_capture = 2 * math.pi ** 2 * (FINE_STRUCTURE * z) ** 3 * (q_mev / ELECTRON_MASS_MEV) ** 2
        f += k_capture
    if f <= 0:
        return math.inf
    return 10 ** log_ft / f


def sargent_s(q_mev):
    """Compatibilité : désintégration β- permise, sans correction coulombienne."""
    return beta_half_life_s(0, 1, q_mev, "beta-")


def half_life(z, n, mode):
    """Retourne (t½ en secondes, source lisible). `mode` est le mode prédit."""
    measured = lookup(z, z + n)
    if measured:
        return measured["half_life_s"], measured["source"]

    if mode in UNBOUND_MODES:
        return UNBOUND_HALF_LIFE_S, "noyau non lié : ~10⁻²¹ s"

    if mode == "alpha":
        q = q_alpha(z, n)
        if q > 0.5:
            return viola_seaborg_s(z, q), "estimée (loi de Viola-Seaborg)"
        return 1e10 * YEAR_S, "estimation grossière (Q α trop faible ou inconnu)"

    beta_mode = "beta-" if mode == "beta-" else "beta+"
    q = (q_beta_minus if beta_mode == "beta-" else q_electron_capture)(z, n)
    rule = ("noyaux miroirs, super-permise" if is_mirror(z, n)
            else "log ft médian, ~x1000 près : spin et parité non modélisés")
    if q > 0.01:
        return beta_half_life_s(z, n, q, beta_mode), f"estimée (espace de phase de Fermi, {rule})"
    # Weizsäcker donne Q <= 0 alors que le noyau est hors de la vallée : le
    # modèle échoue, on retient Q ~ 1 MeV, typique des émetteurs β.
    return beta_half_life_s(z, n, 1.0, beta_mode), "estimation grossière (Q inconnu, 1 MeV supposé)"
