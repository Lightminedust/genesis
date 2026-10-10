package org.genesis;

import com.studio.genesis.grpc.CollisionBody;
import com.studio.genesis.grpc.CollisionRun;
import com.studio.genesis.grpc.CollisionSample;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.paint.Color;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import java.util.*;
import static org.genesis.FusionField.*;

/**
 * La scène de collision, dessinée avec les primitives de JavaFX (rendues par la carte graphique, à la résolution de
 * l'écran) en lumière additive. Tout ce qui est montré vient d'un calcul :
 * <ul>
 * <li>positions, vitesses, temps : les échantillons du moteur (trajectoire sous Coulomb + force nucléaire) ;</li>
 * <li>champ électrique : la carte de sa densité d'énergie ε₀E²/2 (champ total des sphères chargées, superposé), en
 *     échelle logarithmique sur six décades, ancrée sur la pression au contact ;</li>
 * <li>lignes magnétiques : les dipôles des spins (moments mesurés), dans le repère des noyaux ;</li>
 * <li>nuage de chaque noyau : densité de Woods–Saxon (diffusivité 0,55 fm) intégrée le long du regard ; autour,
 *     l'onde : paquet de largeur ƛ = ħ/p, rides de longueur d'onde de de Broglie locale h/p ;</li>
 * <li>effet tunnel : l'amplitude WKB à travers la barrière, puis le noyau réapparaît au contact.</li>
 * </ul>
 * Les distances et les tailles sont à la même échelle (aucun grossissement des noyaux).
 */
final class FusionRenderer {
    /** Ce que le moteur dit d'un corps en plus de sa position. */
    record Look(String color, double excitation, double spin, String kind, String name, double mass) {
        Look(String color, double excitation, double spin, String kind, String name) {
            this(color, excitation, spin, kind, name, 0);
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
        /** L'échelle de la carte ε₀E²/2 (J/m³) : du transparent (bord du cadre) au blanc (pression au contact). */
        double pressureMin, pressureMax;
        /** Vitesse relative des deux noyaux (fraction de c), pendant l'approche, le rebond et la soudure ; 0 sinon. */
        double speed;
        final List<Tag> tags = new ArrayList<>();
        /** Les charges telles que dessinées (même géométrie que les halos) : ce que la sonde interroge. */
        final List<Source> sources = new ArrayList<>();
        /** Les fronts des ruptures visibles : la sphère qu'atteignent les produits les plus rapides. */
        final List<Front> fronts = new ArrayList<>();
        double[] spinSum;
        String spinText = "";
    }

    /**
     * Une charge à l'écran : centre (px), fm par pixel à sa profondeur, charge effective Z·α (α : son opacité pendant une
     * soudure), rayon (fm) ; le corps qu'elle représente.
     */
    record Source(double x, double y, double fmPerPx, double charge, double radius, Body body, Look look, double beta2) {}

    /**
     * Le front d'une rupture : centre et rayon à l'écran, rayon physique R = v·(t − t₀) (fm), énergie libérée (MeV),
     * vitesse des plus rapides (fraction de c), âge t − t₀ (s), nombre de corps après la rupture.
     */
    record Front(double x, double y, double radiusPx, double bandPx, double radiusFm, double releasedMev, double beta, double ageS, int bodies) {}

    /** Un noyau à l'écran ; beta2 : sa déformation par le champ de l'autre (polarisation de Coulomb), 0 sinon. */
    record Tag(double x, double y, double radius, Body body, Look look, double alpha, double beta2) {}

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
    double zoom = 1, extent = 40;
    /** Après la fusion, l'élément que la vue suit (Z, A) ; −1 : l'élément central créé (le plus lourd). */
    int focusZ = -1, focusA = -1;
    /** Horloge d'animation (s) : flux le long des lignes, rides de l'onde, agitation des nucléons. */
    double anim;
    final Report report = new Report();
    private GraphicsContext gc;
    private double width, height;
    private static final Map<Integer, String> ELEMENT_COLORS = new HashMap<>();
    private static final double[][] DUST = dust();
    private static final double FOV_TAN = Math.tan(Math.toRadians(22));
    private final Map<Integer, List<V>> nucleonGeometry = new HashMap<>();
    private final Map<Long, Stop[]> densityProfiles = new HashMap<>();
    private CollisionRun preparedRun;
    private CollisionTimeline preparedTimeline;
    private double reachApproach = 40, startSeparation = 1;
    // La carte ε₀E²/2 : une petite image (un quart de la résolution) que la carte graphique lisse en l'étirant.
    private WritableImage pressureImage;
    private int[] pressurePixels = new int[0];
    /** (Σ Z/R²)² des deux réactifs, en (k·e/fm²)² : la pression au contact, haut de l'échelle. */
    private double pressurePeak = 1;
    /** Secondes physiques par seconde de lecture, dans l'étape montrée (0 : étape sans durée). */
    private double physRate;
    private double shakeX, shakeY;
    private final List<double[]> emissions = new ArrayList<>();
    // Le cadrage a de l'inertie : un saut de cadrage devient un écart qui se résorbe (ressort amorti).
    private boolean snapCamera = true;
    private double lastAnim = Double.NaN, lastFraction, goalLogReach = Double.NaN, offsetLog, offsetLogVel;
    private String lastTarget = "";
    private V goalAim = ZERO, offsetAim = ZERO, offsetAimVel = ZERO, shownAim = ZERO, cameraVelocity = ZERO;

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
                b.getMassMev());
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
                x = new Body(x.z(), x.a(), x.label(), x.position().mul(1 - t).add(y.position().mul(t)),
                        x.velocity().mul(1 - t).add(y.velocity().mul(t)), x.spin().mul(1 - t).add(y.spin().mul(t)).unit(),
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
        snapCamera = true;
        reachApproach = 15;
        CollisionSample first = run.getSamples(0);
        for (CollisionBody b : first.getBodiesList()) {
            V p = vector(b.getPositionList());
            reachApproach = Math.max(reachApproach, p.norm() + 1.3 * b.getRadiusFm());
        }
        startSeparation = Math.max(1, separation(sample(run, 0).bodies()));
        pressurePeak = peakOf(sample(run, 0).bodies());
        // Les émissions calculées : chaque fois qu'un noyau chaud se défait, un éclair à l'endroit où il était.
        emissions.clear();
        for (int i = 1; i < run.getSamplesCount(); i++) {
            CollisionSample a = run.getSamples(i - 1), b = run.getSamples(i);
            String ph = b.getPhase();
            if (!(ph.equals("evaporation") || ph.equals("products") || ph.equals("compound"))) continue;
            if (a.getBodiesCount() == 0 || b.getBodiesCount() <= a.getBodiesCount()) continue;
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
            V axis = fission ? vector(last.getPositionList()).sub(vector(other.getPositionList())).unit() : out;
            emissions.add(new double[]{timeline.fractionAt(b.getTimeS()), at.x(), at.y(), at.z(), c[0], c[1], c[2],
                    light.getA() > 4 ? 1.8 : 1, b.getTimeS(), fastest > 0 ? fastest : 1, released,
                    axis.x(), axis.y(), axis.z(), fission ? 1 : 0, last.getA() + other.getA(), last.getZ() + other.getZ(), b.getBodiesCount()});
        }
    }

