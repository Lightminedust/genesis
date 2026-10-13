"""La forme des hadrons et le champ du proton : ce qui fixe les membranes et le puits de Coulomb qu'on dessine.

1. Le rayon de charge du proton se lit sur la pente du facteur de forme électrique : ⟨r²⟩ = −6 dG_E/dQ² (Q² = 0). Avec la
   paramétrisation de Kelly (G_E = (1 + a₁τ)/(1 + b₁τ + …), τ = Q²/4M²) : ⟨r²⟩ = 6 (b₁ − a₁) (ħc)²/(4M²) → 0,86 fm.
2. Le sac du MIT : trois quarks sans masse dans une cavité de rayon R, pression du vide B. E(R) = 3·2,04 ħc/R + (4π/3) B R³,
   minimale pour R⁴ = 3·2,04 ħc/(4πB). On prend pour R le rayon de la sphère uniforme de même rayon quadratique moyen que la
   charge (R = √(5/3)·r) ; on en déduit B : B^(1/4) ≈ 148 MeV, la valeur ajustée par le MIT (145 MeV) — une vérification.
   Un hadron de n quarks a R_n = R (n/3)^(1/4).
3. Le tube de flux : les champs de couleur décroissent en exp(−r/λ) autour de l'axe de la corde, λ ≈ 0,23 fm (QCD sur réseau,
   Cardoso & Bicudo, PRD 88, 054504, 2013). La paroi du tube est prise à r = 2λ, où la densité d'énergie est divisée par e².
4. Le puits de Coulomb : la densité de charge exponentielle ρ ∝ exp(−r/a) (le Fourier d'un facteur de forme dipolaire),
   a = r_rms/√12. Potentiel d'un électron : U(r) = −αħc/r · [1 − e^(−r/a)(1 + r/2a)] ; au centre U(0) = −αħc/(2a).
5. La trajectoire classique de l'électron dans ce champ est intégrée (dynamique relativiste). Elle montre que le champ de
   Coulomb ne dévie un électron de quelques GeV que d'une fraction de degré : les grands angles mesurés viennent de l'échange
   d'un seul photon dur (un effet quantique), qui est dessiné à part.
"""
import math

import numpy as np

from quantumlab import scattering as sc
from quantumlab import standard_model as sm

LAMBDA_FLUX_FM = 0.23                 # longueur de pénétration du champ de couleur (réseau)
X_MIT = 2.04                          # premier mode d'un quark sans masse dans le sac


def charge_radius(target='proton'):
    """Rayon de charge (fm) : pente de G_E à Q² = 0 (Kelly pour le proton)."""
    if target != 'proton':
        return 0.8628                 # le neutron n'a pas de rayon de charge positif ; on prend la même taille de sac
    a1, (b1, _, _) = sc.KELLY_GE
    m_gev = sc.M_P / 1000
    r2 = 6 * (b1 - a1) / (4 * m_gev ** 2) * (sm.HBAR_C / 1000) ** 2
    return math.sqrt(r2)


def bag(target='proton'):
    """Le sac du nucléon : rayon (fm), pression B (MeV/fm³), B^(1/4) (MeV), rayon du tube (fm)."""
    r_rms = charge_radius(target)
    radius = math.sqrt(5 / 3) * r_rms
    pressure = 3 * X_MIT * sm.HBAR_C / (4 * math.pi * radius ** 4)          # MeV/fm³
    b_quarter = (pressure * sm.HBAR_C ** 3) ** .25                           # MeV
    return dict(charge_radius=r_rms, radius=radius, pressure=pressure, b_quarter=b_quarter,
                tube_radius=2 * LAMBDA_FLUX_FM, flux_lambda=LAMBDA_FLUX_FM)


def bag_radius(quarks, nucleon_radius):
    """R_n = R (n/3)^(1/4) : la même pression pour un hadron de n quarks."""
    return nucleon_radius * (quarks / 3) ** .25


def density_length(target='proton'):
    return charge_radius(target) / math.sqrt(12)


