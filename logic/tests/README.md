# tests — les tests du moteur

Chaque règle est confrontée à une valeur mesurée ou à une loi de conservation. Un fichier `test_x.py` teste le module `x`.

Depuis `logic/` :

```bash
venv\Scripts\python -m unittest discover -s tests -t .     # tout (une dizaine de minutes)
venv\Scripts\python -m unittest tests.test_atom            # un fichier
venv\Scripts\python -m unittest tests.test_atom.SlaterTest # une classe
```

Sous Windows, les tests de `test_chemistry.py` sont sautés (PySCF absent) : ils tournent dans Docker.
