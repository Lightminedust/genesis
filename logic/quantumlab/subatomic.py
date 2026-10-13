"""Few-body effective nucleon dynamics for the separate subatomic laboratory.

Positions are in fm, time in fm/c, masses in MeV/c² and energies in MeV.
This central potential is a calibrated teaching model, not a QCD calculation
or a prediction of nuclear levels. Electrons are represented by an orbital
density at atomic scale, not by classical paths through the nucleus.
"""
import math
from itertools import count

import numpy as np
from scipy import constants as sc
from scipy.optimize import minimize

HBAR_C = sc.hbar * sc.c / (1e6 * sc.e * 1e-15)
COULOMB = sc.e / (4 * math.pi * sc.epsilon_0 * 1e-15 * 1e6)
PROTON_MEV = sc.physical_constants['proton mass energy equivalent in MeV'][0]
NEUTRON_MEV = sc.physical_constants['neutron mass energy equivalent in MeV'][0]
BOHR_PM = sc.physical_constants['Bohr radius'][0] * 1e12
ELECTRON_MEV = sc.physical_constants['electron mass energy equivalent in MeV'][0]
RYDBERG_EV = sc.h * sc.c * sc.Rydberg / sc.e

REP_RANGE_FM = 0.38
ATT_RANGE_FM = 1.35
EQUILIBRIUM_FM = 1.8
WELL_DEPTH_MEV = 8.0
_shape = lambda r, scale: math.exp(-r / scale) * (1 / r**2 + 1 / (scale * r))
_ratio = _shape(EQUILIBRIUM_FM, ATT_RANGE_FM) / _shape(EQUILIBRIUM_FM, REP_RANGE_FM)
ATT_STRENGTH = WELL_DEPTH_MEV * EQUILIBRIUM_FM / (
    math.exp(-EQUILIBRIUM_FM / ATT_RANGE_FM)
    - _ratio * math.exp(-EQUILIBRIUM_FM / REP_RANGE_FM))
REP_STRENGTH = _ratio * ATT_STRENGTH


def nuclear_pair(r, unlike=True):
    """Return potential (MeV), radial outward force (MeV/fm).

    F=-dV/dr includes the 1/(lambda*r) term missing when one differentiates
    a Yukawa potential as though its exponential were constant.
    """
    if not math.isfinite(r) or r <= 0:
        raise ValueError('La séparation doit être strictement positive')
    attraction = ATT_STRENGTH if unlike else 0.72 * ATT_STRENGTH
    rep = REP_STRENGTH * math.exp(-r / REP_RANGE_FM)
    att = attraction * math.exp(-r / ATT_RANGE_FM)
    potential = (rep - att) / r
    force = rep * (1 / r**2 + 1 / (REP_RANGE_FM * r)) - att * (
        1 / r**2 + 1 / (ATT_RANGE_FM * r))
    if not unlike:
        # Effective positive kinetic pressure in the spin-averaged identical
        # channel. It prevents a classical pp/nn false bound state; spin and
        # tensor channels are deliberately outside this small model.
        reduced = PROTON_MEV / 2
        coefficient = HBAR_C**2 / (2 * reduced)
        potential += coefficient / r**2
        force += 2 * coefficient / r**3
    return potential, force


