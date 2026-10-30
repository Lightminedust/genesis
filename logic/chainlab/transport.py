"""Le transport des neutrons dans une sphère : un cœur et son réflecteur, en groupes d'énergie.

Méthode des probabilités de première collision. La sphère est découpée en coquilles où le flux est supposé
uniforme ; P[g, i, j] est la probabilité qu'un neutron du groupe g, né uniformément et dans une direction au hasard
dans la coquille i, fasse sa première collision dans la coquille j (le reste s'échappe). Pour une sphère, toutes les
directions se valent : il suffit d'intégrer le long des cordes parallèles à une seule direction, chaque corde pesant
l'aire 2πy dy qu'elle traverse. Le long d'une corde, l'atténuation est exacte (e^(−τ)) ; seule l'intégrale sur le
paramètre d'impact y est numérique (Gauss-Legendre, après le changement de variable qui efface la racine carrée au
bord de chaque coquille).

La diffusion est supposée isotrope dans le laboratoire, avec la correction de transport : la part μ̄·Σ_s des chocs,
qui ne dévie presque pas le neutron, est retirée de la section efficace totale et de l'auto-diffusion. Ce que la
correction ne capte pas (l'anisotropie au-delà du premier moment) reste une limite du modèle.

Le flux suit l'équation de bilan, groupe par groupe :
    Σ_t,g,j φ_g,j V_j = Σ_i P_g,ij V_i [ Σ_g' Σ_s,g'→g,i φ_g',i + χ_g,i (νΣ_f φ)_i / k ]
et k_eff est la valeur propre trouvée par itération de puissance.
"""

import numpy as np

QUADRATURE = 6                 # points de Gauss par anneau de cordes
NEUTRON_MASS_MEV = 939.56542   # CODATA 2018
C_CM_S = 2.99792458e10


def shells(core_radius, reflector_thickness=0.0, core_shells=10, reflector_shells=8):
    """Rayons extérieurs des coquilles (cm) et région de chacune (0 cœur, 1 réflecteur).

    Le cœur est découpé en coquilles de même volume : elles s'affinent vers la surface, là où le flux varie le plus.
    Le réflecteur, en coquilles qui s'épaississent en s'éloignant du cœur.
    """
    radii = list(core_radius * (np.arange(1, core_shells + 1) / core_shells) ** (1 / 3))
    region = [0] * core_shells
    if reflector_thickness > 0:
        growth = 1.3
        widths = growth ** np.arange(reflector_shells)
        edges = core_radius + reflector_thickness * np.cumsum(widths) / widths.sum()
        radii += list(edges)
        region += [1] * reflector_shells
    return np.array(radii), np.array(region)


def collision_probabilities(radii, sigma):
    """Les probabilités de première collision dans des coquilles concentriques.

    radii : (N,) rayons extérieurs croissants, en cm ; sigma : (G, N) sections efficaces totales macroscopiques
    (cm⁻¹). Rend (P, escape, volumes) : P (G, N, N), la fuite (G, N), et les volumes des coquilles intégrés par les
    mêmes cordes (leur écart aux volumes exacts mesure la précision de la quadrature).
    """
    radii = np.asarray(radii, float)
    sigma = np.maximum(np.asarray(sigma, float), 1e-12)
    groups, n = sigma.shape
    nodes, weights = np.polynomial.legendre.leggauss(QUADRATURE)
    s, ws = 0.5 * (nodes + 1), 0.5 * weights
    inner = np.concatenate(([0.0], radii[:-1]))
    collide = np.zeros((groups, n, n))
    escape = np.zeros((groups, n))
    volume = np.zeros(n)
    for k in range(n):
        # Les cordes dont le point le plus proche du centre tombe dans la coquille k : y² = b² − (b² − a²) s².
        a2, b2 = inner[k] ** 2, radii[k] ** 2
        y2 = b2 - (b2 - a2) * s ** 2
        area = 2 * np.pi * (b2 - a2) * s * ws                       # π d(y²)
        outer = np.arange(n - 1, k, -1)
        half = (np.sqrt(np.maximum(radii[outer][None, :] ** 2 - y2[:, None], 0))
                - np.sqrt(np.maximum(inner[outer][None, :] ** 2 - y2[:, None], 0)))
        middle = 2 * np.sqrt(np.maximum(b2 - y2, 0))
        lengths = np.concatenate([half, middle[:, None], half[:, ::-1]], axis=1)       # (Q, S)
        order = np.concatenate([outer, [k], outer[::-1]])
        segments = len(order)
        sig = sigma[:, order][None]                                   # (1, G, S)
        tau = sig * lengths[:, None, :]                               # (Q, G, S)
        end = np.cumsum(tau, axis=2)
        start = end - tau
        leave = -np.expm1(-tau)                                       # 1 − e^(−τ)
        upper = np.triu(np.ones((segments, segments), bool), 1)
        gap = np.where(upper, start[:, :, None, :] - end[:, :, :, None], 0.0)
        pair = np.where(upper, leave[:, :, :, None] * np.exp(-gap) * leave[:, :, None, :], 0.0) / sig[:, :, :, None]
        alone = (tau + np.expm1(-tau)) / sig                          # même segment : τ − 1 + e^(−τ)
        out = leave * np.exp(-(end[:, :, -1:] - end)) / sig
        to_shell = np.zeros((segments, n))
        to_shell[np.arange(segments), order] = 1
        both = np.tensordot(area, pair, axes=(0, 0)) + np.einsum('qga,ab->gab', area[:, None, None] * alone,
                                                                  np.eye(segments))
        collide += to_shell.T[None] @ both @ to_shell[None]
        escape += np.tensordot(area, out, axes=(0, 0)) @ to_shell
        volume += area @ lengths @ to_shell
    return collide / volume[None, :, None], escape / volume[None, :], volume


