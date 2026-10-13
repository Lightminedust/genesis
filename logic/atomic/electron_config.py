"""Configuration électronique des atomes neutres.

Plutôt que de stocker 118 configurations à la main, on applique la règle de
Klechkowski (ordre de Madelung : n+l croissant, puis n croissant) et on ne
retient que les exceptions réellement observées, où un sous-niveau d demi-plein
ou plein coûte moins d'énergie que la règle ne le prévoit (Cr, Cu, Ag, Au...).

Le résultat est converti en occupation par couche (K, L, M...), qui est ce que
le modèle de Bohr affiche à l'écran.
"""

SUBSHELL_LABELS = {0: "s", 1: "p", 2: "d", 3: "f"}

# Ordre de remplissage de Madelung, sous forme (n, l).
AUFBAU_ORDER = [
    (1, 0),
    (2, 0), (2, 1),
    (3, 0), (3, 1),
    (4, 0), (3, 2), (4, 1),
    (5, 0), (4, 2), (5, 1),
    (6, 0), (4, 3), (5, 2), (6, 1),
    (7, 0), (5, 3), (6, 2), (7, 1),
    (8, 0), (6, 3), (7, 2), (8, 1),
]

# Exceptions à la règle, exprimées comme un transfert d'électrons depuis le
# sous-niveau s le plus externe vers le sous-niveau d ou f indiqué.
# Format : Z -> ((n, l) source, (n, l) destination, nombre d'électrons déplacés)
EXCEPTIONS = {
    24: ((4, 0), (3, 2), 1),   # Chrome     [Ar] 3d5 4s1
    29: ((4, 0), (3, 2), 1),   # Cuivre     [Ar] 3d10 4s1
    41: ((5, 0), (4, 2), 1),   # Niobium
    42: ((5, 0), (4, 2), 1),   # Molybdène
    44: ((5, 0), (4, 2), 1),   # Ruthénium
    45: ((5, 0), (4, 2), 1),   # Rhodium
    46: ((5, 0), (4, 2), 2),   # Palladium  [Kr] 4d10, aucun 5s
    47: ((5, 0), (4, 2), 1),   # Argent
    57: ((4, 3), (5, 2), 1),   # Lanthane   4f0 5d1
    58: ((4, 3), (5, 2), 1),   # Cérium
    64: ((4, 3), (5, 2), 1),   # Gadolinium
    78: ((6, 0), (5, 2), 1),   # Platine
    79: ((6, 0), (5, 2), 1),   # Or         [Xe] 4f14 5d10 6s1
    89: ((5, 3), (6, 2), 1),   # Actinium
    90: ((5, 3), (6, 2), 2),   # Thorium
    91: ((5, 3), (6, 2), 1),   # Protactinium
    92: ((5, 3), (6, 2), 1),   # Uranium
    93: ((5, 3), (6, 2), 1),   # Neptunium
    96: ((5, 3), (6, 2), 1),   # Curium
    103: ((6, 2), (7, 1), 1),  # Lawrencium 7p1 au lieu de 6d1
}


def subshell_capacity(l):
    """Capacité d'un sous-niveau : 2(2l + 1)."""
    return 2 * (2 * l + 1)


def subshell_configuration(z):
    """Configuration en sous-niveaux de l'atome neutre de numéro `z`.

    Retourne une liste de tuples (n, l, occupation), dans l'ordre de
    remplissage. Exemple pour Z=26 : [(1,0,2), (2,0,2), (2,1,6), (3,0,2),
    (3,1,6), (4,0,2), (3,2,6)].
    """
    remaining = z
    filled = []
    for n, l in AUFBAU_ORDER:
        if remaining <= 0:
            break
        placed = min(remaining, subshell_capacity(l))
        filled.append([n, l, placed])
        remaining -= placed

    exception = EXCEPTIONS.get(z)
    if exception:
        _apply_exception(filled, *exception)

    return [tuple(entry) for entry in filled if entry[2] > 0]


