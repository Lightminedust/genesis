"""La mer de partons : ce qu'on voit dans un nucléon quand la résolution Q² augmente.

À basse résolution un proton est trois quarks de valence. À mesure que Q² croît, la sonde distingue des gluons (sans masse,
porteurs de couleur) et des paires quark–antiquark qui naissent d'un gluon (g → q q̄) et s'annihilent en gluon (q q̄ → g).
Ce module calcule cette image :

* les distributions x·f(x, Q²) par l'évolution DGLAP à l'ordre dominant (nf = 3, α_s à une boucle, Λ = 0,204 GeV) à partir d'un
  état d'entrée de valence + gluon à Q₀² = 0,26 GeV² ; la mer est *engendrée* par g → q q̄, rien n'y est écrit à la main ;
* les règles de somme (impulsion totale = 1, 2 u et 1 d de valence), vérifiées par les tests ;
* des populations de partons tirées dans ces distributions, puis une dynamique de fluctuations (gluon ↔ paire, gluon ↔ 2 gluons,
  quark ↔ quark + gluon) qui conserve à chaque événement l'impulsion, la couleur, la saveur et la charge.

Limites : ordre dominant ; mer d'entrée nulle (type GRV) ; positions des partons illustratives (les PDF ne donnent ni position ni
transverse : il faudrait des GPD) ; taux de fusion fixés par l'équilibre détaillé avec les populations des PDF.
"""
import math

import numpy as np

HBAR_C = 197.327            # MeV·fm
LAMBDA = 0.204              # GeV, Λ_QCD (LO, nf = 3), valeur de type GRV
NF = 3
Q0_SQ = 0.26                # GeV², échelle d'entrée
CF, CA, TR = 4 / 3, 3.0, 0.5
X_MIN_GRID = 1e-5
SPECIES = ('u', 'ubar', 'd', 'dbar', 's', 'sbar', 'g')
CHARGE = {'u': 2 / 3, 'd': -1 / 3, 's': -1 / 3}

_N_LOG, _N_LIN, _X_JOIN = 90, 90, 0.1


def alpha_s(q2):
    """α_s(Q²) à une boucle : 4π / (β₀ ln(Q²/Λ²)), β₀ = 11 − 2nf/3."""
    return 4 * math.pi / ((11 - 2 * NF / 3) * math.log(max(q2, 1.05 * LAMBDA ** 2) / LAMBDA ** 2))


def make_grid():
    low = np.logspace(math.log10(X_MIN_GRID), math.log10(_X_JOIN), _N_LOG, endpoint=False)
    high = np.linspace(_X_JOIN, 0.9995, _N_LIN)
    return np.concatenate([low, high])


GRID = make_grid()


def _weights():
    """Poids d'intégration trapézoïdaux de ∫ dx (sur la grille) : pour les sommes ∫F dx."""
    x = GRID
    w = np.zeros_like(x)
    dx = np.diff(x)
    w[:-1] += dx / 2
    w[1:] += dx / 2
    # la queue [0, x_min] : F ∝ x^0,5 environ ; sa contribution à l'impulsion est négligeable, elle est omise (et dite).
    return w


WEIGHT = _weights()


_OPERATORS = None


def _operators():
    """Les opérateurs de convolution P⊗F sur la grille, pour F = x f : (P⊗F)(x) = ∫_x^1 dz P(z) F(x/z).

    Les parties « plus » sont écrites avec soustraction explicite : le terme singulier en 1/(1−z) se compense."""
    global _OPERATORS
    if _OPERATORS is not None:
        return _OPERATORS
    x = GRID
    n = len(x)
    lx = np.log(x)
    nodes, wts = np.polynomial.legendre.leggauss(160)
    qq = np.zeros((n, n)); qg = np.zeros((n, n)); gq = np.zeros((n, n)); gg = np.zeros((n, n))
    for i in range(n):
        xi = x[i]
        if xi >= 0.99995:
            continue
        # z ∈ [xi, 1] : deux tranches (échelle log près de xi, serrée près de 1) pour capter 1/(1−z) et les petits x
        for lo, hi in ((xi, 0.5 + xi / 2), (0.5 + xi / 2, 1.0)):
            if hi <= lo:
                continue
            z = 0.5 * (hi - lo) * nodes + 0.5 * (hi + lo)
            w = 0.5 * (hi - lo) * wts
            y = xi / z
            ok = (y >= x[0]) & (y <= x[-1])
            ly = np.log(np.clip(y, x[0], x[-1]))
            j = np.clip(np.searchsorted(lx, ly) - 1, 0, n - 2)
            t = (ly - lx[j]) / (lx[j + 1] - lx[j])
            # F(y) interpolée linéairement en ln x entre les noeuds j, j+1
            def add(matrix, coef):
                c = w * coef * ok
                np.add.at(matrix[i], j, c * (1 - t))
                np.add.at(matrix[i], j + 1, c * t)
            plus = 1.0 / (1 - z)
            add(qq, CF * (1 + z ** 2) * plus)
            qq[i, i] += CF * (-2.0 * np.sum(w * plus))
            add(qg, TR * (z ** 2 + (1 - z) ** 2))
            add(gq, CF * (1 + (1 - z) ** 2) / z)
            add(gg, 2 * CA * (z * plus + (1 - z) / z + z * (1 - z)))
            gg[i, i] += 2 * CA * (-np.sum(w * plus))
        qq[i, i] += CF * (2 * math.log(1 - xi) + 1.5)
        gg[i, i] += 2 * CA * math.log(1 - xi) + (11 * CA - 4 * NF * TR) / 6
    _OPERATORS = (qq, qg, gq, gg)
    return _OPERATORS


