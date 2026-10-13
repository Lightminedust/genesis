"""Un cœur d'étoile : un plasma chaud et dense où les noyaux fusionnent.

On règle la température et la densité ; tout le reste se calcule :

- les réactions et leurs taux viennent du réseau nucléaire REACLIB
  (`pynucastro`) : chaîne p-p, cycle CNO, triple-α, combustion du carbone,
  D + T... avec l'écrantage électronique du plasma ;
- la composition évolue en intégrant dY/dt (`scipy`, solveur raide : le
  deutérium vit une seconde, l'hydrogène des milliards d'années) ;
- l'énergie de chaque réaction est Q = Δm·c² (masses mesurées), moins ce
  qu'emportent les neutrinos, qui s'échappent (valeurs moyennes mesurées,
  sinon calculées sur le spectre β) ;
- l'ionisation suit l'équation de Saha, avec l'abaissement du potentiel
  d'ionisation par les ions voisins : trop serrés, les atomes perdent leurs
  électrons (ionisation par la pression) ;
- la pression additionne les ions, les électrons (statistique de
  Fermi-Dirac : ils peuvent être dégénérés) et le rayonnement (a·T⁴/3).

Une petite fenêtre du plasma, quelques centaines de noyaux, montre les
fusions une à une : chacune est tirée au sort selon les vrais taux.
"""
import math
import threading

import numpy as np
from scipy import constants as sc
from scipy.integrate import quad, solve_ivp
from scipy.optimize import brentq

from atomic import atomic_data
from fusionlab import collider
from nuclear import nuclear_data

NUCLEI = ["n", "p", "d", "t", "he3", "he4", "li7", "be7", "b8", "c12", "c13", "n13", "n14", "n15",
          "o14", "o15", "o16", "o17", "o18", "f17", "f18", "ne20", "na23", "mg24"]

YEAR_S = 365.25 * 86400.0
MEV_J = sc.physical_constants["electron volt"][0] * 1e6
N_A = sc.N_A
ME_C2_MEV = sc.physical_constants["electron mass energy equivalent in MeV"][0]
K_B = sc.k
U_KG = sc.physical_constants["atomic mass constant"][0]
COULOMB_EV_M = sc.e / (4 * math.pi * sc.epsilon_0)          # e²/(4πε₀) en eV·m
RADIATION_A = 4 * sc.Stefan_Boltzmann / sc.c

# Énergie moyenne emportée par le neutrino (MeV), mesurée/calculée pour le Soleil
# (Bahcall, "Neutrino Astrophysics", 1989 ; Bahcall et al. 1996).
NEUTRINO_MEV = {
    "p_p_to_d_beta_pos_reaclib": 0.2668,
    "p_p_to_d_electron_capture_reaclib": 1.445,
    "He3_p_to_He4_beta_pos_reaclib": 9.628,
    "Be7_to_Li7_electron_capture_reaclib": 0.8139,
    "B8_to_He4_He4_beta_pos_reaclib": 6.735,
    "B8_to_Be8_beta_pos_reaclib": 6.735,
    "N13_to_C13_beta_pos_reaclib": 0.7063,
    "O15_to_N15_beta_pos_reaclib": 0.9964,
    "F17_to_O17_beta_pos_reaclib": 0.9977,
}

SPEEDS = [
    (1e-3, "1 milliseconde / s"),
    (1.0, "temps réel"),
    (60.0, "1 minute / s"),
    (3600.0, "1 heure / s"),
    (86400.0, "1 jour / s"),
    (YEAR_S, "1 an / s"),
    (1e3 * YEAR_S, "1 000 ans / s"),
    (1e4 * YEAR_S, "10 000 ans / s"),
    (1e5 * YEAR_S, "100 000 ans / s"),
    (1e6 * YEAR_S, "1 million d'années / s"),
    (1e8 * YEAR_S, "100 millions d'années / s"),
    (1e9 * YEAR_S, "1 milliard d'années / s"),
]