def _apply_exception(filled, source, destination, count):
    """Déplace `count` électrons d'un sous-niveau vers un autre."""
    source_entry = next((entry for entry in filled
                         if (entry[0], entry[1]) == source), None)
    if source_entry is None or source_entry[2] < count:
        return

    destination_entry = next((entry for entry in filled
                              if (entry[0], entry[1]) == destination), None)
    if destination_entry is None:
        destination_entry = [destination[0], destination[1], 0]
        filled.append(destination_entry)

    if destination_entry[2] + count > subshell_capacity(destination[1]):
        return

    source_entry[2] -= count
    destination_entry[2] += count


def configuration(z, electrons):
    """Configuration en sous-niveaux d'un atome de numéro `z` portant
    `electrons` électrons, ion compris.

    Un cation perd d'abord ses électrons les plus externes (n le plus grand,
    puis l le plus grand) : c'est pourquoi le fer perd ses 4s avant ses 3d.
    Un anion poursuit simplement le remplissage d'aufbau.
    """
    if z <= 0:
        return _plain_aufbau(electrons)

    subshells = [list(entry) for entry in subshell_configuration(z)]
    delta = electrons - z

    if delta < 0:
        _strip_outermost(subshells, -delta)
    elif delta > 0:
        _continue_filling(subshells, delta)

    return [tuple(entry) for entry in subshells if entry[2] > 0]


def _plain_aufbau(electrons):
    """Remplissage d'aufbau nu, sans exception : pour les cas sans élément."""
    remaining = max(0, electrons)
    filled = []
    for n, l in AUFBAU_ORDER:
        if remaining <= 0:
            break
        placed = min(remaining, subshell_capacity(l))
        filled.append((n, l, placed))
        remaining -= placed
    return filled


def _strip_outermost(subshells, count):
    """Retire `count` électrons en commençant par le sous-niveau le plus externe."""
    while count > 0:
        candidates = [entry for entry in subshells if entry[2] > 0]
        if not candidates:
            return
        outermost = max(candidates, key=lambda entry: (entry[0], entry[1]))
        removed = min(count, outermost[2])
        outermost[2] -= removed
        count -= removed


def _continue_filling(subshells, count):
    """Ajoute `count` électrons en suivant l'ordre de remplissage."""
    occupancy = {(entry[0], entry[1]): entry for entry in subshells}
    for n, l in AUFBAU_ORDER:
        if count <= 0:
            return
        entry = occupancy.get((n, l))
        if entry is None:
            entry = [n, l, 0]
            subshells.append(entry)
            occupancy[(n, l)] = entry
        room = subshell_capacity(l) - entry[2]
        placed = min(count, room)
        entry[2] += placed
        count -= placed


def shells_from_subshells(subshells):
    """Somme les sous-niveaux par nombre quantique principal.

    [(1,0,2), (2,0,2), (2,1,6), (3,0,2), (3,1,6), (4,0,2), (3,2,6)]
    donne [2, 8, 14, 2] : la configuration réelle du fer.
    """
    if not subshells:
        return []
    highest = max(n for n, _, _ in subshells)
    shells = [0] * highest
    for n, _, occupancy in subshells:
        shells[n - 1] += occupancy
    # Une couche interne vide n'a pas de sens physique ; on tronque à la
    # dernière couche occupée.
    while shells and shells[-1] == 0:
        shells.pop()
    return shells


def ground_state_shells(z):
    """Occupation par couche de l'atome neutre, ex. Z=26 -> [2, 8, 14, 2]."""
    return shells_from_subshells(subshell_configuration(z))


def format_configuration(subshells):
    """Rend la configuration lisible : '1s2 2s2 2p6 3s2 3p6 3d6 4s2'.

    Les sous-niveaux sont triés par (n, l) et non par ordre de remplissage,
    comme dans la notation usuelle.
    """
    ordered = sorted(subshells, key=lambda entry: (entry[0], entry[1]))
    return " ".join(
        f"{n}{SUBSHELL_LABELS[l]}{occupancy}" for n, l, occupancy in ordered
    )


def shell_configurations(subshells):
    """Notation par couche : {1: '1s2', 3: '3s2 3p6 3d6', ...}."""
    grouped = {}
    for n, l, occupancy in sorted(subshells, key=lambda entry: (entry[0], entry[1])):
        grouped.setdefault(n, []).append(f"{n}{SUBSHELL_LABELS[l]}{occupancy}")
    return {n: " ".join(parts) for n, parts in grouped.items()}
