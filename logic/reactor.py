"""Le réacteur à fusion : un tokamak aux dimensions d'ITER, et deux noyaux qui s'y rencontrent et fusionnent.

Rien n'est scénarisé, tout est calculé :
- le champ magnétique d'ITER : surfaces de flux elliptiques (allongement 1,7), profil de sécurité
  q = 1 au centre et 3 au bord, champ toroïdal B0·R0/R ; divergence nulle par construction ;
- le mouvement des ions : la force de Lorentz q·v×B, intégrée par l'algorithme de Boris (celui des codes
  de plasma : dans un champ magnétique, il conserve exactement l'énergie) ;
- la paire qui fusionne : énergie relative tirée selon σ(E)·E·e^(−E/kT) (le pic de Gamow), avec la section
  efficace mesurée (Bosch & Hale 1992) ; puis on remonte le temps depuis le point de rencontre (le
  mouvement dans B est réversible : il suffit d'inverser la vitesse et la charge) — deux vraies
  trajectoires qui convergent ;
- le choc : Coulomb + force nucléaire (potentiel d'Akyüz-Winther), trajectoire intégrée, effet tunnel WKB ;
- la suite : cinématique à deux corps (masses mesurées, énergie et quantité de mouvement conservées) ;
  la particule chargée reste piégée par le champ (Boris), le neutron file droit jusqu'à la première paroi.
"""
import math
from functools import lru_cache

import numpy as np
import scipy.constants as sc
from scipy.integrate import solve_ivp

import fusion
import nuclear_data

U_KG = sc.physical_constants["atomic mass constant"][0]
MEV_PER_U = fusion.MEV_PER_U
ELECTRON_MEV = sc.physical_constants["electron mass energy equivalent in MeV"][0]
J_PER_KEV = 1e3 * sc.e
BARN_M2 = 1e-28
S_PER_FM_C = 1e-15 / sc.c                     # 1 fm/c en secondes
MU_N = sc.physical_constants["nuclear magneton"][0]

# Rayons de charge mesurés (Angeli & Marinova 2013), en fm. Le neutron, sans charge : rayon du proton.
CHARGE_RADIUS_FM = {(1, 1): 0.8409, (0, 1): 0.8409, (1, 2): 2.1421, (1, 3): 1.7591, (2, 3): 1.9661,
                    (2, 4): 1.6755}
NUCLEON_RADIUS_FM = 0.8409                    # rayon de charge du proton (CODATA 2018)

# Section efficace mesurée : paramétrisation de Bosch & Hale, Nucl. Fusion 32 (1992) 611.
# σ(E) = S(E) / (E·exp(B_G/√E)), E en keV (centre de masse), σ en mb.
BOSCH_HALE = {
    "T(d,n)4He": (34.3827, (6.927e4, 7.454e8, 2.050e6, 5.2002e4, 0.0), (6.38e1, -9.95e-1, 6.981e-5, 1.728e-4)),
    "D(d,p)T": (31.3970, (5.5576e4, 2.1054e2, -3.2638e-2, 1.4987e-6, 1.8181e-10), (0.0, 0.0, 0.0, 0.0)),
    "D(d,n)3He": (31.3970, (5.3701e4, 3.3027e2, -1.2706e-1, 2.9327e-5, -2.5151e-9), (0.0, 0.0, 0.0, 0.0)),
    "3He(d,p)4He": (68.7508, (5.7501e6, 2.5226e3, 4.5566e1, 0.0, 0.0), (-3.1995e-3, -8.5530e-6, 5.9014e-8, 0.0)),
}

# Les combustibles des réacteurs. `resonance` : l'état du noyau composé par lequel passe la fusion
# (⁵He 3/2⁺ pour D-T, son miroir ⁵Li 3/2⁺ pour D-³He) ; il faut des spins parallèles (S = 3/2).
FUELS = [
    {"name": "D + T", "a": (1, 2), "b": (1, 3), "compound": (2, 5), "resonance": "3/2+",
     "branches": [("T(d,n)4He", [(2, 4), (0, 1)])]},
    {"name": "D + D", "a": (1, 2), "b": (1, 2), "compound": (2, 4), "resonance": "",
     "branches": [("D(d,p)T", [(1, 3), (1, 1)]), ("D(d,n)3He", [(2, 3), (0, 1)])]},
    {"name": "D + ³He", "a": (1, 2), "b": (2, 3), "compound": (3, 5), "resonance": "3/2+",
     "branches": [("3He(d,p)4He", [(2, 4), (1, 1)])]},
]
# Largeur de la résonance ³/₂⁺ de l'hélium 5 à 16,84 MeV (TUNL, A = 5) : durée de vie ħ/Γ.
HE5_WIDTH_MEV = 0.076
HBAR_MEV_S = sc.hbar / (1e6 * sc.e)


