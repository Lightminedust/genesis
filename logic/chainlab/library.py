"""La bibliothèque multigroupe (ENDF/B-VIII.0, 44 groupes) et le passage aux sections efficaces d'un matériau.

Le fichier data/groups.npz est produit par tools/build_chain_groups.py à partir des données évaluées. Pour un
matériau donné (des noyaux et leurs densités atomiques), on calcule ici :
  - l'autoprotection des résonances (Bondarenko) : chaque noyau voit une section de dilution
    σ₀ = (Σ des autres noyaux + 1/ℓ̄) / N, où ℓ̄ = 4V/S est la corde moyenne du morceau de métal ;
  - les sections efficaces macroscopiques (cm⁻¹) et la matrice de transfert ;
  - la correction de transport (Σ_tr = Σ_t − μ̄Σ_s), que le solveur suppose ensuite isotrope ;
  - le spectre de fission χ du mélange, pondéré par la production de neutrons de chaque noyau.
"""

from functools import lru_cache
from pathlib import Path

import numpy as np

PATH = Path(__file__).resolve().parent / "data" / "groups.npz"
AVOGADRO = 6.02214076e23
BARN_CM2 = 1e-24
TOTAL, ELASTIC, INELASTIC, MULTIPLICITY, FISSION, NU_FISSION, ABSORPTION = range(7)
SOURCE = "ENDF/B-VIII.0 (NNDC), traité par NJOY pour OpenMC ; 44 groupes calculés par tools/build_chain_groups.py"


@lru_cache(maxsize=1)
def data():
    with np.load(PATH) as raw:
        return {key: raw[key] for key in raw.files}


def names():
    return [str(n) for n in data()["names"]]


def bounds_ev():
    return data()["bounds"]


def groups():
    return len(bounds_ev()) - 1


def has(name):
    return f"{name}/z" in data()


def nuclide_name(z, a):
    from nuclear import nuclear_data
    return f"{nuclear_data.SYMBOLS[z]}{a}"


def get(name, key):
    return data()[f"{name}/{key}"]


def fissile_data(name):
    return f"{name}/chi" in data()


def mass_u(name):
    """Masse atomique mesurée (AME), en u."""
    from nuclear import nuclear_data
    z, a = int(get(name, "z")), int(get(name, "a"))
    return nuclear_data.atomic_mass_u(z, a) or float(get(name, "awr")) * 1.00866491595


def _shielded(name, sigma0):
    """Les sections efficaces du noyau (7, G) pour une section de dilution σ₀ par groupe (G,)."""
    table = get(name, "shielded").astype(float)            # (σ₀, 7, G)
    grid = np.log(data()["sigma0"])
    x = np.clip(np.log(np.maximum(sigma0, 1e-30)), grid[0], grid[-1])
    out = np.empty(table.shape[1:])
    for g in range(table.shape[2]):
        for r in range(table.shape[1]):
            out[r, g] = np.interp(x[g], grid, table[:, r, g])
    return out


def macroscopic(composition, chord_cm=None):
    """Les sections efficaces macroscopiques d'un matériau homogène.

    composition : {nom du noyau: densité atomique en atomes/(barn·cm)} ; chord_cm : corde moyenne ℓ̄ du morceau
    (None : milieu infini, aucune fuite ne creuse les résonances). Rend un dict prêt pour transport.solve.
    """
    g = groups()
    escape = 0.0 if not chord_cm else 1.0 / chord_cm
    micro = {n: get(n, "shielded")[-1].astype(float) for n in composition}
    for _ in range(3):
        total = sum(density * micro[n][TOTAL] for n, density in composition.items())
        micro = {n: _shielded(n, (total - density * micro[n][TOTAL] + escape) / density)
                 for n, density in composition.items() if density > 0}
    sig_t = np.zeros(g)
    scatter = np.zeros((g, g))
    current = np.zeros(g)
    nu_fission = np.zeros(g)
    fission = np.zeros(g)
    capture = np.zeros(g)
    production_chi = np.zeros((g, g))
    for n, density in composition.items():
        if density <= 0:
            continue
        m = micro[n] * density
        sig_t += m[TOTAL]
        scatter += m[ELASTIC][:, None] * get(n, "elastic") + m[INELASTIC][:, None] * get(n, "inelastic")
        current += m[ELASTIC] * get(n, "elastic_mu") + m[INELASTIC] * get(n, "inelastic_mu")
        fission += m[FISSION]
        nu_fission += m[NU_FISSION]
        capture += np.maximum(m[ABSORPTION], 0)
        if fissile_data(n):
            production_chi += m[NU_FISSION][:, None] * get(n, "chi")
    with np.errstate(invalid="ignore", divide="ignore"):
        chi = np.where(nu_fission[:, None] > 0, production_chi / np.where(nu_fission[:, None] > 0, nu_fission[:, None], 1), 0)
    # Correction de transport : la part μ̄Σ_s des chocs ne dévie presque pas ; on la retire du total et de
    # l'auto-diffusion (le solveur suppose ensuite les chocs isotropes).
    transport = sig_t - current
    scatter_tr = scatter.copy()
    scatter_tr[np.arange(g), np.arange(g)] -= current
    return {"total": transport, "scatter": scatter_tr, "nu_fission": nu_fission, "fission": fission,
            "capture": capture, "absorption": transport - scatter_tr.sum(axis=1), "chi": chi,
            "inverse_speed": get(next(iter(composition)), "inverse_speed").astype(float),
            "total_untransported": sig_t, "micro": micro}


def delayed(composition, micro=None):
    """Les précurseurs de neutrons retardés du mélange : constantes λ_i et rendements ν_d,i par groupe (cm⁻¹)."""
    lambdas = {}
    for n, density in composition.items():
        if density <= 0 or not fissile_data(n):
            continue
        sigma_f = (micro[n] if micro else get(n, "shielded")[-1])[FISSION] * density
        for lam, nu in zip(get(n, "decay_constants"), get(n, "delayed_nu")):
            key = round(float(lam), 5)
            lambdas[key] = lambdas.get(key, 0) + sigma_f * nu
    keys = sorted(lambdas)
    return np.array(keys), np.array([lambdas[k] for k in keys]) if keys else np.zeros((0, groups()))
