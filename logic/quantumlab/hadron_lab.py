"""L'atelier de hadrons : assembler des quarks, vérifier qu'ils forment un objet viable, calculer sa masse et son destin.

1. Le filtre de validité. La couleur : la somme des poids SU(3) des quarks doit être nulle (R+V+B, ou couleur + anticouleur) ;
   la charge se somme en tiers de e ; le nombre de quarks moins celui d'antiquarks doit être un multiple de 3.
2. La masse. Quarks constituants + interaction hyperfine de spin (De Rújula–Georgi–Glashow) : on diagonalise
   H = Σ m_i + Σ_{i<j} A/(m_i m_j)^p σ_i·σ_j dans l'espace de spin, restreint aux états symétriques pour les quarks identiques
   (principe de Pauli, la couleur étant antisymétrique). Les constantes sont ajustées sur 38 masses mesurées ; l'écart au PDG est
   affiché. Les baryons sont à ≈ 2 % ; les mésons à ≈ 10 % (le modèle additif est mauvais pour eux, c'est connu).
3. Le destin. Voies de désintégration énumérées sous les lois de conservation (charge, nombre baryonique, nombres leptoniques,
   saveur) et le seuil cinématique ; durée de vie : largeur forte calibrée sur Δ → Nπ, désintégration faible d'un quark lourd par
   le modèle du spectateur (G_F² m⁵ |V|²/192π³), top par sa largeur à l'ordre dominant, W par ses voies.
"""
import itertools
import math

import numpy as np

from quantumlab import hadron_data as data
from quantumlab import standard_model as sm

COLOR_WEIGHTS = np.array([[.5, 1 / (2 * math.sqrt(3))], [-.5, 1 / (2 * math.sqrt(3))], [0.0, -1 / math.sqrt(3)]])
COLOR_NAMES = ['rouge', 'vert', 'bleu']
ANTI_NAMES = ['antirouge', 'antivert', 'antibleu']

_PAULI = [np.array([[0, 1], [1, 0]], complex), np.array([[0, -1j], [1j, 0]]), np.array([[1, 0], [0, -1]], complex)]


def _op(k, index, n):
    out = np.array([[1.0 + 0j]])
    for i in range(n):
        out = np.kron(out, _PAULI[k] if i == index else np.eye(2))
    return out


def _dot(i, j, n):
    return sum(_op(k, i, n) @ _op(k, j, n) for k in range(3))


def spin_levels(masses, constants, symmetric_pairs):
    """Énergies hyperfines et spins totaux de `len(masses)` quarks ; `symmetric_pairs` : paires de quarks identiques."""
    n = len(masses)
    dim = 2 ** n
    amplitude, power = constants
    h = np.zeros((dim, dim), complex)
    s2 = np.zeros((dim, dim), complex)
    for i, j in itertools.combinations(range(n), 2):
        d = _dot(i, j, n)
        h += amplitude / (masses[i] * masses[j]) ** power * d
        s2 += d
    spin_sq = (3 * n * np.eye(dim) + 2 * s2) / 4
    basis = np.eye(dim, dtype=complex)
    for i, j in symmetric_pairs:
        swap = np.zeros((dim, dim))
        for state in range(dim):
            bits = [(state >> (n - 1 - k)) & 1 for k in range(n)]
            bits[i], bits[j] = bits[j], bits[i]
            swap[sum(b << (n - 1 - k) for k, b in enumerate(bits)), state] = 1
        reduced = basis.conj().T @ swap @ basis
        values, vectors = np.linalg.eigh((reduced + reduced.conj().T) / 2)
        basis = basis @ vectors[:, values > .5]
    reduced_h = basis.conj().T @ h @ basis
    reduced_s = basis.conj().T @ spin_sq @ basis
    # H commute avec S² : on diagonalise une combinaison pour séparer sans ambiguïté
    values, vectors = np.linalg.eigh(reduced_h + 1e-7 * reduced_s)
    out = []
    for k in range(len(values)):
        v = vectors[:, k]
        out.append(((v.conj() @ reduced_h @ v).real, (v.conj() @ reduced_s @ v).real))
    return out


