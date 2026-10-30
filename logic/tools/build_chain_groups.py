"""Construit la bibliothèque multigroupe du laboratoire de la réaction en chaîne, depuis ENDF/B-VIII.0.

    python tools/build_chain_groups.py <dossier des fichiers HDF5> [noyau ...]

Les fichiers HDF5 sont ceux d'OpenMC (ENDF/B-VIII.0 traité par NJOY, résonances reconstruites et élargies à
294 K), tels que les publie le dépôt openmc-data-storage/ENDF-B-VIII.0-NNDC. L'outil a besoin de h5py ; le moteur,
lui, ne lit que le fichier produit, chainlab/data/groups.npz.

Pour chaque noyau, et pour 44 groupes d'énergie (20 MeV → 10⁻⁵ eV) :
  - les sections efficaces moyennes du groupe, pondérées par le spectre W(E) / (σ_t(E) + σ₀) pour une gamme de
    sections de dilution σ₀ (méthode de Bondarenko : dans un métal pur, le flux se creuse au cœur des résonances
    et les captures résonnantes s'en trouvent réduites) ;
  - les matrices de transfert g → g' de la diffusion élastique et des autres réactions qui rendent des neutrons
    ((n,n'), (n,2n)...), obtenues en tirant au hasard les angles et les énergies de sortie dans les distributions
    évaluées, cinématique exacte à deux corps et agitation thermique de la cible (gaz libre à 294 K) ;
  - le cosinus moyen de déviation dans le laboratoire (correction de transport) ;
  - le spectre des neutrons de fission χ (prompts et retardés) selon le groupe du neutron incident ;
  - ν total, ν prompt et les rendements des groupes de précurseurs de neutrons retardés avec leurs constantes
    de décroissance ;
  - le partage de l'énergie de fission (ENDF MT 458).
"""

import math
import sys
from pathlib import Path

import h5py
import numpy as np

HERE = Path(__file__).resolve().parent
OUTPUT = HERE.parent / "chainlab" / "data" / "groups.npz"
TEMPERATURE = "294K"
KT_EV = 8.617333262e-5 * 293.6
NEUTRON_MASS_EV = 939.56542052e6
SIGMA0 = np.array([1.0, 3.0, 10.0, 30.0, 100.0, 300.0, 1e3, 1e4, 1e5, 1e10])
SAMPLES = 4000
FISSION_MTS = (18, 19, 20, 21, 38)


def group_bounds():
    """Bornes des 44 groupes, en eV, décroissantes."""
    fast = np.geomspace(2e7, 1e5, 22)
    middle = np.geomspace(1e5, 1.0, 16)[1:]
    thermal = np.array([0.625, 0.35, 0.18, 0.1, 0.05, 0.025, 0.01, 1e-5])
    return np.concatenate([fast, middle, thermal])


BOUNDS = group_bounds()
G = len(BOUNDS) - 1


def weight(e):
    """Le spectre de pondération : Maxwell (kT = 0,0253 eV) → 1/E → fission (Maxwell θ = 1,4 MeV), raccordés."""
    kt, eb, ec, theta = 0.0253, 0.125, 820.3e3, 1.4e6
    w = 1.0 / e
    low = e < eb
    w[low] = (e[low] / kt ** 2 * np.exp(-e[low] / kt)) * (1 / eb) / (eb / kt ** 2 * math.exp(-eb / kt))
    high = e > ec
    w[high] = (np.sqrt(e[high]) * np.exp(-e[high] / theta)) * (1 / ec) / (math.sqrt(ec) * math.exp(-ec / theta))
    return w


def speed_cm_s(e):
    gamma = 1 + e / NEUTRON_MASS_EV
    return 2.99792458e10 * np.sqrt(1 - 1 / gamma ** 2)


# ---------------------------------------------------------------------- lecture des fonctions tabulées
def function1d(dataset):
    """Une fonction de l'énergie incidente (polynôme ou table), en fonction numpy."""
    kind = dataset.attrs["type"].decode()
    data = dataset[()]
    if kind == "Polynomial":
        coefficients = np.array(data, float)
        return lambda e: np.polynomial.polynomial.polyval(np.asarray(e, float), coefficients)
    if kind == "Tabulated1D":
        x, y = np.array(data[0]), np.array(data[1])
        return lambda e: np.interp(np.asarray(e, float), x, y, left=y[0], right=y[-1])
    raise ValueError(kind)


