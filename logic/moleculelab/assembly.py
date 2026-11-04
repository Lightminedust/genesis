"""L'assembleur moléculaire : d'un dessin 2D (atomes, liaisons et leurs ordres) à une géométrie 3D, et le verdict du
calcul sur ce qui a été dessiné.

Le dessin arrive du client en un vecteur de nombres : [n, (Z, x, y, charge formelle) × n, m, (a, b, ordre) × m], x et y
dans l'unité du dessin (la longueur d'une liaison dessinée). Rien n'y est accepté tel quel :

- la géométrie 3D : chaque molécule est posée par contraintes — longueur de chaque liaison d'après les rayons
  covalents de Pyykkö (× 1, 0,87 ou 0,78 pour une simple, une double, une triple), angle autour de chaque atome
  d'après ses liaisons π (180° avec deux π ou une triple, 120° avec une, 109,5° sinon), atomes non liés tenus à
  distance — en partant du dessin et d'un léger relief au hasard (graine fixe) : un carbone à quatre voisins dessiné à
  plat devient tétraédrique. Les molécules gardent leur place relative, écartées d'au moins 2,5 Å. GFN1-xTB la relaxe
  ensuite dans le laboratoire ;
- le verdict : chaque molécule relaxée seule par GFN1-xTB (L-BFGS, forces analytiques). Garde-t-elle le graphe dessiné
  (« tient »), le change-t-elle (« se réarrange » : liaisons perdues, liaisons nouvelles), ou se défait-elle en
  plusieurs morceaux (« se défait ») ? Avec sa cohésion, son écart HOMO–LUMO et son nom dans le dictionnaire.
"""

import numpy as np
from scipy.optimize import minimize

from . import analysis, dictionary, structures, xtb

EV = xtb.HARTREE_EV
SHORTER = {1: 1.0, 2: 0.87, 3: 0.78}
SEPARATION = 2.5                      # Å : entre deux molécules du dessin, au moins
MAX_ATOMS = 40


class Cancelled(Exception):
    """Un autre dessin est arrivé : le verdict de celui-ci ne sert plus."""


def decode(vector):
    """(numéros atomiques, charges formelles, positions 2D (n, 2), liaisons [(a, b, ordre)]) depuis le vecteur du client."""
    v = [float(x) for x in vector]
    n = int(v[0])
    atoms = np.array(v[1:1 + 4 * n], float).reshape(n, 4) if n else np.zeros((0, 4))
    m = int(v[1 + 4 * n]) if len(v) > 1 + 4 * n else 0
    raw = v[2 + 4 * n:2 + 4 * n + 3 * m]
    bonds = []
    for k in range(m):
        a, b, order = int(raw[3 * k]), int(raw[3 * k + 1]), int(round(raw[3 * k + 2]))
        if 0 <= a < n and 0 <= b < n and a != b:
            bonds.append((min(a, b), max(a, b), max(1, min(3, order))))
    return [int(z) for z in atoms[:, 0]], [int(round(q)) for q in atoms[:, 3]], atoms[:, 1:3].copy(), bonds


def encode(numbers, charges, xy, bonds):
    out = [len(numbers)]
    for z, q, (x, y) in zip(numbers, charges, xy):
        out += [z, x, y, q]
    out.append(len(bonds))
    for a, b, order in bonds:
        out += [a, b, order]
    return out


def components(n, bonds):
    """Les molécules du dessin : les groupes d'atomes reliés, chacun trié."""
    parent = list(range(n))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for a, b, _ in bonds:
        parent[find(a)] = find(b)
    groups = {}
    for a in range(n):
        groups.setdefault(find(a), []).append(a)
    return sorted(groups.values(), key=lambda g: g[0])


