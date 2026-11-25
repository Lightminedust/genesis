"""Le schéma réactionnel : un dessin 2D de chimiste (structures en formule squelettique, réactifs écrits au-dessus des
flèches, flèches courbes des électrons) compilé en chemins 3D calculés par GFN1-xTB, étape par étape.

Le dessin arrive en JSON (voir `parse`) :

    {"atoms": [[Z, x, y, charge, "abréviation"|null], …], "bonds": [[a, b, ordre], …],
     "arrows": [{"tail": [x, y], "head": [x, y], "above": "MeI", "below": "THF, 25 °C"}, …],
     "curly": [{"from": [a] | [a, b], "to": [a] | [a, b]}, …]}

Z = 0 avec une abréviation : un groupe (Me, Ph, OMe, TMS…), développé ici. Les hydrogènes sont implicites : chaque
atome reçoit ceux de sa valence usuelle (corrigée par sa charge formelle ; un atome de la troisième période qui en porte
davantage passe à la valence suivante : S 2 → 4 → 6, P 3 → 5) ; un métal n'en reçoit aucun.

Compiler, c'est :

1. découper le dessin en étapes : chaque flèche droite relie les molécules derrière sa queue (les réactifs, en chaîne
   tant qu'elles se touchent presque) à celles devant sa pointe (les produits). Le texte de la flèche ajoute ses
   réactifs (`REAGENTS` : MeI, CsF, Co₂(CO)₈…, un nom du dictionnaire, ou un SMILES) et ses conditions (température,
   solvant, lumière : dites, pas simulées, sauf la température de l'agitation finale) ;
2. dire quel atome devient quel atome. Avec des flèches courbes, c'est exact : chaque flèche déplace une paire
   d'électrons (d'un doublet ou d'une liaison vers un atome, une liaison existante ou une liaison à former), ce qui
   change les ordres de liaison et les charges formelles — le graphe obtenu doit être celui du produit dessiné (sinon
   l'écran le dit). Sans flèches : la plus grande sous-structure commune (RDKit, FMCS) du produit avec les réactifs,
   répétée sur ce qui reste, puis les hydrogènes ; ce qui ne va dans aucun produit dessiné forme les sous-produits,
   déduits des valences (un cation et un anion covalents se lient : Me₃Si⁺ + F⁻ → Me₃SiF) ;
3. calculer : chaque réactif relaxé seul (GFN1-xTB) ; les molécules rapprochées en un complexe où les atomes qui
   vont se lier se font face (corps rigides, distance de contact) ; puis un chemin imposé — les distances des liaisons
   qui se forment, se rompent ou changent d'ordre tirées de leur valeur de départ à leur valeur d'arrivée en
   SCAN_POINTS paliers, tout le reste relaxé à chaque palier (L-BFGS, contrainte harmonique) — et enfin la structure
   d'arrivée relaxée sans contrainte. Le calcul juge : les liaisons attendues tiennent-elles une fois la contrainte
   ôtée (indices de Mayer et densité partagée, comme le recensement du laboratoire) ?

L'énergie de chaque image du chemin est celle de GFN1-xTB, sans la contrainte. Ce n'est pas un état de transition
(recherche trop coûteuse pour un outil interactif) : le chemin est imposé, sa plus haute énergie est une borne haute
grossière de la barrière. ΔE = Σ E(produits relaxés seuls) − Σ E(réactifs relaxés seuls), en phase gazeuse, à 0 K,
sans énergie de point zéro ; sans solvant, les ions isolés sont mal décrits.
"""

import json
import math
import re
import time

import numpy as np
from scipy.optimize import minimize

from . import analysis, assembly, dictionary, solvation, structures, xtb

EV = xtb.HARTREE_EV
KJ_PER_EV = 96.485
MAX_ATOMS = 90                 # par étape : au-delà, un chemin coûterait plus de quelques minutes
SCAN_POINTS = 11               # paliers du chemin imposé
SCAN_ITERATIONS = 22           # pas de L-BFGS par palier
FINAL_ITERATIONS = 160
SPRING = 1.0                   # Eh/Å² : la contrainte des distances tirées (27 eV/Å² : elles suivent leur cible)
APPROACH = (3.0, 2.0, 1.0)     # Å : les images d'approche, molécules écartées d'autant
SYMBOLS = xtb.SYMBOLS

# Électrons de valence (groupe) des éléments non métalliques ; tout autre élément est un métal (sans H implicite).
VALENCE_ELECTRONS = {1: 1, 2: 8, 5: 3, 6: 4, 7: 5, 8: 6, 9: 7, 10: 8, 14: 4, 15: 5, 16: 6, 17: 7, 18: 8, 32: 4,
                     33: 5, 34: 6, 35: 7, 36: 8, 51: 5, 52: 6, 53: 7, 54: 8, 85: 7, 86: 8}
ELECTRONEGATIVITY = {1: 2.20, 5: 2.04, 6: 2.55, 7: 3.04, 8: 3.44, 9: 3.98, 14: 1.90, 15: 2.19, 16: 2.58, 17: 3.16,
                     32: 2.01, 33: 2.18, 34: 2.55, 35: 2.96, 52: 2.1, 53: 2.66}

ABBREVIATIONS = {
    "Me": "C", "Et": "CC", "Pr": "CCC", "nPr": "CCC", "iPr": "C(C)C", "Bu": "CCCC", "nBu": "CCCC", "tBu": "C(C)(C)C",
    "Ph": "c1ccccc1", "Bn": "Cc1ccccc1", "OMe": "OC", "OEt": "OCC", "OPh": "Oc1ccccc1", "TMS": "[Si](C)(C)C",
    "SiMe3": "[Si](C)(C)C", "Ac": "C(C)=O", "OAc": "OC(C)=O", "CO2Me": "C(=O)OC", "CO2Et": "C(=O)OCC",
    "COOH": "C(=O)O", "CO2H": "C(=O)O", "CHO": "C=O", "CN": "C#N", "NO2": "[N+](=O)[O-]", "NMe2": "N(C)C",
    "NEt2": "N(CC)CC", "CF3": "C(F)(F)F", "OH": "O", "NH2": "N", "SH": "S", "Ts": "S(=O)(=O)c1ccc(C)cc1",
    "Boc": "C(=O)OC(C)(C)C", "Bz": "C(=O)c1ccccc1", "Cy": "C1CCCCC1",
}

# Les réactifs qu'on écrit au-dessus d'une flèche : (SMILES, nom affiché).
REAGENTS = {
    "MeI": ("CI", "iodométhane"), "MeBr": ("CBr", "bromométhane"), "MeCl": ("CCl", "chlorométhane"),
    "EtI": ("CCI", "iodoéthane"), "EtBr": ("CCBr", "bromoéthane"), "BnBr": ("BrCc1ccccc1", "bromure de benzyle"),
    "CsF": ("[Cs+].[F-]", "fluorure de césium"), "KF": ("[K+].[F-]", "fluorure de potassium"),
    "NaF": ("[Na+].[F-]", "fluorure de sodium"), "TBAF": ("[F-]", "ion fluorure (du TBAF)"),
    "HF": ("F", "fluorure d'hydrogène"), "HCl": ("Cl", "chlorure d'hydrogène"), "HBr": ("Br", "bromure d'hydrogène"),
    "HI": ("I", "iodure d'hydrogène"), "H2O": ("O", "eau"), "H₂O": ("O", "eau"), "H2": ("[H][H]", "dihydrogène"),
    "H₂": ("[H][H]", "dihydrogène"), "Br2": ("BrBr", "dibrome"), "Br₂": ("BrBr", "dibrome"),
    "Cl2": ("ClCl", "dichlore"), "Cl₂": ("ClCl", "dichlore"), "I2": ("II", "diiode"), "I₂": ("II", "diiode"),
    "F2": ("FF", "difluor"), "NaOH": ("[Na+].[OH-]", "soude"), "KOH": ("[K+].[OH-]", "potasse"),
    "LiOH": ("[Li+].[OH-]", "hydroxyde de lithium"), "NaH": ("[Na+].[H-]", "hydrure de sodium"),
    "NaBH4": ("[Na+].[BH4-]", "borohydrure de sodium"), "NaBH₄": ("[Na+].[BH4-]", "borohydrure de sodium"),
    "LiAlH4": ("[Li+].[AlH4-]", "hydrure de lithium-aluminium"), "LiAlH₄": ("[Li+].[AlH4-]", "hydrure de lithium-aluminium"),
    "BH3": ("B", "borane"), "BH₃": ("B", "borane"), "O2": ("O=O", "dioxygène"), "O₂": ("O=O", "dioxygène"),
    "O3": ("[O-][O+]=O", "ozone"), "O₃": ("[O-][O+]=O", "ozone"), "CO": ("[C-]#[O+]", "monoxyde de carbone"),
    "CO2": ("O=C=O", "dioxyde de carbone"), "CO₂": ("O=C=O", "dioxyde de carbone"), "NH3": ("N", "ammoniac"),
    "NH₃": ("N", "ammoniac"), "MeOH": ("CO", "méthanol"), "EtOH": ("CCO", "éthanol"),
    "NMe3": ("CN(C)C", "triméthylamine"), "Et3N": ("CCN(CC)CC", "triéthylamine"), "NEt3": ("CCN(CC)CC", "triéthylamine"),
    "HCN": ("C#N", "cyanure d'hydrogène"), "NaCN": ("[Na+].[C-]#N", "cyanure de sodium"),
    "KCN": ("[K+].[C-]#N", "cyanure de potassium"), "MeLi": ("[Li]C", "méthyllithium"),
    "nBuLi": ("[Li]CCCC", "n-butyllithium"), "BuLi": ("[Li]CCCC", "n-butyllithium"),
    "H2SO4": ("OS(=O)(=O)O", "acide sulfurique"), "H₂SO₄": ("OS(=O)(=O)O", "acide sulfurique"),
    "HNO3": ("O[N+](=O)[O-]", "acide nitrique"), "mCPBA": ("OOC(=O)c1cccc(Cl)c1", "mCPBA"),
    "OsO4": ("O=[Os](=O)(=O)=O", "tétroxyde d'osmium"), "OsO₄": ("O=[Os](=O)(=O)=O", "tétroxyde d'osmium"),
    "SOCl2": ("O=S(Cl)Cl", "chlorure de thionyle"), "PBr3": ("BrP(Br)Br", "tribromure de phosphore"),
    "Co2(CO)8": ("O#C[Co](C#O)(C#O)(C#O)[Co](C#O)(C#O)(C#O)C#O", "octacarbonyle de dicobalt"),
    "Co₂(CO)₈": ("O#C[Co](C#O)(C#O)(C#O)[Co](C#O)(C#O)(C#O)C#O", "octacarbonyle de dicobalt"),
    "Fe(CO)5": ("O#C[Fe](C#O)(C#O)(C#O)C#O", "pentacarbonyle de fer"),
    "Ni(CO)4": ("O#C[Ni](C#O)(C#O)C#O", "tétracarbonyle de nickel"),
    "BF3": ("FB(F)F", "trifluorure de bore"), "AlCl3": ("Cl[Al](Cl)Cl", "chlorure d'aluminium"),
}
SOLVENTS = {"THF", "DMF", "DMSO", "DCM", "CH2Cl2", "CH₂Cl₂", "MeCN", "CH3CN", "Et2O", "Et₂O", "toluène", "toluene",
            "benzène", "benzene", "acétone", "acetone", "hexane", "pentane", "CHCl3", "CHCl₃", "dioxane", "pyridine",
            "AcOH", "EtOAc", "xylène", "xylene"}


class SchemeError(ValueError):
    """Un dessin qu'on ne peut pas compiler : la raison est dite à l'écran."""


# ====================================================================== valences
def is_metal(z):
    return z not in VALENCE_ELECTRONS


def valence(z, charge):
    """La valence usuelle d'un atome de charge formelle donnée (isoélectronique : N⁺ comme C, O⁻ comme F)."""
    if z == 1:
        return 1 if charge == 0 else 0
    e = VALENCE_ELECTRONS[z] - charge
    if e <= 0 or e >= 8:
        return 0
    return e if e <= 4 else 8 - e


def implicit_hydrogens(z, charge, bonded):
    """Les hydrogènes implicites : ceux qui complètent la valence usuelle (la suivante pour un atome de la troisième
    période qui porte déjà plus de liaisons)."""
    if is_metal(z) or z == 1:
        return 0
    v = valence(z, charge)
    if bonded <= v:
        return v - bonded
    if z > 10:
        while v < bonded and v < 7:
            v += 2
        return max(0, v - bonded)
    return 0


def satisfied(z, charge, bonded):
    """La charge rend-elle compte des liaisons de cet atome (sans hydrogène à ajouter) ?"""
    if is_metal(z):
        return True
    v = valence(z, charge)
    if bonded == v:
        return True
    return z > 10 and bonded > v and (bonded - v) % 2 == 0 and bonded <= 7


# ====================================================================== un graphe moléculaire
class Piece:
    """Une molécule, hydrogènes explicites : numéros, charges, liaisons {(a, b): ordre}, positions 2D (une liaison
    = 1), l'atome du dessin dont vient chaque atome (−1 : un hydrogène, un atome d'abréviation, un réactif écrit)."""

    def __init__(self, label=""):
        self.numbers, self.charges, self.xy, self.drawn = [], [], [], []
        self.bonds = {}
        self.label = label
        self.kind = "drawn"                  # « drawn » : une structure du dessin ; « reagent » : écrite sur une flèche
        self.component = -1

    def add(self, z, charge=0, xy=(0.0, 0.0), drawn=-1):
        self.numbers.append(int(z))
        self.charges.append(int(charge))
        self.xy.append((float(xy[0]), float(xy[1])))
        self.drawn.append(int(drawn))
        return len(self.numbers) - 1

    def bond(self, a, b, order):
        if a != b:
            self.bonds[(min(a, b), max(a, b))] = int(order)

    def bonded(self, a):
        return sum(o for (i, j), o in self.bonds.items() if a in (i, j))

    def neighbours(self, a):
        return [j if i == a else i for (i, j) in self.bonds if a in (i, j)]

    def __len__(self):
        return len(self.numbers)

    def complete(self):
        """Ajoute les hydrogènes implicites de chaque atome lourd, posés dans ses plus grands vides du dessin."""
        for a in range(len(self.numbers)):
            if self.numbers[a] == 1:
                continue
            count = implicit_hydrogens(self.numbers[a], self.charges[a], self.bonded(a))
            if not count:
                continue
            x, y = self.xy[a]
            taken = [math.atan2(self.xy[n][1] - y, self.xy[n][0] - x) for n in self.neighbours(a)]
            angles = _free_angles(taken, count)
            for t in angles:
                h = self.add(1, 0, (x + 0.55 * math.cos(t), y + 0.55 * math.sin(t)))
                self.bond(a, h, 1)
        return self

    def copy(self):
        p = Piece(self.label)
        p.numbers, p.charges, p.xy, p.drawn = list(self.numbers), list(self.charges), list(self.xy), list(self.drawn)
        p.bonds = dict(self.bonds)
        p.kind, p.component = self.kind, self.component
        return p

    def describe(self):
        return label(self.numbers, list(self.bonds), sum(self.charges))


SUPERSCRIPT = str.maketrans("0123456789+-", "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻")


def label(numbers, edges, charge):
    """« C₄H₁₂N⁺ · … » : le nom du dictionnaire, et la charge nette d'un ion sur sa formule."""
    text = dictionary.describe(numbers, edges, charge)
    if not charge:
        return text
    sign = ("" if abs(charge) == 1 else str(abs(charge))) + ("+" if charge > 0 else "-")
    formula, _, name = text.partition(" · ")
    return formula + sign.translate(SUPERSCRIPT) + (f" · {name}" if name else "")


