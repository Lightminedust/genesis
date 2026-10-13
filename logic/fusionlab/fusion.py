"""Le laboratoire de fusion : deux noyaux, n'importe lesquels, et de vraies règles.

On choisit deux isotopes parmi tous ceux dont la masse est connue (AME, via
`mendeleev`), l'énergie du tir et la visée. Le moteur applique :

1. Le potentiel entre les deux noyaux : répulsion électrique (Coulomb, sphère
   uniformément chargée) + attraction nucléaire de courte portée (potentiel de
   Woods-Saxon d'Akyüz et Winther, 1981). Leur somme dessine une colline (la
   barrière de Coulomb) qui entoure un creux (le puits nucléaire).
2. Le mouvement : la trajectoire est intégrée (`scipy.integrate.solve_ivp`)
   avec la vraie force −dV/dr ; une visée décalée dévie le noyau (Rutherford).
3. L'effet tunnel : probabilité de traverser la colline par l'intégrale WKB
   P = 1/(1 + e^{2K}), K = ∫ √(2μ(V_eff − E))/ħ dr, et au-dessus du sommet la
   formule de Hill et Wheeler. Le tir est tiré au sort avec cette probabilité.
4. Ce que devient le noyau composé : les voies mesurées quand on les connaît
   (D + T → He-4 + n...), sinon le modèle d'évaporation de Weisskopf (1937) :
   chaque particule (n, p, d, t, He-3, α) est émise au prorata de l'énergie
   disponible, de la densité de niveaux du noyau restant et de sa chance de
   franchir la barrière de sortie ; un noyau lourd peut aussi fissionner
   (Bohr-Wheeler, barrière de la goutte liquide de Cohen et Swiatecki).
5. E = Δm·c² avec les masses mesurées ; l'énergie se partage selon la
   conservation de la quantité de mouvement.

Les masses jamais mesurées sont estimées (formule de Weizsäcker) et le
disent. Chaque grandeur est aussi traduite en image parlante.
"""

import math
from functools import lru_cache

import numpy as np
from scipy import constants as sc
from scipy.integrate import quad, solve_ivp

from fusionlab import collider
from nuclear import nuclear_data
from nuclear import nucleus
from atomic import periodic_table
from atomic.electron_config import subshell_configuration
from atomic.orbitals import hund_occupation, radial
from atomic.slater import effective_charge, n_star
from common.units import format_duration

MEV_PER_U = collider.MEV_PER_U
HBAR_C = sc.hbar * sc.c / (sc.e * 1e6) * 1e15            # 197,327 MeV·fm
COULOMB = collider.COULOMB_MEV_FM                          # 1,44 MeV·fm
FM_PER_C_S = 1e-15 / sc.c                                  # 1 fm/c en secondes
KEV_TO_K = sc.e * 1e3 / sc.k
NEUTRON_MASS_U = nuclear_data.NEUTRON_MASS_U
DIFFUSENESS_FM = 0.63                                      # Akyüz-Winther
EJECTILES = [("n", 0, 1, 2), ("p", 1, 1, 2), ("d", 1, 2, 3), ("t", 1, 3, 2), ("He-3", 2, 3, 2), ("α", 2, 4, 1)]

# Repères pour traduire les énergies (valeurs de référence).
SUN_LUMINOSITY_W = 3.828e26          # IAU 2015
METHANE_EV = 890.3e3 / sc.N_A / sc.e  # combustion du méthane : 890 kJ/mol → 9,2 eV par molécule
TOE_J = 41.868e9                     # tonne d'équivalent pétrole (définition)
TNT_TON_J = 4.184e9                  # tonne de TNT (définition)
SUN_CORE_K = 1.57e7                  # centre du Soleil (modèle standard)
EARTH_TOUR_M = 40_075e3


def fr(value, digits=3):
    """Nombre lisible à la française ; puissances de dix en exposant."""
    if value == 0:
        return "0"
    if not math.isfinite(value):
        return "∞"
    magnitude = abs(value)
    if 1e-3 <= magnitude < 1e6:
        text = f"{value:.{digits}g}"
        if "e" in text:
            text = f"{value:.0f}"
        if magnitude >= 1000:
            text = f"{round(value):,}".replace(",", " ")
        return text.replace(".", ",")
    sign = "−" if value < 0 else ""
    return sign + collider._power_of_ten(magnitude)


# ======================================================================
# Le catalogue : tous les isotopes connus
# ======================================================================
def catalog():
    """Éléments et isotopes dont la masse est connue, pour l'inventaire."""
    rows = nuclear_data._DB.execute(
        "select atomic_number, mass_number, mass, is_radioactive, half_life, half_life_unit, abundance "
        "from isotopes where mass is not null order by atomic_number, mass_number").fetchall()
    by_z = {}
    for z, a, mass, radioactive, *_ in rows:
        by_z.setdefault(z, []).append((a, mass))
    elements = []
    for element in periodic_table.ELEMENTS:
        z = element["z"]
        isotopes = []
        for a, mass in by_z.get(z, []):
            data = nuclear_data.info(z, a)
            stable = bool(data and data["stable"]) or (data is None and nuclear_data.abundance(z, a) > 0)
            half = data["half_life_s"] if data and not data["stable"] and data["half_life_s"] else 0.0
            isotopes.append({"a": a, "mass_u": mass, "stable": stable, "half_life_s": half,
                             "abundance": nuclear_data.abundance(z, a)})
        if not isotopes:
            continue
        elements.append({"z": z, "symbol": element["symbol"], "name": element["name"],
                         "category": element["category"], "color": element["color_hex"],
                         "row": element["table_row"], "col": element["table_col"],
                         "default_a": default_isotope(z), "isotopes": isotopes})
    return elements


def default_isotope(z):
    """L'isotope le plus abondant, sinon celui qui vit le plus longtemps."""
    rows = nuclear_data._DB.execute("select mass_number, abundance from isotopes where atomic_number=? "
                                    "and mass is not null", (z,)).fetchall()
    if not rows:
        return 2 * z
    best = max(rows, key=lambda r: r[1] or 0)
    if best[1]:
        return best[0]
    lived = [(a, (nuclear_data.info(z, a) or {}).get("half_life_s") or 0) for a, _ in rows]
    return max(lived, key=lambda r: r[1])[0]


# ======================================================================
# Masses : mesurées, sinon estimées (Weizsäcker)
# ======================================================================
def mass_u(z, a):
    """(masse atomique en u, estimée ?)."""
    if z == 0:
        return (NEUTRON_MASS_U, False) if a == 1 else (None, True)
    measured = nuclear_data.atomic_mass_u(z, a)
    if measured is not None:
        return measured, False
    if a < z or z < 0 or a < 1:
        return None, True
    binding, _, _ = nucleus.binding_energy(z, a - z)
    parts = z * nuclear_data.atomic_mass_u(1, 1) + (a - z) * NEUTRON_MASS_U
    return parts - binding / MEV_PER_U, True


HEAVIEST_KNOWN_Z = 118          # oganesson : au-delà, aucun noyau n'a jamais été observé


MU_N_J_T = sc.physical_constants["nuclear magneton"][0]
MU0_OVER_4PI = sc.mu_0 / (4 * math.pi)
NEWTON_PER_MEV_FM = sc.e * 1e6 / 1e-15            # 1 MeV/fm = 160,2 N
HOME_KWH_YEAR = 4700        # consommation électrique moyenne d'un foyer français (CRE)


def chance_meaning(p):
    """Une probabilité en image."""
    if p >= 0.5:
        return f"plus d'une chance sur deux : environ {round(100 * p)} tirs sur 100 réussissent"
    if p >= 0.02:
        return f"environ {round(100 * p)} tirs sur 100 réussissent, les autres sont renvoyés"
    dice = math.log(1 / p) / math.log(6)
    if dice < 30:
        return f"comme obtenir {round(dice)} six d'affilée en lançant un dé"
    return (f"plus improbable que de gagner le loto {fr(math.log(1 / p) / math.log(1.4e7), 2)} fois de suite "
            "(1 chance sur 14 millions chaque fois)")


def label(z, a):
    if z > HEAVIEST_KNOWN_Z:
        return f"Z={z}"
    return collider.label(z, a)