# Lieux réels : température (K), densité (g/cm³), composition (fractions de masse), vitesse.
PRESETS = [
    {"name": "Cœur du Soleil aujourd'hui", "T": 1.57e7, "rho": 150.0, "speed": 10, "size_m": 1.4e8,
     "X": {"p": 0.34, "he3": 1e-5, "he4": 0.64, "c12": 3e-5, "n14": 0.0065, "o16": 0.0080, "ne20": 0.0017},
     "about": "modèle solaire standard : 15,7 millions de K, 150 g/cm³, un tiers d'hydrogène"},
    {"name": "Soleil à sa naissance", "T": 1.36e7, "rho": 80.0, "speed": 10, "size_m": 1.4e8,
     "X": {"p": 0.71, "he3": 3e-5, "he4": 0.27, "c12": 0.0024, "n14": 0.0007, "o16": 0.0057, "ne20": 0.0013},
     "about": "il y a 4,6 milliards d'années : 71 % d'hydrogène"},
    {"name": "Étoile massive (cycle CNO)", "T": 3.5e7, "rho": 8.0, "speed": 7, "size_m": 1e9,
     "X": {"p": 0.70, "he4": 0.28, "c12": 0.0024, "n14": 0.0007, "o16": 0.0057, "ne20": 0.0013},
     "about": "20 masses solaires : plus chaud, le carbone sert de catalyseur"},
    {"name": "Cœur de géante rouge (hélium)", "T": 1.2e8, "rho": 2e4, "speed": 8, "size_m": 2e7,
     "X": {"he4": 0.98, "n14": 0.02},
     "about": "l'hydrogène épuisé, l'hélium fusionne en carbone (triple-α)"},
    {"name": "Fin d'une étoile massive (carbone)", "T": 8e8, "rho": 1.5e5, "speed": 5, "size_m": 1e8,
     "X": {"c12": 0.5, "o16": 0.5},
     "about": "800 millions de K : le carbone fusionne avec lui-même"},
    {"name": "Réacteur à fusion (D + T)", "T": 1.5e8, "rho": 4.2e-10, "speed": 1, "size_m": 2.0,
     "X": {"d": 0.4, "t": 0.6},
     "about": "un plasma de tokamak : 10²⁰ noyaux par m³, 150 millions de K"},
    {"name": "Surface du Soleil", "T": 5772.0, "rho": 2e-7, "speed": 1, "size_m": 5e5,
     "X": {"p": 0.74, "he4": 0.25, "c12": 0.0024, "n14": 0.0007, "o16": 0.0057},
     "about": "la photosphère : 5 800 K, assez froid pour que les atomes gardent leurs électrons"},
]

BOX_NUCLEI = 220
SHOWN_PER_SECOND = 1.2          # fusions montrées une à une, par seconde réelle
FUSION_THRESHOLD_K = 1e6        # en dessous, aucune fusion mesurable (REACLIB : T >= 10^7 K)
FUSION_NEUTRON_M_S = 5.2e7      # neutron de 14 MeV : il traverse un plasma transparent
THOMSON_M2 = sc.physical_constants["Thomson cross section"][0]


# ======================================================================
# Le réseau nucléaire (chargé une fois : la bibliothèque REACLIB est lourde)
# ======================================================================
_network = None
_network_lock = threading.Lock()


class Network:
    def __init__(self):
        import pynucastro as pyna
        self.pyna = pyna
        library = pyna.ReacLibLibrary().linking_nuclei(NUCLEI, with_reverse=False)
        self.collection = pyna.RateCollection(libraries=[library])
        self.nuclei = list(self.collection.get_nuclei())
        self.rates = list(self.collection.get_rates())
        index = {nucleus: i for i, nucleus in enumerate(self.nuclei)}
        count = len(self.nuclei)
        self.power = np.zeros((len(self.rates), count))      # exposant de chaque Y dans le taux
        self.net = np.zeros((len(self.rates), count))        # produits − réactifs
        self.q = np.zeros(len(self.rates))
        self.nu = np.zeros(len(self.rates))
        for j, rate in enumerate(self.rates):
            for nucleus in rate.reactants:
                self.power[j, index[nucleus]] += 1
                self.net[j, index[nucleus]] -= 1
            for nucleus in rate.products:
                self.net[j, index[nucleus]] += 1
            self.q[j] = rate.Q
            self.nu[j] = neutrino_energy(rate)
        self.z = np.array([n.Z for n in self.nuclei])
        self.a = np.array([n.A for n in self.nuclei])
        self.mass_u = np.array([collider.mass_u(int(n.Z), int(n.A)) or float(n.A) for n in self.nuclei])
        self.index = {n.raw: i for i, n in enumerate(self.nuclei)}

    def coefficients(self, rho, temperature, y):
        """k_j tel que R_j = k_j · Π Y^p : taux de la bibliothèque, écrantage compris."""
        pyna = self.pyna
        x = np.maximum(y * self.a, 1e-30)
        comp = pyna.Composition(self.nuclei)
        comp.set_array(x / x.sum())
        state = pyna.ThermoState(rho=rho, T=temperature, comp=comp)
        from pynucastro.screening import screen5
        values = self.collection.evaluate_rates(state, screen_func=screen5)
        floor = np.maximum(comp.get_molar_array(), 1e-300)
        product = np.prod(np.where(self.power > 0, floor[None, :] ** self.power, 1.0), axis=1)
        raw = np.nan_to_num(np.array([values[rate] for rate in self.rates], dtype=float),
                            nan=0.0, posinf=0.0, neginf=0.0)
        k = np.where(product > 0, raw / product, 0.0)
        return np.nan_to_num(k, nan=0.0, posinf=0.0, neginf=0.0)