class Solution:
    """Ce que rend un calcul : k_eff, les flux et les bilans de neutrons."""

    def __init__(self, **values):
        self.__dict__.update(values)


def solve(radii, region, materials, tol=2e-6, max_outer=600, source_spectrum=None):
    """k_eff et le flux d'une sphère à coquilles.

    materials : une entrée par région (0 cœur, 1 réflecteur), chacune un dict de sections efficaces macroscopiques
    (cm⁻¹) en G groupes, déjà corrigées du transport :
        total (G,), scatter (G, G) [g → g'], nu_fission (G,), fission (G,), absorption (G,),
        chi (G, G) [groupe du neutron qui fissionne → groupe du neutron né], inverse_speed (G,) en s/cm.
    Sans aucune fission, il n'y a pas de valeur propre : k = 0, et le flux est celui d'un neutron injecté
    uniformément dans le cœur avec `source_spectrum` (son destin : diffusé, capturé ou échappé).
    """
    radii = np.asarray(radii, float)
    region = np.asarray(region, int)
    n = len(radii)
    groups = len(materials[0]["total"])
    sig_t = np.stack([materials[r]["total"] for r in region], axis=1)            # (G, N)
    scatter = np.stack([materials[r]["scatter"] for r in region])                # (N, G, G)
    nu_fission = np.stack([materials[r]["nu_fission"] for r in region], axis=1)
    fission = np.stack([materials[r]["fission"] for r in region], axis=1)
    absorption = np.stack([materials[r]["absorption"] for r in region], axis=1)
    capture = np.stack([materials[r].get("capture", materials[r]["absorption"] - materials[r]["fission"])
                        for r in region], axis=1)
    chi = np.stack([materials[r]["chi"] for r in region])                        # (N, G, G)
    inverse_speed = materials[0]["inverse_speed"]
    probabilities, escape, volume = collision_probabilities(radii, sig_t)
    # φ_g = A_g q_g avec A_g[j, i] = P_g,ij V_i / (Σ_t,g,j V_j) ; l'auto-diffusion est résolue d'un coup.
    own = scatter[:, np.arange(groups), np.arange(groups)].T                    # (G, N)
    response = np.empty((groups, n, n))
    for g in range(groups):
        a = probabilities[g].T * volume[None, :] / (sig_t[g] * volume)[:, None]
        response[g] = np.linalg.solve(np.eye(n) - a * own[g][None, :], a)
    transfer = scatter.copy()
    transfer[:, np.arange(groups), np.arange(groups)] = 0.0
    phi = np.ones((groups, n))
    k = 1.0
    production = float(np.sum(nu_fission * phi * volume))
    if production <= 0:
        # Rien ne fissionne : aucune génération ne suit la première. On suit un neutron injecté dans le cœur.
        return _fixed_source(radii, region, volume, groups, response, transfer, scatter, escape, absorption,
                             capture, source_spectrum, tol, max_outer)
    converged = False
    iterations = 0
    for iterations in range(1, max_outer + 1):
        births = np.einsum('ngh,gn->hn', chi, nu_fission * phi) / k              # (G, N) neutrons nés par cm³
        for g in range(groups):
            q = births[g] + np.einsum('ng,gn->n', transfer[:, :, g], phi)
            phi[g] = response[g] @ q
        new_production = float(np.sum(nu_fission * phi * volume))
        k_new = k * new_production / production
        change = abs(k_new - k)
        k, production = k_new, new_production
        if change < tol and iterations > 4:
            converged = True
            break
    phi /= production
    births = np.einsum('ngh,gn->hn', chi, nu_fission * phi) / k
    source = births + np.einsum('ngh,gn->hn', scatter, phi)                      # (G, N), auto-diffusion comprise
    leak_group = np.sum(source * escape * volume, axis=1)
    fission_rate = np.sum(fission * phi * volume, axis=0)
    absorbed = np.sum(absorption * phi * volume, axis=0)
    captured = np.sum(capture * phi * volume, axis=0)
    importance = _adjoint(probabilities, sig_t, scatter, transfer, own, nu_fission, chi, k, tol, max_outer)
    emitted = np.einsum('ngh,gn->hn', chi, nu_fission * phi)                     # neutrons nés, sans 1/k
    weighted_present = float(np.sum(importance * phi * inverse_speed[:, None] * volume))
    weighted_emitted = float(np.sum(importance * emitted * volume))
    return Solution(importance=importance,
        # Temps de génération des neutrons prompts, pondéré par leur importance (flux adjoint) : un neutron lent
        # qui s'attarde loin dans le réflecteur compte moins qu'un neutron rapide au cœur.
        generation_time=weighted_present / weighted_emitted if weighted_emitted > 0 else 0.0,
        unweighted_generation_time=float(np.sum(phi * inverse_speed[:, None] * volume)) / float(np.sum(nu_fission * phi * volume)),
        k=k, converged=converged, iterations=iterations, radii=radii, region=region, volume=volume,
        flux=phi, leakage=float(leak_group.sum()), leakage_by_group=leak_group,
        fission_by_shell=fission_rate, absorption_by_shell=absorbed, capture_by_shell=captured,
        fission_by_group=np.sum(fission * phi * volume, axis=1), production_by_group=np.sum(nu_fission * phi * volume, axis=1),
        fissions=float(fission_rate.sum()), absorption=float(absorbed.sum()),
        balance=float(np.sum(nu_fission * phi * volume)) / k - float(absorbed.sum()) - float(leak_group.sum()))