def _targets(numbers, bonds):
    """Les distances visées : liaisons (poids 1) et voisins de voisins (poids 0,5), d'après les angles attendus."""
    n = len(numbers)
    radius = [analysis.covalent_radius(z) for z in numbers]
    length = {}
    around = [[] for _ in range(n)]
    pi = [0] * n
    triple = [False] * n
    for a, b, order in bonds:
        length[(a, b)] = length[(b, a)] = (radius[a] + radius[b]) * SHORTER[order]
        around[a].append(b)
        around[b].append(a)
        pi[a] += order - 1
        pi[b] += order - 1
        triple[a] |= order == 3
        triple[b] |= order == 3
    rules = [(a, b, length[(a, b)], 1.0) for a, b, _ in bonds]
    near = {(a, b) for a, b, _ in bonds}
    for j in range(n):
        if len(around[j]) > 4:
            continue                                      # hypervalent : l'écartement suffit
        if (pi[j] >= 2 or triple[j]) and len(around[j]) == 2:
            angle = 180.0
        elif pi[j] >= 1 and len(around[j]) <= 3:
            angle = 120.0
        else:
            angle = 109.47
        for x in range(len(around[j])):
            for y in range(x + 1, len(around[j])):
                a, b = around[j][x], around[j][y]
                ra, rb = length[(a, j)], length[(b, j)]
                d = np.sqrt(ra * ra + rb * rb - 2 * ra * rb * np.cos(np.radians(angle)))
                rules.append((a, b, d, 0.5))
                near.add((min(a, b), max(a, b)))
    return rules, near


def _settle(p, rules, near, steps=1200):
    """Les contraintes appliquées toutes ensemble à chaque tour (chaque atome bouge de la moyenne de ce que ses
    contraintes lui demandent), puis les atomes non liés écartés à 1,9 Å au moins."""
    n = len(p)
    if not rules:
        return p
    a = np.array([r[0] for r in rules])
    b = np.array([r[1] for r in rules])
    target = np.array([r[2] for r in rules])
    weight = np.array([r[3] for r in rules])
    count = np.maximum(1, np.bincount(np.r_[a, b], minlength=n))[:, None]
    first, second = np.triu_indices(n, 1)
    free = np.array([(x, y) not in near for x, y in zip(first, second)], bool)
    first, second = first[free], second[free]
    for step in range(steps):
        d = p[b] - p[a]
        r = np.maximum(np.linalg.norm(d, axis=1), 1e-6)
        move = (0.5 * weight * (r - target) / r)[:, None] * d
        delta = np.zeros_like(p)
        np.add.at(delta, a, move)
        np.add.at(delta, b, -move)
        p += 1.6 * delta / count
        if len(first) and step % 3 == 0:
            d = p[second] - p[first]
            r = np.maximum(np.linalg.norm(d, axis=1), 1e-6)
            close = r < 1.9
            if np.any(close):
                move = (0.15 * (r[close] - 1.9) / r[close])[:, None] * d[close]
                delta = np.zeros_like(p)
                np.add.at(delta, first[close], move)
                np.add.at(delta, second[close], -move)
                p += delta
    return p


def embed(numbers, bonds, xy, seed=0):
    """Les positions 3D (Å) du dessin : chaque molécule posée par contraintes, les molécules à leur place relative."""
    n = len(numbers)
    xy = np.asarray(xy, float).reshape(n, 2)
    rng = np.random.default_rng(seed)
    drawn = [np.linalg.norm(xy[a] - xy[b]) for a, b, _ in bonds]
    unit = 1.4 / max(1e-6, float(np.mean(drawn))) if drawn else 1.4          # Å par unité du dessin
    positions = np.zeros((n, 3))
    placed = []
    for group in components(n, bonds):
        index = {atom: k for k, atom in enumerate(group)}
        local = [(index[a], index[b], o) for a, b, o in bonds if a in index]
        sub = [numbers[a] for a in group]
        start = np.column_stack([xy[group] * unit, rng.normal(scale=0.35, size=len(group))])
        centre = start.mean(axis=0)
        rules, near = _targets(sub, local)
        p = _settle(start.copy(), rules, near) if local else start.copy()
        p += centre - p.mean(axis=0)
        positions[group] = p
        placed.append(group)
    # Les molécules à l'écart les unes des autres : poussées le long de la ligne de leurs centres.
    for _ in range(60):
        moved = False
        for i in range(len(placed)):
            for j in range(i + 1, len(placed)):
                a, b = positions[placed[i]], positions[placed[j]]
                gap = np.min(np.linalg.norm(a[:, None, :] - b[None, :, :], axis=2))
                if gap >= SEPARATION:
                    continue
                axis = b.mean(axis=0) - a.mean(axis=0)
                norm = np.linalg.norm(axis)
                axis = axis / norm if norm > 1e-6 else np.array([1.0, 0.0, 0.0])
                shift = 0.5 * (SEPARATION - gap + 0.05) * axis
                positions[placed[i]] -= shift
                positions[placed[j]] += shift
                moved = True
        if not moved:
            break
    return positions


