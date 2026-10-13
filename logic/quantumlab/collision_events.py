"""Un événement lepton + nucléon, de bout en bout : la cinématique du lepton, le boson échangé (photon virtuel, ou W pour un
neutrino), et ce que devient le nucléon. Un W change la saveur d'un quark (d → u pour ν, u → d pour ν̄) : quasi-élastique
(n → p), Δ de charge différente, ou quark frappé qui change de saveur.

* élastique : le nucléon est intact ; il recule avec l'impulsion q ;
* Δ(1232) : le nucléon est excité, vit ħ/Γ(W) puis se désintègre en N π, avec les impulsions de la cinématique à deux corps ;
* résonances supérieures et inélastique profond : le photon frappe un quark ; la corde de couleur de `nucleon.py` s'étire, se
  rompt et crée des paires. On donne au quark l'impulsion qui redonne exactement la masse invariante W de l'électron.

Chaque étape est accompagnée de sa loi et de ses nombres (`steps`), pour qu'aucune interaction n'apparaisse « par magie ».
"""
import math

import numpy as np

from quantumlab import hadron_data as data
from quantumlab import hadron_lab as lab
from quantumlab import nucleon as nuc
from quantumlab import scattering as sc
from quantumlab import standard_model as sm

PRE_ROLL_FM = 6.0                   # temps d'approche montré avant le choc (fm/c)
HBAR_C_FM = 197.3269804             # MeV·fm
PROTON_RADIUS_FM = 0.84
ELECTRON_MEV = 0.51099895
DISPLAY = {'p': 'proton', 'n': 'neutron', 'π+': 'π⁺', 'π-': 'π⁻', 'π0': 'π⁰'}   # les noms des tables → ceux de l'écran


def _fmt(v, digits=3):
    """Un nombre à la française : virgule décimale, notation ×10ⁿ hors de [0,01 ; 10⁶[."""
    if v == 0:
        return '0'
    a = abs(v)
    if a >= 1e6 or a < 1e-2:
        exponent = int(math.floor(math.log10(a)))
        sup = str(exponent).translate(str.maketrans('-0123456789', '⁻⁰¹²³⁴⁵⁶⁷⁸⁹'))
        text = ('%.' + str(digits - 1) + 'f') % (v / 10 ** exponent) + '·10' + sup
    elif a >= 100:
        text = '%.0f' % v if digits <= 4 else ('%.' + str(max(0, digits - 4)) + 'f') % v
    else:
        text = ('%.' + str(digits) + 'g') % v
    return text.replace('.', ',')


def _cluster(cid, kind, ids, junction, name, mass, excitation, string, momentum):
    return dict(id=cid, kind=kind, ids=list(ids), junction=np.asarray(junction, float), name=name, mass=mass,
                excitation=excitation, string=string, momentum=momentum)


def _quark(flavor, anti, color, cluster):
    return dict(flavor=flavor, anti=anti, color=color, cluster=cluster)


def _frame(t, x, v, quarks, clusters, base):
    return dict(time=t, x=np.asarray(x, float), v=np.asarray(v, float), quarks=quarks, clusters=clusters,
                kinetic=base['kinetic'], string=base['string'], coulomb=base['coulomb'], total=base['total'],
                break_probability=0.0)


def build(collision, seed=1, force=None):
    """Tire un événement dans le spectre de `collision` (dans le canal `force` si donné) et construit ce que l'écran montre."""
    event = collision.sample_event(seed, force)
    event['forced'] = force is not None and event['index'] == force
    target = collision.target
    model = nuc.Nucleon(target)
    original = model.flavors
    m = collision.m
    q_hat = event['q_vec'] / event['q_mag']
    # ---- les nombres du photon virtuel
    q_mag, nu, q2 = event['q_mag'], event['nu'], event['q2']
    w = event['w']
    steps = _beam_and_photon_steps(collision, event)
    key = event['channel']
    if event.get('resonance'):
        if collision.kind == 'cc':
            model.flavors = _converted(original, event_flavour(collision), 1)
        samples, breaks, info = _delta_movie(model, collision, event, q_hat, seed, steps)
    elif key in ('dis', 'dual', 'res'):
        samples, breaks, info = _strike_movie(model, event, q_hat, seed, steps)
    elif key == 'qe':
        model.flavors = _converted(original, event_flavour(collision), 1)
        samples, breaks, info = _elastic_movie(model, collision, event, q_hat, seed, steps)
        info['converted'] = original.index(event_flavour(collision)[0])
    else:
        samples, breaks, info = _elastic_movie(model, collision, event, q_hat, seed, steps)
    model.flavors = original                           # l'histoire d'avant le choc : le nucléon cible
    pre = model.history(info.get('seed', seed), PRE_ROLL_FM, 24)
    samples = pre + samples
    _outcome_steps(collision, event, samples, breaks, steps)
    model.samples, model.breaks = samples, breaks
    model.q = dict(momentum_mev=q_mag, nu_mev=nu, q2=q2, w_mev=w)
    model.run = next(nuc.Nucleon._runs)
    model.max_energy_error = info.get('energy_error', 0.0)
    model.struck, model.direction = info.get('struck', 0), q_hat
    tracks = _tracks(collision, event, samples, info)
    return dict(event=event, steps=steps, nucleon=model, tracks=tracks, info=info, lobes=_lobes(collision, event, tracks, info, breaks),
                strike=dict(
        struck=info.get('struck', 0), momentum=info.get('kick', 0.0) if key in ('dis', 'dual') else 0.0,
        direction=tuple(q_hat), seed=seed))


def event_flavour(collision):
    """(saveur qui change, nouvelle saveur) sous l'effet du W : d → u pour ν (W⁺), u → d pour ν̄ (W⁻)."""
    return ('d', 'u') if collision.projectile == 'nu' else ('u', 'd')


def _converted(flavors, change, count):
    """Les saveurs après que le W a changé `count` quark(s) `change[0]` en `change[1]` (le premier trouvé)."""
    out, done = list(flavors), 0
    for i, f in enumerate(out):
        if f == change[0] and done < count:
            out[i] = change[1]
            done += 1
    return tuple(out)