def _free_angles(taken, count):
    if not taken:
        return [2 * math.pi * k / count + math.pi / 6 for k in range(count)]
    taken = sorted(t % (2 * math.pi) for t in taken)
    gaps = []
    for k, t in enumerate(taken):
        nxt = taken[(k + 1) % len(taken)] + (2 * math.pi if k == len(taken) - 1 else 0)
        gaps.append((nxt - t, t))
    out = []
    # Les hydrogènes se partagent les vides, le plus grand d'abord, au prorata de leur taille.
    gaps.sort(reverse=True)
    share = [0] * len(gaps)
    for k in range(count):
        best = max(range(len(gaps)), key=lambda i: gaps[i][0] / (share[i] + 1))
        share[best] += 1
    for (size, start), n in zip(gaps, share):
        for k in range(n):
            out.append(start + size * (k + 1) / (n + 1))
    return out


def from_smiles(smiles, label=""):
    """Une molécule (hydrogènes explicites, positions 2D) d'après un SMILES ; métaux et ligands acceptés sans
    vérification de valence."""
    from rdkit import Chem, RDLogger
    from rdkit.Chem import rdDepictor
    RDLogger.DisableLog("rdApp.*")
    mol = Chem.MolFromSmiles(smiles)
    if mol is None:
        mol = Chem.MolFromSmiles(smiles, sanitize=False)
        if mol is None:
            raise SchemeError(f"SMILES illisible : {smiles}")
        mol.UpdatePropertyCache(strict=False)
    try:
        Chem.Kekulize(mol, clearAromaticFlags=True)
    except Exception:
        pass
    try:
        rdDepictor.Compute2DCoords(mol)
        conf = mol.GetConformer()
        xy = [(conf.GetAtomPosition(k).x / 1.5, conf.GetAtomPosition(k).y / 1.5) for k in range(mol.GetNumAtoms())]
    except Exception:
        xy = [(k * 1.0, 0.0) for k in range(mol.GetNumAtoms())]
    piece = Piece(label)
    for k, atom in enumerate(mol.GetAtoms()):
        piece.add(atom.GetAtomicNum(), atom.GetFormalCharge(), xy[k])
    for b in mol.GetBonds():
        order = {1.0: 1, 2.0: 2, 3.0: 3, 1.5: 1}.get(b.GetBondTypeAsDouble(), 1)
        piece.bond(b.GetBeginAtomIdx(), b.GetEndAtomIdx(), order)
    # Les hydrogènes que RDKit compte à chaque atome (entre crochets : ceux écrits ; sinon : la valence usuelle).
    for a, atom in enumerate(mol.GetAtoms()):
        count = atom.GetTotalNumHs()
        if not count:
            continue
        x, y = piece.xy[a]
        taken = [math.atan2(piece.xy[n][1] - y, piece.xy[n][0] - x) for n in piece.neighbours(a)]
        for t in _free_angles(taken, count):
            h = piece.add(1, 0, (x + 0.55 * math.cos(t), y + 0.55 * math.sin(t)))
            piece.bond(a, h, 1)
    return piece


def _without(piece, atom):
    """La molécule sans cet atome (les indices suivants reculent d'un)."""
    out = Piece(piece.label)
    keep = [k for k in range(len(piece)) if k != atom]
    index = {k: i for i, k in enumerate(keep)}
    for k in keep:
        out.add(piece.numbers[k], piece.charges[k], piece.xy[k], piece.drawn[k])
    for (i, j), o in piece.bonds.items():
        if i in index and j in index:
            out.bond(index[i], index[j], o)
    return out


def _piece_of(mol, label=""):
    """Une molécule RDKit (lue d'un fichier ChemDraw, Marvin, MDL) en pièce : ses positions 2D si le fichier en a, sinon
    calculées ; les hydrogènes implicites ajoutés comme pour un SMILES."""
    from rdkit import Chem
    from rdkit.Chem import rdDepictor
    try:
        mol.UpdatePropertyCache(strict=False)
    except Exception:
        pass
    try:
        Chem.Kekulize(mol, clearAromaticFlags=True)
    except Exception:
        pass
    n = mol.GetNumAtoms()
    xy = None
    if mol.GetNumConformers():
        p = mol.GetConformer().GetPositions()
        if n < 2 or float(np.ptp(p[:, 0]) + np.ptp(p[:, 1])) > 1e-3:
            xy = [(float(p[k][0]), float(p[k][1])) for k in range(n)]
    if xy is None:
        try:
            rdDepictor.Compute2DCoords(mol)
            p = mol.GetConformer().GetPositions()
            xy = [(float(p[k][0]), float(p[k][1])) for k in range(n)]
        except Exception:
            xy = [(k * 1.0, 0.0) for k in range(n)]
    piece = Piece(label)
    for k, atom in enumerate(mol.GetAtoms()):
        piece.add(atom.GetAtomicNum(), atom.GetFormalCharge(), xy[k])
    for b in mol.GetBonds():
        order = {1.0: 1, 2.0: 2, 3.0: 3, 1.5: 1}.get(b.GetBondTypeAsDouble(), 1)
        piece.bond(b.GetBeginAtomIdx(), b.GetEndAtomIdx(), order)
    return piece


def _layout(items):
    """Un document de l'éditeur posé de gauche à droite : chaque élément est une liste de pièces (côte à côte) ou une
    flèche (« arrow », au-dessus, au-dessous). Les atomes lourds seulement (les hydrogènes sont implicites dans
    l'éditeur) ; une liaison du fichier y mesure 1, comme dans un dessin."""
    doc = {"atoms": [], "bonds": [], "arrows": [], "curly": []}
    x = 0.0
    for item in items:
        if isinstance(item, tuple):
            doc["arrows"].append({"tail": [x, 0.0], "head": [x + 2.6, 0.0], "above": item[1], "below": item[2]})
            x += 2.6 + 1.2
            continue
        for k, piece in enumerate(item):
            heavy = [a for a, z in enumerate(piece.numbers) if z > 1] or list(range(len(piece)))
            index = {a: i for i, a in enumerate(heavy)}
            xy = np.array([piece.xy[a] for a in heavy], float).reshape(-1, 2)
            lengths = [np.linalg.norm(xy[index[a]] - xy[index[b]]) for (a, b) in piece.bonds if a in index and b in index]
            if lengths:
                xy /= max(1e-6, float(np.median(lengths)))
            xy[:, 1] *= -1                                      # y vers le bas, comme à l'écran
            dx, dy = x - float(xy[:, 0].min()), -float(xy[:, 1].min() + xy[:, 1].max()) / 2
            base = len(doc["atoms"])
            for i, a in enumerate(heavy):
                doc["atoms"].append([piece.numbers[a], float(xy[i][0] + dx), float(xy[i][1] + dy), piece.charges[a], None])
            for (a, b), o in piece.bonds.items():
                if a in index and b in index:
                    doc["bonds"].append([index[a] + base, index[b] + base, o])
            x += float(np.ptp(xy[:, 0])) + (1.6 if k < len(item) - 1 else 1.2)
    return doc


def _split_molecules(mol):
    """Les molécules d'un fichier qui en met plusieurs dans une seule (« A.B ») : chacune à part."""
    from rdkit import Chem
    try:
        return list(Chem.GetMolFrags(mol, asMols=True, sanitizeFrags=False))
    except Exception:
        return [mol]


def _reaction_items(reactions):
    """Des réactions RDKit (RXN, CDXML, MRV, SMILES de réaction) en éléments de _layout : réactifs, flèche (les agents
    au-dessus, en SMILES), produits ; plusieurs réactions s'enchaînent quand les produits de l'une sont les réactifs
    de la suivante (un schéma ChemDraw en plusieurs étapes), sinon elles se suivent."""
    from rdkit import Chem

    def key(mols):
        out = []
        for m in mols:
            try:
                out.append(Chem.MolToSmiles(m))
            except Exception:
                out.append(str(m.GetNumAtoms()))
        return sorted(out)

    items, before = [], None
    for rxn in reactions:
        reactants, products = list(rxn.GetReactants()), list(rxn.GetProducts())
        agents = []
        for m in rxn.GetAgents():
            try:
                agents.append(Chem.MolToSmiles(m))
            except Exception:
                pass
        # Les réactifs de cette étape sont les produits de la précédente : la flèche part d'eux, sans les redessiner.
        if reactants and key(reactants) != before:
            items.append([_piece_of(m) for m in reactants])
        items.append(("arrow", ", ".join(agents), ""))
        if products:
            items.append([_piece_of(m) for m in products])
        before = key(products)
    return items


def imported(text):
    """Une structure ou un schéma venus d'un autre logiciel, pour l'éditeur : un bloc MDL (MOL ou SDF, V2000 ou V3000 :
    « Copier comme MOL » de ChemDraw et MarvinSketch), un bloc RXN, du CDXML (ChemDraw), du MRV (MarvinSketch), un
    SMILES de réaction (« A.B>>C ») ou un SMILES. Rend (document de l'éditeur, nom du format)."""
    from rdkit import Chem, RDLogger
    from rdkit.Chem import rdChemReactions
    RDLogger.DisableLog("rdApp.*")
    raw = text.replace("\r\n", "\n").replace("\r", "\n")
    t = raw.strip()
    if not t:
        raise SchemeError("rien à lire")
    if t.startswith("$RXN"):
        rxn = rdChemReactions.ReactionFromRxnBlock(t, sanitize=False, removeHs=False)
        if rxn is None:
            raise SchemeError("bloc RXN illisible")
        return _layout(_reaction_items([rxn])), "RXN"
    if "<CDXML" in t[:2000]:
        reactions = []
        try:
            reactions = list(rdChemReactions.ReactionsFromCDXMLBlock(t, sanitize=False))
        except Exception:
            reactions = []
        reactions = [r for r in reactions if r is not None and (r.GetNumReactantTemplates() or r.GetNumProductTemplates())]
        if reactions:
            return _layout(_reaction_items(reactions)), "CDXML (ChemDraw)"
        params = Chem.CDXMLParserParams()
        params.sanitize = False
        params.removeHs = False
        mols = [m for m in Chem.MolsFromCDXML(t, params) if m is not None and m.GetNumAtoms()]
        if not mols:
            raise SchemeError("CDXML sans molécule lisible")
        return _layout([[_piece_of(m) for m in mols]]), "CDXML (ChemDraw)"
    if t.startswith("<") and ("<MDocument" in t or "<cml" in t or "<MChemicalStruct" in t):
        if rdChemReactions.MrvBlockIsReaction(t):
            rxn = rdChemReactions.ReactionFromMrvBlock(t, sanitize=False, removeHs=False)
            if rxn is None:
                raise SchemeError("MRV illisible")
            return _layout(_reaction_items([rxn])), "MRV (MarvinSketch)"
        mol = Chem.MolFromMrvBlock(t, sanitize=False, removeHs=False)
        if mol is None:
            raise SchemeError("MRV illisible")
        return _layout([[_piece_of(m) for m in _split_molecules(mol)]]), "MRV (MarvinSketch)"
    if "M  END" in t or "V2000" in t or "V3000" in t:
        # Un bloc MDL commence par trois lignes d'en-tête, la première souvent vide : rien n'y est retiré (un bloc d'un
        # SDF suit la ligne « $$$$ »). Si l'en-tête a perdu ses lignes vides en route (un collage), on les remet.
        mols = []
        for k, block in enumerate(raw.split("$$$$")):
            if k > 0 and block.startswith("\n"):
                block = block[1:]
            if "M  END" not in block:
                continue
            block = block[:block.index("M  END") + 6] + "\n"
            mol = Chem.MolFromMolBlock(block, sanitize=False, removeHs=False)
            for header in ("\n", "\n\n"):
                if mol is None:
                    mol = Chem.MolFromMolBlock(header + block.lstrip("\n"), sanitize=False, removeHs=False)
            if mol is None:
                raise SchemeError("bloc MOL illisible")
            mols.extend(_split_molecules(mol))
        if not mols:
            raise SchemeError("bloc MOL illisible")
        return _layout([[_piece_of(m) for m in mols]]), "MOL" if len(mols) == 1 else "SDF"
    line = t.split("\n")[0].split()[0] if t.split() else ""
    if ">" in line:
        try:
            rxn = rdChemReactions.ReactionFromSmarts(line, useSmiles=True)
        except Exception:
            rxn = None
        if rxn is None:
            raise SchemeError(f"SMILES de réaction illisible : {line}")
        return _layout(_reaction_items([rxn])), "SMILES de réaction"
    return dict(drawing(line), arrows=[], curly=[]), "SMILES"


def drawing(smiles):
    """Le dessin d'un SMILES pour l'éditeur : atomes lourds (Z, x, y, charge) et liaisons, une liaison = 1."""
    piece = from_smiles(smiles)
    heavy = [k for k, z in enumerate(piece.numbers) if z > 1] or list(range(len(piece)))
    index = {a: k for k, a in enumerate(heavy)}
    xy = np.array([piece.xy[a] for a in heavy], float)
    if len(xy) > 1:
        lengths = [np.linalg.norm(xy[index[a]] - xy[index[b]]) for (a, b) in piece.bonds if a in index and b in index]
        if lengths:
            xy /= max(1e-6, float(np.median(lengths)))
        xy -= xy.mean(axis=0)
    atoms = [[piece.numbers[a], float(xy[k][0]), float(-xy[k][1]), piece.charges[a]] for k, a in enumerate(heavy)]
    bonds = [[index[a], index[b], o] for (a, b), o in piece.bonds.items() if a in index and b in index]
    return {"atoms": atoms, "bonds": bonds}


