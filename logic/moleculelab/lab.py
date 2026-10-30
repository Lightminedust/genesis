"""Le laboratoire moléculaire : des noyaux qui bougent sous la force que leurs électrons calculent.

À chaque pas, GFN1-xTB résout les électrons de valence pour la position actuelle des noyaux (Born–Oppenheimer) et
rend l'énergie et la force exacte F = −∇E sur chaque noyau. Tout le reste en découle :

  relaxation   FIRE (Bitzek et al., PRL 97, 170201, 2006) : la molécule descend vers son minimum d'énergie ;
               le méthane devient tétraédrique, l'eau coudée, parce que c'est là que l'énergie est la plus basse.
               C'est le minimum le plus proche (une descente, en masses égales) : pour chercher une forme plus
               stable, chauffer au thermostat puis relaxer (recuit).
  dynamique    Verlet vitesses (pas de 0,25 fs, assez fin pour la vibration O–H de 9 fs) ; thermostat
               CSVR (Bussi, Donadio, Parrinello, JCP 126, 014101, 2007) ou énergie libre de toute contrainte.
  traction     un ressort k (x − cible) entre la souris et l'atome saisi : la liaison s'allonge, l'énergie monte,
               et elle casse quand le ressort fournit plus que l'énergie de liaison.
  étincelle    de l'énergie cinétique déposée sur un atome et ses voisins (quantité de mouvement conservée).
  réacteur     plusieurs molécules dans une sphère aux parois souples : ½ k (r − R)² au-delà de R.
  vibrations   la hessienne et ses modes (analysis.normal_modes) ; « exciter » un mode lui donne n quanta hν
               d'énergie, et la dynamique (anharmonique, exacte pour la méthode) prend le relais.

Les calculs tournent dans un fil à part ; le moteur lit le dernier instantané publié.
"""

import collections
import math
import threading
import time
import traceback

import numpy as np

from . import analysis, density, structures, validation, xtb

TIME_STEP_FS = 0.25
FIRE_DT_START, FIRE_DT_MAX = 0.25, 1.0
FIRE_ALPHA, FIRE_N_MIN = 0.1, 5
# FIRE est une descente, pas une trajectoire : ses masses n'ont pas de sens physique. La même masse fictive pour tous
# les atomes, celle de l'hydrogène, garde le pas stable et fait descendre l'or aussi vite que l'hydrogène (Au₆
# construit atome par atome : 307 pas au lieu de 1 396, même minimum).
FIRE_MASS = 1.008
RELAXED_FORCE = 1e-3            # Eh/Å : la relaxation s'arrête sous cette force maximale (0,026 eV/Å)
TETHER = 0.08                   # Eh/Å² : le ressort de la souris
WALL = 0.05                     # Eh/Å² : la paroi du réacteur
THERMOSTAT_FS = 50.0
HISTORY = 600
GRID_SECONDS = 0.2
PUBLISH_SECONDS = 1 / 40       # un instantané au plus tous les 25 ms : le client dessine à 60 images/s
# Rares depuis le confinement des anions (xtb.ANION_LIMIT) : une charge hors de toute borne malgré lui.
RUNAWAY = ("Le cycle auto-cohérent n'a pas d'état sensé à cette géométrie (une charge hors de toute borne, malgré le "
           "confinement des anions à −2,5 e) : GFN1-xTB ne la décrit pas. Déplacez un atome ou retirez-le.")
RUNAWAY_STOPPED = ("Arrêt sur le dernier état valide : au pas suivant, le cycle auto-cohérent n'avait plus d'état sensé "
                   "(une charge hors de toute borne, ou plus aucun point fixe, malgré le confinement des anions à −2,5 e et "
                   "le recuit). Refroidissez, relaxez ou déplacez un atome.")
CENSUS_HOLD_FS = 20.0           # une espèce doit durer 20 fs pour être annoncée
SPARK_RADIUS = 1.8              # Å
EV = xtb.HARTREE_EV
EH_PER_ANGSTROM_NN = 8.2387234983  # 1 Eh/Å = 8,24 nN
RELAX, THERMOSTAT, FREE = 0, 1, 2
DYNAMICS = ("relaxation", "thermostat", "libre")
PALETTE = (1, 5, 6, 7, 8, 9, 14, 15, 16, 17, 35, 53)
MAX_ATOMS = 40
PAIR_RANGE = 5.0                # Å : les paires dont on montre l'action mutuelle

_ATOM_ENERGY = {}


def atom_terms(z):
    """L'atome isolé et neutre (GFN1-xTB) : son énergie et ses termes (Eh) — la référence de l'énergie de liaison et
    de sa balance. Un atome seul n'a ni répulsion des cœurs, ni dispersion, ni liaison halogène."""
    if z not in _ATOM_ENERGY:
        r = xtb.Calculator([z]).compute(np.zeros((1, 3)), gradient=False)
        _ATOM_ENERGY[z] = {"energy": r.energy, "band": r.band, "coulomb": r.es2 + r.es3, "entropy": r.entropy}
    return _ATOM_ENERGY[z]


def atom_energy(z):
    """L'énergie GFN1-xTB de l'atome isolé et neutre (Eh) : la référence de l'énergie de liaison."""
    return atom_terms(z)["energy"]


