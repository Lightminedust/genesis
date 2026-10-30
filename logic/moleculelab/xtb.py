"""GFN1-xTB : la méthode de liaisons fortes quantique de Grimme, Bannwarth et Shushkov (JCTC 13, 1989, 2017), en numpy.

Pour une position des noyaux, on résout les électrons de valence dans une base minimale d'orbitales de Slater
(développées en gaussiennes, STO-NG) :

  E = Σ P_μν H⁰_μν                       énergie de bande (orbitales qui se recouvrent)
    + ½ Σ q_l γ_ll' q_l'                 électrostatique entre charges de couche (Klopman–Ohno)
    + ⅓ Σ Γ_A q_A³                       troisième ordre (dureté qui change avec la charge)
    + E_rép                              répulsion des cœurs à courte distance (Pauli, noyaux)
    + E_disp                             dispersion de London (DFT-D3, amortissement rationnel)
    + E_XB                               liaison halogène (Br, I)

H⁰_μν = ½ (h_μ + h_ν) · K · π(R) · S_μν : deux orbitales n'interagissent que si elles se recouvrent (S_μν), d'autant
plus que leurs niveaux h (corrigés par le nombre de coordination) sont profonds. Les charges q viennent de la
population de Mulliken et le calcul est répété jusqu'à l'auto-cohérence. Les forces sont les dérivées analytiques
F = −∇E, terme par terme : on peut ainsi montrer séparément ce qui attire et ce qui repousse.

Paramètres : data/gfn1-xtb.toml (xtb / tblite, LGPL-3.0) ; tables : data/xtb-data.npz (tools/build_xtb_data.py).
Vérifié contre l'implémentation de référence dxtb (tests/test_moleculelab.py).
"""

import math
import tomllib
from functools import lru_cache
from pathlib import Path

import numpy as np
from scipy.linalg import eigh

from . import integrals

DATA = Path(__file__).resolve().parent / "data"
EV_TO_HARTREE = 1 / 27.211386245988
HARTREE_EV = 27.211386245988
BOHR_ANGSTROM = 0.529177210903
KB_HARTREE = 3.166811563e-6                  # constante de Boltzmann, Eh/K
ELECTRONIC_TEMPERATURE = 300.0               # lissage de Fermi des occupations (comme xtb)
KCN = 16.0                                   # raideur du comptage des voisins (D3)
CN_CUTOFF = 25.0                             # bohr
REPULSION_KEXP = 1.5
HALOGENS, HALOGEN_BASES = (17, 35, 53, 85), (7, 8, 15, 16)
HALOGEN_CUTOFF = 20.0
# Le confinement des anions. Le terme du troisième ordre, Γ_A q_A³ / 3 (Γ > 0 pour presque tous les éléments), n'a
# pas de borne quand un atome prend des électrons : son potentiel Γ_A q_A² creuse les niveaux de l'atome qui se
# charge, qui en attire d'autant plus. Pm₄ dissocié converge ainsi, chez dxtb aussi, vers un atome à −9 e ; Ba₂Si
# vers Si −4,8 e et Ba +2,4 e (plus d'électrons perdus qu'il n'en a). Aucune molécule réelle n'en approche (la plus
# négative de 21 composés ioniques : O −0,98 e dans Na₂O) : une contrainte stricte tient chaque charge à −2,5 e au
# plus bas. C'est un multiplicateur de Lagrange λ_A ≥ 0 qui relève les niveaux de l'atome juste assez (DFT
# contrainte) ; l'énergie est celle de GFN1 pour cette densité, et les forces suivent la contrainte. Les cations
# ne sont pas bornés : W porte +3,11 e dans WF₆, Os +2,81 e dans OsO₄ (dxtb aussi).
ANION_LIMIT = 2.5                            # e
RUNAWAY_CHARGE = 3.0                         # e : plus négatif malgré la contrainte, le résultat est marqué instable
# Le cycle auto-cohérent par paliers. D'abord le mélange de Broyden (0,4) : 5 tours d'ordinaire, 40 au plus. Quand
# une liaison casse, le point fixe devient très raide (deux niveaux presque dégénérés autour du niveau de Fermi
# échangent leurs électrons d'un tour à l'autre) : O₄ à 4 000 K, 265 pas sur 3 000 ; les amortir davantage n'y fait
# presque rien (Broyden 0,1 : 0 sur 265, puis 0,03 : 48). On passe alors à Newton, amorti : la réponse des charges
# est calculée, et le pas divisé par deux tant que le résidu ne baisse pas — 265 sur 265, en 4 pas (médiane). En
# dernier recours (fragments séparés à couches ouvertes, Pm₄ dissocié), un recuit électronique : occupations lissées
# à 30, 10, 3 fois 300 K, puis 300 K, chaque palier partant du précédent ; si 300 K échoue encore, c'est le palier le
# plus froid qui a convergé, et le résultat le dit (electronic_temperature).
SCC_BROYDEN = (0.4, 40)
NEWTON_STEPS = 12
ANNEALING = (30.0, 10.0, 3.0, 1.0)
ANNEALING_ITERATIONS = 60
SYMBOLS = ["X", "H", "He", "Li", "Be", "B", "C", "N", "O", "F", "Ne", "Na", "Mg", "Al", "Si", "P", "S", "Cl", "Ar",
           "K", "Ca", "Sc", "Ti", "V", "Cr", "Mn", "Fe", "Co", "Ni", "Cu", "Zn", "Ga", "Ge", "As", "Se", "Br", "Kr",
           "Rb", "Sr", "Y", "Zr", "Nb", "Mo", "Tc", "Ru", "Rh", "Pd", "Ag", "Cd", "In", "Sn", "Sb", "Te", "I", "Xe",
           "Cs", "Ba", "La", "Ce", "Pr", "Nd", "Pm", "Sm", "Eu", "Gd", "Tb", "Dy", "Ho", "Er", "Tm", "Yb", "Lu",
           "Hf", "Ta", "W", "Re", "Os", "Ir", "Pt", "Au", "Hg", "Tl", "Pb", "Bi", "Po", "At", "Rn"]
