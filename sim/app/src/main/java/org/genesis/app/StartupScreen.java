package org.genesis.app;

import org.genesis.common.Brand;
import org.genesis.common.Icons;
import org.genesis.common.Plan;

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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

/**
 * L'écran de démarrage, le temps que le moteur Python s'ouvre (au moins cinq secondes ; une minute au premier lancement).
 *
 * <p>Le logotype GENESIS_ (assets/brand) s'écrit comme dans un terminal : d'abord le curseur seul, qui clignote ; le G
 * arrive dans un glitch (des tranches qui glissent, ses franges cyan et violette écartées, qui sautent pas à pas puis se
 * calment) : le G_ de la marque ; puis E, N, E, S, I, S se tapent, le curseur avance d'une case à chaque frappe, chaque
 * lettre arrive avec un petit glitch. Le curseur se remet à clignoter au bout du nom, et le nom glitche par bouffées
 * (Brand.glitchAt), comme les traits du G_. Puis la phrase de Genesis, l'étape en cours ; en bas, discrets, les liens.
 * Les tracés sont ceux des SVG de la marque.
 *
 * <p>Il couvre l'écran dès la première image (on ne voit jamais l'accueil avant lui). Il reste au moins MINIMUM_S, puis
 * s'efface en une seconde (le logotype grandit à peine en disparaissant) quand le moteur a envoyé sa première frame. Si
 * le moteur ne démarre pas, il le dit, le curseur s'arrête en rouge, et laisse fermer.
 */
final class StartupScreen {
    /** Liens de Genesis : texte, icône, adresse. */
    static final String[][] SUPPORT_LINKS = {
            {"Une étoile sur GitHub", "star", "https://github.com/Lightminedust/genesis"},
            {"Site de Genesis", "globe", "https://genesis.gemmi-e.com"},
            {"r/gemmie", "chat", "https://www.reddit.com/r/gemmie/"},
            {"Contact", "mail", "mailto:nellawassi@gmail.com"},
    };
    /** Le plus court affichage (s) : l'écran a le temps de se lire, même quand le moteur est déjà prêt. */
    static final double MINIMUM_S = 5.0;
    /** Le fondu de sortie (s). */
    private static final double EXIT_S = 1.0;
    /** Le curseur paraît, le G arrive dans son glitch (début, durée), les frappes de E N E S I S (s depuis l'ouverture). */
    static final double CURSOR_AT = 0.2, BOOT_AT = 0.55, BOOT_S = 0.9;
    static final double[] KEYS = {1.75, 1.86, 1.95, 2.07, 2.15, 2.26};
    /** Le glitch de l'arrivée du G change à chaque pas (s) ; les bouffées reprennent IDLE_GLITCH s après la dernière frappe. */
    private static final double BOOT_STEP = 0.05, IDLE_GLITCH = 1.0;
    /** Le logotype : sa hauteur à l'écran (px). */
    private static final double HEIGHT_PX = 60;

    private final StackPane root = new StackPane();
    private final VBox content;
    private final Canvas mark = new Canvas(540, 140);
    private final Label tagline = new Label("La matière, calculée sous vos yeux."), step = new Label(),
            elapsedLabel = new Label();
    private final Button close = Plan.button("Fermer");
    private final Canvas line = new Canvas(160, 2);
    private final Region links;
    private Consumer<String> opener;
    private Runnable onClose;
    private double fade, elapsed, clock;
    private boolean showing, failed, ready;

    StartupScreen() {
        tagline.getStyleClass().add("startup-tagline");
        step.getStyleClass().add("startup-step");
        step.setWrapText(true);
        step.setMaxWidth(520);
        step.setAlignment(Pos.CENTER);
        elapsedLabel.getStyleClass().add("startup-caption");
        close.setVisible(false);
        close.setManaged(false);
        close.setOnAction(e -> {
            showing = false;
            if (onClose != null) onClose.run();
        });
        content = new VBox(0, mark, gap(6), tagline, gap(40), line, gap(14), step, gap(4), elapsedLabel, gap(10), close);
        content.setAlignment(Pos.CENTER);
        content.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        links = links();
        BorderPane layout = new BorderPane(content);
        layout.setBottom(links);
        BorderPane.setMargin(links, new Insets(0, 24, 22, 24));
        root.getChildren().add(layout);
        root.getStyleClass().add("startup");
        root.setVisible(false);
        root.setOpacity(0);
    }

    private static Region gap(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        r.setPrefHeight(h);
        return r;
    }

