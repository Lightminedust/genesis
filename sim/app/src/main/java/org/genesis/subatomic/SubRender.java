package org.genesis.subatomic;

import org.genesis.app.I18n;
import org.genesis.common.LabCamera;
import org.genesis.fusion.ReactorView;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Le rendu 3D du monde subatomique, dans le style des images de référence : nébuleuse bleue, quarks en sphères lumineuses floues,
 * gluons en ressorts hélicoïdaux dorés, hadrons enveloppés d'une membrane — la surface de leur sac — qui s'étire avec la corde et
 * s'étrangle quand elle casse, photons en paquets d'onde, électrons en petites sphères à traînée, étiquettes posées sur les objets.
 *
 * La géométrie vient du moteur : positions des quarks, jonction des cordes, rayon du sac (déduit du rayon de charge mesuré),
 * rayon du tube de flux (longueur de pénétration du champ de couleur, réseau), puits de Coulomb. La caméra est en perspective,
 * tournable, avec inertie (comme celle de la fusion).
 */
public final class SubRender {
    /** Une sphère de la membrane (un quark) : position (fm), rayon de sa paroi (fm), palpitation 0–1. */
    record Blob(double[] p, double radius, double pulse, double phase) {}

    /** Un tube de la membrane (une jambe de corde) : extrémités (fm), rayon de sa paroi (fm). */
    record Capsule(double[] a, double[] b, double radius) {}

    /** Une étiquette posée sur un objet : point d'ancrage (écran), titre, détail, couleur d'accent. */
    record Tag(double x, double y, double r, String title, String detail, Color accent, double alpha, double[] p) {}

    /** Les étiquettes en panneaux sur poteaux (comme les fanions d'une carte en relief) plutôt qu'en pastilles flottantes. */
    boolean flags;
    /** La hauteur (fm) où se posent les panneaux : le haut du volume actif ; NaN : à `flagRise` fm au-dessus de chaque objet. */
    double flagTop = Double.NaN, flagRise = 1.8;

    final LabCamera camera;
    GraphicsContext g;
    double width, height, clock, left;
    /** Décalage vertical du centre de la scène (px) : sous l'en-tête de texte. */
    double centreShiftY = 0;
    /** L'épaisseur des traînées (1 : électron ; un muon, 207 fois plus lourd, rayonne 40 000 fois moins : traînée plus fine). */
    double trailWidth = 1;
    /** Le nombre de cases du verre calculé pixel par pixel (moins : plus rapide ; la paroi est lissée au tracé). */
    double membraneCells = 110_000;

    SubRender() {
        this(new LabCamera());
    }

    /** Un rendu qui partage la caméra d'une vue existante (la mer de partons). */
    SubRender(LabCamera camera) {
        this.camera = camera;
    }
    private final List<Tag> tags = new ArrayList<>();
    private final java.util.Map<String, Integer> lastSpot = new java.util.HashMap<>();
    private final List<double[]> taken = new ArrayList<>();
    // une image par membrane dessinée dans l'image en cours : le canevas ne lit les pixels qu'au rendu, une image partagée
    // ferait afficher à chaque bulle les pixels de la dernière (découpés en rectangles)
    private final List<WritableImage> membraneImages = new ArrayList<>();
    private int membranesUsed;
    private int[] membranePixels = new int[0];
    private static final double WYVILL = Math.sqrt(1 - Math.cbrt(.5));    // K(u) = (1−u²)³ vaut ½ en u = 0,454
    private static final double[][] DUST = dust();

    // ================================================================== la scène
    void begin(GraphicsContext g, double w, double h, double stageW, double stageH, double[] target, double depth) {
        begin(g, w, h, 0, stageW, stageH, target, depth, true);
    }

    /** Une scène dans une bande de l'écran [left, left + stageW] ; `background` : peindre aussi le fond. */
    void begin(GraphicsContext g, double w, double h, double left, double stageW, double stageH, double[] target, double depth,
               boolean background) {
        this.g = g;
        this.left = left;
        this.width = left + stageW;
        this.height = stageH;
        tags.clear();
        taken.clear();
        membranesUsed = 0;
        lenses.clear();
        shells.clear();
        if (background) DocStyle.background(g, w, h);
        camera.targetX = target[0];
        camera.targetY = target[1];
        camera.targetZ = target[2];
        camera.setup(stageW, stageH, left + stageW / 2, stageH / 2 + centreShiftY, depth);
        if (background) drawDust(depth);
        g.setLineCap(StrokeLineCap.ROUND);
    }

    /** Se brancher sur une vue qui a déjà posé sa caméra et son fond (la mer de partons). */
    void attach(GraphicsContext g, double left, double stageW, double stageH) {
        this.g = g;
        this.left = left;
        this.width = left + stageW;
        this.height = stageH;
        tags.clear();
        taken.clear();
        membranesUsed = 0;
        g.setLineCap(StrokeLineCap.ROUND);
    }

    /** Des poussières lointaines fixes dans l'espace : elles glissent quand la caméra tourne, ce qui donne la profondeur. */
    private static double[][] dust() {
        java.util.Random r = new java.util.Random(11);
        double[][] out = new double[90][];
        for (int i = 0; i < out.length; i++) {
            double[] v = {r.nextGaussian(), r.nextGaussian(), r.nextGaussian()};
            double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            double far = 6 + r.nextDouble() * 10;
            out[i] = new double[]{v[0] / n * far, v[1] / n * far, v[2] / n * far, .4 + r.nextDouble() * .6};
        }
        return out;
    }

    private void drawDust(double depth) {
        double k = Math.max(1, depth / 6);
        for (double[] d : DUST) {
            double[] s = camera.project(camera.targetX + d[0] * k, camera.targetY + d[1] * k, camera.targetZ + d[2] * k);
            if (s == null) continue;
            double r = 1.2 + 1.8 * d[3];
            g.setFill(Color.color(.8, .93, 1, .16 * d[3]));
            g.fillOval(s[0] - r, s[1] - r, 2 * r, 2 * r);
        }
    }

    double[] project(double[] p) {
        return camera.project(p[0], p[1], p[2]);
    }

    /** Pixels par fm au point p (perspective). */
    double scaleAt(double[] p) {
        double[] s = project(p);
        return s == null ? 0 : s[3];
    }

