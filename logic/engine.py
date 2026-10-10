"""Moteur de simulation atomique exposé en gRPC.

Le moteur fait tourner le temps. Pour chaque client :
  - un fil lit ses actions et les met en file ;
  - la boucle principale, vingt fois par seconde, applique les actions,
    avance le temps simulé (simulation.Session), et envoie une frame dès
    qu'un événement survient, ou quatre fois par seconde tant que le temps
    s'écoule.

Rien n'est scénarisé côté client : il dessine ce que le moteur lui dit être
arrivé, et le calcul qui le justifie.
"""

import math
import os
import queue
import sys
import threading
import time
from concurrent import futures

import grpc
import numpy as np

import genesis_pb2
import genesis_pb2_grpc
import nuclear_data
import nucleus as nucleus_model
from atom import SHELL_NAMES, shell_capacity
from repository import ElementRepository
from particles import PARTICLES
from simulation import SPEEDS, Session
from units import format_duration

# Incrémenté à chaque changement incompatible du contrat. Le client refuse de
# dessiner une frame qui ne porte pas ce numéro : mieux vaut un message clair
# qu'un affichage silencieusement faux.
PROTOCOL_VERSION = 25

TICK_S = 0.05
HEARTBEAT_S = 0.25

Action = genesis_pb2.UserAction

STABILITY = {
    "STABLE": genesis_pb2.STABLE,
    "UNSTABLE": genesis_pb2.UNSTABLE,
    "NOT_AN_ATOM": genesis_pb2.NOT_AN_ATOM,
    "UNKNOWN": genesis_pb2.STABILITY_UNKNOWN,
}

EVENT_FIELDS = ("kind", "title", "formula", "detail", "from_state", "to_state", "decay_mode",
                "particle")


def to_element_message(element):
    """Traduit une fiche du dépôt en message Protobuf."""
    return genesis_pb2.Element(
        z=element["z"],
        symbol=element["symbol"],
        name=element["name"],
        category=element.get("category", ""),
        color_hex=element.get("color_hex", "#94A3B8"),
        stable_isotopes=element.get("stable_isotopes", []),
        table_row=element.get("table_row", 0),
        table_col=element.get("table_col", 0),
    )


def _finite(value):
    """Protobuf accepte l'infini, mais un client n'en ferait rien d'utile."""
    return value if value is not None and math.isfinite(value) else 0.0


def build_frame(tick, evaluation, source, last_action=""):
    """Traduit une `Evaluation` (physique pure) en message Protobuf."""
    ev = evaluation
    frame = genesis_pb2.UniverseFrame(
        tick=tick,
        headline=ev.headline,
        source=source,
        protocol_version=PROTOCOL_VERSION,
        console_lines=ev.console_lines,
        spectrum_source=ev.spectrum_source,
        excitation_level=ev.excitation_level,
        excited_radius_pm=ev.excited_radius_pm,
        ionization_model_ev=ev.ionization_model_ev,
        last_action=last_action,
        atom=genesis_pb2.AtomState(
            protons=ev.protons,
            neutrons=ev.neutrons,
            electrons=ev.electrons,
            mass_number=ev.mass_number,
            net_charge=ev.net_charge,
            stability=STABILITY[ev.stability],
            ion_label=ev.ion_label,
            isotope_label=ev.isotope_label,
            decay_mode=ev.decay_mode,
            binding_energy_mev=ev.binding_total,
            binding_energy_per_nucleon=ev.binding_per_nucleon,
            mass_defect_u=ev.mass_defect_u,
            valence_electrons=ev.valence_electrons,
            octet_satisfied=ev.octet_satisfied,
            neutron_proton_ratio=ev.ratio,
            nuclear_radius_fm=ev.nuclear_radius_fm,
            half_life=ev.half_life,
            one_electron=ev.one_electron,
        ),
    )

    if ev.ionization_measured_ev:
        frame.ionization_measured_ev = ev.ionization_measured_ev
        frame.has_ionization_measured = True

    if ev.element:
        frame.element.CopyFrom(to_element_message(ev.element))
        frame.element.configuration = ev.configuration_text

    for kind, x, y, z in ev.nucleons:
        frame.nucleons.add(
            kind=genesis_pb2.Nucleon.PROTON if kind == "proton" else genesis_pb2.Nucleon.NEUTRON,
            x=x, y=y, z=z,
        )

    for index, occupancy in enumerate(ev.shells):
        frame.shells.add(
            index=index + 1,
            name=SHELL_NAMES[index] if index < len(SHELL_NAMES) else str(index + 1),
            capacity=shell_capacity(index + 1),
            electrons=occupancy,
            configuration=ev.shell_configurations.get(index + 1, ""),
        )

    for detail in ev.subshells:
        frame.subshells.add(
            n=detail["n"], l=detail["l"], electrons=detail["electrons"],
            capacity=detail["capacity"], radius_pm=detail["radius_pm"],
            z_eff=detail["z_eff"], energy_ev=detail["energy_ev"],
            label=detail["label"], occupation=detail["occupation"],
        )

    for level in ev.levels:
        message = frame.levels.add(
            n=level["n"], label=level["label"], energy_ev=level["energy_ev"],
            electrons=level["electrons"], source=level["source"],
        )
        if level["measured_ev"] is not None:
            message.has_measured = True
            message.measured_ev = level["measured_ev"]

    for line in ev.spectrum:
        frame.spectrum.add(
            wavelength_nm=_finite(line["wavelength_nm"]), energy_ev=line["energy_ev"],
            intensity=line.get("intensity", 1.0), label=line["label"],
            from_n=line.get("from_n", 0), to_n=line.get("to_n", 0),
        )

    for term in ev.nuclear_terms:
        frame.nuclear_terms.add(key=term["key"], name=term["name"],
                                description=term["description"], mev=term["mev"])

    if ev.orbital:
        orbital = ev.orbital
        frame.has_orbital = True
        frame.orbital.CopyFrom(genesis_pb2.Orbital(
            density=orbital["grid"], size=orbital["size"], extent_pm=orbital["extent_pm"],
            label=orbital["label"], n=orbital["n"], l=orbital["l"], z_eff=orbital["z_eff"],
            radial_nodes=orbital["radial_nodes"], orbital_names=orbital["orbital_names"],
            occupation=orbital["occupation"],
        ))

    for step in ev.calculations:
        frame.calculations.add(**step)

    return frame


def to_event_message(event):
    message = genesis_pb2.SimEvent(time_s=event["time_s"])
    for field in EVENT_FIELDS:
        if event.get(field):
            setattr(message, field, event[field])
    message.wavelength_nm = _finite(event.get("wavelength_nm"))
    message.energy_ev = _finite(event.get("energy_ev"))
    message.kinetic_ev = _finite(event.get("kinetic_ev"))
    message.q_mev = _finite(event.get("q_mev"))
    message.count = event.get("count", 0)
    message.extra_wavelengths_nm.extend(event.get("extra_wavelengths_nm", []))
    message.particles.extend(event.get("particles", []))
    return message


