# nuclear — le noyau

| Fichier | Rôle |
|---|---|
| `nucleus.py` | La physique du noyau : énergie de liaison, taille, agencement, désintégrations. |
| `nuclear_data.py` | Les données évaluées, lues dans les bibliothèques de référence (radioactivedecay, mendeleev). |
| `nuclides.py` | Les radio-isotopes : ce que la mesure dit de chaque noyau. |
| `halflife.py` | La demi-vie : mesurée quand on la connaît, estimée sinon (et dite comme telle). |

Côté client : les panneaux du noyau sont dans `sim/app/src/main/java/org/genesis/atom/` (`NucleusPanel`, `StabilityChart`, `NuclearDrop`).
