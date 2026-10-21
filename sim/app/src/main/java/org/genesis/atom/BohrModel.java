package org.genesis.atom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

/**
 * Le modèle de Bohr (1913), tel qu'on le dessine dans les livres : des cercles concentriques dans un seul plan, une
 * couche par cercle, les électrons qui tournent. C'est une image historique, pas un calcul : en mécanique quantique
 * l'électron n'a pas de trajectoire (le nuage |ψ|² de la vue normale est la réalité). Le nombre d'électrons par
 * couche, lui, vient du moteur.
 *
 * Lisible jusqu'à l'oganesson : les cercles ne se croisent jamais ; une couche pleine de plus de 8 électrons n'est
 * qu'un anneau lumineux (ses électrons apparaissent au survol) ; au-delà du xénon, le cœur se condense en une sphère
 * étiquetée par son gaz noble. Rien n'apparaît ni ne disparaît d'un coup : un électron gagné arrive de l'extérieur, un
 * électron perdu s'envole, un saut entre couches glisse d'un cercle à l'autre.
 */
final class BohrModel {
    static final String LETTERS = "KLMNOPQ";
    /** Une teinte par couche, du froid (K) au chaud (Q). */
    static final Color[] SHELL_COLORS = {
            Color.web("#7DD3FC"), Color.web("#93C5FD"), Color.web("#A5B4FC"), Color.web("#C4B5FD"),
            Color.web("#F0ABFC"), Color.web("#FDA4AF"), Color.web("#FDBA74")};
    /** Couches des gaz nobles : le cœur qu'on condense. */
    private static final int[][] NOBLE = {{2}, {2, 8}, {2, 8, 8}, {2, 8, 18, 8}, {2, 8, 18, 18, 8},
            {2, 8, 18, 32, 18, 8}, {2, 8, 18, 32, 32, 18, 8}};
    private static final String[] NOBLE_SYMBOL = {"He", "Ne", "Ar", "Kr", "Xe", "Rn", "Og"};
    /** Au-delà de ce nombre d'électrons (le xénon), le cœur se condense toujours : sept cercles pleins seraient illisibles. */
    static final int AUTO_CORE_ABOVE = 54;
    /** Une couche pleine plus peuplée que ça n'est qu'un anneau ; ses électrons se montrent au survol. */
    static final int BEADS_UP_TO = 8;
    private static final int RING_STEPS = 160;
    private static final double MOVE_S = 0.75;

    /** Un électron : sa couche, son angle sur le cercle, et d'où il vient s'il est en train d'arriver ou de sauter. */
    static final class Bead {
        int shell;                  // indice 0 = K
        int fromShell = -1;         // couche quittée (-1 : il arrive de l'extérieur)
        double angle;
        double move = 1;            // 0 → 1 : le trajet vers sa couche
        boolean leaving;
        double life = 1;            // s'éteint en partant
        int slot;
    }

    /** Une ligne de la légende : la couche, ses électrons, montrés un par un ou non. */
    record Entry(int shell, int electrons, boolean condensed, Color color) {
    }

    /** Ce que le dessin rend à la scène : la légende, la couche survolée (-1), le cœur condensé (-1 : aucun). */
    record Result(List<Entry> legend, int hovered, int core) {
    }

    private final List<Bead> beads = new ArrayList<>();
    private final Random random = new Random(11);
    private final double[] phase = new double[7];
    private int shells;
    private double time;
    /** Inclinaison du plan (rad) et rotation dans le plan, tirées de la caméra à chaque image. */
    private double tilt = 0.55;
    private double spin;

