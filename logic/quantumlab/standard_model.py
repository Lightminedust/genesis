"""Le Modèle Standard : les 17 particules élémentaires et les constantes qui servent aux calculs.

Masses, charges, spins : PDG 2024 (MeV, sauf mention). Les masses de quarks sont les masses « courantes » (schéma MS̄) ; pour les
quarks légers elles n'ont rien à voir avec la masse constituante (≈ 350 MeV) qui sert à construire un hadron, d'où deux colonnes.
"""
import math

HBAR_MEV_S = 6.582119569e-22        # ħ en MeV·s
HBAR_C = 197.3269804                # MeV·fm
ALPHA = 1 / 137.035999084
G_F = 1.1663788e-11                 # MeV⁻²
M_W = 80369.2
M_Z = 91187.6
M_H = 125200.0
LAMBDA_QCD = 200.0                  # MeV, l'échelle de l'habillage en hadrons

# |V_ij| (PDG 2022) : ligne = quark de charge +2/3 (u, c, t), colonne = quark de charge −1/3 (d, s, b)
CKM = {
    ('u', 'd'): 0.97373, ('u', 's'): 0.2243, ('u', 'b'): 0.00382,
    ('c', 'd'): 0.221, ('c', 's'): 0.975, ('c', 'b'): 0.0408,
    ('t', 'd'): 0.0086, ('t', 's'): 0.0415, ('t', 'b'): 1.014,
}

# Les 17 cases du tableau. Colonnes : id, symbole, nom, famille, génération, charge, spin, masse (MeV), couleur, force(s)
PARTICLES = [
    dict(id='u', symbol='u', name='up', family='quark', generation=1, charge=2 / 3, spin=.5, mass=2.16, colour=True,
         forces='forte, électromagnétique, faible', anti='ū'),
    dict(id='c', symbol='c', name='charm', family='quark', generation=2, charge=2 / 3, spin=.5, mass=1270.0, colour=True,
         forces='forte, électromagnétique, faible', anti='c̄'),
    dict(id='t', symbol='t', name='top', family='quark', generation=3, charge=2 / 3, spin=.5, mass=172570.0, colour=True,
         forces='forte, électromagnétique, faible', anti='t̄'),
    dict(id='d', symbol='d', name='down', family='quark', generation=1, charge=-1 / 3, spin=.5, mass=4.67, colour=True,
         forces='forte, électromagnétique, faible', anti='d̄'),
    dict(id='s', symbol='s', name='strange', family='quark', generation=2, charge=-1 / 3, spin=.5, mass=93.4, colour=True,
         forces='forte, électromagnétique, faible', anti='s̄'),
    dict(id='b', symbol='b', name='bottom', family='quark', generation=3, charge=-1 / 3, spin=.5, mass=4180.0, colour=True,
         forces='forte, électromagnétique, faible', anti='b̄'),
    dict(id='e', symbol='e', name='électron', family='lepton', generation=1, charge=-1.0, spin=.5, mass=0.51099895, colour=False,
         forces='électromagnétique, faible', anti='e⁺'),
    dict(id='mu', symbol='μ', name='muon', family='lepton', generation=2, charge=-1.0, spin=.5, mass=105.6583755, colour=False,
         forces='électromagnétique, faible', anti='μ⁺'),
    dict(id='tau', symbol='τ', name='tau', family='lepton', generation=3, charge=-1.0, spin=.5, mass=1776.86, colour=False,
         forces='électromagnétique, faible', anti='τ⁺'),
    dict(id='nu_e', symbol='νₑ', name='neutrino électronique', family='lepton', generation=1, charge=0.0, spin=.5, mass=0.0,
         colour=False, forces='faible', anti='ν̄ₑ'),
    dict(id='nu_mu', symbol='νμ', name='neutrino muonique', family='lepton', generation=2, charge=0.0, spin=.5, mass=0.0,
         colour=False, forces='faible', anti='ν̄μ'),
    dict(id='nu_tau', symbol='ντ', name='neutrino tauique', family='lepton', generation=3, charge=0.0, spin=.5, mass=0.0,
         colour=False, forces='faible', anti='ν̄τ'),
    dict(id='g', symbol='g', name='gluon', family='gauge', generation=0, charge=0.0, spin=1.0, mass=0.0, colour=True,
         forces='forte (porte la couleur : 8 états)', anti='g'),
    dict(id='gamma', symbol='γ', name='photon', family='gauge', generation=0, charge=0.0, spin=1.0, mass=0.0, colour=False,
         forces='électromagnétique (vecteur)', anti='γ'),
    dict(id='Z', symbol='Z', name='boson Z', family='gauge', generation=0, charge=0.0, spin=1.0, mass=M_Z, colour=False,
         forces='faible (courant neutre)', anti='Z'),
    dict(id='W', symbol='W', name='boson W', family='gauge', generation=0, charge=1.0, spin=1.0, mass=M_W, colour=False,
         forces='faible (courant chargé)', anti='W⁻'),
    dict(id='H', symbol='H', name='boson de Higgs', family='scalar', generation=0, charge=0.0, spin=0.0, mass=M_H, colour=False,
         forces='donne leur masse aux autres par le champ de Higgs', anti='H'),
]
BY_ID = {p['id']: p for p in PARTICLES}
QUARKS = [p['id'] for p in PARTICLES if p['family'] == 'quark']

# Masse constituante (MeV) : ce que pèse un quark dans un hadron, habillé de gluons. Deux jeux, ajustés (voir hadron_lab.py).
CONSTITUENT_BARYON = {'u': 372.683, 'd': 372.683, 's': 523.137, 'c': 1686.196, 'b': 5014.730}
CONSTITUENT_MESON = {'u': 360.555, 'd': 360.555, 's': 506.636, 'c': 1535.369, 'b': 4778.919}
HYPERFINE_BARYON = (7.00425972e6, 1.0)             # A (MeV^(1+p)·…), exposant p dans A/(m_i m_j)^p
HYPERFINE_MESON = (4.74328009e9, 1.451)


def fr(v, digits=3):
    """Un nombre à la française : virgule décimale, ×10ⁿ hors de [0,01 ; 10⁶[."""
    if v == 0:
        return '0'
    a = abs(v)
    if a >= 1e6 or a < 1e-2:
        exponent = int(math.floor(math.log10(a)))
        sup = str(exponent).translate(str.maketrans('-0123456789', '⁻⁰¹²³⁴⁵⁶⁷⁸⁹'))
        text = ('%.' + str(digits - 1) + 'f') % (v / 10 ** exponent) + '·10' + sup
    elif a >= 100:
        text = '%.0f' % v
    else:
        text = ('%.' + str(digits) + 'g') % v
    return text.replace('.', ',')


def top_width():
    """Γ(t → W b) à l'ordre dominant : G_F m_t³/(8π√2) (1 − m_W²/m_t²)² (1 + 2 m_W²/m_t²)  [MeV]."""
    m = BY_ID['t']['mass']
    r = (M_W / m) ** 2
    return G_F * m ** 3 / (8 * math.pi * math.sqrt(2)) * (1 - r) ** 2 * (1 + 2 * r)


def hadronisation_time():
    """ħ/Λ_QCD : le temps qu'il faut à un quark libre pour s'habiller en hadron (s)."""
    return HBAR_MEV_S / LAMBDA_QCD
