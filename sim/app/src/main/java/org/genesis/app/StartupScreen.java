package org.genesis.app;

import com.studio.genesis.grpc.UniverseFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.DropShadow;
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
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

/**
 * L'écran de démarrage, le temps que le moteur Python s'ouvre (quelques secondes ; une minute au premier lancement).
 *
 * Au centre, une orbitale de l'hydrogène calculée : |ψ|² tiré point par point (rejet sur la densité exacte, comme la
 * vue ψ du laboratoire), qui tourne lentement et passe de 2p à 3d xz, 4f z³, 3d z², 4f xyz — couleur selon la densité, du bleu au
 * blanc puis à l'orangé, comme les figures de référence. Dessous, le nom, l'étape en cours ; en bas, l'appel à
 * soutenir Genesis, avec un bouton franc pour l'étoile sur GitHub et des liens discrets pour le reste.
 *
 * Il s'efface quand la première frame du moteur arrive (au plus tôt après MINIMUM_S, pour ne pas clignoter) ; si le
 * moteur ne démarre pas, il le dit et laisse fermer.
 */
final class StartupScreen {
    /** Liens du soutien : texte, adresse. Le premier est le bouton principal. */
    static final String[][] SUPPORT_LINKS = {
            {"★  Mettre une étoile sur GitHub", "https://github.com/Lightminedust/genesis"},
            {"Site de Genesis", "https://genesis.gemmi-e.com"},
            {"r/gemmie", "https://www.reddit.com/r/gemmie/"},
            {"Gemmie", "https://gemmi-e.com/contact"},
            {"nellawassi@gmail.com", "mailto:nellawassi@gmail.com"},
    };
    /** Le plus court affichage (s) : un moteur déjà chaud ne fait pas clignoter l'écran. */
    static final double MINIMUM_S = 1.2;
    /** Combien de temps chaque orbitale reste (s), et le fondu entre deux. */
    private static final double HOLD_S = 5.5, FADE_S = 1.2;
    private static final int POINTS = 5200, STARS = 220;

    /** Une orbitale tirée : ses points (x, y, z, rayon de la boule ≈ 1) et la densité relative de chacun. */
    record Cloud(String name, String quantum, float[] xyz, float[] weight) { }

    private final StackPane root = new StackPane();
    private final Canvas sky = new Canvas();
    private final Canvas cloud = new Canvas(440, 330);
    private final Label caption = new Label(), step = new Label(), elapsedLabel = new Label();
    private final Button close = new Button("Fermer");
    private final Canvas line = new Canvas(240, 3);
    private final float[] stars = new float[STARS * 4];
    private volatile List<Cloud> clouds = List.of();
    private Consumer<String> opener;
    private Runnable onClose;
    private double fade, elapsed, clock;
    private boolean showing, failed, ready;

