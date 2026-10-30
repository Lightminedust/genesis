"""Ce qu'on lit dans un calcul GFN1-xTB : liaisons, fragments, dipôle, vibrations.

  Indice de liaison de Mayer   B_AB = 2 Σ_σ Σ_μ∈A Σ_ν∈B (P^σ S)_μν (P^σ S)_νμ    (σ = α, β)
      ≈ 1 pour une liaison simple, 2 double, 3 triple ; il varie continûment quand la liaison s'étire et casse.
  Fragments : les atomes reliés par B ≥ 0,5 (seuil de lecture, pour nommer les espèces ; le calcul n'en dépend pas).
  Dipôle      μ = Σ_A q_A R_A + Σ_A d_A (charges de Mulliken et dipôles des atomes ; 1 e·Å = 4,803 D).
  Paires      orbitales localisées de Pipek–Mezey : chaque paire d'électrons se range sur un atome (paire libre) ou
              deux (liaison), et ses populations de Mulliken disent comment la liaison partage sa paire.
  Vibrations  la matrice hessienne par différences centrées des forces analytiques, pondérée par les masses,
              débarrassée des translations et rotations : ω² sont ses valeurs propres, ν̃ = ω / 2πc.
"""

import math
from functools import lru_cache

import numpy as np

from common.elements_db import DB

from . import density, integrals, structures, xtb

FRAGMENT_BOND = 0.5
# Sous ce partage (voir shared_density), deux atomes ne sont plus liés, quel que soit leur indice de Mayer : au milieu
# de l'extinction du trait chez le client (0,03 → 0,15). H₂ s'y sépare vers 2 Å, O₂ vers 2,1 Å.
SHARED_BOND = 0.09
DEBYE_PER_E_ANGSTROM = 4.80320471
HARTREE_J = 4.3597447222071e-18
AMU_KG = 1.66053906660e-27
LIGHT_CM_S = 2.99792458e10
HESSIAN_STEP = 0.005             # Å
# a [Å/fs²] = F [Eh/Å] / m [u] · ACCELERATION
ACCELERATION = HARTREE_J / 1e-10 / AMU_KG / 1e20
BOLTZMANN_EH = xtb.KB_HARTREE
HARTREE_KJ_MOL = 2625.4996394799


@lru_cache(maxsize=None)
def mass(z):
    row = DB.execute("select atomic_weight from elements where atomic_number=?", (int(z),)).fetchone()
    return float(row[0])


_YEARS = {"year": 1.0, "kyear": 1e3, "Myear": 1e6, "Gyear": 1e9, "Tyear": 1e12, "Pyear": 1e15, "Eyear": 1e18,
          "Zyear": 1e21, "Yyear": 1e24, "day": 1 / 365.25, "hour": 1 / 8766.0, "minute": 1 / 525960.0,
          "sec": 1 / 31557600.0, "msec": 1e-3 / 31557600, "usec": 1e-6 / 31557600, "nsec": 1e-9 / 31557600,
          "psec": 1e-12 / 31557600, "asec": 1e-18 / 31557600, "zsec": 1e-21 / 31557600, "ysec": 1e-24 / 31557600}


@lru_cache(maxsize=None)
def isotopes(z):
    """Les isotopes qu'on peut donner à un atome de l'élément z : (A, masse en u, abondance naturelle en %, demi-vie en
    années, 0 s'il est stable). Les stables, et les radioactifs qui vivent au moins un an (³H, ¹⁴C, ⁴⁰K) ; pour un
    élément sans isotope stable ni durable (Rn), ses trois plus durables. Masses et abondances : AME et IUPAC (base
    mendeleev)."""
    rows = DB.execute("select mass_number, mass, abundance, is_radioactive, half_life, half_life_unit from isotopes "
                      "where atomic_number=? order by mass_number", (int(z),)).fetchall()
    stable, lived = [], []
    for a, value, abundance, radioactive, half_life, unit in rows:
        years = float(half_life) * _YEARS.get(unit, 0.0) if half_life else 0.0
        if abundance and abundance > 0 and not radioactive:
            stable.append((int(a), float(value), float(abundance), 0.0))
        elif radioactive and years > 0:
            lived.append((int(a), float(value), float(abundance or 0.0), years))
    durable = [row for row in lived if row[3] >= 1.0]
    if not stable and not durable:
        durable = sorted(lived, key=lambda row: -row[3])[:3]
    return tuple(sorted(stable + durable))


