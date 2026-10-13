"""Les hadrons et leptons connus (PDG 2024) : contenu en quarks, masse, durée de vie ou largeur, désintégrations principales.

Notation du contenu : minuscule = quark (u d s c b t), MAJUSCULE = antiquark (U D S C B T).
Chaque désintégration est un couple (rapport d'embranchement, produits). Les tests vérifient que chacune conserve la charge, le
nombre baryonique, les nombres leptoniques, et qu'elle ne change l'étrangeté / le charme / la beauté que si elle est faible.
"""

# nom : dict(content, J (spin), mass MeV, tau (s) | width (MeV), decays)
# Les noms servent d'identifiants dans les désintégrations.
HADRONS = {
    # ---- baryons J = 1/2
    'p': dict(content='uud', J=.5, mass=938.27208816, stable=True, decays=[]),
    'n': dict(content='udd', J=.5, mass=939.5654205, tau=878.4, decays=[(1.0, ['p', 'e-', 'ν̄e'])]),
    'Λ': dict(content='uds', J=.5, mass=1115.683, tau=2.617e-10, decays=[(.639, ['p', 'π-']), (.358, ['n', 'π0'])]),
    'Σ+': dict(content='uus', J=.5, mass=1189.37, tau=8.018e-11, decays=[(.5157, ['p', 'π0']), (.4831, ['n', 'π+'])]),
    'Σ0': dict(content='uds', J=.5, mass=1192.642, tau=7.4e-20, decays=[(1.0, ['Λ', 'γ'])]),
    'Σ-': dict(content='dds', J=.5, mass=1197.449, tau=1.479e-10, decays=[(.99848, ['n', 'π-'])]),
    'Ξ0': dict(content='uss', J=.5, mass=1314.86, tau=2.90e-10, decays=[(.9952, ['Λ', 'π0'])]),
    'Ξ-': dict(content='dss', J=.5, mass=1321.71, tau=1.639e-10, decays=[(.99887, ['Λ', 'π-'])]),
    'Λc+': dict(content='udc', J=.5, mass=2286.46, tau=2.029e-13, decays=[]),
    'Σc++': dict(content='uuc', J=.5, mass=2453.97, width=1.89, decays=[(1.0, ['Λc+', 'π+'])]),
    'Ξc+': dict(content='usc', J=.5, mass=2467.71, tau=4.53e-13, decays=[]),
    'Ξc0': dict(content='dsc', J=.5, mass=2470.44, tau=1.50e-13, decays=[]),
    'Ωc0': dict(content='ssc', J=.5, mass=2695.2, tau=2.68e-13, decays=[]),
    'Λb0': dict(content='udb', J=.5, mass=5619.60, tau=1.468e-12, decays=[]),
    # ---- baryons J = 3/2
    'Δ++': dict(content='uuu', J=1.5, mass=1232.0, width=117.0, decays=[(1.0, ['p', 'π+'])]),
    'Δ+': dict(content='uud', J=1.5, mass=1232.0, width=117.0, decays=[(.667, ['p', 'π0']), (.333, ['n', 'π+'])]),
    'Δ0': dict(content='udd', J=1.5, mass=1232.0, width=117.0, decays=[(.667, ['n', 'π0']), (.333, ['p', 'π-'])]),
    'Δ-': dict(content='ddd', J=1.5, mass=1232.0, width=117.0, decays=[(1.0, ['n', 'π-'])]),
    'Σ*+': dict(content='uus', J=1.5, mass=1382.8, width=36.0, decays=[(.87, ['Λ', 'π+']), (.117, ['Σ+', 'π0'])]),
    'Σ*0': dict(content='uds', J=1.5, mass=1383.7, width=36.0, decays=[(.87, ['Λ', 'π0']), (.117, ['Σ+', 'π-'])]),
    'Ξ*0': dict(content='uss', J=1.5, mass=1531.8, width=9.1, decays=[(1.0, ['Ξ-', 'π+'])]),
    'Ω-': dict(content='sss', J=1.5, mass=1672.45, tau=8.21e-11, decays=[(.678, ['Λ', 'K-']), (.236, ['Ξ0', 'π-']), (.086, ['Ξ-', 'π0'])]),
    # ---- mésons J = 0
    'π+': dict(content='uD', J=0, mass=139.57039, tau=2.6033e-8, decays=[(.99988, ['μ+', 'νμ'])]),
    'π-': dict(content='dU', J=0, mass=139.57039, tau=2.6033e-8, decays=[(.99988, ['μ-', 'ν̄μ'])]),
    'π0': dict(content='uU', J=0, mass=134.9768, tau=8.43e-17, decays=[(.98823, ['γ', 'γ'])]),
    'K+': dict(content='uS', J=0, mass=493.677, tau=1.238e-8, decays=[(.6356, ['μ+', 'νμ']), (.2067, ['π+', 'π0'])]),
    'K-': dict(content='sU', J=0, mass=493.677, tau=1.238e-8, decays=[(.6356, ['μ-', 'ν̄μ']), (.2067, ['π-', 'π0'])]),
    'K0': dict(content='dS', J=0, mass=497.611, tau=8.954e-11, decays=[(.692, ['π+', 'π-']), (.3069, ['π0', 'π0'])]),
    'K̄0': dict(content='sD', J=0, mass=497.611, tau=8.954e-11, decays=[(.692, ['π+', 'π-']), (.3069, ['π0', 'π0'])]),
    'η': dict(content='uU', J=0, mass=547.862, width=1.31e-3, decays=[(.3936, ['γ', 'γ'])]),
    'D0': dict(content='cU', J=0, mass=1864.84, tau=4.103e-13, decays=[]),
    'D+': dict(content='cD', J=0, mass=1869.66, tau=1.033e-12, decays=[]),
    'Ds+': dict(content='cS', J=0, mass=1968.35, tau=5.04e-13, decays=[]),
    'ηc': dict(content='cC', J=0, mass=2983.9, width=32.0, decays=[]),
    'B+': dict(content='uB', J=0, mass=5279.34, tau=1.638e-12, decays=[]),
    'B0': dict(content='dB', J=0, mass=5279.66, tau=1.517e-12, decays=[]),
    'Bs0': dict(content='sB', J=0, mass=5366.92, tau=1.520e-12, decays=[]),
    'Bc+': dict(content='cB', J=0, mass=6274.47, tau=5.1e-13, decays=[]),
    # ---- mésons J = 1
    'ρ0': dict(content='uU', J=1, mass=775.26, width=149.1, decays=[(1.0, ['π+', 'π-'])]),
    'ρ+': dict(content='uD', J=1, mass=775.11, width=149.1, decays=[(1.0, ['π+', 'π0'])]),
    'ω': dict(content='uU', J=1, mass=782.66, width=8.68, decays=[(.892, ['π+', 'π-', 'π0']), (.0834, ['π0', 'γ'])]),
    'K*+': dict(content='uS', J=1, mass=891.67, width=51.4, decays=[(.667, ['K0', 'π+']), (.333, ['K+', 'π0'])]),
    'K*0': dict(content='dS', J=1, mass=895.55, width=47.3, decays=[(.667, ['K+', 'π-']), (.333, ['K0', 'π0'])]),
    'φ': dict(content='sS', J=1, mass=1019.461, width=4.249, decays=[(.492, ['K+', 'K-']), (.34, ['K0', 'K̄0'])]),
    'D*+': dict(content='cD', J=1, mass=2010.26, width=.0834, decays=[(.677, ['D0', 'π+']), (.307, ['D+', 'π0'])]),
    'Ds*+': dict(content='cS', J=1, mass=2112.2, width=0.0, decays=[(.936, ['Ds+', 'γ'])]),
    'J/ψ': dict(content='cC', J=1, mass=3096.9, width=.0926, decays=[]),
    'Υ': dict(content='bB', J=1, mass=9460.3, width=.05402, decays=[]),
}
# Les hadrons connus ne portent pas de nom dans leur propre table : on le recopie dans le dictionnaire.
for _name, _h in HADRONS.items():
    _h['name'] = _name

