package org.genesis;

import com.studio.genesis.grpc.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.effect.Bloom;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import java.util.*;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import static org.genesis.FusionField.*;

/**
 * La chambre de collision : n'importe quels deux noyaux du tableau, le plasma où ils se rencontrent (critère de
 * Lawson), et la lecture d'un choc calculé par le moteur. La scène occupe toute la fenêtre ; les réglages sont dans
 * un panneau repliable.
 */
public final class ReactorView {
    private final BorderPane root = new BorderPane();
    private final StackPane stage = new StackPane();
    private final Canvas sceneCanvas = new Canvas(), overlayCanvas = new Canvas();
    private final FusionRenderer renderer = new FusionRenderer();
    /** La seconde fenêtre : le même instant, au plus près des noyaux. */
    /** Le bilan interactif, une fois la fusion faite ; un élément choisi y déplace la vue principale vers lui. */
    private final ResultView results = new ResultView(this::focusOn);
    private final ToggleButton resultsButton = new ToggleButton("Bilan");
    private boolean resultsWanted = true, resultsOffscreen, keepResults, lastInsist;
    /** Un tir refusé par le moteur : dit en grand, avec de quoi le corriger. */
    private final Label errorTitle = text("", 15, "#FFD27A"), errorBody = text("", 12, "#E6EEFA");
    private final Button errorFix = button("", true), errorClose = button("Fermer", false);
    private final VBox errorBanner = new VBox(8);
    private double zoomVelocity, zoomGoal = Double.NaN;
    private final Runnable onFire;
    private Runnable onForce = () -> {};
    private IntConsumer onA = i -> {}, onB = i -> {}, onThermal = i -> {};
    private DoubleConsumer onEnergy = v -> {}, onImpact = v -> {}, onField = v -> {}, onTemperature, onDensity = v -> {}, onConfinement = v -> {};
    private final List<LabElement> catalog = new ArrayList<>();
    private CollisionNucleus selectedA, selectedB;
    private final Button chooseA = new Button(), chooseB = new Button();
    private final Label aTitle = text("²H · deutérium", 16, "#FFBC75"), bTitle = text("³H · tritium", 16, "#FFA76A");
    private final Label aData = text("1 proton · 1 neutron", 11, "#A4B6CD"), bData = text("1 proton · 2 neutrons", 11, "#A4B6CD");
    private final Slider beam = new Slider(-1, Math.log10(2e6), Math.log10(64)), impact = new Slider(0, 50, 0), magnetic = new Slider(0, 12, 5.3);
    private final Slider temperature = new Slider(Math.log10(.5), Math.log10(5000), Math.log10(15));
    private final Slider density = new Slider(16, 33, 20), confinement = new Slider(-6, 10, Math.log10(3));
    private final TextField beamInput = new TextField("64");
    private final Label impactLabel = text("0 fm", 12, "#E4EEFF"), fieldLabel = text("5,30 T", 12, "#E4EEFF");
    private final Label temperatureLabel = text("", 12, "#E4EEFF"), densityLabel = text("", 12, "#E4EEFF"), confinementLabel = text("", 12, "#E4EEFF");
    private final Label barrier = text("Barrière : —", 11, "#8196B4");
    private final ToggleButton beamMode = new ToggleButton("Faisceau"), plasmaMode = new ToggleButton("Plasma");
    private final VBox beamBox = new VBox(6), plasmaShotBox = new VBox(4);
    private final Label plasmaShot = text("", 11, "#9FB4CE");
    private final Label lawsonText = text("", 11, "#C6D4E8"), verdict = text("", 11.5, "#F0F5FF");
    private final Canvas lawsonChart = new Canvas(250, 150);
    private final Label phase = text("PRÉPARATION", 10, "#71CDBF"), outcome = text("", 18, "#EEF4FF"), status = text("", 11, "#90A3BE");
    private final Button fire = button("Calculer la collision  →", true), force = button("⚛  Jusqu'à la fusion", false);
    private final Button pause = button("▶", false), replay = button("↺ Rejouer", false);
    private final Slider timeline = new Slider(0, 1, 0), speed = new Slider(.25, 2, 1);
    private final ToggleButton loop = new ToggleButton("Boucle"), series = new ToggleButton("Nouveaux tirs");
    private final VBox sidebar = new VBox();
    private final Button collapse = button("◀", false);
    private LawsonState lawson;
    private CollisionRun run;
    private CollisionTimeline timeMap;
    private FusionRenderer.Snapshot portrait;
    private boolean updating, playing, waiting, dragging;
    private double progress, lastX, lastY, suggested = 64;
    /** Le mode Inspecter : un clic dans la scène y pose une sonde (la zone touchée, les équations du lieu, chiffrées). */
    private final ToggleButton inspect = new ToggleButton("⌖  Inspecter");
    private final FusionDashboard dashboard = new FusionDashboard();
    private final FusionReadouts readouts = new FusionReadouts((z, a) -> z == 0 ? (a == 0 ? "γ" : "n") : nuclide(z, a, symbolOfZ(z)));
    /** Ce que couvrent déjà le tableau de bord et le bilan (x, y, largeur, hauteur) : les étiquettes s'en écartent. */
    private final List<double[]> occupied = new ArrayList<>();
    private double[] probe;
    private double hoverX = -1, hoverY = -1, pressX, pressY, barrierKev, requestedKev = 64;

