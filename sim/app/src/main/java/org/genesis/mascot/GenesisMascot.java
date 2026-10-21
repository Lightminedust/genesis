package org.genesis.mascot;

import org.genesis.app.I18n;
import org.genesis.common.Visuals;

import com.studio.genesis.grpc.SimEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
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
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;

/**
 * Genesis, la petite guide : une boule de matière noire, pleine d'univers, dans une boule de verre posée sur un socle.
 *
 * - Sa forme est solide (`GenesisBlob`) : une boule ronde, deux oreilles arrondies, un contour lumineux bleu-blanc et un grand
 *   halo. Dedans, de l'espace : le vide, une nébuleuse bleue qui tourne, des étoiles ; au cœur, une lumière blanche ; en bas, de
 *   l'écume blanche percée de trous ; autour, des gouttelettes qui se détachent d'elle et y reviennent.
 * - Elle n'a pas de visage. Ses humeurs passent par son cœur de lumière (il regarde la souris, bat quand elle parle, baisse
 *   quand elle dort), par ses oreilles (dressées, tombantes, frémissantes) et par son corps de gelée (il s'étire, s'écrase,
 *   bondit).
 * - Un clic : elle rentre ses oreilles, se divise en masses qui flottent comme dans une flasque (`GenesisFlask`), se rassemble,
 *   se reconstitue et rejette des résidus de matière.
 * - La boule (verre, reflets, ornement doré à l'étoile et au petit cristal) et le socle (noir, filets dorés, anses, emblème « G_ »)
 *   ne bougent pas ; seuls changent la lueur du fond de la boule, la teinte du socle, le petit cristal et les voyants, selon ce
 *   qu'elle fait : au repos, en train d'expliquer, une idée, excitée, curieuse, endormie — et la couleur d'un photon.
 *
 * Elle lit le journal : chaque événement du moteur devient une phrase dans sa bulle (avec le calcul quand il y en a un).
 */
public final class GenesisMascot {

    enum Mood { IDLE, HAPPY, CURIOUS, EXCITED, SLEEPY, IDEA }

    /** Ce qu'elle fait, pour les voyants et la lueur : au repos, elle parle, une idée, excitée, curieuse, elle dort. */
    enum Activity { IDLE, TALKING, IDEA, EXCITED, CURIOUS, SLEEPING }

    private static final Color INK = Color.web("#0A1040"), GOLD = Color.web("#E9B95A"), GOLD_LIGHT = Color.web("#FFE7A6"),
            GOLD_DEEP = Color.web("#9A6A22");
    private static final Color STAR_GOLD = Color.web("#FFD98A");

    private final double width, height;
    private final StackPane node = new StackPane();
    private final Canvas canvas;
    private final Random rng = new Random(7);

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
    private final Label name = new Label("GENESIS"), formula = new Label();
    private final TextFlow text = new TextFlow();
    private String shownSpeech = null;
    /** Les amorces de ses explications, mises en valeur dans la bulle. */
    private static final String[] LEADS = {"Pourquoi :", "Et maintenant :", "À retenir :", "Essaie :", "En clair :"};
    private final ArrayDeque<String[]> queue = new ArrayDeque<>();
    private String full = "", fullFormula = "";
    private double typed, holdUntil;

