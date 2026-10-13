"""Les fiches du tableau du Modèle Standard : chaque ligne est calculée (ou donnée comme mesure), jamais décorative."""
import math

from quantumlab import hadron_lab as lab
from quantumlab import standard_model as sm


def _t(seconds):
    return sm.fr(seconds) + ' s'


def z_width():
    """Γ(Z → f f̄) = G_F M_Z³/(6π√2) · N_c (g_V² + g_A²), g_A = T₃, g_V = T₃ − 2Q sin²θ_W  (ordre dominant, sin²θ_W = 0,2312)."""
    s2 = 0.2312
    unit = sm.G_F * sm.M_Z ** 3 / (6 * math.pi * math.sqrt(2))
    total = 0.0
    parts = {}
    for name, t3, q, nc, count in (('ν', .5, 0, 1, 3), ('ℓ', -.5, -1, 1, 3), ('u', .5, 2 / 3, 3, 2), ('d', -.5, -1 / 3, 3, 3)):
        gv, ga = t3 - 2 * q * s2, t3
        w = unit * nc * (gv ** 2 + ga ** 2) * count
        parts[name] = w
        total += w
    return total, parts


def card(pid):
    p = sm.BY_ID[pid]
    lines = []
    fam = p['family']
    m = p['mass']
    if fam == 'quark':
        lines.append('charge : %s e' % ('+2/3' if p['charge'] > 0 else '−1/3'))
        lines.append('masse courante : %s MeV' % sm.fr(m, 4))
        if pid in ('u', 'd', 's', 'c', 'b'):
            lines.append('masse constituante : %.0f MeV (habillée de gluons)' % sm.CONSTITUENT_BARYON[pid])
        lines.append('couleur : rouge, vert ou bleu (jamais libre : confinement)')
        if pid in ('c', 'b'):
            d = lab.heavy_quark_decay(pid)
            lines.append('désintégration faible : τ = %s (modèle du spectateur G_F² m⁵|V|²/192π³)' % _t(d['lifetime']))
            top = max(d['channels'], key=lambda c: c['branching'])
            lines.append('voie dominante : %s (%.0f %%)' % (top['label'], 100 * top['branching']))
        elif pid == 't':
            w = sm.top_width()
            lines.append('largeur Γ(t → W b) = %s GeV' % sm.fr(w / 1000))
            lines.append('durée de vie τ = %s, %.0f fois moins que le temps d\'habillage %s : jamais de hadron' % (
                _t(sm.HBAR_MEV_S / w), sm.hadronisation_time() / (sm.HBAR_MEV_S / w), _t(sm.hadronisation_time())))
        else:
            lines.append('stable à l\'intérieur d\'un hadron (la désintégration est celle du hadron)')
    elif fam == 'lepton':
        if p['charge'] != 0:
            lines.append('charge : −1 e')
            lines.append('masse : %s MeV' % sm.fr(m, 6))
            if pid == 'e':
                lines.append('stable : le plus léger des leptons chargés')
            else:
                tau = lab.lepton_lifetime(pid)
                lines.append('durée de vie τ = %s (G_F² m⁵/192π³%s)' % (_t(tau), '' if pid == 'mu' else ' × voies'))
                lines.append('désintégration : %s → e ν ν̄' % p['symbol'] if pid == 'mu' else '%s → ℓ ν ν̄ ou hadrons' % p['symbol'])
        else:
            lines.append('charge : 0')
            lines.append('masse : < 0,8 eV (non nulle : les neutrinos oscillent)')
            lines.append('n\'interagit que par la force faible')
    elif pid == 'g':
        lines.append('masse : 0  ·  spin 1  ·  vecteur de l\'interaction forte')
        lines.append('porte une couleur ET une anticouleur : 8 états')
        lines.append('donc il interagit avec lui-même : g → g g')
        lines.append('couplage α_s(M_Z) = %s (une boucle, Λ = 0,204 GeV)' % sm.fr(__import__('importlib').import_module('quantumlab.partons').alpha_s(sm.M_Z ** 2 / 1e6)))
    elif pid == 'gamma':
        lines.append('masse : 0  ·  spin 1  ·  charge 0')
        lines.append('vecteur de l\'électromagnétisme, portée infinie')
        lines.append('α = 1/%s' % sm.fr(1 / sm.ALPHA, 6))
    elif pid == 'W':
        d = lab.w_boson_channels()
        lines.append('masse : %s GeV  ·  charge ±1' % sm.fr(m / 1000, 4))
        lines.append('largeur Γ = %s GeV (somme de ses 9 voies, ordre dominant ; mesuré 2,09)' % sm.fr(d['width'] / 1000))
        lines.append('chaque voie leptonique : %s %%  ·  hadronique : %s %%' % (
            sm.fr(100 * d['channels'][0]['branching']), sm.fr(100 * sum(c['branching'] for c in d['channels'][3:]))))
        lines.append('seul médiateur à changer la saveur : c → s, b → c…')
    elif pid == 'Z':
        total, parts = z_width()
        lines.append('masse : %s GeV  ·  charge 0' % sm.fr(m / 1000, 4))
        lines.append('largeur Γ = %s GeV (ordre dominant, sin²θ_W = 0,2312 ; mesuré 2,50)' % sm.fr(total / 1000))
        lines.append('invisible (3 ν) : %s %%' % sm.fr(100 * parts['ν'] / total))
        lines.append('médiateur du courant neutre : ne change jamais la saveur')
    elif pid == 'H':
        lines.append('masse : %s GeV  ·  spin 0 (le seul scalaire)' % sm.fr(m / 1000, 4))
        lines.append('le champ de Higgs donne leur masse aux autres')
        lines.append('couple proportionnellement à la masse : g = m/v, v = 246 GeV')
        lines.append('largeur ≈ 4 MeV (mesure ; non recalculée ici)')
    lines.append('forces : %s' % p['forces'])
    return lines


def particles():
    out = []
    for p in sm.PARTICLES:
        out.append(dict(p, lines=card(p['id'])))
    return out
