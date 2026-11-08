"""L'instantané du laboratoire moléculaire, recopié dans MoleculeLabState."""

from . import analysis, density, dictionary, experiments, structures, validation, xtb
from . import lab as molecule_lab


def fill(state, lab, sent):
    state.active = True
    snap, grid = lab.snapshot()
    if not sent.get("molecule_catalog"):
        state.presets.extend(name for name, _ in structures.PRESETS)
        state.reactions.extend(name for name, _ in structures.REACTIONS)
        for e in experiments.EXPERIMENTS:
            entry = state.experiments.add(title=e.title, equation=e.equation, explanation=e.explanation,
                                          collision_ev=e.collision_ev, limit_fs=e.limit_fs)
            entry.goal.extend(e.goal)
        state.palette.extend(molecule_lab.PALETTE)
        state.supported.extend(z for z in range(1, len(xtb.SYMBOLS)) if xtb.supported(z))
        state.model = validation.MODEL
        sent["molecule_catalog"] = True
    # Le dictionnaire, une fois ses départs de dessin calculés (dans un fil à part : quelques secondes au lancement).
    if not sent.get("molecule_dictionary") and lab.dictionary_ready():
        for (name, formula, numbers, charges, bonds), flat in zip(dictionary.entries(), dictionary.layouts()):
            entry = state.dictionary.add(name=name, formula=structures.pretty(formula))
            entry.numbers.extend(numbers)
            entry.charges.extend(charges)
            for a, b, order in bonds:
                entry.bonds.extend((a, b, order))
            entry.xy.extend(float(v) for v in flat.ravel())
        sent["molecule_dictionary"] = True
    if snap is None:
        state.status = "Préparation du laboratoire…"
        return
    state.ready = True
    if snap.get("validation") and not sent.get("molecule_validation"):
        for name, computed, measured in snap["validation"]:
            state.validation.add(quantity=name, value=computed, meaning=measured)
        sent["molecule_validation"] = True
    state.status = snap["status"]
    # Le verdict de l'assembleur, quand il change.
    assessment = snap.get("assessment")
    if assessment is not None and sent.get("molecule_assessment") is not assessment:
        sent["molecule_assessment"] = assessment
        a = state.assessment
        a.request, a.deep, a.done, a.error = assessment["request"], assessment["deep"], assessment["done"], assessment["error"]
        for m in assessment["molecules"]:
            v = a.molecules.add(formula=m["formula"], name=m["name"], charge=m["charge"], unpaired=m["unpaired"],
                                verdict=m["verdict"], cohesion_ev=m["cohesion"], gap_ev=m["gap"])
            v.atoms.extend(m["atoms"])
            for x, y in m["lost"]:
                v.lost.extend((x, y))
            for x, y in m["gained"]:
                v.gained.extend((x, y))
            v.pieces.extend(m["pieces"])
            v.notes.extend(m["notes"])
    state.error = snap["error"]
    state.label = snap["label"]
    state.dynamics = snap["dynamics"]
    state.temperature_target = snap["temperature_target"]
    state.paused = snap["paused"]
    state.time_fs = snap["time_fs"]
    state.steps = snap["steps"]
    state.relaxed = snap["relaxed"]
    state.steps_per_second = snap["rate"]
    state.charge = snap["charge"]
    state.wall = snap["wall"]
    state.pressure = snap.get("pressure", 0.0)
    state.pressure_target = snap.get("pressure_target", 0.0)
    state.pressure_mean = snap.get("pressure_mean", 0.0)
    state.volume_mean = snap.get("volume_mean", 0.0)
    state.pressure_span_fs = snap.get("pressure_span", 0.0)
    state.wall_hits = snap.get("wall_hits", 0)
    state.experiment = snap.get("experiment", -1)
    state.experiment_phase = snap.get("experiment_phase", 0)
    state.experiment_time_fs = snap.get("experiment_time", 0.0)
    state.census = snap["census"]
    state.view, state.view_orbital = snap["view"]
    state.excited_mode = snap["excited"]
    state.computing_modes = snap["computing_modes"]
    if snap["drag"] is None:
        state.drag_atom = -1
    else:
        state.drag_atom = snap["drag"][0]
        state.drag_target.extend(float(v) for v in snap["drag"][1])
    for time_fs, text, energy in snap["events"]:
        state.events.add(time_fs=time_fs, text=text, energy_ev=energy)
    for t, e, k in snap["history"]:
        state.history_time.append(t)
        state.history_energy.append(e)
        state.history_kinetic.append(k)
    if "frequencies" in snap:
        for wavenumber, displacement in zip(snap["frequencies"], snap["displacements"]):
            state.modes.add(wavenumber=float(wavenumber), displacement=displacement.ravel().tolist())
    if "energy" not in snap:
        return
    from .xtb import SYMBOLS
    # Le nuage projeté de chaque élément, une fois (le client en tire le halo de chaque atome, à chaque image).
    clouds = sent.setdefault("molecule_clouds", set())
    for z in sorted(set(int(v) for v in snap["numbers"]) - clouds):
        electrons, step, projected = density.projected_atom(z)
        surface, p_radius = density.atom_radii(z)
        cloud = state.clouds.add(z=z, electrons=electrons, step=step, projected=projected.tolist(), surface=surface,
                                 p_radius=p_radius)
        for mass_number, mass, abundance, years in analysis.isotopes(z):
            cloud.isotopes.add(mass_number=mass_number, mass=mass, abundance=abundance, half_life_years=years)
        clouds.add(z)
    forces = snap["forces"]
    for k, z in enumerate(snap["numbers"]):
        atom = state.atoms.add(z=z, symbol=SYMBOLS[z], charge=float(snap["charges"][k]),
                               coordination=float(snap["cn"][k]), mass_u=float(snap["masses"][k]),
                               hardness=float(snap["hardness"][k]), covalent_radius=float(snap["covalent"][k]),
                               mass_number=int(snap["isotopes"][k]))
        atom.polarization.extend(snap["polarization"][k].tolist())
        atom.position.extend(snap["positions"][k].tolist())
        atom.force.extend(snap["total_force"][k].tolist())
        atom.force_electronic.extend(forces["électronique"][k].tolist())
        atom.force_repulsion.extend(forces["répulsion"][k].tolist())
        atom.force_dispersion.extend((forces["dispersion"][k] + forces["halogène"][k]).tolist())
        atom.force_external.extend(forces["externe"][k].tolist())
    for a, b, order, length, sigma, pi, share, pi_axis, pi_split, profile, shared in snap["bonds"]:
        state.bonds.add(a=a, b=b, order=order, length=length, sigma=sigma, pi=pi, share=share, pi_axis=pi_axis,
                        pi_split=pi_split if pi_axis else [], profile=profile, shared=shared)
    for atoms, populations, centroid in snap.get("multicentres", []):
        state.multicentres.add(atoms=atoms, populations=populations, centroid=centroid)
    for a, b, distance, order, electronic, repulsion, dispersion, total, force, overlap, charges, population in snap["pairs"]:
        state.pairs.add(a=a, b=b, distance=distance, order=order, electronic=electronic, repulsion=repulsion,
                        dispersion=dispersion, total=total, force=force, overlap=overlap, charges=charges,
                        population=population)
    state.energy = snap["energy"]
    state.band = snap["band"]
    state.coulomb2 = snap["es2"]
    state.coulomb3 = snap["es3"]
    state.repulsion = snap["repulsion"]
    state.dispersion = snap["dispersion"]
    state.halogen = snap["halogen"]
    state.entropy = snap["entropy"]
    state.binding = snap["binding"]
    state.band_atoms = snap["band_atoms"]
    state.coulomb_atoms = snap["coulomb_atoms"]
    state.entropy_atoms = snap["entropy_atoms"]
    state.kinetic = snap["kinetic"]
    state.tether_energy = snap["tether_energy"]
    state.wall_energy = snap["wall_energy"]
    state.tether_force_nn = snap["tether_force_nn"]
    state.temperature = snap["temperature"]
    for energy, occupation in zip(snap["orbital_energies"], snap["occupations"]):
        state.orbitals.add(energy_ev=float(energy), occupation=float(occupation))
    state.homo = snap["homo"]
    state.dipole.extend(float(v) for v in snap["dipole"])
    state.converged = snap["converged"]
    state.iterations = snap["iterations"]
    state.unstable = snap["unstable"]
    state.electronic_temperature = snap["electronic_temperature"]
    state.confined.extend(snap["confined"])
    # Les espèces, nommées par le dictionnaire (d'après leur graphe), et les atomes de chacune (pour la scène).
    counts = {}
    for members, formula, label in snap["species"]:
        counts[label] = counts.get(label, 0) + 1
        state.named.add(atoms=members, label=label)
    for label, count in counts.items():
        state.species.append(("" if count == 1 else f"{count} × ") + label)
    if grid is not None and sent.get("molecule_grid") != grid["version"]:
        g = state.grid
        g.version = grid["version"]
        g.kind = grid["kind"]
        g.orbital = grid["orbital"]
        g.origin.extend(float(v) for v in grid["origin"])
        g.spacing = grid["spacing"]
        g.counts.extend(int(v) for v in grid["counts"])
        g.data = grid["data"]
        g.peak = grid["peak"]
        g.positions.extend(grid["positions"].ravel().tolist())
        if grid.get("bonds") is not None:
            g.bonds = grid["bonds"]
            g.bonds_peak = grid["bonds_peak"]
        if grid.get("esp") is not None:
            g.esp = grid["esp"]
            g.esp_peak = grid["esp_peak"]
        for atom, shell, share in grid.get("parts", []):
            g.parts.add(atom=atom, shell=shell, share=share)
        sent["molecule_grid"] = grid["version"]
