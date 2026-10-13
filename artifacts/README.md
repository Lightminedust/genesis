# artifacts — images et frames d'aperçu

Ce dossier ne contient pas de code. On y trouve ce que les aperçus produisent et ce dont ils ont besoin.

| Contenu | D'où ça vient |
|---|---|
| `frames/` | De vraies frames du moteur, écrites par `python -m tools.export_atom_frames` (depuis `logic/`). Les aperçus du client les lisent. |
| `*.pb`, `*.json` | Des collisions calculées, écrites par `python -m tools.export_collision`. |
| `dashboard/` | L'écran de l'atome, rendu par `gradlew dashboardPreview`. |
| `atom3d/` | L'atome en 3D, rendu par `gradlew atomPreview`. |
| `mascot*.png` | Genesis, rendue par `gradlew mascotPreview`. |
| Les autres images et dossiers | Des rendus gardés pour comparer avant et après une correction. |

Tout peut être régénéré. Rien ici n'est lu par l'application quand elle tourne.