# Les particules qui ne sont pas des hadrons : (charge en e, nombres leptoniques (e, μ, τ), masse MeV)
LEPTONS = {
    'e-': (-1, (1, 0, 0), .51099895), 'e+': (1, (-1, 0, 0), .51099895),
    'μ-': (-1, (0, 1, 0), 105.6583755), 'μ+': (1, (0, -1, 0), 105.6583755),
    'τ-': (-1, (0, 0, 1), 1776.86), 'τ+': (1, (0, 0, -1), 1776.86),
    'νe': (0, (1, 0, 0), 0.0), 'ν̄e': (0, (-1, 0, 0), 0.0),
    'νμ': (0, (0, 1, 0), 0.0), 'ν̄μ': (0, (0, -1, 0), 0.0),
    'ντ': (0, (0, 0, 1), 0.0), 'ν̄τ': (0, (0, 0, -1), 0.0),
    'γ': (0, (0, 0, 0), 0.0),
}

QUARK_CHARGE_THIRDS = {'u': 2, 'd': -1, 's': -1, 'c': 2, 'b': -1, 't': 2}
# (étrangeté, charme, beauté, « topness ») d'un quark ; l'antiquark prend l'opposé.
QUARK_FLAVOR = {'u': (0, 0, 0, 0), 'd': (0, 0, 0, 0), 's': (-1, 0, 0, 0), 'c': (0, 1, 0, 0), 'b': (0, 0, -1, 0), 't': (0, 0, 0, 1)}