def session_frame(tick, session, evaluation, source, events, last_action, structure_changed, base=None,
                  sent_table=None):
    """La frame complète : l'atome évalué, le temps, et ce qui vient d'arriver.

    `base` : la partie qui ne dépend que de la structure de l'atome (orbitale,
    niveaux...), déjà construite ; on la recopie au lieu de la recalculer.
    """
    if base is None:
        frame = build_frame(tick, evaluation, source, last_action)
    else:
        frame = genesis_pb2.UniverseFrame()
        frame.CopyFrom(base)
        frame.tick = tick
        frame.last_action = last_action
    frame.sim_time_s = session.time_s
    frame.speed_index = session.speed_index
    frame.speed = session.speed
    frame.step_mode = session.step_mode
    if session.one_electron():
        frame.electron_spin = session.electron_spin
    frame.speed_labels.extend(label for _, label in SPEEDS)
    frame.paused = session.paused
    frame.electron_state = session.state_label()
    rate = session.electron_rate()
    frame.electron_lifetime_s = 1.0 / rate if rate > 0 else 0.0
    frame.absorbable_nm.extend(session.absorbable_lines())
    frame.ionization_now_ev = _finite(session.ionization_energy())
    frame.structure_changed = structure_changed
    frame.events.extend(to_event_message(event) for event in events)

    if session.matter is not None:
        fill_matter(frame.matter, session.matter)
    if session.molecule is not None:
        fill_chemistry(frame.chemistry, session.molecule, sent_table)
    if session.lab is not None:
        fill_lab(frame.lab, session.lab)
    if session.reactor is not None:
        fill_reactor(frame.reactor, session.reactor)
    if session.subatomic is not None:
        fill_subatomic(frame.subatomic, session.subatomic, sent_table)
    if session.nucleon is not None:
        fill_nucleon(frame.nucleon, session.nucleon, session.nucleon_strike, sent_table)
    if session.partons is not None and session.nucleon is not None:
        fill_partons(frame.partons, session.partons, session.partons_view, sent_table)
    if session.nucleon is not None:
        fill_standard_model(frame.standard_model, sent_table)
        fill_bag(frame.bag, session.nucleon)
        if session.collision is not None:
            fill_probe(frame.collision, session.collision, sent_table)
        if session.hadron_lab is not None:
            fill_hadron(frame.hadron_lab, session.hadron_lab)
    if session.star is not None:
        fill_star(frame.star, session.star, session.paused)

    status = frame.nucleus
    status.nuclide = session.nuclide_label()
    z, a = session.atom.protons, session.atom.protons + session.atom.neutrons
    status.spin_parity = nuclear_data.spin_parity(z, a) if a else ""
    weighed = nuclear_data.measured_binding(z, a) if a else None
    if weighed:
        status.atomic_mass_u, status.nucleons_mass_u, status.binding_measured_mev = weighed
    decay = session.nuclear_decay()
    if decay:
        status.unstable = True
        status.half_life_s = _finite(decay["half_life_s"])
        status.half_life = format_duration(decay["half_life_s"])
        status.source = decay["source"]
        status.decay_rate = decay["rate"]
        for mode, fraction, daughter in decay["channels"]:
            name = "" if daughter is None else _nuclide(daughter)
            status.channels.add(mode=mode, fraction=fraction, daughter=name)
    for link in session.decay_chain():
        element = session.repo.find(link["z"]) if link["z"] else None
        status.chain.add(
            nuclide=_nuclide((link["z"], link["a"], link["state"])), z=link["z"], a=link["a"],
            element=element["name"] if element else ("neutron" if link["z"] == 0 else ""),
            half_life_s=_finite(link["half_life_s"]),
            half_life=format_duration(link["half_life_s"]) if link["half_life_s"] > 0 else "",
            mode=link["mode"], fraction=link["fraction"], stable=link["stable"],
            estimated=link.get("estimated", False))
    return frame


def fill_subatomic(message, experiment, sent):
    message.active = True
    message.run = experiment.run
    message.protons = experiment.protons
    message.neutrons = experiment.neutrons
    message.electrons = experiment.electrons
    message.impulse_mev = experiment.impulse_mev
    message.electron_radius_pm = experiment.electron_radius_pm
    message.electron_binding_ev = experiment.electron_binding_ev
    summary = experiment.summary()
    message.energy_error_mev = summary['energy_error_mev']
    message.status = summary['status']
    message.note = summary['note']
    message.radial_fm.extend(experiment.profile_r)
    message.potential_mev.extend(experiment.profile_v)
    key = experiment.run
    if sent is not None and sent.get('subatomic_run') == key:
        return
    for sample in experiment.samples:
        frame = message.samples.add(time_fmc=sample['time_fmc'], kinetic_mev=sample['kinetic'],
                                    strong_mev=sample['strong'], coulomb_mev=sample['coulomb'],
                                    total_mev=sample['total'], separation_fm=sample['separation'],
                                    radial_force_mev_fm=sample['radial_force'],
                                    binding_margin_mev=sample['binding_margin'],
                                    locally_bound_count=sample['bound_count'])
        for i, kind in enumerate(experiment.kinds):
            frame.particles.add(kind=genesis_pb2.SubatomicParticle.PROTON if kind else genesis_pb2.SubatomicParticle.NEUTRON,
                                position_fm=sample['positions'][i].tolist(),
                                velocity_c=sample['velocities'][i].tolist(),
                                mass_mev=float(experiment.mass[i]))
        for i, j, r, potential, force, _ in sample['pairs']:
            frame.pairs.add(first=i, second=j, separation_fm=r,
                            effective_force_mev_fm=force, effective_potential_mev=potential)
    if sent is not None:
        sent['subatomic_run'] = key


def fill_nucleon(message, nucleon, strike, sent):
    """Le nucléon de l'intérieur : les paramètres du modèle à chaque frame, les échantillons une seule fois par tir."""
    import nucleon as quarks
    message.active = True
    message.run = nucleon.run
    message.hadron = nucleon.hadron
    message.struck = strike['struck']
    message.momentum_mev = strike['momentum']
    message.direction.extend(float(v) for v in strike['direction'])
    q = getattr(nucleon, 'q', None) or {}
    message.nu_mev = q.get('nu_mev', 0.0)
    message.q2_mev2 = q.get('q2', 0.0)
    message.w_mev = q.get('w_mev', nucleon.mass_pdg)
    message.size_fm = nucleon.b
    message.energy_error_mev = nucleon.max_energy_error
    message.sigma_mev_fm = quarks.SIGMA
    message.alpha_s = quarks.ALPHA_S
    message.mass_pdg_mev = nucleon.mass_pdg
    message.break_rate = quarks.BREAK_RATE
    message.status = "%d rupture(s) de corde" % len(nucleon.breaks) if nucleon.breaks else (
        "intact" if strike['momentum'] <= 0 else "la corde s'étire et revient : pas de rupture")
    message.note = ("Quarks constituants (m = 336 MeV), corde en Y σ = 0,18 GeV², échange de gluons −(2/3)α_s/r ; "
                    "taux de rupture Γ₀·exp(−πm²/σ) avec Γ₀ calibré (non calculé) ; spins non dynamiques.")
    key = nucleon.run
    if sent is not None and sent.get('nucleon_run') == key:
        return
    for sample in nucleon.samples:
        frame = message.samples.add(time_fmc=sample['time'], kinetic_mev=sample['kinetic'], string_mev=sample['string'],
                                    coulomb_mev=sample['coulomb'], total_mev=sample['total'],
                                    break_probability=sample['break_probability'])
        for i, quark in enumerate(sample['quarks']):
            frame.quarks.add(id=i, flavor=quark['flavor'], anti=quark['anti'], color=quark['color'],
                             cluster=quark['cluster'], charge=quarks.charge_of(quark['flavor'], quark['anti']),
                             mass_mev=quarks.MASS[quark['flavor']], position_fm=sample['x'][i].tolist(),
                             velocity_c=sample['v'][i].tolist())
        for cluster in sample['clusters']:
            frame.clusters.add(id=cluster['id'], kind=cluster['kind'], quarks=cluster['ids'],
                               junction_fm=cluster['junction'].tolist(), name=cluster['name'], mass_mev=cluster['mass'],
                               excitation_mev=cluster['excitation'], string_mev=cluster['string'],
                               momentum_mev=cluster['momentum'])
    for event in nucleon.breaks:
        message.breaks.add(time_fmc=event['time'], position_fm=event['position'].tolist(), flavor=event['flavor'],
                           separation_fm=event['separation'], released_mev=event['released'], hadrons=event['hadrons'])
    if sent is not None:
        sent['nucleon_run'] = key


_BAG_CACHE = {}


def fill_bag(message, nucleon):
    """La forme des hadrons et le puits du proton : calculés une fois par cible, recopiés à chaque frame (quelques nombres)."""
    import bag
    target = getattr(nucleon, 'hadron', 'proton')
    if target not in _BAG_CACHE:
        b = bag.bag(target)
        a = bag.density_length(target)
        levels = [-0.5, -1.0, -2.0]
        profile_r = np.concatenate([[0.0], np.geomspace(0.02, 12.0, 47)])
        profile_u = bag.potential(profile_r, a)
        _BAG_CACHE[target] = dict(b, quark_radius=max(.3, b['radius'] - getattr(nucleon, 'b', .5)), a=a,
                                  depth=float(bag.potential(0.0, a)), levels=levels,
                                  radii=[bag.radius_for(l, a) for l in levels], profile_r=profile_r.tolist(),
                                  profile_u=profile_u.tolist(), profile_e=bag.field(profile_r, a).tolist())
    c = _BAG_CACHE[target]
    message.active = True
    message.charge_radius_fm = c['charge_radius']
    message.radius_fm = c['radius']
    message.pressure_mev_fm3 = c['pressure']
    message.b_quarter_mev = c['b_quarter']
    message.tube_radius_fm = c['tube_radius']
    message.quark_radius_fm = c['quark_radius']
    message.density_length_fm = c['a']
    message.well_depth_mev = c['depth'] if target == 'proton' else 0.0
    if target == 'proton':
        message.well_levels_mev.extend(c['levels'])
        message.well_radii_fm.extend(c['radii'])
        message.well_profile_r_fm.extend(c['profile_r'])
        message.well_profile_u_mev.extend(c['profile_u'])
        message.field_profile_mev_fm.extend(c['profile_e'])


