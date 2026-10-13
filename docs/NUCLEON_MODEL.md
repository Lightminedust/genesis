# Le nucléon de l'intérieur — modèle

Écran **◉ Le nucléon de l'intérieur** (touche **K**). Un proton (uud) ou un neutron (udd) est décrit par trois quarks de
valence reliés par une corde de couleur. On frappe un quark ; le moteur (`logic/quantumlab/nucleon.py`) intègre le mouvement,
décide d'une éventuelle rupture de corde et nomme les hadrons qui en sortent. Le client ne calcule aucune physique : il
projette les échantillons reçus (`NucleonState`, protocole v16) et lit dessus ses tableaux.

## Ce qui est calculé

| Grandeur | Loi | Source |
|---|---|---|
| Masses des quarks | u, d : 336 MeV ; s : 500 MeV (quarks *constituants*) | modèle usuel des quarks constituants |
| Potentiel du baryon | `V = σ·L_Y − (2/3)·α_s·ħc·Σ 1/√(r²+ε²)` | corde en Y + échange de gluons |
| Potentiel du méson | `V = σ·r − (4/3)·α_s·ħc / √(r²+ε²)` | corde droite + échange de gluons |
| Constantes | σ = 0,18 GeV² (≈ 912 MeV/fm), α_s = 0,40, ε = 0,2 fm | spectroscopie / QCD sur réseau |
| Longueur de corde `L_Y` | arbre de Steiner : point de Fermat (construction de Napoléon) ; si un angle ≥ 120°, la jonction est au sommet | exacte, forme fermée |
| Force | `−∇V`, gradient analytique (vérifié contre les différences finies) | test |
| Dynamique | relativiste : `ẋ = p/E`, `ṗ = F`, DOP853, rtol 10⁻⁷ | énergie conservée à ≈ 0,01 MeV |
| État de départ | gaussienne variationnelle `exp(−(ρ²+λ²)/2b²)` ; `b` minimise `⟨T⟩+⟨V⟩` (Monte-Carlo) ; positions et impulsions tirées dans la fonction de Wigner exacte, P total = 0 | b ≈ 0,50 fm |
| Le coup | impulsion Q (0–6 GeV/c) ajoutée à un quark : `ν`, `Q² = q² − ν²`, `W = √((M+ν)² − q²)` | diffusion profondément inélastique |
| Rupture de corde | taux `dH/dt = Σ Γ₀·w·max(0, L − d_min)`, `w = Σ_saveurs exp(−π m²/σ)` ; événement quand `H` atteint un tirage Exp(1) | Schwinger / modèle de Lund |
| Paire créée | saveur ∝ `exp(−π m²/σ)` parmi celles que l'énergie permet ; séparation `d` résolue pour que l'énergie totale soit **exactement** conservée | calcul |
| Hadrons produits | nommés par leur contenu en quarks (p, n, Δ, Σ, Λ, Ξ, Ω, π, K, φ…) avec leurs masses mesurées (PDG) | table |

Conservations vérifiées par les tests (`logic/tests/test_nucleon.py`) et relues à l'écran sur les quarks eux-mêmes : énergie
(< 1 MeV), charge, nombre baryonique, neutralité de couleur de chaque hadron (rouge+vert+bleu, ou couleur+anticouleur).

## Ce qu'on voit

* Les trois quarks (couleur rouge/vert/bleu, saveur, charge 2/3 ou −1/3), le tube de flux en Y avec sa jonction et la
  longueur de chaque branche ; les traces des derniers pas de chaque quark (positions calculées).
* La flèche du coup ; on peut aussi **saisir un quark au début du film et tirer** : la longueur est Q, la direction celle
  du coup (le moteur refait le calcul avec un nouveau tirage de Fermi).
* L'anneau d'une rupture, avec la saveur de la paire, `σ·d` (énergie du champ changée en masse) et les hadrons obtenus.
* Le tableau : partage de l'énergie (cinétique / corde / masses), probabilité de rupture accumulée, Q², ν, W, énergie
  d'échange de gluons ; les hadrons présents, et les trois conservations.
