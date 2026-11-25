# Le laboratoire moléculaire — électrons, liaisons, forces

Le laboratoire (**Molécules**, sur l'accueil) pose des atomes sur un établi et calcule leurs
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

**Loin de toutes les références de D3.** Les C6 sont interpolés entre les références de chaque élément par des poids
gaussiens exp(−4 (NC − NC_réf)²). Un gros alcalin très entouré (Cs⁺ contre une molécule : NC ≈ 11, ses références
sous 2) a des poids vers 1e-200 : leur somme tient encore, mais son carré, au dénominateur de leur dérivée, s'annulait —
un gradient NaN pour toute la molécule (la relaxation refusait chaque pas, figée ; l'élimination par CsF de l'exemple
du diène silylé restait sur place). Quand la somme passe sous 1e-140, les mêmes poids sont recalculés en retranchant
le plus grand exposant (identiques en valeur, sans dépassement) ; au-dessus, rien ne change, au bit près. Vérifié
contre les différences finies (Cs au milieu de douze F, tests/test_moleculelab.py).

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
| piston | une pression imposée (100 à 20 000 atm, 0,01 à 2 GPa) : le volume de la sphère suit dV/V = (dt/τ)(P − P₀)/P₀ (Berendsen appliqué à la paroi, pas aux atomes : les molécules gardent leur forme), P la pression moyennée sur 200 fs, τ = 300 fs, au plus 1 % du volume par pas — pour serrer, sur ln(P/P₀) plutôt que (P − P₀)/P₀ (les mêmes près de la consigne) : une sphère encore vide (P ≈ 0) plafonnait à −1 et se fermait en 670 fs (deux eaux, 10 000 atm : 75 % de la consigne) ; en 254 fs ainsi, sans plus de dépassement (12 800 atm). Plus vite, la paroi irait plus vite que les atomes (≈ 1 Å par 100 fs) et les écraserait au lieu de les comprimer. Activé en relaxation ou en pause, le piston met la dynamique à température en route (il n'agit que si les atomes bougent). Sur la pression instantanée (τ = 100 fs), qui vaut zéro entre deux chocs puis bondit, la paroi tremblait à chaque contact : 16 mÅ en 50 fs à 2 000 atm (H₂ + Cl₂), 2 mÅ ainsi ; la consigne reste tenue à 3–6 % (2 000, 10 000, 20 000 atm). La vue se pose sur le centre de l'enceinte : la sphère ne bouge pas, les molécules bougent dedans. Son bord est teinté par la pression (bleu faible, ambre forte) ; des chevrons y montrent quand le piston serre ou cède ; un manomètre en haut à gauche donne la mesure sur une échelle logarithmique (10 à 30 000 atm), la consigne en doré, et ce que fait le piston (à 25 % près). Sur 6 ps, la moyenne tient la consigne à 4 % près. Pourquoi pas 1 atm : à 1 500 K, une molécule n'en rencontre une autre que toutes les ~170 ps, plus d'une heure de calcul ; à 100 atm ~2 ps, à 10 000 atm ~25 fs |
| vibrations | hessienne par différences centrées des forces analytiques (pas de 0,005 Å), pondérée par les masses, sans translations ni rotations ; « exciter » un mode donne n quanta hν, et la dynamique (anharmonique) prend le relais |
| isotope | un atome (ou tous ceux d'un élément) prend la masse d'un de ses isotopes : les stables, et les radioactifs d'au moins un an de demi-vie (¹H ²H ³H, ¹²C ¹³C ¹⁴C, ¹⁶O ¹⁷O ¹⁸O…), masses de l'AME et abondances IUPAC (base mendeleev). Seule la masse change : la surface d'énergie, la géométrie, les charges restent (Born–Oppenheimer) ; la dynamique et les vibrations changent. H₂O → D₂O : 1 440, 3 677, 3 788 → 1 054, 2 650, 2 779 cm⁻¹, rapports 0,73, 0,72, 0,73 (mesurés : 0,74, 0,73, 0,74) ; HDO découple ses élongations (O–D 2 712, O–H 3 736 cm⁻¹). L'isotope s'écrit en exposant devant le symbole (²H, ¹³C) |
| assembleur 2D | un dessin à plat, vérifié par le calcul pendant qu'on dessine, puis posé en 3D et relaxé dans le simulateur (voir « L'assembleur 2D et le dictionnaire ») |
| réaction du dessin | réactifs → produits : les molécules du dessin agitées ensemble dans une sphère, en trois essais ; les issues trouvées, avec leur ΔE et leurs produits, à redessiner ou à ouvrir en 3D (voir « La réaction du dessin ») |
| expériences | cinq réactions réelles, de la plus exigeante à la plus simple (`experiments.py`) : Diels-Alder (buta-1,3-diène s-cis + éthylène → cyclohexène), SN2 (OH⁻ + CH₃Cl → CH₃OH + Cl⁻), transfert de proton (H₃O⁺ + NH₃ → H₂O + NH₄⁺), recombinaison de radicaux (2 CH₃· → éthane), formation de l'eau (OH· + H· → H₂O). Chaque réactif est relaxé seul (L-BFGS, GFN1-xTB), les deux sont posés face à face par les atomes qui réagissent (l'OH⁻ à l'opposé du chlore, l'éthylène à plat à 3,2 Å au-dessus des extrémités du diène), puis lancés le long de la ligne de leurs centres avec une énergie de choc exacte (quantité de mouvement nulle), plus l'agitation de chaque réactif à 300 K sans sa translation. Dynamique libre pendant le choc, puis le thermostat prend le relais (le gaz autour, qui emporte l'énergie libérée). Réussie quand le dictionnaire reconnaît les produits ; manquée après une limite de temps. Réglées par le calcul : chacune réussit sur 3 à 6 tirages de l'agitation (tests/test_experiments.py en rejoue un), et les produits tiennent 600 fs après le choc |
| molécules du vivant | glucose (β-D-glucopyranose), acide laurique (acide gras saturé en C12, un lipide), glycérol, alanine, caféine : les conformères 3D de PubChem (MMFF94), relaxés par GFN1-xTB en 70 à 85 pas sans qu'une liaison casse ; 13 à 38 atomes (au-delà de 40, le calcul ne suit plus en temps réel) |

Les espèces présentes sont lues dans les indices de liaison de Mayer, B_AB = 2 Σ_σ Σ (P^σ S)_μν (P^σ S)_νμ : des
atomes reliés par B ≥ 0,5 forment une espèce (seuil de lecture pour nommer ; le calcul n'en dépend pas), pourvu
qu'ils partagent encore des électrons entre eux (partage ≥ 0,09, voir « Liaison rompue, indice resté haut » plus
bas) : sans cela, deux O₂ séparés de 3 Å, dont l'un garde un indice de 1 avec un atome de l'autre, se liraient O₄.
Un changement d'espèces qui dure 20 fs est annoncé comme une réaction, chaque espèce nommée par le dictionnaire.

## L'assembleur 2D et le dictionnaire

**Le dictionnaire** (`dictionary.py`) : 152 espèces écrites en SMILES (le sous-ensemble organique : hydrogènes
implicites, atomes entre crochets, liaisons `=` et `#`, branches, fermetures de cycle ; forme de Kekulé), du proton et
des atomes seuls aux molécules du vivant, avec des ions (OH⁻, H₃O⁺, NH₄⁺), des radicaux (OH, CH₃, HO₂) et des
isomères (éthanol et éther diméthylique). Une espèce est reconnue par son graphe — qui est lié à qui, hydrogènes
compris — et sa charge nette : l'empreinte de Weisfeiler–Lehman (quatre tours d'étiquettes des voisins) et la formule.
Les ordres de liaison n'y entrent pas : le calcul ne les donne pas entiers (Mayer 1,4 dans le benzène). Les 152
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

