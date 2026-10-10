package org.genesis;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Le tableau de bord de la collision : un instrument, pas une vitrine. Une colonne à plat, des filets fins, une seule
 * hiérarchie — le nombre qui compte en grand, le reste en lignes régulières :
 * <ul>
 * <li>le partage de l'énergie (barre segmentée, légende qui passe à la ligne) ;</li>
 * <li>la mesure principale, en grand, sur une jauge graduée ;</li>
 * <li>les autres mesures, une ligne chacune, avec leur jauge fine — la couleur dit le niveau : turquoise au repos,
 *     ambre en charge, corail à saturation ;</li>
 * <li>en lecture complète : la courbe du potentiel V(r) avec l'énergie du tir et la position actuelle (survol : la
 *     valeur à la distance pointée), puis le tableau des lois ;</li>
 * <li>les équivalences, pour que n'importe qui situe l'énergie (TNT, Hiroshima, une ville…).</li>
 * </ul>
 * Chaque jauge glisse vers sa valeur (lissage exponentiel de 0,15 s) ; les chiffres, eux, sont exacts.
 */
final class FusionDashboard {
    /** Une mesure : clé (pour le lissage), titre, valeur, unité, remplissage 0–1. */
    record Dial(String key, String label, String value, String unit, double fill) {}

    /** Une barre : titre, valeur, remplissage 0–1, repère (−1 : aucun) et sa lettre. */
    record Bar(String key, String label, String value, double fill, double mark, String markLabel) {}

    /** Une énergie partagée entre plusieurs formes (élan, tension, chaleur…). */
    record Split(String label, String total, List<Part> parts) {}

    record Part(String name, String value, double share, Color color) {}

    /**
     * Une courbe y(x) : points, l'énergie du tir (trait horizontal), la position actuelle, les bornes et les unités. Les
     * points hors des bornes verticales sont écrêtés (le puits nucléaire descend à −40 MeV, la barrière monte à 0,2).
     */
    record Plot(String title, double[] xs, double[] ys, double level, String levelLabel, double markerX, double markerY,
                double xmin, double xmax, double ymin, double ymax, String xUnit, String yUnit) {}

    /**
     * Ce que montre le tableau à un instant : l'étape, le partage de l'énergie, les mesures, les barres, les lois (en
     * tableau), une courbe, et des équivalences (valeur, phrase).
     */
    record Board(String stage, Split split, List<Dial> dials, List<Bar> bars, List<String> formulas, Plot plot,
                 List<String[]> equivalences) {
        Board(String stage, Split split, List<Dial> dials, List<Bar> bars, List<String> formulas) {
            this(stage, split, dials, bars, formulas, null, List.of());
        }
    }

    static final double WIDTH = 288;
    private static final double PAD = 16, HEAD = 34, HERO = 62, ROW = 29, PLOT = 142, FACT = 17.5, SECTION = 24;
    private static final Color INK = Color.web("#E8EEF7"), LABEL = Color.web("#8896AB"), DIM = Color.web("#5F6C80");
    private static final Color TEAL = Color.web("#3DD6C6"), AMBER = Color.web("#F5B84B"), CORAL = Color.web("#FF7A6B");
    private final Map<String, Double> shown = new HashMap<>();
    private double hoverX = -1, hoverY = -1;
    /** Le style documentaire (verre bleu) des écrans subatomiques, à la place du verre fumé du laboratoire de fusion. */
    boolean documentary;

    /** La couleur d'un niveau 0–1 : turquoise → ambre → corail. */
    static Color level(double f) {
        double t = Math.max(0, Math.min(1, f));
        return t < .6 ? TEAL.interpolate(AMBER, t / .6) : AMBER.interpolate(CORAL, (t - .6) / .4);
    }