# ================================================================== le faisceau et le boson échangé
def _beam_and_photon_steps(collision, event):
    e = collision.energy
    beam = collision.beam
    ep, theta = event['e_out'], collision.theta
    q2, nu, q = event['q2'], event['nu'], event['q_mag']
    lam = event['wavelength_fm']
    if collision.kind == 'cc':
        first = dict(time=-PRE_ROLL_FM, title="Le faisceau",
                     formula="m_ν ≈ 0 (< 0,8 eV)  ·  charge 0  ·  seule l'interaction faible le touche",
                     values="E = %s MeV  ·  σ totale sur ce nucléon = %s cm²  ·  P(interagir en le traversant) = σ/πr_p² = %s" % (
                         _fmt(e), _fmt(collision.sigma_total_cm2), _fmt(collision.interaction_probability)),
                     result="Un %s n'a pas de charge : ni le puits de Coulomb ni la force forte ne le dévient. Il file droit ; "
                            "l'événement montré est celui, rarissime, où il interagit." % beam['name'])
        exchange = dict(time=0.0, title="Échange d'un boson %s virtuel" % beam['boson'],
                        formula="q = k − k′   ·   Q² = 2(EE′ − kk′ cos θ) − m_μ²   ·   ν = E − E′   ·   propagateur (M_W²/(M_W² + Q²))²   ·   portée ħc/M_W",
                        values="E′ = %s MeV  ·  θ = %.1f°  ·  Q² = %s GeV²  ·  ν = %s MeV  ·  |q| = %s MeV/c  ·  portée = %s fm" % (
                            _fmt(ep), collision.angle, _fmt(q2 / 1e6), _fmt(nu), _fmt(q), _fmt(HBAR_C_FM / sc.M_W)),
                        result="Le %s emporte une charge %s : le neutrino devient un %s, et un quark %s du nucléon devient %s. "
                               "Sa portée minuscule impose un contact : le neutrino doit passer sur le quark." % (
                                   beam['boson'], '+1' if collision.projectile == 'nu' else '−1', beam['out_label'],
                                   *event_flavour(collision)))
    else:
        mass = beam['mass']
        gamma = e / mass
        first = dict(time=-PRE_ROLL_FM, title="Le faisceau", formula="γ = E / mc²  ·  v = c √(1 − 1/γ²)",
                     values="%s : m = %s MeV  ·  E = %s MeV  ·  γ = %s  ·  1 − v/c = %s" % (
                         beam['label'], _fmt(mass), _fmt(e), _fmt(gamma), _fmt(1 / (2 * gamma ** 2))),
                     result="Le %s est ultra-relativiste : il traverse 1 fm en 3,3·10⁻²⁴ s. Charge %s : le puits du nucléon "
                            "l'%s." % (beam['name'], '+1' if beam['charge'] > 0 else '−1',
                                       'attire' if beam['charge'] < 0 else 'repousse'))
        exchange = dict(time=0.0, title="Échange d'un photon virtuel",
                        formula="q = k − k′   ·   Q² = 2(EE′ − kk′ cos θ) − 2m²   ·   ν = E − E′   ·   λ = ħc / Q",
                        values="E′ = %s MeV  ·  θ = %.1f°  ·  Q² = %s GeV²  ·  ν = %s MeV  ·  |q| = %s MeV/c  ·  λ = %s fm" % (
                            _fmt(ep), collision.angle, _fmt(q2 / 1e6), _fmt(nu), _fmt(q), _fmt(lam)),
                        result="Le photon emporte exactement la quantité de mouvement perdue par le %s. La charge entre au carré : "
                               "un %s a la même probabilité qu'un %s." % (beam['name'], beam['label'],
                                                                      'e⁻' if beam['charge'] > 0 else 'e⁺'))
    steps = [first, exchange]
    ratio = lam / PROTON_RADIUS_FM
    if ratio > 1.5:
        seen = "λ dépasse la taille du proton : le photon ne voit que l'ensemble, une charge ponctuelle étalée."
    elif ratio > 0.5:
        seen = "λ est de l'ordre du proton : il en voit la distribution de charge globale (facteurs de forme)."
    elif ratio > 0.15:
        seen = "λ est plus petite que le proton : il distingue les quarks qui le composent."
    else:
        seen = "λ est bien plus petite que le proton : il voit des quarks isolés, et la mer de gluons et de paires."
    steps.append(dict(time=0.0, title="Ce que le boson peut voir", formula="résolution ≈ λ = ħc / Q  comparée à r_p = 0,84 fm",
                      values="λ / r_p = %s" % _fmt(ratio), result=seen))
    table = '  ·  '.join('%s %.0f %%' % (c['name'], 100 * c['probability']) for c in collision.channels if c['probability'] > 5e-4)
    steps.append(dict(time=0.0, title="Les cas possibles à cette énergie et cet angle",
                      formula="P(cas) = σ_cas / σ_total, σ = ∫ dσ/dΩdW dW",
                      values=table, result="Cet événement : %s." % collision.channels[event['index']]['name']))
    return steps


# ================================================================== diffusion élastique
def _rest_run(model, seed):
    samples = model.calculate(struck=0, momentum_mev=0.0, direction=(1, 0, 0), seed=seed)
    return samples


