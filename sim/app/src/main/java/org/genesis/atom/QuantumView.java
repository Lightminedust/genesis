package org.genesis.atom;

import org.genesis.app.I18n;
import org.genesis.common.Visuals;

import com.studio.genesis.grpc.Orbital;
import com.studio.genesis.grpc.UniverseFrame;

import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Écran annexe : l'orbitale de valence telle que la décrit la mécanique
 * quantique, pour l'élément courant.
 *
 * L'image est une coupe de |psi|² dans le plan xz, calculée par le moteur
 * (fonction radiale × harmonique sphérique, charge effective de Slater,
 * remplissage de Hund). Elle change réellement d'un élément à l'autre :
 * sphère pour s, lobes pour p, trèfle pour d ; anneaux sombres aux nœuds
 * radiaux ; taille qui suit la charge effective.
 *
 * Les points blancs sont des tirages conditionnés à la coupe xz, selon sa
 * densité |psi|² ; ils ne sont pas la projection de mesures 3D. Ils s'accumulent comme
 * sur une plaque photographique, et c'est leur accumulation qui dessine
 * l'orbitale.
 */
public class QuantumView {

    /** Rampe « inferno », lisible sur fond noir et monotone en luminosité. */
    private static final Color[] RAMP = {
            Color.web("#000004"), Color.web("#1B0C41"), Color.web("#4A0C6B"),
            Color.web("#781C6D"), Color.web("#A52C60"), Color.web("#CF4446"),
            Color.web("#ED6925"), Color.web("#FB9A06"), Color.web("#F7D03C"),
            Color.web("#FCFFA4"),
    };

    /** Phase de ψ : bleu pour ψ > 0, orange pour ψ < 0 ; la luminosité suit |ψ|² (même échelle log). */
    private static final Color[] PLUS = {Color.web("#000004"), Color.web("#0B1F4D"), Color.web("#1D4ED8"),
            Color.web("#60A5FA"), Color.web("#DBEAFE")};
    private static final Color[] MINUS = {Color.web("#000004"), Color.web("#4A1405"), Color.web("#C2410C"),
            Color.web("#FB923C"), Color.web("#FFEDD5")};

    /** Orbitales réelles nulles dans le plan xz (elles contiennent y en facteur) : occupées, mais hors de la coupe. */
    private static final java.util.Set<String> OUT_OF_SLICE = java.util.Set.of(
            "p_y", "d_yz", "d_xy", "f_yz²", "f_xyz", "f_y(3x²-y²)");

    /** Décades affichées par l'échelle log : assez pour voir les lobes externes et les nœuds. */
    static final double LOG_DECADES = 4.0;

    private static final double MEASUREMENTS_PER_SECOND = 70.0;
    private static final int MAX_MEASUREMENTS = 900;

    private final Canvas canvas;
    private final GraphicsContext gc;
    private final Random random = new Random(7);

    private Orbital orbital;
    private WritableImage image;
    private WritableImage phaseImage;
    /** Montrer la phase de ψ (une orbitale) au lieu de |ψ|² (toute la sous-couche). */
    private boolean phaseMode;
    private double[] phaseChip;
    private WritableImage previousImage;
    private double crossFade = 1.0;
    private double[] cumulative = new double[0];

    private final List<double[]> measurements = new ArrayList<>();   // x, z (fractions), âge
    private double measurementBudget = 0.0;
    private String emptyMessage = "aucun électron : pas de nuage";

    public QuantumView(double width, double height) {
        canvas = new Canvas(width, height);
        gc = canvas.getGraphicsContext2D();
        canvas.setOnMouseClicked(event -> {
            double[] chip = phaseChip;
            if (chip != null && event.getX() >= chip[0] && event.getX() <= chip[0] + chip[2]
                    && event.getY() >= chip[1] && event.getY() <= chip[1] + chip[3]) {
                phaseMode = !phaseMode;
            }
        });
    }

    public Canvas getNode() {
        return canvas;
    }

    public void applyFrame(UniverseFrame frame) {
        Orbital next = frame.getHasOrbital() ? frame.getOrbital() : null;
        emptyMessage = frame.getAtom().getElectrons() > 0
                ? "densité de cet état non calculée" : "aucun électron : pas de nuage";
        if (sameOrbital(orbital, next)) {
            return;   // un neutron de plus ne change pas le nuage : on garde les mesures
        }
        previousImage = image;
        orbital = next;
        image = next == null ? null : paint(next);
        phaseImage = next == null || next.getPhaseCount() != next.getSize() * next.getSize() ? null : paintPhase(next);
        crossFade = previousImage == null ? 1.0 : 0.0;
        cumulative = next == null ? new double[0] : buildCumulative(next);
        measurements.clear();
    }

