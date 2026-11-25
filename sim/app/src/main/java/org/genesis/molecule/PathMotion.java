package org.genesis.molecule;

import java.util.ArrayList;
import java.util.List;

/**
 * Le chemin calculé d'une étape, rejoué sans le moteur : la géométrie, les charges, les liaisons et l'énergie à une
 * position s du chemin (en images, fractionnaire). Aux images, ce sont celles du calcul (GFN1-xTB) ; entre deux images,
 * chaque corps rigide (les atomes liés dans l'une et l'autre) glisse et tourne de la première à la seconde par la
 * rotation la plus courte — le quaternion de Horn qui aligne l'une sur l'autre, puis une fraction de son angle —, sa
 * déformation propre interpolée en ligne droite, comme le fait le moteur (lab.blend) : un CO qui se retourne d'une image
 * à l'autre tourne au lieu de passer son oxygène à travers son carbone. À travers un saut de branche, aucune géométrie
 * n'est inventée : l'une, puis l'autre image. L'énergie, les charges de Mulliken et les indices de Mayer sont ceux des
 * images, interpolés entre elles.
 *
 * <p>La lecture ne dépend donc ni de la charge du moteur (qui calcule peut-être encore les étapes suivantes) ni de ses
 * allers-retours : elle avance avec l'horloge de l'écran.
 */
final class PathMotion {
    /** Une paire est un corps rigide sous 1,25 × la somme des rayons covalents (comme lab._bodies). */
    private static final double BONDED = 1.25;
    /** Sous cet indice de Mayer, une liaison n'est pas montrée (le moteur ne l'envoie pas non plus). */
    private static final double SHOWN = .05;

    private final StageRenderer.Path path;
    private final Segment[] segments;

    PathMotion(StageRenderer.Path path) {
        this.path = path;
        segments = new Segment[Math.max(0, path.count() - 1)];
    }

    StageRenderer.Path path() {
        return path;
    }

    /** La position s (images) bornée au chemin. */
    double clamp(double s) {
        return Math.max(0, Math.min(path.count() - 1, s));
    }