def _elastic_movie(model, collision, event, q_hat, seed, steps):
    rest = _rest_run(model, seed)
    m, nu, q = collision.m, event['nu'], event['q_mag']
    final = sc.target_mass(event['final_nucleon']) if event['channel'] == 'qe' else m
    beta = q / (m + nu)
    out = []
    for s in rest:
        t = s['time']
        shift = beta * q_hat * t
        quarks = [dict(qk, cluster=0) for qk in s['quarks']]
        clusters = []
        for c in s['clusters']:
            clusters.append(dict(c, junction=c['junction'] + shift, excitation=0.0, momentum=q, p_vec=q * q_hat, energy=m + nu,
                                 invariant=final, mass=final))
        rec = dict(s, x=s['x'] + shift, v=s['v'] + beta * q_hat, quarks=quarks, clusters=clusters)
        out.append(rec)
    if event['channel'] == 'qe':
        change = event_flavour(collision)
        steps.append(dict(time=0.0, title="Quasi-élastique : le %s devient un %s" % (collision.target, event['final_nucleon']),
                          formula="dσ/dQ² = M²G_F²cos²θ_C/(8πE²) [A ∓ B(s−u)/M² + C(s−u)²/M⁴]   ·   F_A = g_A/(1 + Q²/M_A²)²",
                          values="Q² = %s GeV²  ·  g_A = %s  ·  M_A = %s GeV  ·  σ = %s nb/sr  ·  quark %s → %s" % (
                              _fmt(event['q2'] / 1e6), _fmt(sc.G_A), _fmt(sc.M_A / 1000), _fmt(collision.channels[0]['xs_nb']),
                              change[0], change[1]),
                          result="Le W change un quark %s en %s sans rien casser : le nucléon change d'identité (formule de "
                                 "Llewellyn Smith, facteurs de forme vectoriels et axial)." % change))
        steps.append(dict(time=0.0, title="Le recul",
                          formula="Σ E = M_cible + ν   ·   p = q   ·   v = |q| / (M + ν)",
                          values="ν = %s MeV  ·  |q| = %s MeV/c  ·  v = %s c" % (_fmt(nu), _fmt(q), _fmt(beta)),
                          result="Le %s part d'un bloc avec l'impulsion du W." % event['final_nucleon']))
        return out, [], dict(energy_error=model.max_energy_error, struck=0, beta=beta)
    ge, gm = event['ge'], event['gm']
    tau = event['q2'] / (4 * m * m)
    steps.append(dict(time=0.0, title="Le nucléon reste lui-même : diffusion élastique (W = M)",
                      formula="dσ/dΩ = (α²/Q⁴)(k′/k) [W₂(4EE′ − Q²) + 2W₁(Q² − 2m²)] / J   ·   W₁ = τG_M², W₂ = (G_E² + τG_M²)/(1+τ)   ·   τ = Q²/4M²",
                      values="τ = %s  ·  G_E = %s  ·  G_M = %s  ·  μ_p G_E/G_M = %s  ·  σ = %s nb/sr" % (
                          _fmt(tau), _fmt(ge), _fmt(gm), _fmt(sm_mu(collision) * ge / gm if gm else 0.0),
                          _fmt(collision.channels[0]['xs_nb'])),
                      result="Les facteurs de forme mesurent à quel point la charge et l'aimantation sont étalées : la section "
                             "efficace s'effondre quand Q² croît, parce que le proton intact ne peut pas absorber un coup sec."))
    steps.append(dict(time=0.0, title="Le recul",
                      formula="T = Q²/2M   ·   p = q   ·   v = |q| / (M + ν)",
                      values="T = %s MeV  ·  |q| = %s MeV/c  ·  v = %s c" % (_fmt(nu), _fmt(q), _fmt(beta)),
                      result="Tout le nucléon part d'un bloc : les trois quarks gardent leur état fondamental."))
    return out, [], dict(energy_error=model.max_energy_error, struck=0, beta=beta)


def sm_mu(collision):
    return sc.MU_P if collision.target == 'proton' else abs(sc.MU_N)


# ================================================================== la résonance Δ
def decay_law(kind):
    """La loi angulaire de Δ → N π dans le repos du Δ, autour de l'axe du projectile (θ* entre le pion et cet axe).

    * formé par un pion (sans spin, moment orbital L_z = 0 le long du faisceau) : le Δ a m = ±½, et |Y₁ ⊗ ½|² donne
      1 + 3 cos²θ* — c'est ce qu'on mesure en π⁺p au pic du Δ ;
    * formé par un photon virtuel par la transition magnétique M1 (le photon transverse apporte m = ±1) : le Δ a surtout
      m = ±3/2 et ±1/2 dans le rapport 3 : 1, d'où 5 − 3 cos²θ* (on néglige la petite part E2, ≈ −2,5 %, et la part
      longitudinale) ;
    * formé par un W (neutrino) : la loi dépend des facteurs de forme axiaux ; non modélisée — isotrope, écrit comme tel."""
    if kind == 'had':
        return dict(key='pion', formula='1 + 3cos²θ*', fn=lambda c: 1 + 3 * c * c, top=4.0,
                    why="Formé par un pion (sans spin), le Δ a m = ±½ le long du faisceau : le pion sort selon 1 + 3cos²θ*, "
                        "surtout vers l'avant et l'arrière.")
    if kind == 'em':
        return dict(key='m1', formula='5 − 3cos²θ*', fn=lambda c: 5 - 3 * c * c, top=5.0,
                    why="Formé par la transition magnétique M1 d'un photon transverse, le Δ a surtout m = ±3/2 : le pion sort selon "
                        "5 − 3cos²θ*, plutôt sur le côté.")
    return dict(key='iso', formula='isotrope (non modélisée)', fn=lambda c: 1.0, top=1.0,
                why="Pour un W, la loi angulaire dépend des facteurs de forme axiaux : non modélisée ici, la direction est isotrope.")


def decay_direction(rng, axis, law):
    """Une direction unitaire tirée selon `law` (fonction de cos θ autour de `axis`) par rejet, azimut uniforme."""
    axis = np.asarray(axis, float) / np.linalg.norm(axis)
    while True:
        c = float(rng.uniform(-1, 1))
        if rng.uniform(0, law['top']) <= law['fn'](c):
            break
    side = np.cross(axis, [1.0, 0.0, 0.0] if abs(axis[0]) < .9 else [0.0, 1.0, 0.0])
    side /= np.linalg.norm(side)
    other = np.cross(axis, side)
    phi = float(rng.uniform(0, 2 * math.pi))
    s = math.sqrt(max(0.0, 1 - c * c))
    return c * axis + s * (math.cos(phi) * side + math.sin(phi) * other)


def lobe(label, thetas, density, origin, axis, chosen_deg, chosen_dir, log_scale):
    """Un lobe angulaire pour l'écran : la densité (relative, max 1) à chaque θ autour de `axis`, partie de `origin`."""
    density = np.asarray(density, float)
    top = float(density.max()) if len(density) else 0.0
    rel = density / top if top > 0 else density
    positive = rel[rel > 0]
    span = float(min(6.0, max(1.0, -math.log10(positive.min())))) if log_scale and len(positive) else 0.0
    return dict(label=label, angles=[float(t) for t in thetas], density=[float(v) for v in rel],
                origin=[float(v) for v in origin], axis=[float(v) for v in axis], log=bool(log_scale), span=span,
                chosen=float(chosen_deg), chosen_dir=[float(v) for v in chosen_dir])