# ================================================================== le filtre de validité
def check(slots):
    """`slots` : liste de (saveur, antiquark ?, couleur). Renvoie les nombres quantiques et les raisons d'un éventuel refus."""
    reasons = []
    quarks = [s for s in slots if s[0] in data.QUARK_CHARGE_THIRDS]
    others = [s[0] for s in slots if s[0] not in data.QUARK_CHARGE_THIRDS]
    if others:
        reasons.append("%s n'entre pas dans un hadron : seuls les quarks s'assemblent par la force forte (les leptons et les bosons "
                       "ne portent pas de couleur, ou sont des médiateurs)." % ', '.join(others))
    n_q = sum(1 for s in quarks if not s[1])
    n_a = sum(1 for s in quarks if s[1])
    content = ''.join(s[0].upper() if s[1] else s[0] for s in quarks)
    charge, baryon, flavor = data.numbers_of(content)
    weight = np.zeros(2)
    for flavor_id, anti, colour in quarks:
        w = COLOR_WEIGHTS[colour % 3]
        weight += -w if anti else w
    if len(quarks) < 2:
        reasons.append("Un hadron a au moins deux quarks (un quark seul n'existe pas : confinement).")
    if len(quarks) > 5:
        reasons.append("Plus de cinq quarks : aucun état de cette taille n'est connu.")
    if (n_q - n_a) % 3 != 0:
        reasons.append("Le nombre de quarks moins celui d'antiquarks (%d) n'est pas un multiple de 3 : aucune combinaison "
                       "de couleurs ne donne un objet neutre." % (n_q - n_a))
    if np.abs(weight).max() > 1e-9:
        names = ', '.join((ANTI_NAMES if s[1] else COLOR_NAMES)[s[2] % 3] for s in quarks)
        reasons.append("Couleur non neutre (%s) : il faut rouge+vert+bleu, ou une couleur et son anticouleur. "
                       "Poids SU(3) total (%s ; %s) au lieu de (0 ; 0)." % (names, sm.fr(weight[0]), sm.fr(weight[1])))
    identical = [(a, b) for a, b in itertools.combinations(range(len(quarks)), 2)
                 if quarks[a][0] == quarks[b][0] and quarks[a][1] == quarks[b][1] and quarks[a][2] == quarks[b][2]]
    if identical:
        reasons.append("Deux quarks identiques de même couleur : le principe d'exclusion de Pauli l'interdit (la fonction d'onde "
                       "de couleur doit être antisymétrique).")
    return dict(valid=not reasons, reasons=reasons, content=content, charge=charge / 3, baryon=baryon / 3,
                strangeness=flavor[0], charm=flavor[1], beauty=flavor[2], top=flavor[3], colour_weight=weight.tolist(),
                n_quarks=n_q, n_antiquarks=n_a)


def default_colours(content):
    """Une coloration neutre pour un contenu (baryon : R V B ; méson : couleur + anticouleur) ou None."""
    letters = list(content)
    n_q = sum(1 for c in letters if c.islower())
    n_a = len(letters) - n_q
    if (n_q - n_a) % 3:
        return None
    colours, free_q, free_a = [None] * len(letters), [], []
    # on apparie d'abord quark et antiquark (même couleur), puis ce qui reste par trois
    quarks = [i for i, c in enumerate(letters) if c.islower()]
    antis = [i for i, c in enumerate(letters) if c.isupper()]
    k = 0
    while quarks and antis:
        colours[quarks.pop()] = k % 3
        colours[antis.pop()] = k % 3
        k += 1
    for group in (quarks, antis):
        for index, i in enumerate(group):
            colours[i] = index % 3
    return colours


# ================================================================== la masse
def _flavor_masses(letters):
    if len(letters) == 2:
        return [sm.CONSTITUENT_MESON[c.lower()] for c in letters], sm.HYPERFINE_MESON
    return [sm.CONSTITUENT_BARYON[c.lower()] for c in letters], sm.HYPERFINE_BARYON


def spectrum(content):
    """Les états de spin de plus basse énergie d'un contenu : [(spin, masse du modèle MeV)] (mésons, baryons, antibaryons)."""
    letters = list(content)
    if len(letters) not in (2, 3) or 't' in content.lower():
        return []
    masses, constants = _flavor_masses(letters)
    pairs = []
    if len(letters) == 3:       # quarks identiques seulement dans un baryon (et un méson q q̄ n'a pas de Pauli)
        pairs = [(i, j) for i, j in itertools.combinations(range(3), 2) if letters[i] == letters[j]]
    levels = spin_levels(masses, constants, pairs)
    rest = sum(masses)
    found = {}
    for energy, s_sq in levels:
        spin = round((-1 + math.sqrt(1 + 4 * max(0.0, s_sq))) / 2 * 2) / 2
        found.setdefault((spin, round(energy, 3)), energy)
    out = sorted(((spin, rest + energy) for (spin, _), energy in found.items()), key=lambda s: s[1])
    # une seule énergie par (spin, énergie) — plusieurs états dégénérés comptent pour un
    return out