# ====================================================================== le dessin
class Drawing:
    """Le dessin lu : ses atomes, ses liaisons, ses molécules (les groupes liés), leurs pièces (hydrogènes compris)."""

    def __init__(self, document):
        if isinstance(document, str):
            document = json.loads(document) if document.strip() else {}
        raw_atoms = document.get("atoms", [])
        self.atoms = []
        for item in raw_atoms:
            z, x, y = int(item[0]), float(item[1]), float(item[2])
            charge = int(item[3]) if len(item) > 3 and item[3] is not None else 0
            label = item[4] if len(item) > 4 and item[4] else None
            if z <= 0 and label not in ABBREVIATIONS:
                raise SchemeError(f"abréviation inconnue : {label}")
            if z > 0 and not xtb.supported(z):
                raise SchemeError(f"{SYMBOLS[z]} n'est pas paramétré dans GFN1-xTB")
            self.atoms.append((z, x, y, charge, label))
        n = len(self.atoms)
        self.bonds = {}
        for a, b, order in document.get("bonds", []):
            a, b = int(a), int(b)
            if 0 <= a < n and 0 <= b < n and a != b:
                self.bonds[(min(a, b), max(a, b))] = max(1, min(3, int(order)))
        self.arrows = []
        for arrow in document.get("arrows", []):
            self.arrows.append({"tail": np.array(arrow["tail"], float), "head": np.array(arrow["head"], float),
                                "above": str(arrow.get("above", "") or ""), "below": str(arrow.get("below", "") or "")})
        self.curly = []
        for c in document.get("curly", []):
            src, dst = [int(k) for k in c.get("from", [])], [int(k) for k in c.get("to", [])]
            if src and dst and all(0 <= k < n for k in src + dst):
                self.curly.append((tuple(src[:2]), tuple(dst[:2])))
        self.groups = assembly.components(n, [(a, b, o) for (a, b), o in self.bonds.items()])
        self.group_of = {}
        for g, members in enumerate(self.groups):
            for a in members:
                self.group_of[a] = g
        lengths = [math.dist(self.atoms[a][1:3], self.atoms[b][1:3]) for a, b in self.bonds]
        self.unit = float(np.median(lengths)) if lengths else 1.0
        self.pieces = [self._expand(g) for g in range(len(self.groups))]

    def _expand(self, g):
        """La molécule g du dessin, développée : abréviations remplacées, hydrogènes ajoutés."""
        members = self.groups[g]
        piece = Piece()
        piece.component = g
        index = {}
        for a in members:
            z, x, y, charge, label = self.atoms[a]
            xy = (x / self.unit, -y / self.unit)
            if z > 0:
                index[a] = piece.add(z, charge, xy, drawn=a)
            else:
                fragment = from_smiles(ABBREVIATIONS[label])
                attached = [n for n in fragment.neighbours(0) if fragment.numbers[n] == 1]
                if attached:
                    fragment = _without(fragment, attached[0])
                # L'abréviation, posée à la place de son atome, tournée vers l'extérieur de la molécule.
                heavy = [k for k, zz in enumerate(fragment.numbers)]
                offset = {}
                origin = np.array(fragment.xy[0])
                for k in heavy:
                    p = (np.array(fragment.xy[k]) - origin) * 0.8
                    offset[k] = piece.add(fragment.numbers[k], fragment.charges[k], (xy[0] + p[0], xy[1] + p[1]),
                                          drawn=a if k == 0 else -1)
                for (i, j), o in fragment.bonds.items():
                    piece.bond(offset[i], offset[j], o)
                index[a] = offset[0]
        for (a, b), o in self.bonds.items():
            if a in index and b in index:
                piece.bond(index[a], index[b], o)
        # Les hydrogènes implicites des atomes dessinés (ceux des abréviations sont déjà là).
        for a in members:
            k = index[a]
            if self.atoms[a][0] <= 0:
                continue
            count = implicit_hydrogens(piece.numbers[k], piece.charges[k], piece.bonded(k))
            if count:
                x, y = piece.xy[k]
                taken = [math.atan2(piece.xy[n][1] - y, piece.xy[n][0] - x) for n in piece.neighbours(k)]
                for t in _free_angles(taken, count):
                    h = piece.add(1, 0, (x + 0.55 * math.cos(t), y + 0.55 * math.sin(t)))
                    piece.bond(k, h, 1)
        piece.label = piece.describe()
        return piece

    # ------------------------------------------------------------------ les étapes
    def steps(self):
        """Pour chaque flèche droite : (indice de la flèche, molécules réactives, molécules produites)."""
        out = []
        centres = []
        for members in self.groups:
            p = np.array([self.atoms[a][1:3] for a in members], float)
            centres.append(p)
        reach = 3.2 * self.unit
        for k, arrow in enumerate(self.arrows):
            tail, head = arrow["tail"], arrow["head"]
            d = head - tail
            length = np.linalg.norm(d)
            if length < 1e-6:
                continue
            d /= length
            normal = np.array([-d[1], d[0]])

            def side(point, sign):
                chosen = []
                candidates = []
                for g, p in enumerate(centres):
                    c = p.mean(axis=0)
                    along = float((c - point) @ d) * sign
                    lateral = abs(float((c - point) @ normal))
                    extent = float(np.max(np.linalg.norm(p - c, axis=1))) if len(p) > 1 else 0.0
                    if along > -0.6 * self.unit - extent * 0.3 and lateral <= extent + 2.5 * self.unit:
                        gap = float(np.min(np.linalg.norm(p - point, axis=1)))
                        candidates.append((gap, g))
                candidates.sort()
                if not candidates or candidates[0][0] > reach + 0.6 * length:
                    return []
                chosen.append(candidates[0][1])
                grown = True
                while grown:
                    grown = False
                    for gap, g in candidates:
                        if g in chosen:
                            continue
                        near = min(float(np.min(np.linalg.norm(centres[g][:, None, :] - centres[h][None, :, :], axis=2)))
                                   for h in chosen)
                        if near <= reach:
                            chosen.append(g)
                            grown = True
                return chosen

            reactants = side(tail, -1)
            products = side(head, +1)
            out.append((k, reactants, [g for g in products if g not in reactants]))
        return out


# ====================================================================== les réactifs écrits sur les flèches
def read_text(text, above=False):
    """Le texte d'une flèche : (réactifs [(Piece, nombre)], conditions, notes, température K ou None, solvant ALPB
    ou None). Au-dessus de la flèche, un réactif connu (H₂O, MeOH…) est un réactif ; au-dessous, un solvant."""
    reagents, conditions, notes, kelvin, solvent = [], [], [], None, None
    if not text:
        return reagents, conditions, notes, kelvin, solvent
    for token in re.split(r"[,;\n/]+|\s\+\s", text):
        token = token.strip()
        if not token:
            continue
        m = re.fullmatch(r"(-?\d+(?:[.,]\d+)?)\s*°\s*C", token)
        if m:
            kelvin = float(m.group(1).replace(",", ".")) + 273.15
            conditions.append(token)
            continue
        if token.lower() in ("δ", "Δ".lower(), "chauffage", "reflux", "heat", "chaleur"):
            conditions.append(token)
            notes.append("le chauffage n'est pas simulé : le chemin est calculé à 0 K")
            continue
        if token.lower() in ("hν", "hv", "lumière", "light", "uv"):
            conditions.append(token)
            notes.append("lumière : GFN1-xTB ne décrit que l'état fondamental, pas une photochimie")
            continue
        found = solvation.find(token)
        if found and not (above and token in REAGENTS):
            solvent = found
            conditions.append(token)
            continue
        if token in SOLVENTS:
            conditions.append(token)
            notes.append(f"{token} : le solvant n'est pas simulé (phase gazeuse)")
            continue
        if re.fullmatch(r"\(?\d+(?:[.,]\d+)?\s*(?:eq|équiv|equiv|mol\s*%)\.?\)?", token, re.IGNORECASE):
            continue
        count = 1
        m = re.fullmatch(r"(\d+)\s+(.+)", token)
        if m:
            count, token = max(1, min(4, int(m.group(1)))), m.group(2).strip()
        piece = None
        if token in REAGENTS:
            smiles, name = REAGENTS[token]
            piece = from_smiles(smiles, f"{token} · {name}")
        else:
            for name_fr, formula, numbers, charges, bonds in dictionary.entries():
                if token.lower() == name_fr.lower() or token.lower() == (dictionary.english(name_fr) or "").lower():
                    piece = Piece(dictionary.describe(numbers, [(a, b) for a, b, _ in bonds], sum(charges)))
                    for z, q in zip(numbers, charges):
                        piece.add(z, q)
                    for a, b, o in bonds:
                        piece.bond(a, b, o)
                    break
        if piece is None and re.fullmatch(r"[A-Za-z0-9@+\-\[\]\(\)=#$%\\/.]+", token):
            try:
                piece = from_smiles(token)
                piece.label = piece.describe()
            except SchemeError:
                piece = None
        if piece is None:
            notes.append(f"« {token} » : inconnu, ignoré (un nom, une formule du tableau ou un SMILES)")
            continue
        piece.kind = "reagent"
        reagents.append((piece, count))
    return reagents, conditions, notes, kelvin, solvent


# ====================================================================== un système : des pièces réunies
class System:
    def __init__(self, pieces):
        self.pieces = pieces
        self.numbers, self.charges, self.owner, self.local = [], [], [], []
        self.bonds = {}
        self.offset = []
        for p, piece in enumerate(pieces):
            base = len(self.numbers)
            self.offset.append(base)
            self.numbers += piece.numbers
            self.charges += piece.charges
            self.owner += [p] * len(piece)
            self.local += list(range(len(piece)))
            for (a, b), o in piece.bonds.items():
                self.bonds[(a + base, b + base)] = o

    def __len__(self):
        return len(self.numbers)

    def atoms_of(self, p):
        return list(range(self.offset[p], self.offset[p] + len(self.pieces[p])))

    def drawn_index(self, drawn_atom):
        for k, (p, l) in enumerate(zip(self.owner, self.local)):
            if self.pieces[p].drawn[l] == drawn_atom:
                return k
        return -1


def _graph(numbers, bonds, nodes=None):
    import networkx as nx
    g = nx.Graph()
    for k in (range(len(numbers)) if nodes is None else nodes):
        g.add_node(k, z=numbers[k])
    for (a, b), o in bonds.items():
        if a in g and b in g and o > 0:
            g.add_edge(a, b, order=o)
    return g


def _fragments(n, bonds, atoms):
    atoms = set(atoms)
    edges = [(a, b, o) for (a, b), o in bonds.items() if o > 0 and a in atoms and b in atoms]
    parent = {a: a for a in atoms}

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for a, b, _ in edges:
        parent[find(a)] = find(b)
    groups = {}
    for a in sorted(atoms):
        groups.setdefault(find(a), []).append(a)
    return list(groups.values())


# ====================================================================== les flèches courbes
def push(numbers, charges, bonds, arrows):
    """Le graphe après les flèches courbes (toutes à la fois) : chaque flèche déplace une paire d'électrons.

    Source : un doublet sur un atome (a,) ou une liaison (a, b) ; cible : un atome (k,) (la paire y devient un doublet)
    ou une paire d'atomes (k, l) (la paire y forme une liaison, ou en renforce une). Les charges formelles suivent :
    l'atome qui donne son doublet ou que la paire quitte gagne +1, celui qui la reçoit sans l'avoir fournie −1."""
    bonds = dict(bonds)
    charges = list(charges)

    def change(a, b, delta):
        key = (min(a, b), max(a, b))
        bonds[key] = bonds.get(key, 0) + delta
        if bonds[key] < 0:
            raise SchemeError(f"une flèche retire une liaison qui n'existe pas ({SYMBOLS[numbers[a]]}–{SYMBOLS[numbers[b]]})")

    for source, target in arrows:
        if len(source) == 1:
            donor, left = source[0], None
        else:
            a, b = source
            if len(target) == 2 and (a in target) != (b in target):
                donor = a if a in target else b
            elif len(target) == 1 and target[0] in (a, b):
                donor = target[0]
            else:
                donor = a
            left = b if donor == a else a
        if len(target) == 1:
            k = target[0]
            if left is None:
                raise SchemeError("une flèche part d'un doublet vers un atome : elle doit viser une liaison à former")
            # Hétérolyse : la liaison donne sa paire à k.
            change(donor, left, -1)
            if k == donor:
                charges[donor] -= 1
                charges[left] += 1
            else:
                raise SchemeError("une flèche part d'une liaison vers un atome qui n'en fait pas partie : "
                                  "visez la liaison à former (entre deux atomes)")
            continue
        k, l = target
        if donor not in (k, l):
            # La paire va d'une liaison (ou d'un doublet) vers deux autres atomes : le donneur est le plus proche dans
            # le graphe ; une telle flèche se dessine plutôt de l'atome vers la liaison.
            raise SchemeError("une flèche courbe doit partir d'un atome (ou d'une liaison) qui touche la liaison visée")
        other = l if donor == k else k
        change(donor, other, +1)
        charges[other] -= 1
        if left is None:
            charges[donor] += 1
        else:
            change(donor, left, -1)
            charges[left] += 1
    return charges, {k: o for k, o in bonds.items() if o > 0}


# ====================================================================== qui devient quoi
def _rdkit(numbers, charges, bonds, atoms):
    """Une molécule RDKit des atomes choisis (hydrogènes explicites), valences non vérifiées ; (mol, index)."""
    from rdkit import Chem
    m = Chem.RWMol()
    index = {}
    for a in atoms:
        at = Chem.Atom(int(numbers[a]))
        at.SetFormalCharge(int(charges[a]))
        at.SetNoImplicit(True)
        index[a] = m.AddAtom(at)
    kinds = {1: Chem.BondType.SINGLE, 2: Chem.BondType.DOUBLE, 3: Chem.BondType.TRIPLE}
    for (a, b), o in bonds.items():
        if a in index and b in index and o > 0:
            m.AddBond(index[a], index[b], kinds.get(o, Chem.BondType.SINGLE))
    mol = m.GetMol()
    try:
        mol.UpdatePropertyCache(strict=False)
        Chem.SanitizeMol(mol, Chem.SanitizeFlags.SANITIZE_ALL ^ Chem.SanitizeFlags.SANITIZE_PROPERTIES,
                         catchErrors=True)
    except Exception:
        pass
    return mol, index


def aromatic_pairs(numbers, charges, bonds, atoms):
    """Les liaisons aromatiques (RDKit) : leurs ordres de Kekulé ne comptent pas comme un changement."""
    try:
        mol, index = _rdkit(numbers, charges, bonds, atoms)
        back = {v: k for k, v in index.items()}
        return {(min(back[b.GetBeginAtomIdx()], back[b.GetEndAtomIdx()]),
                 max(back[b.GetBeginAtomIdx()], back[b.GetEndAtomIdx()])) for b in mol.GetBonds() if b.GetIsAromatic()}
    except Exception:
        return set()


def _heavy_mcs(r_numbers, r_bonds, r_atoms, p_numbers, p_bonds, p_atoms, timeout=4, exact=False):
    """La plus grande sous-structure commune (atomes lourds, liaisons de tout ordre) : [(atome produit, atome réactif)]."""
    from rdkit import Chem
    from rdkit.Chem import rdFMCS
    zeros_r = [0] * len(r_numbers)
    zeros_p = [0] * len(p_numbers)
    rmol, rindex = _rdkit(r_numbers, zeros_r, r_bonds if exact else {k: 1 for k in r_bonds}, r_atoms)
    pmol, pindex = _rdkit(p_numbers, zeros_p, p_bonds if exact else {k: 1 for k in p_bonds}, p_atoms)
    if pmol.GetNumAtoms() == 0 or rmol.GetNumAtoms() == 0:
        return []
    params = rdFMCS.MCSParameters()
    params.AtomTyper = rdFMCS.AtomCompare.CompareElements
    params.BondTyper = rdFMCS.BondCompare.CompareOrderExact if exact else rdFMCS.BondCompare.CompareAny
    params.BondCompareParameters.RingMatchesRingOnly = False
    params.BondCompareParameters.CompleteRingsOnly = False
    params.Timeout = timeout
    found = rdFMCS.FindMCS([pmol, rmol], params)
    if not found.smartsString or found.numAtoms == 0:
        return []
    query = Chem.MolFromSmarts(found.smartsString)
    pm_all = pmol.GetSubstructMatches(query, useChirality=False, uniquify=False, maxMatches=60)
    rm_all = rmol.GetSubstructMatches(query, useChirality=False, uniquify=False, maxMatches=60)
    if not pm_all or not rm_all:
        return []
    pback = {v: k for k, v in pindex.items()}
    rback = {v: k for k, v in rindex.items()}

    def hydrogens(atom, numbers, bonds):
        return sum(1 for (i, j) in bonds if atom in (i, j) and numbers[j if i == atom else i] == 1)

    # Parmi les correspondances, celle qui change le moins : mêmes ordres de liaison, mêmes nombres d'hydrogènes.
    best, score = None, None
    for pm in pm_all:
        for rm in rm_all:
            s = 0
            for b in query.GetBonds():
                i, j = b.GetBeginAtomIdx(), b.GetEndAtomIdx()
                pa, pb = pback[pm[i]], pback[pm[j]]
                ra, rb = rback[rm[i]], rback[rm[j]]
                if p_bonds.get((min(pa, pb), max(pa, pb))) == r_bonds.get((min(ra, rb), max(ra, rb))):
                    s += 1
            for i in range(len(pm)):
                if hydrogens(pback[pm[i]], p_numbers, p_bonds) == hydrogens(rback[rm[i]], r_numbers, r_bonds):
                    s += 2
            if score is None or s > score:
                best, score = (pm, rm), s
    pm, rm = best
    return [(pback[pm[i]], rback[rm[i]]) for i in range(len(pm))]


def map_atoms(r_numbers, r_bonds, p_numbers, p_bonds, r_groups=None):
    """Chaque atome des produits → un atome des réactifs (même élément), au moins de liaisons changées possible.

    Plusieurs correspondances sont essayées — la plus grande sous-structure commune (RDKit FMCS, atomes lourds) avec
    tous les réactifs à la fois, ou molécule par molécule dans chaque ordre, liaisons de tout ordre ou d'ordre exact —
    chacune complétée par les hydrogènes ; celle qui forme, rompt et change le moins de liaisons est gardée."""
    import itertools
    need, have = {}, {}
    for z in p_numbers:
        need[z] = need.get(z, 0) + 1
    for z in r_numbers:
        have[z] = have.get(z, 0) + 1
    for z, c in need.items():
        if have.get(z, 0) < c:
            raise SchemeError(f"le produit demande {c} {SYMBOLS[z]}, les réactifs n'en ont que {have.get(z, 0)} : "
                              "ajoutez le réactif manquant au-dessus de la flèche")
    r_heavy = [k for k, z in enumerate(r_numbers) if z > 1]
    sequences = [[r_heavy]]
    if r_groups and len(r_groups) > 1:
        heavy_groups = [g for g in ([a for a in group if r_numbers[a] > 1] for group in r_groups) if g]
        for perm in itertools.islice(itertools.permutations(heavy_groups), 6):
            sequences.append(list(perm))
    best = None
    for exact in (False, True):
        for sequence in sequences:
            try:
                heavy = _heavy_mapping(r_numbers, r_bonds, p_numbers, p_bonds, sequence, exact)
                mapping = _with_hydrogens(r_numbers, r_bonds, p_numbers, p_bonds, heavy)
            except SchemeError:
                continue
            cost = _changes(r_bonds, p_bonds, mapping, len(r_numbers))
            if best is None or cost < best[0]:
                best = (cost, mapping)
    if best is None:
        raise SchemeError("aucune correspondance entre les atomes des réactifs et ceux du produit")
    return best[1]


