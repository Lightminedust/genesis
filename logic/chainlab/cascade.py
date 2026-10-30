"""La cascade de neutrons, neutron par neutron : un échantillon tiré au hasard dans la vraie géométrie.

Chaque neutron vole en ligne droite sur une distance tirée selon e^(−Σ_tr·d), avec les sections efficaces du
groupe et de la région où il se trouve (les mêmes que celles du calcul de k_eff, correction de transport comprise :
les chocs sont alors isotropes). Au choc : fission, capture ou diffusion (le groupe d'arrivée est tiré dans la
matrice de transfert). Une fission libère ν neutrons, ν tiré selon la loi de Terrell (1957) — une gaussienne
discrétisée de largeur σ = 1,08 — autour du ν̄(E) d'ENDF/B-VIII.0 ; leur groupe est tiré dans le spectre χ. Le temps
avance de d/v. Au-delà de quelques dizaines de neutrons par génération, on n'en suit qu'un échantillon ; l'effectif
de la génération est corrigé du rapport d'échantillonnage.

Chaque point d'une trace porte sa nature : naissance, choc de diffusion, passage d'une surface (cœur ↔ réflecteur),
prolongement d'une fuite, ou choc final (fission ou capture).
"""

import math

import numpy as np

TERRELL_WIDTH = 1.08
MAX_STEPS = 400
BIRTH, SCATTER, SURFACE, ESCAPE, END = range(5)


def terrell(nu_bar, width=TERRELL_WIDTH, n_max=9):
    """P(ν = 0..n_max) : la loi de Terrell, décalée pour que sa moyenne soit exactement ν̄."""
    from scipy.special import ndtr
    n = np.arange(n_max + 1)

    def probabilities(shift):
        edges = np.concatenate([[-np.inf], (n[:-1] + 0.5 - nu_bar + shift) / width, [np.inf]])
        return np.diff(ndtr(edges))

    shift = 0.0
    for _ in range(60):
        mean = float(np.dot(n, probabilities(shift)))
        if abs(mean - nu_bar) < 1e-10:
            break
        shift += mean - nu_bar
    return probabilities(shift)


def divergence_probability(k, nu_bar):
    """La probabilité qu'un seul neutron allume une chaîne qui ne s'éteint jamais (processus de branchement).

    Un neutron fait fissionner avec la probabilité P_f = k/ν̄, et la fission en libère ν (loi de Terrell). La chaîne
    s'éteint avec la probabilité q, plus petite solution de q = 1 − P_f + P_f Σ P(ν) q^ν ; elle diverge avec 1 − q.
    """
    if k <= 1 or nu_bar <= 0:
        return 0.0
    table = terrell(nu_bar)
    fission = min(1.0, k / nu_bar)
    q = 0.0
    for _ in range(5000):
        new = 1 - fission + fission * float(np.polyval(table[::-1], q))
        if abs(new - q) < 1e-13:
            break
        q = new
    return max(0.0, 1 - q)


def _distance_to_sphere(pos, direction, radius):
    """Distance, le long de la direction, jusqu'à la sphère de ce rayon (inf si on ne la rencontre pas)."""
    b = float(np.dot(pos, direction))
    c = float(np.dot(pos, pos)) - radius * radius
    disc = b * b - c
    if disc < 0:
        return math.inf
    root = math.sqrt(disc)
    for d in (-b - root, -b + root):
        if d > 1e-9:
            return d
    return math.inf


def _isotropic(rng):
    mu = rng.uniform(-1, 1)
    phi = rng.uniform(0, 2 * math.pi)
    s = math.sqrt(max(0.0, 1 - mu * mu))
    return np.array([s * math.cos(phi), s * math.sin(phi), mu])


