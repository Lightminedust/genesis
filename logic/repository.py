"""Accès à la table périodique stockée dans MongoDB.

Le moteur ne doit jamais « deviner » la physique : il interroge ce dépôt.
Si MongoDB est injoignable (lancement du moteur hors Docker, base pas
encore prête...), on bascule silencieusement sur les données en mémoire
pour que le simulateur reste utilisable. La frame indique au client quelle
source a été utilisée.
"""

import os

from periodic_table import ELEMENTS

SOURCE_MONGO = "mongodb"
SOURCE_MEMORY = "mémoire"


class ElementRepository:

    def __init__(self, mongo_uri=None, timeout_ms=2000):
        self.source = SOURCE_MEMORY
        self._collection = None
        self._memory = {element["z"]: element for element in ELEMENTS}

        uri = mongo_uri or os.environ.get("MONGO_URI")
        if uri:
            self._try_connect(uri, timeout_ms)

    def _try_connect(self, uri, timeout_ms):
        try:
            from pymongo import MongoClient

            client = MongoClient(uri, serverSelectionTimeoutMS=timeout_ms)
            client.admin.command("ping")  # Échoue vite si la base est absente
            collection = client.get_database().get_collection("elements")
            self._seed(collection)
            self._collection = collection
            self.source = SOURCE_MONGO
            print(f"Table périodique servie par MongoDB ({collection.count_documents({})} éléments).")
        except Exception as err:  # noqa: BLE001 - tout échec doit mener au repli
            print(f"MongoDB injoignable ({type(err).__name__}: {err}). Repli sur les données en mémoire.")

    @staticmethod
    def _seed(collection):
        """Amorce ou met à jour la collection. Idempotent : relancer le
        conteneur ne duplique rien et propage les corrections de données."""
        collection.create_index("z", unique=True)
        for element in ELEMENTS:
            collection.update_one({"z": element["z"]}, {"$set": element}, upsert=True)

    def all(self):
        """Toute la table, triée par numéro atomique."""
        if self._collection is not None:
            try:
                documents = list(self._collection.find({}, {"_id": 0}).sort("z", 1))
                if documents:
                    return documents
            except Exception as err:  # noqa: BLE001 - la base peut tomber en cours de route
                print(f"Lecture MongoDB echouee ({err}), repli memoire pour la table.")
        return [self._memory[z] for z in sorted(self._memory)]

    def find(self, z):
        """Retourne la fiche de l'élément de numéro atomique `z`, ou None."""
        if z <= 0:
            return None
        if self._collection is not None:
            try:
                document = self._collection.find_one({"z": z}, {"_id": 0})
                if document:
                    return document
            except Exception as err:  # noqa: BLE001 - la base peut tomber en cours de route
                print(f"Lecture MongoDB échouée ({err}), repli mémoire pour Z={z}.")
        return self._memory.get(z)
