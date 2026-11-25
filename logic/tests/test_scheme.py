"""Le schéma réactionnel : du dessin 2D (formules squelettiques, réactifs écrits, flèches courbes) aux chemins 3D."""

import json
import time
import unittest

import numpy as np

import genesis_pb2 as pb
from moleculelab import analysis, frame, scheme
from moleculelab import lab as lab_module
from moleculelab.lab import MoleculeLab


def drawn(*molecules, arrows=(), curly=()):
    """Un document : des SMILES posés à (x, y), des flèches (queue, pointe, texte)."""
    doc = {"atoms": [], "bonds": [], "arrows": [], "curly": list(curly)}
    for smiles, x, y in molecules:
        d = scheme.drawing(smiles)
        base = len(doc["atoms"])
        doc["atoms"] += [[z, ax + x, ay + y, q, None] for z, ax, ay, q in d["atoms"]]
        doc["bonds"] += [[a + base, b + base, o] for a, b, o in d["bonds"]]
    for tail, head, above in arrows:
        doc["arrows"].append({"tail": tail, "head": head, "above": above, "below": ""})
    return doc


DIELS_ALDER = drawn(("C=CC=C", -6.5, 0), ("C=C", -3.6, 0), ("C1=CCCCC1", 3.2, 0), arrows=[([-2.2, 0], [0.6, 0], "")])


class DrawingTest(unittest.TestCase):
    def test_hydrogens_are_implicit_and_follow_charge_and_valence(self):
        # Éthanol dessiné C–C–O : C₂H₆O. N⁺ à quatre liaisons : aucun H ; S à six liaisons : la valence suivante.
        self.assertEqual(scheme.implicit_hydrogens(6, 0, 1), 3)
        self.assertEqual(scheme.implicit_hydrogens(8, 0, 1), 1)
        self.assertEqual(scheme.implicit_hydrogens(7, 1, 4), 0)
        self.assertEqual(scheme.implicit_hydrogens(7, 1, 3), 1)
        self.assertEqual(scheme.implicit_hydrogens(8, -1, 1), 0)
        self.assertEqual(scheme.implicit_hydrogens(16, 0, 6), 0)
        self.assertEqual(scheme.implicit_hydrogens(27, 0, 4), 0)
        doc = {"atoms": [[6, 0, 0, 0], [6, 1, 0, 0], [8, 2, 0, 0]], "bonds": [[0, 1, 1], [1, 2, 1]]}
        piece = scheme.Drawing(doc).pieces[0]
        self.assertEqual(sorted(piece.numbers), [1] * 6 + [6, 6, 8])

    def test_abbreviations_are_expanded(self):
        # Ph–OMe dessiné avec deux abréviations liées : l'anisole, C₇H₈O.
        doc = {"atoms": [[0, 0, 0, 0, "Ph"], [0, 1, 0, 0, "OMe"]], "bonds": [[0, 1, 1]]}
        piece = scheme.Drawing(doc).pieces[0]
        counts = {z: piece.numbers.count(z) for z in set(piece.numbers)}
        self.assertEqual(counts, {6: 7, 1: 8, 8: 1})

    def test_arrows_split_the_drawing_into_steps(self):
        steps = scheme.Drawing(DIELS_ALDER).steps()
        self.assertEqual(len(steps), 1)
        _, reactants, products = steps[0]
        self.assertEqual(len(reactants), 2)
        self.assertEqual(len(products), 1)

    def test_the_users_scheme_is_cut_into_its_four_steps(self):
        title, doc = scheme.examples()[3]
        d = scheme.Drawing(doc)
        formulas = [([d.pieces[g].describe().split(" ")[0] for g in r], [d.pieces[g].describe().split(" ")[0] for g in p])
                    for _, r, p in d.steps()]
        self.assertEqual(formulas[1], (["C₂₁H₃₀N₂O₂Si"], ["C₂₂H₃₃N₂O₂Si⁺"]))
        self.assertEqual(sorted(formulas[3][0]), ["C₁₆H₁₅NO₂", "C₄H₆O₂"])

    def test_every_example_is_cut_into_its_steps(self):
        # Les schémas tout faits : le nombre d'étapes que leurs flèches dessinent, chaque étape avec réactifs et produits.
        expected = [1, 1, 1, 4, 3, 2, 2, 2, 3, 2]
        examples = scheme.examples()
        self.assertEqual(len(examples), len(expected))
        for (title, doc), count in zip(examples, expected):
            steps = scheme.Drawing(doc).steps()
            self.assertEqual(len(steps), count, title)
            for _, reactants, products in steps:
                self.assertTrue(reactants and products, title)

    def test_text_on_an_arrow_gives_reagents_and_conditions(self):
        reagents, conditions, notes, kelvin, solvent = scheme.read_text("2 MeI, THF, 25 °C, xyz")
        self.assertEqual(solvent, "thf")
        self.assertEqual(len(reagents), 1)
        piece, count = reagents[0]
        self.assertEqual(count, 2)
        self.assertEqual(sorted(piece.numbers), [1, 1, 1, 6, 53])
        self.assertAlmostEqual(kelvin, 298.15)
        self.assertIn("THF", conditions)
        self.assertTrue(any("xyz" in n for n in notes))
        piece = scheme.read_text("Co2(CO)8")[0][0][0]
        self.assertEqual(sorted(set(piece.numbers)), [6, 8, 27])
        self.assertEqual(len(piece), 18)


