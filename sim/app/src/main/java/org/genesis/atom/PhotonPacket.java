package org.genesis.atom;

import org.genesis.app.I18n;
import org.genesis.common.Visuals;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Le photon comme un paquet d'onde en 3D, construit à partir de sa seule
 * longueur d'onde λ.
 *
 *  - Enveloppe gaussienne : un fuseau brillant au centre, qui fond aux bouts.
 *  - Deux rubans perpendiculaires entre eux et à la propagation : le champ
 *    électrique E et le champ magnétique B. Dans le vide, ils sont en phase et
 *    |B| = |E|/c (dessiné un peu plus court). La polarisation est rectiligne,
 *    E le long de l'axe z de l'atome ; les rubans suivent la caméra 3D, et le
 *    paquet arrive un peu de biais (de l'arrière vers l'avant), en perspective.
 *  - Crêtes et enveloppe avancent ensemble à c (vitesses de phase et de groupe
 *    égales dans le vide) : le motif est fixe dans le paquet.
 *  - Distance entre deux crêtes = λ, à l'échelle quand elle tient à l'écran
 *    (les rayons X ont la taille d'un atome). Sinon elle est resserrée selon
 *    log λ, et l'étiquette donne le facteur : un photon UV de 100 nm est un
 *    millier de fois plus grand que l'atome.
 *  - Couleur : CIE 1931 dans le visible ; conventionnelle hors du visible
 *    (violet → blanc bleuté pour UV et X, rose pâle pour γ, rouge sombre pour IR).
 *  - S'il peut ioniser l'atome (hν > énergie d'ionisation actuelle) : franges
 *    chromatiques et halo qui tremble, d'autant plus marqués que
 *    log(1 + (hν − I)/I) est grand.
 */
public final class PhotonPacket {
    static final double HC_EV_NM = 1239.84193;

    /** Ce que λ impose au dessin, à cette échelle. */
    public record Look(double nm, double spacing, boolean toScale, double compression, double length, double amplitude,
                Color colour, double ionising) {
        double energyEv() {
            return HC_EV_NM / nm;
        }
    }

    private PhotonPacket() {
    }

    /** Le paquet d'un photon de λ = nm, vu à `metresPerPixel`, pour un atome d'énergie d'ionisation donnée. */
    static Look look(double nm, double metresPerPixel, double ionizationEv) {
        double lambdaPx = nm * 1e-9 / metresPerPixel;
        boolean toScale = lambdaPx >= 6 && lambdaPx <= 140;
        double spacing = toScale ? lambdaPx : clamp(14 + 22 * Math.log10(nm), 7, 110);
        double energy = HC_EV_NM / nm;
        double ionising = ionizationEv > 0 && energy > ionizationEv ? (energy - ionizationEv) / ionizationEv : 0;
        // Un paquet de taille raisonnable : il doit se lire comme un photon, pas barrer l'écran.
        return new Look(nm, spacing, toScale, lambdaPx / spacing, clamp(5 * spacing, 80, 220),
                clamp(0.2 * spacing, 5, 11), colour(nm), ionising);
    }

    /** Couleur de rendu : vraie dans le visible, conventionnelle (et annoncée) ailleurs. */
    public static Color colour(double nm) {
        if (nm >= 380 && nm <= 780) {
            return Visuals.wavelengthColor(nm);
        }
        if (nm > 780) {
            double f = clamp(Math.log10(nm / 780) / Math.log10(10000 / 780.0), 0, 1);
            return Color.web("#EF4444").interpolate(Color.web("#7F1D1D"), f);
        }
        if (nm >= 10) {
            double f = clamp(Math.log10(380 / nm) / Math.log10(38), 0, 1);
            return Color.web("#8B5CF6").interpolate(Color.web("#C7D2FE"), f);
        }
        if (nm >= 0.01) {
            double f = clamp(Math.log10(10 / nm) / 3, 0, 1);
            return Color.web("#C7D2FE").interpolate(Color.web("#E0F2FE"), f);
        }
        return Color.web("#F5D0FE");
    }

    /** « λ à l'échelle », ou le facteur de resserrement / d'agrandissement des crêtes. */
    static String scaleNote(Look look) {
        if (look.toScale()) {
            return "λ à l'échelle";
        }
        return look.compression() > 1 ? I18n.text("λ dessinée ÷") + SimCanvas.sci(look.compression())
                : I18n.text("λ dessinée ×") + SimCanvas.sci(1 / look.compression());
    }

    /**
     * Dessine le paquet centré en (x, y), qui avance selon (ux, uy) à l'écran.
     * La caméra oriente les rubans : E suit l'axe z de l'atome.
     */
    static void draw(GraphicsContext g, Camera3D camera, double x, double y, double ux, double uy, Look look,
                     double fade, double clock) {
        if (fade <= 0.01) {
            return;
        }
        // Le photon arrive en biais, de l'arrière vers l'avant : sans cela, un ruban serait vu par la tranche.
        double kz = 0.85;
        double kn = Math.sqrt(1 + kz * kz);
        double kx = ux / kn;
        double ky = uy / kn;
        kz /= kn;
        double[] e1 = new double[3];
        camera.fromChemistry(0, 0, 1, e1);
        double along = e1[0] * kx + e1[1] * ky + e1[2] * kz;
        e1[0] -= along * kx;
        e1[1] -= along * ky;
        e1[2] -= along * kz;
        double norm = Math.sqrt(e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2]);
        if (norm < 0.2) {
            e1[0] = -ky;
            e1[1] = kx;
            e1[2] = 0;
            norm = Math.hypot(kx, ky);
        }
        for (int k = 0; k < 3; k++) {
            e1[k] /= norm;
        }
        // B = k × E.
        double[] e2 = {ky * e1[2] - kz * e1[1], kz * e1[0] - kx * e1[2], kx * e1[1] - ky * e1[0]};
        double focal = 700;      // perspective douce : l'avant du paquet est un peu plus grand

        int samples = 96;
        double half = look.length() / 2;
        double sigma = look.length() / 5;
        double[] ax = new double[samples];
        double[] ay = new double[samples];
        double[] ex = new double[samples];
        double[] ey = new double[samples];
        double[] bx = new double[samples];
        double[] by = new double[samples];
        double amplitude = look.amplitude();
        for (int i = 0; i < samples; i++) {
            double s = -half + 2 * half * i / (samples - 1);
            double envelope = Math.exp(-0.5 * s * s / (sigma * sigma));
            double wave = Math.cos(2 * Math.PI * s / look.spacing()) * envelope;
            double depth = kz * s;
            double f = focal / (focal - depth);
            ax[i] = x + kx * s * f;
            ay[i] = y + ky * s * f;
            double fe = focal / (focal - depth - e1[2] * amplitude * wave);
            ex[i] = x + (kx * s + e1[0] * amplitude * wave) * fe;
            ey[i] = y + (ky * s + e1[1] * amplitude * wave) * fe;
            double fb = focal / (focal - depth - e2[2] * amplitude * 0.82 * wave);
            bx[i] = x + (kx * s + e2[0] * amplitude * 0.82 * wave) * fb;
            by[i] = y + (ky * s + e2[1] * amplitude * 0.82 * wave) * fb;
        }
        double tailX = ax[0];
        double tailY = ay[0];
        double headX = ax[samples - 1];
        double headY = ay[samples - 1];
        Color colour = look.colour();
        Color pale = colour.interpolate(Color.WHITE, 0.55);

        // Le fuseau : un halo le long de l'axe, brillant au centre.
        g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        g.setStroke(envelopeGradient(tailX, tailY, headX, headY, colour, 0.22 * fade));
        g.setLineWidth(amplitude * 1.3);
        g.strokeLine(tailX, tailY, headX, headY);

        if (look.ionising() > 0) {
            drawIonisingHalo(g, x, y, ux, uy, look, ex, ey, fade, clock);
        }
        // Le ruban B (derrière), puis le ruban E.
        ribbon(g, ax, ay, bx, by, tailX, tailY, headX, headY, pale, 0.16 * fade, 0.55 * fade);
        ribbon(g, ax, ay, ex, ey, tailX, tailY, headX, headY, colour, 0.3 * fade, 0.95 * fade);
        // Le cœur du paquet.
        g.setStroke(envelopeGradient(tailX, tailY, headX, headY, Color.WHITE, 0.85 * fade));
        g.setLineWidth(1.6);
        g.strokeLine(tailX, tailY, headX, headY);
        g.setLineCap(javafx.scene.shape.StrokeLineCap.SQUARE);

        if (amplitude >= 10 && fade > 0.4) {
            int crest = samples / 2;
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
            g.setTextAlign(TextAlignment.CENTER);
            g.setFill(colour.deriveColor(0, 1, 1.2, fade));
            I18n.fillText(g, "E", ex[crest] + e1[0] * 9, ey[crest] + e1[1] * 9 + 3);
            g.setFill(pale.deriveColor(0, 1, 1, fade));
            I18n.fillText(g, "B", bx[crest] + e2[0] * 9, by[crest] + e2[1] * 9 + 3);
        }
    }

    /**
     * Ce que le canevas garde quand le photon est en 3D (couche JavaFX 3D) : le halo du fuseau et, s'il est ionisant, ses
     * franges et son halo tremblant.
     */
    static void drawGlow(GraphicsContext g, double x, double y, double ux, double uy, Look look, double fade, double clock) {
        double half = look.length() / 2;
        g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        g.setStroke(envelopeGradient(x - ux * half, y - uy * half, x + ux * half, y + uy * half, look.colour(), 0.09 * fade));
        g.setLineWidth(look.amplitude() * 1.6);
        g.strokeLine(x - ux * half * .8, y - uy * half * .8, x + ux * half * .8, y + uy * half * .8);
        g.setLineCap(javafx.scene.shape.StrokeLineCap.SQUARE);
        if (look.ionising() > 0) {
            int samples = 96;
            double sigma = look.length() / 5;
            double[] ex = new double[samples];
            double[] ey = new double[samples];
            for (int i = 0; i < samples; i++) {
                double s = -half + 2 * half * i / (samples - 1);
                double wave = Math.cos(2 * Math.PI * s / look.spacing()) * Math.exp(-0.5 * s * s / (sigma * sigma));
                ex[i] = x + ux * s * 0.88 - uy * look.amplitude() * wave;
                ey[i] = y + uy * s * 0.88 + ux * look.amplitude() * wave;
            }
            drawIonisingHalo(g, x, y, ux, uy, look, ex, ey, fade, clock);
        }
    }

    /** Un ruban : la surface entre l'axe et la courbe du champ, puis son bord, sous l'enveloppe. */
    private static void ribbon(GraphicsContext g, double[] ax, double[] ay, double[] cx, double[] cy,
                               double tailX, double tailY, double headX, double headY, Color colour,
                               double fillAlpha, double edgeAlpha) {
        int n = ax.length;
        double[] xs = new double[2 * n];
        double[] ys = new double[2 * n];
        for (int i = 0; i < n; i++) {
            xs[i] = ax[i];
            ys[i] = ay[i];
            xs[2 * n - 1 - i] = cx[i];
            ys[2 * n - 1 - i] = cy[i];
        }
        g.setFill(envelopeGradient(tailX, tailY, headX, headY, colour, fillAlpha));
        g.fillPolygon(xs, ys, 2 * n);
        g.setStroke(envelopeGradient(tailX, tailY, headX, headY, colour, edgeAlpha));
        g.setLineWidth(1.4);
        g.strokePolyline(cx, cy, n);
    }

    /**
     * Photon ionisant : franges chromatiques (le ruban E dédoublé en rouge et
     * en cyan) et halo qui tremble, d'écart ∝ log(1 + (hν − I)/I).
     */
    private static void drawIonisingHalo(GraphicsContext g, double x, double y, double ux, double uy, Look look,
                                         double[] ex, double[] ey, double fade, double clock) {
        double shift = (1.5 + 3 * Math.log10(1 + look.ionising())) * fade;
        double nx = -uy;
        double ny = ux;
        int n = ex.length;
        double[] px = new double[n];
        double[] py = new double[n];
        Color[] fringes = {Color.web("#FF3B6B"), Color.web("#3BD5FF")};
        for (int f = 0; f < 2; f++) {
            double sign = f == 0 ? 1 : -1;
            for (int i = 0; i < n; i++) {
                px[i] = ex[i] + nx * shift * sign;
                py[i] = ey[i] + ny * shift * sign;
            }
            g.setStroke(fringes[f].deriveColor(0, 1, 1, 0.38 * fade));
            g.setLineWidth(1.2);
            g.strokePolyline(px, py, n);
        }
        double along = look.length() * 0.32;
        double across = look.amplitude() + 3;
        for (int ring = 1; ring <= 3; ring++) {
            int points = 64;
            double[] hx = new double[points];
            double[] hy = new double[points];
            for (int i = 0; i < points; i++) {
                double angle = 2 * Math.PI * i / points;
                double wobble = 1 + 0.08 * shift * Math.sin(8 * angle + clock * 4 + ring);
                double a = (along + 5 * ring) * Math.cos(angle) * wobble;
                double b = (across + 3 * ring + shift) * Math.sin(angle) * wobble;
                hx[i] = x + ux * a + nx * b;
                hy[i] = y + uy * a + ny * b;
            }
            g.setStroke(look.colour().deriveColor(0, 0.6, 1.3, 0.1 * fade / ring));
            g.setLineWidth(1);
            g.strokePolygon(hx, hy, points);
        }
    }

    /** Dégradé le long du paquet qui suit l'enveloppe gaussienne (σ = longueur / 5). */
    private static LinearGradient envelopeGradient(double x0, double y0, double x1, double y1, Color colour,
                                                   double alpha) {
        double[] at = {0, 0.12, 0.25, 0.38, 0.5, 0.62, 0.75, 0.88, 1};
        Stop[] stops = new Stop[at.length];
        for (int i = 0; i < at.length; i++) {
            double u = (at[i] - 0.5) * 5;          // en unités de σ
            stops[i] = new Stop(at[i], colour.deriveColor(0, 1, 1, alpha * Math.exp(-0.5 * u * u)));
        }
        return new LinearGradient(x0, y0, x1, y1, false, CycleMethod.NO_CYCLE, stops);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
