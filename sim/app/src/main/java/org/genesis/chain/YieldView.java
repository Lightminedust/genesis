package org.genesis.chain;

import org.genesis.app.I18n;

import com.studio.genesis.grpc.ChainCompare;
import com.studio.genesis.grpc.YieldState;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Le bac à sable du rendement, dessiné au centre : la courbe d'Aston (énergie de liaison par nucléon des noyaux
 * stables, mesurée), le noyau choisi et ce que deviennent ses morceaux ; la jauge Q du rouge (il faut fournir de
 * l'énergie) à l'or (il en sort) ; le bilan des masses avant / après ; le tableau U-235 · Fe-56 · Fe-60 · D + T.
 */
final class YieldView {
    private static final Color RED = Color.web("#FF5F57"), GOLD = Color.web("#FFC94A"), TEAL = ChainBoard.TEAL;
    private static final Color INK = ChainBoard.INK, LABEL = ChainBoard.LABEL, DIM = ChainBoard.DIM;
    private int[] curveA = new int[0], curveZ = new int[0];
    private double[] curveBa = new double[0];
    private final List<ChainCompare> compare = new ArrayList<>();
    private YieldState state;
    private double hoverX = -1, hoverY = -1;
    private String[] symbols = new String[0];

    void setSymbols(String[] bySymbolZ) {
        symbols = bySymbolZ;
    }

    void apply(YieldState s) {
        if (s.getCurveACount() > 0) {
            curveA = s.getCurveAList().stream().mapToInt(Integer::intValue).toArray();
            curveZ = s.getCurveZList().stream().mapToInt(Integer::intValue).toArray();
            curveBa = s.getCurveBaList().stream().mapToDouble(Double::doubleValue).toArray();
        }
        if (s.getCompareCount() > 0) {
            compare.clear();
            compare.addAll(s.getCompareList());
        }
        state = s;
    }

    YieldState state() {
        return state;
    }

    void hover(double x, double y) {
        hoverX = x;
        hoverY = y;
    }

    String symbol(int z) {
        return z > 0 && z < symbols.length && symbols[z] != null ? symbols[z] : "Z" + z;
    }

    String label(int z, int a) {
        return symbol(z) + "-" + a;
    }

    /** La couleur d'une énergie : rouge si elle coûte, or si elle rapporte. */
    static Color qColor(double q) {
        if (q <= 0) return RED.interpolate(Color.web("#B83A35"), Math.min(1, -q / 50));
        return GOLD.interpolate(Color.web("#FFE7A0"), Math.min(1, q / 200));
    }

    void draw(GraphicsContext gc, double x, double y, double w, double h) {
        if (state == null || curveA.length == 0) {
            gc.setFill(LABEL);
            gc.setFont(Font.font("Segoe UI", 13));
            gc.setTextAlign(TextAlignment.CENTER);
            I18n.fillText(gc, "Bilan des masses en cours…", x + w / 2, y + h / 2);
            return;
        }
        double tableH = 60 + 26 * Math.max(4, compare.size());
        double lowerH = 206;
        double curveH = Math.max(220, h - tableH - lowerH - 24);
        curve(gc, x, y, w, curveH);
        double gaugeW = Math.min(420, w * .48);
        gauge(gc, x, y + curveH + 12, gaugeW, lowerH);
        balance(gc, x + gaugeW + 12, y + curveH + 12, w - gaugeW - 12, lowerH);
        table(gc, x, y + curveH + lowerH + 24, w, tableH);
    }

    // ================================================================== la courbe d'Aston
    private void curve(GraphicsContext gc, double x, double y, double w, double h) {
        ChainBoard.glass(gc, x, y, w, h);
        double left = x + 54, right = x + w - 20, top = y + 52, bottom = y + h - 34;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(LABEL);
        gc.fillText(ChainBoard.spaced("ÉNERGIE DE LIAISON PAR NUCLÉON — COURBE D'ASTON"), x + 16, y + 21);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(Font.font("Segoe UI", 9));
        gc.setFill(DIM);
        I18n.fillText(gc, "noyaux stables pesés (AME) · survol : lire un noyau", right, y + 21);
        double amax = 260, bmin = 0, bmax = 9.2;
        // Graduations
        gc.setFont(Font.font("Segoe UI", 9));
        for (double b = 0; b <= 9; b += 1) {
            double py = bottom - (b - bmin) / (bmax - bmin) * (bottom - top);
            gc.setStroke(Color.color(1, 1, 1, b == 0 ? .14 : .05));
            gc.strokeLine(left, py, right, py);
            gc.setFill(DIM);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText(ChainFormat.number(b), left - 6, py + 3);
        }
        for (int a = 0; a <= 250; a += 50) {
            double px = left + a / amax * (right - left);
            gc.setFill(DIM);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(String.valueOf(a), px, bottom + 14);
        }
        gc.setTextAlign(TextAlignment.LEFT);
        I18n.fillText(gc, "B/A (MeV)", x + 10, top - 8);
        gc.setTextAlign(TextAlignment.RIGHT);
        I18n.fillText(gc, "nombre de nucléons A", right, bottom + 28);
        // Les deux pentes : vers le sommet, de chaque côté.
        double peakX = left + state.getPeakA() / amax * (right - left);
        double peakY = bottom - (state.getPeakBa() - bmin) / (bmax - bmin) * (bottom - top);
        gc.setFill(Color.web("#3DD6C6", .045));
        gc.fillRect(left, top, peakX - left, bottom - top);
        gc.setFill(Color.web("#FFC94A", .04));
        gc.fillRect(peakX, top, right - peakX, bottom - top);
        gc.setFont(Font.font("Segoe UI Semibold", 11));
        gc.setFill(Color.web("#7FE3D6"));
        gc.setTextAlign(TextAlignment.LEFT);
        I18n.fillText(gc, "FUSION →  les légers gagnent à s'unir", left + 10, bottom - 14);
        gc.setFill(Color.web("#FFD27A"));
        gc.setTextAlign(TextAlignment.RIGHT);
        I18n.fillText(gc, "les lourds gagnent à se briser  ← FISSION", right - 10, top + (bottom - top) * .55);
        // Les noyaux stables
        int hovered = -1;
        double best = 64;
        for (int i = 0; i < curveA.length; i++) {
            double px = left + curveA[i] / amax * (right - left);
            double py = bottom - (curveBa[i] - bmin) / (bmax - bmin) * (bottom - top);
            gc.setFill(Color.web("#9FB4CE", .75));
            gc.fillOval(px - 1.8, py - 1.8, 3.6, 3.6);
            double d = (px - hoverX) * (px - hoverX) + (py - hoverY) * (py - hoverY);
            if (d < best) {
                best = d;
                hovered = i;
            }
        }
        // Le sommet, nommé en haut à droite avec un trait de rappel.
        gc.setStroke(TEAL);
        gc.setLineWidth(1.4);
        gc.strokeOval(peakX - 6, peakY - 6, 12, 12);
        String peak = I18n.text("sommet de la courbe : " + label(state.getPeakZ(), state.getPeakA()) + " · "
                + ChainFormat.decimal(state.getPeakBa(), 3) + " MeV");
        Font peakFont = Font.font("Segoe UI Semibold", 10);
        double labelX = right - ChainBoard.width(peak, peakFont) - 6, labelY = top - 10;
        gc.setStroke(TEAL.deriveColor(0, 1, 1, .5));
        gc.setLineWidth(1);
        gc.strokeLine(peakX + 5, peakY - 5, labelX - 4, labelY - 4);
        gc.setFont(peakFont);
        gc.setFill(TEAL);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(peak, labelX, labelY);
        // Le noyau choisi, ses morceaux et son double.
        if (state.getBindingPerNucleon() > 0) {
            double sx = left + state.getA() / amax * (right - left);
            double sy = bottom - (state.getBindingPerNucleon() - bmin) / (bmax - bmin) * (bottom - top);
            if (state.getFissionKnown()) {
                arrowTo(gc, sx, sy, state.getFissionZ1(), state.getFissionA1(), state.getFissionBa1(), left, right, top,
                        bottom, amax, bmin, bmax, qColor(state.getFissionQMev()));
                arrowTo(gc, sx, sy, state.getFissionZ2(), state.getFissionA2(), state.getFissionBa2(), left, right, top,
                        bottom, amax, bmin, bmax, qColor(state.getFissionQMev()));
            }
            if (state.getFusionKnown()) {
                arrowTo(gc, sx, sy, state.getFusionZ(), state.getFusionA(), state.getFusionBa(), left, right, top, bottom,
                        amax, bmin, bmax, qColor(state.getFusionQMev()));
            }
            Color mine = state.getFissionData() && state.getKInfinity() > 1 ? GOLD : Color.web("#F0F5FF");
            gc.setFill(mine.deriveColor(0, 1, 1, .25));
            gc.fillOval(sx - 11, sy - 11, 22, 22);
            gc.setFill(mine);
            gc.fillOval(sx - 5, sy - 5, 10, 10);
            gc.setFont(Font.font("Segoe UI Semibold", 13));
            gc.setTextAlign(sx > (left + right) / 2 ? TextAlignment.RIGHT : TextAlignment.LEFT);
            gc.fillText(state.getLabel() + "  " + ChainFormat.decimal(state.getBindingPerNucleon(), 3) + " MeV",
                    sx + (sx > (left + right) / 2 ? -14 : 14), sy + 22);
        }
        if (hovered >= 0) {
            double px = left + curveA[hovered] / amax * (right - left);
            double py = bottom - (curveBa[hovered] - bmin) / (bmax - bmin) * (bottom - top);
            String text = label(curveZ[hovered], curveA[hovered]) + "   B/A = " + ChainFormat.decimal(curveBa[hovered], 3) + " MeV";
            Font f = Font.font("Segoe UI Semibold", 10);
            double tw = ChainBoard.width(text, f) + 14, tx = Math.min(right - tw, Math.max(left, px - tw / 2));
            gc.setFill(Color.color(.02, .03, .05, .94));
            gc.fillRoundRect(tx, py - 30, tw, 18, 5, 5);
            gc.setFont(f);
            gc.setFill(INK);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText(text, tx + 7, py - 17);
            gc.setStroke(INK);
            gc.strokeOval(px - 4, py - 4, 8, 8);
        }
    }

    private void arrowTo(GraphicsContext gc, double sx, double sy, int z, int a, double ba, double left, double right,
                         double top, double bottom, double amax, double bmin, double bmax, Color color) {
        if (!(ba > 0)) return;
        double tx = left + a / amax * (right - left);
        double ty = bottom - (ba - bmin) / (bmax - bmin) * (bottom - top);
        gc.setStroke(color.deriveColor(0, 1, 1, .85));
        gc.setLineWidth(1.3);
        gc.setLineDashes(4, 4);
        gc.strokeLine(sx, sy, tx, ty);
        gc.setLineDashes(null);
        gc.setFill(color);
        gc.fillOval(tx - 4, ty - 4, 8, 8);
        gc.setFont(Font.font("Segoe UI", 10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(label(z, a), tx, ty - 9);
    }

    // ================================================================== la jauge Q
    private void gauge(GraphicsContext gc, double x, double y, double w, double h) {
        ChainBoard.glass(gc, x, y, w, h);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(LABEL);
        gc.fillText(ChainBoard.spaced("JAUGE Q = Δm·c²"), x + 16, y + 21);
        double left = x + 16, right = x + w - 16, by = y + 40, bh = 12;
        gc.setFill(new LinearGradient(left, 0, right, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#B83A35")), new Stop(.5, Color.web("#3A3F4B")), new Stop(1, GOLD)));
        gc.fillRoundRect(left, by, right - left, bh, 6, 6);
        gc.setFont(Font.font("Segoe UI", 9));
        gc.setFill(DIM);
        gc.setTextAlign(TextAlignment.LEFT);
        I18n.fillText(gc, "endothermique", left, by + bh + 13);
        gc.setTextAlign(TextAlignment.RIGHT);
        I18n.fillText(gc, "exothermique", right, by + bh + 13);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("0", (left + right) / 2, by + bh + 13);
        double row = by + bh + 34;
        if (state.getFissionKnown()) row = qMarker(gc, "fission", state.getFissionQMev(), left, right, by, bh, row);
        if (state.getFusionKnown()) row = qMarker(gc, "fusion avec lui-même", state.getFusionQMev(), left, right, by, bh, row);
        if (!state.getFissionKnown() && !state.getFusionKnown()) {
            gc.setFill(LABEL);
            gc.setTextAlign(TextAlignment.LEFT);
            I18n.fillText(gc, "Aucune voie entre noyaux pesés.", left, row);
        }
    }

    /** Une marque sur la jauge (échelle sign·log10(1 + |Q|), de −1 000 à +1 000 MeV) et sa ligne de légende. */
    private double qMarker(GraphicsContext gc, String name, double q, double left, double right, double by, double bh, double row) {
        double s = Math.signum(q) * Math.log10(1 + Math.abs(q)) / 3;
        double mx = (left + right) / 2 + Math.max(-1, Math.min(1, s)) * (right - left) / 2;
        Color c = qColor(q);
        gc.setFill(c);
        gc.fillPolygon(new double[]{mx - 5, mx + 5, mx}, new double[]{by - 8, by - 8, by - 1}, 3);
        gc.fillRect(mx - 1, by - 1, 2, bh + 2);
        gc.setFont(Font.font("Segoe UI", 11));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(LABEL);
        I18n.fillText(gc, name, left, row);
        gc.setFont(Font.font("Segoe UI Semibold", 12));
        gc.setFill(c);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("Q = " + ChainFormat.signed(q) + " MeV", right, row);
        return row + 20;
    }

    // ================================================================== le bilan des masses
    private void balance(GraphicsContext gc, double x, double y, double w, double h) {
        ChainBoard.glass(gc, x, y, w, h);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(LABEL);
        gc.fillText(ChainBoard.spaced("BILAN DES MASSES"), x + 16, y + 21);
        double row = y + 40;
        if (state.getFissionKnown()) {
            row = massRow(gc, x + 16, row, w - 32, state.getLabel(), state.getMassU(),
                    label(state.getFissionZ1(), state.getFissionA1()) + " + " + label(state.getFissionZ2(), state.getFissionA2()),
                    state.getFissionProductsU(), state.getFissionQMev());
        }
        if (state.getFusionKnown()) {
            row = massRow(gc, x + 16, row + 4, w - 32, state.getLabel() + " + " + state.getLabel(), 2 * state.getMassU(),
                    label(state.getFusionZ(), state.getFusionA()), state.getFusionProductU(), state.getFusionQMev());
        }
        boolean wall = (!state.getFissionKnown() || state.getFissionQMev() <= 0) && (!state.getFusionKnown() || state.getFusionQMev() <= 0);
        if (wall) {
            gc.setFont(Font.font("Segoe UI Semibold", 11));
            gc.setFill(RED.interpolate(Color.WHITE, .25));
            gc.setTextAlign(TextAlignment.LEFT);
            for (String line : ChainBoard.wrap("Les produits pèsent plus que le noyau de départ : il faudrait fournir l'énergie manquante.",
                    Font.font("Segoe UI Semibold", 11), w - 32)) {
                gc.fillText(line, x + 16, row + 6);
                row += 14;
            }
        }
    }

    private double massRow(GraphicsContext gc, double x, double y, double w, String before, double mBefore, String after,
                           double mAfter, double q) {
        Font small = Font.font("Segoe UI", 10.5), bold = Font.font("Segoe UI Semibold", 10.5);
        double max = Math.max(mBefore, mAfter), min = Math.min(mBefore, mAfter);
        // Les barres montrent l'écart : on part de 99,5 % de la plus petite masse, pour que Δm soit visible.
        double floor = min * .995;
        double barW = w * .52;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(small);
        gc.setFill(LABEL);
        I18n.fillText(gc, "avant", x, y + 10);
        gc.setFill(INK);
        gc.fillText(before, x + 44, y + 10);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(bold);
        gc.fillText(ChainFormat.decimal(mBefore, 4) + " u", x + w, y + 10);
        bar(gc, x + 44, y + 14, barW, (mBefore - floor) / (max - floor), Color.web("#9FB4CE"));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(small);
        gc.setFill(LABEL);
        I18n.fillText(gc, "après", x, y + 34);
        gc.setFill(INK);
        gc.fillText(after, x + 44, y + 34);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(bold);
        gc.fillText(ChainFormat.decimal(mAfter, 4) + " u", x + w, y + 34);
        bar(gc, x + 44, y + 38, barW, (mAfter - floor) / (max - floor), qColor(q));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 10.5));
        gc.setFill(qColor(q));
        String delta = I18n.text("Δm = " + ChainFormat.signed((mBefore - mAfter)) + " u, " + (q > 0 ? "libère " : "coûte ")
                + ChainFormat.number(Math.abs(q)) + " MeV");
        gc.fillText(delta, x + 44, y + 59, w - 44);
        return y + 70;
    }

    private static void bar(GraphicsContext gc, double x, double y, double w, double f, Color c) {
        gc.setFill(Color.color(1, 1, 1, .06));
        gc.fillRect(x, y, w, 6);
        gc.setFill(c);
        gc.fillRect(x, y, w * Math.max(0.02, Math.min(1, f)), 6);
    }

    // ================================================================== le tableau
    private void table(GraphicsContext gc, double x, double y, double w, double h) {
        ChainBoard.glass(gc, x, y, w, h);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9));
        gc.setFill(LABEL);
        gc.fillText(ChainBoard.spaced("FISSION, FUSION, RADIOACTIVITÉ : CE QUE RAPPORTE UN KILOGRAMME"), x + 16, y + 21);
        double[] cols = {16, 90, 230, 330, 470};
        String[] heads = {"noyau", "voie", "Q par réaction", "par kilogramme", "condition"};
        gc.setFont(Font.font("Segoe UI", 9));
        gc.setFill(DIM);
        for (int i = 0; i < heads.length; i++) I18n.fillText(gc, heads[i], x + cols[i], y + 40);
        double row = y + 52;
        for (int i = 0; i < compare.size(); i++) {
            ChainCompare c = compare.get(i);
            if (i % 2 == 0) {
                gc.setFill(Color.color(1, 1, 1, .028));
                gc.fillRect(x + 8, row - 1, w - 16, 25);
            }
            Color tint = c.getPossible() ? GOLD : RED.interpolate(Color.WHITE, .2);
            gc.setFont(Font.font("Segoe UI Semibold", 12));
            gc.setFill(tint);
            gc.fillText(c.getLabel(), x + cols[0], row + 16);
            gc.setFont(Font.font("Segoe UI", 10.5));
            gc.setFill(INK);
            I18n.fillText(gc, c.getProcess(), x + cols[1], row + 16, cols[2] - cols[1] - 8);
            gc.setFill(qColor(c.getQMev()));
            gc.fillText(ChainFormat.signed(c.getQMev()) + " MeV", x + cols[2], row + 16);
            gc.setFill(INK);
            String perKg = c.getJPerKg() > 0 ? I18n.text(ChainFormat.number(c.getTntPerKg()) + " t de TNT") : "—";
            gc.fillText(perKg, x + cols[3], row + 16);
            gc.setFill(LABEL);
            I18n.fillText(gc, c.getCondition(), x + cols[4], row + 16, w - cols[4] - 16);
            row += 26;
        }
    }
}
