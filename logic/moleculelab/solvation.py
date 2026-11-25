"""Le solvant implicite de GFN1-xTB : ALPB (Ehlert, Stahn, Spicher, Grimme, J. Chem. Theory Comput. 17, 4250, 2021),
transposé de tblite (le code de référence de xtb), avec ses paramètres (data/gfn1-alpb.json, tools/build_solvation_data.py).

L'énergie libre de solvatation d'une molécule y a quatre termes :

- **polaire** (Born généralisé, avec la correction « analytique linéarisée de Poisson–Boltzmann ») :
      G_pol = ½ Σ_ij q_i q_j k_ε / f(r_ij)  +  ½ k_ε α_β (Σ q)² / A_det,
  k_ε = (1/ε − 1)/(1 + α_β), α_β = 0,571412/ε ; f est le noyau P16 de Lange et Herbert, f = r + R (1 + ζr/16R)⁻¹⁶ avec
  R = √(R_i R_j) ; R_i est le rayon de Born de l'atome i (Onufriev–Bashford–Case, son intégrale d'écrantage par les
  sphères des voisins, rayons de van der Waals D3 × un facteur d'écrantage par élément) ; A_det une taille de la
  molécule (son tenseur d'inertie). Les charges q sont celles de Mulliken de GFN1, corrigées par CM5 (Marenich et al.,
  une correction qui ne dépend que de la géométrie) ;
- **liaison hydrogène** : G_hb = Σ_i h_i S_i q_i² (S_i la surface accessible au solvant de l'atome i) ;
- **non polaire** (cavité, dispersion, structure du solvant) : G_np = Σ_i γ_i S_i ;
- **décalage** : une constante par solvant (état de référence 1 L de gaz → 1 L de solution).

G_pol et G_hb dépendent des charges : leur potentiel v_i = ∂G/∂q_i entre dans le cycle auto-cohérent, les électrons
répondent au solvant. La surface est intégrée sur une grille de Lebedev de 230 points par atome, lissée (rayon de
van der Waals + sonde, adoucie sur ±0,3 Å). Toutes les dérivées sont analytiques, sauf celle de l'intégrale
d'écrantage par rapport à la distance (différence centrale, pas de 10⁻⁵ bohr).
"""

import json
import math
from functools import lru_cache
from pathlib import Path

import numpy as np

BOHR_ANGSTROM = 0.529177210903
AATOAU = 1 / BOHR_ANGSTROM
KCAL_HARTREE = 1 / 627.509474
ZETA = 1.028                     # noyau P16
ALPHA = 0.571412                 # ALPB
OBC = (1.0, 0.8, 4.85)           # Onufriev–Bashford–Case, jeu II
SMOOTHING = 0.3 * AATOAU
GRID_DEGREE = 25                 # 230 points de Lebedev
DATA = Path(__file__).resolve().parent / "data" / "gfn1-alpb.json"

# Le nom d'un solvant ALPB, son nom à l'écran, et les façons de l'écrire au-dessus d'une flèche.
NAMES = {
    "water": ("eau", ["eau", "water", "h2o", "h₂o"]),
    "acetonitrile": ("acétonitrile", ["acétonitrile", "acetonitrile", "mecn", "ch3cn", "ch₃cn"]),
    "dmf": ("DMF", ["dmf", "diméthylformamide", "dimethylformamide"]),
    "dmso": ("DMSO", ["dmso", "diméthylsulfoxyde", "dimethylsulfoxide"]),
    "methanol": ("méthanol", ["méthanol", "methanol", "meoh"]),
    "ethanol": ("éthanol", ["éthanol", "ethanol", "etoh"]),
    "thf": ("THF", ["thf", "tétrahydrofurane", "tetrahydrofuran"]),
    "acetone": ("acétone", ["acétone", "acetone"]),
    "ch2cl2": ("dichlorométhane", ["dcm", "ch2cl2", "ch₂cl₂", "dichlorométhane", "dichloromethane"]),
    "chcl3": ("chloroforme", ["chcl3", "chcl₃", "chloroforme", "chloroform"]),
    "ether": ("éther", ["éther", "ether", "et2o", "et₂o"]),
    "dioxane": ("dioxane", ["dioxane"]),
    "ethylacetate": ("acétate d'éthyle", ["acoet", "etoac", "acétate d'éthyle", "ethyl acetate"]),
    "nitromethane": ("nitrométhane", ["nitrométhane", "nitromethane", "meno2"]),
    "toluene": ("toluène", ["toluène", "toluene", "phme"]),
    "benzene": ("benzène", ["benzène", "benzene"]),
    "hexane": ("hexane", ["hexane", "hexanes"]),
    "hexadecane": ("hexadécane", ["hexadécane", "hexadecane"]),
    "cs2": ("CS₂", ["cs2", "cs₂"]),
    "aniline": ("aniline", ["aniline"]),
    "benzaldehyde": ("benzaldéhyde", ["benzaldéhyde", "benzaldehyde"]),
    "phenol": ("phénol", ["phénol", "phenol"]),
    "furane": ("furane", ["furane", "furan"]),
    "octanol": ("octanol", ["octanol"]),
    "woctanol": ("octanol humide", ["octanol humide", "wet octanol", "woctanol"]),
}
# L'ordre proposé dans le laboratoire (les plus courants d'abord).
ORDER = ["water", "methanol", "ethanol", "acetonitrile", "dmso", "dmf", "acetone", "thf", "ch2cl2", "chcl3", "ether",
         "ethylacetate", "dioxane", "nitromethane", "toluene", "benzene", "hexane", "hexadecane", "cs2", "aniline",
         "benzaldehyde", "phenol", "furane", "octanol", "woctanol"]