def magnetic_moment(z, a):
    """Moment magnétique mesuré (magnétons nucléaires) : μ = g·I."""
    if z == 0:
        return -1.9130427
    row = nuclear_data._DB.execute("select spin, g_factor from isotopes where atomic_number=? and mass_number=?",
                                   (z, a)).fetchone()
    if not row or row[1] is None or not row[0]:
        return 0.0
    spin = row[0].strip("()+- ")
    try:
        value = float(spin.split("/")[0]) / float(spin.split("/")[1]) if "/" in spin else float(spin)
    except ValueError:
        return 0.0
    return float(row[1]) * value


def element_color(z):
    row = nuclear_data._DB.execute("select jmol_color from elements where atomic_number=?", (z,)).fetchone()
    return row[0] if row and row[0] else "#cbd5e1"


def energy_text(kev):
    return f"{fr(kev / 1000)} MeV" if kev >= 1000 else f"{fr(kev)} keV"


def temperature_text(kelvin):
    """« 232 millions de °C », « 2,1 milliards de °C », au-delà en puissances de dix."""
    if kelvin < 1e6:
        return f"{fr(kelvin)} °C"
    if kelvin < 1e9:
        return f"{fr(kelvin / 1e6)} millions de °C"
    if kelvin < 1e12:
        return f"{fr(kelvin / 1e9)} milliards de °C"
    return f"{fr(kelvin)} °C"


def element_name(z):
    if z == 0:
        return "neutron"
    for element in periodic_table.ELEMENTS:
        if element["z"] == z:
            return element["name"]
    return "?"


def full_name(z, a):
    special = {(1, 1): "proton (hydrogène 1)", (1, 2): "deutérium", (1, 3): "tritium", (0, 1): "neutron",
               (2, 4): "hélium 4 (particule α)"}
    return special.get((z, a), f"{element_name(z).lower()} {a}")


# ======================================================================
# Le potentiel entre deux noyaux, l'effet tunnel, la trajectoire
# ======================================================================
def nuclear_radius(a):
    """Rayon de la distribution nucléaire (Akyüz-Winther), jamais moins que 1,0·A^⅓."""
    return max(1.233 * a ** (1 / 3) - 0.98 * a ** (-1 / 3), 1.0 * a ** (1 / 3))


class Pair:
    """Deux noyaux qui s'approchent : V(r), barrière, effet tunnel, mouvement."""

    def __init__(self, a, b):
        (self.z1, self.a1), (self.z2, self.a2) = a, b
        m1, _ = mass_u(*a)
        m2, _ = mass_u(*b)
        self.mu = m1 * m2 / (m1 + m2) * MEV_PER_U          # masse réduite, MeV/c²
        self.r1, self.r2 = nuclear_radius(self.a1), nuclear_radius(self.a2)
        self.contact = self.r1 + self.r2
        i1 = (self.a1 - 2 * self.z1) / self.a1
        i2 = (self.a2 - 2 * self.z2) / self.a2
        gamma = max(0.4, 0.95 * (1 - 1.8 * i1 * i2))         # tension de surface, MeV/fm²
        self.depth = 16 * math.pi * gamma * DIFFUSENESS_FM * self.r1 * self.r2 / self.contact
        self.zz = self.z1 * self.z2
        self.grid = np.linspace(0.3, 60.0, 1200)
        values = np.array([self.v(r) for r in self.grid])
        self.barrier_r, self.barrier = self._barrier(values)
        self.inside = self._inside(values)
        # Courbure au sommet, pour Hill-Wheeler : ħω = ħ √(|V''|/μ).
        h = 0.05
        curvature = (self.v(self.barrier_r + h) - 2 * self.barrier + self.v(self.barrier_r - h)) / h ** 2
        self.hbar_omega = HBAR_C * math.sqrt(max(abs(curvature), 1e-6) / self.mu) if self.zz else 1.0

    def v(self, r, l=0):
        """Énergie potentielle (MeV) à la distance r (fm) : Coulomb + nucléaire + centrifuge."""
        if self.zz == 0:
            coulomb = 0.0
        elif r >= self.contact:
            coulomb = COULOMB * self.zz / r
        else:
            coulomb = COULOMB * self.zz / (2 * self.contact) * (3 - (r / self.contact) ** 2)
        nuclear = -self.depth / (1 + math.exp(min(60.0, (r - self.contact) / DIFFUSENESS_FM)))
        centrifugal = HBAR_C ** 2 * l * (l + 1) / (2 * self.mu * r * r) if l else 0.0
        return coulomb + nuclear + centrifugal

    def force_n(self, r):
        """Force (N) au sens de −dV/dr : positive = répulsion."""
        h = 1e-3
        dv = (self.v(r + h) - self.v(r - h)) / (2 * h)            # MeV/fm
        return -dv * 1e6 * sc.e / 1e-15

    def coulomb_force_n(self, r):
        return collider.coulomb_force_n(self.z1, self.z2, max(r, 0.1))

    def _barrier(self, values):
        """Le sommet de la colline : le maximum local le plus extérieur."""
        peaks = [i for i in range(1, len(values) - 1) if values[i] >= values[i - 1] and values[i] > values[i + 1]]
        peaks = [i for i in peaks if self.grid[i] > 0.4 * self.contact]
        i = peaks[-1] if peaks else int(np.argmax(values))
        return float(self.grid[i]), float(values[i])

    def _inside(self, values):
        """Le point de capture : là où la force forte a gagné (fond du creux, ou contact)."""
        inner = [i for i in range(len(values)) if self.grid[i] < self.barrier_r]
        if not inner:
            return self.contact * 0.8
        i = min(inner, key=lambda k: values[k])
        return float(self.grid[i])

    def turning_point(self, energy, l=0):
        """Distance où la colline arrête le noyau (r > sommet)."""
        if energy <= 0:
            return math.inf
        if self.v(self.barrier_r, l) <= energy:
            return self.barrier_r
        low, high = self.barrier_r, 1e6
        for _ in range(200):
            mid = math.sqrt(low * high)
            if self.v(mid, l) > energy:
                low = mid
            else:
                high = mid
        return high

    def tunneling(self, energy, l=0):
        """Probabilité de franchir la barrière (WKB / Hill-Wheeler)."""
        if self.zz == 0:
            return 1.0
        # La capture se joue au contact : plus à l'intérieur, les noyaux ne sont plus deux (le terme
        # centrifuge l(l+1)/r² y divergerait sans signification physique).
        low = max(self.inside, 0.75 * self.contact)
        top = max(self.v(r, l) for r in np.linspace(low, max(self.barrier_r * 3, 30), 400))
        if energy >= top:
            return 1.0 / (1.0 + math.exp(-2 * math.pi * (energy - top) / self.hbar_omega))
        outer = self.turning_point(energy, l)
        # Côté intérieur : premier point (en partant du sommet) où V retombe sous E.
        inner = low
        r = self.barrier_r
        while r > low:
            if self.v(r, l) < energy:
                inner = r
                break
            r -= 0.02
        def integrand(x):
            return math.sqrt(max(0.0, 2 * self.mu * (self.v(x, l) - energy))) / HBAR_C
        k = quad(integrand, inner, outer, limit=200)[0]
        return 1.0 / (1.0 + math.exp(min(700.0, 2 * k)))

    def trajectory(self, energy, impact_fm, tunnel):
        """Mouvement relatif dans le plan (fm), intégré avec la vraie force.

        Retourne (points [(t, x, y)], indice du saut tunnel ou -1, plus courte distance, durée en s)."""
        run = self.trajectory_full(energy, impact_fm, tunnel)
        return run["points"], run["jump"], run["closest"], run["duration"]

    def trajectory_full(self, energy, impact_fm, tunnel, moment_b=0.0, gamma_a=0.0, stop_at_contact=False):
        """La trajectoire, avec toutes les forces : −dV/dr (Coulomb + nucléaire) et la force de
        Lorentz q·v×B dans le champ magnétique du dipôle de B (moment `moment_b`, en magnétons
        nucléaires, perpendiculaire au plan). Le spin de A précesse selon l'équation de Bloch
        dS/dt = γ S × B (γ_A en rad/s/T) : on rend l'angle total."""
        turn = self.turning_point(energy)
        # Départ toujours au-delà du point de rebroussement : sinon le noyau partirait sans élan.
        start = min(1e6, max(2.5 * turn, 6.0 * self.barrier_r, 30.0))
        x0 = -math.sqrt(max(start ** 2 - impact_fm ** 2, 1.0))
        # L'énergie du tir est comptée à l'infini : au départ, une part est déjà en répulsion.
        speed = math.sqrt(2 * max(energy - self.v(math.hypot(x0, impact_fm)), 1e-9) / self.mu)   # unités de c

        def field_z(r):
            """Champ du dipôle de B dans son plan équatorial (T), hors du noyau."""
            rm = max(r, self.contact) * 1e-15
            return -MU0_OVER_4PI * moment_b * MU_N_J_T / rm ** 3

        def make(with_lorentz):
            def accel(_t, s):
                x, y, vx, vy = s
                r = math.hypot(x, y)
                h = 1e-3
                dv = (self.v(r + h) - self.v(r - h)) / (2 * h)
                f = -dv / self.mu / max(r, 1e-6)
                ax, ay = f * x, f * y
                if with_lorentz and moment_b and self.z1:
                    bz = field_z(r)
                    k = self.z1 * sc.e * sc.c * bz / NEWTON_PER_MEV_FM / self.mu
                    ax += k * vy
                    ay -= k * vx
                return [vx, vy, ax, ay]
            return accel

        def captured(_t, s):
            return math.hypot(s[0], s[1]) - (self.contact if stop_at_contact else self.inside)
        captured.terminal = True

        def left(_t, s):
            return math.hypot(s[0], s[1]) - start * 1.02
        left.terminal = True
        left.direction = 1

        def closest(_t, s):
            return s[0] * s[2] + s[1] * s[3]
        closest.terminal = bool(tunnel or stop_at_contact)
        closest.direction = 1

        span = 8 * start / speed
        start_state = [x0, impact_fm, speed, 0.0]
        sol = solve_ivp(make(True), (0, span), start_state, events=[captured, left, closest],
                        max_step=span / 400, rtol=1e-9, atol=1e-11, dense_output=True)
        t_end = sol.t[-1]
        times = np.linspace(0, t_end, 320)
        states = sol.sol(times)
        points = [(float(t), float(x), float(y)) for t, x, y in zip(times, states[0], states[1])]
        radii = np.hypot(states[0], states[1])
        closest_r = float(np.min(radii))
        speeds = np.hypot(states[2], states[3])
        # La force de Lorentz le long du trajet, et ce qu'elle change : on refait le calcul sans elle.
        bz = np.array([field_z(r) for r in radii])
        lorentz = np.abs(self.z1 * sc.e * speeds * sc.c * bz)
        deflection = 0.0
        if moment_b and self.z1:
            bare = solve_ivp(make(False), (0, t_end), start_state, max_step=span / 400, rtol=1e-9, atol=1e-11,
                             dense_output=True)
            other = bare.sol(np.clip(times, 0, bare.t[-1]))
            deflection = float(np.max(np.hypot(states[0] - other[0], states[1] - other[1])))
        # Bloch : le spin tourne autour du champ local à la vitesse γ|B|.
        precession = abs(gamma_a) * float(np.trapezoid(np.abs(bz), times * FM_PER_C_S)) if gamma_a else 0.0
        jump = -1
        if (tunnel or stop_at_contact) and sol.t_events[0].size == 0 and closest_r > self.contact:
            # À travers la colline : le noyau passe de l'extérieur au creux sans la gravir.
            x, y = points[-1][1], points[-1][2]
            r = math.hypot(x, y)
            jump = len(points) - 1
            for k in range(1, 25):
                f = k / 24
                rr = r + ((self.contact if stop_at_contact else self.inside) - r) * f
                points.append((t_end * (1 + 0.02 * f), x / r * rr, y / r * rr))
        return {"points": points, "jump": jump, "closest": closest_r, "duration": t_end * FM_PER_C_S,
                "lorentz_max": float(np.max(lorentz)) if len(lorentz) else 0.0, "deflection": deflection,
                "precession": precession}


