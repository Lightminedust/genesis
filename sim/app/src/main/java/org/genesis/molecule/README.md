# molecule — le laboratoire moléculaire

| Classe | Rôle |
|---|---|
| `MoleculeView` | L'écran : l'établi (molécules, réacteur, éléments, charge, affichages), les lectures (bilan d'énergie, orbitales en cible, espèces, carte de l'atome et son isotope, vibrations), la dynamique et la courbe d'énergie ; les gestes (tourner, saisir et tirer un atome, ajouter, clic droit). Une commande reste affichée telle qu'on l'a donnée jusqu'à ce que le moteur la confirme ; les panneaux de chiffres suivent à 8 images/s pendant le mouvement. |
| `MoleculeScene` | La scène épurée : halo de polarisation (en option la carte de potentiel à 0,002 u.a.), signes δ±, sphères de verre (rayon covalent, liseré selon la charge ; celle de devant voile celle de derrière), noyaux incandescents, arcs intérieurs (liaisons et dipôle atomique), liaisons dessinées par leurs paires d'électrons localisées : trait pour une paire sur deux atomes (indice, lobes π orientés comme la densité π, force interne, grains tirés de la densité de la paire), étoile de Lipscomb pour une paire sur trois atomes ou plus, pointillés pour deux ions ; points des forces sans paire partagée : vers le milieu pour une attraction, orange et vers les atomes pour une répulsion, vitesse et éclat selon la force ; légende des signes présents ; en option le champ. Dessine le net et les deux calques du bloom. |
| `MoleculeHalo` | Le halo : la densité projetée de chaque molécule (électrons par Å² le long du regard), somme des nuages des atomes libres à l'échelle de leurs électrons, calculée à chaque image en pleine résolution ; contour 0,3 e/Å² au pixel près, couleur des charges, brume de la traîne, reflet ; bord du halo pour poser les signes δ. |
| `DeformationMap` | La vue Δρ : la déformation projetée le long du regard (e/Å²), en courbes de niveau (marching squares, une courbe par doublement), trait plein où la molécule a gagné des électrons, tirets où elle en a perdu. |
| `OrbitalSurface` | La vue ψ : les lobes à 85 % de \|ψ\|² (bleu ψ > 0, orange ψ < 0), maillage fermé par tétraèdres marchants, sommets partagés, normales au gradient ; construit hors du fil de l'interface. La scène choisit l'axe de vue (`cloudView` : le nuage le plus mince, incliné de 40° quand les phases se recouvriraient). |
| `OrbitalLayer` | La couche 3D sous la scène : les lobes pleins et lisses (bleu ψ > 0, rouge ψ < 0, lumière principale et d'appoint), le squelette en boules et bâtonnets (une, deux ou trois par liaison selon l'indice de Mayer), avec la caméra exacte de la scène. |
| `OrbitalDots` | La vue ψ sans carte graphique 3D : 9 000 positions tirées de \|ψ\|² avec le signe de ψ. |
| `EnergyBalance` | 06 · le bilan d'énergie : la cohésion, la balance de ses termes (chacun moins sa valeur dans les atomes isolés : ce qui lie, ce qui coûte, leur somme est l'énergie de liaison), l'agitation des noyaux. |
| `EnergyTarget` | 07 · les niveaux des orbitales en cible : rayon linéaire en énergie, cercles pleins portant leurs électrons, HOMO, écart, LUMO, vide ; survol et clic. |
| `AtomMap` | 09 · la carte de l'atome choisi : l'essentiel (la force nette seule, des traits colorés et épaissis par force, les voisins liés / non liés avec leur raison calculée), par voisin (une flèche par paire, somme exacte de la force nette) ou par terme (électrons, noyaux, London, main), et le détail de chaque voisin. |
| `MoleculeSketch` | La vignette « Schéma 2D » : la formule topologique de la molécule, ses liaisons multiples, ses charges, l'atome choisi, une ligne de mesures et une consigne en temps réel. |
| `DensityGrid` | Une grille du moteur (ρ_at et ρ_ov, Δρ ou ψ) remise en valeurs physiques ; interpolation trilinéaire, gradient, raffinement cubique. |
| `MoleculeFormat` | Les nombres écrits à la française. |

Le moteur correspondant : `logic/moleculelab/`. Le modèle : `docs/MOLECULE_MODEL.md`. Aperçu sans serveur :
`sim/app/src/test/java/org/genesis/molecule/MoleculePreview.java`, avec les frames de
`logic/tools/export_molecule_frames.py`.