ANGULAR = {"s": 0, "p": 1, "d": 2, "f": 3}


@lru_cache(maxsize=1)
def parameters():
    with open(DATA / "gfn1-xtb.toml", "rb") as handle:
        return tomllib.load(handle)


@lru_cache(maxsize=1)
def tables():
    with np.load(DATA / "xtb-data.npz") as raw:
        return {key: raw[key] for key in raw.files}


def element(z):
    return parameters()["element"][SYMBOLS[z]]


def supported(z):
    return 1 <= z < len(SYMBOLS) and SYMBOLS[z] in parameters()["element"]


@lru_cache(maxsize=None)
def pair_parameter(za, zb):
    pairs = parameters()["hamiltonian"]["xtb"]["kpair"]
    a, b = SYMBOLS[za], SYMBOLS[zb]
    return pairs.get(f"{a}-{b}", pairs.get(f"{b}-{a}", 1.0))


# ====================================================================== la base
@lru_cache(maxsize=None)
def slater_to_gauss(ng, n, l, zeta):
    """Une orbitale de Slater (n, l, ζ) développée en ng gaussiennes normalisées : (exposants, coefficients)."""
    table = tables()[f"sto{ng}g"]
    itype = n + [0, 4, 7, 9, 10][l] - 1
    if n == 6 and ng == 6:
        itype = 15 + l
    coeff, alpha = table[0, itype], table[1, itype]
    alpha = alpha * zeta ** 2
    double_factorial = [1.0, 1.0, 3.0, 15.0, 105.0][l]
    coeff = coeff * (2.0 / math.pi * alpha) ** 0.75 * np.sqrt(4 * alpha) ** l / math.sqrt(double_factorial)
    return alpha, coeff


def _self_overlap(ai, ci, aj, cj):
    return float(np.sum((math.pi / (ai[:, None] + aj[None, :])) ** 1.5 * ci[:, None] * cj[None, :]))


@lru_cache(maxsize=None)
def element_shells(z):
    """Les couches de l'élément : (l, n, primitives, coefficients, valence, paramètres de la couche)."""
    p = element(z)
    shells = []
    seen = {}
    for index, label in enumerate(p["shells"]):
        n, l = int(label[0]), ANGULAR[label[1]]
        alpha, coeff = slater_to_gauss(p["ngauss"][index], n, l, p["slater"][index])
        valence = l not in seen
        if not valence:
            # Deuxième couche s de l'hydrogène : orthogonalisée à la première (Gram–Schmidt).
            first = shells[seen[l]]
            overlap = _self_overlap(first["alpha"], first["coeff"], alpha, coeff)
            alpha = np.concatenate([alpha, first["alpha"]])
            coeff = np.concatenate([coeff, -overlap * first["coeff"]])
            coeff = coeff / math.sqrt(_self_overlap(alpha, coeff, alpha, coeff))
        else:
            seen[l] = len(shells)
        shells.append({"l": l, "n": n, "alpha": alpha, "coeff": coeff, "valence": valence,
                       "level": p["levels"][index] * EV_TO_HARTREE, "kcn": p["kcn"][index] * EV_TO_HARTREE,
                       "shpoly": p["shpoly"][index], "refocc": p["refocc"][index] if valence else 0.0,
                       "hardness": p["gam"] * p["lgam"][index]})
    return shells