### La réaction du dessin

La carte **07 Réaction** de l'assembleur demande au moteur ce que deviennent les molécules dessinées
(`reaction.py`, action `MOLECULE_REACT = 112`, réponse `MoleculeLabState.reaction`). Ce n'est pas une recherche
exhaustive d'états de transition (trop coûteuse pour un outil interactif), mais un **nanoréacteur** (Wang et al.,
*Nat. Chem.* 6, 1044, 2014) :

1. chaque molécule du dessin est posée en 3D comme à l'envoi et relaxée seule (L-BFGS) ; puis toutes sont rapprochées
   ensemble, dans la disposition du dessin, jusqu'à ce que leurs deux atomes les plus proches soient à 2,8 Å
   (`_pack` : leurs centres glissent sur des rayons issus du centre commun — dessinées loin l'une de l'autre, elles ne
   se rencontreraient pas en 600 fs) ;
2. un essai : la sphère du réacteur posée sur leurs nuages, les vitesses tirées à la température choisie (1 500,
   2 500 ou 4 000 K), le thermostat CSVR et le bain de chaque atome pendant 600 fs, puis 120 fs à 300 K ;
3. chaque espèce présente à la fin (le recensement du laboratoire : Mayer et densité partagée) est relaxée seule ; si
   elle se défait en se relaxant, ses morceaux le sont à leur tour. Ce sont les produits de l'essai ;
4. trois essais, aux vitesses de départ différentes ; ceux qui mènent aux mêmes produits sont regroupés en une issue :
   combien d'essais, ΔE = Σ E(produits seuls) − Σ E(réactifs seuls) (GFN1-xTB à 0 K, sans énergie de point zéro), et
   quand les espèces ont changé pour la première fois.

L'écran dit d'avance ce qu'un essai peut franchir : une barrière Eₐ se passe à peu près une fois en 3 × 600 fs si
ν·t·e^(−Eₐ/kT) ≈ 1, avec ν = 10¹³ s⁻¹ (une vibration), soit Eₐ ≈ kT·ln(18) ≈ 2,9 kT : 0,37 eV à 1 500 K, 0,62 eV à
2 500 K, 1,0 eV à 4 000 K — un ordre de grandeur, pas une mesure. Pendant le calcul, la carte suit l'essai (son
temps, ses espèces, le temps de calcul restant d'après le rythme jusqu'ici) ; « Arrêter » l'annule, comme une
nouvelle demande. Chaque issue montre ses produits dessinés (la mise en page de l'assembleur), leurs noms,
ΔE, et deux gestes : **Dessiner** (les produits remplacent le dessin ; Ctrl+Z revient aux réactifs) et **En 3D**
(les produits dans le simulateur ; le dessin ne change pas). Au plus 30 atomes : un essai coûte ≈ 2 500 pas de
0,25 fs, de l'ordre de 10 s pour trois atomes et de 30 s pour six.

Vérifié par le calcul (3 essais × 600 fs ; tests/test_reaction.py rejoue le premier) :

| Dessin | Température | Issues | ΔE GFN1 | Mesuré |
|---|---|---|---|---|
| OH· + H· | 1 500 K | H₂O 3/3, premier changement vers 220 fs (30 s de calcul en tout) | −8,1 eV | −5,1 eV (D₀) ; −5,4 sans point zéro |
| OH⁻ + H₃O⁺ | 1 500 K | 2 H₂O 3/3, dès 34 fs | −10,9 eV | ≈ −9,8 eV (en phase gazeuse) |
| Cl· + CH₄ | 2 500 K | rien 2/3 ; CH₃· + HCl 1/3, à 547 fs | +0,61 eV | ≈ +0,1 eV |
| H₂ + Cl₂ | 4 000 K | rien 3/3 | — | (la vraie réaction passe par des radicaux, Cl· + H₂) |
| H₂O₂ seul | 2 500 K | rien 3/3 | — | (O–O tient ≈ 2,2 eV) |

### Le solvant implicite (ALPB)

Le laboratoire (menu **Milieu**, en bas) et le schéma réactionnel (un solvant écrit sous une flèche : eau, MeCN, DMF,
DMSO, THF, MeOH, CH₂Cl₂…) placent la molécule dans un **solvant implicite** : le modèle ALPB de GFN1-xTB (Ehlert,
Stahn, Spicher, Grimme, *J. Chem. Theory Comput.* 17, 4250, 2021), transposé de tblite, l'implémentation de référence
de xtb (`solvation.py`), avec ses paramètres publiés pour 25 solvants (`data/gfn1-alpb.json`, construit par
`tools/build_solvation_data.py` depuis les sources de tblite et mctc-lib à des commits fixés ; LGPL-3.0). Quatre termes :

- **polaire** : Born généralisé, G_pol = ½ Σ q_i q_j k_ε / f(r_ij), noyau P16 f = r + R (1 + ζr/16R)⁻¹⁶ (R = √(R_i R_j),
  ζ = 1,028), k_ε = (1/ε − 1)/(1 + α/ε), plus la correction ALPB ½ k_ε (α/ε) (Σq)² / A_det (α = 0,571412, A_det une
  taille tirée du tenseur d'inertie). Les rayons de Born R_i suivent Onufriev–Bashford–Case (intégrale d'écrantage
  par les sphères des voisins, rayons de van der Waals D3 × un facteur par élément et par solvant). Les charges sont
  celles de Mulliken de GFN1 plus la correction CM5, comme dans xtb pour GFN1 ;
- **liaisons hydrogène** : Σ h_i S_i q_i², S_i la surface accessible au solvant ;
- **non polaire** : Σ γ_i S_i (cavité, dispersion) ;
- **décalage** : une constante par solvant.

Le potentiel ∂G/∂q entre dans le cycle auto-cohérent : les électrons répondent au solvant (et la réponse linéaire du
Newton en tient compte). La surface est intégrée sur la grille de Lebedev de 230 points (SciPy), lissée sur ±0,3 Å.
Le gradient est analytique (la dérivée de l'intégrale d'écrantage par la distance : différence centrale).

Vérifié par le calcul (tests/test_solvation.py) :

- les gradients analytiques collent aux différences finies de l'énergie à 10⁻⁷–10⁻⁸ Eh/Å près (méthanol dans le
  DMSO, acétate dans l'eau) ; la surface lissée d'un atome isolé vaut 4πR² à 0,06 % près ;
- les énergies libres d'hydratation, chaque espèce relaxée dans le vide puis dans l'eau, contre la mesure (Minnesota
  Solvation Database ; ions : Kelly, Cramer, Truhlar 2006) :

| Espèce | GFN1/ALPB (kcal/mol) | Mesuré |
|---|---|---|
| acétone | −3,6 | −3,8 |
| acétonitrile | −3,8 | −3,9 |
| benzène | −0,4 | −0,9 |
| méthanol | −6,0 | −5,1 |
| acide acétique | −8,0 | −6,7 |
| hexane | +1,0 | +2,5 |
| ammoniac | −9,9 | −4,3 |
| eau | −13,5 | −6,3 |
| méthylammonium | −66,6 | −73,0 |
| hydroxyde | −74,4 | −105 |
| I⁻ (atome seul) | −54 | ≈ −60 |

  Les molécules organiques sont à 1 kcal/mol environ ; l'eau et l'ammoniac, petits et très liés par liaisons
  hydrogène, sont trop solvatés (le terme de liaison hydrogène de GFN1/ALPB, ajusté sur de plus grosses molécules) ;
  les petits anions durs (OH⁻) ne le sont pas assez, comme dans tout continuum. L'acétate n'est pas dans le tableau :
  dans le vide, GFN1 ne le converge qu'en lissant ses occupations à 15 000 K, ce qui fausse sa référence (−90 kcal/mol
  d'entropie électronique) — un défaut du vide, pas du solvant (dans l'eau, il converge à 300 K).

