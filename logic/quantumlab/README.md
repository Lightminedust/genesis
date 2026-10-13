# quantumlab — le monde subatomique

| Fichier | Rôle |
|---|---|
| `standard_model.py` | Les 17 particules élémentaires et les constantes des calculs. |
| `sm_cards.py` | Les fiches du tableau du Modèle Standard (chaque ligne est calculée ou mesurée). |
| `hadron_data.py` | Les hadrons et leptons connus (PDG) : quarks, masse, durée de vie, désintégrations. |
| `hadron_lab.py` | L'atelier de hadrons : assembler des quarks, vérifier l'objet, calculer sa masse et son destin. |
| `scattering.py` | Un lepton frappe un nucléon : les cas possibles et leurs probabilités. |
| `collision_events.py` | Un événement lepton + nucléon, de bout en bout. |
| `hadronic.py` | Un hadron frappe un nucléon : les cas possibles et leurs probabilités. |
| `hadron_events.py` | Un événement hadron + nucléon, de bout en bout. |
| `nucleon.py` | Le nucléon de l'intérieur : trois quarks de valence dans une corde de couleur. |
| `bag.py` | La forme des hadrons et le champ du proton (membranes, puits de Coulomb). |
| `partons.py` | La mer de partons : ce qu'on voit dans un nucléon quand la résolution augmente. |
| `parton_cloud.py` | Une population de partons tirée dans ces distributions, et ses fluctuations. |
| `subatomic.py` | La dynamique à quelques nucléons du premier laboratoire subatomique. |

Les modèles sont expliqués dans `docs/SUBATOMIC_MODEL.md` et `docs/NUCLEON_MODEL.md`. Côté client : `sim/app/src/main/java/org/genesis/subatomic/`.
