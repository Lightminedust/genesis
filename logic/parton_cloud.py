"""Une population de partons tirée dans les PDF de `partons.py`, et ses fluctuations.

À la résolution (Q², x_min) le proton contient trois quarks de valence, des paires q q̄ de mer et des gluons, en nombres donnés
par ∫ f dx. Cette population fluctue : un gluon se scinde en paire (g → q q̄), une paire s'annihile en gluon (q q̄ → g), un gluon se
scinde en deux (g → g g, car il porte la couleur) ou un quark en émet un (q → q g) ; et les inverses. Chaque événement

* conserve l'impulsion (Σ x), la charge, le nombre de chaque saveur (q − q̄) et la couleur (somme des poids SU(3) = 0) ;
* tire la fraction d'impulsion z de la fonction de scission DGLAP du processus (P_qg, P_gg, P_gq), bornée par x_min ;
* a un taux de scission α_s/(2π) · ∫P dz / τ, τ = ħc/Q (relation d'incertitude), et un taux de fusion fixé par l'équilibre
  détaillé avec la population de départ (les populations restent donc celles des PDF).
"""
import math

import numpy as np

import partons

HBAR_C_GEV_FM = 0.1973269804
COLORS = 3
FLAVORS = ('u', 'd', 's')
BAG_RADIUS_FM = 1.08            # sphère de même rayon quadratique moyen que le proton (0,84 fm)
DURATION_TAUS = 14              # le film dure 14 τ
MAX_EVENTS = 900

_W = np.array([[.5, 1 / (2 * math.sqrt(3))], [-.5, 1 / (2 * math.sqrt(3))], [0.0, -1 / math.sqrt(3)]])
_Z, _ZW = np.polynomial.legendre.leggauss(64)


def weight(kind, color, anti):
    """Le poids de couleur SU(3) (I₃, Y) d'un parton : quark c, antiquark −c̄, gluon c − c̄'."""
    if kind == 'q':
        return _W[color]
    if kind == 'qbar':
        return -_W[anti]
    return _W[color] - _W[anti]


def _integral(f, lo, hi):
    if hi <= lo:
        return 0.0
    z = 0.5 * (hi - lo) * _Z + 0.5 * (hi + lo)
    return float(0.5 * (hi - lo) * np.sum(_ZW * f(z)))


def p_qg(z):
    return partons.TR * (z ** 2 + (1 - z) ** 2)


def p_gg(z):
    return partons.CA * (z / (1 - z) + (1 - z) / z + z * (1 - z))     # ½ · 2 C_A : deux gluons identiques


def p_gq(z):
    return partons.CF * (1 + (1 - z) ** 2) / z


def split_integral(kernel, eps):
    """∫ P(z) dz sur [ε, 1−ε] : la probabilité de scission par unité de ln Q² (× α_s/2π)."""
    return _integral(kernel, eps, 1 - eps) if eps < .5 else 0.0


def _draw_z(kernel, eps, rng):
    """Tire z dans P(z) sur [ε, 1−ε] par rejet."""
    zs = np.linspace(eps, 1 - eps, 400)
    top = float(np.max(kernel(zs))) * 1.05
    while True:
        z = rng.uniform(eps, 1 - eps)
        if rng.uniform(0, top) <= float(kernel(z)):
            return z


def _inverse_sampler(density, x_min):
    """Une fonction qui tire x ≥ x_min dans une densité n(x) = F/x (sur la grille des PDF)."""
    grid = partons.GRID
    mask = grid >= x_min
    x = grid[mask]
    weights = np.maximum(partons.WEIGHT[mask] * density[mask] / x, 0)
    total = weights.sum()
    if total <= 0 or len(x) < 2:
        return None
    cdf = np.cumsum(weights) / total
    return lambda rng: float(np.interp(rng.uniform(), cdf, x))