Le surcoût : +50 % par calcul (56 atomes : 0,15 → 0,22 s). Ce qu'il ne corrige pas : l'énergie des ions de GFN1
lui-même (affinité électronique du chlore 9,8 eV calculés, 3,6 mesurés ; ionisation du sodium 8,1 pour 5,1). Ainsi
NMe₃ + MeI → NMe₄⁺ + I⁻ vaut +8,9 eV dans le vide (ions séparés) et +4,6 eV dans l'acétonitrile : le solvant retire
4,3 eV, ce qu'il doit (NMe₄⁺ −2,2 eV, I⁻ −2,3 eV, comme les mesures), mais la réaction se fait en solution — l'écart
restant, ≈ 5 eV, est celui de GFN1 sur l'énergie des ions, et l'écran le dit pour toute étape qui crée ou détruit des
ions. Rien de tout cela n'est vérifié contre xtb lui-même : son binaire officiel (6.8.0) a été téléchargé et sa somme SHA-256
contrôlée, mais pas exécuté ici.

### Le schéma réactionnel

Le bouton **Schéma de réaction** (en-tête du laboratoire) ouvre un éditeur de chimiste : on dessine en formule
squelettique (les carbones aux sommets, les hydrogènes implicites, des cycles de 3 à 7 et le benzène, des abréviations
Me, Et, Ph, OMe, TMS, Ac, CO₂Me, Boc…), on trace des **flèches de réaction** avec, au-dessus, les réactifs (MeI, CsF,
Co₂(CO)₈, H₂, NaBH₄, OsO₄… une soixantaine, un nom du dictionnaire ou un SMILES) et, au-dessous, les conditions
(un solvant parmi les 25 d'ALPB devient le milieu de l'étape, voir plus haut ; température, Δ, hν : dites, pas
simulées), et, si l'on veut, les **flèches courbes** des électrons. Un
SMILES collé se dessine (RDKit) ; des exemples tout faits s'ouvrent du menu. Le moteur (`scheme.py`, action
`MOLECULE_SCHEME = 113`, texte JSON ; réponse `MoleculeLabState.scheme`) compile le dessin :

1. **les étapes** : chaque flèche relie les molécules derrière sa queue (en chaîne, tant qu'elles se touchent presque)
   à celles devant sa pointe ; un schéma en serpentin (flèches vers la droite, vers le bas, vers la gauche) se lit
   comme sur papier, et une étape dont un réactif est le produit d'une autre vient après elle ;
2. **qui devient quoi** : avec des flèches courbes, exactement — chaque flèche déplace une paire d'électrons (d'un
   doublet ou d'une liaison vers un atome, une liaison existante ou à former), ce qui donne les ordres de liaison et
   les charges formelles d'arrivée ; le graphe obtenu doit être celui du produit dessiné (isomorphisme, networkx),
   sinon l'écran le dit et le calcul suit le dessin. Sans flèches : plusieurs correspondances sont essayées (la plus
   grande sous-structure commune, RDKit FMCS, avec tous les réactifs ou molécule par molécule dans chaque ordre, les
   liaisons de tout ordre ou d'ordre exact), complétées par les hydrogènes, et la plus économe en liaisons formées,
   rompues ou changées est gardée. Ce qui ne va dans aucun produit dessiné forme les **sous-produits**, déduits des
   valences (un N⁺ qui perd une liaison redevient neutre, un CO qui perd son métal est [C⁻]≡[O⁺], un cation et un
   anion covalents se lient : Me₃Si⁺ + F⁻ → Me₃SiF) ;
3. **le calcul** (une étape de 60 atomes ≈ 450 calculs GFN1, environ 2 minutes sur une machine libre ; les résultats
   arrivent étape par étape, et une étape calculée se rejoue déjà pendant que les suivantes se calculent) : chaque réactif relaxé seul (sa géométrie de l'étape précédente s'il en sort, sinon son conformère
   le plus stable parmi six, RDKit ETKDG puis UFF), chaque produit et sous-produit de même ; ΔE = Σ E(produits seuls)
   − Σ E(réactifs seuls). Le chemin part du **produit** : posé avec ses sous-produits (le groupe partant à l'opposé
   de l'attaquant), puis les liaisons formées étirées jusqu'au contact (≈ r₁ + r₂ + 1,4 Å) et les liaisons rompues
   ramenées à leur longueur, en 11 paliers (contrainte harmonique de 27 eV/Å², tout le reste relaxé par L-BFGS à pas
   bornés, voir plus bas) ; le
   complexe des réactifs ainsi obtenu, relaxé sans contrainte (ses molécules tenues à 2,6 Å au moins), sert de
   départ au même chemin à l'endroit. Tiré dans un seul sens, un chemin reste sur sa branche (le cyclohexène étiré
   garde ses carbones sp³ jusqu'à 2,5 Å, 6 eV au-dessus du produit, puis bascule) : à chaque palier, l'image de l'une ou
   l'autre branche est gardée, la suite la plus basse (sa plus haute image, puis la somme) parmi celles dont deux
   images voisines se rejoignent — on ne change de branche que là où elles se rejoignent (voir « Les sauts »). Partir du produit compte : tirée depuis un butadiène s-trans, la fermeture
   du cycle donnait un trans-cyclohexène, 2 eV trop haut ;
4. **le verdict** : chaque produit, relaxé seul jusqu'à ce que ses forces retombent (trois départs au plus), garde-t-il
   les liaisons dessinées ? Une liaison est perdue si elle s'allonge au-delà de 1,3 fois la somme des rayons
   covalents, une nouvelle comptée si GFN1 la voit (Mayer, densité partagée) à moins de 1,15 fois — les indices de
   Mayer seuls se trompent sur les liaisons à plusieurs centres (le cœur Co₂C₂ : sous 0,5). Les ions formés dont les
   charges se compensent sont comptés en paire d'ions, comme le sel écrit sur la flèche l'est côté réactifs. La barrière est la plus haute énergie du chemin au-dessus du complexe de départ.

Après le chemin imposé, les molécules formées s'écartent de la plus grosse (1, 2 puis 3 Å, chaque image calculée),
comme les réactifs arrivaient : un sous-produit s'en va au lieu de rester collé au produit. À l'approche, deux
molécules s'écartent l'une de l'autre ; à partir de trois (une molécule et les deux ions d'un sel), les autres
s'écartent de la plus grosse ; une image d'approche ou de départ où deux atomes de morceaux différents seraient plus
proches qu'au contact n'est pas montrée (CsF près d'un ammonium, écartés depuis leur centre commun, plaçait F⁻ dans un
méthyle : +316 eV).

**La relaxation : des pas bornés.** Chaque relaxation d'un schéma (réactifs, produits, paliers, complexe de départ)
est un L-BFGS à pas bornés, celui d'ASE : la direction des deux boucles de L-BFGS, raccourcie pour qu'aucun atome ne
bouge de plus de 0,2 Å, divisée par deux tant que l'énergie monte ; pas de recherche linéaire. Celui de SciPy
(L-BFGS-B) pouvait s'arrêter sur place : son premier pas, long de 1 Å en tout et porté par les quelques atomes que
tire une contrainte, menait à +12 eV ; sa recherche linéaire n'acceptait qu'un pas infime, et son test de réduction
relative (rapportée à une énergie de 87 Eh) annonçait la convergence. Mesuré sur un palier de l'élimination par CsF :
L-BFGS-B, 3 calculs, rien n'a bougé ; à pas bornés, 13 calculs, les trois distances à leur cible (Si–C 2,585 Å pour
2,585). L'étape entière : 237 s au lieu de 288, et un chemin qui avance à chaque palier (la barrière de ce chemin,
1,08 eV au lieu de 3,80 : l'ancienne venait du palier figé, à +16 eV dans l'autre sens).

