"""L'échelle de la molécule : deux atomes qui se rencontrent.

Personne ne décide ici si les atomes se lient, se volent un électron ou se
repoussent. Pour chaque distance R entre les noyaux, la chimie quantique
(DFT, bibliothèque PySCF) résout les équations des électrons et donne :

  - l'énergie E(R) : s'il existe un puits sous l'énergie des atomes séparés,
    il y a une liaison ; sinon (He + He), les atomes se repoussent ;
  - la densité électronique ρ(r) : les électrons s'accumulent entre les
    noyaux (liaison partagée, H₂) ou migrent vers un atome (transfert, NaCl) ;
  - la différence Δρ = ρ(molécule) − ρ(atomes isolés) : où les électrons
    sont allés en formant la liaison ;
  - les charges des atomes (Mulliken) et l'indice de liaison (Mayer) ;
  - les orbitales moléculaires (σ, π, liantes ou antiliantes) et leur
    remplissage électron par électron, spin compris.

L'électronégativité de Pauling (mendeleev) est affichée à côté, comme règle
de manuel à comparer au calcul ; elle ne décide de rien.

Les noyaux bougent ensuite sous la vraie force F = −dE/dR (approximation de
Born-Oppenheimer : les électrons suivent instantanément les noyaux).

Méthode : DFT PBE0 / def2-SVP (pseudopotentiels au-delà du krypton), Kohn-Sham
non restreint (spins α et β séparés), ajustement de densité. Précision typique :
quelques centièmes d'Å sur la longueur de liaison, quelques dixièmes d'eV sur
l'énergie de liaison.
"""

import math
import os
import pickle
import threading

import numpy as np
from scipy.interpolate import CubicSpline
from scipy.linalg import block_diag

from atomic.electron_config import configuration
from common import elements_db

try:
    from pyscf import dft, gto, lib, symm
    AVAILABLE = True
except ImportError:          # PySCF n'existe que pour Linux et macOS
    AVAILABLE = False

HARTREE_EV = 27.211386245988
BOHR_A = 0.529177210903
METHOD = "DFT PBE0 / def2-SVP, Kohn-Sham non restreint (PySCF)"
XC = "pbe0"
BASIS = "def2-svp"            # au-delà du krypton, les électrons de cœur sont remplacés
                              # par le pseudopotentiel de la base (ecp=BASIS, à déclarer)
GRID_NX, GRID_NY = 120, 72
EXTENT_X, EXTENT_Y = 4.8, 2.9         # demi-largeurs de la coupe, en Å
CACHE = os.environ.get("GENESIS_CHEM_CACHE", os.path.join(os.path.dirname(__file__), "cache"))
# 1 eV / (Å · u) en Å / fs²
ACCELERATION = 1.602176634e-19 / (1e-10 * 1.66053906660e-27) * 1e-20

_DB = elements_db.DB   # verrouillée : plusieurs threads la lisent


def element_row(z):
    return _DB.execute("select symbol, name, atomic_weight, en_pauling, covalent_radius_pyykko from elements "
                       "where atomic_number=?", (z,)).fetchone()


def ground_spin(z):
    """Électrons célibataires de l'atome isolé : Hund, sous-couche par sous-couche.

    p⁴ (oxygène) : 2 célibataires ; d⁶ (fer) : 4 ; couches pleines : 0.
    """
    return sum(min(count, 2 * (2 * l + 1) - count) for _, l, count in configuration(z, z))


# ----------------------------------------------------------------------
# Calculs quantiques
# ----------------------------------------------------------------------
def _molecule(symbols, distance, spin, symmetry=True):
    return gto.M(atom=f"{symbols[0]} 0 0 {-distance / 2}; {symbols[1]} 0 0 {distance / 2}",
                 basis=BASIS, ecp=BASIS, spin=spin, symmetry=symmetry, verbose=0)


def _solver(mol):
    solver = dft.UKS(mol).density_fit()
    solver.xc = XC
    solver.grids.level = 2
    solver.max_cycle = 120
    return solver


def _orbital_labels(mol, solver):
    """σ, π, δ (et g/u pour une molécule homonucléaire) de chaque orbitale."""
    try:
        reference = mol
        if not mol.symmetry:
            # Calcul sans symétrie (atomes séparés) : on projette quand même sur σ, π...
            reference = mol.copy()
            reference.symmetry = True
            reference.build()
        labels = []
        for spin in range(2):
            names = symm.label_orb_symm(reference, reference.irrep_name, reference.symm_orb, solver.mo_coeff[spin],
                                        check=False)
            labels.append([_greek(name) for name in names])
        return labels
    except Exception:
        count = solver.mo_energy[0].size
        return [[""] * count, [""] * count]


