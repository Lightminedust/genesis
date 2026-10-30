"""Le laboratoire de la réaction en chaîne dans la frame envoyée au client (message ChainState)."""

import math
import re

from . import library, sandbox
from .text import fixed, joules, number, percent

C_M_S = 2.99792458e8
TNT_J_PER_TONNE = 4.184e9
HIROSHIMA_J = 6.3e13            # Little Boy : environ 15 kt de TNT
ENERGY_PARTS = (("fragments de fission", 0), ("neutrons prompts", 1), ("γ prompts", 3), ("neutrons retardés", 2),
                ("γ retardés", 4), ("électrons β", 5), ("neutrinos (perdus)", 6))
FORMULAS = [
    "k_eff = neutrons produits / (absorbés + échappés)",
    "N(t) ≈ N₀ · e^((k − 1) t / Λ)   (au-delà du critique prompt)",
    "ρ = (k − 1)/k    réactivité en dollars : ρ / β",
    "M_c ∝ 1/ρ²   (sphère nue comprimée)",
    "Q = Δm · c²",
    "P(ν) : loi de Terrell (σ = 1,08) autour de ν̄(E)",
]
MODEL = ("Transport des neutrons par probabilités de première collision dans des coquilles sphériques, 44 groupes "
         "d'énergie tirés d'ENDF/B-VIII.0 (autoprotection de Bondarenko, correction de transport, diffusion isotrope "
         "ensuite). Cinétique ponctuelle à neutrons retardés ; Λ pondéré par le flux adjoint ; β non pondéré. "
         "Excursion : dilatation du métal condensé, puis gaz parfait et rayonnement en expansion homologue ; inertie "
         "du solide, changements de phase du plutonium et ionisation négligés ; réflecteur immobile.")


def _composition(material):
    weights = {n: f * library.mass_u(n) for n, f in material.atoms.items()}
    total = sum(weights.values()) or 1
    parts = sorted(weights.items(), key=lambda kv: -kv[1])
    return " · ".join(f"{percent(w / total)} {nuclide(n)}" for n, w in parts if w / total >= 0.001)


def nuclide(name):
    """« U235 » → « U-235 »."""
    match = re.match(r"([A-Za-z]+)(\d+)", name)
    return f"{match.group(1)}-{match.group(2)}" if match else name


def _material(message, material):
    message.key = material.key
    message.label = material.label
    message.detail = material.detail
    message.density = material.density
    message.fissile = material.fissile
    message.composition = _composition(material)
    message.source = material.source


def fill(message, lab, sent):
    message.active = True
    message.computing = lab.computing
    message.status = lab.status
    message.error = lab.error
    if not sent.get("chain_catalog"):
        for core in lab.cores:
            _material(message.cores.add(), core)
        for reflector in lab.reflectors:
            _material(message.reflectors.add(), reflector)
        message.bounds_ev.extend(float(b) for b in library.bounds_ev())
        sent["chain_catalog"] = True
    message.core = lab.core
    message.reflector = lab.reflector
    message.radius_cm = lab.radius
    message.reflector_cm = lab.thickness
    message.compression = lab.compression
    message.mode = lab.mode
    message.validation = lab.validation
    message.formulas.extend(FORMULAS)
    message.model = MODEL
    result = lab.result
    if result is not None:
        _fill_result(message, lab, result)
    shot = lab.shot
    message.run = lab.run
    message.shot = shot is not None
    if shot is not None:
        message.sources = shot["sources"]
        if sent.get("chain_run") != lab.run:
            _fill_tracks(message, shot)
            sent["chain_run"] = lab.run
        excursion = lab.excursion
        ready = excursion is not None and lab.excursion_run == lab.run
        message.excursion_ready = ready
        if ready and sent.get("chain_excursion") != lab.run:
            _fill_excursion(message, excursion)
            sent["chain_excursion"] = lab.run
    if lab.mode == 1:
        _fill_sandbox(message.sandbox, lab, sent)


