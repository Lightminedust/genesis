# simulator — la session

| Fichier | Rôle |
|---|---|
| `simulation.py` | Le simulateur : une horloge, des règles, et ce qui en découle. La classe `Session` porte l'état d'une partie (l'atome, le temps, les événements) et appelle les domaines (`atomic`, `nuclear`, `fusionlab`…). |
| `repository.py` | L'accès à la table périodique : MongoDB quand elle est là, sinon les données en mémoire. |

`engine.py` crée une `Session` par client et lui passe les actions reçues.
