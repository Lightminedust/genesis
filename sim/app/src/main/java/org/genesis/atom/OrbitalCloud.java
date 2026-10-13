package org.genesis.atom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

/**
 * Le nuage électronique en 3D, tel que la mécanique quantique le décrit :
 * pas d'orbites, une densité de probabilité de présence
 *
 *     |ψ_nlm|² = R_nl(r)² · Y_lm(θ, φ)²
 *
 *  - R_nl est la fonction radiale hydrogénoïde ρ^l e^(−ρ/2) L_(n−l−1)^(2l+1)(ρ) :
 *    elle porte les n − l − 1 nœuds radiaux (les coquilles sombres du 3s).
 *  - Y_lm sont les harmoniques sphériques réelles, dans l'ordre de orbitals.py :
 *    sphère (s), deux lobes (p), trèfles et anneau (d), formes à 6 ou 8 lobes (f).
 *  - Chaque sous-couche est remplie selon Hund (l'occupation vient du moteur) ;
 *    une sous-couche pleine ou à moitié pleine redevient sphérique (Unsöld).
 *  - La taille vient du moteur : la forme hydrogénoïde est mise à l'échelle sur
 *    le rayon de Slater de la sous-couche. L'électron unique (H, He⁺…) est
 *    exact : rayon de Bohr a₀/Z, et m non résolu, donc un mélange isotrope.
 *
 * Rendu volumique exact. Chaque pixel reçoit la densité intégrée le long du
 * regard — ce que donnerait un lancer de rayons à travers le brouillard. Le
 * calcul est fait analytiquement. Le long du rayon r⃗ = b⃗ + t·ê, la partie
 * angulaire est un polynôme en t ; l'intégrale se ramène à quelques tables
 * G_j(b) = ∫ g(√(b² + t²)) t^j dt, calculées une fois par (n, l). Aucun bruit,
 * aucune approximation de rendu.
 *
 * Luminosité : chaque sous-couche a sa couleur et sa propre échelle, réglée
 * sur la colonne au rayon le plus probable. Sans
 * cela, le cœur 1s, des dizaines de fois plus dense, éblouirait tout, et
 * l'atome ne serait qu'une boule (la densité totale d'un atome est presque
 * sphérique). Les formes restent exactes ; seule l'échelle de luminosité est
 * propre à chaque sous-couche, et les couches internes sont atténuées.
 *
 * Par-dessus, des points fugaces : des positions tirées au hasard selon |ψ|²
 * (Monte-Carlo). C'est ce qu'on trouverait en mesurant la position d'un
 * électron. Les tirages changent sans cesse, la loi ne change pas : l'état
 * est stationnaire.
 */
public final class OrbitalCloud {
    static final double BOHR_PM = 52.917721;
    static final String[][] ORBITAL_NAMES = {
            {"s"},
            {"p_z", "p_y", "p_x"},
            {"d_z²", "d_xz", "d_x²−y²", "d_yz", "d_xy"},
            {"f_z³", "f_xz²", "f_z(x²−y²)", "f_x(x²−3y²)", "f_yz²", "f_xyz", "f_y(3x²−y²)"}};

    /**
     * Courbe de luminosité, en fonction de x = colonne / colonne au rayon le plus probable :
     * invisible sous x = 0,06, courbe en S jusqu'à 70 % en x = 1,2 (le dégradé de densité reste
     * visible dans les lobes), puis logarithmique jusqu'au maximum (les lobes internes des orbitales s).
     */
    static final double TONE_FLOOR = 0.06;
    static final double TONE_TOP = 1.2;
    static final double TONE_KNEE = 0.7;
    static final double FULL_WEIGHT = 0.8;       // la couche pleine la plus externe
    static final double INNER_WEIGHT = 0.45;     // les couches pleines internes, atténuées
    private static final int TONE_SIZE = 4096;
    private static final double TONE_MAX = 6;
    private static final float[] TONE = toneTable();
    private static final double FADE_S = 0.9;
    private static final double SUDDEN_S = 0.22;     // une orbitale qui naît d'une absorption : vite
    private static final double SCALE_S = 1.0;       // contraction ou dilatation d'une sous-couche qui reste (ressort)
    private static final double PULSE_S = 0.9;
    private static final double PULSE_GAIN = 1.2;
    private static final double DEPTH_CUE = 0.3;      // l'avant du nuage 30 % plus lumineux à son rayon le plus probable
    private static final int MAX_SPARKS = 220;
    private static final Map<Integer, Profile> PROFILES = new HashMap<>();
    private static final double[][][] INVERSE_VANDERMONDE = inverseVandermondes();

    /** Une sous-couche à peindre. occupation : électrons par orbitale réelle (ordre de orbitals.py), null = m non résolu. */
    record Shell(int n, int l, double zEff, double radiusPm, int electrons, int[] occupation, boolean exact,
                 Color colour, String label) {
        boolean isotropic() {
            if (occupation == null || l == 0) {
                return true;
            }
            for (int count : occupation) {
                if (count != occupation[0]) {
                    return false;
                }
            }
            return occupation.length == 2 * l + 1;
        }

        String key() {
            return n + "," + l + "," + electrons + "," + Arrays.toString(occupation) + "," + exact + ","
                    + Math.round(radiusPm * 100) + "," + Math.round(zEff * 1000) + "," + colour + "," + label;
        }
    }

    /** Une carte radiale de l'image : linéaire (vraie échelle) ou logarithmique (le gouffre). */
    interface Radial {
        double px(double pm);

        double length(double px);
    }

    /** Vraie échelle : `pxPerPm` pixels par picomètre. */
    static Radial linear(double pxPerPm) {
        return new Radial() {
            @Override
            public double px(double pm) {
                return pm * pxPerPm;
            }

            @Override
            public double length(double px) {
                return px / pxPerPm;
            }
        };
    }

    // =================================================================
    // Profil universel d'une sous-couche (n, l), en unités ρ = r / a
    // =================================================================
    static final class Profile {
        static final int RADIAL_BINS = 1536;
        static final int PROJ_BINS = 640;
        final int n;
        final int l;
        final double rhoMax;
        final double rhoPeak;
        final double radialStep;
        final double projStep;
        final double[] cdf;            // ∫₀^ρ R̃² ρ² dρ
        final double[] radial;         // R̃² ρ² (intégrale 1)
        final double[] local;          // g(ρ) = R̃²/ρ^2l : densité locale, sans la partie angulaire
        final double squareMax;        // max de R̃²
        final double[][] columns;      // columns[i][β] = G_2i(β) = ∫ g(√(β² + τ²)) τ^2i dτ, g = R̃²/ρ^2l
        final double[] isotropic;      // H(β) = ∫ R̃² dτ
        final double isotropicMax;
        static final int SLAB_STEPS = 48;
        final float[][][] slabColumns; // [2i][β][m] : la même intégrale limitée à |τ| < T_m (plongée)
        final float[][] slabIsotropic; // [β][m]

