package org.genesis.chain;

import org.genesis.app.I18n;

import com.studio.genesis.grpc.ChainMaterial;
import com.studio.genesis.grpc.ChainState;
import com.studio.genesis.grpc.ChainTrack;
import com.studio.genesis.grpc.LabFact;
import com.studio.genesis.grpc.UniverseFrame;
import com.studio.genesis.grpc.YieldState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
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
import java.util.List;

/**
 * Le laboratoire de la réaction en chaîne : une scène 3D où l'on comprend la masse critique en regardant.
 *
 * <ul>
 * <li>La matière est un volume de noyaux ; on la comprime en attrapant son bord (la masse reste la même, la densité
 *     monte, les noyaux se resserrent), on en ajoute avec Maj ; on pose un réflecteur en y glissant un jeton.</li>
 * <li>Un clic sur la sphère y fait naître un neutron : les traces calculées par le moteur s'animent, chaque choc
 *     tombe sur un noyau (diffusion, capture γ, fission en deux fragments) ; un clic sur un neutron suit sa lignée.</li>
 * <li>Si la chaîne tient, l'excursion calculée prend le relais : tempête d'étincelles, incandescence, dispersion.</li>
 * <li>L'état se lit à la couleur (aura bleue, ambre, rouge) et au rayon critique en filigrane ; les chiffres restent
 *     à portée de main (« ⓘ Les chiffres »).</li>
 * </ul>
 * Le bac à sable du rendement garde son panneau : courbe d'Aston, jauge Q, tableau comparatif.
 */
public final class ChainView {
    /** Ce que la vue demande au moteur. */
    public interface Actions {
        void back();
        void core(int index);
        void radius(double cm);
        /** Le rayon et la compression ensemble (le geste de compression garde la masse). */
        void geometry(double cm, double compression);
        void reflector(int index, double cm);
        void compression(double factor);
        /** Un tir : `count` neutrons d'amorçage nés en `position` (cm) ; null : près du centre. */
        void fire(double[] position, int count);
        void mode(int mode);
        void nuclide(int code);
        void critical();
        void pickElement();
    }

    private enum Gesture { NONE, ROTATE, COMPRESS, GROW, THICKNESS }

    private static final double EXCURSION_SECONDS = 9, CASCADE_SECONDS = 10, RIM_PX = 9;
    private static final String[] CHIPS = {"U235", "Pu239", "U238", "Th232", "Fe56", "Pb208"};
    private static final String[][] TOKENS = {{"BE", "Be", "#B9CDE6"}, {"C", "C", "#5B6270"}, {"UNAT", "U", "#7C8590"}};
    private static final String[][] QUICK = {{"U-235", "92235"}, {"Pu-239", "94239"}, {"Th-232", "90232"},
            {"Fe-56", "26056"}, {"Fe-60", "26060"}, {"Ni-62", "28062"}, {"Pb-208", "82208"}, {"C-12", "6012"},
            {"He-4", "2004"}, {"Li-6", "3006"}, {"H-2", "1002"}, {"H-3", "1003"}};
    private final Actions actions;
    private final BorderPane root = new BorderPane();
    private final StackPane stage = new StackPane();
    private final Pane holder;
    private final Canvas sceneCanvas = new Canvas(), overlayCanvas = new Canvas();
    private final ChainScene scene = new ChainScene();
    private final ChainBoard board = new ChainBoard();
    private final YieldView yield = new YieldView();
    private final VBox sidebar = new VBox(), sandboxBox = new VBox(10);
    private final ToggleButton simulatorMode = new ToggleButton("Simulateur"), sandboxMode = new ToggleButton("Bac à sable");
    private final ToggleButton numbers = new ToggleButton("ⓘ  Les chiffres");
    private final ToggleGroup chipGroup = new ToggleGroup();
    private final List<ToggleButton> chips = new ArrayList<>();
    private final MenuButton others = new MenuButton("Autres");
    private final List<Label> tokens = new ArrayList<>();
    private final Label tokenGhost = new Label();
    private final Label identity = text("", 12.5, "#DCE8F7"), hint = text("", 11, "#9FB4CE");
    private final Label status = text("", 11, "#90A3BE");
    private final Label nuclideTitle = text("", 16, "#FFD27A"), nuclideData = text("", 11, "#A4B6CD");
    private final Label phase = text("", 10, "#71CDBF"), outcome = text("", 13.5, "#EEF4FF");
    private final Button pause = button("▶", false), replay = button("↺", false);
    private final Button one = button("⚛  Un neutron", true), burst = button("☢  Rafale ×10", false);
    private final Slider speed = new Slider(-1.5, 3.5, 0.7);
    private final Label speedLabel = text("", 11, "#C6D4E8");
    private final VBox dock = new VBox(8), topBar = new VBox(4), modelBanner = new VBox(8);
    private final Label modelText = text("", 11.5, "#E6EEFA");
    private final List<ChainMaterial> cores = new ArrayList<>(), reflectors = new ArrayList<>();
    private ChainState state;
    private boolean updating, playing, dragging, autoSpeed = true;
    private double cascadeNs = Double.NaN, stormClock = Double.NaN;
    private double lastX, lastY, pressX, pressY, hoverX = -1, hoverY = -1, boardScroll;
    private double gestureRadius, gestureCompression, gestureThickness, gestureDistance;
    private Gesture gesture = Gesture.NONE;
    private int run = -1, draggedToken = -1;
    private boolean excursionLoaded;
    private double[] times = new double[0], logN = new double[0], energy = new double[0], temperature = new double[0];
    private double[] radiusFactor = new double[0], kt = new double[0], power = new double[0];
    private List<String> phases = new ArrayList<>();
    private double[] generationCounts = new double[0];
    private List<String[]> facts = new ArrayList<>();
    private double boardTop = 0, boardLeft = 0, boardHeight = 0;
    /** La taille de vue pour laquelle la caméra a été cadrée (cm) : on recadre quand la sphère change vraiment. */
    private double framed = Double.NaN;