class SubatomicExperiment:
    _run_numbers = count(1)
    MAX_NUCLEONS = 12
    DT_FMC = 0.025
    SUBSTEPS = 8
    FRAMES = 520

    def __init__(self):
        self.protons = 1
        self.neutrons = 1
        self.electrons = 0
        self.impulse_mev = 0.0
        self.run = 0
        self.samples = []
        self.max_energy_error = 0.0
        self.calculate()

    @property
    def electron_radius_pm(self):
        if not self.electrons or not self.protons:
            return 0.0
        # Hydrogen 1s exact for a single electron; Z_eff is a Slater estimate
        # for the second 1s electron, not a Pauli force between e and p.
        z_eff = self.protons - (0.3 if self.electrons > 1 else 0)
        mass_nucleus = self.protons * PROTON_MEV + self.neutrons * NEUTRON_MEV
        return BOHR_PM * (1 + ELECTRON_MEV / mass_nucleus) / max(z_eff, 0.7)

    @property
    def electron_binding_ev(self):
        if not self.electrons or not self.protons:
            return 0.0
        mass_nucleus = self.protons * PROTON_MEV + self.neutrons * NEUTRON_MEV
        reduced_factor = mass_nucleus / (mass_nucleus + ELECTRON_MEV)
        z_eff = self.protons - (0.3 if self.electrons > 1 else 0)
        return self.electrons * RYDBERG_EV * z_eff*z_eff * reduced_factor

    def set_counts(self, protons=None, neutrons=None, electrons=None):
        p = self.protons if protons is None else int(protons)
        n = self.neutrons if neutrons is None else int(neutrons)
        e = self.electrons if electrons is None else int(electrons)
        if p < 0 or n < 0 or p + n > self.MAX_NUCLEONS or e < 0 or e > 2 or (e and p == 0):
            raise ValueError('Choisir 1 à 12 nucléons et 0 à 2 électrons liés à au moins un proton')
        if p == self.protons and n == self.neutrons:
            # The orbital density is a separate stationary approximation.
            # Changing its occupation must not restart nuclear dynamics.
            self.electrons = e
            return
        previous = self.protons, self.neutrons, self.electrons
        self.protons, self.neutrons, self.electrons = p, n, e
        try:
            self.calculate()
        except ValueError:
            self.protons, self.neutrons, self.electrons = previous
            raise

    def set_impulse(self, mev):
        if not math.isfinite(mev) or mev < 0 or mev > 30:
            raise ValueError('Énergie initiale admise : 0 à 30 MeV')
        previous = self.impulse_mev
        self.impulse_mev = float(mev)
        try:
            self.calculate()
        except ValueError:
            self.impulse_mev = previous
            raise

    def initial_state(self, optimize=True):
        kinds = np.array([1] * self.protons + [0] * self.neutrons, dtype=np.int8)
        count = len(kinds)
        mass = np.where(kinds == 1, PROTON_MEV, NEUTRON_MEV)
        pos = np.zeros((count, 3), dtype=np.float64)
        vel = np.zeros_like(pos)
        if count == 2:
            separation = 2.8
            pos[0, 0] = -separation * mass[1] / mass.sum()
            pos[1, 0] = separation * mass[0] / mass.sum()
            if self.impulse_mev:
                reduced = mass[0] * mass[1] / mass.sum()
                relative = math.sqrt(2 * self.impulse_mev / reduced)
                vel[0, 0] = relative * mass[1] / mass.sum()
                vel[1, 0] = -relative * mass[0] / mass.sum()
        elif count > 2:
            # Deterministic three-dimensional seed; the subsequent positions
            # are integrated, never animated along these seed coordinates.
            for i in range(count):
                angle = i * math.pi * (3 - math.sqrt(5))
                y = 1 - 2 * (i + 0.5) / count
                radius = 1.65 * (count / 2)**(1 / 3)
                pos[i] = radius * np.array([math.sqrt(1 - y*y) * math.cos(angle),
                                             y, math.sqrt(1 - y*y) * math.sin(angle)])
            pos -= np.average(pos, axis=0, weights=mass)
            if optimize and self.protons and self.neutrons:
                # Choose a low-energy starting geometry using the same force
                # law as Verlet. This is initialization, not damping in time.
                initial_energy = sum(self.forces(kinds, pos)[1:3])

                def objective(flat):
                    force, strong, coulomb, _ = self.forces(kinds, flat.reshape(-1, 3))
                    return strong + coulomb, -force.ravel()

                solution = minimize(objective, pos.ravel(), jac=True, method='L-BFGS-B',
                                    options={'maxiter': 240, 'ftol': 1e-12})
                candidate = solution.x.reshape(-1, 3)
                if (np.all(np.isfinite(candidate)) and solution.fun < initial_energy
                        and np.linalg.norm(solution.jac) < .05
                        and np.max(np.linalg.norm(candidate, axis=1)) < 12):
                    pos = candidate.copy()
                    pos[0] += (.18, -.07, .03)
                    pos -= np.average(pos, axis=0, weights=mass)
            if self.impulse_mev:
                vel[0, 0] = math.sqrt(2 * self.impulse_mev / mass[0])
                vel -= np.average(vel, axis=0, weights=mass)
        return kinds, mass, pos, vel

    @staticmethod
    def forces(kinds, pos):
        n = len(kinds)
        force = np.zeros((n, 3), dtype=np.float64)
        strong = coulomb = 0.0
        pairs = []
        for i in range(n):
            for j in range(i + 1, n):
                delta = pos[i] - pos[j]
                r = float(np.linalg.norm(delta))
                if r < 0.12:
                    raise ValueError('Pas de temps trop grand : noyaux superposés')
                potential, radial = nuclear_pair(r, kinds[i] != kinds[j])
                strong += potential
                electromagnetic = 0.0
                pairs.append((i, j, r, potential, radial,
                              COULOMB/r if kinds[i] and kinds[j] else 0.0))
                if kinds[i] and kinds[j]:
                    electromagnetic = COULOMB / r
                    coulomb += electromagnetic
                    radial += electromagnetic / r
                pair = radial * delta / r
                force[i] += pair
                force[j] -= pair
        return force, strong, coulomb, pairs

    @staticmethod
    def binding_diagnostic(mass, vel, pairs):
        """Energy needed to separate one body from the frozen remainder.

        For two bodies this is exactly minus the relative energy and hence an
        exact bound/scattering threshold for this conservative central model.
        With more bodies it is only an instantaneous channel diagnostic.
        """
        if len(mass) < 2:
            return 0.0, 0
        margins = []
        total_mass = float(mass.sum())
        for i in range(len(mass)):
            other_mass = total_mass - mass[i]
            other_velocity = (mass[:, None] * vel).sum(axis=0) - mass[i] * vel[i]
            other_velocity /= other_mass
            reduced = mass[i] * other_mass / total_mass
            relative_kinetic = 0.5 * reduced * float(np.sum((vel[i] - other_velocity)**2))
            attachment = sum(p[3] + p[5] for p in pairs if p[0] == i or p[1] == i)
            margins.append(-relative_kinetic - attachment)
        return float(min(margins)), sum(margin > 0 for margin in margins)

    def calculate(self, seed=None):
        kinds, mass, pos, vel = self.initial_state(optimize=seed is None)
        if seed is not None:
            pos, vel = (np.array(part, dtype=np.float64, copy=True) for part in seed)
            if pos.shape != (len(kinds), 3) or vel.shape != pos.shape:
                raise ValueError('État initial de dimensions incorrectes')
        acceleration, strong, coulomb, pairs = self.forces(kinds, pos)
        acceleration /= mass[:, None]
        energy0 = None
        samples = []
        max_energy_error = 0.0
        for frame in range(self.FRAMES):
            kinetic = float(np.sum(0.5 * mass[:, None] * vel**2))
            total = kinetic + strong + coulomb
            if energy0 is None:
                energy0 = total
            max_energy_error = max(max_energy_error, abs(total - energy0))
            separation = radial_force = 0.0
            if len(kinds) == 2:
                delta = pos[0] - pos[1]
                separation = float(np.linalg.norm(delta))
                radial_force = float(np.dot(acceleration[0] * mass[0], delta / separation))
            binding_margin, bound_count = self.binding_diagnostic(mass, vel, pairs)
            samples.append(dict(time_fmc=frame * self.SUBSTEPS * self.DT_FMC,
                                positions=pos.copy(), velocities=vel.copy(),
                                kinetic=kinetic, strong=strong, coulomb=coulomb, total=total,
                                separation=separation, radial_force=radial_force,
                                pairs=pairs, binding_margin=binding_margin,
                                bound_count=bound_count))
            for _ in range(self.SUBSTEPS):
                pos += vel * self.DT_FMC + 0.5 * acceleration * self.DT_FMC**2
                next_force, strong, coulomb, pairs = self.forces(kinds, pos)
                next_acceleration = next_force / mass[:, None]
                vel += 0.5 * (acceleration + next_acceleration) * self.DT_FMC
                acceleration = next_acceleration
        self.kinds = kinds
        self.mass = mass
        self.samples = samples
        self.max_energy_error = max_energy_error
        self.profile_r = np.linspace(.5, 7.0, 190)
        like = len(kinds) == 2 and kinds[0] == kinds[1]
        self.profile_v = np.array([
            nuclear_pair(float(r), not like)[0] + (COULOMB/r if like and kinds[0] else 0)
            for r in self.profile_r])
        self.run = next(self._run_numbers)

    def perturb(self, frame_index, particle_index, delta_velocity):
        """Restart Verlet from a sampled state after a user-applied impulse."""
        if not 0 <= frame_index < len(self.samples) or not 0 <= particle_index < len(self.kinds):
            raise ValueError('Particule ou instant inconnu')
        impulse = np.asarray(delta_velocity, dtype=np.float64)
        if impulse.shape != (3,) or not np.all(np.isfinite(impulse)) or np.linalg.norm(impulse) > .18:
            raise ValueError('Impulsion vectorielle hors limites (0,18 c maximum)')
        sample = self.samples[frame_index]
        positions = sample['positions'].copy()
        velocities = sample['velocities'].copy()
        velocities[particle_index] += impulse
        self.calculate(seed=(positions, velocities))

    def summary(self):
        first = self.samples[0]
        pn = len(self.kinds) == 2 and self.protons == self.neutrons == 1
        status = 'p + n : paire liée du modèle' if pn and first['total'] < 0 else (
            'p + n : diffusion' if pn else 'système effectif : trajectoires calculées')
        return dict(status=status, energy_error_mev=self.max_energy_error,
                    note=('Potentiel central effectif ajusté : minimum à 1,8 fm, −8 MeV. '
                          'Le deutéron réel est un état quantique lié à 2,224 MeV ; '
                          'la trajectoire classique ne prédit ni son spectre ni sa capture. '
                          'Électrons : densité 1s séparée, sans trajectoire ponctuelle.'))
