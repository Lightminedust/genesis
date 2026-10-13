# engine — la liaison avec le moteur

| Classe | Rôle |
|---|---|
| `EngineClient` | La liaison gRPC bidirectionnelle avec le moteur Python : elle envoie les actions, reçoit les frames, vérifie la version du protocole (`PROTOCOL_VERSION`). |

Les messages eux-mêmes (`UniverseFrame`, `UserAction`…) sont générés depuis `core/genesis.proto`, dans le paquet `com.studio.genesis.grpc`.
