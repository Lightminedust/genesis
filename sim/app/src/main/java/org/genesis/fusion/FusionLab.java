package org.genesis.fusion;

import org.genesis.app.I18n;
import org.genesis.common.LabCamera;
import org.genesis.atom.NucleusPanel;
import org.genesis.atom.SimCanvas;
import org.genesis.common.Visuals;

import com.studio.genesis.grpc.FusionLabState;
import com.studio.genesis.grpc.LabChannel;
import com.studio.genesis.grpc.LabElement;
import com.studio.genesis.grpc.LabFact;
import com.studio.genesis.grpc.LabIsotope;
import com.studio.genesis.grpc.LabNucleus;
import com.studio.genesis.grpc.LabProduct;
import com.studio.genesis.grpc.UniverseFrame;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * Le laboratoire de fusion : une expérience, deux noyaux, vue de côté.
 *
 * Tout ce qui est à l'écran sert l'expérience :
 *  - deux sphères lumineuses (la couleur de l'élément), leur charge +Q et leur
 *    spin (la flèche qui les traverse et le sens de rotation) ;
 *  - le sol quadrillé est la carte de l'énergie : autour de B il se soulève en
 *    colline (la répulsion électrique) et se creuse au centre (le puits de la
 *    force forte). Le noyau A roule dessus, avec sa vraie trajectoire ;
 *  - au-dessus de A, son énergie : la part « mouvement » se change en
 *    « répulsion » pendant qu'il monte (leur somme ne change pas) ; à zéro,
 *    il fait demi-tour, sauf si l'effet tunnel le fait passer ;
 *  - la loi de Coulomb F = k·q₁q₂/r², avec les vraies valeurs, et ce que
 *    devient la force quand la distance change ;
 *  - au contact, la force forte s'allume, les noyaux fusionnent, l'énergie
 *    E = Δm·c² projette les produits.
 *
 * Les choix (table périodique), les explications et le détail technique
 * n'apparaissent qu'à la demande.
 */
public class FusionLab {

    private static final Color SPACE = Color.web("#03050B");
    private static final Color TEXT = Color.web("#EEF2FF");
    private static final Color SOFT = Color.web("#A5B0C6");
    private static final Color MUTED = Color.web("#5D6884");
    private static final Color GRID = Color.web("#38BDF8");
    private static final Color GOLD = Color.web("#FCD34D");
    private static final Color REPULSION = Color.web("#F87171");
    private static final Color STRONG = Color.web("#34D399");
    private static final Color TUNNEL = Color.web("#C4B5FD");
    private static final Color GAMMA = Color.web("#FDE047");
    private static final String UI = "Segoe UI";
    private static final String MONO = "Consolas";

    // La scène (unités arbitraires) : B à droite, A arrive de la gauche.
    private static final double B_X = 42;
    private static final double SPAN = 128;      // distance carte entre B et le point de départ de A
    private static final double HILL = 42;       // échelle des couleurs du sol
    private static final double FUNNEL = 24;     // profondeur de l'entonnoir (puits de la force forte)

    // Les étapes d'un tir (secondes).
    private static final double IONIZE = 1.3;
    private static final double TRAVEL = 3.8;

    private final Canvas canvas = new Canvas(1200, 800);
    private final GraphicsContext gc = canvas.getGraphicsContext2D();
    private final StackPane root = new StackPane();
    private final LabCamera camera = new LabCamera();
    private final Random random = new Random(3);
    private final IntConsumer onA;
    private final IntConsumer onB;
    private final DoubleConsumer onEnergy;
    private final DoubleConsumer onImpact;
    private final IntConsumer onCollide;

    private FusionLabState state;
    private final Map<Integer, LabElement> catalog = new HashMap<>();
    private final Chip chipA = new Chip("A");
    private final Chip chipB = new Chip("B");
    private final Label preview = new Label();
    private final Slider energy = new Slider(-1, Math.log10(5e5), Math.log10(20));
    private final Label energyValue = new Label();
    private final Label temperature = new Label();
    private final Slider impact = new Slider(0, 12, 0);
    private final Label impactValue = new Label("visée : 0 fm (plein centre)");
    private final VBox picker = new VBox(10);
    private final GridPane table = new GridPane();
    private final FlowPane isotopes = new FlowPane(6, 6);
    private final Label pickerTitle = new Label();
    private final TextField search = new TextField();
    private final VBox drawer = new VBox(10);
    private final VBox drawerContent = new VBox(10);
    private final VBox toast = new VBox(4);
    private String drawerMode = "";
    private String picking = "";
    private boolean updating;
    private boolean dragging;
    private double lastX;
    private double lastY;

    private int shownRun;
    private double shotAge = -1;
    private double clock;
    private final double[][] dust = new double[44][4];

    public FusionLab(Runnable onExit, IntConsumer onA, IntConsumer onB, DoubleConsumer onEnergy,
                     DoubleConsumer onImpact, IntConsumer onCollide) {
        this.onA = onA;
        this.onB = onB;
        this.onEnergy = onEnergy;
        this.onImpact = onImpact;
        this.onCollide = onCollide;
        for (double[] d : dust) {
            d[0] = random.nextDouble() * Math.PI * 2;      // angle
            d[1] = 1.25 + random.nextDouble() * 1.1;        // rayon (en rayons de sphère)
            d[2] = (random.nextDouble() - 0.5) * 1.6;       // inclinaison
            d[3] = 0.3 + random.nextDouble() * 0.9;         // vitesse
        }
        Pane holder = new Pane(canvas);
        holder.setMinSize(0, 0);
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        installCamera(holder);

        AnchorPane overlay = new AnchorPane();
        overlay.setPickOnBounds(false);
        overlay.setMinSize(0, 0);
        Region bench = bench();
        benchNode = bench;
        buildResultBar();
        AnchorPane.setBottomAnchor(resultBar, 22.0);
        AnchorPane.setLeftAnchor(resultBar, 0.0);
        AnchorPane.setRightAnchor(resultBar, 0.0);
        AnchorPane.setTopAnchor(bench, 14.0);
        AnchorPane.setLeftAnchor(bench, 260.0);
        AnchorPane.setRightAnchor(bench, 260.0);
        Region launch = launchBar();
        launchNode = launch;
        AnchorPane.setBottomAnchor(launch, 14.0);
        AnchorPane.setLeftAnchor(launch, 200.0);
        AnchorPane.setRightAnchor(launch, 200.0);
        Region corner = cornerButtons(onExit);
        AnchorPane.setTopAnchor(corner, 14.0);
        AnchorPane.setRightAnchor(corner, 14.0);
        buildDrawer();
        AnchorPane.setTopAnchor(drawer, 62.0);
        AnchorPane.setRightAnchor(drawer, 14.0);
        AnchorPane.setBottomAnchor(drawer, 120.0);
        buildToast();
        AnchorPane.setTopAnchor(toast, 62.0);
        AnchorPane.setRightAnchor(toast, 14.0);
        buildPicker();
        AnchorPane.setTopAnchor(picker, 150.0);
        AnchorPane.setLeftAnchor(picker, 0.0);
        AnchorPane.setRightAnchor(picker, 0.0);
        overlay.getChildren().addAll(bench, launch, resultBar, corner, toast, drawer, pickerHolder());
        root.getChildren().addAll(holder, overlay);
        root.setStyle("-fx-background-color: #03050B;");
        camera.yaw = 0;
        camera.pitch = 0.58;
        camera.zoom = 2.25;
    }

    public Region getNode() {
        return root;
    }

    // =====================================================================
    // Caméra
    // =====================================================================
    private void installCamera(Pane holder) {
        holder.setOnMousePressed(event -> {
            dragging = true;
            lastX = event.getX();
            lastY = event.getY();
            holder.setCursor(Cursor.CLOSED_HAND);
            closePicker();
        });
        holder.setOnMouseDragged(event -> {
            camera.drag(event.getX() - lastX, event.getY() - lastY);
            lastX = event.getX();
            lastY = event.getY();
        });
        holder.setOnMouseReleased(event -> {
            dragging = false;
            holder.setCursor(Cursor.OPEN_HAND);
        });
        holder.setOnScroll(event -> camera.scroll(Math.signum(event.getDeltaY()) * 0.5));
        holder.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                camera.orient(0, 0.58);
                camera.flyTo(2.25);
            }
        });
        holder.setCursor(Cursor.OPEN_HAND);
    }

    // =====================================================================
    // L'établi : A + B → ?
    // =====================================================================
    private final class Chip extends Button {
        final Label symbol = new Label("?");
        final Label detail = new Label();
        final String which;

        Chip(String which) {
            this.which = which;
            setAccessibleText("Atome " + which);
            setFocusTraversable(false);
            symbol.setStyle("-fx-text-fill: #EEF2FF; -fx-font-size: 26px; -fx-font-weight: bold;");
            detail.setStyle("-fx-text-fill: #A5B0C6; -fx-font-size: 11px;");
            Label change = new Label("changer ▾");
            change.setStyle("-fx-text-fill: #5D6884; -fx-font-size: 9px;");
            VBox content = new VBox(0, symbol, detail, change);
            content.setAlignment(Pos.CENTER);
            setGraphic(content);
            setPadding(new Insets(6, 16, 6, 16));
            setMinWidth(Region.USE_PREF_SIZE);
            setCursor(Cursor.HAND);
            setOnAction(event -> openPicker(which));
            restyle(false, "#94A3B8");
        }

        void show(LabNucleus nucleus) {
            symbol.setText(nucleus.getLabel());
            detail.setText(nucleus.getName() + " · " + nucleus.getZ() + "p " + (nucleus.getA() - nucleus.getZ()) + "n");
            restyle(picking.equals(which), nucleus.getColor().isEmpty() ? "#94A3B8" : nucleus.getColor());
        }

        void restyle(boolean active, String color) {
            setStyle("-fx-background-color: " + (active ? "#16223A" : "#0B1120E6") + "; -fx-background-radius: 14;"
                    + " -fx-border-color: " + (active ? "#FCD34D" : color + "AA") + "; -fx-border-radius: 14;"
                    + " -fx-border-width: " + (active ? "2" : "1.2") + ";");
        }
    }

    private Region bench() {
        Label plus = new Label("+");
        plus.setStyle("-fx-text-fill: #5D6884; -fx-font-size: 24px;");
        Label arrow = new Label("→");
        arrow.setStyle("-fx-text-fill: #5D6884; -fx-font-size: 22px;");
        preview.setWrapText(true);
        preview.setMaxWidth(380);
        preview.setStyle("-fx-text-fill: #FCD34D; -fx-font-size: 13px; -fx-font-weight: bold;");
        HBox bar = new HBox(12, chipA, plus, chipB, arrow, preview);
        bar.setAlignment(Pos.CENTER);
        bar.setPickOnBounds(false);
        bar.setMinWidth(0);
        return bar;
    }

    // =====================================================================
    // Le choix d'un atome : seulement quand on clique sur A ou B
    // =====================================================================
    private Region pickerHolder() {
        HBox holder = new HBox(picker);
        holder.setAlignment(Pos.TOP_CENTER);
        holder.setPickOnBounds(false);
        AnchorPane.setTopAnchor(holder, 112.0);
        AnchorPane.setLeftAnchor(holder, 0.0);
        AnchorPane.setRightAnchor(holder, 0.0);
        return holder;
    }

    private void buildPicker() {
        pickerTitle.setStyle("-fx-text-fill: #EEF2FF; -fx-font-size: 14px; -fx-font-weight: bold;");
        Button close = new Button("✕");
        close.setFocusTraversable(false);
        close.setStyle("-fx-background-color: transparent; -fx-text-fill: #A5B0C6; -fx-font-size: 14px; -fx-cursor: hand;");
        close.setOnAction(event -> closePicker());
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox header = new HBox(8, pickerTitle, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        search.setPromptText("chercher : or, uranium, Fe…");
        search.setStyle("-fx-background-color: #111827; -fx-text-fill: #EEF2FF; -fx-prompt-text-fill: #5D6884;"
                + " -fx-background-radius: 8;");
        search.textProperty().addListener((obs, old, value) -> filterTable(value));
        table.setHgap(2);
        table.setVgap(2);
        Label isotopeTitle = new Label("ISOTOPES (même élément, nombre de neutrons différent)");
        isotopeTitle.setStyle("-fx-text-fill: #5D6884; -fx-font-size: 9px; -fx-font-weight: bold;");
        picker.getChildren().addAll(header, search, table, isotopeTitle, isotopes);
        picker.setPadding(new Insets(14));
        picker.setMaxWidth(440);
        picker.setStyle("-fx-background-color: #0A0F1CF5; -fx-background-radius: 16; -fx-border-color: #FCD34D66;"
                + " -fx-border-radius: 16;");
        picker.setVisible(false);
        picker.setManaged(false);
    }

    private void openPicker(String which) {
        picking = which;
        pickerTitle.setText(I18n.text("Choisir l'atome ") + which);
        picker.setVisible(true);
        picker.setManaged(true);
        if (state != null) {
            LabNucleus current = which.equals("A") ? state.getA() : state.getB();
            showIsotopes(catalog.get(current.getZ()), current.getA());
            chipA.show(state.getA());
            chipB.show(state.getB());
        }
    }

    private void closePicker() {
        picking = "";
        picker.setVisible(false);
        picker.setManaged(false);
        if (state != null) {
            chipA.show(state.getA());
            chipB.show(state.getB());
        }
    }

    private void choose(int code) {
        (picking.equals("B") ? onB : onA).accept(code);
    }

    private void buildTable() {
        table.getChildren().clear();
        for (LabElement element : catalog.values()) {
            Button tile = new Button(element.getSymbol());
            tile.setUserData(element);
            tile.setFocusTraversable(false);
            tile.setAccessibleText(element.getName());
            tile.setAlignment(Pos.CENTER);
            tile.setPadding(Insets.EMPTY);
            tile.setMinSize(21, 21);
            tile.setPrefSize(21, 21);
            tile.setStyle(tileStyle(element.getColor(), false));
            Tooltip.install(tile, I18n.tooltip(element.getName() + " (Z = " + element.getZ() + ")"));
            tile.setOnAction(event -> {
                showIsotopes(element, element.getDefaultA());
                choose(element.getZ() * 1000 + element.getDefaultA());
            });
            tile.setOnMouseEntered(event -> tile.setStyle(tileStyle(element.getColor(), true)));
            tile.setOnMouseExited(event -> tile.setStyle(tileStyle(element.getColor(), false)));
            int row = element.getRow();
            GridPane.setConstraints(tile, element.getCol() - 1, row >= 9 ? row : row - 1);
            table.getChildren().add(tile);
        }
        Region gap = new Region();
        gap.setMinHeight(5);
        GridPane.setConstraints(gap, 0, 7);
        table.getChildren().add(gap);
    }

    private static String tileStyle(String color, boolean hover) {
        return "-fx-background-color: " + (hover ? color : color + "55") + "; -fx-background-radius: 4;"
                + " -fx-text-fill: " + (hover ? "#03050B" : "#EEF2FF") + "; -fx-font-size: 9.5px; -fx-font-weight: bold;"
                + " -fx-cursor: hand;";
    }

    private void filterTable(String query) {
        String q = query.trim().toLowerCase();
        for (Node node : table.getChildren()) {
            if (node.getUserData() instanceof LabElement element) {
                boolean match = q.isEmpty() || element.getName().toLowerCase().contains(q)
                        || element.getSymbol().toLowerCase().equals(q);
                node.setOpacity(match ? 1 : 0.15);
            }
        }
    }

    private void showIsotopes(LabElement element, int selectedA) {
        isotopes.getChildren().clear();
        if (element == null) {
            return;
        }
        for (LabIsotope isotope : element.getIsotopesList()) {
            boolean usual = isotope.getStable() || isotope.getHalfLifeS() > 3600;
            if (!usual && Math.abs(isotope.getA() - selectedA) > 3) {
                continue;
            }
            String life = isotope.getStable() ? "stable" : isotope.getHalfLifeS() > 0
                    ? Visuals.duration(isotope.getHalfLifeS()) : "instable";
            Button button = new Button(element.getSymbol() + "-" + isotope.getA() + "  " + life);
            button.setFocusTraversable(false);
            boolean selected = isotope.getA() == selectedA;
            button.setStyle("-fx-background-color: " + (selected ? "#FCD34D" : isotope.getStable() ? "#1E3A5F" : "#1A2030")
                    + "; -fx-text-fill: " + (selected ? "#03050B" : "#EEF2FF") + "; -fx-background-radius: 10;"
                    + " -fx-font-size: 11px; -fx-padding: 3 9 3 9; -fx-cursor: hand;");
            button.setOnAction(event -> {
                choose(element.getZ() * 1000 + isotope.getA());
                closePicker();
            });
            isotopes.getChildren().add(button);
        }
    }

    // =====================================================================
    // Le tir
    // =====================================================================
    private Region launchBar() {
        Label title = energyTitle;
        title.setStyle("-fx-text-fill: #5D6884; -fx-font-size: 10px; -fx-font-weight: bold;");
        title.setMinWidth(Region.USE_PREF_SIZE);
        thermalToggle.setFocusTraversable(false);
        thermalToggle.setMinWidth(Region.USE_PREF_SIZE);
        thermalToggle.setSelected(true);
        thermalToggle.setText("Plasma");
        thermalToggle.setTooltip(I18n.tooltip("Plasma : chaque choc a une énergie et une visée tirées au hasard (Maxwell-Boltzmann).\n"
                + I18n.text("Faisceau : énergie fixe, comme dans un accélérateur.")));
        thermalToggle.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #FDBA74; -fx-font-weight: bold;"
                + " -fx-background-radius: 10; -fx-cursor: hand;");
        thermalToggle.selectedProperty().addListener((obs, was, now) -> {
            thermalToggle.setText(now ? "Plasma" : "Faisceau");
            energyTitle.setText(now ? "TEMPÉRATURE DU PLASMA" : "ÉNERGIE DU FAISCEAU");
            if (!updating) {
                onThermal.accept(now ? 1 : 0);
            }
        });
        energy.setPrefWidth(300);
        energy.setFocusTraversable(false);
        energy.valueProperty().addListener((obs, old, value) -> {
            energyValue.setText(energyText(Math.pow(10, value.doubleValue())));
            if (!updating && !energy.isValueChanging()) {
                onEnergy.accept(Math.pow(10, value.doubleValue()));
            }
        });
        energy.valueChangingProperty().addListener((obs, was, now) -> {
            if (!now && !updating) {
                onEnergy.accept(Math.pow(10, energy.getValue()));
            }
        });
        energyValue.setMinWidth(Region.USE_PREF_SIZE);
        energyValue.setStyle("-fx-text-fill: #FCD34D; -fx-font-family: Consolas; -fx-font-size: 15px; -fx-font-weight: bold;");
        temperature.setMinWidth(Region.USE_PREF_SIZE);
        temperature.setStyle("-fx-text-fill: #8B95AD; -fx-font-size: 10.5px;");
        VBox readout = new VBox(0, energyValue, temperature);
        Button fire = action("▶  Lancer", "#1D4ED8", "#EEF2FF");
        fire.setTooltip(I18n.tooltip("un seul tir : il passe, ou il rebondit"));
        fire.setOnAction(event -> {
            closePicker();
            onCollide.accept(0);
        });
        Button insist = action("⚛  Jusqu'à la fusion", "#9A3412", "#FFEDD5");
        insist.setTooltip(I18n.tooltip("on recommence jusqu'à ce que ça passe"));
        insist.setOnAction(event -> {
            closePicker();
            onCollide.accept(1);
        });
        Button replay = action("↺", "#1E293B", "#C7D2FE");
        replay.setTooltip(I18n.tooltip("revoir le dernier tir"));
        replay.setOnAction(event -> {
            if (state != null && state.getFired()) {
                startShot();
            }
        });
        HBox bar = new HBox(14, thermalToggle, title, energy, readout, fire, insist, replay);
        bar.setAlignment(Pos.CENTER);
        bar.setPadding(new Insets(10, 18, 10, 18));
        bar.setStyle(I18n.text("-fx-background-color: #0A0F1CE8; -fx-background-radius: 16; -fx-border-color: #1B2438;")
                + " -fx-border-radius: 16;");
        bar.setMaxWidth(Region.USE_PREF_SIZE);
        HBox wrapper = new HBox(bar);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setPickOnBounds(false);
        wrapper.setMinWidth(0);
        return wrapper;
    }

    static String energyText(double kev) {
        return kev >= 1000 ? Visuals.format(kev / 1000) + " MeV" : Visuals.format(kev) + " keV";
    }

    private static Button action(String text, String background, String foreground) {
        Button button = new Button(text);
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setStyle("-fx-background-color: " + background + "; -fx-text-fill: " + foreground + ";"
                + " -fx-font-size: 13px; -fx-font-weight: bold; -fx-background-radius: 10; -fx-padding: 8 16 8 16;"
                + " -fx-cursor: hand;");
        return button;
    }

    // =====================================================================
    // À la demande : comprendre, expert
    // =====================================================================
    private Region cornerButtons(Runnable onExit) {
        Button understand = action("Comprendre", "#111827", "#C7D2FE");
        understand.setOnAction(event -> toggleDrawer("facts"));
        Button expert = action("Expert", "#111827", "#C7D2FE");
        expert.setOnAction(event -> toggleDrawer("expert"));
        Button back = action("← Atome", "#111827", "#C7D2FE");
        back.setOnAction(event -> onExit.run());
        Button view = action("Vue avancée", "#111827", "#C7D2FE");
        view.setOnAction(event -> {
            advancedView = !advancedView;
            view.setText(advancedView ? "Vue simple" : "Vue avancée");
        });
        HBox box = new HBox(8, view, understand, expert, back);
        box.setPickOnBounds(false);
        return box;
    }

    private void buildDrawer() {
        ScrollPane scroll = new ScrollPane(drawerContent);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        drawerContent.setPadding(new Insets(14));
        drawer.getChildren().add(scroll);
        drawer.setPrefWidth(360);
        drawer.setMaxWidth(360);
        drawer.setStyle("-fx-background-color: #0A0F1CF2; -fx-background-radius: 16; -fx-border-color: #1B2438;"
                + " -fx-border-radius: 16;");
        drawer.setVisible(false);
        drawer.setManaged(false);
    }

    private void toggleDrawer(String mode) {
        drawerMode = drawerMode.equals(mode) ? "" : mode;
        drawer.setVisible(!drawerMode.isEmpty());
        drawer.setManaged(!drawerMode.isEmpty());
        toast.setVisible(drawerMode.isEmpty() && toast.getChildren().size() > 0);
        refreshDrawer();
    }

    private void buildToast() {
        toast.setPadding(new Insets(12, 16, 12, 16));
        toast.setMaxWidth(340);
        toast.setPrefWidth(340);
        toast.setStyle("-fx-background-color: #0A0F1CF2; -fx-background-radius: 14;");
        toast.setVisible(false);
        toast.setPickOnBounds(false);
    }

    private void refreshToast() {
        toast.getChildren().clear();
        boolean revealed = state != null && state.getFired() && shotAge >= revealTime();
        if (!revealed) {
            toast.setVisible(false);
            return;
        }
        List<String> verdict = state.getVerdictList();
        if (state.getFused() || state.getQuasiFission()) {
            toast.setVisible(false);
            return;
        }
        for (int i = 0; i < Math.min(3, verdict.size()); i++) {
            Label line = new Label(verdict.get(i));
            line.setWrapText(true);
            String color = i == 0 ? (state.getFused() ? "#34D399" : "#F87171") : i == 2 ? "#FCD34D" : "#EEF2FF";
            line.setStyle("-fx-text-fill: " + color + "; -fx-font-size: " + (i == 0 ? 22 : 13) + "px; -fx-font-weight: bold;");
            toast.getChildren().add(line);
        }
        Button more = new Button("Comprendre ce résultat →");
        more.setFocusTraversable(false);
        more.setStyle("-fx-background-color: transparent; -fx-text-fill: #7DD3FC; -fx-font-size: 12px; -fx-cursor: hand;"
                + " -fx-padding: 4 0 0 0;");
        more.setOnAction(event -> toggleDrawer("facts"));
        toast.getChildren().add(more);
        toast.setVisible(drawerMode.isEmpty());
    }

    private double revealTime() {
        boolean contact = state.getFused() || state.getQuasiFission();
        return IONIZE + TRAVEL + (contact ? DEFORM + CHAOS + EJECT * 0.75 : 0);
    }

    /** La fiche du résultat : le nouvel élément, ce qui a été éjecté, l'énergie. */
    private void resultCard(List<String> verdict) {
        boolean quasi = state.getQuasiFission();
        Label title = new Label(verdict.isEmpty() ? "" : verdict.get(0));
        title.setStyle("-fx-text-fill: " + (quasi ? "#F87171" : "#34D399") + "; -fx-font-size: 22px; -fx-font-weight: bold;");
        Label reaction = new Label(verdict.size() > 1 ? verdict.get(1) : "");
        reaction.setWrapText(true);
        reaction.setStyle("-fx-text-fill: #EEF2FF; -fx-font-size: 13px; -fx-font-weight: bold;");
        toast.getChildren().addAll(title, reaction);
        LabProduct survivor = finalProduct();
        if (survivor != null) {
            toast.getChildren().add(small("NOUVEL ÉLÉMENT"));
            Label symbol = new Label(survivor.getLabel());
            symbol.setMinWidth(Region.USE_PREF_SIZE);
            symbol.setStyle("-fx-text-fill: " + brightHex(survivor.getColor()) + "; -fx-font-size: 28px; -fx-font-weight: bold;");
            String spin = survivor.getSpinParity().isEmpty() ? "?" : survivor.getSpinParity().replace("+", "").replace("-", "");
            Label details = new Label(survivor.getElement() + "\n" + survivor.getZ() + " protons · "
                    + (survivor.getA() - survivor.getZ()) + " neutrons\ncharge +" + survivor.getZ() + "e · spin " + spin
                    + "\n" + survivor.getStatus());
            details.setStyle("-fx-text-fill: #C7D2FE; -fx-font-size: 11.5px;");
            HBox element = new HBox(14, symbol, details);
            element.setAlignment(Pos.CENTER_LEFT);
            element.setPadding(new Insets(8, 10, 8, 10));
            element.setStyle("-fx-background-color: #10172A; -fx-background-radius: 10;");
            toast.getChildren().add(element);
        }
        // Ce qui est parti : regroupé par nature.
        Map<String, double[]> ejected = new java.util.LinkedHashMap<>();
        for (LabProduct product : state.getProductsList()) {
            if (product == survivor) {
                continue;
            }
            String name = switch (product.getKind()) {
                case "gamma" -> "γ  rayon gamma (lumière)";
                case "positron" -> "e⁺ + ν  positon et neutrino";
                case "neutron" -> "n  neutron";
                default -> product.getLabel().equals("p") ? "p  proton"
                        : product.getLabel().equals("He-4") ? "α  hélium 4" : product.getLabel() + "  " + product.getName();
            };
            ejected.computeIfAbsent(name, k -> new double[2]);
            ejected.get(name)[0] += 1;
            ejected.get(name)[1] += product.getKineticMev();
        }
        if (!ejected.isEmpty()) {
            toast.getChildren().add(small(quasi ? "ILS REPARTENT" : "ÉJECTÉ"));
            for (Map.Entry<String, double[]> entry : ejected.entrySet()) {
                int count = (int) entry.getValue()[0];
                Label row = new Label((count > 1 ? count + " × " : "") + entry.getKey() + "  ·  "
                        + Visuals.format(entry.getValue()[1]) + " MeV");
                row.setStyle("-fx-text-fill: #EEF2FF; -fx-font-size: 12px;");
                toast.getChildren().add(row);
            }
        }
        if (!quasi) {
            toast.getChildren().add(small("ÉNERGIE"));
            double lost = 100 * (state.getMassBeforeU() - state.getMassAfterU()) / state.getMassBeforeU();
            Label energyLine = new Label((state.getQMev() >= 0 ? "+" : "") + Visuals.format(state.getQMev()) + " MeV "
                    + (state.getQMev() >= 0 ? "libérés" : "consommés") + " · masse disparue " + Visuals.format(lost) + " %");
            energyLine.setWrapText(true);
            energyLine.setStyle("-fx-text-fill: #FCD34D; -fx-font-size: 13px; -fx-font-weight: bold;");
            toast.getChildren().add(energyLine);
        }
        if (state.getAttempts() > 1) {
            Label tries = new Label(I18n.text("il a fallu ") + state.getAttempts() + " tirs" + (state.getAbove() ? "" : " (effet tunnel)"));
            tries.setStyle("-fx-text-fill: #A5B0C6; -fx-font-size: 11px;");
            toast.getChildren().add(tries);
        }
        Button more = new Button("Comprendre ce résultat →");
        more.setFocusTraversable(false);
        more.setStyle("-fx-background-color: transparent; -fx-text-fill: #7DD3FC; -fx-font-size: 12px; -fx-cursor: hand;"
                + " -fx-padding: 4 0 0 0;");
        more.setOnAction(event -> toggleDrawer("facts"));
        toast.getChildren().add(more);
        toast.setVisible(drawerMode.isEmpty());
    }

    private void refreshDrawer() {
        drawerContent.getChildren().clear();
        if (state == null || drawerMode.isEmpty()) {
            return;
        }
        if (drawerMode.equals("facts")) {
            drawerContent.getChildren().add(small("CE QUE ÇA VEUT DIRE"));
            for (LabFact fact : state.getFactsList()) {
                Label quantity = small(fact.getQuantity().toUpperCase());
                Label value = new Label(fact.getValue());
                value.setStyle("-fx-text-fill: #FCD34D; -fx-font-family: Consolas; -fx-font-size: 15px; -fx-font-weight: bold;");
                Label meaning = new Label(fact.getMeaning());
                meaning.setWrapText(true);
                meaning.setStyle("-fx-text-fill: #C7D2FE; -fx-font-size: 12px;");
                VBox card = new VBox(2, quantity, value, meaning);
                card.setPadding(new Insets(8, 10, 8, 10));
                card.setStyle("-fx-background-color: #10172A; -fx-background-radius: 10;");
                drawerContent.getChildren().add(card);
            }
            return;
        }
        drawerContent.getChildren().add(small("VISÉE (DÉCALAGE DU TIR)"));
        impact.setFocusTraversable(false);
        impact.setPrefWidth(300);
        impactValue.setStyle("-fx-text-fill: #A5B0C6; -fx-font-size: 11px;");
        drawerContent.getChildren().addAll(impact, impactValue);
        drawerContent.getChildren().add(small("LE CALCUL"));
        for (String line : state.getExpertList()) {
            Label label = new Label(line);
            label.setWrapText(true);
            label.setStyle("-fx-text-fill: #A5B0C6; -fx-font-family: Consolas; -fx-font-size: 10.5px;");
            drawerContent.getChildren().add(label);
        }
        if (state.getChannelsCount() > 0) {
            drawerContent.getChildren().add(small("CE QUE DEVIENT LE NOYAU COMPOSÉ"));
            for (LabChannel channel : state.getChannelsList()) {
                Label label = new Label(Visuals.format(100 * channel.getShare()) + " %  " + channel.getLabel()
                        + "  (Q = " + Visuals.format(channel.getQMev()) + " MeV)");
                label.setWrapText(true);
                label.setStyle("-fx-text-fill: #EEF2FF; -fx-font-family: Consolas; -fx-font-size: 10.5px;");
                drawerContent.getChildren().add(label);
            }
        }
        if (state.getFired()) {
            drawerContent.getChildren().add(small("LE DERNIER TIR"));
            for (LabProduct product : state.getProductsList()) {
                Label label = new Label(product.getLabel() + " · " + Visuals.format(product.getKineticMev()) + " MeV · "
                        + Visuals.format(100 * product.getSpeedC()) + " % de c");
                label.setStyle("-fx-text-fill: #EEF2FF; -fx-font-family: Consolas; -fx-font-size: 10.5px;");
                drawerContent.getChildren().add(label);
            }
        }
    }

    private static Label small(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #5D6884; -fx-font-size: 9.5px; -fx-font-weight: bold;");
        return label;
    }

    // =====================================================================
    // Les frames du moteur
    // =====================================================================
    public void applyFrame(UniverseFrame frame) {
        FusionLabState incoming = frame.getLab();
        if (!incoming.getActive()) {
            state = null;
            return;
        }
        if (incoming.getCatalogCount() > 0) {
            catalog.clear();
            for (LabElement element : incoming.getCatalogList()) {
                catalog.put(element.getZ(), element);
            }
            buildTable();
        }
        state = incoming;
        updating = true;
        chipA.show(incoming.getA());
        chipB.show(incoming.getB());
        preview.setText(incoming.getPreview());
        if (!energy.isValueChanging() && !energy.isPressed()) {
            energy.setValue(Math.log10(incoming.getEnergyKev()));
        }
        energyValue.setText((incoming.getThermal() ? "kT = " : "") + energyText(incoming.getEnergyKev()));
        thermalToggle.setSelected(incoming.getThermal());
        temperature.setText("≈ " + temperatureText(incoming.getTemperatureK()));
        if (!impact.isValueChanging() && !impact.isPressed()) {
            impact.setValue(incoming.getImpactFm());
        }
        updating = false;
        if (impact.getOnMouseReleased() == null) {
            impact.valueProperty().addListener((obs, old, value) ->
                    impactValue.setText(I18n.text("visée : ") + Visuals.format(value.doubleValue()) + " fm"
                            + (value.doubleValue() < 0.05 ? " (plein centre)" : " de décalage")));
            impact.setOnMouseReleased(event -> onImpact.accept(impact.getValue()));
        }
        if (!picking.isEmpty()) {
            LabNucleus current = picking.equals("A") ? incoming.getA() : incoming.getB();
            showIsotopes(catalog.get(current.getZ()), current.getA());
        }
        if (incoming.getFired() && incoming.getRun() != shownRun) {
            shownRun = incoming.getRun();
            startShot();
        } else if (!incoming.getFired()) {
            shotAge = -1;
        }
        refreshToast();
        refreshDrawer();
    }

    static String temperatureText(double kelvin) {
        if (kelvin >= 1e12) {
            return NucleusPanel.scientific(kelvin) + " °C";
        }
        if (kelvin >= 1e9) {
            return Visuals.format(kelvin / 1e9) + " milliards de °C";
        }
        if (kelvin >= 1e6) {
            return Visuals.format(kelvin / 1e6) + " millions de °C";
        }
        return Visuals.format(kelvin) + " °C";
    }

    private boolean fadeInB;
    // Effets de rendu (exagérés), pilotés par des grandeurs calculées.
    private double heatNow;            // 0..1 : la résistance chauffe les nucléons
    private double waveNow;            // 0..1 : onde de compression qui traverse le noyau
    private double ballStretch = 1;    // < 1 écrasé contre la résistance, > 1 étiré par la force forte
    private double ballAngle;          // axe de la déformation
    private double ballJitter;
    private double freeze;             // arrêt sur image restant (s)
    private double sinceHit = 99;      // temps depuis l'impact (s), pour le flash
    private boolean hitDone;

    private void startShot() {
        presenceFrom = lastPresence;
        scaleFrom = lastScale;
        fadeInB = lastPresence < 0.5;
        aFrom = fadeInB ? new double[]{B_X - SPAN * 1.7, 0} : lastAMap;
        bFrom = fadeInB ? null : lastBMap;
        sparks.clear();
        survivorScene = null;
        ejectTurn = null;
        prevSceneA = null;
        // Le plan de la trajectoire : son azimut autour de l'axe du tir est quelconque (la visée a deux dimensions).
        tiltFrom = tiltNow();
        tilt = (random.nextDouble() - 0.5) * 1.0;
        hitDone = false;
        resultDismissed = false;
        survivorReal = null;
        if (state != null) {
            computePrecession();
        }
        freeze = 0;
        sinceHit = 99;
        shotAge = 0;
        refreshToast();
    }

    public void tick(double delta) {
        double dt = Math.min(delta, 0.05);
        frameDt = dt;
        clock += dt;
        sinceHit += dt;
        if (state != null && shotAge >= 0 && freeze > 0) {
            freeze -= dt;                   // arrêt sur image : le temps de la scène est suspendu
        } else if (state != null && shotAge >= 0) {
            stepSparks(dt);
            double before = shotAge;
            shotAge += dt;
            double impact = IONIZE + TRAVEL;
            if (!hitDone && before < impact && shotAge >= impact && (state.getFused() || state.getQuasiFission())) {
                // L'impact : la barrière cède, tout se fige ~130 ms dans un flash blanc.
                shotAge = impact;
                freeze = 0.13;
                sinceHit = 0;
                hitDone = true;
            }
            if (before < revealTime() && shotAge >= revealTime()) {
                refreshToast();
            }
        }
        camera.tick(dt, dragging, 1.6, 3.0);
        syncResultScreen();
        render();
        if (state != null) {
            lastPresence = fieldPresence();
            lastScale = terrainScale();
        }
    }

    // =====================================================================
    // La carte : distance réelle → distance à l'écran, et énergie → hauteur
    // =====================================================================
    private double r0;
    private double rMax;

    private double startFm;
    private double focusX = -20;
    private double focusY;
    private double fit = 1;             // recul de la caméra pour garder les deux noyaux dans le cadre
    private double focusZ;
    private double focusScreen = Double.NaN;
    private double frameDt = 0.016;
    private static final double FAR = SPAN * 0.38;     // au-delà de rMax, la carte se resserre

    /** L'échelle de la carte ne dépend que du couple de noyaux : la barrière garde toujours la même taille. */
    private void setupMap() {
        double barrierR = Math.max(0.5, state.getBarrierRFm());
        r0 = Math.max(0.6, barrierR * 0.45);
        rMax = Math.max(barrierR * 12, state.getContactFm() * 8);
        double turning = state.getTurningFm() > 0 ? state.getTurningFm() : barrierR;
        startFm = state.getPathCount() >= 3 ? Math.hypot(state.getPath(1), state.getPath(2))
                : Math.min(1e6, Math.max(2.5 * turning, Math.max(6 * barrierR, 30)));
    }

    /** Distance réelle (fm) → distance sur la carte : logarithmique, puis resserrée au loin (l'infini au bord). */
    private double mapDistance(double r) {
        if (r <= rMax) {
            return SPAN * Math.log1p(r / r0) / Math.log1p(rMax / r0);
        }
        return SPAN + FAR * (1 - rMax / r);
    }

    private double realDistance(double d) {
        if (d <= SPAN) {
            return r0 * (Math.pow(1 + rMax / r0, d / SPAN) - 1);
        }
        return rMax / (1 - Math.min(d - SPAN, FAR * 0.999) / FAR);
    }

    private double potentialAt(double r) {
        int n = state.getPotentialRCount();
        if (n == 0) {
            return 0;
        }
        if (r <= state.getPotentialR(0)) {
            return state.getPotentialV(0);
        }
        for (int i = 1; i < n; i++) {
            double r1 = state.getPotentialR(i);
            if (r <= r1) {
                double ra = state.getPotentialR(i - 1);
                double t = Math.log(r / ra) / Math.log(r1 / ra);
                return state.getPotentialV(i - 1) + t * (state.getPotentialV(i) - state.getPotentialV(i - 1));
            }
        }
        return state.getPotentialV(n - 1) * state.getPotentialR(n - 1) / r;
    }

    private double scaleMev() {
        double barrier = state.getBarrierMev();
        return Math.max(barrier, 1e-3);
    }

    /** Hauteur du sol à une distance réelle r de B : la colline, et le puits de la force forte au centre. */
    /**
     * Le sol : l'espace autour de B, creusé en entonnoir par le puits de la force forte.
     * La répulsion n'est plus une colline : c'est la lentille (bulle) au-dessus.
     */
    private double hillHeight(double r) {
        if (state != null) {
            return 0;                   // l'espace est plat : ce sont ses cellules qui portent le champ
        }
        double d = mapDistance(Math.max(r, 0.05));
        double width = Math.max(9, mapDistance(Math.max(state.getContactFm(), 0.5)) * 1.5);
        double strong = state.getWellMev() < state.getBarrierMev() * 0.5 ? 1 : 0.55;
        return -FUNNEL * strong * terrainScale() * Math.exp(-(d * d) / (width * width));
    }

    private double floorHeight(double x, double z) {
        double d = Math.hypot(x - B_X, z);
        return hillHeight(realDistance(d)) + ripple(d);
    }

    private double radiusOf(int a) {
        return 7.5 + 1.6 * Math.cbrt(Math.max(1, a));
    }

    // =====================================================================
    // L'espace : une grille où chaque cellule porte le champ réel des deux charges
    // =====================================================================
    /** Énergie (MeV) de ce choc : tirée au hasard dans le plasma, ou celle du faisceau. */
    private double shotEnergyMev() {
        if (state.getFired() && shotAge >= 0 && state.getShotEnergyKev() > 0) {
            return state.getShotEnergyKev() / 1000;
        }
        return state.getEnergyKev() / 1000;
    }

    /** Point de rebroussement (fm) pour l'énergie de ce choc : là où V(r) = E. */
    private double shotTurningFm() {
        double e = shotEnergyMev();
        double barrierR = state.getBarrierRFm();
        if (state.getBarrierMev() <= 0 || e >= state.getBarrierMev()) {
            return 0;
        }
        double low = barrierR;
        double high = Math.max(barrierR * 2, 1e6);
        for (int i = 0; i < 80; i++) {
            double mid = Math.sqrt(low * high);
            if (potentialAt(mid) > e) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return high;
    }

    /** Position réelle (fm, B à l'origine) d'un point de la scène. */
    private double[] realAt(double x, double z) {
        double d = Math.hypot(x - bMap[0], z - bMap[1]);
        double r = realDistance(d);
        double angle = Math.atan2(z - bMap[1], x - bMap[0]);
        return new double[]{r * Math.cos(angle), r * Math.sin(angle)};
    }

    private double[] sceneAt(double rx, double ry) {
        double r = Math.hypot(rx, ry);
        double d = mapDistance(Math.max(r, 1e-6));
        return new double[]{bMap[0] + d * rx / Math.max(r, 1e-9), bMap[1] + d * ry / Math.max(r, 1e-9)};
    }

    // =====================================================================
    // L'espace : des grains de champ, invisibles au repos, qui s'allument quand le champ change
    // =====================================================================
    private double[][] grains;

    private void seedGrains() {
        Random seed = new Random(11);
        grains = new double[700][7];
        for (double[] g : grains) {
            double radius = SPAN * 1.7 * Math.sqrt(seed.nextDouble());
            double angle = seed.nextDouble() * Math.PI * 2;
            g[0] = B_X + radius * Math.cos(angle);
            g[1] = (seed.nextDouble() - 0.5) * 80;
            g[2] = radius * Math.sin(angle);
            g[3] = Double.NaN;          // champ précédent (x, y, z)
        }
    }

    /**
     * Chaque grain mesure le champ électrique des noyaux là où il se trouve (géométrie de la scène) ; il s'allume
     * quand ce champ change — au passage du projectile, puis sous l'onde de choc de la fusion — et s'éteint ensuite.
     * Un sillage dans le vide, sans lignes figées.
     */
    private void drawGrains() {
        if (grains == null) {
            seedGrains();
        }
        double presence = fieldPresence();
        List<double[]> charges = new ArrayList<>();
        if (presence > 0.01) {
            charges.add(new double[]{sceneA[0], sceneA[1], sceneA[2], state.getA().getZ() * presence});
            charges.add(new double[]{sceneB[0], sceneB[1], sceneB[2], state.getB().getZ() * presence});
        }
        if (survivorScene != null && presence < 0.99) {
            LabProduct born = finalProduct();
            if (born != null) {
                charges.add(new double[]{survivorScene[0], survivorScene[1], survivorScene[2], born.getZ() * (1 - presence)});
            }
        }
        double dt = Math.max(frameDt, 1e-3);
        double front = -1;
        if (state.getFired() && shotPhase() >= 3 && (state.getFused() || state.getQuasiFission())) {
            double after = shotAge - IONIZE - TRAVEL - DEFORM;
            if (after > 0 && after < CHAOS + EJECT) {
                front = SPAN * 1.8 * after / (CHAOS + EJECT);
            }
        }
        for (double[] g : grains) {
            double ex = 0;
            double ey = 0;
            double ez = 0;
            for (double[] c : charges) {
                double dx = g[0] - c[0];
                double dy = g[1] - c[1];
                double dz = g[2] - c[2];
                double d2 = dx * dx + dy * dy + dz * dz + 9;
                double d3 = d2 * Math.sqrt(d2);
                ex += c[3] * dx / d3;
                ey += c[3] * dy / d3;
                ez += c[3] * dz / d3;
            }
            double magnitude = Math.sqrt(ex * ex + ey * ey + ez * ez);
            double change = 0;
            if (!Double.isNaN(g[3])) {
                double dx = ex - g[3];
                double dy = ey - g[4];
                double dz = ez - g[5];
                change = Math.sqrt(dx * dx + dy * dy + dz * dz) / (Math.max(magnitude, 1e-9) * dt);
            }
            g[3] = ex;
            g[4] = ey;
            g[5] = ez;
            double lit = Math.min(1, change * 0.35) * Math.min(1, magnitude * 900);
            if (front > 0) {
                double d = Math.sqrt((g[0] - B_X) * (g[0] - B_X) + g[1] * g[1] + g[2] * g[2]);
                lit = Math.max(lit, Math.exp(-Math.pow((d - front) / 10, 2)) * (1 - front / (SPAN * 1.8)));
            }
            g[6] = Math.max(lit, g[6] * Math.exp(-dt * 2.5));
            double[] p = camera.project(g[0], g[1], g[2]);
            if (p == null) {
                continue;
            }
            double alpha = 0.07 + 0.8 * g[6];
            Color color = Color.web("#7DD3FC").interpolate(Color.web("#FDBA74"), Math.min(1, g[6] * 1.4));
            double size = 1.2 + 2.2 * g[6];
            if (g[6] > 0.15) {
                glow(p[0], p[1], size * 3, color, 0.3 * g[6]);
            }
            gc.setFill(color.deriveColor(0, 1, 1, alpha));
            gc.fillOval(p[0] - size / 2, p[1] - size / 2, size, size);
        }
    }

    /** Le plan de la trajectoire, tourné autour de l'axe du tir : (x, z) du plan, hauteur h → espace. */
    private static double[] lift(double x, double h, double z, double angle) {
        return new double[]{x, h + z * Math.sin(angle), z * Math.cos(angle)};
    }

    /** Azimut du plan de ce tir ; au départ d'un tir, il tourne doucement depuis celui du précédent. */
    private double tiltNow() {
        if (shotAge >= 0 && shotAge < 0.9) {
            return tiltFrom + (tilt - tiltFrom) * Visuals.ease(shotAge / 0.9);
        }
        return tilt;
    }

    private double tilt = 0.35;
    private double tiltFrom = 0.35;
    private double[] prevSceneA;
    private final double[] velA = new double[3];

    /**
     * Les lignes du champ électrique, tracées dans l'espace réel (fm) par E = Σ kq r̂/r², puis posées sur la
     * carte. Elles partent de chaque charge, se courbent pour éviter l'autre charge (la répulsion se voit),
     * et leur éclat suit l'intensité du champ : elles s'éteignent au loin, sans frontière.
     */
    private void drawFieldFlow(double[][] charges, double weight) {
        if (weight < 0.02) {
            return;
        }
        double fieldRef = 0;
        for (double[] c : charges) {
            fieldRef = Math.max(fieldRef, c[2] / Math.pow(Math.max(state.getBarrierRFm(), 1), 2));
        }
        if (fieldRef <= 0) {
            return;
        }
        gc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        for (int source = 0; source < charges.length; source++) {
            double q = charges[source][2];
            if (q <= 0.05) {
                continue;
            }
            int lines = (int) Math.max(12, Math.min(28, 10 + 5 * Math.log1p(q)));
            double start = Math.max(0.6, state.getContactFm() * 0.4);
            for (int k = 0; k < lines; k++) {
                double angle = 2 * Math.PI * (k + 0.5) / lines + source * 0.11;
                double x = charges[source][0] + start * Math.cos(angle);
                double y = charges[source][1] + start * Math.sin(angle);
                double[] previous = null;
                for (int step = 0; step < 160; step++) {
                    double ex = 0;
                    double ey = 0;
                    for (double[] c : charges) {
                        double dx = x - c[0];
                        double dy = y - c[1];
                        double r2 = Math.max(dx * dx + dy * dy, 0.04);
                        double r3 = r2 * Math.sqrt(r2);
                        ex += c[2] * dx / r3;
                        ey += c[2] * dy / r3;
                    }
                    double norm = Math.hypot(ex, ey);
                    if (norm < 1e-14) {
                        break;
                    }
                    double nearest = Double.MAX_VALUE;
                    for (double[] c : charges) {
                        nearest = Math.min(nearest, Math.hypot(x - c[0], y - c[1]));
                    }
                    double length = Math.max(0.15, 0.09 * nearest);
                    x += length * ex / norm;
                    y += length * ey / norm;
                    double[] scene = sceneAt(x, y);
                    double strength = Math.max(0, Math.min(1, 0.45 + 0.22 * Math.log10(norm / fieldRef)));
                    if (strength < 0.01) {
                        break;
                    }
                    double[] w = lift(scene[0], 0, scene[1], tiltNow());
                    double[] p = camera.project(w[0], w[1], w[2]);
                    if (p != null && previous != null) {
                        double pulse = Math.max(0, Math.cos(step * 0.35 - clock * 4));
                        Color color = Color.web("#60A5FA").interpolate(Color.web("#FDBA74"), strength);
                        gc.setStroke(color.deriveColor(0, 1, 1, weight * strength * (0.35 + 0.35 * pulse * pulse)));
                        gc.setLineWidth(0.8 + 1.1 * strength);
                        gc.strokeLine(previous[0], previous[1], p[0], p[1]);
                    }
                    previous = p;
                }
            }
        }
        gc.setLineCap(javafx.scene.shape.StrokeLineCap.SQUARE);
    }

    /** La trajectoire déjà parcourue : on voit l'hyperbole d'une déviation, l'approche puis le demi-tour. */
    private void drawTrace() {
        int n = state.getPathCount() / 3;
        if (!state.getFired() || n < 2 || shotPhase() < 1) {
            return;
        }
        double travel = shotPhase() >= 2 ? 1 : (shotAge - IONIZE) / TRAVEL;
        int tunnel = state.getTunnelIndex();
        int last = tunnel >= 0 ? tunnel : n - 1;
        double split = tunnel >= 0 ? 0.82 : 1.0;
        double tNow = state.getPath(3 * last) * Math.min(1, travel / split);
        double y = radiusOf(state.getA().getA()) * 0.25;
        double[] previous = null;
        int shown = 0;
        for (int i = 0; i <= last && state.getPath(3 * i) <= tNow; i++) {
            shown = i;
        }
        Color color = sideColor(true);
        for (int i = 0; i <= shown; i++) {
            double[] s = sceneAt(state.getPath(3 * i + 1), state.getPath(3 * i + 2));
            double[] p = camera.project(s[0], y, s[1]);
            if (p != null && previous != null) {
                double f = (double) i / Math.max(shown, 1);
                gc.setStroke(color.deriveColor(0, 1, 1, 0.08 + 0.5 * f));
                gc.setLineWidth(1 + 1.5 * f);
                gc.strokeLine(previous[0], previous[1], p[0], p[1]);
            }
            previous = p;
        }
    }

    /** Un carré de la grille : où la valeur `iso` le traverse (interpolation linéaire). */
    private void contour(double[][][] points, double[][] level, int i, int j, double iso) {
        double[] v = {level[i][j], level[i + 1][j], level[i + 1][j + 1], level[i][j + 1]};
        double[][] p = {points[i][j], points[i + 1][j], points[i + 1][j + 1], points[i][j + 1]};
        for (double[] q : p) {
            if (q == null) {
                return;
            }
        }
        double[] xs = new double[4];
        double[] ys = new double[4];
        int n = 0;
        for (int e = 0; e < 4; e++) {
            int f = (e + 1) % 4;
            if ((v[e] - iso) * (v[f] - iso) < 0) {
                double t = (iso - v[e]) / (v[f] - v[e]);
                xs[n] = p[e][0] + (p[f][0] - p[e][0]) * t;
                ys[n] = p[e][1] + (p[f][1] - p[e][1]) * t;
                n++;
            }
        }
        if (n >= 2) {
            gc.strokeLine(xs[0], ys[0], xs[1], ys[1]);
        }
        if (n == 4) {
            gc.strokeLine(xs[2], ys[2], xs[3], ys[3]);
        }
    }

    /** Champ faible → nuit bleue ; fort → cyan ; plus fort que l'énergie du choc → orange, blanc. */
    private static Color fieldColor(double t) {
        if (t < 0.45) {
            return Color.web("#06122B").interpolate(Color.web("#0B3A72"), t / 0.45);
        }
        if (t < 0.75) {
            return Color.web("#0B3A72").interpolate(Color.web("#0EA5E9"), (t - 0.45) / 0.3);
        }
        if (t < 0.9) {
            return Color.web("#0EA5E9").interpolate(Color.web("#F97316"), (t - 0.75) / 0.15);
        }
        return Color.web("#F97316").interpolate(Color.web("#FFF7ED"), (t - 0.9) / 0.1);
    }

    // =====================================================================
    // Les noyaux : sphères de verre, cœur brûlant, lignes de champ du dipôle magnétique
    // =====================================================================
    private double[] precessionCum;

    /** Part (0..1) de la précession déjà accomplie : ∫|B|dt le long du trajet, B ∝ 1/r³. */
    private double precessionFraction() {
        int n = state.getPathCount() / 3;
        if (precessionCum == null || precessionCum.length != n || n < 2 || shotPhase() <= 0) {
            return shotPhase() >= 2 ? 1 : 0;
        }
        double travel = shotPhase() >= 2 ? 1 : (shotAge - IONIZE) / TRAVEL;
        int tunnel = state.getTunnelIndex();
        int last = tunnel >= 0 ? tunnel : n - 1;
        double split = tunnel >= 0 ? 0.82 : 1.0;
        double t = state.getPath(3 * last) * Math.min(1, travel / split);
        for (int i = 1; i < n; i++) {
            if (state.getPath(3 * i) >= t) {
                return precessionCum[i];
            }
        }
        return 1;
    }

    private void computePrecession() {
        int n = state.getPathCount() / 3;
        precessionCum = new double[n];
        double total = 0;
        double contact = Math.max(state.getContactFm(), 0.5);
        for (int i = 1; i < n; i++) {
            double r = Math.max(Math.hypot(state.getPath(3 * i + 1), state.getPath(3 * i + 2)), contact);
            total += (state.getPath(3 * i) - state.getPath(3 * (i - 1))) / (r * r * r);
            precessionCum[i] = total;
        }
        for (int i = 0; i < n && total > 0; i++) {
            precessionCum[i] /= total;
        }
    }

    /** Axe du spin (scène : y vers le haut). B : vertical. A : orientation tirée, qui tourne autour du champ de B. */
    private double[] spinAxis(boolean isA) {
        if (axisOverride != null) {
            return normalize(axisOverride);
        }
        if (!isA) {
            return new double[]{0, 1, 0};
        }
        double[] axis = state.getFired() ? new double[]{state.getSpinAx(), Math.abs(state.getSpinAz()) + 0.35, state.getSpinAy()}
                : new double[]{0.35, 0.92, 0.15};
        double n = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        axis = new double[]{axis[0] / n, axis[1] / n, axis[2] / n};
        if (state.getFired() && state.getPrecessionDeg() > 0) {
            // Précession réelle (Bloch) autour du champ de B (vertical), amplifiée pour être visible.
            double angle = Math.toRadians(PRECESSION_SHOWN_DEG) * precessionFraction();
            double c = Math.cos(angle);
            double s = Math.sin(angle);
            axis = new double[]{axis[0] * c - axis[2] * s, axis[1], axis[0] * s + axis[2] * c};
        }
        return axis;
    }

    private static final double PRECESSION_SHOWN_DEG = 120;

    /**
     * Les lignes du champ magnétique, tracées pas à pas dans le champ TOTAL des dipôles présents (superposition) :
     * seul, un noyau dessine la forme classique r = L sin²θ ; quand l'autre approche, les lignes se tordent et
     * se raccordent à lui. Sans moment magnétique (He-4, C-12), aucune ligne.
     */
    private void drawDipoleLines(double cx, double cy, double cz, double radius, double[] axis, double moment, Color tint) {
        if (Math.abs(moment) < 1e-3) {
            return;
        }
        double strength = Math.min(1, 0.6 + 0.4 * Math.log10(1 + Math.abs(moment)));
        double sign = moment >= 0 ? 1 : -1;
        double[] mdir = {axis[0] * sign, axis[1] * sign, axis[2] * sign};
        List<double[]> dipoles = frameDipoles;
        if (dipoles == null) {
            dipoles = List.of(new double[]{cx, cy, cz, mdir[0] * Math.abs(moment), mdir[1] * Math.abs(moment),
                    mdir[2] * Math.abs(moment), radius});
        }
        double[] e1 = Math.abs(mdir[1]) < 0.9 ? cross(mdir, new double[]{0, 1, 0}) : cross(mdir, new double[]{1, 0, 0});
        e1 = normalize(e1);
        double[] e2 = cross(mdir, e1);
        Color color = Color.web("#E0F2FE").interpolate(tint, 0.25);
        double[] shells = {1.7, 2.7};
        int meridians = 6;
        for (double shell : shells) {
            double start = radius * 1.08;
            double theta0 = Math.asin(Math.sqrt(Math.min(1, start / (shell * radius))));
            for (int m = 0; m < meridians; m++) {
                double phi = 2 * Math.PI * m / meridians + clock * 0.2;
                double ox = Math.cos(phi) * e1[0] + Math.sin(phi) * e2[0];
                double oy = Math.cos(phi) * e1[1] + Math.sin(phi) * e2[1];
                double oz = Math.cos(phi) * e1[2] + Math.sin(phi) * e2[2];
                double x = cx + start * (Math.cos(theta0) * mdir[0] + Math.sin(theta0) * ox);
                double y = cy + start * (Math.cos(theta0) * mdir[1] + Math.sin(theta0) * oy);
                double z = cz + start * (Math.cos(theta0) * mdir[2] + Math.sin(theta0) * oz);
                double[] previous = camera.project(x, y, z);
                double length = 0;
                for (int step = 0; step < 240; step++) {
                    double[] b = magneticAt(x, y, z, dipoles);
                    double norm = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
                    if (norm < 1e-12) {
                        break;
                    }
                    double nearest = Double.MAX_VALUE;
                    for (double[] d : dipoles) {
                        nearest = Math.min(nearest, Math.sqrt((x - d[0]) * (x - d[0]) + (y - d[1]) * (y - d[1])
                                + (z - d[2]) * (z - d[2])) - d[6]);
                    }
                    double h = Math.max(0.05 * radius, Math.min(0.35 * radius, 0.15 * Math.max(nearest, 0)));
                    x += h * b[0] / norm;
                    y += h * b[1] / norm;
                    z += h * b[2] / norm;
                    length += h;
                    double[] p = camera.project(x, y, z);
                    if (p != null && previous != null) {
                        double pulse = 0.5 + 0.5 * Math.cos(length / radius * 2.2 - clock * 3 + m);
                        double fade = Math.max(0, 1 - length / (shell * radius * 4.5));
                        gc.setStroke(color.deriveColor(0, 1, 1, Math.min(1, (0.25 + 0.45 * pulse) * strength * fade
                                * 1.8 / shell)));
                        gc.setLineWidth(1.0 + 0.6 * pulse);
                        gc.strokeLine(previous[0], previous[1], p[0], p[1]);
                    }
                    previous = p;
                    if ((step > 6 && nearest < -0.05 * radius) || length > shell * radius * 4.5) {
                        break;
                    }
                }
            }
        }
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalize(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return n < 1e-12 ? new double[]{1, 0, 0} : new double[]{v[0] / n, v[1] / n, v[2] / n};
    }

    /** Une sphère de verre : bord lumineux, reflet, cœur brûlant, nucléons, flèche de spin. */
    private void drawGlassNucleus(double[] p, double scene, double cx, double cy, double cz, LabNucleus nucleus,
                                  Color color, double alpha, boolean isA) {
        double screen = scene * p[3];
        double[] axis = spinAxis(isA);
        gc.setGlobalAlpha(alpha);
        if (advanced()) {
            drawDipoleLines(cx, cy, cz, scene, axis, nucleus.getMagneticMoment(), color);
        }
        Color rim = color.interpolate(Color.web("#F97316"), heatNow * 0.7);
        glow(p[0], p[1], screen * 2.1, rim, 0.25 + 0.3 * heatNow);
        gc.setFill(new RadialGradient(0, 0, p[0], p[1], screen, false, CycleMethod.NO_CYCLE,
                new Stop(0, rim.deriveColor(0, 1, 1, 0.04)), new Stop(0.72, rim.deriveColor(0, 1, 1, 0.10)),
                new Stop(0.93, rim.deriveColor(0, 1, 1, 0.38)), new Stop(1, rim.deriveColor(0, 1, 1, 0.65))));
        gc.fillOval(p[0] - screen, p[1] - screen, screen * 2, screen * 2);
        // Le cœur : là où est la charge, il brûle (d'autant plus que la résistance le chauffe).
        glow(p[0], p[1], screen * (0.55 + 0.35 * heatNow), Color.web("#FDBA74"), 0.55 + 0.4 * heatNow);
        drawCluster(p[0], p[1], screen * 0.42, nucleus.getZ(), nucleus.getA(), ballJitter, 1, 0, alpha);
        // Reflet de verre.
        gc.setFill(new RadialGradient(0, 0, p[0] - screen * 0.38, p[1] - screen * 0.42, screen * 0.5, false,
                CycleMethod.NO_CYCLE, new Stop(0, Color.web("#FFFFFF", 0.45)), new Stop(1, Color.web("#FFFFFF", 0))));
        gc.fillOval(p[0] - screen * 0.88, p[1] - screen * 0.92, screen, screen);
        gc.setStroke(rim.interpolate(Color.WHITE, 0.45).deriveColor(0, 1, 1, 0.85));
        gc.setLineWidth(1.4);
        gc.strokeOval(p[0] - screen, p[1] - screen, screen * 2, screen * 2);
        // Le spin : la flèche le long de l'axe, en 3D.
        if (spinValue(nucleus.getSpinParity()) > 0) {
            double length = scene * 1.55;
            double[] top = camera.project(cx + axis[0] * length, cy + axis[1] * length, cz + axis[2] * length);
            double[] bottom = camera.project(cx - axis[0] * length * 0.7, cy - axis[1] * length * 0.7,
                    cz - axis[2] * length * 0.7);
            if (top != null && bottom != null) {
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(2.2);
                gc.strokeLine(bottom[0], bottom[1], top[0], top[1]);
                double angle = Math.atan2(top[1] - bottom[1], top[0] - bottom[0]);
                gc.setFill(Color.WHITE);
                gc.fillPolygon(new double[]{top[0] + 7 * Math.cos(angle), top[0] + 6 * Math.cos(angle + 2.5),
                        top[0] + 6 * Math.cos(angle - 2.5)}, new double[]{top[1] + 7 * Math.sin(angle),
                        top[1] + 6 * Math.sin(angle + 2.5), top[1] + 6 * Math.sin(angle - 2.5)}, 3);
                if (advanced()) {
                    gc.setFont(Font.font(UI, FontWeight.BOLD, 10.5));
                    gc.setTextAlign(TextAlignment.LEFT);
                    gc.setFill(Color.web("#E0F2FE"));
                    I18n.fillText(gc, "spin " + nucleus.getSpinParity().replace("+", "").replace("-", ""), top[0] + 8, top[1]);
                }
            }
        }
        gc.setGlobalAlpha(1);
    }

    // =====================================================================
    // Le rendu
    // =====================================================================
    private void render() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        gc.setFill(new RadialGradient(0, 0, width / 2, height * 0.55, Math.max(width, height) * 0.75, false,
                CycleMethod.NO_CYCLE, new Stop(0, Color.web("#0B1328")), new Stop(1, SPACE)));
        gc.fillRect(0, 0, width, height);
        if (state == null) {
            return;
        }
        setupMap();
        placeNuclei();
        // Le cadrage suit l'action : entre A et B pendant l'approche, sur le nouveau noyau après la fusion,
        // et dans l'espace libre (à gauche du résultat) quand celui-ci s'affiche. Glissement doux, sans saut.
        notes.clear();
        headline = "";
        double[] want;
        if (state.getFired() && shotPhase() >= 3 && (state.getFused() || state.getQuasiFission())) {
            // Après la fusion : le cadre garde le point d'impact et le nouveau noyau, qui recule.
            want = survivorScene == null ? new double[]{B_X, 0, 0}
                    : new double[]{(survivorScene[0] + B_X) / 2, survivorScene[1] / 2, survivorScene[2] / 2};
        } else {
            want = lift((aMapNow[0] + bMap[0]) / 2, 0, (aMapNow[1] + bMap[1]) / 2, tiltNow());
        }
        double wantScreen = resultShown ? (width - 460) / 2 : (340 + width) / 2;
        double ease = Math.min(1, frameDt * 2.2);
        focusX += (want[0] - focusX) * ease;
        focusY += (want[1] - focusY) * ease;
        focusZ += (want[2] - focusZ) * ease;
        focusScreen = Double.isNaN(focusScreen) ? wantScreen : focusScreen + (wantScreen - focusScreen) * ease;
        camera.targetX = focusX;
        camera.targetY = focusY;
        camera.targetZ = focusZ;
        double jolt = sinceHit < 0.4 ? 9 * (1 - sinceHit / 0.4) : 0;
        double scission = shotAge - IONIZE - TRAVEL - DEFORM - CHAOS;
        if (state.getFired() && state.getFused() && scission >= 0 && scission < 0.6) {
            // La scission : une secousse proportionnelle à l'énergie libérée.
            jolt += Math.min(24, 6 + 6 * Math.log10(1 + Math.abs(state.getQMev()))) * (1 - scission / 0.6);
        }
        camera.setup(width, height, focusScreen + jolt * Math.sin(clock * 97),
                height * 0.58 + jolt * Math.cos(clock * 89), Math.pow(10, camera.zoom) * 1.25 * fit);
        // Si l'un des deux noyaux sortirait de l'écran (déviation forte), la caméra recule en glissant ;
        // elle revient quand ils sont à nouveau dans le cadre.
        double need = 0;
        if (shotPhase() < 3) {
            double left = Math.max(80, focusScreen - (resultShown ? 40 : 330));
            double right = Math.max(80, width - 50 - focusScreen);
            double up = Math.max(80, height * 0.58 - 140);
            double down = Math.max(80, height * 0.42 - 170);
            for (double[] w : new double[][]{sceneA, sceneB}) {
                double[] p = camera.project(w[0], w[1], w[2]);
                if (p != null) {
                    double margin = 18 * p[3];          // la sphère et son aura, pas seulement son centre
                    double dx = p[0] - focusScreen;
                    double dy = p[1] - height * 0.58;
                    need = Math.max(need, Math.max((Math.abs(dx) + margin) / (dx < 0 ? left : right),
                            (Math.abs(dy) + margin) / (dy < 0 ? up : down)));
                }
            }
        }
        double fitGoal = Math.min(3, need > 1 ? fit * need * 1.08 : need < 0.85 ? Math.max(1, fit * need / 0.85) : fit);
        fit += (fitGoal - fit) * Math.min(1, frameDt * (fitGoal > fit ? 4 : 1.2));
        drawGrains();
        if (advanced()) {
            // Vue avancée : les lignes du champ électrique, dans le plan de la trajectoire.
            double presence = fieldPresence();
            double[] rel = relativePosition();
            drawFieldFlow(new double[][]{{rel[0], rel[1], state.getA().getZ()}, {0, 0, state.getB().getZ()}}, presence);
            LabProduct born = state.getFired() && shotPhase() >= 3 ? finalProduct() : null;
            if (born != null && presence < 0.99) {
                double[] at = survivorReal == null ? new double[]{0, 0} : survivorReal;
                drawFieldFlow(new double[][]{{at[0], at[1], born.getZ()}}, 1 - presence);
            }
        }
        if (shotPhase() < 3) {
            drawTraceCm();
        }
        drawGhosts();
        drawExperiment(width, height);
        if (resultShown) {
            drawResultOverlay(width, height);
        } else {
            drawTopLabel(width);
            drawNotes(width, height);
        }
        if (sinceHit < 0.07) {
            // Arrêt sur image : un éclair inversé, l'instant où la barrière cède.
            gc.setGlobalBlendMode(javafx.scene.effect.BlendMode.DIFFERENCE);
            gc.setFill(Color.WHITE);
            gc.fillRect(0, 0, width, height);
            gc.setGlobalBlendMode(javafx.scene.effect.BlendMode.SRC_OVER);
        } else if (sinceHit < 0.45) {
            double flash = Math.max(0, 1 - sinceHit / 0.45);
            gc.setFill(Color.web("#FFFFFF", 0.85 * flash * flash));
            gc.fillRect(0, 0, width, height);
        }
    }

    // =====================================================================
    // Le centre de masse : les deux noyaux bougent, chacun en raison inverse de sa masse
    // =====================================================================
    private double[] bMap = {B_X, 0};
    private double[] survivorScene;
    private double[] aMapNow = {B_X - SPAN, 0};
    private double[] bFrom;
    private double[] lastBMap;
    private double[] lastB;
    private List<double[]> frameDipoles;
    private final List<double[]> sparks = new ArrayList<>();
    private WritableImage liquid;

    /** Part du mouvement relatif portée par A : m_B / (m_A + m_B). */
    private double fracA() {
        double ma = Math.max(state.getMassAU(), 1e-6);
        double mb = Math.max(state.getMassBU(), 1e-6);
        return mb / (ma + mb);
    }

    /** Vecteur relatif (fm) → vecteur dans la scène (même direction, longueur cartographiée). */
    private double[] sceneVector(double rx, double ry) {
        double r = Math.max(Math.hypot(rx, ry), 1e-9);
        double d = mapDistance(r);
        return new double[]{d * rx / r, d * ry / r};
    }

    /**
     * Place A et B autour du centre de masse fixe (B_X, 0) : A = C + (m_B/M)·r, B = C − (m_A/M)·r.
     * La cible n'est pas clouée : elle recule elle aussi, d'autant moins qu'elle est lourde.
     */
    private void placeNuclei() {
        double[] rel = relativePosition();
        double[] v = sceneVector(rel[0], rel[1]);
        double fa = fracA();
        double bx = B_X - (1 - fa) * v[0];
        double bz = -(1 - fa) * v[1];
        double ax = bx + v[0];
        double az = bz + v[1];
        if (shotAge >= 0 && shotAge < 0.9) {
            // Pas de saut : chacun revient en glissant de là où on l'a laissé.
            double k = Visuals.ease(shotAge / 0.9);
            if (aFrom != null) {
                ax = aFrom[0] + (ax - aFrom[0]) * k;
                az = aFrom[1] + (az - aFrom[1]) * k;
            }
            if (bFrom != null) {
                bx = bFrom[0] + (bx - bFrom[0]) * k;
                bz = bFrom[1] + (bz - bFrom[1]) * k;
            }
        }
        bMap = new double[]{bx, bz};
        aMapNow = new double[]{ax, az};
        boolean merged = state.getFired() && shotPhase() >= 3 && (state.getFused() || state.getQuasiFission());
        lastAMap = merged ? new double[]{B_X, 0} : aMapNow;
        lastBMap = merged ? new double[]{B_X, 0} : bMap;
    }

    /** Durée de la traînée, en fraction du trajet montré (≈ 1 s) : derrière, elle s'efface. */
    private static final double TRAIL = 0.28;

    /**
     * La trace du tir dans le centre de masse : une traînée courte derrière A et B, qui s'efface d'elle-même
     * — jamais un trait figé dans l'espace.
     */
    private void drawTraceCm() {
        int n = state.getPathCount() / 3;
        if (!state.getFired() || n < 2 || shotPhase() < 1) {
            return;
        }
        double u = (shotAge - IONIZE) / TRAVEL;
        int tunnel = state.getTunnelIndex();
        int last = tunnel >= 0 ? tunnel : n - 1;
        double split = tunnel >= 0 ? 0.82 : 1.0;
        double tEnd = state.getPath(3 * last);
        double head = tEnd * Math.min(1, u / split);
        double tail = tEnd * (u - TRAIL) / split;
        if (tail >= tEnd) {
            return;
        }
        double fa = fracA();
        double angle = tiltNow();
        for (int body = 0; body < 2; body++) {
            double share = body == 0 ? fa : -(1 - fa);
            Color color = sideColor(body == 0);
            double[] previous = null;
            for (int i = 0; i <= last && state.getPath(3 * i) <= head; i++) {
                double[] v = sceneVector(state.getPath(3 * i + 1), state.getPath(3 * i + 2));
                double[] w = lift(B_X + share * v[0], 0, share * v[1], angle);
                double[] p = camera.project(w[0], w[1], w[2]);
                double f = (state.getPath(3 * i) - tail) / Math.max(head - tail, 1e-12);
                if (p != null && previous != null && f > 0) {
                    gc.setStroke(color.deriveColor(0, 1, 1, (body == 0 ? 0.6 : 0.4) * f * f));
                    gc.setLineWidth(0.6 + 2.2 * f);
                    gc.strokeLine(previous[0], previous[1], p[0], p[1]);
                }
                previous = p;
            }
        }
    }

    /**
     * Les autres chocs du plasma, ratés : le moteur les a calculés (énergie et visée tirées comme les vrais).
     * Chaque visée a son azimut autour de l'axe du tir : les hyperboles forment un cône en 3D, pas un éventail
     * plat. Leurs traînées sont courtes et s'éteignent.
     */
    private void drawGhosts() {
        int count = state.getGhostSizesCount();
        if (count == 0 || !state.getFired() || shotPhase() < 1) {
            return;
        }
        double presence = fieldPresence();
        if (presence < 0.05) {
            return;
        }
        double u = (shotAge - IONIZE) / TRAVEL;
        double fa = fracA();
        Color color = sideColor(true);
        int offset = 0;
        for (int g = 0; g < count; g++) {
            int size = state.getGhostSizes(g);
            double tEnd = state.getGhosts(3 * (offset + size - 1));
            double head = tEnd * Math.min(1, u);
            double tail = tEnd * (u - TRAIL * 1.3);
            double angle = tiltNow() + 2.39996 * (g + 1);
            double[] previous = null;
            double[] tip = null;
            for (int i = 0; i < size; i++) {
                int k = 3 * (offset + i);
                double t = state.getGhosts(k);
                if (t > head) {
                    break;
                }
                double[] v = sceneVector(state.getGhosts(k + 1), state.getGhosts(k + 2));
                double[] w = lift(B_X + fa * v[0], 0, fa * v[1], angle);
                double[] p = camera.project(w[0], w[1], w[2]);
                double f = (t - tail) / Math.max(head - tail, 1e-12);
                if (p != null && previous != null && f > 0) {
                    gc.setStroke(color.deriveColor(0, 0.7, 1.1, 0.42 * presence * f * f));
                    gc.setLineWidth(0.5 + 1.3 * f);
                    gc.strokeLine(previous[0], previous[1], p[0], p[1]);
                }
                previous = p;
                tip = p;
            }
            double fade = u < 1 ? 1 : Math.max(0, 1 - (u - 1) / TRAIL);
            if (tip != null && fade > 0) {
                glow(tip[0], tip[1], 9, color, 0.45 * presence * fade);
                gc.setFill(color.deriveColor(0, 0.6, 1.2, 0.8 * presence * fade));
                gc.fillOval(tip[0] - 2.5, tip[1] - 2.5, 5, 5);
            }
            offset += size;
        }
    }

    // =====================================================================
    // La goutte liquide : surface implicite des nucléons (metaballs), Σ r²/d² = 1
    // =====================================================================
    /** Modes de surface de la goutte (amortis) : a₂ (quadrupôle), a₃ (octupôle), et l'axe qui tourne. */
    private static double[] dropModes(double t) {
        double tt = Math.max(0, t);
        double a2 = 0.32 * Math.exp(-tt / 1.3) * Math.sin(2 * Math.PI * 1.6 * tt + 0.4);
        double a3 = 0.17 * Math.exp(-tt / 0.9) * Math.sin(2 * Math.PI * 2.4 * tt + 1.1);
        return new double[]{a2, a3, 0.7 + 0.5 * tt};
    }

    /** Glisse chaque nucléon de sa place dans A ou B vers sa place dans le noyau composé (protons vers protons). */
    private static List<double[]> morph(List<double[]> from, List<double[]> to, double k) {
        if (from.size() != to.size()) {
            return null;
        }
        List<double[]> out = new ArrayList<>();
        for (int type = 0; type < 2; type++) {
            final int kind = type;
            List<double[]> a = new ArrayList<>(from.stream().filter(b -> b[6] == kind).toList());
            List<double[]> b = new ArrayList<>(to.stream().filter(c -> c[6] == kind).toList());
            if (a.size() != b.size()) {
                return null;
            }
            a.sort((p, q) -> Double.compare(p[0], q[0]));
            b.sort((p, q) -> Double.compare(p[0], q[0]));
            for (int i = 0; i < a.size(); i++) {
                double[] p = a.get(i);
                double[] q = b.get(i);
                out.add(new double[]{p[0] + (q[0] - p[0]) * k, p[1] + (q[1] - p[1]) * k, p[2] + (q[2] - p[2]) * k,
                        q[3], q[4], q[5], q[6]});
            }
        }
        return out;
    }

    /** Ajoute les nucléons d'un noyau (écran) ; a2, a3 : modes de surface quadrupôle et octupôle de la goutte. */
    private void nucleonBalls(List<double[]> out, double cx, double cy, double radius, int z, int a,
                              double a2, double a3, double axisAngle, double jitter) {
        double[][] pts = layout(z, a);
        int n = pts.length;
        int stride = Math.max(1, n / 40);
        double rBall = radius * (n == 1 ? 0.95 : 1.2 / Math.cbrt(n)) * Math.cbrt(stride);
        double turn = clock * 0.5;
        double cosT = Math.cos(turn);
        double sinT = Math.sin(turn);
        double cosA = Math.cos(axisAngle);
        double sinA = Math.sin(axisAngle);
        for (int i = 0; i < n; i += stride) {
            double x = pts[i][0] * cosT + pts[i][2] * sinT;
            double zz = -pts[i][0] * sinT + pts[i][2] * cosT;
            double y = pts[i][1] * 0.94 + zz * 0.34;
            double rr = Math.hypot(x, y);
            double c = rr > 1e-6 ? (x * cosA + y * sinA) / rr : 0;
            double factor = 1 + a2 * 0.5 * (3 * c * c - 1) + a3 * 0.5 * (5 * c * c * c - 3 * c);
            x *= factor;
            y *= factor;
            if (jitter > 0) {
                x += jitter * 0.12 * Math.sin(clock * 31 + i * 7.3);
                y += jitter * 0.12 * Math.cos(clock * 27 + i * 3.1);
            }
            boolean proton = pts[i][3] > 0;
            Color col = proton ? PROTON_RED : NEUTRON_BLUE;
            out.add(new double[]{cx + x * radius, cy - y * radius, rBall, col.getRed(), col.getGreen(), col.getBlue(),
                    proton ? 1 : 0});
        }
    }

    /** Dessine la surface Σ r²/d² = 1 : les nucléons fondent les uns dans les autres comme des gouttes. */
    private void drawLiquid(List<double[]> balls, double heat, double alpha) {
        if (balls.isEmpty() || alpha <= 0.01) {
            return;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double meanR = 0;
        for (double[] b : balls) {
            minX = Math.min(minX, b[0] - b[2] * 1.6);
            maxX = Math.max(maxX, b[0] + b[2] * 1.6);
            minY = Math.min(minY, b[1] - b[2] * 1.6);
            maxY = Math.max(maxY, b[1] + b[2] * 1.6);
            meanR += b[2];
        }
        meanR /= balls.size();
        double size = Math.max(maxX - minX, maxY - minY);
        double cx = (minX + maxX) / 2;
        double cy = (minY + maxY) / 2;
        int n = 140;
        double step = size / n;
        if (liquid == null || (int) liquid.getWidth() != n) {
            liquid = new WritableImage(n, n);
        }
        PixelWriter writer = liquid.getPixelWriter();
        double lx = -0.48;
        double ly = -0.58;
        double lz = 0.66;
        Color hot = Color.web("#FFB86B");
        for (int j = 0; j < n; j++) {
            double y = cy - size / 2 + (j + 0.5) * step;
            for (int i = 0; i < n; i++) {
                double x = cx - size / 2 + (i + 0.5) * step;
                double f = 0;
                double fx = 0;
                double fy = 0;
                double cr = 0;
                double cg = 0;
                double cb = 0;
                for (double[] b : balls) {
                    double dx = x - b[0];
                    double dy = y - b[1];
                    double r2 = b[2] * b[2];
                    double d2 = dx * dx + dy * dy + 0.02 * r2;
                    double w = r2 / d2;
                    f += w;
                    fx -= 2 * w * dx / d2;
                    fy -= 2 * w * dy / d2;
                    cr += w * b[3];
                    cg += w * b[4];
                    cb += w * b[5];
                }
                if (f < 0.6) {
                    writer.setArgb(i, j, 0);
                    continue;
                }
                double edge = Math.max(0, Math.min(1, (f - 0.78) / 0.32));
                edge = edge * edge * (3 - 2 * edge);
                double nx = -fx;
                double ny = -fy;
                double nz = 2.2 * f / meanR;
                double norm = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double diffuse = Math.max(0, (nx * lx + ny * ly + nz * lz) / norm);
                double spec = Math.pow(diffuse, 28) * 0.55;
                double rim = Math.exp(-Math.pow((f - 1.0) / 0.16, 2)) * 0.28;
                double red = cr / f;
                double green = cg / f;
                double blue = cb / f;
                red = red + (hot.getRed() - red) * heat * 0.55;
                green = green + (hot.getGreen() - green) * heat * 0.55;
                blue = blue + (hot.getBlue() - blue) * heat * 0.55;
                double core = Math.min(0.35, (f - 1) * 0.06) * heat;
                double shade = 0.32 + 0.78 * diffuse;
                red = Math.min(1, red * shade + spec + rim + core);
                green = Math.min(1, green * shade + spec + rim + core * 0.8);
                blue = Math.min(1, blue * shade + spec + rim + core * 0.5);
                int a = (int) Math.round(255 * Math.min(1, edge * alpha * 0.96));
                writer.setArgb(i, j, (a << 24) | ((int) (red * 255) << 16) | ((int) (green * 255) << 8) | (int) (blue * 255));
            }
        }
        gc.setImageSmoothing(true);
        glow(cx, cy, size * 0.65, hot, 0.25 * heat * alpha);
        gc.drawImage(liquid, cx - size / 2, cy - size / 2, size, size);
    }

    /** Étincelles incandescentes laissées par les produits rapides. */
    private void emitSparks(double x, double y, double z, double speedC, Color color) {
        int count = 1 + (int) Math.round(2 * Math.min(1, speedC * 5));
        for (int k = 0; k < count && sparks.size() < 900; k++) {
            double jitter = 2.5;
            sparks.add(new double[]{x + (random.nextDouble() - 0.5) * jitter, y + (random.nextDouble() - 0.5) * jitter,
                    z + (random.nextDouble() - 0.5) * jitter, (random.nextDouble() - 0.5) * 3, random.nextDouble() * 1.5,
                    (random.nextDouble() - 0.5) * 3, 0, 0.25 + random.nextDouble() * 0.25,
                    color.getRed(), color.getGreen(), color.getBlue()});
        }
    }

    private void stepSparks(double dt) {
        java.util.Iterator<double[]> it = sparks.iterator();
        while (it.hasNext()) {
            double[] s = it.next();
            s[6] += dt;
            if (s[6] > s[7]) {
                it.remove();
                continue;
            }
            s[0] += s[3] * dt;
            s[1] += s[4] * dt;
            s[2] += s[5] * dt;
        }
    }

    private void drawSparks() {
        for (double[] s : sparks) {
            double[] p = camera.project(s[0], s[1], s[2]);
            if (p == null) {
                continue;
            }
            double life = 1 - s[6] / s[7];
            Color base = Color.color(s[8], s[9], s[10]);
            Color color = Color.WHITE.interpolate(Color.web("#FDBA74"), 1 - life).interpolate(base, 0.25);
            double r = 0.8 + 2.2 * life;
            glow(p[0], p[1], r * 3, color, 0.35 * life);
            gc.setFill(color.deriveColor(0, 1, 1, 0.9 * life));
            gc.fillOval(p[0] - r / 2, p[1] - r / 2, r, r);
        }
    }

    /** Le sillage chaud d'un produit rapide : l'air ondule (ici, l'espace), en arcs derrière lui. */
    private void drawShimmer(double[] p, double[] from, double radius, double strength) {
        double dx = p[0] - from[0];
        double dy = p[1] - from[1];
        double len = Math.hypot(dx, dy);
        if (len < 4) {
            return;
        }
        double ux = dx / len;
        double uy = dy / len;
        for (int k = 1; k <= 4; k++) {
            double back = radius * (1.4 + 1.3 * k);
            double cx = p[0] - ux * back;
            double cy = p[1] - uy * back;
            double half = radius * (1.1 + 0.35 * k);
            double wobble = Math.sin(clock * 18 + k * 1.9) * 3;
            gc.setStroke(Color.web("#E0F2FE", 0.22 * strength / k));
            gc.setLineWidth(1.4);
            gc.strokeLine(cx - uy * half + ux * wobble, cy + ux * half + uy * wobble,
                    cx + uy * half - ux * wobble, cy - ux * half - uy * wobble);
        }
    }

    /** Le champ magnétique total au point (scène) : somme des dipôles présents, B ∝ (3(m·r̂)r̂ − m)/r³. */
    private double[] magneticAt(double x, double y, double z, List<double[]> dipoles) {
        double bx = 0;
        double by = 0;
        double bz = 0;
        for (double[] d : dipoles) {
            double rx = x - d[0];
            double ry = y - d[1];
            double rz = z - d[2];
            double r2 = rx * rx + ry * ry + rz * rz;
            double r = Math.sqrt(r2);
            if (r < 0.25 * d[6]) {
                continue;
            }
            double mr = (d[3] * rx + d[4] * ry + d[5] * rz) / r;
            double r3 = r2 * r;
            bx += (3 * mr * rx / r - d[3]) / r3;
            by += (3 * mr * ry / r - d[4]) / r3;
            bz += (3 * mr * rz / r - d[5]) / r3;
        }
        return new double[]{bx, by, bz};
    }

    /** Bleu au sol plat, orange puis rouge vers le sommet de la colline, vert dans le puits de la force forte. */
    private static Color heightColor(double h) {
        if (h < -0.02) {
            return Color.web("#0B2A3A").interpolate(Color.web("#10B981"), Math.min(1, -h * 3));
        }
        if (h < 0.25) {
            return Color.web("#0A1630").interpolate(Color.web("#1D4ED8"), h / 0.25);
        }
        if (h < 0.65) {
            return Color.web("#1D4ED8").interpolate(Color.web("#F97316"), (h - 0.25) / 0.4);
        }
        return Color.web("#F97316").interpolate(Color.web("#EF4444"), Math.min(1, (h - 0.65) / 0.35));
    }

    /** Le champ magnétique du spin : des boucles autour de l'axe, d'autant plus marquées que le moment est fort. */
    private void drawMagnetic(double[] p, double screen, double moment, Color color) {
        double strength = Math.min(1, Math.abs(moment) / 3);
        if (strength < 0.02) {
            return;
        }
        gc.setStroke(Color.web("#67E8F9").deriveColor(0, 1, 1, 0.18 + 0.35 * strength));
        gc.setLineWidth(1);
        for (int k = 1; k <= 3; k++) {
            double w = screen * (0.9 + 0.55 * k);
            double h = screen * (1.6 + 0.8 * k);
            double wobble = Math.sin(clock * 1.5 + k) * 0.05;
            gc.strokeOval(p[0] - w * (1 + wobble), p[1] - h / 2, w * 0.7, h);
            gc.strokeOval(p[0] + w * (0.3 - wobble), p[1] - h / 2, w * 0.7, h);
        }
    }

    // ---------------------------------------------------------------- les forces, chiffrées
    private static final double MEV_PER_FM_IN_N = 160.2176634;   // 1 MeV/fm en newtons
    private static final double MU_N = 5.0507837461e-27;          // magnéton nucléaire, J/T
    private static final double MU_0 = 1.25663706212e-6;
    private static final double G = 6.67430e-11;
    private static final double U_KG = 1.66053906660e-27;

    /** Répulsion électrique (N) à r : sphères chargées uniformément. */
    private double coulombForce(double r) {
        double zz = state.getA().getZ() * state.getB().getZ();
        double contact = state.getContactFm();
        double mevPerFm = r >= contact ? 1.43996 * zz / (r * r) : 1.43996 * zz * r / Math.pow(contact, 3);
        return mevPerFm * MEV_PER_FM_IN_N;
    }

    /** Attraction nucléaire (N) à r : dérivée du potentiel de Woods-Saxon. */
    private double strongForce(double r) {
        double a = Math.max(state.getDiffusenessFm(), 0.1);
        double e = Math.exp(Math.min(60, (r - state.getContactFm()) / a));
        return state.getNuclearDepthMev() * e / (a * (1 + e) * (1 + e)) * MEV_PER_FM_IN_N;
    }

    /** Force magnétique entre les deux moments (spins alignés, au maximum) : 3μ₀μ₁μ₂/(2πr⁴). */
    private double magneticForce(double r) {
        double m1 = Math.abs(state.getA().getMagneticMoment()) * MU_N;
        double m2 = Math.abs(state.getB().getMagneticMoment()) * MU_N;
        double rm = Math.max(r, 0.5) * 1e-15;
        return 3 * MU_0 * m1 * m2 / (2 * Math.PI * Math.pow(rm, 4));
    }

    /** Force de Lorentz sur A (N) : q·v·B, B = champ du dipôle de B dans son plan équatorial. */
    private double lorentzForce(double r, double speedC) {
        double mb = Math.abs(state.getB().getMagneticMoment()) * MU_N;
        double rm = Math.max(r, state.getContactFm()) * 1e-15;
        double field = 1e-7 * mb / (rm * rm * rm);
        return state.getA().getZ() * 1.602176634e-19 * speedC * 299792458.0 * field;
    }

    private double gravityForce(double r) {
        double rm = Math.max(r, 0.5) * 1e-15;
        return G * state.getA().getMassU() * U_KG * state.getB().getMassU() * U_KG / (rm * rm);
    }

    /** Vitesse relative à la distance r (fraction de c) : l'énergie se conserve, E = ½μv² + V(r). */
    private double speedAt(double r, double energyMev) {
        double mu = Math.max(state.getReducedMassMev(), 1);
        return Math.sqrt(2 * Math.max(energyMev - potentialAt(r), 0) / mu);
    }

    // =====================================================================
    // Les champs qui résistent : bulles, lentille de répulsion, interface
    // =====================================================================
    private double presenceFrom = 1;
    private double scaleFrom = 1;
    private double[] aFrom;
    private double lastPresence = 1;
    private double lastScale = 1;
    private double[] lastAMap;

    /** Présence des champs de la rencontre : 1, puis ils s'effondrent dans la fusion ; ils se reforment au tir suivant. */
    private double fieldPresence() {
        if (state == null) {
            return 1;
        }
        double base = 1;
        if (shotPhase() >= 3 && (state.getFused() || state.getQuasiFission())) {
            double after = shotAge - IONIZE - TRAVEL;
            base = after <= 0 ? 1 : 1 - Visuals.ease(Math.min(1, after / DEFORM));
        }
        if (shotAge >= 0 && shotAge < 0.9) {
            base = presenceFrom + (base - presenceFrom) * Visuals.ease(shotAge / 0.9);
        }
        return base;
    }

    /** Fraction de l'énergie du tir changée en répulsion à la distance r : V(r)/E. */
    private double compression(double r) {
        if (state.getA().getZ() * state.getB().getZ() == 0) {
            return 0;
        }
        return Math.max(0, Math.min(1, potentialAt(r) / Math.max(shotEnergyMev(), 1e-9)));
    }

    /** Un cercle « bulle de savon » : aplati contre la bulle voisine (plan radical), à la distance x du centre. */
    private double[][] flattenedCircle(double cx, double cy, double radius, double ux, double uy, double cut) {
        int n = 96;
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            double px = Math.cos(a) * radius;
            double py = Math.sin(a) * radius;
            double s = px * ux + py * uy;
            if (s > cut) {
                px -= (s - cut) * ux;
                py -= (s - cut) * uy;
            }
            xs[i] = cx + px;
            ys[i] = cy + py;
        }
        return new double[][]{xs, ys};
    }

    private void drawFieldBubble(double cx, double cy, double radius, double ux, double uy, double cut, Color color,
                                 double presence, double pressure, int direction) {
        if (radius < 2) {
            return;
        }
        double[][] outline = flattenedCircle(cx, cy, radius, ux, uy, cut);
        gc.setFill(new RadialGradient(0, 0, cx, cy, radius, false, CycleMethod.NO_CYCLE,
                new Stop(0, color.deriveColor(0, 1, 1, 0.0)), new Stop(0.7, color.deriveColor(0, 1, 1, 0.05 * presence)),
                new Stop(1, color.deriveColor(0, 1, 1, 0.16 * presence))));
        gc.fillPolygon(outline[0], outline[1], outline[0].length);
        gc.setStroke(color.deriveColor(0, 1, 1, (0.25 + 0.3 * pressure) * presence));
        gc.setLineWidth(1.3);
        gc.strokePolygon(outline[0], outline[1], outline[0].length);
        // Les ondes du champ : elles partent du noyau ; contre la bulle voisine, elles s'entassent (compression).
        for (int i = 0; i < 5; i++) {
            double f = ((i + clock * 0.35 * direction) % 5 + 5) % 5 / 5;
            double rr = radius * (0.2 + 0.8 * f);
            double[][] ring = flattenedCircle(cx, cy, rr, ux, uy, Math.min(cut, rr));
            gc.setStroke(color.deriveColor(0, 1, 1, (1 - f) * 0.35 * presence));
            gc.setLineWidth(1);
            gc.strokePolygon(ring[0], ring[1], ring[0].length);
        }
    }

    /**
     * La barrière, comme une membrane : presque invisible au repos, elle s'illumine et se creuse seulement là où
     * le projectile appuie, d'autant plus que la répulsion a mangé son élan (V(r)/E). Le point neutre et le
     * point de demi-tour ne sont dessinés qu'en vue avancée ; leurs valeurs vont dans le panneau de messages.
     */
    private void drawFields(double[] pa, double[] pb, double sa, double sb, double r, boolean tunnelling) {
        double presence = fieldPresence();
        if (presence < 0.01) {
            return;
        }
        int za = state.getA().getZ();
        int zb = state.getB().getZ();
        boolean charged = za * zb > 0;
        double pressure = compression(r);
        double energy = shotEnergyMev();
        double[] rel = relativePosition();
        if (charged && state.getBarrierMev() > 0) {
            double globe = mapDistance(state.getBarrierRFm()) * presence;
            double ratio = state.getBarrierMev() / Math.max(energy, 1e-9);
            double[] center = camera.project(sceneB[0], sceneB[1], sceneB[2]);
            if (center != null) {
                // La silhouette : à peine visible.
                double screen = globe * center[3];
                gc.setStroke(Color.web("#7DD3FC", 0.10));
                gc.setLineWidth(1);
                gc.strokeOval(center[0] - screen, center[1] - screen, screen * 2, screen * 2);
            }
            // La membrane qui travaille : des anneaux autour du point d'appui, creusés et chauffés par la poussée.
            double[] axis = normalize(new double[]{sceneA[0] - sceneB[0], sceneA[1] - sceneB[1], sceneA[2] - sceneB[2]});
            double[] e1 = Math.abs(axis[1]) < 0.9 ? cross(axis, new double[]{0, 1, 0}) : cross(axis, new double[]{1, 0, 0});
            e1 = normalize(e1);
            double[] e2 = cross(axis, e1);
            double reach = Math.max(0, mapDistance(r) - radiusOf(state.getA().getA()));
            double push = reach < globe * 1.25 ? Math.min(1, (globe * 1.25 - reach) / (globe * 0.6)) : 0;
            double relax = shotPhase() == 2 ? Math.exp(-(shotAge - IONIZE - TRAVEL) * 1.5) : 1;
            double glowLevel = Math.max(push * 0.6, Math.pow(pressure, 1.5)) * presence * relax;
            double width = 0.55 + 0.25 * glowLevel;
            double depth = globe * 0.32 * Math.min(pressure, push + 0.2);
            Color hot = Color.web("#38BDF8").interpolate(Color.web("#F97316"), Math.min(1, glowLevel * 1.3));
            if (glowLevel > 0.8) {
                hot = hot.interpolate(Color.web("#FFF7ED"), (glowLevel - 0.8) / 0.2);
            }
            if (glowLevel > 0.02) {
                for (int k = 1; k <= 6; k++) {
                    double theta = k * 0.1;
                    double fall = Math.exp(-Math.pow(theta / width, 2));
                    double dent = depth * Math.exp(-Math.pow(theta / (width * 0.6), 2));
                    double rr = globe * Math.sin(theta);
                    double along = globe * Math.cos(theta) - dent;
                    double[] previous = null;
                    gc.setLineWidth(2.6);
                    for (int s = 0; s <= 48; s++) {
                        double phi = 2 * Math.PI * s / 48;
                        double x = sceneB[0] + axis[0] * along + rr * (Math.cos(phi) * e1[0] + Math.sin(phi) * e2[0]);
                        double y = sceneB[1] + axis[1] * along + rr * (Math.cos(phi) * e1[1] + Math.sin(phi) * e2[1]);
                        double z = sceneB[2] + axis[2] * along + rr * (Math.cos(phi) * e1[2] + Math.sin(phi) * e2[2]);
                        double[] p = camera.project(x, y, z);
                        if (p != null && previous != null) {
                            gc.setStroke(hot.deriveColor(0, 1, 1, Math.min(1, 0.5 * glowLevel * fall)));
                            gc.strokeLine(previous[0], previous[1], p[0], p[1]);
                        }
                        previous = p;
                    }
                }
                double[] tip = camera.project(sceneB[0] + axis[0] * (globe - depth), sceneB[1] + axis[1] * (globe - depth),
                        sceneB[2] + axis[2] * (globe - depth));
                if (tip != null) {
                    glow(tip[0], tip[1], 20 + 60 * glowLevel, hot, 0.25 + 0.55 * glowLevel);
                }
            }
            // L'effet tunnel : la membrane craque là où le noyau la traverse.
            if (tunnelling && center != null) {
                double dx = pa[0] - center[0];
                double dy = pa[1] - center[1];
                double norm = Math.max(Math.hypot(dx, dy), 1e-6);
                double gx = center[0] + dx / norm * globe * center[3];
                double gy = center[1] + dy / norm * globe * center[3];
                gc.setStroke(Color.web("#FFFFFF", 0.85));
                gc.setLineWidth(1.6);
                for (int c = 0; c < 9; c++) {
                    double a0 = c * 2 * Math.PI / 9 + 0.3;
                    double px = gx;
                    double py = gy;
                    for (int sgm = 1; sgm <= 4; sgm++) {
                        double len = 9 + 7 * sgm;
                        double a = a0 + Math.sin(c * 5 + sgm * 2.3) * 0.5;
                        double nx = gx + Math.cos(a) * len;
                        double ny = gy + Math.sin(a) * len;
                        gc.strokeLine(px, py, nx, ny);
                        px = nx;
                        py = ny;
                    }
                }
                glow(gx, gy, 40, Color.WHITE, 0.6);
            }
            note(I18n.text("Barrière de Coulomb : ") + energyText(state.getBarrierMev() * 1000) + (ratio > 1
                    ? " · " + Visuals.format(ratio) + I18n.text(" fois l'énergie de ce choc") : " · ce choc la dépasse"),
                    Color.web("#7DD3FC"));
        }
        // Le point neutre (vue avancée) : entre deux charges de même signe, le champ s'annule.
        if (charged && phaseOrIdle() && advanced()) {
            double share = Math.sqrt(zb) / (Math.sqrt(za) + Math.sqrt(zb));
            double nx = rel[0] * share;
            double ny = rel[1] * share;
            double contact = state.getContactFm();
            double vSaddle = 1.43996 * (za / Math.max(Math.hypot(nx - rel[0], ny - rel[1]), contact / 2)
                    + zb / Math.max(Math.hypot(nx, ny), contact / 2));
            double heat = Math.max(0, Math.min(1, vSaddle / Math.max(energy, 1e-9)));
            double[] scene = sceneAt(nx, ny);
            double[] w = lift(scene[0], 0, scene[1], tiltNow());
            double[] p = camera.project(w[0], w[1], w[2]);
            if (p != null) {
                Color color = Color.web("#7DD3FC").interpolate(Color.web("#FDBA74"), heat);
                glow(p[0], p[1], 14 + 30 * heat, color, 0.2 + 0.5 * heat);
                gc.setStroke(color.interpolate(Color.WHITE, 0.4));
                gc.setLineWidth(1.4);
                double s = 6 + 5 * heat;
                gc.strokeLine(p[0] - s, p[1] - s * 0.5, p[0] + s, p[1] + s * 0.5);
                gc.strokeLine(p[0] - s, p[1] + s * 0.5, p[0] + s, p[1] - s * 0.5);
            }
            note(I18n.text("Point neutre (✕) : les deux champs s'y annulent · V = ") + energyText(vSaddle * 1000), Color.web("#7DD3FC"));
        }
        // Le demi-tour de ce choc : un trait d'or sur l'axe, à la distance réelle où l'élan sera épuisé.
        double turning = shotTurningFm();
        if (charged && turning > 0 && shotPhase() < 2) {
            double world = Math.max(mapDistance(r), 1e-6);
            double turnWorld = mapDistance(turning);
            if (turnWorld < world * 0.98) {
                double dx = pa[0] - pb[0];
                double dy = pa[1] - pb[1];
                double length = Math.max(Math.hypot(dx, dy), 1e-6);
                double f = turnWorld / world;
                double mx = pb[0] + dx * f;
                double my = pb[1] + dy * f;
                double vx = -dy / length;
                double vy = dx / length;
                gc.setStroke(GOLD.deriveColor(0, 1, 1, 0.75 * presence));
                gc.setLineWidth(2.5);
                gc.strokeLine(mx - vx * 12, my - vy * 12, mx + vx * 12, my + vy * 12);
                note(I18n.text("Trait d'or : son élan sera épuisé à ") + Visuals.format(turning) + " fm (demi-tour)", GOLD);
            }
        }
    }

    private double sceneBy;

    private boolean phaseOrIdle() {
        return shotPhase() < 3;
    }

    private void globeRing(double radius, double y, boolean horizontal, Color color, double alpha) {
        double[] previous = null;
        for (int s = 0; s <= 72; s++) {
            double a = 2 * Math.PI * s / 72;
            double[] p = camera.project(bMap[0] + radius * Math.cos(a), y, bMap[1] + radius * Math.sin(a));
            if (p != null && previous != null) {
                gc.setStroke(color.deriveColor(0, 1, 1, alpha));
                gc.strokeLine(previous[0], previous[1], p[0], p[1]);
            }
            previous = p;
        }
    }

    private void globeMeridian(double radius, double cy, double phi, Color color, double alpha) {
        double[] previous = null;
        for (int s = 0; s <= 48; s++) {
            double a = Math.PI * s / 48 - Math.PI / 2;
            double x = bMap[0] + radius * Math.cos(a) * Math.cos(phi);
            double z = bMap[1] + radius * Math.cos(a) * Math.sin(phi);
            double[] p = camera.project(x, cy + radius * Math.sin(a), z);
            if (p != null && previous != null) {
                gc.setStroke(color.deriveColor(0, 1, 1, alpha));
                gc.strokeLine(previous[0], previous[1], p[0], p[1]);
            }
            previous = p;
        }
    }

    /** L'effondrement : au franchissement, la lentille s'effondre vers l'intérieur et aspire le projectile. */
    private void drawCollapse(double[] center, double t) {
        if (state.getBarrierMev() <= 0) {
            return;
        }
        double lens = mapDistance(state.getBarrierRFm()) * center[3];
        double radius = lens * (1 - Visuals.ease(t));
        gc.setStroke(Color.web("#FDBA74", 0.8 * (1 - t)));
        gc.setLineWidth(2 + 3 * t);
        gc.strokeOval(center[0] - radius, center[1] - radius, radius * 2, radius * 2);
        glow(center[0], center[1], lens * (0.5 + t), Color.web("#FDE68A"), 0.5 * t);
    }

    /** L'aura du nouveau noyau : son champ se déploie, ondes et charge. */
    private void drawAura(double[] center, double grow, Color color, int z) {
        if (grow <= 0) {
            return;
        }
        double radius = SPAN * 0.42 * Math.sqrt(Math.max(1, z) / 10.0 + 0.3) * center[3] * Visuals.ease(grow);
        drawFieldBubble(center[0], center[1], radius, 1, 0, radius, color, Math.min(1, grow * 1.5), 0, 1);
    }

    /** Couple d'alignement : le moment magnétique de A dans le champ de B (sur l'axe), en N·m. */
    private double alignmentTorque(double r) {
        double ma = Math.abs(state.getA().getMagneticMoment()) * MU_N;
        double mb = Math.abs(state.getB().getMagneticMoment()) * MU_N;
        double rm = Math.max(r, 0.5) * 1e-15;
        double field = MU_0 / (4 * Math.PI) * 2 * mb / Math.pow(rm, 3);
        return ma * field;
    }

    /** Les mesures de résistance, maintenant : compression, élan, effet tunnel, forces, alignement des spins. */
    private void drawForcesPanel(double r) {
        double x = 22;
        double y = 124;
        double w = 304;
        double h = 482;
        gc.setFill(Color.web("#0A0F1C", 0.84));
        gc.fillRoundRect(x, y, w, h, 14, 14);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 10));
        gc.setFill(SOFT);
        I18n.fillText(gc, I18n.text("MESURES DE RÉSISTANCE (r = ") + (r >= 100 ? Visuals.format(r) : Visuals.fixed(r, 1)) + " fm)",
                x + 14, y + 20);
        double pressure = compression(r);
        double energy = shotEnergyMev();
        double speed = speedAt(r, energy);
        double tunnel = state.getTunneling();
        double row = y + 44;
        boolean inWell = potentialAt(r) < 0;
        row = measure(x, row, w, "Compression du champ", Visuals.format(100 * pressure) + " %", pressure, REPULSION,
                inWell ? "dans le puits : plus de répulsion à vaincre" : "part de l'élan changée en répulsion");
        double kms = speed * 299792.458;
        row = measure(x, row, w, "Élan", (kms >= 1000 ? String.format(java.util.Locale.ROOT, "%,.0f", kms)
                .replace(',', ' ') : Visuals.format(kms)) + " km/s", Math.min(1, 1 - pressure), GOLD,
                inWell ? "la force forte l'aspire : il accélère vers le centre" : "il ralentit en poussant contre le champ");
        double logTunnel = tunnel > 0 ? Math.max(0, 1 + Math.log10(tunnel) / 30) : 0;
        row = measure(x, row, w, "Passage par effet tunnel", tunnel >= 0.5 ? Visuals.format(100 * tunnel) + " %"
                : tunnel > 0 ? I18n.text("1 sur ") + NucleusPanel.scientific(1 / tunnel) : "0", logTunnel, TUNNEL,
                "chance de traverser le bouclier à chaque tir");
        gc.setFont(Font.font(UI, FontWeight.BOLD, 10));
        gc.setFill(SOFT);
        I18n.fillText(gc, "FORCES ENTRE LES DEUX NOYAUX", x + 14, row + 2);
        row += 20;
        double[] values = {coulombForce(r), strongForce(r), lorentzForce(r, speed), magneticForce(r), gravityForce(r)};
        String[] names = {"Répulsion électrique", "Force forte", "Lorentz q·v×B", "Magnétique spin–spin", "Gravité"};
        Color[] colors = {REPULSION, STRONG, Color.web("#67E8F9"), Color.web("#A5F3FC"), Color.web("#A78BFA")};
        for (int i = 0; i < 5; i++) {
            gc.setFont(Font.font(UI, FontWeight.BOLD, 11.5));
            gc.setFill(colors[i]);
            gc.setTextAlign(TextAlignment.LEFT);
            I18n.fillText(gc, names[i], x + 14, row);
            gc.setFont(Font.font(MONO, FontWeight.BOLD, 11.5));
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setFill(TEXT);
            I18n.fillText(gc, forceText(values[i]), x + w - 14, row);
            double t = values[i] > 0 ? Math.max(0, Math.min(1, (Math.log10(values[i]) + 40) / 44)) : 0;
            gc.setFill(Color.web("#1A2238"));
            gc.fillRoundRect(x + 14, row + 5, w - 28, 5, 3, 3);
            gc.setFill(colors[i]);
            gc.fillRoundRect(x + 14, row + 5, Math.max(3, (w - 28) * t), 5, 3, 3);
            if (i >= 2 && values[0] > 0) {
                double ratio = values[i] / values[0];
                gc.setTextAlign(TextAlignment.LEFT);
                gc.setFont(Font.font(UI, 10));
                gc.setFill(MUTED);
                I18n.fillText(gc, NucleusPanel.scientific(ratio) + I18n.text(" × la répulsion")
                        + (ratio < 1e-3 ? " · négligeable, aucun effet sur la fusion" : ""), x + 14, row + 21);
                row += 12;
            }
            row += 25;
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 11.5));
        gc.setFill(Color.web("#67E8F9"));
        I18n.fillText(gc, "Précession du spin (Bloch)", x + 14, row + 4);
        gc.setFont(Font.font(MONO, FontWeight.BOLD, 11.5));
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFill(TEXT);
        boolean shot = state.getFired() && shotPhase() >= 1;
        I18n.fillText(gc, shot ? NucleusPanel.scientific(state.getPrecessionDeg() * precessionFraction()) + "°" : "—",
                x + w - 14, row + 4);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, 10));
        gc.setFill(MUTED);
        I18n.fillText(gc, shot && state.getLorentzDeflectionFm() > 0
                ? I18n.text("trajectoire déviée de ") + NucleusPanel.scientific(state.getLorentzDeflectionFm()) + I18n.text(" fm par q·v×B")
                : "le spin de A tourne autour du champ de B", x + 14, row + 19);
    }

    private double measure(double x, double y, double w, String name, String value, double fill, Color color, String note) {
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
        gc.setFill(color);
        I18n.fillText(gc, name, x + 14, y);
        gc.setFont(Font.font(MONO, FontWeight.BOLD, 12));
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFill(TEXT);
        I18n.fillText(gc, value, x + w - 14, y);
        gc.setFill(Color.web("#1A2238"));
        gc.fillRoundRect(x + 14, y + 7, w - 28, 7, 4, 4);
        gc.setFill(color);
        gc.fillRoundRect(x + 14, y + 7, Math.max(3, (w - 28) * Math.max(0, Math.min(1, fill))), 7, 4, 4);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, 10));
        gc.setFill(MUTED);
        I18n.fillText(gc, note, x + 14, y + 27);
        return y + 44;
    }

    private static String forceText(double newtons) {
        if (newtons <= 0) {
            return "0 N";
        }
        return (newtons >= 0.1 && newtons < 1e5 ? Visuals.format(newtons) : NucleusPanel.scientific(newtons)) + " N";
    }

    private static String ratioText(double ratio) {
        if (ratio >= 1.5) {
            return Visuals.format(ratio) + I18n.text(" × plus que");
        }
        if (ratio >= 0.67) {
            return "autant que";
        }
        return NucleusPanel.scientific(ratio) + " ×";
    }

    /** Les forces opposées, en flèches : l'élan de A contre la répulsion ; B repoussé d'autant (action-réaction). */
    private void drawForceArrows(double[] pa, double[] pb, double sa, double sb, double r, double[] rel, boolean approaching) {
        double dx = pb[0] - pa[0];
        double dy = pb[1] - pa[1];
        double length = Math.hypot(dx, dy);
        if (length < 1) {
            return;
        }
        double ux = dx / length;
        double uy = dy / length;
        double energyMev = shotEnergyMev();
        double speed = speedAt(r, energyMev);
        double start = speedAt(startFm, energyMev);
        // Élan : vers B s'il approche, vers l'arrière s'il repart.
        double elan = start > 0 ? Math.min(110, 25 + 70 * speed / start) : 0;
        double sign = approaching ? 1 : -1;
        if (elan > 26) {
            arrow(pa[0] + sign * ux * (sa + 4), pa[1] + sign * uy * (sa + 4), sign * ux, sign * uy, elan, GOLD, 5);
            if (advanced()) {
                gc.setFont(Font.font(UI, FontWeight.BOLD, 11));
                gc.setFill(GOLD);
                gc.setTextAlign(TextAlignment.CENTER);
                double kms = speed * 299792.458;
                String v = kms >= 1000 ? String.format(java.util.Locale.ROOT, "%,.0f", kms).replace(',', ' ')
                        : Visuals.format(kms);
                I18n.fillText(gc, I18n.text("élan · v = ") + v + " km/s",
                        pa[0] + sign * ux * (sa + elan * 0.6), pa[1] + sign * uy * (sa + elan * 0.6) - 14);
            }
        }
        double coulomb = coulombForce(r);
        double strong = strongForce(r);
        if (coulomb > 0) {
            double size = Math.min(110, 18 + 9 * Math.max(0, Math.log10(coulomb / 1e-5)));
            arrow(pa[0] - ux * (sa + 4), pa[1] - uy * (sa + 4), -ux, -uy, size, REPULSION, 4);
            arrow(pb[0] + ux * (sb + 4), pb[1] + uy * (sb + 4), ux, uy, size, REPULSION, 4);
        }
        if (strong > coulomb * 0.02) {
            double size = Math.min(110, 18 + 9 * Math.max(0, Math.log10(strong / 1e-5)));
            arrow(pa[0] + ux * (sa + 2), pa[1] + uy * (sa + 2) + 10, ux, uy, size * 0.6, STRONG, 4);
            arrow(pb[0] - ux * (sb + 2), pb[1] - uy * (sb + 2) + 10, -ux, -uy, size * 0.6, STRONG, 4);
            glow((pa[0] + pb[0]) / 2, (pa[1] + pb[1]) / 2, 50, STRONG, Math.min(0.8, strong / Math.max(coulomb, 1e-9) * 0.3));
        }
    }

    private void arrow(double x, double y, double ux, double uy, double length, Color color, double width) {
        double tx = x + ux * length;
        double ty = y + uy * length;
        gc.setStroke(color.deriveColor(0, 1, 1, 0.35));
        gc.setLineWidth(width + 4);
        gc.strokeLine(x, y, tx, ty);
        gc.setStroke(color);
        gc.setLineWidth(width);
        gc.strokeLine(x, y, tx - ux * 6, ty - uy * 6);
        gc.setFill(color);
        double s = width * 2.6;
        gc.fillPolygon(new double[]{tx + ux * 2, tx - ux * s - uy * s * 0.6, tx - ux * s + uy * s * 0.6},
                new double[]{ty + uy * 2, ty - uy * s + ux * s * 0.6, ty - uy * s - ux * s * 0.6}, 3);
    }

    /** Les repères : sommet de la colline, ton énergie, puits de la force forte. */
    private void drawLandmarks() {
        if (state != null) {
            return;                     // les repères sont désormais portés par la lentille et la bulle
        }
        if (terrainScale() < 0.9) {
            return;
        }
        if (state.getBarrierMev() > 0) {
            ring(state.getBarrierRFm(), Color.web("#FB923C"), 0.5, false);
            labelAt(state.getBarrierRFm(), true, I18n.text("sommet de la colline · ") + energyText(state.getBarrierMev() * 1000),
                    Color.web("#FDBA74"));
        }
        double e = state.getEnergyKev() / 1000;
        boolean below = state.getBarrierMev() > 0 && e < state.getBarrierMev() && state.getTurningFm() > 0;
        if (below && shotPhase() < 3) {
            ring(state.getTurningFm(), GOLD, 0.85, true);
            labelAt(state.getTurningFm(), false, "ton énergie s'arrête ici", GOLD);
        }
        // Le puits de la force forte : un creux vert au centre.
        double[] center = camera.project(B_X, hillHeight(0.05), 0);
        if (center != null) {
            glow(center[0], center[1], 26 * center[3] * 4, STRONG, 0.18);
        }
    }

    private void ring(double r, Color color, double alpha, boolean dashed) {
        double d = mapDistance(r);
        double h = hillHeight(r) + 0.5;
        double[] previous = null;
        gc.setLineWidth(dashed ? 2 : 1.4);
        if (dashed) {
            gc.setLineDashes(6, 6);
        }
        for (int s = 0; s <= 96; s++) {
            double a = 2 * Math.PI * s / 96;
            double[] p = camera.project(B_X + d * Math.cos(a), h, d * Math.sin(a));
            if (p != null && previous != null) {
                gc.setStroke(color.deriveColor(0, 1, 1, alpha));
                gc.strokeLine(previous[0], previous[1], p[0], p[1]);
            }
            previous = p;
        }
        gc.setLineDashes((double[]) null);
    }

    private void labelAt(double r, boolean top, String text, Color color) {
        double d = mapDistance(r);
        double angle = top ? -Math.PI / 2 : Math.PI / 2;        // derrière B (haut de l'écran), ou devant
        double[] p = camera.project(B_X + d * Math.cos(angle), hillHeight(r) + 1, d * Math.sin(angle));
        if (p != null) {
            tag(p[0], p[1] - 14, text, color, 11);
        }
    }

    // ---------------------------------------------------------------- l'expérience
    private void drawExperiment(double width, double height) {
        LabNucleus a = state.getA();
        LabNucleus b = state.getB();
        double ra = radiusOf(a.getA());
        double rb = radiusOf(b.getA());
        int phase = shotPhase();
        double[] rel = relativePosition();
        double r = Math.max(Math.hypot(rel[0], rel[1]), 1e-6);
        double angle = tiltNow();
        sceneBy = 0;
        sceneA = lift(aMapNow[0], 0, aMapNow[1], angle);
        sceneB = lift(bMap[0], 0, bMap[1], angle);
        double ax = sceneA[0];
        double ay = sceneA[1];
        double az = sceneA[2];
        double by = sceneBy;
        double plasma = state.getEnergyKev() * 1000 > Math.max(a.getIonizationEv(), b.getIonizationEv()) ? 1 : 0;
        double ionized = Math.max(plasma, shotAge < 0 ? 0 : Math.min(1, shotAge / IONIZE));
        double after = shotAge - IONIZE - TRAVEL;
        boolean contact = state.getFired() && (state.getFused() || state.getQuasiFission());

        if (phase >= 3 && contact) {
            lastA = camera.project(ax, ay, az);
            lastB = camera.project(sceneB[0], sceneB[1], sceneB[2]);
            prevSceneA = null;
            drawFusion(after, by, ra, rb);
            if (!resultShown && advanced()) {
                drawForcesPanel(state.getContactFm());
            }
            return;
        }
        double[] pa = camera.project(ax, ay, az);
        double[] pb = camera.project(sceneB[0], sceneB[1], sceneB[2]);
        if (pa == null || pb == null) {
            return;
        }
        boolean tunnelling = inTunnel();
        boolean coming = approaching();
        double pressure = compression(r);
        double resist = phase < 1 ? 0 : tunnelling ? 0.4 : pressure * (coming ? 1 : 0.6);
        double energyMev = shotEnergyMev();
        double speedRatio = speedAt(r, energyMev) / Math.max(speedAt(startFm, energyMev), 1e-12);
        double screenAngle = Math.atan2(pb[1] - pa[1], pb[0] - pa[0]);
        // La résistance : A force, il tremble (quelques pixels), d'autant plus que la compression est forte.
        double shake = (phase >= 1 ? 4.5 : 0) * Math.pow(resist, 1.5);
        pa = pa.clone();
        pa[0] += shake * Math.sin(clock * 83);
        pa[1] += shake * Math.cos(clock * 71);
        drawFields(pa, pb, ra * pa[3], rb * pb[3], r, tunnelling);
        // Les deux dipôles magnétiques : leurs lignes sont tracées dans le champ total.
        frameDipoles = new ArrayList<>();
        for (int k = 0; k < 2; k++) {
            LabNucleus nucleus = k == 0 ? a : b;
            double[] at = k == 0 ? sceneA : sceneB;
            double[] axis = spinAxis(k == 0);
            double mu = nucleus.getMagneticMoment();
            if (Math.abs(mu) > 1e-3) {
                frameDipoles.add(new double[]{at[0], at[1], at[2], axis[0] * mu, axis[1] * mu, axis[2] * mu,
                        k == 0 ? ra : rb});
            }
        }
        // Le flou de mouvement : quelques copies pâles le long de sa vitesse réelle, mesurée d'une image à l'autre.
        // Immobile, il n'en laisse aucune (pas de queue figée).
        if (prevSceneA != null && frameDt > 0) {
            for (int k = 0; k < 3; k++) {
                velA[k] += ((sceneA[k] - prevSceneA[k]) / frameDt - velA[k]) * 0.35;
            }
        } else {
            velA[0] = 0;
            velA[1] = 0;
            velA[2] = 0;
        }
        prevSceneA = sceneA.clone();
        double moving = Math.sqrt(velA[0] * velA[0] + velA[1] * velA[1] + velA[2] * velA[2]);
        if (moving * 0.1 > ra * 0.3) {
            Color trail = sideColor(true);
            for (int k = 5; k >= 1; k--) {
                double back = 0.022 * k;
                double[] q = camera.project(ax - velA[0] * back, ay - velA[1] * back, az - velA[2] * back);
                if (q != null) {
                    double size = ra * q[3] * (1 - 0.08 * k);
                    gc.setFill(trail.deriveColor(0, 1, 1, 0.15 * (1 - k / 6.0)));
                    gc.fillOval(q[0] - size, q[1] - size, size * 2, size * 2);
                }
            }
        }
        // L'aura de A : son champ, comprimé par celui de B. Bleue au repos, elle rougit quand la répulsion mange
        // son élan, blanchit près du demi-tour ; violette quand il passe par effet tunnel. Plus besoin de jauge.
        if (state.getA().getZ() * state.getB().getZ() > 0) {
            Color aura = tunnelling ? TUNNEL
                    : Color.web("#38BDF8").interpolate(Color.web("#EF4444"), Math.min(1, pressure * 1.15));
            if (!tunnelling && pressure > 0.85) {
                aura = aura.interpolate(Color.web("#FFF1C1"), (pressure - 0.85) / 0.15);
            }
            double level = Math.max(pressure, tunnelling ? 0.6 : 0);
            double auraR = ra * pa[3] * (2.0 + 1.1 * level + 0.12 * level * Math.sin(clock * (5 + 14 * level)));
            glow(pa[0], pa[1], auraR, aura, 0.12 + 0.42 * level);
            if (level > 0.04) {
                // Le côté qui pousse contre B : un croissant lumineux, d'autant plus épais que la poussée est forte.
                double rim = ra * pa[3] * 1.4;
                gc.setStroke(aura.deriveColor(0, 1, 1, Math.min(1, 0.25 + 0.7 * level)));
                gc.setLineWidth(1.5 + 3.5 * level);
                gc.strokeArc(pa[0] - rim, pa[1] - rim, rim * 2, rim * 2, -Math.toDegrees(screenAngle) - 55, 110,
                        javafx.scene.shape.ArcType.OPEN);
            }
        }
        boolean inWell = potentialAt(r) < 0;
        // Le plus lointain d'abord.
        // Déformation : écrasé contre la résistance, étiré quand la force forte l'aspire.
        double stretchA = 1;            // les noyaux restent ronds : c'est l'intérieur qui encaisse
        double stretchB = 1;
        if (pa[2] > pb[2]) {
            drawStressedBall(pa, ra, a, ionized, tunnelling ? 0.45 : 1, true, stretchA, -screenAngle, resist);
            drawStressedBall(pb, rb, b, ionized, bAlpha(), false, stretchB, -screenAngle, resist * 0.8);
        } else {
            drawStressedBall(pb, rb, b, ionized, bAlpha(), false, stretchB, -screenAngle, resist * 0.8);
            drawStressedBall(pa, ra, a, ionized, tunnelling ? 0.45 : 1, true, stretchA, -screenAngle, resist);
        }
        frameDipoles = null;
        drawForceArrows(pa, pb, ra * pa[3], rb * pb[3], r, rel, approaching());
        if (advanced()) {
            drawDistance(ra, rb, r);
            drawForcesPanel(r);
            drawCoulomb(width, r);
        } else {
            drawEssentialPanel(r);
        }
        drawBanner(width, height, phase, tunnelling);
    }

    /** Une sphère lumineuse : couleur de l'élément, halo, et tant qu'il en a, son nuage d'électrons. */
    private Color sideColor(boolean isA) {
        Color a = brighten(state.getA().getColor());
        Color b = brighten(state.getB().getColor());
        double gap = Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen())
                + Math.abs(a.getBlue() - b.getBlue());
        if (gap < 0.35) {
            // Même couleur d'élément : on distingue A (chaud) et B (froid), comme sur un schéma.
            return isA ? a.interpolate(Color.web("#FB7185"), 0.65) : b.interpolate(Color.web("#38BDF8"), 0.65);
        }
        return isA ? a : b;
    }

    private void drawStressedBall(double[] p, double radius, LabNucleus nucleus, double ionized, double alpha,
                                  boolean isA, double stretch, double angle, double stress) {
        heatNow = Math.max(0, Math.min(1, stress));
        waveNow = heatNow;
        ballStretch = stretch;
        ballAngle = angle;
        ballJitter = 0.6 * heatNow;
        drawBall(p, radius, nucleus, ionized, alpha, isA);
        heatNow = 0;
        waveNow = 0;
        ballStretch = 1;
        ballAngle = 0;
        ballJitter = 0;
    }

    private void drawBall(double[] p, double radius, LabNucleus nucleus, double ionized, double alpha, boolean isA) {
        double screen = radius * p[3];
        Color color = sideColor(isA);
        gc.setGlobalAlpha(alpha);
        // Le nuage d'électrons : un halo de gaz et de poussière, soufflé au tir.
        if (ionized < 1) {
            double cloud = screen * (2.3 + 1.6 * ionized);
            gc.setGlobalAlpha(alpha * (1 - ionized));
            gc.setFill(new RadialGradient(0, 0, p[0], p[1], cloud, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.web("#7DD3FC", 0.0)), new Stop(0.45, Color.web("#7DD3FC", 0.16)),
                    new Stop(1, Color.web("#7DD3FC", 0))));
            gc.fillOval(p[0] - cloud, p[1] - cloud, cloud * 2, cloud * 2);
            gc.setFill(Color.web("#BAE6FD"));
            for (double[] dust : this.dust) {
                double angle = dust[0] + clock * dust[3] * (isA ? 1 : -1);
                double rr = screen * dust[1] * (1 + ionized * 3);
                double x = p[0] + rr * Math.cos(angle);
                double y = p[1] + rr * Math.sin(angle) * (0.35 + 0.3 * Math.abs(dust[2]));
                gc.fillOval(x - 1.1, y - 1.1, 2.2, 2.2);
            }
            gc.setGlobalAlpha(alpha);
        }
        double[] at = isA ? sceneA : sceneB;
        drawGlassNucleus(p, radius, at[0], at[1], at[2], nucleus, color, alpha, isA);
        gc.setGlobalAlpha(alpha);
        // Le nom et la charge, discrets : « D  +1e ».
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
        gc.setFill(color.deriveColor(0, 1, 1, 0.9));
        I18n.fillText(gc, nucleus.getLabel() + "  +" + nucleus.getZ() + "e", p[0], p[1] - screen * 1.4 - 12);
        gc.setGlobalAlpha(1);
    }

    private static Color brighten(String hex) {
        Color color = Color.web(hex.isEmpty() ? "#94A3B8" : hex);
        double light = 0.3 * color.getRed() + 0.59 * color.getGreen() + 0.11 * color.getBlue();
        return light < 0.55 ? color.interpolate(Color.WHITE, 0.55 - light) : color;
    }

    private double spinTiltA;
    private double[] sceneA = {0, 0, 0};
    private double[] sceneB = {0, 0, 0};
    private IntConsumer onThermal = value -> { };
    private final javafx.scene.control.ToggleButton thermalToggle = new javafx.scene.control.ToggleButton();
    private final Label energyTitle = new Label("TEMPÉRATURE DU PLASMA");

    public void setOnThermal(IntConsumer consumer) {
        onThermal = consumer;
    }

    private double bAlpha() {
        return fadeInB && shotAge >= 0 && shotAge < 0.9 ? Visuals.ease(shotAge / 0.9) : 1;
    }

    /** Le spin de A précesse dans le champ magnétique de B : il tourne autour d'un cône en cherchant à s'aligner. */
    private void drawPrecession(double x, double y, double radius, String spinParity, double tilt) {
        if (spinValue(spinParity) <= 0) {
            return;
        }
        double length = radius * 1.7;
        double phase = clock * 2.6;
        double topX = x + length * Math.sin(tilt) * Math.cos(phase);
        double topY = y - length * Math.cos(tilt) + length * Math.sin(tilt) * Math.sin(phase) * 0.3;
        double bottomX = x - (topX - x) * 0.75;
        double bottomY = y + (y - topY) * 0.75;
        gc.setStroke(Color.web("#67E8F9", 0.6));
        gc.setLineWidth(1.1);
        gc.setLineDashes(3, 4);
        double coneX = length * Math.sin(tilt);
        gc.strokeOval(x - coneX, y - length * Math.cos(tilt) - coneX * 0.3, coneX * 2, coneX * 0.6);
        gc.setLineDashes((double[]) null);
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2.2);
        gc.strokeLine(bottomX, bottomY, topX, topY);
        double angle = Math.atan2(topY - bottomY, topX - bottomX);
        gc.setFill(Color.WHITE);
        gc.fillPolygon(new double[]{topX + 6 * Math.cos(angle), topX + 5 * Math.cos(angle + 2.4), topX + 5 * Math.cos(angle - 2.4)},
                new double[]{topY + 6 * Math.sin(angle), topY + 5 * Math.sin(angle + 2.4), topY + 5 * Math.sin(angle - 2.4)}, 3);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 10.5));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(Color.web("#A5F3FC"));
        I18n.fillText(gc, "spin " + spinParity.replace("+", "").replace("-", "") + I18n.text(" · précession"), x + radius * 1.4,
                y - length * 0.8);
    }

    /** Le spin : une flèche qui traverse la sphère, et le sens de rotation autour. */
    private void drawSpin(double x, double y, double radius, String spinParity) {
        double spin = spinValue(spinParity);
        if (spin <= 0) {
            return;
        }
        double length = radius * 1.7;
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2.2);
        gc.strokeLine(x, y + length * 0.75, x, y - length);
        gc.setFill(Color.WHITE);
        gc.fillPolygon(new double[]{x - 5.5, x + 5.5, x}, new double[]{y - length + 8, y - length + 8, y - length - 4}, 3);
        gc.setStroke(Color.web("#FFFFFF", 0.8));
        gc.setLineWidth(1.4);
        gc.setLineDashes(4, 4);
        gc.setLineDashOffset(-clock * 14);
        gc.strokeArc(x - radius * 1.35, y - length * 0.78, radius * 2.7, radius * 0.6, 200, 300,
                javafx.scene.shape.ArcType.OPEN);
        gc.setLineDashes((double[]) null);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 10.5));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(Color.WHITE);
        I18n.fillText(gc, "spin " + spinParity.replace("+", "").replace("-", ""), x + radius * 1.4, y - length * 0.7);
    }

    static double spinValue(String spinParity) {
        String value = spinParity.replace("+", "").replace("-", "").replace("(", "").replace(")", "").trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            if (value.contains("/")) {
                String[] parts = value.split("/");
                return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
            }
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    /** La distance réelle entre les deux noyaux, comme une cote sur un plan. */
    private void drawDistance(double ra, double rb, double r) {
        double[] p1 = camera.project(sceneA[0], sceneA[1] - ra, sceneA[2]);
        double[] p2 = camera.project(sceneB[0], sceneB[1] - rb, sceneB[2]);
        if (p1 == null || p2 == null) {
            return;
        }
        double y = Math.max(p1[1], p2[1]) + 26;
        gc.setStroke(Color.web("#E2E8F0", 0.75));
        gc.setLineWidth(1.2);
        gc.strokeLine(p1[0], y, p2[0], y);
        gc.strokeLine(p1[0], y - 6, p1[0], y + 6);
        gc.strokeLine(p2[0], y - 6, p2[0], y + 6);
        String text = "r = " + (r >= 100 ? Visuals.format(r) : Visuals.fixed(r, 1)) + " fm";
        tag((p1[0] + p2[0]) / 2, y + 14, text, TEXT, 12);
    }

    /**
     * L'énergie du noyau lancé, au-dessus de lui : « mouvement » (or) et « répulsion » (rouge).
     * Leur somme reste celle du tir ; quand l'or est épuisé, il fait demi-tour.
     */
    private void drawEnergyBars(double[] pa, double ra, double r, boolean tunnelling) {
        double total = shotEnergyMev();
        double repulsion = Math.max(0, potentialAt(r));
        double width = 210;
        double x = pa[0] - width / 2;
        double y = pa[1] + ra * pa[3] * 1.6 + 14;
        double share = Math.min(1, repulsion / total);
        gc.setFill(Color.web("#0A0F1C", 0.85));
        gc.fillRoundRect(x - 8, y - 4, width + 16, 40, 10, 10);
        gc.setFill(GOLD);
        gc.fillRoundRect(x, y, width * (1 - share), 9, 4, 4);
        gc.setFill(tunnelling ? TUNNEL : REPULSION);
        gc.fillRoundRect(x + width * (1 - share), y, width * share, 9, 4, 4);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 10));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(GOLD);
        I18n.fillText(gc, "mouvement " + Visuals.format(100 * (1 - share)) + " %", x, y + 22);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFill(tunnelling ? TUNNEL : REPULSION);
        I18n.fillText(gc, tunnelling ? "effet tunnel" : I18n.text("répulsion ") + Visuals.format(100 * share) + " %", x + width, y + 22);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(Font.font(UI, 9.5));
        gc.setFill(SOFT);
        I18n.fillText(gc, (state.getThermal() && state.getFired() ? "énergie de ce choc (tirée au hasard) : "
                : "énergie du tir : ") + energyText(shotEnergyMev() * 1000), pa[0], y + 33);
    }

    /** La loi de Coulomb en grand, avec ses valeurs, et ce que devient la force quand la distance change. */
    private void drawCoulomb(double width, double r) {
        int za = state.getA().getZ();
        int zb = state.getB().getZ();
        double x = width / 2;
        double y = 148;
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(Font.font("Cambria Math", FontWeight.BOLD, 30));
        gc.setFill(Color.web("#FFFFFF", 0.12));
        I18n.fillText(gc, "F = k · q₁q₂ / r²", x + 2, y + 2);
        gc.setFill(TEXT);
        I18n.fillText(gc, "F = k · q₁q₂ / r²", x, y);
        if (za * zb == 0) {
            gc.setFont(Font.font(UI, 12));
            gc.setFill(SOFT);
            I18n.fillText(gc, "un neutron n'a pas de charge : aucune répulsion, rien ne le freine", x, y + 22);
            return;
        }
        double contact = state.getContactFm();
        double force = state.getForceContactN() * Math.pow(contact / Math.max(r, contact), 2);
        gc.setFont(Font.font(MONO, 13));
        gc.setFill(SOFT);
        I18n.fillText(gc, "q₁ = +" + za + "e · q₂ = +" + zb + "e · r = " + Visuals.format(r) + " fm", x, y + 22);
        gc.setFont(Font.font(MONO, FontWeight.BOLD, 15));
        gc.setFill(REPULSION);
        I18n.fillText(gc, I18n.text("répulsion : ") + (force >= 0.1 ? Visuals.format(force) : NucleusPanel.scientific(force)) + " N",
                x, y + 42);
        double start = startFm;
        if (r < start * 0.95) {
            double ratio = start / r;
            gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
            gc.setFill(GOLD);
            I18n.fillText(gc, I18n.text("depuis le départ : r ÷ ") + Visuals.format(ratio) + "  →  F × " + Visuals.format(ratio * ratio),
                    x, y + 62);
        }
    }

    private void drawBanner(double width, double height, int phase, boolean tunnelling) {
        String text = switch (phase) {
            case 0 -> "Les électrons sont arrachés (" + Visuals.format(state.getA().getIonizationEv())
                    + I18n.text(" eV suffisent) : il ne reste que les noyaux, chargés +");
            case 1 -> tunnelling ? "Effet tunnel : il traverse le bouclier sans avoir l'énergie de le franchir"
                    : state.getDeflected() ? "Visée décalée : la répulsion courbe sa trajectoire"
                    : state.getGhostSizesCount() > 0 ? I18n.text("Les autres chocs du plasma (traits fins) sont déviés ; il a fallu ")
                    + state.getAttempts() + I18n.text(" tirs pour que celui-ci passe")
                    : approaching() ? "Il pousse contre le bouclier de répulsion : la flèche rouge mange son élan (dorée)"
                    : "Le bouclier le renvoie : la répulsion redevient de l'élan";
            case 2 -> state.getDeflected() ? "Dévié : sa visée était décalée de " + Visuals.format(state.getImpactUsedFm())
                    + I18n.text(" fm, il passe à côté (moment cinétique l = ") + state.getOrbitalL() + ")"
                    : state.getReflected() ? "Renvoyé au sommet : un noyau est aussi une onde, une part est réfléchie"
                    : "Plus d'élan : demi-tour, le bouclier l'a renvoyé";
            default -> "";
        };
        if (text.isEmpty()) {
            return;
        }
        headline = text;
        headlineColor = GOLD;
    }

    // ---------------------------------------------------------------- la fusion
    // =====================================================================
    // Les nucléons : protons rouges (chargés, ils brillent), neutrons bleus (mats)
    // =====================================================================
    private final Map<Long, double[][]> layouts = new HashMap<>();

    /** Positions des nucléons dans une sphère unité (suite de Halton, comme le moteur) et leur nature. */
    private double[][] layout(int z, int a) {
        long key = z * 1000L + a;
        return layouts.computeIfAbsent(key, k -> {
            int total = Math.max(1, a);
            double[][] points = new double[total][4];
            for (int i = 0; i < total; i++) {
                double u = halton(i + 1, 2);
                double v = halton(i + 1, 3);
                double w = halton(i + 1, 5);
                double radius = total == 1 ? 0 : Math.cbrt(u) * (1 - 0.55 / Math.cbrt(total));
                double cos = 1 - 2 * v;
                double sin = Math.sqrt(Math.max(0, 1 - cos * cos));
                points[i][0] = radius * sin * Math.cos(2 * Math.PI * w);
                points[i][1] = radius * cos;
                points[i][2] = radius * sin * Math.sin(2 * Math.PI * w);
                points[i][3] = ((i + 1L) * z) / total > ((long) i * z) / total ? 1 : 0;
            }
            return points;
        });
    }

    private static double halton(int index, int base) {
        double fraction = 1;
        double result = 0;
        while (index > 0) {
            fraction /= base;
            result += fraction * (index % base);
            index /= base;
        }
        return result;
    }

    private static final Color PROTON_RED = Color.web("#FF4D5E");
    private static final Color NEUTRON_BLUE = Color.web("#3B82F6");

    /**
     * Un amas de nucléons à l'écran.
     * stretch : étirement (goutte liquide) le long de l'angle donné ; jitter : tremblement (noyau chaud).
     */
    private void drawCluster(double cx, double cy, double radius, int z, int a, double jitter, double stretch,
                             double stretchAngle, double alpha) {
        if (a <= 0 || radius < 1) {
            return;
        }
        double[][] points = layout(z, a);
        double turn = clock * 0.5;
        double cosT = Math.cos(turn);
        double sinT = Math.sin(turn);
        double cosS = Math.cos(stretchAngle);
        double sinS = Math.sin(stretchAngle);
        double size = Math.max(1.6, radius * (a == 1 ? 0.55 : 0.62 / Math.cbrt(a)));
        int n = points.length;
        double[][] screen = new double[n][4];
        for (int i = 0; i < n; i++) {
            double x = points[i][0] * cosT + points[i][2] * sinT;
            double zz = -points[i][0] * sinT + points[i][2] * cosT;
            double y = points[i][1] * 0.94 + zz * 0.34;
            double depth = zz * 0.94 - points[i][1] * 0.34;
            if (jitter > 0) {
                x += jitter * Math.sin(clock * 31 + i * 7.3) * 0.18;
                y += jitter * Math.cos(clock * 27 + i * 3.1) * 0.18;
            }
            if (waveNow > 0) {
                // L'onde de choc traverse le noyau depuis la face qui pousse : les nucléons se tassent et vibrent.
                double a0 = x * cosS + y * sinS;
                double push = waveNow * (0.16 * Math.sin(a0 * 7 - clock * 30) + 0.12 * (1 + a0));
                x -= cosS * push;
                y -= sinS * push;
            }
            // Étirement le long d'un axe (goutte liquide).
            double along = x * cosS + y * sinS;
            double across = -x * sinS + y * cosS;
            along *= stretch;
            across /= Math.sqrt(stretch);
            x = along * cosS - across * sinS;
            y = along * sinS + across * cosS;
            screen[i][0] = cx + x * radius;
            screen[i][1] = cy - y * radius;
            screen[i][2] = depth;
            screen[i][3] = points[i][3];
        }
        java.util.Arrays.sort(screen, (p, q) -> Double.compare(q[2], p[2]));
        double pulse = 0.75 + 0.25 * Math.sin(clock * 5);
        gc.setGlobalAlpha(Math.max(0, Math.min(1, alpha)));
        for (double[] p : screen) {
            double shade = 0.75 - 0.25 * p[2];
            Color hot = Color.web("#FDBA74");
            if (p[3] > 0) {
                Color proton = PROTON_RED.interpolate(hot, heatNow * 0.6);
                if (n <= 120 || p[2] < 0) {
                    glow(p[0], p[1], size * (2.3 + 1.5 * heatNow), proton, 0.32 * pulse + 0.35 * heatNow);
                }
                gc.setFill(new RadialGradient(0, 0, p[0] - size * 0.35, p[1] - size * 0.35, size * 1.3, false,
                        CycleMethod.NO_CYCLE, new Stop(0, Color.web("#FFE4E6")), new Stop(0.4, proton),
                        new Stop(1, proton.deriveColor(0, 1, shade * 0.6, 1))));
            } else {
                Color neutron = NEUTRON_BLUE.interpolate(Color.web("#E0E7FF"), heatNow * 0.45);
                if (heatNow > 0.3) {
                    glow(p[0], p[1], size * 2, hot, 0.25 * heatNow);
                }
                gc.setFill(new RadialGradient(0, 0, p[0] - size * 0.35, p[1] - size * 0.35, size * 1.3, false,
                        CycleMethod.NO_CYCLE, new Stop(0, Color.web("#BFDBFE")), new Stop(0.45, neutron),
                        new Stop(1, Color.web("#1E3A8A").deriveColor(0, 1, shade, 1))));
            }
            gc.fillOval(p[0] - size, p[1] - size, size * 2, size * 2);
        }
        gc.setGlobalAlpha(1);
    }

    /** La bulle d'un noyau : une enveloppe translucide de la couleur de l'élément, ses nucléons dedans. */
    private void drawBubble(double x, double y, double screen, Color color, int z, int a, double jitter,
                            double stretch, double stretchAngle, double alpha) {
        double sx = screen * Math.sqrt(stretch);
        glow(x, y, screen * 2.4, color, 0.42 * alpha);
        gc.setGlobalAlpha(alpha);
        gc.save();
        gc.translate(x, y);
        gc.rotate(Math.toDegrees(-stretchAngle));
        gc.setFill(new RadialGradient(0, 0, 0, 0, screen, false, CycleMethod.NO_CYCLE,
                new Stop(0, color.deriveColor(0, 1, 1, 0.10)), new Stop(0.78, color.deriveColor(0, 1, 1, 0.16)),
                new Stop(1, color.deriveColor(0, 1, 1, 0.55))));
        gc.fillOval(-sx, -screen / Math.sqrt(stretch), sx * 2, screen * 2 / Math.sqrt(stretch));
        gc.setStroke(color.interpolate(Color.WHITE, 0.35).deriveColor(0, 1, 1, 0.75));
        gc.setLineWidth(1.5);
        gc.strokeOval(-sx, -screen / Math.sqrt(stretch), sx * 2, screen * 2 / Math.sqrt(stretch));
        // Volutes de matière : l'objet vit.
        gc.setStroke(color.interpolate(Color.WHITE, 0.5).deriveColor(0, 1, 1, 0.35));
        gc.setLineWidth(1);
        for (int k = 0; k < 3; k++) {
            double start = Math.toDegrees(clock * (0.7 + 0.25 * k) + k * 2.1);
            double rr = screen * (1.05 + 0.12 * k);
            gc.strokeArc(-rr * Math.sqrt(stretch), -rr * 0.8, rr * 2 * Math.sqrt(stretch), rr * 1.6, start, 80,
                    javafx.scene.shape.ArcType.OPEN);
        }
        gc.restore();
        gc.setGlobalAlpha(1);
        drawCluster(x, y, screen * 0.8, z, a, jitter, stretch, stretchAngle, alpha);
    }

    // =====================================================================
    // La fusion : goutte liquide, chaos, éjection, nouvelle harmonie
    // =====================================================================
    private static final double DEFORM = 0.6;
    private static final double CHAOS = 0.9;
    private static final double EJECT = 2.6;

    /** Le produit qui reste au centre : le plus lourd des noyaux nés (aucun pour une quasi-fission). */
    private LabProduct finalProduct() {
        if (state.getQuasiFission()) {
            return null;
        }
        LabProduct best = null;
        for (LabProduct product : state.getProductsList()) {
            if (product.getKind().equals("nucleus") && (best == null || product.getA() > best.getA())) {
                best = product;
            }
        }
        return best;
    }

    /** La colline et le puits ne décrivent que la rencontre : après la fusion, l'espace s'aplatit. */
    private double terrainScale() {
        double base = 1;
        if (state != null && state.getFired() && shotPhase() >= 3) {
            double after = shotAge - IONIZE - TRAVEL - DEFORM - CHAOS;
            base = after <= 0 ? 1 : 1 - Visuals.ease(Math.min(1, after / (EJECT * 0.7)));
        }
        if (state != null && shotAge >= 0 && shotAge < 0.9) {
            base = scaleFrom + (base - scaleFrom) * Visuals.ease(shotAge / 0.9);
        }
        return base;
    }

    /** L'onde de choc qui court sur la grille au moment du flash. */
    private double ripple(double d) {
        if (state == null || shotPhase() < 3) {
            return 0;
        }
        double after = shotAge - IONIZE - TRAVEL - DEFORM;
        if (after < 0 || after > CHAOS + EJECT) {
            return 0;
        }
        double front = SPAN * 1.6 * after / (CHAOS + EJECT);
        double amplitude = 18 * Math.pow(1 - after / (CHAOS + EJECT), 1.5);
        double x = (d - front) / 13;
        return amplitude * Math.exp(-x * x) * Math.cos(x * 1.8);
    }

    private double[] lastA;

    private void drawFusion(double after, double by, double ra, double rb) {
        double[] center = camera.project(B_X, by, 0);
        if (center == null) {
            return;
        }
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        LabNucleus a = state.getA();
        LabNucleus b = state.getB();
        double sa = ra * center[3];
        double sb = rb * center[3];
        LabNucleus compound = state.getCompound();
        int zc = a.getZ() + b.getZ();
        int ac = a.getA() + b.getA();
        double sc = radiusOf(ac) * center[3];

        double tc = after - DEFORM;
        double[] fromA = lastA != null ? lastA : new double[]{center[0] - (sa + sb), center[1]};
        double[] fromB = lastB != null ? lastB : center;
        double angle = Math.atan2(fromA[1] - fromB[1], fromA[0] - fromB[0]);
        double contactX = Math.cos(angle);
        double contactY = Math.sin(angle);
        double axis = Math.atan2(-contactY, contactX);
        double endAx = center[0] + contactX * sa * 0.5;
        double endAy = center[1] + contactY * sa * 0.5;
        double endBx = center[0] - contactX * sb * 0.5;
        double endBy = center[1] - contactY * sb * 0.5;
        if (after < DEFORM) {
            // 1. Contact : les deux gouttes glissent l'une dans l'autre ; la surface implicite forme un col de matière.
            //    Elles gardent l'élan de l'impact (rapide au début, puis amorti) : pas d'arrêt.
            double k = after / DEFORM;
            double t = 1 - (1 - k) * (1 - k);
            drawCollapse(center, t);
            double xa = fromA[0] + (endAx - fromA[0]) * t;
            double ya = fromA[1] + (endAy - fromA[1]) * t;
            double xb = fromB[0] + (endBx - fromB[0]) * t;
            double yb = fromB[1] + (endBy - fromB[1]) * t;
            glow((xa + xb) / 2, (ya + yb) / 2, Math.min(sa, sb) * 3.5, STRONG, 0.25 + 0.5 * t);
            List<double[]> balls = new ArrayList<>();
            nucleonBalls(balls, xa, ya, sa * 0.85, a.getZ(), a.getA(), 0.2 * t, 0, axis, 0.3 + 0.5 * t);
            nucleonBalls(balls, xb, yb, sb * 0.85, b.getZ(), b.getA(), 0.2 * t, 0, axis, 0.3 + 0.5 * t);
            drawLiquid(balls, 0.45 + 0.5 * t, 1);
            caption(width, height, "Contact : la force forte les soude", STRONG);
            return;
        }
        if (after < DEFORM + CHAOS) {
            // 2. Le noyau composé : une goutte chaude qui vibre selon ses modes de surface (quadrupôle, octupôle),
            //    amortis à mesure qu'elle se calme.
            double t = tc / CHAOS;
            double flash = Math.sin(Math.PI * Math.min(1, t * 1.5));
            double merge = Visuals.ease(Math.min(1, t / 0.45));
            double[] modes = dropModes(tc);
            List<double[]> from = new ArrayList<>();
            nucleonBalls(from, endAx, endAy, sa * 0.85, a.getZ(), a.getA(), 0.2, 0, axis, 0.8);
            nucleonBalls(from, endBx, endBy, sb * 0.85, b.getZ(), b.getA(), 0.2, 0, axis, 0.8);
            List<double[]> to = new ArrayList<>();
            nucleonBalls(to, center[0], center[1], sc * 0.85, zc, ac, modes[0], modes[1], modes[2], 1.2 * (1 - t * 0.5));
            List<double[]> balls = morph(from, to, merge);
            if (balls != null) {
                drawLiquid(balls, 1 - 0.4 * t, 1);
            } else {
                drawLiquid(from, 1, 1 - merge);
                drawLiquid(to, 1 - 0.4 * t, merge);
            }
            glow(center[0], center[1], sc * (2.5 + 8 * flash), Color.WHITE, 0.6 * flash);
            // Le flash de la scission monte : il couvre l'instant où la goutte devient les produits.
            double rise = Math.exp(-Math.pow((CHAOS - tc) / 0.12, 2));
            glow(center[0], center[1], sc * 10, Color.WHITE, 0.95 * rise);
            caption(width, height, state.getQuasiFission() ? "Trop de protons : ils ne tiennent pas ensemble"
                    : I18n.text("Noyau composé ") + compound.getLabel() + I18n.text(" : une goutte trop chaude, qui vibre"), GOLD);
            return;
        }
        // 3. L'éjection : chaque produit part dans sa direction, à sa vitesse (quantité de mouvement conservée :
        //    le noyau qui reste recule à l'opposé), puis 4. le nouveau noyau se calme et déploie ses attributs.
        double raw = (after - DEFORM - CHAOS) / EJECT;
        double t = Math.min(1, raw);
        LabProduct survivor = finalProduct();
        double emerge = Visuals.ease(Math.min(1, t / 0.3));
        drawSparks();
        if (emerge < 1) {
            double[] modes = dropModes(tc);
            List<double[]> balls = new ArrayList<>();
            nucleonBalls(balls, center[0], center[1], sc * 0.85, zc, ac, modes[0], modes[1], modes[2], 0.6 * (1 - emerge));
            drawLiquid(balls, 1 - emerge, 1 - emerge);
        }
        double fastest = 1e-6;
        for (LabProduct product : state.getProductsList()) {
            if (!product.getKind().equals("gamma") && !product.getKind().equals("positron")) {
                fastest = Math.max(fastest, product.getSpeedC());
            }
        }
        double[][] turn = ejectionTurn(survivor);
        Map<String, Integer> total = new HashMap<>();
        for (LabProduct product : state.getProductsList()) {
            total.merge(product.getLabel(), 1, Integer::sum);
        }
        Map<String, Integer> seen = new HashMap<>();
        double[] keptA = sceneA;
        for (LabProduct product : state.getProductsList()) {
            boolean light = product.getKind().equals("gamma") || product.getKind().equals("positron");
            // Direction réelle (isotrope, tirée par le moteur), tournée pour la lisibilité ; vitesses dans leur
            // vrai rapport : le plus rapide va le plus loin, le noyau qui reste recule d'autant moins qu'il est lourd.
            double[] dir = rotate(turn, normalize(new double[]{product.getDx(), product.getDy(), product.getDz()}));
            double share = light ? 0 : Math.max(product.getSpeedC(), 1e-6) / fastest;
            double reach = light ? SPAN * 1.8 * Math.max(0, raw) : ejectReach(raw, share);
            double x = B_X + dir[0] * reach;
            double z = dir[2] * reach;
            int index = seen.merge(product.getLabel(), 1, Integer::sum);
            int count = total.get(product.getLabel());
            if (light) {
                drawPhoton(center, x, by + dir[1] * reach, z, product, index == 1);
                continue;
            }
            double scene = radiusOf(Math.max(1, product.getA())) * (product == survivor ? 1.1 : 0.85);
            double y = by + dir[1] * reach;
            double[] p = camera.project(x, y, z);
            if (p == null) {
                continue;
            }
            if (product == survivor) {
                survivorReal = realAt(x, z);
                survivorScene = new double[]{x, y, z};
            }
            Color color = product.getZ() == 0 ? Color.web("#93C5FD") : brighten(product.getColor());
            double alpha = product == survivor ? Math.max(emerge, 0.01)
                    : Math.min(1, raw * 6) * Math.max(0, Math.min(1, 1 - (raw - 0.9) / 0.4));
            if (alpha <= 0.01) {
                continue;
            }
            // Le flou de mouvement : des copies pâles le long de sa propre trajectoire, juste derrière lui.
            double lag = reach - ejectReach(raw - 0.06, share);
            if (lag > scene * 0.5) {
                for (int k = 4; k >= 1; k--) {
                    double back = ejectReach(raw - k * 0.015, share);
                    double[] q = camera.project(B_X + dir[0] * back, by + dir[1] * back, dir[2] * back);
                    if (q != null) {
                        glow(q[0], q[1], scene * q[3] * 1.2, color, 0.3 * (1 - k / 5.0) * alpha);
                    }
                }
            }
            // Un sillage bref : quelques étincelles, et l'espace qui ondule derrière les plus rapides.
            if (freeze <= 0 && raw < 0.5 && product.getSpeedC() > 0.005) {
                emitSparks(x, y, z, product.getSpeedC(), color);
            }
            if (raw < 0.6) {
                drawShimmer(p, center, scene * p[3], Math.min(1, product.getSpeedC() * 6) * (1 - raw / 0.6));
            }
            LabNucleus card = productCard(product);
            axisOverride = new double[]{0, 1, 0};
            sceneA = new double[]{x, y, z};
            drawGlassNucleus(p, scene, x, y, z, card, color, alpha, true);
            axisOverride = null;
            if (index == 1) {
                gc.setGlobalAlpha(product == survivor ? emerge : alpha);
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
                gc.setFill(product == survivor ? color : SOFT);
                I18n.fillText(gc, (count > 1 ? count + " × " : "") + product.getLabel(), p[0], p[1] + scene * p[3] + 18);
                gc.setGlobalAlpha(1);
            }
        }
        sceneA = keptA;
        // Le flash de la scission retombe : l'échange goutte → produits s'est fait dessous.
        double burst = Math.exp(-Math.pow(Math.max(0, raw) * EJECT / 0.12, 2));
        glow(center[0], center[1], sc * 10, Color.WHITE, 0.95 * burst);
        if (state.getQMev() != 0 && !resultShown) {
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setFont(Font.font(MONO, FontWeight.BOLD, 30));
            gc.setFill(GOLD);
            double roll = Math.min(1, t / 0.45);
            double shown = state.getQMev() * (1 - Math.pow(1 - roll, 3));
            if (roll < 1) {
                shown += state.getQMev() * 0.04 * Math.sin(clock * 97) * (1 - roll);   // les chiffres s'emballent
            }
            I18n.fillText(gc, (state.getQMev() > 0 ? "+" : "") + Visuals.fixed(shown, Math.abs(state.getQMev()) < 10 ? 2 : 1)
                    + " MeV", width / 2, 156);
            note(I18n.text("E = Δm·c² : la masse disparue (") + Visuals.format(100 * (state.getMassBeforeU() - state.getMassAfterU())
                    / state.getMassBeforeU()) + I18n.text(" %) est devenue cette énergie"), GOLD);
        }
        if (!resultShown && t < 0.9) {
            caption(width, height, state.getQuasiFission() ? "Ils se séparent" : "Éjection : l'énergie part avec les particules",
                    STRONG);
        }
    }

    /** Une seule légende, discrète, en bas de la scène. */
    private void caption(double width, double height, String text, Color color) {
        headline = text;
        headlineColor = color;
    }

    /** Un photon γ : une onde qui part à la vitesse de la lumière ; sa longueur d'onde réelle est affichée. */
    private void drawPhoton(double[] center, double x, double y, double z, LabProduct product, boolean label) {
        double[] p = camera.project(x, y, z);
        if (p == null) {
            return;
        }
        boolean gamma = product.getKind().equals("gamma");
        Color color = gamma ? GAMMA : Color.web("#F472B6");
        double length = Math.hypot(p[0] - center[0], p[1] - center[1]);
        double angle = Math.atan2(p[1] - center[1], p[0] - center[0]);
        double tail = Math.max(0, length - 220);
        gc.setLineWidth(2.2);
        double px = center[0] + tail * Math.cos(angle);
        double py = center[1] + tail * Math.sin(angle);
        for (double s = tail; s < length; s += 3) {
            double w = Math.sin(s * 0.28 - clock * 16) * 7;
            double nx = center[0] + s * Math.cos(angle) - w * Math.sin(angle);
            double ny = center[1] + s * Math.sin(angle) + w * Math.cos(angle);
            gc.setStroke(color.deriveColor(0, 1, 1, 0.25 + 0.75 * (s - tail) / Math.max(length - tail, 1)));
            gc.strokeLine(px, py, nx, ny);
            px = nx;
            py = ny;
        }
        glow(p[0], p[1], 18, color, 0.7);
        if (label && advanced()) {
            double lambda = 1239.84198 / Math.max(product.getKineticMev(), 1e-9);
            tag(p[0], p[1] - 18, (gamma ? "γ · " : "e⁺ + ν · ") + Visuals.format(product.getKineticMev()) + " MeV"
                    + (gamma ? " · λ = " + Visuals.format(lambda) + " fm" : ""), color, 11);
        } else if (label) {
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
            gc.setFill(color);
            I18n.fillText(gc, gamma ? "γ" : "e⁺ + ν", p[0], p[1] - 14);
        }
    }

    private void arrowHead(double x, double y, double angle, Color color) {
        gc.setFill(color.deriveColor(0, 0.8, 1.3, 0.7));
        double s = 5;
        gc.fillPolygon(new double[]{x + s * Math.cos(angle), x + s * Math.cos(angle + 2.5), x + s * Math.cos(angle - 2.5)},
                new double[]{y + s * Math.sin(angle), y + s * Math.sin(angle + 2.5), y + s * Math.sin(angle - 2.5)}, 3);
    }

    private double[] axisOverride;
    private double[] survivorReal;
    private double[][] ejectTurn;

    /** Distance (scène) d'un produit : proportionnelle à sa vitesse réelle ; lancé, puis il ralentit à l'écran. */
    private static double ejectReach(double raw, double share) {
        double t = Math.max(0, raw);
        double run = (t < 1 ? 1 - Math.pow(1 - t, 3) : 1) + 0.12 * t;
        return SPAN * 1.6 * share * run;
    }

    /**
     * Rotation d'affichage des directions d'éjection, tirées isotropes par le moteur : le premier éjecté part vers
     * la droite de l'écran, un peu vers le haut et vers nous. Toutes tournent ensemble : les angles entre produits
     * et la quantité de mouvement totale sont conservés. Fixée au moment de la scission (la caméra peut tourner).
     */
    private double[][] ejectionTurn(LabProduct survivor) {
        if (ejectTurn != null) {
            return ejectTurn;
        }
        LabProduct lead = null;
        for (LabProduct product : state.getProductsList()) {
            boolean light = product.getKind().equals("gamma") || product.getKind().equals("positron");
            if (product != survivor && !light && (lead == null || product.getSpeedC() > lead.getSpeedC())) {
                lead = product;
            }
        }
        if (lead == null) {
            for (LabProduct product : state.getProductsList()) {
                if (product != survivor) {
                    lead = product;
                    break;
                }
            }
        }
        double[] from = lead == null ? new double[]{1, 0, 0}
                : normalize(new double[]{lead.getDx(), lead.getDy(), lead.getDz()});
        double[] right = camera.right();
        double[] up = camera.up();
        double[] ahead = camera.forward();
        double[] to = normalize(new double[]{0.85 * right[0] + 0.3 * up[0] - 0.3 * ahead[0],
                0.85 * right[1] + 0.3 * up[1] - 0.3 * ahead[1], 0.85 * right[2] + 0.3 * up[2] - 0.3 * ahead[2]});
        ejectTurn = rotationBetween(from, to);
        return ejectTurn;
    }

    /** La rotation (Rodrigues) qui amène le vecteur unitaire a sur b. */
    private static double[][] rotationBetween(double[] a, double[] b) {
        double[] k = cross(a, b);
        double s = Math.sqrt(k[0] * k[0] + k[1] * k[1] + k[2] * k[2]);
        double c = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        if (s < 1e-9) {
            if (c > 0) {
                return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
            }
            double[] p = normalize(cross(a, Math.abs(a[0]) < 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0}));
            double[][] m = new double[3][3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    m[i][j] = 2 * p[i] * p[j] - (i == j ? 1 : 0);
                }
            }
            return m;
        }
        k = new double[]{k[0] / s, k[1] / s, k[2] / s};
        double[][] skew = {{0, -k[2], k[1]}, {k[2], 0, -k[0]}, {-k[1], k[0], 0}};
        double[][] m = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                m[i][j] = (i == j ? c : 0) + s * skew[i][j] + (1 - c) * k[i] * k[j];
            }
        }
        return m;
    }

    private static double[] rotate(double[][] m, double[] v) {
        return new double[]{m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2], m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    /** La carte d'identité d'un produit, pour le dessiner comme un vrai noyau (spin, champ, nucléons). */
    private LabNucleus productCard(LabProduct product) {
        return LabNucleus.newBuilder().setZ(product.getZ()).setA(product.getA()).setLabel(product.getLabel())
                .setName(product.getName()).setSpinParity(product.getSpinParity())
                .setMagneticMoment(product.getMagneticMoment()).setColor(product.getColor()).build();
    }

    // =====================================================================
    // L'écran du résultat : la matière créée, ce qui a été éjecté, l'énergie, les ondes
    // =====================================================================
    private Region benchNode;
    private Region launchNode;
    private final HBox resultBar = new HBox(12);
    private boolean resultDismissed;
    private boolean resultShown;

    private boolean wantsResult() {
        return state != null && state.getFired() && (state.getFused() || state.getQuasiFission())
                && shotAge >= revealTime() + 0.8 && !resultDismissed;
    }

    private void buildResultBar() {
        Button replay = action("↺  Revoir la fusion", "#1E293B", "#C7D2FE");
        replay.setOnAction(event -> startShot());
        Button again = action("Nouvelle expérience →", "#1D4ED8", "#EEF2FF");
        again.setOnAction(event -> {
            resultDismissed = true;
            syncResultScreen();
        });
        Button understand = action("Comprendre", "#111827", "#C7D2FE");
        understand.setOnAction(event -> toggleDrawer("facts"));
        resultBar.getChildren().addAll(replay, understand, again);
        resultBar.setAlignment(Pos.CENTER);
        resultBar.setPickOnBounds(false);
        resultBar.setVisible(false);
        resultBar.setManaged(false);
    }

    private void syncResultScreen() {
        boolean show = wantsResult();
        if (show == resultShown) {
            return;
        }
        resultShown = show;
        resultBar.setVisible(show);
        resultBar.setManaged(show);
        if (benchNode != null) {
            benchNode.setVisible(!show);
            launchNode.setVisible(!show);
        }
        toast.setVisible(!show && drawerMode.isEmpty() && !toast.getChildren().isEmpty()
                && !(state != null && (state.getFused() || state.getQuasiFission())));
    }

    /**
     * Le résultat, en surimpression translucide pendant que la scène continue : les produits s'éloignent,
     * le nouveau noyau recule, l'espace s'apaise. À droite : la matière créée, l'éjecté, l'énergie, les ondes.
     */
    private void drawResultOverlay(double width, double height) {
        double appear = Math.min(1, (shotAge - revealTime() - 0.8) / 0.6);
        gc.setGlobalAlpha(Math.max(0, appear));
        List<String> verdict = state.getVerdictList();
        boolean quasi = state.getQuasiFission();
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 11));
        gc.setFill(quasi ? REPULSION : STRONG);
        I18n.fillText(gc, quasi ? "QUASI-FISSION" : "FUSION", width / 2 - 200, 40);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 28));
        gc.setFill(TEXT);
        I18n.fillText(gc, verdict.size() > 1 ? verdict.get(1) : "", width / 2 - 200, 74);
        gc.setFont(Font.font(MONO, 11.5));
        gc.setFill(SOFT);
        I18n.fillText(gc, conservationLine(), width / 2 - 200, 96);
        double w = 420;
        double x = width - w - 20;
        LabProduct survivor = finalProduct();
        double y = drawNewElement(survivor, x, 20, w);
        y = drawEjected(survivor, x, y + 10, w);
        y = drawEnergyBalance(x, y + 10, w);
        drawWaves(x, y + 10, w, height - 96 - (y + 10));
        gc.setGlobalAlpha(1);
    }

    /** Le nouvel élément : sa carte d'identité (le noyau lui-même est dans la scène, derrière). */
    private double drawNewElement(LabProduct survivor, double x, double y, double w) {
        double h = survivor == null ? 96 : 118;
        resultCard(x, y, w, h, survivor == null ? "AUCUN NOYAU NOUVEAU" : "MATIÈRE CRÉÉE");
        gc.setTextAlign(TextAlignment.LEFT);
        if (survivor == null) {
            gc.setFont(Font.font(UI, 12));
            gc.setFill(SOFT);
            double row = y + 50;
            for (String line : SimCanvas.wrap(I18n.text("Aucun noyau de ") + (state.getA().getZ() + state.getB().getZ())
                    + I18n.text(" protons ne tient : la répulsion les a séparés, ils repartent tels qu'ils étaient."), 62)) {
                I18n.fillText(gc, line, x + 18, row);
                row += 17;
            }
            return y + h;
        }
        Color color = brighten(survivor.getColor());
        gc.setFont(Font.font(UI, FontWeight.BOLD, 30));
        gc.setFill(color);
        I18n.fillText(gc, survivor.getLabel(), x + 18, y + 66);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 14));
        gc.setFill(TEXT);
        I18n.fillText(gc, survivor.getElement(), x + 130, y + 50);
        gc.setFont(Font.font(MONO, 11.5));
        gc.setFill(SOFT);
        String spin = survivor.getSpinParity().isEmpty() ? "?" : survivor.getSpinParity();
        I18n.fillText(gc, survivor.getZ() + " p · " + (survivor.getA() - survivor.getZ()) + " n · charge +" + survivor.getZ()
                + "e · spin " + spin, x + 130, y + 68);
        I18n.fillText(gc, Math.abs(survivor.getMagneticMoment()) > 1e-3
                ? I18n.text("moment magnétique ") + Visuals.format(survivor.getMagneticMoment()) + " μN (lignes autour de lui)"
                : "aucun moment magnétique : champ électrique seul", x + 130, y + 85);
        gc.setFont(Font.font(UI, 11));
        gc.setFill(MUTED);
        I18n.fillText(gc, survivor.getStatus() + I18n.text(" · recule à ") + Visuals.format(100 * survivor.getSpeedC()) + " % de c ("
                + Visuals.format(survivor.getKineticMev()) + " MeV)", x + 18, y + 106);
        return y + h;
    }

    /** Protons, nucléons et charge, avant et après : tout est conservé. */
    private String conservationLine() {
        int zIn = state.getA().getZ() + state.getB().getZ();
        int aIn = state.getA().getA() + state.getB().getA();
        int zOut = 0;
        int aOut = 0;
        int leptons = 0;
        for (LabProduct product : state.getProductsList()) {
            if (product.getKind().equals("nucleus") || product.getKind().equals("neutron")) {
                zOut += product.getZ();
                aOut += product.getA();
            } else if (product.getKind().equals("positron")) {
                leptons++;
            }
        }
        return "protons " + zIn + " → " + zOut + (leptons > 0 ? " (+ " + leptons + " devenu neutron)" : "")
                + I18n.text("   ·   nucléons ") + aIn + " → " + aOut + (aIn == aOut ? " ✓" : "")
                + "   ·   charge +" + zIn + "e → +" + (zOut + leptons) + "e ✓";
    }

    private void resultCard(double x, double y, double w, double h, String title) {
        gc.setFill(Color.web("#0A0F1C", 0.78));
        gc.fillRoundRect(x, y, w, h, 16, 16);
        gc.setStroke(Color.web("#1B2438"));
        gc.setLineWidth(1);
        gc.strokeRoundRect(x, y, w, h, 16, 16);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 11));
        gc.setFill(SOFT);
        I18n.fillText(gc, title, x + 18, y + 24);
    }

    /** Ce qui a été éjecté : chaque particule, son énergie, sa vitesse, son spin. */
    private double drawEjected(LabProduct survivor, double x, double y, double w) {
        Map<String, List<LabProduct>> groups = new java.util.LinkedHashMap<>();
        for (LabProduct product : state.getProductsList()) {
            if (product != survivor) {
                groups.computeIfAbsent(product.getLabel(), k -> new ArrayList<>()).add(product);
            }
        }
        double h = 44 + 54 * Math.max(1, groups.size());
        resultCard(x, y, w, h, state.getQuasiFission() ? "ILS REPARTENT" : "ÉJECTÉ PAR LE NOYAU");
        double row = y + 48;
        if (groups.isEmpty()) {
            gc.setFont(Font.font(UI, 12));
            gc.setFill(SOFT);
            I18n.fillText(gc, "rien : le noyau composé est resté entier", x + 18, row + 14);
            return y + h;
        }
        for (Map.Entry<String, List<LabProduct>> entry : groups.entrySet()) {
            LabProduct product = entry.getValue().get(0);
            int count = entry.getValue().size();
            double energy = 0;
            for (LabProduct p : entry.getValue()) {
                energy += p.getKineticMev();
            }
            double ix = x + 40;
            double iy = row + 14;
            boolean light = product.getKind().equals("gamma") || product.getKind().equals("positron");
            if (light) {
                gc.setStroke(product.getKind().equals("gamma") ? GAMMA : Color.web("#F472B6"));
                gc.setLineWidth(2);
                double px = ix - 22;
                double py = iy;
                for (double s = 0; s <= 44; s += 2) {
                    double ny = iy + Math.sin(s * 0.45 - clock * 8) * 6;
                    gc.strokeLine(px, py, ix - 22 + s, ny);
                    px = ix - 22 + s;
                    py = ny;
                }
            } else {
                Color color = product.getZ() == 0 ? Color.web("#93C5FD") : brighten(product.getColor());
                glow(ix, iy, 26, color, 0.35);
                gc.setFill(new RadialGradient(0, 0, ix, iy, 18, false, CycleMethod.NO_CYCLE,
                        new Stop(0, color.deriveColor(0, 1, 1, 0.08)), new Stop(1, color.deriveColor(0, 1, 1, 0.5))));
                gc.fillOval(ix - 18, iy - 18, 36, 36);
                drawCluster(ix, iy, 9, product.getZ(), product.getA(), 0, 1, 0, 1);
            }
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFont(Font.font(UI, FontWeight.BOLD, 14));
            gc.setFill(TEXT);
            String name = (count > 1 ? count + " × " : "") + product.getLabel() + "  " + product.getName();
            I18n.fillText(gc, name, x + 78, row + 8);
            gc.setFont(Font.font(MONO, 11.5));
            gc.setFill(GOLD);
            String detail = light
                    ? Visuals.format(energy) + " MeV · λ = " + Visuals.format(1239.84198 / Math.max(product.getKineticMev(), 1e-9))
                    + I18n.text(" fm · à la vitesse de la lumière")
                    : Visuals.format(energy) + " MeV" + (count > 1 ? " au total" : "") + " · "
                    + Visuals.format(100 * product.getSpeedC()) + " % de c ("
                    + String.format(java.util.Locale.ROOT, "%,.0f", product.getSpeedC() * 299792.458).replace(',', ' ')
                    + " km/s)";
            I18n.fillText(gc, detail, x + 78, row + 26);
            if (!light && !product.getSpinParity().isEmpty()) {
                gc.setFont(Font.font(UI, 10.5));
                gc.setFill(MUTED);
                I18n.fillText(gc, "spin " + product.getSpinParity() + " · charge +" + product.getZ() + "e"
                        + (product.getStatus().isEmpty() ? "" : " · " + product.getStatus()), x + 78, row + 41);
            }
            row += 54;
        }
        return y + h;
    }

    /** L'énergie : la masse avant et après, ce qui a disparu, et comment l'énergie s'est partagée. */
    private double drawEnergyBalance(double x, double y, double w) {
        double h = 196;
        resultCard(x, y, w, h, "ÉNERGIE");
        double before = state.getMassBeforeU();
        double after = state.getMassAfterU();
        double left = x + 18;
        double barW = w - 36;
        gc.setFont(Font.font(MONO, 11.5));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(SOFT);
        I18n.fillText(gc, "masse avant " + Visuals.fixed(before, 6) + " u", left, y + 50);
        I18n.fillText(gc, I18n.text("masse après ") + Visuals.fixed(after, 6) + " u", left, y + 74);
        // Les deux barres, zoomées sur la différence : on voit la masse qui manque.
        double lost = before - after;
        double bx = left + 210;
        double bw = barW - 210;
        // Les barres sont zoomées sur la différence : sinon elle serait invisible (moins de 1 %).
        double big = Math.max(before, after);
        double scale = Math.max(Math.abs(lost) * 6, 1e-9);
        double wBefore = bw * Math.max(0, 1 - (big - before) / scale);
        double wAfter = bw * Math.max(0, 1 - (big - after) / scale);
        gc.setFill(Color.web("#334155"));
        gc.fillRoundRect(bx, y + 40, wBefore, 12, 6, 6);
        gc.fillRoundRect(bx, y + 64, wAfter, 12, 6, 6);
        gc.setFont(Font.font(UI, 10));
        if (lost > 0) {
            gc.setFill(GOLD);
            gc.fillRoundRect(bx + wAfter, y + 64, wBefore - wAfter, 12, 6, 6);
            I18n.fillText(gc, "disparu : " + Visuals.fixed(lost, 6) + " u", bx + wAfter - 40, y + 92);
        } else if (lost < 0) {
            gc.setFill(REPULSION);
            gc.fillRoundRect(bx + wBefore, y + 40, wAfter - wBefore, 12, 6, 6);
            I18n.fillText(gc, I18n.text("gagné : ") + Visuals.fixed(-lost, 6) + I18n.text(" u (pris sur l'énergie du choc)"), bx + wBefore - 120, y + 92);
        }
        gc.setFont(Font.font(MONO, FontWeight.BOLD, 20));
        gc.setFill(GOLD);
        I18n.fillText(gc, (state.getQMev() >= 0 ? "E = Δm·c² = +" : "coût : ") + Visuals.format(Math.abs(state.getQMev())) + " MeV",
                left, y + 120);
        // Le partage : qui emporte quoi.
        double total = 0;
        for (LabProduct product : state.getProductsList()) {
            total += product.getKineticMev();
        }
        double cursor = left;
        double row = y + 138;
        int index = 0;
        for (LabProduct product : state.getProductsList()) {
            double share = total > 0 ? product.getKineticMev() / total : 0;
            Color color = product.getKind().equals("gamma") ? GAMMA : product.getKind().equals("positron")
                    ? Color.web("#F472B6") : product.getZ() == 0 ? Color.web("#93C5FD") : brighten(product.getColor());
            gc.setFill(color);
            gc.fillRect(cursor, row, barW * share, 14);
            if (share > 0.08) {
                gc.setFill(Color.web("#03050B"));
                gc.setFont(Font.font(UI, FontWeight.BOLD, 10));
                I18n.fillText(gc, product.getLabel() + " " + Visuals.format(100 * share) + " %", cursor + 4, row + 11);
            }
            cursor += barW * share;
            index++;
        }
        gc.setFont(Font.font(UI, 10.5));
        gc.setFill(MUTED);
        I18n.fillText(gc, "partage de l'énergie (Q + énergie du choc) entre les produits, selon la quantité de mouvement",
                left, row + 32);
        return y + h;
    }

    /** Les ondes émises : rayons γ (longueur d'onde réelle), positon et neutrino. */
    private void drawWaves(double x, double y, double w, double h) {
        if (h < 70) {
            return;
        }
        resultCard(x, y, w, h, "ONDES ÉMISES");
        List<LabProduct> waves = new ArrayList<>();
        for (LabProduct product : state.getProductsList()) {
            if (product.getKind().equals("gamma") || product.getKind().equals("positron")) {
                waves.add(product);
            }
        }
        gc.setTextAlign(TextAlignment.LEFT);
        if (waves.isEmpty()) {
            gc.setFont(Font.font(UI, 12));
            gc.setFill(SOFT);
            I18n.fillText(gc, "aucun rayon γ : toute l'énergie part en mouvement des particules", x + 18, y + 50);
            return;
        }
        double row = y + 52;
        for (LabProduct wave : waves) {
            boolean gamma = wave.getKind().equals("gamma");
            Color color = gamma ? GAMMA : Color.web("#F472B6");
            double lambda = 1239.84198 / Math.max(wave.getKineticMev(), 1e-9);
            double frequency = wave.getKineticMev() * 1e6 * 1.602176634e-19 / 6.62607015e-34;
            // La sinusoïde : sa période à l'écran suit la longueur d'onde (échelle log).
            double period = 14 + 10 * Math.log10(1 + lambda / 10);
            gc.setStroke(color);
            gc.setLineWidth(2);
            double px = x + 18;
            double py = row;
            for (double s = 0; s <= w - 36; s += 2) {
                double ny = row + Math.sin(2 * Math.PI * s / period - clock * 6) * 8;
                gc.strokeLine(px, py, x + 18 + s, ny);
                px = x + 18 + s;
                py = ny;
            }
            gc.setFont(Font.font(MONO, 11.5));
            gc.setFill(color);
            I18n.fillText(gc, (gamma ? "photon γ · " : "positon + neutrino · ") + Visuals.format(wave.getKineticMev()) + " MeV"
                    + (gamma ? " · λ = " + Visuals.format(lambda) + " fm · f = " + NucleusPanel.scientific(frequency) + " Hz"
                    : ""), x + 18, row + 26);
            row += 52;
            if (row > y + h - 20) {
                break;
            }
        }
    }

    private String colorOfElement(int z) {
        LabElement element = catalog.get(z);
        if (state.getA().getZ() == z) {
            return brightHex(state.getA().getColor());
        }
        if (state.getB().getZ() == z) {
            return brightHex(state.getB().getColor());
        }
        return element == null ? "#E2E8F0" : brightHex(element.getColor());
    }

    private static String brightHex(String hex) {
        Color c = brighten(hex);
        return String.format("#%02X%02X%02X", (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }

    private void drawTopLabel(double width) {
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 18));
        gc.setFill(TEXT);
        I18n.fillText(gc, "Laboratoire de fusion", 22, 34);
        gc.setFont(Font.font(UI, 10.5));
        gc.setFill(MUTED);
        I18n.fillText(gc, "distances en échelle log · tailles agrandies", 22, 50);
        I18n.fillText(gc, "glisser : tourner · molette : zoomer · double-clic : recentrer", 22, 64);
    }

    // =====================================================================
    // Le mouvement : la trajectoire calculée par le moteur
    // =====================================================================
    /** -1 pas de tir, 0 ionisation, 1 trajet, 2 fin (rebond), 3 fusion. */
    private int shotPhase() {
        if (state == null || !state.getFired() || shotAge < 0) {
            return -1;
        }
        if (shotAge < IONIZE) {
            return 0;
        }
        if (shotAge < IONIZE + TRAVEL) {
            return 1;
        }
        return state.getFused() || state.getQuasiFission() ? 3 : 2;
    }

    /** A s'approche-t-il de B (sinon il repart) ? */
    private boolean approaching() {
        int phase = shotPhase();
        if (phase <= 0) {
            return true;
        }
        if (phase >= 2) {
            return false;
        }
        double saved = shotAge;
        double[] now = relativePosition();
        shotAge = Math.max(IONIZE, shotAge - 0.05);
        double[] before = relativePosition();
        shotAge = saved;
        return Math.hypot(now[0], now[1]) <= Math.hypot(before[0], before[1]) + 1e-9;
    }

    private double[] relativePosition() {
        double[] raw = rawRelativePosition();
        double r = Math.hypot(raw[0], raw[1]);
        double contact = state.getContactFm();
        if (r < contact && r > 1e-9) {
            return new double[]{raw[0] * contact / r, raw[1] * contact / r};
        }
        return raw;
    }

    private double[] rawRelativePosition() {
        int n = state.getPathCount() / 3;
        int phase = shotPhase();
        if (n == 0 || phase <= 0) {
            double y0 = n > 0 ? state.getPath(2) : 0;
            double start = n > 0 ? Math.hypot(state.getPath(1), y0) : startFm;
            return new double[]{-Math.sqrt(Math.max(start * start - y0 * y0, 1e-9)), y0};
        }
        double travel = phase >= 2 ? 1 : (shotAge - IONIZE) / TRAVEL;
        int tunnel = state.getTunnelIndex();
        int last = tunnel >= 0 ? tunnel : n - 1;
        double split = tunnel >= 0 ? 0.82 : 1.0;
        double tEnd = state.getPath(3 * last);
        if (travel <= split) {
            double t = tEnd * travel / split;
            for (int i = 1; i <= last; i++) {
                if (state.getPath(3 * i) >= t) {
                    double t0 = state.getPath(3 * (i - 1));
                    double t1 = state.getPath(3 * i);
                    double f = t1 > t0 ? (t - t0) / (t1 - t0) : 0;
                    return new double[]{lerp(state.getPath(3 * i - 2), state.getPath(3 * i + 1), f),
                            lerp(state.getPath(3 * i - 1), state.getPath(3 * i + 2), f)};
                }
            }
            return new double[]{state.getPath(3 * last + 1), state.getPath(3 * last + 2)};
        }
        double f = (travel - split) / (1 - split);
        int index = Math.min(n - 1, last + (int) Math.round(f * (n - 1 - last)));
        return new double[]{state.getPath(3 * index + 1), state.getPath(3 * index + 2)};
    }

    private boolean inTunnel() {
        return shotPhase() == 1 && state.getTunnelIndex() >= 0 && (shotAge - IONIZE) / TRAVEL > 0.82;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    // ---------------------------------------------------------------- outils de dessin
    private void glow(double x, double y, double radius, Color color, double alpha) {
        if (radius <= 0.5) {
            return;
        }
        gc.setFill(new RadialGradient(0, 0, x, y, radius, false, CycleMethod.NO_CYCLE,
                new Stop(0, color.deriveColor(0, 1, 1, Math.min(1, alpha))),
                new Stop(0.35, color.deriveColor(0, 1, 1, Math.min(1, alpha) * 0.35)),
                new Stop(1, color.deriveColor(0, 1, 1, 0))));
        gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);
    }

    /** Une étiquette sobre : texte sur un fond sombre arrondi. */
    private void tag(double x, double y, String text, Color color, double size) {
        gc.setFont(Font.font(UI, FontWeight.BOLD, size));
        double w = text.length() * size * 0.56 + 22;
        x = Math.max(w / 2 + 10, Math.min(canvas.getWidth() - w / 2 - 10, x));
        gc.setFill(Color.web("#070B16", 0.82));
        gc.fillRoundRect(x - w / 2, y - size, w, size * 2, size * 2, size * 2);
        gc.setFill(color);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        I18n.fillText(gc, text, x, y);
        gc.setTextBaseline(VPos.BASELINE);
    }
    // =====================================================================
    // Les messages : un seul endroit, au lieu de textes flottants dans l'espace
    // =====================================================================
    private final List<Object[]> notes = new ArrayList<>();
    private String headline = "";
    private Color headlineColor = GOLD;

    private void note(String text, Color color) {
        notes.add(new Object[]{text, color});
    }

    private boolean advancedView;

    private boolean advanced() {
        return advancedView;
    }

    private void drawNotes(double width, double height) {
        if (headline.isEmpty() && notes.isEmpty() || resultShown) {
            return;
        }
        if (drawer.isVisible() || toast.isVisible()) {
            if (!headline.isEmpty()) {
                tag(width / 2 + 120, height - 128, headline, headlineColor, 13);
            }
            return;
        }
        double w = 360;
        double x = width - w - 16;
        double y = 72;
        List<String> lines = headline.isEmpty() ? List.of() : SimCanvas.wrap(headline, 48);
        int rows = lines.size();
        for (Object[] n : notes) {
            rows += SimCanvas.wrap((String) n[0], 58).size();
        }
        double h = 22 + rows * 17 + (headline.isEmpty() ? 0 : 8);
        gc.setFill(Color.web("#0A0F1C", 0.82));
        gc.fillRoundRect(x, y, w, h, 14, 14);
        double row = y + 24;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 13.5));
        gc.setFill(headlineColor);
        for (String line : lines) {
            I18n.fillText(gc, line, x + 16, row);
            row += 18;
        }
        if (!headline.isEmpty()) {
            row += 6;
        }
        gc.setFont(Font.font(UI, 11.5));
        for (Object[] n : notes) {
            gc.setFill(((Color) n[1]).deriveColor(0, 0.7, 1, 0.9));
            for (String line : SimCanvas.wrap((String) n[0], 58)) {
                I18n.fillText(gc, line, x + 16, row);
                row += 16;
            }
        }
    }

    /** L'essentiel, sans formules : distance, élan, compression du champ, répulsion, chance de passer. */
    private void drawEssentialPanel(double r) {
        double x = 22;
        double y = 124;
        double w = 270;
        gc.setFill(Color.web("#0A0F1C", 0.78));
        gc.fillRoundRect(x, y, w, 238, 14, 14);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 10));
        gc.setFill(SOFT);
        I18n.fillText(gc, I18n.text("L'ESSENTIEL · distance ") + (r >= 100 ? Visuals.format(r) : Visuals.fixed(r, 1)) + " fm", x + 14, y + 20);
        double pressure = compression(r);
        double speed = speedAt(r, shotEnergyMev());
        double kms = speed * 299792.458;
        double tunnel = state.getTunneling();
        double row = y + 44;
        row = measure(x, row, w, "Élan", (kms >= 1000 ? String.format(java.util.Locale.ROOT, "%,.0f", kms).replace(',', ' ')
                : Visuals.format(kms)) + " km/s", Math.min(1, 1 - pressure), GOLD, "sa vitesse, que la répulsion freine");
        row = measure(x, row, w, "Compression du champ", Visuals.format(100 * pressure) + " %", pressure, REPULSION,
                "part de l'élan changée en répulsion");
        row = measure(x, row, w, "Répulsion", forceText(coulombForce(r)), Math.min(1, Math.max(0,
                (Math.log10(Math.max(coulombForce(r), 1e-12)) + 8) / 11)), REPULSION, "F = k·q₁q₂/r²");
        measure(x, row, w, "Chance de passer", tunnel >= 0.5 ? Visuals.format(100 * tunnel) + " %"
                : tunnel > 0 ? I18n.text("1 sur ") + NucleusPanel.scientific(1 / tunnel) : "0",
                tunnel > 0 ? Math.max(0, 1 + Math.log10(tunnel) / 30) : 0, TUNNEL, "effet tunnel, par choc frontal");
        gc.setFont(Font.font(UI, 10));
        gc.setFill(MUTED);
        I18n.fillText(gc, "Vue avancée : formules, toutes les forces, lignes de champ", x + 14, y + 230);
    }
}