class Tables:
    """Des distributions tabulées, une par énergie incidente : (x, densité, répartition) concaténées."""

    def __init__(self, incident, rows, offsets, interpolation, discrete=None):
        self.incident = np.array(incident, float)
        self.x, self.p, self.c = np.array(rows[0]), np.array(rows[1]), np.array(rows[2])
        self.offsets = np.array(offsets, int)
        self.interpolation = np.array(interpolation, int)
        self.discrete = np.zeros(len(self.offsets), int) if discrete is None else np.array(discrete, int)
        self.ends = np.append(self.offsets[1:], len(self.x))

    def pick(self, energies, rng):
        """Interpolation stochastique entre les deux tables qui encadrent chaque énergie incidente."""
        e = np.clip(energies, self.incident[0], self.incident[-1])
        low = np.clip(np.searchsorted(self.incident, e, side="right") - 1, 0, len(self.incident) - 2)
        span = self.incident[low + 1] - self.incident[low]
        r = np.where(span > 0, (e - self.incident[low]) / np.where(span > 0, span, 1), 0)
        return low + (rng.random(len(e)) < r)

    def sample(self, energies, rng):
        tables = self.pick(energies, rng)
        out = np.empty(len(energies))
        xi = rng.random(len(energies))
        for t in np.unique(tables):
            mask = tables == t
            a, b = self.offsets[t], self.ends[t]
            out[mask] = _sample_table(self.x[a:b], self.p[a:b], self.c[a:b], self.interpolation[t],
                                      self.discrete[t], xi[mask])
        return out


def _sample_table(x, p, c, interpolation, discrete, xi):
    if len(x) == 1:
        return np.full(len(xi), x[0])
    if c[-1] > 0:
        xi = xi * c[-1]
    j = np.clip(np.searchsorted(c, xi, side="right") - 1, 0, len(x) - 2)
    out = np.empty(len(xi))
    lines = j < discrete
    out[lines] = x[j[lines]]
    cont = ~lines
    jj = j[cont]
    x0, p0, c0 = x[jj], p[jj], c[jj]
    if interpolation == 1:
        out[cont] = x0 + np.where(p0 > 0, (xi[cont] - c0) / np.where(p0 > 0, p0, 1), 0)
    else:
        x1, p1 = x[jj + 1], p[jj + 1]
        dx = np.where(x1 > x0, x1 - x0, 1)
        m = (p1 - p0) / dx
        flat = np.abs(m) < 1e-12 * np.maximum(np.abs(p0), 1e-300)
        safe_p0 = np.where(p0 > 0, p0, 1e-300)
        root = np.sqrt(np.maximum(p0 ** 2 + 2 * m * (xi[cont] - c0), 0))
        out[cont] = np.where(flat, x0 + (xi[cont] - c0) / safe_p0, x0 + (root - p0) / np.where(flat, 1, m))
    return np.clip(out, x.min(), x.max())


def angle_tables(group):
    mu = group["mu"]
    return Tables(group["energy"][()], mu[()], mu.attrs["offsets"], mu.attrs["interpolation"])


def energy_tables(group, key):
    data = group[key]
    return Tables(group["energy"][()], data[()][:3], data.attrs["offsets"], data.attrs["interpolation"],
                  data.attrs.get("n_discrete_lines"))


