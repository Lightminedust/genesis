"""L'instantané du laboratoire moléculaire, recopié dans MoleculeLabState."""

import numpy as np

from . import analysis, density, dictionary, experiments, structures, validation, xtb
from . import lab as molecule_lab


def fill(state, lab, sent):
    # Un autre laboratoire (ou le même, repris après un autre) : tout ce qui part « une fois par objet » repart.
    generation = (id(lab), getattr(lab, "generation", 0))
    if sent.get("molecule_generation") != generation:
        for key in ("molecule_assessment", "molecule_reaction", "molecule_smiles", "molecule_scheme",
                    "molecule_scheme_steps", "molecule_grid", "molecule_clouds"):
            sent.pop(key, None)
        sent["molecule_generation"] = generation
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
        from . import solvation
        state.solvents.extend(solvation.label(k) for k in solvation.ORDER)
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
    from . import solvation as _solvation
    state.solvent = _solvation.ORDER.index(snap["solvent"]) if snap.get("solvent") in _solvation.ORDER else -1
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
    # La réaction du dessin, quand elle change.
    reaction = snap.get("reaction")
    if reaction is not None and sent.get("molecule_reaction") is not reaction:
        sent["molecule_reaction"] = reaction
        fill_reaction(state.reaction, reaction)
    # Le schéma réactionnel : ses exemples (une fois), sa réponse à un SMILES, son calcul, le chemin joué.
    examples = snap.get("scheme_examples")
    if examples and not sent.get("molecule_scheme_examples"):
        for title, doc in examples:
            fill_drawing(state.scheme_examples.add(), doc, title)
        sent["molecule_scheme_examples"] = True
    smiles = snap.get("smiles")
    if smiles is not None and sent.get("molecule_smiles") is not smiles:
        sent["molecule_smiles"] = smiles
        state.smiles.request, state.smiles.error = smiles["request"], smiles["error"]
        if smiles["drawing"]:
            fill_drawing(state.smiles.drawing, smiles["drawing"], smiles.get("kind", ""))
    scheme = snap.get("scheme")
    if scheme is not None and sent.get("molecule_scheme") is not scheme:
        sent["molecule_scheme"] = scheme
        fill_scheme(state.scheme, scheme, sent)
    play = snap.get("play")
    if play is not None:
        state.play.step, state.play.position = play["step"], play["position"]
        state.play.running, state.play.frame = play["running"], play["frame"]
        state.play.radius = play.get("radius", 0.0)
        state.play.energy = play.get("energy", 0.0)
        state.play.speed = play.get("speed", 0.0)
    else:
        state.play.step = -1
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
    state.solvation = snap.get("solvation", 0.0)
    state.solvation_polar = snap.get("solvation_polar", 0.0)
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


def fill_drawing(d, doc, title):
    """Un schéma dessiné (le JSON de scheme.py) dans un MoleculeSchemeDrawing."""
    d.title = title
    for atom in doc.get("atoms", []):
        d.atoms.extend((float(atom[0]), float(atom[1]), float(atom[2]), float(atom[3] if len(atom) > 3 else 0)))
        d.labels.append(str(atom[4]) if len(atom) > 4 and atom[4] else "")
    for a, b, o in doc.get("bonds", []):
        d.bonds.extend((int(a), int(b), int(o)))
    for arrow in doc.get("arrows", []):
        d.arrows.extend((float(arrow["tail"][0]), float(arrow["tail"][1]), float(arrow["head"][0]),
                         float(arrow["head"][1])))
        d.above.append(arrow.get("above", "") or "")
        d.below.append(arrow.get("below", "") or "")
    for c in doc.get("curly", []):
        src, dst = list(c["from"]) + [-1], list(c["to"]) + [-1]
        d.curly.extend((int(src[0]), int(src[1]), int(dst[0]), int(dst[1])))