def _greek(irrep):
    letter = {"A1": "σ", "A2": "σ", "E1": "π", "E2": "δ", "E3": "φ"}
    base = irrep.rstrip("xy")
    parity = ""
    if base.endswith("g") or base.endswith("u"):
        parity, base = base[-1], base[:-1]
    if base.startswith("E") and len(base) >= 2 and base[1].isdigit():
        base = base[:2]
    return letter.get(base, irrep) + parity


def _mayer(mol, dm):
    """Indice de liaison de Mayer : 1 pour H₂, 2 pour O₂, 3 pour N₂, ~0 pour He₂."""
    overlap = mol.intor("int1e_ovlp")
    slices = mol.aoslice_by_atom()
    a = slice(slices[0][2], slices[0][3])
    b = slice(slices[1][2], slices[1][3])
    order = 0.0
    for spin_dm in dm:
        ps = spin_dm @ overlap
        order += 2 * np.sum(ps[a, b] * ps[b, a].T)
    return float(order)


def _plane():
    xs = np.linspace(-EXTENT_X, EXTENT_X, GRID_NX)
    ys = np.linspace(-EXTENT_Y, EXTENT_Y, GRID_NY)
    zz, xx = np.meshgrid(xs, ys)               # l'axe de la molécule est z, affiché en horizontal
    coords = np.column_stack([xx.ravel(), np.zeros(xx.size), zz.ravel()]) / BOHR_A
    return coords


def _density(mol, dm_total, coords):
    ao = dft.numint.eval_ao(mol, coords)
    rho = dft.numint.eval_rho(mol, ao, dm_total)
    return rho / BOHR_A ** 3                   # électrons par Å³


def _atom_solution(symbol, spin):
    mol = gto.M(atom=f"{symbol} 0 0 0", basis=BASIS, ecp=BASIS, spin=spin, symmetry=False, verbose=0)
    solver = _solver(mol)
    energy = solver.kernel()
    return mol, solver, energy


def _atom_levels(solver):
    levels = []
    for spin in range(2):
        energies = solver.mo_energy[spin] * HARTREE_EV
        occupation = solver.mo_occ[spin]
        homo = max(energies[occupation > 0.5]) if (occupation > 0.5).any() else energies[0]
        keep = [(float(e), float(o)) for e, o in zip(energies, occupation) if homo - 22 < e < homo + 12][:9]
        levels.append(keep)
    return levels


