"""Le solvant implicite ALPB de GFN1-xTB (moleculelab/solvation.py) : paramètres, surfaces, gradients, mesures."""

import math
import unittest

import numpy as np

import genesis_pb2 as pb
from moleculelab import frame, scheme, solvation, xtb
from moleculelab.lab import MoleculeLab

KCAL = 627.509474


def molecule(smiles):
    p = scheme.from_smiles(smiles)
    return p.numbers, scheme.embed(p.numbers, p.charges, p.bonds, p.xy, seed=3)


def hydration(smiles, charge=0, solvent="water"):
    """ΔG (kcal/mol) : la molécule relaxée dans le vide, puis dans le solvant."""
    numbers, x = molecule(smiles)
    xg, eg, _ = scheme.relax(numbers, charge, x - x.mean(axis=0), lambda: False, iterations=400)
    calc = xtb.Calculator(numbers, charge=charge, solvent=solvent)
    _, es, _ = scheme.relax(numbers, charge, xg, lambda: False, iterations=400, calc=calc)
    return (es - eg) * KCAL


class ParameterTest(unittest.TestCase):
    def test_the_published_gfn1_alpb_sets_are_loaded(self):
        d = solvation.data()
        self.assertEqual(len(d["solvents"]), 25)
        self.assertEqual(sorted(d["solvents"]), sorted(solvation.ORDER))
        water = d["solvents"]["water"]
        self.assertAlmostEqual(water["epsilon"], 80.2)
        self.assertAlmostEqual(water["born_scale"], 1.94517356)
        self.assertEqual(len(water["descreening"]), 94)
        self.assertEqual(len(d["vdw_d3_angstrom"]), 94)
        self.assertEqual(len(d["atomic_rad_angstrom"]), 118)

    def test_names_written_on_arrows_are_found(self):
        self.assertEqual(solvation.find("MeCN"), "acetonitrile")
        self.assertEqual(solvation.find("H2O"), "water")
        self.assertEqual(solvation.find("THF"), "thf")
        self.assertEqual(solvation.find("DMF"), "dmf")
        self.assertIsNone(solvation.find("MeI"))


class GeometryTest(unittest.TestCase):
    def test_an_isolated_atom_has_the_whole_sphere(self):
        sv = solvation.Solvation([8], "water")
        surface = sv.prepare(np.zeros((1, 3)))["surface"][0]
        self.assertAlmostEqual(surface / (4 * math.pi * sv.vdwsa[0] ** 2), 1.0, delta=1e-3)

    def test_gradients_match_finite_differences(self):
        # Une molécule neutre et un anion, dans l'eau et le DMSO : l'énergie et ses dérivées sont cohérentes
        # (solvant dans le cycle auto-cohérent, Born, ALPB, CM5, surface).
        for smiles, charge, solvent in (("CO", 0, "dmso"), ("CC(=O)[O-]", -1, "water")):
            numbers, x = molecule(smiles)
            x = x + np.random.default_rng(1).normal(scale=0.05, size=x.shape)
            calc = xtb.Calculator(numbers, charge=charge, solvent=solvent)
            r = calc.compute(x)
            h, worst = 1e-4, 0.0
            for a in range(len(x)):
                for k in range(3):
                    xp, xm = x.copy(), x.copy()
                    xp[a, k] += h
                    xm[a, k] -= h
                    num = (calc.compute(xp, gradient=False).energy - calc.compute(xm, gradient=False).energy) / (2 * h)
                    worst = max(worst, abs(num - r.gradient[a, k]))
            self.assertLess(worst, 2e-6, smiles)

    def test_the_vacuum_is_unchanged(self):
        numbers, x = molecule("CO")
        self.assertEqual(xtb.Calculator(numbers).compute(x).energy,
                         xtb.Calculator(numbers, solvent=None).compute(x).energy)


class MeasuredTest(unittest.TestCase):
    def test_hydration_of_organic_molecules(self):
        # Mesures (Minnesota Solvation Database) : acétone −3,8, benzène −0,9, acétonitrile −3,9 kcal/mol.
        for smiles, measured in (("CC(C)=O", -3.8), ("c1ccccc1", -0.9), ("CC#N", -3.9)):
            self.assertAlmostEqual(hydration(smiles), measured, delta=1.5, msg=smiles)

    def test_an_anion_is_strongly_stabilised(self):
        # I⁻ dans l'eau : ≈ −54 kcal/mol (mesuré ≈ −60) ; dans le vide, rien.
        calc = xtb.Calculator([53], charge=-1, solvent="water")
        r = calc.compute(np.zeros((1, 3)))
        self.assertLess(r.solvation * KCAL, -45)
        self.assertGreater(r.solvation * KCAL, -65)


class LaboratoryTest(unittest.TestCase):
    def test_the_scene_can_be_solvated(self):
        lab = MoleculeLab(background=False)
        lab._do_preset(0)
        lab._do_solvent(solvation.ORDER.index("water"))
        self.assertEqual(lab.calc.solvation.solvent, "water")
        lab._do_solvent(-1)
        self.assertIsNone(lab.calc.solvation)

    def test_the_frame_lists_the_solvents(self):
        lab = MoleculeLab(background=False)
        lab._do_preset(0)
        lab._do_solvent(solvation.ORDER.index("dmf"))
        lab._publish()
        state = pb.MoleculeLabState()
        frame.fill(state, lab, {})
        self.assertEqual(len(state.solvents), 25)
        self.assertEqual(state.solvents[state.solvent], "DMF")
        self.assertNotEqual(state.solvation, 0.0)         # une petite molécule apolaire : légèrement positive (cavité)
        self.assertLess(abs(state.solvation), 1.0)


class SchemeTest(unittest.TestCase):
    def test_a_solvent_written_under_an_arrow_is_simulated(self):
        title, doc = scheme.examples()[1]                     # Menshutkin, « MeCN » sous la flèche
        step = scheme.compile_scheme(doc, quick=True)["steps"][0]
        self.assertEqual(step["solvent"], "acetonitrile")
        self.assertTrue(any("ALPB" in n for n in step["notes"]))
        self.assertTrue(any("ions eux-mêmes" in n for n in step["notes"]))

    def test_ions_formed_in_a_solvent(self):
        # NMe₃ + MeI → NMe₄⁺ + I⁻, chaque espèce seule : l'acétonitrile retire ≈ 4,3 eV aux ions séparés (NMe₄⁺
        # −2,2 eV, I⁻ −2,3 eV, comme les mesures). Le reste de l'écart à la mesure (réaction favorable en solution)
        # est l'énergie des ions de GFN1 lui-même, ≈ 5 eV trop haute dans le vide.
        energies = {}
        for name, smiles, charge in (("NMe3", "CN(C)C", 0), ("MeI", "CI", 0), ("NMe4", "C[N+](C)(C)C", 1),
                                     ("I", "[I-]", -1)):
            for medium in (None, "acetonitrile"):
                numbers, x = molecule(smiles)
                calc = xtb.Calculator(numbers, charge=charge, solvent=medium)
                energies[name, medium] = scheme.relax(numbers, charge, x - x.mean(axis=0), lambda: False,
                                                      iterations=300, calc=calc)[1]

        def delta(medium):
            return (energies["NMe4", medium] + energies["I", medium] - energies["NMe3", medium]
                    - energies["MeI", medium]) * xtb.HARTREE_EV

        self.assertAlmostEqual(delta(None) - delta("acetonitrile"), 4.25, delta=0.4)


if __name__ == "__main__":
    unittest.main()