Pendant le calcul, l'éditeur montre ce que le moteur calcule (`MoleculeScheme.live`, `.log`) : la géométrie en cours
de relaxation, à chaque pas de L-BFGS (les distances tirées en tirets), le profil qui se remplit palier par palier, à
rebours puis à l'endroit, et le journal — chaque relaxation et chaque palier, sa molécule, son énergie (« produit
relaxé seul, il tient · C₆H₁₀ · E −18,07236 Eh », « chemin à l'endroit, palier 4/12 · +3,00 eV · C1–C7 2,53 Å »),
et le nombre de calculs GFN1-xTB (666 pour Diels–Alder, en 6 s). Une fois le calcul fini, l'écran se dit fini puis
se replie (le journal reste à un clic). Pendant le calcul, le dessin est verrouillé (il est celui qu'on calcule ;
Arrêter le libère), et les cartes des étapes ne sont refaites que quand une étape s'ajoute : le moteur publie toutes
les 0,15 s, et des cartes refaites à chaque publication perdaient les clics (« Voir le chemin » qu'il fallait
recliquer, « Limites et remarques » qui se repliaient aussitôt). Ces publications sont légères : les étapes (les images
de chaque chemin, des milliers de nombres) ne partent qu'à leur changement ; entre-temps, le message les dit gardées
(`MoleculeScheme.steps_kept`) et le client garde les siennes.

Le moteur calcule sur deux fils d'algèbre linéaire (`OPENBLAS_NUM_THREADS`, posé par engine.py s'il n'est pas donné) :
OpenBLAS prenait tous les cœurs pour des matrices de 150 à 250 orbitales, où se synchroniser coûte plus que calculer
— un calcul de 62 atomes prenait 270 ms en occupant 1,9 cœur, il en prend 196 en en occupant un ; la machine et
l'interface gardent le reste.

Chaque étape se rejoue dans sa vue de lecture (plus bas), qui la joue seule, ou dans la scène 3D
(`MOLECULE_SCHEME_PLAY = 114`) : là, le moteur pose les géométries du chemin et
recalcule chacune — densité, liaisons, énergie, espèces présentes —, tout ce que la scène montre d'habitude. Entre deux
images, chaque corps rigide (les atomes liés dans les deux images) glisse et tourne de l'une à l'autre par la rotation
la plus courte, seule sa déformation propre étant interpolée en ligne droite : en ligne droite pour tous les atomes,
un CO expulsé posé à l'envers par les deux branches du chemin passait son oxygène à travers son carbone (+31 eV à
mi-chemin pour GFN1-xTB, des pics sur la courbe d'énergie) ; par corps rigide, chaque géométrie intermédiaire reste
entre ses deux images (complexe alcyne–dicobalt : 0 à 2,53 eV le long du chemin, comme les images). Pendant la
lecture, la scène ne montre que la réaction : les liaisons qui se forment (tirets verts) et se rompent (tirets orange),
d'autant plus épaisses que leur indice de Mayer à cet instant est grand, avec sa valeur ; les atomes qui réagissent,
nommés comme dans la carte (C2, Co13) ; ni signes δ ni étiquettes d'espèce ; un cadre fixe qui tient tout le chemin,
à droite de la carte. Les espèces présentes suivent l'image montrée, sans annonce au journal (le temps ne passe pas).
Une carte en bas à gauche porte le transport, le profil d'énergie avec la position courante et les liaisons qui
changent (formées et rompues d'abord, colorées comme dans la scène) ; toucher la scène (chauffer, tirer un atome) la
rend libre. L'éditeur montre le profil de tout le schéma (un palier par étape, Σ ΔE ;
une bosse par barrière) et, pour chaque étape, ΔE, la barrière, le verdict, les sous-produits déduits et les limites.

**La vue du chemin (le replay).** « Voir le chemin » ouvre l'écran du chemin (`SchemeStage`, `StageRenderer`) à la
place de la scène du laboratoire : un replay en 2.5D, dessiné sur un canevas, dans le fond même de la scène du
laboratoire (son bleu nuit, rien d'autre : pas de décor qui ne mesure rien). Les atomes sont des billes de verre teinté
calculées pixel par pixel (rayon 0,14 Å + 0,32 × rayon covalent : H 0,24 Å, C 0,38 Å, Si 0,51 Å) de la couleur de leur
élément — la teinte de la table CPK pour la nuit : le carbone acier bleuté, l'hydrogène blanc, l'azote bleu roi,
l'oxygène rouge corail — : le verre épais, sombre de sa teinte ; la lumière principale, venue d'en haut à gauche, que
la bille concentre de l'autre côté ; le liseré de Fresnel ; le reflet net de la lumière principale et celui, petit, de
la lumière d'appoint ; dehors, un contour sombre qui la détache de ce qui est derrière. Les liaisons sont des bâtonnets
de verre éclairés de même selon leur angle à l'écran. Tout est vu en perspective et dessiné du fond vers l'avant (chaque
atome couvre ce qui est derrière lui) ; ce qui est loin se voile vers le fond. Ce qu'elle montre vient du calcul :

- les images du chemin telles que le moteur les a calculées, rejouées par l'écran lui-même (`PathMotion`) : leurs
  positions, leurs énergies, leurs charges de Mulliken et les indices de Mayer de toutes leurs liaisons
  (`MoleculeSchemeStep.bond_pairs`, `bond_orders` : chaque paire liée, indice ≥ 0,3, dans au moins une image) ; entre
  deux images, chaque corps rigide glisse et tourne de l'une à l'autre par la rotation la plus courte (le quaternion
  de Horn qui aligne l'une sur l'autre, une fraction de son angle), comme le moteur le fait dans la scène 3D, et les
  nombres sont interpolés ; à travers un saut, l'une puis l'autre image, par un bref fondu dit non calculé. La lecture ne dépend ni du moteur
  (qui calcule peut-être encore les étapes suivantes) ni de ses allers-retours : jouer, revenir, cliquer sur la frise
  répondent tout de suite ;
- un, deux ou trois bâtonnets selon l'indice de Mayer calculé là (`MoleculeScene.strokes`) ; une liaison qui se forme
  (vert) ou se rompt (orange) est un filament de verre lumineux, sa lumière tenue par son indice de Mayer m à cette
  géométrie : son épaisseur (0,24 + 0,76 √s du bâtonnet), son opacité et sa lueur suivent s = m / 0,75 — un fil à peine
  visible quand l'indice est faible, un bâtonnet plein à 0,75, de sa couleur ensuite. Sans liaison (pas encore, ou
  plus : m sous 0,02), un trait fin en pointillé relie la paire. Chaque liaison qui change est étiquetée de sa longueur
  et de son indice (« 2,28 Å 0,23 », sans cadre ; une étiquette sans place se tait) ; celles dont seul l'ordre change
  sont teintées de bleu ;
