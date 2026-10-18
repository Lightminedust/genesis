package org.genesis.fusion;

import org.genesis.app.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import org.genesis.app.Branding;
import javafx.stage.Window;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.DoubleUnaryOperator;
import static org.genesis.fusion.ReactorView.duration;
import static org.genesis.fusion.ReactorView.energy;
import static org.genesis.fusion.ReactorView.fmt;
import static org.genesis.fusion.ReactorView.probability;
import static org.genesis.fusion.ReactorView.scientificShort;

/**
 * Le bilan interactif d'une collision, une fois la fusion faite (ou le rebond fini), en trois pages :
 * <ul>
 * <li>vue d'ensemble : chaque corps éjecté (noyaux, neutrons, rayons γ, leptons) dans sa vraie direction de vol (projetée
 *     dans le plan du choc), à une distance du centre qui croît avec son énergie (échelle log), et une lecture en mots ;</li>
 * <li>produits : le tableau (nombre, énergie, vitesse, part de l'énergie, statut) ; une ligne choisie ouvre sa fiche et
 *     amène la vue principale sur lui ;</li>
 * <li>énergie : où va l'énergie (élan + Q → produits, lumière, neutrinos, excitation), puis ce qu'elle vaut — par
 *     réaction, par gramme, kilogramme ou tonne de combustible — face à des repères : TNT, Hiroshima, une ville, le Soleil.</li>
 * </ul>
 * Fenêtre flottante dans la fenêtre principale (déplaçable par sa barre, redimensionnable par son coin) ; « Fenêtre »
 * la détache dans sa propre fenêtre, pour un second écran.
 */
public final class ResultView {
    static final String TITLE = "Bilan de la collision";

    /**
     * Un corps à la fin du calcul : symbole, nom, couleur, nature (nucleus, neutron, gamma, positron, neutrino), rôle
     * (créé, éjecté, lumière, perdu), Z, A, énergie cinétique (MeV ; énergie totale pour un photon), vitesse (fraction
     * de c), direction de vol dans le plan du choc (dx, dy, y vers le bas), statut, fiche ; central : l'élément créé.
     */
    record Particle(String symbol, String name, String color, String kind, String role, int z, int a, double kineticMev, double beta,
                    double dx, double dy, String status, List<String> lines, boolean central) {}

    /**
     * Le bilan d'un calcul : titre, réaction, sans réaction (rebond), Q (MeV), élan du choc E (MeV), excitation du noyau
     * composé, probabilité du tir, masse du combustible (MeV/c², les deux réactifs), son nom, vie du noyau composé (s),
     * nucléons et charge avant/après, erreur d'énergie du calcul, déviation (degrés, rebond), les corps.
     */
    record Result(String title, String reaction, boolean elastic, boolean touched, double qMev, double energyMev, double excitationMev, double probability,
                  double fuelMassMev, String fuelName, double lifetimeS, int nucleonsIn, int chargeIn, int nucleonsOut, int chargeOut,
                  double energyError, double deflection, List<Particle> particles) {}

    /** Une énergie de référence : son nom sur l'échelle, sa tournure dans une phrase, sa valeur (J). */
    record Reference(String label, String phrase, double joules) {}

    static final double MEV_J = 1.602176634e-13, U_KG = 1.66053906660e-27, U_MEV = 931.49410242, TNT_J_KG = 4.184e6;
    /** Puissances de comparaison (W) : le Soleil, l'humanité (≈ 600 EJ/an), l'électricité d'une ville de 100 000 habitants. */
    static final double SUN_W = 3.828e26, HUMANITY_W = 1.9e13, CITY_W = 7.4e7;
    static final List<Reference> REFERENCES = List.of(
            new Reference("photon visible (2,3 eV)", "un photon de lumière visible", 3.7e-19),
            new Reference("molécule de méthane brûlée", "la combustion d'une molécule de méthane", 1.48e-18),
            new Reference("moustique en vol", "un moustique en vol", 1.6e-7),
            new Reference("pile AA (3,7 Wh)", "une pile AA", 1.35e4),
            new Reference("charge de smartphone", "la charge d'un smartphone (15 Wh)", 5.4e4),
            new Reference("voiture à 100 km/h", "une voiture d'une tonne à 100 km/h", 3.9e5),
            new Reference("1 kg de TNT", "1 kg de TNT", TNT_J_KG),
            new Reference("éclair d'orage", "un éclair d'orage", 1e9),
            new Reference("ville de 100 000 hab., 1 jour", "l'électricité d'une ville de 100 000 habitants pendant un jour", 6.4e12),
            new Reference("bombe d'Hiroshima", "la bombe d'Hiroshima (15 kt)", 6.3e13),
            new Reference("Tsar Bomba (50 Mt)", "la Tsar Bomba (50 Mt)", 2.1e17),
            new Reference("humanité, 1 an", "l'énergie consommée par l'humanité en un an", 6.0e20),
            new Reference("Soleil, 1 seconde", "ce que le Soleil rayonne en une seconde", 3.828e26));
    private static final String[] SCALES = {"1 réaction", "1 g", "1 kg", "1 t"};

    private final VBox content = new VBox(10);
    private final StackPane body = new StackPane(content);
    private final VBox panel = new VBox();
    private Stage window;
    private Runnable onHidden = () -> {};
    private final BiConsumer<Integer, Integer> onFocus;
    private double grabX, grabY;
    private boolean placed;
    private Result shown;
    private String tab = "overview";
    private int selected = -1, scale = 2;
    private final List<double[]> hits = new ArrayList<>();

