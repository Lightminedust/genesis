"""Molécules de départ, et le nom des espèces reconnues.

Les coordonnées ne sont qu'un point de départ grossier (Å) : le laboratoire les relaxe aussitôt avec GFN1-xTB, et
c'est la géométrie calculée qu'on voit. Les mélanges du réacteur posent plusieurs molécules dans la même boîte. Les
molécules du vivant (glucose, acide gras, acide aminé…) partent des conformères 3D de PubChem (data/molecules).
"""

from pathlib import Path

import numpy as np

MOLECULES = Path(__file__).resolve().parent / "data" / "molecules"
_SYMBOLS = ("X H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Sc Ti V Cr Mn Fe Co Ni Cu Zn Ga Ge As Se Br Kr Rb Sr Y "
            "Zr Nb Mo Tc Ru Rh Pd Ag Cd In Sn Sb Te I Xe Cs Ba La Ce Pr Nd Pm Sm Eu Gd Tb Dy Ho Er Tm Yb Lu Hf Ta W Re Os "
            "Ir Pt Au Hg Tl Pb Bi Po At Rn").split()


def _xyz(name):
    """Les atomes d'un fichier XYZ de data/molecules : [(Z, (x, y, z))], Å."""
    lines = (MOLECULES / name).read_text(encoding="utf-8").splitlines()
    count = int(lines[0])
    return [(_SYMBOLS.index(row.split()[0]), tuple(float(v) for v in row.split()[1:4])) for row in lines[2:2 + count]]

PRESETS = [
    ("H₂ · dihydrogène", [(1, (0, 0, 0)), (1, (0, 0, 0.74))]),
    ("H₂O · eau", [(8, (0, 0, 0)), (1, (0, 0.76, 0.59)), (1, (0, -0.76, 0.59))]),
    ("CH₄ · méthane", [(6, (0, 0, 0)), (1, (0.63, 0.63, 0.63)), (1, (-0.63, -0.63, 0.63)), (1, (-0.63, 0.63, -0.63)),
                       (1, (0.63, -0.63, -0.63))]),
    ("NH₃ · ammoniac", [(7, (0, 0, 0.12)), (1, (0.94, 0, -0.27)), (1, (-0.47, 0.81, -0.27)), (1, (-0.47, -0.81, -0.27))]),
    ("CO₂ · dioxyde de carbone", [(6, (0, 0, 0)), (8, (0, 0, 1.16)), (8, (0, 0, -1.16))]),
    ("N₂ · diazote", [(7, (0, 0, 0)), (7, (0, 0, 1.1))]),
    ("O₂ · dioxygène", [(8, (0, 0, 0)), (8, (0, 0, 1.21))]),
    ("HCl · chlorure d'hydrogène", [(17, (0, 0, 0)), (1, (0, 0, 1.27))]),
    ("C₂H₄ · éthylène", [(6, (0, 0, 0.67)), (6, (0, 0, -0.67)), (1, (0, 0.92, 1.24)), (1, (0, -0.92, 1.24)),
                         (1, (0, 0.92, -1.24)), (1, (0, -0.92, -1.24))]),
    ("C₂H₂ · acétylène", [(6, (0, 0, 0.6)), (6, (0, 0, -0.6)), (1, (0, 0, 1.66)), (1, (0, 0, -1.66))]),
    ("CH₂O · formaldéhyde", [(6, (0, 0, 0)), (8, (0, 0, 1.21)), (1, (0, 0.94, -0.59)), (1, (0, -0.94, -0.59))]),
    ("CH₃OH · méthanol", [(6, (-0.05, 0.66, 0)), (8, (-0.05, -0.76, 0)), (1, (-1.09, 0.97, 0)), (1, (0.43, 1.07, 0.89)),
                           (1, (0.43, 1.07, -0.89)), (1, (0.87, -1.06, 0))]),
    ("H₂O₂ · peroxyde d'hydrogène", [(8, (0, 0.73, -0.06)), (8, (0, -0.73, -0.06)), (1, (0.84, 0.89, 0.47)),
                                     (1, (-0.84, -0.89, 0.47))]),
    ("C₆H₆ · benzène", [(6, (1.39 * np.cos(k * np.pi / 3), 1.39 * np.sin(k * np.pi / 3), 0)) for k in range(6)]
                        + [(1, (2.47 * np.cos(k * np.pi / 3), 2.47 * np.sin(k * np.pi / 3), 0)) for k in range(6)]),
    # Le triangle plan D3h, le minimum de Au₆ (DFT et spectres infrarouges) ; GFN1-xTB le trouve aussi le plus bas,
    # 0,30 eV sous le pentagone centré, 0,60 eV sous la pyramide, 1,10 eV sous l'octaèdre. Côtés 2,631 Å, centre 2,823 Å.
    ("Au₆ · agrégat d'or", [(79, (3.035 * np.cos(np.pi / 2 + k * 2 * np.pi / 3), 3.035 * np.sin(np.pi / 2 + k * 2 * np.pi / 3), 0))
                            for k in range(3)]
                           + [(79, (1.630 * np.cos(np.pi / 6 + k * 2 * np.pi / 3), 1.630 * np.sin(np.pi / 6 + k * 2 * np.pi / 3), 0))
                              for k in range(3)]),
    # Des molécules du vivant, plus grandes (13 à 38 atomes) : un sucre, un lipide (acide gras saturé en C12), le
    # squelette des graisses, un acide aminé, un alcaloïde.
    ("C₆H₁₂O₆ · glucose", _xyz("glucose.xyz")),
    ("C₁₂H₂₄O₂ · acide laurique", _xyz("lauric_acid.xyz")),
    ("C₃H₈O₃ · glycérol", _xyz("glycerol.xyz")),
    ("C₃H₇NO₂ · alanine", _xyz("alanine.xyz")),
    ("C₈H₁₀N₄O₂ · caféine", _xyz("caffeine.xyz")),
]

