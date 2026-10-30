"""Ce que voient les yeux : la densité électronique ρ(r) = |Ψ|², calculée sur une grille autour de la molécule.

    ρ(r)  = Σ_μν P_μν φ_μ(r) φ_ν(r)                      les électrons de valence de la molécule (GFN1-xTB)
    ρ₀(r) = Σ_A Σ_μ∈A (n⁰_μ) φ_μ(r)²                     les mêmes atomes, isolés, posés aux mêmes places
    Δρ    = ρ − ρ₀                                       où les électrons sont allés en formant les liaisons
    ρ = ρ_at + ρ_ov :
      ρ_at(r) = Σ_A Σ_μν∈A P_μν φ_μ φ_ν                    les nuages des atomes (blocs d'un même atome, polarisés)
      ρ_ov(r) = Σ_A≠B Σ_μ∈A,ν∈B P_μν φ_μ φ_ν               le recouvrement entre atomes : la liaison (CLOA) ;
                                                          positif où les orbitales s'additionnent (liant), négatif
                                                          où elles s'opposent (antiliant, répulsion de Pauli)
    ψ_i(r) = Σ_μ C_μi φ_μ(r)                             une orbitale moléculaire, avec son signe (sa phase)
    φ(r)  = Σ_A Z_A / |r − R_A| − ∫ ρ(r′) / |r − r′| dr′   le potentiel électrostatique de la molécule (noyaux avec leur
                                                          charge de valence, nuage de valence), en volts
    P⁰(d) = ∫ ρ⁰_A(√(d² + s²)) ds                         le nuage de l'atome libre vu en transparence : les électrons
                                                          par Å² le long d'une droite qui passe à d du noyau (le halo)

n⁰_μ est l'occupation de référence de la couche (2s¹ pour H, 2s² 2p² pour C…) répartie également sur ses 2l+1
orbitales : la densité sphérique de l'atome libre. Rien n'est lissé ni dessiné à la main : le pont lumineux entre
deux noyaux est l'accumulation calculée des électrons, et il s'efface quand la liaison casse.

Les grilles partent vers le client en entiers 16 bits : valeur = max · (q / 65535)^4 pour ρ (une plage de 10⁻¹⁹,
assez pour suivre le brouillard jusqu'à ses bords), valeur = max · signe · (|q| / 32767)² pour Δρ et ψ.
"""

from functools import lru_cache

import numpy as np
from scipy import fft

from . import integrals, xtb
from .xtb import BOHR_ANGSTROM

MARGIN = 2.6           # Å autour des noyaux
ORBITAL_MARGIN = 4.0   # Å : une orbitale vide peut être diffuse (la LUMO de l'eau, sur les 2s de H)…
ORBITAL_MARGIN_MAX = 9.0  # Å : … la boîte s'agrandit jusque-là pour que son nuage y tienne
ORBITAL_FLOOR = 0.03      # |ψ| sous 3 % de son maximum sur les faces de la boîte…
ORBITAL_ENCLOSED = 0.85   # … et sous le niveau des surfaces du client (OrbitalSurface.ENCLOSED : 85 % de |ψ|²)
MAX_POINTS = 52        # points par côté, au plus (molécule immobile)
MOVING_POINTS = 30     # pendant la dynamique
# Une orbitale se dessine en surface : il lui faut une maille fine (ses lobes peuvent n'avoir que 0,5 Å entre deux
# nœuds), que permet son calcul séparable (orbital_values) — 80 points au repos, 56 pendant la dynamique.
ORBITAL_POINTS = 80
ORBITAL_MOVING_POINTS = 56
MIN_SPACING = 0.14     # Å
DENSITY_POWER = 4.0
SIGNED_POWER = 2.0
BOHR3_TO_ANG3 = 1 / BOHR_ANGSTROM ** 3
COULOMB_VOLT_ANGSTROM = 14.399645          # e / (4π ε₀ · 1 Å), en volts
CUBE_SELF = 2.3800772                      # ∫ 1/r sur un cube de côté 1 centré sur l'origine
ESP_LIMIT = 25.0                           # V : au-delà (dans les cœurs, jamais montrés), écrêté avant l'envoi
PROJECTION_STEP = 0.05                     # Å, entre deux valeurs du nuage projeté
PROJECTION_REACH = 7.0                     # Å : au-delà, l'atome libre n'a plus d'électrons à montrer
SURFACE_AU = 0.002                         # e/bohr³ : la surface conventionnelle d'un nuage (celle des cartes de potentiel)
PROFILE_SAMPLES = 20                       # tranches le long d'une liaison, d'un noyau à l'autre
# La quadrature dans le plan d'une tranche : des anneaux autour de l'axe (Å), huit points chacun.
_RING_RADII = np.array([0.05, 0.15, 0.27, 0.42, 0.62, 0.90, 1.30])
_RING_EDGES = np.concatenate([[0.0], 0.5 * (_RING_RADII[1:] + _RING_RADII[:-1]), [1.6]])
_RING_ANGLES = 8


