# Le monde subatomique — modèle

Écran **◉ Le monde subatomique** (touche **K**), protocole v18. Trois ateliers, un même style documentaire (fond bleu canard,
hadrons en bulles de verre, quarks lumineux, gluons en ressorts dorés, photon en onde, électron en trait bleu). Le client ne
calcule rien : il dessine ce que le moteur envoie (`NucleonState`, `CollisionState`, `HadronLabState`, `StandardModelState`,
`PartonState`).

## 1. Collision projectile – nucléon (`logic/scattering.py`, `logic/collision_events.py`, `logic/hadronic.py`, `logic/hadron_events.py`)

Un électron d'énergie E frappe un proton ou un neutron au repos et ressort à l'angle θ. Au premier ordre en α il échange un
photon virtuel (Q², ν). La section efficace inclusive est

    d²σ/dΩdE′ = σ_Mott [ W₂(ν, Q²) + 2 W₁(ν, Q²) tan²(θ/2) ],   σ_Mott = α² cos²(θ/2) / (4E² sin⁴(θ/2)).

L'état hadronique a la masse invariante W = √(M² + 2Mν − Q²). Quatre régions sont calculées, et leurs sections efficaces
intégrées donnent la probabilité de chaque cas :

| Cas | Loi | Données |
|---|---|---|
| **Élastique** (W = M) | Rosenbluth : dσ/dΩ = σ_Mott (E′/E)[(G_E² + τG_M²)/(1+τ) + 2τG_M² tan²(θ/2)] | G_E, G_M du proton : paramétrisation de Kelly (PRC 70, 068202, 2004) ; neutron : dipôle et Galster |
| **Résonance Δ(1232)** | σ_T = (π/k²) Γ_γ Γ(W)/((W − M_Δ)² + Γ²/4), Γ(W) = Γ₀ (p*/p*₀)³ (M_Δ/W) ; d²σ/dΩdE′ = Γ_T σ_T | Γ(Δ → Nγ) calculé à partir de G*_M(0) = 3,02 (mesuré) : 0,65 MeV ; pic σ_γp ≈ 0,41 mb (mesuré ≈ 0,5) |
| **Résonances supérieures** (1,4 < W < 2 GeV) | même courbe d'échelle que le profond (dualité de Bloom–Gilman) | — |
| **Inélastique profond** (W > 2 GeV) | F₂ = x Σ e_q² (q + q̄), F₁ = F₂/2x, atténué par Q²/(Q² + m_ρ²) | PDF par DGLAP (`partons.py`) |

