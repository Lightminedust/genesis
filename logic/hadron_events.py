"""Un événement hadron + nucléon, de bout en bout : l'approche du projectile, le contact, ce que deviennent les deux hadrons.

* **diffraction (élastique)** : les deux hadrons échangent de l'impulsion sans échanger de couleur ; −t est tiré dans le pic
  e^(Bt), chacun repart intact avec sa 4-impulsion exacte ;
* **formation du Δ** (pion) : l'antiquark du pion s'annihile avec un quark de même saveur du nucléon ; les trois quarks qui
  restent forment un Δ de masse W = √s, qui vit ħ/Γ puis se désintègre en N π (Clebsch–Gordan d'isospin) ;
* **échange de couleur (cordes)** : un gluon échange la couleur c entre un quark de chaque hadron — chacun garde son
  impulsion — ; deux cordes se tendent entre les morceaux qui s'éloignent et se rompent (`nucleon.py`, cordes de Lund).
  Les 4-impulsions finales sont remises exactement à Σ E = E_faisceau + M et Σ p = p_faisceau (`collision_events.on_shell`).

Tout est montré dans le **repère du centre de masse** (Σ p = 0, Σ E = √s) : les deux hadrons arrivent l'un vers l'autre à
±p*, et ce qui sort part dos à dos à ses vraies vitesses. Dans le laboratoire, tout filerait vers l'avant à 0,85–0,95 c et la
compression relativiste des vitesses cacherait la séparation (les morceaux ne s'écarteraient qu'à quelques centièmes de c).
Chaque hadron a son mouvement interne ralenti par sa dilatation du temps (facteur γ*) ; la contraction de Lorentz de son sac
n'est pas dessinée. Unités : fm, MeV, fm/c.
"""
import math
from types import SimpleNamespace

import numpy as np

import collision_events as ce
import hadronic as hd
import nucleon as nuc
import scattering as sc

PRE_ROLL_FM = ce.PRE_ROLL_FM
MESON_SIZE_FM = 0.55
_fmt = ce._fmt
NAMES = {'p': 'proton', 'n': 'neutron', 'π+': 'π⁺', 'π-': 'π⁻', 'π0': 'π⁰'}


# ================================================================== l'état du projectile
def _projectile(beam, seed):
    """Les quarks du projectile au repos : positions et impulsions internes, couleurs. Baryon : la gaussienne fondamentale du
    nucléon ; méson : une paire quark–antiquark de taille ≈ 0,55 fm, couleur et anticouleur."""
    if beam['kind'] == 'baryon':
        x, p = nuc.Nucleon('proton').sample_state(seed)
        quarks = [dict(flavor=f, anti=False, color=i, cluster=1) for i, f in enumerate(beam['quarks'])]
        return x, p, quarks
    rng = np.random.default_rng(seed)
    r = rng.normal(0, MESON_SIZE_FM / math.sqrt(2), 3)
    k = rng.normal(0, nuc.HBAR_C / (math.sqrt(2) * MESON_SIZE_FM), 3)
    colour = int(rng.integers(3))
    quarks = [dict(flavor=beam['quarks'][0], anti=False, color=colour, cluster=1),
              dict(flavor=beam['antiquarks'][0], anti=True, color=colour, cluster=1)]
    return np.array([r / 2, -r / 2]), np.array([k, -k]), quarks


def _projectile_name(col):
    """Le nom du hadron lancé, marqué comme tel (deux protons d'une collision p + p ne se confondent plus)."""
    return NAMES.get(col.beam['label'], col.beam['label']) + ' (projectile)'


def _cluster(cid, kind, ids, x, name, mass, p_vec, energy):
    return dict(id=cid, kind=kind, ids=list(ids), junction=x[list(ids)].mean(axis=0), name=name, mass=mass, excitation=0.0,
                string=0.0, momentum=float(np.linalg.norm(p_vec)), p_vec=np.asarray(p_vec, float), energy=energy, invariant=mass)


def cm_beta(col):
    """La vitesse du centre de masse dans le laboratoire : β = p / (E + M), le long du faisceau."""
    return np.array([0.0, 0.0, col.momentum / (col.e_beam + col.m)])


