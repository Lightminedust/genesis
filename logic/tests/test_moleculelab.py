"""Le laboratoire moléculaire : GFN1-xTB confronté à l'implémentation de référence (dxtb) et à l'expérience."""

import time
import unittest

import numpy as np

import engine
from moleculelab import analysis, density, structures, xtb
from moleculelab import frame as molecule_frame
from moleculelab.lab import MoleculeLab
from moleculelab.validation import relax
from simulator.repository import ElementRepository
from simulator.simulation import Session

# Énergies (Eh) et gradients (Eh/Å) calculés par dxtb 0.4.0 (GFN1-xTB, PyTorch), positions en Å.
DXTB = {
    "H2O": ([8, 1, 1], [[0, 0, 0], [0, 0.757, 0.586], [0, -0.757, 0.586]],
            -5.768640888031248,
            [[-0.0, -0.0, -0.005383236], [-0.0, -0.006387247, 0.002691618], [0.0, 0.006387247, 0.002691618]]),
    "CH4": ([6, 1, 1, 1, 1], [[0, 0, 0], [0.63, 0.63, 0.63], [-0.63, -0.63, 0.63], [-0.63, 0.63, -0.63],
                              [0.63, -0.63, -0.63]],
            -4.274205069524736,
            [[-0.0, -0.0, -0.0], [0.003694745, 0.003694745, 0.003694745], [-0.003694745, -0.003694745, 0.003694745],
             [-0.003694745, 0.003694745, -0.003694745], [0.003694745, -0.003694745, -0.003694745]]),
    "HCl": ([17, 1], [[0, 0, 0], [0, 0, 1.27]],
            -4.786518178484149,
            [[0.0, 0.0, -0.005202986], [-0.0, -0.0, 0.005202986]]),
    "C2H4": ([6, 6, 1, 1, 1, 1], [[0, 0, 0.67], [0, 0, -0.67], [0, 0.92, 1.24], [0, -0.92, 1.24], [0, 0.92, -1.24],
                                  [0, -0.92, -1.24]],
             -6.341511183695392,
             [[-0.0, 0.0, 0.046062386], [0.0, -0.0, -0.046062386], [-0.0, 0.001226239, -0.000262375],
              [0.0, -0.001226239, -0.000262375], [0.0, 0.001226239, 0.000262375], [-0.0, -0.001226239, 0.000262375]]),
    "PH3Cl": ([15, 1, 1, 1, 17], [[0, 0, 0], [1.4, 0, 0.3], [-0.7, 1.2, 0.3], [-0.7, -1.2, 0.3], [0, 0, -2.1]],
              -8.419186419761491,
              [[-0.003751314, -0.0, -0.113506871], [0.060243968, 0.0, 0.008145411],
               [-0.028118778, 0.046681174, 0.006904246], [-0.028118778, -0.046681174, 0.006904246],
               [-0.000255098, 0.0, 0.091552968]]),
    "H2O_dimer": ([8, 1, 1, 8, 1, 1], [[0, 0, 0], [0.96, 0, 0], [-0.24, 0.93, 0], [2.9, 0, 0], [3.2, 0.5, 0.75],
                                       [3.2, 0.5, -0.75]],
                  -11.542930708938204,
                  [[0.010752704, -0.011804065, 0.0], [-0.012598761, 0.009107512, 0.0], [0.003603152, 0.004580964, -0.0],
                   [0.004310891, 0.004322426, -0.0], [-0.003033993, -0.003103418, -0.018534543],
                   [-0.003033993, -0.003103418, 0.018534543]]),
    "stretched_H2O": ([8, 1, 1], [[0, 0, 0], [0, 1.2, 0.9], [0, -0.757, 0.586]],
                      -5.669546279767809,
                      [[0.0, -0.184019312, -0.116909286], [0.0, 0.182204304, 0.129559427],
                       [-0.0, 0.001815008, -0.01265014]]),
}