def fill_standard_model(message, sent):
    """Les 17 particules et leurs fiches calculées : envoyées une fois par connexion."""
    if sent is not None and sent.get('standard_model'):
        return
    import sm_cards
    for p in sm_cards.particles():
        message.particles.add(id=p['id'], symbol=p['symbol'], name=p['name'], family=p['family'], generation=p['generation'],
                              charge=p['charge'], spin=p['spin'], mass_mev=p['mass'], colour=p['colour'], forces=p['forces'],
                              anti=p['anti'], lines=p['lines'])
    if sent is not None:
        sent['standard_model'] = True


def fill_probe(message, state, sent):
    """La sonde électronique : paramètres, cas et probabilités, spectre (une fois par tirage), étapes calculées."""
    col, res = state['col'], state['result']
    event, tracks = res['event'], res['tracks']
    message.active = True
    message.run = state['run']
    message.target = col.target
    message.energy_mev = col.energy
    message.angle_deg = col.angle
    for c in col.channels:
        message.channels.add(key=c['key'], name=c['name'], xs_nb=c['xs_nb'], probability=c['probability'])
    message.elastic_xs_nb = col.channels[0]['xs_nb']
    message.chosen = event['index']
    message.forced = bool(event.get('forced'))
    message.w_mev = event['w']
    message.e_out_mev = event['e_out']
    message.q2_gev2 = event['q2'] / 1e6
    message.nu_mev = event['nu']
    message.q_mev = event['q_mag']
    message.x = event['x']
    message.wavelength_fm = event['wavelength_fm']
    message.impact_fm = tracks['impact']
    beam = col.beam
    message.projectile = col.projectile
    message.projectile_label = beam['label']
    message.projectile_charge = beam['charge']
    message.kind = col.kind
    message.electron_mass_mev = beam['mass']
    message.outgoing_label = beam.get('out_label', '')
    message.outgoing_mass_mev = beam.get('out_mass', 0.0)
    message.outgoing_charge = beam.get('out_charge', 0)
    for lb in res.get('lobes', []):
        message.lobes.add(label=lb['label'], angle_deg=lb['angles'], density=lb['density'], origin_fm=lb['origin'], axis=lb['axis'],
                          log_scale=lb['log'], span_decades=lb['span'], chosen_deg=lb['chosen'], chosen_dir=lb['chosen_dir'])
    message.boson = beam.get('boson', '')
    message.sigma_total_cm2 = getattr(col, 'sigma_total_cm2', 0.0)
    message.interaction_probability = getattr(col, 'interaction_probability', 0.0)
    message.sqrt_s_mev = getattr(col, 'sqrt_s', math.sqrt(col.m ** 2 + 2 * col.m * col.energy))
    message.beam_momentum_mev = col.momentum
    message.angle_sampled = bool(getattr(col, 'angle_sampled', False))
    message.angle_median_deg = getattr(col, 'angle_median', 0.0)
    message.angle_beyond_30 = getattr(col, 'angle_beyond_30', 0.0)
    if col.kind == 'had' and 'theta_lab' in event:
        message.angle_deg = event['theta_lab']                 # diffraction : l'angle tiré du projectile
    if 'annihilation_point' in res['info']:
        message.annihilation_fm.extend(float(v) for v in res['info']['annihilation_point'])
    message.q_vec.extend(float(v) for v in event['q_vec'])
    message.photon_from_fm.extend(float(v) for v in tracks['photon_from'])
    message.photon_to_fm.extend(float(v) for v in tracks['photon_to'])
    message.out_direction.extend(float(v) for v in tracks['out_direction'])
    message.pre_roll_fmc = tracks['pre_roll']
    message.w_max_mev = col.w_max
    message.electron_path_fm.extend(float(v) for v in np.asarray(tracks['path']).ravel())
    message.coulomb_deflection_deg = math.degrees(tracks['deflection'])
    message.coulomb_max_deg = math.degrees(tracks['max_deflection'])
    for step in sorted(res['steps'], key=lambda s: s['time']):
        message.steps.add(time_fmc=step['time'], title=step['title'], formula=step['formula'], values=step['values'],
                          result=step['result'])
    if col.kind == 'had':
        message.note = ("Force forte, pas de boson échangé à un seul quark. σ_tot de Donnachie–Landshoff (1992), extrapolée sous "
                        "√s ≈ 6 GeV ; σ_él par le théorème optique (pic e^(Bt), partie réelle négligée) ; Δ(1232) en Breit–Wigner pour "
                        "un pion ; inélastique = échange de couleur et deux cordes (Lund). La contraction de Lorentz du projectile "
                        "n'est pas dessinée.")
    elif col.kind == 'cc':
        message.note = ("Courant chargé, un W échangé. Quasi-élastique : Llewellyn Smith (g_A = −1,2754, M_A = 1,03 GeV) ; "
                        "résonances par dualité (Rein–Sehgal non implémenté) ; profond inélastique : F₂ et xF₃ par DGLAP. La masse du "
                        "muon est dans la cinématique, pas dans le tenseur leptonique.")
    else:
        message.note = ("Premier ordre en α (un photon échangé), masse du lepton exacte. Cas calculés : élastique (Rosenbluth "
                        "généralisé, facteurs de forme de Kelly), Δ(1232) (Breit–Wigner normalisé à G*_M(0) = 3,02), résonances "
                        "supérieures (dualité de Bloom–Gilman) et profond inélastique (F₂ par DGLAP). e⁺ et e⁻ ont ici la même "
                        "section efficace (l'échange de deux photons, ≈ 1 %, est négligé). Le paramètre d'impact dessiné est "
                        "illustratif.")
    key = state['run']
    if sent is not None and sent.get('collision_run') == key:
        return
    if col.kind == 'had':
        if sent is not None:
            sent['collision_run'] = key
        return
    spectrum = col.spectrum()
    for name in spectrum:
        w, xs = spectrum[name]
        if len(w) == 0:
            continue
        curve = message.spectrum.add(name=name)
        curve.w_mev.extend(float(v) for v in w)
        curve.xs.extend(float(v) for v in xs)
    if sent is not None:
        sent['collision_run'] = key


def fill_hadron(message, state):
    """L'atelier : le verdict sur l'assemblage posé."""
    r = state['result']
    message.active = True
    message.run = state['run']
    message.valid = bool(r['valid'])
    message.reasons.extend(r['reasons'])
    message.content = r['content']
    message.charge = r['charge']
    message.baryon_number = r['baryon']
    message.strangeness = r['strangeness']
    message.charm = r['charm']
    message.beauty = r['beauty']
    for s in r.get('states', []):
        known = s['known']
        message.states.add(spin=s['spin'], model_mass_mev=s['model_mass'], known_name=known['name'] if known else '',
                           known_mass_mev=known['mass'] if known else 0.0, error_mev=known['error'] if known else 0.0)
    message.chosen_state = r.get('spin_index', 0)
    fate = r.get('fate') or {}
    message.fate_kind = fate.get('kind', '')
    message.fate_summary = fate.get('summary', '')
    life = fate.get('lifetime')
    message.lifetime_s = float(life) if life else 0.0
    message.width_mev = float(fate.get('width') or 0.0)
    if r.get('top'):
        message.top_lifetime_s = r['top']['lifetime']
        message.hadronisation_s = r['top']['hadronisation']
    chosen = (r.get('states') or [None])[min(r.get('spin_index', 0), max(0, len(r.get('states') or []) - 1))] if r.get('states') else None
    if chosen:
        message.mass_mev = chosen['known']['mass'] if chosen['known'] else chosen['model_mass']
        message.name = chosen['known']['name'] if chosen['known'] else ''
    for c in fate.get('channels') or []:
        if isinstance(c, dict) and 'branching' in c:
            message.channels.add(label=c['label'], branching=c['branching'], kind=c.get('kind', ''))
        elif isinstance(c, dict) and 'products' in c:
            message.channels.add(label=' + '.join(c['products']), branching=0.0, kind=c.get('kind', ''))
        elif isinstance(c, tuple):
            message.channels.add(label=' + '.join(c[1]), branching=c[0], kind='')
    d = r.get('decay')
    if d:
        message.decay_label = d['label']
        message.decay_mass_mev = d['parent_mass']
        import hadron_data
        weak = fate.get('kind', '') in ('weak', 'weak-heavy', 'top')
        for index, (name, mass, p) in enumerate(d['products']):
            try:
                charge = hadron_data.quantum_numbers(name)['charge']
            except KeyError:
                charge = {'W+': 1.0, 'b': -1 / 3}.get(name, 0.0)
            try:
                base, flipped = hadron_data.base_of(name)
                content = hadron_data.HADRONS[base]['content'] if base in hadron_data.HADRONS else ''
                content = content.swapcase() if flipped else content
            except KeyError:
                content = 'b' if name == 'b' else ''
            # dans une désintégration faible, le premier produit garde les quarks spectateurs ; les autres sortent du W
            message.products.add(name=name, mass_mev=mass, charge=charge, momentum_mev=[float(v) for v in p],
                                 energy_mev=float((mass ** 2 + float(p @ p)) ** .5), content=content,
                                 from_w=bool(weak and (index > 0 or name.startswith('W'))))


