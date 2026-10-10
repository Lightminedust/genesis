"""Le plasma autour du choc : section efficace de n'importe quel couple, réactivité ⟨σv⟩ et critère de Lawson.

Section efficace σ(E), énergie du centre de masse E :
- D + T, D + D, D + ³He : ajustements mesurés de Bosch & Hale (1992) dans leur domaine ; au-delà, prolongés par
  le modèle de capture, raccordé en continuité ;
- p + p : interaction faible (p + p → d + e⁺ + ν), facteur astrophysique S(0) = 4,01·10⁻²² keV·b
  (Adelberger et al., Rev. Mod. Phys. 83, 195, 2011) ;
- autres couples : capture, somme des ondes partielles avec la transmission de Hill–Wheeler (le même modèle que le
  tir), nulle là où le noyau composé n'est pas lié.
Réactivité : ⟨σv⟩ = √(8/(πμ)) (kT)^(−3/2) ∫ σ(E) E e^(−E/kT) dE, intégrée en logarithmes (pas de débordement).

Bilan de puissance d'un plasma homogène (électrons à la température des ions) :
  P_fus = n_A n_B ⟨σv⟩ Q   (n²/2 ⟨σv⟩ Q pour deux noyaux identiques) ;
  P_dép = f P_fus, f = part de l'énergie emportée par des particules chargées (elles restent dans le plasma) ;
  P_br = 5,35·10⁻³⁷ n_e Σ n_i Z_i² √T · (1 + 1,78 t^1,34), t = kT/m_ec²  (bremsstrahlung, W/m³, T en keV ;
         NRL Plasma Formulary, correction relativiste de Rider 1995) ;
  W = 3/2 (n_i + n_e) kT, perdue en τ_E.
  Ignition : P_dép ≥ W/τ_E + P_br.  Gain Q = P_fus / (W/τ_E + P_br − P_dép).
"""
import math
from functools import lru_cache

import numpy as np
from scipy import constants as sc
from scipy.special import log_expit, logsumexp

import fusion
import reactor

J_PER_KEV = 1e3 * sc.e
BREMSSTRAHLUNG = 5.35e-37            # W m³ keV^-1/2
PP_S0_KEV_B = 4.01e-22               # S_pp(0), keV·b
PP_Q_MEV = 1.442                     # p + p → d + e⁺ + ν, annihilation comprise
PP_DEPOSITED_MEV = 1.442 - 0.265     # le neutrino emporte 0,265 MeV en moyenne
GRID = np.geomspace(0.05, 2e6, 2400)  # keV
BOSCH_HALE_MAX_KEV = {"T(d,n)4He": 550.0, "D(d,p)T": 5000.0, "D(d,n)3He": 4900.0, "3He(d,p)4He": 900.0}
CURVE_T = np.geomspace(0.5, 5000.0, 64)


def fuel_of(a, b):
    for fuel in reactor.FUELS:
        if sorted((a, b)) == sorted((fuel["a"], fuel["b"])):
            return fuel
    return None


def _nuclear_mass(z, a):
    mass, _ = fusion.mass_u(z, a)
    return None if mass is None else reactor.nuclear_mass_mev(z, a)