def run(core_radius, outer_radius, materials, nu_bar_by_group, rng, sources=1, start=None, source_spectrum=None,
        generations=12, keep=40):
    """Tire une cascade. materials : [cœur, réflecteur] (dicts de library.macroscopic) ; nu_bar_by_group : ν̄(g).

    sources : neutrons d'amorçage, nés en `start` (cm) s'il est dans le cœur, sinon au hasard près du centre ;
    source_spectrum : leur spectre en groupes (par défaut le χ du cœur). Rend les traces (génération, parent, points
    x, y, z (cm) et t (ns), nature de chaque point, groupes, issue, enfants) et l'effectif de chaque génération.
    """
    core = materials[0]
    groups = len(core["total"])
    chi = core["chi"]
    if source_spectrum is None:
        fast = int(np.argmax(core["nu_fission"] > 0)) if core["nu_fission"].max() > 0 else 0
        source_spectrum = chi[fast] if chi[fast].sum() > 0 else np.eye(groups)[0]
    source_spectrum = np.asarray(source_spectrum, float) / float(np.sum(source_spectrum))
    tables = [terrell(nu) if nu > 0 else np.array([1.0]) for nu in nu_bar_by_group]
    tracks = []
    counts = [float(sources)]
    population = []
    origin = None
    if start is not None and float(np.linalg.norm(start)) < core_radius:
        origin = np.asarray(start, float)
    for _ in range(sources):
        where = origin if origin is not None else _isotropic(rng) * core_radius * 0.4 * rng.random() ** (1 / 3)
        population.append((where, int(rng.choice(groups, p=source_spectrum)), 0.0, -1))
    for generation in range(generations):
        if not population:
            break
        if len(population) > keep:
            chosen = rng.choice(len(population), keep, replace=False)
            sampled = [population[i] for i in chosen]
        else:
            sampled = population
        scale = counts[-1] / len(sampled)
        births = []
        for begin, group, t0, parent in sampled:
            track = _follow(begin, group, t0, core_radius, outer_radius, materials, rng)
            track["generation"] = generation
            track["parent"] = parent
            index = len(tracks)
            tracks.append(track)
            if track["end"] == "fission":
                g = track["groups"][-1]
                nu = int(rng.choice(len(tables[g]), p=tables[g]))
                track["children"] = nu
                here = np.array(track["points"][-1][:3])
                row = chi[g] if chi[g].sum() > 0 else source_spectrum
                for _ in range(nu):
                    births.append((here, int(rng.choice(groups, p=row / row.sum())), track["points"][-1][3], index))
        counts.append(len(births) * scale)
        population = births
    return {"tracks": tracks, "counts": counts}


def _follow(pos, group, t_ns, core_radius, outer_radius, materials, rng):
    """Un neutron, du point de naissance à sa fin : fission, capture ou fuite."""
    pos = np.array(pos, float)
    direction = _isotropic(rng)
    points = [(*pos, t_ns)]
    kinds = [BIRTH]
    groups = []
    end = "fuite"
    reflected = outer_radius > core_radius + 1e-9
    for _ in range(MAX_STEPS):
        r = math.sqrt(float(np.dot(pos, pos)))
        region = 0 if (r < core_radius - 1e-7 or not reflected) else 1
        m = materials[region]
        sigma = m["total"][group]
        if region == 0:
            boundary = _distance_to_sphere(pos, direction, core_radius)
        else:
            boundary = min(_distance_to_sphere(pos, direction, outer_radius),
                           _distance_to_sphere(pos, direction, core_radius))
        flight = -math.log(1 - rng.random()) / sigma if sigma > 0 else math.inf
        ns_per_cm = m["inverse_speed"][group] * 1e9
        groups.append(group)
        if flight >= boundary:
            pos = pos + direction * (boundary + 1e-7)
            t_ns += boundary * ns_per_cm
            points.append((*pos, t_ns))
            if math.sqrt(float(np.dot(pos, pos))) >= outer_radius - 1e-6:
                # La fuite : la trace continue un peu hors de la sphère, pour qu'on la voie partir.
                kinds.append(SURFACE)
                extra = outer_radius * 0.9
                points.append((*(pos + direction * extra), t_ns + extra * ns_per_cm))
                kinds.append(ESCAPE)
                groups.append(group)
                end = "fuite"
                break
            kinds.append(SURFACE)
            continue
        pos = pos + direction * flight
        t_ns += flight * ns_per_cm
        points.append((*pos, t_ns))
        fission = m["fission"][group]
        capture = max(m["capture"][group], 0.0)
        scatter = max(sigma - fission - capture, 0.0)
        roll = rng.random() * (fission + capture + scatter)
        if roll < fission:
            kinds.append(END)
            end = "fission"
            break
        if roll < fission + capture:
            kinds.append(END)
            end = "capture"
            break
        kinds.append(SCATTER)
        row = np.maximum(m["scatter"][group], 0)
        if row.sum() > 0:
            group = int(rng.choice(len(row), p=row / row.sum()))
        direction = _isotropic(rng)
    else:
        end = "suivi"          # trop de chocs : on cesse de le suivre (diffusion sans fin dans un réflecteur)
    return {"points": points, "kinds": kinds, "groups": groups, "end": end, "children": 0}
