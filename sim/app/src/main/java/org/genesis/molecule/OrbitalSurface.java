package org.genesis.molecule;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * L'orbitale en surfaces : la frontière où |ψ| atteint le niveau qui enferme une part donnée de la probabilité (85 % :
 * l'électron est dans les lobes 85 fois sur 100), un lobe bleu là où ψ > 0, un rouge là où ψ < 0 ; entre eux, un nœud.
 * Une orbitale diffuse (une molécule chaude, à demi dissociée) peut dépasser la boîte du moteur : la grille est bordée
 * d'une couche où ψ = 0, si bien que la surface se referme sur le bord au lieu de rester ouverte.
 * Le maillage est tiré de la grille du moteur par tétraèdres marchants (chaque maille coupée en six tétraèdres le long
 * de la même diagonale : la surface est fermée, sans trou entre deux mailles), les sommets posés par interpolation
 * linéaire sur les arêtes et partagés entre triangles voisins ; leurs normales sont le gradient de |ψ| — un ombrage lisse,
 * sans facettes. Une grille plus large que 0,2 Å est d'abord raffinée (Catmull–Rom).
 */
final class OrbitalSurface {
    /** La part de |ψ|² enfermée par les lobes. */
    static final double ENCLOSED = .85;

    /** Un maillage : sommets (x, y, z), normales sortantes unitaires, phase de chaque sommet (+1 / −1), triangles. */
    record Mesh(float[] points, float[] normals, byte[] phase, int[] triangles, double level, int orbital) {
        int vertices() {
            return points.length / 3;
        }

        int count() {
            return triangles.length / 3;
        }
    }

    /** Les six tétraèdres d'une maille, autour de la diagonale 0–6 (coins : bit 0 = x, bit 1 = y, bit 2 = z). */
    private static final int[][] TETRAHEDRA = {{0, 1, 3, 7}, {0, 1, 5, 7}, {0, 2, 3, 7}, {0, 2, 6, 7}, {0, 4, 5, 7}, {0, 4, 6, 7}};

    private OrbitalSurface() {}

    /** Le niveau |ψ| au-dessus duquel se trouve la part `enclosed` de Σ ψ² sur la grille. */
    static double level(DensityGrid psi, double enclosed) {
        float[] v = psi.values;
        double[] squares = new double[v.length];
        double total = 0;
        for (int i = 0; i < v.length; i++) {
            squares[i] = (double) v[i] * v[i];
            total += squares[i];
        }
        if (total <= 0) return 0;
        Arrays.sort(squares);
        double kept = 0;
        for (int i = squares.length - 1; i >= 0; i--) {
            kept += squares[i];
            if (kept >= enclosed * total) return Math.sqrt(squares[i]);
        }
        return 0;
    }

    /** La grille bordée d'une couche de zéros sur chaque face : une surface qui touche la boîte s'y referme. */
    static DensityGrid padded(DensityGrid g) {
        int nx = g.nx + 2, ny = g.ny + 2, nz = g.nz + 2;
        float[] values = new float[nx * ny * nz];
        for (int i = 0; i < g.nx; i++) for (int j = 0; j < g.ny; j++) {
            System.arraycopy(g.values, (i * g.ny + j) * g.nz, values, ((i + 1) * ny + j + 1) * nz + 1, g.nz);
        }
        return DensityGrid.of(g.kind, nx, ny, nz, g.ox - g.spacing, g.oy - g.spacing, g.oz - g.spacing, g.spacing, values);
    }

    /** Les deux lobes de l'orbitale, à la part enfermée `enclosed`. */
    static Mesh build(DensityGrid psi, double enclosed) {
        DensityGrid grid = padded(psi.spacing > .2 ? psi.refined() : psi);
        double c = level(grid, enclosed);
        Builder b = new Builder(grid);
        if (c > 0) {
            b.march(c, 1);
            b.march(c, -1);
        }
        return b.mesh(c, psi.orbital);
    }