* La bande « énergie dans le temps » : énergie cinétique hors masses et énergie de corde `σL` ; elles s'échangent à
  énergie totale constante (le quark frappé « yo-yo » : la corde l'arrête puis le renvoie), avec les ruptures repérées.

## Limites (affichées dans l'écran)

* **Γ₀ = 4,4 fm⁻² est calibré**, pas calculé : il place la première rupture vers 2 fm de corde. Le facteur
  `exp(−π m²/σ)` (suppression de l'étrangeté, ≈ 0,1) est, lui, calculé.
* Les **spins ne sont pas dynamiques** (pas d'interaction hyperfine) : les masses N–Δ ne sortent pas du modèle.
* Un quark constituant ne donne pas la masse du pion : les hadrons **portent leurs masses mesurées** (le modèle ne les
  calcule pas).
* Pas d'impulsion transverse à la rupture ; la paire naît au repos ; classique à partir d'un échantillon de Wigner (pas
  d'évolution quantique).
* Une corde ne se rompt que si le quark frappé en étire assez (≈ E_cin/σ) : sous ≈ 1,5 GeV/c il revient sans rupture.
* La variable de Bjorken x n'est **pas affichée** : ce modèle donne ≈ 0,55 au lieu de ≈ 0,36 attendu (il ne respecte pas la
  règle de somme), la montrer serait trompeur.
* Ce n'est ni QCD sur réseau ni un générateur d'événements (PYTHIA) : un modèle à quelques corps, explicite et vérifiable.

## Tests

`logic/tests/test_nucleon.py` (physique), `logic/tests/test_nucleon_engine.py` (remplissage du protocole, refus d'un coup
impossible), `NucleonViewTest` (lecture des conservations, rendu). Aperçu hors ligne : `gradlew nucleonPreview
-Pframe=<fichier.pb> -Pout=<dossier> -Pat="0 12 40"`.

## Les gluons : ressorts de la corde

Dans la vue du nucléon, le champ de couleur entre quarks est dessiné comme un ressort de gluons posé sur le tube de flux : une
spire tous les 0,14 fm de corde, si bien qu'étirer la corde ajoute des spires. Le ressort est un **symbole** (celui des diagrammes de
Feynman) ; la physique de la corde — énergie σ·L, longueur de chaque branche — est dans les tableaux. Les hadrons sont des bulles
translucides (baryon bleu, méson turquoise). Le tube est en Y (jonction de Steiner) et non en triangle : c'est la forme que donne
la corde de couleur à grande séparation (QCD sur réseau), le triangle (« loi Δ ») n'en étant qu'une approximation proche de l'équilibre.

# La mer de partons (écran « Mer de partons », depuis le nucléon)

Un nucléon sondé à la résolution Q² ne montre pas seulement ses trois quarks de valence. `logic/quantumlab/partons.py` calcule
l'évolution des distributions x·f(x, Q²) :

| Élément | Loi |
|---|---|
| Évolution | DGLAP à l'ordre dominant : ∂f/∂ln Q² = α_s/2π · P⊗f, noyaux P_qq, P_qg, P_gq, P_gg exacts (parties « plus » incluses), nf = 3 |
| Couplage | α_s à une boucle, Λ = 0,204 GeV |
| Entrée | Q₀² = 0,26 GeV² : valence x·u_v ∝ x^1,0 (1−x)^3, x·d_v ∝ x^1,0 (1−x)^4 normalisées à 2 u et 1 d ; gluon qui prend le reste de l'impulsion ; **mer nulle** |
| Mer | engendrée par g → q q̄ pendant l'évolution (rien n'est écrit à la main) |
| Sonde | photon virtuel de longueur d'onde λ = ħc/Q, tracé à l'échelle de la sphère (rayon quadratique moyen 0,84 fm) |

Vérifié par les tests (`logic/tests/test_partons.py`) : impulsion totale = 1 à moins de 0,6 % de Q² = 0,26 à 1000 GeV², nombres de
valence 2,000 (u) et 1,000 (d), la mer croît avec Q², la valence cède son impulsion aux gluons, α_s décroît.

**Population et fluctuations** (`logic/quantumlab/parton_cloud.py`). À (Q², x_min) le moteur tire la population : 3 quarks de valence, des
paires de mer par saveur, des gluons, en nombres ∫ f dx (x > x_min), avec des couleurs de somme nulle. Elle fluctue par six
processus — g ↔ q q̄ (création et **annihilation**), g ↔ g g (le gluon est coloré, il se scinde lui-même), q ↔ q g — avec :

* la fraction d'impulsion z des daughters tirée dans la fonction de scission DGLAP (P_qg, P_gg, P_gq), bornée par x_min ;
* un taux de scission α_s/2π · ∫P dz / τ, τ = ħc/Q (durée d'une fluctuation, relation d'incertitude) ;
* des taux de fusion fixés par l'équilibre détaillé avec la population de départ, sur les fractions de paires compatibles en couleur ;
* à **chaque** événement : impulsion Σx, charge, nombre de chaque saveur et couleur (somme des poids SU(3)) strictement conservés
  (écarts relus à l'écran : ≈ 10⁻¹⁷), le nombre net de quarks (2 u, 1 d) restant constant tout le film.

### Limites (affichées dans l'écran)

* Ordre dominant seulement. Avec une entrée de valence + gluon, la mer ne représente que ≈ 10 % de l'impulsion à 10 GeV²
  (mesures : ≈ 20 %) et les gluons ≈ 53 % (≈ 45 %) : l'écart est la mer non perturbative que cette entrée n'a pas.
  Les paramètres d'entrée (a = 1,0) sont réglés pour que la valence soit ≈ 37 % à 10 GeV².
* Les **positions** des partons sont illustratives (les PDF ne donnent pas de position ; il faudrait des GPD). Nombres, saveurs,
  couleurs, x et instants viennent du calcul.
* Les populations dérivent de 10–25 % sur un film long à très petit x_min (l'équilibre détaillé est de champ moyen).
* Pas de masse de quark dans les noyaux (nf = 3 sans seuil), pas de spin ni d'hélicité.

# Entre les nucléons : les pions (écran du noyau)

Dans le noyau, la force forte résiduelle entre nucléons est montrée comme un
échange de pions virtuels (`NuclearDrop`, voir le README, « L'atome en 3D »).
Chaque échange est un tube de flux entre deux voisins, parcouru par une perle :
le pion, avec son contenu en quarks (π⁺ = u d̄, π⁻ = d ū, π⁰ = (uū − dd̄)/√2).
Portée ħ/(m_π c) = 1,41 fm, durée ħ/(m_π c²) = 4,7·10⁻²⁴ s, voisin choisi selon
Yukawa. Un échange chargé change l'identité des deux nucléons (p → n + π⁺,
puis n + π⁺ → p), sans jamais changer la charge totale. C'est une image, pas
un calcul de QCD : le nombre d'échanges en vol (≈ A/4, au plus 6) est un choix
d'affichage ; les règles de charge, la portée et le rapport 2:1 entre échanges
chargés et neutres (couplage √2) sont physiques.
