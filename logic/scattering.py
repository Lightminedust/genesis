"""Un lepton frappe un nucléon au repos : les cas possibles et leurs probabilités, calculés.

Projectiles : électron, positron, muons (un photon échangé) ; neutrino et antineutrino muoniques (un W échangé, courant
chargé). Les hadrons projectiles (p, π) sont dans `hadronic.py`.

Au premier ordre en α, l'électron échange un photon virtuel (moment transféré Q², énergie ν) avec le nucléon. La section efficace
inclusive à l'angle θ est

    d²σ/dΩdE' = σ_Mott · [ W₂(ν,Q²) + 2 W₁(ν,Q²) tan²(θ/2) ] ,   σ_Mott = α² cos²(θ/2) / (4E² sin⁴(θ/2)) ,

et l'état final hadronique de masse invariante W = √(M² + 2Mν − Q²) fixe E'. Trois régions du spectre en W :

* **diffusion élastique** (W = M) : le nucléon reste lui-même ; formule de Rosenbluth avec les facteurs de forme G_E, G_M
  (paramétrisation de Kelly 2004 pour le proton ; dipôle / Galster pour le neutron) ;
* **excitation de la résonance Δ(1232)** : Breit–Wigner de largeur variable, normalisée au photon réel par Γ(Δ→Nγ) tiré de
  G*_M(0) = 3,02, avec une chute dipolaire en Q² (choix de modèle : la transition réelle tombe plus vite) ;
* **résonances supérieures** (1,4 < W < 2 GeV) : N(1520), N(1680)… ne sont pas calculées une à une ; leur force
  moyenne est donnée par la dualité de Bloom–Gilman, c'est-à-dire par la même courbe d'échelle que le continu profond ;
* **diffusion inélastique profonde** (W > 2 GeV) : le photon frappe un quark ; F₂ = x Σ e_q² (q + q̄) calculé avec les
  distributions DGLAP de `partons.py` ; F₁ = F₂/2x (Callan–Gross) ; le continu est atténué par Q²/(Q² + m_ρ²), pour que F₂ → 0
  avec Q² (limite de photoproduction) — forme choisie, pas dérivée.

Entre 1,3 et 1,4 GeV et au seuil de production de pions, le fond non résonnant n'est **pas** modélisé : les probabilités sont celles des quatre régions calculées. Les probabilités sont des rapports de sections efficaces intégrées à (E, θ) fixés.

**Leptons massifs (muon).** La cinématique est exacte : Q² = 2(EE′ − kk′ cos θ) − m² − m′², W² = M² + 2Mν − Q². Le
tenseur leptonique est contracté exactement avec le tenseur hadronique (W₁, W₂) : L·W = W₂ (4EE′ − Q²) + 2W₁ (Q² − 2m²), d'où
d²σ/dΩdE′ = (α²/Q⁴)(k′/k) L·W ; pour m → 0 c'est σ_Mott [W₂ + 2W₁ tan²(θ/2)]. Positron et électron ont la même section
efficace à cet ordre (la charge y entre au carré ; l'échange de deux photons, qui les distingue d'environ 1 %, est négligé).

**Neutrinos (courant chargé).** ν_μ + N → μ⁻ + X par échange d'un W⁺ (ν̄_μ : μ⁺, W⁻) :
d²σ/dΩdE′ = (G_F² E′k′/2π²) (M_W²/(M_W² + Q²))² [2W₁ sin²(θ/2) + W₂ cos²(θ/2) ± (E + E′)/M · W₃ sin²(θ/2)]
(+ pour ν) — la masse du muon est gardée dans la cinématique, pas dans le tenseur leptonique (écart ∼ m_μ²/2ME, < 3 % au-dessus
de 1 GeV). Trois régions : **quasi-élastique** ν n → μ⁻ p, ν̄ p → μ⁺ n (Llewellyn Smith : facteurs de forme vectoriels
isovecteurs tirés des mêmes G_E, G_M ; axial g_A/(1 + Q²/M_A²)², g_A = −1,2754, M_A = 1,03 GeV ; pseudoscalaire par PCAC) ;
**résonances** (M + m_π < W < 2 GeV, dualité : mêmes fonctions de structure que le continu, le Δ n'est pas calculé à part —
modèle de Rein–Sehgal non implémenté) ; **profond inélastique** (W > 2 GeV) : F₂ = 2x(d + s + ū), xF₃ = 2x(d + s − ū) pour ν
sur le proton (ν̄ : u ↔ d), avec les distributions DGLAP de `partons.py`. Le W change la saveur du quark frappé (d → u pour ν).
"""
import functools
import math

import numpy as np

import partons
import standard_model as sm