def fill_partons(message, cloud, view, sent):
    """La mer de partons : la résolution à chaque frame, les populations et courbes une seule fois par tirage."""
    import partons as pdf
    message.active = True
    message.run = view['run']
    message.hadron = cloud.hadron
    message.q2_gev2 = cloud.q2
    message.x_min = cloud.x_min
    message.alpha_s = cloud.alpha
    message.tau_fmc = cloud.tau
    message.duration_fmc = cloud.duration
    message.momentum_valence = cloud.momentum['valence']
    message.momentum_sea = cloud.momentum['sea']
    message.momentum_gluon = cloud.momentum['gluon']
    message.momentum_total = cloud.momentum['total']
    n = cloud.numbers
    message.n_gluon = n['g']
    message.n_sea_quark = n['ubar'] + n['dbar'] + n['sbar']
    message.n_valence = n['u_valence'] + n['d_valence']
    message.note = ("DGLAP à l'ordre dominant (nf = 3, α_s à une boucle) depuis une entrée de valence + gluon à Q₀² = 0,26 GeV² : "
                    "la mer est engendrée par g → q q̄. Elle est plus faible que mesurée (≈ 10 % contre ≈ 20 % de l'impulsion à 10 GeV²). "
                    "Les positions sont illustratives (les PDF n'en donnent pas) ; les taux de fusion sont fixés par l'équilibre détaillé.")
    key = view['run']
    if sent is not None and sent.get('partons_run') == key:
        return
    p = cloud.pdf
    grid = pdf.GRID
    thin = range(0, len(grid), 2)
    for name, values in (('u_valence', p['u'] - p['ubar']), ('d_valence', p['d'] - p['dbar']),
                         ('sea', 2 * (p['ubar'] + p['dbar'] + p['sbar'])), ('gluon', p['g'])):
        curve = message.curves.add(name=name)
        curve.x.extend(float(grid[i]) for i in thin)
        curve.xf.extend(float(values[i]) for i in thin)
    for parton in cloud.partons:
        message.partons.add(id=parton['id'], kind=parton['kind'], flavor=parton['flavor'] or '', color=parton['color'],
                            anticolor=parton['anti'], x=parton['x'], position_fm=parton['pos'].tolist(),
                            born_fmc=parton['born'], died_fmc=-1.0 if parton['died'] is None else parton['died'],
                            valence=parton['valence'])
    for event in cloud.events:
        message.events.add(time_fmc=event['time'], kind=event['kind'], parents=event['parents'], children=event['children'],
                           dx=event['dx'], dcharge=event['dcharge'], dcolour=event['dcolour'])
    if sent is not None:
        sent['partons_run'] = key


def fill_matter(message, m):
    """L'échelle de la matière, en unités réelles (Å, K, bar)."""
    sigma = m.sigma_a
    message.active = True
    message.symbol, message.name, message.count = m.symbol, m.name, m.n
    message.x.extend((m.pos[:, 0] * sigma).tolist())
    message.y.extend((m.pos[:, 1] * sigma).tolist())
    message.z.extend((m.pos[:, 2] * sigma).tolist())
    message.atom_temperature_k.extend((m.atom_t * m.epsilon_k).tolist())
    message.phase.extend(m.phase.tolist())
    message.box_x, message.box_y, message.box_z = (m.box * sigma).tolist()
    message.box_max_x = float(m.box_max[0] * sigma)
    message.temperature_k = m.temperature() * m.epsilon_k
    message.wall_temperature_k = m.wall_t * m.epsilon_k
    message.pressure_bar = m.pressure_bar()
    message.density_kg_m3 = m.density_kg_m3()
    message.solid, message.liquid, message.gas = m.fractions
    for hit in m.hits:
        message.hit_x.append(float(hit[0] * sigma))
        message.hit_y.append(float(hit[1] * sigma))
        message.hit_z.append(float(hit[2] * sigma))
    message.time_ps = m.time_ps()
    message.sigma_angstrom, message.epsilon_k, message.mass_u = m.sigma_a, m.epsilon_k, m.mass_u
    message.source = m.source
    message.melting_k = m.melting_k or 0.0
    message.boiling_k = m.boiling_k or 0.0
    message.critical_k = m.critical_k or 0.0
    message.triple_k = m.triple_k or 0.0
    message.supercritical = m.supercritical()
    message.model_critical_k = 1.085 * m.epsilon_k


def fill_star(message, s, paused):
    """Le cœur d'étoile : conditions, plasma, énergie, matière, fusions de la fenêtre."""
    import star as star_module
    message.active = True
    message.presets.extend(p["name"] for p in star_module.PRESETS)
    message.preset = s.preset
    message.about = star_module.PRESETS[s.preset]["about"]
    message.temperature_k = s.temperature
    message.density_g_cm3 = s.rho
    message.speed_index = s.speed_index
    message.speed_labels.extend(label for _, label in star_module.SPEEDS)
    message.paused = paused
    message.size_m = s.size_m
    if not s.ready or s.y is None:
        return
    message.ready = True
    net = s.net
    c = s.thermo()
    rates = s.rates_now() if s.coeffs is not None else np.zeros(len(net.rates))
    message.pressure_pa = c["pressure"]
    message.pressure_ions_pa = c["p_ion"]
    message.pressure_electrons_pa = c["p_e"]
    message.pressure_radiation_pa = c["p_rad"]
    message.degeneracy = c["eta"] if math.isfinite(c["eta"]) else -100.0
    message.ionized_fraction = c["ionized"]
    message.ipd_ev = c["lowering_ev"]
    message.spacing_m = c["spacing_m"]
    message.optical_depth = c["optical_depth"]
    message.age_s = s.time_s
    message.power_w_kg = s.power_w_kg(rates)
    message.neutrino_w_kg = s.neutrino_w_kg(rates)
    message.energy_j_kg = s.energy_j_kg
    message.neutrino_j_kg = s.neutrino_j_kg
    created, destroyed = s.created_destroyed_per_kg()
    x = s.mass_fractions()
    for i, nucleus in enumerate(net.nuclei):
        counts = s.box_counts.get(i, [0, 0])
        message.species.add(
            z=int(nucleus.Z), a=int(nucleus.A), label=star_module.label_of(nucleus),
            name=star_module.name_of(nucleus), spin_parity=star_module.spin_of(nucleus),
            mass_fraction=float(x[i]), initial_mass_fraction=float(s.initial_x[i]),
            created_per_kg=float(created[i]), destroyed_per_kg=float(destroyed[i]),
            mean_charge=float(c["charges"].get(int(nucleus.Z), 0.0)),
            box_created=counts[0], box_destroyed=counts[1], mass_u=float(net.mass_u[i]))
    for j, rate in enumerate(net.rates):
        per_kg = float(rates[j]) * star_module.N_A * 1e3
        message.reactions.add(
            label=star_module.reaction_label(rate), chain=s.chain_of(rate), per_kg_s=per_kg,
            power_w_kg=per_kg * float(net.q[j] - net.nu[j]) * star_module.MEV_J,
            q_mev=float(net.q[j]), neutrino_mev=float(net.nu[j]),
            reactants=[net.index[n.raw] for n in rate.reactants],
            products=[net.index[n.raw] for n in rate.products], weak=bool(rate.weak))
    for pid, species in s.particles.items():
        message.particles.add(id=pid, species=species)
    for event in s.take_events():
        message.fusions.add(reaction=event["rate"], consumed=event["consumed"], created=event["created"],
                            arrived=event["arrived"], q_mev=event["q"], neutrino_mev=event["nu"])
    message.window_rate = s.box_rate
    message.hidden = s.hidden
    message.window_size_m = (len(s.particles) / max(c["ions_m3"], 1e-30)) ** (1 / 3)
    message.thermal_speed_m_s = s.thermal_speed_m_s(1.007)
    message.kt_kev = star_module.K_B * s.temperature / star_module.sc.e / 1e3
    fuel, left = s.fuel_left_s(rates)
    message.fuel = star_module.label_of(net.nuclei[net.index[fuel]])
    message.fuel_left_s = left if math.isfinite(left) else 0.0
    wait = s.proton_wait_s(rates)
    message.proton_wait_s = wait if math.isfinite(wait) else 0.0
    if rates.any() and s.temperature >= star_module.FUSION_THRESHOLD_K:
        main = s.main_reaction(rates)
        message.main_reaction = star_module.reaction_label(net.rates[main])
        gamow = s.gamow(net.rates[main])
        if gamow:
            message.barrier_kev = gamow["barrier_kev"]
            message.gamow_kev = gamow["peak_kev"]
    message.story.extend(star_module.story(s, rates))