def _approach(col, target_model, seed, beam_x0, beam_quarks):
    """Les instants d'avant le contact, dans le centre de masse : les deux hadrons arrivent l'un vers l'autre en ligne droite
    à ±p*, chacun à sa vitesse p*/E* ; le mouvement interne de la cible (sa dynamique, remontée dans le temps) est ralenti par
    sa dilatation du temps γ* ; celui du projectile, plus rapide encore, est figé."""
    e_beam, e_target = math.hypot(col.p_star, col.m_beam), math.hypot(col.p_star, col.m)
    v_beam = np.array([0.0, 0.0, col.p_star / e_beam])
    v_target = np.array([0.0, 0.0, -col.p_star / e_target])
    gamma_target = e_target / col.m
    pre = target_model.history(seed, PRE_ROLL_FM, 24)
    times = np.array([s['time'] for s in pre])
    n_beam = len(beam_quarks)
    out = []
    for s in pre:
        t = s['time']
        inner = pre[int(np.argmin(np.abs(times - t / gamma_target)))]          # l'horloge de la cible, ralentie
        xt = inner['x'] + v_target * t
        xp = beam_x0 + v_beam * t
        x = np.vstack([xt, xp])
        v = np.vstack([inner['v'] + v_target, np.tile(v_beam, (n_beam, 1))])
        quarks = [dict(q, cluster=0) for q in inner['quarks']] + [dict(q, cluster=1) for q in beam_quarks]
        target = dict(inner['clusters'][0], id=0, p_vec=v_target * e_target, energy=e_target, invariant=col.m, mass=col.m,
                      momentum=col.p_star, junction=inner['clusters'][0]['junction'] + v_target * t)
        projectile = _cluster(1, col.beam['kind'], range(3, 3 + n_beam), x, _projectile_name(col), col.m_beam, v_beam * e_beam,
                              e_beam)
        out.append(dict(s, x=x, v=v, quarks=quarks, clusters=[target, projectile]))
    return out


def build(col, seed=1, force=None):
    """Tire un événement de `col` (HadronCollision) et construit ce que l'écran montre."""
    event = col.sample_event(seed, force)
    event['forced'] = force is not None and event['index'] == force
    model = nuc.Nucleon(col.target)
    rng = np.random.default_rng(seed + 3)
    beam_x, beam_p, beam_quarks = _projectile(col.beam, seed + 1)
    key = event['channel']
    # le point de contact : de côté pour une diffraction (périphérique), presque de face pour une collision qui casse
    impact = 0.9 if key == 'elastic_h' else 0.3
    centre = np.array([impact, 0.0, 0.0])
    beam_x0 = beam_x + centre
    steps = _beam_steps(col, event)
    if key == 'elastic_h':
        samples, breaks, info = _elastic(col, event, model, seed, beam_x0, beam_p, beam_quarks, steps)
    elif key == 'formation':
        samples, breaks, info = _formation(col, event, model, seed, beam_x0, beam_quarks, rng, steps)
    else:
        samples, breaks, info = _strings(col, event, model, seed, beam_x0, beam_p, beam_quarks, steps)
    model.flavors = ('u', 'u', 'd') if col.target == 'proton' else ('u', 'd', 'd')
    pre = _approach(col, model, seed, beam_x0, beam_quarks)
    samples = pre + samples
    _bilan(col, event, samples, steps)
    model.samples, model.breaks = samples, breaks
    model.q = dict(momentum_mev=col.momentum, nu_mev=col.e_beam, q2=event['q2'], w_mev=col.sqrt_s)
    model.run = next(nuc.Nucleon._runs)
    model.max_energy_error = info.get('energy_error', 0.0)
    model.struck, model.direction = info.get('struck', 0), np.array([0.0, 0.0, 1.0])
    out_dir = event['beam_cm'] / np.linalg.norm(event['beam_cm']) if key == 'elastic_h' else np.array([0.0, 0.0, 1.0])
    tracks = dict(impact=impact, photon_from=centre, photon_to=centre, out_direction=out_dir, energy_in=col.e_beam,
                  energy_out=event.get('beam_energy_out', 0.0), wavelength=event['wavelength_fm'], pre_roll=PRE_ROLL_FM,
                  path=np.zeros((0, 3)), deflection=0.0, max_deflection=0.0)
    return dict(event=event, steps=steps, nucleon=model, tracks=tracks, info=info, lobes=_lobes(col, event, info, breaks, centre),
                strike=dict(struck=info.get('struck', 0), momentum=0.0, direction=(0.0, 0.0, 1.0), seed=seed))