M_P = 938.27208816
M_N = 939.5654205
M_PI = 139.57039
ALPHA = sm.ALPHA
HBAR_C = sm.HBAR_C
MEV2_TO_NB = 3.8937937e11            # 1 MeV⁻² = 3,8938·10⁵ mb = 3,8938·10¹¹ nb
MU_P, MU_N = 2.79284734, -1.91304273
KELLY_GE = (-0.24, (10.98, 12.82, 21.97))
KELLY_GM = (0.12, (10.97, 18.86, 6.55))
G_STAR_M0 = 3.02                     # N → Δ, convention de Jones–Scadron (valeur mesurée à Q² = 0)
M_DELTA, GAMMA_DELTA = 1232.0, 117.0
W_DUAL_MIN, W_DIS_MIN, M_RHO2 = 1400.0, 2000.0, 0.6e6   # MeV, MeV, MeV² (m_ρ²)
LAMBDA_D2 = 0.71e6                   # MeV², dipôle
M_E, M_MU = 0.51099895, 105.6583755
G_F = 1.1663788e-11                  # MeV⁻² (constante de Fermi)
M_W = 80369.2                        # MeV
COS_CABIBBO = 0.97435
G_A, M_A = -1.2754, 1030.0           # couplage axial du nucléon, masse axiale (MeV)
MEV2_TO_CM2 = 3.8937937e-22          # 1 MeV⁻² en cm²

# les projectiles : charge (e), masse, interaction, lepton sortant, boson échangé
PROJECTILES = {
    'e-': dict(label='e⁻', name='électron', mass=M_E, charge=-1, kind='em', out='e-', out_label='e⁻', out_mass=M_E, out_charge=-1,
               boson='γ*'),
    'e+': dict(label='e⁺', name='positron', mass=M_E, charge=1, kind='em', out='e+', out_label='e⁺', out_mass=M_E, out_charge=1,
               boson='γ*'),
    'mu-': dict(label='μ⁻', name='muon', mass=M_MU, charge=-1, kind='em', out='mu-', out_label='μ⁻', out_mass=M_MU, out_charge=-1,
                boson='γ*'),
    'mu+': dict(label='μ⁺', name='antimuon', mass=M_MU, charge=1, kind='em', out='mu+', out_label='μ⁺', out_mass=M_MU, out_charge=1,
                boson='γ*'),
    'nu': dict(label='ν_μ', name='neutrino muonique', mass=0.0, charge=0, kind='cc', out='mu-', out_label='μ⁻', out_mass=M_MU,
               out_charge=-1, boson='W⁺'),
    'nubar': dict(label='ν̄_μ', name='antineutrino muonique', mass=0.0, charge=0, kind='cc', out='mu+', out_label='μ⁺', out_mass=M_MU,
                  out_charge=1, boson='W⁻'),
}
PROJECTILE_CODES = ['e-', 'e+', 'mu-', 'mu+', 'nu', 'nubar', 'p', 'pi+', 'pi-']


def target_mass(target):
    return M_P if target == 'proton' else M_N


def _kelly(tau, params):
    a1, (b1, b2, b3) = params
    return (1 + a1 * tau) / (1 + b1 * tau + b2 * tau ** 2 + b3 * tau ** 3)


def dipole(q2):
    return (1 + q2 / LAMBDA_D2) ** -2


def form_factors(q2, target='proton'):
    """(G_E, G_M) : proton = Kelly (G_M en unités de e, c'est-à-dire × μ_p) ; neutron = dipôle (G_M) et Galster (G_E)."""
    m = target_mass(target)
    tau = q2 / (4 * m * m)
    if target == 'proton':
        return _kelly(tau, KELLY_GE), MU_P * _kelly(tau, KELLY_GM)
    gd = dipole(q2)
    return -MU_N * tau / (1 + 5.6 * tau) * gd, MU_N * gd


def mott(e, theta):
    s2, c2 = math.sin(theta / 2) ** 2, math.cos(theta / 2) ** 2
    return ALPHA ** 2 * c2 / (4 * e * e * s2 * s2)


def final_energy(e, theta, w, m):
    """E'(W) à (E, θ) fixés : E'(2M + 4E sin²θ/2) = M² + 2ME − W²."""
    s2 = math.sin(theta / 2) ** 2
    return (m * m + 2 * m * e - w * w) / (2 * m + 4 * e * s2)


def invariants(e, theta, w, m):
    """E', Q², ν pour un état final de masse W."""
    ep = final_energy(e, theta, w, m)
    q2 = 4 * e * ep * math.sin(theta / 2) ** 2
    return ep, q2, e - ep


def kinematics(e, theta, w, m_t, m_in=0.0, m_out=0.0):
    """E′, k′, Q², ν d'un lepton (masse m_in → m_out) diffusé à θ sur une cible m_t au repos, état hadronique de masse W.

    W² = m_t² + 2m_t ν − Q², Q² = 2(EE′ − kk′ cos θ) − m_in² − m_out², ν = E − E′ : on résout en E′ (fonction monotone).
    Renvoie None si W n'est pas atteignable à cet angle."""
    k = math.sqrt(max(e * e - m_in * m_in, 0.0))
    c = math.cos(theta)

    def q2_of(ep):
        kp = math.sqrt(max(ep * ep - m_out * m_out, 0.0))
        return 2 * (e * ep - k * kp * c) - m_in * m_in - m_out * m_out

    def f(ep):
        return m_t * m_t + 2 * m_t * (e - ep) - q2_of(ep) - w * w
    # une réaction exothermique (n → p : M_cible > W) peut rendre au lepton un peu plus que E
    lo, hi = m_out + 1e-9, e + max(0.0, (m_t * m_t - w * w) / (2 * m_t)) + 1.0
    if f(lo) < 0 or f(hi) > 0:
        return None
    for _ in range(90):
        mid = (lo + hi) / 2
        if f(mid) > 0:
            lo = mid
        else:
            hi = mid
    ep = (lo + hi) / 2
    kp = math.sqrt(max(ep * ep - m_out * m_out, 0.0))
    return ep, kp, q2_of(ep), e - ep


