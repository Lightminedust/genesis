"""Le zoo de particules : propriétés intrinsèques, immuables.

Chaque particule du simulateur a une fiche : masse au repos, charge, spin,
moment magnétique, durée de vie, interactions qu'elle ressent. Ce sont ces
nombres qui décident de son comportement :

  - la charge fixe la force électrique (Coulomb) ;
  - le spin fait des électrons, protons et neutrons des FERMIONS (spin 1/2) :
    deux fermions identiques ne peuvent pas occuper le même état (Pauli),
    d'où les couches électroniques et les couches du noyau. Le photon et le
    gluon sont des BOSONS (spin 1) : ils transportent les forces ;
  - le spin du photon (1) impose la règle Δl = ±1 lors d'un saut d'électron :
    le photon emporte une unité de moment cinétique ;
  - la masse fixe l'inertie : un électron est 1 836 fois plus léger qu'un
    proton.

Sources : CODATA 2018 (scipy.constants) pour les leptons et les nucléons ;
Particle Data Group 2024 pour les quarks et les neutrinos, absents de
CODATA.
"""

import math

from scipy import constants as sc


def _codata(key):
    return sc.physical_constants[key][0]


ELECTRON_MEV = _codata("electron mass energy equivalent in MeV")
PROTON_MEV = _codata("proton mass energy equivalent in MeV")
NEUTRON_MEV = _codata("neutron mass energy equivalent in MeV")
ALPHA_MEV = _codata("alpha particle mass energy equivalent in MeV")
FREE_NEUTRON_LIFETIME_S = 878.4          # PDG 2024, durée de vie moyenne

