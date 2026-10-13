"""Règles de Slater (1930) : charge effective, taille et énergie des orbitales.

Un électron ne voit pas toute la charge du noyau : les autres électrons la
masquent en partie. Slater a proposé des coefficients d'écrantage empiriques,
calés sur les données spectroscopiques, qui restent la méthode standard
d'estimation « à la main ».

Les électrons sont rangés en groupes, dans cet ordre :
    (1s) (2s,2p) (3s,3p) (3d) (4s,4p) (4d) (4f) (5s,5p) (5d) (5f) ...

Pour un électron d'un groupe (ns, np) :
    - chaque autre électron du même groupe masque 0,35 (0,30 pour 1s)
    - chaque électron de la couche n-1 masque 0,85
    - chaque électron des couches n-2 et plus profondes masque 1,00
Pour un électron d'un groupe (nd) ou (nf) :
    - chaque autre électron du même groupe masque 0,35
    - chaque électron des groupes situés à gauche masque 1,00
Les groupes situés à droite ne masquent rien.

Au-delà de n = 3, Slater remplace n par un nombre quantique effectif n*.
"""

BOHR_RADIUS_PM = 52.9177210903
RYDBERG_EV = 13.605693122994

# Nombre quantique effectif de Slater
N_STAR = {1: 1.0, 2: 2.0, 3: 3.0, 4: 3.7, 5: 4.0, 6: 4.2, 7: 4.2, 8: 4.2}


def group_key(n, l):
    """(ns, np) partagent un groupe ; (nd) et (nf) ont chacun le leur.

    Trier par cette clé redonne exactement l'ordre de Slater, (3d) se
    plaçant entre (3s,3p) et (4s,4p).
    """
    return (n, 0 if l <= 1 else l)


def screening(subshells, n, l):
    """Constante d'écran S vue par un électron de la sous-couche (n, l).

    `subshells` est une liste de tuples (n, l, occupation) décrivant tout
    le cortège, électron considéré compris.
    """
    target = group_key(n, l)
    total = 0.0
    same_group = 0
    for other_n, other_l, occupancy in subshells:
        group = group_key(other_n, other_l)
        if group > target:
            continue
        if group == target:
            # 2s et 2p forment UN groupe : on cumule avant de retirer l'électron
            # considéré, sinon il serait retiré une fois par sous-couche.
            same_group += occupancy
            continue
        if l <= 1:
            total += occupancy * (0.85 if other_n == n - 1 else 1.00)
        else:
            total += occupancy * 1.00
    # L'électron ne s'écrante pas lui-même.
    total += max(0, same_group - 1) * (0.30 if n == 1 else 0.35)
    return total


def effective_charge(z, subshells, n, l):
    """Z_eff = Z - S. Jamais inférieure à une fraction de charge : un anion
    très chargé n'a plus de sens physique, mais le calcul ne doit pas casser."""
    return max(0.3, z - screening(subshells, n, l))


def n_star(n):
    return N_STAR.get(n, 4.2)


def orbital_radius_pm(z, subshells, n, l):
    """Rayon du maximum de densité d'une orbitale de Slater : n*² a0 / Z_eff."""
    return n_star(n) ** 2 * BOHR_RADIUS_PM / effective_charge(z, subshells, n, l)


def orbital_energy_ev(z, subshells, n, l):
    """Énergie d'un électron de la sous-couche : -13,6 (Z_eff / n*)² eV."""
    return -RYDBERG_EV * (effective_charge(z, subshells, n, l) / n_star(n)) ** 2


def total_energy_ev(z, subshells):
    """Énergie électronique totale : somme sur tous les électrons."""
    return sum(occupancy * orbital_energy_ev(z, subshells, sub_n, sub_l)
               for sub_n, sub_l, occupancy in subshells if occupancy > 0)


def removal_energy_ev(z, subshells, n, l):
    """Énergie à fournir pour arracher un électron de la sous-couche (n, l).

    C'est la bonne façon d'appliquer Slater : E(ion) - E(atome). Quand un
    électron part, les autres sont moins écrantés et se resserrent ; l'énergie
    orbitale seule ignore cette réorganisation et surestime l'ionisation d'un
    facteur 3 à 10 pour les couches externes (carbone : 36 eV au lieu de 11,3),
    la différence d'énergies totales tombe à quelques pour cent (11,5 eV).
    """
    ion = []
    removed = False
    for sub_n, sub_l, occupancy in subshells:
        if not removed and (sub_n, sub_l) == (n, l) and occupancy > 0:
            occupancy -= 1
            removed = True
        if occupancy > 0:
            ion.append((sub_n, sub_l, occupancy))
    return total_energy_ev(z, ion) - total_energy_ev(z, subshells)
