# docs — les modèles physiques

Ces documents expliquent ce que le moteur calcule, d'où viennent les chiffres, et ce qui est approché.

| Document | Sujet | Code du moteur |
|---|---|---|
| `FUSION_MODEL.md` | La collision de deux noyaux : approche, effet tunnel, soudure, issues. | `logic/fusionlab/` |
| `SUBATOMIC_MODEL.md` | Le monde subatomique : collisions, hadrons, Modèle Standard. | `logic/quantumlab/` |
| `NUCLEON_MODEL.md` | Le nucléon de l'intérieur : quarks, corde de couleur, échanges de pions. | `logic/quantumlab/nucleon.py` |
| `CHAIN_MODEL.md` | La réaction en chaîne (écran masqué pour l'instant) : transport des neutrons (ENDF/B-VIII.0), masse critique, cinétique, excursion, bac à sable. | `logic/chainlab/` |
| `MOLECULE_MODEL.md` | Le laboratoire moléculaire : GFN1-xTB, densité électronique, forces terme par terme, dynamique, réacteur, vibrations, calculé ↔ mesuré. | `logic/moleculelab/` |

La vue d'ensemble du projet est dans le `README.md` de la racine.
