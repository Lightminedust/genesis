"""La chambre de collision : deux noyaux face à face, et ce que les règles en font.

On choisit le noyau A, le noyau B et l'énergie du choc. Le moteur applique,
dans l'ordre où la nature les rencontre :

  1. La répulsion électrique : deux noyaux chargés positivement se repoussent.
     Pour se toucher, ils doivent franchir la barrière de Coulomb
     V_C = k Z_A Z_B e² / (R_A + R_B), avec R = 1,2 A^⅓ fm. En dessous, ils
     s'arrêtent au point de rebroussement r = k Z_A Z_B e² / E et repartent.
  2. L'effet tunnel : même sous la barrière, ils ont une probabilité de la
     traverser, P = exp(−2πη), où η = Z_A Z_B e² / (4πε₀ ħ v) est le paramètre
     de Sommerfeld (facteur de Gamow). Le moteur tire au hasard, choc par choc.
  3. La force forte : au contact, elle l'emporte et forme un noyau composé
     (D + T → He-5). Sa durée de vie et ce qu'il éjecte viennent des données
     mesurées (He-5 vit 6·10⁻²² s et éjecte un neutron).
  4. E = Δm·c² : la masse des produits est plus petite que celle des noyaux
     de départ ; la différence devient l'énergie des produits, partagée selon
     la conservation de la quantité de mouvement.

Les voies de sortie des réactions bien connues sont celles qu'on mesure
(D + D : moitié T + p, moitié He-3 + n). Pour les autres, on retient la voie
qui libère le plus d'énergie, et on le dit : la structure fine des noyaux
n'est pas modélisée.
"""

import math

import numpy as np
from scipy import constants as sc

from nuclear import nuclear_data
from nuclear.nuclides import particle_bound
from common.units import format_duration

MEV_PER_U = sc.physical_constants["atomic mass constant energy equivalent in MeV"][0]
ELECTRON_MEV = sc.physical_constants["electron mass energy equivalent in MeV"][0]
COULOMB_MEV_FM = 1.43996        # e² / (4πε₀), en MeV·fm
FINE_STRUCTURE = sc.fine_structure
KEV_TO_KELVIN = sc.e * 1e3 / sc.k

# Les noyaux qu'on peut lancer : les légers, ceux qui fusionnent (Z, A).
CHOICES = [(1, 1), (1, 2), (1, 3), (2, 3), (2, 4), (3, 6), (3, 7), (4, 9), (5, 10), (5, 11),
           (6, 12), (6, 13), (7, 14), (8, 16)]

# Particules légères qu'un noyau composé peut éjecter : (nom, Z, A).
EJECTILES = [("n", 0, 1), ("p", 1, 1), ("d", 1, 2), ("t", 1, 3), ("He-3", 2, 3), ("α", 2, 4)]

# Voies mesurées : (Z,A) + (Z,A) -> [(produits, part)], source.
MEASURED = {
    ((1, 2), (1, 3)): ([(((2, 4), (0, 1)), 1.0)], "mesurée : D + T → He-4 + n"),
    ((1, 2), (1, 2)): ([(((1, 3), (1, 1)), 0.5), (((2, 3), (0, 1)), 0.5)],
                       "mesurée : moitié T + p, moitié He-3 + n"),
    ((1, 2), (2, 3)): ([(((2, 4), (1, 1)), 1.0)], "mesurée : D + He-3 → He-4 + p"),
    ((1, 1), (3, 7)): ([(((2, 4), (2, 4)), 1.0)], "mesurée : p + Li-7 → 2 α (Cockcroft et Walton, 1932)"),
    ((1, 2), (3, 6)): ([(((2, 4), (2, 4)), 1.0)], "mesurée : D + Li-6 → 2 α"),
    ((1, 1), (5, 11)): ([(((4, 8), (2, 4)), 1.0)], "mesurée : p + B-11 → Be-8 + α, puis Be-8 → 2 α"),
    ((1, 1), (1, 2)): ([(((2, 3), None), 1.0)], "mesurée : p + D → He-3 + γ (capture radiative)"),
    ((6, 12), (6, 12)): ([(((10, 20), (2, 4)), 0.56), (((11, 23), (1, 1)), 0.44)],
                         "mesurée : C-12 + C-12, 56 % Ne-20 + α, 44 % Na-23 + p (Caughlan et Fowler 1988)"),
    ((8, 16), (8, 16)): ([(((14, 28), (2, 4)), 0.34), (((15, 31), (1, 1)), 0.56), (((16, 31), (0, 1)), 0.10)],
                         "mesurée : O-16 + O-16, 56 % P-31 + p, 34 % Si-28 + α, 10 % S-31 + n (Caughlan et Fowler 1988)"),
}