    /** En bas, discrets : les liens de Genesis, et une phrase sur ce qui l'aide. */
    private Region links() {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER);
        for (int k = 0; k < SUPPORT_LINKS.length; k++) {
            String[] link = SUPPORT_LINKS[k];
            if (k > 0) {
                Label dot = new Label("·");
                dot.getStyleClass().add("startup-caption");
                row.getChildren().add(dot);
            }
            Button quiet = new Button(link[0], Icons.path(link[1], 13));
            quiet.getStyleClass().setAll("button", "startup-link");
            quiet.setFocusTraversable(false);
            quiet.setTooltip(Plan.tooltip(link[2].replaceFirst("^mailto:", "")));
            quiet.setOnAction(e -> open(link[2]));
            row.getChildren().add(quiet);
        }
        Label note = new Label("Genesis est libre et gratuit. Une étoile sur GitHub l'aide à être trouvé par d'autres curieux.");
        note.getStyleClass().add("startup-caption");
        VBox box = new VBox(6, row, note);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void open(String url) {
        if (opener != null) opener.accept(url);
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
        clock = 0;
        close.setVisible(false);
        close.setManaged(false);
        step.getStyleClass().remove("plan-bad");
        status("Démarrage du moteur Python…");
        // Opaque tout de suite : l'accueil, déjà construit derrière, ne doit jamais se voir avant lui ; et sa première image
        // est celle de l'instant 0 (le texte pas encore arrivé, le nom pas encore écrit).
        fade = 1;
        root.setOpacity(1);
        content.setScaleX(1);
        content.setScaleY(1);
        draw();
        root.setVisible(true);
    }

    /** La phrase de Genesis : pour les tests. */
    Label taglineForTest() {
        return tagline;
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
        if (!step.getStyleClass().contains("plan-bad")) step.getStyleClass().add("plan-bad");
        close.setVisible(true);
        close.setManaged(true);
    }

    void tick(double dt) {
        if (showing && ready && elapsed >= MINIMUM_S) showing = false;
        fade = Math.max(0, Math.min(1, fade + (showing ? dt / 0.35 : -dt / EXIT_S)));
        double out = ease(fade);
        root.setOpacity(out);
        // En partant, le logotype grandit à peine : l'écran s'ouvre sur l'accueil plutôt qu'il ne s'éteint.
        content.setScaleX(1 + .05 * (1 - out));
        content.setScaleY(1 + .05 * (1 - out));
        if (fade <= 0 && !showing) {
            root.setVisible(false);
            return;
        }
        elapsed += dt;
        clock += dt;
        draw();
    }

    /** L'écran à l'instant `clock` : le texte qui arrive, le logotype, la ligne d'attente. */
    private void draw() {
        // Le texte arrive quand le nom est écrit, chaque ligne un peu plus tard.
        reveal(tagline, 2.5, .6, 6);
        reveal(line, 2.85, .5, 0);
        reveal(step, 2.85, .5, 0);
        reveal(elapsedLabel, 2.95, .5, 0);
        reveal(links, 3.15, .8, 0);
        elapsedLabel.setText(failed ? "" : String.format(I18n.locale(), "%.1f s", elapsed)
                + (elapsed > 20 ? I18n.text(" · le premier lancement prépare les données, une minute au plus") : ""));
        drawMark();
        drawLine();
    }

    /** Un nœud qui apparaît à `start` (s) en `span` s, en montant de `rise` px. */
    private void reveal(javafx.scene.Node node, double start, double span, double rise) {
        double f = ease(Math.max(0, Math.min(1, (clock - start) / span)));
        node.setOpacity(f);
        node.setTranslateY(rise * (1 - f));
    }

    /** Une courbe adoucie aux deux bouts (cubique), de 0 à 1. */
    private static double ease(double t) {
        t = Math.max(0, Math.min(1, t));
        return t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    /** Une sortie amortie (rapide puis douce), de 0 à 1. */
    private static double settle(double t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - t, 3);
    }

    // ================================================================== le logotype qui s'écrit
    /** Combien de lettres sont écrites à l'instant `t` (1 = le G seul, 7 = tout le nom). */
    static int typed(double t) {
        int n = t >= BOOT_AT ? 1 : 0;
        for (double key : KEYS) if (t >= key) n++;
        return n;
    }

    private void drawMark() {
        double w = mark.getWidth(), h = mark.getHeight();
        GraphicsContext g = mark.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        Brand.Art art = Brand.LOGOTYPE;
        double s = HEIGHT_PX / art.height();
        double ox = (w - art.width() * s) / 2 - art.minX() * s, oy = (h - art.height() * s) / 2 - art.minY() * s;

        // La lueur violette de l'icône, derrière le nom, qui respire à peine.
        double boot = Math.max(0, Math.min(1, (clock - BOOT_AT) / BOOT_S));
        double glow = ease(boot) * (.85 + .15 * Math.sin(clock * 1.2));
        // Une ellipse à la mesure du canevas, éteinte avant ses bords (sinon son rectangle se verrait).
        g.setFill(new RadialGradient(0, 0, .5, .5, .5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Brand.VIOLET.deriveColor(0, 1, 1, .15 * glow)),
                new Stop(.55, Brand.VIOLET.deriveColor(0, 1, 1, .06 * glow)), new Stop(1, Brand.VIOLET.deriveColor(0, 1, 1, 0))));
        g.fillRect(0, 0, w, h);