@lru_cache(maxsize=1)
def data():
    with open(DATA, encoding="utf-8") as handle:
        return json.load(handle)


def label(solvent):
    """Le nom affiché (« eau », « DMF »)."""
    return NAMES[solvent][0] if solvent in NAMES else solvent


def find(token):
    """Le solvant ALPB que désigne ce texte (« THF », « H2O », « acétonitrile »), ou None."""
    t = token.strip().lower()
    for key, (_, aliases) in NAMES.items():
        if t == key or t in aliases:
            return key
    return None


@lru_cache(maxsize=1)
def _grid():
    from scipy.integrate import lebedev_rule
    points, weights = lebedev_rule(GRID_DEGREE)            # poids de somme 4π
    return points.T.copy(), weights.copy()


class Solvation:
    """Le solvant pour une molécule donnée (ses éléments) ; `prepare` en tire ce qui ne dépend que de la géométrie."""

    def __init__(self, numbers, solvent):
        d = data()
        if solvent not in d["solvents"]:
            raise ValueError(f"solvant inconnu : {solvent}")
        p = d["solvents"][solvent]
        z = np.asarray(numbers, int)
        if np.any(z > 94):
            raise ValueError("le solvant ALPB n'est paramétré que jusqu'au plutonium")
        self.solvent = solvent
        self.epsilon = p["epsilon"]
        self.alpbet = ALPHA / self.epsilon
        self.keps = (1 / self.epsilon - 1) / (1 + self.alpbet)
        vdw = np.array(d["vdw_d3_angstrom"])[z - 1] * AATOAU
        self.vdwr = vdw
        self.rho = vdw * np.array(p["descreening"])[z - 1]
        self.svdw = vdw - p["born_offset"] * 0.1 * AATOAU
        self.c1 = p["born_scale"]
        probe = p["probe_angstrom"] * AATOAU
        self.tension = np.array(p["tension"])[z - 1] * 1e-5
        self.hbond = -KCAL_HARTREE * np.array(p["sqrt_hbond"])[z - 1] ** 2 / (4 * math.pi * (vdw + probe) ** 2)
        self.vdwsa = vdw + probe
        w = SMOOTHING
        self.ah0, self.ah1, self.ah3 = 0.5, 3 / (4 * w), -1 / (4 * w ** 3)
        self.lo2 = (self.vdwsa - w) ** 2
        self.hi2 = (self.vdwsa + w) ** 2

        def part(r, v):
            return (0.25 / w + 3 * self.ah3 * (0.2 * r * r - 0.5 * r * v + v * v / 3)) * r ** 3

        self.wrp = part(self.vdwsa + w, self.vdwsa) - part(self.vdwsa - w, self.vdwsa)
        self.shift = p["gshift_kcal"] * KCAL_HARTREE
        # CM5 : le paramètre de chaque paire d'éléments, et les rayons atomiques.
        a0 = np.array(d["cm5_a0"])
        special = {tuple(int(x) for x in k.split("-")): v for k, v in d["cm5_pairs"].items()}
        n = len(z)
        pair = np.zeros((n, n))
        for i in range(n):
            for j in range(n):
                a, b = int(z[i]), int(z[j])
                if a == b:
                    continue
                lo, hi = min(a, b), max(a, b)
                value = special.get((lo, hi), a0[lo - 1] - a0[hi - 1])
                pair[i, j] = value if a < b else -value
        self.cm5_pair = pair
        self.cm5_rad = np.array(d["atomic_rad_angstrom"])[z - 1] * AATOAU
        self.cm5_alpha = d["cm5_alpha_per_angstrom"] * BOHR_ANGSTROM

    # ------------------------------------------------------------------ la géométrie
    def prepare(self, positions, gradient=True):
        """Ce qui ne dépend que des positions (bohr) : la matrice de Born A (avec la correction ALPB), la correction
        CM5, les surfaces ; et, si demandé, leurs dérivées."""
        x = np.asarray(positions, float)
        n = len(x)
        vec = x[:, None, :] - x[None, :, :]
        r = np.sqrt(np.sum(vec * vec, axis=-1))
        off = ~np.eye(n, dtype=bool)
        out = {"r": r, "vec": vec}
        # Les rayons de Born.
        psi, dpsi = self._psi(r, vec, off)
        rad, drad = self._born(psi, dpsi)
        out["rad"], out["drad"] = rad, drad
        # La matrice de Born (noyau P16) et la correction ALPB.
        ab = np.sqrt(rad[:, None] * rad[None, :])
        arg1 = ab / (ab + ZETA / 16 * r)
        arg16 = arg1 ** 16
        fgb = np.where(off, r + ab * arg16, 1.0)
        A = np.where(off, self.keps / fgb, 0.0)
        A[np.diag_indices(n)] = self.keps / rad
        adet = self._adet(x)
        A += self.keps * self.alpbet / adet
        out.update(A=A, ab=ab, arg1=arg1, arg16=arg16, fgb=fgb, adet=adet)
        # CM5.
        safe = np.where(off, r, 1.0)
        bkk = np.where(off, np.exp(-self.cm5_alpha * (safe - self.cm5_rad[:, None] - self.cm5_rad[None, :])), 0.0)
        out["cm5"] = np.sum(bkk * self.cm5_pair, axis=1)
        # La surface accessible au solvant.
        surface, dsurface = self._surface(x, gradient)
        out["surface"], out["dsurface"] = surface, dsurface
        if gradient:
            # dcm5_i/dx_m (m, i, xyz) : pour la paire i, j, la dérivée de bkk_ij par rapport à x_i.
            dbkk = (-self.cm5_alpha * bkk / safe)[..., None] * vec
            dcm5 = np.zeros((n, n, 3))
            terms = self.cm5_pair[..., None] * dbkk                     # (i, j, 3) : ∂(bkk_ij P_ij)/∂x_i
            dcm5[np.arange(n), np.arange(n)] = np.sum(terms, axis=1)    # ∂cm5_i/∂x_i
            dcm5 -= terms.transpose(1, 0, 2)                            # ∂cm5_i/∂x_j (m = j)
            out["dcm5"] = dcm5
        return out

    def _psi(self, r, vec, off):
        """L'intégrale d'écrantage de chaque atome par ses voisins (OBC) et sa dérivée (m, i, xyz)."""
        n = len(r)
        rvdw_i = np.broadcast_to(self.vdwr[:, None], (n, n))
        rho_j = np.broadcast_to(self.rho[None, :], (n, n))
        g = np.where(off, _screen(np.where(off, r, 1.0), rho_j, rvdw_i), 0.0)
        h = 1e-5
        dg = np.where(off, (_screen(np.where(off, r + h, 1.0), rho_j, rvdw_i)
                            - _screen(np.where(off, r - h, 1.0), rho_j, rvdw_i)) / (2 * h), 0.0)
        psi = g.sum(axis=1)
        unit = np.where(off, 1 / np.where(off, r, 1.0), 0.0)[..., None] * vec          # (i, j, 3) : (x_i − x_j)/r
        term = dg[..., None] * unit                                                       # ∂g_ij/∂x_i
        dpsi = np.zeros((n, n, 3))                                                       # (m, i, 3)
        dpsi[np.arange(n), np.arange(n)] = term.sum(axis=1)
        dpsi -= term.transpose(1, 0, 2)
        return psi, dpsi

    def _born(self, psi, dpsi):
        s1, v1, s2 = 1 / self.svdw, 1 / self.vdwr, 0.5 * self.svdw
        br = psi * s2
        arg2 = br * (OBC[2] * br - OBC[1])
        arg = br * (OBC[0] + arg2)
        darg = 2 * arg2 + OBC[0] + OBC[2] * br * br
        th, ch = np.tanh(arg), np.cosh(arg)
        rad = self.c1 / (s1 - v1 * th)
        drdpsi = self.c1 * s2 * v1 * darg / (ch * (s1 - v1 * th)) ** 2
        return rad, dpsi * drdpsi[None, :, None]

    def _adet(self, x, derivative=False):
        r3 = self.vdwr ** 3
        vol = r3.sum()
        centre = (x * r3[:, None]).sum(axis=0) / vol
        v = x - centre
        r2 = np.sum(v * v, axis=1)
        inertia = np.zeros((3, 3))
        for k in range(len(x)):
            inertia += r3[k] * ((r2[k] + 0.4 * self.vdwr[k] ** 2) * np.eye(3) - np.outer(v[k], v[k]))
        adet = math.sqrt(np.linalg.det(inertia) ** (1 / 3) / (0.4 * vol))
        if not derivative:
            return adet
        return adet, inertia, v, r3, vol

    def _surface(self, x, gradient):
        """La surface accessible de chaque atome (bohr²), lissée ; et dS_i/dx_m (m, i, xyz)."""
        n = len(x)
        points, weights = _grid()
        surface = np.zeros(n)
        dsurface = np.zeros((n, n, 3)) if gradient else None
        for i in range(n):
            others = np.flatnonzero(np.arange(n) != i)
            p = x[i] + self.vdwsa[i] * points                                # (P, 3)
            if len(others) == 0:
                surface[i] = self.wrp[i] * weights.sum()
                continue
            d = p[:, None, :] - x[others][None, :, :]                        # (P, J, 3)
            t2 = np.sum(d * d, axis=-1)
            lo, hi = self.lo2[others][None, :], self.hi2[others][None, :]
            t = np.sqrt(t2)
            u = t - self.vdwsa[others][None, :]
            s = np.where(t2 >= hi, 1.0, np.where(t2 <= lo, 0.0, self.ah0 + (self.ah1 + self.ah3 * u * u) * u))
            sasa = np.prod(s, axis=1)                                         # (P,)
            keep = sasa > 1e-6
            wsa = np.where(keep, weights * self.wrp[i] * sasa, 0.0)
            surface[i] = wsa.sum()
            if gradient:
                ds = np.where((t2 < hi) & (t2 > lo), self.ah1 + 3 * self.ah3 * u * u, 0.0)
                ratio = np.where(s > 1e-12, ds / np.where(s > 1e-12, s, 1.0), 0.0)
                grds = (ratio / np.where(t > 0, t, 1.0))[..., None] * d       # (P, J, 3) : ∂ln(sasa)/∂p par voisin
                contrib = np.einsum("p,pjx->jx", wsa, grds)                   # (J, 3)
                dsurface[i, i] += contrib.sum(axis=0)
                dsurface[others, i] -= contrib
        return surface, dsurface

    # ------------------------------------------------------------------ l'énergie, le potentiel, le gradient
    def charges(self, geometry, atom_charges):
        return np.asarray(atom_charges, float) + geometry["cm5"]

    def potential(self, geometry, atom_charges):
        """v_i = ∂(G_pol + G_hb)/∂q_i (Eh/e), à ajouter au potentiel de chaque couche de l'atome."""
        q = self.charges(geometry, atom_charges)
        return geometry["A"] @ q + 2 * self.hbond * geometry["surface"] * q

    def response(self, geometry):
        """∂v/∂q : la matrice de Born et le terme de liaison hydrogène."""
        return geometry["A"] + np.diag(2 * self.hbond * geometry["surface"])

    def energies(self, geometry, atom_charges):
        """(polaire, liaison hydrogène, non polaire, décalage), Eh."""
        q = self.charges(geometry, atom_charges)
        return (0.5 * float(q @ geometry["A"] @ q), float(np.sum(self.hbond * geometry["surface"] * q * q)),
                float(np.sum(self.tension * geometry["surface"])), self.shift)

    def gradient(self, geometry, atom_charges, positions):
        """dG/dx (Eh/bohr, (n, 3)) à charges de Mulliken fixées (la réponse des électrons passe par le potentiel)."""
        q = self.charges(geometry, atom_charges)
        n = len(q)
        r, vec, rad = geometry["r"], geometry["vec"], geometry["rad"]
        off = ~np.eye(n, dtype=bool)
        ab, arg1, arg16, fgb = geometry["ab"], geometry["arg1"], geometry["arg16"], geometry["fgb"]
        qq = q[:, None] * q[None, :]
        dfgb2 = np.where(off, 1 / fgb ** 2, 0.0)
        safe = np.where(off, r, 1.0)
        # Le noyau, à rayons de Born fixés (½ Σ_{i≠j} : chaque paire deux fois, d'où le ½).
        ap = (1 - ZETA * arg1 * arg16) * dfgb2
        dG = np.where(off, ap * self.keps * qq / safe, 0.0)[..., None] * vec
        g = -np.sum(dG, axis=1)
        # Par les rayons de Born.
        bp = np.where(off, -0.5 * (safe * ZETA / ab * arg1 + 1) / ab * arg16 * dfgb2, 0.0)
        dedbr = np.sum(rad[None, :] * bp * self.keps * qq, axis=1) - 0.5 * self.keps * q * q / rad ** 2
        g += np.einsum("i,mix->mx", dedbr, geometry["drad"])
        # La correction ALPB (la forme de la molécule).
        g += self._adet_gradient(positions, self.keps * self.alpbet, q.sum())
        # Les charges CM5 (qui suivent la géométrie) : v · dcm5.
        v = geometry["A"] @ q + 2 * self.hbond * geometry["surface"] * q
        g += np.einsum("i,mix->mx", v, geometry["dcm5"])
        # La surface : la tension et la liaison hydrogène.
        g += np.einsum("i,mix->mx", self.tension + self.hbond * q * q, geometry["dsurface"])
        return g

    def _adet_gradient(self, x, keps, total):
        adet, inertia, v, r3, vol = self._adet(x, derivative=True)
        I = inertia
        deriv = np.array([
            [I[0, 0] * (I[1, 1] + I[2, 2]) - I[0, 1] ** 2 - I[0, 2] ** 2, I[0, 1] * I[2, 2] - I[0, 2] * I[1, 2],
             I[0, 2] * I[1, 1] - I[0, 1] * I[2, 1]],
            [I[0, 1] * I[2, 2] - I[0, 2] * I[1, 2], I[1, 1] * (I[0, 0] + I[2, 2]) - I[0, 1] ** 2 - I[1, 2] ** 2,
             I[0, 0] * I[1, 2] - I[0, 1] * I[0, 2]],
            [I[0, 2] * I[1, 1] - I[0, 1] * I[2, 1], I[0, 0] * I[1, 2] - I[0, 1] * I[0, 2],
             I[2, 2] * (I[0, 0] + I[1, 1]) - I[0, 2] ** 2 - I[1, 2] ** 2]])
        deriv *= (250.0 / (48.0 * vol ** 3 * adet ** 5)) * (-0.5 * keps * total ** 2 / adet ** 2)
        return r3[:, None] * (v @ deriv.T)


def _screen(r, rho, rvdw):
    """Ce que la sphère de rayon ρ, à la distance r, retire de l'intégrale de Born d'un atome de rayon R_vdW (OBC)."""
    with np.errstate(divide="ignore", invalid="ignore"):
        overlap = r < rvdw + rho
        # Sans recouvrement : ρ/(r² − ρ²) + ln((r − ρ)/(r + ρ))/(2r).
        ap, am = r + rho, r - rho
        free = rho / (ap * am) + 0.5 * np.log(np.abs(am / ap)) / r
        # Recouvrement partiel : 1/R − 1/(r + ρ) + [½ (r − ρ)(1/(r + ρ) − (r + ρ)/R²) − ln((r + ρ)/R)]/(2r).
        aprh = ap / rvdw
        part = 1 / rvdw - 1 / ap + 0.5 / r * (0.5 * am * (1 / ap - aprh / rvdw) - np.log(aprh))
        part = np.where(ap > rvdw, part, 0.0)                    # entièrement à l'intérieur : rien à retirer
        return np.where(overlap, part, free)