# ---------------------------------------------------------------------- tirages des neutrons sortants
class Product:
    """Un neutron sortant d'une réaction : multiplicité, et de quoi tirer (E', μ dans le laboratoire)."""

    def __init__(self, product, reaction_cm, awr):
        self.yield_ = function1d(product["yield"])
        self.awr = awr
        self.decay_rate = float(product.attrs.get("decay_rate", 0.0))
        self.delayed = product.attrs["emission_mode"].decode() == "delayed"
        dist = product["distribution_0"]
        self.kind = dist.attrs["type"].decode()
        self.cm = bool(reaction_cm)
        self.angle = angle_tables(dist["angle"]) if self.kind == "uncorrelated" and "angle" in dist else None
        self.energy = None
        self.level = None
        if self.kind == "uncorrelated" and "energy" in dist:
            energy = dist["energy"]
            etype = energy.attrs["type"].decode()
            if etype == "level":
                self.level = (float(energy.attrs["threshold"]), float(energy.attrs["mass_ratio"]))
            elif etype == "continuous":
                self.energy = energy_tables(energy, "distribution")
            elif etype in ("maxwell", "evaporation", "watt"):
                self.energy = Analytic(energy, etype)
            else:
                raise ValueError(f"distribution d'énergie non gérée : {etype}")
        elif self.kind in ("correlated", "kalbach-mann"):
            key = "energy_out" if self.kind == "correlated" else "distribution"
            self.energy = energy_tables(dist, key)
        elif self.kind != "uncorrelated":
            raise ValueError(f"distribution non gérée : {self.kind}")

    def sample(self, e, rng):
        n = len(e)
        mu_cm = self.angle.sample(e, rng) if self.angle is not None else rng.uniform(-1, 1, n)
        if self.level is not None:
            threshold, ratio = self.level
            e_out = np.maximum(ratio * (e - threshold), 0)
        else:
            e_out = self.energy.sample(e, rng)
            if self.kind != "uncorrelated" or self.angle is None:
                mu_cm = rng.uniform(-1, 1, n)
        if not self.cm:
            return e_out, mu_cm
        a = self.awr
        e_lab = e_out + (e + 2 * mu_cm * (a + 1) * np.sqrt(e * e_out)) / (a + 1) ** 2
        e_lab = np.maximum(e_lab, 1e-11)
        mu_lab = np.clip(mu_cm * np.sqrt(e_out / e_lab) + np.sqrt(e / e_lab) / (a + 1), -1, 1)
        return e_lab, mu_lab


class Analytic:
    """Spectres analytiques ENDF (Maxwell, évaporation, Watt)."""

    def __init__(self, energy, kind):
        self.kind = kind
        self.u = float(energy.attrs["u"])
        if kind == "watt":
            self.a = function1d(energy["a"])
            self.b = function1d(energy["b"])
        else:
            self.theta = function1d(energy["theta"])

    def sample(self, e, rng):
        n = len(e)
        if self.kind == "watt":
            a, b = self.a(e), self.b(e)
            c = np.cos(np.pi / 2 * rng.random(n))
            w = -a * (np.log(rng.random(n)) + np.log(rng.random(n)) * c * c)
            out = w + a * a * b / 4 + (2 * rng.random(n) - 1) * np.sqrt(a * a * b * w)
        elif self.kind == "maxwell":
            t = self.theta(e)
            c = np.cos(np.pi / 2 * rng.random(n))
            out = -t * (np.log(rng.random(n)) + np.log(rng.random(n)) * c * c)
        else:
            t = self.theta(e)
            out = -t * np.log(rng.random(n) * rng.random(n))
        return np.clip(out, 1e-5, np.maximum(e - self.u, 1e-5))


def elastic_kinematics(e, mu_cm, awr):
    """Choc élastique sur une cible au repos : énergie et cosinus de déviation dans le laboratoire."""
    a = awr
    e_out = e * (a * a + 2 * a * mu_cm + 1) / (a + 1) ** 2
    mu_lab = (1 + a * mu_cm) / np.sqrt(a * a + 2 * a * mu_cm + 1)
    return e_out, np.clip(mu_lab, -1, 1)


def free_gas_elastic(e, awr, rng):
    """Choc élastique sur une cible agitée (gaz libre à 294 K), diffusion isotrope dans le centre de masse."""
    n = len(e)
    beta = np.sqrt(awr / KT_EV)                      # en unités où v² = E (masse du neutron = 1)
    vn = np.sqrt(e)
    y = beta * vn
    vt = np.empty(n)
    mut = np.empty(n)
    todo = np.arange(n)
    while len(todo):
        m = len(todo)
        yy = y[todo]
        use_x3 = rng.random(m) * (np.sqrt(np.pi) * yy + 2) > np.sqrt(np.pi) * yy
        x = np.where(use_x3, np.sqrt(-np.log(rng.random(m) * rng.random(m))),
                     np.sqrt(-np.log(rng.random(m)) - np.log(rng.random(m)) * np.cos(np.pi / 2 * rng.random(m)) ** 2))
        speed = x / beta
        mu = rng.uniform(-1, 1, m)
        rel = np.sqrt(vn[todo] ** 2 + speed ** 2 - 2 * vn[todo] * speed * mu)
        ok = rng.random(m) < rel / (vn[todo] + speed)
        vt[todo[ok]] = speed[ok]
        mut[todo[ok]] = mu[ok]
        todo = todo[~ok]
    # Vitesses (le neutron vole selon z ; la cible dans le plan xz).
    v_n = np.stack([np.zeros(n), np.zeros(n), vn], axis=1)
    v_t = np.stack([vt * np.sqrt(1 - mut ** 2), np.zeros(n), vt * mut], axis=1)
    v_cm = (v_n + awr * v_t) / (awr + 1)
    u = v_n - v_cm
    speed_cm = np.linalg.norm(u, axis=1)
    cos_t = rng.uniform(-1, 1, n)
    phi = rng.uniform(0, 2 * np.pi, n)
    sin_t = np.sqrt(1 - cos_t ** 2)
    out = v_cm + speed_cm[:, None] * np.stack([sin_t * np.cos(phi), sin_t * np.sin(phi), cos_t], axis=1)
    e_out = np.sum(out ** 2, axis=1)
    mu_lab = out[:, 2] / np.maximum(np.sqrt(e_out), 1e-30)
    return e_out, mu_lab


