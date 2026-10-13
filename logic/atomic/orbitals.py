"""Forme réelle du nuage électronique de valence.

La vue quantique montre |psi|², la densité de probabilité de présence des
électrons de la sous-couche de valence, dans une coupe du plan xz.

    psi_nlm(r, θ, φ) = R_nl(r) · Y_lm(θ, φ)

  - R_nl est la fonction radiale hydrogénoïde : ρ^l e^(-ρ/2) L_(n-l-1)^(2l+1)(ρ),
    avec ρ = 2 Z_eff r / (n a0). Elle porte les n-l-1 nœuds radiaux, qui
    apparaissent comme des anneaux sombres (3s en a deux).
  - Y_lm sont les harmoniques sphériques réelles, qui donnent la forme :
    sphère pour s, deux lobes pour p, trèfle pour d...
  - Z_eff vient des règles de Slater (écrantage par les autres électrons).

Les orbitales d'une même sous-couche sont remplies selon la règle de Hund :
un électron dans chaque orbitale avant d'en apparier deux. Une sous-couche
pleine ou à moitié pleine redevient sphérique (théorème d'Unsöld), ce que
la coupe montre fidèlement.

Pour un atome isolé, les orbitales d'une sous-couche sont dégénérées :
l'orientation est arbitraire, pas la forme.
"""

import math
from functools import lru_cache

from scipy.special import eval_genlaguerre

from atomic.slater import BOHR_RADIUS_PM, effective_charge

GRID = 88
SUBSHELL_LETTERS = "spdf"

# Ordre de remplissage de Hund, avec la forme de chaque orbitale réelle dans
# le plan xz. Chaque fonction reçoit (cos θ, x/r, z/r) et renvoie Y.
# En y = 0, les orbitales orientées selon y s'annulent : elles sont bien
# occupées, simplement invisibles dans cette coupe.
_ORBITALS = {
    0: [("s", lambda c, xr, zr: 0.28209479)],
    1: [
        ("p_z", lambda c, xr, zr: 0.48860251 * zr),
        ("p_y", lambda c, xr, zr: 0.0),
        ("p_x", lambda c, xr, zr: 0.48860251 * xr),
    ],
    2: [
        ("d_z²", lambda c, xr, zr: 0.31539157 * (3 * zr * zr - 1)),
        ("d_xz", lambda c, xr, zr: 1.09254843 * xr * zr),
        ("d_x²-y²", lambda c, xr, zr: 0.54627422 * xr * xr),
        ("d_yz", lambda c, xr, zr: 0.0),
        ("d_xy", lambda c, xr, zr: 0.0),
    ],
    3: [
        ("f_z³", lambda c, xr, zr: 0.37317633 * (5 * zr ** 3 - 3 * zr)),
        ("f_xz²", lambda c, xr, zr: 0.45704580 * xr * (5 * zr * zr - 1)),
        ("f_z(x²-y²)", lambda c, xr, zr: 1.44530572 * zr * xr * xr),
        ("f_x(x²-3y²)", lambda c, xr, zr: 0.59004359 * xr ** 3),
        ("f_yz²", lambda c, xr, zr: 0.0),
        ("f_xyz", lambda c, xr, zr: 0.0),
        ("f_y(3x²-y²)", lambda c, xr, zr: 0.0),
    ],
}


def hund_occupation(l, electrons):
    """Répartit les électrons d'une sous-couche sur ses 2l+1 orbitales.

    Un par orbitale d'abord (spins parallèles), puis appariement.
    3 électrons en p donnent [1, 1, 1] ; 4 donnent [2, 1, 1].
    """
    orbitals = 2 * l + 1
    occupation = [0] * orbitals
    for index in range(min(electrons, 2 * orbitals)):
        occupation[index % orbitals] += 1
    return occupation


def radial(n, l, z_eff, radius_pm):
    """Fonction radiale hydrogénoïde R_nl, à un facteur de normalisation près
    (commun à toutes les orbitales de la sous-couche, donc sans effet sur la forme)."""
    rho = 2.0 * z_eff * radius_pm / (n * BOHR_RADIUS_PM)
    return rho ** l * math.exp(-rho / 2.0) * eval_genlaguerre(n - l - 1, 2 * l + 1, rho)


