package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeLabState;
import org.genesis.app.I18n;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * La vignette : la molécule en schéma 2D, comme sur une feuille — sa formule développée et squelettique. Les carbones
 * pris dans la chaîne sont des sommets ; les autres atomes sont écrits à la couleur de leur élément, leurs hydrogènes
 * rassemblés (CH₃ ou H₃C selon le côté, OH, NH₂) — ou, bouton « tous les H », chaque hydrogène dessiné sur sa tige. Les
 * liaisons ont un, deux ou trois traits selon l'indice de Mayer (le second en tirets pour une liaison conjuguée, comme
 * dans le benzène). Une petite molécule (deux atomes lourds au plus) est écrite en entier.
 *
 * Le dessin est celui d'un manuel, pas une projection : sa disposition, calculée une fois pour un enchaînement d'atomes
 * (refaite seulement si une liaison se forme ou se rompt), part de la molécule dans son plan moyen puis se règle —
 * liaisons de même longueur, cycles en polygones réguliers (120° pour six chaînons, 108° pour cinq), 120° hors cycle,
 * 180° le long d'une triple liaison, atomes non liés écartés. Il ne vibre pas avec la dynamique, mais suit la vue 3D :
 * à chaque image, il tourne (et se retourne en miroir si l'on regarde l'autre face) pour coller au mieux aux atomes tels
 * que la caméra les montre ; une boussole x, y, z dit l'orientation. Survoler un atome du schéma le montre en 3D, le
 * cliquer le choisit ; l'atome choisi en 3D est entouré ici. Dessus, en temps réel : les charges partielles fortes (δ),
 * les longueurs des liaisons de l'atome choisi, sa force nette (flèche dorée) ; dessous, une ligne de mesures et une
 * consigne qui suit ce que l'on fait.
 */
final class MoleculeSketch {
    static final double W = 250, H = 236, HEAD = 20, FOOT = 34;
    private static final Color CARD = Color.web("#070D18"), GREY = Color.web("#8FA4C2"), LINE = Color.web("#C9D6E8");
    /** Le bouton « tous les H », dans le titre : x, y, largeur, hauteur. */
    private static final double[] H_BUTTON = {84, 4, 58, 13};

    boolean folded, explicitH;
    private String key = "", layoutKey = "";
    private double[][] plane;
    private double[] centre = new double[3];
    private double[][] sheet = new double[0][];
    private double angle = Double.NaN;
    private boolean mirrored;
    private final List<double[]> hits = new ArrayList<>();

