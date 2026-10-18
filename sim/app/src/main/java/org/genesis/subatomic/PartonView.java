package org.genesis.subatomic;

import org.genesis.app.I18n;
import org.genesis.fusion.FusionDashboard;
import org.genesis.common.LabCamera;
import org.genesis.fusion.ReactorView;

import com.studio.genesis.grpc.PartonCurve;
import com.studio.genesis.grpc.PartonEvent;
import com.studio.genesis.grpc.PartonRecord;
import com.studio.genesis.grpc.PartonState;
import com.studio.genesis.grpc.UniverseFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * La mer de partons : le nucléon à une résolution (Q², x_min) donnée. Le moteur (logic/quantumlab/partons.py, parton_cloud.py) calcule les
 * distributions x·f(x) par DGLAP et tire une population de gluons et de paires quark–antiquark qui fluctue (g → q q̄, q q̄ → g,
 * g → g g…) en conservant à chaque événement l'impulsion, la charge, la saveur et la couleur. Cette vue ne calcule rien : elle
 * montre la population à l'instant lu, les courbes, et relit les conservations sur les événements reçus.
 *
 * Les positions des partons sont illustratives (les PDF n'en contiennent pas) ; tout le reste — nombres, saveurs, couleurs,
 * fractions d'impulsion, instants — vient du moteur.
 */
public final class PartonView {
    public interface Actions {
        void back();
        void resolution(double q2, double xMin);
        void lastStrike(double xMin);
        void reset();
    }

    private static final Color INK = Color.web("#E8EEF7"), LABEL = Color.web("#8896AB"), DIM = Color.web("#5F6C80");
    private static final Color TEAL = Color.web("#3DD6C6"), AMBER = Color.web("#F5B84B"), CORAL = Color.web("#FF7A6B");
    private static final Color GLUON = DocStyle.GOLD;
    private static final double SIDE = 300, GAP = 16, STRIP = 160, BAG_RADIUS_FM = 1.08, PLAY_SECONDS = 14;
    static final String[] EVENT_KINDS = {"g->qqbar", "qqbar->g", "g->gg", "gg->g", "q->qg", "qg->q"};
    static final String[] EVENT_NAMES = {"g → q q̄", "q q̄ → g", "g → g g", "g g → g", "q → q g", "q g → q"};

    private final BorderPane root = new BorderPane();
    private final Canvas scene = new Canvas();
    private final LabCamera camera = new LabCamera();
    private final SubRender render = new SubRender(camera);
    private final FusionDashboard dashboard = new FusionDashboard();
    private final Actions actions;
    private final Slider q2Slider = new Slider(Math.log10(.26), Math.log10(3000), 1), xSlider = new Slider(-4, Math.log10(.2), -3);
    private final Slider scrub = new Slider(0, 1000, 0);
    private final ToggleButton play = new ToggleButton("Ⅱ Pause");
    private final ToggleButton slow = new ToggleButton("lent"), normal = new ToggleButton("normal"), fast = new ToggleButton("rapide");
    private final Label q2Label = new Label(), xLabel = new Label(), clock = new Label();

    private PartonState state;
    private List<PartonRecord> partons = List.of();
    private List<PartonEvent> events = List.of();
    private int run = -1;
    private double time, speed = 1, lastDt = 1 / 60.0, manualZoom = 1, mouseX = -1, mouseY = -1, previousX, previousY;
    private boolean updating, dragging;

    public PartonView(Actions actions) {
        this.actions = actions;
        dashboard.documentary = false;                // le verre fumé de la chambre de collision
        render.membraneCells = 45_000;                // le verre du sac : une grille plus grossière, lissée au tracé
        root.getStyleClass().add("doc-lab");
        camera.yaw = -.55;
        camera.pitch = .3;

        Label title = label(FusionDashboard.spaced("LA MER DE PARTONS"), 11, "#8896AB");
        Label sub = label("Plus la résolution Q² monte, plus le nucléon montre de gluons et de paires quark–antiquark.", 13, "#E8EEF7");
        Button exit = new Button("← Collision");
        exit.getStyleClass().add("doc-button");
        exit.setFocusTraversable(false);
        exit.setOnAction(e -> actions.back());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox top = new HBox(20, new VBox(4, title, sub), gap, exit);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(14, 22, 13, 22));
        top.getStyleClass().add("doc-bar");
        root.setTop(top);

        StackPane center = new StackPane(scene);
        center.setStyle("-fx-background-color:#03060C;");
        scene.widthProperty().bind(center.widthProperty());
        scene.heightProperty().bind(center.heightProperty());
        scene.setOnMousePressed(e -> { dragging = true; previousX = e.getX(); previousY = e.getY(); });
        scene.setOnMouseDragged(e -> {
            camera.drag(e.getX() - previousX, e.getY() - previousY);
            previousX = e.getX();
            previousY = e.getY();
            mouseX = e.getX();
            mouseY = e.getY();
        });
        scene.setOnMouseReleased(e -> dragging = false);
        scene.setOnMouseMoved(e -> { mouseX = e.getX(); mouseY = e.getY(); });
        scene.setOnMouseExited(e -> { mouseX = mouseY = -1; });
        scene.setOnScroll(e -> manualZoom = Math.max(.4, Math.min(3, manualZoom * Math.exp(-e.getDeltaY() / 500))));
        root.setCenter(center);

        q2Slider.valueProperty().addListener((o, a, b) -> q2Label.setText(q2Text(Math.pow(10, b.doubleValue()))));
        xSlider.valueProperty().addListener((o, a, b) -> xLabel.setText(xText(Math.pow(10, b.doubleValue()))));
        q2Label.setText(q2Text(Math.pow(10, q2Slider.getValue())));
        xLabel.setText(xText(Math.pow(10, xSlider.getValue())));
        for (Slider s : new Slider[]{q2Slider, xSlider}) {
            s.setFocusTraversable(false);
            s.setOnMouseReleased(e -> send());
            s.setOnKeyReleased(e -> send());
        }
        q2Label.setTextFill(INK);
        xLabel.setTextFill(INK);
        Button low = preset("faible 0,3", .3), mid = preset("10", 10), high = preset("fort 1000", 1000);
        Button strike = new Button("Q² de mon dernier coup");
        strike.getStyleClass().add("doc-button");
        strike.setMaxWidth(Double.MAX_VALUE);
        strike.setFocusTraversable(false);
        strike.setOnAction(e -> actions.lastStrike(Math.pow(10, xSlider.getValue())));
        Button reset = new Button("Nouveau tirage");
        reset.getStyleClass().add("doc-primary");
        reset.setMaxWidth(Double.MAX_VALUE);
        reset.setFocusTraversable(false);
        reset.setOnAction(e -> actions.reset());

        Label legend = label("Gros disques : quarks de valence (le proton en a toujours trois). Étincelles colorées : quarks de mer, "
                + "et leurs antiquarks en anticouleur ; étincelles dorées : gluons — des fluctuations brèves (durée ħ/Q), plus nombreuses "
                + "quand Q² monte. Un éclair doré relie un gluon au quark qu'il touche (l'échange), ses coudes espacés de ħc/E. Taille ∝ x^⅓. "
                + "La membrane est bosselée par ce qui la heurte (∝ x) et tremble là où un événement a lieu. Positions illustratives.", 10.5, "#8896AB");
        Label gluon = label("Un gluon n'a pas de masse, son spin vaut 1 et il porte une couleur et une anticouleur (huit "
                + "combinaisons) : c'est le vecteur de l'interaction forte, et parce qu'il est coloré il se scinde lui-même (g → g g).", 10.5, "#8896AB");
        Label note = label("", 10.5, "#8896AB");
        note.setId("parton-note");

        VBox side = new VBox(16,
                section("RÉSOLUTION Q²", q2Label, q2Slider, new HBox(6, low, mid, high)),
                strike,
                section("PLUS PETIT x RÉSOLU", xLabel, xSlider),
                reset,
                section("LÉGENDE", legend),
                section("LE GLUON", gluon),
                section("MODÈLE ET LIMITES", note));
        side.setPadding(new Insets(18));
        side.setPrefWidth(SIDE);
        side.setStyle("-fx-background-color:rgba(6,28,46,0.38);");
        ScrollPane scroll = new ScrollPane(side);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefWidth(SIDE + 14);
        scroll.setMinWidth(SIDE + 14);
        scroll.setStyle("-fx-background:transparent;-fx-background-color:transparent;-fx-border-color:rgba(143,227,255,0.15);-fx-border-width:0 1 0 0;");
        root.setLeft(scroll);
        this.note = note;

        play.getStyleClass().add("doc-button");
        play.setFocusTraversable(false);
        play.setSelected(true);
        play.setOnAction(e -> play.setText(play.isSelected() ? "Ⅱ Pause" : "▶ Lecture"));
        ToggleGroup speeds = new ToggleGroup();
        ToggleButton[] buttons = {slow, normal, fast};
        double[] rates = {.4, 1, 3};
        for (int i = 0; i < 3; i++) {
            ToggleButton t = buttons[i];
            t.setToggleGroup(speeds);
            t.setFocusTraversable(false);
            t.getStyleClass().add("doc-segment");
            final double r = rates[i];
            t.setOnAction(e -> { t.setSelected(true); speed = r; });
        }
        slow.getStyleClass().add("left");
        fast.getStyleClass().add("right");
        normal.setSelected(true);
        scrub.valueProperty().addListener((o, a, b) -> {
            if (!updating) time = b.doubleValue() / 1000 * duration();
        });
        clock.setTextFill(LABEL);
        clock.setMinWidth(150);
        HBox bottom = new HBox(14, play, scrub, clock, new HBox(slow, normal, fast));
        HBox.setHgrow(scrub, Priority.ALWAYS);
        bottom.setAlignment(Pos.CENTER);
        bottom.setPadding(new Insets(11, 20, 11, 20));
        bottom.getStyleClass().add("doc-bar");
        root.setBottom(bottom);
    }

    private final Label note;

    public BorderPane getNode() { return root; }

    public void togglePlayback() {
        play.setSelected(!play.isSelected());
        play.setText(play.isSelected() ? "Ⅱ Pause" : "▶ Lecture");
    }

    /** Place le film à l'instant t (fm/c) et le met en pause. */
    void seek(double t) {
        time = Math.max(0, Math.min(duration(), t));
        play.setSelected(false);
        play.setText("▶ Lecture");
    }

    private void send() {
        if (updating) return;
        actions.resolution(Math.pow(10, q2Slider.getValue()), Math.pow(10, xSlider.getValue()));
    }

    private Button preset(String text, double q2) {
        Button b = new Button(text);
        b.getStyleClass().add("doc-button");
        b.setFocusTraversable(false);
        b.setOnAction(e -> {
            q2Slider.setValue(Math.log10(q2));
            send();
        });
        return b;
    }

    private static Label label(String text, double size, String color) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill:" + color + ";-fx-font-size:" + size + "px;");
        l.setWrapText(true);
        return l;
    }

    private static VBox section(String title, javafx.scene.Node... nodes) {
        VBox box = new VBox(8);
        box.getChildren().add(label(FusionDashboard.spaced(title), 9, "#8896AB"));
        box.getChildren().addAll(nodes);
        return box;
    }

    static String q2Text(double q2) {
        return String.format(I18n.locale(), "Q² = %s GeV²  (Q = %s GeV)", ReactorView.fmt(q2), ReactorView.fmt(Math.sqrt(q2)));
    }

    static String xText(double x) {
        return String.format(I18n.locale(), "x > %s", x < .01 ? String.format(I18n.locale(), "%.1e", x) : ReactorView.fmt(x));
    }

    private double duration() {
        return state == null ? 1 : Math.max(1e-9, state.getDurationFmc());
    }

    // ================================================================== les données
    public void applyFrame(UniverseFrame frame) {
        PartonState incoming = frame.getPartons();
        if (!incoming.getActive()) return;
        state = incoming;
        if (incoming.getPartonsCount() > 0) {
            partons = incoming.getPartonsList();
            events = incoming.getEventsList();
        }
        updating = true;
        q2Slider.setValue(Math.log10(Math.max(.26, incoming.getQ2Gev2())));
        xSlider.setValue(Math.log10(Math.max(1e-4, incoming.getXMin())));
        updating = false;
        note.setText(incoming.getNote());
        if (incoming.getRun() != run && !partons.isEmpty()) {
            run = incoming.getRun();
            time = 0;
            play.setSelected(true);
            play.setText("Ⅱ Pause");
        }
    }

    public void tick(double dt) {
        lastDt = Math.min(.05, Math.max(1e-3, dt));
        camera.tick(lastDt, dragging, -1, 3);
        if (state != null && play.isSelected()) {
            time += lastDt * speed * duration() / PLAY_SECONDS;
            if (time >= duration()) {
                time = duration();
                play.setSelected(false);
                play.setText("▶ Lecture");
            }
        }
        updating = true;
        scrub.setValue(time / duration() * 1000);
        updating = false;
        draw();
    }

    // ================================================================== les lectures d'un instant
    static boolean alive(PartonRecord p, double t) {
        return p.getBornFmc() <= t && (p.getDiedFmc() < 0 || p.getDiedFmc() > t);
    }

    /** L'opacité d'un parton : il apparaît et disparaît en fondu sur `fade` (fm/c), sauf ceux du départ. */
    static double opacity(PartonRecord p, double t, double fade) {
        double in = p.getBornFmc() <= 0 ? 1 : Math.min(1, (t - p.getBornFmc()) / fade);
        double out = p.getDiedFmc() < 0 ? 1 : Math.min(1, (p.getDiedFmc() - t) / fade);
        return Math.max(0, Math.min(in, out));
    }

    static Color colorOf(int index, boolean anti) {
        Color[] plain = {DocStyle.RED, DocStyle.GREEN, DocStyle.BLUE};
        Color[] opposite = {DocStyle.ANTI_RED, DocStyle.ANTI_GREEN, DocStyle.ANTI_BLUE};
        return (anti ? opposite : plain)[Math.floorMod(index, 3)];
    }

    /** Les nombres de partons vivants à t : {gluons, quarks de mer, antiquarks, valence}. */
    static int[] counts(List<PartonRecord> partons, double t) {
        int gluons = 0, quarks = 0, antis = 0, valence = 0;
        for (PartonRecord p : partons) {
            if (!alive(p, t)) continue;
            if (p.getKind().equals("g")) gluons++;
            else if (p.getKind().equals("qbar")) antis++;
            else if (p.getValence()) valence++;
            else quarks++;
        }
        return new int[]{gluons, quarks, antis, valence};
    }

    /** Les événements de chaque type jusqu'à t. */
    static int[] eventCounts(List<PartonEvent> events, double t) {
        int[] n = new int[EVENT_KINDS.length];
        for (PartonEvent e : events) {
            if (e.getTimeFmc() > t) break;
            for (int i = 0; i < n.length; i++) if (EVENT_KINDS[i].equals(e.getKind())) n[i]++;
        }
        return n;
    }

    // ================================================================== le dessin
    private void draw() {
        double w = scene.getWidth(), h = scene.getHeight();
        if (w < 2 || h < 2) return;
        GraphicsContext g = scene.getGraphicsContext2D();
        // le fond de la chambre de collision : un bleu nuit qui tombe au noir
        g.setFill(new RadialGradient(0, 0, w * .38, h * .4, Math.max(w, h) * .8, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0C1830")), new Stop(.55, Color.web("#060C18")), new Stop(1, Color.web("#020409"))));
        g.fillRect(0, 0, w, h);
        if (state == null || partons.isEmpty()) {
            g.setFill(LABEL);
            g.setFont(Font.font("Segoe UI", 13));
            g.setTextAlign(TextAlignment.CENTER);
            I18n.fillText(g, "En attente du calcul Python…", w / 2, h / 2);
            return;
        }
        double stageWidth = w - FusionDashboard.WIDTH - 2 * GAP, stageHeight = h - STRIP - 2 * GAP;
        camera.targetX = camera.targetY = camera.targetZ = 0;
        camera.setup(stageWidth, stageHeight, stageWidth / 2, stageHeight / 2, 3.9 * manualZoom);
        g.setLineCap(StrokeLineCap.ROUND);
        render.attach(g, 0, stageWidth, stageHeight);
        render.clock = time * 40 + System.nanoTime() * 0;
        double fade = Math.max(1e-6, state.getTauFmc() * .6);
        // les positions, une seule fois par image
        List<PartonRecord> live = new ArrayList<>();
        List<double[]> where = new ArrayList<>(), screen = new ArrayList<>();
        for (PartonRecord p : partons) {
            if (p.getBornFmc() > time && p.getBornFmc() > 0) continue;
            if (p.getDiedFmc() >= 0 && p.getDiedFmc() < time) continue;
            double[] at = moved(p, time);
            double[] s = camera.project(at[0], at[1], at[2]);
            if (s == null) continue;
            live.add(p);
            where.add(at);
            screen.add(s);
        }
        drawBag(live, where, fade);
        // la mer : des étincelles (un parton de mer ou un gluon est une fluctuation brève, pas une bille) — leur nombre est celui du
        // moteur, qui croît avec Q² : le nuage s'épaissit tout seul quand on résout plus fin
        List<double[]> quarks = new ArrayList<>();
        for (int k = 0; k < live.size(); k++) if (!live.get(k).getKind().equals("g")) quarks.add(where.get(k));
        g.setGlobalBlendMode(javafx.scene.effect.BlendMode.ADD);
        for (int k = 0; k < live.size(); k++) {
            PartonRecord p = live.get(k);
            if (p.getValence()) continue;
            double a = opacity(p, time, fade);
            if (a <= .01) continue;
            double[] s = screen.get(k);
            boolean glu = p.getKind().equals("g"), anti = p.getKind().equals("qbar");
            Color c = glu ? GLUON : colorOf(anti ? p.getAnticolor() : p.getColor(), anti);
            double size = (glu ? 1.2 : 1.6) + 3.5 * Math.cbrt(Math.max(1e-6, p.getX()));
            double twinkle = .65 + .35 * Math.sin(time / Math.max(1e-6, state.getTauFmc()) * 6.3 + p.getId());
            g.setFill(c.deriveColor(0, 1, 1, .22 * a * twinkle));
            g.fillOval(s[0] - size * 2.6, s[1] - size * 2.6, size * 5.2, size * 5.2);
            g.setFill(c.interpolate(Color.WHITE, .55).deriveColor(0, 1, 1, .95 * a * twinkle));
            g.fillOval(s[0] - size * .55, s[1] - size * .55, size * 1.1, size * 1.1);
        }
        g.setGlobalBlendMode(javafx.scene.effect.BlendMode.SRC_OVER);
        // l'échange : un gluon à moins de 0,4 fm d'un quark y est relié par un arc (absorbé ou émis) — seulement ceux-là
        for (int k = 0; k < live.size(); k++) {
            PartonRecord p = live.get(k);
            if (!p.getKind().equals("g")) continue;
            double a = opacity(p, time, fade);
            if (a <= .05) continue;
            double[] at = where.get(k), partner = null;
            double best = .4;
            for (double[] q : quarks) {
                double d = SubRender.norm(SubRender.sub(q, at));
                if (d < best) { best = d; partner = q; }
            }
            if (partner == null) continue;
            double energyGev = Math.max(1e-4, p.getX()) * .938, lambda = Math.max(.03, Math.min(.25, .1973 / energyGev));
            long flicker = (long) Math.floor(time / Math.max(1e-6, state.getTauFmc()));
            render.arc(at, partner, Math.max(.04, lambda * .6), .02, 1, 1, p.getId() * 17L + flicker, GLUON, .8 * a * (1 - best / .4));
        }
        // les trois quarks de valence, par-dessus : des sphères lumineuses
        PartonRecord hovered = null;
        double bestHover = 16;
        for (int k = 0; k < live.size(); k++) {
            PartonRecord p = live.get(k);
            double[] s = screen.get(k);
            if (p.getValence()) quark(p, where.get(k), opacity(p, time, fade));
            double d = Math.hypot(s[0] - mouseX, s[1] - mouseY);
            if (mouseX >= 0 && d < bestHover) { bestHover = d; hovered = p; }
        }
        drawFlashes(g, fade);
        drawProbe(g, stageWidth, stageHeight);
        if (hovered != null) tooltip(g, hovered);
        drawScale(g, stageWidth, stageHeight);
        drawPdfStrip(g, GAP, h - STRIP - GAP, stageWidth - GAP, STRIP);
        drawBoard(g, w);
        clock.setText(String.format(I18n.locale(), "t = %.3f fm/c · %.2f ys", time, time * 3.3356));
    }

    /**
     * La sonde : le photon virtuel qui éclaire le proton. Tout vient de Q : sa longueur d'onde réduite λ = ħc/Q (la taille de ce
     * qu'il distingue — un grand λ voit un proton uniforme, λ ≈ 0,3 fm voit trois quarks, un λ minuscule voit la mer), son énergie
     * (sa couleur, du rouge pour un photon mou au blanc bleuté pour un dur ; son éclat ∝ log Q), sa fréquence équivalente f = Qc/h.
     * Le faisceau a la largeur λ (au moins 2 px), son onde est tracée à l'échelle (période λ, si elle dépasse 5 px), elle avance à c ;
     * là où il touche le nucléon, une tache de rayon λ : la plus petite chose qu'il sépare.
     */
    private void drawProbe(GraphicsContext g, double stageWidth, double stageHeight) {
        double[] c = camera.project(0, 0, 0), r = camera.right();
        double[] e = camera.project(r[0], r[1], r[2]);
        if (c == null || e == null) return;
        double perFm = Math.hypot(e[0] - c[0], e[1] - c[1]);
        double q = Math.sqrt(state.getQ2Gev2()), lambdaFm = 0.1973269804 / q, frequency = q * 1e9 * 1.602176634e-19 / 6.62607015e-34;
        Color hue = SubRender.photonColour(q * 1000);
        double shine = Math.min(1, .45 + .22 * Math.log10(1 + state.getQ2Gev2()));
        double width = Math.max(2, Math.min(60, lambdaFm * perFm)), drawn = lambdaFm * perFm;
        double start = 18, end = c[0] - BAG_RADIUS_FM * perFm, y = c[1];
        if (end - start < 40) return;
        // le faisceau : un ruban lumineux de largeur λ
        g.setGlobalBlendMode(javafx.scene.effect.BlendMode.ADD);
        g.setStroke(hue.deriveColor(0, 1, 1, .12 * shine));
        g.setLineWidth(width * 2.4);
        g.strokeLine(start, y, end, y);
        g.setStroke(hue.deriveColor(0, 1, 1, .3 * shine));
        g.setLineWidth(width);
        g.strokeLine(start, y, end, y);
        g.setGlobalBlendMode(javafx.scene.effect.BlendMode.SRC_OVER);
        // son onde, à l'échelle, qui avance à c
        if (drawn >= 5) {
            int n = (int) Math.min(1400, Math.max(40, (end - start) / 1.2));
            double[] xs = new double[n], ys = new double[n];
            double phase = 2 * Math.PI * time / Math.max(1e-9, lambdaFm);
            for (int i = 0; i < n; i++) {
                double x = start + (end - start) * i / (n - 1.0);
                xs[i] = x;
                ys[i] = y - width * .45 * Math.min(1, (x - start) / 40) * Math.sin(2 * Math.PI * (x - start) / drawn - phase % (2 * Math.PI));
            }
            g.setStroke(hue.interpolate(Color.WHITE, .3).deriveColor(0, 1, 1, .95 * shine));
            g.setLineWidth(1.4);
            g.strokePolyline(xs, ys, n);
        } else {
            g.setStroke(hue.interpolate(Color.WHITE, .5).deriveColor(0, 1, 1, .9 * shine));
            g.setLineWidth(1.2);
            g.strokeLine(start, y, end, y);
        }
        // la tache de résolution, là où il touche le nucléon
        double spot = Math.max(3, lambdaFm * perFm);
        g.setGlobalBlendMode(javafx.scene.effect.BlendMode.ADD);
        g.setFill(new RadialGradient(0, 0, end, y, spot * 2, false, CycleMethod.NO_CYCLE, new Stop(0, hue.deriveColor(0, 1, 1, .8 * shine)),
                new Stop(1, hue.deriveColor(0, 1, 1, 0))));
        g.fillOval(end - spot * 2, y - spot * 2, spot * 4, spot * 4);
        g.setGlobalBlendMode(javafx.scene.effect.BlendMode.SRC_OVER);
        g.setStroke(Color.WHITE.deriveColor(0, 1, 1, .8));
        g.setLineWidth(1);
        g.strokeOval(end - spot, y - spot, 2 * spot, 2 * spot);
        // ses nombres
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI Semibold", 11));
        g.setFill(hue.interpolate(Color.WHITE, .4));
        double ty = stageHeight - 74;                              // les nombres sous la scène, pas sur le nucléon
        I18n.fillText(g, "photon virtuel", start, ty);
        g.setFont(Font.font("Segoe UI", 10.5));
        g.setFill(LABEL);
        I18n.fillText(g, String.format(I18n.locale(), "Q = %s GeV  ·  λ = ħc/Q = %s fm  ·  f = Qc/h ≈ %s Hz (équivalente)", ReactorView.fmt(q),
                lambdaFm < .01 ? String.format(I18n.locale(), "%.4f", lambdaFm) : ReactorView.fmt(lambdaFm),
                String.format(I18n.locale(), "%.1e", frequency).replace("e+", "·10^")), start, ty + 16);
        g.setFill(DIM);
        I18n.fillText(g, drawn < 5 ? "onde trop fine pour être tracée à l'échelle : la tache blanche a le rayon λ (agrandi à 3 px)"
                : "largeur du faisceau et période de l'onde : λ, à l'échelle ; la tache : ce qu'il distingue", start, ty + 31);
    }

    // ================================================================== le mouvement des partons
    private final java.util.Map<Integer, double[]> origins = new java.util.HashMap<>();
    private int originsRun = -1;

    /**
     * Où est un parton à l'instant t. Gluons et quarks légers ont une masse nulle ou presque : ils filent à la vitesse de la
     * lumière et rebondissent sur la paroi du nucléon (le « sac » du MIT). Un parton né d'un autre part de l'endroit où était
     * son parent à cet instant — rien n'apparaît ailleurs que là où il est créé. Seule la direction de départ est tirée (les
     * PDF ne donnent pas de direction) ; la vitesse et le confinement, eux, sont ceux de la physique.
     */
    double[] moved(PartonRecord p, double t) {
        double[] start = origin(p);
        double travel = Math.max(0, t - Math.max(0, p.getBornFmc()));
        return billiard(start, direction(p.getId()), travel, BAG_RADIUS_FM);
    }

    private double[] origin(PartonRecord p) {
        if (originsRun != run) {
            origins.clear();
            originsRun = run;
        }
        double[] cached = origins.get(p.getId());
        if (cached != null) return cached;
        double[] out = {p.getPositionFm(0), p.getPositionFm(1), p.getPositionFm(2)};
        if (p.getBornFmc() > 0) {
            // le parent : celui qui meurt à l'instant de cette naissance et dont la position de départ est la plus proche
            PartonRecord parent = null;
            double best = Double.POSITIVE_INFINITY;
            for (PartonRecord q : partons) {
                if (q.getId() >= p.getId() || Math.abs(q.getDiedFmc() - p.getBornFmc()) > 1e-12) continue;
                double d = Math.hypot(Math.hypot(q.getPositionFm(0) - out[0], q.getPositionFm(1) - out[1]), q.getPositionFm(2) - out[2]);
                if (d < best) { best = d; parent = q; }
            }
            if (parent != null) {
                double[] where = moved(parent, p.getBornFmc());
                out = billiard(where, new double[]{0, 0, 0}, 0, BAG_RADIUS_FM);
            }
        }
        origins.put(p.getId(), out);
        return out;
    }

    /** Une direction de départ fixée par l'identifiant (spirale de Fibonacci sur la sphère). */
    static double[] direction(int id) {
        double z = 1 - 2 * ((id * 0.6180339887) % 1.0), r = Math.sqrt(Math.max(0, 1 - z * z)), a = id * 2.399963229728653;
        return new double[]{r * Math.cos(a), r * Math.sin(a), z};
    }

    /** Une trajectoire rectiligne à la vitesse c, réfléchie sur la sphère de rayon R. */
    static double[] billiard(double[] start, double[] dir, double distance, double radius) {
        double[] p = start.clone(), d = dir.clone();
        double norm = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        if (norm > radius * .98) for (int k = 0; k < 3; k++) p[k] *= radius * .98 / norm;
        double left = distance;
        for (int bounce = 0; bounce < 12 && left > 1e-9; bounce++) {
            double b = p[0] * d[0] + p[1] * d[1] + p[2] * d[2];
            double c = p[0] * p[0] + p[1] * p[1] + p[2] * p[2] - radius * radius;
            double hit = -b + Math.sqrt(Math.max(0, b * b - c));
            if (hit >= left || d[0] * d[0] + d[1] * d[1] + d[2] * d[2] < 1e-12) {
                for (int k = 0; k < 3; k++) p[k] += d[k] * left;
                return p;
            }
            for (int k = 0; k < 3; k++) p[k] += d[k] * hit;
            double inv = 1 / radius, dot = (p[0] * d[0] + p[1] * d[1] + p[2] * d[2]) * inv;
            for (int k = 0; k < 3; k++) d[k] -= 2 * dot * p[k] * inv;
            left -= hit;
        }
        return p;
    }

    /**
     * La membrane du nucléon, en verre éclairé (normales, reflet spéculaire, Fresnel — on voit qu'on regarde une sphère) et vivante :
     * poussée de l'intérieur par les partons qui arrivent à la paroi, d'autant plus que leur fraction d'impulsion x est grande (un
     * rebond transmet 2p cos θ), tendue vers les quarks de valence, et qui tremble là où un événement vient d'avoir lieu. Seules les
     * 24 poussées les plus fortes sont gardées (les autres sont invisibles à cette échelle).
     */
    private void drawBag(List<PartonRecord> live, List<double[]> where, double fade) {
        List<SubRender.Blob> blobs = new ArrayList<>();
        blobs.add(new SubRender.Blob(new double[]{0, 0, 0}, BAG_RADIUS_FM * 1.02, .015, 0));
        List<double[]> pushes = new ArrayList<>();
        for (int k = 0; k < live.size(); k++) {
            PartonRecord p = live.get(k);
            double[] at = where.get(k);
            double r = SubRender.norm(at);
            if (p.getValence()) blobs.add(new SubRender.Blob(SubRender.scale(at, .8), .42, 0, 0));
            else if (r > .75 * BAG_RADIUS_FM)
                pushes.add(new double[]{at[0], at[1], at[2], (.08 + .45 * Math.cbrt(Math.max(1e-6, p.getX()))) * opacity(p, time, fade)});
        }
        pushes.sort((a, b) -> Double.compare(b[3], a[3]));
        for (int k = 0; k < Math.min(24, pushes.size()); k++) {
            double[] q = pushes.get(k);
            blobs.add(new SubRender.Blob(new double[]{q[0], q[1], q[2]}, q[3], 0, 0));
        }
        double span = Math.max(1e-6, state.getTauFmc() * 1.2);
        for (PartonEvent e : events) {
            double age = time - e.getTimeFmc();
            if (age < 0) break;
            if (age > span || e.getChildrenCount() == 0) continue;
            PartonRecord child = partons.get(e.getChildren(0));
            double[] here = moved(child, Math.max(time, child.getBornFmc()));
            if (SubRender.norm(here) < .6 * BAG_RADIUS_FM) continue;
            double f = age / span;
            blobs.add(new SubRender.Blob(here, .3 * (1 - f) * (1 + .25 * Math.sin(f * 18)), 0, 0));
        }
        render.membrane(blobs, List.of(), 1);
    }

    private static double quarkRadiusFm(PartonRecord p) {
        return p.getValence() ? .2 : .045 + .1 * Math.cbrt(p.getX());
    }

    /** Un quark ou un antiquark : une sphère lumineuse de sa couleur (l'antiquark, de son anticouleur). */
    private void quark(PartonRecord p, double[] at, double alpha) {
        boolean anti = p.getKind().equals("qbar");
        Color c = colorOf(anti ? p.getAnticolor() : p.getColor(), anti);
        render.quark(at, quarkRadiusFm(p), c, p.getValence() ? p.getFlavor() + (anti ? "̄" : "") : null, alpha);
    }

    /**
     * Un gluon : un filament d'énergie, le long de sa direction de vol, long de ∝ x^⅓, dont les coudes sont espacés de sa longueur
     * d'onde réduite λ = ħc/E (E ≈ x·M, l'énergie qu'il porte ; bornée pour rester visible) ; une couleur à l'avant, une anticouleur
     * à l'arrière. S'il passe à moins de 0,4 fm d'un quark, un arc les relie : il est absorbé ou émis — c'est l'échange qui fait la
     * force. Le filament se tend et se relâche (le tirage de ses coudes change à chaque fluctuation, durée ħ/Q).
     */
    private void gluon(PartonRecord p, double[] at, double alpha) {
        double length = .12 + .32 * Math.cbrt(p.getX());
        double[] d = direction(p.getId());
        double[] a = SubRender.add(at, SubRender.scale(d, -length / 2)), b = SubRender.add(at, SubRender.scale(d, length / 2));
        double energyGev = Math.max(1e-4, p.getX()) * .938, lambda = Math.max(.03, Math.min(.25, .1973 / energyGev));
        long flicker = (long) Math.floor(time / Math.max(1e-6, state.getTauFmc()));
        render.arc(a, b, lambda, .025, 1, 1.1, p.getId() * 31L + flicker, GLUON, alpha);
        render.glow(b, .07, colorOf(p.getColor(), false), .8 * alpha);
        render.glow(a, .07, colorOf(p.getAnticolor(), true), .8 * alpha);
        double best = .4;
        double[] partner = null;
        for (PartonRecord q : partons) {
            if (q.getKind().equals("g") || !alive(q, time)) continue;
            double[] qa = moved(q, time);
            double dist = SubRender.norm(SubRender.sub(qa, at));
            if (dist < best) { best = dist; partner = qa; }
        }
        if (partner != null) render.arc(at, partner, Math.max(.04, lambda * .6), .018, 1, .9, p.getId() * 17L + flicker, GLUON, .7 * alpha * (1 - best / .4));
    }

    /** Un événement : un anneau et son nom, pendant ≈ 1,2 τ après l'instant où il a lieu. */
    private void drawFlashes(GraphicsContext g, double fade) {
        double span = Math.max(1e-6, state.getTauFmc() * 1.2);
        for (PartonEvent e : events) {
            double age = time - e.getTimeFmc();
            if (age < 0) break;
            if (age > span || e.getChildrenCount() == 0) continue;
            boolean pair = e.getKind().equals("g->qqbar") || e.getKind().equals("qqbar->g");
            if (!pair && !e.getKind().startsWith("q")) continue;      // g ↔ g g : compté dans le tableau, pas annoncé sur la scène
            PartonRecord child = partons.get(e.getChildren(0));
            double[] here = moved(child, Math.max(time, child.getBornFmc()));
            double[] s = camera.project(here[0], here[1], here[2]);
            if (s == null) continue;
            double f = age / span, r = 8 + 26 * f;
            Color c = pair ? TEAL : AMBER;
            render.glow(here, .12 + .45 * Math.cbrt(Math.max(1e-6, child.getX())), c.interpolate(Color.WHITE, .4), Math.max(0, 1 - f * 1.6));
            g.setStroke(c.deriveColor(0, 1, 1, 1 - f));
            g.setLineWidth(1.6);
            g.strokeOval(s[0] - r, s[1] - r, 2 * r, 2 * r);
            if (pair && f < .75) {
                g.setTextAlign(TextAlignment.LEFT);
                g.setFont(Font.font("Segoe UI Semibold", 10.5));
                g.setFill(c.deriveColor(0, 1, 1, 1 - f / .75));
                I18n.fillText(g, name(e.getKind()), s[0] + r + 4, s[1] - 2);
            }
        }
    }

    static String name(String kind) {
        for (int i = 0; i < EVENT_KINDS.length; i++) if (EVENT_KINDS[i].equals(kind)) return EVENT_NAMES[i];
        return kind;
    }

    private void tooltip(GraphicsContext g, PartonRecord p) {
        String[] colours = {"rouge", "vert", "bleu"}, anti = {"antirouge", "antivert", "antibleu"};
        String what = p.getKind().equals("g")
                ? "gluon · " + colours[Math.floorMod(p.getColor(), 3)] + " / " + anti[Math.floorMod(p.getAnticolor(), 3)]
                : (p.getKind().equals("qbar") ? "antiquark " + p.getFlavor() + " · " + anti[Math.floorMod(p.getAnticolor(), 3)]
                : (p.getValence() ? "quark de valence " : "quark ") + p.getFlavor() + " · " + colours[Math.floorMod(p.getColor(), 3)]);
        String detail = String.format(I18n.locale(), "x = %.4f · né à t = %.3f fm/c", p.getX(), Math.max(0, p.getBornFmc()));
        Font bold = Font.font("Segoe UI Semibold", 11), light = Font.font("Segoe UI", 10);
        double tw = Math.max(ReactorView.width(what, bold), ReactorView.width(detail, light)) + 18;
        double tx = Math.min(scene.getWidth() - FusionDashboard.WIDTH - 40 - tw, mouseX + 14), ty = Math.max(8, mouseY - 48);
        g.setFill(Color.color(.02, .03, .05, .94));
        g.fillRoundRect(tx, ty, tw, 38, 8, 8);
        g.setStroke(Color.color(1, 1, 1, .16));
        g.setLineWidth(1);
        g.strokeRoundRect(tx + .5, ty + .5, tw - 1, 37, 8, 8);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(bold);
        g.setFill(INK);
        I18n.fillText(g, what, tx + 9, ty + 16);
        g.setFont(light);
        g.setFill(LABEL);
        I18n.fillText(g, detail, tx + 9, ty + 30);
    }

    private void drawScale(GraphicsContext g, double stageWidth, double stageHeight) {
        double[] a = camera.project(0, 0, 0), r = camera.right();
        double[] b = camera.project(r[0], r[1], r[2]);
        if (a == null || b == null) return;
        double perFm = Math.hypot(b[0] - a[0], b[1] - a[1]);
        double fm = DocStyle.niceLength(110 / perFm), px = fm * perFm, x = stageWidth - px - 24, y = stageHeight - 26;
        g.setStroke(Color.color(1, 1, 1, .7));
        g.setLineWidth(1.5);
        g.strokeLine(x, y, x + px, y);
        g.strokeLine(x, y - 4, x, y + 4);
        g.strokeLine(x + px, y - 4, x + px, y + 4);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(Font.font("Segoe UI Semibold", 11));
        g.setFill(INK);
        I18n.fillText(g, String.format(I18n.locale(), fm < 1 ? "%.1f fm" : "%.0f fm", fm), x + px / 2, y - 8);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFont(Font.font("Segoe UI", 9.5));
        g.setFill(DIM);
        I18n.fillText(g, "sphère = rayon quadratique moyen du proton, 0,84 fm × √(5/3)", stageWidth - 24, y + 16);
    }

    // ================================================================== les courbes x·f(x)
    private static Color curveColor(String name) {
        return switch (name) {
            case "u_valence" -> CORAL;
            case "d_valence" -> Color.web("#6C9BFF");
            case "sea" -> TEAL;
            default -> AMBER;
        };
    }

    private static String curveLabel(String name) {
        return switch (name) {
            case "u_valence" -> "u valence";
            case "d_valence" -> "d valence";
            case "sea" -> "mer q+q̄";
            default -> "gluon ÷ 10";
        };
    }

    private static double shown(PartonCurve c, int i) {
        return c.getName().equals("gluon") ? c.getXf(i) / 10 : c.getXf(i);
    }

    private void drawPdfStrip(GraphicsContext g, double x, double y, double w, double h) {
        DocStyle.panel(g, x, y, w, h);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI Semibold", 9));
        g.setFill(LABEL);
        I18n.fillText(g, FusionDashboard.spaced("x·f(x) À CETTE RÉSOLUTION"), x + 16, y + 21);
        double left = x + 16, top = y + 32, pw = w - 32, ph = h - 32 - 28;
        // L'axe suit les quarks de valence ; la mer et les gluons, qui divergent à petit x, sont écrêtés en haut (la lecture au survol
        // donne leurs vraies valeurs).
        double lo = Math.log10(1e-4), hi = 0, ymax = .05;
        for (PartonCurve c : state.getCurvesList())
            if (c.getName().endsWith("valence"))
                for (int i = 0; i < c.getXCount(); i++) if (c.getX(i) >= 1e-4) ymax = Math.max(ymax, shown(c, i));
        ymax *= 2.0;
        g.setFill(Color.color(1, 1, 1, .03));
        g.fillRect(left, top, pw, ph);
        double cut = left + pw * (Math.log10(Math.max(1e-4, state.getXMin())) - lo) / (hi - lo);
        g.setFill(Color.color(0, 0, 0, .28));
        g.fillRect(left, top, cut - left, ph);
        g.setStroke(AMBER.deriveColor(0, 1, 1, .7));
        g.setLineDashes(3, 3);
        g.setLineWidth(1);
        g.strokeLine(cut, top, cut, top + ph);
        g.setLineDashes(null);
        g.setFont(Font.font("Segoe UI", 9));
        g.setFill(AMBER);
        g.setTextAlign(TextAlignment.LEFT);
        if (cut - left > 70) I18n.fillText(g, "non résolu", left + 5, top + 11);
        g.setTextAlign(TextAlignment.RIGHT);
        I18n.fillText(g, "x_min en pointillés · courbes écrêtées en haut", left + pw - 5, top + 11);
        g.setTextAlign(TextAlignment.LEFT);
        double legendX = left + pw;
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFont(Font.font("Segoe UI", 10));
        for (PartonCurve c : state.getCurvesList()) {
            List<Double> px = new ArrayList<>(), py = new ArrayList<>();
            for (int i = 0; i < c.getXCount(); i++) {
                if (c.getX(i) < 1e-4) continue;
                px.add(left + pw * (Math.log10(c.getX(i)) - lo) / (hi - lo));
                py.add(top + ph * (1 - Math.min(ymax, Math.max(0, shown(c, i))) / ymax));
            }
            g.setStroke(curveColor(c.getName()));
            g.setLineWidth(1.7);
            g.strokePolyline(px.stream().mapToDouble(Double::doubleValue).toArray(), py.stream().mapToDouble(Double::doubleValue).toArray(), px.size());
            g.setFill(curveColor(c.getName()));
            I18n.fillText(g, curveLabel(c.getName()), legendX, y + 21);
            legendX -= ReactorView.width(curveLabel(c.getName()), Font.font("Segoe UI", 10)) + 18;
        }
        g.setFont(Font.font("Segoe UI", 9.5));
        g.setFill(DIM);
        g.setTextAlign(TextAlignment.LEFT);
        I18n.fillText(g, "10⁻⁴", left, y + h - 10);
        g.setTextAlign(TextAlignment.CENTER);
        I18n.fillText(g, "fraction d'impulsion x (échelle log)", left + pw / 2, y + h - 10);
        g.setTextAlign(TextAlignment.RIGHT);
        I18n.fillText(g, "1", left + pw, y + h - 10);
        if (mouseX >= left && mouseX <= left + pw && mouseY >= top && mouseY <= top + ph) {
            double xv = Math.pow(10, lo + (hi - lo) * (mouseX - left) / pw);
            g.setStroke(Color.color(1, 1, 1, .5));
            g.setLineWidth(1);
            g.strokeLine(mouseX, top, mouseX, top + ph);
            StringBuilder text = new StringBuilder(String.format(I18n.locale(), "x = %.4f", xv));
            for (PartonCurve c : state.getCurvesList()) {
                int best = 0;
                for (int i = 0; i < c.getXCount(); i++) if (Math.abs(Math.log(c.getX(i) / xv)) < Math.abs(Math.log(c.getX(best) / xv))) best = i;
                text.append(String.format(I18n.locale(), " · %s %.3f", curveLabel(c.getName()).replace(" ÷ 10", "").replace("÷ 10", ""), c.getXf(best)));
            }
            Font font = Font.font("Segoe UI Semibold", 10);
            double tw = ReactorView.width(text.toString(), font) + 14, tx = Math.min(left + pw - tw, Math.max(left, mouseX - tw / 2));
            g.setFill(Color.color(.02, .03, .05, .94));
            g.fillRoundRect(tx, top + 14, tw, 18, 5, 5);
            g.setFont(font);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFill(INK);
            I18n.fillText(g, text.toString(), tx + 7, top + 27);
        }
    }

    // ================================================================== le tableau de droite
    private void drawBoard(GraphicsContext g, double w) {
        double x = w - FusionDashboard.WIDTH - GAP, y = GAP;
        int[] n = counts(partons, time);
        double mv = state.getMomentumValence(), ms = state.getMomentumSea(), mg = state.getMomentumGluon();
        FusionDashboard.Split split = new FusionDashboard.Split("IMPULSION DU NUCLÉON", String.format(I18n.locale(), "%.0f %%", 100 * state.getMomentumTotal()),
                List.of(new FusionDashboard.Part("valence", String.format(I18n.locale(), "%.0f %%", 100 * mv), mv, Color.web("#8EA4C8")),
                        new FusionDashboard.Part("mer", String.format(I18n.locale(), "%.0f %%", 100 * ms), ms, TEAL),
                        new FusionDashboard.Part("gluons", String.format(I18n.locale(), "%.0f %%", 100 * mg), mg, AMBER)));
        List<FusionDashboard.Dial> dials = new ArrayList<>();
        dials.add(new FusionDashboard.Dial("g", "Gluons visibles (attendus " + ReactorView.fmt(state.getNGluon()) + ")", "" + n[0], "",
                Math.min(1, n[0] / Math.max(1, 1.6 * state.getNGluon()))));
        dials.add(new FusionDashboard.Dial("sea", "Quarks et antiquarks de mer (attendus " + ReactorView.fmt(2 * state.getNSeaQuark()) + ")",
                "" + (n[1] + n[2]), "", Math.min(1, (n[1] + n[2]) / Math.max(1, 2.2 * state.getNSeaQuark()))));
        dials.add(new FusionDashboard.Dial("as", "Couplage fort α_s(Q²)", String.format(I18n.locale(), "%.3f", state.getAlphaS()), "",
                Math.min(1, state.getAlphaS() / .8)));
        dials.add(new FusionDashboard.Dial("tau", "Durée d'une fluctuation ħ/Q", String.format(I18n.locale(), "%.3f", state.getTauFmc()), "fm/c",
                Math.min(1, state.getTauFmc() / .4)));
        FusionDashboard.Board board = new FusionDashboard.Board(state.getHadron().toUpperCase(Locale.ROOT) + " · RÉSOLUTION", split, dials, List.of(), List.of());
        dashboard.draw(g, x, y, board, lastDt, true);
        drawEvents(g, x, y + FusionDashboard.height(board, true) + 12);
    }

    private void drawEvents(GraphicsContext g, double x, double y) {
        double w = FusionDashboard.WIDTH, row = 18, h = 34 + row * EVENT_KINDS.length + 52;
        DocStyle.panel(g, x, y, w, h);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI Semibold", 9));
        g.setFill(LABEL);
        I18n.fillText(g, FusionDashboard.spaced("ÉVÉNEMENTS JUSQU'ICI"), x + 16, y + 21);
        int[] counts = eventCounts(events, time);
        double ry = y + 34;
        for (int i = 0; i < EVENT_KINDS.length; i++) {
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font("Segoe UI", 11));
            g.setFill(i < 2 ? TEAL : (i < 4 ? AMBER : INK));
            I18n.fillText(g, EVENT_NAMES[i], x + 16, ry + 12);
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFont(Font.font("Segoe UI Semibold", 11));
            g.setFill(INK);
            I18n.fillText(g, "" + counts[i], x + w - 16, ry + 12);
            ry += row;
        }
        double dx = 0, dq = 0, dc = 0;
        int seen = 0;
        for (PartonEvent e : events) {
            if (e.getTimeFmc() > time) break;
            dx = Math.max(dx, Math.abs(e.getDx()));
            dq = Math.max(dq, Math.abs(e.getDcharge()));
            dc = Math.max(dc, Math.abs(e.getDcolour()));
            seen++;
        }
        g.setFont(Font.font("Segoe UI", 10));
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(LABEL);
        I18n.fillText(g, String.format(I18n.locale(), "écarts maximaux sur %d événements :", seen), x + 16, ry + 18);
        g.setFill(INK);
        I18n.fillText(g, String.format(I18n.locale(), "Δx %.0e · Δcharge %.0e · Δcouleur %.0e", dx, dq, dc), x + 16, ry + 33);
    }
}
