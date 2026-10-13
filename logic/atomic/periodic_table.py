"""Table périodique de référence, Z = 1 à 118.

Sert à deux choses :
  - amorcer la collection MongoDB `elements` au premier démarrage ;
  - servir de repli en mémoire si MongoDB est injoignable, pour que le
    simulateur reste utilisable sans Docker.

`stable_isotopes` liste les nombres de masse A observés stables dans la nature
(les 251 nucléides classiquement stables). Les primordiaux radioactifs à très
longue demi-vie en sont exclus : K-40, V-50, Rb-87, In-115, La-138, Sm-147,
Lu-176, Re-187, Bi-209... Le simulateur les signale donc comme instables, ce
qui est physiquement exact.

Trois éléments légers n'ont aucun isotope stable et portent une liste vide :
le technétium (43), le prométhium (61), et tout ce qui dépasse le plomb (83+).

La configuration électronique n'est pas stockée : elle est calculée par
`electron_config` à partir de la règle de Klechkowski.
"""

# Couleurs par famille chimique : c'est ainsi qu'une vraie table périodique
# se lit, et c'est plus informatif qu'une couleur par élément.
CATEGORY_COLORS = {
    "Non-métal": "#4ADE80",
    "Gaz noble": "#7DD3FC",
    "Métal alcalin": "#FB7185",
    "Alcalino-terreux": "#FBBF24",
    "Métalloïde": "#2DD4BF",
    "Halogène": "#A78BFA",
    "Métal de transition": "#F59E0B",
    "Métal pauvre": "#94A3B8",
    "Lanthanide": "#F472B6",
    "Actinide": "#C084FC",
}