    /** Les électrons par couche voulus par le moteur ; `instant` : pas d'animation (nouvel élément chargé). */
    void sync(int[] target, boolean instant) {
        int count = Math.min(7, target.length);
        int[] current = new int[7];
        for (Bead bead : beads) {
            if (!bead.leaving) current[bead.shell]++;
        }
        List<Bead> freed = new ArrayList<>();
        for (int s = 0; s < 7; s++) {
            int want = s < count ? target[s] : 0;
            while (current[s] > want) {
                freed.add(lastOf(s, freed));
                current[s]--;
            }
        }
        for (int s = 0; s < count; s++) {
            while (current[s] < target[s]) {
                Bead bead;
                if (!freed.isEmpty() && !instant) {
                    bead = freed.remove(0);             // un saut : le même électron change de cercle
                    bead.fromShell = bead.shell;
                } else {
                    bead = new Bead();
                    bead.angle = random.nextDouble() * 2 * Math.PI;
                    bead.fromShell = -1;                // gagné : il arrive de loin
                    beads.add(bead);
                }
                bead.shell = s;
                bead.move = instant ? 1 : 0;
                current[s]++;
            }
        }
        for (Bead bead : freed) {
            if (instant) {
                beads.remove(bead);
            } else {
                bead.leaving = true;                    // perdu : il s'envole
                bead.fromShell = bead.shell;
                bead.move = 0;
            }
        }
        shells = 0;
        for (int s = 0; s < count; s++) {
            if (target[s] > 0) shells = s + 1;
        }
        assignSlots();
    }

    private Bead lastOf(int shell, List<Bead> taken) {
        Bead found = null;
        for (Bead bead : beads) {
            if (bead.shell == shell && !bead.leaving && !taken.contains(bead)) found = bead;
        }
        return found;
    }

    /** Chaque électron reçoit sa place sur le cercle, dans l'ordre où il s'y trouve déjà : pas de croisements. */
    private void assignSlots() {
        for (int s = 0; s < 7; s++) {
            int shell = s;
            List<Bead> on = new ArrayList<>(beads.stream().filter(b -> b.shell == shell && !b.leaving).toList());
            on.sort(Comparator.comparingDouble(b -> wrap(b.angle - phase[shell])));
            for (int k = 0; k < on.size(); k++) on.get(k).slot = k;
        }
    }

    int electrons(int shell) {
        int n = 0;
        for (Bead bead : beads) {
            if (bead.shell == shell && !bead.leaving) n++;
        }
        return n;
    }

    void tick(double dt) {
        time += dt;
        for (int s = 0; s < 7; s++) {
            phase[s] += dt * 0.85 / Math.pow(s + 1, 1.25);    // les couches externes tournent plus lentement
        }
        int[] per = new int[7];
        for (Bead bead : beads) {
            if (!bead.leaving) per[bead.shell]++;
        }
        for (Bead bead : beads) {
            bead.move = Math.min(1, bead.move + dt / MOVE_S);
            if (bead.leaving) {
                bead.life -= dt / MOVE_S;
                continue;
            }
            double goal = phase[bead.shell] + slotAngle(bead.slot, per[bead.shell]);
            double gap = wrap(goal - bead.angle + Math.PI) - Math.PI;
            bead.angle += gap * Math.min(1, 4 * dt);
        }
        beads.removeIf(b -> b.leaving && b.life <= 0);
    }

    /** Places régulières ; au-delà de 12, par paires (↑↓) pour garder le cercle lisible. */
    static double slotAngle(int slot, int count) {
        if (count <= 0) return 0;
        if (count <= 12) return 2 * Math.PI * slot / count;
        int pairs = (count + 1) / 2;
        return 2 * Math.PI * (slot / 2) / pairs + (slot % 2 == 0 ? -0.055 : 0.055);
    }

    private static double wrap(double angle) {
        double twoPi = 2 * Math.PI;
        return ((angle % twoPi) + twoPi) % twoPi;
    }

    /**
     * Rayon d'un cercle (fraction du rayon externe), choisi pour la lisibilité : dans le modèle, r ∝ n², ce qui écraserait
     * les couches internes. `first` : la première couche dessinée (au-dessus d'un cœur condensé).
     */
    private double ringRadius(int shell, int first) {
        int total = Math.max(1, shells - first);
        int index = shell - first;
        double inner = first > 0 ? 0.36 : 0.20;           // un cœur condensé occupe le centre
        return total == 1 ? Math.max(inner + 0.25, 0.6) : inner + (1 - inner) * Math.pow((double) index / (total - 1), 0.8);
    }