def network():
    global _network
    with _network_lock:
        if _network is None:
            _network = Network()
        return _network


def warm_up():
    """À lancer au démarrage du moteur : la bibliothèque met quelques secondes à se lire."""
    threading.Thread(target=network, daemon=True).start()


def neutrino_energy(rate):
    """Énergie moyenne du neutrino d'une réaction faible (MeV) ; 0 sinon."""
    if not rate.weak:
        return 0.0
    if rate.fname in NEUTRINO_MEV:
        return NEUTRINO_MEV[rate.fname]
    if rate.weak_type == "electron_capture":
        return max(rate.Q, 0.0)                     # un seul neutrino emporte tout
    z_in = sum(n.Z for n in rate.reactants)
    z_out = sum(n.Z for n in rate.products)
    endpoint = rate.Q - 2 * ME_C2_MEV if z_out < z_in else rate.Q   # β⁺ : Q atomique − 2 mₑc²
    return beta_mean_neutrino(endpoint)


def beta_mean_neutrino(endpoint_mev):
    """Énergie moyenne du neutrino sur un spectre β permis (forme statistique)."""
    if endpoint_mev <= 0:
        return 0.0

    def shape(t):
        e = t + ME_C2_MEV
        return math.sqrt(max(e * e - ME_C2_MEV ** 2, 0.0)) * e * (endpoint_mev - t) ** 2

    total = quad(shape, 0, endpoint_mev)[0]
    carried = quad(lambda t: shape(t) * (endpoint_mev - t), 0, endpoint_mev)[0]
    return carried / total if total > 0 else 0.0


# ======================================================================
# Le plasma : ionisation, électrons, pression
# ======================================================================
def ionization(temperature, rho, number_density):
    """Équation de Saha pour chaque élément, avec abaissement du potentiel.

    number_density : {Z : noyaux par m³}. Retourne (charge moyenne par Z,
    densité d'électrons libres, abaissement en eV, rayon de la sphère ionique).
    """
    kt_ev = sc.k * temperature / sc.e
    ions = sum(number_density.values())
    sphere = (3 / (4 * math.pi * ions)) ** (1 / 3)
    lowering = COULOMB_EV_M / sphere                          # e²/(4πε₀ a), en eV
    quantum = (2 * math.pi * sc.m_e * sc.k * temperature / sc.h ** 2) ** 1.5
    stages = {}
    for z in number_density:
        energies = []
        for q in range(z):
            energy = atomic_data.ionization_energy_ev(z, q)
            energies.append(energy if energy is not None else 13.6 * (q + 1) ** 2)
        stages[z] = energies

    def mean_charges(n_e):
        charges = {}
        for z, energies in stages.items():
            log_population = [0.0]
            for q, energy in enumerate(energies):
                effective = energy - (q + 1) * lowering
                if effective <= 0:
                    step = 700.0                                   # plus d'état lié : arraché
                else:
                    step = math.log(quantum / n_e) - effective / kt_ev
                log_population.append(log_population[-1] + max(-700.0, min(700.0, step)))
            top = max(log_population)
            weights = [math.exp(v - top) for v in log_population]
            total = sum(weights)
            charges[z] = sum(q * w for q, w in enumerate(weights)) / total
        return charges

    full = sum(z * n for z, n in number_density.items())
    if full <= 0:
        return {z: 0.0 for z in number_density}, 0.0, lowering, sphere

    def balance(log_n):
        n_e = math.exp(log_n)
        return sum(n * c for (z, n), c in zip(number_density.items(), mean_charges(n_e).values())) - n_e

    low, high = math.log(full) - 80, math.log(full)
    if balance(low) <= 0:
        n_e = math.exp(low)
    elif balance(high) >= 0:
        n_e = full
    else:
        n_e = math.exp(brentq(balance, low, high, xtol=1e-6))
    return mean_charges(n_e), n_e, lowering, sphere