def _changes(r_bonds, p_bonds, mapping, n):
    """Combien de liaisons une correspondance forme ou rompt (1 chacune) ou change d'ordre (½)."""
    target = {}
    for (a, b), o in p_bonds.items():
        ra, rb = mapping[a], mapping[b]
        target[(min(ra, rb), max(ra, rb))] = o
    mapped = set(mapping.values())
    for (a, b), o in r_bonds.items():
        if a not in mapped and b not in mapped:
            target[(a, b)] = o
    cost = 0.0
    for key in set(target) | set(r_bonds):
        before, after = r_bonds.get(key, 0), target.get(key, 0)
        if before != after:
            cost += 0.5 if before and after else 1.0
    return cost


def _heavy_mapping(r_numbers, r_bonds, p_numbers, p_bonds, sequence, exact):
    p_heavy = [k for k, z in enumerate(p_numbers) if z > 1]
    mapping, used = {}, set()
    left_p = list(p_heavy)
    for group in list(sequence) + [[k for k, z in enumerate(r_numbers) if z > 1]]:
        for _ in range(8):
            if not left_p:
                break
            free = [a for a in group if a not in used]
            if not free:
                break
            pairs = _heavy_mcs(r_numbers, r_bonds, free, p_numbers, p_bonds, left_p, exact=exact)
            if len(pairs) < 2:
                break
            for pa, ra in pairs:
                mapping[pa] = ra
                used.add(ra)
            left_p = [k for k in left_p if k not in mapping]
    left_r = [k for k, z in enumerate(r_numbers) if z > 1 and k not in used]
    # Les atomes lourds restants : le même élément, voisin d'un atome dont le voisin correspond.
    for pa in list(left_p):
        z = p_numbers[pa]
        p_near = {mapping[n] for (i, j) in p_bonds for n in (i, j) if pa in (i, j) and n != pa and n in mapping}
        options = [ra for ra in left_r if r_numbers[ra] == z]
        if not options:
            raise SchemeError(f"aucun {SYMBOLS[z]} des réactifs ne peut devenir celui du produit")

        def closeness(ra):
            r_near = {n for (i, j) in r_bonds for n in (i, j) if ra in (i, j) and n != ra}
            return -len(r_near & p_near)

        options.sort(key=closeness)
        mapping[pa] = options[0]
        left_r.remove(options[0])
    return mapping


def _with_hydrogens(r_numbers, r_bonds, p_numbers, p_bonds, heavy):
    """La correspondance des atomes lourds complétée : les hydrogènes de chaque atome, puis ceux qui changent d'atome."""
    mapping = dict(heavy)
    used = set(mapping.values())

    def hydrogens(atom, numbers, bonds):
        return sorted(n for (i, j) in bonds for n in (i, j) if atom in (i, j) and n != atom and numbers[n] == 1)

    pending = []
    for pa, ra in heavy.items():
        p_h = hydrogens(pa, p_numbers, p_bonds)
        r_h = [h for h in hydrogens(ra, r_numbers, r_bonds) if h not in used]
        for k, h in enumerate(p_h):
            if k < len(r_h):
                mapping[h] = r_h[k]
                used.add(r_h[k])
            else:
                pending.append((h, ra))
    waiting = {h for h, _ in pending}
    pending += [(k, -1) for k, z in enumerate(p_numbers) if z == 1 and k not in mapping and k not in waiting]
    free = [k for k in range(len(r_numbers)) if r_numbers[k] == 1 and k not in used]
    for h, ra in pending:
        if not free:
            raise SchemeError("un hydrogène du produit ne vient d'aucun réactif")
        free.sort(key=lambda f: 0 if ra < 0 else _graph_distance(r_bonds, f, ra))
        mapping[h] = free.pop(0)
    return mapping


def _graph_distance(bonds, a, b, limit=8):
    if a == b:
        return 0
    seen, frontier = {a}, [a]
    for d in range(1, limit):
        nxt = []
        for x in frontier:
            for (i, j) in bonds:
                if x in (i, j):
                    y = j if i == x else i
                    if y not in seen:
                        if y == b:
                            return d
                        seen.add(y)
                        nxt.append(y)
        frontier = nxt
    return limit


def byproducts(numbers, charges, bonds, leftover, total):
    """Les sous-produits : les atomes qui ne vont dans aucun produit dessiné, liés comme dans les réactifs (sauf aux
    atomes partis dans les produits). Leurs charges rendent compte de leurs liaisons (un N⁺ qui perd une liaison
    redevient neutre, un C≡O qui perd son métal devient [C⁻]≡[O⁺]) ; puis un cation et un anion covalents se lient
    (Me₃Si⁺ + F⁻ → Me₃SiF). `total` : la charge qu'ils doivent porter ensemble."""
    leftover = sorted(leftover)
    inner = {k: o for k, o in bonds.items() if k[0] in leftover and k[1] in leftover}
    q = {a: charges[a] for a in leftover}

    def bonded(a):
        return sum(o for (i, j), o in inner.items() if a in (i, j))

    for group in _fragments(len(numbers), inner, leftover):
        wrong = [a for a in group if not satisfied(numbers[a], q[a], bonded(a))]
        if not wrong or len(wrong) > 6:
            continue
        best = None
        import itertools
        for combo in itertools.product((0, -1, 1), repeat=len(wrong)):
            trial = dict(q)
            for a, c in zip(wrong, combo):
                trial[a] = c
            if not all(satisfied(numbers[a], trial[a], bonded(a)) for a in wrong):
                continue
            net = sum(trial[a] for a in group)
            # Le moins de charges ; l'anion sur un atome plus électronégatif que le carbone, le cation sur un moins.
            score = (abs(net), sum(1 for a in wrong if trial[a]),
                     sum(trial[a] * (ELECTRONEGATIVITY.get(numbers[a], 1.0) - 2.5) for a in wrong))
            if best is None or score < best[0]:
                best = (score, combo)
        if best is not None:
            for a, c in zip(wrong, best[1]):
                q[a] = c
    # Un cation et un anion covalents (pas un ion alcalin) se lient.
    alkali = {3, 11, 19, 37, 55, 4, 12, 20, 38, 56}
    for _ in range(6):
        groups = _fragments(len(numbers), inner, leftover)
        cations = [(a, g) for g in groups for a in g if q[a] > 0 and numbers[a] not in alkali
                   and satisfied(numbers[a], q[a] - 1, bonded(a) + 1)]
        anions = [(a, g) for g in groups for a in g if q[a] < 0 and numbers[a] not in alkali
                  and satisfied(numbers[a], q[a] + 1, bonded(a) + 1)]
        joined = False
        for a, ga in cations:
            for b, gb in anions:
                if ga is gb:
                    continue
                key = (min(a, b), max(a, b))
                inner[key] = inner.get(key, 0) + 1
                q[a] -= 1
                q[b] += 1
                joined = True
                break
            if joined:
                break
        if not joined:
            break
    if sum(q.values()) != total:
        # Ce qui reste de charge va sur l'atome le plus électronégatif (anion) ou le moins (cation).
        rest = total - sum(q.values())
        pick = (max if rest < 0 else min)(leftover, key=lambda a: ELECTRONEGATIVITY.get(numbers[a], 1.0))
        q[pick] += rest
    return q, inner


# ====================================================================== la géométrie 3D
def embed(numbers, charges, bonds, xy, seed=7):
    """Des positions 3D (Å) d'une molécule : RDKit (ETKDG puis UFF), sinon la pose par contraintes de l'assembleur."""
    n = len(numbers)
    if n == 1:
        return np.zeros((1, 3))
    try:
        from rdkit.Chem import AllChem
        mol, index = _rdkit(numbers, charges, bonds, list(range(n)))
        params = AllChem.ETKDGv3()
        params.randomSeed = seed
        params.useRandomCoords = True
        if AllChem.EmbedMolecule(mol, params) == 0:
            try:
                AllChem.UFFOptimizeMolecule(mol, maxIters=400)
            except Exception:
                pass
            p = mol.GetConformer().GetPositions()
            return np.array([p[index[k]] for k in range(n)], float)
    except Exception:
        pass
    # La pose par contraintes de l'assembleur, partie d'une mise en page 2D du graphe (RDKit), pas d'un dessin quelconque.
    xy = np.asarray(xy, float).reshape(n, 2)
    try:
        from rdkit.Chem import rdDepictor
        mol, index = _rdkit(numbers, charges, bonds, list(range(n)))
        rdDepictor.Compute2DCoords(mol)
        conf = mol.GetConformer()
        xy = np.array([[conf.GetAtomPosition(index[k]).x, conf.GetAtomPosition(index[k]).y] for k in range(n)]) / 1.5
    except Exception:
        if np.ptp(xy, axis=0).max() < 1e-6:
            xy = np.random.default_rng(seed).normal(size=(n, 2))
    return assembly.embed(numbers, [(a, b, o) for (a, b), o in bonds.items()], xy)


WALL = 2.6                     # Å : deux morceaux distincts de l'arrivée ne s'approchent pas plus près (relaxation finale)


def conformer(numbers, charges, bonds, xy, seed=7, count=6):
    """Le conformère le plus stable de quelques-uns (RDKit ETKDG, puis UFF) ; sinon la pose de l'assembleur."""
    n = len(numbers)
    # Un métal de transition : UFF pose mal ses complexes (un CO y passe en pont, puis GFN1 garde cette autre
    # structure) ; la pose directe (ETKDG seul) part plus près.
    if any(21 <= z <= 30 or 39 <= z <= 48 or 57 <= z <= 80 for z in numbers):
        return embed(numbers, charges, bonds, xy, seed)
    if n > 2:
        try:
            from rdkit.Chem import AllChem
            mol, index = _rdkit(numbers, charges, bonds, list(range(n)))
            params = AllChem.ETKDGv3()
            params.randomSeed = seed
            params.useRandomCoords = True
            ids = list(AllChem.EmbedMultipleConfs(mol, count, params))
            if ids:
                try:
                    energies = [e for _, e in AllChem.UFFOptimizeMoleculeConfs(mol, maxIters=500)]
                except Exception:
                    energies = [0.0] * len(ids)
                best = min(range(len(ids)), key=lambda k: energies[k])
                p = mol.GetConformer(ids[best]).GetPositions()
                return np.array([p[index[k]] for k in range(n)], float)
        except Exception:
            pass
    return embed(numbers, charges, bonds, xy, seed)


def relax(numbers, charge, positions, cancelled, iterations=400, callback=None, restraints=(), calc=None,
          groups=None, solvent=None, counter=None):
    """La géométrie relaxée par GFN1-xTB (L-BFGS), sous des contraintes harmoniques facultatives
    [(a, b, distance Å, raideur Eh/Å²)] ; (positions, énergie Eh sans les contraintes, résultat)."""
    calc = calc or xtb.Calculator(list(numbers), charge=int(charge), solvent=solvent)
    if len(numbers) == 1:
        r = calc.compute(np.zeros((1, 3)))
        return np.zeros((1, 3)), r.energy, r
    last = {}

    calls = [0]
    good = [None]                  # les charges du dernier calcul gardé : un pas refusé y revient
    other = None
    if groups is not None:
        groups = np.asarray(groups)
        other = groups[:, None] != groups[None, :]

    def energy(x):
        if cancelled():
            raise assembly.Cancelled()
        p = x.reshape(-1, 3)
        d = np.linalg.norm(p[:, None, :] - p[None, :, :], axis=2) + np.eye(len(p)) * 9
        # Une géométrie impossible (atomes confondus, cycle auto-cohérent emballé) : très chère, le pas recule.
        if not np.all(np.isfinite(p)) or d.min() < 0.35:
            return 1e3, np.zeros_like(x)
        if counter is not None:
            counter[0] += 1
        try:
            r = calc.compute(p)
        except ValueError:
            return 1e3, np.zeros_like(x)
        if not np.isfinite(r.energy) or not np.all(np.isfinite(r.gradient)) or r.unstable:
            return 1e3, np.zeros_like(x)
        # Un pas trop long (le premier de L-BFGS, surtout) peut mener où le cycle auto-cohérent ne converge plus qu'en
        # lissant les électrons à des milliers de kelvins : son énergie libre y est fausse (−T·S de dizaines de
        # kcal/mol), et le calculateur garderait ce palier chaud. Hors du point de départ, la géométrie est refusée et
        # le calculateur repart froid.
        if r.electronic_temperature > 1000 and calls[0] > 0:
            calc.level, calc._charges = 1.0, (None if good[0] is None else good[0].copy())
            return 1e3, np.zeros_like(x)
        calls[0] += 1
        good[0] = None if calc._charges is None else np.array(calc._charges, float)
        e, g = r.energy, r.gradient.copy()
        if other is not None:
            close = other & (d < WALL)
            if close.any():
                i, j = np.nonzero(np.triu(close, 1))
                v = p[i] - p[j]
                dij = d[i, j]
                e += float(np.sum(SPRING * (dij - WALL) ** 2))
                f = (2 * SPRING * (dij - WALL) / dij)[:, None] * v
                np.add.at(g, i, f)
                np.add.at(g, j, -f)
        for a, b, d0, k, *floor in restraints:
            v = p[a] - p[b]
            d = float(np.linalg.norm(v)) or 1e-9
            if floor and d >= d0:
                continue
            e += k * (d - d0) ** 2
            f = 2 * k * (d - d0) * v / d
            g[a] += f
            g[b] -= f
        last["r"] = r
        return e, g.ravel()

    x = descend(energy, np.asarray(positions, float).ravel(), iterations,
                callback=(lambda x: callback(x.reshape(-1, 3))) if callback else None).reshape(-1, 3)
    r = calc.compute(x)
    return x, r.energy, r


MAX_STEP = 0.2                 # Å : le plus long déplacement d'un atome en un pas de relaxation
BOND_SHOWN = 0.3               # l'indice de Mayer à partir duquel une paire est une liaison des images (vue 2.5D)
STIFFNESS = 2.57               # Eh/Å² (70 eV/Å²) : la raideur supposée avant le premier pas


