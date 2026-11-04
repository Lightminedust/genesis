"""Le dictionnaire des espèces : reconnaître une molécule à sa structure, pas à sa formule.

Une formule brute ne suffit pas : C₂H₆O est l'éthanol (CH₃–CH₂–OH) ou l'éther diméthylique (CH₃–O–CH₃). Chaque
entrée est donc écrite en SMILES (Weininger, J. Chem. Inf. Comput. Sci. 28, 31, 1988) — un sous-ensemble : atomes du
jeu organique (B C N O P S F Cl Br I) à hydrogènes implicites selon leurs valences usuelles, atomes entre crochets
([OH], [NH4+], [Na]) avec leurs hydrogènes et leur charge, liaisons - = #, branches, fermetures de cycle ; pas de
notation aromatique (les cycles sont écrits en Kekulé). Une molécule est reconnue par son graphe : les atomes (tous,
hydrogènes compris) et qui est lié à qui, sans l'ordre des liaisons (pendant la dynamique, un indice de Mayer de 1,4
n'est ni simple ni double), plus sa charge nette pour un ion. L'empreinte du graphe est un raffinement de
Weisfeiler–Lehman (chaque atome reçoit l'élément, puis la liste triée des étiquettes de ses voisins, quatre fois) :
deux isomères y diffèrent, deux dessins du même graphe y sont égaux.
"""

from functools import lru_cache

from . import structures

ORGANIC = {"B": [3], "C": [4], "N": [3, 5], "O": [2], "P": [3, 5], "S": [2, 4, 6], "F": [1], "Cl": [1], "Br": [1],
           "I": [1]}