    private static boolean sameOrbital(Orbital a, Orbital b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.getLabel().equals(b.getLabel())
                && Math.abs(a.getZEff() - b.getZEff()) < 1e-4
                && a.getOccupationList().equals(b.getOccupationList())
                && a.getPhaseName().equals(b.getPhaseName());
    }

    /** Densité -> couleur, en échelle logarithmique sur LOG_DECADES décades. */
    public static double logScale(double density) {
        if (density <= 0) {
            return 0;
        }
        return Math.max(0, 1 + Math.log10(density) / LOG_DECADES);
    }

    public static Color ramp(double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        double position = clamped * (RAMP.length - 1);
        int index = (int) Math.floor(position);
        if (index >= RAMP.length - 1) {
            return RAMP[RAMP.length - 1];
        }
        return RAMP[index].interpolate(RAMP[index + 1], position - index);
    }

    private static WritableImage paint(Orbital orbital) {
        int size = orbital.getSize();
        WritableImage result = new WritableImage(size, size);
        PixelWriter writer = result.getPixelWriter();
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                writer.setColor(column, row, ramp(logScale(orbital.getDensity(row * size + column))));
            }
        }
        return result;
    }

    /** ψ signé d'une orbitale : la teinte dit le signe, la luminosité |ψ|² sur la même échelle log que la densité. */
    private static WritableImage paintPhase(Orbital orbital) {
        int size = orbital.getSize();
        WritableImage result = new WritableImage(size, size);
        PixelWriter writer = result.getPixelWriter();
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                double psi = orbital.getPhase(row * size + column);
                writer.setColor(column, row, phaseColor(psi));
            }
        }
        return result;
    }

    static Color phaseColor(double psi) {
        Color[] ramp = psi >= 0 ? PLUS : MINUS;
        double t = logScale(psi * psi) * (ramp.length - 1);
        int index = Math.min(ramp.length - 2, (int) Math.floor(t));
        return ramp[index].interpolate(ramp[index + 1], Math.min(1, t - index));
    }

    /**
     * Fonction de répartition de la densité LINÉAIRE : les mesures suivent la
     * vraie probabilité, pas l'échelle d'affichage.
     */
    public static double[] buildCumulative(Orbital orbital) {
        int count = orbital.getDensityCount();
        double[] result = new double[count];
        double total = 0;
        for (int i = 0; i < count; i++) {
            total += Math.max(0, orbital.getDensity(i));
            result[i] = total;
        }
        if (total <= 0) {
            return new double[0];
        }
        for (int i = 0; i < count; i++) {
            result[i] /= total;
        }
        return result;
    }

    public static int sample(double[] cumulative, double uniform) {
        int low = 0;
        int high = cumulative.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (cumulative[middle] < uniform) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    public void tick(double delta) {
        crossFade = Math.min(1.0, crossFade + delta / 0.5);
        if (cumulative.length > 0 && orbital != null) {
            measurementBudget += delta * MEASUREMENTS_PER_SECOND;
            int size = orbital.getSize();
            while (measurementBudget >= 1) {
                measurementBudget -= 1;
                int cell = sample(cumulative, random.nextDouble());
                double fx = ((cell % size) + random.nextDouble()) / size;
                double fz = ((cell / size) + random.nextDouble()) / size;
                measurements.add(new double[]{fx, fz, 0});
            }
            while (measurements.size() > MAX_MEASUREMENTS) {
                measurements.remove(0);
            }
        }
        for (double[] measurement : measurements) {
            measurement[2] += delta;
        }
        render();
    }

    private void render() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        gc.setFill(Color.web("#05070F"));
        gc.fillRect(0, 0, width, height);

        if (orbital == null) {
            gc.setFill(Color.web("#334155"));
            gc.setFont(Font.font("Segoe UI", 11));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            I18n.fillText(gc, emptyMessage, width / 2, height / 2);
            gc.setTextBaseline(VPos.BASELINE);
            return;
        }

        double diagramHeight = 48;
        boolean phaseShown = phaseMode && phaseImage != null;
        double side = Math.min(width - 8, height - diagramHeight - (phaseShown ? 42 : 30));
        double left = (width - side) / 2;
        double top = 18;

        gc.setImageSmoothing(true);
        boolean phase = phaseMode && phaseImage != null;
        if (!phase && previousImage != null && crossFade < 1) {
            gc.setGlobalAlpha(1 - crossFade);
            gc.drawImage(previousImage, left, top, side, side);
        }
        gc.setGlobalAlpha(phase ? 1.0 : crossFade);
        gc.drawImage(phase ? phaseImage : image, left, top, side, side);
        gc.setGlobalAlpha(1.0);

        if (!phase) {
            drawMeasurements(left, top, side);   // les tirages suivent |ψ|² de toute la sous-couche
        }
        drawOverlay(left, top, side);
        drawPhaseChip(left, top, side);
        drawHundDiagram(width, top + side + 6);

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(Font.font("Segoe UI", 9));
        gc.setFill(Color.web("#64748B"));
        if (phase) {
            // Deux lignes : la convention des couleurs, puis l'orbitale choisie et pourquoi.
            I18n.fillText(gc, I18n.text("phase de ψ pour ") + orbital.getPhaseName() + " · " + I18n.text("bleu ψ > 0 · orange ψ < 0"),
                    width / 2, top + side + 48);
            gc.setFill(Color.web("#475569"));
            I18n.fillText(gc, phaseIsLastElectron() ? "l'orbitale du dernier électron placé (Hund)"
                    : "le dernier électron est hors de la coupe : dernière orbitale occupée visible", width / 2, top + side + 60);
        } else {
            I18n.fillText(gc, "clair = densité · points = tirages dans la coupe xz · ↑↓ = spins", width / 2, top + side + 48);
        }
    }

    /** La bascule |ψ|² ↔ phase, en haut à droite de la coupe ; absente si la phase n'est pas définie. */
    private void drawPhaseChip(double left, double top, double side) {
        if (phaseImage == null) {
            phaseChip = null;
            return;
        }
        String text = phaseMode ? "|ψ|²" : "phase ±";
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
        double w = I18n.text(text).length() * 6.2 + 14;
        double x = left + side - w - 6;
        double y = top + 6;
        gc.setFill(Color.web("#0F172A", 0.85));
        gc.fillRoundRect(x, y, w, 18, 9, 9);
        gc.setStroke(Color.web(phaseMode ? "#F59E0B" : "#3B82F6", 0.8));
        gc.setLineWidth(1);
        gc.strokeRoundRect(x + 0.5, y + 0.5, w - 1, 17, 9, 9);
        gc.setFill(Color.web("#E2E8F0"));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        I18n.fillText(gc, text, x + w / 2, y + 9);
        gc.setTextBaseline(VPos.BASELINE);
        phaseChip = new double[]{x, y, w, 18};
    }

    /** L'orbitale de la phase est-elle celle du dernier électron placé (Hund) ? Sinon, celui-ci est hors de la coupe. */
    private boolean phaseIsLastElectron() {
        int electrons = 0;
        for (int count : orbital.getOccupationList()) electrons += count;
        int boxes = orbital.getOrbitalNamesCount();
        return boxes == 0 || electrons == 0
                || orbital.getOrbitalNames((electrons - 1) % boxes).equals(orbital.getPhaseName())
                || orbital.getOrbitalNames(0).equals("m non résolu");
    }

    /** Pour les aperçus et les tests : afficher la phase. */
    public void setPhaseMode(boolean on) {
        phaseMode = on;
    }

    private void drawMeasurements(double left, double top, double side) {
        for (double[] measurement : measurements) {
            double fade = Math.max(0.15, 1 - measurement[2] / 8.0);
            double x = left + measurement[0] * side;
            double y = top + measurement[1] * side;
            gc.setFill(Color.web("#E0F2FE", 0.85 * fade));
            gc.fillRect(x - 0.6, y - 0.6, 1.3, 1.3);
        }
    }

    private void drawOverlay(double left, double top, double side) {
        // Le noyau, au centre : un point, à cette échelle.
        double cx = left + side / 2;
        double cy = top + side / 2;
        gc.setStroke(Color.web("#FFFFFF", 0.5));
        gc.setLineWidth(1);
        gc.strokeLine(cx - 4, cy, cx + 4, cy);
        gc.strokeLine(cx, cy - 4, cx, cy + 4);

        String letters = "spdfghi";
        String title = orbital.getLabel().replaceAll("(\\d+)$", "") + Visuals.superscript(
                Integer.parseInt(orbital.getLabel().replaceAll("^\\d+[spdfghi]", "")));

        // Bandeaux sombres : le texte doit rester lisible sur les zones brillantes.
        gc.setFill(Color.web("#05070F", 0.72));
        gc.fillRect(left, top, side, 46);
        gc.fillRect(left, top + side - 18, side, 18);

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
        gc.setFill(Color.web("#F1F5F9"));
        I18n.fillText(gc, I18n.text("orbitale ") + title, left + 6, top + 16);
        gc.setFont(Font.font("Consolas", 9));
        gc.setFill(Color.web("#CBD5E1", 0.8));
        I18n.fillText(gc, "n=" + orbital.getN() + "  l=" + orbital.getL() + " (" + letters.charAt(Math.min(6, orbital.getL()))
                + ")  Z_eff=" + Visuals.fixed(orbital.getZEff(), 2), left + 6, top + 29);
        boolean unresolvedM = orbital.getOrbitalNamesList().contains("m non résolu");
        I18n.fillText(gc, orbital.getRadialNodes() + I18n.text(" nœud(s) radial(aux)")
                + (unresolvedM ? " · mélange des m" : " · Slater (approx.)"), left + 6, top + 41);

        // Barre d'échelle en picomètres
        double extent = orbital.getExtentPm() * 2 * Visuals.PICOMETRE;
        double metresPerPixel = extent / side;
        double length = Visuals.niceScaleLength(metresPerPixel, side * 0.28);
        double pixels = length / metresPerPixel;
        double barY = top + side - 10;
        double barRight = left + side - 8;
        gc.setStroke(Color.web("#E2E8F0", 0.8));
        gc.setLineWidth(1.4);
        gc.strokeLine(barRight - pixels, barY, barRight, barY);
        gc.setFill(Color.web("#E2E8F0", 0.8));
        gc.setTextAlign(TextAlignment.RIGHT);
        I18n.fillText(gc, Visuals.formatLength(length), barRight, barY - 4);

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(Color.web("#94A3B8", 0.8));
        I18n.fillText(gc, "coupe xz · |ψ|² en échelle log (4 décades)", left + 6, top + side - 6);
    }

    /**
     * Diagramme en cases : une case par orbitale, une flèche par électron.
     * Hund impose de remplir chaque case avant d'apparier ; les orbitales
     * orientées selon y sont occupées mais nulles dans la coupe xz.
     */
    private void drawHundDiagram(double width, double top) {
        int boxes = orbital.getOccupationCount();
        double boxWidth = Math.min(36, (width - 20) / Math.max(1, boxes) - 4);
        double total = boxes * (boxWidth + 4) - 4;
        double x = (width - total) / 2;
        for (int k = 0; k < boxes; k++) {
            int occupancy = orbital.getOccupation(k);
            String name = k < orbital.getOrbitalNamesCount() ? orbital.getOrbitalNames(k) : "";
            boolean outOfSlice = OUT_OF_SLICE.contains(name);
            gc.setStroke(Color.web(outOfSlice ? "#334155" : "#64748B"));
            gc.setLineWidth(1);
            gc.strokeRect(x, top, boxWidth, 18);
            gc.setStroke(Visuals.subshellColor(orbital.getL()));
            gc.setLineWidth(1.6);
            if (occupancy >= 1) {
                drawArrow(x + boxWidth * (occupancy == 2 ? 0.35 : 0.5), top + 3, top + 15, true);
            }
            if (occupancy == 2) {
                drawArrow(x + boxWidth * 0.65, top + 3, top + 15, false);
            }
            gc.setFill(Color.web(outOfSlice ? "#334155" : "#94A3B8"));
            gc.setFont(Font.font("Consolas", 8));
            gc.setTextAlign(TextAlignment.CENTER);
            I18n.fillText(gc, name, x + boxWidth / 2, top + 29);
            x += boxWidth + 4;
        }
    }

    private void drawArrow(double x, double top, double bottom, boolean up) {
        gc.strokeLine(x, top, x, bottom);
        double tip = up ? top : bottom;
        double back = up ? top + 4 : bottom - 4;
        gc.strokeLine(x, tip, x - 3, back);
        gc.strokeLine(x, tip, x + 3, back);
    }
}
