"""Recouvrements entre gaussiennes contractées, et leurs dérivées, par la récurrence d'Obara–Saika.

Une orbitale de Slater est développée en N gaussiennes (STO-NG). Le recouvrement de deux gaussiennes centrées en A et B
se factorise selon x, y, z ; chaque facteur suit la récurrence

    S(i+1, j) = (P − A) S(i, j) + [i S(i−1, j) + j S(i, j−1)] / 2p,

avec p = α + β et P = (αA + βB)/p. La dérivée par rapport à A n'est qu'une autre combinaison :
d/dA_x [x_A^i e^(−α x_A²)] = 2α [i+1] − i [i−1]. Tout est calculé d'un coup pour toutes les paires de couches
de même type (s–s, s–p, p–d…), puis tourné vers les harmoniques sphériques réelles. Le moment ⟨a|(x − A_x)|b⟩
n'est lui aussi qu'un facteur S(i+1, j) selon x : il donne le dipôle de chaque atome.
"""

import math

import numpy as np

# Les composantes cartésiennes (exposants de x, y, z) de chaque moment angulaire.
CARTESIAN = {
    0: [(0, 0, 0)],
    1: [(1, 0, 0), (0, 1, 0), (0, 0, 1)],
    2: [(2, 0, 0), (0, 2, 0), (0, 0, 2), (1, 1, 0), (1, 0, 1), (0, 1, 1)],
}
_S3 = math.sqrt(3.0)
# Cartésien (normalisé comme x^l) → sphérique réel, ordre m = −l … l.
SPHERICAL = {
    0: np.array([[1.0]]),
    # p : m = −1, 0, 1 → y, z, x
    1: np.array([[0.0, 1.0, 0.0], [0.0, 0.0, 1.0], [1.0, 0.0, 0.0]]),
    # d : xy, yz, z², xz, x² − y²
    2: np.array([
        [0, 0, 0, _S3, 0, 0],
        [0, 0, 0, 0, 0, _S3],
        [-0.5, -0.5, 1.0, 0, 0, 0],
        [0, 0, 0, 0, _S3, 0],
        [_S3 / 2, -_S3 / 2, 0, 0, 0, 0],
    ]),
}


def _one_dimension(pa, pb, inv2p, imax, jmax):
    """Les facteurs S(i, j) selon un axe, pour i ≤ imax, j ≤ jmax ; formes (..., imax+1, jmax+1)."""
    shape = pa.shape
    table = np.zeros(shape + (imax + 1, jmax + 1))
    table[..., 0, 0] = 1.0
    for i in range(imax + 1):
        for j in range(jmax + 1):
            if i == 0 and j == 0:
                continue
            if i > 0:
                value = pa * table[..., i - 1, j]
                if i > 1:
                    value = value + (i - 1) * inv2p * table[..., i - 2, j]
                if j > 0:
                    value = value + j * inv2p * table[..., i - 1, j - 1]
            else:
                value = pb * table[..., i, j - 1]
                if j > 1:
                    value = value + (j - 1) * inv2p * table[..., i, j - 2]
            table[..., i, j] = value
    return table