def isotope_mass(z, a):
    """La masse (u) de l'isotope A de l'élément z ; sans A (0), la masse atomique standard, celle du mélange naturel."""
    if not a:
        return mass(z)
    row = DB.execute("select mass from isotopes where atomic_number=? and mass_number=?", (int(z), int(a))).fetchone()
    return float(row[0]) if row else mass(z)


@lru_cache(maxsize=None)
def covalent_radius(z):
    """Rayon covalent de liaison simple, Å (Pyykkö & Atsumi, Chem. Eur. J. 15, 186, 2009 ; base mendeleev)."""
    row = DB.execute("select covalent_radius_pyykko from elements where atomic_number=?", (int(z),)).fetchone()
    return float(row[0]) / 100 if row and row[0] else 0.75


def spin_densities_times_overlap(result):
    """P^σ S pour chaque spin (σ = α, β), calculés une fois par résultat : les indices de Mayer et leur séparation σ / π
    en lisent les blocs."""
    cached = getattr(result, "_ps", None)
    if cached is None:
        C, S = result.coefficients, result.overlap
        cached = [((C * occupation) @ C.T) @ S for occupation in (result.occupations_alpha, result.occupations_beta)]
        result._ps = cached
    return cached


def bond_symmetry(result, a, b, directions=False):
    """L'indice de Mayer de la paire AB séparé en part σ et part π.

    Les orbitales p des deux atomes sont tournées dans le repère de la liaison (axe A → B, deux perpendiculaires) :
    p∥ est σ, les deux p⊥ sont π. B_π = 2 Σ_σspin Σ (P'S')_μν (P'S')_νμ sur les paires (μ, ν) toutes deux π ; le reste
    (s, p∥, d, et les termes croisés, nuls par symétrie autour d'un axe) va dans B_σ. B_σ + B_π = B_AB.

    Avec `directions` : où est la part π autour de l'axe. B_π est la trace de la matrice 2 × 2
    M = 2 Σ_σspin (P'S')_{A⊥,B⊥} (P'S')_{B⊥,A⊥} ; ses axes propres sont les directions des lobes π, ses valeurs propres
    la part π de chacune. Éthylène : 1,00 le long de la normale au plan, 0,03 dans le plan ; N₂ : 1 et 1 (deux π
    perpendiculaires) ; benzène : 0,44 le long de la normale. Rend alors aussi (direction principale unitaire,
    [part le long d'elle, part le long de axe × elle]).
    """
    basis = result.basis
    positions = result.positions
    axis = positions[b] - positions[a]
    axis /= np.linalg.norm(axis)
    helper = np.array([1.0, 0, 0]) if abs(axis[0]) < 0.8 else np.array([0, 1.0, 0])
    e1 = np.cross(axis, helper)
    e1 /= np.linalg.norm(e1)
    e2 = np.cross(axis, e1)
    # Les p sphériques sont rangées (y, z, x) : la rotation vers (∥, ⊥1, ⊥2). Elle ne touche que les orbitales p des
    # deux atomes : seuls les blocs A–B et B–A de P S tournés sont lus, R_Aᵀ (PS)_AB R_B et R_Bᵀ (PS)_BA R_A.
    frame = np.array([[v[1], v[2], v[0]] for v in (axis, e1, e2)])
    orbitals = {atom: np.where(basis.ao_atom == atom)[0] for atom in (a, b)}
    rotations = {atom: np.eye(len(orbitals[atom])) for atom in (a, b)}
    is_pi = {atom: np.zeros(len(orbitals[atom]), bool) for atom in (a, b)}
    perpendicular = {a: [], b: []}
    for shell, l in enumerate(basis.shell_l):
        atom = basis.shell_atom[shell]
        if l != 1 or atom not in (a, b):
            continue
        o = basis.shell_offset[shell] - orbitals[atom][0]
        rotations[atom][o:o + 3, o:o + 3] = frame.T
        is_pi[atom][o + 1:o + 3] = True
        perpendicular[atom].append([o + 1, o + 2])
    both_pi = np.outer(is_pi[a], is_pi[b])
    sigma = pi = 0.0
    M = np.zeros((2, 2))
    for ps in spin_densities_times_overlap(result):
        ab = rotations[a].T @ ps[np.ix_(orbitals[a], orbitals[b])] @ rotations[b]
        ba = rotations[b].T @ ps[np.ix_(orbitals[b], orbitals[a])] @ rotations[a]
        block = ab * ba.T
        pi += 2 * float(block[both_pi].sum())
        sigma += 2 * float(block[~both_pi].sum())
        for pa in perpendicular[a]:
            for pb in perpendicular[b]:
                M += 2 * ab[np.ix_(pa, pb)] @ ba[np.ix_(pb, pa)]
    if not directions:
        return sigma, pi
    if not perpendicular[a] or not perpendicular[b]:
        return sigma, pi, None, [0.0, 0.0]
    values, vectors = np.linalg.eigh(0.5 * (M + M.T))
    main = int(np.argmax(values))
    direction = vectors[0, main] * e1 + vectors[1, main] * e2
    if values[1 - main] > 0.9 * values[main]:
        # Deux π égales (N₂, P₂, C₂H₂, CO₂) : toute paire de directions perpendiculaires convient, et les vecteurs
        # propres tourneraient au gré du bruit numérique d'un pas à l'autre ; on prend celle du repère de la liaison.
        direction = e1
    return sigma, pi, direction, [float(values[main]), float(values[1 - main])]


