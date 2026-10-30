"""Le bac à sable du rendement : n'importe quel noyau, l'énergie de liaison, et ce que coûte ou rapporte chaque voie.

Tout vient des masses mesurées (AME, via mendeleev) : Q = (masse avant − masse après)·c².
  - La courbe d'Aston : l'énergie de liaison par nucléon B/A des noyaux stables ; son sommet (Ni-62, Fe-58,
    Fe-56) sépare les noyaux qui gagnent à se briser de ceux qui gagnent à fusionner.
  - La fission : la coupure en deux noyaux pesés qui libère le plus, et la coupure symétrique.
  - La fusion du noyau avec lui-même : Q, barrière de Coulomb, et nτT de Lawson quand le plasma peut s'allumer.
  - La réaction en chaîne : seulement si le noyau a une section efficace de fission évaluée (ENDF/B-VIII.0) et
    que k∞ dépasse 1 ; sinon, aucune masse, aussi grande soit-elle, ne diverge.
"""

import math
import random
from functools import lru_cache

import numpy as np

from common import elements_db
from nuclear import neutrons, nuclear_data

from . import library
from .text import fixed, number

MEV_PER_U = nuclear_data.MEV_PER_U
NEUTRON_U = nuclear_data.NEUTRON_MASS_U
AVOGADRO = 6.02214076e23
MEV_J = 1.602176634e-13
TNT_J_PER_TONNE = 4.184e9


def mass(z, a):
    return nuclear_data.atomic_mass_u(z, a)


@lru_cache(maxsize=1)
def binding_curve():
    """(A, Z, B/A en MeV) des noyaux stables présents dans la nature, triés par A."""
    rows = elements_db.DB.execute(
        "select atomic_number, mass_number from isotopes where abundance > 0 order by mass_number").fetchall()
    points = []
    for z, a in rows:
        weighed = nuclear_data.measured_binding(z, a)
        if weighed and a > 1:
            points.append((a, z, weighed[2] / a))
    return points


def binding_per_nucleon(z, a):
    weighed = nuclear_data.measured_binding(z, a)
    return weighed[2] / a if weighed else None


@lru_cache(maxsize=1)
def _isotopes_by_z():
    table = {}
    for z, a in elements_db.DB.execute("select atomic_number, mass_number from isotopes").fetchall():
        if mass(z, a) is not None:
            table.setdefault(z, []).append(a)
    return table