def _lobes(collision, event, tracks, info, breaks):
    """Les lois angulaires montrées en 3D : d'où vient l'angle de l'événement, et comment se désintègre un Δ."""
    out = []
    try:
        thetas, values = sc.angular_table(collision.energy, collision.target, collision.projectile)
        to_nb = sc.MEV2_TO_NB if collision.kind == 'em' else sc.MEV2_TO_CM2 * 1e33
        out.append(lobe("dσ/dΩ de %s à %s MeV, tous les cas sommés (max %s nb/sr) : l'angle de l'événement y est tiré" % (
            collision.beam['label'], _fmt(collision.energy), _fmt(float(values.max()) * to_nb)), thetas, values,
            tracks['photon_from'], [0.0, 0.0, 1.0], collision.angle, tracks['out_direction'], True))
    except ValueError:
        pass
    law = info.get('decay_law')
    if law is not None and law['key'] != 'iso' and breaks:
        th = np.linspace(0, 180, 61)
        c = np.cos(np.radians(th))
        d = np.asarray(info['decay_direction'], float)
        axis = np.asarray(info['decay_axis'], float)
        out.append(lobe("désintégration du Δ : le pion sort selon %s (repos du Δ)" % law['formula'], th, [law['fn'](v) for v in c],
                        breaks[0]['position'], axis, math.degrees(math.acos(max(-1.0, min(1.0, float(d @ axis))))), d, False))
    return out


def _delta_movie(model, collision, event, q_hat, seed, steps):
    rng = np.random.default_rng(seed + 7)
    rest = _rest_run(model, seed)
    m, nu, q = collision.m, event['nu'], event['q_mag']
    w = event['delta_mass']
    e_delta = m + nu
    beta_vec = q / e_delta * q_hat
    gamma = e_delta / w
    t_dec = event['decay_time_fm'] * gamma                      # dilatation du temps : la durée de vie propre est ħc/Γ
    name = {2: 'Δ⁺⁺', 1: 'Δ⁺', 0: 'Δ⁰', -1: 'Δ⁻'}[event.get('delta_charge', 1 if collision.target == 'proton' else 0)]
    # désintégration : p* dans le repos du Δ, direction tirée selon sa loi angulaire (`decay_law`), puis boost vers le laboratoire
    n_name, pi_name = event['decay_nucleon'], event['decay_pion']
    m_n, m_pi = data.HADRONS[n_name]['mass'], data.HADRONS[pi_name]['mass']
    p_star = lab.two_body_momentum(w, m_n, m_pi)
    law = decay_law(collision.kind)
    d = decay_direction(rng, q_hat, law)
    def boost(p_vec, mass):
        e_lab, p_lab = _boost(math.sqrt(mass ** 2 + p_vec @ p_vec), p_vec, beta_vec)     # au repos (q = 0) : sans boost
        return p_lab, e_lab
    p_n_lab, e_n_lab = boost(-p_star * d, m_n)
    p_pi_lab, e_pi_lab = boost(p_star * d, m_pi)
    v_n, v_pi = p_n_lab / e_n_lab, p_pi_lab / e_pi_lab
    # quel quark part avec le pion
    pion_q, pion_a = data.HADRONS[pi_name]['content'][0], data.HADRONS[pi_name]['content'][1].lower()
    flavors = [qk['flavor'] for qk in rest[0]['quarks']]
    if pi_name == 'π0':
        options = [i for i, f in enumerate(flavors)]
        removed = int(rng.choice(options))
        pion_q = pion_a = flavors[removed]
    else:
        options = [i for i, f in enumerate(flavors) if f == pion_q]
        removed = int(rng.choice(options))
    new_flavor = pion_a                                          # la paire créée est (pion_a, anti-pion_a)
    boson = 'photon' if collision.kind == 'em' else 'W'
    if collision.kind != 'had':                                   # (la formation par un pion a ses propres étapes)
        steps.append(dict(time=0.0, title="Le %s excite le nucléon : résonance Δ(1232)" % boson,
                          formula="σ_T = (π/k²) Γ_γ(Q²) Γ(W) / ((W − M_Δ)² + Γ(W)²/4)   ·   Γ(W) = Γ₀ (p*/p*₀)³ (M_Δ/W)",
                          values="W = %s MeV  ·  Γ(W) = %s MeV  ·  Γ_γ(0) = %s MeV  ·  Γ_γ(Q²)/Γ_γ(0) = %s" % (
                              _fmt(w), _fmt(event['delta_width']), _fmt(event['gamma_photon']), _fmt(event['form_ratio'])),
                          result=("Un quark change de spin (transition magnétique M1) : le nucléon devient un Δ, de même charge, de spin 3/2."
                                  if collision.kind == 'em' else
                                  "Le W change aussi la saveur d'un quark : le nucléon devient un %s, de spin 3/2. (Pour un neutrino, la région "
                                  "des résonances est calculée par dualité : W tiré dans ce spectre.)" % name)))
    steps.append(dict(time=t_dec, title="Le Δ se désintègre",
                      formula="τ = ħc/Γ(W)   ·   p* = √[(W² − (M+mπ)²)(W² − (M−mπ)²)]/2W   ·   rapports : Clebsch–Gordan d'isospin",
                      values="τ_propre = %s fm/c  ·  t_lab = γτ = %s fm/c  ·  p* = %s MeV/c  ·  %s + %s  ·  loi angulaire %s  ·  θ* = %s°" % (
                          _fmt(event['proper_lifetime_fm']), _fmt(t_dec), _fmt(p_star), n_name, pi_name, law['formula'],
                          _fmt(math.degrees(math.acos(max(-1.0, min(1.0, float(d @ q_hat))))))),
                      result="Une paire %s%s̄ sort du champ de couleur : %s + %s, impulsions opposées dans le repos du Δ. %s" % (
                          new_flavor, new_flavor, n_name, pi_name, law['why'])))
    cm = np.zeros(3)
    out, breaks = [], []
    sep = rng.normal(size=3)
    sep = 0.2 * sep / np.linalg.norm(sep)
    for s in rest:
        t = s['time']
        if t < t_dec:
            shift = beta_vec * t
            quarks = [dict(qk, cluster=0) for qk in s['quarks']]
            clusters = [dict(c, junction=c['junction'] + shift, name=name, mass=w, excitation=w - m, momentum=q, p_vec=q * q_hat,
                             energy=e_delta, invariant=w) for c in s['clusters']]
            out.append(dict(s, x=s['x'] + shift, v=s['v'] + beta_vec, quarks=quarks, clusters=clusters))
            continue
        dt = t - t_dec
        r_dec = beta_vec * t_dec
        offset = s['x']                                     # les écarts internes, issus de l'intégration réelle
        r_n = r_dec + v_n * dt
        r_pi = r_dec + rest[min(len(rest) - 1, int(round(t_dec / (rest[1]['time'] - rest[0]['time']))))]['x'][removed] + v_pi * dt
        ids_n = [i for i in range(3) if i != removed] + [4]
        x = np.zeros((5, 3))
        v = np.zeros((5, 3))
        for i in range(3):
            if i != removed:
                x[i] = r_n + offset[i]
                v[i] = v_n + s['v'][i]
        x[4] = r_n + offset[removed]
        v[4] = v_n + s['v'][removed]
        x[removed] = r_pi + sep / 2
        v[removed] = v_pi
        x[3] = r_pi - sep / 2
        v[3] = v_pi
        quarks = [dict(qk, cluster=0 if i in ids_n else 1) for i, qk in enumerate(list(s['quarks']))]
        quarks.append(_quark(new_flavor, True, s['quarks'][removed]['color'], 1))
        quarks.append(_quark(new_flavor, False, s['quarks'][removed]['color'], 0))
        # l'ordre des indices : 0,1,2 originaux, 3 = antiquark créé, 4 = quark créé (dans le nucléon)
        quarks[3], quarks[4] = quarks[3], quarks[4]
        clusters = [
            dict(_cluster(0, 'baryon', ids_n, x[ids_n].mean(axis=0), DISPLAY.get(n_name, n_name), m_n, 0.0, 0.0,
                          float(np.linalg.norm(p_n_lab))), p_vec=p_n_lab, energy=e_n_lab, invariant=m_n),
            dict(_cluster(1, 'meson', [removed, 3], (x[removed] + x[3]) / 2, DISPLAY.get(pi_name, pi_name), m_pi, 0.0, 0.0,
                          float(np.linalg.norm(p_pi_lab))), p_vec=p_pi_lab, energy=e_pi_lab, invariant=m_pi),
        ]
        rec = dict(s, x=x, v=v, quarks=quarks, clusters=clusters, break_probability=1.0)
        out.append(rec)
    if t_dec <= rest[-1]['time']:
        r_dec = beta_vec * t_dec
        breaks.append(dict(time=t_dec, position=r_dec + rest[min(len(rest) - 1, int(round(t_dec / (rest[1]['time'] - rest[0]['time']))))]['x'][removed],
                           flavor=new_flavor, separation=0.2, released=nuc.SIGMA * 0.2,
                           hadrons=[DISPLAY.get(n_name, n_name), DISPLAY.get(pi_name, pi_name)], cluster=0))
    return out, breaks, dict(energy_error=model.max_energy_error, struck=0, t_decay=t_dec, nucleon=n_name, pion=pi_name,
                             decay_direction=d, decay_axis=np.asarray(q_hat, float), decay_law=law,
                             p_star=p_star, removed=removed, new_flavor=new_flavor)