def electron_pressure(n_e, temperature):
    """Pression des électrons libres, statistique de Fermi-Dirac (relativiste).

    Retourne (pression en Pa, η = μ/kT : > 0 si les électrons sont dégénérés)."""
    if n_e <= 0:
        return 0.0, -math.inf
    beta = ME_C2_MEV * 1e6 * sc.e / (sc.k * temperature)        # mₑc²/kT
    compton = sc.h / (sc.m_e * sc.c)
    scale = 8 * math.pi / compton ** 3

    def occupation(x, eta):
        exponent = beta * (math.sqrt(1 + x * x) - 1) - eta
        return 0.0 if exponent > 700 else 1.0 / (math.exp(exponent) + 1.0)

    def limit(eta):
        energy = (max(eta, 0.0) + 60.0) / beta
        return math.sqrt((1 + energy) ** 2 - 1)

    def density(eta):
        return scale * quad(lambda x: x * x * occupation(x, eta), 0, limit(eta), limit=200)[0]

    classical = n_e * sc.k * temperature
    # Non dégénéré : on le sait sans intégrer (η très négatif).
    nondegenerate_eta = math.log(n_e / (2 * (2 * math.pi * sc.m_e * sc.k * temperature / sc.h ** 2) ** 1.5))
    if nondegenerate_eta < -8:
        return classical, nondegenerate_eta
    eta = brentq(lambda e: density(e) - n_e, -40.0, 1e7, xtol=1e-8, rtol=1e-10)
    pressure = scale / 3 * sc.m_e * sc.c ** 2 * quad(
        lambda x: x ** 4 / math.sqrt(1 + x * x) * occupation(x, eta), 0, limit(eta), limit=200)[0]
    return pressure, eta