# (SMILES, nom, name). Les hydrogènes implicites sont ceux des valences usuelles ; un radical s'écrit entre crochets.
ENTRIES = [
    # Atomes et ions seuls.
    ("[H]", "atome d'hydrogène", "hydrogen atom"), ("[C]", "atome de carbone", "carbon atom"),
    ("[N]", "atome d'azote", "nitrogen atom"), ("[O]", "atome d'oxygène", "oxygen atom"),
    ("[F]", "atome de fluor", "fluorine atom"), ("[Cl]", "atome de chlore", "chlorine atom"),
    ("[Br]", "atome de brome", "bromine atom"), ("[I]", "atome d'iode", "iodine atom"),
    ("[S]", "atome de soufre", "sulfur atom"), ("[P]", "atome de phosphore", "phosphorus atom"),
    ("[Si]", "atome de silicium", "silicon atom"), ("[B]", "atome de bore", "boron atom"),
    ("[Na]", "atome de sodium", "sodium atom"), ("[He]", "atome d'hélium", "helium atom"),
    ("[Ne]", "atome de néon", "neon atom"), ("[Ar]", "atome d'argon", "argon atom"),
    ("[H+]", "proton", "proton"), ("[H-]", "ion hydrure", "hydride ion"), ("[Na+]", "ion sodium", "sodium ion"),
    ("[Cl-]", "ion chlorure", "chloride ion"), ("[F-]", "ion fluorure", "fluoride ion"),
    ("[OH-]", "ion hydroxyde", "hydroxide ion"), ("[OH3+]", "ion hydronium", "hydronium ion"),
    ("[NH4+]", "ion ammonium", "ammonium ion"),
    # Molécules diatomiques et petites espèces inorganiques.
    ("[H][H]", "dihydrogène", "dihydrogen"), ("O=O", "dioxygène", "dioxygen"), ("N#N", "diazote", "dinitrogen"),
    ("FF", "difluor", "difluorine"), ("ClCl", "dichlore", "dichlorine"), ("BrBr", "dibrome", "dibromine"),
    ("II", "diiode", "diiodine"), ("O=[O+][O-]", "ozone", "ozone"),
    ("O", "eau", "water"), ("[OH]", "radical hydroxyle", "hydroxyl radical"),
    ("OO", "peroxyde d'hydrogène", "hydrogen peroxide"), ("O[O]", "radical hydroperoxyle", "hydroperoxyl radical"),
    ("[OH2]", "eau", "water"),
    ("N", "ammoniac", "ammonia"), ("[NH2]", "radical amidogène", "amidogen radical"),
    ("[NH]", "radical imidogène", "imidogen radical"), ("NN", "hydrazine", "hydrazine"), ("N=N", "diazène", "diazene"),
    ("[N]=O", "monoxyde d'azote", "nitric oxide"), ("O=[N][O]", "dioxyde d'azote", "nitrogen dioxide"),
    ("N#[N+][O-]", "protoxyde d'azote", "nitrous oxide"), ("ON=O", "acide nitreux", "nitrous acid"),
    ("O[N+](=O)[O-]", "acide nitrique", "nitric acid"), ("C#N", "cyanure d'hydrogène", "hydrogen cyanide"),
    ("[C-]#[O+]", "monoxyde de carbone", "carbon monoxide"), ("O=C=O", "dioxyde de carbone", "carbon dioxide"),
    ("OC(O)=O", "acide carbonique", "carbonic acid"), ("S", "sulfure d'hydrogène", "hydrogen sulfide"),
    ("O=S=O", "dioxyde de soufre", "sulfur dioxide"), ("O=S(=O)=O", "trioxyde de soufre", "sulfur trioxide"),
    ("OS(=O)(=O)O", "acide sulfurique", "sulfuric acid"), ("Cl", "chlorure d'hydrogène", "hydrogen chloride"),
    ("F", "fluorure d'hydrogène", "hydrogen fluoride"), ("Br", "bromure d'hydrogène", "hydrogen bromide"),
    ("I", "iodure d'hydrogène", "hydrogen iodide"), ("OCl", "acide hypochloreux", "hypochlorous acid"),
    ("ClOCl", "monoxyde de dichlore", "dichlorine monoxide"), ("[Na]Cl", "chlorure de sodium", "sodium chloride"),
    ("[Na]O", "hydroxyde de sodium", "sodium hydroxide"), ("P", "phosphine", "phosphine"),
    ("[SiH4]", "silane", "silane"), ("B", "borane", "borane"),
    ("[BH2]1[H][BH2][H]1", "diborane", "diborane"),
    ("S=C=S", "disulfure de carbone", "carbon disulfide"), ("O=C=S", "sulfure de carbonyle", "carbonyl sulfide"),
    # Hydrocarbures et leurs radicaux.
    ("C", "méthane", "methane"), ("[CH3]", "radical méthyle", "methyl radical"),
    ("[CH2]", "méthylène", "methylene"), ("[CH]", "méthylidyne", "methylidyne"),
    ("CC", "éthane", "ethane"), ("C[CH2]", "radical éthyle", "ethyl radical"), ("C=C", "éthylène", "ethylene"),
    ("C=[CH]", "radical vinyle", "vinyl radical"), ("C#C", "acétylène", "acetylene"),
    ("C#[C]", "radical éthynyle", "ethynyl radical"),
    ("CCC", "propane", "propane"), ("CC=C", "propène", "propene"), ("CC#C", "propyne", "propyne"),
    ("C=C=C", "allène", "allene"), ("C1CC1", "cyclopropane", "cyclopropane"), ("CCCC", "butane", "butane"),
    ("CC(C)C", "isobutane", "isobutane"), ("C=CC=C", "buta-1,3-diène", "1,3-butadiene"),
    ("CC=CC", "but-2-ène", "2-butene"), ("C1CCC1", "cyclobutane", "cyclobutane"),
    ("CCCCC", "pentane", "pentane"), ("C1CCCC1", "cyclopentane", "cyclopentane"), ("CCCCCC", "hexane", "hexane"),
    ("C1CCCCC1", "cyclohexane", "cyclohexane"), ("C1=CC=CC=C1", "benzène", "benzene"),
    ("CC1=CC=CC=C1", "toluène", "toluene"), ("C1=CC=C2C=CC=CC2=C1", "naphtalène", "naphthalene"),
    ("C=CC1=CC=CC=C1", "styrène", "styrene"),
    # Composés oxygénés.
    ("CO", "méthanol", "methanol"), ("CCO", "éthanol", "ethanol"), ("COC", "éther diméthylique", "dimethyl ether"),
    ("C=O", "formaldéhyde", "formaldehyde"), ("CC=O", "acétaldéhyde", "acetaldehyde"),
    ("CC(C)=O", "acétone", "acetone"), ("OC=O", "acide formique", "formic acid"),
    ("CC(O)=O", "acide acétique", "acetic acid"), ("COC=O", "formiate de méthyle", "methyl formate"),
    ("CCCO", "propan-1-ol", "1-propanol"), ("CC(C)O", "propan-2-ol", "2-propanol"),
    ("OCCO", "éthylène glycol", "ethylene glycol"), ("OCC(O)CO", "glycérol", "glycerol"),
    ("C1CO1", "oxyde d'éthylène", "ethylene oxide"), ("OCO", "méthanediol", "methanediol"),
    ("C1OO1", "dioxirane", "dioxirane"), ("[CH2]O", "radical hydroxyméthyle", "hydroxymethyl radical"),
    ("C[O]", "radical méthoxyle", "methoxy radical"), ("[CH]=O", "radical formyle", "formyl radical"),
    ("C[C]=O", "radical acétyle", "acetyl radical"), ("O=[C]O", "radical carboxyle", "carboxyl radical"),
    ("COO", "hydroperoxyde de méthyle", "methyl hydroperoxide"),
    ("CO[O]", "radical méthylperoxyle", "methylperoxy radical"),
    ("CC(=O)C(O)=O", "acide pyruvique", "pyruvic acid"), ("OC(=O)C(O)=O", "acide oxalique", "oxalic acid"),
    ("C1=COC=C1", "furane", "furan"), ("OC1=CC=CC=C1", "phénol", "phenol"),
    ("OCC1OC(O)C(O)C(O)C1O", "glucose", "glucose"), ("CCCCCCCCCCCC(O)=O", "acide laurique", "lauric acid"),
    # Composés azotés.
    ("CN", "méthylamine", "methylamine"), ("CC#N", "acétonitrile", "acetonitrile"),
    ("NC=O", "formamide", "formamide"), ("NC(N)=O", "urée", "urea"), ("N=C=O", "acide isocyanique", "isocyanic acid"),
    ("OC#N", "acide cyanique", "cyanic acid"), ("C[N+](=O)[O-]", "nitrométhane", "nitromethane"),
    ("NCC(O)=O", "glycine", "glycine"), ("CC(N)C(O)=O", "alanine", "alanine"),
    ("C1=CC=NC=C1", "pyridine", "pyridine"), ("NC1=CC=CC=C1", "aniline", "aniline"),
    ("C1=CNC=C1", "pyrrole", "pyrrole"), ("C1=CN=CN1", "imidazole", "imidazole"),
    ("CN1C=NC2=C1C(=O)N(C)C(=O)N2C", "caféine", "caffeine"),
    # Composés halogénés et soufrés.
    ("CCl", "chlorométhane", "chloromethane"), ("ClCCl", "dichlorométhane", "dichloromethane"),
    ("ClC(Cl)Cl", "chloroforme", "chloroform"), ("ClC(Cl)(Cl)Cl", "tétrachlorométhane", "tetrachloromethane"),
    ("CF", "fluorométhane", "fluoromethane"), ("FC(F)(F)F", "tétrafluorométhane", "tetrafluoromethane"),
    ("CBr", "bromométhane", "bromomethane"), ("CI", "iodométhane", "iodomethane"),
    ("CCCl", "chloroéthane", "chloroethane"), ("CS", "méthanethiol", "methanethiol"),
    ("CSC", "sulfure de diméthyle", "dimethyl sulfide"), ("CS(C)=O", "diméthylsulfoxyde", "dimethyl sulfoxide"),
]


