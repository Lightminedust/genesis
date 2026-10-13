# fusionlab — les laboratoires de fusion

| Fichier | Rôle |
|---|---|
| `fusion.py` | Le laboratoire : deux noyaux quelconques, le potentiel, l'effet tunnel, la cascade d'évaporation, la fission. |
| `fusion_dynamics.py` | La collision intégrée pas à pas : approche, contact, soudure, issues et cinématique des produits. |
| `collider.py` | La chambre de collision : réactions mesurées, cinématique à deux corps. |
| `lawson.py` | Le plasma autour du choc : section efficace, réactivité ⟨σv⟩, critère de Lawson. |
| `reactor.py` | Le réacteur : un tokamak aux dimensions d'ITER. |
| `star.py` | Le cœur d'étoile : réseau de réactions, ionisation, pression des électrons. |

Le modèle est expliqué dans `docs/FUSION_MODEL.md`. Côté client : `sim/app/src/main/java/org/genesis/fusion/`.