def descend(fun, x0, iterations, callback=None, gtol=4e-4, memory=20):
    """L-BFGS à pas borné (celui d'ASE) : la direction des deux boucles de L-BFGS, raccourcie pour qu'aucun atome ne
    bouge de plus de MAX_STEP, divisée par deux tant que l'énergie monte (six fois au plus) — pas de recherche linéaire.

    Celui de SciPy (L-BFGS-B) pouvait s'arrêter sur place : son premier pas, long de 1 Å en tout, porté par les quelques
    atomes qu'une contrainte tire, menait à une géométrie à +12 eV ; sa recherche linéaire n'acceptait alors qu'un pas
    infime, et son test de « réduction relative » (rapportée à une énergie de 87 Eh) annonçait la convergence. Chaque
    palier d'un chemin imposé restait là où il commençait (CsF et l'ammonium de l'exemple du diène silylé : trois
    paliers identiques, puis un chemin à l'endroit bloqué à +16 eV). Les pas bornés suivent les cibles (2,585 Å pour
    2,585) en un calcul par pas, sans quitter les géométries où le cycle auto-cohérent converge. Rend la géométrie."""
    x = np.array(x0, float)
    f, g = fun(x)
    steps, changes = [], []
    for _ in range(iterations):
        if np.max(np.abs(g)) < gtol:
            break
        q, alphas = g.copy(), []
        for s, y in zip(reversed(steps), reversed(changes)):
            a = float(s @ q) / float(y @ s)
            alphas.append(a)
            q -= a * y
        z = q * (float(steps[-1] @ changes[-1]) / float(changes[-1] @ changes[-1]) if steps else 1 / STIFFNESS)
        for (s, y), a in zip(zip(steps, changes), reversed(alphas)):
            z += s * (a - float(y @ z) / float(y @ s))
        step = -z
        if float(step @ g) >= 0:                      # la mémoire ne descend plus : on l'oublie
            steps, changes = [], []
            step = -g / STIFFNESS
        longest = float(np.max(np.linalg.norm(step.reshape(-1, 3), axis=1)))
        if longest > MAX_STEP:
            step *= MAX_STEP / longest
        for _ in range(6):
            f_new, g_new = fun(x + step)
            if f_new <= f + 1e-6:
                break
            step *= 0.5
        else:
            break                                      # même un pas minuscule monte : la géométrie est là
        s, y = step, g_new - g
        if float(s @ y) > 1e-12:
            steps.append(s)
            changes.append(y)
            del steps[:-memory], changes[:-memory]
        x, f, g = x + step, f_new, g_new
        if callback is not None:
            callback(x)
    return x


def _rotation(v):
    theta = float(np.linalg.norm(v))
    if theta < 1e-12:
        return np.eye(3)
    k = v / theta
    K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3) + math.sin(theta) * K + (1 - math.cos(theta)) * K @ K


def contact(za, zb):
    """La distance où deux atomes qui vont se lier se font face dans le complexe de départ (Å)."""
    return max(2.2, analysis.covalent_radius(za) + analysis.covalent_radius(zb) + 1.4)


def dock(numbers, bodies, shapes, attract, rng):
    """Les molécules (corps rigides) rapprochées : chaque paire (a, b, d) d'`attract` à la distance d, aucun atome de
    deux molécules à moins de 2,2 Å (1,9 avec un H), le plus gros corps fixe. Positions (n, 3)."""
    if len(bodies) == 1:
        return shapes[0].copy() if len(shapes) == 1 else np.vstack(shapes)
    order = sorted(range(len(bodies)), key=lambda b: -len(bodies[b]))
    n = len(numbers)
    heavy = np.array([z > 1 for z in numbers])
    limit = np.where(heavy[:, None] & heavy[None, :], 2.4, np.where(heavy[:, None] | heavy[None, :], 2.0, 1.8))
    body_of = np.zeros(n, int)
    for b, atoms in enumerate(bodies):
        body_of[atoms] = b
    other = body_of[:, None] != body_of[None, :]
    centred = [s - s.mean(axis=0) for s in shapes]

    def place(params):
        p = np.zeros((n, 3))
        p[bodies[order[0]]] = centred[order[0]]
        for k, b in enumerate(order[1:]):
            v = params[6 * k:6 * k + 6]
            p[bodies[b]] = centred[b] @ _rotation(v[:3]).T + v[3:]
        return p

    outside = {b: [k for k in range(n) if body_of[k] != b] for b in range(len(bodies))}
    pa = np.array([a for a, _, _ in attract], int)
    pb = np.array([b for _, b, _ in attract], int)
    pt = np.array([t for _, _, t in attract], float)

    def cost(params):
        p = place(params)
        d = np.linalg.norm(p[:, None, :] - p[None, :, :], axis=2)
        clash = np.where(other, np.maximum(0.0, limit - d), 0.0)
        c = 20 * float(np.sum(clash ** 2))
        if len(pa):
            c += float(np.sum((d[pa, pb] - pt) ** 2))
        # Chaque corps près des autres (un spectateur reste au contact, pas au loin).
        for b in order[1:]:
            gap = np.min(d[np.ix_(bodies[b], outside[b])])
            c += 0.2 * max(0.0, gap - 3.2) ** 2
        return c

    best = None
    for attempt in range(3):
        start = []
        for k, b in enumerate(order[1:]):
            direction = rng.normal(size=3)
            direction /= np.linalg.norm(direction)
            radius = (np.max(np.linalg.norm(centred[order[0]], axis=1)) + np.max(np.linalg.norm(centred[b], axis=1))
                      + 2.5)
            start += list(rng.normal(scale=1.5, size=3)) + list(direction * radius)
        found = minimize(cost, np.array(start), method="L-BFGS-B", options={"maxiter": 200})
        if best is None or found.fun < best.fun:
            best = found
    return place(best.x)


# ====================================================================== une étape
class Step:
    def __init__(self, index, arrow, reactant_groups, product_groups):
        self.index, self.arrow = index, arrow
        self.reactant_groups, self.product_groups = reactant_groups, product_groups
        self.notes, self.conditions = [], []
        self.reagents_label = ""
        self.out = {}


def _decimal(value, digits):
    """Un nombre à la française (virgule décimale, vrai signe moins) : le client remet un point en anglais."""
    return f"{value:+.{digits}f}".replace("-", "−").replace(".", ",")


class Journal:
    """Ce que le calcul fait, au fil de l'eau — l'écran de calcul de l'éditeur : les lignes du journal (ce qui vient
    d'être calculé, sur quelle molécule, le nombre qui en sort), la géométrie en cours de relaxation, les paliers du
    chemin imposé qui remplissent le profil, et le nombre de calculs GFN1-xTB. Publié au plus toutes les 0,15 s."""
    LINES = 80

    def __init__(self, state=None, publish=None, started=None):
        self.state = state if state is not None else {}
        self.publish = publish
        self.started = time.monotonic() if started is None else started
        self.last = 0.0
        self.counter = [0]
        self.state["log"] = []
        self.state["live"] = self.blank(-1)

    @staticmethod
    def blank(step):
        return {"step": step, "caption": "", "numbers": [], "positions": [], "bonds": [], "pulled": [],
                "energy": 0.0, "backward": [], "forward": [], "calls": 0}

    def begin(self, step):
        self.state["live"] = self.blank(step)

    def line(self, step, what, subject="", value=""):
        log = self.state["log"]
        log.append((time.monotonic() - self.started, step, what, subject, value))
        del log[:-self.LINES]
        self.flush(True)

    def molecule(self, numbers, positions, caption=None, energy=None, pulled=None, force=False):
        live = self.state["live"]
        x = np.asarray(positions, float).reshape(-1, 3)
        if caption is not None:
            live["caption"] = caption
            if energy is None:
                live["energy"] = 0.0
        if pulled is not None:
            live["pulled"] = [int(v) for pair in pulled for v in pair]
        if energy is not None:
            live["energy"] = float(energy)
        if list(live["numbers"]) != [int(z) for z in numbers]:
            live["numbers"] = [int(z) for z in numbers]
            live["bonds"] = []
        if not live["bonds"] or force:
            radius = np.array([analysis.covalent_radius(z) for z in numbers])
            d = np.linalg.norm(x[:, None] - x[None], axis=2)
            i, j = np.nonzero(np.triu(d < 1.25 * (radius[:, None] + radius[None]), 1))
            live["bonds"] = [int(v) for pair in zip(i, j) for v in pair]
        live["positions"] = (x - x.mean(axis=0)).ravel().tolist()
        self.flush(force)

    def point(self, branch, x, ev):
        self.state["live"][branch].extend((float(x), float(ev)))
        self.flush(True)

    def flush(self, force=False):
        now = time.monotonic()
        if self.publish is None or not (force or now - self.last > 0.15):
            return
        self.last = now
        self.state["live"]["calls"] = self.counter[0]
        live = self.state["live"]
        self.publish(dict(self.state, log=list(self.state["log"]), live={k: (list(v) if isinstance(v, list) else v)
                                                                          for k, v in live.items()}))


def compile_scheme(document, cancelled=lambda: False, publish=lambda state: None, quick=False):
    """Le schéma, compilé puis calculé étape par étape. `publish(état)` reçoit l'avancement ; l'état final porte les
    étapes, leurs chemins (images, énergies, ordres des liaisons qui changent) et leurs verdicts."""
    started = time.monotonic()
    drawing_ = Drawing(document)
    plan = drawing_.steps()
    state = {"done": False, "error": "", "status": "Lecture du schéma…", "fraction": 0.0, "remaining": 0.0,
             "steps": []}
    if not plan:
        raise SchemeError("aucune flèche de réaction : tracez une flèche des réactifs vers les produits")
    # L'ordre des étapes : une étape dont un réactif est le produit d'une autre vient après elle.
    produced = {}
    for k, (_, _, products) in enumerate(plan):
        for g in products:
            produced.setdefault(g, k)
    ordered, placed = [], set()
    for _ in range(len(plan)):
        for k, (_, reactants, _) in enumerate(plan):
            if k in placed:
                continue
            if all(produced.get(g, -1) in placed or produced.get(g, -1) in (-1, k) for g in reactants):
                ordered.append(k)
                placed.add(k)
    ordered += [k for k in range(len(plan)) if k not in placed]
    steps = []
    for number, k in enumerate(ordered):
        arrow, reactants, products = plan[k]
        steps.append(Step(number, arrow, reactants, products))
    geometry = {}                            # molécule du dessin → (positions 3D Å, énergie Eh, solvant), relaxée seule à une étape précédente
    # Le numéro de chaque atome, gardé d'une étape à l'autre : un atome du produit de l'étape 1 repris à l'étape 2 y
    # garde son numéro (C3 reste C3) ; les atomes d'un réactif écrit en prennent de nouveaux, à la suite.
    numbering = {"_next": 1}
    weights = [1.0] * len(steps)
    journal = Journal(state, publish, started)
    journal.line(-1, "schéma lu", "", f"{len(steps)} " + ("étapes" if len(steps) > 1 else "étape"))
    for s, step in enumerate(steps):
        def progress(text, part, s=s):
            done = (s + part) / len(steps)
            now = time.monotonic()
            state["status"] = text
            if done > state["fraction"] + 1e-9 and done > .03:
                state["remaining"] = (now - started) * (1 - done) / done
            elif state.get("remaining", 0.0) > 0:
                # La barre n'avance pas (un palier long) : l'estimation décompte au lieu de remonter.
                state["remaining"] = max(1.0, state["remaining"] - (now - state.get("_at", now)))
            state["_at"] = now
            state["fraction"] = max(state["fraction"], done)
            journal.flush(True)

        try:
            journal.begin(s)
            out = run_step(drawing_, step, geometry, cancelled, progress, quick, journal, numbering)
        except assembly.Cancelled:
            raise
        except SchemeError as error:
            out = _failed(drawing_, step, str(error))
        except Exception as error:                   # une étape qui échoue n'arrête pas les suivantes
            import traceback
            traceback.print_exc()
            out = _failed(drawing_, step, f"le calcul de cette étape a échoué : {error}")
        state["steps"].append(out)
        if out["computed"]:
            journal.line(s, "étape calculée", out["products"], f"ΔE {_decimal(out['delta'], 2)} eV · barrière "
                         f"{_decimal(out['barrier'], 2).lstrip('+')} eV")
        else:
            journal.line(s, "étape non calculée", "", out["error"])
    journal.line(-1, "schéma calculé", "", f"{journal.counter[0]} calculs GFN1-xTB · "
                 f"{time.monotonic() - started:.0f} s".replace(".", ","))
    state.update(done=True, status="Schéma calculé", fraction=1.0, remaining=0.0)
    publish(dict(state, log=list(state["log"])))
    return state


def _failed(drawing_, step, reason):
    arrow = drawing_.arrows[step.arrow]
    return {"arrow": step.arrow, "reactant_atoms": [a for g in step.reactant_groups for a in drawing_.groups[g]],
            "product_atoms": [a for g in step.product_groups for a in drawing_.groups[g]],
            "reagents": arrow["above"], "conditions": arrow["below"], "reactants": "", "products": "",
            "byproducts": "", "mapping": "", "delta": 0.0, "barrier": 0.0, "verdict": reason, "confirmed": False,
            "notes": list(step.notes), "computed": False, "numbers": [], "frames": [], "energies": [], "changes": [],
            "orders": [], "charges": [], "jumps": [], "arrows3d": [], "scan": (0, 0), "error": reason, "drawn": [], "charge": 0,
            "solvent": "", "solvent_label": "", "bond_pairs": [], "bond_orders": [], "ids": [], "start_ev": 0.0,
            "end_ev": 0.0, "transfer_ev": 0.0, "temperature": 300.0, "entropy_ev": 0.0}