    /** (Σ Z/R²)² : le champ des deux noyaux qui se touchent, à leur surface, quand il s'ajoute (en k·e/fm²). */
    private static double peakOf(List<Body> bodies) {
        double sum = 0;
        for (Body b : bodies) if (b.z() > 0 && b.a() > 0) sum += b.z() / Math.pow(Math.max(.5, b.radius()), 2);
        return Math.max(1e-6, sum * sum);
    }

    // ================================================================== le dessin
    /** Les deux noyaux choisis, avant le tir. */
    void paintPortrait(GraphicsContext g, double w, double h, Snapshot s) {
        begin(g, w, h);
        double reach = 15;
        for (Body b : s.bodies()) reach = Math.max(reach, b.position().norm() + 2.6 * b.radius());
        pressurePeak = peakOf(s.bodies());
        physRate = 0;
        shownAim = ZERO;
        cameraVelocity = ZERO;
        snapCamera = true;
        setupCamera(reach, ZERO);
        background(reach);
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
        CollisionTimeline.At at = timeline.at(fraction);
        CollisionTimeline.Stage stage = at.stage();
        double u = at.local();
        report.stage = stage.name();
        report.local = u;
        report.time = at.time();
        Snapshot pre = timeline.lastApproach() >= 0 ? sample(run, timeline.lastApproach()) : null;
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
                // La vue zoome à mesure qu'ils se rapprochent, moins vite qu'eux (∝ r^0,75) : on les voit encore foncer
                // l'un vers l'autre et freiner, et l'on arrive au plus près pour le contact.
                // La polarisation de Coulomb : chaque noyau, goutte liquide, est comprimé le long de l'axe par le champ de l'autre.
                for (int i = 0; i < 2; i++) {
                    Actor self = actors.get(i), other = actors.get(1 - i);
                    if (self.body.a() < 2) continue;
                    self.beta2 = coulombBeta2(self.body.z(), self.body.a(), other.body.z(), sep);
                    self.squash = 1 + .631 * self.beta2;
                    self.squashAxis = other.pos.sub(self.pos).unit();
                }
                // Dans la zone de freinage (moins de 2,5 fois la distance minimale), le zoom s'arrête : sinon le grossissement
                // compenserait le ralentissement et le noyau paraîtrait glisser à vitesse constante jusqu'au choc.
                double brake = 2.5 * Math.max(turn, run.getContactFm());
                reach = Math.max(closeReach(pre, run), reachApproach * Math.pow(Math.max(sep, brake) / startSeparation, .75));
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
                reach = ease(Math.min(1, u / .6), closeReach(pre, run), mergeReach(run));
            } else if (stage.name().equals("contact")) {
                double total = Math.max(1e-12, state.kinetic() + state.potential());
                conversion = Math.max(0, Math.min(1, state.potential() / total));
                strong = Math.min(1, Math.abs(state.nuclear()) / Math.max(Math.abs(state.coulomb()), 1e-30));
                report.kinetic = state.kinetic();
                report.potential = state.potential();
                reach = Math.max(2.4 * maxRadius(actors), 1.2 * separation(state.bodies()));
                reach = ease(Math.min(1, u / .3), closeReach(pre, run), reach);
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
            double close = closeReach(pre, run);
            if (stage.kind() == CollisionTimeline.Kind.TUNNEL) {
                conversion = 1;
                reach = close;
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
                reach = melt ? mergeReach(run) : ease(Math.min(1, u / .5), close, mergeReach(run));
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
        report.conversion = conversion;
        double sep = actors.size() >= 2 ? actors.get(0).pos.sub(actors.get(1).pos).norm() : 0;
        report.separation = stage.kind() == CollisionTimeline.Kind.SAMPLES && state != null && state.bodies().size() == 2
                ? separation(state.bodies()) : sep;
        physRate = stage.kind() == CollisionTimeline.Kind.SAMPLES && stage.seconds() > 0
                ? (run.getSamples(stage.last()).getTimeS() - run.getSamples(stage.first()).getTimeS()) / stage.seconds() : 0;
        // Une rupture libère son énergie d'un coup : l'image tressaille, d'autant plus que l'énergie est grande.
        for (double[] e : emissions) {
            double age = (fraction - e[0]) * timeline.durationSeconds();
            if (age < 0 || age > .3) continue;
            double amp = Math.min(6, 1.6 * Math.log10(1 + e[10] / .1)) * Math.exp(-age / .08);
            shakeX += amp * Math.sin(anim * 61 + e[0] * 97);
            shakeY += amp * Math.cos(anim * 47 + e[0] * 53);
        }
        follow(reach, aim, target0, fraction);
        background(reach);
        // Le monde tremble derrière la vitre ; les étiquettes (calculées sans ce décalage) restent fixes.
        gc.translate(shakeX, shakeY);
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
        if (stage.name().equals("products") || stage.name().equals("evaporation")) streaks(actors);
        drawEmissions(timeline, fraction);
        boolean welding = stage.kind() == CollisionTimeline.Kind.MERGE || stage.name().equals("merge") && timeline.phaseStart("relax") < 0;
        if (welding && u > .55 || stage.name().equals("compound") && u < .75) spinAddition(run, timeline, pre, stage, u);
        end();
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
            double[] l = damp(offsetLog, offsetLogVel, .45, dt);
            offsetLog = l[0];
            offsetLogVel = l[1];
            double[] x = damp(offsetAim.x(), offsetAimVel.x(), .45, dt), y = damp(offsetAim.y(), offsetAimVel.y(), .45, dt),
                    z = damp(offsetAim.z(), offsetAimVel.z(), .45, dt);
            offsetAim = new V(x[0], y[0], z[0]);
            offsetAimVel = new V(x[1], y[1], z[1]);
        }
        goalLogReach = logReach;
        goalAim = aim;
        V shown = aim.add(offsetAim);
        cameraVelocity = dt > 0 && dt <= .5 ? shown.sub(shownAim).mul(1 / dt) : ZERO;
        shownAim = shown;
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
        report.fronts.clear();
        report.speed = 0;
        report.spinSum = null;
        report.spinText = "";
        report.tunnelAmplitude = Double.NaN;
        report.heat = 0;
        shakeX = shakeY = 0;
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
    private static double[][] dust() {
        Random random = new Random(17);
        double[][] out = new double[300][5];
        for (double[] d : out) {
            d[0] = random.nextDouble();
            d[1] = random.nextDouble();
            d[2] = random.nextDouble();
            d[3] = random.nextDouble();
            d[4] = random.nextDouble() * Math.PI * 2;
        }
        return out;
    }

    private void background(double reach) {
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        gc.setFill(new RadialGradient(0, 0, width * .5, height * .48, Math.max(width, height) * .75, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0C1830")), new Stop(.55, Color.web("#060C18")), new Stop(1, Color.web("#020409"))));
        gc.fillRect(0, 0, width, height);
        gc.setGlobalBlendMode(BlendMode.ADD);
        Random random = new Random(5);
        for (int i = 0; i < 16; i++) {
            double x = random.nextDouble() * width, y = random.nextDouble() * height, r = 20 + random.nextDouble() * 70;
            boolean warm = random.nextDouble() < .35;
            glow(x, y, r, warm ? Color.web("#FF8A6A") : Color.web("#6FA8FF"), .035 + .03 * random.nextDouble());
        }
        double level = Math.log(3.2 * extent) / Math.log(2);
        int k = (int) Math.floor(level);
        grains(Math.pow(2, k + 1), 1 - (level - k));
        grains(Math.pow(2, k + 2), level - k);
    }

    /**
     * Des grains immobiles dans le repère du centre de masse : un motif répété tous les `period` fm. Ils ne bougent
     * pas, c'est la caméra qui passe ; quand elle accompagne un noyau lancé, ils filent en traits (pose de 1/24 s de
     * lecture) — la vitesse se voit, et le freinage aussi.
     */
    private void grains(double period, double weight) {
        if (weight < .03) return;
        double[] center = camera.project(shownAim.x(), shownAim.y(), shownAim.z());
        double unit = center == null ? 1 : center[3];
        V drift = cameraVelocity.mul(1 / 24.0);
        boolean streak = drift.norm() * unit > 1.5;
        for (double[] d : DUST) {
            double x = d[0] * period, y = d[1] * period, z = d[2] * period;
            x += period * Math.rint((shownAim.x() - x) / period);
            y += period * Math.rint((shownAim.y() - y) / period);
            z += period * Math.rint((shownAim.z() - z) / period);
            double dx = x - shownAim.x(), dy = y - shownAim.y(), dz = z - shownAim.z();
            double r = Math.sqrt(dx * dx + dy * dy + dz * dz) / (period * .5);
            if (r >= 1) continue;
            double[] p = camera.project(x, y, z);
            if (p == null || p[0] < -4 || p[0] > width + 4 || p[1] < -4 || p[1] > height + 4) continue;
            double twinkle = .7 + .3 * Math.sin(anim * (1.1 + d[3]) + d[4]);
            double alpha = .5 * twinkle * weight * (1 - r * r * r * r);
            double size = Math.max(.7, Math.min(2.6, 1.4 * p[3] / unit));
            Color color = Color.color(.65 + .3 * d[3], .75, 1 - .25 * d[3]);
            double[] q = streak ? camera.project(x + drift.x(), y + drift.y(), z + drift.z()) : null;
            if (q != null) {
                double length = Math.hypot(q[0] - p[0], q[1] - p[1]);
                double[] c = clip(q[0], q[1], p[0], p[1]);
                if (c == null) continue;
                gc.setStroke(color.deriveColor(0, 1, 1, Math.min(1, alpha * Math.sqrt(Math.min(1, 14 / Math.max(1, length))) * 1.6)));
                gc.setLineWidth(size * 1.1);
                gc.strokeLine(c[0], c[1], c[2], c[3]);
            } else {
                gc.setFill(color.deriveColor(0, 1, 1, Math.min(1, alpha)));
                gc.fillOval(p[0] - size / 2, p[1] - size / 2, size, size);
            }
        }
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
        if (showMagnetic) magneticLines(actors, conversion);
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
        if (strong > .02 && charged.size() == 2) strongTubes(charged.get(0), charged.get(1), strong);
        else if (strong > .02 && actors.size() >= 2) strongTubes(actors.get(0), actors.get(actors.size() - 1), strong);
        List<Actor> order = new ArrayList<>(actors);
        order.sort(Comparator.comparingDouble((Actor a) -> {
            double[] p = project(a.pos);
            return p == null ? 0 : -p[2];
        }));
        for (Actor a : order) {
            if (a.body.a() == 0) photon(a);
            else nucleus(a);
        }
        for (Actor a : order) if (a.body.a() > 0) spinArrow(a);
    }

    // ------------------------------------------------------------------ champ électrique
    /**
     * Le champ électrique de chaque noyau, comme une brume à sa couleur. Pour chaque cellule d'une grille au quart de la
     * résolution : le champ E_i de chaque noyau (sphère uniformément chargée : Z/r² dehors, Z·r/R³ dedans), et leur somme.
     * <ul>
     * <li>chaque halo : la densité d'énergie de son propre champ, ε₀|E_i|²/2, sur quatre décades sous sa surface
     *     (V_i = log₁₀(1 + 10⁴ u_i/u_surface)/4) ; son opacité croît avec Z — l'uranium écrase, le tritium est translucide ;</li>
     * <li>la teinte : celle du noyau dont le halo domine, selon sa charge Z. Les couleurs ne se mêlent pas, elles se
     *     rencontrent : la frontière, c'est le halo lui-même ;</li>
     * <li>le heurt : là où deux champs se font face (E₁·E₂ < 0), la zone blanchit de cos²·min(V₁, V₂) — l'opposition
     *     réelle des deux champs, là où les deux sont encore présents : une coque épaisse devant le petit noyau, mince sur
     *     les flancs (le signe du terme d'interaction ε₀E₁·E₂ de l'énergie du champ).</li>
     * </ul>
     * Par-dessus, des grains de champ partent de chaque noyau et suivent le champ total (voir {@link #tracers}).
     */
    private void pressureMap(List<Actor> actors) {
        List<double[]> q = new ArrayList<>();
        for (Actor a : actors) {
            if (a.body.z() <= 0 || a.body.a() <= 0 || a.alpha < .02) continue;
            double[] p = project(a.pos);
            if (p == null || p[3] <= 0) continue;
            q.add(new double[]{p[0], p[1], 1 / p[3], a.body.z() * a.alpha, Math.max(.5, a.body.radius()), a.alpha, 0, 0, 0, a.body.z()});
        }
        // Les teintes : selon Z ; à charges voisines, A plus froid et B plus chaud, pour qu'on les distingue.
        for (int i = 0; i < q.size(); i++) {
            double shift = 0;
            for (int j = 0; j < q.size(); j++)
                if (j != i && Math.abs(Math.log(q.get(i)[9] / q.get(j)[9])) < Math.log(1.6)) shift = i < j ? -.1 : .35;
            Color c = haloColor(q.get(i)[9], shift);
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
            pressureImage = new WritableImage(gw, gh);
            pressurePixels = new int[gw * gh];
        }
        int n = q.size();
        double[] fx = new double[n], fy = new double[n], m2 = new double[n], vk = new double[n], floorK = new double[n], opacity = new double[n];
        for (int k = 0; k < n; k++) {
            double surface = q.get(k)[9] / (q.get(k)[4] * q.get(k)[4]);
            floorK[k] = surface * surface * 1e-4;
            opacity[k] = .45 + .55 * Math.max(0, Math.min(1, Math.log(q.get(k)[9]) / Math.log(92)));
        }
        double norm = 1 / Math.log10(1 + 1e4), sx = width / gw, sy = height / gh;
        for (int j = 0; j < gh; j++) {
            double y = (j + .5) * sy;
            for (int i = 0; i < gw; i++) {
                double x = (i + .5) * sx, w = 0, r = 0, g = 0, b = 0, v = 0, halo = 0;
                int first = -1, second = -1;
                for (int k = 0; k < n; k++) {
                    double[] c = q.get(k);
                    double dx = (x - c[0]) * c[2], dy = (y - c[1]) * c[2];
                    double m = Math.max(Math.sqrt(dx * dx + dy * dy), c[4]), f = c[3] / (m * m * m);
                    fx[k] = f * dx;
                    fy[k] = f * dy;
                    m2[k] = fx[k] * fx[k] + fy[k] * fy[k];
                    vk[k] = Math.max(0, Math.min(1, Math.log10(1 + m2[k] / floorK[k]) * norm)) * c[5];
                    double weight = vk[k] * vk[k] * vk[k];
                    w += weight;
                    r += weight * c[6];
                    g += weight * c[7];
                    b += weight * c[8];
                    v = Math.max(v, vk[k]);
                    halo = Math.max(halo, Math.pow(vk[k], 1.3) * opacity[k]);
                    if (first < 0 || vk[k] > vk[first]) {
                        second = first;
                        first = k;
                    } else if (second < 0 || vk[k] > vk[second]) second = k;
                }
                double clash = 0;
                if (second >= 0 && m2[second] > 0 && m2[first] > 0) {
                    double dot = fx[first] * fx[second] + fy[first] * fy[second];
                    if (dot < 0) {
                        double cos = -dot / Math.sqrt(m2[first] * m2[second]);
                        clash = cos * cos * Math.min(vk[first], vk[second]);
                    }
                }
                w = Math.max(w, 1e-300);
                double tint = .35 + .65 * v, hot = Math.max(0, (v - .85) / .15) * .5, white = Math.pow(clash, 1.2) * .9;
                double cr = r / w * tint, cg = g / w * tint, cb = b / w * tint;
                cr += (1 - cr) * hot;
                cg += (1 - cg) * hot;
                cb += (1 - cb) * hot;
                cr += (1 - cr) * white;
                cg += (1 - cg) * white;
                cb += (1 - cb) * white;
                double alpha = Math.min(1, halo + .75 * Math.pow(clash, 1.2));
                pressurePixels[j * gw + i] = (int) (alpha * 255) << 24 | (int) (Math.min(1, cr) * 255) << 16
                        | (int) (Math.min(1, cg) * 255) << 8 | (int) (Math.min(1, cb) * 255);
            }
        }
        pressureImage.getPixelWriter().setPixels(0, 0, gw, gh, PixelFormat.getIntArgbInstance(), pressurePixels, 0, gw);
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        gc.setImageSmoothing(true);
        gc.drawImage(pressureImage, 0, 0, width, height);
        tracers(q);
    }

    /** La couleur du champ d'un noyau, selon sa charge : cyan (léger) → bleu → violet → orange → rouge (lourd). */
    static Color haloColor(double z, double shift) {
        double t = Math.max(0, Math.min(1, Math.log(Math.max(1, z)) / Math.log(100) + shift));
        Color[] stops = {Color.web("#47D7FF"), Color.web("#6F8CFF"), Color.web("#B07CFF"), Color.web("#FF8A4A"), Color.web("#FF3B26")};
        double[] at = {0, .3, .5, .72, 1};
        for (int i = 1; i < at.length; i++) if (t <= at[i]) return stops[i - 1].interpolate(stops[i], (t - at[i - 1]) / (at[i] - at[i - 1]));
        return stops[stops.length - 1];
    }

    /** Le champ total (dans le plan de l'image), en k·e/fm², au point d'écran (x, y). */
    private static double[] field2d(double x, double y, List<double[]> q) {
        double ex = 0, ey = 0;
        for (double[] c : q) {
            double dx = (x - c[0]) * c[2], dy = (y - c[1]) * c[2];
            double m = Math.max(Math.sqrt(dx * dx + dy * dy), c[4]), k = c[3] / (m * m * m);
            ex += k * dx;
            ey += k * dy;
        }
        return new double[]{ex, ey};
    }

    /** Un grain de champ : parti de la surface d'un noyau (ou né au heurt), il avance dans la direction du champ total. */
    private static final class Tracer {
        int source;
        double ox, oy, age, life;
        boolean clash;
    }

    private final List<Tracer> tracers = new ArrayList<>();
    private final Random tracerRandom = new Random(11);
    private double tracerClock = Double.NaN;
    private int tracerSources = -1;

    /**
     * L'activité du champ : des grains très fins naissent à la surface de chaque noyau (à sa couleur, d'autant plus nombreux
     * que Z est grand) et dérivent comme des charges d'essai dans un milieu visqueux : leur vitesse suit le champ local
     * (v = μE, ramenée à √(E/E_réf) pour rester lisible, E_réf = le champ du noyau à 2,5 rayons). Seuls, ils filent droit ;
     * face à un autre noyau, le champ se courbe et ils sont déviés ; là où les deux champs s'annulent (le point neutre, au
     * cœur du heurt), ils ralentissent et s'accumulent : la résistance se voit. Ils s'éteignent en s'éloignant.
     */
    private void tracers(List<double[]> q) {
        double dt = Double.isNaN(tracerClock) ? 0 : Math.max(0, Math.min(.1, anim - tracerClock));
        tracerClock = anim;
        if (q.size() != tracerSources) {
            tracers.clear();
            tracerSources = q.size();
        }
        double speed = .55 * extent;
        int[] count = new int[q.size()];
        for (Tracer t : tracers) count[t.source]++;
        for (int s = 0; s < q.size(); s++) {
            int target = (int) Math.max(24, Math.min(110, 24 + 17 * Math.log1p(q.get(s)[9])));
            boolean fill = count[s] == 0;
            if (!fill && dt <= 0) continue;
            int spawn = fill ? target : Math.min(target - count[s], (int) Math.ceil(target * dt * 1.2));
            for (int k = 0; k < spawn; k++) {
                Tracer t = new Tracer();
                t.source = s;
                double theta = tracerRandom.nextDouble() * 2 * Math.PI, rr = q.get(s)[4] * 1.05;
                t.ox = rr * Math.cos(theta);
                t.oy = rr * Math.sin(theta);
                t.life = .9 + .8 * tracerRandom.nextDouble();
                if (fill) {
                    // Au départ, répartis le long de leur vie : le flux est déjà établi.
                    double age = tracerRandom.nextDouble() * t.life;
                    for (double done = 0; done < age; done += .05) advance(t, q, Math.min(.05, age - done) * speed);
                    t.age = age;
                }
                tracers.add(t);
            }
        }
        // Au heurt : des grains naissent là où les deux champs se font face, avec une probabilité ∝ S = cos²·min(V₁,V₂)
        // (la même grandeur que la zone blanche) ; le champ total les chasse ensuite le long de la frontière, vers les flancs.
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
                    if (tracerRandom.nextDouble() > clashAt(x, y, q)) continue;
                    Tracer t = new Tracer();
                    t.source = 0;
                    t.clash = true;
                    t.ox = (x - a[0]) * a[2];
                    t.oy = (y - a[1]) * a[2];
                    t.life = .6 + .6 * tracerRandom.nextDouble();
                    tracers.add(t);
                    clashes++;
                }
            }
        }
        gc.setGlobalBlendMode(BlendMode.ADD);
        gc.setLineWidth(1);
        Iterator<Tracer> it = tracers.iterator();
        while (it.hasNext()) {
            Tracer t = it.next();
            if (dt > 0) {
                for (double done = 0; done < dt; done += .025) advance(t, q, Math.min(.025, dt - done) * speed);
                t.age += dt;
            }
            double[] src = q.get(t.source);
            double x = src[0] + t.ox / src[2], y = src[1] + t.oy / src[2];
            boolean swallowed = false;
            for (int k = 0; k < q.size(); k++)
                if ((t.clash || k != t.source) && Math.hypot(x - q.get(k)[0], y - q.get(k)[1]) < q.get(k)[4] / q.get(k)[2]) swallowed = true;
            if (t.age > t.life || swallowed || x < -20 || x > width + 20 || y < -20 || y > height + 20) {
                it.remove();
                continue;
            }
            double[] e = field2d(x, y, q);
            double en = Math.hypot(e[0], e[1]);
            if (en < 1e-30) continue;
            double f = t.age / t.life, alpha = Math.min(1, f * 6) * (1 - f) * .8 * src[5];
            double tail = t.clash ? 9 : 7, ux = e[0] / en, uy = e[1] / en;
            if (t.clash) gc.setStroke(Color.color(1, .93, .72, Math.min(1, alpha * 1.15)));
            else gc.setStroke(Color.color(Math.min(1, src[6] * .6 + .4), Math.min(1, src[7] * .6 + .4), Math.min(1, src[8] * .6 + .4), alpha));
            gc.strokeLine(x - ux * tail, y - uy * tail, x, y);
        }
    }

