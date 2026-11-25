# common — les outils partagés

Utilisés par plusieurs écrans. Ce paquet ne dépend d'aucun écran.

| Classe | Rôle |
|---|---|
| `Visuals` | Outils de rendu sans état : couleurs physiques (longueur d'onde → couleur), formatage des nombres. |
| `Plan` | Le style commun : la scène est le sujet, l'interface un boîtier graphite neutre. Les jetons (fonds, trois encres, trait, une alerte, tient / échoue), IBM Plex Sans en quatre graisses (légère, normale, moyenne, demi-grasse), la largeur des colonnes ; les briques des panneaux (section, section repliable, ligne intitulé–valeur, mesure en grand, commande segmentée, curseur dont la piste se remplit, bouton d'icône) ; les explications au survol (`explain` : un titre, une phrase, une formule ; `why` : le petit « ? ») ; `sentence` remet en phrase les légendes de l'ancien style en capitales. Avec `resources/genesis.css`, où chaque laboratoire a sa couleur d'accent. |
| `Icons` | Les icônes, dessinées au trait sur une grille de 16 px (accueil, guide, image, panneaux, plein écran, lecture, étapes, zoom…), et la marque G_ (`logo`, voir `Brand`). |
| `Brand` | La marque de Genesis (`assets/brand`) : le G_ et le logotype GENESIS_ lus tels quels dans leurs SVG (le G blanc, ses franges cyan et violette, le curseur), découpés en lettres rangées par case pour être animés ; `node` les pose dans l'interface, `draw` les dessine sur un canevas. Les traits du G_ sont des glitchs figés : chaque frange est le blanc décalé (14 unités pour le G_, 12 pour le logotype, lus dans les SVG) et un trait est une tranche d'une frange qui a glissé plus loin (`MARK_TRAITS` les refait au pixel près). `glitchAt` fait glitcher la marque par bouffées (toutes les 3,2 s environ, 3 à 5 pas de 55 ms : de nouvelles tranches de franges, ou des déchirures de tout le dessin) ; au repos, la marque telle que son SVG la dessine. |
| `Guide` | Le contenu du guide d'un laboratoire : ce qu'on y fait, par où commencer, ses zones (un nœud, un titre, une phrase), ses touches. |
| `Tabs` | Des pages dans une colonne, une seule montrée : onglets soulignés (des mesures) ou commande segmentée (un choix) ; chaque page défile seule. |
| `LabCamera` | La caméra des laboratoires : orbite autour d'une cible, avec inertie, et projection en perspective. |
