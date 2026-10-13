# atomic — l'atome

| Fichier | Rôle |
|---|---|
| `atom.py` | La machine à états d'un atome : couches, niveaux, orbitale, lien avec son noyau. |
| `electron_config.py` | La configuration électronique des atomes neutres (Klechkowski et ses exceptions). |
| `slater.py` | Les règles de Slater : charge effective, taille et énergie des orbitales. |
| `orbitals.py` | La forme réelle du nuage électronique de valence. |
| `hydrogen.py` | La dynamique exacte d'un système à un électron (H, He⁺, Li²⁺…). |
| `spectra.py` | Niveaux d'énergie, raies spectrales et photons. |
| `light_interaction.py` | Un photon face à un atome : absorption, traversée, photo-ionisation. |
| `atomic_data.py` | Les données mesurées : ionisations, affinités, raies de résonance. |
| `periodic_table.py` | La table périodique de référence, Z = 1 à 118. |

Côté client, l'écran correspondant est dans `sim/app/src/main/java/org/genesis/atom/`.
