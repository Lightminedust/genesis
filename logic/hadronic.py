"""Un hadron (proton ou pion) frappe un nucléon au repos : les cas possibles et leurs probabilités, calculés.

Il n'y a plus de photon échangé : les deux hadrons interagissent par la force forte, à travers leurs nuages de couleur.

* **Section efficace totale** — Donnachie et Landshoff (Phys. Lett. B 296, 1992, 227) : σ = X s^ε + Y s^(−η), ε = 0,0808
  (le poméron), η = 0,4525 (les réggéons), s en GeV², σ en mb. Coefficients publiés : pp (21,70 ; 56,08), pn (21,70 ; 54,77),
  π⁺p (13,63 ; 27,56), π⁻p (13,63 ; 36,02) ; π⁺n = π⁻p et π⁻n = π⁺p par symétrie d'isospin. Ajustés au-dessus de √s ≈ 6 GeV ;
  ici on les extrapole jusqu'à √s ≈ 2 GeV (écart aux mesures de l'ordre de 5 à 10 % entre 2 et 10 GeV/c).
* **Élastique** — le théorème optique avec un pic de diffraction exponentiel dσ/dt = (dσ/dt)₀ e^(Bt) donne
  σ_él = σ_tot² / (16π B) (partie réelle ρ négligée) ; pente B = B₀ + 0,5 ln(s/1 GeV²) GeV⁻², B₀ = 7 (pp) ou 6 (πN) — choix
  calé sur les pentes mesurées (8 à 9 GeV⁻² vers 10 GeV/c).
* **Formation du Δ(1232) par un pion** — Breit–Wigner : σ = C·(2J+1)/((2s_π+1)(2s_N+1)) · (π/k²) · Γ_πN Γ / ((W − M_Δ)² + Γ²/4),
  k l'impulsion dans le centre de masse, Γ(W) en onde P, C le carré du Clebsch–Gordan d'isospin (π⁺p : 1, π⁻p : 1/3). Au pic,
  π⁺p : 8π/k² ≈ 190 mb (mesuré ≈ 200 mb). Le Δ et la partie de Regge se relaient sur un raccord lisse de W = 1,3 à 1,7 GeV
  (forme choisie) : au-dessus, les résonances N* sont moyennées par la courbe de Regge (dualité), pas calculées une à une.
* **Inélastique** — σ_inél = σ_tot − σ_él : les deux hadrons échangent la couleur (un gluon) ; deux cordes se tendent entre les
  morceaux qui s'éloignent et se rompent (modèle des cordes de `nucleon.py`).

Les probabilités sont intégrées sur tous les angles (pour un hadron, l'état final n'a pas un seul angle de diffusion).
"""
import math

import numpy as np

import scattering as sc

M_P, M_N, M_PI = sc.M_P, sc.M_N, sc.M_PI
GEV2_FM2 = 0.0389379                  # 1 GeV⁻² = 0,0389 fm²
FM2_MB = 10.0
EPSILON, ETA = 0.0808, 0.4525
DL = {('p', 'proton'): (21.70, 56.08), ('p', 'neutron'): (21.70, 54.77), ('pi+', 'proton'): (13.63, 27.56),
      ('pi-', 'proton'): (13.63, 36.02), ('pi+', 'neutron'): (13.63, 36.02), ('pi-', 'neutron'): (13.63, 27.56)}
SLOPE_B0 = {'p': 7.0, 'pi+': 6.0, 'pi-': 6.0}
# Clebsch–Gordan² de la formation du Δ (π N → Δ, isospin 3/2) et la charge du Δ formé
DELTA_FORMATION = {('pi+', 'proton'): (1.0, 2), ('pi-', 'proton'): (1 / 3, 0), ('pi+', 'neutron'): (1 / 3, 1), ('pi-', 'neutron'): (1.0, -1)}

HADRON_BEAMS = {
    'p': dict(label='p', name='proton', mass=M_P, charge=1, quarks=('u', 'u', 'd'), antiquarks=(), kind='baryon'),
    'pi+': dict(label='π⁺', name='pion π⁺', mass=M_PI, charge=1, quarks=('u',), antiquarks=('d',), kind='meson'),
    'pi-': dict(label='π⁻', name='pion π⁻', mass=M_PI, charge=-1, quarks=('d',), antiquarks=('u',), kind='meson'),
}


def mandelstam_s(p_lab, m_beam, m_target):
    """s = m² + M² + 2M E_lab (MeV²)."""
    e = math.sqrt(p_lab * p_lab + m_beam * m_beam)
    return m_beam * m_beam + m_target * m_target + 2 * m_target * e


def cm_momentum(sqrt_s, m1, m2):
    """L'impulsion de chacun dans le centre de masse (MeV/c)."""
    s = sqrt_s * sqrt_s
    return math.sqrt(max((s - (m1 + m2) ** 2) * (s - (m1 - m2) ** 2), 0.0)) / (2 * sqrt_s)


