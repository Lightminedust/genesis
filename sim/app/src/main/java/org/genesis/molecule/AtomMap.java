package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeAtom;
import org.genesis.app.I18n;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * La carte de l'atome choisi : ce qui lui arrive — est-il tiré, poussé, et par qui ? Au centre l'atome, le liseré de sa
 * charge (magenta δ−, cyan δ+, comme dans la scène) ; autour, ses voisins dans leur vraie direction : ceux auxquels il
 * est lié sur un cercle proche, les autres plus loin. Trois lectures, en onglets :
 * <ul>
 *   <li>l'essentiel (par défaut) : une seule flèche, la force nette (or, comme dans la scène) ; chaque trait dit ce que
 *       la paire se fait — bleu ils s'attirent, orange ils se repoussent, d'autant plus épais que la force est grande ;
 *       plein s'ils sont liés, en tirets sinon. Dessous, les voisins rangés en liés et non liés, une barre par force, et
 *       pourquoi : la nature de la liaison (sa part π), ou la cause calculée d'une poussée ou d'une attraction sans
 *       liaison — la gêne stérique (recouvrement antiliant de deux couches pleines), des charges de même signe ou
 *       opposées, London, des cœurs trop proches ;</li>
 *   <li>par voisin : une flèche par paire, la force entière que ce voisin exerce sur lui. Elle n'est pas toujours sur
 *       l'axe : les orbitales p sont orientées par toute la molécule, et la part de travers tient les angles. Les
 *       flèches s'additionnent exactement en la force nette (ce qui n'est pas montré — paires lointaines, liaison
 *       halogène, main — va dans « le reste ») ;</li>
 *   <li>par terme : électrons, noyaux, London, main ou paroi ; chaque trait est bleu si la paire s'attire, orange si
 *       elle se repousse.</li>
 * </ul>
 * Les flèches sont la projection exacte des vecteurs sur l'écran, toutes à la même échelle, du bord de l'atome jusqu'avant
 * ses voisins. La carte est centrée sur l'atome et orientée exactement comme la caméra : elle tourne avec la vue. Les
 * voisins liés sont sur un cercle à au moins 60 px, les autres au bord ; un voisin plus petit est derrière ; deux voisins
 * presque dans la même direction s'écartent un peu. Au survol d'un voisin, sa distance et sa force s'écrivent à côté.
 */
final class AtomMap {
    static final Color NET = Color.web("#FFE07A"), ELECTRONS = Color.web("#5CC8FF"), NUCLEI = Color.web("#FF9A4D"),
            LONDON = Color.web("#7EE0A1"), HAND = Color.web("#F4F8FF");
    static final Color ATTRACT = Color.web("#8FD8FF"), REPEL = Color.web("#FF9A5C");
    private static final Color TEXT = Color.web("#DCE8F7"), GREY = Color.web("#8FA4C2"), DIM = Color.web("#6F82A0"),
            REST = Color.web("#B9A8E8");
    private static final double HEAD = 54, MAP = 222, ROW = 15, SUB = 13;
    /** Les flèches : elles partent du bord de l'atome (START px du centre) ; la plus forte mesure LENGTH px. */
    private static final double START = 17, LENGTH = 32;
    private static final int MOST = 6;
    /** Le cercle des voisins liés, en part du rayon de la carte : au moins 60 px du centre, au-delà des flèches. */
    private static final double BONDED = .72;
    static final int ESSENTIAL = 0, BY_PAIR = 1, BY_TERM = 2;

    /** Un voisin de la carte : son atome, sa paire, sa liaison (null si aucune), sa place (px) et sa hauteur hors plan. */
    record Neighbour(int atom, MoleculeScene.Pair pair, MoleculeScene.Bond bond, double x, double y, double lift,
                     double[] force, boolean bonded) {}

    /** La lecture : l'essentiel, par voisin, par terme. */
    int mode = ESSENTIAL;
    private final List<Neighbour> placed = new ArrayList<>();
    private final double[][] tabs = new double[3][];
    /** Le voisin sous le pointeur (−1 : aucun) : ses valeurs s'écrivent à côté de lui. */
    int hovered = -1;

    /** Le voisin sous le pointeur (son atome), −1 sinon : un clic le choisit. */
    int pick(double x, double y) {
        for (Neighbour n : placed) if (Math.hypot(x - n.x, y - n.y) <= 13) return n.atom;
        return -1;
    }

    /** Un clic sur un onglet : vrai s'il change la lecture. */
    boolean tab(double x, double y) {
        for (int k = 0; k < tabs.length; k++) {
            double[] r = tabs[k];
            if (r == null || x < r[0] || x > r[0] + r[2] || y < r[1] || y > r[1] + r[3]) continue;
            if (mode == k) return false;
            mode = k;
            return true;
        }
        return false;
    }

    boolean overTab(double x, double y) {
        for (double[] r : tabs) if (r != null && x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) return true;
        return false;
    }

