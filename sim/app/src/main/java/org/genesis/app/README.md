# app — l'application

Le point d'entrée du client et tout ce qui entoure les écrans.

| Classe | Rôle |
|---|---|
| `App` | Le point d'entrée (`main`). Construit la fenêtre, reçoit les frames du moteur, passe d'un laboratoire à l'autre. |
| `TimelineBar` | La timeline : la chaîne de désintégration en ligne de métro, la vitesse du temps. |
| `EventJournal` | Le journal : tout ce qui est arrivé, par catégorie, avec filtres. |
| `PeriodicTableDialog` | Le sélecteur d'élément : la table périodique entière. |
| `IdentityStrip` | Le bandeau d'identité : ce qu'est l'atome, en un coup d'œil. |
| `LabLoader` | L'écran d'attente quand on ouvre ou quitte un laboratoire ; au démarrage, le cadre « Soutenir Genesis ». |
| `UpdateChecker` | La dernière version publiée sur GitHub (genesis-releases), comparaison, téléchargement vérifié par SHA-256. |
| `UpdateCard` | La carte « nouvelle version disponible », en bas à droite. |
| `I18n` | La traduction à l'affichage (français source → anglais) : relecture (`en-overrides.tsv`), phrases exactes (`en.tsv`), gabarits à emplacements `{0}` (`en-templates.tsv`), découpage en segments, puis fragments en dernier recours. |

Genesis se réactive dans `App` : passer `GENESIS_ENABLED` à `true`.
Le laboratoire subatomique (protons, neutrons, électrons pas à pas), incomplet, se réactive en passant `SUBATOMIC_SCREEN` à `true`.

Ajouter un texte français : s'il est assemblé à l'exécution, entourer chaque morceau de `I18n.text(...)`, puis ajouter sa traduction dans `en.tsv` (ou un gabarit dans `en-templates.tsv` s'il contient des nombres ou des noms d'éléments).

Tests et aperçus : `sim/app/src/test/java/org/genesis/app/`.