class MechanismTest(unittest.TestCase):
    def test_curly_arrows_move_electron_pairs(self):
        # Butadiène C0=C1–C2=C3 + éthylène C4=C5, trois flèches : le cyclohexène, charges nulles.
        numbers = [6] * 6
        bonds = {(0, 1): 2, (1, 2): 1, (2, 3): 2, (4, 5): 2}
        arrows = [((0, 1), (1, 2)), ((2, 3), (3, 4)), ((4, 5), (5, 0))]
        charges, after = scheme.push(numbers, [0] * 6, bonds, arrows)
        self.assertEqual(charges, [0] * 6)
        self.assertEqual(after, {(0, 5): 1, (0, 1): 1, (1, 2): 2, (2, 3): 1, (3, 4): 1, (4, 5): 1})
        # Un doublet de F⁻ vers Si : la liaison se forme, F redevient neutre, Si porte −1.
        charges, after = scheme.push([9, 14], [-1, 0], {}, [((0,), (0, 1))])
        self.assertEqual(charges, [0, -1])
        self.assertEqual(after, {(0, 1): 1})

    def test_atoms_are_mapped_with_the_fewest_bond_changes(self):
        d = scheme.Drawing(DIELS_ALDER)
        _, reactants, products = d.steps()[0]
        R = scheme.System([d.pieces[g] for g in reactants])
        P = scheme.System([d.pieces[g] for g in products])
        mapping = scheme.map_atoms(R.numbers, R.bonds, P.numbers, P.bonds, scheme._fragments(len(R), R.bonds, range(len(R))))
        # Diels–Alder : deux liaisons formées, quatre changent d'ordre ; aucun hydrogène ne change d'atome.
        self.assertEqual(scheme._changes(R.bonds, P.bonds, mapping, len(R)), 2 + 4 * 0.5)

    def test_byproducts_are_deduced_from_valences(self):
        # Me₃Si (sa liaison au carbone partie dans le produit), F⁻ et Cs⁺ : Me₃SiF se forme, Cs⁺ reste un ion.
        tms = scheme.from_smiles("C[SiH](C)C")
        si = tms.numbers.index(14)
        h = [n for n in tms.neighbours(si) if tms.numbers[n] == 1][0]
        tms = scheme._without(tms, h)
        salt = scheme.from_smiles("[Cs+].[F-]")
        R = scheme.System([tms, salt])
        q, inner = scheme.byproducts(R.numbers, R.charges, R.bonds, list(range(len(R))), 1)
        si, f, cs = R.numbers.index(14), R.numbers.index(9), R.numbers.index(55)
        self.assertIn((min(si, f), max(si, f)), inner)
        self.assertEqual((q[si], q[f], q[cs]), (0, 0, 1))
        # Un CO qui a perdu son métal : [C⁻]≡[O⁺], neutre.
        co = scheme.Piece()
        co.add(6)
        co.add(8)
        co.bond(0, 1, 3)
        q, inner = scheme.byproducts([6, 8], [0, 0], co.bonds, [0, 1], 0)
        self.assertEqual((q[0], q[1]), (-1, 1))


class CompileTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.published = []
        cls.state = scheme.compile_scheme(DIELS_ALDER, publish=cls.published.append)

    def test_the_diels_alder_path_is_computed_and_confirmed(self):
        step = self.state["steps"][0]
        self.assertTrue(step["computed"])
        self.assertTrue(step["confirmed"], step["verdict"])
        self.assertEqual(step["mapping"], "correspondance")
        # GFN1-xTB : exothermique (−2,8 eV calculés, comme le calcul direct ; mesuré ΔH ≈ −1,7 eV), avec une barrière
        # sur le chemin (0,35 eV ; mesurée Eₐ ≈ 1,2 eV : GFN1 sous-estime les barrières).
        self.assertAlmostEqual(step["delta"], -2.80, delta=0.1)
        self.assertGreater(step["barrier"], 0.15)
        self.assertLess(step["barrier"], 2.0)
        self.assertEqual(len(step["frames"]), len(step["energies"]))
        self.assertEqual(len(step["orders"]), len(step["frames"]))
        # Les deux liaisons σ formées : leur indice de Mayer passe de ~0 à ~1 le long du chemin.
        formed = [k for k, (a, b, before, after) in enumerate(step["changes"]) if before == 0]
        self.assertEqual(len(formed), 2)
        for k in formed:
            self.assertLess(step["orders"][0][k], 0.2)
            self.assertGreater(step["orders"][-1][k], 0.85)

    def test_progress_is_published(self):
        self.assertTrue(self.published[-1]["done"])
        self.assertGreater(len(self.published), 5)

    def test_the_journal_follows_the_computation(self):
        # Le journal : chaque relaxation et chaque palier, avec son énergie ; le profil se remplit dans les deux sens ;
        # la géométrie en cours part avec ses liaisons et les distances tirées.
        log = self.state["log"]
        whats = [w for _, _, w, _, _ in log]
        self.assertEqual(whats[0], "schéma lu")
        self.assertEqual(whats[-1], "schéma calculé")
        self.assertEqual(sum(w.startswith("chemin à rebours") for w in whats), scheme.SCAN_POINTS + 1)
        self.assertEqual(sum(w.startswith("chemin à l'endroit") for w in whats), scheme.SCAN_POINTS + 1)
        self.assertIn("produit relaxé seul, il tient", whats)
        self.assertTrue(all(t2 >= t1 for (t1, *_), (t2, *_) in zip(log, log[1:])))
        busy = [p for p in self.published if not p["done"] and p["live"]["forward"]]
        self.assertTrue(busy)
        live = busy[-1]["live"]
        self.assertEqual(len(live["positions"]), 3 * len(live["numbers"]))
        self.assertTrue(live["bonds"])
        self.assertEqual(len(live["pulled"]), 4)                  # les deux liaisons σ formées, tirées
        self.assertGreater(live["calls"], 100)
        state = pb.MoleculeLabState()
        frame.fill_scheme(state.scheme, dict(busy[-1], request=2))
        self.assertEqual(len(state.scheme.log), len(busy[-1]["log"]))
        self.assertEqual(list(state.scheme.live.numbers), live["numbers"])

    def test_the_frame_carries_the_scheme(self):
        state = pb.MoleculeLabState()
        frame.fill_scheme(state.scheme, dict(self.state, request=4))
        self.assertEqual(state.scheme.request, 4)
        step = state.scheme.steps[0]
        self.assertTrue(step.confirmed)
        self.assertEqual(len(step.energies), len(self.state["steps"][0]["energies"]))
        self.assertEqual(len(step.changes), 4 * len(self.state["steps"][0]["changes"]))

    def test_the_path_plays_in_the_laboratory(self):
        lab = MoleculeLab(background=False)
        lab._scheme_paths = self.state["steps"]
        lab._do_scheme_play(0, 0.0, True)
        self.assertEqual(len(lab.numbers), 16)
        start = lab.census
        for _ in range(200):
            if not lab.play["running"]:
                break
            lab._play_step()
        self.assertFalse(lab.play["running"])
        self.assertNotEqual(lab.census, start)
        self.assertIn("cyclohexène", " ".join(lab.named))
        # Toucher la scène la rend libre.
        lab._do_dynamics(1, 300.0)
        self.assertIsNone(lab.play)

    def test_the_path_carries_its_images_for_the_2_5d_view(self):
        # La vue 2.5D des schémas : chaque image avec ses positions (centrées sur ses masses, comme la scène les pose)
        # et ses charges de Mulliken (du calcul qui a donné son énergie), les atomes de la scène, les sauts de branche.
        step = self.state["steps"][0]
        n, count = len(step["numbers"]), len(step["frames"])
        self.assertEqual(len(step["charges"]), count)
        self.assertTrue(all(len(row) == n for row in step["charges"]))
        self.assertAlmostEqual(sum(step["charges"][0]), 0.0, places=6)          # Mulliken : la charge totale, 0
        for k in step["jumps"]:                                                  # un saut : un atome hors de son corps
            self.assertGreater(lab_module.internal_motion(step["frames"][k], step["frames"][k + 1], step["numbers"]),
                               lab_module.JUMP)
        state = pb.MoleculeLabState()
        frame.fill_scheme(state.scheme, dict(self.state, request=5))
        s = state.scheme.steps[0]
        self.assertEqual(list(s.numbers), list(step["numbers"]))
        self.assertEqual(len(s.path), 3 * n * count)
        self.assertEqual(len(s.charges), n * count)
        self.assertEqual(list(s.jumps), list(step["jumps"]))
        masses = np.array([analysis.isotope_mass(z, 0) for z in step["numbers"]])
        first = np.array(s.path[:3 * n]).reshape(n, 3)
        np.testing.assert_allclose(masses @ first / masses.sum(), 0, atol=1e-4)

    def test_the_path_carries_the_bonds_of_each_image(self):
        # La vue 2.5D rejoue le chemin seule : chaque paire liée dans au moins une image, son indice de Mayer image par
        # image (les deux liaisons σ du Diels–Alder : de ~0 à ~1), le rayon covalent de chaque atome.
        step = self.state["steps"][0]
        pairs, orders = step["bond_pairs"], step["bond_orders"]
        self.assertEqual(len(orders), len(step["frames"]))
        self.assertTrue(all(len(row) == len(pairs) for row in orders))
        for a, b, before, after in step["changes"]:
            if before == 0:
                k = pairs.index((min(a, b), max(a, b)))
                self.assertLess(orders[0][k], 0.2)
                self.assertGreater(orders[-1][k], 0.85)
        state = pb.MoleculeLabState()
        frame.fill_scheme(state.scheme, dict(self.state, request=5))
        s = state.scheme.steps[0]
        self.assertEqual(len(s.bond_pairs), 2 * len(pairs))
        self.assertEqual(len(s.bond_orders), len(pairs) * len(step["frames"]))
        self.assertEqual(len(s.radii), len(step["numbers"]))

    def test_steps_are_sent_only_when_they_change(self):
        # Pendant le calcul, le moteur publie toutes les 0,15 s : les étapes (lourdes) ne repartent qu'à leur changement.
        sent = {}
        first, second = pb.MoleculeScheme(), pb.MoleculeScheme()
        frame.fill_scheme(first, dict(self.state, request=7), sent)
        frame.fill_scheme(second, dict(self.state, request=7, status="encore"), sent)
        self.assertGreater(len(first.steps), 0)
        self.assertFalse(first.steps_kept)
        self.assertEqual(len(second.steps), 0)
        self.assertTrue(second.steps_kept)
        third = pb.MoleculeScheme()
        frame.fill_scheme(third, dict(self.state, request=8), sent)              # un autre calcul : les étapes repartent
        self.assertGreater(len(third.steps), 0)

    def test_the_play_survives_a_new_publication(self):
        # La liste des étapes est refaite à chaque publication (le même calcul de chaque étape) : la lecture ne repart
        # plus de zéro (ce qui défaisait chaque clic sur la frise pendant que le schéma se calculait).
        lab = MoleculeLab(background=False)
        lab._scheme_paths = list(self.state["steps"])
        lab._do_scheme_play(0, 0.5, False)
        s = lab.play["s"]
        self.assertGreater(s, 0)
        lab._scheme_paths = list(self.state["steps"])
        lab._do_scheme_play(0, -1, False)
        self.assertEqual(lab.play["s"], s)

    def test_the_shown_energy_is_computed_at_the_shown_geometry(self):
        # Aux images, l'énergie que la scène calcule est celle du chemin ; à travers un saut de branche, la scène montre
        # une image puis l'autre, jamais une géométrie entre elles.
        step = self.state["steps"][0]
        lab = MoleculeLab(background=False)
        lab._scheme_paths = self.state["steps"]
        last = len(step["frames"]) - 1
        for k in (0, step["scan"][0], last):
            lab._do_scheme_play(0, k / last, False)
            lab._publish()
            self.assertAlmostEqual(lab._snapshot["play"]["energy"], step["energies"][k], delta=1e-4)
        for k in step["jumps"]:
            for t, image in ((0.3, k), (0.7, k + 1)):
                lab._do_scheme_play(0, (k + t) / last, False)
                p = np.asarray(step["frames"][image])
                np.testing.assert_allclose(lab.positions, p - (lab.masses @ p) / lab.masses.sum(), atol=1e-9)

    def test_the_path_plays_at_the_chosen_speed(self):
        # À 2,5 images par seconde, le chemin avance avec l'horloge (0,1 s → 0,25 image), jamais de plus de PLAY_LEAP
        # d'un calcul au suivant ; sans vitesse donnée, il garde la sienne.
        lab = MoleculeLab(background=False)
        lab._scheme_paths = self.state["steps"]
        lab._do_scheme_play(0, 0.0, True, 2.5)
        self.assertEqual(lab.play["speed"], 2.5)
        lab.play["clock"] = time.perf_counter() - 0.1
        lab._play_step()
        self.assertGreaterEqual(lab.play["s"], 0.25)
        self.assertLess(lab.play["s"], 0.45)
        before = lab.play["s"]
        lab.play["clock"] = time.perf_counter() - 10
        lab._play_step()
        self.assertAlmostEqual(lab.play["s"] - before, lab_module.PLAY_LEAP, places=9)
        lab._do_scheme_play(0, -1, True)
        self.assertEqual(lab.play["speed"], 2.5)
        self.assertGreater(lab._play_wait(), 0.0)          # la géométrie suivante attend son heure
        lab._do_scheme_play(0, -1, True, 0)
        self.assertEqual(lab._play_wait(), 0.0)            # au rythme du calcul : aucune attente