class ReferenceTest(unittest.TestCase):
    def test_energies_and_gradients_match_dxtb(self):
        for name, (numbers, positions, energy, gradient) in DXTB.items():
            with self.subTest(name):
                result = xtb.Calculator(numbers).compute(np.array(positions, float))
                self.assertTrue(result.converged)
                self.assertAlmostEqual(result.energy, energy, delta=3e-7)
                np.testing.assert_allclose(result.gradient, np.array(gradient), atol=1e-6)

    def test_gradient_is_the_derivative_of_the_energy(self):
        numbers, positions, _, _ = DXTB["stretched_H2O"]
        positions = np.array(positions, float)
        calc = xtb.Calculator(numbers)
        gradient = calc.compute(positions).gradient
        step = 1e-4
        for atom, axis in ((0, 1), (1, 2), (2, 1)):
            plus, minus = positions.copy(), positions.copy()
            plus[atom, axis] += step
            minus[atom, axis] -= step
            numeric = (calc.compute(plus, gradient=False).energy
                       - calc.compute(minus, gradient=False).energy) / (2 * step)
            self.assertAlmostEqual(gradient[atom, axis], numeric, delta=2e-6)

    def test_parts_add_up(self):
        numbers, positions, _, _ = DXTB["H2O_dimer"]
        result = xtb.Calculator(numbers).compute(np.array(positions, float))
        np.testing.assert_allclose(sum(result.gradient_parts.values()), result.gradient, atol=1e-12)
        self.assertAlmostEqual(result.energy, result.electronic + result.repulsion + result.dispersion
                               + result.halogen, places=12)

    def test_forces_split_exactly_into_pairs(self):
        # Chaque terme est une somme sur les paires : la somme sur B redonne la force sur A, et [B, A] = −[A, B].
        for name in ("H2O_dimer", "PH3Cl", "C2H4"):
            numbers, positions, _, _ = DXTB[name]
            result = xtb.Calculator(numbers).compute(np.array(positions, float), pairs=True)
            for term, pairs in result.pair_gradient.items():
                np.testing.assert_allclose(pairs.sum(axis=1), result.gradient_parts[term], atol=1e-12)
                np.testing.assert_allclose(pairs, -pairs.transpose(1, 0, 2), atol=1e-12)

    def test_a_stretched_bond_pulls_and_a_squeezed_one_pushes(self):
        _, numbers, positions = structures.preset(2)
        calc, _, p = relax(numbers, positions)

        def pull(scale):
            q = p.copy()
            q[1] = p[0] + (p[1] - p[0]) * scale
            g = calc.compute(q, pairs=True).pair_gradient
            axis = (q[1] - q[0]) / np.linalg.norm(q[1] - q[0])
            return xtb.HARTREE_EV * sum(float(-part[0, 1] @ axis) for part in g.values())      # eV/Å

        self.assertGreater(pull(1.15), pull(1.0) + 2)       # étirée : la liaison C–H tire plus fort
        self.assertLess(pull(0.9), 0)                        # comprimée : les noyaux se repoussent

    def test_a_runaway_anion_is_held_at_the_limit(self):
        # CH₄ plan carré comprimé : sans contrainte, le carbone prend des électrons sans fin (le terme Γq³/3 n'est pas
        # borné ; dxtb aussi). La contrainte le tient à −2,5 e exactement, et le résultat le dit.
        positions = np.array([[0, 0, 0], [1.07, 0, 0], [-1.07, 0, 0], [0, -0.811, 0.698], [0, 0.811, -0.698]])
        result = xtb.Calculator([6, 1, 1, 1, 1]).compute(positions)
        self.assertTrue(result.converged)
        self.assertFalse(result.unstable)
        self.assertEqual(list(result.confined), [0])
        self.assertAlmostEqual(result.atom_charges[0], -xtb.ANION_LIMIT, places=6)

    def test_the_constraint_keeps_the_forces_exact(self):
        # Ba₂Si : Si tenu à −2,5 e (sans contrainte : −4,8 e, et Ba +2,4 e, plus d'électrons perdus qu'il n'en a). Les
        # forces suivent la contrainte : ce sont les différences finies de l'énergie.
        positions = np.array([[0, 0, 0], [0.25, 0, 2.9], [-0.1, 0.2, -2.85]])
        result = xtb.Calculator([14, 56, 56]).compute(positions)
        self.assertEqual(list(result.confined), [0])
        self.assertAlmostEqual(result.atom_charges[0], -xtb.ANION_LIMIT, places=6)
        step = 1e-4
        for atom, axis in ((0, 2), (1, 0), (2, 1)):
            plus, minus = positions.copy(), positions.copy()
            plus[atom, axis] += step
            minus[atom, axis] -= step
            numeric = (xtb.Calculator([14, 56, 56]).energy(plus) - xtb.Calculator([14, 56, 56]).energy(minus)) / (2 * step)
            self.assertAlmostEqual(result.gradient[atom, axis], numeric, places=5)

    def test_real_cations_are_not_capped(self):
        # W porte +3,1 e dans WF₆ (dxtb aussi) : une vraie charge, ni contrainte ni emballement.
        positions = np.vstack([[0, 0, 0], 1.83 * np.vstack([np.eye(3), -np.eye(3)])])
        result = xtb.Calculator([74] + [9] * 6).compute(positions)
        self.assertGreater(result.atom_charges[0], 3.0)
        self.assertFalse(result.unstable)
        self.assertEqual(len(result.confined), 0)

    def test_a_breaking_bond_converges_at_300_k(self):
        # O₄ à 4 000 K, liaisons en train de casser : Broyden oscille (deux niveaux presque dégénérés au niveau de
        # Fermi échangent leurs électrons) ; Newton amorti converge, à 300 K, sur la solution presque neutre.
        positions = np.array([[0.5207, 0.7168, -1.0487], [1.4087, -0.2233, -0.3744], [-0.9071, -0.6174, -0.1365],
                              [-1.0224, 0.1238, 1.5596]])
        result = xtb.Calculator([8] * 4).compute(positions)
        self.assertTrue(result.converged)
        self.assertGreater(result.iterations, xtb.SCC_BROYDEN[1])           # Broyden seul n'y arrive pas
        self.assertEqual(result.electronic_temperature, xtb.ELECTRONIC_TEMPERATURE)
        self.assertLess(np.max(np.abs(result.atom_charges)), 0.1)

    def test_separated_open_shells_fall_back_on_annealing(self):
        # Pm₄ dissocié (un atome à plus de 7 Å des autres) : à 300 K, pas de point fixe atteint ; le recuit converge
        # à 3 000 K et le résultat le dit — plutôt que les −9 e de la fonctionnelle seule.
        positions = np.array([[0.1264, -0.2402, -0.2081], [3.1379, -3.236, 5.9361], [-2.4075, -1.5968, 0.3412],
                              [-0.0484, 3.0549, 0.3459]])
        result = xtb.Calculator([61] * 4).compute(positions)
        self.assertTrue(result.converged)
        self.assertFalse(result.unstable)
        self.assertGreater(result.electronic_temperature, xtb.ELECTRONIC_TEMPERATURE)
        self.assertLess(np.max(np.abs(result.atom_charges)), 0.5)

    def test_many_separated_open_shell_atoms_stay_cheap(self):
        # Des atomes H, O, N épars : un même niveau de Fermi, un rien déplace un électron d'un atome à l'autre. Sans
        # budget ni recuit doux, un seul calcul coûtait jusqu'à 22 000 diagonalisations (50 s) et le laboratoire se
        # figeait dès 14 atomes. Le palier trouvé sert aux pas suivants, en quelques dizaines de tours.
        rng = np.random.default_rng(1)
        numbers = [1, 8, 1, 8, 1, 8, 1, 8, 1, 8, 8, 1, 7, 1, 8, 7, 1, 8, 1, 7]
        placed = []
        for _ in numbers:
            for _ in range(200):
                p = rng.uniform(-6, 6, 3)
                if all(np.linalg.norm(p - q) > 2.2 for q in placed):
                    break
            placed.append(p)
        positions = np.array(placed)
        calc = xtb.Calculator(numbers)
        original, calls = xtb.eigh, [0]

        def counted(*args, **kwargs):
            calls[0] += 1
            return original(*args, **kwargs)

        xtb.eigh = counted
        try:
            first = calc.compute(positions)
            self.assertLessEqual(calls[0], 2 * xtb.SCC_EFFORT + 20)
            self.assertTrue(first.converged)
            self.assertLess(np.max(np.abs(first.atom_charges)), 1.0)
            calls[0] = 0
            second = calc.compute(positions + 0.002)
            self.assertTrue(second.converged)
            self.assertLess(calls[0], 150)
        finally:
            xtb.eigh = original

    def test_a_broken_bond_shares_nothing(self):
        # Rompue, une liaison garde un indice de Mayer de 1 dans un calcul à couches fermées : ses deux électrons
        # restent appariés d'un fragment à l'autre. Ce que les atomes partagent entre eux, lui, s'éteint.
        bonded = xtb.Calculator([8, 8]).compute(np.array([[0, 0, 0], [0, 0, 1.21]]))
        apart = xtb.Calculator([8, 8]).compute(np.array([[0, 0, 0], [0, 0, 3.0]]))
        orders = analysis.mayer_bond_orders(apart)
        self.assertGreater(orders[0, 1], 1.0)
        self.assertAlmostEqual(analysis.shared_density(bonded, [(0, 1)])[(0, 1)], 0.95, delta=0.05)
        self.assertLess(analysis.shared_density(apart, [(0, 1)])[(0, 1)], 0.01)
        self.assertEqual([formula for _, formula in analysis.fragments([8, 8], orders, apart)], ["O", "O"])
        # Les liaisons diffuses des alcalins en restent : leur atome libre, la référence, est diffus aussi.
        sodium = xtb.Calculator([11, 11]).compute(np.array([[0, 0, 0], [0, 0, 3.08]]))
        self.assertGreater(analysis.shared_density(sodium, [(0, 1)])[(0, 1)], 1.0)

    def test_an_orbital_says_what_it_is_made_of(self):
        # La HOMO de l'eau : la paire libre perpendiculaire au plan, O 2p à 100 % ; la π* de l'éthylène : les 2p des
        # deux carbones, moitié-moitié. Les parts sont rangées de la plus grande à la plus petite et font au plus 1.
        _, numbers, positions = structures.preset(1)
        water = xtb.Calculator(numbers).compute(np.array(positions, float), gradient=False)
        homo = int(np.where(water.occupations > 0.5)[0][-1])
        parts = density.orbital_parts(water, homo)
        self.assertEqual((parts[0][0], parts[0][1]), (0, "2p"))
        self.assertAlmostEqual(parts[0][2], 1.0, delta=0.01)
        _, numbers, positions = structures.preset(8)
        ethylene = xtb.Calculator(numbers).compute(np.array(positions, float), gradient=False)
        homo = int(np.where(ethylene.occupations > 0.5)[0][-1])
        parts = density.orbital_parts(ethylene, homo + 1)
        self.assertEqual(sorted((a, shell) for a, shell, _ in parts), [(0, "2p"), (1, "2p")])
        for _, _, share in parts:
            self.assertAlmostEqual(share, 0.5, delta=0.02)
        shares = [share for _, _, share in density.orbital_parts(ethylene, homo - 1)]
        self.assertEqual(shares, sorted(shares, reverse=True))
        self.assertLessEqual(sum(shares), 1.0 + 1e-9)

    def test_an_orbital_grid_reaches_further(self):
        # Une orbitale vide peut être diffuse (la LUMO de l'eau, sur les 2s des H) : sa boîte va plus loin.
        _, numbers, positions = structures.preset(1)
        result = xtb.Calculator(numbers).compute(np.array(positions, float), gradient=False)
        _, deformation_spacing, deformation_counts, _ = density.compute(result, 1, 0, 30)
        _, orbital_spacing, orbital_counts, _ = density.compute(result, 2, 4, 30)
        deformation_span = deformation_spacing * (np.array(deformation_counts) - 1)
        orbital_span = orbital_spacing * (np.array(orbital_counts) - 1)
        self.assertGreater(orbital_span.min(), deformation_span.min() + 2 * (density.ORBITAL_MARGIN - density.MARGIN) - 0.5)