        Profile(int n, int l) {
            this.n = n;
            this.l = l;
            rhoMax = 4.0 * n + 24;
            radialStep = rhoMax / RADIAL_BINS;
            radial = new double[RADIAL_BINS + 1];
            local = new double[RADIAL_BINS + 1];
            cdf = new double[RADIAL_BINS + 1];
            int peak = 0;
            for (int i = 0; i <= RADIAL_BINS; i++) {
                double rho = i * radialStep;
                double lag = laguerre(n - l - 1, 2 * l + 1, rho);
                local[i] = Math.exp(-rho) * lag * lag;
                radial[i] = Math.pow(rho, 2 * l + 2) * local[i];
                if (radial[i] > radial[peak]) {
                    peak = i;
                }
            }
            rhoPeak = Math.max(radialStep, peak * radialStep);
            for (int i = 1; i <= RADIAL_BINS; i++) {
                cdf[i] = cdf[i - 1] + 0.5 * (radial[i - 1] + radial[i]) * radialStep;
            }
            double norm = cdf[RADIAL_BINS];
            double square = 0;
            for (int i = 0; i <= RADIAL_BINS; i++) {
                cdf[i] /= norm;
                radial[i] /= norm;
                local[i] /= norm;
                square = Math.max(square, local[i] * Math.pow(i * radialStep, 2 * l));
            }
            squareMax = square;
            projStep = rhoMax / PROJ_BINS;
            columns = new double[l + 1][PROJ_BINS + 1];
            isotropic = new double[PROJ_BINS + 1];
            slabColumns = new float[l + 1][PROJ_BINS + 1][SLAB_STEPS + 1];
            slabIsotropic = new float[PROJ_BINS + 1][SLAB_STEPS + 1];
            int steps = 400;
            double best = 0;
            double[][] running = new double[l + 2][steps + 1];
            for (int i = 0; i < PROJ_BINS; i++) {
                double beta = i * projStep;
                double span = Math.sqrt(rhoMax * rhoMax - beta * beta);
                double[] previous = new double[l + 2];
                double last = 0;
                for (int s = 0; s <= steps; s++) {
                    // Pas quadratique : fin près de τ = 0, là où se jouent les tranches minces de la plongée.
                    double tau = span * (double) s / steps * s / steps;
                    double h = tau - last;
                    last = tau;
                    double rho = Math.sqrt(beta * beta + tau * tau);
                    double lag = laguerre(n - l - 1, 2 * l + 1, rho);
                    double g = Math.exp(-rho) * lag * lag / norm;
                    double power = 1;
                    for (int j = 0; j <= l + 1; j++) {
                        double value = j <= l ? g * power : g * Math.pow(rho, 2 * l);
                        // ∫₀^τ par trapèzes, des deux côtés (×2) : la colonne d'une tranche |τ| < T.
                        running[j][s] = s == 0 ? 0 : running[j][s - 1] + h * (previous[j] + value);
                        previous[j] = value;
                        power *= tau * tau;
                    }
                }
                for (int j = 0; j <= l; j++) {
                    columns[j][i] = running[j][steps];
                }
                isotropic[i] = running[l + 1][steps];
                best = Math.max(best, isotropic[i]);
                for (int m = 0; m <= SLAB_STEPS; m++) {
                    double f = steps * Math.sqrt(Math.min(1, slabHalfThickness(m) / span));
                    int k = Math.min(steps - 1, (int) f);
                    double low = span * k * k / ((double) steps * steps);
                    double high = span * (k + 1) * (k + 1) / ((double) steps * steps);
                    double w = Math.max(0, Math.min(1, (slabHalfThickness(m) - low) / (high - low)));
                    for (int j = 0; j <= l + 1; j++) {
                        double value = running[j][k] * (1 - w) + running[j][k + 1] * w;
                        if (j <= l) {
                            slabColumns[j][i][m] = (float) value;
                        } else {
                            slabIsotropic[i][m] = (float) value;
                        }
                    }
                }
            }
            isotropicMax = best;
        }

        /** Les demi-épaisseurs tabulées, resserrées près de zéro : T_m = ρ_max (m / M)². */
        double slabHalfThickness(int m) {
            double f = (double) m / SLAB_STEPS;
            return rhoMax * f * f;
        }

        static synchronized Profile of(int n, int l) {
            return PROFILES.computeIfAbsent(n * 16 + l, key -> new Profile(n, l));
        }

        private static double lerp(double[] table, double x, double step) {
            double f = x / step;
            int i = (int) f;
            if (i >= table.length - 1) {
                return table[table.length - 1];
            }
            double w = f - i;
            return table[i] * (1 - w) + table[i + 1] * w;
        }

        double cdf(double rho) {
            return rho <= 0 ? 0 : lerp(cdf, rho, radialStep);
        }

        double radialDensity(double rho) {
            return rho < 0 ? 0 : lerp(radial, rho, radialStep);
        }

        double local(double rho) {
            return lerp(local, rho, radialStep);
        }

        double isotropic(double beta) {
            return lerp(isotropic, beta, projStep);
        }

        double column(int half, double beta) {
            return lerp(columns[half], beta, projStep);
        }

        /** ρ tel que P(r < ρ) = u (inverse de la fonction de répartition). */
        double sampleRho(double u) {
            int low = 0;
            int high = RADIAL_BINS;
            while (high - low > 1) {
                int mid = (low + high) >>> 1;
                if (cdf[mid] <= u) {
                    low = mid;
                } else {
                    high = mid;
                }
            }
            double span = cdf[high] - cdf[low];
            double w = span > 0 ? (u - cdf[low]) / span : 0;
            return (low + w) * radialStep;
        }
    }

    // =================================================================
    // Une sous-couche prête à peindre
    // =================================================================
    static final class Layer {
        final Shell shell;
        final Profile profile;
        final double a;                    // picomètres par unité ρ
        final boolean isotropic;
        final int[] occupation;
        final double angularMax;
        final double[] lobe = {0.80, 0.36, 0.48};
        final double red;
        final double green;
        final double blue;
        private final double[][] plane;      // p_j(X, Y) = Σ_e plane[j][e] X^(2l−j−e) Y^e (X, Y en unités ρ)
        private final double[] xPowers;
        private final double[] yPowers;
        private final double[] tables;
        private double[][] activeColumns;
        private double[] activeIsotropic;
        private double[][] sliceColumns;
        private double[] sliceIsotropic;
        private double activeSlab = Double.NaN;
        double visibleRho;
        final double columnMax;            // la colonne la plus forte, toutes orientations confondues
        final double columnRim;            // la colonne au rayon le plus probable : référence de luminosité
        private final double floorColumn;
        private final double logSpan;
        double weight = 1;                 // atténuation des couches internes
        double jacobianScale = 1;          // vue déformée : 1 / jacobien au rayon le plus probable
        double fade;                       // apparition / effacement
        double fadeIn = 1 / FADE_S;
        double scale = 1;                  // taille relative pendant une contraction (ion) ou dilatation
        private double scaleFrom = 1;
        private double scaleAge = Double.MAX_VALUE;
        double pulse;                      // éclat après une absorption

