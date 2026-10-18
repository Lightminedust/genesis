package org.genesis.subatomic;

import org.genesis.app.I18n;
import org.genesis.fusion.ReactorView;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Le style « documentaire » des écrans subatomiques, relevé sur les images de référence : un fond bleu canard nuageux, des hadrons
 * en bulles de verre au liseré cyan, des quarks en boules lumineuses floues, les gluons en ressorts dorés bouclés, les photons en
 * ondes jaune-vert, l'électron en trait bleu à tête brillante, et des légendes en capitales blanches espacées.
 *
 * Tout ce qui est dessiné ici reçoit sa position et sa taille des calculs ; ce fichier ne fixe que l'apparence.
 */
public final class DocStyle {
    static final Color DEEP = Color.web("#123F5E"), SEA = Color.web("#1A6497"), SKY = Color.web("#2379B4");
    static final Color RIM = Color.web("#8FE3FF"), GLASS = Color.web("#5FC8F2");
    static final Color GOLD = Color.web("#F2C55C"), GOLD_CORE = Color.web("#FFF1B8");
    static final Color WAVE = Color.web("#E4F27A"), ELECTRON = Color.web("#46B9FF");
    static final Color WHITE = Color.web("#F4FAFF"), PALE = Color.web("#BFDDF2"), DIM = Color.web("#86AECB");
    static final Color RED = Color.web("#FF4F7B"), GREEN = Color.web("#9BF04E"), BLUE = Color.web("#5EC8FF");
    static final Color ANTI_RED = Color.web("#3FE0D0"), ANTI_GREEN = Color.web("#E05AE0"), ANTI_BLUE = Color.web("#FFC75A");
    static final Color GOOD = Color.web("#8CF0A0"), BAD = Color.web("#FF7A8A");


    private DocStyle() {}

    /** La couleur d'une charge de couleur (0 rouge, 1 vert, 2 bleu) ou de son anticouleur. */
    static Color colour(int index, boolean anti) {
        Color[] plain = {RED, GREEN, BLUE}, opposite = {ANTI_RED, ANTI_GREEN, ANTI_BLUE};
        return (anti ? opposite : plain)[Math.floorMod(index, 3)];
    }

