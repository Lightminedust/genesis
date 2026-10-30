package org.genesis.molecule;

import org.genesis.app.I18n;
import org.genesis.common.LabCamera;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * La molécule, épurée : des bulles de verre, des noyaux incandescents, des faisceaux de lumière. Chaque trait porte
 * une grandeur calculée.
 *
 * <ul>
 * <li><b>La sphère</b> : l'atome, de rayon 0,45 × son rayon covalent (Pyykkö 2009) — toujours à distance de ses
 *     voisins liés ; deux sphères qui se chevauchent à l'écran sont l'une derrière l'autre, et celle de devant voile
 *     celle de derrière. Verre presque transparent, bord de Fresnel bleu ; le liseré tire vers le magenta quand l'atome
 *     a gagné des électrons (δ−), vers le cyan quand il en a perdu (δ+).</li>
 * <li><b>Le halo de polarisation</b> : le nuage des électrons de valence de chaque molécule vu en transparence, bordé
 *     par le contour où le regard croise 0,3 électron par Å² (voir {@link MoleculeHalo}). Chaque atome y apporte les
 *     électrons qu'il garde (charge de Mulliken) : le halo est fourni et magenta autour des atomes δ−, mince et cyan
 *     autour des atomes δ+. Option : la carte de potentiel conventionnelle — le potentiel électrostatique exact de la
 *     molécule sur la surface ρ = 0,002 u.a.</li>
 * <li><b>Le point incandescent</b> : le noyau.</li>
 * <li><b>Les arcs intérieurs</b> : partis du noyau, ils sont captés par les points où les liaisons percent la bulle —
 *     d'autant plus que la liaison est forte (indice de Mayer) — et se penchent dans le sens où le nuage de l'atome est
 *     déformé (son dipôle atomique, calculé à partir de la densité). Un atome libre et symétrique a des rayons droits.</li>
 * </ul>
 * Les liaisons suivent les paires d'électrons localisées (Pipek–Mezey) qui les tiennent :
 * <ul>
 * <li><b>Le trait</b> : une paire sur deux atomes, d'une bulle à l'autre ; son éclat suit l'indice de liaison. Sa
 *     part π : deux lobes de verre de part et d'autre, le long de la direction calculée de la densité π (éthylène :
 *     la normale à son plan ; N₂ : deux paires de lobes perpendiculaires). Sa couleur dit la force interne le long de
 *     la liaison, ½ (F_A − F_B)·u : bleu nulle, blanc en traction, orange en compression. Ses grains : les électrons de
 *     la paire, tirés de sa densité le long de l'axe (étalés sur C–H, plus denses côté O dans O–H).</li>
 * <li><b>L'étoile</b> : une paire sur trois atomes ou plus (liaison multicentre, notation de Lipscomb), des rayons de
 *     son barycentre vers ses atomes : le pont B–H–B du diborane, les agrégats métalliques (Au₆ : trois paires, chacune
 *     sur un triangle ; aucune sur un seul couple d'atomes).</li>
 * <li><b>Les points</b> : la force entre deux atomes sans paire partagée — entre atomes séparés par au moins quatre
 *     liaisons, par des molécules différentes, ou deux ions (NaCl : la paire reste sur Cl). Ils glissent vers le milieu
 *     quand elle les attire (liaison hydrogène, van der Waals, ions) ; orange, ils partent de là où les deux nuages se
 *     touchent et s'écartent vers les atomes quand elle les repousse. Leur éclat et leur vitesse suivent la force.</li>
 * </ul>
 * Les lignes du champ électrique restent disponibles, éteintes.
 * Le rendu a trois couches : le dessin net, et deux calques flous ajoutés par-dessus (bloom), dessinés par la vue.
 */
final class MoleculeScene {
    /**
     * Une paire d'atomes et ce qu'ils se font, le long de l'axe (eV/Å ; + attire), puis la force entière que la paire
     * exerce sur a (sur b : l'opposée ; null si le moteur ne l'envoie pas) — de travers, elle tient les angles. La part
     * des électrons en deux (recouvrement des orbitales, électrostatique des charges) et la population de recouvrement
     * (e ; < 0 : antiliante, deux couches pleines qui se touchent).
     */
    record Pair(int a, int b, double distance, double order, double electronic, double repulsion, double dispersion,
                double total, double[] force, double overlap, double charges, double population) {
        Pair(int a, int b, double distance, double order, double electronic, double repulsion, double dispersion,
             double total, double[] force) {
            this(a, b, distance, order, electronic, repulsion, dispersion, total, force, electronic, 0, 0);
        }

        /** La force de la paire sur l'atome `atom` (l'un des deux), ou null. */
        double[] forceOn(int atom) {
            if (force == null) return null;
            return atom == a ? force.clone() : new double[]{-force[0], -force[1], -force[2]};
        }
    }

    /**
     * Une liaison : indice de Mayer, sa part σ / π, et la part de ses paires à deux centres du côté de b (−1 : elle
     * n'en a pas ; elle tient alors par une paire à plusieurs centres, ou par l'attraction de deux ions). Où se tient
     * la part π autour de l'axe : sa direction principale (null sans orbitales p) et la part π le long d'elle, puis le
     * long de (axe × elle). Le profil de ses paires le long de l'axe, de a à b, en tranches égales (somme 1 ; vide
     * sans paire à deux centres).
     */
    record Bond(int a, int b, double order, double sigma, double pi, double share, double[] piAxis, double[] piSplit,
                float[] profile, double shared) {
        Bond(int a, int b, double order, double sigma, double pi, double share) {
            this(a, b, order, sigma, pi, share, null, new double[2], new float[0], 1);
        }

        boolean twoCentre() {
            return share >= 0;
        }

        /** Liés : un indice de liaison, et des électrons encore partagés entre les noyaux (voir SHARED_BOND). */
        boolean bonded() {
            return order >= BOND_ORDER && shared >= SHARED_BOND;
        }
    }

    /** Une paire d'électrons étalée sur trois atomes ou plus : ses atomes (part décroissante), leurs parts, son barycentre (Å). */
    record Multicentre(int[] atoms, double[] populations, double[] centroid) {
        boolean holds(int a, int b) {
            boolean hasA = false, hasB = false;
            for (int atom : atoms) {
                hasA |= atom == a;
                hasB |= atom == b;
            }
            return hasA && hasB;
        }
    }

    static final double SPHERE = .45, BOHR = 0.529177;
    /** Les vues : la densité (bulles, halo, liaisons), la déformation (carte en courbes de niveau), une orbitale (lobes). */
    static final int DENSITY = DensityGrid.DENSITY, DEFORMATION = DensityGrid.DEFORMATION, ORBITAL = DensityGrid.ORBITAL;
    /** Le plus petit Δρ dessiné (e/Å³) : en deçà, ce n'est plus une déformation mais le bruit du calcul. */
    static final double DEFORMATION_FLOOR = .01;
    /** La part de lumière qu'une bulle laisse passer de l'atome qu'elle cache. */
    static final double BEHIND_GLASS = .3;
    /** La surface des cartes de potentiel (u.a. = e/bohr³), la convention des manuels. */
    static final double MAP_AU = .002;
    /** Les échelles des couleurs : charge partielle (e) et potentiel (V) qui saturent le magenta ou le cyan. */
    static final double CHARGE_SCALE = .4, POTENTIAL_SCALE = 2.5;
    static final double BOND_ORDER = .35, SIGN_CHARGE = .1;
    /** Le trait d'une liaison s'éteint continûment quand son indice descend vers FADE_FROM (il n'y a pas de seuil
     *  « liaison / pas de liaison » dans le calcul : l'indice de Mayer décroît avec l'étirement). */
    static final double FADE_FROM = .12;
    /** Le trait s'éteint aussi quand les deux atomes ne partagent plus d'électrons entre eux (MoleculeBond.shared : la
     *  densité d'interférence au milieu de l'axe, rapportée à celle d'une liaison simple ordinaire) : entier dès
     *  SHARED_FULL, éteint sous SHARED_FROM. Sans cela, une liaison rompue garde son trait : dans un calcul à couches
     *  fermées, ses deux électrons restent appariés d'un fragment à l'autre (O–O à 3 Å : indice 1,06, partage 0,005).
     *  SHARED_BOND, au milieu, sépare liés et non liés, comme le recensement des espèces du moteur. */
    static final double SHARED_FROM = .03, SHARED_FULL = .15, SHARED_BOND = .09;
    private static final double LOG_LO = Math.log10(1e-4), LOG_HI = Math.log10(8.0);
    private static final double MAP_LEVEL = level(MAP_AU);
    private static final double HARTREE_EV = 27.211386, FINE_SPACING = .22, GAUSS_LINES = 12;
    static final Color GLASS = Color.web("#8FD3FF"), RICH = Color.web("#FF6FDC"), POOR = Color.web("#62EEFF");
    static final Color BEAM = Color.web("#5FD0FF"), CORE = Color.web("#F4FCFF"), TENSE = Color.web("#E6F8FF");
    static final Color SQUEEZED = Color.web("#FF7A45"), HELIX = Color.web("#A6E9FF"), DUST = Color.web("#C9EEFF");
    static final Color NUCLEUS = Color.web("#FF6528"), FIELD = Color.web("#A9DCFF"), DOTS = Color.web("#A8DBFF");
    /** Les paires à plusieurs centres : un or pâle, distinct des faisceaux (bleus, blancs ou orangés). */
    static final Color SHARED = Color.web("#FFD98C");
    private static final String[] JMOL = ("FFFFFF D9FFFF CC80FF C2FF00 FFB5B5 909090 3050F8 FF0D0D 90E050 B3E3F5 AB5CF2 "
            + "8AFF00 BFA6A6 F0C8A0 FF8000 FFFF30 1FF01F 80D1E3 8F40D4 3DFF00 E6E6E6 BFC2C7 A6A6AB 8A99C7 9C7AC7 E06633 "
            + "F090A0 50D050 C88033 7D80B0 C28F8F 668F8F BD80E3 FFA100 A62929 5CB8D1 702EB0 00FF00 94FFFF 94E0E0 73C2C9 "
            + "54B5B5 3B9E9E 248F8F 0A7D8C 006985 C0C0C0 FFD98F A67573 668080 9E63B5 D47A00 940094 429EB0 57178F 00C900 "
            + "70D4FF FFFFC7 D9FFC7 C7FFC7 A3FFC7 8FFFC7 61FFC7 45FFC7 30FFC7 1FFFC7 00FF9C 00E675 00D452 00BF38 00AB24 "
            + "4DC2FF 4DA6FF 2194D6 267DAB 266696 175487 D0D0E0 FFD123 B8B8D0 A6544D 575961 9E4FB5 AB5C00 754F45 428296").split(" ");

    final LabCamera camera = new LabCamera();
    int[] z = new int[0];
    String[] symbols = new String[0];
    /** L'isotope de chaque atome (nombre de masse ; 0 : le mélange naturel), écrit en exposant devant son symbole. */
    int[] massNumbers = new int[0];
    double[][] positions = new double[0][];
    double[] charges = new double[0], hardness = new double[0], covalent = new double[0];
    /** Par atome : la force nette, la part externe (main, paroi) et le dipôle atomique (e·Å). */
    double[][] netForce = new double[0][], externalForce = new double[0][], polarization = new double[0][];
    final List<Pair> pairs = new ArrayList<>();
    final List<Bond> bonds = new ArrayList<>();
    final List<Multicentre> multicentres = new ArrayList<>();
    /** Par élément, deux tailles de l'atome libre (Å) : sa surface à 0,002 e/bohr³ et le maximum de son orbitale p. */
    final Map<Integer, double[]> atomRadii = new HashMap<>();
    double wall;
    int hovered = -1, selected = -1, dragged = -1;
    double[] dragTarget;
    double tetherNewtons;
    boolean showCharges, showOrders, showSymbols = true, showArrows, showHalo = true, showPotential, showField, showBonds = true,
            showInner = true, showContacts = true, showLegend = true;
    /** La légende repliée sur son titre (un clic sur le titre la replie ou la déplie), et où est ce titre. */
    boolean legendFolded;
    private double[] legendTitle = new double[4];
    private DensityGrid grid, field, tint, potentialGrid;
    /**
     * La vue montrée (DENSITY, DEFORMATION, ORBITAL) : c'est elle, et non le genre de la dernière grille reçue, qui dit
     * quoi dessiner — une vue ρ gardait sinon les lobes de la vue précédente. Pour ψ, l'orbitale voulue (−1 : celle que
     * le moteur montre) et depuis quand elle l'est.
     */
    private int mode = DENSITY, wantedOrbital = -1, orientedOrbital = -1;
    private double wantedSince;
    /** La caméra est où la scène l'a placée (personne ne l'a tournée depuis) : elle suit alors la vue choisie. */
    boolean autoView = true;
    final DeformationMap deformation = new DeformationMap();
    /** La vue ψ : le plus grand |ψ| de l'orbitale (la densité y vaut 1), les briques pour sauter le vide, l'image. */
    private float[] orbitalDots = new float[0];
    /**
     * La vue ψ en verre (voir OrbitalSurface, OrbitalLayer) : vrai quand la vue a une couche 3D sous la scène ; les lobes
     * de la grille montrée, null sans grille. Sans 3D (pas de carte graphique), la vue garde ses points.
     */
    boolean glass;
    private OrbitalSurface.Mesh surface;
    /**
     * Les lobes se construisent hors du fil de l'interface (60 à 80 ms pour la caféine) : la dernière grille demandée,
     * et le maillage prêt avec la grille dont il vient. Jusque-là, les lobes d'avant restent (pendant la dynamique).
     */
    private record Built(DensityGrid grid, OrbitalSurface.Mesh mesh) {}
    private static final java.util.concurrent.ExecutorService SURFACES = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "orbital-surface");
        thread.setDaemon(true);
        return thread;
    });
    private volatile DensityGrid requested;
    private volatile Built built;
    private WritableImage dotImage;
    private int[] dotPixels = new int[0];
    private float[] dotLight = new float[0];
    private int[] dotBox = new int[4];
    /** Le halo de polarisation, et la taille d'un pixel logique en pixels de l'écran (plus de 1 en haute densité). */
    final MoleculeHalo halo = new MoleculeHalo();
    double pixelScale = 1;
    /** La molécule (composante connexe des liaisons) de chaque atome ; la version change avec les atomes. */
    private int[] fragment = new int[0];
    private long atomsVersion;
    private WritableImage fog;
    private int[] fogPixels = new int[0];
    private int fogW, fogH;
    private String fogKey = "";
    private boolean sharp;
    private final double[] center = new double[3];
    private boolean centered;
    private double clock;
    /** Les arcs de chaque atome, en coordonnées de la bulle (rayon 1), et ce qui les a guidés (voir computeArcs). */
    private final List<List<double[]>> arcs = new ArrayList<>();
    private final List<double[]> arcInputs = new ArrayList<>();
    private int[] arcAtoms = new int[0];
    private static final double ARC_TOLERANCE = .004;
    /** Distances topologiques (nombre de liaisons) entre atomes ; Integer.MAX_VALUE : pas reliés. */
    private int[][] hops = new int[0][];
    private List<double[][]> fieldLines = new ArrayList<>();
    /** Les liaisons de la frame précédente, pour voir celles qui naissent (éclair) et celles qui cassent (gerbe). */
    private Set<Long> lastBonds = new HashSet<>();
    private int[] lastZ = new int[0];
    private final Map<Long, Double> flashes = new HashMap<>();
    private final List<double[]> bursts = new ArrayList<>();

    MoleculeScene() {
        camera.zoom = Math.log10(6);
        camera.pitch = 0.35;
        camera.yaw = -0.5;
        camera.fovHalfDeg = 22;
    }

    double sphere(int atom) {
        return SPHERE * (atom < covalent.length && covalent[atom] > 0 ? covalent[atom] : .7);
    }

    // ================================================================== les données
    /** Après chaque frame : arcs intérieurs, distances topologiques, naissances et ruptures de liaisons. */
    void updateAtoms() {
        int n = positions.length;
        computeHops(n);
        fragment = new int[n];
        for (int a = 0; a < n; a++) {
            fragment[a] = a;
            for (int b = 0; b < a; b++) {
                if (hops[a][b] != Integer.MAX_VALUE) {
                    fragment[a] = fragment[b];
                    break;
                }
            }
        }
        atomsVersion++;
        computeArcs(n);
        Set<Long> now = new HashSet<>();
        for (Bond b : bonds) if (fade(b) > 0 && b.twoCentre()) now.add(key(b.a, b.b));
        if (Arrays.equals(z, lastZ)) {
            for (Long k : now) if (!lastBonds.contains(k)) flashes.put(k, clock);
            for (Long k : lastBonds) {
                if (now.contains(k)) continue;
                int a = (int) (k >> 32), b = (int) (long) k;
                if (a < n && b < n) {
                    double[] m = {(positions[a][0] + positions[b][0]) / 2, (positions[a][1] + positions[b][1]) / 2,
                            (positions[a][2] + positions[b][2]) / 2, clock, k};
                    bursts.add(m);
                }
            }
        }
        lastBonds = now;
        lastZ = z.clone();
    }

    private static long key(int a, int b) {
        return ((long) Math.min(a, b) << 32) | Math.max(a, b);
    }

    private void computeHops(int n) {
        hops = new int[n][n];
        List<List<Integer>> neighbours = new ArrayList<>();
        for (int i = 0; i < n; i++) neighbours.add(new ArrayList<>());
        for (Bond b : bonds) {
            if (!b.bonded() || b.a >= n || b.b >= n) continue;
            neighbours.get(b.a).add(b.b);
            neighbours.get(b.b).add(b.a);
        }
        for (int s = 0; s < n; s++) {
            Arrays.fill(hops[s], Integer.MAX_VALUE);
            hops[s][s] = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(s);
            while (!queue.isEmpty()) {
                int a = queue.poll();
                for (int b : neighbours.get(a)) {
                    if (hops[s][b] == Integer.MAX_VALUE) {
                        hops[s][b] = hops[s][a] + 1;
                        queue.add(b);
                    }
                }
            }
        }
    }

    /**
     * Les arcs d'un atome, tracés dans sa bulle (rayon 1) le long du champ
     *     F(ρ) = ρ̂/ρ² + Σ_liaisons s_b (u_b − ρ)/|u_b − ρ|³ + E₀ ê :
     * la source au noyau, un puits là où chaque liaison perce la bulle (force s_b = 0,55 × indice), et la déformation
     * du nuage (ê = −d/|d|, E₀ ∝ |d|, d le dipôle atomique calculé).
     */
    private void computeArcs(int n) {
        List<List<double[]>> previous = new ArrayList<>(arcs);
        List<double[]> previousInputs = new ArrayList<>(arcInputs);
        boolean sameAtoms = Arrays.equals(z, arcAtoms);
        arcs.clear();
        arcInputs.clear();
        arcAtoms = z.clone();
        for (int a = 0; a < n; a++) {
            List<double[]> sinks = new ArrayList<>();
            List<Double> strength = new ArrayList<>();
            for (Bond b : bonds) {
                if (!b.bonded() || (b.a != a && b.b != a) || b.a >= n || b.b >= n) continue;
                int other = b.a == a ? b.b : b.a;
                double[] u = {positions[other][0] - positions[a][0], positions[other][1] - positions[a][1],
                        positions[other][2] - positions[a][2]};
                double l = norm(u);
                if (l < 1e-6) continue;
                sinks.add(new double[]{u[0] / l, u[1] / l, u[2] / l});
                strength.add(.55 * Math.min(3, b.order));
            }
            double[] e = {0, 0, 0};
            double push = 0;
            if (a < polarization.length && polarization[a] != null) {
                double[] d = polarization[a];
                double m = norm(d);
                if (m > 1e-4) {
                    e = new double[]{-d[0] / m, -d[1] / m, -d[2] / m};
                    push = .55 * Math.min(1.5, m / .10);
                }
            }
            // Ce qui guide les arcs de l'atome ; s'ils ont bougé de moins de ARC_TOLERANCE (0,2 pixel sur une bulle de
            // 50), on garde les arcs déjà tracés.
            double[] inputs = new double[4 * sinks.size() + 3];
            for (int i = 0; i < sinks.size(); i++) {
                System.arraycopy(sinks.get(i), 0, inputs, 4 * i, 3);
                inputs[4 * i + 3] = strength.get(i);
            }
            for (int k = 0; k < 3; k++) inputs[4 * sinks.size() + k] = e[k] * push;
            arcInputs.add(inputs);
            if (sameAtoms && a < previous.size() && a < previousInputs.size() && close(previousInputs.get(a), inputs)) {
                arcs.add(previous.get(a));
                continue;
            }
            int count = sphere(a) > .25 ? 18 : 12;
            double[][] seeds = fibonacci(count);
            Random spin = new Random(31L * z[Math.min(a, z.length - 1)] + a);
            double turn = spin.nextDouble() * Math.PI;
            List<double[]> lines = new ArrayList<>();
            for (double[] s : seeds) {
                double[] p = {Math.cos(turn) * s[0] - Math.sin(turn) * s[1], Math.sin(turn) * s[0] + Math.cos(turn) * s[1], s[2]};
                for (int k = 0; k < 3; k++) p[k] *= .12;
                double[] line = new double[3 * 70];
                int m = 0;
                line[m++] = p[0];
                line[m++] = p[1];
                line[m++] = p[2];
                for (int step = 0; step < 69; step++) {
                    double[] f = arcField(p, sinks, strength, e, push);
                    double[] mid = {p[0] + f[0] * .015, p[1] + f[1] * .015, p[2] + f[2] * .015};
                    double[] g = arcField(mid, sinks, strength, e, push);
                    for (int k = 0; k < 3; k++) p[k] += g[k] * .03;
                    boolean captured = false;
                    for (double[] sink : sinks) {
                        if (Math.sqrt(sq(p[0] - sink[0]) + sq(p[1] - sink[1]) + sq(p[2] - sink[2])) < .07) {
                            p = new double[]{sink[0] * .985, sink[1] * .985, sink[2] * .985};
                            captured = true;
                        }
                    }
                    double r = norm(p);
                    if (r > .985) {
                        for (int k = 0; k < 3; k++) p[k] *= .985 / r;
                    }
                    line[m++] = p[0];
                    line[m++] = p[1];
                    line[m++] = p[2];
                    if (captured || r > .985) break;
                }
                lines.add(Arrays.copyOf(line, m));
            }
            arcs.add(lines);
        }
    }

    private static boolean close(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int k = 0; k < a.length; k++) if (Math.abs(a[k] - b[k]) > ARC_TOLERANCE) return false;
        return true;
    }

    /** Le champ unitaire qui guide un arc (voir computeArcs). */
    private static double[] arcField(double[] p, List<double[]> sinks, List<Double> strength, double[] e, double push) {
        double r = Math.max(.05, norm(p));
        double fx = p[0] / (r * r * r), fy = p[1] / (r * r * r), fz = p[2] / (r * r * r);
        for (int i = 0; i < sinks.size(); i++) {
            double[] u = sinks.get(i);
            double dx = u[0] - p[0], dy = u[1] - p[1], dz = u[2] - p[2];
            double d = Math.max(.05, Math.sqrt(dx * dx + dy * dy + dz * dz));
            double s = strength.get(i) / (d * d * d);
            fx += s * dx;
            fy += s * dy;
            fz += s * dz;
        }
        fx += push * e[0];
        fy += push * e[1];
        fz += push * e[2];
        double n = Math.sqrt(fx * fx + fy * fy + fz * fz) + 1e-12;
        return new double[]{fx / n, fy / n, fz / n};
    }

    /** La densité en unités de l'échelle logarithmique du rendu (0 : 10⁻⁴ e/Å³, 1 : 8 e/Å³), à partir d'u.a. */
    private static double level(double atomic) {
        return (Math.log10(atomic / (BOHR * BOHR * BOHR)) - LOG_LO) / (LOG_HI - LOG_LO);
    }

    /**
     * La vue montrée et, pour ψ, l'orbitale voulue (−1 : celle que le moteur montre). Une grille d'une autre vue n'est
     * plus dessinée, ni celle d'une autre orbitale qu'on vient de demander (pendant trois secondes au plus).
     */
    void mode(int view, int orbital) {
        if (view != mode) {
            boolean wasOrbital = mode == ORBITAL;
            mode = view;
            if (grid != null && grid.kind != view) dropGrid();
            // ρ et Δρ se lisent de face ; une orbitale, le long de son nuage (dès que sa grille arrive).
            if (autoView && wasOrbital && view != ORBITAL) orient();
            orientedOrbital = -1;
        }
        if (view == ORBITAL && orbital != wantedOrbital) {
            wantedOrbital = orbital;
            wantedSince = clock;
            if (orbital >= 0 && grid != null && grid.kind == ORBITAL && grid.orbital != orbital) dropGrid();
        }
    }

    int mode() {
        return mode;
    }

    private void dropGrid() {
        grid = field = tint = potentialGrid = null;
        fogKey = "";
        orbitalDots = new float[0];
        surface = null;
        requested = null;
        built = null;
        deformation.invalidate();
    }

    /** Les liaisons tenues (pour le squelette 3D de la vue ψ et le schéma). */
    List<Bond> bondedBonds() {
        List<Bond> out = new ArrayList<>();
        for (Bond b : bonds) if (b.bonded()) out.add(b);
        return out;
    }

    /**
     * Combien de traits dessinent une liaison, d'après son indice de Mayer : 1 sous 1,3 ; 2 jusqu'à 2,3 ; 3 au-delà. Le
     * second trait d'une liaison conjuguée (indice sous 1,75 : benzène 1,44) est plus fin — une double liaison partielle.
     */
    static int strokes(double order) {
        return order < 1.3 ? 1 : order < 2.3 ? 2 : 3;
    }

    static boolean partial(double order) {
        return order >= 1.3 && order < 1.75;
    }

    /** Aperçus et tests : attendre (au plus `millis`) les lobes de la dernière grille demandée. */
    void awaitSurface(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (glass && requested != null && System.currentTimeMillis() < end) {
            Built ready = built;
            if (ready != null && ready.grid() == requested) break;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        surface();
    }

    /** Les lobes à montrer à cette image : ceux de la vue ψ en verre (les plus récents prêts), null sinon. */
    OrbitalSurface.Mesh surface() {
        Built ready = built;
        if (ready != null && ready.grid() == grid) {
            surface = ready.mesh();
            built = null;
        }
        return glass && mode == ORBITAL && grid != null && grid.kind == ORBITAL ? surface : null;
    }

    void setGrid(DensityGrid next, DensityGrid bondsGrid, DensityGrid potential) {
        if (next == null || next.kind != mode) return;
        if (mode == ORBITAL && wantedOrbital >= 0 && next.orbital != wantedOrbital && clock - wantedSince < 3) return;
        grid = next;
        potentialGrid = potential;
        if (next.kind == DENSITY) {
            // La carte de potentiel : la densité sur l'échelle logarithmique du rendu, raffinée (grande à l'écran, une
            // maille de 0,15 Å y fait 40 pixels de près).
            float[] sum = next.values.clone();
            if (bondsGrid != null) for (int i = 0; i < sum.length; i++) sum[i] += bondsGrid.values[i];
            float[] mapped = new float[sum.length];
            for (int i = 0; i < mapped.length; i++) {
                float v = sum[i];
                mapped[i] = v <= 0 ? 0f : (float) Math.max(0, Math.min(1, (Math.log10(v) - LOG_LO) / (LOG_HI - LOG_LO)));
            }
            field = next.mapped(mapped);
            if (field.spacing > .1) field = field.refined();
            retint();
        } else if (next.kind == ORBITAL) {
            // ψ telle quelle (Å^−3/2) : ses lobes en verre, à 85 % de |ψ|² (voir OrbitalSurface) ; sans 3D, ses points
            // tirés de |ψ|² (toujours la même suite au hasard, voir OrbitalDots).
            field = next;
            if (glass) {
                requested = next;
                SURFACES.submit(() -> {
                    if (requested != next) return;                          // une grille plus récente attend déjà
                    OrbitalSurface.Mesh mesh = OrbitalSurface.build(next, OrbitalSurface.ENCLOSED);
                    if (requested == next) built = new Built(next, mesh);
                });
            } else {
                orbitalDots = OrbitalDots.sample(next, OrbitalDots.COUNT, 7);
            }
            // Une autre orbitale (pas chaque grille de la même pendant la dynamique) : la caméra se tourne vers elle.
            if (autoView && next.orbital != orientedOrbital) {
                double[] axis = cloudView(next);
                if (axis != null) lookAlong(axis);
            }
            orientedOrbital = next.orbital;
        } else {
            // Δρ telle quelle (e/Å³) : la carte l'intègre le long du regard.
            field = next;
            deformation.invalidate();
        }
    }


    /** La grille des couleurs de la carte de potentiel, raffinée comme la densité. */
    void retint() {
        DensityGrid source = potentialGrid;
        tint = source == null || field == null ? null
                : source.spacing > FINE_SPACING && field.spacing < source.spacing ? source.refined() : source;
        fogKey = "";
    }

    private double[] damping(int n) {
        double[] damping = new double[n];
        for (int a = 0; a < n; a++) {
            double eta = a < hardness.length && hardness[a] > 0 ? hardness[a] : .5;
            damping[a] = 1 / (eta * eta);
        }
        return damping;
    }

    /**
     * Les lignes du champ électrique E = −∇φ des charges du modèle (option). Elles partent des atomes δ+ (et, à
     * rebours, des atomes δ− pour celles qui viennent de l'infini), en nombre proportionnel à |q| (Gauss).
     */
    void computeField() {
        int n = Math.min(positions.length, charges.length);
        List<double[][]> lines = new ArrayList<>();
        if (n == 0 || !showField) {
            fieldLines = lines;
            return;
        }
        double[] damping = damping(n);
        double[] c = centroid();
        double extent = 0;
        for (double[] p : positions) extent = Math.max(extent, Math.sqrt(sq(p[0] - c[0]) + sq(p[1] - c[1]) + sq(p[2] - c[2])));
        double limit = extent + 2.0;
        for (int a = 0; a < n; a++) {
            double q = charges[a];
            int count = (int) Math.round(Math.abs(q) * GAUSS_LINES);
            if (count < 2) continue;
            double sign = Math.signum(q);
            double start = sphere(a) * 1.08;
            for (double[] d : fibonacci(count)) {
                double[] point = {positions[a][0] + d[0] * start, positions[a][1] + d[1] * start, positions[a][2] + d[2] * start};
                List<double[]> line = new ArrayList<>();
                line.add(point.clone());
                int end = -1;
                for (int step = 0; step < 180; step++) {
                    double[] e = efield(point, n, damping);
                    double en = norm(e);
                    if (en < 1e-6) break;
                    double h = .05;
                    double[] mid = {point[0] + sign * e[0] / en * h * .5, point[1] + sign * e[1] / en * h * .5,
                            point[2] + sign * e[2] / en * h * .5};
                    double[] e2 = efield(mid, n, damping);
                    double n2 = norm(e2);
                    if (n2 < 1e-6) break;
                    for (int k = 0; k < 3; k++) point[k] += sign * e2[k] / n2 * h;
                    line.add(point.clone());
                    end = inside(point, n, a);
                    if (end >= 0) break;
                    if (Math.sqrt(sq(point[0] - c[0]) + sq(point[1] - c[1]) + sq(point[2] - c[2])) > limit) break;
                }
                if (sign < 0 && end >= 0 && charges[end] > 0 && Math.round(charges[end] * GAUSS_LINES) >= 2) continue;
                if (line.size() < 3) continue;
                double[][] points = line.toArray(new double[0][]);
                if (sign < 0) java.util.Collections.reverse(Arrays.asList(points));
                lines.add(points);
            }
        }
        fieldLines = lines;
    }

    private double[] efield(double[] r, int n, double[] damping) {
        double ex = 0, ey = 0, ez = 0;
        for (int a = 0; a < n; a++) {
            double dx = (r[0] - positions[a][0]) / BOHR, dy = (r[1] - positions[a][1]) / BOHR, dz = (r[2] - positions[a][2]) / BOHR;
            double d2 = dx * dx + dy * dy + dz * dz + damping[a];
            double f = charges[a] / (d2 * Math.sqrt(d2));
            ex += f * dx;
            ey += f * dy;
            ez += f * dz;
        }
        return new double[]{ex, ey, ez};
    }

    private int inside(double[] r, int n, int origin) {
        for (int b = 0; b < n; b++) {
            if (b == origin) continue;
            double d2 = sq(r[0] - positions[b][0]) + sq(r[1] - positions[b][1]) + sq(r[2] - positions[b][2]);
            if (d2 < sq(sphere(b))) return b;
        }
        return -1;
    }

    DensityGrid grid() {
        return grid;
    }

    /** Recadre la vue sur la molécule (après un changement de molécule). */
    void frame() {
        centered = false;
        double extent = 1.2;
        double[] c = centroid();
        for (double[] p : positions) {
            extent = Math.max(extent, Math.hypot(p[0] - c[0], Math.hypot(p[1] - c[1], p[2] - c[2])));
        }
        if (wall > 0) extent = Math.max(extent, wall);
        // Le cadre englobe le halo (il dépasse les noyaux de 0,5 à 1,3 Å) et les signes δ posés au-delà.
        camera.flyTo(Math.log10(extent * 2 + (showHalo ? 3.4 : 1.1)));
        orient();
        autoView = true;
    }

    /**
     * Le point de vue : de face, le long de l'axe où les noyaux s'étalent le moins (la normale d'une molécule plane),
     * un peu de biais pour garder le relief ; la vue ψ, le long de son nuage (voir cloudView), dès qu'il arrive.
     */
    private void orient() {
        double[] axis = mode == ORBITAL && grid != null && grid.kind == ORBITAL ? cloudView(grid) : thinnestAxis(centroid());
        if (axis != null && (wall <= 0 || mode == ORBITAL)) lookAlong(axis);
    }

    /** La caméra regarde le long de cet axe (unitaire), d'en haut, un peu de biais. */
    private void lookAlong(double[] axis) {
        double[] f = axis[1] > 0 ? new double[]{-axis[0], -axis[1], -axis[2]} : axis;
        double pitch = Math.min(1.25, Math.asin(Math.max(-1, Math.min(1, -f[1]))));
        camera.orient(Math.atan2(-f[0], -f[2]) + .12, Math.max(.2, pitch));
    }

    /**
     * L'axe le long duquel le nuage |ψ|² d'une orbitale est le plus mince : vue le long de lui, l'orbitale se lit
     * comme dans les manuels — la π de l'éthylène de profil, ses lobes au-dessus et au-dessous du plan ; la paire libre
     * de l'eau la molécule par la tranche ; une π du benzène de face, avec son plan nodal.
     */
    static double[] thinnestCloudAxis(DensityGrid psi) {
        double[][] moments = cloudMoments(psi);
        if (moments == null) return null;
        double[][] m = {moments[1], moments[2], moments[3]};
        double trace = m[0][0] + m[1][1] + m[2][2];
        if (trace <= 0) return null;
        return eigen(m, trace, new double[]{.2, 1, .3});
    }

    /** Le centre du nuage |ψ|² (ligne 0) puis sa covariance (lignes 1 à 3) ; null pour une grille vide. */
    private static double[][] cloudMoments(DensityGrid psi) {
        double total = 0;
        double[] c = new double[3];
        double[][] m = new double[3][3];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < psi.nx; i++) for (int j = 0; j < psi.ny; j++) for (int k = 0; k < psi.nz; k++) {
                double v = psi.values[(i * psi.ny + j) * psi.nz + k], w = v * v;
                if (w == 0) continue;
                double[] p = {psi.x(i), psi.y(j), psi.z(k)};
                if (pass == 0) {
                    total += w;
                    for (int a = 0; a < 3; a++) c[a] += w * p[a];
                } else {
                    for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) m[a][b] += w * (p[a] - c[a]) * (p[b] - c[b]);
                }
            }
            if (pass == 0) {
                if (total <= 0) return null;
                for (int a = 0; a < 3; a++) c[a] /= total;
            }
        }
        return new double[][]{c, m[0], m[1], m[2]};
    }

    /** Par itération de puissance : l'axe propre de plus grande valeur de (shift·I − m) (shift = 0 : celle de m). */
    private static double[] eigen(double[][] m, double shift, double[] seed) {
        double[] v = seed.clone();
        for (int it = 0; it < 80; it++) {
            double[] w = new double[3];
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) w[a] += ((a == b ? shift : 0) - m[a][b]) * v[b];
            if (shift == 0) for (int a = 0; a < 3; a++) w[a] = -w[a];
            double n = norm(w);
            if (n < 1e-12) return null;
            v = new double[]{w[0] / n, w[1] / n, w[2] / n};
        }
        return v;
    }

    /**
     * L'axe de vue d'une orbitale : le long de son nuage le plus mince (thinnestCloudAxis) — sauf quand ses deux phases
     * sont de part et d'autre du plan vu de face (une π du benzène : + au-dessus, − au-dessous), où elles se
     * superposeraient à l'écran. La vue s'incline alors de 40° autour du grand axe du nuage : les lobes du dessus
     * montent, ceux du dessous descendent, le plan nodal se voit par la tranche sans perdre la forme dans le plan.
     */
    static double[] cloudView(DensityGrid psi) {
        double[][] moments = cloudMoments(psi);
        double[] thin = thinnestCloudAxis(psi);
        if (moments == null || thin == null) return thin;
        double[][] m = {moments[1], moments[2], moments[3]};
        if (phaseOverlap(psi, thin) < PHASE_SPLIT) return thin;
        double[] longest = eigen(m, 0, new double[]{1, .3, .2});
        if (longest == null) return thin;
        double[] middle = cross(thin, longest);
        double n = norm(middle);
        if (n < 1e-9) return thin;
        double tilt = Math.toRadians(40);
        double[] view = new double[3];
        for (int a = 0; a < 3; a++) view[a] = Math.cos(tilt) * thin[a] + Math.sin(tilt) * middle[a] / n;
        return view;
    }

    /** Au-delà de cette part de ψ² qui se recouvre, vue le long de l'axe, les deux phases se mêlent (voir cloudView). */
    static final double PHASE_SPLIT = .5;

    /**
     * Vue le long de `axis`, la part de |ψ|² où les deux phases se recouvrent : chaque ligne de visée (des colonnes de
     * 0,3 Å) somme ψ² avec son signe ; ce qui s'annule dans une ligne est vu mêlé. 0 : chaque ligne n'a qu'une phase
     * (une orbitale σ dans le plan, vue de face) ; 1 : chaque ligne a autant de bleu que d'orange (une π vue de face).
     */
    static double phaseOverlap(DensityGrid psi, double[] axis) {
        double[] u = Math.abs(axis[0]) < .9 ? cross(axis, new double[]{1, 0, 0}) : cross(axis, new double[]{0, 1, 0});
        double un = norm(u);
        u = new double[]{u[0] / un, u[1] / un, u[2] / un};
        double[] v = cross(axis, u);
        Map<Long, double[]> columns = new HashMap<>();
        double total = 0, bin = .3;
        for (int i = 0; i < psi.nx; i++) for (int j = 0; j < psi.ny; j++) for (int k = 0; k < psi.nz; k++) {
            double value = psi.values[(i * psi.ny + j) * psi.nz + k], w = value * value;
            if (w == 0) continue;
            double x = psi.x(i), y = psi.y(j), z = psi.z(k);
            long a = Math.round((x * u[0] + y * u[1] + z * u[2]) / bin), b = Math.round((x * v[0] + y * v[1] + z * v[2]) / bin);
            double[] column = columns.computeIfAbsent(a * 100_003L + b, key -> new double[1]);
            column[0] += Math.signum(value) * w;
            total += w;
        }
        if (total <= 0) return 0;
        double kept = 0;
        for (double[] column : columns.values()) kept += Math.abs(column[0]);
        return 1 - kept / total;
    }

    /**
     * L'axe le long duquel les noyaux s'étalent le moins : itération de puissance sur (λ_max·I − C), C la covariance
     * des positions. Pour une molécule plane, la normale à son plan.
     */
    private double[] thinnestAxis(double[] c) {
        if (positions.length < 2) return null;
        double[][] m = new double[3][3];
        for (double[] p : positions) {
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) m[i][j] += (p[i] - c[i]) * (p[j] - c[j]);
        }
        double trace = m[0][0] + m[1][1] + m[2][2];
        if (trace < 1e-9) return null;
        double[][] shifted = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) shifted[i][j] = (i == j ? trace : 0) - m[i][j];
        double[] v = {.2, 1, .3};
        for (int it = 0; it < 60; it++) {
            double[] w = new double[3];
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) w[i] += shifted[i][j] * v[j];
            double n = norm(w);
            if (n < 1e-12) return null;
            v = new double[]{w[0] / n, w[1] / n, w[2] / n};
        }
        return v;
    }

    private double[] centroid() {
        double[] c = new double[3];
        if (positions.length == 0) return c;
        for (double[] p : positions) for (int k = 0; k < 3; k++) c[k] += p[k] / positions.length;
        return c;
    }

    // ================================================================== le rendu
    /**
     * Une image : g est le dessin net ; near et far sont les calques du bloom (la vue les floute, 5 et 22 px, et les
     * ajoute par-dessus) : on n'y dessine que ce qui émet de la lumière.
     */
    void render(GraphicsContext g, GraphicsContext near, GraphicsContext far, double w, double h, double dt,
                boolean dragging) {
        clock += dt;
        double[] c = centroid();
        double follow = centered ? Math.min(1, dt * 2.5) : 1;
        for (int k = 0; k < 3; k++) center[k] += (c[k] - center[k]) * follow;
        centered = true;
        camera.targetX = center[0];
        camera.targetY = center[1];
        camera.targetZ = center[2];
        camera.tick(dt, dragging, Math.log10(2.0), Math.log10(60));
        camera.setup(w, h, w / 2, h / 2, camera.distance());
        for (GraphicsContext layer : new GraphicsContext[]{g, near, far}) {
            layer.setGlobalBlendMode(BlendMode.SRC_OVER);
            layer.setGlobalAlpha(1);
            layer.setLineDashes();
        }
        // Le nuage d'une orbitale se lit sur un fond presque noir (comme les figures de référence) ; le reste, sur le bleu nuit.
        g.setFill(mode == ORBITAL
                ? new RadialGradient(0, 0, w * .5, h * .42, Math.max(w, h) * .75, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0B1626")), new Stop(.5, Color.web("#050A14")), new Stop(1, Color.web("#010307")))
                : new RadialGradient(0, 0, w * .5, h * .42, Math.max(w, h) * .75, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#183552")), new Stop(.45, Color.web("#0E2135")), new Stop(1, Color.web("#050B14"))));
        // En verre, les lobes et le fond sont dans la couche 3D, dessous : le canevas reste transparent.
        boolean lobes = surface() != null;
        if (lobes) g.clearRect(0, 0, w, h);
        else g.fillRect(0, 0, w, h);
        near.clearRect(0, 0, w, h);
        far.clearRect(0, 0, w, h);
        // Une grille calculée pour d'autres atomes (un atome ajouté ou retiré) ne se dessine plus.
        if (grid != null && grid.positions.length > 0 && grid.positions.length != 3 * positions.length) dropGrid();
        // Chaque vue ne montre que ce qu'elle dit : la densité ses bulles, son halo, ses liaisons et ses forces ; Δρ sa
        // carte et les noyaux ; ψ ses lobes et le squelette.
        boolean density = mode == DENSITY;
        if (density) {
            if (showPotential) drawFog(g, w, h);
            else if (showHalo) halo.render(g, far, camera, w, h, pixelScale, clock, atomsVersion, positions, z, charges, fragment);
        } else if (mode == DEFORMATION && grid != null) {
            deformation.render(g, near, grid, camera, w, h);
        } else if (mode == ORBITAL && grid != null && !lobes) {
            drawOrbital(g, near, far, w, h);
        }
        drawWall(g);
        double[][] screen = project();
        if (density) {
            if (showField) drawField(g);
            if (showContacts) drawContacts(g, near, screen);
            if (showBonds) {
                drawBonds(g, near, far, screen);
                drawMulticentres(g, near, far);
            }
            drawAtoms(g, near, far, screen);
            drawBursts(g, near);
            if (showArrows) drawArrows(g);
        } else {
            drawNuclei(g, near, far, screen);
        }
        drawDrag(g);
        if (showLegend) drawLegend(g, w);
        else legendTitle[2] = 0;
        String notice = density ? "" : grid == null ? "Calcul de la grille…"
                : mode == DEFORMATION && grid.peak < DEFORMATION_FLOOR
                ? "Δρ sous 0,01 e/Å³ partout : rien n'a bougé (un atome seul est l'atome libre de référence)" : "";
        if (!notice.isEmpty()) {
            g.setFont(Font.font("Segoe UI", 12));
            g.setTextAlign(TextAlignment.CENTER);
            g.setFill(Color.web("#B9CBE0", .8));
            g.fillText(I18n.text(notice), w / 2, h * .18);
        }
    }

    /**
     * Les atomes des vues Δρ et ψ : leur noyau, un point blanc, leur symbole, l'anneau du survol et du choix — ni bulle,
     * ni charge, ni arcs ; dans la vue ψ, les liaisons en traits fins (la carte Δρ les montre seule).
     */
    private void drawNuclei(GraphicsContext g, GraphicsContext near, GraphicsContext far, double[][] screen) {
        // Les lobes pleins ont leur squelette en 3D (boules et bâtonnets, cachés derrière un lobe comme il se doit) :
        // ici ne restent que les étiquettes et les anneaux du survol et du choix.
        boolean solid = surface() != null;
        if (mode == ORBITAL && !solid) {
            // Les liaisons, en traits fins : savoir où sont les atomes au travers du nuage.
            g.setStroke(Color.web("#9FB3D1", .45));
            g.setLineWidth(1);
            for (Bond b : bonds) {
                if (!b.bonded() || b.a() >= screen.length || b.b() >= screen.length) continue;
                double[] a = screen[b.a()], c = screen[b.b()];
                if (a != null && c != null) g.strokeLine(a[0], a[1], c[0], c[1]);
            }
        }
        g.setTextAlign(TextAlignment.CENTER);
        for (int i = 0; i < positions.length; i++) {
            double[] p = screen[i];
            if (p == null) continue;
            if (!solid) whiteNucleus(g, near, p[0], p[1]);
            double ring = 7;
            if (i == hovered || i == selected || i == dragged) {
                g.setStroke(i == selected ? Color.web("#FFE07A", .8) : Color.web("#FFFFFF", .4));
                g.setLineWidth(.8);
                g.strokeOval(p[0] - ring, p[1] - ring, 2 * ring, 2 * ring);
            }
            if (showSymbols && i < symbols.length) {
                g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5));
                g.setFill(Color.web("#DCEBFF", .65));
                g.fillText(MoleculeView.nuclide(symbols[i], i < massNumbers.length ? massNumbers[i] : 0), p[0] + ring + 5, p[1] - ring);
            }
        }
    }

    /** Un petit lobe plein pour la légende, éclairé d'en haut à gauche. */
    private static void glassIcon(GraphicsContext g, double x, double y, Color colour) {
        g.setFill(new RadialGradient(0, 0, x - 1.4, y - 2, 6, false, CycleMethod.NO_CYCLE,
                new Stop(0, colour.interpolate(Color.WHITE, .45)), new Stop(.5, colour), new Stop(1, colour.deriveColor(0, 1, .45, 1))));
        g.fillOval(x - 3.6, y - 5, 7.2, 10);
    }

    /** Un noyau des vues Δρ et ψ : un point blanc (l'orange y est celui des électrons perdus, ou du cœur du nuage). */
    private static void whiteNucleus(GraphicsContext g, GraphicsContext near, double x, double y) {
        g.setFill(new RadialGradient(0, 0, x, y, 3.4, false, CycleMethod.NO_CYCLE, new Stop(0, Color.WHITE),
                new Stop(.45, Color.web("#DDEBFF", .9)), new Stop(1, Color.web("#9CC4FF", 0))));
        g.fillOval(x - 3.4, y - 3.4, 6.8, 6.8);
        near.setFill(Color.web("#CFE3FF", .45));
        near.fillOval(x - 2.6, y - 2.6, 5.2, 5.2);
    }

    /**
     * La vue ψ : les points de l'orbitale, posés à chaque image au pixel de l'écran — un petit disque doux par point, de
     * la couleur de sa phase, un peu plus pâle au fond qu'au premier plan (le relief en tournant) ; les points serrés
     * s'additionnent en lumière, adoucie pour ne jamais saturer. Une lueur légère dans le calque du bloom.
     */
    private void drawOrbital(GraphicsContext g, GraphicsContext near, GraphicsContext far, double w, double h) {
        float[] dots = orbitalDots;
        int n = dots.length / 4;
        if (n == 0 || w < 4 || h < 4) return;
        double s = Math.max(1, pixelScale);
        int bw = (int) Math.ceil(w * s), bh = (int) Math.ceil(h * s);
        if (dotImage == null || (int) dotImage.getWidth() != bw || (int) dotImage.getHeight() != bh) {
            dotImage = new WritableImage(bw, bh);
            dotPixels = new int[bw * bh];
            dotLight = new float[3 * bw * bh];
            dotBox = new int[]{0, 0, bw, bh};
        }
        // Effacer ce que l'image précédente avait posé.
        for (int y = dotBox[1]; y < dotBox[3]; y++) {
            java.util.Arrays.fill(dotPixels, y * bw + dotBox[0], y * bw + dotBox[2], 0);
            java.util.Arrays.fill(dotLight, 3 * (y * bw + dotBox[0]), 3 * (y * bw + dotBox[2]), 0f);
        }
        int[] previous = dotBox.clone();
        double[][] screen = new double[n][];
        double near0 = Double.MAX_VALUE, far0 = -Double.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            screen[k] = camera.project(dots[4 * k], dots[4 * k + 1], dots[4 * k + 2]);
            if (screen[k] == null) continue;
            near0 = Math.min(near0, screen[k][2]);
            far0 = Math.max(far0, screen[k][2]);
        }
        double depthSpan = Math.max(1e-6, far0 - near0), radius = 1.35 * s;
        int x0 = bw, y0 = bh, x1 = 0, y1 = 0;
        for (int k = 0; k < n; k++) {
            double[] p = screen[k];
            if (p == null) continue;
            double px = p[0] * s, py = p[1] * s;
            double depth = (p[2] - near0) / depthSpan;                     // 0 devant, 1 au fond
            double bright = .58 * (1.15 - .55 * depth);
            float[] c = dots[4 * k + 3] > 0 ? OrbitalDots.PLUS : OrbitalDots.MINUS;
            int ix0 = (int) Math.floor(px - radius - 1), iy0 = (int) Math.floor(py - radius - 1);
            int ix1 = (int) Math.ceil(px + radius + 1), iy1 = (int) Math.ceil(py + radius + 1);
            if (ix1 < 0 || iy1 < 0 || ix0 >= bw || iy0 >= bh) continue;
            ix0 = Math.max(0, ix0);
            iy0 = Math.max(0, iy0);
            ix1 = Math.min(bw - 1, ix1);
            iy1 = Math.min(bh - 1, iy1);
            for (int y = iy0; y <= iy1; y++) {
                for (int x = ix0; x <= ix1; x++) {
                    double d = Math.hypot(x + .5 - px, y + .5 - py) / radius;
                    if (d >= 1.4) continue;
                    // Un disque net au bord adouci (une gaussienne tronquée) : un point, pas une tache.
                    double weight = bright * Math.exp(-2.2 * d * d);
                    int index = 3 * (y * bw + x);
                    dotLight[index] += (float) (weight * c[0]);
                    dotLight[index + 1] += (float) (weight * c[1]);
                    dotLight[index + 2] += (float) (weight * c[2]);
                }
            }
            x0 = Math.min(x0, ix0);
            y0 = Math.min(y0, iy0);
            x1 = Math.max(x1, ix1 + 1);
            y1 = Math.max(y1, iy1 + 1);
        }
        if (x1 <= x0) x0 = x1 = y0 = y1 = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int index = y * bw + x;
                float r = dotLight[3 * index], gg = dotLight[3 * index + 1], b = dotLight[3 * index + 2];
                if (r + gg + b <= 0) continue;
                // La lumière des points serrés s'adoucit (1 − e^−x) : les cœurs blanchissent sans brûler.
                int ri = (int) (255 * (1 - Math.exp(-1.4 * r))), gi = (int) (255 * (1 - Math.exp(-1.4 * gg))), bi = (int) (255 * (1 - Math.exp(-1.4 * b)));
                dotPixels[index] = 0xFF000000 | ri << 16 | gi << 8 | bi;
            }
        }
        dotBox = new int[]{x0, y0, x1, y1};
        int ux0 = Math.min(previous[0], x0), uy0 = Math.min(previous[1], y0), ux1 = Math.max(previous[2], x1), uy1 = Math.max(previous[3], y1);
        if (ux1 > ux0 && uy1 > uy0) {
            dotImage.getPixelWriter().setPixels(ux0, uy0, ux1 - ux0, uy1 - uy0, PixelFormat.getIntArgbInstance(), dotPixels,
                    uy0 * bw + ux0, bw);
        }
        g.setImageSmoothing(true);
        g.setGlobalBlendMode(BlendMode.ADD);
        g.drawImage(dotImage, 0, 0, w, h);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        near.setImageSmoothing(true);
        near.setGlobalAlpha(.45);
        near.drawImage(dotImage, 0, 0, w, h);
        near.setGlobalAlpha(1);
    }

    private double[][] project() {
        double[][] screen = new double[positions.length][];
        for (int i = 0; i < positions.length; i++) screen[i] = camera.project(positions[i][0], positions[i][1], positions[i][2]);
        return screen;
    }

    /** La carte de potentiel (vue ρ, en option). */
    private void drawFog(GraphicsContext g, double w, double h) {
        if (field == null || w < 4 || h < 4) return;
        // Pendant que la vue bouge, une image réduite ; dès qu'elle s'arrête, plus fine.
        String k = key(w, h);
        int moving = 3, still = 1;
        if (!k.equals(fogKey) || fog == null) {
            march(w, h, moving);
            fogKey = k;
            sharp = false;
        } else if (!sharp) {
            march(w, h, still);
            sharp = true;
        }
        g.setImageSmoothing(true);
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setGlobalAlpha(.88 + .12 * Math.sin(clock * .8));
        g.drawImage(fog, 0, 0, fogW, fogH, 0, 0, w, h);
        g.setGlobalAlpha(1);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    private String key(double w, double h) {
        return String.format(Locale.ROOT, "%d|%d|%d|%.0f|%.0f|%.4f|%.4f|%.4f|%.3f|%.3f|%.3f", grid.version,
                System.identityHashCode(field), System.identityHashCode(tint), w, h, camera.yaw, camera.pitch,
                camera.zoom, center[0], center[1], center[2]);
    }

    /** Le lancer de rayons : un rayon par pixel, en parallèle sur les lignes, seulement près des atomes. */
    private void march(double w, double h, int divisor) {
        int bw = Math.max(120, (int) (w / divisor)), bh = Math.max(80, (int) (h * bw / w));
        if (fog == null || fogW != bw || fogH != bh) {
            fog = new WritableImage(bw, bh);
            fogPixels = new int[bw * bh];
            fogW = bw;
            fogH = bh;
        }
        Tracer tracer = new Tracer(field, tint, camera, divisor);
        double sx = w / bw, sy = h / bh;
        // La direction d'un rayon est linéaire en (x, y) écran : avant + x·droite − y·haut, à l'échelle 1/focale.
        double[] f = camera.forward(), right = camera.right(), up = camera.up(), probe = camera.ray(w / 2 + 100, h / 2);
        double invFocal = (probe[3] * right[0] + probe[4] * right[1] + probe[5] * right[2])
                / (probe[3] * f[0] + probe[4] * f[1] + probe[5] * f[2]) / 100;
        int[] out = fogPixels;
        IntStream.range(0, bh).parallel().forEach(j -> {
            double[] ray = new double[6], enter = new double[tracer.atoms()], leave = new double[tracer.atoms()];
            double b = -((j + .5) * sy - h / 2) * invFocal;
            for (int i = 0; i < bw; i++) {
                double a = ((i + .5) * sx - w / 2) * invFocal;
                double dx = f[0] + a * right[0] + b * up[0], dy = f[1] + a * right[1] + b * up[1], dz = f[2] + a * right[2] + b * up[2];
                double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
                ray[3] = dx / n;
                ray[4] = dy / n;
                ray[5] = dz / n;
                out[j * bw + i] = tracer.trace(ray, enter, leave);
            }
        });
        fog.getPixelWriter().setPixels(0, 0, bw, bh, PixelFormat.getIntArgbInstance(), out, 0, bw);
    }

    /**
     * Un rayon de la carte de potentiel (vue ρ) : un voile translucide — sa lumière s'accumule avec l'épaisseur
     * traversée, sa couleur suit le potentiel point par point — bordé d'un liseré de verre là où le rayon franchit la
     * surface ρ = 0,002 u.a.
     */
    static final class Tracer {
        private static final double HALO = .03, SLOPE = .6, REACH = 2.5, FILL = .05;
        private final DensityGrid field, tint;
        private final double step, iso;
        private final double[] light, eye, spheres;

        Tracer(DensityGrid field, DensityGrid tint, LabCamera camera, int divisor) {
            this.field = field;
            this.tint = tint;
            step = field.spacing * (divisor >= 2 ? .75 : .4);
            iso = MAP_LEVEL;
            eye = camera.eye();
            double[] r = camera.right(), u = camera.up(), f = camera.forward();
            double[] l = {-.45 * r[0] + .65 * u[0] - .6 * f[0], -.45 * r[1] + .65 * u[1] - .6 * f[1],
                    -.45 * r[2] + .65 * u[2] - .6 * f[2]};
            double n = Math.sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2]);
            light = new double[]{l[0] / n, l[1] / n, l[2] / n};
            spheres = field.positions;
        }

        int atoms() {
            return spheres.length / 3;
        }

        int trace(double[] ray, double[] enter, double[] leave) {
            double dx = ray[3], dy = ray[4], dz = ray[5];
            int n = spheres.length / 3;
            int count = 0;
            for (int a = 0; a < n; a++) {
                double ox = spheres[3 * a] - eye[0], oy = spheres[3 * a + 1] - eye[1], oz = spheres[3 * a + 2] - eye[2];
                double along = ox * dx + oy * dy + oz * dz;
                double d2 = ox * ox + oy * oy + oz * oz - along * along;
                if (d2 > REACH * REACH) continue;
                double half = Math.sqrt(REACH * REACH - d2);
                enter[count] = Math.max(0, along - half);
                leave[count] = along + half;
                count++;
            }
            if (count == 0) return 0xFF000000;
            sortIntervals(enter, leave, count);
            double r = 0, g = 0, b = 0, transmit = 1;
            double start = enter[0], end = leave[0];
            double closest = 0, cx = 0, cy = 0, cz = 0;
            boolean crossed = false;
            for (int s = 1; s <= count && transmit > .03; s++) {
                if (s < count && enter[s] <= end) {
                    end = Math.max(end, leave[s]);
                    continue;
                }
                double previous = Double.NaN, previousT = start;
                double t = start;
                while (t < end && transmit > .03) {
                    double x = eye[0] + dx * t, y = eye[1] + dy * t, z = eye[2] + dz * t;
                    double level = field.sample(x, y, z);
                    if (level > closest && level < iso) {
                        closest = level;
                        cx = x;
                        cy = y;
                        cz = z;
                    }
                    if (!Double.isNaN(previous) && (previous < iso) != (level < iso)) {
                        crossed = true;
                        double frac = (iso - previous) / (level - previous);
                        double tc = previousT + frac * (t - previousT);
                        double[] acc = {r, g, b, transmit};
                        shade(acc, tc, level >= iso, dx, dy, dz);
                        r = acc[0];
                        g = acc[1];
                        b = acc[2];
                        transmit = acc[3];
                    }
                    // Loin de la surface, de grands pas (hors des cœurs, la densité varie de moins de 0,6 unité de
                    // l'échelle log par Å) ; tout près, le pas fin.
                    double advance = Math.max(step, Math.min(.35, .7 * Math.abs(level - iso) / SLOPE));
                    if (!Double.isNaN(previous)) {
                        // Sous la surface : une lumière proportionnelle à l'épaisseur traversée, intégrée exactement
                        // entre les points d'entrée et de sortie (interpolés) : pas de marches au bord.
                        double before = previous - iso, after = level - iso, inside;
                        if (before >= 0 && after >= 0) inside = t - previousT;
                        else if (before < 0 && after < 0) inside = 0;
                        else inside = (before >= 0 ? before / (before - after) : after / (after - before)) * (t - previousT);
                        if (inside > 0) {
                            float[] col = colour(x, y, z);
                            double e = FILL * inside;
                            r += transmit * e * col[0];
                            g += transmit * e * col[1];
                            b += transmit * e * col[2];
                            transmit *= Math.exp(-.45 * inside);
                        }
                    }
                    previous = level;
                    previousT = t;
                    t += advance;
                }
                if (s < count) {
                    start = enter[s];
                    end = leave[s];
                }
            }
            if (!crossed && closest > iso - 3 * HALO) {
                double fade = Math.exp(-sq((iso - closest) / HALO));
                float[] col = colour(cx, cy, cz);
                double halo = .35 * fade;
                r += transmit * halo * col[0];
                g += transmit * halo * col[1];
                b += transmit * halo * col[2];
            }
            double exposure = 1.25;
            int ri = (int) (255 * (1 - Math.exp(-r * exposure)));
            int gi = (int) (255 * (1 - Math.exp(-g * exposure)));
            int bi = (int) (255 * (1 - Math.exp(-b * exposure)));
            return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
        }

        /** Une traversée de la surface en t, accumulée dans acc = {r, g, b, T} : presque rien de face, un bord doux
         *  à la tangente (Fresnel). */
        private void shade(double[] acc, double tc, boolean entering, double dx, double dy, double dz) {
            double px = eye[0] + dx * tc, py = eye[1] + dy * tc, pz = eye[2] + dz * tc;
            float[] col = colour(px, py, pz);
            double[] grad = field.gradient(px, py, pz);
            double gn = Math.sqrt(grad[0] * grad[0] + grad[1] * grad[1] + grad[2] * grad[2]);
            double nx = -dx, ny = -dy, nz = -dz;
            if (gn > 1e-12) {
                nx = -grad[0] / gn;
                ny = -grad[1] / gn;
                nz = -grad[2] / gn;
            }
            double facing = -(nx * dx + ny * dy + nz * dz);
            double rim = Math.pow(1 - Math.min(1, Math.abs(facing)), 3.0);
            double lambert = Math.max(0, nx * light[0] + ny * light[1] + nz * light[2]);
            double hx = light[0] - dx, hy = light[1] - dy, hz = light[2] - dz;
            double hn = Math.sqrt(hx * hx + hy * hy + hz * hz);
            double spec = Math.pow(Math.max(0, (nx * hx + ny * hy + nz * hz) / hn), 60);
            double alpha = (entering ? 1 : .4) * (.03 + .32 * rim), body = .35 + .25 * lambert + .9 * rim;
            acc[0] += acc[3] * alpha * (col[0] * body + .4 * spec);
            acc[1] += acc[3] * alpha * (col[1] * body + .4 * spec);
            acc[2] += acc[3] * alpha * (col[2] * body + .4 * spec);
            acc[3] *= 1 - alpha * .7;
        }

        /** La couleur de la carte en un point : le potentiel, sur l'échelle des charges (±2,5 V saturent). */
        private float[] colour(double x, double y, double z) {
            if (tint == null) return chargeColour(0);
            return chargeColour(tint.sample(x, y, z) / POTENTIAL_SCALE * CHARGE_SCALE);
        }

        private static void sortIntervals(double[] enter, double[] leave, int count) {
            for (int i = 1; i < count; i++) {
                double e = enter[i], l = leave[i];
                int j = i - 1;
                while (j >= 0 && enter[j] > e) {
                    enter[j + 1] = enter[j];
                    leave[j + 1] = leave[j];
                    j--;
                }
                enter[j + 1] = e;
                leave[j + 1] = l;
            }
        }
    }

    /**
     * La couleur d'une charge partielle (e) : magenta δ− (électrons en trop) → violet → bleu (neutre) → cyan δ+
     * (électrons en moins) ; saturée à ±0,4 e. La carte de potentiel emploie la même échelle (±2,5 V saturent), avec le
     * même sens : magenta où le potentiel est négatif, cyan où il est positif.
     */
    static float[] chargeColour(double q) {
        double t = Math.max(-1, Math.min(1, q / CHARGE_SCALE));
        float[][] ramp = {{1f, .38f, .86f}, {.76f, .43f, 1f}, {.46f, .56f, 1f}, {.32f, .76f, 1f}, {.38f, .95f, 1f}};
        double x = (t + 1) * 2;
        int i = Math.min(3, (int) x);
        double f = x - i;
        float[] a = ramp[i], b = ramp[i + 1];
        return new float[]{(float) (a[0] + (b[0] - a[0]) * f), (float) (a[1] + (b[1] - a[1]) * f), (float) (a[2] + (b[2] - a[2]) * f)};
    }

    private void drawWall(GraphicsContext g) {
        if (wall <= 0) return;
        double[] p = camera.project(0, 0, 0);
        if (p == null) return;
        double radius = wall * p[3];
        g.setStroke(Color.web("#7FA6FF", .12));
        g.setLineWidth(1);
        g.setLineDashes(2, 9);
        g.strokeOval(p[0] - radius, p[1] - radius, 2 * radius, 2 * radius);
        g.setLineDashes();
    }

    private void drawField(GraphicsContext g) {
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        for (double[][] line : fieldLines) {
            double[] xs = new double[line.length], ys = new double[line.length];
            int m = 0;
            for (double[] p : line) {
                double[] q = camera.project(p[0], p[1], p[2]);
                if (q == null) continue;
                xs[m] = q[0];
                ys[m] = q[1];
                m++;
            }
            if (m < 2) continue;
            g.setStroke(FIELD.deriveColor(0, 1, 1, .16));
            g.setLineWidth(.6);
            g.strokePolyline(xs, ys, m);
        }
    }

    // ================================================================== ce que les atomes se font sans liaison
    private void drawContacts(GraphicsContext g, GraphicsContext near, double[][] screen) {
        int n = positions.length;
        for (Pair p : pairs) {
            if (p.a() >= n || p.b() >= n) continue;
            // Deux ions : un indice de liaison sans paire partagée (NaCl : la paire reste sur Cl). À l'équilibre leur
            // force nette est nulle — l'attraction des électrons compense la répulsion des cœurs — donc on montre
            // l'attraction seule. Avec une paire partagée, c'est un trait ou une étoile.
            boolean ions = p.order() >= BOND_ORDER;
            if (ions && sharesAPair(p.a(), p.b())) {
                if (linked(p.a(), p.b())) continue;
                ions = false;                   // une paire restée appariée d'un fragment à l'autre : un simple contact
            }
            if (!ions) {
                int hop = p.a() < hops.length && p.b() < hops.length ? hops[p.a()][p.b()] : Integer.MAX_VALUE;
                if (hop < 4) continue;                   // 1-2, 1-3, 1-4 : la géométrie des liaisons, pas un contact
            }
            double pull = ions ? p.electronic() + p.dispersion() : p.total();
            double[] a = screen[p.a()], b = screen[p.b()];
            if (a == null || b == null) continue;
            double dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy);
            if (len < 1) continue;
            double ux = dx / len, uy = dy / len;
            double ra = sphere(p.a()) * a[3], rb = sphere(p.b()) * b[3];
            if (pull > .06 && p.distance() < 3.6) {
                // Une attraction sans paire partagée : pointillés qui glissent vers le milieu.
                double alpha = Math.max(.18, Math.min(.65, .2 + .25 * Math.log10(1 + pull / .03)));
                double from = ra + 3, to = len - rb - 3, gap = 7, shift = (clock * 7) % gap;
                for (double s = from; s <= to; s += gap) {
                    double mid = (from + to) / 2;
                    double at = s < mid ? Math.min(mid, s + shift) : Math.max(mid, s - shift);
                    double x = a[0] + ux * at, y = a[1] + uy * at;
                    g.setFill(DOTS.deriveColor(0, 1, 1, alpha));
                    g.fillOval(x - .8, y - .8, 1.6, 1.6);
                    near.setFill(DOTS.deriveColor(0, 1, 1, alpha * .6));
                    near.fillOval(x - 1.5, y - 1.5, 3, 3);
                }
            } else if (!ions && p.total() < -.25) {
                repel(g, near, p, a, ux, uy, len, ra, rb);
            }
        }
    }

    /**
     * Une répulsion sans paire partagée, montrée par sa force calculée, comme l'attraction mais à l'envers : des
     * points orange partent de là où les deux nuages se touchent (sur l'axe, où se coupent leurs surfaces à
     * 0,002 e/bohr³) et s'écartent vers chacun des deux atomes. Leur vitesse et leur éclat suivent la force de la
     * paire (action = −réaction), recalculée à chaque pas : une collision s'allume, culmine et s'éteint avec elle.
     */
    private void repel(GraphicsContext g, GraphicsContext near, Pair p, double[] a, double ux, double uy, double len,
                       double ra, double rb) {
        double force = -p.total(), heat = Math.tanh(force / 2);
        double alpha = Math.max(.25, Math.min(.85, .25 + .3 * Math.log10(1 + force / .1)));
        double from = ra + 3, to = len - rb - 3;
        if (to - from < 6) return;
        // Le point de contact : à la distance x de a où les deux surfaces se coupent (plan radical), sur l'écran à la
        // même fraction de l'axe projeté.
        double ra3 = surface(p.a()), rb3 = surface(p.b()), d = Math.max(1e-6, p.distance());
        double x = (d * d + ra3 * ra3 - rb3 * rb3) / (2 * d);
        double at = Math.max(from + 2, Math.min(to - 2, len * x / d));
        double gap = 6, shift = (clock * (8 + 14 * heat)) % gap;
        for (int side = -1; side <= 1; side += 2) {
            double reach = side < 0 ? at - from : to - at;
            for (double s = shift; s <= reach; s += gap) {
                double along = at + side * s, fade = 1 - .75 * s / Math.max(1, reach);
                double px = a[0] + ux * along, py = a[1] + uy * along;
                g.setFill(SQUEEZED.deriveColor(0, 1, 1, alpha * fade));
                g.fillOval(px - .9, py - .9, 1.8, 1.8);
                near.setFill(SQUEEZED.deriveColor(0, 1, 1, .6 * alpha * fade));
                near.fillOval(px - 1.6, py - 1.6, 3.2, 3.2);
            }
        }
        double cx = a[0] + ux * at, cy = a[1] + uy * at;
        near.setFill(SQUEEZED.deriveColor(0, 1, 1, .55 * heat));
        near.fillOval(cx - 2.5, cy - 2.5, 5, 5);
    }

    /** Les atomes a et b partagent-ils une paire d'électrons, sur eux deux ou avec d'autres atomes ? */
    private boolean sharesAPair(int a, int b) {
        for (Bond bond : bonds) {
            if ((bond.a() == a && bond.b() == b || bond.a() == b && bond.b() == a) && bond.twoCentre()) return true;
        }
        for (Multicentre m : multicentres) if (m.holds(a, b)) return true;
        return false;
    }

    /** La paire tient-elle une liaison qui n'a pas de paire à deux centres ? Seules celles-là sont dessinées. */
    private boolean holdsABond(Multicentre m) {
        for (Bond bond : bonds) {
            if (bond.bonded() && !bond.twoCentre() && m.holds(bond.a(), bond.b())) return true;
        }
        return false;
    }

    /** Deux atomes liés au sens du dessin (Bond.bonded), et non seulement par leur indice de Mayer. */
    boolean linked(int a, int b) {
        for (Bond bond : bonds) {
            if ((bond.a() == a && bond.b() == b) || (bond.a() == b && bond.b() == a)) return bond.bonded();
        }
        return false;
    }

    // ================================================================== les liaisons
    /**
     * Les paires à plusieurs centres, en notation de Lipscomb : des rayons fins partent du barycentre de la paire vers
     * chacun de ses atomes, d'autant plus nets que l'atome porte une grande part de la paire ; des grains (ses deux
     * électrons) restent serrés autour du barycentre. Dans Au₆, trois étoiles : chaque paire tient un triangle.
     */
    private void drawMulticentres(GraphicsContext g, GraphicsContext near, GraphicsContext far) {
        int n = positions.length;
        g.setLineCap(StrokeLineCap.ROUND);
        near.setLineCap(StrokeLineCap.ROUND);
        for (int index = 0; index < multicentres.size(); index++) {
            Multicentre m = multicentres.get(index);
            if (!holdsABond(m)) continue;
            double[] c = m.centroid();
            double[] pc = camera.project(c[0], c[1], c[2]);
            if (pc == null) continue;
            double top = 0, total = 0;
            for (double p : m.populations()) {
                top = Math.max(top, p);
                total += p;
            }
            for (int k = 0; k < m.atoms().length; k++) {
                int atom = m.atoms()[k];
                if (atom >= n) continue;
                double[] s = surface(atom, c);
                double[] ps = s == null ? null : camera.project(s[0], s[1], s[2]);
                if (ps == null) continue;
                double weight = m.populations()[k] / top;
                g.setStroke(SHARED.deriveColor(0, 1, 1, .3 + .5 * weight));
                g.setLineWidth(.55 + .45 * weight);
                g.strokeLine(ps[0], ps[1], pc[0], pc[1]);
                near.setStroke(SHARED.deriveColor(0, 1, 1, .3 * weight));
                near.setLineWidth(2.4);
                near.strokeLine(ps[0], ps[1], pc[0], pc[1]);
            }
            double node = Math.max(1.1, .03 * pc[3]);
            g.setFill(new RadialGradient(0, 0, pc[0], pc[1], node * 2.2, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.web("#FFF8E6", .9)), new Stop(.35, SHARED.deriveColor(0, 1, 1, .45)), new Stop(1, Color.TRANSPARENT)));
            g.fillOval(pc[0] - node * 2.2, pc[1] - node * 2.2, node * 4.4, node * 4.4);
            near.setFill(SHARED.deriveColor(0, 1, 1, .45));
            near.fillOval(pc[0] - node * 1.3, pc[1] - node * 1.3, node * 2.6, node * 2.6);
            far.setFill(SHARED.deriveColor(0, 1, 1, .14));
            far.fillOval(pc[0] - node * 3.2, pc[1] - node * 3.2, node * 6.4, node * 6.4);
            // Les grains : chacun part du barycentre vers un atome tiré selon sa part de la paire, à une distance tirée
            // d'une loi exponentielle (la paire est serrée autour de son barycentre), et scintille.
            Random seeded = new Random(7919L * (index + 1) + n);
            for (int i = 0; i < 20; i++) {
                double pick = seeded.nextDouble() * total, sum = 0;
                int k = 0;
                while (k < m.atoms().length - 1 && pick >= sum + m.populations()[k]) sum += m.populations()[k++];
                int atom = m.atoms()[k];
                double depth = -Math.log(1 - .93 * seeded.nextDouble()) / 2.6;
                double size = .6 + .8 * seeded.nextDouble(), shine = .35 + .65 * seeded.nextDouble();
                double speed = .3 + .6 * seeded.nextDouble(), phase = seeded.nextDouble(), angle = seeded.nextDouble() * 2 * Math.PI;
                double[] s = atom < n ? surface(atom, c) : null;
                if (s == null) continue;
                double life = (clock * speed + phase) % 1.0;
                double along = Math.min(.9, depth + .03 * Math.sin(2 * Math.PI * life));
                double[] u = {s[0] - c[0], s[1] - c[1], s[2] - c[2]};
                double reach = norm(u);
                if (reach < 1e-6) continue;
                for (int j = 0; j < 3; j++) u[j] /= reach;
                double[] e1 = perpendicular(u), e2 = cross(u, e1);
                double turn = angle + clock * .5, radius = .03 * (1 - along);
                double cx = Math.cos(turn) * radius, sy = Math.sin(turn) * radius;
                double[] q = camera.project(c[0] + u[0] * along * reach + e1[0] * cx + e2[0] * sy,
                        c[1] + u[1] * along * reach + e1[1] * cx + e2[1] * sy, c[2] + u[2] * along * reach + e1[2] * cx + e2[2] * sy);
                if (q == null) continue;
                double a = (.45 + .55 * Math.sin(Math.PI * life)) * shine;
                g.setFill(Color.web("#FFF3D6", .8 * a));
                g.fillOval(q[0] - size / 2, q[1] - size / 2, size, size);
            }
        }
    }

    /** Le point de la bulle de l'atome tourné vers le point c (null si c est dans la bulle). */
    private double[] surface(int atom, double[] c) {
        double[] r = positions[atom];
        double[] u = {c[0] - r[0], c[1] - r[1], c[2] - r[2]};
        double length = norm(u), radius = sphere(atom);
        if (length <= radius + .02) return null;
        return new double[]{r[0] + u[0] / length * radius, r[1] + u[1] / length * radius, r[2] + u[2] / length * radius};
    }

    private void drawBonds(GraphicsContext g, GraphicsContext near, GraphicsContext far, double[][] screen) {
        int n = positions.length;
        g.setLineCap(StrokeLineCap.ROUND);
        near.setLineCap(StrokeLineCap.ROUND);
        far.setLineCap(StrokeLineCap.ROUND);
        for (Bond bond : bonds) {
            // Un faisceau, c'est une paire d'électrons sur deux atomes. Une liaison sans elle tient par une paire à
            // plusieurs centres (drawMulticentres) ou par l'attraction de deux ions (drawContacts).
            double fade = fade(bond);
            if (fade <= 0 || bond.a() >= n || bond.b() >= n || !bond.twoCentre()) continue;
            double[] A = positions[bond.a()], B = positions[bond.b()];
            double[] u = {B[0] - A[0], B[1] - A[1], B[2] - A[2]};
            double length = norm(u);
            if (length < 1e-6) continue;
            for (int k = 0; k < 3; k++) u[k] /= length;
            double ra = sphere(bond.a()), rb = sphere(bond.b()), gap = length - ra - rb;
            if (gap <= .01) continue;
            double[] sa = {A[0] + u[0] * ra, A[1] + u[1] * ra, A[2] + u[2] * ra};
            double[] sb = {B[0] - u[0] * rb, B[1] - u[1] * rb, B[2] - u[2] * rb};
            double[] pa = camera.project(sa[0], sa[1], sa[2]), pb = camera.project(sb[0], sb[1], sb[2]);
            if (pa == null || pb == null) continue;
            double strength = Math.min(1, bond.order());
            double flash = flashes.containsKey(key(bond.a(), bond.b()))
                    ? Math.max(0, 1 - (clock - flashes.get(key(bond.a(), bond.b()))) / .8) : 0;
            double tension = Math.tanh(tension(bond.a(), bond.b()) / 3);
            Color glow = tension >= 0 ? BEAM.interpolate(TENSE, tension * .8) : BEAM.interpolate(SQUEEZED, -tension);
            Color core = tension >= 0 ? CORE : CORE.interpolate(Color.web("#FFE2CF"), -tension);
            double width = (.8 + .35 * Math.min(3, bond.order())) * (.45 + .55 * fade);
            double alpha = (.35 + .65 * strength) * fade;
            piLobes(g, near, bond, sa, u, gap, alpha);           // avant le trait, qui brille à travers eux
            g.setStroke(glow.deriveColor(0, 1, 1, .28 * alpha));
            g.setLineWidth(width + 1.8);
            g.strokeLine(pa[0], pa[1], pb[0], pb[1]);
            g.setStroke(core.deriveColor(0, 1, 1, Math.min(1, .95 * alpha + flash)));
            g.setLineWidth(width);
            g.strokeLine(pa[0], pa[1], pb[0], pb[1]);
            near.setStroke(glow.deriveColor(0, 1, 1, Math.min(1, .9 * alpha + flash)));
            near.setLineWidth(width + 2.5 + 4 * flash);
            near.strokeLine(pa[0], pa[1], pb[0], pb[1]);
            far.setStroke(glow.deriveColor(0, 1, 1, .55 * alpha + .4 * flash));
            far.setLineWidth(width + 6);
            far.strokeLine(pa[0], pa[1], pb[0], pb[1]);
            dust(g, near, bond, A, u, length, ra, rb, alpha, fade);
        }
    }

    /** La tension d'une liaison, ½ (F_A − F_B)·u (eV/Å, forces internes : + tirée, − comprimée). */
    private double tension(int a, int b) {
        if (a >= netForce.length || b >= netForce.length || netForce[a] == null || netForce[b] == null) return 0;
        double[] u = {positions[b][0] - positions[a][0], positions[b][1] - positions[a][1], positions[b][2] - positions[a][2]};
        double l = norm(u);
        if (l < 1e-9) return 0;
        double t = 0;
        for (int k = 0; k < 3; k++) {
            double fa = netForce[a][k] - (a < externalForce.length && externalForce[a] != null ? externalForce[a][k] : 0);
            double fb = netForce[b][k] - (b < externalForce.length && externalForce[b] != null ? externalForce[b][k] : 0);
            t += .5 * (fa - fb) * u[k] / l;
        }
        return t;
    }

    /**
     * La part π : deux lobes de verre de part et d'autre de la liaison, le long de la direction calculée où se tient sa
     * densité (éthylène, benzène : la normale au plan) ; une seconde paire, d'un quart de tour, quand la part π se
     * tient aussi de l'autre côté (N₂ : 1 et 1, deux π ; CO₂ : 0,47 et 0,47). Chaque paire de lobes est d'autant plus
     * nette que sa part π est grande ; leur hauteur suit l'orbitale p des deux atomes, là où elle est la plus dense
     * (C 0,29 Å, Cl 0,51 Å).
     */
    private void piLobes(GraphicsContext g, GraphicsContext near, Bond bond, double[] start, double[] u, double gap,
                         double alpha) {
        if (bond.piAxis() == null) return;
        double rp = .5 * (pRadius(bond.a()) + pRadius(bond.b()));
        if (rp <= 0) return;
        double[] centre = {start[0] + u[0] * gap / 2, start[1] + u[1] * gap / 2, start[2] + u[2] * gap / 2};
        double[] first = bond.piAxis(), second = cross(u, first);
        for (int k = 0; k < 2; k++) {
            double part = bond.piSplit()[k];
            if (part < .12) continue;
            double[] n = k == 0 ? first : second, across = cross(u, n);
            double[][] axes = {scaled(u, .5 * gap + .6 * rp), scaled(n, rp), scaled(across, .85 * rp)};
            for (int side = -1; side <= 1; side += 2) {
                double h = side * 1.45 * rp;
                ellipsoid(g, near, new double[]{centre[0] + n[0] * h, centre[1] + n[1] * h, centre[2] + n[2] * h}, axes,
                        HELIX, Math.min(1, part) * alpha);
            }
        }
    }

    /** Un ellipsoïde de verre (centre et trois demi-axes, Å), dessiné par sa silhouette exacte : bord net, centre clair. */
    private void ellipsoid(GraphicsContext g, GraphicsContext near, double[] c, double[][] axes, Color colour,
                           double weight) {
        double[] p = camera.project(c[0], c[1], c[2]);
        if (p == null) return;
        double[] shape = silhouette(axes, camera.right(), camera.up());
        double rx = Math.max(.6, shape[0] * p[3]), ry = Math.max(.6, shape[1] * p[3]);
        for (GraphicsContext layer : new GraphicsContext[]{g, near}) {
            layer.save();
            layer.translate(p[0], p[1]);
            layer.rotate(Math.toDegrees(shape[2]));
            if (layer == g) {
                layer.setFill(new RadialGradient(0, 0, .5, .5, .5, true, CycleMethod.NO_CYCLE,
                        new Stop(0, colour.deriveColor(0, 1, 1, .05 * weight)), new Stop(.62, colour.deriveColor(0, 1, 1, .1 * weight)),
                        new Stop(.9, colour.deriveColor(0, 1, 1, .32 * weight)), new Stop(1, colour.deriveColor(0, 1, 1, 0))));
                layer.fillOval(-rx, -ry, 2 * rx, 2 * ry);
            } else {
                layer.setStroke(colour.deriveColor(0, 1, 1, .22 * weight));
                layer.setLineWidth(1.2);
                layer.strokeOval(-rx, -ry, 2 * rx, 2 * ry);
            }
            layer.restore();
        }
    }

    /**
     * La silhouette d'un ellipsoïde en projection orthographique sur l'écran : l'ellipse de matrice Σ = Σ_i v_i v_iᵀ,
     * v_i le demi-axe i projeté (vers la droite, vers le bas de l'écran). Rend {grand demi-axe, petit demi-axe, angle du
     * grand axe à l'écran (rad)}, en unités de longueur de la scène.
     */
    static double[] silhouette(double[][] axes, double[] right, double[] up) {
        double sxx = 0, sxy = 0, syy = 0;
        for (double[] axis : axes) {
            double x = axis[0] * right[0] + axis[1] * right[1] + axis[2] * right[2];
            double y = -(axis[0] * up[0] + axis[1] * up[1] + axis[2] * up[2]);
            sxx += x * x;
            sxy += x * y;
            syy += y * y;
        }
        double mean = (sxx + syy) / 2, half = (sxx - syy) / 2, root = Math.sqrt(half * half + sxy * sxy);
        return new double[]{Math.sqrt(mean + root), Math.sqrt(Math.max(0, mean - root)), .5 * Math.atan2(2 * sxy, sxx - syy)};
    }

    /** La présence d'un trait selon l'indice de liaison : 0 sous FADE_FROM, 1 dès BOND_ORDER + 0,15, lisse entre. */
    static double fade(double order) {
        double t = Math.max(0, Math.min(1, (order - FADE_FROM) / (BOND_ORDER + .15 - FADE_FROM)));
        return t * t * (3 - 2 * t);
    }

    /** La présence du trait d'une liaison : son indice, et ce que ses atomes partagent encore entre eux. */
    static double fade(Bond bond) {
        double t = Math.max(0, Math.min(1, (bond.shared() - SHARED_FROM) / (SHARED_FULL - SHARED_FROM)));
        return fade(bond.order()) * t * t * (3 - 2 * t);
    }

    private static double[] scaled(double[] v, double k) {
        return new double[]{v[0] * k, v[1] * k, v[2] * k};
    }

    /**
     * Les grains : les électrons de la paire de la liaison, tirés de sa densité le long de l'axe — le profil calculé
     * par le moteur (ses orbitales localisées, intégrées tranche par tranche) — là où elle passe entre les deux bulles.
     * Étalés sur toute la liaison quand elle est covalente (C–H, H₂), plus denses du côté de l'atome qui garde la paire
     * quand elle est polaire (O–H : vers O). Chaque grain s'éteint et renaît ailleurs, tiré de la même loi : un
     * échantillonnage de la densité.
     */
    private void dust(GraphicsContext g, GraphicsContext near, Bond bond, double[] A, double[] u, double length,
                      double ra, double rb, double alpha, double fade) {
        double from = ra / length, to = 1 - rb / length;
        double[] cumulative = profileLaw(bond.profile(), from, to);
        double[] e1 = perpendicular(u), e2 = cross(u, e1);
        long key = key(bond.a(), bond.b());
        Random seeded = new Random(key * 7919L);
        // Autant de grains que de paires partagées : 24 pour une simple, 36 pour une double, 48 pour une triple.
        int count = (int) Math.round((12 + 12 * Math.min(3, bond.order())) * fade);
        for (int i = 0; i < count; i++) {
            double size = .6 + .8 * seeded.nextDouble(), shine = .35 + .65 * seeded.nextDouble();
            double speed = .3 + .6 * seeded.nextDouble(), phase = seeded.nextDouble();
            double cycle = Math.floor(clock * speed + phase), life = clock * speed + phase - cycle;
            Random draw = new Random(key * 104729L + i * 7919L + (long) cycle);
            double t = sample(cumulative, from, to, draw.nextDouble());
            double angle = draw.nextDouble() * 2 * Math.PI, radius = .012 + .04 * draw.nextDouble();
            double c = Math.cos(angle) * radius, s = Math.sin(angle) * radius, at = t * length;
            double[] q = camera.project(A[0] + u[0] * at + e1[0] * c + e2[0] * s, A[1] + u[1] * at + e1[1] * c + e2[1] * s,
                    A[2] + u[2] * at + e1[2] * c + e2[2] * s);
            if (q == null) continue;
            double a = Math.sin(Math.PI * life) * shine * alpha;            // s'allume et s'éteint : le saut ne se voit pas
            g.setFill(DUST.deriveColor(0, 1, 1, .85 * a));
            g.fillOval(q[0] - size / 2, q[1] - size / 2, size, size);
            if (shine > .75) {
                near.setFill(BEAM.deriveColor(0, 1, 1, .6 * a));
                near.fillOval(q[0] - size, q[1] - size, 2 * size, 2 * size);
            }
        }
    }

    /**
     * La loi des grains entre deux bulles : le profil (tranches égales de 0 à 1, d'un noyau à l'autre) restreint à
     * [from, to], cumulé tranche par tranche (une part par tranche, normalisée) ; uniforme sans profil.
     */
    static double[] profileLaw(float[] profile, double from, double to) {
        int bins = profile.length == 0 ? 1 : profile.length;
        double[] cumulative = new double[bins + 1];
        for (int k = 0; k < bins; k++) {
            double lo = Math.max(from, k / (double) bins), hi = Math.min(to, (k + 1) / (double) bins);
            double mass = hi > lo ? (hi - lo) * bins * (profile.length == 0 ? 1 : profile[k]) : 0;
            cumulative[k + 1] = cumulative[k] + mass;
        }
        double total = cumulative[bins];
        for (int k = 0; k <= bins; k++) cumulative[k] = total > 0 ? cumulative[k] / total : k / (double) bins;
        return cumulative;
    }

    /** Une position (fraction de la liaison, entre from et to) tirée de la loi cumulée, pour un tirage uniforme x. */
    static double sample(double[] cumulative, double from, double to, double x) {
        int bins = cumulative.length - 1;
        int k = 0;
        while (k < bins - 1 && cumulative[k + 1] <= x) k++;
        double span = cumulative[k + 1] - cumulative[k];
        double lo = Math.max(from, k / (double) bins), hi = Math.min(to, (k + 1) / (double) bins);
        if (hi <= lo) return Math.max(from, Math.min(to, (from + to) / 2));
        return lo + (hi - lo) * (span > 0 ? (x - cumulative[k]) / span : .5);
    }

    /** Les tailles de l'atome libre envoyées par le moteur : sa surface à 0,002 e/bohr³, le maximum de son orbitale p. */
    private double surface(int atom) {
        double[] r = atom < z.length ? atomRadii.get(z[atom]) : null;
        return r != null && r[0] > 0 ? r[0] : (atom < covalent.length ? covalent[atom] : .7) + .8;
    }

    private double pRadius(int atom) {
        double[] r = atom < z.length ? atomRadii.get(z[atom]) : null;
        return r != null ? r[1] : .3;
    }


    // ================================================================== les atomes
    private void drawAtoms(GraphicsContext g, GraphicsContext near, GraphicsContext far, double[][] screen) {
        int n = positions.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(screen[b] == null ? 0 : screen[b][2], screen[a] == null ? 0 : screen[a][2]));
        if (showOrders) {
            g.setFont(Font.font("Segoe UI", 10.5));
            g.setTextAlign(TextAlignment.CENTER);
            for (Bond bond : bonds) {
                if (bond.order() < .3 || bond.shared() < SHARED_FROM || bond.a() >= n || bond.b() >= n) continue;
                double[] a = screen[bond.a()], b = screen[bond.b()];
                if (a == null || b == null) continue;
                g.setFill(Color.web("#E9F3FF", .8));
                g.fillText(MoleculeFormat.decimal(bond.order(), 2), (a[0] + b[0]) / 2, (a[1] + b[1]) / 2 - 8);
            }
        }
        double[][] spots = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] p = screen[i];
            if (p == null) continue;
            double shake = Math.min(2, .5 * squeeze(i));
            spots[i] = new double[]{p[0] + shake * Math.sin(clock * 71 + i * 1.7), p[1] + shake * Math.cos(clock * 83 + i),
                    Math.max(5, sphere(i) * p[3])};
        }
        GraphicsContext[] layers = {g, near, far};
        for (int k = 0; k < n; k++) {
            int idx = order[k];
            double[] p = screen[idx];
            if (p == null) continue;
            double x = spots[idx][0], y = spots[idx][1], radius = spots[idx][2];
            // Les bulles plus proches qui la recouvrent à l'écran : vue à travers leur verre, elle est voilée. Sans ce
            // voile, deux bulles l'une derrière l'autre se liraient comme deux bulles qui s'interpénètrent.
            List<double[]> covers = new ArrayList<>();
            for (int m = k + 1; m < n; m++) {
                double[] c = spots[order[m]];
                if (c != null && Math.hypot(c[0] - x, c[1] - y) < c[2] + radius) covers.add(c);
            }
            if (covers.isEmpty()) {
                bubble(g, near, far, idx, p, x, y, radius);
            } else {
                for (GraphicsContext layer : layers) clip(layer, covers, false, x, y, radius);
                bubble(g, near, far, idx, p, x, y, radius);
                for (GraphicsContext layer : layers) {
                    layer.restore();
                    clip(layer, covers, true, x, y, radius);
                    layer.setGlobalAlpha(BEHIND_GLASS);
                }
                bubble(g, near, far, idx, p, x, y, radius);
                for (GraphicsContext layer : layers) layer.restore();
            }
            if (idx == hovered || idx == selected || idx == dragged) {
                double ring = radius + 5;
                g.setStroke(idx == selected ? Color.web("#FFE07A", .8) : Color.web("#FFFFFF", .4));
                g.setLineWidth(.8);
                g.strokeOval(x - ring, y - ring, 2 * ring, 2 * ring);
            }
            // Le symbole se pose du côté opposé aux liaisons de l'atome, pour ne jamais couvrir un faisceau.
            double[] away = labelDirection(idx, screen);
            double lx = x + away[0] * (radius + 11), ly = y + away[1] * (radius + 11) + 4;
            g.setTextAlign(TextAlignment.CENTER);
            if (showSymbols && idx < symbols.length) {
                g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5));
                g.setFill(Color.web("#DCEBFF", .55));
                g.fillText(MoleculeView.nuclide(symbols[idx], idx < massNumbers.length ? massNumbers[idx] : 0), lx, ly);
            }
            if (idx < charges.length && Math.abs(charges[idx]) >= SIGN_CHARGE) signs(g, idx, x, y, radius, away);
        }
    }

    /** L'atome lui-même : la bulle, ses arcs, son noyau. */
    private void bubble(GraphicsContext g, GraphicsContext near, GraphicsContext far, int idx, double[] p, double x,
                        double y, double radius) {
        glass(g, near, far, x, y, radius, rimColour(idx < charges.length ? charges[idx] : 0));
        if (showInner && idx < arcs.size()) drawArcs(g, near, idx, x - p[0], y - p[1], radius);
        nucleus(g, near, far, x, y, radius);
    }

    /** Restreint un calque au dehors (ou au dedans) des disques (x, y, r) donnés, autour de la bulle (x, y, r). */
    private static void clip(GraphicsContext layer, List<double[]> discs, boolean inside, double x, double y, double r) {
        layer.save();
        layer.setFillRule(inside ? FillRule.NON_ZERO : FillRule.EVEN_ODD);
        layer.beginPath();
        if (!inside) layer.rect(x - 2 * r - 40, y - 2 * r - 40, 4 * r + 80, 4 * r + 80);
        for (double[] d : discs) {
            layer.moveTo(d[0] + d[2], d[1]);
            layer.arc(d[0], d[1], d[2], d[2], 0, 360);
            layer.closePath();
        }
        layer.clip();
    }

    /**
     * Le signe δ+ ou δ− d'un atome, posé juste au-delà du bord du halo, du côté opposé à ses liaisons — plus loin que
     * son symbole ; avec « valeurs des charges », la charge de Mulliken en dessous.
     */
    private void signs(GraphicsContext g, int idx, double x, double y, double radius, double[] away) {
        double q = charges[idx];
        boolean shown = showHalo && !showPotential && mode == DENSITY;
        double edge = shown ? halo.edge(idx, x, y, away[0], away[1]) : 0;
        // Un atome au cœur de la molécule (bord à plus de 1,6 Å) garde son signe près de lui.
        if (edge > radius + 1.6 * radius / sphere(idx)) edge = 0;
        double distance = Math.max(radius + 27, edge + 14);
        float[] c = chargeColour(q);
        Color colour = Color.color(Math.min(1, c[0] * 1.08), Math.min(1, c[1] * 1.08), Math.min(1, c[2] * 1.08));
        double lx = x + away[0] * distance, ly = y + away[1] * distance + 5;
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14));
        g.setFill(colour);
        g.fillText(q < 0 ? "δ−" : "δ+", lx, ly);
        if (showCharges) {
            g.setFont(Font.font("Segoe UI", 10));
            g.setFill(colour.deriveColor(0, 1, 1, .8));
            g.fillText(MoleculeFormat.signed(q, 2), lx, ly + 13);
        }
    }

    /**
     * La direction (écran, unitaire) du symbole et du signe δ d'un atome : au milieu du plus grand angle libre entre
     * ses liaisons à l'écran (à l'opposé de son unique liaison), le haut de l'écran à égalité ; vers le bas s'il n'en a
     * pas.
     */
    private double[] labelDirection(int idx, double[][] screen) {
        List<Double> angles = new ArrayList<>();
        for (Bond b : bonds) {
            if (!b.bonded() || (b.a() != idx && b.b() != idx)) continue;
            int other = b.a() == idx ? b.b() : b.a();
            if (other >= screen.length || screen[other] == null || screen[idx] == null) continue;
            double dx = screen[other][0] - screen[idx][0], dy = screen[other][1] - screen[idx][1];
            if (Math.hypot(dx, dy) > 1e-6) angles.add(Math.atan2(dy, dx));
        }
        if (angles.isEmpty()) return new double[]{0, 1};
        angles.sort(null);
        double best = -1, at = 0;
        for (int i = 0; i < angles.size(); i++) {
            double from = angles.get(i), to = i + 1 < angles.size() ? angles.get(i + 1) : angles.get(0) + 2 * Math.PI;
            double middle = (from + to) / 2, score = to - from - .03 * Math.sin(middle);
            if (score > best) {
                best = score;
                at = middle;
            }
        }
        return new double[]{Math.cos(at), Math.sin(at)};
    }

    /** Le liseré : bleu de verre ; vers le magenta si l'atome a gagné des électrons (δ−), vers le cyan s'il en a perdu. */
    static Color rimColour(double q) {
        double t = Math.max(-1, Math.min(1, q / .5));
        return t < 0 ? GLASS.interpolate(RICH, -t * .8) : GLASS.interpolate(POOR, t * .8);
    }

    /** La bulle : centre presque transparent, bord de Fresnel net et lumineux, reflet en haut à gauche. */
    private void glass(GraphicsContext g, GraphicsContext near, GraphicsContext far, double x, double y, double r, Color rim) {
        g.setFill(new RadialGradient(0, 0, x + r * .12, y + r * .15, r, false, CycleMethod.NO_CYCLE,
                new Stop(0, rim.deriveColor(0, 1, .3, .025)), new Stop(.6, rim.deriveColor(0, 1, .5, .05)),
                new Stop(.86, rim.deriveColor(0, 1, .8, .13)), new Stop(.965, rim.deriveColor(0, .9, 1, .5)),
                new Stop(1, rim.deriveColor(0, .8, 1.2, 0))));
        g.fillOval(x - r, y - r, 2 * r, 2 * r);
        g.setStroke(rim.deriveColor(0, .8, 1.15, .85));
        g.setLineWidth(.9);
        g.strokeOval(x - r, y - r, 2 * r, 2 * r);
        // Le reflet de la lumière : un arc vif en haut à gauche, une tache douce dedans, un contre-reflet en bas.
        g.setStroke(Color.web("#FFFFFF", .55));
        g.setLineWidth(1.3);
        g.strokeArc(x - r * .93, y - r * .93, 1.86 * r, 1.86 * r, 105, 55, ArcType.OPEN);
        double hx = x - r * .42, hy = y - r * .4, hr = r * .3;
        g.setFill(new RadialGradient(0, 0, hx, hy, hr, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#FFFFFF", .32)), new Stop(1, Color.TRANSPARENT)));
        g.fillOval(hx - hr, hy - hr, 2 * hr, 2 * hr);
        g.setStroke(rim.deriveColor(0, 1, 1, .3));
        g.setLineWidth(1);
        g.strokeArc(x - r * .9, y - r * .9, 1.8 * r, 1.8 * r, 285, 60, ArcType.OPEN);
        near.setStroke(rim.deriveColor(0, 1, 1, .55));
        near.setLineWidth(1.6);
        near.strokeOval(x - r, y - r, 2 * r, 2 * r);
        far.setStroke(rim.deriveColor(0, 1, 1, .22));
        far.setLineWidth(3);
        far.strokeOval(x - r, y - r, 2 * r, 2 * r);
    }

    /** Les arcs intérieurs : tracés fins, plus pâles derrière le noyau, une lueur qui coule du noyau vers les liaisons. */
    private void drawArcs(GraphicsContext g, GraphicsContext near, int idx, double shiftX, double shiftY, double radius) {
        double[] centre = positions[idx];
        double r = sphere(idx);
        double[] f = camera.forward();
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        double width = radius > 30 ? .7 : .55;
        for (double[] line : arcs.get(idx)) {
            int n = line.length / 3;
            if (n < 2) continue;
            double[] xs = new double[n], ys = new double[n];
            int m = 0;
            double depth = 0;
            for (int k = 0; k < n; k++) {
                double[] q = camera.project(centre[0] + line[3 * k] * r, centre[1] + line[3 * k + 1] * r, centre[2] + line[3 * k + 2] * r);
                if (q == null) continue;
                xs[m] = q[0] + shiftX;
                ys[m] = q[1] + shiftY;
                m++;
                depth += line[3 * k] * f[0] + line[3 * k + 1] * f[1] + line[3 * k + 2] * f[2];
            }
            if (m < 2) continue;
            boolean behind = depth / n > .05;
            g.setStroke(Color.web("#D6EDFF", behind ? .18 : .5));
            g.setLineWidth(width);
            g.strokePolyline(xs, ys, m);
            // Le courant : de courts traits clairs qui glissent du noyau vers le bord.
            g.setStroke(Color.web("#F2FAFF", behind ? .2 : .55));
            g.setLineDashes(3, 16);
            g.setLineDashOffset(-clock * 14);
            g.strokePolyline(xs, ys, m);
            g.setLineDashes();
            if (!behind) {
                near.setStroke(Color.web("#9FD8FF", .18));
                near.setLineWidth(1.2);
                near.strokePolyline(xs, ys, m);
            }
        }
    }

    /** Le noyau : un point incandescent. */
    private void nucleus(GraphicsContext g, GraphicsContext near, GraphicsContext far, double x, double y, double radius) {
        double core = Math.max(1.4, radius * .06);
        g.setFill(new RadialGradient(0, 0, x, y, core * 1.5, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#FFF1E4")), new Stop(.4, Color.web("#FF9A5C")), new Stop(1, NUCLEUS.deriveColor(0, 1, 1, 0))));
        g.fillOval(x - core * 1.5, y - core * 1.5, 3 * core, 3 * core);
        near.setFill(NUCLEUS.deriveColor(0, 1, 1, .8));
        near.fillOval(x - core * 1.5, y - core * 1.5, 3 * core, 3 * core);
        far.setFill(Color.web("#FF4A1A", .35));
        far.fillOval(x - core * 2.6, y - core * 2.6, 5.2 * core, 5.2 * core);
    }

    /** Une liaison qui casse : une gerbe de poussière qui s'envole du point de rupture (une seconde). */
    private void drawBursts(GraphicsContext g, GraphicsContext near) {
        bursts.removeIf(b -> clock - b[3] > 1.0);
        for (double[] b : bursts) {
            double age = clock - b[3];
            Random seeded = new Random((long) b[4]);
            for (int i = 0; i < 26; i++) {
                double[] d = {seeded.nextGaussian(), seeded.nextGaussian(), seeded.nextGaussian()};
                double l = norm(d), speed = .3 + .5 * seeded.nextDouble();
                double[] q = camera.project(b[0] + d[0] / l * speed * age, b[1] + d[1] / l * speed * age, b[2] + d[2] / l * speed * age);
                if (q == null) continue;
                double a = (1 - age) * .8;
                g.setFill(DUST.deriveColor(0, 1, 1, a));
                g.fillOval(q[0] - .8, q[1] - .8, 1.6, 1.6);
                near.setFill(BEAM.deriveColor(0, 1, 1, a * .7));
                near.fillOval(q[0] - 1.6, q[1] - 1.6, 3.2, 3.2);
            }
        }
    }

    /** La répulsion nette que les voisins exercent sur l'atome au-delà de 0,5 eV/Å par paire (eV/Å). */
    double squeeze(int atom) {
        double sum = 0;
        for (Pair p : pairs) {
            if ((p.a == atom || p.b == atom) && p.total < -.5 && (p.order < BOND_ORDER || !linked(p.a, p.b))) {
                sum += -p.total - .5;
            }
        }
        return sum;
    }

    /** Une seule flèche par atome, la force nette (option d'analyse). */
    private void drawArrows(GraphicsContext g) {
        for (int i = 0; i < positions.length && i < netForce.length; i++) {
            double[] f = netForce[i];
            if (f == null) continue;
            double magnitude = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
            if (magnitude < .02) continue;
            double length = .28 * Math.log10(1 + magnitude / .02);
            double[] p = positions[i];
            double[] a = camera.project(p[0], p[1], p[2]);
            double[] b = camera.project(p[0] + f[0] / magnitude * length, p[1] + f[1] / magnitude * length,
                    p[2] + f[2] / magnitude * length);
            if (a == null || b == null) continue;
            arrow(g, a[0], a[1], b[0], b[1], Color.web("#FFE07A", .85), 1.4);
        }
    }

    static void arrow(GraphicsContext g, double x0, double y0, double x1, double y1, Color color, double width) {
        double dx = x1 - x0, dy = y1 - y0, len = Math.hypot(dx, dy);
        if (len < 2) return;
        g.setStroke(color);
        g.setFill(color);
        g.setLineWidth(width);
        g.setLineCap(StrokeLineCap.ROUND);
        double head = Math.min(8, len * .4), ux = dx / len, uy = dy / len;
        g.strokeLine(x0, y0, x1 - ux * head * .6, y1 - uy * head * .6);
        g.fillPolygon(new double[]{x1, x1 - ux * head - uy * head * .5, x1 - ux * head + uy * head * .5},
                new double[]{y1, y1 - uy * head + ux * head * .5, y1 - uy * head - ux * head * .5}, 3);
    }

    private void drawDrag(GraphicsContext g) {
        if (dragged < 0 || dragTarget == null || dragged >= positions.length) return;
        double[] a = camera.project(positions[dragged][0], positions[dragged][1], positions[dragged][2]);
        double[] b = camera.project(dragTarget[0], dragTarget[1], dragTarget[2]);
        if (a == null || b == null) return;
        g.setStroke(Color.web("#FFFFFF", .5));
        g.setLineWidth(.8);
        g.setLineDashes(2, 5);
        g.strokeLine(a[0], a[1], b[0], b[1]);
        g.setLineDashes();
        g.setFill(Color.web("#FFFFFF", .9));
        g.fillOval(b[0] - 3, b[1] - 3, 6, 6);
        g.setFont(Font.font("Segoe UI", 11));
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(MoleculeFormat.decimal(tetherNewtons, 2) + " nN", b[0] + 8, b[1] - 6);
    }

    /**
     * Lire la scène, comme la légende d'une figure : une ligne par signe présent à l'écran, la grandeur calculée qu'il
     * montre, son dessin à gauche. Le cadre suit la longueur du texte et rétrécit avec la fenêtre.
     */
    private void drawLegend(GraphicsContext g, double w) {
        List<String[]> lines = new ArrayList<>();
        if (mode == DEFORMATION) {
            lines.add(new String[]{"gain", "trait plein : la molécule a gagné des électrons (Δρ > 0)"});
            lines.add(new String[]{"loss", "tirets : elle en a perdu, par rapport aux atomes libres (Δρ < 0)"});
            lines.add(new String[]{"levels", "Δρ vue en transparence ; une courbe à 0,025 · 0,05 · 0,1 · 0,2 · 0,4 · 0,8 · 1,6 e/Å²"});
            lines.add(new String[]{"nucleus", "point : un noyau"});
        } else if (mode == ORBITAL && glass) {
            lines.add(new String[]{"lobes", "les lobes enferment 85 % de |ψ|² : l'électron y est 85 fois sur 100"});
            lines.add(new String[]{"lobephase", "bleu : ψ > 0 · rouge : ψ < 0 — le signe de l'onde, pas une charge"});
            lines.add(new String[]{"lobenode", "entre un lobe bleu et un rouge : un nœud, ψ = 0"});
            lines.add(new String[]{"balls", "boules et bâtonnets : les noyaux et leurs liaisons"});
        } else if (mode == ORBITAL) {
            lines.add(new String[]{"cloud", "chaque point : un endroit où l'électron pourrait être (" + OrbitalDots.COUNT
                    + " tirages de |ψ|²)"});
            lines.add(new String[]{"phase", "bleu : ψ > 0 · orange : ψ < 0 — le signe de l'onde, pas une charge"});
            lines.add(new String[]{"node", "aucun point : un nœud, ψ = 0, l'électron n'y est jamais"});
            lines.add(new String[]{"skeleton", "point blanc et trait : les noyaux et leurs liaisons"});
        } else {
            boolean beams = false, stars = false;
            if (showBonds) {
                for (Bond bond : bonds) beams |= bond.bonded() && bond.twoCentre();
                for (Multicentre m : multicentres) stars |= holdsABond(m);
            }
            lines.add(new String[]{"sphere", "sphère : un atome · bord : sa charge (magenta δ−, cyan δ+)"});
            lines.add(new String[]{"halo", showPotential ? "potentiel sur ρ = 0,002 u.a. : magenta négatif, cyan positif"
                    : "halo : densité projetée, bord 0,3 e/Å², teinte des charges"});
            if (showInner) lines.add(new String[]{"arcs", "arcs : champ du noyau vers ses liaisons, dévié par son dipôle"});
            if (beams) {
                lines.add(new String[]{"line", "trait : paire d'électrons sur deux atomes · lobes : part π"});
                lines.add(new String[]{"tension", "bleu : force nulle · blanc : traction · orange : compression"});
            }
            if (beams || stars) lines.add(new String[]{"grains", "grains : la paire, tirée de sa densité le long de la liaison"});
            if (stars) lines.add(new String[]{"star", "étoile : paire sur trois atomes ou plus (multicentre)"});
            if (showContacts) lines.add(new String[]{"dots", "points vers le milieu : attraction · orange, vers les atomes : répulsion"});
        }
        double size = 10.5, widest = 0;
        Text probe = new Text();
        probe.setFont(Font.font("Segoe UI", size));
        for (String[] line : lines) {
            probe.setText(I18n.text(line[1]));
            widest = Math.max(widest, probe.getLayoutBounds().getWidth());
        }
        if (widest + 44 > w - 24) {
            double k = Math.max(.75, (w - 68) / widest);
            size *= k;
            widest *= k;
        }
        // Un fond presque opaque et un filet : la légende se lit sans laisser deviner la molécule dessous ; repliée,
        // il n'en reste que le titre, à droite.
        String title = I18n.text("Légende") + (legendFolded ? "  ▸" : "  ▾");
        probe.setFont(Font.font("Segoe UI", size));
        probe.setText(title);
        double titleWidth = probe.getLayoutBounds().getWidth() + 20;
        double box = legendFolded ? titleWidth : widest + 44, step = Math.round(size * 1.62);
        double x = Math.max(12, w - box - 4), y = 66;
        double height = legendFolded ? step + 6 : (lines.size() + 1) * step + 10;
        g.setFill(Color.web("#050B14", .88));
        g.fillRoundRect(x - 10, y - 15, box, height, 10, 10);
        g.setStroke(Color.web("#2A3B52", .9));
        g.setLineWidth(.8);
        g.strokeRoundRect(x - 10, y - 15, box, height, 10, 10);
        g.setFont(Font.font("Segoe UI", size));
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(Color.web("#8FA4C2", .95));
        g.fillText(title, x, y);
        legendTitle = new double[]{x - 10, y - 15, box, step + 6};
        if (mode == ORBITAL) scaleBar(g, x - 10 + box, y - 15 + height + 22);
        if (legendFolded) return;
        y += step;
        for (int i = 0; i < lines.size(); i++) {
            double gy = y + i * step - 4;
            switch (lines.get(i)[0]) {
                case "sphere" -> {
                    g.setStroke(RICH.deriveColor(0, 1, 1, .85));
                    g.setLineWidth(.9);
                    g.strokeOval(x + 3, gy - 5, 10, 10);
                }
                case "halo" -> {
                    float[] a = chargeColour(CHARGE_SCALE), b = chargeColour(-CHARGE_SCALE);
                    g.setFill(new javafx.scene.paint.LinearGradient(x, 0, x + 16, 0, false, CycleMethod.NO_CYCLE,
                            new Stop(0, Color.color(a[0], a[1], a[2], .75)), new Stop(1, Color.color(b[0], b[1], b[2], .75))));
                    g.fillRoundRect(x, gy - 5, 16, 9, 8, 8);
                }
                case "arcs" -> {
                    g.setStroke(Color.web("#D6EDFF", .6));
                    g.setLineWidth(.7);
                    g.strokeArc(x, gy - 6, 16, 14, 200, 140, ArcType.OPEN);
                }
                case "line" -> {
                    g.setStroke(CORE);
                    g.setLineWidth(1.2);
                    g.strokeLine(x, gy, x + 16, gy);
                }
                case "tension" -> {
                    g.setLineWidth(1.6);
                    g.setStroke(BEAM);
                    g.strokeLine(x, gy, x + 5, gy);
                    g.setStroke(TENSE);
                    g.strokeLine(x + 6, gy, x + 10, gy);
                    g.setStroke(SQUEEZED);
                    g.strokeLine(x + 11, gy, x + 16, gy);
                }
                case "grains" -> {
                    g.setFill(DUST);
                    for (int k = 0; k < 6; k++) g.fillOval(x + 16 - Math.pow(k, 1.4) * 2.2, gy - 1 + (k % 2) * 1.5, 1.4, 1.4);
                }
                case "star" -> {
                    g.setStroke(SHARED.deriveColor(0, 1, 1, .85));
                    g.setLineWidth(.9);
                    for (int k = 0; k < 3; k++) {
                        double angle = -Math.PI / 2 + k * 2 * Math.PI / 3;
                        g.strokeLine(x + 8, gy, x + 8 + 7 * Math.cos(angle), gy + 6 * Math.sin(angle));
                    }
                    g.setFill(Color.web("#FFF8E6"));
                    g.fillOval(x + 6.6, gy - 1.4, 2.8, 2.8);
                }
                case "gain", "loss" -> {
                    boolean gain = lines.get(i)[0].equals("gain");
                    g.setLineWidth(1.3);
                    if (!gain) g.setLineDashes(4.5, 3.5);
                    g.setStroke(gain ? DeformationMap.GAIN : DeformationMap.LOSS);
                    g.strokeArc(x - 2, gy - 4, 20, 14, 20, 140, ArcType.OPEN);
                    g.setLineDashes();
                }
                case "levels" -> {
                    g.setLineWidth(.9);
                    for (int k = 0; k < 3; k++) {
                        g.setStroke(DeformationMap.GAIN.deriveColor(0, 1, 1, .4 + .25 * k));
                        double r = 7 - 2.2 * k;
                        g.strokeOval(x + 8 - r, gy - r * .7, 2 * r, 1.4 * r);
                    }
                }
                case "nucleus" -> whiteNucleus(g, g, x + 8, gy);
                case "cloud" -> {
                    java.util.Random seeded = new java.util.Random(5);
                    for (int k = 0; k < 14; k++) {
                        double r = Math.abs(seeded.nextGaussian()) * 3.2, a = seeded.nextDouble() * 2 * Math.PI;
                        float[] c = OrbitalDots.PLUS;
                        g.setFill(Color.color(c[0], c[1], c[2], .9));
                        g.fillOval(x + 8 + r * Math.cos(a) * 1.6, gy + r * Math.sin(a), 1.6, 1.6);
                    }
                }
                case "phase" -> {
                    for (int k = 0; k < 7; k++) {
                        float[] c = k < 4 ? OrbitalDots.PLUS : OrbitalDots.MINUS;
                        g.setFill(Color.color(c[0], c[1], c[2], .95));
                        g.fillOval(x - 1 + k * 2.6, gy - 1.2 + (k % 3 - 1) * 1.6, 2, 2);
                    }
                }
                case "node" -> {
                    float[] p = OrbitalDots.PLUS, m = OrbitalDots.MINUS;
                    java.util.Random seeded = new java.util.Random(3);
                    for (int k = 0; k < 10; k++) {
                        float[] c = k < 5 ? p : m;
                        double ox = k < 5 ? x - 1 + seeded.nextDouble() * 6 : x + 12 + seeded.nextDouble() * 6;
                        g.setFill(Color.color(c[0], c[1], c[2], .9));
                        g.fillOval(ox, gy - 4 + seeded.nextDouble() * 8, 1.7, 1.7);
                    }
                }
                case "lobes", "lobephase", "lobenode" -> {
                    // De petits lobes de verre : un bleu, et l'orange pour la phase et le nœud.
                    String kind = lines.get(i)[0];
                    boolean two = !kind.equals("lobes");
                    double gap = kind.equals("lobenode") ? 5 : 1.5;
                    glassIcon(g, two ? x + 4 : x + 8, gy, OrbitalLayer.PLUS);
                    if (two) glassIcon(g, x + 4 + 7 + gap, gy, OrbitalLayer.MINUS);
                    if (kind.equals("lobenode")) {
                        g.setStroke(Color.web("#E6EEFA", .55));
                        g.setLineWidth(.8);
                        g.strokeLine(x + 7.5 + gap / 2 + 4, gy - 5, x + 7.5 + gap / 2 + 4, gy + 5);
                    }
                }
                case "balls" -> {
                    g.setStroke(Color.web("#A9B5C6", .9));
                    g.setLineWidth(1.6);
                    g.strokeLine(x + 2, gy + 2, x + 14, gy - 2);
                    for (double[] at : new double[][]{{x + 2, gy + 2}, {x + 14, gy - 2}}) {
                        g.setFill(new RadialGradient(0, 0, at[0] - .8, at[1] - .8, 2.8, false, CycleMethod.NO_CYCLE,
                                new Stop(0, Color.WHITE), new Stop(1, Color.web("#8E9AAD"))));
                        g.fillOval(at[0] - 2.4, at[1] - 2.4, 4.8, 4.8);
                    }
                }
                case "skeleton" -> {
                    g.setStroke(Color.web("#9FB3D1", .6));
                    g.setLineWidth(1);
                    g.strokeLine(x + 1, gy + 2, x + 15, gy - 2);
                    whiteNucleus(g, g, x + 1, gy + 2);
                    whiteNucleus(g, g, x + 15, gy - 2);
                }
                default -> {
                    g.setFill(DOTS);
                    for (int k = 0; k < 2; k++) g.fillOval(x + k * 4, gy - .8, 1.6, 1.6);
                    g.setFill(SQUEEZED);
                    for (int k = 0; k < 2; k++) g.fillOval(x + 11 + k * 4, gy - .8, 1.6, 1.6);
                }
            }
            g.setFill(Color.web("#B9CBE0", .78));
            g.fillText(I18n.text(lines.get(i)[1]), x + 24, y + i * step);
        }
    }

    /** Une barre de 1 Å à la profondeur de la molécule, alignée à droite en (right, y). */
    private void scaleBar(GraphicsContext g, double right, double y) {
        double[] p = camera.project(center[0], center[1], center[2]);
        if (p == null) return;
        double length = p[3];
        g.setStroke(Color.web("#E6EEFA", .85));
        g.setLineWidth(2);
        g.strokeLine(right - length, y, right, y);
        g.setFont(Font.font("Segoe UI", 11));
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web("#E6EEFA", .85));
        g.fillText("1 Å", right - length / 2, y - 6);
        g.setTextAlign(TextAlignment.LEFT);
    }

    // ================================================================== la souris et la géométrie
    /** Le pointeur est-il sur le titre de la légende ? */
    boolean onLegendTitle(double sx, double sy) {
        double[] r = legendTitle;
        return showLegend && r[2] > 0 && sx >= r[0] && sx <= r[0] + r[2] && sy >= r[1] && sy <= r[1] + r[3];
    }

    /** L'atome sous le pointeur (−1 : aucun). */
    int pick(double sx, double sy) {
        int best = -1;
        double bestDepth = Double.MAX_VALUE;
        for (int i = 0; i < positions.length; i++) {
            double[] p = camera.project(positions[i][0], positions[i][1], positions[i][2]);
            if (p == null) continue;
            double radius = Math.max(11, sphere(i) * p[3]);
            if (Math.hypot(sx - p[0], sy - p[1]) <= radius && p[2] < bestDepth) {
                best = i;
                bestDepth = p[2];
            }
        }
        return best;
    }

    /** Le point (Å) sous le pointeur, dans le plan face à la caméra qui passe par `through`. */
    double[] unproject(double sx, double sy, double[] through) {
        double[] ray = camera.ray(sx, sy), f = camera.forward();
        double denominator = ray[3] * f[0] + ray[4] * f[1] + ray[5] * f[2];
        if (Math.abs(denominator) < 1e-9) return null;
        double t = ((through[0] - ray[0]) * f[0] + (through[1] - ray[1]) * f[1] + (through[2] - ray[2]) * f[2]) / denominator;
        return new double[]{ray[0] + ray[3] * t, ray[1] + ray[4] * t, ray[2] + ray[5] * t};
    }

    double[] center() {
        return center.clone();
    }

    private static double sq(double x) {
        return x * x;
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double[] perpendicular(double[] u) {
        double[] t = Math.abs(u[0]) < .8 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
        double[] c = cross(u, t);
        double n = norm(c);
        return new double[]{c[0] / n, c[1] / n, c[2] / n};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[][] fibonacci(int count) {
        double[][] out = new double[count][];
        for (int k = 0; k < count; k++) {
            double h = 1 - 2 * (k + .5) / count, polar = Math.acos(h), azimuth = Math.PI * (1 + Math.sqrt(5)) * k;
            out[k] = new double[]{Math.cos(azimuth) * Math.sin(polar), Math.sin(azimuth) * Math.sin(polar), h};
        }
        return out;
    }

    /** La couleur CPK de l'élément, table de Jmol (Z = 1 à 86) : pour la palette de l'établi. */
    static Color elementColor(int z) {
        return z >= 1 && z <= JMOL.length ? Color.web("#" + JMOL[z - 1]) : Color.web("#D7B8FF");
    }
}
