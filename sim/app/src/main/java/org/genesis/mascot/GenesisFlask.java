package org.genesis.mascot;

import java.util.Arrays;
import java.util.Random;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * Genesis en matière noire : la flasque d'univers, et les résidus que son corps rejette.
 *
 * Sa métamorphose a quatre temps. Elle noircit (c'est sa peau, `GenesisSkin`, qui le montre : l'espace gagne tout son corps,
 * ses pattes et ses branchies rentrent). Puis elle se divise : son corps n'est plus qu'un fluide, cinq masses qui flottent, se
 * soudent et se séparent. Puis elles se rassemblent le long de sa colonne. Enfin elle reprend son gel bleu, et son corps
 * rejette des résidus de matière, qui dérivent, freinés par l'eau, et s'éteignent.
 *
 * Le fluide est une surface implicite : la distance à chaque masse (un tronçon effilé de la colonne, ou un disque), réunies par
 * un minimum lissé — les « metaballs ». Deux masses proches se soudent ; quand elles s'écartent, le col qui les relie s'amincit
 * puis rompt. Dedans, de l'espace : le vide, une nébuleuse bleue, des étoiles ; au bord, un halo bleu puis blanc.
 */
public final class GenesisFlask {
    static final int N = 15;
    private static final int MAX_DROPS = 40;
    private static final int PRIMS = N + MAX_DROPS;
    private static final int TILE = 16;
    private static final double HALO = .13;
    /** En secondes : elle noircit, se divise à SPLIT_AT, se rassemble à GATHER_AT, se recolore à RECOLOUR_AT. */
    private static final double SPLIT_AT = .7, GATHER_AT = 4.9, RECOLOUR_AT = 6.4, END_AT = 7.8;
    /** Les masses de la flasque : rayon, ampleur en x, hauteur moyenne, ampleur en y, cadences et phases. */
    private static final double[][] BLOBS = {{.30, .22, .25, .12, .31, .23, .0, 1.1}, {.21, .40, -.30, .20, .27, .36, 1.7, .4},
            {.15, .45, -.05, .40, .41, .29, 3.1, .8}, {.10, .55, .0, .50, .50, .37, 4.4, 2.6}, {.07, .50, .0, .45, .62, .45, .9, 5.1}};

    // sa colonne, à l'écran (donnée à chaque image) : c'est de là que les masses partent, et là qu'elles reviennent
    private final double[] bx = new double[N], by = new double[N], br = new double[N];
    private double morph = -1, dark, split, time;
    private boolean burst;
    // les résidus : x, y, vitesse, rayon de départ, âge, durée
    private final double[][] drops = new double[MAX_DROPS][7];
    private int dropCount;
    // ce qu'on peint
    private final double[] ex = new double[PRIMS], ey = new double[PRIMS], er = new double[PRIMS], em = new double[PRIMS];
    private final int[] segA = new int[PRIMS], segB = new int[PRIMS];
    private final double[] segPinch = new double[PRIMS];
    private int segs;
    private double coreX, coreY;
    private final Random rng = new Random(11);

    private int size;
    private float[] dist = new float[0], rho = new float[0], mat = new float[0];
    private int[] pixels = new int[0], tileCount = new int[0], tileList = new int[0];
    private WritableImage image;
    private static final float[] NOISE = lattice();

    /** Où est le nœud i de sa colonne à l'écran, et son rayon. */
    void spine(int i, double x, double y, double r) {
        bx[i] = x;
        by[i] = y;
        br[i] = r;
    }

    void step(double dt) {
        time += dt;
        if (morph >= 0) {
            morph += dt;
            if (morph >= RECOLOUR_AT && !burst) {
                burst = true;
                emit(30, .45);
            }
            if (morph >= END_AT) morph = -1;
        }
        double wantDark = morph >= 0 && morph < RECOLOUR_AT ? 1 : 0, wantSplit = morph >= SPLIT_AT && morph < GATHER_AT ? 1 : 0;
        dark += (wantDark - dark) * Math.min(1, dt * 3.4);
        split += (wantSplit - split) * Math.min(1, dt * 2.2);
        // les résidus dérivent, ralentissent (l'eau les freine), rapetissent et s'éteignent
        for (int k = 0; k < dropCount; k++) {
            double[] d = drops[k];
            double brake = Math.exp(-dt * 2.2);
            d[2] *= brake;
            d[3] = d[3] * brake - .012 * dt;
            d[0] += d[2] * dt + .01 * Math.sin(time * 2.3 + k) * dt;
            d[1] += d[3] * dt;
            d[5] += dt;
            if (d[5] >= d[6]) {
                drops[k] = drops[--dropCount];
                drops[dropCount] = d;
                k--;
            }
        }
        pose();
    }