def mayer_bond_orders(result):
    """La matrice B_AB (nat, nat), diagonale nulle."""
    basis = result.basis
    nat = len(basis.numbers)
    total = np.zeros((nat, nat))
    for ps in spin_densities_times_overlap(result):
        product = ps * ps.T
        block = np.zeros((nat, basis.nao))
        np.add.at(block, basis.ao_atom, product)
        atoms = np.zeros((nat, nat))
        np.add.at(atoms.T, basis.ao_atom, block.T)
        total += 2 * atoms
    np.fill_diagonal(total, 0.0)
    return total


def overlap_populations(result):
    """Les populations de recouvrement de Mulliken n_AB = 2 Σ_{μ∈A, ν∈B} P_μν S_μν (nat, nat ; e), diagonale nulle :
    > 0, les électrons partagés lient A et B ; < 0, leur recouvrement est antiliant — deux couches pleines qui se
    touchent se repoussent (la gêne stérique, de Pauli)."""
    basis = result.basis
    nat = len(basis.numbers)
    block = np.zeros((nat, basis.nao))
    np.add.at(block, basis.ao_atom, result.density * result.overlap)
    atoms = np.zeros((nat, nat))
    np.add.at(atoms.T, basis.ao_atom, block.T)
    atoms *= 2
    np.fill_diagonal(atoms, 0.0)
    return atoms


@lru_cache(maxsize=None)
def bond_reference(z):
    """La densité de valence de l'atome libre à son rayon covalent (e/Å³) : ce qu'il apporte au milieu d'une liaison
    simple ordinaire (O 1,66 ; C 0,65 ; Na 0,014 ; Cs 0,003)."""
    point = np.array([[0.0, 0.0, covalent_radius(z) / xtb.BOHR_ANGSTROM]])
    rho = 0.0
    for shell in xtb.element_shells(z):
        if shell["refocc"]:
            values = integrals.gaussian_values(shell["l"], point, np.zeros(3), shell["alpha"], shell["coeff"])
            rho += shell["refocc"] * float(np.sum(values * values)) / (2 * shell["l"] + 1)
    return rho / xtb.BOHR_ANGSTROM ** 3