def _lobes(col, event, info, breaks, centre):
    """La diffraction : dσ/dΩ* ∝ e^(Bt), t = −2p*²(1 − cos θ*), dans le centre de masse ; la formation : la loi de désintégration
    du Δ (1 + 3cos²θ*)."""
    out = []
    if event['channel'] == 'elastic_h':
        th = np.linspace(0, 180, 91)
        b = col.b_slope / 1e6                                                   # MeV⁻²
        density = np.exp(-2 * b * col.p_star ** 2 * (1 - np.cos(np.radians(th))))
        theta_star = math.degrees(math.acos(max(-1.0, min(1.0, event['cos_star']))))
        out.append(ce.lobe("dσ/dΩ* ∝ e^(Bt) : la diffraction, B = %s GeV⁻², p* = %s MeV/c — l'angle de l'événement y est tiré" % (
            _fmt(col.b_slope), _fmt(col.p_star)), th, density, centre, [0.0, 0.0, 1.0], theta_star,
            event['beam_cm'] / np.linalg.norm(event['beam_cm']), True))
    law = info.get('decay_law')
    if law is not None and law['key'] != 'iso' and breaks:
        th = np.linspace(0, 180, 61)
        d = np.asarray(info['decay_direction'], float)
        axis = np.asarray(info['decay_axis'], float)
        out.append(ce.lobe("désintégration du Δ : le pion sort selon %s (repos du Δ)" % law['formula'], th,
                           [law['fn'](v) for v in np.cos(np.radians(th))], breaks[0]['position'], axis,
                           math.degrees(math.acos(max(-1.0, min(1.0, float(d @ axis))))), d, False))
    return out


# ================================================================== les étapes communes
def _beam_steps(col, event):
    beam = col.beam
    gamma = col.e_beam / col.m_beam
    available = col.sqrt_s - col.m - col.m_beam
    steps = [dict(time=-PRE_ROLL_FM, title="Le faisceau",
                  formula="E = √(p² + m²)   ·   γ = E/m   ·   s = m² + M² + 2ME   ·   énergie disponible √s − m − M",
                  values="%s : p = %s MeV/c  ·  E = %s MeV  ·  γ = %s  ·  √s = %s MeV  ·  disponible = %s MeV" % (
                      beam['label'], _fmt(col.momentum), _fmt(col.e_beam), _fmt(gamma), _fmt(col.sqrt_s), _fmt(available)),
                  result="Le %s est lui aussi un sac de quarks. La scène est vue du centre de masse (β = %s c) : les deux hadrons "
                         "arrivent l'un vers l'autre à p* = %s MeV/c, chacun le mouvement interne ralenti par sa dilatation du temps." % (
                             beam['name'], _fmt(float(cm_beta(col)[2])), _fmt(col.p_star)))]
    table = '  ·  '.join('%s %.0f %%' % (c['name'], 100 * c['probability']) for c in col.channels if c['probability'] > 5e-4)
    steps.append(dict(time=0.0, title="Les cas possibles à cette impulsion",
                      formula="σ_tot = X s^0,0808 + Y s^−0,4525 (Donnachie–Landshoff)   ·   σ_él = σ_tot²/16πB   ·   σ_Δ : Breit–Wigner",
                      values="σ_tot = %s mb  ·  σ_él = %s mb  ·  σ_Δ = %s mb  ·  σ_inél = %s mb  ·  B = %s GeV⁻²  ·  %s" % (
                          _fmt(col.sigma_total), _fmt(col.sigma_el), _fmt(col.sigma_delta), _fmt(col.sigma_inel), _fmt(col.b_slope), table),
                      result="Cet événement : %s. (Probabilités intégrées sur tous les angles.)" % col.channels[event['index']]['name']))
    return steps


