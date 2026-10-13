# molecular — la molécule et la matière

| Fichier | Rôle |
|---|---|
| `chemistry.py` | L'échelle de la molécule : deux atomes en chimie quantique (énergie, densité, orbitales). Demande PySCF, qui n'existe que sous Linux : sous Windows, cette échelle marche dans Docker. |
| `matter.py` | L'échelle de la matière : des centaines d'atomes (Lennard-Jones) et la thermodynamique qui en émerge. |

Les calculs de chimie déjà faits sont gardés dans un dossier `cache/` (ou celui que donne `GENESIS_CHEM_CACHE`).

Côté client : `sim/app/src/main/java/org/genesis/matter/`.
