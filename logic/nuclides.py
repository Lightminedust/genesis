"""Radio-isotopes : ce que la mesure dit de chaque noyau.

Toutes les données viennent des bases évaluées (voir nuclear_data.py) :
mode de désintégration, demi-vie, et énergie libérée Q calculée à partir des
masses atomiques MESURÉES. Pourquoi pas une formule : Q est une petite
différence entre deux grandes masses. La formule de Weizsäcker se trompe de
quelques MeV sur chaque masse, ce qui suffit à inverser le signe de Q pour
les noyaux légers (elle prédit que le carbone-14 ne peut pas se désintégrer,
alors qu'il le fait avec Q = +0,156 MeV). Elle ne sert donc qu'aux noyaux que
personne n'a jamais observés, et c'est alors dit.
"""

import math

import nuclear_data
from units import format_duration

UNBOUND_MODES = {"n", "p", "2n", "2p"}


def lookup(z, a, state=""):
    """Données mesurées du nucléide (Z, A), ou None s'il est stable ou inconnu.

    Le mode retenu est la voie principale ; toutes les voies sont dans
    `channels`, avec leur rapport de branchement.
    """
    data = nuclear_data.info(z, a, state)
    if not data or data["stable"] or not data["channels"] or data["half_life_s"] in (None, math.inf):
        return None
    mode = max(data["channels"], key=lambda channel: channel[1])[0]
    q = nuclear_data.q_value_mev(z, a, mode)
    return {"mode": mode, "q_mev": q, "half_life": format_duration(data["half_life_s"]),
            "half_life_s": data["half_life_s"], "channels": data["channels"],
            "source": data["source"]}


def particle_bound(z, a):
    """Le noyau retient-il tous ses nucléons ?

    True/False d'après la mesure (un noyau non lié perd directement un neutron
    ou un proton, en ~10⁻²¹ s) ; pour un noyau jamais observé, d'après
    l'énergie de séparation de Weizsäcker ; None si rien ne permet de trancher.
    """
    measured = lookup(z, a)
    if measured:
        return measured["mode"] not in UNBOUND_MODES
    if nuclear_data.info(z, a):
        return True
    from nucleus import q_neutron_emission, q_proton_emission
    n = a - z
    if n < 0 or z < 1:
        return None
    if q_neutron_emission(z, n) > 0 or q_proton_emission(z, n) > 0:
        return False
    return None


def longest_lived_isotope(z):
    """Pour un élément sans isotope stable : celui qui vit le plus longtemps
    (U-238, Ra-226, Tc-97...), c'est-à-dire celui qu'on trouve vraiment."""
    best = None
    for a in range(z, 3 * z + 10):
        data = nuclear_data.info(z, a)
        if data and not data["stable"] and data["half_life_s"]:
            if best is None or data["half_life_s"] > best[1]:
                best = (a, data["half_life_s"])
    return best[0] if best else None
