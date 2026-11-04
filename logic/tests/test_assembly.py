"""Le dictionnaire des espèces et l'assembleur 2D → 3D."""

import time
import unittest

import numpy as np

from moleculelab import analysis, assembly, dictionary, xtb
from moleculelab.lab import MoleculeLab, change


def drawing(*smiles):
    """Un dessin d'assembleur fait de ces molécules, posées côte à côte (positions 2D au hasard, graine fixe)."""
    numbers, charges, xy, bonds = [], [], [], []
    for k, s in enumerate(smiles):
        n, c, b = dictionary.parse(s)
        offset = len(numbers)
        xy += list(np.random.default_rng(k).normal(size=(len(n), 2)) + [6.0 * k, 0.0])
        numbers += n
        charges += c
        bonds += [(a + offset, bb + offset, o) for a, bb, o in b]
    return numbers, charges, np.array(xy), bonds


def wait(lab, condition, limit=90.0):
    start = time.time()
    while time.time() - start < limit:
        snap, grid = lab.snapshot()
        if snap is not None and condition(snap):
            return snap
        time.sleep(0.05)
    raise AssertionError("le laboratoire n'a pas atteint l'état attendu")


class DictionaryTest(unittest.TestCase):
    def test_every_entry_is_recognised_and_isomers_differ(self):
        keys = {}
        for smiles, name, english in dictionary.ENTRIES:
            numbers, charges, bonds = dictionary.parse(smiles)
            edges = [(a, b) for a, b, _ in bonds]
            self.assertEqual(dictionary.identify(numbers, edges, sum(charges)), dictionary._table()[
                dictionary.key(numbers, edges, sum(charges))][0])
            keys.setdefault(dictionary.key(numbers, edges, sum(charges)), set()).add(name)
            self.assertTrue(english)
        self.assertTrue(all(len(names) == 1 for names in keys.values()))
        # Même formule, autre graphe : C₂H₆O.
        for smiles, name in (("CCO", "éthanol"), ("COC", "éther diméthylique"), ("C1CC1", "cyclopropane"),
                             ("CC=C", "propène")):
            numbers, charges, bonds = dictionary.parse(smiles)
            self.assertEqual(dictionary.identify(numbers, [(a, b) for a, b, _ in bonds]), name)

    def test_a_graph_drawn_in_another_order_is_the_same_species(self):
        # L'eau écrite H–O–H, puis O, H, H : le graphe, pas l'ordre des atomes.
        self.assertEqual(dictionary.identify([1, 8, 1], [(0, 1), (1, 2)]), "eau")
        self.assertEqual(dictionary.identify([8, 1, 1], [(0, 1), (0, 2)]), "eau")
        self.assertEqual(dictionary.identify([8, 1], [(0, 1)]), "radical hydroxyle")
        self.assertEqual(dictionary.identify([8, 1], [(0, 1)], -1), "ion hydroxyde")
        self.assertIsNone(dictionary.identify([8, 8, 1, 1, 8, 8], [(0, 1), (1, 4), (4, 5), (0, 2), (5, 3)]))

    def test_the_lab_names_what_a_reaction_makes(self):
        # Les espèces de la scène portent leur nom ; un événement dit ce qui s'est formé et défait.
        self.assertEqual(change(("H₂ · dihydrogène", "H₂ · dihydrogène", "O₂ · dioxygène"),
                                ("H₂O · eau", "H₂O · eau")),
                         "formé : 2 × H₂O · eau  —  défait : 2 × H₂ · dihydrogène, O₂ · dioxygène")
        numbers, charges, xy, bonds = drawing("O")
        calc = xtb.Calculator(numbers)
        r = calc.compute(assembly.embed(numbers, bonds, xy))
        groups = analysis.species(numbers, analysis.mayer_bond_orders(r), r)
        self.assertEqual([label for _, _, label in groups], ["H₂O · eau"])


class AssemblyTest(unittest.TestCase):
    def test_the_drawing_travels_as_numbers(self):
        numbers, charges, xy, bonds = drawing("CC(O)=O", "[NH4+]")
        back = assembly.decode(assembly.encode(numbers, charges, xy, bonds))
        self.assertEqual(back[0], numbers)
        self.assertEqual(back[1], charges)
        np.testing.assert_allclose(back[2], xy)
        self.assertEqual(sorted(back[3]), sorted(bonds))
        self.assertEqual(len(assembly.components(len(numbers), bonds)), 2)

    def test_a_flat_drawing_becomes_a_3d_molecule(self):
        # Le méthane dessiné en croix, à plat : posé en tétraèdre (109,5°), liaisons C–H du rayon covalent.
        p = assembly.embed([6, 1, 1, 1, 1], [(0, 1, 1), (0, 2, 1), (0, 3, 1), (0, 4, 1)],
                           [[0, 0], [1, 0], [-1, 0], [0, 1], [0, -1]])
        for a in range(1, 5):
            for b in range(a + 1, 5):
                u, v = p[a] - p[0], p[b] - p[0]
                self.assertAlmostEqual(np.degrees(np.arccos(u @ v / np.linalg.norm(u) / np.linalg.norm(v))), 109.47,
                                       delta=1.5)
        # Deux molécules dessinées l'une sur l'autre : écartées d'au moins 2,5 Å.
        numbers, charges, xy, bonds = drawing("O", "O")
        xy[3:] = xy[:3]
        p = assembly.embed(numbers, bonds, xy)
        gap = np.min(np.linalg.norm(p[:3, None, :] - p[None, 3:, :], axis=2))
        self.assertGreaterEqual(gap, assembly.SEPARATION - 0.01)

    def test_the_verdict_comes_from_the_calculation(self):
        # L'eau tient ; deux néons liés au crayon se séparent ; le radical H₃O est dit radical.
        numbers, charges, xy, bonds = drawing("O", "[Ne][Ne]", "[OH3]")
        found = assembly.deep(numbers, charges, xy, bonds)
        self.assertEqual([m["name"] for m in found], ["eau", "", ""])
        self.assertEqual(found[0]["verdict"], "tient")
        self.assertGreater(found[0]["cohesion"], 10)
        self.assertEqual(found[1]["verdict"], "se défait")
        self.assertEqual(len(found[1]["lost"]), 1)
        self.assertEqual(found[2]["unpaired"], 1)

    def test_the_lab_builds_and_judges_a_drawing(self):
        lab = MoleculeLab(rng_seed=2)
        try:
            wait(lab, lambda s: s.get("validation") is not None and s["relaxed"], 180)
            numbers, charges, xy, bonds = drawing("O", "O=C=O")
            vector = assembly.encode(numbers, charges, xy, bonds)
            lab.assess(7, vector, True)
            snap = wait(lab, lambda s: s["assessment"] is not None and s["assessment"]["request"] == 7
                        and s["assessment"]["done"])
            self.assertEqual([m["verdict"] for m in snap["assessment"]["molecules"]], ["tient", "tient"])
            lab.build(vector, 0, False)
            snap = wait(lab, lambda s: len(s["numbers"]) == 6 and s["relaxed"] and "energy" in s, 120)
            self.assertEqual(snap["label"], "H₂O · eau + CO₂ · dioxyde de carbone")
            self.assertEqual(sorted(label for _, _, label in snap["species"]),
                             ["CO₂ · dioxyde de carbone", "H₂O · eau"])
        finally:
            lab.close()


if __name__ == "__main__":
    unittest.main()