class ReadingTest(unittest.TestCase):
    def test_density_holds_every_valence_electron(self):
        _, numbers, positions = structures.preset(1)
        result = xtb.Calculator(numbers).compute(positions)
        origin, spacing, counts, values = density.compute(result, 0)
        self.assertAlmostEqual(float(values.sum()) * spacing ** 3, 8.0, delta=0.02)
        _, _, _, deformation = density.compute(result, 1)
        self.assertAlmostEqual(float(deformation.sum()) * spacing ** 3, 0.0, delta=0.02)
        raw, peak = density.encode(values, signed=False)
        back = density.decode(raw, peak, signed=False).reshape(values.shape)
        self.assertLess(float(np.max(np.abs(back - values))), 1e-3 * peak)

    def test_density_splits_into_atoms_and_bonds(self):
        _, numbers, positions = structures.preset(1)
        result = xtb.Calculator(numbers).compute(positions)
        _, spacing, _, atoms, bonds = density.compute_split(result)
        total = density.compute(result, 0)[3]
        np.testing.assert_allclose(atoms + bonds, total, atol=1e-4)
        self.assertGreater(float(bonds.max()), 0.5)                          # le recouvrement liant
        self.assertAlmostEqual(float(bonds.sum()) * spacing ** 3, 1.26, delta=0.1)   # ≈ population de recouvrement

    def test_bond_orders_count_shared_pairs(self):
        cases = {"H₂ · dihydrogène": 1.0, "N₂ · diazote": 3.0, "C₂H₄ · éthylène": 2.0}
        labels = [name for name, _ in structures.PRESETS]
        for label, expected in cases.items():
            _, numbers, positions = structures.preset(labels.index(label))
            calc, _, positions = relax(numbers, positions)
            orders = analysis.mayer_bond_orders(calc.compute(positions))
            self.assertAlmostEqual(orders[0, 1], expected, delta=0.25, msg=label)

    def test_water_geometry_and_vibrations(self):
        _, numbers, positions = structures.preset(1)
        calc, _, p = relax(numbers, positions)
        self.assertAlmostEqual(np.linalg.norm(p[1] - p[0]), 0.958, delta=0.02)
        u, v = p[1] - p[0], p[2] - p[0]
        self.assertAlmostEqual(np.degrees(np.arccos(u @ v / np.linalg.norm(u) / np.linalg.norm(v))), 104.5, delta=4)
        masses = np.array([analysis.mass(z) for z in numbers])
        frequencies = sorted(analysis.normal_modes(calc, p, masses)[0])
        # GFN1-xTB : 1438, 3675, 3787 cm⁻¹ (la flexion 13 % trop basse, les élongations 4 %).
        for computed, measured in zip(frequencies, (1649, 3832, 3943)):
            self.assertAlmostEqual(computed / measured, 1.0, delta=0.15)

    def test_sigma_and_pi_parts_of_the_bonds(self):
        # La théorie des orbitales moléculaires : N₂ σ + 2π, C₂H₄ σ + π, C₂H₂ σ + 2π, CH₄ σ seul.
        labels = [name for name, _ in structures.PRESETS]
        for label, (sigma, pi) in {"N₂ · diazote": (1, 2), "C₂H₄ · éthylène": (1, 1), "C₂H₂ · acétylène": (1, 2),
                                   "CH₄ · méthane": (1, 0)}.items():
            _, numbers, positions = structures.preset(labels.index(label))
            calc, _, p = relax(numbers, positions)
            result = calc.compute(p)
            s, pi_part = analysis.bond_symmetry(result, 0, 1)
            self.assertAlmostEqual(s, sigma, delta=0.1, msg=label)
            self.assertAlmostEqual(pi_part, pi, delta=0.1, msg=label)
            self.assertAlmostEqual(s + pi_part, analysis.mayer_bond_orders(result)[0, 1], places=8)

    def test_stored_validation_is_what_the_engine_computes(self):
        from moleculelab import validation
        self.assertEqual([list(row) for row in validation.stored()], [list(row) for row in validation.facts()])

    def test_atomic_dipoles_give_the_dipole_of_the_density(self):
        # Σ q_A R_A + Σ d_A doit égaler le dipôle du nuage intégré sur une grille fine (intégrales de moment exactes).
        _, numbers, positions = structures.preset(1)
        calc, _, p = relax(numbers, positions)
        result = calc.compute(p)
        analytic = analysis.dipole(result)
        origin, spacing, counts, rho = density.compute(result, 0, 0, 90)
        axes = [origin[k] + spacing * np.arange(counts[k]) for k in range(3)]
        grid = np.meshgrid(*axes, indexing="ij")
        nuclei = result.positions * xtb.BOHR_ANGSTROM
        center = nuclei.mean(axis=0)
        electrons = -np.array([((grid[k] - center[k]) * rho).sum() for k in range(3)]) * spacing ** 3
        valence = np.bincount(result.basis.shell_atom, weights=result.basis.refocc, minlength=len(numbers))
        numeric = analysis.DEBYE_PER_E_ANGSTROM * (electrons + valence @ (nuclei - center))
        np.testing.assert_allclose(analytic, numeric, atol=0.01)
        # Le nuage de l'oxygène est poussé vers ses paires libres, à l'opposé des hydrogènes.
        d_oxygen = analysis.atomic_dipoles(result)[0]
        towards_h = (p[1] + p[2]) / 2 - p[0]
        self.assertGreater(float(d_oxygen @ towards_h), 0)      # d pointe vers les H : les électrons vont à l'opposé

    def test_localized_pairs_share_the_bonds(self):
        # Pipek–Mezey : une rotation des orbitales occupées, donc le dipôle des paires est celui de la densité ;
        # les paires libres et la répartition des liaisons polaires sont celles qu'attend un chimiste.
        labels = [name for name, _ in structures.PRESETS]
        cases = {"H₂O · eau": (2, 0, 0.6), "HCl · chlorure d'hydrogène": (3, 0, 0.55), "NH₃ · ammoniac": (1, 0, 0.55),
                 "H₂ · dihydrogène": (0, 0, 0.5)}
        for label, (lone_pairs, heavy, share) in cases.items():
            _, numbers, positions = structures.preset(labels.index(label))
            calc, _, p = relax(numbers, positions)
            result = calc.compute(p)
            populations, centroids = analysis.localize(result)
            nuclei = result.positions * xtb.BOHR_ANGSTROM
            valence = np.bincount(result.basis.shell_atom, weights=result.basis.refocc, minlength=len(numbers))
            from_pairs = analysis.DEBYE_PER_E_ANGSTROM * (valence @ nuclei - 2 * centroids.sum(axis=0))
            np.testing.assert_allclose(from_pairs, analysis.dipole(result), atol=1e-6)
            shares, lone, _ = analysis.electron_pairs(result, [(heavy, 1)])
            self.assertEqual(int(lone[heavy]), lone_pairs, label)
            self.assertGreaterEqual(1 - shares[(heavy, 1)], share - 1e-9, label)       # la part gardée par l'atome lourd

    def test_pairs_tell_two_centre_multicentre_and_ionic_bonds(self):
        # Une liaison tient par une paire sur deux atomes (C–H, B–H terminal), par une paire étalée sur trois atomes ou
        # plus (les électrons s de Au₆ : trois paires, chacune sur un triangle — l'image des analyses AdNDP ; le pont
        # B–H–B du diborane, la liaison à trois centres de Lipscomb), ou par l'attraction de deux ions (NaCl).
        labels = [name for name, _ in structures.PRESETS]
        _, numbers, positions = structures.preset(labels.index("Au₆ · agrégat d'or"))
        calc, _, p = relax(numbers, positions)
        result = calc.compute(p)
        orders = analysis.mayer_bond_orders(result)
        bonds = [(a, b) for a in range(6) for b in range(a + 1, 6) if orders[a, b] >= 0.35]
        shares, _, shared = analysis.electron_pairs(result, bonds)
        self.assertEqual(len(bonds), 9)
        self.assertEqual(shares, {})                             # aucune paire sur un seul couple d'atomes
        self.assertEqual(len(shared), 3)
        corners = {int(k) for k in np.argsort(result.cn)[:3]}    # les sommets : la plus faible coordination
        for atoms, populations, _ in shared:
            self.assertEqual(len(atoms), 3)
            self.assertEqual(len(corners & set(atoms)), 1)        # un sommet et ses deux voisins
            self.assertGreater(sum(populations), 0.9)
        self.assertTrue(all(any(a in atoms and b in atoms for atoms, _, _ in shared) for a, b in bonds))

        diborane = [5, 5, 1, 1, 1, 1, 1, 1]
        calc, _, p = relax(diborane, [[0, 0, 0.88], [0, 0, -0.88], [0, 0.97, 0], [0, -0.97, 0], [1.04, 0, 1.47],
                                      [-1.04, 0, 1.47], [1.04, 0, -1.47], [-1.04, 0, -1.47]])
        result = calc.compute(p)
        orders = analysis.mayer_bond_orders(result)
        bonds = [(a, b) for a in range(8) for b in range(a + 1, 8) if orders[a, b] >= 0.35]
        shares, _, shared = analysis.electron_pairs(result, bonds)
        self.assertEqual(sorted(shares), [(0, 4), (0, 5), (1, 6), (1, 7)])          # les B–H terminaux
        self.assertEqual(sorted(sorted(atoms) for atoms, _, _ in shared), [[0, 1, 2], [0, 1, 3]])
        for atoms, populations, _ in shared:
            self.assertEqual(diborane[atoms[0]], 1)               # la paire est centrée sur l'hydrogène du pont
            self.assertAlmostEqual(populations[1], populations[2], delta=0.02)

        calc, _, p = relax([11, 17], [[0, 0, 0], [0, 0, 2.36]])
        result = calc.compute(p)
        self.assertGreater(analysis.mayer_bond_orders(result)[0, 1], 0.35)
        shares, lone, shared = analysis.electron_pairs(result, [(0, 1)])
        self.assertEqual((shares, shared), ({}, []))              # deux ions : aucune paire partagée
        self.assertEqual(int(lone[1]), 4)                         # Cl⁻ : quatre paires libres

    def test_pi_lobes_follow_the_pi_density(self):
        # La part π se tient de part et d'autre de la liaison, le long des axes propres de la matrice de Mayer π :
        # l'éthylène, le long de la normale à son plan ; N₂, deux π perpendiculaires.
        labels = [name for name, _ in structures.PRESETS]
        _, numbers, positions = structures.preset(labels.index("C₂H₄ · éthylène"))
        calc, _, p = relax(numbers, positions)
        result = calc.compute(p)
        sigma, pi, axis, split = analysis.bond_symmetry(result, 0, 1, directions=True)
        self.assertAlmostEqual(sum(split), pi, places=10)
        self.assertAlmostEqual(split[0], 1.0, delta=0.03)
        self.assertLess(split[1], 0.05)
        normal = np.linalg.svd(p - p.mean(axis=0))[2][-1]
        self.assertGreater(abs(float(axis @ normal)), 0.999)
        self.assertAlmostEqual(float(axis @ (p[1] - p[0])), 0.0, places=8)       # perpendiculaire à la liaison
        _, numbers, positions = structures.preset(labels.index("N₂ · diazote"))
        calc, _, p = relax(numbers, positions)
        _, pi, _, split = analysis.bond_symmetry(calc.compute(p), 0, 1, directions=True)
        np.testing.assert_allclose(split, [1.0, 1.0], atol=0.02)

    def test_pair_profiles_place_the_bond_electrons(self):
        # La densité des paires de la liaison le long de l'axe : symétrique dans H₂, plus forte du côté de O dans O–H.
        labels = [name for name, _ in structures.PRESETS]
        for label, a, b in (("H₂ · dihydrogène", 0, 1), ("H₂O · eau", 0, 1)):
            _, numbers, positions = structures.preset(labels.index(label))
            calc, _, p = relax(numbers, positions)
            result = calc.compute(p)
            populations, centroids, orbitals = analysis.localize(result, orbitals=True)
            members = analysis.classify_pairs(populations, centroids, [])[3]
            profile = density.pair_profiles(result, orbitals, members)[(a, b)]
            self.assertAlmostEqual(float(profile.sum()), 1.0, places=5)
            half = len(profile) // 2
            if numbers[a] == numbers[b]:
                np.testing.assert_allclose(profile, profile[::-1], atol=2e-3)
            else:
                self.assertLess(int(np.argmax(profile)), half)                          # le maximum côté O
                self.assertGreater(profile[:half].sum(), 1.1 * profile[half:].sum())    # 54 % côté O, 46 % côté H

    def test_free_atom_sizes(self):
        # La surface à 0,002 e/bohr³ de l'atome libre (GFN1), proche des rayons de van der Waals de Bondi ; le maximum de
        # l'orbitale p de valence le long de son axe.
        for z, surface, bondi in ((1, 1.24, 1.20), (6, 1.48, 1.70), (8, 1.40, 1.52), (17, 1.71, 1.75)):
            computed, p = density.atom_radii(z)
            self.assertAlmostEqual(computed, surface, delta=0.01)
            self.assertLess(abs(computed - bondi), 0.25)
        self.assertEqual(density.atom_radii(1)[1], 0.0)
        self.assertAlmostEqual(density.atom_radii(6)[1], 0.29, delta=0.01)

    def test_gold_six_is_a_planar_triangle(self):
        # Le minimum de Au₆ est le triangle plan D3h (DFT, spectres infrarouges) ; GFN1-xTB le place 0,60 eV sous la
        # pyramide pentagonale, l'isomère en trois dimensions le plus bas.
        labels = [name for name, _ in structures.PRESETS]
        _, numbers, positions = structures.preset(labels.index("Au₆ · agrégat d'or"))
        _, triangle, p = relax(numbers, positions)
        distances = np.sort(np.linalg.norm(p[:, None] - p[None], axis=-1)[np.triu_indices(6, 1)])
        np.testing.assert_allclose(distances[:6], 2.631, atol=0.01)
        np.testing.assert_allclose(distances[6:9], 2.823, atol=0.01)
        side = 2.70 / (2 * np.sin(np.pi / 5))
        pyramid = [[side * np.cos(2 * np.pi * k / 5), side * np.sin(2 * np.pi * k / 5), 0] for k in range(5)]
        _, apex, _ = relax(numbers, pyramid + [[0, 0, 2.2]])
        self.assertAlmostEqual((apex - triangle) * xtb.HARTREE_EV, 0.604, delta=0.02)

    def test_potential_is_the_coulomb_potential_of_nuclei_and_cloud(self):
        _, numbers, positions = structures.preset(1)
        calc, _, p = relax(numbers, positions)
        result = calc.compute(p)
        origin, spacing, counts, atoms, overlap = density.compute_split(result, 40)
        total = atoms + overlap
        valence = np.bincount(result.basis.shell_atom, weights=result.basis.refocc, minlength=len(numbers))
        nuclei = result.positions * xtb.BOHR_ANGSTROM
        phi = density.electrostatic_potential(total, origin, spacing, nuclei, valence)
        axes = [origin[k] + spacing * np.arange(counts[k]) for k in range(3)]
        grid = np.meshgrid(*axes, indexing="ij")
        shape = np.array(total.shape)
        for i, j, k in (tuple(shape // 9), tuple(shape // 2), (shape[0] - 3, 4, shape[2] * 3 // 4)):
            point = np.array([axes[0][i], axes[1][j], axes[2][k]])
            distance = np.sqrt(sum((grid[c] - point[c]) ** 2 for c in range(3)))
            distance[i, j, k] = np.inf
            cloud = (total * spacing ** 3 / distance).sum() + total[i, j, k] * spacing ** 2 * density.CUBE_SELF
            core = sum(z / np.linalg.norm(point - r) for z, r in zip(valence, nuclei))
            self.assertAlmostEqual(phi[i, j, k], density.COULOMB_VOLT_ANGSTROM * (core - cloud), delta=1e-6)
        # La carte conventionnelle (ρ = 0,002 u.a.) : négative du côté de l'oxygène, positive du côté des H.
        shell = np.abs(total - 0.002 / xtb.BOHR_ANGSTROM ** 3) < 0.15 * 0.002 / xtb.BOHR_ANGSTROM ** 3
        self.assertLess(phi[shell].min(), -2.0)
        self.assertGreater(phi[shell].max(), 2.0)

    def test_projected_clouds_hold_the_valence_electrons(self):
        # ∫ 2π d P⁰(d) dd = N⁰ : vu en transparence, le nuage de l'atome libre garde tous ses électrons de valence.
        for z in (1, 6, 8, 17, 26, 55):
            electrons, step, projected = density.projected_atom(z)
            d = np.arange(len(projected)) * step
            self.assertAlmostEqual(float(np.trapezoid(2 * np.pi * d * projected, d)), electrons, delta=0.01 * electrons)

    def test_the_halo_follows_the_computed_column_density(self):
        # Le halo du client : la somme des nuages des atomes, (N⁰ − q_A) / N⁰ · P⁰. Son contour σ = 0,3 e/Å² recouvre
        # celui de la densité calculée (ρ = Σ P_μν φ_μ φ_ν), projetée le long d'un axe : eau, HCl, formaldéhyde.
        for index in (1, 7, 10):
            _, numbers, positions = structures.preset(index)
            result = xtb.Calculator(numbers).compute(positions)
            origin, spacing, counts, values = density.compute(result, 0)
            column = values.sum(axis=0) * spacing
            ys = origin[1] + spacing * np.arange(counts[1])
            zs = origin[2] + spacing * np.arange(counts[2])
            y, z = np.meshgrid(ys, zs, indexing="ij")
            model = np.zeros_like(column)
            nuclei = result.positions * xtb.BOHR_ANGSTROM
            for atom, number in enumerate(numbers):
                electrons, step, projected = density.projected_atom(int(number))
                distance = np.hypot(y - nuclei[atom, 1], z - nuclei[atom, 2])
                cloud = np.interp(distance, step * np.arange(len(projected)), projected, right=0.0)
                model += (electrons - result.atom_charges[atom]) / electrons * cloud
            computed, drawn = column >= 0.3, model >= 0.3
            self.assertGreater((computed & drawn).sum() / (computed | drawn).sum(), 0.93, structures.PRESETS[index][0])

    def test_polarity(self):
        _, numbers, positions = structures.preset(7)        # HCl
        result = xtb.Calculator(numbers).compute(positions)
        self.assertLess(result.atom_charges[0], -0.1)        # δ− sur le chlore
        self.assertGreater(result.atom_charges[1], 0.1)
        self.assertAlmostEqual(float(result.atom_charges.sum()), 0.0, places=8)

    def test_census_names_species(self):
        groups = [([0, 1, 2], "H2O"), ([3, 4, 5], "H2O"), ([6, 7], "O2")]
        self.assertEqual(analysis.census(groups), "2 H₂O + O₂")
        self.assertEqual(structures.hill_formula([8, 1, 1]), "H2O")
        self.assertEqual(structures.hill_formula([1, 6, 8, 1, 1, 1]), "CH4O")
        self.assertIn(structures.hill_formula([17, 1]), structures.NAMES)


def wait(lab, condition, limit=90.0):
    start = time.time()
    while time.time() - start < limit:
        snap, grid = lab.snapshot()
        if snap is not None and condition(snap, grid):
            return snap, grid
        time.sleep(0.05)
    raise AssertionError("le laboratoire n'a pas atteint l'état attendu")


class LabTest(unittest.TestCase):
    def test_methane_builds_itself_tetrahedral(self):
        lab = MoleculeLab(rng_seed=3)
        try:
            lab.clear()
            lab.add_atom(6)
            for _ in range(4):
                lab.add_atom(1, 0)
            snap, _ = wait(lab, lambda s, g: s["label"] == "CH₄ · méthane" and s["relaxed"])
            p = snap["positions"]
            for i in range(1, 5):
                for j in range(i + 1, 5):
                    u, v = p[i] - p[0], p[j] - p[0]
                    angle = np.degrees(np.arccos(u @ v / np.linalg.norm(u) / np.linalg.norm(v)))
                    self.assertAlmostEqual(angle, 109.47, delta=1.0)
            self.assertEqual(snap["census"], "CH₄")
        finally:
            lab.close()

    def test_pulling_breaks_the_bond(self):
        lab = MoleculeLab(rng_seed=4)
        try:
            lab.load_preset(0)
            wait(lab, lambda s, g: s["label"].startswith("H₂") and s["relaxed"])
            lab.drag_atom(1, [0.0, 0.0, 5.0])
            snap, _ = wait(lab, lambda s, g: s["census"] == "2 H")
            # Rompue : plus rien de partagé entre les noyaux (ce que suivent le recensement et le dessin). L'indice de
            # Mayer, lui, reste près de 1 jusqu'à 3,2 Å dans ce modèle à couches fermées : il ne dit pas la rupture.
            self.assertTrue(all(bond[10] < analysis.SHARED_BOND for bond in snap["bonds"]))   # […, partage]
        finally:
            lab.close()

    def test_frame_carries_the_lab(self):
        session = Session(ElementRepository(), seed=5)
        session.start_molecule_lab()
        try:
            wait(session.molecule_lab, lambda s, g: s["relaxed"] and "energy" in s)
            message = engine.genesis_pb2.MoleculeLabState()
            sent = {}
            molecule_frame.fill(message, session.molecule_lab, sent)
            self.assertTrue(message.active and message.ready)
            self.assertEqual(len(message.atoms), 3)
            self.assertEqual(len(message.presets), len(structures.PRESETS))
            self.assertEqual(len(message.grid.data), 0)        # la vue de la densité n'a pas besoin de grille
            self.assertEqual(sorted(c.z for c in message.clouds), [1, 8])
            # La carte de potentiel en demande une : densité et potentiel, sur la même grille.
            session.molecule_lab.set_view(0, -1, True)
            wait(session.molecule_lab, lambda s, g: g is not None)
            gridded = engine.genesis_pb2.MoleculeLabState()
            molecule_frame.fill(gridded, session.molecule_lab, sent)
            grid = gridded.grid
            self.assertEqual(len(grid.data), 2 * int(np.prod(list(grid.counts))))
            self.assertEqual(len(grid.esp), len(grid.data))
            again = engine.genesis_pb2.MoleculeLabState()
            molecule_frame.fill(again, session.molecule_lab, sent)
            self.assertEqual(len(again.presets), 0)          # le catalogue n'est envoyé qu'une fois
            self.assertEqual(len(again.grid.data), 0)        # la grille non plus, tant qu'elle ne change pas
            self.assertEqual(len(again.clouds), 0)           # ni le nuage d'un élément déjà envoyé
            session.molecule_lab.set_view(0, -1, False)
            self.assertEqual(len(message.multicentres), 0)   # l'eau : des paires sur deux atomes seulement
            for bond in (b for b in message.bonds if b.order >= 0.35):
                self.assertEqual(len(bond.profile), density.PROFILE_SAMPLES)
                self.assertAlmostEqual(sum(bond.profile), 1.0, places=4)
                self.assertAlmostEqual(bond.shared, 1.0, delta=0.1)           # O–H : une liaison ordinaire
            self.assertEqual(message.electronic_temperature, 300.0)
            self.assertEqual(len(message.confined), 0)
            self.assertTrue(all(c.surface > 1.0 for c in message.clouds))
            self.assertEqual([a.mass_number for a in message.atoms], [0, 0, 0])           # le mélange naturel
            hydrogen = next(c for c in message.clouds if c.z == 1)
            self.assertEqual([i.mass_number for i in hydrogen.isotopes], [1, 2, 3])
            labels = [name for name, _ in structures.PRESETS]
            session.molecule_lab.load_preset(labels.index("Au₆ · agrégat d'or"))
            wait(session.molecule_lab, lambda s, g: s["label"].startswith("Au₆") and s["relaxed"] and "energy" in s)
            gold = engine.genesis_pb2.MoleculeLabState()
            molecule_frame.fill(gold, session.molecule_lab, sent)
            self.assertEqual([len(m.atoms) for m in gold.multicentres], [3, 3, 3])
            self.assertTrue(all(b.share == -1 for b in gold.bonds if b.order >= 0.35))
        finally:
            session.stop_molecule_lab()

    def test_the_balance_and_the_pairs_add_up(self):
        # Le bilan : chaque terme moins sa valeur dans les atomes isolés ; leur somme est l'énergie de liaison.
        # La carte de l'atome : les forces de ses paires (vecteurs entiers) font sa force, et leur part le long de
        # l'axe est celle que la fiche annonce.
        lab = MoleculeLab(rng_seed=6)
        try:
            snap, _ = wait(lab, lambda s, g: s["relaxed"] and "energy" in s)
            deltas = (snap["band"] - snap["band_atoms"], snap["es2"] + snap["es3"] - snap["coulomb_atoms"],
                      snap["entropy"] - snap["entropy_atoms"], snap["repulsion"], snap["dispersion"], snap["halogen"])
            self.assertAlmostEqual(sum(deltas), snap["binding"], places=9)
            self.assertLess(deltas[0], -15.0)                  # l'eau : les orbitales qui se recouvrent font la liaison
            self.assertGreater(deltas[1], 0.0)                 # ses charges séparées coûtent
            positions = snap["positions"]
            self.assertEqual(len(snap["pairs"]), 3)            # O–H, O–H, H–H : toutes les paires partent
            total = np.zeros_like(positions)
            for a, b, distance, order, electronic, repulsion, dispersion, along, force, overlap, charges, population \
                    in snap["pairs"]:
                total[a] += force
                total[b] -= force
                self.assertAlmostEqual(overlap + charges, electronic, places=9)
                self.assertEqual(population > 0, order > 0.5)          # O–H partagent ; H···H se recouvrent mal
                axis = (positions[b] - positions[a]) / distance
                self.assertAlmostEqual(float(np.dot(force, axis)), along, places=9)    # + : vers l'autre, ils s'attirent
            parts = snap["forces"]
            np.testing.assert_allclose(total, parts["électronique"] + parts["répulsion"] + parts["dispersion"], atol=1e-9)
        finally:
            lab.close()

    def test_isotopes_change_the_vibrations_not_the_geometry(self):
        # Seule la masse change (Born–Oppenheimer) : D₂O vibre à ≈ 0,73 × les fréquences de H₂O (la racine du rapport
        # des masses réduites ; mesuré : 0,74 pour le pliage, 0,73 et 0,74 pour les élongations) ; dans HDO, les deux
        # élongations se découplent en O–D et O–H.
        lab = MoleculeLab(rng_seed=5)
        try:
            lab.close()
            time.sleep(0.3)
            lab._do_preset(1)
            while not lab.relaxed:
                lab._fire_step()
            energy, positions = lab.result.energy, lab.positions.copy()
            lab._normal_modes()
            light = np.array(lab.modes[0])
            lab._do_isotope(-1, 2)                                   # tous les H en ²H
            self.assertEqual(lab.isotopes, [0, 2, 2])
            self.assertAlmostEqual(lab.masses[1], 2.014102, places=5)
            lab._normal_modes()
            heavy = np.array(lab.modes[0])
            np.testing.assert_allclose(heavy / light, 0.73, atol=0.015)
            self.assertAlmostEqual(lab.result.energy, energy, places=10)              # même surface d'énergie
            np.testing.assert_allclose(lab.positions, positions)
            lab._do_isotope(1, 0)                                    # HDO
            lab._normal_modes()
            mixed = np.sort(np.array(lab.modes[0]))
            self.assertAlmostEqual(mixed[1] / heavy[1], 1.0, delta=0.03)   # l'élongation O–D
            self.assertAlmostEqual(mixed[2] / light[2], 1.0, delta=0.03)   # l'élongation O–H
            lab._do_isotope(1, 7)                                    # ⁷H n'est pas proposé : rien ne change
            self.assertEqual(lab.isotopes, [0, 0, 2])
        finally:
            lab.close()

    def test_a_runaway_stops_on_the_last_valid_state(self):
        # Quand le cycle auto-cohérent s'emballe pendant un pas (GFN1 n'est pas borné : Pm₄ dissocié prend des charges
        # de −9 et +3, dxtb aussi), on n'intègre pas ces forces : retour au dernier état valide, arrêt, explication.
        from moleculelab import lab as lab_module
        lab = MoleculeLab(rng_seed=5)
        try:
            lab.close()
            time.sleep(0.3)
            lab._do_preset(1)
            while not lab.relaxed:
                lab._fire_step()
            lab._do_dynamics(lab_module.THERMOSTAT, 300.0)
            lab._step()
            positions, steps, time_fs = lab.positions.copy(), lab.steps, lab.time_fs
            honest = lab.calc.compute

            def runaway(*args, **kwargs):
                result = honest(*args, **kwargs)
                result.unstable = True
                return result

            lab.calc.compute = runaway
            lab._step()
            self.assertTrue(lab.paused)
            self.assertEqual(lab.error, lab_module.RUNAWAY_STOPPED)
            np.testing.assert_array_equal(lab.positions, positions)
            self.assertEqual((lab.steps, lab.time_fs), (steps, time_fs))
            self.assertFalse(lab.result.unstable)
        finally:
            lab.close()

    def test_a_grid_is_published_with_the_view_it_belongs_to(self):
        # La grille d'une vue ne paraît qu'avec l'instantané qui annonce cette vue : avant, un lecteur pouvait prendre
        # la grille ψ avec l'instantané de la vue Δρ, et le client, qui ne dessine que la grille de sa vue, la jetait
        # sans qu'elle soit jamais renvoyée.
        lab = MoleculeLab(rng_seed=5)
        try:
            lab.close()
            time.sleep(0.3)
            lab._do_preset(1)
            lab._do_view(1, -1)
            lab._compute_grid()
            lab._publish()
            self.assertEqual(lab.snapshot()[1]["kind"], 1)
            lab._do_view(2, -1)
            lab._compute_grid()
            snap, grid = lab.snapshot()
            self.assertEqual((snap["view"][0], grid["kind"]), (1, 1))      # pas encore publiée
            lab._publish()
            snap, grid = lab.snapshot()
            self.assertEqual((snap["view"][0], grid["kind"]), (2, 2))
            self.assertTrue(grid["parts"])                                   # de quoi l'orbitale est faite
            # Une autre molécule, d'autres orbitales : la vue ψ revient à la HOMO.
            lab._do_view(2, 7)
            lab._do_preset(0)
            self.assertEqual(lab.view, (2, -1))
        finally:
            lab.close()

    def test_degenerate_pi_lobes_keep_their_direction(self):
        # N₂ : deux π égales ; leur direction ne doit pas tourner au gré du bruit numérique d'un pas à l'autre.
        calc = xtb.Calculator([7, 7])
        directions = []
        for shift in (0.0, 1e-4, -2e-4):
            p = np.array([[0, 0, 0], [shift, 0, 1.10]], float)
            directions.append(analysis.bond_symmetry(calc.compute(p), 0, 1, directions=True)[2])
        for d in directions[1:]:
            self.assertGreater(abs(float(d @ directions[0])), 0.999)

    def test_isotope_choices(self):
        # Les stables et les radioactifs d'au moins un an de demi-vie ; les masses de l'AME.
        self.assertEqual([a for a, *_ in analysis.isotopes(1)], [1, 2, 3])
        self.assertAlmostEqual(analysis.isotopes(1)[2][3], 12.32, places=2)         # le tritium
        self.assertIn(14, [a for a, *_ in analysis.isotopes(6)])                    # ¹⁴C, 5 700 ans
        self.assertEqual([a for a, *_ in analysis.isotopes(8)], [16, 17, 18])
        self.assertTrue(analysis.isotopes(43))                                      # Tc : aucun stable
        self.assertEqual(analysis.isotope_mass(1, 0), analysis.mass(1))
        self.assertAlmostEqual(analysis.isotope_mass(6, 13), 13.003355, places=5)

    def test_molecules_of_life_relax_and_keep_their_bonds(self):
        # Glucose, un acide gras (lipide), le glycérol, un acide aminé, la caféine : conformères de PubChem, relaxés
        # par GFN1-xTB sans qu'une liaison casse.
        lab = MoleculeLab(rng_seed=5)
        try:
            lab.close()
            time.sleep(0.3)
            labels = [name for name, _ in structures.PRESETS]
            for label in ("C₆H₁₂O₆ · glucose", "C₁₂H₂₄O₂ · acide laurique", "C₃H₈O₃ · glycérol",
                          "C₃H₇NO₂ · alanine", "C₈H₁₀N₄O₂ · caféine"):
                lab._do_preset(labels.index(label))
                before = int(np.sum(np.triu(analysis.mayer_bond_orders(lab.result)) >= 0.5))
                steps = 0
                while not lab.relaxed and steps < 1500:
                    lab._fire_step()
                    steps += 1
                self.assertTrue(lab.relaxed, label)
                after = int(np.sum(np.triu(analysis.mayer_bond_orders(lab.result)) >= 0.5))
                self.assertEqual(after, before, label)
                self.assertEqual(after, len(lab.numbers) - 1 + (1 if "glucose" in label else 0)
                                 + (2 if "caféine" in label else 0), label)        # cycles : glucose 1, caféine 2
        finally:
            lab.close()

    def test_gold_cluster_relaxes_quickly_atom_by_atom(self):
        # La relaxation en masses égales : les atomes d'or descendent aussi vite que l'hydrogène, et la localisation des
        # paires ne bloque plus le calcul.
        lab = MoleculeLab(rng_seed=5)
        try:
            lab.clear()
            start = time.time()
            for k in range(6):
                lab.add_atom(79)
                wait(lab, lambda s, g, k=k: len(s["numbers"]) == k + 1 and s["relaxed"], limit=60.0)
            self.assertLess(time.time() - start, 60.0)
        finally:
            lab.close()


if __name__ == "__main__":
    unittest.main()