# ======================================================================
# Ce que devient le noyau composé
# ======================================================================
def level_density(a_mass, u):
    """Densité de niveaux du gaz de Fermi : ρ(U) ∝ exp(2√(aU)), a = A/8 MeV⁻¹."""
    return math.exp(2 * math.sqrt(a_mass / 8.0 * max(u, 0.0))) if u > 0 else 0.0


def fission_barrier(z, a):
    """Barrière de fission de la goutte liquide (Cohen-Swiatecki), MeV."""
    i = (a - 2 * z) / a
    surface = 17.9439 * (1 - 1.7826 * i * i) * a ** (2 / 3)
    coulomb = 0.7053 * z * z / a ** (1 / 3)
    x = coulomb / (2 * surface)
    if x >= 1:
        return 0.0
    if x > 2 / 3:
        return 0.83 * (1 - x) ** 3 * surface
    return 0.38 * (0.75 - x) * surface


@lru_cache(maxsize=8192)
def exit_barrier(z, a, zr, ar):
    """Barrière (MeV) et ħω qu'une particule (z, a) doit franchir pour quitter le noyau (zr, ar)."""
    pair = Pair((z, a), (zr, ar))
    return pair.barrier, pair.hbar_omega


def transmission(z, a, zr, ar, eps):
    """Chance de sortir avec l'énergie eps : 1 pour un neutron, Hill-Wheeler pour une particule chargée."""
    if z == 0 or zr == 0:
        return np.ones_like(eps)
    barrier, hbar_omega = exit_barrier(z, a, zr, ar)
    return 1.0 / (1.0 + np.exp(np.minimum(700.0, 2 * math.pi * (barrier - eps) / hbar_omega)))


@lru_cache(maxsize=8192)
def _mass(z, a):
    return mass_u(z, a)[0]


GAMMA_WIDTH_MEV = 1e-7          # largeur radiative typique d'un noyau excité : ~0,1 eV


def fermi_density(a_mass, u):
    """Densité de niveaux du gaz de Fermi (MeV⁻¹) : √π/(12 a^¼ U^(5/4)) · exp(2√(aU)), a = A/8."""
    u = np.maximum(u, 0.5)
    level = a_mass / 8.0
    return math.sqrt(math.pi) / (12 * level ** 0.25 * u ** 1.25) * np.exp(2 * np.sqrt(level * u))


def decay_widths(z, a, excitation, exclude=None):
    """Largeurs (MeV) de chaque voie de désexcitation du noyau (z, a) porté à E* MeV.

    Particules (Weisskopf 1937) : Γ_x = g μ /(π² ħ² ρ_C(E*)) ∫ ε σ(ε) ρ_R(E* − S_x − ε) dε,
    σ(ε) = πR² T(ε). Fission (Bohr-Wheeler 1939) : Γ_f = 1/(2π ρ_C) ∫ ρ_selle(E* − B_f − ε) dε.
    Rayons γ : largeur typique de 0,1 eV."""
    m = _mass(z, a)
    if m is None or excitation <= 0:
        return []
    rho_c = fermi_density(a, excitation)
    widths = [{"kind": "gamma", "weight": GAMMA_WIDTH_MEV}]
    for name, ze, ae, spin_states in EJECTILES:
        zr, ar = z - ze, a - ae
        if zr < 0 or ar < max(1, zr) or (exclude and {(ze, ae), (zr, ar)} == exclude):
            continue
        mr, mx = _mass(zr, ar), _mass(ze, ae)
        if mr is None or mx is None:
            continue
        separation = (mr + mx - m) * MEV_PER_U
        top = excitation - separation
        if top <= 0:
            continue
        mu = mx * mr / (mx + mr) * MEV_PER_U
        radius = 1.2 * (ar ** (1 / 3) + ae ** (1 / 3))
        eps = np.linspace(top / 40, top, 40)
        spectrum = eps * math.pi * radius ** 2 * transmission(ze, ae, zr, ar, eps) * fermi_density(ar, top - eps)
        width = spin_states * mu / (math.pi ** 2 * HBAR_C ** 2 * rho_c) * float(spectrum.sum()) * top / 40
        if width > 0:
            widths.append({"kind": "particle", "name": name, "ejectile": (ze, ae), "residual": (zr, ar),
                           "separation": separation, "weight": width, "eps": eps, "spectrum": spectrum})
    if z >= 70:
        barrier = fission_barrier(z, a)
        top = excitation - barrier
        if top > 0.2:
            eps = np.linspace(0, top, 40)
            width = float(fermi_density(a, top - eps).sum()) * top / 40 / (2 * math.pi * rho_c)
            widths.append({"kind": "fission", "weight": width, "barrier": barrier})
    return widths


