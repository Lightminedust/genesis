package org.genesis.mascot;

import java.util.Random;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * Le corps de Genesis : une boule de matière noire, pleine d'univers, avec deux oreilles.
 *
 * Sa forme est solide et simple : une boule ronde, deux oreilles arrondies plantées dessus, quelques gouttelettes autour.
 * - Dedans : de l'espace (le vide noir, une nébuleuse bleue qui tourne lentement, des étoiles).
 * - Au bord : un contour lumineux, bleu puis blanc, et un grand halo autour.
 * - Au cœur : une lumière blanche. Elle se tourne un peu vers la souris, bat quand elle parle, baisse quand elle dort.
 * - En bas : de l'écume blanche, percée de trous, qui penche quand elle bouge (le liquide a de l'inertie) ; quelques îlots s'en
 *   détachent vers le haut.
 * - Autour : des gouttelettes qui se détachent de la boule, flottent, puis y reviennent se fondre.
 *
 * Elle bouge peu, et doucement : elle flotte, se balance, penche vers la souris ; sa boule s'étire ou s'écrase un peu, comme une
 * gelée, et revient ; ses oreilles sont sur ressort : elles traînent quand elle se déplace, se dressent quand elle est
 * curieuse, tombent quand elle dort, et l'une d'elles frémit de temps en temps.
 *
 * La forme est une surface implicite (la distance à la boule, aux oreilles et aux gouttes, réunies par un minimum lissé) : le
 * contour est lisse et net à toutes les tailles, et une goutte qui touche la boule s'y soude.
 */
public final class GenesisBlob {
    static final double RADIUS = .36;
    private static final int DROPS = 7;
    private static final double HALO = .19;
    /** Les gouttelettes : angle autour de la boule (0 : à droite, 90° : en bas), taille, cadence, phase. */
    private static final double[][] DROP = {{2.45, .05, .23, .0}, {2.9, .032, .31, 1.7}, {.75, .044, .19, 3.1}, {.35, .028, .37, 4.4},
            {1.95, .026, .27, 2.3}, {-.75, .03, .21, 5.2}, {1.2, .022, .33, .9}};

    /** Ce qu'on lui demande, et où est la souris (en unités de la boule de verre). */
    static final class Input {
        double vigor = 1;          // sa vivacité
        double perk;               // ses oreilles dressées (0 … 1)
        boolean sleep, talking, pointer;
        double pointerX, pointerY;
    }

    private double x, y = .06, vx, vy, stretch, stretchV, time, hopIn = 3, twitchIn = 4;
    private final double[] ear = {-.42, .42}, earV = new double[2];
    private double gazeX, gazeY, core = 1, tilt, tiltV;
    private final double[] dropX = new double[DROPS], dropY = new double[DROPS], dropAway = new double[DROPS];
    private final Random rng = new Random(31);

    private int size;
    private int[] pixels = new int[0];
    private WritableImage image;
    private static final float[] NOISE = lattice();

    GenesisBlob() {
        for (int k = 0; k < DROPS; k++) {
            dropX[k] = x + Math.cos(DROP[k][0]) * RADIUS;
            dropY[k] = y + Math.sin(DROP[k][0]) * RADIUS;
        }
    }