    /** Le maillage en construction : les sommets sont partagés par arête de la grille et par phase. */
    private static final class Builder {
        final DensityGrid g;
        final int nx, ny, nz;
        final Map<Long, Integer> edges = new HashMap<>();
        float[] points = new float[3 * 4096], normals = new float[3 * 4096];
        byte[] phase = new byte[4096];
        int[] triangles = new int[3 * 8192];
        int vertexCount, triangleCount;

        Builder(DensityGrid g) {
            this.g = g;
            nx = g.nx;
            ny = g.ny;
            nz = g.nz;
        }

        int index(int i, int j, int k) {
            return (i * ny + j) * nz + k;
        }

        /** La surface s·ψ = c (s = +1 : le lobe positif ; −1 : le négatif) ; l'intérieur est s·ψ > c. */
        void march(double c, int s) {
            float[] v = g.values;
            int[] corner = new int[8];
            double[] f = new double[8];
            for (int i = 0; i < nx - 1; i++) for (int j = 0; j < ny - 1; j++) for (int k = 0; k < nz - 1; k++) {
                int inside = 0;
                for (int q = 0; q < 8; q++) {
                    int id = index(i + (q & 1), j + ((q >> 1) & 1), k + ((q >> 2) & 1));
                    corner[q] = id;
                    f[q] = s * v[id] - c;
                    if (f[q] > 0) inside++;
                }
                if (inside == 0 || inside == 8) continue;                  // la maille ne coupe pas la surface
                for (int[] t : TETRAHEDRA) tetrahedron(t, corner, f, s);
            }
        }

        private void tetrahedron(int[] t, int[] corner, double[] f, int s) {
            int[] in = new int[4], out = new int[4];
            int ni = 0, no = 0;
            for (int q : t) {
                if (f[q] > 0) in[ni++] = q;
                else out[no++] = q;
            }
            if (ni == 0 || ni == 4) return;
            // Le sens de la surface : de l'intérieur vers l'extérieur (pour orienter chaque triangle).
            double[] direction = new double[3];
            for (int a = 0; a < no; a++) add(direction, position(corner[out[a]]), 1.0 / no);
            for (int a = 0; a < ni; a++) add(direction, position(corner[in[a]]), -1.0 / ni);
            if (ni == 1 || ni == 3) {
                int lone = ni == 1 ? in[0] : out[0];
                int[] others = ni == 1 ? out : in;
                int a = vertex(corner[lone], corner[others[0]], f[lone], f[others[0]], s);
                int b = vertex(corner[lone], corner[others[1]], f[lone], f[others[1]], s);
                int d = vertex(corner[lone], corner[others[2]], f[lone], f[others[2]], s);
                triangle(a, b, d, direction);
            } else {
                // Deux dedans, deux dehors : un quadrilatère, deux triangles.
                int p = vertex(corner[in[0]], corner[out[0]], f[in[0]], f[out[0]], s);
                int q = vertex(corner[in[0]], corner[out[1]], f[in[0]], f[out[1]], s);
                int r = vertex(corner[in[1]], corner[out[1]], f[in[1]], f[out[1]], s);
                int u = vertex(corner[in[1]], corner[out[0]], f[in[1]], f[out[0]], s);
                triangle(p, q, r, direction);
                triangle(p, r, u, direction);
            }
        }

        private double[] position(int id) {
            int k = id % nz, j = (id / nz) % ny, i = id / (ny * nz);
            return new double[]{g.x(i), g.y(j), g.z(k)};
        }

        private static void add(double[] to, double[] p, double w) {
            for (int a = 0; a < 3; a++) to[a] += w * p[a];
        }