def regge_total(s_mev2, beam, target):
    """σ_tot de Donnachie–Landshoff (mb)."""
    x, y = DL[(beam, target)]
    s = s_mev2 / 1e6
    return x * s ** EPSILON + y * s ** -ETA


def slope(s_mev2, beam):
    """La pente du pic de diffraction B (GeV⁻²)."""
    return SLOPE_B0[beam] + 0.5 * math.log(s_mev2 / 1e6)


def elastic_from_optical(sigma_tot_mb, b_gev2):
    """σ_él = σ_tot² / (16π B) (mb), plafonnée à σ_tot/2 (disque noir)."""
    sigma_fm2 = sigma_tot_mb / FM2_MB
    el = sigma_fm2 ** 2 / (16 * math.pi * b_gev2 * GEV2_FM2) * FM2_MB
    return min(el, sigma_tot_mb / 2)


def delta_formation(w, beam, target):
    """σ(π N → Δ) (mb) en Breit–Wigner, onde P ; 0 pour un proton projectile."""
    if beam == 'p':
        return 0.0
    cg, _ = DELTA_FORMATION[(beam, target)]
    m_n = sc.target_mass(target)
    k = cm_momentum(w, M_PI, m_n)
    if k <= 0:
        return 0.0
    gamma = float(sc.delta_width(w, m_n))
    spin = 4 / (1 * 2)                                       # (2J+1)/((2s_π+1)(2s_N+1)), J = 3/2
    sigma_mev2 = cg * spin * math.pi / (k * k) * gamma * gamma / ((w - sc.M_DELTA) ** 2 + gamma ** 2 / 4)
    return _mev2_to_mb(sigma_mev2)


def _mev2_to_mb(x):
    """MeV⁻² → mb : × (ħc)² (MeV²·fm²) × 10 mb/fm²."""
    return x * sc.HBAR_C ** 2 * FM2_MB


def regge_weight(w, beam):
    """Le raccord de la partie de Regge sous W ≈ 1,7 GeV (pions) — forme choisie ; 1 pour un proton projectile."""
    if beam == 'p':
        return 1.0
    f = max(0.0, min(1.0, (w - 1300.0) / 400.0))
    return f * f * (3 - 2 * f)


