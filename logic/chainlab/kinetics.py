"""La population de neutrons dans le temps : cinétique ponctuelle, neutrons retardés et excursion.

Sans contre-réaction, la population suit les équations de la cinétique ponctuelle :
    dn/dt = (ρ − β)/Λ · n + Σ λ_i C_i        dC_i/dt = β_i/Λ · n − λ_i C_i
avec ρ = (k − 1)/k, Λ le temps de génération des neutrons prompts, β_i et λ_i les rendements et constantes des
précurseurs de neutrons retardés (ENDF/B-VIII.0). Pour k < 1 + β, ce sont les neutrons retardés qui fixent la
période (secondes) ; au-delà (« critique prompt »), N(t) ≈ N₀·e^((k(1−β)−1)t/Λ), en nanosecondes.

L'excursion ajoute la contre-réaction. L'énergie des fissions chauffe le cœur (capacité thermique, chaleurs
latentes mesurées) ; le métal se dilate (coefficient mesuré) tant qu'il est condensé, et une fois vaporisé le gaz
pousse (gaz parfait monoatomique + pression de rayonnement aT⁴/3) : la sphère s'étend de façon homologue,
R̈ = 5p/(ρR). k(R) vient du transport recalculé à chaque rayon, densité ρ₀/s³. Limites : inertie du solide (ondes
de contrainte) et ionisation négligées, réflecteur immobile, combustible non appauvri dans le calcul de k.
"""

import math

import numpy as np
from scipy.integrate import solve_ivp
from scipy.optimize import brentq

BOLTZMANN = 1.380649e-23
RADIATION_A = 7.565733e-16          # J m⁻³ K⁻⁴
ATOMIC_MASS_KG = 1.66053906660e-27
MEV_J = 1.602176634e-13
ROOM_K = 293.15


def inhour(reactivity, generation_time, betas, lambdas):
    """La racine dominante ω de l'équation de Nordheim : ρ = ωΛ + Σ β_i ω/(ω + λ_i). Période = 1/ω."""
    lambdas = np.asarray(lambdas, float)
    betas = np.asarray(betas, float)

    def f(w):
        return w * generation_time + np.sum(betas * w / (w + lambdas)) - reactivity

    low = -lambdas.min() * (1 - 1e-12) if len(lambdas) else -1e12
    if f(0.0) == 0:
        return 0.0
    if reactivity > 0:
        high = 1.0
        while f(high) < 0:
            high *= 10
        return brentq(f, 0.0, high, xtol=1e-14, rtol=1e-12)
    return brentq(f, low, 0.0, xtol=1e-14, rtol=1e-12)


class Caloric:
    """L'énergie d'un kilogramme de métal en fonction de sa température : solide, fusion, liquide, ébullition, gaz."""

    def __init__(self, element, mean_mass_u):
        self.atom_kg = mean_mass_u * ATOMIC_MASS_KG
        per_mol = 1000.0 / mean_mass_u                                   # moles par kg
        dulong_petit = 3 * 8.314462618 * per_mol
        measured = element.get("specific_heat")
        self.heat_capacity = measured * 1000.0 if measured else dulong_petit   # J/(kg·K)
        self.heat_source = "mesurée (mendeleev)" if measured else "Dulong-Petit (3R), faute de mesure"
        self.melting = element.get("melting_k") or 1400.0
        self.boiling = element.get("boiling_k") or 4000.0
        self.fusion = (element.get("fusion_heat") or 0.0) * 1000.0 * per_mol
        self.vaporization = (element.get("evaporation_heat") or 0.0) * 1000.0 * per_mol
        c = self.heat_capacity
        self.e_melt = c * (self.melting - ROOM_K)
        self.e_liquid = self.e_melt + self.fusion
        self.e_boil = self.e_liquid + c * (self.boiling - self.melting)
        self.e_gas = self.e_boil + self.vaporization

    def temperature(self, e, density):
        """T (K) et phase pour une énergie massique e (J/kg) au-dessus de la température ambiante."""
        c = self.heat_capacity
        if e < self.e_melt:
            return ROOM_K + e / c, "solide"
        if e < self.e_liquid:
            return self.melting, "en fusion"
        if e < self.e_boil:
            return self.melting + (e - self.e_liquid) / c, "liquide"
        if e < self.e_gas:
            return self.boiling, "ébullition"
        extra = e - self.e_gas
        gas = 1.5 * BOLTZMANN / self.atom_kg
        rho = density * 1000.0
        t = self.boiling + extra / gas
        for _ in range(60):   # gaz + rayonnement : Newton sur 3/2 k (T − T_b)/m + a (T⁴ − T_b⁴)/ρ = extra
            g = gas * (t - self.boiling) + RADIATION_A * (t ** 4 - self.boiling ** 4) / rho - extra
            dg = gas + 4 * RADIATION_A * t ** 3 / rho
            step = g / dg
            t = max(self.boiling, t - step)
            if abs(step) < 1e-9 * t:
                break
        return t, ("plasma" if t > 1e5 else "gaz")