def shared_density(result, pairs):
    """Ce que deux atomes partagent entre eux, au milieu de leur axe : la densité d'interférence
    2 Σ_{μ∈A, ν∈B} P_μν φ_μ φ_ν (ni les nuages propres des atomes, ni celui d'un troisième atome au même endroit),
    rapportée à √(ρ⁰_A ρ⁰_B), la densité de chaque atome libre à son rayon covalent. ≈ 1 pour une liaison ordinaire
    (HCl 0,8 … N₂ 2,1 ; Na₂ 1,9, Cs₂ 2,4 : leurs nuages sont diffus, la référence aussi), Au₂ 0,34, I₂ 0,62. Elle
    tombe sous 0,03 quand la liaison est rompue, même quand l'indice de Mayer d'un calcul à couches fermées reste
    à 1 : les deux électrons y restent appariés d'un fragment à l'autre, sans rien entre les noyaux (O–O à 3 Å :
    indice 1,06, partage 0,005 ; H₂ à 2,5 Å : 1,00 et 0,03). Rend {(a, b): partage}."""
    if not pairs:
        return {}
    basis, p = result.basis, result.positions
    first = np.array([a for a, _ in pairs])
    second = np.array([b for _, b in pairs])
    phi = density.basis_values(basis, p, 0.5 * (p[first] + p[second])).astype(float)
    shared = {}
    for k, (a, b) in enumerate(pairs):
        on_a, on_b = basis.ao_atom == a, basis.ao_atom == b
        cross = 2 * float(phi[on_a, k] @ result.density[np.ix_(on_a, on_b)] @ phi[on_b, k]) / xtb.BOHR_ANGSTROM ** 3
        shared[(a, b)] = cross / math.sqrt(bond_reference(int(basis.numbers[a])) * bond_reference(int(basis.numbers[b])))
    return shared


def fragments(numbers, orders, result=None):
    """Les groupes d'atomes liés (B ≥ 0,5, et avec `result` un partage d'au moins SHARED_BOND), chacun avec sa
    formule brute."""
    nat = len(numbers)
    if result is not None:
        candidates = [(a, b) for a in range(nat) for b in range(a + 1, nat) if orders[a, b] >= FRAGMENT_BOND]
        shared = shared_density(result, candidates)
        orders = orders.copy()
        for (a, b), value in shared.items():
            if value < SHARED_BOND:
                orders[a, b] = orders[b, a] = 0.0
    seen = [False] * nat
    groups = []
    for start in range(nat):
        if seen[start]:
            continue
        stack, members = [start], []
        seen[start] = True
        while stack:
            a = stack.pop()
            members.append(a)
            for b in range(nat):
                if not seen[b] and orders[a, b] >= FRAGMENT_BOND:
                    seen[b] = True
                    stack.append(b)
        members.sort()
        groups.append((members, structures.hill_formula([numbers[k] for k in members])))
    return groups


def census(groups):
    """« 2 H₂O + O₂ » : les espèces présentes, comptées."""
    counts = {}
    for _, formula in groups:
        counts[formula] = counts.get(formula, 0) + 1
    parts = []
    for formula in sorted(counts, key=lambda f: (-len(f), f)):
        n = counts[formula]
        parts.append(("" if n == 1 else f"{n} ") + structures.pretty(formula))
    return " + ".join(parts)


def atomic_dipoles(result):
    """Le dipôle de chaque atome (e·Å) : le premier moment de sa part de Mulliken du nuage, autour de son noyau,

        d_A = − Σ_μ∈A Σ_ν P_μν ⟨μ|(r − R_A)|ν⟩.

    Il dit de combien et vers où le nuage de l'atome est déformé : les électrons vont dans la direction −d_A.
    """
    basis = result.basis
    M = result.moments if getattr(result, "moments", None) is not None else basis.moments(result.positions)
    nat = len(basis.numbers)
    weighted = -np.einsum("cmn,mn->cm", M, result.density)              # (3, nao)
    out = np.zeros((nat, 3))
    for c in range(3):
        out[:, c] = np.bincount(basis.ao_atom, weights=weighted[c], minlength=nat)
    return out * xtb.BOHR_ANGSTROM


def dipole(result, atomic=None):
    """Le moment dipolaire (Debye, vecteur) : charges de Mulliken et dipôles atomiques, Σ q_A R_A + Σ d_A — le
    dipôle exact du nuage de valence et des cœurs dans cette base."""
    positions = result.positions * xtb.BOHR_ANGSTROM
    center = positions.mean(axis=0)
    if atomic is None:
        atomic = atomic_dipoles(result)
    return DEBYE_PER_E_ANGSTROM * (result.atom_charges @ (positions - center) + atomic.sum(axis=0))


