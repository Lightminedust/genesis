package org.genesis.fusion;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import java.util.List;

/**
 * La loi de l'instant, posée en grand sur la scène : la formule composée comme on l'écrit (fraction barrée), la même
 * avec ses valeurs dessous, et deux pastilles qui disent comment elle répond quand on change une grandeur — « r → 2r »,
 * « F → F/4 ». Tout vient du calcul en cours ; rien n'est une légende figée.
 */
public final class FormulaCard {
    /**
     * Une loi à montrer : son nom, son membre de gauche (« F = k »), le haut et le bas de sa fraction (le bas vide :
     * pas de fraction), sa valeur chiffrée, la même formule avec les nombres substitués, et les deux pastilles de
     * réponse (cause, effet).
     */
    record Formula(String title, String left, String numerator, String denominator, String value, String substituted,
                   String cause, String effect, Color accent) {}

    /** La taille de référence de la formule (px) : lisible, sans écraser la scène. */
    static final double BASE = 18;

    private FormulaCard() {}

    /** La largeur que prendra la formule, pour la centrer sans chevaucher. */
    static double width(Formula f, double scale) {
        double big = BASE * scale;
        double w = ReactorView.width(f.left() + " ", Font.font("Cambria Math", big));
        double fraction = Math.max(ReactorView.width(f.numerator(), Font.font("Cambria Math", big * .86)),
                ReactorView.width(f.denominator(), Font.font("Cambria Math", big * .86)));
        return w + fraction + ReactorView.width("  =  " + f.value(), Font.font("Cambria Math", big)) + 24;
    }

    /**
     * Dessine la loi centrée en (cx, cy). `scale` ramène la taille à la place disponible ; `alpha` la fait apparaître.
     * Renvoie la hauteur occupée.
     */
    static double draw(GraphicsContext gc, double cx, double cy, Formula f, double scale, double alpha) {
        return draw(gc, cx, cy, f, scale, alpha, true);
    }

    /** `detail` : avec la formule chiffrée dessous (lecture complète) ; sans, la loi et ses deux pastilles seulement. */
    static double draw(GraphicsContext gc, double cx, double cy, Formula f, double scale, double alpha, boolean detail) {
        if (alpha <= .01) return 0;
        double big = BASE * scale, small = big * .86;
        Font main = Font.font("Cambria Math", big), part = Font.font("Cambria Math", small);
        Color ink = Color.web("#EAF3FF", Math.min(1, alpha));
        double leftWidth = ReactorView.width(f.left() + " ", main);
        boolean fraction = !f.denominator().isBlank();
        double numWidth = ReactorView.width(f.numerator(), part), denWidth = ReactorView.width(f.denominator(), part);
        double barWidth = Math.max(numWidth, denWidth) + 10 * scale;
        double tail = ReactorView.width("  =  " + f.value(), main);
        double total = leftWidth + (fraction ? barWidth : numWidth) + tail;
        double x = cx - total / 2;
        // Le titre, au-dessus.
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(Font.font("Segoe UI Semibold", 9 * Math.max(.9, scale)));
        gc.setFill(f.accent().deriveColor(0, 1, 1, .8 * alpha));
        gc.fillText(FusionDashboard.spaced(f.title()), cx, cy - big * .95);
        // Le membre de gauche.
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(main);
        gc.setFill(ink);
        gc.fillText(f.left() + " ", x, cy + big * .34);
        x += leftWidth;
        // La fraction : numérateur, barre, dénominateur.
        gc.setFont(part);
        if (fraction) {
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(f.numerator(), x + barWidth / 2, cy - small * .22);
            gc.fillText(f.denominator(), x + barWidth / 2, cy + small * 1.02);
            gc.setStroke(ink);
            gc.setLineWidth(Math.max(1.2, 1.6 * scale));
            gc.strokeLine(x + 3, cy + small * .12, x + barWidth - 3, cy + small * .12);
            x += barWidth;
        } else {
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText(f.numerator(), x, cy + big * .34);
            x += numWidth;
        }
        // La valeur.
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(main);
        gc.setFill(f.accent().interpolate(Color.WHITE, .35).deriveColor(0, 1, 1, alpha));
        gc.fillText("  =  " + f.value(), x, cy + big * .34);
        // La formule chiffrée, dessous.
        double bottom = cy + (fraction ? small * 1.3 : big * .34);
        if (detail && !f.substituted().isBlank()) {
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setFont(Font.font("Segoe UI", 10 * Math.max(.9, scale)));
            gc.setFill(Color.web("#9FB4CE", .85 * alpha));
            bottom += 15 * Math.max(.9, scale);
            gc.fillText(f.substituted(), cx, bottom, total + 120);
        }
        // Les deux pastilles : la cause à gauche, l'effet à droite.
        if (!f.cause().isBlank()) {
            chip(gc, cx - total / 2 - 14, cy - big * .2, f.cause(), Color.web("#8FB3D9"), alpha, true);
            chip(gc, cx + total / 2 + 14, cy - big * .2, f.effect(), f.accent(), alpha, false);
        }
        return bottom - cy + big;
    }

    /** Une pastille d'annotation, ancrée à droite (`before`) ou à gauche du point donné. */
    private static void chip(GraphicsContext gc, double x, double y, String text, Color accent, double alpha, boolean before) {
        Font font = Font.font("Segoe UI Semibold", 10);
        double w = ReactorView.width(text, font) + 16, h = 20;
        double left = before ? x - w : x;
        gc.setFill(Color.color(.03, .06, .11, .72 * alpha));
        gc.fillRoundRect(left, y - h / 2, w, h, 8, 8);
        gc.setStroke(accent.deriveColor(0, 1, 1, .5 * alpha));
        gc.setLineWidth(1);
        gc.strokeRoundRect(left + .5, y - h / 2 + .5, w - 1, h - 1, 8, 8);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(font);
        gc.setFill(accent.interpolate(Color.WHITE, .4).deriveColor(0, 1, 1, alpha));
        gc.fillText(text, left + w / 2, y + 3.5);
    }

    /** Une suite de lignes « grandeur : valeur » en colonnes, pour un tableau compact. */
    static void table(GraphicsContext gc, double x, double y, double w, List<String[]> rows, double alpha) {
        double row = y;
        for (int i = 0; i < rows.size(); i++) {
            if (i % 2 == 0) {
                gc.setFill(Color.color(1, 1, 1, .028 * alpha));
                gc.fillRoundRect(x - 6, row - 11, w + 12, 18, 5, 5);
            }
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFont(Font.font("Segoe UI", 10.5));
            gc.setFill(Color.web("#9FB4CE", alpha));
            gc.fillText(rows.get(i)[0], x, row + 3, w * .52);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setFont(Font.font("Segoe UI Semibold", 10.5));
            gc.setFill(Color.web("#E6EEFA", alpha));
            gc.fillText(rows.get(i)[1], x + w, row + 3, w * .46);
            row += 18;
        }
    }
}