def w_jacobian(e, theta, ep, kp, w, m_t, m_in=0.0):
    """|dE′/dW| à θ fixé : 2W / (2m_t + dQ²/dE′), dQ²/dE′ = 2(E − k cos θ E′/k′)."""
    k = math.sqrt(max(e * e - m_in * m_in, 0.0))
    slope = e - k * math.cos(theta) * ep / max(kp, 1e-9)
    return w / (m_t + slope)


def em_lepton_tensor(e, ep, kp, k, q2, m, w1, w2):
    """d²σ/dΩdE′ (MeV⁻³/sr) d'un lepton chargé de masse m, un photon échangé : (α²/Q⁴)(k′/k)[W₂(4EE′ − Q²) + 2W₁(Q² − 2m²)]."""
    return ALPHA ** 2 / (q2 * q2) * (kp / k) * (w2 * (4 * e * ep - q2) + 2 * w1 * (q2 - 2 * m * m))


def elastic_cross_section(e, theta, target='proton'):
    """dσ/dΩ (MeV⁻²/sr) de la diffusion élastique : Rosenbluth. Renvoie aussi E', Q², G_E, G_M."""
    m = target_mass(target)
    ep = e / (1 + 2 * e * math.sin(theta / 2) ** 2 / m)
    q2 = 4 * e * ep * math.sin(theta / 2) ** 2
    tau = q2 / (4 * m * m)
    ge, gm = form_factors(q2, target)
    t2 = math.tan(theta / 2) ** 2
    value = mott(e, theta) * (ep / e) * ((ge ** 2 + tau * gm ** 2) / (1 + tau) + 2 * tau * gm ** 2 * t2)
    return dict(xs=value, e_out=ep, q2=q2, ge=ge, gm=gm, tau=tau, nu=e - ep)


def _p_star(w, m_n):
    return np.sqrt(np.maximum((w ** 2 - (m_n + M_PI) ** 2) * (w ** 2 - (m_n - M_PI) ** 2), 0)) / (2 * w)


def delta_width(w, m_n=M_P):
    """Largeur en fonction de W : Γ(W) = Γ₀ (p*(W)/p*(M_Δ))³ (M_Δ/W) — onde P, comme pour Δ → Nπ."""
    return GAMMA_DELTA * (_p_star(w, m_n) / _p_star(M_DELTA, m_n)) ** 3 * (M_DELTA / w)


def delta_photon_width(m_n=M_P):
    """Γ(Δ → Nγ) = (α/16) (M_Δ² − M²)³ / (M_Δ³ M²) · G*_M(0)²  [MeV]  (≈ 0,66 MeV ; mesuré ≈ 0,64)."""
    return ALPHA / 16 * (M_DELTA ** 2 - m_n ** 2) ** 3 / (M_DELTA ** 3 * m_n ** 2) * G_STAR_M0 ** 2


def delta_sigma_t(w, q2, m_n=M_P):
    """σ_T(W, Q²) (MeV⁻²) : Breit–Wigner du photon virtuel sur Δ(1232), (π/k²) · Γ_γ Γ / ((W − M_Δ)² + Γ²/4)."""
    w = np.asarray(w, float)
    k = (w ** 2 - m_n ** 2) / (2 * w)
    k = np.maximum(k, 1e-6)
    gamma = delta_width(w, m_n)
    g_gamma = delta_photon_width(m_n) * dipole(q2) ** 2 / dipole(0.0) ** 2
    return math.pi / k ** 2 * g_gamma * gamma / ((w - M_DELTA) ** 2 + gamma ** 2 / 4)


def delta_cross_section(e, theta, w, target='proton'):
    """d²σ/dΩdE' (MeV⁻³/sr) de e N → e Δ : Γ_T σ_T avec Γ_T = α/(2π²) (E'/E) K/(Q²(1−ε)), σ_L ≈ 0 (M1 dominant)."""
    m = target_mass(target)
    w = np.asarray(w, float)
    s2 = math.sin(theta / 2) ** 2
    ep = (m * m + 2 * m * e - w ** 2) / (2 * m + 4 * e * s2)
    q2 = 4 * e * ep * s2
    nu = e - ep
    t2 = math.tan(theta / 2) ** 2
    eps = 1 / (1 + 2 * (1 + nu ** 2 / np.maximum(q2, 1e-9)) * t2)
    flux_k = (w ** 2 - m * m) / (2 * m)
    gamma_t = ALPHA / (2 * math.pi ** 2) * (ep / e) * flux_k / (np.maximum(q2, 1e-9) * (1 - eps))
    return gamma_t * delta_sigma_t(w, q2, m)


def structure_f2(x, q2, target='proton'):
    """F₂(x, Q²) = x Σ e_q² (q + q̄), séparé en valence et mer : (F₂ total, F₂ valence, {saveur : poids de valence})."""
    pdf = partons.pdfs(max(q2 / 1e6, partons.Q0_SQ))
    u, d, s = pdf['u'], pdf['d'], pdf['s']
    ub, db, sb = pdf['ubar'], pdf['dbar'], pdf['sbar']
    if target == 'neutron':
        u, d, ub, db = d, u, db, ub
    grid = partons.GRID

    def at(a):
        return float(np.interp(x, grid, a))
    xu_v, xd_v = at(u - ub), at(d - db)
    f2_val = 4 / 9 * xu_v + 1 / 9 * xd_v
    f2 = 4 / 9 * (at(u) + at(ub)) + 1 / 9 * (at(d) + at(db) + at(s) + at(sb))
    return f2, f2_val, {'u': 4 / 9 * xu_v, 'd': 1 / 9 * xd_v}


