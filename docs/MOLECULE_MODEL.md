# Le laboratoire moléculaire — électrons, liaisons, forces

Le laboratoire (menu **Laboratoires → ⌬ Laboratoire moléculaire**) pose des atomes sur un établi et calcule leurs
électrons. Personne ne décide que deux atomes sont liés, ni sous quel angle : à chaque position des noyaux, la
méthode quantique GFN1-xTB rend l'énergie et la force sur chaque noyau, et la géométrie, les liaisons, les
vibrations et les réactions en découlent.

Code : `logic/moleculelab/` (moteur), `sim/app/src/main/java/org/genesis/molecule/` (client).

## La méthode : GFN1-xTB

GFN1-xTB (Grimme, Bannwarth, Shushkov, *J. Chem. Theory Comput.* 13, 1989, 2017) est une méthode de liaisons
fortes quantique : les électrons de valence sont décrits dans une base minimale d'orbitales de Slater (chacune
développée en gaussiennes, STO-NG), et l'énergie est

| Terme | Formule | Ce qu'il représente |
|---|---|---|
| bande | Σ P_μν H⁰_μν, H⁰_μν = ½(h_μ + h_ν) · K · π(R) · S_μν | deux orbitales n'interagissent que si elles se recouvrent (S) ; les niveaux h sont décalés par le nombre de voisins |
| électrostatique | ½ Σ q_l γ_ll' q_l', γ = 1/√(R² + η̄⁻²) (Klopman–Ohno) | les charges de couche (Mulliken) se repoussent ou s'attirent |
| troisième ordre | ⅓ Σ Γ_A q_A³ | la dureté d'un atome change avec sa charge |
| répulsion | Σ Z_A Z_B / R · exp(−√(α_A α_B) R^1,5) | les cœurs se repoussent à courte distance (Pauli, noyaux) |
| dispersion | D3(BJ), C6 selon la coordination | l'attraction de London |
| liaison halogène | terme classique (Cl, Br, I) | l'interaction X···O/N |

Les charges sont auto-cohérentes, les occupations lissées par Fermi à 300 K (comme xtb) ; voir « Le cycle
auto-cohérent » plus bas pour les cas difficiles (liaisons qui cassent, fragments séparés, charges qui s'emballent).
Les forces sont les dérivées analytiques F = −∇E, calculées terme par terme.

**Qui agit sur qui.** Chaque terme du gradient est une somme sur les paires d'atomes (recouvrements dS_μν/dR pour
μ ∈ A, ν ∈ B ; facteur π(R) ; nombre de coordination, compté paire par paire ; électrostatique γ_AB ; répulsion ;
dispersion), si bien que la force sur A se décompose exactement en ce que lui fait chaque autre atome B, avec
action = −réaction (vérifié à 10⁻¹² ; seule la liaison halogène, à trois corps, reste globale). Le laboratoire en
montre la composante le long de l'axe A–B : dans CH₄ à l'équilibre, chaque C–H tire de +1,0 eV/Å (électrons +4,6,
noyaux −3,6) et chaque paire H···H se repousse de −0,41 eV/Å ; étirée de 15 %, la liaison tire de +4,3 eV/Å ;
comprimée de 10 %, elle repousse (−4,1 eV/Å).

**Tout le tableau, jusqu'au radon.** GFN1-xTB est paramétré de H à Rn (Z = 1 à 86) ; l'établi les propose tous
(tableau périodique). Vérifié contre dxtb aussi pour K, Cu, Zn, Sn, I, Xe, Au, Pb et Fe (10⁻⁷ Eh). Pour TiCl₄,
c'est dxtb qui diverge (énergie −61 Eh, gradient 10⁴) ; notre calcul converge vers une solution saine (Ti +0,68 e).
Au-delà du radon, aucun paramètre : le laboratoire le dit et ne calcule rien.

Paramètres : `data/gfn1-xtb.toml` (xtb / tblite, LGPL-3.0). Tables (STO-NG, rayons, C6 de référence de D3) :
`data/xtb-data.npz`, extraites par `logic/tools/build_xtb_data.py`.

**Vérification.** L'implémentation (numpy) est comparée à dxtb 0.4.0, l'implémentation de référence en PyTorch
(`logic/tests/test_moleculelab.py`, valeurs stockées) : énergies à 3·10⁻⁷ Eh, gradients à 10⁻⁶ Eh/Å, sur l'eau, le
méthane, HCl, l'éthylène, PH₃Cl, le dimère d'eau et une eau étirée. Le gradient analytique est aussi comparé aux
différences finies de l'énergie.

## Ce que le laboratoire en fait

