# app — l'application

Le point d'entrée du client et tout ce qui entoure les écrans.

| Classe | Rôle |
|---|---|
| `App` | Le point d'entrée (`main`). Construit la fenêtre, reçoit les frames du moteur, passe d'un laboratoire à l'autre. |
| `TimelineBar` | La timeline : la chaîne de désintégration en ligne de métro, la vitesse du temps. |
| `EventJournal` | Le journal : tout ce qui est arrivé, par catégorie, avec filtres. |
| `PeriodicTableDialog` | Le sélecteur d'élément : la table périodique entière. |
| `IdentityStrip` | Le bandeau d'identité : ce qu'est l'atome, en un coup d'œil. |
| `LabLoader` | L'écran d'attente quand on ouvre ou quitte un laboratoire. |

Genesis se réactive dans `App` : passer `GENESIS_ENABLED` à `true`.

Tests et aperçus : `sim/app/src/test/java/org/genesis/app/`.