def dis_cross_section(e, theta, w, target='proton', w_min=W_DIS_MIN):
    """d²σ/dΩdE' (MeV⁻³/sr) de la région profondément inélastique (0 hors de son domaine de validité)."""
    m = target_mass(target)
    ep, q2, nu = invariants(e, theta, w, m)
    if w < w_min or ep <= 0 or q2 <= 0:
        return 0.0
    x = q2 / (2 * m * nu)
    if not 1e-5 < x < .9995:
        return 0.0
    f2, _, _ = structure_f2(x, q2, target)
    w2 = f2 / nu
    w1 = f2 / (2 * x * m)
    # F₂ → 0 comme Q² quand Q² → 0 (limite de photoproduction, dominance des mésons vecteurs) : on atténue par Q²/(Q² + m_ρ²)
    damping = q2 / (q2 + M_RHO2)
    return damping * mott(e, theta) * (w2 + 2 * w1 * math.tan(theta / 2) ** 2)


def elastic_massive(e, theta, target='proton', m=M_E):
    """dσ/dΩ élastique d'un lepton chargé de masse m (Rosenbluth généralisé) : (α²/Q⁴)(k′/k) L·W / J, J = 1 + (E − k cos θ E′/k′)/M."""
    mt = target_mass(target)
    kin = kinematics(e, theta, mt, mt, m, m)
    if kin is None:
        return None
    ep, kp, q2, nu = kin
    k = math.sqrt(e * e - m * m)
    tau = q2 / (4 * mt * mt)
    ge, gm = form_factors(q2, target)
    w1, w2 = tau * gm * gm, (ge * ge + tau * gm * gm) / (1 + tau)
    recoil = 1 + (e - k * math.cos(theta) * ep / kp) / mt
    value = em_lepton_tensor(e, ep, kp, k, q2, m, w1, w2) / recoil
    return dict(xs=value, e_out=ep, k_out=kp, q2=q2, ge=ge, gm=gm, tau=tau, nu=nu)


def em_inelastic(e, theta, w, target, m, region):
    """d²σ/dΩdW (MeV⁻⁴/sr) d'un lepton chargé de masse m dans la région `region` (delta, dual, dis)."""
    mt = target_mass(target)
    kin = kinematics(e, theta, w, mt, m, m)
    if kin is None:
        return 0.0
    ep, kp, q2, nu = kin
    if q2 <= 0 or nu <= 0:
        return 0.0
    k = math.sqrt(e * e - m * m)
    if region == 'delta':
        if w <= mt + M_PI:
            return 0.0
        kh = (w * w - mt * mt) / (2 * mt)                        # flux de Hand
        sigma_t = float(delta_sigma_t(w, q2, mt))
        w1 = kh * sigma_t / (4 * math.pi ** 2 * ALPHA)
        w2 = w1 * q2 / (q2 + nu * nu)                            # σ_L = 0 (M1 dominant)
    else:
        x = q2 / (2 * mt * nu)
        if not 1e-5 < x < .9995 or w < (W_DUAL_MIN if region == 'dual' else W_DIS_MIN):
            return 0.0
        if region == 'dual' and w > W_DIS_MIN:
            return 0.0
        f2, _, _ = structure_f2(x, q2, target)
        damping = q2 / (q2 + M_RHO2)
        w2 = damping * f2 / nu
        w1 = damping * f2 / (2 * x * mt)
    return em_lepton_tensor(e, ep, kp, k, q2, m, w1, w2) * w_jacobian(e, theta, ep, kp, w, mt, m)


def structure_cc(x, q2, target='proton', antineutrino=False):
    """Fonctions de structure du courant chargé (F₂, xF₃) et poids des saveurs frappées.

    ν : F₂ = 2x(d + s + ū), xF₃ = 2x(d + s − ū) ; ν̄ : F₂ = 2x(u + d̄ + s̄), xF₃ = 2x(u − d̄ − s̄) (cible proton ; neutron :
    u ↔ d). Le W⁺ (ν) ne voit que les quarks de charge −1/3 et les antiquarks de charge −2/3 : il élève la charge de 1."""
    pdf = partons.pdfs(max(q2 / 1e6, partons.Q0_SQ))
    u, d, s = pdf['u'], pdf['d'], pdf['s']
    ub, db, sb = pdf['ubar'], pdf['dbar'], pdf['sbar']
    if target == 'neutron':
        u, d, ub, db = d, u, db, ub
    grid = partons.GRID

    def at(a):
        return float(np.interp(x, grid, a))
    if not antineutrino:
        quark, anti = at(d) + at(s), at(ub)
        valence = {'d': at(d - db)}
    else:
        quark, anti = at(u), at(db) + at(sb)
        valence = {'u': at(u - ub)}
    return 2 * (quark + anti), 2 * (quark - anti), valence