def _fill_result(message, lab, r):
    message.ready = (r["core"] is lab.cores[lab.core] and abs(r["radius"] - lab.radius) < 1e-9
                     and (r["reflector"] is (lab.reflectors[lab.reflector] if lab.reflector >= 0 else None))
                     and abs(r["compression"] - lab.compression) < 1e-12
                     and (r["reflector"] is None or abs(r["thickness"] - lab.thickness) < 1e-9))
    message.k_eff = r["k"]
    message.k_infinity = r["k_infinity"]
    message.regime = r["regime"]
    from .lab import REGIMES
    message.regime_text = REGIMES[r["regime"]]
    message.density = r["density"]
    message.mass_kg = r["mass_kg"]
    message.reflector_mass_kg = r["reflector_mass_kg"]
    message.critical_radius_cm = r["critical_radius"] or 0.0
    message.critical_mass_kg = r["critical_mass"]
    message.leakage = r["leakage"]
    message.fission = r["fission"]
    message.capture = r["capture"]
    message.mean_fission_energy_ev = r["mean_fission_energy"]
    message.generation_time_s = r["generation_time"]
    message.beta = r["beta"]
    message.dollars = r["dollars"]
    message.period_s = 1 / r["omega"] if r["omega"] else 0.0
    message.prompt_critical_k = r["prompt_critical_k"] if math.isfinite(r["prompt_critical_k"]) else 0.0
    message.nu_bar = r["nu_bar"]
    message.curve_radius.extend(r["curve_radius"])
    message.curve_k.extend(r["curve_k"])
    message.spectrum.extend(float(x) for x in r["spectrum"])
    message.fission_spectrum.extend(float(x) for x in r["fission_spectrum"])
    solution = r["solution"]
    total = solution.flux.sum(axis=0)
    peak = float(total.max()) or 1.0
    message.flux_radius.extend(float(x) for x in solution.radii)
    message.flux_profile.extend(float(x) / peak for x in total)
    message.source_n_s = r["source"]
    if r["k"] <= 0:
        pass                      # sans fission, la source intrinsèque n'allume rien
    elif r["source"] > 0:
        message.source_text = (f"Fission spontanée : {number(r['source'])} neutrons par seconde allument la chaîne "
                               "sans aide extérieure.")
    elif r["source_unknown"]:
        message.source_text = "Fission spontanée présente, mais sa multiplicité n'est pas tabulée ici."
    else:
        message.source_text = "Aucune fission spontanée tabulée : il faut un neutron extérieur pour amorcer la chaîne."
    message.q_recoverable_mev = r["q_recoverable"]
    message.q_prompt_mev = r["q_prompt"]
    message.q_mass_mev = r["q_mass"]
    fissile = [n for n in r["composition"] if library.fissile_data(n)]
    if fissile:
        main = max(fissile, key=lambda n: r["composition"][n])
        release = library.get(main, "energy_release")
        for name, index in ENERGY_PARTS:
            value = float(release[index]) / 1e6
            if value > 0:
                message.energy_split.add(quantity=name, value=f"{number(value)} MeV", meaning=nuclide(main))
    message.outer_radius_cm = r["radius"] + r["thickness"]
    message.core_atoms = r["core_atoms"]
    message.divergence_probability = r["divergence"]


def _fill_tracks(message, shot):
    for track in shot["tracks"]:
        t = message.tracks.add(generation=track["generation"], parent=track["parent"], end=track["end"],
                               children=track["children"])
        for point in track["points"]:
            t.points.extend(float(v) for v in point)
        t.kinds.extend(int(k) for k in track["kinds"])
        t.groups.extend(int(g) for g in track["groups"])
    message.generation_counts.extend(float(c) for c in shot["counts"])