def fission(z, a, excitation, rng):
    """Le noyau se coupe en deux : énergie cinétique de Viola (1985), neutrons prompts, γ."""
    m = _mass(z, a)
    tke = 0.1189 * z * z / a ** (1 / 3) + 7.3
    z1 = z // 2 - int(rng.integers(0, 3))
    z2 = z - z1
    for neutrons in range(6, -1, -1):
        left = a - neutrons
        a1 = round(left * z1 / z)
        for shift in (0, 1, -1, 2, -2, 3, -3):
            b1, b2 = a1 + shift, left - a1 - shift
            m1, m2 = _mass(z1, b1), _mass(z2, b2)
            if m1 is None or m2 is None:
                continue
            total = (m - m1 - m2 - neutrons * NEUTRON_MASS_U) * MEV_PER_U + excitation
            spare = total - tke - 2.0 * neutrons
            if spare >= 0:
                return {"fragments": [(z1, b1), (z2, b2)], "neutrons": neutrons, "tke": tke, "gamma": spare}
    return None


def cascade(z, a, excitation, rng, exclude=None):
    """Le noyau composé se refroidit pas à pas : il évapore des particules (ou fissionne),
    jusqu'à ne plus pouvoir en émettre ; le reste part en rayons γ."""
    emitted = []
    gamma = 0.0
    for step in range(40):
        widths = decay_widths(z, a, excitation, exclude if step == 0 else None)
        total = sum(w["weight"] for w in widths)
        if total <= 0:
            gamma += max(excitation, 0.0)
            break
        pick = rng.uniform(0, total)
        for chosen in widths:
            pick -= chosen["weight"]
            if pick <= 0:
                break
        if chosen["kind"] == "gamma":
            gamma += excitation              # il se calme en rayonnant : plus rien ne sort
            break
        if chosen["kind"] == "fission":
            split = fission(z, a, excitation, rng)
            if split:
                return {"residual": None, "emitted": emitted, "gamma": gamma + split["gamma"], "fission": split}
            gamma += excitation
            break
        p = chosen["spectrum"] / chosen["spectrum"].sum()
        eps = float(rng.choice(chosen["eps"], p=p))
        emitted.append((*chosen["ejectile"], eps))
        excitation -= chosen["separation"] + eps
        z, a = chosen["residual"]
    return {"residual": (z, a), "emitted": emitted, "gamma": gamma, "fission": None}


def group_names(products):
    """« Ts-294 + 3 n + γ » : les produits identiques regroupés."""
    counts = {}
    for p in products:
        name = "γ" if p == "gamma" else "e⁺ + ν" if p == "e+" else label(*p)
        counts[name] = counts.get(name, 0) + 1
    ordered = sorted(counts.items(), key=lambda kv: (kv[0] in ("γ", "e⁺ + ν"), kv[0] in ("n", "p")))
    return " + ".join(f"{n} {name}" if n > 1 else name for name, n in ordered)


def outcome_products(outcome):
    """Les noyaux à la fin : résidu (ou fragments), particules évaporées, neutrons de fission."""
    nuclei = []
    if outcome["fission"]:
        nuclei += outcome["fission"]["fragments"] + [(0, 1)] * outcome["fission"]["neutrons"]
    else:
        nuclei.append(outcome["residual"])
    nuclei += [(z, a) for z, a, _ in outcome["emitted"]]
    return nuclei


def signature(outcome):
    counts = {}
    for z, a in outcome_products(outcome):
        counts[(z, a)] = counts.get((z, a), 0) + 1
    return tuple(sorted(counts.items(), key=lambda kv: (-kv[0][1], kv[0][0])))


def channels(a, b, energy, rng=None, samples=160):
    """Voies de sortie : [(produits, part, Q, source, faible, genre)].

    Réactions mesurées : leurs voies. Sinon, le noyau composé se désexcite
    (cascade d'évaporation, fission) : on tire `samples` cascades et on compte."""
    rng = rng or np.random.default_rng(7)
    key = tuple(sorted((a, b)))
    before = _mass(*a) + _mass(*b)
    if key in collider.MEASURED:
        options, source = collider.MEASURED[key]
        out = []
        for products, share in options:
            nuclei = [p for p in products if p]
            after = sum(_mass(*p) for p in nuclei)
            out.append(([*nuclei, "gamma"] if None in products else list(nuclei), share,
                        (before - after) * MEV_PER_U, source, False, "measured"))
        return out
    zc, ac = a[0] + b[0], a[1] + b[1]
    if zc > HEAVIEST_KNOWN_Z:
        return []                   # aucun noyau aussi lourd n'existe : ils se séparent (quasi-fission)
    mc = _mass(zc, ac)
    excitation = (before - mc) * MEV_PER_U + energy if mc is not None else -1
    if excitation > 0:
        counts = {}
        for _ in range(samples):
            outcome = cascade(zc, ac, excitation, rng, exclude={a, b})
            sig = signature(outcome)
            counts.setdefault(sig, [0, outcome])[0] += 1
        out = []
        for sig, (count, outcome) in sorted(counts.items(), key=lambda kv: -kv[1][0]):
            nuclei = outcome_products(outcome)
            q = (before - sum(_mass(*p) for p in nuclei)) * MEV_PER_U
            kind = "fission" if outcome["fission"] else ("capture" if not outcome["emitted"] else "cascade")
            source = (f"noyau composé {label(zc, ac)} (E* = {fr(excitation)} MeV) : "
                      + ("il fissionne (Bohr-Wheeler, goutte liquide)" if kind == "fission" else
                         "aucune particule ne peut sortir : il se calme en émettant des γ (capture radiative)"
                         if kind == "capture" else "il évapore des particules (modèle de Weisskopf)"))
            products = nuclei + (["gamma"] if kind == "capture" else [])
            out.append((products, count / samples, q, source, False, kind))
        return out[:6]
    weak = (zc - 1, ac)
    mw = _mass(*weak)
    if mw is not None:
        q = (before - mw) * MEV_PER_U - 2 * collider.ELECTRON_MEV
        if q > 0:
            return [([weak, "e+"], 1.0, q, "seule l'interaction faible le permet : un proton devient "
                     "neutron (p → n + e⁺ + ν)", True, "weak")]
    return []


# ======================================================================
# L'atome : nuage électronique tiré de |ψ|²
# ======================================================================
def _real_harmonic(l, k, x, y, z):
    """Harmoniques sphériques réelles (forme et signe de l'onde), sur la sphère unité."""
    table = {
        (0, 0): 0.28209479,
        (1, 0): 0.48860251 * z, (1, 1): 0.48860251 * x, (1, 2): 0.48860251 * y,
        (2, 0): 0.31539157 * (3 * z * z - 1), (2, 1): 1.09254843 * x * z, (2, 2): 1.09254843 * y * z,
        (2, 3): 0.54627422 * (x * x - y * y), (2, 4): 1.09254843 * x * y,
        (3, 0): 0.37317633 * z * (5 * z * z - 3), (3, 1): 0.45704580 * x * (5 * z * z - 1),
        (3, 2): 0.45704580 * y * (5 * z * z - 1), (3, 3): 1.44530572 * z * (x * x - y * y),
        (3, 4): 2.89061144 * x * y * z, (3, 5): 0.59004359 * x * (x * x - 3 * y * y),
        (3, 6): 0.59004359 * y * (3 * x * x - y * y),
    }
    return table[(l, k)]