LONE_PAIR = 0.8         # population sur un seul atome au-delà de laquelle une paire est libre (seuil de lecture)
BOND_PAIR = 0.75        # population cumulée des deux atomes au-delà de laquelle une paire est une liaison
CENTRE = 0.1            # population à partir de laquelle un atome compte parmi les centres d'une paire
THIRD_CENTRE = 0.15     # population d'un troisième atome au-delà de laquelle la paire a plusieurs centres


def localize(result, sweeps=300, tolerance=1e-6, seed=7, orbitals=False):
    """Les paires d'électrons localisées de Pipek–Mezey (J. Chem. Phys. 90, 4916, 1989).

    Les orbitales doublement occupées sont tournées entre elles (une rotation unitaire : la densité, l'énergie et le
    dipôle ne changent pas) de façon à maximiser Σ_i Σ_A (Q^A_ii)², où Q^A_ii est la population de Mulliken de
    l'orbitale i sur l'atome A. Chaque orbitale se concentre alors sur un atome (une paire libre), deux (une
    liaison) ou plus (une liaison à plusieurs centres), les parts σ et π restant séparées. Rotations de Jacobi : pour
    chaque couple (i, j),
        A = Σ_A [(Q^A_ij)² − ¼ (Q^A_ii − Q^A_jj)²],  B = Σ_A Q^A_ij (Q^A_ii − Q^A_jj),  4γ = atan2(B, −A),
    jusqu'à ce que plus aucun angle ne dépasse `tolerance` (10⁻⁶ rad : les populations sont alors exactes à 10⁻⁴).

    Rend les populations (paires, atomes) et les barycentres des paires (paires, 3), en Å ; avec `orbitals`, aussi
    les orbitales localisées (coefficients, nao × paires).
    """
    basis, S, C, occupations = result.basis, result.overlap, result.coefficients, result.occupations
    nat = len(basis.numbers)
    pairs = np.where(occupations > 1.5)[0]
    if len(pairs) == 0:
        empty = (np.zeros((0, nat)), np.zeros((0, 3)))
        return empty + (np.zeros((basis.nao, 0)),) if orbitals else empty
    L = C[:, pairs].copy()
    n = L.shape[1]
    if n > 1:
        # Un léger mélange initial (graine fixe) : les orbitales canoniques d'une molécule symétrique sont un col
        # du critère, d'où les rotations ne sauraient pas de quel côté partir.
        generator = np.random.default_rng(seed).normal(scale=0.02, size=(n, n))
        rotation, _ = np.linalg.qr(np.eye(n) + generator - generator.T)
        L = L @ rotation
    SL = S @ L
    Q = np.empty((nat, n, n))
    for atom in range(nat):
        mask = basis.ao_atom == atom
        block = L[mask].T @ SL[mask]
        Q[atom] = 0.5 * (block + block.T)
    # Jacobi parallèle : chaque tour fait tourner n/2 couples disjoints à la fois (ordre du tournoi à la ronde) ; des
    # rotations sur des couples disjoints commutent, donc un tour vaut ces rotations faites l'une après l'autre.
    rounds = _round_robin(n)
    for _ in range(sweeps):
        largest = 0.0
        for i, j in rounds:
            qii, qjj, qij = Q[:, i, i], Q[:, j, j], Q[:, i, j]
            a = np.sum(qij * qij - 0.25 * (qii - qjj) ** 2, axis=0)
            b = np.sum(qij * (qii - qjj), axis=0)
            gamma = np.where(a * a + b * b < 1e-24, 0.0, 0.25 * np.arctan2(b, -a))
            gamma[np.abs(gamma) < 1e-13] = 0.0
            if not gamma.any():
                continue
            largest = max(largest, float(np.max(np.abs(gamma))))
            c, s = np.cos(gamma), np.sin(gamma)
            R = np.eye(n)
            R[i, i], R[j, j], R[j, i], R[i, j] = c, c, s, -s        # L[:, i] ← c lᵢ + s lⱼ, L[:, j] ← −s lᵢ + c lⱼ
            L = L @ R
            Q = R.T @ Q @ R
        if largest < tolerance:
            break
    populations = np.stack([np.diagonal(Q[atom]) for atom in range(nat)], axis=1)
    # Les barycentres : ⟨i|r|i⟩ = Σ_μν L_μi L_νi ⟨μ|r|ν⟩, ⟨μ|r|ν⟩ = ⟨μ|(r − R_μ)|ν⟩ + R_μ ⟨μ|ν⟩.
    M = result.moments if getattr(result, "moments", None) is not None else basis.moments(result.positions)
    centres = result.positions[basis.ao_atom]
    centroids = np.empty((n, 3))
    for axis in range(3):
        operator = M[axis] + centres[:, axis][:, None] * S
        centroids[:, axis] = np.einsum("mi,mn,ni->i", L, operator, L)
    if orbitals:
        return populations, centroids * xtb.BOHR_ANGSTROM, L
    return populations, centroids * xtb.BOHR_ANGSTROM