def grid_for(positions_angstrom, points=MAX_POINTS, margin=MARGIN):
    """Origine, pas (Å) et nombre de points d'une grille cubique qui enveloppe les noyaux."""
    low = positions_angstrom.min(axis=0) - margin
    high = positions_angstrom.max(axis=0) + margin
    span = high - low
    spacing = max(MIN_SPACING, float(span.max()) / (points - 1))
    counts = np.minimum(np.ceil(span / spacing).astype(int) + 1, points)
    center = 0.5 * (low + high)
    origin = center - 0.5 * spacing * (counts - 1)
    return origin, spacing, counts


def _points(origin, spacing, counts):
    axes = [origin[k] + spacing * np.arange(counts[k]) for k in range(3)]
    x, y, z = np.meshgrid(*axes, indexing="ij")
    return np.stack([x.ravel(), y.ravel(), z.ravel()], axis=1)


def basis_values(basis, positions_bohr, points_bohr):
    """φ_μ aux points : (nao, m), en simple précision (assez pour l'image, deux fois plus rapide)."""
    points = points_bohr.astype(np.float32)
    values = np.empty((basis.nao, len(points)), np.float32)
    for shell, data in enumerate(basis.shells):
        start = basis.shell_offset[shell]
        center = positions_bohr[data["atom"]].astype(np.float32)
        values[start:start + 2 * data["l"] + 1] = integrals.gaussian_values(
            data["l"], points, center, data["alpha"].astype(np.float32), data["coeff"].astype(np.float32))
    return values


def orbital_values(result, index, origin, spacing, counts):
    """ψ_index sur la grille (Å^−3/2, float32, forme `counts`), sans passer par chaque φ_μ en chaque point : une
    gaussienne se sépare, x^a y^b z^c e^(−α r²) = (x^a e^(−α x²)) (y^b e^(−α y²)) (z^c e^(−α z²)), si bien que chaque
    primitive est le produit de trois vecteurs, posé sur la seule sous-boîte où elle dépasse 10⁻¹² — dix à vingt fois
    plus vite que φ point par point, et la même valeur (à 10⁻⁶ près)."""
    basis = result.basis
    c = result.coefficients[:, index]
    axes = [(origin[k] + spacing * np.arange(counts[k])) / BOHR_ANGSTROM for k in range(3)]
    psi = np.zeros(tuple(int(n) for n in counts), np.float64)
    for shell, data in enumerate(basis.shells):
        l, start = data["l"], basis.shell_offset[shell]
        weights = c[start:start + 2 * l + 1] @ integrals.SPHERICAL[l]        # sur les composantes cartésiennes
        if not np.any(np.abs(weights) > 1e-12):
            continue
        centre = result.positions[data["atom"]]
        for alpha, coef in zip(data["alpha"], data["coeff"]):
            amplitude = abs(coef) * float(np.abs(weights).max())
            if amplitude == 0:
                continue
            reach = np.sqrt(max(0.0, (np.log(amplitude) + 12 * np.log(10) + 8) / alpha))
            ranges, factors = [], []
            for k in range(3):
                lo = int(np.searchsorted(axes[k], centre[k] - reach))
                hi = int(np.searchsorted(axes[k], centre[k] + reach))
                ranges.append(slice(lo, hi))
                d = axes[k][lo:hi] - centre[k]
                g = np.exp(-alpha * d * d)
                factors.append([g * d ** power for power in range(l + 1)])
            if any(r.stop <= r.start for r in ranges):
                continue
            box = psi[ranges[0], ranges[1], ranges[2]]
            for w, (a, b, cc) in zip(weights, integrals.CARTESIAN[l]):
                if abs(w) < 1e-14:
                    continue
                box += (coef * w) * np.multiply.outer(np.multiply.outer(factors[0][a], factors[1][b]), factors[2][cc])
    return (psi * np.sqrt(BOHR3_TO_ANG3)).astype(np.float32)