def fusion_spin(z, a):
    """Spin mesuré (en unités de ħ) : 1 pour le deutéron, 1/2 pour le triton, 0 pour l'hélium 4."""
    text = nuclear_data.spin_parity(z, a).strip("()+-")
    try:
        return float(text.split("/")[0]) / float(text.split("/")[1]) if "/" in text else float(text or 0)
    except ValueError:
        return 0.0


def cross_section_b(reaction, e_kev):
    """Section efficace mesurée (barns) à l'énergie e_kev (centre de masse)."""
    bg, a, b = BOSCH_HALE[reaction]
    e = np.maximum(np.asarray(e_kev, dtype=float), 1e-3)
    s = (a[0] + e * (a[1] + e * (a[2] + e * (a[3] + e * a[4])))) / (1 + e * (b[0] + e * (b[1] + e * (b[2] + e * b[3]))))
    return s / (e * np.exp(bg / np.sqrt(e))) * 1e-3


_trapezoid = getattr(np, "trapezoid", None) or np.trapz


def _energy_grid(kt_kev):
    return np.geomspace(0.05, min(4000.0, max(60 * kt_kev, 200.0)), 6000)


def reactivity(fuel, kt_kev):
    """⟨σv⟩ (m³/s) d'un plasma maxwellien à la température kT : ∫ σ(E)·v·f(E) dE."""
    mu = reduced_mass_kg(fuel["a"], fuel["b"])
    grid = _energy_grid(kt_kev)
    sigma = sum(cross_section_b(name, grid) for name, _ in fuel["branches"]) * BARN_M2
    weight = sigma * grid * np.exp(-grid / kt_kev)
    integral = _trapezoid(weight, grid) * J_PER_KEV ** 2
    return math.sqrt(8 / (math.pi * mu)) * (kt_kev * J_PER_KEV) ** -1.5 * integral


def gamow_peak_kev(fuel, kt_kev):
    grid = _energy_grid(kt_kev)
    sigma = sum(cross_section_b(name, grid) for name, _ in fuel["branches"])
    return float(grid[int(np.argmax(sigma * grid * np.exp(-grid / kt_kev)))])


def nuclear_mass_mev(z, a):
    """Masse du noyau seul (MeV/c²) : masse atomique mesurée moins ses électrons."""
    mass, _ = fusion.mass_u(z, a)
    return mass * MEV_PER_U - z * ELECTRON_MEV


def reduced_mass_kg(a, b):
    ma = nuclear_mass_mev(*a)
    mb = nuclear_mass_mev(*b)
    return ma * mb / (ma + mb) / MEV_PER_U * U_KG


def charge_radius(z, a):
    return CHARGE_RADIUS_FM.get((z, a), 0.94 * a ** (1 / 3) + 0.4)


def sphere_radius(z, a):
    """Rayon de la sphère uniforme qui a le même rayon de charge quadratique moyen : √(5/3)·r_ch."""
    return math.sqrt(5 / 3) * charge_radius(z, a)


def deuteron_separation_fm():
    """Séparation proton-neutron la plus probable du deutéron : maximum de u(r) = e^(−αr) − e^(−βr)
    (fonction d'onde de Hulthén), α = √(2μB)/ħ avec l'énergie de liaison mesurée, β = 5,95·α."""
    m_p = sc.physical_constants["proton mass energy equivalent in MeV"][0]
    m_n = sc.physical_constants["neutron mass energy equivalent in MeV"][0]
    mu = m_p * m_n / (m_p + m_n)
    binding = nuclear_data.measured_binding(1, 2)[2]
    alpha = math.sqrt(2 * mu * binding) / fusion.HBAR_C
    beta = 5.95 * alpha
    return math.log(beta / alpha) / (beta - alpha)


