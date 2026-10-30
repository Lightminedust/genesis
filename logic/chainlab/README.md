# chainlab — la réaction en chaîne

| Fichier | Rôle |
|---|---|
| `lab.py` | Le laboratoire : réglages, fil de calcul (transport, rayon critique, tir), régimes. |
| `transport.py` | Le transport des neutrons dans une sphère à coquilles : probabilités de première collision, k_eff, flux, flux adjoint, Λ. |
| `library.py` | La bibliothèque multigroupe (ENDF/B-VIII.0, 44 groupes) et les sections efficaces d'un matériau (autoprotection, correction de transport). |
| `materials.py` | Les cœurs et les réflecteurs, leurs densités et compositions ; la source de fission spontanée. |
| `kinetics.py` | La cinétique ponctuelle, l'équation inhour, l'excursion avec contre-réaction (dilatation, vaporisation, expansion). |
| `cascade.py` | Les neutrons tirés un par un dans la vraie géométrie (la nature de chaque point : diffusion, surface, fuite, fin) ; la loi de Terrell pour ν ; la probabilité qu'un neutron allume une chaîne sans fin. |
| `sandbox.py` | Le bac à sable du rendement : courbe d'Aston, Q de la fission et de la fusion, tableau comparatif. |
| `frame.py` | Le laboratoire dans la frame envoyée au client (`ChainState`). |
| `text.py` | Les nombres des phrases, à la française. |
| `data/groups.npz` | Les constantes de groupes, produites par `tools/build_chain_groups.py`. |

Le modèle et sa validation (Godiva, Jezebel, Flattop) sont dans `docs/CHAIN_MODEL.md`. Côté client :
`sim/app/src/main/java/org/genesis/chain/`.

Reconstruire la bibliothèque (h5py requis, fichiers HDF5 d'OpenMC dans un dossier) :

```bash
venv\Scripts\python tools/build_chain_groups.py <dossier des .h5> U233 U234 U235 U238 Np237 Pu239 Pu240 Pu241 Am241 Th232 Be9 C12 Ga69 Ga71 Fe56 Pb208
```