    /** Elle se change en matière noire, se divise, flotte, puis se rassemble et se reconstitue. */
    void transform() {
        if (morph < 0) {
            morph = 0;
            burst = false;
            emit(8, .2);
        }
    }

    boolean morphing() { return morph >= 0; }

    /** 0 : son gel bleu … 1 : toute en matière noire. */
    double dark() { return dark; }

    /** De combien ses pattes, ses branchies et sa nageoire sont sorties (elles rentrent quand elle noircit). */
    double limbs() { return 1 - smooth(dark * 1.25); }

    /** De combien le fluide a remplacé son corps (0 : c'est elle … 1 : ce sont les masses de la flasque). */
    double cover() { return smooth(split / .08); }

    /** Y a-t-il quelque chose à peindre : le fluide, ou des résidus. */
    boolean ink() { return cover() > .003 || dropCount > 0; }

    /** Un résidu de matière, lâché ici avec cette vitesse. */
    void drop(double x, double y, double vx, double vy, double r, double life) {
        if (dropCount < MAX_DROPS) {
            double[] d = drops[dropCount++];
            d[0] = x;
            d[1] = y;
            d[2] = vx;
            d[3] = vy;
            d[4] = r;
            d[5] = 0;
            d[6] = life;
        }
    }

    /** Son corps rejette des résidus : ils partent de sa surface, dans tous les sens. */
    private void emit(int n, double speed) {
        for (int j = 0; j < n; j++) {
            int i = rng.nextInt(N);
            double a = 2 * Math.PI * rng.nextDouble(), go = .1 + speed * rng.nextDouble();
            drop(bx[i] + Math.cos(a) * br[i] * .7, by[i] + Math.sin(a) * br[i] * .7, Math.cos(a) * go, Math.sin(a) * go,
                    .016 + .03 * rng.nextDouble(), 1.2 + 1.8 * rng.nextDouble());
        }
    }

    private static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /** À quelle masse de la flasque appartient le nœud i. */
    private static int group(int i) {
        return i < 5 ? 0 : i < 9 ? 1 : i < 12 ? 2 : i < 14 ? 3 : 4;
    }

    /** Les masses à peindre : sa colonne, ou les masses de la flasque, ou un mélange des deux ; puis les résidus. */
    private void pose() {
        double e = smooth(split), cover = cover();
        for (int i = 0; i < N; i++) {
            double[] b = BLOBS[group(i)];
            double cx = b[1] * Math.sin(time * b[4] + b[6]), cy = b[2] + b[3] * Math.sin(time * b[5] + b[7]), a = i * 2.4 + time * .4;
            double fx = cx + Math.cos(a) * b[0] * .28, fy = cy + Math.sin(a) * b[0] * .28;
            ex[i] = bx[i] + (fx - bx[i]) * e;
            ey[i] = by[i] + (fy - by[i]) * e;
            er[i] = br[i] * (1 - e) + b[0] * .78 * e;
            em[i] = cover;
        }
        int prims = N;
        for (int k = 0; k < dropCount; k++) {
            double[] d = drops[k];
            double age = d[5] / d[6];
            ex[prims] = d[0];
            ey[prims] = d[1];
            er[prims] = d[4] * (1 - age * age);
            em[prims] = 1;
            prims++;
        }
        coreX = bx[4] * (1 - e);
        coreY = by[4] * (1 - e);
        // la colonne est un tube continu ; là où deux masses de la flasque se quittent, le tube s'étrangle, puis rompt
        segs = 0;
        if (cover > .003) {
            for (int i = 0; i < N - 1; i++) {
                segA[segs] = i;
                segB[segs] = i + 1;
                segPinch[segs++] = group(i) != group(i + 1) ? e : 0;
            }
        }
        for (int i = N; i < prims; i++) {
            segA[segs] = segB[segs] = i;
            segPinch[segs++] = 0;
        }
    }