| Geste | Calcul |
|---|---|
| choisir une molécule | coordonnées de départ grossières, puis relaxation FIRE (Bitzek et al. 2006) jusqu'à ce que la force maximale tombe sous 10⁻³ Eh/Å. FIRE est une descente, pas une trajectoire : tous les atomes y ont la même masse fictive (1 u), si bien que l'or descend aussi vite que l'hydrogène (Au₆ construit atome par atome : 307 pas au lieu de 1 396 avec les vraies masses, même minimum). La relaxation trouve le minimum **le plus proche** — comme toute optimisation de géométrie (ORCA, Gaussian) ; pour chercher une forme plus stable, chauffer au thermostat puis relaxer (recuit) |
| construire atome par atome | l'atome est posé dans la direction la plus libre autour de l'atome choisi (avec quelques degrés de désordre, comme l'agitation thermique), à la somme des rayons covalents ; la relaxation décide du reste. C + 4 H donne le tétraèdre (109,47°) |
| tirer un atome | un ressort k = 0,08 Eh/Å² entre la souris et l'atome ; l'autre main tient le reste par son centre de masse. La liaison s'étire, l'énergie monte, elle casse |
| thermostat | dynamique de Verlet (pas de 0,25 fs) et thermostat CSVR (Bussi, Donadio, Parrinello 2007), τ = 50 fs, plus un bain de Langevin faible sur chaque atome (τ = 500 fs). Le CSVR ne règle que l'énergie totale en multipliant toutes les vitesses par un même facteur : il ne redistribue rien. Dans le réacteur, l'énergie qu'une géométrie de départ rendait à ses vibrations était retirée à tout le monde, translations comprises, et seuls des chocs rares la rendaient — Cl₂ avançait à 0,1–0,5 fois son agitation thermique pendant 4 ps à 1 500 K (4 graines). Avec le bain : 0,46–0,97 sur 2 ps (une seule molécule : ±0,3 attendu). Les deux gardent la loi canonique ; l'énergie totale en dynamique libre tient à 1 meV près sur 2 ps (aucun chauffage numérique). Sans paroi, le bain ne pousse pas la molécule (centre de masse retiré) ; dans une enceinte, le centre de masse est un degré de liberté comme les autres (sans cela, H₂ seule à 3 000 K n'a pas touché la paroi en 10 ps) |
| libre | Verlet sans thermostat : l'énergie totale se conserve (courbe jaune) |
| étincelle | de l'énergie cinétique déposée sur un atome et ses voisins à 1,8 Å, quantité de mouvement conservée |
| réacteur | plusieurs molécules dans une sphère à paroi raide, ½ k (r + ρ − R)², k = 0,5 Eh/Å², ρ le rayon où la densité de l'atome libre tombe à 0,002 e/bohr³ (H 1,24, O 1,40, Cl 1,71 Å) : la paroi touche le nuage, pas le noyau, et la sphère dessinée est le bord des nuages. Sur les noyaux (k = 0,05), les nuages dépassaient de 2,2 Å à 20 000 atm ; désormais de 0,22 Å au plus |
| pression | celle que lirait un manomètre : la force des atomes sur la paroi, Σ k (r + ρ − R), divisée par sa surface 4πR², en atm (1 Eh/Å³ = 4,30·10⁷ atm) ; affichée moyennée sur 200 fs. L'enceinte la compare au gaz parfait NkT/V (N molécules) par Z = PV/NkT, calculé sur une moyenne longue (depuis le dernier changement, ou depuis que le piston est à l'équilibre) et montré seulement après 30 chocs d'atomes sur la paroi (±20 % environ) : en gaz dilué, 200 fs n'en comptent qu'un ou deux. Z > 1 : les molécules se gênent (leur volume propre — même un gaz parfait de molécules de taille finie donne Z = R/a, a le rayon où leurs centres peuvent aller) ; Z < 1 : elles s'attirent. H₂ + Cl₂ à 1 500 K sous 20 000 atm : Z = 6,1 (densité de liquide, où la fréquence des rencontres, une formule des gaz, n'est plus donnée) ; 2 H₂O à 3 000 K, paroi fixe : Z ≈ 1,1–1,5 |
| piston | une pression imposée (100 à 20 000 atm, 0,01 à 2 GPa) : le volume de la sphère suit dV/V = (dt/τ)(P − P₀)/P₀ (Berendsen appliqué à la paroi, pas aux atomes : les molécules gardent leur forme), τ = 100 fs, au plus 1 % du volume par pas. H₂ + Cl₂ à 1 500 K, consigne 20 000 atm : 19 273 atm mesurés à 900 fs (R 6,0 → 3,1 Å) ; à τ = 250 fs, 9 000 à 11 000 atm à 750 fs — une minute d'écran sans y être. La sphère affiche la consigne et la mesure, le panneau dit si la paroi se resserre, s'écarte ou est à l'équilibre (à 25 % près). Sur 6 ps, la moyenne tient la consigne à 4 % près. Pourquoi pas 1 atm : à 1 500 K, une molécule n'en rencontre une autre que toutes les ~170 ps, plus d'une heure de calcul ; à 100 atm ~2 ps, à 10 000 atm ~25 fs |
| vibrations | hessienne par différences centrées des forces analytiques (pas de 0,005 Å), pondérée par les masses, sans translations ni rotations ; « exciter » un mode donne n quanta hν, et la dynamique (anharmonique) prend le relais |
| isotope | un atome (ou tous ceux d'un élément) prend la masse d'un de ses isotopes : les stables, et les radioactifs d'au moins un an de demi-vie (¹H ²H ³H, ¹²C ¹³C ¹⁴C, ¹⁶O ¹⁷O ¹⁸O…), masses de l'AME et abondances IUPAC (base mendeleev). Seule la masse change : la surface d'énergie, la géométrie, les charges restent (Born–Oppenheimer) ; la dynamique et les vibrations changent. H₂O → D₂O : 1 440, 3 677, 3 788 → 1 054, 2 650, 2 779 cm⁻¹, rapports 0,73, 0,72, 0,73 (mesurés : 0,74, 0,73, 0,74) ; HDO découple ses élongations (O–D 2 712, O–H 3 736 cm⁻¹). L'isotope s'écrit en exposant devant le symbole (²H, ¹³C) |
| assembleur 2D | un dessin à plat, vérifié par le calcul pendant qu'on dessine, puis posé en 3D et relaxé dans le simulateur (voir « L'assembleur 2D et le dictionnaire ») |
| molécules du vivant | glucose (β-D-glucopyranose), acide laurique (acide gras saturé en C12, un lipide), glycérol, alanine, caféine : les conformères 3D de PubChem (MMFF94), relaxés par GFN1-xTB en 70 à 85 pas sans qu'une liaison casse ; 13 à 38 atomes (au-delà de 40, le calcul ne suit plus en temps réel) |

Les espèces présentes sont lues dans les indices de liaison de Mayer, B_AB = 2 Σ_σ Σ (P^σ S)_μν (P^σ S)_νμ : des
atomes reliés par B ≥ 0,5 forment une espèce (seuil de lecture pour nommer ; le calcul n'en dépend pas), pourvu
qu'ils partagent encore des électrons entre eux (partage ≥ 0,09, voir « Liaison rompue, indice resté haut » plus
bas) : sans cela, deux O₂ séparés de 3 Å, dont l'un garde un indice de 1 avec un atome de l'autre, se liraient O₄.
Un changement d'espèces qui dure 20 fs est annoncé comme une réaction, chaque espèce nommée par le dictionnaire.

## L'assembleur 2D et le dictionnaire

**Le dictionnaire** (`dictionary.py`) : 151 espèces écrites en SMILES (le sous-ensemble organique : hydrogènes
implicites, atomes entre crochets, liaisons `=` et `#`, branches, fermetures de cycle ; forme de Kekulé), du proton et
des atomes seuls aux molécules du vivant, avec des ions (OH⁻, H₃O⁺, NH₄⁺), des radicaux (OH, CH₃, HO₂) et des
isomères (éthanol et éther diméthylique). Une espèce est reconnue par son graphe — qui est lié à qui, hydrogènes
compris — et sa charge nette : l'empreinte de Weisfeiler–Lehman (quatre tours d'étiquettes des voisins) et la formule.
Les ordres de liaison n'y entrent pas : le calcul ne les donne pas entiers (Mayer 1,4 dans le benzène). Les 151
empreintes sont distinctes. La charge d'un fragment est la somme de ses charges de Mulliken, arrondie à l'entier dès
0,6 e en valeur absolue (OH à −0,9 e : l'ion hydroxyde).

Dans la scène, chaque espèce annoncée (celles du recensement, qui ont tenu 20 fs) est nommée sous elle : le nom et la
formule, la formule seule hors du dictionnaire ; un atome seul garde son symbole, et une molécule seule est nommée par
le titre. Une espèce qui gagne un exemplaire — une eau formée, pas un H passé d'une eau à l'autre — est entourée d'un
cercle vert pendant 3 s, et le journal l'annonce : « formé : H₂O · eau — défait : H · atome d'hydrogène, HO · radical
hydroxyle ». Option « noms des molécules » (05 Afficher).

**L'assembleur** (« ✎ Assembleur 2D ») est un écran de dessin à plat : des atomes, des liaisons simples, doubles ou
triples, plusieurs molécules, 40 atomes au plus. Ce qui se décide sans calcul :

- la valence de chaque élément selon sa charge formelle — usuelle (C 4 ; N 3, 4 en N⁺ ; O 2, 3 en O⁺, 1 en O⁻ ;
  halogènes 1) et maximale (P 5, S 6, Cl, Br et I 7 : les hypervalents ; le néon 0). Une liaison au-delà du maximum
  est refusée et l'écran dit pourquoi ; ce qui manque à la valence usuelle est marqué de points rouges (« valences
  libres ») : un radical, une molécule hypothétique se dessinent ;
- « Compléter les H » (chaque valence libre reçoit un H, dans le plus grand vide), « Mettre au propre » (les
  contraintes du schéma 2D : liaisons égales, cycles réguliers, 120°), les modèles du dictionnaire (recherche sans
  accents, formules comprises) et la reprise de la scène 3D, redessinée à plat ;
- les gestes. Avant de cliquer, l'écran montre ce qui se ferait : l'atome fantôme sous le curseur (le curseur lui-même
  est un cercle de la couleur de l'élément, avec son symbole ; la gomme, un cercle rouge barré ; la charge, ±), la
  liaison et l'atome fantômes qui partiraient de l'atome survolé, rouges si ses valences les refusent ; une pastille
  dit l'atome survolé (« C2 · liaisons 4/4 · neutre ») et ce qu'un clic ferait (« valence pleine : plus de liaison »,
  « clic : charge +1 »). L'outil Sélection choisit des atomes (clic ; Maj ou Ctrl pour en ajouter ; un cadre tiré dans
  le vide ; double-clic : toute la molécule ; Ctrl+A : tout) ; une barre posée au-dessus d'eux change leur charge (+1,
  −1, 0 — un atome dont les liaisons ne tiendraient plus la refuse, et l'écran dit combien), complète leurs H, remet
  leurs molécules au propre, les duplique (Ctrl+D) ou les efface (Suppr) ; tirer la sélection la déplace ; + − 0 au
  clavier agissent sur elle ou sur l'atome survolé. Le clic droit ouvre le menu de ce qui est dessous : un atome (sa
  charge, ses H, toute sa molécule, l'effacer), une liaison (simple, double, triple, l'effacer), le vide (tout
  sélectionner, mettre au propre, annuler). « Numéros des atomes » montre le numéro que chacun gardera en 3D (C1, O2…).

Le calcul suit le dessin : 0,2 s après un changement, le nom, la formule, la charge et la parité des électrons ;
1,3 s après, chaque molécule, posée en 3D comme à l'envoi puis relaxée seule par GFN1-xTB (L-BFGS, 400 itérations au
plus, interrompue si le dessin change), reçoit son verdict :

| Verdict | Ce que dit le calcul |
|---|---|
| tient | un minimum où chaque liaison dessinée existe (Mayer ≥ 0,5 et partage ≥ 0,09) et aucune autre ; la cohésion (énergie des atomes isolés moins celle de la molécule) et l'écart HOMO–LUMO |
| se réarrange | une seule molécule, mais pas celle dessinée : les liaisons rompues en rouge, les formées en tirets verts |
| se défait | plusieurs morceaux à la relaxation, nommés |
| échec | aucun état électronique sensé (le cycle auto-cohérent ne converge pas) |