def reference_occupations(basis):
    """n⁰_μ : l'occupation de l'atome libre, répartie sur les 2l+1 orbitales de chaque couche."""
    return np.repeat(basis.refocc / basis.shell_size, basis.shell_size)


def compute(result, kind=0, orbital=0, points=MAX_POINTS):
    """La grille demandée : 0 densité, 1 densité de déformation, 2 orbitale `orbital`. Valeurs en e/Å³ (ψ en Å^−3/2).

    `points` : points par côté, au plus (moins pendant que les noyaux bougent, pour suivre le mouvement). Une
    orbitale a une boîte assez grande pour que son nuage y tienne : agrandie tant que |ψ| dépasse sur ses faces le
    seuil où le client ne montre plus rien (ORBITAL_FLOOR de son maximum).
    """
    basis = result.basis
    positions_angstrom = result.positions * BOHR_ANGSTROM
    margin = ORBITAL_MARGIN if kind == 2 else MARGIN
    while True:
        origin, spacing, counts = grid_for(positions_angstrom, points, margin)
        if kind != 2:
            phi = basis_values(basis, result.positions, _points(origin, spacing, counts) / BOHR_ANGSTROM)
            values = np.einsum("mp,mp->p", phi, result.density.astype(np.float32) @ phi) * BOHR3_TO_ANG3
            if kind == 1:
                values = values - reference_occupations(basis).astype(np.float32) @ (phi * phi) * BOHR3_TO_ANG3
            break
        index = int(min(max(orbital, 0), result.coefficients.shape[1] - 1))
        values = orbital_values(result, index, origin, spacing, counts).ravel()
        cube = np.abs(values.reshape(tuple(counts)))
        faces = max(cube[0].max(), cube[-1].max(), cube[:, 0].max(), cube[:, -1].max(), cube[:, :, 0].max(),
                    cube[:, :, -1].max())
        # La surface du client enferme ORBITAL_ENCLOSED de |ψ|² : la boîte grandit tant que ce niveau touche ses faces
        # (une orbitale diffuse s'y couperait à plat), et tant que |ψ| y dépasse ORBITAL_FLOOR de son maximum.
        squares = np.sort((cube * cube).ravel())[::-1]
        level = float(np.sqrt(squares[np.searchsorted(np.cumsum(squares), ORBITAL_ENCLOSED * squares.sum())]))
        if (faces < ORBITAL_FLOOR * cube.max() and faces < .7 * level) or margin >= ORBITAL_MARGIN_MAX:
            break
        margin = min(ORBITAL_MARGIN_MAX, margin + 2.5)
    return origin, spacing, counts, values.reshape(tuple(counts))


def orbital_parts(result, index, floor=0.05):
    """Ce dont une orbitale est faite : sa population de Mulliken par couche d'atome, c_μ (S c)_μ sommé sur la couche
    (somme 1), les parts d'au moins `floor`, de la plus grande à la plus petite : [(atome, « 2p », part)]. La HOMO de
    l'eau : O 2p à 100 % (la paire libre perpendiculaire au plan)."""
    basis = result.basis
    c = result.coefficients[:, index]
    shells = np.bincount(basis.ao_shell, weights=c * (result.overlap @ c), minlength=len(basis.shells))
    parts = []
    for s in np.argsort(-shells):
        if shells[s] < floor:
            break
        shell = basis.shells[s]
        parts.append((int(shell["atom"]), f"{shell['n']}{'spdf'[shell['l']]}", float(shells[s])))
    return parts


def compute_split(result, points=MAX_POINTS):
    """La densité en deux parts : nuages des atomes ρ_at et recouvrement des liaisons ρ_ov (e/Å³)."""
    basis = result.basis
    positions_angstrom = result.positions * BOHR_ANGSTROM
    origin, spacing, counts = grid_for(positions_angstrom, points)
    phi = basis_values(basis, result.positions, _points(origin, spacing, counts) / BOHR_ANGSTROM)
    same = basis.ao_atom[:, None] == basis.ao_atom[None, :]
    on_site = np.where(same, result.density, 0.0).astype(np.float32)
    between = np.where(same, 0.0, result.density).astype(np.float32)
    atoms = np.einsum("mp,mp->p", phi, on_site @ phi) * BOHR3_TO_ANG3
    bonds = np.einsum("mp,mp->p", phi, between @ phi) * BOHR3_TO_ANG3
    shape = tuple(counts)
    return origin, spacing, counts, atoms.reshape(shape), bonds.reshape(shape)