class PlayTest(unittest.TestCase):
    def test_a_flipped_molecule_turns_instead_of_crossing_itself(self):
        # Un CO retourné d'une image à l'autre : en ligne droite, son O passerait par son C (les deux atomes confondus
        # à mi-chemin) ; par corps rigide, il tourne et garde sa longueur de liaison.
        from moleculelab.lab import blend
        a = np.array([[0.0, 0.0, 0.0], [1.13, 0.0, 0.0]])
        b = a[::-1].copy()
        for t in (0.25, 0.5, 0.75):
            x = blend(a, b, t, [6, 8])
            self.assertAlmostEqual(float(np.linalg.norm(x[0] - x[1])), 1.13, places=6)
        np.testing.assert_allclose(blend(a, b, 0.0, [6, 8]), a)
        np.testing.assert_allclose(blend(a, b, 1.0, [6, 8]), b)

    def test_a_rigid_motion_is_not_a_jump(self):
        # Tourner et déplacer une molécule entière ne la déforme pas ; un H du méthane poussé de 0,6 Å de côté (encore
        # lié), si — l'alignement de Kabsch en absorbe une part. Un atome qui quitte son corps rigide (plus lié dans
        # l'une des images) n'en est pas une déformation : il réagit.
        methane = np.array([[0, 0, 0], [.63, .63, .63], [-.63, -.63, .63], [-.63, .63, -.63], [.63, -.63, -.63]], float)
        numbers = [6, 1, 1, 1, 1]
        c, s = np.cos(1.1), np.sin(1.1)
        turned = methane @ np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]]).T + np.array([2.0, -1.0, 0.5])
        self.assertLess(lab_module.internal_motion(methane, turned, numbers), 1e-9)
        bent = methane.copy()
        bent[1] += np.array([0.6, -0.6, 0.0]) / np.sqrt(2)
        self.assertGreater(lab_module.internal_motion(methane, bent, numbers), 0.2)

    def test_byproducts_leave_after_the_path(self):
        # Menshutkin : NMe₄⁺ et I⁻ formés s'écartent l'un de l'autre après le chemin imposé (images calculées).
        title, doc = scheme.examples()[1]
        step = scheme.compile_scheme(doc, quick=True)["steps"][0]
        start, end = step["scan"]
        self.assertEqual(len(step["frames"]) - end, len(scheme.APPROACH))
        x0, x1 = np.asarray(step["frames"][end - 1]), np.asarray(step["frames"][-1])
        iodine = [k for k, z in enumerate(step["numbers"]) if z == 53][0]
        nitrogen = [k for k, z in enumerate(step["numbers"]) if z == 7][0]
        self.assertGreater(np.linalg.norm(x1[iodine] - x1[nitrogen]), np.linalg.norm(x0[iodine] - x0[nitrogen]) + 2.5)
        lab = MoleculeLab(background=False)
        lab._scheme_paths = [step]
        lab._do_scheme_play(0, 0.0, False)
        self.assertGreater(lab.play["radius"], 1.0)