- au choix (bouton des charges), chaque bille prend la couleur de sa charge de Mulliken à cette géométrie (argent au
  neutre, cyan δ+, magenta δ−, la convention du laboratoire) et un halo de cette couleur, d'autant plus large et vif
  qu'elle est forte ; au choix aussi, les trajectoires des atomes
  qui réagissent (un point par image calculée, pâles pour l'approche et le départ), les flèches courbes du dessin
  posées sur les atomes calculés (une hypothèse sur les électrons, dite comme telle), les hydrogènes qui ne réagissent
  pas ; sur chaque bille assez grande, le symbole de l'élément comme dans une formule squelettique — les carbones et
  les hydrogènes sous-entendus, les autres éléments écrits, les atomes qui réagissent numérotés (tous, sur demande) ;
- la vue elle-même : l'œil regarde le long de l'axe le plus mince des positions des atomes qui réagissent sur tout le
  chemin imposé — les liaisons qui changent sont dans le plan de l'écran (Diels–Alder : l'anneau qui se ferme, vu de
  face) —, puis s'incline de 22° ; la caméra cadre le chemin imposé ; elle tourne, se rapproche (vers la souris), se
  déplace, et revient (double-clic, R).

La légende, courte, dit chaque signe présent en quelques mots et ce qui n'est qu'un décor (le verre et ses reflets) ;
les lueurs, elles, sont calculées (l'éclat des liaisons qui changent ; en mode charges, les halos) ; les explications
sont dans les « ? » du panneau. À droite : l'énergie à la géométrie
montrée en grand, la barrière de ce chemin et ΔE, la courbe par les images (et le point calculé là) ; image par
image, l'indice de Mayer et la longueur de chaque liaison qui change, la charge de chaque atome qui réagit
(`MoleculeSchemeStep.path`, `charges`, `orders` — un clic place le chemin là), et dessous ce qui a le plus changé du
début à la fin, calculé (« C21 gagne 0,25 e · O28 en perd 0,04 e ») ; les liaisons qui changent à cet instant, avec la
jauge de leur indice ; le verdict, le milieu, ce qui est écrit sur la flèche (noms traduits) et les limites. En bas,
la frise : le profil d'énergie du chemin en silhouette (déjà lu, en couleur), ses zones (approche, chemin imposé,
départ), une marque par image, les sauts de branche ; et la vitesse : 1, 2,5 ou 6 images du chemin par seconde, à
l'horloge de l'écran. Pendant la lecture, la vue suit la géométrie image d'écran par image d'écran ; un pas d'une image
ou un clic sur la frise y glisse en quelques centièmes de seconde (et saute d'un coup au-delà de 0,35 Å : un saut de
branche). Le rendu prend 0,06 à 0,25 ms par image sur le fil de l'interface (Robinson 30 atomes, diène silylé 55, vue
fixe ou en rotation ; la scène du laboratoire, 13 à 16 ms), seulement quand quelque chose bouge : chaque bille et
chaque tranche de bâtonnet est calculée une fois (par teinte, taille, voile et angle) puis reposée ; la légende n'est
redessinée que si elle change ; la scène du laboratoire, cachée dessous, n'est plus dessinée. Sur la courbe
d'énergie, un trait pointillé marque ΔE (produits et réactifs relaxés chacun seul) : la dernière image du chemin,
qui garde les molécules formées côte à côte, en diffère (Robinson, étape 1 : −1,28 eV contre −1,45). **Scène 3D**
confie le même chemin au moteur, depuis la position montrée ; la carte de la scène 3D revient à la vue de lecture à
la sienne (et arrête le moteur). Les onglets du laboratoire (ρ, Δρ, ψ, assembleur 2D) referment l'éditeur et la vue
de lecture.

**Les sauts.** Le chemin imposé gardait à chaque palier la plus basse de ses deux branches : là où elles se
croisent, un atome pouvait sauter d'une image à l'autre. Mesuré : un H du Diels–Alder bouge de 1,6 Å entre deux images
voisines, un H de l'étape 2 du Wittig de 2,6 Å ; une géométrie interpolée entre elles n'est sur aucune branche —
GFN1-xTB la trouve 2,1 à 2,4 eV (Diels–Alder) et 9,9 eV (Wittig) au-dessus de la droite entre les deux images (la
lecture la montrait : un point d'énergie bien au-dessus de la courbe). Le moteur repère ces sauts en calculant le
chemin (`lab.internal_motion`, `JUMP`, `JUMP_EV` : un atome bouge de plus de 0,4 Å hors de son corps rigide, après
l'alignement de Kabsch, ET la géométrie du milieu coûte plus de 0,3 eV de plus que la droite ; le complexe au cobalt
fait pivoter ses CO de 0,6 à 1,0 Å d'une image à l'autre, mais le milieu n'y coûte que −0,07 à +0,09 eV : pas de saut)
et les écrit au journal (« saut de branche entre les images 13 et 14 · +2,38 eV au milieu ») ; la lecture passe d'une
image à l'autre (`MoleculeSchemeStep.jumps`), la frise et les courbes les marquent, et la vue le dit.

Deux corrections réduisent ces sauts. Le chemin gardé ne passe plus d'une branche à l'autre là où leurs images ne se
rejoignent pas (chaque branche, chaque palier partant du précédent, est continue) : la suite d'images reliées la plus
basse (`scheme._continuous`). Puis, entre deux images voisines encore séparées, le palier du milieu est calculé
(relaxé sous ses contraintes depuis leur mélange par corps rigides, deux fois au plus en profondeur) : un pas trop grand
s'y résout. Ce qui reste est un vrai décrochage : la géométrie suivie sous contrainte cesse d'exister, et la molécule
tombe d'un coup dans un autre minimum. Robinson, étape 1 : juste après le col (2,61 eV), C2 se pyramidalise de 0,49 Å
d'un palier au suivant — même à un quart de pas — et l'énergie tombe de 1,24 eV. La vue le dit avec ce qu'elle calcule
sur les deux images (« Saut · C2 bouge d'un coup de 0,49 Å, énergie −1,24 eV · passage non calculé, montré en
fondu ») et passe de l'une à l'autre par un bref fondu par corps rigides, au tiers du segment, au lieu d'une coupure.
Relier les deux minima par des images calculées (une bande élastique entre eux, au même palier) n'est pas fait.

**Les étapes qui se suivent, tout le schéma.** Chaque étape était calculée et montrée à part : ses atomes numérotés
dans son propre ordre, ses images posées dans son propre repère, sa vue calculée sur elle seule — de l'étape 1 à
l'étape 2, on voyait deux images sans lien. Maintenant :

- un atome garde son numéro dans tout le schéma (`MoleculeSchemeStep.ids`) : le moteur reprend ceux du produit d'une
  étape pour la molécule qui en vient, et donne de nouveaux numéros, à la suite, aux réactifs écrits sur une flèche
  (aldolisation : O11 et H10 de l'étape 1 sont O11 et H10 de l'étape 2) ; le journal du calcul, la vue, la scène 3D et
  sa carte écrivent les mêmes ;
- les images d'une étape sont tournées et déplacées d'un seul bloc (la rotation de Horn sur ses atomes partagés avec
  l'étape d'où ils viennent : rien d'autre ne change, distances, énergies et charges restent celles du calcul) pour que
  sa première image pose la molécule reprise là où l'étape précédente l'a laissée ; la vue glisse en 0,9 s de la vue
  calculée de l'une à celle de l'autre (tournée à la main, elle reste telle quelle) ;
- **Tout le schéma** (premier onglet de la vue du chemin, ou « Tout le schéma, à la suite » dans l'éditeur) joue les
  étapes à la suite. Entre deux, un relais : la dernière image de l'une, la première de l'autre, sur leurs atomes
  réunis ; la molécule reprise passe de l'une à l'autre par corps rigides, le sous-produit s'efface (première moitié),
  le réactif écrit paraît (seconde moitié) ; rien n'y est calculé, la vue le dit (« Relais · étape 1 → 2 · passage non
  calculé »). La colonne de droite devient celle du schéma : l'énergie depuis les réactifs du départ relaxés chacun
  seul, ΔE de tout le schéma, la plus haute barrière, le profil de toutes les étapes à leur hauteur, la liste des étapes
  (un clic en ouvre une) et le bilan — ce qui se forme et se rompt du début à la fin (par les numéros : une liaison
  formée à l'étape 1 et rompue à l'étape 3 ne compte pas), ce qui entre (écrit sur les flèches) et sort (les
  sous-produits), l'étape à la plus haute barrière et son temps de demi-réaction.

Les hauteurs (`SchemeStage.Levels`) : les réactifs de l'étape k relaxés chacun seul sont à Σ ΔE des étapes d'avant (les
molécules qui ne réagissent pas encore, ou plus, loin des autres : chaque énergie compte une fois) ; le moteur dit où
sont la première et la dernière image de chaque chemin par rapport à ces molécules relaxées seules
(`start_ev`, `end_ev`) ; ses images s'y posent. Vérifié : l'aldolisation catalysée (trois étapes) retombe sur le ΔE de
l'aldolisation directe (−1,46 − 1,23 + 1,70 = −0,99 eV).

Trouvé en route : une molécule reprise d'une étape faite dans un autre milieu gardait l'énergie calculée dans ce
milieu-là — le diacide de l'exemple Diels–Alder puis hydrolyse comptait l'anhydride relaxé dans le toluène pour une
hydrolyse dans l'eau. Elle est maintenant relaxée de nouveau dans le milieu de l'étape, et ce passage est compté
(`transfer_ev` : +0,10 eV pour l'anhydride, du toluène à l'eau) ; l'hydrolyse passe de −0,45 à −0,56 eV.

**Ce que le chemin forme vraiment à son bout.** Le chemin imposé ne tire que les liaisons qui se forment ou se rompent ;
le reste est libre, et GFN1-xTB peut y faire autre chose que le dessin. L'aldolisation catalysée, étape 3 (la
reprotonation) : pendant que H9 passe bien de l'eau à O13, l'hydroxyde qui naît arrache un autre proton, H8, au CH₂
voisin de la cétone (plus acide pour GFN1). Les images de départ écartaient pourtant les molécules du dessin : le OH⁻
dessiné s'en allait en laissant H8 seul au milieu (+3,4 eV dès 1 Å, +5,3 eV à 3 Å ; le complexe y valait 5,9 eV de plus
que ses morceaux seuls). Maintenant, la dernière image du chemin est jugée comme un produit seul (une liaison du dessin
rompue au-delà de 1,3 fois la somme des rayons covalents ; une autre formée si GFN1 la lie sous 1,15 fois — la distance
seule aurait pris le contact Cs⁺…F⁻ pour une liaison et manqué les liaisons à plusieurs centres du complexe au cobalt) ;
les images de départ écartent les molécules qui y sont vraiment ; et si elles diffèrent du dessin, le verdict le dit
(« chaque produit tient seul, mais au bout du chemin imposé GFN1-xTB finit sur un autre produit »), les remarques
nomment les liaisons (« forme O1–H8 et rompt C3–H8 » ; ses images de départ ne montent plus que de 0,21 eV ; recalculés, les neuf autres exemples ne changent pas et ne déclenchent aucune alerte), et le journal aussi.

**Ce que ΔE ignore : l'entropie.** Quand des molécules partent (le complexe alcyne–dicobalt libère 2 CO), ΔE (0 K,
sans entropie) dit la réaction plus coûteuse qu'elle n'est. Le moteur estime −TΔS (`entropy_ev`) : l'entropie de
translation (Sackur–Tetrode, gaz parfait à 1 bar) et de rotation (rotor rigide, nombre de symétrie 1) de chaque
molécule relaxée seule, à la température de l'étape — CO à 298 K : 197,6 J/mol/K (mesuré 197,7) ; H₂O : 188,6 une fois
corrigé de σ = 2 (mesuré 188,8). Ni vibrations ni solvant : un ordre de grandeur. Complexe au cobalt : −TΔS ≈ −0,43 eV
(une molécule de plus au bout), ΔE − TΔS ≈ +1,86 eV — l'écart à l'expérience (la réaction se fait à température
ambiante) vient surtout des énergies métal–ligand de GFN1-xTB, dites dans les remarques. La vue du chemin et la carte
de l'éditeur l'écrivent dès qu'il dépasse 0,05 eV.