    /** L'énergie (eV, depuis la première image) à la position s : celle des images, interpolée entre elles. */
    double energy(double s) {
        double[] e = path.energies;
        if (e.length == 0) return 0;
        if (e.length == 1) return e[0];
        s = Math.max(0, Math.min(e.length - 1, s));
        int k = Math.min((int) s, e.length - 2);
        double t = s - k;
        if (path.jumps.contains(k)) return t < .5 ? e[k] : e[k + 1];
        return e[k] + (e[k + 1] - e[k]) * t;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /**
     * Ce qui se passe à travers le saut qui suit l'image k, calculé sur ses deux images : {l'atome qui bouge le plus dans
     * son corps rigide, de combien (Å), la variation d'énergie (eV)} ; null s'il n'y a pas de saut là.
     */
    double[] jump(int k) {
        if (!path.jumps.contains(k) || k + 1 >= path.count()) return null;
        Segment seg = segment(k);
        double[][] a = path.frames[k], b = path.frames[k + 1];
        int worst = -1;
        double most = -1;
        for (int j = 0; j < seg.bodies.length; j++) {
            double[] q = seg.rotation[j], inverse = {q[0], -q[1], -q[2], -q[3]};
            for (int i : seg.bodies[j]) {
                double[] u = {a[i][0] - seg.ca[j][0], a[i][1] - seg.ca[j][1], a[i][2] - seg.ca[j][2]};
                double[] v = rotate(inverse, new double[]{b[i][0] - seg.cb[j][0], b[i][1] - seg.cb[j][1], b[i][2] - seg.cb[j][2]});
                double d = Math.sqrt((v[0] - u[0]) * (v[0] - u[0]) + (v[1] - u[1]) * (v[1] - u[1]) + (v[2] - u[2]) * (v[2] - u[2]));
                if (d > most) {
                    most = d;
                    worst = i;
                }
            }
        }
        double de = k + 1 < path.energies.length ? path.energies[k + 1] - path.energies[k] : 0;
        return new double[]{worst, Math.max(0, most), de};
    }

    /** La géométrie montrée à la position s, avec ses charges et ses liaisons. */
    StageRenderer.Pose at(double s) {
        int n = path.z.length, count = path.count();
        if (count == 0) return null;
        s = clamp(s);
        int k = count == 1 ? 0 : Math.min((int) s, count - 2);
        double t = count == 1 ? 0 : s - k;
        boolean jump = path.jumps.contains(k);
        // À travers un saut, chaque image tient sa moitié ; le passage de l'une à l'autre, bref (du tiers aux deux
        // tiers du segment), est montré en fondu par corps rigides — dit non calculé (la vue l'annonce).
        if (jump) t = smooth(Math.max(0, Math.min(1, (t - .35) / .3)));
        // Le relais entre deux étapes : un glissé doux d'un bout à l'autre, sans rien de calculé entre les deux.
        else if (path.relay) t = smooth(t);
        double[][] p = count == 1 ? copy(path.frames[0]) : blend(k, t);
        double[] q = new double[n];
        if (path.charges.length > 0) {
            double[] qa = path.charges[Math.min(k, path.charges.length - 1)];
            double[] qb = path.charges[Math.min(k + 1, path.charges.length - 1)];
            for (int i = 0; i < n && i < qa.length && i < qb.length; i++) q[i] = qa[i] + (qb[i] - qa[i]) * t;
        }
        double[] radius = new double[n];
        for (int i = 0; i < n; i++) radius[i] = i < path.radii.length && path.radii[i] > 0 ? path.radii[i] : .75;
        // La présence de chaque atome : celui qui s'en va s'efface dans la première moitié du relais, celui qui arrive
        // paraît dans la seconde.
        double[] alpha = null;
        if (path.alpha != null && path.alpha.length > 0) {
            double[] aa = path.alpha[Math.min(k, path.alpha.length - 1)], ab = path.alpha[Math.min(count == 1 ? 0 : k + 1, path.alpha.length - 1)];
            alpha = new double[n];
            for (int i = 0; i < n; i++) {
                double from = i < aa.length ? aa[i] : 1, to = i < ab.length ? ab[i] : 1;
                double u = from > to ? Math.min(1, t * 2) : from < to ? Math.max(0, t * 2 - 1) : t;
                alpha[i] = from + (to - from) * smooth(u);
            }
        }
        return new StageRenderer.Pose(p, q, radius, bonds(p, k, t), s, alpha);
    }

    /**
     * Les liaisons à cette position : celles des images (chaque paire liée dans au moins une image, son indice de Mayer
     * image par image, interpolé) ; celles qui changent, leur indice du chemin. Sans liaisons envoyées (un calcul d'un
     * moteur plus ancien), les paires proches, d'indice 1.
     */
    private List<MoleculeScene.Bond> bonds(double[][] p, int k, double t) {
        List<MoleculeScene.Bond> out = new ArrayList<>();
        int frames = path.count();
        int ka = Math.min(k, frames - 1), kb = Math.min(k + 1, frames - 1);
        boolean[][] changed = new boolean[p.length][];
        for (int c = 0; c < path.changes.length; c++) {
            int a = path.changes[c][0], b = path.changes[c][1];
            if (a >= p.length || b >= p.length) continue;
            double[] oa = path.orders[Math.min(ka, path.orders.length - 1)], ob = path.orders[Math.min(kb, path.orders.length - 1)];
            double o = c < oa.length && c < ob.length ? oa[c] + (ob[c] - oa[c]) * t : 0;
            mark(changed, a, b);
            if (o >= SHOWN) out.add(new MoleculeScene.Bond(Math.min(a, b), Math.max(a, b), o, o, 0, .5));
        }
        if (path.bondPairs.length > 0 && path.bondOrders.length > 0) {
            double[] oa = path.bondOrders[Math.min(ka, path.bondOrders.length - 1)];
            double[] ob = path.bondOrders[Math.min(kb, path.bondOrders.length - 1)];
            for (int j = 0; j < path.bondPairs.length; j++) {
                int a = path.bondPairs[j][0], b = path.bondPairs[j][1];
                if (a >= p.length || b >= p.length || marked(changed, a, b)) continue;
                double o = j < oa.length && j < ob.length ? oa[j] + (ob[j] - oa[j]) * t : 0;
                if (o >= SHOWN) out.add(new MoleculeScene.Bond(a, b, o, o, 0, .5));
            }
            return out;
        }
        for (int a = 0; a < p.length; a++) {
            for (int b = a + 1; b < p.length; b++) {
                if (marked(changed, a, b)) continue;
                double limit = BONDED * (radius(a) + radius(b));
                if (StageRenderer.dist(p[a], p[b]) < limit) out.add(new MoleculeScene.Bond(a, b, 1, 1, 0, .5));
            }
        }
        return out;
    }

    private static void mark(boolean[][] changed, int a, int b) {
        if (changed[a] == null) changed[a] = new boolean[changed.length];
        if (changed[b] == null) changed[b] = new boolean[changed.length];
        changed[a][b] = changed[b][a] = true;
    }

    private static boolean marked(boolean[][] changed, int a, int b) {
        return changed[a] != null && changed[a][b];
    }

    private double radius(int atom) {
        return atom < path.radii.length && path.radii[atom] > 0 ? path.radii[atom] : .75;
    }

    // ================================================================== entre deux images
    /** Un segment k → k + 1 : ses corps rigides, leurs centres, leurs formes et la rotation de l'un à l'autre. */
    private static final class Segment {
        int[][] bodies;
        double[][] ca, cb;
        double[][] rotation;          // quaternion (w, x, y, z) de l'image k vers l'image k + 1, w ≥ 0
    }

    private double[][] blend(int k, double t) {
        double[][] a = path.frames[k], b = path.frames[k + 1];
        int n = a.length;
        double[][] out = new double[n][3];
        for (int i = 0; i < n; i++) for (int c = 0; c < 3; c++) out[i][c] = a[i][c] * (1 - t) + b[i][c] * t;
        if (t <= 0 || t >= 1) return out;
        Segment seg = segment(k);
        for (int j = 0; j < seg.bodies.length; j++) {
            int[] body = seg.bodies[j];
            double[] q = seg.rotation[j], ca = seg.ca[j], cb = seg.cb[j];
            double[] partial = partial(q, t), inverse = {q[0], -q[1], -q[2], -q[3]};
            for (int i : body) {
                double[] u = {a[i][0] - ca[0], a[i][1] - ca[1], a[i][2] - ca[2]};
                double[] v = rotate(inverse, new double[]{b[i][0] - cb[0], b[i][1] - cb[1], b[i][2] - cb[2]});
                double[] own = {u[0] * (1 - t) + v[0] * t, u[1] * (1 - t) + v[1] * t, u[2] * (1 - t) + v[2] * t};
                double[] turned = rotate(partial, own);
                for (int c = 0; c < 3; c++) out[i][c] = ca[c] * (1 - t) + cb[c] * t + turned[c];
            }
        }
        return out;
    }

    private Segment segment(int k) {
        if (segments[k] != null) return segments[k];
        double[][] a = path.frames[k], b = path.frames[k + 1];
        int n = a.length;
        boolean[] seen = new boolean[n];
        List<int[]> bodies = new ArrayList<>();
        for (int start = 0; start < n; start++) {
            if (seen[start]) continue;
            List<Integer> body = new ArrayList<>();
            List<Integer> stack = new ArrayList<>(List.of(start));
            seen[start] = true;
            while (!stack.isEmpty()) {
                int i = stack.remove(stack.size() - 1);
                body.add(i);
                for (int j = 0; j < n; j++) {
                    if (seen[j]) continue;
                    double limit = BONDED * (radius(i) + radius(j));
                    if (StageRenderer.dist(a[i], a[j]) < limit && StageRenderer.dist(b[i], b[j]) < limit) {
                        seen[j] = true;
                        stack.add(j);
                    }
                }
            }
            if (body.size() >= 2) bodies.add(body.stream().mapToInt(Integer::intValue).toArray());
        }
        Segment seg = new Segment();
        seg.bodies = bodies.toArray(new int[0][]);
        seg.ca = new double[bodies.size()][];
        seg.cb = new double[bodies.size()][];
        seg.rotation = new double[bodies.size()][];
        for (int j = 0; j < seg.bodies.length; j++) {
            int[] body = seg.bodies[j];
            double[] ca = new double[3], cb = new double[3];
            for (int i : body) for (int c = 0; c < 3; c++) {
                ca[c] += a[i][c] / body.length;
                cb[c] += b[i][c] / body.length;
            }
            double[][] s = new double[3][3];
            for (int i : body) {
                for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) s[r][c] += (a[i][r] - ca[r]) * (b[i][c] - cb[c]);
            }
            seg.ca[j] = ca;
            seg.cb[j] = cb;
            seg.rotation[j] = horn(s);
        }
        segments[k] = seg;
        return seg;
    }