    /** S = cos²·min(V₁, V₂) au point d'écran (x, y) : l'opposition des deux champs, là où les deux sont présents. */
    private static double clashAt(double x, double y, List<double[]> q) {
        double[][] e = new double[2][];
        double[] v = new double[2];
        for (int k = 0; k < 2; k++) {
            double[] c = q.get(k);
            double dx = (x - c[0]) * c[2], dy = (y - c[1]) * c[2];
            double m = Math.max(Math.sqrt(dx * dx + dy * dy), c[4]), f = c[3] / (m * m * m), surface = c[9] / (c[4] * c[4]);
            e[k] = new double[]{f * dx, f * dy};
            v[k] = Math.max(0, Math.min(1, Math.log10(1 + (e[k][0] * e[k][0] + e[k][1] * e[k][1]) / (surface * surface * 1e-4)) / 4));
        }
        double n0 = Math.hypot(e[0][0], e[0][1]), n1 = Math.hypot(e[1][0], e[1][1]);
        if (n0 <= 0 || n1 <= 0) return 0;
        double cos = -(e[0][0] * e[1][0] + e[0][1] * e[1][1]) / (n0 * n1);
        return cos <= 0 ? 0 : cos * cos * Math.min(v[0], v[1]);
    }

    /** Un pas d'un grain le long du champ total : `step` fm dans la direction de E (RK2). */
    private void advance(Tracer t, List<double[]> q, double step) {
        if (step <= 0) return;
        double[] src = q.get(t.source);
        double x = src[0] + t.ox / src[2], y = src[1] + t.oy / src[2];
        double[] e1 = field2d(x, y, q);
        double n1 = Math.hypot(e1[0], e1[1]);
        if (n1 < 1e-30) return;
        // La mobilité : là où le champ faiblit, le grain ralentit ; au point neutre (E = 0), il s'arrête presque.
        double ref = src[3] / (6.25 * src[4] * src[4]);
        step *= Math.sqrt(Math.max(.002, Math.min(9, n1 / Math.max(1e-30, ref))));
        double mx = x + .5 * step / src[2] * e1[0] / n1, my = y + .5 * step / src[2] * e1[1] / n1;
        double[] e2 = field2d(mx, my, q);
        double n2 = Math.hypot(e2[0], e2[1]);
        if (n2 < 1e-30) return;
        t.ox += step * e2[0] / n2;
        t.oy += step * e2[1] / n2;
    }