@lru_cache(maxsize=None)
def projected_atom(z, step=PROJECTION_STEP, reach=PROJECTION_REACH):
    """Le nuage de valence de l'atome libre vu en transparence (transformée d'Abel de sa densité sphérique) :

        P⁰(d) = ∫ ρ⁰(√(d² + s²)) ds,   ρ⁰(r) = Σ_couches n⁰_l · (1 / (2l+1)) Σ_m φ_lm(r)²

    les électrons par Å² que croise une droite passant à d du noyau. Dans une molécule, l'atome A qui garde
    N_A = N⁰ − q_A électrons (charge de Mulliken) a le nuage (N_A / N⁰) · ρ⁰ : c'est ce que dessine le client, pour
    chaque atome, à chaque image. La somme sur les atomes reproduit la densité projetée calculée (le contour du halo
    coïncide à 95–99 % de sa surface, voir les tests). Renvoie (N⁰, pas en Å, P⁰(0), P⁰(pas)… en e/Å²).
    """
    radii = np.linspace(0.0, reach, int(round(reach / 0.002)) + 1)
    points = np.zeros((len(radii), 3))
    points[:, 2] = radii / BOHR_ANGSTROM
    rho = np.zeros(len(radii))
    electrons = 0.0
    for shell in xtb.element_shells(z):
        if not shell["refocc"]:
            continue
        values = integrals.gaussian_values(shell["l"], points, np.zeros(3), shell["alpha"], shell["coeff"])
        rho += shell["refocc"] * np.sum(values * values, axis=0) / (2 * shell["l"] + 1)
        electrons += shell["refocc"]
    rho *= BOHR3_TO_ANG3
    distances = np.arange(0.0, reach + 1e-9, step)
    s = radii
    projected = np.array([2 * np.trapezoid(np.interp(np.sqrt(d * d + s * s), radii, rho, right=0.0), s)
                          for d in distances])
    return electrons, step, projected.astype(np.float32)


@lru_cache(maxsize=None)
def atom_radii(z):
    """Deux tailles de l'atome libre, calculées dans la base GFN1 (Å) :

      surface   le rayon où sa densité de valence tombe à 0,002 e/bohr³ — la surface conventionnelle du nuage, celle
                où deux atomes commencent à se toucher (H 1,24, C 1,48, O 1,40, Cl 1,71 ; Bondi : 1,20, 1,70, 1,52, 1,75)
      p         la distance au noyau où une orbitale p de valence est la plus dense le long de son axe : la hauteur des
                lobes d'une liaison π (0 sans orbitale p de valence : H, He)
    """
    radii = np.linspace(0.0, 6.0, 6001)
    points = np.zeros((len(radii), 3))
    points[:, 2] = radii / BOHR_ANGSTROM
    rho = np.zeros(len(radii))
    p_radius = 0.0
    for shell in xtb.element_shells(z):
        values = integrals.gaussian_values(shell["l"], points, np.zeros(3), shell["alpha"], shell["coeff"])
        if shell["refocc"]:
            rho += shell["refocc"] * np.sum(values * values, axis=0) / (2 * shell["l"] + 1)
        if shell["l"] == 1 and not p_radius:
            p_radius = float(radii[np.argmax(values[1] ** 2)])        # p_z (rangées y, z, x) le long de z
    inside = np.where(rho >= SURFACE_AU)[0]
    return (float(radii[inside[-1]]) if len(inside) else 0.0), p_radius