Sous 0,5 eV d'écart, la carte prévient : des couches presque ouvertes, que GFN1 sans spin décrit mal (le verdict est
moins sûr). L'écart va du plus haut niveau qui porte un électron au plus bas qui a encore une place : nul pour O₂.

**L'envoi** pose chaque molécule en 3D par contraintes : longueurs = somme des rayons covalents de Pyykkö × 1, 0,87
ou 0,78 (simple, double, triple), angles de 180°, 120° ou 109,47° selon les liaisons π de l'atome, atomes non liés à
1,9 Å au moins. Chaque molécule garde sa place sur le dessin (la liaison dessinée moyenne vaut 1,4 Å) et les molécules
restent à 2,5 Å au moins l'une de l'autre ; l'enceinte, en option, est une sphère de rayon le plus éloigné + 1,5 Å.
Puis le simulateur relaxe, et tout s'applique (dynamique, réacteur, vues, vibrations, isotopes). CH₄ dessiné à plat
devient un tétraèdre ; OH· et H· dessinés côte à côte deviennent H₂O en 0,8 s de relaxation.

## Ce qu'on voit

Le rendu est volontairement épuré : des bulles de verre, des noyaux incandescents, des faisceaux de lumière, sur un
fond sombre, avec un bloom (deux calques flous ajoutés par-dessus, calculés par la carte graphique). Chaque signe porte
une grandeur calculée :

| À l'écran | Ce que c'est |
|---|---|
| la sphère de verre | l'atome : rayon 0,45 × rayon covalent (Pyykkö & Atsumi 2009), donc toujours loin de ses voisins liés (Au₂ à 2,49 Å : sphères de 0,56 Å, 1,4 Å de vide entre elles) ; centre presque transparent et bord de Fresnel, en verre argenté pour un atome neutre, teintés — bord et intérieur — vers le cyan si l'atome a perdu des électrons (δ+) et vers le magenta s'il en a gagné (δ−), charges de Mulliken : pleine teinte à ±0,45 e, déjà nette à 0,1 e (la teinte suit (\|q\|/0,45)^0,7). Le bord était d'abord bleu de verre pour le neutre : il se confondait avec le cyan des δ+. Deux sphères qui se chevauchent à l'écran sont l'une derrière l'autre : celle de devant voile celle de derrière (70 % de sa lumière arrêtée), comme un verre |
| le halo de polarisation | le nuage des électrons de valence de chaque molécule, vu en transparence : en chaque pixel, la densité projetée σ = ∫ ρ ds, les électrons par Å² que croise le regard ; son bord est le contour σ = 0,3 e/Å², tracé au pixel près, et la traîne du nuage s'éteint au-delà en brume. Voir « Le halo » plus bas. Couleur : la charge des atomes qui apportent leurs électrons au point, moyennée selon leurs parts (Hirshfeld vu en projection) : magenta δ−, violet, bleu neutre, cyan δ+ (saturé à ±0,4 e). Il est fourni autour des atomes qui attirent les électrons (O, Cl), mince autour de ceux qui les cèdent (H) |
| δ+ / δ− | posés juste au-delà du bord du halo, au milieu du plus grand angle libre entre les liaisons de l'atome (plus loin que son symbole), pour les atomes dont la charge dépasse 0,1 e ; près de l'atome s'il est au cœur de la molécule |
| le point incandescent | le noyau |
| les arcs dans la bulle | partis du noyau, ils suivent le champ ρ̂/ρ² + Σ s_b (u_b − ρ)/\|u_b − ρ\|³ + E₀ ê : captés par les points où les liaisons percent la bulle (s_b = 0,55 × indice de Mayer) et penchés dans le sens où le nuage de l'atome est déformé (ê = −d/\|d\|, d le dipôle atomique, E₀ ∝ \|d\|). Un atome libre et symétrique a des rayons droits ; l'oxygène de l'eau penche vers ses paires libres |
| les brins | une liaison tenue par une paire d'électrons sur deux atomes (voir « Les liaisons » plus bas), d'une bulle à l'autre : un brin par paire partagée, l'indice de Mayer arrondi comme sur le schéma 2D (sous 1,3 : un ; jusqu'à 2,3 : deux ; au-delà : trois), le second brin d'une liaison conjuguée (indice sous 1,75 : benzène 1,44) pâle et en tirets ; leur éclat suit l'indice de Mayer et s'éteignent continûment quand il descend de 0,5 à 0,12, ou quand les deux atomes ne partagent plus d'électrons entre eux (partage de 0,15 à 0,03, voir « Liaison rompue, indice resté haut » plus bas) — le calcul n'a pas de seuil « liaison / pas de liaison ». H₂ étiré : trait entier jusqu'à 1,6 Å, à moitié vers 2 Å, éteint à 2,5 Å — alors que son indice reste à 1 jusqu'à 3,2 Å. Gerbe de grains quand il s'éteint, éclair quand il naît |
| à plat ou torsadés | la disposition des brins d'une liaison multiple suit sa part π calculée (axes propres de la matrice de Mayer π, voir « σ et π ») : à plat quand cette part a un côté — l'éthylène 1,00 / 0,03, le formaldéhyde, le benzène : la normale à leur plan —, et la liaison ne tourne pas sur elle-même ; les brins y sont côte à côte dans le plan des liaisons σ, comme les bâtonnets doubles des modèles. Torsadés d'un demi-tour quand la part π entoure l'axe, ses deux parts presque égales (rapport au-delà de 0,7 : N₂, acétylène, CO₂ à 0,47 et 0,47, C₃) ; une triple l'est toujours. L'écart des brins, le plus grand au milieu, suit l'orbitale p de valence des deux atomes (C 0,29 Å, N et O 0,25 Å, Cl 0,51 Å). Ils remplacent les lobes de verre π, qui disaient la même direction sans dire combien de paires ; écartés d'abord le long du π lui-même, les deux brins de l'éthylène se superposaient dans la vue de face par défaut |
| la couleur du trait | la force interne le long de la liaison, ½ (F_A − F_B)·u (sans la main ni la paroi) : bleu nulle, blanc en traction, orange en compression ; en dynamique, elle bat avec les vibrations |
| les grains | les électrons des paires de la liaison — 24 pour une simple, 36 pour une double, 48 pour une triple —, tirés de leur densité le long de l'axe : la densité Σ_k \|φ_k\|² de ses orbitales localisées, intégrée dans chaque tranche perpendiculaire (vingt tranches d'un noyau à l'autre, anneaux jusqu'à 1,6 Å), entre les deux bulles. Chaque grain s'éteint et renaît ailleurs, tiré de la même loi. Ils s'étalent sur toute une liaison covalente (H₂ : profil plat ; C–H : plateau au milieu) et sont plus denses du côté de l'atome qui garde la paire dans une liaison polaire (O–H : 54 % de la densité dans la moitié côté O, maximum près de O ; C=O : croissante vers O) ; Cl–H reste presque centrée, l'orbitale 3p du chlore s'étendant loin vers H. La densité de déformation Δρ ne convient pas : elle est négative tout le long de O–H et de H–Cl (le défaut connu de la référence en atomes sphériques). La part de la paire par atome (populations de Mulliken des orbitales localisées : orbitales localisées de Pipek–Mezey (1989), populations de Mulliken de chaque paire (O–H de l'eau : 66 % côté O ; Cl–H : 61 % côté Cl ; N–H : 59 % ; C=O du formaldéhyde : 63 % côté O ; C–H : 51 %). La fiche de l'atome l'écrit (« paire de la liaison 66 % chez O1 ») |
| l'étoile | une paire d'électrons étalée sur trois atomes ou plus (liaison multicentre), en notation de Lipscomb : des rayons fins (or pâle) de son barycentre vers ses atomes, d'autant plus nets que l'atome porte une grande part de la paire, et ses grains serrés autour du barycentre. Dans Au₆, trois étoiles, une par triangle de sommet ; dans le diborane, les deux ponts B–H–B. La fiche de l'atome l'écrit (« paire à 3 centres  Au1 · Au4 · Au5 ») |
| les pointillés | une attraction sans paire partagée (force de la paire > 0,06 eV/Å) entre atomes à au moins quatre liaisons d'écart ou de molécules différentes — liaison hydrogène, van der Waals — ou entre deux ions (NaCl : l'attraction des électrons seule, puisqu'à l'équilibre elle compense exactement la répulsion des cœurs) ; ils glissent vers le milieu |
| des points orange qui s'écartent | une répulsion nette (< −0,25 eV/Å) entre deux tels atomes, montrée par sa force comme l'attraction mais à l'envers : les points partent de là où les deux nuages se touchent — sur l'axe, où se coupent leurs surfaces (atomes libres à 0,002 e/bohr³ : H 1,24 Å, C 1,48, O 1,40, Cl 1,71 ; Bondi : 1,20, 1,70, 1,52, 1,75) — et s'écartent vers chacun des deux atomes ; leur éclat et leur vitesse suivent la force de la paire, recalculée à chaque pas (une collision s'allume, culmine et s'éteint). Aucune forme dessinée ne la représente : le transfert d'électrons entre les deux atomes change de signe selon les cas (−0,09 e entre deux eaux comprimées, +0,08 e entre un iode radical et O, qui commencent à se lier), et la densité de déformation ne distingue pas une répulsion d'une liaison hydrogène. Un noyau qui tremble : ses voisins le compriment au-delà de 0,5 eV/Å |
| la carte au survol | dessinée plutôt qu'écrite : la tuile de l'élément (numéro atomique, symbole, masse) et la molécule dont l'atome fait partie ; sa charge sur une jauge de −1 à +1 e (« a gagné 0,49 e⁻ ») ; sa force nette dans un cadran tourné comme la vue, et vers quel voisin lié elle tire ; ses voisins, liés d'abord : la liaison en 1, 2, 3 traits (des points sans liaison), la distance, une barre logarithmique de la force que la paire exerce sur lui (bleu : elle l'attire, orange : elle le repousse) ; « charge tenue à −2,5 e » pour un atome que la contrainte des anions retient. Sur la scène, les mêmes forces partent de l'atome en flèches de ces couleurs, la nette en jaune, et ses voisins sont cerclés |
| la fiche au clic droit | la molécule de l'atome cliqué (l'espèce annoncée qui le contient, sinon ses atomes liés) : son nom du dictionnaire et sa formule ; son dessin à plat (la mise en page du schéma 2D, tous ses H), tourné comme la vue, chaque atome teinté par sa charge ; son moment dipolaire μ = Σ q (r − centre de masse) + les dipôles atomiques du calcul, en debye, en flèche du δ+ vers le δ− (la convention des chimistes) — l'eau seule 2,77 D, le moteur 2,78 D pour la scène entière (C₃ n'y ajoute rien) ; celui d'un ion dépend de l'origine, c'est dit ; sa masse molaire, ses charges atome par atome en barres, ses liaisons par sorte avec leur longueur moyenne. Puis les gestes : étincelle sur l'atome, carte 09, la modifier dans l'assembleur 2D (elle seule y est redessinée, sa charge d'ion gardée), retirer l'atome ou la molécule. Échap, ✕ ou un clic ailleurs la ferment |
| la légende | en haut à droite, une ligne par signe présent à l'écran ; un clic sur son titre la replie (il n'en reste que le titre) ou la déplie |
| la vue Δρ | une carte de déformation, en courbes de niveau : voir « Les trois vues » plus bas |
| la vue ψ | une orbitale moléculaire en nuage de probabilité |ψ|² : voir « Les trois vues » plus bas |
| options | la carte de potentiel conventionnelle (le potentiel électrostatique exact sur la surface ρ = 0,002 u.a.), les lignes du champ électrique des charges, la flèche de la force nette |