def potential(r, a):
    """U(r) d'un électron dans le champ d'une charge +e de densité exp(−r/a) (MeV ; r en fm)."""
    r = np.asarray(r, float)
    alpha_hc = sm.ALPHA * sm.HBAR_C
    small = r < 1e-6
    safe = np.where(small, 1.0, r)
    u = -alpha_hc / safe * (1 - np.exp(-safe / a) * (1 + safe / (2 * a)))
    return np.where(small, -alpha_hc / (2 * a), u)


def field(r, a):
    """|dU/dr| (MeV/fm) : la force électrique qu'exerce le proton sur un électron, e·E(r) = αħc·Q(r)/r²,
    Q(r) = 1 − e^(−r/a)(1 + r/a + r²/2a²) la charge contenue dans la sphère de rayon r."""
    r = np.asarray(r, float)
    safe = np.maximum(r, 1e-9)
    enclosed = 1 - np.exp(-safe / a) * (1 + safe / a + safe * safe / (2 * a * a))
    return sm.ALPHA * sm.HBAR_C * enclosed / safe ** 2


def radius_for(level, a):
    """Le rayon où U(r) = level (level < 0)."""
    lo, hi = 1e-4, 1e4
    for _ in range(100):
        mid = math.sqrt(lo * hi)
        if float(potential(mid, a)) < level:
            lo = mid
        else:
            hi = mid
    return math.sqrt(lo * hi)


def coulomb_path(energy_mev, impact_fm, a, start_fm=-40.0, end_fm=0.0, steps=600, mass=0.51099895, charge=-1):
    """Trajectoire classique (relativiste) d'un lepton de masse `mass` et de charge `charge` (en e) dans le champ du proton, du
    point z = start à la verticale du proton. Attractive pour une charge négative, répulsive pour une positive.

    Le faisceau va selon +z, le lepton passe à x = b. Renvoie les points (fm) et l'angle de déviation accumulé (rad)."""
    me = mass
    p = math.sqrt(energy_mev ** 2 - me ** 2)
    state = np.array([impact_fm, 0.0, start_fm, 0.0, 0.0, p])      # x, y, z, px, py, pz
    alpha_hc = sm.ALPHA * sm.HBAR_C

    def force(pos):
        r = float(np.linalg.norm(pos))
        if r < 1e-9:
            return np.zeros(3)
        # F = q_lepton · e·E(r) r̂, charge enclose Q(r) = 1 − e^(−r/a)(1 + r/a + r²/2a²) : tirée vers le proton pour q < 0
        enclosed = 1 - math.exp(-r / a) * (1 + r / a + r * r / (2 * a * a))
        return charge * alpha_hc * enclosed / r ** 2 * pos / r

    def rhs(s):
        pos, mom = s[:3], s[3:]
        e = math.sqrt(me ** 2 + mom @ mom)
        return np.concatenate([mom / e, force(pos)])

    points = [state[:3].copy()]
    ds = (end_fm - start_fm) / steps
    for _ in range(steps):
        # pas de temps tel que l'électron avance de ds le long de z (v ≈ c)
        h = ds
        k1 = rhs(state)
        k2 = rhs(state + h / 2 * k1)
        k3 = rhs(state + h / 2 * k2)
        k4 = rhs(state + h * k3)
        state = state + h / 6 * (k1 + 2 * k2 + 2 * k3 + k4)
        points.append(state[:3].copy())
    mom = state[3:]
    angle = math.atan2(math.hypot(mom[0], mom[1]), mom[2])
    return np.array(points), angle


def classical_max_deflection(energy_mev, a, mass=0.0):
    """La plus grande déviation que le seul champ de Coulomb du proton peut donner (rad), à paramètre d'impact optimal.

    Approximation d'impulsion, deux fois la traversée du demi-espace : θ(b) = (2/pc) ∫ F_⊥ dz ; on cherche le maximum en b."""
    best = 0.0
    for b in np.geomspace(.02, 20, 120):
        z = np.linspace(-60, 60, 2401)
        r = np.sqrt(b * b + z * z)
        enclosed = 1 - np.exp(-r / a) * (1 + r / a + r * r / (2 * a * a))
        f_perp = sm.ALPHA * sm.HBAR_C * enclosed / r ** 2 * (b / r)
        pv = (energy_mev ** 2 - mass ** 2) / energy_mev           # p·v : la rigidité du lepton
        theta = np.trapezoid(f_perp, z) / pv
        best = max(best, float(theta))
    return best
