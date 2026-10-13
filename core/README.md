# core — le contrat partagé

Ce dossier contient le seul fichier que le moteur (Python) et le client (Java) ont en commun.

| Fichier | Rôle |
|---|---|
| `genesis.proto` | Le contrat gRPC : les messages échangés (`UniverseFrame`, `UserAction`, `SimEvent`…) et le service. |
| `atomicsim.proto.bak` | Une très ancienne version du contrat. Elle ne sert plus. |

## Quand on modifie `genesis.proto`

1. Augmenter le numéro de version aux deux bouts : `PROTOCOL_VERSION` dans `logic/engine.py` et dans `sim/app/src/main/java/org/genesis/engine/EngineClient.java`. Le client refuse un moteur d'une autre version.
2. Régénérer les fichiers Python, depuis `logic/` :
   ```bash
   venv\Scripts\python -m grpc_tools.protoc -I../core --python_out=. --grpc_python_out=. ../core/genesis.proto
   ```
3. Côté Java, rien à faire : Gradle régénère les classes à la compilation (paquet `com.studio.genesis.grpc`).
4. Relancer le moteur.
