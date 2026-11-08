"""Les expériences du laboratoire moléculaire : cinq réactions réelles, de la plus exigeante à la plus simple.

Chacune est montée comme une expérience de faisceaux croisés : les réactifs, relaxés chacun seul par GFN1-xTB,
sont posés face à face par leurs atomes qui réagissent, puis lancés l'un vers l'autre avec une énergie de choc
donnée (dynamique libre, l'énergie se conserve) ; après le choc, un thermostat refroidit ce qui s'est formé, comme le
ferait un gaz autour. L'expérience réussit quand le dictionnaire reconnaît les produits attendus.

Les énergies de choc et le moment où le thermostat prend le relais ont été choisis par le calcul, sur plusieurs
tirages de l'agitation (tests/test_experiments.py en rejoue un par expérience) : assez pour franchir le col que
GFN1-xTB donne à chaque réaction, pas assez pour tout briser.
"""

from dataclasses import dataclass
from functools import lru_cache

import numpy as np
from scipy.optimize import minimize

from . import analysis, xtb

# Les réactifs, en coordonnées de départ approximatives (Å) : la relaxation les met au minimum GFN1-xTB.
FRAGMENTS = {
    # s-cis (les deux extrémités du même côté) : la seule forme qui peut se refermer sur l'éthylène.
    "butadiene": ([6, 6, 6, 6, 1, 1, 1, 1, 1, 1], 0, [
        (-1.48, -0.62, 0), (-0.74, 0.48, 0), (0.74, 0.48, 0), (1.48, -0.62, 0),
        (-2.56, -0.55, 0), (-1.05, -1.62, 0), (-1.25, 1.44, 0), (1.25, 1.44, 0), (2.56, -0.55, 0), (1.05, -1.62, 0)]),
    "ethylene": ([6, 6, 1, 1, 1, 1], 0, [
        (-0.67, 0, 0), (0.67, 0, 0), (-1.23, 0.92, 0), (-1.23, -0.92, 0), (1.23, 0.92, 0), (1.23, -0.92, 0)]),
    "chloromethane": ([6, 17, 1, 1, 1], 0, [
        (0, 0, 0), (0, 0, 1.78), (1.03, 0, -0.36), (-0.51, 0.89, -0.36), (-0.51, -0.89, -0.36)]),
    "hydroxide": ([8, 1], -1, [(0, 0, 0), (0, 0, 0.97)]),
    "hydronium": ([8, 1, 1, 1], 1, [(0, 0, 0.1), (0.95, 0, -0.2), (-0.47, 0.82, -0.2), (-0.47, -0.82, -0.2)]),
    "ammonia": ([7, 1, 1, 1], 0, [(0, 0, 0.12), (0.94, 0, -0.27), (-0.47, 0.81, -0.27), (-0.47, -0.81, -0.27)]),
    "methyl": ([6, 1, 1, 1], 0, [(0, 0, 0), (1.08, 0, 0), (-0.54, 0.94, 0), (-0.54, -0.94, 0)]),
    "hydroxyl": ([8, 1], 0, [(0, 0, 0), (0, 0, 0.98)]),
    "hydrogen": ([1], 0, [(0, 0, 0)]),
}


@lru_cache(maxsize=None)
def fragment(name):
    """Le réactif relaxé seul (L-BFGS sur GFN1-xTB), centré sur son centre de masse : (numéros, positions, charge)."""
    numbers, charge, start = FRAGMENTS[name]
    positions = np.array(start, float)
    if len(numbers) > 1:
        calc = xtb.Calculator(numbers, charge=charge)

        def energy(x):
            r = calc.compute(x.reshape(-1, 3))
            return r.energy, r.gradient.ravel()

        positions = minimize(energy, positions.ravel(), jac=True, method="L-BFGS-B",
                             options={"maxiter": 300, "gtol": 2e-4}).x.reshape(-1, 3)
    masses = np.array([analysis.isotope_mass(z, 0) for z in numbers])
    positions -= masses @ positions / masses.sum()
    return list(numbers), positions, charge