    public GenesisMascot(double width, double height) {
        this.width = width;
        this.height = height;
        canvas = new Canvas(width, height);
        node.getChildren().add(canvas);
        node.setMinSize(width, height);
        node.setPrefSize(width, height);
        node.setMaxSize(width, height);
        node.setOnMouseMoved(e -> { lookX = e.getX() / width - .5; lookY = e.getY() / height - .45; wake(); });
        node.setOnMouseDragged(e -> { lookX = e.getX() / width - .5; lookY = e.getY() / height - .45; wake(); });
        node.setOnMouseExited(e -> lookX = lookY = Double.NaN);
        node.setOnMouseClicked(e -> click(e.getX(), e.getY()));
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

    public Region getNode() { return node; }

    public Region speech() { return speech; }

    /** Pour les aperçus : elle n'avance plus seule (on appelle `tick`). */
    public void setManual(boolean on) { manual = on; }

    /** Combien de morceaux sa matière fait à cet instant : elle, plus les bulles qui s'en sont détachées (1 : tout est recollé). */
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

    // ================================================================== le corps
    private final GenesisBlob blob = new GenesisBlob();
    private final GenesisBlob.Input input = new GenesisBlob.Input();
    private final GenesisFlask flask = new GenesisFlask();
    /** Dans combien de temps elle se divisera d'elle-même (au repos seulement). */
    private double transformIn = 70;
    /** Ses oreilles dressées (0 … 1). */
    private double perk;

    /** Un clic dans la boule : elle s'écrase comme une gelée, puis se divise. */
    private void click(double x, double y) {
        poke();
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

    /** Un clic : ses parties se séparent, flottent un instant, puis se recomposent ; elle rit, et redit ce qu'elle expliquait. */
    void poke() {
        wake();
        blob.poke();
        flask.transform();
        setMood(Mood.HAPPY);
        if (full.isEmpty()) say("C'est moi, Genesis ! Ajoute un proton, tire un photon : je te dirai ce que le moteur a calculé.", "", Mood.HAPPY);
        else typed = 0;
    }

    public void tick(double dt) {
        t += dt;
        moodTime += dt;
        idleTime += dt;
        if (mood != Mood.SLEEPY && idleTime > 80 && queue.isEmpty() && typed >= full.length()) setMood(Mood.SLEEPY);
        if (mood != Mood.IDLE && mood != Mood.SLEEPY && moodTime > 9) setMood(Mood.IDLE);
        boolean talking = typed < full.length();
        if (talking) typed = Math.min(full.length(), typed + dt * 70);
        else if (!queue.isEmpty() && t > holdUntil) {
            String[] next = queue.poll();
            begin(next[0], next[1], Mood.valueOf(next[2]));
        }
        showSpeech(full.substring(0, (int) typed));
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
        // son corps : sa vivacité et ses oreilles suivent son humeur ; son cœur regarde la souris, et bat quand elle parle
        input.vigor = switch (mood) {
            case EXCITED -> 1.9;
            case HAPPY -> 1.35;
            case IDEA -> 1.4;
            case SLEEPY -> .4;
            default -> 1;
        };
        double wantPerk = switch (mood) {
            case CURIOUS, IDEA, EXCITED -> 1;
            case HAPPY -> .55;
            default -> !Double.isNaN(lookX) ? .6 : .1;
        };
        perk += (wantPerk - perk) * Math.min(1, dt * 2.5);
        input.perk = perk;
        input.sleep = mood == Mood.SLEEPY;
        input.talking = talking;
        input.pointer = !Double.isNaN(lookX) && R > 0;
        if (input.pointer) {
            input.pointerX = ((lookX + .5) * width - cx) / R;
            input.pointerY = ((lookY + .45) * height - cy) / R;
        }
        blob.step(dt, input);
        // les masses de la flasque partent de sa boule, et y reviennent
        for (int i = 0; i < GenesisFlask.N; i++) flask.spine(i, blob.x(), blob.y(), GenesisBlob.RADIUS);
        // de temps en temps, au repos, elle se divise d'elle-même
        transformIn -= dt;
        if (transformIn < 0) {
            transformIn = 70 + 60 * rng.nextDouble();
            if (mood == Mood.IDLE && !talking && !input.pointer) flask.transform();
        }
        flask.step(dt);
        pieces = flask.pieces();
        double wantGazeX = !Double.isNaN(lookX) ? .9 * lookX : .22 * Math.sin(t * .25) + (Math.sin(t * .13) > .9 ? .3 : 0);
        double wantGazeY = !Double.isNaN(lookY) ? .7 * lookY : .1 * Math.sin(t * .18);
        gazeX += (wantGazeX - gazeX) * Math.min(1, dt * 1.6);
        gazeY += (wantGazeY - gazeY) * Math.min(1, dt * 1.6);
        // l'éparpillement (un clic) : un ressort doux
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
        drawBody(g);
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
     * Son corps : la boule et ses oreilles, peintes pixel par pixel, avec quelques reflets ; ou, quand elle est divisée, le
     * fluide de la flasque ; et toujours les résidus.
     */
    private void drawBody(GraphicsContext g) {
        double ss = R < 100 ? 1.2 : 1, cover = flask.cover(), limbs = flask.limbs();
        int pixel = Math.max(16, (int) Math.ceil(2 * R * ss));
        if (cover < .997) {
            g.setGlobalAlpha(1 - cover);
            g.drawImage(blob.render(pixel, limbs), X(-1), Y(-1), 2 * R, 2 * R);
            // les reflets : trois traits clairs en haut à droite de la boule, comme sur du verre
            g.save();
            g.translate(X(blob.x()), Y(blob.y()));
            g.scale(1 / blob.tall(), blob.tall());
            g.setGlobalAlpha((1 - cover) * limbs * .85);
            g.setStroke(Color.color(.95, .98, 1));
            g.setLineCap(StrokeLineCap.ROUND);
            g.setLineWidth(Math.max(1, R * GenesisBlob.RADIUS * .06));
            double r = R * GenesisBlob.RADIUS * .8;
            g.strokeArc(-r, -r, 2 * r, 2 * r, 18, 16, ArcType.OPEN);
            g.strokeArc(-r, -r, 2 * r, 2 * r, 42, 6, ArcType.OPEN);
            g.strokeArc(-r, -r, 2 * r, 2 * r, 56, 1, ArcType.OPEN);
            g.restore();
            g.setGlobalAlpha(1);
        }
        if (flask.ink()) g.drawImage(flask.render(pixel), X(-1), Y(-1), 2 * R, 2 * R);
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
        text.setMaxWidth(Double.MAX_VALUE);
        text.setLineSpacing(1.5);
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

    /**
     * Écrit sa bulle : la première ligne dit ce qui s'est passé (claire, un peu plus grande) ; les suivantes commencent par une
     * amorce (« Pourquoi : », « Et maintenant : », « À retenir : »…) mise en couleur.
     */
    private void showSpeech(String shown) {
        if (shown.equals(shownSpeech)) return;
        shownSpeech = shown;
        text.getChildren().clear();
        String[] lines = shown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String lead = "";
            for (String candidate : LEADS) if (line.startsWith(candidate)) lead = candidate;
            if (!lead.isEmpty()) {
                Text head = new Text(lead + " ");
                head.setStyle("-fx-fill:#C4B5FD;-fx-font-size:11.5px;-fx-font-weight:bold;");
                text.getChildren().add(head);
                line = line.substring(lead.length()).stripLeading();
            }
            Text body = new Text(line + (i < lines.length - 1 ? "\n" : ""));
            body.setStyle(i == 0 ? "-fx-fill:#F4F1FF;-fx-font-size:12.5px;-fx-font-weight:bold;" : "-fx-fill:#D9D4F5;-fx-font-size:11.5px;");
            text.getChildren().add(body);
        }
    }

    /** Elle explique cet événement tout de suite (un clic dans le journal), en laissant ce qu'elle disait. */
    public void explainNow(SimEvent e) {
        queue.clear();
        typed = full.length();
        holdUntil = 0;
        explain(e);
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
        holdUntil = t + full.length() / 70.0 + 4.5;
        idleTime = 0;
        setMood(m);
    }

    /**
     * Un événement du moteur : elle l'explique avec des mots simples, en trois temps — ce qui s'est passé, pourquoi, et ce qui va
     * suivre (ou ce qu'il faut retenir). Les nombres viennent de l'événement ; son humeur suit, et un photon lui donne sa couleur.
     */
    public void explain(SimEvent e) {
        if (e.getKind().equals("photon_fired")) return;
        double nm = e.getWavelengthNm(), ev = e.getEnergyEv() > 0 ? e.getEnergyEv() : nm > 0 ? 1239.84 / nm : 0;
        String calc = e.getFormula().length() <= 90 ? e.getFormula() : "";
        String photon = nm > 0 ? String.format(I18n.locale(), " (%.0f nm, %.2f eV : %s)", nm, ev, lightName(nm)) : "";
        String title = e.getTitle(), detail = e.getDetail();
        String sentence;
        Mood m;
        switch (e.getKind()) {
            case "emitted" -> {
                m = Mood.HAPPY;
                sentence = I18n.text("L'atome vient d'émettre de la lumière") + photon + ".\n"
                        + I18n.text("Pourquoi : son électron était perché sur un niveau d'énergie trop haut pour y rester. En redescendant, ")
                        + I18n.text("il a rendu la différence d'énergie sous la forme d'un grain de lumière, un photon.\n")
                        + I18n.text("À retenir : chaque élément rend toujours les mêmes couleurs. C'est sa signature.");
            }
            case "absorbed" -> {
                m = Mood.EXCITED;
                sentence = I18n.text("L'atome a avalé ton photon") + photon + ".\n"
                        + I18n.text("Pourquoi : un électron ne peut prendre que des quantités d'énergie bien précises. Celle de ce photon ")
                        + I18n.text("valait exactement l'écart entre deux de ses niveaux.\n")
                        + I18n.text("Et maintenant : l'électron est monté d'un étage. Il n'y restera pas : il va redescendre en rendant cette lumière.");
            }
            case "passed" -> {
                m = Mood.CURIOUS;
                sentence = I18n.text("Le photon a traversé l'atome sans rien lui faire") + photon + ".\n"
                        + I18n.text("Pourquoi : son énergie ne correspond à aucun saut possible pour l'électron. L'atome ne peut pas en prendre ")
                        + I18n.text("seulement un morceau : c'est tout ou rien.\n")
                        + I18n.text("Essaie : dans l'onglet Lumière, clique une raie marquée ▲. Ce sont les énergies que cet atome accepte.");
            }
            case "photo_ionized" -> {
                m = Mood.EXCITED;
                sentence = I18n.text("Le photon a arraché un électron à l'atome") + photon + ".\n"
                        + I18n.text("Pourquoi : il apportait plus d'énergie qu'il n'en faut pour retenir cet électron. Le surplus")
                        + (e.getKineticEv() > 0 ? String.format(I18n.locale(), " (%.2f eV)", e.getKineticEv()) : "")
                        + I18n.text(" est parti en vitesse pour l'électron.\n")
                        + I18n.text("Et maintenant : l'atome a un électron de moins que de protons. Il est chargé positivement : c'est un ion.");
            }
            case "ionized" -> {
                m = Mood.CURIOUS;
                sentence = I18n.text("Tu as retiré un électron à l'atome.\n")
                        + I18n.text("En clair : il a maintenant plus de protons (+) que d'électrons (−). C'est un ion positif, et les électrons ")
                        + I18n.text("qui restent sont tenus un peu plus fort.");
            }
            case "excited" -> {
                m = Mood.EXCITED;
                sentence = I18n.text("Un choc a donné de l'énergie à l'atome : son électron est monté sur un niveau plus haut.\n")
                        + I18n.text("Pourquoi : dans une flamme, les atomes se cognent sans cesse, et un choc assez fort suffit.\n")
                        + I18n.text("Et maintenant : l'électron va redescendre en émettant de la lumière. C'est ce qui colore les flammes.");
            }
            case "captured" -> {
                m = Mood.HAPPY;
                sentence = I18n.text("L'atome a capturé un électron.\n")
                        + I18n.text("Pourquoi : son noyau, chargé positivement, attire les électrons, et il restait une place libre sur ses couches.\n")
                        + I18n.text("Et maintenant : en s'installant, l'électron perd de l'énergie, qui repart sous forme de lumière.");
            }
            case "rejected" -> {
                m = Mood.CURIOUS;
                sentence = I18n.text("L'atome n'a pas gardé cet électron.\n")
                        + "Pourquoi : " + (detail.isEmpty() ? I18n.text("son noyau ne peut pas retenir un électron de plus. Les électrons déjà là le repoussent ")
                        + I18n.text("plus fort que le noyau ne l'attire.") : detail);
            }
            case "decay" -> {
                m = Mood.EXCITED;
                String what = switch (e.getDecayMode()) {
                    case "alpha" -> "Il a éjecté un petit paquet de 2 protons et 2 neutrons (une particule α). Avec 2 protons de moins, "
                            + I18n.text("ce n'est plus le même élément.");
                    case "beta-" -> "Un de ses neutrons s'est changé en proton, en éjectant un électron. Un proton de plus : c'est l'élément suivant du tableau.";
                    case "beta+" -> "Un de ses protons s'est changé en neutron, en éjectant un positon. Un proton de moins : c'est l'élément précédent du tableau.";
                    case "CE" -> "Un de ses protons a avalé un électron du cortège et s'est changé en neutron. Un proton de moins : c'est l'élément précédent.";
                    case "IT" -> "Il s'est simplement calmé en émettant un rayon γ. Même élément, moins d'énergie.";
                    case "SF" -> "Il s'est cassé en deux noyaux plus petits : c'est une fission.";
                    default -> "Il a éjecté une particule pour se rapprocher d'un équilibre.";
                };
                sentence = I18n.text("Le noyau vient de se transformer : ") + title + ".\n"
                        + I18n.text("Pourquoi : ce noyau n'est pas stable. Il finit toujours par changer, à un moment que seul le hasard décide ; ")
                        + I18n.text("la demi-vie dit juste combien de temps il faut attendre en moyenne.\n")
                        + "En clair : " + what;
            }
            case "nucleon" -> {
                m = Mood.HAPPY;
                sentence = I18n.text("Tu as changé le noyau : ") + title + ".\n"
                        + I18n.text("À retenir : le nombre de protons décide de l'élément. Le nombre de neutrons décide de l'isotope, ")
                        + I18n.text("et si le noyau tiendra ou finira par se désintégrer (regarde la timeline en bas).");
            }
            case "structure" -> {
                m = Mood.HAPPY;
                sentence = title + "." + (detail.isEmpty() ? "" : "\nEn clair : " + detail);
            }
            default -> {
                m = Mood.CURIOUS;
                if (title.startsWith("Saut dans le temps")) {
                    sentence = I18n.text("J'ai fait avancer le temps jusqu'au prochain événement (") + title.replace("Saut dans le temps : ", "") + ").\n"
                            + I18n.text("Pourquoi : rien ne se passait avant. L'attente est tirée au hasard, d'après les durées de vie.");
                } else if (title.startsWith("Rien ne peut arriver")) {
                    sentence = I18n.text("Rien ne peut arriver à cet atome tout seul.\n")
                            + I18n.text("Pourquoi : son électron est déjà au plus bas, et son noyau est stable. Il restera tel quel pour toujours, ")
                            + I18n.text("sauf si tu l'éclaires ou si tu le modifies.");
                } else {
                    sentence = title + "." + (detail.isEmpty() ? "" : "\nEn clair : " + detail);
                }
            }
        }
        if (nm > 0 && (e.getKind().equals("emitted") || e.getKind().equals("absorbed") || e.getKind().equals("photo_ionized"))) {
            flashColour = Visuals.wavelengthColor(nm);
            flash = 1;
        }
        if (sentence.length() > 560) sentence = sentence.substring(0, sentence.lastIndexOf(' ', 557)) + "…";
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

    public String saying() { return full; }

    /** Pour les aperçus : tourner son corps (degrés) sans attendre. */
    void pose(double yawDeg) {
        yawNow = yawDeg;
        lookX = yawDeg / 34;
    }
}
