"""Le nucléon de l'intérieur : trois quarks de valence dans une corde de couleur.

Modèle de quarks constituants (type Cornell), calculé, pas dessiné :

* trois quarks (uud, udd) de masse constituante m ≈ 336 MeV, de charge 2/3 ou −1/3, de couleur rouge, verte, bleue ;
* le confinement : une corde de tension σ = 0,18 GeV² en **Y**, dont la longueur est celle de l'arbre de Steiner
  (point de Fermat) ; V_corde = σ·L_Y ;
* l'échange de gluons à courte portée : V_c = −C·α_s·ħc / r, avec C = 2/3 par paire dans un baryon, 4/3 dans un
  méson (facteur de couleur), régularisé à r → √(r² + ε²) ;
* dynamique relativiste (E = √(p² + m²)) intégrée par DOP853 ; l'énergie totale est vérifiée à chaque instant.

Quand un quark reçoit une impulsion (diffusion profonde inélastique : un photon virtuel le frappe), la corde
s'étire. Elle peut se **rompre** : le champ de couleur crée une paire quark–antiquark (effet Schwinger, modèle des cordes
de Lund). Le taux de rupture par fm de corde et par fm/c est Γ₀·exp(−π m²/σ) : la masse du quark décide quelles
saveurs sortent (les étranges sont rares). La paire naît au repos, séparée de d telle que l'énergie soit exactement
conservée (σ·d libère l'énergie de masse 2m, à l'énergie de Coulomb près). Un baryon et un méson en résultent ; chacun
peut se rompre à son tour. La masse reste celle des hadrons mesurés (PDG) pour leur nom.

Limites (affichées dans l'interface) : constante d'ajustement Γ₀ (la formation d'un hadron en ~1–2 fm) ; les spins ne
sont pas dynamiques ; pas de pT à la rupture ; la masse du pion (boson de Goldstone) n'est pas reproduite par un
modèle constituant — les hadrons sont nommés par leur contenu en quarks et portent leur masse mesurée ; l'état
fondamental est celui d'une gaussienne variationnelle, dont on tire positions et impulsions (Wigner exact).
Unités : fm, MeV, temps en fm/c (c = 1).
"""
import math

import numpy as np
from scipy import constants as sc
from scipy.integrate import solve_ivp
from scipy.optimize import brentq, minimize_scalar

HBAR_C = sc.hbar * sc.c / (1e6 * sc.e * 1e-15)           # 197,327 MeV·fm
SIGMA_MEV2 = 0.18e6                                      # σ = 0,18 GeV²
SIGMA = SIGMA_MEV2 / HBAR_C                              # MeV/fm (≈ 912)
ALPHA_S = 0.40
SMEAR_FM = 0.2
MASS = {'u': 336.0, 'd': 336.0, 's': 500.0}
CHARGE = {'u': 2 / 3, 'd': -1 / 3, 's': -1 / 3}
COLORS = ('rouge', 'vert', 'bleu')
PROTON_MEV = 938.272
NEUTRON_MEV = 939.565
BREAK_RATE = 4.4                                         # Γ₀ (fm⁻²), calibré : première rupture ≈ 2 fm
MAX_BREAKS = 8
T_END = 26.0
MAX_KICK_MEV = 15000.0                                             # fm/c
FRAMES = 260
FLAVOR_WEIGHT = {f: math.exp(-math.pi * m * m / SIGMA_MEV2) for f, m in MASS.items()}

BARYON_NAMES = {
    ('u', 'u', 'd'): ('proton', 938.272), ('d', 'u', 'd'): ('neutron', 939.565),
    ('u', 'u', 'u'): ('Δ⁺⁺', 1232.0), ('d', 'd', 'd'): ('Δ⁻', 1232.0),
    ('u', 'u', 's'): ('Σ⁺', 1189.37), ('d', 'd', 's'): ('Σ⁻', 1197.45), ('d', 's', 'u'): ('Λ', 1115.68),
    ('s', 's', 'u'): ('Ξ⁰', 1314.86), ('d', 's', 's'): ('Ξ⁻', 1321.71), ('s', 's', 's'): ('Ω⁻', 1672.45),
}
MESON_NAMES = {
    ('u', 'd'): ('π⁺', 139.570), ('d', 'u'): ('π⁻', 139.570), ('u', 'u'): ('π⁰', 134.977), ('d', 'd'): ('π⁰', 134.977),
    ('u', 's'): ('K⁺', 493.677), ('s', 'u'): ('K⁻', 493.677), ('d', 's'): ('K⁰', 497.611), ('s', 'd'): ('K̄⁰', 497.611),
    ('s', 's'): ('φ', 1019.46),
}


def baryon_name(flavors):
    """(nom, masse mesurée) d'un baryon à partir de ses saveurs (le contenu seul fixe le multiplet)."""
    key = tuple(sorted(flavors, key=lambda f: 'uds'.index(f)))
    table = {tuple(sorted(k, key=lambda f: 'uds'.index(f))): v for k, v in BARYON_NAMES.items()}
    if key == ('u', 'd', 's'):
        return 'Λ', 1115.68
    return table.get(key, (''.join(key), 1000.0))


def meson_name(quark, antiquark):
    """Un quark `quark` et un antiquark d'une saveur `antiquark` : le nom du méson le plus léger de ce contenu."""
    if quark == antiquark:
        return ('π⁰', 134.977) if quark in 'ud' else ('φ', 1019.46)
    return MESON_NAMES.get((quark, antiquark), (quark + antiquark + '̄', 600.0))