def _rotation(a, b):
    """La rotation qui porte le vecteur unitaire a sur b."""
    a, b = a / np.linalg.norm(a), b / np.linalg.norm(b)
    v, c = np.cross(a, b), float(a @ b)
    if c < -0.999999:
        axis = np.cross(a, [1.0, 0, 0] if abs(a[0]) < 0.9 else [0, 1.0, 0])
        axis /= np.linalg.norm(axis)
        return 2 * np.outer(axis, axis) - np.eye(3)
    k = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + k + k @ k / (1 + c)


def _front(positions, atom, away=None):
    """D'où l'atome réagit : à l'opposé de l'atome `away` s'il est donné (le dos du carbone, face au chlore partant),
    sinon à l'opposé de ses voisins (la paire libre de NH₃, l'O de OH) ; pour un atome que ses voisins entourent dans un
    plan (CH₃·), la normale à ce plan."""
    if away is not None:
        v = positions[atom] - positions[away]
        return v / np.linalg.norm(v)
    others = np.delete(positions, atom, axis=0)
    if len(others) == 0:
        return np.array([0, 0, 1.0])
    v = positions[atom] - others.mean(axis=0)
    if np.linalg.norm(v) > 0.2:
        return v / np.linalg.norm(v)
    centred = others - others.mean(axis=0)
    return np.linalg.svd(centred)[2][-1]


def facing(a_name, a_atom, b_name, b_atom, gap, a_away=None):
    """Deux réactifs face à face : l'atome b_atom de B à `gap` Å de a_atom de A, chacun tourné vers l'autre."""
    na, pa, qa = fragment(a_name)
    nb, pb, qb = fragment(b_name)
    ua = _front(pa, a_atom, a_away)
    pb = pb @ _rotation(_front(pb, b_atom), -ua).T
    pb = pb - pb[b_atom] + pa[a_atom] + gap * ua
    return na + nb, np.vstack([pa, pb]), qa + qb, len(na)


def diels_alder():
    """L'éthylène posé à plat au-dessus du butadiène s-cis, sa double liaison parallèle à C1···C4, à 3,2 Å."""
    nd, pd, _ = fragment("butadiene")
    ne, pe, _ = fragment("ethylene")
    ends = pd[[0, 3]]
    normal = np.linalg.svd(pd[:4] - pd[:4].mean(axis=0))[2][-1]
    # L'éthylène dans un plan parallèle : sa normale sur celle du butadiène, puis sa liaison C=C sur C1···C4.
    pe = pe @ _rotation(np.linalg.svd(pe - pe.mean(axis=0))[2][-1], normal).T
    axis_e = pe[1] - pe[0]
    axis_d = ends[1] - ends[0]
    axis_e -= normal * (axis_e @ normal)
    axis_d -= normal * (axis_d @ normal)
    pe = pe @ _rotation(axis_e, axis_d).T
    pe = pe - pe[:2].mean(axis=0) + ends.mean(axis=0) + 3.2 * normal
    return nd + ne, np.vstack([pd, pe]), 0, len(nd)


APPROACH, COOLING, SUCCEEDED, FAILED = 0, 1, 2, 3          # les phases d'une expérience


@dataclass(frozen=True)
class Experiment:
    title: str                 # le nom affiché
    equation: str              # ce qui doit se passer
    goal: tuple                # les produits attendus (noms du dictionnaire)
    build: object              # () → (numéros, positions, charge, nombre d'atomes du premier réactif)
    collision_ev: float        # l'énergie du choc, le long de la ligne des centres
    cool_fs: float             # quand le thermostat prend le relais
    limit_fs: float            # au-delà : l'expérience a échoué
    explanation: str           # ce qu'on regarde, et ce que le calcul vaut face à la mesure
    temperature: float = 300.0


