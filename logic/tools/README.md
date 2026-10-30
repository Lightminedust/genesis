# tools — outils en ligne de commande

Ils fabriquent de vraies frames du moteur, sans serveur, pour les aperçus du client (`gradlew atomPreview`, `fusionPreview`…).

Ils se lancent depuis `logic/`, comme des modules :

```bash
venv\Scripts\python -m tools.export_atom_frames ..\artifacts\frames
venv\Scripts\python -m tools.export_collision --a 6:12 --b 8:16 --energy 20000 --out ..\artifacts\carbon-oxygen.pb
```

| Fichier | Rôle |
|---|---|
| `export_atom_frames.py` | Écrit les frames de l'écran de l'atome (hydrogène, sodium, uranium, une désintégration…). |
| `export_collision.py` | Calcule une collision et l'écrit comme une frame du client. |
| `export_chain_frames.py` | Écrit des frames du laboratoire de la réaction en chaîne (sous-critique, bouffée, dispersion, réflecteur, bac à sable) pour `ChainPreview`. |
| `build_chain_groups.py` | Construit `chainlab/data/groups.npz` depuis les fichiers HDF5 d'ENDF/B-VIII.0 (OpenMC) ; demande h5py, que le moteur n'utilise pas. |
| `export_molecule_frames.py` | Écrit des frames du laboratoire moléculaire (eau en ρ, Δρ et HOMO, éthylène, benzène, H₂ tiré, modes du méthane, réacteur) pour `MoleculePreview`. |
| `build_xtb_data.py` | Construit `moleculelab/data/xtb-data.npz` (STO-NG, rayons, C6 de D3) depuis une installation de dxtb ; dxtb et PyTorch ne servent qu'ici, jamais au moteur. |
