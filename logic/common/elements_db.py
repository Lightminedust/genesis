"""La base SQLite de mendeleev (elements.db), partagée par tous les threads du moteur.

Une connexion SQLite ouverte avec check_same_thread=False n'est pas pour autant
utilisable par deux threads à la fois : la session d'un client, celle d'un
autre client et le chargement de la bibliothèque des étoiles (au démarrage)
la lisaient en même temps, et SQLite renvoyait alors « bad parameter or other
API misuse » ou des lignes vides. Chaque requête passe ici sous un verrou et
rend toutes ses lignes d'un coup.
"""

import os
import sqlite3
import threading

import mendeleev


class _Rows(list):
    """Le résultat complet d'une requête, avec l'interface d'un curseur."""

    def fetchone(self):
        return self[0] if self else None

    def fetchall(self):
        return list(self)


class _LockedDB:
    def __init__(self, path):
        self._connection = sqlite3.connect(path, check_same_thread=False)
        self._lock = threading.Lock()

    def execute(self, sql, parameters=()):
        with self._lock:
            return _Rows(self._connection.execute(sql, parameters).fetchall())


DB = _LockedDB(os.path.join(os.path.dirname(mendeleev.__file__), "elements.db"))