    StartupScreen() {
        Random random = new Random(7);
        for (int k = 0; k < STARS; k++) {
            stars[4 * k] = random.nextFloat();
            stars[4 * k + 1] = random.nextFloat();
            stars[4 * k + 2] = .4f + 1.2f * random.nextFloat() * random.nextFloat();      // taille
            stars[4 * k + 3] = random.nextFloat() * 6.283f;                              // phase du scintillement
        }
        Pane back = new Pane(sky);
        sky.widthProperty().bind(root.widthProperty());
        sky.heightProperty().bind(root.heightProperty());
        cloud.heightProperty().bind(root.heightProperty().multiply(.36).map(h -> Math.max(170, Math.min(340, h.doubleValue()))));
        cloud.widthProperty().bind(cloud.heightProperty().multiply(1.34));

        Label title = new Label("GENESIS");
        title.setStyle("-fx-text-fill: #F2F6FC; -fx-font-size: 40px; -fx-font-weight: 300; -fx-font-family: 'Segoe UI Light', 'Segoe UI';");
        title.setEffect(new DropShadow(24, Color.web("#5B8CFF", .45)));
        Label tagline = new Label("La matière, calculée — du noyau à la molécule");
        tagline.setStyle("-fx-text-fill: #8FA4C2; -fx-font-size: 13.5px;");
        caption.setStyle("-fx-text-fill: #5E7396; -fx-font-size: 10.5px;");
        step.setStyle("-fx-text-fill: #C6D4E8; -fx-font-size: 12.5px;");
        elapsedLabel.setStyle("-fx-text-fill: #5B6B86; -fx-font-size: 10.5px; -fx-font-family: Consolas, monospace;");
        close.getStyleClass().add("dash-button");
        close.setFocusTraversable(false);
        close.setVisible(false);
        close.setManaged(false);
        close.setOnAction(e -> {
            showing = false;
            if (onClose != null) onClose.run();
        });
        Region gap = new Region();
        gap.setMinHeight(14);
        VBox center = new VBox(4, cloud, caption, gap, title, tagline, spacer(18), line, step, elapsedLabel, close);
        center.setAlignment(Pos.CENTER);

        BorderPane layout = new BorderPane(center);
        layout.setBottom(support());
        BorderPane.setMargin(layout.getBottom(), new Insets(0, 24, 26, 24));
        root.getChildren().addAll(back, layout);
        root.setStyle("-fx-background-color: #03060C;");
        root.setVisible(false);
        root.setOpacity(0);
        Thread sampler = new Thread(() -> clouds = sample(), "genesis-startup-orbitals");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static Region spacer(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        return r;
    }

    /** Le soutien : une étoile, deux phrases, le bouton GitHub ; les autres liens, en petit, dessous. */
    private Region support() {
        Canvas star = new Canvas(48, 48);
        drawStar(star.getGraphicsContext2D(), 24, 24, 18, 7.6);
        Label heading = new Label("Genesis est gratuit, et le restera.");
        heading.setStyle("-fx-text-fill: #E6EDF7; -fx-font-size: 14px; -fx-font-weight: bold;");
        Label text = new Label("Une étoile sur GitHub l'aide à être trouvé par d'autres curieux : un clic, et c'est le meilleur soutien.");
        text.setWrapText(true);
        text.setStyle("-fx-text-fill: #9FB0CB; -fx-font-size: 12px;");
        VBox words = new VBox(3, heading, text);
        words.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(words, Priority.ALWAYS);
        Button github = new Button(SUPPORT_LINKS[0][0]);
        github.setFocusTraversable(false);
        github.setMinWidth(Region.USE_PREF_SIZE);
        String base = "-fx-background-color: linear-gradient(to bottom, #FFE29A, #F2B84B); -fx-text-fill: #1C1404; "
                + "-fx-font-size: 13px; -fx-font-weight: bold; -fx-background-radius: 10; -fx-padding: 10 18; -fx-cursor: hand;";
        github.setStyle(base);
        DropShadow glow = new DropShadow(18, Color.web("#F2B84B", .45));
        github.setEffect(glow);
        github.setOnMouseEntered(e -> glow.setRadius(28));
        github.setOnMouseExited(e -> glow.setRadius(18));
        github.setTooltip(new Tooltip(SUPPORT_LINKS[0][1]));
        github.setOnAction(e -> open(SUPPORT_LINKS[0][1]));
        HBox top = new HBox(14, star, words, github);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox links = new HBox(4);
        links.setAlignment(Pos.CENTER);
        for (int k = 1; k < SUPPORT_LINKS.length; k++) {
            String[] link = SUPPORT_LINKS[k];
            if (k > 1) {
                Label dot = new Label("·");
                dot.setStyle("-fx-text-fill: #3A4A66;");
                links.getChildren().add(dot);
            }
            Button quiet = new Button(link[0]);
            quiet.getStyleClass().add("startup-link");
            quiet.setFocusTraversable(false);
            quiet.setTooltip(new Tooltip(link[1].replaceFirst("^mailto:", "")));
            quiet.setOnAction(e -> open(link[1]));
            links.getChildren().add(quiet);
        }
        VBox card = new VBox(10, top, links);
        card.setMaxWidth(640);
        card.setStyle("-fx-background-color: rgba(14,24,42,0.78); -fx-background-radius: 16; -fx-border-color: #24365A;"
                + " -fx-border-radius: 16; -fx-padding: 16 20 12 18;");
        StackPane holder = new StackPane(card);
        holder.setMaxHeight(Region.USE_PREF_SIZE);
        return holder;
    }

    private void open(String url) {
        if (opener != null) opener.accept(url);
    }

    /** Une étoile à cinq branches, dorée, avec un reflet. */
    private static void drawStar(GraphicsContext g, double cx, double cy, double outer, double inner) {
        double[] xs = new double[10], ys = new double[10];
        for (int k = 0; k < 10; k++) {
            double a = -Math.PI / 2 + k * Math.PI / 5, r = k % 2 == 0 ? outer : inner;
            xs[k] = cx + r * Math.cos(a);
            ys[k] = cy + r * Math.sin(a);
        }
        double glow = Math.min(cx, cy) - 1;                 // le halo reste dans le canevas (sinon, un carré)
        g.setFill(new RadialGradient(0, 0, cx, cy, glow, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#F2B84B", .32)), new Stop(1, Color.web("#F2B84B", 0))));
        g.fillOval(cx - glow, cy - glow, 2 * glow, 2 * glow);
        g.setFill(new LinearGradient(0, cy - outer, 0, cy + outer, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#FFE7A8")), new Stop(1, Color.web("#E8A93A"))));
        g.fillPolygon(xs, ys, 10);
    }

    StackPane getNode() {
        return root;
    }

    boolean isShowing() {
        return showing;
    }

    /** Montrer l'écran ; `open` ouvre une adresse, `onClose` sert si le démarrage échoue et qu'on le ferme. */
    void show(Consumer<String> open, Runnable onClose) {
        opener = open;
        this.onClose = onClose;
        showing = true;
        failed = false;
        ready = false;
        elapsed = 0;
        close.setVisible(false);
        close.setManaged(false);
        status("Démarrage du moteur Python…");
        root.setVisible(true);
    }

    /** L'étape en cours, telle que l'application la vit. */
    void status(String text) {
        if (!failed) step.setText(I18n.text(text));
    }

    /** La première frame du moteur : l'écran s'efface (après MINIMUM_S). */
    void onFrame(UniverseFrame frame) {
        if (showing) ready = true;
    }

    /** Le moteur ne démarrera pas : on le dit, en clair, et on laisse fermer. */
    void fail(String heading, String detail) {
        if (!showing) return;
        failed = true;
        step.setText(I18n.text(heading) + " — " + detail);
        step.setStyle("-fx-text-fill: #FCA5A5; -fx-font-size: 12.5px;");
        step.setWrapText(true);
        step.setMaxWidth(520);
        close.setVisible(true);
        close.setManaged(true);
    }

    void tick(double dt) {
        if (showing && ready && elapsed >= MINIMUM_S) showing = false;
        fade = Math.max(0, Math.min(1, fade + (showing ? dt / 0.2 : -dt / 0.55)));
        root.setOpacity(fade);
        if (fade <= 0 && !showing) {
            root.setVisible(false);
            return;
        }
        elapsed += dt;
        clock += dt;
        elapsedLabel.setText(failed ? "" : String.format(I18n.locale(), "%.1f s", elapsed)
                + (elapsed > 20 ? I18n.text(" · le premier lancement prépare les données, une minute au plus") : ""));
        drawSky();
        drawCloud();
        drawLine();
    }

    // ================================================================== le dessin
    private void drawSky() {
        double w = sky.getWidth(), h = sky.getHeight();
        if (w < 2 || h < 2) return;
        GraphicsContext g = sky.getGraphicsContext2D();
        g.setFill(new RadialGradient(0, 0, w * .5, h * .38, Math.max(w, h) * .72, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#13284A")), new Stop(.45, Color.web("#0A1529")), new Stop(1, Color.web("#03060C"))));
        g.fillRect(0, 0, w, h);
        for (int k = 0; k < STARS; k++) {
            double x = stars[4 * k] * w, y = stars[4 * k + 1] * h, size = stars[4 * k + 2];
            double twinkle = .35 + .3 * Math.sin(clock * (.6 + size) + stars[4 * k + 3]);
            g.setFill(Color.web("#BFD3FF", Math.max(0, Math.min(1, twinkle * (.4 + .5 * size)))));
            g.fillOval(x - size / 2, y - size / 2, size, size);
        }
    }

    /** L'orbitale du moment (et la précédente qui s'efface), tournant sur elle-même en perspective douce. */
    private void drawCloud() {
        double w = cloud.getWidth(), h = cloud.getHeight();
        GraphicsContext g = cloud.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        List<Cloud> list = clouds;
        if (list.isEmpty()) return;
        double cycle = HOLD_S + FADE_S;
        int index = (int) Math.floor(clock / cycle) % list.size();
        double into = clock % cycle;
        double radius = Math.min(w, h) * .46, cx = w / 2, cy = h / 2;
        double yaw = clock * .32, pitch = .38 + .1 * Math.sin(clock * .17);
        g.setGlobalBlendMode(BlendMode.ADD);
        // Le noyau : un point chaud, minuscule à l'échelle de l'orbitale (comme dans un vrai atome).
        g.setFill(new RadialGradient(0, 0, cx, cy, 10, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#FFE2B0", .9)), new Stop(1, Color.web("#FF9A4D", 0))));
        g.fillOval(cx - 10, cy - 10, 20, 20);
        double appear = Math.min(1, into / FADE_S);
        if (into < FADE_S && clock >= cycle) paint(g, list.get((index - 1 + list.size()) % list.size()), 1 - appear, cx, cy, radius, yaw, pitch);
        paint(g, list.get(index), appear, cx, cy, radius, yaw, pitch);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        Cloud shown = list.get(index);
        String text = I18n.text("hydrogène · orbitale") + " " + shown.name() + " · " + shown.quantum() + " — "
                + I18n.text("|ψ|² calculé,") + " " + shown.weight().length + " " + I18n.text("tirages");
        if (!text.equals(caption.getText())) caption.setText(text);
    }

    private static void paint(GraphicsContext g, Cloud c, double alpha, double cx, double cy, double radius, double yaw, double pitch) {
        if (alpha <= 0.01) return;
        double cosY = Math.cos(yaw), sinY = Math.sin(yaw), cosP = Math.cos(pitch), sinP = Math.sin(pitch);
        alpha *= Math.min(1, radius / 150);           // dessin plus petit, points plus serrés : moins d'éclat chacun
        float[] p = c.xyz(), wgt = c.weight();
        for (int k = 0; k < wgt.length; k++) {
            double x = p[3 * k], y = p[3 * k + 1], z = p[3 * k + 2];
            double x1 = x * cosY + y * sinY, y1 = -x * sinY + y * cosY;              // autour de l'axe vertical de l'orbitale
            double depth = y1 * cosP - z * sinP, up = y1 * sinP + z * cosP;
            double scale = 1 / (1 + depth * .22);
            double sx = cx + x1 * radius * scale, sy = cy - up * radius * scale;
            g.setFill(ramp(wgt[k], alpha * (.42 + .25 * scale)));
            double size = 1.35 + 1.1 * wgt[k];
            g.fillRect(sx - size / 2, sy - size / 2, size, size);
        }
    }

    /** La densité relative en couleur : bleu profond, bleu, blanc, or, orange (la figure de référence). */
    private static Color ramp(double w, double alpha) {
        double[][] stops = {{0, .11, .25, .62}, {.3, .31, .55, 1}, {.58, .82, .9, 1}, {.8, 1, .84, .48}, {1, 1, .55, .24}};
        double t = Math.sqrt(Math.max(0, Math.min(1, w)));
        for (int k = 1; k < stops.length; k++) {
            if (t <= stops[k][0]) {
                double f = (t - stops[k - 1][0]) / (stops[k][0] - stops[k - 1][0]);
                return Color.color(mix(stops[k - 1][1], stops[k][1], f), mix(stops[k - 1][2], stops[k][2], f),
                        mix(stops[k - 1][3], stops[k][3], f), Math.max(0, Math.min(1, alpha)));
            }
        }
        return Color.color(1, .55, .24, Math.max(0, Math.min(1, alpha)));
    }

    private static double mix(double a, double b, double f) {
        return a + (b - a) * f;
    }

    /** L'attente : un trait qui court sur une ligne fine (rouge et immobile si le démarrage a échoué). */
    private void drawLine() {
        GraphicsContext g = line.getGraphicsContext2D();
        double w = line.getWidth(), h = line.getHeight();
        g.clearRect(0, 0, w, h);
        g.setFill(Color.web("#1C2A44"));
        g.fillRoundRect(0, 0, w, h, h, h);
        if (failed) {
            g.setFill(Color.web("#F87171"));
            g.fillRoundRect(0, 0, w, h, h, h);
            return;
        }
        double head = ((clock * .55) % 1.3) * w * 1.3 - w * .3;
        g.setFill(new LinearGradient(head - w * .3, 0, head, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#7DD3FC", 0)), new Stop(1, Color.web("#7DD3FC", .95))));
        g.fillRoundRect(Math.max(0, head - w * .3), 0, Math.min(w, head) - Math.max(0, head - w * .3), h, h, h);
    }

    // ================================================================== les orbitales
    /** Une orbitale réelle de l'hydrogène (unités atomiques) : ψ(x, y, z), au facteur de normalisation près. */
    private interface Wave {
        double at(double x, double y, double z, double r);
    }

    /** Les cinq orbitales, tirées une fois (hors du fil de l'interface : 0,1 s environ). */
    static List<Cloud> sample() {
        Object[][] table = {
                {"2p", "n = 2, l = 1", 2, (Wave) (x, y, z, r) -> z * Math.exp(-r / 2)},
                {"3d xz", "n = 3, l = 2", 3, (Wave) (x, y, z, r) -> x * z * Math.exp(-r / 3)},
                {"4f z³", "n = 4, l = 3", 4, (Wave) (x, y, z, r) -> z * (5 * z * z - 3 * r * r) * Math.exp(-r / 4)},
                {"3d z²", "n = 3, l = 2", 3, (Wave) (x, y, z, r) -> (3 * z * z - r * r) * Math.exp(-r / 3)},
                {"4f xyz", "n = 4, l = 3", 4, (Wave) (x, y, z, r) -> x * y * z * Math.exp(-r / 4)},
        };
        List<Cloud> out = new ArrayList<>();
        Random random = new Random(42);
        for (Object[] row : table) out.add(draw((String) row[0], (String) row[1], (int) row[2], (Wave) row[3], random));
        return List.copyOf(out);
    }

    /** Rejet sur |ψ|² dans un cube assez grand ; puis l'échelle : 97 % des points dans la boule de rayon 1. */
    static Cloud draw(String name, String quantum, int n, Wave wave, Random random) {
        double half = 2.6 * n * n + 4, peak = 0;
        for (int k = 0; k < 80_000; k++) {
            double x = (2 * random.nextDouble() - 1) * half, y = (2 * random.nextDouble() - 1) * half, z = (2 * random.nextDouble() - 1) * half;
            double v = wave.at(x, y, z, Math.sqrt(x * x + y * y + z * z));
            peak = Math.max(peak, v * v);
        }
        peak *= 1.15;
        float[] xyz = new float[3 * POINTS], weight = new float[POINTS];
        double[] radii = new double[POINTS];
        int count = 0;
        for (int tries = 0; tries < 6_000_000 && count < POINTS; tries++) {
            double x = (2 * random.nextDouble() - 1) * half, y = (2 * random.nextDouble() - 1) * half, z = (2 * random.nextDouble() - 1) * half;
            double r = Math.sqrt(x * x + y * y + z * z), v = wave.at(x, y, z, r), d = v * v / peak;
            if (random.nextDouble() >= d) continue;
            xyz[3 * count] = (float) x;
            xyz[3 * count + 1] = (float) y;
            xyz[3 * count + 2] = (float) z;
            weight[count] = (float) Math.min(1, d * 1.15);
            radii[count] = r;
            count++;
        }
        double[] sorted = java.util.Arrays.copyOf(radii, count);
        java.util.Arrays.sort(sorted);
        double scale = count > 0 ? 1 / sorted[(int) (count * .97) - 1] : 1;
        for (int k = 0; k < 3 * count; k++) xyz[k] *= (float) scale;
        return new Cloud(name, quantum, java.util.Arrays.copyOf(xyz, 3 * count), java.util.Arrays.copyOf(weight, count));
    }
}