def numbers_of(content):
    """Charge (en tiers de e), nombre baryonique (en tiers), (S, C, B', T) d'un contenu en quarks."""
    charge = baryon = 0
    flavor = [0, 0, 0, 0]
    for letter in content:
        anti = letter.isupper()
        q = letter.lower()
        sign = -1 if anti else 1
        charge += sign * QUARK_CHARGE_THIRDS[q]
        baryon += sign
        for k in range(4):
            flavor[k] += sign * QUARK_FLAVOR[q][k]
    return charge, baryon, tuple(flavor)


def _build_anti():
    """Les noms des antiparticules : π⁺ ↔ π⁻, K⁰ ↔ K̄⁰, p ↔ p̄… (barre combinante sur la première lettre d'un baryon)."""
    table = {}
    for name, h in HADRONS.items():
        content = h['content']
        baryon = numbers_of(content)[1] != 0
        if baryon:
            anti = name[0] + '̄' + name[1:]
            if anti.endswith('++'):
                anti = anti[:-2] + '--'
            elif anti.endswith('+'):
                anti = anti[:-1] + '-'
            elif anti.endswith('-'):
                anti = anti[:-1] + '+'
        elif name.endswith('+'):
            anti = name[:-1] + '-'
        elif name.endswith('-'):
            anti = name[:-1] + '+'
        elif name.endswith('0') and content != content.swapcase()[::-1] and set(content.lower()) != set(content.upper()) and (
                content[0].islower() != content[1].islower() and content[0].lower() != content[1].lower()):
            anti = name[0] + '̄' + name[1:]
        else:
            anti = name
        table[name] = anti
    return table


ANTI_OF = None


def anti_name(name):
    """Le nom de l'antiparticule d'un hadron (ou d'un lepton, via la table des leptons)."""
    global ANTI_OF
    if ANTI_OF is None:
        ANTI_OF = _build_anti()
        ANTI_OF.update({v: k for k, v in list(ANTI_OF.items()) if v != k})
    flips = {'e+': 'e-', 'e-': 'e+', 'μ+': 'μ-', 'μ-': 'μ+', 'τ+': 'τ-', 'τ-': 'τ+', 'νe': 'ν̄e', 'ν̄e': 'νe',
             'νμ': 'ν̄μ', 'ν̄μ': 'νμ', 'ντ': 'ν̄τ', 'ν̄τ': 'ντ', 'γ': 'γ'}
    return flips.get(name) or ANTI_OF.get(name, name)


def base_of(name):
    """(nom dans la table, est-ce l'antiparticule ?)"""
    if name in HADRONS or name in LEPTONS:
        return name, False
    anti_name('p')
    if name in ANTI_OF and ANTI_OF[name] in HADRONS:
        return ANTI_OF[name], True
    raise KeyError(name)


def quantum_numbers(name):
    """Charge (e), nombre baryonique, nombres leptoniques, (S, C, B', T), masse (MeV) d'un produit de désintégration."""
    if name in LEPTONS:
        charge, lepton, mass = LEPTONS[name]
        return dict(charge=charge, baryon=0.0, lepton=lepton, flavor=(0, 0, 0, 0), mass=mass)
    base, flipped = base_of(name)
    h = HADRONS[base]
    charge, baryon, flavor = numbers_of(h['content'])
    sign = -1 if flipped else 1
    return dict(charge=sign * charge / 3, baryon=sign * baryon / 3, lepton=(0, 0, 0), flavor=tuple(sign * f for f in flavor), mass=h['mass'])