def cc_inelastic(e, theta, w, target, antineutrino, region):
    """d²σ/dΩdW (MeV⁻⁴/sr) d'un neutrino (courant chargé) dans la région `res` (M + m_π < W < 2 GeV) ou `dis` (W > 2 GeV)."""
    mt = target_mass(target)
    if region == 'res' and not (mt + M_PI < w < W_DIS_MIN):
        return 0.0
    if region == 'dis' and w < W_DIS_MIN:
        return 0.0
    kin = kinematics(e, theta, w, mt, 0.0, M_MU)
    if kin is None:
        return 0.0
    ep, kp, q2, nu = kin
    if q2 <= 0 or nu <= 0:
        return 0.0
    x = q2 / (2 * mt * nu)
    if not 1e-5 < x < .9995:
        return 0.0
    f2, xf3, _ = structure_cc(x, q2, target, antineutrino)
    w2, w1, w3 = f2 / nu, f2 / (2 * x * mt), (xf3 / x) / nu
    s2, c2 = math.sin(theta / 2) ** 2, math.cos(theta / 2) ** 2
    sign = -1 if antineutrino else 1
    prop = (M_W * M_W / (M_W * M_W + q2)) ** 2
    bracket = 2 * w1 * s2 + w2 * c2 + sign * (e + ep) / mt * w3 * s2
    value = G_F ** 2 * ep * kp / (2 * math.pi ** 2) * prop * max(0.0, bracket)
    return value * w_jacobian(e, theta, ep, kp, w, mt, 0.0)


def llewellyn_smith(e, q2, antineutrino=False, m_l=M_MU):
    """dσ/dQ² (MeV⁻⁴) quasi-élastique ν n → μ⁻ p (ou ν̄ p → μ⁺ n), formule de Llewellyn Smith (1972)."""
    m = (M_P + M_N) / 2
    tau = q2 / (4 * m * m)
    gep, gmp = form_factors(q2, 'proton')
    gen, gmn = form_factors(q2, 'neutron')
    gev, gmv = gep - gen, gmp - gmn
    f1 = (gev + tau * gmv) / (1 + tau)
    f2 = (gmv - gev) / (1 + tau)
    fa = G_A / (1 + q2 / (M_A * M_A)) ** 2
    fp = 2 * m * m * fa / (M_PI * M_PI + q2)
    r = m_l * m_l / (m * m)
    a = (r + q2 / (m * m)) * ((1 + tau) * fa * fa - (1 - tau) * f1 * f1 + tau * (1 - tau) * f2 * f2 + 4 * tau * f1 * f2
                              - r / 4 * ((f1 + f2) ** 2 + (fa + 2 * fp) ** 2 - 4 * (1 + tau) * fp * fp))
    b = q2 / (m * m) * fa * (f1 + f2)
    c = (fa * fa + f1 * f1 + tau * f2 * f2) / 4
    su = 4 * m * e - q2 - m_l * m_l
    sign = 1 if antineutrino else -1                             # g_A < 0 : − B pour ν, + B pour ν̄
    bracket = a + sign * b * su / (m * m) + c * su * su / m ** 4
    return m * m * G_F ** 2 * COS_CABIBBO ** 2 / (8 * math.pi * e * e) * max(0.0, bracket)


def qe_targets(antineutrino):
    """La cible du quasi-élastique et le nucléon produit : ν n → μ⁻ p ; ν̄ p → μ⁺ n."""
    return ('proton', 'neutron') if antineutrino else ('neutron', 'proton')


def qe_cross_section(e, theta, target, antineutrino):
    """dσ/dΩ (MeV⁻²/sr) quasi-élastique à θ ; 0 si la cible ne s'y prête pas (ν sur un proton : il faudrait une charge +2)."""
    need, final = qe_targets(antineutrino)
    if target != need:
        return None
    mt, mf = target_mass(target), target_mass(final)
    kin = kinematics(e, theta, mf, mt, 0.0, M_MU)
    if kin is None:
        return None
    ep, kp, q2, nu = kin
    # |dQ²/dΩ| = |dQ²/d cos θ| / 2π, par différences finies sur la cinématique exacte
    h = 1e-4
    c0 = math.cos(theta)
    up, dn = kinematics(e, math.acos(min(1, c0 + h)), mf, mt, 0.0, M_MU), kinematics(e, math.acos(max(-1, c0 - h)), mf, mt, 0.0, M_MU)
    if up is None or dn is None:
        return None
    dq2 = abs(up[2] - dn[2]) / (min(1, c0 + h) - max(-1, c0 - h))
    value = llewellyn_smith(e, q2, antineutrino) * dq2 / (2 * math.pi)
    return dict(xs=value, e_out=ep, k_out=kp, q2=q2, nu=nu, final=final)