La caméra se place face à la molécule : le long de l'axe où ses noyaux s'étalent le moins (la normale d'une molécule
plane), pour que l'eau se voie coudée et le benzène en hexagone.

**Naviguer.** La vue tourne en trackball : un geste horizontal autour de la verticale de l'écran, un geste vertical
autour de son horizontale, sans butée ni pôle (on regarde une molécule par-dessous) et sans élan (elle s'arrête où
on la lâche). Elle tourne autour du point visé, montré par une mire pendant le geste. La molette zoome vers le
curseur : l'œil et le point visé se rapprochent du point pointé dans le même rapport (une homothétie de centre ce
point), il reste donc sous le curseur, et le pivot glisse vers lui. Le clic droit glissé (ou le milieu, ou Maj)
déplace la vue, pixel pour pixel à la profondeur du point visé ; le clic droit seul ouvre la fiche. Ctrl + glisser
tourne même en partant d'un atome (zoomé de près, les atomes couvrent tout). Le double-clic sur un atome le centre et
le suit pendant la dynamique ; « Centrer la vue sur elle », dans la fiche, cadre une molécule et la suit. « Tout
voir » (le bloc en bas à droite, la touche F, le double-clic dans le vide) revient à toutes les molécules, cadrées
sur le centre de leur boîte et non sur leur barycentre : une grosse molécule l'attirait, et une petite à l'autre bout
sortait de l'écran. Un atome ajouté ne recadre plus une vue menée à la main ; une autre scène, si.

**Les trois vues.** Chacune ne montre que ce qu'elle dit, dans sa propre langue — la vue choisie décide de ce qui
est dessiné, pas la dernière grille reçue : une vue ρ gardait sinon, figés, les lobes de la vue précédente (le
moteur ne calcule plus de grille pour ρ), jusque dans une session suivante. Le moteur publie une grille avec
l'instantané qui annonce sa vue, jamais avant.

- **ρ** : les bulles, le halo, les liaisons et les forces (tout ce qui précède).
- **Δρ — où les électrons sont allés.** Δρ = ρ(molécule) − Σ ρ(atomes libres, sphériques), vue en transparence
  comme le halo l'est pour ρ : Δσ(pixel) = ∫ Δρ ds le long du regard (e/Å²), calculée par le client sur la grille du
  moteur et tracée en courbes de niveau, la convention des cartes de densité de déformation — trait plein (cyan) où
  la molécule a gagné des électrons, tirets (orange) où elle en a perdu, une courbe à chaque doublement : 0,025 ·
  0,05 · 0,1 · 0,2 · 0,4 · 0,8 · 1,6 e/Å², avec un lavis à peine teinté et une lueur sur les courbes intérieures.
  Les noyaux sont des points blancs, sans bulle ni charge ni liaison : la carte dit seule où sont les liaisons. Gains
  et pertes se compensent (∫ Δσ = 0, vérifié à 10⁻⁴ e). On y lit les cartes des manuels : H₂ gagne au milieu de la
  liaison et perd derrière ses noyaux ; le benzène et l'éthylène gagnent sur toutes leurs liaisons (C–C, C=C, C–H)
  et perdent sur les noyaux de carbone ; l'eau a déplacé ses électrons des H vers O — sur O–H même, la référence en
  atomes sphériques donne une perte (son défaut connu). La courbe zéro n'est pas tracée : loin des noyaux, elle
  errerait au gré du bruit.