**Le temps d'une barrière.** Sous la barrière, son temps de demi-réaction d'Eyring, t½ = ln 2 / k avec
k = (k_B T / h) · e^(−E / k_B T), à la température écrite sur la flèche (sinon 300 K) — la barrière prise pour
l'enthalpie libre d'activation : un ordre de grandeur. Au-delà d'un an (1,22 eV à 300 K), la vue dit que ce chemin
imposé, sans catalyseur, ne se fait pas, et propose de dessiner les étapes du mécanisme réel. La remarque dépend de la réaction :
si un H passe d'un atome à un autre, la catalyse acide ou basique ; sinon (le complexe au cobalt), un chemin concerté
qui n'est sans doute pas le mécanisme, à découper en étapes. Sous une barrière basse, la vue rappelle que celle de
GFN1-xTB est souvent trop basse (le Diels–Alder « en moins d'une milliseconde » est sans doute plus lent). Vérifié sur
l'aldolisation dans l'eau : sans catalyseur, 3,9 eV (plus que l'âge de l'Univers) ; dessinée avec OH⁻ (l'énolate,
l'addition, la reprotonation — l'exemple « Aldolisation catalysée par OH⁻ »), 0,83, 0,11 et 2,36 eV. La reprotonation
par l'eau reste haute et coûteuse (+1,70 eV) : GFN1-xTB lie mal OH⁻ dans l'eau (voir le solvant implicite).

**Le relief.** Le bouton des lunettes de la vue du chemin : anaglyphe rouge-cyan (les deux yeux de part et d'autre de
la cible, à ±1,5° ; l'œil gauche en gris dans le rouge, l'œil droit en couleur dans le vert et le bleu — un anaglyphe
« demi-couleur », qui évite qu'un atome rouge ne se voie que d'un œil ; le fond passe au gris), puis côte à côte
(l'œil gauche à gauche : visiocasque, écran 3D, vision parallèle). Les textes à plat (la fiche, la pastille, l'échelle)
sont les mêmes pour les deux yeux.

**Depuis ChemDraw et MarvinSketch.** L'éditeur lit ce que ces logiciels copient ou enregistrent (`scheme.imported`,
par RDKit) : un bloc MDL (MOL ou SDF, V2000 ou V3000 — « Copier comme MOL »), un bloc RXN, du CDXML (ChemDraw : ses
molécules, et ses étapes de réaction avec leurs flèches), du MRV (MarvinSketch, molécule ou réaction), un SMILES de
réaction (« A.B>>C ») ou un SMILES. « Coller » (ou Ctrl+V sur le dessin), « Ouvrir un fichier… », ou un fichier glissé
sur le dessin ; sous Windows, le format MDL que ChemDraw dépose dans le presse-papiers (MDLCT) est lu aussi. Les
positions 2D du fichier sont gardées (une liaison y mesure 1, comme dans un dessin), les hydrogènes deviennent
implicites, les agents d'une réaction s'écrivent au-dessus de sa flèche. Le CDX binaire de ChemDraw ne se lit pas
(l'enregistrer en CDXML).

**D'un laboratoire à l'autre.** Ouvrir un autre laboratoire fermait le laboratoire moléculaire : la scène, le schéma
calculé (des minutes de calcul) et ses chemins étaient perdus au retour. Il est maintenant mis de côté
(`Session.parked_molecule_lab`, `MoleculeLab.park` / `resume`) : plus de pas de dynamique ni de grille pendant
l'absence, tout reste en mémoire, et un schéma en cours de calcul continue dans son fil ; au retour, tout est republié.

**La scène 3D d'une étape.** « Scène 3D » montrait un instant ce que la scène avait avant (l'eau de départ) : le
moteur mettait 1,2 à 3,7 s à publier la première image du chemin, dont 1,9 à 3,1 s de localisation de Pipek–Mezey à
froid (les grains des liaisons ; aldolisation, 24 atomes dans l'eau). La localisation d'un chemin qui vient d'être posé
se fait maintenant dans un fil à part (la première image part en 0,3 s, les grains suivent), et la vue du chemin reste
à l'écran jusqu'à ce que le moteur joue l'étape demandée.

Les schémas tout faits (menu **Exemples** de l'éditeur) : Diels–Alder butadiène + éthylène ; Menshutkin dans
l'acétonitrile ; complexe alcyne–dicobalt ; le diène silylé en quatre étapes ; l'annulation de Robinson jusqu'à la
cétone de Wieland–Miescher (Michael, aldol intramoléculaire, déshydratation, dans le méthanol) ; la réaction de Wittig
(ylure + benzaldéhyde → oxaphosphétane → styrène + Ph₃PO, dans le THF) ; le réarrangement de Claisen aromatique
(éther d'allyle et de phényle → cyclohexadiénone → 2-allylphénol) ; l'aldolisation-crotonisation benzaldéhyde +
acétone dans l'eau ; la même addition catalysée par OH⁻, en trois étapes ; le Diels–Alder cyclopentadiène +
anhydride maléique puis l'hydrolyse en diacide.

Les exemples calculés (chaque étape confirmée : chaque produit tient seul ; ΔE GFN1-xTB, barrière du chemin imposé ;
tous recalculés avec la relaxation à pas bornés et le chemin d'images reliées : les ΔE à 0,01 eV près ; les barrières
montent là où l'ancien chemin sautait d'une branche à l'autre, ce qui les sous-estimait — Michael 2,4 → 2,6 eV, aldol
de la Robinson 3,8 → 4,1, Claisen 3,3 → 3,6, aldol de la benzalacétone 3,5 → 3,9) :

| Exemple | Étapes : ΔE (barrière), eV | Milieu |
|---|---|---|
| Robinson → Wieland–Miescher | Michael −1,45 (2,6) ; aldol −0,69 (4,1) ; déshydratation +0,85 (4,2), H₂O déduite | méthanol |
| Wittig | oxaphosphétane −2,39 (0,17) ; styrène + Ph₃PO −1,08 (0,52) | THF |
| Claisen aromatique | cyclohexadiénone +0,77 (3,6) ; 2-allylphénol −0,93 (0,75) | vide |
| aldolisation-crotonisation | aldol −0,99 (3,9) ; benzalacétone + H₂O +0,68 (4,4) | eau |
| aldolisation catalysée par OH⁻ | énolate + H₂O −1,46 (0,83) ; addition −1,23 (0,11) ; reprotonation + OH⁻ +1,70 (2,36) | eau |
| cyclopentadiène + anhydride maléique | Diels–Alder −2,18 (0,21) ; hydrolyse −0,56 (6,5) | toluène, puis eau |

À 0 K et sans entropie, une déshydratation qui libère une molécule d'eau paraît coûteuse (+0,7 à +0,9 eV) : en vrai,
l'entropie gagnée la rend favorable. Une hydrolyse forcée en un seul chemin concerté (6,5 eV) n'est pas le mécanisme
réel, qui passe par des intermédiaires tétraédriques : le chemin imposé ne dit alors qu'une borne très haute.

Vérifié par le calcul (tests/test_scheme.py rejoue le premier) :

| Schéma | Correspondance | ΔE GFN1 | Barrière du chemin | Verdict | Mesuré |
|---|---|---|---|---|---|
| butadiène + éthylène → cyclohexène | sous-structures | −2,80 eV (calcul direct : −2,80) | 0,35 eV | tient | ΔH ≈ −1,7 eV, Eₐ ≈ 1,2 eV |
| NMe₃ + MeI → NMe₄⁺ + I⁻ | sous-structures | vide : +0,71 eV en paire d'ions (+8,9 ions séparés) ; acétonitrile : +4,65 eV | 2,9 / 2,8 eV | tient | se fait en solution (solvant polaire) |
| diène silylé + N-phénylmaléimide → adduit (Diels–Alder) | sous-structures | −3,15 eV | 0,21 eV | tient | |
| adduit + MeI → ammonium⁺ + I⁻, dans l'acétonitrile | sous-structures | +3,95 eV (ions séparés, solvatés) | 2,72 eV | tient | se fait en solution : l'écart est celui de GFN1 sur l'énergie des ions |
| ammonium⁺ + CsF → 2,3-diméthylènecyclohexane + Me₃SiF + NMe₃ + Cs⁺, dans l'acétonitrile | sous-structures ; sous-produits déduits | −1,36 eV | 1,08 eV | tient | élimination 1,4 induite par le fluorure ; 3,80 eV avant la correction de la relaxation et de D3 (un palier figé) |
| diène + acrylate de méthyle → bicycle (Diels–Alder) | sous-structures | −2,23 eV | 1,07 eV | tient | |
| but-2-yne + Co₂(CO)₈ → Co₂(CO)₆(but-2-yne) + 2 CO | sous-structures ; 2 CO déduits | +2,29 eV (calcul direct : +2,28) | 2,74 eV (chemin concerté imposé) | tient (Co–Co 2,41 Å, Co–C 1,97 Å) | se fait à température ambiante ; le vrai mécanisme perd d'abord un CO |

Les quatre dernières lignes sont le schéma de l'exemple « Diène silylé » (quatre étapes, jusqu'à 62 atomes) : les
atomes de chaque étape sont appariés sans flèche courbe, les sous-produits de l'élimination (Me₃SiF, NMe₃, Cs⁺) sont
déduits des valences, et chaque produit tient seul.

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
rotations une à une, quatre à sept fois plus vite ; refaite au plus deux fois par seconde pendant que les atomes
bougent, chaque barycentre suivant ses atomes entre-temps. Une rotation ne change que deux colonnes des orbitales :
plutôt que de faire tourner les matrices Q^A entières, L et S L tournent ensemble et les Q^A_ii, Q^A_jj, Q^A_ij de
chaque tour s'en déduisent — les mêmes équations (populations égales à 10⁻¹⁴ près, l'ordre des arrondis seul change),
deux à quatre fois plus vite que la version d'origine (caféine : 87 → 32 ms ; acide laurique : 121 → 32 ms).

**Le coût.** Optimisé sans rien changer de ce qui est calculé ni montré : chaque sortie comparée à la version d'avant
— identique au bit près, sauf les paires localisées (10⁻¹⁴) — et chaque image de la scène identique au pixel près.

- Les valeurs des orbitales en des points (profils des paires, densité partagée, grilles ρ, Δρ, ψ) : loin d'un noyau,
  les primitives serrées tombaient sous le plus petit nombre normal de la simple précision (10⁻³⁸), où le processeur
  calcule des dizaines de fois plus lentement ; elles valent 0 d'emblée (`integrals.normal_exp` ; aucune somme n'en
  dépend). Les couches s et p ne passent plus par la matrice sphérique (une simple permutation), et les distances
  d'un atome servent à toutes ses couches. Profils des paires : caféine 210 → 70 ms, acide laurique 350 → 200 ms.
