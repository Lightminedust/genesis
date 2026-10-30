package org.genesis.chain;

import org.genesis.app.I18n;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleFunction;

/**
 * Le tableau de bord du laboratoire, dans la famille des instruments de Genesis : une colonne de verre fumé, la
 * grandeur qui compte en grand, les autres en lignes régulières avec leur jauge fine (turquoise au repos, ambre en
 * charge, corail à saturation), une courbe lisible au survol, puis le texte et les équivalences.
 */
final class ChainBoard {
    record Part(String name, String value, double share, Color color) {}

    /** Une mesure : libellé, valeur, remplissage 0–1, repère (−1 : aucun) et sa légende. */
    record Row(String key, String label, String value, double fill, double mark, String markLabel) {}

    /** Une courbe y(x) : bornes, échelle log en x ou non, niveau horizontal (NaN : aucun), position marquée. */
    record Plot(String title, double[] xs, double[] ys, boolean logX, double level, String levelLabel, double markerX,
                double xmin, double xmax, double ymin, double ymax, DoubleFunction<String> xText,
                DoubleFunction<String> yText) {}

    record Board(String stage, String heroLabel, String heroValue, String heroUnit, double heroFill, double heroMark,
                 String splitLabel, List<Part> parts, List<Row> rows, Plot plot, String note, String factsTitle,
                 List<String[]> facts) {}

    static final double WIDTH = 300;
    static final Color INK = Color.web("#E8EEF7"), LABEL = Color.web("#8896AB"), DIM = Color.web("#5F6C80");
    static final Color TEAL = Color.web("#3DD6C6"), AMBER = Color.web("#F5B84B"), CORAL = Color.web("#FF7A6B");
    private static final double PAD = 16, HEAD = 34, HERO = 64, ROW = 29, PLOT = 150, FACT = 17.5, SECTION = 24, LINE = 14.5;
    private static final Font NOTE = Font.font("Segoe UI", 10.5);
    private static final Text MEASURE = new Text();
    private final Map<String, Double> shown = new HashMap<>();
    private double hoverX = -1, hoverY = -1;

    static Color level(double f) {
        double t = Math.max(0, Math.min(1, f));
        return t < .6 ? TEAL.interpolate(AMBER, t / .6) : AMBER.interpolate(CORAL, (t - .6) / .4);
    }

    static double width(String text, Font font) {
        MEASURE.setFont(font);
        MEASURE.setText(text);
        return MEASURE.getLayoutBounds().getWidth();
    }

    /** Le texte traduit, coupé en lignes de la largeur donnée. */
    static List<String> wrap(String source, Font font, double width) {
        List<String> lines = new ArrayList<>();
        if (source == null || source.isBlank()) return lines;
        String line = "";
        for (String word : I18n.text(source).split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width(next, font) > width) {
                lines.add(line);
                line = word;
            } else {
                line = next;
            }
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    static String spaced(String text) {
        String translated = I18n.text(text);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < translated.length(); i++) {
            char c = translated.charAt(i);
            out.append(c);
            if (i < translated.length() - 1) out.append(c == ' ' ? "  " : " ");
        }
        return out.toString();
    }

    /** Un panneau plat : fond sombre presque opaque, filet fin. */
    static void glass(GraphicsContext gc, double x, double y, double w, double h) {
        gc.setFill(Color.color(.039, .055, .078, .9));
        gc.fillRoundRect(x, y, w, h, 12, 12);
        gc.setLineWidth(1);
        gc.setStroke(Color.color(1, 1, 1, .09));
        gc.strokeRoundRect(x + .5, y + .5, w - 1, h - 1, 12, 12);
    }

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

    double height(Board b) {
        double h = HEAD;
        if (b.parts() != null && !b.parts().isEmpty()) h += 36 + 15 * legendLines(b.parts()) + 6;
        if (b.heroValue() != null) h += HERO;
        h += ROW * b.rows().size();
        if (b.plot() != null) h += PLOT + 6;
        if (b.note() != null && !b.note().isBlank()) h += 8 + LINE * wrap(b.note(), NOTE, WIDTH - 2 * PAD).size();
        if (b.facts() != null && !b.facts().isEmpty()) h += SECTION + FACT * b.facts().size();
        return h + 12;
    }