- **ψ — où l'électron peut être trouvé.** Comme dans les logiciels de chimie (Avogadro, GaussView) : les lobes de
  l'orbitale en surfaces pleines et lisses, à la frontière qui enferme 85 % de |ψ|² (l'électron y est 85 fois sur 100),
  bleues où ψ > 0, rouges où ψ < 0 — la phase de l'onde, pas une charge ; entre un lobe bleu et un rouge, un nœud. Le
  squelette est en boules et bâtonnets dans la même scène 3D (chaque chose cache ce qui est derrière elle), une liaison
  en un, deux ou trois bâtonnets selon son indice de Mayer (moins de 1,3 ; jusqu'à 2,3 ; au-delà), côte à côte dans le
  plan perpendiculaire à sa densité π — le second plus fin pour une liaison conjuguée (sous 1,75 : benzène 1,44). La
  carte graphique rend tout, sous le canevas de la scène (étiquettes, légende, échelle), avec la caméra de la scène
  (même œil, mêmes axes, même champ) : net à toute résolution, même en tournant. Le maillage est tiré de la grille par
  tétraèdres marchants (six par maille le long de la même diagonale : une surface fermée, vérifié χ = 2 par lobe),
  chaque triangle tourné selon les normales du gradient de |ψ| à ses sommets, dessiné des deux côtés ; il est construit
  hors du fil de l'interface, et pendant la dynamique les lobes d'avant restent jusqu'aux nouveaux.

  La grille de ψ : 80 points par côté au repos, 56 pendant la dynamique (la caféine au pas de 0,19 Å). Elle est
  calculée par séparation des gaussiennes — x^a y^b z^c e^(−α r²) est un produit de trois fonctions d'une variable —,
  chaque primitive posée sur la seule sous-boîte où elle dépasse 10⁻¹² : la même valeur qu'en évaluant chaque φ_μ en
  chaque point (à 10⁻⁷ près), 8 à 25 fois plus vite (caféine : 2,7 s → 0,11 s à 72 points). La boîte grandit (jusqu'à
  9 Å de marge) tant que |ψ| touche ses faces au niveau de la surface ; au-delà, la grille est bordée de zéros et la
  surface s'y referme. Deux défauts vus pendant la dynamique d'un dimère d'eau à 3 800 K, et corrigés : une grille de
  30 points (0,45 Å) ne résolvait pas les orbitales diffuses, dont les lobes n'ont parfois que 0,5 Å entre deux nœuds
  (vérifié en double précision : ces nœuds sont réels) ; et le sens des triangles, pris d'abord de l'intérieur vers
  l'extérieur de chaque tétraèdre, se trompait sur les surfaces très courbes — masqués comme faces arrière, ils
  ouvraient des trous en dents de scie.

  La caméra regarde le long de l'axe où le nuage est le plus mince (moments de |ψ|²), une fois par orbitale, et
  seulement si on ne l'a pas tournée : la π de l'éthylène de profil, la paire libre de l'eau par la tranche ; quand
  chaque ligne de visée traverserait les deux phases (une π du benzène vue de face — la part de |ψ|² dont le signe
  s'annule le long des lignes de visée, en colonnes de 0,3 Å, dépasse 50 %), la vue s'incline de 40° autour du grand
  axe du nuage. La ligne de la vue dit l'orbitale : nom, énergie, électrons et ce dont elle est faite (populations de
  Mulliken par couche, parts d'au moins 5 %). Sans carte graphique 3D, la vue garde un nuage de 9 000 points tirés de
  |ψ|². Ont été essayés puis écartés : des lobes tracés par rayons au processeur (flous en mouvement), le nuage |ψ|²
  projeté (sans phase ni nœud), le nuage de points (« un essaim de parasites »), puis un verre teinté à liseré de
  Fresnel (rejeté : « fais un truc standard »).

**Les lectures (colonne de droite).** Trois panneaux dessinés, chacun calculé :

- **06 Bilan énergétique.** En tête, la cohésion : l'énergie à fournir pour séparer la molécule en atomes isolés et
  neutres (« liée », « à peine liée » sous 0,5 eV, « non liée »). Puis sa balance : chaque terme de GFN1-xTB moins
  sa valeur dans les atomes isolés, calculés par le même modèle — la bande (« orbitales qui se recouvrent »),
  l'électrostatique des charges (deuxième et troisième ordre), l'entropie électronique des occupations lissées —, plus
  la répulsion des cœurs, la dispersion et la liaison halogène, que les atomes seuls n'ont pas. La somme est exactement
  l'énergie de liaison (vérifié à 10⁻⁹ eV ; eau : bande −19,18, électrostatique +1,36, répulsion +1,03, entropie
  +0,10, London −0,004 → −16,68 eV). Les termes sont rangés par leur signe, pas par leur nom : ce qui lie part du
  zéro vers la gauche (bleus), ce qui coûte vers la droite (orangés), et ce qui reste — la cohésion — est dessiné
  dessous. L'électrostatique coûte dans l'eau (des charges séparées) mais lie dans Au₆ (−1,6 eV). Enfin l'agitation :
  l'énergie cinétique des noyaux rapportée à la cohésion, sur une jauge logarithmique (0,1 % → 100 %). La limite de
  GFN1 sur les énergies de liaison est sous le ⓘ du titre.
- **07 Orbitales, en cible.** Les niveaux HOMO − 5 … HOMO + 4 en cercles concentriques : le rayon suit l'énergie,
  linéairement — au centre les électrons les plus liés, au bord le cercle du vide (0 eV). Un niveau occupé est un
  cercle plein qui porte ses électrons en points ; la HOMO est orangée, l'écart HOMO–LUMO un anneau doré, la LUMO un
  cercle violet en tirets ; les niveaux de même énergie (à 0,02 eV) partagent un cercle, nommé par la HOMO ou la LUMO
  s'il la porte (« HOMO ×2 » pour le benzène). Les niveaux vides montrés s'arrêtent à 6 eV au-dessus de la LUMO (un
  niveau très haut écraserait les autres). Les cotes sont posées sur leur cercle ; survoler un cercle le lit au cœur,
  le cliquer montre son orbitale dans la vue ψ.
- **09 Atome, en carte.** Ce qui arrive à l'atome choisi — est-il tiré, poussé, et par qui ? En tête la case de son
  élément (Z, symbole, masse), sa charge, sa coordination et sa force nette, avec sa direction en mots quand elle va
  presque vers un voisin (« vers C11 », « loin de N4 »). La carte : l'atome au centre, ses voisins dans leur
  direction à l'écran — ceux auxquels il est lié sur un cercle proche, les autres au bord, deux voisins trop proches
  légèrement écartés ; tous les liés, puis les paires les plus fortes, six au plus. Trois lectures, en onglets :
  - *l'essentiel* (par défaut) : une seule flèche, la force nette (dorée, comme dans la scène) ; chaque trait dit ce
    que la paire se fait, bleu s'ils s'attirent, orange s'ils se repoussent, d'autant plus épais que la force est
    grande, plein s'ils sont liés, en tirets sinon. Dessous, les voisins rangés en *liés* et *non liés*, une barre
    par force (une échelle pour tous), et en une ligne le pourquoi, calculé : la nature d'une liaison selon sa part π
    (simple sous 0,15, en partie double — conjuguée — sous 0,75, double, triple au-delà de 1,4 ; un nom tiré de
    l'ordre seul se tromperait : C8–N6 de la caféine chaude, ordre 1,46, n'est pas « simple » : π 0,49), et pour deux atomes non liés la part de
    leur force qui pèse le plus dans son sens — le recouvrement des orbitales (antiliant, population de recouvrement
    de Mulliken < 0 : deux couches pleines qui se touchent, la gêne stérique), les charges (de même signe ou
    opposées), London, ou les cœurs. Vérifié sur la caféine : les poussées 1-3 autour de N6 (C7, N4, N3, H15 :
    0,6 à 1,8 eV/Å) viennent du recouvrement pour 81 % au moins, avec des populations de −0,02 à −0,05 e ; les charges n'y
    sont que 0 à 19 % (C7 est presque neutre), et la répulsion des cœurs est nulle à 2,2 Å. « δ− repousse δ− » serait
    donc faux ;
  - *par voisin* : une flèche par paire, la force entière que ce voisin exerce sur lui (bleue s'ils s'attirent le long
    de leur axe, orange s'ils se repoussent) ; elle n'est pas toujours sur l'axe : les orbitales p sont orientées par
    toute la molécule, et la part de travers tient les angles (O–H de l'eau chaude : 1,27 eV/Å le long de l'axe,
    0,15 de travers). Les flèches s'additionnent exactement en la force nette — le moteur envoie pour chaque paire sa
    force entière, et leur somme refait la force de chaque atome à 10⁻¹⁵ eV/Å près ; ce qui n'est pas montré
    (paires lointaines, liaison halogène, main) est une flèche « le reste », en tirets ;
  - *par terme* : électrons, noyaux, London, main ou paroi — deux grandes forces presque opposées et une petite
    nette ; chaque trait est bleu si la paire s'attire, orange si elle se repousse.

  Dans les deux lectures détaillées, chaque voisin a sa fiche : distance, liaison (σ + π), sa force le long de l'axe
  et de travers, ses termes (électrons = recouvrement + charges, noyaux, London) et la paire de la liaison. La carte
  est centrée sur l'atome et orientée exactement comme la caméra (elle tourne avec la vue) ; ses voisins liés sont à
  60 px au moins, au-delà des flèches ; au survol d'un voisin, sa distance et sa force s'écrivent à côté de lui. Un
  clic sur un voisin le choisit.
- **La vignette « Schéma 2D »** (en bas à gauche de la scène, repliable) : la molécule comme sur une feuille, en
  formule développée et squelettique — les carbones pris dans la chaîne en sommets, les autres atomes écrits à la
  couleur de leur élément avec leurs hydrogènes rassemblés (CH₃ ou H₃C selon le côté, OH, NH₂), ou, bouton « tous les
  H », chaque hydrogène dessiné sur sa tige ; une petite molécule (deux atomes lourds au plus, comptés molécule par
  molécule : l'eau à côté de C₃ s'écrit H–O–H, pas OH₂) écrite en entier ; un carbone en ligne droite (C=C=C, C≡C–C)
  écrit lui aussi, un sommet à 180° ne se voyant pas (C₃ paraissait n'avoir que deux carbones) — et ses liaisons en un,
  deux ou trois traits selon l'indice de Mayer, le second en tirets pour une liaison conjuguée. Les traits s'arrêtent au
  bord de l'étiquette de leur atome ; un signe δ se pose dans le plus grand angle libre autour de lui (ni sur une
  liaison, ni sur ses H rassemblés), au-delà de toutes les étiquettes. Dépliée, la vignette couvre le bas de la bande
  gauche : la scène 3D se centre à sa droite, et le cadrage tient compte de la largeur qui reste.
  Le dessin est celui d'un manuel, pas une projection : sa disposition, calculée une fois par enchaînement d'atomes
  (refaite seulement quand une liaison se forme ou se rompt), part de la molécule dans son plan moyen puis se règle par
  contraintes — liaisons de même longueur, cycles en polygones réguliers (120° à six chaînons, 108° à cinq), 120° hors
  cycle, 180° le long d'une triple liaison, atomes non liés écartés (caféine : la purine régulière ; glucose : le
  pyranose et ses OH ; acide laurique : un zigzag). Il ne vibre pas avec la dynamique, mais suit la vue 3D : à chaque
  image, il tourne — et se retourne en miroir si l'on regarde l'autre face — pour coller au mieux aux atomes tels que la
  caméra les montre (Procuste en deux dimensions ; le miroir ne bascule que s'il accorde nettement mieux) ; une
  boussole x, y, z dit l'orientation. Survoler un atome du schéma le montre en 3D, le cliquer le choisit ; l'atome
  choisi en 3D y est entouré. Dessus, en temps réel : les charges partielles fortes (δ), les longueurs des liaisons de
  l'atome choisi et sa force nette ; dessous, une ligne de mesures (cohésion, température, liaisons — ou le dernier
  événement, rupture ou formation) et une consigne qui suit le geste en cours.

**Le potentiel électrostatique.** φ(r) = Σ_A Z_A / |r − R_A| − ∫ ρ(r′) / |r − r′| dr′ : les noyaux portent leur charge de
valence (les électrons de cœur ne sont pas dans GFN1), leur part est exacte ; celle du nuage est la convolution de ρ
par 1/r, faite par transformée de Fourier sur une grille doublée (méthode de Hockney : pas d'images périodiques ; à
r = 0, la moyenne exacte de 1/r sur la cellule). Vérifié contre la somme directe (10⁻⁸ V). Sur la surface conventionnelle
ρ = 0,002 u.a. (celle des cartes de potentiel des manuels), il donne les motifs attendus : eau −3,4 V côté O, +2,8 V
côté H ; CO₂ ceinture positive (+2,7 V) et extrémités négatives (−1,8 V) ; benzène faces π négatives (−0,7 V), couronne
de H positive (+0,65 V) ; méthane presque neutre. Il n'est pas montré sur le halo : sur une surface aussi proche des
noyaux (0,1 u.a.), ceux-ci sont mal écrantés et le potentiel est positif presque partout (H₂ : +3 à +7 V) — d'où les
charges pour colorer le halo, et la carte de potentiel à 0,002 u.a. en option.

**Le halo.** Ce qu'on voit d'un nuage translucide, c'est ce que le regard en traverse : la densité projetée
σ(pixel) = ∫ ρ ds le long du rayon de vue (électrons par Å²). Le client la calcule à chaque image, en pleine
résolution, sans grille : chaque atome y apporte le nuage de valence de l'atome libre vu en transparence,
P⁰(d) = ∫ ρ⁰(√(d² + s²)) ds (transformée d'Abel de sa densité sphérique dans la base GFN1, calculée par le moteur et
envoyée une fois par élément ; ∫ 2πd P⁰ dd = N⁰ à 1 % près), à l'échelle des électrons qu'il garde,
N_A / N⁰ = (N⁰ − q_A) / N⁰ (charge de Mulliken) : σ = Σ_A (N_A / N⁰) P⁰_A(d_A), d_A la distance du rayon au noyau.
Cette somme d'atomes sphériques reproduit la densité calculée projetée (ρ = Σ P_μν φ_μ φ_ν, liaisons comprises) : les
contours σ = 0,3 e/Å² se recouvrent à 95–99 % de leur surface pour l'eau, HCl et le formaldéhyde (testé). Le bord
σ = 0,3 e/Å² passe à 0,44 Å au-delà des H de l'eau et à 1 Å au-delà de son O. Chaque molécule (composante connexe des
liaisons) a son halo, composé du plus loin au plus proche. Les nuages de valence sans cœur sont creux au noyau :
vu en transparence, ils sont un peu plus denses à 0,1–0,3 Å du centre qu'au centre (comme le bord d'une bulle). Les
alcalins (Li, Na, K…) n'atteignent jamais le contour — leur unique électron est trop étalé (P⁰(0) = 0,10 e/Å² pour
Li, 0,05 pour Na) : ils n'ont que la brume.