    // ================================================================== les objets
    /**
     * Un quark, ombré comme les nucléons de la chambre de collision : un dégradé clair → couleur → sombre décentré vers la lumière,
     * un reflet net ; plus loin, il pâlit (profondeur). Sa couleur est sa charge de couleur (anticouleur pour un antiquark).
     */
    void quark(double[] p, double radiusFm, Color c, String label, double alpha) {
        double[] s = project(p);
        if (s == null || alpha <= .01) return;
        double r = Math.max(3.5, radiusFm * s[3]);
        double fog = Math.max(.7, Math.min(1, 1.25 - .06 * (s[2] - camera.distance())));
        double a = alpha * fog;
        g.setGlobalBlendMode(BlendMode.ADD);
        glowAt(s[0], s[1], r * 1.9, c, .22 * a);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setFill(new RadialGradient(0, 0, s[0] - r * .35, s[1] - r * .38, r * 1.15, false, CycleMethod.NO_CYCLE,
                new Stop(0, c.interpolate(Color.WHITE, .7).deriveColor(0, 1, 1, a)), new Stop(.45, c.deriveColor(0, 1, 1, a)),
                new Stop(1, c.deriveColor(0, 1.05, .38, a))));
        g.fillOval(s[0] - r, s[1] - r, 2 * r, 2 * r);
        g.setGlobalBlendMode(BlendMode.ADD);
        glowAt(s[0] - r * .38, s[1] - r * .42, r * .38, Color.WHITE, .5 * a);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        if (label != null && r >= 7) {
            g.setTextAlign(TextAlignment.CENTER);
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, Math.min(14, r * .85)));
            g.setFill(Color.color(.02, .04, .09, .72 * a));
            I18n.fillText(g, label, s[0] + r * .05, s[1] + Math.min(14, r * .85) * .36);
        }
    }

    /** Une lueur (px) : le dégradé à trois arrêts de la chambre de collision. */
    void glowAt(double x, double y, double radius, Color color, double alpha) {
        if (radius < .5 || alpha <= .003) return;
        g.setFill(new RadialGradient(0, 0, x, y, radius, false, CycleMethod.NO_CYCLE,
                new Stop(0, color.deriveColor(0, 1, 1, Math.min(1, alpha))),
                new Stop(.35, color.deriveColor(0, 1, 1, Math.min(1, alpha) * .35)),
                new Stop(1, color.deriveColor(0, 1, 1, 0))));
        g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
    }

    /** La forme d'un hadron à l'écran : centre, rayon (px), et son contour s'il n'est pas rond. */
    record Shell(double x, double y, double r, double[][] outline, Color tint, double alpha) {}

    /**
     * Un hadron dessiné comme un noyau de la chambre de collision, partie arrière : la lueur de gaz (plus chaude s'il est excité),
     * puis le nuage de densité. La densité est celle du mode fondamental d'un quark dans le sac du MIT, ρ(r) ∝ j₀²(xr/R) + j₁²(xr/R),
     * x = 2,04 : pleine au centre, encore 38 % à la paroi, nulle au-delà. Excité, le sac vibre comme une goutte (modes P₂ et P₃,
     * amplitude `wobble`) ; tiré par une corde courte, il s'allonge en ovale de Cassini (`stretch` 0–1) le long de `axis`.
     */
    Shell hadronBack(double[] centre, double radiusFm, Color tint, double heat, double wobble, double phase, double[] axis,
                     double stretch, double alpha) {
        double[] p = project(centre);
        if (p == null || alpha <= .01) return null;
        double r = radiusFm * p[3];
        double angle = 0;
        if (axis != null) {
            double[] tip = project(add(centre, axis));
            if (tip != null) angle = Math.atan2(tip[1] - p[1], tip[0] - p[0]);
        }
        double[][] outline = null;
        if (stretch > .02) outline = cassini(p[0], p[1], r * (1 + .35 * stretch), angle, stretch);
        else if (wobble > .005) outline = drop(p[0], p[1], r, wobble, phase, angle);
        g.setGlobalBlendMode(BlendMode.ADD);
        glowAt(p[0], p[1], r * 2.1, tint.interpolate(Color.web("#FF9A55"), heat * .6), (.16 + .3 * heat) * alpha);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.save();
        double outer = r * 1.05;
        if (outline != null) {
            g.beginPath();
            g.moveTo(outline[0][0], outline[1][0]);
            for (int k = 1; k < outline[0].length; k++) g.lineTo(outline[0][k], outline[1][k]);
            g.closePath();
            g.clip();
            outer *= 1.4 + stretch;
        }
        Color cloud = tint.interpolate(Color.WHITE, .25);
        Stop[] stops = new Stop[9];
        for (int k = 0; k < 8; k++) {
            double f = k / 7.0, z = 2.04 * f / 1.05 * (outline != null ? 1.4 + stretch : 1), rho = bagDensity(Math.min(2.04, z));
            stops[k] = new Stop(f * .97, cloud.deriveColor(0, 1, 1, .5 * rho * alpha));
        }
        stops[8] = new Stop(1, cloud.deriveColor(0, 1, 1, 0));
        g.setFill(new RadialGradient(0, 0, p[0], p[1], outer, false, CycleMethod.NO_CYCLE, stops));
        g.fillOval(p[0] - outer, p[1] - outer, 2 * outer, 2 * outer);
        g.restore();
        if (heat > .05) {
            g.setGlobalBlendMode(BlendMode.ADD);
            glowAt(p[0], p[1], r * .9, Color.web("#FF8A3A"), .3 * heat * alpha);
            g.setGlobalBlendMode(BlendMode.SRC_OVER);
        }
        return new Shell(p[0], p[1], r, outline, tint, alpha);
    }

    /** La partie avant du hadron : le verre (bord de Fresnel teinté, ou la membrane tendue s'il est déformé) et deux reflets. */
    void hadronFront(Shell h) {
        if (h == null) return;
        double r = h.r(), a = h.alpha();
        Color tint = h.tint();
        if (h.outline() != null) {
            g.setStroke(tint.interpolate(Color.WHITE, .6).deriveColor(0, 1, 1, .7 * a));
            g.setLineWidth(1.8);
            g.strokePolygon(h.outline()[0], h.outline()[1], h.outline()[0].length);
        } else {
            g.setFill(new RadialGradient(0, 0, h.x(), h.y(), r * 1.03, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.TRANSPARENT), new Stop(.78, tint.deriveColor(0, 1, 1, 0)),
                    new Stop(.94, tint.interpolate(Color.WHITE, .45).deriveColor(0, 1, 1, .32 * a)),
                    new Stop(.99, tint.interpolate(Color.WHITE, .7).deriveColor(0, 1, 1, .6 * a)), new Stop(1, Color.TRANSPARENT)));
            g.fillOval(h.x() - r * 1.03, h.y() - r * 1.03, 2.06 * r, 2.06 * r);
        }
        g.setGlobalBlendMode(BlendMode.ADD);
        glowAt(h.x() - r * .38, h.y() - r * .42, r * .42, Color.WHITE, .4 * a);
        glowAt(h.x() + r * .5, h.y() + r * .55, r * .12, Color.WHITE, .6 * a);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    /** ρ(z)/ρ(0) du mode fondamental du sac : j₀²(z) + j₁²(z). */
    static double bagDensity(double z) {
        if (z < 1e-6) return 1;
        double j0 = Math.sin(z) / z, j1 = Math.sin(z) / (z * z) - Math.cos(z) / z;
        return j0 * j0 + j1 * j1;
    }

    /** Une goutte qui vibre : R(φ) = R[1 + α₂P₂(cos φ) + α₃P₃(cos φ)], les deux modes à leurs propres cadences. */
    private double[][] drop(double cx, double cy, double r, double wobble, double phase, double axis) {
        double a2 = wobble * Math.sin(clock * 5.1 + phase), a3 = .6 * wobble * Math.sin(clock * 7.3 + phase * 1.7);
        int n = 48;
        double[][] out = new double[2][n];
        for (int k = 0; k < n; k++) {
            double phi = 2 * Math.PI * k / n, c = Math.cos(phi - axis);
            double rr = r * (1 + a2 * (1.5 * c * c - .5) + a3 * (2.5 * c * c * c - 1.5 * c));
            out[0][k] = cx + rr * Math.cos(phi);
            out[1][k] = cy + rr * Math.sin(phi);
        }
        return out;
    }

    /** Un ovale de Cassini le long de `axis` (la paramétrisation de la fission de la chambre de collision), surface conservée. */
    private static double[][] cassini(double cx, double cy, double r, double axis, double stretch) {
        double k = stretch * stretch * (3 - 2 * stretch), a2 = .9 * k;
        int n = 72;
        double[] radius = new double[n];
        double area = 0;
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n - axis, c2 = Math.cos(2 * t), s2 = Math.sin(2 * t);
            double rr2 = a2 * c2 + Math.sqrt(Math.max(0, 1 - a2 * a2 * s2 * s2));
            radius[i] = Math.sqrt(Math.max(0, rr2));
            area += .5 * rr2 * 2 * Math.PI / n;
        }
        double scale = r * Math.sqrt(Math.PI / Math.max(1e-6, area));
        double[][] out = new double[2][n];
        for (int i = 0; i < n; i++) {
            double phi = 2 * Math.PI * i / n;
            out[0][i] = cx + scale * radius[i] * Math.cos(phi);
            out[1][i] = cy + scale * radius[i] * Math.sin(phi);
        }
        return out;
    }

    /**
     * La corde de gluons : un tube de flux doré et lumineux (la lueur de contact de la chambre de collision), de rayon 2λ = 0,46 fm ;
     * plus tendue (σL plus grand), plus vive. Une onde d'énergie y court du quark vers la jonction.
     */
    void fluxTube(double[] a, double[] b, double radiusFm, double tension, double alpha) {
        double[] pa = project(a), pb = project(b);
        if (pa == null || pb == null || alpha <= .01) return;
        double w = Math.max(2, radiusFm * (pa[3] + pb[3]) / 2);
        Color gold = Color.web("#FCD34D");
        double t = Math.max(.25, Math.min(1, tension));
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setLineCap(StrokeLineCap.ROUND);
        g.setStroke(gold.deriveColor(0, 1, 1, .1 * t * alpha));
        g.setLineWidth(w * 2.2);
        g.strokeLine(pa[0], pa[1], pb[0], pb[1]);
        g.setStroke(gold.deriveColor(0, 1, 1, .28 * t * alpha));
        g.setLineWidth(w * .9);
        g.strokeLine(pa[0], pa[1], pb[0], pb[1]);
        g.setStroke(gold.interpolate(Color.WHITE, .55).deriveColor(0, 1, 1, .75 * t * alpha));
        g.setLineWidth(Math.max(1, w * .22));
        g.strokeLine(pa[0], pa[1], pb[0], pb[1]);
        double u = (clock * .8) % 1;
        glowAt(pa[0] + (pb[0] - pa[0]) * u, pa[1] + (pb[1] - pa[1]) * u, w * .9, gold, .35 * t * alpha);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    /**
     * Le halo du champ électrique d'un hadron chargé, comme ceux de la chambre de collision : la densité d'énergie ε₀|E|²/2 du
     * profil calculé (e·E(r) du moteur), en échelle log sur trois décades, à la teinte du hadron.
     */
    void fieldHalo(double[] centre, double[] profileR, double[] profileE, Color tint, double alpha) {
        double[] s = project(centre);
        if (s == null || alpha <= .01 || profileR.length < 2) return;
        double peak = 0;
        for (double e : profileE) peak = Math.max(peak, e * e);
        double outer = 6.0, r = outer * s[3];
        int n = 14;
        Stop[] stops = new Stop[n];
        for (int k = 0; k < n; k++) {
            double f = k / (n - 1.0), e = interpolate(profileR, profileE, f * outer);
            double level = Math.max(0, 1 + Math.log10(Math.max(1e-12, e * e / peak)) / 3);
            stops[k] = new Stop(f, tint.deriveColor(0, 1, 1, .22 * alpha * level * level));
        }
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setFill(new RadialGradient(0, 0, s[0], s[1], r, false, CycleMethod.NO_CYCLE, stops));
        g.fillOval(s[0] - r, s[1] - r, 2 * r, 2 * r);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    /** Un électron (ou un lepton) : une petite sphère dense et brillante, plus un halo. */
    void lepton(double[] p, Color c, double alpha) {
        lepton(p, c, alpha, 1000);
    }

    /**
     * Un lepton : une particule élémentaire, ponctuelle dans le Modèle Standard — un point sursaturé, pas une bille. Autour, une
     * aura dont le rayon suit son énergie (0,08 + 0,05 log₁₀(E/1 MeV) fm : un codage de l'énergie, pas une taille). Hors du plan de
     * netteté (la distance du centre de la scène), il devient flou et plus pâle : la profondeur de champ.
     */
    void lepton(double[] p, Color c, double alpha, double energyMev) {
        lepton(p, c, alpha, energyMev, -1, false);
    }

    /**
     * Un lepton avec son identité : sa charge (le signe −/+ à côté du point ; l'aura est son champ — un neutre n'en a pas), et
     * l'antimatière (un liseré pointillé autour). Un neutrino (charge 0) n'émet ni ne porte de champ : un simple cercle creux.
     */
    void lepton(double[] p, Color c, double alpha, double energyMev, int charge, boolean anti) {
        double[] s = project(p);
        if (s == null || alpha <= .01) return;
        if (charge == 0) {
            double r = 4.5 + 4 * defocus(s[2]);
            g.setStroke(c.deriveColor(0, 1, 1, .75 * alpha));
            g.setLineWidth(1.2);
            if (anti) g.setLineDashes(2.5, 2.5);
            g.strokeOval(s[0] - r, s[1] - r, 2 * r, 2 * r);
            g.setLineDashes((double[]) null);
            g.setFill(c.deriveColor(0, 1, 1, .9 * alpha));
            g.fillOval(s[0] - 1.2, s[1] - 1.2, 2.4, 2.4);
            return;
        }
        drawCharged(s, c, alpha, energyMev);
        double glow = 4.5 + 7 * defocus(s[2]);
        g.setFill(Color.WHITE.deriveColor(0, 1, 1, .9 * alpha));
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        g.setTextAlign(TextAlignment.CENTER);
        I18n.fillText(g, charge < 0 ? "−" : "+", s[0] + glow + 5, s[1] - glow);
        if (anti) {
            double ring = glow * 1.9;
            g.setStroke(c.interpolate(Color.WHITE, .4).deriveColor(0, 1, 1, .8 * alpha));
            g.setLineWidth(1);
            g.setLineDashes(3, 3);
            g.strokeOval(s[0] - ring, s[1] - ring, 2 * ring, 2 * ring);
            g.setLineDashes((double[]) null);
        }
    }

    /** Le spin d'un neutrino : une petite flèche le long de son mouvement — vers l'arrière pour ν (hélicité gauche), vers l'avant
     *  pour ν̄ (droite). C'est la seule chose qui les distingue à l'œil : la nature n'a fait que des ν gauches et des ν̄ droits. */
    void helicity(double[] p, double[] direction, boolean right, double alpha) {
        double[] a = project(p), b = project(add(p, scale(direction, right ? .35 : -.35)));
        if (a == null || b == null || alpha <= .01) return;
        double dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy);
        if (len < 4) return;
        dx /= len;
        dy /= len;
        double nx = -dy * 7, ny = dx * 7;
        g.setStroke(Color.WHITE.deriveColor(0, 1, 1, .85 * alpha));
        g.setLineWidth(1.6);
        g.strokeLine(a[0] + nx, a[1] + ny, b[0] + nx, b[1] + ny);
        g.strokeLine(b[0] + nx, b[1] + ny, b[0] + nx - dx * 6 - dy * 4, b[1] + ny - dy * 6 + dx * 4);
        g.strokeLine(b[0] + nx, b[1] + ny, b[0] + nx - dx * 6 + dy * 4, b[1] + ny - dy * 6 - dx * 4);
    }

    /** Une trajectoire en pointillés : le chemin calculé d'une particule qu'on ne voit pas (un neutrino ne laisse rien). */
    void dotted(List<double[]> path, Color c, double alpha) {
        for (int i = 0; i < path.size(); i += 2) {
            double[] s = project(path.get(i));
            if (s == null) continue;
            double r = Math.max(1, Math.min(4, .035 * s[3])), f = .25 + .75 * i / (double) path.size();
            g.setStroke(c.deriveColor(0, 1, 1, .7 * alpha * f));
            g.setLineWidth(1);
            g.strokeOval(s[0] - r, s[1] - r, 2 * r, 2 * r);
        }
    }

    private void drawCharged(double[] s, Color c, double alpha, double energyMev) {
        double blur = defocus(s[2]);
        double aura = Math.max(10, Math.min(90, (.08 + .05 * Math.log10(Math.max(1, energyMev))) * s[3])) * (1 + blur);
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setFill(new RadialGradient(0, 0, s[0], s[1], aura, false, CycleMethod.NO_CYCLE,
                new Stop(0, c.deriveColor(0, 1, 1, .5 * alpha)), new Stop(.2, c.deriveColor(0, 1, 1, .22 * alpha)),
                new Stop(1, c.deriveColor(0, 1, 1, 0))));
        g.fillOval(s[0] - aura, s[1] - aura, 2 * aura, 2 * aura);
        double core = 1.6 + 5 * blur, glow = 4.5 + 7 * blur;
        g.setFill(new RadialGradient(0, 0, s[0], s[1], glow, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE.deriveColor(0, 1, 1, alpha * (1 - .5 * blur))), new Stop(1, c.deriveColor(0, 1, 1, 0))));
        g.fillOval(s[0] - glow, s[1] - glow, 2 * glow, 2 * glow);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setFill(Color.WHITE.deriveColor(0, 1, 1, alpha * (1 - .6 * blur)));
        g.fillOval(s[0] - core, s[1] - core, 2 * core, 2 * core);
    }

    /** Le flou de profondeur de champ (0 net … 1 très flou) d'un point à la profondeur `depth`, par rapport au centre visé. */
    double defocus(double depth) {
        double[] centre = camera.project(camera.targetX, camera.targetY, camera.targetZ);
        if (centre == null) return 0;
        double d = Math.abs(depth - centre[2]) / Math.max(1e-6, centre[2]);
        return Math.min(1, Math.max(0, (d - .15) * 1.6));
    }

    /** Une traînée : le chemin parcouru (points en fm), qui s'efface vers l'arrière. */
    void trail(List<double[]> path, Color c, double alpha) {
        trail(path, c, alpha, 1);
    }

    /** Une traînée plus épaisse à son départ (`thick` > 1) : le sillage de l'électron là où il a cédé son impulsion. */
    void trail(List<double[]> path, Color c, double alpha, double thick) {
        if (path.size() < 2 || alpha <= .01) return;
        double[] prev = project(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            double[] s = project(path.get(i));
            if (prev != null && s != null) {
                double f = i / (double) (path.size() - 1);
                g.setGlobalBlendMode(BlendMode.ADD);
                g.setStroke(c.deriveColor(0, 1, 1, .22 * alpha * f));
                double boost = 1 + (thick - 1) * (1 - f);
                double blur = defocus(s[2]);                          // au loin, la traînée est floue : plus large, plus pâle
                boost *= 1 + 2.5 * blur;
                f *= 1 - .55 * blur;
                g.setStroke(c.deriveColor(0, 1, 1, .22 * alpha * f));
                g.setLineWidth(6 * boost * trailWidth);
                g.strokeLine(prev[0], prev[1], s[0], s[1]);
                g.setGlobalBlendMode(BlendMode.SRC_OVER);
                g.setStroke(c.interpolate(Color.WHITE, .3).deriveColor(0, 1, 1, .85 * alpha * f));
                g.setLineWidth(1.8 * boost * Math.max(.6, trailWidth));
                g.strokeLine(prev[0], prev[1], s[0], s[1]);
            }
            prev = s;
        }
    }

    /**
     * Un ressort de gluons en 3D : une hélice autour de l'axe de la corde, doublée de petites boucles (l'aspect « pétillant »
     * des images). Le pas est fixe en fm : étirer la corde ajoute des spires. La profondeur module l'éclat.
     */
    void spring(double[] a, double[] b, double alpha, int seed) {
        double[] axis = sub(b, a);
        double length = norm(axis);
        if (length < 1e-4 || alpha <= .01) return;
        double[] u = scale(axis, 1 / length), n1 = perpendicular(u), n2 = cross(u, n1);
        double radius = .045, pitch = .09, small = .022;
        int turns = (int) Math.max(3, length / pitch);
        int n = Math.min(1600, turns * 14);
        double[] xs = new double[n], ys = new double[n];
        boolean[] ok = new boolean[n];
        for (int i = 0; i < n; i++) {
            double f = i / (n - 1.0), phase = 2 * Math.PI * turns * f + seed;
            double curl = 2 * Math.PI * turns * 3.1 * f;
            double taper = Math.min(1, Math.min(f, 1 - f) * 12);
            double r1 = radius * taper, r2 = small * taper;
            double cx = r1 * Math.cos(phase) + r2 * Math.cos(curl), cy = r1 * Math.sin(phase) + r2 * Math.sin(curl);
            double[] w = {a[0] + axis[0] * f + n1[0] * cx + n2[0] * cy, a[1] + axis[1] * f + n1[1] * cx + n2[1] * cy,
                    a[2] + axis[2] * f + n1[2] * cx + n2[2] * cy};
            double[] s = project(w);
            ok[i] = s != null;
            if (s != null) {
                xs[i] = s[0];
                ys[i] = s[1];
            }
        }
        g.setGlobalBlendMode(BlendMode.ADD);
        stroke(xs, ys, ok, DocStyle.GOLD.deriveColor(0, 1, 1, .18 * alpha), 4.5);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        stroke(xs, ys, ok, DocStyle.GOLD.deriveColor(0, 1, 1.05, .9 * alpha), 1.3);
        stroke(xs, ys, ok, DocStyle.GOLD_CORE.deriveColor(0, 1, 1, .7 * alpha), .6);
        // des paillettes : quelques points brillants le long de la corde, qui scintillent
        g.setGlobalBlendMode(BlendMode.ADD);
        for (int i = 0; i < n; i += 23) {
            if (!ok[i]) continue;
            double twinkle = .5 + .5 * Math.sin(clock * 6 + i * 1.7 + seed);
            g.setFill(DocStyle.GOLD_CORE.deriveColor(0, 1, 1, .55 * alpha * twinkle));
            g.fillOval(xs[i] - 1.6, ys[i] - 1.6, 3.2, 3.2);
        }
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    private void stroke(double[] xs, double[] ys, boolean[] ok, Color c, double width) {
        g.setStroke(c);
        g.setLineWidth(width);
        g.beginPath();
        boolean open = false;
        for (int i = 0; i < xs.length; i++) {
            if (!ok[i]) { open = false; continue; }
            if (!open) { g.moveTo(xs[i], ys[i]); open = true; }
            else g.lineTo(xs[i], ys[i]);
        }
        g.stroke();
    }

    /**
     * Un photon : un paquet d'onde en hélice (polarisation circulaire) de longueur d'onde λ (fm, à l'échelle), de rayon
     * `radiusFm`, qui avance de `phase`. `reach` : la part du trajet déjà parcourue.
     */
    void wave(double[] a, double[] b, double lambdaFm, double radiusFm, double phase, double reach, Color c, double alpha) {
        double[] axis = sub(b, a);
        double length = norm(axis) * Math.max(0, Math.min(1, reach));
        if (length < 1e-4 || alpha <= .01) return;
        double[] u = scale(axis, 1 / norm(axis)), n1 = perpendicular(u), n2 = cross(u, n1);
        double lam = Math.max(.02, lambdaFm);
        int n = (int) Math.min(1400, Math.max(40, length / lam * 24));
        double[] xs = new double[n], ys = new double[n];
        boolean[] ok = new boolean[n];
        for (int i = 0; i < n; i++) {
            double s = length * i / (n - 1.0), env = Math.min(1, Math.min(s, length - s) / Math.max(.05, lam * .5));
            double ang = 2 * Math.PI * s / lam - phase;
            double cx = radiusFm * env * Math.cos(ang), cy = radiusFm * env * Math.sin(ang);
            double[] w = {a[0] + u[0] * s + n1[0] * cx + n2[0] * cy, a[1] + u[1] * s + n1[1] * cx + n2[1] * cy,
                    a[2] + u[2] * s + n1[2] * cx + n2[2] * cy};
            double[] p = project(w);
            ok[i] = p != null;
            if (p != null) { xs[i] = p[0]; ys[i] = p[1]; }
        }
        g.setGlobalBlendMode(BlendMode.ADD);
        stroke(xs, ys, ok, c.deriveColor(0, 1, 1, .25 * alpha), 7);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        stroke(xs, ys, ok, c.interpolate(Color.WHITE, .35).deriveColor(0, 1, 1, alpha), 1.8);
    }

    /** Un éclat : l'instant d'une interaction, halo et rayons. */
    void flash(double[] p, double radiusFm, Color c, double alpha) {
        double[] s = project(p);
        if (s == null || alpha <= .01) return;
        g.setGlobalBlendMode(BlendMode.ADD);
        DocStyle.flash(g, s[0], s[1], Math.max(8, radiusFm * s[3]), c, alpha);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    /**
     * Le puits de Coulomb du proton, en brume volumique : sa densité suit la profondeur |U(r)|/|U(0)| du puits calculé par le
     * moteur (profil r, U). Pas de cercles : une sphère de plus en plus épaisse vers le centre, où l'électron « s'enfonce ».
     */
    void well(double[] centre, double[] profileR, double[] profileU, double alpha) {
        double[] s = project(centre);
        if (s == null || alpha <= .01 || profileR.length < 2) return;
        double u0 = Math.abs(profileU[0]), outer = 0;
        for (int i = 0; i < profileR.length; i++) if (Math.abs(profileU[i]) > .08 * u0) outer = profileR[i];
        double r = outer * s[3];
        int n = 14;
        Stop[] stops = new Stop[n];
        for (int k = 0; k < n; k++) {
            double f = k / (n - 1.0), rr = f * outer, depth = Math.abs(interpolate(profileR, profileU, rr)) / u0;
            Color c = Color.web("#4A3CD0").interpolate(Color.web("#8C8CFF"), f);
            stops[k] = new Stop(f, c.deriveColor(0, 1, 1, .6 * alpha * Math.pow(depth, .8)));
        }
        g.setFill(new RadialGradient(0, 0, s[0], s[1], r, false, CycleMethod.NO_CYCLE, stops));
        g.fillOval(s[0] - r, s[1] - r, 2 * r, 2 * r);
    }

    static double interpolate(double[] xs, double[] ys, double x) {
        if (x <= xs[0]) return ys[0];
        for (int i = 1; i < xs.length; i++) {
            if (xs[i] >= x) {
                double f = (x - xs[i - 1]) / Math.max(1e-12, xs[i] - xs[i - 1]);
                return ys[i - 1] + f * (ys[i] - ys[i - 1]);
            }
        }
        return ys[ys.length - 1];
    }

    private WritableImage fieldImage;
    private int[] fieldPixels = new int[0];

    // ================================================================== la lentille gravitationnelle
    /**
     * Toute énergie courbe l'espace (relativité générale) : un rayon qui passe à la distance b d'une énergie E est dévié de
     * α = 4GE/(c⁴b). Une lentille ponctuelle a pour rayon d'Einstein θ_E² = (4GE/c⁴)·D ; ce qu'on voit derrière elle, à l'écart
     * θ, vient de la position β = θ − θ_E² θ/|θ|² (équation de la lentille) : le fond est repoussé et étiré en arcs autour d'elle.
     * À l'échelle du femtomètre la vraie déviation est ~10⁻⁴¹ rad : on garde la loi exacte (θ_E² ∝ E, la même pour tous) et on
     * l'amplifie d'un seul facteur LENS_GAIN, affiché. Un électron de 2 GeV « pèse » ainsi plus que le proton (938 MeV) ; quand
     * il émet un photon, sa lentille rétrécit de ν, le photon emporte la sienne (∝ ν), et celle de sa cible grandit d'autant.
     */
    static final double THETA_1GEV_FM = 1.3;                                  // rayon d'Einstein affiché pour 1 GeV
    static final double LENS_GAIN = THETA_1GEV_FM * THETA_1GEV_FM
            / (4 * 6.67430e-11 * 1.602176634e-10 / Math.pow(299_792_458.0, 4) * 1e15);   // fm² ÷ (4GE/c⁴ pour 1 GeV, en fm)
    static final int LENS_CELL = 6;
    private static final double LENS_MAX_PX = 160;
    private final javafx.scene.effect.DisplacementMap lensEffect = new javafx.scene.effect.DisplacementMap();
    private final javafx.scene.effect.FloatMap[] maps = new javafx.scene.effect.FloatMap[2];
    private int flip, lensW, lensH;
    private float[] lensX = new float[0], lensY = new float[0];
    private final List<double[]> lenses = new ArrayList<>();          // {x écran, y écran, θ_E en px}
    private final List<double[]> shells = new ArrayList<>();          // {x écran, y écran, rayon px, force}

    /** Le rayon d'Einstein affiché (fm) d'une énergie E (MeV) : θ_E ∝ √E. */
    static double einsteinRadius(double energyMev) {
        return THETA_1GEV_FM * Math.sqrt(Math.max(0, energyMev) / 1000);
    }

    /** La déformation de l'espace (lentilles, front de l'information) : coupée pour l'instant, à la demande. */
    boolean warp = false;

    /** Une énergie E (MeV) au point p : elle courbe l'espace autour d'elle. */
    void lens(double[] p, double energyMev, double alpha) {
        if (!warp) return;
        double[] sp = project(p);
        if (sp == null || alpha <= .01 || energyMev <= 0) return;
        lenses.add(new double[]{sp[0], sp[1], einsteinRadius(energyMev) * Math.sqrt(alpha) * sp[3]});
    }

    /**
     * Le front de l'information : quand une charge change brusquement de vitesse, la nouvelle du changement s'éloigne du point
     * d'échange à la vitesse de la lumière, sur une sphère de rayon c·t (le « coude » des lignes de champ de Purcell) ; au-delà, le
     * champ est encore celui de l'ancienne trajectoire. On le montre comme une coquille transparente qui réfracte le fond, d'autant
     * plus faible qu'elle s'éloigne (champ rayonné ∝ 1/r) et proportionnelle à |Δβ|, le saut de vitesse de l'électron.
     */
    void shell(double[] centre, double radiusFm, double strength) {
        if (!warp) return;
        double[] sp = project(centre);
        if (sp == null || strength <= .01 || radiusFm <= 0) return;
        shells.add(new double[]{sp[0], sp[1], radiusFm * sp[3], strength});
        // et une lueur annulaire très douce (pas de contour) : l'énergie du champ qui se réorganise sur la coquille
        double r = radiusFm * sp[3];
        g.setGlobalBlendMode(BlendMode.ADD);
        for (int k = 0; k < 3; k++) {
            g.setStroke(Color.color(.7, .9, 1, (.14 - .04 * k) * Math.min(1, strength)));
            g.setLineWidth(10 + 12 * k);
            g.strokeOval(sp[0] - r, sp[1] - r, 2 * r, 2 * r);
        }
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    /**
     * Pose la carte de déplacement de toutes les lentilles sur le canevas du fond (la carte graphique l'applique ; les objets et
     * le texte, sur le canevas de devant, restent nets). Les déviations des lentilles s'ajoutent (lentilles minces). Le cœur est
     * adouci (s = 0,45 θ_E) pour éviter la singularité du point.
     */
    void commitLens(javafx.scene.canvas.Canvas target) {
        if (lenses.isEmpty() && shells.isEmpty()) {
            if (target.getEffect() == lensEffect) target.setEffect(null);
            return;
        }
        double w = target.getWidth(), h = target.getHeight();
        int cw = (int) Math.ceil(w / LENS_CELL), ch = (int) Math.ceil(h / LENS_CELL);
        if (cw != lensW || ch != lensH) {
            lensW = cw;
            lensH = ch;
            lensX = new float[cw * ch];
            lensY = new float[cw * ch];
        }
        final double cellX = w / cw, cellY = h / ch;
        final double[][] all = lenses.toArray(new double[0][]), rings = shells.toArray(new double[0][]);
        final float[] outX = lensX, outY = lensY;
        java.util.stream.IntStream.range(0, ch).parallel().forEach(j -> {
            double y = (j + .5) * cellY;
            for (int i = 0; i < cw; i++) {
                double x = (i + .5) * cellX, dx = 0, dy = 0;
                for (double[] l : all) {
                    double rx = x - l[0], ry = y - l[1], e2 = l[2] * l[2], soft = .2 * e2;
                    double k = e2 / (rx * rx + ry * ry + soft);
                    dx -= k * rx;                                // β = θ − θ_E² θ/|θ|² : on lit plus près du centre
                    dy -= k * ry;
                }
                for (double[] sh : rings) {
                    // une coquille mince : déplacement radial en dérivée de gaussienne autour du rayon (une lentille annulaire)
                    double rx = x - sh[0], ry = y - sh[1], r = Math.sqrt(rx * rx + ry * ry);
                    if (r < 1e-6) continue;
                    double width = 12, u = (r - sh[2]) / width;
                    double push = 55 * sh[3] * u * Math.exp(-u * u);
                    dx -= push * rx / r;
                    dy -= push * ry / r;
                }
                // près du cœur d'une lentille très forte, le déplacement sature en douceur (au loin il reste ∝ E) ;
                // et l'on ne lit jamais hors de l'image
                double mag = Math.sqrt(dx * dx + dy * dy), cap = LENS_MAX_PX / Math.sqrt(LENS_MAX_PX * LENS_MAX_PX + mag * mag);
                dx = Math.max(1 - x, Math.min(w - 1 - x, dx * cap));
                dy = Math.max(1 - y, Math.min(h - 1 - y, dy * cap));
                outX[j * cw + i] = (float) (dx / w);
                outY[j * cw + i] = (float) (dy / h);
            }
        });
        flip = 1 - flip;                                         // deux cartes en alternance : l'effet se recalcule
        javafx.scene.effect.FloatMap map = maps[flip];
        if (map == null || map.getWidth() != cw || map.getHeight() != ch) maps[flip] = map = new javafx.scene.effect.FloatMap(cw, ch);
        for (int j = 0; j < ch; j++) for (int i = 0; i < cw; i++) map.setSamples(i, j, lensX[j * cw + i], lensY[j * cw + i]);
        lensEffect.setMapData(map);
        if (target.getEffect() != lensEffect) target.setEffect(lensEffect);
    }

    /** Le déplacement posé dans la case (i, j), en px (pour les tests). */
    double[] lensShift(int i, int j, double w, double h) {
        return new double[]{lensX[j * lensW + i] * w, lensY[j * lensW + i] * h};
    }

    int lensColumns() {
        return lensW;
    }

    int lensRows() {
        return lensH;
    }

    private WritableImage nebula;

    /**
     * Le fond : dégradés, une nébuleuse fine (bruit fractal fixe, peu contrasté, calculé une fois à la taille de l'écran),
     * poussières lointaines et un semis de grains — ce que les lentilles déforment. Un dégradé lisse déformé ne se voit pas ;
     * une texture, si (comme les galaxies d'arrière-plan d'une vraie lentille gravitationnelle). Le fond n'est pas de la physique.
     */
    void backdrop(double w, double h, double depth) {
        DocStyle.background(g, w, h);
        if (nebula == null) nebula = nebula(NEBULA_TILE);
        // à l'infini, comme un ciel : elle glisse quand la caméra tourne (Δx = f·Δlacet), jamais quand elle avance
        double focal = h / (2 * Math.tan(Math.toRadians(22)));
        double ox = Math.floorMod((long) Math.round(-camera.yaw * focal), NEBULA_TILE);
        double oy = Math.floorMod((long) Math.round(camera.pitch * focal), NEBULA_TILE);
        if (nebulaShown) for (double x = -ox; x < w; x += NEBULA_TILE) for (double y = -oy; y < h; y += NEBULA_TILE) g.drawImage(nebula, x, y);
        drawDust(depth);
        // les grains : trois couches de profondeur (proches, moyennes, lointaines) ; plus proches, plus gros et plus vifs — en
        // tournant, ils défilent à des vitesses différentes (parallaxe)
        double k = Math.max(1, depth / 6);
        for (double[] d : GRAIN) {
            double[] sp = camera.project(camera.targetX + d[0] * k, camera.targetY + d[1] * k, camera.targetZ + d[2] * k);
            if (sp == null) continue;
            double near = Math.max(0, Math.min(1, sp[3] / (h * .15)));
            g.setFill(Color.color(.85, .95, 1, (.1 + .25 * d[3]) * (.45 + .55 * near)));
            double r = (.45 + .6 * d[3]) * (.6 + 1.6 * near);
            g.fillOval(sp[0] - r, sp[1] - r, 2 * r, 2 * r);
        }
    }

    private static final double[][] GRAIN = grain();

    static final int NEBULA_TILE = 640;
    /** La nébuleuse : absente dans le style de la chambre de collision (l'espace y est noir), gardée pour d'autres vues. */
    boolean nebulaShown = false;

    /**
     * Bruit fractal (5 octaves de bruit de valeur lissé), en voile clair et sombre jusqu'à 17 % autour du fond — périodique : la
     * tuile se raccorde à elle-même, pour pouvoir glisser sans couture quand la caméra tourne.
     */
    private static WritableImage nebula(int size) {
        int w = size, h = size;
        int[] px = new int[w * h];
        java.util.Random r = new java.util.Random(7);
        int[] cells = {7, 14, 28, 56, 112};
        float[][] lattice = new float[cells.length][];
        for (int o = 0; o < cells.length; o++) {
            lattice[o] = new float[cells[o] * cells[o]];
            for (int k = 0; k < lattice[o].length; k++) lattice[o][k] = r.nextFloat();
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = 0, amp = .5, norm = 0;
                for (int o = 0; o < cells.length; o++) {
                    int n = cells[o];
                    double fx = x * (double) n / w, fy = y * (double) n / h;
                    int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
                    double tx = fx - x0, ty = fy - y0;
                    tx = tx * tx * (3 - 2 * tx);
                    ty = ty * ty * (3 - 2 * ty);
                    int a = Math.floorMod(x0, n), a1 = Math.floorMod(x0 + 1, n), b = Math.floorMod(y0, n), b1 = Math.floorMod(y0 + 1, n);
                    float[] l = lattice[o];
                    double v00 = l[b * n + a], v10 = l[b * n + a1], v01 = l[b1 * n + a], v11 = l[b1 * n + a1];
                    v += amp * ((v00 * (1 - tx) + v10 * tx) * (1 - ty) + (v01 * (1 - tx) + v11 * tx) * ty);
                    norm += amp;
                    amp *= .55;
                }
                double n = v / norm - .5;                    // ≈ −0,5 … 0,5
                double strength = Math.min(1, Math.abs(n) * 3.2);
                int alpha = (int) (255 * (n > 0 ? .13 : .2) * strength);
                int rgb = n > 0 ? 0x5F7FC8 : 0x000000;                // nuées bleutées et vides noirs, sur l'espace sombre
                px[y * w + x] = (Math.min(255, alpha) << 24) | rgb;
            }
        }
        WritableImage img = new WritableImage(w, h);
        img.getPixelWriter().setPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), px, 0, w);
        return img;
    }

    private static double[][] grain() {
        java.util.Random r = new java.util.Random(29);
        double[][] out = new double[1100][];
        for (int i = 0; i < out.length; i++) {
            double[] v = {r.nextGaussian(), r.nextGaussian(), r.nextGaussian()};
            double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            double u = r.nextDouble();
            double far = u < .3 ? 3 + 3 * r.nextDouble() : u < .7 ? 6 + 8 * r.nextDouble() : 14 + 22 * r.nextDouble();   // trois couches
            out[i] = new double[]{v[0] / n * far, v[1] / n * far, v[2] / n * far, r.nextDouble()};
        }
        return out;
    }

    /** Dessiner désormais sur une autre couche (le fond), avec la même caméra. */
    void layer(GraphicsContext other) {
        this.g = other;
        other.setLineCap(StrokeLineCap.ROUND);
    }

    // ================================================================== le photon virtuel
    /**
     * La couleur d'un photon selon son énergie ν (MeV), en fausse couleur : orangé vers 50 MeV, jaune vers 700 MeV, blanc bleuté
     * vers 10 GeV (échelle log). Un photon virtuel n'a pas de couleur visible : c'est une échelle d'énergie.
     */
    static Color photonColour(double nuMev) {
        double t = Math.max(0, Math.min(1, Math.log10(Math.max(1, nuMev) / 50) / Math.log10(200)));
        Color warm = Color.web("#FF8A3D"), mid = Color.web("#FFE36B"), hot = Color.web("#EAF6FF");
        return t < .5 ? warm.interpolate(mid, t / .5) : mid.interpolate(hot, (t - .5) / .5);
    }

    /**
     * Le photon virtuel, en arc électrique : il part de l'électron et file vers sa cible. Ce qui est calculé : sa maille — les
     * coudes sont espacés de λ = ħc/Q, l'échelle que le photon résout (court = arc serré et nerveux, long = grands coudes) —, son
     * épaisseur (∝ 1 + log₁₀(1 + Q²/0,01 GeV²)), son éclat et la couleur de son halo (∝ log ν, cf. photonColour) ; le cœur est
     * blanc. Le tracé des coudes (leur direction, les sous-coudes, le second filament) est un tirage renouvelé 24 fois par
     * seconde : la forme exacte d'un échange virtuel n'a pas de sens physique, seule son échelle en a.
     */
    void arc(double[] a, double[] b, double lambdaFm, double radiusFm, double reach, double glowLevel, long seed, Color c, double alpha) {
        if (alpha <= .01 || reach <= 0) return;
        double[] axis = sub(b, a);
        double length = norm(axis);
        if (length < 1e-6) return;
        double[] dir = scale(axis, 1 / length), n1 = perpendicular(dir), n2 = cross(dir, n1);
        int nodes = (int) Math.max(4, Math.min(28, Math.round(length / Math.max(.05, lambdaFm))));
        long frame = (long) Math.floor(clock * 24);
        g.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        g.setGlobalBlendMode(BlendMode.ADD);
        // deux filaments : le principal, et un second plus pâle (le canal a un volume) ; chaque coude porte trois sous-coudes
        for (int strand = 0; strand < 2; strand++) {
            java.util.Random random = new java.util.Random(seed * 31 + frame * 7 + strand * 1013);
            double amplitude = Math.min(lambdaFm, length / 3) * (strand == 0 ? .28 : .4);
            List<double[]> pts = new ArrayList<>();
            int fine = nodes * 4;
            double[] kinkX = new double[nodes + 1], kinkY = new double[nodes + 1];
            for (int k = 1; k < nodes; k++) {
                kinkX[k] = random.nextGaussian() * amplitude;
                kinkY[k] = random.nextGaussian() * amplitude;
            }
            for (int k = 0; k <= fine; k++) {
                double f = k / (double) fine;
                if (f > reach) break;
                double at = f * nodes;
                int lo = (int) Math.min(nodes - 1, Math.floor(at));
                double w = at - lo;
                double ox = kinkX[lo] * (1 - w) + kinkX[lo + 1] * w, oy = kinkY[lo] * (1 - w) + kinkY[lo + 1] * w;
                if (k % 4 != 0) {
                    ox += random.nextGaussian() * amplitude * .3;
                    oy += random.nextGaussian() * amplitude * .3;
                }
                double envelope = Math.sin(Math.PI * f);
                pts.add(add(add(a, scale(axis, f)), add(scale(n1, ox * envelope), scale(n2, oy * envelope))));
            }
            int m = pts.size();
            if (m < 2) continue;
            double[] xs = new double[m], ys = new double[m];
            double scaleSum = 0;
            boolean ok = true;
            for (int k = 0; k < m && ok; k++) {
                double[] sp = project(pts.get(k));
                if (sp == null) { ok = false; break; }
                xs[k] = sp[0];
                ys[k] = sp[1];
                scaleSum += sp[3];
            }
            if (!ok) continue;
            double px = Math.max(1, .45 * radiusFm * scaleSum / m);
            double weight = strand == 0 ? 1 : .4;
            g.setStroke(c.deriveColor(0, 1, 1, .12 * alpha * glowLevel * weight));
            g.setLineWidth(px * 7);
            g.strokePolyline(xs, ys, m);
            g.setStroke(c.deriveColor(0, 1, 1, .4 * alpha * weight));
            g.setLineWidth(px * 2.4);
            g.strokePolyline(xs, ys, m);
            g.setStroke(Color.WHITE.deriveColor(0, 1, 1, .95 * alpha * weight));
            g.setLineWidth(px * (strand == 0 ? 1 : .6));
            g.strokePolyline(xs, ys, m);
        }
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setLineJoin(javafx.scene.shape.StrokeLineJoin.MITER);
    }

    /** Une étincelle : un point de couleur à peine visible (un quark ou un antiquark virtuel). */
    void spark(double[] p, Color c, double alpha) {
        double[] sp = project(p);
        if (sp == null || alpha <= .01) return;
        double r = Math.max(1.1, Math.min(2.6, .022 * sp[3]));
        g.setFill(c.interpolate(Color.WHITE, .35).deriveColor(0, 1, 1, alpha * .35));
        g.fillOval(sp[0] - r * 2, sp[1] - r * 2, r * 4, r * 4);
        g.setFill(c.interpolate(Color.WHITE, .5).deriveColor(0, 1, 1, alpha));
        g.fillOval(sp[0] - r, sp[1] - r, r * 2, r * 2);
    }

    /** Une lueur douce (sans rayons) : une énergie qui se dépose, vue de l'intérieur. */
    void glow(double[] p, double radiusFm, Color c, double alpha) {
        double[] sp = project(p);
        if (sp == null || alpha <= .01) return;
        double r = Math.max(6, radiusFm * sp[3]);
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setFill(new RadialGradient(0, 0, sp[0], sp[1], r, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE.deriveColor(0, 1, 1, .7 * alpha)), new Stop(.25, c.deriveColor(0, 1, 1, .5 * alpha)),
                new Stop(.6, c.deriveColor(0, 1, 1, .16 * alpha)), new Stop(1, c.deriveColor(0, 1, 1, 0))));
        g.fillOval(sp[0] - r, sp[1] - r, 2 * r, 2 * r);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    /**
     * La membrane : la surface du sac qui enveloppe les quarks et les cordes. Le champ F = Σ K(d/R) (noyau de Wyvill, nul au-delà
     * de R) est évalué à l'écran à partir des sphères (quarks) et des tubes (cordes) projetés ; la paroi est F = ½. Deux sacs
     * qui s'éloignent se rejoignent par un col qui s'amincit puis se rompt. Rendu : verre presque transparent, liseré lumineux
     * (Fresnel), halo extérieur doux.
     */
    void membrane(List<Blob> blobs, List<Capsule> capsules, double alpha) {
        if (blobs.isEmpty() || alpha <= .01) return;
        int nb = blobs.size(), nc = capsules.size();
        double[] bx = new double[nb], by = new double[nb], br = new double[nb];
        double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9, typical = 0;
        for (int i = 0; i < nb; i++) {
            Blob b = blobs.get(i);
            double[] s = project(b.p());
            if (s == null) return;
            double pulse = 1 + b.pulse() * Math.sin(clock * (5 + 3 * b.pulse()) + b.phase());
            bx[i] = s[0];
            by[i] = s[1];
            br[i] = b.radius() * pulse / WYVILL * s[3];
            typical = Math.max(typical, br[i] * WYVILL);
            minX = Math.min(minX, bx[i] - br[i]);
            maxX = Math.max(maxX, bx[i] + br[i]);
            minY = Math.min(minY, by[i] - br[i]);
            maxY = Math.max(maxY, by[i] + br[i]);
        }
        double[] cax = new double[nc], cay = new double[nc], cbx = new double[nc], cby = new double[nc], cr = new double[nc];
        for (int i = 0; i < nc; i++) {
            Capsule c = capsules.get(i);
            double[] a = project(c.a()), b = project(c.b());
            if (a == null || b == null) continue;
            cax[i] = a[0];
            cay[i] = a[1];
            cbx[i] = b[0];
            cby[i] = b[1];
            cr[i] = c.radius() / WYVILL * (a[3] + b[3]) / 2;
            minX = Math.min(minX, Math.min(a[0], b[0]) - cr[i]);
            maxX = Math.max(maxX, Math.max(a[0], b[0]) + cr[i]);
            minY = Math.min(minY, Math.min(a[1], b[1]) - cr[i]);
            maxY = Math.max(maxY, Math.max(a[1], b[1]) + cr[i]);
        }
        minX = Math.max(left - 40, minX - 14);
        minY = Math.max(-40, minY - 14);
        maxX = Math.min(width + 40, maxX + 14);
        maxY = Math.min(height + 40, maxY + 14);
        if (maxX <= minX || maxY <= minY) return;
        // la grille : un pixel d'écran par case (au plus 640 × 480 cases), lissée au tracé
        double cell = Math.max(1, Math.max(Math.max((maxX - minX) / 640, (maxY - minY) / 480),
                Math.sqrt((maxX - minX) * (maxY - minY) / membraneCells)));
        int gw = (int) Math.ceil((maxX - minX) / cell) + 2, gh = (int) Math.ceil((maxY - minY) / cell) + 2;
        // le champ et la hauteur de la bulle, ligne par ligne sur tous les cœurs
        final double[] field = new double[gw * gh], height = new double[gw * gh];
        final double x0 = minX, y0 = minY, step = cell;
        final int cols = gw;
        java.util.stream.IntStream.range(0, gh).parallel().forEach(j -> {
            double y = y0 + j * step;
            for (int i = 0; i < cols; i++) {
                double x = x0 + i * step, f = 0;
                for (int k = 0; k < nb; k++) {
                    double dx = x - bx[k], dy = y - by[k], u2 = (dx * dx + dy * dy) / (br[k] * br[k]);
                    if (u2 < 1) { double q = 1 - u2; f += q * q * q; }
                }
                for (int k = 0; k < nc; k++) {
                    if (cr[k] <= 0) continue;
                    double vx = cbx[k] - cax[k], vy = cby[k] - cay[k], len2 = vx * vx + vy * vy;
                    double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - cax[k]) * vx + (y - cay[k]) * vy) / len2));
                    double dx = x - (cax[k] + t * vx), dy = y - (cay[k] + t * vy), u2 = (dx * dx + dy * dy) / (cr[k] * cr[k]);
                    if (u2 < 1) { double q = 1 - u2; f += q * q * q; }
                }
                double sv = f / .5;
                field[j * cols + i] = sv;
                // la hauteur de la bulle : h = √(1 − e^(−(s − 1))), qui sature sans cassure (pas de facettes au reflet)
                height[j * cols + i] = bulge(sv);
            }
        });
        if (membranesUsed >= membraneImages.size()) membraneImages.add(null);
        WritableImage membraneImage = membraneImages.get(membranesUsed);
        if (membraneImage == null || membraneImage.getWidth() < gw || membraneImage.getHeight() < gh) {
            membraneImage = new WritableImage(Math.max(gw, 64), Math.max(gh, 64));
            membraneImages.set(membranesUsed, membraneImage);
        }
        membranesUsed++;
        final int iw = (int) membraneImage.getWidth(), ih = (int) membraneImage.getHeight();
        if (membranePixels.length < iw * ih) membranePixels = new int[iw * ih];
        final int[] out = membranePixels;
        java.util.Arrays.fill(out, 0);
        // l'éclairage : lumière en haut à gauche, devant ; reflet spéculaire, Fresnel au bord, ombre douce en bas
        final double lx = -.45, ly = -.6, lz = .66;
        double hn = Math.sqrt(lx * lx + ly * ly + (lz + 1) * (lz + 1));
        final double hx = lx / hn, hy = ly / hn, hz = (lz + 1) / hn;
        final double depthScale = Math.max(8, typical / cell);              // la bulle bombe sur un rayon typique
        final double glR = DocStyle.GLASS.getRed(), glG = DocStyle.GLASS.getGreen(), glB = DocStyle.GLASS.getBlue();
        final double rimR = DocStyle.RIM.getRed(), rimG = DocStyle.RIM.getGreen(), rimB = DocStyle.RIM.getBlue();
        final double opacity = alpha;
        java.util.stream.IntStream.range(1, gh - 1).parallel().forEach(j -> {
            for (int i = 1; i < cols - 1; i++) {
                double sv = field[j * cols + i];
                if (sv < .25) continue;
                double a, r, gg, b;
                if (sv < 1) {
                    // le halo : une lueur douce juste hors de la paroi (anticrénelage compris)
                    double d = (1 - sv) / .16;
                    double halo = d > 4 ? 0 : Math.exp(-d * d);
                    a = .55 * halo;
                    r = rimR; gg = rimG; b = rimB;
                } else {
                    // la normale par différences finies de la hauteur
                    double nx = -(height[j * cols + i + 1] - height[j * cols + i - 1]) * .5 * depthScale;
                    double ny = -(height[(j + 1) * cols + i] - height[(j - 1) * cols + i]) * .5 * depthScale;
                    double nn = Math.sqrt(nx * nx + ny * ny + 1);
                    nx /= nn; ny /= nn;
                    double nz = 1 / nn;
                    double diffuse = Math.max(0, nx * lx + ny * ly + nz * lz);
                    double spec = pow140(Math.max(0, nx * hx + ny * hy + nz * hz));
                    double f = 1 - nz;
                    double fresnel = f * f * Math.sqrt(Math.sqrt(f));                 // (1 − n_z)^2,25
                    double e = (sv - 1) / .05;
                    double edge = e > 4 ? 0 : Math.exp(-e * e);                        // le liseré net de la paroi
                    double glass = .05 + .55 * fresnel + .5 * edge;
                    double shade = .72 + .38 * diffuse;
                    double white = Math.min(1, spec * 1.1);
                    a = Math.min(1, glass + .75 * white);
                    double mix = Math.min(1, fresnel * 1.3 + edge), norm = Math.max(1e-6, glass + white * .75);
                    r = ((glR + (rimR - glR) * mix) * shade * glass + white * .75) / norm;
                    gg = ((glG + (rimG - glG) * mix) * shade * glass + white * .75) / norm;
                    b = ((glB + (rimB - glB) * mix) * shade * glass + white * .75) / norm;
                }
                a *= opacity;
                int ai = (int) Math.round(255 * Math.max(0, Math.min(1, a)));
                out[j * iw + i] = (ai << 24) | ((int) (Math.min(1, r) * 255) << 16) | ((int) (Math.min(1, gg) * 255) << 8)
                        | (int) (Math.min(1, b) * 255);
            }
        });
        PixelWriter px = membraneImage.getPixelWriter();
        px.setPixels(0, 0, iw, ih, javafx.scene.image.PixelFormat.getIntArgbInstance(), membranePixels, 0, iw);
        g.setImageSmoothing(true);
        g.drawImage(membraneImage, 0, 0, gw, gh, minX - cell / 2, minY - cell / 2, gw * cell, gh * cell);
    }

    // ================================================================== la membrane vectorielle (style de la chambre de collision)
    /** Une membrane tracée : ses contours à l'écran (une boucle par morceau), pour poser le verre par-dessus les quarks. */
    record Membrane(List<double[][]> loops, Color tint, double alpha) {}

    /**
     * Le sac d'un hadron comme surface calculée : le champ F = Σ K(d/R) (noyau de Wyvill (1 − u²)³, nul au-delà de R) des sphères
     * de ses quarks et des tubes de ses cordes, projetés à l'écran ; la paroi est F = ½, extraite par « marching squares » en
     * contours vectoriels. Une corde tendue étire le sac en tube ; quand elle casse, la paire naît dans le tube, et le col qui
     * relie les deux morceaux s'amincit à mesure qu'ils s'écartent, jusqu'à se pincer : un sac qui se divise, pas un objet qui
     * apparaît. Partie arrière : lueur de gaz, puis, dans le contour, le nuage de densité (mode fondamental du sac du MIT autour de
     * chaque quark) et la lueur dorée du tube.
     */
    Membrane membraneBack(List<Blob> blobs, List<Capsule> capsules, Color tint, double heat, double alpha) {
        List<Color> tints = new ArrayList<>();
        for (int i = 0; i < blobs.size(); i++) tints.add(tint);
        return membraneBack(blobs, tints, capsules, tint, heat, alpha);
    }

    /** Une membrane dont chaque quark teinte son propre nuage (deux morceaux qui se séparent gardent chacun sa couleur). */
    Membrane membraneBack(List<Blob> blobs, List<Color> tints, List<Capsule> capsules, Color tint, double heat, double alpha) {
        int[] bo = new int[blobs.size()], co = new int[capsules.size()];
        return membraneBack(blobs, tints, bo, capsules, co, new double[][]{{1}}, tint, heat, alpha);
    }

    /**
     * Une membrane commune à plusieurs hadrons qui se touchent (des « metaballs ») : chaque sphère et chaque tube appartient à un
     * hadron (`blobOwner`, `capOwner`) ; en un point, le champ est celui du hadron le plus présent, plus celui des autres pondéré par
     * `weight[a][b]` — 1 au contact (leurs halos fondent l'un dans l'autre, une seule masse), 0 quand ils se sont éloignés. Entre
     * les deux, le pont de matière qui les relie s'amincit à mesure qu'ils s'écartent, puis se rompt : les deux bulles se referment.
     */
    Membrane membraneBack(List<Blob> blobs, List<Color> tints, int[] blobOwner, List<Capsule> capsules, int[] capOwner, double[][] weight,
                          Color tint, double heat, double alpha) {
        if (blobs.isEmpty() || alpha <= .01) return null;
        final int owners = weight.length;
        int nb = blobs.size(), nc = capsules.size();
        double[] bx = new double[nb], by = new double[nb], br = new double[nb], bs = new double[nb];
        double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
        for (int i = 0; i < nb; i++) {
            Blob b = blobs.get(i);
            double[] p = project(b.p());
            if (p == null) return null;
            double pulse = 1 + b.pulse() * Math.sin(clock * (5 + 3 * b.pulse()) + b.phase());
            bx[i] = p[0];
            by[i] = p[1];
            bs[i] = b.radius() * pulse * p[3];                     // le rayon de la paroi d'un quark seul (px)
            br[i] = bs[i] / WYVILL;                                  // la portée du noyau (px)
            minX = Math.min(minX, bx[i] - br[i]);
            maxX = Math.max(maxX, bx[i] + br[i]);
            minY = Math.min(minY, by[i] - br[i]);
            maxY = Math.max(maxY, by[i] + br[i]);
        }
        double[] cax = new double[nc], cay = new double[nc], cbx = new double[nc], cby = new double[nc], cr = new double[nc];
        for (int i = 0; i < nc; i++) {
            Capsule c = capsules.get(i);
            double[] a = project(c.a()), b = project(c.b());
            if (a == null || b == null || c.radius() <= 0) continue;
            cax[i] = a[0];
            cay[i] = a[1];
            cbx[i] = b[0];
            cby[i] = b[1];
            cr[i] = c.radius() / WYVILL * (a[3] + b[3]) / 2;
            minX = Math.min(minX, Math.min(a[0], b[0]) - cr[i]);
            maxX = Math.max(maxX, Math.max(a[0], b[0]) + cr[i]);
            minY = Math.min(minY, Math.min(a[1], b[1]) - cr[i]);
            maxY = Math.max(maxY, Math.max(a[1], b[1]) + cr[i]);
        }
        minX = Math.max(left - 80, minX - 6);
        minY = Math.max(-80, minY - 6);
        maxX = Math.min(width + 80, maxX + 6);
        maxY = Math.min(height + 80, maxY + 6);
        if (maxX - minX < 2 || maxY - minY < 2) return null;
        double cell = Math.max(3, Math.max(maxX - minX, maxY - minY) / 80);    // la paroi est interpolée entre les nœuds
        int gw = (int) Math.ceil((maxX - minX) / cell) + 1, gh = (int) Math.ceil((maxY - minY) / cell) + 1;
        double[] f = new double[gw * gh];
        final double x0 = minX, y0 = minY;
        java.util.stream.IntStream.range(0, gh).parallel().forEach(j -> {
            double y = y0 + j * cell;
            double[] acc = new double[owners];
            for (int i = 0; i < gw; i++) {
                double x = x0 + i * cell;
                java.util.Arrays.fill(acc, 0);
                for (int k = 0; k < nb; k++) {
                    double dx = x - bx[k], dy = y - by[k], u2 = (dx * dx + dy * dy) / (br[k] * br[k]);
                    if (u2 < 1) { double q = 1 - u2; acc[blobOwner[k]] += q * q * q; }
                }
                for (int k = 0; k < nc; k++) {
                    if (cr[k] <= 0) continue;
                    double vx = cbx[k] - cax[k], vy = cby[k] - cay[k], len2 = vx * vx + vy * vy;
                    double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - cax[k]) * vx + (y - cay[k]) * vy) / len2));
                    double dx = x - (cax[k] + t * vx), dy = y - (cay[k] + t * vy), u2 = (dx * dx + dy * dy) / (cr[k] * cr[k]);
                    if (u2 < 1) { double q = 1 - u2; acc[capOwner[k]] += q * q * q; }
                }
                int top = 0;
                for (int o = 1; o < owners; o++) if (acc[o] > acc[top]) top = o;
                double sum = acc[top];
                for (int o = 0; o < owners; o++) if (o != top) sum += weight[top][o] * acc[o];
                f[j * gw + i] = sum / .5;                           // la paroi : 1
            }
        });
        List<double[][]> loops = contours(f, gw, gh, minX, minY, cell);
        if (loops.isEmpty()) return null;
        // la lueur de gaz, hors du verre (plus chaude s'il est excité)
        g.setGlobalBlendMode(BlendMode.ADD);
        for (int k = 0; k < nb; k++)
            glowAt(bx[k], by[k], bs[k] * 3.2, tints.get(k).interpolate(Color.web("#FF9A55"), heat * .6), (.1 + .22 * heat) * alpha / Math.sqrt(nb / 2.0));
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.save();
        path(loops);
        g.clip();
        g.setFill(tint.deriveColor(0, 1, .55, .16 * alpha));
        g.fillRect(minX, minY, maxX - minX, maxY - minY);
        // le nuage de densité autour de chaque quark : ρ ∝ j₀² + j₁² du mode fondamental du sac
        for (int k = 0; k < nb; k++) {
            double r = bs[k] * 1.9;
            Color cloud = tints.get(k).interpolate(Color.WHITE, .25);
            Stop[] stops = new Stop[7];
            for (int m = 0; m < 6; m++) {
                double u = m / 5.0;
                stops[m] = new Stop(u * .95, cloud.deriveColor(0, 1, 1, .34 * bagDensity(2.04 * u) * alpha));
            }
            stops[6] = new Stop(1, cloud.deriveColor(0, 1, 1, 0));
            g.setFill(new RadialGradient(0, 0, bx[k], by[k], r, false, CycleMethod.NO_CYCLE, stops));
            g.fillOval(bx[k] - r, by[k] - r, 2 * r, 2 * r);
        }
        if (heat > .05) {
            g.setGlobalBlendMode(BlendMode.ADD);
            for (int k = 0; k < nb; k++) glowAt(bx[k], by[k], bs[k] * 1.4, Color.web("#FF8A3A"), .22 * heat * alpha / Math.sqrt(nb));
            g.setGlobalBlendMode(BlendMode.SRC_OVER);
        }
        g.restore();
        return new Membrane(loops, tint, alpha);
    }

    /** La partie avant de la membrane : la paroi (liseré de Fresnel), une bande claire à l'intérieur du bord, deux reflets. */
    void membraneFront(Membrane m) {
        if (m == null) return;
        double a = m.alpha();
        Color tint = m.tint();
        g.save();
        path(m.loops());
        g.clip();
        g.setGlobalBlendMode(BlendMode.ADD);
        path(m.loops());
        g.setStroke(tint.interpolate(Color.WHITE, .45).deriveColor(0, 1, 1, .13 * a));
        g.setLineWidth(14);
        g.stroke();
        path(m.loops());
        g.setStroke(tint.interpolate(Color.WHITE, .55).deriveColor(0, 1, 1, .2 * a));
        g.setLineWidth(5);
        g.stroke();
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.restore();
        path(m.loops());
        g.setStroke(tint.interpolate(Color.WHITE, .65).deriveColor(0, 1, 1, .75 * a));
        g.setLineWidth(1.5);
        g.stroke();
        g.setGlobalBlendMode(BlendMode.ADD);
        for (double[][] loop : m.loops()) {
            double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
            for (int i = 0; i < loop[0].length; i++) {
                x0 = Math.min(x0, loop[0][i]); x1 = Math.max(x1, loop[0][i]);
                y0 = Math.min(y0, loop[1][i]); y1 = Math.max(y1, loop[1][i]);
            }
            double size = Math.min(x1 - x0, y1 - y0);
            if (size < 6) continue;
            glowAt(x0 + .3 * (x1 - x0), y0 + .27 * (y1 - y0), .22 * size, Color.WHITE, .32 * a);
            glowAt(x1 - .24 * (x1 - x0), y1 - .22 * (y1 - y0), .06 * size, Color.WHITE, .5 * a);
        }
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    private void path(List<double[][]> loops) {
        g.beginPath();
        for (double[][] loop : loops) {
            g.moveTo(loop[0][0], loop[1][0]);
            for (int i = 1; i < loop[0].length; i++) g.lineTo(loop[0][i], loop[1][i]);
            g.closePath();
        }
    }

    /**
     * Les contours f = 1 d'une grille (marching squares, interpolation linéaire sur les arêtes, cols tranchés par la valeur au
     * centre de la case), chaînés en boucles fermées.
     */
    static List<double[][]> contours(double[] f, int gw, int gh, double x0, double y0, double cell) {
        java.util.Map<Integer, int[]> links = new java.util.HashMap<>();
        java.util.Map<Integer, double[]> points = new java.util.HashMap<>();
        for (int j = 0; j < gh - 1; j++) {
            for (int i = 0; i < gw - 1; i++) {
                double v0 = f[j * gw + i], v1 = f[j * gw + i + 1], v2 = f[(j + 1) * gw + i + 1], v3 = f[(j + 1) * gw + i];
                int c = (v0 >= 1 ? 1 : 0) | (v1 >= 1 ? 2 : 0) | (v2 >= 1 ? 4 : 0) | (v3 >= 1 ? 8 : 0);
                if (c == 0 || c == 15) continue;
                int top = 2 * (j * gw + i), bottom = 2 * ((j + 1) * gw + i), left = 2 * (j * gw + i) + 1, right = 2 * (j * gw + i + 1) + 1;
                boolean centre = (v0 + v1 + v2 + v3) / 4 >= 1;
                int[][] segs = switch (c) {
                    case 1, 14 -> new int[][]{{left, top}};
                    case 2, 13 -> new int[][]{{top, right}};
                    case 3, 12 -> new int[][]{{left, right}};
                    case 4, 11 -> new int[][]{{right, bottom}};
                    case 6, 9 -> new int[][]{{top, bottom}};
                    case 7, 8 -> new int[][]{{left, bottom}};
                    case 5 -> centre ? new int[][]{{top, right}, {bottom, left}} : new int[][]{{left, top}, {right, bottom}};
                    case 10 -> centre ? new int[][]{{left, top}, {right, bottom}} : new int[][]{{top, right}, {bottom, left}};
                    default -> new int[0][];
                };
                for (int[] sg : segs) {
                    for (int e : sg) points.computeIfAbsent(e, k -> edgePoint(k, f, gw, x0, y0, cell));
                    link(links, sg[0], sg[1]);
                    link(links, sg[1], sg[0]);
                }
            }
        }
        List<double[][]> loops = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int start : links.keySet()) {
            if (seen.contains(start)) continue;
            List<double[]> chain = new ArrayList<>();
            int prev = -1, cur = start;
            while (cur >= 0 && seen.add(cur)) {
                chain.add(points.get(cur));
                int[] n = links.get(cur);
                int next = n[0] != prev && n[0] >= 0 ? n[0] : n[1];
                if (next == prev) next = -1;
                prev = cur;
                cur = next;
            }
            if (chain.size() < 3) continue;
            double[][] loop = new double[2][chain.size()];
            for (int k = 0; k < chain.size(); k++) {
                loop[0][k] = chain.get(k)[0];
                loop[1][k] = chain.get(k)[1];
            }
            loops.add(loop);
        }
        return loops;
    }

    private static void link(java.util.Map<Integer, int[]> links, int a, int b) {
        int[] n = links.computeIfAbsent(a, k -> new int[]{-1, -1});
        if (n[0] < 0) n[0] = b;
        else if (n[0] != b) n[1] = b;
    }

    private static double[] edgePoint(int key, double[] f, int gw, double x0, double y0, double cell) {
        int node = key / 2, i = node % gw, j = node / gw;
        boolean vertical = key % 2 == 1;
        double va = f[node], vb = vertical ? f[node + gw] : f[node + 1];
        double t = Math.abs(vb - va) < 1e-12 ? .5 : Math.max(0, Math.min(1, (1 - va) / (vb - va)));
        return vertical ? new double[]{x0 + i * cell, y0 + (j + t) * cell} : new double[]{x0 + (i + t) * cell, y0 + j * cell};
    }

    // ================================================================== la figure 3D : une boîte ouverte
    /** Les limites de la boîte (fm) : toute la zone où se passe l'événement. */
    record Box(double x0, double x1, double y0, double y1, double z0, double z1) {
        double[] centre() { return new double[]{(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2}; }

        double radius() { return .5 * Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0)); }

        boolean contains(double[] p, double margin) {
            return p[0] >= x0 - margin && p[0] <= x1 + margin && p[1] >= y0 - margin && p[1] <= y1 + margin && p[2] >= z0 - margin
                    && p[2] <= z1 + margin;
        }
    }

    /**
     * Les trois faces du fond, comme dans une figure 3D : pour chaque axe, la paroi du côté opposé à l'œil — on regarde dans une
     * boîte ouverte, et quand on la tourne, ce sont toujours les faces du fond qui portent la grille. Renvoie {x de la paroi
     * latérale, y du sol (ou du plafond si l'on regarde d'en dessous), z de la paroi du fond}.
     */
    double[] backPlanes(Box b) {
        double[] eye = camera.eye(), c = b.centre();
        return new double[]{eye[0] > c[0] ? b.x0() : b.x1(), eye[1] > c[1] ? b.y0() : b.y1(), eye[2] > c[2] ? b.z0() : b.z1()};
    }

    /** Un pas de graduation lisible (1, 2, 5 × 10ⁿ fm) pour qu'une longueur `span` ait environ `count` intervalles. */
    static double niceStep(double span, double count) {
        double raw = Math.max(1e-6, span / count), p = Math.pow(10, Math.floor(Math.log10(raw))), m = raw / p;
        return (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * p;
    }

    private void line3(double[] a, double[] b) {
        double[] pa = project(a), pb = project(b);
        if (pa != null && pb != null) g.strokeLine(pa[0], pa[1], pb[0], pb[1]);
    }

    private void quad(double[][] corners, Color fill) {
        double[] xs = new double[4], ys = new double[4];
        for (int k = 0; k < 4; k++) {
            double[] p = project(corners[k]);
            if (p == null) return;
            xs[k] = p[0];
            ys[k] = p[1];
        }
        g.setFill(fill);
        g.fillPolygon(xs, ys, 4);
    }

    /**
     * La boîte : le sol et les deux parois du fond, chacun avec sa grille (pas `step` fm), leurs arêtes, les graduations en fm sur
     * les arêtes de devant, le nom des axes et une flèche au bout de chacun. Tout est à l'échelle (même fm sur les trois axes).
     */
    void box(Box b, double step, double alpha) {
        double[] planes = backPlanes(b);
        double wx = planes[0], fy = planes[1], wz = planes[2];
        double fx = wx == b.x0() ? b.x1() : b.x0(), fz = wz == b.z0() ? b.z1() : b.z0(), top = fy == b.y0() ? b.y1() : b.y0();
        Color ink = Color.web("#C9D7EA");
        // les faces, à peine teintées
        quad(new double[][]{{b.x0(), fy, b.z0()}, {b.x1(), fy, b.z0()}, {b.x1(), fy, b.z1()}, {b.x0(), fy, b.z1()}}, ink.deriveColor(0, 1, 1, .05 * alpha));
        quad(new double[][]{{wx, b.y0(), b.z0()}, {wx, b.y1(), b.z0()}, {wx, b.y1(), b.z1()}, {wx, b.y0(), b.z1()}}, ink.deriveColor(0, 1, 1, .03 * alpha));
        quad(new double[][]{{b.x0(), b.y0(), wz}, {b.x1(), b.y0(), wz}, {b.x1(), b.y1(), wz}, {b.x0(), b.y1(), wz}}, ink.deriveColor(0, 1, 1, .03 * alpha));
        // les grilles
        g.setLineWidth(1);
        g.setLineCap(StrokeLineCap.BUTT);
        for (double v = Math.ceil(b.x0() / step) * step; v <= b.x1() + 1e-9; v += step) {
            g.setStroke(ink.deriveColor(0, 1, 1, (Math.abs(v) < 1e-9 ? .32 : .14) * alpha));
            line3(new double[]{v, fy, b.z0()}, new double[]{v, fy, b.z1()});
            line3(new double[]{v, b.y0(), wz}, new double[]{v, b.y1(), wz});
        }
        for (double v = Math.ceil(b.z0() / step) * step; v <= b.z1() + 1e-9; v += step) {
            g.setStroke(ink.deriveColor(0, 1, 1, (Math.abs(v) < 1e-9 ? .32 : .14) * alpha));
            line3(new double[]{b.x0(), fy, v}, new double[]{b.x1(), fy, v});
            line3(new double[]{wx, b.y0(), v}, new double[]{wx, b.y1(), v});
        }
        for (double v = Math.ceil(b.y0() / step) * step; v <= b.y1() + 1e-9; v += step) {
            g.setStroke(ink.deriveColor(0, 1, 1, (Math.abs(v) < 1e-9 ? .32 : .14) * alpha));
            line3(new double[]{wx, v, b.z0()}, new double[]{wx, v, b.z1()});
            line3(new double[]{b.x0(), v, wz}, new double[]{b.x1(), v, wz});
        }
        // les arêtes des trois faces ; celles où elles se rejoignent, plus marquées
        g.setStroke(ink.deriveColor(0, 1, 1, .32 * alpha));
        line3(new double[]{fx, fy, b.z0()}, new double[]{fx, fy, b.z1()});
        line3(new double[]{b.x0(), fy, fz}, new double[]{b.x1(), fy, fz});
        line3(new double[]{wx, top, b.z0()}, new double[]{wx, top, b.z1()});
        line3(new double[]{b.x0(), top, wz}, new double[]{b.x1(), top, wz});
        line3(new double[]{wx, b.y0(), fz}, new double[]{wx, b.y1(), fz});
        line3(new double[]{fx, b.y0(), wz}, new double[]{fx, b.y1(), wz});
        g.setStroke(ink.deriveColor(0, 1, 1, .5 * alpha));
        g.setLineWidth(1.3);
        line3(new double[]{wx, fy, b.z0()}, new double[]{wx, fy, b.z1()});
        line3(new double[]{b.x0(), fy, wz}, new double[]{b.x1(), fy, wz});
        line3(new double[]{wx, b.y0(), wz}, new double[]{wx, b.y1(), wz});
        // les graduations, sur les arêtes de devant : x le long de z = fz, z le long de x = fx, y le long de (fx, wz)
        double[] centre = b.centre();
        axis(new double[]{b.x0(), fy, fz}, new double[]{b.x1(), fy, fz}, 0, step, new double[]{0, 0, fz - centre[2]}, "x (fm)", alpha);
        axis(new double[]{fx, fy, b.z0()}, new double[]{fx, fy, b.z1()}, 2, step, new double[]{fx - centre[0], 0, 0}, "z (fm) · faisceau", alpha);
        axis(new double[]{fx, b.y0(), wz}, new double[]{fx, b.y1(), wz}, 1, step, new double[]{fx - centre[0], 0, 0}, "y (fm)", alpha);
    }

    /** Une arête graduée : un trait tous les `step` fm, un nombre tous les deux, le nom de l'axe, une flèche vers les valeurs croissantes. */
    private void axis(double[] from, double[] to, int k, double step, double[] outward, String name, double alpha) {
        double len = SubRender.norm(outward);
        double[] out = len < 1e-9 ? new double[]{0, 0, 0} : scale(outward, 1 / len);
        Color ink = Color.web("#AFC0D6");
        g.setFont(Font.font("Consolas", 10));
        g.setTextAlign(TextAlignment.CENTER);
        g.setLineWidth(1);
        int index = 0;
        for (double v = Math.ceil(from[k] / step) * step; v <= to[k] + 1e-9; v += step, index++) {
            double[] base = from.clone();
            base[k] = v;
            double[] a = project(base), tick = project(add(base, scale(out, step * .18))), label = project(add(base, scale(out, step * .55)));
            if (a == null || tick == null || label == null) continue;
            g.setStroke(ink.deriveColor(0, 1, 1, .55 * alpha));
            g.strokeLine(a[0], a[1], tick[0], tick[1]);
            boolean major = Math.abs(Math.round(v / step)) % 2 == 0;
            if (major) {
                g.setFill(ink.deriveColor(0, 1, 1, .75 * alpha));
                String text = Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v)) : String.format(I18n.locale(), "%.1f", v);
                I18n.fillText(g, text.replace('-', '−'), label[0], label[1] + 3.5);
            }
        }
        double[] mid = from.clone();
        mid[k] = (from[k] + to[k]) / 2;
        double[] title = project(add(mid, scale(out, step * 1.25)));
        if (title != null) {
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10.5));
            g.setFill(ink.deriveColor(0, 1, 1, .85 * alpha));
            I18n.fillText(g, name, title[0], title[1] + 3.5);
        }
        // la flèche, au bout des valeurs croissantes
        double[] tip = project(to), before = project(add(to, new double[]{k == 0 ? -step * .5 : 0, k == 1 ? -step * .5 : 0, k == 2 ? -step * .5 : 0}));
        if (tip != null && before != null) {
            double dx = tip[0] - before[0], dy = tip[1] - before[1], n = Math.hypot(dx, dy);
            if (n > 1e-6) {
                dx /= n;
                dy /= n;
                g.setStroke(ink.deriveColor(0, 1, 1, .7 * alpha));
                g.setLineWidth(1.3);
                g.strokeLine(tip[0], tip[1], tip[0] + dx * 9, tip[1] + dy * 9);
                g.strokeLine(tip[0] + dx * 9, tip[1] + dy * 9, tip[0] + dx * 3 - dy * 4, tip[1] + dy * 3 + dx * 4);
                g.strokeLine(tip[0] + dx * 9, tip[1] + dy * 9, tip[0] + dx * 3 + dy * 4, tip[1] + dy * 3 - dx * 4);
            }
        }
    }

    /** Une suite de points (fm) tracée en polyligne : les points hors de la caméra coupent le trait. */
    private void polyline(List<double[]> pts) {
        double[] xs = new double[pts.size()], ys = new double[pts.size()];
        int n = 0;
        for (double[] p : pts) {
            double[] s = project(p);
            if (s == null) {
                if (n > 1) g.strokePolyline(xs, ys, n);
                n = 0;
                continue;
            }
            xs[n] = s[0];
            ys[n] = s[1];
            n++;
        }
        if (n > 1) g.strokePolyline(xs, ys, n);
    }

    /**
     * Les projections d'une trajectoire sur les trois faces du fond (le sol et les deux parois) : son ombre vue d'en haut, de
     * côté et de face. Lire une position en 3D, c'est lire ces trois ombres sur les grilles.
     */
    void projections(List<double[]> path, Box b, Color c, double alpha) {
        if (path.size() < 2 || alpha <= .01) return;
        double[] planes = backPlanes(b);
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineWidth(1.2);
        for (int axis = 0; axis < 3; axis++) {
            List<double[]> flat = new ArrayList<>(path.size());
            for (double[] p : path) {
                double[] q = p.clone();
                q[axis] = planes[axis];
                flat.add(q);
            }
            g.setStroke(c.deriveColor(0, 1, 1, (axis == 1 ? .42 : .26) * alpha));
            polyline(flat);
        }
    }

    /** Une trajectoire tracée dans la boîte : un trait fin et lumineux. */
    void trace(List<double[]> path, Color c, double alpha, double width) {
        if (path.size() < 2 || alpha <= .01) return;
        g.setLineCap(StrokeLineCap.ROUND);
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setStroke(c.deriveColor(0, 1, 1, .16 * alpha));
        g.setLineWidth(width * 3.5);
        polyline(path);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setStroke(c.interpolate(Color.WHITE, .2).deriveColor(0, 1, 1, .9 * alpha));
        g.setLineWidth(width);
        polyline(path);
    }

    /** Les points du temps : la position à intervalles de temps égaux. Leur écart, c'est la vitesse (chronophotographie). */
    void timeDots(List<double[]> dots, Color c, double alpha) {
        for (double[] p : dots) {
            double[] s = project(p);
            if (s == null) continue;
            double r = Math.max(2.3, Math.min(4, .06 * s[3]));
            g.setFill(c.deriveColor(0, 1, 1, .95 * alpha));
            g.fillOval(s[0] - r, s[1] - r, 2 * r, 2 * r);
            g.setFill(Color.WHITE.deriveColor(0, 1, 1, .85 * alpha));
            g.fillOval(s[0] - r * .4, s[1] - r * .4, r * .8, r * .8);
        }
    }

    /** Le repère d'un objet : un trait pointillé jusqu'au sol de la boîte et une petite croix à son pied. */
    void marker(double[] p, Box b, Color c, double alpha) {
        if (alpha <= .01) return;
        double fy = backPlanes(b)[1];
        double[] a = project(p), foot = project(new double[]{p[0], fy, p[2]});
        if (a == null || foot == null) return;
        g.setStroke(c.deriveColor(0, 1, 1, .4 * alpha));
        g.setLineWidth(1);
        g.setLineDashes(2, 4);
        g.strokeLine(a[0], a[1], foot[0], foot[1]);
        g.setLineDashes((double[]) null);
        double arm = Math.max(3, Math.min(7, .15 * foot[3]));
        g.setStroke(c.deriveColor(0, 1, 1, .8 * alpha));
        g.setLineWidth(1.3);
        double[] e1 = project(new double[]{p[0] + arm / Math.max(1e-6, foot[3]), fy, p[2]}), e2 = project(new double[]{p[0], fy, p[2] + arm / Math.max(1e-6, foot[3])});
        if (e1 != null) g.strokeLine(2 * foot[0] - e1[0], 2 * foot[1] - e1[1], e1[0], e1[1]);
        if (e2 != null) g.strokeLine(2 * foot[0] - e2[0], 2 * foot[1] - e2[1], e2[0], e2[1]);
    }

    private static String decade(int k) {
        if (k == 0) return "1";
        String digits = "⁰¹²³⁴⁵⁶⁷⁸⁹";
        StringBuilder sup = new StringBuilder("10⁻");
        for (char ch : Integer.toString(k).toCharArray()) sup.append(digits.charAt(ch - '0'));
        return sup.toString();
    }

    /** Un cercle (fm) posé dans le plan horizontal y = centre[1]. */
    private List<double[]> flatCircle(double[] centre, double r, int n) {
        List<double[]> out = new ArrayList<>(n + 1);
        for (int k = 0; k <= n; k++) {
            double a = 2 * Math.PI * k / n;
            out.add(new double[]{centre[0] + r * Math.cos(a), centre[1], centre[2] + r * Math.sin(a)});
        }
        return out;
    }

    /**
     * Une loi angulaire en diagramme polaire, à plat sur le sol (le centre de la boîte reste vide) : r(θ) = R·g(θ) dans le plan
     * horizontal, θ compté depuis `axis` (posé à plat), des deux côtés (la loi ne dépend pas de l'azimut). g = densité relative,
     * ou 1 + log₁₀(densité)/décades en échelle logarithmique — alors un cercle par décade (1, 10⁻¹, 10⁻²…). Des rayons tous les 30°,
     * la direction de l'événement en trait plein.
     */
    void polarFloor(double[] centre, double[] axis, double[] thetaDeg, double[] density, boolean log, double span, double radiusFm,
                    double chosenDeg, int side, Color c, double alpha) {
        if (thetaDeg.length < 2 || alpha <= .01 || radiusFm <= 0) return;
        double n = Math.hypot(axis[0], axis[2]);
        double[] u = n < 1e-6 ? new double[]{0, 0, 1} : new double[]{axis[0] / n, 0, axis[2] / n}, sd = {u[2], 0, -u[0]};
        java.util.function.DoubleUnaryOperator shape = th -> {
            double d = interpolate(thetaDeg, density, Math.max(thetaDeg[0], Math.min(thetaDeg[thetaDeg.length - 1], th)));
            return log ? Math.max(0, 1 + Math.log10(Math.max(1e-12, d)) / Math.max(1, span)) : Math.max(0, d);
        };
        g.setLineCap(StrokeLineCap.ROUND);
        g.setFont(Font.font("Consolas", 9.5));
        g.setTextAlign(TextAlignment.CENTER);
        // les cercles de l'échelle
        int rings = log ? (int) Math.floor(span + 1e-9) : 4;
        for (int k = 0; k <= rings; k++) {
            double r = log ? radiusFm * (1 - k / span) : radiusFm * (1 - k / 4.0);
            if (r <= radiusFm * .04) continue;
            g.setStroke(c.deriveColor(0, 1, 1, (k == 0 ? .35 : .2) * alpha));
            g.setLineWidth(1);
            polyline(flatCircle(centre, r, 64));
            double[] at = project(add(centre, scale(sd, -r * side)));
            if (at != null && k < rings) {
                g.setFill(c.deriveColor(0, 1, 1, .7 * alpha));
                I18n.fillText(g, log ? decade(k) : (100 - 25 * k) + " %", at[0], at[1] - 3);
            }
        }
        // les rayons tous les 30°, des deux côtés
        for (int a = 0; a <= 180; a += 30) {
            double th = Math.toRadians(a);
            for (int sgn : new int[]{1, -1}) {
                double[] dir = add(scale(u, Math.cos(th)), scale(sd, sgn * Math.sin(th)));
                g.setStroke(c.deriveColor(0, 1, 1, .14 * alpha));
                line3(centre, add(centre, scale(dir, radiusFm)));
                if (sgn == side) {
                    double[] lab = project(add(centre, scale(dir, radiusFm * 1.12)));
                    if (lab != null) {
                        g.setFill(c.deriveColor(0, 1, 1, .75 * alpha));
                        I18n.fillText(g, a + "°", lab[0], lab[1] + 3);
                    }
                }
            }
        }
        // la loi
        List<double[]> curve = new ArrayList<>();
        for (int k = 0; k <= 90; k++) {
            double th = Math.toRadians(2 * k), r = radiusFm * shape.applyAsDouble(2 * k);
            curve.add(add(centre, scale(add(scale(u, Math.cos(th)), scale(sd, Math.sin(th))), r)));
        }
        for (int k = 90; k >= 0; k--) {
            double th = Math.toRadians(2 * k), r = radiusFm * shape.applyAsDouble(2 * k);
            curve.add(add(centre, scale(add(scale(u, Math.cos(th)), scale(sd, -Math.sin(th))), r)));
        }
        double[] xs = new double[curve.size()], ys = new double[curve.size()];
        int m = 0;
        for (double[] q : curve) {
            double[] sp = project(q);
            if (sp == null) continue;
            xs[m] = sp[0];
            ys[m] = sp[1];
            m++;
        }
        if (m > 2) {
            g.setFill(c.deriveColor(0, 1, 1, .13 * alpha));
            g.fillPolygon(xs, ys, m);
            g.setStroke(c.deriveColor(0, 1, 1, .9 * alpha));
            g.setLineWidth(1.6);
            g.strokePolygon(xs, ys, m);
        }
        // la direction de l'événement
        double th = Math.toRadians(chosenDeg), rc = radiusFm * shape.applyAsDouble(chosenDeg);
        double[] tip = add(centre, scale(add(scale(u, Math.cos(th)), scale(sd, side * Math.sin(th))), Math.max(rc, radiusFm * .08)));
        g.setStroke(Color.WHITE.deriveColor(0, 1, 1, .9 * alpha));
        g.setLineWidth(2.2);
        line3(centre, tip);
        double[] sp = project(tip);
        if (sp != null) {
            g.setFill(Color.WHITE.deriveColor(0, 1, 1, alpha));
            g.fillOval(sp[0] - 3, sp[1] - 3, 6, 6);
        }
        // le sens du faisceau
        double[] f0 = project(add(centre, scale(u, radiusFm * 1.02))), f1 = project(add(centre, scale(u, radiusFm * 1.3)));
        if (f0 != null && f1 != null) {
            g.setStroke(c.deriveColor(0, 1, 1, .8 * alpha));
            g.setLineWidth(1.3);
            g.strokeLine(f0[0], f0[1], f1[0], f1[1]);
            double dx = f1[0] - f0[0], dy = f1[1] - f0[1], len = Math.hypot(dx, dy);
            if (len > 1e-6) {
                dx /= len;
                dy /= len;
                g.strokeLine(f1[0], f1[1], f1[0] - dx * 6 - dy * 3.5, f1[1] - dy * 6 + dx * 3.5);
                g.strokeLine(f1[0], f1[1], f1[0] - dx * 6 + dy * 3.5, f1[1] - dy * 6 - dx * 3.5);
            }
        }
    }

    /**
     * Une comète : ce qui bouge, vu comme un volume qui file — une tête lumineuse (la particule) et, derrière elle, une traînée qui
     * s'amincit et pâlit sur `lengthFm` fm. L'épaisseur suit la perspective ; c'est l'effilement vers l'arrière qui dit d'où l'objet
     * vient et où il va, sans flèche. `path` va du plus ancien au plus récent (la tête).
     */
    void comet(List<double[]> path, Color c, double alpha, double widthFm, double lengthFm, double headFm) {
        if (path.size() < 2 || alpha <= .01) return;
        double[] head = path.get(path.size() - 1);
        List<double[]> tail = new ArrayList<>();
        List<Double> along = new ArrayList<>();
        double run = 0;
        tail.add(head);
        along.add(0.0);
        for (int i = path.size() - 2; i >= 0 && run < lengthFm; i--) {
            double seg = norm(sub(path.get(i + 1), path.get(i)));
            double[] p = path.get(i);
            if (run + seg > lengthFm && seg > 1e-9) p = lerp(path.get(i + 1), path.get(i), (lengthFm - run) / seg);
            run = Math.min(lengthFm, run + seg);
            tail.add(p);
            along.add(run);
        }
        g.setLineCap(StrokeLineCap.ROUND);
        for (int pass = 0; pass < 2; pass++) {
            g.setGlobalBlendMode(pass == 0 ? BlendMode.ADD : BlendMode.SRC_OVER);
            for (int k = tail.size() - 1; k > 0; k--) {
                double[] a = project(tail.get(k)), b = project(tail.get(k - 1));
                if (a == null || b == null) continue;
                double f = 1 - (along.get(k) + along.get(k - 1)) / 2 / Math.max(1e-9, lengthFm);
                double w = Math.max(1, Math.min(18, Math.max(3.2, 2 * widthFm * b[3]) * Math.pow(f, .85)));
                if (pass == 0) {
                    g.setStroke(c.deriveColor(0, 1, 1, .22 * alpha * f));
                    g.setLineWidth(w * 2.6);
                } else {
                    g.setStroke(c.interpolate(Color.WHITE, .35 * f).deriveColor(0, 1, 1, .85 * alpha * f * f));
                    g.setLineWidth(w);
                }
                g.strokeLine(a[0], a[1], b[0], b[1]);
            }
        }
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        double[] s = project(head);
        if (s == null) return;
        double r = Math.max(5, Math.min(26, headFm * s[3]));
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setFill(new RadialGradient(0, 0, s[0], s[1], r, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE.deriveColor(0, 1, 1, .9 * alpha)), new Stop(.25, c.deriveColor(0, 1, 1, .7 * alpha)),
                new Stop(1, c.deriveColor(0, 1, 1, 0))));
        g.fillOval(s[0] - r, s[1] - r, 2 * r, 2 * r);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    /** Un cercle lumineux sur le sol (fm) : le front d'une nouvelle qui s'éloigne à c. */
    void floorRing(double[] centre, double radiusFm, Color c, double alpha) {
        if (alpha <= .01 || radiusFm <= .05) return;
        g.setLineCap(StrokeLineCap.ROUND);
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setStroke(c.deriveColor(0, 1, 1, .25 * alpha));
        g.setLineWidth(4);
        polyline(flatCircle(centre, radiusFm, 72));
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setStroke(c.deriveColor(0, 1, 1, .85 * alpha));
        g.setLineWidth(1.3);
        polyline(flatCircle(centre, radiusFm, 72));
        g.setStroke(c.deriveColor(0, 1, 1, .35 * alpha));
        g.setLineWidth(1);
        polyline(flatCircle(centre, radiusFm * .94, 72));
    }

    /**
     * Le volume actif : un cube filaire néon qui enveloppe les hadrons (arêtes lumineuses, équerres aux coins, l'intérieur vide) ;
     * sa taille est écrite à l'un de ses coins.
     */
    void neonBox(double[] lo, double[] hi, Color c, double alpha, String label) {
        if (alpha <= .01) return;
        double[][] k = new double[8][];
        for (int i = 0; i < 8; i++) k[i] = new double[]{(i & 1) == 0 ? lo[0] : hi[0], (i & 2) == 0 ? lo[1] : hi[1], (i & 4) == 0 ? lo[2] : hi[2]};
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        g.setLineCap(StrokeLineCap.ROUND);
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setStroke(c.deriveColor(0, 1, 1, .16 * alpha));
        g.setLineWidth(5);
        for (int[] e : edges) line3(k[e[0]], k[e[1]]);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setStroke(c.deriveColor(0, 1, 1, .6 * alpha));
        g.setLineWidth(1.1);
        for (int[] e : edges) line3(k[e[0]], k[e[1]]);
        g.setStroke(c.interpolate(Color.WHITE, .35).deriveColor(0, 1, 1, .95 * alpha));
        g.setLineWidth(2.2);
        for (double[] corner : k) {
            for (int axis = 0; axis < 3; axis++) {
                double[] tip = corner.clone();
                double span = hi[axis] - lo[axis];
                tip[axis] += (corner[axis] == lo[axis] ? 1 : -1) * Math.min(.8, .18 * span);
                line3(corner, tip);
            }
        }
        // l'étiquette, au coin le plus haut à l'écran
        double best = 1e9;
        double[] at = null;
        for (double[] corner : k) {
            double[] sp = project(corner);
            if (sp != null && sp[1] < best) { best = sp[1]; at = sp; }
        }
        if (at != null && label != null) {
            g.setFont(Font.font("Consolas", 10));
            g.setTextAlign(TextAlignment.LEFT);
            g.setFill(c.deriveColor(0, 1, 1, .9 * alpha));
            I18n.fillText(g, label, at[0] + 6, at[1] - 6);
        }
    }

    /**
     * La trajectoire comme un tube en 3D : son épaisseur suit la perspective (plus large près de nous, plus fine au loin), un
     * corps teinté, un liseré clair du côté de la lumière (en haut à gauche) — un cylindre, pas un trait — qui s'efface vers
     * l'arrière ; la tête est la plus vive. Rayon : une épaisseur de dessin (le lepton est ponctuel), écrite comme telle.
     */
    void tube(List<double[]> path, Color c, double alpha, double radiusFm) {
        if (path.size() < 2 || alpha <= .01) return;
        double[] prev = project(path.get(0));
        g.setLineCap(StrokeLineCap.ROUND);
        int n = path.size();
        for (int i = 1; i < n; i++) {
            double[] s = project(path.get(i));
            if (prev != null && s != null) {
                double f = Math.pow(i / (double) (n - 1), 1.3), blur = defocus(s[2]);
                double w = Math.max(1.4, Math.min(14, 2 * radiusFm * (prev[3] + s[3]) / 2));
                double a = alpha * f * (1 - .5 * blur);
                g.setGlobalBlendMode(BlendMode.ADD);
                g.setStroke(c.deriveColor(0, 1, 1, .12 * a));
                g.setLineWidth(w * 3.2);
                g.strokeLine(prev[0], prev[1], s[0], s[1]);
                g.setGlobalBlendMode(BlendMode.SRC_OVER);
                g.setStroke(c.deriveColor(0, 1, .7, .85 * a));
                g.setLineWidth(w);
                g.strokeLine(prev[0], prev[1], s[0], s[1]);
                double dx = s[0] - prev[0], dy = s[1] - prev[1], len = Math.hypot(dx, dy);
                if (len > 1e-6) {
                    double nx = -dy / len, ny = dx / len;
                    if (nx + ny > 0) { nx = -nx; ny = -ny; }                    // du côté de la lumière (en haut à gauche)
                    double o = w * .22;
                    g.setStroke(c.interpolate(Color.WHITE, .6).deriveColor(0, 1, 1, .9 * a));
                    g.setLineWidth(Math.max(.8, w * .3));
                    g.strokeLine(prev[0] + nx * o, prev[1] + ny * o, s[0] + nx * o, s[1] + ny * o);
                }
            }
            prev = s;
        }
    }

    /** x^140 par élévations au carré (x^128 · x^8 · x^4) : le reflet spéculaire, sans Math.pow. */
    private static double pow140(double x) {
        double x2 = x * x, x4 = x2 * x2, x8 = x4 * x4, x16 = x8 * x8, x32 = x16 * x16, x64 = x32 * x32;
        return x64 * x64 * x8 * x4;
    }

    private static double bulge(double s) {
        return s <= 1 ? 0 : Math.sqrt(1 - Math.exp(-(s - 1)));
    }

    /** Le reflet d'une bulle : un arc clair en haut à gauche, comme sur le verre des images. */
    void highlight(double[] centre, double radiusFm, double alpha) {
        double[] s = project(centre);
        if (s == null || alpha <= .01) return;
        double r = radiusFm * s[3];
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setStroke(Color.color(1, 1, 1, .32 * alpha));
        g.setLineWidth(Math.max(1.5, r * .045));
        g.strokeArc(s[0] - r * .8, s[1] - r * .8, 1.6 * r, 1.6 * r, 112, 46, javafx.scene.shape.ArcType.OPEN);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    // ================================================================== les étiquettes
    /** Une étiquette accrochée à un point de la scène (fm) ; elle sera posée sans chevaucher les autres. */
    void tag(double[] anchor, double radiusFm, String title, String detail, Color accent, double alpha) {
        double[] s = project(anchor);
        if (s == null || alpha <= .02) return;
        tags.add(new Tag(s[0], s[1], radiusFm * s[3], title, detail, accent, alpha, anchor.clone()));
    }

    /** Pose les étiquettes : à droite de leur objet si possible, sinon au-dessus, à gauche, en dessous ; jamais l'une sur l'autre. */
    void drawTags() {
        if (flags) {
            drawFlags();
            return;
        }
        Font bold = Font.font("Segoe UI", FontWeight.BOLD, 12), light = Font.font("Segoe UI", 11);
        for (Tag t : tags) {
            double tw = ReactorView.width(t.title(), bold) + (t.detail().isEmpty() ? 0 : ReactorView.width(t.detail(), light) + 14) + 22;
            double th = 24;
            double[][] spots = {{t.x() + t.r() + 14, t.y() - th / 2}, {t.x() - tw / 2, t.y() - t.r() - th - 12},
                    {t.x() - t.r() - 14 - tw, t.y() - th / 2}, {t.x() - tw / 2, t.y() + t.r() + 12}};
            double[] spot = null;
            Integer previous = lastSpot.get(t.title());
            for (int k = -1; k < spots.length && spot == null; k++) {
                int index = k < 0 ? (previous == null ? -1 : previous) : k;
                if (index < 0) continue;
                double[] c = spots[index];
                if (c[0] < left + 4 || c[0] + tw > width - 4 || c[1] < 4 || c[1] + th > height - 4) continue;
                boolean clash = false;
                for (double[] o : taken) if (c[0] < o[0] + o[2] && c[0] + tw > o[0] && c[1] < o[1] + o[3] && c[1] + th > o[1]) { clash = true; break; }
                if (!clash) {
                    spot = c;
                    lastSpot.put(t.title(), index);
                }
            }
            if (spot == null) continue;
            taken.add(new double[]{spot[0], spot[1], tw, th});
            double lx = Math.max(spot[0], Math.min(spot[0] + tw, t.x())), ly = spot[1] + th / 2;
            g.setStroke(t.accent().deriveColor(0, 1, 1, .55 * t.alpha()));
            g.setLineWidth(1);
            if (Math.hypot(lx - t.x(), ly - t.y()) > t.r() + 4) g.strokeLine(t.x(), t.y(), lx, ly);
            g.setFill(Color.color(.03, .06, .11, .78 * t.alpha()));
            g.fillRoundRect(spot[0], spot[1], tw, th, th, th);
            g.setStroke(t.accent().deriveColor(0, 1, 1, .42 * t.alpha()));
            g.strokeRoundRect(spot[0] + .5, spot[1] + .5, tw - 1, th - 1, th, th);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(bold);
            g.setFill(Color.web("#EEF2FF").deriveColor(0, 1, 1, t.alpha()));
            I18n.fillText(g, t.title(), spot[0] + 11, spot[1] + 16.5);
            if (!t.detail().isEmpty()) {
                g.setFont(light);
                g.setFill(Color.web("#A5B0C6").deriveColor(0, 1, 1, t.alpha()));
                I18n.fillText(g, t.detail(), spot[0] + 11 + ReactorView.width(t.title(), bold) + 12, spot[1] + 16.5);
            }
        }
    }

    /**
     * Les panneaux : chaque étiquette monte sur un poteau vertical (en 3D) depuis son objet jusqu'au haut du volume actif, et s'y
     * pose en petit panneau lumineux (barre de couleur, titre, une ligne de mesures). Les panneaux voisins s'empilent ou passent
     * de l'autre côté du poteau ; aucun ne recouvre un autre, ni l'en-tête, ni le tableau de bord.
     */
    private void drawFlags() {
        Font bold = Font.font("Segoe UI", FontWeight.BOLD, 11.5), light = Font.font("Segoe UI", 10.5);
        List<Tag> list = new ArrayList<>(tags);
        list.sort((a, b) -> Double.compare(a.x(), b.x()));
        for (Tag t : list) {
            if (t.p() == null) continue;
            double topY = Double.isNaN(flagTop) ? t.p()[1] + flagRise : Math.max(t.p()[1] + .5, flagTop);
            double[] base = project(t.p()), top = project(new double[]{t.p()[0], topY, t.p()[2]});
            if (base == null || top == null) continue;
            double tw = Math.max(ReactorView.width(t.title(), bold), t.detail().isEmpty() ? 0 : ReactorView.width(t.detail(), light)) + 20;
            double th = t.detail().isEmpty() ? 21 : 35;
            double[] spot = null;
            int chosen = -1;
            Integer previous = lastSpot.get("⚑" + t.title());
            for (int k = -1; k < 14 && spot == null; k++) {
                int index = k < 0 ? (previous == null ? -1 : previous) : k;
                if (index < 0) continue;
                int level = index / 2;
                boolean leftSide = index % 2 == 1;
                double x = leftSide ? top[0] - tw - 1 : top[0] + 1, y = top[1] - th - 3 - level * (th + 4);
                if (x < left + 4 || x + tw > width - 4 || y < 4 || y + th > height - 4) continue;
                boolean clash = false;
                for (double[] o : taken) if (x < o[0] + o[2] && x + tw > o[0] && y < o[1] + o[3] && y + th > o[1]) { clash = true; break; }
                if (!clash) {
                    spot = new double[]{x, y};
                    chosen = index;
                }
            }
            if (spot == null) continue;
            lastSpot.put("⚑" + t.title(), chosen);
            taken.add(new double[]{spot[0], spot[1], tw, th});
            double a = t.alpha();
            // le poteau : vertical dans l'espace, puis tout droit jusqu'au panneau
            g.setStroke(t.accent().deriveColor(0, 1, 1, .6 * a));
            g.setLineWidth(1.1);
            g.strokeLine(base[0], base[1], top[0], top[1]);
            g.strokeLine(top[0], top[1], top[0], spot[1] + th);
            g.setFill(t.accent().deriveColor(0, 1, 1, .9 * a));
            g.fillOval(base[0] - 2.2, base[1] - 2.2, 4.4, 4.4);
            // le panneau
            g.setFill(Color.color(.02, .05, .1, .86 * a));
            g.fillRect(spot[0], spot[1], tw, th);
            g.setStroke(t.accent().deriveColor(0, 1, 1, .7 * a));
            g.setLineWidth(1);
            g.strokeRect(spot[0] + .5, spot[1] + .5, tw - 1, th - 1);
            g.setFill(t.accent().deriveColor(0, 1, 1, .95 * a));
            g.fillRect(spot[0], spot[1], 3.5, th);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(bold);
            g.setFill(Color.web("#EEF2FF").deriveColor(0, 1, 1, a));
            I18n.fillText(g, t.title(), spot[0] + 10, spot[1] + 14.5);
            if (!t.detail().isEmpty()) {
                g.setFont(light);
                g.setFill(Color.web("#A9B8D0").deriveColor(0, 1, 1, a));
                I18n.fillText(g, t.detail(), spot[0] + 10, spot[1] + 28.5);
            }
        }
    }

    /** Une zone déjà occupée à l'écran (sous-titre, pastilles…) : les étiquettes l'éviteront. */
    void reserve(double x, double y, double w, double h) {
        taken.add(new double[]{x, y, w, h});
    }

    // ================================================================== l'algèbre
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }

    static double[] scale(double[] a, double k) { return new double[]{a[0] * k, a[1] * k, a[2] * k}; }

    static double norm(double[] a) { return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] perpendicular(double[] u) {
        double[] t = Math.abs(u[1]) < .9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
        double[] n = cross(u, t);
        return scale(n, 1 / norm(n));
    }

    static double[] lerp(double[] a, double[] b, double f) {
        return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
    }
}
