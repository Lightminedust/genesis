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