def _lab_nucleus(message, lab, z, a, with_cloud=False):
    import atomic_data
    card = lab.describe(z, a)
    message.z, message.a, message.label, message.name = z, a, card["label"], card["name"]
    message.symbol, message.mass_u, message.estimated = card["symbol"], card["mass_u"], card["estimated"]
    message.spin_parity, message.status, message.radius_fm = card["spin"], card["status"], card["radius_fm"]
    message.color = card["color"]
    message.magnetic_moment = card["magnetic"]
    message.ionization_ev = (atomic_data.ionization_energy_ev(z, z - 1) or 0.0) if 0 < z <= 118 else 0.0


def _reactor_particle(message, z, a, b_meet=None, data=None):
    """Une particule du réacteur : identité mesurée, nucléons, et (si elle a volé) sa trajectoire."""
    import fusion
    import reactor
    message.z, message.a = z, a
    message.label = "n" if (z, a) == (0, 1) else "p" if (z, a) == (1, 1) else fusion.label(z, a)
    message.name = fusion.full_name(z, a)
    message.spin_parity = nuclear_data.spin_parity(z, a) if (z, a) != (2, 5) else "3/2+"
    message.magnetic_moment = fusion.magnetic_moment(z, a)
    message.radius_fm = reactor.sphere_radius(z, a)
    weighed = nuclear_data.measured_binding(z, a) if z >= 1 and a > 1 else None
    message.binding_per_nucleon_mev = weighed[2] / a if weighed else 0.0
    for x, y, w, kind in reactor.nucleon_layout(z, a):
        message.nucleons.extend((x, y, w, kind))
    message.charged = z > 0
    if data is None:
        return
    times, points = data["path"]
    for t, (x, y, w) in zip(times, points):
        message.path.extend((float(t), float(x), float(y), float(w)))
    fine_t, fine_p = data["fine"]
    for t, (x, y, w) in zip(fine_t, fine_p):
        message.fine.extend((float(t), float(x), float(y), float(w)))
    v = np.asarray(data["v"], dtype=float)
    message.vx, message.vy, message.vz = (float(c) for c in v)
    speed = float(np.linalg.norm(v))
    message.speed_m_s = speed
    mass_mev = reactor.nuclear_mass_mev(z, a)
    gamma = 1 / math.sqrt(max(1 - (speed / 299792458.0) ** 2, 1e-12))
    message.kinetic_mev = data.get("kinetic_mev", (gamma - 1) * mass_mev)
    b = float(np.linalg.norm(b_meet))
    if z > 0 and b > 0:
        b_hat = b_meet / b
        v_perp = float(np.linalg.norm(v - np.dot(v, b_hat) * b_hat))
        m_kg = mass_mev / reactor.MEV_PER_U * reactor.U_KG
        message.gyro_radius_m = m_kg * v_perp / (z * 1.602176634e-19 * b)
        message.gyro_period_s = 2 * math.pi * m_kg / (z * 1.602176634e-19 * b)
    spin = reactor.fusion_spin(z, a)
    if spin > 0:
        moment = abs(message.magnetic_moment) * reactor.MU_N
        message.larmor_hz = moment * b / (spin * 6.62607015e-34)
    axis = np.asarray(data.get("spin", np.zeros(3)), dtype=float)
    message.spin_x, message.spin_y, message.spin_z = (float(c) for c in axis)
    message.confined = bool(data.get("confined", True))
    message.end_t = float(data.get("end_t", 0.0))
    message.wall_m = float(data.get("wall_m", 0.0))


def fill_reactor(message, r):
    """Le réacteur : le tokamak (une fois), le plasma, et le dernier couple suivi (une fois par tir)."""
    import fusion
    import reactor
    from fusion_dynamics import FusionExperiment
    if isinstance(r, FusionExperiment):
        fill_collision(message, r)
        return
    tok = r.tokamak
    message.active = True
    message.fuels.extend(fuel["name"] for fuel in reactor.FUELS)
    message.fuel = r.fuel_index
    message.temperature_kev = r.kt_kev
    message.temperature_text = reactor._kelvin_text(r.kt_kev)
    message.major_radius_m, message.minor_radius_m = tok.R0, tok.A
    message.kappa, message.field_t = tok.KAPPA, tok.B0
    message.density_m3 = tok.DENSITY
    plasma = r.plasma()
    message.reactivity_m3_s = float(plasma["reactivity"])
    message.power_w_m3 = float(plasma["power_w_m3"])
    message.wait_s = float(plasma["wait_s"])
    message.gamow_peak_kev = float(plasma["gamow_kev"])
    message.run = r.run
    if not r.sent_geometry:
        message.q_axis = tok.safety_factor(0.05)
        message.q_edge = tok.safety_factor(0.95)
        message.boundary.extend(tok.contour(1.0))
        message.wall.extend(tok.contour(tok.WALL))
        for rho, points in tok.field_lines():
            message.field_line_sizes.append(len(points))
            message.field_line_rho.append(rho)
            for x, y, z in points:
                message.field_lines.extend((x, y, z))
        r.sent_geometry = True
    fuel = r.fuel
    pair = fusion.Pair(fuel["a"], fuel["b"])
    for x in np.geomspace(0.3, 300.0, 140):
        message.potential_r.append(float(x))
        message.potential_v.append(float(pair.v(x)))
    _reactor_particle(message.compound, *fuel["compound"])
    s = r.shot
    if s is None or r.sent_run == s["run"]:
        if s is None:
            for z, a in (fuel["a"], fuel["b"]):
                _reactor_particle(message.reactants.add(), z, a)
        return
    r.sent_run = s["run"]
    message.fired = True
    n = s["nuclear"]
    for data in s["reactants"]:
        _reactor_particle(message.reactants.add(), data["z"], data["a"], s["b_meet"], data)
    for data in s["products"]:
        _reactor_particle(message.products.add(), data["z"], data["a"], s["b_meet"], data)
    names = " + ".join(m.label for m in message.products)
    message.reaction = f"{message.reactants[0].label} + {message.reactants[1].label} → {names}"
    message.meet_x, message.meet_y, message.meet_z = (float(c) for c in s["meet"])
    message.field_x, message.field_y, message.field_z = (float(c) for c in s["b_meet"])
    message.vcm_x, message.vcm_y, message.vcm_z = (float(c) for c in s["v_cm"])
    message.axis_x, message.axis_y, message.axis_z = (float(c) for c in s["axis"])
    message.impact_x, message.impact_y, message.impact_z = (float(c) for c in s["impact_dir"])
    message.ecm_kev = s["e_kev"]
    message.impact_fm = n["impact_fm"]
    message.r_min_fm = n["r_min_fm"]
    message.contact_fm = n["contact_fm"]
    message.barrier_kev = n["barrier_mev"] * 1000
    message.barrier_r_fm = n["barrier_r_fm"]
    message.gamow = n["gamow"]
    message.tunneling = n["tunneling"]
    message.cross_section_b = s["sigma_b"]
    for t, x, y in zip(n["times_s"], n["x"], n["y"]):
        message.nuclear_path.extend((float(t), float(x), float(y)))
    message.q_mev = s["q_mev"]
    message.compound_lifetime_s = s["lifetime_s"]
    message.spin_text = s["spin_text"]
    message.rho = s["rho"]
    forces = r.forces()
    message.coulomb_n, message.strong_n = forces["coulomb"], forces["strong"]
    message.lorentz_n, message.dipole_n, message.gravity_n = forces["lorentz"], forces["dipole"], forces["gravity"]
    for quantity, value, meaning in r.facts():
        message.facts.add(quantity=quantity, value=value, meaning=meaning)


