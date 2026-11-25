"""Réactifs → produits : la recherche de réaction de l'assembleur (le nanoréacteur)."""

import unittest

import numpy as np

import genesis_pb2 as pb
from moleculelab import assembly, frame, reaction
from moleculelab.lab import MoleculeLab

from tests.test_assembly import wait

# OH· et H· dessinés loin l'un de l'autre : deux radicaux, qui se lient sans barrière.
OH_H = ([8, 1, 1], [0, 0, 0], [(0, 0), (1, 0), (6, 0)], [(0, 1, 1)])


class ReactionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.published = []
        cls.state = reaction.explore(*OH_H, 1500, lambda: False, cls.published.append, trials=2, duration=300)

    def test_the_reactants_are_brought_into_contact(self):
        numbers, charges, xy, bonds = OH_H
        positions, energy, labels = reaction.reactants(numbers, charges, xy, bonds, lambda: False)
        self.assertEqual(sorted(labels), ["H · atome d'hydrogène", "HO · radical hydroxyle"])
        # Le H seul face au H du radical (dessinés en ligne : O—H ··· H) : les deux atomes les plus proches.
        closest = min(float(np.linalg.norm(positions[2] - positions[k])) for k in (0, 1))
        self.assertAlmostEqual(closest, reaction.CONTACT, delta=.01)
        self.assertAlmostEqual(float(np.linalg.norm(positions[1] - positions[0])), .97, delta=.03)

    def test_two_radicals_make_water(self):
        state = self.state
        self.assertTrue(state["done"])
        self.assertEqual(state["error"], "")
        self.assertEqual(state["reactants"], "H · atome d'hydrogène + HO · radical hydroxyle")
        best = state["outcomes"][0]
        self.assertEqual(best["label"], "H₂O · eau")
        self.assertEqual(best["count"], 2)
        self.assertFalse(best["unchanged"])
        # GFN1-xTB, sans spin, lie trop fort deux radicaux : ≈ −8 eV, pour ≈ −5,4 eV d'après la mesure (D₀ 5,10 eV
        # plus les énergies de point zéro). Le test garde le signe et l'ordre de grandeur, et cette surestimation.
        self.assertLess(best["delta"], -5.4)
        self.assertGreater(best["delta"], -10)
        water = best["products"][0]
        self.assertEqual(water["formula"], "H₂O")
        self.assertEqual(sorted((a, b) for a, b, _ in water["bonds"]), [(0, 1), (0, 2)])
        self.assertEqual(water["xy"].shape, (3, 2))

    def test_the_progress_is_published_as_it_goes(self):
        phases = [p["phase"] for p in self.published]
        self.assertEqual(phases[0], 0)
        self.assertIn(1, phases)
        self.assertIn(2, phases)
        self.assertEqual(phases[-1], 3)
        fractions = [p["fraction"] for p in self.published]
        self.assertEqual(fractions, sorted(fractions))
        self.assertEqual(fractions[-1], 1.0)

    def test_too_many_atoms_are_refused(self):
        with self.assertRaises(ValueError):
            reaction.explore([1] * (reaction.MAX_ATOMS + 1), [0] * 31, np.zeros((31, 2)), [], 1500,
                             lambda: False, lambda s: None)

    def test_the_lab_runs_cancels_and_sends_a_reaction(self):
        lab = MoleculeLab(rng_seed=3)
        try:
            wait(lab, lambda s: s["relaxed"], 180)
            lab.react(4, assembly.encode(*OH_H), 1500)
            wait(lab, lambda s: (s.get("reaction") or {}).get("request") == 4 and s["reaction"]["phase"] >= 1, 120)
            lab.react(-1, [], 0)
            wait(lab, lambda s: s.get("reaction") is None, 60)
            # Ce que le moteur envoie : l'état d'une réaction finie, recopié dans le message.
            lab._reaction = dict(self.state, request=5)
            lab._dirty = True
            state = pb.MoleculeLabState()
            frame.fill(state, lab, {})
            r = state.reaction
            self.assertEqual((r.request, r.done, r.phase, r.trials), (5, True, 3, 2))
            self.assertEqual(r.outcomes[0].label, "H₂O · eau")
            self.assertEqual(r.outcomes[0].products[0].formula, "H₂O")
            self.assertEqual(len(r.outcomes[0].products[0].xy), 6)
            self.assertEqual(len(r.outcomes[0].products[0].bonds), 6)
        finally:
            lab.close()


if __name__ == "__main__":
    unittest.main()
