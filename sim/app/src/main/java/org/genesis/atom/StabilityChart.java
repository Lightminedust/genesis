package org.genesis.atom;

import org.genesis.common.Visuals;

import com.studio.genesis.grpc.DecayChannel;
import com.studio.genesis.grpc.Element;
import com.studio.genesis.grpc.PeriodicTable;
import com.studio.genesis.grpc.Stability;
import com.studio.genesis.grpc.UniverseFrame;

import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * La vallée de stabilité : chaque point est un noyau stable réellement
 * observé dans la nature (N en fonction de Z).
 *
 * Elle rend visible la règle la plus importante du noyau : les éléments
 * légers sont stables avec N ≈ Z, mais plus Z grandit, plus il faut de
 * neutrons pour diluer la répulsion entre protons, et la vallée s'écarte de
 * la diagonale. Le noyau courant y est placé ; s'il sort de la vallée, une
 * flèche montre dans quel sens la désintégration le ramène.
 */
public class StabilityChart {

    private static final Color MUTED = Color.web("#64748B");
    private static final int MAX_Z = 92;
    private static final int MAX_N = 146;
    private static final int INSET_HALF = 6;

    private final Canvas canvas;
    private final GraphicsContext gc;

    private final List<int[]> stableNuclides = new ArrayList<>();
    private UniverseFrame frame;
    private int[] from;          // position avant une désintégration, pour l'animer
    private double moveAge = 1;
    private double clock = 0;

    public StabilityChart(double width, double height) {
        canvas = new Canvas(width, height);
        gc = canvas.getGraphicsContext2D();
    }

    public Canvas getNode() {
        return canvas;
    }

    /** Les nucléides stables viennent de la table périodique servie par le moteur. */
    public void setTable(PeriodicTable table) {
        stableNuclides.clear();
        for (Element element : table.getElementsList()) {
            for (int massNumber : element.getStableIsotopesList()) {
                stableNuclides.add(new int[]{element.getZ(), massNumber - element.getZ()});
            }
        }
    }

    public int stableCount() {
        return stableNuclides.size();
    }

    public void applyFrame(UniverseFrame incoming) {
        boolean decayed = incoming.getEventsList().stream().anyMatch(event -> event.getKind().equals("decay"));
        if (frame != null && decayed) {
            from = new int[]{frame.getAtom().getProtons(), frame.getAtom().getNeutrons()};
            moveAge = 0;
        }
        frame = incoming;
    }

    /** La voie principale de désintégration, d'après les données du moteur. */
    private String mainMode() {
        String mode = "";
        float best = -1;
        for (DecayChannel channel : frame.getNucleus().getChannelsList()) {
            if (channel.getFraction() > best) {
                best = channel.getFraction();
                mode = channel.getMode();
            }
        }
        return mode;
    }

    /** Déplacement (dZ, dN) d'un mode de désintégration : la flèche du graphique. */
    public static int[] decayVector(String mode) {
        return switch (mode) {
            case "beta-" -> new int[]{+1, -1};
            case "beta+", "CE", "beta+ / CE" -> new int[]{-1, +1};
            case "alpha" -> new int[]{-2, -2};
            case "n" -> new int[]{0, -1};     // noyau non lié : un neutron part
            case "p" -> new int[]{-1, 0};     // noyau non lié : un proton part
            case "2n" -> new int[]{0, -2};
            case "2p" -> new int[]{-2, 0};
            case "beta-n" -> new int[]{+1, -2};
            case "beta+p" -> new int[]{-2, +1};
            default -> null;
        };
    }

    public void tick(double delta) {
        clock += delta;
        moveAge = Math.min(1, moveAge + delta / 1.2);
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

        double left = 30;
        double right = width - 10;
        double top = 10;
        double bottom = height - 22;

        // Axes
        gc.setStroke(Color.web("#1E293B"));
        gc.setLineWidth(1);
        gc.strokeLine(left, bottom, right, bottom);
        gc.strokeLine(left, top, left, bottom);
        gc.setFont(Font.font("Consolas", 8));
        gc.setFill(MUTED);
        gc.setTextBaseline(VPos.TOP);
        gc.setTextAlign(TextAlignment.CENTER);
        for (int z = 0; z <= MAX_Z; z += 20) {
            gc.fillText(Integer.toString(z), x(z, left, right), bottom + 3);
        }
        gc.fillText("Z (protons)", (left + right) / 2, bottom + 11);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setTextBaseline(VPos.CENTER);
        for (int n = 0; n <= MAX_N; n += 40) {
            gc.fillText(Integer.toString(n), left - 3, y(n, top, bottom));
        }
        gc.setTextBaseline(VPos.BASELINE);

        // N = Z : ce que ferait un noyau sans répulsion électrique
        gc.setStroke(Color.web("#475569", 0.7));
        gc.setLineDashes(3, 4);
        gc.strokeLine(x(0, left, right), y(0, top, bottom), x(MAX_Z, left, right), y(MAX_Z, top, bottom));
        gc.setLineDashes((double[]) null);
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("N = Z", x(70, left, right), y(62, top, bottom));

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI", 9));
        gc.setFill(Color.web("#34D399"));
        gc.fillText("■ noyau stable réel", left + 6, top + 10);
        gc.setFill(Color.web("#CBD5E1"));
        gc.fillText("◯ ton noyau", left + 6, top + 22);

        gc.setFill(Color.web("#34D399", 0.75));
        for (int[] nuclide : stableNuclides) {
            gc.fillRect(x(nuclide[0], left, right) - 1, y(nuclide[1], top, bottom) - 1, 2.2, 2.2);
        }

        if (frame == null) {
            return;
        }
        drawCurrentNucleus(left, right, top, bottom);
        drawInset(right, bottom);
    }