- Le dictionnaire des espèces : la pseudo-inverse de SMACOF ne dépend pas des positions, calculée une fois par
  molécule au lieu de 300 (les 152 croquis, à l'ouverture du laboratoire : 2,2 → 0,8 s).
- La matrice de Mayer et la densité partagée sont gardées le temps d'un résultat (le recensement de chaque pas, puis
  l'instantané, les lisaient deux ou trois fois) ; les parts des recouvrements qui ne dépendent que des exposants
  sont gardées d'un calcul à l'autre.
- La scène : le halo est calculé atome par atome sur les seules colonnes de pixels qu'il couvre (les mêmes sommes,
  dans le même ordre), ses pixels vont à la carte graphique sans conversion (PixelBuffer) ; les arcs des bulles sont
  tracés sans objet par évaluation du champ, et leurs couleurs ne sont plus relues de leur texte à chaque trait
  (−15 à −25 % de temps par image sur le fil de l'interface).

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
- **Expériences guidées.** Ce que le calcul a appris en les réglant, et que l'interface dit :
  - *Diels-Alder.* En choc frontal, GFN1-xTB franchit le col entre 0,6 et 1,0 eV (barrière mesurée ≈ 1,2 eV) ; le
    choc est lancé à 2,5 eV, assez pour passer malgré l'agitation (à 2,0 eV, deux tirages sur six rebondissaient à
    2,1 Å, au col). ΔE GFN1 −2,7 eV, mesuré ≈ −1,7 eV.
  - *SN2.* L'attaque doit venir de l'arrière : posé de côté (un premier montage, faux), l'OH⁻ arrachait un proton
    (CH₂Cl⁻ + H₂O) à toutes les énergies essayées, 0,5 à 3 eV. GFN1 rend cette voie légèrement exothermique
    (−0,26 eV ; mesurée ≈ +0,3 eV). ΔE SN2 : GFN1 −4,1 eV, mesuré ≈ −2,2 eV.
  - *Radicaux.* Sans polarisation de spin, GFN1 surestime les liaisons formées entre radicaux : 2 CH₃· → éthane
    −5,6 eV (mesuré ≈ −3,9), OH· + H· → H₂O −8,1 eV (mesuré ≈ −5,1). Le transfert de proton, entre espèces à couches
    fermées, est juste : −1,9 eV (mesuré ≈ −1,7).
  - *Troisième corps.* Seuls, H et OH se rejoignent puis se séparent : trois atomes n'ont nulle part où mettre
    l'énergie de la liaison (en dynamique libre, l'H repart à 4 Å et revient plusieurs fois). Le thermostat, dès le
    contact, joue le gaz qui l'emporte (H + OH + M → H₂O + M), comme il le fait pour l'éthane.
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
  trans, énantiomères) portent le même nom, et une espèce absente des 152 n'a que sa formule. « Tient » dit que
  GFN1-xTB trouve un minimum pour la molécule seule, dans le vide, à 0 K : ni qu'elle existe, ni qu'elle dure à
  température ambiante ou au contact d'autres (un radical « tient », puis réagit au premier choc) ; sa cohésion est
  celle de GFN1 (1,3 à 2,2 fois trop forte).