def fill_scheme(m, scheme, sent=None):
    """Le schéma calculé (scheme.compile_scheme, plus le numéro de la demande) dans un MoleculeScheme."""
    m.request, m.done, m.error = scheme["request"], scheme["done"], scheme["error"]
    m.status, m.fraction, m.remaining_s = scheme["status"], scheme["fraction"], scheme["remaining"]
    for t, step, what, subject, value in scheme.get("log", []):
        m.log.add(time_s=t, step=step, what=what, subject=subject, value=value)
    live = scheme.get("live")
    if live and not scheme["done"]:
        m.live.step, m.live.caption, m.live.energy_eh, m.live.calls = (live["step"], live["caption"], live["energy"],
                                                                       live["calls"])
        m.live.numbers.extend(live["numbers"])
        m.live.positions.extend(live["positions"])
        m.live.bonds.extend(live["bonds"])
        m.live.pulled.extend(live["pulled"])
        m.live.backward.extend(live["backward"])
        m.live.forward.extend(live["forward"])
    # Les étapes, lourdes (les images de chaque chemin), ne repartent qu'à leur changement : une nouvelle étape calculée,
    # un autre schéma. Entre-temps, le client garde celles qu'il a (steps_kept).
    key = (scheme.get("request"), len(scheme["steps"]), bool(scheme["done"]))
    if sent is not None and sent.get("molecule_scheme_steps") == key:
        m.steps_kept = True
        return
    if sent is not None:
        sent["molecule_scheme_steps"] = key
    for s in scheme["steps"]:
        step = m.steps.add(arrow=s["arrow"], reagents=s["reagents"], conditions=s["conditions"],
                           reactants=s["reactants"], products=s["products"], byproducts=s["byproducts"],
                           mapping=s["mapping"], delta_ev=s["delta"], barrier_ev=s["barrier"],
                           verdict=s["verdict"], confirmed=s["confirmed"], computed=s["computed"],
                           scan_start=s["scan"][0], scan_end=s["scan"][1])
        step.reactant_atoms.extend(s["reactant_atoms"])
        step.product_atoms.extend(s["product_atoms"])
        step.notes.extend(s["notes"])
        step.energies.extend(float(e) for e in s["energies"])
        for a, b, before, after in s["changes"]:
            step.changes.extend((int(a), int(b), int(before), int(after)))
        for row in s["orders"]:
            step.orders.extend(float(v) for v in row)
        for arrow in s["arrows3d"]:
            step.arrows.extend(int(v) for v in arrow)
        step.drawn.extend(int(v) for v in s.get("drawn", []))
        step.solvent = s.get("solvent_label", "")
        # Les images du chemin, pour la vue 2.5D : chacune centrée sur ses masses, comme la scène jouée la pose.
        numbers = [int(z) for z in s.get("numbers", [])]
        step.numbers.extend(numbers)
        frames = s.get("frames", [])
        if numbers and len(frames):
            masses = np.array([analysis.isotope_mass(z, 0) for z in numbers])
            for p in frames:
                p = np.asarray(p, float)
                step.path.extend((p - (masses @ p) / masses.sum()).ravel().tolist())
        for row in s.get("charges", []):
            step.charges.extend(float(q) for q in row)
        step.jumps.extend(int(k) for k in s.get("jumps", []))
        step.radii.extend(float(analysis.covalent_radius(z)) for z in numbers)
        for a, b in s.get("bond_pairs", []):
            step.bond_pairs.extend((int(a), int(b)))
        for row in s.get("bond_orders", []):
            step.bond_orders.extend(float(v) for v in row)
        # Le numéro de chaque atome dans tout le schéma, et de quoi poser l'étape dans le profil de tout le schéma.
        step.ids.extend(int(v) for v in s.get("ids", []))
        step.start_ev, step.end_ev = float(s.get("start_ev", 0.0)), float(s.get("end_ev", 0.0))
        step.transfer_ev = float(s.get("transfer_ev", 0.0))
        step.temperature = float(s.get("temperature", 300.0) or 300.0)
        step.entropy_ev = float(s.get("entropy_ev", 0.0))


def fill_reaction(r, reaction):
    """La réaction du dessin (reaction.explore, plus le numéro de la demande) dans un MoleculeReaction."""
    r.request, r.done, r.error = reaction["request"], reaction["done"], reaction["error"]
    r.phase, r.trial, r.time_fs, r.census = reaction["phase"], reaction["trial"], reaction["time_fs"], reaction["census"]
    r.remaining_s, r.fraction, r.reactants = reaction["remaining"], reaction["fraction"], reaction["reactants"]
    r.temperature, r.trials, r.duration_fs = reaction["temperature"], reaction["trials"], reaction["duration"]
    for o in reaction["outcomes"]:
        out = r.outcomes.add(label=o["label"], count=o["count"], delta_ev=o["delta"], first_fs=o["first"],
                             unchanged=o["unchanged"])
        for p in o["products"]:
            entry = out.products.add(name=p["label"], formula=p["formula"])
            entry.numbers.extend(p["numbers"])
            entry.charges.extend(p["charges"])
            for a, b, order in p["bonds"]:
                entry.bonds.extend((a, b, order))
            entry.xy.extend(float(v) for v in p["xy"].ravel())