def _fill_excursion(message, shot):
    message.time_s.extend(shot["time_s"])
    message.log10_neutrons.extend(shot["log10_neutrons"])
    message.energy_j.extend(shot["energy_j"])
    message.temperature_k.extend(shot["temperature_k"])
    message.radius_factor.extend(shot["radius_factor"])
    message.k_t.extend(shot["k"])
    message.power_w.extend(shot["power_w"])
    message.phase.extend(shot["phase"])
    message.yield_j = shot["energy"]
    message.fissions = shot["fissions"]
    message.burned_fraction = shot["burned_fraction"]
    message.peak_temperature_k = shot["peak_temperature_k"]
    message.excursion_text = shot["text"]
    message.dispersed = shot["dispersed"]
    energy = shot["energy"]
    if energy > 0:
        tonnes = energy / TNT_J_PER_TONNE
        message.facts.add(quantity="énergie libérée", value=joules(energy),
                          meaning=f"{number(tonnes)} t de TNT" if tonnes >= 1e-3 else f"{number(tonnes * 1e6)} g de TNT")
        message.facts.add(quantity="masse convertie", value=f"{number(energy / C_M_S ** 2 * 1e3)} g",
                          meaning="Δm = E / c²")
        message.facts.add(quantity="fissions", value=f"{number(shot['fissions'])}",
                          meaning=f"{percent(shot['burned_fraction'])} du combustible")
        if energy > 1e-4 * HIROSHIMA_J:
            message.facts.add(quantity="Hiroshima", value=percent(energy / HIROSHIMA_J),
                              meaning="de l'énergie de Little Boy (≈ 15 kt)")


def _fill_sandbox(message, lab, sent):
    if not sent.get("chain_curve"):
        for a, z, ba in sandbox.binding_curve():
            message.curve_a.append(a)
            message.curve_z.append(z)
            message.curve_ba.append(ba)
        sent["chain_curve"] = True
    peak = max(sandbox.binding_curve(), key=lambda p: p[2])
    message.peak_a, message.peak_z, message.peak_ba = peak
    for row in lab.comparison or []:
        message.compare.add(label=row["label"], process=row["process"], q_mev=row["q_mev"], j_per_kg=row["j_per_kg"],
                            tnt_per_kg=row["tnt_per_kg"], condition=row["condition"], possible=row["possible"],
                            note=row["note"])
    e = lab.explored
    if e is None:
        message.z, message.a = lab.nuclide
        return
    message.z, message.a = e["z"], e["a"]
    message.label = e["label"]
    message.name = e["name"]
    message.mass_u = e["mass_u"]
    message.binding_per_nucleon = e["binding_per_nucleon"]
    if e["fission"]:
        q, (z1, a1), (z2, a2) = e["fission"]
        message.fission_known = True
        message.fission_q_mev = q
        message.fission_z1, message.fission_a1, message.fission_z2, message.fission_a2 = z1, a1, z2, a2
        message.fission_products_u = sandbox.mass(z1, a1) + sandbox.mass(z2, a2)
        message.fission_ba1 = sandbox.binding_per_nucleon(z1, a1) or 0.0
        message.fission_ba2 = sandbox.binding_per_nucleon(z2, a2) or 0.0
    if e["symmetric"]:
        message.symmetric_known = True
        message.symmetric_q_mev = e["symmetric"][0]
    if e["fusion"]:
        q, (zf, af) = e["fusion"]
        message.fusion_known = True
        message.fusion_q_mev = q
        message.fusion_z, message.fusion_a = zf, af
        message.fusion_product_u = sandbox.mass(zf, af)
        message.fusion_ba = sandbox.binding_per_nucleon(zf, af) or 0.0
    message.barrier_mev = e["barrier_mev"]
    message.lawson_triple = e["lawson_triple"]
    message.lawson_t_kev = e["lawson_t_kev"]
    chain = e["chain"]
    message.evaluated = chain["evaluated"]
    message.fission_data = chain["fission_data"]
    message.k_infinity = chain["k_infinity"]
    message.sigma_thermal_b = chain["sigma_thermal"]
    message.sigma_fast_b = chain["sigma_fast"]
    message.critical_kg = chain["critical_kg"]
    message.verdict = e["verdict"]
