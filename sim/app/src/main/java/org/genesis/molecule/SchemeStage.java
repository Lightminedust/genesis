package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeScheme;
import com.studio.genesis.grpc.MoleculeSchemeStep;
import org.genesis.app.I18n;
import org.genesis.common.Icons;
import org.genesis.common.Plan;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * L'écran du chemin d'une étape du schéma : ouvert par « Voir le chemin » dans l'éditeur de schémas, à la place de la
 * scène 3D du laboratoire. Au centre, la vue 2.5D du replay (StageRenderer) ; en haut, les étapes ; à droite, ce qui se
 * mesure — l'énergie à la géométrie montrée et le long du chemin, la barrière, ΔE ; image par image, les indices de
 * Mayer, les longueurs et les charges de ce qui réagit, avec ce qui a le plus changé ; les liaisons qui changent à cet
 * instant ; le verdict ; en bas, le transport et la frise du chemin (son profil d'énergie, ses zones, ses images).
 * Les explications sont dans les « ? » : le panneau ne garde que les noms et les valeurs.
 *
 * <p>L'écran rejoue seul le chemin que le moteur a calculé (MoleculeSchemeStep : ses images, leurs énergies, leurs
 * charges et leurs liaisons) : la lecture avance avec l'horloge de l'écran, sans attendre le moteur, qui calcule
 * peut-être encore les étapes suivantes (voir PathMotion). Aux images, tout est calculé ; entre deux, la géométrie
 * glisse par corps rigides et les nombres sont interpolés. Rien n'est simulé ici. **Scène 3D** confie le même chemin au
 * moteur, depuis cette position.
 */
final class SchemeStage {
    interface Actions {
        /** Continuer dans la scène 3D du laboratoire : le moteur y joue ce chemin depuis cette position (0 → 1). */
        void openScene(int step, double position, boolean running, double speed);
        void openEditor();
    }

    /** Les vitesses de lecture : images du chemin par seconde (chaque géométrie montrée est calculée). */
    static final double[] SPEEDS = {1, 2.5, 6};
    private static final String[] SPEED_NAMES = {"Lente", "Normale", "Rapide"};
    private static final double CHART_W = Plan.RIGHT_WIDTH - 20;
    /** La marge droite des courbes image par image : leurs noms (« C13–H24 »). */
    private static final double LABELS = 66;
    private static final Color ACCENT = Color.web("#4FD1A9"), PANEL = Plan.PAPER;
    /** Les couleurs des charges des atomes qui réagissent (l'élément ne suffit pas à distinguer deux carbones). */
    private static final Color[] SERIES = {Color.web("#7CC4FF"), Color.web("#F5A45D"), Color.web("#5FE0B4"),
            Color.web("#C59BFF"), Color.web("#F2C14E"), Color.web("#FF7A9A"), Color.web("#9DE07A"), Color.web("#6FE3E3")};

    private final Actions actions;
    private final BorderPane root = new BorderPane();
    private final StageRenderer view = new StageRenderer();
    private final Label title = new Label();
    /** Les formules de l'étape (réactifs → produits), indices en vrais chiffres. */
    private final javafx.scene.text.TextFlow sub = new javafx.scene.text.TextFlow();
    private final HBox steps = new HBox(0);
    private final Canvas energyChart = new Canvas(CHART_W, 150), seriesChart = new Canvas(CHART_W, 168),
            table = new Canvas(CHART_W, 60);
    private final ToggleButton[] seriesButtons = new ToggleButton[3];
    private final Label energyNow = new Label(), energyKj = Plan.note(""), barrier = Plan.value(""), delta = Plan.value("");
    private final Label summary = Plan.note("");
    private final VBox verdict = new VBox(8);
    private final Button previous = Plan.icon("step-back", null, "Étape précédente");
    private final Button play = Plan.icon("play", null, "Jouer le chemin calculé (Espace)");
    private final Button next = Plan.icon("step-forward", null, "Étape suivante");
    private final Canvas scrubber = new Canvas(400, 44);
    private final Label readout = Plan.note("");
    private final ToggleButton[] speedButtons = new ToggleButton[SPEEDS.length];
    private final ToggleButton labels = Plan.iconToggle("tag", "Étiqueter tous les atomes (sinon, ceux qui réagissent)");
    private final ToggleButton chargesToggle = Plan.iconToggle("charge", "Colorer les atomes par leur charge de Mulliken, calculée à cette géométrie");
    private final ToggleButton curlyToggle = Plan.iconToggle("curly", "Les flèches courbes du dessin, posées sur les atomes calculés");
    private final ToggleButton trailsToggle = Plan.iconToggle("trail", "La trajectoire des atomes qui réagissent : un point par image calculée du chemin");
    private final ToggleButton hydrogensToggle = Plan.iconToggle("hydrogen", "Montrer les hydrogènes qui ne réagissent pas");
    private final ToggleButton stereoToggle = Plan.iconToggle("glasses", "Relief");
    /** Ce que dit le bouton du relief, selon le mode en cours (le clic passe au suivant). */
    private static final String[] STEREO_TIPS = {
            "Relief : aucun. Un clic : anaglyphe rouge-cyan (des lunettes rouge à gauche, cyan à droite)",
            "Relief : anaglyphe rouge-cyan — l'œil gauche en gris dans le rouge, l'œil droit en couleur. Un clic : côte à "
                    + "côte (visiocasque, écran 3D, vision parallèle)",
            "Relief : côte à côte — l'œil gauche à gauche. Un clic : sans relief"};
    private final Text probe = new Text();

    /** Le temps (s) sur la première image d'une étape ouverte en lecture : on voit d'où elle part. */
    static final double START_HOLD = .6;
    private double hold;

    /** `show(…, WHOLE, …)` : tout le schéma, ses étapes jouées à la suite. */
    static final int WHOLE = -2;
    /** La longueur d'un relais entre deux étapes, en images de la frise (≈ 0,8 s à la vitesse normale). */
    static final double RELAY = 2;

    private MoleculeScheme scheme;
    private MoleculeSchemeStep st;
    private StageRenderer.Path path;
    private PathMotion motion;

    // ------------------------------------------------------------------ tout le schéma
    /**
     * Un morceau de la lecture de tout le schéma : une étape (son chemin, aligné sur celle d'où viennent ses atomes), ou
     * le relais entre deux étapes (le sous-produit qui s'en va, le réactif écrit qui arrive, la molécule reprise qui
     * passe de la dernière image de l'une à la première de l'autre : rien n'y est calculé).
     */
    static final class Segment {
        final StageRenderer.Path path;
        final PathMotion motion;
        final int step, from, to;          // step : l'étape (−1 : un relais, de l'étape from à l'étape to)
        final double start, length;        // en images de la frise de tout le schéma
        final double base;                 // eV : la première image de l'étape dans le profil de tout le schéma

        Segment(StageRenderer.Path path, int step, int from, int to, double start, double base) {
            this.path = path;
            this.motion = new PathMotion(path);
            this.step = step;
            this.from = from;
            this.to = to;
            this.start = start;
            this.length = step >= 0 ? Math.max(0, path.count() - 1) : RELAY;
            this.base = base;
        }

        boolean relay() {
            return step < 0;
        }

        /** La position s du segment (0 → length) en images de son chemin. */
        double local(double s) {
            return step >= 0 ? s : s / RELAY;
        }
    }

    /** Vrai : tout le schéma, à la suite ; faux : une étape. */
    private boolean whole;
    private final List<Segment> playlist = new ArrayList<>();
    private Segment seg;
    /** La position dans la frise de tout le schéma (images, relais compris) et sa longueur. */
    private double global, total;
    /** Le chemin de chaque étape, ses images tournées pour suivre celles de l'étape d'où viennent ses atomes. */
    private final java.util.IdentityHashMap<MoleculeSchemeStep, StageRenderer.Path> aligned = new java.util.IdentityHashMap<>();
    /** Les hauteurs de chaque étape dans le profil de tout le schéma (voir levels). */
    private Levels levels;
    /** L'étape demandée qui ne s'est pas encore calculée (−1 : aucune) : elle s'ouvre dès qu'elle arrive. */
    private int wanted = -1;
    private int step = -1, series, chartHover = -1, seriesHover = -1, scrubHover = -1;
    private double position, energy, speed = SPEEDS[1], clock, readingsAt = -1;
    private boolean running, seeking, readingsDirty = true;
    private double pressX, pressY, lastX, lastY;
    private boolean dragging, panning;

