"""La réaction d'un dessin de l'assembleur : réactifs → produits, par le calcul.

Ce n'est pas une recherche exhaustive d'états de transition (trop coûteuse pour un outil interactif) mais un
« nanoréacteur », la méthode de Wang et al. (Nat. Chem. 2014) : les molécules dessinées, chacune relaxée seule par
GFN1-xTB, sont réunies dans une sphère (la paroi du réacteur du laboratoire, posée sur leurs nuages) et agitées à une
température choisie (thermostat CSVR et bain de chaque atome), pendant DURATION_FS, en TRIALS essais indépendants (des
vitesses de départ différentes). Le recensement du laboratoire suit les espèces (indices de Mayer et densité partagée,
nommées par le dictionnaire). À la fin d'un essai, le gaz est refroidi à 300 K, puis chaque espèce présente est relaxée
seule : ce sont les produits de cet essai. Les essais qui mènent aux mêmes produits sont regroupés ; leur énergie de
réaction est celle de GFN1-xTB :

    ΔE = Σ E(produit relaxé seul) − Σ E(réactif relaxé seul).

Limites, dites à l'écran : GFN1-xTB n'a pas de polarisation de spin (les liaisons formées entre radicaux sont trop fortes,
O₂ n'a pas de barrière de spin) ; un essai ne dure que quelques centaines de femtosecondes, à une densité de liquide et une
température élevée : une issue qui n'apparaît pas peut exister, et la fréquence d'une issue n'est pas une vitesse réelle.
"""

import time

import numpy as np

from . import analysis, assembly, dictionary, structures, xtb

TRIALS = 3
DURATION_FS = 600.0
COOL_FS = 120.0
MAX_ATOMS = 30
CONTACT = 2.8          # Å : les réactifs rapprochés jusqu'à ce que leurs atomes les plus proches soient à cette distance
EV = xtb.HARTREE_EV


def _bonds(numbers, result):
    """Les liaisons d'une molécule relaxée : (a, b, ordre arrondi 1, 2 ou 3), comme le recensement les voit."""
    orders = analysis.mayer_bond_orders(result)
    n = len(numbers)
    pairs = [(a, b) for a in range(n) for b in range(a + 1, n) if orders[a, b] >= analysis.FRAGMENT_BOND]
    shared = analysis.shared_density(result, pairs)
    out = []
    for a, b in pairs:
        if shared.get((a, b), 1.0) < analysis.SHARED_BOND:
            continue
        o = orders[a, b]
        out.append((a, b, 1 if o < 1.5 else 2 if o < 2.5 else 3))
    return out


def _flat(positions, bonds):
    """Un départ de dessin : la molécule projetée sur son plan principal, une liaison moyenne valant 1."""
    p = positions - positions.mean(axis=0)
    if len(p) == 1:
        return np.zeros((1, 2))
    axes = np.linalg.svd(p, full_matrices=False)[2][:2]
    xy = p @ axes.T
    lengths = [np.linalg.norm(xy[a] - xy[b]) for a, b, _ in bonds]
    scale = np.mean(lengths) if lengths and np.mean(lengths) > 1e-6 else 1.0
    return xy / scale