    /** Des capitales espacées, à la manière des légendes de photos documentaires. */
    static String spaced(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c);
            if (i < text.length() - 1) out.append(c == ' ' ? "  " : " ");
        }
        return out.toString();
    }

    /** Un panneau plat : un fond sombre presque opaque, un filet fin ; `accent` colore un trait court sur le bord gauche. */
    static void glass(GraphicsContext gc, double x, double y, double w, double h, Color accent) {
        gc.setFill(Color.color(.039, .055, .078, .9));
        gc.fillRoundRect(x, y, w, h, 12, 12);
        gc.setLineWidth(1);
        gc.setStroke(Color.color(1, 1, 1, .09));
        gc.strokeRoundRect(x + .5, y + .5, w - 1, h - 1, 12, 12);
        if (accent != null) {
            gc.setStroke(accent.deriveColor(0, 1, 1, .9));
            gc.setLineWidth(2);
            gc.strokeLine(x + 1.5, y + 12, x + 1.5, y + h - 12);
        }
    }

    /** Le survol : où se trouve la souris (px de la fenêtre) ; −1 hors de la scène. */
    void hover(double x, double y) {
        hoverX = x;
        hoverY = y;
    }

    private double smooth(String key, double target, double dt) {
        target = Math.max(0, Math.min(1, Double.isFinite(target) ? target : 0));
        Double now = shown.get(key);
        double next = now == null ? target : dt <= 0 ? now : now + (target - now) * (1 - Math.exp(-dt / .15));
        shown.put(key, next);
        return next;
    }

    // ================================================================== la mesure (même gabarit que le dessin)
    /** La légende du partage, disposée en lignes : (colonne, ligne) de chaque part, et le nombre de lignes. */
    private static int legendLines(Split s, double width, double[][] at) {
        Font font = Font.font("Segoe UI", 10);
        double x = 0;
        int line = 0;
        for (int i = 0; i < s.parts().size(); i++) {
            Part p = s.parts().get(i);
            double w = 12 + ReactorView.width(p.name() + "  " + p.value(), font) + 14;
            if (x > 0 && x + w > width) {
                x = 0;
                line++;
            }
            if (at != null) {
                at[i][0] = x;
                at[i][1] = line;
            }
            x += w;
        }
        return line + 1;
    }

    private static double splitHeight(Split s) {
        return 36 + 15 * legendLines(s, WIDTH - 2 * PAD, null) + 6;
    }

    /** La hauteur que prendra le tableau. `compact` : sans la courbe ni le tableau des lois. */
    static double height(Board b, boolean compact) {
        double h = HEAD;
        if (b.split() != null) h += splitHeight(b.split());
        if (!b.dials().isEmpty()) h += HERO + ROW * (b.dials().size() - 1);
        h += ROW * b.bars().size();
        if (!compact && b.plot() != null) h += PLOT + 6;
        if (!compact && !b.formulas().isEmpty()) h += SECTION + FACT * Math.min(5, b.formulas().size());
        if (!b.equivalences().isEmpty()) h += SECTION + FACT * Math.min(4, b.equivalences().size());
        return h + 12;
    }

    // ================================================================== le dessin
    /** Dessine le tableau en (x, y) ; `dt` : le temps écoulé depuis l'image précédente (lissage des jauges). */
    void draw(GraphicsContext gc, double x, double y, Board b, double dt, boolean compact) {
        double w = WIDTH, inner = w - 2 * PAD, h = height(b, compact);
        gc.save();
        gc.setLineCap(StrokeLineCap.BUTT);
        if (documentary) DocStyle.panel(gc, x, y, w, h);
        else glass(gc, x, y, w, h, null);
        gc.setTextAlign(TextAlignment.LEFT);
        // L'étape : à gauche en capitales fines, un point à droite.
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(LABEL);
        gc.fillText(spaced(b.stage()), x + PAD, y + 21);
        gc.setFill(TEAL);
        gc.fillOval(x + w - PAD - 5, y + 16, 5, 5);
        rule(gc, x + PAD, y + HEAD - 4, inner);
        double row = y + HEAD;
        if (b.split() != null) row += split(gc, x + PAD, row, inner, b.split(), dt);
        if (!b.dials().isEmpty()) {
            hero(gc, x + PAD, row, inner, b.dials().get(0), dt);
            row += HERO;
            for (int i = 1; i < b.dials().size(); i++) {
                Dial d = b.dials().get(i);
                metric(gc, x + PAD, row, inner, d.key(), d.label(), d.value() + (d.unit().isBlank() ? "" : " " + d.unit()), d.fill(), -1, "", dt);
                row += ROW;
            }
        }
        for (Bar bar : b.bars()) {
            metric(gc, x + PAD, row, inner, bar.key(), bar.label(), bar.value(), bar.fill(), bar.mark(), bar.markLabel(), dt);
            row += ROW;
        }
        if (!compact && b.plot() != null) {
            row += 6;
            plot(gc, x + PAD, row, inner, b.plot());
            row += PLOT;
        }
        if (!compact && !b.formulas().isEmpty()) {
            row += 4;
            heading(gc, x + PAD, row, inner, "LOIS EN JEU");
            row += SECTION - 4;
            int shownRows = Math.min(5, b.formulas().size());
            for (int i = 0; i < shownRows; i++) {
                String f = b.formulas().get(i);
                int cut = f.lastIndexOf(" = ");
                fact(gc, x + PAD, row, inner, i, cut > 0 ? f.substring(0, cut) : f, cut > 0 ? f.substring(cut + 3) : "", false);
                row += FACT;
            }
        }
        if (!b.equivalences().isEmpty()) {
            row += 4;
            heading(gc, x + PAD, row, inner, "ÉQUIVALENCES");
            row += SECTION - 4;
            int shownRows = Math.min(4, b.equivalences().size());
            for (int i = 0; i < shownRows; i++) {
                String[] e = b.equivalences().get(i);
                fact(gc, x + PAD, row, inner, i, e[0], e[1], true);
                row += FACT;
            }
        }
        gc.restore();
    }

    /** Un filet fin, qui s'efface vers la droite. */
    private static void rule(GraphicsContext gc, double x, double y, double w) {
        gc.setLineWidth(1);
        gc.setStroke(new LinearGradient(x, 0, x + w, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(1, 1, 1, .12)), new Stop(1, Color.color(1, 1, 1, .02))));
        gc.strokeLine(x, y + .5, x + w, y + .5);
    }

    private static void heading(GraphicsContext gc, double x, double y, double w, String text) {
        rule(gc, x, y, w);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(LABEL);
        gc.fillText(spaced(text), x, y + 17);
    }

    /** Une ligne de tableau : un libellé à gauche, sa valeur à droite ; les lignes paires sont à peine teintées. */
    private static void fact(GraphicsContext gc, double x, double y, double w, int index, String key, String value, boolean accent) {
        if (index % 2 == 0) {
            gc.setFill(Color.color(1, 1, 1, .028));
            gc.fillRect(x - 6, y - 1, w + 12, FACT - 1);
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI", 10.5));
        gc.setFill(LABEL);
        gc.fillText(key, x, y + 11.5, value.isEmpty() ? w : w * .56);
        if (!value.isEmpty()) {
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setFont(Font.font("Segoe UI Semibold", 10.5));
            gc.setFill(accent ? AMBER.interpolate(Color.WHITE, .25) : INK);
            gc.fillText(value, x + w, y + 11.5, w * .5);
        }
    }

    /** Le partage de l'énergie : le total, une barre segmentée, et la légende. Renvoie la hauteur occupée. */
    private double split(GraphicsContext gc, double x, double y, double w, Split s, double dt) {
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(LABEL);
        gc.fillText(spaced(s.label()), x, y + 13);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(Font.font("Segoe UI Semibold", 14));
        gc.setFill(INK);
        gc.fillText(s.total(), x + w, y + 14);
        double by = y + 22, bh = 7, sum = 0;
        for (Part p : s.parts()) sum += Math.max(0, p.share());
        gc.setFill(Color.color(1, 1, 1, .06));
        gc.fillRoundRect(x, by, w, bh, 2, 2);
        double at = x, gap = 2;
        for (Part p : s.parts()) {
            double share = sum > 0 ? smooth("split:" + s.label() + ":" + p.name(), Math.max(0, p.share()) / sum, dt) : 0;
            double len = (w - gap * Math.max(0, s.parts().size() - 1)) * share;
            if (len < .5) continue;
            gc.setFill(p.color());
            gc.fillRoundRect(at, by, len, bh, 2, 2);
            at += len + gap;
        }
        double[][] pos = new double[s.parts().size()][2];
        int lines = legendLines(s, w, pos);
        gc.setFont(Font.font("Segoe UI", 10));
        gc.setTextAlign(TextAlignment.LEFT);
        for (int i = 0; i < s.parts().size(); i++) {
            Part p = s.parts().get(i);
            double lx = x + pos[i][0], ly = by + bh + 15 + 15 * pos[i][1];
            gc.setFill(p.color());
            gc.fillOval(lx, ly - 7, 6, 6);
            gc.setFill(LABEL);
            gc.fillText(p.name() + "  ", lx + 11, ly);
            gc.setFill(INK);
            gc.fillText(p.value(), lx + 11 + ReactorView.width(p.name() + "  ", Font.font("Segoe UI", 10)), ly);
        }
        return 36 + 15 * lines + 6;
    }

    /** La mesure principale : son titre, sa valeur en grand, et une jauge graduée dessous. */
    private void hero(GraphicsContext gc, double x, double y, double w, Dial d, double dt) {
        double f = smooth("hero:" + d.key(), d.fill(), dt);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI", 10.5));
        gc.setFill(LABEL);
        gc.fillText(d.label(), x, y + 13);
        Font big = Font.font("Segoe UI Semibold", 27);
        gc.setFont(big);
        gc.setFill(INK);
        gc.fillText(d.value(), x, y + 41, w * .72);
        if (!d.unit().isBlank()) {
            gc.setFont(Font.font("Segoe UI", 11));
            gc.setFill(LABEL);
            gc.fillText(d.unit(), x + Math.min(w * .72, ReactorView.width(d.value(), big)) + 6, y + 41);
        }
        gauge(gc, x, y + 50, w, 4, f, true);
    }

    /** Une mesure ordinaire : titre à gauche, valeur à droite, jauge fine dessous. */
    private void metric(GraphicsContext gc, double x, double y, double w, String key, String label, String value, double fill,
                        double mark, String markLabel, double dt) {
        double f = smooth("metric:" + key, fill, dt);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI", 10.5));
        gc.setFill(LABEL);
        gc.fillText(label, x, y + 11, w * .6);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(Font.font("Segoe UI Semibold", 11.5));
        gc.setFill(INK);
        gc.fillText(value, x + w, y + 11, w * .5);
        gauge(gc, x, y + 17, w, 2.5, f, false);
        if (mark >= 0 && mark <= 1) {
            double mx = x + w * mark;
            gc.setFill(INK);
            gc.fillRect(mx - .75, y + 14, 1.5, 8.5);
            if (markLabel != null && !markLabel.isEmpty()) {
                gc.setFont(Font.font("Segoe UI", 8.5));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setFill(DIM);
                gc.fillText(markLabel, Math.max(x + 6, Math.min(x + w - 6, mx)), y + 28);
            }
        }
    }

    /** Une jauge : une piste sombre, un remplissage dont la couleur suit le niveau, et (grande) des graduations. */
    private static void gauge(GraphicsContext gc, double x, double y, double w, double h, double f, boolean ticks) {
        gc.setFill(Color.color(1, 1, 1, .07));
        gc.fillRect(x, y, w, h);
        double len = Math.max(0, Math.min(1, f)) * w;
        if (len > .5) {
            gc.setFill(new LinearGradient(x, 0, x + w, 0, false, CycleMethod.NO_CYCLE,
                    new Stop(0, TEAL), new Stop(.6, AMBER), new Stop(1, CORAL)));
            gc.fillRect(x, y, len, h);
        }
        if (ticks) {
            gc.setStroke(Color.color(1, 1, 1, .22));
            gc.setLineWidth(1);
            for (int k = 0; k <= 4; k++) {
                double tx = x + w * k / 4.0;
                gc.strokeLine(tx, y + h, tx, y + h + (k % 2 == 0 ? 4 : 2.5));
            }
        }
    }

    // ================================================================== la courbe
    /**
     * La courbe y(x) dans un cadre de 142 px : l'axe zéro en pointillé, la courbe en turquoise, le trait d'énergie en
     * ambre, le point de la position actuelle. Au survol : un repère vertical, et la valeur de la courbe à cet endroit.
     */
    private void plot(GraphicsContext gc, double x, double y, double w, Plot p) {
        double top = y + 20, height = PLOT - 40, left = x, right = x + w;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(LABEL);
        gc.fillText(spaced(p.title()), x, y + 11);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(Font.font("Segoe UI", 9));
        gc.setFill(DIM);
        gc.fillText("survol : lire la valeur", right, y + 11);
        gc.setFill(Color.color(1, 1, 1, .03));
        gc.fillRect(left, top, w, height);
        double xr = Math.max(1e-12, p.xmax() - p.xmin()), yr = Math.max(1e-12, p.ymax() - p.ymin());
        if (p.ymin() < 0 && p.ymax() > 0) {
            double zy = top + height * (1 - (0 - p.ymin()) / yr);
            gc.setStroke(Color.color(1, 1, 1, .16));
            gc.setLineWidth(1);
            gc.setLineDashes(2, 3);
            gc.strokeLine(left, zy, right, zy);
            gc.setLineDashes(null);
        }
        double ey = top + height * (1 - (p.level() - p.ymin()) / yr);
        if (ey >= top && ey <= top + height) {
            gc.setStroke(AMBER.deriveColor(0, 1, 1, .85));
            gc.setLineWidth(1);
            gc.setLineDashes(5, 3);
            gc.strokeLine(left, ey, right, ey);
            gc.setLineDashes(null);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFont(Font.font("Segoe UI Semibold", 9));
            gc.setFill(AMBER);
            gc.fillText(p.levelLabel(), left + 4, ey - 4);
        }
        int n = p.xs().length;
        double[] px = new double[n], py = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = left + w * (p.xs()[i] - p.xmin()) / xr;
            py[i] = top + height * (1 - (Math.max(p.ymin(), Math.min(p.ymax(), p.ys()[i])) - p.ymin()) / yr);
        }
        gc.setStroke(TEAL);
        gc.setLineWidth(1.7);
        gc.strokePolyline(px, py, n);
        double mx = left + w * Math.max(0, Math.min(1, (p.markerX() - p.xmin()) / xr));
        double my = top + height * (1 - (Math.max(p.ymin(), Math.min(p.ymax(), p.markerY())) - p.ymin()) / yr);
        gc.setStroke(Color.color(1, 1, 1, .35));
        gc.setLineWidth(1);
        gc.strokeLine(mx, top, mx, top + height);
        gc.setFill(INK);
        gc.fillOval(mx - 3.5, my - 3.5, 7, 7);
        gc.setStroke(TEAL);
        gc.setLineWidth(1.3);
        gc.strokeOval(mx - 5.5, my - 5.5, 11, 11);
        gc.setFont(Font.font("Segoe UI", 9));
        gc.setFill(DIM);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(ReactorView.fmt(p.xmin()) + " " + p.xUnit(), left, top + height + 12);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(ReactorView.fmt(p.xmax()) + " " + p.xUnit(), right, top + height + 12);
        if (hoverX >= left && hoverX <= right && hoverY >= top - 6 && hoverY <= top + height + 6) {
            double xv = p.xmin() + xr * (hoverX - left) / w, yv = interpolate(p, xv);
            double hy = top + height * (1 - (Math.max(p.ymin(), Math.min(p.ymax(), yv)) - p.ymin()) / yr);
            gc.setStroke(Color.color(1, 1, 1, .5));
            gc.setLineWidth(1);
            gc.strokeLine(hoverX, top, hoverX, top + height);
            gc.setFill(TEAL);
            gc.fillOval(hoverX - 3, hy - 3, 6, 6);
            String text = "r = " + ReactorView.fmt(xv) + " " + p.xUnit() + "   V = " + ReactorView.energy(yv * 1000);
            Font f = Font.font("Segoe UI Semibold", 10);
            double tw = ReactorView.width(text, f) + 14, tx = Math.min(right - tw, Math.max(left, hoverX - tw / 2));
            gc.setFill(Color.color(.02, .03, .05, .94));
            gc.fillRoundRect(tx, top + 2, tw, 18, 5, 5);
            gc.setStroke(Color.color(1, 1, 1, .14));
            gc.strokeRoundRect(tx + .5, top + 2.5, tw - 1, 17, 5, 5);
            gc.setFont(f);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFill(INK);
            gc.fillText(text, tx + 7, top + 15);
        }
    }

    /** La valeur de la courbe à l'abscisse x (interpolation linéaire entre ses points). */
    static double interpolate(Plot p, double x) {
        double[] xs = p.xs(), ys = p.ys();
        if (xs.length == 0) return 0;
        if (x <= xs[0]) return ys[0];
        for (int i = 1; i < xs.length; i++) {
            if (xs[i] >= x) {
                double f = (x - xs[i - 1]) / Math.max(1e-12, xs[i] - xs[i - 1]);
                return ys[i - 1] + f * (ys[i] - ys[i - 1]);
            }
        }
        return ys[ys.length - 1];
    }
}