    // Un tronçon de ligne en cours : points écran, chaleur, opacité.
    private final double[] stripX = new double[256], stripY = new double[256], stripH = new double[256], stripA = new double[256];
    private int stripN;

    private void stripAdd(double x, double y, double h, double a) {
        if (stripN >= stripX.length) return;
        stripX[stripN] = x;
        stripY[stripN] = y;
        stripH[stripN] = h;
        stripA[stripN] = a;
        stripN++;
    }

    // ------------------------------------------------------------------ champ magnétique
    /** Les dipôles des spins (moment mesuré μ = g·I), superposés ; lignes fermées, qui coulent dans le sens de B. */
    private void magneticLines(List<Actor> actors, double tension) {
        List<double[]> dipoles = new ArrayList<>();
        for (Actor a : actors) {
            if (a.body.a() == 0 || a.alpha < .05 || Math.abs(a.body.moment()) < 1e-3 || a.look == null || a.look.spin() <= 0) continue;
            V axis = a.body.spin().unit();
            if (axis.norm() < .1) continue;
            dipoles.add(new double[]{a.pos.x(), a.pos.y(), a.pos.z(), axis.x() * a.body.moment(), axis.y() * a.body.moment(),
                    axis.z() * a.body.moment(), a.body.radius(), a.alpha});
        }
        if (dipoles.isEmpty()) return;
        gc.setGlobalBlendMode(BlendMode.ADD);
        for (double[] d : dipoles) {
            double moment = Math.sqrt(d[3] * d[3] + d[4] * d[4] + d[5] * d[5]);
            V m = new V(d[3], d[4], d[5]).unit();
            V e1 = m.cross(Math.abs(m.z()) < .9 ? new V(0, 0, 1) : new V(1, 0, 0)).unit(), e2 = m.cross(e1);
            double radius = d[6], top = radius * (1.7 + 1.5 * Math.cbrt(moment));
            double sin2 = Math.min(.95, 1.05 * radius / top), theta = Math.asin(Math.sqrt(sin2));
            int lines = (int) Math.max(4, Math.min(12, Math.round(4 + 2.2 * moment)));
            // Face à la tension électrique (Coulomb), la force magnétique est négligeable : ses lignes pâlissent jusqu'à 20 %.
            double alpha = Math.min(.45, .2 + .08 * Math.log1p(moment * 3)) * d[7] * (1 - .8 * Math.max(0, Math.min(1, tension)));
            for (int i = 0; i < lines; i++) {
                double phi = 2 * Math.PI * i / lines + anim * .15;
                V dir = m.mul(Math.cos(theta)).add(e1.mul(Math.sin(theta) * Math.cos(phi))).add(e2.mul(Math.sin(theta) * Math.sin(phi)));
                double x = d[0] + dir.x() * radius * 1.05, y = d[1] + dir.y() * radius * 1.05, z = d[2] + dir.z() * radius * 1.05;
                double step = radius * .12;
                List<double[]> pts = new ArrayList<>();
                pts.add(new double[]{x, y, z});
                for (int k = 0; k < 220; k++) {
                    double[] b = bfield(x, y, z, dipoles);
                    double nb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
                    if (nb < 1e-30) break;
                    double near = Double.MAX_VALUE;
                    for (double[] o : dipoles) near = Math.min(near, Math.sqrt((x - o[0]) * (x - o[0]) + (y - o[1]) * (y - o[1]) + (z - o[2]) * (z - o[2])) - o[6]);
                    double h = Math.max(radius * .06, Math.min(radius * .3, .25 * Math.max(near, radius * .2)));
                    x += h * b[0] / nb;
                    y += h * b[1] / nb;
                    z += h * b[2] / nb;
                    pts.add(new double[]{x, y, z});
                    if (k > 6 && near < radius * .02) break;
                    if (Math.sqrt((x - d[0]) * (x - d[0]) + (y - d[1]) * (y - d[1]) + (z - d[2]) * (z - d[2])) > top * 2.2) break;
                }
                stripN = 0;
                // La comète court le long de B, d'autant plus vite que le moment magnétique est grand.
                double speed = 30 * (.4 + moment), phase = i * .37;
                for (double[] q : pts) {
                    double[] p = camera.project(q[0], q[1], q[2]);
                    if (p == null) flushMagnetic(alpha, speed, phase);
                    else stripAdd(p[0], p[1], 0, 1);
                }
                flushMagnetic(alpha, speed, phase);
            }
        }
    }

