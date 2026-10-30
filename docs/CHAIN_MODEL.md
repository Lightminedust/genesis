# La réaction en chaîne — masse critique, k_eff, excursion

Le laboratoire (menu **Laboratoires → ☢ Réaction en chaîne · masse critique**) pose une sphère de matière
fissile, avec ou sans réflecteur, et calcule ce que font ses neutrons. Rien n'est tabulé à la main : les sections
efficaces viennent d'ENDF/B-VIII.0, les masses d'AME2020, et chaque nombre affiché sort d'un calcul.

Code : `logic/chainlab/` (moteur), `sim/app/src/main/java/org/genesis/chain/` (client).

## La scène

Le simulateur n'a pas de panneau de réglages : tout se fait dans la scène 3D, et l'état se lit sans un mot.

| Ce qu'on voit | Ce que c'est |
|---|---|
| un volume de noyaux | un point pour 10²³ noyaux réels (10²⁴, 10²⁵… au-delà de 1 600 points). Comprimer garde les points et les resserre ; ajouter de la matière en ajoute. |
| l'anneau ambre en pointillé | le rayon critique calculé, à la densité du moment : quand la surface l'atteint, k_eff = 1 |
| l'aura | k_eff en couleur : bleu froid (sous-critique), ambre (critique), rouge qui palpite (sur-critique), gris (aucune fission) |
| un neutron et sa trace | une trace calculée par le moteur, vitesse réelle au ralenti choisi ; couleur : rapide, intermédiaire, thermique |
| une étincelle sur un noyau | une diffusion (le choc tombe sur le noyau le plus proche du volume) |
| une onde violette | une capture radiative (γ) |
| un éclair et deux fragments | une fission ; le noyau disparaît du volume. La direction des deux fragments est tirée pour l'image : le moteur ne la calcule pas |
| la tempête d'étincelles | l'excursion : d'autant plus d'étincelles que log₁₀ N(t) est grand, nées selon le profil de flux calculé |
| la couleur de la matière | le corps noir à la température calculée ; la sphère gonfle puis se disperse quand le gaz pousse |

Les gestes : un clic dans la sphère y fait naître un neutron (« Rafale » : dix) ; glisser le bord comprime à masse
constante (Maj : ajoute de la matière à densité constante) ; glisser un jeton (Be, C, U) sur la sphère pose un
réflecteur, glisser son bord en change l'épaisseur ; un clic sur un neutron suit sa lignée ; le curseur « ralenti »
règle combien de nanosecondes défilent par seconde. « ⓘ Les chiffres » ouvre le tableau de bord et N(t).

Un seul neutron dans une sphère à peine sur-critique s'éteint presque toujours : la probabilité qu'il allume une
chaîne sans fin (processus de branchement, loi de Terrell) n'est que de 2 % à k = 1,02. La scène le montre tel quel ;
l'excursion ne prend le relais que si la chaîne tirée a tenu jusqu'au bout de l'échantillon.

Le fer 56 et le plomb 208 sont dans la bibliothèque : sans section efficace de fission, k = 0 ; un neutron injecté
est diffusé, capturé ou s'échappe (calcul à source fixe), et rien ne le remplace, quelle que soit la masse.

## Les données

`tools/build_chain_groups.py` lit les fichiers HDF5 d'OpenMC (ENDF/B-VIII.0 traité par NJOY à 294 K, dépôt
`openmc-data-storage/ENDF-B-VIII.0-NNDC`) et écrit `chainlab/data/groups.npz` (220 Ko). Le moteur ne lit que ce
fichier ; h5py n'est nécessaire que pour le reconstruire.

Seize noyaux : U-233, U-234, U-235, U-238, Np-237, Pu-239, Pu-240, Pu-241, Am-241, Th-232, Be-9, C-12, Ga-69,
Ga-71, Fe-56, Pb-208. Pour chacun, en 44 groupes d'énergie (20 MeV → 10⁻⁵ eV) :