class Basis:
    """Les couches et orbitales d'une molécule, et les indices qui les relient aux atomes."""

    def __init__(self, numbers):
        self.numbers = np.asarray(numbers, int)
        shells = []
        for atom, z in enumerate(self.numbers):
            for shell in element_shells(int(z)):
                shells.append(dict(shell, atom=atom, z=int(z)))
        self.shells = shells
        self.shell_atom = np.array([s["atom"] for s in shells])
        self.shell_l = np.array([s["l"] for s in shells])
        self.shell_size = 2 * self.shell_l + 1
        self.shell_offset = np.concatenate([[0], np.cumsum(self.shell_size)[:-1]])
        self.nao = int(self.shell_size.sum())
        self.ao_shell = np.repeat(np.arange(len(shells)), self.shell_size)
        self.ao_atom = self.shell_atom[self.ao_shell]
        self.valence = np.array([s["valence"] for s in shells])
        self.level = np.array([s["level"] for s in shells])
        self.kcn = np.array([s["kcn"] for s in shells])
        self.shpoly = np.array([s["shpoly"] for s in shells])
        self.refocc = np.array([s["refocc"] for s in shells])
        self.hardness = np.array([s["hardness"] for s in shells])
        width = max(len(s["alpha"]) for s in shells)
        self.alpha = np.zeros((len(shells), width))
        self.coeff = np.zeros((len(shells), width))
        for i, s in enumerate(shells):
            self.alpha[i, :len(s["alpha"])] = s["alpha"]
            self.alpha[i, len(s["alpha"]):] = 1.0
            self.coeff[i, :len(s["coeff"])] = s["coeff"]
        nsh = len(shells)
        z_shell = self.numbers[self.shell_atom]
        # Le facteur K de chaque paire de couches (valence × valence : k_ll' · k_AB · (1 + k_EN ΔEN²)).
        ham = parameters()["hamiltonian"]["xtb"]
        kshell = {}
        for key, value in ham["shell"].items():
            kshell[(ANGULAR[key[0]], ANGULAR[key[1]])] = value
            kshell[(ANGULAR[key[1]], ANGULAR[key[0]])] = value
        kpol = ham["kpol"]
        self.kscale = np.zeros((nsh, nsh))
        en = np.array([element(int(z))["en"] for z in z_shell])
        for i in range(nsh):
            for j in range(nsh):
                li, lj = self.shell_l[i], self.shell_l[j]
                kii = kshell.get((li, li), 1.0) if self.valence[i] else kpol
                kjj = kshell.get((lj, lj), 1.0) if self.valence[j] else kpol
                if self.valence[i] and self.valence[j]:
                    k = kshell.get((li, lj), (kii + kjj) / 2)
                    k *= pair_parameter(int(z_shell[i]), int(z_shell[j]))
                    k *= 1 + ham["enscale"] * (en[i] - en[j]) ** 2
                else:
                    k = (kii + kjj) / 2
                self.kscale[i, j] = k
        data = tables()
        self.rad = data["atomic_radii"][self.numbers]
        self.rcov = data["cov_d3"][self.numbers]
        self.gam3 = np.array([element(int(z))["gam3"] for z in self.numbers])
        self.zeff = np.array([element(int(z))["zeff"] for z in self.numbers])
        self.arep = np.array([element(int(z))["arep"] for z in self.numbers])
        self.xbond = np.array([element(int(z))["xbond"] for z in self.numbers])
        # Les paires de couches i ≤ j (S est symétrique), groupées par (l, l') pour le calcul vectorisé.
        self.groups = {}
        for i in range(nsh):
            for j in range(i, nsh):
                self.groups.setdefault((self.shell_l[i], self.shell_l[j]), []).append((i, j))
        self.groups = {k: np.array(v) for k, v in self.groups.items()}
        self.nprim = np.array([len(s["alpha"]) for s in shells])

    def overlap(self, positions, derivative=False, moments=False):
        """S (nao, nao) et, si demandé, dS_μν/dR_atome(μ) (nao, nao, 3) ; avec `moments` et `derivative`, aussi les
        moments M (voir Basis.moments), tirés des mêmes intégrales (un tiers du coût d'un calcul à part)."""
        S = np.zeros((self.nao, self.nao))
        dS = np.zeros((self.nao, self.nao, 3)) if derivative else None
        both = derivative and moments
        M = np.zeros((3, self.nao, self.nao)) if both else None
        centers = positions[self.shell_atom]
        for (la, lb), pairs in self.groups.items():
            i, j = pairs[:, 0], pairs[:, 1]
            ka, kb = self.nprim[i].max(), self.nprim[j].max()
            values = integrals.shell_pairs(la, lb, centers[i], centers[j], self.alpha[i, :ka], self.coeff[i, :ka],
                                           self.alpha[j, :kb], self.coeff[j, :kb], derivative, moment=both)
            block, grad = values[0], values[1]
            na, nb = 2 * la + 1, 2 * lb + 1
            rows = self.shell_offset[i][:, None] + np.arange(na)[None, :]
            cols = self.shell_offset[j][:, None] + np.arange(nb)[None, :]
            S[rows[:, :, None], cols[:, None, :]] = block
            S[cols[:, :, None], rows[:, None, :]] = np.swapaxes(block, 1, 2)
            if both:
                self._put_moments(M, values[2], block, centers, i, j, rows, cols)
            if derivative:
                same = self.shell_atom[i] == self.shell_atom[j]
                grad[same] = 0.0
                # dS_νμ/dR_atome(ν) = dS_μν/dB = −dS_μν/dA.
                dS[rows[:, :, None], cols[:, None, :], :] = np.moveaxis(grad, 1, -1)
                dS[cols[:, :, None], rows[:, None, :], :] = -np.moveaxis(grad, 1, -1).swapaxes(1, 2)
        return (S, dS, M) if both else (S, dS)

    @staticmethod
    def _put_moments(M, moment, block, centers, i, j, rows, cols):
        # ⟨ν|(r − B)|μ⟩ = ⟨μ|(r − A)|ν⟩ + (A − B)⟨μ|ν⟩ : le bloc transposé, autour du centre de ν.
        shift = (centers[i] - centers[j]).T[:, :, None, None] * block[None]
        for c in range(3):
            M[c][rows[:, :, None], cols[:, None, :]] = moment[:, c]
            M[c][cols[:, :, None], rows[:, None, :]] = np.swapaxes(moment[:, c] + shift[c], 1, 2)


    def moments(self, positions):
        """M[c, μ, ν] = ⟨μ|(r_c − R_μ,c)|ν⟩ (bohr), le moment autour du centre de μ : (3, nao, nao)."""
        M = np.zeros((3, self.nao, self.nao))
        centers = positions[self.shell_atom]
        for (la, lb), pairs in self.groups.items():
            i, j = pairs[:, 0], pairs[:, 1]
            ka, kb = self.nprim[i].max(), self.nprim[j].max()
            block, moment = integrals.shell_pairs(la, lb, centers[i], centers[j], self.alpha[i, :ka],
                                                  self.coeff[i, :ka], self.alpha[j, :kb], self.coeff[j, :kb],
                                                  moment=True)
            na, nb = 2 * la + 1, 2 * lb + 1
            rows = self.shell_offset[i][:, None] + np.arange(na)[None, :]
            cols = self.shell_offset[j][:, None] + np.arange(nb)[None, :]
            self._put_moments(M, moment, block, centers, i, j, rows, cols)
        return M


# ====================================================================== le nombre de coordination
def coordination(basis, positions, derivative=False):
    """CN_A = Σ_B 1 / (1 + exp(−16 (R⁰_AB/R_AB − 1))) ; si demandé, dCN_A/dR_C (nat, nat, 3) et, paire par paire,
    d count_AB / dR_A (nat, nat, 3)."""
    d = positions[:, None, :] - positions[None, :, :]
    r = np.sqrt(np.sum(d * d, axis=-1))
    n = len(positions)
    np.fill_diagonal(r, np.inf)
    r0 = basis.rcov[:, None] + basis.rcov[None, :]
    mask = r <= CN_CUTOFF
    x = np.exp(-KCN * (r0 / r - 1))
    count = np.where(mask, 1 / (1 + x), 0.0)
    cn = count.sum(axis=1)
    if not derivative:
        return cn, None, None
    dcount = np.where(mask, -KCN * r0 / r ** 2 * x / (1 + x) ** 2, 0.0)   # d count / dR_AB
    unit = np.where(np.isfinite(r)[..., None], d / np.where(np.isfinite(r), r, 1)[..., None], 0.0)
    pair = dcount[..., None] * unit                                          # d count_AB / dR_A
    dcn = np.zeros((n, n, 3))                                                # dCN_A / dR_C
    dcn[np.arange(n), np.arange(n)] = pair.sum(axis=1)
    dcn -= pair
    return cn, dcn, pair


