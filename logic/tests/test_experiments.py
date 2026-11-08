"""Les cinq expériences guidées du laboratoire moléculaire : chacune doit réussir, par le chemin réel du moteur.

Chaque essai rejoue l'expérience comme l'interface la lance (MoleculeLab._do_experiment puis les pas du moteur) : le
choc, le relais du thermostat, puis le recensement par le dictionnaire. Une graine fixe par expérience ; les réglages
ont été vérifiés sur plusieurs autres tirages de l'agitation (voir moleculelab/experiments.py).
"""

import unittest

import numpy as np

from moleculelab import experiments, lab as lab_module
from moleculelab.lab import MoleculeLab


def run(index, seed):
    lab = MoleculeLab(rng_seed=seed)
    lab.close()
    lab._worker.join()
    lab._do_experiment(index)
    e = experiments.EXPERIMENTS[index]
    while lab.experiment is not None and lab.experiment["phase"] not in (experiments.SUCCEEDED, experiments.FAILED):
        lab._step()
        if lab.error:
            break
    return lab, e


class ExperimentsTest(unittest.TestCase):
    def test_catalogue(self):
        self.assertEqual(len(experiments.EXPERIMENTS), 5)
        self.assertEqual(experiments.EXPERIMENTS[0].title, "Diels-Alder")
        for e in experiments.EXPERIMENTS:
            self.assertTrue(e.goal and e.cool_fs < e.limit_fs and e.collision_ev > 0)
        self.assertTrue(experiments.reached(("C₆H₁₀ · cyclohexène",), ("cyclohexène",)))
        self.assertFalse(experiments.reached(("CH₃ · radical méthyle",), ("éthane",)))

    def test_launch(self):
        # Le choc : quantité de mouvement nulle, énergie relative exacte ; l'agitation ajoutée ne la change pas.
        numbers, positions, _, split = experiments.EXPERIMENTS[1].build()
        masses = np.array([lab_module.analysis.isotope_mass(z, 0) for z in numbers])
        v = experiments.launch(numbers, positions, split, masses, 0.5)
        self.assertAlmostEqual(float(np.abs(masses @ v).max()), 0.0, places=10)
        ev = lab_module.analysis.kinetic_energy(masses, v) * lab_module.EV
        self.assertAlmostEqual(ev, 0.5, places=6)
        noise = experiments.internal(np.random.default_rng(1).normal(size=v.shape) * 0.01, masses, split)
        for part in (slice(0, split), slice(split, None)):
            self.assertAlmostEqual(float(np.abs(masses[part] @ noise[part]).max()), 0.0, places=10)

    def test_sn2_backside(self):
        # L'OH⁻ arrive à l'opposé du chlore (O–C–Cl à 180°) : de côté, il arrache un proton au lieu de substituer.
        numbers, p, charge, _ = experiments.EXPERIMENTS[1].build()
        self.assertEqual(charge, -1)
        a, b = p[5] - p[0], p[1] - p[0]
        angle = np.degrees(np.arccos(a @ b / np.linalg.norm(a) / np.linalg.norm(b)))
        self.assertGreater(angle, 179.0)

    def test_diels_alder(self):
        lab, e = run(0, 3)
        self.assertEqual(lab.experiment["phase"], experiments.SUCCEEDED, lab.named)
        self.assertLess(lab.experiment["time"], e.cool_fs)         # le cycle se referme au premier choc
        # Le thermostat prend tout de même le relais : le cyclohexène refroidit, et reste un cyclohexène.
        while lab.time_fs < e.cool_fs + 50:
            lab._step()
        self.assertEqual(lab.dynamics, lab_module.THERMOSTAT)
        self.assertTrue(experiments.reached(lab.named, e.goal), lab.named)

    def test_sn2(self):
        lab, _ = run(1, 3)
        self.assertEqual(lab.experiment["phase"], experiments.SUCCEEDED, lab.named)

    def test_proton_transfer(self):
        lab, _ = run(2, 3)
        self.assertEqual(lab.experiment["phase"], experiments.SUCCEEDED, lab.named)

    def test_radicals(self):
        lab, _ = run(3, 3)
        self.assertEqual(lab.experiment["phase"], experiments.SUCCEEDED, lab.named)

    def test_water(self):
        lab, _ = run(4, 3)
        self.assertEqual(lab.experiment["phase"], experiments.SUCCEEDED, lab.named)
        self.assertTrue(any("réussie" in text for _, text, _ in lab.events))

    def test_new_scene_ends_experiment(self):
        lab = MoleculeLab(rng_seed=1)
        lab.close()
        lab._worker.join()
        lab._do_experiment(4)
        self.assertIsNotNone(lab.experiment)
        lab._do_add(1, -1, None)
        self.assertIsNone(lab.experiment)


if __name__ == "__main__":
    unittest.main()