    /** Un point du plan des cercles, projeté : out = {x écran, y écran, profondeur (> 0 : vers l'observateur)}. */
    private void project(double radius, double angle, double[] out) {
        double u = radius * Math.cos(angle + spin);
        double v = radius * Math.sin(angle + spin);
        out[0] = u;
        out[1] = v * Math.cos(tilt);
        out[2] = v * Math.sin(tilt);
    }

    /** Position d'un électron : sur son cercle, entre deux cercles pendant un saut, au loin s'il arrive ou part. */
    private void beadPosition(Bead bead, int first, double scale, double[] out) {
        double t = ease(bead.move);
        double target = ringRadius(Math.max(first, bead.shell), first) * scale;
        double radius;
        if (bead.leaving) {
            radius = target * (1 + 1.8 * t);
        } else if (t >= 1) {
            radius = target;
        } else if (bead.fromShell >= 0) {
            double from = ringRadius(Math.max(first, bead.fromShell), first) * scale;
            radius = from + (target - from) * t;
        } else {
            radius = target * (2.6 - 1.6 * t);
        }
        project(radius, bead.angle, out);
    }

    private static double ease(double t) {
        return t * t * (3 - 2 * t);
    }

    /** Le cœur de gaz noble à condenser (le plus grand qui laisse au moins un électron dehors), -1 s'il n'y en a pas. */
    static int nobleCore(int electrons) {
        int best = -1;
        for (int i = 0; i < NOBLE.length; i++) {
            int total = 0;
            for (int e : NOBLE[i]) total += e;
            if (total < electrons) best = i;
        }
        return best;
    }

    static String nobleSymbol(int index) {
        return index < 0 ? "" : "[" + NOBLE_SYMBOL[index] + "]";
    }

    static int nobleElectrons(int index) {
        int total = 0;
        if (index >= 0) for (int e : NOBLE[index]) total += e;
        return total;
    }

    /** Les couches du cœur condensé : « K 2 · L 8 · M 18 … », pour le survol. */
    static String nobleShells(int index) {
        StringBuilder out = new StringBuilder();
        if (index >= 0) {
            for (int s = 0; s < NOBLE[index].length; s++) {
                out.append(s == 0 ? "" : " · ").append(LETTERS.charAt(s)).append(' ').append(NOBLE[index][s]);
            }
        }
        return out.toString();
    }

    private int total() {
        int n = 0;
        for (Bead bead : beads) if (!bead.leaving) n++;
        return n;
    }

    /** Le cœur condensé pour ce réglage : forcé par « Valence », automatique au-delà du xénon. */
    int core(boolean valenceOnly) {
        int total = total();
        return valenceOnly || total > AUTO_CORE_ABOVE ? nobleCore(total) : -1;
    }

    /**
     * La première couche dessinée : la plus interne qui garde des électrons hors du cœur condensé (pour l'oganesson et
     * son cœur [Rn] : O, puisque 5f¹⁴ dépasse le radon).
     */
    int firstShell(int core) {
        if (core < 0) return 0;
        for (int s = 0; s < shells; s++) {
            int hidden = s < NOBLE[core].length ? NOBLE[core][s] : 0;
            if (electrons(s) - hidden > 0) return s;
        }
        return Math.max(0, shells - 1);
    }

    /** Un électron qui agit (le plus externe) : sa position à l'écran, relative au centre, pour les photons. */
    double[] actor(double scale, boolean valenceOnly) {
        Bead best = null;
        for (Bead bead : beads) {
            if (!bead.leaving && (best == null || bead.shell >= best.shell)) best = bead;
        }
        if (best == null) return null;
        int core = core(valenceOnly);
        double[] p = new double[3];
        beadPosition(best, firstShell(core), scale, p);
        return new double[]{p[0], p[1]};
    }