| Grandeur | Comment |
|---|---|
| σ_t, σ_élastique, σ_inélastique, σ_f, νσ_f, σ_capture | moyennes pondérées par W(E)/(σ_t(E) + σ₀), pour 10 sections de dilution σ₀ de 1 b à 10¹⁰ b (autoprotection de Bondarenko) |
| matrices de transfert g → g' | angles et énergies de sortie tirés dans les distributions évaluées : cinématique à deux corps, niveaux discrets, continuum, (n,2n), agitation thermique de la cible (gaz libre à 294 K) |
| μ̄ dans le laboratoire | premier moment des mêmes tirages, pour la correction de transport |
| χ (spectre de fission) | par groupe incident, neutrons prompts et retardés |
| ν prompt, ν retardé par précurseur, λ_i | les produits retardés d'ENDF (6 groupes pour U-235) |
| partage de l'énergie de fission | ENDF MT 458 : fragments, neutrons, γ, β, neutrinos |

W(E) est le spectre de pondération habituel : maxwellienne à 0,0253 eV, 1/E, puis spectre de fission.

## Le transport

La sphère est découpée en coquilles (16 dans le cœur, de même volume ; 14 dans le réflecteur). La méthode des
**probabilités de première collision** donne, pour chaque groupe, la probabilité P_ij qu'un neutron né uniformément
dans la coquille i fasse son premier choc dans la coquille j. Pour une sphère toutes les directions se valent : on
intègre le long de cordes parallèles, l'atténuation e^(−τ) étant exacte le long de chacune ; seule l'intégrale sur
le paramètre d'impact est numérique (Gauss-Legendre après un changement de variable). Les probabilités se
conservent à 10⁻¹⁶ et respectent la réciprocité Σ_i V_i P_ij = Σ_j V_j P_ji.