**Les paires d'électrons.** Les orbitales doublement occupées sont tournées entre elles (rotation unitaire : densité,
énergie et dipôle inchangés — le dipôle reconstruit à partir des barycentres des paires égale celui de la densité) pour
maximiser Σ_i Σ_A (Q^A_ii)², la localisation de Pipek–Mezey : chaque paire se concentre sur un atome (paire libre : eau
2, NH₃ 1, HCl 3), deux (liaison) ou plus. Les systèmes π délocalisés (CO₂ : paires π à 80 % sur O et 19 % sur C) n'ont
pas de découpage unique en paires libres et liaisons ; seule la part de chaque liaison est montrée. Rotations de Jacobi
en parallèle (n/2 couples disjoints par tour, ordre du tournoi à la ronde) jusqu'à 10⁻⁶ rad : même optimum que les
rotations une à une, quatre à sept fois plus vite (Au₆ : 94 ms) ; refaite au plus deux fois par seconde pendant que les
atomes bougent, chaque barycentre suivant ses atomes entre-temps.

**Les liaisons.** Un indice de Mayer dit combien d'électrons deux atomes partagent, pas comment. Les paires localisées
le disent, et chaque liaison (B ≥ 0,35) est dessinée selon les paires qui la tiennent (seuils de lecture) :

- **une paire sur deux atomes** — deux atomes en portent plus de 75 % et aucun troisième 15 % : une liaison à deux
  centres, covalente ou polaire (H₂O, benzène, CO₂, BF₃, SiF₄, SF₆, TiCl₄, Au₂, AuH) : un trait ;
- **une paire sur trois atomes ou plus** (les atomes qui en portent au moins 10 %) : une liaison multicentre. Les
  électrons s des agrégats métalliques n'ont aucune paire sur un seul couple d'atomes : Au₆ (triangle D3h) a trois
  paires, chacune étalée sur un triangle de sommet (0,48 sur le sommet, 0,24 sur chacun de ses voisins), l'image
  des analyses AdNDP ;
  Li₄, Au₄ (losanges) deux ; Cu₃ une. Le pont B–H–B du diborane est la liaison à trois centres de Lipscomb (H 0,49,
  B 0,26, B 0,26). Une étoile ;
- **aucune paire partagée** : la paire reste sur un atome — deux ions (NaCl, CsF, KCl : 0,7 e transférés). Des
  pointillés d'attraction.

Les charges d'un agrégat pur ne sont pas toutes nulles : dans Au₆, les sommets (coordination 1,8) portent +0,05 e et
les atomes des côtés (3,3) −0,05 e — sous le seuil de 0,1 e des signes δ, qui n'apparaissent donc pas au minimum.

**Au₆.** GFN1-xTB place le triangle plan D3h (côtés 2,631 Å, centre 2,823 Å) sous le pentagone centré (+0,30 eV), la
pyramide pentagonale (+0,60 eV) et l'octaèdre (+1,10 eV), l'ordre des calculs DFT et des spectres infrarouges ; notre
implémentation donne ces énergies à 2·10⁻⁷ Eh de dxtb. Le triangle est dans les molécules toutes prêtes ; construit
atome par atome, Au₆ tombe sur le minimum le plus proche du chemin suivi (le triangle, ou une forme plane 0,87 eV
plus haut selon le désordre de placement).

**Le dipôle des atomes.** d_A = − Σ_μ∈A Σ_ν P_μν ⟨μ|(r − R_A)|ν⟩, avec les intégrales de moment ⟨a|(x − A_x)|b⟩
(Obara–Saika : un facteur S(i+1, j) selon x). Σ q_A R_A + Σ d_A est le dipôle exact de la densité GFN1 : vérifié
contre l'intégrale sur une grille fine (2,770 contre 2,769 D pour l'eau). GFN1 donne 2,77 D pour l'eau (mesuré :
1,85 D) — dans le tableau calculé ↔ mesuré.