def _fill_lawson(message, experiment):
    import math
    p = experiment.plasma()
    message.temperature_kev, message.density_m3 = p['temperature_kev'], p['density']
    message.confinement_s, message.reactivity_m3_s = p['tau'], p['reactivity']
    message.fusion_w_m3, message.deposited_w_m3 = p['fusion'], p['deposited']
    message.bremsstrahlung_w_m3 = p['brems']
    message.conduction_w_m3 = p['conduction'] if math.isfinite(p['conduction']) else 1e300
    message.gain = -1.0 if p['ignited'] else p['gain']
    message.ignited = p['ignited']
    message.triple_product, message.required_triple = p['triple'], p['need']
    message.curve_t_kev.extend(p['curve_t'])
    message.curve_ignition.extend(p['ignition_curve'])
    message.curve_breakeven.extend(p['breakeven_curve'])
    message.q_mev, message.charged_fraction = p['q'], p['charged']
    message.model, message.verdict = p['model'], p['verdict']
    message.wait_s = p['wait'] if math.isfinite(p['wait']) else 0.0
    message.best_t_kev, message.best_triple = p['best_t'], p['best_triple']


def fill_collision(message, experiment):
    """Calculs envoyés une fois ; le client interpole seulement ces échantillons."""
    import reactor
    import math
    from scipy import constants as sc
    message.active = True
    message.fuels.extend(f['name'] for f in reactor.FUELS)
    message.fuel = experiment.fuel_index
    message.temperature_kev = experiment.kt_kev
    message.field_t = experiment.field_t
    message.run = experiment.run
    from fusion_dynamics import collision_catalog, nucleus_properties
    import fusion
    if not experiment.catalog_sent:
        for element in collision_catalog():
            entry = message.catalog.add(z=element['z'], symbol=element['symbol'], name=element['name'],
                                        category=element['category'], color=element['color'],
                                        row=element['row'], col=element['col'], default_a=element['default_a'])
            for iso in element['isotopes']:
                entry.isotopes.add(a=iso['a'], mass_u=iso['mass_u'], stable=iso['stable'],
                                   half_life_s=iso['half_life_s'] or 0., abundance=iso['abundance'] or 0.)
        experiment.catalog_sent = True
    for target, za in ((message.selected_a, experiment.a), (message.selected_b, experiment.b)):
        for key, value in nucleus_properties(*za).items():
            setattr(target, key, value)
    _fill_lawson(message.lawson, experiment)
    message.requested_energy_kev = experiment.energy_kev
    message.requested_impact_fm = experiment.impact_fm
    message.thermal_mode = experiment.thermal_mode
    message.error = experiment.error
    message.status = 'Noyaux entierement ionises ; energie du centre de masse'
    pair = fusion.Pair(experiment.a, experiment.b)
    reduced_a = experiment.a[1] * experiment.b[1] / (experiment.a[1] + experiment.b[1])
    message.suggested_energy_kev = min(2e6, max(64., 1.3 * pair.barrier * 1000), 9.5e3 * reduced_a)
    message.barrier_kev = pair.barrier * 1000
    message.contact_fm = pair.contact
    s = experiment.shot
    if s is None:
        for z, a in (experiment.fuel['a'], experiment.fuel['b']):
            _reactor_particle(message.reactants.add(), z, a)
        return
    if experiment.sent_run == s['run']:
        return
    experiment.sent_run = s['run']
    message.fired = True
    out = message.collision
    out.run, out.fused = s['run'], s['fused']
    out.energy_kev, out.probability = s['energy'], s['probability']
    out.cross_section_b, out.aperture_fm, out.impact_fm = s['sigma_b'], s['aperture'], s['impact']
    out.time_scale_s, out.q_mev, out.energy_error = s['time_scale'], s['q'], s['error']
    out.reaction, out.field_t = s['reaction'], s['field']
    out.radial_fm.extend(s['r'])
    out.potential_mev.extend(s['v'])
    out.wave_amplitude.extend(s['wave'])
    out.contact_fm, out.depth_mev = s['contact'], s['depth']
    out.compound_duration_s, out.wkb_transmission = s['lifetime'], s['wkb']
    out.trials, out.reactions, out.barrier_mev = s['trials'], s['reactions'], s['barrier']
    out.reduced_mass_mev = s['mu']
    out.model, out.outcome, out.outcome_resolved = s['model'], s['outcome'], s['resolved']
    out.excitation_mev, out.notes = s['excitation'], s['notes']
    out.compound_z, out.compound_a = s['compound']
    out.thermal_kev = s.get('thermal', 0.)
    out.rarity_log10 = s.get('rarity', 0.)
    for sample in s['samples']:
        frame = out.samples.add(time_s=sample['time'], phase=sample['phase'],
                                coulomb_n=sample['coulomb'], nuclear_n=sample['strong'],
                                kinetic_mev=sample['kinetic'], potential_mev=sample['potential'])
        for body in sample['bodies']:
            item = frame.bodies.add(z=body['z'], a=body['a'], label=body['label'],
                             position=body['position'], velocity=body['velocity'], spin=body['spin'],
                             radius_fm=body['radius'], magnetic_moment=body['moment'])
            item.color_hex, item.name, item.spin_parity = body['color'], body['name'], body['spin_parity']
            item.mass_mev, item.excitation_mev, item.kind = body['mass'], body['excitation'], body['kind']
            item.known_moment = body['known_moment']
            speed2 = sum(v*v for v in body['velocity']) / sc.c**2
            item.kinetic_mev = body.get('kinetic', body['mass'] * (1/math.sqrt(max(1e-15, 1-speed2))-1))


def fill_lab(message, lab):
    """Le laboratoire de fusion : les deux noyaux, la colline, le dernier tir, et ce qu'ils veulent dire."""
    import collider
    import fusion
    message.active = True
    if not lab.catalog_sent:
        for element in fusion.catalog():
            entry = message.catalog.add(z=element["z"], symbol=element["symbol"], name=element["name"],
                                        category=element["category"], color=element["color"],
                                        row=element["row"], col=element["col"], default_a=element["default_a"])
            for iso in element["isotopes"]:
                entry.isotopes.add(a=iso["a"], mass_u=iso["mass_u"], stable=iso["stable"],
                                   half_life_s=iso["half_life_s"] or 0.0, abundance=iso["abundance"] or 0.0)
        lab.catalog_sent = True
    _lab_nucleus(message.a, lab, *lab.a)
    _lab_nucleus(message.b, lab, *lab.b)
    zc, ac = lab.a[0] + lab.b[0], lab.a[1] + lab.b[1]
    if zc <= fusion.HEAVIEST_KNOWN_Z and fusion.mass_u(zc, ac)[0] is not None:
        _lab_nucleus(message.compound, lab, zc, ac)
    pair = lab.pair()
    energy = lab.energy_kev / 1000
    message.energy_kev = lab.energy_kev
    message.impact_fm = lab.impact_fm
    message.angular_momentum = lab.angular_momentum()
    for r in np.geomspace(0.3, 400.0, 160):
        message.potential_r.append(float(r))
        message.potential_v.append(float(pair.v(r)))
    message.barrier_mev = pair.barrier
    message.barrier_r_fm = pair.barrier_r
    message.contact_fm = pair.contact
    message.inside_fm = pair.inside
    message.well_mev = min(pair.v(r) for r in np.linspace(0.3, pair.barrier_r, 200))
    turning = pair.turning_point(energy)
    message.turning_fm = turning if math.isfinite(turning) else 0.0
    message.tunneling = lab.tunneling()
    message.probability = lab.probability()
    message.temperature_k = lab.energy_kev * fusion.KEV_TO_K
    message.preview = lab.preview()
    level, verdict = lab.energy_verdict()
    message.energy_level, message.energy_verdict = level, verdict
    for products, share, q, source, weak, kind in lab.options():
        message.channels.add(label=fusion.group_names(products), share=share, q_mev=q, kind=kind)
    if lab.options():
        message.source = lab.options()[0][3]
    message.strong_range_fm = collider.STRONG_RANGE_FM
    message.force_contact_n = pair.coulomb_force_n(pair.contact)
    message.nuclear_depth_mev = pair.depth
    message.diffuseness_fm = fusion.DIFFUSENESS_FM
    message.reduced_mass_mev = pair.mu
    message.thermal = lab.thermal
    message.mass_a_u = fusion.mass_u(*lab.a)[0] or 1.0
    message.mass_b_u = fusion.mass_u(*lab.b)[0] or 1.0
    message.gamow_peak_kev = lab.gamow_peak_mev() * 1000
    for quantity, value, meaning in lab.interpretations():
        message.facts.add(quantity=quantity, value=value, meaning=meaning)
    message.expert.extend(lab.expert())
    r = lab.result
    if r is None:
        return
    message.run = r["run"]
    message.fired = True
    message.fused = r["fused"]
    message.never = r["never"]
    message.attempts = r["attempts"]
    message.above = bool(r.get("above"))
    for t, x, y in r.get("path", []):
        message.path.extend((t, x, y))
    message.tunnel_index = r.get("tunnel_index", -1)
    message.closest_fm = r.get("closest", 0.0)
    message.duration_s = r.get("duration_s", 0.0)
    message.q_mev = r["q"]
    message.mass_before_u = r["mass_before"]
    message.mass_after_u = r["mass_after"]
    message.kind = r.get("kind", "")
    message.reflected = bool(r.get("reflected"))
    message.deflected = bool(r.get("deflected"))
    message.orbital_l = int(r.get("orbital", 0))
    for ghost in r.get("ghosts", []):
        message.ghost_sizes.append(len(ghost))
        for t, x, y in ghost:
            message.ghosts.extend((t, x, y))
    message.shot_energy_kev = r.get("shot_energy", lab.energy_kev / 1000) * 1000
    message.impact_used_fm = r.get("impact", 0.0)
    message.precession_deg = math.degrees(r.get("precession", 0.0))
    message.lorentz_max_n = r.get("lorentz_max", 0.0)
    message.lorentz_deflection_fm = r.get("deflection", 0.0)
    axis = r.get("spin_axis", (0.0, 1.0, 0.0))
    message.spin_ax, message.spin_ay, message.spin_az = axis
    message.quasi_fission = bool(r.get("quasi"))
    message.excitation_mev = r.get("excitation", 0.0)
    for p in r["products"]:
        name = ("photon γ" if p["kind"] == "gamma" else "positon et neutrino" if p["kind"] == "positron"
                else fusion.full_name(p["z"], p["a"]))
        lab_label = lab.product_label(p)
        d = p.get("dir", (1.0, 0.0, 0.0))
        entry = message.products.add(z=p["z"], a=p["a"], label=lab_label, name=name, kinetic_mev=p["kinetic"],
                                     speed_c=p["speed_c"], kind=p["kind"], dx=d[0], dy=d[1], dz=d[2])
        if p["kind"] in ("nucleus", "neutron") and p["a"] > 0:
            card = lab.describe(p["z"], p["a"])
            entry.spin_parity = card["spin"] or ""
            entry.magnetic_moment = card["magnetic"]
            entry.color = card["color"] if p["z"] > 0 else "#94A3B8"
            entry.status = card["status"]
            entry.element = fusion.element_name(p["z"])
    for step in r.get("chain", []):
        message.chain.add(index=step["index"], daughter=fusion.label(*step["daughter"]),
                          ejected=fusion.label(*step["ejected"]), q_mev=step["q"], half_life_s=step["half_life"])
    message.verdict.extend(lab.verdict())