    /**
     * La rotation qui aligne au mieux une forme sur l'autre (Horn 1987) : le vecteur propre de la plus grande valeur
     * propre de la matrice 4 × 4 bâtie sur S = Σ u vᵀ ; un quaternion unité (w, x, y, z), w ≥ 0.
     */
    static double[] horn(double[][] s) {
        double sxx = s[0][0], sxy = s[0][1], sxz = s[0][2], syx = s[1][0], syy = s[1][1], syz = s[1][2];
        double szx = s[2][0], szy = s[2][1], szz = s[2][2];
        double[][] m = {
                {sxx + syy + szz, syz - szy, szx - sxz, sxy - syx},
                {syz - szy, sxx - syy - szz, sxy + syx, szx + sxz},
                {szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy},
                {sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz}};
        double[][] vectors = new double[4][4];
        double[] values = jacobi(m, vectors);
        int best = 0;
        for (int k = 1; k < 4; k++) if (values[k] > values[best]) best = k;
        double[] q = {vectors[0][best], vectors[1][best], vectors[2][best], vectors[3][best]};
        double norm = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (norm < 1e-12) return new double[]{1, 0, 0, 0};
        double sign = q[0] < 0 ? -1 : 1;
        for (int c = 0; c < 4; c++) q[c] *= sign / norm;
        return q;
    }