def known_state(content, spin, model_mass=None):
    """L'entrée du PDG qui correspond à (contenu, spin) : la plus proche en masse si plusieurs (Λ / Σ⁰)."""
    key = ''.join(sorted(content))
    candidates = [h for h in data.HADRONS.values() if ''.join(sorted(h['content'])) == key and abs(h['J'] - spin) < .01]
    if not candidates:
        # antibaryon ou méson conjugué : on cherche le conjugué
        conj = ''.join(sorted(c.swapcase() for c in content))
        candidates = [h for h in data.HADRONS.values() if ''.join(sorted(h['content'])) == conj and abs(h['J'] - spin) < .01]
    if not candidates:
        return None
    if model_mass is None:
        return candidates[0]
    best = min(candidates, key=lambda h: abs(h['mass'] - model_mass))
    return best if abs(best['mass'] - model_mass) < 160 else None       # un état voisin d'un autre multiplet n'est pas « le même »


# ================================================================== le destin
_QN_CACHE = {}


def _qn(name):
    if name not in _QN_CACHE:
        _QN_CACHE[name] = data.quantum_numbers(name)
    return _QN_CACHE[name]


def hadron_numbers(content):
    c, b, f = data.numbers_of(content)
    return dict(charge=c / 3, baryon=b / 3, flavor=f)


def conserves(parent, products):
    """Vérifie charge, baryon, leptons et renvoie (conserve ?, changement de saveur (S, C, B', T))."""
    total = dict(charge=0.0, baryon=0.0, lepton=[0, 0, 0], flavor=[0, 0, 0, 0])
    for name in products:
        q = _qn(name)
        total['charge'] += q['charge']
        total['baryon'] += q['baryon']
        for i in range(3):
            total['lepton'][i] += q['lepton'][i]
        for i in range(4):
            total['flavor'][i] += q['flavor'][i]
    ok = (abs(total['charge'] - parent['charge']) < 1e-9 and abs(total['baryon'] - parent['baryon']) < 1e-9
          and total['lepton'] == [0, 0, 0])
    delta = tuple(total['flavor'][i] - parent['flavor'][i] for i in range(4))
    return ok, delta


def interaction_of(parent, products):
    """'forte' si la saveur est conservée sans lepton ni photon ; 'électromagnétique' avec photon ; sinon 'faible'."""
    ok, delta = conserves(parent, products)
    if not ok:
        return None
    if any(delta):
        return 'faible'
    if any(p in data.LEPTONS for p in products):
        return 'électromagnétique' if 'γ' in products else 'faible'
    return 'forte'


def two_body_momentum(m, m1, m2):
    """|p*| dans le repos du parent pour M → m1 + m2 (MeV/c) ; 0 sous le seuil."""
    if m < m1 + m2:
        return 0.0
    return math.sqrt((m ** 2 - (m1 + m2) ** 2) * (m ** 2 - (m1 - m2) ** 2)) / (2 * m)


def open_channels(parent_numbers, mass, max_body=3):
    """Toutes les désintégrations à 2 ou 3 corps ouvertes pour un objet de ces nombres quantiques et de cette masse."""
    products = [p for p in list(data.HADRONS) + list(data.LEPTONS)
                if p not in ('Δ++', 'Δ+', 'Δ0', 'Δ-') and not p.startswith('Σ*') and not p.startswith('Ξ*')
                and p not in ('ρ0', 'ρ+', 'ω', 'K*+', 'K*0', 'φ', 'D*+', 'Ds*+', 'J/ψ', 'Υ', 'η')]
    out = []
    for k in range(2, max_body + 1):
        for combo in itertools.combinations_with_replacement(products, k):
            masses = sum(_qn(c)['mass'] for c in combo)
            if masses >= mass:
                continue
            kind = interaction_of(parent_numbers, combo)
            if kind is None:
                continue
            if k == 3 and kind != 'faible':
                continue            # 3 corps : on ne garde que les semileptoniques / hadroniques faibles
            out.append(dict(products=list(combo), kind=kind, q_value=mass - masses))
    return out


