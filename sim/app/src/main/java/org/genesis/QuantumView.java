package org.genesis;

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

    /** Décades affichées par l'échelle log : assez pour voir les lobes externes et les nœuds. */
    static final double LOG_DECADES = 4.0;

    private static final double MEASUREMENTS_PER_SECOND = 70.0;
    private static final int MAX_MEASUREMENTS = 900;

    private final Canvas canvas;
    private final GraphicsContext gc;
    private final Random random = new Random(7);

    private Orbital orbital;
    private WritableImage image;
    private WritableImage previousImage;
    private double crossFade = 1.0;
    private double[] cumulative = new double[0];

    private final List<double[]> measurements = new ArrayList<>();   // x, z (fractions), âge
    private double measurementBudget = 0.0;
    private String emptyMessage = "aucun électron : pas de nuage";

    public QuantumView(double width, double height) {
        canvas = new Canvas(width, height);
        gc = canvas.getGraphicsContext2D();
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
                && a.getOccupationList().equals(b.getOccupationList());
    }

    /** Densité -> couleur, en échelle logarithmique sur LOG_DECADES décades. */
    static double logScale(double density) {
        if (density <= 0) {
            return 0;
        }
        return Math.max(0, 1 + Math.log10(density) / LOG_DECADES);
    }

    static Color ramp(double t) {
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

    /**
     * Fonction de répartition de la densité LINÉAIRE : les mesures suivent la
     * vraie probabilité, pas l'échelle d'affichage.
     */
    static double[] buildCumulative(Orbital orbital) {
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

    static int sample(double[] cumulative, double uniform) {
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
            gc.fillText(emptyMessage, width / 2, height / 2);
            gc.setTextBaseline(VPos.BASELINE);
            return;
        }

        double diagramHeight = 48;
        double side = Math.min(width - 8, height - diagramHeight - 30);
        double left = (width - side) / 2;
        double top = 18;

        gc.setImageSmoothing(true);
        if (previousImage != null && crossFade < 1) {
            gc.setGlobalAlpha(1 - crossFade);
            gc.drawImage(previousImage, left, top, side, side);
        }
        gc.setGlobalAlpha(crossFade);
        gc.drawImage(image, left, top, side, side);
        gc.setGlobalAlpha(1.0);

        drawMeasurements(left, top, side);
        drawOverlay(left, top, side);
        drawHundDiagram(width, top + side + 6);

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(Font.font("Segoe UI", 9));
        gc.setFill(Color.web("#64748B"));
        gc.fillText("clair = densité · points = tirages dans la coupe xz · ↑↓ = spins",
                width / 2, top + side + 48);
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
        gc.fillText("orbitale " + title, left + 6, top + 16);
        gc.setFont(Font.font("Consolas", 9));
        gc.setFill(Color.web("#CBD5E1", 0.8));
        gc.fillText("n=" + orbital.getN() + "  l=" + orbital.getL() + " (" + letters.charAt(Math.min(6, orbital.getL()))
                + ")  Z_eff=" + Visuals.fixed(orbital.getZEff(), 2), left + 6, top + 29);
        boolean unresolvedM = orbital.getOrbitalNamesList().contains("m non résolu");
        gc.fillText(orbital.getRadialNodes() + " nœud(s) radial(aux)"
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
        gc.fillText(Visuals.formatLength(length), barRight, barY - 4);

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(Color.web("#94A3B8", 0.8));
        gc.fillText("coupe xz · |ψ|² en échelle log (4 décades)", left + 6, top + side - 6);
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
            boolean outOfSlice = name.contains("y");
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
            gc.fillText(name, x + boxWidth / 2, top + 29);
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