def _orbital_set(message, levels):
    """levels : [alpha, beta], chacun une liste de (énergie, occupation[, symétrie])."""
    for spin, target in ((0, message.alpha), (1, message.beta)):
        for level in levels[spin]:
            target.add(energy_ev=level[0], occupation=level[1], label=level[2] if len(level) > 2 else "")


def fill_chemistry(message, molecule, sent_table):
    """La molécule ; la table des calculs (lourde) n'est envoyée qu'une fois par version.

    sent_table : dictionnaire {"version": ...} propre à la connexion, mis à jour ici.
    """
    import chemistry
    p = molecule.pair
    message.active = True
    message.symbol_a, message.symbol_b = p.symbols
    message.name_a, message.name_b = p.names
    message.z_a, message.z_b = p.za, p.zb
    message.status = p.status
    message.progress = p.progress
    message.ready = p.ready
    message.failed = p.failed
    message.chi_a, message.chi_b = p.chi
    message.method = chemistry.METHOD
    if not p.ready:
        return
    message.table_version = p.version
    message.spin = p.spin
    message.spin_a, message.spin_b = p.atom_spins
    message.de_ev, message.re_angstrom, message.period_fs, message.bound = p.de, p.re, p.period_fs, p.bound
    message.grid_nx, message.grid_ny = chemistry.GRID_NX, chemistry.GRID_NY
    message.extent_x, message.extent_y = chemistry.EXTENT_X, chemistry.EXTENT_Y
    _orbital_set(message.atom_a, p.atom_levels[0])
    _orbital_set(message.atom_b, p.atom_levels[1])
    if molecule.r is not None:
        message.r = molecule.r
        message.velocity = molecule.velocity
        message.energy_ev = p.energy_at(molecule.r)
        message.kinetic_ev = molecule.kinetic_ev()
        message.held = molecule.held
        message.time_fs = molecule.time_fs
    key = (p.za, p.zb, p.version)
    if sent_table is not None and sent_table.get("key") == key:
        return
    for point in p.points:
        entry = message.points.add(r=point["r"], energy_ev=point["energy"], charge_a=point["charge_a"],
                                   bond_order=point["bond_order"])
        entry.density.extend(point["density"].tolist())
        entry.difference.extend(point["difference"].tolist())
        _orbital_set(entry.orbitals, point["orbitals"])
    if sent_table is not None:
        sent_table["key"] = key


def _nuclide(daughter):
    z, a, state = daughter
    return nuclear_data.format_name(z, a, state)