    SchemeStage(Actions actions) {
        this.actions = actions;
        root.getStyleClass().addAll("fusion-lab", "molecule-lab", "plan-scene");
        root.setTop(topBar());
        root.setCenter(stage());
        root.setRight(side());
        root.setBottom(transport());
        root.setFocusTraversable(true);
        root.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case SPACE -> toggle();
                case LEFT -> nudge(-1);
                case RIGHT -> nudge(1);
                case HOME -> seek(0);
                case END -> seek(1);
                case R -> {
                    view.reset();
                    drawStage();
                }
                case ESCAPE -> back();
                default -> { return; }
            }
            e.consume();
        });
        root.setVisible(false);
    }

    Region getNode() {
        return root;
    }

    boolean isOpen() {
        return root.isVisible();
    }

    /** La vitesse choisie (images du chemin par seconde). */
    double speed() {
        return speed;
    }

    /** Ouvre l'écran sur l'étape `wanted`, jouée depuis le début (−1 : celle qui est là), ou le referme. */
    void show(boolean open, int wanted) {
        show(open, wanted, 0, true);
    }

    /** Ouvre l'écran sur l'étape `wanted` à la position `at` (0 → 1), jouée ou non (−1 : celle qui est là), ou le referme. */
    void show(boolean open, int wanted, double at, boolean playing) {
        root.setVisible(open);
        if (!open) {
            running = false;
            opening(false);
            return;
        }
        if (wanted == WHOLE) {
            if (anyComputed()) {
                this.wanted = -1;
                loadWhole(0, playing);
            } else {
                this.wanted = WHOLE;
            }
        } else if (wanted >= 0) {
            if (computed(wanted)) {
                this.wanted = -1;
                load(wanted, at, playing);
            } else {
                this.wanted = wanted;
            }
        }
        root.requestFocus();
        readingsDirty = true;
        drawStage();
    }

    /** La position montrée (0 → 1) et l'étape : la scène 3D reprend de là. */
    double position() {
        return position;
    }

    int step() {
        return step;
    }

    boolean running() {
        return running;
    }

    private void back() {
        running = false;
        actions.openEditor();
    }

    // ================================================================== l'interface
    private final Button scene = Plan.icon("orbit", "Scène 3D", "Continuer dans la scène 3D du laboratoire : le même chemin, "
            + "puis la scène libre (chauffer, relaxer, tirer un atome)");

    /** Pendant que le moteur pose l'étape dans la scène 3D : le bouton le dit, et ne repart pas. */
    void opening(boolean on) {
        scene.setDisable(on);
        scene.setText(I18n.text(on ? "Scène 3D…" : "Scène 3D"));
    }

    private Region topBar() {
        Button back = Plan.icon("chevron-left", "Schéma", "Revenir à l'éditeur de schémas (Échap)");
        back.setOnAction(e -> back());
        title.getStyleClass().add("plan-heading");
        VBox titles = new VBox(2, title, sub);
        HBox.setHgrow(titles, Priority.ALWAYS);
        titles.setMinWidth(0);
        scene.setOnAction(e -> {
            // Au bout du chemin (la lecture finie), la scène 3D repart du début de l'étape, pas de sa dernière image.
            double from = whole && seg != null && seg.relay() || position >= 1 - 1e-9 ? 0 : position;
            if (step >= 0) actions.openScene(step, from, running || from == 0 && position >= 1 - 1e-9, speed);
        });
        HBox bar = new HBox(14, back, titles, steps, scene);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 14, 10, 14));
        bar.getStyleClass().add("plan-bar");
        return bar;
    }

    private Region stage() {
        Pane holder = view.getNode();
        Pane canvas = view.input();
        holder.widthProperty().addListener((o, a, b) -> drawStage());
        holder.heightProperty().addListener((o, a, b) -> drawStage());
        canvas.setOnMousePressed(e -> {
            root.requestFocus();
            pressX = lastX = e.getX();
            pressY = lastY = e.getY();
            panning = e.getButton() != MouseButton.PRIMARY || e.isShiftDown();
            dragging = false;
        });
        canvas.setOnMouseDragged(e -> {
            double dx = e.getX() - lastX, dy = e.getY() - lastY;
            lastX = e.getX();
            lastY = e.getY();
            if (!dragging && Math.hypot(e.getX() - pressX, e.getY() - pressY) < 3) return;
            dragging = true;
            if (panning) view.pan(dx, dy);
            else view.rotate(dx, dy);
            canvas.setCursor(Cursor.CLOSED_HAND);
            drawStage();
        });
        canvas.setOnMouseReleased(e -> {
            canvas.setCursor(Cursor.OPEN_HAND);
            if (!dragging && e.getButton() == MouseButton.PRIMARY) {
                if (view.legendClick(e.getX(), e.getY())) drawStage();
                else if (e.getClickCount() == 2) {
                    view.reset();
                    drawStage();
                }
            }
            dragging = false;
        });
        canvas.setOnMouseMoved(e -> {
            view.mouseX = e.getX();
            view.mouseY = e.getY();
            int atom = view.pick(e.getX(), e.getY());
            int change = atom >= 0 ? -1 : view.pickChange(e.getX(), e.getY());
            if (atom != view.hovered || change != view.hoveredChange || atom >= 0 || change >= 0) {
                view.hovered = atom;
                view.hoveredChange = change;
                drawStage();
            }
            canvas.setCursor(atom >= 0 || change >= 0 ? Cursor.HAND : Cursor.OPEN_HAND);
        });
        canvas.setOnMouseExited(e -> {
            view.hovered = view.hoveredChange = -1;
            drawStage();
        });
        canvas.setOnScroll(e -> {
            view.zoomAt(e.getX(), e.getY(), e.getDeltaY());
            drawStage();
        });
        canvas.setCursor(Cursor.OPEN_HAND);
        // La barre de la vue, en haut à droite : les calques, et la vue calculée.
        for (ToggleButton b : new ToggleButton[]{labels, chargesToggle, curlyToggle, trailsToggle, hydrogensToggle}) {
            b.setOnAction(e -> option());
        }
        curlyToggle.setSelected(true);
        hydrogensToggle.setSelected(true);
        Button reset = Plan.icon("target", null, "La vue calculée : le long de l'axe le plus mince des atomes qui réagissent, "
                + "un peu inclinée (double-clic, R)");
        reset.setOnAction(e -> {
            view.reset();
            drawStage();
        });
        // Le relief : aucun → anaglyphe rouge-cyan → côte à côte → aucun.
        stereoToggle.setOnAction(e -> {
            StageRenderer.Stereo next = switch (view.stereo()) {
                case OFF -> StageRenderer.Stereo.ANAGLYPH;
                case ANAGLYPH -> StageRenderer.Stereo.SIDE;
                case SIDE -> StageRenderer.Stereo.OFF;
            };
            view.stereo(next);
            stereoToggle.setSelected(next != StageRenderer.Stereo.OFF);
            stereoToggle.setTooltip(I18n.tooltip(STEREO_TIPS[next.ordinal()]));
            drawStage();
            root.requestFocus();
        });
        stereoToggle.setTooltip(I18n.tooltip(STEREO_TIPS[0]));
        HBox viewBar = new HBox(2, labels, chargesToggle, curlyToggle, trailsToggle, hydrogensToggle, separator(), stereoToggle,
                reset);
        viewBar.setAlignment(Pos.CENTER_LEFT);
        viewBar.setPadding(new Insets(4));
        viewBar.setStyle("-fx-background-color: rgba(10,20,34,0.82); -fx-background-radius: 12;"
                + " -fx-border-color: rgba(255,255,255,0.06); -fx-border-radius: 12;");
        viewBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane stack = new StackPane(holder, viewBar);
        StackPane.setAlignment(viewBar, Pos.TOP_RIGHT);
        StackPane.setMargin(viewBar, new Insets(14, 14, 0, 0));
        return stack;
    }

    private static Region separator() {
        Region line = new Region();
        line.setPrefSize(1, 18);
        line.setMaxHeight(18);
        line.setStyle("-fx-background-color: rgba(255,255,255,0.10);");
        HBox.setMargin(line, new Insets(0, 4, 0, 4));
        return line;
    }

    private void option() {
        view.allLabels = labels.isSelected();
        view.charges = chargesToggle.isSelected();
        view.curly = curlyToggle.isSelected();
        view.trails = trailsToggle.isSelected();
        view.hydrogens = hydrogensToggle.isSelected();
        view.invalidate();
        drawStage();
        root.requestFocus();
    }

    private ScrollPane side() {
        String[] names = {"Liaisons", "Distances", "Charges"};
        String[] tips = {"L'indice de Mayer de chaque liaison qui change, image par image (calculé)",
                "La longueur de chaque liaison qui change, image par image (Å, mesurée sur les images calculées)",
                "La charge de Mulliken de chaque atome qui réagit, image par image (e, calculée)"};
        ToggleGroup group = new ToggleGroup();
        for (int k = 0; k < 3; k++) {
            ToggleButton b = new ToggleButton(names[k]);
            b.setToggleGroup(group);
            b.setTooltip(I18n.tooltip(tips[k]));
            final int index = k;
            b.setOnAction(e -> {
                b.setSelected(true);
                series = index;
                readingsDirty = true;
                refreshReadings();
                root.requestFocus();
            });
            seriesButtons[k] = b;
        }
        seriesButtons[0].setSelected(true);
        chart(energyChart, true);
        chart(seriesChart, false);
        // L'énergie : la valeur à la géométrie montrée en grand ; la barrière et ΔE dessous ; la courbe.
        energyKj.setWrapText(false);
        energyNow.getStyleClass().add("plan-metric");
        Label unit = new Label("eV");
        unit.getStyleClass().add("plan-unit");
        HBox value = new HBox(6, energyNow, unit, energyKj);
        value.setAlignment(Pos.BASELINE_LEFT);
        HBox.setMargin(energyKj, new Insets(0, 0, 0, 8));
        VBox heroLine = new VBox(1, Plan.label("sur le chemin, depuis le départ"), value);
        VBox barrierBox = new VBox(2, Plan.label("barrière de ce chemin imposé"), barrier);
        VBox deltaBox = new VBox(2, Plan.label("ΔE, produits − réactifs"), delta);
        HBox.setHgrow(barrierBox, Priority.ALWAYS);
        HBox.setHgrow(deltaBox, Priority.ALWAYS);
        barrierBox.setMaxWidth(Double.MAX_VALUE);
        deltaBox.setMaxWidth(Double.MAX_VALUE);
        HBox figures = new HBox(12, barrierBox, deltaBox);
        rate.setWrapText(true);
        slow.setWrapText(true);
        slow.setStyle("-fx-text-fill:" + Plan.hex(Plan.MARK) + ";");
        entropyLine.setWrapText(true);
        VBox rates = new VBox(3, entropyLine, rate, slow);
        VBox energySection = Plan.trailing(Plan.section("Énergie", heroLine, figures, rates, energyChart),
                Plan.why("L'énergie le long du chemin", "Calculée par GFN1-xTB à chaque image du chemin (la courbe, les "
                        + "points), depuis la première image ; entre deux images, interpolée (le point qui avance). La "
                        + "barrière : la plus haute image du chemin imposé moins son départ — un chemin imposé, pas un état "
                        + "de transition cherché. ΔE (le trait pointillé) : les produits et les réactifs relaxés chacun seul ; "
                        + "la dernière image du chemin, elle, garde les molécules formées côte à côte. En tirets : un saut "
                        + "de branche, où rien n'est calculé entre deux images. Un clic place le chemin là. Le temps de "
                        + "demi-réaction : la formule d'Eyring, k = (k_B T / h) · e^(−E / k_B T), avec la barrière prise pour "
                        + "l'enthalpie libre d'activation — un ordre de grandeur : GFN1-xTB sous-estime souvent les "
                        + "barrières, un chemin imposé les surestime.", null));
        summary.setWrapText(true);
        VBox seriesSection = folding("Image par image", true, Plan.why("Image par image", "Pour ce qui réagit, à chaque "
                + "image calculée du chemin : l'indice de Mayer et la longueur de chaque liaison qui change (vert : se forme ; "
                + "orange : se rompt ; bleu : son ordre change), la charge de Mulliken de chaque atome (δ− en gagne, δ+ en "
                + "perd). Le point : la valeur à la position montrée. Au survol, les valeurs de l'image. Dessous, ce qui a "
                + "le plus changé du début à la fin.", null), Plan.segmented(true, seriesButtons), seriesChart, summary);
        VBox tableSection = folding("Liaisons qui changent", true, Plan.why("Liaisons qui changent", "À la position "
                + "montrée : la longueur et l'indice de Mayer (calculés aux images, interpolés entre elles) de chaque "
                + "liaison que le dessin forme, rompt, ou dont il change l'ordre.", null), table);
        // Sur un petit écran (1366 × 768), la colonne commence plus courte : le verdict et ses remarques repliés.
        boolean small = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() < 820;
        VBox calculation = folding("Le calcul", !small, null, verdict);
        stepContent.getChildren().setAll(energySection, seriesSection, tableSection, calculation);
        stepContent.setPadding(new Insets(16, 18, 18, 18));
        wholeContent.getChildren().setAll(wholeSections());
        wholeContent.setPadding(new Insets(16, 18, 18, 18));
        ScrollPane pane = sidePane;
        pane.setContent(stepContent);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        double width = Plan.RIGHT_WIDTH + 20;
        pane.setPrefWidth(width);
        pane.setMinWidth(width);
        pane.setMaxWidth(width);
        pane.getStyleClass().addAll("plan-side", "right");
        return pane;
    }

    private final ScrollPane sidePane = new ScrollPane();
    /** La colonne de droite : celle d'une étape, celle de tout le schéma. */
    private final VBox stepContent = new VBox(22), wholeContent = new VBox(22);
    private final Label rate = Plan.note(""), slow = Plan.note(""), entropyLine = Plan.note("");

    // ------------------------------------------------------------------ la colonne de tout le schéma
    private final Canvas wholeChart = new Canvas(CHART_W, 170);
    private final Label wholeNow = new Label(), wholeKj = Plan.note(""), wholeDelta = Plan.value(""), wholeTop = Plan.value("");
    private final Label wholeTopCaption = Plan.label("plus haute barrière");
    private final VBox stepRows = new VBox(4), balance = new VBox(8);
    private int wholeHover = -1;

    /**
     * Tout le schéma : l'énergie à la position montrée (depuis les réactifs du départ), ΔE de tout le schéma et la plus
     * haute barrière, le profil de toutes les étapes à leur hauteur ; la liste des étapes (un clic en ouvre une) ; le
     * bilan — ce qui se forme et se rompt du début à la fin, ce qui entre et sort en route, l'étape la plus lente.
     */
    private javafx.scene.Node[] wholeSections() {
        wholeNow.getStyleClass().add("plan-metric");
        Label unit = new Label("eV");
        unit.getStyleClass().add("plan-unit");
        HBox value = new HBox(6, wholeNow, unit, wholeKj);
        value.setAlignment(Pos.BASELINE_LEFT);
        HBox.setMargin(wholeKj, new Insets(0, 0, 0, 8));
        VBox hero = new VBox(1, Plan.label("depuis les réactifs du départ"), value);
        VBox deltaBox = new VBox(2, Plan.label("ΔE de tout le schéma"), wholeDelta);
        VBox topBox = new VBox(2, wholeTopCaption, wholeTop);
        HBox.setHgrow(deltaBox, Priority.ALWAYS);
        HBox.setHgrow(topBox, Priority.ALWAYS);
        deltaBox.setMaxWidth(Double.MAX_VALUE);
        topBox.setMaxWidth(Double.MAX_VALUE);
        wholeChart.setOnMouseMoved(e -> {
            int k = wholeStepAt(e.getX());
            if (k != wholeHover) {
                wholeHover = k;
                paintWhole();
            }
            wholeChart.setCursor(k >= 0 ? Cursor.HAND : Cursor.DEFAULT);
        });
        wholeChart.setOnMouseExited(e -> {
            wholeHover = -1;
            paintWhole();
        });
        wholeChart.setOnMouseClicked(e -> {
            double s = wholeX(e.getX());
            if (s >= 0) seekWhole(s);
        });
        VBox energy = Plan.trailing(Plan.section("Tout le schéma", hero, new HBox(12, deltaBox, topBox), wholeChart),
                Plan.why("Le profil de tout le schéma", "Chaque étape à sa hauteur : l'énergie de chacune de ses images "
                        + "(calculée par GFN1-xTB), comptée depuis les réactifs du départ relaxés chacun seul, les molécules "
                        + "qui ne réagissent pas encore — ou plus — loin de celles qui réagissent. Entre deux étapes, le "
                        + "palier : les produits de l'une relaxés chacun seul, l'intermédiaire (ΔE de l'étape) ; s'ils "
                        + "changent de milieu (du toluène à l'eau), ce que coûte ce passage. En pointillés : le relais, où "
                        + "rien n'est calculé entre la dernière image d'une étape et la première de la suivante. Un clic "
                        + "place la lecture là.", null));
        VBox steps = Plan.section("Les étapes", stepRows);
        VBox sum = Plan.trailing(Plan.section("Bilan du schéma", balance), Plan.why("Le bilan", "Ce qui se forme et se rompt du début "
                + "à la fin du schéma (les atomes gardent leur numéro d'une étape à l'autre) ; ce qui entre en route (écrit "
                + "sur les flèches) et ce qui sort (les sous-produits déduits) ; l'étape à la plus haute barrière, et son "
                + "temps de demi-réaction (Eyring, à la température écrite sur sa flèche, sinon 300 K).", null));
        return new javafx.scene.Node[]{energy, steps, sum};
    }

    /** Une section qu'on replie (son titre cliquable), avec son « ? » à droite, avant le chevron. */
    private static VBox folding(String title, boolean open, Label why, javafx.scene.Node... nodes) {
        VBox section = Plan.foldable(title, open, nodes);
        if (why != null) {
            HBox head = (HBox) section.getChildren().get(0);
            why.setOnMouseClicked(javafx.event.Event::consume);    // le « ? » s'explique ; il ne replie pas la section
            HBox.setMargin(why, new Insets(0, 6, 0, 0));
            head.getChildren().add(head.getChildren().size() - 1, why);
        }
        return section;
    }

    /** Un graphique du chemin : survoler montre une image, cliquer y place le chemin. */
    private void chart(Canvas c, boolean energyCurve) {
        c.setOnMouseMoved(e -> {
            int f = frameAt(c, e.getX(), 34, energyCurve ? 8 : LABELS);
            if (energyCurve && f != chartHover) {
                chartHover = f;
                paintEnergy();
            } else if (!energyCurve && f != seriesHover) {
                seriesHover = f;
                paintSeries();
            }
            c.setCursor(f >= 0 ? Cursor.HAND : Cursor.DEFAULT);
        });
        c.setOnMouseExited(e -> {
            chartHover = seriesHover = -1;
            paintEnergy();
            paintSeries();
        });
        c.setOnMouseClicked(e -> {
            int f = frameAt(c, e.getX(), 34, energyCurve ? 8 : LABELS);
            if (f >= 0 && path != null) seek(f / (double) Math.max(1, path.count() - 1));
        });
    }

    private Region transport() {
        previous.setOnAction(e -> {
            if (whole) jumpStep(-1);
            else if (computed(step - 1)) load(step - 1, 0, true);
        });
        next.setOnAction(e -> {
            if (whole) jumpStep(1);
            else if (computed(step + 1)) load(step + 1, 0, true);
        });
        play.setOnAction(e -> toggle());
        play.getStyleClass().add("play-main");
        Pane track = new Pane(scrubber);
        track.setMinWidth(120);
        track.setPrefHeight(44);
        scrubber.widthProperty().bind(track.widthProperty());
        scrubber.widthProperty().addListener((o, a, b) -> paintScrubber());
        HBox.setHgrow(track, Priority.ALWAYS);
        scrubber.setOnMousePressed(e -> {
            seeking = true;
            scrub(e.getX());
        });
        scrubber.setOnMouseDragged(e -> scrub(e.getX()));
        scrubber.setOnMouseReleased(e -> {
            scrub(e.getX());
            seeking = false;
        });
        scrubber.setOnMouseMoved(e -> {
            double along = Math.max(0, Math.min(1, (e.getX() - 10) / Math.max(1, scrubber.getWidth() - 20)));
            int f = whole ? (int) Math.round(along * total) : path == null ? -1 : (int) Math.round(along * (path.count() - 1));
            if (f != scrubHover) {
                scrubHover = f;
                paintScrubber();
            }
        });
        scrubber.setOnMouseExited(e -> {
            scrubHover = -1;
            paintScrubber();
        });
        scrubber.setCursor(Cursor.HAND);
        readout.setMinWidth(186);
        readout.setPrefWidth(186);
        readout.setWrapText(false);
        ToggleGroup group = new ToggleGroup();
        for (int k = 0; k < SPEEDS.length; k++) {
            ToggleButton b = new ToggleButton(SPEED_NAMES[k]);
            b.setToggleGroup(group);
            b.setTooltip(I18n.tooltip(MoleculeFormat.decimal(SPEEDS[k], SPEEDS[k] == Math.rint(SPEEDS[k]) ? 0 : 1)
                    + " " + I18n.text(SPEEDS[k] > 1 ? "images du chemin par seconde" : "image du chemin par seconde")
                    + I18n.text(" — chaque géométrie montrée est calculée")));
            final int index = k;
            b.setOnAction(e -> {
                b.setSelected(true);
                speed = SPEEDS[index];
                root.requestFocus();
            });
            speedButtons[k] = b;
        }
        speedButtons[1].setSelected(true);
        HBox speeds = Plan.segmented(false, speedButtons);
        HBox buttons = new HBox(2, previous, play, next);
        buttons.setAlignment(Pos.CENTER_LEFT);
        HBox bar = new HBox(14, buttons, track, readout, speeds);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 14, 8, 14));
        bar.getStyleClass().add("plan-bar");
        return bar;
    }

    // ================================================================== le schéma calculé
    /**
     * Le schéma (ses étapes arrivent une à une pendant le calcul ; entre deux, le même objet revient). Les boutons des
     * étapes ne sont refaits que si les étapes changent ; l'étape montrée n'est relue que si son calcul a changé — la
     * vue, sa rotation et la lecture ne repartent pas de zéro à chaque nouvelle publication du moteur.
     */
    void update(MoleculeScheme latest) {
        if (latest == null || latest == scheme) return;
        boolean same = scheme != null && sameSteps(scheme, latest);
        if (scheme == null || scheme.getRequest() != latest.getRequest()) aligned.clear();
        scheme = latest;
        if (!same) {
            stepButtons();
            levels = new Levels(latest);
        }
        if (whole) {
            // Tout le schéma : une étape de plus calculée allonge la frise ; la lecture garde sa place.
            if (latest.getRequest() != request) unload();
            else if (!same) rebuildWhole();
        } else if (step >= latest.getStepsCount() || step >= 0 && latest.getRequest() != request) {
            unload();
        } else if (step >= 0) {
            MoleculeSchemeStep now = latest.getSteps(step);
            if (now != st) {
                if (!now.getComputed()) unload();
                else if (now.equals(st)) st = now;
                else load(step, position, running);
            }
        }
        if (wanted >= 0 && computed(wanted)) {
            int k = wanted;
            wanted = -1;
            load(k, 0, true);
        } else if (wanted == WHOLE && anyComputed()) {
            wanted = -1;
            loadWhole(0, true);
        }
        if (isOpen()) readingsDirty = true;
    }

    private boolean anyComputed() {
        if (scheme == null) return false;
        for (int k = 0; k < scheme.getStepsCount(); k++) if (computed(k)) return true;
        return false;
    }

    /** La demande de calcul de l'étape montrée (un nouveau calcul du schéma la remplace). */
    private int request = -1;

    private static boolean sameSteps(MoleculeScheme a, MoleculeScheme b) {
        if (a.getRequest() != b.getRequest() || a.getStepsCount() != b.getStepsCount()) return false;
        for (int k = 0; k < a.getStepsCount(); k++) if (a.getSteps(k) != b.getSteps(k)) return false;
        return true;
    }

    private boolean computed(int k) {
        return scheme != null && k >= 0 && k < scheme.getStepsCount() && scheme.getSteps(k).getComputed();
    }

    /** L'étape k, à la position `at` (0 → 1), jouée ou non. */
    private void load(int k, double at, boolean playing) {
        // Une autre étape du même schéma : ses images sont alignées sur la précédente, la vue glisse au lieu de sauter.
        boolean continuous = path != null && scheme.getRequest() == request;
        whole = false;
        seg = null;
        step = k;
        st = scheme.getSteps(k);
        request = scheme.getRequest();
        path = aligned(k);
        motion = new PathMotion(path);
        view.setPath(path, continuous);
        view.jumpCause = motion::jump;
        position = Math.max(0, Math.min(1, at));
        running = playing && path.count() > 1;
        if (running && position >= 1 - 1e-9) position = 0;
        // Ouverte en lecture depuis le début : un temps sur la première image, avant qu'elle n'avance.
        hold = running && position < 1e-9 ? START_HOLD : 0;
        sidePane.setContent(stepContent);
        fillStatic();
        place(false);
        play.setGraphic(Icons.path(running ? "pause" : "play", 16));
        readingsDirty = true;
    }

    private void unload() {
        step = -1;
        st = null;
        path = null;
        motion = null;
        seg = null;
        playlist.clear();
        total = global = 0;
        running = false;
        view.setPath(null);
        view.setPose(null);
        readingsDirty = true;
    }

    // ================================================================== tout le schéma, à la suite
    /** Tout le schéma, à la position `at` (images de sa frise), joué ou non. */
    private void loadWhole(double at, boolean playing) {
        boolean continuous = path != null && scheme.getRequest() == request;
        whole = true;
        request = scheme.getRequest();
        levels = new Levels(scheme);
        buildPlaylist();
        if (playlist.isEmpty()) {
            unload();
            return;
        }
        global = Math.max(0, Math.min(total, at));
        running = playing && total > 0;
        if (running && global >= total - 1e-9) global = 0;
        seg = null;
        enter(segmentAt(global), continuous);
        sidePane.setContent(wholeContent);
        fillWhole();
        place(false);
        play.setGraphic(Icons.path(running ? "pause" : "play", 16));
        readingsDirty = true;
    }

    /** De nouvelles étapes calculées : la frise refaite, la lecture à la même place. */
    private void rebuildWhole() {
        buildPlaylist();
        if (playlist.isEmpty()) {
            unload();
            return;
        }
        global = Math.min(global, total);
        Segment keep = seg;
        seg = null;
        Segment now = segmentAt(global);
        if (keep != null && now.path == keep.path) {
            seg = now;
            motion = now.motion;
            view.jumpCause = motion::jump;
        } else {
            enter(now, true);
        }
        fillWhole();
        place(false);
    }

    /**
     * La frise de tout le schéma : chaque étape calculée, dans l'ordre, et entre deux, leur relais. Les chemins sont ceux
     * des étapes, alignés (aligned) ; leur énergie posée à leur hauteur dans le profil de tout le schéma (Levels).
     */
    private void buildPlaylist() {
        playlist.clear();
        total = 0;
        if (scheme == null) return;
        if (levels == null) levels = new Levels(scheme);
        int before = -1;
        for (int k = 0; k < scheme.getStepsCount(); k++) {
            if (!computed(k)) continue;
            StageRenderer.Path p = aligned(k);
            if (before >= 0) {
                Segment r = new Segment(relay(aligned(before), p), -1, before, k, total, Double.NaN);
                r.path.relayNote = I18n.text("étape") + " " + (before + 1) + " → " + (k + 1) + " · "
                        + I18n.text("passage non calculé : la fin de l'une glisse vers le début de l'autre");
                playlist.add(r);
                total += r.length;
            }
            Segment s = new Segment(p, k, k, k, total, levels.base[k]);
            playlist.add(s);
            total += s.length;
            before = k;
        }
    }

    /** Le morceau de la frise à la position s (le suivant, pile à sa frontière ; le dernier, au bout). */
    private Segment segmentAt(double s) {
        Segment found = playlist.isEmpty() ? null : playlist.get(0);
        for (Segment p : playlist) if (p.start <= s + 1e-9) found = p;
        return found;
    }

    /** La lecture entre dans un morceau de la frise : son chemin montré, la vue glissant depuis le précédent. */
    private void enter(Segment s, boolean continuous) {
        if (s == null) return;
        boolean same = seg != null && seg.path == s.path;
        seg = s;
        path = s.path;
        motion = s.motion;
        step = s.relay() ? s.to : s.step;
        st = scheme.getSteps(step);
        if (!same) view.setPath(path, continuous);
        view.jumpCause = motion::jump;
        curlyToggle.setDisable(path.curly.length == 0);
        selectTabs();
        readingsDirty = true;
    }

    /** La position de l'étape k, à la position p (0 → 1) de son chemin, dans la frise de tout le schéma. */
    private double wholePosition(int k, double p) {
        for (Segment s : playlist) if (s.step == k) return s.start + p * s.length;
        return 0;
    }

    /** Dans tout le schéma : au début de l'étape précédente (ou de celle-ci, si l'on y est déjà avancé) ou suivante. */
    private void jumpStep(int direction) {
        if (playlist.isEmpty() || seg == null) return;
        int here = playlist.indexOf(seg);
        Segment target = null;
        if (direction < 0) {
            if (!seg.relay() && global - seg.start > .5) target = seg;
            for (int i = here - 1; i >= 0 && target == null; i--) if (!playlist.get(i).relay()) target = playlist.get(i);
        } else {
            for (int i = here + 1; i < playlist.size() && target == null; i++) if (!playlist.get(i).relay()) target = playlist.get(i);
        }
        if (target != null) seekWhole(target.start);
    }

    /** Place la lecture de tout le schéma à la position s (images de la frise). */
    private void seekWhole(double s) {
        if (playlist.isEmpty()) return;
        global = Math.max(0, Math.min(total, s));
        Segment now = segmentAt(global);
        if (now != seg) enter(now, true);
        place(false);
        readingsDirty = true;
        refreshReadings();
    }

    /**
     * Le chemin de l'étape k tel que la vue le montre : ses atomes numérotés comme dans tout le schéma, et ses images
     * tournées et déplacées d'un seul bloc (la même rotation pour toutes, rien d'autre ne change : distances, énergies
     * et charges restent celles du calcul) pour que les molécules reprises d'une étape précédente y soient là où cette
     * étape les a laissées. Ainsi l'étape 2 commence où l'étape 1 finit, au lieu d'une autre image.
     */
    private StageRenderer.Path aligned(int k) {
        MoleculeSchemeStep s = scheme.getSteps(k);
        StageRenderer.Path known = aligned.get(s);
        if (known != null) return known;
        StageRenderer.Path p = path(s);
        int from = previousOf(k);
        if (from >= 0) align(p, aligned(from));
        aligned.put(s, p);
        return p;
    }

    /** L'étape calculée d'avant qui partage le plus d'atomes avec l'étape k (−1 : aucune). */
    private int previousOf(int k) {
        java.util.Set<Integer> here = new java.util.HashSet<>(scheme.getSteps(k).getIdsList());
        int best = -1, most = 0;
        for (int j = k - 1; j >= 0; j--) {
            if (!computed(j)) continue;
            int shared = 0;
            for (int id : scheme.getSteps(j).getIdsList()) if (here.contains(id)) shared++;
            if (shared > most) {
                most = shared;
                best = j;
            }
        }
        return best;
    }

    /**
     * Tourne et déplace d'un bloc toutes les images de `p` pour que ses atomes partagés avec `before` (même numéro), dans
     * sa première image, recouvrent au mieux ceux de la dernière image de `before` (la rotation de Horn, PathMotion).
     */
    static void align(StageRenderer.Path p, StageRenderer.Path before) {
        if (p.count() == 0 || before.count() == 0) return;
        java.util.Map<Integer, Integer> there = new java.util.HashMap<>();
        for (int j = 0; j < before.z.length; j++) there.put(before.number(j), j);
        double[][] first = p.frames[0], last = before.frames[before.count() - 1];
        List<double[]> src = new ArrayList<>(), dst = new ArrayList<>();
        for (int i = 0; i < p.z.length; i++) {
            Integer j = there.get(p.number(i));
            if (j != null && p.z[i] == before.z[j]) {
                src.add(first[i]);
                dst.add(last[j]);
            }
        }
        if (src.isEmpty()) return;
        double[] cs = new double[3], cd = new double[3];
        for (int m = 0; m < src.size(); m++) for (int c = 0; c < 3; c++) {
            cs[c] += src.get(m)[c] / src.size();
            cd[c] += dst.get(m)[c] / src.size();
        }
        double[] inverse = {1, 0, 0, 0};
        if (src.size() >= 2) {
            double[][] s = new double[3][3];
            for (int m = 0; m < src.size(); m++) {
                for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) s[r][c] += (dst.get(m)[r] - cd[r]) * (src.get(m)[c] - cs[c]);
            }
            double[] q = PathMotion.horn(s);                         // tourne la forme de `before` vers celle de p
            inverse = new double[]{q[0], -q[1], -q[2], -q[3]};
        }
        for (double[][] frame : p.frames) {
            for (int i = 0; i < frame.length; i++) {
                double[] v = PathMotion.rotate(inverse, new double[]{frame[i][0] - cs[0], frame[i][1] - cs[1], frame[i][2] - cs[2]});
                for (int c = 0; c < 3; c++) frame[i][c] = v[c] + cd[c];
            }
        }
    }

    /**
     * Le relais de l'étape `a` à l'étape `b` (leurs chemins alignés) : deux images — la dernière de a, la première de b —
     * sur les atomes de l'une et de l'autre. La molécule reprise passe de l'une à l'autre (par corps rigides, PathMotion) ;
     * un atome qui ne continue pas (un sous-produit) s'efface où il est ; un atome qui arrive (un réactif écrit sur la
     * flèche) paraît où l'étape b le pose. Rien n'est calculé entre ces deux images : la vue le dit.
     */
    static StageRenderer.Path relay(StageRenderer.Path a, StageRenderer.Path b) {
        int na = a.z.length;
        java.util.Map<Integer, Integer> inA = new java.util.HashMap<>();
        for (int i = 0; i < na; i++) inA.put(a.number(i), i);
        int[] where = new int[b.z.length];
        int extra = 0;
        for (int i = 0; i < b.z.length; i++) {
            Integer j = inA.get(b.number(i));
            where[i] = j != null && a.z[j] == b.z[i] ? j : na + extra++;
        }
        int n = na + extra;
        int[] z = new int[n], ids = new int[n];
        double[] radii = new double[n];
        double[][][] frames = new double[2][n][];
        double[][] q = new double[2][n], alpha = new double[2][n];
        boolean[] stays = new boolean[na];
        double[][] lastA = a.frames[a.count() - 1], firstB = b.frames[0];
        double[] qa = a.charges.length > 0 ? a.charges[a.charges.length - 1] : new double[0];
        double[] qb = b.charges.length > 0 ? b.charges[0] : new double[0];
        for (int u = 0; u < na; u++) {
            z[u] = a.z[u];
            ids[u] = a.number(u);
            radii[u] = u < a.radii.length ? a.radii[u] : 0;
            frames[0][u] = lastA[u].clone();
            alpha[0][u] = 1;
            q[0][u] = u < qa.length ? qa[u] : 0;
        }
        for (int i = 0; i < b.z.length; i++) {
            int u = where[i];
            double charge = i < qb.length ? qb[i] : 0;
            if (u >= na) {
                z[u] = b.z[i];
                ids[u] = b.number(i);
                radii[u] = i < b.radii.length ? b.radii[i] : 0;
                frames[0][u] = firstB[i].clone();
                alpha[0][u] = 0;
                q[0][u] = charge;
            } else {
                stays[u] = true;
            }
            frames[1][u] = firstB[i].clone();
            alpha[1][u] = 1;
            q[1][u] = charge;
        }
        for (int u = 0; u < na; u++) {
            if (stays[u]) continue;
            frames[1][u] = lastA[u].clone();
            alpha[1][u] = 0;
            q[1][u] = q[0][u];
        }
        // Les liaisons : celles de la dernière image de a, celles de la première de b (une paire absente d'un côté y
        // garde l'indice de l'autre : la même molécule, ou un atome absent).
        java.util.Map<Long, double[]> orders = new java.util.LinkedHashMap<>();
        double[] oa = a.bondOrders.length > 0 ? a.bondOrders[a.bondOrders.length - 1] : new double[0];
        double[] ob = b.bondOrders.length > 0 ? b.bondOrders[0] : new double[0];
        for (int j = 0; j < a.bondPairs.length; j++) {
            orders.put(pair(a.bondPairs[j][0], a.bondPairs[j][1]), new double[]{j < oa.length ? oa[j] : 0, Double.NaN});
        }
        for (int j = 0; j < b.bondPairs.length; j++) {
            double[] v = orders.computeIfAbsent(pair(where[b.bondPairs[j][0]], where[b.bondPairs[j][1]]),
                    key -> new double[]{Double.NaN, Double.NaN});
            v[1] = j < ob.length ? ob[j] : 0;
        }
        int[][] bondPairs = new int[orders.size()][];
        double[][] bondOrders = new double[2][orders.size()];
        int j = 0;
        for (java.util.Map.Entry<Long, double[]> e : orders.entrySet()) {
            long key = e.getKey();
            bondPairs[j] = new int[]{(int) (key >> 32), (int) key};
            double o0 = e.getValue()[0], o1 = e.getValue()[1];
            bondOrders[0][j] = Double.isNaN(o0) ? o1 : o0;
            bondOrders[1][j] = Double.isNaN(o1) ? o0 : o1;
            j++;
        }
        StageRenderer.Path out = new StageRenderer.Path(z, frames, q, new double[]{0, 0}, 0, 2, new int[0][], new double[2][0],
                new int[0][], new HashSet<>(), bondPairs, bondOrders, radii);
        out.ids = ids;
        out.alpha = alpha;
        out.relay = true;
        out.viewOf = b;
        return out;
    }

    private static long pair(int a, int b) {
        return ((long) Math.min(a, b) << 32) | Math.max(a, b);
    }

    /** La géométrie de la position montrée, rejouée (PathMotion) ; `follow` : la vue la suit sans glisser (lecture). */
    private void place(boolean follow) {
        if (motion == null) return;
        if (whole && seg != null) {
            double local = seg.local(global - seg.start);
            view.setPose(seg.motion.at(local), follow);
            // Le relais n'a pas d'énergie calculée : la vue et le panneau le disent.
            energy = seg.relay() ? Double.NaN : seg.base + seg.motion.energy(local);
            position = seg.relay() ? 0 : local / Math.max(1, path.count() - 1);
            return;
        }
        double s = position * Math.max(0, path.count() - 1);
        view.setPose(motion.at(s), follow);
        energy = motion.energy(s);
    }

    /** Le chemin de l'étape, tel que le moteur l'a calculé. */
    static StageRenderer.Path path(MoleculeSchemeStep st) {
        int n = st.getNumbersCount();
        int[] z = new int[n];
        for (int i = 0; i < n; i++) z[i] = st.getNumbers(i);
        int frames = n == 0 ? 0 : st.getPathCount() / (3 * n);
        double[][][] fr = new double[frames][n][3];
        for (int f = 0; f < frames; f++) {
            for (int i = 0; i < n; i++) for (int c = 0; c < 3; c++) fr[f][i][c] = st.getPath((f * n + i) * 3 + c);
        }
        int qFrames = n == 0 ? 0 : st.getChargesCount() / n;
        double[][] q = new double[qFrames][n];
        for (int f = 0; f < qFrames; f++) for (int i = 0; i < n; i++) q[f][i] = st.getCharges(f * n + i);
        double[] energies = st.getEnergiesList().stream().mapToDouble(Double::doubleValue).toArray();
        int count = st.getChangesCount() / 4;
        int[][] changes = new int[count][4];
        for (int c = 0; c < count; c++) for (int j = 0; j < 4; j++) changes[c][j] = st.getChanges(4 * c + j);
        int oFrames = count == 0 ? energies.length : st.getOrdersCount() / count;
        double[][] orders = new double[Math.max(oFrames, 1)][count];
        for (int f = 0; f < oFrames; f++) for (int c = 0; c < count; c++) orders[f][c] = st.getOrders(f * count + c);
        int arrows = st.getArrowsCount() / 4;
        int[][] curly = new int[arrows][4];
        for (int a = 0; a < arrows; a++) for (int j = 0; j < 4; j++) curly[a][j] = st.getArrows(4 * a + j);
        Set<Integer> jumps = new HashSet<>(st.getJumpsList());
        int pairs = st.getBondPairsCount() / 2;
        int[][] bondPairs = new int[pairs][];
        for (int j = 0; j < pairs; j++) bondPairs[j] = new int[]{st.getBondPairs(2 * j), st.getBondPairs(2 * j + 1)};
        int bondFrames = pairs == 0 ? 0 : st.getBondOrdersCount() / pairs;
        double[][] bondOrders = new double[bondFrames][pairs];
        for (int f = 0; f < bondFrames; f++) for (int j = 0; j < pairs; j++) bondOrders[f][j] = st.getBondOrders(f * pairs + j);
        double[] radii = new double[n];
        for (int i = 0; i < n && i < st.getRadiiCount(); i++) radii[i] = st.getRadii(i);
        StageRenderer.Path p = new StageRenderer.Path(z, fr, q, energies, st.getScanStart(), st.getScanEnd(), changes, orders,
                curly, jumps, bondPairs, bondOrders, radii);
        // Les numéros de tout le schéma (un moteur plus ancien n'en envoie pas : le rang + 1).
        if (st.getIdsCount() == n) p.ids = st.getIdsList().stream().mapToInt(Integer::intValue).toArray();
        return p;
    }

    /**
     * Les hauteurs de chaque étape dans le profil de tout le schéma, en eV depuis les réactifs du départ relaxés chacun
     * seul : chaque molécule y compte une fois, celles qui ne réagissent pas encore (ou plus) loin de celles qui
     * réagissent — les ΔE des étapes s'y ajoutent. reactants[k] : les réactifs de l'étape k, relaxés chacun seul dans son
     * milieu (ce qu'a coûté un changement de milieu compris) ; products[k] = reactants[k] + ΔE ; base[k] : sa première
     * image ; peak[k] : la plus haute image de son chemin imposé.
     */
    static final class Levels {
        final double[] reactants, products, base, peak;
        final boolean[] computed;
        /** L'étape à la plus haute barrière (−1 : aucune calculée). */
        final int slowest;

        Levels(MoleculeScheme s) {
            int n = s.getStepsCount();
            reactants = new double[n];
            products = new double[n];
            base = new double[n];
            peak = new double[n];
            computed = new boolean[n];
            double level = 0;
            int top = -1;
            for (int k = 0; k < n; k++) {
                MoleculeSchemeStep st = s.getSteps(k);
                computed[k] = st.getComputed();
                if (!computed[k]) {
                    reactants[k] = products[k] = base[k] = peak[k] = level;
                    continue;
                }
                level += st.getTransferEv();
                reactants[k] = level;
                base[k] = level + st.getStartEv();
                double high = Double.NEGATIVE_INFINITY;
                int from = st.getScanEnd() > st.getScanStart() ? st.getScanStart() : 0;
                int to = st.getScanEnd() > st.getScanStart() ? st.getScanEnd() : st.getEnergiesCount();
                for (int f = from; f < to && f < st.getEnergiesCount(); f++) high = Math.max(high, st.getEnergies(f));
                peak[k] = base[k] + (Double.isInfinite(high) ? 0 : high);
                products[k] = level + st.getDeltaEv();
                level = products[k];
                if (top < 0 || st.getBarrierEv() > s.getSteps(top).getBarrierEv()) top = k;
            }
            slowest = top;
        }

        /** Les produits de la dernière étape calculée : ΔE de tout le schéma. */
        double end() {
            for (int k = products.length - 1; k >= 0; k--) if (computed[k]) return products[k];
            return 0;
        }
    }

    private final List<ToggleButton> stepTabs = new ArrayList<>();
    private ToggleButton wholeTab;

    /**
     * Les onglets : « Tout le schéma » (ses étapes jouées à la suite, reliées), puis chaque étape. Une seule étape : rien
     * à choisir.
     */
    private void stepButtons() {
        steps.getChildren().clear();
        stepTabs.clear();
        wholeTab = null;
        if (scheme == null || scheme.getStepsCount() < 2) return;
        ToggleGroup group = new ToggleGroup();
        ToggleButton[] buttons = new ToggleButton[scheme.getStepsCount() + 1];
        wholeTab = new ToggleButton(I18n.text("Tout le schéma"));
        wholeTab.setToggleGroup(group);
        wholeTab.setTooltip(I18n.tooltip("Les étapes jouées à la suite : chacune commence où la précédente finit (mêmes "
                + "atomes, mêmes numéros), reliées par un relais ; le profil d'énergie de tout le schéma et son bilan"));
        wholeTab.setOnAction(e -> {
            if (anyComputed()) loadWhole(whole ? global : wholeStart(), true);
            selectTabs();
            root.requestFocus();
        });
        buttons[0] = wholeTab;
        for (int k = 0; k < scheme.getStepsCount(); k++) {
            MoleculeSchemeStep s = scheme.getSteps(k);
            ToggleButton b = new ToggleButton(I18n.text("Étape ") + (k + 1));
            b.setToggleGroup(group);
            b.setDisable(!s.getComputed());
            if (!s.getReactants().isEmpty()) {
                b.setTooltip(I18n.tooltip(MoleculeFormat.names(s.getReactants()) + "  →  " + MoleculeFormat.names(s.getProducts())));
            }
            final int index = k;
            b.setOnAction(e -> {
                if (computed(index)) load(index, 0, true);
                selectTabs();
                root.requestFocus();
            });
            stepTabs.add(b);
            buttons[k + 1] = b;
        }
        steps.getChildren().setAll(Plan.segmented(false, buttons).getChildren());
        selectTabs();
    }

    /** Passer d'une étape à tout le schéma : la lecture reprend à la même place de la frise. */
    private double wholeStart() {
        if (step < 0) return 0;
        levels = new Levels(scheme);
        buildPlaylist();
        return wholePosition(step, position);
    }

    private void selectTabs() {
        if (wholeTab != null) wholeTab.setSelected(whole);
        for (int k = 0; k < stepTabs.size(); k++) stepTabs.get(k).setSelected(!whole && k == step);
    }

    /** Ce qui ne change pas pendant la lecture d'une étape : son titre, ses énergies, son verdict. */
    private void fillStatic() {
        title.setText(I18n.text("Étape ") + (step + 1) + (scheme.getStepsCount() > 1 ? " / " + scheme.getStepsCount() : ""));
        sub.getChildren().setAll(MoleculeFormat.formula(MoleculeFormat.names(st.getReactants()) + "  →  "
                + MoleculeFormat.names(st.getProducts()) + (st.getByproducts().isEmpty() ? ""
                : "  (+ " + MoleculeFormat.names(st.getByproducts()) + ")"), Plan.sans(12.5), Plan.INK2).getChildren());
        selectTabs();
        previous.setDisable(step <= 0);
        next.setDisable(step + 1 >= scheme.getStepsCount());
        barrier.setText(MoleculeFormat.decimal(st.getBarrierEv(), 2) + " eV   " + MoleculeFormat.decimal(st.getBarrierEv() * 96.485, 0)
                + " kJ/mol");
        entropyLine.setText(entropyText(st));
        Plan.show(entropyLine, !entropyLine.getText().isEmpty());
        // Ce que la barrière veut dire en temps (Eyring), à la température écrite sur la flèche ; trop lent, c'est dit.
        double kelvin = kelvin(st);
        double half = halfLife(st.getBarrierEv(), kelvin);
        rate.setText(rateText(half, kelvin));
        boolean tooSlow = half > YEAR;
        slow.setText(tooSlow ? I18n.text(slowText(path)) : st.getBarrierEv() < 1 ? I18n.text(UNDERESTIMATED) : "");
        if (!tooSlow) slow.setStyle("-fx-text-fill:" + Plan.INK3_HEX + ";");
        else slow.setStyle("-fx-text-fill:" + Plan.hex(Plan.MARK) + ";");
        Plan.show(slow, !slow.getText().isEmpty());
        delta.setText(MoleculeFormat.signed(st.getDeltaEv(), 2) + " eV   " + MoleculeFormat.signed(st.getDeltaEv() * 96.485, 0)
                + " kJ/mol");
        verdict.getChildren().clear();
        Circle dot = new Circle(4, st.getConfirmed() ? Plan.GOOD : Plan.BAD);
        Label v = new Label(st.getVerdict());
        v.setWrapText(true);
        v.setStyle("-fx-text-fill:" + Plan.INK_HEX + ";-fx-font-size:12px;");
        HBox.setHgrow(v, Priority.ALWAYS);
        HBox line = new HBox(10, dot, v);
        line.setAlignment(Pos.TOP_LEFT);
        HBox.setMargin(dot, new Insets(5, 0, 0, 0));
        verdict.getChildren().add(line);
        // Le milieu et ce qui est écrit sur la flèche, noms traduits (« H₂O · eau » → « H₂O · water »).
        verdict.getChildren().add(Plan.row("Milieu", Plan.value(st.getSolvent().isEmpty() ? I18n.text("le vide")
                : I18n.text(st.getSolvent()))));
        // Au-dessus de la flèche, les réactifs écrits ; au-dessous, les conditions (le solvant y devient le milieu).
        if (!st.getReagents().isEmpty()) verdict.getChildren().add(Plan.row("Réactifs écrits", Plan.value(MoleculeFormat.names(st.getReagents()))));
        if (!st.getConditions().isEmpty()) verdict.getChildren().add(Plan.row("Conditions", Plan.value(MoleculeFormat.names(st.getConditions()))));
        if (!st.getByproducts().isEmpty()) {
            Label by = Plan.note(I18n.text("Sous-produits déduits : ") + MoleculeFormat.names(st.getByproducts()));
            by.setWrapText(true);
            verdict.getChildren().add(by);
        }
        if (st.getNotesCount() > 0) {
            VBox notes = new VBox(5);
            for (String n : st.getNotesList()) {
                Label l = Plan.note(n);
                l.setWrapText(true);
                notes.getChildren().add(l);
            }
            verdict.getChildren().add(Plan.foldable("Limites et remarques", false, notes));
        }
        curlyToggle.setDisable(path.curly.length == 0);
        table.setHeight(30 + Math.min(10, path.changes.length) * 26 + (path.changes.length > 10 ? 20 : 0));
        readingsDirty = true;
    }

    // ================================================================== l'horloge
    /** L'horloge de l'écran : la lecture avance de `speed` images par seconde (sauf pendant un glissé de la frise). */
    void tick(double dt) {
        clock += dt;
        if (!isOpen()) return;
        if (whole && running && !seeking && !playlist.isEmpty()) {
            // Tout le schéma : la lecture passe d'une étape au relais, du relais à l'étape suivante, sans s'arrêter.
            global = Math.min(total, global + speed * Math.min(dt, .1));
            if (global >= total) {
                running = false;
                play.setGraphic(Icons.path("play", 16));
            }
            Segment now = segmentAt(global);
            if (now != seg) enter(now, true);
            place(true);
            readingsDirty = true;
        } else if (!whole && running && hold > 0) {
            hold -= dt;
        } else if (!whole && running && !seeking && motion != null && path.count() > 1) {
            double last = path.count() - 1, s = position * last + speed * Math.min(dt, .1);
            if (s >= last) {
                s = last;
                running = false;
                play.setGraphic(Icons.path("play", 16));
            }
            position = s / last;
            place(true);
            readingsDirty = true;
        }
        if (view.tick(dt)) drawStage();
        if (readingsDirty && clock - readingsAt > (running ? .05 : 0)) refreshReadings();
    }

    private void drawStage() {
        if (!isOpen()) return;
        view.render();
    }

    private void refreshReadings() {
        readingsAt = clock;
        readingsDirty = false;
        paintScrubber();
        if (path == null) {
            readout.setText(I18n.text(wanted >= 0 || wanted == WHOLE ? "Cette étape se calcule encore…" : "Aucun chemin joué"));
            paintEnergy();
            paintSeries();
            paintTable();
            return;
        }
        if (whole && seg != null) {
            double local = seg.local(global - seg.start);
            readout.setText(seg.relay()
                    ? I18n.text("relais") + " · " + I18n.text("étape") + " " + (seg.from + 1) + " → " + (seg.to + 1)
                    : I18n.text("étape") + " " + (seg.step + 1) + " · " + I18n.text("image") + " " + MoleculeFormat.decimal(local + 1, 1)
                    + " / " + path.count());
            wholeNow.setText(Double.isNaN(energy) ? "—" : MoleculeFormat.signed(energy, 2));
            wholeKj.setText(Double.isNaN(energy) ? I18n.text("relais : rien n'y est calculé")
                    : MoleculeFormat.signed(energy * 96.485, 1) + " kJ/mol");
            paintWhole();
            return;
        }
        int n = path.count();
        double s = position * Math.max(0, n - 1);
        String zone = s < path.scanStart ? "approche" : s <= path.scanEnd - 1 + 1e-9 ? "chemin imposé" : "départ";
        readout.setText(I18n.text("image") + " " + MoleculeFormat.decimal(s + 1, 1) + " / " + n + "   " + I18n.text(zone));
        energyNow.setText(MoleculeFormat.signed(energy, 2));
        energyKj.setText(MoleculeFormat.signed(energy * 96.485, 1) + " kJ/mol");
        paintEnergy();
        paintSeries();
        paintTable();
    }

    // ================================================================== le transport
    /** Lecture ou pause ; arrivé au bout, la lecture repart du début. */
    private void toggle() {
        if (whole) {
            if (playlist.isEmpty() || total <= 0) return;
            if (!running && global >= total - 1e-9) {
                global = 0;
                Segment first = segmentAt(0);
                if (first != seg) enter(first, true);
            }
            running = !running;
            play.setGraphic(Icons.path(running ? "pause" : "play", 16));
            place(false);
            readingsDirty = true;
            return;
        }
        if (motion == null || path.count() < 2) return;
        if (!running && position >= 1 - 1e-9) position = 0;
        running = !running;
        play.setGraphic(Icons.path(running ? "pause" : "play", 16));
        place(false);
        readingsDirty = true;
    }

    /** Une image avant ou après (en pause). */
    private void nudge(int images) {
        if (whole) {
            running = false;
            play.setGraphic(Icons.path("play", 16));
            seekWhole(Math.round(global) + images);
            return;
        }
        if (path == null || path.count() < 2) return;
        double s = Math.round(position * (path.count() - 1)) + images;
        running = false;
        play.setGraphic(Icons.path("play", 16));
        seek(Math.max(0, Math.min(1, s / (path.count() - 1))));
    }

    /** Place le chemin à la position `to` (0 → 1) : la lecture continue de là si elle tournait. */
    private void seek(double to) {
        if (motion == null) return;
        position = Math.max(0, Math.min(1, to));
        place(false);
        readingsDirty = true;
        refreshReadings();
    }

    /** La frise : un clic ou un glissé place le chemin là, tout de suite. */
    private void scrub(double x) {
        if (path == null) return;
        double w = scrubber.getWidth(), left = 10, right = w - 10;
        double along = (x - left) / Math.max(1, right - left);
        if (whole) seekWhole(Math.max(0, Math.min(1, along)) * total);
        else seek(along);
    }

    /**
     * La frise du chemin : son profil d'énergie en silhouette (déjà lu, en couleur), ses zones (approche, chemin imposé,
     * départ), une marque par image calculée, les sauts de branche, la position.
     */
    private void paintScrubber() {
        GraphicsContext g = scrubber.getGraphicsContext2D();
        double w = scrubber.getWidth(), h = scrubber.getHeight();
        g.clearRect(0, 0, w, h);
        double left = 10, right = w - 10, base = h - 9, top = 6;
        g.setLineCap(StrokeLineCap.ROUND);
        if (whole && !playlist.isEmpty() && total > 0) {
            paintWholeScrubber(g, w, h, left, right, base, top);
            return;
        }
        if (path == null || path.count() < 2 || path.energies.length != path.count()) {
            g.setStroke(Color.web("#FFFFFF", .10));
            g.setLineWidth(3);
            g.strokeLine(left, base, right, base);
            return;
        }
        int n = path.count();
        double[] e = path.energies;
        double lo = 0, hi = 0;
        for (double v : e) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        final double low = lo, span = Math.max(.05, hi - lo);
        java.util.function.DoubleUnaryOperator X = f -> left + f / (n - 1) * (right - left);
        java.util.function.DoubleUnaryOperator Y = v -> base - 3 - (v - low) / span * (base - 3 - top);
        double at = X.applyAsDouble(position * (n - 1));
        // La silhouette : en gris ce qui reste à lire, en couleur ce qui est lu.
        double[] xs = new double[n + 2], ys = new double[n + 2];
        for (int f = 0; f < n; f++) {
            xs[f] = X.applyAsDouble(f);
            ys[f] = Y.applyAsDouble(e[f]);
        }
        xs[n] = right;
        ys[n] = base;
        xs[n + 1] = left;
        ys[n + 1] = base;
        g.setFill(Color.web("#FFFFFF", .035));
        g.fillPolygon(xs, ys, n + 2);
        g.setStroke(Color.web("#FFFFFF", .16));
        g.setLineWidth(1);
        g.strokePolyline(xs, ys, n);
        g.save();
        g.beginPath();
        g.rect(left, 0, Math.max(0, at - left), h);
        g.clip();
        g.setFill(new LinearGradient(0, top, 0, base, false, CycleMethod.NO_CYCLE,
                new Stop(0, ACCENT.deriveColor(0, 1, 1, .22)), new Stop(1, ACCENT.deriveColor(0, 1, 1, .02))));
        g.fillPolygon(xs, ys, n + 2);
        g.setStroke(ACCENT.deriveColor(0, 1, 1, .85));
        g.setLineWidth(1.2);
        g.strokePolyline(xs, ys, n);
        g.restore();
        // La ligne de base : les zones.
        double a = X.applyAsDouble(path.scanStart), b = X.applyAsDouble(Math.max(path.scanStart, path.scanEnd - 1));
        g.setLineWidth(3);
        g.setStroke(Color.web("#FFFFFF", .10));
        g.strokeLine(left, base, right, base);
        g.setStroke(Color.web("#FFFFFF", .22));
        g.strokeLine(a, base, b, base);
        g.setStroke(ACCENT);
        g.strokeLine(left, base, at, base);
        for (int f = 0; f < n; f++) {
            double x = X.applyAsDouble(f);
            g.setFill(f == scrubHover ? Plan.INK : Color.web("#FFFFFF", .35));
            g.fillOval(x - 1.3, base + 4, 2.6, 2.6);
            if (path.jumps.contains(f) && f + 1 < n) {
                g.setStroke(Plan.MARK);
                g.setLineWidth(2);
                g.strokeLine(x + 3, base + 5.3, X.applyAsDouble(f + 1) - 3, base + 5.3);
            }
        }
        // La position.
        g.setStroke(Color.web("#FFFFFF", .55));
        g.setLineWidth(1);
        g.strokeLine(at, top - 2, at, base);
        g.setFill(ACCENT.deriveColor(0, 1, 1, .25));
        g.fillOval(at - 9, base - 9, 18, 18);
        g.setFill(Plan.INK);
        g.fillOval(at - 5, base - 5, 10, 10);
    }

    // ================================================================== tout le schéma : le profil, la frise, le bilan
    /** Les marges du profil de tout le schéma (px). */
    private static final double WL = 34, WR = 8;

    /**
     * Le pointillé d'un relais dans le profil de tout le schéma, en {x (images de la frise), eV} : la dernière image de
     * l'étape d'avant, le palier de ses produits relaxés chacun seul (l'intermédiaire), ce qu'a coûté un changement de
     * milieu, la première image de l'étape suivante.
     */
    private double[][] relayLine(Segment r) {
        Segment before = null, after = null;
        for (Segment s : playlist) {
            if (s.step == r.from) before = s;
            if (s.step == r.to) after = s;
        }
        List<double[]> out = new ArrayList<>();
        if (before != null) out.add(new double[]{r.start, before.base + before.path.energies[before.path.energies.length - 1]});
        double mid = r.start + r.length / 2;
        out.add(new double[]{mid - .25, levels.products[r.from]});
        out.add(new double[]{mid + .25, levels.products[r.from]});
        if (Math.abs(levels.reactants[r.to] - levels.products[r.from]) > 1e-4) {
            out.add(new double[]{mid + .25, levels.reactants[r.to]});
        }
        if (after != null) out.add(new double[]{r.start + r.length, after.base + after.path.energies[0]});
        return out.toArray(new double[0][]);
    }

    private double wholeLeft() {
        return WL;
    }

    /** La position de la frise sous le curseur dans le profil de tout le schéma (−1 : hors du profil). */
    private double wholeX(double x) {
        double w = wholeChart.getWidth(), left = WL, right = w - WR;
        if (playlist.isEmpty() || x < left - 6 || x > right + 6) return -1;
        return Math.max(0, Math.min(total, (x - left) / Math.max(1, right - left) * total));
    }

    /** L'étape sous le curseur dans le profil de tout le schéma (−1 : aucune, ou un relais). */
    private int wholeStepAt(double x) {
        double s = wholeX(x);
        if (s < 0) return -1;
        Segment at = segmentAt(s);
        return at == null || at.relay() ? -1 : at.step;
    }

    /**
     * Le profil de tout le schéma : chaque étape à sa hauteur (son chemin, image par image, calculé ; en tirets à
     * travers un saut), les relais en pointillés par le palier de l'intermédiaire, ΔE de tout le schéma, la plus haute
     * barrière, et la position montrée. L'étape survolée en clair, avec ses valeurs.
     */
    private void paintWhole() {
        GraphicsContext g = wholeChart.getGraphicsContext2D();
        double w = wholeChart.getWidth(), h = wholeChart.getHeight();
        g.clearRect(0, 0, w, h);
        if (playlist.isEmpty() || total <= 0 || levels == null) return;
        double left = WL, right = w - WR, top = 16, bottom = h - 22;
        double lo = Math.min(0, levels.end()), hi = Math.max(0, levels.end());
        for (Segment s : playlist) {
            if (s.relay()) {
                for (double[] p : relayLine(s)) {
                    lo = Math.min(lo, p[1]);
                    hi = Math.max(hi, p[1]);
                }
            } else {
                for (double e : s.path.energies) {
                    lo = Math.min(lo, s.base + e);
                    hi = Math.max(hi, s.base + e);
                }
            }
        }
        double pad = Math.max(.08, (hi - lo) * .1);
        final double low = lo - pad, high = hi + pad, span = total;
        java.util.function.DoubleUnaryOperator X = s -> left + s / span * (right - left);
        java.util.function.DoubleUnaryOperator Y = v -> bottom - (v - low) / (high - low) * (bottom - top);
        // Les bandes des étapes, numérotées dessous ; l'étape survolée plus claire.
        g.setFont(Plan.sans(9.5));
        g.setTextBaseline(VPos.BASELINE);
        g.setTextAlign(TextAlignment.CENTER);
        for (Segment s : playlist) {
            if (s.relay()) continue;
            double a = X.applyAsDouble(s.start), b = X.applyAsDouble(s.start + s.length);
            g.setFill(Color.web("#FFFFFF", s.step == wholeHover ? .07 : .025));
            g.fillRect(a, top - 6, Math.max(1, b - a), bottom - top + 10);
            String name = I18n.text("étape") + " " + (s.step + 1);
            g.setFill(s.step == wholeHover ? Plan.INK : Plan.INK3);
            g.fillText(b - a > width(name, Plan.sans(9.5)) + 6 ? name : String.valueOf(s.step + 1), (a + b) / 2, h - 6);
        }
        g.setStroke(Color.web("#FFFFFF", .08));
        g.setLineWidth(1);
        g.strokeLine(left, bottom + .5, right, bottom + .5);
        // L'échelle : 0 (les réactifs du départ), le plus haut, le plus bas.
        g.setFont(Plan.sans(10));
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(Plan.INK3);
        double y0 = Y.applyAsDouble(0);
        g.fillText("0", left - 8, y0 + 3.5);
        if (Math.abs(Y.applyAsDouble(hi) - y0) > 12) g.fillText(MoleculeFormat.signed(hi, 1), left - 8, Y.applyAsDouble(hi) + 3.5);
        if (Math.abs(Y.applyAsDouble(lo) - y0) > 12) g.fillText(MoleculeFormat.signed(lo, 1), left - 8, Y.applyAsDouble(lo) + 3.5);
        g.setStroke(Color.web("#FFFFFF", .10));
        g.setLineDashes(2, 4);
        g.strokeLine(left, y0, right, y0);
        // ΔE de tout le schéma : les produits de la dernière étape relaxés chacun seul.
        double end = levels.end();
        if (Math.abs(end) > 1e-6) {
            double yd = Y.applyAsDouble(end);
            g.setStroke(Plan.INK3);
            g.setLineDashes(1.5, 3);
            g.strokeLine(left, yd, right, yd);
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFill(Plan.INK3);
            g.fillText("ΔE", right - 2, yd + (end < (low + high) / 2 ? -4 : 11));
        }
        g.setLineDashes(null);
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        for (Segment s : playlist) {
            if (s.relay()) {
                // Le relais : rien n'y est calculé ; son palier, l'intermédiaire relaxé seul, l'est.
                double[][] line = relayLine(s);
                g.setStroke(Plan.INK3);
                g.setLineWidth(1.2);
                g.setLineDashes(1.5, 3.5);
                for (int k = 0; k + 1 < line.length; k++) {
                    g.strokeLine(X.applyAsDouble(line[k][0]), Y.applyAsDouble(line[k][1]), X.applyAsDouble(line[k + 1][0]),
                            Y.applyAsDouble(line[k + 1][1]));
                }
                g.setLineDashes(null);
                double mid = s.start + s.length / 2, yp = Y.applyAsDouble(levels.products[s.from]);
                g.setStroke(Plan.INK2);
                g.setLineWidth(2.4);
                g.strokeLine(X.applyAsDouble(mid) - 5, yp, X.applyAsDouble(mid) + 5, yp);
                continue;
            }
            double[] e = s.path.energies;
            for (int f = 0; f + 1 < e.length; f++) {
                boolean jump = s.path.jumps.contains(f);
                g.setStroke(jump ? Plan.MARK.deriveColor(0, 1, 1, .8) : ACCENT);
                g.setLineWidth(s.step == wholeHover ? 2.2 : 1.7);
                g.setLineDashes(jump ? new double[]{3, 4} : null);
                g.strokeLine(X.applyAsDouble(s.start + f), Y.applyAsDouble(s.base + e[f]), X.applyAsDouble(s.start + f + 1),
                        Y.applyAsDouble(s.base + e[f + 1]));
            }
            g.setLineDashes(null);
        }
        // La plus haute barrière : de la première image du chemin imposé de son étape à sa plus haute.
        if (levels.slowest >= 0) {
            for (Segment s : playlist) {
                if (s.step != levels.slowest || s.path.scanEnd <= s.path.scanStart + 1) continue;
                double[] e = s.path.energies;
                int peak = s.path.scanStart;
                for (int f = s.path.scanStart; f < s.path.scanEnd && f < e.length; f++) if (e[f] > e[peak]) peak = f;
                double rise = e[peak] - e[s.path.scanStart];
                if (rise < .02) break;
                double x = X.applyAsDouble(s.start + peak), ya = Y.applyAsDouble(s.base + e[s.path.scanStart]);
                double yb = Y.applyAsDouble(s.base + e[peak]);
                g.setStroke(Color.web("#FFFFFF", .30));
                g.setLineWidth(1);
                g.strokeLine(x, ya, x, yb + 4);
                g.strokeLine(x - 3, ya, x + 3, ya);
                Font font = Font.font(Plan.MEDIUM, 10.5);
                String text = "↑ " + MoleculeFormat.decimal(rise, 2) + " eV";
                double tw = width(text, font), tx = Math.max(left, Math.min(right - tw, x + 6));
                g.setFont(font);
                g.setTextAlign(TextAlignment.LEFT);
                g.setFill(Plan.INK);
                g.fillText(text, tx, Math.max(top + 8, yb - 6));
            }
        }
        // La position montrée : sur la courbe de son étape, ou sur le pointillé du relais.
        double xs = X.applyAsDouble(global), ys;
        if (!Double.isNaN(energy)) {
            ys = Y.applyAsDouble(energy);
        } else {
            double[][] line = seg != null && seg.relay() ? relayLine(seg) : new double[0][];
            double v = line.length > 0 ? line[0][1] : 0;
            for (int k = 0; k + 1 < line.length; k++) {
                if (global >= line[k][0] && global <= line[k + 1][0]) {
                    double t = line[k + 1][0] > line[k][0] ? (global - line[k][0]) / (line[k + 1][0] - line[k][0]) : 1;
                    v = line[k][1] + (line[k + 1][1] - line[k][1]) * t;
                }
            }
            if (line.length > 0 && global > line[line.length - 1][0]) v = line[line.length - 1][1];
            ys = Y.applyAsDouble(v);
        }
        marker(g, xs, ys, top, bottom);
        if (wholeHover >= 0 && wholeHover < scheme.getStepsCount() && computed(wholeHover)) {
            MoleculeSchemeStep s = scheme.getSteps(wholeHover);
            String text = I18n.text("Étape ") + (wholeHover + 1) + "   ΔE " + MoleculeFormat.signed(s.getDeltaEv(), 2) + "   "
                    + I18n.text("barrière") + " " + MoleculeFormat.decimal(s.getBarrierEv(), 2) + " eV";
            Font font = Plan.sans(10.5);
            double tw = width(text, font) + 14;
            double bx = Math.max(0, Math.min(w - tw, xs - tw / 2));
            for (Segment p : playlist) {
                if (p.step == wholeHover) bx = Math.max(0, Math.min(w - tw, X.applyAsDouble(p.start + p.length / 2) - tw / 2));
            }
            g.setFill(Plan.RAISED.deriveColor(0, 1, 1, .96));
            g.fillRoundRect(bx, 0, tw, 20, 10, 10);
            g.setFill(Plan.INK);
            g.setFont(font);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(text, bx + 7, 14);
        }
    }

    /** La frise de tout le schéma : le profil en silhouette (déjà lu, en couleur), une marque par image, les étapes. */
    private void paintWholeScrubber(GraphicsContext g, double w, double h, double left, double right, double base, double top) {
        List<double[]> points = new ArrayList<>();
        for (Segment s : playlist) {
            if (s.relay()) {
                for (double[] p : relayLine(s)) points.add(p);
            } else {
                for (int f = 0; f < s.path.energies.length; f++) points.add(new double[]{s.start + f, s.base + s.path.energies[f]});
            }
        }
        double lo = 0, hi = 0;
        for (double[] p : points) {
            lo = Math.min(lo, p[1]);
            hi = Math.max(hi, p[1]);
        }
        final double low = lo, span = Math.max(.05, hi - lo), length = total;
        java.util.function.DoubleUnaryOperator X = s -> left + s / length * (right - left);
        java.util.function.DoubleUnaryOperator Y = v -> base - 3 - (v - low) / span * (base - 3 - top);
        int n = points.size();
        double[] xs = new double[n + 2], ys = new double[n + 2];
        for (int k = 0; k < n; k++) {
            xs[k] = X.applyAsDouble(points.get(k)[0]);
            ys[k] = Y.applyAsDouble(points.get(k)[1]);
        }
        xs[n] = right;
        ys[n] = base;
        xs[n + 1] = left;
        ys[n + 1] = base;
        double at = X.applyAsDouble(global);
        g.setFill(Color.web("#FFFFFF", .035));
        g.fillPolygon(xs, ys, n + 2);
        g.setStroke(Color.web("#FFFFFF", .16));
        g.setLineWidth(1);
        g.strokePolyline(xs, ys, n);
        g.save();
        g.beginPath();
        g.rect(left, 0, Math.max(0, at - left), h);
        g.clip();
        g.setFill(new LinearGradient(0, top, 0, base, false, CycleMethod.NO_CYCLE,
                new Stop(0, ACCENT.deriveColor(0, 1, 1, .22)), new Stop(1, ACCENT.deriveColor(0, 1, 1, .02))));
        g.fillPolygon(xs, ys, n + 2);
        g.setStroke(ACCENT.deriveColor(0, 1, 1, .85));
        g.setLineWidth(1.2);
        g.strokePolyline(xs, ys, n);
        g.restore();
        g.setLineWidth(3);
        g.setStroke(Color.web("#FFFFFF", .10));
        g.strokeLine(left, base, right, base);
        g.setFont(Plan.sans(9));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.BASELINE);
        for (Segment s : playlist) {
            double a = X.applyAsDouble(s.start), b = X.applyAsDouble(s.start + s.length);
            if (s.relay()) {
                g.setStroke(Color.web("#FFFFFF", .22));
                g.setLineWidth(1);
                g.setLineDashes(2, 3);
                g.strokeLine((a + b) / 2, top, (a + b) / 2, base);
                g.setLineDashes(null);
                continue;
            }
            g.setStroke(Color.web("#FFFFFF", .22));
            g.setLineWidth(3);
            double sa = X.applyAsDouble(s.start + s.path.scanStart), sb = X.applyAsDouble(s.start + Math.max(s.path.scanStart, s.path.scanEnd - 1));
            g.strokeLine(sa, base, sb, base);
            g.setFill(Plan.INK3);
            g.fillText(String.valueOf(s.step + 1), a + 3, top + 6);
            for (int f = 0; f < s.path.count(); f++) {
                double x = X.applyAsDouble(s.start + f);
                g.setFill(Color.web("#FFFFFF", .3));
                g.fillOval(x - 1.1, base + 4, 2.2, 2.2);
            }
        }
        g.setStroke(ACCENT);
        g.setLineWidth(3);
        g.strokeLine(left, base, at, base);
        g.setStroke(Color.web("#FFFFFF", .55));
        g.setLineWidth(1);
        g.strokeLine(at, top - 2, at, base);
        g.setFill(ACCENT.deriveColor(0, 1, 1, .25));
        g.fillOval(at - 9, base - 9, 18, 18);
        g.setFill(Plan.INK);
        g.fillOval(at - 5, base - 5, 10, 10);
    }

    /**
     * Ce qui ne change pas pendant la lecture de tout le schéma : le titre (des réactifs du départ aux produits de la
     * fin), ΔE de tout le schéma, la plus haute barrière, la liste des étapes, le bilan.
     */
    private void fillWhole() {
        if (scheme == null || levels == null) return;
        int first = -1, last = -1, count = 0, confirmed = 0;
        for (int k = 0; k < scheme.getStepsCount(); k++) {
            if (!computed(k)) continue;
            if (first < 0) first = k;
            last = k;
            count++;
            if (scheme.getSteps(k).getConfirmed()) confirmed++;
        }
        title.setText(I18n.text("Tout le schéma"));
        if (first >= 0) {
            sub.getChildren().setAll(MoleculeFormat.formula(MoleculeFormat.names(scheme.getSteps(first).getReactants()) + "  →  "
                    + MoleculeFormat.names(scheme.getSteps(last).getProducts()), Plan.sans(12.5), Plan.INK2).getChildren());
        }
        selectTabs();
        previous.setDisable(false);
        next.setDisable(false);
        double end = levels.end();
        wholeDelta.setText(MoleculeFormat.signed(end, 2) + " eV   " + MoleculeFormat.signed(end * 96.485, 0) + " kJ/mol");
        if (levels.slowest >= 0) {
            MoleculeSchemeStep s = scheme.getSteps(levels.slowest);
            wholeTop.setText(MoleculeFormat.decimal(s.getBarrierEv(), 2) + " eV   " + I18n.text("étape") + " " + (levels.slowest + 1));
        } else {
            wholeTop.setText("—");
        }
        // Les étapes : un clic en ouvre une.
        stepRows.getChildren().clear();
        for (int k = 0; k < scheme.getStepsCount(); k++) stepRows.getChildren().add(stepRow(k));
        // Le bilan.
        balance.getChildren().clear();
        bondBalance();
        List<String> in = new ArrayList<>(), out = new ArrayList<>();
        for (int k = 0; k < scheme.getStepsCount(); k++) {
            MoleculeSchemeStep s = scheme.getSteps(k);
            if (!s.getReagents().isEmpty()) in.add(I18n.text("étape") + " " + (k + 1) + " · " + MoleculeFormat.names(s.getReagents()));
            if (!s.getByproducts().isEmpty()) out.add(I18n.text("étape") + " " + (k + 1) + " · " + MoleculeFormat.names(s.getByproducts()));
        }
        if (!in.isEmpty()) balance.getChildren().add(listing("Entrent en route", String.join("\n", in)));
        if (!out.isEmpty()) balance.getChildren().add(listing("Sortent", String.join("\n", out)));
        if (levels.slowest >= 0) {
            MoleculeSchemeStep s = scheme.getSteps(levels.slowest);
            double kelvin = kelvin(s), half = halfLife(s.getBarrierEv(), kelvin);
            String text = I18n.text("étape") + " " + (levels.slowest + 1) + " · " + MoleculeFormat.decimal(s.getBarrierEv(), 2) + " eV\n"
                    + rateText(half, kelvin);
            VBox slowest = listing("La plus haute barrière", text);
            if (half > YEAR) {
                Label warn = Plan.note(I18n.text(slowText(aligned(levels.slowest))));
                warn.setStyle("-fx-text-fill:" + Plan.hex(Plan.MARK) + ";");
                slowest.getChildren().add(warn);
            }
            balance.getChildren().add(slowest);
        }
        Circle dot = new Circle(4, confirmed == count ? Plan.GOOD : Plan.BAD);
        Label told = Plan.value(confirmed + " / " + count + " " + I18n.text(count > 1 ? "étapes" : "étape"));
        HBox line = new HBox(8, dot, told);
        line.setAlignment(Pos.CENTER_LEFT);
        balance.getChildren().add(Plan.row("Confirmées par le calcul", line));
        curlyToggle.setDisable(path == null || path.curly.length == 0);
    }

    /** Une rangée de la liste des étapes : son verdict, son nom, ΔE et sa barrière, ses formules ; un clic l'ouvre. */
    private VBox stepRow(int k) {
        MoleculeSchemeStep s = scheme.getSteps(k);
        Circle dot = new Circle(3.5, !s.getComputed() ? Plan.INK3 : s.getConfirmed() ? Plan.GOOD : Plan.BAD);
        Label name = new Label(I18n.text("Étape ") + (k + 1));
        name.setStyle("-fx-text-fill:" + Plan.INK_HEX + ";-fx-font-size:12px;-fx-font-family:'" + Plan.SANS_SEMIBOLD + "';");
        Label figures = Plan.note(s.getComputed() ? "ΔE " + MoleculeFormat.signed(s.getDeltaEv(), 2) + "   " + I18n.text("barrière")
                + " " + MoleculeFormat.decimal(s.getBarrierEv(), 2) + " eV" : I18n.text("non calculée"));
        figures.setWrapText(false);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox head = new HBox(8, dot, name, grow, figures);
        head.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.text.TextFlow what = MoleculeFormat.formula(MoleculeFormat.names(s.getReactants()) + "  →  "
                + MoleculeFormat.names(s.getProducts()), Plan.sans(11.5), Plan.INK2);
        what.setMaxWidth(CHART_W - 16);
        VBox row = new VBox(4, head, what);
        row.setPadding(new Insets(7, 8, 8, 8));
        String rest = "-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 8;";
        String lit = "-fx-background-color: rgba(255,255,255,0.08); -fx-background-radius: 8;";
        row.setStyle(rest);
        if (s.getComputed()) {
            row.setCursor(Cursor.HAND);
            Tooltip.install(row, I18n.tooltip("Ouvrir cette étape : son chemin, image par image"));
            row.setOnMouseEntered(e -> {
                row.setStyle(lit);
                wholeHover = k;
                paintWhole();
            });
            row.setOnMouseExited(e -> {
                row.setStyle(rest);
                wholeHover = -1;
                paintWhole();
            });
            row.setOnMouseClicked(e -> {
                if (computed(k)) load(k, 0, true);
                root.requestFocus();
            });
        }
        return row;
    }

    /** Un intitulé et, dessous, son texte (plusieurs lignes). */
    private static VBox listing(String caption, String text) {
        Label body = Plan.note(text);
        body.setStyle("-fx-text-fill:" + Plan.INK_HEX + ";");
        return new VBox(2, Plan.label(caption), body);
    }

    /**
     * Ce qui se forme et se rompt du début à la fin du schéma : les atomes gardent leur numéro d'une étape à l'autre, si
     * bien qu'une liaison formée à l'étape 1 et rompue à l'étape 3 ne compte pas. L'ordre d'avant de chaque paire, celui
     * de la première étape qui la change ; celui d'après, de la dernière.
     */
    private void bondBalance() {
        java.util.Map<Long, int[]> net = new java.util.LinkedHashMap<>();      // {avant, après, Z a, Z b, numéro a, numéro b}
        for (int k = 0; k < scheme.getStepsCount(); k++) {
            if (!computed(k)) continue;
            StageRenderer.Path p = aligned(k);
            for (int[] c : p.changes) {
                int ia = p.number(c[0]), ib = p.number(c[1]);
                long key = pair(ia, ib);
                int[] v = net.get(key);
                if (v == null) net.put(key, new int[]{c[2], c[3], p.z[c[0]], p.z[c[1]], ia, ib});
                else v[1] = c[3];
            }
        }
        List<String> formed = new ArrayList<>(), broken = new ArrayList<>(), changed = new ArrayList<>();
        for (int[] v : net.values()) {
            if (v[0] == v[1]) continue;
            String name = MoleculeView.symbolOf(v[2]) + v[4] + "–" + MoleculeView.symbolOf(v[3]) + v[5];
            if (v[0] == 0) formed.add(name);
            else if (v[1] == 0) broken.add(name);
            else changed.add(name + " (" + v[0] + " → " + v[1] + ")");
        }
        if (!formed.isEmpty()) balance.getChildren().add(listing("Se forment", String.join("  ·  ", formed)));
        if (!broken.isEmpty()) balance.getChildren().add(listing("Se rompent", String.join("  ·  ", broken)));
        if (!changed.isEmpty()) balance.getChildren().add(listing("Changent d'ordre", String.join("  ·  ", changed)));
    }

    // ================================================================== les courbes
    /** L'image sous le curseur dans un graphique (marges gauche et droite en px), ou −1. */
    private int frameAt(Canvas c, double x, double left, double right) {
        if (path == null || path.count() < 2) return -1;
        double w = c.getWidth();
        if (x < left - 6 || x > w - right + 6) return -1;
        double f = (x - left) / Math.max(1, w - left - right) * (path.count() - 1);
        return (int) Math.max(0, Math.min(path.count() - 1, Math.round(f)));
    }

    /**
     * L'énergie le long du chemin : la courbe par les images calculées (en tirets à travers un saut), l'aire dessous,
     * une marque par image (pleine sur le chemin imposé), la barrière, et le point calculé à la géométrie montrée.
     */
    private void paintEnergy() {
        GraphicsContext g = energyChart.getGraphicsContext2D();
        double w = energyChart.getWidth(), h = energyChart.getHeight();
        g.clearRect(0, 0, w, h);
        if (path == null || path.energies.length < 2) return;
        double[] e = path.energies;
        int n = e.length;
        double left = 34, right = w - 8, top = 16, bottom = h - 22;
        double delta = st == null ? 0 : st.getDeltaEv();
        double lo = Math.min(Math.min(0, energy), delta), hi = Math.max(Math.max(0, energy), delta);
        for (double v : e) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        double pad = Math.max(.06, (hi - lo) * .1);
        final double low = lo - pad, high = hi + pad;
        java.util.function.DoubleUnaryOperator X = f -> left + f / (n - 1) * (right - left);
        java.util.function.DoubleUnaryOperator Y = v -> bottom - (v - low) / (high - low) * (bottom - top);
        axes(g, w, h, left, right, top, bottom, X);
        g.setFont(Plan.sans(10));
        g.setTextBaseline(VPos.BASELINE);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(Plan.INK3);
        double y0 = Y.applyAsDouble(0);
        g.fillText("0", left - 8, y0 + 3.5);
        if (Math.abs(Y.applyAsDouble(hi) - y0) > 12) g.fillText(MoleculeFormat.signed(hi, 1), left - 8, Y.applyAsDouble(hi) + 3.5);
        if (Math.abs(Y.applyAsDouble(lo) - y0) > 12) g.fillText(MoleculeFormat.signed(lo, 1), left - 8, Y.applyAsDouble(lo) + 3.5);
        g.setStroke(Color.web("#FFFFFF", .10));
        g.setLineWidth(1);
        g.setLineDashes(2, 4);
        g.strokeLine(left, y0, right, y0);
        // ΔE : les produits et les réactifs relaxés chacun seul (la dernière image garde ses molécules côte à côte).
        if (st != null && Math.abs(delta) > 1e-6) {
            double yd = Y.applyAsDouble(delta);
            g.setStroke(Plan.INK3);
            g.setLineDashes(1.5, 3);
            g.strokeLine(left, yd, right, yd);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFill(Plan.INK3);
            g.fillText("ΔE", left + 3, yd + (delta < (low + high) / 2 ? -4 : 11));
        }
        g.setLineDashes(null);
        // L'aire sous la courbe, puis la courbe.
        double[] xs = new double[n + 2], ys = new double[n + 2];
        for (int f = 0; f < n; f++) {
            xs[f] = X.applyAsDouble(f);
            ys[f] = Y.applyAsDouble(e[f]);
        }
        xs[n] = right;
        ys[n] = bottom;
        xs[n + 1] = left;
        ys[n + 1] = bottom;
        g.setFill(new LinearGradient(0, top, 0, bottom, false, CycleMethod.NO_CYCLE,
                new Stop(0, ACCENT.deriveColor(0, 1, 1, .20)), new Stop(1, ACCENT.deriveColor(0, 1, 1, 0))));
        g.fillPolygon(xs, ys, n + 2);
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        for (int f = 0; f + 1 < n; f++) {
            boolean jump = path.jumps.contains(f);
            g.setStroke(jump ? Plan.MARK.deriveColor(0, 1, 1, .8) : ACCENT);
            g.setLineWidth(1.8);
            g.setLineDashes(jump ? new double[]{3, 4} : null);
            g.strokeLine(xs[f], ys[f], xs[f + 1], ys[f + 1]);
        }
        g.setLineDashes(null);
        for (int f = 0; f < n; f++) {
            boolean scan = f >= path.scanStart && f < path.scanEnd;
            g.setFill(scan ? ACCENT : PANEL);
            g.fillOval(xs[f] - 2.4, ys[f] - 2.4, 4.8, 4.8);
            if (!scan) {
                g.setStroke(ACCENT.deriveColor(0, 1, 1, .7));
                g.setLineWidth(1);
                g.strokeOval(xs[f] - 2.4, ys[f] - 2.4, 4.8, 4.8);
            }
        }
        // La barrière de ce chemin : du début du chemin imposé à sa plus haute image.
        if (path.scanEnd > path.scanStart + 1) {
            int peak = path.scanStart;
            for (int f = path.scanStart; f < path.scanEnd; f++) if (e[f] > e[peak]) peak = f;
            double rise = e[peak] - e[path.scanStart];
            if (rise > .02 && peak != path.scanStart) {
                double x = xs[peak], ya = Y.applyAsDouble(e[path.scanStart]), yb = ys[peak];
                g.setStroke(Color.web("#FFFFFF", .30));
                g.setLineWidth(1);
                g.strokeLine(x, ya, x, yb + 4);
                g.strokeLine(x - 3, ya, x + 3, ya);
                Font font = Font.font(Plan.MEDIUM, 10.5);
                String text = "↑ " + MoleculeFormat.decimal(rise, 2) + " eV";
                double tw = width(text, font), tx = Math.max(left, Math.min(right - tw, x + 6));
                g.setFont(font);
                g.setTextAlign(TextAlignment.LEFT);
                g.setFill(Plan.INK);
                g.fillText(text, tx, Math.max(top + 8, yb - 6));
            }
        }
        // La position montrée et son énergie, calculée à cette géométrie.
        double xsNow = X.applyAsDouble(position * (n - 1)), ysNow = Y.applyAsDouble(energy);
        marker(g, xsNow, ysNow, top, bottom);
        if (chartHover >= 0 && chartHover < n) {
            tip(g, xs[chartHover], ys[chartHover], I18n.text("image") + " " + (chartHover + 1) + "   "
                    + MoleculeFormat.signed(e[chartHover], 3) + " eV", left, right);
        }
    }

    /** Le fond d'un graphique du chemin : les zones (approche, chemin imposé, départ) en bandes, nommées dessous. */
    private void axes(GraphicsContext g, double w, double h, double left, double right, double top, double bottom,
                      java.util.function.DoubleUnaryOperator X) {
        double a = X.applyAsDouble(path.scanStart), b = X.applyAsDouble(Math.max(path.scanStart, path.scanEnd - 1));
        g.setFill(Color.web("#FFFFFF", .025));
        g.fillRect(a, top - 6, Math.max(1, b - a), bottom - top + 10);
        g.setStroke(Color.web("#FFFFFF", .08));
        g.setLineWidth(1);
        g.strokeLine(left, bottom + .5, right, bottom + .5);
        g.setFont(Plan.sans(9.5));
        g.setFill(Plan.INK3);
        g.setTextBaseline(VPos.BASELINE);
        g.setTextAlign(TextAlignment.CENTER);
        String approach = I18n.text("approche");
        if (a - left > width(approach, Plan.sans(9.5)) + 6) g.fillText(approach, (left + a) / 2, h - 6);
        else if (a - left > 26) g.fillText(I18n.text("appr."), (left + a) / 2, h - 6);
        g.fillText(I18n.text("chemin imposé"), (a + b) / 2, h - 6);
        if (right - b > 40) g.fillText(I18n.text("départ"), (b + right) / 2, h - 6);
    }

    /** La position montrée : un trait, et un point cerclé de lumière. */
    private static void marker(GraphicsContext g, double x, double y, double top, double bottom) {
        g.setStroke(Color.web("#FFFFFF", .30));
        g.setLineWidth(1);
        g.strokeLine(x, top - 6, x, bottom);
        g.setFill(ACCENT.deriveColor(0, 1, 1, .22));
        g.fillOval(x - 8, y - 8, 16, 16);
        g.setFill(Plan.INK);
        g.fillOval(x - 4, y - 4, 8, 8);
    }

    /**
     * Image par image, pour ce qui réagit : l'indice de Mayer de chaque liaison qui change, sa longueur, ou la charge de
     * chaque atome qui réagit ; à droite, son nom ; le point, sa valeur à la géométrie montrée (calculée là). Dessous,
     * ce qui a le plus changé du début à la fin du chemin.
     */
    private void paintSeries() {
        GraphicsContext g = seriesChart.getGraphicsContext2D();
        double w = seriesChart.getWidth(), h = seriesChart.getHeight();
        g.clearRect(0, 0, w, h);
        if (path == null || path.count() < 2) return;
        int n = path.count();
        List<double[]> values = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Color> colours = new ArrayList<>();
        List<Double> now = new ArrayList<>();
        StageRenderer.Pose pose = view.pose();
        String unit;
        if (series == 0 || series == 1) {
            unit = series == 0 ? I18n.text("Mayer") : "Å";
            for (int k = 0; k < path.changes.length; k++) {
                int[] c = path.changes[k];
                double[] v = new double[n];
                for (int f = 0; f < n; f++) {
                    if (series == 0) v[f] = f < path.orders.length && k < path.orders[f].length ? path.orders[f][k] : 0;
                    else v[f] = StageRenderer.dist(path.frames[f][c[0]], path.frames[f][c[1]]);
                }
                values.add(v);
                names.add(bondName(c));
                int kind = StageRenderer.Path.kind(c);
                colours.add(kind > 0 ? StageRenderer.FORMING : kind < 0 ? StageRenderer.BREAKING : StageRenderer.CHANGED);
                now.add(pose == null ? Double.NaN : series == 0 ? view.mayer(k) : StageRenderer.dist(pose.p[c[0]], pose.p[c[1]]));
            }
        } else {
            unit = "e";
            int colour = 0;
            for (int i = 0; i < path.z.length; i++) {
                if (!path.reacting[i] || path.charges.length < n) continue;
                double[] v = new double[n];
                for (int f = 0; f < n; f++) v[f] = path.charges[f][i];
                values.add(v);
                names.add(path.name(i));
                colours.add(SERIES[colour++ % SERIES.length]);
                now.add(pose == null || pose.q == null ? Double.NaN : pose.q[i]);
            }
        }
        summary.setText(summary(values, names));
        double left = 34, right = w - LABELS, top = 16, bottom = h - 22;
        if (values.isEmpty()) {
            g.setFill(Plan.INK3);
            g.setFont(Plan.sans(11));
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText(I18n.text("Aucune liaison ne change dans cette étape"), w / 2, h / 2);
            return;
        }
        // L'échelle : celle du chemin imposé (l'approche écarte les molécules de plusieurs Å) ; le reste est coupé.
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        boolean core = series == 1 && path.scanEnd > path.scanStart + 1;
        for (double[] v : values) {
            for (int f = core ? path.scanStart : 0; f < (core ? path.scanEnd : n); f++) {
                lo = Math.min(lo, v[f]);
                hi = Math.max(hi, v[f]);
            }
        }
        for (double x : now) if (!Double.isNaN(x)) {
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
        }
        if (series == 0) lo = Math.min(0, lo);
        if (series == 2) {
            double m = Math.max(Math.abs(lo), Math.abs(hi));
            lo = -m;
            hi = m;
        }
        double pad = Math.max(series == 1 ? .05 : .02, (hi - lo) * .1);
        final double low = lo - pad, high = hi + pad;
        java.util.function.DoubleUnaryOperator X = f -> left + f / (n - 1) * (right - left);
        java.util.function.DoubleUnaryOperator Y = v -> bottom - (v - low) / (high - low) * (bottom - top);
        axes(g, w, h, left, right, top, bottom, X);
        g.setFont(Plan.sans(10));
        g.setTextBaseline(VPos.BASELINE);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(Plan.INK3);
        g.fillText(MoleculeFormat.decimal(hi, series == 2 ? 2 : 1), left - 8, Y.applyAsDouble(hi) + 3.5);
        g.fillText(MoleculeFormat.decimal(lo, series == 2 ? 2 : 1), left - 8, Y.applyAsDouble(lo) + 3.5);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Plan.sans(9.5));
        g.fillText(unit, 2, top - 6);
        if (series != 1) {
            g.setStroke(Color.web("#FFFFFF", .10));
            g.setLineWidth(1);
            g.setLineDashes(2, 4);
            g.strokeLine(left, Y.applyAsDouble(0), right, Y.applyAsDouble(0));
            g.setLineDashes(null);
        }
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        g.save();
        g.beginPath();
        g.rect(left - 2, top - 6, right - left + 4, bottom - top + 10);
        g.clip();
        for (int k = 0; k < values.size(); k++) {
            double[] v = values.get(k);
            Color c = colours.get(k);
            for (int f = 0; f + 1 < n; f++) {
                boolean jump = path.jumps.contains(f);
                g.setStroke(c.deriveColor(0, 1, 1, jump ? .5 : .95));
                g.setLineWidth(1.6);
                g.setLineDashes(jump ? new double[]{3, 4} : null);
                g.strokeLine(X.applyAsDouble(f), Y.applyAsDouble(v[f]), X.applyAsDouble(f + 1), Y.applyAsDouble(v[f + 1]));
            }
            g.setLineDashes(null);
        }
        g.restore();
        // La fin du tracé (les noms des courbes sont à sa droite) : au bout du chemin, le trait de position s'y arrête —
        // il semblait bloqué aux quatre cinquièmes de la largeur.
        g.setStroke(Color.web("#FFFFFF", .12));
        g.setLineWidth(1);
        g.strokeLine(right + .5, top - 6, right + .5, bottom);
        double xsNow = X.applyAsDouble(position * (n - 1));
        g.setStroke(Color.web("#FFFFFF", .30));
        g.setLineWidth(1);
        g.strokeLine(xsNow, top - 6, xsNow, bottom);
        for (int k = 0; k < values.size(); k++) {
            double v = now.get(k);
            if (Double.isNaN(v)) continue;
            double y = Y.applyAsDouble(v);
            g.setFill(colours.get(k).deriveColor(0, 1, 1, .25));
            g.fillOval(xsNow - 6, y - 6, 12, 12);
            g.setFill(colours.get(k));
            g.fillOval(xsNow - 3, y - 3, 6, 6);
        }
        // Les noms à droite, au bout de leur courbe, écartés pour ne pas se chevaucher.
        Integer[] order = new Integer[values.size()];
        for (int k = 0; k < order.length; k++) order[k] = k;
        java.util.Arrays.sort(order, (p, q) -> Double.compare(Y.applyAsDouble(values.get(p)[n - 1]), Y.applyAsDouble(values.get(q)[n - 1])));
        double lastY = -1e9;
        Font font = Font.font(Plan.MEDIUM, 10);
        g.setFont(font);
        int shown = 0;
        for (int k : order) {
            double y = Math.max(lastY + 15, Math.max(top + 4, Math.min(bottom + 2, Y.applyAsDouble(values.get(k)[n - 1]) + 3.5)));
            if (y > h - 4 || shown >= 11) break;
            lastY = y;
            shown++;
            g.setFill(colours.get(k));
            g.fillText(fit(names.get(k), w - right - 10, font), right + 8, y);
        }
        if (seriesHover >= 0 && seriesHover < n) {
            double x = X.applyAsDouble(seriesHover);
            g.setStroke(Color.web("#FFFFFF", .22));
            g.setLineWidth(1);
            g.strokeLine(x, top - 6, x, bottom);
            List<String> lines = new ArrayList<>();
            List<Color> inks = new ArrayList<>();
            lines.add(I18n.text("image") + " " + (seriesHover + 1));
            inks.add(Plan.INK2);
            for (int j = 0; j < Math.min(6, values.size()); j++) {
                int k = order[j];
                lines.add(names.get(k) + "   " + MoleculeFormat.decimal(values.get(k)[seriesHover], 2)
                        + (series == 1 ? " Å" : series == 2 ? " e" : ""));
                inks.add(colours.get(k));
            }
            Font tipFont = Plan.sans(10);
            double tw = 0;
            for (String line : lines) tw = Math.max(tw, width(line, tipFont));
            tw += 16;
            double th = 8 + 13 * lines.size();
            double bx = x + 8 + tw > right ? x - 8 - tw : x + 8, by = top - 4;
            g.setFill(Plan.RAISED.deriveColor(0, 1, 1, .96));
            g.fillRoundRect(bx, by, tw, th, 10, 10);
            g.setFont(tipFont);
            g.setTextAlign(TextAlignment.LEFT);
            for (int j = 0; j < lines.size(); j++) {
                g.setFill(inks.get(j));
                g.fillText(lines.get(j), bx + 8, by + 15 + 13 * j);
            }
        }
    }

    /**
     * Ce qui a le plus changé du début à la fin du chemin, calculé : les deux indices de Mayer ou les deux longueurs qui
     * bougent le plus ; pour les charges, l'atome qui gagne le plus d'électrons et celui qui en perd le plus.
     */
    private String summary(List<double[]> values, List<String> names) {
        if (values.isEmpty() || path == null) return "";
        int n = path.count();
        if (series == 2) {
            int gain = -1, loss = -1;
            for (int k = 0; k < values.size(); k++) {
                double d = values.get(k)[n - 1] - values.get(k)[0];
                if (d < 0 && (gain < 0 || d < values.get(gain)[n - 1] - values.get(gain)[0])) gain = k;
                if (d > 0 && (loss < 0 || d > values.get(loss)[n - 1] - values.get(loss)[0])) loss = k;
            }
            List<String> parts = new ArrayList<>();
            if (gain >= 0 && values.get(gain)[0] - values.get(gain)[n - 1] >= .02) {
                parts.add(names.get(gain) + " " + I18n.text("gagne") + " "
                        + MoleculeFormat.decimal(values.get(gain)[0] - values.get(gain)[n - 1], 2) + " e");
            }
            if (loss >= 0 && values.get(loss)[n - 1] - values.get(loss)[0] >= .02) {
                parts.add(names.get(loss) + " " + I18n.text("en perd") + " "
                        + MoleculeFormat.decimal(values.get(loss)[n - 1] - values.get(loss)[0], 2) + " e");
            }
            return parts.isEmpty() ? I18n.text("Les charges bougent de moins de 0,02 e du début à la fin.")
                    : String.join("   ·   ", parts) + I18n.text(", du début à la fin du chemin");
        }
        Integer[] order = new Integer[values.size()];
        for (int k = 0; k < order.length; k++) order[k] = k;
        java.util.Arrays.sort(order, (p, q) -> Double.compare(Math.abs(values.get(q)[n - 1] - values.get(q)[0]),
                Math.abs(values.get(p)[n - 1] - values.get(p)[0])));
        List<String> parts = new ArrayList<>();
        int digits = 2;
        for (int j = 0; j < Math.min(2, order.length); j++) {
            double[] v = values.get(order[j]);
            parts.add(names.get(order[j]) + "  " + MoleculeFormat.decimal(v[0], digits) + " → " + MoleculeFormat.decimal(v[n - 1], digits)
                    + (series == 1 ? " Å" : ""));
        }
        return String.join("   ·   ", parts);
    }

    private void tip(GraphicsContext g, double x, double y, String text, double left, double right) {
        Font font = Plan.sans(10.5);
        double tw = width(text, font) + 14;
        double bx = Math.max(left - 30, Math.min(right - tw + 8, x - tw / 2)), by = Math.max(0, y - 30);
        g.setFill(Plan.RAISED.deriveColor(0, 1, 1, .96));
        g.fillRoundRect(bx, by, tw, 20, 10, 10);
        g.setFill(Plan.INK);
        g.setFont(font);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(text, bx + 7, by + 14);
        g.fillOval(x - 3, y - 3, 6, 6);
    }

    /**
     * Les liaisons qui changent, à la géométrie montrée : le genre (un point de couleur), le nom, la longueur, l'indice
     * de Mayer et sa jauge (0 à 2, ou plus pour une liaison multiple) — formées et rompues d'abord.
     */
    private void paintTable() {
        GraphicsContext g = table.getGraphicsContext2D();
        double w = table.getWidth(), h = table.getHeight();
        g.clearRect(0, 0, w, h);
        if (path == null) return;
        StageRenderer.Pose pose = view.pose();
        g.setTextBaseline(VPos.BASELINE);
        if (path.changes.length == 0) {
            g.setFont(Plan.sans(11.5));
            g.setFill(Plan.INK3);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(I18n.text("Aucune liaison ne change"), 0, 18);
            return;
        }
        double colD = w - 118, colM = w - 62, bar = 40;
        g.setFont(Plan.sans(10));
        g.setFill(Plan.INK3);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText("Å", colD, 12);
        g.fillText(I18n.text("Mayer"), colM, 12);
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < path.changes.length; k++) order.add(k);
        order.sort((p, q) -> Integer.compare(Math.abs(StageRenderer.Path.kind(path.changes[q])), Math.abs(StageRenderer.Path.kind(path.changes[p]))));
        double top = 26, row = 26, scale = 2;
        for (int k = 0; k < path.changes.length; k++) scale = Math.max(scale, Math.ceil(view.mayer(k)));
        for (int r = 0; r < Math.min(10, order.size()); r++) {
            int k = order.get(r);
            int[] c = path.changes[k];
            int kind = StageRenderer.Path.kind(c);
            Color colour = kind > 0 ? StageRenderer.FORMING : kind < 0 ? StageRenderer.BREAKING : StageRenderer.CHANGED;
            double y = top + r * row;
            if (r > 0) {
                g.setStroke(Color.web("#FFFFFF", .05));
                g.setLineWidth(1);
                g.strokeLine(0, y - 16.5, w, y - 16.5);
            }
            g.setFill(colour);
            g.fillOval(1, y - 7, 7, 7);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font(Plan.MEDIUM, 12));
            g.setFill(Plan.INK);
            String name = bondName(c);
            g.fillText(name, 15, y);
            g.setFont(Plan.sans(10.5));
            g.setFill(colour);
            String what = kind > 0 ? I18n.text("se forme") : kind < 0 ? I18n.text("se rompt") : c[2] + " → " + c[3];
            g.fillText(fit(what, colD - 60 - 22 - width(name, Font.font(Plan.MEDIUM, 12)), Plan.sans(10.5)),
                    22 + width(name, Font.font(Plan.MEDIUM, 12)), y);
            double m = pose == null ? 0 : view.mayer(k);
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFont(Font.font(Plan.MEDIUM, 12));
            g.setFill(Plan.INK);
            g.fillText(pose == null ? "—" : MoleculeFormat.decimal(StageRenderer.dist(pose.p[c[0]], pose.p[c[1]]), 2), colD, y);
            g.fillText(pose == null ? "—" : MoleculeFormat.decimal(m, 2), colM, y);
            double bx = w - bar, by = y - 6;
            g.setFill(Color.web("#FFFFFF", .07));
            g.fillRoundRect(bx, by, bar, 4, 4, 4);
            g.setFill(colour);
            g.fillRoundRect(bx, by, Math.max(2, bar * Math.min(1, m / scale)), 4, 4, 4);
        }
        if (order.size() > 10) {
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Plan.sans(10.5));
            g.setFill(Plan.INK3);
            g.fillText("+ " + (order.size() - 10), 15, top + 10 * row);
        }
    }

    private String bondName(int[] c) {
        return path.name(c[0]) + "–" + path.name(c[1]);
    }

    // ================================================================== le temps d'une barrière
    static final double YEAR = 365.25 * 86400;
    /**
     * Dit sous une barrière dont la demi-réaction dépasse un an. Vérifié sur l'aldolisation dans l'eau : le chemin imposé
     * sans catalyseur monte à 3,9 eV ; dessinée en trois étapes avec OH⁻ (énolate, addition, reprotonation), ses
     * barrières tombent à 0,83, 0,11 et 2,36 eV, pour le même ΔE (−0,99 eV).
     */
    static final String SLOW = "Trop lent pour se faire : sans catalyseur, ce chemin imposé ne mène nulle part. La réaction "
            + "réelle en prend un autre (catalyse acide ou basique, relais de proton) — dessinez ses étapes.";
    /**
     * La même remarque sans proton qui bouge (le complexe alcyne–dicobalt : 2,74 eV) : parler de catalyse acide ou
     * basique n'y aurait aucun sens. Le chemin imposé est concerté (tout se fait à la fois) ; le vrai mécanisme, par
     * étapes (un CO part, l'alcyne se coordonne, le second CO part).
     */
    static final String SLOW_CONCERTED = "Trop lent pour se faire : ce chemin imposé fait tout à la fois, ce n'est sans doute "
            + "pas le vrai mécanisme — découpez-le en étapes, dans l'ordre où les liaisons se font et se défont.";
    /** Sous la demi-réaction d'une barrière basse : celle de GFN1-xTB, souvent trop basse. */
    static final String UNDERESTIMATED = "GFN1-xTB sous-estime souvent les barrières (Diels–Alder : 0,35 eV calculés, "
            + "≈ 1,2 eV mesurés) : la vraie réaction est sans doute plus lente.";

    /** La remarque d'une étape trop lente : un H passe-t-il d'un atome à un autre (catalyse possible) ? */
    static String slowText(StageRenderer.Path p) {
        for (int[] c : p.changes) {
            if (StageRenderer.Path.kind(c) != 0 && (p.z[c[0]] == 1 || p.z[c[1]] == 1)) return SLOW;
        }
        return SLOW_CONCERTED;
    }
    /** L'âge de l'Univers (13,8 milliards d'années), en secondes. */
    private static final double UNIVERSE = 13.8e9 * YEAR;

    /** La température d'une étape : écrite sur sa flèche (« 80 °C »), sinon 300 K (un moteur plus ancien : 300 K). */
    static double kelvin(MoleculeSchemeStep st) {
        return st.getTemperature() > 1 ? st.getTemperature() : 300;
    }

    /**
     * Le temps de demi-réaction d'une barrière E (eV) à la température T, par la formule d'Eyring :
     * t½ = ln 2 / k, k = (k_B T / h) · e^(−E / k_B T) — la barrière prise pour l'enthalpie libre d'activation.
     */
    static double halfLife(double barrierEv, double kelvin) {
        double kT = 8.617333262e-5 * kelvin;                        // eV
        double attempts = 2.083661912e10 * kelvin;                  // k_B T / h, s⁻¹
        return Math.log(2) / (attempts * Math.exp(-Math.max(0, barrierEv) / kT));
    }

    /**
     * Ce que ΔE ignore : « −TΔS estimé (translations, rotations, 1 bar) −1,20 eV · ΔE − TΔS ≈ +1,09 eV » — dit quand il
     * dépasse 0,05 eV (des molécules partent : les 2 CO du complexe au cobalt).
     */
    static String entropyText(MoleculeSchemeStep st) {
        double tds = st.getEntropyEv();
        if (Math.abs(tds) <= .05) return "";
        return I18n.text("−TΔS estimé (translations, rotations, 1 bar)") + (I18n.language() == I18n.Language.FR ? " : " : ": ")
                + MoleculeFormat.signed(tds, 2) + " eV  ·  ΔE − TΔS ≈ " + MoleculeFormat.signed(st.getDeltaEv() + tds, 2) + " eV";
    }

    /** « demi-réaction (Eyring), 300 K : ≈ 3 h ». */
    static String rateText(double seconds, double kelvin) {
        return I18n.text("demi-réaction (Eyring, barrière GFN1)") + ", " + MoleculeFormat.decimal(kelvin, 0) + " K"
                + (I18n.language() == I18n.Language.FR ? " : " : ": ") + duration(seconds);
    }

    /** Une durée lisible : ms, s, min, h, jours, ans, puis une puissance de dix d'années (et l'âge de l'Univers). */
    static String duration(double s) {
        if (s < 1e-3) return I18n.text("moins d'une milliseconde");
        if (s < 1) return "≈ " + MoleculeFormat.decimal(s * 1000, 0) + " ms";
        if (s < 90) return "≈ " + MoleculeFormat.decimal(s, s < 10 ? 1 : 0) + " s";
        if (s < 5400) return "≈ " + MoleculeFormat.decimal(s / 60, 0) + " min";
        if (s < 2 * 86400) return "≈ " + MoleculeFormat.decimal(s / 3600, s < 36000 ? 1 : 0) + " h";
        if (s < 2 * YEAR) return "≈ " + MoleculeFormat.decimal(s / 86400, 0) + " " + I18n.text("jours");
        double years = s / YEAR;
        if (years < 1e4) return "≈ " + MoleculeFormat.decimal(years, 0) + " " + I18n.text("ans");
        String power = "≈ 10" + superscript((int) Math.floor(Math.log10(years))) + " " + I18n.text("ans");
        return s > UNIVERSE ? power + " — " + I18n.text("plus que l'âge de l'Univers") : power;
    }

    private static String superscript(int n) {
        StringBuilder out = new StringBuilder();
        for (char c : String.valueOf(n).toCharArray()) out.append(c == '-' ? '⁻' : "⁰¹²³⁴⁵⁶⁷⁸⁹".charAt(c - '0'));
        return out.toString();
    }

    // ================================================================== le texte
    private double width(String text, Font font) {
        probe.setFont(font);
        probe.setText(text);
        return probe.getLayoutBounds().getWidth();
    }

    private String fit(String text, double room, Font font) {
        if (room <= 0) return "";
        if (width(text, font) <= room) return text;
        String cut = text;
        while (cut.length() > 1 && width(cut + "…", font) > room) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    // ================================================================== aperçus et tests
    StageRenderer view() {
        return view;
    }

    /** Aperçus : dessine tout de suite (sans attendre l'horloge). */
    void drawNow() {
        readingsDirty = true;
        refreshReadings();
        view.invalidate();
        drawStage();
    }
}
