# app — l'application

Le point d'entrée du client et tout ce qui entoure les écrans.

| Classe | Rôle |
|---|---|
| `App` | Le point d'entrée (`main`). Construit la fenêtre, reçoit les frames du moteur, passe d'un laboratoire à l'autre. |
| `TimelineBar` | La timeline : la chaîne de désintégration en ligne de métro, la vitesse du temps. |
| `EventJournal` | Le journal : tout ce qui est arrivé, par catégorie, avec filtres. |
| `PeriodicTableDialog` | Le sélecteur d'élément : la table périodique entière. |
| `IdentityStrip` | Le bandeau d'identité : ce qu'est l'atome, en un coup d'œil. |
| `Branding` | Les icônes des fenêtres (16 à 512 px), tirées du kit de la marque `assets/brand` par Gradle. |
| `LabBar` | La barre du haut : le fil « GENESIS_ / Atome 10⁻¹⁰ m » (le logotype ramène à l'accueil), les modes du laboratoire, puis les outils communs en icônes — guide, image de la scène, panneaux, plein écran, Méthode, réglages ; l'état du moteur seulement s'il y a quelque chose à en dire. Rien des autres laboratoires. |
| `GuideOverlay` | Le guide d'un laboratoire (touche H, et seul à la première visite) : l'écran s'assombrit, chaque zone est cernée et porte une fiche numérotée, une fiche centrale dit par où commencer et les touches. Les zones et les textes viennent de chaque laboratoire (`common/Guide`). |
| `Toast` | Le message bref du bas de l'écran (« Image enregistrée », un geste impossible), avec une action s'il le faut ; il s'efface seul. |
| `HomeScreen` | L'accueil : ce qu'est Genesis en une phrase ; les quatre laboratoires du plus grand au plus petit, chacun relié à sa place sur une règle des tailles (10⁻⁸ à 10⁻¹⁶ m), un dessin tracé d'après une formule, sa méthode au survol ; en bas, Genesis (ce qu'il est, ses liens, sa version et sa mise à jour, sa langue). Touches 1 à 4 ; Échap revient au laboratoire ouvert. |
| `LabLoader` | L'écran d'attente quand on ouvre ou quitte un laboratoire. Il se pose d'un coup sur l'image figée de l'écran au clic (capture à la résolution de l'écran, environ 20 ms), qui fond vers son fond opaque en 0,2 s ; le moteur change de laboratoire dessous sans que rien ne se voie, puis tout s'efface en 0,25 s sur le laboratoire demandé. La carte n'apparaît qu'après 0,3 s d'attente ; elle montre le G_ de la marque, son curseur qui clignote, et il glitche par bouffées (`Brand.glitchAt`, la première quand la carte a fini de paraître) : ses traits sautent ailleurs, une tranche se déchire, puis tout revient en place. Si le moteur ne répondra pas, plus de glitch : le curseur s'arrête, en rouge. |
| `StartupScreen` | L'écran de démarrage : le logotype GENESIS_ s'écrit comme dans un terminal (le curseur seul ; le G qui arrive dans un glitch, des tranches qui glissent de moins en moins loin pas à pas ; E N E S I S tapés, chacune avec un petit glitch, le curseur avance d'une case par frappe, puis clignote ; ensuite le nom glitche par bouffées) ; « La matière, calculée sous vos yeux. », l'étape en cours, et en petit les liens de Genesis. Opaque dès sa première image (posé avant que la fenêtre s'ouvre) : l'accueil, construit derrière, ne se voit jamais avant lui. Si le démarrage échoue, le curseur s'arrête en rouge. Reste au moins 5 s (`MINIMUM_S`), puis s'efface en une seconde à la première frame du moteur. |
| `Telemetry` | Les statistiques d'utilisation (docs/PRIVACY.md) : identifiant aléatoire dans les préférences, ouverture et fin de session en JSON vers gemmi-e.com/genesis/stats, temps par laboratoire et au premier plan, file hors ligne, fin notée chaque minute contre un arrêt brutal ; actif par défaut, refus définitif, rien depuis les sources (`-Dgenesis.telemetry=on`, `-Dgenesis.telemetry.endpoint=…` pour essayer). |
| `TelemetryNotice` | La carte du premier lancement (D'accord / Refuser l'envoi, ce qui est envoyé), rouverte depuis ⚙ avec l'identifiant de l'installation. |
| `UpdateChecker` | La dernière version publiée sur GitHub (genesis-releases), comparaison, téléchargement vérifié par SHA-256. |
| `UpdateCard` | La carte « nouvelle version disponible », en bas à droite. |
| `I18n` | La traduction à l'affichage (français source → anglais) : relecture (`en-overrides.tsv`), phrases exactes (`en.tsv`), gabarits à emplacements `{0}` (`en-templates.tsv`), découpage en segments, puis fragments en dernier recours. |

Genesis se réactive dans `App` : passer `GENESIS_ENABLED` à `true`.
Le laboratoire subatomique (protons, neutrons, électrons pas à pas), incomplet, se réactive en passant `SUBATOMIC_SCREEN` à `true`.

Ajouter un texte français : s'il est assemblé à l'exécution, entourer chaque morceau de `I18n.text(...)`, puis ajouter sa traduction dans `en.tsv` (ou un gabarit dans `en-templates.tsv` s'il contient des nombres ou des noms d'éléments).

Tests et aperçus : `sim/app/src/test/java/org/genesis/app/`.
