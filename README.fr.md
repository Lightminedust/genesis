# Genesis — Simulateur atomique

[English](README.md) · **Français**

Un atome qui vit selon les règles de la physique. Le moteur fait tourner le
temps ; à chaque instant il applique les règles, et ce qui arrive en découle :
un électron excité redescend en émettant un photon, un photon est absorbé ou
traverse, un noyau instable se désintègre. Rien n'est scénarisé : chaque
événement arrive au moment tiré par la loi physique, avec le calcul qui le
justifie.

Licence d'utilisation : [EULA.txt](EULA.txt).

```
JavaFX (sim/)  --- gRPC bidirectionnel --->  Python (logic/)  ---> MongoDB
  dessine les événements   genesis.proto v28   horloge + règles     table périodique
                                               scipy, radioactivedecay, mendeleev
```

## Lancer

```bash
# 1. Le moteur et la base (--build est indispensable après une mise à jour)
docker compose down
docker compose up --build

# 2. Le client, dans un second terminal
cd sim
gradle run
```

Le client vise `localhost:50051` ; pour un autre moteur :
`gradle run -PgenesisHost=192.168.1.20 -PgenesisPort=50052`.
Un conteneur resté sur une ancienne image est détecté (`protocol_version`).

L'application démarre en **anglais** par défaut. La barre supérieure permet de
choisir **Français** ou **English** dans tous les laboratoires ; le choix est
mémorisé. Pour démarrer directement en français : `gradle run -PgenesisLang=fr` (une langue imposée ainsi n'est pas enregistrée).
Le moteur écrit toujours en français ; le client traduit à l'affichage tout ce
qu'il montre, y compris les explications et les messages du moteur (tables de
`sim/app/src/main/resources/i18n/` : phrases exactes, gabarits où nombres,
nucléides et noms d'éléments sont des emplacements `{0}`, et noms des 118 éléments).

**Sans Docker** (Windows, connexion lente) : le moteur tourne aussi
directement, données en mémoire. Tout marche sauf l'échelle Molécule
(PySCF n'existe que sous Linux).

```bash
cd logic
python -m venv venv && venv\Scripts\pip install -r requirements.txt   # une seule fois
venv\Scripts\python -m grpc_tools.protoc -I../core --python_out=. --grpc_python_out=. ../core/genesis.proto
venv\Scripts\python engine.py
```

Le build Docker garde les paquets téléchargés dans un cache : seul ce qui
change est retéléchargé.

## Paquet Windows

Sur Windows, avec un JDK 21 (`jpackage`), `gradle` et l'environnement
`logic/venv` déjà équipé des dépendances du moteur, installer PyInstaller dans
cet environnement puis construire le paquet :

```powershell
.\logic\venv\Scripts\python.exe -m pip install "pyinstaller>=6,<7"
powershell -NoProfile -ExecutionPolicy Bypass -File .\packaging\build-windows.ps1 -Version 0.4.0
```

Le script teste le moteur autonome, puis produit `dist/windows/Genesis`
(application portable), `Genesis-0.4.0-portable.zip` et un installateur `.exe`.
L'installateur place Genesis dans le profil utilisateur et ajoute les raccourcis
au menu Démarrer et au bureau. Pour une nouvelle version, augmenter `-Version` ;
le même identifiant d'installation permet la mise à niveau.

Le paquet embarque le client JavaFX, son runtime Java et le moteur Python.
Au premier démarrage, un écran de chargement reste visible jusqu'à ce que le
moteur Python ait ouvert sa connexion locale. En cas d'échec, le journal est
dans `%LOCALAPPDATA%\Genesis\logs\engine.log`.
L'utilisateur n'a besoin ni de Gradle, ni de Python, ni de Docker ou MongoDB.
Le moteur écoute uniquement sur `127.0.0.1` et s'arrête avec Genesis. Les
journaux et le cache sont écrits dans `%LOCALAPPDATA%\Genesis\` ; l'application
portable y écrit aussi. L'échelle Molécule reste masquée dans cette version.
Le paquet n'est pas signé ; une signature de code et les notices des composants
tiers sont à préparer avant diffusion publique.

**Mises à jour.** Au démarrage, l'application empaquetée demande à GitHub, en
arrière-plan, la dernière version de
[genesis-releases](https://github.com/Lightminedust/genesis-releases) et la
compare à la sienne (donnée par `jpackage`). S'il en existe une plus récente,
une petite carte apparaît en bas à droite une fois le chargement fini :
**Installer maintenant** (Windows : l'installateur est téléchargé, son SHA-256
est vérifié dans `CHECKSUMS-SHA256.txt`, puis il est lancé et Genesis se ferme),
**Voir la version**, **Plus tard** ou **Ignorer cette version**. Sous Linux et
macOS, le bouton ouvre la page de téléchargement. Hors ligne, ou lancé depuis
les sources, rien ne s'affiche (`-Dgenesis.version=0.1.0` simule une ancienne
version pour tester). Chaque version publiée doit donc fournir son
`CHECKSUMS-SHA256.txt`.

**Soutien.** Pendant le démarrage du moteur, l'écran de chargement montre un
cadre discret « Soutenir Genesis » : GitHub, le site de Genesis, Gemmie
(gemmi-e.com/contact), r/gemmie et nellawassi@gmail.com.

## Paquet Linux x86-64

Sur Ubuntu 22.04 (ou un système de construction compatible), installer JDK 21,
`gradle`, `fakeroot`, les bibliothèques JavaFX/GTK et un environnement Python
avec `logic/requirements.txt`, `grpcio-tools` et PyInstaller. Puis lancer :

```bash
bash packaging/build-linux.sh 0.4.0
```

Le script gèle le même moteur Python, vérifie son service gRPC et la table des
118 éléments, construit le client JavaFX, puis produit dans `dist/linux/` un
installateur `Genesis-0.4.0-linux-x64.deb` et une archive portable
`Genesis-0.4.0-linux-x64.tar.gz`. Un workflow GitHub Actions
`.github/workflows/linux-package.yml` réalise cette construction sur Ubuntu.

L'archive s'extrait puis se lance avec `Genesis/bin/Genesis` ; le `.deb` ajoute
un lanceur au menu des applications. Java et Python sont inclus. Le moteur
écoute seulement sur `127.0.0.1` et s'arrête avec l'application. Sous Linux,
son journal est dans `${XDG_STATE_HOME:-~/.local/state}/genesis/logs/engine.log`
et son cache dans `${XDG_CACHE_HOME:-~/.cache}/genesis/cache`.

## Paquet macOS (Apple Silicon)

Sur un Mac avec un JDK 21 et un environnement Python équipé de
`logic/requirements.txt` et de PyInstaller, lancer
`bash packaging/build-macos.sh 0.4.0` ; sans Mac, le workflow GitHub Actions
`.github/workflows/macos-package.yml` le construit sur une machine Apple
Silicon. Il produit `Genesis-0.4.0-macos-arm64.dmg` et
`Genesis-0.4.0-macos-arm64.zip` dans `dist/macos/`. jpackage refuse une version
macOS qui commence par 0 : le paquet porte `1.4.0` en interne, et le vrai
numéro passe par `-Dgenesis.version`, que la vérification des mises à jour lit
en premier. L'application est signée localement (ad hoc), pas notariée : au
premier lancement, clic droit puis **Ouvrir**. Le journal du moteur est dans
`~/Library/Logs/Genesis/engine.log`.

## Laboratoire subatomique

> Masqué pour l'instant (incomplet) : il n'apparaît plus dans le menu
> **Laboratoires** et la touche **S** ne l'ouvre plus. Son code est conservé
> (`SUBATOMIC_SCREEN` dans `App.java`) ; la description ci-dessous vaut quand il est réactivé.

Depuis l'accueil, ouvrir **◈ Laboratoire subatomique** (touche **S**). Ce mode
est indépendant de la construction de l'atome. On peut créer jusqu'à 12
nucléons, ajouter 0 à 2 électrons, régler l'énergie cinétique initiale puis
lire ou parcourir les 520 échantillons calculés par Python. La vue nucléaire
montre les positions 3D, les vitesses et la séparation ; les courbes affichent
les énergies cinétique, forte, coulombienne et totale, la force radiale
signée pour deux corps et l'erreur de conservation. Un second graphe montre
le potentiel effectif en fonction de la séparation ; son axe vertical
compressé garde visibles à la fois le cœur répulsif et le puits attractif.
La molette zoome et le glissement tourne la caméra.

Le calque **Couplages calculés** relie les paires de nucléons : cyan pour une
force effective attractive, ambre pour une force répulsive ; l'épaisseur suit
`|F(r)|` transmis par Python à chaque échantillon. Ces segments montrent une
force centrale entre deux particules, pas des lignes de champ physiques. La
jauge **Seuil de séparation** indique `−E_rel` pour deux corps : une marge
positive signifie qu'ils sont liés dans ce modèle conservatif. Pour plusieurs
corps, elle montre la marge minimale nécessaire pour arracher un nucléon à
l'amas figé à cet instant : ce diagnostic local ne garantit pas la stabilité
future. Le tritium de démonstration démarre près d'un minimum obtenu en
minimisant le même potentiel, puis oscille sans amortissement.

Dans la vue nucléaire, un encart affiche la densité électronique 1s à une
autre échelle ; l'échelle électronique complète montre aussi un encart grossi
du noyau. Ses pixels évaluent `exp(−2r/a)` aux coordonnées physiques en pm, avec
le rayon `a` calculé par Python. **Saisir un nucléon, le tirer et le relâcher**
envoie une impulsion vectorielle au moteur : celui-ci repart de l'échantillon
choisi et recalcule 520 nouvelles images et leur bilan d'énergie. Aucun
déplacement de particule n'est décidé par le Canvas.
Changer seulement le nombre d'électrons modifie l'occupation de l'orbitale
sans remettre à zéro la trajectoire nucléaire en cours.

Le premier cas validé est **p + n**. Un potentiel central effectif
`V(r) = Gᵣ exp(−r/λᵣ)/r − Gₐ exp(−r/λₐ)/r` possède un cœur répulsif et
un puits attractif. La force est exactement `−dV/dr`, y compris la dérivée
des exponentielles ; Coulomb s'ajoute entre protons. Un intégrateur
**velocity-Verlet** transporte positions et vitesses. Le modèle place une
paire déjà liée à énergie négative : elle oscille sans s'amortir, car aucune
émission qui emporterait l'énergie n'est simulée. Le canal des nucléons
identiques reçoit une pression cinétique effective ; le modèle ne déduit
pas les niveaux, spins, réactions ni sections efficaces nucléaires.

Les électrons ont une échelle environ 100 000 fois plus grande. Le bouton
**Échelle électronique** montre une coupe de densité orbitale 1s
`|ψ|² ∝ exp(−2r/a)` et son énergie de liaison : hydrogénoïdes pour un
électron, estimation de Slater pour deux. Il n'y a pas de trajectoire
ponctuelle d'électron ni de répulsion de Pauli entre électron et proton :
l'exclusion concerne des fermions identiques. Ce laboratoire est un
modèle *few-body* explicite, pas un calcul QCD ni un simulateur complet des
réactions subatomiques. Les masses et l'échelle de Bohr viennent de
[CODATA/NIST](https://physics.nist.gov/cuu/Constants/index.html) ; le
[deutéron est un état lié quantique](https://www.energy.gov/science/np/articles/quantum-computing-atomic-nucleus)
et sa liaison mesurée ne doit pas être confondue avec l'énergie de ce puits
classique. Le [principe de Pauli entre électrons](https://www.nist.gov/pml/atomic-spectroscopy-compendium-basic-ideas-notation-data-and-formulas/atomic-spectroscopy-10)
ne remplace pas la mécanique quantique de l'orbitale.

## Le monde subatomique

**◉ Le monde subatomique** (touche **K**) réunit trois ateliers ; la collision est une figure 3D de toute la zone (boîte ouverte graduée en fm, traces ramifiées avec leurs points du temps et leurs ombres, lois angulaires calculées en lobes, loupe sur les hadrons), dans le style de la chambre de fusion ; ailleurs, style documentaire (bulles de verre, quarks
lumineux, gluons en ressorts dorés) :

* **Collision** — un projectile frappe un proton ou un neutron : électron, positron, muons (un photon échangé, 0,3 à 10 GeV, 5° à 170°), neutrino et antineutrino muoniques (un W, qui change la saveur d'un quark), proton ou pions (la force forte : diffraction, formation du Δ, échange de couleur et cordes). Le moteur calcule les cas possibles
  et leur probabilité : diffusion élastique (Rosenbluth, facteurs de forme de Kelly), excitation du Δ(1232) (Breit–Wigner),
  résonances supérieures (dualité) et inélastique profond (F₂ par DGLAP). Tout est en 3D : le sac du nucléon (rayon tiré
  du rayon de charge, sac du MIT), le puits de Coulomb en brume, et l'espace courbé par l'énergie de chacun (lentilles
  d'Einstein, θ_E² ∝ E, amplifiées d'un facteur affiché). Puis il tire un événement et le montre : le photon virtuel en arc
  électrique (coudes espacés de λ = ħc/Q, couleur et éclat selon ν) qui emporte l'énergie de l'électron, le recul de l'électron, la désintégration du Δ ou la corde qui se rompt. Chaque étape a sa formule et ses
  nombres, et énergie et impulsion sont conservées exactement.
* **Atelier de hadrons** — le tableau du Modèle Standard (fiches calculées : largeurs du W et du Z, durées de vie du muon, du
  top…) ; on y prend des quarks, on choisit leurs couleurs, et le moteur juge : couleur neutre, Pauli, charge, masse
  (quarks constituants + hyperfin), destin (stable, fort, électromagnétique, faible par le W, top qui meurt avant de former
  un hadron), avec une désintégration montrée.
* **Mer de partons** — le nucléon à une résolution Q² donnée : gluons et paires qui se créent et s'annihilent.

Lois, sources et limites : [le monde subatomique](docs/SUBATOMIC_MODEL.md) et [le modèle du nucléon](docs/NUCLEON_MODEL.md).

## Le laboratoire moléculaire

**⌬ Laboratoire moléculaire** (menu Laboratoires) pose des atomes sur un établi et calcule leurs électrons. Personne
ne décide que deux atomes sont liés, ni sous quel angle : pour chaque position des noyaux, la méthode quantique de
liaisons fortes GFN1-xTB donne l'énergie et la force exacte sur chaque noyau, et la géométrie, les liaisons, les
vibrations et les réactions en découlent.

* **Des liaisons dessinées par leurs paires d'électrons** — les orbitales occupées sont localisées (Pipek–Mezey) et
  chaque liaison est montrée par ce qui la tient : une paire sur deux atomes est un trait (covalente ou polaire :
  eau, benzène, SF₆, Au₂) ; une paire étalée sur trois atomes ou plus est une liaison multicentre, dessinée en étoile
  de Lipscomb depuis le barycentre de la paire (agrégats d'or : Au₆ a trois telles paires, une par triangle de
  sommet, et aucune sur un seul couple d'atomes ; les ponts B–H–B du diborane) ; deux ions sans paire partagée (NaCl)
  ont des pointillés d'attraction. Le triangle plan D3h de Au₆ est dans les molécules toutes prêtes, et GFN1-xTB le
  place sous la pyramide (+0,60 eV) et l'octaèdre (+1,10 eV).
* **Le halo de polarisation** — le nuage des électrons de valence de chaque molécule vu en transparence : en chaque
  pixel, les électrons par Å² que croise le regard, calculés à chaque image en pleine résolution à partir du nuage de
  chaque atome libre (GFN1, projeté par transformée d'Abel) à l'échelle des électrons qu'il garde (charge de
  Mulliken) ; il reproduit la densité calculée projetée à 95–99 %. Son bord est le contour 0,3 e/Å² ; sa couleur, la
  charge des atomes à qui appartient le point : magenta δ−, cyan δ+, avec les signes δ+ / δ− au-delà de son bord.
  Fourni autour des atomes qui attirent les électrons (O, Cl), mince autour de ceux qui les cèdent (H). En option, la
  carte de potentiel conventionnelle : le potentiel exact des noyaux et du nuage (convolution de Fourier) sur la
  surface ρ = 0,002 u.a.
* **Une image épurée et calculée** — les atomes sont des bulles de verre (0,45 × rayon covalent de Pyykkö) au bord
  de Fresnel lumineux, qui vire au magenta quand l'atome a gagné des électrons (δ−) et au cyan quand il en a perdu
  (δ+), avec un noyau incandescent et quelques arcs fins qui partent du noyau, sont captés là où les liaisons percent
  la bulle et se penchent dans le sens où le nuage de l'atome est déformé (son dipôle atomique calculé). Une liaison
  covalente est un faisceau unique, tendu comme une corde, dont l'éclat suit l'indice de Mayer et la densité que les
  deux atomes partagent encore entre leurs noyaux (rompue, une liaison garde un indice de 1 dans ce modèle à couches
  fermées — O–O à 3 Å : 1,06 — mais ne partage plus rien : son faisceau s'éteint) ; sa part π, deux
  lobes de verre de part et d'autre de la liaison, le long de la direction calculée de la densité π (éthylène : la
  normale à son plan ; N₂ : deux paires perpendiculaires) ; sa couleur dit la force interne le long de la liaison
  (bleu nulle, blanc traction, orange compression) ; ses grains sont tirés de la densité de sa paire localisée le long
  de l'axe (étalés sur C–H, plus denses côté O dans O–H). Une attraction sans paire partagée se dessine en pointillés
  (liaison hydrogène, van der Waals, ions), qui glissent vers le milieu ; une répulsion, en points orange qui partent
  de là où les deux nuages se touchent (surfaces des atomes libres à 0,002 e/bohr³) et s'écartent vers les deux
  atomes, aussi vite et vifs que la force calculée de la paire. Le bloom est calculé par la carte graphique. Les lignes du champ
  électrique sont en option.
* **Trois vues, trois langues** — Δρ est une carte de densité de déformation en courbes de niveau (vue en
  transparence, comme le halo : trait plein où la molécule a gagné des électrons, tirets où elle en a perdu, une
  courbe par doublement de 0,025 à 1,6 e/Å²) : le benzène gagne sur toutes ses liaisons et perd sur ses noyaux de
  carbone, comme dans les manuels. ψ est une orbitale moléculaire en lobes de verre teinté : la surface qui enferme
  85 % de |ψ|², bleue où ψ > 0 et rouge où ψ < 0 (la phase de l'onde, pas une charge), un nœud entre les deux, comme
  dans les logiciels de chimie : des lobes pleins et lisses, un squelette en boules et bâtonnets en 3D dont les
  liaisons ont un, deux ou trois bâtonnets selon l'indice de Mayer ; un maillage fermé par tétraèdres marchants sur une
  grille fine (80 points par côté, calculée par séparation des gaussiennes), rendu par la carte graphique — net même
  en tournant. La caméra regarde le long de l'axe où le nuage est le plus mince et s'incline de 40° quand les deux
  phases tomberaient sur les mêmes lignes de visée (une π du benzène vue de face) ; la ligne de la vue dit ce dont
  l'orbitale est faite (HOMO de l'eau : O 2p 100 %). Chaque vue ne dessine que ce qu'elle dit.
* **Des lectures qui se voient** — le bilan d'énergie : la cohésion (l'énergie qu'il faut pour séparer la molécule
  en atomes), puis chaque terme de GFN1-xTB moins sa valeur dans les atomes isolés, ce qui lie contre ce qui coûte,
  dont la somme est exactement l'énergie de liaison (eau : orbitales qui se recouvrent −19,2 eV, charges +1,4,
  répulsion des noyaux +1,0 → −16,7 eV), et l'agitation des noyaux rapportée à la cohésion. Les niveaux des orbitales
  en cible : le rayon suit l'énergie, les cercles pleins portent leurs électrons, la HOMO orangée, l'écart HOMO–LUMO
  en anneau doré, la LUMO en tirets violets, le vide au bord ; le survol lit un cercle, un clic montre son orbitale.
  L'atome choisi en carte des forces, en 2D : par défaut la seule force nette et, pour chaque voisin, un trait coloré
  et épaissi selon ce que la paire se fait, les voisins rangés en liés / non liés avec leur raison calculée (la nature
  d'une liaison selon sa part π ; pour deux atomes non liés, la part dominante de leur force : gêne stérique d'un
  recouvrement antiliant, charges de même signe ou opposées, London, cœurs). Les onglets détaillés donnent une flèche
  par paire (le vecteur entier, sa part de travers comprise — elle tient les angles), dont la somme est exactement la
  force nette, ou une flèche par terme (électrons, noyaux, London, main). Une vignette « Schéma 2D » montre la
  molécule en formule de manuel (CH₃, OH écrits, ou tous les hydrogènes ; liaisons simples, doubles, triples et
  conjuguées d'après l'indice de Mayer), qui tourne avec la vue 3D et lui répond dans les deux sens,, avec
  en temps réel les charges, les longueurs et la force de l'atome choisi, une ligne de mesures et une consigne qui
  suit le geste en cours.
* **Qui fait quoi à qui, sans flèches** — les forces sont décomposées exactement par paires d'atomes (action =
  −réaction) ; au survol d'un atome : « H3 l'attire de 1,00 eV/Å (électrons +4,60 · noyaux −3,60) » ; un noyau
  comprimé tremble.
* **Tout le tableau jusqu'au radon** — GFN1-xTB est paramétré pour Z = 1 à 86 (les 86 vérifiés : chacun se relaxe
  avec un hydrogène attaché) ; dans le tableau du laboratoire, les éléments au-delà (uranium…) sont pâlis et disent
  pourquoi.
* **Les isotopes** — un atome choisi prend n'importe lequel de ses isotopes stables ou vivant au moins un an (¹H ²H
  ³H, ¹³C ¹⁴C, ¹⁸O… ; masses de l'AME), lui seul ou tous les atomes de l'élément. Seule la masse change
  (Born–Oppenheimer) : même géométrie, autres dynamique et vibrations — D₂O vibre à 0,72–0,73 × H₂O (mesuré
  0,73–0,74), HDO découple ses élongations O–D et O–H.
* **Des molécules du vivant** — glucose, acide laurique (un acide gras, un lipide), glycérol, alanine et caféine,
  depuis les conformères 3D de PubChem, relaxés par GFN1-xTB.
* **L'établi** — choisir un élément et cliquer sur un atome pour l'y attacher : C + 4 H se relaxe tout seul en
  tétraèdre (109,47°), l'eau sort coudée. Saisir un atome et tirer : le reste de la molécule est tenu par son centre
  de masse, la liaison s'étire, l'énergie monte, et elle casse.
* **Dynamique et réacteur** — relaxation (FIRE), dynamique moléculaire avec thermostat (CSVR) ou libre (énergie
  conservée, pas de 0,25 fs) ; des molécules dans une sphère (2 H₂ + O₂, H₂ + Cl₂, CH₄ + 2 O₂…) qui réagissent quand
  on chauffe ou qu'une étincelle dépose de l'énergie cinétique ; les espèces présentes et chaque réaction sont lues
  dans les indices de liaison de Mayer et la densité partagée. La dynamique chaude ne s'arrête plus : quand une
  liaison casse, le cycle auto-cohérent passe du mélange de Broyden à un pas de Newton amorti sur la réponse calculée
  des charges (O₄ à 4 000 K : aucun arrêt, chaque pas convergé à 300 K), et une contrainte stricte tient chaque atome
  à −2,5 e au plus bas, là où le terme du troisième ordre de GFN1, non borné, y entasserait des électrons (Pm₄
  dissocié : −9 e) ; la ligne d'état dit quand elle agit.
* **Les vibrations** — la hessienne tirée des forces analytiques donne les modes propres (cm⁻¹) ; en exciter un lui
  donne n quanta hν, et la dynamique (anharmonique) prend le relais.

GFN1-xTB est réécrit en numpy et vérifié contre l'implémentation de référence dxtb à 3·10⁻⁷ Eh près. Il donne juste
les géométries et les vibrations (eau : 0,958 Å, fréquences 4 à 13 % trop basses) ; il surestime les énergies
d'atomisation et certaines énergies de réaction (2 H₂ + O₂ → 2 H₂O : −9,4 eV calculés, ≈ −5,5 eV mesurés) — le
laboratoire le dit, avec un tableau calculé ↔ mesuré. Le modèle, ses vérifications et ses limites :
[le laboratoire moléculaire](docs/MOLECULE_MODEL.md).

## La réaction en chaîne

> Masqué pour l'instant : il n'apparaît plus dans le menu **Laboratoires**. Son code et son moteur sont conservés
> (`CHAIN_SCREEN` dans `App.java`, `logic/chainlab/`) ; la description ci-dessous vaut quand il est réactivé.

**☢ Réaction en chaîne · masse critique** (menu Laboratoires) pose une sphère de métal fissile au milieu de l'écran,
avec ou sans réflecteur, et calcule ce que font ses neutrons. Chaque nombre sort d'un calcul sur des données évaluées :

* **Une scène, pas un formulaire** — la matière est un volume de noyaux (un point pour 10²³ noyaux réels). Un clic
  dans la sphère y fait naître un neutron ; glisser son bord la comprime à masse constante (Maj : ajoute de la
  matière), et l'on voit les noyaux se resserrer et l'anneau du rayon critique se rapprocher ; glisser un jeton de
  béryllium, de graphite ou d'uranium sur la sphère pose un réflecteur ; un curseur ralentit le temps jusqu'à la
  nanoseconde. La couleur dit l'état : aura bleu froid en sous-critique, ambre au critique, rouge qui palpite
  au-delà. Matériaux : U-235, Pu-239, U-238, Th-232, fer 56 et plomb 208 (qui ne fissionnent jamais : les neutrons
  rebondissent, sont capturés dans un éclair γ violet, ou fuient), et huit autres.
* **Transport des neutrons** — probabilités de première collision dans des coquilles sphériques, 44 groupes d'énergie
  construits depuis ENDF/B-VIII.0 (autoprotection de Bondarenko, correction de transport, flux adjoint). Le tableau de
  bord donne k_eff, k∞, le destin des neutrons (fission, capture, fuite), le rayon et la masse critiques, k_eff(R), la
  réactivité en dollars, la période (équation inhour, neutrons retardés), Λ, β, ν̄. Godiva, Jezebel et Flattop sortent
  critiques à un demi-pour-cent près ; les masses critiques nues sont celles publiées (U-235 46 kg, Pu-239 10 kg,
  U-233 15 kg). U-238 et Th-232 ne divergent jamais.
* **Le tir** — une cascade tirée neutron par neutron dans la vraie géométrie (ν selon la loi de Terrell autour du ν̄
  évalué) : chaque choc tombe sur un noyau du volume — une diffusion jette une étincelle, une capture lâche une onde γ
  violette, une fission l'éclate en deux fragments dans un éclair. Les générations s'alignent en bas (1 → 4 → 6 →
  10…, ou un ✕). Si la chaîne tirée tient, l'excursion calculée prend le relais : une tempête d'étincelles répartie
  selon le flux calculé, le métal chauffe, rougeoie (couleur du corps noir à la température calculée), se dilate,
  bout, se disperse. Un neutron seul dans une sphère à peine sur-critique s'éteint presque toujours : la scène dit
  combien il est rare qu'il allume une chaîne sans fin (2 % à k = 1,02). Les chiffres (k_eff, masse critique, N(t),
  énergie, TNT, Δm = E/c²) sont derrière « ⓘ Les chiffres ».
* **Bac à sable — la quête du rendement** — n'importe quel noyau pesé : la courbe d'Aston, la meilleure coupure de
  fission et la fusion avec lui-même, une jauge Q du rouge (endothermique) à l'or (exothermique), le bilan des masses
  avant / après, et un tableau U-235 · Fe-56 · Fe-60 · D + T. Fission et réaction en chaîne (neutrons, σ_f, k_eff) et
  fusion (plasma, Coulomb, Lawson) restent deux mondes séparés.

Le modèle, ses données et sa validation : [la réaction en chaîne](docs/CHAIN_MODEL.md).

## Ce qu'on peut faire

Au lancement, le simulateur charge un atome d'uranium 238 et ouvre l'onglet Orbitale (la forme du nuage de valence, avant le spectre). L'écran de l'atome tient en trois bandes :

- **La barre du haut** :
  - l'élément (clic : la table périodique, qui se referme dès qu'un élément est choisi, ou avec Échap). Un élément choisi arrive avec son **isotope naturel**, le plus abondant sur Terre (Fe-56, Cu-63, U-238) ; sans abondance tabulée, l'isotope qui vit le plus longtemps (Tc-98, Pu-244) ;
  - trois compteurs − p⁺ + · − n + · − e⁻ + ;
  - la lumière : la longueur d'onde (clic : un petit réglage, avec la flamme) et « Tirer un photon » ;
  - **☢ Tirer sur le noyau** : un **neutron lent** (0,0253 eV), dont l'issue est tirée au prorata des sections efficaces mesurées (diffusion, capture (n,γ), fission de U-233/U-235/Pu-239, (n,α) de Li-6 et B-10, (n,p) de He-3 et N-14), avec Q calculé sur les masses mesurées ; ou une **particule α de 5 MeV**, qui ouvre la chambre de collision avec He-4 contre le noyau affiché. Sans section efficace mesurée (Tc-98…), Genesis le dit et n'invente pas l'issue ;
  - à droite, le menu **Laboratoires** (fusion · fission · transmutation, laboratoire moléculaire, monde subatomique), masqué tant qu'on ne l'ouvre pas. Dès qu'on en choisit un, un **écran d'attente** (`LabLoader`) dit ce que le moteur prépare et le temps écoulé. Il s'efface quand la frame confirme le laboratoire, et dit pourquoi si le moteur ne répond pas (obsolète, injoignable). Le retour à l'atome a la même attente ;
  - un point de couleur qui donne l'état de la connexion au survol.
- **La scène**, avec sa barre de vue :
  - l'échelle (Atome · Noyau · Nucléon · Gouffre) ;
  - ce que fait le glisser (⟳ Tourner · ✥ Déplacer ; clic droit ou Maj déplace toujours) ;
  - ⌖ Recentrer, et des vues toutes faites (face, dessus, côté, trois-quarts) ;
  - la rotation propre, le zoom ;
  - **Bohr** (touche `O`) : le modèle de Bohr (1913), l'image que tout le monde connaît — des cercles concentriques dans un seul plan (ils ne se croisent jamais), un par couche, les électrons qui tournent, et au centre le vrai noyau (la goutte de nucléons placés par le moteur), agrandi. Le nombre d'électrons par couche vient du moteur ; les rayons ne sont pas à l'échelle et la scène rappelle que c'est une image historique (en réalité, l'électron n'a pas de trajectoire). Une couche pleine de plus de 8 électrons est un anneau lumineux, ses électrons apparaissent au survol ; au-delà du xénon (ou avec **Valence**), le cœur se condense en une sphère étiquetée par son gaz noble ([Kr], [Rn]…). Les comptes sont dans une légende sous l'atome (`[Rn] 86 · O 14 · P 10 · Q 8`), pas dans la scène. Rien n'apparaît d'un coup : un électron capturé arrive en volant, un électron excité saute de cercle en cercle, un électron perdu s'envole, une désintégration secoue le noyau ;
  - **Valence** (touche `V`) : seules les sous-couches ouvertes sont dessinées. Pleines, elles sont sphériques (théorème d'Unsöld) et, empilées dans un atome lourd, elles cachent la forme de la valence ; sans elles, le 4d¹ de l'yttrium montre en 3D les lobes et l'anneau de d_z² ;
  - un **double-clic** centre la vue sur le point choisi et s'en approche.

  Le panneau des couches se replie en une pastille, et une pastille ⓘ donne la légende et les **limites du modèle** au survol.
- **La timeline**, en bas : ▶/⏸, « Événement suivant », « Pas à pas », et la **vitesse du temps** en clair (« 1 s réelle = 1 milliard d'années simulées »). Dessous, ce que devient l'atome, comme une ligne de métro :
  - **Passé** : les noyaux déjà vécus, grisés, avec leur durée réelle.
  - **Noyau actuel** : son anneau donne la probabilité qu'il se soit déjà désintégré, P = 1 − e^(−t/τ).
  - **Avenir** : les stations jusqu'au noyau stable, avec leur voie (α, β⁻…), leur demi-vie et le temps moyen pour y arriver (U-238 → Th-234 → … → Pb-206).
  - **Interactions** : un clic sur une station avance le temps jusqu'à elle, événement par événement (les désintégrations restent tirées au hasard). Le survol d'un rail explique la voie.
  - **En dessous** : la prochaine étape, et ce qu'elle représente en temps réel à la vitesse choisie.

La chaîne vient du moteur (`Session.decay_chain`, voie dominante à chaque pas, données ICRP-107 / NUBASE2020 ; contrat v28).

| Groupe | Action | Clavier |
|---|---|---|
| CONSTRUIRE | ajouter / retirer proton, neutron, électron · table périodique · vider | `P` `N` `E` (Maj : retirer) · `T` · `R` |
| LUMIÈRE | régler le laser (barre du haut, ou clic sur une raie) · tirer un photon · flamme | `L` · `F` |
| NOYAU | tirer un neutron lent · tirer une α (menu ☢) | `B` |
| TEMPS | pause · vitesse · prochain événement · pas à pas · aller jusqu'à une station (clic) | `Espace` · `[` `]` · `→` · `A` |
| VUE | atome, noyau, nucléon, gouffre · zoom molette · glisser : tourner (ou déplacer) · double-clic : centrer · valence seule · modèle de Bohr | `1` `2` `3` `G` · `+` `−` · `V` · `O` |
| LABORATOIRES | menu Laboratoires : fusion · fission · transmutation, laboratoire moléculaire, monde subatomique | `U` · `K` |

Les échelles Matière et Molécule sont désactivées dans l'interface
(`App.ADVANCED_SCALES = false`) ; leur code et leurs tests sont conservés.

**Une scène lisible.** Les particules portent une étiquette courte (une par
sorte) ; le calcul (énergie, λ, facteur d'échelle) est au survol et dans le
journal. Un électron qu'on ne peut pas ajouter (Ar⁻, Mg⁻ n'existent pas :
affinité électronique mesurée ≤ 0) rebondit, et la case qu'il aurait prise
clignote en rouge dans la carte des couches, avec la raison.

**Les particules qui arrivent.** Un proton, un neutron ou un électron capturé
vole d'abord vers l'atome ; l'atome ne change (nom, compteurs, journal,
timeline) qu'à l'impact, avec un éclair sur le noyau. Plusieurs clics rapides
volent en parallèle et s'appliquent dans l'ordre.

**Pas à pas** (activé par défaut) : le temps s'arrête juste après chaque
événement. On lit le calcul, on regarde, puis ▶ pour reprendre ou ⏭ pour
aller au suivant.

## Les règles appliquées

**Le temps.** Chaque processus a une probabilité par seconde (désexcitation :
coefficient d'Einstein A ; noyau : λ = ln 2 / t½). L'attente avant
l'événement est tirée selon la loi exponentielle : c'est la loi d'un
phénomène sans mémoire. À 1 ns/s on voit l'hydrogène émettre ; à 1 000 ans/s,
le carbone 14 se désintégrer. « Prochain événement » saute directement au
moment tiré.

**La lumière.**
- Système à un électron (H, He⁺, Li²⁺…) : états (n, l), énergies exactes
  (Rydberg, masse réduite), règle de sélection Δl = ±1, coefficients A
  calculés sur les vraies fonctions d'onde (2p : 1,60 ns ; branchement
  3p → 1s : 88 %). Le 2s, sans transition permise, ne se désexcite qu'en
  émettant deux photons (0,12 s).
- Photon monochromatique, atome immobile au centre d'un faisceau gaussien :
  rayon w = max(1 µm, 2λ dans le vide). En régime de faible couplage,
  P = σ / (πw²/2). L'absorption suit un profil naturel lorentzien, de largeur
  (Γ_haut + Γ_bas)/(2π) en Hz, calculée à partir des durées de vie.
  Les canaux hydrogénoïdes sont pondérés par leurs coefficients d'Einstein
  et leurs dégénérescences ; aucune fenêtre artificielle de ±0,1 %.
  Pour le continuum hydrogénoïde 1s, la section efficace coulombienne rend
  l'ionisation probabiliste (σ au seuil H : 6,30·10⁻²² m²) et
  E_c = E_photon − E_ionisation. Les autres continuums sont signalés comme
  non calculés ; connaître le seuil ne suffit pas à prédire une probabilité.
  Pas encore de Doppler, polarisation, saturation ou émission stimulée.
  Références : [profils de raies NIST](https://physics.nist.gov/Pubs/AtSpec/node20.html),
  [continuum hydrogénoïde, Nahar 2021](https://doi.org/10.3390/atoms9040073).
- Atomes à plusieurs électrons : raies mesurées (NIST) ; absorption sur les
  raies de résonance avec durée de vie connue, approximation à deux niveaux
  non polarisés (dégénérescences et forces d'oscillateur non tabulées).
  Flamme = raie tirée selon son intensité ; excitation collisionnelle simplifiée.

**Le spin.** Électron, proton, neutron : spin ½, des fermions. Deux électrons
au plus par orbitale, de spins opposés (Pauli) ; les orbitales se remplissent
d'abord une par une, spins parallèles (Hund). Le photon a un spin 1 : en
partant il emporte une unité de moment cinétique, d'où Δl = ±1, et le spin
de l'électron ne change pas. Le couplage spin-orbite dédouble les raies des
alcalins (sodium 589,0 / 589,6 nm : 3p₃/₂ et 3p₁/₂). Le spin de chaque noyau
est mesuré (C-14 : 0⁺, Na-23 : 3/2⁺).

**Les électrons.** Un électron ajouté est capturé, ou repoussé si l'ion
n'existe pas : Ne⁻ (affinité électronique ≤ 0), Cl²⁻ (aucun dianion
atomique). Capturé par un noyau nu, il tombe dans un niveau élevé puis
descend en cascade. Énergies d'ionisation mesurées pour chaque ion.

**Le noyau.** Demi-vies, voies et rapports de branchement mesurés (ICRP-107,
NUBASE2020), Q calculé à partir des masses mesurées (AME2020). Les chaînes se
déroulent seules : U-238 → … → Pb-206, en passant par l'isomère Pa-234m. Un
Be-7 entièrement ionisé ne peut plus capturer d'électron : il devient stable.
Après une désintégration α, l'atome fils garde deux électrons de trop et les
perd.

**Les neutrons.** Un neutron lent (0,0253 eV, 2 200 m/s) n'a pas de charge :
aucune barrière ne l'arrête. Ce qu'il fait est tiré au prorata des sections
efficaces thermiques mesurées (Sears 1992, NIST) : diffusion élastique, capture
radiative (n,γ), fission (U-233, U-235, Pu-239), (n,α) et (n,p). Q vient des
masses mesurées ; une fission scinde le noyau composé autour du pic lourd
A ≈ 140 et libère ν̄ neutrons (ENDF/B-VIII.0). Sans section efficace mesurée,
aucune issue n'est inventée.

**La matière.** En dézoomant au-delà de l'atome (ou bouton « Matière »),
256 atomes de l'élément sont simulés en dynamique moléculaire 3D. Une seule
règle entre voisins, le potentiel de Lennard-Jones
V(r) = 4ε[(σ/r)¹² − (σ/r)⁶] : attraction de van der Waals, répulsion des
nuages électroniques. Solide, liquide, gaz et fluide supercritique en
émergent ; l'état de chaque atome est mesuré (garde-t-il ses voisins ? en
a-t-il ?), pas décidé. La chaleur entre par les parois (parois thermiques),
la pression est mesurée par les chocs sur les parois, le piston change le
volume. Pas de gravité : à cette échelle, elle est des milliards de fois
plus faible que l'agitation thermique. L'argon du modèle fond vers 65-70 K
et a son point critique à 134 K (réel : 83,8 K et 150,7 K).

**La molécule.** L'atome courant et un partenaire choisi dans la table.
Personne ne décide s'ils se lient : pour 21 distances R, la chimie quantique
(DFT PBE0/def2-SVP, PySCF) résout leurs électrons et donne l'énergie E(R),
la densité électronique, la différence avec les atomes isolés (où sont allés
les électrons), les charges (Mulliken), l'indice de liaison (Mayer) et les
orbitales moléculaires avec leurs spins. À chaque distance, l'état de spin le
plus bas l'emporte (O₂ triplet près de la liaison). On attrape un noyau, on
le lâche : la vraie force −dE/dR décide, ils vibrent (liaison) ou
s'écartent. Vérifié : H₂ 0,76 Å, 4,4 eV, vibration 7,6 fs ; NaCl 2,34 Å,
0,6 e transférés de Na vers Cl ; O₂ triplet, liaison double ; He₂ ne se lie
pas. L'électronégativité de Pauling n'est affichée que pour comparer.
PySCF n'existe que pour Linux et macOS : le moteur Docker l'a ; lancé sous
Windows hors Docker, cette échelle l'indique. Premier calcul d'une paire :
de quelques secondes à deux minutes, puis gardé en cache (volume Docker).

**Le laboratoire de collisions** (touche `U`) permet de choisir indépendamment
les deux éléments dans le tableau complet, puis leurs isotopes. Le catalogue
contient 118 éléments et 3 557 isotopes ; leurs masses sont évaluées ou
estimées selon les données disponibles. Les fiches identifient Z, N, masse,
rayon, spin et moment magnétique lorsqu'il est connu, avec leur provenance.
À cette échelle, les participants sont des **noyaux entièrement ionisés** ;
les collisions chimiques d'atomes neutres ne sont pas modélisées par cet écran.

L'énergie du faisceau et le paramètre d'impact sont réglables directement.
Le moteur intègre les trajectoires dans le potentiel de Coulomb et un potentiel
nucléaire effectif, avec la force de Lorentz. Les réactions légères utilisent,
dans leur domaine, les ajustements expérimentaux de Bosch–Hale ; les
autres collisions utilisent un modèle de capture, signalé comme estimation dans
le panneau du tir et dans « Modèle & données ». Chaque couple reçoit ses propres
charges, masses, rayons, barrière, trajectoire et champs ; cela ne signifie pas
que toutes ses voies de réaction ont été mesurées. **Calculer la collision**
tire l'issue selon la probabilité de la branche utilisée (souvent :
ils se repoussent) ; **Jusqu'à la fusion** montre la fusion et affiche la
probabilité qu'elle avait à ce tir. Au-delà du fer (Z₁ + Z₂ > 26), souder deux
noyaux coûte de l'énergie au lieu d'en libérer : la chambre parle alors de
**transmutation** (« Jusqu'à la transmutation »).

La fusion va jusqu'au bout : noyau composé chaud, puis désexcitation
statistique — évaporation (Weisskopf), γ, ou **fission** (Bohr–Wheeler,
partage à densité de charge constante, énergie cinétique de Viola) ; les
fragments évaporent ensuite leurs neutrons. Au-delà de Z = 118, aucun noyau ne
tient : **quasi-fission** (les deux noyaux tournent un instant ensemble puis se
séparent). Énergie et quantité de mouvement sont conservées à chaque étape.
Exemples : C-12 + U-238 → californium 250 excité → fission en deux indium ;
Fe-56 + Pb-208 → hassium 264 → xénon + tellure ; Pb-208 + Pb-208 →
quasi-fission.

Chaque noyau a l'apparence de ce qu'il est, **à l'échelle réelle** (aucun
grossissement) : nuage de densité Woods–Saxon teinté de la couleur de son
élément (convention CPK), tous ses nucléons dedans (protons rouges, neutrons
lavande), paquet d'onde de largeur ħ/p, flèche de spin d'autant plus longue
que le spin est grand. Le **champ électrique est un halo par noyau** : sa teinte
conventionnelle reprend l'identité de l'élément et nuance l'isotope, tandis que
sa forme et son intensité suivent le champ calculé pour sa charge et son rayon.
**Entre les noyaux, le potentiel combiné** U = eφ = Σ kZᵢe²/rᵢ (l'énergie qu'aurait
une charge +e posée là) est peint en couleurs de chaleur, du bleu au blanc, là où
les deux potentiels comptent et où leurs champs s'opposent ; ses isopotentielles
y sont tracées, et son **col** — le point de selle où E₁ + E₂ = 0 — est trouvé par
Newton et chiffré (« col U = … MeV ») : il s'échauffe à mesure que les noyaux
s'approchent. Des repères très fins partent de chaque noyau, suivent le champ
total, sont déviés par l'autre et sont entraînés par leur charge (le champ se
déplace avec elle). Au contact, le recouvrement des densités de Woods–Saxon ρ₁·ρ₂
(là où agit la force forte) luit en or. Le **champ magnétique du tir** se lit sur le fond
à la convention habituelle (point s'il sort de l'écran, croix s'il y entre) ; le moteur
l'intègre réellement, et la sonde donne le rayon de Larmor r_L = p/qB de chaque noyau.
Le **tableau de bord** est un instrument plat : partage de l'énergie, mesure principale sur
une jauge graduée, mesures secondaires en lignes, et — en lecture complète — la **courbe du
potentiel V(r)** (survol : la valeur à la distance pointée) et le tableau des lois ; à la fin,
les **équivalences** de l'énergie libérée (joules, TNT, Hiroshima, par gramme de combustible).
L'interface propose **deux lectures** : « Essentiel » (la loi de l'instant, le partage
de l'énergie, trois mesures) et « Complet » (toutes les métriques, les calques, la sonde,
le milieu). À chaque étape, **la loi est composée en grand sur la scène** — fraction
barrée, valeur, formule chiffrée, et deux pastilles de réponse (« r → 2r », « F → F/4 ») —
et reprise en tableau dans le panneau.
Juste avant la première éjection, la scène passe d'elle-même en **« Carte »** : la vue
recule d'avance, les mêmes noyaux gardent leurs champs et leurs spins, l'élément créé est
un centre fixe et les distances sont comprimées (échelle log, facteur affiché) pour que
tout tienne à l'écran ; chaque étiquette donne la vitesse calculée et la distance vraie.
Un clic sur un corps zoome dessus sans déplacer le centre ; un clic dans le vide revient.
À la **rupture**, la lecture passe au ralenti (t ∝ u^3) et la caméra tient le barycentre
en reculant pour garder les fragments, avant de revenir sur l'élément créé ; dans un
plasma, chaque corps chargé laisse une **trace d'ionisation** dont l'épaisseur suit son
pouvoir d'arrêt dE/dx ∝ Z²/v² — le neutron et le photon n'en laissent aucune. **Le choc a lieu dans le plasma** réglé
dans le panneau Lawson : ses champs et son potentiel sont écrantés (Debye–Hückel,
e^(−r/λ_D)) — infime à l'échelle des noyaux à 15 keV et 10²⁰ ions/m³ (λ_D ≈ 64 µm),
sensible aux densités extrêmes — et un médaillon, sous le tableau de bord, montre
ce plasma **à son échelle propre** : une boîte de quatre longueurs de Debye, avec sa
grille et sa règle en unités physiques, où ions et électrons s'agitent selon
Maxwell–Boltzmann (les électrons √(m_i/m_e) fois plus vite, chacun traînant la
distance qu'il parcourt pendant la pose) ; la couleur de chaque particule suit son
énergie ½mv² (bleu à 1 keV, blanc à 100 keV), une onde de Langmuir (Bohm–Gross) y
fait respirer le nuage d'électrons, et des photons de freinage sont émis au taux
calculé par le moteur. Tout y suit T et n. Le champ magnétique superpose les dipôles de spin,
le champ des charges en mouvement et le champ externe du tir ; les marqueurs
mobiles en indiquent le sens. Un noyau chaud est une goutte liquide qui vibre.
La grille du champ est mise à jour dans un `PixelBuffer` ; la zone du col et ses
isopotentielles sont calculées sur la même grille. Les équations, le modèle et l'erreur
d'énergie se consultent dans **Inspecter** : ils ne couvrent plus le bas de
la scène. Les particules du décor ont été retirées ; les grains qui suivent
le champ électrique restent présents.

Les trajectoires reçues sont conservées pendant les mises à jour des réglages ;
la retransmission d'un même tir ne redémarre pas la lecture. Entre deux
échantillons d'une même phase, les positions sont interpolées avec les vitesses
du moteur (Hermite), pour conserver le freinage et le recul. Cette liaison est
commune à tous les éléments et produits. Les traceurs électriques gardent leurs
positions dans le monde 3D et interrogent le champ total à chaque sous-pas : ils
ne sont plus attachés au déplacement du noyau émetteur. Leur vitesse de lecture
est une convention graphique, indépendante du zoom.

**La vue zoome avec eux.** Les noyaux partent de 12 fois la distance de
rebroussement ; à mesure qu'ils se rapprochent, la vue zoome, puis se fige dans
la zone de freinage pour qu'on voie le noyau ralentir — des échos à intervalles
de temps égaux se resserrent derrière lui (la répulsion change son élan en
énergie du champ, sans frottement) — jusqu'au femtomètre pour le mur de Coulomb,
l'effet tunnel et la soudure. Chaque noyau est comprimé le long de l'axe par le
champ de l'autre (polarisation de Coulomb, calculée : invisible pour le tritium,
forte entre noyaux lourds) ; la valeur β₂ est dans sa fiche. Les fiches donnent leur vitesse à chaque
instant. La caméra a de l'inertie (on la pousse, elle glisse puis s'arrête) et
ses changements de cadrage glissent au lieu de sauter ; un long déplacement
recule d'abord, comme sur une carte qu'on survole. Le champ magnétique pâlit
quand la tension électrique monte ; de petits repères mobiles suivent son
vecteur local recalculé à chaque image, sans dessiner d'orbites. Avant une fission, le noyau s'étire en haltère
(ovales de Cassini) jusqu'à la scission ; avant une émission, sa surface bombe là
où la particule sort. Les ruptures qui remplacent deux noyaux par deux fragments
sont également reconnues. À la première rupture et aux fissions, la caméra
observe le lieu d'émission pendant une seconde de lecture avant d'accompagner
un fragment : son recul reste visible. Les vitesses des produits sont celles des
échantillons Python, appliquées dès leur naissance, sans accélération ajoutée
par le rendu. Il n'y a ni anneau de choc ni traînée dessinés autour des produits.
Un éclat local n'apparaît que si le moteur a effectivement émis un photon γ ;
son intensité spatiale suit `I₀/[1+(r/R₀)²]` et sa persistance à l'écran décroît
exponentiellement. Cette persistance est un temps d'exposition graphique, pas
la durée physique de l'émission.

**La soudure est calculée** : depuis la sortie du tunnel (là où V(r) = E) ou
le contact, le moteur intègre le mouvement dans le potentiel Coulomb +
Woods–Saxon avec la friction nucléaire de Gross–Kalinowski ; les deux noyaux
tombent l'un dans l'autre, leur élan devient chaleur (affichée), leurs champs
électriques se fondent en un seul. Pour les noyaux lourds, la répulsion
arrête ce calcul au contact (système dinucléaire) : la fonte finale des deux
formes est montrée lentement, signalée comme non tracée. Les produits sont
montrés tant qu'ils restent sur la carte (le rayon de leur cercle de Lorentz,
de l'ordre du centimètre, est donné en texte). **Une fois la fusion faite**, la
vue centrale ne présente plus que l'élément central créé : la caméra glisse sur
lui (spin, champ électrique, dipôle magnétique) avec sa fiche (liaison par
nucléon, champs en surface et au pôle, demi-vie, vitesse, cercle de Lorentz) ;
les autres produits sont projetés sans étiquette. Le **bilan interactif**
s'ouvre alors à droite (flottant, détachable par « ⧉ Fenêtre ») : onglets *Créé*,
*Rejeté*, *Lumière* (photons γ : énergie, longueur d'onde, fréquence) et
*Énergie* (Q en joules et en **équivalent TNT**, par réaction et pour 1 g de
combustible, ce qu'emporte chaque produit) ; un clic sur un élément ouvre sa
fiche et amène la vue principale sur lui (un neutron éjecté, par exemple).
Un tir refusé (énergie trop basse pour le couple, ou au-delà du modèle) est dit
au centre de l'écran, avec un bouton qui règle l'énergie conseillée et relance. Le champ électrique reprend la teinte conventionnelle de chaque isotope ;
entre les noyaux, le potentiel combiné et son col sont chiffrés ; le champ magnétique se lit par des repères
advectés dans B ; le paquet d'onde s'aplatit contre la barrière dans la direction du
champ total. On peut suspendre, parcourir, rejouer ou lancer de nouveaux tirs.

Le mode **Plasma** règle T, n et τ (critère de Lawson, courbe nτT) ; chaque
tir y tire son énergie dans la distribution de Maxwell–Boltzmann.

Les équations, les modèles approchés et les conventions de représentation
sont décrits dans [le modèle de collision](docs/FUSION_MODEL.md).

**Le cœur d'étoile** (désactivé dans l'interface, `App.STAR_SCREEN = false` ; code et tests conservés). On choisit
un lieu réel (cœur du Soleil, Soleil jeune, étoile massive, géante rouge,
combustion du carbone, réacteur D-T, surface du Soleil) puis on règle la
température et la densité. Tout se calcule :
- les réactions viennent du réseau nucléaire REACLIB (`pynucastro`,
  85 réactions sur 24 noyaux : chaîne p-p, cycle CNO, triple-α, carbone,
  D-T), avec l'écrantage électronique ; la composition évolue en intégrant
  dY/dt (solveur raide de `scipy`) ;
- l'énergie est Q = Δm·c² de chaque réaction, moins ce qu'emportent les
  neutrinos qui s'échappent (énergies moyennes mesurées, Bahcall) ;
- l'ionisation suit l'équation de Saha avec l'abaissement du potentiel :
  au cœur du Soleil les noyaux sont à 18 pm les uns des autres, moins que
  la taille d'un atome, les électrons ne peuvent plus être liés ;
- la pression additionne noyaux, électrons (Fermi-Dirac : dégénérés dans
  une géante rouge) et lumière (seulement si le plasma est opaque) ;
- une fenêtre de ~220 noyaux, à sa vraie taille (172 pm au cœur du Soleil,
  avec un atome d'hydrogène en pointillés pour comparer), montre chaque
  fusion tirée au sort selon les vrais taux : les réactifs se rejoignent,
  l'éclair, les produits, le neutrino qui s'échappe, le positon qui
  s'annihile. Le panneau dit ce qui a été créé et détruit, l'énergie, la
  pression, et ce que cela veut dire.

Vérifié : pression centrale du Soleil 2,3·10¹⁶ Pa (modèle standard :
2,34·10¹⁶), ~1,6 mW/kg (moins qu'un corps humain), hydrogène épuisé en
~4 milliards d'années ; réacteur D-T : 4 atm ; surface du Soleil : 7 700 Pa,
atomes neutres.

## Genesis, la petite guide

> **Mise de côté.** Genesis n'est plus affichée : le journal n'a qu'une vue, les logs. Son code est gardé dans `sim/app/.../mascot/` ; pour la retrouver, passer `GENESIS_ENABLED` à `true` dans `sim/app/.../app/App.java`. Ce qui suit décrit ce qu'elle fait quand elle est activée.

Genesis est une boule de matière noire, pleine d'univers. Elle flotte dans une boule de verre posée sur un socle (quatre câbles, losange doré, le vide du fond qui sert de voyant).

**Sa forme est solide et simple** (`GenesisBlob.java`) :
- **Une boule ronde et deux oreilles arrondies**, avec un contour lumineux, bleu puis blanc, et un grand halo autour.
- **Dedans, de l'espace** : le vide noir, une nébuleuse bleue qui tourne lentement, des étoiles.
- **Au cœur, une lumière blanche.**
- **En bas, de l'écume blanche**, percée de trous, avec quelques îlots qui s'en détachent vers le haut.
- **Autour, des gouttelettes** qui sortent de la boule, flottent, puis y reviennent se fondre.
- **Trois reflets clairs** en haut à droite, comme sur du verre.

Elle n'a pas de visage. Ses humeurs passent par trois choses :
- **Son cœur de lumière** : il se tourne vers la souris, bat quand elle parle, baisse quand elle dort.
- **Ses oreilles**, montées sur ressort : elles traînent quand elle se déplace, se dressent quand elle est curieuse, tombent quand elle dort ; l'une d'elles frémit de temps en temps.
- **Son corps de gelée** : il s'étire dans le sens où elle va, s'écrase quand on la touche, fait de petits bonds quand elle est contente ; l'écume penche et clapote avec ses mouvements.

Elle bouge peu et doucement : elle flotte au milieu, penche vers la souris, se pose au fond pour dormir.

**Un clic la divise** (`GenesisFlask.java`, 7,8 s) : elle rentre ses oreilles, se sépare en cinq masses qui flottent, se soudent et se séparent comme dans une flasque, puis se rassemble, se reconstitue et rejette des résidus de matière. Elle le fait aussi d'elle-même de temps en temps, au repos.

La forme est une surface implicite (la distance à la boule, aux oreilles et aux gouttes, réunies par un minimum lissé) : le contour est lisse et net à toutes les tailles. Aucune bibliothèque n'est ajoutée.

**Le journal a deux vues**, avec une bascule en haut à droite (le choix est mémorisé) :
- **Journal** (sans Genesis) : les logs complets. Chaque ligne porte une étiquette de catégorie (Lumière, Électrons, Noyau, Info), le temps simulé, le titre, le calcul dans un encart et l'explication du moteur. Des filtres avec compteurs ne gardent qu'une catégorie.
- **Genesis** : elle explique chaque événement en mots simples, en trois temps : ce qui s'est passé, « Pourquoi : », puis « Et maintenant : » (ou « À retenir : », « Essaie : », « En clair : »). Les logs restent dessous, en une ligne chacun ; un clic sur une ligne lui fait expliquer cet événement. Quand plusieurs événements arrivent au même instant, elle commence par le plus important (le noyau, puis la lumière, puis les électrons).

Elle lit le journal : chaque événement devient une phrase dans sa bulle, avec le calcul en doré. Au bout de 80 s sans
événement, elle s'endort : elle se pose au fond, les oreilles tombées, le cœur en veilleuse. `gradlew mascotPreview` dessine cinq planches : ses états,
douze instants serrés (0,2 s d'écart, pour juger la continuité), huit instants plus espacés, ses réactions à la souris, et sa
division après un clic ; `gradlew journalPreview` dessine le journal.

## L'atome en 3D

L'écran de l'atome ne dessine plus d'orbites : tout y est calculé, en 3D, et
on le fait tourner en le glissant (il tourne aussi, très lentement, de
lui-même). `python -m tools.export_atom_frames` (dans `logic`) écrit des frames réelles
du moteur dans `artifacts/frames` ; `gradlew dashboardPreview` rend l'écran entier (barres, scène, timeline), une désintégration réelle de l'uranium, la carte des couches, la légende des limites et une fission de U-235 par un neutron dans `artifacts/dashboard` ; `gradlew atomPreview` les rend dans
`artifacts/atom3d` (éléments, rotation, plongée, goutte, gouffre, photon absorbé
par H, photoionisation de Na, écran étroit) et mesure le temps par image
(1 à 6 ms) ; `-Ponly=photon` ne joue que les photons.

**Le nuage électronique** (`OrbitalCloud`). Chaque sous-couche occupée est la
densité |ψ_nlm|² = R_nl(r)² · Σ occ_m Y_lm² : fonction radiale hydrogénoïde
(ses n − l − 1 nœuds), harmoniques sphériques réelles dans l'ordre de
`orbitals.py`, remplissage de Hund envoyé par le moteur, taille mise à
l'échelle sur le rayon de Slater du moteur. L'électron unique (H, He⁺…) est
exact (a₀/Z) ; le moteur ne suivant pas m, son nuage est le mélange isotrope des
2l + 1 orientations. Rendu volumique exact : chaque pixel reçoit la densité
intégrée le long du regard. Le long du rayon b⃗ + t·ê, la partie angulaire est
un polynôme en t ; l'intégrale se ramène à des tables G_j(b) = ∫ g t^j dt,
calculées une fois par (n, l). Les coefficients du polynôme sont développés
une fois par image et par orientation : chaque pixel ne coûte que quelques
multiplications. Les sous-couches sphériques, invariantes par rotation, sont
gardées d'une image à l'autre. Ce qu'on voit, et ce qui est un choix
d'affichage :
- les formes sont exactes : sablier p (bore 2p¹), d_z² avec son anneau
  (scandium 3d¹), lobes f, nœuds radiaux (anneaux du 4s excité du sodium) ;
  une sous-couche pleine ou à moitié pleine est sphérique (Unsöld) ;
- chaque sous-couche a sa couleur et sa propre échelle de luminosité, réglée
  sur la colonne au rayon le plus probable (courbe en S en dessous,
  logarithmique au-dessus). Sans cela le cœur 1s éblouirait tout, et l'atome
  ne serait qu'une boule (sa densité totale est presque sphérique). Les
  couches pleines internes sont atténuées ; l'avant du nuage est un peu plus
  lumineux (indice de profondeur, 30 % au rayon le plus probable) ;
- les **points brefs** sont des positions tirées selon |ψ|² (Monte-Carlo) :
  ce qu'on trouverait en mesurant un électron. Les tirages changent, la loi
  ne change pas : l'état est stationnaire ;
- quand la configuration change (capture, saut d'un électron), le nouveau
  nuage apparaît en 0,9 s pendant que l'ancien s'efface. Pour l'électron
  unique, c'est la densité exacte du mélange (les termes croisés s'annulent
  en moyenne sur m).

**Le photon** (`PhotonPacket`), construit à partir de λ seule :
- **Forme** : un paquet d'onde 3D, avec une enveloppe gaussienne (un fuseau qui fond aux bouts) et deux rubans perpendiculaires, E (couleur de λ) et B (pâle). Ils sont en phase, |B| = |E|/c.
- **Polarisation** : rectiligne, E le long de l'axe z de l'atome. Le paquet arrive un peu en biais, en perspective, et ses rubans suivent la caméra.
- **Motif** : dans le vide, crêtes et enveloppe avancent ensemble à c, donc le motif est fixe dans le paquet.
- **Crêtes à l'écran** : la distance entre crêtes est λ, à l'échelle quand elle tient à l'écran (rayons X : ~50 pm). Un photon UV, visible ou IR est des milliers de fois plus grand que l'atome : ses crêtes sont alors resserrées selon log λ, et l'étiquette donne le facteur (« λ dessinée ÷1 128 »). La vraie image est un atome baigné dans un champ presque uniforme qui oscille à ν (approximation dipolaire).
- **Couleur** : CIE 1931 (ajustement de Wyman, Sloan et Shirley 2013) → sRGB, pour tout le simulateur. Hors du visible, une couleur conventionnelle annoncée.
- **Photon ionisant** (hν > énergie d'ionisation actuelle) : franges chromatiques et halo qui tremble, ∝ log(1 + (hν − I)/I).
- **Longueur du paquet** : une raie atomique ou nucléaire dure des nanosecondes, soit ~10⁷ à 10⁸ oscillations ; même un γ est un long paquet, pas un « éclat bref ». Le paquet est donc dessiné long.

**Le photon en vraie 3D** (`Photon3DLayer`, JavaFX 3D sur la scène, projection parallèle alignée pixel pour pixel) :
- **Volumes** : un fuseau fin et presque transparent (sphère étirée, matériau Phong, reflet spéculaire), pour que l'œil aille sur l'onde ; deux rubans maillés (TriangleMesh, E et B, auto-éclairés), un axe fin et une lumière ponctuelle embarquée qui éclaire ses volumes. L'orientation suit la caméra de l'atome.
- **Le canevas garde** le halo du fuseau, les franges du photon ionisant, et la lueur qu'il projette dans le brouillard traversé. Cette lueur est un effet de rendu, pas une diffusion calculée.

**Les particules :**
- **L'électron libre** est un point très brillant dans un halo qui s'éteint, et non une bille : c'est une particule ponctuelle, sans rayon mesuré.
- **Les nucléons**, vus de près, sont des bulles : corps translucide, liseré lumineux, et leurs trois quarks de valence qui tournent et échangent leur couleur. Une lueur commune fond leurs bords, et le noyau se lit comme une goutte. Leur teinte glisse quand un pion change leur identité.

**L'interaction.**
- **Trajet** : le photon file vers l'orbitale qui agit (électron excité ou unique, sinon la valence), au point où elle est la plus probable (son lobe, au rayon le plus probable), et il la suit si l'atome tourne.
- **Absorption** : le photon disparaît, un éclair s'étend depuis ce point, la nouvelle orbitale se matérialise en 0,22 s et pulse, et l'ancienne s'efface en 0,9 s.
- **Photoionisation** : l'électron part en paquet d'onde libre, qui s'étale en avançant comme tout paquet libre. Sa direction est tirée selon cos²θ autour de E, sa vitesse à l'écran ∝ √K (ralentie), et ses crêtes de de Broglie sont à λ = h/p = 1,226 nm/√(K/eV).
- **Contraction de l'ion** : une sous-couche qui reste avec le même remplissage ne clignote pas. Elle passe de son ancien rayon de Slater au nouveau en 0,35 s (mouvement sec puis freiné). Selon Slater, seules les couches que l'électron parti écrantait se contractent : les couches internes ne bougent pas.

**Le panneau** de droite se replie (»). Une pastille le remplace (« COUCHES · SPINS · 5f³ 6d¹ »), et il est replié par défaut sous 1 250 px de large.

**La transmutation, visible même à un milliard d'années par seconde** (les effets durent en temps réel) :
- **Le recul du noyau**, par conservation de l'impulsion. Pour une α : p = √(2 m_α E_α), v = p/M, soit 243 km/s pour U-238 → Th-234. Pour une β, l'antineutrino emporte une part inconnue : la direction est approchée. Le déplacement à l'écran est amorti, et la vitesse est écrite.
- **Le nuage qui respire** : Z change, l'attraction aussi. Les rayons de Slater recalculés par le moteur sont atteints comme par un ressort amorti : le nuage gonfle après une α et se resserre après une β⁻.
- **L'onde de la désintégration** : trois fronts avec rémanence, qui s'éloignent du noyau.
- **Le bandeau** « Uranium → Thorium », avec Z avant/après et le recul.

Le photon file vers un point d'impact tiré au hasard selon |ψ|² de l'orbitale qui agit (Monte-Carlo) : chaque tir touche ailleurs, là où l'électron a des chances d'être. L'éclair et l'électron libéré partent de ce point. Pour les atomes lourds, la carte des couches résume les sous-couches pleines du début en une ligne (clic : tout voir). Les étiquettes du nuage restent dans la scène, loin de l'en-tête.

**La plongée.** Le clic sur la loupe (ou « Noyau ») traverse les décades à
vitesse constante (≤ 1,5 décade/s), avec le champ de vision et le nombre
moyen d'électrons à cette distance du noyau (Σ N·P(r' < r)). La caméra entre
dans le nuage : seule la tranche |t| < 1,5 × la largeur du champ est
intégrée, avec des tables exactes, tabulées en épaisseur. Le voile 1s reste
visible longtemps, et c'est réel : sa densité est maximale au noyau. Il
s'éteint vers 100 fm, et la goutte nucléaire apparaît.

**Le gouffre des échelles** (bouton « Gouffre », touche `G`). Noyau et nuage
ensemble, la distance au noyau comprimée en logarithme dans le plan de
l'image : ρ = K·log₁₀(1 + r / 1 fm), un anneau par décade de 1 fm à 10 nm. La
luminosité suit la probabilité par surface de l'image déformée (colonne ×
b·db / (ρ·dρ)). Les sous-couches deviennent des anneaux à leur décade, et
le centre, immense dans l'image, est presque vide : l'écran calcule combien
d'électrons s'y trouvent (2,8·10⁻³ entre 4,5 fm et 0,21 pm pour le fer). Une
fiche donne la vraie proportion : si le noyau était une bille de 1 cm, le
nuage s'étendrait sur des centaines de mètres.

**Le noyau, goutte de liquide quantique** (`NuclearDrop`). Les nucléons
placés par le moteur sont tournés en 3D et triés en profondeur, dans une
membrane : la silhouette calculée de la surface déformée, avec une peau
diffuse (Woods-Saxon, a = 0,55 fm). La surface vibre selon Bohr et Mottelson :
R(θ, φ) = R₀ (1 + Σ α_λμ Y_λμ), modes λ = 2 et 3. Raideur C_λ (tension de
surface a_s contre répulsion des protons a_c, ceux de `nucleus.py`),
inertie d'écoulement irrotationnel B_λ = 3 A m_N R₀² / (4πλ), ħω_λ = ħ √(C_λ/B_λ),
amplitude de point zéro β² = (2λ+1) ħ / (2 B ω). Les nucléons suivent le champ
de déplacement ∝ r^(λ−1) Y_λμ. La période réelle (~10⁻²¹ s) est montrée en
3,6 s, avec le vrai rapport de fréquences λ = 3 / λ = 2. Le modèle est
grossier : s'il prédit β > 0,3 (noyaux légers, fer…), la fiche le dit et
l'amplitude affichée est bornée à 0,12. Les nucléons échangent des **pions
virtuels**, chacun dessiné comme un tube de flux qu'une perle parcourt :
portée ħ/(m_π c) = 1,41 fm, durée ħ/(m_π c²) = 4,7·10⁻²⁴ s (ralenti à 1,7 s), voisin
choisi selon Yukawa e^(−r/1,41 fm)/r. Entre un proton et un neutron, deux
échanges sur trois sont chargés (couplage √2 pour π±). Le proton qui émet un
π⁺ (u d̄) devient neutron, le neutron qui l'absorbe devient proton : les
couleurs changent, la charge totale jamais (testé à chaque pas). La fiche
« Goutte quantique » donne ħω₂, la période, β₂ et le compte des échanges.

## Ce que montre l'écran

- **La carte** : l'atome en 3D à sa vraie échelle (voir « L'atome en 3D ») :
  un brouillard de probabilité |ψ|² par sous-couche, nommée avec son
  remplissage et ses spins ; les photons (colorés selon λ, en pointillé si
  invisibles), les électrons capturés ou repoussés, les particules qui sortent
  du noyau. Une **loupe** montre en permanence le noyau, cent mille fois plus
  petit (clic : on y plonge, lentement, à travers le vide). Survol : la
  sous-couche qui domine sous la souris, le noyau, l'électron, le pion.
- **La bulle** : le dernier événement et son calcul, reliée à l'électron ou
  au noyau qui l'a produit ; elle reste jusqu'au suivant.
- **Les panneaux** selon l'échelle : couches et spins en diagramme
  d'énergie, 1s en bas et chaque sous-couche suivante (Klechkowski) au-dessus ;
  le survol d'une sous-couche explique son rang (n + l, voisines, exceptions
  mesurées) (atome) ; forces de Weizsäcker et bilan de masse mesuré
  E = Δm·c² (noyau) ; quarks, couleurs, spins (nucléon).
- **Les fiches** : clic sur une particule (électron, nucléon, photon, quark,
  α, neutrino…) : masse, charge, spin, moment magnétique, durée de vie
  (CODATA, PDG).
- **Le bas de l'écran** : ce qui peut arriver ensuite (« demi-vie 5 700 ans
  → ≈ 8 s d'attente à cette vitesse »).
- **Lumière** : niveaux d'énergie avec l'électron et ses sauts ; spectre avec
  les raies absorbables maintenant (▲), le seuil d'ionisation et le laser.
- **Orbitale** : pour un système à un électron, densité de l'état (n,l)
  réellement occupé, masse réduite comprise. Le moteur ne suit pas m :
  mélange équiprobable des orientations, donc densité isotrope, même en p.
  Les points sont des tirages conditionnés à la coupe xz, pas une projection
  de mesures 3D. Pour les atomes à plusieurs électrons : valence approchée
  par Slater ; la densité excitée non calculée est indiquée comme absente.
  La carte montre toutes les sous-couches en 3D (voir « L'atome en 3D »). La
  pastille **phase ±** montre le signe de ψ = R_nl·Y_lm pour une orbitale — celle
  qui a reçu le dernier électron (Hund), ou la dernière occupée visible si cet
  électron est hors de la coupe xz — en bleu pour ψ > 0, en orange pour ψ < 0,
  la luminosité suivant |ψ|². Le dessin du panneau se resserre avec la place
  laissée par le journal au lieu d'être coupé.
- **Noyau** : vallée de stabilité, demi-vie, probabilité par seconde, voies.
- **Matière** : la boîte en 3D (glisser pour la tourner), les atomes colorés
  selon leur agitation ou leur état, les traînées des atomes de gaz, les
  chocs sur les parois ; les mesures (température, pression, densité, part
  de chaque état), les valeurs réelles pour comparer, la courbe V(r).
- **Journal** : tous les événements, du plus récent au plus ancien.

## Limites du modèle

Elles sont aussi rappelées dans l'application (pastille ⓘ de l'atome).

- **Nuage électronique** : orbitales hydrogénoïdes avec la charge effective de
  Slater. Exact pour un électron seul, approché au-delà : pas de
  Hartree–Fock, pas d'effets relativistes (or, mercure, éléments lourds).
- **Énergies et raies** : mesurées (NIST) quand elles existent, sinon
  estimées par Slater.
- **Noyau** : masses et désintégrations mesurées (AME2020, NUBASE,
  ICRP-107) ; Weizsäcker pour les noyaux jamais observés. La disposition des
  nucléons est une image, pas un calcul du modèle en couches.
- **Hasard** : chaque événement est tiré selon les probabilités mesurées ;
  deux essais ne racontent pas la même histoire.
- **Neutrons** : seulement des neutrons thermiques (0,0253 eV), et seulement sur
  les noyaux dont les sections efficaces sont tabulées (isotopes naturels et
  quelques actinides).
- **Au-delà de l'oganesson** (Z = 118) : aucun proton n'est ajouté.
- **Réaction en chaîne** : diffusion isotrope après correction de transport ;
  graphite et béryllium en gaz libre (sans S(α,β)) ; β non pondéré par
  l'importance ; pendant l'excursion, inertie du solide, changements de phase du
  plutonium, ionisation et épuisement du combustible dans k sont négligés, et le
  réflecteur reste immobile. Seuls les 16 noyaux de la bibliothèque ont un k∞.
- **Au-delà du fer** (Z₁ + Z₂ > 26) : la chambre de collision parle de
  transmutation, pas de fusion, car souder deux noyaux y coûte de l'énergie.

## D'où viennent les chiffres

| Grandeur | Source |
|---|---|
| Constantes physiques, masses et spins des particules | CODATA, via `scipy.constants` ; PDG (quarks, neutrinos) |
| Spin et parité des noyaux | NUBASE, base de `mendeleev` |
| Paramètres de Lennard-Jones ε, σ | Magalhães et al. 2013, Poling et al. 2001, via `chemicals` ; sinon Bird-Stewart-Lightfoot |
| Fusion, ébullition, point critique réels | base de `mendeleev` |
| Voisins, listes de paires | `scipy.spatial.cKDTree` |
| Liaisons, densités, orbitales moléculaires | DFT PBE0/def2-SVP, `pyscf` |
| Électronégativité de Pauling (comparaison) | base de `mendeleev` |
| Barrière de Coulomb, effet tunnel | rayon nucléaire R = 1,2·A^⅓ fm, facteur de Gamow |
| Voies de sortie des fusions | réactions mesurées (D+T, D+D, D+He-3, p+Li-7, D+Li-6, p+B-11, p+D) |
| Portée de la force forte | ħ/(m_π c), m_π± = 139,570 MeV (PDG) |
| Taux des réactions stellaires, écrantage | REACLIB, via `pynucastro` (screen5) |
| Énergie des neutrinos solaires | Bahcall, *Neutrino Astrophysics* (1989) ; sinon spectre β calculé |
| Ionisation du plasma | équation de Saha + abaissement du potentiel (sphère ionique) |
| Pression des électrons | statistique de Fermi-Dirac relativiste (`scipy.integrate`) |
| Lieux stellaires | modèle solaire standard ; ITER pour le réacteur |
| Fonctions d'onde, intégrales | `scipy.special` (Laguerre), `scipy.integrate` |
| Demi-vies, modes, branchements, filiations | ICRP-107 / NUBASE2020, via `radioactivedecay` |
| Sections efficaces rapides et thermiques, ν̄, χ, neutrons retardés, énergie de fission (réaction en chaîne) | ENDF/B-VIII.0 (NNDC, HDF5 d'OpenMC), condensé en 44 groupes par `logic/tools/build_chain_groups.py` |
| Assemblages critiques de référence (validation) | ICSBEP : Godiva, Jezebel, Jezebel-233, Flattop-25 ; cas analytique LA-13511 |
| Électrons et forces des molécules (laboratoire moléculaire) | GFN1-xTB (Grimme, Bannwarth, Shushkov 2017) ; paramètres de xtb / tblite (LGPL-3.0), développements STO-NG, rayons et C6 de référence de D3 (Grimme 2010) extraits par `logic/tools/build_xtb_data.py` ; vérifié contre dxtb |
| Géométries, vibrations, enthalpie de réaction des molécules (validation) | NIST CCCBDB ; NIST-JANAF (ΔH°f de l'eau) |
| Loi de multiplicité des neutrons de fission, ν̄ de fission spontanée | Terrell (1957) ; Holden et Zucker (1985) |
| Dilatation thermique de U, Pu, Th | CRC Handbook of Chemistry and Physics |
| Sections efficaces des neutrons thermiques (diffusion, absorption) | Sears, *Neutron News* 3 (1992) 26, table du NIST via `periodictable` |
| Part de fission, (n,α), (n,p) dans l'absorption ; neutrons par fission ν̄ | évaluations thermiques ENDF/B-VIII.0 (Mughabghab) |
| Masses atomiques (Q), noyaux très éphémères | AME2020 / NUBASE, base de `mendeleev` |
| Énergies d'ionisation de chaque ion, affinités | NIST, base de `mendeleev` |
| Raies d'émission, durées de vie des raies de résonance | NIST ASD (table embarquée) |
| Configuration électronique | Klechkowski + 20 exceptions réelles |
| Charge effective, rayons | règles de Slater |
| Bilan de liaison du noyau (termes) | Weizsäcker |
| Noyau jamais observé | estimations dites comme telles (Viola-Seaborg, espace de phase de Fermi) |

## Tests

```bash
cd logic && python -m unittest discover -s tests -t .     # tout le moteur (une dizaine de minutes)
cd logic && python -m unittest tests.test_fusion          # un seul fichier de tests
cd sim   && gradle test                                   # contrat et rendu
```

## Structure

Le dépôt a cinq dossiers ; chacun a son README, qui dit ce qu'il contient et comment s'en servir.

| Dossier | Contenu |
|---|---|
| [`core/`](core/README.md) | Le contrat gRPC partagé entre le moteur et le client (`genesis.proto`) |
| [`logic/`](logic/README.md) | Le moteur Python : la physique, rangée par domaine |
| [`sim/`](sim/README.md) | Le client JavaFX : les écrans, rangés par laboratoire |
| [`docs/`](docs/README.md) | Les modèles physiques, expliqués en détail |
| [`artifacts/`](artifacts/README.md) | Les images et les frames d'aperçu |

Le moteur et le client sont rangés de la même façon, par domaine :

| Domaine | Moteur (`logic/`) | Client (`sim/app/src/main/java/org/genesis/`) |
|---|---|---|
| Point d'entrée | `engine.py` | `app/` (l'application, le journal, la timeline) |
| Liaison gRPC | `genesis_pb2*.py` (générés) | `engine/` |
| Session, données | `simulator/` | — |
| Outils partagés | `common/` | `common/` |
| L'atome et sa lumière | `atomic/` | `atom/` |
| Le noyau | `nuclear/` | `atom/` (panneaux du noyau) |
| Molécule et matière | `molecular/` | `matter/` |
| Fusion, réacteur, étoile | `fusionlab/` | `fusion/` |
| Monde subatomique | `quantumlab/` | `subatomic/` |
| Laboratoire moléculaire (GFN1-xTB) | `moleculelab/` | `molecule/` |
| Réaction en chaîne, masse critique (masquée) | `chainlab/` | `chain/` |
| Genesis (mise de côté, non affichée) | — | `mascot/` |
| Outils et tests | `tools/`, `tests/` | `sim/app/src/test/java/org/genesis/` (mêmes paquets) |

Fichier par fichier :

| Chemin | Rôle |
|---|---|
| `core/genesis.proto` | Le contrat partagé (v28), longueurs d'onde absorbables en double précision |
| `logic/chainlab/transport.py` | Le transport des neutrons dans une sphère : probabilités de première collision, k_eff, flux adjoint, Λ |
| `logic/chainlab/lab.py` | Le laboratoire de la réaction en chaîne : réglages, rayon critique, cascade, excursion |
| `logic/moleculelab/xtb.py` | GFN1-xTB en numpy : énergie et forces analytiques, terme par terme |
| `logic/moleculelab/lab.py` | Le laboratoire moléculaire : établi, relaxation, dynamique, traction, étincelle, réacteur, vibrations |
| `sim/app/.../molecule/MoleculeView.java` | L'écran du laboratoire moléculaire ; la scène dans `MoleculeScene` (vue ρ), `DeformationMap` (carte Δρ en courbes de niveau), `OrbitalSurface` et `OrbitalLayer` (lobes de verre de ψ ; `OrbitalDots` sans 3D) ; les lectures dans `EnergyBalance` (bilan d'énergie), `EnergyTarget` (niveaux en cible) et `AtomMap` (carte des forces de l'atome choisi) |
| `sim/app/.../chain/ChainView.java` | L'écran de la réaction en chaîne ; sphère 3D dans `ChainScene`, bac à sable dans `YieldView` |
| `logic/atomic/light_interaction.py` | Profils naturels, faisceau gaussien et continuum hydrogénoïde 1s |
| `logic/simulator/simulation.py` | L'horloge et les règles : la session qui vit |
| `logic/engine.py` | Serveur gRPC : lit les actions, avance le temps, envoie les événements |
| `logic/atomic/hydrogen.py` | Coefficients d'Einstein, durées de vie, absorption (un électron) |
| `logic/nuclear/nuclear_data.py` | Accès aux bases nucléaires (radioactivedecay, mendeleev) |
| `logic/nuclear/neutrons.py` | Un neutron lent sur un noyau : sections efficaces thermiques mesurées, issue tirée, Q des masses mesurées |
| `logic/atomic/atomic_data.py` | Ionisations, affinités, raies de résonance, doublets de spin |
| `logic/common/particles.py` | Le zoo de particules : masse, charge, spin… |
| `logic/common/elements_db.py` | La base SQLite de `mendeleev` derrière une seule connexion verrouillée, partagée par tous les threads |
| `logic/molecular/matter.py` | Dynamique moléculaire : Lennard-Jones, parois thermiques, états mesurés |
| `logic/molecular/chemistry.py` | Deux atomes en chimie quantique : E(R), densité, orbitales, dynamique |
| `logic/fusionlab/fusion.py` | Le laboratoire : potentiel, WKB, trajectoire, cascade d'évaporation, fission, nuages |ψ|², interprétations |
| `logic/fusionlab/collider.py` | Réactions mesurées, étiquettes, cinématique à deux corps |
| `logic/fusionlab/fusion_dynamics.py` | Collisions intégrées, soudure calculée (friction), issues probabilistes et cinématique des produits ; données nucléaires dans `reactor.py` |
| `logic/fusionlab/lawson.py` | Le plasma : ⟨σv⟩ maxwellien, bremsstrahlung, critère de Lawson, tirage de l'énergie d'un choc |
| `sim/app/.../fusion/ReactorView.java` | Laboratoire de fusion : la carte, les réglages, le bilan ; champs dans `FusionField`, rendu (carte ou au plus près) dans `FusionRenderer` |
| `sim/app/.../fusion/ResultView.java` | Le bilan interactif : flottant ou fenêtre détachée ; un élément choisi y amène la vue |
| `sim/app/.../fusion/CollisionTimeline.java` | Les étapes de lecture (temps physique, tunnel, soudure, produits sur la carte) |
| `sim/app/.../fusion/FusionLab.java` | L'ancien laboratoire 3D (plus relié à l'interface) |
| `sim/app/.../common/LabCamera.java` | La caméra à inertie et la projection en perspective |
| `logic/fusionlab/star.py` | Le cœur d'étoile : réseau REACLIB, Saha, Fermi-Dirac, énergie, fenêtre de fusions |
| `sim/app/.../fusion/StarView.java` | L'écran du cœur d'étoile |
| `sim/app/.../matter/MoleculeCanvas.java` | La molécule : densité, Δρ, E(R), diagramme d'orbitales |
| `sim/app/.../matter/MatterCanvas.java` | La boîte d'atomes en 3D, piston, mesures |
| `logic/atomic/atom.py` | Évaluation de l'atome : couches, niveaux, orbitale, noyau |
| `logic/nuclear/halflife.py` | Estimations pour les noyaux jamais observés |
| `sim/app/.../atom/BohrModel.java` | La vue de Bohr : anneaux inclinés, électrons qui arrivent, sautent et partent, cœur de gaz noble |
| `sim/app/.../atom/SimCanvas.java` | La carte : échelles, événements, loupe, plongée, gouffre, bandeau |
| `sim/app/.../atom/OrbitalCloud.java` | Le nuage 3D : |ψ|² projeté exactement, tranches de plongée, mesures fugaces |
| `sim/app/.../atom/NuclearDrop.java` | Le noyau goutte : vibrations de Bohr–Mottelson, échanges de pions p ↔ n |
| `sim/app/.../atom/Camera3D.java` | La caméra des vues 3D de l'atome et du noyau |
| `sim/app/.../app/LabLoader.java` | L'écran d'attente quand on ouvre ou quitte un laboratoire ; au démarrage, le cadre « Soutenir Genesis » |
| `sim/app/.../app/UpdateChecker.java` | La dernière version sur GitHub, comparaison des numéros, téléchargement vérifié (SHA-256) |
| `sim/app/.../app/UpdateCard.java` | La carte « nouvelle version disponible » |
| `sim/app/.../app/I18n.java` | La traduction à l'affichage (source française → anglais) : libellés relus, phrases exactes, gabarits `{0}`, segments |
| `sim/app/.../atom/Photon3DLayer.java` | Le photon en JavaFX 3D (fuseau, rubans E/B maillés, lumière), posé sur la scène |
| `sim/app/.../app/TimelineBar.java` | La timeline : chaîne de désintégration en ligne de métro, vitesse du temps en clair |
| `sim/app/.../atom/PhotonPacket.java` | Le photon : paquet d'onde 3D (E, B, enveloppe) construit à partir de λ |
| `sim/app/.../atom/LightPanel.java` | Niveaux d'énergie et spectre (réglage du laser) |
| `sim/app/.../atom/NucleusPanel.java` | Vallée de stabilité et données du noyau |
| `sim/app/.../app/EventJournal.java` | Le journal : logs par catégorie, filtres, vue compacte sous Genesis |
