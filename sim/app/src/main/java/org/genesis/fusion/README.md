# fusion — les laboratoires de fusion

| Classe | Rôle |
|---|---|
| `ReactorView` | La chambre de collision : deux noyaux du tableau, les réglages, le bilan. C'est l'écran principal. |
| `FusionRenderer` | La scène de la collision, dessinée avec les primitives de JavaFX. |
| `FusionField` | Les champs locaux calculés à partir de l'état (unités fm, m/s, T, V/m). |
| `FusionDashboard` | Le tableau de bord de la collision : jauges et barres. |
| `FusionReadouts` | Les calculs montrés pendant la collision, étape par étape. |
| `FormulaCard` | La loi de l'instant, posée en grand sur la scène. |
| `PlasmaInset` | Le plasma autour du choc, à son échelle propre. |
| `ResultView` | Le bilan interactif d'une collision, en trois pages. |
| `CollisionTimeline` | Le calcul découpé en étapes de lecture. |
| `CollisionPicker` | Le choix des deux noyaux dans l'inventaire du moteur. |
| `StarView` | Le cœur d'étoile : un plasma où les noyaux fusionnent. |
| `FusionLab` | L'ancien laboratoire 3D (plus relié à l'interface). |

Le moteur correspondant : `logic/fusionlab/`. Le modèle : `docs/FUSION_MODEL.md`. Tests et aperçus : `sim/app/src/test/java/org/genesis/fusion/`.