    /** Les valeurs propres d'une matrice symétrique 4 × 4 (méthode de Jacobi) ; ses vecteurs propres en colonnes. */
    private static double[] jacobi(double[][] matrix, double[][] vectors) {
        double[][] a = new double[4][4];
        for (int r = 0; r < 4; r++) {
            a[r] = matrix[r].clone();
            for (int c = 0; c < 4; c++) vectors[r][c] = r == c ? 1 : 0;
        }
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = 0;
            for (int r = 0; r < 4; r++) for (int c = r + 1; c < 4; c++) off += a[r][c] * a[r][c];
            if (off < 1e-22) break;
            for (int p = 0; p < 4; p++) {
                for (int q = p + 1; q < 4; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) t = 1;
                    double c = 1 / Math.sqrt(t * t + 1), sn = t * c;
                    for (int k = 0; k < 4; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - sn * akq;
                        a[k][q] = sn * akp + c * akq;
                    }
                    for (int k = 0; k < 4; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = c * apk - sn * aqk;
                        a[q][k] = sn * apk + c * aqk;
                    }
                    for (int k = 0; k < 4; k++) {
                        double vkp = vectors[k][p], vkq = vectors[k][q];
                        vectors[k][p] = c * vkp - sn * vkq;
                        vectors[k][q] = sn * vkp + c * vkq;
                    }
                }
            }
        }
        return new double[]{a[0][0], a[1][1], a[2][2], a[3][3]};
    }

    /** Une fraction t de la rotation q (même axe, t × son angle). */
    static double[] partial(double[] q, double t) {
        double w = Math.max(-1, Math.min(1, q[0]));
        double half = Math.acos(w), sin = Math.sin(half);
        if (sin < 1e-9) return new double[]{1, 0, 0, 0};
        double h = half * t, k = Math.sin(h) / sin;
        return new double[]{Math.cos(h), q[1] * k, q[2] * k, q[3] * k};
    }

    /** Le vecteur v tourné par le quaternion unité q. */
    static double[] rotate(double[] q, double[] v) {
        double w = q[0], x = q[1], y = q[2], z = q[3];
        double[][] r = {
                {1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)},
                {2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)},
                {2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)}};
        return new double[]{r[0][0] * v[0] + r[0][1] * v[1] + r[0][2] * v[2], r[1][0] * v[0] + r[1][1] * v[1] + r[1][2] * v[2],
                r[2][0] * v[0] + r[2][1] * v[1] + r[2][2] * v[2]};
    }

    private static double[][] copy(double[][] frame) {
        double[][] out = new double[frame.length][];
        for (int i = 0; i < frame.length; i++) out[i] = frame[i].clone();
        return out;
    }
}