# ------------------------------------------------------------------ le SMILES

def _symbol_z(symbol):
    from .xtb import SYMBOLS
    return SYMBOLS.index(symbol)


def parse(smiles):
    """(numéros atomiques, charges formelles, liaisons [(a, b, ordre)]) — hydrogènes tous explicites."""
    atoms, charges, explicit_h, bracket, bonds = [], [], [], [], []
    ring, stack, previous, order, i = {}, [], None, 1, 0
    while i < len(smiles):
        c = smiles[i]
        if c == "(":
            stack.append(previous)
            i += 1
            continue
        if c == ")":
            previous = stack.pop()
            i += 1
            continue
        if c in "-=#":
            order = {"-": 1, "=": 2, "#": 3}[c]
            i += 1
            continue
        if c.isdigit():
            if c in ring:
                other, ring_order = ring.pop(c)
                bonds.append((other, previous, max(order, ring_order)))
            else:
                ring[c] = (previous, order)
            order = 1
            i += 1
            continue
        if c == "[":
            end = smiles.index("]", i)
            body, i = smiles[i + 1:end], end + 1
            k = 2 if len(body) > 1 and body[1].islower() else 1
            symbol, rest = body[:k], body[k:]
            h = 0
            if rest.startswith("H"):
                rest = rest[1:]
                digits = ""
                while rest and rest[0].isdigit():
                    digits, rest = digits + rest[0], rest[1:]
                h = int(digits) if digits else 1
            charge = 0
            while rest:
                sign = 1 if rest[0] == "+" else -1
                rest = rest[1:]
                digits = ""
                while rest and rest[0].isdigit():
                    digits, rest = digits + rest[0], rest[1:]
                charge += sign * (int(digits) if digits else 1)
            atoms.append(_symbol_z(symbol))
            charges.append(charge)
            explicit_h.append(h)
            bracket.append(True)
        else:
            symbol = smiles[i:i + 2] if smiles[i:i + 2] in ("Cl", "Br") else c
            i += len(symbol)
            atoms.append(_symbol_z(symbol))
            charges.append(0)
            explicit_h.append(0)
            bracket.append(False)
        index = len(atoms) - 1
        if previous is not None:
            bonds.append((previous, index, order))
        previous, order = index, 1
    # Les hydrogènes implicites des atomes du jeu organique : la plus petite valence usuelle qui tient leurs liaisons.
    from .xtb import SYMBOLS
    used = [0] * len(atoms)
    for a, b, o in bonds:
        used[a] += o
        used[b] += o
    numbers, out_charges, out_bonds = list(atoms), list(charges), list(bonds)
    for k in range(len(atoms)):
        h = explicit_h[k]
        if not bracket[k]:
            valences = ORGANIC.get(SYMBOLS[atoms[k]], [])
            fitting = [v for v in valences if v >= used[k]]
            h = (fitting[0] - used[k]) if fitting else 0
        for _ in range(h):
            numbers.append(1)
            out_charges.append(0)
            out_bonds.append((k, len(numbers) - 1, 1))
    return numbers, out_charges, out_bonds