    public ChainView(Actions actions) {
        this.actions = actions;
        root.getStyleClass().addAll("fusion-lab", "chain-lab");
        root.setStyle("-fx-background-color:#04070D;-fx-font-family:'Segoe UI';");
        holder = new Pane(sceneCanvas, overlayCanvas);
        holder.setMinSize(100, 100);
        for (Canvas c : new Canvas[]{sceneCanvas, overlayCanvas}) {
            c.widthProperty().bind(holder.widthProperty());
            c.heightProperty().bind(holder.heightProperty());
        }
        overlayCanvas.setMouseTransparent(true);
        holder.setCursor(Cursor.OPEN_HAND);
        holder.setOnMousePressed(this::press);
        holder.setOnMouseDragged(this::drag);
        holder.setOnMouseReleased(this::release);
        holder.setOnMouseMoved(e -> {
            hoverX = e.getX();
            hoverY = e.getY();
            if (!sandbox()) holder.setCursor(cursorAt(e.getX(), e.getY(), e.isShiftDown()));
        });
        holder.setOnMouseExited(e -> hoverX = hoverY = -1);
        holder.setOnScroll(e -> {
            boolean overBoard = numbers.isSelected() && e.getX() >= boardLeft && e.getY() >= boardTop
                    && e.getY() <= boardTop + boardHeight;
            if (overBoard || sandbox()) boardScroll = Math.max(0, boardScroll - e.getDeltaY());
            else scene.camera.scroll(e.getDeltaY() / 40);
        });
        holder.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !sandbox()) {
                scene.camera.orient(-.6, .32);
                scene.camera.flyTo(Math.log10(scene.framing()));
            }
        });
        tokenGhost.setMouseTransparent(true);
        tokenGhost.setVisible(false);
        tokenGhost.setManaged(false);
        Pane floating = new Pane(tokenGhost);
        floating.setPickOnBounds(false);
        floating.setMouseTransparent(true);
        stage.getChildren().addAll(holder, header(), dock(), modelBanner(), floating);
        root.setCenter(stage);
        buildSidebar();
        showMode();
    }

    public Region getNode() {
        return root;
    }

    /** Les symboles des éléments (indice = Z), pour nommer les morceaux dans le bac à sable. */
    public void setSymbols(String[] bySymbolZ) {
        yield.setSymbols(bySymbolZ);
    }

    private boolean sandbox() {
        return sandboxMode.isSelected();
    }

    /** Aperçus : une position de lecture fixe (0–1 cascade, 1–2 excursion). */
    void seek(double position) {
        playing = false;
        if (position < 1) {
            cascadeNs = scene.tracks.isEmpty() ? Double.NaN : position * scene.endNs;
            stormClock = Double.NaN;
        } else {
            cascadeNs = scene.endNs;
            stormClock = (position - 1) * EXCURSION_SECONDS;
        }
    }

    void showFull() {
        numbers.setSelected(true);
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
        Label number = text(index, 9.5, "#36CDF2"), name = text(ChainBoard.spaced(title), 9.5, "#8FB3D9");
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

    // ================================================================== l'en-tête et le dock
    private Region header() {
        ToggleGroup modes = new ToggleGroup();
        for (ToggleButton t : new ToggleButton[]{simulatorMode, sandboxMode}) {
            t.setToggleGroup(modes);
            t.setFocusTraversable(false);
        }
        simulatorMode.getStyleClass().addAll("segment", "left");
        sandboxMode.getStyleClass().addAll("segment", "right");
        simulatorMode.setSelected(true);
        simulatorMode.setId("chain-mode-simulator");
        sandboxMode.setId("chain-mode-sandbox");
        simulatorMode.setTooltip(I18n.tooltip("Une sphère de matière : on la comprime, on y tire des neutrons, on regarde"));
        sandboxMode.setTooltip(I18n.tooltip("N'importe quel noyau : énergie de liaison, Q de la fission et de la fusion, "
                + "réaction en chaîne possible ou non"));
        simulatorMode.setOnAction(e -> { simulatorMode.setSelected(true); showMode(); if (!updating) actions.mode(0); });
        sandboxMode.setOnAction(e -> { sandboxMode.setSelected(true); showMode(); if (!updating) actions.mode(1); });
        numbers.getStyleClass().add("glass-toggle");
        numbers.setFocusTraversable(false);
        numbers.setId("chain-numbers");
        numbers.setTooltip(I18n.tooltip("Les grandeurs calculées : k_eff, masse critique, destin des neutrons, N(t)"));
        Button model = button("Modèle & données", false);
        model.setOnAction(e -> modelBanner.setVisible(!modelBanner.isVisible()));
        Button back = button("← Atome", false);
        back.setOnAction(e -> actions.back());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox controls = new HBox(8, back, new HBox(0, simulatorMode, sandboxMode), phase, gap, numbers, model);
        controls.setAlignment(Pos.CENTER_LEFT);
        outcome.setWrapText(true);
        outcome.setMaxWidth(Double.MAX_VALUE);
        topBar.getChildren().addAll(controls, identity, outcome);
        topBar.setPadding(new Insets(10, 14, 0, 18));
        topBar.setPickOnBounds(false);
        topBar.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(topBar, Pos.TOP_CENTER);
        return topBar;
    }

    private Region dock() {
        // Les matériaux : des pastilles, comme dans la table périodique.
        HBox materials = new HBox(6);
        materials.setAlignment(Pos.CENTER);
        String[][] names = {{"²³⁵U", "Uranium 235"}, {"²³⁹Pu", "Plutonium 239"}, {"²³⁸U", "Uranium 238"},
                {"²³²Th", "Thorium 232"}, {"⁵⁶Fe", "Fer 56"}, {"²⁰⁸Pb", "Plomb 208"}};
        for (int i = 0; i < CHIPS.length; i++) {
            ToggleButton chip = new ToggleButton(names[i][0]);
            chip.setToggleGroup(chipGroup);
            chip.setFocusTraversable(false);
            chip.getStyleClass().add("glass-toggle");
            chip.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-padding:6 12;");
            chip.setTooltip(I18n.tooltip(names[i][1]));
            chip.setId("chain-chip-" + CHIPS[i].toLowerCase(java.util.Locale.ROOT));
            final String key = CHIPS[i];
            chip.setOnAction(e -> {
                if (!chip.isSelected()) chip.setSelected(true);
                chooseCore(key);
            });
            chips.add(chip);
            materials.getChildren().add(chip);
        }
        others.getStyleClass().add("glass-button");
        others.setText("");
        others.setGraphic(text("Autres", 12, "#DCE8F7"));
        others.setFocusTraversable(false);
        others.setTooltip(I18n.tooltip("Autres matériaux…"));
        materials.getChildren().add(others);
        // Les réflecteurs : des jetons qu'on glisse sur la sphère (un clic suffit aussi).
        HBox shells = new HBox(6);
        shells.setAlignment(Pos.CENTER);
        Label caption = text("réflecteur", 10.5, "#8FB3D9");
        shells.getChildren().add(caption);
        String[] tips = {"Béryllium : glisser sur la sphère", "Graphite : glisser sur la sphère",
                "Uranium naturel (tamper) : glisser sur la sphère"};
        for (int i = 0; i < TOKENS.length; i++) {
            Label token = token(TOKENS[i][1], TOKENS[i][2]);
            token.setTooltip(I18n.tooltip(tips[i]));
            final int index = i;
            token.setOnMousePressed(e -> startToken(index, e));
            token.setOnMouseDragged(e -> moveToken(e));
            token.setOnMouseReleased(e -> dropToken(e));
            tokens.add(token);
            shells.getChildren().add(token);
        }
        Label none = token("✕", "#3A4458");
        none.setTooltip(I18n.tooltip("Retirer le réflecteur"));
        none.setOnMouseClicked(e -> { if (!updating) actions.reflector(-1, 0); });
        tokens.add(none);
        shells.getChildren().add(none);
        Region spacer = new Region();
        spacer.setMinWidth(18);
        HBox palette = new HBox(14, materials, spacer, shells);
        palette.setAlignment(Pos.CENTER);
        // La lecture : tirer, rejouer, ralentir.
        one.setId("chain-fire");
        one.setOnAction(e -> fire(null, 1));
        one.setTooltip(I18n.tooltip("Un neutron d'amorçage au centre (ou cliquer dans la sphère pour choisir où)"));
        burst.setId("chain-burst");
        burst.setOnAction(e -> fire(null, 10));
        burst.setTooltip(I18n.tooltip("Dix neutrons d'amorçage à la fois, comme une source"));
        pause.setId("chain-play");
        pause.setOnAction(e -> {
            playing = !playing;
            pause.setText(playing ? "Ⅱ" : "▶");
        });
        replay.setOnAction(e -> {
            cascadeNs = scene.tracks.isEmpty() ? Double.NaN : 0;
            stormClock = Double.NaN;
            playing = true;
            pause.setText("Ⅱ");
        });
        replay.setTooltip(I18n.tooltip("Rejouer le même tir"));
        speed.setPrefWidth(170);
        speed.setId("chain-speed");
        speed.valueProperty().addListener((o, a, b) -> {
            if (!updating) autoSpeed = false;
            refreshSpeed();
        });
        speed.setTooltip(I18n.tooltip("Le ralenti : combien de nanosecondes simulées défilent par seconde"));
        HBox transport = new HBox(8, pause, replay, one, burst, text("ralenti", 10.5, "#8FB3D9"), speed, speedLabel);
        transport.setAlignment(Pos.CENTER);
        hint.setMaxWidth(Double.MAX_VALUE);
        hint.setAlignment(Pos.CENTER);
        dock.getChildren().addAll(hint, palette, transport);
        dock.setAlignment(Pos.CENTER);
        dock.setPadding(new Insets(8, 14, 10, 14));
        dock.setMaxHeight(Region.USE_PREF_SIZE);
        dock.setPickOnBounds(false);
        dock.setStyle("-fx-background-color:linear-gradient(to bottom, rgba(5,9,16,0), rgba(5,9,16,0.9));");
        StackPane.setAlignment(dock, Pos.BOTTOM_CENTER);
        return dock;
    }

    private static Label token(String text, String color) {
        Label token = new Label(text);
        token.setAlignment(Pos.CENTER);
        token.setMinSize(34, 34);
        token.setPrefSize(34, 34);
        token.setStyle("-fx-background-color:radial-gradient(center 35% 30%, radius 75%, derive(" + color + ",45%), " + color + ");"
                + "-fx-background-radius:17;-fx-text-fill:#0A1220;-fx-font-weight:bold;-fx-font-size:12px;"
                + "-fx-border-color:rgba(255,255,255,0.35);-fx-border-radius:17;-fx-cursor:hand;");
        return token;
    }

    private Region modelBanner() {
        Label title = text("Le modèle et ses données", 15, "#FFD27A");
        Button close = button("Fermer", false);
        close.setOnAction(e -> modelBanner.setVisible(false));
        modelText.setWrapText(true);
        modelBanner.getChildren().addAll(title, modelText, close);
        modelBanner.setPadding(new Insets(14, 16, 14, 16));
        modelBanner.setMaxSize(600, Region.USE_PREF_SIZE);
        modelBanner.setStyle("-fx-background-color:linear-gradient(to bottom, rgba(16,27,43,0.97), rgba(7,12,20,0.98));-fx-background-radius:14;"
                + "-fx-border-color:rgba(245,184,75,0.6);-fx-border-radius:14;-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.6), 24, 0.2, 0, 6);");
        modelBanner.setVisible(false);
        StackPane.setAlignment(modelBanner, Pos.CENTER);
        return modelBanner;
    }

    /** Le panneau du bac à sable (le simulateur, lui, n'a plus de panneau : tout se fait dans la scène). */
    private void buildSidebar() {
        FlowPane quick = new FlowPane(6, 6);
        for (String[] q : QUICK) {
            Button chip = button(q[0], false);
            chip.setStyle("-fx-padding:4 8;-fx-font-size:11px;");
            chip.setOnAction(e -> actions.nuclide(Integer.parseInt(q[1])));
            quick.getChildren().add(chip);
        }
        Button table = button("Table périodique  ↗", false);
        table.setMaxWidth(Double.MAX_VALUE);
        table.setId("chain-pick-element");
        table.setOnAction(e -> actions.pickElement());
        Button lighter = button("◀  A − 1", false), heavier = button("A + 1  ▶", false);
        lighter.setOnAction(e -> stepIsotope(-1));
        heavier.setOnAction(e -> stepIsotope(1));
        lighter.setMaxWidth(Double.MAX_VALUE);
        heavier.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(lighter, Priority.ALWAYS);
        HBox.setHgrow(heavier, Priority.ALWAYS);
        nuclideData.setWrapText(true);
        Label split = text("Fission et réaction en chaîne : neutrons, σ_f, k_eff. Fusion : plasma chaud, barrière de "
                + "Coulomb, critère de Lawson. Deux mondes : le tableau de droite les sépare.", 10.5, "#7F97B6");
        split.setWrapText(true);
        sandboxBox.getChildren().addAll(
                section("01", "LE NOYAU", nuclideTitle, nuclideData, table, new HBox(6, lighter, heavier)),
                section("02", "ESSAYER", quick),
                section("03", "DEUX VOIES", split), status);
        status.setWrapText(true);
        sandboxBox.setPadding(new Insets(2, 12, 12, 12));
        sandboxBox.setPrefWidth(296);
        sandboxBox.setMaxWidth(296);
        ScrollPane scroll = new ScrollPane(sandboxBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background:transparent;-fx-background-color:transparent;-fx-border-color:transparent;");
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(3.5, Color.web("#F5B84B"));
        dot.setEffect(new javafx.scene.effect.DropShadow(8, Color.web("#F5B84B")));
        Label brand = text(ChainBoard.spaced("GENESIS"), 10, "#DCE8F7"), room = text(ChainBoard.spaced("QUÊTE DU RENDEMENT"), 8.5, "#7F98B8");
        brand.setStyle(brand.getStyle() + "-fx-font-weight:bold;");
        HBox named = new HBox(9, dot, new VBox(1, brand, room));
        named.setAlignment(Pos.CENTER_LEFT);
        named.setPadding(new Insets(12, 10, 10, 14));
        sidebar.getChildren().addAll(named, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        sidebar.setPrefWidth(300);
        sidebar.setMinWidth(300);
        sidebar.setMaxWidth(300);
        sidebar.getStyleClass().add("glass-side");
    }

    private void stepIsotope(int delta) {
        YieldState y = yield.state();
        if (y != null && y.getZ() > 0) actions.nuclide(y.getZ() * 1000 + Math.max(y.getZ(), y.getA() + delta));
    }

    private void showMode() {
        boolean sand = sandbox();
        root.setLeft(sand ? sidebar : null);
        dock.setVisible(!sand);
        dock.setManaged(!sand);
        identity.setVisible(!sand);
        identity.setManaged(!sand);
        numbers.setVisible(!sand);
        numbers.setManaged(!sand);
        outcome.setVisible(sand);
        outcome.setManaged(sand);
    }

    private void chooseCore(String key) {
        for (int i = 0; i < cores.size(); i++) {
            if (cores.get(i).getKey().equals(key)) {
                if (!updating) actions.core(i);
                return;
            }
        }
    }

    private void fire(double[] position, int count) {
        actions.fire(position, count);
    }

    private void refreshSpeed() {
        scene.nsPerSecond = Math.pow(10, speed.getValue());
        speedLabel.setText(I18n.text("1 s = ") + ChainFormat.time(scene.nsPerSecond * 1e-9));
    }

    // ================================================================== les gestes dans la scène
    private Cursor cursorAt(double x, double y, boolean shift) {
        double[] rim = scene.rimDistance(x, y);
        scene.rimHover = rim[0] < RIM_PX ? 1 : 0;
        if (rim[0] < RIM_PX || rim[1] < RIM_PX) return Cursor.H_RESIZE;
        if (scene.neutronAt(x, y) >= 0) return Cursor.HAND;
        return scene.pointUnder(x, y) != null ? Cursor.CROSSHAIR : Cursor.OPEN_HAND;
    }

    private void press(MouseEvent e) {
        dragging = true;
        lastX = pressX = e.getX();
        lastY = pressY = e.getY();
        gesture = Gesture.ROTATE;
        if (sandbox() || state == null) return;
        double[] rim = scene.rimDistance(e.getX(), e.getY());
        gestureRadius = state.getRadiusCm();
        gestureCompression = state.getCompression();
        gestureThickness = state.getReflectorCm();
        gestureDistance = Math.max(rim[2], .1);
        if (rim[0] < RIM_PX) gesture = e.isShiftDown() ? Gesture.GROW : Gesture.COMPRESS;
        else if (rim[1] < RIM_PX) gesture = Gesture.THICKNESS;
        if (gesture != Gesture.ROTATE) holder.setCursor(Cursor.H_RESIZE);
        else holder.setCursor(Cursor.CLOSED_HAND);
    }

    private void drag(MouseEvent e) {
        if (gesture == Gesture.ROTATE || sandbox()) {
            scene.camera.drag(e.getX() - lastX, e.getY() - lastY);
        } else {
            double distance = Math.max(scene.rimDistance(e.getX(), e.getY())[2], .05);
            double ratio = distance / gestureDistance;
            switch (gesture) {
                case COMPRESS -> {
                    // La masse ne change pas : comprimer, c'est rapetisser la sphère et serrer ses noyaux.
                    double compression = Math.max(.5, Math.min(4, gestureCompression / Math.pow(ratio, 3)));
                    scene.previewScale = Math.cbrt(gestureCompression / compression);
                }
                case GROW -> scene.previewScale = Math.max(.5 / gestureRadius, Math.min(60 / gestureRadius, ratio));
                case THICKNESS -> scene.previewOuter = Math.max(gestureRadius + 1, Math.min(gestureRadius + 30,
                        (gestureRadius + gestureThickness) * ratio));
                default -> { }
            }
        }
        lastX = e.getX();
        lastY = e.getY();
    }

    private void release(MouseEvent e) {
        dragging = false;
        boolean tap = Math.hypot(e.getX() - pressX, e.getY() - pressY) < 5;
        Gesture done = gesture;
        gesture = Gesture.NONE;
        holder.setCursor(Cursor.OPEN_HAND);
        if (sandbox() || state == null) return;
        if (done == Gesture.COMPRESS || done == Gesture.GROW) {
            double scale = scene.previewScale;
            if (Math.abs(scale - 1) > 1e-3) {
                double radius = gestureRadius * scale;
                double compression = done == Gesture.COMPRESS ? gestureCompression / Math.pow(scale, 3) : gestureCompression;
                actions.geometry(radius, compression);
            } else {
                scene.previewScale = 1;
            }
            return;
        }
        if (done == Gesture.THICKNESS) {
            double outer = scene.previewOuter;
            if (!Double.isNaN(outer)) actions.reflector(state.getReflector(), outer - gestureRadius);
            return;
        }
        if (!tap) return;
        int neutron = scene.neutronAt(e.getX(), e.getY());
        if (neutron >= 0) {
            scene.select(neutron == scene.selected ? -1 : neutron);
            return;
        }
        double[] point = scene.pointUnder(e.getX(), e.getY());
        if (point != null) fire(point, 1);
        else scene.select(-1);
    }

    private void startToken(int index, MouseEvent e) {
        draggedToken = index;
        Label token = tokens.get(index);
        tokenGhost.setText(token.getText());
        tokenGhost.setStyle(token.getStyle());
        tokenGhost.resize(34, 34);
        moveToken(e);
        e.consume();
    }

    private void moveToken(MouseEvent e) {
        if (draggedToken < 0) return;
        javafx.geometry.Point2D local = stage.sceneToLocal(e.getSceneX(), e.getSceneY());
        tokenGhost.relocate(local.getX() - 17, local.getY() - 17);
        tokenGhost.setVisible(true);
        javafx.geometry.Point2D inScene = holder.sceneToLocal(e.getSceneX(), e.getSceneY());
        double[] rim = scene.rimDistance(inScene.getX(), inScene.getY());
        boolean over = rim[2] < scene.displayedOuter() * 1.6;
        scene.reflectorPreview = over ? Color.web(TOKENS[draggedToken][2]) : null;
        e.consume();
    }

    private void dropToken(MouseEvent e) {
        int index = draggedToken;
        draggedToken = -1;
        tokenGhost.setVisible(false);
        boolean over = scene.reflectorPreview != null;
        scene.reflectorPreview = null;
        javafx.geometry.Point2D start = tokens.get(index).localToScene(17, 17);
        boolean click = Math.hypot(e.getSceneX() - start.getX(), e.getSceneY() - start.getY()) < 20;
        if (index >= 0 && (over || click)) {
            int reflector = reflectorIndex(TOKENS[index][0]);
            double thickness = state != null && state.getReflectorCm() > 0 ? state.getReflectorCm() : 5;
            if (reflector >= 0) actions.reflector(reflector, thickness);
        }
        e.consume();
    }

    private int reflectorIndex(String key) {
        for (int i = 0; i < reflectors.size(); i++) if (reflectors.get(i).getKey().equals(key)) return i;
        return -1;
    }

    // ================================================================== les frames du moteur
    public void applyFrame(UniverseFrame frame) {
        ChainState s = frame.getChain();
        if (!s.getActive()) {
            if (state != null) {
                state = null;
                run = -1;
                clearShot();
            }
            return;
        }
        updating = true;
        try {
            if (s.getCoresCount() > 0) {
                cores.clear();
                cores.addAll(s.getCoresList());
                reflectors.clear();
                reflectors.addAll(s.getReflectorsList());
                others.getItems().clear();
                for (int i = 0; i < cores.size(); i++) {
                    ChainMaterial m = cores.get(i);
                    if (java.util.Arrays.asList(CHIPS).contains(m.getKey())) continue;
                    MenuItem item = new MenuItem(m.getLabel());
                    final int index = i;
                    item.setOnAction(e -> actions.core(index));
                    others.getItems().add(item);
                }
            }
            if (s.getBoundsEvCount() > 0) scene.bounds = s.getBoundsEvList().stream().mapToDouble(Double::doubleValue).toArray();
            state = s;
            String key = s.getCore() < cores.size() ? cores.get(s.getCore()).getKey() : "";
            int chip = java.util.Arrays.asList(CHIPS).indexOf(key);
            if (chip >= 0) chips.get(chip).setSelected(true);
            else chipGroup.selectToggle(null);
            if ((s.getMode() == 1) != sandbox()) {
                (s.getMode() == 1 ? sandboxMode : simulatorMode).setSelected(true);
                showMode();
            }
        } finally {
            updating = false;
        }
        boolean changed = Math.abs(scene.coreRadius - s.getRadiusCm()) > 1e-9;
        scene.coreRadius = s.getRadiusCm();
        scene.reflected = s.getReflector() >= 0;
        scene.outerRadius = s.getRadiusCm() + (scene.reflected ? s.getReflectorCm() : 0);
        scene.criticalRadius = s.getCriticalRadiusCm();
        scene.reflectorTint = reflectorTint(s.getReflector());
        scene.metal = metal(key(s));
        scene.keff = s.getReady() ? s.getKEff() : scene.keff;
        if (gesture == Gesture.NONE && (changed || s.getReady())) {
            scene.previewScale = 1;
            scene.previewOuter = Double.NaN;
        }
        if (s.getCoreAtoms() > 0) scene.setNuclei(s.getCoreAtoms());
        if (s.getFluxRadiusCount() > 0) {
            scene.fluxRadius = s.getFluxRadiusList().stream().mapToDouble(Double::doubleValue).toArray();
            scene.fluxProfile = s.getFluxProfileList().stream().mapToDouble(Double::doubleValue).toArray();
        }
        if (s.getRun() != run && s.getShot() && s.getTracksCount() > 0) loadShot(s);
        if (s.getRun() == run && s.getTimeSCount() > 0 && !excursionLoaded) loadExcursion(s);
        if (!s.getShot() && run >= 0 && s.getRun() == run) clearShot();
        if (s.getMode() == 1) {
            yield.apply(s.getSandbox());
            YieldState y = s.getSandbox();
            if (!y.getLabel().isEmpty()) {
                nuclideTitle.setText(y.getLabel() + " · " + I18n.text(y.getName()));
                nuclideData.setText(I18n.text("masse " + ChainFormat.decimal(y.getMassU(), 6) + " u · B/A "
                        + ChainFormat.decimal(y.getBindingPerNucleon(), 3) + " MeV"));
            }
        }
        one.setDisable(!s.getReady());
        burst.setDisable(!s.getReady());
        String message = !s.getError().isEmpty() ? s.getError() : s.getStatus();
        status.setText(I18n.text(message));
        modelText.setText(I18n.text(s.getModel()) + "\n\n" + I18n.text(s.getValidation()) + "\n\n"
                + I18n.text("Données : ENDF/B-VIII.0 (NNDC) pour les neutrons, AME2020 pour les masses, ICRP-107 pour les "
                + "désintégrations, ICSBEP pour les assemblages critiques de référence."));
        updateHeader();
    }

    private String key(ChainState s) {
        return s.getCore() < cores.size() ? cores.get(s.getCore()).getKey() : "";
    }

    private void loadShot(ChainState s) {
        run = s.getRun();
        List<ChainScene.Track> list = new ArrayList<>();
        for (ChainTrack t : s.getTracksList()) {
            float[] pts = new float[t.getPointsCount()];
            for (int i = 0; i < pts.length; i++) pts[i] = t.getPoints(i);
            int[] kinds = t.getKindsList().stream().mapToInt(Integer::intValue).toArray();
            int[] groups = t.getGroupsList().stream().mapToInt(Integer::intValue).toArray();
            if (pts.length >= 8) list.add(new ChainScene.Track(t.getGeneration(), t.getParent(), pts, kinds, groups,
                    t.getEnd(), t.getChildren()));
        }
        scene.setTracks(list);
        generationCounts = s.getGenerationCountsList().stream().mapToDouble(Double::doubleValue).toArray();
        times = new double[0];
        excursionLoaded = false;
        facts = new ArrayList<>();
        cascadeNs = 0;
        stormClock = Double.NaN;
        playing = true;
        pause.setText("Ⅱ");
        if (autoSpeed && scene.endNs > 0) {
            updating = true;
            speed.setValue(Math.log10(Math.max(scene.endNs / CASCADE_SECONDS, .03)));
            updating = false;
            refreshSpeed();
        }
        if (s.getTimeSCount() > 0) loadExcursion(s);
    }

    private void loadExcursion(ChainState s) {
        excursionLoaded = true;
        times = s.getTimeSList().stream().mapToDouble(Double::doubleValue).toArray();
        logN = s.getLog10NeutronsList().stream().mapToDouble(Double::doubleValue).toArray();
        energy = s.getEnergyJList().stream().mapToDouble(Double::doubleValue).toArray();
        temperature = s.getTemperatureKList().stream().mapToDouble(Double::doubleValue).toArray();
        radiusFactor = s.getRadiusFactorList().stream().mapToDouble(Double::doubleValue).toArray();
        kt = s.getKTList().stream().mapToDouble(Double::doubleValue).toArray();
        power = s.getPowerWList().stream().mapToDouble(Double::doubleValue).toArray();
        phases = new ArrayList<>(s.getPhaseList());
        facts = new ArrayList<>();
        for (LabFact f : s.getFactsList()) facts.add(new String[]{f.getQuantity(), I18n.text(f.getValue()) + "  ·  " + I18n.text(f.getMeaning())});
    }

    private void clearShot() {
        scene.setTracks(List.of());
        times = new double[0];
        logN = energy = temperature = radiusFactor = kt = power = new double[0];
        phases = new ArrayList<>();
        generationCounts = new double[0];
        facts = new ArrayList<>();
        excursionLoaded = false;
        playing = false;
        cascadeNs = Double.NaN;
        stormClock = Double.NaN;
        scene.timeNs = Double.NaN;
        scene.temperature = 293;
        scene.radiusFactor = 1;
        scene.storm = 0;
    }

    private static Color reflectorTint(int index) {
        return switch (index) {
            case 0 -> Color.web("#B9CDE6");
            case 1 -> Color.web("#5B6270");
            case 2 -> Color.web("#7C8590");
            default -> Color.web("#9FB7D9");
        };
    }

    private static Color metal(String key) {
        if (key.startsWith("Pu") || key.equals("WGPU")) return Color.web("#A8988A");
        if (key.startsWith("Th")) return Color.web("#B3BAC4");
        if (key.startsWith("Fe")) return Color.web("#7A6E68");
        if (key.startsWith("Pb")) return Color.web("#6F7C8E");
        if (key.startsWith("Np") || key.startsWith("Am")) return Color.web("#B0A898");
        return Color.web("#9AA6B8");
    }

    private void updateHeader() {
        ChainState s = state;
        if (s == null) return;
        if (sandbox()) {
            YieldState y = s.getSandbox();
            phase.setText(ChainBoard.spaced("QUÊTE DU RENDEMENT"));
            phase.setStyle("-fx-text-fill:#FFD27A;-fx-font-size:10px;");
            outcome.setText(y.getLabel().isEmpty() ? I18n.text("Choisir un noyau") : I18n.text(y.getVerdict()));
            return;
        }
        phase.setText("");
        ChainMaterial core = s.getCore() < cores.size() ? cores.get(s.getCore()) : null;
        if (core == null) return;
        String reflector = s.getReflector() >= 0 && s.getReflector() < reflectors.size()
                ? " · " + reflectorName(reflectors.get(s.getReflector()).getKey()) + " · " + ChainFormat.length(s.getReflectorCm())
                : "";
        identity.setText(I18n.text(core.getLabel() + " · " + ChainFormat.mass(s.getMassKg()) + " · "
                + ChainFormat.number(s.getDensity()) + " g/cm³" + reflector));
    }

    private static String reflectorName(String key) {
        return switch (key) {
            case "BE" -> "réflecteur de béryllium";
            case "C" -> "réflecteur de graphite";
            default -> "tamper d'uranium naturel";
        };
    }

    // ================================================================== l'image
    public void tick(double dt) {
        double w = sceneCanvas.getWidth(), h = sceneCanvas.getHeight();
        if (w < 10 || h < 10) return;
        GraphicsContext gc = sceneCanvas.getGraphicsContext2D();
        GraphicsContext over = overlayCanvas.getGraphicsContext2D();
        over.clearRect(0, 0, w, h);
        boardLeft = w - ChainBoard.WIDTH - 14;
        boardTop = 96;
        board.hover(hoverX, hoverY + boardScroll);
        yield.hover(hoverX, hoverY);
        if (sandbox()) {
            gc.setFill(Color.web("#04070D"));
            gc.fillRect(0, 0, w, h);
            yield.draw(gc, 18, 96, Math.max(200, boardLeft - 32), h - 96 - 14);
            drawBoard(over, sandboxBoard(), h);
            return;
        }
        advance(dt);
        double right = numbers.isSelected() ? boardLeft - 10 : w;
        double cx = right / 2, cy = h * .46;
        scene.camera.targetX = scene.camera.targetY = scene.camera.targetZ = 0;
        double view = scene.framing();
        if (Double.isNaN(framed) || Math.abs(Math.log(view / framed)) > .15) {
            framed = view;
            scene.camera.flyTo(Math.log10(view));
        }
        if (gesture == Gesture.NONE && hoverX < 0) scene.rimHover = 0;
        scene.draw(gc, w, h, cx, cy, dt, dragging && gesture == Gesture.ROTATE);
        generations(over, cx, h - dock.getHeight() - 30);
        updateHint();
        if (numbers.isSelected()) {
            drawBoard(over, simulatorBoard(), h - dock.getHeight() - 8);
            if (times.length > 1) excursionPanel(over, 18, 104, Math.min(440, boardLeft - 40), 180);
        }
    }

    /** La lecture : la cascade au ralenti choisi, puis, si la chaîne a tenu, l'excursion calculée. */
    private void advance(double dt) {
        if (playing && !Double.isNaN(cascadeNs)) {
            if (cascadeNs < scene.endNs) {
                cascadeNs = Math.min(scene.endNs, cascadeNs + dt * scene.nsPerSecond);
            } else if (stormEligible()) {
                stormClock = Double.isNaN(stormClock) ? 0 : stormClock + dt;
                if (stormClock >= EXCURSION_SECONDS) {
                    stormClock = EXCURSION_SECONDS;
                    playing = false;
                    pause.setText("▶");
                }
            } else if (cascadeNs >= scene.endNs + scene.nsPerSecond * 2.5) {
                playing = false;
                pause.setText("▶");
            } else {
                cascadeNs += dt * scene.nsPerSecond;     // laisser s'éteindre les derniers éclairs
            }
        }
        scene.timeNs = cascadeNs;
        if (!Double.isNaN(stormClock) && times.length > 1) {
            double t = excursionTime();
            scene.temperature = at(temperature, t);
            scene.radiusFactor = at(radiusFactor, t);
            double ln = at(logN, t);
            scene.storm = Math.max(0, Math.min(1, ln / 18));
        } else {
            scene.temperature = 293;
            scene.radiusFactor = 1;
            scene.storm = 0;
        }
    }

    /** La chaîne tirée a-t-elle tenu jusqu'au bout de l'échantillon, et la sphère est-elle sur-critique ? */
    private boolean stormEligible() {
        return state != null && state.getKEff() > 1 && times.length > 1 && generationCounts.length > 1
                && generationCounts[generationCounts.length - 1] > 0;
    }

    private double excursionTime() {
        // La cascade a déjà montré les premières générations : l'excursion reprend là où elle s'arrête.
        double first = Math.max(Math.max(times[1], 1e-15), scene.tracks.isEmpty() ? 0 : scene.endNs * 1e-9);
        double last = Math.max(times[times.length - 1], first * 1.0001);
        double f = Math.max(0, Math.min(1, (Double.isNaN(stormClock) ? 0 : stormClock) / EXCURSION_SECONDS));
        return Math.pow(10, Math.log10(first) + f * (Math.log10(last) - Math.log10(first)));
    }

    private double at(double[] values, double t) {
        if (values.length == 0) return 0;
        for (int i = 1; i < times.length; i++) {
            if (times[i] >= t) {
                double f = (t - times[i - 1]) / Math.max(1e-300, times[i] - times[i - 1]);
                return values[i - 1] + f * (values[i] - values[i - 1]);
            }
        }
        return values[values.length - 1];
    }

    private String phaseAt(double t) {
        for (int i = 1; i < times.length && i < phases.size(); i++) if (times[i] >= t) return phases.get(i);
        return phases.isEmpty() ? "" : phases.get(phases.size() - 1);
    }

    /** Une ligne, en bas : ce qu'on peut faire, ou ce qui vient d'arriver. */
    private void updateHint() {
        String text;
        ChainState s = state;
        if (s == null || !s.getReady()) {
            text = "Le moteur calcule le transport des neutrons…";
        } else if (gesture == Gesture.COMPRESS) {
            text = "Comprimer : la masse reste " + ChainFormat.mass(s.getMassKg()) + ", la densité passe à "
                    + ChainFormat.number(s.getDensity() / Math.pow(scene.previewScale, 3)) + " g/cm³";
        } else if (gesture == Gesture.GROW) {
            text = "Ajouter de la matière : " + ChainFormat.mass(s.getMassKg() * Math.pow(scene.previewScale, 3));
        } else if (gesture == Gesture.THICKNESS) {
            text = "Épaisseur du réflecteur : " + ChainFormat.length(scene.previewOuter - s.getRadiusCm());
        } else if (!Double.isNaN(stormClock) && times.length > 1) {
            double t = excursionTime();
            text = "excursion · t = " + ChainFormat.time(t) + " · " + ChainFormat.temperature(scene.temperature) + " · " + phaseAt(t);
        } else if (!scene.tracks.isEmpty() && !Double.isNaN(cascadeNs) && cascadeNs >= scene.endNs && !stormEligible()) {
            text = s.getKEff() <= 0 ? "Aucune fission : la matière diffuse ou absorbe les neutrons, sans jamais en produire."
                    : s.getKEff() < 1 ? "La chaîne s'éteint : les neutrons fuient ou sont capturés plus vite qu'ils ne naissent."
                    : "Chaîne éteinte : un neutron seul n'allume une chaîne sans fin qu'avec "
                    + ChainFormat.percent(s.getDivergenceProbability()) + " de chances. Encore un neutron, ou une rafale.";
        } else if (scene.selected >= 0) {
            text = "Lignée suivie : un clic dans le vide la relâche.";
        } else {
            text = "Clic dans la sphère : un neutron · glisser le bord : comprimer (Maj : ajouter de la matière) · "
                    + "glisser un jeton : poser un réflecteur · clic sur un neutron : suivre sa lignée";
        }
        hint.setText(I18n.text(text));
    }

    /** Les générations du tir, en bas : 1 → 2 → 4 → 8, ou la chaîne qui s'éteint. */
    private void generations(GraphicsContext gc, double cx, double y) {
        int n = generationCounts.length;
        if (n == 0 || scene.tracks.isEmpty()) return;
        int current = Double.isNaN(cascadeNs) ? -1 : scene.generationAt(cascadeNs);
        double cell = 46, total = n * cell;
        double x0 = cx - total / 2;
        gc.setTextAlign(TextAlignment.CENTER);
        for (int g = 0; g < n; g++) {
            double count = generationCounts[g];
            double x = x0 + g * cell + cell / 2;
            boolean reached = g <= current || cascadeNs >= scene.endNs;
            Color c = count <= 0 ? Color.web("#FF7A6B") : g == current ? Color.web("#FFD27A") : reached ? Color.web("#F5B84B")
                    : Color.color(1, 1, 1, .18);
            int dots = (int) Math.min(12, Math.round(count));
            for (int d = 0; d < dots; d++) {
                double dx = (d % 4 - 1.5) * 6, dy = -(d / 4) * 6;
                gc.setFill(c.deriveColor(0, 1, 1, reached ? 1 : .5));
                gc.fillOval(x + dx - 2, y + dy - 2, 4, 4);
            }
            if (count <= 0) {
                gc.setStroke(c);
                gc.setLineWidth(1.4);
                gc.strokeLine(x - 4, y - 4, x + 4, y + 4);
                gc.strokeLine(x - 4, y + 4, x + 4, y - 4);
            }
            gc.setFont(Font.font("Segoe UI Semibold", 10));
            gc.setFill(c.deriveColor(0, 1, 1, reached ? 1 : .6));
            gc.fillText(count > 12 ? ChainFormat.number(Math.round(count)) : String.valueOf(Math.round(count)), x, y + 16);
            if (g < n - 1) {
                gc.setFill(Color.color(1, 1, 1, .25));
                gc.fillText("→", x + cell / 2, y + 2);
            }
        }
    }

    private void drawBoard(GraphicsContext over, ChainBoard.Board b, double bottom) {
        double height = board.height(b);
        boardHeight = Math.min(height, bottom - boardTop - 8);
        boardScroll = Math.max(0, Math.min(boardScroll, height - boardHeight));
        over.save();
        over.beginPath();
        over.rect(boardLeft - 2, boardTop - 2, ChainBoard.WIDTH + 4, boardHeight + 4);
        over.clip();
        board.draw(over, boardLeft, boardTop - boardScroll, b, 1 / 60.0);
        over.restore();
        if (height > boardHeight + 1) {
            double track = boardHeight * boardHeight / height;
            double at = boardTop + (boardHeight - track) * boardScroll / (height - boardHeight);
            over.setFill(Color.color(1, 1, 1, .18));
            over.fillRoundRect(boardLeft + ChainBoard.WIDTH + 3, at, 3, track, 3, 3);
        }
    }

    private ChainBoard.Board simulatorBoard() {
        ChainState s = state;
        List<ChainBoard.Row> rows = new ArrayList<>();
        if (s == null || (!s.getReady() && s.getKEff() == 0 && s.getRegime().isEmpty())) {
            return new ChainBoard.Board("TRANSPORT DES NEUTRONS", "k_eff", "…", "", 0, .5, null, null, rows, null,
                    "Le moteur calcule le transport des neutrons dans la sphère.", null, null);
        }
        List<ChainBoard.Part> parts = List.of(
                new ChainBoard.Part("fission", ChainFormat.percent(s.getFission()), s.getFission(), Color.web("#F5B84B")),
                new ChainBoard.Part("capture", ChainFormat.percent(s.getCapture()), s.getCapture(), Color.web("#FF7A6B")),
                new ChainBoard.Part("fuite", ChainFormat.percent(s.getLeakage()), s.getLeakage(), Color.web("#7FD3F7")));
        double kinf = s.getKInfinity();
        rows.add(new ChainBoard.Row("kinf", "k∞ (milieu infini)", ChainFormat.decimal(kinf, 3), kinf / 3, 1 / 3.0, "1"));
        if (s.getCriticalRadiusCm() > 0) {
            rows.add(new ChainBoard.Row("rc", "Rayon critique", ChainFormat.length(s.getCriticalRadiusCm()) + " · "
                    + ChainFormat.mass(s.getCriticalMassKg()), s.getRadiusCm() / (2 * s.getCriticalRadiusCm()), .5, "R_c"));
        } else {
            rows.add(new ChainBoard.Row("rc", "Rayon critique", I18n.text("aucun : k∞ < 1"), 0, -1, ""));
        }
        if (s.getKEff() > 0) {
            double dollars = s.getDollars();
            rows.add(new ChainBoard.Row("rho", "Réactivité", ChainFormat.signed(dollars) + " $", (dollars + 1) / 3, 2 / 3.0, "prompt"));
            rows.add(new ChainBoard.Row("period", "Période", s.getPeriodS() == 0 ? "∞" : ChainFormat.time(s.getPeriodS()),
                    s.getPeriodS() > 0 ? Math.max(0, Math.min(1, (3 - Math.log10(s.getPeriodS())) / 12)) : 0, -1, ""));
            rows.add(new ChainBoard.Row("div", "Un neutron allume une chaîne sans fin", ChainFormat.percent(s.getDivergenceProbability()),
                    s.getDivergenceProbability(), -1, ""));
            rows.add(new ChainBoard.Row("lambda", "Temps de génération Λ", ChainFormat.time(s.getGenerationTimeS()),
                    Math.max(0, Math.min(1, (Math.log10(Math.max(s.getGenerationTimeS(), 1e-12)) + 9) / 4)), -1, ""));
            rows.add(new ChainBoard.Row("beta", "Neutrons retardés β", ChainFormat.percent(s.getBeta()), s.getBeta() / .025, -1, ""));
            rows.add(new ChainBoard.Row("nu", "Neutrons par fission ν̄", ChainFormat.decimal(s.getNuBar(), 3), s.getNuBar() / 4, -1, ""));
        }
        rows.add(new ChainBoard.Row("atoms", "Noyaux dans le cœur", ChainFormat.number(s.getCoreAtoms()) + " · "
                + I18n.text("1 point = ") + ChainFormat.number(scene.atomsPerPoint()), 0, -1, ""));
        ChainBoard.Plot plot = null;
        if (s.getCurveRadiusCount() > 1 && s.getKInfinity() > 0) {
            double[] xs = s.getCurveRadiusList().stream().mapToDouble(Double::doubleValue).toArray();
            double[] ys = s.getCurveKList().stream().mapToDouble(Double::doubleValue).toArray();
            double ymax = Math.max(1.5, Math.min(3.2, java.util.Arrays.stream(ys).max().orElse(1.5) * 1.05));
            plot = new ChainBoard.Plot("k_eff SELON LE RAYON", xs, ys, true, 1.0, I18n.text("critique"), s.getRadiusCm(),
                    xs[0], xs[xs.length - 1], 0, ymax, v -> ChainFormat.length(v), v -> "k = " + ChainFormat.decimal(v, 3));
        }
        String note = I18n.text(s.getRegimeText()) + "  " + I18n.text(s.getSourceText());
        List<String[]> list = facts;
        String factsTitle = "ÉQUIVALENCES";
        if (list.isEmpty() && s.getQRecoverableMev() > 0) {
            list = new ArrayList<>();
            list.add(new String[]{"Q récupérable (ENDF)", ChainFormat.number(s.getQRecoverableMev()) + " MeV"});
            list.add(new String[]{"Q des masses (Δm·c²)", ChainFormat.number(s.getQMassMev()) + " MeV"});
            for (LabFact f : s.getEnergySplitList()) list.add(new String[]{f.getQuantity(), f.getValue()});
            factsTitle = "L'ÉNERGIE D'UNE FISSION";
        }
        double fill = Math.max(0, Math.min(1, s.getKEff() / 2));
        return new ChainBoard.Board("TRANSPORT · ENDF/B-VIII.0", "k_eff : neutrons d'une génération à la suivante",
                ChainFormat.decimal(s.getKEff(), 4), "", fill, .5, "DESTIN DES NEUTRONS", parts, rows, plot, note,
                factsTitle, list);
    }

    private ChainBoard.Board sandboxBoard() {
        ChainState s = state;
        List<ChainBoard.Row> rows = new ArrayList<>();
        if (s == null || s.getSandbox().getLabel().isEmpty()) {
            return new ChainBoard.Board("BAC À SABLE", "énergie de liaison par nucléon", "…", "MeV", 0, -1, null, null, rows,
                    null, "Choisir un noyau à gauche.", null, null);
        }
        YieldState y = s.getSandbox();
        rows.add(new ChainBoard.Row("fq", "FISSION · Q de la meilleure coupure",
                y.getFissionKnown() ? ChainFormat.signed(y.getFissionQMev()) + " MeV" : "—",
                y.getFissionKnown() ? .5 + Math.signum(y.getFissionQMev()) * Math.log10(1 + Math.abs(y.getFissionQMev())) / 6 : 0, .5, "0"));
        if (y.getFissionData()) {
            rows.add(new ChainBoard.Row("sf", "σ_f à 0,0253 eV · vers 1,5 MeV", ChainFormat.number(y.getSigmaThermalB()) + " b · "
                    + ChainFormat.number(y.getSigmaFastB()) + " b", Math.min(1, Math.log10(1 + y.getSigmaThermalB()) / 3.5), -1, ""));
            rows.add(new ChainBoard.Row("ki", "k∞ : la chaîne peut-elle diverger ?", ChainFormat.decimal(y.getKInfinity(), 3),
                    y.getKInfinity() / 3, 1 / 3.0, "1"));
            rows.add(new ChainBoard.Row("mc", "Masse critique (sphère nue)", y.getCriticalKg() > 0 ? ChainFormat.mass(y.getCriticalKg())
                    : I18n.text("jamais"), y.getCriticalKg() > 0 ? Math.max(0, 1 - Math.log10(y.getCriticalKg()) / 3) : 0, -1, ""));
        } else {
            rows.add(new ChainBoard.Row("ki", "k∞ : la chaîne peut-elle diverger ?", y.getEvaluated() ? "0" : I18n.text("non calculé"), 0, 1 / 3.0, "1"));
        }
        rows.add(new ChainBoard.Row("uq", "FUSION · Q de " + y.getLabel() + " + " + y.getLabel(),
                y.getFusionKnown() ? ChainFormat.signed(y.getFusionQMev()) + " MeV" : I18n.text("aucun noyau pesé"),
                y.getFusionKnown() ? .5 + Math.signum(y.getFusionQMev()) * Math.log10(1 + Math.abs(y.getFusionQMev())) / 6 : 0, .5, "0"));
        rows.add(new ChainBoard.Row("cb", "Barrière de Coulomb", ChainFormat.number(y.getBarrierMev()) + " MeV",
                Math.min(1, Math.log10(1 + y.getBarrierMev()) / 3.2), -1, ""));
        if (y.getLawsonTriple() > 0) {
            rows.add(new ChainBoard.Row("lw", "Lawson : nτT minimal", ChainFormat.number(y.getLawsonTriple()) + " keV·s/m³",
                    Math.min(1, (Math.log10(y.getLawsonTriple()) - 20) / 6), -1, ""));
        }
        return new ChainBoard.Board("BAC À SABLE · QUÊTE DU RENDEMENT", "énergie de liaison par nucléon " + y.getLabel(),
                ChainFormat.decimal(y.getBindingPerNucleon(), 3), "MeV", y.getBindingPerNucleon() / 9, y.getPeakBa() / 9,
                null, null, rows, null, I18n.text(y.getVerdict()), null, null);
    }

    private void excursionPanel(GraphicsContext gc, double x, double y, double w, double h) {
        ChainBoard.glass(gc, x, y, w, h);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(ChainBoard.LABEL);
        gc.fillText(ChainBoard.spaced("EXCURSION · NEUTRONS N(t) ET TEMPÉRATURE"), x + 14, y + 20);
        double left = x + 44, right = x + w - 16, top = y + 32, bottom = y + h - 44;
        double t0 = Math.log10(Math.max(times[1], 1e-15)), t1 = Math.log10(Math.max(times[times.length - 1], times[1] * 1.0001));
        double nMax = 1, nMin = 0;
        for (double v : logN) nMax = Math.max(nMax, v);
        nMax = Math.ceil(nMax + .5);
        double tMax = 1;
        for (double v : temperature) tMax = Math.max(tMax, v);
        gc.setFill(Color.color(1, 1, 1, .03));
        gc.fillRect(left, top, right - left, bottom - top);
        gc.setFont(Font.font("Segoe UI", 8.5));
        gc.setFill(ChainBoard.DIM);
        gc.setTextAlign(TextAlignment.RIGHT);
        for (int e = 0; e <= nMax; e += Math.max(1, (int) Math.ceil(nMax / 5))) {
            double py = bottom - (e - nMin) / (nMax - nMin) * (bottom - top);
            gc.fillText("10" + org.genesis.common.Visuals.superscript(e), left - 4, py + 3);
            gc.setStroke(Color.color(1, 1, 1, .04));
            gc.strokeLine(left, py, right, py);
        }
        int n = times.length - 1;
        double[] px = new double[n], pyN = new double[n], pyT = new double[n];
        for (int i = 1; i < times.length; i++) {
            double lt = Math.log10(Math.max(times[i], 1e-15));
            px[i - 1] = left + (lt - t0) / Math.max(1e-9, t1 - t0) * (right - left);
            pyN[i - 1] = bottom - (Math.max(nMin, logN[i]) - nMin) / (nMax - nMin) * (bottom - top);
            pyT[i - 1] = bottom - Math.log10(Math.max(temperature[i], 293) / 293) / Math.max(1e-9, Math.log10(tMax / 293)) * (bottom - top);
        }
        gc.setStroke(Color.web("#FF7A6B", .75));
        gc.setLineWidth(1.2);
        gc.setLineDashes(4, 3);
        if (tMax > 300) gc.strokePolyline(px, pyT, n);
        gc.setLineDashes(null);
        gc.setStroke(ChainBoard.TEAL);
        gc.setLineWidth(1.8);
        gc.strokePolyline(px, pyN, n);
        double t = excursionTime();
        if (!Double.isNaN(stormClock)) {
            double cx = left + (Math.log10(t) - t0) / Math.max(1e-9, t1 - t0) * (right - left);
            gc.setStroke(Color.color(1, 1, 1, .55));
            gc.setLineWidth(1);
            gc.strokeLine(cx, top, cx, bottom);
        }
        gc.setFont(Font.font("Segoe UI", 9));
        gc.setFill(ChainBoard.DIM);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(ChainFormat.time(Math.pow(10, t0)), left, bottom + 12);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(ChainFormat.time(Math.pow(10, t1)), right, bottom + 12);
        gc.setTextAlign(TextAlignment.CENTER);
        I18n.fillText(gc, "temps (échelle logarithmique)", (left + right) / 2, bottom + 12);
        double tt = !Double.isNaN(stormClock) ? t : times[times.length - 1];
        String[] values = {"N " + ChainFormat.number(Math.pow(10, at(logN, tt))),
                "P " + ChainFormat.number(at(power, tt)) + " W",
                "E " + ChainFormat.energyJ(at(energy, tt)),
                "T " + ChainFormat.temperature(at(temperature, tt)),
                "k " + ChainFormat.decimal(at(kt, tt), 4)};
        Color[] colors = {ChainBoard.TEAL, ChainBoard.INK, ChainBoard.AMBER, ChainBoard.CORAL, ChainBoard.INK};
        double vx = x + 14;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 11));
        for (int i = 0; i < values.length; i++) {
            gc.setFill(colors[i]);
            gc.fillText(values[i], vx, y + h - 12);
            vx += ChainBoard.width(values[i], Font.font("Segoe UI Semibold", 11)) + 16;
        }
    }
}