    private static int legendLines(List<Part> parts) {
        Font font = Font.font("Segoe UI", 10);
        double x = 0;
        int line = 0;
        for (Part p : parts) {
            double w = 12 + width(I18n.text(p.name()) + "  " + p.value(), font) + 14;
            if (x > 0 && x + w > WIDTH - 2 * PAD) {
                x = 0;
                line++;
            }
            x += w;
        }
        return line + 1;
    }

    void draw(GraphicsContext gc, double x, double y, Board b, double dt) {
        double w = WIDTH, inner = w - 2 * PAD;
        gc.save();
        glass(gc, x, y, w, height(b));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(LABEL);
        gc.fillText(spaced(b.stage()), x + PAD, y + 21);
        gc.setFill(TEAL);
        gc.fillOval(x + w - PAD - 5, y + 16, 5, 5);
        rule(gc, x + PAD, y + HEAD - 4, inner);
        double row = y + HEAD;
        if (b.parts() != null && !b.parts().isEmpty()) row += split(gc, x + PAD, row, inner, b, dt);
        if (b.heroValue() != null) {
            hero(gc, x + PAD, row, inner, b, dt);
            row += HERO;
        }
        for (Row r : b.rows()) {
            metric(gc, x + PAD, row, inner, r, dt);
            row += ROW;
        }
        if (b.plot() != null) {
            row += 6;
            plot(gc, x + PAD, row, inner, b.plot());
            row += PLOT;
        }
        if (b.note() != null && !b.note().isBlank()) {
            row += 8;
            gc.setFont(NOTE);
            gc.setFill(Color.web("#B8C6D8"));
            gc.setTextAlign(TextAlignment.LEFT);
            for (String line : wrap(b.note(), NOTE, inner)) {
                gc.fillText(line, x + PAD, row + 10);
                row += LINE;
            }
        }
        if (b.facts() != null && !b.facts().isEmpty()) {
            row += 4;
            heading(gc, x + PAD, row, inner, b.factsTitle());
            row += SECTION - 4;
            for (int i = 0; i < b.facts().size(); i++) {
                String[] f = b.facts().get(i);
                fact(gc, x + PAD, row, inner, i, f[0], f[1]);
                row += FACT;
            }
        }
        gc.restore();
    }