def charge_of(flavor, anti):
    return -CHARGE[flavor] if anti else CHARGE[flavor]


# ================================================================== l'énergie
def _apex(a, b, c):
    """Le sommet du triangle équilatéral bâti sur bc, du côté opposé à a (construction de Napoléon)."""
    m, edge = (b + c) / 2, c - b
    n = m - a
    n = n - np.dot(n, edge) / np.dot(edge, edge) * edge
    return m + (math.sqrt(3) / 2) * np.linalg.norm(edge) * n / np.linalg.norm(n)


def fermat(points):
    """Le point de Fermat de trois points : (J, L, jambes), en forme close. Si un angle atteint 120°, J est ce sommet.

    J est l'intersection des droites joignant chaque sommet au sommet du triangle équilatéral bâti sur le côté opposé,
    et L = |A − A'| (la longueur de l'arbre de Steiner) : exact, donc la force dérive exactement du potentiel.
    """
    a, b, c = (np.asarray(p, dtype=float) for p in points)
    pts = [a, b, c]
    for k in range(3):
        u, v = pts[(k + 1) % 3] - pts[k], pts[(k + 2) % 3] - pts[k]
        nu, nv = np.linalg.norm(u), np.linalg.norm(v)
        if nu < 1e-12 or nv < 1e-12 or np.dot(u, v) / (nu * nv) <= -0.5:
            legs = [float(np.linalg.norm(pts[i] - pts[k])) for i in range(3)]
            return pts[k].copy(), float(sum(legs)), legs
    a2, b2 = _apex(a, b, c), _apex(b, c, a)
    da, db = a2 - a, b2 - b
    w = b - a
    m = np.array([[np.dot(da, da), -np.dot(da, db)], [-np.dot(da, db), np.dot(db, db)]])
    t, _ = np.linalg.solve(m, np.array([np.dot(da, w), -np.dot(db, w)]))
    j = a + t * da
    return j, float(np.linalg.norm(da)), [float(np.linalg.norm(q - j)) for q in pts]


_SQRT3_2 = math.sqrt(3) / 2