def extent_pm(n, l, z_eff):
    """Demi-largeur de la coupe : assez pour contenir l'essentiel du nuage."""
    mean_radius = BOHR_RADIUS_PM / (2.0 * z_eff) * (3 * n * n - l * (l + 1))
    return 2.1 * mean_radius


@lru_cache(maxsize=256)
def _density_grid(n, l, occupation, z_eff_rounded):
    """Coupe |psi|² sur une grille GRID x GRID, normalisée à 1.

    Mis en cache : ajouter un neutron ne change pas le nuage électronique,
    inutile de le recalculer.
    """
    z_eff = z_eff_rounded
    half = extent_pm(n, l, z_eff)
    shapes = _ORBITALS[min(l, 3)]
    grid = []
    peak = 0.0
    for row in range(GRID):
        z = half * (1.0 - 2.0 * (row + 0.5) / GRID)
        for column in range(GRID):
            x = half * (2.0 * (column + 0.5) / GRID - 1.0)
            r = math.hypot(x, z)
            if r < 1e-9:
                xr, zr = 0.0, 1.0
            else:
                xr, zr = x / r, z / r
            radial_sq = radial(n, l, z_eff, r) ** 2
            value = 0.0
            for (_, shape), count in zip(shapes, occupation):
                if count:
                    value += count * shape(zr, xr, zr) ** 2
            density = radial_sq * value
            grid.append(density)
            peak = max(peak, density)
    if peak > 0:
        grid = [value / peak for value in grid]
    return tuple(grid), half


def valence_subshell(subshells):
    """Dernière sous-couche occupée dans l'ordre de remplissage : c'est
    elle qui porte les électrons de plus haute énergie (3d pour le fer)."""
    occupied = [entry for entry in subshells if entry[2] > 0]
    return occupied[-1] if occupied else None


def valence_orbital(z, subshells):
    """Nuage de la sous-couche de valence, prêt à être peint.

    Retourne un dict (grille, portée, étiquette, occupation par orbitale)
    ou None si l'atome n'a aucun électron.
    """
    valence = valence_subshell(subshells)
    if valence is None or z <= 0:
        return None
    n, l, electrons = valence
    z_eff = effective_charge(z, subshells, n, l)
    occupation = hund_occupation(l, electrons)
    grid, half = _density_grid(n, l, tuple(occupation), round(z_eff, 3))

    shapes = _ORBITALS[min(l, 3)]
    return {
        "grid": list(grid),
        "size": GRID,
        "extent_pm": half,
        "label": f"{n}{SUBSHELL_LETTERS[l]}{electrons}",
        "n": n,
        "l": l,
        "z_eff": z_eff,
        "radial_nodes": n - l - 1,
        "orbital_names": [name for name, _ in shapes],
        "occupation": occupation,
    }


@lru_cache(maxsize=256)
def _hydrogenic_grid(n, l, charge):
    """Coupe d'un mélange équiprobable des m : isotrope, sans orientation inventée.

    Le moteur suit (n,l), pas m ni la polarisation. La somme sur m des
    |Y_lm|²/(2l+1) vaut 1/(4π). La normalisation au maximum sert au rendu.
    """
    half = extent_pm(n, l, charge)
    grid = []
    for row in range(GRID):
        z = half * (1 - 2 * (row + 0.5) / GRID)
        for column in range(GRID):
            x = half * (2 * (column + 0.5) / GRID - 1)
            grid.append(radial(n, l, charge, math.hypot(x, z)) ** 2)
    peak = max(grid)
    return tuple(value / peak for value in grid), half


def hydrogenic_orbital(z, state, reduced_mass=1.0):
    """État réellement occupé ; correction de masse réduite dans l'échelle."""
    n, l = state
    if z <= 0 or n < 1 or not 0 <= l < n:
        raise ValueError("État hydrogénoïde invalide")
    charge = z * reduced_mass
    grid, half = _hydrogenic_grid(n, l, charge)
    return {"grid": list(grid), "size": GRID, "extent_pm": half,
            "label": f"{n}{'spdfghi'[l]}1", "n": n, "l": l,
            "z_eff": charge, "radial_nodes": n - l - 1,
            "orbital_names": ["m non résolu"], "occupation": [1]}