        /** Le sommet sur l'arête (a, b) de la grille, où s·ψ passe par c ; partagé par tous les triangles qui le touchent. */
        private int vertex(int a, int b, double fa, double fb, int s) {
            long lo = Math.min(a, b), hi = Math.max(a, b);
            long key = ((lo * 2_000_003L) + hi) * 2 + (s > 0 ? 1 : 0);
            Integer known = edges.get(key);
            if (known != null) return known;
            double t = fa / (fa - fb);
            double[] pa = position(a), pb = position(b);
            double x = pa[0] + t * (pb[0] - pa[0]), y = pa[1] + t * (pb[1] - pa[1]), z = pa[2] + t * (pb[2] - pa[2]);
            // La normale sortante : |ψ| décroît vers l'extérieur du lobe.
            double[] grad = g.gradient(x, y, z);
            double n = Math.sqrt(grad[0] * grad[0] + grad[1] * grad[1] + grad[2] * grad[2]);
            if (n < 1e-12) {
                grad = new double[]{pb[0] - pa[0], pb[1] - pa[1], pb[2] - pa[2]};
                n = Math.sqrt(grad[0] * grad[0] + grad[1] * grad[1] + grad[2] * grad[2]);
                if (fa > 0) for (int q = 0; q < 3; q++) grad[q] = -grad[q];
            }
            if (vertexCount == phase.length) {
                points = Arrays.copyOf(points, 6 * vertexCount);
                normals = Arrays.copyOf(normals, 6 * vertexCount);
                phase = Arrays.copyOf(phase, 2 * vertexCount);
            }
            int id = vertexCount++;
            points[3 * id] = (float) x;
            points[3 * id + 1] = (float) y;
            points[3 * id + 2] = (float) z;
            normals[3 * id] = (float) (-grad[0] / n);
            normals[3 * id + 1] = (float) (-grad[1] / n);
            normals[3 * id + 2] = (float) (-grad[2] / n);
            phase[id] = (byte) s;
            edges.put(key, id);
            return id;
        }

        /**
         * Un triangle, tourné pour que sa face avant regarde vers l'extérieur du lobe. Même minuscule (un sommet presque
         * sur un coin de maille), il est gardé : l'omettre ouvrirait un trou dans la surface.
         */
        private void triangle(int a, int b, int c, double[] outward) {
            if (a == b || b == c || a == c) return;
            double ux = points[3 * b] - points[3 * a], uy = points[3 * b + 1] - points[3 * a + 1], uz = points[3 * b + 2] - points[3 * a + 2];
            double vx = points[3 * c] - points[3 * a], vy = points[3 * c + 1] - points[3 * a + 1], vz = points[3 * c + 2] - points[3 * a + 2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            // Le sens : celui des normales du gradient à ses sommets (sûr même là où la surface se courbe fort) ; à
            // défaut, de l'intérieur du tétraèdre vers l'extérieur.
            double gx = normals[3 * a] + normals[3 * b] + normals[3 * c], gy = normals[3 * a + 1] + normals[3 * b + 1] + normals[3 * c + 1],
                    gz = normals[3 * a + 2] + normals[3 * b + 2] + normals[3 * c + 2];
            double side = nx * gx + ny * gy + nz * gz;
            if (Math.abs(side) < 1e-12) side = nx * outward[0] + ny * outward[1] + nz * outward[2];
            if (side < 0) {
                int swap = b;
                b = c;
                c = swap;
            }
            if (3 * triangleCount + 3 > triangles.length) triangles = Arrays.copyOf(triangles, 2 * triangles.length);
            triangles[3 * triangleCount] = a;
            triangles[3 * triangleCount + 1] = b;
            triangles[3 * triangleCount + 2] = c;
            triangleCount++;
        }

        Mesh mesh(double level, int orbital) {
            return new Mesh(Arrays.copyOf(points, 3 * vertexCount), Arrays.copyOf(normals, 3 * vertexCount),
                    Arrays.copyOf(phase, vertexCount), Arrays.copyOf(triangles, 3 * triangleCount), level, orbital);
        }
    }
}