    // =================================================================
    // La vie
    // =================================================================
    void step(double dt, Input in) {
        dt = Math.min(dt, .05);
        time += dt;
        // où elle veut être : elle flotte au milieu, penche vers la souris, se pose au fond pour dormir
        double tx, ty;
        if (in.sleep) {
            tx = 0;
            ty = .34;
        } else if (in.pointer) {
            tx = Math.max(-.2, Math.min(.2, in.pointerX * .4));
            ty = Math.max(-.1, Math.min(.2, .04 + in.pointerY * .35));
        } else {
            tx = .1 * Math.sin(time * .23) + .04 * Math.sin(time * .61);
            ty = .06 + .04 * Math.sin(time * .31);
        }
        if (!in.sleep) ty += .018 * Math.sin(time * 1.7);
        double ax = (tx - x) * 7 - vx * 4.2, ay = (ty - y) * 7 - vy * 4.2;
        // contente ou excitée, elle fait de petits bonds
        hopIn -= dt * in.vigor;
        if (hopIn < 0 && !in.sleep) {
            hopIn = in.vigor > 1.2 ? .9 + rng.nextDouble() : 5 + 6 * rng.nextDouble();
            if (in.vigor > 1.2) {
                vy -= .5;
                stretchV += 1.6;
            }
        }
        vx += ax * dt;
        vy += ay * dt;
        x += vx * dt;
        y += vy * dt;
        // la gelée : elle s'étire dans le sens où elle va, s'écrase un peu quand elle est posée, respire
        double wantStretch = Math.max(-.16, Math.min(.2, .45 * Math.abs(vy) - .3 * Math.abs(vx))) + (in.sleep ? -.04 + .015 * Math.sin(time * 1.2) : .012 * Math.sin(time * 1.5));
        stretchV += ((wantStretch - stretch) * 70 - stretchV * 9) * dt;
        stretch += stretchV * dt;
        // les oreilles, sur ressort : elles traînent derrière le mouvement, se dressent, tombent ; l'une frémit parfois
        twitchIn -= dt;
        if (twitchIn < 0 && !in.sleep) {
            twitchIn = 2.5 + 5 * rng.nextDouble();
            int k = rng.nextInt(2);
            earV[k] += (k == 0 ? 1 : -1) * 3.2;
        }
        for (int k = 0; k < 2; k++) {
            int side = k == 0 ? -1 : 1;
            double open = in.sleep ? 1.3 : .44 - .24 * in.perk;
            double want = side * open + Math.max(-.5, Math.min(.5, -vx * 1.6)) + .05 * Math.sin(time * 1.9 + k * 2);
            earV[k] += ((want - ear[k]) * 46 - earV[k] * 6.5) * dt;
            ear[k] += earV[k] * dt;
        }
        // son cœur de lumière : il regarde la souris ; il bat quand elle parle ; il baisse quand elle dort
        double gx = 0, gy = 0;
        if (in.pointer) {
            double dx = in.pointerX - x, dy = in.pointerY - y, far = Math.hypot(dx, dy) + 1e-9, pull = Math.min(1, far / .5);
            gx = dx / far * pull;
            gy = dy / far * pull;
        } else if (!in.sleep) {
            gx = .35 * Math.sin(time * .37);
            gy = .2 * Math.sin(time * .29 + 1);
        }
        gazeX += (gx - gazeX) * Math.min(1, dt * 4);
        gazeY += (gy - gazeY) * Math.min(1, dt * 4);
        double wantCore = in.sleep ? .42 + .1 * Math.sin(time * 1.2) : in.talking ? 1 + .4 * Math.abs(Math.sin(time * 8.5) * Math.sin(time * 3.7)) : 1 + .06 * Math.sin(time * 2.1);
        core += (wantCore - core) * Math.min(1, dt * 9);
        // l'écume : le liquide a de l'inertie, sa surface penche à l'opposé de l'accélération, puis clapote
        tiltV += ((-ax * .09 - tilt) * 34 - tiltV * 2.6) * dt;
        tilt = Math.max(-.5, Math.min(.5, tilt + tiltV * dt));
        // les gouttelettes : elles sortent de la boule, flottent, y reviennent ; elles suivent la boule avec un temps de retard
        for (int k = 0; k < DROPS; k++) {
            double away = .5 + .5 * Math.tanh(2.4 * (Math.sin(DROP[k][2] * time + DROP[k][3]) - .15));
            dropAway[k] = away;
            double a = DROP[k][0] + .22 * Math.sin(time * .4 + k), far = RADIUS * (.8 + (.42 + .1 * Math.sin(time * .7 + k * 1.3)) * away);
            double hx = x + Math.cos(a) * far, hy = y + Math.sin(a) * far, follow = Math.min(1, dt * (3 + 6 * (1 - away)));
            dropX[k] += (hx - dropX[k]) * follow;
            dropY[k] += (hy - dropY[k]) * follow;
        }
    }

    /** Un coup : elle s'écrase comme une gelée, puis rebondit. */
    void poke() {
        stretchV -= 2.6;
        earV[0] -= 3;
        earV[1] += 3;
    }

    double x() { return x; }

    double y() { return y; }

    /** De combien elle est étirée en hauteur (1 : ronde). */
    double tall() { return 1 + stretch; }

    /** L'angle d'une oreille (0 : la gauche, 1 : la droite), compté depuis la verticale, positif vers la droite. */
    double ear(int k) { return ear[k]; }

    /** L'éclat de son cœur de lumière (1 : au repos). */
    double core() { return core; }

    private static double smooth(double v) {
        v = Math.max(0, Math.min(1, v));
        return v * v * (3 - 2 * v);
    }