class RelaxTest(unittest.TestCase):
    """La relaxation des chemins : des pas bornés qui suivent leurs cibles, et des images qui ne se traversent pas."""

    def test_a_pulled_distance_follows_its_target(self):
        # L'éthane, sa liaison C–C tirée vers 1,85 Å par le ressort des chemins imposés (1 Eh/Å²) : en douze pas, elle
        # est déjà à l'équilibre du ressort et de la liaison (≈ 1,77 Å, celui que trouvent 120 pas). L-BFGS-B pouvait
        # s'arrêter dès son premier pas, trop long, et laisser le palier là où il commençait.
        numbers = [6, 6, 1, 1, 1, 1, 1, 1]
        x = np.array([[0, 0, 0.765], [0, 0, -0.765], [1.02, 0, 1.16], [-0.51, 0.88, 1.16], [-0.51, -0.88, 1.16],
                      [-1.02, 0, -1.16], [0.51, -0.88, -1.16], [0.51, 0.88, -1.16]], float)
        pulled = [(0, 1, 1.85, scheme.SPRING)]
        short, e, _ = scheme.relax(numbers, 0, x, lambda: False, iterations=12, restraints=pulled)
        long, _, _ = scheme.relax(numbers, 0, x, lambda: False, iterations=120, restraints=pulled)
        d12, d120 = (float(np.linalg.norm(p[0] - p[1])) for p in (short, long))
        self.assertGreater(d120, 1.7)
        self.assertAlmostEqual(d12, d120, delta=0.02)
        self.assertTrue(np.isfinite(e))

    def test_each_step_moves_an_atom_by_at_most_max_step(self):
        # Une cuvette raide, loin de son fond : chaque pas est borné, et l'énergie descend.
        target = np.zeros(9)

        def bowl(x):
            return float(50 * np.sum((x - target) ** 2)), 100 * (x - target)

        seen = []
        start = np.full(9, 3.0)
        end = scheme.descend(bowl, start, 30, callback=lambda x: seen.append(x.copy()))
        previous = start
        for x in seen:
            self.assertLessEqual(float(np.max(np.linalg.norm((x - previous).reshape(-1, 3), axis=1))),
                                 scheme.MAX_STEP + 1e-9)
            self.assertLess(bowl(x)[0], bowl(previous)[0])
            previous = x
        self.assertLess(float(np.max(np.abs(end - target))), 0.05)

    def test_the_closest_approach_between_bodies(self):
        x = np.array([[0, 0, 0], [1, 0, 0], [4, 0, 0], [6.5, 0, 0]], float)
        self.assertAlmostEqual(scheme._closest_between(x, [[0, 1], [2], [3]]), 2.5)
        self.assertEqual(scheme._closest_between(x, [[0, 1, 2, 3]]), np.inf)


