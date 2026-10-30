# sim — le client JavaFX

Le client dessine ce que le moteur calcule. Il ne fait aucune physique : il reçoit des frames par gRPC et envoie les actions de l'utilisateur.

## Lancer

Le moteur doit tourner (voir `../logic/README.md`).

```bash
cd sim
gradlew run                                                # vise localhost:50051
gradlew run -PgenesisHost=192.168.1.20 -PgenesisPort=50052 # un autre moteur
gradlew test                                               # les tests
```

## Où sont les choses

| Chemin | Contenu |
|---|---|
| `app/build.gradle` | La construction : Java 21, JavaFX 21, gRPC, et les tâches d'aperçu. |
| `app/src/main/java/org/genesis/` | Le code, rangé en paquets (tableau ci-dessous). Chaque paquet a son README. |
| `app/src/main/resources/genesis.css` | Les styles de l'interface. |
| `app/src/test/java/org/genesis/` | Les tests et les aperçus, rangés dans les mêmes paquets que le code. |
| `gradlew`, `gradle/`, `settings.gradle` | Gradle (aucune installation nécessaire). |

Les classes gRPC (`com.studio.genesis.grpc`) sont générées depuis `../core/genesis.proto` à la compilation.

## Les paquets

| Paquet | Contenu |
|---|---|
| [`app`](app/src/main/java/org/genesis/app/README.md) | L'application : la fenêtre, les barres, le journal, la timeline, la table périodique. |
| [`engine`](app/src/main/java/org/genesis/engine/README.md) | La liaison gRPC avec le moteur. |
| [`common`](app/src/main/java/org/genesis/common/README.md) | Les outils partagés : couleurs, formats, caméra des laboratoires. |
| [`atom`](app/src/main/java/org/genesis/atom/README.md) | L'écran de l'atome : la carte 3D, le nuage, le noyau, la lumière. |
| [`matter`](app/src/main/java/org/genesis/matter/README.md) | Les échelles molécule et matière. |
| [`fusion`](app/src/main/java/org/genesis/fusion/README.md) | Les laboratoires de fusion : chambre de collision, étoile. |
| [`subatomic`](app/src/main/java/org/genesis/subatomic/README.md) | Le monde subatomique : collisions, atelier de hadrons, Modèle Standard, partons. |
| [`chain`](app/src/main/java/org/genesis/chain/README.md) | La réaction en chaîne : masse critique, cascade de neutrons, excursion, bac à sable du rendement (écran masqué). |
| [`molecule`](app/src/main/java/org/genesis/molecule/README.md) | Le laboratoire moléculaire : densité électronique en volume, forces, établi, dynamique, réacteur, vibrations. |
| [`mascot`](app/src/main/java/org/genesis/mascot/README.md) | Genesis, la guide. Mise de côté : le code est là, elle n'est pas affichée. |

## Les aperçus

Ils rendent un écran dans un fichier image, sans ouvrir la fenêtre. Les images vont dans `../artifacts/`.

| Tâche | Ce qu'elle rend |
|---|---|
| `gradlew dashboardPreview` | L'écran entier de l'atome. |
| `gradlew atomPreview` | L'atome en 3D, depuis les frames de `artifacts/frames`. |
| `gradlew journalPreview` | Le journal. |
| `gradlew fusionPreview`, `fusionPlaybackPreview`, `fusionLivePreview` | La chambre de collision. |
| `gradlew labPreview`, `partonPreview` | Le monde subatomique. |
| `gradlew mascotPreview` | Genesis. |