class Products:
    """Les produits, chacun relaxé seul."""

    def __init__(self, cancelled):
        self.cancelled = cancelled

    def relax(self, numbers, charge, positions):
        """Une espèce relaxée seule : son énergie (Eh) et sa fiche dessinable. Si elle se défait en se relaxant, ses
        morceaux (chacun relaxé à son tour)."""
        calc = xtb.Calculator(numbers, charge=charge)
        if len(numbers) == 1:
            r = calc.compute(np.zeros((1, 3)))
            x = np.zeros((1, 3))
        else:
            x = assembly._relax(calc, positions - positions.mean(axis=0), self.cancelled)
            r = calc.compute(x)
        orders = analysis.mayer_bond_orders(r)
        pieces = analysis.species(numbers, orders, r)
        if len(pieces) > 1:
            out = []
            for members, _, _ in pieces:
                sub = [numbers[k] for k in members]
                q = float(np.sum(r.atom_charges[members]))
                out += self.relax(sub, int(round(q)) if abs(q) >= .6 else 0, x[members])
            return out
        bonds = _bonds(numbers, r)
        charges = [0] * len(numbers)
        if charge:
            # la charge formelle sur l'atome qui porte le plus de la charge nette (le O de OH⁻, le N de NH₄⁺)
            pick = int(np.argmax(np.sign(charge) * r.atom_charges))
            charges[pick] = charge
        label = pieces[0][2] if pieces else structures.pretty(structures.hill_formula(numbers))
        entry = {"label": label, "name": label.split(" · ", 1)[1] if " · " in label else "",
                 "formula": structures.pretty(structures.hill_formula(numbers)), "numbers": list(numbers),
                 "charges": charges, "bonds": bonds, "xy": _flat(x, bonds), "energy": float(r.energy)}
        return [entry]


def _pack(positions, groups):
    """Les molécules rapprochées (ou écartées) ensemble, dans la disposition du dessin : leurs centres sur des rayons
    issus du centre commun, à l'échelle où les deux atomes les plus proches de deux molécules sont à CONTACT."""
    if len(groups) < 2:
        return positions
    centres = np.array([positions[g].mean(axis=0) for g in groups])
    middle = centres.mean(axis=0)
    shapes = [positions[g] - c for g, c in zip(groups, centres)]

    def placed(scale):
        out = positions.copy()
        for g, c, shape in zip(groups, centres, shapes):
            out[g] = middle + scale * (c - middle) + shape
        return out

    def closest(p):
        return min(float(np.min(np.linalg.norm(p[a][:, None, :] - p[b][None, :, :], axis=2)))
                   for i, a in enumerate(groups) for b in groups[i + 1:])

    low, high = 0.0, 1.0
    while closest(placed(high)) < CONTACT and high < 64:
        high *= 2
    for _ in range(40):
        mid = (low + high) / 2
        low, high = (mid, high) if closest(placed(mid)) < CONTACT else (low, mid)
    return placed(high)


def reactants(numbers, charges, xy, bonds, cancelled):
    """Les réactifs : chaque molécule du dessin relaxée seule, posée à sa place du dessin, puis toutes rapprochées
    jusqu'au contact (_pack) ; (positions, énergie totale, étiquettes des molécules)."""
    positions = assembly.embed(numbers, bonds, xy)
    energies = 0.0
    labels = []
    groups = assembly.components(len(numbers), bonds)
    for group in groups:
        sub = [numbers[a] for a in group]
        charge = sum(charges[a] for a in group)
        index = {atom: k for k, atom in enumerate(group)}
        labels.append(dictionary.describe(sub, [(index[a], index[b]) for a, b, _ in bonds if a in index], charge))
        calc = xtb.Calculator(sub, charge=charge)
        centre = positions[group].mean(axis=0)
        if len(sub) == 1:
            energies += calc.compute(np.zeros((1, 3))).energy
            continue
        x = assembly._relax(calc, positions[group] - centre, cancelled)
        energies += calc.compute(x).energy
        positions[group] = x + centre
    return _pack(positions, [list(g) for g in groups]), energies, labels


def trial(numbers, positions, charge, temperature, seed, duration, cancelled, report):
    """Un essai : les réactifs dans la sphère, agités à `temperature` pendant `duration` fs, puis refroidis ; les espèces
    présentes à la fin (atomes, étiquette, charge nette), et l'instant où les espèces ont changé pour la première fois."""
    from .lab import MoleculeLab, THERMOSTAT, TIME_STEP_FS
    lab = MoleculeLab(rng_seed=seed, background=False)
    lab.charge = charge
    lab.temperature = float(temperature)
    lab._set_structure("réaction", numbers, positions)
    lab._set_wall(lab._enclosing())
    lab.dynamics = THERMOSTAT
    lab.velocities = lab._thermal_velocities(lab.temperature)
    start, first = lab.named, None
    steps = int(duration / TIME_STEP_FS)
    for i in range(steps):
        if cancelled():
            raise assembly.Cancelled()
        lab._step()
        if lab.paused:                                    # le cycle auto-cohérent s'est emballé : l'essai s'arrête là
            break
        if first is None and lab.named != start:
            first = lab.time_fs
        if i % 40 == 0:
            report(lab.time_fs, lab.census)
    lab._do_dynamics(THERMOSTAT, 300.0)
    end = lab.time_fs + COOL_FS
    while lab.time_fs < end and not lab.paused:
        if cancelled():
            raise assembly.Cancelled()
        lab._step()
    out = []
    for members, _, label in lab.groups:
        q = float(np.sum(lab.result.atom_charges[list(members)])) if lab.result is not None else 0.0
        out.append((list(members), label, int(round(q)) if abs(q) >= .6 else 0))
    return out, lab.positions.copy(), first