@lru_cache(maxsize=32)
def _log_capture(a, b):
    """log σ_capture(E) (barns) sur GRID. σ = π/k² Σ (2ℓ+1) T₀(E − ΔE_ℓ), ΔE_ℓ = ħ²ℓ(ℓ+1)/(2μR_b²) (barrière
    centrifuge au sommet). T₀ : sous le sommet, effet tunnel WKB à travers le vrai potentiel (Coulomb + nucléaire),
    formule de Kemble T = 1/(1 + e^(2S)) ; au-dessus, Hill–Wheeler. Canal fermé (noyau composé non lié) → −∞."""
    from fusion_dynamics import potential_terms
    ma, mb = _nuclear_mass(*a), _nuclear_mass(*b)
    compound = (a[0] + b[0], a[1] + b[1])
    mc = _nuclear_mass(*compound) if compound[0] <= 118 else None
    out = np.full(GRID.shape, -np.inf)
    if ma is None or mb is None or mc is None:
        return out
    pair = fusion.Pair(a, b)
    pair.mu = ma * mb / (ma + mb)
    top = pair.barrier
    hbar_omega = max(pair.hbar_omega, 1e-6)
    # T₀(E) sur une grille fine en énergie (MeV).
    fine = np.geomspace(1e-7, max(top * 4, 1.0), 500)
    inner = max(pair.contact * .5, .3)
    outer = max(1.44 * a[0] * b[0] / fine[0] * 1.2, 4 * pair.barrier_r)
    r = np.geomspace(inner, outer, 3000)
    v = sum(potential_terms(pair, r)[:2])
    log_t0 = np.empty(fine.shape)
    for start in range(0, len(fine), 50):
        e = fine[start:start + 50, None]
        kappa = np.sqrt(np.maximum(0.0, 2 * pair.mu * (v[None, :] - e))) / fusion.HBAR_C
        action = np.trapezoid(kappa, r, axis=1) if hasattr(np, "trapezoid") else np.trapz(kappa, r, axis=1)
        log_t0[start:start + 50] = log_expit(-2 * action)
    above = fine >= top
    log_t0[above] = log_expit(2 * math.pi * (fine[above] - top) / hbar_omega)
    energy = GRID / 1000
    k = np.sqrt(2 * pair.mu * energy) / fusion.HBAR_C
    shift_unit = fusion.HBAR_C ** 2 / (2 * pair.mu * pair.barrier_r ** 2)
    ell_max = int(min(4000, math.sqrt(energy.max() / shift_unit) + 2))
    ell = np.arange(ell_max + 1)
    shifted = energy[None, :] - shift_unit * (ell * (ell + 1))[:, None]
    log_t = np.full(shifted.shape, -np.inf)
    open_ = shifted > fine[0]
    log_t[open_] = np.interp(np.log(shifted[open_]), np.log(fine), log_t0)
    hot = shifted > fine[-1]
    log_t[hot] = 0.0
    out = np.log(math.pi / 100) - 2 * np.log(k) + logsumexp(np.log(2 * ell + 1)[:, None] + log_t, axis=0)
    out[energy + (ma + mb - mc) < 0] = -np.inf
    return out


@lru_cache(maxsize=32)
def log_sigma(a, b):
    """(log σ(E) en barns sur GRID, modèle). −∞ : aucune fusion possible à cette énergie."""
    a, b = tuple(a), tuple(b)
    fuel = fuel_of(a, b)
    if fuel:
        capture = _log_capture(a, b)
        total = np.zeros(GRID.shape)
        for name, _ in fuel["branches"]:
            top = BOSCH_HALE_MAX_KEV[name]
            sigma = np.asarray(reactor.cross_section_b(name, np.minimum(GRID, top)), dtype=float)
            above = GRID > top
            if above.any():
                # Au-delà du domaine mesuré : la forme du modèle de capture, raccordée à la dernière valeur mesurée.
                edge = int(np.searchsorted(GRID, top))
                scale = np.exp(np.clip(capture[above] - capture[min(edge, len(GRID) - 1)], -700, 50))
                sigma[above] = sigma[above] * np.where(np.isfinite(scale), scale, 0)
            total += sigma
        with np.errstate(divide="ignore"):
            return np.log(total), "Bosch-Hale (mesures), prolongé par le modèle de capture"
    if a == b == (1, 1):
        gamow = math.pi * sc.alpha * math.sqrt(2 * reactor.nuclear_mass_mev(1, 1) / 2 * 1000)
        return np.log(PP_S0_KEV_B / GRID) - gamow / np.sqrt(GRID), "interaction faible, S(0) mesuré (Adelberger 2011)"
    return _log_capture(a, b), "capture : ondes partielles, transmission de Hill-Wheeler (modèle)"


def log_reactivity(a, b, kt_kev):
    """log ⟨σv⟩ (m³/s) d'un plasma maxwellien à kT."""
    ls, _ = log_sigma(tuple(a), tuple(b))
    integrand = ls + np.log(GRID) - GRID / kt_kev
    finite = np.isfinite(integrand)
    if not finite.any():
        return -np.inf
    # ∫ f dE sur une grille géométrique : ∫ f E d(ln E).
    log_integral = logsumexp(integrand[finite] + np.log(GRID[finite])) + math.log(math.log(GRID[1] / GRID[0]))
    ma, mb = reactor.nuclear_mass_mev(*a), reactor.nuclear_mass_mev(*b)
    mu = ma * mb / (ma + mb) / reactor.MEV_PER_U * reactor.U_KG
    return (0.5 * math.log(8 / (math.pi * mu)) - 1.5 * math.log(kt_kev * J_PER_KEV) + log_integral
            + 2 * math.log(J_PER_KEV) + math.log(1e-28))


