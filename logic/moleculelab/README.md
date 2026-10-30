# moleculelab — le laboratoire moléculaire

| Fichier | Rôle |
|---|---|
| `xtb.py` | GFN1-xTB en numpy : base STO-NG, H⁰, électrostatique auto-cohérente, répulsion, dispersion D3(BJ), liaison halogène ; énergie et gradients analytiques terme par terme. Le cycle auto-cohérent par paliers : Broyden, puis Newton amorti sur la réponse linéaire des charges quand une liaison casse, puis recuit électronique en dernier recours ; la contrainte des anions (multiplicateur de Lagrange : aucune charge sous −2,5 e). |
| `integrals.py` | Recouvrements entre gaussiennes, leurs dérivées et leurs moments dipolaires (Obara–Saika) ; valeurs des orbitales en des points. |
| `analysis.py` | Indices de liaison de Mayer et leur part σ / π, paires d'électrons localisées (Pipek–Mezey, rotations de Jacobi en parallèle) : partage de chaque liaison à deux centres, paires à plusieurs centres (agrégats métalliques, pont B–H–B), deux ions sans paire partagée ; les isotopes proposés (stables et radioactifs d'au moins un an) et leurs masses ; le partage de chaque liaison (densité d'interférence au milieu de l'axe, rapportée à celle d'une liaison simple ordinaire : une liaison rompue n'en a plus, même si son indice reste haut) ; espèces (formules de Hill), dipôles des atomes (intégrales de moment) et de la molécule, hessienne et modes de vibration. |
| `density.py` | La densité ρ (nuages des atomes et recouvrement), la densité de déformation Δρ, les orbitales ψ (par séparation des gaussiennes, sur une grille fine dont la boîte grandit tant que la surface des lobes touche ses faces) et le potentiel électrostatique (convolution de Fourier) sur une grille ; leur codage 16 bits. Le nuage de chaque atome libre vu en transparence (transformée d'Abel), d'où le client tire le halo ; sa surface à 0,002 e/bohr³ et le maximum de son orbitale p ; la densité des paires de chaque liaison le long de son axe (les grains). |
| `lab.py` | La session : établi, isotopes (la masse de chaque atome), relaxation FIRE (masse fictive égale pour tous les atomes), dynamique (Verlet, thermostat CSVR), traction, étincelle, réacteur, vibrations. Tourne dans son propre fil. Chaque instantané porte le bilan d'énergie (les termes des atomes isolés, `atom_terms` : bande, électrostatique, entropie) et, pour chaque paire proche, sa force le long de l'axe et sa force entière (la somme sur les paires refait la force de chaque atome), la part des électrons en recouvrement et en charges (`xtb` : `pair_split`), et la population de recouvrement de Mulliken (`analysis.overlap_populations` : < 0, antiliante). |
| `structures.py` | Les molécules de départ (dont Au₆, le triangle plan D3h, et les molécules du vivant lues dans `data/molecules/`, conformères 3D de PubChem), les mélanges du réacteur, les noms des espèces. |
| `validation.py` | Calculé ↔ mesuré (géométries, vibrations, énergies) ; `python -m moleculelab.validation` l'enregistre dans `data/validation.json`, que les tests revérifient ; le texte de la méthode. |
| `frame.py` | Le laboratoire dans la frame envoyée au client (`MoleculeLabState`). |
| `data/gfn1-xtb.toml` | Les paramètres GFN1-xTB (xtb / tblite, LGPL-3.0). |
| `data/xtb-data.npz` | Développements STO-NG, rayons, C6 de référence de D3 ; produit par `tools/build_xtb_data.py`. |

Le modèle, sa vérification contre dxtb et ses limites : `docs/MOLECULE_MODEL.md`. Côté client :
`sim/app/src/main/java/org/genesis/molecule/`.

Reconstruire les tables (dxtb et ses dépendances installés dans un dossier à part, jamais dans le moteur) :

```bash
venv\Scripts\python tools/build_xtb_data.py <dossier où dxtb est installé>
```