def run_step(drawing_, step, geometry, cancelled, progress, quick=False, journal=None, numbering=None):
    arrow = drawing_.arrows[step.arrow]
    journal = journal or Journal()
    numbering = numbering if numbering is not None else {"_next": 1}
    k_ = step.index
    if not step.reactant_groups:
        raise SchemeError("aucune molécule derrière la queue de la flèche")
    if not step.product_groups:
        raise SchemeError("aucune molécule devant la pointe de la flèche")
    # ---------------------------------------------------------------- les réactifs : dessinés et écrits
    pieces = [drawing_.pieces[g].copy() for g in step.reactant_groups]
    temperature, solvent = None, None
    for text, above in ((arrow["above"], True), (arrow["below"], False)):
        reagents, conditions, notes, kelvin, found = read_text(text, above)
        step.conditions += conditions
        step.notes += notes
        temperature = kelvin or temperature
        solvent = found or solvent
        for piece, count in reagents:
            for _ in range(count):
                pieces.append(piece.copy())

    def relax_(numbers, *args, caption=None, pulled=None, **kwargs):
        """Chaque calcul de l'étape dans son solvant (celui écrit sur la flèche ; aucun : le vide), montré en direct
        sur l'écran de calcul (la géométrie à chaque pas de L-BFGS)."""
        if caption is not None:
            journal.molecule(numbers, args[1], caption=caption, pulled=pulled or [], force=True)
        x, e, r = relax(numbers, *args, solvent=solvent, counter=journal.counter,
                        callback=lambda x: journal.molecule(numbers, x), **kwargs)
        journal.molecule(numbers, x, energy=e)
        return x, e, r

    def energy_text(e):
        return f"E {_decimal(e, 5)} Eh".replace("+", "")

    R = System(pieces)
    products = [drawing_.pieces[g].copy() for g in step.product_groups]
    P = System(products)
    if len(R) > MAX_ATOMS:
        raise SchemeError(f"{len(R)} atomes dans cette étape : au plus {MAX_ATOMS} pour un chemin calculé")
    # Un atome de la deuxième période qui porte plus de liaisons que sa valence ne le permet (un carbone à cinq
    # liaisons) : le dessin est faux. Il était calculé quand même (sans hydrogène, en silence).
    for side, system in (("réactifs", R), ("produits", P)):
        for piece in system.pieces:
            for a, z in enumerate(piece.numbers):
                if not 5 <= z <= 9:
                    continue
                bonded = sum(o for (i, j), o in piece.bonds.items() if a in (i, j))
                allowed = valence(z, piece.charges[a])
                # N, O, F peuvent donner un doublet de plus (le C≡O d'un carbonyle métallique : O à trois liaisons) ;
                # B et C, sans doublet à donner, non.
                if bonded > allowed + (1 if z >= 7 else 0):
                    q = piece.charges[a]
                    raise SchemeError(f"un {SYMBOLS[z]} des {side} porte {bonded} liaisons, au plus {allowed} "
                                      + (f"à la charge {q:+d}" if q else "neutre")
                                      + " : une liaison en trop, ou une charge oubliée ?")
    # Le numéro montré de chaque atome : celui qu'il avait dans le produit d'une étape précédente, sinon un nouveau.
    ids = [0] * len(R)
    for p, piece in enumerate(R.pieces):
        atoms = R.atoms_of(p)
        known = numbering.get(piece.component) if piece.kind == "drawn" else None
        if known is not None and len(known) == len(atoms):
            for a, number in zip(atoms, known):
                ids[a] = int(number)
        else:
            for a in atoms:
                ids[a] = numbering["_next"]
                numbering["_next"] += 1
    # ---------------------------------------------------------------- qui devient quoi
    curly = []
    for source, target in drawing_.curly:
        s_atoms = [R.drawn_index(a) for a in source]
        t_atoms = [R.drawn_index(a) for a in target]
        if all(k >= 0 for k in s_atoms + t_atoms):
            curly.append((tuple(s_atoms), tuple(t_atoms)))
    mapping, method, arrow_target = None, "correspondance", None
    if curly:
        try:
            charges_after, bonds_after = push(R.numbers, R.charges, R.bonds, curly)
            arrow_target = (charges_after, bonds_after)
            mapping = _match_arrows(R, P, bonds_after)
            if mapping is None:
                step.notes.append("les flèches courbes mènent à une autre structure que le produit dessiné : "
                                  "le calcul suit le produit dessiné")
            else:
                method = "flèches"
        except SchemeError as error:
            step.notes.append(f"flèches courbes : {error}")
    if mapping is None:
        mapping = map_atoms(R.numbers, R.bonds, P.numbers, P.bonds, _fragments(len(R), R.bonds, range(len(R))))
    inverse = {r: p for p, r in mapping.items()}
    leftover = [a for a in range(len(R)) if a not in inverse]
    # ---------------------------------------------------------------- le graphe d'arrivée (indices des réactifs)
    target_bonds = {}
    target_charges = list(R.charges)
    for (a, b), o in P.bonds.items():
        ra, rb = mapping[a], mapping[b]
        target_bonds[(min(ra, rb), max(ra, rb))] = o
    for p, r in mapping.items():
        target_charges[r] = P.charges[p]
    total = sum(R.charges)
    if leftover:
        if method == "flèches" and arrow_target is not None:
            ca, ba = arrow_target
            q = {a: ca[a] for a in leftover}
            inner = {k: o for k, o in ba.items() if k[0] in q and k[1] in q}
        else:
            q, inner = byproducts(R.numbers, R.charges, R.bonds, leftover, total - sum(P.charges))
        target_bonds.update(inner)
        for a in leftover:
            target_charges[a] = q[a]
    by_groups = _fragments(len(R), target_bonds, leftover) if leftover else []
    by_labels = [label([R.numbers[a] for a in g],
                                     [(g.index(a), g.index(b)) for (a, b) in target_bonds if a in g and b in g],
                                     sum(target_charges[a] for a in g)) for g in by_groups]
    # Les liaisons qui changent (les ordres de Kekulé des cycles aromatiques ne comptent pas).
    every = range(len(R))
    aromatic = aromatic_pairs(R.numbers, R.charges, R.bonds, every) | aromatic_pairs(
        R.numbers, target_charges, target_bonds, every)
    changes = []
    for key in sorted(set(R.bonds) | set(target_bonds)):
        before, after = R.bonds.get(key, 0), target_bonds.get(key, 0)
        if before == after or (before and after and key in aromatic):
            continue
        changes.append((key[0], key[1], before, after))
    if not changes:
        step.notes.append("aucune liaison ne change : le produit dessiné est le réactif")
    # ---------------------------------------------------------------- les réactifs, relaxés seuls
    from .reaction import _bonds
    progress(f"Étape {step.index + 1} : les réactifs se relaxent…", 0.02)
    rng = np.random.default_rng(17 + step.index)
    charge = sum(R.charges)
    # Les grosses étapes : moins de paliers et de pas (le calcul croît comme n³ ; 60 atomes ≈ 0,2 s le calcul sur
    # une machine libre) — une étape de 60 atomes ≈ 450 calculs.
    big = len(R) > 30
    iterations = 60 if quick else (150 if big else 220)
    scan_iterations = 8 if quick else (12 if big else SCAN_ITERATIONS)
    bodies = _fragments(len(R), R.bonds, range(len(R)))
    body_of = {a: b for b, atoms in enumerate(bodies) for a in atoms}
    e_reactants = 0.0
    alone_reactants, alone_products = [], []        # (numéros, positions) de chaque molécule relaxée seule (−TΔS)
    transfer = 0.0                               # Eh : ce que coûte aux réactifs repris le passage dans un autre milieu
    # Chaque pièce seule (une molécule dessinée, un réactif écrit — une paire d'ions comprise) : sa géométrie sortie
    # de l'étape précédente quand elle en vient, sinon son conformère le plus stable.
    for p, piece in enumerate(R.pieces):
        atoms = R.atoms_of(p)
        known = geometry.get(piece.component) if piece.kind == "drawn" else None
        if known is not None and len(known[0]) == len(atoms):
            if known[2] == solvent:
                e_reactants += known[1]              # relaxé seul à l'étape précédente, dans le même milieu : la même énergie
                alone_reactants.append((piece.numbers, np.asarray(known[0], float)))
                journal.line(k_, "réactif repris de l'étape précédente", piece.describe(), energy_text(known[1]))
                continue
            # Un autre milieu (du toluène à l'eau) : son énergie y est autre. Reprise de sa géométrie, relaxée ici —
            # l'énergie de l'étape précédente, calculée dans l'autre milieu, fausserait ΔE.
            x0 = np.asarray(known[0], float)
            xk, e, _ = relax_(piece.numbers, sum(piece.charges), x0 - x0.mean(axis=0), cancelled, iterations=iterations,
                             caption=f"réactif repris, dans son nouveau milieu · {piece.describe()}")
            e_reactants += e
            alone_reactants.append((piece.numbers, xk))
            transfer += e - known[1]
            journal.line(k_, "réactif repris de l'étape précédente, relaxé dans son nouveau milieu", piece.describe(),
                         energy_text(e))
            continue
        start_x = None
        if start_x is None:
            start_x = conformer(piece.numbers, piece.charges, piece.bonds, piece.xy, seed=7 + p)
            if piece.kind == "reagent" and len(_fragments(len(piece), piece.bonds, range(len(piece)))) > 1:
                start_x = _ion_pair(piece, start_x)
        xk, e, _ = relax_(piece.numbers, sum(piece.charges), start_x - start_x.mean(axis=0), cancelled,
                         iterations=iterations, caption=f"réactif seul · {piece.describe()}")
        e_reactants += e
        alone_reactants.append((piece.numbers, xk))
        journal.line(k_, "réactif relaxé seul", piece.describe(), energy_text(e))
    # ---------------------------------------------------------------- les produits, chacun seul
    # Le produit d'abord : son conformère le plus stable (plusieurs départs, UFF), relaxé par GFN1-xTB. Le chemin part
    # de lui, à rebours (voir plus bas) : une fermeture de cycle tirée depuis les réactifs peut fermer le cycle du
    # mauvais côté (un butadiène s-trans donne un trans-cyclohexène, 2 eV plus haut) ; à rebours, les réactifs
    # retrouvent d'eux-mêmes leur conformation réactive.
    progress(f"Étape {step.index + 1} : les produits se relaxent seuls…", 0.1)
    target_groups = _fragments(len(R), target_bonds, range(len(R)))
    group_of = {a: g for g, members in enumerate(target_groups) for a in members}
    mapped_piece = {}
    for p in range(len(P.pieces)):
        mapped_piece[frozenset(mapping[a] for a in P.atoms_of(p))] = p
    e_products = 0.0
    product_x = np.zeros((len(R), 3))
    unheld = []
    for g, group in enumerate(target_groups):
        local = {a: k for k, a in enumerate(group)}
        bonds_g = {(local[a], local[b]): o for (a, b), o in target_bonds.items() if a in local and b in local}
        charges_g = [target_charges[a] for a in group]
        sub = [R.numbers[a] for a in group]
        radius = [analysis.covalent_radius(z) for z in sub]

        def judge(xg, r):
            """Tient-il ? Une liaison dessinée est perdue si elle s'est vraiment allongée (au-delà de 1,3 fois la somme
            des rayons covalents) ; une nouvelle, si GFN1 la voit (Mayer, densité partagée) à moins de 1,15 fois.
            Les indices de Mayer seuls se trompent sur les liaisons à plusieurs centres (Co₂C₂ : sous 0,5)."""
            if len(sub) == 1:
                return set(), set()
            found = {(a, b) for a, b, _ in _bonds(sub, r)}

            def length(a, b):
                return float(np.linalg.norm(xg[a] - xg[b])) / (radius[a] + radius[b])

            return ({(a, b) for (a, b) in bonds_g if length(a, b) > 1.3},
                    {(a, b) for (a, b) in found - set(bonds_g) if length(a, b) < 1.15})

        # Un produit qui ne tient pas depuis un départ est réessayé depuis deux autres (GFN1 a parfois des isomères
        # voisins — un CO en pont — où une pose de départ peut tomber) : le premier qui tient est gardé.
        best = None
        drawn_piece = mapped_piece.get(frozenset(group))
        for attempt in range(1 if quick else 3):
            if drawn_piece is not None:
                # Un produit dessiné : posé depuis sa propre pièce (l'ordre et la mise en page du dessin), puis remis
                # dans l'ordre des atomes de l'étape.
                pc = P.pieces[drawn_piece]
                own = conformer(pc.numbers, pc.charges, pc.bonds, pc.xy, seed=7 + 101 * attempt)
                start_x = np.zeros((len(group), 3))
                for k, a in enumerate(P.atoms_of(drawn_piece)):
                    start_x[local[mapping[a]]] = own[k]
            else:
                start_x = conformer(sub, charges_g, bonds_g, np.random.default_rng(g + 7 * attempt).normal(size=(len(group), 2)),
                                    seed=11 + g + 101 * attempt)
            name_g = label(sub, list(bonds_g), sum(charges_g))
            xg, e, r = relax_(sub, sum(charges_g), start_x - start_x.mean(axis=0), cancelled, iterations=iterations,
                              caption=f"produit seul · {name_g}")
            # Juger une structure arrivée, pas en route : tant que les forces restent fortes, la relaxation continue.
            for _ in range(0 if quick else 2):
                if len(sub) == 1 or float(np.max(np.abs(r.gradient))) < 3e-3:
                    break
                xg, e, r = relax_(sub, sum(charges_g), xg, cancelled, iterations=iterations)
            lost, gained = judge(xg, r)
            holds = not lost and not gained
            journal.line(k_, "produit relaxé seul, il tient" if holds else
                         "produit relaxé seul, il ne tient pas" + (" : autre départ" if attempt < (0 if quick else 2)
                                                                    else ""), name_g, energy_text(e))
            if best is None or (holds and (bool(best[3] or best[4]) or e < best[1])) or (
                    not holds and bool(best[3] or best[4]) and e < best[1]):
                best = (xg, e, r, lost, gained)
            if holds:
                break
        xg, e, r, lost, gained = best
        e_products += e
        alone_products.append((sub, np.asarray(xg, float)))
        product_x[group] = xg
        if lost or gained:
            unheld.append((group, [(group[a], group[b]) for a, b in lost], [(group[a], group[b]) for a, b in gained]))
        piece = mapped_piece.get(frozenset(group))
        if piece is not None:
            geometry[P.pieces[piece].component] = (xg[[local[mapping[a]] for a in P.atoms_of(piece)]], e, solvent)
            numbering[P.pieces[piece].component] = [ids[mapping[a]] for a in P.atoms_of(piece)]
    # ---------------------------------------------------------------- le complexe d'arrivée
    progress(f"Étape {step.index + 1} : les produits se posent ensemble…", 0.2)
    attract = []
    for a, b, before, after in changes:
        if after == 0 and group_of[a] != group_of[b]:
            attract.append((a, b, contact(R.numbers[a], R.numbers[b]) + 0.4))
    # Une substitution : le groupe qui part, à l'opposé de celui qui arrive (l'attaque par l'arrière).
    for c, x_, before, after in changes:
        if before > 0 and after == 0:
            for a, b, b0, a0 in changes:
                if b0 == 0 and a0 > 0 and c in (a, b):
                    y = b if a == c else a
                    if y != x_ and group_of[y] != group_of[x_]:
                        attract.append((x_, y, contact(R.numbers[c], R.numbers[x_]) + 0.4 + 1.5))
    complex_p = dock(R.numbers, target_groups, [product_x[g] for g in target_groups], attract, rng)
    journal.line(k_, "produits posés ensemble", "", f"{len(target_groups)} " + (
        "molécules" if len(target_groups) > 1 else "molécule"))
    # ---------------------------------------------------------------- le chemin, calculé à rebours
    calc = xtb.Calculator(list(R.numbers), charge=int(charge), solvent=solvent)
    targets = []
    # Seules les liaisons qui se forment ou se rompent sont tirées ; celles qui changent d'ordre suivent les électrons.
    for a, b, before, after in changes:
        if before and after:
            continue
        ra, rb = analysis.covalent_radius(R.numbers[a]), analysis.covalent_radius(R.numbers[b])
        d_p = float(np.linalg.norm(complex_p[a] - complex_p[b]))
        if before == 0:
            d_r = max(d_p, contact(R.numbers[a], R.numbers[b]))
        else:
            d_r = (ra + rb) * assembly.SHORTER[min(3, before)]
        targets.append((a, b, d_p, d_r, SPRING))
    points = 3 if quick else (8 if big else SCAN_POINTS)
    lambdas = [i / points for i in range(points + 1)]          # 0 : le produit, 1 : le contact des réactifs
    pulled = [(a, b) for a, b, *_ in targets]
    reference_e = [None]

    def scan(x, order, part):
        out = {}
        branch = "backward" if part == 0 else "forward"
        for n, i in enumerate(order):
            progress(f"Étape {step.index + 1} : le chemin, {round(100 * (part + n / len(order)) / 2)} %",
                     0.25 + 0.55 * (part + n / len(order)) / 2)
            restraints = [(a, b, d0 + (d1 - d0) * lambdas[i], k) for a, b, d0, d1, k in targets]
            # Un calculateur neuf à chaque palier : repartir des charges du palier précédent garderait la solution
            # électronique de l'autre branche.
            x, e, r = relax_(R.numbers, charge, x, cancelled, iterations=scan_iterations, restraints=restraints,
                             caption=("chemin à rebours" if part == 0 else "chemin à l'endroit")
                             + f", palier {n + 1}/{len(order)}", pulled=pulled)
            out[i] = (x, r)
            if reference_e[0] is None:
                reference_e[0] = e
            ev = (e - reference_e[0]) * EV
            journal.point(branch, 1 - lambdas[i], ev)
            shown = ""
            if targets:
                a, b = targets[0][0], targets[0][1]
                shown = (f" · {SYMBOLS[R.numbers[a]]}{ids[a]}–{SYMBOLS[R.numbers[b]]}{ids[b]} "
                         f"{_decimal(float(np.linalg.norm(x[a] - x[b])), 2).lstrip('+')} Å")
            journal.line(k_, ("chemin à rebours" if part == 0 else "chemin à l'endroit") + f", palier {n + 1}/{len(order)}",
                         "", f"{_decimal(ev, 2)} eV depuis le produit" + shown)
        return out

    # À rebours, du produit au contact : les réactifs y prennent leur conformation réactive.
    backwards = scan(complex_p, range(points + 1), 0)
    x = backwards[points][0]
    # Le complexe de départ, relaxé sans contrainte ; ses molécules ne se lient pas entre elles (paroi à WALL).
    progress(f"Étape {step.index + 1} : les réactifs se posent ensemble…", 0.5)
    x_r, e_r, r_r = relax_(R.numbers, charge, x, cancelled, iterations=30 if quick else (40 if big else FINAL_ITERATIONS // 2),
                          calc=calc, groups=[body_of[a] for a in range(len(R))], caption="réactifs posés ensemble")
    journal.line(k_, "réactifs posés ensemble, relaxés", "", f"{_decimal((e_r - reference_e[0]) * EV, 2)} eV")
    # Puis à l'endroit, du contact au produit. Un chemin tiré dans un seul sens reste sur sa branche (le produit
    # étiré garde ses carbones sp³ bien après le col) : à chaque palier, l'image la plus basse des deux sens — mais
    # seulement là où elle reste reliée à la précédente (voir _continuous).
    forwards = scan(x_r, range(points, -1, -1), 1)
    path = _continuous(backwards, forwards, points, R.numbers, calc)
    # Là où deux images voisines ne se rejoignent pas (un pas trop grand dans une descente raide : Robinson, étape 1,
    # juste après le col, C2 se pyramidalise de 0,52 Å et l'énergie tombe de 2,6 à 0,7 eV d'un palier au suivant), des
    # images calculées entre elles : le palier du milieu, relaxé sous ses contraintes depuis leur mélange par corps
    # rigides, deux fois au plus en profondeur. Ce qui reste séparé est dit (saut de branche, plus bas).
    from . import lab as lab_

    def linked(a, b):
        if lab_.internal_motion(a[0], b[0], R.numbers) <= lab_.JUMP:
            return True
        middle = calc.compute(lab_.blend(a[0], b[0], 0.5, R.numbers), gradient=False).energy
        return (middle - (a[1].energy + b[1].energy) / 2) * EV <= lab_.JUMP_EV

    def between(a, b, la, lb, depth):
        if depth == 0 or linked(a, b):
            return []
        level = (la + lb) / 2
        restraints = [(p, q, d0 + (d1 - d0) * level, k) for p, q, d0, d1, k in targets]
        x, e, r = relax_(R.numbers, charge, lab_.blend(a[0], b[0], 0.5, R.numbers), cancelled, iterations=scan_iterations,
                         restraints=restraints, caption="chemin affiné entre deux paliers", pulled=pulled)
        middle = (x, r)
        return between(a, middle, la, level, depth - 1) + [middle] + between(middle, b, level, lb, depth - 1)

    levels = [lambdas[i] for i in range(points, -1, -1)]
    refined = [path[0]]
    for j in range(1, len(path)):
        refined += between(path[j - 1], path[j], levels[j - 1], levels[j], 0 if quick else 2) + [path[j]]
    if len(refined) > len(path):
        journal.line(k_, "chemin affiné", "", f"{len(refined) - len(path)} " + (
            "images calculées de plus" if len(refined) - len(path) > 1 else "image calculée de plus"))
    path = refined
    found_r = {(a, b) for a, b, _ in _bonds(R.numbers, r_r)}
    early = [(a, b) for a, b, before, after in changes if before == 0 and (a, b) in found_r]
    late = [(a, b) for a, b, before, after in changes if after == 0 and before > 0 and (a, b) not in found_r]
    frames, energies, orders, charges, mayers = [], [], [], [], []

    def record(p, result=None):
        r = result if result is not None else calc.compute(p)
        frames.append(np.array(p, float))
        energies.append(float(r.energy))
        charges.append([float(q) for q in r.atom_charges])        # Mulliken, du même calcul que l'énergie
        try:
            m = np.asarray(analysis.mayer_bond_orders(r), float)
            orders.append([float(m[a, b]) for a, b, _, _ in changes])
            mayers.append(m)
        except Exception:
            orders.append([0.0] * len(changes))
            mayers.append(None)

    # L'approche : les molécules qui vont se lier arrivent de plus loin. Deux molécules s'écartent l'une de l'autre ; à
    # partir de trois (une molécule et les deux ions d'un sel), les autres s'écartent de la plus grosse. Une image où un
    # morceau passerait à travers un autre (deux atomes de morceaux différents plus proches qu'au contact) n'est pas
    # montrée : CsF à côté d'un ammonium, écartés depuis leur centre commun, plaçait F⁻ dans un méthyle (+316 eV).
    reacting = {body_of[a] for a, b, before, _ in changes if before == 0} | {
        body_of[b] for a, b, before, _ in changes if before == 0}
    if len(bodies) > 1 and len(reacting) > 1:
        anchor = max(range(len(bodies)), key=lambda b: len(bodies[b])) if len(bodies) > 2 else -1
        centre = x_r[bodies[anchor]].mean(axis=0) if anchor >= 0 else x_r.mean(axis=0)
        closest = _closest_between(x_r, bodies)
        for shift in APPROACH:
            p = x_r.copy()
            for b in range(len(bodies)):
                if b == anchor:
                    continue
                c = x_r[bodies[b]].mean(axis=0) - centre
                norm = np.linalg.norm(c)
                if norm > 1e-6:
                    p[bodies[b]] += c / norm * shift
            if _closest_between(p, bodies) >= closest - 0.05:
                record(p)
    scan_start = len(frames)
    record(x_r, r_r)
    for x, r in path:
        record(x, r)
    scan_end = len(frames)
    # Ce que le chemin a vraiment formé à son bout : les liaisons de sa dernière image (distances sous 1,25 fois la
    # somme des rayons covalents), comparées à celles du produit dessiné. Le chemin ne tire que les liaisons qui se
    # forment ou se rompent ; le reste est libre, et GFN1-xTB peut y faire autre chose — l'aldolisation catalysée,
    # étape 3 : l'hydroxyde qui se forme arrache un H au CH₂ voisin de la cétone (O1–H8) pendant que H9 passe sur O13.
    # Jugé comme un produit seul (judge, plus haut) : une liaison du dessin est rompue au-delà de 1,3 fois la somme des
    # rayons covalents ; une autre se forme si GFN1-xTB la lie (Mayer, densité partagée) à moins de 1,15 fois. La
    # distance seule prenait pour liaisons un contact d'ions (Cs⁺…F⁻) ou manquait une liaison à plusieurs centres.
    last = frames[-1]
    r_end = path[-1][1] if path else r_r
    radius = np.array([analysis.covalent_radius(z) for z in R.numbers])
    span = np.linalg.norm(last[:, None] - last[None], axis=2) / (radius[:, None] + radius[None])
    wanted_end = {key for key, o in target_bonds.items() if o > 0}
    other_broken = sorted((a, b) for a, b in wanted_end if span[a, b] > 1.3)
    other_formed = sorted({(min(a, b), max(a, b)) for a, b, _ in _bonds(R.numbers, r_end)
                           if (min(a, b), max(a, b)) not in wanted_end and span[a, b] < 1.15})
    found_end = (wanted_end - set(other_broken)) | set(other_formed)
    if other_formed or other_broken:
        said = []
        if other_formed:
            said.append("forme " + ", ".join(f"{SYMBOLS[R.numbers[a]]}{ids[a]}–{SYMBOLS[R.numbers[b]]}{ids[b]}"
                                             for a, b in other_formed[:3]))
        if other_broken:
            said.append("rompt " + ", ".join(f"{SYMBOLS[R.numbers[a]]}{ids[a]}–{SYMBOLS[R.numbers[b]]}{ids[b]}"
                                             for a, b in other_broken[:3]))
        step.notes.append("au bout du chemin imposé, GFN1-xTB ne finit pas sur le produit dessiné : il "
                          + " et ".join(said) + " (liaisons non tirées par le chemin)")
        journal.line(k_, "au bout du chemin, un autre produit", "", " ; ".join(said))
    # Le départ : les molécules présentes au bout du chemin s'écartent de la plus grosse (un sous-produit s'en va), comme
    # les réactifs arrivaient — chaque image calculée aussi, et aucune où un morceau en traverse un autre. Les morceaux
    # sont ceux de la dernière image, pas ceux du dessin : écarter le OH⁻ dessiné laissait seul au milieu le H8 que
    # l'hydroxyde avait pris (+3,4 eV à 1 Å, +5,3 eV à 3 Å).
    end_groups = _fragments(len(R), {key: 1 for key in found_end}, range(len(R)))
    if len(end_groups) > 1:
        main = max(range(len(end_groups)), key=lambda g: len(end_groups[g]))
        core = last[end_groups[main]].mean(axis=0)
        closest = _closest_between(last, end_groups)
        for shift in sorted(APPROACH):
            p = last.copy()
            for g, members in enumerate(end_groups):
                if g == main:
                    continue
                c = last[members].mean(axis=0) - core
                norm = float(np.linalg.norm(c))
                p[members] += (c / norm if norm > 1e-6 else np.array([1.0, 0.0, 0.0])) * shift
            if _closest_between(p, end_groups) >= closest - 0.05:
                record(p)
    # Les sauts de branche (voir lab.JUMP) : deux images voisines qu'aucune géométrie interpolée ne relie. La lecture
    # passe alors de l'une à l'autre, sans rien montrer entre elles.
    from . import lab as lab_
    jumps = []
    for k in range(len(frames) - 1):
        if lab_.internal_motion(frames[k], frames[k + 1], R.numbers) <= lab_.JUMP:
            continue
        middle = calc.compute(lab_.blend(frames[k], frames[k + 1], 0.5, R.numbers), gradient=False).energy
        bump = (middle - (energies[k] + energies[k + 1]) / 2) * EV
        if bump > lab_.JUMP_EV:
            jumps.append(k)
            journal.line(k_, f"saut de branche entre les images {k + 1} et {k + 2}", "",
                         f"{_decimal(bump, 2)} eV au milieu")
    # ---------------------------------------------------------------- le verdict

    def name(a, b):
        return f"{SYMBOLS[R.numbers[a]]}–{SYMBOLS[R.numbers[b]]}"

    if not unheld and (other_formed or other_broken):
        # Chaque produit tient seul, mais le chemin, lui, ne finit pas sur eux : il ne les relie pas aux réactifs.
        confirmed = False
        verdict = ("chaque produit tient seul (un minimum de GFN1-xTB), mais au bout du chemin imposé GFN1-xTB finit "
                   "sur un autre produit (voir les remarques)")
    elif not unheld:
        confirmed = True
        verdict = ("le calcul confirme : chaque produit tient seul (un minimum de GFN1-xTB), et le chemin le relie "
                   "aux réactifs")
    else:
        confirmed = False
        parts = []
        for group, lost, gained in unheld[:3]:
            what = label([R.numbers[a] for a in group],
                         [(group.index(a), group.index(b)) for (a, b) in target_bonds if a in group and b in group],
                         sum(target_charges[a] for a in group))
            bits = []
            if lost:
                bits.append("perd " + ", ".join(name(a, b) for a, b in lost[:3]))
            if gained:
                bits.append("forme " + ", ".join(name(a, b) for a, b in gained[:3]))
            parts.append(f"{what} ne tient pas seul ({' ; '.join(bits)})")
        verdict = "le calcul ne confirme pas — " + " ; ".join(parts)
    if early or late:
        step.notes.append("les réactifs rapprochés réagissent déjà en se relaxant (sans barrière, pour GFN1-xTB)")
    # Des ions formés dont les charges se compensent : comptés ensemble, en paire d'ions posée comme dans le complexe
    # d'arrivée (une paroi les empêche de se recombiner), comme le sel écrit sur la flèche l'est côté réactifs — et
    # non chacun seul à l'infini, ce qui coûterait en phase gazeuse toute leur attraction (≈ 9 eV pour NMe₄⁺ + I⁻).
    charged = [g for g in target_groups if sum(target_charges[a] for a in g)]
    if solvent is None and len(charged) >= 2 and sum(target_charges[a] for g in charged for a in g) == 0:
        atoms = [a for g in charged for a in g]
        alone = 0.0
        for g in charged:
            local = {a: k for k, a in enumerate(g)}
            sub = [R.numbers[a] for a in g]
            alone += relax_(sub, sum(target_charges[a] for a in g), product_x[g] - product_x[g].mean(axis=0),
                           cancelled, iterations=0)[1]
        index = {a: k for k, a in enumerate(atoms)}
        _, paired, _ = relax_([R.numbers[a] for a in atoms], 0, complex_p[atoms], cancelled, iterations=iterations,
                             groups=[group_of[a] for a in atoms])
        e_products += paired - alone
        step.notes.append("les ions formés sont comptés ensemble, en paire d'ions (phase gazeuse, sans solvant)")
    delta = (e_products - e_reactants) * EV
    barrier = (max(energies[scan_start:scan_end]) - energies[scan_start]) * EV if scan_end > scan_start else 0.0
    # Où sont la première et la dernière image par rapport aux molécules relaxées chacune seule (celles de ΔE) : de quoi
    # poser chaque étape à sa hauteur dans le profil de tout le schéma.
    start_ev = (energies[0] - e_reactants) * EV
    end_ev = (energies[-1] - e_products) * EV
    # Ce que ΔE ignore : l'entropie. Estimée pour les translations et les rotations de chaque molécule seule (gaz
    # parfait à 1 bar, rotor rigide), à la température de l'étape — ce qui compte quand des molécules partent (le
    # complexe au cobalt libère 2 CO). Ni les vibrations, ni la symétrie, ni le solvant (en solution, la translation
    # gagne moins).
    kelvin = temperature or 300.0
    entropy = sum(_entropy(z, x, kelvin) for z, x in alone_products) - sum(_entropy(z, x, kelvin) for z, x in alone_reactants)
    entropy_ev = -kelvin * entropy / 96485.33212
    if len(alone_products) > len(alone_reactants) and abs(entropy_ev) > 0.1:
        step.notes.append(f"{len(alone_products) - len(alone_reactants)} molécule(s) de plus au bout : ΔE ignore l'entropie "
                          f"qu'elles gagnent ; −TΔS (translations et rotations, gaz parfait à 1 bar, {kelvin:.0f} K) "
                          f"≈ {_decimal(entropy_ev, 2)} eV — en solution, la translation en gagne moins")
    step.notes.append("chemin imposé (distances tirées dans les deux sens, les images reliées les plus basses gardées), pas un état "
                      "de transition cherché : sa plus haute énergie estime la barrière selon ce chemin ; GFN1-xTB sous-"
                      "estime souvent les barrières (Diels–Alder butadiène + éthylène : 0,35 eV calculés, ≈ 1,2 eV "
                      "mesurés) ; " + (f"dans {solvation.label(solvent)} (solvant implicite ALPB), 0 K" if solvent
                                       else "phase gazeuse, 0 K"))
    ionic = any(sum(R.charges[a] for a in g) for g in bodies) or any(
        sum(target_charges[a] for a in g) for g in target_groups)
    reactant_ions = sum(1 for g in bodies if sum(R.charges[a] for a in g))
    product_ions = sum(1 for g in target_groups if sum(target_charges[a] for a in g))
    if reactant_ions != product_ions:
        step.notes.append("des ions se forment ou disparaissent : GFN1-xTB se trompe de plusieurs électronvolts sur "
                          "l'énergie des ions eux-mêmes (affinité électronique du chlore : 9,8 eV calculés, 3,6 mesurés ; "
                          "NMe₃ + MeI → NMe₄⁺ + I⁻ ≈ 5 eV trop haut), solvant ou non — ΔE n'en dit que le sens")
    if solvent:
        step.notes.append(f"dans {solvation.label(solvent)} : le solvant implicite ALPB de GFN1-xTB (ε = "
                          f"{solvation.data()['solvents'][solvent]['epsilon']:.4g}), un continuum — pas de molécule de "
                          "solvant qui participe, ni de liaison hydrogène dirigée")
    elif ionic:
        step.notes.append(("des ions : sans solvant, GFN1-xTB les décrit en phase gazeuse, où une réaction qui sépare des charges "
                          "(Menshutkin) est bien plus coûteuse qu'en solution"))
    if any(21 <= z <= 30 or 39 <= z <= 48 or 57 <= z <= 80 for z in R.numbers):
        step.notes.append("un métal de transition : GFN1-xTB est fait pour ses géométries (Co–Co et Co–C à quelques "
                          "centièmes d'Å près), pas pour ses énergies de liaison métal–ligand, souvent fausses de plus "
                          "d'un électronvolt")
    electrons = int(sum(xtb.Calculator(list(R.numbers), charge=charge).basis.refocc)) - charge
    if electrons % 2:
        step.notes.append("un nombre impair d'électrons : un radical, que GFN1-xTB sans spin décrit mal")
    reference = energies[0]
    # Les liaisons de chaque image (la vue 2.5D rejoue le chemin seule) : les paires liées dans au moins une image.
    bond_pairs = sorted({(int(a), int(b)) for m in mayers if m is not None
                         for a, b in zip(*np.nonzero(np.triu(m >= BOND_SHOWN, 1)))})
    bond_orders = [[float(m[a, b]) if m is not None else 0.0 for a, b in bond_pairs] for m in mayers]
    arrows3d = []
    for source, target in curly:
        arrows3d.append([source[0], source[1] if len(source) > 1 else -1,
                         target[0], target[1] if len(target) > 1 else -1])
    return {"arrow": step.arrow,
            "reactant_atoms": [a for g in step.reactant_groups for a in drawing_.groups[g]],
            "product_atoms": [a for g in step.product_groups for a in drawing_.groups[g]],
            "reagents": arrow["above"], "conditions": ", ".join(step.conditions) or arrow["below"],
            "reactants": " + ".join(p.describe() for p in R.pieces),
            "products": " + ".join(p.describe() for p in P.pieces),
            "byproducts": _counted(by_labels), "mapping": method, "delta": float(delta),
            "barrier": float(barrier), "verdict": verdict, "confirmed": confirmed, "notes": list(step.notes),
            "computed": True, "numbers": list(R.numbers), "frames": frames,
            "energies": [(e - reference) * EV for e in energies], "changes": changes, "orders": orders,
            "charges": charges, "jumps": jumps, "arrows3d": arrows3d, "scan": (scan_start, scan_end), "error": "", "temperature": temperature or 300.0,
            "bond_pairs": bond_pairs, "bond_orders": bond_orders, "ids": [int(v) for v in ids],
            "start_ev": float(start_ev), "end_ev": float(end_ev), "transfer_ev": float(transfer * EV),
            "entropy_ev": float(entropy_ev),
            "charge": int(charge), "solvent": solvent or "", "solvent_label": solvation.label(solvent) if solvent else "",
            "drawn": [R.pieces[p].drawn[l] for p, l in zip(R.owner, R.local)]}


def _entropy(numbers, x, kelvin, pressure=1e5):
    """L'entropie de translation et de rotation (J/mol/K) d'une molécule seule : gaz parfait (Sackur–Tetrode) à
    `pressure` Pa, rotor rigide (nombre de symétrie 1 : elle est surestimée de R ln σ, 5,8 J/mol/K pour σ = 2)."""
    kb, h, amu, r = 1.380649e-23, 6.62607015e-34, 1.66053906660e-27, 8.314462618
    masses = np.array([analysis.isotope_mass(int(z), 0) for z in numbers], float)
    m = float(masses.sum()) * amu
    t = float(kelvin)
    s = r * (math.log((2 * math.pi * m * kb * t / h ** 2) ** 1.5 * kb * t / pressure) + 2.5)
    if len(numbers) < 2:
        return s
    x = np.asarray(x, float).reshape(-1, 3)
    c = x - (masses @ x) / masses.sum()
    inertia = (masses[:, None, None] * (np.einsum("ij,ik->ijk", c, c) * -1 + np.eye(3)[None] * (c * c).sum(1)[:, None, None])).sum(0)
    moments = np.sort(np.linalg.eigvalsh(inertia)) * amu * 1e-20            # kg m²
    if moments[0] < 1e-3 * moments[2]:                                      # linéaire
        return s + r * (math.log(8 * math.pi ** 2 * moments[2] * kb * t / h ** 2) + 1)
    return s + r * (math.log(math.sqrt(math.pi) * (8 * math.pi ** 2 * kb * t / h ** 2) ** 1.5
                             * math.sqrt(float(np.prod(moments)))) + 1.5)


def _counted(labels):
    """Des espèces identiques comptées : « 2 CO · monoxyde de carbone », pas « CO · … + CO · … »."""
    counts = {}
    for item in labels:
        counts[item] = counts.get(item, 0) + 1
    return " + ".join(item if n == 1 else f"{n} {item}" for item, n in counts.items())


def _continuous(backwards, forwards, points, numbers, calc):
    """Les images du chemin gardées, du contact des réactifs (palier `points`) au produit (palier 0) : à chaque palier,
    celle de l'une ou l'autre branche (à rebours, à l'endroit). Chaque branche est continue (chaque palier part du
    précédent) ; les mêler image par image, la plus basse à chaque palier, sautait d'une branche à l'autre là où leurs
    images ne se rejoignent pas — Robinson, étape 1 : entre deux images voisines, C1–C13 formée d'un coup et H24 passé de
    C13 à C2, sans aucune géométrie calculée entre elles (lab.JUMP). Gardée maintenant : parmi les suites où deux images
    voisines sont reliées (même branche, ou deux branches qui s'y rejoignent : un atome y bouge de moins de JUMP hors de
    son corps rigide, ou le milieu ne coûte pas JUMP_EV de plus que la droite), celle dont la plus haute image est la plus
    basse, puis la somme des énergies. Rend [(positions, résultat)]."""
    from . import lab as lab_
    order = list(range(points, -1, -1))
    branches = (backwards, forwards)

    def linked(a, b):
        if lab_.internal_motion(a[0], b[0], numbers) <= lab_.JUMP:
            return True
        middle = calc.compute(lab_.blend(a[0], b[0], 0.5, numbers), gradient=False).energy
        return (middle - (a[1].energy + b[1].energy) / 2) * EV <= lab_.JUMP_EV

    infinite = (float("inf"), float("inf"))
    # cost[j][b] : (la plus haute énergie, la somme) de la meilleure suite qui finit au palier order[j] sur la branche b.
    cost = [[infinite, infinite] for _ in order]
    came = [[-1, -1] for _ in order]
    for b in (0, 1):
        e = branches[b][order[0]][1].energy
        cost[0][b] = (e, e)
    for j in range(1, len(order)):
        for b in (0, 1):
            here = branches[b][order[j]]
            e = here[1].energy
            for before in (0, 1):
                if cost[j - 1][before] == infinite:
                    continue
                if before != b and not linked(branches[before][order[j - 1]], here):
                    continue
                candidate = (max(cost[j - 1][before][0], e), cost[j - 1][before][1] + e)
                if candidate < cost[j][b]:
                    cost[j][b] = candidate
                    came[j][b] = before
    last = min((0, 1), key=lambda b: cost[-1][b])
    if cost[-1][last] == infinite:                 # aucune suite reliée (ne peut pas arriver : une branche l'est)
        return [min(backwards[i], forwards[i], key=lambda xr: xr[1].energy) for i in order]
    chosen = [0] * len(order)
    chosen[-1] = last
    for j in range(len(order) - 1, 0, -1):
        chosen[j - 1] = came[j][chosen[j]]
    return [branches[chosen[j]][order[j]] for j in range(len(order))]


def _closest_between(x, groups):
    """La plus courte distance (Å) entre deux atomes de morceaux différents (l'infini s'il n'y en a qu'un)."""
    owner = np.empty(len(x), int)
    for g, members in enumerate(groups):
        owner[list(members)] = g
    d = np.linalg.norm(x[:, None] - x[None], axis=2)
    d[owner[:, None] == owner[None, :]] = np.inf
    return float(d.min())


def _ion_pair(piece, start):
    """Les ions d'un sel écrit (CsF) posés au contact l'un de l'autre."""
    groups = _fragments(len(piece), piece.bonds, range(len(piece)))
    out = start.copy()
    x = 0.0
    for g in groups:
        out[g] -= out[g].mean(axis=0)
        out[g] += [x, 0, 0]
        x += 2.6
    return out


def _match_arrows(R, P, bonds_after):
    """Le graphe obtenu par les flèches contient-il chaque produit dessiné ? Alors la correspondance est exacte :
    {atome produit: atome réactif} ; sinon None."""
    import networkx as nx
    from networkx.algorithms import isomorphism
    g_after = _graph(R.numbers, bonds_after)
    mapping = {}
    used = set()
    for p in range(len(P.pieces)):
        atoms = P.atoms_of(p)
        g_p = _graph(P.numbers, P.bonds, atoms)
        hit = None
        for comp in nx.connected_components(g_after):
            if comp & used or len(comp) != len(atoms):
                continue
            sub = g_after.subgraph(comp)
            matcher = isomorphism.GraphMatcher(sub, g_p, node_match=lambda x, y: x["z"] == y["z"])
            if matcher.is_isomorphic():
                hit = {pa: ra for ra, pa in matcher.mapping.items()}
                used |= set(comp)
                break
        if hit is None:
            return None
        mapping.update(hit)
    return mapping


# ====================================================================== des exemples
def examples():
    """Des schémas tout faits pour l'éditeur (titre, document JSON) : composés d'après des SMILES."""
    out = []

    def place(smiles, dx, dy, doc, label=None):
        d = drawing(smiles)
        base = len(doc["atoms"])
        xs = [a[1] for a in d["atoms"]]
        width = (max(xs) - min(xs)) if xs else 0
        for z, x, y, q in d["atoms"]:
            doc["atoms"].append([z, x + dx, y + dy, q, None])
        for a, b, o in d["bonds"]:
            doc["bonds"].append([a + base, b + base, o])
        return base, width

    def arrow(doc, x0, x1, y, above="", below=""):
        doc["arrows"].append({"tail": [x0, y], "head": [x1, y], "above": above, "below": below})

    doc = {"atoms": [], "bonds": [], "arrows": [], "curly": []}
    place("C=CC=C", -6.5, 0, doc)
    place("C=C", -3.6, 0, doc)
    arrow(doc, -2.2, 0.6, 0)
    place("C1=CCCCC1", 3.2, 0, doc)
    out.append(("Diels–Alder : butadiène + éthylène", doc))

    doc = {"atoms": [], "bonds": [], "arrows": [], "curly": []}
    place("CN(C)C", -5, 0, doc)
    arrow(doc, -3.2, -0.2, 0, "MeI", "MeCN")
    place("C[N+](C)(C)C", 2.2, 0, doc)
    out.append(("Menshutkin : triméthylamine + iodométhane", doc))

    doc = {"atoms": [], "bonds": [], "arrows": [], "curly": []}
    place("CC#CC", -4.5, 0, doc)
    arrow(doc, -2.6, 0.6, 0, "Co2(CO)8")
    co = "CC12C(C)3[Co]1(C#O)(C#O)(C#O)[Co]23(C#O)(C#O)C#O"
    d = from_smiles(co)
    heavy = [k for k, z in enumerate(d.numbers) if z > 1]
    index = {a: k for k, a in enumerate(heavy)}
    base = len(doc["atoms"])
    xy = np.array([d.xy[a] for a in heavy])
    xy -= xy.mean(axis=0)
    for k, a in enumerate(heavy):
        doc["atoms"].append([d.numbers[a], 4.2 + xy[k][0], -xy[k][1], d.charges[a], None])
    for (a, b), o in d.bonds.items():
        if a in index and b in index:
            doc["bonds"].append([index[a] + base, index[b] + base, o])
    out.append(("Complexe alcyne–dicobalt : but-2-yne + Co₂(CO)₈", doc))

    doc = {"atoms": [], "bonds": [], "arrows": [], "curly": []}
    place("C=C(C[Si](C)(C)C)C(=C)CN(C)C", -16.5, 0, doc)
    place("O=C1C=CC(=O)N1c1ccccc1", -10.5, 0, doc)
    arrow(doc, -7.4, -5.0, 0)
    place("C[Si](C)(C)CC1=C(CN(C)C)CC2C(C1)C(=O)N(c1ccccc1)C2=O", 0.0, 0, doc)
    arrow(doc, 5.6, 8.2, 0, "MeI", "MeCN")
    place("C[Si](C)(C)CC1=C(C[N+](C)(C)C)CC2C(C1)C(=O)N(c1ccccc1)C2=O", 14.0, 0, doc)
    doc["arrows"].append({"tail": [14.0, 4.2], "head": [14.0, 7.0], "above": "CsF", "below": "MeCN"})
    place("C=C1CC2C(CC1=C)C(=O)N(c1ccccc1)C2=O", 14.0, 10.5, doc)
    place("C=CC(=O)OC", 8.2, 10.5, doc)
    arrow(doc, 6.4, 3.6, 10.5)
    place("COC(=O)C1CCC2=C(C1)CC1C(C2)C(=O)N(c3ccccc3)C1=O", -3.0, 10.5, doc)
    out.append(("Diène silylé : Diels–Alder, Menshutkin, élimination 1,4 par le fluorure, Diels–Alder", doc))
    for title, sequence in SEQUENCES:
        out.append((title, _row(sequence)))
    return out


# Des schémas en ligne : des molécules (SMILES, plusieurs séparées par « . » côte à côte) et des flèches
# (« > texte au-dessus | texte au-dessous »). Chaque étape a été calculée (docs/MOLECULE_MODEL.md).
SEQUENCES = [
    ("Annulation de Robinson : cétone de Wieland–Miescher (Michael, aldol, déshydratation)", [
        "CC1C(=O)CCCC1=O.C=CC(C)=O", "> | MeOH",
        "CC1(CCC(C)=O)C(=O)CCCC1=O", "> | MeOH",
        "CC12CCC(=O)CC1(O)CCCC2=O", "> | MeOH",
        "CC12CCC(=O)C=C1CCCC2=O"]),
    ("Wittig : ylure de phosphore + benzaldéhyde → oxaphosphétane → styrène + Ph₃PO", [
        "C=P(c1ccccc1)(c1ccccc1)c1ccccc1.O=Cc1ccccc1", "> | THF",
        "P1(c2ccccc2)(c2ccccc2)(c2ccccc2)CC(c2ccccc2)O1", "> | THF",
        "C=Cc1ccccc1.O=P(c1ccccc1)(c1ccccc1)c1ccccc1"]),
    ("Réarrangement de Claisen aromatique : éther d'allyle et de phényle → 2-allylphénol", [
        "C=CCOc1ccccc1", "> Δ |",
        "C=CCC1C=CC=CC1=O", "> |",
        "C=CCc1ccccc1O"]),
    ("Aldolisation-crotonisation dans l'eau : benzaldéhyde + acétone → benzalacétone", [
        "O=Cc1ccccc1.CC(C)=O", "> | H2O",
        "CC(=O)CC(O)c1ccccc1", "> | H2O",
        "CC(=O)C=Cc1ccccc1"]),
    # La même addition, catalysée par OH⁻ et dessinée étape par étape : sans catalyseur, le chemin imposé monte à
    # 3,9 eV ; ici 0,83 (l'énolate), 0,11 (l'addition), 2,36 eV (la reprotonation : GFN1 lie mal OH⁻ dans l'eau), pour
    # le même ΔE au total (−0,99 eV).
    ("Aldolisation catalysée par OH⁻ dans l'eau : énolate, addition, reprotonation", [
        "CC(C)=O.[OH-]", "> | H2O",
        "C=C(C)[O-].O", "> O=Cc1ccccc1 | H2O",
        "CC(=O)CC([O-])c1ccccc1.O", "> | H2O",
        "CC(=O)CC(O)c1ccccc1.[OH-]"]),
    ("Diels–Alder du cyclopentadiène et de l'anhydride maléique, puis hydrolyse en diacide", [
        "C1C=CC=C1.O=C1OC(=O)C=C1", "> | toluène",
        "O=C1OC(=O)C2C3C=CC(C3)C12", "> H2O | eau",
        "OC(=O)C1C2C=CC(C2)C1C(=O)O"]),
]


def _row(sequence):
    """Un schéma posé de gauche à droite : chaque molécule à sa largeur, chaque flèche de 2,6 liaisons."""
    doc = {"atoms": [], "bonds": [], "arrows": [], "curly": []}
    x = 0.0
    gap = 1.2
    for item in sequence:
        if item.startswith(">"):
            above, _, below = item[1:].partition("|")
            doc["arrows"].append({"tail": [x, 0.0], "head": [x + 2.6, 0.0], "above": above.strip(),
                                  "below": below.strip()})
            x += 2.6 + gap
            continue
        for k, smiles in enumerate(item.split(".")):
            d = drawing(smiles)
            xs = [a[1] for a in d["atoms"]]
            left, right = min(xs), max(xs)
            dx = x - left
            base = len(doc["atoms"])
            for z, ax, ay, q in d["atoms"]:
                doc["atoms"].append([z, ax + dx, ay, q, None])
            for a, b2, o in d["bonds"]:
                doc["bonds"].append([a + base, b2 + base, o])
            x += right - left + (1.6 if k < len(item.split(".")) - 1 else gap)
    return doc