    ResultView(BiConsumer<Integer, Integer> onFocus) {
        this.onFocus = onFocus;
        Label title = new Label(FusionDashboard.spaced("BILAN"));
        title.setStyle("-fx-text-fill:#8FE6FF;-fx-font-size:10px;-fx-font-weight:bold;");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button detach = small("⧉ Fenêtre", "Le détacher dans sa propre fenêtre (second écran)");
        detach.setOnAction(e -> detach(panel.getScene() == null ? null : panel.getScene().getWindow()));
        Button close = small("✕", "Fermer le bilan");
        close.setId("collision-results-close");
        close.setOnAction(e -> hide());
        HBox bar = new HBox(6, title, gap, detach, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(7, 8, 5, 16));
        bar.setCursor(Cursor.MOVE);
        bar.setOnMousePressed(e -> {
            grabX = e.getSceneX() - panel.getLayoutX();
            grabY = e.getSceneY() - panel.getLayoutY();
        });
        bar.setOnMouseDragged(e -> place(e.getSceneX() - grabX, e.getSceneY() - grabY));
        Region grip = new Region();
        grip.setPrefSize(14, 14);
        grip.setMaxSize(14, 14);
        grip.setCursor(Cursor.SE_RESIZE);
        grip.setStyle("-fx-background-color:linear-gradient(to bottom right, transparent 50%, #4F7BA6 50%);-fx-background-radius:0 0 12 0;");
        grip.setOnMouseDragged(e -> {
            panel.setPrefSize(Math.max(400, e.getSceneX() - panel.getLayoutX()), Math.max(300, e.getSceneY() - panel.getLayoutY()));
            place(panel.getLayoutX(), panel.getLayoutY());
        });
        HBox foot = new HBox(grip);
        foot.setAlignment(Pos.BOTTOM_RIGHT);
        foot.setMaxHeight(14);
        foot.setPickOnBounds(false);
        content.setPadding(new Insets(6, 16, 14, 16));
        body.getStyleClass().add("fusion-lab");
        body.setStyle("-fx-background-color:transparent;");
        VBox.setVgrow(body, Priority.ALWAYS);
        panel.getChildren().addAll(bar, body, foot);
        foot.setManaged(false);
        panel.layoutBoundsProperty().addListener((o, a, b) -> foot.resizeRelocate(b.getWidth() - 16, b.getHeight() - 16, 14, 14));
        panel.setPrefSize(472, 568);
        panel.setStyle("-fx-background-color:linear-gradient(to bottom, rgba(16,27,43,0.95), rgba(6,10,18,0.97));-fx-background-radius:14;"
                + "-fx-border-color:rgba(150,200,255,0.22);-fx-border-radius:14;-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.65), 26, 0.2, 0, 6);");
        panel.setVisible(false);
        panel.setManaged(false);
    }

    private static Button small(String text, String tip) {
        Button b = new Button(text);
        b.getStyleClass().add("glass-button");
        b.setStyle("-fx-padding:2 9;-fx-font-size:10.5px;");
        b.setFocusTraversable(false);
        b.setTooltip(I18n.tooltip(tip));
        return b;
    }

    /** La fenêtre flottante, à poser dans un calque au-dessus de la scène. */
    Region panel() {
        return panel;
    }

    /** Le contenu, retiré de la fenêtre flottante (pour l'enregistrer hors écran). */
    Region takeBody() {
        panel.getChildren().remove(body);
        return body;
    }

    void setOnHidden(Runnable action) {
        onHidden = action;
    }

    boolean isShowing() {
        return window != null && window.isShowing() || panel.isVisible();
    }

    /** Ouvre le bilan (flottant, ou dans sa fenêtre s'il a été détaché), en haut à droite de la scène. */
    void show() {
        if (window != null) {
            window.show();
            return;
        }
        panel.setVisible(true);
        if (!placed && panel.getParent() instanceof Region host && host.getWidth() > 0) {
            placed = true;
            panel.setPrefHeight(Math.max(320, Math.min(568, host.getHeight() - 160)));
            place(host.getWidth() - panel.getPrefWidth() - 14, 58);
        }
    }

    void hide() {
        boolean was = isShowing();
        panel.setVisible(false);
        if (window != null) window.hide();
        if (was) onHidden.run();
    }

    /** Le bilan à montrer (même objet : rien n'est reconstruit). Un nouveau bilan revient à la vue d'ensemble. */
    void setResult(Result r) {
        if (r == shown) return;
        shown = r;
        if (r == null) return;
        tab = "overview";
        selected = -1;
        build();
    }

    private void place(double x, double y) {
        if (!(panel.getParent() instanceof Region host)) return;
        double w = panel.getPrefWidth(), h = panel.getPrefHeight();
        panel.resizeRelocate(Math.max(0, Math.min(host.getWidth() - 60, x)), Math.max(0, Math.min(host.getHeight() - 40, y)), w, h);
    }