# ================================================================== le photon frappe un quark
def _strike_movie(model, event, q_hat, seed, steps):
    flavor = event['struck_flavor']
    candidates = [i for i, f in enumerate(model.flavors) if f == flavor]
    rng = np.random.default_rng(seed + 11)
    struck = int(rng.choice(candidates))
    # Approximation d'impulsion : le photon n'est absorbé que par un quark dont le mouvement de Fermi le permet (c'est ce que
    # mesure x). On retient le premier tirage de Fermi où le quark frappé peut porter la masse invariante W.
    kick, used = None, seed
    for attempt in range(60):
        trial = seed + 7919 * attempt
        k = model.momentum_for_mass(event['w'], struck, q_hat, trial)
        if k < nuc.MAX_KICK_MEV * .999 and k < 4 * event['q_mag'] + 3000:
            kick, used = k, trial
            break
    if kick is None:
        kick = model.momentum_for_mass(event['w'], struck, q_hat, seed)
    info_seed = used
    new_flavor = event.get('new_flavor', flavor)
    if new_flavor != flavor:                                    # le W change la saveur du quark qu'il touche
        flavors = list(model.flavors)
        flavors[struck] = new_flavor
        model.flavors = tuple(flavors)
    samples = model.calculate(struck=struck, momentum_mev=kick, direction=q_hat, seed=used)
    w_model = model.q['w_mev']
    samples = to_lab_frame(samples, q_hat, event['q_mag'], event['w'], model.breaks)
    weights = event['weights']
    if new_flavor != flavor:
        steps.append(dict(time=0.0, title="Le W frappe un quark %s et le change en %s" % (flavor, new_flavor),
                          formula="x = Q² / 2Mν   ·   F₂ = 2x(d + s + ū)   ·   xF₃ = 2x(d + s − ū)   (ν ; ν̄ : u ↔ d)",
                          values="x = %s  ·  F₂ = %s  ·  xF₃ = %s  ·  part de valence = %.0f %%  →  quark %s → %s" % (
                              _fmt(event['x']), _fmt(event['f2']), _fmt(event['xf3']), 100 * min(1.0, event['valence_share']), flavor,
                              new_flavor),
                          result="Le W ne voit que les quarks dont il peut changer la charge : il en prend un, lui cède (ν, q) et "
                                 "change sa saveur — le nucléon gagne une charge."))
    else:
        steps.append(dict(time=0.0, title="Le photon frappe un quark",
                          formula="x = Q² / 2Mν   ·   F₂ = x Σ e_q² (q + q̄)   ·   P(saveur) ∝ e_q² · q_valence(x, Q²)",
                          values="x = %s  ·  F₂ = %s  ·  part de valence = %.0f %%  ·  poids u : %s, d : %s  →  quark %s" % (
                              _fmt(event['x']), _fmt(event['f2']), 100 * event['valence_share'], _fmt(weights.get('u', 0)),
                              _fmt(weights.get('d', 0)), flavor),
                          result="Le photon voit le quark comme une particule libre : il n'interagit qu'avec lui, et lui cède (ν, q)."))
    steps.append(dict(time=0.0, title="La masse invariante est fixée par le lepton",
                      formula="W² = M² + 2Mν − Q²   ·   le quark reçoit l'impulsion qui redonne W dans le modèle de cordes",
                      values="W = %s MeV  ·  q = %s MeV/c  ·  impulsion donnée au quark = %s MeV/c  ·  W (modèle) = %s MeV" % (
                          _fmt(event['w']), _fmt(event['q_mag']), _fmt(kick), _fmt(w_model)),
                      result="Le quark frappé s'éloigne ; la corde de couleur qui le retenait s'étire (σ ≈ 0,9 GeV/fm)."))
    for b in model.breaks:
        steps.append(break_step(b))
    return samples, list(model.breaks), dict(energy_error=model.max_energy_error, struck=struck, kick=kick, w_model=w_model,
                                             seed=info_seed)