Vérifications (tests `test_collision.py`) : G_E(0) = 1, G_M(0) = μ_p ; μ_p G_E/G_M décroît avec Q² (≈ 0,38 à 5,2 GeV², la
tendance des mesures de polarisation du JLab) ; les probabilités font 1 ; à petit Q² le proton reste entier (> 80 %
d'élastique à 1 GeV, 30°), à grand Q² il est cassé (> 90 % de profond à 6 GeV, 40°).

**Un événement**, tiré dans ce spectre :

* *élastique* : le nucléon recule d'un bloc avec l'impulsion q (T = Q²/2M) ; ses quarks gardent leur état fondamental ;
* *Δ* : le nucléon devient un Δ (même charge, spin 3/2), vit ħ/Γ(W) (dilaté par γ), puis se désintègre en N π selon les
  rapports de Clebsch–Gordan d'isospin (Δ⁺ → p π⁰ : 2/3, n π⁺ : 1/3), avec p* de la cinématique à deux corps et un boost exact ;
* *résonances / profond* : le photon frappe un quark de valence, choisi selon e_q² q_v(x, Q²). La corde de couleur
  (`nucleon.py`) s'étire et se rompt. Le modèle reçoit l'impulsion qui redonne exactement W (approximation d'impulsion : on
  retient un tirage de Fermi où le quark peut absorber le photon). Juste après le choc, le système est mis sur sa couche de
  masse (W, q). Ensuite chaque **rupture de corde** est une désintégration exacte à deux corps (`collision_events.split`) :
  le parent (M, P) donne m₁ + m₂ ; dans son repos, les morceaux partent dos à dos avec
  p* = √((M² − (m₁+m₂)²)(M² − (m₁−m₂)²))/2M — l'énergie Q = M − m₁ − m₂ que la corde n'a pas mise en masse —, **le long de
  l'axe de la corde**, avec la seule impulsion transverse de la paire créée par effet tunnel, dP/dp_T² ∝ exp(−πp_T²/σ)
  (Schwinger, ⟨p_T²⟩ = σ/π ≈ (240 MeV/c)², loi tronquée à p_T ≤ p*), puis le boost du parent. Les autres hadrons ne changent
  pas : **Σ E = M + ν et Σ p = q à chaque instant**, pas seulement au bilan. (Avant, un réajustement global refait à chaque
  rupture faisait « sauter » les hadrons spectateurs de 0,1 à 1,3 GeV/c et les morceaux partaient à 60–80° de la corde.)
  L'étape « La corde se rompt » écrit M → m₁ + m₂, Q, p*, p_T, l'angle à la corde et |Σp − P| (≈ 10⁻¹² MeV/c).

Chaque étape (faisceau, photon virtuel, résolution λ = ħc/Q comparée au rayon du proton, cas possibles, loi du cas, rupture
de corde, bilan) s'affiche avec sa formule et ses nombres à l'instant où elle a lieu. Le film ralentit autour du choc.

**La présentation** (v19). La scène est une planche 2D fixe : faisceau de gauche à droite, nucléon au centre, plan de
diffusion = plan de l'écran, cadre centré sur le nucléon d'avant le choc (on voit le recul), qui ne fait que reculer. Le récit est
découpé en étapes nommées (l'électron arrive · il émet un photon virtuel · le photon frappe un quark / le proton absorbe le photon
d'un bloc / un quark retourne son spin · la corde s'étire · la corde casse · les hadrons s'éloignent · bilan), chacune avec un
sous-titre, sa loi et ses nombres ; boutons étape précédente / suivante. Le photon part de l'électron : front d'onde plus large
que le nucléon quand λ = ħc/Q le dépasse (il ne voit pas les quarks), onde serrée vers un seul quark sinon. Une paire naît au
point de rupture pendant un arrêt sur image. Le spectre « ce qui peut arriver » est cliquable : une zone tire un événement de ce
cas (tirage conditionnel, même énergie et même angle ; refusé si sa probabilité est nulle).

Limites : premier ordre en α (un photon) ; fond non résonnant sous 1,4 GeV non modélisé ; chute en Q² de la transition N → Δ
prise dipolaire (la vraie est plus rapide) ; les quarks de mer ne sont pas frappés (leur part, ≈ 5 % à x ≈ 0,2, est affichée) ;
le paramètre d'impact dessiné est illustratif (une onde plane n'en a pas).

## Le rendu (v21) : 3D, membranes, puits de Coulomb, champ de l'électron

**Tout est en 3D** (caméra en perspective, tournable, avec inertie), dans le style des images de référence ; il n'y a plus de
panneau sur la scène de collision : des étiquettes sur les objets, un sous-titre, la loi de l'étape, et les cas possibles en
pastilles cliquables au-dessus du sous-titre (rangée fixe). La caméra regarde le faisceau de trois quarts (lacet −90° + 34°,
tangage 17°) : l'électron arrive en biais vers nous, ce qui montre la profondeur et les lentilles (ci-dessous).

**La membrane des hadrons** est la surface de leur sac (`logic/bag.py`) :

| Grandeur | Calcul |
|---|---|
| rayon de charge | ⟨r²⟩ = −6 dG_E/dQ² à Q² = 0 (Kelly) → 0,863 fm |
| rayon du sac | R = √(5/3)·r = 1,114 fm (sphère uniforme de même rayon quadratique moyen) |
| pression du vide | B = 3·2,04 ħc/(4πR⁴) → B^(1/4) = 148 MeV (le MIT avait ajusté 145 MeV : vérification) |
| hadron de n quarks | R_n = R (n/3)^(1/4) |
| tube de flux | rayon 2λ = 0,46 fm, λ ≈ 0,23 fm (pénétration du champ de couleur, QCD sur réseau, Cardoso & Bicudo 2013) |

La paroi est l'isosurface F = ½ d'un champ F = Σ K(d/R) (noyau de Wyvill) : une sphère du sac au centre de chaque hadron,
une petite sphère par quark, un tube par jambe de corde. Au repos le hadron est rond (la sphère du sac ; un quark ne pousse la paroi que s'il en approche, au-delà de 0,55 R_n) ;
quand la corde s'étire au-delà de 0,6 R_n, le tube l'allonge ;
quand elle casse, les deux sacs se rejoignent par un col qui s'amincit puis se rompt — une division cellulaire, qui sort des
positions calculées. Le raccord en col est un lissage visuel des deux sacs pendant qu'ils se séparent. La membrane palpite
d'autant plus que l'énergie d'excitation calculée E* est grande (amplitude ∝ √(E*/M)) : c'est le seul signe visuel de
l'excitation, à la place du graphique. Éclairage de la paroi : la hauteur de la bulle h = √(1 − e^(−(F/½ − 1))) sature sans
cassure, d'où des normales continues, un reflet spéculaire net (exposant 140) et un liseré de Fresnel ; la grille de calcul
fait un pixel d'écran par case (jusqu'à 640 × 480 cases), lissée au tracé.

**Le puits de Coulomb et l'électron.** La densité de charge du proton est prise exponentielle, ρ ∝ e^(−r/a) avec a = r/√12
(le Fourier d'un facteur de forme dipolaire), d'où U(r) = −αħc/r·[1 − e^(−r/a)(1 + r/2a)] et U(0) = −αħc/2a = −2,9 MeV.
Le puits est dessiné en brume volumique (pas de cercles) dont la densité suit |U(r)|/|U(0)|. La trajectoire d'approche de
l'électron est intégrée dans ce champ (dynamique relativiste), avec un paramètre d'impact b = max(1,75 R, ħc/|q|) : l'électron
passe à côté du sac, il ne le traverse pas. Résultat, affiché : le champ de Coulomb seul ne dévie un électron de 1 GeV que de 0,16° au plus
(il va comme 1/E). Les grands angles mesurés viennent de l'échange d'**un seul photon dur** : c'est un effet quantique, qu'on
ne peut pas obtenir en faisant glisser l'électron sur la pente du puits. Le photon est dessiné en hélice de longueur d'onde
ħc/Q à l'échelle, d'épaisseur croissante avec Q² (rayon ∝ 1 + log₁₀(1 + Q²/0,01 GeV²)).

**L'espace courbé : des lentilles d'Einstein.** Toute énergie courbe l'espace (relativité générale) : un rayon qui passe à la
distance b d'une énergie E est dévié de α = 4GE/(c⁴b), et une lentille ponctuelle a pour rayon d'Einstein θ_E² = (4GE/c⁴)·D. Ce
qu'on voit derrière elle à l'écart θ vient de β = θ − θ_E² θ/|θ|² (équation de la lentille) : le fond est repoussé et étiré en
arcs autour d'elle. On garde cette loi exacte, la même pour tous, et on l'amplifie d'un seul facteur affiché (θ_E = 1,3 fm pour
1 GeV, soit 3·10³⁸ fois la vraie valeur à D = 1 fm, qui serait ~10⁻⁴¹ rad). Chaque corps porte la sienne : l'électron E puis E′,
le photon ν pendant qu'il file, le nucléon M au repos puis M + ν quand il absorbe le photon, ensuite chaque hadron
√((m + E*)² + P²). La somme se conserve : ce que la lentille de l'électron perd à l'émission, celle du photon l'emporte et celle
de la cible le gagne. Un électron de 2 GeV courbe donc plus l'espace que le proton (938 MeV).

Rendu : deux couches. Le fond (dégradés, une nébuleuse fine en bruit fractal fixe à ±17 %, poussières et 1 600 grains fixes dans
l'espace, la brume du puits) est sur un canevas que la carte graphique déforme (`DisplacementMap`, une case tous les 4 px, les
déviations des lentilles s'additionnent) ; les objets et le texte sont sur le canevas de devant, nets (une lentille ne se
déforme pas elle-même). Un dégradé lisse déformé ne se voit pas : la texture du fond est là pour qu'on voie l'espace se tordre,
comme les galaxies d'arrière-plan d'une vraie lentille ; elle n'est pas de la physique. Le cœur est adouci (s = 0,45 θ_E) et le
déplacement sature en douceur au-delà de ~160 px (au loin, il reste ∝ E) ; on ne lit jamais hors de l'image.

**Pourquoi l'électron dévie.** C'est l'émission du photon : en l'émettant, l'électron lui cède ν et q = k − k′, et ce recul le
dévie (action-réaction). Le champ du proton est fait de tels photons : de loin, il n'en échange que des mous, qui ne dévient
l'électron que de quelques centièmes de degré (la trajectoire classique intégrée le montre) ; un grand angle demande un seul
photon dur, de longueur d'onde λ = ħc/Q.

**Le photon virtuel : un arc électrique** (`SubRender.arc`) qui part de l'électron et file vers sa cible à la vitesse de la
lumière du film (8 fm par étape, la même pour le photon, l'électron diffusé et le front ci-dessous). Ce qui est calculé : sa
maille — les coudes sont espacés de λ = ħc/Q (court = arc serré et nerveux, long = grands coudes) —, son épaisseur
(∝ 1 + log₁₀(1 + Q²/0,01 GeV²)), son éclat et la couleur de son halo selon ν (échelle log, fausse couleur : orangé vers 50 MeV,
doré vers 700 MeV, blanc bleuté vers 10 GeV) ; le cœur est blanc. La direction des coudes, les sous-coudes et le second
filament sont un tirage renouvelé 24 fois par seconde : la forme exacte d'un échange virtuel n'a pas de sens physique, seule son
échelle en a. À l'arrivée, il est absorbé : la cible s'illumine de l'intérieur — le nucléon entier si λ est plus grand que lui
(élastique, Δ), le quark frappé sinon. Ni rayons ni traits : les éclats sont des lueurs douces. L'étiquette γ* porte ν, q, Q² et
λ ; la loi q = k − k′ reste dans la ligne de formules.

**Le front de l'information.** Quand l'électron change brusquement de vitesse, la nouvelle s'éloigne du point d'échange à c,
sur une sphère de rayon c·t (le « coude » des lignes de champ de Purcell) : au-delà, le champ est encore celui de l'ancienne
trajectoire. Rendu : une coquille transparente qui réfracte le fond (lentille annulaire) avec une lueur très douce, sans
contour, d'intensité ∝ |Δβ| = 2 sin(θ/2) et décroissant comme le champ rayonné (∝ 1/r). L'électron diffusé, qui va à c, reste
sur elle. À l'arrivée du photon, un éclat additif bref à saturation (largeur croissante avec log ν) illumine la cible de
l'intérieur, puis retombe en lueur.

**La caméra de suivi.** Avant et pendant l'échange, elle regarde le nucléon ; ensuite elle suit le centre des hadrons (le
nucléon qui recule, ou les fragments qui s'écartent), à une distance qui garde tout le monde dans le cadre, en avançant comme en
reculant. C'est un ressort amorti critique (3,2 rad/s) qui connaît la vitesse de sa cible : un proton qui recule à 0,8 c reste au
centre au lieu de foncer vers l'objectif ; un saut de cible au changement d'étape n'est pas pris pour une vitesse. L'inertie de
la rotation à la souris est plus courte (frottement 3,2).

**L'électron s'efface au loin.** Il est montré près du nucléon et disparaît entre 5 et 9 fm du point d'échange (avec sa
lentille et son étiquette) ; sa trajectoire, elle, reste tracée.

**Fluidité.** La membrane est calculée sur tous les cœurs (lignes en parallèle), avec une seule exponentielle par pixel et les
puissances par élévations au carré ; la carte des lentilles aussi, une case tous les 6 px (la carte graphique interpole). Une
image coûte 5 à 9 ms côté processeur (25 à 30 ms avant).

**Le nuage de pions virtuels.** Autour de chaque nucléon, des paires quark–antiquark (des pions virtuels) naissent et meurent :
deux points colorés, à une distance du bord du sac tirée selon e^(−2r/λ) (densité de Yukawa (e^(−r/λ)/r)², λ = ħ/m_π c = 1,41 fm),
qui vivent ħ/m_π c² ≈ 1,4 fm/c (ralenti comme le film). Seules la portée et la durée sont physiques ; positions et apparitions sont
tirées au hasard.

**La profondeur.** La nébuleuse du fond est une tuile périodique à l'infini : elle glisse quand la caméra tourne (Δx = f·Δlacet),
comme un ciel. Les grains sont répartis sur trois couches (3–6, 6–14 et 14–36 fm, × l'échelle de la vue), plus gros et plus vifs
de près : en tournant, ils défilent à des vitesses différentes (parallaxe). Pas de collimateur dessiné : la source d'un faisceau
est à des mètres, 10¹⁵ fois l'échelle de la scène.

**L'angle tiré par la physique.** Par défaut (bouton « tiré par la physique »), l'angle de diffusion n'est plus choisi au curseur :
il est tiré selon dσ/dΩ · 2π sin θ, tous les cas sommés, entre 5° et 170° (`scattering.sample_angle`, 30 angles calculés à
résolution réduite). La légende donne la médiane et la part au-delà de 30°. Un photon (propagateur 1/Q⁴) favorise énormément les
petits angles : un électron de 2 GeV dévie de 6,6° en médiane, 0,7 % au-delà de 30° ; un W, lourd, beaucoup moins : un neutrino de
3 GeV dévie de 20° en médiane, 30 % au-delà de 30°. Décoché (ou un préréglage), le curseur fixe l'angle et l'on regarde « ce qui
arrive aux tirs qui dévient là ».

**Le coude et l'impulsion.** La trajectoire s'arrondit au point d'échange sur la résolution λ = ħc/Q (bornée 0,08–0,6 fm ; une
courbe de Bézier) : en deçà, la position de l'échange n'est pas définie. Au coude, un éclair bref (le lepton cède ν et q d'un
coup) et une étiquette « −ν MeV cédés au photon · q · dévié de θ », d'où part le front sphérique.

**Les sacs en 3D.** Deux hadrons ne partagent une membrane que s'ils se touchent réellement en 3D (centres à moins de 0,95 (R₁ + R₂)
— une division en cours) ; sinon chacun a sa bulle, et les groupes sont dessinés entiers (membrane, ressorts, quarks) du plus
loin au plus proche. Avant cette correction, la membrane, calculée sur l'image, unissait deux hadrons éloignés en profondeur dès
qu'ils se recouvraient à l'écran.