# ------------------------------------------------------------------ l'empreinte

def key(numbers, edges, charge=0):
    """L'empreinte d'un graphe moléculaire : formule, charge nette, étiquettes de Weisfeiler–Lehman (4 tours)."""
    from .xtb import SYMBOLS
    n = len(numbers)
    neighbours = [[] for _ in range(n)]
    for a, b in edges:
        neighbours[a].append(b)
        neighbours[b].append(a)
    labels = [SYMBOLS[z] for z in numbers]
    for _ in range(4):
        labels = [labels[k] + "(" + ",".join(sorted(labels[j] for j in neighbours[k])) + ")" for k in range(n)]
        # Des étiquettes courtes : leur rang dans l'ordre trié (le graphe se décrit par les mêmes rangs).
        ranks = {label: f"{SYMBOLS[numbers[labels.index(label)]]}{r}" for r, label in enumerate(sorted(set(labels)))}
        labels = [ranks[label] for label in labels]
    signature = sorted(labels[k] + ":" + ".".join(sorted(labels[j] for j in neighbours[k])) for k in range(n))
    return structures.hill_formula(numbers) + f"|{charge:+d}|" + ";".join(signature)


@lru_cache(maxsize=1)
def _table():
    table = {}
    for smiles, name, english in ENTRIES:
        numbers, charges, bonds = parse(smiles)
        k = key(numbers, [(a, b) for a, b, _ in bonds], sum(charges))
        table.setdefault(k, (name, english, smiles))
    return table