    /** Le plan du schéma : les deux grands axes des positions (u, v), le centre ; gardé tant que `signature` ne change pas. */
    void plane(double[][] positions, String signature) {
        if (signature.equals(key) && plane != null) return;
        key = signature;
        int n = positions.length;
        centre = new double[3];
        for (double[] p : positions) for (int a = 0; a < 3; a++) centre[a] += p[a] / Math.max(1, n);
        double[][] c = new double[3][3];
        for (double[] p : positions) for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) c[a][b] += (p[a] - centre[a]) * (p[b] - centre[b]);
        double[][] axes = new double[3][3];
        AtomMap.eigen(c, axes);
        plane = new double[][]{axes[2], axes[1]};
    }

    /** La position 2D (unités du monde) de chaque atome dans le plan du schéma. */
    double[][] flatten(double[][] positions) {
        double[][] out = new double[positions.length][];
        for (int i = 0; i < positions.length; i++) {
            double[] d = {positions[i][0] - centre[0], positions[i][1] - centre[1], positions[i][2] - centre[2]};
            out[i] = new double[]{d[0] * plane[0][0] + d[1] * plane[0][1] + d[2] * plane[0][2],
                    d[0] * plane[1][0] + d[1] * plane[1][1] + d[2] * plane[1][2]};
        }
        return out;
    }

    /**
     * La disposition du schéma (unité : une liaison) : `start` (la molécule dans son plan moyen) réglée par contraintes —
     * longueur des liaisons, angles (polygone régulier dans un cycle de 3 à 8 chaînons, 120° ou 180° ailleurs),
     * écartement des atomes non liés. `drawn` : les atomes du schéma ; `edges` : ses liaisons {a, b, traits}.
     */
    static double[][] layout(double[][] start, boolean[] drawn, List<int[]> edges) {
        int n = start.length;
        double[][] p = new double[n][];
        double mean = 0;
        for (int[] e : edges) mean += Math.hypot(start[e[1]][0] - start[e[0]][0], start[e[1]][1] - start[e[0]][1]);
        mean = edges.isEmpty() ? 1 : Math.max(1e-6, mean / edges.size());
        for (int i = 0; i < n; i++) p[i] = new double[]{start[i][0] / mean, start[i][1] / mean};
        List<List<Integer>> next = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) next.add(new java.util.ArrayList<>());
        java.util.Map<Long, Integer> strokes = new java.util.HashMap<>();
        for (int[] e : edges) {
            next.get(e[0]).add(e[1]);
            next.get(e[1]).add(e[0]);
            strokes.put(pair(e[0], e[1]), e.length > 2 ? e[2] : 1);
        }
        // Le plus petit cycle de chaque liaison (au plus huit chaînons) : le chemin le plus court qui l'évite.
        List<int[]> rings = new java.util.ArrayList<>();
        for (int[] e : edges) {
            int[] path = shortest(next, e[0], e[1], 7);
            if (path != null) rings.add(path);
        }
        // Les contraintes : {i, k, longueur visée, poids}.
        List<double[]> rules = new java.util.ArrayList<>();
        java.util.Set<Long> near = new java.util.HashSet<>();
        for (int[] e : edges) {
            rules.add(new double[]{e[0], e[1], 1, 1});
            near.add(pair(e[0], e[1]));
        }
        for (int j = 0; j < n; j++) {
            List<Integer> around = next.get(j);
            if (around.size() > 3) continue;                         // quatre voisins : l'écartement suffit
            for (int a = 0; a < around.size(); a++) for (int b = a + 1; b < around.size(); b++) {
                int u = around.get(a), v = around.get(b);
                double angle = Math.toRadians(120);
                int size = 0;
                for (int[] r : rings) if (consecutive(r, u, j, v)) size = size == 0 ? r.length : Math.min(size, r.length);
                if (size > 0) {
                    angle = Math.PI * (size - 2) / size;
                } else if (around.size() == 2) {
                    int su = strokes.getOrDefault(pair(u, j), 1), sv = strokes.getOrDefault(pair(v, j), 1);
                    if (su == 3 || sv == 3 || (su == 2 && sv == 2)) angle = Math.PI;
                }
                rules.add(new double[]{u, v, 2 * Math.sin(angle / 2), .6});
                near.add(pair(u, v));
            }
        }
        for (int step = 0; step < 600; step++) {
            for (double[] r : rules) pull(p, (int) r[0], (int) r[1], r[2], r[3] * .5);
            if (step % 3 != 0) continue;
            for (int i = 0; i < n; i++) for (int k = i + 1; k < n; k++) {
                if (!drawn[i] || !drawn[k] || near.contains(pair(i, k))) continue;
                if (Math.hypot(p[k][0] - p[i][0], p[k][1] - p[i][1]) < 1.5) pull(p, i, k, 1.5, .3);
            }
        }
        return p;
    }

    private static long pair(int a, int b) {
        return Math.min(a, b) * 100_003L + Math.max(a, b);
    }

    /** a, j, b se suivent dans le cycle r (dans un sens ou dans l'autre). */
    private static boolean consecutive(int[] r, int a, int j, int b) {
        for (int k = 0; k < r.length; k++) {
            if (r[k] != j) continue;
            int before = r[(k - 1 + r.length) % r.length], after = r[(k + 1) % r.length];
            return (before == a && after == b) || (before == b && after == a);
        }
        return false;
    }

    /** Le chemin le plus court de a à b sans la liaison a–b, d'au plus `most` liaisons ; null s'il n'y en a pas. */
    private static int[] shortest(List<List<Integer>> next, int a, int b, int most) {
        int n = next.size();
        int[] from = new int[n], depth = new int[n];
        java.util.Arrays.fill(from, -2);
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        queue.add(a);
        from[a] = -1;
        while (!queue.isEmpty()) {
            int x = queue.poll();
            if (depth[x] >= most) continue;
            for (int y : next.get(x)) {
                if ((x == a && y == b) || from[y] != -2) continue;
                from[y] = x;
                depth[y] = depth[x] + 1;
                if (y == b) {
                    int[] path = new int[depth[y] + 1];
                    for (int k = path.length - 1, at = y; k >= 0; k--, at = from[at]) path[k] = at;
                    return path;
                }
                queue.add(y);
            }
        }
        return null;
    }

    /** Rapprocher ou écarter i et k vers la distance `target` (chacun fait la moitié du chemin, fois `weight`). */
    private static void pull(double[][] p, int i, int k, double target, double weight) {
        double dx = p[k][0] - p[i][0], dy = p[k][1] - p[i][1], d = Math.hypot(dx, dy);
        if (d < 1e-9) {
            dx = 1e-3 * (k - i);
            dy = 1e-3;
            d = Math.hypot(dx, dy);
        }
        double c = weight * (d - target) / d / 2;
        p[i][0] += c * dx;
        p[i][1] += c * dy;
        p[k][0] -= c * dx;
        p[k][1] -= c * dy;
    }

    /** Un clic dans la vignette : le titre la plie, le bouton « H » montre ou tait les hydrogènes ; vrai s'il a servi. */
    boolean onTitle(double x, double y) {
        if (y > HEAD) return false;
        if (x >= H_BUTTON[0] && x <= H_BUTTON[0] + H_BUTTON[2]) {
            explicitH = !explicitH;
            return true;
        }
        if (x > H_BUTTON[0]) return false;
        folded = !folded;
        return true;
    }

    /** L'atome sous le pointeur dans le schéma (−1 : aucun) : le survoler le montre en 3D, le cliquer le choisit. */
    int pick(double x, double y) {
        int best = -1;
        double distance = 9;
        for (double[] h : hits) {
            double d = Math.hypot(x - h[0], y - h[1]);
            if (d < distance) {
                distance = d;
                best = (int) h[2];
            }
        }
        return best;
    }

    /** Le nombre d'hydrogènes portés par chaque atome (d'après les liaisons tenues). */
    static int[] hydrogens(int[] z, List<MoleculeScene.Bond> bonds) {
        int[] count = new int[z.length];
        for (MoleculeScene.Bond b : bonds) {
            if (b.a() >= z.length || b.b() >= z.length) continue;
            if (z[b.a()] == 1 && z[b.b()] != 1) count[b.b()]++;
            if (z[b.b()] == 1 && z[b.a()] != 1) count[b.a()]++;
        }
        return count;
    }

    /**
     * L'étiquette d'un atome écrit, ses hydrogènes rassemblés comme sur une feuille : CH₃ (ou H₃C si le groupe part
     * vers la gauche), OH (HO), NH₂ (H₂N) ; le symbole seul sans hydrogène.
     */
    static String label(String symbol, int hydrogens, boolean left) {
        if (hydrogens == 0) return symbol;
        String h = "H" + (hydrogens > 1 ? "₀₁₂₃₄₅₆₇₈₉".charAt(hydrogens) : "");
        return left ? h + symbol : symbol + h;
    }

    /**
     * La rotation (et le miroir) du schéma qui le met au plus près de la vue 3D : celle qui envoie les points du schéma
     * sur les atomes tels que la caméra les montre (moindres carrés, Procuste en deux dimensions). Renvoie {angle,
     * miroir (0/1), avance relative du miroir sur la rotation seule (−1 … 1)}.
     */
    static double[] fit(double[][] sheet, double[][] view, boolean[] use) {
        double[] plain = procrustes(sheet, view, use, false), flipped = procrustes(sheet, view, use, true);
        double lead = (flipped[1] - plain[1]) / Math.max(1e-12, Math.max(flipped[1], plain[1]));
        return lead > 0 ? new double[]{flipped[0], 1, lead} : new double[]{plain[0], 0, lead};
    }

    /** L'angle qui accorde le mieux le schéma (retourné en miroir ou non) à la vue, et la qualité de l'accord. */
    static double[] procrustes(double[][] sheet, double[][] view, boolean[] use, boolean mirror) {
        double sx = 0, sy = 0, vx = 0, vy = 0;
        int n = 0;
        for (int i = 0; i < sheet.length; i++) {
            if (!use[i]) continue;
            sx += sheet[i][0];
            sy += sheet[i][1];
            vx += view[i][0];
            vy += view[i][1];
            n++;
        }
        if (n == 0) return new double[]{0, 0};
        sx /= n;
        sy /= n;
        vx /= n;
        vy /= n;
        double a = 0, b = 0;
        for (int i = 0; i < sheet.length; i++) {
            if (!use[i]) continue;
            double x = (sheet[i][0] - sx) * (mirror ? -1 : 1), y = sheet[i][1] - sy;
            double u = view[i][0] - vx, v = view[i][1] - vy;
            a += x * u + y * v;
            b += x * v - y * u;
        }
        return new double[]{Math.atan2(b, a), Math.hypot(a, b)};      // l'angle ; plus grand : meilleur accord
    }

    void draw(Canvas canvas, MoleculeScene scene, MoleculeLabState s, String instruction) {
        double h = folded ? HEAD + 4 : H;
        if (Math.abs(canvas.getHeight() - h) > .5) canvas.setHeight(h);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, W, H);
        g.setFill(CARD.deriveColor(0, 1, 1, .9));
        g.fillRoundRect(0, 0, W, h, 12, 12);
        g.setStroke(Color.web("#2A3B52", .9));
        g.setLineWidth(1);
        g.strokeRoundRect(.5, .5, W - 1, h - 1, 12, 12);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
        g.setFill(GREY);
        g.fillText(I18n.text("SCHÉMA 2D") + (folded ? "  ▸" : "  ▾"), 10, 14);
        // Le bouton « H » : tous les hydrogènes dessinés, ou rassemblés dans leurs groupes.
        g.setFill(explicitH ? Color.web("#1E3A5C", .95) : Color.web("#0B1626", .9));
        g.fillRoundRect(H_BUTTON[0], H_BUTTON[1], H_BUTTON[2], H_BUTTON[3], 10, 10);
        g.setStroke(explicitH ? Color.web("#5CC8FF", .7) : Color.web("#2A3B52", .9));
        g.setLineWidth(.8);
        g.strokeRoundRect(H_BUTTON[0], H_BUTTON[1], H_BUTTON[2], H_BUTTON[3], 10, 10);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
        g.setFill(explicitH ? Color.web("#EAF2FF") : GREY);
        g.fillText(I18n.text("tous les H"), H_BUTTON[0] + H_BUTTON[2] / 2, H_BUTTON[1] + 10);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFont(Font.font("Segoe UI", 10));
        g.setFill(Color.web("#DCE8F7"));
        g.fillText(s.getCensus(), W - 10, 14);
        hits.clear();
        int n = scene.positions.length;
        if (folded || n == 0) return;
        List<MoleculeScene.Bond> bonds = scene.bondedBonds();
        int heavy = 0;
        for (int v : scene.z) if (v > 1) heavy++;
        boolean small = heavy <= 2;
        int[] hcount = hydrogens(scene.z, bonds);
        // Les atomes du squelette : les lourds (et tout, pour une petite molécule) ; leur disposition, refaite quand
        // l'enchaînement change.
        boolean[] core = new boolean[n];
        for (int i = 0; i < n; i++) core[i] = small || scene.z[i] > 1 || hcountParent(i, scene.z, bonds) < 0;
        List<int[]> edges = new java.util.ArrayList<>();
        StringBuilder signature = new StringBuilder(java.util.Arrays.toString(scene.z));
        for (MoleculeScene.Bond b : bonds) {
            if (b.a() >= n || b.b() >= n || !core[b.a()] || !core[b.b()]) continue;
            edges.add(new int[]{b.a(), b.b(), MoleculeScene.strokes(b.order())});
            signature.append(';').append(Math.min(b.a(), b.b())).append('-').append(Math.max(b.a(), b.b()));
        }
        if (!signature.toString().equals(layoutKey) || sheet.length != n) {
            layoutKey = signature.toString();
            plane(scene.positions, layoutKey);
            sheet = layout(flatten(scene.positions), core, edges);
        }
        // Les hydrogènes rassemblés : leur place au besoin (bouton « H », ou l'un d'eux choisi), dans le plus grand
        // secteur libre autour de leur atome.
        double[][] place = new double[n][];
        for (int i = 0; i < n; i++) if (core[i]) place[i] = sheet[i];
        placeHydrogens(place, core, scene.z, bonds, edges);
        boolean[] shown = new boolean[n];
        for (int i = 0; i < n; i++) shown[i] = core[i] || explicitH || i == scene.selected || i == scene.hovered;
        // L'orientation : le schéma tourne (et se retourne) pour coller à la vue 3D ; l'angle suit en douceur.
        double[] right = scene.camera.right(), up = scene.camera.up();
        if (Math.sqrt(right[0] * right[0] + right[1] * right[1] + right[2] * right[2]) < .5) {
            right = new double[]{1, 0, 0};
            up = new double[]{0, 1, 0};
        }
        double[][] view = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] p = scene.positions[i];
            view[i] = new double[]{p[0] * right[0] + p[1] * right[1] + p[2] * right[2], p[0] * up[0] + p[1] * up[1] + p[2] * up[2]};
        }
        // Le miroir ne bascule que s'il accorde nettement mieux (sinon il hésiterait quand la molécule est de profil).
        double[] fitted = fit(place, view, core);
        if ((fitted[1] > .5) != mirrored && Math.abs(fitted[2]) > .15) mirrored = fitted[1] > .5;
        double target = procrustes(place, view, core, mirrored)[0];
        if (Double.isNaN(angle)) angle = target;
        angle += .35 * Math.IEEEremainder(target - angle, 2 * Math.PI);
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double cxs = 0, cys = 0, radius = .5;
        int counted = 0;
        for (int i = 0; i < n; i++) {
            if (!core[i]) continue;
            cxs += place[i][0];
            cys += place[i][1];
            counted++;
        }
        cxs /= Math.max(1, counted);
        cys /= Math.max(1, counted);
        for (int i = 0; i < n; i++) {
            if (place[i] == null || !(core[i] || shown[i])) continue;
            radius = Math.max(radius, Math.hypot(place[i][0] - cxs, place[i][1] - cys) + (core[i] ? .35 : .2));
        }
        double top = HEAD + 6, bottom = H - FOOT - 6, left = 10, right2 = W - 10;
        double scale = Math.min(30, Math.min(right2 - left, bottom - top) / 2 / radius);
        double ox = (left + right2) / 2, oy = (top + bottom) / 2;
        double[][] xy = new double[n][];
        for (int i = 0; i < n; i++) {
            if (place[i] == null) continue;
            double x = (place[i][0] - cxs) * (mirrored ? -1 : 1), y = place[i][1] - cys;
            xy[i] = new double[]{ox + scale * (cos * x - sin * y), oy - scale * (sin * x + cos * y)};
        }
        int selected = scene.selected;
        // Les liaisons : 1, 2 ou 3 traits ; plus fines vers un hydrogène ; raccourcies devant un atome écrit.
        g.setLineCap(StrokeLineCap.ROUND);
        for (MoleculeScene.Bond b : bonds) {
            if (b.a() >= n || b.b() >= n || !shown[b.a()] || !shown[b.b()] || xy[b.a()] == null || xy[b.b()] == null) continue;
            double[] p = xy[b.a()], q = xy[b.b()];
            double dx = q[0] - p[0], dy = q[1] - p[1], len = Math.hypot(dx, dy);
            if (len < 1) continue;
            boolean toH = scene.z[b.a()] == 1 || scene.z[b.b()] == 1;
            double ux = dx / len, uy = dy / len;
            double cutA = written(b.a(), scene.z, bonds, core, small) ? 7 : 0, cutB = written(b.b(), scene.z, bonds, core, small) ? 7 : 0;
            int strokes = toH ? 1 : MoleculeScene.strokes(b.order());
            boolean mine = b.a() == selected || b.b() == selected;
            for (int k = 0; k < strokes; k++) {
                double offset = strokes == 1 ? 0 : strokes == 2 ? (k - .5) * 3.4 : (k - 1) * 3;
                boolean dashed = strokes == 2 && k == 1 && MoleculeScene.partial(b.order());
                g.setStroke(mine ? Color.web("#FFE07A", .95) : LINE.deriveColor(0, 1, toH ? .8 : 1, toH ? .6 : .85));
                g.setLineWidth(toH ? .9 : 1.3);
                if (dashed) g.setLineDashes(2.5, 2.5);
                g.strokeLine(p[0] + ux * cutA - uy * offset, p[1] + uy * cutA + ux * offset,
                        q[0] - ux * cutB - uy * offset, q[1] - uy * cutB + ux * offset);
                g.setLineDashes();
            }
            if (mine) {
                g.setFont(Font.font("Segoe UI", 8));
                g.setTextAlign(TextAlignment.CENTER);
                g.setFill(Color.web("#FFE07A", .9));
                g.fillText(MoleculeFormat.decimal(length(scene.positions[b.a()], scene.positions[b.b()]), 2),
                        (p[0] + q[0]) / 2 + uy * 8, (p[1] + q[1]) / 2 - ux * 8 + 3);
            }
        }
        // Les atomes : écrits avec leurs hydrogènes (CH₃, OH…) ou en sommet ; la charge forte, le choix, le survol.
        for (int i = 0; i < n; i++) {
            if (!shown[i] || xy[i] == null) continue;
            double x = xy[i][0], y = xy[i][1];
            boolean write = written(i, scene.z, bonds, core, small) || !core[i];
            if (write) {
                int folded = explicitH || small ? 0 : hcount[i];
                boolean leftward = false;
                if (folded > 0) {
                    double sum = 0;
                    for (int[] e : edges) {
                        int other = e[0] == i ? e[1] : e[1] == i ? e[0] : -1;
                        if (other >= 0 && xy[other] != null) sum += xy[other][0] - x;
                    }
                    leftward = sum > 0;                                  // le reste de la molécule est à droite
                }
                String text = label(i < scene.symbols.length ? scene.symbols[i] : "?", folded, leftward);
                g.setFont(Font.font("Segoe UI", FontWeight.BOLD, scene.z[i] == 1 ? 9 : 10.5));
                g.setTextAlign(folded > 0 ? (leftward ? TextAlignment.RIGHT : TextAlignment.LEFT) : TextAlignment.CENTER);
                g.setFill(MoleculeScene.elementColor(scene.z[i]).deriveColor(0, .8, 1.25, 1));
                double tx = folded > 0 ? (leftward ? x + 4 : x - 4) : x;
                g.fillText(text, tx, y + 4);
            }
            hits.add(new double[]{x, y, i});
            double q = i < scene.charges.length ? scene.charges[i] : 0;
            if (write && scene.z[i] > 1 && Math.abs(q) >= .25) {
                float[] c = MoleculeScene.chargeColour(q);
                g.setFont(Font.font("Segoe UI", 8));
                g.setTextAlign(TextAlignment.LEFT);
                g.setFill(Color.color(c[0], c[1], c[2]));
                g.fillText(q < 0 ? "δ−" : "δ+", x + 5, y - 6);
            }
            if (i == selected || i == scene.hovered || i == scene.dragged) {
                g.setStroke(i == selected ? Color.web("#FFE07A", .9) : Color.web("#FFFFFF", .6));
                g.setLineWidth(1);
                g.strokeOval(x - 7.5, y - 7.5, 15, 15);
            }
        }
        // La force nette de l'atome choisi, telle que la caméra la montre.
        if (selected >= 0 && selected < n && xy[selected] != null && scene.netForce != null && selected < scene.netForce.length
                && scene.netForce[selected] != null) {
            double[] f = scene.netForce[selected];
            double fx = f[0] * right[0] + f[1] * right[1] + f[2] * right[2], fy = f[0] * up[0] + f[1] * up[1] + f[2] * up[2];
            double fl = Math.hypot(fx, fy);
            if (fl > .02) {
                double length = 9 + 14 * Math.min(1, Math.log10(1 + fl / .05) / 2);
                double x0 = xy[selected][0], y0 = xy[selected][1];
                MoleculeScene.arrow(g, x0 + fx / fl * 9, y0 - fy / fl * 9, x0 + fx / fl * (9 + length), y0 - fy / fl * (9 + length),
                        Color.web("#FFE07A", .95), 1.6);
            }
        }
        compass(g, W - 26, H - FOOT - 18, right, up);
        // Dessous : une ligne de mesures, puis la consigne.
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", 9.5));
        g.setFill(Color.web("#C6D4E8"));
        g.fillText(measures(s, bonds.size()), 10, H - FOOT + 12);
        g.setFont(Font.font("Segoe UI", javafx.scene.text.FontPosture.ITALIC, 9));
        g.setFill(GREY);
        g.fillText(instruction, 10, H - FOOT + 26);
    }

    /** La boussole : les axes x, y, z du monde tels que la caméra les voit (le schéma est tourné comme la vue). */
    private static void compass(GraphicsContext g, double x, double y, double[] right, double[] up) {
        String[] names = {"x", "y", "z"};
        Color[] colours = {Color.web("#FF7A6B"), Color.web("#7EE0A1"), Color.web("#6FB6FF")};
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 7.5));
        g.setTextAlign(TextAlignment.CENTER);
        for (int k = 0; k < 3; k++) {
            double ex = right[k] * 11, ey = -up[k] * 11;
            g.setStroke(colours[k].deriveColor(0, 1, 1, .8));
            g.setLineWidth(1.1);
            g.strokeLine(x, y, x + ex, y + ey);
            g.setFill(colours[k]);
            g.fillText(names[k], x + ex * 1.35, y + ey * 1.35 + 2.5);
        }
    }

    /** L'atome lourd auquel tient un hydrogène (−1 : aucun, ou ce n'est pas un hydrogène). */
    private static int hcountParent(int i, int[] z, List<MoleculeScene.Bond> bonds) {
        if (z[i] != 1) return -1;
        for (MoleculeScene.Bond b : bonds) {
            int other = b.a() == i ? b.b() : b.b() == i ? b.a() : -1;
            if (other >= 0 && other < z.length && z[other] != 1) return other;
        }
        return -1;
    }

    /** Un atome du squelette s'écrit-il (lettres) plutôt qu'en sommet ? Tout sauf un carbone pris entre deux atomes lourds. */
    private static boolean written(int i, int[] z, List<MoleculeScene.Bond> bonds, boolean[] core, boolean small) {
        if (!core[i]) return true;
        if (small || z[i] != 6) return true;
        int heavy = 0;
        for (MoleculeScene.Bond b : bonds) {
            int other = b.a() == i ? b.b() : b.b() == i ? b.a() : -1;
            if (other >= 0 && other < z.length && z[other] != 1) heavy++;
        }
        return heavy <= 1;                                             // un méthyle, un carbone seul : CH₃, CH₄
    }

    /** La place de chaque hydrogène rassemblé : dans le plus grand secteur libre autour de son atome, à 0,75 liaison. */
    private static void placeHydrogens(double[][] place, boolean[] core, int[] z, List<MoleculeScene.Bond> bonds, List<int[]> edges) {
        java.util.Map<Integer, List<Integer>> carried = new java.util.HashMap<>();
        for (int i = 0; i < z.length; i++) {
            if (core[i]) continue;
            int parent = hcountParent(i, z, bonds);
            if (parent >= 0 && place[parent] != null) carried.computeIfAbsent(parent, k -> new java.util.ArrayList<>()).add(i);
        }
        for (var entry : carried.entrySet()) {
            int parent = entry.getKey();
            List<Double> taken = new java.util.ArrayList<>();
            for (int[] e : edges) {
                int other = e[0] == parent ? e[1] : e[1] == parent ? e[0] : -1;
                if (other >= 0 && place[other] != null) {
                    taken.add(Math.atan2(place[other][1] - place[parent][1], place[other][0] - place[parent][0]));
                }
            }
            double from, span;
            if (taken.isEmpty()) {
                from = 0;
                span = 2 * Math.PI;
            } else {
                java.util.Collections.sort(taken);
                from = taken.get(taken.size() - 1);
                span = taken.get(0) + 2 * Math.PI - from;
                for (int k = 1; k < taken.size(); k++) {
                    double gap = taken.get(k) - taken.get(k - 1);
                    if (gap > span) {
                        span = gap;
                        from = taken.get(k - 1);
                    }
                }
            }
            List<Integer> hs = entry.getValue();
            for (int k = 0; k < hs.size(); k++) {
                double a = taken.isEmpty() ? 2 * Math.PI * k / hs.size() : from + span * (k + 1) / (hs.size() + 1);
                place[hs.get(k)] = new double[]{place[parent][0] + .75 * Math.cos(a), place[parent][1] + .75 * Math.sin(a)};
            }
        }
    }

    private static double length(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    /** Les mesures du moment : la cohésion, la température des noyaux, le dernier événement s'il est récent. */
    static String measures(MoleculeLabState s, int bonds) {
        if (s.getEventsCount() > 0) {
            var last = s.getEvents(s.getEventsCount() - 1);
            if (s.getTimeFs() - last.getTimeFs() < 200) return MoleculeFormat.time(last.getTimeFs()) + "  " + I18n.text(last.getText());
        }
        return I18n.text("cohésion") + " " + MoleculeFormat.decimal(-s.getBinding(), 2) + " eV  ·  "
                + MoleculeFormat.decimal(s.getTemperature(), 0) + " K  ·  " + bonds + " " + I18n.text("liaisons");
    }
}