def _boost(e, p, beta_vec):
    """Boost de Lorentz de (E, p) par la vitesse beta_vec (unités de c)."""
    b2 = float(beta_vec @ beta_vec)
    if b2 < 1e-18:
        return e, p.copy()
    g = 1 / math.sqrt(1 - b2)
    bp = float(beta_vec @ p)
    return g * (e + bp), p + ((g - 1) * bp / b2 + g * e) * beta_vec


def on_shell(clusters, w, lab_momentum):
    """Les 4-impulsions des hadrons, exactes : Σ E = √(W² + q²), Σ p = q.

    1. On passe au repère du centre de masse du modèle ; 2. on met chaque hadron sur sa couche de masse (masse mesurée plus
    l'excitation que lui donne le modèle, réduite si besoin pour tenir sous W) ; 3. on multiplie toutes les impulsions par le
    même facteur λ pour que Σ √(m² + λ²p*²) = W (le « réajustement » des générateurs d'événements) ; 4. on revient au laboratoire
    par le boost qui donne l'impulsion totale q. Renvoie [(masse, énergie, impulsion)]."""
    e_model = [math.sqrt(c['invariant'] ** 2 + float(c['p_vec'] @ c['p_vec'])) for c in clusters]
    e_tot = sum(e_model)
    p_tot = sum((c['p_vec'] for c in clusters), np.zeros(3))
    to_rest = -p_tot / e_tot
    star = [_boost(e, c['p_vec'], to_rest)[1] for e, c in zip(e_model, clusters)]
    ground = np.array([c['mass'] for c in clusters])
    excite = np.array([max(0.0, c['invariant'] - c['mass']) for c in clusters])
    if ground.sum() >= w:
        masses = ground * (w * .999 / ground.sum())          # ne devrait pas arriver : le modèle refuse ces ruptures
    elif ground.sum() + excite.sum() < w:
        masses = ground + excite
    else:
        masses = ground + excite * ((w - ground.sum()) * .999 / max(1e-9, excite.sum()))
    norms = [float(np.linalg.norm(p)) for p in star]

    def energy(lam):
        return sum(math.sqrt(m * m + (lam * n) ** 2) for m, n in zip(masses, norms))
    if len(clusters) == 1 or max(norms) < 1e-9:
        lam = 1.0
        masses = np.array([w]) if len(clusters) == 1 else masses
    else:
        lo, hi = 0.0, 1.0
        while energy(hi) < w:
            hi *= 2
        for _ in range(80):
            mid = (lo + hi) / 2
            lo, hi = (mid, hi) if energy(mid) < w else (lo, mid)
        lam = (lo + hi) / 2
    q_mag = float(np.linalg.norm(lab_momentum))
    beta_lab = lab_momentum / math.sqrt(w * w + q_mag * q_mag)
    out = []
    for m, p in zip(masses, star):
        p_star = lam * p
        e_lab, p_lab = _boost(math.sqrt(m * m + float(p_star @ p_star)), p_star, beta_lab)
        out.append((float(m), e_lab, p_lab))
    return out


def split(parent, children, rupture=None):
    """La rupture d'une corde, en cinématique exacte à deux corps : le hadron parent (masse M, 4-impulsion de laboratoire)
    devient deux hadrons de masses m₁, m₂.

    Dans le repos du parent, les deux morceaux partent dos à dos avec p* = √((M² − (m₁ + m₂)²)(M² − (m₁ − m₂)²)) / 2M :
    c'est l'énergie de la corde qui n'a pas servi à faire les masses, Q = M − m₁ − m₂, qui devient leur mouvement. La direction
    est celle de la corde (cordes de Lund) : les morceaux s'écartent le long de son axe, avec la seule impulsion transverse p_T
    de la paire créée par effet tunnel, dP/dp_T² ∝ exp(−π p_T²/σ), loi tronquée à p_T ≤ p* (on ne peut donner plus que ce que
    la rupture libère ; le tirage uniforme et l'azimut viennent de `nucleon._break`). Le premier morceau est du
    côté du départ de la jambe (−axe), le second du côté du quark qui tirait (+axe). Sans rupture décrite, la direction est
    celle des impulsions du modèle. Le boost du parent ramène au laboratoire : p₁ + p₂ = P et E₁ + E₂ = E exactement — les
    autres hadrons ne bougent pas.
    `parent` : (M, E, P) ; `children` : les deux amas du modèle (masse mesurée, masse invariante, impulsion du modèle)."""
    big_m, e_parent, p_parent = parent
    ground = np.array([c['mass'] for c in children])
    excite = np.array([max(0.0, c['invariant'] - c['mass']) for c in children])
    if ground.sum() >= big_m:
        masses = ground * (big_m * .999 / ground.sum())          # ne devrait pas arriver : le modèle refuse ces ruptures
    elif ground.sum() + excite.sum() < big_m:
        masses = ground + excite
    else:
        masses = ground + excite * ((big_m - ground.sum()) * .999 / max(1e-9, excite.sum()))
    m1, m2 = float(masses[0]), float(masses[1])
    p_star = math.sqrt(max(0.0, (big_m ** 2 - (m1 + m2) ** 2) * (big_m ** 2 - (m1 - m2) ** 2))) / (2 * big_m)
    pt_used = 0.0
    if rupture is not None and 'axis' in rupture:
        axis = np.asarray(rupture['axis'], float)
        mean = nuc.SIGMA * nuc.HBAR_C / math.pi                    # ⟨p_T²⟩ = σ/π (σ en MeV²)
        draw = rupture.get('pt_draw', 0.0)
        pt_used = math.sqrt(-mean * math.log(1 - draw * (1 - math.exp(-p_star ** 2 / mean))))
        pt_used = min(pt_used, p_star)
        first = -math.sqrt(max(0.0, p_star ** 2 - pt_used ** 2)) * axis + pt_used * np.asarray(rupture['pt_direction'], float)
    else:
        # la direction du modèle, dans le repos des deux morceaux du modèle
        e_model = [math.sqrt(c['invariant'] ** 2 + float(c['p_vec'] @ c['p_vec'])) for c in children]
        p_model = sum((c['p_vec'] for c in children), np.zeros(3))
        first = _boost(e_model[0], children[0]['p_vec'], -p_model / sum(e_model))[1]
    norm = float(np.linalg.norm(first))
    direction = first / norm if norm > 1e-9 else np.array([0.0, 0.0, 1.0])
    beta = p_parent / e_parent
    out = []
    for m, sign in ((m1, 1.0), (m2, -1.0)):
        e_lab, p_lab = _boost(math.sqrt(m * m + p_star * p_star), sign * p_star * direction, beta)
        out.append((m, e_lab, p_lab))
    info = dict(parent_mass=float(big_m), masses=(m1, m2), q_value=float(big_m - m1 - m2), p_star=float(p_star), pt=pt_used,
                direction=direction, momentum_error=float(np.linalg.norm(out[0][2] + out[1][2] - p_parent)),
                energy_error=float(abs(out[0][1] + out[1][1] - e_parent)))
    return out, info