def neutrino_total(e, target='proton', antineutrino=False):
    """σ totale (cm²) d'un neutrino d'énergie E sur un nucléon, intégrée sur tous les angles : quasi-élastique (∫ dσ/dQ²) +
    inélastique (∫∫ d²σ/dxdy, W > M + m_π, F aux Q² = 2MExy) — pour la probabilité d'interagir en traversant un nucléon."""
    mt = target_mass(target)
    total = 0.0
    need, final = qe_targets(antineutrino)
    if target == need:
        lo = kinematics(e, 0.0, target_mass(final), mt, 0.0, M_MU)
        hi = kinematics(e, math.pi, target_mass(final), mt, 0.0, M_MU)
        if lo is not None and hi is not None:
            qs = np.linspace(lo[2], hi[2], 400)
            total += float(np.trapezoid([llewellyn_smith(e, q, antineutrino) for q in qs], qs))
    sign = -1 if antineutrino else 1
    xs, ys = np.linspace(.005, .995, 70), np.linspace(.005, .995, 70)
    grid = np.zeros((len(xs), len(ys)))
    for i, x in enumerate(xs):
        for j, y in enumerate(ys):
            q2 = 2 * mt * e * x * y
            nu = e * y
            if mt * mt + 2 * mt * nu - q2 <= (mt + M_PI) ** 2 or e * (1 - y) < M_MU:
                continue
            f2, xf3, _ = structure_cc(x, q2, target, antineutrino)
            prop = (M_W * M_W / (M_W * M_W + q2)) ** 2
            grid[i, j] = G_F ** 2 * mt * e / math.pi * prop * max(0.0, y * y * f2 / 2 + (1 - y - mt * x * y / (2 * e)) * f2
                                                                   + sign * y * (1 - y / 2) * xf3)
    total += float(np.trapezoid(np.trapezoid(grid, ys, axis=1), xs))
    return total * MEV2_TO_CM2


@functools.lru_cache(maxsize=64)
def _angular_table(energy_mev, target, projectile):
    thetas = np.concatenate([np.geomspace(5, 30, 14), np.linspace(32, 170, 16)])
    values = []
    for th in thetas:
        try:
            values.append(Collision(energy_mev, float(th), target, projectile, resolution=.12, totals=False).total)
        except ValueError:
            values.append(0.0)
    return tuple(float(t) for t in thetas), tuple(values)


def angular_table(energy_mev, target, projectile):
    """dσ/dΩ (tous les cas sommés, MeV⁻²/sr) à 30 angles de 5° à 170° : la distribution d'où la physique tire l'angle.
    Mise en cache (même énergie, même cible, même projectile : même table)."""
    thetas, values = _angular_table(round(float(energy_mev), 3), target, projectile)
    return np.array(thetas), np.array(values)


def sample_angle(energy_mev, target, projectile, u):
    """L'angle de diffusion tiré par la physique : θ selon dσ/dΩ · 2π sin θ (tous les cas sommés), entre 5° et 170°.

    `u` ∈ [0, 1[ est le tirage. Renvoie (θ en degrés, médiane de la distribution, probabilité de dévier de plus de 30°)."""
    thetas, values = angular_table(energy_mev, target, projectile)
    density = values * np.sin(np.radians(thetas))
    cdf = np.concatenate([[0.0], np.cumsum((density[1:] + density[:-1]) / 2 * np.diff(np.radians(thetas)))])
    if cdf[-1] <= 0:
        raise ValueError("Aucun angle accessible à cette énergie")
    cdf /= cdf[-1]
    angle = float(np.interp(u, cdf, thetas))
    median = float(np.interp(.5, cdf, thetas))
    beyond = float(1 - np.interp(30.0, thetas, cdf))
    return angle, median, beyond