    // ================================================================== le fond
    /** Le fond : un dégradé bleu, plus clair vers le centre, et des nuées douces (bruit de valeur lissé, fixé une fois). */
    static void background(GraphicsContext g, double w, double h) {
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        // l'espace sombre de l'écran de fusion (même dégradé), plutôt qu'un bleu plein
        g.setFill(new RadialGradient(0, 0, w * .5, h * .48, Math.max(w, h) * .75, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0C1830")), new Stop(.55, Color.web("#060C18")), new Stop(1, Color.web("#020409"))));
        g.fillRect(0, 0, w, h);
        // la nébuleuse : de grandes nuées douces en dégradés (pas de texture : nette à toute résolution)
        for (double[] c : NEBULA) {
            double x = c[0] * w, y = c[1] * h, r = c[2] * Math.max(w, h);
            Color tone = c[3] > 0 ? Color.color(.35, .5, .85) : Color.color(0, 0, 0);
            g.setFill(new RadialGradient(0, 0, x, y, r, false, CycleMethod.NO_CYCLE,
                    new Stop(0, tone.deriveColor(0, 1, 1, Math.abs(c[3]))), new Stop(.55, tone.deriveColor(0, 1, 1, Math.abs(c[3]) * .45)),
                    new Stop(1, tone.deriveColor(0, 1, 1, 0))));
            g.fillOval(x - r, y - r, 2 * r, 2 * r);
        }
    }

    /** Les nuées : (x, y, rayon relatifs, opacité ; positive = claire, négative = sombre). */
    private static final double[][] NEBULA = {
            {.18, .22, .32, .10}, {.72, .15, .28, .08}, {.88, .62, .35, -.12}, {.12, .78, .30, -.10}, {.48, .55, .40, .06},
            {.32, .40, .22, -.06}, {.62, .82, .26, .07}, {.95, .30, .22, .05}, {.05, .45, .25, .05}, {.55, .05, .30, -.07},
            {.40, .95, .30, -.08}, {.80, .45, .18, .05},
    };

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    // ================================================================== les objets
    /** Une bulle de hadron : verre teinté, plus dense au bord, liseré lumineux et reflet en haut à gauche. */
    static void bubble(GraphicsContext g, double cx, double cy, double r, Color tint, double alpha) {
        if (r < 2 || alpha <= .01) return;
        g.setFill(new RadialGradient(0, 0, cx, cy, r, false, CycleMethod.NO_CYCLE,
                new Stop(0, tint.deriveColor(0, 1, 1, .05 * alpha)), new Stop(.72, tint.deriveColor(0, 1, 1, .12 * alpha)),
                new Stop(.93, tint.deriveColor(0, 1, 1.2, .32 * alpha)), new Stop(1, tint.deriveColor(0, 1, 1.4, .0))));
        g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setStroke(RIM.deriveColor(0, 1, 1, .18 * alpha));
        g.setLineWidth(Math.max(3, r * .07));
        g.strokeOval(cx - r * .985, cy - r * .985, 2 * r * .985, 2 * r * .985);
        g.setStroke(RIM.deriveColor(0, 1, 1, .75 * alpha));
        g.setLineWidth(1.4);
        g.strokeOval(cx - r * .97, cy - r * .97, 2 * r * .97, 2 * r * .97);
        g.setStroke(Color.color(1, 1, 1, .35 * alpha));
        g.setLineWidth(Math.max(1.2, r * .03));
        g.setLineCap(StrokeLineCap.ROUND);
        g.strokeArc(cx - r * .8, cy - r * .8, 1.6 * r, 1.6 * r, 110, 50, javafx.scene.shape.ArcType.OPEN);
    }

    /** Un quark : une boule lumineuse floue, cœur clair, halo de sa couleur. */
    static void blob(GraphicsContext g, double x, double y, double r, Color c, double alpha) {
        if (alpha <= .01) return;
        g.setFill(new RadialGradient(0, 0, x, y, r * 2.3, false, CycleMethod.NO_CYCLE,
                new Stop(0, c.deriveColor(0, 1, 1, .45 * alpha)), new Stop(1, c.deriveColor(0, 1, 1, 0))));
        g.fillOval(x - r * 2.3, y - r * 2.3, r * 4.6, r * 4.6);
        g.setFill(new RadialGradient(0, 0, x - r * .25, y - r * .3, r * 1.15, false, CycleMethod.NO_CYCLE,
                new Stop(0, c.interpolate(Color.WHITE, .55).deriveColor(0, 1, 1, alpha)), new Stop(.5, c.deriveColor(0, 1, 1, alpha)),
                new Stop(1, c.deriveColor(0, 1.1, .75, .9 * alpha))));
        g.fillOval(x - r, y - r, 2 * r, 2 * r);
    }

    /**
     * Un ressort de gluons entre deux points, en boucles dorées (trochoïde allongée). `pitchPx` : l'écart entre deux boucles ;
     * étirer le segment ajoute des boucles, comme sur les images de référence.
     */
    static void spring(GraphicsContext g, double ax, double ay, double bx, double by, double radius, double pitchPx, double alpha) {
        double dx = bx - ax, dy = by - ay, length = Math.hypot(dx, dy);
        if (length < 2 || alpha <= .01) return;
        double ux = dx / length, uy = dy / length;
        double loops = Math.max(1, length / Math.max(3, pitchPx));
        int n = (int) Math.min(1600, Math.max(30, loops * 18));
        double[] xs = new double[n], ys = new double[n];
        for (int i = 0; i < n; i++) {
            double f = i / (n - 1.0), phase = 2 * Math.PI * loops * f;
            double taper = Math.min(1, Math.min(f, 1 - f) * 10);
            double along = length * f - radius * 1.25 * Math.sin(phase) * taper;
            double across = radius * Math.cos(phase) * taper;
            xs[i] = ax + ux * along - uy * across;
            ys[i] = ay + uy * along + ux * across;
        }
        g.setLineCap(StrokeLineCap.ROUND);
        g.setStroke(GOLD.deriveColor(0, 1, 1, .22 * alpha));
        g.setLineWidth(5);
        g.strokePolyline(xs, ys, n);
        g.setStroke(GOLD.deriveColor(0, 1, 1, .85 * alpha));
        g.setLineWidth(1.6);
        g.strokePolyline(xs, ys, n);
        g.setStroke(GOLD_CORE.deriveColor(0, 1, 1, .9 * alpha));
        g.setLineWidth(.7);
        g.strokePolyline(xs, ys, n);
    }

    /** Une onde (photon) : sinusoïde lumineuse de longueur d'onde `lambdaPx`, qui avance de `phase`. */
    static void wave(GraphicsContext g, double ax, double ay, double bx, double by, double lambdaPx, double amplitude, double phase,
                     Color color, double alpha) {
        double dx = bx - ax, dy = by - ay, length = Math.hypot(dx, dy);
        if (length < 2 || alpha <= .01) return;
        double ux = dx / length, uy = dy / length, lam = Math.max(4, lambdaPx);
        int n = (int) Math.min(2000, Math.max(24, length / Math.min(2, lam / 8)));
        double[] xs = new double[n], ys = new double[n];
        for (int i = 0; i < n; i++) {
            double s = length * i / (n - 1.0), envelope = Math.min(1, Math.min(s, length - s) / Math.max(6, lam * .6));
            double a = amplitude * envelope * Math.sin(2 * Math.PI * s / lam - phase);
            xs[i] = ax + ux * s - uy * a;
            ys[i] = ay + uy * s + ux * a;
        }
        g.setLineCap(StrokeLineCap.ROUND);
        g.setStroke(color.deriveColor(0, 1, 1, .25 * alpha));
        g.setLineWidth(6);
        g.strokePolyline(xs, ys, n);
        g.setStroke(color.interpolate(Color.WHITE, .35).deriveColor(0, 1, 1, alpha));
        g.setLineWidth(1.6);
        g.strokePolyline(xs, ys, n);
    }

    /** Un trait lumineux (la trajectoire de l'électron) avec sa tête. */
    static void track(GraphicsContext g, double ax, double ay, double bx, double by, Color color, double alpha, boolean head) {
        if (alpha <= .01) return;
        g.setLineCap(StrokeLineCap.ROUND);
        g.setStroke(new LinearGradient(ax, ay, bx, by, false, CycleMethod.NO_CYCLE,
                new Stop(0, color.deriveColor(0, 1, 1, 0)), new Stop(1, color.deriveColor(0, 1, 1, .35 * alpha))));
        g.setLineWidth(7);
        g.strokeLine(ax, ay, bx, by);
        g.setStroke(new LinearGradient(ax, ay, bx, by, false, CycleMethod.NO_CYCLE,
                new Stop(0, color.deriveColor(0, 1, 1, .1 * alpha)), new Stop(1, color.interpolate(Color.WHITE, .3).deriveColor(0, 1, 1, alpha))));
        g.setLineWidth(2.2);
        g.strokeLine(ax, ay, bx, by);
        if (head) blob(g, bx, by, 4.5, color.interpolate(Color.WHITE, .4), alpha);
    }

    /** Un éclat : l'instant d'une interaction (absorption du photon, annihilation…), rayons et halo. */
    static void flash(GraphicsContext g, double x, double y, double r, Color color, double alpha) {
        if (alpha <= .01 || r < 1) return;
        g.setFill(new RadialGradient(0, 0, x, y, r, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(1, 1, 1, .95 * alpha)), new Stop(.25, color.deriveColor(0, 1, 1, .6 * alpha)),
                new Stop(1, color.deriveColor(0, 1, 1, 0))));
        g.fillOval(x - r, y - r, 2 * r, 2 * r);
        g.setStroke(Color.color(1, 1, 1, .55 * alpha));
        g.setLineWidth(1.2);
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4 + .2, len = r * (k % 2 == 0 ? 1.6 : 1.0);
            g.strokeLine(x + Math.cos(a) * r * .2, y + Math.sin(a) * r * .2, x + Math.cos(a) * len, y + Math.sin(a) * len);
        }
    }

