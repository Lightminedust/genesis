// Exécuté par l'image mongo au premier démarrage d'un volume vide (docker-entrypoint-initdb.d), connecté en
// administrateur : le compte du moteur, qui ne peut que lire et écrire la base genesis_db (la table périodique),
// rien d'autre — ni les autres bases, ni les comptes.
const name = process.env.GENESIS_DB_USER || 'genesis';
const password = process.env.GENESIS_DB_PASSWORD;
if (!password) throw new Error('GENESIS_DB_PASSWORD manquant (voir .env.example)');
db.getSiblingDB('genesis_db').createUser({
  user: name,
  pwd: password,
  roles: [{ role: 'readWrite', db: 'genesis_db' }],
});
print(`Compte ${name} créé : lecture-écriture sur genesis_db seulement.`);