def excursion(k_of_s, time_of_s, betas, lambdas, nu_bar, q_prompt_mev, mass_kg, radius_cm, density, caloric,
              expansion, n0=1.0, t_end=None):
    """Intègre la population de neutrons et la sphère qui chauffe, se dilate puis se disperse.

    k_of_s, time_of_s : k_eff et Λ (s) en fonction du facteur d'échelle s = R/R₀ ; betas, lambdas : précurseurs ;
    expansion : coefficient de dilatation linéaire (1/K), None s'il n'est pas tabulé. Rend un dict de courbes.
    """
    betas = np.asarray(betas, float)
    lambdas = np.asarray(lambdas, float)
    beta = float(betas.sum())
    m = len(lambdas)
    r0 = radius_cm / 100.0
    k0, lam0 = k_of_s(1.0), time_of_s(1.0)
    rho0 = (k0 - 1) / k0
    omega = inhour(rho0, lam0, betas, lambdas)
    if t_end is None:
        if omega > 0:
            # Après la bouffée prompte, la population peut encore vivre au rythme des neutrons retardés.
            t_end = min(1e4, max(60.0 / omega, 100.0))
        else:
            t_end = 300.0 if omega > -50 else max(40.0 * lam0 / max(1 - k0, 1e-6), 1e-6) + 200.0
    q_j = q_prompt_mev * MEV_J
    gas_start = caloric.e_gas

    def state(y):
        """Énergie interne massique, s, température, phase, densité (g/cm³)."""
        energy, s_gas, v = y[m + 1], y[m + 2], y[m + 3]
        kinetic = 0.3 * mass_kg * (r0 * v) ** 2
        e = max(energy - kinetic, 0.0) / mass_kg
        if e < gas_start:
            t, phase = caloric.temperature(e, density)
            s = 1.0 + (expansion or 0.0) * max(min(t, caloric.boiling) - ROOM_K, 0.0)
        else:
            s_condensed = 1.0 + (expansion or 0.0) * (caloric.boiling - ROOM_K)
            s = s_condensed * s_gas
            t, phase = caloric.temperature(e, density / s ** 3)
        return e, s, t, phase, density / s ** 3

    def rhs(t, y):
        ln_n = y[0]
        c = y[1:m + 1]
        e, s, temp, phase, rho = state(y)
        k = k_of_s(s)
        lam = time_of_s(s)
        reactivity = (k - 1) / k
        dln = (reactivity - beta) / lam + float(np.sum(lambdas * c))
        dc = betas / lam - lambdas * c - c * dln
        n = math.exp(min(ln_n, 700.0))
        fissions = n / (lam * nu_bar)
        dv = 0.0
        if e >= gas_start:
            pressure = rho * 1000.0 * BOLTZMANN * temp / caloric.atom_kg + RADIATION_A * temp ** 4 / 3
            dv = 5 * pressure / (rho * 1000.0 * r0 ** 2 * s)
        return np.concatenate([[dln], dc, [fissions * q_j, y[m + 3], dv]])

    y0 = np.concatenate([[math.log(n0)], np.zeros(m), [0.0, 1.0, 0.0]])
    peak = {"ln": -math.inf}

    def collapsed(t, y):
        peak["ln"] = max(peak["ln"], y[0])
        return y[0] - (peak["ln"] - 40.0) if t > 0 and peak["ln"] > y0[0] + 5 else 1.0
    collapsed.terminal = True

    def scattered(t, y):
        return y[m + 2] - 6.0
    scattered.terminal = True

    solution = solve_ivp(rhs, (0.0, t_end), y0, method="LSODA", rtol=1e-6, atol=1e-9,
                         dense_output=True, events=[collapsed, scattered], first_step=lam0 * 1e-3)
    t_last = solution.t[-1]
    t_first = min(lam0 * 0.1, t_last / 1e6) if t_last > 0 else 1e-12
    times = np.unique(np.concatenate([[0.0], np.geomspace(max(t_first, 1e-15), max(t_last, 2e-15), 240)]))
    rows = []
    for t in times:
        y = solution.sol(t)
        e, s, temp, phase, rho = state(y)
        k = k_of_s(s)
        lam = time_of_s(s)
        n = math.exp(min(y[0], 700.0))
        rows.append((t, y[0] / math.log(10), y[m + 1], temp, s, k, n / (lam * nu_bar) * q_j, phase))
    energy = solution.y[m + 1, -1]
    fissions = energy / q_j
    peak_temperature = max(r[3] for r in rows)
    final = rows[-1]
    return {"time_s": [r[0] for r in rows], "log10_neutrons": [r[1] for r in rows],
            "energy_j": [r[2] for r in rows], "temperature_k": [r[3] for r in rows],
            "radius_factor": [r[4] for r in rows], "k": [r[5] for r in rows], "power_w": [r[6] for r in rows],
            "phase": [r[7] for r in rows], "energy": energy, "fissions": fissions,
            "peak_temperature_k": peak_temperature, "final_phase": final[7], "omega": omega,
            "period_s": 1 / omega if omega else math.inf, "dispersed": bool(solution.t_events[1].size),
            "quenched": bool(solution.t_events[0].size), "t_end": t_last}