# ======================================================================
# Le cœur d'étoile
# ======================================================================
class Star:
    def __init__(self, rng_seed=None, preset=0):
        self.rng = np.random.default_rng(rng_seed)
        self.net = None
        self.preset = preset
        self.temperature = PRESETS[preset]["T"]
        self.rho = PRESETS[preset]["rho"]
        self.speed_index = PRESETS[preset]["speed"]
        self.time_s = 0.0
        self.y = None
        self.initial_x = None
        self.reactions_done = None      # mol/g de chaque réaction depuis le début
        self.energy_j_kg = 0.0
        self.neutrino_j_kg = 0.0
        self.coeffs = None
        self.coeff_key = None
        self.particles = {}             # id -> indice d'espèce
        self.next_id = 0
        self.pending = []               # fusions de la fenêtre, à montrer
        self.box_counts = {}            # créés / détruits dans la fenêtre
        self.size_m = PRESETS[preset]["size_m"]
        self.budget = 1.0               # fusions qu'on peut encore montrer
        self.box_rate = 0.0             # fusions par seconde réelle dans la fenêtre
        self.hidden = 0                 # fusions survenues mais pas montrées
        self.conditions = None
        self._reset_needed = True

    # --- réglages ------------------------------------------------------------
    @property
    def ready(self):
        if self.net is None and _network is not None:
            self.net = _network
        return self.net is not None

    def set_preset(self, index):
        self.preset = max(0, min(len(PRESETS) - 1, index))
        chosen = PRESETS[self.preset]
        self.temperature, self.rho, self.speed_index = chosen["T"], chosen["rho"], chosen["speed"]
        self.size_m = chosen["size_m"]
        self._reset_needed = True

    def set_temperature(self, kelvin):
        self.temperature = min(3e9, max(1e3, kelvin))
        self.coeff_key = None
        self.conditions = None

    def set_density(self, g_cm3):
        self.rho = min(1e7, max(1e-10, g_cm3))
        self.coeff_key = None
        self.conditions = None

    def set_speed(self, index):
        self.speed_index = max(0, min(len(SPEEDS) - 1, index))

    @property
    def speed(self):
        return SPEEDS[self.speed_index][0]

    def _reset(self):
        net = self.net
        x = np.zeros(len(net.nuclei))
        for name, value in PRESETS[self.preset]["X"].items():
            x[net.index[name]] = value
        x /= x.sum()
        self.initial_x = x.copy()
        self.y = x / net.a
        self.time_s = 0.0
        self.reactions_done = np.zeros(len(net.rates))
        self.energy_j_kg = 0.0
        self.neutrino_j_kg = 0.0
        self.coeff_key = None
        self.conditions = None
        self.pending = []
        self.box_counts = {}
        self.hidden = 0
        self.seen = {i for i, x in enumerate(self.initial_x) if x > 0}
        self.firsts = []                # (espèce, indice de réaction) créées pour la première fois
        self.particles = {}
        fractions = self.y / self.y.sum()
        for _ in range(BOX_NUCLEI):
            self._add(int(self.rng.choice(len(fractions), p=fractions)))
        self._reset_needed = False

    def _add(self, species):
        self.particles[self.next_id] = species
        self.next_id += 1
        return self.next_id - 1

    # --- le temps -----------------------------------------------------------------
    def advance(self, real_dt, paused):
        if not self.ready:
            return []
        if self._reset_needed:
            self._reset()
        if paused:
            return []
        dt = real_dt * self.speed
        net = self.net
        self.budget = min(2.0, self.budget + real_dt * SHOWN_PER_SECOND)
        if self.temperature < FUSION_THRESHOLD_K:
            self.coeffs = np.zeros(len(net.rates))
            self.box_rate = 0.0
            self.time_s += dt
            return []
        key = (self.temperature, self.rho, round(math.log10(max(self.y[net.index["p"]], 1e-30)), 1),
               round(math.log10(max(self.y[net.index["he4"]], 1e-30)), 1))
        if key != self.coeff_key:
            self.coeffs = net.coefficients(self.rho, self.temperature, self.y)
            self.coeff_key = key
        k = self.coeffs
        count = len(self.y)
        # Un plasma transparent (réacteur) laisse filer ses neutrons vers la paroi.
        escape = np.zeros(count)
        escape[net.index["n"]] = FUSION_NEUTRON_M_S / self.size_m if not self.opaque() else 0.0

        def rhs(_t, state):
            y = np.maximum(state[:count], 0.0)
            r = k * np.prod(np.where(net.power > 0, y[None, :] ** net.power, 1.0), axis=1)
            return np.concatenate([net.net.T @ r - escape * y, r])

        def jacobian(_t, state):
            y = np.maximum(state[:count], 0.0)
            factors = np.where(net.power > 0, y[None, :] ** net.power, 1.0)
            d_rate = np.zeros((len(k), count))
            for m in range(count):
                powers = net.power[:, m]
                if not powers.any():
                    continue
                others = factors.copy()
                others[:, m] = 1.0
                d_rate[:, m] = k * np.prod(others, axis=1) * np.where(
                    powers > 0, powers * y[m] ** np.maximum(powers - 1, 0), 0.0)
            full = np.zeros((count + len(k), count + len(k)))
            full[:count, :count] = net.net.T @ d_rate - np.diag(escape)
            full[count:, :count] = d_rate
            return full

        start = np.concatenate([self.y, np.zeros(len(k))])
        # Les compteurs de réactions suivent la trajectoire : on ne contrôle l'erreur que sur Y.
        solution = solve_ivp(rhs, (0.0, dt), start, method="BDF", jac=jacobian, rtol=1e-6,
                             atol=np.concatenate([np.full(count, 1e-18), np.full(len(k), 1e300)]))
        end = solution.y[:, -1]
        self.y = np.maximum(end[:count], 0.0)
        done = np.maximum(end[count:], 0.0)
        self.reactions_done += done
        self.energy_j_kg += float(np.sum(done * (net.q - net.nu))) * N_A * 1e3 * MEV_J
        self.neutrino_j_kg += float(np.sum(done * net.nu)) * N_A * 1e3 * MEV_J
        self.time_s += dt
        self._box(done, real_dt)
        return []

    def rates_now(self):
        """Taux de chaque réaction maintenant (mol/g/s)."""
        y = self.y
        return self.coeffs * np.prod(np.where(self.net.power > 0, y[None, :] ** self.net.power, 1.0), axis=1)

    # --- la fenêtre : les fusions une à une ---------------------------------------
    def _box(self, done, real_dt):
        """Tire les réactions qui ont lieu dans la fenêtre pendant ce pas.

        Chaque réaction se produit dans la fenêtre au prorata de ses noyaux :
        N_fenêtre x (réactions par noyau). Trop nombreuses, on n'en montre que
        quelques-unes par seconde, et on dit combien il y en a eu."""
        total = self.y.sum()
        if total <= 0:
            return
        expected = len(self.particles) * done / total
        self.box_rate = 0.8 * self.box_rate + 0.2 * float(expected.sum()) / max(real_dt, 1e-6)
        drawn = self.rng.poisson(np.minimum(expected, 1e9))
        applied = 0
        for j in self.rng.permutation(len(drawn)):
            for _ in range(int(drawn[j])):
                if self.budget >= 1.0:
                    self.budget -= 1.0
                    self._react(j)
                else:
                    self.hidden += 1
                    if applied < 40:            # la fenêtre change ; au-delà, on compte seulement
                        self._react(j, silent=True)
                applied += 1
        self._refill()

    def _refill(self):
        """La fenêtre est un petit morceau de l'étoile : la matière autour la renouvelle."""
        fractions = self.y / self.y.sum()
        while len(self.particles) < BOX_NUCLEI * 0.85:
            self._add(int(self.rng.choice(len(fractions), p=fractions)))
        while len(self.particles) > BOX_NUCLEI * 1.2:
            self.particles.pop(next(iter(self.particles)))

    def _react(self, j, silent=False):
        net = self.net
        rate = net.rates[j]
        consumed = []
        arrived = []
        for nucleus in rate.reactants:
            species = net.index[nucleus.raw]
            candidates = [pid for pid, s in self.particles.items() if s == species and pid not in consumed]
            if candidates:
                consumed.append(int(self.rng.choice(candidates)))
            else:
                # Rare ici : il arrive de l'étoile autour de la fenêtre.
                pid = self._add(species)
                arrived.append(pid)
                consumed.append(pid)
        for pid in consumed:
            species = self.particles.pop(pid)
            self.box_counts.setdefault(species, [0, 0])[1] += 1
        created = []
        for nucleus in rate.products:
            species = net.index[nucleus.raw]
            created.append(self._add(species))
            if species not in self.seen:
                self.seen.add(species)
                self.firsts.append((species, j))
            self.box_counts.setdefault(species, [0, 0])[0] += 1
        if not silent:
            self.pending.append({"rate": j, "consumed": consumed, "arrived": arrived, "created": created,
                                 "q": float(net.q[j]), "nu": float(net.nu[j])})

    def take_events(self):
        events, self.pending = self.pending, []
        return events

    # --- ce qu'on mesure -----------------------------------------------------------------
    def thermo(self):
        """Ionisation, électrons, pression : recalculés quand les conditions changent."""
        net = self.net
        key = (self.temperature, self.rho, tuple(np.round(self.y * 1e4, 3)))
        if self.conditions is not None and self.conditions["key"][:2] == key[:2] and \
                np.allclose(self.conditions["y"], self.y, rtol=0.02, atol=1e-8):
            return self.conditions
        rho_si = self.rho * 1e3
        per_m3 = self.y * rho_si / 1e-3 * N_A                    # Y en mol/g → noyaux/m³
        by_z = {}
        for i, z in enumerate(net.z):
            if z > 0 and per_m3[i] > 0:
                by_z[int(z)] = by_z.get(int(z), 0.0) + per_m3[i]
        charges, n_e, lowering, sphere = ionization(self.temperature, rho_si, by_z)
        ions = float(per_m3.sum())
        p_ion = ions * K_B * self.temperature
        p_e, eta = electron_pressure(n_e, self.temperature)
        # Le rayonnement ne pousse que s'il est piégé : plasma opaque (étoile), pas transparent (réacteur).
        depth = n_e * THOMSON_M2 * self.size_m
        p_rad = RADIATION_A * self.temperature ** 4 / 3 if depth > 1 else 0.0
        full = sum(z * n for z, n in by_z.items())
        self.conditions = {
            "key": key, "y": self.y.copy(), "charges": charges, "n_e": n_e, "lowering_ev": lowering,
            "spacing_m": sphere, "ions_m3": ions, "p_ion": p_ion, "p_e": p_e, "p_rad": p_rad,
            "pressure": p_ion + p_e + p_rad, "eta": eta, "ionized": n_e / full if full > 0 else 0.0,
            "optical_depth": depth,
        }
        return self.conditions

    def opaque(self):
        return self.thermo()["optical_depth"] > 1

    def power_w_kg(self, rates=None):
        rates = self.rates_now() if rates is None else rates
        return float(np.sum(rates * (self.net.q - self.net.nu))) * N_A * 1e3 * MEV_J

    def neutrino_w_kg(self, rates=None):
        rates = self.rates_now() if rates is None else rates
        return float(np.sum(rates * self.net.nu)) * N_A * 1e3 * MEV_J

    def mass_fractions(self):
        return self.y * self.net.a

    def created_destroyed_per_kg(self):
        """Noyaux créés et détruits par kg depuis le début, pour chaque espèce."""
        net = self.net
        gained = np.clip(net.net, 0, None).T @ self.reactions_done
        lost = np.clip(-net.net, 0, None).T @ self.reactions_done
        return gained * N_A * 1e3, lost * N_A * 1e3

    def thermal_speed_m_s(self, mass_u=1.0):
        return math.sqrt(3 * K_B * self.temperature / (mass_u * U_KG))

    def chain_of(self, rate):
        """La famille de la réaction : chaîne p-p, cycle CNO, triple-α..."""
        reactants = [n.raw for n in rate.reactants]
        names = set(reactants) | {n.raw for n in rate.products}
        cno = {"c12", "c13", "n13", "n14", "n15", "o14", "o15", "o16", "o17", "o18", "f17", "f18"}
        if "t" in reactants or ("d" in reactants and set(reactants) <= {"d", "he3", "n", "t"}):
            return "fusion D-T / D-D"
        if reactants == ["he4", "he4", "he4"]:
            return "triple-α"
        if reactants.count("c12") == 2 or names & {"ne20", "na23", "mg24"}:
            return "combustion du carbone"
        if "he4" in reactants and set(reactants) & cno:
            return "capture α"
        if "n" in reactants:
            return "capture de neutron"
        if names & cno:
            return "cycle CNO"
        return "chaîne p-p"

    def main_reaction(self, rates):
        """L'étape qui consomme le combustible : c'est elle qui donne le rythme."""
        net = self.net
        fuel, _ = self.fuel_left_s(rates)
        use = -np.minimum(net.net[:, net.index[fuel]], 0) * rates
        if not np.any(use > 0):
            return int(np.argmax(rates * net.q))
        # Parmi celles qui consomment le plus (à 10 % près), la première de la chaîne : la plus légère.
        strong = np.where(use >= 0.5 * use.max())[0]
        return int(min(strong, key=lambda j: sum(n.A for n in net.rates[j].reactants)))

    def gamow(self, rate):
        """Barrière et pic de Gamow d'une réaction à deux noyaux chargés (keV)."""
        charged = [n for n in rate.reactants if n.Z > 0]
        if len(charged) < 2:
            return None
        a, b = charged[0], charged[1]
        z1, z2 = int(a.Z), int(b.Z)
        m1, m2 = a.A, b.A
        mu_mev = m1 * m2 / (m1 + m2) * 931.494
        kt_kev = K_B * self.temperature / sc.e / 1e3
        barrier = 1.44 * z1 * z2 / (1.2 * (m1 ** (1 / 3) + m2 ** (1 / 3))) * 1e3
        peak = (math.pi * sc.alpha * z1 * z2 * kt_kev * math.sqrt(mu_mev * 1e3 / 2)) ** (2 / 3)
        return {"barrier_kev": barrier, "peak_kev": peak, "kt_kev": kt_kev}

    def proton_wait_s(self, rates):
        """Temps moyen avant qu'un proton fusionne (chaîne p-p ou CNO)."""
        net = self.net
        yp = self.y[net.index["p"]]
        if yp <= 0:
            return math.inf
        use = -np.minimum(net.net[:, net.index["p"]], 0) @ rates
        return yp / use if use > 0 else math.inf

    def fuel_left_s(self, rates):
        """Temps avant l'épuisement du combustible principal, au rythme actuel."""
        net = self.net
        # Le combustible : l'espèce abondante dont la masse fond le plus vite.
        mass_loss = -(net.net.T @ rates) * net.a
        mass_loss[self.mass_fractions() < 1e-3] = 0.0
        if not np.any(mass_loss > 0):
            x = self.mass_fractions()
            return net.nuclei[int(np.argmax(x))].raw, math.inf
        i = int(np.argmax(mass_loss))
        loss = -(net.net.T @ rates)[i]
        return net.nuclei[i].raw, self.y[i] / loss