def pair_profiles(result, orbitals, bonds, samples=PROFILE_SAMPLES):
    """Où sont, le long de chaque liaison, les électrons de ses paires localisées : leur densité Σ_k |φ_k|² intégrée
    dans le plan perpendiculaire à l'axe, en `samples` tranches égales du noyau a au noyau b (somme 1).

    `bonds` : {(a, b): indices des paires à deux centres de la liaison} ; `orbitals` : les orbitales localisées
    (nao × paires). C'est la loi d'où le client tire les grains de la liaison : serrée au milieu d'une liaison
    covalente (C–H), déportée vers l'atome qui garde la paire dans une liaison polaire (O–H : vers O).
    """
    if not bonds:
        return {}
    positions = result.positions * BOHR_ANGSTROM
    angles = 2 * np.pi * (np.arange(_RING_ANGLES) + 0.5) / _RING_ANGLES
    weights = np.repeat(np.pi * (_RING_EDGES[1:] ** 2 - _RING_EDGES[:-1] ** 2) / _RING_ANGLES, _RING_ANGLES)
    t = (np.arange(samples) + 0.5) / samples
    chunks = []
    for a, b in bonds:
        axis = positions[b] - positions[a]
        length = float(np.linalg.norm(axis))
        u = axis / length
        e1 = np.cross(u, [1.0, 0, 0] if abs(u[0]) < 0.8 else [0, 1.0, 0])
        e1 /= np.linalg.norm(e1)
        e2 = np.cross(u, e1)
        ring = (_RING_RADII[:, None, None] * (np.cos(angles)[None, :, None] * e1 + np.sin(angles)[None, :, None] * e2))
        slices = positions[a] + t[:, None] * length * u
        chunks.append((slices[:, None, :] + ring.reshape(1, -1, 3)).reshape(-1, 3))
    phi = basis_values(result.basis, result.positions, np.concatenate(chunks) / BOHR_ANGSTROM)
    out, start, size = {}, 0, samples * len(weights)
    for (a, b), members in bonds.items():
        values = orbitals[:, members].T.astype(np.float32) @ phi[:, start:start + size]
        profile = np.sum(values * values, axis=0).reshape(samples, -1) @ weights
        total = float(profile.sum())
        out[(a, b)] = (profile / total).astype(np.float32) if total > 0 else None
        start += size
    return out


def electrostatic_potential(values, origin, spacing, positions_angstrom, valence):
    """Le potentiel électrostatique φ (V) aux points de la grille.

    Les noyaux portent leur charge de valence Z_A (les électrons de cœur ne sont pas dans le modèle) : leur part est
    exacte, Σ Z_A / |r − R_A|. Celle du nuage est la convolution de ρ par 1/r, faite par transformée de Fourier sur une
    grille doublée (méthode de Hockney : la molécule n'interagit avec aucune image périodique) ; à r = 0, la moyenne
    exacte de 1/r sur la cellule. C'est la grandeur des cartes de potentiel : négative où les électrons dominent (δ−),
    positive où les noyaux sont moins écrantés (δ+).
    """
    counts = np.array(values.shape)
    size = 2 * counts
    axes = [np.minimum(np.arange(n), n - np.arange(n)) * spacing for n in size]
    x, y, z = np.meshgrid(*axes, indexing="ij", sparse=True)
    r = np.sqrt(x * x + y * y + z * z)
    with np.errstate(divide="ignore"):
        green = np.where(r > 0, 1.0 / r, CUBE_SELF / spacing)
    charge = np.zeros(size)
    charge[:counts[0], :counts[1], :counts[2]] = values * spacing ** 3
    electrons = fft.irfftn(fft.rfftn(charge, workers=-1) * fft.rfftn(green, workers=-1), s=size,
                           workers=-1)[:counts[0], :counts[1], :counts[2]]
    grid = [origin[k] + spacing * np.arange(counts[k]) for k in range(3)]
    gx, gy, gz = np.meshgrid(*grid, indexing="ij", sparse=True)
    nuclei = np.zeros(values.shape)
    for (px, py, pz), z_val in zip(positions_angstrom, valence):
        distance = np.sqrt((gx - px) ** 2 + (gy - py) ** 2 + (gz - pz) ** 2)
        nuclei += z_val / np.maximum(distance, 0.5 * spacing)
    return COULOMB_VOLT_ANGSTROM * (nuclei - electrons)


def encode(values, signed):
    """Les entiers 16 bits envoyés au client, et l'échelle (max) qui va avec."""
    peak = float(np.max(np.abs(values))) or 1.0
    if signed:
        q = np.sign(values) * np.sqrt(np.abs(values) / peak) * 32767
        return np.round(q).astype("<i2").tobytes(), peak
    q = (np.maximum(values, 0) / peak) ** (1 / DENSITY_POWER) * 65535
    return np.round(q).astype("<u2").tobytes(), peak


def decode(raw, peak, signed):
    """L'inverse de encode (pour les tests et les aperçus)."""
    if signed:
        q = np.frombuffer(raw, "<i2").astype(float) / 32767
        return np.sign(q) * q * q * peak
    return (np.frombuffer(raw, "<u2").astype(float) / 65535) ** DENSITY_POWER * peak
