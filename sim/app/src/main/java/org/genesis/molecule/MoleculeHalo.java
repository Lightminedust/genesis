package org.genesis.molecule;

import org.genesis.common.LabCamera;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Le halo de polarisation : le nuage des électrons de valence de chaque molécule, vu en transparence.
 *
 * <p>En chaque pixel, la densité projetée σ = ∫ ρ ds : les électrons par Å² que croise le rayon de vue. Chaque atome y
 * apporte le nuage de l'atome libre (P⁰, calculé par le moteur dans la base GFN1) à l'échelle des électrons qu'il garde
 * dans la molécule, N_A = N⁰ − q_A (charge de Mulliken) : plus fourni s'il en a gagné (δ−), plus mince s'il en a perdu
 * (δ+). La somme reproduit la densité projetée calculée (95–99 % de la surface du contour, voir les tests du moteur).
 *
 * <p>Le bord du halo est le contour σ = 0,3 e/Å², tracé au pixel près ; au-delà, la traîne du nuage s'éteint en brume
 * (les alcalins, dont l'unique électron est trop étalé pour atteindre le contour, n'ont qu'elle). La couleur d'un point
 * est la charge des atomes qui y apportent leurs électrons, moyennée selon leurs parts, q̄ = Σ_A σ_A q_A / σ (la
 * partition de Hirshfeld, vue en projection) : magenta δ−, violet, bleu neutre, cyan δ+. Chaque molécule a son halo, et
 * tout est recalculé à chaque image, en pleine résolution : le halo suit les atomes sans retard.
 */
final class MoleculeHalo {
    /** Le bord du halo (e/Å²). */
    static final double COLUMN = .3;
    /** En deçà de cette part du contour, la traîne n'est plus visible : on ne la calcule plus. */
    private static final double TAIL = .03;
    private static final int TABLE = 2048, SHADES = 256;
    /** L'aspect : voile intérieur, liseré (intensité, largeur en Å), ligne du contour, brume, reflet. */
    private static final double BODY = .19, RIM = .42, RIM_WIDTH = .07, LINE = .5, HAZE = .22, SHEEN = .2;
    /** La lumière du reflet, en haut à gauche de l'écran (comme celle des bulles). */
    private static final double LIGHT_X = -.55, LIGHT_Y = -.835;
    /** Les couleurs des charges de −0,4 à +0,4 e, en table : aucune allocation par pixel. */
    private static final float[] SHADE = new float[3 * SHADES];

    static {
        for (int i = 0; i < SHADES; i++) {
            float[] c = MoleculeScene.chargeColour((2.0 * i / (SHADES - 1) - 1) * MoleculeScene.CHARGE_SCALE);
            System.arraycopy(c, 0, SHADE, 3 * i, 3);
        }
    }

    /** Le nuage projeté d'un élément, tel que le moteur l'envoie : P⁰(d), d = 0, step, 2·step… (Å, e/Å²). */
    record Cloud(double electrons, double step, float[] projected) {}

    /** P⁰ en table régulière de u = d² (Å²) : aucune racine carrée par pixel. */
    private static final class Table {
        final double electrons, reach2, inverse;
        final float[] values = new float[TABLE];

        Table(Cloud cloud) {
            electrons = cloud.electrons();
            float[] p = cloud.projected();
            int last = p.length - 1;
            while (last > 1 && p[last] < 1e-5) last--;
            double reach = last * cloud.step();
            reach2 = reach * reach;
            inverse = (TABLE - 1) / reach2;
            for (int i = 0; i < TABLE; i++) {
                double x = Math.sqrt(i / inverse) / cloud.step();
                int k = Math.min(last - 1, (int) x);
                double f = x - k, a = Math.max(p[k], 1e-12), b = Math.max(p[k + 1], 1e-12);
                values[i] = (float) (a * Math.pow(b / a, f));                 // log-linéaire : P⁰ décroît en exponentielle
            }
            values[TABLE - 1] = 0;
        }

        double at(double u) {
            if (u >= reach2) return 0;
            double x = u * inverse;
            int i = (int) x;
            return values[i] + (values[i + 1] - values[i]) * (x - i);
        }

        /** La distance (Å) au-delà de laquelle scale · P⁰ reste sous level. */
        double radius(double scale, double level) {
            for (int i = TABLE - 1; i > 0; i--) if (scale * values[i] >= level) return Math.sqrt(i / inverse);
            return 0;
        }
    }

    private final Map<Integer, Table> tables = new HashMap<>();
    private WritableImage image;
    private int[] pixels = new int[0];
    /** La part de l'écran (en pixels de l'écran) que couvre l'image : x, y, largeur, hauteur. */
    private int[] drawn = {0, 0, 0, 0};
    private String key = "";
    private boolean sharp;
    private float[] column = new float[0], charge = new float[0];

    // L'état de la dernière image (pour poser les signes δ au bord) : caméra et atomes.
    private double[] eye = {0, 0, 0}, forward = {0, 0, 1}, right = {1, 0, 0}, up = {0, 1, 0};
    private double invFocal = 1, centerX, centerY, pixelScale = 1;
    private int count;
    private int[] fragmentOf = new int[0];
    private double[] scale = new double[0], vf = new double[0], vr = new double[0], vu = new double[0], vv = new double[0];
    private Table[] table = new Table[0];

    void cloud(int z, Cloud cloud) {
        if (cloud.projected().length >= 3 && cloud.step() > 0 && cloud.electrons() > 0) tables.put(z, new Table(cloud));
    }

    boolean ready() {
        return !tables.isEmpty();
    }

    /**
     * Dessine le halo de chaque molécule (fragment) dans g, du plus loin au plus proche. {@code version} change avec
     * les atomes ; l'image n'est recalculée que si eux ou la caméra ont bougé. Sur un écran à haute densité
     * ({@code deviceScale} > 1), tant que tout bouge le halo est calculé au pixel logique, puis à la densité de l'écran
     * dès que l'image se fige.
     */
    void render(GraphicsContext g, GraphicsContext glow, LabCamera camera, double w, double h, double deviceScale,
                double clock, long version, double[][] positions, int[] z, double[] charges, int[] fragment) {
        String k = version + "|" + w + "|" + h + "|" + deviceScale + "|" + camera.yaw + "|" + camera.pitch + "|"
                + camera.zoom + "|" + camera.targetX + "|" + camera.targetY + "|" + camera.targetZ + "|" + tables.size();
        if (!k.equals(key)) {
            key = k;
            sharp = deviceScale <= 1;
            compute(camera, w, h, Math.min(1, deviceScale), positions, z, charges, fragment);
        } else if (!sharp) {
            sharp = true;
            compute(camera, w, h, deviceScale, positions, z, charges, fragment);
        }
        if (drawn[2] <= 0 || drawn[3] <= 0) return;
        // Une respiration lente, à peine visible ; et un peu du halo dans le calque flou large : son aura.
        double breath = .94 + .06 * Math.sin(clock * .8);
        for (GraphicsContext layer : new GraphicsContext[]{g, glow}) {
            layer.setGlobalBlendMode(BlendMode.SRC_OVER);
            layer.setGlobalAlpha(layer == g ? breath : .3 * breath);
            layer.setImageSmoothing(true);
            layer.drawImage(image, 0, 0, drawn[2], drawn[3], drawn[0] / pixelScale, drawn[1] / pixelScale,
                    drawn[2] / pixelScale, drawn[3] / pixelScale);
            layer.setGlobalAlpha(1);
        }
    }

    private void compute(LabCamera camera, double w, double h, double deviceScale, double[][] positions, int[] z,
                         double[] charges, int[] fragment) {
        pixelScale = deviceScale;
        int bw = Math.max(1, (int) Math.ceil(w * deviceScale)), bh = Math.max(1, (int) Math.ceil(h * deviceScale));
        prepare(camera, w, h, positions, z, charges, fragment);
        double[][] circle = new double[count][];
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int a = 0; a < count; a++) {
            if (table[a] == null) continue;
            double reach = table[a].radius(scale[a], TAIL * COLUMN) * 1.08 + .05;
            double[] p = camera.project(positions[a][0], positions[a][1], positions[a][2]);
            if (p == null) {
                table[a] = null;
                continue;
            }
            circle[a] = new double[]{p[0] * deviceScale, p[1] * deviceScale, reach * p[3] * deviceScale + 2};
            groups.computeIfAbsent(fragment[a], key -> new ArrayList<>()).add(a);
        }
        // Les molécules, de la plus lointaine à la plus proche, et le cadre de chacune.
        List<int[]> order = new ArrayList<>();
        for (List<Integer> members : groups.values()) order.add(members.stream().mapToInt(Integer::intValue).toArray());
        order.sort((p, q) -> Double.compare(depth(q), depth(p)));
        List<int[]> boxes = new ArrayList<>();
        int x0 = bw, y0 = bh, x1 = 0, y1 = 0;
        for (int[] members : order) {
            int[] box = box(members, circle, bw, bh);
            boxes.add(box);
            if (box == null) continue;
            x0 = Math.min(x0, box[0]);
            y0 = Math.min(y0, box[1]);
            x1 = Math.max(x1, box[0] + box[2]);
            y1 = Math.max(y1, box[1] + box[3]);
        }
        if (x1 <= x0 || y1 <= y0) {
            drawn = new int[]{0, 0, 0, 0};
            return;
        }
        int uw = x1 - x0, uh = y1 - y0;
        // L'image ne couvre que les molécules : la carte graphique la reçoit entière à chaque changement. Elle n'est
        // recréée que trop petite, ou bien trop grande (par paliers de 64 pixels).
        int iw = image == null ? 0 : (int) image.getWidth(), ih = image == null ? 0 : (int) image.getHeight();
        if (iw < uw || ih < uh || (long) iw * ih > 3L * uw * uh + 64 * 64) {
            iw = (uw + 63) / 64 * 64;
            ih = (uh + 63) / 64 * 64;
            image = new WritableImage(iw, ih);
            pixels = new int[iw * ih];
        }
        for (int j = 0; j < uh; j++) Arrays.fill(pixels, j * iw, j * iw + uw, 0);
        for (int f = 0; f < order.size(); f++) {
            if (boxes.get(f) != null) draw(order.get(f), circle, charges, boxes.get(f), x0, y0, iw);
        }
        image.getPixelWriter().setPixels(0, 0, uw, uh, PixelFormat.getIntArgbPreInstance(), pixels, 0, iw);
        drawn = new int[]{x0, y0, uw, uh};
    }

    /**
     * La caméra et les atomes de cette image (ce que lisent aussi {@link #column} et {@link #edge}) : le rayon du pixel
     * (x, y) a la direction avant + a·droite + b·haut, a et b linéaires en x et y ; chaque atome, vu de l'œil, et
     * l'échelle de son nuage N_A / N⁰.
     */
    void prepare(LabCamera camera, double w, double h, double[][] positions, int[] z, double[] charges, int[] fragment) {
        eye = camera.eye();
        forward = camera.forward();
        right = camera.right();
        up = camera.up();
        double[] probe = camera.ray(w / 2 + 100, h / 2);
        invFocal = (probe[3] * right[0] + probe[4] * right[1] + probe[5] * right[2])
                / (probe[3] * forward[0] + probe[4] * forward[1] + probe[5] * forward[2]) / 100;
        centerX = w / 2;
        centerY = h / 2;
        int n = Math.min(positions.length, Math.min(z.length, Math.min(charges.length, fragment.length)));
        count = n;
        fragmentOf = Arrays.copyOf(fragment, n);
        scale = new double[n];
        vf = new double[n];
        vr = new double[n];
        vu = new double[n];
        vv = new double[n];
        table = new Table[n];
        for (int a = 0; a < n; a++) {
            Table t = tables.get(z[a]);
            if (t == null || positions[a] == null) continue;
            double[] v = {positions[a][0] - eye[0], positions[a][1] - eye[1], positions[a][2] - eye[2]};
            vf[a] = v[0] * forward[0] + v[1] * forward[1] + v[2] * forward[2];
            if (vf[a] <= .1) continue;
            vr[a] = v[0] * right[0] + v[1] * right[1] + v[2] * right[2];
            vu[a] = v[0] * up[0] + v[1] * up[1] + v[2] * up[2];
            vv[a] = v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
            scale[a] = Math.max(0, (t.electrons - charges[a]) / t.electrons);
            table[a] = t;
        }
    }

    private double depth(int[] members) {
        double d = 0;
        for (int a : members) d += vf[a];
        return d / members.length;
    }

    /** Le cadre (pixels de l'écran) d'une molécule : ses atomes et leur traîne, une marge d'un pixel ; null si vide. */
    private static int[] box(int[] members, double[][] circle, int bw, int bh) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int a : members) {
            minX = Math.min(minX, circle[a][0] - circle[a][2]);
            maxX = Math.max(maxX, circle[a][0] + circle[a][2]);
            minY = Math.min(minY, circle[a][1] - circle[a][2]);
            maxY = Math.max(maxY, circle[a][1] + circle[a][2]);
        }
        int x0 = Math.max(0, (int) Math.floor(minX) - 1), x1 = Math.min(bw, (int) Math.ceil(maxX) + 2);
        int y0 = Math.max(0, (int) Math.floor(minY) - 1), y1 = Math.min(bh, (int) Math.ceil(maxY) + 2);
        return x1 - x0 < 3 || y1 - y0 < 3 ? null : new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    /**
     * Une molécule : σ et sa charge moyenne sur son cadre, puis le halo, composé par-dessus l'image (dont le coin est
     * le pixel d'écran (ox, oy)).
     */
    private void draw(int[] members, double[][] circle, double[] charges, int[] box, int ox, int oy, int stride) {
        int x0 = box[0], y0 = box[1], cw = box[2], ch = box[3];
        int size = cw * ch;
        if (column.length < size) {
            column = new float[size];
            charge = new float[size];
        }
        float[] sigma = column, held = charge;
        double inv = invFocal / pixelScale;
        double cx = centerX * pixelScale, cy = centerY * pixelScale;
        IntStream.range(0, ch).parallel().forEach(j -> {
            int y = y0 + j;
            int[] row = new int[members.length];
            int m = 0;
            for (int a : members) if (Math.abs(y + .5 - circle[a][1]) <= circle[a][2]) row[m++] = a;
            int base = j * cw;
            Arrays.fill(sigma, base, base + cw, 0f);
            Arrays.fill(held, base, base + cw, 0f);
            if (m == 0) return;
            double b = -(y + .5 - cy) * inv;
            for (int i = 0; i < cw; i++) {
                int x = x0 + i;
                double a = (x + .5 - cx) * inv;
                double invNorm = 1 / Math.sqrt(1 + a * a + b * b);
                double s = 0, q = 0;
                for (int k = 0; k < m; k++) {
                    int atom = row[k];
                    double[] c = circle[atom];
                    if (Math.abs(x + .5 - c[0]) > c[2]) continue;
                    double along = (vf[atom] + a * vr[atom] + b * vu[atom]) * invNorm;
                    double value = scale[atom] * table[atom].at(vv[atom] - along * along);
                    if (value <= 0) continue;
                    s += value;
                    q += value * charges[atom];
                }
                sigma[base + i] = (float) s;
                held[base + i] = (float) q;
            }
        });
        // Pixels par Å à la profondeur de la molécule : pour donner au liseré une largeur en Å.
        double perAngstrom = pixelScale / (invFocal * depth(members));
        int[] out = pixels;
        IntStream.range(1, ch - 1).parallel().forEach(j -> {
            int row = (y0 + j - oy) * stride - ox;
            for (int i = 1; i < cw - 1; i++) {
                int at = j * cw + i;
                double s = sigma[at];
                if (s < TAIL * COLUMN) continue;
                double gx = .5 * (sigma[at + 1] - sigma[at - 1]), gy = .5 * (sigma[at + cw] - sigma[at - cw]);
                double gl = Math.sqrt(gx * gx + gy * gy) + 1e-9;
                double signed = (s - COLUMN) / gl;                         // pixels jusqu'au contour (+ dedans)
                double inside = Math.max(0, signed) / perAngstrom;          // Å
                double cover = Math.max(0, Math.min(1, signed + .5));
                double rim = inside < 8 * RIM_WIDTH ? Math.exp(-inside / RIM_WIDTH) : 0;
                double line = Math.max(0, 1 - Math.abs(signed) / 1.25);
                double haze = 0;
                if (cover < 1) {
                    double t = Math.min(1, s / COLUMN);
                    haze = HAZE * t * Math.sqrt(t);
                }
                double alpha = Math.min(.9, cover * (BODY + RIM * rim) + (1 - cover) * haze + LINE * line);
                // Le reflet : le bord tourné vers la lumière brille un peu, juste à l'intérieur.
                double sheen = 0;
                if (cover > 0 && inside < .8) {
                    double facing = Math.max(0, -(gx * LIGHT_X + gy * LIGHT_Y) / gl);
                    sheen = SHEEN * cover * facing * facing * facing * Math.exp(-inside / .16);
                }
                double lift = Math.min(1, .55 * line + .3 * cover * rim);
                int shade = 3 * (int) Math.max(0, Math.min(SHADES - 1,
                        Math.round((held[at] / s / MoleculeScene.CHARGE_SCALE + 1) * .5 * (SHADES - 1))));
                double r = SHADE[shade], g = SHADE[shade + 1], b = SHADE[shade + 2];
                r += (1 - r) * lift;
                g += (1 - g) * lift;
                b += (1 - b) * lift;
                double pr = alpha * r + sheen, pg = alpha * g + sheen, pb = alpha * b + sheen;
                double pa = Math.min(1, Math.max(alpha, Math.max(pr, Math.max(pg, pb))));
                pr = Math.min(pa, pr);
                pg = Math.min(pa, pg);
                pb = Math.min(pa, pb);
                int index = row + x0 + i;
                int dst = out[index];
                double keep = 1 - pa;
                int oa = (int) Math.min(255, pa * 255 + ((dst >>> 24) & 255) * keep + .5);
                int or = (int) Math.min(255, pr * 255 + ((dst >>> 16) & 255) * keep + .5);
                int og = (int) Math.min(255, pg * 255 + ((dst >>> 8) & 255) * keep + .5);
                int ob = (int) Math.min(255, pb * 255 + (dst & 255) * keep + .5);
                out[index] = (oa << 24) | (or << 16) | (og << 8) | ob;
            }
        });
    }

    /** σ (e/Å²) de la molécule {@code fragment} au point d'écran (x, y) de la dernière image. */
    double column(double x, double y, int fragment) {
        double a = (x - centerX) * invFocal, b = -(y - centerY) * invFocal;
        double invNorm = 1 / Math.sqrt(1 + a * a + b * b);
        double s = 0;
        for (int atom = 0; atom < count; atom++) {
            if (table[atom] == null || fragmentOf[atom] != fragment) continue;
            double along = (vf[atom] + a * vr[atom] + b * vu[atom]) * invNorm;
            s += scale[atom] * table[atom].at(vv[atom] - along * along);
        }
        return s;
    }

    /**
     * La distance (pixels) du point d'écran (x, y) au bord du halo de la molécule de l'atome, dans la direction
     * (dx, dy) ; 0 si le point est déjà hors du halo.
     */
    double edge(int atom, double x, double y, double dx, double dy) {
        if (atom >= count || table[atom] == null) return 0;
        int fragment = fragmentOf[atom];
        double t = 0;
        while (t < 600 && column(x + dx * t, y + dy * t, fragment) >= COLUMN) t += 1.5;
        return t;
    }
}