    public ReactorView(Runnable exit, IntConsumer legacyFuel, DoubleConsumer legacyTemperature, Runnable fireCallback) {
        onFire = fireCallback;
        onTemperature = legacyTemperature;
        root.getStyleClass().add("fusion-lab");
        root.setStyle("-fx-background-color:#04070D;-fx-font-family:'Segoe UI';");
        Pane holder = new Pane(sceneCanvas, overlayCanvas);
        holder.setMinSize(100, 100);
        for (Canvas c : new Canvas[]{sceneCanvas, overlayCanvas}) {
            c.widthProperty().bind(holder.widthProperty());
            c.heightProperty().bind(holder.heightProperty());
        }
        overlayCanvas.setMouseTransparent(true);
        if (Boolean.getBoolean("genesis.bloom")) sceneCanvas.setEffect(new Bloom(.62));
        holder.setCursor(Cursor.OPEN_HAND);
        holder.setOnMousePressed(e -> {
            dragging = true;
            lastX = pressX = e.getX();
            lastY = pressY = e.getY();
            holder.setCursor(inspect.isSelected() ? Cursor.CROSSHAIR : Cursor.CLOSED_HAND);
        });
        holder.setOnMouseDragged(e -> {
            renderer.camera.drag(e.getX() - lastX, e.getY() - lastY);
            lastX = e.getX();
            lastY = e.getY();
        });
        holder.setOnMouseReleased(e -> {
            dragging = false;
            holder.setCursor(inspect.isSelected() ? Cursor.CROSSHAIR : Cursor.OPEN_HAND);
            // Un clic (sans glisser) en mode Inspecter pose la sonde ; sur la sonde, il la retire.
            if (inspect.isSelected() && Math.hypot(e.getX() - pressX, e.getY() - pressY) < 5) pin(e.getX(), e.getY());
        });
        holder.setOnMouseMoved(e -> {
            hoverX = e.getX();
            hoverY = e.getY();
        });
        holder.setOnMouseExited(e -> hoverX = hoverY = -1);
        holder.setOnScroll(e -> scrollZoom(e.getDeltaY()));
        holder.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                zoomGoal = 1;
                zoomVelocity = 0;
                renderer.camera.orient(-.55, .34);
            }
        });
        Button back = button("← Atome", false);
        back.setOnAction(e -> exit.run());
        resultsButton.setId("collision-results");
        resultsButton.getStyleClass().add("glass-toggle");
        inspect.setId("collision-inspect");
        inspect.setFocusTraversable(false);
        inspect.getStyleClass().add("glass-toggle");
        inspect.setTooltip(new Tooltip("Un clic dans la scène : la zone touchée (halo, heurt, point neutre, noyau, front des produits) "
                + "et les équations qui s'y appliquent, avec leurs valeurs"));
        inspect.setOnAction(e -> {
            probe = null;
            holder.setCursor(inspect.isSelected() ? Cursor.CROSSHAIR : Cursor.OPEN_HAND);
        });
        resultsButton.setFocusTraversable(false);
        resultsButton.setSelected(true);
        resultsButton.setTooltip(new Tooltip("Le bilan interactif s'ouvre une fois la fusion faite : créé, rejeté, lumière, énergie"));
        resultsButton.setOnAction(e -> {
            resultsWanted = resultsButton.isSelected();
            if (resultsWanted && settled()) openResults();
            else if (!resultsWanted) results.hide();
        });
        results.setOnHidden(() -> {
            if (!keepResults) resultsWanted = false;
            resultsButton.setSelected(resultsWanted);
        });
        // Quitter la chambre de collision referme le bilan ; y revenir le rouvre s'il l'était.
        root.visibleProperty().addListener((o, was, now) -> {
            if (!now) quietlyHideResults();
            else if (resultsWanted && settled()) openResults();
        });
        errorBanner.getChildren().addAll(errorTitle, errorBody, new HBox(8, errorFix, errorClose));
        errorBanner.setPadding(new Insets(14, 16, 14, 16));
        errorBanner.setMaxSize(540, Region.USE_PREF_SIZE);
        errorBanner.setStyle("-fx-background-color:linear-gradient(to bottom, rgba(16,27,43,0.96), rgba(7,12,20,0.97));-fx-background-radius:14;"
                + "-fx-border-color:rgba(255,178,74,0.7);-fx-border-radius:14;-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.6), 24, 0.2, 0, 6);");
        errorBody.setWrapText(true);
        errorBanner.setVisible(false);
        errorFix.setId("collision-error-fix");
        errorClose.setOnAction(e -> errorBanner.setVisible(false));
        StackPane.setAlignment(errorBanner, Pos.CENTER);
        Button model = button("Modèle & données", false);
        model.setOnAction(e -> model());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox controls = new HBox(8, phase, gap, layer("Champ E", () -> renderer.showElectric = !renderer.showElectric),
                layer("Champ B", () -> renderer.showMagnetic = !renderer.showMagnetic),
                layer("Onde", () -> renderer.showWave = !renderer.showWave), inspect, resultsButton, model, back);
        controls.setAlignment(Pos.CENTER_LEFT);
        outcome.setTextOverrun(OverrunStyle.ELLIPSIS);
        outcome.setMaxWidth(Double.MAX_VALUE);
        Tooltip fullReaction = new Tooltip();
        outcome.setTooltip(fullReaction);
        outcome.textProperty().addListener((o, oldText, newText) -> fullReaction.setText(newText));
        VBox header = new VBox(2, controls, outcome);
        header.setAlignment(Pos.TOP_LEFT);
        header.setPadding(new Insets(10, 14, 0, 18));
        header.setPickOnBounds(false);
        StackPane.setAlignment(header, Pos.TOP_CENTER);
        header.setMaxHeight(Region.USE_PREF_SIZE);
        Region transport = transport();
        StackPane.setAlignment(transport, Pos.BOTTOM_CENTER);
        // Le calque du bilan flottant : il ne prend la souris que sur la fenêtre elle-même.
        Pane floating = new Pane(results.panel());
        floating.setPickOnBounds(false);
        stage.getChildren().addAll(holder, header, transport, floating, errorBanner);
        root.setCenter(stage);
        root.setLeft(sidebar());
        portrait = portrait();
    }

    public Region getNode() {
        return root;
    }

    /** Le bilan comme nœud (pour l'enregistrer hors écran). */
    Region resultNode() {
        resultsOffscreen = true;
        return results.takeBody();
    }

    private void scrollZoom(double deltaY) {
        zoomGoal = Double.NaN;
        zoomVelocity += -deltaY * .012;
    }

    private void openResults() {
        if (!root.isVisible() || resultsOffscreen) return;
        results.show();
        resultsButton.setSelected(true);
    }

    private void quietlyHideResults() {
        keepResults = true;
        results.hide();
        keepResults = false;
    }

    /** La fusion est faite (désexcitation, produits) ou le calcul est fini : place au bilan. */
    private boolean settled() {
        if (run == null) return false;
        String now = renderer.report.stage;
        return now.equals("evaporation") || now.equals("products") || progress >= .965;
    }

    /** Un élément choisi dans le bilan : la vue principale va vers lui. */
    private void focusOn(int z, int a) {
        renderer.focusZ = z;
        renderer.focusA = a;
    }

    public void setOnNucleusA(IntConsumer c) { onA = c; }
    public void setOnNucleusB(IntConsumer c) { onB = c; }
    public void setOnCollisionEnergy(DoubleConsumer c) { onEnergy = c; }
    public void setOnImpact(DoubleConsumer c) { onImpact = c; }
    public void setOnField(DoubleConsumer c) { onField = c; }
    /** Un tir qui montre la fusion (sa probabilité réelle reste affichée). */
    public void setOnForce(Runnable c) { onForce = c; }
    public void setOnThermal(IntConsumer c) { onThermal = c; }
    public void setOnDensity(DoubleConsumer c) { onDensity = c; }
    public void setOnConfinement(DoubleConsumer c) { onConfinement = c; }

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

    /** Une mesure de texte (pour poser les étiquettes sans chevauchement). */
    private static final Text MEASURE = new Text();

    static double width(String text, Font font) {
        MEASURE.setFont(font);
        MEASURE.setText(text);
        return MEASURE.getLayoutBounds().getWidth();
    }

    // ================================================================== le panneau
    private void card(Button button, Label title, Label data, String side) {
        Label eyebrow = text(FusionDashboard.spaced("NOYAU " + side), 9, "#7F97B6"), pick = text("CHOISIR  ↗", 9, "#8FE6FF");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox top = new HBox(6, eyebrow, gap, pick);
        top.setAlignment(Pos.CENTER_LEFT);
        data.setWrapText(true);
        title.setMaxWidth(226);
        data.setMaxWidth(226);
        VBox box = new VBox(3, top, title, data);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPrefWidth(226);
        box.setMaxWidth(226);
        button.setGraphic(box);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setPrefHeight(96);
        button.setMinHeight(96);
        button.setMaxHeight(96);
        button.setAlignment(Pos.CENTER_LEFT);
        button.getStyleClass().add("nucleus-card");
        styleCard(button, Color.web(side.equals("A") ? "#FFBC75" : "#FFA76A"));
        button.setFocusTraversable(false);
        button.setId("collision-select-" + side.toLowerCase(Locale.ROOT));
        button.setOnAction(e -> {
            if (catalog.isEmpty()) {
                status.setText("Le catalogue est en cours de réception.");
                return;
            }
            CollisionNucleus n = side.equals("A") ? selectedA : selectedB;
            new CollisionPicker(catalog, n == null ? 1 : n.getZ(), n == null ? 2 : n.getA(), side,
                    code -> { if (side.equals("A")) onA.accept(code); else onB.accept(code); }).show();
        });
    }

    /** La carte d'un noyau : un liseré et un dégradé à la couleur de l'élément, sur du verre fumé. */
    private static void styleCard(Button button, Color color) {
        String c = hex(color);
        button.setStyle("-fx-background-color:" + c + ", #0C1626, linear-gradient(to right, " + c + "33, " + c + "08 55%, rgba(255,255,255,0.02));"
                + "-fx-background-insets:0, 0 0 0 3, 0 0 0 3;-fx-background-radius:12, 0 12 12 0, 0 12 12 0;-fx-border-color:rgba(150,200,255,0.14);"
                + "-fx-border-radius:12;-fx-padding:9 12 9 13;-fx-cursor:hand;");
    }

    /** Une section du panneau : son numéro, son titre en capitales espacées, son contenu, sur une plaque de verre. */
    private static VBox section(String index, String title, Node... nodes) {
        Label number = text(index, 9.5, "#36CDF2"), name = text(FusionDashboard.spaced(title), 9.5, "#8FB3D9");
        number.setStyle(number.getStyle() + "-fx-font-weight:bold;");
        name.setStyle(name.getStyle() + "-fx-font-weight:bold;");
        HBox head = new HBox(9, number, name);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(0, 0, 2, 0));
        VBox box = new VBox(7);
        box.getChildren().add(head);
        box.getChildren().addAll(nodes);
        box.getStyleClass().add("glass-section");
        return box;
    }

    /** Un réglage : son nom à gauche, sa valeur à droite. */
    private static HBox row(String title, Label value) {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(8, text(title, 11, "#8EA6C0"), gap, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Region sidebar() {
        card(chooseA, aTitle, aData, "A");
        card(chooseB, bTitle, bData, "B");
        beamInput.setId("collision-energy-input");
        beamInput.setPrefColumnCount(8);
        beamInput.setOnAction(e -> submitEnergy());
        beamInput.focusedProperty().addListener((o, a, b) -> { if (!b) submitEnergy(); });
        beam.setId("collision-energy");
        impact.setId("collision-impact");
        magnetic.setId("collision-field");
        beam.valueProperty().addListener((o, a, b) -> {
            if (!updating) {
                double energy = Math.pow(10, b.doubleValue());
                beamInput.setText(String.format(Locale.ROOT, "%.5g", energy));
                if (!beam.isValueChanging()) onEnergy.accept(energy);
            }
        });
        beam.valueChangingProperty().addListener((o, a, b) -> { if (!b && !updating) onEnergy.accept(Math.pow(10, beam.getValue())); });
        impact.valueProperty().addListener((o, a, b) -> {
            impactLabel.setText(fmt(b.doubleValue()) + " fm");
            if (!updating && !impact.isValueChanging()) onImpact.accept(b.doubleValue());
        });
        impact.valueChangingProperty().addListener((o, a, b) -> { if (!b && !updating) onImpact.accept(impact.getValue()); });
        magnetic.valueProperty().addListener((o, a, b) -> {
            fieldLabel.setText(fmt(b.doubleValue()) + " T");
            if (!updating && !magnetic.isValueChanging()) onField.accept(b.doubleValue());
        });
        magnetic.valueChangingProperty().addListener((o, a, b) -> { if (!b && !updating) onField.accept(magnetic.getValue()); });
        Button near = button("Énergie au-dessus de la barrière", false);
        near.setMaxWidth(Double.MAX_VALUE);
        near.setOnAction(e -> onEnergy.accept(suggested));
        HBox typed = new HBox(8, beamInput, text("keV", 12, "#ADC4DB"));
        typed.setAlignment(Pos.CENTER_LEFT);
        beamBox.getChildren().addAll(text("Énergie du choc (centre de masse)", 11, "#8EA6C0"), typed, beam, barrier, near, row("Visée b", impactLabel), impact);
        plasmaShotBox.getChildren().add(plasmaShot);
        plasmaShot.setWrapText(true);
        ToggleGroup mode = new ToggleGroup();
        for (ToggleButton t : new ToggleButton[]{beamMode, plasmaMode}) {
            t.setToggleGroup(mode);
            t.setFocusTraversable(false);
            t.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(t, Priority.ALWAYS);
        }
        beamMode.getStyleClass().addAll("segment", "left");
        plasmaMode.getStyleClass().addAll("segment", "right");
        beamMode.setSelected(true);
        beamMode.setOnAction(e -> { beamMode.setSelected(true); if (!updating) onThermal.accept(0); showMode(false); });
        plasmaMode.setOnAction(e -> { plasmaMode.setSelected(true); if (!updating) onThermal.accept(1); showMode(true); });
        showMode(false);
        logSlider(temperature, v -> onTemperature.accept(v));
        logSlider(density, v -> onDensity.accept(v));
        logSlider(confinement, v -> onConfinement.accept(v));
        temperature.setId("collision-temperature");
        density.setId("collision-density");
        confinement.setId("collision-confinement");
        lawsonText.setWrapText(true);
        verdict.setWrapText(true);
        status.setWrapText(true);
        HBox modes = new HBox(0, beamMode, plasmaMode);
        VBox box = new VBox(10,
                section("01", "LES DEUX NOYAUX", chooseA, chooseB),
                section("02", "LE CHOC", modes, beamBox, plasmaShotBox, row("Champ externe B", fieldLabel), magnetic),
                section("03", "LE PLASMA · LAWSON", row("Température", temperatureLabel), temperature, row("Densité", densityLabel), density,
                        row("Confinement τ", confinementLabel), confinement, lawsonChart, lawsonText, verdict),
                status);
        box.setPadding(new Insets(2, 12, 12, 12));
        box.setPrefWidth(296);
        box.setMaxWidth(296);
        box.setStyle("-fx-background-color:transparent;");
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background:transparent;-fx-background-color:transparent;-fx-border-color:transparent;");
        fire.setId("collision-fire");
        fire.setMaxWidth(Double.MAX_VALUE);
        fire.setOnAction(e -> launch(false));
        force.setId("collision-force");
        force.getStyleClass().setAll("button", "glass-amber");
        force.setMaxWidth(Double.MAX_VALUE);
        force.setOnAction(e -> launch(true));
        force.setTooltip(new Tooltip("Montre une fusion : la capture est retenue, sa vraie probabilité reste affichée"));
        collapse.setOnAction(e -> toggleSidebar());
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(3.5, Color.web("#36CDF2"));
        dot.setEffect(new javafx.scene.effect.DropShadow(8, Color.web("#36CDF2")));
        Label brand = text(FusionDashboard.spaced("GENESIS"), 10, "#DCE8F7"), room = text(FusionDashboard.spaced("CHAMBRE DE COLLISION"), 8.5, "#7F98B8");
        brand.setStyle(brand.getStyle() + "-fx-font-weight:bold;");
        VBox title = new VBox(1, brand, room);
        HBox named = new HBox(9, dot, title);
        named.setAlignment(Pos.CENTER_LEFT);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox top = new HBox(8, named, gap, collapse);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 10, 10, 14));
        VBox dock = new VBox(7, fire, force);
        dock.setPadding(new Insets(10, 12, 12, 12));
        dock.setStyle("-fx-border-color:rgba(150,200,255,0.12) transparent transparent transparent;");
        sidebar.getChildren().addAll(top, scroll, dock);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        setSidebarWidth(300);
        sidebar.getStyleClass().add("glass-side");
        return sidebar;
    }

    private void setSidebarWidth(double width) {
        sidebar.setPrefWidth(width);
        sidebar.setMinWidth(width);
        sidebar.setMaxWidth(width);
    }

    private void toggleSidebar() {
        boolean open = sidebar.getPrefWidth() > 100;
        setSidebarWidth(open ? 46 : 300);
        collapse.setText(open ? "▶" : "◀");
        HBox top = (HBox) sidebar.getChildren().get(0);
        top.getChildren().get(0).setVisible(!open);
        for (int i = 1; i < sidebar.getChildren().size(); i++) {
            sidebar.getChildren().get(i).setVisible(!open);
            sidebar.getChildren().get(i).setManaged(!open);
        }
    }

    private void showMode(boolean plasma) {
        beamBox.setVisible(!plasma);
        beamBox.setManaged(!plasma);
        plasmaShotBox.setVisible(plasma);
        plasmaShotBox.setManaged(plasma);
    }

    private void logSlider(Slider slider, DoubleConsumer consumer) {
        slider.valueProperty().addListener((o, a, b) -> {
            refreshPlasmaLabels();
            if (!updating && !slider.isValueChanging()) consumer.accept(Math.pow(10, b.doubleValue()));
        });
        slider.valueChangingProperty().addListener((o, a, b) -> { if (!b && !updating) consumer.accept(Math.pow(10, slider.getValue())); });
    }

    private void refreshPlasmaLabels() {
        double kev = Math.pow(10, temperature.getValue());
        temperatureLabel.setText(energy(kev) + " · " + kelvin(kev * 1.160451812e7));
        densityLabel.setText(scientificShort(Math.pow(10, density.getValue())) + " ions/m³");
        confinementLabel.setText(duration(Math.pow(10, confinement.getValue())));
    }

    /** Le champ d'énergie dit-il autre chose que l'énergie en vigueur ? */
    private boolean edited() {
        try {
            double typed = Double.parseDouble(beamInput.getText().replace(',', '.')), current = Math.pow(10, beam.getValue());
            return Math.abs(typed - current) > 1e-6 * Math.max(1, current);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void submitEnergy() {
        if (updating) return;
        try {
            double value = Double.parseDouble(beamInput.getText().replace(',', '.'));
            if (!Double.isFinite(value) || value < .1 || value > 2e6) throw new NumberFormatException();
            onEnergy.accept(value);
        } catch (NumberFormatException e) {
            status.setText("Énergie attendue : 0,1 à 2 000 000 keV, dans le domaine du modèle.");
        }
    }

    private ToggleButton layer(String title, Runnable action) {
        ToggleButton b = new ToggleButton(title);
        b.setSelected(true);
        b.setFocusTraversable(false);
        b.getStyleClass().add("glass-toggle");
        b.setOnAction(e -> action.run());
        return b;
    }

    private Region transport() {
        pause.setId("collision-play");
        replay.setId("collision-replay");
        timeline.setId("collision-timeline");
        pause.setOnAction(e -> {
            if (run != null) {
                if (progress >= 1) progress = 0;
                playing = !playing;
                pause.setText(playing ? "Ⅱ" : "▶");
            }
        });
        replay.setOnAction(e -> {
            if (run != null) {
                progress = 0;
                playing = true;
                pause.setText("Ⅱ");
            }
        });
        timeline.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(timeline, Priority.ALWAYS);
        timeline.valueProperty().addListener((o, a, b) -> { if (!updating) seekProgress(b.doubleValue()); });
        Button closest = button("Au plus près", false), products = button("Issue", false);
        closest.setOnAction(e -> { if (timeMap != null) seekProgress(timeMap.closestApproach()); });
        products.setOnAction(e -> {
            if (timeMap != null) {
                double p = timeMap.phaseStart("products");
                seekProgress(p < 0 ? 1 : Math.min(1, p + .1));
            }
        });
        speed.setPrefWidth(70);
        speed.setMaxWidth(70);
        speed.setTooltip(new Tooltip("Vitesse de lecture"));
        for (ToggleButton t : new ToggleButton[]{loop, series}) { t.getStyleClass().add("glass-toggle"); t.setFocusTraversable(false); }
        loop.setTooltip(new Tooltip("Rejouer le même calcul"));
        series.setTooltip(new Tooltip("Calculer une nouvelle collision à chaque fin"));
        HBox controls = new HBox(7, pause, replay, timeline, closest, products, text("×", 10, "#9DB1C8"), speed, loop, series);
        controls.setAlignment(Pos.CENTER_LEFT);
        VBox bar = new VBox(3, controls);
        bar.setPadding(new Insets(7, 14, 7, 14));
        bar.setMaxHeight(Region.USE_PREF_SIZE);
        bar.setStyle("-fx-background-color:linear-gradient(to bottom, rgba(10,18,30,0.8), rgba(5,9,16,0.93));"
                + "-fx-border-color:rgba(150,200,255,0.14) transparent transparent transparent;");
        return bar;
    }

    private void launch(boolean insist) {
        if (waiting) return;
        // Les boutons ne prennent pas le focus : une énergie tapée sans Entrée serait restée dans le champ.
        if (beamMode.isSelected() && edited()) submitEnergy();
        lastInsist = insist;
        errorBanner.setVisible(false);
        waiting = true;
        fire.setDisable(true);
        force.setDisable(true);
        status.setText("Calcul des trajectoires et des voies de sortie…");
        if (insist) onForce.run();
        else onFire.run();
    }

    // ================================================================== les frames du moteur
    public void applyFrame(UniverseFrame frame) {
        ReactorState s = frame.getReactor();
        if (!s.getActive()) return;
        if (s.getCatalogCount() > 0) {
            catalog.clear();
            catalog.addAll(s.getCatalogList());
        }
        if (s.hasSelectedA()) {
            selectedA = s.getSelectedA();
            selectedB = s.getSelectedB();
            FusionRenderer.registerColor(selectedA.getZ(), selectedA.getColorHex());
            FusionRenderer.registerColor(selectedB.getZ(), selectedB.getColorHex());
            updateCard(selectedA, aTitle, aData, chooseA);
            updateCard(selectedB, bTitle, bData, chooseB);
            portrait = portrait();
        }
        if (s.hasCollision())
            for (CollisionSample sample : s.getCollision().getSamplesList())
                for (CollisionBody body : sample.getBodiesList()) FusionRenderer.registerColor(body.getZ(), body.getColorHex());
        updating = true;
        if (!beam.isValueChanging() && !beamInput.isFocused() && s.getRequestedEnergyKev() > 0) {
            beam.setValue(Math.log10(s.getRequestedEnergyKev()));
            beamInput.setText(String.format(Locale.ROOT, "%.6g", s.getRequestedEnergyKev()));
        }
        if (!impact.isValueChanging()) impact.setValue(s.getRequestedImpactFm());
        if (!magnetic.isValueChanging()) magnetic.setValue(s.getFieldT());
        (s.getThermalMode() ? plasmaMode : beamMode).setSelected(true);
        showMode(s.getThermalMode());
        if (s.hasLawson()) {
            lawson = s.getLawson();
            if (!temperature.isValueChanging() && !temperature.isPressed()) temperature.setValue(Math.log10(lawson.getTemperatureKev()));
            if (!density.isValueChanging() && !density.isPressed()) density.setValue(Math.log10(lawson.getDensityM3()));
            if (!confinement.isValueChanging() && !confinement.isPressed()) confinement.setValue(Math.log10(lawson.getConfinementS()));
            refreshLawson();
        }
        updating = false;
        refreshPlasmaLabels();
        suggested = s.getSuggestedEnergyKev() > 0 ? s.getSuggestedEnergyKev() : 64;
        barrier.setText("Barrière de Coulomb : " + energy(s.getBarrierKev()));
        barrierKev = s.getBarrierKev();
        if (s.getRequestedEnergyKev() > 0) requestedKev = s.getRequestedEnergyKev();
        if (!s.getError().isBlank()) {
            if (waiting) showRefusal(s.getError(), s.getThermalMode());
            waiting = false;
            fire.setDisable(false);
            force.setDisable(false);
            status.setText(s.getError());
            return;
        }
        if (s.hasCollision() && s.getCollision().getSamplesCount() > 1) {
            run = s.getCollision();
            timeMap = new CollisionTimeline(run);
            progress = 0;
            playing = true;
            waiting = false;
            fire.setDisable(false);
            force.setDisable(false);
            pause.setText("Ⅱ");
            renderer.zoom = 1;
            zoomVelocity = 0;
            zoomGoal = Double.NaN;
            renderer.focusZ = renderer.focusA = -1;
            errorBanner.setVisible(false);
            quietlyHideResults();
            status.setText(energy(run.getEnergyKev()) + " · b = " + fmt(run.getImpactFm()) + " fm · P = " + probability(run.getProbability())
                    + (run.getThermalKev() > 0 ? " · tiré dans le plasma : 1 choc sur 10" + superscript((int) Math.round(-run.getRarityLog10())) + " est aussi énergique" : ""));
        } else if (s.getReactantsCount() >= 2 || s.hasSelectedA()) {
            if (!s.getFired()) {
                run = null;
                timeMap = null;
                playing = false;
                progress = 0;
                updating = true;
                timeline.setValue(0);
                updating = false;
                pause.setText("▶");
            }
            waiting = false;
            fire.setDisable(false);
            force.setDisable(false);
        }
    }

    /** Le moteur a refusé le tir : on le dit au centre de l'écran, et l'on propose l'énergie qui marche. */
    private void showRefusal(String error, boolean plasma) {
        String e = error.toLowerCase(Locale.ROOT);
        boolean low = e.contains("trop basse") || e.contains("probabilite nulle") || e.contains("canal ferme");
        boolean high = e.contains("hors modele");
        errorTitle.setText(low ? "Tir refusé : énergie trop basse pour ce couple" : high ? "Tir refusé : énergie trop haute pour le modèle"
                : "Collision non calculée");
        errorBody.setText(error + (low || high ? "\nÉnergie conseillée pour ce couple : " + energy(suggested) + " (au-dessus du mur de Coulomb)." : ""));
        errorFix.setVisible(low || high);
        errorFix.setManaged(low || high);
        errorFix.setText((plasma ? "Passer en faisceau à " : "Régler à ") + energy(suggested) + " et relancer");
        errorFix.setOnAction(ev -> {
            errorBanner.setVisible(false);
            if (plasmaMode.isSelected()) {
                beamMode.setSelected(true);
                onThermal.accept(0);
                showMode(false);
            }
            updating = true;
            beam.setValue(Math.log10(suggested));
            beamInput.setText(String.format(Locale.ROOT, "%.5g", suggested));
            updating = false;
            onEnergy.accept(suggested);
            launch(lastInsist);
        });
        errorBanner.setVisible(true);
    }

    private void refreshLawson() {
        LawsonState l = lawson;
        double gain = l.getGain();
        lawsonText.setText("⟨σv⟩ = " + scientificShort(l.getReactivityM3S()) + " m³/s · Q = " + fmt(l.getQMev()) + " MeV par réaction\n"
                + "fusion " + power(l.getFusionWM3()) + " · dont chaleur gardée " + power(l.getDepositedWM3()) + "\n"
                + "pertes : freinage " + power(l.getBremsstrahlungWM3()) + " · fuite W/τ " + power(l.getConductionWM3()) + "\n"
                + "gain Q = " + (l.getIgnited() ? "∞ (ignition)" : gain > 0 ? fmt(gain) : "0") + " · nτT = " + scientificShort(l.getTripleProduct()) + " keV·s/m³"
                + (l.getWaitS() > 0 ? "\nun noyau fusionne en moyenne après " + duration(l.getWaitS()) : ""));
        verdict.setText(l.getVerdict());
        verdict.setStyle("-fx-text-fill:" + (l.getIgnited() ? "#7FE3B0" : "#FFC98A") + ";-fx-font-size:11.5px;");
        plasmaShot.setText("Le choc est tiré dans ce plasma (kT = " + energy(l.getTemperatureKev()) + ") : « Calculer » prend un choc au hasard"
                + " (flux maxwellien), « Jusqu'à la fusion » un choc parmi ceux qui fusionnent (pic de Gamow).");
        drawLawson();
    }

    /** Le diagramme de Lawson : nτT nécessaire selon la température (ignition, Q = 1), et le plasma réglé. */
    private void drawLawson() {
        GraphicsContext g = lawsonChart.getGraphicsContext2D();
        double w = lawsonChart.getWidth(), h = lawsonChart.getHeight(), left = 30, bottom = 18, top = 6, right = 6;
        g.clearRect(0, 0, w, h);
        g.setFill(Color.color(1, 1, 1, .035));
        g.fillRoundRect(0, 0, w, h, 10, 10);
        if (lawson == null) return;
        double x0 = Math.log10(.5), x1 = Math.log10(5000), y0 = 18, y1 = 28;
        java.util.function.DoubleUnaryOperator px = t -> left + (Math.log10(t) - x0) / (x1 - x0) * (w - left - right);
        java.util.function.DoubleUnaryOperator py = v -> h - bottom - (Math.log10(v) - y0) / (y1 - y0) * (h - bottom - top);
        g.setStroke(Color.color(.6, .8, 1, .1));
        g.setLineWidth(1);
        g.setFont(Font.font("Segoe UI", 8.5));
        g.setFill(Color.web("#6F86A3"));
        g.setTextAlign(TextAlignment.LEFT);
        for (double t : new double[]{1, 10, 100, 1000}) {
            double x = px.applyAsDouble(t);
            g.strokeLine(x, top, x, h - bottom);
            g.fillText(t >= 1000 ? "1 MeV" : fmt(t) + " keV", x - 10, h - 5);
        }
        for (int e = 19; e <= 27; e += 2) {
            double y = py.applyAsDouble(Math.pow(10, e));
            g.strokeLine(left, y, w - right, y);
            g.fillText("10" + superscript(e), 2, y + 3);
        }
        plot(g, lawson.getCurveTKevList(), lawson.getCurveBreakevenList(), px, py, Color.web("#FFB547"), h - bottom, top);
        plot(g, lawson.getCurveTKevList(), lawson.getCurveIgnitionList(), px, py, Color.web("#FF5A4E"), h - bottom, top);
        double t = lawson.getTemperatureKev(), v = Math.max(1e18, lawson.getTripleProduct());
        double x = px.applyAsDouble(t), y = Math.max(top, Math.min(h - bottom, py.applyAsDouble(v)));
        g.setFill(lawson.getIgnited() ? Color.web("#7FE3B0") : Color.WHITE);
        g.fillOval(x - 4, y - 4, 8, 8);
        g.setFill(Color.web("#FF8A7E"));
        g.fillText("ignition", w - 46, top + 10);
        g.setFill(Color.web("#FFC870"));
        g.fillText("Q = 1", w - 46, top + 21);
        g.setFill(Color.web("#9FB4CE"));
        g.fillText("nτT", 3, top + 8);
    }

    private static void plot(GraphicsContext g, List<Double> ts, List<Double> vs, java.util.function.DoubleUnaryOperator px,
                             java.util.function.DoubleUnaryOperator py, Color color, double bottom, double top) {
        g.setStroke(color);
        g.setLineWidth(1.8);
        double[] prev = null;
        for (int i = 0; i < Math.min(ts.size(), vs.size()); i++) {
            double v = vs.get(i);
            if (v <= 0) { prev = null; continue; }
            double x = px.applyAsDouble(ts.get(i)), y = py.applyAsDouble(v);
            if (y < top || y > bottom) { prev = null; continue; }
            if (prev != null) g.strokeLine(prev[0], prev[1], x, y);
            prev = new double[]{x, y};
        }
    }

    private void updateCard(CollisionNucleus n, Label title, Label data, Button card) {
        title.setText(nuclide(n.getZ(), n.getA(), n.getSymbol()) + "  ·  " + n.getName());
        title.setStyle("-fx-text-fill:" + hex(FusionRenderer.bodyColor(n.getZ(), n.getA())) + ";-fx-font-size:16px;-fx-font-weight:bold;");
        styleCard(card, FusionRenderer.bodyColor(n.getZ(), n.getA()));
        title.setWrapText(false);
        data.setText(n.getZ() + " p⁺ / " + (n.getA() - n.getZ()) + " n  ·  charge +" + n.getChargeState() + "e  ·  r ≈ " + fmt(n.getRadiusFm()) + " fm\n"
                + "spin " + n.getSpinParity() + "  ·  μ " + (n.getMomentKnown() ? fmt(n.getMagneticMoment()) + " μN" : "inconnu") + "  ·  "
                + (n.getStable() ? "stable" : n.getHalfLifeS() > 0 ? "demi-vie " + duration(n.getHalfLifeS()) : "radioactif"));
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
    }

    private FusionRenderer.Snapshot portrait() {
        List<Body> bodies = new ArrayList<>();
        List<FusionRenderer.Look> looks = new ArrayList<>();
        double ra = selectedA == null ? 2.77 : selectedA.getRadiusFm(), rb = selectedB == null ? 2.27 : selectedB.getRadiusFm();
        double separation = 2.6 * (ra + rb) + 6;
        for (int i = 0; i < 2; i++) {
            CollisionNucleus n = i == 0 ? selectedA : selectedB;
            int z = n == null ? 1 : n.getZ(), a = n == null ? i + 2 : n.getA();
            bodies.add(new Body(z, a, n == null ? (i == 0 ? "D" : "T") : n.getLabel(), new V((i == 0 ? -.5 : .5) * separation, 0, 0), ZERO,
                    new V(i == 0 ? .5 : -.4, .9, .3).unit(), n == null ? (i == 0 ? 2.77 : 2.27) : n.getRadiusFm(),
                    n == null ? (i == 0 ? .857 : 2.979) : n.getMagneticMoment()));
            looks.add(new FusionRenderer.Look(n == null ? "" : n.getColorHex(), 0, n == null ? (i == 0 ? 1 : .5) : FusionRenderer.spinValue(n.getSpinParity()),
                    "nucleus", n == null ? (i == 0 ? "deutérium" : "tritium") : n.getName(), n == null ? (i + 2) * 931.5 : n.getMassMev()));
        }
        return new FusionRenderer.Snapshot(0, "ready", bodies, 0, 0, 0, 0, looks);
    }

    void seekTime(double time) {
        if (timeMap != null) seekProgress(timeMap.fractionAt(time));
    }

    void seekProgress(double fraction) {
        if (run != null) {
            progress = Math.max(0, Math.min(1, fraction));
            playing = false;
            pause.setText("▶");
        }
    }

    double playbackDuration() {
        return timeMap == null ? 0 : timeMap.durationSeconds();
    }

    double physicalTime() {
        return timeMap == null ? 0 : timeMap.timeAt(progress);
    }

    // ================================================================== l'animation
    private static final boolean FPS = Boolean.getBoolean("genesis.fps");
    private int frames;
    private long since = System.nanoTime();
    private double paintMs;

    public void tick(double dt) {
        if (FPS) {
            frames++;
            if (System.nanoTime() - since > 2e9) {
                System.out.printf("FPS %.1f paint %.1f ms stage=%s%n", frames / ((System.nanoTime() - since) / 1e9), paintMs / frames, renderer.report.stage);
                frames = 0;
                paintMs = 0;
                since = System.nanoTime();
            }
        }
        long started = System.nanoTime();
        tickInner(dt);
        paintMs += (System.nanoTime() - started) / 1e6;
    }

    private void tickInner(double dt) {
        double step = Math.max(0, Math.min(.1, dt));
        renderer.anim += step;
        renderer.camera.tick(step, dragging, -30, 30);
        if (!dragging) renderer.camera.yaw += step * .02;
        if (zoomVelocity != 0) {
            renderer.zoom = Math.max(.12, Math.min(6, renderer.zoom * Math.exp(zoomVelocity * step)));
            zoomVelocity *= Math.exp(-step * 6);
            if (Math.abs(zoomVelocity) < 1e-4) zoomVelocity = 0;
        }
        if (!Double.isNaN(zoomGoal)) {
            renderer.zoom += (zoomGoal - renderer.zoom) * Math.min(1, step * 3);
            if (Math.abs(zoomGoal - renderer.zoom) < 1e-3) zoomGoal = Double.NaN;
        }
        if (playing && timeMap != null) {
            progress = Math.min(1, progress + step * speed.getValue() / timeMap.durationSeconds());
            if (progress >= 1) {
                playing = false;
                pause.setText("▶");
                if (series.isSelected()) launch(false);
                else if (loop.isSelected()) { progress = 0; playing = true; pause.setText("Ⅱ"); }
            }
        }
        double w = sceneCanvas.getWidth(), h = sceneCanvas.getHeight();
        if (w < 2 || h < 2) return;
        GraphicsContext gc = sceneCanvas.getGraphicsContext2D();
        if (run != null) {
            renderer.paint(gc, w, h, run, timeMap, progress);
            updating = true;
            timeline.setValue(progress);
            updating = false;
        } else renderer.paintPortrait(gc, w, h, portrait);
        // Une fois la fusion faite (désexcitation, produits) ou le calcul fini : le bilan s'ouvre. Pendant la collision,
        // rien d'autre à dessiner : la vue principale zoome elle-même jusqu'au contact.
        if (settled()) {
            results.setResult(result());
            if (resultsWanted && !results.isShowing() && !renderer.report.recoil) openResults();
        } else if (results.isShowing()) quietlyHideResults();
        GraphicsContext overlay = overlayCanvas.getGraphicsContext2D();
        overlay.clearRect(0, 0, w, h);
        annotate(overlay, w, h, step);
    }

    // ================================================================== par-dessus la scène
    /**
     * Par-dessus la scène, seulement des calculs, sans encombrer : le tableau de bord (jauges en temps réel) à droite,
     * une étiquette compacte par noyau, l'échelle et l'horloge. Les équations et les explications
     * viennent à la demande (mode Inspecter).
     */
    private void annotate(GraphicsContext gc, double w, double h, double dt) {
        FusionRenderer.Report r = renderer.report;
        String stageName = r.stage;
        boolean contactRebound = run != null && run.getOutcome().equals("elastic") && timeMap != null
                && timeMap.phaseStart("contact") >= 0;
        phase.setText(run != null && stageName.equals("products") && run.getOutcome().equals("quasi_fission")
                ? "RÉSULTAT · QUASI-FISSION" : contactRebound && stageName.equals("contact")
                ? "CONTACT · AUCUN ÉTAT LIÉ" : contactRebound && stageName.equals("scattered")
                ? "REBOND · SÉPARATION ÉLASTIQUE" : stageTitle(stageName));
        outcome.setText(run == null ? identity(selectedA, "²H") + "  +  " + identity(selectedB, "³H")
                : stageName.equals("approach") || stageName.equals("tunnel") ? identity(selectedA, "A") + "  +  " + identity(selectedB, "B") : reaction(run));
        occupied.clear();
        if (results.panel().isVisible()) {
            javafx.geometry.Bounds b = results.panel().getBoundsInParent();
            occupied.add(new double[]{b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()});
        }
        // Le tableau de bord ; le bilan prend sa place une fois ouvert.
        if (!results.isShowing()) {
            FusionDashboard.Board board = run == null ? readouts.ready(selectedA, selectedB, requestedKev, barrierKev) : readouts.board(run, timeMap, r);
            if (board != null) {
                double bx = w - FusionDashboard.WIDTH - 16, by = 62;
                boolean compact = inspect.isSelected() || by + FusionDashboard.height(board, false) > h - 112;
                dashboard.draw(gc, bx, by, board, dt, compact);
                occupied.add(new double[]{bx, by, FusionDashboard.WIDTH, FusionDashboard.height(board, compact)});
            }
        }
        // La scène se cadre à gauche de ce qui couvre la droite (glissement doux : la caméra a de l'inertie).
        double cover = 0;
        for (double[] o : occupied) if (o[0] > w * .45) cover = Math.max(cover, w - o[0] + 8);
        renderer.insetRight += (cover - renderer.insetRight) * (1 - Math.exp(-Math.max(0, dt) / .35));
        nucleusCards(gc, w, h, r);
        if (!r.spinText.isEmpty() && r.spinSum != null) {
            gc.setFont(Font.font("Segoe UI Semibold", 13));
            gc.setFill(Color.web("#F2F7FF"));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText(r.spinText, r.spinSum[0] + 8, r.spinSum[1] - 6);
        }
        scaleAndClock(gc, w, h, r);
        if (inspect.isSelected()) inspection(gc, w, h, r);
    }

    /** Un rectangle touche-t-il ce qui est déjà posé (tableau de bord, bilan) ? */
    private boolean overlaps(double x, double y, double w, double h) {
        for (double[] o : occupied) if (x < o[0] + o[2] && x + w > o[0] && y < o[1] + o[3] && y + h > o[1]) return true;
        return false;
    }

    /** Une pastille de verre fumé, liserée de `accent`. */
    private static void pill(GraphicsContext gc, double x, double y, double w, double h, Color accent) {
        gc.setFill(Color.color(.03, .06, .11, .66));
        gc.fillRoundRect(x, y, w, h, h, h);
        gc.setStroke(accent.deriveColor(0, 1, 1, .42));
        gc.setLineWidth(1);
        gc.strokeRoundRect(x + .5, y + .5, w - 1, h - 1, h, h);
    }

    private static String stageTitle(String stage) {
        return switch (stage) {
            case "approach" -> "APPROCHE · L'ÉLAN DEVIENT RÉPULSION";
            case "tunnel" -> "EFFET TUNNEL · L'ONDE TRAVERSE LE MUR";
            case "merge" -> "FUSION · LA FORCE FORTE SOUDE LES DEUX NOYAUX";
            case "relax" -> "FUSION · LES DEUX FORMES SE FONDENT EN UNE";
            case "compound" -> "NOYAU COMPOSÉ · EXCITÉ";
            case "evaporation" -> "DÉSEXCITATION · ÉMISSIONS CALCULÉES";
            case "products" -> "RÉSULTAT · L'ÉLÉMENT CRÉÉ";
            case "scattered" -> "REBOND · LA RÉPULSION REND L'ÉLAN";
            case "contact" -> "CONTACT · LES DEUX NOYAUX TOURNENT ENSEMBLE";
            default -> "PRÉPARATION · TOUT LE TABLEAU PÉRIODIQUE";
        };
    }

    /**
     * Une étiquette compacte par noyau, sous lui : son symbole, Z, N, son spin — et E* s'il est chaud. Le reste (vitesse,
     * compression, forces) est au tableau de bord.
     */
    private void nucleusCards(GraphicsContext gc, double w, double h, FusionRenderer.Report r) {
        if (centralCard(gc, w, h, r)) return;
        List<FusionRenderer.Tag> tags = new ArrayList<>(r.tags);
        boolean crowded = tags.size() > 6;
        tags.sort(Comparator.comparingDouble(FusionRenderer.Tag::x));
        List<double[]> placed = new ArrayList<>(occupied);
        int outside = 0;
        Font big = Font.font("Segoe UI Semibold", 13.5), small = Font.font("Segoe UI", 10.5);
        for (FusionRenderer.Tag t : tags) {
            Body body = t.body();
            FusionRenderer.Look look = t.look();
            if (t.alpha() < .5) continue;
            if (t.x() < 0 || t.x() > w || t.y() < 0 || t.y() > h) {
                if (body.a() > 4 && outside < 4) {
                    gc.setFont(Font.font("Segoe UI", 10.5));
                    gc.setFill(Color.web("#8FA7C4"));
                    gc.setTextAlign(TextAlignment.LEFT);
                    gc.fillText(symbolOf(body) + " → hors du cadre", 20, 150 + outside++ * 14);
                }
                continue;
            }
            if (crowded && body.a() <= 4) continue;
            Color color = FusionRenderer.bodyColor(body.z(), body.a());
            String symbol = symbolOf(body);
            String data = body.z() == 0 ? "neutre" : "Z " + body.z() + " · N " + (body.a() - body.z());
            if (look != null && look.spin() > 0) data += " · J " + spinText(look.spin());
            String hot = look != null && look.excitation() > .05 ? "E* " + fmt(look.excitation()) + " MeV" : "";
            double sw = width(symbol, big), dw = width(data, small), hw = hot.isEmpty() ? 0 : width(hot, small) + 12;
            double bw = 12 + sw + 8 + dw + hw + 12, bh = 24;
            double x = t.x() - bw / 2, y = t.y() + Math.max(t.radius(), 4) + 10;
            y = Math.max(60, Math.min(h - bh - 104, y));
            for (double[] o : placed) {
                if (!(x < o[0] + o[2] + 6 && x + bw > o[0] - 6 && y < o[1] + o[3] && y + bh > o[1])) continue;
                double left = o[0] - bw - 8, right = o[0] + o[2] + 8;
                x = right + bw > w - 8 || Math.abs(left - x) < Math.abs(right - x) && left >= 8 ? left : right;
            }
            x = Math.max(8, Math.min(w - bw - 8, x));
            placed.add(new double[]{x, y, bw, bh});
            pill(gc, x, y, bw, bh, color);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFont(big);
            gc.setFill(color.interpolate(Color.WHITE, .15));
            gc.fillText(symbol, x + 12, y + 17);
            gc.setFont(small);
            gc.setFill(Color.web("#B4C6DC"));
            gc.fillText(data, x + 12 + sw + 8, y + 16);
            if (!hot.isEmpty()) {
                gc.setFill(Color.web("#FFB27A"));
                gc.fillText(hot, x + 12 + sw + 8 + dw + 12, y + 16);
            }
        }
    }

    /**
     * Après la fusion, la vue centrale ne présente que l'élément central créé (le plus lourd) : sa fiche complète, à
     * côté de lui. Les autres produits sont projetés sans étiquette. Faux si l'étape n'est pas celle du résultat.
     */
    private boolean centralCard(GraphicsContext gc, double w, double h, FusionRenderer.Report r) {
        if (run == null || r.recoil || !(r.stage.equals("compound") || r.stage.equals("evaporation") || r.stage.equals("products"))) return false;
        FusionRenderer.Tag heaviest = null, chosen = null;
        for (FusionRenderer.Tag t : r.tags) {
            if (t.alpha() < .5) continue;
            if (chosen == null && t.body().z() == renderer.focusZ && t.body().a() == renderer.focusA) chosen = t;
            if (t.body().z() > 0 && (heaviest == null || t.body().a() > heaviest.body().a())) heaviest = t;
        }
        // Un photon choisi n'a pas de fiche ici : le bilan la montre déjà.
        if (renderer.focusA == 0 && renderer.focusZ == 0) return true;
        FusionRenderer.Tag central = chosen != null ? chosen : heaviest;
        if (central == null) return true;
        Body b = central.body();
        FusionRenderer.Look look = central.look();
        double mass = look == null || look.mass() <= 0 ? b.a() * 931.494 : look.mass();
        double excitation = look == null ? 0 : look.excitation(), beta = Math.min(.999999, b.velocity().norm() / C);
        double kinetic = (1 / Math.sqrt(1 - beta * beta) - 1) * mass;
        List<String> lines = properties(look == null ? "" : look.kind(), b.z(), b.a(), b.radius(), b.moment(), "", look == null ? 0 : look.spin(), mass, excitation, beta, kinetic);
        String name = look == null || look.name().isBlank() ? b.label() : look.name();
        double boxW = 318, boxH = 34 + 15 * lines.size(), radius = Math.max(central.radius(), 6);
        double x = central.x() + radius + 30, y = Math.max(60, Math.min(h - boxH - 104, central.y() - boxH / 2));
        // Du côté libre : à gauche si le bilan ou le tableau de bord occupent la droite, ou si l'écran s'arrête.
        if (overlaps(x, y, boxW, boxH) || x + boxW > w - 8) x = Math.max(8, central.x() - radius - 30 - boxW);
        Color color = FusionRenderer.bodyColor(b.z(), b.a());
        gc.setStroke(color.deriveColor(0, 1, 1, .5));
        gc.setLineWidth(1);
        double edge = x > central.x() ? central.x() + radius : central.x() - radius;
        gc.strokeLine(edge, central.y(), x > central.x() ? x : x + boxW, y + 20);
        FusionDashboard.glass(gc, x, y, boxW, boxH, color);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 17));
        gc.setFill(color);
        String symbol = symbolOf(b);
        gc.fillText(symbol, x + 14, y + 25);
        gc.setFont(Font.font("Segoe UI", 12));
        gc.setFill(Color.web("#E6EEFA"));
        boolean fragmented = run != null && (run.getOutcome().equals("quasi_fission") || run.getOutcome().equals("fission"));
        String role = central != heaviest ? "projeté par la réaction" : r.stage.equals("compound") ? "noyau composé"
                : fragmented ? "fragment de la rupture" : "créé par la fusion";
        gc.fillText(name + "  ·  " + role, x + 22 + width(symbol, Font.font("Segoe UI Semibold", 17)), y + 24);
        gc.setFont(Font.font("Segoe UI", 11));
        double row = y + 44;
        for (String line : lines) {
            gc.setFill(Color.web(line.startsWith("excité") ? "#FFB27A" : line.startsWith("vitesse") ? "#9FE0FF" : "#B9C9DF"));
            gc.fillText(line, x + 14, row, boxW - 24);
            row += 15;
        }
        return true;
    }

    /** La loi de l'instant reste disponible exclusivement dans Inspecter. */
    private String sceneEquation(FusionRenderer.Report r) {
        return run == null ? FusionReadouts.readyEquation(selectedA, selectedB, requestedKev, barrierKev)
                : readouts.equation(run, timeMap, r);
    }

    /** Le mode Inspecter, sonde posée en (x, y) — pour les aperçus et les tests ; la lecture continue. */
    void inspectAt(double x, double y) {
        inspect.setSelected(true);
        probe = new double[]{x, y};
    }

    /** Un clic en mode Inspecter : la sonde se pose (et la lecture se fige pour lire) ; sur la sonde, elle se retire. */
    private void pin(double x, double y) {
        if (probe != null && Math.hypot(x - probe[0], y - probe[1]) < 10) {
            probe = null;
            return;
        }
        probe = new double[]{x, y};
        if (playing) {
            playing = false;
            pause.setText("▶");
        }
    }

    /**
     * Le mode Inspecter : sous la souris, le nom de la zone ; là où la sonde est posée, les vecteurs du champ (E de chaque
     * noyau à sa couleur, leur somme en blanc) et la fiche du lieu — ce qu'il est, les équations, leurs valeurs.
     */
    private void inspection(GraphicsContext gc, double w, double h, FusionRenderer.Report r) {
        if (hoverX >= 0 && (probe == null || Math.hypot(hoverX - probe[0], hoverY - probe[1]) > 14)) {
            FusionReadouts.Card c = readouts.probe(r, hoverX, hoverY);
            String text = c.zone() + (probe == null ? "   ·   clic : sonder ici" : "   ·   clic : déplacer la sonde");
            Font f = Font.font("Segoe UI Semibold", 10.5);
            double tw = Math.min(width(text, f), 380), x = Math.max(8, Math.min(w - tw - 26, hoverX + 18)), y = Math.max(72, hoverY - 16);
            pill(gc, x - 9, y - 14, tw + 18, 21, c.accent());
            gc.setFont(f);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFill(Color.web("#EAF2FF"));
            gc.fillText(text, x, y + 1, 380);
        }
        boolean pinned = probe != null;
        double px = pinned ? probe[0] : 20, py = pinned ? probe[1] : 158;
        FusionReadouts.Card c;
        if (pinned) c = readouts.probe(r, px, py);
        else {
            List<String> theory = new ArrayList<>(SimCanvas.wrap(sceneEquation(r), 55));
            if (run != null) {
                theory.addAll(SimCanvas.wrap("Modèle : " + run.getModel(), 55));
                theory.add("Erreur relative d'énergie : " + String.format(Locale.ROOT, "%.1e", run.getEnergyError()));
            }
            c = new FusionReadouts.Card("PHYSIQUE DE L'INSTANT", captionText(r), theory, Color.web("#36CDF2"), List.of());
        }
        if (pinned) {
            for (double[] v : c.vectors()) {
                double mag = Math.hypot(v[0], v[1]);
                if (mag <= 0) continue;
                double len = Math.max(10, Math.min(60, 12 + 9 * Math.log10(mag / 1e17)));
                vectorGlyph(gc, px, py, v[0] / mag * len, v[1] / mag * len, Color.color(v[2], v[3], v[4]));
            }
            double pulse = 1 + .12 * Math.sin(renderer.anim * 4);
            gc.setStroke(c.accent().deriveColor(0, 1, 1, .95));
            gc.setLineWidth(1.3);
            gc.strokeOval(px - 7 * pulse, py - 7 * pulse, 14 * pulse, 14 * pulse);
            gc.setStroke(c.accent().deriveColor(0, 1, 1, .95));
            gc.setLineWidth(1.1);
            for (int k = 0; k < 4; k++) {
                double a = k * Math.PI / 2;
                gc.strokeLine(px + 10 * Math.cos(a), py + 10 * Math.sin(a), px + 15 * Math.cos(a), py + 15 * Math.sin(a));
            }
            gc.setFill(Color.WHITE);
            gc.fillOval(px - 1.5, py - 1.5, 3, 3);
        }
        List<String> about = SimCanvas.wrap(c.about(), 60);
        double cw = 372, ch = 44 + 14.5 * about.size() + 16.5 * c.lines().size() + 26;
        // La fiche se pose là où elle cache le moins : ni le tableau de bord, ni le point sondé, ni les noyaux.
        List<double[]> blockers = new ArrayList<>();
        for (double[] o : occupied) blockers.add(new double[]{o[0], o[1], o[2], o[3], 20});
        if (pinned) blockers.add(new double[]{px - 20, py - 20, 40, 40, 20});
        for (FusionRenderer.Tag t : r.tags) {
            if (t.alpha() < .3) continue;
            double rr = Math.max(t.radius(), 8) + 8;
            blockers.add(new double[]{t.x() - rr, t.y() - rr, 2 * rr, 2 * rr, 1});
        }
        double[][] spots = {{8, 158}, {w - cw - 8, 158}, {8, h - ch - 108}, {w - cw - 8, h - ch - 108},
                {px + 38, py - ch / 2}, {px - 38 - cw, py - ch / 2}, {px - cw / 2, py + 38}, {px - cw / 2, py - 38 - ch},
                {px + 38, py + 26}, {px - 38 - cw, py + 26}, {px + 38, py - 26 - ch}, {px - 38 - cw, py - 26 - ch}};
        double cx = 8, cy = 62, best = Double.MAX_VALUE;
        for (double[] spot : spots) {
            double x = Math.max(8, Math.min(w - cw - 8, spot[0])), y = Math.max(62, Math.min(h - ch - 104, spot[1])), cost = 0;
            for (double[] b : blockers) {
                double ox = Math.min(x + cw, b[0] + b[2]) - Math.max(x, b[0]), oy = Math.min(y + ch, b[1] + b[3]) - Math.max(y, b[1]);
                if (ox > 0 && oy > 0) cost += ox * oy * b[4];
            }
            cost += Math.abs(x - spot[0]) + Math.abs(y - spot[1]);
            if (y < 145) cost += 1200;
            if (x > 24 && x + cw < w - 24) cost += 850;
            if (cost < best) {
                best = cost;
                cx = x;
                cy = y;
            }
        }
        double ex = Math.max(cx, Math.min(cx + cw, px)), ey = Math.max(cy, Math.min(cy + ch, py));
        gc.setStroke(c.accent().deriveColor(0, 1, 1, .5));
        gc.setLineWidth(1);
        double gap = Math.hypot(ex - px, ey - py);
        if (pinned && gap > 16) gc.strokeLine(px + (ex - px) * 15 / gap, py + (ey - py) * 15 / gap, ex, ey);
        FusionDashboard.glass(gc, cx, cy, cw, ch, c.accent());
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 10));
        gc.setFill(Color.web("#7FA6CF"));
        gc.fillText(FusionDashboard.spaced(pinned ? "SONDE" : "THÉORIE"), cx + 16, cy + 19);
        gc.setFont(Font.font("Segoe UI Semibold", 13.5));
        gc.setFill(c.accent().interpolate(Color.WHITE, .3));
        gc.fillText(c.zone(), cx + 16, cy + 37, cw - 30);
        double y = cy + 54;
        gc.setFont(Font.font("Segoe UI", 10.5));
        gc.setFill(Color.web("#9FB4CE"));
        for (String line : about) {
            gc.fillText(line, cx + 16, y, cw - 30);
            y += 14.5;
        }
        y += 6;
        gc.setFont(Font.font("Segoe UI", 11.5));
        for (String line : c.lines()) {
            gc.setFill(Color.web("#5FD3F5", .8));
            gc.fillOval(cx + 16, y - 6, 3, 3);
            gc.setFill(Color.web("#E2EDFA"));
            gc.fillText(line, cx + 25, y, cw - 40);
            y += 16.5;
        }
        gc.setFont(Font.font("Segoe UI", 9.5));
        gc.setFill(Color.web("#6F86A3"));
        gc.fillText(pinned ? "clic sur la sonde : revenir à la théorie · ▶ : reprendre"
                : "cliquer dans la scène : sonder le champ à cet endroit", cx + 16, cy + ch - 10, cw - 30);
    }

    /** Un vecteur du champ au point sondé (longueur logarithmique). */
    private static void vectorGlyph(GraphicsContext gc, double x, double y, double dx, double dy, Color c) {
        double len = Math.hypot(dx, dy);
        if (len < 2) return;
        double ux = dx / len, uy = dy / len, x1 = x + dx, y1 = y + dy, head = Math.min(7, len * .35);
        gc.setStroke(c.deriveColor(0, 1, 1, .9));
        gc.setLineWidth(1.6);
        gc.strokeLine(x, y, x1 - ux * head * .6, y1 - uy * head * .6);
        gc.setFill(c);
        gc.fillPolygon(new double[]{x1, x1 - ux * head - uy * head * .45, x1 - ux * head + uy * head * .45},
                new double[]{y1, y1 - uy * head + ux * head * .45, y1 - uy * head - ux * head * .45}, 3);
    }

    private String captionText(FusionRenderer.Report r) {
        if (run == null) {
            return selectedA == null ? "" : "Deux noyaux nus, à la même échelle. Autour de chacun, son champ électrique : un halo à sa couleur "
                    + "(selon sa charge), parcouru de grains qui en suivent le sens ; ceux qui ont un spin sont de petits aimants (comètes "
                    + "bleu glacier). Règle le choc ou le plasma, puis lance ; un clic dans la scène sonde le champ.";
        }
        String a = identity(selectedA, "A");
        return switch (r.stage) {
            case "approach" -> "Approche : chaque noyau baigne dans son champ (à sa couleur). La répulsion — " + force(r.coulomb) + " à "
                    + fmt(r.separation) + " fm — change l'élan K en tension V (le tableau de bord suit ce partage) : ils freinent. Là où "
                    + "les champs se heurtent, la zone blanchit et les grains s'y arrêtent.";
            case "tunnel" -> run.getEnergyKev() / 1000 < run.getBarrierMev() * .98
                    ? "Le mur de Coulomb : il faudrait " + energy(run.getBarrierMev() * 1000) + ", ils n'ont que " + energy(run.getEnergyKev())
                    + ". L'onde de " + a + " s'enfonce dans le mur et s'y éteint presque — pas tout à fait : elle se matérialise de l'autre côté"
                    + (run.getProbability() < .5 ? " (probabilité de ce tir : " + probability(run.getProbability()) + ")." : ".")
                    : "Au sommet du mur de Coulomb (" + energy(run.getBarrierMev() * 1000) + ") : l'élan est épuisé à quelques femtomètres du contact ; "
                    + "l'onde de " + a + " finit le trajet (probabilité de ce tir : " + probability(run.getProbability()) + ").";
            case "merge" -> timeMap != null && timeMap.computedMerge()
                    ? "Soudure calculée : la force forte (" + force(r.strong) + ") tire les deux noyaux l'un dans l'autre ; la friction nucléaire "
                    + "change leur élan en chaleur (" + fmt(r.heat) + " MeV). Leurs champs électriques se fondent en un seul, de charge +"
                    + run.getCompoundZ() + "e."
                    : "Contact : la force forte (" + force(r.strong) + ") l'emporte sur la répulsion et soude les "
                    + (selectedA == null ? "" : (selectedA.getA() + selectedB.getA()) + " ") + "nucléons ; un champ de charge +"
                    + run.getCompoundZ() + "e remplace les deux.";
            case "relax" -> "Noyaux lourds : à l'intérieur, la répulsion des " + run.getCompoundZ() + " protons arrête la soudure calculée au contact "
                    + "(système dinucléaire, chauffé de " + fmt(r.heat) + " MeV). Les deux formes finissent de fondre en une — étape lente, non tracée.";
            case "compound" -> "Noyau composé : excité de " + fmt(run.getExcitationMev()) + " MeV. Les spins s'additionnent (J = j₁ + j₂ + ℓ). "
                    + "Il va se refroidir en émettant des particules.";
            case "evaporation" -> "Il se refroidit : chaque éclair est une émission calculée (évaporation de Weisskopf"
                    + (run.getOutcome().equals("fission") ? ", fission de Bohr-Wheeler" : "") + "), avec recul et conservation de l'énergie.";
            case "products" -> "Résultat : " + centralName() + " au centre, avec son spin et ses champs électrique et magnétique calculés. "
                    + "Les autres produits sont projetés (" + productSpeeds() + ")" + (results.isShowing() ? " — le bilan complet est à droite ; un clic sur un élément y amène la vue." : ".");
            case "scattered" -> "Renvoyés : la répulsion rend l'élan. " + (run.getEnergyKev() / 1000 < run.getBarrierMev()
                    ? "L'onde s'était enfoncée dans le mur, mais ce coup-ci elle n'est pas passée (probabilité " + probability(run.getProbability()) + ")."
                    : "Ils ont frôlé le contact sans être capturés.");
            case "contact" -> run.getOutcome().equals("elastic")
                    ? "Contact sans état lié : le système se sépare en reprenant les vitesses sortantes calculées, avec conservation de l'énergie et de la quantité de mouvement."
                    : run.getCompoundZ() > 118
                    ? "Les deux noyaux se touchent et tournent ensemble quelques 10⁻²¹ s : au-delà de Z = 118, aucun noyau composé ne tient "
                    + "(quasi-fission). Ils se séparent après un échange de nucléons."
                    : "Les deux noyaux se touchent ; le modèle n'a pas de voie de sortie pour ce noyau composé : l'issue reste non résolue.";
            default -> "";
        };
    }

    private void scaleAndClock(GraphicsContext gc, double w, double h, FusionRenderer.Report r) {
        double px = r.pxPerFm;
        if (px <= 0) return;
        double target = 120 / px, exp = Math.floor(Math.log10(target)), base = Math.pow(10, exp), length = base;
        for (double v : new double[]{1, 2, 5}) if (v * base <= target) length = v * base;
        double bar = length * px, x = 20, y = 92;
        gc.setStroke(Color.web("#E8F0FF", .85));
        gc.setLineWidth(1.2);
        gc.strokeLine(x, y, x + bar, y);
        gc.strokeLine(x, y - 5, x, y + 5);
        gc.strokeLine(x + bar, y - 5, x + bar, y + 5);
        gc.setFont(Font.font("Segoe UI", 10.5));
        gc.setFill(Color.web("#E8F0FF"));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(lengthText(length * 1e-15) + " · échelle réelle", x, y - 9);
        if (renderer.showElectric && r.pressureMax > 0)
            pressureLegend(gc, x, run == null ? y + 22 : y + 50, r.pressureMin, r.pressureMax);
        if (run == null || timeMap == null) return;
        double t = timeMap.timeAt(progress);
        boolean staged = r.stage.equals("merge") && !timeMap.computedMerge();
        String clock = r.stage.equals("tunnel") ? "t ≈ 0 · l'effet tunnel n'a pas de durée classique"
                : staged ? "t ≈ 0 · la soudure prend ~10⁻²² s" : r.stage.equals("relax") ? "fonte des formes : non tracée"
                : "t = " + scientific(t) + " s";
        gc.setFont(Font.font("Segoe UI", 12));
        gc.fillText(clock, x, y + 20);
        double d = 1e-3, rate = Math.abs(timeMap.timeAt(Math.min(1, progress + d)) - timeMap.timeAt(Math.max(0, progress - d))) / (2 * d) / timeMap.durationSeconds();
        if (rate > 0 && !r.stage.equals("tunnel") && !staged && !r.stage.equals("relax")) {
            gc.setFont(Font.font("Segoe UI", 10.5));
            gc.setFill(Color.web("#9FB4CE"));
            gc.fillText("ralenti ×10" + superscript((int) Math.round(-Math.log10(rate))) + " : 1 s ici = " + scientific(rate) + " s", x, y + 35);
        }
    }

    /** La légende de la brume : teinte selon Z (de qui vient le champ), éclat ε₀E²/2 en log, blanc où les champs se heurtent. */
    private void pressureLegend(GraphicsContext gc, double x, double y, double min, double max) {
        double w = 120;
        javafx.scene.paint.Stop[] stops = new javafx.scene.paint.Stop[5];
        double[] zs = {1, 4, 10, 30, 92};
        for (int i = 0; i < 5; i++) stops[i] = new javafx.scene.paint.Stop(i / 4.0, FusionRenderer.haloColor(zs[i], 0));
        gc.setFill(new javafx.scene.paint.LinearGradient(x, 0, x + w, 0, false, javafx.scene.paint.CycleMethod.NO_CYCLE, stops));
        gc.fillRoundRect(x, y, w, 6, 3, 3);
        gc.setFont(Font.font("Segoe UI", 10));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(Color.web("#BFCBDD"));
        gc.fillText("Z = 1", x, y + 18);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("92", x + w, y + 18);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("halo ε₀E²/2, teinte selon Z · blanc : champs opposés", x + w + 10, y + 7);
    }

    private CollisionRun resultRun;
    private ResultView.Result resultCache;

    /**
     * Le bilan interactif, calculé une fois par tir : chaque corps à la fin du calcul (ce qu'il est, son énergie, sa
     * vitesse, sa direction de vol dans le plan du choc, sa fiche), et ce qu'il faut pour chiffrer l'énergie.
     */
    private ResultView.Result result() {
        if (run == resultRun && resultCache != null) return resultCache;
        resultRun = run;
        CollisionSample first = run.getSamples(0), last = run.getSamples(run.getSamplesCount() - 1);
        boolean elastic = run.getOutcome().equals("elastic") || run.getOutcome().equals("contact_unresolved") && !run.getFused();
        boolean touched = run.getSamplesList().stream().anyMatch(s -> s.getPhase().equals("contact"));
        String title = switch (run.getOutcome()) {
            case "fission" -> "BILAN · FUSION, PUIS FISSION";
            case "quasi_fission" -> "BILAN · QUASI-FISSION";
            case "elastic" -> touched ? "BILAN · REBOND AU CONTACT" : "BILAN · PAS DE FUSION";
            default -> run.getFused() ? "BILAN · FUSION" : "BILAN · ISSUE NON RÉSOLUE";
        };
        CollisionBody central = null;
        if (!elastic) for (CollisionBody b : last.getBodiesList()) if (b.getZ() > 0 && b.getA() > 0 && (central == null || b.getA() > central.getA())) central = b;
        List<ResultView.Particle> particles = new ArrayList<>();
        int nucleonsIn = 0, chargeIn = 0, nucleonsOut = 0, chargeOut = 0;
        double fuel = 0;
        for (CollisionBody b : first.getBodiesList()) {
            nucleonsIn += b.getA();
            chargeIn += b.getZ();
            fuel += b.getMassMev() > 0 ? b.getMassMev() : b.getA() * 931.494;
        }
        for (CollisionBody b : last.getBodiesList()) {
            double beta = speedOf(b);
            String kind = b.getA() == 0 ? (b.getKind().equals("positron") || b.getKind().equals("neutrino") ? b.getKind() : "gamma")
                    : b.getZ() == 0 ? "neutron" : "nucleus";
            List<String> lines = properties(b.getKind(), b.getZ(), b.getA(), b.getRadiusFm(), b.getMagneticMoment(), b.getSpinParity(),
                    FusionRenderer.spinValue(b.getSpinParity()), b.getMassMev(), b.getExcitationMev(), beta, b.getKineticMev());
            String color = switch (kind) {
                case "positron" -> "#FF7AD9";
                case "neutrino" -> "#B8F5C8";
                case "gamma" -> b.getColorHex().isBlank() ? "#FFD36A" : b.getColorHex();
                default -> hex(FusionRenderer.bodyColor(b.getZ(), b.getA()));
            };
            String name = switch (kind) {
                case "gamma" -> "photon γ";
                case "positron" -> "positon (antiélectron)";
                case "neutrino" -> "neutrino électronique";
                default -> b.getName().isBlank() ? symbolOfBody(b) : b.getName();
            };
            boolean fragmented = run.getOutcome().equals("quasi_fission") || run.getOutcome().equals("fission");
            String role = kind.equals("gamma") ? "lumière" : kind.equals("neutrino") ? "perdu" : elastic ? "renvoyé"
                    : fragmented && b == central ? "fragment principal" : fragmented && b.getA() >= 5 ? "fragment"
                    : b == central || b.getZ() > 0 && b.getA() >= 5 ? "créé" : "éjecté";
            String state = switch (kind) {
                case "gamma", "neutrino" -> "file à c";
                case "positron" -> "s'annihile au premier électron";
                case "neutron" -> b.getA() == 1 ? "libre : ≈ 15 min" : stability(b.getZ(), b.getA());
                default -> stability(b.getZ(), b.getA());
            };
            double vx = b.getVelocityCount() >= 3 ? b.getVelocity(0) : 0, vy = b.getVelocityCount() >= 3 ? b.getVelocity(1) : 0;
            particles.add(new ResultView.Particle(symbolOfBody(b), name, color, kind, role, b.getZ(), b.getA(), b.getKineticMev(), beta,
                    vx, -vy, state, lines, b == central));
            nucleonsOut += b.getA();
            chargeOut += b.getZ();
        }
        // Le rebond : l'angle entre les vitesses relatives d'avant et d'après.
        double deflection = 0;
        if (first.getBodiesCount() == 2 && last.getBodiesCount() == 2) {
            double[] before = relativeVelocity(first), after = relativeVelocity(last);
            double nb = Math.sqrt(before[0] * before[0] + before[1] * before[1] + before[2] * before[2]);
            double na = Math.sqrt(after[0] * after[0] + after[1] * after[1] + after[2] * after[2]);
            if (nb > 0 && na > 0) deflection = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
                    (before[0] * after[0] + before[1] * after[1] + before[2] * after[2]) / (nb * na)))));
        }
        String fuelName = first.getBodiesCount() >= 2 ? symbolOfBody(first.getBodies(0)) + " + " + symbolOfBody(first.getBodies(1)) : reaction(run);
        resultCache = new ResultView.Result(title, reaction(run), elastic, touched, run.getQMev(), run.getEnergyKev() / 1000, run.getExcitationMev(),
                run.getProbability(), fuel, fuelName, run.getCompoundDurationS(), nucleonsIn, chargeIn, nucleonsOut, chargeOut,
                run.getEnergyError(), deflection, particles);
        return resultCache;
    }

    private static double[] relativeVelocity(CollisionSample s) {
        CollisionBody a = s.getBodies(0), b = s.getBodies(1);
        if (a.getVelocityCount() < 3 || b.getVelocityCount() < 3) return new double[3];
        return new double[]{a.getVelocity(0) - b.getVelocity(0), a.getVelocity(1) - b.getVelocity(1), a.getVelocity(2) - b.getVelocity(2)};
    }

    private static double speedOf(CollisionBody b) {
        if (b.getVelocityCount() < 3) return 0;
        return Math.min(.999999, Math.sqrt(b.getVelocity(0) * b.getVelocity(0) + b.getVelocity(1) * b.getVelocity(1) + b.getVelocity(2) * b.getVelocity(2)) / C);
    }

    private String symbolOfBody(CollisionBody b) {
        if (b.getA() == 0) return b.getKind().equals("positron") ? "e⁺" : b.getKind().equals("neutrino") ? "νₑ" : "γ";
        return b.getZ() == 0 ? "n" : nuclide(b.getZ(), b.getA(), symbolOfZ(b.getZ()));
    }

    /** Les propriétés d'un noyau, d'un neutron ou d'un photon, calculées à partir de ce que le moteur envoie. */
    private List<String> properties(String kind, int z, int a, double radius, double moment, String spinParity, double spin, double mass,
                                    double excitation, double beta, double kinetic) {
        List<String> out = new ArrayList<>();
        if ("positron".equals(kind)) {
            out.add("antiparticule de l'électron : charge +1e, masse 0,511 MeV/c²");
            out.add("créé quand un proton devient neutron (interaction faible, β⁺)");
            out.add("spin 1/2 · moment magnétique ≈ 1 magnéton de Bohr (1 838 μN)");
            out.add("vitesse " + ResultView.speed(beta) + " · énergie cinétique " + energy(kinetic * 1000));
            out.add("au premier électron rencontré : annihilation en deux photons γ de 511 keV");
            return out;
        }
        if ("neutrino".equals(kind)) {
            out.add("neutre, masse < 0,8 eV/c² : il file pratiquement à c");
            out.add("énergie " + energy(kinetic * 1000) + " emportée hors du réacteur (ou du Soleil)");
            out.add("n'interagit que par l'interaction faible : il traverserait la Terre sans s'arrêter");
            out.add("spin 1/2 · hélicité gauche");
            return out;
        }
        if (a == 0) {
            out.add("énergie " + energy(kinetic * 1000) + " = " + scientificShort(kinetic * 1.602176634e-13) + " J");
            out.add("longueur d'onde λ = hc/E = " + lengthText(1239.84198 / Math.max(kinetic, 1e-12) * 1e-15));
            out.add("fréquence ν = E/h = " + scientificShort(kinetic * 1.602176634e-13 / 6.62607015e-34) + " Hz");
            out.add("lumière γ, invisible et très pénétrante : sans masse ni charge, elle file à c");
            return out;
        }
        int n = a - z;
        double rest = mass - excitation;
        out.add(z + " proton" + (z > 1 ? "s" : "") + " · " + n + " neutron" + (n > 1 ? "s" : "") + " · A = " + a);
        out.add("masse " + String.format(Locale.FRANCE, "%,.2f", rest) + " MeV/c² = " + String.format(Locale.FRANCE, "%.4f", rest / 931.49410242) + " u");
        if (z > 0 && a > 1) {
            double binding = z * 938.27208816 + n * 939.56542052 - rest;
            out.add("énergie de liaison " + fmt(binding) + " MeV · " + fmt(binding / a) + " MeV par nucléon");
        }
        if (z > 0) {
            out.add("charge +" + z + "e · rayon " + fmt(radius) + " fm");
            if (radius > 0) out.add("champ électrique en surface ≈ " + scientificShort(1.43996e21 * z / (radius * radius)) + " V/m");
        } else out.add("neutre : aucun champ électrique");
        String sp = spinParity == null || spinParity.isBlank() ? spinText(spin) : spinParity;
        if (spin > 0 && Math.abs(moment) > 1e-6) {
            out.add("spin " + sp + " · moment magnétique μ = " + fmt(moment) + " μN");
            if (radius > 0) out.add("champ magnétique au pôle ≈ " + scientificShort(1.0102e12 * Math.abs(moment) / Math.pow(radius, 3)) + " T");
        } else out.add("spin " + sp + (spin > 0 ? "" : " : pas de champ magnétique"));
        out.add(z == 0 && a == 1 ? "libre, il se désintègre en ~15 min (proton + électron + antineutrino)" : stability(z, a));
        if (excitation > .05) out.add("excité : E* = " + fmt(excitation) + " MeV · T ≈ " + fmt(Math.sqrt(8 * excitation / Math.max(1, a))) + " MeV");
        if (beta > 1e-7) {
            out.add("vitesse " + ResultView.speed(beta) + " · énergie cinétique " + energy(kinetic * 1000));
            if (z > 0 && run != null && run.getFieldT() > 0) {
                double p = rest * beta / Math.sqrt(1 - beta * beta);
                out.add("dans " + fmt(run.getFieldT()) + " T : cercle de Lorentz r = p/qB = " + lengthText(3.33564095e-3 * p / (z * run.getFieldT())));
            }
        }
        return out;
    }

    private String centralName() {
        CollisionSample last = run.getSamples(run.getSamplesCount() - 1);
        CollisionBody central = null;
        for (CollisionBody b : last.getBodiesList()) if (b.getZ() > 0 && (central == null || b.getA() > central.getA())) central = b;
        return central == null ? "le produit" : symbolOfBody(central) + " (" + central.getName() + ")";
    }

    /** Les vitesses des autres produits (sans l'élément central), à la fin. */
    private String productSpeeds() {
        CollisionSample last = run.getSamples(run.getSamplesCount() - 1);
        CollisionBody central = null;
        for (CollisionBody b : last.getBodiesList()) if (b.getZ() > 0 && (central == null || b.getA() > central.getA())) central = b;
        List<String> parts = new ArrayList<>();
        for (CollisionBody b : last.getBodiesList()) {
            if (b == central || parts.size() == 3) continue;
            boolean light = b.getA() == 0 && !b.getKind().equals("positron");
            parts.add(symbolOfBody(b) + (light ? " à c" : " à " + fmt(100 * speedOf(b)) + " % de c"));
        }
        return parts.isEmpty() ? "aucun" : String.join(", ", parts);
    }

    private static String reaction(CollisionRun run) {
        return run.getReaction().replace("->", "→").replace("gamma", "γ");
    }

    private void model() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Modèle et données");
        alert.setHeaderText("Collisions de noyaux entièrement ionisés, et le plasma où elles ont lieu");
        alert.setContentText("Tailles et distances à la même échelle (rayons de charge mesurés ; aucun grossissement).\n\n"
                + "Approche : Coulomb + Woods–Saxon, impulsions relativistes, force de Lorentz (DOP853), depuis 12 fois la distance de "
                + "rebroussement (la répulsion n'y vaut que ~8 % de l'énergie). La lecture suit le temps physique : on voit les noyaux traverser "
                + "la carte lancés, puis freiner contre la répulsion.\n\n"
                + "La vue zoome à mesure qu'ils se rapprochent (∝ r^0,75) jusqu'au contact ; une fois la fusion faite, elle ne présente "
                + "que l'élément créé, et le bilan s'ouvre (un élément choisi y amène la vue). Les grains du fond sont immobiles dans le "
                + "repère du centre de masse : ils filent quand la caméra bouge.\n\n"
                + "Par-dessus la scène, seulement des calculs : le tableau de bord suit l'instant (élan K et tension V, force de répulsion, "
                + "distance, vitesse, η, compression β₂ ; puis force forte, chaleur de friction, recouvrement ; E*, température √(8E*/A), "
                + "pression ⅔E*/V ; Q et énergie emportée), la ligne du bas donne la loi de l'instant, chiffrée. « Inspecter » : un clic "
                + "dans la scène pose une sonde — E₁, E₂, leur somme, ε₀E²/2, φ, ce qui s'y oppose — et l'explication de l'étape.\n\n"
                + "Champ électrique : un halo par noyau — la densité d'énergie de son propre champ, ε₀|E_i|²/2, sur quatre décades sous sa "
                + "surface (sphères uniformément chargées), teinte selon Z, opacité croissante avec Z ; blanc là où deux champs se font face "
                + "(E₁·E₂ < 0) ; des grains suivent le champ total (mobilité ∝ √E). Champ magnétique : dipôles des spins (moments mesurés), "
                + "flux figuré par des comètes. Noyaux : gouttes liquides, comprimées par le champ de l'autre (β₂ de Bohr–Mottelson) ; "
                + "chaudes, elles vibrent (amplitudes thermiques √(T/C)).\n\n"
                + "Onde : paquet de largeur ƛ = ħ/p, rides à la longueur d'onde de de Broglie locale ; η = Z₁Z₂α/β. Effet tunnel : amplitude WKB "
                + "exp(−∫κdr) ; le noyau réapparaît au contact. Le passage n'a pas de trajectoire classique : c'est une mise en scène du changement "
                + "d'identité, signalée.\n\n"
                + "Réactions : Bosch–Hale pour D–T, D–D, D–³He ; sinon capture (ondes partielles, WKB sous la barrière, Hill–Wheeler au-dessus). "
                + "Désexcitation : Weisskopf, γ, fission de Bohr–Wheeler ; quasi-fission au-delà de Z = 118.\n\n"
                + "Plasma : ⟨σv⟩ maxwellien, bremsstrahlung (avec correction relativiste), pertes W/τ ; ignition si la part chargée de la fusion "
                + "compense les pertes (critère de Lawson).\n\n" + (run == null ? "" : run.getModel() + "\n" + run.getNotes()));
        alert.getDialogPane().setPrefWidth(760);
        alert.show();
    }

    // ================================================================== textes
    private String symbolOf(Body body) {
        if (body.a() == 0) return "γ";
        if (body.z() == 0) return "n";
        return nuclide(body.z(), body.a(), symbolOfZ(body.z()));
    }

    private String symbolOfZ(int z) {
        return z == 0 ? "n" : catalog.stream().filter(e -> e.getZ() == z).map(LabElement::getSymbol).findFirst().orElse("Z" + z);
    }

    private static String identity(CollisionNucleus n, String fallback) {
        return n == null ? fallback : n.getLabel();
    }

    private String stability(int z, int a) {
        for (LabElement e : catalog) if (e.getZ() == z) for (LabIsotope i : e.getIsotopesList()) if (i.getA() == a)
            return i.getStable() ? "stable" : i.getHalfLifeS() > 0 ? "demi-vie " + duration(i.getHalfLifeS()) : "radioactif";
        return "jamais observé";
    }

    static String nuclide(int z, int a, String symbol) {
        String digits = "⁰¹²³⁴⁵⁶⁷⁸⁹";
        StringBuilder out = new StringBuilder();
        for (char c : Integer.toString(a).toCharArray()) out.append(digits.charAt(c - '0'));
        return out + symbol;
    }

    static String spinText(double spin) {
        if (Math.abs(spin - Math.rint(spin)) < 1e-6) return Integer.toString((int) Math.rint(spin));
        return (int) Math.rint(spin * 2) + "/2";
    }

    static String superscript(int n) {
        String digits = "⁰¹²³⁴⁵⁶⁷⁸⁹";
        StringBuilder out = new StringBuilder();
        for (char c : Integer.toString(n).toCharArray()) out.append(c == '-' ? '⁻' : digits.charAt(c - '0'));
        return out.toString();
    }

    static String probability(double p) {
        return p >= .01 ? fmt(100 * p) + " %" : p > 0 ? scientificShort(p) : "0";
    }

    static String force(double newtons) {
        return (newtons >= .01 && newtons < 1e5 ? fmt(newtons) : scientificShort(newtons)) + " N";
    }

    private static String power(double watts) {
        if (watts <= 0) return "0";
        if (watts >= 1e6 && watts < 1e12) return fmt(watts / 1e6) + " MW/m³";
        if (watts >= 1e3 && watts < 1e6) return fmt(watts / 1e3) + " kW/m³";
        if (watts >= 1 && watts < 1e3) return fmt(watts) + " W/m³";
        return scientificShort(watts) + " W/m³";
    }

    private static String kelvin(double k) {
        if (k >= 1e9) return fmt(k / 1e9) + " milliards de °C";
        if (k >= 1e6) return fmt(k / 1e6) + " millions de °C";
        return fmt(k) + " °C";
    }

    static String duration(double s) {
        if (s >= 3.156e7) return scientificShort(s / 3.156e7) + " ans";
        if (s >= 86400) return fmt(s / 86400) + " j";
        if (s >= 3600) return fmt(s / 3600) + " h";
        if (s >= 60) return fmt(s / 60) + " min";
        if (s >= 1) return fmt(s) + " s";
        if (s >= 1e-3) return fmt(s * 1e3) + " ms";
        if (s >= 1e-6) return fmt(s * 1e6) + " µs";
        return scientificShort(s) + " s";
    }

    static String scientificShort(double v) {
        if (v == 0) return "0";
        if (!Double.isFinite(v)) return "∞";
        int exp = (int) Math.floor(Math.log10(Math.abs(v)));
        double mantissa = v / Math.pow(10, exp);
        return String.format(Locale.FRANCE, "%.2g", mantissa) + "·10" + superscript(exp);
    }

    static String fmt(double v) {
        return String.format(Locale.FRANCE, Math.abs(v) >= 100 ? "%.0f" : Math.abs(v) >= 10 ? "%.1f" : "%.2f", v);
    }

    static String scientific(double v) {
        return v == 0 ? "0" : String.format(Locale.FRANCE, "%.2e", v);
    }

    static String energy(double kev) {
        return kev >= 1e6 ? fmt(kev / 1e6) + " GeV" : kev >= 1000 ? fmt(kev / 1000) + " MeV" : fmt(kev) + " keV";
    }

    static String lengthText(double metres) {
        double[] units = {1, 1e-2, 1e-3, 1e-6, 1e-9, 1e-12, 1e-15};
        String[] names = {"m", "cm", "mm", "µm", "nm", "pm", "fm"};
        for (int i = 0; i < units.length; i++) if (Math.abs(metres) >= units[i] * .999) return fmt(metres / units[i]) + " " + names[i];
        return fmt(metres / 1e-15) + " fm";
    }
}
