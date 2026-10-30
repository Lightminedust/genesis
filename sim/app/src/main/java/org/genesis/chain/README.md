# chain — le laboratoire de la réaction en chaîne

| Classe | Rôle |
|---|---|
| `ChainView` | L'écran : la scène et ses gestes (clic, bord, jetons, ralenti), les générations, « ⓘ Les chiffres », le bac à sable. |
| `ChainScene` | La scène 3D : volume de noyaux, aura de k_eff, rayon critique, réflecteur, neutrons, chocs (diffusion, γ, fission), tempête, corps noir. |
| `ChainBoard` | Le tableau de bord : k_eff en grand, destin des neutrons, mesures, courbe k_eff(R), équivalences. |
| `YieldView` | Le bac à sable : courbe d'Aston, jauge Q, bilan des masses, tableau U-235 · Fe-56 · Fe-60 · D + T. |
| `ChainFormat` | Les grandeurs écrites pour être lues (puissances de dix, unités, virgule française). |

Le moteur correspondant : `logic/chainlab/`. Le modèle : `docs/CHAIN_MODEL.md`. Aperçu sans serveur :
`sim/app/src/test/java/org/genesis/chain/ChainPreview.java`, avec les frames de `logic/tools/export_chain_frames.py`.
