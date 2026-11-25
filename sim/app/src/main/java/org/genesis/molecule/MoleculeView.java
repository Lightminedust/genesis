package org.genesis.molecule;

import org.genesis.app.I18n;

import com.studio.genesis.grpc.LabFact;
import com.studio.genesis.grpc.MoleculeAtom;
import com.studio.genesis.grpc.MoleculeBond;
import com.studio.genesis.grpc.MoleculeCloud;
import com.studio.genesis.grpc.MoleculeIsotope;
import com.studio.genesis.grpc.MoleculeEvent;
import com.studio.genesis.grpc.MoleculeExperiment;
import com.studio.genesis.grpc.MoleculeLabState;
import com.studio.genesis.grpc.MoleculeMode;
import com.studio.genesis.grpc.MoleculeMulticentre;
import com.studio.genesis.grpc.MoleculeOrbital;
import com.studio.genesis.grpc.MoleculeOrbitalPart;
import com.studio.genesis.grpc.MoleculePair;
import com.studio.genesis.grpc.MoleculeSpecies;
import com.studio.genesis.grpc.UniverseFrame;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Le laboratoire moléculaire : des atomes posés sur un établi, leurs électrons calculés (GFN1-xTB), et les forces
 * qui en sortent.
 *
 * <ul>
 * <li>Au centre, la densité électronique calculée, en volume : les liaisons sont les ponts de lumière que les
 *     électrons forment entre les noyaux. On tourne la vue en glissant, on zoome à la molette.</li>
 * <li>On saisit un atome et on tire : la molécule est tenue par son centre de masse, la liaison s'étire, l'énergie
 *     monte, et elle casse quand on lui donne plus que son énergie de liaison.</li>
 * <li>L'établi : choisir un élément puis cliquer sur un atome pour l'y attacher ; la géométrie (tétraèdre du méthane,
 *     eau coudée) sort de la relaxation, pas d'une règle.</li>
 * <li>La dynamique : relaxation, thermostat (température), ou libre (l'énergie se conserve) ; une étincelle (clic
 *     droit sur un atome) dépose de l'énergie pour franchir une barrière d'activation dans le réacteur.</li>
 * <li>À droite, ce que le calcul en dit : énergies terme par terme, orbitales (HOMO, LUMO), espèces présentes,
 *     réactions, vibrations.</li>
 * </ul>
 */
public final class MoleculeView {
    /** Ce que la vue demande au moteur. */
    public interface Actions {
        void back();
        void preset(int index);
        void reaction(int index);
        void addAtom(int z, int anchor, double[] position);
        void removeAtom(int index);
        void drag(int index, double[] target);
        void release();
        void dynamics(int mode, double kelvin);
        void spark(int index, double ev);
        void view(int kind, int orbital, boolean potential);
        void modes();
        void excite(int mode, double quanta);
        void charge(int charge);
        void pause(boolean paused);

        /** L'isotope A d'un atome (target ≥ 0) ou de tous les atomes de l'élément −target ; A = 0 : le mélange naturel. */
        void isotope(int target, int massNumber);
        void clear();
        void pickElement();

        /** Le dessin de l'assembleur devient la scène (voir MoleculeAssembler.vector). */
        default void build(double[] vector, int charge, boolean enclosed) {}

        /** La pression imposée par le piston (atm) ; 0 : la paroi ne bouge plus (volume fixe). */
        default void pressure(double atm) {}

        /** Une des expériences guidées (MoleculeLabState.experiments) : la scène devient l'expérience. */
        default void experiment(int index) {}

        /** Le verdict du moteur sur un dessin de l'assembleur : noms aussitôt, puis (deep) la relaxation GFN1-xTB. */
        default void assess(int request, double[] vector, boolean deep) {}

        /** La réaction d'un dessin de l'assembleur (le nanoréacteur) : request < 0 l'annule ; temperature en K. */
        default void react(int request, double[] vector, double temperature) {}

        /** Le schéma réactionnel dessiné (JSON, voir SchemeEditor.json) : request < 0 annule le calcul. */
        default void scheme(int request, String json) {}

        /** Le chemin d'une étape joué dans la scène : step < 0 l'arrête ; position 0 → 1 (< 0 : garder). */
        default void schemePlay(int step, double position, boolean running) {}

        /** Le même, à une vitesse choisie : images du chemin par seconde (0 : au rythme du calcul). */
        default void schemePlay(int step, double position, boolean running, double speed) {
            schemePlay(step, position, running);
        }

        /** Le dessin d'un SMILES, pour l'éditeur de schémas. */
        default void smiles(int request, String text) {}

        /** Le solvant implicite de la scène (indice dans MoleculeLabState.solvents ; −1 : le vide). */
        default void solvent(int index) {}
    }

    private static final String[] VIEW_NAMES = {"ρ  Densité", "Δρ  Déformation", "ψ  Orbitale"};
    private static final String[] VIEW_TIPS = {
            "La densité électronique ρ = |Ψ|² des électrons de valence : les liaisons sont les ponts entre les noyaux",
            "Δρ = ρ(molécule) − ρ(atomes isolés) : cyan, les électrons que la liaison a rassemblés ; orange, ceux qu'elle a pris",
            "Une orbitale moléculaire ψ, avec sa phase (bleu +, orange −) : choisir dans le diagramme des niveaux"};
    private static final String[] DYNAMICS = {"Relaxation", "Thermostat", "Libre"};
    private static final String[] DYNAMICS_TIPS = {
            "Les noyaux descendent vers le minimum d'énergie le plus proche (FIRE) : une géométrie d'équilibre. "
                    + "Pour chercher une forme plus stable, chauffer au thermostat puis relaxer (recuit)",
            "Dynamique moléculaire à température fixée (ensemble NVT : thermostat CSVR, et un bain faible sur chaque "
                    + "atome pour que chaque molécule ait sa part d'agitation) : la molécule vibre, tourne, et peut réagir",
            "Dynamique sans thermostat (ensemble NVE) : l'énergie totale (potentielle + cinétique) se conserve"};
    private final Actions actions;
    private final BorderPane root = new BorderPane();
    /** L'écran de l'assembleur 2D, posé par-dessus le laboratoire 3D quand il est ouvert. */
    private final MoleculeAssembler assembler;
    /** L'éditeur de schémas réactionnels, posé par-dessus le laboratoire quand il est ouvert ; le chemin joué. */
    private final SchemeEditor schemeEditor;
    private final SchemePlayer schemePlayer;
    /** Le chemin d'une étape dans sa vue 2.5D de lecture, posée par-dessus le laboratoire (voir SchemeStage). */
    private final SchemeStage schemeStage;
    private com.studio.genesis.grpc.MoleculeScheme lastScheme, playedScheme;
    private int playedRequest = -1;
    private boolean playMode;

    /**
     * Pendant la lecture d'un chemin de schéma, la colonne de gauche (établi, molécules, expériences) se retire : elle
     * ne sert pas au replay et prenait un cinquième de la largeur. Elle revient quand la scène redevient libre.
     */
    private void playMode(boolean on) {
        if (on == playMode) return;
        playMode = on;
        Node left = root.getLeft();
        if (left != null) {
            left.setVisible(!on);
            left.setManaged(!on);
        }
    }
    /** L'étape dont le chemin est joué, cadrée une fois au début de sa lecture (−1 : aucune). */
    private int playedStep = -1;
    /** Le solvant de la scène (ALPB) et son énergie libre de solvatation. */
    private final javafx.scene.control.ComboBox<String> solventBox = new javafx.scene.control.ComboBox<>();
    private final Label solvationLabel = new Label();
    private final StackPane frame;
    private final StackPane stage = new StackPane();
    private final Pane holder;
    private final Canvas sceneCanvas = new Canvas(), glowNear = new Canvas(), glowFar = new Canvas();
    /** Les lobes de la vue ψ, en verre, sous la scène (null sans carte graphique 3D : la vue garde ses points). */
    private final OrbitalLayer orbitalLayer = OrbitalLayer.available() ? new OrbitalLayer() : null;
    /** La vignette : la molécule en schéma 2D, avec ses mesures et une consigne en temps réel (voir MoleculeSketch). */
    private final Canvas sketchCanvas = new Canvas(MoleculeSketch.W, MoleculeSketch.H);
    private final MoleculeSketch sketch = new MoleculeSketch();
    final MoleculeScene scene = new MoleculeScene();
    private final ToggleButton[] viewButtons = new ToggleButton[3];
    private final ToggleButton[] dynamicsButtons = new ToggleButton[3];
    private final ToggleGroup paletteGroup = new ToggleGroup();
    private final FlowPane presetBox = new FlowPane(5, 5), reactionBox = new FlowPane(5, 5), paletteBox = new FlowPane(4, 4);
    private final ToggleButton[] chargeButtons = new ToggleButton[3];
    /** Les expériences guidées : une ligne chacune (titre, équation), puis ce qui se passe pour celle qui tourne. */
    private final VBox experimentBox = new VBox(4);
    private final HBox viewModes = new HBox(10);
    private final List<HBox> experimentButtons = new ArrayList<>();
    private final Label experimentPhase = text("", 11.5, "#C7D6EA"), experimentNote = text("", 10.5, "#A2ABB8");
    private final Label title = text("", 17, "#ECEFF3"), census = text("", 12.5, "#8FD8FF");
    /** Sous les espèces, ce que montre la vue Δρ ou ψ (l'orbitale : nom, énergie, électrons, composition). */
    private final Label modeLine = text("", 12, "#C7D6EA");
    private String moleculeSignature = "";
    private List<MoleculeOrbitalPart> orbitalParts = List.of();
    private int partsOrbital = -1;
    private final Label status = text("", 11, "#A2ABB8"), error = text("", 11.5, "#FF9C8A");
    /** Le bilan d'énergie (voir EnergyBalance), la cible des niveaux (EnergyTarget), la carte de l'atome (AtomMap). */
    private final Canvas balance = new Canvas(270, 200), atomCanvas = new Canvas(270, 300);
    private final EnergyTarget target = new EnergyTarget();
    private final AtomMap atomMap = new AtomMap();
    private int levelHover = -1;
    private final Label speciesLines = text("", 11.5, "#ECEFF3"), eventLines = text("", 10.5, "#A9BCD6");
    private final Label atomCard = text("", 11.5, "#ECEFF3"), dipoleLine = text("", 11, "#A9BCD6");
    /** Les isotopes de l'atome choisi (voir refreshIsotopes) ; ceux que le moteur propose, par élément. */
    private final FlowPane isotopeRow = new FlowPane(4, 4);
    private final ToggleButton isotopeAll = new ToggleButton();
    private final Map<Integer, List<MoleculeIsotope>> isotopeChoices = new HashMap<>();
    private String isotopeKey = "";
    private final Label orbitalLine = text("", 11, "#A9BCD6"), modeHeader = text("", 11, "#A9BCD6");
    private final VBox modeList = new VBox(3);
    private final Slider temperature = new Slider(0, 4000, 300), spark = new Slider(1, 30, 8), quanta = new Slider(1, 10, 1);
    private final Label temperatureLabel = text("", 11, "#A2ABB8"), sparkLabel = text("", 11, "#A2ABB8");
    private final Label quantaLabel = text("", 11, "#A2ABB8");
    /**
     * Le piston du réacteur : allumé, la paroi suit la pression choisie (100 à 20 000 atm, échelle logarithmique) ; et
     * ce que l'enceinte mesure (pression sur la paroi, volume, gaz parfait, fréquence des rencontres).
     */
    private final ToggleButton piston = new ToggleButton();
    private final Slider pressure = new Slider(2, Math.log10(20000), Math.log10(3000));
    private final Label pressureLabel = text("", 11, "#A2ABB8"), enclosure = text("", 10.5, "#A9BCD6");
    /** Le sens de la lecture de l'enceinte (ce que veut dire Z, ce que fait le piston), au survol. */
    private final Tooltip enclosureTip = I18n.tooltip();
    private final Button pause = button("Ⅱ", false);
    private final Canvas chart = new Canvas(430, 92), levels = new Canvas(270, 264);
    private final VBox modelBanner = new VBox(8);
    private final Label modelText = text("", 11.5, "#ECEFF3");
    private final GridPane validation = new GridPane();
    private final List<String> presets = new ArrayList<>(), reactions = new ArrayList<>();
    private final java.util.Set<Integer> supported = new java.util.HashSet<>();
    private final Label chosenElement = text("", 10.5, "#FFD27A");
    /** La carte du survol d'un atome (voir AtomCard) et la fiche d'une molécule au clic droit (MoleculeCard). */
    private final Canvas hoverCard = new Canvas(AtomCard.W, 120);
    private final MoleculeCard moleculeCard;
    private final Region dock;
    /**
     * Ce que l'on vient de demander et que le moteur n'a pas encore confirmé. Les frames parties avant l'ordre montrent
     * encore l'ancien état : elles ne doivent pas remettre le bouton en arrière (on cliquait, rien ne semblait se
     * passer, puis le bouton basculait). Une demande tient jusqu'à ce qu'une frame la confirme, au plus PENDING_NS.
     */
    private static final long PENDING_NS = 3_000_000_000L, PANELS_NS = 125_000_000L;
    private long panelsAt;
    private final Map<String, Object> pending = new HashMap<>();
    private final Map<String, Long> pendingSince = new HashMap<>();
    private MoleculeLabState state;
    private boolean updating, rotating, paused, legendPress;
    /**
     * Le clic droit, le milieu ou Maj + clic : glissés, ils déplacent la vue (panning) ; le clic droit sans glisser ouvre
     * la fiche. freshScene : une autre scène est demandée (molécule, réacteur, établi vide, assemblage) — elle se
     * recadre entière, alors qu'un atome ajouté laisse la vue où la main l'a menée.
     */
    private boolean panPress, panning, freshScene;
    /** Le bloc de navigation, en bas à droite de la scène : ce que suit la vue, zoom, tout voir. */
    private final Label viewLine = text("", 10.5, "#A9BCD6");
    private HBox navigation;
    private double lastX, lastY, pressX, pressY, lastDragSent, hoverX, hoverY;
    private int pressedAtom = -1, dragging = -1, homo, viewKind, viewOrbital = -1;
    private String framedLabel = "", titleLabel = "", titleSpecies = "";
    private int framedCount = -1;
    private double[] dragPlane;
    private double[] historyTime = new double[0], historyEnergy = new double[0], historyKinetic = new double[0];
    private List<MoleculeOrbital> orbitals = new ArrayList<>();