class HadronCollision:
    """p ou π± + nucléon à l'impulsion p_lab : les cas, leurs probabilités (intégrées sur les angles) et un événement."""

    def __init__(self, p_lab_mev, target='proton', projectile='p'):
        if target not in ('proton', 'neutron'):
            raise ValueError('La cible est un proton ou un neutron')
        if projectile not in HADRON_BEAMS:
            raise ValueError('Projectile hadronique inconnu : %s' % projectile)
        low = 1500.0 if projectile == 'p' else 150.0
        if not low <= p_lab_mev <= 10000:
            raise ValueError("Impulsion du faisceau : %s à 10 GeV/c" % ('1,5' if projectile == 'p' else '0,15'))
        self.projectile, self.target = projectile, target
        self.beam = HADRON_BEAMS[projectile]
        self.kind = 'had'
        self.m_beam, self.m = self.beam['mass'], sc.target_mass(target)
        self.momentum = self.energy = float(p_lab_mev)               # « énergie » du curseur : l'impulsion du faisceau
        self.e_beam = math.sqrt(self.momentum ** 2 + self.m_beam ** 2)
        self.s = mandelstam_s(self.momentum, self.m_beam, self.m)
        self.sqrt_s = math.sqrt(self.s)
        self.angle = 0.0
        self.theta = 0.0
        self.p_star = cm_momentum(self.sqrt_s, self.m_beam, self.m)
        self.b_slope = slope(self.s, projectile)
        self.regge = regge_total(self.s, projectile, target) * regge_weight(self.sqrt_s, projectile)
        self.sigma_el = elastic_from_optical(self.regge, self.b_slope) if self.regge > 0 else 0.0
        # le Δ et la partie de Regge se relaient sur le même raccord (dualité) : le Δ s'éteint loin de sa résonance
        self.sigma_delta = delta_formation(self.sqrt_s, projectile, target) * (1 - regge_weight(self.sqrt_s, projectile))
        # au-dessous du seuil de production de pions, l'inélastique est impossible : tout le Regge est élastique
        threshold = self.m + self.m_beam + M_PI
        self.sigma_inel = max(0.0, self.regge - self.sigma_el) if self.sqrt_s > threshold + 5 else 0.0
        if self.sqrt_s <= threshold + 5:
            self.sigma_el = self.regge
        self.sigma_total = self.sigma_el + self.sigma_delta + self.sigma_inel
        if self.sigma_total <= 0:
            raise ValueError("Aucun état final accessible")
        t = self.sigma_total
        self.channels = [
            dict(name='élastique (diffraction)', key='elastic_h', xs_nb=self.sigma_el * 1e6, probability=self.sigma_el / t),
            dict(name='formation Δ(1232)' if projectile != 'p' else 'formation Δ (pion seulement)', key='formation',
                 xs_nb=self.sigma_delta * 1e6, probability=self.sigma_delta / t),
            dict(name='échange de couleur (cordes)', key='strings', xs_nb=self.sigma_inel * 1e6, probability=self.sigma_inel / t),
        ]
        self.w_max = self.sqrt_s
        self.sigma_total_cm2 = self.sigma_total * 1e-27
        self.interaction_probability = 0.0

    def spectrum(self):
        """σ en fonction de l'impulsion du faisceau, pour tracer (MeV/c, mb) : total, élastique, Δ."""
        ps = np.geomspace(1500.0 if self.projectile == 'p' else 150.0, 10000.0, 120)
        out = dict(total=[], elastic=[], delta=[])
        for p in ps:
            c = HadronCollision(p, self.target, self.projectile)
            out['total'].append(c.sigma_total)
            out['elastic'].append(c.sigma_el)
            out['delta'].append(c.sigma_delta)
        return {k: (ps, np.array(v)) for k, v in out.items()}

    def sample_event(self, seed=1, force=None):
        rng = np.random.default_rng(seed)
        probs = np.array([c['probability'] for c in self.channels])
        if force is not None and 0 <= force < len(probs) and probs[force] > 0:
            index = int(force)
            rng.uniform()
        else:
            index = int(rng.choice(len(probs), p=probs / probs.sum()))
        key = self.channels[index]['key']
        event = dict(channel=key, index=index, w=self.sqrt_s, s=self.s, p_star=self.p_star, projectile=self.projectile,
                     q_vec=np.array([0.0, 0.0, self.momentum]), q_mag=self.momentum, nu=self.e_beam, q2=0.0, x=0.0,
                     wavelength_fm=sc.HBAR_C / max(self.p_star, 1e-6), e_out=0.0, k_out=0.0)
        beta = self.momentum / (self.e_beam + self.m)                 # vitesse du centre de masse
        gamma = 1 / math.sqrt(1 - beta * beta)
        if key == 'elastic_h':
            # −t tiré dans e^(Bt) sur [0, 4p*²] ; θ* dans le centre de masse, azimut uniforme
            b = self.b_slope / 1e6                                        # MeV⁻²
            t_max = 4 * self.p_star ** 2
            u = rng.uniform()
            minus_t = -math.log(1 - u * (1 - math.exp(-b * t_max))) / b
            cos_star = 1 - minus_t / (2 * self.p_star ** 2)
            sin_star = math.sqrt(max(0.0, 1 - cos_star * cos_star))
            phi = rng.uniform(0, 2 * math.pi)
            direction = np.array([sin_star * math.cos(phi), sin_star * math.sin(phi), cos_star])
            p1 = self.p_star * direction
            e1, e2 = math.sqrt(self.p_star ** 2 + self.m_beam ** 2), math.sqrt(self.p_star ** 2 + self.m ** 2)
            beam_lab = np.array([p1[0], p1[1], gamma * (p1[2] + beta * e1)])
            target_lab = np.array([-p1[0], -p1[1], gamma * (-p1[2] + beta * e2)])
            event.update(minus_t=minus_t, cos_star=cos_star, beam_out=beam_lab, target_out=target_lab, beam_cm=p1, target_cm=-p1,
                         beam_energy_cm=e1, target_energy_cm=e2,
                         beam_energy_out=math.sqrt(self.m_beam ** 2 + beam_lab @ beam_lab),
                         target_energy_out=math.sqrt(self.m ** 2 + target_lab @ target_lab),
                         theta_lab=math.degrees(math.atan2(math.hypot(beam_lab[0], beam_lab[1]), beam_lab[2])))
            event['q2'] = minus_t
            event['nu'] = event['target_energy_out'] - self.m
            event['wavelength_fm'] = sc.HBAR_C / math.sqrt(max(minus_t, 1e-6))
        elif key == 'formation':
            _, charge = DELTA_FORMATION[(self.projectile, self.target)]
            gamma_w = float(sc.delta_width(self.sqrt_s, self.m))
            branches = sc.Collision.DELTA_DECAYS[charge]
            pick = branches[int(rng.choice(len(branches), p=[b for b, _, _ in branches]))]
            proper = sc.HBAR_C / gamma_w
            event.update(delta_mass=self.sqrt_s, delta_width=gamma_w, proper_lifetime_fm=proper, decay_nucleon=pick[1],
                         decay_pion=pick[2], decay_branches=branches, p_star=float(sc._p_star(self.sqrt_s, self.m)),
                         decay_time_fm=float(rng.exponential(proper)), gamma_photon=0.0, form_ratio=1.0, delta_charge=charge,
                         resonance=True)
        else:
            event.update(colour=int(rng.integers(3)))
        return event