def _adjoint(probabilities, sig_t, scatter, transfer, own, nu_fission, chi, k, tol, max_outer):
    """L'importance d'un neutron émis dans le groupe g, la coquille i (flux adjoint) :
    φ*_g,i = Σ_j P_g,ij · [Σ_g' Σ_s,g→g',j φ*_g',j + (νΣ_f,g,j / k) Σ_g' χ_g→g',j φ*_g',j] / Σ_t,g,j
    (un neutron émis fait son premier choc en j ; il y diffuse ou y fait fissionner, et la suite vaut ce qu'elle vaut)."""
    groups, n = sig_t.shape
    response = np.empty((groups, n, n))
    for g in range(groups):
        a = probabilities[g] / sig_t[g][None, :]
        response[g] = np.linalg.solve(np.eye(n) - a * own[g][None, :], a)
    importance = np.ones((groups, n))
    previous = None
    for _ in range(max_outer):
        fission_value = np.einsum('ngh,hn->gn', chi, importance) * nu_fission / k
        for g in reversed(range(groups)):
            rest = np.einsum('nh,hn->n', transfer[:, g, :], importance) + fission_value[g]
            importance[g] = response[g] @ rest
        norm = float(np.abs(importance).max())
        importance /= norm if norm > 0 else 1.0
        if previous is not None and float(np.abs(importance - previous).max()) < tol:
            break
        previous = importance.copy()
    return importance


def _fixed_source(radii, region, volume, groups, response, transfer, scatter, escape, absorption, capture,
                  spectrum, tol, max_outer):
    """Un neutron injecté uniformément dans le cœur (aucune fission) : flux, fuite et captures par neutron."""
    n = len(radii)
    core = region == 0
    spectrum = np.eye(groups)[0] if spectrum is None else np.asarray(spectrum, float) / float(np.sum(spectrum))
    external = np.zeros((groups, n))
    external[:, core] = spectrum[:, None] / float(volume[core].sum())
    phi = np.zeros((groups, n))
    iterations = 0
    for iterations in range(1, max_outer + 1):
        previous = phi.copy()
        for g in range(groups):
            q = external[g] + np.einsum('ng,gn->n', transfer[:, :, g], phi)
            phi[g] = response[g] @ q
        if np.abs(phi - previous).max() <= tol * max(float(np.abs(phi).max()), 1e-300):
            break
    source = external + np.einsum('ngh,gn->hn', scatter, phi)
    leak_group = np.sum(source * escape * volume, axis=1)
    absorbed = np.sum(absorption * phi * volume, axis=0)
    captured = np.sum(capture * phi * volume, axis=0)
    zeros_n, zeros_g = np.zeros(n), np.zeros(groups)
    return Solution(k=0.0, converged=True, iterations=iterations, radii=radii, region=region, volume=volume,
                    flux=phi, importance=np.zeros((groups, n)), leakage=float(leak_group.sum()),
                    leakage_by_group=leak_group, fission_by_shell=zeros_n, absorption_by_shell=absorbed,
                    capture_by_shell=captured, fission_by_group=zeros_g, production_by_group=zeros_g, fissions=0.0,
                    absorption=float(absorbed.sum()), generation_time=0.0, unweighted_generation_time=0.0,
                    balance=1.0 - float(absorbed.sum()) - float(leak_group.sum()))