**σ et π.** L'indice de Mayer de chaque liaison est séparé en orbitales : on tourne les p des deux atomes dans le repère
de la liaison (p∥ le long de l'axe, deux p⊥), et la somme de Mayer restreinte aux paires (p⊥, p⊥) donne B_π ; le reste
est B_σ (B_σ + B_π = B exactement). Résultats : N₂ σ 1,00 + π 2,00 ; C₂H₂ σ 1,00 + π 2,00 ; C₂H₄ σ 1,01 + π 1,03 ;
CO₂ σ 0,98 + π 0,95 ; benzène σ 0,98 + π 0,46 ; O₂ σ 1,00 + π 0,50 (état restreint). Où se tient la part π autour de
l'axe : B_π est la trace de la matrice 2 × 2 M = 2 Σ_spin (P'S')_{A⊥,B⊥} (P'S')_{B⊥,A⊥} ; ses axes propres sont les
directions des lobes, ses valeurs propres la part π de chacune. Éthylène 1,00 le long de la normale au plan et 0,03
dans le plan ; formaldéhyde 0,88 et 0,08 ; benzène 0,44 et 0,02 ; N₂ et acétylène 1 et 1 ; CO₂ 0,47 et 0,47.

**Liaison rompue, indice resté haut.** L'état électronique est restreint (couches fermées, comme xtb) : quand une
liaison casse, ses deux électrons restent appariés d'un fragment à l'autre, et l'indice de Mayer reste haut — O–O à
3 Å : 1,06 ; H₂ : 1 jusqu'à 3,2 Å. Rien pourtant n'est plus partagé entre les noyaux. Ce partage, le laboratoire le
calcule pour chaque liaison : la densité d'interférence au milieu de l'axe, 2 Σ_{μ∈A, ν∈B} P_μν φ_μ φ_ν (ni les
nuages propres des deux atomes, ni celui d'un troisième atome au même endroit), rapportée à √(ρ⁰_A ρ⁰_B), la
densité de chaque atome libre à son rayon covalent (ce qu'il apporte au milieu d'une liaison simple ordinaire).
Liaisons ordinaires : 0,8 (HCl) à 2,1 (N₂) ; Au₂ 0,34 ; I₂ 0,62 ; Na₂ 1,9 et Cs₂ 2,4 (leurs nuages sont diffus, la
référence aussi). Rompues : O–O à 3 Å 0,005, H₂ à 2,5 Å 0,03, et 0,000 à 0,024 pour toutes les paires fantômes
d'O₄ à 4 000 K. Le trait s'éteint entre 0,15 et 0,03 ; sous 0,09, les deux atomes ne sont plus liés pour les
espèces, les arcs et les contacts — la paire, qui s'attire encore un peu, est montrée en pointillés.

**Le cycle auto-cohérent.** D'abord le mélange de Broyden (0,4), depuis les charges du dernier calcul convergé :
5 tours d'ordinaire. Quand une liaison casse, deux niveaux presque dégénérés au niveau de Fermi échangent leurs
électrons d'un tour à l'autre : le point fixe devient très raide (la réponse des charges atteint 240 fois leur
variation) et Broyden oscille — O₄ à 4 000 K, avant : 2 852 pas non convergés sur 3 000, et des charges de ±33 e
réutilisées d'un pas au suivant. Amortir davantage n'y fait presque rien (sur les 265 pas les plus durs : Broyden
0,1, aucun ; 0,03, 48). Après 40 tours, le cycle passe donc à Newton : la réponse J = ∂q_sortie/∂q_entrée est
calculée au premier ordre (perturbations des orbitales, occupations de Fermi, nombre d'électrons fixé ; vérifiée
contre les différences finies à 10⁻⁸ près), et le pas (1 − J)⁻¹ r est divisé par deux tant que le résidu ne baisse
pas : les 265 convergent, en 4 pas (médiane), à 300 K. En dernier recours — fragments séparés à couches ouvertes,
Pm₄ dissocié, où il n'y a pas de point fixe atteignable à 300 K — un recuit électronique : occupations lissées à 50,
20, 10, 3 fois 300 K, puis 300 K, chaque palier partant du précédent, avec un mélange doux (0,15) ; si 300 K échoue
encore, le résultat est celui du palier le plus froid qui a convergé, et la ligne d'état le dit (« électrons lissés à
3000 K »). Des atomes épars à couches ouvertes partagent un même niveau de Fermi : un rien fait passer un électron de
l'un à l'autre. C'est le mélange qui compte (14 à 20 atomes H, O, N à 6 000 K : 4 cas sur 9 à 0,4, 9 sur 9 à 0,15, en
34 diagonalisations). Le palier trouvé sert de départ aux pas suivants (un cran plus froid est retenté tous les
40 calculs), et il survit à l'ajout d'un atome. Avant, un seul calcul pouvait coûter 22 000 diagonalisations (50 s) : le
laboratoire semblait figé dès 12 à 14 atomes épars, l'interface restant fluide et les grilles ne venant plus. Un calcul
s'arrête désormais à 1 200 diagonalisations ; un pas non convergé n'est pas intégré, la dynamique s'arrête sur le
dernier état valide (20 atomes épars à 4 000 K : aucun arrêt, 76 % des pas à 300 K, 6 000 K au plus). Seules des
charges convergées servent de départ au pas suivant. O₄ à 4 000 K avec des étincelles de 30 eV : 3 000 pas, aucun
arrêt, aucun pas non convergé, tous à 300 K (71 tours au plus, 5,5 ms par pas) ; glucose : 53 tours au plus.