    // =================================================================
    // La peinture
    // =================================================================
    /**
     * Peint son corps dans une image carrée de `pixelSize` de côté, qui couvre la boule de verre ([−1, 1] dans les deux sens).
     * `limbs` : 1 d'ordinaire, 0 quand ses oreilles, son écume et son cœur sont rentrés (elle va se diviser).
     */
    WritableImage render(int pixelSize, double limbs) {
        if (pixelSize != size || image == null) {
            size = pixelSize;
            pixels = new int[size * size];
            image = new WritableImage(size, size);
        }
        java.util.Arrays.fill(pixels, 0);
        double half = size / 2.0, sy = 1 + stretch, sx = 1 / sy, least = Math.min(sx, sy);
        // les oreilles : deux tronçons chacune, plantées sur le haut de la boule, un peu plus larges au milieu
        double[][] seg = new double[4][6];
        for (int k = 0; k < 2; k++) {
            double a = ear[k], bend = a * 1.25 + .1 * Math.signum(a), len = RADIUS * .72 * limbs;
            double bx = x + Math.sin(a * .8) * RADIUS * .82 * sx, by = y - Math.cos(a * .8) * RADIUS * .82 * sy;
            double mx = bx + Math.sin(a) * len * .55, my = by - Math.cos(a) * len * .55;
            double ex = mx + Math.sin(bend) * len * .45, ey = my - Math.cos(bend) * len * .45;
            seg[2 * k] = new double[]{bx, by, RADIUS * .17 * limbs, mx, my, RADIUS * .21 * limbs};
            seg[2 * k + 1] = new double[]{mx, my, RADIUS * .21 * limbs, ex, ey, RADIUS * .13 * limbs};
        }
        int x0 = Math.max(0, (int) ((x - RADIUS * 1.3 - HALO + 1) * half)), x1 = Math.min(size - 1, (int) ((x + RADIUS * 1.3 + HALO + 1) * half));
        int y0 = Math.max(0, (int) ((y - RADIUS * 2.1 - HALO + 1) * half)), y1 = Math.min(size - 1, (int) ((y + RADIUS * 1.3 + HALO + 1) * half));
        double swirl = time * .07, drift = time * .02;
        for (int py = y0; py <= y1; py++) {
            double v = (py + .5) / half - 1;
            for (int px = x0; px <= x1; px++) {
                double u = (px + .5) / half - 1;
                if (u * u + v * v > .9) continue;                               // hors de la boule de verre : rien
                // --- la forme : la boule, puis les oreilles et les gouttes qui s'y soudent
                double bu = (u - x) / sx, bv = (v - y) / sy, d = (Math.sqrt(bu * bu + bv * bv) - RADIUS) * least, earness = 0, thick = RADIUS;
                if (d > HALO + RADIUS * .95) continue;                            // trop loin de tout
                if (limbs > .02) {
                    for (double[] s : seg) {
                        double abx = s[3] - s[0], aby = s[4] - s[1], len2 = abx * abx + aby * aby;
                        if (len2 < 1e-10) continue;
                        // loin de cette oreille : on passe
                        double mu = u - (s[0] + s[3]) * .5, mv = v - (s[1] + s[4]) * .5, most = Math.sqrt(len2) * .5 + s[2] + s[5] + HALO + .04;
                        if (mu * mu + mv * mv > most * most) continue;
                        double dd = roundCone(u - s[0], v - s[1], abx, aby, len2, s[2], s[5]), k = .035;
                        double hh = Math.max(k - Math.abs(dd - d), 0) / k, w = Math.max(0, Math.min(1, .5 + .5 * (d - dd) / k));
                        d = Math.min(d, dd) - hh * hh * k * .25;
                        earness += (1 - earness) * w;
                        thick += (s[2] - thick) * w;
                    }
                    for (int j = 0; j < DROPS; j++) {
                        double r = DROP[j][1] * (.55 + .45 * dropAway[j]) * limbs, du = u - dropX[j], dv = v - dropY[j], k = .03;
                        if (du * du + dv * dv > (r + HALO + k) * (r + HALO + k)) continue;
                        double dd = Math.sqrt(du * du + dv * dv) - r;
                        double hh = Math.max(k - Math.abs(dd - d), 0) / k, w = Math.max(0, Math.min(1, .5 + .5 * (d - dd) / k));
                        d = Math.min(d, dd) - hh * hh * k * .25;
                        thick += (r - thick) * w;
                    }
                }
                if (d > HALO) continue;
                double inside = Math.max(0, Math.min(1, .5 - d * half));
                double red = 0, green = 0, blue = 0, alpha = 0;
                if (inside < 1) {
                    // le halo : épais, bleu puis blanc contre le bord ; et une grande lueur douce autour
                    double away = Math.max(0, d), rim = Math.exp(-away / .045), wide = Math.exp(-away / .12) * (1 - away / HALO);
                    alpha = Math.min(1, .95 * rim + .3 * wide) * Math.min(1, thick / .03);
                    red = .2 + .68 * rim * rim;
                    green = .48 + .48 * rim * rim;
                    blue = 1;
                }
                if (inside > 0) {
                    double delta = -d, bodyU = bu / RADIUS, bodyV = bv / RADIUS;
                    // l'espace : il tourne lentement dans son corps
                    double ct = Math.cos(swirl), st = Math.sin(swirl), su = bodyU * ct - bodyV * st, sv = bodyU * st + bodyV * ct;
                    double n1 = clouds(su * 1.5 + drift + 4, sv * 1.5 - drift * .5), n2 = mist(su * 3.3 - drift * .7 + 11, sv * 3.3 + drift * .4 + 5);
                    double nebula = Math.max(0, Math.min(1, (n1 - .44) / .3)), wisp = nebula * Math.max(0, (n2 - .35) / .65);
                    double sr = .006 + .05 * nebula + .22 * wisp * wisp, sg = .008 + .14 * nebula + .5 * wisp * wisp, sb = .04 + .62 * nebula + .4 * wisp;
                    double violet = Math.max(0, (n2 - .58) / .42) * (1 - nebula) * .55;
                    sr += .24 * violet;
                    sg += .06 * violet;
                    sb += .42 * violet;
                    double stars = starfield(su * 13 + 3, sv * 13, time * 1.6) + .9 * starfield(su * 5.5 + 40, sv * 5.5 + 17, time * 1.1);
                    sr += stars * .9;
                    sg += stars * .95;
                    sb += stars;
                    // l'écume : blanche, percée de trous ; pleine au fond, en îlots vers le haut ; sa surface penche et clapote
                    double below = bodyV - (.24 + tilt * bodyU + .04 * Math.sin(bodyU * 5 + time * 1.3));
                    double froth = .62 * mist(bodyU * 3.1 + time * .05, bodyV * 3.1 - time * .035) + .38 * noise(bodyU * 7.3 - time * .04 + 9, bodyV * 7.3 + 2);
                    // le bruit est contrasté pour faire des taches nettes ; le seuil baisse vers le fond : plus d'écume, moins de trous
                    froth = (froth - .5) * 2 + .5;
                    double fill = below > 0 ? .2 + .16 * smooth(below / .5) : .2 * smooth(1 + below / .28) - .06;
                    fill += .1 * Math.exp(-delta / .04) * smooth((bodyV + .1) / .5);
                    double foam = smooth((froth + fill - .78) / .03) * (1 - earness) * limbs;
                    // dans les oreilles : quelques éclats
                    foam = Math.max(foam, smooth((froth - .74) / .04) * earness * .85 * limbs);
                    sr += (.93 - sr) * foam;
                    sg += (.98 - sg) * foam;
                    sb += (1 - sb) * foam;
                    // le contour lumineux continue dedans, et s'éteint vers le cœur
                    double rim = Math.exp(-delta / .03), soft = Math.exp(-delta / .11) * .32;
                    sr += (.9 - sr) * rim + .12 * soft;
                    sg += (.97 - sg) * rim + .32 * soft;
                    sb += (1 - sb) * rim + .6 * soft;
                    // le cœur de lumière
                    double cu = bodyU - (-.1 + .2 * gazeX), cv = bodyV - (-.14 + .2 * gazeY), far2 = cu * cu + cv * cv;
                    double heart = core * limbs * (1 - earness), bright = heart * 1.5 * Math.exp(-far2 / .045), bloom = heart * .55 * Math.exp(-far2 / .3);
                    sr += bright + bloom * .45;
                    sg += bright + bloom * .75;
                    sb += bright + bloom;
                    red = sr * inside + red * alpha * (1 - inside);
                    green = sg * inside + green * alpha * (1 - inside);
                    blue = sb * inside + blue * alpha * (1 - inside);
                    alpha = inside + alpha * (1 - inside);
                    red /= alpha;
                    green /= alpha;
                    blue /= alpha;
                }
                // rien ne traverse le verre
                alpha *= Math.max(0, Math.min(1, (.945 - Math.sqrt(u * u + v * v)) / .03));
                pixels[py * size + px] = channel(alpha) << 24 | channel(red) << 16 | channel(green) << 8 | channel(blue);
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

    /** Deux octaves de bruit (moins cher que les nuages). */
    private static double mist(double x, double y) {
        return (noise(x, y) + .5 * noise(2 * x + 7.1, 2 * y + 3.3)) / 1.5;
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
