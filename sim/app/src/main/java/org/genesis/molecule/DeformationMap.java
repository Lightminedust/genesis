package org.genesis.molecule;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import org.genesis.common.LabCamera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * La vue Δρ : où les électrons sont allés quand les atomes se sont liés. La densité de déformation
 * Δρ = ρ(molécule) − Σ ρ(atomes libres, sphériques), calculée par le moteur, est vue en transparence :
 * Δσ(pixel) = ∫ Δρ ds le long du regard (électrons par Å²), comme le halo de la vue ρ l'est pour la densité. Elle est
 * tracée en courbes de niveau, la convention des cartes de déformation : trait plein où la molécule a gagné des
 * électrons, tirets où elle en a perdu ; chaque courbe double la précédente ({@link #LEVELS}). La courbe zéro n'est pas
 * tracée : loin des noyaux, Δσ y frôle zéro et elle errerait au gré du bruit. Gains et pertes se compensent exactement
 * (∫ Δσ = 0 : la molécule a les électrons de ses atomes).
 */
final class DeformationMap {
    /** Les niveaux (e/Å²), positifs et négatifs : chacun double le précédent. */
    static final double[] LEVELS = {.025, .05, .1, .2, .4, .8, 1.6};
    static final Color GAIN = Color.web("#7FE3FF"), LOSS = Color.web("#FF9A5C");
    private static final float[] GAIN_TINT = {.32f, .82f, 1f}, LOSS_TINT = {1f, .5f, .22f};
    private static final double FINE_CELL = 3, COARSE_CELL = 6;

    private String key = "";
    private boolean sharp;
    private double cell, x0, y0;
    private double[] anchor = new double[2];
    private int cols, rows;
    private float[] field = new float[0];
    private final List<List<double[]>> gains = new ArrayList<>(), losses = new ArrayList<>();
    private WritableImage tint;

    /** À refaire au prochain dessin (nouvelle grille). */
    void invalidate() {
        key = "";
    }

    /**
     * La carte de la grille Δρ (e/Å³) vue par la caméra. Elle est recalculée quand la grille change, ou quand on tourne
     * la vue — sur un réseau deux fois plus lâche le temps du geste, puis le fin ; quand seul le centre suivi par la
     * caméra bouge (la dynamique), elle se décale avec lui.
     */
    void render(GraphicsContext g, GraphicsContext near, DensityGrid grid, LabCamera camera, double w, double h) {
        String view = String.format(Locale.ROOT, "|%.0f|%.0f|%.4f|%.4f|%.4f", w, h, camera.yaw, camera.pitch, camera.zoom);
        String k = grid.version + ":" + System.identityHashCode(grid) + view;
        if (!k.equals(key)) {
            boolean turning = !key.isEmpty() && !key.endsWith(view);
            compute(grid, camera, w, h, turning ? COARSE_CELL : FINE_CELL);
            key = k;
            sharp = !turning;
        } else if (!sharp) {
            compute(grid, camera, w, h, FINE_CELL);
            sharp = true;
        }
        double[] now = anchor(grid, camera);
        double dx = now == null ? 0 : now[0] - anchor[0], dy = now == null ? 0 : now[1] - anchor[1];
        g.save();
        near.save();
        g.translate(dx, dy);
        near.translate(dx, dy);
        draw(g, near);
        g.restore();
        near.restore();
    }

    /** Où tombe à l'écran le centre de la boîte de la grille (null derrière la caméra). */
    private static double[] anchor(DensityGrid grid, LabCamera camera) {
        return camera.project((grid.ox + grid.maxX()) / 2, (grid.oy + grid.maxY()) / 2, (grid.oz + grid.maxZ()) / 2);
    }

    private void compute(DensityGrid grid, LabCamera camera, double w, double h, double step) {
        // Le cadre : l'écran couvert par la boîte de la grille.
        double minX = w, minY = h, maxX = 0, maxY = 0;
        boolean behind = false;
        for (int c = 0; c < 8; c++) {
            double[] p = camera.project((c & 1) == 0 ? grid.ox : grid.maxX(), (c & 2) == 0 ? grid.oy : grid.maxY(),
                    (c & 4) == 0 ? grid.oz : grid.maxZ());
            if (p == null) {
                behind = true;
                continue;
            }
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        if (behind) {
            minX = 0;
            minY = 0;
            maxX = w;
            maxY = h;
        }
        minX = Math.max(0, minX - step);
        minY = Math.max(0, minY - step);
        maxX = Math.min(w, maxX + step);
        maxY = Math.min(h, maxY + step);
        cell = step;
        x0 = minX;
        y0 = minY;
        cols = Math.max(2, (int) Math.ceil((maxX - minX) / step) + 1);
        rows = Math.max(2, (int) Math.ceil((maxY - minY) / step) + 1);
        float[] f = new float[cols * rows];
        IntStream.range(0, rows).parallel().forEach(j -> {
            for (int i = 0; i < cols; i++) f[j * cols + i] = (float) column(grid, camera.ray(x0 + i * step, y0 + j * step));
        });
        field = f;
        gains.clear();
        losses.clear();
        for (double level : LEVELS) {
            gains.add(toScreen(contour(f, cols, rows, level)));
            losses.add(toScreen(contour(f, cols, rows, -level)));
        }
        tint = tint(f);
        double[] a = anchor(grid, camera);
        anchor = a == null ? new double[2] : a;
    }

    /** Δσ le long d'un rayon {origine, direction unitaire} : l'intégrale de Δρ dans la boîte de la grille (e/Å²). */
    static double column(DensityGrid grid, double[] ray) {
        double near = 0, far = Double.MAX_VALUE;
        double[] low = {grid.ox, grid.oy, grid.oz}, high = {grid.maxX(), grid.maxY(), grid.maxZ()};
        for (int a = 0; a < 3; a++) {
            double o = ray[a], d = ray[3 + a];
            if (Math.abs(d) < 1e-12) {
                if (o < low[a] || o > high[a]) return 0;
                continue;
            }
            double t1 = (low[a] - o) / d, t2 = (high[a] - o) / d;
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
        }
        if (far <= near) return 0;
        double ds = grid.spacing * .5, sum = 0;
        int n = (int) Math.ceil((far - near) / ds);
        ds = (far - near) / n;
        for (int s = 0; s < n; s++) {
            double t = near + (s + .5) * ds;
            sum += grid.sample(ray[0] + ray[3] * t, ray[1] + ray[4] * t, ray[2] + ray[5] * t);
        }
        return sum * ds;
    }

    /**
     * Les courbes de niveau `level` d'un champ sur un réseau cols × rows (carrés en marche, points de passage
     * interpolés sur les arêtes, cols carrés ambigus tranchés par la valeur au centre), chaînées en polylignes
     * {x0, y0, x1, y1, …} en coordonnées du réseau ; une courbe fermée répète son premier point.
     */
    static List<double[]> contour(float[] f, int cols, int rows, double level) {
        int horizontal = rows * (cols - 1), edges = horizontal + (rows - 1) * cols;
        double[] px = new double[edges], py = new double[edges];
        int[] first = new int[edges], second = new int[edges];
        Arrays.fill(first, -1);
        Arrays.fill(second, -1);
        for (int j = 0; j < rows - 1; j++) {
            for (int i = 0; i < cols - 1; i++) {
                double a = f[j * cols + i], b = f[j * cols + i + 1], c = f[(j + 1) * cols + i + 1], d = f[(j + 1) * cols + i];
                int index = (a > level ? 8 : 0) | (b > level ? 4 : 0) | (c > level ? 2 : 0) | (d > level ? 1 : 0);
                if (index == 0 || index == 15) continue;
                // Les arêtes du carré : haut, droite, bas, gauche ; leur numéro, et le point où le niveau les coupe.
                int top = j * (cols - 1) + i, bottom = (j + 1) * (cols - 1) + i;
                int left = horizontal + j * cols + i, right = horizontal + j * cols + i + 1;
                px[top] = i + (level - a) / (b - a);
                py[top] = j;
                px[bottom] = i + (level - d) / (c - d);
                py[bottom] = j + 1;
                px[left] = i;
                py[left] = j + (level - a) / (d - a);
                px[right] = i + 1;
                py[right] = j + (level - b) / (c - b);
                boolean centre = (a + b + c + d) / 4 > level;
                switch (index) {
                    case 1, 14 -> link(left, bottom, first, second);
                    case 2, 13 -> link(bottom, right, first, second);
                    case 3, 12 -> link(left, right, first, second);
                    case 4, 11 -> link(top, right, first, second);
                    case 6, 9 -> link(top, bottom, first, second);
                    case 7, 8 -> link(top, left, first, second);
                    case 5 -> {
                        if (centre) {
                            link(top, left, first, second);
                            link(bottom, right, first, second);
                        } else {
                            link(top, right, first, second);
                            link(left, bottom, first, second);
                        }
                    }
                    case 10 -> {
                        if (centre) {
                            link(top, right, first, second);
                            link(left, bottom, first, second);
                        } else {
                            link(top, left, first, second);
                            link(bottom, right, first, second);
                        }
                    }
                    default -> { }
                }
            }
        }
        // Le chaînage : d'abord les courbes ouvertes (elles finissent au bord), puis les boucles.
        boolean[] used = new boolean[edges];
        List<double[]> lines = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int e = 0; e < edges; e++) {
                if (used[e] || first[e] < 0) continue;
                boolean end = second[e] < 0;
                if (pass == 0 && !end) continue;
                List<Integer> chain = new ArrayList<>();
                int previous = -1, current = e;
                while (current >= 0 && !used[current]) {
                    used[current] = true;
                    chain.add(current);
                    int next = first[current] != previous ? first[current] : second[current];
                    if (next == previous && second[current] == previous) next = -1;
                    previous = current;
                    current = next;
                }
                boolean closed = current == e && chain.size() > 2;
                double[] line = new double[2 * (chain.size() + (closed ? 1 : 0))];
                for (int k = 0; k < chain.size(); k++) {
                    line[2 * k] = px[chain.get(k)];
                    line[2 * k + 1] = py[chain.get(k)];
                }
                if (closed) {
                    line[line.length - 2] = line[0];
                    line[line.length - 1] = line[1];
                }
                if (chain.size() > 1) lines.add(line);
            }
        }
        return lines;
    }

    private static void link(int a, int b, int[] first, int[] second) {
        if (first[a] < 0) first[a] = b;
        else second[a] = b;
        if (first[b] < 0) first[b] = a;
        else second[b] = a;
    }

    private List<double[]> toScreen(List<double[]> lines) {
        for (double[] line : lines) {
            for (int k = 0; k < line.length; k += 2) {
                line[k] = x0 + line[k] * cell;
                line[k + 1] = y0 + line[k + 1] * cell;
            }
        }
        return lines;
    }

    /** Un lavis à peine teinté sous les courbes, pour lire d'un coup d'œil où l'on gagne et où l'on perd. */
    private WritableImage tint(float[] f) {
        int[] pixels = new int[cols * rows];
        double span = Math.log(LEVELS[LEVELS.length - 1] / LEVELS[0]);
        for (int i = 0; i < pixels.length; i++) {
            double v = f[i], m = Math.abs(v);
            if (m < 1e-6) continue;
            double t = m < LEVELS[0] ? .18 * m / LEVELS[0] : .18 + .82 * Math.min(1, Math.log(m / LEVELS[0]) / span);
            float[] c = v > 0 ? GAIN_TINT : LOSS_TINT;
            int alpha = (int) Math.round(255 * .2 * t);
            pixels[i] = alpha << 24 | (int) (255 * c[0]) << 16 | (int) (255 * c[1]) << 8 | (int) (255 * c[2]);
        }
        WritableImage image = new WritableImage(cols, rows);
        image.getPixelWriter().setPixels(0, 0, cols, rows, PixelFormat.getIntArgbInstance(), pixels, 0, cols);
        return image;
    }

    private void draw(GraphicsContext g, GraphicsContext near) {
        if (tint != null) {
            g.setImageSmoothing(true);
            g.drawImage(tint, x0 - cell / 2, y0 - cell / 2, cols * cell, rows * cell);
        }
        for (GraphicsContext layer : new GraphicsContext[]{g, near}) {
            layer.setLineCap(StrokeLineCap.ROUND);
            layer.setLineJoin(StrokeLineJoin.ROUND);
        }
        for (int k = 0; k < LEVELS.length; k++) {
            double alpha = .42 + .08 * k, width = 1.05 + .06 * k;
            g.setLineWidth(width);
            g.setLineDashes();
            g.setStroke(GAIN.deriveColor(0, 1, 1, alpha));
            for (double[] line : gains.get(k)) stroke(g, line);
            g.setLineDashes(4.5, 3.5);
            g.setStroke(LOSS.deriveColor(0, 1, 1, alpha));
            for (double[] line : losses.get(k)) stroke(g, line);
            // Une lueur douce sur les courbes intérieures, là où la déformation est la plus forte.
            if (k >= 3) {
                near.setLineWidth(width + .6);
                near.setLineDashes();
                near.setStroke(GAIN.deriveColor(0, 1, 1, .1 * (k - 2)));
                for (double[] line : gains.get(k)) stroke(near, line);
                near.setLineDashes(4.5, 3.5);
                near.setStroke(LOSS.deriveColor(0, 1, 1, .1 * (k - 2)));
                for (double[] line : losses.get(k)) stroke(near, line);
            }
        }
        g.setLineDashes();
        near.setLineDashes();
    }

    /** Une polyligne lissée : des arcs paraboliques par les milieux de ses segments. */
    private static void stroke(GraphicsContext g, double[] line) {
        int n = line.length / 2;
        if (n < 2) return;
        g.beginPath();
        boolean closed = n > 2 && line[0] == line[2 * n - 2] && line[1] == line[2 * n - 1];
        if (closed) {
            double mx = (line[0] + line[2]) / 2, my = (line[1] + line[3]) / 2;
            g.moveTo(mx, my);
            for (int k = 1; k < n - 1; k++) {
                g.quadraticCurveTo(line[2 * k], line[2 * k + 1], (line[2 * k] + line[2 * k + 2]) / 2, (line[2 * k + 1] + line[2 * k + 3]) / 2);
            }
            g.quadraticCurveTo(line[0], line[1], mx, my);
            g.closePath();
        } else {
            g.moveTo(line[0], line[1]);
            for (int k = 1; k < n - 1; k++) {
                g.quadraticCurveTo(line[2 * k], line[2 * k + 1], (line[2 * k] + line[2 * k + 2]) / 2, (line[2 * k + 1] + line[2 * k + 3]) / 2);
            }
            g.lineTo(line[2 * n - 2], line[2 * n - 1]);
        }
        g.stroke();
    }
}