**La contrainte des anions.** Le terme du troisième ordre, Γ_A q_A³ / 3, n'est pas borné quand un atome prend des
électrons (Γ > 0 pour presque tous les éléments) : son potentiel Γ_A q_A² creuse les niveaux de l'atome qui se
charge, qui en attire d'autant plus. Pm₄ dissocié converge ainsi, chez dxtb aussi (2·10⁻⁸ Eh), vers un atome isolé à
−9 e ; Ba₂Si vers Si −4,8 e et Ba +2,4 e — plus d'électrons perdus que Ba n'en a ; Cs₄Pb vers Pb −4,5 e. Aucune
molécule réelle n'en approche : la plus négative de 21 composés ioniques est O −0,98 e (Na₂O). Une contrainte
stricte tient donc chaque charge à −2,5 e au plus bas : un multiplicateur de Lagrange λ_A ≥ 0 relève les niveaux
de l'atome juste assez pour que sa charge y soit exactement (DFT contrainte ; λ cherché à chaque tour, par fausse
position). L'énergie est celle de GFN1 pour cette densité, et les forces suivent la contrainte (vérifiées par
différences finies, 10⁻⁶ Eh/Å). La contrainte n'entre en jeu que si la solution libre la franchit. Les cations ne
sont pas bornés : un plafond à +2,5 e fausserait WF₆ (W +3,11 e), OsO₄ (Os +2,81 e) et MoF₆ (Mo +2,73 e), des
charges que dxtb donne aussi ; et seul Ag aurait, en principe, un emballement côté positif (Γ < 0), au-delà de
+10 e. La ligne d'état et la fiche de l'atome disent quand la contrainte agit (« Si1 tenu à −2,5 e »). Un résultat
encore hors de toute borne (plus négatif que −3 e, ou plus d'électrons perdus que l'atome n'en a) arrête la
dynamique sur le dernier état valide, avec une explication ; aucun ne s'est produit dans les essais à 4 000 K.

**Le temps réel.** Le moteur publie un instantané au plus tous les 25 ms (les pas s'enchaînent entre deux) et
le relaie aussitôt ; la grille de densité n'est calculée que pour les vues qui la montrent (Δρ, ψ, carte de
potentiel) — le halo et les liaisons n'en ont pas besoin. La séparation σ / π ne lit que les blocs des deux atomes
de P S (calculé une fois par pas), les intégrales de moment sortent du même calcul que le recouvrement, les paires
sont décomposées d'un bloc. Benzène : 94 pas/s et 29 images/s (avant : 58 et 13) ; une pause ou une température
s'applique en 30 ms. Acide laurique (38 atomes) : 7 pas/s. Côté client, une commande reste affichée telle qu'on
l'a donnée jusqu'à ce que le moteur la confirme ; les panneaux de chiffres se rafraîchissent à 8 images/s pendant
le mouvement ; les arcs ne sont retracés que si ce qui les guide a bougé de plus de 0,2 pixel.

Les images des vues Δρ et ψ ne sont refaites que pour une nouvelle grille ou quand on tourne la vue (un réseau
plus lâche le temps du geste) ; quand seul le centre suivi par la caméra bouge, elles se décalent avec lui.
Les grilles (pour la carte de potentiel, Δρ et ψ) arrivent à 52³ points au plus (30³ pendant la dynamique) ; une grille plus
lâche que 0,22 Å est raffinée côté client par interpolation cubique (Catmull–Rom), qui conserve les points du moteur.

## Calculé ↔ mesuré

Calculé par ce moteur (`python -m moleculelab.validation` → `data/validation.json`), revérifié à chaque passage des tests
(le tableau enregistré doit égaler le calcul refait), affiché dans « Méthode & validation » :

| Grandeur | GFN1-xTB | Mesuré |
|---|---|---|
| r(H–H) | 0,766 Å | 0,741 Å |
| Dₑ(H₂) | 6,36 eV | 4,75 eV |
| r(O–H), angle de l'eau | 0,958 Å, 107,1° | 0,958 Å, 104,5° |
| vibrations de l'eau | 1438 · 3675 · 3787 cm⁻¹ | 1649 · 3832 · 3943 cm⁻¹ (ωₑ) |
| r(C–H), angle du méthane | 1,086 Å, 109,47° | 1,087 Å, 109,47° |
| 2 H₂ + O₂ → 2 H₂O (ΔE) | −9,43 eV | ≈ −5,5 eV (ΔH°₂₉₈ = −483,6 kJ/mol, sans les points zéro) |
| atomisation CH₄ · H₂O · O₂ · CO₂ (Dₑ) | 25,24 · 16,69 · 11,29 · 29,91 eV | 18,2 · 10,1 · 5,21 · 16,9 eV |
| CH₄ + 2 O₂ : liaison totale (Dₑ) | 47,82 eV | 28,6 eV |
| CH₄ + 2 O₂ → CO₂ + 2 H₂O (ΔE) | −15,46 eV | ≈ −8,5 eV |

## Limites (dites à l'écran)

- **Énergies de liaison et de réaction.** GFN1-xTB est paramétré pour les géométries, les conformations et les
  interactions faibles ; ses énergies par rapport aux atomes isolés sont 1,3 à 2,2 fois trop fortes (H₂ 1,34 ; CH₄
  1,39 ; H₂O 1,65 ; CO₂ 1,77 ; O₂ 2,17), comparées à Dₑ puisque le modèle n'a pas de point zéro. Ce n'est pas la
  référence atomique : calculer les atomes en spin polarisé ne change que 0,05 eV par atome. Et les énergies de
  réaction sont fausses aussi (1,7 à 1,8 fois trop fortes pour 2 H₂ + O₂ et CH₄ + 2 O₂), ce qu'aucun décalage par
  élément ne peut corriger : une réaction garde ses atomes. Le chiffre est dit sous le bilan, précisé sous son ⓘ,
  et dans le tableau ci-dessus. Les énergies du laboratoire se comparent entre elles, pas à une valeur mesurée.
- **Spin.** L'état électronique est restreint (pas de polarisation de spin dans GFN1) ; le dioxygène est traité avec
  des occupations fractionnaires sur ses deux orbitales π* dégénérées, pas comme un vrai triplet, et GFN1 n'a aucun
  terme d'énergie de spin (O₂ fermé ou « triplet » : 0,07 eV d'écart, au lieu d'environ 1 eV). Il n'y a donc pas de
  barrière de spin : O₂ réagit trop facilement.
- **Réacteur.** Une sphère de 4,9 Å pour 9 atomes, c'est environ 3 000 atm à 4 000 K : un nanoréacteur, pas une flamme.
  La pression est désormais mesurée sur la paroi et réglable (le piston, 100 à 20 000 atm) ; plus bas, rien ne se
  rencontrerait à l'écran. Avec deux ou trois molécules, ce n'est pas un gaz macroscopique : la pression fluctue, et
  le piston de Berendsen tient sa moyenne sans donner les fluctuations exactes d'un ensemble NPT. Le temps : 0,25 fs
  par pas, 30 à 250 pas/s selon la taille, soit 8 à 60 fs par seconde d'écran (la ligne d'état le dit) — une
  picoseconde prend de 15 s à 2 min, et une collision qui réagit est rare : chauffer, serrer le piston ou lancer une
  étincelle. Un radical réagit presque sans barrière : H· + Cl₂ → HCl + Cl· en 80 à 90 fs (6 essais, 300 et 1 000 K),
  quand H₂ + Cl₂ ne réagit pas en 2 ps (4 essais). Mais GFN1, sans spin, garde ensuite Cl· accroché à HCl en Cl–H–Cl
  symétrique (1,40/1,42 Å à 300 K) : un état de transition réel qu'il prend pour un minimum.
  Six trajectoires de CH₄ + 2 O₂ (4 ps, 2 500 et 4 000 K) : l'abstraction d'un H vers CH₃ + HO₂ ou CH₃ + H, la voie
  radicalaire attendue, apparaît dans quatre d'entre elles ; toutes réagissent en moins de 1 ps, même à 2 500 K, bien
  trop vite (densité, liaisons trop fortes, pas de barrière de spin) ; aucune n'atteint CO₂ + H₂O en 4 ps : elles
  finissent en CH₂O₂ + H₂O₂ (quatre) ou en un seul agrégat CH₄O₄ (deux). Le laboratoire montre donc des mécanismes
  possibles, pas des vitesses ni une combustion complète.
- **Noyaux classiques.** Pas d'énergie de point zéro dans la dynamique, pas d'effet tunnel des protons.
- **Charges qui s'emballent.** Pour quelques géométries (CH₄ plan carré comprimé à 1,07 Å, Ba₂Si, Pm₄ dissocié), la
  fonctionnelle seule donne des anions absurdes ; dxtb aussi. La contrainte des anions les tient à −2,5 e — un ajout
  à GFN1, dit à l'écran. Ces géométries peuvent avoir plusieurs solutions auto-cohérentes (CH₄ plan carré : C à
  −2,5 e, ou une solution oscillante vers −1,3 e) : le cycle suit celle du pas précédent.
- **Fragments séparés à couches ouvertes.** Des atomes isolés aux couches d ouvertes (Pm₄ dissocié) n'ont pas de
  point fixe atteignable à 300 K : les électrons y sont lissés à une température plus haute (3 000 K), affichée.
  L'énergie libre change alors un peu de surface : un saut d'énergie peut accompagner le passage d'une
  température électronique à l'autre.
- **Couleurs des charges.** Le potentiel affiché est celui des charges de Mulliken (le modèle électrostatique de
  GFN1), pas le potentiel exact de toute la densité.
- **Dictionnaire et assembleur.** Les noms viennent du graphe, pas de la géométrie : des stéréo-isomères (cis et
  trans, énantiomères) portent le même nom, et une espèce absente des 151 n'a que sa formule. « Tient » dit que
  GFN1-xTB trouve un minimum pour la molécule seule, dans le vide, à 0 K : ni qu'elle existe, ni qu'elle dure à
  température ambiante ou au contact d'autres (un radical « tient », puis réagit au premier choc) ; sa cohésion est
  celle de GFN1 (1,3 à 2,2 fois trop forte).
- **Taille.** Au plus 40 atomes : le calcul est en numpy (≈ 10 ms par pas pour le méthanol, 30 ms pour neuf atomes).

## Fichiers

| Fichier | Rôle |
|---|---|
| `xtb.py` | GFN1-xTB : base, recouvrements, H⁰, électrostatique, répulsion, D3, liaison halogène, cycle auto-cohérent, gradients |
| `integrals.py` | Recouvrements de gaussiennes et leurs dérivées (Obara–Saika), valeurs des orbitales sur une grille |
| `analysis.py` | Indices de Mayer, partage des liaisons, espèces (nommées), dipôle, modes de vibration |
| `dictionary.py` | Le dictionnaire : SMILES, empreinte des graphes, noms français et anglais, départs de dessin 2D |
| `assembly.py` | L'assembleur : le dessin reçu (`MOLECULE_ASSESS`, `MOLECULE_BUILD`), sa pose en 3D, les verdicts |
| `AtomCard.java`, `MoleculeCard.java` | La carte du survol d'un atome et la fiche d'une molécule au clic droit (client) |
| `density.py` | ρ, Δρ et ψ sur une grille, et leur codage pour le client |
| `lab.py` | La session : établi, relaxation, dynamique, traction, étincelle, réacteur, vibrations, dans un fil à part |
| `structures.py` | Les molécules de départ, les mélanges du réacteur, les noms des espèces |
| `validation.py` | Le tableau calculé ↔ mesuré, et le texte de la méthode |
| `frame.py` | Le laboratoire dans la frame (`MoleculeLabState`) |