def reactivity(a, b, kt_kev):
    value = log_reactivity(a, b, kt_kev)
    return 0.0 if value < -690 else math.exp(value)


@lru_cache(maxsize=64)
def energetics(a, b):
    """(Q en MeV, part déposée par les particules chargées, texte). Q ≤ 0 : la réaction consomme de l'énergie."""
    a, b = tuple(a), tuple(b)
    fuel = fuel_of(a, b)
    if fuel:
        before = fusion.mass_u(*a)[0] + fusion.mass_u(*b)[0]
        qs, charged, weights = [], [], []
        for name, products in fuel["branches"]:
            masses = [fusion.mass_u(*p)[0] for p in products]
            q = (before - sum(masses)) * reactor.MEV_PER_U
            # Deux corps : chacun emporte Q · m_autre / (m₁ + m₂).
            share = sum(masses[1 - i] / sum(masses) for i, p in enumerate(products) if p[0] > 0)
            qs.append(q)
            charged.append(share)
            weights.append(float(reactor.cross_section_b(name, 30.0)))
        w = np.array(weights) / sum(weights)
        q = float(np.dot(w, qs))
        f = float(np.dot(w, [qq * s for qq, s in zip(qs, charged)])) / q
        return q, f, "masses mesurées ; produits chargés : " + ", ".join(f"{100 * s:.0f} %" for s in charged)
    if a == b == (1, 1):
        return PP_Q_MEV, PP_DEPOSITED_MEV / PP_Q_MEV, "le neutrino s'échappe (0,265 MeV en moyenne)"
    compound = (a[0] + b[0], a[1] + b[1])
    if compound[0] > 118 or fusion.mass_u(*compound)[0] is None:
        return 0.0, 0.0, "aucun noyau composé au-delà de Z = 118"
    q = (fusion.mass_u(*a)[0] + fusion.mass_u(*b)[0] - fusion.mass_u(*compound)[0]) * reactor.MEV_PER_U
    return q, 1.0, "énergie de capture ; cas le plus favorable : tout reste dans le plasma"


def _budget(a, b, kt_kev, density, tau, log_sv=None):
    """Puissances (W/m³) d'un plasma de A et B (moitié-moitié, ou pur si A = B)."""
    same = tuple(a) == tuple(b)
    na = density if same else density / 2
    nb = 0.0 if same else density / 2
    ne = na * a[0] + nb * b[0]
    z2 = na * a[0] ** 2 + nb * b[0] ** 2
    log_sv = log_reactivity(a, b, kt_kev) if log_sv is None else log_sv
    sv = 0.0 if log_sv < -690 else math.exp(log_sv)
    rate = (na * na / 2 if same else na * nb) * sv
    q, f, _ = energetics(tuple(a), tuple(b))
    fusion_w = rate * max(q, 0.0) * 1e6 * sc.e
    deposited = f * fusion_w
    thermal = 1.5 * (density + ne) * kt_kev * J_PER_KEV
    relativistic = 1 + 1.78 * (kt_kev / 511.0) ** 1.34
    brems = BREMSSTRAHLUNG * ne * z2 * math.sqrt(kt_kev) * relativistic
    return {"sv": sv, "fusion": fusion_w, "deposited": deposited, "thermal": thermal, "brems": brems,
            "conduction": thermal / tau if tau > 0 else math.inf, "partner": nb if not same else na}


def required_triple(a, b, kt_kev):
    """nτT (keV·s/m³) nécessaires à cette température : (ignition, Q = 1) ; 0 = impossible."""
    n = 1e20
    p = _budget(a, b, kt_kev, n, 1.0)
    ignition = n * p["thermal"] / (p["deposited"] - p["brems"]) * kt_kev if p["deposited"] > p["brems"] else 0.0
    net = p["fusion"] + p["deposited"] - p["brems"]
    breakeven = n * p["thermal"] / net * kt_kev if net > 0 else 0.0
    return ignition, breakeven