    // ================================================================== la construction
    private void build() {
        content.getChildren().clear();
        Label kicker = label(FusionDashboard.spaced(shown.title()), "#7FD0C8", 9.5, true);
        Label reaction = label(shown.reaction(), "#F2F7FF", 16, true);
        double q = shown.qMev(), fastest = 0;
        for (Particle p : shown.particles()) if (p.a() > 0 || p.kind().equals("positron")) fastest = Math.max(fastest, p.beta());
        int ejected = 0;
        for (Particle p : shown.particles()) if (!p.central()) ejected++;
        HBox tiles = new HBox(8,
                tile(shown.elastic() ? "ÉNERGIE" : q < 0 ? "Q · ÉNERGIE ABSORBÉE" : "Q · MASSE → ÉNERGIE", shown.elastic() ? "aucune" : (q >= 0 ? "+" : "") + fmt(q) + " MeV",
                        q > 0 && !shown.elastic() ? "#FFC46B" : "#C9D6EA"),
                tile(shown.elastic() ? "CORPS" : "ÉJECTÉS", Integer.toString(ejected), "#E6EEFA"),
                tile("LE PLUS RAPIDE", fastest > 0 ? ResultView.percent(fastest) : "—", "#9FE0FF"),
                tile(shown.title().contains("QUASI-FISSION") ? "CAPTURE" : "PROBABILITÉ", probability(shown.probability()), "#C9D6EA"));
        HBox tabs = new HBox(0);
        String[][] names = {{"overview", "Vue d'ensemble"}, {"products", "Produits"}, {"energy", "Énergie"}};
        for (int i = 0; i < names.length; i++) {
            String key = names[i][0];
            Button b = new Button(names[i][1]);
            b.getStyleClass().addAll("segment", i == 0 ? "left" : i == names.length - 1 ? "right" : "middle");
            if (key.equals(tab)) b.getStyleClass().add("on");
            b.setFocusTraversable(false);
            b.setId("collision-result-tab-" + i);
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
            b.setOnAction(e -> {
                tab = key;
                build();
            });
            tabs.getChildren().add(b);
        }
        Node page = switch (tab) {
            case "products" -> productsPage();
            case "energy" -> energyPage();
            default -> overviewPage();
        };
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background:transparent;-fx-background-color:transparent;-fx-padding:0;");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        content.getChildren().addAll(new VBox(2, kicker, reaction), tiles, tabs, scroll);
    }