**L'identité des projectiles.** Chaque lepton porte ce qui le distingue physiquement : sa charge (le signe −/+ à côté du
point ; son aura est son champ), l'antimatière (un liseré pointillé), sa masse (un muon, 207 fois plus lourd que l'électron,
rayonne 40 000 fois moins : sa traînée est plus fine). Un neutrino n'a ni charge, ni champ, ni trace : un cercle creux, sa
trajectoire calculée en pointillés, et son spin — toujours opposé à son mouvement pour ν (hélicité gauche), dans son sens pour
ν̄ (droite) : c'est la seule différence entre eux à l'œil, et la nature n'en a pas fait d'autres. Un hadron projectile est un sac
de quarks (trois pour p, une paire q q̄ pour π), étiqueté « (projectile) ».

**Fluidité.** Chaque membrane dessinée dans une image a sa propre image de travail (une image partagée faisait afficher à chaque
bulle les pixels de la dernière, découpés en rectangles) ; une membrane compte au plus ~110 000 cases ; les étiquettes gardent
leur place d'une image à l'autre ; la vitesse de la cible de la caméra est lissée (0,25 s), une naissance de hadron ne la fait
plus sursauter.

**Le fond.** L'espace sombre de l'écran de fusion (dégradé #0C1830 → #060C18 → #020409), avec la nébuleuse en nuées bleutées et
vides noirs, les étoiles en trois couches de profondeur et la lentille.

**Le recul de l'électron.** Au moment où le photon part, l'électron cède q : une lueur au point d'échange, et son sillage
s'épaissit (×4) à l'endroit du coude puis se calme (constante 0,12 étape). Il repart selon k′ = k − q, donc à l'opposé de ce
qu'emporte le photon, et sa lentille rétrécit de E à E′. Le quark frappé, lui, n'a pas de
flèche : son sillage est le chemin calculé qu'il a parcouru depuis le choc.

Ce qui n'est pas dessiné, parce que ce serait faux : le proton ne « s'agite » pas avant le choc (le puits de Coulomb ne lui
transmet que quelques MeV au plus, et rien de visible), et les quarks ne tremblent pas d'un bruit décoratif — ils bougent
selon la dynamique calculée du nucléon ; la palpitation de la membrane n'apparaît qu'après l'excitation, proportionnelle à E*.

**Le mouvement des hadrons.** Chaque hadron reçoit sa 4-impulsion exacte (Σ E = M + ν, Σ p = q) et son centre avance à
v = p/E à partir de l'endroit où il est né ; ses quarks gardent autour de ce centre le mouvement interne calculé. Ce qu'on voit
s'éloigner et les impulsions écrites sur les étiquettes sont la même chose (test `test_bag.py`).

## Les projectiles

Neuf projectiles, choisis dans la barre de la collision (code envoyé au moteur dans `COLLISION_RUN`, vecteur[1]) :

| Projectile | Échange | Ce qui est calculé | Vérifié contre |
|---|---|---|---|
| e⁻, e⁺ | un photon | masse exacte ; même section efficace (charge au carré ; l'échange de deux photons, ≈ 1 %, est négligé) ; puits attractif pour e⁻, répulsif pour e⁺ | la formule générale redonne Rosenbluth à 10⁻⁶ près |
| μ⁻, μ⁺ | un photon | cinématique exacte Q² = 2(EE′ − kk′ cos θ) − 2m² ; tenseur leptonique contracté exactement : (α²/Q⁴)(k′/k)[W₂(4EE′ − Q²) + 2W₁(Q² − 2m²)] | rejoint l'électron quand E ≫ m (écart 10 % à 0,5 GeV, 0,05 % à 8 GeV) |
| ν_μ, ν̄_μ | un W⁺ ou un W⁻ (courant chargé) | quasi-élastique de Llewellyn Smith (g_A = −1,2754, M_A = 1,03 GeV) ; résonances par dualité ; profond inélastique avec F₂ = 2x(d + s + ū) et xF₃ = 2x(d + s − ū) (ν̄ : u ↔ d), propagateur (M_W²/(M_W² + Q²))² | QE ν n ≈ 1,07·10⁻³⁸ cm² (mesuré 0,9–1,0) ; σ/E à 10 GeV : ν 0,70 et ν̄ 0,29·10⁻³⁸ cm²/GeV (PDG : 0,68 et 0,33) |
| p | force forte | σ_tot de Donnachie–Landshoff ; élastique par le théorème optique ; échange de couleur et deux cordes | pp à 10 GeV/c : σ_tot 42 mb, σ_él 10,6 mb (mesuré 40 et 10) |
| π⁺, π⁻ | force forte | idem, plus la formation du Δ(1232) en Breit–Wigner (Clebsch–Gordan d'isospin) | pic π⁺p 199 mb à 285 MeV/c (mesuré ≈ 200 mb vers 300 MeV/c) ; π⁻p un tiers ; π⁺p 24,5 mb à 10 GeV/c |

**Neutrinos.** Le W change la saveur du quark qu'il touche (d → u pour ν, u → d pour ν̄) : un neutron devient proton
(quasi-élastique), un Δ de charge différente se forme (Δ⁺⁺ pour ν p), ou le quark frappé repart avec sa nouvelle saveur et la
corde se rompt. Le quasi-élastique de ν sur un proton est impossible (il faudrait un état final de charge +2) : sa probabilité
est 0, et l'écran le dit. Le neutrino file droit (aucune charge : ni Coulomb ni force forte) et passe sur le quark touché — le W
a une portée de ħc/M_W = 0,0025 fm. La section efficace totale, intégrée sur tous les angles, donne la probabilité d'interagir
en traversant un nucléon, σ/πr_p² ≈ 5·10⁻¹³ à 3 GeV : l'événement montré est ce cas rare. La masse du muon est gardée dans la
cinématique, pas dans le tenseur leptonique (écart ∼ m_μ²/2ME). Le Δ des neutrinos n'est pas calculé à part (modèle de
Rein–Sehgal non implémenté) : la région des résonances est moyennée par dualité ; un W < 1,4 GeV y forme un Δ, au-dessus le W
frappe un quark.

**Hadrons.** Pas de boson échangé avec un seul quark : les deux sacs de quarks se rencontrent.
* σ_tot = X s^0,0808 + Y s^−0,4525 (Donnachie et Landshoff, 1992) : pp (21,70 ; 56,08), pn (21,70 ; 54,77), π⁺p (13,63 ;
  27,56), π⁻p (13,63 ; 36,02), π⁺n = π⁻p, π⁻n = π⁺p (isospin). Ajusté au-dessus de √s ≈ 6 GeV, extrapolé ici jusqu'à 2 GeV.
* σ_él = σ_tot²/16πB, pente B = B₀ + 0,5 ln s (B₀ = 7 pour pp, 6 pour πN, GeV⁻²) — partie réelle ρ négligée ; −t est tiré dans
  e^(Bt), l'angle passe du centre de masse au laboratoire.
* Formation du Δ par un pion : σ = C (2J+1)/((2s_π+1)(2s_N+1)) (π/k²) Γ_πN Γ / ((W − M_Δ)² + Γ²/4), W = √s. Le Δ et la partie de
  Regge se relaient sur un raccord lisse de 1,3 à 1,7 GeV (forme choisie ; les N* au-dessus sont moyennées par dualité).
* Les probabilités sont intégrées sur tous les angles (un hadron n'a pas un seul angle de diffusion) : le curseur d'angle est
  désactivé, le curseur du faisceau donne l'impulsion (proton dès 1,5 GeV/c, pion dès 0,15 GeV/c pour voir le Δ).
* Les films (`hadron_events.py`) sont vus du **centre de masse** (Σ p = 0, Σ E = √s) : les deux hadrons arrivent l'un vers
  l'autre à ±p*, chacun le mouvement interne ralenti par sa dilatation du temps γ* (la contraction de Lorentz n'est pas
  dessinée). Dans le laboratoire, à 6 GeV/c, tout file vers l'avant à 0,85–0,95 c : la compression relativiste des vitesses
  fait que les morceaux ne s'écartent qu'à 0,01–0,1 c l'un de l'autre, la séparation est invisible. Le bilan rappelle la
  vitesse du centre de masse et les totaux du laboratoire (E + M, p). **Diffraction** : chacun repart intact avec sa 4-impulsion exacte.
  **Formation** : l'antiquark du pion s'annihile avec un quark de même saveur du nucléon (la paire reste dans la liste, hors de
  tout amas, et l'écran ne la dessine plus) ; les trois quarks restants forment un Δ de masse √s, qui vit ħ/Γ et se désintègre.
  **Échange de couleur** : le quark de couleur c du projectile et celui du nucléon changent de partenaires ; deux cordes se
  tendent entre les morceaux qui s'éloignent et se rompent (même dynamique que le nucléon frappé, `Nucleon.evolve`). Les
  4-impulsions finales sont remises exactement à Σ E = E + M et Σ p = p_faisceau.

À l'écran : chaque lepton a sa couleur (e⁻ cyan, e⁺ rose, μ violet, ν blanc à peine visible) et il est **ponctuel** (particule
élémentaire) : un point sursaturé, entouré d'une aura dont le rayon code son énergie (0,08 + 0,05 log₁₀(E/1 MeV) fm — pas une
taille). Hors du plan de netteté (la distance du centre de la scène), lepton et traînée deviennent flous et plus pâles :
**profondeur de champ**. Le W est une **étincelle de contact** violette sur le quark touché, qui s'éteint en une fraction de
seconde (M_W = 80,4 GeV, portée ħc/M_W = 0,0025 fm, durée ħ/M_W c² ≈ 8·10⁻²⁷ s), sans trajet ; pour un hadron, le projectile est un sac de quarks dessiné comme la cible,
chaque hadron a sa lentille, et l'annihilation ou l'échange de couleur sont des lueurs douces au point de contact. Le récit
change avec la famille (approche, contact, ce qui en sort) et le bilan compte la charge du lepton sortant.

## La figure 3D : toute la zone dans une boîte (v24)

L'écran de collision montre l'événement comme une **figure en 3D**, à l'échelle réelle et sans aucune déformation de l'espace
(les lentilles et le front qui réfracte sont coupés pour l'instant — `SubRender.warp`, la loi reste dans le code et ses tests) :

- **La boîte** contient toute la zone de l'événement : tous les quarks à tous les instants (plus une bonne longueur de l'arrivée
  et du départ du lepton), avec une marge ; presque cubique (chaque côté ≥ 60 % du plus grand), même échelle sur les trois axes.
  Comme dans une figure scientifique, seules **les faces du fond** sont dessinées (le sol et les deux parois du côté opposé à
  l'œil) : quand on tourne, ce sont toujours elles qui portent la grille. Graduations en fm sur les arêtes de devant (un nombre
  tous les deux traits), nom des axes (x, y, z = faisceau), flèche vers les valeurs croissantes, plan zéro plus marqué.
- **La caméra** cadre toute la boîte (perspective douce, demi-angle 15°) ; la molette zoome de toute la zone (×1) jusqu'aux
  quarks (×0,03), et plus on zoome, plus la vue glisse du centre de la boîte vers l'action.
- **Les traces** : le centre de chaque hadron à chaque instant ; un hadron né d'une rupture part du dernier point de son parent
  (celui avec qui il partage le plus de quarks) — les traces se ramifient aux ruptures, comme dans une chambre à bulles. Avant le
  contact, rien ne s'exerce : l'arrivée de deux hadrons est une droite, prolongée jusqu'à la paroi par où chacun entre. Le lepton :
  son chemin calculé dans la boîte, puis sa sortie (à β = p′/E′, en temps physique après l'échange) jusqu'à la paroi.
- **Les points du temps** (chronophotographie) : sur chaque trace, la position aux instants k·Δt (Δt = 2 fm/c = 6,7·10⁻²⁴ s en
  général, un pas rond choisi pour ~14 points sur le film). Leur écart est la vitesse : serrés pour un proton lent, espacés pour
  un pion ou un lepton à presque c.
- **Les ombres** : chaque trace est projetée sur le sol et les deux parois (traits pâles) — lire une position en 3D, c'est lire
  ces trois projections sur les grilles. Chaque objet a aussi un trait pointillé jusqu'au sol et une croix à son pied.
- **Les lois angulaires, en diagrammes polaires posés sur le sol** (le centre de la boîte reste vide — un lobe 3D au point de
  rencontre masquait le proton) : la loi calculée d'où sort la direction de l'événement, r(θ) dans le plan horizontal, θ compté
  depuis l'axe (posé à plat), des deux côtés ; en échelle logarithmique, un cercle par décade (1, 10⁻¹, 10⁻²…), des rayons tous
  les 30°, la direction réellement tirée en trait plein, une flèche dans le sens du faisceau :
  - lepton : dσ/dΩ(θ) de 5° à 170°, tous les cas sommés (la table d'où `sample_angle` tire l'angle, en cache), sur ≤ 6 décades
    (Mott chute de 6 décades de 5° à 170° à 1,5 GeV ; un neutrino, ~3) ;
  - deux hadrons, diffraction : dσ/dΩ* ∝ e^(Bt), t = −2p*²(1 − cos θ*) — un pic très étroit vers l'avant ;
  - Δ → N π, sous l'endroit où le Δ se désintègre : **1 + 3cos²θ*** s'il est formé par un pion (m = ±½ le long du faisceau),
    **5 − 3cos²θ*** s'il est formé par un photon (transition M1, m surtout ±3/2 ; E2 ≈ −2,5 % et la part longitudinale
    négligées). La direction du pion est désormais **tirée selon cette loi** (avant : isotrope). Pour un W : non modélisée,
    isotrope, écrit comme tel.
- **Les fronts au sol** : la nouvelle de chaque événement (le choc, chaque rupture) s'éloigne de son point à c — un cercle sur le
  sol, de rayon c·(t − t₀), qui pâlit en s'élargissant.
- **Les comètes** : tout ce qui bouge — le projectile, chaque hadron — a une tête lumineuse et, derrière elle, une traînée qui
  s'amincit et pâlit sur ~9 fm (épaisseur en perspective) : l'effilement vers l'arrière dit d'où l'objet vient et où il va. Le
  chemin complet reste en trait fin, avec ses points du temps.
- **Les panneaux** : plus de pastilles flottantes dans la vue d'ensemble ; chaque étiquette monte sur un poteau vertical depuis
  son objet jusqu'au-dessus de l'action (une hauteur commune, juste au-dessus des hadrons), en petit panneau (barre de couleur,
  titre, une ligne de mesures). (Un cube néon « volume actif » a été essayé puis retiré : il imposait une forme rigide, comme un
  tiroir qui s'ouvre, à ce qui doit être une division de matière.) Un panneau de
  l'événement donne ce que le choc a transféré : ν, Q², W (ou √s pour deux hadrons).
- **Le vol libre** : au dernier instant calculé (26 fm/c), chaque hadron est formé et libre — rien ne s'exerce plus sur lui — :
  il file en ligne droite à sa vitesse p/E (la moyenne des vitesses de ses quarks, que le moteur règle exactement sur sa
  4-impulsion), sa jonction avec lui ; son mouvement interne est figé. Le film continue jusqu'à ce que tous soient écartés d'au
  moins 3,5 fm (6 à 25 fm/c). Avant, l'image s'arrêtait au dernier instant calculé : des hadrons formés tard restaient collés.
- **Les sacs qui se touchent ne font qu'une masse (metaballs)** : la membrane d'un groupe somme les champs de ses hadrons, chacun
  pondéré par sa proximité en 3D — 1 quand leurs quarks les plus proches sont à moins de 1 fm (les halos fondent l'un dans
  l'autre), décroissant jusqu'à 0 à 2,6 fm. Au contact de deux hadrons, une seule masse ; quand ils reculent, le pont qui les
  relie s'amincit en sablier puis se rompt, et les deux bulles se referment. À une rupture de corde, c'est la paire q q̄ qui naît
  dans le tube qui fait le pont. La distance est réelle (3D) : deux hadrons superposés seulement à l'écran ne fusionnent pas.
- **Les ruptures sont à leur place** : le point d'une rupture (calculé dans le modèle) reçoit le décalage d'affichage de son parent
  (centre de masse, recul) — avant, l'éclair de la paire, le front au sol et la loupe pouvaient tomber à plusieurs fm des hadrons.
- **La loupe** (sous le tableau de bord, qui passe en compact s'il manque de place) : un gros plan qui suit ce qui compte — le
  nucléon et l'échange au début, puis la rupture en cours, sinon le hadron le plus excité, et à la fin les deux morceaux de la
  dernière rupture (le plus lourd s'ils sont trop loin l'un de l'autre) — avec son grossissement et une échelle de 1 fm ; son
  ressort connaît la vitesse de sa cible. Elle ne montre le lepton que près du point d'échange. C'est un second dessin du même
  modèle sur sa propre toile (aucun objet partagé entre deux vues).
- Coût : 2 à 8 ms par image (la vue d'ensemble est légère ; la loupe porte les membranes).

- **Les sacs sont des membranes calculées** : la surface F = ½ du champ de Wyvill de leurs quarks, de leur cœur (la jonction des
  cordes) et de leurs tubes de flux (rayon 0,34 fm), projetés à l'écran et extraits en contours (« marching squares »), puis
  rendus comme les noyaux de la fusion (gaz, nuage de densité du sac autour de chaque quark, verre de Fresnel). Une corde tendue
  étire le sac en tube. **À la rupture, rien n'apparaît** : la paire naît à l'intérieur du tube, les deux morceaux restent une
  seule membrane tant que leurs champs se recouvrent, et le col qui les relie s'amincit à mesure qu'ils s'écartent (à leurs
  vitesses calculées) jusqu'à se pincer ; chaque morceau garde la teinte de son hadron. Coût : 2 à 3 ms par image.

## Profondeur, neutrino, ruptures (client et moteur, après v23)

- **Profondeur** : les hadrons ne sont plus dessinés hadron par hadron, mais triés pour toute la scène — l'arrière de chaque sac
  (gaz, nuage) à la profondeur de sa face arrière (centre + R), chaque quark et chaque tube à la sienne, le verre à celle de sa
  face avant (centre − R). Deux hadrons au contact (ils occupent vraiment le même espace) s'entrelacent comme des sphères
  réelles. Avant le choc, le sac reste rond : l'écart d'un quark n'est que la fluctuation de l'état fondamental.
- **Neutrino** : aucune charge, donc aucune courbure — sa trajectoire est droite jusqu'au vertex (testé). Le coude a la taille
  de la portée de l'échange : arrondi sur λ = ħc/Q pour le photon (sans masse, localisé à λ près), **angle vif** pour le W
  (portée ħc/M_W = 0,0025 fm). C'est au vertex seulement que le neutrino, devenu lepton chargé, part dans sa direction.
  Le puits de Coulomb du proton est marqué « sans effet sur le ν » ; l'étiquette du choc dit « μ⁻ créé à θ », pas « ν dévié ».
- **Ruptures** : voir la cinématique exacte à deux corps ci-dessus, et le centre de masse pour les collisions de hadrons.

## Le style de la chambre de collision (client, après v23)

L'écran de collision reprend le style de la fusion (aucun calcul n'a changé, seulement l'affichage) :

- **L'espace** : le dégradé sombre de la chambre de fusion (#0C1830 → #020409), la poussière en trois couches de parallaxe et
  la lentille d'Einstein de chaque particule (calculée, inchangée). Plus de nébuleuse.
- **Les hadrons** sont dessinés comme les noyaux de la fusion : lueur de gaz additive, nuage de densité du mode fondamental
  du sac du MIT (j₀² + j₁²), quarks ombrés, verre à reflet de Fresnel et deux reflets spéculaires. Excité (E* / M), le sac vibre
  comme une goutte ; tiré par une corde courte, il s'allonge en ovale de Cassini ; un quark arraché garde une petite bulle et
  reste relié par le **tube de flux doré** (le même or que le contact de la fusion). La teinte vient de la palette de la fusion
  (le proton a celle de l'hydrogène, le neutron celle du neutron, Δ orange, π turquoise, K violet, hypérons vert).
- **L'en-tête** (en haut à gauche de la scène) : l'étape en capitales cyan, la réaction, la barre d'échelle « fm · échelle
  réelle », l'horloge (t et ralenti ×10ⁿ) et la légende. La scène est décalée sous lui.
- **Le tableau de bord** de la fusion (à droite) : la répartition de l'énergie (E = E′ + ν, ou √s = masses + disponible),
  les cadrans Q², ν, W, λ (ou √s, σ), une barre par cas possible (le cas tiré marqué ▸) et les lois de l'étape, abrégées.
- **La colonne de réglages** : 01 le projectile (neuf boutons et sa fiche), 02 la cible, 03 le tir (énergie, angle, « tiré par
  la physique », préréglages), 04 les cas possibles (cliquables : forcer un cas) ; en bas, « Lancer » et « Autre événement ».
  La barre de lecture est celle de la fusion.

## 2. L'atelier de hadrons (`logic/hadron_lab.py`, `logic/hadron_data.py`, `logic/standard_model.py`)

On pioche des quarks dans le tableau du Modèle Standard, on choisit couleur et antiparticule, et l'on teste.

**L'atelier explique ses règles par l'image (client) :**
- **Les moules** : on choisit d'abord ce qu'on construit — *baryon* (trois emplacements en triangle, reliés au centre comme la
  corde en Y), *méson* (un emplacement « + » pour la matière, « − » pour l'antimatière : un quark posé sur « − » devient
  l'antiquark) ou *libre* (états exotiques, jusqu'à cinq). Les emplacements vides sont des anneaux en pointillés ; pendant qu'on
  glisse une particule, celui qu'elle vise s'allume en vert (un quark) ou en rouge (ce qui n'a pas de couleur).
- **Ce qui n'a pas de couleur est repoussé** : un lepton, un photon, un Z, un W ou le Higgs revient à sa case en rebondissant,
  l'emplacement vibre en rouge, et la raison s'écrit (pas de charge de couleur : l'interaction forte ne le voit pas). Le gluon
  aussi : il porte la couleur, mais c'est la colle — il est déjà dans le sac, ce sont les cordes.
- **La couleur, en synthèse additive** : rouge (1, 0, 0), vert (0, 1, 0), bleu (0, 0, 1) ; un antiquark porte l'anticouleur, la
  complémentaire (antirouge = cyan…). Le sac prend la couleur du mélange, et un disque par quark montre l'addition (rouge + vert =
  jaune, + bleu = blanc ; rouge + cyan = blanc). Le mélange est blanc **si et seulement si** chaque couleur a le même compte net
  (couleurs moins anticouleurs) — c'est exactement la neutralité de couleur du filtre (vérifié sur 4 000 assemblages au hasard
  dans les tests) : faire du blanc n'est pas une image, c'est la règle. Le conseil dit ce qui manque (« jaune : il manque du
  bleu », « l'antiquark devra porter l'antirouge »).
- **L'arithmétique, sous le sac** : la charge, le nombre baryonique et le spin s'écrivent en additions dont chaque terme est une
  pastille partie de son quark (au test, elles s'envolent des quarks vers la ligne, puis le total apparaît) : +2/3 + 2/3 − 1/3
  = +1 e ; +⅓ + ⅓ + ⅓ = 1 (un nombre non entier dit qu'il manque des quarks) ; ↑ +½ ↑ +½ ↓ −½ = J = ½. Les flèches de spin sont
  aussi sur les quarks : une configuration de l'état |J, M = J⟩ sans moment orbital (le nombre de ↓ est (n − 2J)/2) — l'état
  réel en superpose plusieurs, ce qui est écrit.
- **Les cordes, à la main** : on peut saisir un quark posé et tirer. Sa corde (un tube de flux) s'allonge et stocke σ·ΔL
  (σ = 0,9 GeV/fm, la tension des collisions), écrit en direct. Dès que cette énergie paie une paire (2 × 336 MeV pour u ū ou
  d d̄, 2 × 486 MeV pour s s̄ ; u et d à égalité, s supprimé ≈ 0,3 par l'effet tunnel exp(−πm²/σ)), la corde casse : le quark
  arraché part avec le nouvel antiquark (ou quark) dans un méson blanc (π⁺, K⁻…, nommé), et le nouveau quark prend sa place, de
  même couleur — le hadron reste blanc, mais son contenu a changé (arracher un u au proton peut laisser un neutron et un π⁺). On
  n'isole jamais un quark. Lâché avant, le quark revient (retour élastique amorti).
- **Les gluons échangés** : une impulsion court d'un quark à l'autre le long des cordes ; un gluon porte une couleur et une
  anticouleur (« rouge–antivert ») et, en arrivant, échange les couleurs des deux quarks (baryon) ou fait tourner celle du quark
  et de son antiquark (méson). Les couleurs changent sans cesse, le total reste blanc (seulement si l'assemblage est blanc).
- **La mer** : dans le sac, des points fugaces — des paires quark–antiquark (deux points de couleurs complémentaires) et des
  gluons virtuels (dorés) qui naissent et meurent. C'est une illustration (les densités calculées, qui dépendent de l'échelle,
  sont dans l'onglet « mer de partons »). La membrane respire un peu (bords qui fluctuent).
- **Les spins, en anneaux** : chaque quark a un anneau autour de son axe de spin, avec deux repères qui tournent — sens
  trigonométrique vu d'en haut pour +½, l'autre pour −½ ; sans verdict, l'anneau est immobile (le sens n'est pas connu). Si
  J > 0, un anneau du spin total entoure le sac ; si J = 0 (méson pseudoscalaire), il n'y en a pas : les spins se compensent.
  La membrane ne tourne pas sur elle-même : ce serait un moment orbital (L = 0 ici).
- **La masse** (en bas du verdict) : au verdict, le compteur part de la somme des masses des quarks (celles du tableau, venues
  du Higgs : 8,99 MeV pour u u d) et monte jusqu'à la masse du hadron (938 MeV) ; une barre sépare les deux parts — 1 % de
  quarks, 99 % d'interaction forte (le mouvement des quarks confinés et le champ des gluons, devenus masse). Pour le nucléon,
  la décomposition calculée en QCD sur réseau (χQCD, Yang et al., PRL 121, 2018) : énergie des quarks 32 %, champ des gluons
  36 %, masse des quarks 9 % (mer comprise), anomalie de trace 23 %. Pour un hadron lourd (J/ψ…), la part des quarks est grande :
  le compteur le montre aussi.

**Le filtre** : somme des poids SU(3) de couleur nulle (rouge+vert+bleu, ou couleur+anticouleur) ; deux quarks identiques
ne portent pas la même couleur (Pauli) ; nombre de quarks − antiquarks multiple de 3 ; au moins deux quarks ; leptons et
bosons refusés (pas de couleur). La charge est sommée en tiers de e.

**La masse** : quarks constituants + interaction hyperfine de spin (De Rújula–Georgi–Glashow),
H = Σ mᵢ + Σ A/(mᵢmⱼ)^p σᵢ·σⱼ, diagonalisé dans l'espace de spin, restreint aux états symétriques pour les quarks identiques.
Les constantes sont ajustées sur 38 masses mesurées : baryons à 18 MeV en moyenne quadratique (Λ à 1,5 MeV, Λ/Σ⁰ séparés,
Ω⁻ seul en J = 3/2) ; mésons à ≈ 100 MeV (le modèle additif est connu pour être mauvais avec eux). La masse mesurée est
utilisée quand le hadron est connu ; l'écart au modèle est affiché.

**Le destin**, calculé :

| Cas | Calcul |
|---|---|
| proton | stable : plus léger baryon |
| neutron | τ = 2π³ħ/(G_F²\|V_ud\|²(1+3g_A²)mₑ⁵f) = 944 s (f = 1,636 sans correction coulombienne ; mesuré 878 s) |
| π⁰ → γγ | Γ = α²m³/(64π³f_π²) = 7,8 eV, τ = 8,5·10⁻¹⁷ s |
| désintégration forte | voies ouvertes par conservation + seuil ; Γ = Γ_Δ (p*/p*_Δ)³ (M_Δ/M) (calibré sur Δ → Nπ) |
| quark c ou b | modèle du spectateur : Γ = Σ G_F² m⁵ \|V\|² f(ρ)/192π³ par voie (ℓν, q q̄′ ×3 couleurs) → τ_c ≈ 1,8 ps, τ_b ≈ 4,9 ps (mesurés 0,4–1 et 1,5 ps) |
| top | Γ(t → Wb) = G_F m_t³/(8π√2)(1 − r)²(1 + 2r) = 1,48 GeV : τ = 4,4·10⁻²⁵ s, 7 fois moins que ħ/Λ_QCD — jamais de hadron |
| 4–5 quarks | seuil de la meilleure dislocation en deux hadrons ; pas de calcul de liaison |

Une désintégration est tirée et montrée : impulsions de l'espace de phase (2 corps exact, 3 corps par Dalitz), contenu en
quarks de chaque produit, et le W virtuel tracé jusqu'aux produits qu'il fabrique. Tests : chaque désintégration tabulée
conserve charge, nombre baryonique, leptons, et ne change la saveur que si elle est faible ; 336 désintégrations tirées de
hadrons lourds conservent tout ; énergie et impulsion conservées à 10⁻⁶ MeV.

**Le tableau** (17 particules, masses PDG 2024) : chaque fiche est calculée — W : Γ = 2,04 GeV (9 voies ; mesuré 2,09),
Z : Γ = 2,42 GeV (mesuré 2,50), muon : τ = 2,19 μs, tau, top, c, b ; α_s(M_Z) à une boucle.

## 3. La mer de partons

**L'apparence, calculée (client)** : la membrane du nucléon est un verre éclairé pixel par pixel (normales, reflet spéculaire,
Fresnel : on voit une sphère, pas un calque), bosselée de l'intérieur par les partons qui arrivent à la paroi (d'autant plus
que leur fraction d'impulsion x est grande ; les 24 plus fortes poussées), tendue vers les trois quarks de valence, et qui
tremble là où un événement (g → q q̄…) vient d'avoir lieu. Les partons de mer et les gluons sont des étincelles brèves (ce sont
des fluctuations de durée ħ/Q), de taille ∝ x^⅓, colorées (quark : sa couleur, antiquark : son anticouleur, gluon : doré) — le
nuage s'épaissit tout seul quand Q² monte, puisque le moteur en crée plus. Un éclair doré relie un gluon au quark qu'il touche
(à moins de 0,4 fm : l'échange), ses coudes espacés de sa longueur d'onde ħc/E (E ≈ x·M). Chaque événement est un éclair à son
point. La sonde : un faisceau de largeur λ = ħc/Q, couleur selon son énergie, éclat ∝ log Q², onde de période λ à l'échelle
(si elle dépasse 5 px), une tache de rayon λ là où elle touche le nucléon, et ses nombres : Q, λ, f = Qc/h. Tout est peint sur
une seule toile (aucun objet graphique par parton) : 8 à 13 ms par image, positions calculées une fois par image.


Les partons ont une masse nulle ou presque : à l'écran ils filent à la vitesse de la lumière et rebondissent sur la paroi du
nucléon (le « sac » du MIT) ; un parton né d'un autre part de la position de son parent à cet instant. Seule la direction de départ
est tirée (les PDF n'en donnent pas). Gluons en petites boucles dorées, quarks en couleurs pleines, halo additif léger.


Voir [le modèle du nucléon](NUCLEON_MODEL.md) : DGLAP, populations de gluons et de paires, événements conservés, sonde
λ = ħc/Q.

## Tests et aperçus

`logic/test_collision.py`, `test_hadron_lab.py`, `test_partons.py`, `test_nucleon*.py` ; `QuantumLabTest`, `PartonViewTest`.
Aperçu hors ligne : `gradlew labPreview -Pframe=<frame.pb> -Pout=<dossier> -Pat="-1 0.3 4" -Ptab=collision` (ou
`-Ptab=bench:cU`).
