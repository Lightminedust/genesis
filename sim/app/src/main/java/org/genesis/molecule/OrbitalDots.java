package org.genesis.molecule;

import java.util.SplittableRandom;

/**
 * La vue ψ : une orbitale moléculaire en nuage de points, le diagramme des manuels. Chaque point est un endroit où
 * l'électron de l'orbitale pourrait être trouvé, tiré au hasard selon |ψ|² : là où les points se serrent, il a le plus
 * de chances d'être ; là où ψ = 0 — un nœud —, il n'y en a aucun. La couleur d'un point dit le signe de ψ en ce point
 * (bleu ψ > 0, orange ψ < 0) : la phase de l'onde, pas une charge.
 *
 * <p>Le tirage est exact pour |ψ|² interpolé dans la grille du moteur : une maille choisie selon un majorant de |ψ|²
 * sur ses coins, un point uniforme dedans, gardé avec la probabilité |ψ|² / majorant. Chaque point a sa propre suite
 * de nombres au hasard, la même d'une grille à l'autre : quand l'orbitale change peu (la dynamique), les points ne
 * bougent que peu, au lieu de grouiller.
 */
final class OrbitalDots {
    /** Le nombre de points. */
    static final int COUNT = 9000;
    static final float[] PLUS = {.36f, .66f, 1f}, MINUS = {1f, .6f, .26f};
    private static final int TRIES = 400;

    private OrbitalDots() {
    }

    /**
     * Les points d'une orbitale : {x, y, z, signe} pour chacun (Å ; signe ±1), au plus `count` (moins si un tirage
     * n'aboutit pas, ce qui n'arrive que pour une grille presque nulle).
     */
    static float[] sample(DensityGrid psi, int count, long seed) {
        int nx = psi.nx, ny = psi.ny, nz = psi.nz;
        int cells = (nx - 1) * (ny - 1) * (nz - 1);
        if (cells <= 0) return new float[0];
        // Le majorant de chaque maille : le plus grand ψ² de ses huit coins (l'interpolation trilinéaire de ψ y reste
        // entre le plus petit et le plus grand coin) ; sa somme cumulée pour choisir une maille.
        double[] cumulative = new double[cells];
        float[] bound = new float[cells];
        double total = 0;
        int c = 0;
        for (int i = 0; i < nx - 1; i++) {
            for (int j = 0; j < ny - 1; j++) {
                for (int k = 0; k < nz - 1; k++) {
                    float max = 0;
                    for (int corner = 0; corner < 8; corner++) {
                        float v = psi.values[((i + (corner & 1)) * ny + j + (corner >> 1 & 1)) * nz + k + (corner >> 2 & 1)];
                        max = Math.max(max, v * v);
                    }
                    bound[c] = max;
                    total += max;
                    cumulative[c++] = total;
                }
            }
        }
        if (total <= 0) return new float[0];
        float[] dots = new float[4 * count];
        int kept = 0;
        for (int d = 0; d < count; d++) {
            SplittableRandom random = new SplittableRandom(seed * 1_000_003L + d);
            for (int attempt = 0; attempt < TRIES; attempt++) {
                double u = random.nextDouble() * total;
                int cell = search(cumulative, u);
                int i = cell / ((ny - 1) * (nz - 1)), j = cell / (nz - 1) % (ny - 1), k = cell % (nz - 1);
                double x = psi.x(i) + random.nextDouble() * psi.spacing, y = psi.y(j) + random.nextDouble() * psi.spacing,
                        z = psi.z(k) + random.nextDouble() * psi.spacing;
                double v = psi.sample(x, y, z), accept = random.nextDouble();
                if (v * v >= accept * bound[cell] && v != 0) {
                    dots[4 * kept] = (float) x;
                    dots[4 * kept + 1] = (float) y;
                    dots[4 * kept + 2] = (float) z;
                    dots[4 * kept + 3] = v > 0 ? 1 : -1;
                    kept++;
                    break;
                }
            }
        }
        return kept == count ? dots : java.util.Arrays.copyOf(dots, 4 * kept);
    }

    /** La première maille dont la somme cumulée dépasse u. */
    private static int search(double[] cumulative, double u) {
        int lo = 0, hi = cumulative.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] < u) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