    private double x(double z, double left, double right) {
        return left + z / MAX_Z * (right - left);
    }

    private double y(double n, double top, double bottom) {
        return bottom - n / MAX_N * (bottom - top);
    }

    private double[] currentPosition() {
        int z = frame.getAtom().getProtons();
        int n = frame.getAtom().getNeutrons();
        if (from == null || moveAge >= 1) {
            return new double[]{z, n};
        }
        double t = Visuals.ease(moveAge);
        return new double[]{from[0] + (z - from[0]) * t, from[1] + (n - from[1]) * t};
    }

    private Color markerColor() {
        if (frame.getNucleus().getUnstable()) {
            return Color.web("#F87171");
        }
        return frame.getAtom().getStability() == Stability.STABLE ? Color.web("#34D399") : MUTED;
    }

    private void drawCurrentNucleus(double left, double right, double top, double bottom) {
        double[] position = currentPosition();
        double px = x(position[0], left, right);
        double py = y(position[1], top, bottom);
        Color color = markerColor();
        double pulse = 5 + 1.5 * Math.sin(clock * 4);
        gc.setStroke(color);
        gc.setLineWidth(1.6);
        gc.strokeOval(px - pulse, py - pulse, pulse * 2, pulse * 2);
        gc.setStroke(color.deriveColor(0, 1, 1, 0.35));
        gc.strokeLine(px, py, px, bottom);
        gc.strokeLine(left, py, px, py);
    }

    /**
     * Loupe sur le voisinage du noyau courant : on y voit les isotopes stables
     * un par un, et la flèche de désintégration case par case.
     *
     * Posée en bas à droite : aucun noyau lourd n'a beaucoup moins de neutrons
     * que de protons, cette région du graphique est vide par nature.
     */
    private void drawInset(double chartRight, double chartBottom) {
        int z = frame.getAtom().getProtons();
        int n = frame.getAtom().getNeutrons();
        // Proportionnée au graphique : trop grande, elle masquait le marqueur
        // des noyaux lourds, qui se trouvent en haut à droite.
        double size = Math.min(96, 0.46 * canvas.getHeight());
        double left = chartRight - size - 4;
        double top = chartBottom - size - 16;
        double cell = size / (2 * INSET_HALF + 1);

        gc.setFill(Color.web("#0B1120", 0.95));
        gc.fillRect(left, top, size, size);
        gc.setStroke(Color.web("#334155"));
        gc.setLineWidth(1);
        gc.strokeRect(left, top, size, size);

        double[] position = currentPosition();
        double centerZ = Math.round(position[0]);
        double centerN = Math.round(position[1]);

        for (int[] nuclide : stableNuclides) {
            double dz = nuclide[0] - centerZ;
            double dn = nuclide[1] - centerN;
            if (Math.abs(dz) <= INSET_HALF && Math.abs(dn) <= INSET_HALF) {
                double cx = left + (dz + INSET_HALF) * cell;
                double cy = top + (INSET_HALF - dn) * cell;
                gc.setFill(Color.web("#34D399", 0.8));
                gc.fillRect(cx + 1, cy + 1, cell - 2, cell - 2);
            }
        }

        double markerX = left + (position[0] - centerZ + INSET_HALF) * cell;
        double markerY = top + (INSET_HALF - (position[1] - centerN)) * cell;
        gc.setStroke(markerColor());
        gc.setLineWidth(2);
        gc.strokeRect(markerX, markerY, cell, cell);

        int[] vector = frame.getNucleus().getUnstable() ? decayVector(mainMode()) : null;
        if (vector != null && moveAge >= 1) {
            double fromX = markerX + cell / 2;
            double fromY = markerY + cell / 2;
            double toX = fromX + vector[0] * cell;
            double toY = fromY - vector[1] * cell;
            double pulse = 0.55 + 0.45 * Math.sin(clock * 4);
            gc.setStroke(Color.web("#FBBF24", pulse));
            gc.setLineWidth(1.8);
            gc.strokeLine(fromX, fromY, toX, toY);
            double angle = Math.atan2(toY - fromY, toX - fromX);
            gc.strokeLine(toX, toY, toX - 6 * Math.cos(angle - 0.5), toY - 6 * Math.sin(angle - 0.5));
            gc.strokeLine(toX, toY, toX - 6 * Math.cos(angle + 0.5), toY - 6 * Math.sin(angle + 0.5));
        }

        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8));
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("loupe  Z=" + z + "  N=" + n, left + 3, top + size + 10);
        if (vector != null) {
            // Au-dessus de la loupe : sous elle, il n'y a la place que pour une ligne.
            gc.setFill(Color.web("#FBBF24"));
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText("désintégration → " + mainMode(), left + size, top - 3);
        }
    }
}