    public MoleculeView(Actions actions) {
        this.actions = actions;
        schemePlayer = new SchemePlayer(new SchemePlayer.Actions() {
            @Override public void play(int step, double position, boolean running) { actions.schemePlay(step, position, running); }
            @Override public void openEditor() { showScheme(true); }
            @Override public void openStage() {
                // La vue du chemin reprend là où joue la scène 3D, et la rejoue seule : le moteur s'arrête.
                com.studio.genesis.grpc.MoleculeSchemePlay p = state == null ? null : state.getPlay();
                int step = p == null ? -1 : p.getStep();
                schemeStage.update(lastScheme);
                schemeStage.show(true, step, p == null ? 0 : p.getPosition(), p != null && p.getRunning());
                schemeEditor.getNode().setVisible(false);
                if (step >= 0) actions.schemePlay(-1, -1, false);
            }
        });
        schemeStage = new SchemeStage(new SchemeStage.Actions() {
            @Override public void openScene(int step, double position, boolean running, double speed) {
                // La vue du chemin reste là jusqu'à ce que le moteur ait posé l'étape : la scène 3D montrait sinon, un
                // instant, ce qu'elle avait avant (l'eau de départ).
                pendingScene = step;
                sceneAskedAt = System.nanoTime();
                schemeStage.opening(true);
                actions.schemePlay(step, position, running, speed);
            }
            @Override public void openEditor() {
                showStage(false, -1);
                showScheme(true);
            }
        });
        root.getStyleClass().addAll("fusion-lab", "molecule-lab");
        root.getStyleClass().add("plan-scene");
        holder = new Pane(sceneCanvas, glowFar, glowNear);
        if (orbitalLayer != null) {
            Pane lobes = (Pane) orbitalLayer.getNode();
            lobes.prefWidthProperty().bind(holder.widthProperty());
            lobes.prefHeightProperty().bind(holder.heightProperty());
            holder.getChildren().add(0, lobes);
            scene.glass = true;
        }
        // Le bloom : deux calques où la scène ne dessine que ce qui émet de la lumière, floutés et ajoutés par-dessus.
        glowNear.setEffect(new GaussianBlur(5));
        glowFar.setEffect(new GaussianBlur(22));
        for (Canvas glow : new Canvas[]{glowNear, glowFar}) {
            glow.setBlendMode(BlendMode.ADD);
            glow.setMouseTransparent(true);
            glow.widthProperty().bind(holder.widthProperty());
            glow.heightProperty().bind(holder.heightProperty());
        }
        holder.setMinSize(100, 100);
        javafx.scene.shape.Rectangle bounds = new javafx.scene.shape.Rectangle();
        bounds.widthProperty().bind(holder.widthProperty());
        bounds.heightProperty().bind(holder.heightProperty());
        holder.setClip(bounds);                     // les lobes 3D décalés avec la vue ne débordent pas sur les lectures
        sceneCanvas.widthProperty().bind(holder.widthProperty());
        sceneCanvas.heightProperty().bind(holder.heightProperty());
        holder.setCursor(Cursor.OPEN_HAND);
        holder.setOnMousePressed(this::press);
        holder.setOnMouseDragged(this::drag);
        holder.setOnMouseReleased(this::release);
        holder.setOnMouseMoved(e -> {
            scene.hovered = scene.pick(e.getX(), e.getY());
            holder.setCursor(scene.hovered >= 0 ? Cursor.HAND : Cursor.OPEN_HAND);
            hoverX = e.getX();
            hoverY = e.getY();
            refreshHover();
        });
        holder.setOnMouseExited(e -> {
            scene.hovered = -1;
            refreshHover();
        });
        hoverCard.setMouseTransparent(true);
        hoverCard.setManaged(false);
        hoverCard.setVisible(false);
        holder.getChildren().add(hoverCard);
        moleculeCard = new MoleculeCard(new MoleculeCard.Actions() {
            @Override public void spark(int atom) { actions.spark(atom, MoleculeView.this.spark.getValue()); }
            @Override public void removeAtom(int atom) {
                closeCard();
                actions.removeAtom(atom);
            }
            @Override public void removeMolecule(int[] atoms) {
                closeCard();
                // Du plus grand indice au plus petit : retirer un atome ne renumérote que ceux d'après.
                for (int k = atoms.length - 1; k >= 0; k--) actions.removeAtom(atoms[k]);
            }
            @Override public void edit(int[] atoms) {
                closeCard();
                assembler.importAtoms(scene, atoms);
                showAssembler(true);
            }
            @Override public void select(int atom) {
                closeCard();
                if (scene.selected != atom) MoleculeView.this.select(atom);
            }
            @Override public void close() { closeCard(); }
            @Override public void centre(int[] atoms) {
                closeCard();
                scene.focusOn(atoms, true);
            }
        });
        Region card = moleculeCard.getNode();
        card.setManaged(false);
        card.setVisible(false);
        holder.getChildren().add(card);
        holder.setFocusTraversable(true);
        holder.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE -> closeCard();
                case F, HOME -> scene.showAll();
                default -> { }
            }
        });
        // + et − d'après le caractère tapé : la touche n'est pas la même en AZERTY et en QWERTY.
        holder.setOnKeyTyped(e -> {
            if (e.getCharacter().equals("+")) zoomStep(1);
            if (e.getCharacter().equals("-") || e.getCharacter().equals("−")) zoomStep(-1);
        });
        // La molette zoome vers le curseur : ce qui est dessous reste dessous (voir MoleculeScene.zoomAt).
        holder.setOnScroll(e -> scene.zoomAt(e.getX(), e.getY(), e.getDeltaY() / 40));
        dock = dock();
        navigation = navigation();
        stage.getChildren().addAll(holder, header(), sketchCanvas, navigation, modelBanner(), schemePlayer.getNode());
        StackPane.setAlignment(schemePlayer.getNode(), Pos.BOTTOM_LEFT);
        StackPane.setMargin(schemePlayer.getNode(), new javafx.geometry.Insets(0, 0, 14, 14));
        // La navigation en bas à droite de la scène, la vignette 2D en bas à gauche ; la barre de dynamique est dessous.
        StackPane.setAlignment(navigation, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(navigation, new Insets(0, 14, 14, 0));
        StackPane.setAlignment(sketchCanvas, Pos.BOTTOM_LEFT);
        StackPane.setMargin(sketchCanvas, new Insets(0, 0, 14, 12));
        // La vignette et la scène se répondent : survoler un atome du schéma le montre en 3D, le cliquer le choisit.
        sketchCanvas.setOnMouseClicked(e -> {
            if (sketch.onTitle(e.getX(), e.getY())) {
                drawSketch();
                return;
            }
            int atom = sketch.pick(e.getX(), e.getY());
            if (atom >= 0) select(atom);
        });
        sketchCanvas.setOnMouseMoved(e -> {
            int atom = sketch.pick(e.getX(), e.getY());
            sketchCanvas.setCursor(atom >= 0 || e.getY() <= MoleculeSketch.HEAD ? Cursor.HAND : Cursor.DEFAULT);
            if (atom != scene.hovered) {
                scene.hovered = atom;
                drawSketch();
            }
        });
        sketchCanvas.setOnMouseExited(e -> {
            scene.hovered = -1;
            drawSketch();
        });
        BorderPane centre = new BorderPane(stage);
        centre.setBottom(dock);
        sceneStage = stage;
        root.setCenter(centre);
        root.setLeft(column(workbench(), org.genesis.common.Plan.LEFT_WIDTH, "left"));
        root.setRight(column(readings(), org.genesis.common.Plan.RIGHT_WIDTH, "right"));
        // La cible : survoler un cercle lit son niveau au cœur, cliquer montre son orbitale.
        Tooltip.install(levels, I18n.tooltip("Le rayon suit l'énergie, pas une distance : au centre, les électrons les plus liés ; "
                + "au bord, le vide. Survoler un cercle pour lire son niveau, cliquer pour voir son orbitale."));
        levels.setOnMouseMoved(e -> {
            int over = target.pick(e.getX(), e.getY());
            levels.setCursor(over >= 0 ? Cursor.HAND : Cursor.DEFAULT);
            if (over != levelHover) {
                levelHover = over;
                drawLevels();
            }
        });
        levels.setOnMouseExited(e -> {
            levelHover = -1;
            drawLevels();
        });
        levels.setOnMouseClicked(e -> pickLevel(target.pick(e.getX(), e.getY())));
        // La carte de l'atome : un clic sur un voisin le choisit.
        atomCanvas.setOnMouseMoved(e -> {
            int over = atomMap.pick(e.getX(), e.getY());
            atomCanvas.setCursor(over >= 0 || atomMap.overTab(e.getX(), e.getY()) ? Cursor.HAND : Cursor.DEFAULT);
            if (over != atomMap.hovered) {
                atomMap.hovered = over;
                refreshAtomCard();
            }
        });
        atomCanvas.setOnMouseExited(e -> {
            atomMap.hovered = -1;
            refreshAtomCard();
        });
        atomCanvas.setOnMouseClicked(e -> {
            if (atomMap.tab(e.getX(), e.getY())) {
                refreshAtomCard();
                return;
            }
            int other = atomMap.pick(e.getX(), e.getY());
            if (other >= 0) select(other);
        });
        assembler = new MoleculeAssembler(new MoleculeAssembler.Host() {
            @Override public void build(double[] vector, int charge, boolean enclosed) {
                newScene(() -> actions.build(vector, charge, enclosed));
                framedLabel = "";                     // la nouvelle scène sera cadrée
            }
            @Override public void assess(int request, double[] vector, boolean deep) { actions.assess(request, vector, deep); }
            @Override public void react(int request, double[] vector, double temperature) {
                actions.react(request, vector, temperature);
            }
            @Override public void close() { showAssembler(false); }
            @Override public void pickElement() { actions.pickElement(); }
        });
        assembler.onImport(() -> assembler.importScene(scene));
        Region drawing = assembler.getNode();
        drawing.setVisible(false);
        schemeEditor = new SchemeEditor(new SchemeEditor.Host() {
            @Override public void compute(int request, String json) { actions.scheme(request, json); }
            @Override public void smiles(int request, String text) { actions.smiles(request, text); }
            @Override public void play(int step) {
                // La vue du chemin rejoue elle-même le chemin calculé : rien n'est demandé au moteur (qui calcule
                // peut-être encore les étapes suivantes).
                showScheme(false);
                showStage(true, step);
            }
            @Override public void close() { showScheme(false); }
        });
        Region schemes = schemeEditor.getNode();
        schemes.setVisible(false);
        frame = new StackPane(root, drawing, schemes, schemeStage.getNode());
        frame.getStyleClass().add("lab-molecule");              // sa couleur (genesis.css), assembleur compris
    }

    public Region getNode() {
        return frame;
    }

    /** La scène (ses canevas et ce qui est posé dessus), pour en enregistrer une image. */
    private javafx.scene.Node sceneStage;

    /** Le guide du laboratoire (touche H) : ce qu'on y fait, par où commencer, ce que montre chaque zone. */
    public org.genesis.common.Guide guide() {
        return new org.genesis.common.Guide("Molécules", "Des atomes et des molécules calculés par la chimie quantique : "
                + "leurs électrons, leurs liaisons, leurs forces et leurs réactions.")
                .step("Choisir une molécule, une expérience guidée ou un mélange du réacteur.")
                .step("Choisir la dynamique, en bas : relaxer, chauffer (thermostat) ou laisser libre.")
                .step("Cliquer un atome : ses forces. Clic droit sur une molécule : sa fiche. L'assembleur 2D dessine les vôtres.")
                .spot(viewModes, "Les vues", "La densité d'électrons, sa déformation par les liaisons, une orbitale ; l'assembleur 2D.")
                .spot(root.getLeft(), "Préparer", "Molécules, expériences, réacteur, établi : ce qu'on met dans la scène.")
                .spot(sceneStage, "La scène", "Glisser : tourner · molette : zoomer · tirer un atome : le déplacer · double-clic : centrer.")
                .spot(root.getRight(), "Mesurer", "L'énergie et ce qui la compose, les orbitales, les espèces, l'atome choisi.")
                .spot(dock, "Le temps", "Relaxation, thermostat ou dynamique libre, la température, une étincelle.");
    }

    /** Les colonnes du laboratoire : cachées, la scène prend toute la place. */
    public void showPanels(boolean shown) {
        org.genesis.common.Plan.show(root.getLeft(), shown);
        org.genesis.common.Plan.show(root.getRight(), shown);
    }

    /** La scène seule, pour en enregistrer une image (l'assembleur, s'il est ouvert). */
    public javafx.scene.Node sceneNode() {
        if (schemeStage != null && schemeStage.isOpen()) return schemeStage.getNode();
        if (schemeEditor != null && schemeEditor.getNode().isVisible()) return schemeEditor.getNode();
        return assemblerOpen() ? assembler.getNode() : sceneStage;
    }

    /** L'assembleur 2D (ou l'éditeur de schémas, ou la vue du chemin) est-il ouvert (Échap y sert à autre chose) ? */
    public boolean assemblerOpen() {
        return assembler != null && assembler.getNode().isVisible()
                || schemeEditor != null && schemeEditor.getNode().isVisible()
                || schemeStage != null && schemeStage.isOpen();
    }

    /** Ouvre ou ferme l'éditeur de schémas réactionnels. */
    void showScheme(boolean open) {
        Region node = schemeEditor.getNode();
        node.setVisible(open);
        if (open) {
            showAssembler(false);
            schemeStage.show(false, -1);
            schemeEditor.draw();
            node.requestFocus();
        }
    }

    /** L'étape que la scène 3D attend du moteur avant de s'ouvrir (−1 : aucune), et depuis quand. */
    private int pendingScene = -1;
    private long sceneAskedAt;

    /** Ouvre ou ferme la vue du chemin (l'étape que le moteur va jouer, ou −1 : celle qu'il joue). */
    void showStage(boolean open, int step) {
        if (open) schemeStage.update(lastScheme);
        schemeStage.show(open, step);
        if (open) schemeEditor.getNode().setVisible(false);
    }

    /** La vue du chemin (aperçus et tests). */
    SchemeStage schemeStage() {
        return schemeStage;
    }

    /** Les modes du laboratoire (vues ρ, Δρ, ψ et l'assembleur 2D), posés dans l'en-tête commun. */
    public Region viewModes() {
        return viewModes;
    }

    /** La page des lectures montrée (aperçus). */
    Region readingsPane() {
        return readingTabs.page(Math.max(0, readingTabs.selected()));
    }

    /** Ouvre ou ferme l'assembleur 2D. */
    /** L'éditeur de schémas, la vue du chemin et l'assembleur fermés : la scène du laboratoire. */
    private void closeOverlays() {
        if (schemeStage.isOpen()) showStage(false, -1);
        schemeEditor.getNode().setVisible(false);
        showAssembler(false);
    }

    void showAssembler(boolean open) {
        Region drawing = assembler.getNode();
        drawing.setVisible(open);
        if (open) {
            assembler.draw();
            drawing.requestFocus();
        }
    }

    MoleculeAssembler assembler() {
        return assembler;
    }

    static Label text(String value, double size, String color) {
        Label l = new Label(value);
        l.setStyle("-fx-text-fill:" + color + ";-fx-font-size:" + size + "px;");
        return l;
    }

    static Button button(String label, boolean primary) {
        Button b = new Button(label);
        b.getStyleClass().add(primary ? "glass-primary" : "glass-button");
        b.setFocusTraversable(false);
        return b;
    }

    private static VBox section(String index, String title, Node... nodes) {
        return org.genesis.common.Plan.section(index, title, nodes);
    }

    private static String spaced(String title) {
        return I18n.text(title).toUpperCase(I18n.locale());
    }

    /**
     * Une colonne faite de pages (gauche : ce qu'on prépare ; droite : ce qu'on mesure), de la largeur commune aux
     * laboratoires ; ses pages défilent seules.
     */
    private static VBox column(Region content, double width, String side) {
        VBox pane = new VBox(content);
        VBox.setVgrow(content, Priority.ALWAYS);
        pane.setPrefWidth(width);
        pane.setMinWidth(width);
        pane.setMaxWidth(width);
        pane.getStyleClass().addAll("plan-side", side);
        return pane;
    }

    /** Le contenu d'une page de colonne, avec sa marge. */
    private static VBox page(javafx.scene.Node... nodes) {
        VBox box = new VBox(12, nodes);
        box.setPadding(new Insets(12, 16, 16, 16));
        return box;
    }

    /** Une colonne (gauche : ce qu'on fait ; droite : ce qu'on mesure), de la largeur commune aux laboratoires. */
    private static ScrollPane scroll(VBox content, double width, String side) {
        content.setPadding(new Insets(14, 16, 14, 16));
        ScrollPane pane = new ScrollPane(content);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pane.setPrefWidth(width);
        pane.setMinWidth(width);
        pane.setMaxWidth(width);
        pane.getStyleClass().addAll("plan-side", side);
        return pane;
    }

    // ================================================================== l'en-tête, le dock, le modèle
    private Region header() {
        ToggleGroup views = new ToggleGroup();
        HBox segment = new HBox(0);
        for (int k = 0; k < 3; k++) {
            ToggleButton b = new ToggleButton(VIEW_NAMES[k]);
            b.setToggleGroup(views);
            b.setFocusTraversable(false);
            b.getStyleClass().addAll("segment", k == 0 ? "left" : k == 2 ? "right" : "middle");
            b.setTooltip(I18n.tooltip(VIEW_TIPS[k]));
            b.setId("molecule-view-" + k);
            final int kind = k;
            b.setOnAction(e -> {
                b.setSelected(true);
                if (updating) return;
                // Un onglet du laboratoire y ramène : l'éditeur de schémas, la vue du chemin, l'assembleur se ferment.
                closeOverlays();
                request("view", kind);
                // Tout de suite : la scène quitte la vue précédente sans attendre le moteur.
                viewKind = kind;
                scene.mode(kind, kind == 2 ? viewOrbital : -1);
                modeLine();
                actions.view(kind, kind == 2 ? viewOrbital : -1, scene.showPotential);
            });
            viewButtons[k] = b;
            segment.getChildren().add(b);
        }
        viewButtons[0].setSelected(true);
        Button draw = button("Assembleur 2D", false);
        draw.setTooltip(I18n.tooltip("Dessiner ses molécules à plat, les vérifier par le calcul, puis les envoyer ici en 3D"));
        draw.setOnAction(e -> {
            closeOverlays();
            showAssembler(true);
        });
        Button schemeButton = button("Schéma de réaction", false);
        schemeButton.setTooltip(I18n.tooltip("Dessiner un schéma en plusieurs étapes (réactifs sur les flèches, flèches courbes) : "
                + "Genesis calcule le chemin 3D de chaque étape"));
        schemeButton.setOnAction(e -> showScheme(true));
        // Les vues (ρ, Δρ, ψ) et l'assembleur sont les modes du laboratoire : dans l'en-tête commun (voir viewModes()).
        viewModes.getChildren().setAll(segment, draw, schemeButton);
        viewModes.setAlignment(Pos.CENTER_LEFT);
        error.setWrapText(true);
        // Le message s'enroule à gauche de la légende de la scène (en haut à droite) au lieu de passer dessous.
        error.maxWidthProperty().bind(javafx.beans.binding.Bindings.max(260, holder.widthProperty().subtract(470)));
        modeLine.setWrapText(true);
        modeLine.maxWidthProperty().bind(error.maxWidthProperty());
        modeLine.setVisible(false);
        modeLine.setManaged(false);
        // Les espèces présentes sont dans la colonne de droite (et nommées dans la scène) : l'en-tête garde le titre.
        title.setStyle("");
        title.getStyleClass().add("scene-title");
        VBox top = new VBox(3, title, modeLine, error);
        top.setPadding(new Insets(12, 14, 0, 16));
        top.setPickOnBounds(false);
        top.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(top, Pos.TOP_CENTER);
        return top;
    }

    private Region dock() {
        ToggleGroup group = new ToggleGroup();
        HBox segment = new HBox(0);
        for (int k = 0; k < 3; k++) {
            ToggleButton b = new ToggleButton(DYNAMICS[k]);
            b.setToggleGroup(group);
            b.setFocusTraversable(false);
            b.getStyleClass().addAll("segment", k == 0 ? "left" : k == 2 ? "right" : "middle");
            b.setTooltip(I18n.tooltip(DYNAMICS_TIPS[k]));
            b.setId("molecule-dynamics-" + k);
            final int mode = k;
            b.setOnAction(e -> {
                b.setSelected(true);
                if (!updating) sendDynamics(mode);
            });
            dynamicsButtons[k] = b;
            segment.getChildren().add(b);
        }
        temperature.setPrefWidth(170);
        temperature.setFocusTraversable(false);
        temperature.valueProperty().addListener((o, a, b) -> {
            temperatureLabel.setText(MoleculeFormat.decimal(b.doubleValue(), 0) + " K");
            if (!updating && !temperature.isValueChanging()) sendTemperature();
        });
        temperature.valueChangingProperty().addListener((o, a, changing) -> {
            if (!changing && !updating) sendTemperature();
        });
        temperatureLabel.setText("300 K");
        spark.setPrefWidth(110);
        spark.setFocusTraversable(false);
        spark.valueProperty().addListener((o, a, b) -> sparkLabel.setText(MoleculeFormat.decimal(b.doubleValue(), 0) + " eV"));
        sparkLabel.setText("8 eV");
        Label sparkName = text("étincelle", 11, "#8FB3D9");
        Label sparkHint = text("clic droit sur un atome → ✦ étincelle", 10.5, "#8FB3D9");
        pause.setOnAction(e -> {
            paused = !paused;
            pause.setText(paused ? "▶" : "Ⅱ");
            request("pause", paused);
            actions.pause(paused);
        });
        pause.setTooltip(I18n.tooltip("Suspendre ou reprendre le calcul"));
        // Faute de place, les curseurs raccourcissent et l'aide de l'étincelle s'efface (son nom reste, l'aide est dans
        // l'infobulle du curseur) : jamais un bouton, un nom ni une valeur tronqués.
        for (ToggleButton b : dynamicsButtons) b.setMinWidth(Region.USE_PREF_SIZE);
        for (Region r : new Region[]{temperatureLabel, sparkName, sparkLabel, pause, sparkHint}) {
            r.setMinWidth(Region.USE_PREF_SIZE);
        }
        temperature.setMinWidth(70);
        spark.setMinWidth(56);
        spark.setTooltip(I18n.tooltip("Étincelle : de l'énergie cinétique déposée sur un atome et ses voisins (clic "
                + "droit sur l'atome, puis ✦ dans sa fiche), quantité de mouvement conservée"));
        sparkHint.managedProperty().bind(sparkHint.visibleProperty());
        sparkHint.setVisible(false);              // le geste est dans l'infobulle du curseur : moins de texte à l'écran
        // Une rangée qui passe à la ligne quand la place manque (petite fenêtre), au lieu d'élargir l'écran.
        HBox heat = new HBox(8, org.genesis.common.Plan.label("T"), temperature, temperatureLabel);
        heat.setAlignment(Pos.CENTER_LEFT);
        HBox sparkBox = new HBox(8, sparkName, spark, sparkLabel, sparkHint);
        sparkBox.setAlignment(Pos.CENTER_LEFT);
        solventBox.setTooltip(I18n.tooltip("Le milieu : le vide, ou un solvant implicite (ALPB de GFN1-xTB, ses "
                + "paramètres publiés) — un continuum diélectrique qui écrante les charges, avec la cavité et les liaisons "
                + "hydrogène à la surface ; les électrons y répondent"));
        solventBox.setVisibleRowCount(14);
        solventBox.setOnAction(e -> {
            if (updating || solventBox.getSelectionModel().getSelectedIndex() < 0) return;
            actions.solvent(solventBox.getSelectionModel().getSelectedIndex() - 1);
        });
        solvationLabel.getStyleClass().add("plan-note");
        solvationLabel.setTooltip(I18n.tooltip("L'énergie libre de solvatation calculée : polaire (Born généralisé), liaisons "
                + "hydrogène, cavité et dispersion, décalage du solvant"));
        HBox medium = new HBox(8, org.genesis.common.Plan.label("Milieu"), solventBox, solvationLabel);
        medium.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.FlowPane row = new javafx.scene.layout.FlowPane(14, 6, pause, segment, heat, sparkBox, medium);
        row.setAlignment(Pos.CENTER_LEFT);
        status.setWrapText(true);
        // Une ligne d'état, une seule : le reste est dans l'infobulle.
        status.setWrapText(false);
        status.setTextOverrun(OverrunStyle.ELLIPSIS);
        status.setMaxWidth(Double.MAX_VALUE);
        Tooltip statusTip = I18n.tooltip();
        status.setTooltip(statusTip);
        status.textProperty().addListener((o, was, now) -> statusTip.setText(now));
        VBox dock = new VBox(4, row, status);
        dock.setPadding(new Insets(8, 14, 8, 14));
        dock.getStyleClass().add("plan-foot");
        return dock;
    }

    private void sendTemperature() {
        int mode = dynamicsButtons[1].isSelected() ? 1 : dynamicsButtons[2].isSelected() ? 2 : 1;
        sendDynamics(mode);
    }

    private void sendDynamics(int mode) {
        request("dynamics", mode);
        request("temperature", Math.round(temperature.getValue()));
        actions.dynamics(mode, temperature.getValue());
    }

    private void request(String key, Object value) {
        pending.put(key, value);
        pendingSince.put(key, System.nanoTime());
    }

    /** La valeur à montrer : la demande en cours tant qu'aucune frame ne l'a confirmée (ni ne l'a fait attendre trop). */
    private <T> T shown(String key, T engine) {
        Object wanted = pending.get(key);
        if (wanted == null) return engine;
        if (wanted.equals(engine) || System.nanoTime() - pendingSince.get(key) > PENDING_NS) {
            pending.remove(key);
            return engine;
        }
        @SuppressWarnings("unchecked") T value = (T) wanted;
        return value;
    }

    private Region modelBanner() {
        modelText.setWrapText(true);
        modelText.setMaxWidth(640);
        validation.setHgap(18);
        validation.setVgap(4);
        Button close = button("Fermer", false);
        close.setOnAction(e -> modelBanner.setVisible(false));
        modelBanner.getChildren().addAll(text("Méthode", 14, "#FFD27A"), modelText,
                text("Calculé ↔ mesuré", 14, "#FFD27A"), validation, close);
        modelBanner.setPadding(new Insets(16));
        modelBanner.getStyleClass().add("glass-section");
        modelBanner.getStyleClass().add("plan-card");
        modelBanner.setMaxSize(700, Region.USE_PREF_SIZE);
        modelBanner.setVisible(false);
        StackPane.setAlignment(modelBanner, Pos.CENTER);
        return modelBanner;
    }

    // ================================================================== l'établi (gauche)
    private VBox workbench() {
        Button table = button("Tableau périodique…", false);
        table.setTooltip(I18n.tooltip("Tous les éléments que GFN1-xTB sait calculer : de l'hydrogène au radon (Z = 1 à 86)"));
        table.setOnAction(e -> actions.pickElement());
        chosenElement.setWrapText(true);
        Button empty = button("Établi vide", false);
        empty.setOnAction(e -> newScene(actions::clear));
        empty.setTooltip(I18n.tooltip("Tout retirer, puis construire une molécule atome par atome"));
        Label paletteHint = new Label("Choisir un élément, puis cliquer un atome pour l'y attacher (ou dans le vide).");
        paletteHint.getStyleClass().add("plan-quiet-note");
        paletteHint.setWrapText(true);
        experimentNote.setWrapText(true);
        experimentPhase.setWrapText(true);
        ToggleGroup charges = new ToggleGroup();
        HBox chargeRow = new HBox(0);
        String[] chargeNames = {"−1", "0", "+1"};
        for (int k = 0; k < 3; k++) {
            ToggleButton b = new ToggleButton(chargeNames[k]);
            b.setToggleGroup(charges);
            b.setFocusTraversable(false);
            b.getStyleClass().addAll("segment", k == 0 ? "left" : k == 2 ? "right" : "middle");
            final int charge = k - 1;
            b.setOnAction(e -> {
                b.setSelected(true);
                if (updating) return;
                request("charge", charge);
                actions.charge(charge);
            });
            chargeButtons[k] = b;
            chargeRow.getChildren().add(b);
        }
        chargeButtons[1].setSelected(true);
        VBox show = new VBox(8);
        javafx.scene.control.CheckBox bondsToggle = toggle("liaisons", true, v -> scene.showBonds = v,
                "Les paires d'électrons partagées, localisées (Pipek–Mezey). Entre deux atomes : un brin par paire "
                        + "(indice de Mayer : 1, 2, 3 ; le second en tirets pour une liaison conjuguée), à plat quand la part π "
                        + "a un côté (la liaison ne tourne pas), torsadés quand elle entoure l'axe ; couleur selon la force "
                        + "interne le long de la liaison (bleu nulle, blanc traction, orange compression), grains tirés de la "
                        + "densité de la paire. Une paire sur trois atomes ou plus (métaux, pont B–H–B) : une étoile de son "
                        + "barycentre vers ses atomes. Deux ions sans paire partagée (NaCl) : des pointillés.");
        javafx.scene.control.CheckBox arcsToggle = toggle("arcs des atomes", true, v -> scene.showInner = v,
                "Dans chaque bulle, des arcs partis du noyau, captés par les liaisons et penchés par la déformation du "
                        + "nuage de l'atome (son dipôle calculé)");
        javafx.scene.control.CheckBox contacts = toggle("attractions et répulsions sans liaison", true, v -> scene.showContacts = v,
                "Entre atomes qui ne partagent pas de paire d'électrons (au moins quatre liaisons d'écart, deux molécules, "
                        + "ou deux ions) : des points qui glissent vers le milieu quand la force calculée les attire ; "
                        + "quand elle les repousse, des points orange qui partent de là où leurs nuages se touchent et "
                        + "s'écartent vers les deux atomes, d'autant plus vite et plus vifs que la force est grande");
        javafx.scene.control.CheckBox envelope = toggle("halo de polarisation", true, v -> scene.showHalo = v,
                "Le nuage des électrons de valence de chaque molécule, vu en transparence ; son bord : là où le regard "
                        + "croise 0,3 électron par Å². Chaque atome y apporte les électrons qu'il garde (charge de Mulliken) : "
                        + "fourni et magenta autour des atomes δ−, mince et cyan autour des atomes δ+");
        javafx.scene.control.CheckBox potential = toggle("carte de potentiel (0,002 u.a.)", false, v -> {
                    scene.showPotential = v;
                    scene.retint();
                    // Le moteur ne calcule la grille (densité et potentiel) que lorsqu'une vue la montre.
                    actions.view(viewKind, viewKind == 2 ? viewOrbital : -1, v);
                },
                "La convention des cartes de potentiel électrostatique : le potentiel exact de la molécule (noyaux et "
                        + "nuage) sur la surface ρ = 0,002 électron par bohr³ ; magenta négatif, cyan positif (±2,5 V)");
        javafx.scene.control.CheckBox lines = toggle("champ électrique", false, v -> {
                    scene.showField = v;
                    scene.computeField();
                },
                "Les lignes du champ des charges partielles, du + vers le −, en nombre proportionnel à la charge (Gauss)");
        javafx.scene.control.CheckBox arrows = toggle("flèche de la force nette", false, v -> scene.showArrows = v,
                "Une seule flèche par atome : la somme de toutes les forces sur son noyau");
        javafx.scene.control.CheckBox charge = toggle("valeurs des charges δ", false, v -> scene.showCharges = v,
                "Charges de Mulliken : δ− là où les électrons s'accumulent, δ+ là où ils manquent (la couleur des "
                        + "bulles les montre déjà)");
        javafx.scene.control.CheckBox orders = toggle("indices de liaison (Mayer)", false, v -> scene.showOrders = v,
                "Le nombre de paires d'électrons partagées : ≈ 1 simple, 2 double, 3 triple");
        javafx.scene.control.CheckBox symbols = toggle("symboles", true, v -> scene.showSymbols = v, "Le symbole de chaque élément");
        javafx.scene.control.CheckBox names = toggle("noms des molécules", true, v -> scene.showNames = v,
                "Le nom de chaque molécule que le dictionnaire reconnaît (d'après qui est lié à qui, et sa charge) ; un "
                        + "cercle vert quand une espèce vient de se former");
        javafx.scene.control.CheckBox legend = toggle("légende", true, v -> scene.showLegend = v, "Ce que veut dire chaque signe de la scène");
        show.getChildren().addAll(envelope, bondsToggle, arcsToggle, contacts, potential, lines, arrows, charge, orders,
                symbols, names, legend);
        table.setMaxWidth(Double.MAX_VALUE);
        empty.setMaxWidth(Double.MAX_VALUE);
        HBox tools = new HBox(6, table, empty);
        HBox.setHgrow(table, Priority.ALWAYS);
        HBox.setHgrow(empty, Priority.ALWAYS);
        Label reactorNote = new Label("Des molécules serrées dans une sphère : chauffer, une étincelle ou le piston pour qu'elles réagissent.");
        reactorNote.getStyleClass().add("plan-quiet-note");
        reactorNote.setWrapText(true);
        reactorNote.setTooltip(org.genesis.common.Plan.tooltip(REACTOR_HINT));
        Label experimentLead = new Label("Deux réactifs relaxés, lancés l'un vers l'autre ; le dictionnaire reconnaît les produits.");
        experimentLead.getStyleClass().add("plan-quiet-note");
        experimentLead.setWrapText(true);
        library = new org.genesis.common.Tabs(false, new String[]{"Molécules", "Expériences", "Réacteur", "Établi"},
                page(presetBox),
                page(experimentLead, experimentBox, experimentPhase, experimentNote),
                page(reactorNote, reactionBox, pistonRow(), enclosure),
                page(paletteBox, tools, chosenElement, paletteHint, org.genesis.common.Plan.row("Charge totale", chargeRow)));
        library.button(0).setTooltip(org.genesis.common.Plan.tooltip("Des molécules toutes faites, relaxées par le calcul"));
        library.button(1).setTooltip(org.genesis.common.Plan.tooltip("Cinq réactions réelles, lancées et suivies pas à pas"));
        library.button(2).setTooltip(org.genesis.common.Plan.tooltip("Des mélanges dans une enceinte, le piston"));
        library.button(3).setTooltip(org.genesis.common.Plan.tooltip("Construire atome par atome, n'importe quel élément de H à Rn"));
        library.bar().getStyleClass().add("compact");
        VBox layers = org.genesis.common.Plan.foldable("Calques de la scène", false, show);
        // La marge sur un conteneur à part : celle de la section vient de la feuille de style (elle l'emporterait).
        VBox layersBox = new VBox(layers);
        layersBox.setPadding(new Insets(12, 16, 0, 16));
        layersBox.getStyleClass().add("plan-dock-top");
        VBox column = new VBox(library, layersBox);
        VBox.setVgrow(library, Priority.ALWAYS);
        return column;
    }

    /** Les pages de la colonne de gauche (molécules, expériences, réacteur, établi). */
    private org.genesis.common.Tabs library;

    /** Ce qu'est le réacteur, et ce qu'il n'est pas : une enceinte très dense, sans spin, où tout va trop vite. */
    static final String REACTOR_HINT = "Des molécules dans une sphère : chauffer, lancer une étincelle ou serrer le piston pour "
            + "franchir la barrière d'activation. Un nanoréacteur, pas une flamme : des centaines à des milliers "
            + "d'atmosphères, et O₂ sans barrière de spin. Il montre des mécanismes possibles, pas des vitesses réelles.";

    /**
     * Le piston : allumé, la paroi de l'enceinte suit la pression choisie — elle se resserre tant que les molécules la
     * poussent moins fort, s'écarte quand elles la poussent plus (voir lab.PISTON_FS). Éteint, le volume ne bouge plus.
     */
    private Region pistonRow() {
        piston.setText(I18n.text("piston"));
        piston.getStyleClass().add("glass-toggle");
        piston.setFocusTraversable(false);
        piston.setMinWidth(Region.USE_PREF_SIZE);          // « piston » en entier, le curseur se resserre
        piston.setTooltip(I18n.tooltip("Imposer la pression : la paroi devient un piston qui la tient (crée une enceinte "
                + "autour des molécules s'il n'y en a pas). 100 à 20 000 atm, soit 0,01 à 2 GPa : en dessous, les "
                + "molécules se rencontreraient trop rarement pour qu'on le voie (à 1 atm, une fois par heure de calcul)"));
        piston.setOnAction(e -> sendPressure());
        pressure.setPrefWidth(96);
        pressure.setMinWidth(40);
        pressure.setFocusTraversable(false);
        pressure.valueProperty().addListener((o, a, b) -> {
            pressureLabel.setText(MoleculeFormat.atm(roundPressure(b.doubleValue())));
            if (!updating && !pressure.isValueChanging() && piston.isSelected()) sendPressure();
        });
        pressure.valueChangingProperty().addListener((o, a, changing) -> {
            if (!changing && !updating && piston.isSelected()) sendPressure();
        });
        pressureLabel.setText(MoleculeFormat.atm(3000));
        pressureLabel.setMinWidth(Region.USE_PREF_SIZE);
        enclosure.setWrapText(true);
        enclosure.setTooltip(enclosureTip);
        HBox row = new HBox(6, piston, pressure, pressureLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pressure, Priority.ALWAYS);
        return row;
    }

    /** La pression du curseur (échelle logarithmique), arrondie à deux chiffres : 3 000, 3 200, 12 000 atm. */
    private static double roundPressure(double log10) {
        double atm = Math.pow(10, log10), unit = Math.pow(10, Math.floor(Math.log10(atm)) - 1);
        return Math.round(atm / unit) * unit;
    }

    private void sendPressure() {
        double atm = piston.isSelected() ? roundPressure(pressure.getValue()) : 0;
        request("pressure", atm);
        actions.pressure(atm);
    }

    /**
     * Ce que mesure l'enceinte : la pression sur sa paroi (la force des atomes divisée par sa surface, moyennée), son
     * volume, le gaz parfait NkT/V pour comparer (N molécules), et le temps moyen entre deux rencontres d'une molécule
     * (théorie cinétique : 1 / (√2 n σ v̄), σ = π (3 Å)²) — en temps de calcul et à l'écran, au rythme du moteur.
     */
    /** L'expérience en cours : sa ligne allumée, la phase (approche, relais du thermostat, réussie ou manquée), et ce
     *  qu'on regarde. Sans expérience : ce qu'elles sont. */
    private void experiment(MoleculeLabState s) {
        int active = s.getExperiment();
        for (int i = 0; i < experimentButtons.size(); i++) {
            var classes = experimentButtons.get(i).getStyleClass();
            classes.remove("plan-row-on");
            if (i == active) classes.add("plan-row-on");
        }
        boolean running = active >= 0 && active < s.getExperimentsCount();
        String phase = "", color = "#C7D6EA";
        if (running) {
            MoleculeExperiment x = s.getExperiments(active);
            String products = x.getGoalList().stream().map(I18n::text).reduce((a, b) -> a + " + " + b).orElse("");
            switch (s.getExperimentPhase()) {
                case 2 -> {
                    phase = "✓ " + I18n.text("réussie") + " · " + products + " · "
                            + MoleculeFormat.decimal(s.getExperimentTimeFs(), 0) + " fs";
                    color = "#8CF0B4";
                }
                case 3 -> {
                    phase = "✗ " + I18n.text("manquée : rien ne s'est formé ; relancez, l'agitation change");
                    color = "#FFB38A";
                }
                case 1 -> phase = "● ● ○  " + I18n.text("le thermostat emporte l'énergie du choc") + " · "
                        + MoleculeFormat.decimal(s.getTimeFs(), 0) + " fs";
                default -> phase = "● ○ ○  " + I18n.text("les réactifs foncent l'un vers l'autre") + " · "
                        + MoleculeFormat.decimal(s.getTimeFs(), 0) + " fs";
            }
            experimentNote.setText(I18n.text(x.getExplanation()));
        } else {
            experimentNote.setText("");
        }
        experimentPhase.setText(phase);
        experimentPhase.setStyle("-fx-text-fill:" + color + ";-fx-font-size:11.5px;");
        experimentPhase.setVisible(running);
        experimentPhase.setManaged(running);
        experimentNote.setVisible(running);
        experimentNote.setManaged(running);
    }

    private void enclosure(MoleculeLabState s) {
        boolean shown = s.getWall() > 0 && s.getAtomsCount() > 0;
        enclosure.setVisible(shown);
        enclosure.setManaged(shown);
        if (!shown) return;
        double radius = s.getWall(), volume = 4 / 3.0 * Math.PI * radius * radius * radius;
        int molecules = Math.max(1, scene.molecules());
        // La température du gaz : celle du thermostat (la moyenne de l'agitation), sinon l'instantanée.
        double kelvin = Math.max(1, s.getDynamics() == 1 ? s.getTemperatureTarget() : s.getTemperature()), mass = 0;
        for (int i = 0; i < s.getAtomsCount(); i++) mass += s.getAtoms(i).getMassU();
        double ideal = molecules * 1.380649e-23 * kelvin / (volume * 1e-30) / 101325;
        double speed = Math.sqrt(8 * 1.380649e-23 * kelvin / (Math.PI * mass / molecules * 1.66053907e-27)) * 1e-5;   // Å/fs
        double between = 1 / (Math.sqrt(2) * molecules / volume * Math.PI * 9 * speed);                                // fs
        double measured = s.getPressure(), wanted = s.getPressureTarget();
        StringBuilder b = new StringBuilder();
        b.append(I18n.text("paroi")).append(" ").append(MoleculeFormat.atm(measured)).append(" ").append(I18n.text("mesurée"));
        if (wanted > 0) {
            b.append(" · ").append(I18n.text("consigne du piston")).append(" ").append(MoleculeFormat.atm(wanted));
        }
        b.append('\n').append(I18n.text("gaz parfait NkT/V")).append(" ").append(MoleculeFormat.atm(ideal));
        // Le facteur de compressibilité, l'écart au gaz parfait : sur la moyenne longue du moteur, et seulement quand la
        // paroi a reçu assez de chocs (30 : ±20 % environ) — en gaz dilué, une moyenne de 200 fs n'en compte qu'un ou
        // deux. Au-dessus de 1, les molécules se gênent (leur volume propre, leur répulsion) ; en dessous, elles s'attirent.
        int hits = s.getWallHits();
        double z = Double.NaN;
        String meaning = "";
        if (hits >= 30 && s.getPressureMean() > 0 && s.getVolumeMean() > 0) {
            z = s.getPressureMean() * 101325 * s.getVolumeMean() * 1e-30 / (molecules * 1.380649e-23 * kelvin);
            b.append('\n').append("Z = PV/NkT = ").append(MoleculeFormat.decimal(z, 2)).append(" ")
                    .append(I18n.text("sur")).append(" ").append(MoleculeFormat.rough(s.getPressureSpanFs()))
                    .append(" (").append(hits).append(" ").append(I18n.text("chocs")).append(")");
            meaning = I18n.text(z > 1.15 ? "plus que le gaz parfait : les molécules se gênent (leur volume propre, leur répulsion)"
                    : z < .85 ? "moins que le gaz parfait : les molécules s'attirent" : "presque le gaz parfait");
        } else {
            b.append('\n').append("Z = PV/NkT : ").append(hits).append(" ").append(I18n.text("chocs, trop peu pour le dire"));
        }
        b.append('\n').append("V ").append(MoleculeFormat.decimal(volume, 0)).append(" Å³ (R ").append(MoleculeFormat.decimal(radius, 2))
                .append(" Å) · ").append(molecules).append(" ").append(I18n.text(molecules > 1 ? "molécules" : "molécule"));
        // La fréquence des rencontres vient de la théorie des gaz : à densité de liquide (Z > 2), elle n'a plus de sens.
        boolean dense = !Double.isNaN(z) ? z > 2 : measured > 2 * ideal;
        if (molecules > 1 && dense) {
            b.append('\n').append(I18n.text("densité de liquide"));
        } else if (molecules > 1) {
            b.append('\n').append(I18n.text("une rencontre toutes les")).append(" ~").append(MoleculeFormat.rough(between));
            if (s.getStepsPerSecond() > 0) {
                double screen = between / (s.getStepsPerSecond() * 0.25);
                b.append(" (").append(MoleculeFormat.decimal(screen, screen < 10 ? 1 : 0)).append(" ").append(I18n.text("s d'écran")).append(")");
            }
        }
        enclosure.setText(b.toString());
        enclosureTip.setText(meaning.isEmpty() ? I18n.text(pistonMotion(measured, wanted)) : meaning);
    }

    /**
     * Où en est le piston : il rapproche la pression de sa consigne, ou il y est — à 25 % près, car avec deux ou trois
     * molécules la pression fluctue autour de sa moyenne.
     */
    static String pistonMotion(double measured, double wanted) {
        if (measured < .75 * wanted) return "la paroi se resserre : la pression monte vers la consigne";
        if (measured > 1.25 * wanted) return "la paroi s'écarte : la pression descend vers la consigne";
        return "à l'équilibre : la pression fluctue autour de la consigne";
    }

    private javafx.scene.control.CheckBox toggle(String label, boolean on, java.util.function.Consumer<Boolean> set, String tip) {
        javafx.scene.control.CheckBox b = new javafx.scene.control.CheckBox(label);
        b.setFocusTraversable(false);
        b.setSelected(on);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setTooltip(org.genesis.common.Plan.tooltip(tip));
        b.setOnAction(e -> set.accept(b.isSelected()));
        return b;
    }

    private static String web(Color c) {
        return String.format("#%02X%02X%02X", (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }

    // ================================================================== les lectures (droite)
    private VBox readings() {
        orbitalLine.setWrapText(true);
        speciesLines.setWrapText(true);
        eventLines.setWrapText(true);
        atomCard.setWrapText(true);
        isotopeRow.setAlignment(Pos.CENTER_LEFT);
        isotopeRow.setVisible(false);
        isotopeRow.setManaged(false);
        isotopeAll.getStyleClass().add("chip");
        isotopeAll.setFocusTraversable(false);
        isotopeAll.setTooltip(I18n.tooltip("Le même isotope pour tous les atomes de cet élément (D₂O : les deux H)"));
        Button compute = button("Calculer les vibrations", false);
        compute.setTooltip(I18n.tooltip("La hessienne (dérivées secondes de l'énergie) par différences des forces : "
                + "les fréquences propres et la forme de chaque mode"));
        compute.setOnAction(e -> actions.modes());
        quanta.setFocusTraversable(false);
        quanta.setMajorTickUnit(1);
        quanta.setSnapToTicks(true);
        quanta.valueProperty().addListener((o, a, b) -> quantaLabel.setText(MoleculeFormat.decimal(b.doubleValue(), 0) + " hν"));
        quantaLabel.setText("1 hν");
        HBox quantaRow = new HBox(8, text("énergie donnée", 10.5, "#8FB3D9"), quanta, quantaLabel);
        quantaRow.setAlignment(Pos.CENTER_LEFT);
        // La limite de GFN1 sur les énergies de liaison : sous le « ? » du bilan, pas dans le panneau.
        chart.setWidth(org.genesis.common.Plan.RIGHT_WIDTH - 34);
        Label info = org.genesis.common.Plan.why("Ce que vaut ce bilan", "GFN1-xTB surestime les énergies de liaison par "
                + "rapport aux atomes isolés : 1,3 à 2,2 fois selon la molécule (CH₄ + 2 O₂ : 47,8 eV au lieu de 28,6). "
                + "Les énergies de réaction aussi : CH₄ + 2 O₂ → CO₂ + 2 H₂O donne −15,5 eV au lieu de −8,5. "
                + "Un décalage par élément ne le corrigerait pas : une réaction garde ses atomes. "
                + "Les géométries sont fiables (voir Méthode). La cohésion et sa balance sont comptées depuis les atomes "
                + "isolés et neutres, calculés par le même modèle.", null);
        VBox energy = org.genesis.common.Plan.trailing(org.genesis.common.Plan.section("Bilan énergétique", balance, chart), info);
        VBox speciesSection = org.genesis.common.Plan.section("Espèces présentes", speciesLines, dipoleLine, eventLines);
        VBox species = new VBox(speciesSection);
        species.setPadding(new Insets(16, 16, 0, 16));
        readingTabs = new org.genesis.common.Tabs(false, new String[]{"Énergie", "Orbitales", "Atome", "Vibrations"},
                page(energy),
                page(org.genesis.common.Plan.section("Niveaux d'énergie", levels, levelKey(), orbitalLine)),
                page(org.genesis.common.Plan.section("L'atome choisi", atomCard, atomCanvas, isotopeRow)),
                page(org.genesis.common.Plan.section("Modes de vibration", compute, modeHeader, quantaRow, modeList)));
        VBox column = new VBox(species, readingTabs);
        VBox.setVgrow(readingTabs, Priority.ALWAYS);
        return column;
    }

    /** Les pages de la colonne de droite (énergie, orbitales, atome, vibrations). */
    private org.genesis.common.Tabs readingTabs;

    // ================================================================== la souris
    private void press(MouseEvent e) {
        lastX = pressX = e.getX();
        lastY = pressY = e.getY();
        rotating = false;
        // Le titre de la légende la replie ou la déplie : ni sélection, ni rotation, ni atome posé.
        legendPress = e.getButton() == MouseButton.PRIMARY && scene.onLegendTitle(e.getX(), e.getY());
        if (legendPress) {
            scene.legendFolded = !scene.legendFolded;
            pressedAtom = -1;
            return;
        }
        pressedAtom = scene.pick(e.getX(), e.getY());
        holder.requestFocus();
        // Clic droit, milieu, ou Maj + clic : glissés, ils déplacent la vue ; le clic droit seul ouvrira la fiche.
        panPress = e.getButton() == MouseButton.SECONDARY || e.getButton() == MouseButton.MIDDLE
                || (e.getButton() == MouseButton.PRIMARY && e.isShiftDown());
        panning = false;
        if (panPress) return;
        closeCard();
        // Ctrl : tourner la vue même en partant d'un atome (zoomé de près, les atomes couvrent tout).
        if (pressedAtom >= 0 && paletteGroup.getSelectedToggle() == null && !e.isShortcutDown()) {
            dragging = pressedAtom;
            double[] p = scene.positions[dragging];
            dragPlane = new double[]{p[0], p[1], p[2]};
            holder.setCursor(Cursor.CLOSED_HAND);
        } else {
            holder.setCursor(Cursor.CLOSED_HAND);
        }
    }

    private void drag(MouseEvent e) {
        if (legendPress) return;
        double dx = e.getX() - lastX, dy = e.getY() - lastY;
        lastX = e.getX();
        lastY = e.getY();
        if (panPress) {
            if (Math.hypot(e.getX() - pressX, e.getY() - pressY) > 3) panning = true;
            if (panning) {
                holder.setCursor(Cursor.MOVE);
                scene.panBy(dx, dy);
            }
            return;
        }
        if (dragging >= 0) {
            double[] target = scene.unproject(e.getX(), e.getY(), dragPlane);
            if (target == null) return;
            scene.dragged = dragging;
            scene.dragTarget = target;
            double now = System.nanoTime() * 1e-9;
            if (now - lastDragSent > 0.03) {
                actions.drag(dragging, target);
                lastDragSent = now;
            }
            return;
        }
        if (Math.hypot(e.getX() - pressX, e.getY() - pressY) > 3) rotating = true;
        if (rotating) scene.rotateBy(dx, dy);       // la caméra est à vous : les vues ne la tournent plus
    }

    private void release(MouseEvent e) {
        holder.setCursor(Cursor.OPEN_HAND);
        // Le geste est fini : la mire s'efface, la vue peut de nouveau glisser vers une vue visée (Tout voir). Sans cela
        // la mire restait au milieu de la scène jusqu'au clic suivant.
        rotating = false;
        if (legendPress) {
            legendPress = false;
            return;
        }
        boolean click = Math.hypot(e.getX() - pressX, e.getY() - pressY) <= 3;
        if (panPress) {
            panPress = false;
            if (!panning && e.getButton() == MouseButton.SECONDARY) {
                if (pressedAtom >= 0) openCard(pressedAtom, e.getX(), e.getY());
                else closeCard();
            } else if (!panning && e.getButton() == MouseButton.PRIMARY && pressedAtom >= 0) {
                select(pressedAtom);                // Maj + clic, sans glisser : un clic ordinaire
            }
            panning = false;
            return;
        }
        if (dragging >= 0) {
            if (!click) actions.release();
            else if (e.getClickCount() == 2) centreOn(dragging);
            else select(dragging);
            dragging = -1;
            scene.dragged = -1;
            scene.dragTarget = null;
            return;
        }
        if (!click || e.getButton() != MouseButton.PRIMARY) return;
        if (e.getClickCount() == 2 && pressedAtom < 0) {
            scene.showAll();
            return;
        }
        if (e.getClickCount() == 2 && paletteGroup.getSelectedToggle() == null) {
            centreOn(pressedAtom);                  // Ctrl + double-clic sur un atome
            return;
        }
        ToggleButton element = (ToggleButton) paletteGroup.getSelectedToggle();
        if (element != null) {
            int z = (int) element.getUserData();
            if (pressedAtom >= 0) actions.addAtom(z, pressedAtom, null);
            else actions.addAtom(z, -1, scene.positions.length == 0 ? new double[]{0, 0, 0}
                    : scene.unproject(e.getX(), e.getY(), scene.center()));
            return;
        }
        if (pressedAtom >= 0) select(pressedAtom);
    }

    private void refreshHover() {
        int i = scene.hovered;
        if (state == null || i < 0 || i >= state.getAtomsCount() || dragging >= 0 || moleculeCard.getNode().isVisible()) {
            hoverCard.setVisible(false);
            return;
        }
        AtomCard.draw(hoverCard, state.getAtoms(i), scene, i, state.getConfinedList().contains(i));
        double x = hoverX + 18, y = hoverY + 14;
        if (x + hoverCard.getWidth() > holder.getWidth() - 8) x = hoverX - hoverCard.getWidth() - 14;
        if (y + hoverCard.getHeight() > holder.getHeight() - 8) y = hoverY - hoverCard.getHeight() - 10;
        hoverCard.relocate(x, y);
        hoverCard.setVisible(true);
    }

    private void select(int atom) {
        scene.selected = scene.selected == atom ? -1 : atom;
        if (scene.selected >= 0 && readingTabs != null) readingTabs.select(2);
        refreshAtomCard();
        drawSketch();
    }

    /** Une autre scène est demandée : la vue revient à l'ensemble, et se recadrera sur elle à son arrivée. */
    private void newScene(Runnable request) {
        freshScene = true;
        scene.showAll();
        request.run();
    }

    /** Le double-clic sur un atome : il est choisi, la vue se centre sur lui et le suit. */
    private void centreOn(int atom) {
        if (atom < 0) return;
        if (scene.selected != atom) select(atom);
        scene.focusOn(new int[]{atom}, false);
    }

    /** Les boutons + et − (et les touches) : un cran de molette, vers le point visé. */
    private void zoomStep(int notches) {
        double x = scene.camera.centerX(), y = scene.camera.centerY();
        scene.zoomAt(x, y, notches * 1.5);
    }

    /**
     * Le bloc de navigation : ce que la vue suit (toutes les molécules, un atome, une molécule, ou là où la main l'a
     * menée), le zoom et le retour à la vue d'ensemble. Ses bulles d'aide disent tous les gestes de la souris.
     */
    private HBox navigation() {
        Button out = button("−", false), in = button("+", false), all = button("Tout voir", false);
        for (Button b : new Button[]{out, in}) b.setMinWidth(30);
        out.setOnAction(e -> zoomStep(-1));
        in.setOnAction(e -> zoomStep(1));
        all.setOnAction(e -> scene.showAll());
        out.setTooltip(I18n.tooltip("S'éloigner (touche −, ou la molette)"));
        in.setTooltip(I18n.tooltip("Se rapprocher du centre de la vue (touche +, ou la molette : vers le curseur)"));
        all.setTooltip(I18n.tooltip("Revenir à toutes les molécules, cadrées et de face (touche F, ou double-clic dans le vide)"));
        Tooltip gestures = I18n.tooltip("Glisser : tourner la vue autour du point visé (la mire) · Ctrl + glisser : tourner "
                + "même en partant d'un atome · clic droit glissé, ou Maj + glisser : déplacer la vue · molette : zoomer "
                + "vers le curseur · double-clic sur un atome : le centrer et le suivre · double-clic dans le vide : tout voir");
        gestures.setWrapText(true);
        gestures.setMaxWidth(340);
        viewLine.setMinWidth(Region.USE_PREF_SIZE);
        HBox box = new HBox(4, viewLine, out, in, all);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.setPadding(new Insets(3, 4, 3, 10));
        box.getStyleClass().add("plan-overlay");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Tooltip.install(box, gestures);             // les boutons gardent la leur
        return box;
    }

    /** La ligne du bloc de navigation : ce que la vue suit, et son cadre (la hauteur de vue, en Å). */
    private void refreshViewLine() {
        int[] focus = scene.focus();
        String what;
        if (focus.length == 1 && focus[0] < scene.symbols.length) {
            what = I18n.text("centrée sur") + " " + scene.symbols[focus[0]] + (focus[0] + 1);
        } else if (focus.length > 1) {
            what = I18n.text("centrée sur une molécule");
        } else {
            what = I18n.text(scene.navigated ? "menée à la main" : "toutes les molécules");
        }
        double size = Math.pow(10, scene.camera.zoom);
        String text = "ⓘ " + I18n.text("vue :") + " " + what + " · " + MoleculeFormat.decimal(size, size < 10 ? 1 : 0) + " Å";
        if (!text.equals(viewLine.getText())) viewLine.setText(text);
    }

    /** Aperçus : le survol de cet atome (sa carte et ses flèches) ou sa fiche de molécule (clic droit). */
    void preview(int atom, boolean card) {
        if (atom < 0 || atom >= scene.positions.length) return;
        double[] p = scene.positions[atom], s = scene.camera.project(p[0], p[1], p[2]);
        if (s == null) return;
        if (card) {
            openCard(atom, s[0], s[1]);
            return;
        }
        scene.hovered = atom;
        hoverX = s[0];
        hoverY = s[1];
        refreshHover();
    }

    /** Aperçus : la vignette avec tous ses hydrogènes. */
    void sketchAllHydrogens() {
        sketch.explicitH = true;
        drawSketch();
    }

    private void drawSketch() {
        if (state == null || !state.getReady()) return;
        sketch.draw(sketchCanvas, scene, state, instruction());
    }

    /** La consigne de la vignette : ce que l'on peut faire maintenant, d'après le geste en cours. */
    private String instruction() {
        ToggleButton element = (ToggleButton) paletteGroup.getSelectedToggle();
        int i = scene.selected;
        String name = i >= 0 && i < scene.symbols.length ? scene.symbols[i] + (i + 1) : "";
        if (dragging >= 0 || (state != null && state.getDragAtom() >= 0)) {
            return I18n.text("relâcher : elle se détend, ou casse si on a trop tiré");
        }
        if (element != null) return I18n.text("cliquer un atome pour y attacher") + " " + element.getText() + I18n.text(", ou dans le vide");
        if (viewKind == 2) return I18n.text("cliquer un cercle de la cible (07) : une autre orbitale");
        if (!name.isEmpty()) return I18n.text("clic droit sur") + " " + name + I18n.text(" : la fiche de sa molécule");
        return I18n.text("cliquer un atome : sa carte (09) · le saisir : le tirer");
    }

    /** La fiche de la molécule de `atom`, posée près du clic (dans le cadre de la scène). */
    void openCard(int atom, double x, double y) {
        if (state == null || atom < 0 || atom >= scene.positions.length) return;
        moleculeCard.show(state, scene, atom);
        Region card = moleculeCard.getNode();
        card.applyCss();
        card.autosize();
        double w = card.getWidth(), h = card.getHeight();
        // À côté de la molécule (sa boîte à l'écran), à droite s'il y a la place, sinon à gauche ; au-dessus du dock.
        double minX = x, maxX = x;
        for (int a : moleculeCard.atoms()) {
            double[] p = scene.positions[a], s = scene.camera.project(p[0], p[1], p[2]);
            if (s == null) continue;
            double r = scene.sphere(a) * s[3] + 6;
            minX = Math.min(minX, s[0] - r);
            maxX = Math.max(maxX, s[0] + r);
        }
        double left = maxX + 14 + w <= holder.getWidth() - 8 ? maxX + 14 : minX - 14 - w;
        if (left < 8) left = Math.max(8, Math.min(holder.getWidth() - w - 8, x + 16));
        double floor = holder.getHeight() - 14;
        double top = Math.max(8, Math.min(y - h / 3, floor - h));
        card.relocate(left, top);
        card.setVisible(true);
        hoverCard.setVisible(false);
    }

    private void closeCard() {
        moleculeCard.getNode().setVisible(false);
    }

    /** La fiche suit la frame (charges, distances) ; elle se ferme si ses atomes ne sont plus là. */
    private void refreshCard(MoleculeLabState s) {
        if (!moleculeCard.getNode().isVisible()) return;
        int[] atoms = moleculeCard.atoms();
        if (atoms.length == 0 || atoms[atoms.length - 1] >= scene.positions.length || moleculeCard.atom() >= scene.positions.length) {
            closeCard();
            return;
        }
        moleculeCard.refresh(s, scene);
    }

    private void pickLevel(int index) {
        if (index < 0) return;
        viewOrbital = index;
        viewButtons[2].setSelected(true);
        request("view", 2);
        request("orbital", index);
        viewKind = 2;
        scene.mode(2, index);
        modeLine();
        actions.view(2, index, scene.showPotential);
    }

    /**
     * La ligne de la vue, sous les espèces : pour Δρ, ce que la carte montre ; pour ψ, l'orbitale montrée — son nom,
     * son énergie, ses électrons, et ce dont elle est faite (populations de Mulliken, parts d'au moins 5 %).
     */
    private void modeLine() {
        String text = "";
        if (viewKind == 1) {
            text = I18n.text("Δρ = ρ(molécule) − ρ(atomes libres), vue en transparence : où les électrons sont allés en formant les liaisons");
        } else if (viewKind == 2 && viewOrbital >= 0 && viewOrbital < orbitals.size()) {
            MoleculeOrbital o = orbitals.get(viewOrbital);
            int lumo = homo + 1;
            String name = viewOrbital == homo ? "HOMO" : viewOrbital == lumo ? "LUMO"
                    : viewOrbital < homo ? "HOMO−" + (homo - viewOrbital) : "LUMO+" + (viewOrbital - lumo);
            double occupation = o.getOccupation();
            String electrons = occupation >= 1.95 ? I18n.text("2 électrons") : occupation <= .05 ? I18n.text("vide")
                    : MoleculeFormat.decimal(occupation, 2) + " " + I18n.text(occupation >= 2 ? "électrons" : "électron");
            StringBuilder b = new StringBuilder(name).append("  ·  ψ").append(viewOrbital + 1).append("  ·  ")
                    .append(MoleculeFormat.decimal(o.getEnergyEv(), 2)).append(" eV  ·  ").append(electrons);
            if (!orbitalParts.isEmpty() && partsOrbital == viewOrbital) {
                b.append("  ·  ");
                for (int k = 0; k < orbitalParts.size(); k++) {
                    MoleculeOrbitalPart part = orbitalParts.get(k);
                    String symbol = part.getAtom() < scene.symbols.length ? scene.symbols[part.getAtom()] : "?";
                    b.append(k > 0 ? ", " : "").append(symbol).append(part.getAtom() + 1).append(' ').append(part.getShell())
                            .append(' ').append(MoleculeFormat.decimal(100 * part.getShare(), 0)).append(" %");
                }
            }
            text = b.toString();
        }
        modeLine.setText(text);
        modeLine.setVisible(!text.isEmpty());
        modeLine.setManaged(!text.isEmpty());
    }

    // ================================================================== les frames
    public void applyFrame(UniverseFrame frame) {
        MoleculeLabState s = frame.getMoleculeLab();
        assembler.update(s);
        if (s.hasScheme()) lastScheme = merged(s.getScheme(), lastScheme);
        schemeEditor.update(s, lastScheme);
        schemePlayer.update(s, lastScheme);
        schemeStage.update(lastScheme);
        playPath(s);
        if (pendingScene >= 0) {
            // La scène 3D s'ouvre quand le moteur y joue l'étape demandée (au plus tard après 8 s).
            boolean arrived = s.hasPlay() && s.getPlay().getStep() == pendingScene;
            if (!schemeStage.isOpen()) {
                pendingScene = -1;
            } else if (arrived || System.nanoTime() - sceneAskedAt > 8e9) {
                pendingScene = -1;
                schemeStage.opening(false);
                showStage(false, -1);
            }
        }
        if (!s.getActive()) return;
        state = s;
        updating = true;
        try {
            if (s.getPresetsCount() > 0) catalog(s);
            if (!s.getModel().isEmpty()) modelText.setText(s.getModel());
            if (s.getValidationCount() > 0) fillValidation(s.getValidationList());
            if (!s.getReady()) {
                status.setText(I18n.text(s.getStatus()));
                return;
            }
            for (MoleculeCloud c : s.getCloudsList()) {
                float[] projected = new float[c.getProjectedCount()];
                for (int i = 0; i < projected.length; i++) projected[i] = c.getProjected(i);
                scene.halo.cloud(c.getZ(), new MoleculeHalo.Cloud(c.getElectrons(), c.getStep(), projected));
                scene.atomRadii.put(c.getZ(), new double[]{c.getSurface(), c.getPRadius()});
                isotopeChoices.put(c.getZ(), c.getIsotopesList());
            }
            atoms(s);
            // La vue d'abord : c'est elle, pas la dernière grille reçue, qui dit quoi dessiner ; pour ψ, l'orbitale du
            // moteur (sa HOMO tant qu'il n'en a pas d'autre ; il y revient quand la molécule change), ou celle qu'on
            // vient de choisir dans le diagramme.
            String molecule = s.getAtomsList().stream().map(a -> String.valueOf(a.getZ())).reduce("", (a, b) -> a + "," + b);
            if (!molecule.equals(moleculeSignature)) {
                moleculeSignature = molecule;
                orbitalParts = List.of();
            }
            orbitals = s.getOrbitalsList();
            homo = s.getHomo();
            viewOrbital = shown("orbital", s.getViewOrbital() >= 0 ? s.getViewOrbital() : homo);
            viewKind = shown("view", s.getView());
            for (int k = 0; k < 3; k++) viewButtons[k].setSelected(viewKind == k);
            scene.mode(viewKind, viewKind == 2 ? viewOrbital : -1);
            if (s.hasGrid() && s.getGrid().getVersion() > 0) {
                DensityGrid grid = DensityGrid.of(s.getGrid());
                scene.setGrid(grid, DensityGrid.bonds(s.getGrid()), DensityGrid.potential(s.getGrid()));
                if (grid != null && scene.grid() == grid && grid.kind == DensityGrid.ORBITAL) {
                    // De quoi est faite l'orbitale que l'on voit (envoyé avec sa grille).
                    orbitalParts = s.getGrid().getPartsList();
                    partsOrbital = grid.orbital;
                }
            }
            modeLine();
            // Le titre : le nom de ce qu'on a posé, jusqu'à ce qu'une réaction change les espèces ; ensuite, ce qu'il y a.
            String present = speciesTitle(s);
            if (!s.getLabel().equals(titleLabel)) {
                titleLabel = s.getLabel();
                titleSpecies = present;
            } else if (titleSpecies.isEmpty()) {
                titleSpecies = present;
            }
            // Pendant la lecture d'un chemin, le nom de l'étape : les espèces vues image par image s'y trompaient (Co₂(CO)₈,
            // dont GFN1-xTB ne voit pas la liaison Co–Co comme une liaison ordinaire, lu « 2 C₄CoO₄ » ; les 2 CO déjà
            // parmi les réactifs en revenant en arrière).
            String shownTitle = MoleculeFormat.names(scene.playing || present.isEmpty() || present.equals(titleSpecies)
                    ? s.getLabel() : present);
            if (!shownTitle.equals(title.getText())) {
                title.setText(shownTitle);
                title.setTooltip(new javafx.scene.control.Tooltip(shownTitle));   // le nom entier, s'il est coupé
            }
            census.setText(s.getCensus().isEmpty() ? "" : I18n.text("Espèces présentes")
                    + (I18n.language() == I18n.Language.FR ? " : " : ": ") + s.getCensus());
            error.setText(s.getError());
            error.setVisible(!s.getError().isEmpty());
            error.setManaged(!s.getError().isEmpty());
            int dynamics = shown("dynamics", s.getDynamics()), charge = shown("charge", s.getCharge());
            for (int k = 0; k < 3; k++) dynamicsButtons[k].setSelected(dynamics == k);
            long kelvin = shown("temperature", Math.round(s.getTemperatureTarget()));
            if (!temperature.isValueChanging()) temperature.setValue(kelvin);
            double atm = shown("pressure", s.getPressureTarget());
            piston.setSelected(atm > 0);
            if (atm > 0 && !pressure.isValueChanging()) pressure.setValue(Math.log10(atm));
            for (int k = 0; k < 3; k++) chargeButtons[k].setSelected(charge == k - 1);
            paused = shown("pause", s.getPaused());
            pause.setText(paused ? "▶" : "Ⅱ");
            // Les panneaux de texte et de courbes : à 8 images/s pendant le mouvement (des chiffres qui changent plus
            // vite ne se lisent pas), à chaque frame quand tout est immobile. La scène, elle, suit chaque frame.
            long now = System.nanoTime();
            boolean moving = !s.getPaused() && (s.getDynamics() != 0 || !s.getRelaxed());
            if (!moving || now - panelsAt >= PANELS_NS) {
                panelsAt = now;
                energies(s);
                enclosure(s);
                experiment(s);
                drawLevels();
                species(s);
                modes(s);
                history(s);
                refreshAtomCard();
                refreshHover();
                drawSketch();
                refreshCard(s);
                status.setText(statusLine(s));
            }
            if (!s.getLabel().equals(framedLabel) || (s.getWall() > 0 && scene.positions.length != framedCount)
                    || framedCount < 0) {
                if (!s.getLabel().equals(framedLabel)) closeCard();       // une autre scène : la fiche n'a plus d'objet
                // Une autre scène se recadre entière ; un atome ajouté ou retiré laisse la vue où la main l'a menée.
                if (freshScene || framedCount < 0 || !scene.navigated) scene.frame();
                freshScene = false;
                framedLabel = s.getLabel();
                framedCount = scene.positions.length;
            }
            // Le milieu : la liste (une fois), le solvant de la scène, son énergie de solvatation.
            if (s.getSolventsCount() > 0 && solventBox.getItems().isEmpty()) {
                solventBox.getItems().add(I18n.text("le vide"));
                for (String name : s.getSolventsList()) solventBox.getItems().add(I18n.text(name));
            }
            if (!solventBox.getItems().isEmpty() && !solventBox.isShowing()
                    && solventBox.getSelectionModel().getSelectedIndex() != s.getSolvent() + 1) {
                solventBox.getSelectionModel().select(s.getSolvent() + 1);
            }
            boolean solvated = s.getSolvent() >= 0;
            solvationLabel.setVisible(solvated);
            solvationLabel.setManaged(solvated);
            if (solvated) solvationLabel.setText(String.format(I18n.locale(), "ΔG solv. %+.2f eV", s.getSolvation()));
        } finally {
            updating = false;
        }
    }

    /** Les espèces tenues, nommées, dans l'ordre où elles sont : « H₂O · eau + C₃ », « 2 H₃N · ammoniac + … ». */
    static String speciesTitle(MoleculeLabState s) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (MoleculeSpecies named : s.getNamedList()) counts.merge(named.getLabel(), 1, Integer::sum);
        StringBuilder b = new StringBuilder();
        for (var e : counts.entrySet()) {
            if (b.length() > 0) b.append(" + ");
            b.append(e.getValue() > 1 ? e.getValue() + " " : "").append(e.getKey());
        }
        return b.toString();
    }

    private void catalog(MoleculeLabState s) {
        presets.clear();
        presets.addAll(s.getPresetsList());
        presetBox.getChildren().clear();
        for (int i = 0; i < presets.size(); i++) {
            String[] parts = presets.get(i).split(" · ");
            Button b = button(parts[0], false);
            b.setTooltip(I18n.tooltip(parts.length > 1 ? parts[1] : parts[0]));
            final int index = i;
            b.setOnAction(e -> newScene(() -> actions.preset(index)));
            presetBox.getChildren().add(b);
        }
        reactions.clear();
        reactions.addAll(s.getReactionsList());
        reactionBox.getChildren().clear();
        for (int i = 0; i < reactions.size(); i++) {
            Button b = button(reactions.get(i), false);
            b.setTooltip(I18n.tooltip(REACTOR_HINT));
            final int index = i;
            b.setOnAction(e -> newScene(() -> actions.reaction(index)));
            reactionBox.getChildren().add(b);
        }
        experimentBox.getChildren().clear();
        experimentButtons.clear();
        for (int i = 0; i < s.getExperimentsCount(); i++) {
            MoleculeExperiment x = s.getExperiments(i);
            Label name = text(I18n.text(x.getTitle()), 12, "#ECEFF3"), equation = text(I18n.text(x.getEquation()), 10.5, "#8FD8FF");
            name.setStyle(name.getStyle() + "-fx-font-weight:bold;");
            equation.setWrapText(true);
            Label rank = text(String.valueOf(i + 1), 10, "#36CDF2");
            rank.setMinWidth(12);
            // Une ligne cliquable plutôt qu'un bouton : un texte qui passe à la ligne dans le graphique d'un bouton lui
            // donne une hauteur calculée sans sa largeur (des boutons hauts de 400 px).
            VBox words = new VBox(1, name, equation);
            HBox.setHgrow(words, Priority.ALWAYS);
            HBox row = new HBox(8, rank, words);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 10, 6, 8));
            row.getStyleClass().add("glass-button");
            row.setCursor(Cursor.HAND);
            Tooltip.install(row, I18n.tooltip(x.getExplanation()));
            final int index = i;
            row.setOnMouseClicked(e -> newScene(() -> actions.experiment(index)));
            experimentButtons.add(row);
            experimentBox.getChildren().add(row);
        }
        paletteBox.getChildren().clear();
        for (int z : s.getPaletteList()) paletteBox.getChildren().add(elementChip(z, symbolOf(z)));
        supported.clear();
        supported.addAll(s.getSupportedList());
    }

    static String symbolOf(int z) {
        String[] list = ("X H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Sc Ti V Cr Mn Fe Co Ni Cu Zn Ga Ge As "
                + "Se Br Kr Rb Sr Y Zr Nb Mo Tc Ru Rh Pd Ag Cd In Sn Sb Te I Xe").split(" ");
        return z < list.length ? list[z] : "Z" + z;
    }

    private void fillValidation(List<LabFact> facts) {
        validation.getChildren().clear();
        validation.add(text("grandeur", 10.5, "#8FB3D9"), 0, 0);
        validation.add(text("GFN1-xTB (calculé ici)", 10.5, "#8FB3D9"), 1, 0);
        validation.add(text("mesuré", 10.5, "#8FB3D9"), 2, 0);
        for (int i = 0; i < facts.size(); i++) {
            LabFact f = facts.get(i);
            validation.add(text(f.getQuantity(), 11.5, "#ECEFF3"), 0, i + 1);
            validation.add(text(localized(f.getValue()), 11.5, "#8FE3FF"), 1, i + 1);
            validation.add(text(localized(f.getMeaning()), 11.5, "#FFD27A"), 2, i + 1);
        }
    }

    /** Le moteur écrit ses nombres avec un point ; en français, une virgule. */
    private static String localized(String value) {
        return I18n.language() == I18n.Language.FR ? value.replaceAll("(\\d)\\.(\\d)", "$1,$2") : value;
    }

    private void atoms(MoleculeLabState s) {
        int n = s.getAtomsCount();
        scene.z = new int[n];
        scene.symbols = new String[n];
        scene.massNumbers = new int[n];
        scene.positions = new double[n][];
        scene.charges = new double[n];
        scene.hardness = new double[n];
        scene.covalent = new double[n];
        scene.netForce = new double[n][];
        scene.externalForce = new double[n][];
        scene.polarization = new double[n][];
        for (int i = 0; i < n; i++) {
            MoleculeAtom a = s.getAtoms(i);
            scene.z[i] = a.getZ();
            scene.symbols[i] = a.getSymbol();
            scene.massNumbers[i] = a.getMassNumber();
            scene.positions[i] = vector(a.getPositionList());
            scene.charges[i] = a.getCharge();
            scene.hardness[i] = a.getHardness();
            scene.covalent[i] = a.getCovalentRadius();
            scene.netForce[i] = vector(a.getForceList());
            scene.externalForce[i] = vector(a.getForceExternalList());
            scene.polarization[i] = vector(a.getPolarizationList());
        }
        scene.pairs.clear();
        for (MoleculePair p : s.getPairsList()) {
            scene.pairs.add(new MoleculeScene.Pair(p.getA(), p.getB(), p.getDistance(), p.getOrder(), p.getElectronic(),
                    p.getRepulsion(), p.getDispersion(), p.getTotal(), p.getForceCount() == 3 ? vector(p.getForceList()) : null,
                    p.getOverlap(), p.getCharges(), p.getPopulation()));
        }
        scene.bonds.clear();
        for (MoleculeBond b : s.getBondsList()) {
            float[] profile = new float[b.getProfileCount()];
            for (int k = 0; k < profile.length; k++) profile[k] = b.getProfile(k);
            double[] split = b.getPiSplitCount() == 2 ? new double[]{b.getPiSplit(0), b.getPiSplit(1)} : new double[2];
            scene.bonds.add(new MoleculeScene.Bond(b.getA(), b.getB(), b.getOrder(), b.getSigma(), b.getPi(), b.getShare(),
                    b.getPiAxisCount() == 3 ? vector(b.getPiAxisList()) : null, split, profile, b.getShared()));
        }
        scene.multicentres.clear();
        for (MoleculeMulticentre m : s.getMulticentresList()) {
            scene.multicentres.add(new MoleculeScene.Multicentre(m.getAtomsList().stream().mapToInt(Integer::intValue).toArray(),
                    m.getPopulationsList().stream().mapToDouble(Double::doubleValue).toArray(),
                    m.getCentroidList().stream().mapToDouble(Double::doubleValue).toArray()));
        }
        scene.updateAtoms();
        List<int[]> members = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (MoleculeSpecies named : s.getNamedList()) {
            members.add(named.getAtomsList().stream().mapToInt(Integer::intValue).toArray());
            labels.add(named.getLabel());
        }
        scene.species(members, labels);
        scene.computeField();
        scene.wall = s.getWall();
        scene.pressure = s.getPressure();
        scene.pistonOn = s.getPressureTarget() > 0;
        scene.pressureTarget = s.getPressureTarget();
        scene.tetherNewtons = s.getTetherForceNn();
        if (scene.selected >= n) scene.selected = -1;
        if (dragging < 0 && s.getDragAtom() >= 0 && s.getDragTargetCount() == 3) {
            scene.dragged = s.getDragAtom();
            scene.dragTarget = vector(s.getDragTargetList());
        } else if (dragging < 0) {
            scene.dragged = -1;
        }
    }

    private static double[] vector(List<Double> values) {
        double[] v = new double[3];
        for (int k = 0; k < Math.min(3, values.size()); k++) v[k] = values.get(k);
        return v;
    }

    private void energies(MoleculeLabState s) {
        EnergyBalance.draw(balance, s);
    }

    private void species(MoleculeLabState s) {
        speciesLines.setText(String.join("\n", s.getSpeciesList().stream().map(MoleculeFormat::names).toList()));
        if (s.getDipoleCount() == 3) {
            double mu = Math.sqrt(s.getDipole(0) * s.getDipole(0) + s.getDipole(1) * s.getDipole(1)
                    + s.getDipole(2) * s.getDipole(2));
            dipoleLine.setText(I18n.text("Moment dipolaire") + "  " + MoleculeFormat.decimal(mu, 2) + " D");
        }
        StringBuilder b = new StringBuilder();
        for (MoleculeEvent e : s.getEventsList()) {
            b.append(MoleculeFormat.time(e.getTimeFs())).append("  ").append(MoleculeFormat.names(e.getText())).append('\n');
        }
        eventLines.setText(b.toString().trim());
    }

    private void modes(MoleculeLabState s) {
        if (s.getComputingModes()) {
            modeHeader.setText(I18n.text("Calcul de la hessienne…"));
            return;
        }
        List<MoleculeMode> list = s.getModesList();
        if (list.isEmpty()) {
            modeHeader.setText(I18n.text("Pas encore calculées."));
            modeList.getChildren().clear();
            return;
        }
        modeHeader.setText(list.size() + " " + I18n.text("modes · cliquer pour exciter (dynamique libre)"));
        if (modeList.getChildren().size() != list.size()) {
            modeList.getChildren().clear();
            for (int i = 0; i < list.size(); i++) {
                double nu = list.get(i).getWavenumber();
                Button b = button((i + 1) + "  ·  " + (nu < 0 ? MoleculeFormat.decimal(-nu, 0) + "i" : MoleculeFormat.decimal(nu, 0))
                        + " cm⁻¹", false);
                b.setMaxWidth(Double.MAX_VALUE);
                final int index = i;
                b.setOnAction(e -> actions.excite(index, quanta.getValue()));
                modeList.getChildren().add(b);
            }
        }
        for (int i = 0; i < modeList.getChildren().size(); i++) {
            modeList.getChildren().get(i).setStyle(i == s.getExcitedMode() ? "-fx-border-color:#FFD27A;" : "");
        }
    }

    private void history(MoleculeLabState s) {
        int n = s.getHistoryTimeCount();
        historyTime = new double[n];
        historyEnergy = new double[n];
        historyKinetic = new double[n];
        for (int i = 0; i < n; i++) {
            historyTime[i] = s.getHistoryTime(i);
            historyEnergy[i] = s.getHistoryEnergy(i);
            historyKinetic[i] = s.getHistoryKinetic(i);
        }
    }

    private String statusLine(MoleculeLabState s) {
        String time = s.getDynamics() == 0 ? s.getSteps() + " " + I18n.text("pas") : MoleculeFormat.time(s.getTimeFs());
        String rate = s.getStepsPerSecond() > 0 ? " · " + MoleculeFormat.decimal(s.getStepsPerSecond(), 0) + " " + I18n.text("pas/s") : "";
        // Le temps de la simulation face à celui de l'écran : 0,25 fs par pas, quelques dizaines de pas par seconde.
        if (s.getDynamics() != 0 && s.getStepsPerSecond() > 0) {
            double fs = s.getStepsPerSecond() * 0.25;
            rate += " · " + I18n.text("1 s d'écran =") + " " + MoleculeFormat.decimal(fs, fs < 10 ? 1 : 0) + " fs";
        }
        String scc = " · SCC " + s.getIterations() + " it." + (s.getConverged() ? "" : " " + I18n.text("(non convergé)"));
        // Ce que le cycle a dû faire pour converger, dit tel quel : des occupations lissées (recuit), une charge
        // tenue à −2,5 e (GFN1 seul s'y emballerait).
        if (s.getElectronicTemperature() > 301) {
            scc += " · " + I18n.text("électrons lissés à " + Math.round(s.getElectronicTemperature()) + " K");
        }
        if (s.getConfinedCount() > 0) {
            StringBuilder held = new StringBuilder();
            for (int i : s.getConfinedList()) {
                if (i < s.getAtomsCount()) held.append(held.length() > 0 ? ", " : "").append(s.getAtoms(i).getSymbol()).append(i + 1);
            }
            scc += " · " + held + " " + I18n.text("tenu à −2,5 e");
        }
        return I18n.text(s.getStatus()) + "  ·  " + time + rate + scc;
    }

    /** La lecture de la carte de l'atome (pour les aperçus) : AtomMap.ESSENTIAL, BY_PAIR ou BY_TERM. */
    void atomMapMode(int mode) {
        atomMap.mode = mode;
        refreshAtomCard();
    }

    private void refreshAtomCard() {
        int i = scene.selected;
        boolean chosen = state != null && i >= 0 && i < state.getAtomsCount() && i < scene.positions.length;
        atomCard.setVisible(!chosen);
        atomCard.setManaged(!chosen);
        atomCanvas.setVisible(chosen);
        atomCanvas.setManaged(chosen);
        if (!chosen) {
            atomCard.setText(I18n.text("Cliquer sur un atome pour voir sa carte : ses voisins et les forces qu'il subit."));
            isotopeRow.setVisible(false);
            isotopeRow.setManaged(false);
            return;
        }
        atomMap.draw(atomCanvas, state.getAtoms(i), scene, i, state.getConfinedList().contains(i));
        refreshIsotopes(state.getAtoms(i), i);
    }

    /**
     * Les isotopes de l'atome choisi : le mélange naturel, chaque isotope stable, et les radioactifs qui vivent au moins
     * un an. Seule la masse change : la géométrie reste, la dynamique et les vibrations changent (D₂O : élongations
     * × 0,73). « tous les H » : le même isotope pour tous les atomes de l'élément.
     */
    private void refreshIsotopes(MoleculeAtom atom, int index) {
        List<MoleculeIsotope> choices = isotopeChoices.getOrDefault(atom.getZ(), List.of());
        String key = atom.getZ() + ":" + atom.getMassNumber() + ":" + index + ":" + choices.size() + ":" + I18n.language();
        isotopeRow.setVisible(!choices.isEmpty());
        isotopeRow.setManaged(!choices.isEmpty());
        if (key.equals(isotopeKey) || choices.isEmpty()) return;
        isotopeKey = key;
        isotopeRow.getChildren().clear();
        isotopeRow.getChildren().add(text(I18n.text("isotope"), 10.5, "#A2ABB8"));
        int[] numbers = new int[choices.size() + 1];
        for (int k = 0; k < choices.size(); k++) numbers[k + 1] = choices.get(k).getMassNumber();
        for (int k = 0; k < numbers.length; k++) {
            int a = numbers[k];
            ToggleButton b = new ToggleButton(a == 0 ? I18n.text("naturel") : nuclide(atom.getSymbol(), a));
            b.getStyleClass().add("chip");
            b.setFocusTraversable(false);
            b.setSelected(atom.getMassNumber() == a);
            b.setTooltip(I18n.tooltip(a == 0 ? I18n.text("le mélange naturel : masse atomique standard") + " "
                    + MoleculeFormat.decimal(atom.getMassU(), 3) + " u" : isotopeNote(choices.get(k - 1))));
            b.setOnAction(e -> {
                b.setSelected(true);
                actions.isotope(isotopeAll.isSelected() ? -atom.getZ() : index, a);
            });
            isotopeRow.getChildren().add(b);
        }
        isotopeAll.setText(I18n.text("tous les") + " " + atom.getSymbol());
        isotopeRow.getChildren().add(isotopeAll);
    }

    private static String isotopeNote(MoleculeIsotope isotope) {
        String mass = MoleculeFormat.decimal(isotope.getMass(), 4) + " u";
        if (isotope.getHalfLifeYears() > 0) {
            return mass + " · " + I18n.text("radioactif, demi-vie") + " " + MoleculeFormat.halfLife(isotope.getHalfLifeYears());
        }
        return mass + " · " + MoleculeFormat.decimal(isotope.getAbundance(), isotope.getAbundance() < 1 ? 4 : 2) + " % "
                + I18n.text("du mélange naturel");
    }

    /** Le symbole d'un isotope en notation IUPAC : le nombre de masse en exposant devant le symbole (²H, ¹³C). */
    static String nuclide(String symbol, int massNumber) {
        if (massNumber <= 0) return symbol;
        StringBuilder b = new StringBuilder();
        for (char c : String.valueOf(massNumber).toCharArray()) b.append("⁰¹²³⁴⁵⁶⁷⁸⁹".charAt(c - '0'));
        return b.append(symbol).toString();
    }

    /** Le moteur sait-il calculer cet élément (GFN1-xTB : Z = 1 à 86) ? Vrai tant que la liste n'est pas arrivée. */
    public boolean supports(int z) {
        return supported.isEmpty() || supported.contains(z);
    }

    /** Un élément choisi dans le tableau périodique : il devient l'élément à poser. */
    public void chooseElement(int z, String symbol) {
        if (assembler.getNode().isVisible()) {
            assembler.chooseElement(z, symbol);
            return;
        }
        if (!supported.isEmpty() && !supported.contains(z)) {
            chosenElement.setText(symbol + " : " + I18n.text("GFN1-xTB n'est paramétré que de l'hydrogène au radon "
                    + "(Z = 1 à 86) ; au-delà, aucun calcul n'est possible."));
            return;
        }
        for (Node node : paletteBox.getChildren()) {
            if (node instanceof ToggleButton t && Integer.valueOf(z).equals(t.getUserData())) {
                t.setSelected(true);
                chosenElement.setText(I18n.text("À poser") + " : " + symbol + I18n.text(" — cliquer sur un atome ou dans le vide"));
                return;
            }
        }
        ToggleButton b = elementChip(z, symbol);
        paletteBox.getChildren().add(b);
        b.setSelected(true);
        chosenElement.setText(I18n.text("À poser") + " : " + symbol + I18n.text(" — cliquer sur un atome ou dans le vide"));
    }

    private ToggleButton elementChip(int z, String symbol) {
        ToggleButton b = new ToggleButton(symbol);
        b.setUserData(z);
        b.setToggleGroup(paletteGroup);
        b.setFocusTraversable(false);
        b.getStyleClass().add("glass-toggle");
        b.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-min-width:38;-fx-text-fill:"
                + web(MoleculeScene.elementColor(z)) + ";");
        return b;
    }

    // ================================================================== les petits tracés
    /** La cible des niveaux (voir EnergyTarget) : l'orbitale montrée dans la vue ψ, celle sous le pointeur. */
    private void drawLevels() {
        GraphicsContext g = levels.getGraphicsContext2D();
        double w = levels.getWidth(), h = levels.getHeight();
        if (orbitals.isEmpty() || homo < 0) {
            g.clearRect(0, 0, w, h);
            orbitalLine.setText("");
            return;
        }
        target.layout(orbitals, homo, w, h);
        target.draw(g, w, h, orbitals, viewKind == 2 ? viewOrbital : -1, levelHover);
        orbitalLine.setText("");
    }

    /** La clé de la cible : ce que veut dire chaque cercle. */
    private static FlowPane levelKey() {
        FlowPane key = new FlowPane(10, 3);
        String[][] items = {{"●", "occupé", web(EnergyTarget.FILLED)}, {"●", "HOMO", web(EnergyTarget.HOMO)},
                {"▬", "écart", web(EnergyTarget.GAP)}, {"◌", "LUMO", web(EnergyTarget.LUMO)}, {"◌", "vide", web(EnergyTarget.EMPTY)}};
        for (String[] item : items) {
            Label mark = text(item[0], 10, item[2]), name = text(I18n.text(item[1]), 10, "#A9BCD6");
            HBox pair = new HBox(4, mark, name);
            pair.setAlignment(Pos.CENTER_LEFT);
            key.getChildren().add(pair);
        }
        return key;
    }

    private void drawChart() {
        GraphicsContext g = chart.getGraphicsContext2D();
        double w = chart.getWidth(), h = chart.getHeight();
        org.genesis.common.Plan.frame(g, 0, 0, w, h, org.genesis.common.Plan.DEEP.deriveColor(0, 1, 1, .92));
        int n = historyTime.length;
        g.setFont(Font.font(org.genesis.common.Plan.SANS, 10));
        if (n < 2) {
            g.setFill(Color.web("#6F82A0"));
            g.fillText(I18n.text("L'énergie au fil du calcul s'affichera ici."), 10, h / 2);
            return;
        }
        boolean free = state != null && state.getDynamics() == 2;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            lo = Math.min(lo, historyEnergy[i]);
            hi = Math.max(hi, historyEnergy[i] + (free ? historyKinetic[i] : 0));
        }
        double span = Math.max(hi - lo, 0.05);
        double t0 = historyTime[0], t1 = Math.max(historyTime[n - 1], t0 + 1e-9);
        double left = 46, right = w - 8, top = 8, bottom = h - 14;
        g.setStroke(Color.web("#1B2840"));
        g.strokeLine(left, bottom, right, bottom);
        plot(g, historyEnergy, null, lo, span, t0, t1, left, right, top, bottom, Color.web("#4FE3FF"));
        if (free) plot(g, historyEnergy, historyKinetic, lo, span, t0, t1, left, right, top, bottom, Color.web("#FFD27A"));
        g.setFill(Color.web("#8FB3D9"));
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(MoleculeFormat.decimal(hi - lo, 2) + " eV", left - 4, top + 8);
        g.fillText("0", left - 4, bottom);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(Color.web("#4FE3FF"));
        g.fillText(I18n.text("énergie potentielle") + (free ? "" : ""), left + 4, h - 2);
        if (free) {
            g.setFill(Color.web("#FFD27A"));
            g.fillText(I18n.text("potentielle + cinétique"), left + 130, h - 2);
        }
        g.setFill(Color.web("#8FB3D9"));
        g.setTextAlign(TextAlignment.RIGHT);
        String axis = state != null && state.getDynamics() == 0 ? I18n.text("pas de relaxation")
                : MoleculeFormat.time(t1 - t0);
        g.fillText(axis, right, h - 2);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private static void plot(GraphicsContext g, double[] values, double[] extra, double lo, double span, double t0,
                             double t1, double left, double right, double top, double bottom, Color color) {
        g.setStroke(color);
        g.setLineWidth(1.4);
        g.beginPath();
        for (int i = 0; i < values.length; i++) {
            double v = values[i] + (extra == null ? 0 : extra[i]);
            double x = left + (i / (double) (values.length - 1)) * (right - left);
            double y = bottom - (v - lo) / span * (bottom - top);
            if (i == 0) g.moveTo(x, y);
            else g.lineTo(x, y);
        }
        g.stroke();
    }

    // ================================================================== l'horloge
    public void tick(double dt) {
        // La vue du chemin couvre tout le laboratoire : la scène, cachée dessous, n'est pas dessinée pour rien.
        if (schemeStage.isOpen()) {
            schemeStage.tick(dt);
            return;
        }
        if (schemeEditor.getNode().isVisible()) schemeEditor.tick(dt);
        GraphicsContext g = sceneCanvas.getGraphicsContext2D();
        double w = sceneCanvas.getWidth(), h = sceneCanvas.getHeight();
        if (w < 2 || h < 2) return;
        javafx.stage.Window window = sceneCanvas.getScene() == null ? null : sceneCanvas.getScene().getWindow();
        scene.pixelScale = window == null ? 1 : Math.max(1, window.getRenderScaleX());
        // La vignette dépliée couvre le bas de la bande gauche : la molécule se centre à sa droite (en douceur quand on
        // la plie), sauf dans une fenêtre trop étroite pour cela.
        scene.insetTarget = !sketch.folded && sketchCanvas.isVisible() && w > 3 * MoleculeSketch.W ? MoleculeSketch.W + 20 : 0;
        // La carte du chemin joué couvre le bas de la bande gauche : la molécule se centre à sa droite.
        Region card = schemePlayer.getNode();
        double cardWidth = card.getWidth() > 0 ? card.getWidth() : card.getPrefWidth();
        if (card.isVisible() && w > 1.8 * cardWidth) scene.insetTarget = Math.max(scene.insetTarget, cardWidth + 28);
        scene.render(g, glowNear.getGraphicsContext2D(), glowFar.getGraphicsContext2D(), w, h, dt, rotating || panning);
        refreshViewLine();
        if (orbitalLayer != null) {
            orbitalLayer.getNode().setTranslateX(scene.insetLeft / 2);    // les lobes 3D suivent le même centre
            orbitalLayer.update(scene.surface(), scene.camera, scene.positions, scene.bondedBonds());
        }
        drawSketch();                                                 // il suit la rotation de la vue, image par image
        drawChart();
    }

    /**
     * Le chemin joué dans la scène : ses liaisons qui changent et leurs indices de Mayer à l'image montrée (la scène les
     * dessine), et, au début de la lecture d'une étape, un cadre qui tient tout le chemin, à droite de la carte.
     */
    /**
     * Le schéma reçu, complet : pendant le calcul, le moteur n'envoie les étapes (lourdes : les images de chaque chemin)
     * qu'à leur changement ; entre-temps, ses messages les disent gardées, et ce sont celles du message précédent de la
     * même demande — les mêmes objets, si bien que ce qui les montre n'a rien à refaire.
     */
    static com.studio.genesis.grpc.MoleculeScheme merged(com.studio.genesis.grpc.MoleculeScheme incoming,
                                                          com.studio.genesis.grpc.MoleculeScheme previous) {
        if (!incoming.getStepsKept() || previous == null || previous.getRequest() != incoming.getRequest()) return incoming;
        return incoming.toBuilder().clearSteps().addAllSteps(previous.getStepsList()).build();
    }

    private void playPath(MoleculeLabState s) {
        com.studio.genesis.grpc.MoleculeSchemePlay p = s.getPlay();
        boolean playing = s.hasPlay() && p.getStep() >= 0 && lastScheme != null && p.getStep() < lastScheme.getStepsCount();
        if (!playing) {
            if (scene.playing) {
                scene.playing = false;
                scene.pathChanges = new int[0];
                scene.pathMayer = new double[0];
                playedStep = -1;
                playMode(false);
            }
            return;
        }
        com.studio.genesis.grpc.MoleculeSchemeStep st = lastScheme.getSteps(p.getStep());
        int count = st.getChangesCount() / 4;
        int frames = Math.max(1, st.getEnergiesCount());
        int frame = Math.max(0, Math.min(frames - 1, p.getFrame()));
        int[] changes = new int[st.getChangesCount()];
        for (int k = 0; k < changes.length; k++) changes[k] = st.getChanges(k);
        double[] mayer = new double[count];
        for (int k = 0; k < count; k++) {
            int index = frame * count + k;
            mayer[k] = index < st.getOrdersCount() ? st.getOrders(index) : 0;
        }
        boolean started = !scene.playing;
        scene.playing = true;
        scene.pathNumbers = st.getIdsList().stream().mapToInt(Integer::intValue).toArray();
        scene.pathChanges = changes;
        scene.pathMayer = mayer;
        scene.pathRadius = p.getRadius();
        // Recadrer une seule fois par étape jouée : l'objet du schéma change à chaque publication pendant que les
        // étapes suivantes se calculent (toutes les 0,15 s), et la vue reprenait la main sur le zoom à chaque fois.
        if (started || playedStep != p.getStep() || lastScheme.getRequest() != playedRequest) {
            playedStep = p.getStep();
            playedRequest = lastScheme.getRequest();
            playedScheme = lastScheme;
            scene.frame();
        }
        playMode(true);
    }

    /** La méthode du laboratoire (bouton « Méthode » de l'en-tête commun) : ouvre ou referme son panneau. */
    public void showModel() {
        modelBanner.setVisible(!modelBanner.isVisible());
    }
}