def _input():
    """L'état à Q₀² : valence de forme x^a (1−x)^b normalisée à 2 u et 1 d ; gluon qui prend le reste de l'impulsion ; mer nulle."""
    x = GRID

    def shape(a, b, number):
        raw = x ** a * (1 - x) ** b                    # F = x f : ∫ F/x dx = nombre de quarks
        norm = np.sum(WEIGHT * raw / x)
        return number * raw / norm

    u = shape(1.0, 3.0, 2.0)
    d = shape(1.0, 4.0, 1.0)
    momentum_valence = np.sum(WEIGHT * (u + d))
    g = x ** -0.0 * (1 - x) ** 5.0 * x ** 0.5
    g *= (1 - momentum_valence) / np.sum(WEIGHT * g)
    zero = np.zeros_like(x)
    return {'u': u + zero, 'ubar': zero.copy(), 'd': d + zero, 'dbar': zero.copy(), 's': zero.copy(),
            'sbar': zero.copy(), 'g': g}


def _derivative(state, alpha, ops):
    qq, qg, gq, gg = ops
    sigma = sum(state[k] for k in SPECIES if k != 'g')
    out = {}
    for k in SPECIES:
        if k == 'g':
            out[k] = alpha / (2 * math.pi) * (gq @ sigma + gg @ state['g'])
        else:
            out[k] = alpha / (2 * math.pi) * (qq @ state[k] + qg @ state['g'])
    return out


def _step(state, h, t, ops):
    """Un pas de Runge–Kutta d'ordre 4 en t = ln Q²."""
    alpha = lambda tt: alpha_s(math.exp(tt))
    k1 = _derivative(state, alpha(t), ops)
    s2 = {k: state[k] + h / 2 * k1[k] for k in SPECIES}
    k2 = _derivative(s2, alpha(t + h / 2), ops)
    s3 = {k: state[k] + h / 2 * k2[k] for k in SPECIES}
    k3 = _derivative(s3, alpha(t + h / 2), ops)
    s4 = {k: state[k] + h * k3[k] for k in SPECIES}
    k4 = _derivative(s4, alpha(t + h), ops)
    return {k: state[k] + h / 6 * (k1[k] + 2 * k2[k] + 2 * k3[k] + k4[k]) for k in SPECIES}


_TABLE = None
Q2_TABLE = np.logspace(math.log10(Q0_SQ), 3.5, 40)


def table():
    """Les PDF à une série de Q² (40 valeurs de 0,26 à ≈ 3000 GeV²), calculées une fois."""
    global _TABLE
    if _TABLE is not None:
        return _TABLE
    ops = _operators()
    state = _input()
    out = [state]
    t = math.log(Q0_SQ)
    for q2 in Q2_TABLE[1:]:
        target = math.log(q2)
        steps = max(1, int(math.ceil((target - t) / 0.04)))
        h = (target - t) / steps
        for _ in range(steps):
            state = _step(state, h, t, ops)
            t += h
        out.append(state)
    _TABLE = out
    return out


def pdfs(q2):
    """x·f(x, Q²) pour chaque espèce, interpolées entre les Q² calculés (linéairement en ln Q²)."""
    q2 = max(Q0_SQ, min(float(Q2_TABLE[-1]), q2))
    tab = table()
    pos = np.interp(math.log(q2), np.log(Q2_TABLE), np.arange(len(Q2_TABLE)))
    i = int(min(len(Q2_TABLE) - 2, math.floor(pos)))
    f = pos - i
    return {k: (1 - f) * tab[i][k] + f * tab[i + 1][k] for k in SPECIES}


def momentum_fractions(q2):
    """La part de l'impulsion du proton portée par les quarks de valence, la mer et les gluons."""
    p = pdfs(q2)
    sea_u, sea_d, sea_s = p['ubar'], p['dbar'], p['sbar']
    u_val, d_val = p['u'] - p['ubar'], p['d'] - p['dbar']
    valence = np.sum(WEIGHT * (u_val + d_val))
    sea = np.sum(WEIGHT * (2 * (sea_u + sea_d + sea_s)))
    gluon = np.sum(WEIGHT * p['g'])
    return {'valence': float(valence), 'sea': float(sea), 'gluon': float(gluon), 'total': float(valence + sea + gluon)}


def numbers(q2, x_min):
    """Le nombre de partons de chaque espèce avec x > x_min : ∫ f dx = ∫ F/x dx."""
    p = pdfs(q2)
    mask = GRID >= x_min
    out = {}
    for k in SPECIES:
        out[k] = float(np.sum((WEIGHT * p[k] / GRID)[mask]))
    out['u_valence'] = float(np.sum((WEIGHT * (p['u'] - p['ubar']) / GRID)[mask]))
    out['d_valence'] = float(np.sum((WEIGHT * (p['d'] - p['dbar']) / GRID)[mask]))
    return out
