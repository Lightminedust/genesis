"""Les matériaux du laboratoire : cœurs fissiles, réflecteurs, et leurs constantes mesurées.

Les densités viennent de la base des éléments (mendeleev) ; un métal isotopiquement pur garde la densité atomique
du métal mesuré (même cristal, noyaux plus ou moins lourds). Les compositions des assemblages de référence sont
celles des expériences critiques publiées (ICSBEP) ; leurs densités aussi, puisqu'elles ont été mesurées.
"""

import math

from common import elements_db
from nuclear import nuclear_data

from . import library

AVOGADRO = 6.02214076e23
# Le métal dont la densité tabulée a été mesurée : uranium et thorium naturels, ailleurs l'isotope disponible.
REFERENCE_ISOTOPE = {92: None, 90: None, 93: 237, 94: 239, 95: 241, 4: None, 6: None}
# Multiplicité moyenne des neutrons de fission spontanée (Holden & Zucker, 1985).
SPONTANEOUS_NU = {(92, 238): 2.00, (94, 238): 2.21, (94, 240): 2.154, (94, 242): 2.149, (96, 244): 2.72,
                  (98, 252): 3.757}
# Dilatation linéaire à 25 °C, en 1/K (CRC Handbook) : la contre-réaction de dilatation du cœur.
THERMAL_EXPANSION = {92: 13.9e-6, 94: 46.7e-6, 90: 11.0e-6}


def element(z):
    row = elements_db.DB.execute(
        "select symbol, name, density, atomic_weight, specific_heat_capacity, fusion_heat, evaporation_heat "
        "from elements where atomic_number=?", (z,)).fetchone()
    phases = elements_db.DB.execute(
        "select melting_point, boiling_point from phasetransitions where atomic_number=?", (z,)).fetchone()
    return {"symbol": row[0], "name": row[1], "density": row[2], "atomic_weight": row[3],
            "specific_heat": row[4], "fusion_heat": row[5], "evaporation_heat": row[6],
            "melting_k": phases[0] if phases else None, "boiling_k": phases[1] if phases else None}


def metal_density(z, isotopes):
    """Densité (g/cm³) d'un métal de l'élément z fait des isotopes donnés {nom: fraction atomique}."""
    e = element(z)
    reference = REFERENCE_ISOTOPE.get(z)
    reference_mass = nuclear_data.atomic_mass_u(z, reference) if reference else e["atomic_weight"]
    mean = sum(f * library.mass_u(n) for n, f in isotopes.items()) / sum(isotopes.values())
    return e["density"] * mean / reference_mass


class Material:
    """Un matériau : sa composition (fractions atomiques) et sa densité nominale."""

    def __init__(self, key, label, detail, atoms, density, z=None, source=""):
        self.key, self.label, self.detail = key, label, detail
        total = sum(atoms.values())
        self.atoms = {n: f / total for n, f in atoms.items()}
        self.density = density
        self.z = z
        self.source = source

    @property
    def mean_mass(self):
        return sum(f * library.mass_u(n) for n, f in self.atoms.items())

    def number_densities(self, compression=1.0):
        """Densités atomiques (atomes par barn·cm) à cette compression."""
        n = self.density * compression / self.mean_mass * AVOGADRO * library.BARN_CM2
        return {name: n * f for name, f in self.atoms.items()}

    @property
    def fissile(self):
        return any(library.fissile_data(n) for n in self.atoms)

    @property
    def main(self):
        return max(self.atoms, key=self.atoms.get)


def _weights_to_atoms(weights):
    atoms = {n: w / library.mass_u(n) for n, w in weights.items()}
    total = sum(atoms.values())
    return {n: a / total for n, a in atoms.items()}


def _pure(key, label, detail, name, z):
    return Material(key, label, detail, {name: 1.0}, metal_density(z, {name: 1.0}), z,
                    "densité du métal (mendeleev), même densité atomique")


