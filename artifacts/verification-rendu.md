# Vérification du raccordement du rendu — 4 octobre 2026

Les 40 fichiers Python de `logic` ont les mêmes empreintes SHA-256 qu'avant
cette intervention (`render-before-stream-fix/python-sha256.json`).
Les sources Java initiales sont sauvegardées dans `render-before-stream-fix`.

`gradle test` : **52 tests, zéro échec, zéro erreur**. Les nouveaux contrôles
couvrent la conservation d'une trajectoire après un message de statut, une
retransmission, la remise à zéro explicite, l'interpolation position/vitesse,
la visibilité du PixelBuffer, la déviation des traceurs et les contributions
électriques et magnétiques des charges Z = 1 à 118.

Le contrôle `fusionLivePreview` utilise le moteur Python inchangé, le vrai
client gRPC et l'AnimationTimer JavaFX. Il envoie des réglages pendant la lecture
et vérifie qu'ils ne modifient pas son temps ou ne renvoient pas à la chambre vide.

| Couple | Énergie | Issue reçue | Mises à jour reçues pendant la lecture |
| --- | ---: | --- | ---: |
| Li-7 + Og-294 | 40 MeV | Quasi-fission | 14 |
| D + T | 64 keV | He-4 + neutron | 19 |
| U-238 + Au-197 | 1 GeV | Quasi-fission et produits | 20 |

Ces essais sont enregistrés dans `live-li-og`, `live-dt` et `live-u-au-final`
(frame `.pb`, images et `playback.csv`). Les essais D–T et U–Au ont utilisé
l'option existante de capture retenue. Il ne s'agit pas de simulations de toutes
les paires du tableau : les tests sur les 118 charges vérifient leur contribution au champ.

Le rebond Li-7/Og-294 calculé avec la graine 7 est également relu depuis
`li7-og294-baseline.pb`. Son approche passe de 0,106676 c à 0,000018 c.
Les images sont dans `li7-og294-repaired`.

Animations encodées à partir des captures JavaFX, sans retouche :
- [Rebond lithium–oganesson](rebond-li-og-repare.webp)
- [Collision uranium–or](collision-u-au-reparee.webp)

Les vitesses des traceurs et les couleurs restent des conventions de lecture.
Les traceurs interrogent le champ 3D ; la carte bicolore conserve sa projection
des halos dans le plan de l'image. Aucun nouveau modèle nucléaire n'a été introduit.