class Cloud:
    """Un proton ou neutron à la résolution (Q², x_min) : sa population de partons et ses fluctuations."""

    def __init__(self, hadron='proton', q2=10.0, x_min=0.01):
        if hadron not in ('proton', 'neutron'):
            raise ValueError('Le nucléon est un proton ou un neutron')
        if not partons.Q0_SQ <= q2 <= 3000:
            raise ValueError('Q² doit être compris entre %.2f et 3000 GeV²' % partons.Q0_SQ)
        if not 1e-4 <= x_min <= 0.2:
            raise ValueError('x_min doit être compris entre 1e-4 et 0,2')
        self.hadron, self.q2, self.x_min = hadron, float(q2), float(x_min)
        pdf = partons.pdfs(self.q2)
        if hadron == 'neutron':     # symétrie d'isospin
            pdf = dict(pdf, u=pdf['d'], d=pdf['u'], ubar=pdf['dbar'], dbar=pdf['ubar'])
        self.pdf = pdf
        self.alpha = partons.alpha_s(self.q2)
        self.tau = HBAR_C_GEV_FM / math.sqrt(self.q2)       # fm/c
        self.numbers = partons.numbers(self.q2, self.x_min)
        self.momentum = partons.momentum_fractions(self.q2)
        self.partons, self.events = [], []
        self.duration = DURATION_TAUS * self.tau

    # ------------------------------------------------------------------ la population de départ
    def _new(self, kind, flavor, color, anti, x, pos, t, valence=False, parents=()):
        p = dict(id=len(self.partons), kind=kind, flavor=flavor, color=color, anti=anti, x=float(x),
                 pos=np.asarray(pos, float), born=float(t), died=None, valence=valence, parents=tuple(parents))
        self.partons.append(p)
        return p['id']

    def _point(self, rng):
        while True:
            v = rng.uniform(-1, 1, 3)
            if v @ v <= 1:
                return v * BAG_RADIUS_FM

    def sample(self, seed=1):
        """Tire la population : valence (3), paires de mer par saveur, gluons ; la couleur totale est nulle par construction."""
        rng = np.random.default_rng(seed)
        self.partons, self.events = [], []
        flavors = ('u', 'u', 'd') if self.hadron == 'proton' else ('u', 'd', 'd')
        colors = list(rng.permutation(COLORS))
        while True:
            pending = []
            for flavor, color in zip(flavors, colors):
                valence = self.pdf[flavor] - self.pdf[flavor + 'bar']
                draw = _inverse_sampler(valence, self.x_min)
                pending.append(('q', flavor, int(color), -1, draw(rng) if draw else 0.3, True))
            for flavor in FLAVORS:
                count = rng.poisson(self.numbers[flavor + 'bar'] if self.hadron == 'proton' else self._number(flavor + 'bar'))
                draw = _inverse_sampler(self.pdf[flavor + 'bar'], self.x_min)
                for _ in range(count if draw else 0):
                    c = int(rng.integers(COLORS))
                    pending.append(('q', flavor, c, -1, draw(rng), False))
                    pending.append(('qbar', flavor, -1, c, draw(rng), False))
            gluons = rng.poisson(self._number('g'))
            draw = _inverse_sampler(self.pdf['g'], self.x_min)
            made = 0
            while draw and made < gluons:
                c1, c2 = int(rng.integers(COLORS)), int(rng.integers(COLORS))
                if made + 1 < gluons and c1 != c2:       # deux gluons de poids opposés
                    pending.append(('g', None, c1, c2, draw(rng), False))
                    pending.append(('g', None, c2, c1, draw(rng), False))
                    made += 2
                else:                                    # un gluon neutre (c c̄)
                    pending.append(('g', None, c1, c1, draw(rng), False))
                    made += 1
            if sum(p[4] for p in pending) <= 0.98:
                break
        for kind, flavor, color, anti, x, valence in pending:
            self._new(kind, flavor, color, anti, x, self._point(rng), 0.0, valence)
        self.seed = seed
        self._run(rng)
        return self.partons

    def _number(self, key):
        n = partons.numbers(self.q2, self.x_min)
        if self.hadron == 'neutron' and key in ('ubar', 'dbar'):
            return n['dbar' if key == 'ubar' else 'ubar']
        return n[key]

    # ------------------------------------------------------------------ l'état à un instant
    def alive(self):
        return [p for p in self.partons if p['died'] is None]

    def totals(self, ids=None):
        """Impulsion, charge, nombre de chaque saveur et poids de couleur d'un ensemble de partons (par défaut les vivants)."""
        members = self.alive() if ids is None else [self.partons[i] for i in ids]
        x = sum(p['x'] for p in members)
        charge = 0.0
        flavor = {f: 0 for f in FLAVORS}
        weight_sum = np.zeros(2)
        for p in members:
            if p['kind'] == 'g':
                weight_sum += weight('g', p['color'], p['anti'])
                continue
            sign = 1 if p['kind'] == 'q' else -1
            flavor[p['flavor']] += sign
            charge += sign * partons.CHARGE[p['flavor']]
            weight_sum += weight(p['kind'], p['color'], p['anti'])
        return dict(x=x, charge=charge, flavor=flavor, colour=weight_sum)

    # ------------------------------------------------------------------ la dynamique
    def _rates(self):
        """Taux de départ par parton (moyennes sur la population tirée) et constantes de fusion par équilibre détaillé."""
        alive = self.alive()
        gluons = [p for p in alive if p['kind'] == 'g']
        quarks = [p for p in alive if p['kind'] != 'g']
        base = self.alpha / (2 * math.pi) / self.tau
        self.base = base
        self.n_g0 = max(1, len(gluons))
        self.n_q0 = max(1, len(quarks))
        self.flavor_count = {(k, f): sum(1 for p in alive if p['kind'] == k and p['flavor'] == f)
                             for k in ('q', 'qbar') for f in FLAVORS}
        self.rate_a = lambda x: base * len(FLAVORS) * split_integral(p_qg, self.x_min / x)
        self.rate_c = lambda x: base * split_integral(p_gg, self.x_min / x)
        self.rate_e = lambda x: base * split_integral(p_gq, self.x_min / x)
        mean_a = np.mean([self.rate_a(p['x']) for p in gluons]) if gluons else 0.0
        mean_c = np.mean([self.rate_c(p['x']) for p in gluons]) if gluons else 0.0
        mean_e = np.mean([self.rate_e(p['x']) for p in quarks]) if quarks else 0.0
        total_pair = sum(self.flavor_count[('qbar', f)] for f in FLAVORS)
        self.flavor_weight = {f: (self.flavor_count[('qbar', f)] + .5) / (total_pair + 1.5) for f in FLAVORS}
        self.merge_b = {}
        for f in FLAVORS:
            nq, nqb = self.flavor_count[('q', f)], self.flavor_count[('qbar', f)]
            self.merge_b[f] = (self.n_g0 * mean_a * self.flavor_weight[f] / max(1, nq * nqb)) if nq and nqb else 0.0
        # fractions de paires compatibles en couleur, mesurées sur la population de départ (fusion g g → g et q g → q)
        chain = sum(1 for i, a in enumerate(gluons) for b in gluons[i + 1:] if a['anti'] == b['color'] or b['anti'] == a['color'])
        frac_gg = max(chain, 1) / max(1, len(gluons) * (len(gluons) - 1) / 2)
        hits = sum(1 for q in quarks for g in gluons if (g['anti'] == q['color'] if q['kind'] == 'q' else g['color'] == q['anti']))
        frac_qg = max(hits, 1) / max(1, len(quarks) * len(gluons))
        pairs = 0.5 * self.n_g0 * max(1, self.n_g0 - 1) * frac_gg
        self.merge_d = self.n_g0 * mean_c / pairs if pairs > 0 else 0.0
        self.merge_f = mean_e / (self.n_g0 * frac_qg)
        self.rate_a_max = self.rate_a(1.0) or 1e-9
        self.rate_c_max = self.rate_c(1.0) or 1e-9
        self.rate_e_max = self.rate_e(1.0) or 1e-9

    def _run(self, rng):
        self._rates()
        t = 0.0
        while len(self.events) < MAX_EVENTS:
            alive = self.alive()
            gluons = [p for p in alive if p['kind'] == 'g']
            quarks = [p for p in alive if p['kind'] == 'q']
            antis = [p for p in alive if p['kind'] == 'qbar']
            n_g, n_q, n_a = len(gluons), len(quarks), len(antis)
            by_flavor = {f: ([p for p in quarks if p['flavor'] == f], [p for p in antis if p['flavor'] == f]) for f in FLAVORS}
            candidates = {
                'g->qqbar': n_g * self.rate_a_max,
                'qqbar->g': sum(self.merge_b[f] * len(by_flavor[f][0]) * len(by_flavor[f][1]) for f in FLAVORS),
                'g->gg': n_g * self.rate_c_max,
                'gg->g': self.merge_d * n_g * max(0, n_g - 1) / 2,
                'q->qg': (n_q + n_a) * self.rate_e_max,
                'qg->q': self.merge_f * (n_q + n_a) * n_g,
            }
            total = sum(candidates.values())
            if total <= 0:
                break
            t += rng.exponential(1 / total)
            if t > self.duration:
                break
            kind = rng.choice(list(candidates), p=np.array(list(candidates.values())) / total)
            self._try(kind, t, rng, gluons, quarks, antis, by_flavor)
        self.events_end = min(t, self.duration)
        self.duration = self.events_end

    def _split_position(self, parent, rng):
        return parent['pos'] + rng.normal(0, 0.12, 3)

    def _try(self, kind, t, rng, gluons, quarks, antis, by_flavor):
        eps_of = lambda x: self.x_min / x
        if kind == 'g->qqbar':
            g = gluons[rng.integers(len(gluons))]
            if rng.uniform() > self.rate_a(g['x']) / self.rate_a_max or g['x'] < 2 * self.x_min:
                return
            f = rng.choice(FLAVORS, p=[self.flavor_weight[k] for k in FLAVORS])
            z = _draw_z(p_qg, eps_of(g['x']), rng)
            self._event(kind, t, [g['id']], [('q', f, g['color'], -1, g['x'] * z), ('qbar', f, -1, g['anti'], g['x'] * (1 - z))], g['pos'], rng)
        elif kind == 'qqbar->g':
            weights = np.array([self.merge_b[f] * len(by_flavor[f][0]) * len(by_flavor[f][1]) for f in FLAVORS])
            f = FLAVORS[int(rng.choice(3, p=weights / weights.sum()))]
            q = by_flavor[f][0][rng.integers(len(by_flavor[f][0]))]
            a = by_flavor[f][1][rng.integers(len(by_flavor[f][1]))]
            if q['x'] + a['x'] > 0.98:
                return
            pos = (q['pos'] * q['x'] + a['pos'] * a['x']) / (q['x'] + a['x'])
            self._event(kind, t, [q['id'], a['id']], [('g', None, q['color'], a['anti'], q['x'] + a['x'])], pos, rng, merge=True)
        elif kind == 'g->gg':
            g = gluons[rng.integers(len(gluons))]
            if g['x'] < 2 * self.x_min or rng.uniform() > self.rate_c(g['x']) / self.rate_c_max:
                return
            c3 = int(rng.integers(COLORS))
            z = _draw_z(p_gg, eps_of(g['x']), rng)
            self._event(kind, t, [g['id']], [('g', None, g['color'], c3, g['x'] * z), ('g', None, c3, g['anti'], g['x'] * (1 - z))], g['pos'], rng)
        elif kind == 'gg->g':
            if len(gluons) < 2:
                return
            i, j = rng.choice(len(gluons), 2, replace=False)
            a, b = gluons[i], gluons[j]
            if a['anti'] != b['color']:
                a, b = b, a
            if a['anti'] != b['color'] or a['x'] + b['x'] > 0.98:
                return
            pos = (a['pos'] * a['x'] + b['pos'] * b['x']) / (a['x'] + b['x'])
            self._event(kind, t, [a['id'], b['id']], [('g', None, a['color'], b['anti'], a['x'] + b['x'])], pos, rng, merge=True)
        elif kind == 'q->qg':
            pool = quarks + [p for p in self.alive() if p['kind'] == 'qbar']
            q = pool[rng.integers(len(pool))]
            if q['x'] < 2 * self.x_min or rng.uniform() > self.rate_e(q['x']) / self.rate_e_max:
                return
            z = _draw_z(p_gq, eps_of(q['x']), rng)           # fraction d'impulsion emportée par le gluon
            c2 = int(rng.integers(COLORS))
            if q['kind'] == 'q':                              # quark c → quark c2 + gluon (c, c̄2)
                out = [('q', q['flavor'], c2, -1, q['x'] * (1 - z)), ('g', None, q['color'], c2, q['x'] * z)]
            else:                                             # antiquark c̄ → antiquark c̄2 + gluon (c2, c̄)
                out = [('qbar', q['flavor'], -1, c2, q['x'] * (1 - z)), ('g', None, c2, q['anti'], q['x'] * z)]
            self._event(kind, t, [q['id']], out, q['pos'], rng, valence_from=q)
        elif kind == 'qg->q':
            pool = quarks + [p for p in self.alive() if p['kind'] == 'qbar']
            if not gluons or not pool:
                return
            q = pool[rng.integers(len(pool))]
            g = gluons[rng.integers(len(gluons))]
            if q['x'] + g['x'] > 0.98:
                return
            if q['kind'] == 'q':
                if g['anti'] != q['color']:
                    return
                out = [('q', q['flavor'], g['color'], -1, q['x'] + g['x'])]
            else:
                if g['color'] != q['anti']:
                    return
                out = [('qbar', q['flavor'], -1, g['anti'], q['x'] + g['x'])]
            pos = (q['pos'] * q['x'] + g['pos'] * g['x']) / (q['x'] + g['x'])
            self._event(kind, t, [q['id'], g['id']], out, pos, rng, merge=True, valence_from=q)

    def _event(self, kind, t, parents, children, pos, rng, merge=False, valence_from=None):
        before = self.totals(parents)
        for i in parents:
            self.partons[i]['died'] = t
        ids = []
        for k, (pk, flavor, color, anti, x) in enumerate(children):
            where = pos if merge else pos + rng.normal(0, 0.12, 3)
            valence = bool(valence_from and valence_from['valence'] and pk != 'g')
            ids.append(self._new(pk, flavor, color, anti, x, where, t, valence, parents))
        after = self.totals(ids)
        self.events.append(dict(time=t, kind=kind, parents=list(parents), children=ids,
                                dx=after['x'] - before['x'], dcharge=after['charge'] - before['charge'],
                                dcolour=float(np.abs(after['colour'] - before['colour']).max()),
                                dflavor=max(abs(after['flavor'][f] - before['flavor'][f]) for f in FLAVORS)))

    def summary(self):
        counts = {}
        for e in self.events:
            counts[e['kind']] = counts.get(e['kind'], 0) + 1
        return counts
