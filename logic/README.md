# logic — le moteur Python

Le moteur fait tourner le temps, applique les règles de la physique et envoie au client ce qui arrive. Il ne dessine rien.

## Lancer

```bash
cd logic
python -m venv venv && venv\Scripts\pip install -r requirements.txt    # une seule fois
venv\Scripts\python engine.py                                          # le serveur gRPC, port 50051
```

Avec Docker : `docker compose up --build` à la racine du dépôt (voir `Dockerfile` et `entrypoint.sh`).

## Ce qui est à la racine

| Fichier | Rôle |
|---|---|
| `engine.py` | Le point d'entrée : le serveur gRPC. Il lit les actions du client, avance la session, remplit les frames. Une erreur de calcul est journalisée et la frame sautée : elle ne coupe plus la connexion. |
| `genesis_pb2.py`, `genesis_pb2_grpc.py` | Générés depuis `../core/genesis.proto`. Ne pas les modifier à la main. |
| `requirements.txt`, `requirements-chemistry.txt` | Les dépendances (la chimie quantique, plus lourde, est à part). |
| `Dockerfile`, `entrypoint.sh` | L'image du moteur ; l'entrypoint régénère les fichiers gRPC au démarrage. |

## Les dossiers

Chaque dossier est un paquet Python et a son README.

| Dossier | Contenu |
|---|---|
| [`simulator/`](simulator/README.md) | La session (l'horloge et les règles) et l'accès à la table périodique. |
| [`common/`](common/README.md) | Ce que tout le monde utilise : mise en forme des grandeurs, fiches des particules. |
| [`atomic/`](atomic/README.md) | L'atome : électrons, orbitales, lumière, table périodique. |
| [`nuclear/`](nuclear/README.md) | Le noyau : liaison, données évaluées, radio-isotopes, demi-vies. |
| [`molecular/`](molecular/README.md) | La molécule (chimie quantique) et la matière (dynamique moléculaire). |
| [`fusionlab/`](fusionlab/README.md) | Les laboratoires de fusion : collision, plasma, réacteur, étoile. |
| [`quantumlab/`](quantumlab/README.md) | Le monde subatomique : Modèle Standard, hadrons, collisions, nucléon, partons. |
| [`tools/`](tools/README.md) | Outils en ligne de commande (exporter des frames pour les aperçus du client). |
| [`tests/`](tests/README.md) | Les tests : chaque règle confrontée à une valeur mesurée. |

## Qui importe qui

```
engine.py  →  simulator/  →  atomic/, nuclear/, molecular/, fusionlab/, quantumlab/  →  common/
```

Un dossier de droite n'importe jamais un dossier de gauche. Les imports s'écrivent avec le nom du dossier :

```python
from atomic import spectra
from fusionlab.fusion_dynamics import FusionExperiment
```

## Tests et outils

Toutes les commandes se lancent depuis `logic/`.

```bash
venv\Scripts\python -m unittest discover -s tests -t .       # tous les tests (une dizaine de minutes)
venv\Scripts\python -m unittest tests.test_fusion            # un seul fichier
venv\Scripts\python -m tools.export_atom_frames ..\artifacts\frames
```
