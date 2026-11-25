# engine — la liaison avec le moteur

| Classe | Rôle |
|---|---|
| `BundledEngine` | Le moteur Python embarqué par le paquet (PyInstaller `--onedir`, un seul processus) : lancé sur un port libre de `127.0.0.1`, arrêté avec la fenêtre (`App.stop`) et à la sortie de la JVM (crochet d'arrêt) ; il reçoit `GENESIS_PARENT_PID` et s'arrête seul si l'application meurt sans l'arrêter (`engine.watch_parent`). |
| `EngineClient` | La liaison gRPC bidirectionnelle avec le moteur Python : elle envoie les actions, reçoit les frames, vérifie la version du protocole (`PROTOCOL_VERSION`). |

Les messages eux-mêmes (`UniverseFrame`, `UserAction`…) sont générés depuis `core/genesis.proto`, dans le paquet `com.studio.genesis.grpc`.