        Brand.Part first = art.layer(Brand.Role.INK).parts().get(0);
        Brand.Part cursorPart = art.layer(Brand.Role.CURSOR).parts().get(0);
        int cursorSlot = art.slots() - 1;
        double advance = (cursorPart.minX() - first.minX()) / cursorSlot;
        int n = typed(clock);
        double[] alpha = new double[art.slots()], spread = new double[art.slots()];
        List<Brand.Slice> glitch = null;

        // Le G arrive dans un glitch : à chaque pas, d'autres tranches glissent, de moins en moins loin ; il clignote un
        // peu, ses franges écartées se referment.
        if (clock >= BOOT_AT) {
            alpha[0] = 1;
            if (boot < 1) {
                double k = 1 - settle(boot);
                long step = (long) Math.floor((clock - BOOT_AT) / BOOT_STEP);
                alpha[0] = new Random(step * 31 + 7).nextDouble() < .45 * k ? .3 : 1;
                spread[0] = 90 * k;
                glitch = new ArrayList<>(Brand.slices(art, step, 2 + (int) Math.round(4 * k), .6 + 2.4 * k, -1));
            }
        }
        // Une lettre tapée paraît d'un coup, dans un petit glitch de trois pas, ses franges écartées se refermant.
        for (int slot = 1; slot < n; slot++) {
            double p = (clock - KEYS[slot - 1]) / .16;
            alpha[slot] = Math.min(1, .35 + p * 4);
            spread[slot] = 46 * (1 - settle(p));
            if (p < 1) {
                if (glitch == null) glitch = new ArrayList<>();
                glitch.addAll(Brand.slices(art, 1000 + slot * 7L + (long) (p * 3), 2, 1 - .5 * p, slot));
            }
        }
        // Le nom écrit, il glitche par bouffées, comme les traits du G_.
        if (glitch == null && !failed && n == cursorSlot) {
            glitch = Brand.glitchAt(art, clock - KEYS[KEYS.length - 1] - IDLE_GLITCH);
        }
        alpha[cursorSlot] = cursorShown() ? 1 : 0;
        double cursorDx = (n - cursorSlot) * advance;

        g.save();
        g.translate(ox, oy);
        g.scale(s, s);
        Brand.draw(g, art, new Brand.Look() {
            @Override public double alpha(int slot) { return alpha[slot]; }
            @Override public double dx(int slot) { return slot == cursorSlot ? cursorDx : 0; }
            @Override public double spread(int slot) { return spread[slot]; }
        }, glitch, failed ? Plan.BAD : null);
        g.restore();
    }

    /**
     * Le curseur : fixe pendant la frappe, il clignote avant (le terminal attend) et après (le nom est écrit) ; fixe, en
     * rouge, si le démarrage a échoué.
     */
    private boolean cursorShown() {
        if (clock < CURSOR_AT) return false;
        double lastKey = KEYS[KEYS.length - 1];
        boolean typing = clock >= KEYS[0] - .2 && clock < lastKey + .45;
        return failed || typing || Brand.cursorOn(clock < KEYS[0] ? clock - CURSOR_AT : clock - lastKey - .45);
    }

    /** L'attente : un trait qui court sur une ligne fine, du cyan au violet (rouge et immobile si le démarrage a échoué). */
    private void drawLine() {
        GraphicsContext g = line.getGraphicsContext2D();
        double w = line.getWidth(), h = line.getHeight();
        g.clearRect(0, 0, w, h);
        g.setFill(Color.web("#FFFFFF", .08));
        g.fillRect(0, 0, w, h);
        if (failed) {
            g.setFill(Plan.BAD);
            g.fillRect(0, 0, w, h);
            return;
        }
        double head = ((clock * .45) % 1.4) * w * 1.4 - w * .4, tail = head - w * .35;
        double x0 = Math.max(0, tail), x1 = Math.min(w, head);
        if (x1 <= x0) return;
        g.setFill(new LinearGradient(tail, 0, head, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, Brand.CYAN.deriveColor(0, 1, 1, 0)), new Stop(.55, Brand.CYAN.deriveColor(0, 1, 1, .85)),
                new Stop(1, Brand.CURSOR)));
        g.fillRect(x0, 0, x1 - x0, h);
    }
}