- **La réaction du dessin.** Quelques centaines de femtosecondes à haute température, à la densité d'un liquide :
  une issue qui n'apparaît pas peut exister, et la fréquence d'une issue (« 1/3 ») n'est ni une vitesse ni un
  rendement réels — elle dit seulement ce que trois essais ont rencontré. Les ΔE héritent des défauts de GFN1 sans
  spin (radicaux trop liés : OH· + H· −8,1 eV pour −5,1) ; O₂ n'a pas de barrière de spin. Le nanoréacteur original
  comprime aussi le gaz par à-coups pour hâter les rencontres ; ici la sphère reste fixe.
- **Le schéma réactionnel.** Un chemin imposé n'est pas un état de transition cherché : sa plus haute énergie estime
  la barrière le long de ce chemin, et GFN1-xTB sous-estime souvent les barrières (Diels–Alder : 0,35 eV pour
  ≈ 1,2 mesuré) ; les ΔE ont les défauts de GFN1 (Diels–Alder −2,80 eV pour ≈ −1,7). Tout se calcule à 0 K, en phase
  gazeuse ou dans le solvant implicite écrit sous la flèche (ALPB) ; sans solvant, les ions isolés sont très défavorisés (NMe₃ + MeI → NMe₄⁺ + I⁻ : +0,7 eV en paire
  d'ions, +8,9 eV pour les ions séparés, quand la réaction se fait en solution ; les ions formés sont donc comptés en
  paire), et les conditions écrites sur une flèche ne sont pas
  simulées. Pour un métal de transition, GFN1 donne de bonnes géométries (Co₂(CO)₆(but-2-yne) : Co–Co 2,41 Å, Co–C
  1,96 Å ; mesurés ≈ 2,46 et 1,95) mais de mauvaises énergies métal–ligand (Co₂(CO)₈ + but-2-yne → complexe + 2 CO :
  +2,3 eV, alors que la réaction se fait à température ambiante). Pas de stéréochimie dessinée : le conformère le
  plus stable est choisi. Au plus 90 atomes par étape ; une étape de 60 atomes prend quelques minutes.
- **Taille.** Au plus 40 atomes : le calcul est en numpy (≈ 10 ms par pas pour le méthanol, 30 ms pour neuf atomes).

## Fichiers

| Fichier | Rôle |
|---|---|
| `xtb.py` | GFN1-xTB : base, recouvrements, H⁰, électrostatique, répulsion, D3, liaison halogène, cycle auto-cohérent, gradients |
| `integrals.py` | Recouvrements de gaussiennes et leurs dérivées (Obara–Saika), valeurs des orbitales sur une grille |
| `analysis.py` | Indices de Mayer, partage des liaisons, espèces (nommées), dipôle, modes de vibration |
| `experiments.py` | Les cinq expériences guidées : réactifs relaxés, mise face à face, choc, protocole (`MOLECULE_EXPERIMENT`) |
| `dictionary.py` | Le dictionnaire : SMILES, empreinte des graphes, noms français et anglais, départs de dessin 2D |
| `assembly.py` | L'assembleur : le dessin reçu (`MOLECULE_ASSESS`, `MOLECULE_BUILD`), sa pose en 3D, les verdicts |
| `reaction.py` | La réaction du dessin (`MOLECULE_REACT`) : réactifs relaxés et rapprochés, essais du nanoréacteur, produits relaxés seuls, issues regroupées |
| `solvation.py` | Le solvant implicite ALPB (`Calculator(…, solvent=…)`) : rayons de Born, noyau P16, correction ALPB, CM5, surface de Lebedev, liaisons hydrogène, gradients ; `data/gfn1-alpb.json` |
| `scheme.py` | Le schéma réactionnel (`MOLECULE_SCHEME`, `MOLECULE_SMILES`) : lecture du dessin (H implicites, abréviations, réactifs écrits), étapes, flèches courbes, correspondance des atomes, sous-produits, chemins calculés dans les deux sens, verdicts, exemples |
| `ReactionPanel.java` | La carte 07 Réaction de l'assembleur : température, essais en cours, issues dessinées (client) |
| `AtomCard.java`, `MoleculeCard.java` | La carte du survol d'un atome et la fiche d'une molécule au clic droit (client) |
| `density.py` | ρ, Δρ et ψ sur une grille, et leur codage pour le client |
| `lab.py` | La session : établi, relaxation, dynamique, traction, étincelle, réacteur, vibrations, dans un fil à part |
| `structures.py` | Les molécules de départ, les mélanges du réacteur, les noms des espèces |
| `validation.py` | Le tableau calculé ↔ mesuré, et le texte de la méthode |
| `frame.py` | Le laboratoire dans la frame (`MoleculeLabState`) |