def electron_cloud(z, total_points=1500, seed=1):
    """Points tirés au sort selon |ψ|² pour chaque électron de l'atome neutre.

    Retourne (positions en pm, signe de l'onde, indice de sous-couche, étiquettes, rayon utile)."""
    rng = np.random.default_rng(seed + z)
    subshells = subshell_configuration(z)
    occupied = [(n, l, count) for n, l, count in subshells]
    positions, signs, shells, labels = [], [], [], []
    weights = [count for _, _, count in occupied]
    per_electron = total_points / max(1, sum(weights))
    radius_99 = 0.0
    for index, (n, l, count) in enumerate(occupied):
        z_eff = effective_charge(z, subshells, n, l)
        labels.append(f"{n}{'spdf'[l]}{count}")
        # Distribution radiale r² R² sur une grille, tirage par fonction de répartition inverse.
        r_max = 52.9177 / z_eff * (3 * n * n) * 2.2
        grid = np.linspace(1e-3, r_max, 1500)
        density = np.array([(g * radial(n, l, z_eff, g)) ** 2 for g in grid])
        cdf = np.cumsum(density)
        cdf /= cdf[-1]
        # Slater : au-delà de n = 3, l'exposant radial utilise n* (3,7 ; 4,0 ; 4,2) et non n.
        shrink = n_star(n) / n
        radius_99 = max(radius_99, float(grid[np.searchsorted(cdf, 0.97)]) * shrink)
        occupation = hund_occupation(l, count)
        for k, electrons in enumerate(occupation):
            if electrons == 0:
                continue
            wanted = max(36, int(per_electron * electrons * (1 + 0.6 * n) / 2))
            got = 0
            while got < wanted:
                batch = 4 * wanted
                v = rng.normal(size=(batch, 3))
                v /= np.linalg.norm(v, axis=1)[:, None]
                y_values = np.array([_real_harmonic(l, k, *p) for p in v])
                peak = 0.6 if l == 0 else np.max(np.abs(y_values)) + 1e-9
                keep = rng.random(batch) < (y_values ** 2) / (peak ** 2)
                radii = np.interp(rng.random(int(keep.sum())), cdf, grid) * shrink
                for direction, value, r in zip(v[keep], y_values[keep], radii):
                    if got >= wanted:
                        break
                    sign = 1 if value >= 0 else -1
                    # Le signe de R_nl change à chaque nœud radial : il fait partie de l'onde.
                    sign *= 1 if radial(n, l, z_eff, r / shrink) >= 0 else -1
                    positions.append(direction * r)
                    signs.append(sign)
                    shells.append(index)
                    got += 1
    return np.array(positions), signs, shells, labels, radius_99