def strong_width(mass, products, calibrated=(117.0, 1232.0, 938.272, 139.57)):
    """Largeur d'une désintégration forte à 2 corps : Γ = Γ_Δ (p*/p*_Δ)³ · (M_Δ/M)  — une calibration sur Δ → Nπ (P-onde)."""
    width, m_delta, m_n, m_pi = calibrated
    p_delta = two_body_momentum(m_delta, m_n, m_pi)
    p = two_body_momentum(mass, *[data.quantum_numbers(x)['mass'] for x in products])
    return width * (p / p_delta) ** 3 * (m_delta / mass) if p > 0 else 0.0


def phase_space_factor(rho):
    """f(ρ) = 1 − 8ρ + 8ρ³ − ρ⁴ − 12ρ² ln ρ : l'espace de phase d'un quark final de masse² relative ρ (désintégration du muon : f(0)=1)."""
    if rho <= 0:
        return 1.0
    if rho >= 1:
        return 0.0
    return 1 - 8 * rho + 8 * rho ** 3 - rho ** 4 - 12 * rho ** 2 * math.log(rho)


def heavy_quark_decay(quark):
    """Désintégration faible d'un quark lourd (c, b) dans le modèle du spectateur : voies, largeurs, durée de vie (s)."""
    m = sm.BY_ID[quark]['mass']
    base = sm.G_F ** 2 * m ** 5 / (192 * math.pi ** 3)
    down = ['d', 's', 'b'] if quark in ('u', 'c', 't') else ['u', 'c']
    targets = ['d', 's'] if quark == 'c' else ['u', 'c']
    channels = []
    leptons = [('e', .511), ('μ', 105.66), ('τ', 1776.86)]
    for target in targets:
        v = sm.CKM[(quark, target)] if quark == 'c' else sm.CKM[(target, quark)]
        mt = sm.BY_ID[target]['mass']
        rho_q = (mt / m) ** 2
        for name, ml in leptons:
            rho_l = (ml / m) ** 2
            if mt + ml >= m:
                continue
            factor = phase_space_factor(rho_q) * (phase_space_factor(rho_l) if ml > 1 else 1)
            sign_text = '%s⁺ ν' % name if quark == 'c' else '%s⁻ ν̄' % name
            channels.append(dict(label='%s → %s %s' % (quark, target, sign_text), width=base * v ** 2 * factor, kind='semileptonic',
                                 target=target, lepton=name))
        # W* → q q̄' (couleur ×3) : u d̄, u s̄ (et c s̄ pour le b)
        for up, dn in (('u', 'd'), ('u', 's'), ('c', 'd'), ('c', 's')):
            if quark == 'c' and up != 'u':
                continue
            if quark == 'b' and up == 'c' and target == 'c':
                continue
            mu = sm.BY_ID[up]['mass'] if (quark == 'c' or up == 'c') else 0.0
            if mt + sm.BY_ID[up]['mass'] + sm.BY_ID[dn]['mass'] >= m:
                continue
            vw = sm.CKM[(up, dn)]
            v = sm.CKM[(quark, target)] if quark == 'c' else sm.CKM[(target, quark)]
            factor = phase_space_factor(rho_q) * (phase_space_factor((sm.BY_ID[up]['mass'] / m) ** 2) if up == 'c' else 1.0)
            # c → s W⁺* → s (u d̄) ; b → c W⁻* → c (d ū) : la paire emporte la charge du W*
            pair = (up + dn.upper()) if quark == 'c' else (dn + up.upper())
            text = '%s %s̄' % (up, dn) if quark == 'c' else '%s %s̄' % (dn, up)
            channels.append(dict(label='%s → %s %s' % (quark, target, text), width=3 * base * v ** 2 * vw ** 2 * factor,
                                 kind='hadronic', target=target, pair_content=pair))
    total = sum(c['width'] for c in channels)
    for c in channels:
        c['branching'] = c['width'] / total
    return dict(channels=channels, width=total, lifetime=sm.HBAR_MEV_S / total)