# Le réacteur : des molécules posées loin les unes des autres dans une même boîte.
REACTIONS = [
    ("2 H₂ + O₂", [("H₂ · dihydrogène", (-2.6, 0, 0)), ("H₂ · dihydrogène", (2.6, 0.4, 0)), ("O₂ · dioxygène", (0, 0, 2.2))]),
    ("H₂ + Cl₂", [("H₂ · dihydrogène", (-2.2, 0, 0)), ("Cl₂", (2.2, 0, 0))]),
    ("CH₄ + 2 O₂", [("CH₄ · méthane", (0, 0, 0)), ("O₂ · dioxygène", (-3.0, 0, 0.5)), ("O₂ · dioxygène", (3.0, 0, -0.5))]),
    ("H₂O + H₂O", [("H₂O · eau", (-1.6, 0, 0)), ("H₂O · eau", (1.6, 0, 0))]),
]
EXTRA = {"Cl₂": [(17, (0, 0, 0)), (17, (0, 0, 1.99))]}

NAMES = {
    "H2": "dihydrogène", "O2": "dioxygène", "N2": "diazote", "Cl2": "dichlore", "H2O": "eau", "HO": "radical hydroxyle",
    "HO2": "radical hydroperoxyle", "H2O2": "peroxyde d'hydrogène", "H": "atome d'hydrogène", "O": "atome d'oxygène",
    "Cl": "atome de chlore", "ClH": "chlorure d'hydrogène", "CH4": "méthane", "CH3": "radical méthyle",
    "CO": "monoxyde de carbone", "CO2": "dioxyde de carbone", "H3N": "ammoniac", "H2N": "radical amidogène", "C2H4": "éthylène",
    "C2H2": "acétylène", "C2H6": "éthane", "CH2O": "formaldéhyde", "CH4O": "méthanol", "C6H6": "benzène",
    "O3": "ozone", "N": "atome d'azote", "C": "atome de carbone", "H3O": "radical H₃O", "HN": "radical imidogène",
    "Au6": "agrégat d'or", "C6H12O6": "glucose", "C12H24O2": "acide laurique", "C3H8O3": "glycérol",
    "C3H7NO2": "alanine", "C8H10N4O2": "caféine",
}


def preset(index):
    name, atoms = PRESETS[index]
    return name, [z for z, _ in atoms], np.array([p for _, p in atoms], float)


def molecule_atoms(name):
    for label, atoms in PRESETS:
        if label == name:
            return atoms
    return EXTRA[name]


def reaction(index):
    """Les atomes d'un mélange : chaque molécule tournée au hasard (graine fixe) et posée à sa place."""
    label, parts = REACTIONS[index]
    rng = np.random.default_rng(index + 7)
    numbers, positions = [], []
    for name, offset in parts:
        atoms = molecule_atoms(name)
        xyz = np.array([p for _, p in atoms], float)
        xyz -= xyz.mean(axis=0)
        q, _ = np.linalg.qr(rng.normal(size=(3, 3)))
        xyz = xyz @ q.T + np.array(offset)
        numbers += [z for z, _ in atoms]
        positions += list(xyz)
    return label, numbers, np.array(positions)


def hill_formula(numbers):
    """La formule brute en notation de Hill : C, puis H, puis les autres par ordre alphabétique."""
    from .xtb import SYMBOLS
    counts = {}
    for z in numbers:
        counts[SYMBOLS[z]] = counts.get(SYMBOLS[z], 0) + 1
    order = []
    if "C" in counts:
        order = ["C"] + (["H"] if "H" in counts else [])
    order += sorted(s for s in counts if s not in order)
    return "".join(s + (str(counts[s]) if counts[s] > 1 else "") for s in order)


SUBSCRIPT = str.maketrans("0123456789", "₀₁₂₃₄₅₆₇₈₉")


def pretty(formula):
    return formula.translate(SUBSCRIPT)


def label(numbers):
    """« H₃N · ammoniac » : la formule, et le nom quand l'espèce est connue."""
    formula = hill_formula(numbers)
    name = NAMES.get(formula)
    return pretty(formula) + (f" · {name}" if name else "")