def label_of(nucleus):
    return "n" if nucleus.Z == 0 else collider.label(int(nucleus.Z), int(nucleus.A))


def name_of(nucleus):
    return collider.name(int(nucleus.Z), int(nucleus.A))


def spin_of(nucleus):
    return nuclear_data.spin_parity(int(nucleus.Z), int(nucleus.A))


# ======================================================================
# Ce qui se passe, en mots
# ======================================================================
def nice(value, unit=""):
    """Un nombre lisible : 3 chiffres significatifs, exposant en toutes lettres."""
    if value == 0 or not math.isfinite(value):
        return f"0 {unit}".strip() if value == 0 else "∞"
    exponent = math.floor(math.log10(abs(value)))
    if -3 <= exponent < 6:
        text = f"{value:.3g}" if abs(value) < 1000 else f"{round(value):,}".replace(",", " ")
        return f"{text.replace('.', ',')} {unit}".strip()
    return f"{collider._power_of_ten(abs(value))} {unit}".strip() if value > 0 else \
        f"−{collider._power_of_ten(abs(value))} {unit}".strip()


def reaction_label(rate):
    """« p + p → D + e⁺ + ν » à partir des noyaux du réseau."""
    def side(nuclei):
        counts = {}
        for nucleus in nuclei:
            counts[label_of(nucleus)] = counts.get(label_of(nucleus), 0) + 1
        return " + ".join(f"{n} {name}" if n > 1 else name for name, n in counts.items())

    left, right = side(rate.reactants), side(rate.products)
    if rate.weak:
        z_in = sum(n.Z for n in rate.reactants)
        z_out = sum(n.Z for n in rate.products)
        if rate.weak_type == "electron_capture":
            left += " + e⁻"
            right += " + ν"
        elif z_out < z_in:
            right += " + e⁺ + ν"
        else:
            right += " + e⁻ + ν̄"
    elif len(rate.products) == 1:
        right += " + γ"
    return f"{left} → {right}"


