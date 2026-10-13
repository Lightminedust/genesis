"""L'échelle de la molécule : la chimie quantique décide, confrontée aux mesures.

Ces tests demandent PySCF (Linux, macOS) : ils s'exécutent dans le conteneur Docker.
"""

import unittest

from molecular import chemistry


@unittest.skipUnless(chemistry.AVAILABLE, "PySCF absent (Windows) : tests lancés dans Docker")
class BondTest(unittest.TestCase):
    pairs = {}

    @classmethod
    def molecule(cls, za, zb):
        if (za, zb) not in cls.pairs:
            molecule = chemistry.Molecule(za, zb)
            molecule.pair._compute()
            cls.pairs[(za, zb)] = molecule
        return cls.pairs[(za, zb)]

    def test_hydrogen_molecule_matches_experiment(self):
        pair = self.molecule(1, 1).pair
        self.assertTrue(pair.bound)
        self.assertAlmostEqual(pair.re, 0.741, delta=0.04)     # mesure : 0,741 Å
        self.assertAlmostEqual(pair.de, 4.75, delta=0.45)      # mesure : 4,75 eV
        self.assertAlmostEqual(pair.period_fs, 7.6, delta=0.8)  # 4401 cm⁻¹ : 7,6 fs
        point = pair.points[pair.nearest(pair.re)]
        self.assertAlmostEqual(point["bond_order"], 1.0, delta=0.1)
        self.assertAlmostEqual(point["charge_a"], 0.0, delta=0.01)

    def test_helium_does_not_bind(self):
        pair = self.molecule(2, 2).pair
        self.assertFalse(pair.bound)

    def test_sodium_chloride_transfers_charge(self):
        pair = self.molecule(11, 17).pair
        point = pair.points[pair.nearest(pair.re)]
        self.assertGreater(point["charge_a"], 0.4)              # le sodium perd de l'électron
        self.assertAlmostEqual(pair.re, 2.36, delta=0.08)       # mesure : 2,361 Å

    def test_oxygen_is_a_triplet(self):
        pair = self.molecule(8, 8).pair
        self.assertEqual(pair.spin, 2)                         # deux électrons célibataires
        self.assertAlmostEqual(pair.re, 1.21, delta=0.05)

    def test_curves_return_to_the_separated_atoms(self):
        # Au loin, l'énergie doit rejoindre celle des atomes séparés (0), quel que soit le spin.
        for za, zb in ((1, 1), (8, 8)):
            pair = self.molecule(za, zb).pair
            with self.subTest(pair=pair.symbols):
                self.assertAlmostEqual(pair.energy[-1], 0.0, delta=0.35)

    def test_bound_molecule_vibrates_without_escaping(self):
        molecule = self.molecule(1, 1)
        molecule.hold(0.95)
        events = molecule.release()
        self.assertIn("vibre", events[0]["title"])
        extremes = []
        for _ in range(200):
            molecule.advance(0.02)
            extremes.append(molecule.r)
        self.assertLess(max(extremes), 1.2)
        self.assertGreater(min(extremes), 0.5)

    def test_unbound_pair_flies_apart(self):
        molecule = self.molecule(2, 2)
        molecule.hold(1.6)
        events = molecule.release()
        self.assertIn("Pas de liaison", events[0]["title"])


if __name__ == "__main__":
    unittest.main()
