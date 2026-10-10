package org.genesis;

import com.studio.genesis.grpc.CollisionBody;
import com.studio.genesis.grpc.CollisionRun;
import com.studio.genesis.grpc.CollisionSample;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.paint.Color;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import java.util.*;
import java.nio.IntBuffer;
import static org.genesis.FusionField.*;

/**
 * La scène de collision, dessinée avec les primitives de JavaFX (rendues par la carte graphique, à la résolution de
 * l'écran) en lumière additive. Tout ce qui est montré vient d'un calcul :
 * <ul>
 * <li>positions, vitesses, temps : les échantillons du moteur (trajectoire sous Coulomb + force nucléaire) ;</li>
 * <li>champ électrique : halos de densité d'énergie ε₀E_i²/2 sur quatre décades ; entre les noyaux, le potentiel combiné
 *     U = eφ (couleurs de chaleur, isopotentielles, cols chiffrés) ; repères mobiles entraînés par leurs charges ;</li>
 * <li>contact nucléaire : recouvrement des densités de Woods–Saxon ρ₁·ρ₂ ;</li>
 * <li>repères magnétiques mobiles : superposition des dipôles de spin, du champ des charges en mouvement et du champ externe ;</li>
 * <li>nuage de chaque noyau : densité de Woods–Saxon (diffusivité 0,55 fm) intégrée le long du regard ; autour,
 *     l'onde : paquet de largeur ƛ = ħ/p, rides de longueur d'onde de de Broglie locale h/p ;</li>
 * <li>effet tunnel : l'amplitude WKB à travers la barrière, puis le noyau réapparaît au contact.</li>
 * </ul>
 * Les distances et les tailles sont à la même échelle (aucun grossissement des noyaux).
 */
final class FusionRenderer {
    /** Ce que le moteur dit d'un corps en plus de sa position. */
    record Look(String color, double excitation, double spin, String kind, String name, double mass, boolean knownMoment) {
        Look(String color, double excitation, double spin, String kind, String name) {
            this(color, excitation, spin, kind, name, 0, false);
        }

        Look(String color, double excitation, double spin, String kind, String name, double mass) {
            this(color, excitation, spin, kind, name, mass, false);
        }
    }

    record Snapshot(double time, String phase, List<Body> bodies, double coulomb, double nuclear, double kinetic,
                    double potential, List<Look> looks) {
        Snapshot(double time, String phase, List<Body> bodies, double coulomb, double nuclear, double kinetic,
                 double potential) {
            this(time, phase, bodies, coulomb, nuclear, kinetic, potential, List.of());
        }

        Look look(int i) {
            return i < looks.size() ? looks.get(i) : null;
        }
    }

    /** Ce que l'image montre, pour les étiquettes et les chiffres posés par-dessus. */
    static final class Report {
        String stage = "ready";
        double local, time, conversion, separation, coulomb, strong, eta = Double.NaN, lambda = Double.NaN;
        double tunnelAmplitude = Double.NaN, pxPerFm, kinetic, potential;
        /** Chaleur dégagée par la friction pendant la soudure (MeV), calculée par le moteur. */
        double heat;
        /** La caméra observe encore le recul depuis le lieu de rupture, avant de suivre un fragment. */
        boolean recoil;
        /** L'échelle de la carte ε₀E²/2 (J/m³) : du transparent (bord du cadre) au blanc (pression au contact). */
        double pressureMin, pressureMax;
        /** Vitesse relative des deux noyaux (fraction de c), pendant l'approche, le rebond et la soudure ; 0 sinon. */
        double speed;
        /** Vrai quand la carte est à l'écran : les distances y sont comprimées, rien de ce qui s'y mesure n'est à l'échelle. */
        boolean map;
        /** Le facteur de compression de la carte (distance vraie / distance montrée du plus éloigné) ; 0 hors carte. */
        double mapCompression;
        final List<Tag> tags = new ArrayList<>();
        /** Les charges telles que dessinées (même géométrie que les halos) : ce que la sonde interroge. */
        final List<Source> sources = new ArrayList<>();
        /** Les cols du potentiel combiné (points de selle, E = 0) : x, y (px), U = eφ (MeV), indices des deux sources. */
        final List<double[]> saddles = new ArrayList<>();
        /** Le haut de l'échelle du potentiel (MeV) : le col quand les surfaces se touchent ; 0 sans carte. */
        double potentialTop;
        /** Le champ externe du tir (T) et la densité d'électrons libres du plasma (m⁻³) ; 0 : aucun. */
        double externalFieldT, electronDensityM3;
        /** La longueur de Debye appliquée aux champs (fm) ; ∞ : pas de plasma. */
        double debyeFm = Double.POSITIVE_INFINITY;
        /** Les traces d'ionisation visibles : Z, A, dE/dx (J/m), parcours E/(dE/dx) (m), énergie (MeV). */
        final List<double[]> tracks = new ArrayList<>();
        double[] spinSum;
        String spinText = "";
    }

    /**
     * Une charge à l'écran : centre (px), fm par pixel à sa profondeur, charge effective Z·α (α : son opacité pendant une
     * soudure), rayon (fm) ; le corps qu'elle représente.
     */
    record Source(double x, double y, double fmPerPx, double charge, double radius, Body body, Look look, double beta2) {}

    /** Un noyau à l'écran ; beta2 : sa déformation par le champ de l'autre (polarisation de Coulomb), 0 sinon. */
    record Tag(double x, double y, double radius, Body body, Look look, double alpha, double beta2, V offset) {}

    private static final class Actor {
        Body body;
        Look look;
        V pos;
        double alpha = 1, heat, squash = 1, sigma;
        V squashAxis = new V(1, 0, 0);
        /** Avant une fission : 0 → 1 jusqu'à la scission (col nul) ; avant une émission : la bosse où la particule sort. */
        double elong, bump;
        /** La déformation quadrupolaire imposée par le champ de l'autre noyau (négative : comprimé le long de l'axe). */
        double beta2;
        V elongAxis = new V(1, 0, 0), bumpDir = new V(1, 0, 0);
        V vel = ZERO;
        boolean ghost, followed;
        /** Sur la carte : l'écart vrai à l'élément créé (fm), avant compression ; nul ailleurs. */
        V mapOffset = ZERO;

        Actor(Body body, Look look, V pos) {
            this.body = body;
            this.look = look;
            this.pos = pos;
            vel = body.velocity();
        }
    }

    final LabCamera camera = new LabCamera();
    boolean showElectric = true, showMagnetic = true, showWave = true;
    /** La largeur (px) couverte à droite par un panneau (tableau de bord, bilan) : la scène se cadre dans le reste. */
    double insetRight;
    /** La longueur de Debye du plasma où a lieu le choc (fm) : les champs et le potentiel y sont écrantés ; ∞ : vide. */
    double debyeFm = Double.POSITIVE_INFINITY;
    /** La densité d'électrons libres du plasma traversé (m⁻³) : ce qui ionise sur le passage des produits ; 0 : vide. */
    double electronDensityM3;
    /** Le champ magnétique externe du tir (T), suivant +z : la trame de fond et les rayons de Larmor. */
    double externalFieldT;
    /** La légende des calques : seulement en lecture complète. */
    boolean legendWanted = true;
    /**
     * La carte : la scène elle-même, vue d'en haut au moment où les produits partent. Les corps restent ce qu'ils sont —
     * noyaux, halos, spins — mais leurs distances à l'élément créé (le centre, fixe) sont comprimées en échelle
     * logarithmique pour tenir dans un seul regard ; leurs directions sont celles du calcul. Le rendu l'enclenche de
     * lui-même un peu avant la première éjection (la vue recule d'avance) ; `mapEnabled` permet de l'éteindre.
     */
    boolean mapEnabled = true;
    /**
     * Le corps vers lequel la carte zoome : son (Z, A) et la direction d'où il vole (pour distinguer deux neutrons).
     * Cliquer ne déplace jamais le centre de la carte : la vue s'approche, c'est tout.
     */
    int mapFocusZ = -1, mapFocusA = -1;
    V mapFocusDir = ZERO;
    /** Constantes de la carte, tirées de l'état final montré pour que l'échelle ne bouge pas pendant la lecture. */
    private double mapFrom = Double.NaN, mapUnit = 2.5, mapOuter = 30, mapDref = 100;
    /** Le temps de réponse du ressort de la caméra (s) : une seconde pour entrer dans la carte ou en sortir. */
    private double springTime = .45;
    /** Les corps de la carte : le centre d'abord. Vide hors de l'état de carte. */
    private final List<Actor> mapped = new ArrayList<>();
    /** Le facteur de compression appliqué (distance vraie / distance montrée) au corps le plus éloigné ; 0 : aucune. */
    double mapCompression;
    double zoom = 1, extent = 40;
    /** Après la fusion, l'élément que la vue suit (Z, A) ; −1 : l'élément central créé (le plus lourd). */
    int focusZ = -1, focusA = -1;
    /** Horloge d'animation (s) : flux le long des lignes, rides de l'onde, agitation des nucléons. */
    double anim;
    final Report report = new Report();
    private GraphicsContext gc;
    private double width, height;
    private static final Map<Integer, String> ELEMENT_COLORS = new HashMap<>();
    private static final double FOV_TAN = Math.tan(Math.toRadians(22));
    private final Map<Integer, List<V>> nucleonGeometry = new HashMap<>();
    private final Map<Long, Stop[]> densityProfiles = new HashMap<>();
    private CollisionRun preparedRun;
    private CollisionTimeline preparedTimeline;
    private double reachApproach = 40, startSeparation = 1;
    // La carte ε₀E²/2 : une petite image (un quart de la résolution) que la carte graphique lisse en l'étirant.
    private WritableImage pressureImage;
    private PixelBuffer<IntBuffer> pressureBuffer;
    private int[] pressurePixels = new int[0];
    /** (Σ Z/R²)² des deux réactifs, en (k·e/fm²)² : la pression au contact, haut de l'échelle. */
    private double pressurePeak = 1;
    /** Secondes physiques par seconde de lecture, dans l'étape montrée (0 : étape sans durée). */
    private double physRate;
    private double externalField;
    private final List<double[]> emissions = new ArrayList<>();
    private WritableImage flashImage;
    private PixelBuffer<IntBuffer> flashBuffer;
    private int[] flashPixels = new int[0];
    // Le cadrage a de l'inertie : un saut de cadrage devient un écart qui se résorbe (ressort amorti).
    private boolean snapCamera = true;
    private double lastAnim = Double.NaN, lastFraction, goalLogReach = Double.NaN, offsetLog, offsetLogVel;
    private String lastTarget = "";
    private V goalAim = ZERO, offsetAim = ZERO, offsetAimVel = ZERO;

    FusionRenderer() {
        camera.yaw = -.55;
        camera.pitch = .34;
    }

    // ================================================================== données reçues
    static void registerColor(int z, String hex) {
        if (z > 0 && hex != null && hex.matches("#[0-9A-Fa-f]{6}")) ELEMENT_COLORS.put(z, hex);
    }

    static V vector(List<Double> values) {
        return values.size() >= 3 ? new V(values.get(0), values.get(1), values.get(2)) : ZERO;
    }

    static Body body(CollisionBody b) {
        return new Body(b.getZ(), b.getA(), b.getLabel(), vector(b.getPositionList()), vector(b.getVelocityList()),
                vector(b.getSpinList()), b.getA() == 0 ? 0 : Math.max(.84, b.getRadiusFm()), b.getMagneticMoment());
    }

    static Look look(CollisionBody b) {
        return new Look(b.getColorHex(), b.getExcitationMev(), spinValue(b.getSpinParity()), b.getKind(), b.getName(),
                b.getMassMev(), b.getKnownMoment());
    }