def cores():
    """Les cœurs proposés : d'abord les quatre du sélecteur, puis les assemblages historiques et le reste."""
    godiva = _weights_to_atoms({"U234": 1.02, "U235": 93.71, "U238": 5.27})
    pu = {"Pu239": 0.9518, "Pu240": 0.0449, "Pu241": 0.0031}
    mpu = sum(f * library.mass_u(n) for n, f in pu.items())
    jezebel = {n: f * library.mass_u(n) / mpu * 0.9898 for n, f in pu.items()}
    jezebel.update({"Ga69": 0.0102 * 0.60108, "Ga71": 0.0102 * 0.39892})
    natural = {"U234": 0.000054, "U235": 0.007204, "U238": 0.992742}
    return [
        _pure("U235", "Uranium 235", "fissile : fission par neutrons de toute énergie", "U235", 92),
        _pure("Pu239", "Plutonium 239", "fissile ; métal en phase α (le plus dense)", "Pu239", 94),
        _pure("U238", "Uranium 238", "fissionnable au-delà de 1 MeV seulement ; captures résonnantes stériles", "U238", 92),
        _pure("Th232", "Thorium 232", "fertile : une capture donne de l'uranium 233 après deux β⁻", "Th232", 90),
        _pure("Fe56", "Fer 56", "au sommet de la courbe de liaison : aucune fission, des diffusions et des captures",
              "Fe56", 26),
        _pure("Pb208", "Plomb 208", "doublement magique : il renvoie les neutrons presque sans en absorber", "Pb208", 82),
        Material("HEU", "Uranium enrichi 93,7 % (Godiva)", "la sphère nue critique de Los Alamos, 1951",
                 godiva, 18.74, 92, "ICSBEP HEU-MET-FAST-001"),
        Material("WGPU", "Plutonium militaire (Jezebel)", "95 % Pu-239, 4,5 % Pu-240 ; phase δ stabilisée au gallium",
                 _weights_to_atoms(jezebel), 15.61, 94, "ICSBEP PU-MET-FAST-001"),
        _pure("U233", "Uranium 233", "fissile, né du thorium dans un surgénérateur", "U233", 92),
        Material("UNAT", "Uranium naturel", "0,72 % d'uranium 235", natural, metal_density(92, natural), 92,
                 "abondances naturelles"),
        _pure("Pu240", "Plutonium 240", "fissionnable rapide ; fission spontanée intense", "Pu240", 94),
        _pure("Pu241", "Plutonium 241", "fissile, se désintègre en américium 241 (t½ 14 ans)", "Pu241", 94),
        _pure("Np237", "Neptunium 237", "fissionnable rapide seulement", "Np237", 93),
        _pure("Am241", "Américium 241", "fissionnable rapide seulement", "Am241", 95),
    ]


def reflectors():
    natural = {"U234": 0.000054, "U235": 0.007204, "U238": 0.992742}
    return [
        Material("BE", "Béryllium", "léger, peu absorbant ; (n,2n) au-delà de 1,9 MeV", {"Be9": 1.0},
                 element(4)["density"], 4, "densité (mendeleev)"),
        Material("C", "Graphite", "ralentit les neutrons qu'il renvoie (traité en gaz libre, sans S(α,β))",
                 {"C12": 1.0}, element(6)["density"], 6, "densité (mendeleev)"),
        Material("UNAT", "Uranium naturel (tamper)", "lourd : renvoie les neutrons et freine l'expansion",
                 natural, metal_density(92, natural), 92, "abondances naturelles"),
    ]


def spontaneous_source(material, compression, volume_cm3):
    """Neutrons par seconde émis par fission spontanée dans le cœur ; et les noyaux dont la multiplicité manque."""
    total, unknown = 0.0, []
    for name, n in material.number_densities(compression).items():
        z, a = int(library.get(name, "z")), int(library.get(name, "a"))
        decay = nuclear_data.info(z, a)
        if not decay or decay["stable"]:
            continue
        sf = sum(f for mode, f, _ in decay["channels"] if mode == "SF")
        if sf <= 0:
            continue
        nu = SPONTANEOUS_NU.get((z, a))
        if nu is None:
            unknown.append(name)
            continue
        atoms = n / library.BARN_CM2 * volume_cm3
        total += atoms * math.log(2) / decay["half_life_s"] * sf * nu
    return total, unknown