def to_lab_frame(samples, axis, q_mag, w, breaks=None, frame_beta=None):
    """Ramène le film du modèle de cordes dans le repère du laboratoire, avec des hadrons qui bougent comme leurs 4-impulsions.

    1. Juste après le choc, les hadrons reçoivent leurs 4-impulsions exactes (`on_shell` : Σ E = M + ν, Σ p = q).
    2. Un hadron isolé n'échange rien avec les autres : sa 4-impulsion reste la même jusqu'à ce que sa corde se rompe.
    3. À la rupture, le parent se partage en deux (`split`, cinématique exacte à deux corps) : p₁ + p₂ = P, E₁ + E₂ = E ; les
       autres hadrons ne changent pas. La conservation tient donc à chaque instant, pas seulement au bilan final.
    4. Le centre de chacun avance à la vitesse v = p/E, à partir de l'endroit où il est né (le point de rupture) ; ses quarks
       gardent, autour de ce centre, le mouvement interne calculé par le modèle.
    Ainsi ce qu'on voit s'éloigner et les impulsions écrites sur les étiquettes sont la même chose. Les ruptures (`breaks`)
    reçoivent leur cinématique (`decay`). `frame_beta` : la vitesse d'un autre repère (celui du centre de masse pour deux
    hadrons) ; les 4-impulsions y sont transformées par le boost de Lorentz −β avant d'en tirer vitesses et étiquettes."""
    out = []
    lab_momentum = q_mag * axis
    centres = {}                      # amas (ensemble de quarks) → centre affiché
    quark_shift = {}                  # quark → décalage affiché de son amas
    lab4 = {}                         # amas → (masse, E, p) de laboratoire
    pending = list(breaks or [])
    previous_t, previous_keys = None, None
    for s in samples:
        if s['time'] < 0 or not s['clusters'] or 'p_vec' not in s['clusters'][0]:
            out.append(s)
            continue
        keys = [frozenset(c['ids']) for c in s['clusters']]
        new = [i for i, k in enumerate(keys) if k not in lab4]
        gone = [k for k in (previous_keys or []) if k not in keys]
        if new and previous_keys is not None and len(new) == 2 and len(gone) == 1:
            rupture = pending.pop(0) if pending else None
            children, decay = split(lab4[gone[0]], [s['clusters'][i] for i in new], rupture)
            for i, f in zip(new, children):
                lab4[keys[i]] = f
            if rupture is not None:
                rupture['decay'] = decay
                # la rupture a lieu là où le parent est montré : son point du modèle, plus le décalage du parent à cet instant
                known = [quark_shift[i] for i in gone[0] if i in quark_shift]
                if known and 'position' in rupture:
                    rupture['position'] = np.asarray(rupture['position'], float) + np.mean(known, axis=0)
        elif new:
            # le premier instant après le choc (ou un cas que le partage à deux ne décrit pas) : tous à la couche de masse
            for k, f in zip(keys, on_shell(s['clusters'], w, lab_momentum)):
                lab4[k] = f
        previous_keys = keys
        fourvectors = [lab4[k] for k in keys]
        if frame_beta is not None:
            fourvectors = [(m, *_boost(e, p, -np.asarray(frame_beta, float))) for m, e, p in fourvectors]
        dt = 0.0 if previous_t is None else s['time'] - previous_t
        previous_t = s['time']
        x = s['x'].copy()
        v = s['v'].copy()
        clusters = []
        for c, (m, e, p) in zip(s['clusters'], fourvectors):
            ids = list(c['ids'])
            key = frozenset(ids)
            model_centre = s['x'][ids].mean(axis=0)
            velocity = p / e
            if key not in centres:
                # né d'une rupture : il part de là où étaient ses quarks à l'écran (le décalage déjà pris par son parent)
                known = [quark_shift[i] for i in ids if i in quark_shift]
                inherited = np.mean(known, axis=0) if known else np.zeros(3)
                centres[key] = model_centre + inherited
            else:
                centres[key] = centres[key] + velocity * dt
            shift = centres[key] - model_centre
            for i in ids:
                quark_shift[i] = shift
            x[ids] = s['x'][ids] + shift
            model_velocity = (s['v'][ids]).mean(axis=0)
            v[ids] = s['v'][ids] - model_velocity + velocity
            clusters.append(dict(c, p_vec=p, momentum=float(np.linalg.norm(p)), energy=e, invariant=m,
                                 junction=c['junction'] + shift))
        out.append(dict(s, x=x, v=v, clusters=clusters))
    return out