    /**
     * Dessine l'atome de Bohr centré en (cx, cy), cercle externe de rayon `scale` pixels. `camera` donne l'inclinaison
     * (tangage) et la rotation dans le plan (lacet) ; `nucleus` dessine le vrai noyau au centre ; (mouseX, mouseY) : le
     * survol, qui montre les électrons d'une couche condensée en anneau.
     */
    Result draw(GraphicsContext gc, Camera3D camera, double cx, double cy, double scale, double nucleusRadius,
                double nucleusFlash, boolean valenceOnly, double mouseX, double mouseY, Runnable nucleus) {
        tilt = Math.max(0.15, Math.min(1.25, 0.55 - (camera.pitch + 0.38) * 0.8));
        spin = camera.yaw;
        int core = core(valenceOnly);
        int first = firstShell(core);
        int[] hidden = new int[7];
        if (core >= 0) {
            for (int s = 0; s < NOBLE[core].length; s++) hidden[s] = NOBLE[core][s];
        }
        int[] per = new int[7];
        for (int s = 0; s < 7; s++) per[s] = electrons(s);

        // Le cercle survolé : on inverse la projection (le plan est seulement incliné).
        int hovered = -1;
        if (mouseX >= 0) {
            double u = mouseX - cx, v = (mouseY - cy) / Math.cos(tilt);
            double r = Math.hypot(u, v), best = 9;
            for (int s = first; s < shells; s++) {
                double gap = Math.abs(r - ringRadius(s, first) * scale);
                if (per[s] - hidden[s] > 0 && gap < best) {
                    best = gap;
                    hovered = s;
                }
            }
            if (core >= 0 && r < ringRadius(first, first) * scale * 0.62) hovered = -2;   // le cœur
        }
        boolean[] beaded = new boolean[7];
        for (int s = 0; s < 7; s++) {
            int visible = per[s] - hidden[s];
            boolean full = per[s] >= 2 * (s + 1) * (s + 1);
            beaded[s] = visible <= BEADS_UP_TO || !full || s == shells - 1 || s == hovered;
        }

        // Derrière (moitié haute du disque), le noyau, puis devant.
        drawRings(gc, cx, cy, scale, first, per, hidden, beaded, hovered, false);
        List<double[]> placed = new ArrayList<>();
        for (Bead bead : beads) {
            boolean inCore = !bead.leaving && bead.slot < hidden[bead.shell] && bead.move >= 1;
            if (inCore || (!bead.leaving && !beaded[bead.shell] && bead.move >= 1)) continue;
            double[] p = new double[3];
            beadPosition(bead, first, scale, p);
            placed.add(new double[]{p[0], p[1], p[2], bead.shell, bead.leaving ? Math.max(0, bead.life) : Math.min(1, 0.3 + bead.move)});
        }
        placed.sort(Comparator.comparingDouble(p -> p[2]));
        for (double[] p : placed) if (p[2] < 0) drawBead(gc, cx + p[0], cy + p[1], p[2] / scale, (int) p[3], p[4]);
        if (core >= 0) drawCore(gc, cx, cy, ringRadius(first, first) * scale * 0.62, core, hovered == -2);
        drawAura(gc, cx, cy, nucleusRadius, nucleusFlash);
        nucleus.run();
        drawRings(gc, cx, cy, scale, first, per, hidden, beaded, hovered, true);
        for (double[] p : placed) if (p[2] >= 0) drawBead(gc, cx + p[0], cy + p[1], p[2] / scale, (int) p[3], p[4]);

        List<Entry> legend = new ArrayList<>();
        for (int s = first; s < shells; s++) {
            int visible = per[s] - hidden[s];
            if (visible > 0) legend.add(new Entry(s, visible, !beaded[s], SHELL_COLORS[s]));
        }
        return new Result(legend, hovered, core);
    }