    static void rule(GraphicsContext gc, double x, double y, double w) {
        gc.setLineWidth(1);
        gc.setStroke(new LinearGradient(x, 0, x + w, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(1, 1, 1, .12)), new Stop(1, Color.color(1, 1, 1, .02))));
        gc.strokeLine(x, y + .5, x + w, y + .5);
    }

    static void heading(GraphicsContext gc, double x, double y, double w, String text) {
        rule(gc, x, y, w);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(LABEL);
        gc.fillText(spaced(text), x, y + 17);
    }

    private static void fact(GraphicsContext gc, double x, double y, double w, int index, String key, String value) {
        if (index % 2 == 0) {
            gc.setFill(Color.color(1, 1, 1, .028));
            gc.fillRect(x - 6, y - 1, w + 12, FACT - 1);
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI", 10.5));
        gc.setFill(LABEL);
        I18n.fillText(gc, key, x, y + 11.5, w * .4);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(Font.font("Segoe UI Semibold", 10.5));
        gc.setFill(AMBER.interpolate(Color.WHITE, .25));
        I18n.fillText(gc, value, x + w, y + 11.5, w * .62);
    }

    private double split(GraphicsContext gc, double x, double y, double w, Board b, double dt) {
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(LABEL);
        gc.fillText(spaced(b.splitLabel()), x, y + 13);
        double by = y + 22, bh = 7, sum = 0;
        for (Part p : b.parts()) sum += Math.max(0, p.share());
        gc.setFill(Color.color(1, 1, 1, .06));
        gc.fillRoundRect(x, by, w, bh, 2, 2);
        double at = x, gap = 2;
        for (Part p : b.parts()) {
            double share = sum > 0 ? smooth("split:" + p.name(), Math.max(0, p.share()) / sum, dt) : 0;
            double len = (w - gap * Math.max(0, b.parts().size() - 1)) * share;
            if (len < .5) continue;
            gc.setFill(p.color());
            gc.fillRoundRect(at, by, len, bh, 2, 2);
            at += len + gap;
        }
        Font font = Font.font("Segoe UI", 10);
        gc.setFont(font);
        double lx = 0;
        int line = 0;
        for (Part p : b.parts()) {
            String name = I18n.text(p.name());
            double pw = 12 + width(name + "  " + p.value(), font) + 14;
            if (lx > 0 && lx + pw > w) {
                lx = 0;
                line++;
            }
            double px = x + lx, py = by + bh + 15 + 15 * line;
            gc.setFill(p.color());
            gc.fillOval(px, py - 7, 6, 6);
            gc.setFill(LABEL);
            gc.fillText(name + "  ", px + 11, py);
            gc.setFill(INK);
            gc.fillText(p.value(), px + 11 + width(name + "  ", font), py);
            lx += pw;
        }
        return 36 + 15 * (line + 1) + 6;
    }

    private void hero(GraphicsContext gc, double x, double y, double w, Board b, double dt) {
        double f = smooth("hero", b.heroFill(), dt);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI", 10.5));
        gc.setFill(LABEL);
        I18n.fillText(gc, b.heroLabel(), x, y + 13);
        Font big = Font.font("Segoe UI Semibold", 28);
        gc.setFont(big);
        gc.setFill(level(b.heroFill()).interpolate(Color.WHITE, .35));
        gc.fillText(b.heroValue(), x, y + 42, w * .7);
        if (b.heroUnit() != null && !b.heroUnit().isBlank()) {
            gc.setFont(Font.font("Segoe UI", 11));
            gc.setFill(LABEL);
            I18n.fillText(gc, b.heroUnit(), x + Math.min(w * .7, width(b.heroValue(), big)) + 8, y + 42, w * .3);
        }
        gauge(gc, x, y + 51, w, 4, f, true);
        if (b.heroMark() >= 0 && b.heroMark() <= 1) {
            double mx = x + w * b.heroMark();
            gc.setFill(INK);
            gc.fillRect(mx - 1, y + 47, 2, 12);
        }
    }

    private void metric(GraphicsContext gc, double x, double y, double w, Row r, double dt) {
        double f = smooth("metric:" + r.key(), r.fill(), dt);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI", 10.5));
        gc.setFill(LABEL);
        I18n.fillText(gc, r.label(), x, y + 11, w * .55);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(Font.font("Segoe UI Semibold", 11.5));
        gc.setFill(INK);
        I18n.fillText(gc, r.value(), x + w, y + 11, w * .52);
        gauge(gc, x, y + 17, w, 2.5, f, false);
        if (r.mark() >= 0 && r.mark() <= 1) {
            double mx = x + w * r.mark();
            gc.setFill(INK);
            gc.fillRect(mx - .75, y + 14, 1.5, 8.5);
            if (r.markLabel() != null && !r.markLabel().isEmpty()) {
                gc.setFont(Font.font("Segoe UI", 8.5));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setFill(DIM);
                I18n.fillText(gc, r.markLabel(), Math.max(x + 8, Math.min(x + w - 8, mx)), y + 28);
            }
        }
    }

    static void gauge(GraphicsContext gc, double x, double y, double w, double h, double f, boolean ticks) {
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

    private double toX(Plot p, double v, double left, double w) {
        if (p.logX()) {
            double a = Math.log10(p.xmin()), b = Math.log10(p.xmax());
            return left + w * (Math.log10(Math.max(v, p.xmin())) - a) / Math.max(1e-12, b - a);
        }
        return left + w * (v - p.xmin()) / Math.max(1e-12, p.xmax() - p.xmin());
    }

    private double fromX(Plot p, double px, double left, double w) {
        double t = (px - left) / w;
        if (p.logX()) {
            double a = Math.log10(p.xmin()), b = Math.log10(p.xmax());
            return Math.pow(10, a + t * (b - a));
        }
        return p.xmin() + t * (p.xmax() - p.xmin());
    }

    void plot(GraphicsContext gc, double x, double y, double w, Plot p) {
        double top = y + 20, height = PLOT - 40, left = x, right = x + w;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(LABEL);
        gc.fillText(spaced(p.title()), x, y + 11);
        gc.setFill(Color.color(1, 1, 1, .03));
        gc.fillRect(left, top, w, height);
        double yr = Math.max(1e-12, p.ymax() - p.ymin());
        if (Double.isFinite(p.level())) {
            double ey = top + height * (1 - (p.level() - p.ymin()) / yr);
            if (ey >= top && ey <= top + height) {
                gc.setStroke(AMBER.deriveColor(0, 1, 1, .85));
                gc.setLineWidth(1);
                gc.setLineDashes(5, 3);
                gc.strokeLine(left, ey, right, ey);
                gc.setLineDashes(null);
                gc.setFont(Font.font("Segoe UI Semibold", 9));
                gc.setFill(AMBER);
                I18n.fillText(gc, p.levelLabel(), left + 4, ey - 4);
            }
        }
        int n = p.xs().length;
        double[] px = new double[n], py = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = toX(p, p.xs()[i], left, w);
            py[i] = top + height * (1 - (Math.max(p.ymin(), Math.min(p.ymax(), p.ys()[i])) - p.ymin()) / yr);
        }
        gc.setStroke(TEAL);
        gc.setLineWidth(1.7);
        gc.strokePolyline(px, py, n);
        if (Double.isFinite(p.markerX()) && n > 1) {
            double mx = toX(p, p.markerX(), left, w);
            double my = top + height * (1 - (Math.max(p.ymin(), Math.min(p.ymax(), interpolate(p, p.markerX()))) - p.ymin()) / yr);
            gc.setStroke(Color.color(1, 1, 1, .35));
            gc.setLineWidth(1);
            gc.strokeLine(mx, top, mx, top + height);
            gc.setFill(INK);
            gc.fillOval(mx - 3.5, my - 3.5, 7, 7);
            gc.setStroke(TEAL);
            gc.setLineWidth(1.3);
            gc.strokeOval(mx - 5.5, my - 5.5, 11, 11);
        }
        gc.setFont(Font.font("Segoe UI", 9));
        gc.setFill(DIM);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(p.xText().apply(p.xmin()), left, top + height + 12);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(p.xText().apply(p.xmax()), right, top + height + 12);
        if (hoverX >= left && hoverX <= right && hoverY >= top - 6 && hoverY <= top + height + 6 && n > 1) {
            double xv = fromX(p, hoverX, left, w), yv = interpolate(p, xv);
            double hy = top + height * (1 - (Math.max(p.ymin(), Math.min(p.ymax(), yv)) - p.ymin()) / yr);
            gc.setStroke(Color.color(1, 1, 1, .5));
            gc.setLineWidth(1);
            gc.strokeLine(hoverX, top, hoverX, top + height);
            gc.setFill(TEAL);
            gc.fillOval(hoverX - 3, hy - 3, 6, 6);
            String text = p.xText().apply(xv) + "   " + p.yText().apply(yv);
            Font f = Font.font("Segoe UI Semibold", 10);
            double tw = width(text, f) + 14, tx = Math.min(right - tw, Math.max(left, hoverX - tw / 2));
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

    static double interpolate(Plot p, double x) {
        double[] xs = p.xs(), ys = p.ys();
        if (xs.length == 0) return 0;
        if (x <= xs[0]) return ys[0];
        for (int i = 1; i < xs.length; i++) {
            if (xs[i] >= x) {
                double a = p.logX() ? Math.log(Math.max(xs[i - 1], 1e-300)) : xs[i - 1];
                double b = p.logX() ? Math.log(Math.max(xs[i], 1e-300)) : xs[i];
                double v = p.logX() ? Math.log(Math.max(x, 1e-300)) : x;
                double f = (v - a) / Math.max(1e-12, b - a);
                return ys[i - 1] + f * (ys[i] - ys[i - 1]);
            }
        }
        return ys[ys.length - 1];
    }
}