class Pair:
    """Deux atomes : leur courbe d'énergie, leurs électrons, à chaque distance."""

    def __init__(self, za, zb):
        self.za, self.zb = za, zb
        row_a, row_b = element_row(za), element_row(zb)
        self.symbols = (row_a[0], row_b[0])
        self.names = (row_a[1], row_b[1])
        self.masses = (row_a[2], row_b[2])
        self.chi = (row_a[3] or 0.0, row_b[3] or 0.0)
        self.reduced_mass = self.masses[0] * self.masses[1] / (self.masses[0] + self.masses[1])
        radii = ((row_a[4] or 100) + (row_b[4] or 100)) / 100   # pm -> Å
        self.guess = radii
        self.progress = 0.0
        self.status = "en attente"
        self.ready = False
        self.failed = ""
        self.version = 0

    # --- calcul -------------------------------------------------------------
    def cache_file(self):
        return os.path.join(CACHE, f"{min(self.za, self.zb)}-{max(self.za, self.zb)}-{BASIS}-{XC}-v8.pkl")

    def start(self):
        threading.Thread(target=self._compute_safely, daemon=True).start()

    def _compute_safely(self):
        try:
            self._compute()
        except Exception as error:    # le calcul quantique peut ne pas converger : on le dit
            self.failed = f"le calcul n'a pas abouti : {error}"
            self.status = self.failed

    def _compute(self):
        cached = self._load()
        if cached:
            self._finish(cached)
            return
        # Le calcul se fait toujours dans l'ordre des Z croissants (c'est aussi la clé du cache).
        low, high = sorted((self.za, self.zb))
        symbols = (element_row(low)[0], element_row(high)[0])
        spin_a, spin_b = ground_spin(low), ground_spin(high)
        self.status = "atomes isolés"
        mol_a, solver_a, energy_a = _atom_solution(symbols[0], spin_a)
        mol_b, solver_b, energy_b = _atom_solution(symbols[1], spin_b)
        separated = energy_a + energy_b
        atom_dm_a, atom_dm_b = solver_a.make_rdm1(), solver_b.make_rdm1()

        # Spin de la molécule : celui qui donne l'énergie la plus basse près de la liaison
        # (O₂ triplet, N₂ singulet...). On essaie les combinaisons permises des spins atomiques.
        self.status = "état de spin de la molécule"
        best = None
        for spin in sorted({abs(spin_a - spin_b) + 2 * k for k in range(min(spin_a, spin_b) + 1)}):
            solver = _solver(_molecule(symbols, self.guess, spin))
            energy = solver.kernel()
            if solver.converged and (best is None or energy < best[0]):
                best = (energy, spin)
        spin = best[1] if best else abs(spin_a - spin_b)

        # Distances : serrées autour de la liaison, plus lâches au loin.
        grid = np.unique(np.round(np.concatenate([
            np.linspace(0.55, 0.9, 5), np.linspace(0.95, 1.35, 9),
            # au loin : jusqu'à ce que la liaison soit défaite (au moins 2,2 Å au-delà)
            np.linspace(1.45, max(2.6, 1 + 2.2 / self.guess), 7)]) * self.guess, 3))
        coords = _plane()
        points = []
        order = np.argsort(np.abs(grid - self.guess))       # on part de la liaison, vers l'extérieur
        previous = {}                     # spin -> {distance: densité}, pour partir du voisin
        for done, index in enumerate(order):
            distance = float(grid[index])
            self.status = f"calcul quantique {done + 1}/{len(grid)} (R = {distance:.2f} Å)"
            self.progress = done / len(grid)
            # À chaque distance, l'état de spin le plus bas l'emporte. Près de la liaison, c'est
            # celui de la molécule (O₂ : triplet) ; au loin, celui où chaque atome garde ses
            # électrons célibataires (O + O : spins alignés), qui rejoint les atomes séparés.
            # Tentatives : (spin, symétrie imposée, point de départ). La plus basse l'emporte.
            far = distance > 1.1 * self.guess
            attempts = [(spin, True, "voisin")]
            if far and spin == abs(spin_a - spin_b):
                # Symétrie brisée : chaque atome garde ses électrons, spins opposés d'un atome
                # à l'autre. C'est ainsi qu'une liaison (N₂, H₂) se défait proprement.
                attempts.append((spin, False, "brisée"))
            if distance > 1.25 * self.guess and spin_a + spin_b != spin:
                # Spins des atomes alignés : rejoint exactement les atomes séparés (O + O).
                attempts.append((spin_a + spin_b, False, "alignés"))
            best = None
            for state_spin, symmetric, start in attempts:
                mol_s = _molecule(symbols, distance, state_spin, symmetry=symmetric)
                solver_s = _solver(mol_s)
                track = (state_spin, start)
                known = previous.get(track, {})
                neighbour = min(known, key=lambda r: abs(r - distance)) if known else None
                if neighbour is not None:
                    energy_s = solver_s.kernel(known[neighbour])
                elif start == "alignés":
                    energy_s = solver_s.kernel(np.array([block_diag(atom_dm_a[0], atom_dm_b[0]),
                                                         block_diag(atom_dm_a[1], atom_dm_b[1])]))
                elif start == "brisée":
                    # L'atome qui a le plus de spins ↑ les garde ; l'autre les retourne.
                    major, minor = (atom_dm_a, atom_dm_b) if spin_a >= spin_b else (atom_dm_b, atom_dm_a)
                    alpha = block_diag(major[0], minor[1]) if spin_a >= spin_b else block_diag(minor[1], major[0])
                    beta = block_diag(major[1], minor[0]) if spin_a >= spin_b else block_diag(minor[0], major[1])
                    energy_s = solver_s.kernel(np.array([alpha, beta]))
                else:
                    energy_s = solver_s.kernel()
                if far:
                    # Analyse de stabilité : si une solution plus basse existe, on la suit.
                    for _ in range(2):
                        stable_mo = solver_s.stability()[0]
                        if stable_mo is solver_s.mo_coeff:
                            break
                        energy_s = solver_s.kernel(solver_s.make_rdm1(stable_mo, solver_s.mo_occ))
                if not np.isfinite(energy_s):
                    continue
                previous.setdefault(track, {})[distance] = solver_s.make_rdm1()
                if best is None or energy_s < best[0]:
                    best = (energy_s, state_spin, mol_s, solver_s)
            if best is None:
                continue
            energy, point_spin, mol, solver = best
            dm = solver.make_rdm1()
            charges = solver.mulliken_pop(verbose=0)[1]
            density = _density(mol, dm[0] + dm[1], coords)
            # Atomes isolés posés aux mêmes places : ce que serait la densité sans liaison.
            shifted_a = gto.M(atom=f"{symbols[0]} 0 0 {-distance / 2}", basis=BASIS, ecp=BASIS, spin=spin_a, verbose=0)
            shifted_b = gto.M(atom=f"{symbols[1]} 0 0 {distance / 2}", basis=BASIS, ecp=BASIS, spin=spin_b, verbose=0)
            dm_a, dm_b = solver_a.make_rdm1(), solver_b.make_rdm1()
            promolecule = _density(shifted_a, dm_a[0] + dm_a[1], coords) + _density(shifted_b, dm_b[0] + dm_b[1], coords)
            labels = _orbital_labels(mol, solver)
            orbitals = []
            for s in range(2):
                energies = solver.mo_energy[s] * HARTREE_EV
                occupation = solver.mo_occ[s]
                homo = max(energies[occupation > 0.5]) if (occupation > 0.5).any() else energies[0]
                orbitals.append([(float(e), float(o), labels[s][k]) for k, (e, o) in enumerate(zip(energies, occupation))
                                 if homo - 22 < e < homo + 12][:10])
            points.append({
                "r": distance, "energy": (energy - separated) * HARTREE_EV,
                "charge_a": float(charges[0]), "bond_order": _mayer(mol, dm),
                "density": density.astype(np.float32), "difference": (density - promolecule).astype(np.float32),
                "orbitals": orbitals, "spin": point_spin,
            })
        points.sort(key=lambda point: point["r"])
        result = {"points": points, "spin": spin, "atoms": (_atom_levels(solver_a), _atom_levels(solver_b)),
                  "spins": (spin_a, spin_b)}
        self._save(result)
        self._finish(result)

    def _load(self):
        try:
            with open(self.cache_file(), "rb") as handle:
                return pickle.load(handle)
        except (OSError, pickle.UnpicklingError, EOFError):
            return None

    def _save(self, result):
        try:
            os.makedirs(CACHE, exist_ok=True)
            with open(self.cache_file(), "wb") as handle:
                pickle.dump(result, handle)
        except OSError:
            pass

    def _finish(self, result):
        swapped = self.za > self.zb
        points = result["points"]
        self.points = points
        self.spin = result["spin"]
        self.atom_levels = result["atoms"]
        self.atom_spins = result["spins"]
        if swapped:
            # Le cache range les paires par Z croissant : on remet A à gauche.
            self.atom_levels = self.atom_levels[::-1]
            self.atom_spins = self.atom_spins[::-1]
            points = [dict(point) for point in points]
            for point in points:
                point["charge_a"] = -point["charge_a"]
                point["density"] = point["density"].reshape(GRID_NY, GRID_NX)[:, ::-1].ravel()
                point["difference"] = point["difference"].reshape(GRID_NY, GRID_NX)[:, ::-1].ravel()
        self.points = points
        self.r = np.array([p["r"] for p in points])
        self.energy = np.array([p["energy"] for p in points])
        self.curve = CubicSpline(self.r, self.energy)
        self.force = self.curve.derivative()
        fine = np.linspace(self.r[0], self.r[-1], 800)
        values = self.curve(fine)
        index = int(np.argmin(values))
        self.re = float(fine[index])
        self.de = float(-values[index])
        stiffness = float(self.curve.derivative(2)(self.re))
        self.bound = self.de > 0.05 and 0 < index < len(fine) - 1
        if self.bound and stiffness > 0:
            omega = math.sqrt(stiffness * ACCELERATION / self.reduced_mass)   # rad/fs
            self.period_fs = 2 * math.pi / omega
        else:
            self.period_fs = 0.0
        self.progress = 1.0
        self.status = "prêt"
        self.ready = True
        self.version += 1

    # --- lecture ---------------------------------------------------------------
    def energy_at(self, r):
        return float(self.curve(np.clip(r, self.r[0], self.r[-1]))) if r <= self.r[-1] else float(self.energy[-1])

    def force_at(self, r):
        """F = −dE/dR, en eV/Å. Au-delà de la dernière distance calculée, plus de force."""
        if r >= self.r[-1]:
            return 0.0
        return float(-self.force(max(r, self.r[0])))

    def nearest(self, r):
        return int(np.argmin(np.abs(self.r - r)))