    // ================================================================== les textes et panneaux
    /**
     * Capitales espacées, à la manière des légendes de documentaire. Les lettres grecques gardent leur casse (π n'est pas Π) et
     * les signes combinants (la barre de ū) restent collés à leur lettre.
     */
    static String caps(String text) {
        StringBuilder out = new StringBuilder();
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            boolean greek = Character.UnicodeBlock.of(c) == Character.UnicodeBlock.GREEK;
            out.append(greek ? c : Character.toUpperCase(c));
            boolean nextCombining = i + 1 < n && Character.getType(text.charAt(i + 1)) == Character.NON_SPACING_MARK;
            if (i < n - 1 && !nextCombining) out.append(c == ' ' ? "  " : " ");
        }
        return out.toString();
    }

    /** Une légende en capitales blanches espacées, avec une ombre douce. */
    static void label(GraphicsContext g, String text, double x, double y, double size, TextAlignment align, double alpha) {
        if (alpha <= .01) return;
        g.setTextAlign(align);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, size));
        g.setFill(Color.color(0, .08, .15, .45 * alpha));
        I18n.fillText(g, caps(text), x + 1, y + 1.5);
        g.setFill(WHITE.deriveColor(0, 1, 1, alpha));
        I18n.fillText(g, caps(text), x, y);
    }

    /** Une légende sans capitales : pour les symboles de particules (e⁻, γ*, ū) dont la casse a un sens. */
    static void symbol(GraphicsContext g, String text, double x, double y, double size, TextAlignment align, double alpha) {
        if (alpha <= .01) return;
        g.setTextAlign(align);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, size));
        g.setFill(Color.color(0, .08, .15, .45 * alpha));
        I18n.fillText(g, text, x + 1, y + 1.5);
        g.setFill(WHITE.deriveColor(0, 1, 1, alpha));
        I18n.fillText(g, text, x, y);
    }

    /** Un panneau de verre bleu translucide. */
    public static void panel(GraphicsContext g, double x, double y, double w, double h) {
        g.setFill(Color.color(.03, .14, .24, .55));
        g.fillRoundRect(x, y, w, h, 16, 16);
        g.setStroke(Color.color(.65, .88, 1, .28));
        g.setLineWidth(1);
        g.strokeRoundRect(x + .5, y + .5, w - 1, h - 1, 16, 16);
    }

    /** Un nombre lisible : 3 chiffres significatifs, notation scientifique hors de [0,01 ; 10⁵[. */
    static String number(double v) {
        if (v == 0) return "0";
        double a = Math.abs(v);
        if (a >= 1e5 || a < 1e-2) {
            int e = (int) Math.floor(Math.log10(a));
            return String.format(I18n.locale(), "%.2f·10%s", v / Math.pow(10, e), ReactorView.superscript(e));
        }
        if (a >= 100) return String.format(I18n.locale(), "%.0f", v);
        if (a >= 10) return String.format(I18n.locale(), "%.1f", v);
        return String.format(I18n.locale(), "%.2f", v);
    }

    static double niceLength(double target) {
        double p = Math.pow(10, Math.floor(Math.log10(target)));
        double m = target / p;
        return (m >= 5 ? 5 : m >= 2 ? 2 : 1) * p;
    }
}