class MoleculeLab:
    def __init__(self, rng_seed=0):
        self.rng = np.random.default_rng(rng_seed)
        self._lock = threading.Condition()
        self._commands = []
        self._snapshot = None
        self._grid = self._next_grid = None
        self._dirty = True
        self._closed = False
        # L'état de la simulation (fil de calcul seulement).
        self.label = ""
        self.numbers = []
        self.positions = np.zeros((0, 3))
        self.velocities = np.zeros((0, 3))
        self.masses = np.zeros(0)
        self.isotopes = []              # nombre de masse de chaque atome ; 0 : le mélange naturel
        self.charge = 0
        self.calc = None
        self.result = None
        self.dynamics = RELAX
        self.temperature = 300.0
        self.paused = False
        self.drag = None                # (atome, cible Å, centre de masse tenu des autres atomes)
        self.wall = 0.0                 # Å, 0 : pas de paroi
        self.view = (0, -1)             # (0 densité, 1 déformation, 2 orbitale ; indice d'orbitale, −1 : HOMO)
        self.potential_map = False      # la carte de potentiel est affichée : il lui faut la grille
        self._published = 0.0
        self.time_fs = 0.0
        self.steps = 0
        self.relaxed = False
        self.fire = None
        self.modes = None
        self.mode_job = False
        self.excited = -1
        self.history = collections.deque(maxlen=HISTORY)
        self.events = collections.deque(maxlen=14)
        self.census = ""
        self._candidate = ("", 0.0)
        self.status = "Préparation…"
        self.error = ""
        self.rate = 0.0
        self.grid_version = 0
        self._grid_time = 0.0
        self._grid_positions = None
        self._grid_view = None
        self._grid_coarse = False
        self.validation = None
        self._shares = None
        self._forces = None
        self._worker = threading.Thread(target=self._loop, daemon=True)
        self._worker.start()
        # Le tableau calculé ↔ mesuré : celui que ce moteur a calculé et que les tests revérifient ; à défaut, il est
        # recalculé dans son propre fil.
        self.validation = validation.stored()
        if self.validation is None:
            threading.Thread(target=self._validate, daemon=True).start()
        self.load_preset(1)

    def _validate(self):
        try:
            facts = validation.facts()
        except Exception:
            traceback.print_exc()
            return
        with self._lock:
            self.validation = facts
            if self._snapshot is not None:
                self._snapshot = dict(self._snapshot, validation=facts)
            self._dirty = True

    # ------------------------------------------------------------------ commandes (fil du moteur)
    def _post(self, name, *args):
        with self._lock:
            self._commands.append((name, args))
            self._lock.notify()

    def load_preset(self, index):
        self._post("preset", index)

    def load_reaction(self, index):
        self._post("reaction", index)

    def add_atom(self, z, anchor=-1, position=None):
        self._post("add", z, anchor, position)

    def remove_atom(self, index):
        self._post("remove", index)

    def drag_atom(self, index, target):
        self._post("drag", index, target)

    def release(self):
        self._post("release")

    def set_dynamics(self, mode, temperature=None):
        self._post("dynamics", mode, temperature)

    def spark(self, index, ev):
        self._post("spark", index, ev)

    def set_view(self, kind, orbital=-1, potential=False):
        """La vue : 0 densité, 1 déformation, 2 orbitale ; `potential` : la carte de potentiel est affichée."""
        self._post("view", kind, orbital, potential)

    def compute_modes(self):
        self._post("modes")

    def excite_mode(self, index, quanta):
        self._post("excite", index, quanta)

    def set_charge(self, charge):
        self._post("charge", charge)

    def set_isotope(self, index, mass_number):
        """L'isotope A d'un atome (index ≥ 0), ou de tous les atomes de l'élément −index ; A = 0 : le mélange naturel."""
        self._post("isotope", index, mass_number)

    def set_paused(self, paused):
        self._post("pause", paused)

    def clear(self):
        self._post("clear")

    def set_wall(self, radius):
        self._post("wall", radius)

    def take_dirty(self):
        with self._lock:
            dirty, self._dirty = self._dirty, False
            return dirty

    def snapshot(self):
        with self._lock:
            return self._snapshot, self._grid

    def close(self):
        with self._lock:
            self._closed = True
            self._lock.notify()

    # ------------------------------------------------------------------ le fil de calcul
    def _busy(self):
        if self.calc is None:
            return False
        if self.mode_job:
            return True
        if self.paused:
            return False
        return self.dynamics != RELAX or not self.relaxed or self.drag is not None

    def _grid_due(self):
        # La vue de la densité dessine le halo et les liaisons sans grille (le client calcule le nuage projeté) : la
        # grille ne sert qu'aux vues Δρ et ψ et à la carte de potentiel.
        if self.result is None or (self.view[0] == 0 and not self.potential_map):
            return False
        if self._grid_view != self.view or self._grid_positions is None:
            return True
        if len(self._grid_positions) != len(self.positions):
            return True
        if self._grid_coarse and not self._busy():
            return True                 # la molécule s'est arrêtée : la grille fine
        moved = float(np.max(np.abs(self._grid_positions - self.positions))) > 0.01
        return moved and time.perf_counter() - self._grid_time >= GRID_SECONDS

    def _loop(self):
        while True:
            with self._lock:
                while not self._closed and not self._commands and not self._busy() and not self._grid_due():
                    self._lock.wait(0.5)
                if self._closed:
                    return
                commands, self._commands = self._commands, []
            try:
                for name, args in commands:
                    getattr(self, "_do_" + name)(*args)
                stepped, relaxed_before = False, self.relaxed
                if self.mode_job:
                    self._normal_modes()
                elif self.calc is not None and not self.paused and (self.dynamics != RELAX or not self.relaxed
                                                                    or self.drag is not None):
                    started = time.perf_counter()
                    self._step()
                    stepped = True
                    elapsed = time.perf_counter() - started
                    self.rate = 0.8 * self.rate + 0.2 / max(elapsed, 1e-4) if self.rate else 1 / max(elapsed, 1e-4)
                gridded = self._grid_due()
                if gridded:
                    self._compute_grid()
                # Un instantané par image du client, pas un par pas : entre deux, les pas s'enchaînent. Tout ce qui
                # change l'état sans pas (une commande, l'arrêt de la relaxation, une grille) est publié aussitôt.
                now = time.perf_counter()
                if (commands or gridded or not stepped or self.relaxed != relaxed_before
                        or now - self._published >= PUBLISH_SECONDS):
                    self._publish()
                    self._published = now
            except Exception as failure:
                traceback.print_exc()
                self.error = f"Erreur de calcul : {failure}"
                self.paused = True
                self._publish()

    # ------------------------------------------------------------------ les commandes, appliquées
    def _set_structure(self, label, numbers, positions, keep_velocities=False):
        numbers = [int(z) for z in numbers]
        for z in numbers:
            if not xtb.supported(z):
                raise ValueError(f"{xtb.SYMBOLS[z]} n'est pas paramétré dans GFN1-xTB")
        positions = np.asarray(positions, float).reshape(-1, 3)
        old_velocities = self.velocities
        self.label = label
        self.numbers = numbers
        # L'isotope de chaque atome (nombre de masse ; 0 : le mélange naturel) suit les atomes ; une nouvelle
        # molécule repart du mélange naturel.
        if len(self.isotopes) != len(numbers):
            self.isotopes = [0] * len(numbers)
        self.masses = np.array([analysis.isotope_mass(z, a) for z, a in zip(numbers, self.isotopes)])
        if len(numbers):
            positions = positions - (self.masses @ positions) / self.masses.sum()
        self.positions = positions
        if keep_velocities and len(old_velocities) >= len(numbers) - 1:
            v = np.zeros_like(positions)
            n = min(len(old_velocities), len(numbers))
            v[:n] = old_velocities[:n]
            self.velocities = v
        else:
            self.velocities = np.zeros_like(positions)
        level = self.calc.level if self.calc is not None else 1.0
        self.calc = xtb.Calculator(numbers, charge=self.charge) if numbers else None
        if self.calc is not None:
            self.calc.level = level                # un atome de plus : le palier du recuit reste le bon point de départ
        self.result = None
        self.relaxed = False
        self.fire = None
        self.modes = None
        self.excited = -1
        self.drag = None
        self._forces = None
        self._shares = None
        self._grid_positions = None
        # Une autre molécule, d'autres orbitales : la vue ψ revient à sa HOMO.
        self.view = (self.view[0], -1)
        self.error = ""
        if self.calc is not None:
            self._evaluate()
        self.census = self._read_census()
        self._candidate = (self.census, self.time_fs)

    def _do_preset(self, index):
        name, numbers, positions = structures.preset(int(min(max(index, 0), len(structures.PRESETS) - 1)))
        self.isotopes = []
        # Un léger désordre (0,02 Å, l'agitation thermique) : partie d'une géométrie trop symétrique (H₂O₂ plan), la
        # relaxation resterait sur un col au lieu de trouver le vrai minimum.
        positions = positions + self.rng.normal(scale=0.02, size=positions.shape)
        self.wall = 0.0
        self.time_fs = 0.0
        self.history.clear()
        self.events.clear()
        self.dynamics = RELAX
        self._set_structure(name, numbers, positions)
        self.status = "Relaxation : la molécule cherche son minimum d'énergie"

    def _do_reaction(self, index):
        label, numbers, positions = structures.reaction(int(min(max(index, 0), len(structures.REACTIONS) - 1)))
        self.isotopes = []
        self.time_fs = 0.0
        self.history.clear()
        self.events.clear()
        centered = positions - positions.mean(axis=0)
        self.wall = float(np.max(np.linalg.norm(centered, axis=1))) + 1.5
        self.dynamics = THERMOSTAT
        self._set_structure(label, numbers, positions)
        self.velocities = self._thermal_velocities(self.temperature)
        self.status = f"Réacteur : {label} à {self.temperature:.0f} K"

    def _do_clear(self):
        self.isotopes = []
        self.time_fs = 0.0
        self.history.clear()
        self.events.clear()
        self.wall = 0.0
        self._set_structure("Établi vide", [], np.zeros((0, 3)))
        self.status = "Établi vide : ajoutez un atome"

    def _do_add(self, z, anchor, position):
        z = int(z)
        if not xtb.supported(z):
            raise ValueError(f"Élément {z} non paramétré dans GFN1-xTB")
        if len(self.numbers) >= MAX_ATOMS:
            self.error = f"Au plus {MAX_ATOMS} atomes : au-delà, le calcul ne suit plus en temps réel."
            return
        radius = _covalent(z)
        if position is not None and len(position) >= 3:
            spot = np.array(position[:3], float)
        elif not self.numbers:
            spot = np.zeros(3)
        else:
            a = int(anchor) if 0 <= int(anchor) < len(self.numbers) else int(np.argmin(
                np.linalg.norm(self.positions - self.positions.mean(axis=0), axis=1)))
            spot = self.positions[a] + (radius + _covalent(self.numbers[a])) * self._free_direction(a)
        numbers = self.numbers + [z]
        self.isotopes = self.isotopes + [0]
        positions = np.vstack([self.positions, spot])
        self._set_structure(structures.label(numbers), numbers, positions, keep_velocities=True)
        self.dynamics = RELAX if self.dynamics == FREE else self.dynamics
        self.history.clear()                  # d'autres atomes, une autre énergie : la courbe repart de zéro
        self.status = f"Atome ajouté ({xtb.SYMBOLS[z]}) : la géométrie se recalcule"

    def _free_direction(self, a):
        """La direction la plus éloignée des liaisons de l'atome a : celle qui maximise le plus petit angle avec elles.

        Un léger désordre (quelques degrés, comme l'agitation thermique) évite de poser l'atome sur un col d'énergie
        parfaitement symétrique (CH₄ plan carré), d'où la relaxation ne saurait pas de quel côté descendre.
        """
        d = self.positions - self.positions[a]
        r = np.linalg.norm(d, axis=1)
        near = (r > 1e-6) & (r < 2.2)
        if not near.any():
            others = r > 1e-6
            if not others.any():
                return np.array([1.0, 0.0, 0.0])
            near = others & (r <= r[others].min() + 1e-6)
        units = d[near] / r[near][:, None]
        directions = _SPHERE + self.rng.normal(scale=0.06, size=_SPHERE.shape)
        directions /= np.linalg.norm(directions, axis=1)[:, None]
        closest = np.max(directions @ units.T, axis=1)          # cosinus de l'angle avec la liaison la plus proche
        return directions[int(np.argmin(closest))]

    def _do_remove(self, index):
        index = int(index)
        if not 0 <= index < len(self.numbers):
            return
        numbers = self.numbers[:index] + self.numbers[index + 1:]
        self.isotopes = self.isotopes[:index] + self.isotopes[index + 1:]
        positions = np.delete(self.positions, index, axis=0)
        velocities = np.delete(self.velocities, index, axis=0)
        self.velocities = velocities
        self._set_structure(structures.label(numbers) if numbers else "Établi vide", numbers, positions,
                            keep_velocities=True)
        self.status = "Atome retiré : la géométrie se recalcule"

    def _do_drag(self, index, target):
        index = int(index)
        if 0 <= index < len(self.numbers) and target is not None and len(target) >= 3:
            grabbed = self.drag is None or self.drag[0] != index
            hold = self._rest_center(index) if grabbed else self.drag[2]
            self.drag = (index, np.array(target[:3], float), hold)
            if grabbed:
                self.status = "Traction : votre main tire l'atome, l'autre tient le reste par son centre de masse"
            self.relaxed = False

    def _rest_center(self, index):
        rest = np.arange(len(self.numbers)) != index
        if not rest.any():
            return None
        m = self.masses[rest]
        return (m @ self.positions[rest]) / m.sum()

    def _do_release(self):
        if self.drag is not None:
            self.status = "Relâché : les noyaux suivent à nouveau leurs seules forces"
        self.drag = None
        self.relaxed = False

    def _do_dynamics(self, mode, temperature):
        mode = int(mode)
        if mode not in (RELAX, THERMOSTAT, FREE):
            return
        if temperature is not None and temperature >= 0:
            self.temperature = float(min(max(temperature, 0.0), 6000.0))
        if mode == THERMOSTAT and self.dynamics != THERMOSTAT and np.allclose(self.velocities, 0):
            self.velocities = self._thermal_velocities(self.temperature)
        if mode == RELAX:
            self.relaxed = False
            self.fire = None
        self.dynamics = mode
        self.excited = -1 if mode != FREE else self.excited
        self.paused = False
        self.status = {RELAX: "Relaxation vers le minimum d'énergie",
                       THERMOSTAT: f"Dynamique à {self.temperature:.0f} K (thermostat)",
                       FREE: "Dynamique libre : l'énergie totale se conserve"}[mode]

    def _do_spark(self, index, ev):
        index = int(index)
        if not 0 <= index < len(self.numbers) or ev <= 0:
            return
        r = np.linalg.norm(self.positions - self.positions[index], axis=1)
        group = np.where(r <= SPARK_RADIUS)[0]
        kick = self.rng.normal(size=(len(group), 3))
        m = self.masses[group]
        if len(group) > 1:
            kick -= (m @ kick) / m.sum()          # quantité de mouvement du groupe nulle
        energy = analysis.kinetic_energy(m, kick)
        kick *= math.sqrt(ev / EV / energy)
        self.velocities[group] += kick
        if self.dynamics == RELAX:
            self.dynamics = FREE
        self.relaxed = False
        self.paused = False
        self.events.appendleft((self.time_fs, f"Étincelle : {ev:.1f} eV déposés autour de l'atome {index + 1}",
                                _potential(self.result)))

    def _do_view(self, kind, orbital, potential=False):
        self.view = (int(min(max(kind, 0), 2)), int(orbital))
        self.potential_map = bool(potential)

    def _do_modes(self):
        if self.calc is not None and len(self.numbers) >= 2:
            self.mode_job = True
            self.status = "Calcul de la hessienne (6N forces)…"

    def _do_excite(self, index, quanta):
        if self.modes is None or not 0 <= int(index) < len(self.modes[0]):
            return
        frequencies, displacements = self.modes
        index = int(index)
        quanta = float(min(max(quanta, 0.1), 50.0))
        self.velocities = analysis.mode_velocities(displacements[index], self.masses, frequencies[index], quanta)
        self.dynamics = FREE
        self.excited = index
        self.relaxed = False
        self.paused = False
        self.status = f"Mode {index + 1} excité ({frequencies[index]:.0f} cm⁻¹, {quanta:g} quanta) : dynamique libre"

    def _do_isotope(self, index, mass_number):
        """Un autre isotope : seule la masse change. La surface d'énergie (Born–Oppenheimer) et donc la géométrie restent
        les mêmes ; la dynamique et les vibrations changent (H₂O → D₂O : les élongations O–H descendent d'un facteur
        ≈ 0,73, la racine du rapport des masses réduites)."""
        index, mass_number = int(index), int(mass_number)
        targets = [index] if index >= 0 else [k for k, z in enumerate(self.numbers) if z == -index]
        targets = [k for k in targets if 0 <= k < len(self.numbers)]
        if not targets:
            return
        z = self.numbers[targets[0]]
        if mass_number and mass_number not in [a for a, *_ in analysis.isotopes(z)]:
            return
        for k in targets:
            self.isotopes[k] = mass_number
        self.masses = np.array([analysis.isotope_mass(n, a) for n, a in zip(self.numbers, self.isotopes)])
        self.modes = None
        self.excited = -1
        value = f"{self.masses[targets[0]]:.3f}".replace(".", ",")
        self.status = (f"Nouvelle masse : {value} u. La surface d'énergie ne change pas (Born–Oppenheimer) ; "
                       "la dynamique et les vibrations, si.")

    def _do_charge(self, charge):
        self.charge = int(min(max(charge, -2), 2))
        if self.numbers:
            self._set_structure(self.label, self.numbers, self.positions, keep_velocities=True)

    def _do_pause(self, paused):
        self.paused = bool(paused)

    def _do_wall(self, radius):
        self.wall = float(max(radius, 0.0))
        self.relaxed = False

    # ------------------------------------------------------------------ physique
    def _thermal_velocities(self, kelvin):
        if not len(self.numbers):
            return np.zeros((0, 3))
        sigma = np.sqrt(analysis.BOLTZMANN_EH * kelvin * analysis.ACCELERATION / self.masses)
        v = self.rng.normal(size=(len(self.numbers), 3)) * sigma[:, None]
        v -= (self.masses @ v) / self.masses.sum()
        return v

    def _external(self, positions):
        """Forces (Eh/Å) et énergies du ressort de la souris et de la paroi."""
        force = np.zeros_like(positions)
        e_tether = e_wall = 0.0
        tether = np.zeros(3)
        if self.drag is not None:
            # Une main tire l'atome saisi vers la souris ; l'autre tient le reste de la molécule par son centre de
            # masse (sans elle, la molécule entière suivrait l'atome et aucune liaison ne s'étirerait).
            index, target, hold = self.drag
            pull = TETHER * (target - positions[index])
            force[index] += pull
            tether = pull
            e_tether = 0.5 * TETHER * float(np.sum((target - positions[index]) ** 2))
            if hold is not None:
                rest = np.arange(len(positions)) != index
                m = self.masses[rest]
                offset = hold - (m @ positions[rest]) / m.sum()
                force[rest] += TETHER * offset * (m / m.sum())[:, None]
                e_tether += 0.5 * TETHER * float(offset @ offset)
        if self.wall > 0:
            r = np.linalg.norm(positions, axis=1)
            out = r > self.wall
            if out.any():
                excess = r[out] - self.wall
                force[out] -= (WALL * excess / r[out])[:, None] * positions[out]
                e_wall = 0.5 * WALL * float(np.sum(excess ** 2))
        return force, e_tether, e_wall, tether

    def _evaluate(self):
        self.result = self.calc.compute(self.positions, pairs=True, moments=True)
        if self.result.unstable:
            self.error = RUNAWAY
        elif self.error in (RUNAWAY, RUNAWAY_STOPPED):
            self.error = ""
        external, e_tether, e_wall, tether = self._external(self.positions)
        self._forces = -self.result.gradient + external
        self._external_parts = (external, e_tether, e_wall, tether)
        return self._forces

    def _step(self):
        # Si le cycle auto-cohérent s'emballe pendant un pas (GFN1 n'a plus d'état sensé : son terme du troisième ordre
        # n'est pas borné, et un atome isolé peut y prendre des électrons sans fin — Pm₄ dissocié : −9 e, dxtb aussi),
        # les forces n'ont plus de sens : on revient au dernier état valide et on s'arrête, plutôt que d'intégrer des
        # forces absurdes.
        saved = (self.positions.copy(), self.velocities.copy(), self.result, self._forces, self._external_parts,
                 self.time_fs, self.steps)
        if self.dynamics == RELAX:
            self._fire_step()
        else:
            self._verlet_step()
        bad = self.result is not None and (self.result.unstable or not self.result.converged)
        if bad and saved[2] is not None and not saved[2].unstable and saved[2].converged:
            (self.positions, self.velocities, self.result, self._forces, self._external_parts, self.time_fs,
             self.steps) = saved
            self.paused = True
            self.fire = None
            self.error = RUNAWAY_STOPPED
            return
        self._record()

    def _verlet_step(self):
        dt = TIME_STEP_FS
        if self._forces is None:
            self._evaluate()
        acc = self._forces / self.masses[:, None] * analysis.ACCELERATION
        self.velocities = self.velocities + 0.5 * dt * acc
        self.positions = self.positions + dt * self.velocities
        forces = self._evaluate()
        acc = forces / self.masses[:, None] * analysis.ACCELERATION
        self.velocities = self.velocities + 0.5 * dt * acc
        if self.dynamics == THERMOSTAT:
            self._thermostat(dt)
        self.time_fs += dt
        self.steps += 1

    def _thermostat(self, dt):
        """CSVR : l'énergie cinétique suit la loi canonique à la température demandée."""
        n = len(self.numbers)
        dof = max(3 * n - 3, 1)
        kinetic = analysis.kinetic_energy(self.masses, self.velocities)
        target = 0.5 * dof * analysis.BOLTZMANN_EH * self.temperature
        if kinetic <= 1e-14:
            if target > 0:
                self.velocities = self._thermal_velocities(self.temperature)
            return
        c = math.exp(-dt / THERMOSTAT_FS)
        r1 = self.rng.normal()
        rest = self.rng.chisquare(dof - 1) if dof > 1 else 0.0
        new = (kinetic * c + target / dof * (1 - c) * (r1 * r1 + rest)
               + 2 * r1 * math.sqrt(c * (1 - c) * kinetic * target / dof))
        self.velocities *= math.sqrt(max(new, 0.0) / kinetic)

    def _fire_step(self):
        if self.fire is None:
            self.fire = {"dt": FIRE_DT_START, "alpha": FIRE_ALPHA, "positive": 0}
            self.velocities = np.zeros_like(self.positions)
        state = self.fire
        forces = self._forces if self._forces is not None else self._evaluate()
        power = float(np.sum(forces * self.velocities))
        if power > 0:
            fnorm = np.linalg.norm(forces)
            vnorm = np.linalg.norm(self.velocities)
            self.velocities = (1 - state["alpha"]) * self.velocities + state["alpha"] * vnorm * forces / max(fnorm, 1e-12)
            state["positive"] += 1
            if state["positive"] > FIRE_N_MIN:
                state["dt"] = min(state["dt"] * 1.1, FIRE_DT_MAX)
                state["alpha"] *= 0.99
        else:
            self.velocities[:] = 0.0
            state["dt"] *= 0.5
            state["alpha"] = FIRE_ALPHA
            state["positive"] = 0
        dt = state["dt"]
        self.velocities = self.velocities + dt * forces / FIRE_MASS * analysis.ACCELERATION
        step = dt * self.velocities
        longest = float(np.max(np.linalg.norm(step, axis=1)))
        if longest > 0.2:
            step *= 0.2 / longest
        self.positions = self.positions + step
        forces = self._evaluate()
        self.steps += 1
        if self.drag is None and float(np.max(np.linalg.norm(forces, axis=1))) < RELAXED_FORCE:
            self.relaxed = True
            self.velocities[:] = 0.0
            self.fire = None
            self.status = "Minimum atteint : les forces s'annulent sur chaque noyau"

    def _record(self):
        r = self.result
        kinetic = analysis.kinetic_energy(self.masses, self.velocities) if len(self.numbers) else 0.0
        self.history.append((self.time_fs if self.dynamics != RELAX else float(self.steps),
                             r.energy * EV, kinetic * EV))
        census = self._read_census()
        if census != self._candidate[0]:
            self._candidate = (census, self.time_fs)
        elif census != self.census and (self.time_fs - self._candidate[1] >= CENSUS_HOLD_FS or self.dynamics == RELAX):
            text = f"{self.census or '∅'}  →  {census}"
            self.events.appendleft((self.time_fs, text, r.energy * EV))
            self.census = census

    def _read_census(self):
        if self.result is None or not self.numbers:
            return ""
        orders = analysis.mayer_bond_orders(self.result)
        return analysis.census(analysis.fragments(self.numbers, orders, self.result))

    def _normal_modes(self):
        self.mode_job = False
        if self.calc is None or len(self.numbers) < 2:
            return
        frequencies, displacements, _ = analysis.normal_modes(self.calc, self.positions, self.masses)
        self.modes = (frequencies, displacements)
        self._evaluate()
        imaginary = int(np.sum(frequencies < 0))
        note = "" if self.relaxed else " (géométrie hors minimum : fréquences approchées)"
        self.status = (f"{len(frequencies)} modes de vibration calculés" + note +
                       (f" · {imaginary} imaginaire(s) : point selle" if imaginary else ""))

    def _compute_grid(self):
        kind, orbital = self.view
        r = self.result
        if kind == 2:
            homo = _homo(r)
            orbital = homo if orbital < 0 else orbital
        moving = self._busy() and not self.mode_job
        points = density.MOVING_POINTS if moving else density.MAX_POINTS
        if kind == 2:
            points = density.ORBITAL_MOVING_POINTS if moving else density.ORBITAL_POINTS
        bonds = bonds_peak = esp = esp_peak = None
        if kind == 0:
            origin, spacing, counts, values, overlap = density.compute_split(r, points)
            bonds, bonds_peak = density.encode(overlap, signed=True)
            valence = np.bincount(r.basis.shell_atom, weights=r.basis.refocc, minlength=len(self.numbers))
            potential = density.electrostatic_potential(values + overlap, origin, spacing,
                                                        r.positions * xtb.BOHR_ANGSTROM, valence)
            esp, esp_peak = density.encode(np.clip(potential, -density.ESP_LIMIT, density.ESP_LIMIT), signed=True)
        else:
            origin, spacing, counts, values = density.compute(r, kind, orbital, points)
        raw, peak = density.encode(values, signed=kind != 0)
        self.grid_version += 1
        self._grid_time = time.perf_counter()
        self._grid_positions = self.positions.copy()
        self._grid_view = self.view
        self._grid_coarse = moving
        grid = {"version": self.grid_version, "kind": kind, "orbital": orbital if kind == 2 else -1,
                "origin": origin, "spacing": spacing, "counts": counts, "data": raw, "peak": peak,
                "bonds": bonds, "bonds_peak": bonds_peak, "esp": esp, "esp_peak": esp_peak,
                "positions": self.positions.copy(), "parts": density.orbital_parts(r, orbital) if kind == 2 else []}
        # Publiée avec l'instantané qui suit (et qui dit la vue de cette grille), jamais avant : sinon un lecteur peut
        # prendre la grille ψ avec l'instantané de la vue Δρ.
        self._next_grid = grid

    def _pair_shares(self, result, bonds):
        """Les paires localisées : la part de chaque liaison à deux centres et les paires à plusieurs centres.

        La localisation (jusqu'à une centaine de millisecondes pour un agrégat d'or) est refaite au plus deux fois par
        seconde pendant que les atomes bougent ; entre-temps, le classement est refait sur les paires gardées (il ne
        coûte rien) et chaque barycentre suit ses atomes, pondérés par leur part de la paire. Avec elle, le profil des
        paires de chaque liaison le long de son axe (density.pair_profiles), d'où le client tire ses grains.
        """
        now = time.perf_counter()
        cached = self._shares
        if cached is None or cached[0] != tuple(self.numbers) or (now - cached[1] >= 0.5 and not (self.relaxed and cached[5])):
            populations, centroids, orbitals = analysis.localize(result, orbitals=True)
            members = analysis.classify_pairs(populations, centroids, [])[3]
            profiles = density.pair_profiles(result, orbitals, members)
            cached = (tuple(self.numbers), now, populations, centroids, self.positions.copy(), self.relaxed, profiles)
            self._shares = cached
        _, _, populations, centroids, then, _, profiles = cached
        shares, _, shared, _ = analysis.classify_pairs(populations, centroids + populations @ (self.positions - then),
                                                       bonds)
        return shares, shared, profiles

    # ------------------------------------------------------------------ l'instantané publié
    def _publish(self):
        snap = {"label": self.label, "numbers": list(self.numbers), "positions": self.positions.copy(),
                "dynamics": self.dynamics, "temperature_target": self.temperature, "paused": self.paused,
                "time_fs": self.time_fs, "steps": self.steps, "relaxed": self.relaxed, "status": self.status,
                "error": self.error, "rate": self.rate, "charge": self.charge, "wall": self.wall,
                "census": self.census, "events": list(self.events), "history": list(self.history),
                "view": self.view, "drag": None if self.drag is None else (self.drag[0], self.drag[1].copy()),
                "excited": self.excited, "computing_modes": self.mode_job, "validation": self.validation}
        if self.modes is not None:
            snap["frequencies"] = self.modes[0]
            snap["displacements"] = self.modes[1]
        r = self.result
        if r is not None and self.numbers:
            parts = r.gradient_parts
            external, e_tether, e_wall, tether = self._external_parts
            orders = analysis.mayer_bond_orders(r)
            bonds = []
            n = len(self.numbers)
            for a in range(n):
                for b in range(a + 1, n):
                    if orders[a, b] >= 0.05:
                        # σ, π, et où se tient la part π autour de l'axe (les lobes : direction, part de chacune).
                        sigma, pi, pi_axis, pi_split = (analysis.bond_symmetry(r, a, b, directions=True)
                                                        if orders[a, b] >= 0.3 else (orders[a, b], 0.0, None, [0.0, 0.0]))
                        bonds.append([a, b, float(orders[a, b]),
                                      float(np.linalg.norm(self.positions[a] - self.positions[b])), sigma, pi, -1.0,
                                      [] if pi_axis is None else pi_axis.tolist(), pi_split, [], 0.0])
            # Ce que les deux atomes partagent encore entre eux : le trait d'une liaison rompue s'éteint, même quand
            # l'indice de Mayer (calcul à couches fermées) reste à 1 d'un fragment à l'autre.
            for bond, value in zip(bonds, analysis.shared_density(r, [(bond[0], bond[1]) for bond in bonds]).values()):
                bond[10] = value
            # Comment chaque liaison partage sa paire d'électrons (orbitales localisées) : recalculé au plus deux fois
            # par seconde pendant la dynamique (la localisation coûte quelques dizaines de millisecondes). Une liaison
            # sans paire à deux centres est tenue par des paires à plusieurs centres (agrégat métallique, pont B–H–B)
            # ou par l'attraction de deux ions (NaCl : la paire reste sur Cl).
            # Dès 0,1 : le client fait s'éteindre le trait d'une liaison qui s'étire au fil de son indice.
            shares, shared, profiles = self._pair_shares(r, [(b[0], b[1]) for b in bonds if b[2] >= 0.1])
            for bond in bonds:
                bond[6] = shares.get((bond[0], bond[1]), -1.0)
                profile = profiles.get((bond[0], bond[1])) if bond[6] >= 0 else None
                bond[9] = [] if profile is None else profile.tolist()
            kinetic = analysis.kinetic_energy(self.masses, self.velocities)
            dof = max(3 * n - 3, 1)
            reference = sum(atom_energy(z) for z in self.numbers)
            homo = _homo(r)
            polarization = analysis.atomic_dipoles(r)
            # Ce que chaque paire proche se fait (+ : elle s'attire), le long de son axe, et la force entière qu'elle
            # exerce sur son premier atome (le reste, de travers, tient les angles). Seules partent les paires liées
            # ou dont la force atteint 0,02 eV/Å (en deçà, ni la scène ni la fiche ne la montrent).
            first, second = np.triu_indices(n, 1)
            separation = self.positions[second] - self.positions[first]
            distance = np.linalg.norm(separation, axis=1)
            near = distance <= PAIR_RANGE
            first, second, separation, distance = first[near], second[near], separation[near], distance[near]
            axis = separation / distance[:, None]
            pull = {k: -np.einsum("pk,pk->p", g[first, second], axis) * EV for k, g in r.pair_gradient.items()}
            total = pull["électronique"] + pull["répulsion"] + pull["dispersion"]
            # La part des électrons, en deux : le recouvrement des orbitales et l'électrostatique des charges ; et la
            # population de recouvrement (< 0 : antiliant) — de quoi dire pourquoi deux atomes s'attirent ou se poussent.
            split = {k: -np.einsum("pk,pk->p", g[first, second], axis) * EV for k, g in r.pair_split.items()}
            population = analysis.overlap_populations(r)[first, second]
            push = -sum(g[first, second] for g in r.pair_gradient.values()) * EV
            kept = ((np.abs(total) >= 0.02) | (np.linalg.norm(push, axis=1) >= 0.02)
                    | (orders[first, second] >= 0.05))
            pairs = list(zip(first[kept].tolist(), second[kept].tolist(), distance[kept].tolist(),
                             orders[first, second][kept].tolist(), pull["électronique"][kept].tolist(),
                             pull["répulsion"][kept].tolist(), pull["dispersion"][kept].tolist(), total[kept].tolist(),
                             push[kept].tolist(), split["recouvrement"][kept].tolist(), split["charges"][kept].tolist(),
                             population[kept].tolist()))
            forces = {"électronique": -parts["électronique"] * EV, "répulsion": -parts["répulsion"] * EV,
                      "dispersion": -parts["dispersion"] * EV, "halogène": -parts["halogène"] * EV,
                      "externe": external * EV}
            snap.update({
                "energy": r.energy * EV, "band": r.band * EV, "es2": r.es2 * EV, "es3": r.es3 * EV,
                "repulsion": r.repulsion * EV, "dispersion": r.dispersion * EV, "halogen": r.halogen * EV,
                "entropy": r.entropy * EV, "binding": (r.energy - reference) * EV, "kinetic": kinetic * EV,
                # Les mêmes termes pour les atomes isolés : la balance de la liaison (ce qui lie, ce qui coûte).
                "band_atoms": sum(atom_terms(z)["band"] for z in self.numbers) * EV,
                "coulomb_atoms": sum(atom_terms(z)["coulomb"] for z in self.numbers) * EV,
                "entropy_atoms": sum(atom_terms(z)["entropy"] for z in self.numbers) * EV,
                "tether_energy": e_tether * EV, "wall_energy": e_wall * EV,
                "tether_force_nn": float(np.linalg.norm(tether)) * EH_PER_ANGSTROM_NN,
                "temperature": 2 * kinetic / (dof * analysis.BOLTZMANN_EH),
                "charges": r.atom_charges.copy(), "cn": r.cn.copy(), "forces": forces,
                "total_force": (-r.gradient + external) * EV,
                "bonds": bonds, "multicentres": [(atoms, pops, centroid.tolist()) for atoms, pops, centroid in shared],
                "orbital_energies": r.orbital_energies * EV, "occupations": r.occupations.copy(),
                "homo": homo, "dipole": analysis.dipole(r, polarization), "polarization": polarization,
                "converged": r.converged, "iterations": r.iterations,
                "electronic_temperature": r.electronic_temperature, "confined": r.confined.tolist(),
                "fragments": analysis.fragments(self.numbers, orders, r), "unstable": r.unstable, "pairs": pairs,
                "hardness": [xtb.element(z)["gam"] for z in self.numbers], "isotopes": list(self.isotopes),
                "covalent": [analysis.covalent_radius(z) for z in self.numbers],
                "masses": self.masses.copy(),
            })
        with self._lock:
            self._snapshot = snap
            if self._next_grid is not None:
                self._grid, self._next_grid = self._next_grid, None
            self._dirty = True


def _potential(result):
    return result.energy * EV if result is not None else 0.0


def _fibonacci(count):
    k = np.arange(count) + 0.5
    polar = np.arccos(1 - 2 * k / count)
    azimuth = np.pi * (1 + 5 ** 0.5) * k
    return np.stack([np.cos(azimuth) * np.sin(polar), np.sin(azimuth) * np.sin(polar), np.cos(polar)], axis=1)


_SPHERE = _fibonacci(400)


def _homo(result):
    occupied = np.where(result.occupations > 0.5)[0]
    return int(occupied[-1]) if len(occupied) else 0


def _covalent(z):
    """Rayon covalent (Å) tiré des rayons D3 (×3/4, qui annule leur mise à l'échelle de 4/3)."""
    return float(xtb.tables()["cov_d3"][z]) * 0.75 * xtb.BOHR_ANGSTROM