# ====================================================================== la dispersion D3(BJ)
def dispersion(basis, positions, cn, dcn=None):
    """E_disp, son gradient et dE/dCN, C6 interpolé selon le nombre de coordination (Grimme 2010, 2011) ; plus la
    part directe du gradient paire par paire (nat, nat, 3), C6 fixés."""
    data = tables()
    par = parameters()["dispersion"]["d3"]
    z = basis.numbers
    refcn = data["d3_cn"][z]                                                 # (nat, 7)
    valid = refcn >= 0
    w = np.where(valid, np.exp(-4 * (cn[:, None] - refcn) ** 2), 0.0)
    norm = w.sum(axis=1, keepdims=True)
    dw = np.where(valid, -8 * (cn[:, None] - refcn) * w, 0.0)
    weights = np.where(norm > 1e-300, w / np.where(norm > 1e-300, norm, 1), 0.0)
    exceptional = norm[:, 0] <= 1e-300
    if exceptional.any():
        maxcn = np.max(np.where(valid, refcn, -1), axis=1, keepdims=True)
        weights[exceptional] = (refcn == maxcn)[exceptional].astype(float)
    dweights = np.where(norm > 1e-300, (dw * norm - w * dw.sum(axis=1, keepdims=True)) / np.where(norm > 1e-300, norm, 1) ** 2, 0.0)
    c6ref = data["d3_c6"][z[:, None], z[None, :]].astype(float)             # (nat, nat, 7, 7)
    c6 = np.einsum('ai,abij,bj->ab', weights, c6ref, weights)
    dc6 = np.einsum('ai,abij,bj->ab', dweights, c6ref, weights)              # dC6_AB / dCN_A
    r4r2 = data["r4r2"][z]
    qq = 3 * r4r2[:, None] * r4r2[None, :]
    c8 = c6 * qq
    d = positions[:, None, :] - positions[None, :, :]
    r2 = np.sum(d * d, axis=-1)
    n = len(z)
    off = ~np.eye(n, dtype=bool)
    r0 = par["a1"] * np.sqrt(qq) + par["a2"]
    r2s = np.where(off, r2, 1.0)
    t6 = 1 / (r2s ** 3 + r0 ** 6)
    t8 = 1 / (r2s ** 4 + r0 ** 8)
    pair = -(par["s6"] * t6 + par["s8"] * qq * t8)                          # E_AB / C6_AB
    pair = np.where(off, pair, 0.0)
    energy = 0.5 * float(np.sum(c6 * pair))
    dpair_dr2 = np.where(off, par["s6"] * 3 * r2s ** 2 * t6 ** 2 + par["s8"] * qq * 4 * r2s ** 3 * t8 ** 2, 0.0)
    direct = (c6 * dpair_dr2)[..., None] * 2 * d                           # paire AB, C6 fixe
    grad = direct.sum(axis=1)
    dedcn = np.sum(dc6 * pair, axis=1)                                      # dE/dCN_A (les deux paires AB et BA)
    if dcn is not None:
        grad += np.einsum('a,acx->cx', dedcn, dcn)
    return energy, grad, dedcn, direct


# ====================================================================== la répulsion des cœurs
def repulsion(basis, positions):
    d = positions[:, None, :] - positions[None, :, :]
    r = np.sqrt(np.sum(d * d, axis=-1))
    n = len(positions)
    off = ~np.eye(n, dtype=bool)
    rs = np.where(off, r, 1.0)
    alpha = np.sqrt(basis.arep[:, None] * basis.arep[None, :])
    zz = basis.zeff[:, None] * basis.zeff[None, :]
    e = np.where(off, zz / rs * np.exp(-alpha * rs ** REPULSION_KEXP), 0.0)
    energy = 0.5 * float(e.sum())
    de_dr = np.where(off, -e * (1 / rs + alpha * REPULSION_KEXP * rs ** (REPULSION_KEXP - 1)), 0.0)
    grad = np.sum((de_dr / rs)[..., None] * d, axis=1)
    return energy, grad, e


# ====================================================================== la liaison halogène
def halogen_energy(basis, positions):
    z = basis.numbers
    scale = parameters()["halogen"]["classical"]
    rads = tables()["atomic_radii"][z] * scale["rscale"]
    energy = 0.0
    for x in range(len(z)):
        if z[x] not in HALOGENS or basis.xbond[x] == 0:
            continue
        others = [k for k in range(len(z)) if k != x]
        if not others:
            continue
        k = min(others, key=lambda m: np.linalg.norm(positions[m] - positions[x]))
        for j in range(len(z)):
            if z[j] not in HALOGEN_BASES or np.linalg.norm(positions[j] - positions[x]) > HALOGEN_CUTOFF:
                continue
            dxj, dxk, dkj = positions[j] - positions[x], positions[k] - positions[x], positions[k] - positions[j]
            d2xj, d2xk, d2kj = dxj @ dxj, dxk @ dxk, dkj @ dkj
            lj6 = ((rads[x] + rads[j]) / math.sqrt(d2xj)) ** 6
            lj = (lj6 ** 2 - scale["damping"] * lj6) / (1 + lj6 ** 2)
            cosa = (d2xk + d2xj - d2kj) / math.sqrt(d2xk * d2xj)
            energy += lj * (0.5 - 0.25 * cosa) ** 6 * basis.xbond[x]
    return energy


def halogen_gradient(basis, positions, step=1e-5):
    if not any(z in HALOGENS for z in basis.numbers):
        return np.zeros_like(positions)
    grad = np.zeros_like(positions)
    for a in range(len(positions)):
        for c in range(3):
            plus, minus = positions.copy(), positions.copy()
            plus[a, c] += step
            minus[a, c] -= step
            grad[a, c] = (halogen_energy(basis, plus) - halogen_energy(basis, minus)) / (2 * step)
    return grad