        /** Même sous-couche, même remplissage : seule la taille (Z_eff) peut avoir changé. */
        boolean sameShape(Layer other) {
            return shell.n() == other.shell.n() && shell.l() == other.shell.l()
                    && shell.electrons() == other.shell.electrons() && shell.exact() == other.shell.exact()
                    && Arrays.equals(occupation, other.occupation) && shell.label().equals(other.shell.label());
        }

        /** Rayon de mise à l'échelle effectif : a, multiplié par l'animation de taille. */
        double length() {
            return a * scale;
        }

        Layer(Shell shell) {
            this.shell = shell;
            profile = Profile.of(shell.n(), Math.min(shell.l(), shell.n() - 1));
            a = shell.exact() ? shell.n() * BOHR_PM / (2 * Math.max(1e-6, shell.zEff()))
                    : Math.max(1e-6, shell.radiusPm()) / profile.rhoPeak;
            isotropic = shell.isotropic() || shell.l() > 3;
            occupation = shell.occupation() == null ? new int[]{shell.electrons()} : shell.occupation().clone();
            red = shell.colour().getRed();
            green = shell.colour().getGreen();
            blue = shell.colour().getBlue();
            int degree = 2 * shell.l();
            plane = new double[degree + 1][];
            for (int j = 0; j <= degree; j++) {
                plane[j] = new double[degree - j + 1];
            }
            xPowers = new double[degree + 1];
            yPowers = new double[degree + 1];
            tables = new double[profile.l + 1];
            visibleRho = profile.rhoMax;
            activeColumns = profile.columns;
            activeIsotropic = profile.isotropic;
            double best = 0;
            if (!isotropic) {
                double[] u = new double[3];
                int samples = 2400;
                for (int i = 0; i < samples; i++) {
                    fibonacci(i, samples, u);
                    double f = shape(u[0], u[1], u[2]);
                    if (f > best) {
                        best = f;
                        System.arraycopy(u, 0, lobe, 0, 3);
                    }
                }
            }
            double norm = Math.sqrt(lobe[0] * lobe[0] + lobe[1] * lobe[1] + lobe[2] * lobe[2]);
            for (int k = 0; k < 3; k++) {
                lobe[k] /= norm;
            }
            angularMax = best * 1.06;
            double[] estimate = estimateColumns();
            columnMax = estimate[0];
            // Les lobes internes d'une orbitale s (2s, 3s, 4s…) sont très denses au centre : l'échelle part du
            // grand lobe externe, là où est l'essentiel de la probabilité, et monte en logarithme jusqu'au maximum.
            columnRim = Math.min(columnMax, estimate[1]);
            floorColumn = TONE_FLOOR * columnRim;
            logSpan = columnMax > 1.05 * TONE_TOP * columnRim ? 1 / Math.log(columnMax / (TONE_TOP * columnRim)) : 0;
        }