class UniverseEngineServicer(genesis_pb2_grpc.UniverseEngineServicer):

    def __init__(self, repository):
        self.repository = repository

    def GetPeriodicTable(self, request, context):
        """Sert la table complète : le client en a besoin pour son sélecteur
        et pour tracer la vallée de stabilité."""
        table = genesis_pb2.PeriodicTable(protocol_version=PROTOCOL_VERSION)
        for element in self.repository.all():
            table.elements.append(to_element_message(element))
        for particle in PARTICLES:
            table.particles.add(**particle)
        print(f"Table periodique servie ({len(table.elements)} elements).")
        return table

    def RunSimulation(self, request_iterator, context):
        print("Un client s'est connecte. Nouvel atome vierge.")
        # Chaque connexion a son propre atome et sa propre horloge.
        session = Session(self.repository)
        actions = queue.Queue()
        closed = threading.Event()

        def read_actions():
            try:
                for action in request_iterator:
                    actions.put(action)
            except grpc.RpcError:
                pass
            finally:
                closed.set()

        threading.Thread(target=read_actions, daemon=True).start()

        tick = 0
        evaluation, key, base = None, None, None
        sent_table = {}
        last_sent = 0.0
        previous = time.monotonic()
        while context.is_active() and not (closed.is_set() and actions.empty()):
            try:
                first = actions.get(timeout=TICK_S)
                pending = [first]
                while not actions.empty():
                    pending.append(actions.get_nowait())
            except queue.Empty:
                pending = []

            now = time.monotonic()
            events = []
            last_action = ""
            for action in pending:
                try:
                    events += self._apply(session, action)
                except Exception as error:  # la session doit survivre a toute action
                    import traceback
                    traceback.print_exc()
                    target = session.reactor
                    if target is not None and hasattr(target, 'error'):
                        target.error = f'Erreur interne pendant {Action.ActionType.Name(action.type)} : {error}'
                last_action = Action.ActionType.Name(action.type)
            events += session.advance(now - previous)
            previous = now

            new_key = (session.atom.protons, session.atom.neutrons, session.atom.electrons,
                       session.electron_state, bool(session.excited), session.isomer)
            structure_changed = new_key != key
            if structure_changed:
                evaluation = session.evaluate(last_action or None)
                base = build_frame(0, evaluation, self.repository.source)
                key = new_key

            flowing = not session.paused and (session.electron_rate() > 0 or session.nuclear_rate() > 0
                                              or session.photons_in_flight)
            due = flowing and now - last_sent >= HEARTBEAT_S
            # La matière bouge à chaque pas : une frame par pas, sinon on ne la verrait pas vivre.
            due = due or (session.matter is not None and not session.paused)
            # L'étoile vit en continu (ou attend sa bibliothèque nucléaire).
            due = due or (session.star is not None and (not session.paused or not session.star.ready))
            # La molécule aussi : ses noyaux bougent, ou son calcul avance.
            due = due or (session.molecule is not None and (not session.paused or not session.molecule.pair.ready))
            if pending or events or structure_changed or due:
                tick += 1
                last_sent = now
                yield session_frame(tick, session, evaluation, self.repository.source, events,
                                    last_action, structure_changed, base, sent_table)
                for event in events:
                    print(f"  t={format_duration(event['time_s'])} [{event['kind']}] {event['title']}")

        print("Le client a ferme la connexion.")

    @staticmethod
    def _apply(session, action):
        """Applique une action de l'utilisateur ; retourne les événements produits."""
        kind = action.type
        simple = {
            Action.ADD_PROTON: session.add_proton,
            Action.REMOVE_PROTON: session.remove_proton,
            Action.ADD_NEUTRON: session.add_neutron,
            Action.REMOVE_NEUTRON: session.remove_neutron,
            Action.ADD_ELECTRON: session.add_electron,
            Action.REMOVE_ELECTRON: session.remove_electron,
            Action.RESET: session.reset,
            Action.HEAT: session.heat,
            Action.TOGGLE_PAUSE: session.toggle_pause,
            Action.NEXT_EVENT: session.next_event,
        }
        if kind in simple:
            return simple[kind]()
        if kind == Action.LOAD_ELEMENT:
            return session.load_element(action.value)
        if kind == Action.FIRE_PHOTON:
            return session.fire_photon(action.amount)
        if kind == Action.SET_SPEED:
            return session.set_speed(action.value)
        if kind == Action.SET_STEP_MODE:
            return session.set_step_mode(action.value)
        if kind == Action.FUSION_START:
            return session.start_fusion()
        if kind == Action.SUBATOMIC_START:
            return session.start_subatomic()
        if kind == Action.SUBATOMIC_STOP:
            return session.stop_subatomic()
        if kind == Action.SUBATOMIC_RESET:
            return session.start_subatomic()
        if kind == Action.SUBATOMIC_SET_PROTONS:
            return session.set_subatomic_counts('protons', action.value)
        if kind == Action.SUBATOMIC_SET_NEUTRONS:
            return session.set_subatomic_counts('neutrons', action.value)
        if kind == Action.SUBATOMIC_SET_ELECTRONS:
            return session.set_subatomic_counts('electrons', action.value)
        if kind == Action.SUBATOMIC_SET_IMPULSE:
            return session.set_subatomic_impulse(action.amount)
        if kind == Action.SUBATOMIC_KICK:
            return session.kick_subatomic(int(action.amount), action.value, action.vector)
        if kind == Action.NUCLEON_START:
            return session.start_nucleon()
        if kind == Action.NUCLEON_STOP:
            return session.stop_nucleon()
        if kind == Action.NUCLEON_SET_HADRON:
            return session.set_nucleon_hadron(action.value)
        if kind == Action.NUCLEON_STRIKE:
            return session.strike_nucleon(action.value, action.amount, list(action.vector))
        if kind == Action.NUCLEON_RESET:
            return session.reset_nucleon()
        if kind == Action.PARTONS_START:
            return session.start_partons(action.amount, action.vector[0] if action.vector else 0.0)
        if kind == Action.PARTONS_SET:
            return session.set_partons(action.amount, action.vector[0] if action.vector else 1e-3)
        if kind == Action.PARTONS_STOP:
            return session.stop_partons()
        if kind == Action.PARTONS_RESET:
            return session.reset_partons()
        if kind == Action.COLLISION_RUN:
            return session.run_collision(action.value, action.amount, action.vector[0] if action.vector else 30.0,
                                         int(action.vector[1]) if len(action.vector) > 1 else 0)
        if kind == Action.COLLISION_RESAMPLE:
            return session.resample_collision()
        if kind == Action.COLLISION_FORCE:
            return session.force_collision(action.value)
        if kind == Action.HADRON_TEST:
            return session.test_hadron(action.value, list(action.vector))
        if kind == Action.HADRON_DECAY:
            return session.redecay_hadron()
        if kind == Action.HADRON_CLEAR:
            return session.clear_hadron()
        if kind == Action.FUSION_STOP:
            return session.stop_fusion()
        if kind == Action.SET_NUCLEUS_A:
            return session.set_nucleus("a", action.value)
        if kind == Action.SET_NUCLEUS_B:
            return session.set_nucleus("b", action.value)
        if kind == Action.SET_COLLISION_ENERGY:
            return session.set_collision_energy(action.amount)
        if kind == Action.COLLIDE:
            return session.collide(action.value)
        if kind == Action.SET_IMPACT:
            return session.set_impact(action.amount)
        if kind == Action.SET_THERMAL:
            return session.set_thermal(action.value)
        if kind == Action.REACTOR_START:
            return session.start_reactor()
        if kind == Action.REACTOR_STOP:
            return session.stop_reactor()
        if kind == Action.REACTOR_FIRE:
            return session.fire_reactor(action.value == 1)
        if kind == Action.SET_REACTOR_FUEL:
            return session.set_reactor_fuel(action.value)
        if kind == Action.SET_REACTOR_TEMPERATURE:
            try:
                return session.set_reactor_temperature(action.amount)
            except ValueError as error:
                if session.reactor is not None:
                    session.reactor.error = str(error)
                return []
        if kind == Action.SET_REACTOR_FIELD:
            if session.reactor is not None and hasattr(session.reactor, 'set_field'):
                try:
                    session.reactor.set_field(action.amount)
                except ValueError as error:
                    session.reactor.error = str(error)
            return []
        if kind in (Action.SET_REACTOR_NUCLEUS_A, Action.SET_REACTOR_NUCLEUS_B, Action.SET_REACTOR_DENSITY,
                    Action.SET_REACTOR_CONFINEMENT,
                    Action.SET_REACTOR_ENERGY, Action.SET_REACTOR_IMPACT, Action.SET_REACTOR_THERMAL):
            experiment = session.reactor
            if experiment is None:
                return []
            try:
                if kind == Action.SET_REACTOR_NUCLEUS_A:
                    experiment.set_nucleus('a', action.value)
                elif kind == Action.SET_REACTOR_NUCLEUS_B:
                    experiment.set_nucleus('b', action.value)
                elif kind == Action.SET_REACTOR_ENERGY:
                    experiment.set_energy(action.amount)
                elif kind == Action.SET_REACTOR_IMPACT:
                    experiment.set_impact(action.amount)
                elif kind == Action.SET_REACTOR_DENSITY:
                    experiment.set_density(action.amount)
                elif kind == Action.SET_REACTOR_CONFINEMENT:
                    experiment.set_confinement(action.amount)
                else:
                    experiment.set_thermal(action.value != 0)
            except ValueError as error:
                experiment.error = str(error)
                return [{'kind': 'info', 'time_s': session.time_s, 'title': 'Parametre refuse', 'formula': '', 'detail': str(error)}]
            return []
        if kind == Action.STAR_START:
            return session.start_star()
        if kind == Action.STAR_STOP:
            return session.stop_star()
        if kind == Action.SET_STAR_PRESET:
            return session.set_star_preset(action.value)
        if kind == Action.SET_STAR_TEMPERATURE:
            return session.set_star_temperature(action.amount)
        if kind == Action.SET_STAR_DENSITY:
            return session.set_star_density(action.amount)
        if kind == Action.SET_STAR_SPEED:
            return session.set_star_speed(action.value)
        if kind == Action.CHEMISTRY_START:
            return session.start_chemistry(action.value)
        if kind == Action.CHEMISTRY_STOP:
            return session.stop_chemistry()
        if kind == Action.SET_BOND_LENGTH:
            return session.hold_bond(action.amount)
        if kind == Action.RELEASE_BOND:
            return session.release_bond()
        if kind == Action.MATTER_START:
            return session.start_matter()
        if kind == Action.MATTER_STOP:
            return session.stop_matter()
        if kind == Action.SET_WALL_TEMPERATURE:
            return session.set_wall_temperature(action.amount)
        if kind == Action.SET_PISTON:
            return session.set_piston(action.amount)
        # HELLO ne modifie rien : il sert à obtenir la frame initiale.
        return []


def serve():
    # Le journal contient des flèches et des exposants : une console qui ne
    # sait pas les écrire ne doit pas couper la connexion du client.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="backslashreplace")
    repository = ElementRepository()
    import star
    star.warm_up()          # la bibliothèque REACLIB met quelques secondes à se lire

    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    genesis_pb2_grpc.add_UniverseEngineServicer_to_server(
        UniverseEngineServicer(repository), server
    )

    port = os.environ.get("GRPC_PORT", "50051")
    server.add_insecure_port(f"[::]:{port}")
    server.start()
    print(f"Moteur atomique demarre sur le port {port} (protocole v{PROTOCOL_VERSION}).")
    server.wait_for_termination()


if __name__ == "__main__":
    serve()