# ---------------------------------------------------------------------- un noyau
class Nuclide:
    def __init__(self, path):
        self.file = h5py.File(path, "r")
        self.root = self.file[list(self.file.keys())[0]]
        self.name = self.root.name.strip("/")
        self.z = int(self.root.attrs["Z"])
        self.a = int(self.root.attrs["A"])
        self.awr = float(self.root.attrs["atomic_weight_ratio"])
        self.energy = self.root["energy"][TEMPERATURE][()]
        self.reactions = {}
        for key in self.root["reactions"]:
            r = self.root["reactions"][key]
            mt = int(r.attrs["mt"])
            if TEMPERATURE not in r:
                continue
            xs = r[TEMPERATURE]["xs"]
            full = np.zeros(len(self.energy))
            start = int(xs.attrs["threshold_idx"])
            full[start:start + len(xs)] = xs[()]
            self.reactions[mt] = {"group": r, "xs": full, "redundant": bool(r.attrs["redundant"]),
                                  "cm": bool(r.attrs["center_of_mass"]), "q": float(r.attrs["Q_value"])}

    def neutron_products(self, mt):
        r = self.reactions[mt]["group"]
        out = []
        for key in r:
            if key.startswith("product_") and r[key].attrs["particle"].decode() == "neutron":
                out.append(Product(r[key], self.reactions[mt]["cm"], self.awr))
        return out

    def classify(self):
        """Les réactions non redondantes : élastique, fission, diffusions (neutrons rendus), absorptions."""
        elastic, fission, scatter, absorb = None, [], [], []
        for mt, r in sorted(self.reactions.items()):
            if r["redundant"] or mt in (1, 3, 4, 27, 101, 301, 444) or 200 <= mt < 600:
                continue
            if mt == 2:
                elastic = mt
            elif mt in FISSION_MTS:
                fission.append(mt)
            elif any(k.startswith("product_") and r["group"][k].attrs["particle"].decode() == "neutron"
                     for k in r["group"]):
                scatter.append(mt)
            else:
                absorb.append(mt)
        if 18 in fission and len(fission) > 1:
            fission = [mt for mt in fission if mt != 18]
        return elastic, fission, scatter, absorb


def group_integrals(energy, values, weights):
    """∫ valeurs × poids dans chaque groupe (trapèzes sur la grille fine, bornes insérées)."""
    grid = np.union1d(energy, BOUNDS[(BOUNDS >= energy[0]) & (BOUNDS <= energy[-1])])
    v = np.array([np.interp(grid, energy, row) for row in np.atleast_2d(values)])
    w = np.interp(grid, energy, weights)
    f = v * w
    cumulative = np.concatenate([np.zeros((len(v), 1)), np.cumsum((f[:, 1:] + f[:, :-1]) / 2 * np.diff(grid), axis=1)],
                                axis=1)
    at = np.array([np.interp(BOUNDS, grid, row) for row in cumulative])
    return at[:, :-1] - at[:, 1:]                     # groupe 0 = le plus énergétique


