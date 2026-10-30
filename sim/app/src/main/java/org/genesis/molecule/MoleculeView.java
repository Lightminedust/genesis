package org.genesis.molecule;

import org.genesis.app.I18n;

import com.studio.genesis.grpc.LabFact;
import com.studio.genesis.grpc.MoleculeAtom;
import com.studio.genesis.grpc.MoleculeBond;
import com.studio.genesis.grpc.MoleculeCloud;
import com.studio.genesis.grpc.MoleculeIsotope;
import com.studio.genesis.grpc.MoleculeEvent;
import com.studio.genesis.grpc.MoleculeLabState;
import com.studio.genesis.grpc.MoleculeMode;
import com.studio.genesis.grpc.MoleculeMulticentre;
import com.studio.genesis.grpc.MoleculeOrbital;
import com.studio.genesis.grpc.MoleculeOrbitalPart;
import com.studio.genesis.grpc.MoleculePair;
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
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
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
            "Dynamique moléculaire à température fixée (ensemble NVT, thermostat CSVR) : la molécule vibre, "
                    + "tourne, et peut réagir",
            "Dynamique sans thermostat (ensemble NVE) : l'énergie totale (potentielle + cinétique) se conserve"};
    private final Actions actions;
    private final BorderPane root = new BorderPane();
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
    private final Label title = text("", 17, "#EAF2FF"), census = text("", 12.5, "#8FD8FF");
    /** Sous les espèces, ce que montre la vue Δρ ou ψ (l'orbitale : nom, énergie, électrons, composition). */
    private final Label modeLine = text("", 12, "#C7D6EA");
    private String moleculeSignature = "";
    private List<MoleculeOrbitalPart> orbitalParts = List.of();
    private int partsOrbital = -1;
    private final Label status = text("", 11, "#90A3BE"), error = text("", 11.5, "#FF9C8A");
    /** Le bilan d'énergie (voir EnergyBalance), la cible des niveaux (EnergyTarget), la carte de l'atome (AtomMap). */
    private final Canvas balance = new Canvas(270, 200), atomCanvas = new Canvas(270, 300);
    private final EnergyTarget target = new EnergyTarget();
    private final AtomMap atomMap = new AtomMap();
    private int levelHover = -1;
    private final Label speciesLines = text("", 11.5, "#DCE8F7"), eventLines = text("", 10.5, "#A9BCD6");
    private final Label atomCard = text("", 11.5, "#DCE8F7"), dipoleLine = text("", 11, "#A9BCD6");
    /** Les isotopes de l'atome choisi (voir refreshIsotopes) ; ceux que le moteur propose, par élément. */
    private final FlowPane isotopeRow = new FlowPane(4, 4);
    private final ToggleButton isotopeAll = new ToggleButton();
    private final Map<Integer, List<MoleculeIsotope>> isotopeChoices = new HashMap<>();
    private String isotopeKey = "";
    private final Label orbitalLine = text("", 11, "#A9BCD6"), modeHeader = text("", 11, "#A9BCD6");
    private final VBox modeList = new VBox(3);
    private final Slider temperature = new Slider(0, 4000, 300), spark = new Slider(1, 30, 8), quanta = new Slider(1, 10, 1);
    private final Label temperatureLabel = text("", 11, "#C6D4E8"), sparkLabel = text("", 11, "#C6D4E8");
    private final Label quantaLabel = text("", 11, "#C6D4E8");
    private final Button pause = button("Ⅱ", false);
    private final Canvas chart = new Canvas(430, 92), levels = new Canvas(270, 264);
    private final VBox modelBanner = new VBox(8);
    private final Label modelText = text("", 11.5, "#E6EEFA");
    private final GridPane validation = new GridPane();
    private final List<String> presets = new ArrayList<>(), reactions = new ArrayList<>();
    private final java.util.Set<Integer> supported = new java.util.HashSet<>();
    private final Label chosenElement = text("", 10.5, "#FFD27A");
    private final Label hoverCard = text("", 11, "#E6EEFA");
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
    private double lastX, lastY, pressX, pressY, lastDragSent, hoverX, hoverY;
    private int pressedAtom = -1, dragging = -1, homo, viewKind, viewOrbital = -1;
    private String framedLabel = "";
    private int framedCount = -1;
    private double[] dragPlane;
    private double[] historyTime = new double[0], historyEnergy = new double[0], historyKinetic = new double[0];
    private List<MoleculeOrbital> orbitals = new ArrayList<>();

    public MoleculeView(Actions actions) {
        this.actions = actions;
        root.getStyleClass().addAll("fusion-lab", "molecule-lab");
        root.setStyle("-fx-background-color:#04070D;-fx-font-family:'Segoe UI';");
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
        hoverCard.setStyle(hoverCard.getStyle() + "-fx-background-color:rgba(6,10,22,0.88);-fx-padding:8 10;"
                + "-fx-background-radius:8;-fx-border-color:rgba(120,200,255,0.25);-fx-border-radius:8;");
        holder.getChildren().add(hoverCard);
        holder.setOnScroll(e -> scene.camera.scroll(e.getDeltaY() / 40));
        Region dock = dock();
        stage.getChildren().addAll(holder, header(), dock, sketchCanvas, modelBanner());
        // La vignette 2D, en bas à gauche, juste au-dessus du dock (qui change de hauteur avec la ligne d'état).
        StackPane.setAlignment(sketchCanvas, Pos.BOTTOM_LEFT);
        dock.heightProperty().addListener((o, a, b) ->
                StackPane.setMargin(sketchCanvas, new Insets(0, 0, b.doubleValue() + 22, 12)));
        StackPane.setMargin(sketchCanvas, new Insets(0, 0, 200, 12));
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
        root.setCenter(stage);
        root.setLeft(scroll(workbench(), 236));
        root.setRight(scroll(readings(), 300));
        // La cible : survoler un cercle lit son niveau au cœur, cliquer montre son orbitale.
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
    }

    public Region getNode() {
        return root;
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
        Label number = text(index, 9.5, "#36CDF2"), name = text(spaced(title), 9.5, "#8FB3D9");
        number.setStyle(number.getStyle() + "-fx-font-weight:bold;");
        name.setStyle(name.getStyle() + "-fx-font-weight:bold;");
        HBox head = new HBox(9, number, name);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(7);
        box.getChildren().add(head);
        box.getChildren().addAll(nodes);
        box.getStyleClass().add("glass-section");
        return box;
    }

    private static String spaced(String title) {
        return I18n.text(title).toUpperCase(I18n.locale());
    }

    private static ScrollPane scroll(VBox content, double width) {
        content.setPadding(new Insets(12, 10, 12, 10));
        content.setPrefWidth(width);
        ScrollPane pane = new ScrollPane(content);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pane.setPrefWidth(width + 14);
        pane.setStyle("-fx-background:#060A14;-fx-background-color:#060A14;");
        return pane;
    }

    // ================================================================== l'en-tête, le dock, le modèle
    private Region header() {
        Button back = button("← Atome", false);
        back.setOnAction(e -> actions.back());
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
        Button model = button("Méthode & validation", false);
        model.setOnAction(e -> modelBanner.setVisible(!modelBanner.isVisible()));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox controls = new HBox(10, back, segment, gap, model);
        controls.setAlignment(Pos.CENTER_LEFT);
        error.setWrapText(true);
        // Le message s'enroule à gauche de la légende de la scène (en haut à droite) au lieu de passer dessous.
        error.maxWidthProperty().bind(javafx.beans.binding.Bindings.max(260, holder.widthProperty().subtract(470)));
        modeLine.setWrapText(true);
        modeLine.maxWidthProperty().bind(error.maxWidthProperty());
        modeLine.setVisible(false);
        modeLine.setManaged(false);
        VBox top = new VBox(3, controls, title, census, modeLine, error);
        top.setPadding(new Insets(10, 14, 0, 16));
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
        Label sparkHint = text("clic droit sur un atome", 10.5, "#8FB3D9");
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
                + "droit sur l'atome), quantité de mouvement conservée"));
        sparkHint.managedProperty().bind(sparkHint.visibleProperty());
        sparkHint.visibleProperty().bind(holder.widthProperty().greaterThan(860));
        HBox row = new HBox(10, segment, text("T", 11, "#8FB3D9"), temperature, temperatureLabel, pause,
                sparkName, spark, sparkLabel, sparkHint);
        row.setAlignment(Pos.CENTER_LEFT);
        status.setWrapText(true);
        VBox dock = new VBox(6, row, chart, status);
        dock.setPadding(new Insets(10, 14, 10, 14));
        dock.getStyleClass().add("glass-section");
        dock.setMaxWidth(Region.USE_PREF_SIZE);
        dock.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(dock, Pos.BOTTOM_CENTER);
        StackPane.setMargin(dock, new Insets(0, 0, 12, 0));
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
        modelBanner.setStyle("-fx-background-color:rgba(6,10,22,0.94);");
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
        empty.setOnAction(e -> actions.clear());
        empty.setTooltip(I18n.tooltip("Tout retirer, puis construire une molécule atome par atome"));
        Label paletteHint = text("Choisir un élément, puis cliquer sur un atome pour l'y attacher (ou dans le vide). "
                + "La géométrie sort du calcul.", 10.5, "#8FA4C2");
        paletteHint.setWrapText(true);
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
        VBox show = new VBox(4);
        ToggleButton bondsToggle = toggle("liaisons", true, v -> scene.showBonds = v,
                "Les paires d'électrons partagées, localisées (Pipek–Mezey). Une paire sur deux atomes : un trait, éclat "
                        + "selon l'indice de Mayer, couleur selon la force interne le long de la liaison (bleu nulle, "
                        + "blanc traction, orange compression), grains tirés de la densité de la paire ; sa part π : deux "
                        + "lobes de part et d'autre, orientés comme sa densité. Une paire sur trois atomes ou plus "
                        + "(métaux, pont B–H–B) : une étoile de son barycentre vers ses atomes. Deux ions sans paire "
                        + "partagée (NaCl) : des pointillés.");
        ToggleButton arcsToggle = toggle("arcs des atomes", true, v -> scene.showInner = v,
                "Dans chaque bulle, des arcs partis du noyau, captés par les liaisons et penchés par la déformation du "
                        + "nuage de l'atome (son dipôle calculé)");
        ToggleButton contacts = toggle("attractions et répulsions sans liaison", true, v -> scene.showContacts = v,
                "Entre atomes qui ne partagent pas de paire d'électrons (au moins quatre liaisons d'écart, deux molécules, "
                        + "ou deux ions) : des points qui glissent vers le milieu quand la force calculée les attire ; "
                        + "quand elle les repousse, des points orange qui partent de là où leurs nuages se touchent et "
                        + "s'écartent vers les deux atomes, d'autant plus vite et plus vifs que la force est grande");
        ToggleButton envelope = toggle("halo de polarisation", true, v -> scene.showHalo = v,
                "Le nuage des électrons de valence de chaque molécule, vu en transparence ; son bord : là où le regard "
                        + "croise 0,3 électron par Å². Chaque atome y apporte les électrons qu'il garde (charge de Mulliken) : "
                        + "fourni et magenta autour des atomes δ−, mince et cyan autour des atomes δ+");
        ToggleButton potential = toggle("carte de potentiel (0,002 u.a.)", false, v -> {
                    scene.showPotential = v;
                    scene.retint();
                    // Le moteur ne calcule la grille (densité et potentiel) que lorsqu'une vue la montre.
                    actions.view(viewKind, viewKind == 2 ? viewOrbital : -1, v);
                },
                "La convention des cartes de potentiel électrostatique : le potentiel exact de la molécule (noyaux et "
                        + "nuage) sur la surface ρ = 0,002 électron par bohr³ ; magenta négatif, cyan positif (±2,5 V)");
        ToggleButton lines = toggle("champ électrique", false, v -> {
                    scene.showField = v;
                    scene.computeField();
                },
                "Les lignes du champ des charges partielles, du + vers le −, en nombre proportionnel à la charge (Gauss)");
        ToggleButton arrows = toggle("flèche de la force nette", false, v -> scene.showArrows = v,
                "Une seule flèche par atome : la somme de toutes les forces sur son noyau");
        ToggleButton charge = toggle("valeurs des charges δ", false, v -> scene.showCharges = v,
                "Charges de Mulliken : δ− là où les électrons s'accumulent, δ+ là où ils manquent (la couleur des "
                        + "bulles les montre déjà)");
        ToggleButton orders = toggle("indices de liaison (Mayer)", false, v -> scene.showOrders = v,
                "Le nombre de paires d'électrons partagées : ≈ 1 simple, 2 double, 3 triple");
        ToggleButton symbols = toggle("symboles", true, v -> scene.showSymbols = v, "Le symbole de chaque élément");
        ToggleButton legend = toggle("légende", true, v -> scene.showLegend = v, "Ce que veut dire chaque signe de la scène");
        show.getChildren().addAll(envelope, bondsToggle, arcsToggle, contacts, potential, lines, arrows, charge, orders,
                symbols, legend);
        return new VBox(10,
                section("01", "Molécules", presetBox),
                section("02", "Réacteur", reactionBox, reactorHint()),
                section("03", "Établi", paletteBox, table, chosenElement, paletteHint, empty),
                section("04", "Charge totale", chargeRow),
                section("05", "Afficher", show));
    }

    /** Ce qu'est le réacteur, et ce qu'il n'est pas : une enceinte très dense, sans spin, où tout va trop vite. */
    private static Label reactorHint() {
        Label hint = text("Des molécules dans une sphère : chauffer ou lancer une étincelle pour franchir la barrière "
                + "d'activation. Un nanoréacteur, pas une flamme : environ 3 000 atm, et O₂ sans barrière de spin. "
                + "Il montre des mécanismes possibles, pas des vitesses réelles.", 10.5, "#8FA4C2");
        hint.setWrapText(true);
        return hint;
    }

    private ToggleButton toggle(String label, boolean on, java.util.function.Consumer<Boolean> set, String tip) {
        ToggleButton b = new ToggleButton(label);
        b.getStyleClass().add("glass-toggle");
        b.setFocusTraversable(false);
        b.setSelected(on);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setTooltip(I18n.tooltip(tip));
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
        // La limite de GFN1 sur les énergies de liaison : sous le ⓘ du bilan, pas dans le panneau.
        VBox energy = section("06", "Bilan énergétique", balance);
        Label info = text("ⓘ", 12, "#8FB3D9");
        info.setCursor(Cursor.HAND);
        Tooltip limit = I18n.tooltip("GFN1-xTB surestime les énergies de liaison par rapport aux atomes isolés : 1,3 à 2,2 fois "
                + "selon la molécule (CH₄ + 2 O₂ : 47,8 eV au lieu de 28,6). "
                + "Les énergies de réaction aussi : CH₄ + 2 O₂ → CO₂ + 2 H₂O donne −15,5 eV au lieu de −8,5. "
                + "Un décalage par élément ne le corrigerait pas : une réaction garde ses atomes. "
                + "Les géométries sont fiables (voir Méthode & validation). La cohésion et sa balance "
                + "sont comptées depuis les atomes isolés et neutres, calculés par le même modèle.");
        limit.setWrapText(true);
        limit.setMaxWidth(320);
        limit.setShowDelay(javafx.util.Duration.millis(120));
        Tooltip.install(info, limit);
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        ((HBox) energy.getChildren().get(0)).getChildren().addAll(push, info);
        return new VBox(10,
                energy,
                section("07", "Orbitales", levels, levelKey(), orbitalLine),
                section("08", "Espèces", speciesLines, dipoleLine, eventLines),
                section("09", "Atome", atomCard, atomCanvas, isotopeRow),
                section("10", "Vibrations", compute, modeHeader, quantaRow, modeList));
    }

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
        if (e.getButton() == MouseButton.SECONDARY) {
            if (pressedAtom >= 0) contextMenu(pressedAtom, e);
            pressedAtom = -1;
            return;
        }
        if (pressedAtom >= 0 && paletteGroup.getSelectedToggle() == null) {
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
        if (rotating) {
            scene.camera.drag(dx, dy);
            scene.autoView = false;                 // la caméra est à vous : les vues ne la tournent plus
        }
    }

    private void release(MouseEvent e) {
        holder.setCursor(Cursor.OPEN_HAND);
        if (legendPress) {
            legendPress = false;
            return;
        }
        boolean click = Math.hypot(e.getX() - pressX, e.getY() - pressY) <= 3;
        if (dragging >= 0) {
            if (!click) actions.release();
            else select(dragging);
            dragging = -1;
            scene.dragged = -1;
            scene.dragTarget = null;
            return;
        }
        if (!click || e.getButton() != MouseButton.PRIMARY) return;
        if (e.getClickCount() == 2 && pressedAtom < 0) {
            scene.frame();
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
        if (state == null || i < 0 || i >= state.getAtomsCount() || dragging >= 0) {
            hoverCard.setVisible(false);
            return;
        }
        hoverCard.setText(describe(i, false));
        hoverCard.autosize();
        double x = hoverX + 18, y = hoverY + 14;
        if (x + hoverCard.getWidth() > holder.getWidth() - 8) x = hoverX - hoverCard.getWidth() - 14;
        if (y + hoverCard.getHeight() > holder.getHeight() - 8) y = hoverY - hoverCard.getHeight() - 10;
        hoverCard.relocate(x, y);
        hoverCard.setVisible(true);
    }

    private void select(int atom) {
        scene.selected = scene.selected == atom ? -1 : atom;
        refreshAtomCard();
        drawSketch();
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
        if (!name.isEmpty()) return I18n.text("clic droit sur") + " " + name + I18n.text(" : étincelle ou retirer");
        return I18n.text("cliquer un atome : sa carte (09) · le saisir : le tirer");
    }

    private void contextMenu(int atom, MouseEvent e) {
        String name = atom < scene.symbols.length ? scene.symbols[atom] + (atom + 1) : "";
        MenuItem remove = new MenuItem(I18n.text("Retirer") + " " + name);
        remove.setOnAction(a -> actions.removeAtom(atom));
        MenuItem fire = new MenuItem(I18n.text("Étincelle") + " · " + MoleculeFormat.decimal(spark.getValue(), 0) + " eV");
        fire.setOnAction(a -> actions.spark(atom, spark.getValue()));
        MenuItem pick = new MenuItem(I18n.text("Détails de l'atome"));
        pick.setOnAction(a -> select(atom));
        new ContextMenu(fire, remove, pick).show(holder, e.getScreenX(), e.getScreenY());
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
        if (!s.getActive()) return;
        state = s;
        updating = true;
        try {
            if (s.getPresetsCount() > 0) catalog(s);
            if (!s.getModel().isEmpty()) modelText.setText(s.getModel());
            if (s.getValidationCount() > 0) fillValidation(s.getValidationList());
            if (!s.getReady()) {
                status.setText(s.getStatus());
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
            title.setText(s.getLabel());
            census.setText(s.getCensus().isEmpty() ? "" : I18n.text("Espèces présentes") + " : " + s.getCensus());
            error.setText(s.getError());
            error.setVisible(!s.getError().isEmpty());
            error.setManaged(!s.getError().isEmpty());
            int dynamics = shown("dynamics", s.getDynamics()), charge = shown("charge", s.getCharge());
            for (int k = 0; k < 3; k++) dynamicsButtons[k].setSelected(dynamics == k);
            long kelvin = shown("temperature", Math.round(s.getTemperatureTarget()));
            if (!temperature.isValueChanging()) temperature.setValue(kelvin);
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
                drawLevels();
                species(s);
                modes(s);
                history(s);
                refreshAtomCard();
                refreshHover();
                drawSketch();
                status.setText(statusLine(s));
            }
            if (!s.getLabel().equals(framedLabel) || (s.getWall() > 0 && scene.positions.length != framedCount)
                    || framedCount < 0) {
                framedLabel = s.getLabel();
                framedCount = scene.positions.length;
                scene.frame();
            }
        } finally {
            updating = false;
        }
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
            b.setOnAction(e -> actions.preset(index));
            presetBox.getChildren().add(b);
        }
        reactions.clear();
        reactions.addAll(s.getReactionsList());
        reactionBox.getChildren().clear();
        for (int i = 0; i < reactions.size(); i++) {
            Button b = button(reactions.get(i), false);
            final int index = i;
            b.setOnAction(e -> actions.reaction(index));
            reactionBox.getChildren().add(b);
        }
        paletteBox.getChildren().clear();
        for (int z : s.getPaletteList()) paletteBox.getChildren().add(elementChip(z, symbolOf(z)));
        supported.clear();
        supported.addAll(s.getSupportedList());
    }

    private static String symbolOf(int z) {
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
            validation.add(text(f.getQuantity(), 11.5, "#DCE8F7"), 0, i + 1);
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
        scene.computeField();
        scene.wall = s.getWall();
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
        speciesLines.setText(String.join("\n", s.getSpeciesList()));
        if (s.getDipoleCount() == 3) {
            double mu = Math.sqrt(s.getDipole(0) * s.getDipole(0) + s.getDipole(1) * s.getDipole(1)
                    + s.getDipole(2) * s.getDipole(2));
            dipoleLine.setText(I18n.text("Moment dipolaire") + "  " + MoleculeFormat.decimal(mu, 2) + " D");
        }
        StringBuilder b = new StringBuilder();
        for (MoleculeEvent e : s.getEventsList()) {
            b.append(MoleculeFormat.time(e.getTimeFs())).append("  ").append(e.getText()).append('\n');
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
        return s.getStatus() + "  ·  " + time + rate + scc;
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
        isotopeRow.getChildren().add(text(I18n.text("isotope"), 10.5, "#8FA4C2"));
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

    /** Ce que le calcul dit d'un atome : charge, force nette par terme, et ce que chaque voisin lui fait. */
    private String describe(int i, boolean full) {
        MoleculeAtom a = state.getAtoms(i);
        StringBuilder b = new StringBuilder();
        b.append(a.getSymbol()).append(i + 1).append(a.getMassNumber() > 0 ? " (" + nuclide(a.getSymbol(), a.getMassNumber()) + ")" : "")
                .append("  ·  ").append(I18n.text("charge"))
                .append(' ').append(MoleculeFormat.signed(a.getCharge(), 2)).append(" e");
        b.append(a.getCharge() < -.05 ? "  (δ−)" : a.getCharge() > .05 ? "  (δ+)" : "").append('\n');
        if (state.getConfinedList().contains(i)) {
            b.append(I18n.text("Charge tenue à −2,5 e par la contrainte des anions : sans elle, le terme du troisième ordre de "
                    + "GFN1, qui n'est pas borné, lui donnerait des électrons sans fin.")).append('\n');
        }
        if (full) {
            b.append(MoleculeFormat.decimal(a.getMassU(), 3)).append(" u  ·  ").append(I18n.text("voisins (coordination)"))
                    .append(' ').append(MoleculeFormat.decimal(a.getCoordination(), 2)).append('\n');
        }
        b.append(I18n.text("force nette")).append("  ").append(MoleculeFormat.decimal(norm(a.getForceList()), 2)).append(" eV/Å");
        b.append("  (").append(I18n.text("électrons")).append(' ').append(MoleculeFormat.decimal(norm(a.getForceElectronicList()), 2))
                .append(" · ").append(I18n.text("noyaux")).append(' ').append(MoleculeFormat.decimal(norm(a.getForceRepulsionList()), 2));
        double external = norm(a.getForceExternalList());
        if (external > .005) b.append(" · ").append(I18n.text("main/paroi")).append(' ').append(MoleculeFormat.decimal(external, 2));
        b.append(")\n");
        List<MoleculeScene.Pair> mine = new ArrayList<>();
        for (MoleculeScene.Pair p : scene.pairs) if (p.a() == i || p.b() == i) mine.add(p);
        mine.sort((x, y) -> Double.compare(Math.abs(y.total()), Math.abs(x.total())));
        int shown = 0;
        for (MoleculeScene.Pair p : mine) {
            if (shown >= (full ? 8 : 4) || Math.abs(p.total()) < .02) break;
            int other = p.a() == i ? p.b() : p.a();
            b.append(p.total() >= 0 ? "← " : "→ ").append(scene.symbols[other]).append(other + 1).append(' ')
                    .append(I18n.text(p.total() >= 0 ? "l'attire" : "le repousse")).append(' ')
                    .append(MoleculeFormat.decimal(Math.abs(p.total()), 2)).append(" eV/Å");
            boolean broken = p.order() >= MoleculeScene.BOND_ORDER && !scene.linked(p.a(), p.b());
            if (broken) {
                // L'indice de Mayer d'un calcul à couches fermées reste haut après la rupture ; rien n'est plus partagé.
                b.append("  · ").append(I18n.text("indice")).append(' ').append(MoleculeFormat.decimal(p.order(), 2))
                        .append(", ").append(I18n.text("plus rien de partagé entre les noyaux : liaison rompue"));
            } else if (p.order() >= MoleculeScene.BOND_ORDER) {
                b.append("  · ").append(I18n.text("liaison")).append(' ').append(MoleculeFormat.decimal(p.order(), 2));
                String pair = "";
                for (MoleculeScene.Bond bond : scene.bonds) {
                    if (bond.a() != p.a() || bond.b() != p.b()) continue;
                    if (bond.pi() >= .1) {
                        b.append(" (σ ").append(MoleculeFormat.decimal(bond.sigma(), 2)).append(" + π ")
                                .append(MoleculeFormat.decimal(bond.pi(), 2)).append(')');
                    }
                    if (bond.twoCentre()) {
                        // La paire de la liaison : la part que garde l'atome qui en tient le plus.
                        int holder = bond.share() >= .5 ? bond.b() : bond.a();
                        double part = Math.max(bond.share(), 1 - bond.share());
                        pair = "\n     " + I18n.text("paire de la liaison") + "  " + MoleculeFormat.decimal(100 * part, 0)
                                + " % " + I18n.text("chez") + " " + scene.symbols[holder] + (holder + 1);
                    } else {
                        // Sans paire à deux centres : une paire étalée sur plusieurs atomes, ou deux ions.
                        pair = "\n     " + I18n.text("aucune paire partagée : deux ions");
                        for (MoleculeScene.Multicentre m : scene.multicentres) {
                            if (!m.holds(bond.a(), bond.b())) continue;
                            StringBuilder members = new StringBuilder();
                            for (int atom : m.atoms()) {
                                if (members.length() > 0) members.append(" · ");
                                members.append(scene.symbols[atom]).append(atom + 1);
                            }
                            pair = "\n     " + I18n.text("paire à " + m.atoms().length + " centres") + "  " + members;
                            break;
                        }
                    }
                }
                b.append(", ").append(MoleculeFormat.decimal(p.distance(), 3)).append(" Å").append(pair);
            } else {
                b.append("  · ").append(MoleculeFormat.decimal(p.distance(), 2)).append(" Å");
            }
            if (full) {
                b.append("\n     ").append(I18n.text("électrons")).append(' ').append(MoleculeFormat.signed(p.electronic(), 2))
                        .append(" · ").append(I18n.text("noyaux")).append(' ').append(MoleculeFormat.signed(p.repulsion(), 2))
                        .append(" · London ").append(MoleculeFormat.signed(p.dispersion(), 3));
            }
            b.append('\n');
            shown++;
        }
        return b.toString().trim();
    }

    private static double norm(List<Double> v) {
        double s = 0;
        for (double x : v) s += x * x;
        return Math.sqrt(s);
    }

    /** Le moteur sait-il calculer cet élément (GFN1-xTB : Z = 1 à 86) ? Vrai tant que la liste n'est pas arrivée. */
    public boolean supports(int z) {
        return supported.isEmpty() || supported.contains(z);
    }

    /** Un élément choisi dans le tableau périodique : il devient l'élément à poser. */
    public void chooseElement(int z, String symbol) {
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
        orbitalLine.setText(I18n.text("Le rayon suit l'énergie, pas une distance : au centre, les électrons les plus liés ; "
                + "au bord, le vide. Survoler un cercle pour lire son niveau, cliquer pour voir son orbitale."));
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
        g.setFill(Color.web("#070C18", .9));
        g.fillRect(0, 0, w, h);
        int n = historyTime.length;
        g.setFont(Font.font("Segoe UI", 10));
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
        GraphicsContext g = sceneCanvas.getGraphicsContext2D();
        double w = sceneCanvas.getWidth(), h = sceneCanvas.getHeight();
        if (w < 2 || h < 2) return;
        javafx.stage.Window window = sceneCanvas.getScene() == null ? null : sceneCanvas.getScene().getWindow();
        scene.pixelScale = window == null ? 1 : Math.max(1, window.getRenderScaleX());
        scene.render(g, glowNear.getGraphicsContext2D(), glowFar.getGraphicsContext2D(), w, h, dt, rotating);
        if (orbitalLayer != null) orbitalLayer.update(scene.surface(), scene.camera, scene.positions, scene.bondedBonds());
        drawSketch();                                                 // il suit la rotation de la vue, image par image
        drawChart();
    }

    /** Aperçus et tests : le panneau Méthode ouvert. */
    void showModel() {
        modelBanner.setVisible(true);
    }
}