def identify(numbers, edges, charge=0):
    """Le nom (français) de l'espèce dont c'est le graphe, ou None."""
    found = _table().get(key(numbers, edges, charge))
    return found[0] if found else None


def english(name):
    for _, french, english_name in ENTRIES:
        if french == name:
            return english_name
    return None


def entries():
    """Les entrées du dictionnaire, pour l'assembleur : (nom, formule, atomes, liaisons [(a, b, ordre)])."""
    out = []
    seen = set()
    for smiles, name, _ in ENTRIES:
        if name in seen:
            continue
        seen.add(name)
        numbers, charges, bonds = parse(smiles)
        out.append((name, structures.hill_formula(numbers), numbers, charges, bonds))
    return out


def sketch(numbers, bonds):
    """Des positions 2D de départ pour dessiner une molécule (unité : une liaison) : le squelette des atomes lourds
    placé d'après les distances du graphe (nombre de liaisons entre deux atomes) — un positionnement multidimensionnel
    classique, puis la majorisation du stress (SMACOF, distances proches pesant le plus) —, ce qui défait les cycles
    fusionnés sans les emmêler ; chaque hydrogène près de son atome (le client le replace dans le plus grand vide)."""
    import numpy as np
    n = len(numbers)
    heavy = [k for k in range(n) if numbers[k] > 1] or list(range(n))
    index = {atom: k for k, atom in enumerate(heavy)}
    m = len(heavy)
    out = np.zeros((n, 2))
    if m >= 2:
        far = np.full((m, m), np.inf)
        np.fill_diagonal(far, 0)
        for a, b, _ in bonds:
            if a in index and b in index:
                far[index[a], index[b]] = far[index[b], index[a]] = 1
        for k in range(m):                                            # Floyd–Warshall
            far = np.minimum(far, far[:, k:k + 1] + far[k:k + 1, :])
        far[np.isinf(far)] = m
        # Les distances visées : celles d'un dessin en zigzag (√3 pour deux liaisons, pas 2).
        target = np.where(far <= 1, far, 0.5 + 0.87 * far)
        j = np.eye(m) - 1 / m
        values, vectors = np.linalg.eigh(-0.5 * j @ (target ** 2) @ j)
        p = vectors[:, -2:] * np.sqrt(np.maximum(values[-2:], 1e-9))
        weight = 1 / np.maximum(target, 1) ** 2
        np.fill_diagonal(weight, 0)
        for _ in range(300):                                          # SMACOF
            d = np.linalg.norm(p[:, None, :] - p[None, :, :], axis=2)
            ratio = np.where(d > 1e-9, target / np.maximum(d, 1e-9), 0)
            b_matrix = -weight * ratio
            np.fill_diagonal(b_matrix, -b_matrix.sum(axis=1))
            v = -weight.copy()
            np.fill_diagonal(v, weight.sum(axis=1))
            p = np.linalg.pinv(v) @ b_matrix @ p
        for k, atom in enumerate(heavy):
            out[atom] = p[k]
    for a, b, _ in bonds:
        for h, parent in ((a, b), (b, a)):
            if numbers[h] == 1 and h not in index and parent in index:
                out[h] = out[parent] + [0.3, 0.3]
    return out


@lru_cache(maxsize=1)
def layouts():
    """Pour chaque entrée (dans l'ordre d'entries) : son départ de dessin (voir sketch)."""
    return [sketch(numbers, bonds) for _, _, numbers, _, bonds in entries()]


def describe(numbers, edges, charge=0):
    """« H₂O · eau » : la formule, et le nom quand le dictionnaire connaît ce graphe."""
    formula = structures.pretty(structures.hill_formula(numbers))
    name = identify(numbers, edges, charge)
    if name is None and len(set(numbers)) == 1:
        name = structures.NAMES.get(structures.hill_formula(numbers))        # les agrégats : Au₆
    return formula + (f" · {name}" if name else "")