    /** Ligne intégrée dans le champ des dipôles à leurs positions actuelles, parcourue par une impulsion mobile. */
    private void flushMagnetic(double alpha, double speed, double phase) {
        if (stripN > 1) {
            double[] xs = Arrays.copyOf(stripX, stripN), ys = Arrays.copyOf(stripY, stripN);
            gc.setStroke(Color.color(.45, .8, 1, Math.min(1, alpha * .32)));
            gc.setLineWidth(1);
            gc.strokePolyline(xs, ys, stripN);
            double[] cum = new double[stripN];
            for (int k = 1; k < stripN; k++) cum[k] = cum[k - 1] + Math.hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1]);
            double length = cum[stripN - 1];
            if (length > 20) {
                int comets = length > 260 ? 2 : 1;
                double tail = Math.min(.35 * length, 60);
                for (int c = 0; c < comets; c++) {
                    double head = ((anim * speed + (phase + (double) c / comets) * length) % length + length) % length;
                    for (int k = 0; k < 5; k++) {
                        double s0 = head - tail + tail * k / 5, s1 = head - tail + tail * (k + 1) / 5;
                        if (s1 <= 0) continue;
                        double[][] seg = along(xs, ys, cum, Math.max(0, s0), s1);
                        if (seg[0].length < 2) continue;
                        double f = (k + 1) / 5.0;
                        gc.setStroke(Color.color(.7, .95, 1, Math.min(1, alpha * 2.2 * f * f)));
                        gc.setLineWidth(.8 + 1.4 * f);
                        gc.strokePolyline(seg[0], seg[1], seg[0].length);
                    }
                }
            }
        }
        stripN = 0;
    }

    /** Le morceau de polyligne entre les abscisses curvilignes s0 et s1 (extrémités interpolées). */
    private static double[][] along(double[] xs, double[] ys, double[] cum, double s0, double s1) {
        List<double[]> pts = new ArrayList<>();
        for (int k = 1; k < cum.length; k++) {
            double a = cum[k - 1], b = cum[k];
            if (b < s0 || a > s1 || b <= a) continue;
            double t0 = Math.max(0, (s0 - a) / (b - a)), t1 = Math.min(1, (s1 - a) / (b - a));
            if (pts.isEmpty()) pts.add(new double[]{xs[k - 1] + (xs[k] - xs[k - 1]) * t0, ys[k - 1] + (ys[k] - ys[k - 1]) * t0});
            pts.add(new double[]{xs[k - 1] + (xs[k] - xs[k - 1]) * t1, ys[k - 1] + (ys[k] - ys[k - 1]) * t1});
        }
        double[][] out = new double[2][pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            out[0][i] = pts.get(i)[0];
            out[1][i] = pts.get(i)[1];
        }
        return out;
    }

    private static double[] bfield(double x, double y, double z, List<double[]> dipoles) {
        double bx = 0, by = 0, bz = 0;
        for (double[] d : dipoles) {
            double rx = x - d[0], ry = y - d[1], rz = z - d[2];
            double a = d[6] * .5, r2 = rx * rx + ry * ry + rz * rz;
            double md = d[3] * rx + d[4] * ry + d[5] * rz, k = 1 / Math.pow(r2 + a * a, 2.5);
            bx += (3 * rx * md + d[3] * (2 * a * a - r2)) * k;
            by += (3 * ry * md + d[4] * (2 * a * a - r2)) * k;
            bz += (3 * rz * md + d[5] * (2 * a * a - r2)) * k;
        }
        return new double[]{bx, by, bz};
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
        report.tags.add(new Tag(p[0], p[1], r, body, a.look, a.alpha, a.beta2));
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

    /** La force forte au contact : des tubes de lumière dorée entre les surfaces qui se touchent. */
    private void strongTubes(Actor a, Actor b, double strength) {
        V axis = b.pos.sub(a.pos);
        double sep = axis.norm();
        if (sep < 1e-6) return;
        axis = axis.mul(1 / sep);
        V across = axis.cross(Math.abs(axis.z()) < .9 ? new V(0, 0, 1) : new V(1, 0, 0)).unit(), up = axis.cross(across);
        double span = Math.min(a.body.radius(), b.body.radius()) * .75;
        gc.setGlobalBlendMode(BlendMode.ADD);
        for (int s = 0; s < 9; s++) {
            double angle = s * 2 * Math.PI / 9 + anim * .4, radius = span * (.3 + .7 * ((s * 37) % 9) / 8.0);
            V offset = across.mul(Math.cos(angle) * radius).add(up.mul(Math.sin(angle) * radius));
            V from = a.pos.add(axis.mul(a.body.radius() * .6)).add(offset.mul(.8));
            V to = b.pos.sub(axis.mul(b.body.radius() * .6)).add(offset.mul(.8));
            double[] xs = new double[17], ys = new double[17];
            int count = 0;
            for (int k = 0; k <= 16; k++) {
                double t = k / 16.0;
                double wobble = Math.sin(t * Math.PI) * span * .25 * Math.sin(anim * 6 + s + t * 9);
                double[] p = project(from.mul(1 - t).add(to.mul(t)).add(up.mul(wobble)));
                if (p == null) continue;
                xs[count] = p[0];
                ys[count++] = p[1];
            }
            if (count > 1) {
                gc.setStroke(Color.color(1, .78, .32, .14 * strength));
                gc.setLineWidth(5);
                gc.strokePolyline(xs, ys, count);
                gc.setStroke(Color.color(1, .9, .6, .6 * strength));
                gc.setLineWidth(1.3);
                gc.strokePolyline(xs, ys, count);
            }
        }
        double[] mid = project(a.pos.add(b.pos).mul(.5));
        if (mid != null) glow(mid[0], mid[1], span * mid[3] * 2.2, Color.web("#FFB43A"), .3 * strength);
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

    // ------------------------------------------------------------------ éclairs
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
     * Chaque rupture : un éclair au point de rupture, d'autant plus grand que l'énergie libérée est grande, puis le front
     * — la sphère qu'atteignent à cet instant les produits les plus rapides, de rayon v·(t − t₀) (calculé).
     */
    private void drawEmissions(CollisionTimeline timeline, double fraction) {
        for (double[] e : emissions) {
            double age = (fraction - e[0]) * timeline.durationSeconds();
            if (age < 0 || age > 1.2) continue;
            double[] p = project(new V(e[1], e[2], e[3]));
            if (p == null) continue;
            double energy = Math.log10(1 + e[10] / .1), flash = Math.exp(-age / .18);
            Color color = Color.color(e[4], e[5], e[6]);
            gc.setGlobalBlendMode(BlendMode.ADD);
            glow(p[0], p[1], (16 + 24 * energy) * (1 + 2.5 * (1 - flash)), color.interpolate(Color.WHITE, .5), .8 * flash);
            double front = e[9] * C * 1e15 * Math.max(0, report.time - e[8]) * p[3];
            if (front > 3 && front < Math.hypot(width, height) * 1.5) {
                double fade = Math.exp(-age / .7), band = Math.max(6, front * .14);
                report.fronts.add(new Front(p[0], p[1], front, band, front / p[3], e[10], e[9], Math.max(0, report.time - e[8]), (int) e[17]));
                gc.setFill(new RadialGradient(0, 0, p[0], p[1], front + band, false, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.TRANSPARENT), new Stop(Math.max(0, (front - band) / (front + band)), Color.TRANSPARENT),
                        new Stop(front / (front + band), color.interpolate(Color.WHITE, .4).deriveColor(0, 1, 1, Math.min(1, .45 * fade * energy / 2.2))),
                        new Stop(1, Color.TRANSPARENT)));
                gc.fillOval(p[0] - front - band, p[1] - front - band, 2 * (front + band), 2 * (front + band));
                gc.setStroke(color.interpolate(Color.WHITE, .3).deriveColor(0, 1, 1, .55 * fade));
                gc.setLineWidth(1.2 + 3 * fade);
                gc.strokeOval(p[0] - front, p[1] - front, 2 * front, 2 * front);
            }
        }
    }

    /**
     * La vitesse des produits : derrière chacun, la distance qu'il parcourt pendant une même pose — la traînée du plus
     * rapide fait 160 px, les autres en proportion de leur vitesse (le recul du gros noyau est court et large).
     */
    private void streaks(List<Actor> actors) {
        double fastest = 0;
        for (Actor a : actors) fastest = Math.max(fastest, a.vel.norm() / C);
        if (fastest < 1e-5) return;
        gc.setGlobalBlendMode(BlendMode.ADD);
        for (Actor a : actors) {
            double beta = a.vel.norm() / C;
            if (beta < 1e-5 || a.alpha < .2 || a.followed) continue;
            double[] p = project(a.pos), q = project(a.pos.add(a.vel.unit().mul(Math.max(.5, a.body.radius()))));
            if (p == null || q == null) continue;
            double dx = q[0] - p[0], dy = q[1] - p[1], dn = Math.hypot(dx, dy);
            if (dn < 1e-6) continue;
            dx /= dn;
            dy /= dn;
            double length = 160 * beta / fastest, half = a.body.a() == 0 ? 1.5 : Math.max(1.5, Math.min(14, a.body.radius() * p[3] * .6));
            double hx = p[0] - dx * half * .3, hy = p[1] - dy * half * .3, tx = p[0] - dx * (length + half), ty = p[1] - dy * (length + half);
            double[] c = clip(hx, hy, tx, ty);
            if (c == null) continue;
            Color color = a.body.a() == 0 ? Color.web("#FFD36A") : bodyColor(a.body.z(), a.body.a());
            gc.setFill(new LinearGradient(hx, hy, tx, ty, false, CycleMethod.NO_CYCLE,
                    new Stop(0, color.interpolate(Color.WHITE, .5).deriveColor(0, 1, 1, .95 * a.alpha)), new Stop(1, color.deriveColor(0, 1, 1, 0))));
            gc.fillPolygon(new double[]{hx - dy * half, hx + dy * half, tx}, new double[]{hy + dx * half, hy - dx * half, ty}, 3);
            // Le cœur blanc de la traînée : une balle, pas un sillage mou.
            double mx = p[0] - dx * (length * .6 + half), my = p[1] - dy * (length * .6 + half);
            gc.setStroke(new LinearGradient(hx, hy, mx, my, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.color(1, 1, 1, .9 * a.alpha)), new Stop(1, Color.color(1, 1, 1, 0))));
            gc.setLineWidth(Math.max(1.2, half * .35));
            gc.strokeLine(hx, hy, mx, my);
        }
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