class ContinuousPathTest(unittest.TestCase):
    def test_the_kept_images_change_branch_only_where_they_join(self):
        # Deux branches de 5 paliers : la plus basse alterne d'un palier à l'autre, mais elles ne se rejoignent qu'au
        # palier 2. Le chemin gardé ne passe de l'une à l'autre que là (au lieu de sauter à chaque palier), et prend la
        # suite dont la plus haute image est la plus basse.
        from unittest import mock
        from moleculelab import lab as lab_module_

        class R:
            def __init__(self, energy):
                self.energy = energy

        def image(branch, level, energy):
            x = np.zeros((2, 3))
            x[0, 0], x[0, 1] = branch, level
            return (x, R(energy))

        back = [image(0, i, e) for i, e in enumerate([0.0, 0.5, 1.0, 0.4, 0.2])]
        fore = [image(1, i, e) for i, e in enumerate([0.1, 0.3, 1.0, 0.9, 0.1])]

        def motion(a, b, numbers):
            same = a[0, 0] == b[0, 0]
            joined = {a[0, 1], b[0, 1]} == {2.0} or (a[0, 1] == 2 and b[0, 1] in (1, 3)) or (b[0, 1] == 2 and a[0, 1] in (1, 3))
            return 0.0 if same or (joined and 2.0 in (a[0, 1], b[0, 1])) else 1.0

        calc = mock.Mock()
        calc.compute.return_value = R(9.0)                # le milieu de deux images non reliées : très haut
        with mock.patch.object(lab_module_, "internal_motion", motion),                 mock.patch.object(lab_module_, "blend", lambda a, b, t, numbers: a):
            kept = scheme._continuous(back, fore, 4, [1, 1], calc)
        branches = [int(x[0, 0]) for x, r in kept]
        levels = [int(x[0, 1]) for x, r in kept]
        self.assertEqual(levels, [4, 3, 2, 1, 0])
        for j in range(1, len(kept)):
            if branches[j] != branches[j - 1]:
                self.assertIn(2, (levels[j], levels[j - 1]))          # on ne change de branche qu'au palier 2
        self.assertLessEqual(max(r.energy for x, r in kept), 1.0)


class FailureTest(unittest.TestCase):
    def test_a_missing_reagent_is_said(self):
        # Éthylène → éthane sans H₂ : il manque deux hydrogènes.
        doc = drawn(("C=C", -3, 0), ("CC", 3, 0), arrows=[([-1.5, 0], [1.5, 0], "")])
        state = scheme.compile_scheme(doc, quick=True)
        self.assertFalse(state["steps"][0]["computed"])
        self.assertIn("ajoutez le réactif", state["steps"][0]["verdict"])

    def test_no_arrow_is_an_error(self):
        with self.assertRaises(scheme.SchemeError):
            scheme.compile_scheme(drawn(("C=C", 0, 0)), quick=True)

    def test_smiles_drawing(self):
        d = scheme.drawing("c1ccccc1O")
        self.assertEqual(len(d["atoms"]), 7)
        self.assertEqual(sorted(o for _, _, o in d["bonds"]), [1, 1, 1, 1, 2, 2, 2])
        json.dumps(d)