    /** En combien de masses séparées le fluide est à cet instant (1 quand c'est elle). */
    int pieces() {
        if (cover() < .5) return 1;
        int[] root = new int[N];
        for (int i = 0; i < N; i++) root[i] = i;
        int count = N;
        for (int i = 0; i < N; i++) {
            for (int j = i + 1; j < N; j++) {
                if (Math.hypot(ex[i] - ex[j], ey[i] - ey[j]) < er[i] + er[j] + .02) {
                    int a = i, b = j;
                    while (root[a] != a) a = root[a];
                    while (root[b] != b) b = root[b];
                    if (a != b) {
                        root[a] = b;
                        count--;
                    }
                }
            }
        }
        return count;
    }

    // =================================================================
    // La peinture
    // =================================================================
    /** Peint le fluide et les résidus dans une image carrée de `pixelSize` de côté, qui couvre la boule. */
    WritableImage render(int pixelSize) {
        if (pixelSize != size || image == null) {
            size = pixelSize;
            int n = size * size, tiles = (size + TILE - 1) / TILE;
            dist = new float[n];
            rho = new float[n];
            mat = new float[n];
            pixels = new int[n];
            tileCount = new int[tiles * tiles];
            tileList = new int[tiles * tiles * PRIMS];
            image = new WritableImage(size, size);
        }
        // les masses de la flasque se soudent mollement ; les résidus aussi
        double half = size / 2.0, e = smooth(split), k = .04, kg = .004 + .1 * e, reach = HALO + Math.max(k, kg);
        double[] massD = new double[BLOBS.length], massR = new double[BLOBS.length], massW = new double[BLOBS.length];
        int tiles = (size + TILE - 1) / TILE, chain = segs > 0 && segA[0] != segB[0] ? N - 1 : 0;
        Arrays.fill(tileCount, 0);
        for (int p = 0; p < segs; p++) {
            int a = segA[p], b = segB[p];
            double span = Math.max(er[a], er[b]) + reach;
            int x0 = Math.max(0, (int) ((Math.min(ex[a], ex[b]) - span + 1) * half) / TILE);
            int x1 = Math.min(tiles - 1, (int) ((Math.max(ex[a], ex[b]) + span + 1) * half) / TILE);
            int y0 = Math.max(0, (int) ((Math.min(ey[a], ey[b]) - span + 1) * half) / TILE);
            int y1 = Math.min(tiles - 1, (int) ((Math.max(ey[a], ey[b]) + span + 1) * half) / TILE);
            for (int ty = y0; ty <= y1; ty++)
                for (int tx = x0; tx <= x1; tx++) {
                    int t = ty * tiles + tx;
                    tileList[t * PRIMS + tileCount[t]++] = p;
                }
        }
        // 1. la surface : la distance au fluide (négative dedans)
        for (int py = 0; py < size; py++) {
            double v = (py + .5) / half - 1;
            for (int px = 0; px < size; px++) {
                double u = (px + .5) / half - 1;
                int index = py * size + px, t = (py / TILE) * tiles + px / TILE, n = tileCount[t];
                if (n == 0 || u * u + v * v > .9) {
                    dist[index] = Float.POSITIVE_INFINITY;
                    continue;
                }
                double d = Double.POSITIVE_INFINITY, r = 0, m = 0;
                Arrays.fill(massD, Double.POSITIVE_INFINITY);
                Arrays.fill(massW, 0);
                Arrays.fill(massR, 0);
                for (int round = 0; round < 2; round++) {
                    for (int j = 0; j < n; j++) {
                        int p = tileList[t * PRIMS + j], a = segA[p], b = segB[p];
                        if ((p < chain) != (round == 0)) continue;
                        double abx = ex[b] - ex[a], aby = ey[b] - ey[a], len2 = abx * abx + aby * aby;
                        double f = len2 > 1e-12 ? Math.max(0, Math.min(1, ((u - ex[a]) * abx + (v - ey[a]) * aby) / len2)) : 0;
                        double dx = u - ex[a] - abx * f, dy = v - ey[a] - aby * f;
                        double pr = (er[a] + (er[b] - er[a]) * f) * (1 - segPinch[p] * 5.2 * f * (1 - f));
                        // trop loin pour compter (ni surface, ni halo, ni soudure) : on passe
                        if (pr > 0 && dx * dx + dy * dy > (pr + reach + .03) * (pr + reach + .03)) continue;
                        // un tube rompu (rayon négatif) ne laisse rien, pas même un halo
                        double dd = Math.sqrt(dx * dx + dy * dy) - (pr < 0 ? 6 * pr : pr);
                        if (segPinch[p] == 0 && len2 > 1e-10) dd = roundCone(u - ex[a], v - ey[a], abx, aby, len2, er[a], er[b]);
                        if (round == 0) {
                            // un tronçon de la colonne : la distance est celle du plus proche de sa masse ; le rayon local est
                            // une moyenne où les tronçons proches pèsent le plus (il ne saute pas d'un tronçon à l'autre)
                            int g = e < .02 ? 0 : group(f < .5 ? a : b);
                            double x = Math.max(.02, dd + .35), w = 1 / (x * x * x * x * x * x);
                            massD[g] = Math.min(massD[g], dd);
                            massW[g] += w;
                            massR[g] += w * pr;
                            m = em[a];
                        } else if (d == Double.POSITIVE_INFINITY) {
                            d = dd;
                            r = pr;
                            m = 1;
                        } else {
                            double hh = Math.max(k - Math.abs(dd - d), 0) / k, w = Math.max(0, Math.min(1, .5 + .5 * (d - dd) / k));
                            d = Math.min(d, dd) - hh * hh * k * .25;
                            r += (pr - r) * w;
                            m += (1 - m) * w;
                        }
                    }
                    if (round == 0) {
                        for (int g = 0; g < massD.length; g++) {
                            if (massD[g] == Double.POSITIVE_INFINITY) continue;
                            double gr = massR[g] / massW[g];
                            if (d == Double.POSITIVE_INFINITY) {
                                d = massD[g];
                                r = gr;
                            } else {
                                double hh = Math.max(kg - Math.abs(massD[g] - d), 0) / kg, w = Math.max(0, Math.min(1, .5 + .5 * (d - massD[g]) / kg));
                                d = Math.min(d, massD[g]) - hh * hh * kg * .25;
                                r += (gr - r) * w;
                            }
                        }
                    }
                }
                dist[index] = (float) d;
                rho[index] = (float) Math.max(.012, r);
                mat[index] = (float) m;
            }
        }
        // 2. la matière : l'espace dedans, le halo dehors
        double drift = time * .02, freq = 4.6 - 2.2 * e;
        for (int py = 0; py < size; py++) {
            double v = (py + .5) / half - 1;
            for (int px = 0; px < size; px++) {
                int index = py * size + px;
                double d = dist[index];
                if (d > HALO) {
                    pixels[index] = 0;
                    continue;
                }
                double u = (px + .5) / half - 1, inside = Math.max(0, Math.min(1, .5 - d * half));
                double red = 0, green = 0, blue = 0, alpha = 0;
                if (inside < 1) {
                    // le halo : épais, bleu puis blanc contre le bord
                    double away = Math.max(0, d), space = Math.exp(-away / .055), end = 1 - (away / HALO) * (away / HALO);
                    alpha = .95 * space * Math.min(1, rho[index] / .03) * end;
                    red = .22 + .66 * space * space;
                    green = .5 + .46 * space * space;
                    blue = 1;
                }
                if (inside > 0) {
                    double delta = Math.min(-d, rho[index]);
                    double cu = u - coreX, cv = v - coreY, turn = time * .1 + .5 / (Math.hypot(cu, cv) + .3);
                    double ct = Math.cos(turn), st = Math.sin(turn), su = cu * ct - cv * st, sv = cu * st + cv * ct;
                    double n1 = clouds(su * freq + drift, sv * freq - drift * .5), n2 = clouds(su * freq * 2.1 - drift * .7 + 11, sv * freq * 2.1 + drift * .4 + 5);
                    double nebula = Math.max(0, Math.min(1, (n1 - .46) / .3)), wisp = nebula * Math.max(0, (n2 - .35) / .65);
                    double sr = .004 + .04 * nebula + .2 * wisp * wisp, sg = .006 + .13 * nebula + .5 * wisp * wisp, sb = .028 + .6 * nebula + .4 * wisp;
                    double violet = Math.max(0, (n2 - .6) / .4) * (1 - nebula) * .5;
                    sr += .22 * violet;
                    sg += .06 * violet;
                    sb += .4 * violet;
                    double stars = starfield(su * 34 + drift * 6, sv * 34 - drift * 3, time * 1.6) + .9 * starfield(su * 13 + 40, sv * 13 + 17, time * 1.1);
                    sr += stars * .9;
                    sg += stars * .95;
                    sb += stars;
                    // la lumière du halo continue dedans et s'éteint vers le cœur
                    double glow = Math.exp(-delta / .028);
                    sr += (.88 - sr) * glow;
                    sg += (.96 - sg) * glow;
                    sb += (1 - sb) * glow;
                    double total = inside + alpha * (1 - inside);
                    red = (sr * inside + red * alpha * (1 - inside)) / Math.max(1e-6, total);
                    green = (sg * inside + green * alpha * (1 - inside)) / Math.max(1e-6, total);
                    blue = (sb * inside + blue * alpha * (1 - inside)) / Math.max(1e-6, total);
                    alpha = total;
                }
                // rien ne traverse le verre ; et le fluide apparaît à mesure qu'il remplace son corps
                alpha *= Math.max(0, Math.min(1, (.945 - Math.sqrt(u * u + v * v)) / .03)) * mat[index];
                pixels[index] = channel(alpha) << 24 | channel(red) << 16 | channel(green) << 8 | channel(blue);
            }
        }
        image.getPixelWriter().setPixels(0, 0, size, size, PixelFormat.getIntArgbInstance(), pixels, 0, size);
        return image;
    }