# ======================================================================
# Le laboratoire
# ======================================================================
class FusionLab:
    def __init__(self, rng_seed=None):
        self.rng = np.random.default_rng(rng_seed)
        self.a = (1, 2)
        self.b = (1, 3)
        self.energy_kev = 20.0
        self.impact_fm = 0.0
        self.thermal = True
        self._thermal_key = None
        self._thermal = None
        self.result = None
        self.run = 0
        self.catalog_sent = False
        self._pair = None
        self._pair_key = None
        self._channels_key = None
        self._channels = None
        self._clouds = {}

    # --- réglages ----------------------------------------------------------------
    def set_nucleus(self, which, code):
        z, a = code // 1000, code % 1000
        if mass_u(z, a)[0] is None or z < 1:
            return
        if which == "a":
            self.a = (z, a)
        else:
            self.b = (z, a)
        self.result = None

    def set_energy(self, kev):
        self.energy_kev = min(5e5, max(0.1, kev))
        self.result = None

    def set_thermal(self, on):
        self.thermal = bool(on)
        self.result = None

    def set_impact(self, fm):
        self.impact_fm = max(0.0, min(40.0, fm))
        self.result = None

    # --- calculs mis en cache ------------------------------------------------------
    def pair(self):
        key = (self.a, self.b)
        if key != self._pair_key:
            self._pair = Pair(self.a, self.b)
            self._pair_key = key
        return self._pair

    def options(self):
        key = (self.a, self.b, round(self.energy_kev, 3))
        if key != self._channels_key:
            self._channels = channels(self.a, self.b, self.energy_kev / 1000, rng=np.random.default_rng(11))
            self._channels_key = key
        return self._channels

    def cloud(self, z):
        if z not in self._clouds:
            self._clouds[z] = electron_cloud(z)
        return self._clouds[z]

    def compound(self):
        return self.a[0] + self.b[0], self.a[1] + self.b[1]

    def threshold_mev(self):
        """Énergie minimale pour former le noyau composé, si la fusion en coûte (Q < 0)."""
        if self.compound()[0] > HEAVIEST_KNOWN_Z:
            return None
        mc = _mass(*self.compound())
        if mc is None:
            return None
        q = (_mass(*self.a) + _mass(*self.b) - mc) * MEV_PER_U
        return -q if q < 0 else None

    @staticmethod
    def gamma(nucleus):
        """Rapport gyromagnétique γ = μ / (ħ I), en rad/s/T (0 sans spin)."""
        spin = nuclear_data.spin_parity(*nucleus).strip("()+- ")
        try:
            value = float(spin.split("/")[0]) / float(spin.split("/")[1]) if "/" in spin else float(spin)
        except ValueError:
            return 0.0
        if value <= 0:
            return 0.0
        return magnetic_moment(*nucleus) * MU_N_J_T / (sc.hbar * value)

    def angular_momentum(self):
        """l ≈ μ v b / ħ : le moment cinétique apporté par une visée décalée."""
        pair = self.pair()
        p = math.sqrt(2 * pair.mu * self.energy_kev / 1000)           # MeV/c
        return int(round(p * self.impact_fm / HBAR_C))

    def tunneling(self):
        return self.pair().tunneling(self.energy_kev / 1000, self.angular_momentum())

    def thermal_table(self):
        """Plasma à kT = énergie réglée : chocs distribués selon E·e^(−E/kT) (Maxwell-Boltzmann,
        pondéré par le flux). Retourne (E, P(E), f(E), P moyen par choc frontal)."""
        key = (self.a, self.b, round(self.energy_kev, 4))
        if key != self._thermal_key:
            kt = self.energy_kev / 1000
            pair = self.pair()
            energies = np.geomspace(kt * 0.02, kt * 40, 64)
            passing = np.array([pair.tunneling(e) for e in energies])
            flux = energies * np.exp(-energies / kt)
            mean = float(np.trapezoid(flux * passing, energies) / np.trapezoid(flux, energies))
            self._thermal = (energies, passing, flux, mean)
            self._thermal_key = key
        return self._thermal

    def gamow_peak_mev(self):
        """Énergie la plus efficace d'un plasma : E₀ = (π α Z₁Z₂ kT √(μc²/2))^(2/3)."""
        pair = self.pair()
        kt = self.energy_kev / 1000
        return (math.pi * sc.fine_structure * pair.zz * kt * math.sqrt(pair.mu / 2)) ** (2 / 3) if pair.zz else kt

    def probability(self):
        options = self.options()
        quasi = not options and self.compound()[0] > HEAVIEST_KNOWN_Z
        if not options and not quasi:
            return 0.0
        weak = bool(options) and options[0][4]
        passing = self.thermal_table()[3] if self.thermal else self.tunneling()
        return passing * (collider.WEAK_FACTOR if weak else 1.0)

    def _sample_shot(self, until_fusion):
        """L'énergie et la visée de ce choc (MeV, fm)."""
        pair = self.pair()
        if not self.thermal:
            # Faisceau : énergie fixe. Sa largeur (bien plus grande qu'un noyau) rend la visée aléatoire,
            # sauf si l'expert la fixe.
            energy = self.energy_kev / 1000
            if self.impact_fm > 0:
                return energy, self.impact_fm
            if until_fusion:
                wavelength = HBAR_C / math.sqrt(2 * pair.mu * energy)
                return energy, math.sqrt(self.rng.random()) * min(wavelength, pair.contact)
            reach = max(pair.turning_point(energy) if pair.zz else pair.contact, pair.barrier_r)
            return energy, math.sqrt(self.rng.random()) * min(reach, 40.0)
        kt = self.energy_kev / 1000
        if until_fusion:
            # Le choc qui réussit : tiré parmi ceux qui passent, f(E)·P(E) — les plus rapides.
            energies, passing, flux, _ = self.thermal_table()
            weight = flux * passing
            cdf = np.concatenate([[0], np.cumsum((weight[1:] + weight[:-1]) / 2 * np.diff(energies))])
            if cdf[-1] <= 0:
                energy = float(self.rng.gamma(2, kt))
            else:
                energy = float(np.interp(self.rng.random() * cdf[-1], cdf, energies))
            wavelength = HBAR_C / math.sqrt(2 * pair.mu * energy)
            return energy, math.sqrt(self.rng.random()) * min(wavelength, pair.contact)
        energy = float(self.rng.gamma(2, kt))
        reach = max(pair.turning_point(energy) if pair.zz else pair.contact, pair.barrier_r)
        return energy, math.sqrt(self.rng.random()) * min(reach, 40.0)

    # --- le tir ------------------------------------------------------------------------
    def fire(self, until_fusion):
        self.run += 1
        pair = self.pair()
        options = self.options()
        probability = self.probability()
        energy, impact = self._sample_shot(until_fusion)
        if not until_fusion and options:
            # Ce choc précis : sa propre chance de passer (énergie et moment cinétique tirés).
            p = math.sqrt(2 * pair.mu * energy)
            l = int(round(p * impact / HBAR_C))
            probability = pair.tunneling(energy, l) * (collider.WEAK_FACTOR if options[0][4] else 1.0)
        r = {"run": self.run, "fused": False, "never": False, "attempts": 1, "reason": "", "products": [],
             "chain": [], "q": 0.0, "source": options[0][3] if options else "", "weak": bool(options and options[0][4]),
             "probability": probability, "mass_before": mass_u(*self.a)[0] + mass_u(*self.b)[0], "mass_after": 0.0}
        quasi = not options and self.compound()[0] > HEAVIEST_KNOWN_Z
        if quasi:
            # Au-delà de 118 protons, aucun noyau ne tient : s'ils se touchent, ils se séparent (quasi-fission).
            touch = self.probability() if until_fusion else probability
            if until_fusion and touch < 1e-15:
                r["never"] = True
                r["reason"] = (f"Ils ne se toucheront jamais : il faudrait {fr(1 / max(touch, 1e-300))} essais. "
                               f"Et même au contact, un noyau de {self.compound()[0]} protons n'existe pas.")
            else:
                if until_fusion:
                    r["attempts"] = int(self.rng.geometric(touch)) if touch < 1 else 1
                r["quasi"] = bool(until_fusion or self.rng.random() < touch)
        elif not options:
            r["never"] = True
            r["reason"] = "Aucune voie possible : quoi qu'il arrive, les produits seraient plus lourds que les noyaux de départ."
        elif until_fusion:
            if probability < 1e-15:
                r["never"] = True
                r["reason"] = (f"Il faudrait en moyenne {fr(1 / max(probability, 1e-300))} essais : en pratique, jamais."
                               + (" " + options[0][3][0].upper() + options[0][3][1:] + "." if r["weak"] else ""))
            else:
                r["attempts"] = int(self.rng.geometric(probability)) if probability < 1 else 1
                r["fused"] = True
        else:
            r["fused"] = bool(self.rng.random() < probability)
        above = energy >= pair.barrier
        contact = r["fused"] or r.get("quasi", False)
        tunnel = contact and not above
        # Renvoyé alors qu'il passait au-dessus : réflexion quantique, montrée comme un demi-tour au sommet.
        # Le moment cinétique de ce choc : une visée décalée l'empêche d'atteindre le contact (barrière
        # centrifuge) — il passe à côté, dévié. La réflexion quantique ne concerne que le choc frontal.
        orbital = int(round(math.sqrt(2 * pair.mu * max(energy, 1e-12)) * impact / HBAR_C))
        reflected = not contact and above and not r["never"] and orbital <= 1
        r["deflected"] = not contact and not r["never"] and not reflected and orbital > 1
        r["orbital"] = orbital
        shot_energy = pair.barrier * 0.995 if reflected else energy
        used_impact = impact if not contact else min(impact, pair.contact * 0.3)
        run = pair.trajectory_full(shot_energy, used_impact, tunnel, magnetic_moment(*self.b), self.gamma(self.a),
                                   stop_at_contact=contact)
        r.update({"path": run["points"], "tunnel_index": run["jump"], "closest": run["closest"],
                  "duration_s": run["duration"], "above": above, "reflected": reflected,
                  "shot_energy": energy, "impact": used_impact, "lorentz_max": run["lorentz_max"],
                  "deflection": run["deflection"], "precession": run["precession"]})
        # Les autres chocs du plasma : quand il a fallu de nombreux essais, un échantillon de ceux qui ont
        # échoué (énergie et visée tirées comme les vrais), calculés eux aussi : l'éventail de Rutherford.
        r["ghosts"] = self._failed_sample(min(r["attempts"] - 1, 7)) if r["attempts"] > 1 else []
        spin = self.rng.normal(size=3)
        r["spin_axis"] = (spin / np.linalg.norm(spin)).tolist()
        if r["fused"] and self.thermal and abs(energy - self.energy_kev / 1000) > 1e-9:
            # Les voies de sortie dépendent de l'énergie de ce choc-là.
            options = channels(self.a, self.b, energy, rng=np.random.default_rng(11)) or options
        if r.get("quasi"):
            r["kind"] = "quasifission"
            r["products"] = self._kinematics([self.a, self.b], energy)
            r["mass_after"] = r["mass_before"]
        if r["fused"]:
            pick = self.rng.uniform(0, sum(option[1] for option in options))
            for products, share, q, source, weak, kind in options:
                pick -= share
                if pick <= 0:
                    break
            if kind in ("cascade", "fission", "capture"):
                # Le noyau composé se désexcite pour de bon : une cascade tirée au sort.
                zc, ac = self.a[0] + self.b[0], self.a[1] + self.b[1]
                excitation = (r["mass_before"] - _mass(zc, ac)) * MEV_PER_U + energy
                outcome = cascade(zc, ac, excitation, self.rng, exclude={self.a, self.b})
                products = outcome_products(outcome)
                q = (r["mass_before"] - sum(_mass(*n) for n in products)) * MEV_PER_U
                kind = "fission" if outcome["fission"] else ("capture" if not outcome["emitted"] else "cascade")
                r["products"] = self._cascade_kinematics(outcome)
                r["excitation"] = excitation
            else:
                r["products"] = self._kinematics(products, q + energy)
            r.update({"q": q, "source": source, "weak": weak, "kind": kind})
            r["chain"] = collider.Collision._chain(r["products"])
            r["mass_after"] = sum(mass_u(p["z"], p["a"])[0] for p in r["products"]
                                  if p["kind"] in ("nucleus", "neutron"))
        self.result = r
        return self._events(r)

    def _kinematics(self, products, available):
        """Énergie partagée selon l'impulsion ; directions tirées au hasard dans l'espace."""
        nuclei = [p for p in products if isinstance(p, tuple)]
        out = []
        direction = self.rng.normal(size=3)
        direction /= np.linalg.norm(direction)
        if "gamma" in products or "e+" in products:
            kind = "gamma" if "gamma" in products else "positron"
            for z, a in nuclei:
                out.append({"z": z, "a": a, "kinetic": 0.0, "speed_c": 0.0, "kind": "nucleus",
                            "dir": (-direction).tolist()})
            out.append({"z": 0, "a": 0, "kinetic": max(available, 0.0), "speed_c": 1.0, "kind": kind,
                        "dir": direction.tolist()})
            return out
        extra = nuclei[2:]
        spent = 0.0
        for z, a in extra:                           # neutrons prompts de fission : ~2 MeV chacun
            d = self.rng.normal(size=3)
            d /= np.linalg.norm(d)
            out.append(self._particle(z, a, 2.0, d))
            spent += 2.0
        first, second = nuclei[0], nuclei[1]
        m1, m2 = mass_u(*first)[0], mass_u(*second)[0]
        share = max(available - spent, 0.0)
        out.insert(0, self._particle(*first, share * m2 / (m1 + m2), direction))
        out.insert(1, self._particle(*second, share * m1 / (m1 + m2), -direction))
        return out

    def _failed_sample(self, count):
        pair = self.pair()
        kt = self.energy_kev / 1000
        out = []
        for _ in range(count):
            energy = float(self.rng.gamma(2, kt)) if self.thermal else kt
            reach = max(pair.turning_point(energy) if pair.zz else pair.contact, pair.barrier_r)
            impact = math.sqrt(self.rng.random()) * min(reach * 1.5, 40.0)
            sign = 1 if self.rng.random() < 0.5 else -1
            run = pair.trajectory_full(energy, impact, False)
            points = run["points"][::5]
            out.append([(t, x, sign * y) for t, x, y in points])
        return out

    def _direction(self):
        d = self.rng.normal(size=3)
        return d / np.linalg.norm(d)

    def _cascade_kinematics(self, outcome):
        """Particules évaporées (leur énergie tirée du spectre), fragments de fission, recul, γ."""
        out = []
        momentum = np.zeros(3)
        for z, a, eps in outcome["emitted"]:
            d = self._direction()
            out.append(self._particle(z, a, eps, d))
            momentum += d * math.sqrt(2 * _mass(z, a) * MEV_PER_U * eps)
        gamma = outcome["gamma"]
        if outcome["fission"]:
            split = outcome["fission"]
            (z1, a1), (z2, a2) = split["fragments"]
            m1, m2 = _mass(z1, a1), _mass(z2, a2)
            d = self._direction()
            out.insert(0, self._particle(z1, a1, split["tke"] * m2 / (m1 + m2), d))
            out.insert(1, self._particle(z2, a2, split["tke"] * m1 / (m1 + m2), -d))
            for _ in range(split["neutrons"]):
                out.append(self._particle(0, 1, 2.0, self._direction()))
        else:
            z, a = outcome["residual"]
            recoil = float(momentum @ momentum) / (2 * _mass(z, a) * MEV_PER_U)
            direction = -momentum / max(np.linalg.norm(momentum), 1e-12) if recoil > 0 else self._direction()
            out.insert(0, self._particle(z, a, recoil, direction))
            gamma = max(gamma - recoil, 0.0)
        if gamma > 0.01:
            out.append({"z": 0, "a": 0, "kinetic": gamma, "speed_c": 1.0, "kind": "gamma",
                        "dir": self._direction().tolist()})
        return out

    @staticmethod
    def _particle(z, a, kinetic, direction):
        rest = mass_u(z, a)[0] * MEV_PER_U
        gamma = 1 + kinetic / rest
        return {"z": z, "a": a, "kinetic": kinetic, "speed_c": math.sqrt(1 - 1 / gamma ** 2),
                "kind": "neutron" if z == 0 else "nucleus", "dir": list(direction)}

    def _events(self, r):
        a, b = label(*self.a), label(*self.b)
        if r["never"]:
            return [{"kind": "collision", "title": f"{a} + {b} : pas de fusion", "formula": "", "detail": r["reason"]}]
        if not r["fused"]:
            return [{"kind": "collision", "title": f"{a} + {b} : rebond",
                     "formula": f"plus courte distance {fr(r['closest'])} fm", "detail": "La répulsion a gagné."}]
        products = group_names([("gamma" if p["kind"] == "gamma" else "e+" if p["kind"] == "positron"
                                  else (p["z"], p["a"])) for p in r["products"]])
        return [{"kind": "fusion", "title": f"Fusion : {a} + {b} → {products}",
                 "formula": f"Q = Δm·c² = {fr(r['q'])} MeV", "detail": r["source"]}]

    @staticmethod
    def product_label(p):
        if p["kind"] == "gamma":
            return "γ"
        if p["kind"] == "positron":
            return "e⁺ + ν"
        return label(p["z"], p["a"])

    # --- en mots : verdict, jauges, images parlantes ---------------------------------
    def preview(self):
        """Ce que donnerait la fusion, avant de tirer."""
        options = self.options()
        if not options and self.compound()[0] > HEAVIEST_KNOWN_Z:
            return (f"{label(*self.a)} + {label(*self.b)} : un noyau de {self.compound()[0]} protons n'existe pas "
                    f"(le plus lourd connu en a 118) ; ils se toucheraient puis se sépareraient")
        if not options:
            threshold = self.threshold_mev()
            if threshold is not None:
                return (f"{label(*self.a)} + {label(*self.b)} → {label(*self.compound())} : la fusion coûte "
                        f"{fr(threshold)} MeV ; lance plus fort")
            return "aucune fusion possible avec ces deux-là"
        products, share, q, source, weak, kind = options[0]
        names = group_names(products)
        sign = "+" if q >= 0 else ""
        return f"{label(*self.a)} + {label(*self.b)} → {names} · {sign}{fr(q)} MeV"

    def energy_verdict(self):
        pair = self.pair()
        p = self.probability()
        if not self.options() and self.compound()[0] > HEAVIEST_KNOWN_Z:
            return "impossible", (f"impossible : au-delà de 118 protons, aucun noyau ne tient ; ils se séparent "
                                  f"aussitôt (quasi-fission)")
        if not self.options():
            threshold = self.threshold_mev()
            if threshold is not None:
                return "impossible", (f"trop faible : former {label(*self.compound())} coûte {fr(threshold)} MeV "
                                      f"(Δm·c² négatif) ; il faut au moins cette énergie")
            return "impossible", "aucune réaction : les produits seraient plus lourds"
        if self.energy_kev / 1000 >= pair.barrier:
            return "au-dessus", "assez d'énergie pour passer par-dessus la colline"
        if p > 1e-3:
            return "tunnel", "sous le sommet, mais l'effet tunnel passe souvent"
        if p > 1e-12:
            return "rare", "sous le sommet : il faudra beaucoup d'essais (effet tunnel)"
        return "impossible", "beaucoup trop faible : rebond quasi certain"

    def interpretations(self):
        """(grandeur, valeur, ce que ça veut dire), pour un profane."""
        pair = self.pair()
        e = self.energy_kev / 1000
        out = []
        if pair.zz:
            t_barrier = pair.barrier * 1e3 * KEV_TO_K
            out.append(("Hauteur de la colline", energy_text(pair.barrier * 1000),
                        f"la répulsion entre +{self.a[0]} et +{self.b[0]} ; pour passer par-dessus sans effet tunnel, "
                        f"il faudrait chauffer à {temperature_text(t_barrier)}, "
                        f"{fr(t_barrier / SUN_CORE_K)} fois le cœur du Soleil"))
        out.append(("Énergie du tir", energy_text(self.energy_kev),
                    f"l'agitation d'un gaz à {temperature_text(self.energy_kev * KEV_TO_K)}"
                    + (f" : {fr(100 * e / pair.barrier)} % de la hauteur de la colline" if pair.zz else "")))
        p = self.probability()
        if 0 < p < 1:
            out.append(("Chance de fusion par tir", f"1 sur {fr(1 / p)}", chance_meaning(p)))
        r = self.result
        if r and r.get("shot_energy") is not None and self.thermal:
            peak = self.gamow_peak_mev()
            out.append(("Énergie de ce choc", energy_text(r["shot_energy"] * 1000),
                        f"tirée au hasard dans le plasma (Maxwell-Boltzmann à kT = {energy_text(self.energy_kev)}). "
                        + ("Les chocs qui fusionnent sont les plus rapides : la plupart autour de "
                           f"{energy_text(peak * 1000)} (pic de Gamow)." if r["fused"] else
                           "Chaque choc a son énergie et sa visée : certains passent, la plupart rebondissent.")))
        if r and r.get("lorentz_max"):
            out.append(("Force de Lorentz q·v×B", f"{fr(r['lorentz_max'])} N",
                        f"le champ magnétique de B courbe la trajectoire de A, mais de {fr(r['deflection'])} fm "
                        "seulement : la répulsion électrique domine tout"))
        if r and r.get("precession"):
            out.append(("Précession du spin de A", f"{fr(math.degrees(r['precession']))}°",
                        "équation de Bloch : le spin tourne autour du champ de B, mais le choc est si bref "
                        f"({fr(r['duration_s'])} s) qu'il n'a pas le temps de faire un tour"))
        if r and r["fused"] and r["q"] > 0:
            q = r["q"]
            out.append(("Énergie d'une seule fusion", f"{fr(q)} MeV",
                        f"{fr(q * 1e6 / METHANE_EV)} fois l'énergie d'une réaction chimique "
                        f"(une molécule de méthane qui brûle : 9,2 eV)"))
            fuel_g = r["mass_before"] * sc.atomic_mass * 1e3            # masse du couple, en g
            per_gram = q * 1e6 * sc.e / fuel_g
            out.append(("1 gramme de ce mélange", f"{fr(per_gram / 1e9)} GJ",
                        f"{fr(per_gram / TOE_J)} tonnes de pétrole, ou {fr(per_gram / TNT_TON_J)} tonnes de TNT"))
            out.append(("En Soleils", f"{fr(SUN_LUMINOSITY_W / (q * 1e6 * sc.e))} fusions/s",
                        "le nombre de fusions comme celle-ci qu'il faudrait chaque seconde pour briller "
                        "comme le Soleil"))
            lost = (r["mass_before"] - r["mass_after"]) / r["mass_before"]
            kwh = lost * sc.c ** 2 / 3.6e6                    # pour 1 kg de mélange
            out.append(("Masse disparue", f"{fr(100 * lost)} %",
                        f"ça paraît peu, mais E = m·c² : sur 1 kg de mélange, {fr(lost * 1000)} g disparaissent et "
                        f"deviennent {fr(kwh / 1e6)} millions de kWh, l'électricité d'un foyer français pendant "
                        f"{fr(kwh / HOME_KWH_YEAR)} ans. Une réaction chimique ne fait disparaître qu'un milliardième "
                        f"de la masse."))
            fastest = max(r["products"], key=lambda p: p["speed_c"])
            if fastest["kind"] in ("nucleus", "neutron") and fastest["speed_c"] > 0:
                v = fastest["speed_c"] * sc.c
                out.append((f"Vitesse : {full_name(fastest['z'], fastest['a'])}", f"{fr(100 * fastest['speed_c'])} % de c",
                            f"il ferait le tour de la Terre en {fr(EARTH_TOUR_M / v)} s"))
        if r and r.get("duration_s"):
            out.append(("Durée du choc", f"{fr(r['duration_s'])} s",
                        f"en une seconde, il y aurait la place pour {fr(1 / r['duration_s'])} chocs comme celui-ci"))
        cloud_radius = self.cloud(self.b[0])[4]
        out.append(("Taille", f"noyau {fr(nuclear_radius(self.b[1]))} fm · atome {fr(cloud_radius)} pm",
                    f"si le noyau était une bille de 1 cm, l'atome ferait {fr(cloud_radius * 1e3 / nuclear_radius(self.b[1]) / 100)} m"))
        return out

    def verdict(self):
        """Le gros titre, après un tir."""
        r = self.result
        if r is None:
            return []
        a, b = label(*self.a), label(*self.b)
        if r["never"]:
            return ["IMPOSSIBLE EN PRATIQUE", r["reason"]]
        if r.get("quasi"):
            return ["QUASI-FISSION", f"{a} et {b} se sont touchés, mais aucun noyau de {self.compound()[0]} protons "
                    "ne tient (le plus lourd connu en a 118) : la répulsion de leurs charges les a séparés aussitôt.",
                    "rien de nouveau n'est né"]
        if r.get("reflected"):
            return ["RENVOYÉ", f"{a} avait assez d'énergie pour franchir le sommet, mais un noyau est aussi une onde : "
                    f"au sommet, une partie de l'onde est réfléchie (ici {fr(100 * (1 - r['probability']))} % du temps). "
                    "Monte encore l'énergie, ou insiste."]
        if r.get("deflected"):
            return ["DÉVIÉ", f"{a} visait à {fr(r['impact'])} fm du centre de {b} : avec ce décalage (moment cinétique "
                    f"l = {r['orbital']}), il n'approche qu'à {fr(r['closest'])} fm et passe à côté, dévié par la "
                    "répulsion (trajectoire hyperbolique). Dans un plasma, la plupart des chocs sont ainsi."]
        if not r["fused"]:
            return ["REBOND", f"{a} a approché jusqu'à {fr(r['closest'])} fm ; son élan épuisé, la répulsion "
                    "l'a renvoyé. Insiste (effet tunnel), ou monte l'énergie."]
        products = group_names([("gamma" if p["kind"] == "gamma" else "e+" if p["kind"] == "positron"
                                  else (p["z"], p["a"])) for p in r["products"]])
        born = sorted({label(p["z"], p["a"]) for p in r["products"] if p["kind"] == "nucleus"})
        lines = ["FUSION !", f"{a} + {b} → {products}"]
        if r["q"] >= 0:
            lines.append(f"+{fr(r['q'])} MeV libérés · {', '.join(born)} {'est né' if len(born) == 1 else 'sont nés'}")
        else:
            lines.append(f"{fr(r['q'])} MeV : cette fusion coûte de l'énergie, prise sur le tir")
        if r["attempts"] > 1:
            lines.append(f"il a fallu {fr(r['attempts'])} tirs" + (" (effet tunnel)" if not r["above"] else ""))
        elif not r["above"]:
            lines.append("passé à travers la colline : effet tunnel")
        return lines

    def expert(self):
        """Le détail technique, pour le mode expert."""
        pair = self.pair()
        e = self.energy_kev / 1000
        lines = [
            (f"Plasma : kT = {energy_text(self.energy_kev)}, énergies tirées selon E·e^(−E/kT) ; pic de Gamow "
             f"{energy_text(self.gamow_peak_mev() * 1000)} ; P moyen par choc frontal {fr(self.thermal_table()[3])}"
             if self.thermal else f"Faisceau : énergie fixe {energy_text(self.energy_kev)}"),
            f"Potentiel : V(r) = Z₁Z₂·1,44/r − V₀/(1+e^((r−R₁−R₂)/a)) · V₀ = {fr(pair.depth)} MeV, "
            f"R₁ + R₂ = {fr(pair.contact)} fm, a = 0,63 fm (Akyüz-Winther)",
            f"Barrière : {fr(pair.barrier * 1000)} keV à r = {fr(pair.barrier_r)} fm · ħω = {fr(pair.hbar_omega)} MeV",
            f"Masse réduite μ = {fr(pair.mu)} MeV/c² · visée b = {fr(self.impact_fm)} fm → l = {self.angular_momentum()}",
            f"Effet tunnel (WKB) : P = 1/(1+e^(2K)) = {fr(self.tunneling())}",
        ]
        if pair.zz and e < pair.barrier:
            lines.append(f"Point de rebroussement : {fr(pair.turning_point(e))} fm")
        for products, share, q, source, weak, kind in self.options():
            names = group_names(products)
            lines.append(f"Voie {names} : {fr(100 * share)} % · Q = {fr(q)} MeV")
        if self.options():
            lines.append(f"Source : {self.options()[0][3]}")
        for z, a in (self.a, self.b):
            m, estimated = mass_u(z, a)
            lines.append(f"{label(z, a)} : {m:.6f} u{' (estimée, Weizsäcker)' if estimated else ' (AME)'} · "
                         f"spin {nuclear_data.spin_parity(z, a) or '?'}")
        return lines

    def describe(self, z, a):
        m, estimated = mass_u(z, a)
        data = nuclear_data.info(z, a)
        if data is None or data["stable"]:
            status = "stable" if data is not None or nuclear_data.abundance(z, a) > 0 else "jamais observé"
        elif data["half_life_s"]:
            status = f"radioactif, demi-vie {format_duration(data['half_life_s'])}"
        else:
            status = "instable"
        return {"z": z, "a": a, "label": label(z, a), "name": full_name(z, a),
                "symbol": nuclear_data.SYMBOLS.get(z, "n"), "mass_u": m or 0.0, "estimated": estimated,
                "spin": nuclear_data.spin_parity(z, a), "status": status, "radius_fm": nuclear_radius(a),
                "color": element_color(z), "magnetic": magnetic_moment(z, a)}