class WholeSchemeTest(unittest.TestCase):
    """Les étapes d'un même schéma se suivent : un atome repris garde son numéro, chaque étape sait où elle est par
    rapport aux molécules relaxées seules (le profil de tout le schéma)."""

    @classmethod
    def setUpClass(cls):
        doc = scheme._row(["C=CC=C.C=C", "> |", "C1=CCCCC1", "> [H][H] |", "C1CCCCC1"])
        cls.state = scheme.compile_scheme(doc, quick=True)

    def test_atoms_keep_their_number_from_step_to_step(self):
        first, second = self.state["steps"]
        self.assertTrue(first["computed"] and second["computed"])
        self.assertEqual(first["ids"], list(range(1, len(first["numbers"]) + 1)))
        # Les 16 atomes du cyclohexène gardent leurs numéros de l'étape 1 ; les deux H de H₂ en prennent de nouveaux.
        carried = [i for i in second["ids"] if i <= len(first["ids"])]
        self.assertEqual(len(carried), 16)
        self.assertEqual(sorted(i for i in second["ids"] if i > len(first["ids"])), [17, 18])
        by_id = dict(zip(first["ids"], first["numbers"]))
        for i, z in zip(second["ids"], second["numbers"]):
            if i in by_id:
                self.assertEqual(by_id[i], z)                     # le même atome, le même élément

    def test_each_step_is_placed_against_the_molecules_relaxed_alone(self):
        for s in self.state["steps"]:
            for key in ("start_ev", "end_ev", "transfer_ev"):
                self.assertTrue(np.isfinite(s[key]), key)
            self.assertEqual(s["transfer_ev"], 0.0)                # le même milieu (le vide) d'une étape à l'autre
            self.assertEqual(s["temperature"], 300.0)
        m = pb.MoleculeScheme()
        frame.fill_scheme(m, dict(self.state, request=1))
        self.assertEqual(list(m.steps[1].ids), self.state["steps"][1]["ids"])
        self.assertAlmostEqual(m.steps[0].start_ev, self.state["steps"][0]["start_ev"])


class ValenceTest(unittest.TestCase):
    def test_a_pentavalent_carbon_is_refused_with_its_reason(self):
        doc = {"atoms": [[6, 0, 0, 0, None], [6, 1, 0, 0, None], [6, -1, 0, 0, None], [6, 0, 1, 0, None],
                         [6, 0, -1, 0, None], [6, 0.7, 0.7, 0, None], [6, 5, 0, 0, None], [6, 6, 0, 0, None]],
               "bonds": [[0, 1, 1], [0, 2, 1], [0, 3, 1], [0, 4, 1], [0, 5, 1], [6, 7, 1]],
               "arrows": [{"tail": [2, 0], "head": [4, 0], "above": "", "below": ""}], "curly": []}
        step = scheme.compile_scheme(doc, quick=True)["steps"][0]
        self.assertFalse(step["computed"])
        self.assertIn("5 liaisons", step["verdict"])


class PistonTest(unittest.TestCase):
    def test_the_piston_starts_the_dynamics_and_closes_in_log_steps(self):
        lab = MoleculeLab(background=False, rng_seed=3)
        lab._do_reaction(3)
        lab._do_dynamics(lab_module.RELAX, 300.0)
        lab._do_pressure(10000.0)
        self.assertEqual(lab.dynamics, lab_module.THERMOSTAT)          # en relaxation, le piston n'agissait pas
        self.assertFalse(lab.paused)
        # Une sphère encore vide (P ≈ 0) : elle se ferme au plafond du pas (1 %), pas à 1/300 par fs.
        r0 = lab.wall
        lab.pressure = 0.0
        lab._piston(0.5)
        self.assertAlmostEqual(lab.wall / r0, (1 - lab_module.PISTON_STEP) ** (1 / 3), places=6)


class EntropyTest(unittest.TestCase):
    def test_translations_and_rotations_match_the_tables(self):
        # S°(298 K, 1 bar) mesurées : CO 197,7 J/mol/K (presque rien de vibration) ; H₂O 188,8 (σ = 2 : R ln 2 de moins).
        co = scheme._entropy([6, 8], np.array([[0, 0, 0], [1.128, 0, 0]]), 298.15)
        self.assertAlmostEqual(co, 197.7, delta=0.5)
        water = scheme._entropy([8, 1, 1], np.array([[0, 0, 0], [0.757, 0.586, 0], [-0.757, 0.586, 0]]), 298.15)
        self.assertAlmostEqual(water - 8.314462618 * np.log(2), 188.8, delta=1.0)

    def test_identical_byproducts_are_counted(self):
        self.assertEqual(scheme._counted(["CO · monoxyde de carbone", "CO · monoxyde de carbone"]),
                         "2 CO · monoxyde de carbone")
        self.assertEqual(scheme._counted(["NMe₃", "Me₃SiF"]), "NMe₃ + Me₃SiF")