# (Z, symbole, nom, famille, isotopes stables)
_RAW = [
    (1, "H", "Hydrogène", "Non-métal", [1, 2]),
    (2, "He", "Hélium", "Gaz noble", [3, 4]),
    (3, "Li", "Lithium", "Métal alcalin", [6, 7]),
    (4, "Be", "Béryllium", "Alcalino-terreux", [9]),
    (5, "B", "Bore", "Métalloïde", [10, 11]),
    (6, "C", "Carbone", "Non-métal", [12, 13]),
    (7, "N", "Azote", "Non-métal", [14, 15]),
    (8, "O", "Oxygène", "Non-métal", [16, 17, 18]),
    (9, "F", "Fluor", "Halogène", [19]),
    (10, "Ne", "Néon", "Gaz noble", [20, 21, 22]),
    (11, "Na", "Sodium", "Métal alcalin", [23]),
    (12, "Mg", "Magnésium", "Alcalino-terreux", [24, 25, 26]),
    (13, "Al", "Aluminium", "Métal pauvre", [27]),
    (14, "Si", "Silicium", "Métalloïde", [28, 29, 30]),
    (15, "P", "Phosphore", "Non-métal", [31]),
    (16, "S", "Soufre", "Non-métal", [32, 33, 34, 36]),
    (17, "Cl", "Chlore", "Halogène", [35, 37]),
    (18, "Ar", "Argon", "Gaz noble", [36, 38, 40]),
    (19, "K", "Potassium", "Métal alcalin", [39, 41]),
    (20, "Ca", "Calcium", "Alcalino-terreux", [40, 42, 43, 44, 46, 48]),
    (21, "Sc", "Scandium", "Métal de transition", [45]),
    (22, "Ti", "Titane", "Métal de transition", [46, 47, 48, 49, 50]),
    (23, "V", "Vanadium", "Métal de transition", [51]),
    (24, "Cr", "Chrome", "Métal de transition", [50, 52, 53, 54]),
    (25, "Mn", "Manganèse", "Métal de transition", [55]),
    (26, "Fe", "Fer", "Métal de transition", [54, 56, 57, 58]),
    (27, "Co", "Cobalt", "Métal de transition", [59]),
    (28, "Ni", "Nickel", "Métal de transition", [58, 60, 61, 62, 64]),
    (29, "Cu", "Cuivre", "Métal de transition", [63, 65]),
    (30, "Zn", "Zinc", "Métal de transition", [64, 66, 67, 68, 70]),
    (31, "Ga", "Gallium", "Métal pauvre", [69, 71]),
    (32, "Ge", "Germanium", "Métalloïde", [70, 72, 73, 74]),
    (33, "As", "Arsenic", "Métalloïde", [75]),
    (34, "Se", "Sélénium", "Non-métal", [74, 76, 77, 78, 80]),
    (35, "Br", "Brome", "Halogène", [79, 81]),
    (36, "Kr", "Krypton", "Gaz noble", [78, 80, 82, 83, 84, 86]),
    (37, "Rb", "Rubidium", "Métal alcalin", [85]),
    (38, "Sr", "Strontium", "Alcalino-terreux", [84, 86, 87, 88]),
    (39, "Y", "Yttrium", "Métal de transition", [89]),
    (40, "Zr", "Zirconium", "Métal de transition", [90, 91, 92, 94]),
    (41, "Nb", "Niobium", "Métal de transition", [93]),
    (42, "Mo", "Molybdène", "Métal de transition", [92, 94, 95, 96, 97, 98]),
    (43, "Tc", "Technétium", "Métal de transition", []),
    (44, "Ru", "Ruthénium", "Métal de transition", [96, 98, 99, 100, 101, 102, 104]),
    (45, "Rh", "Rhodium", "Métal de transition", [103]),
    (46, "Pd", "Palladium", "Métal de transition", [102, 104, 105, 106, 108, 110]),
    (47, "Ag", "Argent", "Métal de transition", [107, 109]),
    (48, "Cd", "Cadmium", "Métal de transition", [106, 108, 110, 111, 112, 114]),
    (49, "In", "Indium", "Métal pauvre", [113]),
    (50, "Sn", "Étain", "Métal pauvre",
     [112, 114, 115, 116, 117, 118, 119, 120, 122, 124]),
    (51, "Sb", "Antimoine", "Métalloïde", [121, 123]),
    (52, "Te", "Tellure", "Métalloïde", [120, 122, 123, 124, 125, 126]),
    (53, "I", "Iode", "Halogène", [127]),
    (54, "Xe", "Xénon", "Gaz noble", [126, 128, 129, 130, 131, 132, 134]),
    (55, "Cs", "Césium", "Métal alcalin", [133]),
    (56, "Ba", "Baryum", "Alcalino-terreux", [132, 134, 135, 136, 137, 138]),
    (57, "La", "Lanthane", "Lanthanide", [139]),
    (58, "Ce", "Cérium", "Lanthanide", [136, 138, 140, 142]),
    (59, "Pr", "Praséodyme", "Lanthanide", [141]),
    (60, "Nd", "Néodyme", "Lanthanide", [142, 143, 145, 146, 148]),
    (61, "Pm", "Prométhium", "Lanthanide", []),
    (62, "Sm", "Samarium", "Lanthanide", [144, 150, 152, 154]),
    (63, "Eu", "Europium", "Lanthanide", [153]),
    (64, "Gd", "Gadolinium", "Lanthanide", [154, 155, 156, 157, 158, 160]),
    (65, "Tb", "Terbium", "Lanthanide", [159]),
    (66, "Dy", "Dysprosium", "Lanthanide", [156, 158, 160, 161, 162, 163, 164]),
    (67, "Ho", "Holmium", "Lanthanide", [165]),
    (68, "Er", "Erbium", "Lanthanide", [162, 164, 166, 167, 168, 170]),
    (69, "Tm", "Thulium", "Lanthanide", [169]),
    (70, "Yb", "Ytterbium", "Lanthanide", [168, 170, 171, 172, 173, 174, 176]),
    (71, "Lu", "Lutécium", "Lanthanide", [175]),
    (72, "Hf", "Hafnium", "Métal de transition", [176, 177, 178, 179, 180]),
    (73, "Ta", "Tantale", "Métal de transition", [181]),
    (74, "W", "Tungstène", "Métal de transition", [182, 183, 184, 186]),
    (75, "Re", "Rhénium", "Métal de transition", [185]),
    (76, "Os", "Osmium", "Métal de transition", [187, 188, 189, 190, 192]),
    (77, "Ir", "Iridium", "Métal de transition", [191, 193]),
    (78, "Pt", "Platine", "Métal de transition", [192, 194, 195, 196, 198]),
    (79, "Au", "Or", "Métal de transition", [197]),
    (80, "Hg", "Mercure", "Métal de transition", [196, 198, 199, 200, 201, 202, 204]),
    (81, "Tl", "Thallium", "Métal pauvre", [203, 205]),
    (82, "Pb", "Plomb", "Métal pauvre", [204, 206, 207, 208]),
    (83, "Bi", "Bismuth", "Métal pauvre", []),
    (84, "Po", "Polonium", "Métalloïde", []),
    (85, "At", "Astate", "Halogène", []),
    (86, "Rn", "Radon", "Gaz noble", []),
    (87, "Fr", "Francium", "Métal alcalin", []),
    (88, "Ra", "Radium", "Alcalino-terreux", []),
    (89, "Ac", "Actinium", "Actinide", []),
    (90, "Th", "Thorium", "Actinide", []),
    (91, "Pa", "Protactinium", "Actinide", []),
    (92, "U", "Uranium", "Actinide", []),
    (93, "Np", "Neptunium", "Actinide", []),
    (94, "Pu", "Plutonium", "Actinide", []),
    (95, "Am", "Américium", "Actinide", []),
    (96, "Cm", "Curium", "Actinide", []),
    (97, "Bk", "Berkélium", "Actinide", []),
    (98, "Cf", "Californium", "Actinide", []),
    (99, "Es", "Einsteinium", "Actinide", []),
    (100, "Fm", "Fermium", "Actinide", []),
    (101, "Md", "Mendélévium", "Actinide", []),
    (102, "No", "Nobélium", "Actinide", []),
    (103, "Lr", "Lawrencium", "Actinide", []),
    (104, "Rf", "Rutherfordium", "Métal de transition", []),
    (105, "Db", "Dubnium", "Métal de transition", []),
    (106, "Sg", "Seaborgium", "Métal de transition", []),
    (107, "Bh", "Bohrium", "Métal de transition", []),
    (108, "Hs", "Hassium", "Métal de transition", []),
    (109, "Mt", "Meitnérium", "Métal de transition", []),
    (110, "Ds", "Darmstadtium", "Métal de transition", []),
    (111, "Rg", "Roentgenium", "Métal de transition", []),
    (112, "Cn", "Copernicium", "Métal de transition", []),
    (113, "Nh", "Nihonium", "Métal pauvre", []),
    (114, "Fl", "Flérovium", "Métal pauvre", []),
    (115, "Mc", "Moscovium", "Métal pauvre", []),
    (116, "Lv", "Livermorium", "Métal pauvre", []),
    (117, "Ts", "Tennesse", "Halogène", []),
    (118, "Og", "Oganesson", "Gaz noble", []),
]