def break_step(b):
    """L'étape « la corde se rompt » : où et pourquoi (le taux de Lund), puis la cinématique exacte du partage."""
    values = "t = %s fm/c  ·  paire %s%s̄  ·  séparation %s fm  ·  σd = %s MeV" % (
        _fmt(b['time']), b['flavor'], b['flavor'], _fmt(b['separation']), _fmt(b['released']))
    result = "Hadrons formés : %s." % ' + '.join(b['hadrons'])
    decay = b.get('decay')
    if decay is not None:
        m1, m2 = decay['masses']
        cosine = abs(float(decay['direction'] @ b['axis'])) if 'axis' in b else None
        values += "  ·  M = %s → %s + %s MeV  ·  Q = %s MeV  ·  p* = %s MeV/c  ·  p_T = %s MeV/c%s  ·  |Σp − P| = %s MeV/c" % (
            _fmt(decay['parent_mass']), _fmt(m1), _fmt(m2), _fmt(decay['q_value']), _fmt(decay['p_star']), _fmt(decay['pt']),
            "" if cosine is None else " (%s° de la corde)" % _fmt(math.degrees(math.acos(min(1.0, cosine)))),
            "%.0e" % max(decay['momentum_error'], 1e-12))
        result += (" La corde a donné σd en masse ; ce qui reste, Q = M − m₁ − m₂, devient le mouvement des deux morceaux : dos à "
                   "dos à p* le long de la corde dans le repos du parent (plus le p_T de la paire, effet tunnel), puis emportés "
                   "par son élan. Les autres hadrons ne changent pas.")
    return dict(time=b['time'], title="La corde se rompt",
                formula="d(hasard)/dt = Σ Γ₀ exp(−πm²/σ) (L − d_min)   ·   p* = √((M² − (m₁+m₂)²)(M² − (m₁−m₂)²)) / 2M   ·   dP/dp_T² ∝ exp(−πp_T²/σ)",
                values=values, result=result)


# ================================================================== le bilan et les trajectoires
def _outcome_steps(collision, event, samples, breaks, steps):
    last = samples[-1]
    names = []
    for c in last['clusters']:
        mass = c.get('invariant', c['mass'])
        excited = mass - c['mass'] > 5
        names.append('%s%s (%s MeV)' % (c['name'], '*' if excited else '', _fmt(mass)))
    charge = sum(data.QUARK_CHARGE_THIRDS[q['flavor']] * (-1 if q['anti'] else 1) for q in last['quarks']) / 3
    baryon = sum((-1 if q['anti'] else 1) for q in last['quarks']) / 3
    energy = sum(c.get('energy', math.sqrt(c['mass'] ** 2 + c['momentum'] ** 2)) for c in last['clusters'])
    p_sum = sum((np.asarray(c['p_vec']) for c in last['clusters'] if 'p_vec' in c), np.zeros(3))
    m = collision.m
    beam = collision.beam
    before = beam['charge'] + (1 if collision.target == 'proton' else 0)
    steps.append(dict(time=last['time'], title="Bilan : tout est conservé",
                      formula="E + M = E′ + Σ E_hadrons   ·   k = k′ + Σ p_hadrons   ·   charge, nombre baryonique, nombre leptonique",
                      values="état final : %s + %s  ·  Σ E = %s MeV (M + ν = %s)  ·  |Σ p| = %s MeV/c (|q| = %s)  ·  charge %+d → %+d (%s) %+.0f (hadrons)  ·  B = %.0f" % (
                          beam['out_label'], ' + '.join(names), _fmt(energy, 5), _fmt(m + event['nu'], 5),
                          _fmt(float(np.linalg.norm(p_sum)), 5), _fmt(event['q_mag'], 5), before, beam['out_charge'] + round(charge),
                          beam['out_label'], charge, baryon),
                      result="%s diffusé à E′ = %s MeV, angle %.1f°. Un astérisque marque un hadron encore excité à la fin "
                             "du film." % (beam['out_label'], _fmt(event['e_out']), collision.angle)))


def _tracks(collision, event, samples, info):
    """Le trajet de l'électron et le point où le photon est absorbé (fm, fm/c ; faisceau selon z, plan de diffusion x–z).

    Le paramètre d'impact est pris à ħc/|q| : la distance que résout le photon échangé (une onde plane n'en a pas d'exact).
    La trajectoire d'approche est intégrée dans le champ de Coulomb du proton ; on garde sa déviation pour la montrer."""
    from quantumlab import bag
    lam = event['wavelength_fm']
    # une onde plane n'a pas de paramètre d'impact exact : on fait passer l'électron juste hors du sac (on voit ainsi le champ
    # les relier et le photon entrer), ou à ħc/|q| si c'est plus loin ; la résolution réelle ħc/|q| est écrite à l'écran
    first = next(s for s in samples if s['time'] >= 0)
    beam = collision.beam
    a = bag.density_length(collision.target)
    if collision.kind == 'cc':
        # le W a une portée de ħc/M_W = 0,0025 fm : le neutrino passe sur le quark qu'il touche ; rien ne le dévie avant
        if event['channel'] == 'qe':
            hit = info['converted']
        elif event.get('resonance'):
            hit = 0
        else:
            hit = info['struck']
        target = first['x'][min(hit, len(first['x']) - 1)].copy()
        zs = np.linspace(-40.0, target[2], 201)
        path = np.column_stack([np.full(len(zs), target[0]), np.full(len(zs), target[1]), zs])
        b = float(math.hypot(target[0], target[1]))
        deflection = 0.0
        out = math.sin(collision.theta), math.cos(collision.theta)
        return dict(impact=b, photon_from=path[-1].copy(), photon_to=target, out_direction=np.array([out[0], 0.0, out[1]]),
                    energy_in=collision.energy, energy_out=event['e_out'], wavelength=lam, pre_roll=PRE_ROLL_FM,
                    path=path[::4], deflection=0.0, max_deflection=0.0)
    b = min(6.0, max(bag.bag(collision.target)['radius'] * 1.75, HBAR_C_FM / max(event['q_mag'], 1e-6)))
    path, deflection = bag.coulomb_path(collision.energy, b, a, start_fm=-40.0, end_fm=0.0, steps=200, mass=beam['mass'],
                                        charge=beam['charge'])
    if collision.target == 'neutron':
        path = np.column_stack([np.full(len(path), b), np.zeros(len(path)), path[:, 2]])
        deflection = 0.0
    target = np.zeros(3)
    if event['channel'] in ('dis', 'dual') or (event['channel'] == 'res' and not event.get('resonance')):
        target = first['x'][info['struck']].copy()
    th = collision.theta
    out_dir = np.array([math.sin(th), 0.0, math.cos(th)])
    vertex = path[-1].copy()
    return dict(impact=b, photon_from=vertex, photon_to=target, out_direction=out_dir,
                energy_in=collision.energy, energy_out=event['e_out'], wavelength=lam, pre_roll=PRE_ROLL_FM,
                path=path[::4], deflection=deflection,
                max_deflection=0.0 if collision.target == 'neutron' else bag.classical_max_deflection(collision.energy, a, beam['mass']))
