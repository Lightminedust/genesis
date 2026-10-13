"""Données nucléaires évaluées, lues dans des bibliothèques de référence.

Plutôt que de recopier des valeurs à la main, le moteur interroge :

  - radioactivedecay : base ICRP-107 mise à jour avec NUBASE2020 et AME2020,
    1 512 radionucléides. Demi-vies, modes de désintégration, rapports de
    branchement (Bi-212 : 64 % β⁻, 36 % α) et noyaux fils, états isomères
    compris (Th-234 donne Pa-234m).
  - mendeleev : 3 557 isotopes connus avec leurs masses atomiques mesurées
    (AME), leurs abondances, et les modes des noyaux très éphémères absents
    d'ICRP-107 (He-5 perd un neutron en 6 × 10⁻²² s, Be-8 se casse en deux α).

Les énergies libérées Q sont calculées à partir des masses MESURÉES :
Q = (masse avant − masse après) × c². Weizsäcker ne sert plus que pour les
noyaux que personne n'a jamais observés.
"""

import math
import os
import sqlite3
from functools import lru_cache

import mendeleev
import radioactivedecay as rd
from scipy import constants as sc

MEV_PER_U = sc.physical_constants["atomic mass constant energy equivalent in MeV"][0]
ELECTRON_MEV = sc.physical_constants["electron mass energy equivalent in MeV"][0]
NEUTRON_MASS_U = sc.physical_constants["neutron mass in u"][0]
# Neutron libre : durée de vie moyenne 878,4 s (PDG), t½ = τ ln 2.
FREE_NEUTRON_HALF_LIFE_S = 878.4 * math.log(2)

DATASET = "ICRP-107, radioactivedecay"
MENDELEEV_SOURCE = "NUBASE, mendeleev"

_DB = sqlite3.connect(os.path.join(os.path.dirname(mendeleev.__file__), "elements.db"),
                      check_same_thread=False)
_DATA = rd.DEFAULTDATA

_UNIT_S = {
    "ysec": 1e-24, "zsec": 1e-21, "asec": 1e-18, "psec": 1e-12, "nsec": 1e-9, "usec": 1e-6,
    "msec": 1e-3, "sec": 1.0, "minute": 60.0, "hour": 3600.0, "day": 86400.0,
    "year": 365.25 * 86400.0,
}
for _prefix, _power in (("k", 3), ("M", 6), ("G", 9), ("T", 12), ("P", 15), ("E", 18),
                        ("Z", 21), ("Y", 24)):
    _UNIT_S[_prefix + "year"] = 365.25 * 86400.0 * 10 ** _power

# Modes, dans les conventions du simulateur : (code, ΔZ, ΔN)
_RD_MODES = {"α": ("alpha", -2, -2), "β-": ("beta-", 1, -1), "β+ & EC": ("beta+", -1, 1),
             "EC": ("CE", -1, 1), "IT": ("IT", 0, 0), "SF": ("SF", 0, 0)}
_MENDELEEV_MODES = {"A": ("alpha", -2, -2), "B-": ("beta-", 1, -1), "B+": ("beta+", -1, 1),
                    "EC": ("CE", -1, 1), "n": ("n", 0, -1), "p": ("p", -1, 0),
                    "2n": ("2n", 0, -2), "2p": ("2p", -2, 0), "B-n": ("beta-n", 1, -2),
                    "B+p": ("beta+p", -2, 1), "SF": ("SF", 0, 0)}

SYMBOLS = {row[0]: row[1] for row in _DB.execute("select atomic_number, symbol from elements")}


# ----------------------------------------------------------------------
# Masses et isotopes (mendeleev)
# ----------------------------------------------------------------------
@lru_cache(maxsize=None)
def _isotope_row(z, a):
    return _DB.execute("select id, mass, abundance, is_radioactive, half_life, half_life_unit "
                       "from isotopes where atomic_number=? and mass_number=?", (z, a)).fetchone()


def atomic_mass_u(z, a):
    """Masse atomique mesurée (AME), en u, ou None si le noyau n'a jamais été pesé."""
    if z == 0:
        return NEUTRON_MASS_U if a == 1 else None
    row = _isotope_row(z, a)
    return row[1] if row else None


def is_known(z, a):
    return (z == 0 and a == 1) or _isotope_row(z, a) is not None


def abundance(z, a):
    row = _isotope_row(z, a)
    return row[2] if row and row[2] else 0.0


@lru_cache(maxsize=None)
def spin_parity(z, a):
    """Spin nucléaire mesuré et parité : "0+", "3/2+", "1"... ; "" si inconnu."""
    if z == 0:
        return "1/2+" if a == 1 else ""
    row = _DB.execute("select spin, parity from isotopes where atomic_number=? and mass_number=?",
                      (z, a)).fetchone()
    if not row or not row[0]:
        return ""
    return f"{row[0]}{row[1] or ''}"