EXPERIMENTS = [
    Experiment(
        "Diels-Alder", "buta-1,3-diène + éthylène → cyclohexène", ("cyclohexène",), diels_alder, 2.5, 150, 1200,
        "Une cycloaddition : les deux extrémités du butadiène (replié en s-cis) se referment sur la double liaison de "
        "l'éthylène, deux liaisons C–C naissent ensemble et un cycle à six atomes se forme. Il faut franchir un col : "
        "en choc frontal, GFN1-xTB le place entre 0,6 et 1,0 eV (barrière mesurée ≈ 1,2 eV) ; à 2,5 eV, le choc passe "
        "malgré l'agitation. ΔE : GFN1 −2,7 eV, mesuré ≈ −1,7 eV."),
    Experiment(
        "Substitution SN2", "OH⁻ + CH₃Cl → CH₃OH + Cl⁻", ("méthanol", "ion chlorure"),
        lambda: facing("chloromethane", 0, "hydroxide", 0, 3.0, a_away=1), 0.5, 60, 1200,
        "L'ion hydroxyde attaque le carbone par l'arrière, à l'opposé du chlore : la liaison C–O se forme pendant que "
        "C–Cl se rompt, et les trois hydrogènes se retournent comme un parapluie (inversion de Walden). De côté, l'OH⁻ "
        "arracherait plutôt un proton. ΔE : GFN1 −4,1 eV, mesuré ≈ −2,2 eV."),
    Experiment(
        "Transfert de proton", "H₃O⁺ + NH₃ → H₂O + NH₄⁺", ("eau", "ion ammonium"),
        lambda: facing("hydronium", 1, "ammonia", 0, 2.0), 0.2, 40, 1000,
        "Un acide et une base : l'ammoniac, plus basique que l'eau, arrache un proton à l'ion hydronium, sans col à "
        "franchir. ΔE : GFN1 −1,9 eV, mesuré ≈ −1,7 eV (affinités protoniques 204 et 165 kcal/mol)."),
    Experiment(
        "Recombinaison de radicaux", "2 CH₃· → éthane", ("éthane",),
        lambda: facing("methyl", 0, "methyl", 0, 3.4), 0.3, 30, 1000,
        "Deux radicaux méthyle, chacun avec un électron non apparié, les mettent en commun : la liaison C–C naît sans "
        "col. Le thermostat joue le gaz autour, qui emporte l'énergie libérée. ΔE : GFN1 −5,6 eV, mesuré ≈ −3,9 eV "
        "(GFN1 ne polarise pas les spins : il surestime les liaisons entre radicaux)."),
    Experiment(
        "Formation de l'eau", "OH· + H· → H₂O", ("eau",),
        lambda: facing("hydroxyl", 0, "hydrogen", 0, 3.0), 0.3, 20, 1000,
        "Le plus simple : un atome d'hydrogène s'unit au radical hydroxyle. Seuls, ils rebondiraient — trois atomes "
        "ne savent pas où mettre l'énergie de la liaison ; le thermostat joue le troisième corps du gaz (H + OH + M). "
        "ΔE : GFN1 −8,1 eV, mesuré ≈ −5,1 eV (spins non polarisés, comme pour CH₃·)."),
]


def launch(numbers, positions, split, masses, energy_ev):
    """Les vitesses du choc (Å/fs) : les deux réactifs l'un vers l'autre le long de la ligne de leurs centres de masse,
    avec `energy_ev` d'énergie relative, quantité de mouvement totale nulle."""
    a, b = np.arange(split), np.arange(split, len(numbers))
    ma, mb = masses[a].sum(), masses[b].sum()
    ca, cb = masses[a] @ positions[a] / ma, masses[b] @ positions[b] / mb
    u = (cb - ca) / np.linalg.norm(cb - ca)
    mu = ma * mb / (ma + mb)
    speed = np.sqrt(2 * energy_ev / xtb.HARTREE_EV / mu * analysis.ACCELERATION)    # vitesse relative, Å/fs
    v = np.zeros_like(positions)
    v[a] = u * speed * mb / (ma + mb)
    v[b] = -u * speed * ma / (ma + mb)
    return v


def names(named):
    """Les noms du dictionnaire dans les étiquettes du recensement (« H₂O · eau » → « eau »)."""
    return [label.split(" · ", 1)[-1] for label in named]


def reached(named, goal):
    """Les produits attendus sont tous là (chacun autant de fois qu'il est demandé)."""
    have = names(named)
    return all(have.count(g) >= goal.count(g) for g in set(goal))


def internal(velocities, masses, split):
    """L'agitation thermique de chaque réactif sans sa translation : le choc garde exactement l'énergie annoncée, comme
    dans un faisceau moléculaire (vitesse relative choisie, molécules chaudes à l'intérieur)."""
    v = np.array(velocities, float)
    for part in (slice(0, split), slice(split, len(v))):
        m = masses[part]
        if len(m) and m.sum() > 0:
            v[part] -= (m @ v[part]) / m.sum()
    return v