def w_boson_channels():
    """Voies de désintégration du W⁺ à l'ordre dominant : Γ(W → f f̄') = G_F M_W³/(6π√2) × (couleurs) × |V|²."""
    unit = sm.G_F * sm.M_W ** 3 / (6 * math.pi * math.sqrt(2))
    channels = [dict(label='e⁺ νₑ', width=unit), dict(label='μ⁺ ν_μ', width=unit), dict(label='τ⁺ ν_τ', width=unit)]
    for up in ('u', 'c'):
        for dn in ('d', 's', 'b'):
            channels.append(dict(label='%s %s̄' % (up, dn), width=3 * unit * sm.CKM[(up, dn)] ** 2))
    total = sum(c['width'] for c in channels)
    for c in channels:
        c['branching'] = c['width'] / total
    return dict(channels=channels, width=total)


def neutron_lifetime(g_a=1.2754):
    """τ_n = 2π³ ħ / (G_F² |V_ud|² (1 + 3 g_A²) m_e⁵ f), f = ∫ E p (Δ − E)² dE / m_e⁵ (sans correction coulombienne)."""
    me, delta = .51099895, 939.5654205 - 938.27208816
    energies = np.linspace(me, delta, 20001)
    p = np.sqrt(np.maximum(energies ** 2 - me ** 2, 0))
    f = np.trapezoid(energies * p * (delta - energies) ** 2, energies) / me ** 5
    rate = sm.G_F ** 2 * sm.CKM[('u', 'd')] ** 2 * (1 + 3 * g_a ** 2) * me ** 5 * f / (2 * math.pi ** 3)
    return sm.HBAR_MEV_S / rate, f


def pi0_width(f_pi=92.2):
    """Γ(π⁰ → γγ) = α² m_π³ / (64 π³ f_π²) : l'anomalie chirale, en MeV."""
    m = 134.9768
    return sm.ALPHA ** 2 * m ** 3 / (64 * math.pi ** 3 * f_pi ** 2)


def lepton_lifetime(name):
    """Muon : τ = 192π³ ħ / (G_F² m⁵) ; tau : même formule × le rapport de branchement leptonique (≈ 17,8 % ×2)."""
    m = {'mu': 105.6583755, 'tau': 1776.86}[name]
    width = sm.G_F ** 2 * m ** 5 / (192 * math.pi ** 3)
    if name == 'tau':
        # voies : e ν ν, μ ν ν, et d ū (×3 colonnes de couleur, |V_ud|²) : 1 + 1·f(μ) + 3|V_ud|² ≈ 5 voies
        width *= 1 + phase_space_factor((105.6583755 / m) ** 2) + 3 * sm.CKM[('u', 'd')] ** 2
    return sm.HBAR_MEV_S / width


def decay_kinematics(mass, masses, rng):
    """Une désintégration M → m_i dans le repos du parent : impulsions (MeV/c) tirées uniformément dans l'espace de phase.

    2 corps : exact (impulsion fixée, direction isotrope). 3 corps : échantillonnage plat de Dalitz.
    """
    masses = list(masses)
    if len(masses) == 1:
        return [np.zeros(3)]
    if len(masses) == 2:
        p = two_body_momentum(mass, *masses)
        v = rng.normal(size=3)
        v /= np.linalg.norm(v)
        return [p * v, -p * v]
    if len(masses) == 3:
        m1, m2, m3 = masses
        while True:
            m12 = rng.uniform(m1 + m2, mass - m3)
            # énergie de 3 dans le repos du parent
            p3 = two_body_momentum(mass, m12, m3)
            p12 = two_body_momentum(m12, m1, m2)
            # poids de Dalitz : ∝ p3 · p12 / M ; rejet par la valeur maximale approchée
            weight = p3 * p12
            peak = two_body_momentum(mass, m1 + m2, m3) * 0 + 1.0
            if rng.uniform() <= weight / max(1e-12, _dalitz_peak(mass, m1, m2, m3)):
                break
        v = rng.normal(size=3)
        v /= np.linalg.norm(v)
        u = rng.normal(size=3)
        u /= np.linalg.norm(u)
        # 3 est dans la direction v ; le système (1,2) recule ; dans son repos 1 et 2 partent en sens opposés (direction u)
        e12 = math.sqrt(m12 ** 2 + p3 ** 2)
        beta = -p3 / e12
        gamma = e12 / m12
        out = []
        for m, sign in ((m1, 1), (m2, -1)):
            pm = p12 * sign * u
            em = math.sqrt(m ** 2 + p12 ** 2)
            along = pm @ (-v)                 # le système (1,2) se déplace dans la direction −v
            boosted = pm + (gamma - 1) * along * (-v) + gamma * (p3 / e12) * em * (-v)
            out.append(boosted)
        out.append(p3 * v)
        return out
    raise ValueError('2 ou 3 corps seulement')


