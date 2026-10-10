package org.genesis;

import com.studio.genesis.grpc.SmParticle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Le tableau du Modèle Standard, dans sa forme classique : trois générations de fermions (quarks en haut, leptons en bas), les
 * bosons de jauge à droite, le Higgs à part. Chaque case porte la masse, la charge et le spin envoyés par le moteur ; la fiche
 * de la particule choisie (durées de vie, largeurs… calculées par le moteur) s'affiche dessous.
 */
final class SmTable {
    /** Où se trouve chaque particule : (colonne, ligne). */
    private static final String[][] GRID = {
            {"u", "c", "t", "g", "H"},
            {"d", "s", "b", "gamma", null},
            {"e", "mu", "tau", "Z", null},
            {"nu_e", "nu_mu", "nu_tau", "W", null},
    };
    static final Color QUARK = Color.web("#E9D8F7"), QUARK_RING = Color.web("#A86AD6");
    static final Color LEPTON = Color.web("#DDF3D3"), LEPTON_RING = Color.web("#6FBE4A");
    static final Color GAUGE = Color.web("#FAD9D6"), GAUGE_RING = Color.web("#E0705E");
    static final Color SCALAR = Color.web("#FBF0C8"), SCALAR_RING = Color.web("#E2B23C");

    private final List<SmParticle> particles = new ArrayList<>();
    private String selected = "u";
    private String hovered;
    private double x0, y0, cell, gap;

    void setParticles(List<SmParticle> list) {
        particles.clear();
        particles.addAll(list);
    }

    boolean ready() { return !particles.isEmpty(); }

    String selected() { return selected; }

    void select(String id) { if (id != null) selected = id; }

    void hover(String id) { hovered = id; }

    SmParticle particle(String id) {
        for (SmParticle p : particles) if (p.getId().equals(id)) return p;
        return null;
    }

    static Color fill(String family) {
        return switch (family) {
            case "quark" -> QUARK;
            case "lepton" -> LEPTON;
            case "scalar" -> SCALAR;
            default -> GAUGE;
        };
    }

    static Color ring(String family) {
        return switch (family) {
            case "quark" -> QUARK_RING;
            case "lepton" -> LEPTON_RING;
            case "scalar" -> SCALAR_RING;
            default -> GAUGE_RING;
        };
    }

    /** La hauteur occupée par le tableau pour une largeur donnée. */
    static double height(double width) {
        double c = cellSize(width);
        return 26 + 4 * c + 3 * c * .08 + 4;
    }

    private static double cellSize(double width) {
        return Math.min(78, (width - 28) / (5 + 4 * .08));
    }

    /** La particule sous le point (x, y), ou null. */
    String at(double x, double y) {
        for (int row = 0; row < GRID.length; row++) {
            for (int col = 0; col < GRID[row].length; col++) {
                String id = GRID[row][col];
                if (id == null) continue;
                double cx = x0 + col * (cell + gap), cy = y0 + row * (cell + gap);
                if (x >= cx && x <= cx + cell && y >= cy && y <= cy + cell) return id;
            }
        }
        return null;
    }

    /** Le centre à l'écran de la case d'une particule (après le dernier dessin), ou null. */
    double[] centre(String id) {
        for (int row = 0; row < GRID.length; row++)
            for (int col = 0; col < GRID[row].length; col++)
                if (id.equals(GRID[row][col])) return new double[]{x0 + col * (cell + gap) + cell / 2, y0 + row * (cell + gap) + cell / 2};
        return null;
    }

    /** Dessine le tableau en (x, y) sur une largeur `width`. */
    void draw(GraphicsContext g, double x, double y, double width) {
        cell = cellSize(width);
        gap = cell * .08;
        x0 = x + 14;
        y0 = y + 26;
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
        g.setFill(DocStyle.PALE);
        g.fillText(DocStyle.caps("I"), x0 + cell * .45, y0 - 8);
        g.fillText(DocStyle.caps("II"), x0 + (cell + gap) + cell * .42, y0 - 8);
        g.fillText(DocStyle.caps("III"), x0 + 2 * (cell + gap) + cell * .4, y0 - 8);
        g.fillText(DocStyle.caps("forces"), x0 + 3 * (cell + gap) + cell * .2, y0 - 8);
        for (int row = 0; row < GRID.length; row++) {
            for (int col = 0; col < GRID[row].length; col++) {
                String id = GRID[row][col];
                if (id == null) continue;
                SmParticle p = particle(id);
                if (p == null) continue;
                drawCell(g, x0 + col * (cell + gap), y0 + row * (cell + gap), cell, p, id.equals(selected), id.equals(hovered));
            }
        }
        // les familles, en marge droite de la grille
        double right = x0 + 5 * (cell + gap) - gap;
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
        g.setFill(DocStyle.PALE);
        g.setTextAlign(TextAlignment.LEFT);
        g.save();
        g.translate(x0 - 6, y0 + 2 * cell + gap);
        g.rotate(-90);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(DocStyle.caps("quarks"), cell + gap / 2, 0);
        g.fillText(DocStyle.caps("leptons"), -cell - gap / 2, 0);
        g.restore();
        g.setFill(DocStyle.PALE);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(DocStyle.caps("scalaire"), right - cell / 2, y0 + cell + gap + 14);
    }

    private void drawCell(GraphicsContext g, double x, double y, double s, SmParticle p, boolean chosen, boolean over) {
        Color fill = fill(p.getFamily()), ring = ring(p.getFamily());
        g.setFill(fill.deriveColor(0, 1, over ? 1.0 : .97, .96));
        g.fillRoundRect(x, y, s, s, 10, 10);
        if (chosen) {
            g.setStroke(Color.WHITE);
            g.setLineWidth(2.4);
            g.strokeRoundRect(x - 1.5, y - 1.5, s + 3, s + 3, 12, 12);
        }
        double fs = s / 78;
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", 8.2 * fs + 1));
        g.setFill(Color.web("#3B3550"));
        g.fillText(mass(p.getMassMev()), x + 4, y + 10 * fs + 2);
        g.fillText(charge(p.getCharge()), x + 4, y + 20 * fs + 2);
        g.fillText(spin(p.getSpin()), x + 4, y + 30 * fs + 2);
        double r = s * .24, cx = x + s * .6, cy = y + s * .5;
        g.setFill(Color.WHITE.deriveColor(0, 1, 1, .85));
        g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setStroke(ring);
        g.setLineWidth(2);
        g.strokeOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 17 * fs + 2));
        g.setFill(ring.darker());
        g.fillText(p.getSymbol(), cx, cy + 6 * fs + 1);
        g.setFont(Font.font("Segoe UI", 8 * fs + 1.5));
        g.setFill(Color.web("#3B3550"));
        g.fillText(p.getName(), x + s / 2, y + s - 5, s - 6);
    }

    static String mass(double mev) {
        if (mev == 0) return "0";
        if (mev >= 1000) return String.format(Locale.FRANCE, "%.3g GeV", mev / 1000);
        if (mev >= 1) return String.format(Locale.FRANCE, "%.3g MeV", mev);
        return String.format(Locale.FRANCE, "%.3g keV", mev * 1000);
    }

    static String charge(double q) {
        long thirds = Math.round(q * 3);
        if (thirds == 0) return "0";
        if (thirds % 3 == 0) return (thirds > 0 ? "+" : "−") + Math.abs(thirds / 3);
        return (thirds > 0 ? "+" : "−") + Math.abs(thirds) + "/3";
    }

    static String spin(double s) {
        if (Math.abs(s - .5) < 1e-9) return "½";
        return String.format(Locale.FRANCE, "%.0f", s);
    }
}