class Collision:
    """Un lepton (électron, positron, muon, neutrino) + nucléon à (E, θ) : les cas, leurs probabilités, le spectre en W et un
    événement tiré dans ce spectre."""

    def __init__(self, energy_mev, angle_deg, target='proton', projectile='e-', resolution=1.0, totals=True):
        if target not in ('proton', 'neutron'):
            raise ValueError('La cible est un proton ou un neutron')
        if projectile not in PROJECTILES:
            raise ValueError('Projectile inconnu : %s' % projectile)
        if not 300 <= energy_mev <= 10000:
            raise ValueError("Énergie du faisceau : 0,3 à 10 GeV")
        if not 5 <= angle_deg <= 170:
            raise ValueError("Angle de diffusion : 5° à 170°")
        self.projectile = projectile
        self.beam = PROJECTILES[projectile]
        self.kind = self.beam['kind']
        self.m_in, self.m_out = self.beam['mass'], self.beam['out_mass']
        if energy_mev <= self.m_in * 1.05 or energy_mev < self.m_out + 50:
            raise ValueError("Énergie trop basse pour ce projectile")
        self.target, self.m = target, target_mass(target)
        self.energy, self.angle = float(energy_mev), float(angle_deg)
        self.theta = math.radians(angle_deg)
        self.momentum = math.sqrt(self.energy ** 2 - self.m_in ** 2)
        # W maximal à cet angle : le lepton sortant au repos
        q2_rest = 2 * self.energy * self.m_out - self.m_in ** 2 - self.m_out ** 2
        self.w_max = math.sqrt(max(self.m ** 2 + 2 * self.m * (self.energy - self.m_out) - q2_rest, self.m ** 2))
        self.resolution, self.totals = resolution, totals
        self.angle_sampled, self.angle_median, self.angle_beyond_30 = False, 0.0, 0.0
        if self.kind == 'cc':
            self._tabulate_cc()
        else:
            self._tabulate()

    # ------------------------------------------------------------------ le spectre (leptons chargés)
    def _jacobian(self):
        return lambda w: w / (self.m + 2 * self.energy * math.sin(self.theta / 2) ** 2)

    def _tabulate(self):
        e, th, m, ml = self.energy, self.theta, self.m, self.m_in
        el = elastic_massive(e, th, self.target, ml)
        self.elastic = el
        top = min(self.w_max, 1700.0)
        n = max(30, int(360 * self.resolution))
        self.w_delta = np.linspace(M_P + M_PI + 1, top, n) if top > M_P + M_PI + 2 else np.array([])
        self.delta_density = np.array([em_inelastic(e, th, w, self.target, ml, 'delta') for w in self.w_delta])
        self.w_dis = np.linspace(W_DIS_MIN, self.w_max, n) if self.w_max > W_DIS_MIN + 5 else np.array([])
        self.dis_density = np.array([em_inelastic(e, th, w, self.target, ml, 'dis') for w in self.w_dis])
        top_dual = min(self.w_max, W_DIS_MIN)
        self.w_dual = np.linspace(W_DUAL_MIN, top_dual, max(20, n * 5 // 9)) if top_dual > W_DUAL_MIN + 5 else np.array([])
        self.dual_density = np.array([em_inelastic(e, th, w, self.target, ml, 'dual') for w in self.w_dual])
        self.dual_total = float(np.trapezoid(self.dual_density, self.w_dual)) if len(self.w_dual) else 0.0
        self.delta_total = float(np.trapezoid(self.delta_density, self.w_delta)) if len(self.w_delta) else 0.0
        self.dis_total = float(np.trapezoid(self.dis_density, self.w_dis)) if len(self.w_dis) else 0.0
        self.elastic_total = el['xs'] if el else 0.0
        total = self.elastic_total + self.delta_total + self.dual_total + self.dis_total
        if total <= 0:
            raise ValueError("Aucun état final n'est accessible à cet angle")
        self.total = total
        self.channels = [
            dict(name='élastique', key='elastic', xs_nb=self.elastic_total * MEV2_TO_NB, probability=self.elastic_total / total),
            dict(name='excitation Δ(1232)', key='delta', xs_nb=self.delta_total * MEV2_TO_NB, probability=self.delta_total / total),
            dict(name='résonances supérieures', key='dual', xs_nb=self.dual_total * MEV2_TO_NB, probability=self.dual_total / total),
            dict(name='inélastique profond', key='dis', xs_nb=self.dis_total * MEV2_TO_NB, probability=self.dis_total / total),
        ]
        self.sigma_total_cm2 = 0.0
        self.interaction_probability = 0.0

    # ------------------------------------------------------------------ le spectre (neutrinos)
    def _tabulate_cc(self):
        e, th, anti = self.energy, self.theta, self.projectile == 'nubar'
        qe = qe_cross_section(e, th, self.target, anti)
        self.elastic = qe
        self.qe_total = qe['xs'] if qe else 0.0
        top = min(self.w_max, W_DIS_MIN)
        n = max(30, int(300 * self.resolution))
        self.w_res = np.linspace(self.m + M_PI + 1, top, n) if top > self.m + M_PI + 2 else np.array([])
        self.res_density = np.array([cc_inelastic(e, th, w, self.target, anti, 'res') for w in self.w_res])
        self.w_dis = np.linspace(W_DIS_MIN, self.w_max, n) if self.w_max > W_DIS_MIN + 5 else np.array([])
        self.dis_density = np.array([cc_inelastic(e, th, w, self.target, anti, 'dis') for w in self.w_dis])
        self.res_total = float(np.trapezoid(self.res_density, self.w_res)) if len(self.w_res) else 0.0
        self.dis_total = float(np.trapezoid(self.dis_density, self.w_dis)) if len(self.w_dis) else 0.0
        total = self.qe_total + self.res_total + self.dis_total
        if total <= 0:
            raise ValueError("Aucun état final n'est accessible à cet angle")
        self.total = total
        need, _ = qe_targets(anti)
        qe_name = 'quasi-élastique' if self.target == need else 'quasi-élastique (impossible : charge)'
        to_nb = MEV2_TO_CM2 * 1e33                       # cm² → nb
        self.channels = [
            dict(name=qe_name, key='qe', xs_nb=self.qe_total * to_nb, probability=self.qe_total / total),
            dict(name='résonances (dualité)', key='res', xs_nb=self.res_total * to_nb, probability=self.res_total / total),
            dict(name='inélastique profond', key='dis', xs_nb=self.dis_total * to_nb, probability=self.dis_total / total),
        ]
        # la probabilité d'interagir du tout, en traversant un nucléon : σ_totale / (π r_p²)
        self.sigma_total_cm2 = neutrino_total(e, self.target, anti) if self.totals else 0.0
        r_p = 0.8409e-13                                 # cm
        self.interaction_probability = self.sigma_total_cm2 / (math.pi * r_p * r_p)

    def spectrum(self):
        """Pour tracer : (W en MeV, dσ/dΩdW en nb/(sr·GeV)) des régions continues ; l'élastique est une raie."""
        if self.kind == 'cc':
            scale = MEV2_TO_CM2 * 1e33 * 1000.0
            return dict(res=(self.w_res, self.res_density * scale), dis=(self.w_dis, self.dis_density * scale))
        scale = MEV2_TO_NB * 1000.0
        return dict(delta=(self.w_delta, self.delta_density * scale), dual=(self.w_dual, self.dual_density * scale),
                    dis=(self.w_dis, self.dis_density * scale))

    # ------------------------------------------------------------------ un événement
    def sample_event(self, seed=1, force=None):
        """Tire un événement ; `force` (indice de canal) le tire dans ce canal seulement — s'il a une probabilité non nulle."""
        rng = np.random.default_rng(seed)
        probs = np.array([c['probability'] for c in self.channels])
        if force is not None and 0 <= force < len(probs) and probs[force] > 0:
            index = int(force)
            rng.uniform()                      # garde la suite de tirages alignée sur le cas libre
        else:
            index = int(rng.choice(len(probs), p=probs / probs.sum()))
        key = self.channels[index]['key']
        e, th, m = self.energy, self.theta, self.m
        final_nucleon = None
        if key == 'elastic':
            w = m
        elif key == 'qe':
            final_nucleon = self.elastic['final']
            w = target_mass(final_nucleon)
        elif key == 'delta':
            w = self._draw(self.w_delta, self.delta_density, rng)
        elif key == 'dual':
            w = self._draw(self.w_dual, self.dual_density, rng)
        elif key == 'res':
            w = self._draw(self.w_res, self.res_density, rng)
        else:
            w = self._draw(self.w_dis, self.dis_density, rng)
        ep, kp, q2, nu = kinematics(e, th, w, m, self.m_in, self.m_out)
        k_out = np.array([kp * math.sin(th), 0.0, kp * math.cos(th)])
        k_in = np.array([0.0, 0.0, self.momentum])
        q_vec = k_in - k_out
        event = dict(channel=key, index=index, w=w, e_out=ep, k_out=kp, q2=q2, nu=nu, q_vec=q_vec, q_mag=float(np.linalg.norm(q_vec)),
                     x=q2 / (2 * m * nu) if nu > 0 else 1.0, wavelength_fm=HBAR_C / math.sqrt(q2) if q2 > 0 else math.inf,
                     projectile=self.projectile)
        event['epsilon'] = 1 / (1 + 2 * (1 + nu ** 2 / max(q2, 1e-9)) * math.tan(th / 2) ** 2)
        if key == 'elastic':
            event.update(ge=self.elastic['ge'], gm=self.elastic['gm'], recoil_kinetic=nu)
        elif key == 'qe':
            event.update(final_nucleon=final_nucleon, recoil_kinetic=nu)
        elif key == 'delta' or (key == 'res' and w < W_DUAL_MIN):
            event.update(self._delta_event(w, q2, rng))
            event['resonance'] = True
        else:
            event.update(self._dis_event(event['x'], q2, rng))      # le boson frappe un quark
        return event

    @staticmethod
    def _draw(grid, density, rng):
        cdf = np.cumsum(density)
        cdf = cdf / cdf[-1]
        return float(np.interp(rng.uniform(), cdf, grid))

    # les voies de désintégration du Δ formé (Clebsch–Gordan d'isospin), selon sa charge
    DELTA_DECAYS = {2: [(1.0, 'p', 'π+')], 1: [(2 / 3, 'p', 'π0'), (1 / 3, 'n', 'π+')], 0: [(2 / 3, 'n', 'π0'), (1 / 3, 'p', 'π-')],
                    -1: [(1.0, 'n', 'π-')]}

    def delta_charge(self):
        """La charge du Δ formé : celle du nucléon, plus celle que le boson apporte (W⁺ : +1, W⁻ : −1, γ : 0)."""
        base = 1 if self.target == 'proton' else 0
        if self.kind == 'cc':
            return base + (1 if self.projectile == 'nu' else -1)
        return base

    def _delta_event(self, w, q2, rng):
        """Largeur, durée de vie et voies de désintégration (Clebsch–Gordan d'isospin) du Δ formé."""
        gamma = float(delta_width(w, self.m))
        isospin = self.DELTA_DECAYS[self.delta_charge()]
        pick = isospin[int(rng.choice(len(isospin), p=[b for b, _, _ in isospin]))]
        p_star = float(_p_star(w, self.m))
        proper = HBAR_C / gamma
        return dict(delta_mass=w, delta_width=gamma, proper_lifetime_fm=proper, decay_nucleon=pick[1], decay_pion=pick[2],
                    decay_branches=isospin, p_star=p_star, decay_time_fm=float(rng.exponential(proper)),
                    gamma_photon=float(delta_photon_width(self.m)), form_ratio=float(dipole(q2) ** 2),
                    delta_charge=self.delta_charge())

    def _dis_event(self, x, q2, rng):
        """Quel quark est frappé. Photon : saveur ∝ e_q² × valence. W : seulement les saveurs qu'il peut changer (d pour W⁺,
        u pour W⁻), et la nouvelle saveur ; la part de mer (antiquarks) est calculée et signalée."""
        if self.kind == 'cc':
            anti = self.projectile == 'nubar'
            f2, xf3, valence = structure_cc(x, q2, self.target, anti)
            flavor = next(iter(valence))
            return dict(f2=f2, xf3=xf3, valence_share=valence[flavor] * 2 / f2 if f2 > 0 else 1.0, struck_flavor=flavor,
                        new_flavor='u' if flavor == 'd' else 'd', weights={flavor: valence[flavor]})
        f2, f2_val, weights = structure_f2(x, q2, self.target)
        flavors = list(weights)
        values = np.array([weights[f] for f in flavors])
        flavor = flavors[int(rng.choice(len(flavors), p=values / values.sum()))]
        return dict(f2=f2, valence_share=f2_val / f2 if f2 > 0 else 1.0, struck_flavor=flavor, weights=weights, new_flavor=flavor)
