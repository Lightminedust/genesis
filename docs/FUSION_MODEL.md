# Laboratoire de collisions — modèle v13

Le laboratoire (`U`) calcule un nouveau problème initial à chaque tir.
Les fichiers `artifacts/*.pb` servent aux aperçus de développement ; ils ne
pilotent pas le simulateur en fonctionnement.

## Sélection et domaine

Le catalogue installé contient 118 éléments et 3 557 isotopes. Les deux
participants sont choisis indépendamment, avec leur masse, rayon, Z, N,
spin et moment magnétique lorsqu'il est disponible.

Il s'agit de **noyaux entièrement ionisés**, pas de collisions chimiques
d'atomes neutres. La dynamique des électrons liés n'est pas simulée ici.
Les masses tabulées de mendeleev comprennent des valeurs évaluées et des
extrapolations : tabulé ne signifie pas toujours mesuré directement.
Les moments inconnus sont signalés. Les rayons sans mesure sont estimés.

Le faisceau fixe E_cm (0,1 à 2 000 000 keV) et b (0 à 50 fm).
La limite supplémentaire E_cm / A_réduit ≤ 10 MeV écarte le domaine de
fragmentation à haute énergie. Une entrée hors domaine affiche une erreur
et conserve la session.

En mode **Plasma** (`logic/fusionlab/lawson.py`), T, n et τ sont réglables. La
réactivité ⟨σv⟩ est intégrée sur la maxwellienne (Bosch–Hale pour D–T, D–D,
D–³He ; ailleurs la section de capture du moteur). Le bilan compare la
puissance de fusion déposée (part chargée) aux pertes W/τ et au rayonnement
de freinage (avec correction relativiste) : ignition, gain Q, courbes nτT
d'ignition et de Q = 1. L'énergie d'un tir est tirée dans le flux maxwellien
(« Calculer ») ou dans le pic de Gamow σ·E·e^(−E/kT) (« Jusqu'à la fusion »),
avec la rareté de ce choc.

## Dynamique et issue

Le moteur actif est `logic/fusionlab/fusion_dynamics.py`. Il réutilise les données
de `fusion.py` et `reactor.py` ; leurs anciens écrans ne pilotent pas celui-ci.

```
V(r) = V_C(r) - V0 / [1 + exp((r - R) / a)]
F = -dV/dr
v_i = c p_i / sqrt(m_i² c² + p_i²)
dp_i/dt = F_i + q_i v_i × B_i
```

Les noyaux partent de max(150 fm, 12 r_rebroussement, 3b, 4 R_contact) : la
répulsion n'y vaut que ~8 % de E, ils traversent donc la carte lancés avant
de freiner. DOP853 intègre positions et impulsions relativistes. Le potentiel nucléaire
Woods–Saxon de type Akyüz–Winther est phénoménologique ; il ne résout pas
la QCD. Le Coulomb est régularisé au contact. B comprend un champ externe
uniforme, les dipôles régularisés et le champ quasistatique des charges
mobiles. Les orientations des dipôles restent fixes pendant l'approche.
Transferts, couplages collectifs, écrantage électronique et électrodynamique
relativiste complète sont absents. L'erreur d'énergie affichée vérifie
l'intégration numérique de l'approche, pas la précision du modèle nucléaire.

Pour D–T, D–D et D–³He entre 2 et 400 keV, les sections efficaces sont les
ajustements Bosch–Hale. P = σ/(π b_max²) dans un disque avec
b_max = max(1/k, sqrt(σ/(0,5π))). Cette répartition uniforme est une
approximation géométrique, pas une section différentielle mesurée en b.
Les branchements sont pondérés par leurs sections efficaces.

**p + p** n'a pas de noyau lié à deux protons : la seule fusion passe par
l'interaction faible pendant le contact, p + p → d + e⁺ + νₑ (Q = 0,420 MeV).
σ = S(0)/E · e^(−2πη), S(0) = 4,01·10⁻²² keV·b (Adelberger 2011) : environ
2·10⁻²⁶ par choc à 64 keV. Cinématique à trois corps : énergie du positon tirée
dans le spectre β permis p·E·(E₀ − E)² (sans fonction de Fermi), directions
isotropes, recul du deuton ; bilan d'énergie exact (test).

Pour les autres couples, le potentiel centrifuge dépend de la visée.
La capture est estimée par WKB/Hill–Wheeler, et la section par une somme
des transmissions des ondes partielles. Capture, formation d'un composé
et survie d'un isotope stable sont des questions distinctes.

Les produits légers utilisent la cinématique relativiste à deux corps.
La désexcitation est un arbre : chaque noyau excité choisit sa voie selon les
largeurs statistiques Weisskopf (n, p, d, t, ³He, α), γ et la fission de
Bohr–Wheeler (barrière de Cohen–Swiatecki). Une fission partage le noyau à
densité de charge constante (A₁/Z₁ = A/Z, asymétrie tirée), donne aux
fragments l'énergie cinétique de Viola (0,1189 Z²/A^⅓ + 7,3 MeV) et laisse le
reste en excitation ; les fragments évaporent ensuite leurs neutrons.
Chaque désintégration conserve le quadrivecteur (deux corps dans le repère du
parent, puis transformée de Lorentz). Directions isotropes, branchements et
durées (ħ/Γ) sont approchés ; la suite radioactive des résidus n'est pas
suivie. Les masses inconnues sont estimées (Weizsäcker), et signalées.

Au-delà de Z = 118, le système à deux noyaux ne forme pas de noyau composé :
**quasi-fission**. Les noyaux restent en contact 2 à 8 × 10⁻²¹ s en tournant
(moment cinétique de l'entrée), transfèrent une partie de leurs nucléons vers
la symétrie (10 à 40 %), puis se séparent avec l'énergie cinétique de Viola du
système entier ; les fragments chauds évaporent à leur tour.
Aucun nouvel élément stable au-delà du catalogue n'est fabriqué.

**Jusqu'à la fusion** retient la capture pour la montrer (au lieu de la tirer)
et affiche la probabilité réelle de ce tir ; si elle est nulle (visée hors du
disque de Bosch–Hale), la visée est retirée dans le disque, ce qui est dit.
Aucune trajectoire classique sous la barrière n'est inventée : l'onde WKB
est une enveloppe relative, pas une solution temporelle de Schrödinger, et
le passage du point de rebroussement à la sortie du tunnel n'a pas de durée.

**La soudure est calculée.** Après capture, le mouvement radial reprend là où
il redevient classiquement permis : la sortie du tunnel (V(r) = E, sous la
barrière) ou le contact (au-dessus). Le moteur intègre

```
μ r̈ = −dV/dr − γ(r) ṙ,    γ(r) = K (dV_N/dr)²,    K = 4·10⁻²³ s/MeV
```

(friction radiale de Gross & Kalinowski) jusqu'au recouvrement complet ou à
l'arrêt. L'énergie dissipée ∫γṙ² dt devient la chaleur des noyaux (partagée
selon les masses) ; cinétique + potentielle + chaleur = E à 2 keV près
(test). D–T à 64 keV : de 2,7 fm à 0,15 fm en 4·10⁻²² s, 18 MeV dissipés.
Mouvement radial seul (le moment orbital n'est pas suivi dans cette phase),
non relativiste. Pour les noyaux lourds, la répulsion des charges qui se
recouvrent arrête ce calcul près du contact (système dinucléaire) ; la fonte
des deux formes en une n'est pas tracée et la lecture la montre comme telle.
Les étapes suivantes sont décalées de la durée de la soudure.

Les produits libres suivent Lorentz dans B uniforme : ω = qB/(γm).
Neutrons et photons volent droit. Plasma, parois et tokamak ne sont pas simulés.

## Image et lecture

La scène est projetée en perspective depuis des coordonnées 3D et dessinée par
la carte graphique (JavaFX/Prism, lumière additive), **à l'échelle réelle** :
rayons et distances dans le même rapport, sans grossissement. Chaque noyau est
un nuage de densité Woods–Saxon (a = 0,55 fm, intégrée le long du regard)
teinté de sa couleur CPK (nuancée selon l'excès de neutrons), avec ses
nucléons ; leur agitation suit T ≈ √(8E*/A). Le champ magnétique réutilise
`FusionField.magnetic` : dipôles des spins, v×E/c² des charges en mouvement,
et champ externe du tir. De petits repères sont advectés dans le champ 3D
recalculé à chaque image ; ils indiquent la topologie, sans représenter des
particules matérielles ni dessiner des orbites. Paquet d'onde de largeur ħ/p, rides à la longueur de
de Broglie locale ; queue WKB sous la barrière.

**Une vue qui zoome.** Pendant l'approche, la portée du cadre suit
R(r) = max(R_contact, R₀ (max(r, r_f)/r₀)^0,75), avec r_f = 2,5 fois la distance
minimale : la vue zoome tant que les noyaux filent presque librement, puis
s'arrête dans la zone de freinage — sinon le grossissement compenserait le
ralentissement et le noyau paraîtrait glisser à vitesse constante. La répulsion
de Coulomb n'a pas de frottement : elle change l'élan en énergie du champ et la
rend au rebond. Ce freinage se lit à trois choses : la vitesse dans les fiches,
les **échos** (un écho de chaque noyau tous les Δt de temps physique, Δt = 1/30
de l'étape : leur écart est la distance parcourue pendant Δt, il se resserre
quand le noyau freine) et les grains de champ (ci-dessous), qui s'arrêtent au
point neutre. Le tunnel et la soudure sont cadrés au femtomètre. Dès le noyau
composé, la vue se pose sur l'élément central (le plus lourd) — ou sur celui
choisi dans le bilan — avec sa fiche calculée (liaison B/A depuis les masses,
E = kZe/R² en surface, B = μ₀·2μ/(4πR³) au pôle, demi-vie, vitesse, rayon p/qB) ;
les autres produits sont projetés sans étiquette.
Le cadrage a de l'inertie : un changement de cible devient un écart résorbé par
un ressort critique (0,45 s), la vue reculant d'autant que l'écart est grand ;
au-delà de soixante cadres, elle coupe.

**Champ électrique : un halo par noyau.** Sur une grille au quart de la
résolution, pour chaque cellule : le champ E_i de chaque noyau (sphère
uniformément chargée : Z/r² dehors, Z·r/R³ dedans — pas de singularité).
Chaque halo montre l'énergie de son propre champ, ε₀|E_i|²/2, sur quatre
décades sous le maximum commun du couple ; sa teinte conventionnelle identifie
l'élément du catalogue et varie légèrement selon l'isotope, tandis que son
intensité et son opacité suivent la même échelle d'énergie de champ pour tous
les noyaux présents. Chaque pixel prend la teinte du halo qui y
domine ; les halos se superposent sans trou (opacité 1 − Π(1 − 0,92 V_i^1,25)) :
là où le champ total s'annule, chacun garde le sien.

**Potentiel combiné et col.** Sur la même grille : U = eφ = Σ kZᵢe²/rᵢ (sphère
chargée : kZe²(3R² − r²)/2R³ dedans), l'énergie qu'aurait une charge test +e.
Il est peint en couleurs de chaleur (bleu → violet → cramoisi → orange → blanc)
sur 2,2 décades sous le col au contact, U_top = ke²(√Z₁ + √Z₂)²/(R₁ + R₂). Il
n'apparaît que dans la zone du col : poids (2U₂/(U₁+U₂))^1,2 · ((1 − cos θ)/2)²
(deux potentiels comparables, deux champs opposés), et son opacité croît avec
son niveau — discret quand les noyaux sont loin, il s'illumine à mesure qu'ils
s'approchent (le col de deux charges ponctuelles vaut ke²(√Z₁ + √Z₂)²/d). Les
isopotentielles (un quart de décade) sont tracées par carrés marchants dans
cette zone seulement. Le col lui-même — point de selle de U, où E₁ + E₂ = 0,
minimal le long de l'axe et maximal en travers — est trouvé par Newton sur le
champ de toutes les charges (départ : r₁/r₂ = √(Z₁/Z₂)) et chiffré sur la
scène ; la sonde d'Inspecter y donne U, l'écart à la formule ponctuelle, la
barrière du choc V(d) = kZ₁Z₂e²/d et la valeur du col au contact. Ce n'est pas
une barrière « blanche » : la barrière du mouvement relatif reste V(d), au
tableau de bord.

L'éclat de la zone est normalisé au col de son couple (U/U_col, U_col = ke²(√Z₁ + √Z₂)²/d,
écranté) : il vaut 1 sur la crête et entre les noyaux et s'éteint au dehors, si bien
que la zone reste lisible du deutérium à l'oganesson ; sa couleur, elle, compare U
au col au contact et chauffe à mesure que les noyaux s'approchent.

**Le plasma.** Le choc a lieu dans le plasma du panneau Lawson (T, n ; moitié A,
moitié B, pur si A = B ; électrons n_e = Σn_iZ_i). Longueur de Debye, électrons et
ions à la même température : λ_D = √(ε₀kT / e²(n_e + Σn_iZ_i²)) ; quand N_D =
4/3·πλ_D³(n_e + n) < 1 (plasma fortement couplé), l'écran ne descend pas sous le
rayon de la sphère ionique a = (3/4πn)^⅓. Dans la scène, champs et potentiel sont
écrantés (Debye–Hückel : φ ∝ e^(−r/λ)/r, E ∝ e^(−r/λ)(1 + r/λ)/r²), pour la carte,
le col, la sonde et les repères. À 15 keV et 10²⁰ ions/m³, λ_D ≈ 64 µm : à 100 fm,
e^(−r/λ_D) = 1 − 1,6·10⁻⁹ — l'effet est réel mais infime ; il devient sensible aux
densités extrêmes du curseur (10³³ ions/m³, 0,5 keV : quelques pour cent à 100 fm).
La sonde l'affiche. À l'échelle des noyaux, le plasma est vide (l'ion voisin est à
n^(−1/3) ≈ 10⁸ fm ; une période plasma dure 10¹⁰ fois la collision) : on ne
l'y dessine pas. Un médaillon le montre à son échelle propre, une boîte de quatre
longueurs d'écran : ions et électrons aux vitesses de Maxwell–Boltzmann (σ = √(kT/m)
par axe, ramenée sous c), autant de points que la boîte contient de particules
(plafonné, « 1 point ≈ N »). Chaque particule porte la couleur de sa propre énergie
cinétique ½mv² (0,1 keV → 100 keV, du bleu profond au blanc, trois décades) : un
plasma à 1 keV est bleu et engourdi, à 100 keV blanc et furieux ; ions et électrons
y ont la même énergie (équipartition), ce sont leurs vitesses qui diffèrent de
√(m_i/m_e). Chacune traîne derrière elle la distance v·Δt parcourue pendant une pose
Δt : 1/24 s de lecture, raccourcie pour que la plus rapide ne barre pas plus d'un
dixième de la boîte (Δt affiché). L'ion garde la teinte de son isotope, mêlée à
55 % à celle de son énergie : il reste nommable. Une grille et une règle à l'échelle
physique (pas de 1, 2 ou 5 × une puissance de dix) donnent la taille de la boîte ;
elles changent avec la densité. Une onde de Langmuir (ω² = ω_p² + 3k²kT/m_e, ω_p =
√(n_e e²/ε₀m_e), k = 2π/L) déplace les électrons de ξ = (δn/n)/k pour une
perturbation choisie δn/n = 30 % — seule l'amplitude est choisie, et là où ils se
resserrent le nuage s'éclaire. Des photons de freinage sont émis
au hasard (Poisson) au taux P_br·V/kT, P_br étant la puissance calculée par le
moteur ; 1 s de lecture = un tiers de période plasma. Il affiche aussi le facteur de
renforcement de Salpeter f = exp(Z₁Z₂e²/λ_D kT) (écrantage faible ; au-delà de 1, le
couplage Γ) — affiché, non appliqué au taux du moteur.

**Champ magnétique externe.** Le champ du tir, uniforme suivant +z, est tracé par la
convention habituelle : un point là où il sort de l'écran, une croix là où il y entre,
un tiret dans sa direction projetée quand il est de biais (le choix suit B·n̂, n̂ étant
l'axe du regard ; l'opacité aussi). La trame est volontairement légère — marques d'un
à deux pixels, fondues vers les bords, confinées entre l'en-tête et la barre de lecture
— et la valeur du champ est rappelée en toutes lettres dans le coin. Un test mesure
qu'elle reste visible sans charger la scène (entre 0,2 et 2,5 % des pixels). Il n'est pas décoratif : le moteur intègre la force
de Lorentz pendant l'approche et transporte chaque produit sur son orbite relativiste
exacte dans ce champ. Le rayon de Larmor r_L = p/qB figure dans la sonde de chaque
noyau, avec son rapport à la taille du noyau — il vaut des centimètres, soit 10¹³ fois
la scène : la courbure est réelle mais invisible ici, elle ne se lit qu'en laissant les
produits s'éloigner.

**Rupture.** Les produits se lisent au ralenti : t = t₀ + (t₁ − t₀)·u^2,2, de sorte que
la moitié du temps de lecture couvre le huitième du temps physique — les premiers
instants de la séparation, les plus denses, ne défilent plus d'un bloc. Le temps lu
reste exact et l'horloge affiche le ralenti en vigueur. La caméra tient d'abord le
barycentre (immobile, Σp = 0 dans le centre de masse) et recule pour contenir les
fragments (A ≥ 4 ; les particules légères, déjà au bord, n'écartent pas la vue) pendant
les deux tiers de l'étape, puis se referme sur l'élément créé — ou cède plus tôt si les
fragments dépassent vingt-deux rayons, rien ne les freinant dans le vide. Pour voir la
réaction entière d'un coup d'œil, sans la course des produits, il y a la carte.

**Le tableau de bord.** Un instrument plat : fond uni, filets fins, une seule hiérarchie.
De haut en bas : le partage de l'énergie (barre segmentée, légende qui passe à la ligne
au lieu de se chevaucher) ; la mesure principale en grand, sur une jauge graduée ; les
autres mesures, une ligne chacune avec sa jauge fine (turquoise au repos, ambre en charge,
corail à saturation) ; en lecture complète, la **courbe du potentiel V(r)** du couple,
tirée du moteur (Coulomb + Woods–Saxon), avec l'énergie du tir en travers et la position
actuelle — **au survol, la valeur de V à la distance pointée** — puis le tableau des lois ;
enfin les **équivalences** de l'énergie libérée : par réaction et par gramme de combustible,
en joules, en TNT, en bombes d'Hiroshima. Le plasma autour est à la demande (chip ⊙) pour
laisser sa place à la courbe.

**Cadrage de l'approche.** Le cadre se resserre de la distance de départ au point de
rebroussement (ou au contact) en échelle logarithmique, avec une pente bornée
d ln(cadre)/d ln(distance) ≤ 0,8 : il reste toujours plus lent que le rapprochement, donc
les noyaux se rapprochent à l'écran. Sous la barrière, où le rebroussement peut être à des
centaines de fm, le passage par effet tunnel descend ensuite vers le contact (là où le
noyau réapparaît) au lieu de rester au large ; l'entrée dans la soudure est serrée sur le
contact. L'ancien « gel du zoom à 2,5 fois le rebroussement » laissait les noyaux minuscules.

**Deux lectures, une seule physique.** L'en-tête propose « Essentiel » et « Complet ».
La lecture simple ne garde que la loi de l'instant, le partage de l'énergie et trois
mesures ; la lecture complète ajoute les barres, le tableau des lois, les calques
(E, B, ψ, ⊙), la sonde, les légendes et le médaillon du plasma. Le calcul est le même :
seule la quantité montrée change.

**La loi de l'instant, sur la scène.** À chaque étape, la loi qui gouverne est composée
en grand entre les deux noyaux (ou au-dessus d'eux s'ils se touchent) : la fraction
barrée comme on l'écrit, sa valeur, la même formule avec les nombres substitués, et deux
pastilles qui disent sa réponse — « r → 2r » et « F → F/4 » pour Coulomb, « E → 1,2 E »
et le facteur correspondant sur la pénétrabilité, calculé par le facteur de Gamow
lui-même. Approche : F = k|q₁q₂|/r². Tunnel : T = exp(−2∫κ dr). Soudure :
μr̈ = −dV/dr − γ(r)ṙ. Noyau composé : T = √(8E*/A). Produits : Q = Δmc². Le tableau de
bord reprend ces lois en deux colonnes — leur écriture à gauche, leur valeur à droite.

**Carte de la réaction.** Ce n'est pas un écran à part : c'est la scène elle-même, qui
s'enclenche **juste avant la première éjection** (0,6 s de lecture avant) : la vue recule
d'avance sur toute la carte, de sorte que les corps, quand ils partent, sont déjà dans le
cadre — l'écran ne reste jamais vide. Les constantes de la carte (rayon, échelle) sont
tirées de l'état final, donc **rien ne bouge d'échelle pendant la lecture**. Les corps y restent ce qu'ils sont — noyaux de Woods–Saxon,
nucléons, halos de champ, flèches de spin —, l'élément créé devient le centre fixe de
la vue (il ne dérive plus), et **seules les distances sont comprimées**, en échelle
logarithmique, pour que tout tienne dans un regard : r_montré = r₀ + (R − r₀)·
ln(1 + d/r₀)/ln(1 + d_max/r₀). Les **directions**, elles, restent celles du calcul. La
scène affiche le facteur appliqué au plus éloigné, pour qu'on ne s'y trompe pas. Des
liens rattachent chaque corps au centre, parcourus d'une impulsion dans le sens du vol.
Chaque étiquette porte la **vitesse calculée** et la **distance vraie** au centre (v 4,06 %
de c · à 150 nm), puisque l'écran ne peut plus les montrer par le mouvement. Un clic sur
un corps **zoome dessus sans rien déplacer** : le centre reste l'élément créé, la carte
n'est pas recalculée ; un second clic, ou un clic dans le vide, revient à la carte
entière. Deux neutrons se distinguent par leur direction de vol. La sonde y donne la
distance vraie, la vitesse, l'énergie et la direction ; la barre d'échelle est remplacée
par une mention (une barre mentirait sous compression), et le col de potentiel n'est pas
tracé (il ne voudrait rien dire à distances comprimées). Le bilan s'ouvre à la fin de la
lecture, pas au premier produit.

**Traces d'ionisation.** Dans un plasma, un corps chargé arrache des électrons tout au
long de sa route. L'épaisseur et l'éclat de sa trace suivent le pouvoir d'arrêt
dE/dx = 4π n_e Z² (e²/4πε₀)² lnΛ / (m_e v²), lnΛ = ln(λ_D/b_min) et
b_min = max(Ze²/4πε₀m_e v², ħ/2m_e v) : un fragment lourd et lent creuse un sillon
large, un proton rapide un trait fin (Z² contre 1/v²). Un neutron ou un photon n'en
laisse aucune, et dans le vide il n'y a rien à ioniser : aucune trace n'est dessinée.
La trace suit les positions calculées, pas une traînée inventée ; la sonde donne dE/dx
en MeV/mm.

**Repères du champ.** Des points très fins naissent à la surface de chaque noyau
(30 + 14 ln(1+Z), plafonnés à 110) et avancent dans la direction du champ total
3D par RK2 (`FusionField.electric`), avec une mobilité d'affichage
max(10 R/s, 15 % du cadre) × √min(9, E/E_réf), E_réf = le champ du noyau à 2,5
rayons. Le champ d'une charge se déplace avec elle : à chaque image, chaque
repère est entraîné par le déplacement des sources, pondéré par la part de
chacune dans |E|² à l'endroit du repère (près d'un noyau lancé, il le suit ; là
où domine le champ de l'autre, il reste avec lui). Seuls, les repères filent
droit ; face à un autre noyau ils sont déviés et glissent le long de la
rencontre ; au col (E = 0) ils ralentissent. D'autres naissent au heurt, avec une
probabilité ∝ −2E₁·E₂. Ils sont dessinés clairs (la teinte du noyau éclaircie),
lisibles sur leur propre halo. La direction est le champ calculé, l'échelle de
vitesse une convention. Les équations et les informations du modèle sont
accessibles dans Inspecter, sans ligne de texte au bas de la scène.

**Contact nucléaire.** Quand les surfaces diffuses se touchent, le recouvrement
ρ₁·ρ₂ des densités de Woods–Saxon (mi-densité c tirée de ⟨r²⟩ = 3/5c² + 7/5π²a²,
a = 0,55 fm) — l'intégrande du potentiel de convolution par lequel agit la force
forte — luit en or (opacité ∝ ρ₁ρ₂) ; rien n'est dessiné tant qu'elles ne se
touchent pas. Il remplace les anciens « tubes » décoratifs.

**Polarisation de Coulomb.** Pendant l'approche, chaque noyau (goutte liquide)
est déformé par le champ de l'autre : énergie quadrupolaire W = k·Z′e·Q₀/(2d³),
Q₀ = (3/√5π)·Z·R₀²·β₂, contre la raideur C₂ = 4R₀²σ − (3/10π)·Z²e²/R₀ ; à
l'équilibre β₂ = −k·Z′·(3/√5π)·Z·R₀²/(2d³·C₂) < 0 : deux charges qui se repoussent
se compriment l'une l'autre le long de l'axe (marée inversée), R∥ = R(1 + 0,63β₂),
R⊥ = R(1 − 0,32β₂). Tritium face à l'uranium à 13 fm : −0,006 (invisible) ; fer
face au plomb : −0,19 ; le plomb dépasse −0,3, où la valeur est bornée (au-delà,
la réponse n'est plus linéaire). Valeur statique (adiabatique), donc un majorant
pour un choc rapide ; affichée dans la fiche de chaque noyau.
La carte de halos est calculée dans le plan de l'image (profondeurs négligées) ;
les repères électriques et magnétiques utilisent les positions 3D.

**Noyau chaud : goutte liquide** (Bohr–Mottelson). Surface
R(θ) = R[1 + α₂P₂(cos θ) + α₃P₃(cos θ)] ; raideurs
C_λ = (λ−1)(λ+2)R₀²σ − (3/2π)(λ−1)/(2λ+1)·Z²e²/R₀ (σ = a_s/4πr₀², a_s = 17,23 MeV),
masses irrotationnelles B_λ = 3AmR₀²/(4πλ), amplitudes thermiques
⟨α_λ²⟩ = T/C_λ (équipartition, bornées à 0,35), pulsations ħω = ħc√(C/B) montrées
à la vitesse de la lecture (0,4–9 Hz). He-5 à 16,9 MeV bout ; un noyau lourd,
dont la répulsion annule presque la tension de surface, se déforme lentement.

**Rupture.** Avant une fission (0,7 s de lecture), le noyau descend vers la
scission en ovales de Cassini (paramétrisation de Pashkevich) le long de l'axe
des futurs fragments, (a/b)² de 0 à ~1 (col nul), section d'aire conservée ;
chaque nucléon rejoint le lobe de son côté. Avant une émission (0,3 s), la surface
bombe dans la direction calculée de la particule qui sort — une figuration du
passage de la surface. Puis, seulement si un photon γ figure parmi les produits
calculés, un éclat I(r, t) = I₀/(1 + (r/R₀)²)·e^(−t/τ), I₀ ∝ E_γ, R₀ le rayon du
noyau qui se défait (τ = 0,12 s de lecture : une persistance de la vue, pas une
durée physique). Ni anneau de choc, ni traînée, ni tremblement : les produits
partent avec les vitesses calculées, et le champ des produits s'établit avec
eux.
La détection inclut les ruptures à nombre de corps constant (deux noyaux →
deux fragments). À la première rupture et aux fissions, le cadrage reste
centré sur le lieu de rupture au départ, puis rejoint le fragment suivi en
0,6 s de lecture ; il ne compense donc pas
immédiatement le recul. Les vitesses sortantes sont appliquées à l'instant
de naissance des produits donné par le moteur, sans impulsion supplémentaire.

La frontière entre les deux champs n'est pas tracée : elle apparaît comme la
rencontre des deux halos, et la zone du col la chiffre — face à un noyau bien
plus chargé, elle se resserre devant le petit noyau (r₁/r₂ = √(Z₁/Z₂)).

Les repères magnétiques sont atténués quand la tension électrique monte
(facteur 1 − 0,45 V/E). Leur direction est celle de B total au point courant ;
leur vitesse d'affichage est proportionnelle à √min(2, |B|/B_réf).

`CollisionTimeline` découpe la lecture : approche, soudure et rebond en temps
physique linéaire (on voit l'accélération et le freinage) ; tunnel (sans durée)
et fonte des noyaux lourds (non tracée) sans échantillons ; évaporation par
émission ; produits en temps linéaire jusqu'à ce que le plus rapide ait
parcouru 0,85 fois la carte. Dès la désexcitation (ou à la fin d'un rebond),
le bilan interactif s'ouvre (onglets Créé, Rejeté, Lumière, Énergie ; une fiche
par élément, photons compris : λ = hc/E, ν = E/h ; choisir un élément y amène
la vue principale). L'énergie Q = Δm·c² y est donnée en joules et en TNT
(4,184 MJ/kg), par réaction et pour 1 g de combustible (D–T : 3,4·10¹¹ J,
≈ 81 t, ≈ 94 MWh). Pause, curseur et rejeu ne recalculent rien. Nouveaux tirs
lance de nouvelles intégrations.

La synchronisation verticale de Prism est coupée (`-Dprism.vsync=false` dans
`build.gradle`) : avec le bilan détaché, deux fenêtres l'attendraient tour à
tour ; le compositeur de Windows évite déjà les déchirures en mode fenêtré.

Refus : au-delà de 10 MeV par nucléon réduit (fragmentation), ou quand
l'énergie est si basse que le point d'arrêt dépasse 1 nm ou que la probabilité
sous-passe 10⁻³⁰⁸, le moteur refuse le tir avec la barrière et le maximum du
couple ; l'interface le dit en grand et propose l'énergie conseillée
(1,3 × barrière, bornée au domaine). Le serveur rattrape toute exception
d'action : une erreur ne coupe plus la session.

## Vérification et reproduction

Le moteur transmet chaque trajectoire une seule fois. Les messages suivants
portent les réglages, avec `fired=false` par défaut : ce booléen ne signifie
pas que la collision a été annulée. Le client conserve le tir ; les réactifs
d'une chambre prête ou une nouvelle sélection le remplacent. Un même numéro
de tir retransmis ne relance pas la lecture. L'interpolation d'une phase
calculée utilise positions et vitesses (Hermite), avec conversion m/s → fm/s ;
elle ne franchit jamais les discontinuités de réaction.

Depuis `logic` :

```powershell
.\venv\Scripts\python.exe -m unittest tests.test_fusion_dynamics
.\venv\Scripts\python.exe -m tools.export_collision --a 6:12 --b 8:16 --energy 20000 --out ../artifacts/carbon-oxygen.pb
```

Depuis `sim` :

```powershell
gradle test
gradle fusionPreview '-Pframe=../../artifacts/carbon-oxygen.pb' '-Pout=../../artifacts/carbon-oxygen.png' '-Ptime=-1e-21' '-Pwidth=1365' '-Pheight=768'
gradle fusionPlaybackPreview '-Pframe=../../artifacts/carbon-oxygen.pb' '-Pout=../../artifacts/carbon-oxygen-playback'
```

Les aperçus rendent la vraie interface JavaFX à partir d'un calcul Python ;
la lecture enregistre la carte (`0000.png`…) et la vue rapprochée (`c0000.png`…).

Avec le moteur Python démarré, vérification du flux réel et de l'AnimationTimer :

```powershell
gradle fusionLivePreview '-Pa=3007' '-Pb=118294' '-Penergy=40000' '-Pout=../../artifacts/live-li-og'
```

Ce contrôle ouvre sa propre session, reçoit le calcul puis envoie des réglages
pendant sa lecture. Il vérifie la continuité du temps et l'absence de retour à
la chambre vide, et enregistre les images, la frame reçue et `playback.csv`.

## Sources

- [Données de mendeleev](https://mendeleev.readthedocs.io/en/latest/data.html).
- [Guide NuDat des données évaluées](https://www.nndc.bnl.gov/nudat3/guide/).
- [Bosch & Hale (1992)](https://doi.org/10.1088/0029-5515/32/4/I07).
- [IAEA LEXFOR, définitions des réactions](https://www-nds.iaea.org/nrdc/nrdc_doc/iaea-nds-0208-202505.pdf).
- [Capture, formation et survie des éléments lourds](https://arxiv.org/abs/1705.07223).
- [WKB, Richard Fitzpatrick](https://farside.ph.utexas.edu/teaching/qmech/lectures/node49.html).
- Gross & Kalinowski, *Friction model of heavy-ion collisions*, Phys. Rep. 45 (1978) 175.
- Lawson, *Some criteria for a power producing thermonuclear reactor*, Proc. Phys. Soc. B 70 (1957) 6.