def nucleon_layout(z, a):
    """Les nucléons dans le noyau : (x, y, z en fm, 1 proton / 0 neutron)."""
    if a == 1:
        return [(0.0, 0.0, 0.0, float(z))]
    if (z, a) == (1, 2):
        d = deuteron_separation_fm() / 2
        return [(d, 0.0, 0.0, 1.0), (-d, 0.0, 0.0, 0.0)]
    # Les autres : à leur rayon quadratique moyen (rayon de charge moins la taille du proton),
    # sans dépasser la surface du noyau.
    r_point = math.sqrt(max(charge_radius(z, a) ** 2 - NUCLEON_RADIUS_FM ** 2, 0.25))
    d = min(r_point, sphere_radius(z, a) - 1.15 * NUCLEON_RADIUS_FM)
    if a == 3:
        points = [(math.cos(k * 2 * math.pi / 3), math.sin(k * 2 * math.pi / 3), 0.0) for k in range(3)]
    elif a == 4:
        s = 1 / math.sqrt(3)
        points = [(s, s, s), (s, -s, -s), (-s, s, -s), (-s, -s, s)]
    elif a == 5 and z == 2:
        # ⁵He = une particule α et un neutron sur l'orbite p₃/₂, plus loin.
        s = 1 / math.sqrt(3)
        core = [(s * 0.8, s * 0.8, s * 0.8), (s * 0.8, -s * 0.8, -s * 0.8), (-s * 0.8, s * 0.8, -s * 0.8),
                (-s * 0.8, -s * 0.8, s * 0.8)]
        out = [(x * d, y * d, w * d, t) for (x, y, w), t in zip(core, (1.0, 0.0, 1.0, 0.0))]
        return out + [(1.55 * d, -0.4 * d, 0.0, 0.0)]
    else:
        golden = math.pi * (3 - math.sqrt(5))
        points = []
        for k in range(a):
            y = 1 - 2 * (k + 0.5) / a
            r = math.sqrt(1 - y * y)
            points.append((r * math.cos(golden * k), y, r * math.sin(golden * k)))
    kinds = [1.0 if k % 2 == 0 and k // 2 < z else 0.0 for k in range(a)]
    protons = sum(kinds)
    for k in range(a):
        if protons < z and kinds[k] == 0.0:
            kinds[k] = 1.0
            protons += 1
    return [(x * d, y * d, w * d, t) for (x, y, w), t in zip(points, kinds)]


# ======================================================================
# Le tokamak
# ======================================================================
class Tokamak:
    """Le tokamak ITER : R0 = 6,2 m, petit rayon 2,0 m, allongement 1,7, B0 = 5,3 T, q = 1 au centre et ≈ 3
    au bord. Champ analytique : surfaces de flux elliptiques r² = (R − R0)² + (Z/κ)², flux poloïdal ψ(r)
    avec ψ'(r) = B0·κ·r/q(r) — le champ B = ∇ψ×∇φ + B0R0/R·φ̂ est sans divergence par construction, et une
    ligne de champ fait q tours toroïdaux par tour poloïdal (à la correction torique près, mesurée)."""

    R0 = 6.2            # grand rayon (m)
    A = 2.0             # petit rayon (m)
    B0 = 5.3            # champ toroïdal au grand rayon (T)
    KAPPA = 1.7         # allongement du plasma
    Q_AXIS = 1.0        # facteur de sécurité au centre (limité à ~1 par les dents de scie)
    Q_EDGE = 3.0        # au bord (ITER : q95 = 3)
    WALL = 1.15         # la première paroi, en petits rayons du plasma
    DENSITY = 1.0e20    # densité d'ions (m⁻³), ITER

    def q(self, rho):
        return self.Q_AXIS + (self.Q_EDGE - self.Q_AXIS) * rho * rho

    def minor(self, r_major, z):
        return math.sqrt((r_major - self.R0) ** 2 + (z / self.KAPPA) ** 2)

    def field_at(self, x, y, z):
        """B (T) en un point (m), en nombres simples (pour l'intégrateur)."""
        r_major = math.sqrt(x * x + y * y)
        dx = r_major - self.R0
        rho2 = (dx * dx + (z / self.KAPPA) ** 2) / (self.A * self.A)
        q = self.Q_AXIS + (self.Q_EDGE - self.Q_AXIS) * rho2
        # B_R = −(1/R)·∂ψ/∂Z, B_Z = (1/R)·∂ψ/∂R, avec ψ'(r) = B0κr/q : le r se simplifie.
        b_r = -self.B0 * z / (self.KAPPA * q * r_major)
        b_z = self.B0 * self.KAPPA * dx / (q * r_major)
        b_phi = self.B0 * self.R0 / r_major
        cos, sin = x / r_major, y / r_major
        return b_r * cos - b_phi * sin, b_r * sin + b_phi * cos, b_z

    def field(self, p):
        """B (T) aux points p (N, 3)."""
        return np.array([self.field_at(*row) for row in np.atleast_2d(p)])

    def rho(self, p):
        p = np.atleast_2d(p)
        r_major = np.hypot(p[:, 0], p[:, 1])
        return np.sqrt((r_major - self.R0) ** 2 + (p[:, 2] / self.KAPPA) ** 2) / self.A

    def point(self, rho, theta, phi):
        """Le point (x, y, z) de la surface de flux ρ, à l'angle poloïdal θ et toroïdal φ."""
        r_major = self.R0 + rho * self.A * math.cos(theta)
        return np.array([r_major * math.cos(phi), r_major * math.sin(phi), self.KAPPA * rho * self.A * math.sin(theta)])

    def contour(self, rho, n=160):
        """Une surface de flux dans le plan (R, Z) : le bord du plasma (ρ = 1) ou la paroi."""
        out = []
        for k in range(n + 1):
            p = self.point(rho, 2 * math.pi * k / n, 0.0)
            out += [float(p[0]), float(p[2])]
        return out

    def _line_rhs(self, _phi, s):
        r_major, z = s
        bx, by, bz = self.field_at(r_major, 0.0, z)
        return [r_major * bx / by, r_major * bz / by]

    def safety_factor(self, rho):
        """q(ρ) mesuré : le nombre de tours toroïdaux d'une ligne de champ pour un tour poloïdal."""
        start = self.point(rho, 0.0, 0.0)

        def crossing(_phi, s):
            return s[1]
        crossing.direction = 1
        sol = solve_ivp(self._line_rhs, (0, 60 * math.pi), [start[0], start[2]], events=crossing,
                        rtol=1e-9, atol=1e-11, max_step=0.05)
        hits = [phi for phi in sol.t_events[0] if phi > 0.5]
        return hits[0] / (2 * math.pi) if hits else float("nan")

    @lru_cache(maxsize=1)
    def field_lines(self):
        """Lignes de champ tracées dans B (dR/dφ = R·B_R/B_φ, dZ/dφ = R·B_Z/B_φ), sur trois surfaces."""
        lines = []
        for rho, starts in ((0.35, 3), (0.65, 4), (0.95, 5)):
            for k in range(starts):
                p = self.point(rho, 2 * math.pi * k / starts, 0.0)
                phis = np.linspace(0, 2 * 2 * math.pi, 420)
                sol = solve_ivp(self._line_rhs, (0, phis[-1]), [p[0], p[2]], t_eval=phis, rtol=1e-8, atol=1e-10)
                phi0 = 2 * math.pi * k / starts * 0.37
                pts = [(float(r * math.cos(f + phi0)), float(r * math.sin(f + phi0)), float(z))
                       for f, r, z in zip(sol.t, sol.y[0], sol.y[1])]
                lines.append((rho, pts))
        return lines

    def wall_hit(self, start, velocity):
        """Distance (m) et point où une droite partie de `start` touche la première paroi."""
        direction = velocity / np.linalg.norm(velocity)

        def outside(s):
            p = start + direction * s
            return self.minor(math.hypot(p[0], p[1]), p[2]) >= self.WALL * self.A
        s = 0.0
        while not outside(s) and s < 40:
            s += 0.02
        low, high = max(0.0, s - 0.02), s
        for _ in range(40):
            mid = (low + high) / 2
            if outside(mid):
                high = mid
            else:
                low = mid
        return high, start + direction * high


def boris(tokamak, x0, v0, qm, dt, steps, every=1, fine=0):
    """Algorithme de Boris (champ magnétique seul) : à chaque pas, la vitesse tourne autour de B d'un angle
    exact, sans changer de norme ; la position avance. Rend (temps, positions tous les `every` pas),
    (temps, positions des `fine` premiers pas) et la vitesse finale."""
    x, y, z = (float(c) for c in x0)
    vx, vy, vz = (float(c) for c in v0)
    h = qm * dt / 2
    times, points = [0.0], [(x, y, z)]
    fine_times, fine_points = [0.0], [(x, y, z)]
    field = tokamak.field_at
    half = dt / 2
    for i in range(steps):
        # Demi-pas, rotation dans le champ du milieu, demi-pas : schéma symétrique, exactement réversible.
        x += vx * half
        y += vy * half
        z += vz * half
        bx, by, bz = field(x, y, z)
        tx, ty, tz = h * bx, h * by, h * bz
        k = 2 / (1 + tx * tx + ty * ty + tz * tz)
        sx, sy, sz = k * tx, k * ty, k * tz
        px = vx + (vy * tz - vz * ty)
        py = vy + (vz * tx - vx * tz)
        pz = vz + (vx * ty - vy * tx)
        vx += py * sz - pz * sy
        vy += pz * sx - px * sz
        vz += px * sy - py * sx
        x += vx * half
        y += vy * half
        z += vz * half
        if (i + 1) % every == 0:
            times.append((i + 1) * dt)
            points.append((x, y, z))
        if i < fine:
            fine_times.append((i + 1) * dt)
            fine_points.append((x, y, z))
    return (np.array(times), np.array(points)), (np.array(fine_times), np.array(fine_points)), np.array([vx, vy, vz])


def _isotropic(rng):
    u = rng.normal(size=3)
    return u / np.linalg.norm(u)


def _perpendicular(u):
    other = np.array([1.0, 0.0, 0.0]) if abs(u[0]) < 0.9 else np.array([0.0, 1.0, 0.0])
    w = np.cross(u, other)
    return w / np.linalg.norm(w)


def _kelvin_text(kt_kev):
    kelvin = kt_kev * fusion.KEV_TO_K
    return f"{fusion.fr(kelvin / 1e6, 3)} millions de °C"


# ======================================================================
# Le réacteur
# ======================================================================
class Reactor:
    HISTORY_S = 0.3e-6      # durée de vol calculée avant la rencontre
    ALPHA_S = 2e-6          # durée de vol calculée après la naissance des produits
    STEPS_PER_TURN = 24     # pas de Boris par tour de giration
    FINE_STEPS = 1500       # pas gardés tels quels près de la rencontre

    def __init__(self, rng_seed=None):
        self.rng = np.random.default_rng(rng_seed)
        self.tokamak = Tokamak()
        self.fuel_index = 0
        self.kt_kev = 15.0
        self.run = 0
        self.shot = None
        self.sent_geometry = False
        self.sent_run = 0

    @property
    def fuel(self):
        return FUELS[self.fuel_index]

    def set_fuel(self, index):
        self.fuel_index = max(0, min(len(FUELS) - 1, int(index)))
        self.shot = None

    def set_temperature(self, kev):
        self.kt_kev = float(min(200.0, max(1.0, kev)))

    # ------------------------------------------------------------------ le plasma
    def plasma(self):
        fuel = self.fuel
        sv = reactivity(fuel, self.kt_kev)
        n = self.tokamak.DENSITY
        same = fuel["a"] == fuel["b"]
        n_a = n if same else n / 2
        n_b = n if same else n / 2
        rate = n_a * n_b * sv / (2 if same else 1)               # réactions par m³ et par seconde
        q = self._q_values()
        power = rate * float(np.mean(q)) * 1e6 * sc.e             # W/m³
        wait = 1 / (n_b * sv) if sv > 0 else math.inf             # temps moyen avant qu'un noyau A fusionne
        return {"reactivity": sv, "power_w_m3": power, "wait_s": wait,
                "gamow_kev": gamow_peak_kev(fuel, self.kt_kev)}

    def _q_values(self):
        fuel = self.fuel
        before = fusion.mass_u(*fuel["a"])[0] + fusion.mass_u(*fuel["b"])[0]
        out = []
        for _, products in fuel["branches"]:
            after = sum(fusion.mass_u(*p)[0] for p in products)
            out.append((before - after) * MEV_PER_U)
        return out

    # ------------------------------------------------------------------ le tir
    def _sample_energy(self):
        """Énergie relative (keV) d'une paire qui fusionne : densité ∝ σ(E)·E·e^(−E/kT), et la branche."""
        fuel = self.fuel
        grid = _energy_grid(self.kt_kev)
        sigmas = [cross_section_b(name, grid) for name, _ in fuel["branches"]]
        weight = sum(sigmas) * grid * np.exp(-grid / self.kt_kev)
        cdf = np.cumsum((weight[1:] + weight[:-1]) / 2 * np.diff(grid))
        cdf /= cdf[-1]
        e = float(np.interp(self.rng.random(), cdf, grid[1:]))
        shares = np.array([float(cross_section_b(name, e)) for name, _ in fuel["branches"]])
        branch = int(self.rng.choice(len(shares), p=shares / shares.sum()))
        return e, branch

    def fire(self):
        fuel = self.fuel
        tok = self.tokamak
        self.run += 1
        e_kev, branch = self._sample_energy()
        reaction, products = fuel["branches"][branch]
        za, aa = fuel["a"]
        zb, ab = fuel["b"]
        ma = nuclear_mass_mev(za, aa) / MEV_PER_U * U_KG
        mb = nuclear_mass_mev(zb, ab) / MEV_PER_U * U_KG
        m_total = ma + mb
        mu = ma * mb / m_total

        # Le lieu : dans le cœur du plasma, à un angle toroïdal quelconque.
        rho = 0.15 + 0.35 * self.rng.random()
        meet = tok.point(rho, self.rng.uniform(0, 2 * math.pi), self.rng.uniform(0, 2 * math.pi))
        b_meet = np.array(tok.field_at(*meet))
        # Les vitesses : centre de masse maxwellien, vitesse relative de la paire qui fusionne.
        kt_j = self.kt_kev * J_PER_KEV
        v_cm = self.rng.normal(size=3) * math.sqrt(kt_j / m_total)
        axis = _isotropic(self.rng)
        v_rel = axis * math.sqrt(2 * e_kev * J_PER_KEV / mu)
        va = v_cm + mb / m_total * v_rel
        vb = v_cm - ma / m_total * v_rel

        # Leur passé : on renverse le temps (vitesse et charge opposées), on intègre, on remet à l'endroit.
        qm = np.array([za * sc.e / ma, zb * sc.e / mb])
        period = 2 * math.pi / (max(abs(qm[0]), abs(qm[1])) * np.linalg.norm(b_meet))
        dt = period / self.STEPS_PER_TURN
        steps = int(self.HISTORY_S / dt)
        every = max(1, steps // 900)
        history = []
        for v, q_over_m in ((va, qm[0]), (vb, qm[1])):
            (t_c, p_c), (t_f, p_f), _ = boris(tok, meet, -v, -q_over_m, dt, steps, every, self.FINE_STEPS)
            history.append(((-t_c[::-1], p_c[::-1]), (-t_f[::-1], p_f[::-1] - meet)))
        rho_max = float(max(np.max(tok.rho(h[0][1])) for h in history))

        pair = fusion.Pair(fuel["a"], fuel["b"])
        nuclear = self._encounter(pair, e_kev)
        # Facteur de Gamow exp(−2πη) : la constante B_G de la section efficace mesurée, B_G = πα·Z₁Z₂·√(2μc²).
        nuclear["gamow"] = math.exp(-BOSCH_HALE[reaction][0] / math.sqrt(e_kev))
        impact_dir = _perpendicular(axis)
        turn = self.rng.uniform(0, 2 * math.pi)
        impact_dir = math.cos(turn) * impact_dir + math.sin(turn) * np.cross(axis, impact_dir)

        # Les spins : la résonance 3/2⁺ demande des spins parallèles (S = 3/2), projetés sur B.
        b_hat = b_meet / np.linalg.norm(b_meet)
        spin_text, spins = self._spins(fuel, b_hat)

        # La fusion : énergie libérée (masses mesurées), deux produits dos à dos dans le centre de masse.
        q_mev = self._q_values()[branch]
        (z1, a1), (z2, a2) = products
        m1 = nuclear_mass_mev(z1, a1)
        m2 = nuclear_mass_mev(z2, a2)
        total = m1 + m2 + q_mev + e_kev / 1000
        p = math.sqrt(max((total ** 2 - (m1 + m2) ** 2) * (total ** 2 - (m1 - m2) ** 2), 0)) / (2 * total)
        out_dir = _isotropic(self.rng)
        born = []
        for (z, a), m, sign in (((z1, a1), m1, 1), ((z2, a2), m2, -1)):
            energy = math.sqrt(p * p + m * m)
            beta = p / energy
            v = sign * out_dir * beta * sc.c + v_cm
            spin = fusion_spin(z, a)
            born.append({"z": z, "a": a, "v": v, "kinetic_mev": energy - m, "beta": beta,
                         "spin": _isotropic(self.rng) if spin > 0 else np.zeros(3)})

        # Leur avenir : la particule chargée suit le champ (Boris), le neutre file droit jusqu'à la paroi.
        for product in born:
            if product["z"] > 0:
                m_kg = nuclear_mass_mev(product["z"], product["a"]) / MEV_PER_U * U_KG
                qm_p = product["z"] * sc.e / m_kg
                period_p = 2 * math.pi / (qm_p * np.linalg.norm(b_meet))
                dt_p = period_p / self.STEPS_PER_TURN
                steps_p = int(self.ALPHA_S / dt_p)
                (t_p, path_p), (ft, fp), _ = boris(tok, meet, product["v"], qm_p, dt_p, steps_p,
                                                   max(1, steps_p // 1500), self.FINE_STEPS)
                product.update(path=(t_p, path_p), fine=(ft, fp - meet), qm=qm_p,
                               confined=bool(np.max(tok.rho(path_p)) < tok.WALL), end_t=float(t_p[-1]))
            else:
                distance, hit = tok.wall_hit(meet, product["v"])
                t_hit = distance / np.linalg.norm(product["v"])
                ts = np.linspace(0, t_hit, 60)
                pts = meet[None, :] + product["v"][None, :] * ts[:, None]
                product.update(path=(ts, pts), fine=(ts, pts - meet), qm=0.0, confined=False,
                               end_t=float(t_hit), wall_m=float(distance), hit=hit)

        self.shot = {
            "run": self.run, "reaction": reaction, "branch": branch, "e_kev": e_kev, "meet": meet,
            "b_meet": b_meet, "rho": rho, "rho_max": rho_max, "v_cm": v_cm, "axis": axis,
            "reactants": [
                {"z": za, "a": aa, "v": va, "path": history[0][0], "fine": history[0][1], "qm": qm[0],
                 "spin": spins[0]},
                {"z": zb, "a": ab, "v": vb, "path": history[1][0], "fine": history[1][1], "qm": qm[1],
                 "spin": spins[1]},
            ],
            "products": born, "q_mev": q_mev, "nuclear": nuclear, "spin_text": spin_text,
            "sigma_b": float(cross_section_b(reaction, e_kev)), "pair": pair, "impact_dir": impact_dir,
            "lifetime_s": HBAR_MEV_S / HE5_WIDTH_MEV if fuel["compound"] == (2, 5) else 0.0,
        }
        return self._events()

    def _encounter(self, pair, e_kev):
        """Le choc dans le centre de masse : trajectoire sous Coulomb + force nucléaire jusqu'au point le
        plus proche, puis l'effet tunnel jusqu'au contact. Unités : fm et fm/c."""
        energy = e_kev / 1000
        k = math.sqrt(2 * pair.mu * energy) / fusion.HBAR_C           # nombre d'onde (fm⁻¹)
        # Onde s (l = 0) : un paramètre d'impact dans le disque où l ≈ 0, b < 1/(2k).
        impact = 0.5 / k * math.sqrt(self.rng.random())
        start = 2500.0
        x0 = -math.sqrt(start ** 2 - impact ** 2)
        speed = math.sqrt(2 * max(energy - pair.v(start), 1e-12) / pair.mu)

        def accel(_t, s):
            x, y, vx, vy = s
            r = math.hypot(x, y)
            h = 1e-3 * max(r, 1.0)
            dv = (pair.v(r + h) - pair.v(r - h)) / (2 * h)
            f = -dv / pair.mu / max(r, 1e-6)
            return [vx, vy, f * x, f * y]

        def closest(_t, s):
            return s[0] * s[2] + s[1] * s[3]
        closest.terminal = True
        closest.direction = 1

        span = 3 * start / speed
        sol = solve_ivp(accel, (0, span), [x0, impact, speed, 0.0], events=closest, rtol=1e-10, atol=1e-12,
                        dense_output=True, max_step=span / 2000)
        t_end = sol.t[-1]
        # Échantillons serrés près de la rencontre (là où tout se joue), lâches au loin.
        times = np.unique(np.concatenate([np.linspace(0, t_end, 200), t_end - np.geomspace(1e-3, t_end, 300)]))
        times = times[(times >= 0) & (times <= t_end)]
        states = sol.sol(times)
        r_min = float(np.hypot(states[0][-1], states[1][-1]))
        tunnel = pair.tunneling(energy, 0)
        return {
            "times_s": (times - t_end) * S_PER_FM_C, "x": states[0], "y": states[1],
            "vx": states[2], "vy": states[3], "impact_fm": impact, "r_min_fm": r_min,
            "turning_fm": pair.turning_point(energy), "contact_fm": pair.contact,
            "barrier_mev": pair.barrier, "barrier_r_fm": pair.barrier_r, "tunneling": tunnel,
            "k_fm": k, "speed_c": speed, "energy_mev": energy,
        }

    def _spins(self, fuel, b_hat):
        """Les axes de spin des deux noyaux. Spins parallèles pour une résonance 3/2⁺ (S = 3/2) :
        projection M tirée, angle à B tel que cos θ = M/√(S(S+1))."""
        e1 = _perpendicular(b_hat)
        e2 = np.cross(b_hat, e1)
        if fuel["resonance"]:
            m = self.rng.choice([-1.5, -0.5, 0.5, 1.5])
            cos = m / math.sqrt(1.5 * 2.5)
            sin = math.sqrt(1 - cos * cos)
            phi = self.rng.uniform(0, 2 * math.pi)
            axis = cos * b_hat + sin * (math.cos(phi) * e1 + math.sin(phi) * e2)
            text = (f"Spins parallèles : S = 3/2, projection M = {fusion.fr(m, 2)} sur B. Seule cette combinaison "
                    f"ouvre la résonance {fuel['resonance']} du noyau composé, qui fait presque toute cette fusion : "
                    f"2 paires sur 3 au hasard, toutes si le combustible est polarisé (σ × 1,5).")
            return text, [axis, axis]
        a, b = _isotropic(self.rng), _isotropic(self.rng)
        return "Spins quelconques : D + D ne passe pas par une résonance étroite.", [a, b]

    # ------------------------------------------------------------------ ce qu'on en dit
    def _events(self):
        s = self.shot
        n = s["nuclear"]
        a, b = (fusion.label(r["z"], r["a"]) for r in s["reactants"])
        names = " + ".join(fusion.label(p["z"], p["a"]) for p in s["products"])
        title = f"{a} + {b} → {names} + {fusion.fr(s['q_mev'], 3)} MeV"
        detail = (f"Énergie du choc : {fusion.fr(s['e_kev'], 3)} keV ; ils s'approchent à {fusion.fr(n['r_min_fm'], 3)} fm, "
                  f"puis traversent la barrière de Coulomb ({fusion.fr(n['barrier_mev'] * 1000, 3)} keV) par effet tunnel.")
        return [{"kind": "fusion", "title": title, "formula": f"σ = {fusion.fr(s['sigma_b'], 3)} b (Bosch-Hale)",
                 "detail": detail}]

    def facts(self):
        """Les chiffres du tir, et ce qu'ils veulent dire (pour « Comprendre »)."""
        s = self.shot
        n = s["nuclear"]
        plasma = self.plasma()
        tok = self.tokamak
        fr = fusion.fr
        a, b = s["reactants"]
        la, lb = fusion.label(a["z"], a["a"]), fusion.label(b["z"], b["a"])
        kelvin = self.kt_kev * fusion.KEV_TO_K
        out = [
            ("Température", f"kT = {fr(self.kt_kev, 3)} keV · {_kelvin_text(self.kt_kev)}",
             f"{fr(kelvin / fusion.SUN_CORE_K, 2)} fois le cœur du Soleil : les électrons sont arrachés, il ne reste "
             f"que des noyaux et des électrons libres — un plasma, tenu loin des parois par le champ magnétique."),
            ("Pourquoi ces deux-là", f"{fr(plasma['wait_s'], 3)} s d'attente en moyenne",
             f"Un {la} donné attend en moyenne {fr(plasma['wait_s'], 3)} s (des millions de tours du tokamak) avant de "
             f"fusionner. Nous suivons une paire qui se rencontre ; sa vitesse est tirée comme dans le vrai plasma."),
            ("Le champ qui les tient", f"B = {fr(float(np.linalg.norm(s['b_meet'])), 3)} T · q de 1 à 3",
             f"Chargés, {la} et {lb} s'enroulent autour des lignes de champ (rayon de Larmor de quelques mm) et "
             f"suivent leur surface magnétique sans toucher la paroi."),
            ("Énergie du choc", f"{fr(s['e_kev'], 3)} keV",
             f"Plus que la moyenne du plasma ({fr(self.kt_kev * 1.5, 3)} keV) : les paires qui fusionnent sont les "
             f"plus rapides (pic de Gamow, {fr(plasma['gamow_kev'], 3)} keV)."),
            ("La barrière de Coulomb", f"{fr(n['barrier_mev'] * 1000, 3)} keV",
             f"Les deux noyaux sont positifs : la répulsion les arrête à {fr(n['r_min_fm'], 3)} fm, loin du contact "
             f"({fr(n['contact_fm'], 3)} fm). Ils passent quand même, par effet tunnel : probabilité "
             f"exp(−2πη) = {fr(n['gamow'] * 100, 3)} %."),
            ("Section efficace mesurée", f"{fr(s['sigma_b'], 3)} b",
             "La surface « cible » que voit chaque noyau, mesurée en laboratoire (Bosch & Hale 1992)."),
            ("Spins", "parallèles" if self.fuel["resonance"] else "quelconques", s["spin_text"]),
            ("Énergie libérée", f"Q = {fr(s['q_mev'], 4)} MeV",
             "Les produits pèsent moins lourd que les noyaux de départ : la masse disparue devient énergie, E = Δm·c²."),
        ]
        fuel_kg = (fusion.mass_u(a["z"], a["a"])[0] + fusion.mass_u(b["z"], b["a"])[0]) * U_KG
        per_kg = s["q_mev"] * 1e6 * sc.e / fuel_kg
        out.append(("Pour 1 kg de combustible", f"{fr(per_kg / fusion.TOE_J, 3)} tonnes de pétrole",
                    f"{fr(per_kg, 3)} J par kilogramme."))
        for p in s["products"]:
            name = fusion.label(p["z"], p["a"]) if p["z"] else "neutron"
            if p["z"] > 0:
                text = ("reste piégée dans le plasma : chargée, elle tourne autour des lignes de champ et cède son "
                        "énergie au plasma — c'est elle qui l'entretient chaud." if p["confined"]
                        else "sort du plasma.")
            else:
                text = (f"sans charge, le champ ne le retient pas : il file droit et frappe la paroi à "
                        f"{fr(p['wall_m'], 3)} m, en {fr(p['end_t'] * 1e9, 3)} ns. La couverture le freine (chaleur → "
                        f"électricité) ; avec du lithium 6, il fabrique le tritium du combustible.")
            out.append((name, f"{fr(p['kinetic_mev'], 3)} MeV", text))
        return out

    def forces(self):
        """Les forces au moment le plus serré du choc (N)."""
        s = self.shot
        n = s["nuclear"]
        pair = s["pair"]
        r = n["r_min_fm"]
        coulomb = pair.coulomb_force_n(r)
        # Pente du potentiel nucléaire (Woods-Saxon) au contact : V₀/(4a), en N.
        strong = pair.depth / (4 * fusion.DIFFUSENESS_FM) * fusion.NEWTON_PER_MEV_FM
        ra, rb = s["reactants"]
        ma = nuclear_mass_mev(ra["z"], ra["a"]) / MEV_PER_U * U_KG
        mb = nuclear_mass_mev(rb["z"], rb["a"]) / MEV_PER_U * U_KG
        b = float(np.linalg.norm(s["b_meet"]))
        lorentz = ra["z"] * sc.e * float(np.linalg.norm(ra["v"])) * b
        mu_a = fusion.magnetic_moment(ra["z"], ra["a"]) * MU_N
        mu_b = fusion.magnetic_moment(rb["z"], rb["a"]) * MU_N
        rm = r * 1e-15
        dipole = 3 * sc.mu_0 * abs(mu_a * mu_b) / (2 * math.pi * rm ** 4)
        gravity = sc.G * ma * mb / rm ** 2
        return {"coulomb": coulomb, "strong": strong, "lorentz": lorentz, "dipole": dipole, "gravity": gravity}