def measured_binding(z, a):
    """(masse atomique mesurée, masse des constituants séparés, énergie de liaison) en u, u, MeV.

    Constituants : Z atomes d'hydrogène (proton + électron) et N neutrons ;
    la différence de masse est l'énergie de liaison du noyau, E = Δm c².
    None si le noyau n'a jamais été pesé.
    """
    mass = atomic_mass_u(z, a)
    if mass is None or z < 1:
        return None
    parts = z * atomic_mass_u(1, 1) + (a - z) * NEUTRON_MASS_U
    return mass, parts, (parts - mass) * MEV_PER_U


def q_value_mev(z, a, mode):
    """Q d'après les masses atomiques mesurées ; None si une masse manque.

    Masses ATOMIQUES (électrons compris) : pour β⁻ et la capture, les électrons
    s'équilibrent d'eux-mêmes ; pour β⁺, il faut retrancher 2 mₑc².
    """
    parent = atomic_mass_u(z, a)
    if parent is None:
        return None

    def mass(dz, da):
        return atomic_mass_u(z + dz, a + da)

    if mode == "beta-":
        daughter = mass(1, 0)
        return None if daughter is None else (parent - daughter) * MEV_PER_U
    if mode in ("CE", "beta+"):
        daughter = mass(-1, 0)
        if daughter is None:
            return None
        q = (parent - daughter) * MEV_PER_U
        return q - 2 * ELECTRON_MEV if mode == "beta+" else q
    if mode == "alpha":
        daughter, helium = mass(-2, -4), atomic_mass_u(2, 4)
        return None if daughter is None else (parent - daughter - helium) * MEV_PER_U
    if mode == "n":
        daughter = mass(0, -1)
        return None if daughter is None else (parent - daughter - NEUTRON_MASS_U) * MEV_PER_U
    if mode == "p":
        daughter = mass(-1, -1)
        return None if daughter is None else (parent - daughter - atomic_mass_u(1, 1)) * MEV_PER_U
    return None


# ----------------------------------------------------------------------
# Désintégrations
# ----------------------------------------------------------------------
def nuclide_name(z, a, state=""):
    if z == 0:
        return "n" if a == 1 else f"{a}n"
    return f"{SYMBOLS.get(z, f'Z{z}')}-{a}{state}"


@lru_cache(maxsize=None)
def _parse(name):
    nuclide = rd.Nuclide(name)
    return nuclide.Z, nuclide.A, nuclide.state


def info(z, a, state=""):
    """Tout ce que l'on sait du nuclide (Z, A, état).

    Retourne None s'il n'a jamais été observé, sinon un dict :
      stable, half_life_s, source, channels = [(mode, fraction, (Z, A, état))].
    """
    if z == 0:
        if a != 1:
            return None
        return {"stable": False, "half_life_s": FREE_NEUTRON_HALF_LIFE_S,
                "source": "mesurée (PDG : τ = 878,4 s)",
                "channels": [("beta-", 1.0, (1, 1, ""))]}

    name = nuclide_name(z, a, state)
    index = _DATA.nuclide_dict.get(name)
    if index is not None:
        channels = []
        for mode, fraction, daughter in zip(_DATA.modes[index], _DATA.bfs[index], _DATA.progeny[index]):
            code = _RD_MODES.get(mode, (mode, 0, 0))[0]
            target = None if code == "SF" else _parse(daughter)
            channels.append((code, float(fraction), target))
        seconds = float(_DATA.half_life(name, "s"))
        if math.isinf(seconds):
            # ICRP-107 garde quelques noyaux stables (Fe-56) pour clore les chaînes.
            return {"stable": True, "half_life_s": math.inf, "source": "stable (mesure)", "channels": []}
        return {"stable": False, "half_life_s": seconds,
                "source": f"mesurée ({DATASET})", "channels": channels}

    row = _isotope_row(z, a)
    if row is None:
        return None
    isotope_id, _, natural, radioactive, half_life, unit = row
    if not radioactive:
        if not natural:
            # Observé mais jamais caractérisé (C-21, Tc-83...) : rien à en dire.
            return None
        return {"stable": True, "half_life_s": math.inf, "source": "stable (mesure)", "channels": []}
    seconds = half_life * _UNIT_S[unit] if half_life and unit in _UNIT_S else None
    channels = []
    modes = _DB.execute("select mode, intensity from isotopedecaymodes where isotope_id=? "
                        "and relation in ('=', '~') order by intensity desc", (isotope_id,)).fetchall()
    # Base secondaire : on ne garde que le mode principal, les intensités
    # des voies minoritaires n'y étant pas toutes exprimées de la même façon.
    for mode, _ in modes[:1]:
        if mode in _MENDELEEV_MODES:
            code, dz, dn = _MENDELEEV_MODES[mode]
            channels.append((code, 1.0, None if code == "SF" else (z + dz, a + dz + dn, "")))
    return {"stable": False, "half_life_s": seconds, "source": f"mesurée ({MENDELEEV_SOURCE})",
            "channels": channels}


def format_name(z, a, state=""):
    return f"{SYMBOLS.get(z, 'n')}-{a}{state}" if z else ("neutron libre" if a == 1 else f"{a} neutrons")
