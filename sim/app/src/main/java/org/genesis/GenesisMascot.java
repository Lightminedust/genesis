package org.genesis;

import com.studio.genesis.grpc.SimEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Genesis, la petite guide : une flaque de matière noire, prisonnière d'une boule de verre posée sur un socle.
 *
 * - Sa matière est un ensemble de « metaballs » : un champ calculé (la somme des noyaux de Wyvill de ses lobes) dont la paroi est
 *   extraite en contours. Sa silhouette rappelle un petit nuage cosmique : un corps rond et festonné, deux grandes oreilles-nuages
 *   reliées par un col, et quelques bouffées qui s'en détachent, s'éloignent et se recollent (le corps est stable, pas figé).
 * - Un dégradé sur tout le corps : violet en bas, la nuit au centre derrière le visage, et les oreilles toutes bleues ; dans
 *   chaque oreille, une galaxie spirale qui tourne (en sens opposés). Une nébuleuse dérive en fond, des étoiles blanches
 *   scintillent ; un fin contour sombre, un liseré de lumière et un reflet. Que de la matière : rien d'or sur elle.
 * - Ses yeux, ovales et ambrés, brillent ; elle cligne, plisse les yeux quand elle sourit, regarde la souris ; une bouche apparaît
 *   par moments (quand elle parle, qu'elle rit, qu'elle s'étonne). C'est une entité féminine : des cils fins, une douceur.
 * - La boule (verre, reflets, ornement doré à l'étoile et au petit cristal) et le socle (noir, filets dorés, anses, emblème « G_ »)
 *   ne bougent pas ; seuls changent la lueur du fond de la boule, la teinte du socle, le petit cristal et les voyants, selon ce
 *   qu'elle fait : au repos, en train d'expliquer, une idée, excitée, curieuse, endormie — et la couleur d'un photon.
 *
 * Elle lit le journal : chaque événement du moteur devient une phrase dans sa bulle (avec le calcul quand il y en a un).
 */
final class GenesisMascot {

    enum Mood { IDLE, HAPPY, CURIOUS, EXCITED, SLEEPY, IDEA }

    /** Ce qu'elle fait, pour les voyants et la lueur : au repos, elle parle, une idée, excitée, curieuse, elle dort. */
    enum Activity { IDLE, TALKING, IDEA, EXCITED, CURIOUS, SLEEPING }

    /** Un lobe de matière (unités : rayon de la boule = 1, y vers le bas, origine au centre de la boule). */
    record Lobe(double x, double y, double r) {}

    private static final double WYVILL = Math.sqrt(1 - Math.cbrt(.5));
    private static final Color INK = Color.web("#0A1040"), GOLD = Color.web("#E9B95A"), GOLD_LIGHT = Color.web("#FFE7A6"),
            GOLD_DEEP = Color.web("#9A6A22");
    private static final Color STAR_GOLD = Color.web("#FFD98A");

    private final double width, height;
    private final StackPane node = new StackPane();
    private final Canvas canvas;
    private final Image nebula;
    private final Random rng = new Random(7);
    /** Les morceaux qui se détachent : angle de départ, vitesse angulaire, distance moyenne, amplitude, cadence, phase, rayon. */
    private final double[][] satellites = {
            {-.75, .06, .6, .17, .31, .0, .16, 1.5}, {2.45, -.07, .6, .15, .23, 1.7, .12, 1.1}, {3.75, .05, .58, .16, .37, 3.1, .13, 1.2},
            {.55, -.05, .62, .14, .27, 4.4, .11, 1.3}};
    /** Les perles qui flottent : angle, distance, vitesse, rayon. */
    private final double[][] beads = {{.9, .7, -.06, .022}, {3.0, .64, -.07, .02}, {5.3, .68, -.05, .024}};
    private final double[][] stars = new double[22][];

    // l'état
    private double t, moodTime, idleTime, blinkIn = 2.4, blinkT = -1, lid = .05, squint, mouthShow, mouthOpen, eyeSize = 1;
    private double lookX = Double.NaN, lookY = Double.NaN, gazeX, gazeY, yawNow, scatter, scatterV, flash, ideaGlow, tilt;
    private double pace = .65, wobble = 1, gather = 1, sleepSink, glow = .5;
    private Color light = Color.web("#4FA8FF");
    private Mood mood = Mood.IDLE;
    private boolean manual;
    private Color flashColour = Color.WHITE;
    private int pieces = 1;

    // la parole
    private final VBox speech = new VBox(3);
    private final Label name = new Label("GENESIS"), text = new Label(), formula = new Label();
    private final ArrayDeque<String[]> queue = new ArrayDeque<>();
    private String full = "", fullFormula = "";
    private double typed, holdUntil;

    GenesisMascot(double width, double height) {
        this.width = width;
        this.height = height;
        canvas = new Canvas(width, height);
        node.getChildren().add(canvas);
        node.setMinSize(width, height);
        node.setPrefSize(width, height);
        node.setMaxSize(width, height);
        node.setOnMouseMoved(e -> { lookX = e.getX() / width - .5; lookY = e.getY() / height - .45; wake(); });
        node.setOnMouseExited(e -> lookX = lookY = Double.NaN);
        node.setOnMouseClicked(e -> poke());
        nebula = nebulaImage();
        Random r = new Random(29);
        for (int k = 0; k < stars.length; k++)
            stars[k] = new double[]{r.nextDouble() * 2 * Math.PI, .05 + .55 * Math.sqrt(r.nextDouble()), .012 + .02 * Math.pow(r.nextDouble(), 2),
                    r.nextDouble() * 6.28, .8 + 1.6 * r.nextDouble(), r.nextDouble() < .7 ? 1 : 0};
        buildSpeech();
        say("Coucou, je suis Genesis ! Je lis les calculs du moteur et je t'explique, ici, tout ce qui arrive à ton atome.", "",
                Mood.HAPPY);
        new javafx.animation.AnimationTimer() {
            private long last = -1;

            @Override
            public void handle(long now) {
                double dt = last < 0 ? 1 / 60.0 : Math.min(.05, (now - last) / 1e9);
                last = now;
                if (!manual && shown()) tick(dt);
            }
        }.start();
    }

    Region getNode() { return node; }

    Region speech() { return speech; }

    /** Pour les aperçus : elle n'avance plus seule (on appelle `tick`). */
    void setManual(boolean on) { manual = on; }

    /** Combien de morceaux son corps fait à cet instant (1 : tout est recollé). */
    int pieces() { return pieces; }

    private boolean shown() {
        if (node.getScene() == null) return false;
        for (Node n = node; n != null; n = n.getParent()) if (!n.isVisible()) return false;
        return true;
    }

    /** Ce qu'elle fait en ce moment (pour les voyants et la lueur). */
    Activity activity() {
        if (mood == Mood.SLEEPY) return Activity.SLEEPING;
        if (mood == Mood.IDEA && moodTime < 4) return Activity.IDEA;
        if (typed < full.length()) return Activity.TALKING;
        if (mood == Mood.EXCITED) return Activity.EXCITED;
        if (mood == Mood.CURIOUS) return Activity.CURIOUS;
        return Activity.IDLE;
    }

    /** La couleur d'un état : bleu au repos, vert-cyan quand elle explique, or pour une idée, rose excitée, violet curieuse. */
    static Color stateColour(Activity a) {
        return switch (a) {
            case TALKING -> Color.web("#3DF2C2");
            case IDEA -> Color.web("#FFD34A");
            case EXCITED -> Color.web("#FF6FD8");
            case CURIOUS -> Color.web("#B48CFF");
            case SLEEPING -> Color.web("#5D52B0");
            default -> Color.web("#4FA8FF");
        };
    }

    // ================================================================== la matière
    /** Les bouffées qui se détachent : d'où (0 oreille gauche, 1 oreille droite, 2 bas du corps), direction (rad), cadence, phase, rayon. */
    private final double[][] puffs = {{0, -2.6, .23, .0, .05}, {1, -.55, .19, 2.1, .048}, {2, 2.3, .27, 4.0, .05},
            {2, .7, .17, 5.2, .042}};
    /** Pour le dessin : le centre de chaque oreille et du corps à l'instant t (unités de la boule). */
    private double bodyX, bodyY, earLX, earLY, earRX, earRY;

    /**
     * Ses lobes à l'instant t : le corps (un rond festonné), deux cols et deux oreilles-nuages, et les bouffées qui s'en vont puis
     * reviennent. Le corps bouge peu (stable, mais vivant) ; les bouffées vont et viennent franchement.
     */
    List<Lobe> lobes() {
        List<Lobe> out = new ArrayList<>();
        double bob = .025 * Math.sin(t * .5 * pace), sink = .1 * sleepSink;
        double mx = .015 * Math.sin(t * .2), my = .14 + bob + sink;
        double w = .012 * wobble;
        bodyX = mx;
        bodyY = my;
        // le corps : un grand lobe et sept autour, en festons
        out.add(new Lobe(mx, my, .25 * (1 + .025 * Math.sin(t * .6 * pace))));
        for (int i = 0; i < 7; i++) {
            double a = i * 2 * Math.PI / 7 + .2, ph = i * 1.7;
            double d = .17 + w * Math.sin(t * (.45 + .05 * i) * pace + ph);
            out.add(new Lobe(mx + d * Math.cos(a), my + d * .92 * Math.sin(a), .12 * (1 + .05 * Math.sin(t * (.5 + .06 * i) * pace + ph))));
        }
        // les oreilles : un col, puis un nuage de cinq lobes ; elles se balancent doucement, chacune à son rythme
        for (int side = -1; side <= 1; side += 2) {
            double sway = .05 * Math.sin(t * .35 * pace + (side > 0 ? 1.3 : 0)) * wobble;
            double ex = mx + side * (.40 + .02 * Math.sin(t * .3 + side)), ey = my - .43 + sway * .5 - .04 * gather + .04;
            ex += side * sway * .4;
            if (side < 0) { earLX = ex; earLY = ey; } else { earRX = ex; earRY = ey; }
            for (double f : new double[]{.42, .66}) {
                double nx = mx + (ex - mx) * f, ny = my + (ey - my) * f;
                out.add(new Lobe(nx, ny, f < .5 ? .075 : .085));
            }
            out.add(new Lobe(ex, ey, .155 * (1 + .04 * Math.sin(t * .7 * pace + side))));
            for (int i = 0; i < 6; i++) {
                double a = i * Math.PI / 3 + .3 + side * .2, ph = i * 2.1 + side;
                double d = .1 + .008 * Math.sin(t * .6 * pace + ph);
                out.add(new Lobe(ex + d * Math.cos(a), ey + d * .85 * Math.sin(a), .085));
            }
        }
        // les bouffées : elles s'éloignent, restent un peu, reviennent se recoller (tanh), sans quitter la boule
        for (double[] puff : puffs) {
            double ax = puff[0] == 0 ? earLX : puff[0] == 1 ? earRX : mx;
            double ay = puff[0] == 0 ? earLY : puff[0] == 1 ? earRY : my;
            double base = puff[0] == 2 ? .32 : .19;
            double cycle = .5 + .5 * Math.tanh(2.2 * Math.sin(puff[2] * t * pace + puff[3]));
            double d = base + (.03 + .1 * cycle) * gather + .18 * scatter;
            double ang = puff[1] + .15 * Math.sin(t * .3 + puff[3]);
            double x = ax + d * Math.cos(ang), y = ay + d * Math.sin(ang);
            double reach = Math.hypot(x, y);
            if (reach > .82 - puff[4]) {
                double k = (.82 - puff[4]) / reach;
                x *= k;
                y *= k;
            }
            out.add(new Lobe(x, y, puff[4]));
            out.add(new Lobe(ax + (x - ax) * .55, ay + (y - ay) * .55, puff[4] * .55 * (1 - cycle) + .01));
        }
        return out;
    }

    /** Le champ de la matière (normalisé : la paroi vaut 1) sur une grille, et ses contours. */
    static List<double[][]> outline(List<Lobe> lobes, double x0, double y0, int gw, int gh, double cell) {
        double[] f = new double[gw * gh];
        for (int j = 0; j < gh; j++) {
            double y = y0 + j * cell;
            for (int i = 0; i < gw; i++) {
                double x = x0 + i * cell, sum = 0;
                for (Lobe l : lobes) {
                    double s = l.r() / WYVILL, dx = x - l.x(), dy = y - l.y(), u2 = (dx * dx + dy * dy) / (s * s);
                    if (u2 < 1) { double q = 1 - u2; sum += q * q * q; }
                }
                f[j * gw + i] = sum / .5;
            }
        }
        return SubRender.contours(f, gw, gh, x0, y0, cell);
    }

    /** La nébuleuse de son corps : un fond d'encre, des nuages bleus, cyan, violets et magenta, une poussière d'étoiles. */
    private static Image nebulaImage() {
        int s = 256;
        Canvas c = new Canvas(s, s);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#0B0F38"));
        g.fillRect(0, 0, s, s);
        Random r = new Random(41);
        Color[] clouds = {Color.web("#2D5BFF"), Color.web("#22B8FF"), Color.web("#7B3FE0"), Color.web("#C23FD8"), Color.web("#1B2C9A"),
                Color.web("#3FD8FF")};
        for (int k = 0; k < 70; k++) {
            double x = r.nextDouble() * s, y = r.nextDouble() * s, rad = 12 + 46 * r.nextDouble() * r.nextDouble();
            Color col = clouds[r.nextInt(clouds.length)];
            double a = .18 + .3 * r.nextDouble();
            for (double dx : new double[]{-s, 0, s})
                for (double dy : new double[]{-s, 0, s}) {
                    g.setFill(new RadialGradient(0, 0, x + dx, y + dy, rad, false, CycleMethod.NO_CYCLE, new Stop(0, col.deriveColor(0, 1, 1, a)),
                            new Stop(1, col.deriveColor(0, 1, 1, 0))));
                    g.fillOval(x + dx - rad, y + dy - rad, 2 * rad, 2 * rad);
                }
        }
        for (int k = 0; k < 160; k++) {
            double x = r.nextDouble() * s, y = r.nextDouble() * s, size = .5 + 1.1 * Math.pow(r.nextDouble(), 3);
            g.setFill(Color.color(.85 + .15 * r.nextDouble(), .9, 1, .35 + .5 * r.nextDouble()));
            g.fillOval(x - size, y - size, 2 * size, 2 * size);
        }
        SnapshotParameters p = new SnapshotParameters();
        p.setFill(Color.TRANSPARENT);
        return c.snapshot(p, null);
    }

    // ================================================================== la vie
    void setMood(Mood m) {
        mood = m;
        moodTime = 0;
    }

    private void wake() {
        idleTime = 0;
        if (mood == Mood.SLEEPY) setMood(Mood.IDLE);
    }

    /** Un clic : sa matière s'éparpille un instant, puis se rassemble ; elle rit, et redit ce qu'elle expliquait. */
    private void poke() {
        wake();
        scatterV = 1.3;
        setMood(Mood.HAPPY);
        if (full.isEmpty()) say("C'est moi, Genesis ! Ajoute un proton, tire un photon : je te dirai ce que le moteur a calculé.", "", Mood.HAPPY);
        else typed = 0;
    }

    void tick(double dt) {
        t += dt;
        moodTime += dt;
        idleTime += dt;
        if (mood != Mood.SLEEPY && idleTime > 80 && queue.isEmpty() && typed >= full.length()) setMood(Mood.SLEEPY);
        if (mood != Mood.IDLE && mood != Mood.SLEEPY && moodTime > 9) setMood(Mood.IDLE);
        boolean talking = typed < full.length();
        if (talking) typed = Math.min(full.length(), typed + dt * 46);
        else if (!queue.isEmpty() && t > holdUntil) {
            String[] next = queue.poll();
            begin(next[0], next[1], Mood.valueOf(next[2]));
        }
        text.setText(full.substring(0, (int) typed));
        formula.setText(typed >= full.length() ? fullFormula : "");
        formula.setVisible(!formula.getText().isEmpty());
        formula.setManaged(formula.isVisible());
        // l'humeur : la matière plus ou moins vive, les yeux, la bouche
        double wantPace, wantWobble, wantGather, wantLid, wantSquint, wantSize, wantMouth, wantOpen, wantTilt;
        switch (mood) {
            case HAPPY -> { wantPace = .85; wantWobble = 1.1; wantGather = 1; wantLid = .03; wantSquint = .5; wantSize = 1; wantMouth = 1; wantOpen = .45; wantTilt = 0; }
            case CURIOUS -> { wantPace = .6; wantWobble = 1; wantGather = 1; wantLid = 0; wantSquint = 0; wantSize = 1.08; wantMouth = moodTime < 2.5 ? 1 : 0;
                wantOpen = -1; wantTilt = 10; }
            case EXCITED -> { wantPace = 1.25; wantWobble = 1.35; wantGather = 1.12; wantLid = 0; wantSquint = .2; wantSize = 1.12; wantMouth = 1; wantOpen = .7; wantTilt = 0; }
            case SLEEPY -> { wantPace = .22; wantWobble = .5; wantGather = .78; wantLid = 1; wantSquint = 0; wantSize = .96; wantMouth = 0; wantOpen = 0; wantTilt = -6; }
            case IDEA -> { wantPace = .9; wantWobble = 1.1; wantGather = 1.05; wantLid = 0; wantSquint = 0; wantSize = 1.14; wantMouth = moodTime < 1.5 ? 1 : 0;
                wantOpen = -1; wantTilt = -4; }
            default -> { wantPace = .65; wantWobble = 1; wantGather = 1; wantLid = .05; wantSquint = .1; wantSize = 1; wantMouth = 0; wantOpen = 0; wantTilt = 0; }
        }
        if (talking) {
            wantMouth = 1;
            wantOpen = .1 + .38 * Math.abs(Math.sin(t * 5.2) * Math.sin(t * 2.3 + 1));
        }
        blinkIn -= dt;
        if (blinkIn < 0 && mood != Mood.SLEEPY) {
            blinkT = 0;
            blinkIn = 3.5 + 4.5 * rng.nextDouble();
        }
        if (blinkT >= 0) {
            blinkT += dt;
            double b = blinkT < .1 ? blinkT / .1 : Math.max(0, 1 - (blinkT - .1) / .14);
            wantLid = Math.max(wantLid, b);
            if (blinkT > .24) blinkT = -1;
        }
        // tout glisse doucement vers sa cible : rien de brusque
        double k = Math.min(1, dt * 2.2);
        pace += (wantPace - pace) * Math.min(1, dt * .6);
        wobble += (wantWobble - wobble) * Math.min(1, dt * .6);
        gather += (wantGather - gather) * Math.min(1, dt * .5);
        sleepSink += ((mood == Mood.SLEEPY ? 1 : 0) - sleepSink) * Math.min(1, dt * .4);
        lid += (wantLid - lid) * Math.min(1, dt * (blinkT >= 0 ? 40 : mood == Mood.SLEEPY ? .8 : 3));
        squint += (wantSquint - squint) * k;
        eyeSize += (wantSize - eyeSize) * k;
        mouthShow += (wantMouth - mouthShow) * Math.min(1, dt * 2.5);
        mouthOpen = wantOpen < -.5 ? -1 : mouthOpen < -.5 ? 0 : mouthOpen + (wantOpen - mouthOpen) * Math.min(1, dt * 8);
        tilt += (wantTilt - tilt) * Math.min(1, dt * 1.1);
        double wantGazeX = !Double.isNaN(lookX) ? .9 * lookX : .22 * Math.sin(t * .25) + (Math.sin(t * .13) > .9 ? .3 : 0);
        double wantGazeY = !Double.isNaN(lookY) ? .7 * lookY : .1 * Math.sin(t * .18);
        gazeX += (wantGazeX - gazeX) * Math.min(1, dt * 1.6);
        gazeY += (wantGazeY - gazeY) * Math.min(1, dt * 1.6);
        // l'éparpillement (un clic) : un ressort doux
        scatterV += (-6 * scatter - 3.2 * scatterV) * dt;
        scatter = Math.max(0, scatter + scatterV * dt);
        ideaGlow = Math.max(mood == Mood.IDEA ? Math.max(0, 1 - moodTime / 5) : 0, ideaGlow - dt * .4);
        flash = Math.max(0, flash - dt * .45);
        // la lumière du fond : sa couleur passe en fondu, son intensité suit lentement ce qu'elle fait
        Activity act = activity();
        Color target = flash > .05 ? stateColour(act).interpolate(flashColour, Math.min(1, flash * 1.4)) : stateColour(act);
        light = light.interpolate(target, Math.min(1, dt * 1.2));
        double wantGlow = switch (act) {
            case SLEEPING -> .22 + .07 * Math.sin(t * .6);
            case TALKING -> .72 + .12 * Math.sin(t * 2.2);
            case IDEA -> .85 + .15 * Math.sin(t * 1.4);
            case EXCITED -> .8 + .15 * Math.sin(t * 2.8);
            case CURIOUS -> .58 + .12 * Math.sin(t * 1.3);
            default -> .5 + .14 * Math.sin(t * .8);
        };
        glow += (wantGlow - glow) * Math.min(1, dt * 1.5);
        draw();
    }

    // ================================================================== le dessin
    private double cx, cy, R;

    private double X(double u) { return cx + u * R; }

    private double Y(double v) { return cy + v * R; }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, width, height);
        R = Math.min(width / 2.14, height / 2.42);
        cx = width / 2;
        cy = R * 1.035 + 1;
        Color state = light;
        drawCables(g, false);                      // les deux câbles de derrière : vus à travers le verre
        drawGlassBack(g, state, glow);
        g.save();
        g.beginPath();
        g.arc(cx, cy, R * .965, R * .965, 0, 360);
        g.clip();
        drawMatter(g);
        drawBeads(g);
        // la lumière du fond éclaire doucement l'intérieur — et seulement l'intérieur — de la boule, la matière comprise
        g.setFill(new LinearGradient(0, Y(.9), 0, Y(-.25), false, CycleMethod.NO_CYCLE, new Stop(0, state.deriveColor(0, 1, 1, .26 * glow)),
                new Stop(.45, state.deriveColor(0, 1, 1, .08 * glow)), new Stop(1, state.deriveColor(0, 1, 1, 0))));
        g.fillRect(X(-1), Y(-1), 2 * R, 2 * R);
        g.restore();
        drawGlassFront(g);
        drawOrnament(g, state);
        drawBase(g, state);
        drawCables(g, true);                       // les deux de devant : par-dessus le socle et le verre
    }

    /**
     * Le verre, côté fond : une teinte bleutée qui s'éclaircit vers le bord ; et le fond de la boule, le vide qui est en bas : c'est
     * lui le voyant. Il émet la couleur de ce qu'elle fait, et sa lumière monte dans la boule (dans la boule seulement).
     */
    private void drawGlassBack(GraphicsContext g, Color state, double glow) {
        g.setFill(new RadialGradient(0, 0, X(-.15), Y(-.2), R * 1.15, false, CycleMethod.NO_CYCLE, new Stop(0, Color.color(.55, .68, .95, .07)),
                new Stop(.75, Color.color(.6, .74, 1, .14)), new Stop(1, Color.color(.75, .86, 1, .3))));
        g.fillOval(X(-1), Y(-1), 2 * R, 2 * R);
        g.save();
        g.beginPath();
        g.arc(cx, cy, R * .97, R * .97, 0, 360);
        g.clip();
        // la lumière qui monte du fond
        g.setFill(new RadialGradient(0, 0, X(0), Y(.88), R * .95, false, CycleMethod.NO_CYCLE, new Stop(0, state.deriveColor(0, 1, 1, .42 * glow)),
                new Stop(.5, state.deriveColor(0, 1, 1, .14 * glow)), new Stop(1, state.deriveColor(0, 1, 1, 0))));
        g.fillOval(X(-.95), Y(-.07), 1.9 * R, 1.9 * R);
        // le vide du fond : sombre au centre, il rayonne de son bord
        double fy = Y(.84), fw = .5 * R, fh = .075 * R;
        g.setFill(Color.web("#04050C"));
        g.fillOval(X(0) - fw, fy - fh, 2 * fw, 2 * fh);
        g.setFill(new RadialGradient(0, 0, X(0), fy, fw, false, CycleMethod.NO_CYCLE, new Stop(0, state.interpolate(Color.WHITE, .5).deriveColor(0, 1, 1, .85 * glow)),
                new Stop(.55, state.deriveColor(0, 1, 1, .55 * glow)), new Stop(1, state.deriveColor(0, 1, 1, .15 * glow))));
        g.save();
        g.translate(X(0), fy);
        g.scale(1, fh / fw);
        g.fillOval(-fw, -fw, 2 * fw, 2 * fw);
        g.restore();
        g.setStroke(state.interpolate(Color.WHITE, .35).deriveColor(0, 1, 1, .4 + .5 * glow));
        g.setLineWidth(Math.max(.8, R * .012));
        g.strokeOval(X(0) - fw, fy - fh, 2 * fw, 2 * fh);
        g.restore();
    }

    /**
     * Les quatre câbles qui tiennent la boule au socle, à 90° les uns des autres et tournés de 25° (la structure est vue de trois
     * quarts, un peu d'en haut : les quatre se voient) : deux devant, deux derrière (plus petits, plus sombres, vus à travers le verre). Chacun est un tube noir annelé, cerclé d'or à ses
     * deux bouts, qui part de la bande du socle et vient serrer le bas de la boule.
     */
    private void drawCables(GraphicsContext g, boolean front) {
        double[] angles = front ? new double[]{25, 115} : new double[]{205, 295};
        for (double deg : angles) {
            double a = Math.toRadians(deg), ca = Math.cos(a), sa = Math.sin(a), size = front ? 1 : .78;
            double bx = X(.52 * ca), by = Y(.97 + .055 * sa);                // sur la bande du socle
            double tx = X(.76 * ca), ty = Y(.58 + .1 * sa);                  // sur le bas de la boule
            double qx = X(1.0 * ca), qy = Y(.84 + .08 * sa);                 // le coude, vers l'extérieur
            double w = R * .075 * size;
            Color shade = front ? Color.web("#0C0D13") : Color.web("#0C0D13").interpolate(Color.web("#2A3550"), .35);
            g.setLineCap(StrokeLineCap.ROUND);
            g.setStroke(front ? Color.web("#3A3F55") : Color.web("#2B3048"));
            g.setLineWidth(w * 1.18);
            curve(g, bx, by, qx, qy, tx, ty);
            g.setStroke(shade);
            g.setLineWidth(w);
            curve(g, bx, by, qx, qy, tx, ty);
            g.setStroke(Color.web(front ? "#7A82A6" : "#555D7E").deriveColor(0, 1, 1, .8));
            g.setLineWidth(w * .2);
            double ox = -ca * w * .22, oy = -w * .18;
            curve(g, bx + ox, by + oy, qx + ox, qy + oy, tx + ox, ty + oy);
            // les anneaux du tube
            for (double u : new double[]{.3, .5, .7}) {
                double[] p = bez(bx, by, qx, qy, tx, ty, u), d = bezTangent(bx, by, qx, qy, tx, ty, u);
                double n = Math.hypot(d[0], d[1]), nx = -d[1] / n, ny = d[0] / n;
                g.setStroke(Color.web(front ? "#2A2D3D" : "#232838"));
                g.setLineWidth(Math.max(1, w * .22));
                g.strokeLine(p[0] - nx * w * .5, p[1] - ny * w * .5, p[0] + nx * w * .5, p[1] + ny * w * .5);
            }
            // les bagues dorées aux deux bouts
            for (double u : new double[]{.04, .94}) {
                double[] p = bez(bx, by, qx, qy, tx, ty, u), d = bezTangent(bx, by, qx, qy, tx, ty, u);
                double ang = Math.toDegrees(Math.atan2(d[1], d[0]));
                g.save();
                g.translate(p[0], p[1]);
                g.rotate(ang);
                Color gold = front ? GOLD : GOLD.deriveColor(0, .8, .75, 1);
                g.setFill(new LinearGradient(0, -w * .7, 0, w * .7, false, CycleMethod.NO_CYCLE, new Stop(0, GOLD_LIGHT.interpolate(gold, front ? 0 : .4)),
                        new Stop(.5, gold), new Stop(1, GOLD_DEEP)));
                g.fillRoundRect(-w * .32, -w * .68, w * .64, w * 1.36, w * .3, w * .3);
                g.setStroke(Color.web("#5C3E10"));
                g.setLineWidth(Math.max(.5, R * .006));
                g.strokeRoundRect(-w * .32, -w * .68, w * .64, w * 1.36, w * .3, w * .3);
                g.restore();
            }
        }
    }

    private static void curve(GraphicsContext g, double ax, double ay, double qx, double qy, double bx, double by) {
        g.beginPath();
        g.moveTo(ax, ay);
        g.quadraticCurveTo(qx, qy, bx, by);
        g.stroke();
    }

    private static double[] bez(double ax, double ay, double qx, double qy, double bx, double by, double u) {
        double v = 1 - u;
        return new double[]{v * v * ax + 2 * v * u * qx + u * u * bx, v * v * ay + 2 * v * u * qy + u * u * by};
    }

    private static double[] bezTangent(double ax, double ay, double qx, double qy, double bx, double by, double u) {
        return new double[]{2 * (1 - u) * (qx - ax) + 2 * u * (bx - qx), 2 * (1 - u) * (qy - ay) + 2 * u * (by - qy)};
    }

    /**
     * Sa matière : le dégradé (violet en bas, nuit au centre, oreilles toutes bleues), la nébuleuse qui dérive, les deux galaxies
     * des oreilles, les étoiles blanches ; puis le liseré, les reflets, le contour, et son visage.
     */
    private void drawMatter(GraphicsContext g) {
        List<Lobe> lobes = lobes();
        double cell = Math.max(.012, 1.6 / R);
        int n = (int) Math.ceil(1.9 / cell) + 1;
        List<double[][]> loops = outline(lobes, -.95, -.95, n, n, cell);
        List<double[][]> px = new ArrayList<>();
        int count = 0;
        for (double[][] loop : loops) {
            double[][] p = new double[2][loop[0].length];
            double minX = 1e9, maxX = -1e9;
            for (int i = 0; i < loop[0].length; i++) {
                p[0][i] = X(loop[0][i]);
                p[1][i] = Y(loop[1][i]);
                minX = Math.min(minX, p[0][i]);
                maxX = Math.max(maxX, p[0][i]);
            }
            px.add(p);
            if (maxX - minX > R * .05) count++;
        }
        pieces = Math.max(1, count);
        if (px.isEmpty()) return;
        g.save();
        path(g, px);
        g.clip();
        // la nuit du corps
        g.setFill(Color.web("#0E1252"));
        g.fillRect(X(-1), Y(-1), 2 * R, 2 * R);
        // le violet monte du bas du corps
        g.setFill(new RadialGradient(0, 0, X(bodyX), Y(bodyY + .3), R * .5, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#B44BF0").deriveColor(0, 1, 1, .95)), new Stop(.5, Color.web("#7A35DA").deriveColor(0, 1, 1, .7)),
                new Stop(1, Color.web("#3A1F9A").deriveColor(0, 1, 1, 0))));
        g.fillRect(X(-1), Y(-1), 2 * R, 2 * R);
        // les bouffées gardent la couleur d'où elles viennent
        for (int k = 0; k < puffs.length; k++) {
            Lobe l = lobes.get(lobes.size() - 2 * puffs.length + 2 * k);
            Color c = puffs[k][0] == 2 ? Color.web("#A646EE") : Color.web("#4FD2F6");
            double r = l.r() * R * 1.8;
            g.setFill(new RadialGradient(0, 0, X(l.x()), Y(l.y()), r, false, CycleMethod.NO_CYCLE, new Stop(0, c.deriveColor(0, 1, 1, .95)),
                    new Stop(.6, c.deriveColor(0, 1, 1, .6)), new Stop(1, c.deriveColor(0, 1, 1, 0))));
            g.fillOval(X(l.x()) - r, Y(l.y()) - r, 2 * r, 2 * r);
        }
        // les oreilles : toutes bleues, plus claires au cœur
        for (int side = 0; side < 2; side++) {
            double ex = side == 0 ? earLX : earRX, ey = side == 0 ? earLY : earRY, r = R * .34;
            g.setFill(new RadialGradient(0, 0, X(ex), Y(ey), r, false, CycleMethod.NO_CYCLE, new Stop(0, Color.web("#A8F4FF")),
                    new Stop(.35, Color.web("#4FD2F6")), new Stop(.72, Color.web("#2387E8")), new Stop(1, Color.web("#1C4FD0").deriveColor(0, 1, 1, 0))));
            g.fillOval(X(ex) - r, Y(ey) - r, 2 * r, 2 * r);
        }
        // l'ombre douce derrière le visage
        g.setFill(new RadialGradient(0, 0, X(bodyX), Y(bodyY - .02), R * .27, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#070A2E").deriveColor(0, 1, 1, .8)), new Stop(1, Color.web("#070A2E").deriveColor(0, 1, 1, 0))));
        g.fillOval(X(bodyX) - R * .27, Y(bodyY - .02) - R * .27, R * .54, R * .54);
        // la nébuleuse, qui dérive lentement, en voile
        double tile = R * 1.1, ox = -(t * R * .015 % tile), oy = -(t * R * .008 % tile);
        g.setGlobalAlpha(.32);
        for (double x = X(-1) + ox - tile; x < X(1); x += tile)
            for (double y = Y(-1) + oy - tile; y < Y(1); y += tile) g.drawImage(nebula, x, y, tile, tile);
        g.setGlobalAlpha(1);
        // une galaxie dans chaque oreille, qui tourne (en sens opposés)
        galaxy(g, X(earLX), Y(earLY), R * .12, t * .35 * pace);
        galaxy(g, X(earRX), Y(earRY), R * .12, -t * .3 * pace + 1.2);
        // les étoiles blanches, emportées lentement
        for (double[] st : stars) {
            double a = st[0] + t * .025 * pace, d = st[1];
            double x = X(bodyX + d * Math.cos(a)), y = Y(bodyY - .12 + d * .95 * Math.sin(a));
            double tw = .55 + .45 * Math.sin(t * st[4] * .5 + st[3]), r = st[2] * R;
            if (st[5] > .5) star(g, x, y, r * 1.3, Color.web("#F2FAFF"), tw);
            else {
                g.setFill(Color.color(.85, .95, 1, .8 * tw));
                g.fillOval(x - r * .45, y - r * .45, r * .9, r * .9);
            }
        }
        // le liseré : un trait clair à l'intérieur du bord, côté lumière (en haut à gauche)
        g.setLineJoin(StrokeLineJoin.ROUND);
        g.translate(R * .02, R * .025);
        path(g, px);
        g.setStroke(Color.web("#BFF3FF").deriveColor(0, 1, 1, .35));
        g.setLineWidth(Math.max(1, R * .03));
        g.stroke();
        g.translate(-R * .02, -R * .025);
        // un reflet sur chaque morceau
        for (double[][] p : px) {
            double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
            for (int i = 0; i < p[0].length; i++) {
                minX = Math.min(minX, p[0][i]); maxX = Math.max(maxX, p[0][i]);
                minY = Math.min(minY, p[1][i]); maxY = Math.max(maxY, p[1][i]);
            }
            double w = maxX - minX, h = maxY - minY;
            if (w < R * .1) continue;
            double size = Math.min(w, R * .45);
            g.save();
            g.translate(minX + w * .28, minY + h * .16);
            g.rotate(-28);
            g.setFill(Color.color(.85, .95, 1, .35));
            g.fillOval(-size * .13, -size * .03, size * .26, size * .06);
            g.restore();
        }
        g.restore();
        // le contour
        path(g, px);
        g.setStroke(INK);
        g.setLineWidth(Math.max(1, R * .016));
        g.setLineJoin(StrokeLineJoin.ROUND);
        g.stroke();
        drawFace(g, lobes.get(0));
    }

    /** Une petite galaxie spirale : un bulbe lumineux et deux bras logarithmiques de poussière d'étoiles, qui tournent. */
    private static void galaxy(GraphicsContext g, double x, double y, double r, double angle) {
        g.setFill(new RadialGradient(0, 0, x, y, r * 1.4, false, CycleMethod.NO_CYCLE, new Stop(0, Color.color(1, 1, 1, .55)),
                new Stop(.3, Color.color(.8, .97, 1, .25)), new Stop(1, Color.color(.6, .9, 1, 0))));
        g.fillOval(x - r * 1.4, y - r * 1.4, r * 2.8, r * 2.8);
        for (int arm = 0; arm < 2; arm++) {
            for (int k = 0; k < 26; k++) {
                double theta = angle + arm * Math.PI + k * .22;
                double d = r * .12 * Math.exp(.085 * k);
                if (d > r * 1.25) break;
                double px = x + d * Math.cos(theta), py = y + d * .62 * Math.sin(theta);
                double size = Math.max(.5, r * (.12 - .0035 * k));
                g.setFill(Color.color(.92, .99, 1, .85 - .028 * k));
                g.fillOval(px - size, py - size, 2 * size, 2 * size);
            }
        }
        g.setFill(Color.WHITE);
        g.fillOval(x - r * .14, y - r * .1, r * .28, r * .2);
    }

    private static void path(GraphicsContext g, List<double[][]> loops) {
        g.beginPath();
        for (double[][] p : loops) {
            g.moveTo(p[0][0], p[1][0]);
            for (int i = 1; i < p[0].length; i++) g.lineTo(p[0][i], p[1][i]);
            g.closePath();
        }
    }

    /** Une étoile à quatre branches effilées, et sa petite lueur. */
    private static void star(GraphicsContext g, double x, double y, double r, Color c, double alpha) {
        double w = r * .2;
        g.setFill(new RadialGradient(0, 0, x, y, r * 1.2, false, CycleMethod.NO_CYCLE, new Stop(0, c.deriveColor(0, 1, 1, .4 * alpha)),
                new Stop(1, c.deriveColor(0, 1, 1, 0))));
        g.fillOval(x - r * 1.2, y - r * 1.2, 2.4 * r, 2.4 * r);
        g.setFill(c.deriveColor(0, 1, 1, Math.min(1, alpha)));
        g.fillPolygon(new double[]{x - r, x - w, x, x + w, x + r, x + w, x, x - w}, new double[]{y, y - w, y - r, y - w, y, y + w, y + r, y + w}, 8);
    }

    /**
     * Son visage, sur le grand lobe : deux yeux ovales ambrés (rouge-orangé en haut, pêche en bas, un grand reflet blanc), un peu
     * inclinés vers l'extérieur, avec des cils fins ; des paupières qui descendent en arc ; un plissement doux ; le regard qui suit
     * la souris. Une bouche apparaît par moments.
     */
    private void drawFace(GraphicsContext g, Lobe heart) {
        double fx = X(heart.x() + .02 * gazeX), fy = Y(heart.y() + .01 + .015 * gazeY);
        double sep = .135 * R, er = .058 * R * eyeSize, eh = .084 * R * eyeSize;
        g.save();
        g.translate(fx, fy);
        g.rotate(tilt);
        // les joues : une rougeur très douce
        for (int s = -1; s <= 1; s += 2) {
            double bx = s * sep * 1.15, by = eh * 1.3;
            g.setFill(new RadialGradient(0, 0, bx, by, er * 1.5, false, CycleMethod.NO_CYCLE, new Stop(0, Color.web("#FF7FB4").deriveColor(0, 1, 1, .22)),
                    new Stop(1, Color.web("#FF7FB4").deriveColor(0, 1, 1, 0))));
            g.fillOval(bx - er * 1.5, by - er * 1.5, er * 3, er * 3);
        }
        for (int s = -1; s <= 1; s += 2) {
            double ex = s * sep + .025 * R * gazeX;
            g.save();
            g.translate(ex, .015 * R * gazeY);
            g.rotate(s * 9);
            // la lueur de l'œil
            g.setFill(new RadialGradient(0, 0, 0, 0, eh * 1.9, false, CycleMethod.NO_CYCLE, new Stop(0, Color.web("#FF9A4A").deriveColor(0, 1, 1, .3 * (1 - lid))),
                    new Stop(1, Color.web("#FF9A4A").deriveColor(0, 1, 1, 0))));
            g.fillOval(-eh * 1.9, -eh * 1.9, eh * 3.8, eh * 3.8);
            double top = -eh + 2 * eh * lid, bottom = eh - 2 * eh * .5 * squint * (1 - lid);
            if (lid < .92) {
                g.save();
                g.beginPath();
                int m = 14;
                for (int k = 0; k <= m; k++) {
                    double u = -1.3 + 2.6 * k / m, bow = (1 - Math.min(1, u * u)) * eh * .35 * Math.min(1, lid * 3);
                    if (k == 0) g.moveTo(u * er, top + bow);
                    else g.lineTo(u * er, top + bow);
                }
                for (int k = m; k >= 0; k--) {
                    double u = -1.3 + 2.6 * k / m, bow = (1 - Math.min(1, u * u)) * eh * .3 * Math.min(1, squint * 2);
                    g.lineTo(u * er, Math.max(top + .3, bottom - bow));
                }
                g.closePath();
                g.clip();
                g.setFill(new LinearGradient(0, -eh, 0, eh, false, CycleMethod.NO_CYCLE, new Stop(0, Color.web("#B83A26")), new Stop(.45, Color.web("#EE7A35")),
                        new Stop(1, Color.web("#FFD48C"))));
                g.fillOval(-er, -eh, 2 * er, 2 * eh);
                g.setStroke(Color.web("#3A0E0C"));
                g.setLineWidth(Math.max(.8, R * .01));
                g.strokeOval(-er, -eh, 2 * er, 2 * eh);
                g.setFill(Color.color(1, 1, 1, .95));
                g.fillOval(-er * .55 - .012 * R * gazeX, -eh * .72, er * .9, eh * .62);
                g.setFill(Color.color(1, .96, .88, .8));
                g.fillOval(er * .2, eh * .38, er * .35, eh * .22);
                g.restore();
            } else {
                // fermés : une courbe douce, comme quand on dort
                g.setStroke(Color.web("#F2A35A"));
                g.setLineWidth(Math.max(1, R * .016));
                g.setLineCap(StrokeLineCap.ROUND);
                g.strokeArc(-er * 1.1, -eh * .6, er * 2.2, eh * 1.1, 200, 140, ArcType.OPEN);
            }
            // les cils : deux traits fins au coin extérieur de la paupière
            g.setStroke(Color.web("#8E9BFF").deriveColor(0, 1, 1, .75));
            g.setLineWidth(Math.max(.7, R * .009));
            g.setLineCap(StrokeLineCap.ROUND);
            double lidY = Math.min(eh * .2, top + eh * .25);
            double cxl = s * er * .82, cyl = lidY - eh * .55 * (1 - lid);
            g.strokeLine(cxl, cyl, cxl + s * er * .55, cyl - eh * .35);
            g.strokeLine(cxl - s * er * .2, cyl - eh * .12, cxl + s * er * .2, cyl - eh * .5);
            g.restore();
        }
        // la bouche, par moments
        if (mouthShow > .03) {
            double my = eh * 1.35, mw = .055 * R * (.6 + .4 * mouthShow);
            g.setGlobalAlpha(Math.min(1, mouthShow));
            if (mouthOpen < -.5) {
                double r = mw * .45;
                g.setFill(Color.web("#2A0812"));
                g.fillOval(-r, my - r * .9, 2 * r, 2 * r * 1.1);
                g.setStroke(Color.web("#FF9FC4"));
                g.setLineWidth(Math.max(.8, R * .01));
                g.strokeOval(-r, my - r * .9, 2 * r, 2 * r * 1.1);
            } else if (mouthOpen < .08) {
                g.setStroke(Color.web("#FF9FC4"));
                g.setLineWidth(Math.max(.9, R * .013));
                g.setLineCap(StrokeLineCap.ROUND);
                g.strokeArc(-mw * .6, my - mw * .45, mw * 1.2, mw * .7, 200, 140, ArcType.OPEN);
            } else {
                double h = mw * (.35 + .8 * mouthOpen);
                g.setFill(Color.web("#2A0812"));
                g.fillArc(-mw * .7, my - h * .45, mw * 1.4, h * 1.4, 180, 180, ArcType.CHORD);
                g.setFill(Color.web("#FF8FB6"));
                g.fillArc(-mw * .42, my + h * .1, mw * .84, h * .65, 180, 180, ArcType.CHORD);
                g.setStroke(Color.web("#FF9FC4").deriveColor(0, 1, 1, .8));
                g.setLineWidth(Math.max(.7, R * .009));
                g.strokeArc(-mw * .7, my - h * .45, mw * 1.4, h * 1.4, 180, 180, ArcType.CHORD);
            }
            g.setGlobalAlpha(1);
        }
        g.restore();
    }

    /** Les perles de matière qui flottent autour d'elle : de petites sphères sombres, un reflet. */
    private void drawBeads(GraphicsContext g) {
        for (int i = 0; i < beads.length; i++) {
            double[] b = beads[i];
            double a = b[0] + b[2] * t * pace, d = b[1] * (gather * .9 + .1) + .15 * scatter;
            double x = X(.02 * Math.sin(t * .2) + d * Math.cos(a)), y = Y(-.06 + d * .8 * Math.sin(a) + .02 * Math.sin(t * .6 + b[0]));
            double r = b[3] * R;
            boolean blue = i != 1;
            g.setFill(new RadialGradient(0, 0, x - r * .3, y - r * .35, r * 1.3, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.web(blue ? "#A8F4FF" : "#D79BFF")), new Stop(.6, Color.web(blue ? "#2F8FE8" : "#8A3BDB")),
                    new Stop(1, Color.web(blue ? "#163E9E" : "#3E1B8A"))));
            g.fillOval(x - r, y - r, 2 * r, 2 * r);
            g.setStroke(INK);
            g.setLineWidth(Math.max(.6, R * .008));
            g.strokeOval(x - r, y - r, 2 * r, 2 * r);
            g.setFill(Color.color(.85, .92, 1, .85));
            g.fillOval(x - r * .5, y - r * .55, r * .5, r * .38);
        }
    }

    /** Le verre, côté devant : le bord, un grand croissant de lumière à gauche, un arc à droite, des reflets. */
    private void drawGlassFront(GraphicsContext g) {
        // le bord clair à l'intérieur du verre
        g.setStroke(Color.color(.8, .9, 1, .22));
        g.setLineWidth(R * .06);
        g.strokeOval(X(-.96), Y(-.96), 1.92 * R, 1.92 * R);
        // le croissant de gauche
        g.beginPath();
        int n = 30;
        for (int k = 0; k <= n; k++) {
            double a = Math.toRadians(140 + 95.0 * k / n);
            if (k == 0) g.moveTo(X(.94 * Math.cos(a)), Y(.94 * Math.sin(a)));
            else g.lineTo(X(.94 * Math.cos(a)), Y(.94 * Math.sin(a)));
        }
        for (int k = n; k >= 0; k--) {
            double a = Math.toRadians(140 + 95.0 * k / n), r = .94 - .085 * Math.sin(Math.PI * k / n);
            g.lineTo(X(.02 + r * Math.cos(a)), Y(r * Math.sin(a)));
        }
        g.closePath();
        g.setFill(Color.color(1, 1, 1, .32));
        g.fill();
        // l'arc de droite, et des tirets
        g.setLineCap(StrokeLineCap.ROUND);
        g.setStroke(Color.color(1, 1, 1, .55));
        g.setLineWidth(Math.max(1.2, R * .03));
        g.strokeArc(X(-.88), Y(-.88), 1.76 * R, 1.76 * R, 8, 42, ArcType.OPEN);
        g.setLineWidth(Math.max(1, R * .022));
        g.strokeArc(X(-.88), Y(-.88), 1.76 * R, 1.76 * R, -32, 9, ArcType.OPEN);
        g.strokeArc(X(-.88), Y(-.88), 1.76 * R, 1.76 * R, 198, 10, ArcType.OPEN);
        // deux pastilles de reflet en haut à gauche
        for (double[] p : new double[][]{{-.5, -.56, .15, .045}, {-.3, -.73, .08, .028}}) {
            g.save();
            g.translate(X(p[0]), Y(p[1]));
            g.rotate(-42);
            g.setFill(Color.color(1, 1, 1, .6));
            g.fillRoundRect(-p[2] * R / 2, -p[3] * R / 2, p[2] * R, p[3] * R, p[3] * R, p[3] * R);
            g.restore();
        }
        // le contour du verre
        g.setStroke(Color.web("#22325E"));
        g.setLineWidth(Math.max(1, R * .012));
        g.strokeOval(X(-1), Y(-1), 2 * R, 2 * R);
    }

    /**
     * L'ornement doré, en haut sur le verre : une étoile à quatre branches, deux volutes qui suivent la courbe de la boule et se
     * terminent en boucle, et un petit cristal cyan suspendu (il s'allume quand elle a une idée ou qu'elle parle).
     */
    private void drawOrnament(GraphicsContext g, Color state) {
        g.setLineJoin(StrokeLineJoin.ROUND);
        for (int s = -1; s <= 1; s += 2) {
            // une volute : un ruban qui s'affine en suivant le bord, puis une boucle
            int n = 26;
            double[] xs = new double[2 * n + 2], ys = new double[2 * n + 2];
            for (int k = 0; k <= n; k++) {
                double u = (double) k / n, a = Math.toRadians(-90 + s * (7 + 40 * u)), r = .885 - .02 * Math.sin(Math.PI * u);
                double thick = .036 * (1 - .75 * u) + .006;
                xs[k] = X(r * Math.cos(a));
                ys[k] = Y(r * Math.sin(a) - thick * .2);
                xs[2 * n + 1 - k] = X((r - thick) * Math.cos(a));
                ys[2 * n + 1 - k] = Y((r - thick) * Math.sin(a) + thick * .3);
            }
            g.setFill(new LinearGradient(0, Y(-.95), 0, Y(-.75), false, CycleMethod.NO_CYCLE, new Stop(0, GOLD_LIGHT), new Stop(.6, GOLD), new Stop(1, GOLD_DEEP)));
            g.fillPolygon(xs, ys, 2 * n + 2);
            g.setStroke(GOLD_DEEP);
            g.setLineWidth(Math.max(.6, R * .007));
            g.strokePolygon(xs, ys, 2 * n + 2);
            // la boucle au bout
            double a = Math.toRadians(-90 + s * 47), bx = X(.87 * Math.cos(a)), by = Y(.87 * Math.sin(a));
            g.setStroke(GOLD);
            g.setLineWidth(Math.max(.8, R * .012));
            g.setLineCap(StrokeLineCap.ROUND);
            g.strokeArc(bx - R * .045 + s * R * .02, by - R * .055, R * .08, R * .07, s > 0 ? 120 : -120, s > 0 ? 200 : -200, ArcType.OPEN);
        }
        // l'étoile
        double sx = X(0), sy = Y(-.875), sr = R * .085;
        g.setFill(new LinearGradient(sx, sy - sr, sx, sy + sr, false, CycleMethod.NO_CYCLE, new Stop(0, GOLD_LIGHT), new Stop(.5, GOLD), new Stop(1, GOLD_DEEP)));
        double w = sr * .26;
        double[] px = {sx - sr, sx - w, sx, sx + w, sx + sr, sx + w, sx, sx - w}, py = {sy, sy - w, sy - sr * .95, sy - w, sy, sy + w, sy + sr * .75, sy + w};
        g.fillPolygon(px, py, 8);
        g.setStroke(GOLD_DEEP);
        g.setLineWidth(Math.max(.6, R * .008));
        g.strokePolygon(px, py, 8);
        g.setFill(Color.color(1, 1, 1, .55));
        g.fillPolygon(new double[]{sx - sr * .5, sx, sx}, new double[]{sy, sy - sr * .55, sy}, 3);
        // le cristal suspendu
        double gy = Y(-.755), gh = R * .05, gw = R * .022;
        g.setStroke(GOLD_DEEP);
        g.setLineWidth(Math.max(.5, R * .006));
        g.strokeLine(sx, sy + sr * .7, sx, gy - gh * .5);
        double glow = Math.min(1, .25 + ideaGlow + (activity() == Activity.TALKING ? .35 : 0));
        Color crystal = Color.web("#5FE3FF").interpolate(state, .3);
        g.setFill(new RadialGradient(0, 0, sx, gy, gh * 1.8, false, CycleMethod.NO_CYCLE, new Stop(0, crystal.deriveColor(0, 1, 1, .5 * glow)),
                new Stop(1, crystal.deriveColor(0, 1, 1, 0))));
        g.fillOval(sx - gh * 1.8, gy - gh * 1.8, gh * 3.6, gh * 3.6);
        double[] cxs = {sx, sx + gw, sx, sx - gw}, cys = {gy - gh * .5, gy, gy + gh * .6, gy};
        g.setFill(crystal.interpolate(Color.WHITE, .25 * glow));
        g.fillPolygon(cxs, cys, 4);
        g.setStroke(Color.web("#1B6A88"));
        g.setLineWidth(Math.max(.5, R * .006));
        g.strokePolygon(cxs, cys, 4);
        g.setFill(Color.color(1, 1, 1, .7));
        g.fillPolygon(new double[]{sx, sx - gw * .6, sx}, new double[]{gy - gh * .4, gy, gy + gh * .1}, 3);
    }

    /**
     * Le socle : un col sombre qui reçoit la boule, une bande cerclée d'or où brillent les voyants, une jupe noire évasée avec son
     * filet d'or et l'emblème « G_ », deux anses courbes aux bagues dorées. Sa teinte et ses voyants suivent ce qu'elle fait.
     */
    private void drawBase(GraphicsContext g, Color state) {
        Color tint = Color.web("#14151F").interpolate(state, .07), dark = Color.web("#06070C").interpolate(state, .04);
        // la jupe évasée
        double topY = Y(1.0), botY = Y(1.27), tw = .6 * R, bw = .82 * R;
        g.setFill(new LinearGradient(0, topY, 0, botY, false, CycleMethod.NO_CYCLE, new Stop(0, tint), new Stop(1, dark)));
        g.beginPath();
        g.moveTo(cx - tw, topY);
        g.lineTo(cx - bw, botY);
        g.quadraticCurveTo(cx, botY + R * .13, cx + bw, botY);
        g.lineTo(cx + tw, topY);
        g.closePath();
        g.fill();
        g.setFill(Color.web("#06070C"));
        g.fillOval(cx - bw, botY - R * .06, 2 * bw, R * .13);
        g.setFill(new LinearGradient(0, topY, 0, botY, false, CycleMethod.NO_CYCLE, new Stop(0, tint), new Stop(1, dark)));
        g.fillOval(cx - bw * .995, botY - R * .075, 2 * bw * .995, R * .12);
        // un reflet vertical sur la jupe
        g.setFill(Color.color(1, 1, 1, .06));
        g.fillPolygon(new double[]{cx - tw * .55, cx - tw * .35, cx - bw * .42, cx - bw * .66}, new double[]{topY, topY, botY, botY}, 4);
        // le filet d'or de la jupe, et le losange : une étoile à quatre branches entre deux filets
        g.setStroke(GOLD);
        g.setLineWidth(Math.max(.8, R * .012));
        g.strokeArc(cx - R * .66, Y(1.0) - R * .02, R * 1.32, R * .14, 190, 160, ArcType.OPEN);
        double ey = Y(1.16), er = R * .075, ew = er * .26;
        g.setStroke(GOLD.deriveColor(0, 1, 1, .9));
        g.setLineWidth(Math.max(.6, R * .008));
        g.strokeLine(cx - R * .42, ey, cx - er * 1.25, ey);
        g.strokeLine(cx + er * 1.25, ey, cx + R * .42, ey);
        double[] lx = {cx - er, cx - ew, cx, cx + ew, cx + er, cx + ew, cx, cx - ew}, ly = {ey, ey - ew, ey - er, ey - ew, ey, ey + ew, ey + er, ey + ew};
        g.setFill(new LinearGradient(0, ey - er, 0, ey + er, false, CycleMethod.NO_CYCLE, new Stop(0, GOLD_LIGHT), new Stop(.5, GOLD), new Stop(1, GOLD_DEEP)));
        g.fillPolygon(lx, ly, 8);
        g.setStroke(GOLD_DEEP);
        g.setLineWidth(Math.max(.5, R * .006));
        g.strokePolygon(lx, ly, 8);
        g.setFill(Color.color(1, 1, 1, .5));
        g.fillPolygon(new double[]{cx - er * .55, cx, cx}, new double[]{ey, ey - er * .6, ey}, 3);
        // la bande du haut, sous le col
        double bandTop = Y(.9), bandBot = Y(1.01), bw2 = .58 * R;
        g.setFill(new LinearGradient(0, bandTop, 0, bandBot, false, CycleMethod.NO_CYCLE, new Stop(0, Color.web("#2A2C3A").interpolate(state, .06)),
                new Stop(1, Color.web("#0C0D14"))));
        g.fillRect(cx - bw2, bandTop, 2 * bw2, bandBot - bandTop);
        g.fillOval(cx - bw2, bandBot - R * .06, 2 * bw2, R * .12);
        // le col : l'anneau sombre où se pose la boule, et sa lèvre éclairée par la lueur
        g.setFill(Color.web("#11121A"));
        g.fillOval(cx - bw2, bandTop - R * .07, 2 * bw2, R * .14);
        g.setStroke(state.interpolate(Color.WHITE, .3).deriveColor(0, 1, 1, .55 * glow));
        g.setLineWidth(Math.max(.8, R * .014));
        g.strokeArc(cx - bw2 * .92, bandTop - R * .055, 2 * bw2 * .92, R * .11, 180, 180, ArcType.OPEN);
        // le filet d'or de la bande
        g.setStroke(GOLD);
        g.setLineWidth(Math.max(.8, R * .014));
        g.strokeArc(cx - bw2, Y(.955) - R * .06, 2 * bw2, R * .12, 190, 160, ArcType.OPEN);
    }

    // ================================================================== la parole
    private void buildSpeech() {
        name.setStyle("-fx-text-fill:#B9A6FF;-fx-font-size:9px;-fx-font-weight:bold;-fx-letter-spacing:2;");
        text.setWrapText(true);
        text.setStyle("-fx-text-fill:#E9E6FF;-fx-font-size:11.5px;");
        text.setMaxWidth(Double.MAX_VALUE);
        formula.setWrapText(true);
        formula.setStyle("-fx-text-fill:#FDE68A;-fx-font-family:Consolas,monospace;-fx-font-size:10.5px;");
        formula.setMaxWidth(Double.MAX_VALUE);
        speech.getChildren().addAll(name, text, formula);
        speech.setPadding(new Insets(8, 11, 9, 12));
        speech.setStyle("-fx-background-color:linear-gradient(to bottom, rgba(34,26,78,0.92), rgba(18,14,44,0.92));"
                + "-fx-background-radius:12;-fx-border-color:rgba(185,166,255,0.35);-fx-border-radius:12;");
        speech.setMinHeight(Region.USE_PREF_SIZE);
        speech.setOnMouseClicked(e -> { if (typed < full.length()) typed = full.length(); else if (!queue.isEmpty()) holdUntil = 0; });
    }

    /** Elle dit quelque chose (après ce qu'elle est en train de dire). */
    void say(String sentence, String calc, Mood m) {
        if (typed >= full.length() && queue.isEmpty() && t >= holdUntil) begin(sentence, calc, m);
        else {
            queue.add(new String[]{sentence, calc, m.name()});
            while (queue.size() > 3) queue.poll();               // une rafale : on garde les dernières
        }
    }

    private void begin(String sentence, String calc, Mood m) {
        full = sentence;
        fullFormula = calc == null ? "" : calc;
        typed = 0;
        holdUntil = t + full.length() / 46.0 + 3.2;
        idleTime = 0;
        setMood(m);
    }

    /** Un événement du moteur : elle l'explique, avec son humeur et, pour un photon, un éclair de sa couleur. */
    void explain(SimEvent e) {
        if (e.getKind().equals("photon_fired")) return;
        double nm = e.getWavelengthNm(), ev = e.getEnergyEv() > 0 ? e.getEnergyEv() : nm > 0 ? 1239.84 / nm : 0;
        String light = nm > 0 ? lightName(nm) : "";
        String title = e.getTitle(), detail = e.getDetail(), calc = e.getFormula().length() <= 90 ? e.getFormula() : "";
        String photon = nm > 0 ? String.format(Locale.FRANCE, "%.0f nm, %.2f eV — %s", nm, ev, light) : "";
        String sentence;
        Mood m;
        switch (e.getKind()) {
            case "emitted" -> {
                m = Mood.HAPPY;
                sentence = "Un photon s'échappe ! " + (photon.isEmpty() ? "" : "(" + photon + ") ")
                        + "L'électron redescend d'un niveau et l'écart d'énergie part en lumière. " + title + ".";
            }
            case "absorbed" -> {
                m = Mood.EXCITED;
                sentence = "Le photon est avalé ! " + (photon.isEmpty() ? "" : "(" + photon + ") ")
                        + "Son énergie vaut exactement l'écart entre deux niveaux : l'électron monte. " + title + ".";
            }
            case "passed" -> {
                m = Mood.CURIOUS;
                sentence = "Celui-là passe tout droit" + (photon.isEmpty() ? "" : " (" + photon + ")")
                        + " : son énergie ne correspond à aucun saut permis, l'atome ne peut pas le prendre.";
            }
            case "photo_ionized" -> {
                m = Mood.EXCITED;
                sentence = "Paf ! Ce photon avait plus que l'énergie de liaison : l'électron est arraché, l'atome devient un ion. " + title + ".";
            }
            case "excited" -> {
                m = Mood.EXCITED;
                sentence = title + ". " + detail;
            }
            case "captured" -> {
                m = Mood.HAPPY;
                sentence = "Capturé ! " + title + ". L'électron trouve sa place, et l'énergie qu'il gagne doit partir quelque part.";
            }
            case "rejected" -> {
                m = Mood.CURIOUS;
                sentence = "Oh… " + title + ". " + (detail.isEmpty() ? "" : detail);
            }
            case "decay" -> {
                m = Mood.EXCITED;
                sentence = "Le noyau se transforme : " + title + ". " + (detail.isEmpty() ? "Il change d'élément !" : detail);
            }
            case "nucleon" -> {
                m = Mood.HAPPY;
                sentence = title + " : le noyau change, donc l'atome (ou l'isotope) aussi.";
            }
            case "structure" -> {
                m = Mood.IDEA;
                sentence = title + " ! " + (detail.isEmpty() ? "" : detail);
            }
            default -> {
                m = Mood.CURIOUS;
                sentence = title + (detail.isEmpty() ? "." : ". " + detail);
            }
        }
        if (nm > 0 && (e.getKind().equals("emitted") || e.getKind().equals("absorbed") || e.getKind().equals("photo_ionized"))) {
            flashColour = Visuals.wavelengthColor(nm);
            flash = 1;
        }
        if (sentence.length() > 260) sentence = sentence.substring(0, sentence.lastIndexOf(' ', 257)) + "…";
        say(sentence.replace(" .", ".").replace("..", "."), calc, m);
        wake();
    }

    /** Le nom d'une lumière d'après sa longueur d'onde (nm). */
    static String lightName(double nm) {
        if (nm < 380) return "de l'ultraviolet, invisible pour nos yeux";
        if (nm < 450) return "une lumière violette";
        if (nm < 495) return "une lumière bleue";
        if (nm < 570) return "une lumière verte";
        if (nm < 590) return "une lumière jaune";
        if (nm < 620) return "une lumière orange";
        if (nm < 750) return "une lumière rouge";
        return "de l'infrarouge, invisible pour nos yeux";
    }

    Mood mood() { return mood; }

    String saying() { return full; }

    /** Pour les aperçus : tourner son corps (degrés) sans attendre. */
    void pose(double yawDeg) {
        yawNow = yawDeg;
        lookX = yawDeg / 34;
    }
}