# Rapport d'intensité de l'interaction faible à l'interaction forte, déduit de la
# section efficace mesurée de p + p → D + e⁺ + ν (S(0) ≈ 4·10⁻²⁵ MeV·b, contre
# ~1 MeV·b pour une réaction forte).
WEAK_FACTOR = 4e-25

NAMES = {0: "neutron", 1: "hydrogène", 2: "hélium", 3: "lithium", 4: "béryllium", 5: "bore", 6: "carbone",
         7: "azote", 8: "oxygène", 9: "fluor", 10: "néon", 11: "sodium", 12: "magnésium", 13: "aluminium",
         14: "silicium", 15: "phosphore", 16: "soufre"}


# Portée de la force forte : échange d'un pion, ħ/(m_π c) (m_π± = 139,570 MeV, PDG).
PION_MEV = 139.57039
STRONG_RANGE_FM = sc.hbar * sc.c / (PION_MEV * 1e6 * sc.e) * 1e15


def coulomb_force_n(z1, z2, distance_fm):
    """Répulsion électrique entre deux noyaux : k·Z₁Z₂e²/r², en newtons."""
    return z1 * z2 * sc.e ** 2 / (4 * math.pi * sc.epsilon_0 * (distance_fm * 1e-15) ** 2)


def plasma_note(collision):
    """Où sont passés les électrons : à ces énergies, les atomes sont ionisés."""
    from atomic import atomic_data
    z = max(collision.a[0], collision.b[0])
    last = atomic_data.ionization_energy_ev(z, z - 1) if z > 0 else None
    energy_ev = collision.energy_kev * 1e3
    if last and energy_ev > last:
        def fr(v):
            return f"{v:.3g}".replace(".", ",")
        return (f"Les atomes ont perdu leurs électrons : le choc apporte {fr(energy_ev / 1e3)} keV, il en faut "
                f"{fr(last)} eV pour arracher le dernier. Restent les noyaux nus, chargés +.")
    return "Noyaux nus (atomes ionisés) : seuls comptent leurs charges et leurs nucléons."


def _power_of_ten(x):
    """1.0e5 → "10⁵", 2.8e27 → "2,8 × 10²⁷"."""
    exponent = int(math.floor(math.log10(x)))
    mantissa = x / 10 ** exponent
    power = "10" + str(exponent).translate(str.maketrans("-0123456789", "⁻⁰¹²³⁴⁵⁶⁷⁸⁹"))
    return power if round(mantissa, 1) == 1 else f"{mantissa:.1f}".replace(".", ",") + " × " + power


def label(z, a):
    if z == 0:
        return "n"
    special = {(1, 1): "p", (1, 2): "D", (1, 3): "T", (2, 4): "He-4"}
    if (z, a) in special:
        return special[(z, a)]
    return f"{nuclear_data.SYMBOLS.get(z, '?')}-{a}"


def name(z, a):
    special = {(1, 1): "proton (hydrogène 1)", (1, 2): "deutérium", (1, 3): "tritium", (0, 1): "neutron",
               (2, 4): "hélium 4 (particule α)"}
    return special.get((z, a), f"{NAMES.get(z, nuclear_data.SYMBOLS.get(z, '?'))} {a}")