def shell_pairs(la, lb, centers_a, centers_b, alpha_a, coef_a, alpha_b, coef_b, derivative=False, moment=False):
    """Recouvrements (et dérivées par rapport au centre A) pour une liste de paires de couches de moments la, lb.

    centers : (n, 3) ; alpha, coef : (n, k) (les primitives manquantes ont un coefficient nul).
    Rend S (n, 2la+1, 2lb+1) et, si demandé, dS/dA (n, 3, 2la+1, 2lb+1). dS/dB = −dS/dA.
    Avec moment=True, rend à la place de dS/dA les moments ⟨a|(r − A)|b⟩ (n, 3, 2la+1, 2lb+1) ; avec les deux, rend
    S, dS/dA et les moments, tirés des mêmes tables.
    """
    a = alpha_a[:, :, None]
    b = alpha_b[:, None, :]
    p = a + b
    inv2p = 0.5 / p
    ab = centers_a - centers_b                                          # (n, 3)
    r2 = np.sum(ab * ab, axis=1)[:, None, None]
    prefactor = np.exp(-a * b / p * r2) * (math.pi / p) ** 1.5 * coef_a[:, :, None] * coef_b[:, None, :]
    extra = 1 if derivative or moment else 0
    tables = []
    for axis in range(3):
        center_p = (a * centers_a[:, axis, None, None] + b * centers_b[:, axis, None, None]) / p
        pa = center_p - centers_a[:, axis, None, None]
        pb = center_p - centers_b[:, axis, None, None]
        tables.append(_one_dimension(pa, pb, inv2p, la + extra, lb))
    # Toutes les composantes cartésiennes d'un coup : facteurs (n, k, k', composante de A, composante de B).
    pa_pow = np.array(CARTESIAN[la]).T                                   # (3, na)
    pb_pow = np.array(CARTESIAN[lb]).T
    factors = [tables[axis][..., pa_pow[axis][:, None], pb_pow[axis][None, :]] for axis in range(3)]
    ta, tb = SPHERICAL[la], SPHERICAL[lb]
    n = len(centers_a)
    weights = prefactor.reshape(n, 1, -1)

    def contract(values):
        return ta @ (weights @ values.reshape(n, weights.shape[2], -1)).reshape(n, len(ta[0]), len(tb[0])) @ tb.T

    overlap = contract(factors[0] * factors[1] * factors[2])
    grad = moments = None
    if moment:
        # (x − A_x) · x_A^i = x_A^(i+1) : le facteur selon l'axe passe de S(i, j) à S(i+1, j).
        moments = np.empty((n, 3) + overlap.shape[1:])
        for axis in range(3):
            raised = tables[axis][..., pa_pow[axis][:, None] + 1, pb_pow[axis][None, :]]
            others = factors[(axis + 1) % 3] * factors[(axis + 2) % 3]
            moments[:, axis] = contract(raised * others)
        if not derivative:
            return overlap, moments
    if derivative:
        # d/dA_x [x_A^i e^(−α x_A²)] = 2α S(i+1) − i S(i−1).
        grad = np.empty((n, 3) + overlap.shape[1:])
        for axis in range(3):
            power = pa_pow[axis][:, None]
            table = tables[axis]
            up = 2 * a[..., None, None] * table[..., power + 1, pb_pow[axis][None, :]]
            up = up - power * table[..., np.maximum(power - 1, 0), pb_pow[axis][None, :]]
            others = factors[(axis + 1) % 3] * factors[(axis + 2) % 3]
            grad[:, axis] = contract(up * others)
    if moments is not None:
        return overlap, grad, moments
    return overlap, grad


def gaussian_values(l, points, center, alpha, coef):
    """Valeurs de la couche (2l+1 fonctions sphériques) aux points (m, 3), en bohr."""
    d = points - center
    r2 = np.einsum("pk,pk->p", d, d)
    # Au-delà de ce rayon, la couche vaut moins de 10⁻¹² : on ne calcule que les points en deçà.
    live = alpha[np.abs(coef) > 0]
    reach = (math.log(float(np.sum(np.abs(coef))) + 1e-300) + 12 * math.log(10) + 8) / float(live.min())
    near = np.nonzero(r2 < reach)[0]
    out = np.zeros((2 * l + 1, len(points)))
    if not len(near):
        return out
    dn, rn = d[near], r2[near]
    radial = np.exp(-rn[:, None] * alpha[None, :]) @ coef
    cart = np.empty((len(CARTESIAN[l]), len(near)))
    for i, (x, y, z) in enumerate(CARTESIAN[l]):
        value = radial
        for axis, power in enumerate((x, y, z)):
            for _ in range(power):
                value = value * dn[:, axis]
        cart[i] = value
    out[:, near] = SPHERICAL[l] @ cart
    return out