def _bilan(col, event, samples, steps):
    last = samples[-1]
    names, energy, p_sum = [], 0.0, np.zeros(3)
    for c in last['clusters']:
        mass = c.get('invariant', c['mass'])
        names.append('%s%s (%s MeV)' % (c['name'], '*' if mass - c['mass'] > 5 else '', _fmt(mass)))
        energy += c.get('energy', math.sqrt(c['mass'] ** 2 + c['momentum'] ** 2))
        p_sum += np.asarray(c.get('p_vec', np.zeros(3)))
    visible = [q for q in last['quarks'] if q.get('cluster', 0) >= 0]
    charge = sum(nuc.charge_of(q['flavor'], q['anti']) for q in visible)
    baryon = sum((-1 if q['anti'] else 1) for q in visible) / 3
    before = col.beam['charge'] + (1 if col.target == 'proton' else 0)
    steps.append(dict(time=last['time'], title="Bilan : tout est conservé",
                      formula="dans le centre de masse : Σ E = √s   ·   Σ p = 0   ·   charge et nombre baryonique",
                      values="état final : %s  ·  Σ E = %s MeV (√s = %s)  ·  |Σ p| = %s MeV/c (0)  ·  charge %+d → %+.0f  ·  B = %.0f" % (
                          ' + '.join(names), _fmt(energy, 5), _fmt(col.sqrt_s, 5), "%.1e" % float(np.linalg.norm(p_sum)),
                          before, charge, baryon),
                      result="Vu du centre de masse (vitesse β = %s c dans le laboratoire, où Σ E = E + M = %s MeV et Σ p = %s MeV/c). "
                             "Un astérisque marque un hadron encore excité à la fin du film." % (
                                 _fmt(float(cm_beta(col)[2])), _fmt(col.e_beam + col.m, 5), _fmt(col.momentum, 5))))


# ================================================================== diffraction
def _rest_frames(col, kind, beam_x, beam_p, beam_quarks, seed):
    """Le mouvement interne du projectile dans son repos (sans rupture)."""
    if kind == 'baryon':
        m = nuc.Nucleon('proton')
        m.flavors = tuple(q['flavor'] for q in beam_quarks)
        return m.calculate(struck=0, momentum_mev=0.0, direction=(1, 0, 0), seed=seed)
    m = nuc.Nucleon(col.target)
    return m.evolve(beam_quarks, [dict(kind='meson', ids=[0, 1], reference=None)], beam_x, beam_p, seed, breaks=False)


def _elastic(col, event, model, seed, beam_x0, beam_p, beam_quarks, steps):
    target_rest = model.calculate(struck=0, momentum_mev=0.0, direction=(1, 0, 0), seed=seed)
    beam_rest = _rest_frames(col, col.beam['kind'], beam_x0 - beam_x0.mean(axis=0), beam_p, beam_quarks, seed + 1)
    centre = beam_x0.mean(axis=0)
    pt, pb = event['target_cm'], event['beam_cm']                     # dans le centre de masse
    et, eb = event['target_energy_cm'], event['beam_energy_cm']
    vt, vb = pt / et, pb / eb
    gt, gb = et / col.m, eb / col.m_beam
    times = [s['time'] for s in target_rest]
    beam_times = np.array([s['time'] for s in beam_rest])
    n_beam = len(beam_quarks)
    out = []
    for s in target_rest:
        t = s['time']
        # le mouvement interne de chacun, ralenti par sa dilatation du temps, autour de son centre qui file à v = p/E
        st = target_rest[min(len(target_rest) - 1, int(np.searchsorted(times, t / gt)))]
        sb = beam_rest[min(len(beam_rest) - 1, int(np.searchsorted(beam_times, t / gb)))]
        xt = st['x'] + vt * t
        xb = sb['x'] - sb['x'].mean(axis=0) + centre + vb * t
        x = np.vstack([xt, xb])
        v = np.vstack([np.tile(vt, (3, 1)), np.tile(vb, (n_beam, 1))])
        quarks = [dict(q, cluster=0) for q in st['quarks']] + [dict(q, cluster=1) for q in beam_quarks]
        clusters = [_cluster(0, 'baryon', range(3), x, col.target, col.m, pt, et),
                    _cluster(1, col.beam['kind'], range(3, 3 + n_beam), x, _projectile_name(col), col.m_beam, pb, eb)]
        clusters[0]['junction'] = st['clusters'][0]['junction'] + vt * t
        out.append(dict(s, x=x, v=v, quarks=quarks, clusters=clusters))
    steps.append(dict(time=0.0, title="Diffraction : ils se frôlent",
                      formula="dσ/dt ∝ e^(Bt)   ·   cos θ* = 1 − |t|/2p*²   ·   puis retour au laboratoire (boost de vitesse p/(E + M))",
                      values="−t = %s GeV²  ·  p* = %s MeV/c  ·  θ* = %s°  ·  θ_lab du %s = %s°" % (
                          _fmt(event['minus_t'] / 1e6), _fmt(col.p_star), _fmt(math.degrees(math.acos(max(-1, min(1, event['cos_star']))))),
                          col.beam['label'], _fmt(event['theta_lab'])),
                      result="Aucune couleur n'est échangée : seulement de l'impulsion (l'échange d'un « poméron », un état de gluons "
                             "sans couleur nette). Les deux hadrons repartent intacts."))
    steps.append(dict(time=0.0, title="Le recul",
                      formula="centre de masse : p₁ = −p₂, |p₁| = p*   ·   laboratoire : p₁ + p₂ = p_faisceau, E₁ + E₂ = E + M",
                      values="centre de masse : %s MeV/c chacun, dos à dos  ·  laboratoire : %s %s MeV/c, %s %s MeV/c" % (
                          _fmt(float(np.linalg.norm(pb))), col.beam['label'], _fmt(float(np.linalg.norm(event['beam_out']))),
                          col.target, _fmt(float(np.linalg.norm(event['target_out'])))),
                      result="Chacun garde ses quarks et son état fondamental. Ils repartent dos à dos, chacun avec p* : la "
                             "diffraction ne change que la direction."))
    return out, [], dict(energy_error=model.max_energy_error, struck=0)