    /** La force d'une paire sur l'atome i : le vecteur envoyé par le moteur, sinon sa seule part le long de l'axe. */
    static double[] pairForce(MoleculeScene scene, MoleculeScene.Pair p, int i) {
        double[] f = p.forceOn(i);
        if (f != null) return f;
        int other = p.a() == i ? p.b() : p.a();
        double[] a = scene.positions[i], b = scene.positions[other];
        double[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double len = Math.max(1e-9, norm(d));
        return new double[]{p.total() * d[0] / len, p.total() * d[1] / len, p.total() * d[2] / len};
    }

    /** Les voisins montrés : tous ceux auxquels il est lié, puis les paires les plus fortes (au moins 0,02 eV/Å). */
    static List<MoleculeScene.Pair> neighbours(MoleculeScene scene, int i) {
        List<MoleculeScene.Pair> mine = new ArrayList<>();
        for (MoleculeScene.Pair p : scene.pairs) {
            if (p.a() != i && p.b() != i) continue;
            if (p.order() >= MoleculeScene.BOND_ORDER || norm(pairForce(scene, p, i)) >= .02) mine.add(p);
        }
        mine.sort((x, y) -> {
            boolean bx = x.order() >= MoleculeScene.BOND_ORDER, by = y.order() >= MoleculeScene.BOND_ORDER;
            if (bx != by) return bx ? -1 : 1;
            return Double.compare(norm(pairForce(scene, y, i)), norm(pairForce(scene, x, i)));
        });
        return mine.size() > MOST ? new ArrayList<>(mine.subList(0, MOST)) : mine;
    }

    /** La nature d'une liaison, d'après sa part π (Mayer) : simple, en partie double (conjuguée), double, triple. */
    static String character(MoleculeScene.Bond bond) {
        if (bond == null) return "liaison";
        double pi = bond.pi();
        return pi < .15 ? "liaison simple" : pi < .75 ? "liaison en partie double (conjuguée)" : pi < 1.4 ? "liaison double"
                : "liaison triple";
    }

    /**
     * Pourquoi deux atomes non liés s'attirent ou se repoussent : la part de leur force (le long de l'axe) qui pèse le
     * plus dans le sens de la force — le recouvrement des orbitales (antiliant, population < 0 : deux couches pleines
     * qui se touchent, la gêne stérique ; liant : un début de liaison), les charges (de même signe ou opposées),
     * London, ou la répulsion des cœurs.
     */
    static String cause(MoleculeScene.Pair p, double qa, double qb) {
        double total = p.total();
        double[] parts = {p.overlap(), p.charges(), p.dispersion(), p.repulsion()};
        int main = -1;
        double best = 0;
        for (int k = 0; k < parts.length; k++) {
            double along = parts[k] * Math.signum(total);                   // ce qui pousse dans le sens de la force
            if (along > best) {
                best = along;
                main = k;
            }
        }
        if (Math.abs(total) < .02 || main < 0) return "presque rien entre eux";
        String charges = " (" + MoleculeFormat.signed(qa, 2) + " / " + MoleculeFormat.signed(qb, 2) + ")";
        return switch (main) {
            case 0 -> total < 0 ? (p.population() < 0 ? "gêne stérique : leurs couches pleines se touchent"
                    : "leurs orbitales se recouvrent") : "un début de liaison : leurs orbitales se recouvrent";
            case 1 -> I18n.text(total < 0 ? "charges de même signe" : "charges opposées") + charges;
            case 2 -> "London (van der Waals)";
            default -> "cœurs trop proches";
        };
    }

    /**
     * Les valeurs propres d'une matrice symétrique 3 × 3, croissantes, et leurs axes (unitaires, dans `axes`) : rotations
     * de Jacobi jusqu'à ce que les termes hors diagonale s'annulent.
     */
    static double[] eigen(double[][] m, double[][] axes) {
        double[][] a = {m[0].clone(), m[1].clone(), m[2].clone()};
        double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2];
            if (off < 1e-24) break;
            for (int p = 0; p < 2; p++) for (int q = p + 1; q < 3; q++) {
                if (Math.abs(a[p][q]) < 1e-30) continue;
                double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                if (theta == 0) t = 1;
                double cos = 1 / Math.sqrt(t * t + 1), sin = t * cos;
                for (int k = 0; k < 3; k++) {                       // A ← Jᵀ A J
                    double akp = a[k][p], akq = a[k][q];
                    a[k][p] = cos * akp - sin * akq;
                    a[k][q] = sin * akp + cos * akq;
                }
                for (int k = 0; k < 3; k++) {
                    double apk = a[p][k], aqk = a[q][k];
                    a[p][k] = cos * apk - sin * aqk;
                    a[q][k] = sin * apk + cos * aqk;
                }
                for (int k = 0; k < 3; k++) {                       // V ← V J
                    double vkp = v[k][p], vkq = v[k][q];
                    v[k][p] = cos * vkp - sin * vkq;
                    v[k][q] = sin * vkp + cos * vkq;
                }
            }
        }
        Integer[] order = {0, 1, 2};
        java.util.Arrays.sort(order, (x, y) -> Double.compare(a[x][x], a[y][y]));
        double[] values = new double[3];
        for (int k = 0; k < 3; k++) {
            int c = order[k];
            values[k] = a[c][c];
            axes[k] = new double[]{v[0][c], v[1][c], v[2][c]};
        }
        return values;
    }

    /** `a` sans sa part le long de `n` (et de `m`), unitaire ; null s'il ne reste presque rien. */
    private static double[] reject(double[] a, double[] n, double[] m) {
        double[] r = a.clone();
        double k = dot(r, n);
        for (int x = 0; x < 3; x++) r[x] -= k * n[x];
        if (m != null) {
            k = dot(r, m);
            for (int x = 0; x < 3; x++) r[x] -= k * m[x];
        }
        double len = Math.sqrt(dot(r, r));
        if (len < .15) return null;
        for (int x = 0; x < 3; x++) r[x] /= len;
        return r;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] vector(List<Double> values) {
        double[] v = new double[3];
        for (int k = 0; k < Math.min(3, values.size()); k++) v[k] = values.get(k);
        return v;
    }

    private static double norm(double[] v) {
        return Math.sqrt(dot(v, v));
    }

    /**
     * Deux voisins d'un même cercle presque dans la même direction s'écartent juste assez pour ne pas se couvrir
     * (`gap` : l'angle minimal entre eux, en radians) ; les autres gardent leur direction.
     */
    static void spread(double[] angles, double gap) {
        int n = angles.length;
        if (n < 2) return;
        for (int it = 0; it < 40; it++) {
            boolean moved = false;
            for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) {
                double d = Math.IEEEremainder(angles[b] - angles[a], 2 * Math.PI);
                if (Math.abs(d) >= gap) continue;
                double push = (gap - Math.abs(d)) / 2 * (d >= 0 ? 1 : -1);
                angles[a] -= push;
                angles[b] += push;
                moved = true;
            }
            if (!moved) return;
        }
    }

    /** La carte de l'atome `i` ; la hauteur de `canvas` suit ce qu'il y a à dire. */
    void draw(Canvas canvas, MoleculeAtom atom, MoleculeScene scene, int i, boolean confined) {
        List<MoleculeScene.Pair> mine = neighbours(scene, i);
        double[][] terms = {vector(atom.getForceElectronicList()), vector(atom.getForceRepulsionList()),
                vector(atom.getForceDispersionList()), vector(atom.getForceExternalList())};
        double[] net = vector(atom.getForceList());
        // Le reste : ce que les voisins montrés n'expliquent pas (paires lointaines ou faibles, liaison halogène, main).
        double[] rest = net.clone();
        for (MoleculeScene.Pair p : mine) {
            double[] f = pairForce(scene, p, i);
            for (int a = 0; a < 3; a++) rest[a] -= f[a];
        }
        int bondedCount = 0;
        for (MoleculeScene.Pair p : mine) if (p.order() >= MoleculeScene.BOND_ORDER && scene.linked(p.a(), p.b())) bondedCount++;
        double legendTop = HEAD + MAP + 20, tableTop = legendTop + ROW * 4 + 10;
        double table;
        if (mode == ESSENTIAL) {
            table = 18 + (bondedCount > 0 ? 16 : 0) + (mine.size() > bondedCount ? 22 : 0) + mine.size() * (ROW + SUB + 7)
                    + bondedCount * SUB;
        } else {
            int detail = 0;
            for (MoleculeScene.Pair p : mine) detail += p.order() >= MoleculeScene.BOND_ORDER ? 5 : 4;
            table = 18 + ROW * detail + 7 * mine.size();
        }
        double height = tableTop + (mine.isEmpty() ? 18 : table) + (confined ? 30 : 0);
        if (Math.abs(canvas.getHeight() - height) > .5) canvas.setHeight(height);
        double w = canvas.getWidth();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, height);
        g.setLineCap(StrokeLineCap.ROUND);
        // La carte.
        double cx = w / 2, cy = HEAD + 14 + (MAP - 14) / 2, rim = (MAP - 14) / 2 - 2;
        g.setFill(new RadialGradient(0, 0, cx, cy, rim, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#14304D", .85)), new Stop(.6, Color.web("#0A1729", .55)), new Stop(1, Color.web("#070C18", 0))));
        g.fillOval(cx - rim, cy - rim, 2 * rim, 2 * rim);
        tabs(g, w);
        double[] p0 = scene.positions[i];
        double[][] directions = new double[mine.size()][];
        for (int k = 0; k < mine.size(); k++) {
            MoleculeScene.Pair p = mine.get(k);
            int other = p.a() == i ? p.b() : p.a();
            double[] q = scene.positions[other], d = {q[0] - p0[0], q[1] - p0[1], q[2] - p0[2]};
            double len = Math.max(1e-6, norm(d));
            directions[k] = new double[]{d[0] / len, d[1] / len, d[2] / len};
        }
        // Les axes de l'écran ; avant la première image, la caméra n'en a pas encore : ceux du monde.
        double[] right = scene.camera.right(), up = scene.camera.up(), forward = scene.camera.forward();
        if (norm(right) < .5 || norm(up) < .5 || norm(forward) < .5) {
            right = new double[]{1, 0, 0};
            up = new double[]{0, 1, 0};
            forward = new double[]{0, 0, 1};
        }
        // La normale de l'image précédente sert de départ : la carte ne bascule pas d'une image à l'autre.
        // La carte est orientée exactement comme l'écran : elle tourne avec la caméra.
        double[][] basis = {right, up, forward};
        // Les voisins : les liés sur un cercle proche, les autres au bord ; chacun dans sa direction projetée.
        placed.clear();
        double reach = rim - 14;
        double[] angle = new double[mine.size()], lift = new double[mine.size()];
        boolean[] bonded = new boolean[mine.size()];
        for (int k = 0; k < mine.size(); k++) {
            MoleculeScene.Pair p = mine.get(k);
            double[] d = directions[k];
            double px = dot(d, basis[0]), py = dot(d, basis[1]);
            angle[k] = Math.hypot(px, py) < 1e-3 ? 0 : Math.atan2(-py, px);
            lift[k] = -dot(d, basis[2]);
            bonded[k] = p.order() >= MoleculeScene.BOND_ORDER && scene.linked(p.a(), p.b());
        }
        for (boolean ring : new boolean[]{true, false}) {
            List<Integer> members = new ArrayList<>();
            for (int k = 0; k < mine.size(); k++) if (bonded[k] == ring) members.add(k);
            double r = ring ? BONDED * reach : reach;
            double[] a = new double[members.size()];
            for (int m = 0; m < a.length; m++) a[m] = angle[members.get(m)];
            spread(a, 2 * Math.asin(Math.min(1, 12.5 / r)));
            for (int m = 0; m < a.length; m++) angle[members.get(m)] = a[m];
        }
        for (int k = 0; k < mine.size(); k++) {
            MoleculeScene.Pair p = mine.get(k);
            int other = p.a() == i ? p.b() : p.a();
            double r = bonded[k] ? BONDED * reach : reach;
            MoleculeScene.Bond bond = null;
            for (MoleculeScene.Bond b : scene.bonds) if ((b.a() == i && b.b() == other) || (b.a() == other && b.b() == i)) bond = b;
            placed.add(new Neighbour(other, p, bond, cx + r * Math.cos(angle[k]), cy + r * Math.sin(angle[k]), lift[k],
                    pairForce(scene, p, i), bonded[k]));
        }
        // Une échelle pour toutes les forces : les flèches, et l'épaisseur des traits de l'essentiel.
        double biggest = norm(net);
        if (mode == BY_TERM) for (double[] f : terms) biggest = Math.max(biggest, norm(f));
        else for (Neighbour n : placed) biggest = Math.max(biggest, norm(n.force));
        double scale = biggest > 1e-9 ? LENGTH / biggest : 0;
        for (Neighbour n : placed) link(g, cx, cy, n, mode, biggest);
        for (Neighbour n : placed) neighbour(g, scene, n);
        nucleus(g, scene, i, cx, cy, atom.getCharge());
        if (mode == BY_TERM) {
            Color[] colours = {ELECTRONS, NUCLEI, LONDON, HAND};
            for (int t : new int[]{2, 3, 1, 0}) arrow(g, basis, cx, cy, terms[t], scale, colours[t].deriveColor(0, 1, 1, .9), 2, false);
        } else if (mode == BY_PAIR) {
            for (Neighbour n : placed) {
                Color colour = n.pair.total() >= 0 ? ATTRACT : REPEL;
                arrow(g, basis, cx, cy, n.force, scale, colour.deriveColor(0, 1, 1, .92), 2, false);
            }
            if (norm(rest) >= .02) arrow(g, basis, cx, cy, rest, scale, REST, 1.6, true);
        }
        if (norm(net) >= .02 && arrow(g, basis, cx, cy, net, scale, Color.web("#04070D", .7), 5.5, false)) {
            arrow(g, basis, cx, cy, net, scale, NET, mode == ESSENTIAL ? 3.2 : 2.8, false);
        } else {
            g.setStroke(NET.deriveColor(0, 1, 1, .7));
            g.setLineWidth(1);
            g.setLineDashes(2, 3);
            g.strokeOval(cx - 21, cy - 21, 42, 42);
            g.setLineDashes();
            g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 9.5));
            g.setTextAlign(TextAlignment.CENTER);
            g.setFill(NET);
            g.fillText(I18n.text("en équilibre"), cx, cy + 33);
        }
        header(g, w, atom, i, net, toward(scene, i, net, placed));
        g.setFont(Font.font("Segoe UI", 9));
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(DIM);
        g.fillText(I18n.text(mine.isEmpty() ? "aucun voisin : rien ne le tire ni ne le pousse"
                : "orienté comme à l'écran · survoler un voisin : ses valeurs"), cx, HEAD + MAP + 6);
        for (Neighbour n : placed) if (n.atom == hovered) callout(g, w, cx, cy, n);
        if (mode == ESSENTIAL) essentialLegend(g, w, legendTop);
        else if (mode == BY_TERM) termLegend(g, w, legendTop, terms, net, norm(terms[3]) > .005);
        else pairLegend(g, w, legendTop, net, rest);
        if (mode == ESSENTIAL) summary(g, w, tableTop, scene, placed);
        else table(g, w, scene, tableTop, placed);
        if (confined) {
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font("Segoe UI", 9.5));
            g.setFill(Color.web("#FFB37A"));
            double y = height - 22;
            g.fillText(I18n.text("charge tenue à −2,5 e par la contrainte des anions :"), 2, y);
            g.fillText(I18n.text("sans elle, GFN1 lui donnerait des électrons sans fin"), 2, y + 13);
        }
    }

    /** Où va la force nette, en mots : vers un voisin, ou loin de lui, quand elle est (presque) dans sa direction. */
    static String toward(MoleculeScene scene, int i, double[] net, List<Neighbour> placed) {
        double magnitude = norm(net);
        if (magnitude < .02) return "";
        double best = 0;
        Neighbour along = null;
        for (Neighbour n : placed) {
            double[] a = scene.positions[i], b = scene.positions[n.atom];
            double[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
            double c = dot(d, net) / (norm(d) * magnitude);
            if (Math.abs(c) > Math.abs(best)) {
                best = c;
                along = n;
            }
        }
        if (along == null || Math.abs(best) < .75) return "";
        String name = scene.symbols[along.atom] + (along.atom + 1);
        return I18n.text(best > 0 ? "vers" : "loin de") + " " + name;
    }

    /** Une flèche depuis le bord de l'atome : la projection de `f`, de longueur proportionnelle ; faux si trop courte. */
    private static boolean arrow(GraphicsContext g, double[][] basis, double cx, double cy, double[] f, double scale, Color colour,
                                 double width, boolean dashed) {
        double fx = dot(f, basis[0]) * scale, fy = -dot(f, basis[1]) * scale, len = Math.hypot(fx, fy);
        if (len < 3) return false;
        double sx = cx + fx / len * START, sy = cy + fy / len * START;
        if (dashed) g.setLineDashes(3, 3);
        MoleculeScene.arrow(g, sx, sy, sx + fx, sy + fy, colour, width);
        g.setLineDashes();
        return true;
    }

    /** Les trois lectures, en onglets au-dessus de la carte. */
    private void tabs(GraphicsContext g, double w) {
        String[] labels = {I18n.text("l'essentiel"), I18n.text("par voisin"), I18n.text("par terme")};
        Font font = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 9.5);
        double x = w - 2, y = HEAD + 2, h = 15;
        for (int k = labels.length - 1; k >= 0; k--) {
            Text probe = new Text(labels[k]);
            probe.setFont(font);
            double width = probe.getLayoutBounds().getWidth() + 14;
            x -= width;
            tabs[k] = new double[]{x, y, width, h};
            boolean on = mode == k;
            g.setFill(on ? Color.web("#1E3A5C", .95) : Color.web("#0B1626", .9));
            g.fillRoundRect(x, y, width, h, h, h);
            g.setStroke(on ? Color.web("#5CC8FF", .7) : Color.web("#2A3B52", .9));
            g.setLineWidth(.8);
            g.strokeRoundRect(x, y, width, h, h, h);
            g.setFont(font);
            g.setTextAlign(TextAlignment.CENTER);
            g.setFill(on ? Color.web("#EAF2FF") : GREY);
            g.fillText(labels[k], x + width / 2, y + 11);
            x -= 4;
        }
    }

    /** L'en-tête : la case de l'élément (Z, symbole, masse), puis sa charge, ses voisins, la force nette et sa direction. */
    private static void header(GraphicsContext g, double w, MoleculeAtom atom, int i, double[] net, String toward) {
        g.clearRect(0, 0, w, HEAD);
        Color element = MoleculeScene.elementColor(atom.getZ());
        g.setFill(element.deriveColor(0, 1, 1, .16));
        g.fillRoundRect(2, 3, 46, 46, 10, 10);
        g.setStroke(element.deriveColor(0, 1, 1, .8));
        g.setLineWidth(1.2);
        g.strokeRoundRect(2, 3, 46, 46, 10, 10);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", 8.5));
        g.setFill(GREY);
        g.fillText(String.valueOf(atom.getZ()), 6, 13);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 19));
        g.setFill(element.deriveColor(0, 1, 1.15, 1));
        g.fillText(MoleculeView.nuclide(atom.getSymbol(), atom.getMassNumber()), 25, 33);
        g.setFont(Font.font("Segoe UI", 8.5));
        g.setFill(GREY);
        g.fillText(MoleculeFormat.decimal(atom.getMassU(), 3), 25, 45);
        double q = atom.getCharge();
        float[] c = MoleculeScene.chargeColour(q);
        Color charge = Color.color(c[0], c[1], c[2]);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14));
        g.setFill(TEXT);
        g.fillText(atom.getSymbol() + (i + 1), 58, 17);
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11.5));
        g.setFill(charge);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(I18n.text("charge") + " " + MoleculeFormat.signed(q, 2) + " e" + (q < -.05 ? "  δ−" : q > .05 ? "  δ+" : ""), w - 2, 17);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", 10));
        g.setFill(GREY);
        g.fillText(I18n.text("voisins (coordination)") + "  " + MoleculeFormat.decimal(atom.getCoordination(), 2), 58, 32);
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5));
        g.setFill(NET);
        g.fillText(I18n.text("force nette") + "  " + MoleculeFormat.decimal(norm(net), 2) + " eV/Å"
                + (toward.isEmpty() ? "" : "  ·  " + toward), 58, 47);
    }

    /**
     * Le trait vers un voisin. L'essentiel : coloré par ce que la paire se fait (bleu attraction, orange répulsion), son
     * épaisseur suit la force, plein s'ils sont liés, en tirets sinon. Par voisin : un trait discret sous les flèches.
     * Par terme : un trait fin, bleu s'ils s'attirent, orange s'ils se repoussent.
     */
    private static void link(GraphicsContext g, double cx, double cy, Neighbour n, int mode, double biggest) {
        MoleculeScene.Pair p = n.pair;
        double dx = n.x - cx, dy = n.y - cy, len = Math.hypot(dx, dy);
        if (len < 1) return;
        double ux = dx / len, uy = dy / len, force = p.total();
        double x0 = cx + ux * 16, y0 = cy + uy * 16, x1 = n.x - ux * 12, y1 = n.y - uy * 12;
        if (mode == ESSENTIAL) {
            double strength = biggest > 1e-9 ? Math.min(1, norm(n.force) / biggest) : 0;
            Color colour = Math.abs(force) < .02 ? GREY : force >= 0 ? ATTRACT : REPEL;
            g.setStroke(colour.deriveColor(0, 1, 1, .25 + .6 * Math.sqrt(strength)));
            g.setLineWidth(1 + 5 * strength);
            if (!n.bonded) g.setLineDashes(3, 4 + 2 * strength);
            g.strokeLine(x0, y0, x1, y1);
            g.setLineDashes();
            return;
        }
        Color colour = mode == BY_TERM && Math.abs(force) >= .02 ? (force >= 0 ? ATTRACT : REPEL).deriveColor(0, 1, 1, .55)
                : n.bonded ? Color.web("#B9CBE0", .22) : Color.web("#8FA4C2", .35);
        g.setStroke(colour);
        g.setLineWidth(n.bonded ? 1.2 + 1.1 * Math.min(3, p.order()) : 1);
        if (!n.bonded) g.setLineDashes(1.5, 3.5);
        g.strokeLine(x0, y0, x1, y1);
        g.setLineDashes();
    }

    /** Au survol d'un voisin : sa distance, la force de la paire et ce qu'elle fait, dans une étiquette à côté de lui. */
    private static void callout(GraphicsContext g, double w, double cx, double cy, Neighbour n) {
        MoleculeScene.Pair p = n.pair;
        String first = MoleculeFormat.decimal(p.distance(), 3) + " Å", second = MoleculeFormat.decimal(norm(n.force), 2) + " eV/Å  "
                + I18n.text(p.total() >= 0 ? "l'attire" : "le repousse");
        double bw = 112, bh = 30, ux = n.x - cx, uy = n.y - cy, len = Math.max(1, Math.hypot(ux, uy));
        double x = n.x + ux / len * 16 - bw / 2, y = n.y + uy / len * 16 - bh / 2;
        x = Math.max(2, Math.min(w - bw - 2, x));
        y = Math.max(HEAD + 20, Math.min(HEAD + MAP - bh, y));
        g.setFill(Color.web("#081120", .94));
        g.fillRoundRect(x, y, bw, bh, 8, 8);
        g.setStroke(Color.web("#3B4F6B", .9));
        g.setLineWidth(.8);
        g.strokeRoundRect(x, y, bw, bh, 8, 8);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 9.5));
        g.setFill(TEXT);
        g.fillText(first, x + 7, y + 12);
        g.setFont(Font.font("Segoe UI", 9.5));
        g.setFill(p.total() >= 0 ? ATTRACT : REPEL);
        g.fillText(second, x + 7, y + 25);
    }

    /** Un voisin : un disque à la couleur de son élément, plus petit et plus pâle s'il est derrière le plan. */
    private static void neighbour(GraphicsContext g, MoleculeScene scene, Neighbour n) {
        int z = n.atom < scene.z.length ? scene.z[n.atom] : 0;
        Color element = MoleculeScene.elementColor(z);
        double r = 10.5 * (1 + .18 * Math.max(-1, Math.min(1, n.lift))), alpha = n.lift < -.35 ? .6 : 1;
        g.setFill(new RadialGradient(0, 0, n.x - r * .35, n.y - r * .35, r * 1.4, false, CycleMethod.NO_CYCLE,
                new Stop(0, element.deriveColor(0, .7, 1.25, alpha)), new Stop(1, element.deriveColor(0, 1, .55, alpha))));
        g.fillOval(n.x - r, n.y - r, 2 * r, 2 * r);
        float[] c = MoleculeScene.chargeColour(n.atom < scene.charges.length ? scene.charges[n.atom] : 0);
        g.setStroke(Color.color(c[0], c[1], c[2], .8 * alpha));
        g.setLineWidth(1.3);
        g.strokeOval(n.x - r, n.y - r, 2 * r, 2 * r);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(element.getBrightness() > .7 ? Color.web("#0A1220", alpha) : Color.web("#F4F8FF", alpha));
        g.fillText(scene.symbols[n.atom] + (n.atom + 1), n.x, n.y + 3);
    }

    /** L'atome lui-même, au centre : son élément, le liseré de sa charge. */
    private static void nucleus(GraphicsContext g, MoleculeScene scene, int i, double cx, double cy, double q) {
        Color element = MoleculeScene.elementColor(scene.z[i]);
        float[] c = MoleculeScene.chargeColour(q);
        Color charge = Color.color(c[0], c[1], c[2]);
        double r = 15;
        g.setFill(new RadialGradient(0, 0, cx, cy, r + 9, false, CycleMethod.NO_CYCLE,
                new Stop(.55, charge.deriveColor(0, 1, 1, .35 * Math.min(1, Math.abs(q) / .4 + .3))), new Stop(1, Color.TRANSPARENT)));
        g.fillOval(cx - r - 9, cy - r - 9, 2 * r + 18, 2 * r + 18);
        g.setFill(new RadialGradient(0, 0, cx - r * .35, cy - r * .35, r * 1.4, false, CycleMethod.NO_CYCLE,
                new Stop(0, element.deriveColor(0, .7, 1.3, 1)), new Stop(1, element.deriveColor(0, 1, .55, 1))));
        g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setStroke(charge);
        g.setLineWidth(1.8);
        g.strokeOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(element.getBrightness() > .7 ? Color.web("#0A1220") : Color.web("#F4F8FF"));
        g.fillText(scene.symbols[i] + (i + 1), cx, cy + 4);
    }

    private static void key(GraphicsContext g, double x, double y, Color colour, double width, boolean dashed, String text,
                            Color ink, boolean strong) {
        g.setStroke(colour);
        g.setLineWidth(width);
        if (dashed) g.setLineDashes(3, 3);
        g.strokeLine(x, y - 3.5, x + 14, y - 3.5);
        g.setLineDashes();
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", strong ? FontWeight.SEMI_BOLD : FontWeight.NORMAL, 10.5));
        g.setFill(ink);
        g.fillText(text, x + 20, y);
    }

    /** L'essentiel : la flèche, les deux couleurs, ce que disent l'épaisseur et les tirets. */
    private static void essentialLegend(GraphicsContext g, double w, double y) {
        key(g, 2, y, NET, 3.2, false, I18n.text("la force nette : où l'atome est poussé"), NET, true);
        key(g, 2, y + ROW, ATTRACT, 3, false, I18n.text("ils s'attirent"), TEXT, false);
        key(g, w / 2 + 4, y + ROW, REPEL, 3, false, I18n.text("ils se repoussent"), TEXT, false);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", 9.5));
        g.setFill(GREY);
        g.fillText(I18n.text("trait plein : liés · tirets : non liés · épaisseur : la force"), 2, y + 2 * ROW);
        g.fillText(I18n.text("le détail des forces : « par voisin », « par terme »"), 2, y + 3 * ROW);
    }

    /** Par voisin : la nette (la somme des flèches), ce qui attire, ce qui repousse, le reste. */
    private static void pairLegend(GraphicsContext g, double w, double y, double[] net, double[] rest) {
        key(g, 2, y, NET, 2.8, false, I18n.text("nette") + "  " + MoleculeFormat.decimal(norm(net), norm(net) < .1 ? 3 : 2)
                + " eV/Å  " + I18n.text("(la somme des flèches)"), NET, true);
        key(g, 2, y + ROW, ATTRACT, 2, false, I18n.text("un voisin qui l'attire"), TEXT, false);
        key(g, w / 2 + 4, y + ROW, REPEL, 2, false, I18n.text("qui le repousse"), TEXT, false);
        boolean more = norm(rest) >= .02;
        if (more) {
            key(g, 2, y + 2 * ROW, REST, 1.6, true, I18n.text("le reste (paires lointaines, halogène, main)") + "  "
                    + MoleculeFormat.decimal(norm(rest), 2), TEXT, false);
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", 9.5));
        g.setFill(GREY);
        g.fillText(I18n.text("une flèche de travers : la paire tient aussi l'angle"), 2, y + (more ? 3 : 2) * ROW);
    }

    /** Par terme : la nette, les électrons, les noyaux, London, la main ; les chevrons des paires. */
    private static void termLegend(GraphicsContext g, double w, double y, double[][] terms, double[] net, boolean hand) {
        key(g, 2, y, NET, 2.8, false, I18n.text("nette") + "  " + MoleculeFormat.decimal(norm(net), norm(net) < .1 ? 3 : 2)
                + " eV/Å  " + I18n.text("(la somme)"), NET, true);
        String[] names = {"électrons", "noyaux", "London", "main / paroi"};
        Color[] colours = {ELECTRONS, NUCLEI, LONDON, HAND};
        for (int t = 0; t < (hand ? 4 : 3); t++) {
            double x = t % 2 == 0 ? 2 : w / 2 + 4, ly = y + ROW * (1 + t / 2);
            double value = norm(terms[t]);
            key(g, x, ly, colours[t], 2, false, I18n.text(names[t]) + "  " + MoleculeFormat.decimal(value, value < .1 ? 3 : 2),
                    TEXT, false);
        }
        double ly = y + 3 * ROW;
        key(g, 2, ly, ATTRACT.deriveColor(0, 1, 1, .7), 1.6, false, I18n.text("la paire s'attire"), ATTRACT, false);
        key(g, w / 2 + 4, ly, REPEL.deriveColor(0, 1, 1, .7), 1.6, false, I18n.text("elle se repousse"), REPEL, false);
    }

    /**
     * L'essentiel, en liste : les liés puis les non liés, une barre par force (la même échelle pour tous), et en une
     * ligne la nature de la liaison ou la cause de ce qu'ils se font.
     */
    private static void summary(GraphicsContext g, double w, double top, MoleculeScene scene, List<Neighbour> placed) {
        double biggest = 0;
        for (Neighbour n : placed) biggest = Math.max(biggest, norm(n.force));
        double y = top + 2;
        for (boolean group : new boolean[]{true, false}) {
            List<Neighbour> members = new ArrayList<>();
            for (Neighbour n : placed) if (n.bonded == group) members.add(n);
            if (members.isEmpty()) continue;
            if (!group && y > top + 2) y += 6;
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
            g.setFill(GREY);
            g.fillText(I18n.text(group ? "LIÉS" : "NON LIÉS"), 2, y);
            y += 16;
            for (Neighbour n : members) {
                MoleculeScene.Pair p = n.pair;
                double force = norm(n.force), along = p.total();
                Color colour = Math.abs(along) < .02 ? DIM : along >= 0 ? ATTRACT : REPEL;
                g.setFill(MoleculeScene.elementColor(scene.z[n.atom]));
                g.fillOval(2, y - 8, 8, 8);
                g.setTextAlign(TextAlignment.LEFT);
                g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5));
                g.setFill(TEXT);
                g.fillText(scene.symbols[n.atom] + (n.atom + 1), 16, y);
                g.setFont(Font.font("Segoe UI", 10));
                g.setFill(GREY);
                g.fillText(MoleculeFormat.decimal(p.distance(), 2) + " Å", 48, y);
                double barX = 96, barW = w - 2 - 64 - barX, filled = biggest > 1e-9 ? barW * force / biggest : 0;
                g.setFill(Color.web("#16202F"));
                g.fillRoundRect(barX, y - 7, barW, 6, 6, 6);
                g.setFill(colour.deriveColor(0, 1, 1, .9));
                g.fillRoundRect(barX, y - 7, Math.max(3, filled), 6, 6, 6);
                g.setTextAlign(TextAlignment.RIGHT);
                g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5));
                g.setFill(colour);
                g.fillText(MoleculeFormat.decimal(force, 2) + " eV/Å", w - 2, y);
                y += SUB;
                g.setTextAlign(TextAlignment.LEFT);
                g.setFont(Font.font("Segoe UI", 9));
                g.setFill(GREY);
                String verb = Math.abs(along) < .02 ? "" : I18n.text(along >= 0 ? "l'attire" : "le repousse") + " · ";
                String why;
                if (group) {
                    // Deux lignes : la nature de la liaison (sa part π), puis ce qu'elle fait et où est sa paire.
                    MoleculeScene.Bond b = n.bond;
                    g.fillText(I18n.text(character(b)) + (b != null && b.pi() >= .1 ? "  ·  σ " + MoleculeFormat.decimal(b.sigma(), 2)
                            + " + π " + MoleculeFormat.decimal(b.pi(), 2) : ""), 16, y);
                    y += SUB;
                    why = verb + (along < -.02 ? I18n.text("liaison comprimée : les cœurs se repoussent")
                            : I18n.text("les électrons partagés le tiennent")) + pairShare(scene, n);
                } else {
                    boolean broken = p.order() >= MoleculeScene.BOND_ORDER;
                    double qa = scene.charges[p.a() == n.atom ? p.b() : p.a()], qb = scene.charges[n.atom];
                    why = verb + (broken ? I18n.text("liaison rompue : plus rien de partagé")
                            : I18n.text(cause(p, qa, qb)));
                }
                g.fillText(why, 16, y);
                y += ROW + 7;
            }
        }
    }

    /** « · paire 54 % chez N6 » : la part de la paire de la liaison que garde l'atome qui en tient le plus. */
    private static String pairShare(MoleculeScene scene, Neighbour n) {
        MoleculeScene.Bond bond = n.bond;
        if (bond == null || !bond.twoCentre()) return "";
        int holder = bond.share() >= .5 ? bond.b() : bond.a();
        double part = Math.max(bond.share(), 1 - bond.share());
        return " · " + I18n.text("paire") + " " + MoleculeFormat.decimal(100 * part, 0) + " % " + I18n.text("chez") + " "
                + scene.symbols[holder] + (holder + 1);
    }

    /** Le détail de chaque voisin : distance, liaison, sa force (le long de l'axe, de travers), de quoi elle est faite. */
    private static void table(GraphicsContext g, double w, MoleculeScene scene, double top, List<Neighbour> placed) {
        double y = top + 2;
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
        g.setFill(GREY);
        g.fillText(I18n.text("VOISINS"), 2, y);
        if (placed.isEmpty()) return;
        y += 18;
        for (Neighbour n : placed) {
            MoleculeScene.Pair p = n.pair;
            boolean bonded = p.order() >= MoleculeScene.BOND_ORDER, broken = bonded && !n.bonded;
            g.setFill(MoleculeScene.elementColor(scene.z[n.atom]));
            g.fillOval(2, y - 8, 8, 8);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5));
            g.setFill(TEXT);
            String bond = broken ? I18n.text("liaison rompue") : bonded ? I18n.text("liaison") + " " + MoleculeFormat.decimal(p.order(), 2)
                    : I18n.text("non liés");
            g.fillText(scene.symbols[n.atom] + (n.atom + 1) + "   " + MoleculeFormat.decimal(p.distance(), 3) + " Å   " + bond, 16, y);
            double along = p.total(), whole = norm(n.force), across = Math.sqrt(Math.max(0, whole * whole - along * along));
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFill(whole < .02 ? DIM : along >= 0 ? ATTRACT : REPEL);
            g.fillText(MoleculeFormat.decimal(whole, 2) + " eV/Å", w - 2, y);
            y += ROW;
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font("Segoe UI", 9.5));
            g.setFill(along >= 0 ? ATTRACT.deriveColor(0, .7, .9, 1) : REPEL.deriveColor(0, .7, .9, 1));
            String axis = Math.abs(along) < .02 ? I18n.text("le long de l'axe : presque rien")
                    : I18n.text(along >= 0 ? "le long de l'axe : ils s'attirent" : "le long de l'axe : ils se repoussent") + " "
                    + MoleculeFormat.decimal(Math.abs(along), 2);
            g.fillText(axis + (across >= .02 ? "  ·  " + I18n.text("de travers") + " " + MoleculeFormat.decimal(across, 2) : ""), 16, y);
            y += ROW;
            g.setFill(GREY);
            g.fillText(I18n.text("électrons") + " " + MoleculeFormat.signed(p.electronic(), 2) + "  ·  " + I18n.text("noyaux") + " "
                    + MoleculeFormat.signed(p.repulsion(), 2) + "  ·  London " + MoleculeFormat.signed(p.dispersion(), 3), 16, y);
            y += ROW;
            g.fillText(I18n.text("électrons") + " = " + I18n.text("recouvrement") + " " + MoleculeFormat.signed(p.overlap(), 2)
                    + "  +  " + I18n.text("charges") + " " + MoleculeFormat.signed(p.charges(), 2), 16, y);
            if (bonded) {
                y += ROW;
                boolean pi = n.bond != null && n.bond.pi() >= .1 && !broken;
                g.fillText((pi ? "σ " + MoleculeFormat.decimal(n.bond.sigma(), 2) + " + π " + MoleculeFormat.decimal(n.bond.pi(), 2)
                        + "  ·  " : "") + pairText(scene, n, pi), 16, y);
            }
            y += ROW + 7;
        }
    }

    /** La paire de la liaison : la part que garde l'atome qui en tient le plus, ou la paire étalée, ou deux ions. */
    private static String pairText(MoleculeScene scene, Neighbour n, boolean brief) {
        MoleculeScene.Pair p = n.pair;
        if (!n.bonded) return I18n.text("plus rien de partagé entre les noyaux");
        MoleculeScene.Bond bond = n.bond;
        if (bond == null) return "";
        if (bond.twoCentre()) {
            int holder = bond.share() >= .5 ? bond.b() : bond.a();
            double part = Math.max(bond.share(), 1 - bond.share());
            return I18n.text(brief ? "paire" : "paire de la liaison") + "  " + MoleculeFormat.decimal(100 * part, 0) + " % "
                    + I18n.text("chez") + " " + scene.symbols[holder] + (holder + 1);
        }
        for (MoleculeScene.Multicentre m : scene.multicentres) {
            if (!m.holds(bond.a(), bond.b())) continue;
            StringBuilder members = new StringBuilder();
            for (int atom : m.atoms()) {
                if (members.length() > 0) members.append(" · ");
                members.append(scene.symbols[atom]).append(atom + 1);
            }
            return I18n.text("paire à " + m.atoms().length + " centres") + "  " + members;
        }
        return I18n.text("aucune paire partagée : deux ions");
    }
}