def label(numbers, charges, bonds):
    """« 2 H₂O · eau + CO₂ · dioxyde de carbone » : les molécules du dessin, nommées."""
    counts = {}
    for group in components(len(numbers), bonds):
        index = {atom: k for k, atom in enumerate(group)}
        edges = [(index[a], index[b]) for a, b, _ in bonds if a in index]
        name = dictionary.describe([numbers[a] for a in group], edges, sum(charges[a] for a in group))
        counts[name] = counts.get(name, 0) + 1
    return " + ".join(("" if c == 1 else f"{c} ") + name for name, c in counts.items())


def quick(numbers, charges, bonds):
    """Ce qui se dit sans calcul : pour chaque molécule, ses atomes, sa formule, son nom, sa charge, ses électrons."""
    out = []
    for group in components(len(numbers), bonds):
        index = {atom: k for k, atom in enumerate(group)}
        edges = [(index[a], index[b]) for a, b, _ in bonds if a in index]
        sub = [numbers[a] for a in group]
        charge = sum(charges[a] for a in group)
        electrons = int(sum(xtb.Calculator(sub, charge=charge).basis.refocc)) - charge if all(xtb.supported(z) for z in sub) else 0
        out.append({"atoms": group, "formula": structures.pretty(structures.hill_formula(sub)),
                    "name": dictionary.identify(sub, edges, charge) or "", "charge": charge,
                    "unpaired": electrons % 2, "verdict": "", "notes": [], "cohesion": 0.0, "gap": 0.0,
                    "lost": [], "gained": [], "pieces": []})
    return out


def _relax(calc, positions, cancelled):
    def energy(x):
        if cancelled():
            raise Cancelled()
        r = calc.compute(x.reshape(-1, 3))
        return r.energy, r.gradient.ravel()

    found = minimize(energy, np.asarray(positions, float).ravel(), jac=True, method="L-BFGS-B",
                     options={"maxiter": 400, "gtol": 1e-4})
    return found.x.reshape(-1, 3)


def deep(numbers, charges, xy, bonds, cancelled=lambda: False):
    """Le verdict de chaque molécule du dessin, relaxée seule par GFN1-xTB."""
    from .lab import atom_energy
    positions = embed(numbers, bonds, xy)
    out = quick(numbers, charges, bonds)
    for entry in out:
        group = entry["atoms"]
        index = {atom: k for k, atom in enumerate(group)}
        local = [(index[a], index[b], o) for a, b, o in bonds if a in index]
        sub = [numbers[a] for a in group]
        if len(sub) == 1:
            entry["verdict"] = "atome"
            continue
        calc = xtb.Calculator(sub, charge=entry["charge"])
        x = _relax(calc, positions[group] - positions[group].mean(axis=0), cancelled)
        r = calc.compute(x)
        if not r.converged or r.unstable:
            entry["verdict"] = "échec"
            entry["notes"].append("le calcul n'a pas trouvé d'état électronique sensé")
            continue
        orders = analysis.mayer_bond_orders(r)
        pieces = analysis.species(sub, orders, r)
        linked = set()
        for members, _, _ in pieces:
            for a in members:
                for b in members:
                    if a < b and orders[a, b] >= analysis.FRAGMENT_BOND:
                        linked.add((a, b))
        shared = analysis.shared_density(r, sorted(linked))
        linked = {pair for pair in linked if shared.get(pair, 1.0) >= analysis.SHARED_BOND}
        drawn = {(min(a, b), max(a, b)) for a, b, _ in local}
        entry["lost"] = [(group[a], group[b]) for a, b in sorted(drawn - linked)]
        entry["gained"] = [(group[a], group[b]) for a, b in sorted(linked - drawn)]
        entry["pieces"] = [label for _, _, label in pieces]
        entry["cohesion"] = (sum(atom_energy(z) for z in sub) - r.energy) * EV
        # L'écart : du plus haut niveau qui porte un électron au plus bas qui a encore une place (nul pour un niveau
        # à demi rempli : O₂ et ses deux π* à un électron chacune).
        holding = np.flatnonzero(r.occupations > 0.05)
        room = np.flatnonzero(r.occupations < 1.95)
        if len(holding) and len(room):
            entry["gap"] = max(0.0, float(r.orbital_energies[room[0]] - r.orbital_energies[holding[-1]]) * EV)
        if len(pieces) > 1:
            entry["verdict"] = "se défait"
        elif entry["lost"] or entry["gained"]:
            entry["verdict"] = "se réarrange"
        else:
            entry["verdict"] = "tient"
    return out