def _dalitz_peak(mass, m1, m2, m3):
    grid = np.linspace(m1 + m2, mass - m3, 60)
    return max(two_body_momentum(mass, g, m3) * two_body_momentum(g, m1, m2) for g in grid) + 1e-12


# ================================================================== l'analyse complète
def lightest(content):
    """Le hadron connu le plus léger de ce contenu (ou None)."""
    key = ''.join(sorted(content))
    options = [h for h in data.HADRONS.values() if ''.join(sorted(h['content'])) == key]
    if not options:
        conj = ''.join(sorted(c.swapcase() for c in content))
        options = [h for h in data.HADRONS.values() if ''.join(sorted(h['content'])) == conj]
    return min(options, key=lambda h: h['mass']) if options else None


def analyse(slots):
    """Tout ce que l'atelier affiche pour un assemblage : validité, nombres quantiques, états (masse du modèle et du PDG), destin."""
    info = check(slots)
    content = info['content']
    out = dict(info, states=[], fate=None)
    if 't' in content.lower() and (info['valid'] or True):
        # le top se désintègre avant de s'habiller : même s'il est dans un assemblage, il est signalé
        width = sm.top_width()
        out['top'] = dict(width=width, lifetime=sm.HBAR_MEV_S / width, hadronisation=sm.hadronisation_time(),
                          ratio=(sm.HBAR_MEV_S / width) / sm.hadronisation_time())
    if not info['valid']:
        return out
    letters = list(content)
    if len(letters) in (2, 3) and 't' not in content.lower():
        for spin, mass in spectrum(content):
            known = known_state(content, spin, mass)
            out['states'].append(dict(spin=spin, model_mass=mass, known=None if known is None else dict(
                name=known['name'], mass=known['mass'], error=mass - known['mass'])))
    elif len(letters) > 3 and 't' not in content.lower():
        out['states'] = []
    return out


def fate_of(slots, spin=None):
    """Le destin d'un assemblage valide : stable, désintégration (forte / électromagnétique / faible), top, ou dislocation."""
    info = analyse(slots)
    content = info['content']
    out = dict(info)
    if not info['valid']:
        out['fate'] = dict(kind='refused', summary=' '.join(info['reasons']))
        return out
    if 't' in content.lower():
        t = info['top']
        out['fate'] = dict(kind='top', lifetime=t['lifetime'], width=t['width'], hadronisation=t['hadronisation'],
                           summary=("Le quark top vit τ = ħ/Γ = %s s (Γ = G_F m_t³/8π√2 · (1−m_W²/m_t²)²(1+2m_W²/m_t²) = %s GeV), "
                                    "%s fois moins que le temps d'habillage ħ/Λ_QCD = %s s : il se désintègre en W + b avant d'avoir pu "
                                    "former un hadron.") % (sm.fr(t['lifetime']), sm.fr(t['width'] / 1000),
                                                            sm.fr(t['hadronisation'] / t['lifetime'], 2), sm.fr(t['hadronisation'])))
        return out
    states = info['states']
    if not states:
        # exotique : seuil de la meilleure paire de hadrons
        out['fate'] = exotic_fate(content)
        return out
    state = next((s for s in states if spin is None or abs(s['spin'] - spin) < .01), states[0])
    out['chosen'] = state
    known = data.HADRONS[state['known']['name']] if state['known'] else None
    numbers = hadron_numbers(content)
    mass = known['mass'] if known else state['model_mass']
    out['fate'] = fate_state(content, numbers, mass, known)
    return out


def exotic_fate(content):
    """Quatre ou cinq quarks : on cherche le seuil de la meilleure coupure en deux hadrons colorés neutres."""
    best = None
    letters = list(content)
    for k in range(2, len(letters) - 1):
        for left in itertools.combinations(range(len(letters)), k):
            a = ''.join(letters[i] for i in left)
            b = ''.join(letters[i] for i in range(len(letters)) if i not in left)
            ha, hb = lightest(a), lightest(b)
            if ha and hb:
                threshold = ha['mass'] + hb['mass']
                if best is None or threshold < best[0]:
                    best = (threshold, ha['name'], hb['name'])
    if best is None:
        return dict(kind='unknown', summary="Aucune coupure en hadrons connus : état exotique sans modèle.")
    return dict(kind='fall-apart', threshold=best[0], products=[best[1], best[2]],
                summary=("État à %d quarks : le plus bas seuil de dislocation est %s + %s à %s MeV. Un tel état ne peut exister "
                         "que s'il est lié sous ce seuil (c'est le cas des tétraquarks et pentaquarks découverts) ; ce modèle "
                         "n'a pas de calcul de liaison pour lui.") % (len(letters), best[1], best[2], sm.fr(best[0])))