    /**
     * La distance exacte à un tronçon effilé : l'enveloppe de deux disques de rayons r1 (au départ) et r2 (à l'arrivée), joints par
     * leurs tangentes communes. (p : le point, compté depuis le départ ; b : l'arrivée, comptée depuis le départ ; l2 = |b|².)
     */
    private static double roundCone(double px, double py, double bx, double by, double l2, double r1, double r2) {
        double rr = r1 - r2, a2 = l2 - rr * rr;
        if (a2 <= 0) return r1 >= r2 ? Math.sqrt(px * px + py * py) - r1 : Math.hypot(px - bx, py - by) - r2;   // l'un contient l'autre
        double y = px * bx + py * by, z = y - l2, qx = px * l2 - bx * y, qy = py * l2 - by * y;
        double x2 = qx * qx + qy * qy, y2 = y * y * l2, z2 = z * z * l2, k = Math.signum(rr) * rr * rr * x2;
        if (Math.signum(z) * a2 * z2 > k) return Math.sqrt(x2 + z2) / l2 - r2;
        if (Math.signum(y) * a2 * y2 < k) return Math.sqrt(x2 + y2) / l2 - r1;
        return (Math.sqrt(x2 * a2 / l2) + y * rr) / l2 - r1;
    }

    private static int channel(double value) {
        return (int) (255 * Math.max(0, Math.min(1, value)));
    }