def _round_robin(n):
    """Les tours d'un tournoi à la ronde entre n orbitales : chaque couple (i < j) une fois, n/2 couples disjoints par
    tour (méthode du cercle ; une orbitale fictive complète un nombre impair)."""
    players = list(range(n)) + ([-1] if n % 2 else [])
    m = len(players)
    rounds = []
    for _ in range(m - 1):
        couples = [(players[k], players[m - 1 - k]) for k in range(m // 2)]
        couples = [(min(p, q), max(p, q)) for p, q in couples if p >= 0 and q >= 0]
        if couples:
            rounds.append((np.array([p for p, _ in couples]), np.array([q for _, q in couples])))
        players = [players[0], players[-1]] + players[1:-1]
    return rounds


def electron_pairs(result, bonds):
    """Ce que les paires localisées disent de chaque liaison et de chaque atome.

    Une paire est libre si plus de 80 % de sa population est sur un atome ; à deux centres si deux atomes en portent
    plus de 75 % et qu'aucun troisième n'en porte 15 % ; sinon elle est partagée par plusieurs centres, les atomes qui
    en portent au moins 10 % (seuils de lecture : le calcul n'en dépend pas). Ces paires à plusieurs centres sont la
    liaison à trois centres du pont B–H–B du diborane, ou celles des agrégats métalliques : les électrons s de Au₆
    forment trois paires chacune étalée sur un triangle de trois atomes, aucune sur un seul couple.

    Rend, pour chaque liaison (a, b) de `bonds` qui a des paires à deux centres, la part de ces paires du côté de b,
    Σ p_b / Σ (p_a + p_b) (0,5 : partage égal ; 0,7 : 70 % de la paire chez b) ; pour chaque atome, son nombre de paires
    libres ; et les paires à plusieurs centres, (atomes, populations, barycentre en Å), atomes par population
    décroissante.
    """
    return classify_pairs(*localize(result), bonds)[:3]


def classify_pairs(populations, centroids, bonds):
    """Le classement d'`electron_pairs`, sur des paires déjà localisées (populations, barycentres en Å) ; rend en plus,
    pour chaque couple (a < b) tenu par des paires à deux centres, les indices de ces paires."""
    nat = populations.shape[1]
    lone = np.zeros(nat, int)
    sums = {}
    members = {}
    shared = []
    for index, (pop, centroid) in enumerate(zip(populations, centroids)):
        order = np.argsort(-pop)
        first = order[0]
        second = order[1] if nat > 1 else first
        third = pop[order[2]] if nat > 2 else 0.0
        centres = [int(k) for k in order if pop[k] >= CENTRE]
        if pop[first] >= LONE_PAIR and (nat == 1 or pop[second] < 0.15):
            lone[first] += 1
        elif len(centres) >= 3 and (pop[first] + pop[second] < BOND_PAIR or third >= THIRD_CENTRE):
            shared.append((centres, [float(pop[k]) for k in centres], centroid.copy()))
        elif nat > 1 and (pop[first] + pop[second] >= BOND_PAIR or len(centres) == 2) and pop[second] >= CENTRE:
            a, b = sorted((int(first), int(second)))
            on_a, on_b = sums.get((a, b), (0.0, 0.0))
            sums[(a, b)] = (on_a + pop[a], on_b + pop[b])
            members.setdefault((a, b), []).append(index)
    shares = {}
    for a, b in bonds:
        key = (min(a, b), max(a, b))
        if key in sums:
            on_a, on_b = sums[key]
            share = on_b / (on_a + on_b) if on_a + on_b > 0 else 0.5
            shares[(a, b)] = share if (a, b) == key else 1 - share
    return shares, lone, shared, members


def kinetic_energy(masses, velocities):
    """½ Σ m v², en Eh (v en Å/fs)."""
    return 0.5 * float(np.sum(masses[:, None] * velocities ** 2)) / ACCELERATION


def _rigid_motions(positions, masses):
    """Les translations et rotations, en coordonnées pondérées par les masses, orthonormées."""
    n = len(positions)
    root = np.sqrt(masses)
    center = (masses @ positions) / masses.sum()
    rel = positions - center
    vectors = []
    for axis in range(3):
        v = np.zeros((n, 3))
        v[:, axis] = root
        vectors.append(v.ravel())
    for axis in range(3):
        unit = np.zeros(3)
        unit[axis] = 1.0
        v = np.cross(unit, rel) * root[:, None]
        vectors.append(v.ravel())
    basis = []
    for v in vectors:
        for b in basis:
            v = v - (v @ b) * b
        norm = np.linalg.norm(v)
        if norm > 1e-6:
            basis.append(v / norm)
    return np.array(basis)


def normal_modes(calculator, positions, masses):
    """Fréquences (cm⁻¹, négatives si imaginaires) et déplacements cartésiens normés (Å) des modes de vibration."""
    n = len(positions)
    hessian = np.zeros((3 * n, 3 * n))
    for k in range(3 * n):
        plus, minus = positions.copy(), positions.copy()
        plus.flat[k] += HESSIAN_STEP
        minus.flat[k] -= HESSIAN_STEP
        g_plus = calculator.compute(plus).gradient.ravel()
        g_minus = calculator.compute(minus).gradient.ravel()
        hessian[k] = (g_plus - g_minus) / (2 * HESSIAN_STEP)
    calculator.compute(positions)
    hessian = 0.5 * (hessian + hessian.T)
    weights = np.repeat(1 / np.sqrt(masses), 3)
    weighted = hessian * weights[:, None] * weights[None, :]
    rigid = _rigid_motions(positions, masses)
    projector = np.eye(3 * n) - rigid.T @ rigid
    weighted = projector @ weighted @ projector
    values, vectors = np.linalg.eigh(weighted)
    # Les 3N − 6 (ou 3N − 5) vecteurs propres orthogonaux aux mouvements rigides.
    overlap = np.sum((rigid @ vectors) ** 2, axis=0)
    keep = np.argsort(overlap)[: 3 * n - len(rigid)]
    keep = keep[np.argsort(values[keep])]
    to_s2 = HARTREE_J / (1e-20 * AMU_KG)
    frequencies = []
    displacements = []
    for k in keep:
        omega2 = values[k] * to_s2
        frequencies.append(math.copysign(math.sqrt(abs(omega2)) / (2 * math.pi * LIGHT_CM_S), omega2))
        cart = (vectors[:, k] * weights).reshape(n, 3)
        displacements.append(cart / np.linalg.norm(cart))
    return np.array(frequencies), displacements, hessian


def mode_velocities(displacement, masses, frequency_cm, quanta):
    """Les vitesses (Å/fs) qui donnent au mode `quanta` quanta hν d'énergie, au passage par l'équilibre."""
    energy = quanta * 6.62607015e-34 * LIGHT_CM_S * abs(frequency_cm) / HARTREE_J      # Eh
    shape = displacement / np.sqrt(np.sum(masses[:, None] * displacement ** 2))           # Σ m d² = 1
    return shape * math.sqrt(2 * energy * ACCELERATION)