# ====================================================================== l'occupation (lissage de Fermi)
def fermi(energies, electrons, kt):
    """Occupations (0–1) d'un jeu d'orbitales pour `electrons` électrons de même spin, et l'entropie (en k_B)."""
    if electrons <= 0:
        return np.zeros_like(energies), 0.0
    order = np.sort(energies)
    k = int(math.ceil(electrons)) - 1
    if k + 1 >= len(order):
        mu = order[-1] + 50 * kt
    else:
        mu = 0.5 * (order[k] + order[k + 1])            # au milieu de la bande interdite
    # Bissection gardée par Newton : la somme des occupations croît avec μ.
    low, high = order[0] - 50 * kt, order[-1] + 50 * kt
    for _ in range(200):
        x = np.clip((energies - mu) / kt, -700, 700)
        f = 1 / (1 + np.exp(x))
        excess = f.sum() - electrons
        if abs(excess) < 1e-12:
            break
        if excess > 0:
            high = mu
        else:
            low = mu
        df = np.sum(f * (1 - f)) / kt
        step = -excess / df if df > 1e-300 else 0.0
        mu = mu + step if df > 1e-300 and low < mu + step < high else 0.5 * (low + high)
        if high - low < 1e-15:
            break
    x = np.clip((energies - mu) / kt, -700, 700)
    f = 1 / (1 + np.exp(x))
    with np.errstate(divide="ignore", invalid="ignore"):
        s = -(f * np.log(np.where(f > 0, f, 1)) + (1 - f) * np.log(np.where(f < 1, 1 - f, 1)))
    return f, float(s.sum())


def _root(g, x, fx, step):
    """La racine d'une fonction croissante g sur [0, ∞[, depuis x (fx = g(x)) : encadrement, puis fausse position
    (Illinois). Rend (x, g(x), l'état de g en x) ; 0 si g(0) ≥ 0."""
    lo, flo, hi, fhi = None, None, None, None
    state = None
    if abs(fx) < 1e-10:
        return x, fx, None
    if fx < 0:
        lo, flo = x, fx
    else:
        hi, fhi = x, fx
    while lo is None:                            # g(x) ≥ 0 : la contrainte est peut-être de trop
        fz, state0 = g(0.0)
        if fz >= 0:
            return 0.0, fz, state0
        lo, flo = 0.0, fz
    while hi is None:
        x = lo + step
        fx, state = g(x)
        if fx >= 0:
            hi, fhi = x, fx
        else:
            lo, flo, step = x, fx, 2 * step
    side = 0
    for _ in range(60):
        x = hi - fhi * (hi - lo) / (fhi - flo)
        fx, state = g(x)
        if abs(fx) < 1e-10 or hi - lo < 1e-14:
            return x, fx, state
        if fx < 0:
            lo, flo = x, fx
            if side == -1:
                fhi *= 0.5
            side = -1
        else:
            hi, fhi = x, fx
            if side == 1:
                flo *= 0.5
            side = 1
    return x, fx, state


# ====================================================================== le calcul
class Result:
    def __init__(self, **values):
        self.__dict__.update(values)