    // =================================================================
    // Bruit, étoiles
    // =================================================================
    private static float[] lattice() {
        float[] lattice = new float[64 * 64];
        Random r = new Random(17);
        for (int i = 0; i < lattice.length; i++) lattice[i] = r.nextFloat();
        return lattice;
    }

    private static double noise(double x, double y) {
        double fx = Math.floor(x), fy = Math.floor(y), ux = x - fx, uy = y - fy;
        ux = ux * ux * (3 - 2 * ux);
        uy = uy * uy * (3 - 2 * uy);
        int x0 = ((int) fx) & 63, y0 = ((int) fy) & 63, x1 = (x0 + 1) & 63, y1 = (y0 + 1) & 63;
        double a = NOISE[y0 * 64 + x0], b = NOISE[y0 * 64 + x1], c = NOISE[y1 * 64 + x0], d = NOISE[y1 * 64 + x1];
        return (a + (b - a) * ux) * (1 - uy) + (c + (d - c) * ux) * uy;
    }

    private static double clouds(double x, double y) {
        return (noise(x, y) + .5 * noise(2 * x + 7.1, 2 * y + 3.3) + .25 * noise(4 * x + 1.7, 4 * y + 9.2)) / 1.75;
    }

    private static int hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    private static double starfield(double x, double y, double twinkle) {
        int cx = (int) Math.floor(x), cy = (int) Math.floor(y), h = hash(cx, cy);
        if ((h & 0xFF) >= 64) return 0;
        double sx = cx + .15 + .7 * ((h >>> 8) & 0xFF) / 255.0, sy = cy + .15 + .7 * ((h >>> 16) & 0xFF) / 255.0;
        double big = ((h >>> 24) & 0xFF) / 255.0, extent = .07 + .2 * big * big * big;
        double dx = x - sx, dy = y - sy;
        return Math.exp(-(dx * dx + dy * dy) / (extent * extent)) * (.65 + .35 * Math.sin(twinkle * (1 + big) + (h & 0xFFFF)));
    }
}