def table_position(z):
    """Place l'élément dans la grille d'affichage 18 colonnes.

    Les lanthanides et les actinides sont sortis sur deux lignes séparées
    (9 et 10), comme sur toute table périodique imprimée.
    """
    if z == 1:
        return 1, 1
    if z == 2:
        return 1, 18
    if z <= 10:
        return (2, z - 2) if z <= 4 else (2, z + 8)
    if z <= 18:
        return (3, z - 10) if z <= 12 else (3, z)
    if z <= 36:
        return 4, z - 18
    if z <= 54:
        return 5, z - 36
    if 57 <= z <= 71:
        return 9, z - 54          # Lanthanides : colonnes 3 à 17
    if z <= 56:
        return 6, z - 54
    if z <= 86:
        return 6, z - 68          # Hf (72) en colonne 4
    if 89 <= z <= 103:
        return 10, z - 86         # Actinides : colonnes 3 à 17
    if z <= 88:
        return 7, z - 86
    return 7, z - 100             # Rf (104) en colonne 4


def _build():
    elements = []
    for z, symbol, name, category, isotopes in _RAW:
        row, col = table_position(z)
        elements.append({
            "z": z,
            "symbol": symbol,
            "name": name,
            "category": category,
            "color_hex": CATEGORY_COLORS[category],
            "stable_isotopes": isotopes,
            "table_row": row,
            "table_col": col,
        })
    return elements


ELEMENTS = _build()
MAX_Z = max(element["z"] for element in ELEMENTS)