def mass_u(z, a):
    return nuclear_data.atomic_mass_u(z, a)


def radius_fm(a):
    return 1.2 * a ** (1 / 3)


def describe(z, a):
    """La carte d'identité du noyau : ce que l'atome en sait."""
    data = nuclear_data.info(z, a)
    if data is None or data["stable"]:
        status = "stable"
    elif data["half_life_s"]:
        status = f"radioactif, t½ {format_duration(data['half_life_s'])}"
    else:
        status = "instable"
    return {"z": z, "a": a, "label": label(z, a), "name": name(z, a), "protons": z, "neutrons": a - z,
            "charge": z, "mass_u": mass_u(z, a) or 0.0, "status": status}


class Collision:
    """Un choc entre deux noyaux, et son issue décidée par les règles."""

    def __init__(self, rng_seed=None):
        self.rng = np.random.default_rng(rng_seed)
        self.a = (1, 2)
        self.b = (1, 3)
        self.energy_kev = 20.0
        self.result = None
        self.run = 0

    # --- ce qui ne dépend que du choix ----------------------------------------
    def reduced_mass_mev(self):
        ma, mb = mass_u(*self.a), mass_u(*self.b)
        return ma * mb / (ma + mb) * MEV_PER_U

    def barrier_mev(self):
        za, zb = self.a[0], self.b[0]
        if za * zb == 0:
            return 0.0
        return COULOMB_MEV_FM * za * zb / (radius_fm(self.a[1]) + radius_fm(self.b[1]))

    def contact_fm(self):
        return radius_fm(self.a[1]) + radius_fm(self.b[1])

    def turning_point_fm(self):
        """Distance où la répulsion arrête les noyaux (choc frontal)."""
        za, zb = self.a[0], self.b[0]
        if za * zb == 0:
            return 0.0
        return COULOMB_MEV_FM * za * zb / (self.energy_kev / 1000)

    def tunneling(self):
        """Probabilité de franchir la barrière : 1 au-dessus, exp(−2πη) en dessous."""
        za, zb = self.a[0], self.b[0]
        energy = self.energy_kev / 1000
        if za * zb == 0 or energy >= self.barrier_mev():
            return 1.0
        # η = Z_A Z_B α / β, avec β = v/c de la vitesse relative (non relativiste)
        beta = math.sqrt(2 * energy / self.reduced_mass_mev())
        eta = za * zb * FINE_STRUCTURE / beta
        return math.exp(-2 * math.pi * eta)

    def compound(self):
        return (self.a[0] + self.b[0], self.a[1] + self.b[1])

    def channels(self):
        """Voies de sortie possibles, avec leur Q (MeV) : [(produits, part, Q, source, faible)]."""
        key = tuple(sorted((self.a, self.b)))
        energy = self.energy_kev / 1000
        before = mass_u(*self.a) + mass_u(*self.b)
        if key in MEASURED:
            options, source = MEASURED[key]
            result = []
            for products, share in options:
                after = sum(mass_u(*p) for p in products if p)
                result.append((products, share, (before - after) * MEV_PER_U, source, False))
            return result
        zc, ac = self.compound()
        candidates = []
        for _, z, a in EJECTILES:
            residual = (zc - z, ac - a)
            if residual[0] < 0 or residual[1] < 1 or residual[1] < residual[0]:
                continue
            if mass_u(*residual) is None:
                continue
            q = (before - mass_u(*residual) - mass_u(z, a)) * MEV_PER_U
            if q + energy > 0 and {residual, (z, a)} != {self.a, self.b}:
                candidates.append(((residual, (z, a)), q))
        if mass_u(zc, ac) is not None and particle_bound(zc, ac) is not False:
            q = (before - mass_u(zc, ac)) * MEV_PER_U
            candidates.append((((zc, ac), None), q))      # capture : le noyau composé reste, un γ part
        if candidates:
            best = max(candidates, key=lambda c: c[1])
            return [(best[0], 1.0, best[1], "voie supposée : celle qui libère le plus d'énergie "
                     "(la structure fine du noyau n'est pas modélisée)", False)]
        # Aucune voie par la force forte : reste l'interaction faible (p + p → D + e⁺ + ν).
        weak = (zc - 1, ac)
        if mass_u(*weak) is not None:
            q = (before - mass_u(*weak)) * MEV_PER_U - 2 * ELECTRON_MEV
            if q > 0:
                return [(((weak), "e+"), 1.0, q, "seule l'interaction faible le permet : un proton devient "
                         "neutron (p → n + e⁺ + ν)", True)]
        return []

    # --- le choc --------------------------------------------------------------------
    def fire(self, until_fusion):
        """Lance un choc (ou insiste jusqu'à la fusion). Retourne les événements."""
        self.run += 1
        options = self.channels()
        passing = self.tunneling()
        weak = bool(options) and options[0][4]
        probability = passing * (WEAK_FACTOR if weak else 1.0) if options else 0.0
        energy = self.energy_kev / 1000
        result = {"run": self.run, "barrier": self.barrier_mev(), "turning": self.turning_point_fm(),
                  "contact": self.contact_fm(), "tunneling": passing, "probability": probability,
                  "attempts": 1, "fused": False, "never": False, "products": [], "q": 0.0,
                  "source": options[0][3] if options else "", "compound": self.compound(),
                  "mass_before": mass_u(*self.a) + mass_u(*self.b), "mass_after": 0.0, "weak": weak}
        if not options:
            result["never"] = True
            result["reason"] = "aucune voie possible : les produits seraient plus lourds que les noyaux de départ"
        elif until_fusion:
            if probability < 1e-15:
                result["never"] = True
                result["reason"] = (f"Il faudrait en moyenne {_power_of_ten(1 / max(probability, 1e-300))} chocs : "
                                    f"en pratique, jamais.")
                if weak:
                    result["reason"] += (" " + options[0][3][0].upper() + options[0][3][1:]
                                         + ". Dans le Soleil, un proton attend en moyenne des milliards d'années "
                                         "avant de fusionner.")
            else:
                result["attempts"] = int(self.rng.geometric(probability)) if probability < 1 else 1
                result["fused"] = True
        else:
            result["fused"] = self.rng.random() < probability
        if result["fused"]:
            pick = self.rng.uniform(0, sum(option[1] for option in options))
            for products, share, q, source, _ in options:
                pick -= share
                if pick <= 0:
                    break
            result["q"] = q
            result["source"] = source
            result["products"] = self._kinematics(products, q + energy)
            result["chain"] = self._chain(result["products"])
            result["mass_after"] = sum(mass_u(*p) for p in products if isinstance(p, tuple))
        self.result = result
        return self._events(result)

    @staticmethod
    def _chain(products):
        """Un produit lui-même instable se brise aussitôt (Be-8 → 2 α) : les données le disent."""
        chain = []
        for index, product in enumerate(products):
            if product["kind"] != "nucleus":
                continue
            data = nuclear_data.info(product["z"], product["a"])
            if not data or data["stable"] or not data["half_life_s"] or data["half_life_s"] > 1e-12:
                continue
            mode, _, daughter = max(data["channels"], key=lambda c: c[1])
            if daughter is None:
                continue
            ejected = (product["z"] - daughter[0], product["a"] - daughter[1])
            q = (mass_u(product["z"], product["a"]) - mass_u(daughter[0], daughter[1]) - mass_u(*ejected)) * MEV_PER_U
            chain.append({"index": index, "parent": (product["z"], product["a"]), "daughter": daughter[:2],
                          "ejected": ejected, "q": q, "half_life": data["half_life_s"]})
        return chain

    def _kinematics(self, products, available):
        """Deux produits : énergie partagée en raison inverse des masses (impulsion conservée)."""
        first, second = products
        masses = [mass_u(*first), mass_u(*second) if isinstance(second, tuple) else 0.0]
        out = []
        if second is None:          # capture radiative : un photon γ emporte l'énergie
            out.append({"z": first[0], "a": first[1], "kinetic": 0.0, "speed_c": 0.0, "kind": "nucleus"})
            out.append({"z": 0, "a": 0, "kinetic": available, "speed_c": 1.0, "kind": "gamma"})
            return out
        if second == "e+":          # interaction faible : positon et neutrino emportent l'énergie
            out.append({"z": first[0], "a": first[1], "kinetic": 0.0, "speed_c": 0.0, "kind": "nucleus"})
            out.append({"z": -1, "a": 0, "kinetic": available, "speed_c": 0.99, "kind": "positron"})
            return out
        total = masses[0] + masses[1]
        for (z, a), mine, other in ((first, masses[0], masses[1]), (second, masses[1], masses[0])):
            kinetic = available * other / total
            rest = mine * MEV_PER_U
            gamma = 1 + kinetic / rest
            out.append({"z": z, "a": a, "kinetic": kinetic, "speed_c": math.sqrt(1 - 1 / gamma ** 2),
                        "kind": "neutron" if z == 0 else "nucleus"})
        return out

    def _events(self, r):
        a, b = label(*self.a), label(*self.b)
        if r["never"]:
            return [{"kind": "collision", "title": f"{a} + {b} : pas de fusion",
                     "formula": f"barrière {r['barrier'] * 1000:.0f} keV · passage par effet tunnel "
                                f"{r['tunneling']:.1e} par choc",
                     "detail": r.get("reason", "")}]
        if not r["fused"]:
            return [{"kind": "collision", "title": f"{a} + {b} : rebond",
                     "formula": f"les noyaux s'arrêtent à {r['turning']:.0f} fm (contact à {r['contact']:.1f} fm) · "
                                f"chance de passer : {r['probability']:.1e}",
                     "detail": "La répulsion électrique a gagné ce choc. Monte l'énergie, ou insiste."}]
        products = " + ".join(self._product_label(p) for p in r["products"])
        compound = nuclear_data.info(*r["compound"])
        compound_text = ""
        if compound and not compound["stable"] and compound["half_life_s"] and compound["half_life_s"] < 1e-15:
            compound_text = (f"Noyau composé {label(*r['compound'])} : il vit "
                             f"{format_duration(compound['half_life_s'])}, puis se brise. ")
        attempts = f"il a fallu {r['attempts']:,} chocs · ".replace(",", " ") if r["attempts"] > 1 else ""
        for step in r.get("chain", []):
            compound_text += (f"{label(*step['parent'])} se brise aussitôt (t½ {format_duration(step['half_life'])}) en "
                              f"{label(*step['daughter'])} + {label(*step['ejected'])}, +{step['q']:.2f} MeV. ")
        if r["q"] < 0:
            formula = (f"Δm = {r['mass_before'] - r['mass_after']:.6f} u : les produits sont plus lourds, la fusion "
                       f"CONSOMME {-r['q']:.3f} MeV, pris sur l'énergie du choc")
        else:
            formula = f"Δm = {r['mass_before'] - r['mass_after']:.6f} u → E = Δm·c² = {r['q']:.3f} MeV libérés"
        return [{"kind": "fusion", "title": f"Fusion : {a} + {b} → {products}",
                 "formula": formula,
                 "detail": f"{attempts}{compound_text}{r['source']}"}]

    @staticmethod
    def _product_label(product):
        if product["kind"] == "gamma":
            return "γ"
        if product["kind"] == "positron":
            return "e⁺ + ν"
        return label(product["z"], product["a"])

    def temperature_equivalent_k(self):
        """Température d'un plasma dont l'agitation typique vaut cette énergie de choc."""
        return self.energy_kev * KEV_TO_KELVIN