def _scalar_baryon(x):
    """Les mêmes formules que `baryon_potential`, en scalaires Python purs (l'intégrateur l'appelle des dizaines de
    milliers de fois). `x` : 3 lignes de 3 flottants. Renvoie (V, grad 3×3 en listes, jonction, jambes)."""
    (ax, ay, az), (bx, by, bz), (cx, cy, cz) = x[0], x[1], x[2]
    pts = ((ax, ay, az), (bx, by, bz), (cx, cy, cz))
    sqrt = math.sqrt
    # côtés et angles obtus
    obtuse = -1
    for k in range(3):
        p0, p1, p2 = pts[k], pts[(k + 1) % 3], pts[(k + 2) % 3]
        ux, uy, uz = p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]
        vx, vy, vz = p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]
        nu, nv = sqrt(ux * ux + uy * uy + uz * uz), sqrt(vx * vx + vy * vy + vz * vz)
        if nu < 1e-12 or nv < 1e-12 or (ux * vx + uy * vy + uz * vz) <= -0.5 * nu * nv:
            obtuse = k
            break
    grad = [[0.0, 0.0, 0.0], [0.0, 0.0, 0.0], [0.0, 0.0, 0.0]]
    if obtuse >= 0:
        k = obtuse
        length = 0.0
        legs = [0.0, 0.0, 0.0]
        for i in range(3):
            if i == k:
                continue
            dx, dy, dz = pts[i][0] - pts[k][0], pts[i][1] - pts[k][1], pts[i][2] - pts[k][2]
            n = sqrt(dx * dx + dy * dy + dz * dz)
            legs[i] = n
            length += n
            if n > 1e-12:
                for c, d in enumerate((dx, dy, dz)):
                    grad[i][c] += SIGMA * d / n
                    grad[k][c] -= SIGMA * d / n
        junction = pts[k]
    else:
        def apex(o, p1, p2):
            mx, my, mz = (p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2, (p1[2] + p2[2]) / 2
            ex, ey, ez = p2[0] - p1[0], p2[1] - p1[1], p2[2] - p1[2]
            e2 = ex * ex + ey * ey + ez * ez
            nx, ny, nz = mx - o[0], my - o[1], mz - o[2]
            f = (nx * ex + ny * ey + nz * ez) / e2
            nx, ny, nz = nx - f * ex, ny - f * ey, nz - f * ez
            nn = sqrt(nx * nx + ny * ny + nz * nz)
            h = _SQRT3_2 * sqrt(e2) / nn
            return (mx + h * nx, my + h * ny, mz + h * nz)
        a2, b2 = apex(pts[0], pts[1], pts[2]), apex(pts[1], pts[2], pts[0])
        dax, day, daz = a2[0] - ax, a2[1] - ay, a2[2] - az
        dbx, dby, dbz = b2[0] - bx, b2[1] - by, b2[2] - bz
        wx, wy, wz = bx - ax, by - ay, bz - az
        aa, ab, bb = dax * dax + day * day + daz * daz, dax * dbx + day * dby + daz * dbz, dbx * dbx + dby * dby + dbz * dbz
        r1, r2 = dax * wx + day * wy + daz * wz, -(dbx * wx + dby * wy + dbz * wz)
        det = aa * bb - ab * ab
        t = (r1 * bb + ab * r2) / det
        jx, jy, jz = ax + t * dax, ay + t * day, az + t * daz
        junction = (jx, jy, jz)
        length = sqrt(aa)
        legs = []
        for q in pts:
            legs.append(sqrt((q[0] - jx) ** 2 + (q[1] - jy) ** 2 + (q[2] - jz) ** 2))
        short = min(range(3), key=legs.__getitem__)
        others = [i for i in range(3) if i != short]
        for i in others:
            for c in range(3):
                grad[i][c] = SIGMA * (pts[i][c] - junction[c]) / legs[i]
        for c in range(3):
            grad[short][c] = -(grad[others[0]][c] + grad[others[1]][c])
    coulomb = 0.0
    k = (2 / 3) * ALPHA_S * HBAR_C
    eps2 = SMEAR_FM ** 2
    for i in range(3):
        for j in range(i + 1, 3):
            dx, dy, dz = pts[i][0] - pts[j][0], pts[i][1] - pts[j][1], pts[i][2] - pts[j][2]
            r2 = dx * dx + dy * dy + dz * dz + eps2
            root = sqrt(r2)
            coulomb -= k / root
            f = k / (r2 * root)
            for c, d in enumerate((dx, dy, dz)):
                grad[i][c] += f * d
                grad[j][c] -= f * d
    return SIGMA * length + coulomb, grad, junction, legs


def baryon_potential(x):
    """(V, ∇V (3×3), jonction, longueurs des jambes) d'un baryon : σ·L_Y − (2/3)α_s ħc Σ 1/√(r²+ε²)."""
    v, grad, junction, legs = _scalar_baryon(np.asarray(x, dtype=float).tolist())
    return v, np.array(grad), np.array(junction), legs


def meson_potential(x):
    """(V, ∇V (2×3), longueur) d'un méson : σ·r − (4/3)α_s ħc/√(r²+ε²)."""
    d = x[0] - x[1]
    r = float(np.linalg.norm(d))
    r2 = r * r + SMEAR_FM ** 2
    c = (4 / 3) * ALPHA_S * HBAR_C
    unit = d / r if r > 1e-12 else np.zeros(3)
    g = SIGMA * unit + c * d / r2 ** 1.5
    return SIGMA * r - c / math.sqrt(r2), np.array([g, -g]), r


def string_and_coulomb(kind, x):
    """(énergie de corde, énergie de couleur) d'un amas."""
    if kind == 'baryon':
        _, length, _ = fermat(x)
        v, _, _, _ = baryon_potential(x)
        return SIGMA * length, v - SIGMA * length
    d = float(np.linalg.norm(x[0] - x[1]))
    return SIGMA * d, -(4 / 3) * ALPHA_S * HBAR_C / math.sqrt(d * d + SMEAR_FM ** 2)


def baryon_potential_batch(xs):
    """V de N baryons à la fois (N,3,3) : mêmes formules que baryon_potential, sans boucle Python."""
    a, b, c = xs[:, 0], xs[:, 1], xs[:, 2]
    pts = [a, b, c]
    obtuse = np.zeros(len(xs), dtype=bool)
    obtuse_length = np.zeros(len(xs))
    for k in range(3):
        u, v = pts[(k + 1) % 3] - pts[k], pts[(k + 2) % 3] - pts[k]
        nu, nv = np.linalg.norm(u, axis=1), np.linalg.norm(v, axis=1)
        cos = np.sum(u * v, axis=1) / np.maximum(nu * nv, 1e-12)
        hit = (cos <= -0.5) & ~obtuse
        obtuse_length = np.where(hit, nu + nv, obtuse_length)
        obtuse |= hit
    j = (a + b + c) / 3
    for _ in range(60):
        d = np.stack([np.maximum(np.linalg.norm(p_ - j, axis=1), 1e-9) for p_ in pts], axis=1)
        w = 1 / d
        j = (w[:, 0:1] * a + w[:, 1:2] * b + w[:, 2:3] * c) / w.sum(axis=1, keepdims=True)
    fermat_length = sum(np.linalg.norm(p_ - j, axis=1) for p_ in pts)
    length = np.where(obtuse, obtuse_length, fermat_length)
    coulomb = np.zeros(len(xs))
    k = (2 / 3) * ALPHA_S * HBAR_C
    for i, jx in ((0, 1), (0, 2), (1, 2)):
        coulomb -= k / np.sqrt(np.sum((xs[:, i] - xs[:, jx]) ** 2, axis=1) + SMEAR_FM ** 2)
    return SIGMA * length + coulomb


_GROUND_CACHE = {}


# ================================================================== l'état fondamental
def ground_state(mass=MASS['u'], samples=20000, seed=3):
    """Le nucléon au repos : taille b minimisant ⟨T⟩ + ⟨V⟩ pour ψ ∝ exp(−(ρ² + λ²)/2b²) (coordonnées de Jacobi).

    ⟨T⟩ = (3/2)(ħc)²/(m b²) ; ⟨V⟩ est calculé sur des tirages communs de |ψ|². Renvoie (b, énergie interne E₀ en MeV,
    sans la constante), pour trois masses égales.
    """
    key = (round(mass, 3), samples, seed)
    if key in _GROUND_CACHE:
        return _GROUND_CACHE[key]
    rng = np.random.default_rng(seed)
    z = rng.normal(size=(samples, 6))
    kin = lambda b: 1.5 * HBAR_C ** 2 / (mass * b * b)

    def configuration(b):
        s = b / math.sqrt(2)
        rho, lam = z[:, :3] * s, z[:, 3:] * s
        return np.stack([rho / math.sqrt(2) + lam / math.sqrt(6), -rho / math.sqrt(2) + lam / math.sqrt(6),
                         -2 * lam / math.sqrt(6)], axis=1)

    def energy(b):
        return kin(b) + float(np.mean(baryon_potential_batch(configuration(b))))

    best = minimize_scalar(energy, bounds=(0.25, 1.4), method='bounded', options={'xatol': 1e-4})
    b = float(best.x)
    _GROUND_CACHE[key] = (b, 3 * mass + float(best.fun))
    return _GROUND_CACHE[key]


class Nucleon:
    """Un nucléon (proton uud ou neutron udd), et ce qu'un coup sur un quark en fait."""

    _runs = __import__('itertools').count(1)

    def __init__(self, hadron='proton'):
        if hadron not in ('proton', 'neutron'):
            raise ValueError('Le nucléon est un proton ou un neutron')
        self.hadron = hadron
        self.run = 0
        self.b, self.e_ground = ground_state()
        self.flavors = ('u', 'u', 'd') if hadron == 'proton' else ('u', 'd', 'd')
        self.mass_pdg = PROTON_MEV if hadron == 'proton' else NEUTRON_MEV
        # Σ r_i² = ρ² + λ² (centre de masse) : chaque quark est en moyenne à √⟨r²⟩ = b du centre.
        self.rms_radius_fm = self.b
        self.samples = []
        self.breaks = []
        self.max_energy_error = 0.0
        self.last = {}

    # ---------------------------------------------------------------- l'état initial
    def sample_state(self, seed):
        """Positions et impulsions tirées de la gaussienne fondamentale (Wigner exact, centre de masse fixe)."""
        rng = np.random.default_rng(seed)
        s_x, s_p = self.b / math.sqrt(2), HBAR_C / (math.sqrt(2) * self.b)
        rho, lam = rng.normal(0, s_x, 3), rng.normal(0, s_x, 3)
        prho, plam = rng.normal(0, s_p, 3), rng.normal(0, s_p, 3)
        a, b2 = 1 / math.sqrt(2), 1 / math.sqrt(6)
        x = np.array([a * rho + b2 * lam, -a * rho + b2 * lam, -2 * b2 * lam])
        p = np.array([a * prho + b2 * plam, -a * prho + b2 * plam, -2 * b2 * plam])
        return x, p

    # ---------------------------------------------------------------- la dynamique
    def calculate(self, struck=0, momentum_mev=0.0, direction=(1, 0, 0), seed=1):
        """Intègre un coup sur le quark `struck` (impulsion `momentum_mev` selon `direction`) jusqu'à T_END."""
        if not (0 <= struck < 3):
            raise ValueError('Le quark frappé est 0, 1 ou 2')
        if not math.isfinite(momentum_mev) or momentum_mev < 0 or momentum_mev > MAX_KICK_MEV:
            raise ValueError('Transfert de moment admis : 0 à %.0f GeV/c' % (MAX_KICK_MEV / 1000))
        d = np.asarray(direction, dtype=float)
        if d.shape != (3,) or np.linalg.norm(d) < 1e-9:
            d = np.array([1.0, 0, 0])
        d = d / np.linalg.norm(d)
        rng = np.random.default_rng(seed)
        x, p = self.sample_state(seed)
        quarks = [dict(flavor=f, anti=False, color=i, cluster=0) for i, f in enumerate(self.flavors)]
        masses = np.array([MASS[q['flavor']] for q in quarks])
        energy0 = float(np.sum(np.sqrt(np.sum(p * p, axis=1) + masses ** 2)))
        p_before = p.copy()
        p = p.copy()
        p[struck] += momentum_mev * d
        e1 = float(np.sum(np.sqrt(np.sum(p * p, axis=1) + masses ** 2)))
        nu = e1 - energy0
        q3 = momentum_mev
        # La cinématique du photon virtuel : transfert q, énergie ν, Q² = q² − ν², masse invariante W du système final.
        # (Pas de x de Bjorken : un modèle de quarks constituants, avec son puits de potentiel de −1,7 GeV, ne respecte
        # pas la règle de somme des x ; l'afficher serait afficher un nombre faux.)
        self.q = dict(momentum_mev=q3, nu_mev=nu, q2=q3 * q3 - nu * nu,
                      w_mev=math.sqrt(max(0.0, (self.mass_pdg + nu) ** 2 - q3 * q3)))
        self.struck, self.direction = struck, d
        clusters = [dict(kind='baryon', ids=[0, 1, 2], reference=None)]
        # Le nucléon d'avant le coup est, par définition, le hadron mesuré : son énergie interne est l'origine de son
        # excitation (sans cela, la dispersion de point zéro du tirage passerait pour de l'énergie d'excitation).
        clusters[0]['reference'] = self._cluster_energy(clusters[0], x, p_before, quarks)
        state = dict(x=x, p=p, quarks=quarks, clusters=clusters, time=0.0)
        self.samples, self.breaks = [], []
        self.max_energy_error = 0.0
        state['excitation0'] = nu
        self._reference_masses = {}
        self._run(state, rng)
        self.run = next(Nucleon._runs)
        return self.samples

    def evolve(self, quarks, clusters, x, p, seed=1, breaks=True):
        """Intègre un état quelconque — plusieurs amas, baryons et mésons (une collision de deux hadrons) — jusqu'à T_END.

        `quarks` : saveur, anti, couleur ; `clusters` : genre et indices ; `breaks` : les cordes peuvent-elles se rompre."""
        rng = np.random.default_rng(seed)
        state = dict(x=np.asarray(x, float).copy(), p=np.asarray(p, float).copy(), quarks=[dict(q) for q in quarks],
                     clusters=[dict(c, ids=list(c['ids']), reference=c.get('reference')) for c in clusters], time=0.0,
                     excitation0=0.0)
        self.samples, self.breaks = [], []
        self.max_energy_error = 0.0
        self._reference_masses = {}
        self._allow_breaks = breaks
        try:
            self._run(state, rng)
        finally:
            self._allow_breaks = True
        self.run = next(Nucleon._runs)
        return self.samples

    def momentum_for_mass(self, w_mev, struck=0, direction=(1, 0, 0), seed=1):
        """L'impulsion à donner au quark `struck` pour que le système final ait la masse invariante `w_mev` (modèle de cordes).

        La cinématique de l'électron fixe (ν, q) donc W ; le modèle de quarks constituants, lié, n'y retombe pas exactement
        avec q seul. On cherche (par dichotomie) l'impulsion du quark qui redonne le bon W, pour hadroniser la bonne masse."""
        d = np.asarray(direction, float)
        d = d / np.linalg.norm(d)
        x, p = self.sample_state(seed)
        masses = np.array([MASS[f] for f in self.flavors])
        e0 = float(np.sum(np.sqrt(np.sum(p * p, axis=1) + masses ** 2)))

        def invariant(q):
            pk = p.copy()
            pk[struck] += q * d
            nu = float(np.sum(np.sqrt(np.sum(pk * pk, axis=1) + masses ** 2))) - e0
            return math.sqrt(max(0.0, (self.mass_pdg + nu) ** 2 - q * q))
        lo, hi = 0.0, float(MAX_KICK_MEV)
        if invariant(hi) < w_mev:
            return hi
        for _ in range(60):
            mid = (lo + hi) / 2
            # W(q) croît avec q pour un quark frappé dans le sens du coup ; sinon on prend la branche croissante
            if invariant(mid) < w_mev:
                lo = mid
            else:
                hi = mid
        return (lo + hi) / 2

    def history(self, seed, duration, frames=40):
        """Le nucléon avant le coup : la même dynamique, remontée dans le temps (impulsions inversées, sans coup ni rupture).

        Renvoie des échantillons de temps négatifs, du plus ancien à t = 0⁻ ; ils se raccordent à `calculate` (même graine)."""
        x, p = self.sample_state(seed)
        quarks = [dict(flavor=f, anti=False, color=i, cluster=0) for i, f in enumerate(self.flavors)]
        clusters = [dict(kind='baryon', ids=[0, 1, 2], reference=None)]
        clusters[0]['reference'] = self._cluster_energy(clusters[0], x, p, quarks)
        state = dict(x=x, p=-p, quarks=quarks, clusters=clusters, time=0.0, excitation0=0.0)
        self._reference_masses = {}
        rhs, _ = self._rhs_factory(state, [(0.0, 0.0)])
        n = 3
        y0 = np.concatenate([x.ravel(), (-p).ravel(), [0.0]])
        times = np.linspace(0.0, duration, frames + 1)[1:]
        sol = solve_ivp(rhs, (0.0, duration), y0, method='DOP853', rtol=1e-7, atol=1e-6, t_eval=times, max_step=0.2)
        out = []
        for t, y in zip(sol.t, sol.y.T):
            snap = dict(state, x=y[:3 * n].reshape(n, 3), p=-y[3 * n:6 * n].reshape(n, 3), time=-float(t))
            out.append(self._record(snap, 0.0))
        return out[::-1]

    # ---------------------------------------------------------------- les amas
    def _cluster_energy(self, cl, x, p, quarks):
        ids = cl['ids']
        m = np.array([MASS[quarks[i]['flavor']] for i in ids])
        kin = float(np.sum(np.sqrt(np.sum(p[ids] ** 2, axis=1) + m ** 2)))
        pot = baryon_potential(x[ids])[0] if cl['kind'] == 'baryon' else meson_potential(x[ids])[0]
        return kin + pot

    def _ground_energy(self, kind, flavors):
        """L'énergie interne de l'état fondamental du modèle, pour comparer à l'énergie d'un amas (excitation)."""
        key = (kind, tuple(sorted(flavors)))
        if key not in self._reference_masses:
            if kind == 'baryon':
                m = float(np.mean([MASS[f] for f in flavors]))
                b, e = ground_state(m, samples=6000)
            else:
                m = float(np.mean([MASS[f] for f in flavors]))
                e = self._meson_ground(m)
            self._reference_masses[key] = e
        return self._reference_masses[key]

    @staticmethod
    def _meson_ground(m):
        rng = np.random.default_rng(5)
        z = rng.normal(size=(4000, 3))

        def energy(b):
            # ψ ∝ exp(−r²/2b²) pour la coordonnée relative, masse réduite m/2
            r = np.linalg.norm(z * b / math.sqrt(2), axis=1)
            v = SIGMA * r - (4 / 3) * ALPHA_S * HBAR_C / np.sqrt(r * r + SMEAR_FM ** 2)
            return 1.5 * HBAR_C ** 2 / (m * b * b) + float(np.mean(v))

        best = minimize_scalar(energy, bounds=(0.15, 1.5), method='bounded', options={'xatol': 1e-3})
        return 2 * m + float(best.fun)

    def _lightest_split(self, cl, quarks, available_mev):
        """Les saveurs f pour lesquelles une rupture est énergétiquement permise : {f: masse de la paire de hadrons}."""
        allowed = {}
        ids = cl['ids']
        for f in 'uds':
            if cl['kind'] == 'baryon':
                # un quark d'une jambe (saveur g) part avec un antiquark f̄ ; le baryon garde f à sa place
                best = None
                for k, i in enumerate(ids):
                    g = quarks[i]['flavor']
                    rest = [quarks[j]['flavor'] for j in ids if j != i]
                    mass = baryon_name(rest + [f])[1] + meson_name(g, f)[1]
                    best = mass if best is None else min(best, mass)
                total = best
            else:
                a, b = quarks[ids[0]]['flavor'], quarks[ids[1]]['flavor']
                total = meson_name(a, f)[1] + meson_name(f, b)[1]
            if available_mev >= total:
                allowed[f] = total
        return allowed

    def _hadron_mass(self, cl, quarks):
        fl = [quarks[i]['flavor'] for i in cl['ids']]
        return baryon_name(fl)[1] if cl['kind'] == 'baryon' else meson_name(fl[0], fl[1])[1]

    def _cluster_invariant(self, cl, x, p, quarks):
        """(masse invariante physique, excitation, impulsion) d'un amas.

        L'énergie interne du modèle est la masse invariante √(H² − P²) de l'amas (H : son énergie, P : son impulsion) ;
        son excitation est cette masse moins celle de son origine — le nucléon avant le coup, ou l'état fondamental du
        modèle pour un hadron né de la rupture. La masse physique est la masse mesurée du hadron plus cette excitation :
        un amas intact vaut exactement sa masse mesurée, quel que soit son mouvement d'ensemble.
        """
        ids = cl['ids']
        fl = [quarks[i]['flavor'] for i in ids]
        h = self._cluster_energy(cl, x, p, quarks)
        total_p = np.sum(p[ids], axis=0)
        pdg = self._hadron_mass(cl, quarks)
        reference = cl.get('reference')
        if reference is not None:
            # Le nucléon d'avant le coup : sa masse physique (938 MeV) et l'énergie reçue (H − H_réf) donnent exactement la
            # masse invariante W du système final, √((M + ν)² − q²) — la correction de boost est faite avec la vraie masse,
            # non avec les 2,7 GeV d'énergie interne du modèle.
            # (la masse de départ est celle du nucléon cible, même si un W a changé la saveur d'un quark : uud → uuu)
            e_phys = self.mass_pdg + (h - reference)
            invariant = math.sqrt(max(e_phys * e_phys - float(np.dot(total_p, total_p)), 0.0))
            return invariant, max(0.0, invariant - pdg), total_p
        internal = math.sqrt(max(h * h - float(np.dot(total_p, total_p)), 0.0))
        excitation = max(0.0, internal - self._ground_energy(cl['kind'], fl))
        return pdg + excitation, excitation, total_p

    # ---------------------------------------------------------------- l'intégration
    def _rhs_factory(self, state, flavors_weight):
        quarks, clusters = state['quarks'], state['clusters']
        masses_all = np.array([MASS[q['flavor']] for q in quarks])
        layout = [(cl['kind'], list(cl['ids'])) for cl in clusters]
        n = len(quarks)
        legs_weight = flavors_weight

        def forces(x):
            f = np.zeros((n, 3))
            hazard = 0.0
            for (kind, ids), (weight, dmin) in zip(layout, legs_weight):
                if kind == 'baryon':
                    v, g, j, legs = baryon_potential(x[ids])
                    for k, i in enumerate(ids):
                        f[i] -= g[k]
                    if weight > 0:
                        for leg in legs:
                            hazard += BREAK_RATE * weight * max(0.0, leg - dmin)
                else:
                    v, g, length = meson_potential(x[ids])
                    f[ids[0]] -= g[0]
                    f[ids[1]] -= g[1]
                    if weight > 0:
                        hazard += BREAK_RATE * weight * max(0.0, length - dmin)
            return f, hazard

        def rhs(_t, y):
            x = y[:3 * n].reshape(n, 3)
            p = y[3 * n:6 * n].reshape(n, 3)
            e = np.sqrt(np.sum(p * p, axis=1) + masses_all ** 2)
            f, hazard = forces(x)
            return np.concatenate([(p / e[:, None]).ravel(), f.ravel(), [hazard]])

        return rhs, forces

    def _flavor_gates(self, state):
        """Pour chaque amas : (poids total des saveurs permises, séparation minimale d'une paire)."""
        gates, allowed_list = [], []
        quarks, clusters = state['quarks'], state['clusters']
        for cl in clusters:
            invariant, excitation, _ = self._cluster_invariant(cl, state['x'], state['p'], quarks)
            allowed = self._lightest_split(cl, quarks, invariant)
            weight = sum(FLAVOR_WEIGHT[f] for f in allowed)
            dmin = min((2 * MASS[f] / SIGMA for f in allowed), default=0.0)
            gates.append((weight, dmin))
            allowed_list.append(allowed)
        return gates, allowed_list

    def _record(self, state, hazard_total):
        x, p, quarks, clusters = state['x'], state['p'], state['quarks'], state['clusters']
        n = len(quarks)
        masses = np.array([MASS[q['flavor']] for q in quarks])
        e = np.sqrt(np.sum(p * p, axis=1) + masses ** 2)
        kinetic, string, coulomb = float(np.sum(e)), 0.0, 0.0
        out = []
        for ci, cl in enumerate(clusters):
            ids = cl['ids']
            s, c = string_and_coulomb(cl['kind'], x[ids])
            string += s
            coulomb += c
            junction = baryon_potential(x[ids])[2] if cl['kind'] == 'baryon' else (x[ids[0]] + x[ids[1]]) / 2
            fl = [quarks[i]['flavor'] for i in ids]
            name, pdg = baryon_name(fl) if cl['kind'] == 'baryon' else meson_name(fl[0], fl[1])
            invariant, excitation, total_p = self._cluster_invariant(cl, x, p, quarks)
            out.append(dict(id=ci, kind=cl['kind'], ids=list(ids), junction=junction.copy(), name=name, mass=pdg,
                            excitation=excitation, string=s, momentum=float(np.linalg.norm(total_p)),
                            p_vec=np.asarray(total_p, float).copy(), invariant=float(invariant)))
        total = kinetic + string + coulomb
        v = p / e[:, None]
        return dict(time=state['time'], x=x.copy(), v=v.copy(), quarks=[dict(q, cluster=self._cluster_of(clusters, i))
                                                                       for i, q in enumerate(quarks)],
                    clusters=out, kinetic=kinetic, string=string, coulomb=coulomb, total=total,
                    break_probability=1 - math.exp(-hazard_total))

    @staticmethod
    def _cluster_of(clusters, i):
        for ci, cl in enumerate(clusters):
            if i in cl['ids']:
                return ci
        return -1

    def _run(self, state, rng):
        """Intègre par segments : entre deux ruptures, DOP853 ; une rupture arrive quand le hasard accumulé dépasse un tirage."""
        hazard_total = 0.0
        t_next_frame = 0.0
        dt_frame = T_END / FRAMES
        reference = None
        while True:
            n = len(state['quarks'])
            gates, allowed_list = self._flavor_gates(state)
            rhs, _ = self._rhs_factory(state, gates)
            allowed = getattr(self, '_allow_breaks', True)
            threshold = float(rng.exponential(1.0)) if allowed and len(self.breaks) < MAX_BREAKS and any(g[0] > 0 for g in gates) else math.inf
            y0 = np.concatenate([state['x'].ravel(), state['p'].ravel(), [0.0]])
            h_index = 6 * n

            def crossing(_t, y):
                return y[h_index] - threshold
            crossing.terminal = True
            crossing.direction = 1
            frame_times = np.arange(t_next_frame, T_END + 1e-9, dt_frame)
            frame_times = frame_times[(frame_times >= state['time']) & (frame_times <= T_END)]
            if len(frame_times) == 0:
                frame_times = np.array([T_END])
            sol = solve_ivp(rhs, (state['time'], T_END), y0, method='DOP853', rtol=1e-7, atol=1e-6, t_eval=frame_times,
                            events=crossing if math.isfinite(threshold) else None, max_step=0.2)
            if not sol.success:
                raise RuntimeError(sol.message)
            hz0 = hazard_total
            # un arrêt sur rupture avant le premier instant demandé laisse sol.y vide (liste) : on le remet en tableau
            for t, y in zip(sol.t, np.asarray(sol.y, float).reshape(len(y0), -1).T):
                snap = dict(state, x=y[:3 * n].reshape(n, 3), p=y[3 * n:6 * n].reshape(n, 3), time=float(t))
                rec = self._record(snap, hz0 + y[h_index])
                self.samples.append(rec)
                if reference is None:
                    reference = rec['total']
                self.max_energy_error = max(self.max_energy_error, abs(rec['total'] - reference))
                t_next_frame = float(t) + dt_frame
            if sol.t_events is not None and len(sol.t_events) and len(sol.t_events[0]):
                te = float(sol.t_events[0][0])
                y = sol.y_events[0][0]
                state = dict(state, x=y[:3 * n].reshape(n, 3).copy(), p=y[3 * n:6 * n].reshape(n, 3).copy(), time=te)
                hazard_total = hz0 + float(y[h_index])
                state, event = self._break(state, rng, gates, allowed_list)
                if event is not None:
                    self.breaks.append(event)
                    # le nouvel instant est mis à l'échantillon suivant ; l'énergie de référence reste celle du départ
                    rec = self._record(state, hazard_total)
                    self.samples.append(rec)
                    self.max_energy_error = max(self.max_energy_error, abs(rec['total'] - reference))
                continue
            break
        self.max_energy_error = float(self.max_energy_error)

    # ---------------------------------------------------------------- la rupture
    def _break(self, state, rng, gates, allowed_list):
        """Choisit l'amas et la jambe (au prorata du taux), la saveur et le point ; crée la paire à l'énergie conservée."""
        x, p, quarks, clusters = state['x'], state['p'], state['quarks'], state['clusters']
        legs = []  # (amas, indice de jambe, longueur, point de départ, direction unitaire)
        for ci, cl in enumerate(clusters):
            weight, dmin = gates[ci]
            if weight <= 0:
                continue
            ids = cl['ids']
            if cl['kind'] == 'baryon':
                j, _, lens = fermat(x[ids])
                for k, i in enumerate(ids):
                    leg = lens[k]
                    if leg > dmin:
                        u = (x[i] - j) / leg
                        legs.append((ci, k, leg, j, u, BREAK_RATE * weight * (leg - dmin)))
            else:
                r = float(np.linalg.norm(x[ids[1]] - x[ids[0]]))
                if r > dmin:
                    u = (x[ids[1]] - x[ids[0]]) / r
                    legs.append((ci, 0, r, x[ids[0]].copy(), u, BREAK_RATE * weight * (r - dmin)))
        if not legs:
            return state, None
        w = np.array([lg[5] for lg in legs])
        ci, k, length, start, u, _ = legs[int(rng.choice(len(legs), p=w / w.sum()))]
        allowed = allowed_list[ci]
        flavors = list(allowed)
        fw = np.array([FLAVOR_WEIGHT[f] for f in flavors])
        f = flavors[int(rng.choice(len(flavors), p=fw / fw.sum()))]
        place = float(rng.uniform())
        cl = clusters[ci]
        before = self._total_energy(state)
        built = None

        def build(d):
            return self._with_pair(state, ci, k, length, start, u, f, d, place)

        def mismatch(d):
            return self._total_energy(build(d)[0]) - before

        lo, hi = 0.12, min(length, 3.0)
        try:
            if mismatch(lo) * mismatch(hi) < 0:
                d = brentq(mismatch, lo, hi, xtol=1e-9)
                built = build(d)
            else:
                return state, None
        except ValueError:
            return state, None
        new_state, hadrons, position = built
        # l'impulsion transverse de la paire créée par effet tunnel (Schwinger), dP/dp_T² ∝ exp(−π p_T²/σ) : son azimut, uniforme
        # autour de la corde, et le tirage uniforme qui fixera p_T (`collision_events.split`, où l'on connaît la borne p*)
        side = np.cross(u, [1.0, 0.0, 0.0] if abs(u[0]) < .9 else [0.0, 1.0, 0.0])
        side /= np.linalg.norm(side)
        phi = float(rng.uniform(0, 2 * math.pi))
        event = dict(time=state['time'], position=position, flavor=f, separation=d,
                     released=SIGMA * d, hadrons=hadrons, cluster=ci, axis=np.asarray(u, float).copy(),
                     pt_direction=math.cos(phi) * side + math.sin(phi) * np.cross(u, side), pt_draw=float(rng.uniform()))
        return new_state, event

    @staticmethod
    def _total_energy(state):
        x, p, quarks, clusters = state['x'], state['p'], state['quarks'], state['clusters']
        masses = np.array([MASS[q['flavor']] for q in quarks])
        kin = float(np.sum(np.sqrt(np.sum(p * p, axis=1) + masses ** 2)))
        pot = 0.0
        for cl in clusters:
            ids = cl['ids']
            pot += baryon_potential(x[ids])[0] if cl['kind'] == 'baryon' else meson_potential(x[ids])[0]
        return kin + pot

    def _with_pair(self, state, ci, k, length, start, u, f, d, place):
        """L'état après la création d'une paire f f̄ au point `place` de la jambe, séparée de d (au repos)."""
        x, p = state['x'], state['p']
        quarks = [dict(q) for q in state['quarks']]
        clusters = [dict(cl, ids=list(cl['ids'])) for cl in state['clusters']]
        cl = clusters[ci]
        cl['reference'] = None            # les hadrons nés de la rupture : leur origine est leur état fondamental
        s = d / 2 + place * max(length - d, 0.0)
        mid = start + u * s
        n = len(quarks)
        if cl['kind'] == 'baryon':
            struck = cl['ids'][k]
            color = quarks[struck]['color']
            new_q = dict(flavor=f, anti=False, color=color, cluster=ci)       # reste dans le baryon, côté jonction
            new_a = dict(flavor=f, anti=True, color=color, cluster=-1)        # part avec le quark frappé
            quarks.extend([new_q, new_a])
            iq, ia = n, n + 1
            x2 = np.vstack([x, mid - u * d / 2, mid + u * d / 2])
            p2 = np.vstack([p, np.zeros(3), np.zeros(3)])
            others = [i for i in cl['ids'] if i != struck]
            cl['ids'] = others + [iq]
            meson = dict(kind='meson', ids=[struck, ia])
            clusters.append(meson)
            baryon_flavors = [quarks[i]['flavor'] for i in cl['ids']]
            hadrons = [baryon_name(baryon_flavors)[0], meson_name(quarks[struck]['flavor'], f)[0]]
        else:
            a, b = cl['ids']
            color = quarks[a]['color']
            new_a = dict(flavor=f, anti=True, color=color, cluster=ci)        # côté du quark a
            new_q = dict(flavor=f, anti=False, color=quarks[b]['color'], cluster=-1)  # côté de l'antiquark b
            quarks.extend([new_a, new_q])
            ia, iq = n, n + 1
            x2 = np.vstack([x, mid - u * d / 2, mid + u * d / 2])
            p2 = np.vstack([p, np.zeros(3), np.zeros(3)])
            cl['ids'] = [a, ia]
            clusters.append(dict(kind='meson', ids=[iq, b]))
            hadrons = [meson_name(quarks[a]['flavor'], f)[0], meson_name(f, quarks[b]['flavor'])[0]]
        new_state = dict(state, x=x2, p=p2, quarks=quarks, clusters=clusters)
        return new_state, hadrons, mid

    # ---------------------------------------------------------------- les résumés
    def summary(self):
        return dict(energy_error_mev=self.max_energy_error, breaks=len(self.breaks))


def charge_total(quarks):
    return sum(charge_of(q['flavor'], q['anti']) for q in quarks)