Le bilan, groupe par groupe :

    Σ_t,g,j φ_g,j V_j = Σ_i P_g,ij V_i [ Σ_g' Σ_s,g'→g,i φ_g',i + χ_g,i (νΣ_f φ)_i / k ]

et k_eff est la valeur propre trouvée par itération de puissance. La diffusion est rendue isotrope par la
**correction de transport** (Σ_tr = Σ_t − μ̄Σ_s). Le flux adjoint (l'importance d'un neutron) est calculé de la même
façon ; il pondère le temps de génération des neutrons prompts Λ.

Pour un mélange, chaque noyau voit σ₀ = (Σ des autres noyaux + 1/ℓ̄) / N, où ℓ̄ = 4V/S est la corde moyenne du
morceau (approximation rationnelle de Wigner).

## La validation

| Cas | Référence | Calculé |
|---|---|---|
| Ua-1-0-SP (un groupe, analytique, LA-13511) | r_c = 7,428998 cm | 7,4290 cm (60 coquilles) |
| Godiva, ICSBEP HEU-MET-FAST-001 (R = 8,7407 cm, 18,74 g/cm³) | k = 1,000 | 1,002 |
| Jezebel, PU-MET-FAST-001 (R = 6,3849 cm, 15,61 g/cm³) | k = 1,000 | 1,003 |
| Jezebel-233, U233-MET-FAST-001 (R = 5,9838 cm) | k = 1,000 | 1,003 |
| Flattop-25, HEU-MET-FAST-028 (réflecteur d'uranium naturel) | k = 1,000 | 0,992 |
| Λ de Godiva, de Flattop (Rossi-α mesuré à la criticité) | ≈ 6 ns, ≈ 17 ns | 6,4 ns, 16 ns |
| Masse critique nue : U-235, Pu-239 (α), U-233, Np-237 | ≈ 47–52, 10, 15, 60 kg | 46,4 ; 10,0 ; 15,5 ; 61 kg |

U-238 (k∞ = 0,31), Th-232 (k∞ = 0,06) et l'uranium naturel (k∞ = 0,43) ne deviennent jamais critiques. Pour une
sphère nue comprimée, la masse critique varie comme 1/ρ² (test : × 2 de densité → masse ÷ 4).

`logic/tests/test_chain.py` vérifie tout cela, et le laboratoire recalcule Godiva à son ouverture (« Modèle &
données »).

## La cinétique

ρ = (k − 1)/k, β = Σ β_i (les rendements des précurseurs pondérés par les fissions de chaque noyau), et la période
asymptotique vient de l'équation de Nordheim (inhour) : ρ = ωΛ + Σ β_i ω/(ω + λ_i). Les régimes affichés :

| Régime | Condition | Ce qui se passe |
|---|---|---|
| sous-critique | k < 1 | la chaîne s'éteint ; un neutron source en donne M = 1/(1 − k) |
| critique | k = 1 | la population reste stable |
| sur-critique différé | 1 < k < 1/(1 − β) | croissance au rythme des neutrons retardés (secondes) : un réacteur se pilote ici |
| critique prompt | k ≥ 1/(1 − β) | les neutrons prompts suffisent : N(t) ≈ N₀ e^((k(1−β)−1)t/Λ), en nanosecondes |

La source intrinsèque vient des fissions spontanées : demi-vie et rapport de branchement (ICRP-107), multiplicité
ν̄_SF de Holden et Zucker (1985) pour U-238, Pu-238, Pu-240, Pu-242, Cm-244 et Cf-252.

## L'excursion

Au tir, la cinétique ponctuelle (avec ses précurseurs) est intégrée (`scipy.integrate.solve_ivp`, LSODA) depuis un
neutron, avec la contre-réaction :

- l'énergie des fissions (Q prompt d'ENDF MT 458) chauffe le cœur : capacité thermique et chaleurs latentes de
  mendeleev (Dulong-Petit 3R quand la mesure manque), fusion puis ébullition ;
- tant que le métal est condensé, il se dilate (coefficient linéaire du CRC Handbook pour U, Pu, Th) ;
- vaporisé, il pousse : gaz parfait monoatomique et pression de rayonnement aT⁴/3, expansion homologue
  R̈ = 5p/(ρR), énergie interne diminuée du travail fourni ;
- k et Λ viennent du transport recalculé pour la sphère dilatée (rayon R₀·s, densité ρ₀/s³), à 13 facteurs s.

La couleur de la sphère est celle du corps noir à la température calculée (Planck × CIE 1931 ; luminosité sur une
échelle logarithmique). L'énergie libérée est donnée en joules, en tonnes de TNT, en masse convertie Δm = E/c², et
en part de Little Boy.

## La cascade

Pour montrer la chaîne, quelques neutrons sont suivis un par un dans la vraie géométrie, avec les mêmes sections
efficaces : vol tiré selon e^(−Σ·d), choc (fission, capture ou diffusion, le groupe d'arrivée tiré dans la matrice de
transfert), ν tiré selon la loi de Terrell (1957, largeur 1,08) autour du ν̄(E) évalué, groupes des nouveaux neutrons
tirés dans χ. Le temps avance de d/v. Au-delà de 36 neutrons par génération, on n'en suit qu'un échantillon et
l'effectif est corrigé du rapport d'échantillonnage.

## Le bac à sable du rendement

Pour n'importe quel noyau pesé :

- la courbe d'Aston (B/A des noyaux stables naturels) et son sommet (Ni-62) ;
- la **fission** : la coupure en deux noyaux pesés qui libère le plus, la coupure symétrique ; la réaction en chaîne
  n'est calculée que si le noyau a une section efficace de fission évaluée (k∞, σ_f, masse critique nue) ;
- la **fusion** du noyau avec lui-même : Q, barrière de Coulomb (potentiel du laboratoire de fusion), nτT minimal de
  Lawson pour les noyaux légers ;
- la jauge Q (rouge : il faut fournir de l'énergie ; or : il en sort) et le bilan des masses avant / après ;
- le tableau U-235 · Fe-56 · Fe-60 · D + T : Q par réaction, énergie par kilogramme, condition. La demi-vie de Fe-60
  est celle de NUBASE (2,62 millions d'années) ; ICRP-107 garde l'ancienne valeur de 1,5.

## Limites

- Diffusion isotrope après correction de transport : l'anisotropie au-delà du premier moment est négligée.
- Graphite et béryllium en gaz libre, sans S(α,β) : les neutrons thermiques du réflecteur sont approchés.
- β n'est pas pondéré par l'importance ; Λ l'est.
- Excursion : inertie du solide (ondes de contrainte), changements de phase du plutonium (α → δ), ionisation et
  épuisement du combustible dans le calcul de k négligés ; réflecteur immobile.
- Seuls les seize noyaux de la bibliothèque ont un k∞ ; ailleurs, le bac à sable le dit.
- La probabilité de divergence suppose un neutron « moyen » (k/ν̄ de chances de faire fissionner), sans
  dépendance spatiale.