def sample_incident(energy, weights, xs, g, n, rng):
    """Des énergies incidentes du groupe g, tirées selon W(E) σ(E)."""
    hi, lo = BOUNDS[g], BOUNDS[g + 1]
    inside = (energy > lo) & (energy < hi)
    grid = np.concatenate([[lo], energy[inside], [hi]])
    f = np.interp(grid, energy, xs * weights)
    area = (f[1:] + f[:-1]) / 2 * np.diff(grid)
    if area.sum() <= 0:
        return None
    cdf = np.cumsum(area) / area.sum()
    j = np.minimum(np.searchsorted(cdf, rng.random(n)), len(area) - 1)
    return grid[j] + rng.random(n) * (grid[j + 1] - grid[j])


def group_of(e):
    return np.clip(np.searchsorted(-BOUNDS, -e, side="right") - 1, 0, G - 1)


def process(path, rng):
    nuc = Nuclide(path)
    e = nuc.energy
    w = weight(e)
    elastic, fission, scatter, absorb = nuc.classify()
    xs = lambda mts: sum((nuc.reactions[mt]["xs"] for mt in mts), np.zeros(len(e)))
    total = xs([elastic] + fission + scatter + absorb)
    sig_el = nuc.reactions[elastic]["xs"]
    sig_f = xs(fission)
    sig_in = xs(scatter)
    has_fission = bool(fission)
    nu_total = function1d(nuc.root["total_nu"]["yield"])(e) if has_fission and "total_nu" in nuc.root else np.zeros(len(e))
    # Les rendements des réactions qui rendent des neutrons (multiplicité moyenne).
    multiplicity = np.zeros(len(e))
    for mt in scatter:
        for p in nuc.neutron_products(mt):
            multiplicity += nuc.reactions[mt]["xs"] * p.yield_(e)

    rows = np.stack([total, sig_el, sig_in, multiplicity, sig_f, nu_total * sig_f, total - sig_el - sig_in - sig_f])
    shielded = []
    for s0 in SIGMA0:
        flux = w / (total + s0)
        integrals = group_integrals(e, rows, flux)
        norm = group_integrals(e, np.ones(len(e)), flux)[0]
        shielded.append(integrals / np.maximum(norm, 1e-300))
    shielded = np.array(shielded)                     # (σ0, 7, G)
    inverse_speed = group_integrals(e, 1 / speed_cm_s(e), w)[0] / group_integrals(e, np.ones(len(e)), w)[0]

    # Transferts : élastique, puis les autres diffusions (avec leur multiplicité).
    elastic_matrix = np.zeros((G, G))
    elastic_mu = np.zeros(G)
    angle = nuc.neutron_products(elastic)[0]
    for g in range(G):
        energies = sample_incident(e, w, sig_el, g, SAMPLES, rng)
        if energies is None:
            continue
        thermal = energies < 400 * KT_EV
        e_out = np.empty(SAMPLES)
        mu = np.empty(SAMPLES)
        if thermal.any():
            e_out[thermal], mu[thermal] = free_gas_elastic(energies[thermal], nuc.awr, rng)
        if (~thermal).any():
            fast = energies[~thermal]
            e_out[~thermal], mu[~thermal] = elastic_kinematics(fast, angle.angle.sample(fast, rng), nuc.awr)
        np.add.at(elastic_matrix[g], group_of(e_out), 1.0 / SAMPLES)
        elastic_mu[g] = mu.mean()

    inelastic_matrix = np.zeros((G, G))               # neutrons rendus par réaction de diffusion inélastique
    inelastic_mu = np.zeros((G,))
    weights_in = group_integrals(e, np.stack([nuc.reactions[mt]["xs"] for mt in scatter]), w) if scatter else None
    flux_norm = group_integrals(e, np.ones(len(e)), w)[0]
    for k, mt in enumerate(scatter):
        sig = nuc.reactions[mt]["xs"]
        for p in nuc.neutron_products(mt):
            for g in range(G):
                if weights_in[k, g] <= 0:
                    continue
                n = max(400, int(SAMPLES * 0.5))
                energies = sample_incident(e, w, sig, g, n, rng)
                if energies is None:
                    continue
                e_out, mu = p.sample(energies, rng)
                share = weights_in[k, g] / flux_norm[g]  # σ moyen de cette réaction dans le groupe
                y = p.yield_(energies)
                np.add.at(inelastic_matrix[g], group_of(e_out), share * y / n)
                inelastic_mu[g] += share * np.mean(y * mu)
    # En probabilités par réaction : on divise par σ_in du groupe (dilution infinie).
    sig_in_inf = shielded[-1, 2]
    with np.errstate(invalid="ignore", divide="ignore"):
        inelastic_matrix = np.where(sig_in_inf[:, None] > 0, inelastic_matrix / sig_in_inf[:, None], 0)
        inelastic_mu = np.where(sig_in_inf > 0, inelastic_mu / sig_in_inf, 0)

    result = {"z": nuc.z, "a": nuc.a, "awr": nuc.awr, "shielded": shielded, "inverse_speed": inverse_speed,
              "elastic": elastic_matrix, "elastic_mu": elastic_mu, "inelastic": inelastic_matrix,
              "inelastic_mu": inelastic_mu}
    if has_fission:
        result.update(fission_data(nuc, fission, sig_f, e, w, rng))
    return nuc.name, result