def fate_state(content, numbers, mass, known):
    parent = dict(numbers)
    if known and known.get('stable'):
        return dict(kind='stable', summary="%s est stable : c'est le baryon le plus léger, rien de plus léger ne porte le nombre "
                                           "baryonique 1." % known['name'])
    if known and known['name'] == 'n':
        tau, f = neutron_lifetime()
        return dict(kind='weak', lifetime=tau, summary=("Le neutron se désintègre par interaction faible (d → u W⁻, W⁻ → e⁻ ν̄ₑ) : "
                    "τ = 2π³ħ/(G_F²|V_ud|²(1+3g_A²)mₑ⁵f) = %s s, avec f = %s (sans la correction coulombienne ; mesuré : 878 s).") % (
                    sm.fr(tau), sm.fr(f, 4)), channels=known['decays'])
    channels = open_channels(parent, mass)
    strong = [c for c in channels if c['kind'] == 'forte' and len(c['products']) == 2]
    if strong:
        best = max(strong, key=lambda c: c['q_value'])
        width = strong_width(mass, best['products'])
        return dict(kind='strong', width=width, lifetime=sm.HBAR_MEV_S / width if width else None, channels=strong[:8],
                    summary=("Désintégration forte ouverte : %s, énergie libérée Q = %s MeV. Largeur Γ = Γ_Δ (p*/p*_Δ)³ (M_Δ/M) = %s MeV "
                             "(calibrée sur Δ → Nπ), τ = ħ/Γ.") % (' + '.join(best['products']), sm.fr(best['q_value']), sm.fr(width)))
    if known and known['name'] == 'π0':
        w = pi0_width()
        return dict(kind='electromagnetic', width=w, lifetime=sm.HBAR_MEV_S / w, summary=(
            "π⁰ → γγ par l'anomalie chirale : Γ = α²mπ³/(64π³fπ²) = %s eV, soit τ = %s s (mesuré : 8,4·10⁻¹⁷ s).") % (
            sm.fr(w * 1e6), sm.fr(sm.HBAR_MEV_S / w)))
    em = [c for c in channels if c['kind'] == 'électromagnétique']
    if em and (not known or 'tau' not in known):
        c = em[0]
        return dict(kind='electromagnetic', channels=em[:6], summary="Désintégration électromagnétique : %s." % ' + '.join(c['products']))
    heavy = [q for q in content.lower() if q in ('c', 'b')]
    if heavy:
        quark = 'b' if 'b' in heavy else 'c'
        d = heavy_quark_decay(quark)
        return dict(kind='weak-heavy', quark=quark, lifetime=d['lifetime'], channels=d['channels'],
                    summary=("Le quark %s se désintègre par interaction faible (%s → q W*) : τ = 192π³ħ/(G_F² m⁵ Σ|V|² f) = %s s dans "
                             "le modèle du spectateur%s.") % (quark, quark, sm.fr(d['lifetime']), '' if not known or 'tau' not in known else
                                                             ' (mesuré : %s s)' % sm.fr(known['tau'])))
    if known and 'tau' in known:
        return dict(kind='weak', lifetime=known['tau'], channels=known['decays'],
                    summary="Désintégration faible (l'étrangeté change, seul le W le permet) : τ = %s s (mesuré)." % sm.fr(known['tau']))
    return dict(kind='unknown', summary="Aucune voie de désintégration ouverte trouvée.")


# ================================================================== une désintégration tirée
def _product_mass(name):
    if name in data.LEPTONS or name in data.HADRONS or name in (data.ANTI_OF or {}):
        return data.quantum_numbers(name)['mass']
    if name == 'W+' or name == 'W-':
        return sm.M_W
    if name == 'b' or name == 'B̄':
        return sm.BY_ID['b']['mass']
    return 0.0


def _antiparticle(name):
    return data.anti_name(name)