# ----------------------------------------------------------------------
# Les noyaux bougent
# ----------------------------------------------------------------------
class Molecule:
    """Deux noyaux sur l'axe, mus par la vraie force quantique (Born-Oppenheimer)."""

    def __init__(self, za, zb):
        self.pair = Pair(za, zb)
        self.r = None
        self.velocity = 0.0
        self.held = True
        self.time_fs = 0.0
        self.announced = False

    def start(self):
        self.pair.start()

    def speed(self):
        """Femtosecondes simulées par seconde réelle : une vibration en ~3 s."""
        if self.pair.period_fs:
            return self.pair.period_fs / 3.0
        return 10.0

    def hold(self, r):
        if not self.pair.ready:
            return []
        self.r = float(np.clip(r, self.pair.r[0], self.pair.r[-1] * 1.6))
        self.velocity = 0.0
        self.held = True
        return []

    def release(self):
        if not self.pair.ready or self.r is None:
            return []
        self.held = False
        p = self.pair
        energy = p.energy_at(self.r)
        if energy < 0 and p.bound:
            return [{"kind": "bond", "title": f"Liaison : {p.symbols[0]}–{p.symbols[1]} reste lié et vibre",
                     "formula": f"E = {energy:.2f} eV sous les atomes séparés · puits De = {p.de:.2f} eV à "
                                f"Re = {p.re:.3f} Å · période {p.period_fs:.1f} fs",
                     "detail": "L'énergie totale est négative : les noyaux ne peuvent plus s'échapper. Ils "
                               "oscillent entre deux points de rebroussement, sur la vraie courbe E(R)."}]
        if energy >= 0 and p.bound:
            return [{"kind": "bond", "title": "Trop d'énergie : les atomes rebondissent et repartent",
                     "formula": f"E = +{energy:.2f} eV au-dessus des atomes séparés (puits : {p.de:.2f} eV)",
                     "detail": "Comprimés contre le mur répulsif, ils repartent avec plus d'énergie que le puits "
                               "ne peut en retenir. Pour former la molécule, il faudrait évacuer cette énergie "
                               "(un troisième atome, ou un photon émis)."}]
        return [{"kind": "bond", "title": f"Pas de liaison entre {p.symbols[0]} et {p.symbols[1]}",
                 "formula": "aucun puits sur la courbe E(R) : la force est répulsive partout",
                 "detail": "Leurs couches sont pleines : rapprocher les nuages ne fait que les repousser "
                           "(Pauli). C'est pourquoi l'hélium ne forme pas de molécule."}]

    def advance(self, real_dt):
        p = self.pair
        if not p.ready:
            return []
        events = []
        if self.r is None:
            # Les atomes arrivent l'un vers l'autre, lâchés au loin : la force décide du reste.
            self.r = float(min(p.r[-1] * 0.98, 2.2 * (p.re if p.bound else p.guess)))
            self.held = False
            events.append({"kind": "bond", "title": f"{p.names[0]} et {p.names[1]} : courbe E(R) calculée",
                           "formula": (f"puits De = {p.de:.2f} eV à Re = {p.re:.3f} Å · {METHOD}" if p.bound
                                       else f"aucun puits : pas de liaison · {METHOD}"),
                           "detail": f"électronégativités de Pauling : {p.chi[0]:.2f} et {p.chi[1]:.2f} "
                                     f"(Δχ = {abs(p.chi[0] - p.chi[1]):.2f}) — règle de manuel, donnée pour "
                                     f"comparaison ; c'est le calcul qui décide."})
        if self.held:
            return events
        simulated = real_dt * self.speed()
        steps = max(1, int(simulated / max(0.02, (p.period_fs or 20) / 200)))
        dt = simulated / steps
        for _ in range(steps):
            accel = p.force_at(self.r) * ACCELERATION / p.reduced_mass
            self.velocity += 0.5 * dt * accel
            self.r += dt * self.velocity
            if self.r < p.r[0] * 0.95:          # au-delà de la mesure, le mur est infini
                self.r = p.r[0] * 0.95
                self.velocity = abs(self.velocity)
            accel = p.force_at(self.r) * ACCELERATION / p.reduced_mass
            self.velocity += 0.5 * dt * accel
            self.time_fs += dt
        if self.r > p.r[-1] * 1.5:
            self.r = p.r[-1] * 1.5
            self.velocity = 0.0
            self.held = True
            events.append({"kind": "bond", "title": "Les atomes se sont séparés",
                           "formula": "au-delà de la dernière distance calculée, plus aucune force",
                           "detail": "Rapproche-les à la souris, puis lâche-les."})
        return events

    def kinetic_ev(self):
        return 0.5 * self.pair.reduced_mass * self.velocity ** 2 / ACCELERATION