@lru_cache(maxsize=256)
def lawson(a, b, kt_kev, density, tau):
    """Le bilan du plasma réglé (T, n, τ) et les courbes de Lawson du couple."""
    a, b = tuple(a), tuple(b)
    p = _budget(a, b, kt_kev, density, tau)
    q, f, energy_note = energetics(a, b)
    _, model = log_sigma(a, b)
    heat = p["conduction"] + p["brems"] - p["deposited"]
    ignited = q > 0 and heat <= 0
    gain = math.inf if ignited else (p["fusion"] / heat if heat > 0 else 0.0)
    curves = [required_triple(a, b, t) for t in CURVE_T]
    ignition_curve = [c[0] for c in curves]
    breakeven_curve = [c[1] for c in curves]
    possible = [(c, t) for c, t in zip(ignition_curve, CURVE_T) if c > 0]
    best_triple, best_t = min(possible) if possible else (0.0, 0.0)
    triple = density * tau * kt_kev
    need, _ = required_triple(a, b, kt_kev)
    wait = 1 / (p["partner"] * p["sv"]) if p["sv"] > 0 and p["partner"] > 0 else math.inf
    names = " + ".join(fusion.label(*x) for x in (a, b))
    if q <= 0 and f == 0:
        verdict = f"{names} : pas de noyau composé, pas de fusion qui libère de l'énergie."
    elif q <= 0:
        verdict = f"{names} consomme de l'énergie (Q = {q:.1f} MeV) : aucun plasma ne peut l'entretenir."
    elif not possible:
        verdict = f"{names} : le rayonnement de freinage l'emporte toujours ; aucune température ne permet l'ignition."
    elif ignited:
        verdict = f"Ignition : la fusion entretient seule la chaleur du plasma (nτT = {triple:.2g} ≥ {need:.2g})."
    elif need > 0:
        verdict = (f"Pas d'ignition : nτT = {triple:.2g} keV·s/m³, il en faut {need:.2g} à cette température "
                   f"(minimum {best_triple:.2g} vers {best_t:.0f} keV).")
    else:
        verdict = (f"À {kt_kev:.3g} keV, le freinage dépasse la fusion ; l'ignition demande "
                   f"nτT ≥ {best_triple:.2g} vers {best_t:.0f} keV.")
    return {"temperature_kev": kt_kev, "density": density, "tau": tau, "reactivity": p["sv"],
            "fusion": p["fusion"], "deposited": p["deposited"], "brems": p["brems"], "conduction": p["conduction"],
            "gain": gain, "ignited": ignited, "triple": triple, "need": need, "curve_t": list(CURVE_T),
            "ignition_curve": ignition_curve, "breakeven_curve": breakeven_curve, "q": q, "charged": f,
            "model": model + " ; " + energy_note, "verdict": verdict, "wait": wait,
            "best_t": float(best_t), "best_triple": float(best_triple)}


def sample_energy(a, b, kt_kev, rng, fusing):
    """Énergie (keV) d'un choc de ce plasma. `fusing` : parmi les chocs qui fusionnent, σ(E)·E·e^(−E/kT) (pic de
    Gamow) ; sinon le flux maxwellien E·e^(−E/kT). Rend aussi log₁₀ de la fraction des chocs au moins aussi
    énergiques."""
    if fusing:
        ls, _ = log_sigma(tuple(a), tuple(b))
        weight = ls + 2 * np.log(GRID) - GRID / kt_kev       # densité en ln E : f(E)·E
        finite = np.isfinite(weight)
        if not finite.any():
            raise ValueError("Aucune fusion possible pour ce couple : pas de noyau composé lié.")
        p = np.exp(weight - weight[finite].max())
        p[~finite] = 0
        cdf = np.cumsum(p)
        energy = float(np.interp(rng.random() * cdf[-1], cdf, GRID))
    else:
        energy = max(0.1, float(rng.gamma(2, kt_kev)))
    x = energy / kt_kev
    rarity = (math.log(1 + x) - x) / math.log(10)
    return energy, rarity