class ImportTest(unittest.TestCase):
    """Ce que ChemDraw et MarvinSketch copient ou enregistrent, lu pour l'éditeur."""

    def check(self, text, kind, atoms, arrows, steps):
        doc, found = scheme.imported(text)
        self.assertEqual(found, kind)
        self.assertEqual(len(doc["atoms"]), atoms)
        self.assertEqual(len(doc["arrows"]), arrows)
        self.assertEqual(len(scheme.Drawing(doc).steps()), steps)
        json.dumps(doc)

    def test_every_format(self):
        from rdkit import Chem
        from rdkit.Chem import AllChem, rdChemReactions

        def mol(smiles):
            m = Chem.MolFromSmiles(smiles)
            AllChem.Compute2DCoords(m)
            return m

        rxn = rdChemReactions.ReactionFromSmarts("C=CC=C.C=C>>C1=CCCCC1", useSmiles=True)
        for m in list(rxn.GetReactants()) + list(rxn.GetProducts()):
            AllChem.Compute2DCoords(m)
        self.check("CC(C)=O", "SMILES", 4, 0, 0)
        self.check("C=CC=C.C=C>>C1=CCCCC1", "SMILES de réaction", 12, 1, 1)
        self.check(Chem.MolToMolBlock(mol("O=Cc1ccccc1")), "MOL", 8, 0, 0)
        self.check(Chem.MolToV3KMolBlock(mol("CC(=O)[O-]")), "MOL", 4, 0, 0)
        self.check(Chem.MolToMolBlock(mol("CC=O")) + "$$$$\n" + Chem.MolToMolBlock(mol("CCO")) + "$$$$\n", "SDF", 6, 0, 0)
        self.check(rdChemReactions.ReactionToRxnBlock(rxn), "RXN", 12, 1, 1)
        self.check(Chem.MolToMrvBlock(mol("c1ccncc1")), "MRV (MarvinSketch)", 6, 0, 0)
        self.check(rdChemReactions.ReactionToMrvBlock(rxn), "MRV (MarvinSketch)", 12, 1, 1)
        self.check(Chem.MolToCDXMLBlock(mol("CC(C)=O")), "CDXML (ChemDraw)", 4, 0, 0)
        # Un schéma ChemDraw : deux fragments, une flèche, une étape (le format que ChemDraw écrit).
        cdxml = ('<?xml version="1.0" encoding="UTF-8" ?><CDXML><page id="1">'
                 '<fragment id="10"><n id="11" p="100 100" Element="6"/><n id="12" p="114 108" Element="8"/>'
                 '<b id="13" B="11" E="12" Order="2"/></fragment>'
                 '<fragment id="20"><n id="21" p="200 100"/><n id="22" p="214 108"/><n id="23" p="228 100" Element="8"/>'
                 '<b id="24" B="21" E="22"/><b id="25" B="22" E="23"/></fragment>'
                 '<arrow id="30" Head3D="180 104 0" Tail3D="130 104 0"/>'
                 '<scheme id="40"><step id="41" ReactionStepReactants="10" ReactionStepProducts="20" '
                 'ReactionStepArrows="30"/></scheme></page></CDXML>')
        self.check(cdxml, "CDXML (ChemDraw)", 5, 1, 1)

    def test_a_pasted_mol_block_that_lost_its_blank_header_line(self):
        from rdkit import Chem
        block = Chem.MolToMolBlock(Chem.MolFromSmiles("CCO"))
        self.check(block.lstrip("\n"), "MOL", 3, 0, 0)

    def test_nonsense_is_refused(self):
        with self.assertRaises(Exception):
            scheme.imported("ceci n'est pas une molécule")


class ParkedLabTest(unittest.TestCase):
    def test_the_molecular_lab_waits_while_another_lab_is_open(self):
        from simulator.repository import ElementRepository
        from simulator.simulation import Session
        session = Session(ElementRepository(mongo_uri=None), seed=3)
        session.start_molecule_lab()
        first = session.molecule_lab
        session.start_subatomic()                      # un autre laboratoire : le moléculaire est mis de côté
        self.assertIsNone(session.molecule_lab)
        self.assertIs(session.parked_molecule_lab, first)
        self.assertFalse(first.closed())
        session.start_molecule_lab()                   # le retour : le même, tel qu'il a été laissé
        self.assertIs(session.molecule_lab, first)
        self.assertEqual(first.generation, 1)
        session.close_labs()
        self.assertTrue(first.closed())


if __name__ == "__main__":
    unittest.main()