    static double spinValue(String spinParity) {
        String value = spinParity.replace("+", "").replace("-", "").replace("(", "").replace(")", "").trim();
        if (value.isEmpty() || value.equals("inconnu")) return 0;
        try {
            if (value.contains("/")) {
                String[] parts = value.split("/");
                return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1].split("[^0-9.]")[0]);
            }
            return Double.parseDouble(value.split("[^0-9.]")[0]);
        } catch (RuntimeException error) {
            return 0;
        }
    }

    static Snapshot snapshot(CollisionRun run, double time) {
        if (run.getSamplesCount() == 0) return new Snapshot(time, "ready", List.of(), 0, 0, 0, 0);
        int lo = 0, hi = run.getSamplesCount() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (run.getSamples(mid).getTimeS() <= time) lo = mid;
            else hi = mid - 1;
        }
        CollisionSample a = run.getSamples(lo), b = run.getSamples(Math.min(lo + 1, run.getSamplesCount() - 1));
        boolean same = a.getPhase().equals(b.getPhase()) && a.getBodiesCount() == b.getBodiesCount();
        for (int i = 0; same && i < a.getBodiesCount(); i++)
            same = a.getBodies(i).getZ() == b.getBodies(i).getZ() && a.getBodies(i).getA() == b.getBodies(i).getA();
        double t = same && b.getTimeS() > a.getTimeS()
                ? Math.max(0, Math.min(1, (time - a.getTimeS()) / (b.getTimeS() - a.getTimeS()))) : 0;
        List<Body> bodies = new ArrayList<>();
        List<Look> looks = new ArrayList<>();
        for (int i = 0; i < a.getBodiesCount(); i++) {
            Body x = body(a.getBodies(i));
            if (t > 0) {
                Body y = body(b.getBodies(i));
                V position = x.position().mul(1 - t).add(y.position().mul(t));
                V velocity = x.velocity().mul(1 - t).add(y.velocity().mul(t));
                if (a.getBodies(i).getVelocityCount() == 3 && b.getBodies(i).getVelocityCount() == 3) {
                    // Hermite : les tangentes sont les vitesses Python, converties de m/s en fm par intervalle.
                    // Aucun lissage à travers une scission, un changement d'identité ou une phase sans trajectoire.
                    double dtFm = (b.getTimeS() - a.getTimeS()) * 1e15, t2 = t * t, t3 = t2 * t;
                    position = x.position().mul(2 * t3 - 3 * t2 + 1).add(y.position().mul(-2 * t3 + 3 * t2))
                            .add(x.velocity().mul((t3 - 2 * t2 + t) * dtFm)).add(y.velocity().mul((t3 - t2) * dtFm));
                    velocity = x.position().sub(y.position()).mul((6 * t2 - 6 * t) / dtFm)
                            .add(x.velocity().mul(3 * t2 - 4 * t + 1)).add(y.velocity().mul(3 * t2 - 2 * t));
                }
                x = new Body(x.z(), x.a(), x.label(), position, velocity, x.spin().mul(1 - t).add(y.spin().mul(t)).unit(),
                        x.radius(), x.moment());
            }
            bodies.add(x);
            looks.add(look(a.getBodies(i)));
        }
        return new Snapshot(time, a.getPhase(), bodies, lerp(a.getCoulombN(), b.getCoulombN(), t),
                lerp(a.getNuclearN(), b.getNuclearN(), t), lerp(a.getKineticMev(), b.getKineticMev(), t),
                lerp(a.getPotentialMev(), b.getPotentialMev(), t), looks);
    }

    /** L'état d'un échantillon précis (deux échantillons peuvent porter le même temps physique). */
    static Snapshot sample(CollisionRun run, int index) {
        CollisionSample a = run.getSamples(Math.max(0, Math.min(run.getSamplesCount() - 1, index)));
        List<Body> bodies = new ArrayList<>();
        List<Look> looks = new ArrayList<>();
        for (CollisionBody b : a.getBodiesList()) {
            bodies.add(body(b));
            looks.add(look(b));
        }
        return new Snapshot(a.getTimeS(), a.getPhase(), bodies, a.getCoulombN(), a.getNuclearN(), a.getKineticMev(),
                a.getPotentialMev(), looks);
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * La couleur d'un noyau : celle de son élément (convention CPK/Jmol, éclaircie pour l'écran), nuancée par
     * l'isotope — vers le bleu s'il est riche en neutrons, vers l'ambre s'il en manque.
     */
    static Color bodyColor(int z, int a) {
        if (z == 0) return Color.web(a == 0 ? "#FFF2AE" : "#C1D2ED");
        String hex = ELEMENT_COLORS.get(z);
        Color base;
        if (hex != null) base = brighten(Color.web(hex));
        else {
            double hue = z == 1 ? 28 : z == 2 ? 190 : (z * 137.507764 + 35) % 360;
            base = Color.hsb((hue + 360) % 360, .62, .98);
        }
        double excess = a > 0 ? (a - 2.0 * z) / a : 0;
        Color tint = excess >= 0 ? Color.web("#8FB2FF") : Color.web("#FFB66E");
        return base.interpolate(tint, Math.min(.42, Math.abs(excess) * 1.1));
    }

    private static Color brighten(Color color) {
        double light = .3 * color.getRed() + .59 * color.getGreen() + .11 * color.getBlue();
        return light < .62 ? color.interpolate(Color.WHITE, .62 - light) : color;
    }

    static double[] spectrum(int z, int a) {
        Color c = bodyColor(z, a);
        return new double[]{c.getRed(), c.getGreen(), c.getBlue()};
    }

    /** Échelle réelle : aucun grossissement. */
    double nuclearMagnification() {
        return 1;
    }

    double displayRadius(Body b) {
        return b.radius();
    }

    double[] project(V p) {
        return camera.project(p.x(), p.y(), p.z());
    }

    // ================================================================== préparation d'un calcul
    private void prepare(CollisionRun run, CollisionTimeline timeline) {
        if (run == preparedRun && timeline == preparedTimeline) return;
        preparedRun = run;
        preparedTimeline = timeline;
        tracers.clear();
        tracerClock = Double.NaN;
        snapCamera = true;
        reachApproach = 15;
        CollisionSample first = run.getSamples(0);
        for (CollisionBody b : first.getBodiesList()) {
            V p = vector(b.getPositionList());
            reachApproach = Math.max(reachApproach, p.norm() + 1.3 * b.getRadiusFm());
        }
        startSeparation = Math.max(1, separation(sample(run, 0).bodies()));
        pressurePeak = peakOf(sample(run, 0).bodies());
        // Repérer chaque naissance de produits ; seul un photon gamma porte un éclat radiatif.
        emissions.clear();
        for (int i = 1; i < run.getSamplesCount(); i++) {
            CollisionSample a = run.getSamples(i - 1), b = run.getSamples(i);
            String ph = b.getPhase();
            if (!(ph.equals("evaporation") || ph.equals("products") || ph.equals("compound"))) continue;
            if (!isEmission(a, b)) continue;
            CollisionBody last = b.getBodies(b.getBodiesCount() - 1), other = b.getBodies(b.getBodiesCount() - 2);
            CollisionBody light = last.getA() <= other.getA() ? last : other;
            V at = vector(last.getPositionList());
            double[] c = light.getA() == 0 ? new double[]{1, .9, .55} : light.getZ() == 0 ? new double[]{.72, .84, 1}
                    : light.getA() <= 4 ? new double[]{1, .74, .42} : new double[]{1, .95, .86};
            double fastest = 0, released = 0;
            for (CollisionBody x : b.getBodiesList()) {
                V v = vector(x.getVelocityList());
                if (x.getMassMev() > 0) fastest = Math.max(fastest, v.norm() / C);
                released += x.getKineticMev();
            }
            V lv = vector(light.getVelocityList()), out = lv.norm() > 0 ? lv.unit()
                    : vector(light.getPositionList()).sub(vector((light == last ? other : last).getPositionList())).unit();
            boolean fission = Math.min(last.getA(), other.getA()) >= 20;
            if (a.getPhase().equals("contact")) {
                double m1 = Math.max(1, last.getMassMev()), m2 = Math.max(1, other.getMassMev());
                at = vector(last.getPositionList()).mul(m1 / (m1 + m2))
                        .add(vector(other.getPositionList()).mul(m2 / (m1 + m2)));
            }
            V axis = fission ? vector(last.getPositionList()).sub(vector(other.getPositionList())).unit() : out;
            // Seule l'énergie d'un photon gamma réellement émis peut alimenter l'éclat radiatif.
            double gammaMev = "gamma".equals(light.getKind()) ? Math.max(0, light.getKineticMev()) : 0;
            emissions.add(new double[]{timeline.fractionAt(b.getTimeS()), at.x(), at.y(), at.z(), c[0], c[1], c[2],
                    light.getA() > 4 ? 1.8 : 1, b.getTimeS(), fastest > 0 ? fastest : 1, released,
                    axis.x(), axis.y(), axis.z(), fission ? 1 : 0, last.getA() + other.getA(), last.getZ() + other.getZ(), b.getBodiesCount(),
                    gammaMev, Math.max(1, other.getRadiusFm())});
        }
        // La carte : ses constantes viennent de l'état final montré. Elle s'enclenche un peu avant la première éjection.
        mapFrom = Double.NaN;
        CollisionTimeline.Stage finalStage = null;
        for (CollisionTimeline.Stage st : timeline.stages())
            if (st.kind() == CollisionTimeline.Kind.SAMPLES && st.name().equals("products")) finalStage = st;
        if (finalStage != null) {
            CollisionSample fin = run.getSamples(finalStage.last());
            CollisionBody heavy = null;
            for (CollisionBody b : fin.getBodiesList())
                if (b.getZ() > 0 && b.getA() > 0 && (heavy == null || b.getA() > heavy.getA())) heavy = b;
            if (heavy != null) {
                V centre = vector(heavy.getPositionList());
                double unit = 2.5, farthest = 0;
                for (CollisionBody b : fin.getBodiesList()) {
                    if (b.getA() > 0) unit = Math.max(unit, b.getRadiusFm());
                    farthest = Math.max(farthest, vector(b.getPositionList()).sub(centre).norm());
                }
                mapUnit = unit;
                mapDref = Math.max(farthest, 1);
                mapOuter = Math.min(11 * unit, Math.max(mapDref, 3 * unit));
                double firstEjection = emissions.isEmpty() ? finalStage.start() : Math.min(emissions.get(0)[0], finalStage.start());
                mapFrom = Math.max(0, firstEjection - .6 / Math.max(1, timeline.durationSeconds()));
            }
        }
    }

    /**
     * La distance montrée d'un corps parti à la distance vraie d de l'élément créé : r = R·ln(1 + d/d₀)/ln(1 + D/d₀),
     * D étant la plus grande distance de l'état final et R le rayon de la carte. Presque linéaire près du noyau (d ≪ d₀ :
     * l'échelle est vraie), logarithmique au loin ; croissante, donc le plus vite est toujours le plus loin.
     */
    static double mapRadius(double d, double d0, double dref, double outer) {
        return outer * Math.log1p(d / d0) / Math.log1p(Math.max(dref, 1e-3 * d0) / d0);
    }

    /** Une scission peut remplacer deux noyaux par deux fragments : le nombre de corps ne suffit pas. */
    static boolean isEmission(CollisionSample before, CollisionSample after) {
        if (before.getBodiesCount() == 0) return false;
        if (after.getBodiesCount() > before.getBodiesCount()) return true;
        if (after.getBodiesCount() < 2 || after.getBodiesCount() != before.getBodiesCount()) return false;
        if (before.getPhase().equals("contact")) return true;
        for (int i = 0; i < after.getBodiesCount(); i++) {
            CollisionBody a = before.getBodies(i), b = after.getBodies(i);
            if (a.getZ() != b.getZ() || a.getA() != b.getA()) return true;
        }
        return false;
    }

    /** (Σ Z/R²)² : le champ des deux noyaux qui se touchent, à leur surface, quand il s'ajoute (en k·e/fm²). */
    private static double peakOf(List<Body> bodies) {
        double sum = 0;
        for (Body b : bodies) if (b.z() > 0 && b.a() > 0) sum += b.z() / Math.pow(Math.max(.5, b.radius()), 2);
        return Math.max(1e-6, sum * sum);
    }

    // ================================================================== le dessin
    /** Les deux noyaux choisis, avant le tir. */
    void paintPortrait(GraphicsContext g, double w, double h, Snapshot s, double fieldT) {
        externalField = externalFieldT = fieldT;
        begin(g, w, h);
        double reach = 15;
        for (Body b : s.bodies()) reach = Math.max(reach, b.position().norm() + 2.6 * b.radius());
        pressurePeak = peakOf(s.bodies());
        physRate = 0;
        snapCamera = true;
        setupCamera(reach, ZERO);
        background();
        externalFieldGrid();
        List<Actor> actors = new ArrayList<>();
        for (int i = 0; i < s.bodies().size(); i++) actors.add(new Actor(s.bodies().get(i), s.look(i), s.bodies().get(i).position()));
        report.stage = "ready";
        drawScene(actors, 0, null, null, 0, 1);
        end();
    }

    /** Un instant du calcul : `fraction` de la lecture. */
    void paint(GraphicsContext g, double w, double h, CollisionRun run, CollisionTimeline timeline, double fraction) {
        begin(g, w, h);
        prepare(run, timeline);
        externalField = externalFieldT = run.getFieldT();
        if (fraction < lastFraction || Math.abs(fraction - lastFraction) > .1) tracers.clear();
        CollisionTimeline.At at = timeline.at(fraction);
        CollisionTimeline.Stage stage = at.stage();
        double u = at.local();
        report.stage = stage.name();
        report.local = u;
        report.time = at.time();
        Snapshot pre = timeline.lastApproach() >= 0 ? sample(run, timeline.lastApproach()) : null;
        // La carte s'enclenche juste avant la première éjection, et reste jusqu'au bout.
        boolean mapNow = mapEnabled && !Double.isNaN(mapFrom) && fraction >= mapFrom;
        List<Actor> actors = new ArrayList<>();
        double conversion = 0, strong = 0, tunnelFront = -1, tunnelAlpha = 0, reach;
        V aim = ZERO;
        String target0 = stage.name();
        Actor projectile = null, target = null;
        Snapshot state;
        if (stage.kind() == CollisionTimeline.Kind.SAMPLES) {
            state = snapshot(run, at.time());
            for (int i = 0; i < state.bodies().size(); i++) {
                Body b = state.bodies().get(i);
                actors.add(new Actor(b, state.look(i), b.position()));
            }
            if (state.bodies().size() == 2) report.speed = state.bodies().get(0).velocity().sub(state.bodies().get(1).velocity()).norm() / C;
            if (state.bodies().size() == 2 && (stage.name().equals("approach") || stage.name().equals("scattered"))) {
                double total = Math.max(1e-12, state.kinetic() + state.potential());
                conversion = Math.max(0, Math.min(1, state.potential() / total));
                double coulomb = Math.max(state.coulomb(), 1e-30);
                strong = Math.min(1, Math.abs(state.nuclear()) / coulomb);
                report.kinetic = state.kinetic();
                report.potential = state.potential();
                // Sous la barrière, près du point de rebroussement : l'onde s'enfonce déjà dans le mur.
                double turn = pre == null ? 0 : separation(pre.bodies());
                double sep = separation(state.bodies());
                if (run.getEnergyKev() / 1000 < run.getBarrierMev() && sep < turn * 1.3 && turn > 0) {
                    tunnelFront = 1;
                    tunnelAlpha = .55 * (1 - (sep - turn) / (.3 * turn));
                }
                // Le zoom reste plus lent que l'approche (∝ r^0,35) pour que le freinage calculé reste perceptible.
                // l'un vers l'autre et freiner, et l'on arrive au plus près pour le contact.
                // La polarisation de Coulomb : chaque noyau, goutte liquide, est comprimé le long de l'axe par le champ de l'autre.
                for (int i = 0; i < 2; i++) {
                    Actor self = actors.get(i), other = actors.get(1 - i);
                    if (self.body.a() < 2) continue;
                    self.beta2 = coulombBeta2(self.body.z(), self.body.a(), other.body.z(), sep);
                    self.squash = 1 + .631 * self.beta2;
                    self.squashAxis = other.pos.sub(self.pos).unit();
                }
                // Le cadre se resserre de la distance de départ au point de rebroussement (ou au contact), en log, et
                // atteint le cadrage serré exactement là où le noyau s'arrête. Sous la barrière ce point peut être à des
                // centaines de femtomètres : un cadre figé à 2,5 fois cette distance laissait les noyaux minuscules.
                // Plancher : les deux noyaux restent dans le cadre à toute distance (0,62·d + 1,2·R, comme au rebroussement).
                double turnOrContact = Math.max(turn, run.getContactFm()), close = closeReach(pre, run);
                double wide = Math.max(reachApproach, close * 1.0001), span = Math.log(wide / close);
                double ratio = Math.max(0, Math.min(1, (sep - turnOrContact) / Math.max(1e-9, startSeparation - turnOrContact)));
                // L'exposant borne la pente : d ln(cadre) / d ln(distance) ≤ 0,8 < 1. Le zoom reste toujours plus lent que le
                // rapprochement, donc les noyaux se rapprochent bel et bien à l'écran (jamais un zoom qui les écarte).
                double power = Math.min(.5, .8 / Math.max(span, 1e-9));
                reach = Math.max(.62 * sep + 1.2 * maxRadius(actors), close * Math.exp(span * Math.pow(ratio, power)));
            } else if (state.bodies().size() == 2 && stage.name().equals("merge")) {
                // La soudure calculée : les deux noyaux tombent l'un dans l'autre (Coulomb + Woods–Saxon), freinés par la
                // friction nucléaire qui change leur élan en chaleur ; quand ils se recouvrent, le noyau composé apparaît.
                double r = separation(state.bodies()), overlap = Math.max(0, Math.min(1, 1 - r / run.getContactFm()));
                double weld = timeline.phaseStart("relax") >= 0 ? 0 : smooth((overlap - .6) / .38);
                for (Actor a : actors) a.alpha = 1 - weld;
                Actor compound = compoundActor(run, timeline);
                compound.alpha = weld;
                if (weld > .01) actors.add(compound);
                actors.removeIf(a -> a.alpha < .01);
                for (int i = 0; i < 2; i++) report.heat += state.look(i) == null ? 0 : state.look(i).excitation();
                conversion = 1 - smooth((overlap - .3) / .6);
                strong = Math.min(1, Math.abs(state.nuclear()) / Math.max(state.coulomb(), 1e-30));
                report.kinetic = state.kinetic();
                report.potential = state.potential();
                reach = ease(Math.min(1, u / .6), entryReach(pre, run), mergeReach(run));
            } else if (stage.name().equals("contact")) {
                double total = Math.max(1e-12, state.kinetic() + state.potential());
                conversion = Math.max(0, Math.min(1, state.potential() / total));
                strong = Math.min(1, Math.abs(state.nuclear()) / Math.max(Math.abs(state.coulomb()), 1e-30));
                report.kinetic = state.kinetic();
                report.potential = state.potential();
                reach = Math.max(2.4 * maxRadius(actors), 1.2 * separation(state.bodies()));
                reach = ease(Math.min(1, u / .3), entryReach(pre, run), reach);
            } else {
                // Une fois la fusion faite, la vue ne présente que l'élément central créé (le plus lourd) — ou celui choisi
                // dans le bilan : la caméra glisse sur lui (son spin, ses champs) ; les autres sont projetés hors du cadre.
                Actor heavy = null, chosen = null;
                for (Actor a : actors) if (a.body.z() > 0 && (heavy == null || a.body.a() > heavy.body.a())) heavy = a;
                for (Actor a : actors) if (chosen == null && a.body.z() == focusZ && a.body.a() == focusA) chosen = a;
                Actor shown = chosen != null ? chosen : heavy;
                if (shown != null) shown.followed = true;
                reach = mergeReach(run);
                target0 = "decay";
                if (shown != null) {
                    reach = Math.max(4.6 * shown.body.radius(), 9);
                    aim = shown.pos;
                    target0 = chosen != null ? "focus:" + focusZ + ":" + focusA : "central:" + shown.body.z();
                }
                // Après la rupture, la vue tient d'abord le barycentre (immobile : Σp = 0) et recule assez pour garder
                // tous les fragments ; on y voit leurs routes diverger — chacune courbée par le champ selon son p/qB.
                // Puis elle se referme sur l'élément créé (ou celui choisi dans le bilan).
                if (mapNow && heavy != null) {
                    // La carte : chaque corps garde sa direction calculée, sa distance est comprimée (log) pour tenir dans le
                    // cadre. Le centre — l'élément créé — ne bouge plus : c'est le point fixe de la lecture, quoi qu'on clique.
                    V middle = heavy.pos;
                    double d0 = 2.2 * mapUnit;
                    for (Actor a : actors) {
                        V offset = a.pos.sub(middle);
                        a.mapOffset = offset;
                        double d = offset.norm();
                        a.pos = d < 1e-9 ? ZERO : offset.unit().mul(mapRadius(d, d0, mapDref, mapOuter));
                        a.vel = ZERO;
                    }
                    mapped.add(heavy);
                    for (Actor a : actors) if (a != heavy) mapped.add(a);
                    mapCompression = mapDref / mapOuter;
                    report.mapCompression = mapCompression;
                    report.map = true;
                    heavy.followed = false;
                    Actor focused = mapFocused(actors, heavy);
                    if (focused != null) {
                        // Un corps choisi : la vue s'en approche (le centre de la carte, lui, reste où il est).
                        aim = focused.pos;
                        reach = Math.max(3.4 * focused.body.radius(), 5);
                        target0 = "map:" + focused.body.z() + ":" + focused.body.a();
                    } else {
                        aim = ZERO;
                        reach = mapOuter * 1.3;
                        target0 = "map";
                    }
                } else if (stage.name().equals("products") && shown != null && focusZ < 0) {
                    // L'écart des fragments (A ≥ 4) : les particules légères, déjà au bord du cadre, n'écartent pas la vue.
                    double spread = 0;
                    for (Actor a : actors) if (a.body.a() >= 4 && a.body.z() > 0 && a.alpha > .3) spread = Math.max(spread, a.pos.norm() + a.body.radius());
                    // La vue barycentrique tient tant que les fragments restent dans un cadre lisible (14 rayons) ;
                    // dès qu'ils le dépassent — rien ne les freine dans le vide — elle se referme sur l'élément suivi.
                    double far = 22 * shown.body.radius();
                    double settle = Math.max(smooth((u - .62) / .3), smooth(2 * spread / far - 1));
                    aim = shown.pos.mul(settle);
                    reach = ease(settle, Math.max(reach, Math.min(1.25 * spread, far)), reach);
                    target0 = "products";
                }
            }
        } else {
            boolean melt = stage.name().equals("relax");
            Snapshot base = stage.kind() == CollisionTimeline.Kind.MERGE ? sample(run, stage.first()) : pre;
            state = base;
            List<Body> pair = base.bodies();
            int p = pair.get(0).position().norm() >= pair.get(1).position().norm() ? 0 : 1;
            Body pb = pair.get(p), tb = pair.get(1 - p);
            Look pl = base.look(p), tl = base.look(1 - p);
            double mp = mass(pl, pb), mt = mass(tl, tb), m = mp + mt;
            V dir = pb.position().sub(tb.position()).unit();
            double contact = run.getContactFm();
            V pContact = dir.mul(contact * mt / m), tContact = dir.mul(-contact * mp / m);
            int exit = timeline.lastApproach() + 1;
            if (exit < run.getSamplesCount() && run.getSamples(exit).getPhase().equals("merge")) {
                // La sortie du tunnel calculée : là où le mouvement redevient permis (V(r) = E).
                Snapshot out = sample(run, exit);
                pContact = out.bodies().get(p).position();
                tContact = out.bodies().get(1 - p).position();
            }
            double close = closeReach(pre, run), entry = entryReach(pre, run);
            if (stage.kind() == CollisionTimeline.Kind.TUNNEL) {
                conversion = 1;
                // Pendant le passage, la vue descend du rebroussement vers le contact, là où le noyau va réapparaître.
                reach = ease(Math.max(0, Math.min(1, (u - .08) / .6)), close, entry);
                projectile = new Actor(pb, pl, pb.position());
                target = new Actor(tb, tl, tb.position());
                projectile.vel = ZERO;
                target.vel = ZERO;
                projectile.squash = 1 - .12 * smooth(u / .3) * (1 - smooth((u - .45) / .3));
                projectile.squashAxis = dir;
                tunnelFront = Math.min(1, u / .3);
                tunnelAlpha = 1 - smooth((u - .7) / .3);
                actors.add(target);
                // La probabilité passe d'un côté du mur à l'autre : l'onde s'efface ici et se matérialise là-bas.
                double v = smooth((u - .25) / .6);
                if (v > 0) {
                    projectile.alpha = 1 - v;
                    target.pos = tb.position().add(tContact.sub(tb.position()).mul(v));
                    Actor ghost = new Actor(pb, pl, pContact);
                    ghost.alpha = v;
                    ghost.ghost = v < .999;
                    ghost.vel = ZERO;
                    actors.add(ghost);
                    strong = smooth((u - .6) / .4);
                }
                if (projectile.alpha > .01) actors.add(projectile);
            } else {
                // La fonte des formes (noyaux lourds : la soudure calculée s'arrête au contact, la suite n'est pas tracée)
                // ou, pour un ancien calcul, la soudure mise en scène.
                double s0 = melt ? separation(pair) : contact;
                reach = melt ? mergeReach(run) : ease(Math.min(1, u / .5), entry, mergeReach(run));
                conversion = (melt ? .7 : 1) * (1 - smooth(u / .8));
                strong = 1;
                double s = s0 * (1 - smooth(u / .85));
                projectile = new Actor(pb, pl, dir.mul(s * mt / m));
                target = new Actor(tb, tl, dir.mul(-s * mp / m));
                double fade = 1 - smooth((u - .45) / .4);
                projectile.alpha = target.alpha = fade;
                projectile.heat = target.heat = Math.min(1, .4 + u);
                projectile.vel = target.vel = ZERO;
                if (fade > .01) {
                    actors.add(target);
                    actors.add(projectile);
                }
                for (int i = 0; i < 2; i++) report.heat += base.look(i) == null ? 0 : base.look(i).excitation();
                Actor compound = compoundActor(run, timeline);
                compound.alpha = smooth((u - .35) / .45);
                if (compound.alpha > .01) actors.add(compound);
            }
        }
        if (mapNow && !report.map) {
            // Juste avant la première éjection : la vue recule d'avance sur toute la carte — quand les corps partiront,
            // ils seront déjà dans le cadre, au lieu de filer d'un écran vide.
            aim = ZERO;
            reach = mapOuter * 1.3;
            target0 = "map";
        }
        report.conversion = conversion;
        double sep = actors.size() >= 2 ? actors.get(0).pos.sub(actors.get(1).pos).norm() : 0;
        report.separation = stage.kind() == CollisionTimeline.Kind.SAMPLES && state != null && state.bodies().size() == 2
                ? separation(state.bodies()) : sep;
        physRate = stage.kind() == CollisionTimeline.Kind.SAMPLES && stage.seconds() > 0
                ? (run.getSamples(stage.last()).getTimeS() - run.getSamples(stage.first()).getTimeS()) / stage.seconds() : 0;
        // Au premier départ et aux fissions, observer le lieu de rupture avant de suivre un fragment.
        // Les corps gardent leurs positions et vitesses calculées ; seul le cadrage est différé.
        for (int i = mapNow ? -1 : emissions.size() - 1; i >= 0; i--) {
            double[] e = emissions.get(i);
            if (i != 0 && e[14] <= 0) continue;
            double age = (fraction - e[0]) * timeline.durationSeconds();
            if (age < 0 || age >= 1.8) continue;
            double track = smooth((age - 1.0) / .8);
            V origin = new V(e[1], e[2], e[3]);
            // Le barycentre ne bouge pas (Σp = 0 dans le centre de masse) : la vue le garde et recule pour contenir les
            // fragments, au lieu de se coller tout de suite au plus lourd.
            double spread = 0;
            for (Actor a : actors) if (a.body.a() >= 4 && a.body.z() > 0 && a.alpha > .3)
                spread = Math.max(spread, Math.min(a.pos.sub(origin).norm(), 10 * a.body.radius()) + a.body.radius());
            aim = origin.mul(1 - track).add(aim.mul(track));
            reach = lerp(Math.max(mergeReach(run), 1.35 * spread), reach, track);
            target0 = "recoil:" + e[8];
            report.recoil = true;
            for (Actor a : actors) a.followed = false;
            break;
        }
        follow(reach, aim, target0, fraction);
        background();
        externalFieldGrid();
        // Les ondes (paquets) : ƛ = 1/k du mouvement relatif, à l'infini ; partagé selon les masses.
        double energy = run.getEnergyKev() / 1000, mu = Math.max(run.getReducedMassMev(), 1);
        double reduced = 197.3269804 / Math.sqrt(2 * mu * Math.max(energy, 1e-9));
        boolean pairStage = stage.name().equals("approach") || stage.name().equals("scattered") || stage.name().equals("merge")
                && stage.kind() == CollisionTimeline.Kind.SAMPLES || stage.kind() == CollisionTimeline.Kind.TUNNEL;
        if (pairStage && state != null && state.bodies().size() == 2) {
            double m0 = mass(state.look(0), state.bodies().get(0)), m1 = mass(state.look(1), state.bodies().get(1));
            for (Actor a : actors) {
                double own = a.body == state.bodies().get(0) ? m0 : m1;
                a.sigma = reduced * (m0 + m1 - own) / (m0 + m1);
            }
            double beta = Math.sqrt(2 * energy / mu);
            report.eta = state.bodies().get(0).z() * state.bodies().get(1).z() / 137.035999 / Math.max(beta, 1e-12);
            V rel = state.bodies().get(0).velocity().sub(state.bodies().get(1).velocity());
            double p = mu * rel.norm() / C;
            report.lambda = p > 1e-9 ? 2 * Math.PI * 197.3269804 / p : Double.POSITIVE_INFINITY;
        } else {
            report.eta = Double.NaN;
            report.lambda = Double.NaN;
            for (Actor a : actors) {
                double m = a.look == null ? 0 : a.look.mass();
                double beta = a.vel.norm() / C;
                if (m > 0 && beta > 1e-4) {
                    double p = m * beta / Math.sqrt(Math.max(1e-12, 1 - beta * beta));
                    a.sigma = 197.3269804 / p;
                }
            }
        }
        // La compression : la force de Coulomb à la distance montrée, la force nucléaire de Woods–Saxon.
        double coulomb = 0;
        if (actors.size() >= 2 && actors.get(0).body.z() > 0 && actors.get(1).body.z() > 0) {
            double r = Math.max(report.separation, run.getContactFm());
            coulomb = 1.43996 * actors.get(0).body.z() * actors.get(1).body.z() / (r * r) * 160.2176634;
        }
        report.coulomb = coulomb;
        double diffuse = .63, f = 1 / (1 + Math.exp((report.separation - run.getContactFm()) / diffuse));
        double nuclear = run.getDepthMev() / diffuse * f * (1 - f) * 160.2176634;
        report.strong = stage.kind() == CollisionTimeline.Kind.SAMPLES && state != null && state.bodies().size() == 2
                && !stage.name().equals("contact") ? Math.abs(state.nuclear()) : nuclear * Math.max(strong, .001);
        for (double[] e : emissions) {
            if (e.length < 17) continue;
            double ahead = (e[0] - fraction) * timeline.durationSeconds(), window = e[14] > 0 ? .7 : .3;
            if (ahead <= 0 || ahead > window) continue;
            for (Actor a : actors) {
                if (a.body.a() != (int) e[15] || a.body.z() != (int) e[16]) continue;
                double k = 1 - ahead / window;
                if (e[14] > 0) {
                    a.elong = k;
                    a.elongAxis = new V(e[11], e[12], e[13]);
                } else {
                    a.bump = k;
                    a.bumpDir = new V(e[11], e[12], e[13]);
                }
            }
        }
        drawScene(actors, conversion, projectile, target, strong, 1);
        if (tunnelFront >= 0 && tunnelAlpha > .01 && showWave) tunnelTail(run, actors, tunnelFront, tunnelAlpha);
        if (!report.map && (stage.name().equals("products") || stage.name().equals("evaporation"))) ionisationTracks(run, timeline, fraction, stage);
        drawRadiativeFlash(timeline, fraction);
        boolean welding = stage.kind() == CollisionTimeline.Kind.MERGE || stage.name().equals("merge") && timeline.phaseStart("relax") < 0;
        if (welding && u > .55 || stage.name().equals("compound") && u < .75) spinAddition(run, timeline, pre, stage, u);
        end();
    }

    /**
     * Le corps de la carte vers lequel la vue s'approche : celui qu'on a cliqué (même (Z, A), et le plus proche de la
     * direction mémorisée — deux neutrons ne se confondent pas), ou celui choisi dans le bilan. Null : la carte entière.
     */
    private Actor mapFocused(List<Actor> actors, Actor centre) {
        boolean clicked = mapFocusZ >= 0;
        int z = clicked ? mapFocusZ : focusZ, a = clicked ? mapFocusA : focusA;
        if (z < 0) return null;
        Actor best = null;
        double bestDot = -3;
        for (Actor x : actors) {
            if (x.body.z() != z || x.body.a() != a || x.alpha < .3) continue;
            double dot = x == centre ? 2 : clicked && x.mapOffset.norm() > 1e-9 && mapFocusDir.norm() > 1e-9
                    ? x.mapOffset.unit().dot(mapFocusDir) : 0;
            if (dot > bestDot) {
                bestDot = dot;
                best = x;
            }
        }
        return best;
    }

    private double mergeReach(CollisionRun run) {
        int a = Math.max(1, run.getCompoundA());
        double radius = Math.sqrt(5 / 3.0) * (.94 * Math.cbrt(a) + .4);
        return Math.max(2.8 * radius, run.getContactFm() * 1.3);
    }

    /**
     * La polarisation de Coulomb d'un noyau (goutte liquide de Bohr–Mottelson) dans le champ d'un autre de charge Z′ à la
     * distance d : énergie quadrupolaire W = k·Z′e·Q₀/(2d³) avec Q₀ = (3/√5π)·Z·R₀²·β₂, contre la raideur
     * C₂ = 4R₀²σ − (3/10π)·Z²e²/R₀. À l'équilibre : β₂ = −k·Z′·(3/√5π)·Z·R₀² / (2d³·C₂) < 0 — deux charges qui se
     * repoussent se compriment l'une l'autre le long de l'axe (marée inversée). Tritium face à l'uranium : −0,006 ; fer face
     * au plomb à 13 fm : −0,19. Bornée à −0,3 (au-delà, la réponse n'est plus linéaire). Valeur statique (adiabatique) :
     * un majorant pour un choc rapide.
     */
    static double coulombBeta2(int z, int a, int zOther, double d) {
        double r0 = 1.2 * Math.cbrt(Math.max(1, a)), sigma = 17.23 / (4 * Math.PI * 1.44);
        double stiff = Math.max(.5, 4 * r0 * r0 * sigma - 3 / (10 * Math.PI) * z * z * 1.43996 / r0);
        double beta2 = -1.43996 * zOther * (3 / Math.sqrt(5 * Math.PI)) * z * r0 * r0 / (2 * Math.pow(Math.max(d, 1e-3), 3) * stiff);
        return Math.max(-.3, beta2);
    }

    /**
     * Le cadrage d'entrée du tunnel et de la soudure : serré sur le contact. Sous la barrière, le rebroussement est loin
     * (des centaines de fm) mais le noyau réapparaît au contact — il faut y être, pas rester au large.
     */
    private static double entryReach(Snapshot pre, CollisionRun run) {
        double r = pre == null || pre.bodies().size() < 2 ? 2 : Math.max(pre.bodies().get(0).radius(), pre.bodies().get(1).radius());
        return Math.min(closeReach(pre, run), Math.max(3.2 * r, 2.4 * run.getContactFm()));
    }

    /** Le cadrage serré autour du point de rebroussement : les deux noyaux et l'espace qui les sépare. */
    private static double closeReach(Snapshot pre, CollisionRun run) {
        if (pre == null || pre.bodies().size() < 2) return Math.max(8, 1.5 * run.getContactFm());
        double r = Math.max(pre.bodies().get(0).radius(), pre.bodies().get(1).radius());
        return Math.max(.62 * separation(pre.bodies()) + 1.2 * r, 3 * r);
    }

    /**
     * L'inertie du cadrage. Tant que la cible reste la même (un noyau qu'on accompagne, même lancé), la caméra la
     * suit exactement ; quand elle change (étape suivante, autre noyau suivi, saut dans la lecture), l'écart entre
     * l'ancien et le nouveau cadre est résorbé par un ressort amorti critique : la vue glisse au lieu de sauter.
     */
    private void follow(double reach, V aim, String targetKey, double fraction) {
        double dt = Double.isNaN(lastAnim) ? 0 : anim - lastAnim;
        lastAnim = anim;
        double logReach = Math.log(reach);
        boolean changed = !targetKey.equals(lastTarget) || Math.abs(fraction - lastFraction) > .03;
        if (changed) {
            // Entrer dans la carte ou en sortir est un grand mouvement : il se fait en une seconde, sans à-coup.
            boolean fromMap = lastTarget.startsWith("map"), toMap = targetKey.startsWith("map");
            springTime = fromMap != toMap ? 1.1 : fromMap ? .75 : .45;
        }
        lastTarget = targetKey;
        lastFraction = fraction;
        if (snapCamera || Double.isNaN(goalLogReach) || dt <= 0 || dt > .5) {
            offsetLog = offsetLogVel = 0;
            offsetAim = offsetAimVel = ZERO;
            snapCamera = false;
        } else {
            if (changed) {
                // Un saut de plus de douze cadres (un fragment déjà loin) : on coupe, glisser n'aurait pas de sens.
                boolean cut = goalAim.sub(aim).norm() > 60 * reach;
                offsetLog = offsetLog + goalLogReach - logReach;
                offsetAim = cut ? ZERO : offsetAim.add(goalAim.sub(aim));
                if (cut) offsetAimVel = ZERO;
            }
            double[] l = damp(offsetLog, offsetLogVel, springTime, dt);
            offsetLog = l[0];
            offsetLogVel = l[1];
            double[] x = damp(offsetAim.x(), offsetAimVel.x(), springTime, dt), y = damp(offsetAim.y(), offsetAimVel.y(), springTime, dt),
                    z = damp(offsetAim.z(), offsetAimVel.z(), springTime, dt);
            offsetAim = new V(x[0], y[0], z[0]);
            offsetAimVel = new V(x[1], y[1], z[1]);
        }
        goalLogReach = logReach;
        goalAim = aim;
        V shown = aim.add(offsetAim);
        // Un trajet de plusieurs cadres : la vue recule d'autant, puis revient (comme une carte qu'on survole).
        double travel = Math.min(40, 1 + offsetAim.norm() / (2.5 * reach));
        setupCamera(Math.exp(logReach + offsetLog) * travel, shown);
    }

    /** Un pas de ressort critique vers zéro (temps de réponse `time`), stable pour tout pas de temps. */
    private static double[] damp(double x, double v, double time, double dt) {
        double omega = 2 / time, k = omega * dt, e = 1 / (1 + k + .48 * k * k + .235 * k * k * k);
        double temp = (v + omega * x) * dt;
        return new double[]{(x + temp) * e, (v - omega * temp) * e};
    }

    private Actor compoundActor(CollisionRun run, CollisionTimeline timeline) {
        for (int i = timeline.lastApproach() + 1; i < run.getSamplesCount(); i++) {
            CollisionSample next = run.getSamples(i);
            if (next.getPhase().equals("merge")) continue;
            CollisionBody heaviest = null;
            for (CollisionBody b : next.getBodiesList()) if (heaviest == null || b.getA() > heaviest.getA()) heaviest = b;
            if (heaviest != null && heaviest.getA() > 0) return new Actor(body(heaviest), look(heaviest), ZERO);
            break;
        }
        int z = run.getCompoundZ(), a = Math.max(1, run.getCompoundA());
        Body body = new Body(z, a, z + "-" + a, ZERO, ZERO, new V(0, .3, 1).unit(), Math.sqrt(5 / 3.0) * (.94 * Math.cbrt(a) + .4), 0);
        return new Actor(body, new Look("", run.getExcitationMev(), 0, "nucleus", "noyau composé", a * 931.494), ZERO);
    }

    private static double mass(Look look, Body body) {
        return look != null && look.mass() > 0 ? look.mass() : Math.max(1, body.a()) * 931.494;
    }

    private static double separation(List<Body> bodies) {
        return bodies.size() < 2 ? 0 : bodies.get(0).position().sub(bodies.get(1).position()).norm();
    }

    private static double maxRadius(List<Actor> actors) {
        double r = .84;
        for (Actor a : actors) r = Math.max(r, a.body.radius());
        return r;
    }

    private static double smooth(double t) {
        double x = Math.max(0, Math.min(1, t));
        return x * x * (3 - 2 * x);
    }

    private static double ease(double t, double from, double to) {
        return Math.exp(Math.log(from) + (Math.log(to) - Math.log(from)) * smooth(t));
    }

    private void begin(GraphicsContext g, double w, double h) {
        gc = g;
        width = w;
        height = h;
        report.tags.clear();
        report.sources.clear();
        report.saddles.clear();
        report.tracks.clear();
        report.potentialTop = 0;
        report.debyeFm = debyeFm;
        report.electronDensityM3 = electronDensityM3;
        report.externalFieldT = externalFieldT;
        mapped.clear();
        mapCompression = 0;
        report.mapCompression = 0;
        report.map = false;
        report.speed = 0;
        report.spinSum = null;
        report.spinText = "";
        report.tunnelAmplitude = Double.NaN;
        report.heat = 0;
        report.recoil = false;
        gc.save();
        gc.setLineCap(StrokeLineCap.ROUND);
    }

    private void end() {
        gc.restore();
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        gc.setGlobalAlpha(1);
    }

    private void setupCamera(double reach, V aim) {
        extent = reach * zoom;
        double free = Math.max(width * .55, width - insetRight);
        double aspect = Math.max(.6, free / Math.max(1, height));
        double distance = extent / (FOV_TAN * .86 * Math.min(aspect, 1.9));
        camera.targetX = aim.x();
        camera.targetY = aim.y();
        camera.targetZ = aim.z();
        camera.setup(width, height, free * .5, height * .5, distance);
        double[] center = camera.project(aim.x(), aim.y(), aim.z());
        report.pxPerFm = center == null ? 1 : center[3];
    }

    // ------------------------------------------------------------------ l'espace
    private void background() {
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        gc.setFill(new RadialGradient(0, 0, width * .5, height * .48, Math.max(width, height) * .75, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0C1830")), new Stop(.55, Color.web("#060C18")), new Stop(1, Color.web("#020409"))));
        gc.fillRect(0, 0, width, height);
        gc.setGlobalBlendMode(BlendMode.ADD);
    }

    // ------------------------------------------------------------------ la scène
    private void drawScene(List<Actor> actors, double conversion, Actor projectile, Actor target, double strong, double fieldScale) {
        List<double[]> charges = new ArrayList<>();
        for (Actor a : actors) if (a.body.z() > 0 && a.body.a() > 0 && a.alpha > .02 && !a.ghost)
            charges.add(new double[]{a.pos.x(), a.pos.y(), a.pos.z(), a.body.z() * a.alpha, a.body.radius(), a.alpha, a.body.z()});
        List<Actor> charged = new ArrayList<>();
        for (Actor a : actors) if (a.body.z() > 0 && a.body.a() > 0 && a.alpha > .3 && !a.ghost) charged.add(a);
        for (Actor a : actors) {
            if (a.body.z() <= 0 || a.body.a() <= 0 || a.alpha < .02 || a.ghost) continue;
            double[] p = project(a.pos);
            if (p == null || p[3] <= 0) continue;
            report.sources.add(new Source(p[0], p[1], 1 / p[3], a.body.z() * a.alpha, Math.max(.5, a.body.radius()), a.body, a.look, a.beta2));
        }
        if (showElectric) pressureMap(actors);
        if (showMagnetic) magneticTexture(actors, conversion);
        if (showWave) for (Actor a : actors) {
            // Le champ des autres charges au point où il se trouve (superposition) : il pousse le paquet, qui
            // s'aplatit à l'avant et s'étire à l'arrière d'autant plus que l'élan est devenu tension.
            V push = null;
            if (charges.size() >= 2 && a.body.z() > 0 && !a.ghost) {
                V e = ZERO;
                for (double[] q : charges) {
                    V d = a.pos.sub(new V(q[0], q[1], q[2]));
                    double r = d.norm();
                    if (r > 1e-6) e = e.add(d.mul(q[3] / (r * r * r)));
                }
                if (e.norm() > 0) push = e.unit();
            }
            wavePacket(a, push, push == null ? 0 : Math.pow(Math.max(0, Math.min(1, conversion)), 1.3));
        }
        if (!mapped.isEmpty()) mapLinks();
        List<Actor> order = new ArrayList<>(actors);
        order.sort(Comparator.comparingDouble((Actor a) -> {
            double[] p = project(a.pos);
            return p == null ? 0 : -p[2];
        }));
        for (Actor a : order) {
            if (a.body.a() == 0) photon(a);
            else nucleus(a);
        }
        nuclearContact(actors);
        for (Actor a : order) if (a.body.a() > 0) spinArrow(a);
    }

    /** Les liens de la carte : du corps créé vers chacun des autres, avec une impulsion qui court dans le sens du vol. */
    private void mapLinks() {
        double[] middle = project(mapped.get(0).pos);
        if (middle == null) return;
        gc.setGlobalBlendMode(BlendMode.ADD);
        for (int i = 1; i < mapped.size(); i++) {
            Actor a = mapped.get(i);
            double[] p = project(a.pos);
            if (p == null) continue;
            Color color = bodyColor(a.body.z(), a.body.a());
            gc.setStroke(color.deriveColor(0, 1, 1, .32 * a.alpha));
            gc.setLineWidth(1.2);
            gc.strokeLine(middle[0], middle[1], p[0], p[1]);
            double t = (anim * .35 + i * .19) % 1;
            double x = middle[0] + (p[0] - middle[0]) * t, y = middle[1] + (p[1] - middle[1]) * t;
            gc.setFill(color.interpolate(Color.WHITE, .45).deriveColor(0, 1, 1, .6 * a.alpha * (1 - Math.abs(2 * t - 1) * .5)));
            gc.fillOval(x - 2.1, y - 2.1, 4.2, 4.2);
        }
    }

    // ------------------------------------------------------------------ champ électrique
    /** Décades de potentiel montrées sous le col au contact (bleu en bas, blanc en haut). */
    static final double POTENTIAL_DECADES = 2.2;
    private double[] potentialGrid = new double[0], zoneGrid = new double[0];

    /**
     * Le champ électrique, calculé sur une grille au quart de la résolution à partir des charges telles qu'elles sont
     * dessinées (sphères uniformément chargées : Z/r² dehors, Z·r/R³ dedans) :
     * <ul>
     * <li>le halo de chaque noyau : la densité d'énergie de son propre champ, ε₀|E_i|²/2, sur quatre décades sous le
     *     maximum commun des réactifs, à la teinte de son isotope. Les halos se superposent sans trou : là où le champ
     *     total s'annule, chacun garde le sien ;</li>
     * <li>entre les noyaux : le potentiel combiné U = eφ = Σ kZ_ie²/r_i — l'énergie qu'aurait une charge +e posée là —
     *     en couleurs de chaleur (bleu → blanc) sur {@value #POTENTIAL_DECADES} décades sous le col au contact. Il
     *     n'apparaît que là où deux potentiels comptent (part du plus faible, 2U₂/(U₁+U₂)) et où leurs champs s'opposent
     *     ((1 − cos θ)/2) : la zone du col, qui s'échauffe à mesure que les noyaux s'approchent ;</li>
     * <li>par-dessus, les isopotentielles de U dans cette zone, et ses cols (points de selle : E = 0), chiffrés.</li>
     * </ul>
     * Puis les repères mobiles du champ (voir {@link #tracers}).
     */
    private void pressureMap(List<Actor> actors) {
        List<double[]> q = new ArrayList<>();
        List<Body> sources = new ArrayList<>();
        for (Actor a : actors) {
            if (a.body.z() <= 0 || a.body.a() <= 0 || a.alpha < .02 || a.ghost) continue;
            double[] p = project(a.pos);
            if (p == null || p[3] <= 0) continue;
            q.add(new double[]{p[0], p[1], 1 / p[3], a.body.z() * a.alpha, Math.max(.5, a.body.radius()), a.alpha, 0, 0, 0, a.body.z()});
            sources.add(fieldBody(a));
        }
        // La teinte identifie l'isotope affiché ; l'intensité et la forme restent calculées à partir de son champ.
        for (int i = 0; i < q.size(); i++) {
            Body source = sources.get(i);
            Color c = bodyColor(source.z(), source.a());
            q.get(i)[6] = c.getRed();
            q.get(i)[7] = c.getGreen();
            q.get(i)[8] = c.getBlue();
        }
        double toJoules = 8.8541878128e-12 / 2 * 1.43996e21 * 1.43996e21;
        report.pressureMax = pressurePeak * toJoules;
        report.pressureMin = pressurePeak * 1e-4 * toJoules;
        if (q.isEmpty()) return;
        int gw = (int) Math.max(48, Math.ceil(width / 4)), gh = (int) Math.max(27, Math.ceil(height / 4));
        if (pressureImage == null || (int) pressureImage.getWidth() != gw || (int) pressureImage.getHeight() != gh) {
            pressurePixels = new int[gw * gh];
            pressureBuffer = new PixelBuffer<>(gw, gh, IntBuffer.wrap(pressurePixels), PixelFormat.getIntArgbPreInstance());
            pressureImage = new WritableImage(pressureBuffer);
        }
        if (potentialGrid.length != gw * gh) {
            potentialGrid = new double[gw * gh];
            zoneGrid = new double[gw * gh];
        }
        int n = q.size();
        double[] fx = new double[n], fy = new double[n], vk = new double[n], uk = new double[n], heat = new double[3];
        // Une seule échelle d'énergie pour toute la scène : un champ faible ne peut pas se présenter aussi
        // intense qu'un champ fort en se renormalisant à sa propre surface.
        double floorK = pressurePeak * 1e-4;
        double norm = 1 / Math.log10(1 + 1e4), sx = width / gw, sy = height / gh;
        double top = colTop(q);
        report.potentialTop = top * 1.43996;
        // Le col de chaque couple (charges ponctuelles, écranté) : l'échelle propre de sa zone, pour lire U/U_col.
        double[] cols = new double[n * n];
        for (int i1 = 0; i1 < n; i1++)
            for (int i2 = 0; i2 < n; i2++) {
                if (i1 == i2) continue;
                double[] c1 = q.get(i1), c2 = q.get(i2);
                double gap = Math.max(1e-6, Math.hypot(c1[0] - c2[0], c1[1] - c2[1]) * (c1[2] + c2[2]) / 2);
                cols[i1 * n + i2] = Math.pow(Math.sqrt(c1[3]) + Math.sqrt(c2[3]), 2) / gap * Math.exp(-gap / 2 / debyeFm);
            }
        for (int j = 0; j < gh; j++) {
            double y = (j + .5) * sy;
            for (int i = 0; i < gw; i++) {
                double x = (i + .5) * sx, w = 0, r = 0, g = 0, b = 0, brightest = 0, clear = 1, sum = 0;
                int first = -1, second = -1;
                for (int k = 0; k < n; k++) {
                    double[] c = q.get(k);
                    double dx = (x - c[0]) * c[2], dy = (y - c[1]) * c[2], d = Math.sqrt(dx * dx + dy * dy);
                    // Dans un plasma, Debye–Hückel : φ ∝ e^(−r/λ)/r, E ∝ e^(−r/λ)(1 + r/λ)/r².
                    double m = Math.max(d, c[4]), screen = Math.exp(-d / debyeFm), f = c[3] / (m * m * m) * screen * (1 + m / debyeFm);
                    fx[k] = f * dx;
                    fy[k] = f * dy;
                    vk[k] = Math.max(0, Math.min(1, Math.log10(1 + (fx[k] * fx[k] + fy[k] * fy[k]) / floorK) * norm)) * c[5];
                    double weight = vk[k] * vk[k] * vk[k];
                    w += weight;
                    r += weight * c[6];
                    g += weight * c[7];
                    b += weight * c[8];
                    brightest = Math.max(brightest, vk[k]);
                    clear *= 1 - .92 * Math.pow(vk[k], 1.25);
                    // Le potentiel de la sphère chargée (en k·e/fm) : Z/r dehors, Z(3R² − r²)/2R³ dedans.
                    uk[k] = (d >= c[4] ? c[3] / d : c[3] * (3 * c[4] * c[4] - d * d) / (2 * c[4] * c[4] * c[4])) * screen;
                    sum += uk[k];
                    if (first < 0 || uk[k] > uk[first]) {
                        second = first;
                        first = k;
                    } else if (second < 0 || uk[k] > uk[second]) second = k;
                }
                double halo = 1 - clear, tint = .25 + .75 * brightest;
                w = Math.max(w, 1e-300);
                double hr = Math.min(1, r / w * tint), hg = Math.min(1, g / w * tint), hb = Math.min(1, b / w * tint);
                // La zone du col : deux potentiels comparables, deux champs qui s'opposent.
                double zone = 0, level = 0;
                if (!report.map && second >= 0 && uk[first] > 0) {
                    double share = Math.min(1, 2 * uk[second] / (uk[first] + uk[second]));
                    double n1 = Math.hypot(fx[first], fy[first]), n2 = Math.hypot(fx[second], fy[second]);
                    double cos = n1 > 0 && n2 > 0 ? (fx[first] * fx[second] + fy[first] * fy[second]) / (n1 * n2) : 1;
                    // L'éclat est normalisé au col du couple (U/U_col : 1 sur la crête et entre les noyaux, s'éteint au
                    // dehors) — lisible du deutérium à l'oganesson ; la couleur, elle, compare U au col au contact.
                    double colU = cols[first * n + second], profile = colU > 0 ? Math.pow(Math.min(1, sum / colU), 2) : 0;
                    zone = Math.pow(share, .6) * Math.pow(Math.max(0, (1 - cos) / 2), 2) * profile;
                    level = Math.max(0, Math.min(1, 1 + Math.log10(sum / top) / POTENTIAL_DECADES));
                }
                int idx = j * gw + i;
                potentialGrid[idx] = Math.log10(Math.max(sum, 1e-30));
                zoneGrid[idx] = zone;
                // Loin l'un de l'autre, le col est bas : la zone garde sa forme, en couleurs froides ; elle chauffe à
                // mesure que U y monte.
                double ai = zone * (.4 + .5 * level);
                heatRgb(level, heat);
                double alpha = ai + halo * (1 - ai);
                double pr = heat[0] * ai + hr * halo * (1 - ai), pg = heat[1] * ai + hg * halo * (1 - ai), pb = heat[2] * ai + hb * halo * (1 - ai);
                pressurePixels[idx] = (int) (alpha * 255) << 24 | (int) (Math.min(alpha, pr) * 255) << 16
                        | (int) (Math.min(alpha, pg) * 255) << 8 | (int) (Math.min(alpha, pb) * 255);
            }
        }
        pressureBuffer.updateBuffer(buffer -> null);
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        gc.setImageSmoothing(true);
        gc.drawImage(pressureImage, 0, 0, width, height);
        // À distances comprimées, un potentiel combiné ne voudrait rien dire : la carte garde les halos, pas le col.
        if (!report.map) {
            isopotentials(gw, gh, sx, sy, top);
            saddles(q);
        }
        List<Body> fieldSources = actors.stream().filter(a -> a.body.z() != 0 && a.alpha >= .02 && !a.ghost)
                .map(FusionRenderer::fieldBody).toList();
        tracers(q, sources, fieldSources);
    }

    /**
     * Le haut de l'échelle du potentiel, en k·e/fm : le col entre deux charges quand leurs surfaces se touchent,
     * (√Z₁ + √Z₂)²/(R₁ + R₂) — le plus haut des couples présents.
     */
    private static double colTop(List<double[]> q) {
        double top = 0;
        for (double[] c : q) top = Math.max(top, c[9] / c[4]);
        for (int i = 0; i < q.size(); i++)
            for (int j = i + 1; j < q.size(); j++) {
                double[] a = q.get(i), b = q.get(j);
                top = Math.max(top, Math.pow(Math.sqrt(a[9]) + Math.sqrt(b[9]), 2) / (a[4] + b[4]));
            }
        return Math.max(top, 1e-6);
    }

    /** Couleurs de chaleur du potentiel : bleu (bas) → violet → cramoisi → orange → blanc (le col au contact). */
    static void heatRgb(double t, double[] out) {
        double[] at = {0, .35, .6, .8, 1};
        double[][] rgb = {{.118, .247, .682}, {.416, .31, .847}, {.847, .251, .353}, {1, .541, .227}, {1, .949, .839}};
        double x = Math.max(0, Math.min(1, t));
        for (int k = 1; k < at.length; k++) {
            if (x > at[k] && k < at.length - 1) continue;
            double f = (x - at[k - 1]) / (at[k] - at[k - 1]);
            for (int c = 0; c < 3; c++) out[c] = rgb[k - 1][c] + (rgb[k][c] - rgb[k - 1][c]) * f;
            return;
        }
    }

    static Color heatColor(double t) {
        double[] c = new double[3];
        heatRgb(t, c);
        return Color.color(c[0], c[1], c[2]);
    }

    /**
     * Les isopotentielles de U (tous les quarts de décade sous le col au contact), par carrés marchants sur la grille, là
     * seulement où se trouve la zone du col : leur opacité suit celle de la zone.
     */
    private void isopotentials(int gw, int gh, double sx, double sy, double top) {
        double logTop = Math.log10(top);
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        gc.setLineWidth(1);
        double[] alphas = {.12, .24, .4};
        for (int level = 0; level <= 9; level++) {
            double value = logTop - level / 4.0;
            double[][] buckets = new double[3][256];
            int[] counts = new int[3];
            for (int j = 0; j < gh - 1; j++) {
                for (int i = 0; i < gw - 1; i++) {
                    int idx = j * gw + i;
                    double zone = Math.max(Math.max(zoneGrid[idx], zoneGrid[idx + 1]), Math.max(zoneGrid[idx + gw], zoneGrid[idx + gw + 1]));
                    if (zone < .12) continue;
                    double a = potentialGrid[idx], b = potentialGrid[idx + 1], c = potentialGrid[idx + gw + 1], d = potentialGrid[idx + gw];
                    int code = (a > value ? 8 : 0) | (b > value ? 4 : 0) | (c > value ? 2 : 0) | (d > value ? 1 : 0);
                    if (code == 0 || code == 15) continue;
                    double x0 = (i + .5) * sx, y0 = (j + .5) * sy;
                    double[] top0 = {x0 + sx * frac(a, b, value), y0}, right = {x0 + sx, y0 + sy * frac(b, c, value)};
                    double[] bottom = {x0 + sx * frac(d, c, value), y0 + sy}, left = {x0, y0 + sy * frac(a, d, value)};
                    int bucket = zone < .3 ? 0 : zone < .55 ? 1 : 2;
                    boolean centerAbove = (a + b + c + d) / 4 > value;
                    switch (code) {
                        case 1, 14 -> counts[bucket] = segment(buckets, counts, bucket, left, bottom);
                        case 2, 13 -> counts[bucket] = segment(buckets, counts, bucket, bottom, right);
                        case 3, 12 -> counts[bucket] = segment(buckets, counts, bucket, left, right);
                        case 4, 11 -> counts[bucket] = segment(buckets, counts, bucket, top0, right);
                        case 6, 9 -> counts[bucket] = segment(buckets, counts, bucket, top0, bottom);
                        case 7, 8 -> counts[bucket] = segment(buckets, counts, bucket, top0, left);
                        case 5 -> {
                            counts[bucket] = segment(buckets, counts, bucket, top0, centerAbove ? left : right);
                            counts[bucket] = segment(buckets, counts, bucket, bottom, centerAbove ? right : left);
                        }
                        case 10 -> {
                            counts[bucket] = segment(buckets, counts, bucket, top0, centerAbove ? right : left);
                            counts[bucket] = segment(buckets, counts, bucket, bottom, centerAbove ? left : right);
                        }
                        default -> { }
                    }
                }
            }
            Color color = heatColor(1 + (value - logTop) / POTENTIAL_DECADES).interpolate(Color.WHITE, .35);
            for (int k = 0; k < 3; k++) {
                if (counts[k] == 0) continue;
                gc.setStroke(color.deriveColor(0, 1, 1, alphas[k]));
                gc.beginPath();
                for (int m = 0; m < counts[k]; m += 4) {
                    gc.moveTo(buckets[k][m], buckets[k][m + 1]);
                    gc.lineTo(buckets[k][m + 2], buckets[k][m + 3]);
                }
                gc.stroke();
            }
        }
    }

    private static double frac(double from, double to, double value) {
        return Math.abs(to - from) < 1e-12 ? .5 : Math.max(0, Math.min(1, (value - from) / (to - from)));
    }

    private static int segment(double[][] buckets, int[] counts, int k, double[] p, double[] q) {
        int c = counts[k];
        if (c + 4 > buckets[k].length) buckets[k] = Arrays.copyOf(buckets[k], buckets[k].length * 2);
        buckets[k][c] = p[0];
        buckets[k][c + 1] = p[1];
        buckets[k][c + 2] = q[0];
        buckets[k][c + 3] = q[1];
        return c + 4;
    }

    /**
     * Les cols du potentiel : pour chaque couple de charges, le point où E_total = 0 (Newton sur le champ de toutes les
     * charges, départ au point neutre de deux charges ponctuelles, r₁/r₂ = √(Z₁/Z₂)). Un col hors des noyaux est un point
     * de selle de U : minimal le long de l'axe, maximal en travers.
     */
    private void saddles(List<double[]> q) {
        if (q.size() < 2 || q.size() > 12) return;
        for (int i = 0; i < q.size(); i++)
            for (int j = i + 1; j < q.size(); j++) {
                double[] a = q.get(i), b = q.get(j);
                double si = Math.sqrt(a[3]), sj = Math.sqrt(b[3]), span = Math.hypot(b[0] - a[0], b[1] - a[1]);
                if (si <= 0 || sj <= 0 || span < 2) continue;
                double x = a[0] + (b[0] - a[0]) * si / (si + sj), y = a[1] + (b[1] - a[1]) * si / (si + sj);
                for (int it = 0; it < 24; it++) {
                    double[] e = field2d(x, y, q), ex = field2d(x + .5, y, q), ey = field2d(x, y + .5, q);
                    double j00 = (ex[0] - e[0]) / .5, j01 = (ey[0] - e[0]) / .5, j10 = (ex[1] - e[1]) / .5, j11 = (ey[1] - e[1]) / .5;
                    double det = j00 * j11 - j01 * j10;
                    if (Math.abs(det) < 1e-300) break;
                    double dx = (-e[0] * j11 + e[1] * j01) / det, dy = (-j00 * e[1] + j10 * e[0]) / det, step = Math.hypot(dx, dy);
                    if (step > .25 * span) {
                        dx *= .25 * span / step;
                        dy *= .25 * span / step;
                    }
                    x += dx;
                    y += dy;
                    if (step < .02) break;
                }
                double[] e = field2d(x, y, q);
                double ra = Math.hypot(x - a[0], y - a[1]) * a[2], rb = Math.hypot(x - b[0], y - b[1]) * b[2];
                if (ra < 1e-6 || rb < 1e-6) continue;
                double reference = a[3] / (ra * ra) + b[3] / (rb * rb);
                if (Math.hypot(e[0], e[1]) > .02 * reference || x < -20 || x > width + 20 || y < -20 || y > height + 20) continue;
                boolean inside = false;
                double u = 0;
                for (double[] c : q) {
                    double d = Math.hypot(x - c[0], y - c[1]) * c[2];
                    if (d < c[4]) inside = true;
                    u += (d >= c[4] ? c[3] / d : c[3] * (3 * c[4] * c[4] - d * d) / (2 * c[4] * c[4] * c[4])) * Math.exp(-d / debyeFm);
                }
                if (inside) continue;
                boolean seen = false;
                for (double[] s : report.saddles) seen |= Math.hypot(s[0] - x, s[1] - y) < 4;
                if (!seen) report.saddles.add(new double[]{x, y, u * 1.43996, i, j});
            }
    }

    /** Le champ total (dans le plan de l'image), en k·e/fm², au point d'écran (x, y). */
    private double[] field2d(double x, double y, List<double[]> q) {
        double ex = 0, ey = 0;
        for (double[] c : q) {
            double dx = (x - c[0]) * c[2], dy = (y - c[1]) * c[2], d = Math.sqrt(dx * dx + dy * dy);
            double m = Math.max(d, c[4]), k = c[3] / (m * m * m) * Math.exp(-d / debyeFm) * (1 + m / debyeFm);
            ex += k * dx;
            ey += k * dy;
        }
        return new double[]{ex, ey};
    }

    /** Un grain de champ : parti de la surface d'un noyau (ou né au heurt), il avance dans la direction du champ total. */
    private static final class Tracer {
        int source;
        V position;
        double age, life;
        boolean clash;
    }

    private final List<Tracer> tracers = new ArrayList<>();
    private final Random tracerRandom = new Random(11);
    private double tracerClock = Double.NaN;
    private List<String> tracerSources = List.of();
    private List<V> tracerAnchors = List.of();

    /**
     * L'activité du champ : des grains très fins naissent à la surface de chaque noyau (à sa couleur, d'autant plus nombreux
     * que Z est grand) et dérivent comme des charges d'essai dans un milieu visqueux : leur vitesse suit le champ local
     * (v = μE, ramenée à √(E/E_réf) pour rester lisible, E_réf = le champ du noyau à 2,5 rayons). Seuls, ils filent droit ;
     * face à un autre noyau, le champ se courbe et ils sont déviés ; là où les deux champs s'annulent (le point neutre, au
     * cœur du heurt), ils ralentissent et s'accumulent : la résistance se voit. Ils s'éteignent en s'éloignant.
     */
    private void tracers(List<double[]> q, List<Body> sources, List<Body> fieldSources) {
        double dt = Double.isNaN(tracerClock) ? 0 : Math.max(0, Math.min(.1, anim - tracerClock));
        tracerClock = anim;
        List<String> identities = sources.stream().map(b -> b.z() + ":" + b.a()).toList();
        boolean same = identities.equals(tracerSources);
        if (!same) {
            tracers.clear();
            tracerSources = identities;
        }
        // Le champ d'une charge se déplace avec elle : chaque repère est entraîné par le déplacement des sources depuis
        // l'image précédente, pondéré par la part de chacune dans |E|² à l'endroit du repère.
        List<V> anchors = new ArrayList<>();
        for (Body b : sources) anchors.add(b.position());
        V[] moves = null;
        if (same && tracerAnchors.size() == anchors.size()) {
            moves = new V[anchors.size()];
            for (int k = 0; k < anchors.size(); k++) moves[k] = anchors.get(k).sub(tracerAnchors.get(k));
        }
        tracerAnchors = anchors;
        int existing = tracers.size();
        int[] count = new int[q.size()];
        for (Tracer t : tracers) count[t.source]++;
        for (int s = 0; s < q.size(); s++) {
            if (!inFrame(sources.get(s).position(), 53 * sources.get(s).radius())) continue;
            int target = (int) Math.max(30, Math.min(110, 30 + 14 * Math.log1p(q.get(s)[9])));
            boolean fill = count[s] == 0;
            if (!fill && dt <= 0) continue;
            int spawn = fill ? target : Math.min(target - count[s], (int) Math.ceil(target * dt * 1.2));
            for (int k = 0; k < spawn; k++) {
                Tracer t = new Tracer();
                t.source = s;
                double theta = tracerRandom.nextDouble() * 2 * Math.PI, rr = q.get(s)[4] * 1.05;
                double z = 2 * tracerRandom.nextDouble() - 1, equator = Math.sqrt(1 - z * z);
                t.position = sources.get(s).position().add(new V(equator * Math.cos(theta), equator * Math.sin(theta), z).mul(rr));
                t.life = .7 + .6 * tracerRandom.nextDouble();
                if (fill) {
                    // Au départ, répartis le long de leur vie : le flux est déjà établi.
                    double age = tracerRandom.nextDouble() * t.life;
                    for (double done = 0; done < age; done += .05) advance(t, sources, fieldSources, Math.min(.05, age - done));
                    t.age = age;
                }
                tracers.add(t);
            }
        }
        // Au heurt : des grains naissent avec une probabilité proportionnelle au terme opposé
        // −2E₁·E₂ (la même grandeur que la zone blanche), puis suivent le champ total.
        if (q.size() == 2 && dt > 0) {
            double[] a = q.get(0), b = q.get(1);
            double gap = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (gap > 1.05 * (a[4] / a[2] + b[4] / b[2])) {
                double share = Math.sqrt(a[3]) / (Math.sqrt(a[3]) + Math.sqrt(b[3]));
                double nx = a[0] + (b[0] - a[0]) * share, ny = a[1] + (b[1] - a[1]) * share;
                double spread = .45 * Math.min(gap * share, gap * (1 - share));
                int clashes = 0;
                for (Tracer t : tracers) if (t.clash) clashes++;
                for (int k = 0; k < 40 && clashes < 70; k++) {
                    if (tracerRandom.nextDouble() > dt * 6) continue;
                    double x = nx + tracerRandom.nextGaussian() * spread, y = ny + tracerRandom.nextGaussian() * spread;
                    if (tracerRandom.nextDouble() > clashAt(x, y, q, pressurePeak)) continue;
                    Tracer t = new Tracer();
                    t.source = 0;
                    t.clash = true;
                    V neutral = sources.get(0).position().mul(1 - share).add(sources.get(1).position().mul(share));
                    t.position = neutral.add(vector(camera.right()).mul((x - nx) * a[2]))
                            .sub(vector(camera.up()).mul((y - ny) * a[2]));
                    t.life = .6 + .6 * tracerRandom.nextDouble();
                    tracers.add(t);
                    clashes++;
                }
            }
        }
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        Iterator<Tracer> it = tracers.iterator();
        int index = 0;
        while (it.hasNext()) {
            Tracer t = it.next();
            if (moves != null && index++ < existing) t.position = t.position.add(carried(t.position, sources, moves));
            if (dt > 0) {
                for (double done = 0; done < dt; done += .025) advance(t, sources, fieldSources, Math.min(.025, dt - done));
                t.age += dt;
            }
            double[] src = q.get(t.source);
            double[] screen = project(t.position);
            if (screen == null) { it.remove(); continue; }
            double x = screen[0], y = screen[1];
            boolean swallowed = false;
            for (int k = 0; k < q.size(); k++)
                if ((t.clash || k != t.source) && t.position.sub(sources.get(k).position()).norm() < sources.get(k).radius()) swallowed = true;
            if (t.age > t.life || swallowed || x < -20 || x > width + 20 || y < -20 || y > height + 20) {
                it.remove();
                continue;
            }
            double f = t.age / t.life, alpha = Math.min(1, f * 6) * (1 - f) * .95 * src[5];
            // Un point clair (la teinte de son noyau, éclaircie) : lisible même sur le halo de sa propre couleur.
            if (t.clash) gc.setFill(Color.color(1, .95, .86, alpha));
            else gc.setFill(Color.color(src[6] + (1 - src[6]) * .62, src[7] + (1 - src[7]) * .62, src[8] + (1 - src[8]) * .62, alpha));
            // Un point advecté par E_total : son mouvement suit le champ, sans traînée rectiligne artificielle.
            gc.fillOval(x - 1.3, y - 1.3, 2.6, 2.6);
        }
    }

    /** Terme croisé positif -2E₁·E₂, sur la même échelle que la carte du champ. */
    private static double clashAt(double x, double y, List<double[]> q, double peak) {
        double[][] e = new double[2][];
        for (int k = 0; k < 2; k++) {
            double[] c = q.get(k);
            double dx = (x - c[0]) * c[2], dy = (y - c[1]) * c[2];
            double m = Math.max(Math.sqrt(dx * dx + dy * dy), c[4]), f = c[3] / (m * m * m);
            e[k] = new double[]{f * dx, f * dy};
        }
        double opposed = Math.max(0, -2 * (e[0][0] * e[1][0] + e[0][1] * e[1][1]));
        return Math.min(1, Math.log10(1 + opposed / Math.max(1e-12, peak * 1e-4)) / Math.log10(1 + 1e4));
    }

    /**
     * RK2 du champ total à chaque sous-pas. La mobilité est une convention de lecture : 10 rayons par seconde, et au moins
     * 15 % du cadre, pour que le flux reste visible quand la vue embrasse des centaines de femtomètres.
     */
    private void advance(Tracer t, List<Body> sources, List<Body> fieldSources, double dt) {
        Body source = sources.get(t.source);
        double radius = Math.max(.5, source.radius());
        double reference = KE * E_CHARGE * source.z() / (6.25 * radius * radius * 1e-30);
        t.position = advectElectric(t.position, fieldSources, reference, dt * Math.max(radius * 10, extent * .15), debyeFm);
    }

    /** Le déplacement du motif du champ en p : celui de chaque source, pondéré par sa part de |E|² en p. */
    private static V carried(V p, List<Body> sources, V[] moves) {
        double total = 0;
        V sum = ZERO;
        for (int k = 0; k < sources.size(); k++) {
            Body s = sources.get(k);
            double r = Math.max(p.sub(s.position()).norm(), Math.max(.5, s.radius())), e = s.z() / (r * r), w = e * e;
            total += w;
            sum = sum.add(moves[k].mul(w));
        }
        return total > 0 ? sum.mul(1 / total) : ZERO;
    }

    private static V vector(double[] values) { return new V(values[0], values[1], values[2]); }

    private static Body fieldBody(Actor a) {
        return new Body(a.body.z(), a.body.a(), a.body.label(), a.pos, a.vel, a.body.spin(), a.body.radius(), a.body.moment());
    }

    /** Borne conservative de projection d'une sphère ; seule la géométrie invisible est omise, jamais ses sources. */
    private boolean inFrame(V position, double radius) {
        V aim = new V(camera.targetX, camera.targetY, camera.targetZ);
        double[] center = project(aim);
        double depth = position.sub(aim).dot(vector(camera.forward())) + center[2];
        if (depth + radius <= 1e-9) return false;
        if (depth - radius <= 1e-9) return true;
        double[] p = project(position);
        if (p == null) return false;
        double focal = height / (2 * FOV_TAN);
        double padX = radius * (focal + Math.abs(p[0] - center[0])) / (depth - radius);
        double padY = radius * (focal + Math.abs(p[1] - center[1])) / (depth - radius);
        return p[0] + padX >= 0 && p[0] - padX <= width && p[1] + padY >= 0 && p[1] - padY <= height;
    }

    // ------------------------------------------------------------------ champ magnétique
    /** Repères mobiles advectés dans B_total, recalculé à partir de tous les noyaux à chaque pas. */
    private static final class MagneticMarker {
        int source;
        V position;
        double age, life;
    }

    private final List<MagneticMarker> magneticMarkers = new ArrayList<>();
    private final Random magneticRandom = new Random(37);
    private List<String> magneticSources = List.of();
    private double magneticClock = Double.NaN;

    private void magneticTexture(List<Actor> actors, double tension) {
        List<Actor> visible = actors.stream().filter(a -> a.body.a() > 0 && a.alpha >= .05 && !a.ghost).toList();
        List<Body> sources = visible.stream().map(FusionRenderer::fieldBody).toList();
        if (sources.isEmpty()) { magneticMarkers.clear(); return; }
        double dt = Double.isNaN(magneticClock) ? 0 : Math.max(0, Math.min(.1, anim - magneticClock));
        magneticClock = anim;
        List<String> identities = sources.stream().map(b -> b.z() + ":" + b.a()).toList();
        if (!identities.equals(magneticSources)) {
            magneticMarkers.clear();
            magneticSources = identities;
        }
        double peak = Math.max(1e-9, Math.abs(externalField));
        for (Body body : sources) {
            V axis = body.spin().unit();
            if (axis.norm() < .1) axis = new V(0, 0, 1);
            peak = Math.max(peak, magnetic(body.position().add(axis.mul(Math.max(.5, body.radius()))),
                    sources, externalField).norm());
        }
        int[] count = new int[sources.size()];
        for (MagneticMarker marker : magneticMarkers) count[marker.source]++;
        for (int s = 0; s < sources.size(); s++) {
            Body body = sources.get(s);
            if (!inFrame(body.position(), 8 * Math.max(.5, body.radius()))) continue;
            boolean moving = body.z() > 0 && body.velocity().norm() > 0;
            if (Math.abs(body.moment()) < 1e-3 && !moving && externalField == 0) continue;
            int target = Math.min(36, 16 + (int) (7 * Math.log1p(Math.abs(body.moment()))));
            int spawn = count[s] == 0 ? target : dt <= 0 ? 0 : Math.min(target - count[s], Math.max(1, (int) Math.ceil(target * dt)));
            for (int k = 0; k < spawn; k++) {
                double z = magneticRandom.nextDouble() * 2 - 1, phi = magneticRandom.nextDouble() * 2 * Math.PI;
                double plane = Math.sqrt(1 - z * z);
                V direction = new V(plane * Math.cos(phi), plane * Math.sin(phi), z);
                MagneticMarker marker = new MagneticMarker();
                marker.source = s;
                marker.position = body.position().add(direction.mul(body.radius() * (1.1 + 2.7 * magneticRandom.nextDouble())));
                marker.life = .8 + magneticRandom.nextDouble() * 1.5;
                marker.age = magneticRandom.nextDouble() * marker.life;
                magneticMarkers.add(marker);
            }
        }
        gc.setGlobalBlendMode(BlendMode.ADD);
        Iterator<MagneticMarker> it = magneticMarkers.iterator();
        while (it.hasNext()) {
            MagneticMarker marker = it.next();
            Body source = sources.get(marker.source);
            if (dt > 0) {
                marker.position = advectMagnetic(marker.position, sources, externalField, peak,
                        dt * Math.max(.5, source.radius()) * 5);
                marker.age += dt;
            }
            double[] p = project(marker.position);
            if (marker.age >= marker.life || p == null || p[0] < 0 || p[0] > width || p[1] < 0 || p[1] > height) {
                it.remove();
                continue;
            }
            double b = magnetic(marker.position, sources, externalField).norm();
            double level = Math.min(1, Math.log10(1 + 1e3 * b * b / (peak * peak)) / Math.log10(1 + 1e3));
            double fade = Math.min(1, marker.age * 6) * Math.min(1, (marker.life - marker.age) * 5);
            double alpha = .42 * level * fade * visible.get(marker.source).alpha * (1 - .45 * Math.max(0, Math.min(1, tension)));
            gc.setFill(Color.color(.48, .77, 1, alpha));
            gc.fillOval(p[0] - .95, p[1] - .95, 1.9, 1.9);
        }
    }

    // ------------------------------------------------------------------ l'onde
    /**
     * Le paquet d'onde : une enveloppe de largeur σ (ƛ partagé selon les masses), et des rides perpendiculaires à la
     * vitesse, espacées de la longueur d'onde de de Broglie locale h/p (elles s'étirent quand le noyau freine).
     */
    private void wavePacket(Actor a, V push, double squeeze) {
        if (a.body.a() == 0 || a.sigma < .25 * a.body.radius() || a.alpha < .02) return;
        double[] c = project(a.pos);
        if (c == null) return;
        Color tint = bodyColor(a.body.z(), a.body.a()).interpolate(Color.web("#BFEFFF"), .55);
        double halo = (a.body.radius() + 2.2 * a.sigma) * c[3];
        double sig = a.sigma * c[3];
        gc.setGlobalBlendMode(BlendMode.ADD);
        Stop[] stops = new Stop[8];
        for (int k = 0; k < 8; k++) {
            double x = k / 7.0, r = x * halo;
            double env = Math.exp(-Math.pow(Math.max(0, r - a.body.radius() * c[3]) / Math.max(1, sig), 2) / 2);
            stops[k] = new Stop(x, tint.deriveColor(0, 1, 1, Math.min(1, (k == 7 ? 0 : .13 * env) * a.alpha * (1 + .35 * squeeze))));
        }
        double[] front = push == null || squeeze < .02 ? null : project(a.pos.sub(push.mul(a.body.radius())));
        if (front != null && Math.hypot(front[0] - c[0], front[1] - c[1]) > .5) {
            // Vers l'autre noyau (l'avant) : le paquet s'accumule contre la barrière (comme une fonction d'Airy au
            // point de rebroussement) ; à l'arrière il s'étire.
            double angle = Math.toDegrees(Math.atan2(front[1] - c[1], front[0] - c[0]));
            gc.save();
            gc.translate(c[0], c[1]);
            gc.rotate(angle);
            gc.translate(-.22 * squeeze * halo, 0);
            gc.scale(1 + .3 * squeeze, 1 - .14 * squeeze);
            gc.setFill(new RadialGradient(0, .55 * squeeze, 0, 0, halo, false, CycleMethod.NO_CYCLE, stops));
            gc.fillOval(-halo, -halo, 2 * halo, 2 * halo);
            gc.restore();
        } else {
            gc.setFill(new RadialGradient(0, 0, c[0], c[1], halo, false, CycleMethod.NO_CYCLE, stops));
            gc.fillOval(c[0] - halo, c[1] - halo, 2 * halo, 2 * halo);
        }
        // Les rides : la longueur d'onde locale, de l'impulsion réelle à cet instant.
        double speed = a.vel.norm() / C;
        double m = a.look == null ? 0 : a.look.mass();
        if (m <= 0 || speed < 1e-6) return;
        double lambda = 2 * Math.PI * 197.3269804 / (m * speed) * c[3];
        if (lambda < 5 || lambda > halo * 1.6) return;
        double[] ahead = project(a.pos.add(a.vel.unit().mul(a.body.radius())));
        if (ahead == null) return;
        double dx = ahead[0] - c[0], dy = ahead[1] - c[1], dn = Math.hypot(dx, dy);
        if (dn < 1e-6) return;
        dx /= dn;
        dy /= dn;
        double shift = (anim * .9 % 1) * lambda;
        for (double o = -halo + shift % lambda; o < halo; o += lambda) {
            double half = Math.sqrt(Math.max(0, halo * halo - o * o));
            double env = Math.exp(-o * o / (2 * Math.max(1, sig + a.body.radius() * c[3]) * Math.max(1, sig + a.body.radius() * c[3])));
            for (int s = -3; s < 3; s++) {
                double t0 = half * s / 3, t1 = half * (s + 1) / 3;
                double along = Math.exp(-Math.pow((t0 + t1) / 2 / Math.max(1, half), 2) * 2);
                gc.setStroke(tint.deriveColor(0, 1, 1, .32 * env * along * a.alpha));
                gc.setLineWidth(1.4);
                gc.strokeLine(c[0] + dx * o - dy * t0, c[1] + dy * o + dx * t0, c[0] + dx * o - dy * t1, c[1] + dy * o + dx * t1);
            }
        }
    }

    /**
     * L'onde évanescente : dans la barrière, l'amplitude WKB décroît comme exp(−∫κ dr). Elle part du projectile vers
     * la cible, jusqu'au contact.
     */
    private void tunnelTail(CollisionRun run, List<Actor> actors, double front, double alpha) {
        if (run.getRadialFmCount() < 2 || actors.size() < 2) return;
        Actor p = null, t = null;
        for (Actor a : actors) if (!a.ghost && a.alpha > .05 && a.body.a() > 0) {
            if (p == null || a.pos.norm() > p.pos.norm()) {
                t = p;
                p = a;
            } else if (t == null) t = a;
        }
        if (p == null || t == null) return;
        V dir = t.pos.sub(p.pos);
        double sep = dir.norm();
        if (sep < 1e-6) return;
        dir = dir.mul(1 / sep);
        double contact = run.getContactFm();
        double reachS = Math.max(0, sep - contact * .55) * front;
        gc.setGlobalBlendMode(BlendMode.ADD);
        double amplitudeAtContact = 1;
        int n = 24;
        for (int i = 0; i <= n; i++) {
            double s = p.body.radius() * .4 + (reachS - p.body.radius() * .4) * i / n;
            if (s < 0) continue;
            double r = Math.max(contact, sep - s);
            double amp = amplitude(run, r);
            amplitudeAtContact = amp;
            double[] q = project(p.pos.add(dir.mul(s)));
            if (q == null) continue;
            double size = Math.max(2, p.body.radius() * q[3] * (.3 + .45 * Math.pow(amp, .15)));
            glow(q[0], q[1], size, Color.web("#9FE8FF"), alpha * .13 * Math.pow(amp, .3) * p.alpha);
        }
        report.tunnelAmplitude = amplitudeAtContact * amplitudeAtContact;
    }

    /** L'amplitude WKB de l'onde relative à la distance r (fm), interpolée dans le profil du moteur ; NaN sans profil. */
    static double waveAmplitude(CollisionRun run, double r) {
        return run.getRadialFmCount() < 2 ? Double.NaN : amplitude(run, r);
    }

    private static double amplitude(CollisionRun run, double r) {
        int n = run.getRadialFmCount();
        if (r <= run.getRadialFm(0)) return run.getWaveAmplitude(0);
        for (int i = 1; i < n; i++) {
            if (run.getRadialFm(i) >= r) {
                double f = (r - run.getRadialFm(i - 1)) / (run.getRadialFm(i) - run.getRadialFm(i - 1));
                return run.getWaveAmplitude(i - 1) + f * (run.getWaveAmplitude(i) - run.getWaveAmplitude(i - 1));
            }
        }
        return run.getWaveAmplitude(n - 1);
    }

    // ------------------------------------------------------------------ les noyaux
    private List<V> geometry(int a) {
        return nucleonGeometry.computeIfAbsent(Math.max(1, a), count -> {
            if (count == 1) return List.of(ZERO);
            if (count == 2) return List.of(new V(-.5, 0, 0), new V(.5, 0, 0));
            if (count == 3) return List.of(new V(-.5, -.29, 0), new V(.5, -.29, 0), new V(0, .58, 0));
            List<V> lattice = new ArrayList<>();
            int n = (int) Math.ceil(Math.cbrt(count)) + 2;
            for (int x = -n; x <= n; x++)
                for (int y = -n; y <= n; y++)
                    for (int z = -n; z <= n; z++) if (((x + y + z) & 1) == 0) lattice.add(new V(x, y, z));
            lattice.sort(Comparator.comparingDouble(p -> p.dot(p) + .0001 * (p.x() * .7 + p.y() * .3 + p.z() * .11)));
            List<V> chosen = new ArrayList<>(lattice.subList(0, count));
            V center = ZERO;
            for (V p : chosen) center = center.add(p);
            center = center.mul(1.0 / count);
            double max = 0;
            for (V p : chosen) max = Math.max(max, p.sub(center).norm());
            List<V> result = new ArrayList<>();
            for (V p : chosen) result.add(p.sub(center).mul(1 / Math.max(1, max)));
            return List.copyOf(result);
        });
    }

    /** Le profil de densité de Woods–Saxon vu en projection : Σ(b) = ∫ρ(√(b²+z²))dz, en paliers de dégradé. */
    private Stop[] density(double radius, Color tint) {
        long key = Math.round(radius * 40) * 4096 + (long) (tint.getRed() * 15) * 256 + (long) (tint.getGreen() * 15) * 16 + (long) (tint.getBlue() * 15);
        return densityProfiles.computeIfAbsent(key, k -> {
            double a = .55, rms2 = .6 * radius * radius, c2 = (5.0 / 3) * (rms2 - 1.4 * Math.PI * Math.PI * a * a);
            double c = Math.sqrt(Math.max(c2, .09)), outer = c + 4.5 * a;
            int n = 12;
            double[] sigma = new double[n];
            for (int i = 0; i < n; i++) {
                double b = outer * i / (n - 1), sum = 0;
                for (int j = 0; j < 80; j++) {
                    double z = outer * (j + .5) / 80, r = Math.sqrt(b * b + z * z);
                    sum += 1 / (1 + Math.exp((r - c) / a));
                }
                sigma[i] = sum;
            }
            Stop[] stops = new Stop[n];
            for (int i = 0; i < n; i++) stops[i] = new Stop(i / (n - 1.0), tint.deriveColor(0, 1, 1, .62 * sigma[i] / sigma[0]));
            return stops;
        });
    }

    private void nucleus(Actor a) {
        double[] p = project(a.pos);
        if (p == null || a.alpha < .01) return;
        Body body = a.body;
        double r = body.radius() * p[3];
        if (p[0] < -r * 3 || p[0] > width + r * 3 || p[1] < -r * 3 || p[1] > height + r * 3) return;
        Color tint = bodyColor(body.z(), body.a());
        double excitation = a.look == null ? 0 : a.look.excitation();
        double temperature = body.a() > 0 ? Math.sqrt(8 * Math.max(0, excitation) / body.a()) : 0;
        double hot = Math.min(1, temperature / 2.5 + a.heat * .6);
        report.tags.add(new Tag(p[0], p[1], r, body, a.look, a.alpha, a.beta2, a.mapOffset));
        if (r < 2.5) {
            gc.setGlobalBlendMode(BlendMode.ADD);
            glow(p[0], p[1], 7, tint, .9 * a.alpha);
            glow(p[0], p[1], 2.2, Color.WHITE, .9 * a.alpha);
            return;
        }
        gc.save();
        // Un noyau chaud est une goutte liquide qui vibre : sa surface calculée (voir dropShape).
        double[][] drop = a.ghost ? null : surface(a, p, r, temperature);
        if (drop == null && a.squash < .999) {
            double[] ahead = project(a.pos.add(a.squashAxis.mul(body.radius())));
            if (ahead != null) {
                double angle = Math.atan2(ahead[1] - p[1], ahead[0] - p[0]);
                gc.translate(p[0], p[1]);
                gc.rotate(Math.toDegrees(angle));
                gc.scale(a.squash, 1 / Math.sqrt(a.squash));
                gc.rotate(-Math.toDegrees(angle));
                gc.translate(-p[0], -p[1]);
            }
        }
        // La lueur de gaz autour, la couleur de l'élément ; plus vive quand le noyau est chaud.
        gc.setGlobalBlendMode(BlendMode.ADD);
        glow(p[0], p[1], r * 2.1, tint.interpolate(Color.web("#FF9A55"), hot * .6), (.16 + .3 * hot) * a.alpha);
        // Le nuage de matière : densité de Woods–Saxon (centre dense, bord diffus).
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        double fadeAll = a.alpha * (a.ghost ? .7 : 1);
        Stop[] cloud = density(body.radius(), tint.interpolate(Color.WHITE, .25));
        Stop[] faded = new Stop[cloud.length];
        for (int i = 0; i < cloud.length; i++) faded[i] = new Stop(cloud[i].getOffset(), cloud[i].getColor().deriveColor(0, 1, 1, fadeAll));
        double outer = body.radius() * 1.35 * p[3];
        if (drop != null) {
            // La matière remplit la goutte déformée : on découpe le nuage et les nucléons à sa surface.
            gc.save();
            gc.beginPath();
            gc.moveTo(drop[0][0], drop[1][0]);
            for (int k = 1; k < drop[0].length; k++) gc.lineTo(drop[0][k], drop[1][k]);
            gc.closePath();
            gc.clip();
            outer *= 1.4 + a.elong;
        }
        gc.setFill(new RadialGradient(0, 0, p[0], p[1], outer, false, CycleMethod.NO_CYCLE, faded));
        gc.fillOval(p[0] - outer, p[1] - outer, 2 * outer, 2 * outer);
        // Les nucléons : protons rouges, neutrons lavande, qui s'agitent (plus fort dans un noyau chaud).
        if (r > 6 && body.a() > 0) nucleons(a, p, r, hot);
        if (drop != null) {
            gc.restore();
            // La surface de la goutte : son contour calculé, tendu comme une membrane.
            gc.setStroke(tint.interpolate(Color.WHITE, .6).deriveColor(0, 1, 1, .7 * fadeAll));
            gc.setLineWidth(1.8);
            gc.strokePolygon(drop[0], drop[1], drop[0].length);
        } else {
            // Le verre : bord de Fresnel teinté, reflet.
            gc.setFill(new RadialGradient(0, 0, p[0], p[1], r * 1.03, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.TRANSPARENT), new Stop(.78, tint.deriveColor(0, 1, 1, 0)),
                    new Stop(.94, tint.interpolate(Color.WHITE, .45).deriveColor(0, 1, 1, .32 * fadeAll)),
                    new Stop(.99, tint.interpolate(Color.WHITE, .7).deriveColor(0, 1, 1, .6 * fadeAll)), new Stop(1, Color.TRANSPARENT)));
            gc.fillOval(p[0] - r * 1.03, p[1] - r * 1.03, 2.06 * r, 2.06 * r);
        }
        gc.setGlobalBlendMode(BlendMode.ADD);
        glow(p[0] - r * .38, p[1] - r * .42, r * .42, Color.WHITE, .45 * fadeAll);
        glow(p[0] + r * .5, p[1] + r * .55, r * .12, Color.WHITE, .7 * fadeAll);
        if (hot > .05) glow(p[0], p[1], r * .9, Color.web("#FF8A3A"), .35 * hot * fadeAll);
        gc.restore();
        if (a.ghost) {
            gc.setGlobalBlendMode(BlendMode.ADD);
            double ring = r * (1 + 2.5 * (1 - a.alpha));
            gc.setStroke(Color.web("#CFF6FF", .7 * a.alpha * (1 - a.alpha) * 4));
            gc.setLineWidth(2);
            gc.strokeOval(p[0] - ring, p[1] - ring, 2 * ring, 2 * ring);
        }
    }

    /**
     * La surface d'un noyau chaud, modèle de la goutte liquide (Bohr–Mottelson) : R(θ) = R[1 + α₂P₂(cos θ) + α₃P₃(cos θ)].
     * Raideurs C_λ = (λ−1)(λ+2)R₀²σ − (3/2π)(λ−1)/(2λ+1)·Z²e²/R₀ (σ = a_s/4πr₀², a_s = 17,23 MeV, r₀ = 1,2 fm) : la
     * tension de surface la retient, la répulsion des protons la pousse à se déformer. Masses irrotationnelles
     * B_λ = 3AmR₀²/(4πλ). Amplitudes thermiques ⟨α_λ²⟩ = T/C_λ (équipartition), pulsations ħω_λ = ħc√(C_λ/B_λ),
     * montrées à la vitesse de la lecture (bornée à 0,4–9 Hz pour rester visible). Null : noyau froid.
     */
    private double[][] surface(Actor a, double[] p, double r, double temperature) {
        if (r < 6 || temperature < .3 && a.bump <= 0 && a.elong <= 0) return null;
        if (a.elong > 0) return cassini(a, p, r);
        int nucleons = a.body.a(), z = a.body.z();
        double r0 = 1.2 * Math.cbrt(nucleons), sigma = 17.23 / (4 * Math.PI * 1.44);
        double[] alpha = new double[4];
        for (int l = 2; l <= 3; l++) {
            double stiff = Math.max(.5, (l - 1) * (l + 2) * r0 * r0 * sigma - 3 / (2 * Math.PI) * (l - 1) / (2.0 * l + 1) * z * z * 1.43996 / r0);
            double inertia = 3 * nucleons * 931.494 * r0 * r0 / (4 * Math.PI * l);
            double omega = 197.3269804 * Math.sqrt(stiff / inertia) / 6.582119569e-22;
            double shown = Math.max(2 * Math.PI * .4, Math.min(2 * Math.PI * 9, omega * physRate));
            double rms = temperature < .3 ? 0 : Math.min(.25, Math.sqrt(temperature / stiff));
            alpha[l] = Math.max(-.35, Math.min(.35, rms * Math.sqrt(2) * Math.sin(shown * anim + l * 1.7 + nucleons)));
        }
        // Juste avant une émission : la surface bombe là où la particule va sortir (direction calculée).
        double[] exit = a.bump > 0 ? project(a.pos.add(a.bumpDir.mul(a.body.radius()))) : null;
        double bumpAngle = exit == null ? 0 : Math.atan2(exit[1] - p[1], exit[0] - p[0]);
        V axis = a.body.spin().unit();
        if (axis.norm() < .1) axis = new V(.3, .5, 1).unit();
        double[] tip = project(a.pos.add(axis.mul(a.body.radius())));
        double axisAngle = tip == null ? 0 : Math.atan2(tip[1] - p[1], tip[0] - p[0]);
        int n = 48;
        double[][] out = new double[2][n];
        for (int k = 0; k < n; k++) {
            double phi = 2 * Math.PI * k / n, c = Math.cos(phi - axisAngle);
            double rr = r * (1 + alpha[2] * (1.5 * c * c - .5) + alpha[3] * (2.5 * c * c * c - 1.5 * c));
            if (exit != null) {
                double gap = Math.atan2(Math.sin(phi - bumpAngle), Math.cos(phi - bumpAngle));
                rr *= 1 + .45 * a.bump * a.bump * Math.exp(-gap * gap / (2 * .3 * .3));
            }
            out[0][k] = p[0] + rr * Math.cos(phi);
            out[1][k] = p[1] + rr * Math.sin(phi);
        }
        return out;
    }

    /**
     * Avant une fission : la descente du point selle vers la scission, en ovales de Cassini (paramétrisation de
     * Pashkevich) le long de l'axe des futurs fragments : (a/b)² croît de 0 (sphère) à ~1 (lemniscate : col nul).
     * Surface conservée (aire de la section normalisée à πR²).
     */
    private double[][] cassini(Actor a, double[] p, double r) {
        double[] tip = project(a.pos.add(a.elongAxis.mul(a.body.radius())));
        double axisAngle = tip == null ? 0 : Math.atan2(tip[1] - p[1], tip[0] - p[0]);
        double k = smooth(a.elong), a2 = .985 * k;
        int n = 72;
        double[] radius = new double[n];
        double area = 0;
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n - axisAngle, c2 = Math.cos(2 * t), s2 = Math.sin(2 * t);
            double rr2 = a2 * c2 + Math.sqrt(Math.max(0, 1 - a2 * a2 * s2 * s2));
            radius[i] = Math.sqrt(Math.max(0, rr2));
            area += .5 * rr2 * 2 * Math.PI / n;
        }
        double scale = r * Math.sqrt(Math.PI / Math.max(1e-6, area));
        double[][] out = new double[2][n];
        for (int i = 0; i < n; i++) {
            double phi = 2 * Math.PI * i / n;
            out[0][i] = p[0] + scale * radius[i] * Math.cos(phi);
            out[1][i] = p[1] + scale * radius[i] * Math.sin(phi);
        }
        return out;
    }

    private void nucleons(Actor a, double[] p, double r, double hot) {
        Body body = a.body;
        V axis = body.spin().unit();
        if (axis.norm() < .1) axis = new V(.24, .5, 1).unit();
        V u = axis.cross(Math.abs(axis.z()) < .9 ? new V(0, 0, 1) : new V(1, 0, 0)).unit(), v = axis.cross(u);
        List<V> points = geometry(body.a());
        double rn = Math.min(.84, body.radius() * .55), bound = Math.max(.1, body.radius() - rn) * .92;
        double turn = anim * .35, ct = Math.cos(turn), st = Math.sin(turn);
        double jitter = rn * (.12 + .5 * hot);
        record N(double x, double y, double depth, boolean proton) {}
        List<N> list = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            V q = points.get(i);
            double qx = q.x() * ct - q.y() * st, qy = q.x() * st + q.y() * ct;
            double jx = jitter * Math.sin(anim * (2.3 + .17 * (i % 7)) + i * 2.1);
            double jy = jitter * Math.sin(anim * (2.9 + .13 * (i % 5)) + i * 1.3);
            double jz = jitter * Math.sin(anim * (2.6 + .11 * (i % 3)) + i * .7);
            V world = a.pos.add(u.mul(qx * bound + jx)).add(v.mul(qy * bound + jy)).add(axis.mul(q.z() * bound + jz));
            double[] s = project(world);
            if (s == null) continue;
            if (a.elong > 0) {
                // Avant la fission, chaque nucléon rejoint le lobe de son côté (le futur fragment) : le col se vide.
                double[] tip = project(a.pos.add(a.elongAxis.mul(body.radius())));
                double ang = tip == null ? 0 : Math.atan2(tip[1] - p[1], tip[0] - p[0]), k = smooth(a.elong);
                double ux = Math.cos(ang), uy = Math.sin(ang), du = (s[0] - p[0]) * ux + (s[1] - p[1]) * uy;
                double dv = -(s[0] - p[0]) * uy + (s[1] - p[1]) * ux;
                du = du * (1 - .35 * k) + Math.signum(du == 0 ? i % 2 - .5 : du) * .95 * k * r;
                dv *= 1 - .3 * k;
                s = new double[]{p[0] + du * ux - dv * uy, p[1] + du * uy + dv * ux, s[2], s[3]};
            }
            boolean proton = ((i + 1) * body.z() / points.size()) > (i * body.z() / points.size());
            list.add(new N(s[0], s[1], s[2], proton));
        }
        list.sort((x, y) -> Double.compare(y.depth(), x.depth()));
        double size = rn * p[3];
        double nearest = list.isEmpty() ? 0 : list.get(list.size() - 1).depth(), farthest = list.isEmpty() ? 1 : list.get(0).depth();
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        for (N n : list) {
            double depth = farthest > nearest ? (n.depth() - nearest) / (farthest - nearest) : 0;
            double alpha = (1 - .45 * depth) * a.alpha * (a.ghost ? .7 : 1);
            if (size < 2.2) {
                gc.setFill((n.proton() ? Color.web("#FF6A55") : Color.web("#B9A8FF")).deriveColor(0, 1, 1, alpha));
                gc.fillOval(n.x() - size, n.y() - size, 2 * size, 2 * size);
                continue;
            }
            Stop[] stops = n.proton()
                    ? new Stop[]{new Stop(0, Color.web("#FFD0C4", alpha)), new Stop(.45, Color.web("#FF5A44", alpha)), new Stop(1, Color.web("#8E1A12", alpha))}
                    : new Stop[]{new Stop(0, Color.web("#F6F2FF", alpha)), new Stop(.45, Color.web("#B4A2FF", alpha)), new Stop(1, Color.web("#4C3AA6", alpha))};
            gc.setFill(new RadialGradient(0, 0, n.x() - size * .35, n.y() - size * .38, size * 1.15, false, CycleMethod.NO_CYCLE, stops));
            gc.fillOval(n.x() - size, n.y() - size, 2 * size, 2 * size);
        }
    }

    private void photon(Actor a) {
        double[] p = project(a.pos);
        if (p == null) return;
        String kind = a.look == null ? "gamma" : a.look.kind();
        Color halo = kind.equals("positron") ? Color.web("#FF7AD9") : kind.equals("neutrino") ? Color.web("#B8F5C8") : Color.web("#FFC24A");
        gc.setGlobalBlendMode(BlendMode.ADD);
        glow(p[0], p[1], kind.equals("neutrino") ? 6 : 10, halo, kind.equals("neutrino") ? .3 : .5);
        glow(p[0], p[1], 2.5, halo.interpolate(Color.WHITE, .7), 1);
    }

    /** Le seul vecteur affiché : le spin intrinsèque local du noyau. */
    private void spinArrow(Actor a) {
        double spin = a.look == null ? 0 : a.look.spin();
        double[] p = project(a.pos);
        if (spin <= 0 || p == null || a.alpha < .2) return;
        double r = a.body.radius() * p[3];
        if (r < 6) return;
        V axis = a.body.spin().unit();
        if (axis.norm() < .1) return;
        double length = a.body.radius() * (1.2 + .35 * Math.sqrt(spin * (spin + 1)));
        double[] tip = project(a.pos.add(axis.mul(length))), tail = project(a.pos.sub(axis.mul(a.body.radius() * 1.1)));
        if (tip == null || tail == null) return;
        gc.setGlobalBlendMode(BlendMode.ADD);
        arrow(tail[0], tail[1], tip[0], tip[1], Color.web("#EAF6FF"), a.alpha * .85, 1.8);
        V e1 = axis.cross(Math.abs(axis.z()) < .9 ? new V(0, 0, 1) : new V(1, 0, 0)).unit(), e2 = axis.cross(e1);
        double[] prev = null;
        for (int k = 0; k <= 36; k++) {
            double phi = 2 * Math.PI * k / 36;
            V q = a.pos.add(axis.mul(a.body.radius() * .9)).add(e1.mul(a.body.radius() * .75 * Math.cos(phi))).add(e2.mul(a.body.radius() * .75 * Math.sin(phi)));
            double[] s = project(q);
            if (s != null && prev != null) {
                double pulse = .5 + .5 * Math.cos(phi - anim * 2.4);
                gc.setStroke(Color.color(.9, .97, 1, .45 * a.alpha * pulse * pulse));
                gc.setLineWidth(1.3);
                gc.strokeLine(prev[0], prev[1], s[0], s[1]);
            }
            prev = s;
        }
    }

    private final WritableImage[] contactImages = new WritableImage[4];
    private final int[] contactPixels = new int[48 * 48];

    /**
     * Le contact nucléaire, calculé : là où les densités de Woods–Saxon de deux noyaux se recouvrent, ρ₁·ρ₂ — l'intégrande
     * du potentiel de convolution par lequel la force forte agit (portée ~1 fm). Lueur dorée, opacité ∝ ρ₁ρ₂ (saturation
     * à ρ₁ρ₂ = 1/2) ; rien n'est dessiné tant que les surfaces diffuses ne se touchent pas.
     */
    private void nuclearContact(List<Actor> actors) {
        int used = 0;
        for (int i = 0; i < actors.size() && used < contactImages.length; i++)
            for (int j = i + 1; j < actors.size() && used < contactImages.length; j++) {
                Actor a = actors.get(i), b = actors.get(j);
                if (a.body.a() == 0 || b.body.a() == 0 || a.alpha < .05 || b.alpha < .05) continue;
                if (a.pos.sub(b.pos).norm() > a.body.radius() + b.body.radius() + 5) continue;
                if (overlap(a, b, used)) used++;
            }
    }

    /** Le rayon à mi-densité c de la Woods–Saxon (diffusivité 0,55 fm) qui reproduit le rayon R : ⟨r²⟩ = 3/5 c² + 7/5 π²a². */
    private static double halfDensity(double radius) {
        double a = .55;
        return Math.sqrt(Math.max(5.0 / 3 * (.6 * radius * radius - 1.4 * Math.PI * Math.PI * a * a), .09));
    }

    private boolean overlap(Actor a, Actor b, int slot) {
        double[] pa = project(a.pos), pb = project(b.pos);
        if (pa == null || pb == null) return false;
        double diffuse = .55, ca = halfDensity(a.body.radius()), cb = halfDensity(b.body.radius());
        double gap = Math.hypot(pb[0] - pa[0], pb[1] - pa[1]), scale = (pa[3] + pb[3]) / 2;
        double t = gap > 1e-6 ? Math.max(0, Math.min(1, (ca * pa[3] + (gap - ca * pa[3] - cb * pb[3]) / 2) / gap)) : .5;
        double cx = pa[0] + (pb[0] - pa[0]) * t, cy = pa[1] + (pb[1] - pa[1]) * t;
        double half = (Math.min(ca, cb) + 3.2) * scale;
        if (half < 1.5) return false;
        int size = 48;
        double peak = 0, fade = Math.min(a.alpha, b.alpha);
        for (int j = 0; j < size; j++) {
            double y = cy - half + (j + .5) * 2 * half / size;
            for (int i = 0; i < size; i++) {
                double x = cx - half + (i + .5) * 2 * half / size;
                double ra = Math.hypot(x - pa[0], y - pa[1]) / pa[3], rb = Math.hypot(x - pb[0], y - pb[1]) / pb[3];
                double v = 1 / (1 + Math.exp((ra - ca) / diffuse)) / (1 + Math.exp((rb - cb) / diffuse));
                peak = Math.max(peak, v);
                double alpha = Math.min(1, 2 * v) * .6 * fade;
                contactPixels[j * size + i] = (int) (alpha * 255) << 24 | (int) (alpha * 255) << 16
                        | (int) (alpha * (.74 + .26 * v) * 255) << 8 | (int) (alpha * (.36 + .64 * v) * 255);
            }
        }
        if (peak < 2e-3) return false;
        if (contactImages[slot] == null) contactImages[slot] = new WritableImage(size, size);
        contactImages[slot].getPixelWriter().setPixels(0, 0, size, size, PixelFormat.getIntArgbPreInstance(), contactPixels, 0, size);
        gc.setGlobalBlendMode(BlendMode.ADD);
        gc.setImageSmoothing(true);
        gc.drawImage(contactImages[slot], cx - half, cy - half, 2 * half, 2 * half);
        return true;
    }

    /** J = j₁ + j₂ + ℓ : les spins des deux noyaux et le moment orbital s'ajoutent en celui du noyau composé. */
    private void spinAddition(CollisionRun run, CollisionTimeline timeline, Snapshot pre, CollisionTimeline.Stage stage, double u) {
        if (pre == null || pre.bodies().size() != 2) return;
        double visible = stage.kind() == CollisionTimeline.Kind.MERGE || stage.name().equals("merge") ? smooth((u - .55) / .2) : 1 - smooth((u - .5) / .25);
        if (visible < .02) return;
        double unit = Math.max(mergeReach(run), extent * .3) * .16;
        double k = Math.sqrt(2 * Math.max(run.getReducedMassMev(), 1) * run.getEnergyKev() / 1000) / 197.3269804;
        double ell = Math.round(k * run.getImpactFm());
        V cursor = ZERO, total = ZERO;
        StringBuilder text = new StringBuilder("J = ");
        for (int i = 0; i < 2; i++) {
            Look look = pre.look(i);
            double j = look == null ? 0 : look.spin();
            V axis = pre.bodies().get(i).spin().unit();
            if (j <= 0 || axis.norm() < .1) continue;
            V vec = axis.mul(j * unit);
            draw3DArrow(cursor, cursor.add(vec), bodyColor(pre.bodies().get(i).z(), pre.bodies().get(i).a()), .9 * visible);
            cursor = cursor.add(vec);
            total = total.add(axis.mul(j));
            text.append(ReactorView.spinText(j)).append(" + ");
        }
        if (ell > 0) {
            V vec = new V(0, 0, 1).mul(Math.min(ell, 8) * unit);
            draw3DArrow(cursor, cursor.add(vec), Color.web("#9BE7C4"), .9 * visible);
            total = total.add(new V(0, 0, ell));
            text.append("ℓ ").append((int) ell).append(" + ");
        }
        if (total.norm() > 1e-6) {
            V shown = total.unit().mul(Math.min(total.norm(), 8) * unit);
            draw3DArrow(ZERO, shown, Color.WHITE, visible);
            double[] tip = project(shown.mul(1.1));
            report.spinSum = tip;
        }
        report.spinText = text.length() > 4 ? text.substring(0, text.length() - 3) : "";
    }

    private void draw3DArrow(V from, V to, Color color, double alpha) {
        double[] a = project(from), b = project(to);
        if (a == null || b == null) return;
        gc.setGlobalBlendMode(BlendMode.ADD);
        arrow(a[0], a[1], b[0], b[1], color, alpha, 2.4);
    }

    /**
     * Les traces d'ionisation, comme dans une chambre à bulles : un corps chargé arrache des électrons au plasma tout au
     * long de son trajet calculé, un corps neutre n'en arrache aucun — le neutron et le photon ne laissent rien. L'épaisseur
     * et l'éclat suivent le pouvoir d'arrêt dE/dx ∝ Z²/v² du plasma traversé (voir {@link PlasmaInset#stoppingPower}) :
     * le fragment lourd et lent creuse un sillon large, le proton rapide un trait fin. Sans plasma (vide), rien n'est tracé.
     */
    private void ionisationTracks(CollisionRun run, CollisionTimeline timeline, double fraction, CollisionTimeline.Stage stage) {
        if (electronDensityM3 <= 0 || stage.kind() != CollisionTimeline.Kind.SAMPLES) return;
        double now = timeline.at(fraction).time();
        Snapshot head = snapshot(run, now);
        int n = head.bodies().size();
        if (n == 0 || n > 24) return;
        int last = stage.first();
        while (last < stage.last() && run.getSamples(last + 1).getTimeS() <= now) last++;
        double[] power = new double[n];
        double strongest = 0;
        for (int i = 0; i < n; i++) {
            Body b = head.bodies().get(i);
            if (b.z() <= 0 || b.a() <= 0) continue;
            double speed = b.velocity().norm();
            power[i] = PlasmaInset.stoppingPower(b.z(), speed, electronDensityM3, debyeFm * 1e-15);
            strongest = Math.max(strongest, power[i]);
            double mass = Math.max(1, b.a()) * 931.494, beta = Math.min(.999999, speed / C);
            double kinetic = (1 / Math.sqrt(1 - beta * beta) - 1) * mass;
            if (power[i] > 0) report.tracks.add(new double[]{b.z(), b.a(), power[i], kinetic * 1.602176634e-13 / power[i], kinetic});
        }
        if (strongest <= 0) return;
        gc.setGlobalBlendMode(BlendMode.ADD);
        for (int i = 0; i < n; i++) {
            if (power[i] <= 0) continue;
            Body b = head.bodies().get(i);
            double level = Math.cbrt(power[i] / strongest);
            Color color = bodyColor(b.z(), b.a());
            double[] previous = null;
            for (int k = stage.first(); k <= last + 1; k++) {
                CollisionSample s = k > last ? null : run.getSamples(k);
                double[] q = s == null ? project(head.bodies().get(i).position())
                        : s.getBodiesCount() == n ? project(vector(s.getBodies(i).getPositionList())) : null;
                if (q == null) {
                    previous = null;
                    continue;
                }
                if (previous != null) {
                    double[] c = clip(previous[0], previous[1], q[0], q[1]);
                    if (c != null) {
                        gc.setStroke(color.deriveColor(0, 1, 1, .14 + .36 * level));
                        gc.setLineWidth(1.1 + 5.2 * level);
                        gc.strokeLine(c[0], c[1], c[2], c[3]);
                        gc.setStroke(color.interpolate(Color.WHITE, .5).deriveColor(0, 1, 1, .3 + .5 * level));
                        gc.setLineWidth(.8 + 1.3 * level);
                        gc.strokeLine(c[0], c[1], c[2], c[3]);
                    }
                }
                previous = q;
            }
        }
    }

    /**
     * Le segment ramené à l'écran (plus une marge) — Liang–Barsky. Un produit parti à des centimètres donnerait sinon
     * des traits de 10¹³ px que la carte graphique devrait quand même découper. Null : rien de visible.
     */
    private double[] clip(double x0, double y0, double x1, double y1) {
        double m = 40, t0 = 0, t1 = 1, dx = x1 - x0, dy = y1 - y0;
        double[] pe = {-dx, dx, -dy, dy}, qe = {x0 + m, width + m - x0, y0 + m, height + m - y0};
        for (int i = 0; i < 4; i++) {
            if (pe[i] == 0) {
                if (qe[i] < 0) return null;
            } else {
                double r = qe[i] / pe[i];
                if (pe[i] < 0) t0 = Math.max(t0, r);
                else t1 = Math.min(t1, r);
            }
        }
        return t0 > t1 ? null : new double[]{x0 + t0 * dx, y0 + t0 * dy, x0 + t1 * dx, y0 + t1 * dy};
    }

    /**
     * Le champ magnétique externe du tir, suivant +z : la convention universelle d'un champ uniforme — une croix s'il
     * s'enfonce derrière l'écran, un point s'il en sort, un tiret dans sa direction projetée s'il est de biais. Le pas
     * de la trame est celui de la vue (une marque tous les ~64 px) ; l'opacité suit |B·n̂|.
     */
    private void externalFieldGrid() {
        if (externalFieldT <= 0) return;
        double[] forward = camera.forward();
        double along = -forward[2];
        V b = new V(0, 0, externalFieldT);
        double[] origin = project(ZERO), tip = project(b.unit().mul(extent * .25));
        double dx = 0, dy = 0;
        if (origin != null && tip != null) {
            dx = tip[0] - origin[0];
            dy = tip[1] - origin[1];
        }
        double flat = Math.hypot(dx, dy), pitch = 46, arm = 2.1;
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        gc.setLineWidth(.9);
        double midX = width / 2, midY = height / 2, half = Math.hypot(midX, midY);
        // Entre l'en-tête et la barre de lecture, et fondue vers les bords : elle structure l'espace sans le charger.
        for (double y = 66; y < height - 96; y += pitch)
            for (double x = pitch / 2; x < width; x += pitch) {
                double edge = 1 - Math.min(1, Math.hypot(x - midX, y - midY) / half);
                double fade = (.05 + .1 * Math.abs(along)) * (.35 + .65 * edge);
                gc.setStroke(Color.color(.66, .82, 1, fade));
                gc.setFill(Color.color(.72, .86, 1, fade * 1.7));
                if (Math.abs(along) > .55) {
                    if (along > 0) gc.fillOval(x - .7, y - .7, 1.4, 1.4);
                    else {
                        gc.strokeLine(x - arm, y - arm, x + arm, y + arm);
                        gc.strokeLine(x - arm, y + arm, x + arm, y - arm);
                    }
                } else if (flat > 1e-6) {
                    gc.strokeLine(x - dx / flat * arm, y - dy / flat * arm, x + dx / flat * arm, y + dy / flat * arm);
                }
            }
        if (!legendWanted) return;
        // La légende, une fois : la convention et la valeur du champ.
        gc.setFont(javafx.scene.text.Font.font("Segoe UI", 10));
        gc.setFill(Color.color(.72, .86, 1, .55));
        gc.fillText((Math.abs(along) > .55 ? along > 0 ? "⊙" : "⊗" : "—") + "  B = "
                + ReactorView.fmt(externalFieldT) + " T" + (Math.abs(along) > .55 ? along > 0 ? " · vers vous" : " · vers le fond" : " · dans le plan"),
                16, height - 118);
    }

    // ------------------------------------------------------------------ éclairs
    /**
     * Exposition visuelle des photons gamma calculés par Python. Le noyau spatial est
     * I(r,t) = I0/[1+(r/R0)^2] exp(-t/tau), I0 proportionnel à l'énergie du photon.
     * tau est la persistance de la vue (0,12 s de lecture), pas une durée de vie physique.
     * Une scission sans photon gamma ne produit aucun flash ni onde fictive.
     */
    private void drawRadiativeFlash(CollisionTimeline timeline, double fraction) {
        List<double[]> active = new ArrayList<>();
        for (double[] e : emissions) {
            if (e[18] <= 0) continue;
            double age = (fraction - e[0]) * timeline.durationSeconds();
            if (age < 0 || age > .7) continue;
            double[] p = project(report.map ? ZERO : new V(e[1], e[2], e[3]));
            if (p != null && p[3] > 0) active.add(new double[]{p[0], p[1], Math.max(2, e[19] * p[3]),
                    .12 * e[18] * Math.exp(-age / .12)});
        }
        if (active.isEmpty()) return;
        int gw = (int) Math.max(48, Math.ceil(width / 4)), gh = (int) Math.max(27, Math.ceil(height / 4));
        if (flashImage == null || (int) flashImage.getWidth() != gw || (int) flashImage.getHeight() != gh) {
            flashPixels = new int[gw * gh];
            flashBuffer = new PixelBuffer<>(gw, gh, IntBuffer.wrap(flashPixels), PixelFormat.getIntArgbPreInstance());
            flashImage = new WritableImage(flashBuffer);
        }
        double sx = width / gw, sy = height / gh;
        for (int j = 0; j < gh; j++) {
            double y = (j + .5) * sy;
            for (int i = 0; i < gw; i++) {
                double x = (i + .5) * sx, intensity = 0;
                for (double[] g : active) {
                    double dx = (x - g[0]) / g[2], dy = (y - g[1]) / g[2];
                    intensity += g[3] / (1 + dx * dx + dy * dy);
                }
                int alpha = (int) (255 * Math.min(.82, intensity));
                flashPixels[j * gw + i] = alpha << 24 | alpha << 16 | (int) (.94 * alpha) << 8 | (int) (.77 * alpha);
            }
        }
        flashBuffer.updateBuffer(buffer -> null);
        gc.setGlobalBlendMode(BlendMode.ADD);
        gc.setImageSmoothing(true);
        gc.drawImage(flashImage, 0, 0, width, height);
    }

    // ------------------------------------------------------------------ outils
    private void glow(double x, double y, double radius, Color color, double alpha) {
        if (radius < .5 || alpha <= .003) return;
        gc.setFill(new RadialGradient(0, 0, x, y, radius, false, CycleMethod.NO_CYCLE,
                new Stop(0, color.deriveColor(0, 1, 1, Math.min(1, alpha))),
                new Stop(.35, color.deriveColor(0, 1, 1, Math.min(1, alpha) * .35)),
                new Stop(1, color.deriveColor(0, 1, 1, 0))));
        gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);
    }

    private void arrow(double x0, double y0, double x1, double y1, Color color, double alpha, double width) {
        double dx = x1 - x0, dy = y1 - y0, len = Math.hypot(dx, dy);
        if (len < 2 || alpha < .01) return;
        double ux = dx / len, uy = dy / len, head = Math.min(12, len * .35);
        gc.setStroke(color.deriveColor(0, 1, 1, Math.min(1, alpha * .25)));
        gc.setLineWidth(width + 5);
        gc.strokeLine(x0, y0, x1, y1);
        gc.setStroke(color.deriveColor(0, 1, 1, Math.min(1, alpha)));
        gc.setLineWidth(width);
        gc.strokeLine(x0, y0, x1 - ux * head * .6, y1 - uy * head * .6);
        gc.setFill(color.deriveColor(0, 1, 1, Math.min(1, alpha)));
        gc.fillPolygon(new double[]{x1, x1 - ux * head - uy * head * .5, x1 - ux * head + uy * head * .5},
                new double[]{y1, y1 - uy * head + ux * head * .5, y1 - uy * head - ux * head * .5}, 3);
    }
}