        /**
         * La colonne maximale. Pour une sous-couche sphérique, c'est exact. Pour
         * une forme, on balaie 10 directions de visée et le plan image : le
         * maximum ne dépend que de la configuration, pas de l'orientation à
         * l'écran.
         */
        private double[] estimateColumns() {
            if (isotropic) {
                double scale = shell.electrons() / (4 * Math.PI * a * a);
                return new double[]{scale * profile.isotropicMax, scale * profile.isotropic(profile.rhoPeak)};
            }
            double best = 0;
            double rim = 0;
            double[] view = new double[3];
            double[] first = new double[3];
            double[] second = new double[3];
            for (int v = 0; v < 10; v++) {
                if (v < 3) {
                    view[0] = v == 0 ? 1 : 0;
                    view[1] = v == 1 ? 1 : 0;
                    view[2] = v == 2 ? 1 : 0;
                } else {
                    fibonacci(v - 3, 7, view);
                }
                // Deux axes perpendiculaires à la visée.
                double[] helper = Math.abs(view[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{1, 0, 0};
                cross(view, helper, first);
                cross(view, first, second);
                for (int r = 0; r <= 36; r++) {
                    double radius = r / 36.0 * 3 * profile.rhoPeak * a;
                    for (int k = 0; k < 24; k++) {
                        double angle = k * Math.PI / 12;
                        double x = radius * Math.cos(angle);
                        double y = radius * Math.sin(angle);
                        double column = column(x * first[0] + y * second[0], x * first[1] + y * second[1],
                                x * first[2] + y * second[2], view, false);
                        best = Math.max(best, column);
                        if (r == 12) {
                            rim = Math.max(rim, column);     // r = 12/36 × 3 ρ_pic : le rayon le plus probable
                        }
                    }
                }
            }
            return new double[]{Math.max(best, 1e-30), Math.max(rim, 1e-30)};
        }

        /** Luminosité de la sous-couche (0 à 1) pour une colonne donnée. */
        double brightness(double column) {
            if (column <= floorColumn) {
                return 0;
            }
            double x = column / columnRim;
            if (x < TONE_TOP) {
                double u = (x - TONE_FLOOR) / (TONE_TOP - TONE_FLOOR);
                return weight * TONE_KNEE * u * u * (3 - 2 * u);
            }
            double above = logSpan > 0 ? Math.min(1, Math.log(x / TONE_TOP) * logSpan) : 1;
            return weight * (TONE_KNEE + (1 - TONE_KNEE) * above);
        }

        double peakPm() {
            return profile.rhoPeak * a * scale;
        }

        /** Rayon contenant 99,5 % de la probabilité. */
        double extentPm() {
            return profile.sampleRho(0.995) * a * scale;
        }

        /** Σ occ_k · Ŷ_k(v)², homogène de degré 2l (Ŷ : harmonique solide réelle). */
        double shape(double x, double y, double z) {
            int l = shell.l();
            if (isotropic) {
                double r2 = x * x + y * y + z * z;
                return shell.electrons() / (4 * Math.PI) * Math.pow(r2, l);
            }
            double sum = 0;
            for (int k = 0; k < occupation.length; k++) {
                if (occupation[k] > 0) {
                    double value = solid(l, k, x, y, z);
                    sum += occupation[k] * value * value;
                }
            }
            return sum;
        }

        /** Densité maximale (électrons / pm³), pour savoir si une tranche mince peut encore se voir. */
        double densityMax() {
            double angular = isotropic ? shell.electrons() / (4 * Math.PI) : angularMax;
            return angular * profile.squareMax / (a * a * a);
        }

        /**
         * Plongée : la caméra entre dans le nuage, et seule la tranche |t| < slab
         * (pm) autour du plan du noyau est vue. Les tables de colonnes sont
         * remplacées, pour cette image, par les intégrales limitées à la
         * tranche ; elles sont tabulées en épaisseur et interpolées ici.
         */
        void useSlab(double slab) {
            double t = slab / length();
            if (Double.isInfinite(slab) || t >= profile.rhoMax) {
                activeColumns = profile.columns;
                activeIsotropic = profile.isotropic;
                activeSlab = Double.NaN;
                return;
            }
            if (t == activeSlab) {
                return;
            }
            activeSlab = t;
            double f = Profile.SLAB_STEPS * Math.sqrt(t / profile.rhoMax);
            int m = Math.min(Profile.SLAB_STEPS - 1, (int) f);
            // Interpolation linéaire en T (pas en √T) : une tranche mince voit 2T·n, linéaire en T.
            double low = profile.slabHalfThickness(m);
            double w = Math.max(0, Math.min(1, (t - low) / (profile.slabHalfThickness(m + 1) - low)));
            int bins = profile.isotropic.length;
            if (sliceIsotropic == null) {
                sliceIsotropic = new double[bins];
                sliceColumns = new double[profile.l + 1][bins];
            }
            for (int i = 0; i < bins; i++) {
                float[] iso = profile.slabIsotropic[i];
                sliceIsotropic[i] = iso[m] * (1 - w) + iso[m + 1] * w;
                for (int k = 0; k <= profile.l; k++) {
                    float[] column = profile.slabColumns[k][i];
                    sliceColumns[k][i] = column[m] * (1 - w) + column[m + 1] * w;
                }
            }
            activeColumns = sliceColumns;
            activeIsotropic = sliceIsotropic;
        }

        double column(double bx, double by, double bz, double[] toward, boolean cue) {
            double beta = Math.sqrt(bx * bx + by * by + bz * bz) / a;
            if (beta >= profile.rhoMax) {
                return 0;
            }
            if (isotropic) {
                return shell.electrons() / (4 * Math.PI * a * a) * profile.isotropic(beta);
            }
            double[] p = expand(bx / a, by / a, bz / a, toward);
            double total = 0;
            double front = 0;
            for (int j = 0; j < p.length; j++) {
                if ((j & 1) == 0) {
                    total += p[j] * profile.column(j / 2, beta);
                } else if (cue) {
                    front += p[j] * profile.column((j + 1) / 2, beta);
                }
            }
            if (cue) {
                total += DEPTH_CUE / profile.rhoPeak * front;
            }
            return Math.max(0, total) / (a * a);
        }

        /**
         * Les coefficients p_j du polynôme P̂(β⃗ + τ ê) = Σ_j p_j τ^j au point β⃗
         * (unités ρ) : on évalue P̂ en 2l + 1 points de la droite de visée, puis
         * on inverse la matrice de Vandermonde.
         */
        double[] expand(double ux, double uy, double uz, double[] toward) {
            int l = shell.l();
            int degree = 2 * l;
            double s = 1 + 0.5 * Math.sqrt(ux * ux + uy * uy + uz * uz);
            double[] values = new double[degree + 1];
            for (int m = 0; m <= degree; m++) {
                double tau = (m - l) * s;
                values[m] = shape(ux + tau * toward[0], uy + tau * toward[1], uz + tau * toward[2]);
            }
            double[][] inverse = INVERSE_VANDERMONDE[l];
            double[] out = new double[degree + 1];
            double power = 1;
            for (int j = 0; j <= degree; j++) {
                double c = 0;
                for (int m = 0; m <= degree; m++) {
                    c += inverse[j][m] * values[m];
                }
                out[j] = c / power;
                power *= s;
            }
            return out;
        }

        /**
         * Pour cette orientation de la caméra, développe p_j(β⃗) en polynômes des
         * coordonnées (X, Y) du plan image : une fois par image, et chaque pixel
         * ne coûte plus que quelques multiplications.
         */
        void prepare(double[] right, double[] down, double[] toward) {
            if (isotropic) {
                return;
            }
            int l = shell.l();
            int degree = 2 * l;
            double[][] samples = new double[degree + 1][];
            for (int i = 0; i <= degree; i++) {
                double t = i - l;      // le point (X, Y) = (1, t)
                samples[i] = expand(right[0] + t * down[0], right[1] + t * down[1], right[2] + t * down[2], toward);
            }
            double[][] inverse = INVERSE_VANDERMONDE[l];
            for (int j = 0; j <= degree; j++) {
                // p_j(1, t) = Σ_e c_e t^e, et p_j(X, Y) = X^(2l−j) p_j(1, Y/X) (homogène).
                for (int e = 0; e <= degree - j; e++) {
                    double c = 0;
                    for (int i = 0; i <= degree; i++) {
                        c += inverse[e][i] * samples[i][j];
                    }
                    plane[j][e] = c;
                }
            }
        }

        /** Jusqu'où la sous-couche reste visible : au-delà, sa colonne est sous le seuil de la courbe en S. */
        void setVisibility() {
            double threshold = floorColumn;
            double scaleColumn = (isotropic ? shell.electrons() / (4 * Math.PI) : 2 * angularMax) / (a * a);
            int i = profile.isotropic.length - 1;
            while (i > 0 && profile.isotropic[i] * scaleColumn < threshold) {
                i--;
            }
            visibleRho = Math.min(profile.rhoMax, (i + 2) * profile.projStep);
        }

        /**
         * La colonne (électrons/pm²) au point (X, Y) du plan image, en pm le long
         * des axes droite et bas de l'écran. Suppose {@link #prepare} et
         * {@link #useSlab} appelés pour l'image courante.
         */
        double columnFast(double x, double y) {
            double xr = x / length();
            double yr = y / length();
            double beta2 = xr * xr + yr * yr;
            if (beta2 >= visibleRho * visibleRho) {
                return 0;
            }
            double beta = Math.sqrt(beta2);
            double f = beta / profile.projStep;
            int i = (int) f;
            if (i >= Profile.PROJ_BINS) {
                return 0;
            }
            double w = f - i;
            double total;
            if (isotropic) {
                total = shell.electrons() / (4 * Math.PI) * (activeIsotropic[i] * (1 - w) + activeIsotropic[i + 1] * w);
            } else {
                for (int k = 0; k < tables.length; k++) {
                    tables[k] = activeColumns[k][i] * (1 - w) + activeColumns[k][i + 1] * w;
                }
                int degree = 2 * shell.l();
                xPowers[0] = 1;
                yPowers[0] = 1;
                for (int k = 1; k <= degree; k++) {
                    xPowers[k] = xPowers[k - 1] * xr;
                    yPowers[k] = yPowers[k - 1] * yr;
                }
                double even = 0;
                double odd = 0;
                for (int j = 0; j <= degree; j++) {
                    int m = degree - j;
                    double[] c = plane[j];
                    double pj = 0;
                    for (int e = 0; e <= m; e++) {
                        pj += c[e] * xPowers[m - e] * yPowers[e];
                    }
                    if ((j & 1) == 0) {
                        even += pj * tables[j >> 1];
                    } else {
                        odd += pj * tables[(j + 1) >> 1];
                    }
                }
                total = even + DEPTH_CUE / profile.rhoPeak * odd;
            }
            return Math.max(0, total) / (a * a);
        }

        /** Une position tirée selon |ψ|² (pm, repère de chimiste). */
        void sample(Random random, double[] out) {
            double r = profile.sampleRho(random.nextDouble()) * length();
            double x;
            double y;
            double z;
            int guard = 0;
            while (true) {
                z = 2 * random.nextDouble() - 1;
                double phi = 2 * Math.PI * random.nextDouble();
                double ring = Math.sqrt(1 - z * z);
                x = ring * Math.cos(phi);
                y = ring * Math.sin(phi);
                if (isotropic || angularMax <= 0 || ++guard > 200
                        || random.nextDouble() * angularMax <= shape(x, y, z)) {
                    break;
                }
            }
            out[0] = x * r;
            out[1] = y * r;
            out[2] = z * r;
        }
    }

    // =================================================================
    // État : le nuage affiché, ceux qui s'effacent, les mesures fugaces
    // =================================================================
    private static final class Cloud {
        final List<Layer> layers = new ArrayList<>();
        final String key;
        final double electrons;

        Cloud(List<Shell> shells, String key) {
            this.key = key;
            double total = 0;
            for (Shell shell : shells) {
                if (shell.electrons() > 0) {
                    layers.add(new Layer(shell));
                    total += shell.electrons();
                }
            }
            electrons = total;
            // Les sous-couches incomplètes (celles qui ont une forme), l'électron excité ou unique : plein éclat.
            // La couche pleine la plus externe : un peu moins. Les couches pleines internes : atténuées.
            Layer outermost = null;
            for (Layer layer : layers) {
                if (outermost == null || layer.peakPm() >= outermost.peakPm() * 0.999) {
                    outermost = layer;
                }
            }
            for (Layer layer : layers) {
                int capacity = 2 * (2 * layer.shell.l() + 1);
                boolean open = layer.shell.electrons() < capacity || layer.shell.exact()
                        || layer.shell.label().endsWith("(excité)");
                layer.weight = open ? 1 : layer == outermost ? FULL_WEIGHT : INNER_WEIGHT;
            }
        }
    }

    private Cloud current;
    private final List<Layer> leaving = new ArrayList<>();
    private final Random random = new Random(11);

    private final double[][] sparks = new double[MAX_SPARKS][3];
    private final Layer[] sparkLayer = new Layer[MAX_SPARKS];
    private final double[] sparkAge = new double[MAX_SPARKS];
    private final double[] sparkLife = new double[MAX_SPARKS];
    private int sparkCount;
    private double sparkDebt;

    // Tampon de rendu : 1 pixel pour scale × scale pixels d'écran.
    private int scale = 3;
    private int bufferWidth;
    private int bufferHeight;
    private int bufferScale;
    private float[] accumulation = new float[0];
    private float[] still = new float[0];        // sous-couches sphériques : invariantes par rotation, gardées
    private int[] pixels = new int[0];
    private float[] planeX = new float[0];
    private float[] planeY = new float[0];
    private float[] jacobian = new float[0];   // surface vraie / surface à l'écran (vue déformée)
    private WritableImage image;
    private double[] planeKey;
    private double[] stillKey;
    private int[] stillRegion;
    private int[] lastRegion;
    private double lastCx;
    private double lastCy;
    private double lastSlab = Double.POSITIVE_INFINITY;
    private Radial lastMap;
    private final double[] right = new double[3];
    private final double[] down = new double[3];
    private final double[] toward = new double[3];
    private final double[] point = new double[3];

    /**
     * Nouvelle configuration. Une sous-couche qui reste (même remplissage) ne
     * clignote pas : si sa taille a changé (ion, ou Z qui change par
     * désintégration), elle se contracte ou se dilate comme un ressort amorti. Les autres apparaissent en fondu, vite
     * (`sudden`) si une absorption vient de les créer. Celles qui disparaissent
     * s'effacent.
     */
    void update(List<Shell> shells, boolean sudden) {
        StringBuilder key = new StringBuilder();
        for (Shell shell : shells) {
            key.append(shell.key()).append(';');
        }
        String text = key.toString();
        if (current != null && current.key.equals(text)) {
            return;
        }
        if (current == null && text.isEmpty()) {
            return;
        }
        List<Layer> old = current == null ? new ArrayList<>() : new ArrayList<>(current.layers);
        Cloud next = text.isEmpty() ? null : new Cloud(shells, text);
        if (next != null) {
            for (Layer layer : next.layers) {
                Layer match = null;
                for (Layer previous : old) {
                    if (previous.sameShape(layer)) {
                        match = previous;
                        break;
                    }
                }
                if (match != null) {
                    old.remove(match);
                    layer.fade = match.fade;
                    layer.fadeIn = match.fadeIn;
                    layer.pulse = match.pulse;
                    double ratio = match.length() / layer.a;
                    if (Math.abs(ratio - 1) > 0.002) {
                        layer.scaleFrom = ratio;
                        layer.scale = ratio;
                        layer.scaleAge = 0;
                    }
                } else {
                    layer.fade = 0;
                    layer.fadeIn = 1 / (sudden ? SUDDEN_S : FADE_S);
                }
            }
        }
        leaving.addAll(old);
        current = next;
    }

    void update(List<Shell> shells) {
        update(shells, false);
    }

    /** L'orbitale qui agit : l'électron excité ou unique, sinon la valence. */
    int actorIndex() {
        if (current == null) {
            return -1;
        }
        int valence = -1;
        for (int i = 0; i < current.layers.size(); i++) {
            Shell shell = current.layers.get(i).shell;
            if (shell.exact() || shell.label().endsWith("(excité)")) {
                return i;
            }
            valence = i;
        }
        return valence;
    }

    /** L'orbitale qui vient d'absorber (ou d'émettre) s'illumine, puis s'apaise. */
    void pulseActor(double strength) {
        int index = actorIndex();
        if (index >= 0) {
            Layer layer = current.layers.get(index);
            layer.pulse = Math.max(layer.pulse, strength);
        }
    }

    void tick(double dt) {
        if (current != null) {
            for (Layer layer : current.layers) {
                layer.fade = Math.min(1, layer.fade + dt * layer.fadeIn);
                layer.pulse = Math.max(0, layer.pulse - dt / PULSE_S);
                if (layer.scaleAge < SCALE_S) {
                    // Un ressort amorti : le nuage file vers sa nouvelle taille, la dépasse un peu, puis s'y pose.
                    layer.scaleAge += dt;
                    double t = Math.min(SCALE_S, layer.scaleAge);
                    layer.scale = 1 + (layer.scaleFrom - 1) * Math.exp(-t / 0.18) * Math.cos(2 * Math.PI * t / 0.55);
                    if (layer.scaleAge >= SCALE_S) {
                        layer.scale = 1;
                    }
                }
            }
        }
        for (Layer layer : leaving) {
            layer.fade -= dt / FADE_S;
            layer.pulse = Math.max(0, layer.pulse - dt / PULSE_S);
        }
        leaving.removeIf(layer -> layer.fade <= 0);
        // Les mesures fugaces : chacune vit ~0,5 s, puis une autre position est tirée.
        for (int i = 0; i < sparkCount; i++) {
            sparkAge[i] += dt;
            if (sparkAge[i] >= sparkLife[i]) {
                sparkCount--;
                double[] spare = sparks[i];
                sparks[i] = sparks[sparkCount];
                sparks[sparkCount] = spare;
                sparkLayer[i] = sparkLayer[sparkCount];
                sparkAge[i] = sparkAge[sparkCount];
                sparkLife[i] = sparkLife[sparkCount];
                i--;
            }
        }
        if (current == null || current.layers.isEmpty()) {
            return;
        }
        double rate = Math.min(150, 26 + 7 * current.electrons);
        sparkDebt += rate * dt;
        while (sparkDebt >= 1 && sparkCount < MAX_SPARKS) {
            sparkDebt -= 1;
            // Une sous-couche au hasard, pondérée par son nombre d'électrons, puis une position selon |ψ|².
            double pick = random.nextDouble() * current.electrons;
            Layer chosen = current.layers.get(current.layers.size() - 1);
            for (Layer layer : current.layers) {
                pick -= layer.shell.electrons();
                if (pick < 0) {
                    chosen = layer;
                    break;
                }
            }
            chosen.sample(random, sparks[sparkCount]);
            sparkLayer[sparkCount] = chosen;
            sparkAge[sparkCount] = 0;
            sparkLife[sparkCount] = 0.35 + 0.4 * random.nextDouble();
            sparkCount++;
        }
        sparkDebt = Math.min(sparkDebt, 2);
    }

    int layerCount() {
        return current == null ? 0 : current.layers.size();
    }

    Layer layer(int index) {
        return current.layers.get(index);
    }

    /** Nombre moyen d'électrons à moins de r du noyau : Σ N · P(r' < r). */
    double electronsWithin(double rPm) {
        double total = 0;
        if (current != null) {
            for (Layer layer : current.layers) {
                total += layer.shell.electrons() * layer.profile.cdf(rPm / layer.length());
            }
        }
        return total;
    }

    /** La sous-couche qui domine au point d'écran (x, y), d'après le dernier rendu ; −1 si le brouillard y est invisible. */
    int ownerAt(double x, double y) {
        if (current == null || lastMap == null) {
            return -1;
        }
        double sx = x - lastCx;
        double sy = y - lastCy;
        double d = Math.hypot(sx, sy);
        double k = d < 1e-9 ? 0 : lastMap.length(d) / d;
        double area = areaRatio(lastMap, Math.max(0.5, d));
        int best = -1;
        double strongest = 0.05;
        for (int i = 0; i < current.layers.size(); i++) {
            Layer layer = current.layers.get(i);
            double light = layer.brightness(layer.columnFast(sx * k, sy * k) * area * layer.jacobianScale)
                    * smooth(layer.fade);
            if (light > strongest) {
                strongest = light;
                best = i;
            }
        }
        return best;
    }

    /** La luminosité du brouillard au point d'écran (x, y), toutes sous-couches confondues (0 : vide). */
    double lightAt(double x, double y) {
        if (current == null || lastMap == null) {
            return 0;
        }
        double sx = x - lastCx;
        double sy = y - lastCy;
        double d = Math.hypot(sx, sy);
        double k = d < 1e-9 ? 0 : lastMap.length(d) / d;
        double area = areaRatio(lastMap, Math.max(0.5, d));
        double total = 0;
        for (Layer layer : current.layers) {
            total += layer.brightness(layer.columnFast(sx * k, sy * k) * area * layer.jacobianScale) * smooth(layer.fade);
        }
        return Math.min(1, total);
    }

    /**
     * Peint le nuage : densité intégrée le long du regard, pixel par pixel,
     * puis les mesures fugaces. (cx, cy) : le noyau à l'écran.
     */
    void draw(GraphicsContext g, Camera3D camera, double cx, double cy, Radial map,
              double left, double top, double width, double height, double slab) {
        if (current == null && leaving.isEmpty()) {
            return;
        }
        axes(camera, right, down, toward);
        lastCx = cx;
        lastCy = cy;
        lastMap = map;
        lastSlab = slab;
        List<Layer> layers = new ArrayList<>(leaving);
        if (current != null) {
            layers.addAll(current.layers);
        }
        // ~80 000 pixels de tampon : le brouillard est lisse, l'agrandissement bilinéaire suffit.
        scale = (int) Math.max(3, Math.min(5, Math.ceil(Math.sqrt(width * height / 80000.0))));
        int w = Math.max(4, (int) Math.ceil(width / scale));
        int h = Math.max(4, (int) Math.ceil(height / scale));
        ensure(w, h);

        // Le plan image sous chaque pixel : ne change qu'avec le zoom, le centrage ou la taille.
        double[] geometry = {cx, cy, map.px(1), map.px(1000), left, top, scale, w, h};
        if (!Arrays.equals(geometry, planeKey)) {
            for (int py = 0; py < h; py++) {
                double sy = top + (py + 0.5) * scale - cy;
                for (int px = 0; px < w; px++) {
                    double sx = left + (px + 0.5) * scale - cx;
                    double d = Math.sqrt(sx * sx + sy * sy);
                    double k = d < 1e-9 ? 0 : map.length(d) / d;
                    planeX[py * w + px] = (float) (sx * k);
                    planeY[py * w + px] = (float) (sy * k);
                    jacobian[py * w + px] = (float) areaRatio(map, Math.max(0.5, d));
                }
            }
            planeKey = geometry;
        }
        double[] key = new double[geometry.length + 2 + 4 * layers.size()];
        System.arraycopy(geometry, 0, key, 0, geometry.length);
        key[geometry.length] = Double.isInfinite(slab) ? -1 : slab;
        key[geometry.length + 1] = layers.size();
        boolean shaped = false;
        for (int c = 0; c < layers.size(); c++) {
            Layer layer = layers.get(c);
            layer.jacobianScale = 1 / areaRatio(map, Math.max(0.5, map.px(layer.peakPm())));
            int at = geometry.length + 2 + 4 * c;
            key[at] = System.identityHashCode(layer);
            key[at + 1] = layer.fade;
            key[at + 2] = layer.scale;
            key[at + 3] = layer.pulse;
            layer.setVisibility();
            layer.useSlab(slab);
            if (!layer.isotropic && layer.fade > 0) {
                layer.prepare(right, down, toward);
                shaped = true;
            }
        }
        boolean reuse = Arrays.equals(key, stillKey);
        if (reuse && !shaped && lastRegion != null) {
            // Rien n'a changé que la rotation, et tout est sphérique : l'image est la même.
            blit(g, left, top, lastRegion);
            drawSparks(g, camera, cx, cy, map);
            return;
        }
        if (!reuse) {
            clear(still, stillRegion, w);
            stillRegion = null;
            for (Layer layer : layers) {
                double fade = smooth(layer.fade);
                if (layer.isotropic && fade > 0 && visible(layer, slab)) {
                    int[] box = box(layer, map, cx, cy, left, top, w, h);
                    if (box != null) {
                        accumulate(still, layer, box, fade, slab, w);
                        stillRegion = union(stillRegion, box);
                    }
                }
            }
            stillKey = key;
        }
        clear(accumulation, lastRegion, w);
        int[] region = stillRegion == null ? null : stillRegion.clone();
        if (stillRegion != null) {
            for (int py = stillRegion[1]; py <= stillRegion[3]; py++) {
                int from = 3 * (py * w + stillRegion[0]);
                System.arraycopy(still, from, accumulation, from, 3 * (stillRegion[2] - stillRegion[0] + 1));
            }
        }
        for (Layer layer : layers) {
            double fade = smooth(layer.fade);
            if (!layer.isotropic && fade > 0 && visible(layer, slab)) {
                int[] box = box(layer, map, cx, cy, left, top, w, h);
                if (box != null) {
                    accumulate(accumulation, layer, box, fade, slab, w);
                    region = union(region, box);
                }
            }
        }
        lastRegion = region;
        if (region == null) {
            drawSparks(g, camera, cx, cy, map);
            return;
        }
        // Courbe de luminosité, puis l'image (pré-multipliée : un brouillard qui s'ajoute au fond sombre).
        for (int py = region[1]; py <= region[3]; py++) {
            for (int px = region[0]; px <= region[2]; px++) {
                int i = py * w + px;
                int r = tone(accumulation[3 * i]);
                int gr = tone(accumulation[3 * i + 1]);
                int b = tone(accumulation[3 * i + 2]);
                pixels[i] = Math.max(r, Math.max(gr, b)) << 24 | r << 16 | gr << 8 | b;
            }
        }
        image.getPixelWriter().setPixels(region[0], region[1], region[2] - region[0] + 1, region[3] - region[1] + 1,
                PixelFormat.getIntArgbPreInstance(), pixels, region[1] * w + region[0], w);
        blit(g, left, top, region);
        drawSparks(g, camera, cx, cy, map);
    }

    /**
     * Surface vraie (pm²) par pixel² d'écran, à la distance d (px) du noyau :
     * (b db) / (d dd). Dans la vue déformée, la luminosité doit suivre la
     * probabilité par surface de l'image ; on multiplie la colonne par ce
     * rapport. Le centre, très dilaté, devient sombre : c'est le vide. En vraie
     * échelle, le rapport est constant et ne change rien.
     */
    static double areaRatio(Radial map, double d) {
        double b = map.length(d);
        double db = map.length(d * 1.001) - b;
        return b <= 0 || db <= 0 ? 0 : b * db / (d * d * 0.001);
    }

    private void blit(GraphicsContext g, double left, double top, int[] r) {
        g.drawImage(image, r[0], r[1], r[2] - r[0] + 1, r[3] - r[1] + 1,
                left + r[0] * scale, top + r[1] * scale, (r[2] - r[0] + 1) * scale, (r[3] - r[1] + 1) * scale);
    }

    private static double smooth(double fade) {
        return fade <= 0 ? 0 : fade * fade * (3 - 2 * fade);
    }

    /** Une tranche trop mince (plongée) ne laisse plus voir cette sous-couche. */
    private boolean visible(Layer layer, double slab) {
        return Double.isInfinite(slab) || 2 * slab * layer.densityMax() >= layer.floorColumn;
    }

    private int[] box(Layer layer, Radial map, double cx, double cy, double left, double top, int w, int h) {
        double extent = map.px(layer.visibleRho * layer.length());
        int x0 = Math.max(0, (int) Math.floor((cx - extent - left) / scale));
        int x1 = Math.min(w - 1, (int) Math.ceil((cx + extent - left) / scale));
        int y0 = Math.max(0, (int) Math.floor((cy - extent - top) / scale));
        int y1 = Math.min(h - 1, (int) Math.ceil((cy + extent - top) / scale));
        return x0 > x1 || y0 > y1 ? null : new int[]{x0, y0, x1, y1};
    }

    private void accumulate(float[] target, Layer layer, int[] box, double fade, double slab, int w) {
        double gain = fade * (1 + PULSE_GAIN * layer.pulse);
        float red = (float) (layer.red * gain);
        float green = (float) (layer.green * gain);
        float blue = (float) (layer.blue * gain);
        for (int py = box[1]; py <= box[3]; py++) {
            for (int px = box[0]; px <= box[2]; px++) {
                int i = py * w + px;
                float light = (float) layer.brightness(
                        layer.columnFast(planeX[i], planeY[i]) * jacobian[i] * layer.jacobianScale);
                if (light > 0) {
                    target[3 * i] += light * red;
                    target[3 * i + 1] += light * green;
                    target[3 * i + 2] += light * blue;
                }
            }
        }
    }

    private static int[] union(int[] a, int[] b) {
        if (a == null) {
            return b.clone();
        }
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    private static void clear(float[] target, int[] region, int w) {
        if (region == null) {
            return;
        }
        for (int y = region[1]; y <= region[3]; y++) {
            Arrays.fill(target, 3 * (y * w + region[0]), 3 * (y * w + region[2] + 1), 0f);
        }
    }

    private void ensure(int w, int h) {
        if (w != bufferWidth || h != bufferHeight || image == null || scale != bufferScale) {
            bufferWidth = w;
            bufferHeight = h;
            bufferScale = scale;
            accumulation = new float[3 * w * h];
            still = new float[3 * w * h];
            pixels = new int[w * h];
            planeX = new float[w * h];
            planeY = new float[w * h];
            jacobian = new float[w * h];
            image = new WritableImage(w, h);
            planeKey = null;
            stillKey = null;
            stillRegion = null;
            lastRegion = null;
        }
    }

    /** Les mesures possibles : un point bref là où un électron pourrait être trouvé. */
    private void drawSparks(GraphicsContext g, Camera3D camera, double cx, double cy, Radial map) {
        for (int i = 0; i < sparkCount; i++) {
            double[] s = sparks[i];
            camera.fromChemistry(s[0], s[1], s[2], point);
            double b = Math.sqrt(point[0] * point[0] + point[1] * point[1]);
            double k = b < 1e-12 ? 0 : map.px(b) / b;
            double x = cx + point[0] * k;
            double y = cy + point[1] * k;
            double life = Math.sin(Math.PI * Math.min(1, sparkAge[i] / sparkLife[i]));
            Layer layer = sparkLayer[i];
            double front = 0.75 + 0.25 * Math.max(-1, Math.min(1, point[2] / Math.max(1e-9, layer.peakPm())));
            double alpha = life * front;
            g.setFill(Color.color(layer.red, layer.green, layer.blue, 0.22 * alpha));
            g.fillOval(x - 3.2, y - 3.2, 6.4, 6.4);
            g.setFill(Color.color(0.5 + 0.5 * layer.red, 0.5 + 0.5 * layer.green, 0.5 + 0.5 * layer.blue, 0.95 * alpha));
            g.fillOval(x - 1.1, y - 1.1, 2.2, 2.2);
        }
    }

    /** Les axes de l'écran (droite, bas) et la direction de l'observateur, en repère de chimiste. */
    static void axes(Camera3D camera, double[] right, double[] down, double[] toward) {
        double cy = Math.cos(camera.yaw);
        double sy = Math.sin(camera.yaw);
        double cp = Math.cos(camera.pitch);
        double sp = Math.sin(camera.pitch);
        // Lignes de la rotation (repère de rendu), puis rendu (X, Y, Z) → chimie (X, Z, −Y).
        right[0] = cy;
        right[1] = sy;
        right[2] = 0;
        down[0] = sy * sp;
        down[1] = -cy * sp;
        down[2] = -cp;
        toward[0] = -sy * cp;
        toward[1] = cy * cp;
        toward[2] = -sp;
    }

    /** Mélange additif doux des sous-couches : 1 − e^(−1,05·somme). */
    private static int tone(double x) {
        if (x <= 0) {
            return 0;
        }
        int index = (int) (x / TONE_MAX * (TONE_SIZE - 1));
        return (int) (255 * TONE[Math.min(TONE_SIZE - 1, index)]);
    }

    private static float[] toneTable() {
        float[] table = new float[TONE_SIZE];
        for (int i = 0; i < TONE_SIZE; i++) {
            table[i] = (float) (1 - Math.exp(-1.05 * i * TONE_MAX / (TONE_SIZE - 1)));
        }
        return table;
    }

    private static void cross(double[] a, double[] b, double[] out) {
        double x = a[1] * b[2] - a[2] * b[1];
        double y = a[2] * b[0] - a[0] * b[2];
        double z = a[0] * b[1] - a[1] * b[0];
        double norm = Math.sqrt(x * x + y * y + z * z);
        out[0] = x / norm;
        out[1] = y / norm;
        out[2] = z / norm;
    }

    // =================================================================
    // Mathématiques
    // =================================================================
    /** Polynôme de Laguerre généralisé L_k^α(x), par récurrence. */
    static double laguerre(int k, double alpha, double x) {
        if (k <= 0) {
            return 1;
        }
        double previous = 1;
        double current = 1 + alpha - x;
        for (int i = 1; i < k; i++) {
            double next = ((2 * i + 1 + alpha - x) * current - (i + alpha) * previous) / (i + 1);
            previous = current;
            current = next;
        }
        return current;
    }

    /**
     * Harmonique solide réelle Ŷ_lk(v) = r^l · Y_lk(v/r) : un polynôme homogène
     * de degré l. Pour un vecteur unitaire, c'est l'harmonique sphérique réelle.
     */
    static double solid(int l, int k, double x, double y, double z) {
        switch (l) {
            case 0:
                return 0.28209479;
            case 1:
                return 0.48860251 * (k == 0 ? z : k == 1 ? y : x);
            case 2:
                return switch (k) {
                    case 0 -> 0.31539157 * (2 * z * z - x * x - y * y);
                    case 1 -> 1.09254843 * x * z;
                    case 2 -> 0.54627422 * (x * x - y * y);
                    case 3 -> 1.09254843 * y * z;
                    default -> 1.09254843 * x * y;
                };
            default:
                return switch (k) {
                    case 0 -> 0.37317633 * z * (2 * z * z - 3 * x * x - 3 * y * y);
                    case 1 -> 0.45704580 * x * (4 * z * z - x * x - y * y);
                    case 2 -> 1.44530572 * z * (x * x - y * y);
                    case 3 -> 0.59004359 * x * (x * x - 3 * y * y);
                    case 4 -> 0.45704580 * y * (4 * z * z - x * x - y * y);
                    case 5 -> 2.89061144 * x * y * z;
                    default -> 0.59004359 * y * (3 * x * x - y * y);
                };
        }
    }

    static void fibonacci(int i, int count, double[] out) {
        double z = 1 - 2 * (i + 0.5) / count;
        double ring = Math.sqrt(1 - z * z);
        double phi = i * Math.PI * (3 - Math.sqrt(5));
        out[0] = ring * Math.cos(phi);
        out[1] = ring * Math.sin(phi);
        out[2] = z;
    }

    /** Inverses des matrices de Vandermonde aux nœuds −l…l : coefficients d'un polynôme à partir de ses valeurs. */
    private static double[][][] inverseVandermondes() {
        double[][][] all = new double[4][][];
        for (int l = 0; l <= 3; l++) {
            int size = 2 * l + 1;
            double[][] m = new double[size][2 * size];
            for (int row = 0; row < size; row++) {
                double tau = row - l;
                double power = 1;
                for (int j = 0; j < size; j++) {
                    m[row][j] = power;
                    power *= tau;
                }
                m[row][size + row] = 1;
            }
            for (int col = 0; col < size; col++) {
                int pivot = col;
                for (int row = col + 1; row < size; row++) {
                    if (Math.abs(m[row][col]) > Math.abs(m[pivot][col])) {
                        pivot = row;
                    }
                }
                double[] swap = m[col];
                m[col] = m[pivot];
                m[pivot] = swap;
                double p = m[col][col];
                for (int j = 0; j < 2 * size; j++) {
                    m[col][j] /= p;
                }
                for (int row = 0; row < size; row++) {
                    if (row != col && m[row][col] != 0) {
                        double f = m[row][col];
                        for (int j = 0; j < 2 * size; j++) {
                            m[row][j] -= f * m[col][j];
                        }
                    }
                }
            }
            // V · c = valeurs  →  c = V⁻¹ · valeurs ; on range V⁻¹[j][m].
            double[][] inverse = new double[size][size];
            for (int j = 0; j < size; j++) {
                System.arraycopy(m[j], size, inverse[j], 0, size);
            }
            all[l] = inverse;
        }
        return all;
    }
}