    private static VBox tile(String title, String value, String color) {
        Label t = label(title, "#7F98B8", 8.5, true), v = label(value, color, 13.5, true);
        v.setWrapText(false);
        v.setTextOverrun(OverrunStyle.CLIP);
        VBox box = new VBox(2, t, v);
        box.setPadding(new Insets(7, 9, 7, 9));
        box.setStyle("-fx-background-color:rgba(255,255,255,0.035);-fx-background-radius:10;-fx-border-color:rgba(150,200,255,0.12);-fx-border-radius:10;");
        box.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    // ------------------------------------------------------------------ vue d'ensemble
    private Node overviewPage() {
        Canvas chart = new Canvas(440, 262);
        drawChart(chart.getGraphicsContext2D(), chart.getWidth(), chart.getHeight());
        chart.setOnMouseClicked(e -> {
            double[] best = null;
            for (double[] h : hits) if (Math.hypot(e.getX() - h[0], e.getY() - h[1]) < 12 && (best == null
                    || Math.hypot(e.getX() - h[0], e.getY() - h[1]) < Math.hypot(e.getX() - best[0], e.getY() - best[1]))) best = h;
            if (best == null) return;
            Particle p = shown.particles().get((int) best[2]);
            List<List<Particle>> groups = groups();
            for (int i = 0; i < groups.size(); i++) if (groups.get(i).contains(p)) selected = i;
            onFocus.accept(p.z(), p.a());
            build();
        });
        chart.setOnMouseMoved(e -> {
            boolean over = false;
            for (double[] h : hits) over |= Math.hypot(e.getX() - h[0], e.getY() - h[1]) < 12;
            chart.setCursor(over ? Cursor.HAND : Cursor.DEFAULT);
        });
        Label caption = label("Chaque trait : un corps éjecté, dans sa direction de vol (plan du choc) ; plus il est long, plus il emporte "
                + "d'énergie (anneaux : échelle logarithmique). Un clic l'amène dans la vue principale.", "#8FA7C4", 10, false);
        VBox reading = new VBox(5);
        reading.getChildren().add(label(FusionDashboard.spaced("LECTURE"), "#7FA6CF", 9, true));
        for (String[] line : reading()) reading.getChildren().add(label(line[0], line[1], 11.5, false));
        reading.setPadding(new Insets(10, 12, 11, 12));
        reading.setStyle("-fx-background-color:rgba(255,255,255,0.03);-fx-background-radius:10;-fx-border-color:rgba(150,200,255,0.1);-fx-border-radius:10;");
        VBox page = new VBox(8, chart, caption, reading);
        if (selected >= 0 && selected < groups().size()) page.getChildren().add(detail(groups().get(selected)));
        return page;
    }

    /** Les corps éjectés autour de l'élément créé : direction de vol, longueur ∝ log de l'énergie. */
    private void drawChart(GraphicsContext g, double w, double h) {
        hits.clear();
        double cx = w / 2, cy = h / 2, outer = Math.min(w, h) / 2 - 22, inner = 30;
        Particle center = null;
        double lo = Double.MAX_VALUE, hi = 0;
        for (Particle p : shown.particles()) {
            if (p.central() && center == null) {
                center = p;
                continue;
            }
            if (p.kineticMev() > 0) {
                lo = Math.min(lo, p.kineticMev());
                hi = Math.max(hi, p.kineticMev());
            }
        }
        if (hi <= 0) {
            lo = .01;
            hi = 1;
        }
        double l1 = Math.log10(hi) + .12, l0 = Math.min(Math.log10(lo) - .45, l1 - 2);
        DoubleUnaryOperator radius = e -> inner + (outer - inner) * Math.max(.04, Math.min(1, (Math.log10(Math.max(e, 1e-12)) - l0) / (l1 - l0)));
        g.setFill(new RadialGradient(0, 0, cx, cy, outer + 24, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#173052", .75)), new Stop(.7, Color.web("#0C1A2E", .45)), new Stop(1, Color.TRANSPARENT)));
        g.fillOval(cx - outer - 24, cy - outer - 24, 2 * (outer + 24), 2 * (outer + 24));
        g.setFont(Font.font("Segoe UI", 9));
        g.setTextAlign(TextAlignment.LEFT);
        for (int d = (int) Math.ceil(l0); d <= (int) Math.floor(l1); d++) {
            double r = radius.applyAsDouble(Math.pow(10, d));
            g.setStroke(Color.color(.6, .8, 1, .13));
            g.setLineWidth(1);
            g.setLineDashes(2, 5);
            g.strokeOval(cx - r, cy - r, 2 * r, 2 * r);
            g.setLineDashes(null);
            g.setFill(Color.color(.62, .76, .92, .6));
            I18n.fillText(g, energy(Math.pow(10, d) * 1000), cx + r * .72 + 3, cy - r * .70 - 2);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < shown.particles().size(); i++) if (shown.particles().get(i) != center) order.add(i);
        order.sort((x, y) -> Double.compare(shown.particles().get(y).kineticMev(), shown.particles().get(x).kineticMev()));
        int labelled = 0;
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i : order) {
            Particle p = shown.particles().get(i);
            double ux = p.dx(), uy = p.dy(), n = Math.hypot(ux, uy);
            if (n < 1e-9) {
                ux = 1;
                uy = 0;
            } else {
                ux /= n;
                uy /= n;
            }
            double len = radius.applyAsDouble(p.kineticMev()), x0 = cx + ux * inner, y0 = cy + uy * inner, x1 = cx + ux * len, y1 = cy + uy * len;
            Color c = Color.web(p.color());
            g.setLineWidth(p.a() > 4 ? 2.6 : 1.8);
            switch (p.kind()) {
                case "gamma" -> {
                    g.setStroke(c.deriveColor(0, 1, 1, .9));
                    double length = Math.hypot(x1 - x0, y1 - y0);
                    int steps = Math.max(8, (int) (length / 2));
                    double[] xs = new double[steps + 1], ys = new double[steps + 1];
                    for (int k = 0; k <= steps; k++) {
                        double s = length * k / steps, wave = 3.2 * Math.sin(2 * Math.PI * s / 9) * Math.min(1, s / 12);
                        xs[k] = x0 + ux * s - uy * wave;
                        ys[k] = y0 + uy * s + ux * wave;
                    }
                    g.strokePolyline(xs, ys, steps + 1);
                }
                case "neutrino" -> {
                    g.setStroke(c.deriveColor(0, 1, 1, .8));
                    g.setLineDashes(3, 4);
                    g.strokeLine(x0, y0, x1, y1);
                    g.setLineDashes(null);
                }
                default -> {
                    g.setStroke(new LinearGradient(x0, y0, x1, y1, false, CycleMethod.NO_CYCLE,
                            new Stop(0, c.deriveColor(0, 1, 1, .1)), new Stop(1, c.interpolate(Color.WHITE, .2))));
                    g.strokeLine(x0, y0, x1, y1);
                }
            }
            double dot = p.a() > 0 ? 2.6 + 1.5 * Math.cbrt(p.a()) : 3;
            g.setFill(new RadialGradient(0, 0, x1, y1, dot * 3, false, CycleMethod.NO_CYCLE,
                    new Stop(0, c.deriveColor(0, 1, 1, .55)), new Stop(1, c.deriveColor(0, 1, 1, 0))));
            g.fillOval(x1 - dot * 3, y1 - dot * 3, dot * 6, dot * 6);
            if (p.kind().equals("neutron")) {
                g.setStroke(c.interpolate(Color.WHITE, .3));
                g.setLineWidth(1.6);
                g.strokeOval(x1 - dot, y1 - dot, 2 * dot, 2 * dot);
            } else {
                g.setFill(c.interpolate(Color.WHITE, .25));
                g.fillOval(x1 - dot, y1 - dot, 2 * dot, 2 * dot);
            }
            hits.add(new double[]{x1, y1, i});
            int same = seen.merge(p.symbol(), 1, Integer::sum);
            if (labelled < 9 && same <= 2) {
                labelled++;
                g.setFont(Font.font("Segoe UI Semibold", 10.5));
                g.setTextAlign(ux < -.2 ? TextAlignment.RIGHT : ux > .2 ? TextAlignment.LEFT : TextAlignment.CENTER);
                g.setFill(c.interpolate(Color.WHITE, .35));
                double lx = x1 + ux * (dot + 7), ly = y1 + uy * (dot + 7) + (Math.abs(ux) <= .2 ? (uy > 0 ? 9 : -3) : 4);
                I18n.fillText(g, p.symbol() + "  " + energy(p.kineticMev() * 1000), lx, ly);
            }
        }
        if (center != null) {
            Color c = Color.web(center.color());
            double r = inner - 9;
            g.setFill(new RadialGradient(0, 0, cx, cy, r * 2.2, false, CycleMethod.NO_CYCLE,
                    new Stop(0, c.deriveColor(0, 1, 1, .5)), new Stop(1, c.deriveColor(0, 1, 1, 0))));
            g.fillOval(cx - r * 2.2, cy - r * 2.2, r * 4.4, r * 4.4);
            g.setFill(new RadialGradient(0, 0, cx - r * .3, cy - r * .35, r * 1.3, false, CycleMethod.NO_CYCLE,
                    new Stop(0, c.interpolate(Color.WHITE, .55)), new Stop(1, c.deriveColor(0, 1, .55, 1))));
            g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
            g.setFill(Color.web("#06101C"));
            g.setFont(Font.font("Segoe UI Semibold", center.symbol().length() > 4 ? 10 : 12));
            g.setTextAlign(TextAlignment.CENTER);
            I18n.fillText(g, center.symbol(), cx, cy + 4, 2 * r - 2);
            hits.add(new double[]{cx, cy, shown.particles().indexOf(center)});
        } else {
            g.setStroke(Color.color(1, 1, 1, .5));
            g.setLineWidth(1);
            g.strokeLine(cx - 5, cy, cx + 5, cy);
            g.strokeLine(cx, cy - 5, cx, cy + 5);
            g.setFont(Font.font("Segoe UI", 9));
            g.setFill(Color.color(1, 1, 1, .6));
            g.setTextAlign(TextAlignment.CENTER);
            I18n.fillText(g, "centre de masse", cx, cy + 16);
        }
    }

    /** La lecture du résultat, en phrases calculées (texte, couleur). */
    private List<String[]> reading() {
        List<String[]> out = new ArrayList<>();
        Particle center = null, best = null;
        double total = 0, light = 0, lost = 0;
        int photons = 0;
        for (Particle p : shown.particles()) {
            if (p.central() && center == null) center = p;
            total += p.kineticMev();
            if (p.kind().equals("gamma")) {
                light += p.kineticMev();
                photons++;
            } else if (p.kind().equals("neutrino")) lost += p.kineticMev();
            if (!p.central() && !p.kind().equals("gamma") && !p.kind().equals("neutrino") && (best == null || p.kineticMev() > best.kineticMev())) best = p;
        }
        if (shown.elastic()) {
            out.add(new String[]{shown.touched()
                    ? "Contact atteint, sans état final lié : les deux noyaux se séparent élastiquement, déviés de θ = " + fmt(shown.deflection()) + "°."
                    : "Pas de fusion : la répulsion les a renvoyés, déviés de θ = " + fmt(shown.deflection()) + "°.", "#E6EEFA"});
            out.add(new String[]{"L'élan du choc (" + energy(shown.energyMev() * 1000) + ") est rendu : rien n'est créé, rien n'est émis.", "#B9C9DF"});
            out.add(new String[]{"Probabilité de fusion à ce tir : " + probability(shown.probability()) + ".", "#FFC46B"});
            return out;
        }
        if (center != null) out.add(new String[]{Character.toUpperCase(center.role().charAt(0)) + center.role().substring(1) + " : "
                + center.symbol() + " (" + center.name() + "), " + center.status() + " — il recule à "
                + percent(center.beta()) + " avec " + energy(center.kineticMev() * 1000) + ".", "#E6EEFA"});
        if (best != null && total > 0) out.add(new String[]{"Le plus énergique : " + best.symbol() + ", " + energy(best.kineticMev() * 1000) + " ("
                + fmt(100 * best.kineticMev() / total) + " % de l'énergie emportée), à " + percent(best.beta()) + ".", "#9FE0FF"});
        List<Particle> massive = new ArrayList<>();
        for (Particle p : shown.particles()) if (p.a() > 0) massive.add(p);
        if (massive.size() == 2 && shown.particles().size() == 2) out.add(new String[]{"Deux corps : impulsions égales et opposées — le plus léger "
                + "emporte le plus d'énergie (K ∝ 1/m).", "#B9C9DF"});
        if (photons > 0) out.add(new String[]{"Lumière : " + photons + " photon" + (photons > 1 ? "s" : "") + " γ, " + energy(light * 1000)
                + " — invisible à l'œil, très pénétrante.", "#FFD36A"});
        if (lost > 0) out.add(new String[]{"Neutrino : " + energy(lost * 1000) + " perdus — il traverserait la Terre sans s'arrêter.", "#B8F5C8"});
        out.add(new String[]{"Conservés : " + shown.nucleonsIn() + " nucléons → " + shown.nucleonsOut() + " · charge +" + shown.chargeIn() + "e → +"
                + shown.chargeOut() + "e · énergie (erreur du calcul " + String.format(Locale.ROOT, "%.0e", shown.energyError()) + ").", "#8FA7C4"});
        if (shown.lifetimeS() > 0) out.add(new String[]{"Le noyau composé a vécu ≈ " + duration(shown.lifetimeS()) + ".", "#8FA7C4"});
        return out;
    }

    // ------------------------------------------------------------------ produits
    /** Les corps regroupés par espèce (même symbole, même nature), l'élément créé d'abord. */
    private List<List<Particle>> groups() {
        Map<String, List<Particle>> map = new LinkedHashMap<>();
        for (Particle p : shown.particles()) if (p.central()) map.computeIfAbsent("central", k -> new ArrayList<>()).add(p);
        for (Particle p : shown.particles()) if (!p.central()) map.computeIfAbsent(p.symbol() + ":" + p.kind() + ":" + p.role(), k -> new ArrayList<>()).add(p);
        return new ArrayList<>(map.values());
    }

    private Node productsPage() {
        VBox table = new VBox(3);
        HBox head = row(label("", "#7F98B8", 9, true), label("PARTICULE", "#7F98B8", 8.5, true), label("NB", "#7F98B8", 8.5, true),
                label("ÉNERGIE", "#7F98B8", 8.5, true), label("VITESSE", "#7F98B8", 8.5, true), label("PART", "#7F98B8", 8.5, true),
                label("RÔLE", "#7F98B8", 8.5, true));
        head.setPadding(new Insets(0, 8, 2, 8));
        table.getChildren().add(head);
        List<List<Particle>> groups = groups();
        double total = 0;
        for (Particle p : shown.particles()) total += p.kineticMev();
        for (int i = 0; i < groups.size(); i++) {
            List<Particle> g = groups.get(i);
            Particle p = g.get(0);
            double sum = 0;
            for (Particle x : g) sum += x.kineticMev();
            Circle dot = new Circle(5, Color.web(p.color()));
            if (p.kind().equals("neutron")) {
                dot.setFill(Color.TRANSPARENT);
                dot.setStroke(Color.web(p.color()));
                dot.setStrokeWidth(1.6);
            }
            Label symbol = label(p.symbol(), p.color(), 13, true), name = label(p.name(), "#9FB4CE", 9.5, false);
            symbol.setWrapText(false);
            name.setWrapText(false);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            VBox who = new VBox(0, symbol, name);
            double share = total > 0 ? sum / total : 0;
            Region track = new Region(), fill = new Region();
            track.setPrefSize(40, 5);
            track.setMaxSize(40, 5);
            track.setStyle("-fx-background-color:rgba(255,255,255,0.08);-fx-background-radius:3;");
            fill.setPrefSize(Math.max(2, 40 * share), 5);
            fill.setMaxSize(Math.max(2, 40 * share), 5);
            fill.setStyle("-fx-background-color:linear-gradient(to right, #36CDF2, " + (share > .5 ? "#FFB24A" : "#7FDCF5") + ");-fx-background-radius:3;");
            StackPane bar = new StackPane(track, fill);
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            bar.setAlignment(Pos.CENTER_LEFT);
            HBox shareBox = new HBox(5, bar, label(fmt(100 * share) + " %", "#C9D6EA", 10, false));
            shareBox.setAlignment(Pos.CENTER_LEFT);
            String role = p.role();
            Label chip = label(role, role.equals("créé") ? "#7FE3B0" : role.equals("lumière") ? "#FFD36A" : role.equals("perdu") ? "#B8F5C8" : "#9FE0FF", 9.5, true);
            chip.setWrapText(false);
            boolean light = p.a() == 0 && !p.kind().equals("positron");
            HBox r = row(dot, who, label(g.size() > 1 ? "×" + g.size() : "1", "#E6EEFA", 11, true),
                    label(g.size() > 1 ? energy(sum * 1000) + "\n(" + energy(sum / g.size() * 1000) + " chacun)" : energy(sum * 1000), "#FFD9A0", 10.5, false),
                    label(light ? "c" : percent(p.beta()), "#9FE0FF", 10.5, false), shareBox, chip);
            int index = i;
            String base = "-fx-background-radius:9;-fx-padding:6 8;-fx-cursor:hand;";
            String idle = "-fx-background-color:rgba(255,255,255,0.025);" + base;
            String on = "-fx-background-color:rgba(54,205,242,0.13);-fx-border-color:rgba(54,205,242,0.5);-fx-border-radius:9;" + base;
            r.setStyle(i == selected ? on : idle);
            r.setOnMouseEntered(e -> { if (index != selected) r.setStyle("-fx-background-color:rgba(255,255,255,0.06);" + base); });
            r.setOnMouseExited(e -> r.setStyle(index == selected ? on : idle));
            r.setOnMouseClicked(e -> {
                selected = index;
                onFocus.accept(p.z(), p.a());
                build();
            });
            r.setId("collision-result-row-" + i);
            table.getChildren().add(r);
        }
        VBox page = new VBox(10, table);
        if (selected >= 0 && selected < groups.size()) page.getChildren().add(detail(groups.get(selected)));
        else page.getChildren().add(label("Une ligne choisie ouvre sa fiche et amène la vue principale sur ce corps.", "#8FA7C4", 10, false));
        return page;
    }

    /** Une ligne du tableau, colonnes de largeur fixe. */
    private static HBox row(Node dot, Node who, Node count, Node energy, Node speed, Node share, Node role) {
        double[] widths = {12, 120, 30, 82, 62, 70, 44};
        Node[] cells = {dot, who, count, energy, speed, share, role};
        HBox r = new HBox(6);
        r.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < cells.length; i++) {
            StackPane cell = new StackPane(cells[i]);
            cell.setAlignment(Pos.CENTER_LEFT);
            cell.setMinWidth(widths[i]);
            cell.setPrefWidth(widths[i]);
            cell.setMaxWidth(widths[i]);
            r.getChildren().add(cell);
        }
        return r;
    }

    /** La fiche d'une espèce : ses propriétés calculées. */
    private Node detail(List<Particle> g) {
        Particle p = g.get(0);
        VBox box = new VBox(3);
        box.getChildren().add(label((g.size() > 1 ? g.size() + " × " : "") + p.symbol() + "   " + p.name(), p.color(), 13, true));
        for (String line : p.lines()) box.getChildren().add(label(line, "#C9D6EA", 11, false));
        box.setPadding(new Insets(10, 12, 11, 12));
        box.setStyle("-fx-background-color:rgba(255,255,255,0.035);-fx-background-radius:10;-fx-border-color:" + p.color() + "55;-fx-border-radius:10;");
        return box;
    }

    // ------------------------------------------------------------------ énergie
    private Node energyPage() {
        VBox page = new VBox(9);
        double e = shown.energyMev(), q = shown.qMev();
        page.getChildren().add(label(FusionDashboard.spaced("OÙ VA L'ÉNERGIE"), "#7FA6CF", 9, true));
        page.getChildren().add(label(shown.elastic() ? "Entrée : élan du choc " + energy(e * 1000) + " — rendu tel quel : rien n'est converti."
                : "Entrée : élan " + energy(e * 1000) + "  +  bilan de masse Q = " + fmt(q) + " MeV  =  " + energy((e + q) * 1000), "#E6EEFA", 11.5, false));
        Map<String, double[]> parts = new LinkedHashMap<>();
        Map<String, String> colors = new LinkedHashMap<>();
        double excitation = 0;
        for (Particle p : shown.particles()) {
            String key = p.kind().equals("gamma") ? "lumière γ" : p.kind().equals("neutrino") ? "neutrinos" : p.symbol();
            parts.computeIfAbsent(key, k -> new double[1])[0] += p.kineticMev();
            colors.putIfAbsent(key, p.color());
        }
        double out = 0;
        for (double[] v : parts.values()) out += v[0];
        double left = Math.max(0, e + q - out);
        if (!shown.elastic() && left > .01 * Math.max(1e-9, e + q)) {
            parts.put("excitation restante", new double[]{left});
            colors.put("excitation restante", "#FF8A4A");
            excitation = left;
        }
        Canvas bar = new Canvas(440, 16);
        GraphicsContext g = bar.getGraphicsContext2D();
        g.setFill(Color.color(1, 1, 1, .06));
        g.fillRoundRect(0, 4, 440, 10, 10, 10);
        double total = out + excitation, at = 0;
        FlowPane legend = new FlowPane(12, 4);
        for (Map.Entry<String, double[]> p : parts.entrySet()) {
            double share = total > 0 ? p.getValue()[0] / total : 0, len = 440 * share;
            Color c = Color.web(colors.get(p.getKey()));
            if (len >= .5) {
                g.setFill(new LinearGradient(at, 0, at + len, 0, false, CycleMethod.NO_CYCLE, new Stop(0, c.deriveColor(0, 1, .85, 1)), new Stop(1, c.interpolate(Color.WHITE, .2))));
                g.fillRect(at, 4, len, 10);
                at += len;
            }
            Circle dot = new Circle(4, c);
            HBox item = new HBox(5, dot, label(p.getKey() + "  " + energy(p.getValue()[0] * 1000) + " · " + fmt(100 * share) + " %", "#C9D6EA", 10.5, false));
            item.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(item);
        }
        page.getChildren().addAll(bar, legend);
        if (shown.elastic() || q <= 0) {
            page.getChildren().add(label(shown.elastic() ? "Aucune réaction : aucune énergie créée." : "Q = " + fmt(q) + " MeV : la réaction absorbe "
                    + scientificShort(-q * MEV_J) + " J, pris sur l'élan du choc — rien à gagner en énergie.", "#FF9C7A", 11.5, true));
            return page;
        }
        double perReaction = q * MEV_J, fuelKg = shown.fuelMassMev() / U_MEV * U_KG;
        double[] joules = {perReaction, perReaction / fuelKg / 1000, perReaction / fuelKg, perReaction / fuelKg * 1000};
        page.getChildren().add(label(FusionDashboard.spaced("CE QUE ÇA VAUT"), "#7FA6CF", 9, true));
        HBox scales = new HBox(0);
        for (int i = 0; i < SCALES.length; i++) {
            int index = i;
            Button b = new Button(SCALES[i]);
            b.getStyleClass().addAll("segment", i == 0 ? "left" : i == SCALES.length - 1 ? "right" : "middle");
            if (i == scale) b.getStyleClass().add("on");
            b.setFocusTraversable(false);
            b.setId("collision-result-scale-" + i);
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
            b.setOnAction(ev -> {
                scale = index;
                build();
            });
            scales.getChildren().add(b);
        }
        double j = joules[scale];
        String what = scale == 0 ? "pour une seule réaction (" + fmt(q) + " MeV)" : "pour " + SCALES[scale] + " de combustible " + shown.fuelName();
        Label big = label(scientificShort(j) + " J", "#FFC46B", 22, true);
        VBox head = new VBox(0, big, label(what, "#9FB4CE", 10.5, false));
        VBox equivalences = new VBox(3);
        for (String line : equivalences(j)) equivalences.getChildren().add(label(line, "#E6EEFA", 11.5, false));
        Canvas ladder = new Canvas(440, 340);
        drawLadder(ladder.getGraphicsContext2D(), ladder.getWidth(), ladder.getHeight(), joules);
        page.getChildren().addAll(scales, head, equivalences, ladder);
        return page;
    }

    /** Une énergie (J) face aux repères : TNT, le repère juste en dessous et juste au-dessus, le temps qu'y mettent le Soleil, l'humanité, une ville. */
    static List<String> equivalences(double joules) {
        List<String> out = new ArrayList<>();
        out.add("≈ " + tnt(joules));
        Reference below = null, above = null;
        for (Reference r : REFERENCES) {
            if (r.joules() <= joules) below = r;
            else if (above == null) above = r;
        }
        if (below != null && !below.label().equals("1 kg de TNT")) out.add("= " + ratio(joules / below.joules()) + " × " + below.phrase());
        if (above != null && !above.label().equals("1 kg de TNT")) out.add("= " + above.phrase() + " ÷ " + ratio(above.joules() / joules));
        if (joules >= 1e6) {
            out.add("une ville de 100 000 habitants la consomme (électricité) en " + duration(joules / CITY_W));
            out.add("l'humanité la consomme en " + duration(joules / HUMANITY_W));
        }
        if (joules >= 1) out.add("le Soleil la rayonne en " + duration(joules / SUN_W));
        return out;
    }

    /** Une énergie en équivalent TNT (4,184 MJ/kg). */
    static String tnt(double joules) {
        double kg = joules / TNT_J_KG;
        if (kg >= 1e9) return fmt(kg / 1e9) + " Mt de TNT";
        if (kg >= 1e6) return fmt(kg / 1e6) + " kt de TNT";
        if (kg >= 1e3) return fmt(kg / 1e3) + " t de TNT";
        if (kg >= 1) return fmt(kg) + " kg de TNT";
        if (kg >= 1e-3) return fmt(kg * 1e3) + " g de TNT";
        return scientificShort(kg) + " kg de TNT";
    }

    private static String ratio(double r) {
        return r >= 1e5 ? scientificShort(r) : r >= 100 ? String.format(I18n.locale(), "%,.0f", r) : fmt(r);
    }

    /**
     * L'échelle des énergies, de 10⁻¹⁹ à 10²⁷ J (logarithmique) : les repères à droite, à gauche ce que libère la
     * réaction pour 1 réaction, 1 g, 1 kg et 1 t de combustible — celle choisie, en surbrillance.
     */
    private void drawLadder(GraphicsContext g, double w, double h, double[] marks) {
        double axis = 178, top = 14, bottom = 14, lo = -19, hi = 27;
        DoubleUnaryOperator y = v -> top + (hi - Math.log10(Math.max(v, 1e-300))) / (hi - lo) * (h - top - bottom);
        g.setFill(Color.color(1, 1, 1, .025));
        g.fillRoundRect(0, 0, w, h, 12, 12);
        g.setStroke(new LinearGradient(0, top, 0, h - bottom, false, CycleMethod.NO_CYCLE,
                new Stop(0, FusionDashboard.level(1)), new Stop(.5, FusionDashboard.level(.58)), new Stop(1, FusionDashboard.level(0))));
        g.setLineWidth(2);
        g.strokeLine(axis, top, axis, h - bottom);
        g.setFont(Font.font("Segoe UI", 8.5));
        g.setTextAlign(TextAlignment.LEFT);
        for (int d = -15; d <= 25; d += 5) {
            double yy = y.applyAsDouble(Math.pow(10, d));
            g.setStroke(Color.color(1, 1, 1, .25));
            g.setLineWidth(1);
            g.strokeLine(axis - 4, yy, axis + 4, yy);
            g.setFill(Color.color(.62, .74, .9, .55));
            I18n.fillText(g, "10" + ReactorView.superscript(d) + " J", 6, yy + 3);
        }
        // Les repères, à droite : les étiquettes poussées vers le bas pour ne pas se chevaucher.
        List<Reference> refs = new ArrayList<>(REFERENCES);
        refs.sort((a, b) -> Double.compare(b.joules(), a.joules()));
        double prev = -1e9;
        g.setFont(Font.font("Segoe UI", 10));
        for (Reference r : refs) {
            double ideal = y.applyAsDouble(r.joules()), ly = Math.max(ideal, prev + 13);
            prev = ly;
            g.setStroke(Color.color(1, 1, 1, .4));
            g.setLineWidth(1);
            g.strokeLine(axis, ideal, axis + 7, ideal);
            g.setStroke(Color.color(1, 1, 1, .18));
            g.strokeLine(axis + 7, ideal, axis + 16, ly - 3);
            g.setFill(Color.web("#B9C9DF"));
            I18n.fillText(g, r.label(), axis + 20, ly + 1, w - axis - 24);
        }
        // Ce que libère la réaction : 1 réaction, 1 g, 1 kg, 1 t.
        prev = -1e9;
        Color hotColor = Color.web("#FFC46B");
        for (int i = marks.length - 1; i >= 0; i--) {
            double ideal = y.applyAsDouble(marks[i]), ly = Math.max(ideal, prev + 14);
            prev = ly;
            boolean chosen = i == scale;
            if (chosen) {
                g.setStroke(hotColor.deriveColor(0, 1, 1, .45));
                g.setLineWidth(1);
                g.setLineDashes(3, 4);
                g.strokeLine(axis, ideal, w - 6, ideal);
                g.setLineDashes(null);
                g.setFill(new RadialGradient(0, 0, axis, ideal, 12, false, CycleMethod.NO_CYCLE,
                        new Stop(0, hotColor.deriveColor(0, 1, 1, .8)), new Stop(1, hotColor.deriveColor(0, 1, 1, 0))));
                g.fillOval(axis - 12, ideal - 12, 24, 24);
            }
            g.setFill(chosen ? Color.WHITE : hotColor.deriveColor(0, 1, 1, .7));
            g.fillOval(axis - 3.5, ideal - 3.5, 7, 7);
            g.setFont(Font.font(chosen ? "Segoe UI Semibold" : "Segoe UI", chosen ? 10.5 : 9.5));
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFill(chosen ? hotColor : Color.web("#C9A878"));
            I18n.fillText(g, SCALES[i] + " · " + scientificShort(marks[i]) + " J", axis - 10, ly + 3);
        }
    }

    // ------------------------------------------------------------------ outils
    private static Label label(String value, String color, double size, boolean bold) {
        Label l = new Label(value);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill:" + color + ";-fx-font-size:" + size + "px;" + (bold ? "-fx-font-weight:bold;" : ""));
        return l;
    }

    /** Détache le bilan dans sa propre fenêtre (au-dessus de la fenêtre principale). */
    private void detach(Window owner) {
        if (window != null) return;
        panel.setVisible(false);
        panel.getChildren().remove(body);
        window = new Stage();
        window.setTitle(TITLE);
        Branding.applyWindowIcons(window);
        if (owner != null) window.initOwner(owner);
        Scene scene = new Scene(body, Math.max(480, panel.getPrefWidth()), Math.max(420, panel.getPrefHeight()), Color.web("#070C16"));
        if (owner != null && owner.getScene() != null) scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        body.setStyle("-fx-background-color:linear-gradient(to bottom, #101B2B, #060A12);");
        window.setScene(scene);
        window.setMinWidth(420);
        window.setMinHeight(320);
        if (owner != null) {
            window.setX(owner.getX() + Math.max(0, owner.getWidth() - 520));
            window.setY(owner.getY() + Math.max(0, owner.getHeight() - 640));
        }
        // Fermer la fenêtre détachée : le bilan revient flottant (fermé) dans la fenêtre principale.
        window.setOnHidden(e -> {
            Stage closing = window;
            window = null;
            closing.setScene(null);
            body.setStyle("-fx-background-color:transparent;");
            panel.getChildren().add(1, body);
            onHidden.run();
        });
        window.show();
    }

    /** Une vitesse en pourcentage de c (ou en ‰, ou en km/s). */
    static String percent(double beta) {
        if (beta >= .01) return fmt(100 * beta) + " % de c";
        if (beta >= 1e-4) return fmt(1000 * beta) + " ‰ de c";
        return String.format(I18n.locale(), "%,.0f km/s", beta * FusionField.C / 1000);
    }

    static String speed(double beta) {
        double kms = beta * FusionField.C / 1000;
        String share = beta >= .01 ? fmt(100 * beta) + " % de c" : fmt(1000 * beta) + " ‰ de c";
        return String.format(I18n.locale(), "%s · %,.0f km/s", share, kms);
    }
}