def story(s, rates):
    """L'interprétation : l'état de la matière, la fusion, l'énergie, le temps."""
    from common.units import format_duration
    c = s.thermo()
    lines = []
    kt_ev = K_B * s.temperature / sc.e
    spacing_pm = c["spacing_m"] * 1e12
    if c["ionized"] > 0.99:
        agitation = nice(kt_ev / 1e3, "keV") if kt_ev > 1e3 else nice(kt_ev, "eV")
        why = (f"agitation {agitation} ≫ 13,6 eV qui retiennent l'électron de l'hydrogène"
               if kt_ev > 13.6 else "")
        if c["lowering_ev"] > 13.6:
            squeeze = (f"noyaux distants de {nice(spacing_pm, 'pm')}, moins que la taille d'un atome "
                       f"(53 pm) : aucun électron ne peut rester lié")
            why = f"{why} ; {squeeze}" if why else squeeze
        lines.append(f"PLASMA — les atomes n'existent plus : électrons et noyaux séparés ({why}).")
    elif c["ionized"] < 0.01:
        lines.append(f"GAZ D'ATOMES — à {nice(s.temperature, 'K')}, l'agitation ({nice(kt_ev, 'eV')}) "
                     f"ne suffit pas à arracher les électrons : les atomes sont entiers, et neutres.")
    else:
        lines.append(f"GAZ PARTIELLEMENT IONISÉ — {nice(100 * c['ionized'], '%')} des électrons sont "
                     f"libres (équation de Saha à {nice(s.temperature, 'K')}).")

    if s.temperature < FUSION_THRESHOLD_K or not np.any(rates > 0):
        lines.append("PAS DE FUSION — tous les noyaux sont chargés + : ils se repoussent, et à cette "
                     "température aucun ne s'approche assez pour que la force forte agisse.")
    else:
        net = s.net
        main = s.main_reaction(rates)
        gamow = s.gamow(net.rates[main])
        text = f"FUSION — réaction principale : {reaction_label(net.rates[main])} ({s.chain_of(net.rates[main])})."
        if gamow:
            text += (f" Barrière électrique {nice(gamow['barrier_kev'], 'keV')}, agitation moyenne "
                     f"{nice(1.5 * gamow['kt_kev'], 'keV')} : classiquement rien ne passerait. "
                     f"L'effet tunnel laisse passer les noyaux les plus rapides, vers "
                     f"{nice(gamow['peak_kev'], 'keV')} (pic de Gamow).")
        lines.append(text)
        if net.rates[main].fname.startswith("p_p"):
            lines.append("INTERACTION FAIBLE — dans p + p → D, un proton doit devenir neutron pendant "
                         "le bref instant du contact : seule l'interaction faible le peut, d'où la lenteur. "
                         "Le deutéron naît spins parallèles (spin 1) ; le positon e⁺ s'annihile aussitôt "
                         "avec un électron (2 photons γ).")
        elif net.rates[main].fname.startswith("t_d"):
            lines.append("NEUTRON — 80 % de l'énergie part avec le neutron (14,1 MeV) : sans charge, aucun "
                         "champ ne le retient ; il traverse le plasma et chauffe la paroi.")

    p_w = s.power_w_kg(rates)
    if p_w > 0:
        text = f"ÉNERGIE — chaque kilo dégage {nice(p_w, 'W')}"
        if p_w < 1.1:
            text += (", moins qu'un corps humain (≈ 1 W/kg) ! Une étoile brille par sa masse immense, "
                     "pas par l'ardeur de chaque kilo")
        nu = s.neutrino_w_kg(rates)
        if nu > 1e-3 * p_w:
            text += f". Les neutrinos emportent {nice(100 * nu / (nu + p_w), '%')} de l'énergie et s'échappent"
        lines.append(text + ".")

    wait = s.proton_wait_s(rates)
    fuel, left = s.fuel_left_s(rates)
    if math.isfinite(wait) and wait > 0 and s.temperature >= FUSION_THRESHOLD_K and \
            s.mass_fractions()[s.net.index["p"]] > 0.01:
        lines.append(f"TEMPS — un proton attend en moyenne {format_duration(wait)} avant de fusionner.")
    if math.isfinite(left) and left > 0:
        names = {"p": "hydrogène", "d": "deutérium", "he4": "hélium", "c12": "carbone"}
        lines.append(f"Au rythme actuel, le combustible ({names.get(fuel, fuel)}) serait épuisé en "
                     f"{format_duration(left)}.")

    atm = c["pressure"] / 101325
    text = f"PRESSION — {nice(c['pressure'], 'Pa')}, soit {nice(atm)} fois l'air que tu respires"
    parts = {"ions": c["p_ion"], "électrons": c["p_e"], "lumière": c["p_rad"]}
    top = max(parts, key=parts.get)
    text += f" ; ce sont surtout les {top} qui poussent" if top != "lumière" else " ; la lumière elle-même pousse"
    if c["eta"] > 0:
        text += (". Électrons DÉGÉNÉRÉS : si serrés que le principe de Pauli (spin ½, deux par état) les "
                 "force à bouger même sans chaleur")
    if c["optical_depth"] <= 1:
        text += ". Plasma transparent : la lumière s'échappe au lieu de pousser"
    lines.append(text + ".")
    return lines