# ================================================================== formation du Δ par un pion
def _formation(col, event, model, seed, beam_x0, beam_quarks, rng, steps):
    target_flavors = list(model.flavors)
    quark, anti = beam_quarks[0], beam_quarks[1]
    k = target_flavors.index(anti['flavor'])                    # le quark du nucléon qui s'annihile avec l'antiquark du pion
    survivors = [i for i in range(3) if i != k]
    # le quark du pion prend la place du quark annihilé : les deux autres gardent leur indice (et leur position)
    delta_flavors = tuple(quark['flavor'] if i == k else target_flavors[i] for i in range(3))
    model.flavors = delta_flavors
    fake = SimpleNamespace(m=col.m, kind='had', target=col.target)
    ev = dict(event, nu=col.sqrt_s - col.m, q_mag=0.0)                 # centre de masse : le Δ naît au repos, de masse √s
    q_hat = np.array([0.0, 0.0, 1.0])
    samples, breaks, info = ce._delta_movie(model, fake, ev, q_hat, seed, steps)
    # les indices du modèle (Δ : 0, 1, 2 ; paire créée : 3, 4) → ceux de l'écran (cible 0..2, pion 3 = quark, 4 = antiquark,
    # paire créée 5, 6) ; le quark k et l'antiquark du pion, annihilés, restent dans la liste, hors de tout amas
    mapping = {survivors[0]: survivors[0], survivors[1]: survivors[1], k: 3, 3: 5, 4: 6}
    point = beam_x0.mean(axis=0) * .5
    annihilated = [k, 4]
    out = []
    for s in samples:
        n_model = len(s['quarks'])
        n = 5 if n_model == 3 else 7
        x = np.zeros((n, 3))
        v = np.zeros((n, 3))
        quarks = [None] * n
        for j in range(n_model):
            x[mapping[j]] = s['x'][j]
            v[mapping[j]] = s['v'][j]
            quarks[mapping[j]] = dict(s['quarks'][j])
        for i in annihilated:
            x[i] = point
            source = dict(flavor=target_flavors[k], anti=False, color=0) if i == k else dict(anti)
            quarks[i] = dict(source, cluster=-1)
        clusters = [dict(c, ids=[mapping[j] for j in c['ids']]) for c in s['clusters']]
        out.append(dict(s, x=x, v=v, quarks=quarks, clusters=clusters))
    delta_name = {2: 'Δ⁺⁺', 1: 'Δ⁺', 0: 'Δ⁰', -1: 'Δ⁻'}[event['delta_charge']]
    steps.insert(2, dict(time=0.0, title="Annihilation : un %s se forme" % delta_name,
                         formula="σ = C_iso (2J+1)/((2s_π+1)(2s_N+1)) (π/k²) Γ_πN Γ / ((W − M_Δ)² + Γ²/4)   ·   W = √s",
                         values="W = %s MeV  ·  k = %s MeV/c  ·  Γ(W) = %s MeV  ·  σ_Δ = %s mb  ·  %s%s̄ → gluon" % (
                             _fmt(col.sqrt_s), _fmt(hd.cm_momentum(col.sqrt_s, sc.M_PI, col.m)), _fmt(event['delta_width']),
                             _fmt(col.sigma_delta), anti['flavor'], anti['flavor']),
                         result="L'antiquark %s̄ du pion et un quark %s du %s s'annihilent ; il reste %s : un %s de spin 3/2, de masse "
                                "W = √s, qui emporte toute l'impulsion du pion." % (
                                    anti['flavor'], anti['flavor'], col.target, ''.join(delta_flavors), delta_name)))
    info.update(annihilated=annihilated, annihilation_point=point, struck=k)
    return out, breaks, info