PARTICLES = [
    {
        "key": "electron", "symbol": "e⁻", "name": "électron",
        "family": "lepton · fermion", "mass_mev": ELECTRON_MEV, "charge": "−1", "spin": "1/2",
        "magnetic": f"{_codata('electron mag. mom. to Bohr magneton ratio'):.6f} μ_B",
        "lifetime": "stable", "interactions": "électromagnétique, faible",
        "note": "Ignore la force forte. Spin 1/2 : deux électrons par orbitale au plus, "
                "de spins opposés (Pauli).",
        "source": "CODATA 2018 (scipy.constants)",
    },
    {
        "key": "positron", "symbol": "e⁺", "name": "positon (anti-électron)",
        "family": "antilepton · fermion", "mass_mev": ELECTRON_MEV, "charge": "+1", "spin": "1/2",
        "magnetic": "opposé à celui de l'électron", "lifetime": "stable (s'annihile avec un électron)",
        "interactions": "électromagnétique, faible",
        "note": "Émis par la désintégration β⁺. En rencontrant un électron, les deux "
                "disparaissent en deux photons de 511 keV.",
        "source": "CODATA 2018 (scipy.constants)",
    },
    {
        "key": "proton", "symbol": "p⁺", "name": "proton",
        "family": "baryon (u u d) · fermion", "mass_mev": PROTON_MEV, "charge": "+1", "spin": "1/2",
        "magnetic": f"{_codata('proton mag. mom. to nuclear magneton ratio'):.6f} μ_N",
        "lifetime": "stable (> 10³⁴ ans)", "interactions": "forte, électromagnétique, faible",
        "note": f"Rayon de charge {_codata('proton rms charge radius') * 1e15:.4f} fm. "
                "Lié aux autres nucléons par la force forte, repoussé par les autres protons.",
        "source": "CODATA 2018 (scipy.constants)",
    },
    {
        "key": "neutron", "symbol": "n", "name": "neutron",
        "family": "baryon (u d d) · fermion", "mass_mev": NEUTRON_MEV, "charge": "0", "spin": "1/2",
        "magnetic": f"{_codata('neutron mag. mom. to nuclear magneton ratio'):.6f} μ_N",
        "lifetime": f"{FREE_NEUTRON_LIFETIME_S:.1f} s libre (t½ = "
                    f"{FREE_NEUTRON_LIFETIME_S * math.log(2) / 60:.1f} min) ; stable dans un noyau stable",
        "interactions": "forte, faible (et magnétique, malgré sa charge nulle)",
        "note": f"Plus lourd que le proton de {NEUTRON_MEV - PROTON_MEV:.3f} MeV : c'est ce qui "
                "permet n → p + e⁻ + ν̄. Colle le noyau sans ajouter de répulsion.",
        "source": "CODATA 2018 (scipy.constants), PDG 2024",
    },
    {
        "key": "photon", "symbol": "γ", "name": "photon",
        "family": "boson de jauge", "mass_mev": 0.0, "charge": "0", "spin": "1",
        "magnetic": "0", "lifetime": "stable", "interactions": "porte la force électromagnétique",
        "note": "Va toujours à c. Son spin 1 explique la règle Δl = ±1 : en partant, il emporte "
                "une unité de moment cinétique ; le spin de l'électron, lui, ne change pas.",
        "source": "PDG 2024",
    },
    {
        "key": "neutrino", "symbol": "ν", "name": "neutrino électronique",
        "family": "lepton · fermion", "mass_mev": 0.0, "charge": "0", "spin": "1/2",
        "magnetic": "≈ 0", "lifetime": "stable", "interactions": "faible uniquement",
        "note": "Masse < 0,8 eV/c² (KATRIN 2022). Traverse la Terre sans interagir : il emporte "
                "une part de l'énergie des désintégrations β, ce qui rend le spectre β continu.",
        "source": "PDG 2024, KATRIN",
    },
    {
        "key": "antineutrino", "symbol": "ν̄", "name": "antineutrino électronique",
        "family": "antilepton · fermion", "mass_mev": 0.0, "charge": "0", "spin": "1/2",
        "magnetic": "≈ 0", "lifetime": "stable", "interactions": "faible uniquement",
        "note": "Créé par la désintégration β⁻ pour conserver l'énergie, le moment cinétique et "
                "le nombre leptonique.",
        "source": "PDG 2024",
    },
    {
        "key": "alpha", "symbol": "α", "name": "particule alpha (noyau d'hélium 4)",
        "family": "noyau (2p + 2n) · boson composite", "mass_mev": ALPHA_MEV, "charge": "+2",
        "spin": "0", "magnetic": "0", "lifetime": "stable",
        "interactions": "forte, électromagnétique",
        "note": "Si fortement liée (28,3 MeV) qu'elle sort d'un bloc des noyaux lourds, par effet "
                "tunnel à travers la barrière électrique.",
        "source": "CODATA 2018 (scipy.constants)",
    },
    {
        "key": "up", "symbol": "u", "name": "quark up",
        "family": "quark · fermion", "mass_mev": 2.16, "charge": "+2/3", "spin": "1/2",
        "magnetic": "—", "lifetime": "jamais isolé (confinement)",
        "interactions": "forte (charge de couleur), électromagnétique, faible",
        "note": "Porte une charge de couleur (rouge, vert ou bleu). Les trois quarks d'un "
                "nucléon portent les trois couleurs : l'ensemble est « blanc ».",
        "source": "PDG 2024 (masse MS-bar à 2 GeV)",
    },
    {
        "key": "down", "symbol": "d", "name": "quark down",
        "family": "quark · fermion", "mass_mev": 4.70, "charge": "−1/3", "spin": "1/2",
        "magnetic": "—", "lifetime": "jamais isolé (confinement)",
        "interactions": "forte (charge de couleur), électromagnétique, faible",
        "note": "La désintégration β⁻ change un d en u (par l'interaction faible) : le neutron "
                "(u d d) devient proton (u u d).",
        "source": "PDG 2024 (masse MS-bar à 2 GeV)",
    },
    {
        "key": "gluon", "symbol": "g", "name": "gluon",
        "family": "boson de jauge", "mass_mev": 0.0, "charge": "0", "spin": "1",
        "magnetic": "0", "lifetime": "jamais isolé (confinement)",
        "interactions": "porte la force forte (et porte lui-même une couleur)",
        "note": "Les quarks u + u + d ne pèsent qu'environ 9 MeV : les 929 MeV restants du "
                "proton sont l'énergie des gluons et du mouvement des quarks (E = mc²).",
        "source": "PDG 2024",
    },
]