    private void drawRings(GraphicsContext gc, double cx, double cy, double scale, int first, int[] per, int[] hidden,
                           boolean[] beaded, int hovered, boolean front) {
        double[] a = new double[3], b = new double[3];
        for (int s = first; s < shells; s++) {
            if (per[s] - hidden[s] <= 0) continue;
            Color color = SHELL_COLORS[s];
            boolean valence = s == shells - 1;
            boolean neon = !beaded[s];                       // une couche condensée en anneau : plus lumineuse
            double emphasis = s == hovered ? 1.5 : 1;
            double radius = ringRadius(s, first) * scale;
            for (int k = 0; k < RING_STEPS; k++) {
                project(radius, 2 * Math.PI * k / RING_STEPS, a);
                project(radius, 2 * Math.PI * (k + 1) / RING_STEPS, b);
                double depth = (a[2] + b[2]) / 2;
                if ((depth >= 0) != front) continue;
                double near = 0.6 + 0.4 * Math.max(-1, Math.min(1, depth / Math.max(1, radius)));
                gc.setStroke(color.deriveColor(0, 1, 1, (neon ? 0.16 : 0.06) * near * emphasis));
                gc.setLineWidth(neon ? 9 : 6);
                gc.strokeLine(cx + a[0], cy + a[1], cx + b[0], cy + b[1]);
                gc.setStroke(color.deriveColor(0, 0.9, 1.1, Math.min(1, (neon ? 0.85 : valence ? 0.7 : 0.45) * near * emphasis)));
                gc.setLineWidth(neon ? 1.8 : valence ? 1.5 : 1.0);
                gc.strokeLine(cx + a[0], cy + a[1], cx + b[0], cy + b[1]);
            }
        }
    }

    private static void drawBead(GraphicsContext gc, double x, double y, double depth, int shell, double alpha) {
        Color color = SHELL_COLORS[Math.max(0, Math.min(6, shell))];
        double size = 1 + 0.2 * Math.max(-1, Math.min(1, depth));
        double halo = 10 * size;
        gc.setFill(new RadialGradient(0, 0, x, y, halo, false, CycleMethod.NO_CYCLE,
                new Stop(0, color.deriveColor(0, 0.6, 1.3, 0.7 * alpha)),
                new Stop(0.35, color.deriveColor(0, 1, 1, 0.25 * alpha)),
                new Stop(1, color.deriveColor(0, 1, 1, 0))));
        gc.fillOval(x - halo, y - halo, 2 * halo, 2 * halo);
        gc.setFill(Color.web("#FFFFFF", 0.95 * alpha));
        double core = 2.8 * size;
        gc.fillOval(x - core, y - core, 2 * core, 2 * core);
    }

    /** Le cœur condensé : une sphère calme, étiquetée par le gaz noble dont il a la structure. */
    private static void drawCore(GraphicsContext gc, double cx, double cy, double radius, int core, boolean hovered) {
        gc.setFill(new RadialGradient(0, 0, cx, cy, radius, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#A5B4FC", hovered ? 0.26 : 0.16)), new Stop(0.8, Color.web("#818CF8", 0.08)),
                new Stop(1, Color.web("#818CF8", 0))));
        gc.fillOval(cx - radius, cy - radius, 2 * radius, 2 * radius);
        gc.setStroke(Color.web("#A5B4FC", hovered ? 0.6 : 0.32));
        gc.setLineWidth(1);
        gc.setLineDashes(3, 4);
        gc.strokeOval(cx - radius, cy - radius, 2 * radius, 2 * radius);
        gc.setLineDashes((double[]) null);
    }

    /** Une aura sur le bord du noyau (plus vive au bord qu'au centre, comme une charge vue en transparence). */
    private static void drawAura(GraphicsContext gc, double cx, double cy, double radius, double flash) {
        double outer = radius * (1.9 + 0.8 * flash);
        gc.setFill(new RadialGradient(0, 0, cx, cy, outer, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#FB7185", 0)), new Stop(radius / outer * 0.85, Color.web("#FB7185", 0.05)),
                new Stop(radius / outer, Color.web("#FDA4AF", 0.30 + 0.35 * flash)),
                new Stop(1, Color.web("#FB7185", 0))));
        gc.fillOval(cx - outer, cy - outer, 2 * outer, 2 * outer);
    }

    /** Pour les tests : les électrons qui ne partent pas, par couche. */
    int[] counts() {
        int[] out = new int[7];
        for (Bead bead : beads) if (!bead.leaving) out[bead.shell]++;
        return out;
    }

    int movingCount() {
        int n = 0;
        for (Bead bead : beads) if (bead.move < 1 || bead.leaving) n++;
        return n;
    }
}
