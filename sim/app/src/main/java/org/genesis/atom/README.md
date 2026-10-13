# atom — l'écran de l'atome

| Classe | Rôle |
|---|---|
| `SimCanvas` | La carte : l'atome à sa vraie échelle et ce qui lui arrive (échelles, événements, loupe, plongée, gouffre). |
| `OrbitalCloud` | Le nuage électronique en 3D, tel que la mécanique quantique le décrit. |
| `NuclearDrop` | Le noyau vu comme une goutte de liquide quantique, avec ses échanges de pions. |
| `Camera3D` | La caméra des vues 3D de l'atome et du noyau. |
| `PhotonPacket` | Le photon comme un paquet d'onde, construit à partir de sa longueur d'onde. |
| `Photon3DLayer` | Les photons en JavaFX 3D, posés sur la scène de l'atome. |
| `LightPanel` | L'onglet Lumière : niveaux d'énergie et spectre. |
| `QuantumView` | L'onglet Orbitale : l'orbitale de valence. |
| `NucleusPanel` | L'onglet Noyau : sa place dans la vallée de stabilité, sa demi-vie. |
| `StabilityChart` | La vallée de stabilité. |

Le moteur correspondant : `logic/atomic/` et `logic/nuclear/`. Tests et aperçus : `sim/app/src/test/java/org/genesis/atom/`.