# ================================================================== échange de couleur : deux cordes
def _strings(col, event, model, seed, beam_x0, beam_p, beam_quarks, steps):
    xt, pt = model.sample_state(seed)
    target_quarks = [dict(flavor=f, anti=False, color=i, cluster=0) for i, f in enumerate(model.flavors)]
    n_beam = len(beam_quarks)
    # le projectile : ses impulsions internes, plus sa part de l'impulsion du faisceau
    share = col.momentum / n_beam
    pb = beam_p + np.array([0.0, 0.0, share])
    quarks = target_quarks + [dict(q, cluster=1) for q in beam_quarks]
    x = np.vstack([xt, beam_x0])
    p = np.vstack([pt, pb])
    # l'échange de couleur : le quark de couleur c du projectile et celui du nucléon changent de partenaires
    beam_ids = list(range(3, 3 + n_beam))
    if col.beam['kind'] == 'meson':
        j = 3
        c = beam_quarks[0]['color']
    else:
        c = event['colour']
        j = next(i for i in beam_ids if quarks[i]['color'] == c)
    k = next(i for i in range(3) if quarks[i]['color'] == c)
    a_ids = [i for i in range(3) if i != k] + [j]
    b_ids = [i for i in beam_ids if i != j] + [k]
    clusters = [dict(kind='baryon', ids=a_ids, reference=None),
                dict(kind='baryon' if col.beam['kind'] == 'baryon' else 'meson', ids=b_ids, reference=None)]
    if col.beam['kind'] == 'meson':
        clusters[1]['ids'] = [k, 4]                                   # le quark du nucléon et l'antiquark du pion
    samples = model.evolve(quarks, clusters, x, p, seed, breaks=True)
    samples = ce.to_lab_frame(samples, np.array([0.0, 0.0, 1.0]), col.momentum, col.sqrt_s, model.breaks, frame_beta=cm_beta(col))
    colours = ('rouge', 'vert', 'bleu')
    steps.append(dict(time=0.0, title="Échange de couleur : deux cordes se tendent",
                      formula="un gluon échange la couleur entre un quark de chaque hadron   ·   σ_inél = σ_tot − σ_él   ·   V = σ L",
                      values="couleur échangée : %s  ·  quark %s du %s ↔ quark %s du %s  ·  √s = %s MeV" % (
                          colours[c], quarks[j]['flavor'], col.beam['label'], quarks[k]['flavor'], col.target, _fmt(col.sqrt_s)),
                      result="Chaque quark garde son impulsion, mais il est désormais lié à l'autre hadron : les morceaux qui "
                             "s'éloignent restent reliés par deux cordes, qui s'étirent (σ ≈ 0,9 GeV/fm) puis se rompent."))
    for b in model.breaks:
        steps.append(ce.break_step(b))
    return samples, list(model.breaks), dict(energy_error=model.max_energy_error, struck=k, swapped=(j, k))
