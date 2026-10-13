# Genesis — Simulateur atomique

Un atome qui vit selon les règles de la physique. Le moteur fait tourner le
temps ; à chaque instant il applique les règles, et ce qui arrive en découle :
un électron excité redescend en émettant un photon, un photon est absorbé ou
traverse, un noyau instable se désintègre. Rien n'est scénarisé : chaque
événement arrive au moment tiré par la loi physique, avec le calcul qui le
justifie.

Licence d'utilisation : [EULA.txt](EULA.txt).

```
JavaFX (sim/)  --- gRPC bidirectionnel --->  Python (logic/)  ---> MongoDB
  dessine les événements   genesis.proto v25   horloge + règles     table périodique
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

## Laboratoire subatomique

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

## Ce qu'on peut faire

Au lancement, le simulateur charge un atome d'uranium 238 et ouvre l'onglet Orbitale (la forme du nuage de valence, avant le spectre). L'écran de l'atome tient en trois bandes :

- **La barre du haut** :
  - l'élément (clic : la table périodique, qui se referme dès qu'un élément est choisi, ou avec Échap) ;
  - trois compteurs − p⁺ + · − n + · − e⁻ + ;
  - la lumière : la longueur d'onde (clic : un petit réglage, avec la flamme) et « Tirer un photon » ;
  - à droite, le menu **Laboratoires** (fusion, laboratoire subatomique, monde subatomique), masqué tant qu'on ne l'ouvre pas. Dès qu'on en choisit un, un **écran d'attente** (`LabLoader`) dit ce que le moteur prépare et le temps écoulé. Il s'efface quand la frame confirme le laboratoire, et dit pourquoi si le moteur ne répond pas (obsolète, injoignable). Le retour à l'atome a la même attente ;
  - un point de couleur qui donne l'état de la connexion au survol.
- **La scène**, avec sa barre de vue :
  - l'échelle (Atome · Noyau · Nucléon · Gouffre) ;
  - ce que fait le glisser (⟳ Tourner · ✥ Déplacer ; clic droit ou Maj déplace toujours) ;
  - ⌖ Recentrer, et des vues toutes faites (face, dessus, côté, trois-quarts) ;
  - la rotation propre, le zoom ;
  - un **double-clic** centre la vue sur le point choisi et s'en approche.

  Le panneau des couches se replie en une pastille, et une pastille ⓘ donne la légende au survol.
- **La timeline**, en bas : ▶/⏸, « Événement suivant », « Pas à pas », et la **vitesse du temps** en clair (« 1 s réelle = 1 milliard d'années simulées »). Dessous, ce que devient l'atome, comme une ligne de métro :
  - **Passé** : les noyaux déjà vécus, grisés, avec leur durée réelle.
  - **Noyau actuel** : son anneau donne la probabilité qu'il se soit déjà désintégré, P = 1 − e^(−t/τ).
  - **Avenir** : les stations jusqu'au noyau stable, avec leur voie (α, β⁻…), leur demi-vie et le temps moyen pour y arriver (U-238 → Th-234 → … → Pb-206).
  - **Interactions** : un clic sur une station avance le temps jusqu'à elle, événement par événement (les désintégrations restent tirées au hasard). Le survol d'un rail explique la voie.
  - **En dessous** : la prochaine étape, et ce qu'elle représente en temps réel à la vitesse choisie.

La chaîne vient du moteur (`Session.decay_chain`, voie dominante à chaque pas, données ICRP-107 / NUBASE2020 ; contrat v25).

| Groupe | Action | Clavier |
|---|---|---|
| CONSTRUIRE | ajouter / retirer proton, neutron, électron · table périodique · vider | `P` `N` `E` (Maj : retirer) · `T` · `R` |
| LUMIÈRE | régler le laser (barre du haut, ou clic sur une raie) · tirer un photon · flamme | `L` · `F` |
| TEMPS | pause · vitesse · prochain événement · pas à pas · aller jusqu'à une station (clic) | `Espace` · `[` `]` · `→` · `A` |
| VUE | atome, noyau, nucléon, gouffre · zoom molette · glisser : tourner (ou déplacer) · double-clic : centrer | `1` `2` `3` `G` · `+` `−` |
| LABORATOIRES | menu Laboratoires : fusion, subatomique, monde subatomique | `U` · `S` · `K` |

Les échelles Matière et Molécule sont désactivées dans l'interface
(`App.ADVANCED_SCALES = false`) ; leur code et leurs tests sont conservés.

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
probabilité qu'elle avait à ce tir.

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
du moteur dans `artifacts/frames` ; `gradlew dashboardPreview` rend l'écran entier (barres, scène, timeline) et une désintégration réelle de l'uranium dans `artifacts/dashboard` ; `gradlew atomPreview` les rend dans
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
- **Les panneaux** selon l'échelle : couches et spins dans l'ordre de
  Klechkowski (atome) ; forces de Weizsäcker et bilan de masse mesuré
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
  La carte montre toutes les sous-couches en 3D (voir « L'atome en 3D »).
- **Noyau** : vallée de stabilité, demi-vie, probabilité par seconde, voies.
- **Matière** : la boîte en 3D (glisser pour la tourner), les atomes colorés
  selon leur agitation ou leur état, les traînées des atomes de gaz, les
  chocs sur les parois ; les mesures (température, pression, densité, part
  de chaque état), les valeurs réelles pour comparer, la courbe V(r).
- **Journal** : tous les événements, du plus récent au plus ancien.

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
| Genesis (mise de côté, non affichée) | — | `mascot/` |
| Outils et tests | `tools/`, `tests/` | `sim/app/src/test/java/org/genesis/` (mêmes paquets) |

Fichier par fichier :

| Chemin | Rôle |
|---|---|
| `core/genesis.proto` | Le contrat partagé (v25), longueurs d'onde absorbables en double précision |
| `logic/atomic/light_interaction.py` | Profils naturels, faisceau gaussien et continuum hydrogénoïde 1s |
| `logic/simulator/simulation.py` | L'horloge et les règles : la session qui vit |
| `logic/engine.py` | Serveur gRPC : lit les actions, avance le temps, envoie les événements |
| `logic/atomic/hydrogen.py` | Coefficients d'Einstein, durées de vie, absorption (un électron) |
| `logic/nuclear/nuclear_data.py` | Accès aux bases nucléaires (radioactivedecay, mendeleev) |
| `logic/atomic/atomic_data.py` | Ionisations, affinités, raies de résonance, doublets de spin |
| `logic/common/particles.py` | Le zoo de particules : masse, charge, spin… |
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
| `sim/app/.../atom/SimCanvas.java` | La carte : échelles, événements, loupe, plongée, gouffre, bandeau |
| `sim/app/.../atom/OrbitalCloud.java` | Le nuage 3D : |ψ|² projeté exactement, tranches de plongée, mesures fugaces |
| `sim/app/.../atom/NuclearDrop.java` | Le noyau goutte : vibrations de Bohr–Mottelson, échanges de pions p ↔ n |
| `sim/app/.../atom/Camera3D.java` | La caméra des vues 3D de l'atome et du noyau |
| `sim/app/.../app/LabLoader.java` | L'écran d'attente quand on ouvre ou quitte un laboratoire |
| `sim/app/.../atom/Photon3DLayer.java` | Le photon en JavaFX 3D (fuseau, rubans E/B maillés, lumière), posé sur la scène |
| `sim/app/.../app/TimelineBar.java` | La timeline : chaîne de désintégration en ligne de métro, vitesse du temps en clair |
| `sim/app/.../atom/PhotonPacket.java` | Le photon : paquet d'onde 3D (E, B, enveloppe) construit à partir de λ |
| `sim/app/.../atom/LightPanel.java` | Niveaux d'énergie et spectre (réglage du laser) |
| `sim/app/.../atom/NucleusPanel.java` | Vallée de stabilité et données du noyau |
| `sim/app/.../app/EventJournal.java` | Le journal : logs par catégorie, filtres, vue compacte sous Genesis |