class Calculator:
    """GFN1-xTB pour une molécule donnée (les numéros atomiques sont fixés ; les positions changent)."""

    def __init__(self, numbers, charge=0, unpaired=None, temperature=ELECTRONIC_TEMPERATURE):
        for z in numbers:
            if not supported(int(z)):
                raise ValueError(f"Élément {z} non paramétré dans GFN1-xTB")
        self.basis = Basis(numbers)
        self.valence = np.bincount(self.basis.shell_atom, weights=self.basis.refocc, minlength=len(numbers))
        self.charge = charge
        electrons = float(self.basis.refocc.sum()) - charge
        self.electrons = electrons
        if unpaired is None:
            unpaired = int(round(electrons)) % 2
        self.n_alpha = (electrons + unpaired) / 2
        self.n_beta = (electrons - unpaired) / 2
        self.kt = KB_HARTREE * temperature
        self._charges = None
        n = len(self.basis.shells)
        self._diag_shell = self.basis.shell_atom[:, None] == self.basis.shell_atom[None, :]

    # ------------------------------------------------------------------ électrostatique
    def _gamma(self, positions):
        b = self.basis
        d = positions[b.shell_atom][:, None, :] - positions[b.shell_atom][None, :, :]
        r2 = np.sum(d * d, axis=-1)
        eta = 2 / (1 / b.hardness[:, None] + 1 / b.hardness[None, :])         # moyenne harmonique
        gamma = 1 / np.sqrt(r2 + eta ** -2)
        return gamma, d, r2, eta

    def compute(self, positions_angstrom, gradient=True, tol=1e-8, pairs=False, moments=False):
        """Énergie (Eh), et gradient (Eh/Å) si demandé, pour ces positions (Å).

        Le cycle part des charges du dernier calcul convergé ; s'il ne converge pas de là, on recommence depuis des
        charges nulles. Un résultat aux charges hors de toute borne (plus négative que −3 e malgré le confinement, ou
        plus d'électrons perdus que l'atome n'en a) est marqué `unstable`.
        """
        result = self._compute(positions_angstrom, gradient, tol, pairs, moments)
        if not result.converged and self._charges_were_reused:
            self._charges = None
            retry = self._compute(positions_angstrom, gradient, tol, pairs, moments)
            retry.iterations += result.iterations
            result = retry
        q = result.atom_charges
        result.unstable = bool(np.any(q < -RUNAWAY_CHARGE) or np.any(q > self.valence + 0.5))
        if result.unstable:
            self._charges = None
        return result

    def _compute(self, positions_angstrom, gradient, tol, pairs=False, moments=False):
        b = self.basis
        positions = np.asarray(positions_angstrom, float) / BOHR_ANGSTROM
        nat = len(positions)
        # Avec `moments` (le laboratoire : dipôles des atomes, barycentres des paires), les intégrales de moment sont
        # tirées du même calcul que le recouvrement et ses dérivées.
        integrals_ = b.overlap(positions, derivative=gradient, moments=moments and gradient)
        S, dS = integrals_[0], integrals_[1]
        cn, dcn, cn_pairs = coordination(b, positions, derivative=gradient)
        # H⁰ : niveaux décalés par la coordination, mis à l'échelle et multipliés par le recouvrement.
        h_shell = b.level - b.kcn * cn[b.shell_atom]
        d = positions[:, None, :] - positions[None, :, :]
        r_atoms = np.sqrt(np.sum(d * d, axis=-1))
        rr = np.sqrt(r_atoms / (b.rad[:, None] + b.rad[None, :]))
        rr_shell = rr[b.shell_atom][:, b.shell_atom]
        offsite = ~self._diag_shell
        pi = np.where(offsite, (1 + b.shpoly[:, None] * rr_shell) * (1 + b.shpoly[None, :] * rr_shell), 1.0)
        kscale = np.where(offsite, b.kscale, 1.0)
        hbar = 0.5 * (h_shell[:, None] + h_shell[None, :])
        hcore_shell = pi * kscale * hbar
        hcore = hcore_shell[b.ao_shell][:, b.ao_shell]
        H0 = hcore * S
        gamma, dvec, r2, eta = self._gamma(positions)
        gam3 = b.gam3

        def respond(v_shell, constraint, kt):
            """Les orbitales, leurs occupations et les charges qu'elles donnent, pour ce potentiel de couche (Eh) et
            ces multiplicateurs de la contrainte (Eh, un par atome : ils relèvent ses niveaux)."""
            v_ao = (v_shell - constraint[b.shell_atom])[b.ao_shell]
            F = H0 - 0.5 * S * (v_ao[:, None] + v_ao[None, :])
            eps, C = eigh(F, S)
            fa, sa = fermi(eps, self.n_alpha, kt)
            fb, sb = (fa, sa) if self.n_beta == self.n_alpha else fermi(eps, self.n_beta, kt)
            occ = fa + fb
            P = (C * occ) @ C.T
            pops = np.bincount(b.ao_shell, weights=np.sum(P * S, axis=1), minlength=len(b.shells))
            q_new = b.refocc - pops
            return dict(q=q_new, qa=np.bincount(b.shell_atom, weights=q_new, minlength=nat), entropy=-kt * (sa + sb),
                        orbital_energies=eps, coefficients=C, occupations=occ, occupations_alpha=fa,
                        occupations_beta=fb, density=P, v_ao=v_ao, kt=kt, constraint=constraint.copy())

        def constrained(v_shell, constraint, kt):
            """La réponse des électrons sous la contrainte q_A ≥ −ANION_LIMIT : pour chaque atome qui la franchit,
            λ_A ≥ 0 tel que sa charge y soit exactement (ou λ_A = 0 si elle n'en a plus besoin). Quelques balayages,
            un atome à la fois (Gauss–Seidel)."""
            out = respond(v_shell, constraint, kt)
            for _ in range(8):
                moved = False
                for a in np.flatnonzero((out["qa"] < -ANION_LIMIT - 1e-9) | (constraint > 0)):
                    if constraint[a] == 0 and out["qa"][a] >= -ANION_LIMIT:
                        continue

                    def g(x):
                        trial = constraint.copy()
                        trial[a] = x
                        state = respond(v_shell, trial, kt)
                        return state["qa"][a] + ANION_LIMIT, state

                    x, fx, state = _root(g, constraint[a], out["qa"][a] + ANION_LIMIT, 0.02)
                    if state is not None and abs(x - constraint[a]) > 1e-12:
                        constraint = state["constraint"]
                        out = state
                        moved = True
                if not moved:
                    break
            return out

        def evaluate(q, constraint, kt, confine):
            """Un tour du cycle : les charges d'entrée q donnent le potentiel, puis les électrons et leurs énergies
            (sous la contrainte des anions si `confine`)."""
            qa = np.bincount(b.shell_atom, weights=q, minlength=nat)
            v_shell = gamma @ q + (gam3 * qa ** 2)[b.shell_atom]
            state = constrained(v_shell, constraint, kt) if confine else respond(v_shell, constraint, kt)
            q_new, qa_new, P = state["q"], state["qa"], state["density"]
            state.update(band=float(np.sum(P * H0)), es2=0.5 * float(q_new @ gamma @ q_new),
                         es3=float(np.sum(gam3 * qa_new ** 3)) / 3)
            return state

        def cycle(q, kt, damp, count, confine):
            """Le cycle auto-cohérent sur les charges de couche (mélange de Broyden), à la température électronique
            kT : (convergé, itérations, charges, état final)."""
            mixer = _Broyden(len(q), damp)
            energy_old = None
            constraint = np.zeros(nat)
            for iteration in range(1, count + 1):
                state = evaluate(q, constraint, kt, confine)
                constraint = state["constraint"]
                energy = state["band"] + state["es2"] + state["es3"] + state["entropy"]
                delta = float(np.max(np.abs(state["q"] - q)))
                if delta < 1e-6 and energy_old is not None and abs(energy - energy_old) < tol:
                    return True, iteration, state["q"], state
                energy_old = energy
                q = mixer.mix(q, state["q"], iteration)
            return False, count, q, state

        starts = np.flatnonzero(np.r_[True, np.diff(b.ao_shell) > 0])          # la première orbitale de chaque couche
        same_atom = b.shell_atom[:, None] == b.shell_atom[None, :]

        def jacobian(state, q):
            """J = ∂q_sortie/∂q_entrée au premier ordre (occupations de Fermi, nombre d'électrons fixé, contrainte
            comprise) : J = Π χ K, K = ∂v/∂q (γ + le troisième ordre), χ = ∂q/∂v la réponse des électrons, Π la
            projection qui garde la charge des atomes contraints."""
            eps, C = state["orbital_energies"], state["coefficients"]
            SC = S @ C
            # X_t[i, j] = Σ_{μ ∈ couche t} C_μi (SC)_μj : ce qu'un potentiel sur la couche t fait au couple i, j.
            X = np.add.reduceat(C[:, :, None] * SC[:, None, :], starts, axis=0)
            Z = 0.5 * (X + X.transpose(0, 2, 1)).reshape(len(starts), -1)
            gap = eps[:, None] - eps[None, :]
            chi = np.zeros((len(starts), len(starts)))
            spins = [state["occupations_alpha"]] * 2 if self.n_alpha == self.n_beta else \
                [state["occupations_alpha"], state["occupations_beta"]]
            for f in spins:
                d = f * (1 - f) / state["kt"]                                       # −∂f/∂ε
                with np.errstate(divide="ignore", invalid="ignore"):
                    N = np.where(np.abs(gap) > 1e-9, (f[:, None] - f[None, :]) / gap, -0.5 * (d[:, None] + d[None, :]))
                chi += (Z * N.ravel()) @ Z.T
                if d.sum() > 1e-12:                                                 # le niveau de Fermi suit
                    a = np.einsum("sii,i->s", X, d)
                    chi += np.outer(a, a) / d.sum()
            qa = np.bincount(b.shell_atom, weights=q, minlength=nat)
            J = chi @ (gamma + np.where(same_atom, 2 * (gam3 * qa)[b.shell_atom][:, None], 0.0))
            held = np.flatnonzero(state["constraint"] > 0)
            if len(held):
                E = (b.shell_atom[:, None] == held[None, :]).astype(float)
                J -= chi @ E @ np.linalg.solve(E.T @ chi @ E, E.T @ J)
            return J

        def newton(q, kt, count, confine):
            """Newton amorti sur le résidu r(q) = q_sortie(q) − q : le pas (1 − J)⁻¹ r, J la réponse des charges en
            réponse linéaire, divisé par deux tant que le résidu ne baisse pas. Rend (convergé, tours, charges, état)."""
            n = len(q)
            state = evaluate(q, np.zeros(nat), kt, confine)
            calls, residual = 1, state["q"] - q
            for _ in range(count):
                if np.max(np.abs(residual)) < 1e-7:
                    break
                step = np.linalg.lstsq(np.eye(n) - jacobian(state, q), residual, rcond=1e-10)[0]
                norm, fraction = np.linalg.norm(residual), 1.0
                while True:
                    trial_q = q + fraction * step
                    trial = evaluate(trial_q, state["constraint"], kt, confine)
                    calls += 1
                    trial_residual = trial["q"] - trial_q
                    if np.linalg.norm(trial_residual) < (1 - 0.1 * fraction) * norm or fraction < 1 / 256:
                        break
                    fraction *= 0.5
                q, state, residual = trial_q, trial, trial_residual
            return bool(np.max(np.abs(residual)) < 1e-7), calls, state["q"], state

        def solve(guess, confine):
            converged, iterations, q, state = cycle(guess.copy(), self.kt, *SCC_BROYDEN, confine)
            if not converged:
                converged, n, q, state = newton(guess.copy(), self.kt, NEWTON_STEPS, confine)
                iterations += n
            return converged, iterations, q, state

        # Les paliers (voir SCC_BROYDEN) : chacun repart des charges du dernier calcul convergé. La contrainte des
        # anions n'entre en jeu que si la solution libre la franchit, ou ne converge pas : un point fixe qui la
        # respecte la satisfait déjà (λ = 0), et les tours désordonnés du début d'un pas difficile la franchissent
        # parfois en passant, ce qui coûterait une recherche de λ à chacun.
        self._charges_were_reused = self._charges is not None and len(self._charges) == len(b.shells)
        guess = self._charges if self._charges_were_reused else np.zeros(len(b.shells))
        converged, iterations, q, state = solve(guess, False)
        if not converged or np.min(state["qa"]) < -ANION_LIMIT:
            converged, n, q, state = solve(guess, True)
            iterations += n
        if not converged:
            start, coldest = guess.copy(), None
            for factor in ANNEALING:
                ok, n, q, state = cycle(start.copy(), self.kt * factor, SCC_BROYDEN[0], ANNEALING_ITERATIONS, True)
                iterations += n
                if ok:
                    start, coldest = q, (q, state)
            converged = coldest is not None
            if converged:
                q, state = coldest
        if converged:
            self._charges = q
        energy_el = state["band"] + state["es2"] + state["es3"] + state["entropy"]
        eps, C, occ, P, v_ao = (state[k] for k in ("orbital_energies", "coefficients", "occupations", "density", "v_ao"))
        e_rep, g_rep, rep_pairs = repulsion(b, positions)
        e_disp, g_disp, dedcn_disp, disp_pairs = dispersion(b, positions, cn, dcn)
        e_hal = halogen_energy(b, positions)
        total = energy_el + e_rep + e_disp + e_hal
        result = Result(energy=total, electronic=energy_el, band=state["band"], es2=state["es2"], es3=state["es3"],
                        constraint=state["constraint"], confined=np.flatnonzero(state["constraint"] > 0),
                        entropy=state["entropy"], electronic_temperature=state["kt"] / KB_HARTREE,
                        repulsion=e_rep, dispersion=e_disp, halogen=e_hal, converged=converged,
                        iterations=iterations, orbital_energies=eps, coefficients=C, occupations=occ,
                        occupations_alpha=state["occupations_alpha"], occupations_beta=state["occupations_beta"],
                        density=P, overlap=S, shell_charges=q,
                        atom_charges=np.bincount(b.shell_atom, weights=q, minlength=nat),
                        cn=cn, basis=b, positions=positions,
                        moments=integrals_[2] if len(integrals_) > 2 else None)
        if gradient:
            # L'énergie pondérée W = Σ f ε c cᵀ, puis chaque terme de la force.
            W = (C * (occ * eps)) @ C.T
            g_h0, dedcn_h0, h0_pairs = self._hamiltonian_gradient(positions, S, dS, P, W, v_ao, hcore, hcore_shell,
                                                                  pi, kscale, rr_shell, r_atoms, cn)
            g_cn = np.einsum('a,acx->cx', dedcn_h0, dcn)
            # Électrostatique à charges fixées : dγ/dR.
            with np.errstate(divide="ignore", invalid="ignore"):
                dg = -(r2 + eta ** -2) ** -1.5                                   # dγ/d(R²)/2 · 2 → dγ/dR · 1/R
            qq = q[:, None] * q[None, :] * dg
            g_shell = np.sum(qq[..., None] * dvec, axis=1)
            g_es = np.zeros((nat, 3))
            np.add.at(g_es, b.shell_atom, g_shell)
            g_es *= 1.0
            g_hal = halogen_gradient(b, positions)
            electronic = g_h0 + g_cn + g_es
            result.gradient_parts = {"électronique": electronic / BOHR_ANGSTROM,
                                     "répulsion": g_rep / BOHR_ANGSTROM,
                                     "dispersion": g_disp / BOHR_ANGSTROM,
                                     "halogène": g_hal / BOHR_ANGSTROM}
            result.gradient = (electronic + g_rep + g_disp + g_hal) / BOHR_ANGSTROM      # Eh/Å
            if not pairs:
                return result
            # Paire par paire : [A, B] est la part du gradient sur A qui vient de la paire AB (Eh/Å). Chaque terme
            # est une somme sur les paires d'atomes (troisième loi : [B, A] = −[A, B]) ; seule la liaison halogène,
            # à trois corps, n'est pas décomposée.
            es_pairs = np.stack([_reduce(b.shell_atom, qq * dvec[..., k], nat) for k in range(3)], axis=-1)
            cn_link = (dedcn_h0[:, None] + dedcn_h0[None, :])[..., None] * cn_pairs
            d_atoms = positions[:, None, :] - positions[None, :, :]
            r_safe = np.where(np.eye(nat, dtype=bool), 1.0, r_atoms)
            rep_link = (_repulsion_slope(b, r_safe, rep_pairs) / r_safe)[..., None] * d_atoms
            disp_link = disp_pairs + (dedcn_disp[:, None] + dedcn_disp[None, :])[..., None] * cn_pairs
            # La part des électrons se lit en deux : le recouvrement des orbitales (bande, à densité fixée, et son
            # décalage par la coordination) et l'électrostatique des charges.
            result.pair_split = {"recouvrement": (h0_pairs + cn_link) / BOHR_ANGSTROM, "charges": es_pairs / BOHR_ANGSTROM}
            result.pair_gradient = {"électronique": (h0_pairs + cn_link + es_pairs) / BOHR_ANGSTROM,
                                    "répulsion": rep_link / BOHR_ANGSTROM,
                                    "dispersion": disp_link / BOHR_ANGSTROM}
        return result

    def _hamiltonian_gradient(self, positions, S, dS, P, W, v_ao, hcore, hcore_shell, pi, kscale, rr_shell,
                              r_atoms, cn):
        """dE_bande/dR (à densité fixée : P, W) et dE/dCN, transposés de l'implémentation de référence."""
        b = self.basis
        nat = len(positions)
        offsite = ~self._diag_shell
        shpoly_a, shpoly_b = b.shpoly[:, None], b.shpoly[None, :]
        tmp_a, tmp_b = 1 + shpoly_a * rr_shell, 1 + shpoly_b * rr_shell
        r_shell = r_atoms[b.shell_atom][:, b.shell_atom]
        with np.errstate(divide="ignore", invalid="ignore"):
            dpi = np.where(offsite, (tmp_a * shpoly_b + tmp_b * shpoly_a) * rr_shell * 0.5 / r_shell ** 2, 0.0)
        ph = P * hcore
        ps_shell = _reduce(b.ao_shell, ph * S, len(b.shells))
        dpi_term = 2 * ps_shell * dpi / pi
        dpi_atoms = _reduce(b.shell_atom, dpi_term, nat)
        rij = positions[:, None, :] - positions[None, :, :]
        g1 = np.sum(dpi_atoms[..., None] * rij, axis=1)
        tmp = 2 * (ph - W) - P * (v_ao[:, None] + v_ao[None, :])
        off_ao = b.ao_atom[:, None] != b.ao_atom[None, :]
        sval = np.where(off_ao, tmp, 0.0)
        g2 = np.zeros((nat, 3))
        np.add.at(g2, b.ao_atom, np.sum(dS * sval[..., None], axis=1))
        pairs = dpi_atoms[..., None] * rij
        pairs += np.stack([_reduce(b.ao_atom, dS[..., k] * sval, nat) for k in range(3)], axis=-1)
        dsedcn = -b.kcn[None, :]
        dhdcn = np.where(offsite, dsedcn * pi * kscale, dsedcn)
        pss = _reduce(b.ao_shell, P * S, len(b.shells))
        dcn_shell = pss * dhdcn
        dedcn = _reduce(b.shell_atom, dcn_shell, nat).sum(axis=0)
        return g1 + g2, dedcn, pairs

    def energy(self, positions_angstrom):
        return self.compute(positions_angstrom, gradient=False).energy