def explore(numbers, charges, xy, bonds, temperature, cancelled, publish, trials=TRIALS, duration=DURATION_FS):
    """La réaction du dessin. `publish(état)` reçoit l'avancement et les issues trouvées après chaque essai."""
    numbers = [int(z) for z in numbers]
    if len(numbers) > MAX_ATOMS:
        raise ValueError(f"au plus {MAX_ATOMS} atomes pour chercher une réaction (un essai coûterait plusieurs minutes)")
    charge = int(sum(charges))
    # phase : 0 les réactifs se relaxent, 1 un essai à chaud (puis refroidi), 2 ses produits se relaxent, 3 fini
    state = {"done": False, "error": "", "phase": 0, "trial": 0, "time_fs": 0.0, "census": "", "remaining": 0.0,
             "fraction": 0.0, "reactants": "", "outcomes": [], "temperature": float(temperature), "trials": trials,
             "duration": duration}
    publish(dict(state))
    positions, reactant_energy, reactant_labels = reactants(numbers, charges, xy, bonds, cancelled)
    reactant_key = tuple(sorted(reactant_labels))
    state["reactants"] = _count(reactant_labels)
    products = Products(cancelled)
    outcomes = {}
    started = time.monotonic()
    for k in range(trials):
        def report(time_fs, census, k=k):
            state.update(phase=1, trial=k + 1, time_fs=time_fs, census=census,
                         fraction=(k + time_fs / (duration + COOL_FS)) / trials)
            # Le temps restant, d'après le rythme de calcul des essais jusqu'ici.
            if state["fraction"] > .02:
                state["remaining"] = (time.monotonic() - started) * (1 - state["fraction"]) / state["fraction"]
            publish(dict(state))
        species, final, first = trial(numbers, positions, charge, temperature, 11 + k, duration, cancelled, report)
        state["phase"] = 2
        publish(dict(state))
        made = []
        for members, _, q in species:
            made += products.relax([numbers[a] for a in members], q, final[members])
        key = tuple(sorted(p["label"] for p in made))
        energy = sum(p["energy"] for p in made)
        o = outcomes.setdefault(key, {"count": 0, "products": made, "delta": (energy - reactant_energy) * EV,
                                      "first": [], "label": _count([p["label"] for p in made])})
        o["count"] += 1
        if first is not None:
            o["first"].append(first)
        state["outcomes"] = sorted(({"label": v["label"], "count": v["count"], "delta": v["delta"],
                                     "first": float(np.mean(v["first"])) if v["first"] else 0.0,
                                     "unchanged": key == reactant_key, "products": v["products"]}
                                    for key, v in outcomes.items()), key=lambda v: (-v["count"], v["delta"]))
        state["fraction"] = (k + 1) / trials
        publish(dict(state))
    state.update(done=True, phase=3, remaining=0.0)
    publish(dict(state))
    return state


def _count(labels):
    """« CO₂ · dioxyde de carbone + 2 H₂O · eau » : des espèces, comptées."""
    counts = {}
    for label in labels:
        counts[label] = counts.get(label, 0) + 1
    return " + ".join(("" if c == 1 else f"{c} ") + name for name, c in sorted(counts.items(), key=lambda kv: kv[0]))