@lru_cache(maxsize=256)
def best_fission(z, a):
    """La coupure (Z₁, A₁) + (Z₂, A₂) qui libère le plus d'énergie, parmi les noyaux pesés ; et sa valeur Q."""
    parent = mass(z, a)
    if parent is None or a < 4:
        return None
    best = None
    known = _isotopes_by_z()
    for z1 in range(1, z // 2 + 1):
        z2 = z - z1
        for a1 in known.get(z1, []):
            a2 = a - a1
            if a2 < 1 or (z1 == z2 and a1 > a2):
                continue
            m2 = mass(z2, a2)
            if m2 is None:
                continue
            q = (parent - mass(z1, a1) - m2) * MEV_PER_U
            if best is None or q > best[0]:
                best = (q, (z1, a1), (z2, a2))
    return best


def symmetric_fission(z, a):
    z1, a1 = z // 2, a // 2
    z2, a2 = z - z1, a - a1
    parent, m1, m2 = mass(z, a), mass(z1, a1), mass(z2, a2)
    if None in (parent, m1, m2) or z1 < 1:
        return None
    return (parent - m1 - m2) * MEV_PER_U, (z1, a1), (z2, a2)


def self_fusion(z, a):
    """X + X → (2Z, 2A) : Q, et le noyau formé ; None s'il n'a jamais été pesé."""
    parent = mass(z, a)
    product = mass(2 * z, 2 * a) if 2 * z <= 118 else None
    if parent is None or product is None:
        return None
    return (2 * parent - product) * MEV_PER_U, (2 * z, 2 * a)


def coulomb_barrier(z, a):
    """Hauteur de la barrière (MeV) que deux noyaux identiques doivent franchir pour se toucher."""
    try:
        from fusionlab import fusion
        return float(fusion.Pair((z, a), (z, a)).barrier)
    except Exception:
        radius = 1.2 * a ** (1 / 3)
        return 1.44 * z * z / (2 * radius)


@lru_cache(maxsize=64)
def lawson_minimum(z, a, z2=None, a2=None):
    """Le plus petit nτT (keV·s/m³) qui allume le plasma, et sa température (keV) ; (0, 0) : jamais."""
    try:
        from fusionlab import lawson
        p = lawson.lawson((z, a), (z2 or z, a2 or a), 15.0, 1e20, 3.0)
        return p["best_triple"], p["best_t"]
    except Exception:
        return 0.0, 0.0


def label(z, a):
    return f"{nuclear_data.SYMBOLS.get(z, '?')}-{a}"


def element_name(z):
    from atomic.periodic_table import ELEMENTS
    return next((e["name"] for e in ELEMENTS if e["z"] == z), "")


def per_kg(q_mev, mass_u):
    """Énergie libérée par kilogramme de combustible (J/kg) pour Q par réaction et la masse des réactifs (u)."""
    return q_mev * MEV_J / (mass_u * 1.66053906660e-27)


def mean_fission_q(z, a, samples=600, seed=7):
    """Q moyen d'une fission induite par neutron lent, d'après les masses des fragments tirés (avant leurs β⁻)."""
    rng = random.Random(seed)
    parent = mass(z, a)
    total, n = 0.0, 0
    for _ in range(samples):
        split = neutrons.fission_split(rng, z, a)
        if not split:
            continue
        heavy, light, nu = split
        total += (parent + NEUTRON_U - mass(*heavy) - mass(*light) - nu * NEUTRON_U) * MEV_PER_U
        n += 1
    return total / n if n else None


def chain_reaction(z, a, critical):
    """Ce que dit l'évaluation sur la réaction en chaîne : (évalué ?, k∞, σ_f thermique, σ_f rapide, masse critique)."""
    name = f"{nuclear_data.SYMBOLS.get(z)}{a}"
    if not library.has(name) or not library.fissile_data(name):
        return {"evaluated": library.has(name), "fission_data": False, "k_infinity": 0.0, "sigma_thermal": 0.0,
                "sigma_fast": 0.0, "critical_kg": 0.0, "name": name}
    shielded = library.get(name, "shielded")[-1]
    bounds = library.bounds_ev()
    g_thermal = int(np.searchsorted(-bounds, -0.0253, side="right") - 1)
    g_fast = int(np.searchsorted(-bounds, -1.5e6, side="right") - 1)
    result = {"evaluated": True, "fission_data": True, "name": name,
              "sigma_thermal": float(shielded[library.FISSION, g_thermal]),
              "sigma_fast": float(shielded[library.FISSION, g_fast])}
    result.update(critical(name))
    return result


def explore(z, a, critical):
    """Le bilan complet d'un noyau pour le bac à sable."""
    m = mass(z, a)
    if m is None:
        return None
    fission = best_fission(z, a)
    symmetric = symmetric_fission(z, a)
    fusion = self_fusion(z, a)
    barrier = coulomb_barrier(z, a)
    triple, best_t = lawson_minimum(z, a) if fusion and fusion[0] > 0 and z <= 8 else (0.0, 0.0)
    chain = chain_reaction(z, a, critical)
    ba = binding_per_nucleon(z, a) or 0.0
    peak = max(binding_curve(), key=lambda p: p[2])
    name = label(z, a)
    q_fission = fission[0] if fission else None
    q_fusion = fusion[0] if fusion else None
    if chain["fission_data"] and chain["k_infinity"] > 1:
        verdict = (f"{name} peut entretenir une réaction en chaîne : k∞ = {fixed(chain['k_infinity'], 2)}. "
                   f"Une sphère nue devient critique à {number(chain['critical_kg'])} kg.")
    elif chain["fission_data"]:
        verdict = (f"{name} fissionne sous les neutrons, mais trop peu : k∞ = {fixed(chain['k_infinity'], 2)} < 1. "
                   "Aucune masse, aussi grande soit-elle, ne diverge.")
    elif q_fission is not None and q_fission > 0:
        verdict = (f"Briser {name} libérerait {fixed(q_fission, 1)} MeV, mais il faudrait d'abord le déformer jusqu'à la "
                   "rupture : " + ("ENDF/B-VIII.0 ne lui donne aucune fission par neutron." if chain["evaluated"]
                                   else "ce noyau n'est pas dans la bibliothèque de neutrons du laboratoire.")
                   + " Pas de réaction en chaîne calculée.")
    elif q_fusion is not None and q_fusion > 0:
        verdict = (f"{name} est trop léger pour gagner à se briser ({fixed(-q_fission, 1)} MeV à fournir). " if q_fission is not None
                   else f"{name} ne se brise pas en noyaux pesés. ") + (
            f"C'est la fusion qui libère : {name} + {name} donne {fixed(q_fusion, 1)} MeV, derrière une barrière de "
            f"Coulomb de {fixed(barrier, 2)} MeV — un plasma chaud et confiné, pas une chaîne de neutrons.")
    else:
        parts = []
        if q_fission is not None:
            parts.append(f"le briser coûte {fixed(-q_fission, 1)} MeV")
        if q_fusion is not None:
            parts.append(f"le fusionner avec lui-même coûte {fixed(-q_fusion, 1)} MeV")
        # Au sommet de la courbe (A ≥ 40), ou un noyau léger exceptionnellement lié (He-4).
        where = "est près du sommet de la courbe" if a >= 40 else "est un noyau très lié"
        verdict = (f"{name} {where} : " + " et ".join(parts or ["aucune voie pesée"])
                   + ". Les produits pèseraient plus que lui : aucune énergie à en tirer.")
    return {"z": z, "a": a, "label": label(z, a), "name": element_name(z), "mass_u": m,
            "binding_per_nucleon": ba, "peak": peak, "fission": fission, "symmetric": symmetric, "fusion": fusion,
            "barrier_mev": barrier, "lawson_triple": triple, "lawson_t_kev": best_t, "chain": chain,
            "verdict": verdict}


def comparison(critical):
    """U-235, Fe-56, Fe-60 et D + T côte à côte : Q par réaction, énergie par kilogramme, condition."""
    rows = []
    u_q = float(library.get("U235", "energy_release")[8]) / 1e6        # Q récupérable (ENDF MT 458)
    u_crit = critical("U235")
    rows.append({"label": "U-235", "process": "fission par neutron", "q_mev": u_q,
                 "j_per_kg": per_kg(u_q, mass(92, 235)), "possible": True,
                 "condition": f"masse critique nue {number(u_crit['critical_kg'])} kg (k∞ = {fixed(u_crit['k_infinity'], 2)})",
                 "note": "Q récupérable ENDF/B-VIII.0 (fragments, γ, β ; sans les neutrinos)"})
    fe = best_fission(26, 56)
    fe_fusion = self_fusion(26, 56)
    rows.append({"label": "Fe-56", "process": "fission ou fusion", "q_mev": fe[0], "j_per_kg": per_kg(fe[0], mass(26, 56)),
                 "possible": False,
                 "condition": f"fusion Fe-56 + Fe-56 : Q = {fixed(fe_fusion[0], 1)} MeV" if fe_fusion else "aucune voie exothermique",
                 "note": f"au sommet de la courbe : B/A = {fixed(binding_per_nucleon(26, 56), 3)} MeV"})
    # La demi-vie de NUBASE (2,62 millions d'années, mesure de 2009) : ICRP-107 garde l'ancienne valeur de 1,5.
    value, unit = elements_db.DB.execute("select half_life, half_life_unit from isotopes "
                                         "where atomic_number=26 and mass_number=60").fetchone()
    half_life = value * nuclear_data._UNIT_S[unit]
    q_beta = nuclear_data.q_value_mev(26, 60, "beta-") or 0.0
    power = q_beta * MEV_J * AVOGADRO * 1000 / 60 * math.log(2) / half_life
    years = half_life / (365.25 * 86400)
    fe60_split = best_fission(26, 60)
    rows.append({"label": "Fe-60", "process": "désintégration β⁻", "q_mev": q_beta, "j_per_kg": per_kg(q_beta, mass(26, 60)),
                 "possible": False,
                 "condition": f"t½ = {fixed(years / 1e6, 2)} millions d'années (NUBASE) : {number(power, 2)} W par kg",
                 "note": f"radioactif mais pas énergétique ; sa fission coûterait {fixed(-fe60_split[0], 1)} MeV" if fe60_split else ""})
    dt_q = (mass(1, 2) + mass(1, 3) - mass(2, 4) - NEUTRON_U) * MEV_PER_U
    triple, temperature = lawson_minimum(1, 2, 1, 3)
    rows.append({"label": "D + T", "process": "fusion", "q_mev": dt_q, "j_per_kg": per_kg(dt_q, mass(1, 2) + mass(1, 3)),
                 "possible": True,
                 "condition": f"Lawson : nτT ≥ {number(triple, 2)} keV·s/m³ vers {fixed(temperature, 0)} keV",
                 "note": "pas de chaîne : chaque fusion demande un plasma chaud et confiné"})
    for row in rows:
        row["tnt_per_kg"] = row["j_per_kg"] / TNT_J_PER_TONNE
    return rows