def _repulsion_slope(basis, r, pair_energy):
    """dE_AB/dR pour la répulsion des cœurs (bohr), diagonale nulle."""
    alpha = np.sqrt(basis.arep[:, None] * basis.arep[None, :])
    slope = -pair_energy * (1 / r + alpha * REPULSION_KEXP * r ** (REPULSION_KEXP - 1))
    np.fill_diagonal(slope, 0.0)
    return slope


def _reduce(index, matrix, size):
    """Somme d'une matrice par blocs (orbitales → couches, couches → atomes) : Pᵀ M P, P la matrice d'appartenance."""
    member = np.zeros((len(index), size))
    member[np.arange(len(index)), index] = 1.0
    return member.T @ matrix @ member


class _Broyden:
    """Mélange de Broyden modifié (Johnson 1988) des charges entre deux itérations."""

    def __init__(self, size, damp=0.4, memory=8):
        self.damp = damp
        self.memory = memory
        self.df, self.dx = [], []
        self.last_x = self.last_f = None

    def mix(self, x, x_new, iteration):
        f = x_new - x
        if self.last_x is not None:
            df = f - self.last_f
            dx = x - self.last_x
            norm = df @ df
            if norm > 1e-30:
                self.df.append(df / math.sqrt(norm))
                self.dx.append(dx / math.sqrt(norm))
                if len(self.df) > self.memory:
                    self.df.pop(0)
                    self.dx.pop(0)
        self.last_x, self.last_f = x.copy(), f.copy()
        update = self.damp * f
        if self.df:
            dfm = np.array(self.df)
            dxm = np.array(self.dx)
            gamma = np.linalg.lstsq(dfm @ dfm.T + 1e-12 * np.eye(len(dfm)), dfm @ f, rcond=None)[0]
            update = self.damp * f - (dxm.T + self.damp * dfm.T) @ gamma
        return x + update
