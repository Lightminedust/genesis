# common — ce que tout le monde utilise

| Fichier | Rôle |
|---|---|
| `units.py` | La mise en forme des grandeurs pour l'affichage (durées, longueurs, énergies). |
| `particles.py` | Le zoo de particules : masse, charge, spin, durée de vie de chacune. |
| `elements_db.py` | La base SQLite de mendeleev, une seule connexion verrouillée : la session de chaque client et le chargement des étoiles la lisent en même temps sans se corrompre. |

Ce dossier n'importe aucun autre dossier du moteur.