def _lightest_name(content):
    h = lightest(content)
    if h is None:
        return None
    if ''.join(sorted(h['content'])) == ''.join(sorted(content)):
        return h['name']
    return _antiparticle(h['name'])           # on a trouvé le conjugué : l'antiparticule


def heavy_final_state(content, quark, channel, anti):
    """Le contenu hadronique d'une désintégration c/b → q W* : (liste de produits, ou None si un hadron manque)."""
    letter = quark.upper() if anti else quark
    index = content.index(letter)
    spectators = content[:index] + content[index + 1:]
    target = channel['target'].upper() if anti else channel['target']
    main = _lightest_name(spectators + target)
    if main is None:
        return None
    if channel['kind'] == 'semileptonic':
        lep = channel['lepton']
        # c → s ℓ⁺ ν (W⁺*) ; b → c ℓ⁻ ν̄ (W⁻*) ; l'antiquark prend les antiparticules
        plus = {'e': ('e+', 'νe'), 'μ': ('μ+', 'νμ'), 'τ': ('τ+', 'ντ')}[lep]
        pair = list(plus) if quark == 'c' else [data.anti_name(n) for n in plus]
        if anti:
            pair = [data.anti_name(n) for n in pair]
        return [main] + pair
    pair_content = channel['pair_content']
    if anti:
        pair_content = pair_content.swapcase()
    second = _lightest_name(pair_content)
    return None if second is None else [main, second]


def decay_event(result, rng):
    """Tire une désintégration de l'objet analysé : (étiquette, produits, impulsions dans le repos du parent, masse du parent)."""
    fate = result.get('fate') or {}
    kind = fate.get('kind')
    if kind in (None, 'refused', 'stable', 'unknown'):
        return None
    content = result['content']
    chosen = result.get('chosen')
    known = data.HADRONS[chosen['known']['name']] if chosen and chosen['known'] else None
    mass = known['mass'] if known else (chosen['model_mass'] if chosen else 0.0)
    if kind == 'top':
        names = ['b', 'W+']
        label = 't → b W⁺'
        masses = [sm.BY_ID['b']['mass'], sm.M_W]
        momenta = decay_kinematics(sm.BY_ID['t']['mass'], masses, rng)
        return dict(label=label, parent_mass=sm.BY_ID['t']['mass'], products=list(zip(names, masses, momenta)))
    products = None
    label = ''
    if kind == 'fall-apart':
        products = list(fate['products'])
        label = ' → '.join(['état exotique', ' + '.join(products)])
    elif kind == 'weak-heavy':
        quark = fate['quark']
        anti = quark.upper() in content
        letter = quark.upper() if anti else quark
        if letter not in content:
            letter = quark.upper() if quark.upper() in content else quark
            anti = letter.isupper()
        options = []
        for ch in fate['channels']:
            final = heavy_final_state(content, quark, ch, anti)
            if final is None:
                continue
            if sum(_product_mass(n) for n in final) < mass:
                options.append((ch, final))
        if not options:
            return None
        weights = np.array([c['branching'] for c, _ in options])
        ch, products = options[int(rng.choice(len(options), p=weights / weights.sum()))]
        label = '%s → %s' % (ch['label'], ' + '.join(products))
    else:
        channels = fate.get('channels') or []
        table = []
        for c in channels:
            if isinstance(c, tuple):
                table.append((c[0], list(c[1])))
            elif isinstance(c, dict) and 'products' in c:
                table.append((1.0, c['products']))
        if not table:
            return None
        weights = np.array([b for b, _ in table])
        _, products = table[int(rng.choice(len(table), p=weights / weights.sum()))]
        label = ' → '.join([known['name'] if known else 'objet', ' + '.join(products)])
    masses = [_product_mass(n) for n in products]
    if len(products) > 3 or sum(masses) >= mass:
        return None
    momenta = decay_kinematics(mass, masses, rng)
    return dict(label=label, parent_mass=mass, products=list(zip(products, masses, momenta)))


def full_analysis(slots, spin_index=0, seed=1):
    """Tout ce que le protocole envoie pour l'atelier : analyse, destin d'un état de spin choisi et une désintégration tirée."""
    base = analyse(slots)
    states = base['states']
    spin = states[min(spin_index, len(states) - 1)]['spin'] if states else None
    result = fate_of(slots, spin)
    result['spin_index'] = min(spin_index, max(0, len(states) - 1))
    rng = np.random.default_rng(seed)
    result['decay'] = decay_event(result, rng)
    return result