def fission_data(nuc, fission, sig_f, e, w, rng):
    """χ (prompts + retardés) par groupe incident, ν prompt, rendements et constantes des précurseurs, MT 458."""
    chi = np.zeros((G, G))
    nu_total = function1d(nuc.root["total_nu"]["yield"])(e)
    rate = group_integrals(e, sig_f, w)[0]
    prompt_nu = np.zeros(G)
    delayed_nu = []
    decay = []
    for mt in fission:
        sig = nuc.reactions[mt]["xs"]
        share = group_integrals(e, sig, w)[0] / np.maximum(rate, 1e-300)
        for p in nuc.neutron_products(mt):
            nu_g = group_integrals(e, sig * p.yield_(e), w)[0] / np.maximum(group_integrals(e, sig, w)[0], 1e-300)
            if p.delayed:
                decay.append(p.decay_rate)
                delayed_nu.append(share * nu_g)
            else:
                prompt_nu += share * nu_g
            for g in range(G):
                if share[g] <= 0:
                    continue
                energies = sample_incident(e, w, sig, g, SAMPLES, rng)
                if energies is None:
                    continue
                e_out, _ = p.sample(energies, rng)
                np.add.at(chi[g], group_of(e_out), share[g] * nu_g[g] / SAMPLES)
    # Des précurseurs déclarés par réaction partielle (19, 20...) se répètent : on les fusionne par constante.
    merged = {}
    for lam, nu in zip(decay, delayed_nu):
        key = round(lam, 6)
        merged[key] = merged.get(key, 0) + nu
    decay = np.array(sorted(merged))
    delayed = np.array([merged[k] for k in decay]) if len(decay) else np.zeros((0, G))
    sums = chi.sum(axis=1, keepdims=True)
    chi = np.where(sums > 0, chi / np.where(sums > 0, sums, 1), 0)
    release = nuc.root.get("fission_energy_release")
    energy_release = np.zeros(9)
    names = ["fragments", "prompt_neutrons", "delayed_neutrons", "prompt_photons", "delayed_photons", "betas",
             "neutrinos", "q_prompt", "q_recoverable"]
    if release is not None:
        energy_release = np.array([float(function1d(release[k])(1e6)) if k in release else 0.0 for k in names])
    return {"chi": chi, "prompt_nu": prompt_nu, "delayed_nu": delayed, "decay_constants": decay,
            "energy_release": energy_release}


def main():
    folder = Path(sys.argv[1])
    wanted = sys.argv[2:]
    files = sorted(folder.glob("*.h5"))
    if wanted:
        files = [folder / f"{n}.h5" for n in wanted]
    rng = np.random.default_rng(20261024)
    arrays = {"bounds": BOUNDS, "sigma0": SIGMA0}
    names = []
    if OUTPUT.exists():
        with np.load(OUTPUT) as old:
            arrays.update({k: old[k] for k in old.files if k not in ("bounds", "sigma0", "names")})
            names = [str(n) for n in old["names"]]
    for path in files:
        if path.stat().st_size < 10000:
            print("ignoré (vide) :", path.name)
            continue
        name, data = process(path, rng)
        print(f"{name}: Z={data['z']} A={data['a']} σ_t(1 MeV)≈{data['shielded'][-1, 0, group_of(np.array([1e6]))[0]]:.2f} b")
        for key, value in data.items():
            arrays[f"{name}/{key}"] = np.asarray(value, np.float64 if key in ("z", "a", "awr") else np.float32)
        if name not in names:
            names.append(name)
    arrays["names"] = np.array(names)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(OUTPUT, **arrays)
    print("écrit :", OUTPUT, f"{OUTPUT.stat().st_size / 1024:.0f} Ko")


if __name__ == "__main__":
    main()
