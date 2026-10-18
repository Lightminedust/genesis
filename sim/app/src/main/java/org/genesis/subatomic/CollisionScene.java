package org.genesis.subatomic;

import org.genesis.app.I18n;
import org.genesis.fusion.FusionDashboard;
import org.genesis.fusion.FusionRenderer;
import org.genesis.common.LabCamera;
import org.genesis.fusion.ReactorView;

import com.studio.genesis.grpc.AngularLobe;
import com.studio.genesis.grpc.BagState;
import com.studio.genesis.grpc.CollisionChannel;
import com.studio.genesis.grpc.CollisionState;
import com.studio.genesis.grpc.CollisionStep;
import com.studio.genesis.grpc.NucleonBreak;
import com.studio.genesis.grpc.NucleonCluster;
import com.studio.genesis.grpc.NucleonQuark;
import com.studio.genesis.grpc.NucleonSample;
import com.studio.genesis.grpc.NucleonState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * La collision e⁻ + nucléon, en 3D, racontée en étapes. Tout ce qui est dessiné vient du moteur :
 * <ul>
 * <li>l'électron suit la trajectoire intégrée dans le champ de Coulomb du proton (puits dessiné en coquilles froides), puis repart
 * à l'angle θ après l'échange d'un photon virtuel ;</li>
 * <li>le photon est un paquet d'onde en hélice de longueur d'onde λ = ħc/Q (à l'échelle), d'épaisseur croissante avec Q² ;</li>
 * <li>les hadrons sont enveloppés de la surface de leur sac (rayon déduit du rayon de charge, tube de flux de 2λ) : elle s'étire
 * avec la corde et s'étrangle quand elle casse ; elle palpite selon l'énergie d'excitation calculée ;</li>
 * <li>les quarks ont une taille qui suit leur masse constituante ; les cordes sont des ressorts de gluons en 3D.</li>
 * </ul>
 * Pas de panneau : des étiquettes sur les objets, un sous-titre, la loi de l'étape, et les cas possibles en pastilles cliquables.
 */
public final class CollisionScene {
    static final double CONTROLS = 54;
    private static final Color[] CHANNEL_COLOURS = {Color.web("#7FE3FF"), Color.web("#FF6FB5"), Color.web("#F2C55C"), Color.web("#9BF04E")};
    private static final String[] KEYS = {"elastic", "delta", "dual", "dis"};

    /** Une étape du récit : sa durée à l'écran, la fenêtre de temps physique qu'elle montre, et ce qu'on en dit. */
    record Beat(String key, String title, String caption, String detail, double seconds, double t0, double t1, double hold,
                List<Integer> steps) {}

    private final Canvas canvas = new Canvas();
    private final Canvas back = new Canvas();                       // le fond : la boîte, les traces et leurs projections
    /** La loupe : un gros plan qui suit les hadrons, pendant que la vue principale montre toute la zone à l'échelle. */
    static final double CLOSE_W = FusionDashboard.WIDTH, CLOSE_H = 230, CLOSE_MIN = 150;
    private double closeHeight = CLOSE_H;
    private boolean compactBoard;
    private final Canvas closeCanvas = new Canvas(CLOSE_W, CLOSE_H);
    private final Pane root = new Pane(back, canvas, closeCanvas);
    private final SubRender main = new SubRender(), close = new SubRender(new LabCamera());
    private SubRender render = main;
    private List<NucleonSample> samples = List.of();
    private List<NucleonBreak> breaks = List.of();
    private NucleonState nucleon;
    private CollisionState collision;
    private BagState bag;
    private int run = -1, collisionRun = -1, storyRun = -1;
    private final List<Beat> beats = new ArrayList<>();
    private double story, speed = 1, lastDt = 1 / 60.0, mouseX = -1, mouseY = -1, pendingSince = -1, previousX, previousY;
    private String pendingText = "";
    private boolean playing = true, steady, dragging;
    private double depth = 6, manualZoom = 1;
    private double[] focus = {0, 0, 0}, origin = {0, 0, 0};
    private final List<double[]> zones = new ArrayList<>();     // pastilles cliquables : {x0, x1, y0, y1, canal}

    CollisionScene(IntConsumer force) {
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());
        back.widthProperty().bind(root.widthProperty());
        back.heightProperty().bind(root.heightProperty());
        back.setMouseTransparent(true);
        closeCanvas.setMouseTransparent(true);
        render.camera.yaw = -Math.PI / 2 + .75;
        render.camera.pitch = .42;
        render.camera.friction = 3.2;
        render.camera.fovHalfDeg = 15;                                  // une figure : perspective douce
        main.flags = true;                                              // des panneaux sur poteaux, pas des pastilles flottantes
        canvas.setOnMousePressed(e -> { dragging = true; previousX = e.getX(); previousY = e.getY(); });
        canvas.setOnMouseDragged(e -> {
            render.camera.drag(e.getX() - previousX, e.getY() - previousY);
            previousX = e.getX();
            previousY = e.getY();
        });
        canvas.setOnMouseReleased(e -> dragging = false);
        canvas.setOnMouseMoved(e -> { mouseX = e.getX(); mouseY = e.getY(); });
        canvas.setOnMouseExited(e -> { mouseX = mouseY = -1; });
        // la molette : de toute la zone (1) jusqu'aux quarks (0,03) ; en zoomant, la vue glisse vers l'action
        canvas.setOnScroll(e -> manualZoom = Math.max(.03, Math.min(1.6, manualZoom * Math.exp(-e.getDeltaY() / 400))));
        canvas.setOnMouseClicked(e -> {
            if (!e.isStillSincePress()) return;
            for (double[] z : zones) {
                if (e.getX() >= z[0] && e.getX() <= z[1] && e.getY() >= z[2] && e.getY() <= z[3]) {
                    pending("Le moteur tire un événement dans ce cas…");
                    force.accept((int) z[4]);
                    return;
                }
            }
        });
    }

    CollisionScene() {
        this(i -> {});
    }

    Pane getNode() { return root; }

    boolean playing() { return playing; }

    void setPlaying(boolean on) {
        if (on && story >= total() - 1e-6) story = 0;
        playing = on;
    }

    void setSpeed(double s) { speed = s; }

    double story() { return story; }

    double total() {
        double t = 0;
        for (Beat b : beats) t += b.seconds();
        return Math.max(1e-6, t);
    }

    void seekStory(double s) {
        if (s < story) steady = closeSteady = activeSteady = false;
        story = Math.max(0, Math.min(total(), s));
    }

    /** Aller au début de l'étape suivante (+1) ou précédente (−1, ou au début de l'étape en cours si on y est depuis un moment). */
    void jump(int direction) {
        int current = beatIndex();
        int target = Math.max(0, Math.min(beats.size() - 1, current + direction));
        if (direction < 0 && story - beatStart(current) > .6) target = current;
        if (beatStart(target) < story) steady = false;
        story = beatStart(target);
    }

    int beatIndex() {
        double t = 0;
        for (int i = 0; i < beats.size(); i++) {
            t += beats.get(i).seconds();
            if (story < t - 1e-9) return i;
        }
        return Math.max(0, beats.size() - 1);
    }

    private double beatStart(int index) {
        double t = 0;
        for (int i = 0; i < index; i++) t += beats.get(i).seconds();
        return t;
    }

    int beatCount() { return beats.size(); }

    String beatTitle() { return beats.isEmpty() ? "" : beats.get(beatIndex()).title(); }

    List<Beat> beats() { return beats; }

    /** Le bouton « lancer » vient d'être pressé : on le dit tout de suite, le temps que le moteur calcule. */
    void pending(String text) {
        pendingSince = render.clock;
        pendingText = text;
    }

    boolean hasCollision() { return collision != null; }

    // ================================================================== les données
    void apply(NucleonState n, CollisionState c) {
        apply(n, c, null);
    }

    void apply(NucleonState n, CollisionState c, BagState b) {
        if (b != null && b.getActive()) bag = b;
        if (n != null && n.getActive()) {
            nucleon = n;
            if (n.getSamplesCount() > 0) {
                samples = n.getSamplesList();
                breaks = n.getBreaksList();
            }
            if (n.getRun() != run && !samples.isEmpty()) run = n.getRun();
        }
        if (c != null && c.getActive()) {
            if (c.getSpectrumCount() > 0 || collision == null || c.getRun() != collisionRun) {
                boolean fresh = collision == null || c.getRun() != collisionRun;
                collision = c;
                collisionRun = c.getRun();
                if (fresh) onChannels.run();
            }
        } else if (c != null) {
            collision = null;
        }
        int key = run * 1000 + (collision == null ? 0 : collisionRun);
        if (key != storyRun && !samples.isEmpty()) {
            storyRun = key;
            NucleonSample hit = samples.get(index(0.0));
            origin = new double[3];
            for (int i = 0; i < hit.getQuarksCount(); i++)
                for (int k = 0; k < 3; k++) origin[k] += hit.getQuarks(i).getPositionFm(k) / hit.getQuarksCount();
            focus = origin.clone();
            prepareFlight();
            buildStory();
            prepareFigure();
            story = 0;
            playing = true;
            pendingSince = -1;
            steady = false;
        }
    }

    private String channel() {
        if (collision == null || collision.getChannelsCount() <= collision.getChosen()) return "";
        return collision.getChannels(collision.getChosen()).getKey();
    }

    private double endTime() {
        return samples.isEmpty() ? 0 : samples.get(samples.size() - 1).getTimeFmc();
    }

    /** La fin du film : le dernier instant calculé, plus le vol libre des hadrons formés. */
    private double filmEnd() {
        return endTime() + flight;
    }

    private double flight = 0, shownTime;
    private double[][] drift = new double[0][];

    /**
     * Le vol libre. Au dernier instant calculé, chaque hadron est formé et libre : plus aucune corde ne le retient, rien ne s'exerce
     * sur lui — il file en ligne droite à sa vitesse p/E (la moyenne des vitesses de ses quarks, que le moteur règle exactement sur
     * sa 4-impulsion). On prolonge le film jusqu'à ce que tous se soient écartés d'au moins 3,5 fm — deux sacs d'1 fm de rayon et un
     * vrai vide entre eux — (6 à 25 fm/c) : sans cela, des
     * hadrons formés tard restaient collés à la fin du film. Leur mouvement interne est figé au-delà du calcul (écrit tel quel).
     */
    private void prepareFlight() {
        flight = 0;
        drift = new double[0][];
        if (samples.size() < 3 || collision == null) return;
        NucleonSample last = samples.get(samples.size() - 1);
        drift = new double[last.getQuarksCount()][];
        List<double[]> centres = new ArrayList<>(), velocities = new ArrayList<>();
        for (NucleonCluster c : last.getClustersList()) {
            double[] centre = new double[3], v = new double[3];
            int n = 0;
            for (int id : c.getQuarksList()) {
                if (id >= last.getQuarksCount() || last.getQuarks(id).getCluster() < 0) continue;
                NucleonQuark q = last.getQuarks(id);
                for (int k = 0; k < 3; k++) {
                    centre[k] += q.getPositionFm(k);
                    v[k] += q.getVelocityCCount() > k ? q.getVelocityC(k) : 0;
                }
                n++;
            }
            if (n == 0) continue;
            for (int k = 0; k < 3; k++) { centre[k] /= n; v[k] /= n; }
            double speed = SubRender.norm(v);
            if (speed > .995) v = SubRender.scale(v, .995 / speed);
            for (int id : c.getQuarksList()) if (id < drift.length) drift[id] = v;
            centres.add(centre);
            velocities.add(v);
        }
        double need = centres.size() < 2 ? 8 : 0;
        for (int a = 0; a < centres.size(); a++)
            for (int b = a + 1; b < centres.size(); b++) {
                double d0 = SubRender.norm(SubRender.sub(centres.get(a), centres.get(b)));
                double vr = SubRender.norm(SubRender.sub(velocities.get(a), velocities.get(b)));
                if (d0 < 3.5) need = Math.max(need, (3.5 - d0) / Math.max(.05, vr));
            }
        flight = Math.max(6, Math.min(25, need + 2));
    }

    private List<Integer> stepsMatching(String... words) {
        List<Integer> out = new ArrayList<>();
        if (collision == null) return out;
        for (int i = 0; i < collision.getStepsCount(); i++) {
            String title = collision.getSteps(i).getTitle().toLowerCase(Locale.ROOT);
            for (String w : words) if (title.contains(w)) { out.add(i); break; }
        }
        return out;
    }

    /** Le récit : les étapes, construites à partir du cas tiré et des instants calculés par le moteur. */
    private void buildStory() {
        beats.clear();
        if (collision == null) {
            beats.add(new Beat("rest", "Le nucléon au repos",
                    "Trois quarks liés par la corde de gluons bougent sans cesse dans leur sac.",
                    "Choisis le projectile, règle le faisceau et l'angle, puis lance-le.", 30, 0, Math.min(25, endTime()), 0, List.of()));
            return;
        }
        String e = DocStyle.number(collision.getEnergyMev()), ep = DocStyle.number(collision.getEOutMev());
        String lam = DocStyle.number(collision.getWavelengthFm());
        double pre = collision.getPreRollFmc();
        String target = nucleonName();
        if (hadronic()) {
            buildHadronStory(target, pre);
            beats.add(new Beat("bilan", "Bilan", "Tout est conservé : énergie, impulsion, charge et nombre de baryons.",
                    "", 4.0, filmEnd(), filmEnd(), 0, stepsMatching("bilan")));
            return;
        }
        String lens = main.warp ? "Toute énergie courbe l'espace : chacun déforme le fond autour de lui (lentille d'Einstein, θ_E² ∝ E, "
                + "amplifiée " + lensGain() + " fois — sinon invisible)." : "";
        // l'angle : tiré par la physique, ou choisi au curseur — on le dit
        String why = collision.getAngleSampled()
                ? String.format(I18n.locale(), "Angle tiré par la physique selon dσ/dΩ : la moitié des tirs dévient de moins de %.0f°, "
                        + "%.1f %% de plus de 30°. ", collision.getAngleMedianDeg(), 100 * collision.getAngleBeyond30())
                : String.format(I18n.locale(), "Angle de %.0f° fixé au curseur : on regarde ce qui arrive aux tirs qui dévient là. ",
                        collision.getAngleDeg());
        if (weak()) {
            beats.add(new Beat("approach", capital(beamName()) + " arrive",
                    capital(beamName()) + " de " + e + " MeV file vers le " + target + ". Sans charge, rien ne le dévie ; il n'avait que "
                            + DocStyle.number(collision.getInteractionProbability()) + " chance d'interagir en traversant le " + target
                            + " — voici ce cas rare. " + lens,
                    "Le W a une portée de ħc/M_W = 0,0025 fm : pour interagir, il doit passer sur un quark. Les étincelles autour du nucléon : ses pions virtuels (portée ħ/m_π c = 1,4 fm).", 3.4, -pre * .6, 0, 0,
                    stepsMatching("faisceau")));
            beats.add(new Beat("photon", "Il échange un " + collision.getBoson() + " virtuel",
                    "En émettant un " + collision.getBoson() + ", " + beamName() + " devient un " + outLabel() + " et cède ν = "
                            + DocStyle.number(collision.getNuMev()) + " MeV et q = " + DocStyle.number(collision.getQMev())
                            + " MeV/c : ce recul le dévie de " + String.format(I18n.locale(), "%.0f°", collision.getAngleDeg())
                            + ". Le " + outLabel() + " repart avec " + ep + " MeV.",
                    why + "Le " + collision.getBoson() + " emporte une charge : il change la saveur du quark qu'il touche.", 3.6, 0, 0, 0,
                    stepsMatching("boson", "peut voir", "cas possibles")));
        } else {
            String pull = collision.getProjectileCharge() > 0 ? "le repousse" : "l'attire";
            beats.add(new Beat("approach", capital(beamName()) + " arrive",
                    capital(beamName()) + " (" + beamLabel() + ") de " + e + " MeV fonce vers le " + target + ". " + lens + " Le champ du "
                            + target + " " + pull + " à peine : " + String.format(I18n.locale(), "%.2f°", collision.getCoulombDeflectionDeg()) + ".",
                    "Le champ de Coulomb seul ne peut pas le dévier de plus de " + String.format(I18n.locale(), "%.2f°", collision.getCoulombMaxDeg())
                            + " : les grands angles viennent du photon. Les étincelles autour du nucléon : ses pions virtuels (portée ħ/m_π c = 1,4 fm).", 3.4, -pre * .6, 0, 0, stepsMatching("faisceau")));
            beats.add(new Beat("photon", "Il échange un photon virtuel",
                    "En émettant un photon virtuel, " + beamName() + " lui cède ν = " + DocStyle.number(collision.getNuMev()) + " MeV et q = "
                            + DocStyle.number(collision.getQMev()) + " MeV/c : c'est ce recul qui le dévie de "
                            + String.format(I18n.locale(), "%.0f°", collision.getAngleDeg()) + " (action-réaction). Il repart avec " + ep + " MeV.",
                    why + "Le champ du " + target + " est fait de tels photons : de loin, seulement des photons mous (" + String.format(I18n.locale(),
                            "%.2f°", collision.getCoulombDeflectionDeg()) + ") ; un grand angle demande un photon dur, de longueur d'onde λ = ħc/Q = "
                            + lam + " fm.", 3.6, 0, 0, 0,
                    stepsMatching("photon virtuel", "peut voir", "cas possibles")));
        }
        double end = filmEnd();
        switch (storyCase()) {
            case "elastic" -> {
                beats.add(new Beat("whole", "Le " + target + " absorbe le photon d'un bloc",
                        "λ = " + lam + " fm, plus grand que les écarts entre quarks : le photon enveloppe le " + target + " entier.",
                        "Rien ne casse : c'est une diffusion élastique (les facteurs de forme G_E, G_M fixent la probabilité).",
                        2.8, 0, .4, 0, stepsMatching("reste lui-même")));
                beats.add(new Beat("recoil", "Il recule, intact",
                        "Le sac garde sa forme et ses trois quarks leur état ; il part avec l'impulsion du photon.",
                        "Énergie de recul T = Q²/2M = " + DocStyle.number(collision.getNuMev()) + " MeV.", 3.8, .4, Math.min(end, 16), 0,
                        stepsMatching("recul")));
            }
            case "qe" -> {
                String fin = collision.getTarget().equals("neutron") ? "proton" : "neutron";
                beats.add(new Beat("convert", "Le " + target + " devient un " + fin,
                        "Le " + collision.getBoson() + " change un quark " + (collision.getProjectile().equals("nu") ? "d en u" : "u en d")
                                + " sans rien casser : le " + target + " change d'identité d'un bloc.",
                        "Quasi-élastique (Llewellyn Smith) : facteurs de forme vectoriels et axial g_A = −1,27.", 2.8, 0, .4, 0,
                        stepsMatching("quasi-élastique")));
                beats.add(new Beat("recoil", "Il recule, intact",
                        "Le " + fin + " part avec l'impulsion du W ; le " + outLabel() + " file de son côté.", "", 3.8, .4,
                        Math.min(end, 16), 0, stepsMatching("recul")));
            }
            case "delta" -> {
                double tDecay = breaks.isEmpty() ? end : breaks.get(0).getTimeFmc();
                double flipEnd = Math.min(.3, tDecay * .2);
                beats.add(new Beat("flip", weak() ? "Le W change un quark et son spin" : "Un quark retourne son spin",
                        (weak() ? "Le W change la saveur d'un quark et retourne son spin" : "Le photon retourne le spin d'un quark")
                                + " : les trois spins s'alignent, le sac se met à palpiter.",
                        "Le " + target + " devient un Δ(1232) : spin 3/2, " + DocStyle.number(collision.getWMev() - mass())
                                + " MeV d'excitation.", 2.8, 0, flipEnd, 0, stepsMatching("excite")));
                beats.add(new Beat("live", "Le Δ ne vit qu'un instant",
                        "L'état excité est instable : la membrane vibre, prête à se scinder.",
                        "Durée de vie τ = ħ/Γ ≈ 1,7 fm/c, soit 5·10⁻²⁴ s.", 2.2, flipEnd, tDecay, 0, List.of()));
                if (!breaks.isEmpty()) {
                    NucleonBreak b = breaks.get(0);
                    beats.add(new Beat("break0", "Le Δ se divise",
                            "Une paire " + pair(b.getFlavor()) + " naît dans la corde : le sac s'étrangle et se scinde en deux.",
                            "Il en sort " + hadrons(b) + ".", 3.0, tDecay, Math.min(end, tDecay + 1.6), 1.2, stepsMatching("se désintègre")));
                    beats.add(new Beat("away", "Ils s'éloignent",
                            hadrons(b) + " s'écartent, chacun avec l'impulsion de la cinématique à deux corps.", "", 3.4,
                            Math.min(end, tDecay + 1.6), Math.min(end, tDecay + 16), 0, List.of()));
                }
            }
            default -> {
                String flavour = struckFlavour();
                double first = breaks.isEmpty() ? end : breaks.get(0).getTimeFmc();
                double kickEnd = Math.min(.25, first * .3);
                String changed = weak() ? (flavour.equals("d") ? " et le change en u" : " et le change en d") : "";
                beats.add(new Beat("strike", "Le " + bosonWord() + " frappe un quark " + flavour + changed,
                        "λ = " + lam + " fm, plus petit que le " + target + " : le " + bosonWord() + " ne touche qu'un quark et lui cède "
                                + DocStyle.number(collision.getQMev()) + " MeV/c.",
                        weak() ? "Le " + collision.getBoson() + " ne voit que les quarks dont il peut changer la charge."
                                : "Choisi selon sa charge au carré (u : 4/9, d : 1/9) et sa part d'impulsion x = " + DocStyle.number(collision.getX()) + ".",
                        2.8, 0, kickEnd, 0, stepsMatching("frappe un quark", "masse invariante")));
                beats.add(new Beat("stretch", "La corde s'étire, le sac s'allonge",
                        "Le quark arraché tire la corde de gluons ; le sac s'étire en tube autour d'elle.",
                        "Chaque fm de corde coûte σ ≈ 0,9 GeV : un quark ne peut jamais sortir seul.", 3.2, kickEnd,
                        Math.max(kickEnd, first - .05), 0, List.of()));
                List<Integer> breakSteps = stepsMatching("corde se rompt");
                for (int i = 0; i < breaks.size(); i++) {
                    NucleonBreak b = breaks.get(i);
                    double next = i + 1 < breaks.size() ? breaks.get(i + 1).getTimeFmc() - .05 : Math.min(end, b.getTimeFmc() + 3);
                    beats.add(new Beat("break" + i, i == 0 ? "La corde casse : le sac se divise" : "Elle casse encore",
                            "L'énergie de la corde devient une paire " + pair(b.getFlavor()) + " ; le tube s'étrangle et se sépare en deux sacs.",
                            "Il en sort " + hadrons(b) + ". Ce que la corde n'a pas mis en masse, Q = M − m₁ − m₂, les pousse dos à dos le long "
                                    + "d'elle (plus un petit p_T, effet tunnel) ; les autres hadrons n'en sont pas affectés.", 3.0, b.getTimeFmc(),
                            Math.max(b.getTimeFmc(), next), 1.2,
                            i < breakSteps.size() ? List.of(breakSteps.get(i)) : List.of()));
                }
                double last = breaks.isEmpty() ? first : breaks.get(breaks.size() - 1).getTimeFmc() + 3;
                beats.add(new Beat("away", "Les hadrons s'éloignent",
                        "Aucun quark n'est sorti seul : seulement des hadrons, chacun dans son sac.", "", 3.6, Math.min(end, last), end, 0,
                        List.of()));
            }
        }
        beats.add(new Beat("bilan", "Bilan", "Tout est conservé : énergie, impulsion, charge et nombre de baryons.",
                "", 4.0, end, end, 0, stepsMatching("bilan")));
    }

    /** Le récit d'une collision de deux hadrons : l'approche du sac projectile, le contact, ce qui en sort. */
    private void buildHadronStory(String target, double pre) {
        String p = DocStyle.number(collision.getBeamMomentumMev()), roots = DocStyle.number(collision.getSqrtSMev());
        double end = filmEnd();
        // la scène est vue du centre de masse : dans le laboratoire tout filerait vers l'avant et la séparation serait invisible
        double sqrtS = collision.getSqrtSMev(), m1 = collision.getElectronMassMev(), m2 = mass();
        double pStar = Math.sqrt(Math.max(0, (sqrtS * sqrtS - (m1 + m2) * (m1 + m2)) * (sqrtS * sqrtS - (m1 - m2) * (m1 - m2)))) / (2 * sqrtS);
        beats.add(new Beat("approach", capital(beamName()) + " arrive",
                "Vus de leur centre de masse, " + beamName() + " (p = " + p + " MeV/c dans le laboratoire) et le " + target
                        + " foncent l'un vers l'autre à p* = " + DocStyle.number(pStar) + " MeV/c : deux sacs de quarks, chacun ralenti par "
                        + "la dilatation du temps.",
                "√s = " + roots + " MeV : l'énergie disponible, Σ p = 0. Dans le laboratoire, tout filerait vers l'avant à presque c et "
                        + "l'on ne verrait pas les morceaux se séparer.", 3.6, -pre, 0, 0, stepsMatching("faisceau", "cas possibles")));
        switch (channel()) {
            case "elastic_h" -> {
                beats.add(new Beat("contact", "Ils se frôlent : diffraction",
                        "Aucune couleur n'est échangée : les deux sacs échangent seulement de l'impulsion (−t = "
                                + DocStyle.number(collision.getQ2Gev2()) + " GeV²).",
                        "Le pic de diffraction e^(Bt) et le théorème optique fixent la probabilité.", 2.8, 0, .4, 0, stepsMatching("diffraction")));
                beats.add(new Beat("recoil", "Chacun repart intact",
                        "Ils repartent dos à dos, chacun avec p* ; dans le laboratoire, " + beamName() + " serait dévié de "
                                + String.format(I18n.locale(), "%.1f°", collision.getAngleDeg()) + ". Personne ne casse.", "", 3.8, .4,
                        Math.min(end, 16), 0, stepsMatching("recul")));
            }
            case "formation" -> {
                double tDecay = breaks.isEmpty() ? end : breaks.get(0).getTimeFmc();
                double meetEnd = Math.min(.3, tDecay * .2);
                beats.add(new Beat("annihilate", "Annihilation : un Δ se forme",
                        "L'antiquark du pion et un quark de même saveur du " + target + " s'annihilent ; les trois quarks restants forment un Δ "
                                + "de masse √s = " + roots + " MeV.", "Formation en Breit–Wigner : le pic est à 190 mb pour π⁺p.", 3.2, 0, meetEnd,
                        0, stepsMatching("annihilation")));
                beats.add(new Beat("live", "Le Δ ne vit qu'un instant", "L'état excité palpite, prêt à se scinder.",
                        "Durée de vie τ = ħ/Γ ≈ 1,7 fm/c, soit 5·10⁻²⁴ s.", 2.2, meetEnd, tDecay, 0, List.of()));
                if (!breaks.isEmpty()) {
                    NucleonBreak b = breaks.get(0);
                    beats.add(new Beat("break0", "Le Δ se divise",
                            "Une paire " + pair(b.getFlavor()) + " naît dans la corde : le sac s'étrangle et se scinde en deux.",
                            "Il en sort " + hadrons(b) + ".", 3.0, tDecay, Math.min(end, tDecay + 1.6), 1.2, stepsMatching("se désintègre")));
                    beats.add(new Beat("away", "Ils s'éloignent", hadrons(b) + " s'écartent.", "", 3.4, Math.min(end, tDecay + 1.6),
                            Math.min(end, tDecay + 16), 0, List.of()));
                }
            }
            default -> {
                double first = breaks.isEmpty() ? end : breaks.get(0).getTimeFmc();
                double kickEnd = Math.min(.3, first * .3);
                beats.add(new Beat("exchange", "Échange de couleur",
                        "Un gluon échange la couleur entre un quark de chaque hadron : chacun garde son élan, mais reste lié à l'autre.",
                        "Inélastique : σ_inél = σ_tot − σ_él.", 3.0, 0, kickEnd, 0, stepsMatching("échange de couleur")));
                beats.add(new Beat("stretch", "Deux cordes se tendent",
                        "Les morceaux s'éloignent en tirant deux cordes de gluons ; les sacs s'étirent en tubes.",
                        "Chaque fm de corde coûte σ ≈ 0,9 GeV.", 3.2, kickEnd, Math.max(kickEnd, first - .05), 0, List.of()));
                List<Integer> breakSteps = stepsMatching("corde se rompt");
                for (int i = 0; i < breaks.size(); i++) {
                    NucleonBreak b = breaks.get(i);
                    double next = i + 1 < breaks.size() ? breaks.get(i + 1).getTimeFmc() - .05 : Math.min(end, b.getTimeFmc() + 3);
                    beats.add(new Beat("break" + i, i == 0 ? "Une corde casse" : "Elle casse encore",
                            "L'énergie de la corde devient une paire " + pair(b.getFlavor()) + " ; le tube s'étrangle et se sépare.",
                            "Il en sort " + hadrons(b) + ". Ce que la corde n'a pas mis en masse, Q = M − m₁ − m₂, les pousse dos à dos le long "
                                    + "d'elle (plus un petit p_T, effet tunnel) ; les autres hadrons n'en sont pas affectés.", 3.0, b.getTimeFmc(),
                            Math.max(b.getTimeFmc(), next), 1.2,
                            i < breakSteps.size() ? List.of(breakSteps.get(i)) : List.of()));
                }
                double last = breaks.isEmpty() ? first : breaks.get(breaks.size() - 1).getTimeFmc() + 3;
                beats.add(new Beat("away", "Les hadrons s'éloignent",
                        "Aucun quark n'est sorti seul : seulement des hadrons, chacun dans son sac.", "", 3.6, Math.min(end, last), end, 0,
                        List.of()));
            }
        }
    }

    private String nucleonName() {
        return nucleon != null && nucleon.getHadron().equals("neutron") ? "neutron" : "proton";
    }

    private double mass() {
        return nucleonName().equals("neutron") ? 939.565 : 938.272;
    }

    /** em : un photon échangé ; cc : un W (neutrino) ; had : un hadron, la force forte. */
    private String kind() {
        return collision == null || collision.getKind().isEmpty() ? "em" : collision.getKind();
    }

    private boolean hadronic() {
        return kind().equals("had");
    }

    private boolean weak() {
        return kind().equals("cc");
    }

    private String beamLabel() {
        return collision == null || collision.getProjectileLabel().isEmpty() ? "e⁻" : collision.getProjectileLabel();
    }

    private String outLabel() {
        return collision == null || collision.getOutgoingLabel().isEmpty() ? beamLabel() : collision.getOutgoingLabel();
    }

    /** Le projectile avec son article : « l'électron », « le neutrino », « le pion π⁺ »… */
    private String beamName() {
        String code = collision == null ? "e-" : collision.getProjectile();
        return switch (code) {
            case "e+" -> "le positron";
            case "mu-" -> "le muon";
            case "mu+" -> "l'antimuon";
            case "nu" -> "le neutrino";
            case "nubar" -> "l'antineutrino";
            case "p" -> "le proton";
            case "pi+" -> "le pion π⁺";
            case "pi-" -> "le pion π⁻";
            default -> "l'électron";
        };
    }

    private String bosonWord() {
        return weak() ? "W" : "photon";
    }

    private static String capital(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    static final Color W_COLOUR = Color.web("#E68CFF");

    /** La couleur d'un lepton à l'écran : électron cyan, positron rose, muon violet, neutrino blanc pâle. */
    static Color leptonColour(String label) {
        if (label.startsWith("e⁺")) return Color.web("#FF7AD9");                 // les teintes de la chambre de collision
        if (label.startsWith("μ")) return Color.web("#C4B5FD");
        if (label.startsWith("ν")) return Color.web("#B8F5C8");
        return Color.web("#7DD3FC");
    }

    /** La teinte d'un hadron, dans la palette de la chambre de collision (le proton a celle de l'hydrogène). */
    static Color hadronColour(String name) {
        String n = name == null ? "" : name;
        if (n.startsWith("proton") || n.equals("p")) return FusionRenderer.bodyColor(1, 1);
        if (n.startsWith("neutron") || n.equals("n")) return FusionRenderer.bodyColor(0, 1);
        if (n.startsWith("Δ")) return Color.web("#FF8A5B");
        if (n.startsWith("π")) return Color.web("#5EEAD4");
        if (n.startsWith("K")) return Color.web("#C4B5FD");
        if (n.startsWith("Λ") || n.startsWith("Σ") || n.startsWith("Ξ") || n.startsWith("Ω")) return Color.web("#A3E635");
        return Color.web("#9FB8D8");
    }

    /** Une ligne courte pour le tableau de bord : coupée au mot, avec une ellipse. */
    static String clip(String text, int max) {
        max = Math.max(10, max);
        if (text.length() <= max) return text;
        int cut = text.lastIndexOf(' ', max - 1);
        return text.substring(0, cut > max / 2 ? cut : max - 1).trim() + "…";
    }

    /** Le rayon du tube d'une trajectoire (fm) : une épaisseur de dessin, le lepton est ponctuel ; celui d'un muon est plus fin. */
    static final double TUBE_FM = .035;

    /** Le projectile est-il une antiparticule (e⁺, μ⁺, ν̄) ? */
    private boolean antiBeam() {
        String label = beamLabel();
        return label.contains("⁺") && !hadronic() || label.contains("̄");
    }

    private static boolean muonic(String label) {
        return label.startsWith("μ");
    }

    /** Le boson touche-t-il un quark (profond, résonances supérieures) plutôt que le nucléon entier ? */
    private boolean strikesQuark() {
        String ch = channel();
        return ch.equals("dis") || ch.equals("dual") || (ch.equals("res") && collision.getWMev() >= 1400);
    }

    /** Le récit à suivre pour ce cas. */
    private String storyCase() {
        String ch = channel();
        if (ch.equals("elastic") || ch.equals("qe")) return ch;
        if (ch.equals("delta") || (ch.equals("res") && collision.getWMev() < 1400)) return "delta";
        return "strike";
    }

    private String struckFlavour() {
        if (samples.isEmpty() || nucleon == null) return "";
        int i = nucleon.getStruck();
        NucleonSample s = samples.get(0);
        return i < s.getQuarksCount() ? s.getQuarks(i).getFlavor() : "";
    }

    private static String pair(String f) {
        return f + " " + f + "̄";
    }

    private static String hadrons(NucleonBreak b) {
        List<String> names = new ArrayList<>();
        for (String h : b.getHadronsList()) names.add(pretty(h));
        return String.join(" + ", names);
    }

    static String pretty(String name) {
        String n = switch (name) {
            case "p" -> "proton";
            case "n" -> "neutron";
            default -> name;
        };
        return n.replace("++", "⁺⁺").replace("+", "⁺").replace("--", "⁻⁻").replace("-", "⁻").replaceAll("0$", "⁰");
    }

    /** Un ralenti autour d'un instant (gardé pour les tests). */
    static double rate(double t) {
        double distance = Math.max(0, Math.abs(t - .75) - 1.25);
        return .18 + .82 * Math.min(1, distance / 2.5);
    }

    // ================================================================== le temps
    void tick(double dt) {
        lastDt = Math.min(.05, Math.max(1e-3, dt));
        render.clock += lastDt;
        render.camera.tick(lastDt, dragging, -2, 4);
        if (playing && !beats.isEmpty()) {
            story += lastDt * speed;
            if (beats.get(0).key().equals("rest")) story %= total();
            else if (story >= total()) {
                story = total();
                playing = false;
            }
        }
        draw();
    }

    /** Le temps physique (fm/c) montré, la progression dans l'étape (0 → 1) et le temps passé dans l'étape (s). */
    private double[] physical() {
        if (beats.isEmpty()) return new double[]{0, 0, 0};
        int i = beatIndex();
        Beat b = beats.get(i);
        double into = Math.max(0, story - beatStart(i));
        double progress = Math.min(1, into / b.seconds());
        double moving = Math.min(1, Math.max(0, into - b.hold()) / Math.max(1e-6, b.seconds() - b.hold()));
        double ease = moving * moving * (3 - 2 * moving);
        return new double[]{b.t0() + (b.t1() - b.t0()) * ease, progress, into};
    }

    // ================================================================== la lecture des échantillons
    private int index(double t) {
        int lo = 0, hi = samples.size() - 1;
        if (t <= samples.get(0).getTimeFmc()) return 0;
        if (t >= samples.get(hi).getTimeFmc()) return hi;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).getTimeFmc() <= t) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    private double[] position(int quark, double t) {
        double tEnd = endTime();
        if (t > tEnd && quark < drift.length && drift[quark] != null) {
            double[] base = position(quark, tEnd);
            return SubRender.add(base, SubRender.scale(drift[quark], t - tEnd));
        }
        int i = index(t);
        int j = Math.min(samples.size() - 1, i + 1);
        NucleonSample a = samples.get(i), b = samples.get(j);
        double span = b.getTimeFmc() - a.getTimeFmc(), f = span > 1e-9 ? Math.max(0, Math.min(1, (t - a.getTimeFmc()) / span)) : 0;
        if (quark >= a.getQuarksCount()) a = b;
        if (quark >= b.getQuarksCount()) b = a;
        List<Double> pa = a.getQuarks(quark).getPositionFmList(), pb = b.getQuarks(quark).getPositionFmList();
        return new double[]{pa.get(0) + f * (pb.get(0) - pa.get(0)), pa.get(1) + f * (pb.get(1) - pa.get(1)), pa.get(2) + f * (pb.get(2) - pa.get(2))};
    }

    private double[] junction(NucleonCluster c, double[][] at) {
        if (c.getKind().equals("baryon") && c.getJunctionFmCount() == 3) {
            double[] j = {c.getJunctionFm(0), c.getJunctionFm(1), c.getJunctionFm(2)};
            // en vol libre, la jonction suit ses quarks (le hadron entier file à sa vitesse)
            double extra = shownTime - endTime();
            int first = c.getQuarksCount() > 0 ? c.getQuarks(0) : -1;
            if (extra > 0 && first >= 0 && first < drift.length && drift[first] != null) j = SubRender.add(j, SubRender.scale(drift[first], extra));
            return j;
        }
        double[] m = new double[3];
        for (int id : c.getQuarksList()) for (int k = 0; k < 3; k++) m[k] += at[id][k] / c.getQuarksCount();
        return m;
    }

    // ================================================================== le dessin
    private void draw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w < 4 || h < 4) return;
        GraphicsContext g = canvas.getGraphicsContext2D(), gb = back.getGraphicsContext2D();
        zones.clear();
        if (samples.isEmpty()) {
            back.setEffect(null);
            DocStyle.background(gb, w, h);
            g.clearRect(0, 0, w, h);
            DocStyle.label(g, "en attente du calcul", w / 2, h / 2, 14, TextAlignment.CENTER, .8);
            return;
        }
        double[] now = physical();
        double t = now[0];
        shownTime = t;
        NucleonSample sample = samples.get(index(t));
        int n = sample.getQuarksCount();
        double[][] at = new double[n][];
        for (int i = 0; i < n; i++) at[i] = position(i, t);
        String beat = beats.isEmpty() ? "" : beats.get(beatIndex()).key();
        frame(at, beat);
        g.clearRect(0, 0, w, h);
        double stage = Math.max(320, w - FusionDashboard.WIDTH - 32);           // à droite : le tableau de bord
        render.centreShiftY = 75;                                                 // la scène sous l'en-tête
        lastStage = stage;
        lastHeight = h;
        render.begin(g, w, h, 0, stage, h, focus, depth, false);
        render.layer(gb);
        render.backdrop(w, h, depth);
        long t0 = System.nanoTime();
        drawFigure(sample, at, beat, now);
        spaceNanos += System.nanoTime() - t0;
        render.layer(g);
        FusionDashboard.Board board = board(beat);
        // la loupe se loge sous le tableau de bord ; s'il manque de la place, le tableau passe en compact (sans ses formules)
        double full = board == null ? 0 : FusionDashboard.height(board, false), slim = board == null ? 0 : FusionDashboard.height(board, true);
        compactBoard = false;
        closeHeight = h - 16 - full - 26;
        if (closeHeight < CLOSE_MIN && slim < full) {
            compactBoard = true;
            closeHeight = h - 16 - slim - 26;
        }
        closeHeight = Math.min(CLOSE_H, closeHeight);
        if (closeHeight < CLOSE_MIN || manualZoom <= .2 || collision == null) compactBoard = false;
        double boardHeight = compactBoard ? slim : full;
        boardBottom = 16 + boardHeight;
        render.reserve(w - FusionDashboard.WIDTH - 16, 16, FusionDashboard.WIDTH, boardHeight);
        render.reserve(0, 0, Math.min(stage * .52, 540), headerHeight(stage));
        drawElectron(beat, now, false);
        long t1 = System.nanoTime();
        drawHadrons(sample, at, beat, now);
        hadronNanos += System.nanoTime() - t1;
        drawPionCloud(sample, at);
        drawLenses(sample, at, beat, now);
        drawPhoton(at, beat, now);
        drawBreakFlash(beat, now);
        drawContact(beat, now);
        render.drawTags();
        drawHeader(g, stage, now);
        if (board != null) dashboard.draw(g, w - FusionDashboard.WIDTH - 16, 16, board, lastDt, compactBoard);
        if (pendingSince >= 0) drawPending(g, w, h);
        render.commitLens(back);
        drawClose(sample, at, beat, now, w, h);
        drawLegend(g, stage, h);
    }

    static long spaceNanos, hadronNanos;                                      // mesure du coût (aperçus)

    // ================================================================== la figure : toute la zone, dans une boîte ouverte
    private SubRender.Box figure = new SubRender.Box(-6, 6, -4, 4, -6, 6);
    private double gridStep = 2, dotStep = 2, lastStage = 900, lastHeight = 700, boardBottom = 300;

    /** Une trace : les centres successifs d'un hadron (et, à son début, ceux de son parent), à leurs instants. */
    private record Trace(List<double[]> points, List<Double> times, String[] name, List<Integer> ids) {}

    /** La vitesse d'une trace au-delà du dernier instant calculé (null si son hadron n'existe plus à la fin). */
    private double[] driftOf(Trace tr) {
        if (tr.times().isEmpty() || Math.abs(tr.times().get(tr.times().size() - 1) - endTime()) > 1e-9 || tr.ids().isEmpty()) return null;
        int id = tr.ids().get(0);
        return id < drift.length ? drift[id] : null;
    }

    private final List<Trace> traces = new ArrayList<>();

    /**
     * Prépare la figure d'un événement : les traces (le centre de chaque hadron à chaque échantillon ; un hadron né d'une rupture
     * part du dernier point de son parent — celui avec qui il partage le plus de quarks), la boîte qui contient toute la zone
     * (tous les quarks à tous les instants, plus une marge), le pas de sa grille et celui des points du temps.
     */
    private void prepareFigure() {
        traces.clear();
        java.util.Map<String, Trace> alive = new java.util.HashMap<>();
        java.util.Map<String, List<Integer>> previous = new java.util.HashMap<>();
        double[] lo = {origin[0], origin[1], origin[2]}, hi = lo.clone();
        for (NucleonSample s : samples) {
            java.util.Map<String, List<Integer>> current = new java.util.LinkedHashMap<>();
            java.util.Map<String, double[]> centres = new java.util.HashMap<>();
            java.util.Map<String, String> names = new java.util.HashMap<>();
            for (NucleonCluster c : s.getClustersList()) {
                List<Integer> ids = new ArrayList<>();
                double[] centre = new double[3];
                for (int id : c.getQuarksList()) {
                    if (id >= s.getQuarksCount() || s.getQuarks(id).getCluster() < 0) continue;
                    ids.add(id);
                    for (int k = 0; k < 3; k++) {
                        double v = s.getQuarks(id).getPositionFm(k);
                        centre[k] += v;
                        lo[k] = Math.min(lo[k], v);
                        hi[k] = Math.max(hi[k], v);
                    }
                }
                if (ids.isEmpty()) continue;
                for (int k = 0; k < 3; k++) centre[k] /= ids.size();
                java.util.Collections.sort(ids);
                String key = ids.toString();
                current.put(key, ids);
                centres.put(key, centre);
                names.put(key, c.getName());
            }
            java.util.Map<String, Trace> next = new java.util.HashMap<>();
            for (String key : current.keySet()) {
                Trace tr = alive.get(key);
                if (tr == null) {
                    tr = new Trace(new ArrayList<>(), new ArrayList<>(), new String[1], current.get(key));
                    String parent = null;
                    int best = 0;
                    for (var e : previous.entrySet()) {
                        int shared = 0;
                        for (int id : e.getValue()) if (current.get(key).contains(id)) shared++;
                        if (shared > best) { best = shared; parent = e.getKey(); }
                    }
                    Trace from = parent == null ? null : alive.get(parent);
                    if (from != null && !from.points().isEmpty()) {
                        tr.points().add(from.points().get(from.points().size() - 1));
                        tr.times().add(from.times().get(from.times().size() - 1));
                    }
                    traces.add(tr);
                }
                tr.points().add(centres.get(key));
                tr.times().add(s.getTimeFmc());
                tr.name()[0] = names.get(key);
                next.put(key, tr);
            }
            alive = next;
            previous = current;
        }
        if (!samples.isEmpty()) {
            NucleonSample last = samples.get(samples.size() - 1);
            for (int id = 0; id < Math.min(drift.length, last.getQuarksCount()); id++) {
                if (drift[id] == null) continue;
                for (int k = 0; k < 3; k++) {
                    double v = last.getQuarks(id).getPositionFm(k) + drift[id][k] * flight;
                    lo[k] = Math.min(lo[k], v);
                    hi[k] = Math.max(hi[k], v);
                }
            }
        }
        double span = 0;
        for (int k = 0; k < 3; k++) span = Math.max(span, hi[k] - lo[k]);
        if (collision != null && !hadronic() && collision.getElectronPathFmCount() >= 6) {
            // le lepton : on garde dans la boîte une bonne longueur de son arrivée et de son départ
            double reach = Math.max(8, .6 * span);
            double[] v = vertex(), out = {collision.getOutDirection(0), collision.getOutDirection(1), collision.getOutDirection(2)};
            int n = collision.getElectronPathFmCount() / 3;
            for (int i = 0; i < n; i++) {
                double[] p = {collision.getElectronPathFm(3 * i), collision.getElectronPathFm(3 * i + 1), collision.getElectronPathFm(3 * i + 2)};
                if (SubRender.norm(SubRender.sub(p, v)) > reach) continue;
                for (int k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], p[k]); hi[k] = Math.max(hi[k], p[k]); }
            }
            double[] end = SubRender.add(v, SubRender.scale(out, reach));
            for (int k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], end[k]); hi[k] = Math.max(hi[k], end[k]); }
            span = 0;
            for (int k = 0; k < 3; k++) span = Math.max(span, hi[k] - lo[k]);
        }
        double margin = Math.max(2, .1 * span);
        double[] a = new double[3], b = new double[3];
        double biggest = 0;
        for (int k = 0; k < 3; k++) biggest = Math.max(biggest, (hi[k] - lo[k]) / 2 + margin);
        for (int k = 0; k < 3; k++) {
            // presque un cube : chaque côté fait au moins 60 % du plus grand (la figure a du volume, les parois ont leur grille)
            double mid = (lo[k] + hi[k]) / 2, half = Math.max(Math.max((hi[k] - lo[k]) / 2 + margin, .6 * biggest), 4);
            a[k] = mid - half;
            b[k] = mid + half;
        }
        gridStep = SubRender.niceStep(Math.max(b[0] - a[0], Math.max(b[1] - a[1], b[2] - a[2])), 8);
        for (int k = 0; k < 3; k++) {
            a[k] = Math.floor(a[k] / gridStep) * gridStep;
            b[k] = Math.ceil(b[k] / gridStep) * gridStep;
        }
        figure = new SubRender.Box(a[0], b[0], a[1], b[1], a[2], b[2]);
        double t0 = samples.isEmpty() ? 0 : samples.get(0).getTimeFmc(), t1 = filmEnd();
        dotStep = SubRender.niceStep(Math.max(1, t1 - t0), 14);
    }

    /** La distance de la caméra qui fait tenir toute la boîte dans la scène (sous l'en-tête, à gauche du tableau de bord). */
    private double fitDistance() {
        double tan = Math.tan(Math.toRadians(main.camera.fovHalfDeg));
        double usable = Math.max(200, lastHeight - 190);                       // sous l'en-tête, au-dessus de la légende
        double aspect = Math.max(.35, Math.min(1, lastStage / Math.max(1, usable)));
        return figure.radius() / (tan * aspect * (usable / Math.max(1, lastHeight)) * .98);
    }

    /** Les points d'une trace jusqu'à l'instant t. */
    private static List<double[]> upTo(Trace tr, double t) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < tr.points().size() && tr.times().get(i) <= t + 1e-9; i++) out.add(tr.points().get(i));
        return out;
    }

    /** Les points du temps d'une trace : sa position aux instants k·Δt (interpolée entre les échantillons), jusqu'à t. */
    private List<double[]> dotsOf(Trace tr, double t) {
        List<double[]> out = new ArrayList<>();
        List<Double> ts = tr.times();
        double[] v = driftOf(tr);
        if (v != null && t > endTime()) {
            double tEnd = endTime();
            double[] end = tr.points().get(tr.points().size() - 1);
            for (double tk = Math.floor(tEnd / dotStep) * dotStep + dotStep; tk <= t + 1e-9; tk += dotStep)
                out.add(SubRender.add(end, SubRender.scale(v, tk - tEnd)));
        }
        if (ts.size() < 2) return out;
        for (double tk = Math.ceil(ts.get(0) / dotStep) * dotStep; tk <= Math.min(t, ts.get(ts.size() - 1)) + 1e-9; tk += dotStep) {
            int i = 0;
            while (i + 1 < ts.size() - 1 && ts.get(i + 1) < tk) i++;
            double span = ts.get(i + 1) - ts.get(i), f = span > 1e-9 ? Math.max(0, Math.min(1, (tk - ts.get(i)) / span)) : 0;
            out.add(SubRender.lerp(tr.points().get(i), tr.points().get(i + 1), f));
        }
        return out;
    }

    /**
     * La figure : la boîte (le sol et les deux parois du fond, gradués en fm), chaque trace (le chemin du centre de chaque hadron,
     * qui se ramifie aux ruptures) avec ses points du temps et ses projections sur les trois faces, le chemin du lepton, le repère
     * au sol de chaque objet, et les lois angulaires calculées (lobes).
     */
    private void drawFigure(NucleonSample sample, double[][] at, String beat, double[] now) {
        render.box(figure, gridStep, 1);
        if (collision == null) return;
        double t = now[0], tEnd = endTime();
        double t0 = samples.get(0).getTimeFmc();
        drawFronts(beat, now);
        drawPolar(beat, now);
        for (Trace tr : traces) {
            if (tr.times().get(0) > t + 1e-9) continue;
            Color c = hadronColour(tr.name()[0]);
            List<double[]> pts = upTo(tr, t);
            double[] v = driftOf(tr);
            List<double[]> lead = new ArrayList<>();                      // l'arrivée en ligne droite, devant la trace
            if (v != null && t > tEnd)                                    // le vol libre : une droite, à sa vitesse
                pts.add(SubRender.add(tr.points().get(tr.points().size() - 1), SubRender.scale(v, t - tEnd)));
            // avant le contact, rien ne s'exerce : l'arrivée est une droite, prolongée jusqu'à la paroi par où le hadron entre
            if (hadronic() && Math.abs(tr.times().get(0) - t0) < 1e-9 && tr.points().size() > 3) {
                double[] p0 = tr.points().get(0), p1 = tr.points().get(3);
                double dt = tr.times().get(3) - tr.times().get(0);
                double[] u = SubRender.scale(SubRender.sub(p1, p0), 1 / Math.max(1e-9, dt));
                if (SubRender.norm(u) > .05) {
                    List<double[]> before = new ArrayList<>();
                    List<double[]> beforeDots = new ArrayList<>();
                    for (double back = 0; back < 200; back += .5) {
                        double[] q = SubRender.sub(p0, SubRender.scale(u, back));
                        if (!figure.contains(q, 0)) break;
                        before.add(0, q);
                    }
                    for (double tk = Math.floor(t0 / dotStep) * dotStep; ; tk -= dotStep) {
                        double[] q = SubRender.add(p0, SubRender.scale(u, tk - t0));
                        if (!figure.contains(q, 0) || tk < t0 - 200) break;
                        if (tk < t0 - 1e-9) beforeDots.add(q);
                    }
                    before.add(p0);
                    lead.addAll(before.subList(0, before.size() - 1));
                    render.projections(before, figure, c, .6);
                    render.trace(before, c, .5, 1.1);
                    render.timeDots(beforeDots, c, .6);
                }
            }
            if (pts.size() >= 2) {
                render.projections(pts, figure, c, .9);
                render.trace(pts, c, .35, 1.2);
                // ce qui bouge encore : une comète, tête lumineuse et traînée qui s'effile vers l'arrière
                boolean alive = v != null || tr.times().get(tr.times().size() - 1) >= t - 1e-9;
                double moved = SubRender.norm(SubRender.sub(pts.get(pts.size() - 1), pts.get(Math.max(0, pts.size() - 8))));
                if (alive && moved > .05) {
                    List<double[]> path = new ArrayList<>(lead);
                    path.addAll(pts);
                    render.comet(path, c, .9, .55, Math.min(9, .22 * figure.radius()), 1.1);
                }
            }
            render.timeDots(dotsOf(tr, t), c, .95);
        }
        // le lepton : son chemin dans la boîte (arrivée calculée, puis sortie), ses points du temps (il va presque à c)
        if (!hadronic() && collision.getElectronPathFmCount() >= 6) {
            Color in = leptonColour(beamLabel()), out = leptonColour(outLabel());
            List<double[]> arrival = new ArrayList<>(), arrivalDots = new ArrayList<>();
            int n = collision.getElectronPathFmCount() / 3;
            int head = beat.equals("approach") ? Math.max(1, (int) Math.round(approach(now[1]) * (n - 1))) : n - 1;
            for (int i = 0; i <= head; i++) {
                double[] p = {collision.getElectronPathFm(3 * i), collision.getElectronPathFm(3 * i + 1), collision.getElectronPathFm(3 * i + 2)};
                if (figure.contains(p, 0)) arrival.add(p);
            }
            if (!beat.equals("approach")) arrival.add(vertex());
            double run = 0;
            double[] v = vertex();
            for (int i = n - 1; i > 0; i--) {
                double[] p = {collision.getElectronPathFm(3 * i), collision.getElectronPathFm(3 * i + 1), collision.getElectronPathFm(3 * i + 2)};
                double[] q = {collision.getElectronPathFm(3 * i - 3), collision.getElectronPathFm(3 * i - 2), collision.getElectronPathFm(3 * i - 1)};
                double seg = SubRender.norm(SubRender.sub(p, q));
                double next = Math.ceil((run + 1e-9) / dotStep) * dotStep;
                while (next <= run + seg) {
                    double[] d = SubRender.lerp(p, q, (next - run) / Math.max(1e-9, seg));
                    if (figure.contains(d, 0) && i <= head) arrivalDots.add(d);
                    next += dotStep;
                }
                run += seg;
                if (!figure.contains(q, 2)) break;
            }
            if (weak()) render.dotted(arrival, in, 1);
            else {
                render.projections(arrival, figure, in, .9);
                render.trace(arrival, in, .35, 1.2);
            }
            if (beat.equals("approach") && arrival.size() > 1)
                render.comet(arrival, in, weak() ? .5 : 1, .3, Math.min(10, .25 * figure.radius()), .9);
            render.timeDots(arrivalDots, in, .9);
            if (!beat.equals("approach")) {
                double[] dir = {collision.getOutDirection(0), collision.getOutDirection(1), collision.getOutDirection(2)};
                double reach = leptonTravel(beat, now), e = collision.getEOutMev(), m = collision.getOutgoingMassMev();
                double beta = e > m ? Math.sqrt(1 - m * m / (e * e)) : 1;
                List<double[]> leaving = new ArrayList<>(), leavingDots = new ArrayList<>();
                for (double d = 0; d <= reach + 1e-9; d += .25) {
                    double[] p = SubRender.add(v, SubRender.scale(dir, d));
                    if (!figure.contains(p, 0)) break;
                    leaving.add(p);
                }
                for (double tk = dotStep; beta * tk <= reach + 1e-9; tk += dotStep) {
                    double[] p = SubRender.add(v, SubRender.scale(dir, beta * tk));
                    if (!figure.contains(p, 0)) break;
                    leavingDots.add(p);
                }
                render.projections(leaving, figure, out, .9);
                render.trace(leaving, out, .35, 1.2);
                if (leaving.size() > 1) render.comet(leaving, out, 1, .3, Math.min(10, .25 * figure.radius()), .9);
                render.timeDots(leavingDots, out, .9);
            }
            double[] h = electronHead(beat, now);
            if (h != null && figure.contains(h, 0)) render.marker(h, figure, beat.equals("approach") ? in : out, .9);
        }
        for (NucleonCluster c : sample.getClustersList()) {
            double[] centre = new double[3];
            int count = 0;
            for (int id : c.getQuarksList()) {
                if (id >= at.length || sample.getQuarks(id).getCluster() < 0) continue;
                for (int k = 0; k < 3; k++) centre[k] += at[id][k];
                count++;
            }
            if (count == 0) continue;
            render.marker(SubRender.scale(centre, 1.0 / count), figure, hadronColour(c.getName()), .8);
        }
        drawActiveVolume(sample, at, beat, now);
        // le panneau de l'événement : ce que le choc a transféré
        boolean after = !beat.equals("approach") && !beat.equals("photon");
        if (after) {
            if (hadronic())
                render.tag(origin, .1, "contact", "√s " + DocStyle.number(collision.getSqrtSMev()) + " MeV · p " + DocStyle.number(
                        collision.getBeamMomentumMev()) + " MeV/c (labo)", Color.web("#9FE8FF"), .9);
            else
                render.tag(vertex(), .1, collision.getBoson().isEmpty() ? "γ*" : collision.getBoson(), "ν " + DocStyle.number(collision.getNuMev())
                        + " MeV · Q² " + DocStyle.number(collision.getQ2Gev2()) + " GeV² · W " + DocStyle.number(collision.getWMev()) + " MeV",
                        weak() ? W_COLOUR : SubRender.photonColour(collision.getNuMev()), .9);
        }
    }

    /**
     * Les fronts au sol : la nouvelle de chaque événement — le choc, puis chaque rupture — s'éloigne de son point à la vitesse de la
     * lumière ; un cercle sur le sol, de rayon c·(t − t₀), qui pâlit en s'élargissant.
     */
    private void drawFronts(String beat, double[] now) {
        double fy = render.backPlanes(figure)[1], limit = 2 * figure.radius();
        double[] at = hadronic() ? origin : vertex();
        double radius = beat.equals("approach") ? -1 : beat.equals("photon") ? LIGHT_FM_PER_BEAT * now[1]
                : (hadronic() ? now[0] : Math.max(LIGHT_FM_PER_BEAT, now[0] + LIGHT_FM_PER_BEAT));
        Color c = hadronic() ? Color.web("#9FE8FF") : weak() ? W_COLOUR : SubRender.photonColour(collision.getNuMev());
        if (radius > 0 && radius < limit)
            render.floorRing(new double[]{at[0], fy, at[2]}, radius, c, .9 / Math.sqrt(1 + radius / 3));
        for (NucleonBreak b : breaks) {
            double r = now[0] - b.getTimeFmc();
            if (r <= 0 || r > limit) continue;
            render.floorRing(new double[]{b.getPositionFm(0), fy, b.getPositionFm(2)}, r, DocStyle.GOLD, .8 / Math.sqrt(1 + r / 3));
        }
    }

    /**
     * Les lois angulaires, en diagrammes polaires posés sur le sol (le centre de la boîte reste vide) : dσ/dΩ du lepton (ou la
     * diffraction e^(Bt) de deux hadrons) sous le point de rencontre, la loi de désintégration du Δ sous l'endroit où il se
     * désintègre ; la direction réellement tirée en trait plein.
     */
    private void drawPolar(String beat, double[] now) {
        double fy = render.backPlanes(figure)[1];
        boolean early = beat.equals("approach") || beat.equals("photon") || beat.equals("contact");
        for (AngularLobe lb : collision.getLobesList()) {
            boolean decay = lb.getLabel().startsWith("désintégration");
            if (decay && (breaks.isEmpty() || now[0] < breaks.get(0).getTimeFmc() - 1e-9)) continue;
            double[] angles = lb.getAngleDegList().stream().mapToDouble(Double::doubleValue).toArray();
            double[] density = lb.getDensityList().stream().mapToDouble(Double::doubleValue).toArray();
            double[] o = lb.getOriginFmList().stream().mapToDouble(Double::doubleValue).toArray();
            double[] axis = lb.getAxisList().stream().mapToDouble(Double::doubleValue).toArray();
            double[] chosen = lb.getChosenDirList().stream().mapToDouble(Double::doubleValue).toArray();
            if (o.length < 3 || axis.length < 3) continue;
            double[] foot = {o[0], fy, o[2]};
            double n = Math.hypot(axis[0], axis[2]);
            double[] flat = n < .2 ? new double[]{0, 0, 1} : new double[]{axis[0] / n, 0, axis[2] / n};
            double[] side = {flat[2], 0, -flat[0]};
            int sign = chosen.length >= 3 && chosen[0] * side[0] + chosen[2] * side[2] < 0 ? -1 : 1;
            double room = Math.min(Math.min(foot[0] - figure.x0(), figure.x1() - foot[0]), Math.min(foot[2] - figure.z0(), figure.z1() - foot[2]));
            double radius = Math.min((decay ? .16 : .3) * figure.radius(), Math.max(1.5, room * .9));
            Color c = decay ? Color.web("#FFB86B") : Color.web("#7FE3FF");
            double alpha = decay ? (beat.startsWith("break") ? 1 : .75) : early ? 1 : .7;
            render.polarFloor(foot, flat, angles, density, lb.getLogScale(), lb.getSpanDecades(), radius, lb.getChosenDeg(), sign, c, alpha);
            String law = decay ? lb.getLabel().replace("désintégration du Δ : le pion sort selon ", "").replace(" (repos du Δ)", "")
                    : lb.getLogScale() ? "échelle log, " + String.format(I18n.locale(), "%.0f", lb.getSpanDecades()) + " décades" : "échelle linéaire";
            render.tag(SubRender.add(foot, SubRender.scale(flat, -radius * .2)), .1, decay ? "Δ → N π (au sol)" : hadronic() ? "dσ/dΩ* (au sol)" : "dσ/dΩ (au sol)",
                    law + " · cet événement " + String.format(I18n.locale(), "%.0f°", lb.getChosenDeg()), c, alpha);
        }
    }

    private final double[] activeLo = new double[3], activeHi = new double[3];
    private double activeAlpha;
    private boolean activeSteady;
    static final Color NEON = Color.web("#3DF2C2");

    /**
     * Le volume actif : un cube néon qui enveloppe les hadrons. Il apparaît quand le projectile entre dans la zone du nucléon (à moins
     * de 6 fm), suit les fragments en s'élargissant à mesure qu'ils s'écartent, et se dissipe au bilan. Sa taille est écrite : on
     * lit la séparation. Les panneaux se posent sur son arête du haut.
     */
    private void drawActiveVolume(NucleonSample sample, double[][] at, String beat, double[] now) {
        double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
        List<double[]> centres = new ArrayList<>();
        for (NucleonCluster c : sample.getClustersList()) {
            double[] centre = new double[3];
            int count = 0;
            for (int id : c.getQuarksList()) {
                if (id >= at.length || sample.getQuarks(id).getCluster() < 0) continue;
                for (int k = 0; k < 3; k++) {
                    lo[k] = Math.min(lo[k], at[id][k]);
                    hi[k] = Math.max(hi[k], at[id][k]);
                    centre[k] += at[id][k];
                }
                count++;
            }
            if (count > 0) centres.add(SubRender.scale(centre, 1.0 / count));
        }
        if (centres.isEmpty()) return;
        double want = 1;
        if (beat.equals("approach")) {
            double gap;
            if (hadronic()) gap = centres.size() > 1 ? SubRender.norm(SubRender.sub(centres.get(0), centres.get(1))) : 99;
            else {
                double[] head = electronHead(beat, now);
                gap = head == null ? 99 : SubRender.norm(SubRender.sub(head, centres.get(0)));
                if (head != null && gap < 6) for (int k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], head[k]); hi[k] = Math.max(hi[k], head[k]); }
            }
            want = Math.max(0, Math.min(1, (6 - gap) / 3));
        } else if (beat.equals("bilan")) want = Math.max(0, 1 - now[1] * 1.3);
        for (int k = 0; k < 3; k++) { lo[k] -= 1; hi[k] += 1; }
        double dt = lastDt;
        activeAlpha += (want - activeAlpha) * Math.min(1, dt * 4);
        if (!activeSteady) {
            System.arraycopy(lo, 0, activeLo, 0, 3);
            System.arraycopy(hi, 0, activeHi, 0, 3);
            activeAlpha = want;
            activeSteady = true;
        }
        for (int k = 0; k < 3; k++) {
            activeLo[k] += (lo[k] - activeLo[k]) * Math.min(1, dt * 7);
            activeHi[k] += (hi[k] - activeHi[k]) * Math.min(1, dt * 7);
        }
        // (le cube qui enveloppait les hadrons n'est plus dessiné : il dictait une forme rigide à la séparation ; seule sa hauteur
        // reste, pour aligner les panneaux au-dessus de l'action)
        main.flagTop = activeAlpha > .3 ? activeHi[1] + .25 : Double.NaN;
    }

    // ================================================================== la loupe
    private final double[] closeFocus = new double[3], closeSpeed = new double[3], closeLastAim = new double[3], closeAimVelocity = new double[3];
    private double closeDepth = 6, closeDepthSpeed;
    private boolean closeSteady;

    /**
     * La loupe : un gros plan qui suit les hadrons (même orientation que la boîte), pour voir les quarks, les sacs et les cordes
     * pendant que la vue principale montre toute la zone à l'échelle. Elle se retire quand on zoome déjà de près.
     */
    private void drawClose(NucleonSample sample, double[][] at, String beat, double[] now, double w, double h) {
        double ch = closeHeight, y = boardBottom + 12;
        boolean show = collision != null && manualZoom > .2 && ch >= CLOSE_MIN;
        closeCanvas.setVisible(show);
        if (!show) return;
        if (Math.abs(closeCanvas.getHeight() - ch) > .5) closeCanvas.setHeight(ch);
        closeCanvas.setLayoutX(w - FusionDashboard.WIDTH - 16);
        closeCanvas.setLayoutY(y);
        GraphicsContext gc = closeCanvas.getGraphicsContext2D();
        frameClose(at, beat);
        close.camera.yaw = main.camera.yaw;
        close.camera.pitch = main.camera.pitch;
        gc.clearRect(0, 0, CLOSE_W, ch);
        gc.save();
        gc.beginPath();
        gc.moveTo(10, 0);
        gc.arcTo(CLOSE_W, 0, CLOSE_W, ch, 10);
        gc.arcTo(CLOSE_W, ch, 0, ch, 10);
        gc.arcTo(0, ch, 0, 0, 10);
        gc.arcTo(0, 0, CLOSE_W, 0, 10);
        gc.closePath();
        gc.clip();
        gc.setFill(new javafx.scene.paint.LinearGradient(0, 0, 0, 1, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, Color.web("#0C1830")), new javafx.scene.paint.Stop(1, Color.web("#03060C"))));
        gc.fillRect(0, 0, CLOSE_W, ch);
        SubRender keep = render;
        render = close;
        try {
            close.centreShiftY = 8;
            close.begin(gc, CLOSE_W, ch, 0, CLOSE_W, ch, closeFocus, closeDepth, false);
            drawElectron(beat, now, true);
            drawHadrons(sample, at, beat, now);
            drawPionCloud(sample, at);
            drawPhoton(at, beat, now);
            drawBreakFlash(beat, now);
            drawContact(beat, now);
        } finally {
            render = keep;
        }
        gc.restore();
        // le cadre, le grossissement, l'échelle
        gc.setStroke(Color.web("#36CDF2").deriveColor(0, 1, 1, .55));
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(.6, .6, CLOSE_W - 1.2, ch - 1.2, 20, 20);
        double zoom = close.scaleAt(closeFocus) / Math.max(1e-9, main.scaleAt(closeFocus));
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(Color.web("#36CDF2"));
        I18n.fillText(gc, FusionDashboard.spaced("LOUPE") + "   ×" + String.format(I18n.locale(), "%.0f", zoom), 12, 18);
        double fm = close.scaleAt(closeFocus);
        if (fm > 1) {
            double bar = fm * (fm > 120 ? .5 : 1);
            gc.setStroke(Color.web("#C9D7EA").deriveColor(0, 1, 1, .8));
            gc.strokeLine(12, ch - 14, 12 + bar, ch - 14);
            gc.strokeLine(12, ch - 18, 12, ch - 10);
            gc.strokeLine(12 + bar, ch - 18, 12 + bar, ch - 10);
            gc.setFont(Font.font("Consolas", 10));
            gc.setFill(Color.web("#C9D7EA"));
            I18n.fillText(gc, fm > 120 ? "0,5 fm" : "1 fm", 18 + bar, ch - 10);
        }
    }

    private static double[] centreOf(NucleonCluster c, double[][] at) {
        double[] centre = new double[3];
        int n = 0;
        for (int id : c.getQuarksList()) {
            if (id >= at.length) continue;
            for (int k = 0; k < 3; k++) centre[k] += at[id][k];
            n++;
        }
        return n == 0 ? null : SubRender.scale(centre, 1.0 / n);
    }

    /** Le cadrage de la loupe : les hadrons vivants (le nucléon et l'échange au début), au recul de l'ancienne chambre. */
    private void frameClose(double[][] at, String beat) {
        boolean early = beat.equals("approach") || beat.equals("photon") || beat.equals("rest") || beat.equals("contact")
                || beat.equals("annihilate") || beat.equals("exchange");
        NucleonSample now = samples.get(index(physical()[0]));
        List<double[]> live = new ArrayList<>();
        for (int i = 0; i < at.length; i++) if (i >= now.getQuarksCount() || now.getQuarks(i).getCluster() >= 0) live.add(at[i]);
        double[] c = new double[3];
        for (double[] p : live) for (int k = 0; k < 3; k++) c[k] += p[k] / Math.max(1, live.size());
        double[] aim = early ? origin : c;
        double reach = 1.9;
        if (!early) {
            NucleonCluster best = null;
            double score = -1;
            for (NucleonCluster cl : now.getClustersList()) {
                double sc = cl.getExcitationMev() * 10 + cl.getQuarksCount() * 100 + cl.getMassMev() / 10;
                if (sc > score) { score = sc; best = cl; }
            }
            if ((beat.equals("away") || beat.equals("bilan")) && !breaks.isEmpty()) {
                // à la fin : les deux morceaux de la dernière rupture, pour voir qu'ils se sont bien séparés (au moins ×3)
                int initial = samples.get(0).getQuarksCount(), k = breaks.size() - 1;
                int qa = initial + 2 * k, qb = qa + 1;
                double[] ca = null, cb = null;
                for (NucleonCluster cl : now.getClustersList()) {
                    if (cl.getQuarksList().contains(qa)) ca = centreOf(cl, at);
                    if (cl.getQuarksList().contains(qb)) cb = centreOf(cl, at);
                }
                if (ca != null && cb != null) {
                    double gap = SubRender.norm(SubRender.sub(ca, cb));
                    if (gap < 7) {
                        aim = SubRender.lerp(ca, cb, .5);
                        reach = Math.max(1.9, gap / 2 + 1.5);
                    } else {
                        // trop loin l'un de l'autre pour un gros plan commun : le plus lourd des deux
                        NucleonCluster heavy = null;
                        for (NucleonCluster cl : now.getClustersList())
                            if ((cl.getQuarksList().contains(qa) || cl.getQuarksList().contains(qb)) && (heavy == null || cl.getMassMev() > heavy.getMassMev()))
                                heavy = cl;
                        aim = heavy == null ? ca : centreOf(heavy, at);
                        reach = 2.2;
                    }
                }
                best = null;
            } else if (beat.startsWith("break")) {
                int k = Integer.parseInt(beat.substring(5));
                if (k < breaks.size()) {
                    NucleonBreak b = breaks.get(k);
                    aim = new double[]{b.getPositionFm(0), b.getPositionFm(1), b.getPositionFm(2)};
                    reach = 2.6;
                }
            } else if (best != null && !(beat.equals("away") || beat.equals("bilan"))) {
                aim = new double[3];
                int count = 0;
                for (int id : best.getQuarksList()) {
                    if (id >= at.length) continue;
                    for (int k = 0; k < 3; k++) aim[k] += at[id][k];
                    count++;
                }
                for (int k = 0; k < 3; k++) aim[k] /= Math.max(1, count);
                for (int id : best.getQuarksList()) if (id < at.length) reach = Math.max(reach, Math.min(6, SubRender.norm(SubRender.sub(at[id], aim)) + 1.1));
            }
        } else {
            for (double[] p : live) reach = Math.max(reach, Math.min(6, SubRender.norm(SubRender.sub(p, aim)) + .9));
        }
        double want = reach * 3.4, dt = lastDt, w = 3.2;
        if (!closeSteady) {
            System.arraycopy(aim, 0, closeFocus, 0, 3);
            System.arraycopy(aim, 0, closeLastAim, 0, 3);
            java.util.Arrays.fill(closeSpeed, 0);
            java.util.Arrays.fill(closeAimVelocity, 0);
            closeDepth = want;
            closeDepthSpeed = 0;
            closeSteady = true;
            return;
        }
        // comme la caméra principale : le ressort connaît la vitesse de sa cible (un hadron qui file reste dans le cadre)
        double[] raw = SubRender.scale(SubRender.sub(aim, closeLastAim), 1 / dt);
        if (SubRender.norm(SubRender.sub(aim, closeLastAim)) > .6) raw = new double[3];
        System.arraycopy(aim, 0, closeLastAim, 0, 3);
        double smooth = 1 - Math.exp(-dt / .25);
        for (int k = 0; k < 3; k++) {
            closeAimVelocity[k] += (raw[k] - closeAimVelocity[k]) * smooth;
            closeSpeed[k] += (w * w * (aim[k] - closeFocus[k]) + 2 * w * (closeAimVelocity[k] - closeSpeed[k])) * dt;
            closeFocus[k] += closeSpeed[k] * dt;
        }
        closeDepthSpeed += (w * w * (want - closeDepth) - 2 * w * closeDepthSpeed) * dt;
        closeDepth += closeDepthSpeed * dt;
    }

    /** La légende de la figure, en bas de la scène. */
    private void drawLegend(GraphicsContext g, double stage, double h) {
        if (collision == null) return;
        double x = 16;
        g.setFont(Font.font("Segoe UI", 11));
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(Color.web("#9FB0C8"));
        String dt = DocStyle.number(dotStep);
        I18n.fillText(g, "● un point toutes les " + dt + " fm/c : leur écart est la vitesse   ·   au sol : lois angulaires, fronts à c   ·   "
                + "comète : ce qui bouge, sa traînée derrière lui", x, h - 32);
        I18n.fillText(g, "après le dernier instant calculé, chaque hadron libre file en ligne droite   ·   molette : zoom   ·   glisser : tourner",
                x, h - 14);
    }

    private final double[] focusSpeed = new double[3], lastAim = new double[3], aimVelocity = new double[3];
    private double depthSpeed;

    /**
     * La caméra de suivi. Avant et pendant l'échange, elle regarde le nucléon. Ensuite, elle suit les hadrons : leur centre (le
     * nucléon qui recule, ou les fragments qui s'écartent), à une distance qui garde tout le monde dans le cadre — elle avance et
     * recule. Le mouvement est un ressort amorti critique (pas de dépassement, pas d'à-coups), avec une pulsation de 3,2 rad/s,
     * qui connaît la vitesse de sa cible : un hadron qui recule à 0,8 c reste au centre au lieu de foncer vers la caméra. Un saut
     * de cible (changement d'étape) n'est pas pris pour une vitesse.
     */
    private void frame(double[][] at, String beat) {
        boolean early = beat.equals("approach") || beat.equals("photon") || beat.equals("rest") || beat.equals("contact")
                || beat.equals("annihilate") || beat.equals("exchange") || collision == null;
        List<double[]> live = new ArrayList<>();
        NucleonSample now = samples.isEmpty() ? null : samples.get(index(physical()[0]));
        for (int i = 0; i < at.length; i++) if (now == null || i >= now.getQuarksCount() || now.getQuarks(i).getCluster() >= 0) live.add(at[i]);
        double[] c = new double[3];
        for (double[] p : live) for (int k = 0; k < 3; k++) c[k] += p[k] / live.size();
        double[] action = early ? origin : c;
        // toute la zone par défaut ; plus on zoome, plus la vue glisse du centre de la boîte vers l'action
        double zoomIn = Math.max(0, Math.min(1, (1 - manualZoom) / .8));
        zoomIn = zoomIn * zoomIn * (3 - 2 * zoomIn);
        double[] aim = SubRender.lerp(figure.centre(), action, zoomIn);
        double want = fitDistance() * manualZoom;
        if (!steady) {
            System.arraycopy(aim, 0, focus, 0, 3);
            System.arraycopy(aim, 0, lastAim, 0, 3);
            java.util.Arrays.fill(focusSpeed, 0);
            depth = want;
            depthSpeed = 0;
            steady = true;
            return;
        }
        double w = 3.2, dt = lastDt;
        double[] raw = SubRender.scale(SubRender.sub(aim, lastAim), 1 / dt);
        if (SubRender.norm(SubRender.sub(aim, lastAim)) > .6) raw = new double[3];          // un saut, pas un mouvement
        System.arraycopy(aim, 0, lastAim, 0, 3);
        double smooth = 1 - Math.exp(-dt / .25);                                              // vitesse estimée, lissée
        for (int k = 0; k < 3; k++) aimVelocity[k] += (raw[k] - aimVelocity[k]) * smooth;
        double[] aimSpeed = aimVelocity;
        for (int k = 0; k < 3; k++) {
            focusSpeed[k] += (w * w * (aim[k] - focus[k]) + 2 * w * (aimSpeed[k] - focusSpeed[k])) * dt;
            focus[k] += focusSpeed[k] * dt;
        }
        depthSpeed += (w * w * (want - depth) - 2 * w * depthSpeed) * dt;
        depth += depthSpeed * dt;
    }

    private double quarkRadius(NucleonQuark q) {
        return .24 * Math.cbrt(Math.max(1, q.getMassMev()) / 336.0);
    }

    private double[] vertex() {
        List<Double> v = collision.getPhotonFromFmList();
        return new double[]{v.get(0), v.get(1), v.get(2)};
    }

    /** Le puits de Coulomb du proton (brume volumique) et le champ électrique total proton + électron, tant qu'il approche. */
    private void drawWell(String beat, double[] now) {
        // deux hadrons vus du centre de masse : la cible bouge, et leur interaction est forte — pas de puits fixe à l'origine
        if (bag == null || collision == null || bag.getWellProfileRFmCount() < 2 || hadronic()) return;
        double alpha = beat.equals("approach") ? 1 : beat.equals("photon") ? Math.max(0, 1 - now[1] * 1.2) : 0;
        if (alpha <= 0) return;
        double[] r = bag.getWellProfileRFmList().stream().mapToDouble(Double::doubleValue).toArray();
        double[] u = bag.getWellProfileUMevList().stream().mapToDouble(Double::doubleValue).toArray();
        double[] e = bag.getFieldProfileMevFmList().stream().mapToDouble(Double::doubleValue).toArray();
        if (nucleonName().equals("proton")) render.fieldHalo(origin, r, e, hadronColour("proton"), alpha);
        double[] edge = SubRender.add(origin, new double[]{0, -1.6, 0});
        // un neutrino n'a pas de charge : le champ du proton existe, mais il ne le sent pas (sa trajectoire reste droite)
        render.tag(edge, .1, "puits de Coulomb", weak() ? "sans effet sur le " + beamLabel() + " : charge 0, trajectoire droite"
                : "U(0) = " + DocStyle.number(bag.getWellDepthMev()) + " MeV · champ e·E max " + DocStyle.number(max(e)) + " MeV/fm",
                Color.web("#8E9CFF"), alpha);
    }

    /**
     * Les lentilles : toute énergie courbe l'espace, θ_E² ∝ E (loi d'Einstein, amplifiée d'un facteur unique, cf.
     * SubRender.LENS_GAIN). L'électron porte E puis E′ ; le photon emporte ν pendant qu'il file ; le nucléon a M au repos puis
     * M + ν quand le photon est absorbé ; ensuite chaque hadron a son énergie √((m + E*)² + P²). Σ E se conserve : ce que la
     * lentille de l'électron perd, celle du photon puis celle de la cible le gagnent.
     */
    private void drawLenses(NucleonSample sample, double[][] at, String beat, double[] now) {
        if (collision == null) return;
        boolean in = beat.equals("approach"), exchange = beat.equals("photon");
        double[] e = electronHead(beat, now);
        if (e != null) render.lens(e, in ? collision.getEnergyMev() : collision.getEOutMev(), electronShown(e));
        double reach = photonReach(beat, now, at);
        if (hadronic()) {
            // deux hadrons : chacun courbe l'espace selon son énergie √((m + E*)² + P²), avant comme après le contact
            for (NucleonCluster c : sample.getClustersList()) {
                double[] centre = new double[3];
                for (int id : c.getQuarksList()) for (int k = 0; k < 3; k++) centre[k] += at[id][k] / c.getQuarksCount();
                render.lens(centre, Math.hypot(c.getMassMev() + c.getExcitationMev(), c.getMomentumMev()), 1);
            }
            return;
        }
        if (exchange && reach < 1) render.lens(SubRender.lerp(vertex(), target(at), reach), collision.getNuMev(), 1);
        // le front de l'information : la sphère où arrive la nouvelle de la déviation, à c depuis le point d'échange
        if (exchange) {
            double radius = LIGHT_FM_PER_BEAT * now[1];
            double jump = 2 * Math.sin(Math.toRadians(collision.getAngleDeg()) / 2);      // |Δβ| = |β′ − β| pour β ≈ β′ ≈ 1
            render.shell(vertex(), radius, jump * Math.min(1, 1.5 / (.5 + radius)) * Math.min(1, now[1] * 12));
        }
        boolean absorbed = exchange && reach >= 1;
        for (NucleonCluster c : sample.getClustersList()) {
            double[] centre = new double[3];
            for (int id : c.getQuarksList()) for (int k = 0; k < 3; k++) centre[k] += at[id][k] / c.getQuarksCount();
            double energy = in || exchange ? mass() + (absorbed ? collision.getNuMev() : 0)
                    : Math.hypot(c.getMassMev() + c.getExcitationMev(), c.getMomentumMev());
            render.lens(centre, energy, 1);
        }
    }

    /**
     * La vitesse de la lumière dans le film de l'échange : 8 fm par étape. Le photon, l'électron diffusé (β ≈ 1) et le front de
     * l'information avancent tous à cette vitesse : ils restent cohérents entre eux.
     */
    static final double LIGHT_FM_PER_BEAT = 8;

    /** Où en est le photon (0 au départ, 1 arrivé) : il file à c du point d'échange jusqu'à sa cible. */
    private double photonReach(String beat, double[] now, double[][] at) {
        if (beat.equals("approach") || beat.equals("rest")) return 0;
        if (!beat.equals("photon")) return 1;
        double distance = Math.max(.2, SubRender.norm(SubRender.sub(target(at), vertex())));
        return Math.min(1, now[1] * LIGHT_FM_PER_BEAT / distance);
    }

    /** Le chemin parcouru par l'électron diffusé depuis le point d'échange (fm), à c. */
    private static double travel(String beat, double[] now) {
        return beat.equals("photon") ? LIGHT_FM_PER_BEAT * now[1] : LIGHT_FM_PER_BEAT * (1 + Math.min(1, now[1]));
    }

    /** Le lepton est montré tant qu'il est dans la boîte ; il en sort par une paroi. */
    private double electronShown(double[] head) {
        if (head == null || collision == null) return 0;
        double outside = 0;
        double[] lo = {figure.x0(), figure.y0(), figure.z0()}, hi = {figure.x1(), figure.y1(), figure.z1()};
        for (int k = 0; k < 3; k++) outside = Math.max(outside, Math.max(lo[k] - head[k], head[k] - hi[k]));
        double f = Math.max(0, Math.min(1, 1 - outside / 1.5));
        return f * f * (3 - 2 * f);
    }

    /** La tête de l'électron à cet instant (fm) : sur son chemin calculé, puis repartie selon k′. */
    private double[] electronHead(String beat, double[] now) {
        if (collision == null || collision.getElectronPathFmCount() < 6 || beat.equals("rest")) return null;
        if (beat.equals("approach")) return electronAt(beat, now);
        double[] out = {collision.getOutDirection(0), collision.getOutDirection(1), collision.getOutDirection(2)};
        return SubRender.add(vertex(), SubRender.scale(out, leptonTravel(beat, now)));
    }

    /** La distance parcourue par le lepton sortant : pendant l'échange, à c dans le film de l'échange ; ensuite, β·t en temps physique. */
    private double leptonTravel(String beat, double[] now) {
        if (beat.equals("photon")) return travel(beat, now);
        double e = collision.getEOutMev(), m = collision.getOutgoingMassMev();
        double beta = e > m ? Math.sqrt(1 - m * m / (e * e)) : 1;
        return Math.max(LIGHT_FM_PER_BEAT, beta * Math.max(0, physical()[0]));
    }

    /** Le facteur d'amplification des lentilles, écrit « 6·10³⁷ ». */
    static String lensGain() {
        int exp = (int) Math.floor(Math.log10(SubRender.LENS_GAIN));
        String digits = "⁰¹²³⁴⁵⁶⁷⁸⁹";
        StringBuilder sup = new StringBuilder();
        for (char ch : Integer.toString(exp).toCharArray()) sup.append(digits.charAt(ch - '0'));
        return Math.round(SubRender.LENS_GAIN / Math.pow(10, exp)) + "·10" + sup;
    }

    private static double max(double[] v) {
        double m = 0;
        for (double x : v) m = Math.max(m, x);
        return m;
    }

    /** L'avance de l'électron sur son chemin : vite au loin, lentement près du proton, pour qu'on voie leurs champs se rejoindre. */
    static double approach(double progress) {
        double f = Math.min(1, progress * 1.05);
        return 1 - Math.pow(1 - f, 3);
    }

    /** La position de l'électron à cet instant du récit (fm), ou null s'il n'est pas sur la scène. */
    private double[] electronAt(String beat, double[] now) {
        if (collision == null || collision.getElectronPathFmCount() < 6) return null;
        int n = collision.getElectronPathFmCount() / 3;
        if (beat.equals("approach")) {
            int head = Math.max(1, (int) Math.round(approach(now[1]) * (n - 1)));
            return new double[]{collision.getElectronPathFm(3 * head), collision.getElectronPathFm(3 * head + 1), collision.getElectronPathFm(3 * head + 2)};
        }
        if (beat.equals("photon")) {
            double[] v = vertex(), out = {collision.getOutDirection(0), collision.getOutDirection(1), collision.getOutDirection(2)};
            return SubRender.add(v, SubRender.scale(out, 10 * Math.min(1, now[1] * 1.15)));
        }
        return null;
    }

    /** L'électron : il suit sa trajectoire calculée jusqu'au point d'échange, puis repart à l'angle θ. */
    private void drawElectron(String beat, double[] now, boolean closeUp) {
        if (collision == null || collision.getElectronPathFmCount() < 6) return;
        List<double[]> path = new ArrayList<>();
        for (int i = 0; i + 2 < collision.getElectronPathFmCount(); i += 3)
            path.add(new double[]{collision.getElectronPathFm(i), collision.getElectronPathFm(i + 1), collision.getElectronPathFm(i + 2)});
        double[] v = vertex();
        if (beat.equals("approach")) {
            // la tête parcourt le chemin calculé ; on en montre tout ce qui est déjà parcouru, depuis la porte de la cage
            int head = Math.max(1, (int) Math.round(approach(now[1]) * (path.size() - 1)));
            List<double[]> shown = new ArrayList<>();
            for (int i = 0; i <= head; i++) if (path.get(i)[2] > path.get(head)[2] - (closeUp ? 5 : 30)) shown.add(path.get(i));
            double seen = electronShown(path.get(head));
            Color in = leptonColour(beamLabel());
            if (weak()) {
                // un neutrino : aucune charge, aucun champ, aucune trace — seulement sa trajectoire calculée, en pointillés, et son
                // spin, toujours opposé à son mouvement (ν, gauche) ou dans son sens (ν̄, droit)
                if (closeUp) render.dotted(shown, in, 1);
                render.lepton(path.get(head), in, seen, collision.getEnergyMev(), 0, antiBeam());
                render.helicity(path.get(head), new double[]{0, 0, 1}, antiBeam(), seen);
                render.tag(path.get(head), .1, beamLabel(), DocStyle.number(collision.getEnergyMev()) + " MeV · invisible : charge 0 · hélicité "
                        + (antiBeam() ? "droite" : "gauche"), in, seen);
                return;
            }
            if (closeUp) render.tube(shown, in, seen, muonic(beamLabel()) ? TUBE_FM * .6 : TUBE_FM);
            render.lepton(path.get(head), in, seen, collision.getEnergyMev(), (int) Math.signum(collision.getProjectileCharge()), antiBeam());
            render.tag(path.get(head), .1, beamLabel(), DocStyle.number(collision.getEnergyMev()) + " MeV · m " + DocStyle.number(
                    collision.getElectronMassMev()) + " MeV · charge " + (collision.getProjectileCharge() > 0 ? "+1" : "−1"), in, seen);
            return;
        }
        Color in = leptonColour(beamLabel()), outColour = leptonColour(outLabel());
        // le coude a la taille de la portée de l'échange. Le photon, sans masse, n'est localisé qu'à λ = ħc/Q près : la trajectoire
        // s'arrondit sur cette longueur (une courbe de Bézier entre l'entrée et la sortie, de contrôle au point d'échange). Le W, lui,
        // a une portée ħc/M_W = 0,0025 fm : un contact ponctuel — le neutrino arrive en ligne droite jusqu'au vertex exact, et
        // c'est là seulement que, devenu lepton chargé, il change de direction (un angle vif)
        double corner = weak() ? 0 : Math.max(.08, Math.min(.6, collision.getWavelengthFm()));
        double[] outDir = {collision.getOutDirection(0), collision.getOutDirection(1), collision.getOutDirection(2)};
        List<double[]> incoming = new ArrayList<>();
        double[] entry = null;
        for (double[] p : path) {
            if (p[2] <= v[2] - (closeUp ? 5 : 12)) continue;
            if (corner > 0 && SubRender.norm(SubRender.sub(p, v)) < corner) {
                if (entry == null) entry = p;
                continue;
            }
            incoming.add(p);
        }
        if (entry == null) entry = incoming.isEmpty() ? v : incoming.get(incoming.size() - 1);
        double[] exit = SubRender.add(v, SubRender.scale(outDir, corner));
        if (corner == 0) incoming.add(v);                                       // le vertex exact, sans arrondi
        else for (int k = 0; k <= 8; k++) {
            double u = k / 8.0;
            double[] a = SubRender.lerp(entry, v, u), b = SubRender.lerp(v, exit, u);
            incoming.add(SubRender.lerp(a, b, u));
        }
        boolean after = !beat.equals("photon");
        if (closeUp && weak()) render.dotted(incoming, in, after ? .5 : 1);
        else if (closeUp) render.tube(incoming, in, after ? .45 : .85, muonic(beamLabel()) ? TUBE_FM * .6 : TUBE_FM);
        double travel = Math.max(corner, leptonTravel(beat, now));
        if (closeUp) travel = Math.min(travel, 4);
        double[] out = outDir;
        List<double[]> leaving = new ArrayList<>();
        for (int i = 0; i <= 20; i++) leaving.add(SubRender.add(v, SubRender.scale(out, corner + (travel - corner) * i / 20.0)));
        // le choc : au moment où il cède q au photon, son sillage s'épaissit au point d'échange puis se calme
        double kick = beat.equals("photon") ? Math.exp(-now[1] / .12) : 0;
        if (closeUp) render.tube(leaving, outColour, 1, (muonic(outLabel()) ? TUBE_FM * .6 : TUBE_FM) * (1 + 2 * kick));
        if (kick > .02) {
            // l'impulsion : au point d'échange, l'énergie ν et l'impulsion q quittent le lepton d'un coup — un éclair bref, puis
            // une lueur qui retombe ; le front sphérique (lentille annulaire) part de là
            double flash = Math.exp(-now[1] / .05);
            render.glow(v, weak() ? .14 : .22, Color.WHITE, flash);
            render.glow(v, weak() ? .3 : .6, outColour.interpolate(Color.WHITE, .3), Math.max(flash, kick * .7));
            if (now[1] < .3)
                render.tag(v, .1, "−" + DocStyle.number(collision.getNuMev()) + " MeV", "cédés au " + bosonWord() + " · q = "
                        + DocStyle.number(collision.getQMev()) + " MeV/c · " + (weak() ? outLabel() + " créé à " : beamLabel() + " dévié de ")
                        + String.format(I18n.locale(), "%.0f°", collision.getAngleDeg()), outColour, 1 - now[1] / .3);
        }
        double[] head = leaving.get(leaving.size() - 1);
        double seen = electronShown(head);
        render.lepton(head, outColour, seen, collision.getEOutMev(), (int) Math.signum(collision.getOutgoingCharge() != 0
                ? collision.getOutgoingCharge() : collision.getProjectileCharge()), outLabel().contains("⁺"));
        if (beat.equals("photon") && seen > .02)
            render.tag(head, .1, outLabel() + (weak() ? " créé" : " diffusé"), DocStyle.number(collision.getEOutMev()) + " MeV · θ "
                    + String.format(I18n.locale(), "%.0f°", collision.getAngleDeg()), outColour, Math.min(seen, now[1] * 2));
    }

    /**
     * Le photon virtuel : un paquet d'onde qui file de l'électron à sa cible (λ = ħc/Q à l'échelle ; éclat, halo et couleur selon
     * ν). À l'arrivée il est absorbé : l'énergie ν se dépose et la cible s'illumine de l'intérieur — le nucléon entier si λ est
     * plus grand que lui (élastique, Δ), un seul quark sinon (inélastique). Il emporte q = k − k′.
     */
    private void drawPhoton(double[][] at, String beat, double[] now) {
        if (collision == null) return;
        boolean active = beat.equals("photon");
        boolean after = beat.equals("whole") || beat.equals("flip") || beat.equals("strike") || beat.equals("convert");
        if (!active && !after || hadronic()) return;
        double[] v = vertex(), target = target(at);
        double nu = collision.getNuMev(), lambda = collision.getWavelengthFm();
        double reach = photonReach(beat, now, at);
        Color c = weak() ? W_COLOUR : SubRender.photonColour(nu);
        if (weak()) {
            // le W : M_W = 80,4 GeV, portée ħc/M_W = 0,0025 fm et durée ħ/M_W c² ≈ 8·10⁻²⁷ s — un contact ponctuel. Une étincelle
            // violette sur le quark touché, qui s'éteint presque aussitôt ; puis plus rien du W
            if (!active) return;
            double spark = Math.exp(-now[1] / .04), ember = .3 * Math.exp(-now[1] / .2);
            render.glow(target, .1, Color.WHITE, spark);
            render.glow(target, .28, W_COLOUR, Math.max(spark, ember));
            render.tag(target, .1, collision.getBoson() + " virtuel", "M_W 80,4 GeV · portée 0,0025 fm · Q² "
                    + DocStyle.number(collision.getQ2Gev2()) + " GeV² · ν " + DocStyle.number(nu) + " MeV", W_COLOUR, Math.max(.25, 1 - now[1] * 1.5));
            return;
        }
        if (active && reach < 1 && !weak()) {
            double radius = .02 * (1 + Math.log10(1 + collision.getQ2Gev2() / .01));
            double shine = Math.min(1.8, .6 + Math.log10(1 + nu / 100) * .45);
            render.arc(v, target, Math.min(lambda, 6), radius, reach, shine, collision.getRun(), c, 1);
            render.tag(SubRender.lerp(v, target, Math.min(reach, .5)), .05, "γ*", "ν " + DocStyle.number(nu) + " MeV · q " + DocStyle.number(collision.getQMev())
                    + " MeV/c · Q² " + DocStyle.number(collision.getQ2Gev2()) + " GeV² · λ " + DocStyle.number(lambda) + " fm", c, 1);
        }
        double arrival = active ? Math.min(.95, SubRender.norm(SubRender.sub(target, v)) / LIGHT_FM_PER_BEAT) : 0;     // l'instant où il arrive
        double since = active ? (now[1] - arrival) / Math.max(.05, 1 - arrival) : 1;
        if (since < 0) return;
        double fade = active ? 1 - .45 * since : Math.max(0, .55 - now[1] * 1.6);
        boolean whole = !strikesQuark();
        double rBag = bag == null ? 1.1 : bag.getRadiusFm();
        double strength = Math.min(1, .45 + .2 * Math.log10(Math.max(1, nu / 50)));
        render.glow(target, (whole ? rBag * 1.05 : .34) * (.7 + .3 * Math.min(1, since * 3)), c, fade * strength);
        // l'éclat de l'impact : bref, à saturation, d'autant plus large que ν est grand
        double burst = Math.exp(-since * 9) * (active ? 1 : 0);
        if (burst > .02) {
            render.glow(target, (whole ? rBag * 1.4 : .6) * (1 + .25 * Math.log10(Math.max(1, nu / 100))), c.interpolate(Color.WHITE, .5), burst);
            render.glow(target, whole ? rBag * .6 : .2, Color.WHITE, burst);
        }
    }

    private double[] target(double[][] at) {
        if (strikesQuark() && nucleon != null && nucleon.getStruck() < at.length) return at[nucleon.getStruck()];
        double[] c = new double[3];
        int n = Math.min(3, at.length);
        for (int i = 0; i < n; i++) for (int k = 0; k < 3; k++) c[k] += at[i][k] / n;
        return c;
    }

    /** Les hadrons : la membrane de leurs sacs, les ressorts de gluons, les quarks, et leur étiquette. */
    private void drawHadrons(NucleonSample sample, double[][] at, String beat, double[] now) {
        boolean before = beat.equals("approach") || beat.equals("photon") || collision == null;
        boolean deltaPhase = beat.equals("flip") || beat.equals("live");
        int born = beat.startsWith("break") ? Integer.parseInt(beat.substring(5)) : -1;
        double birth = born >= 0 ? Math.min(1, now[2] / Math.max(.2, beats.get(beatIndex()).hold())) : 1;
        int initial = samples.get(0).getQuarksCount();
        int newest = born >= 0 ? initial + 2 * born : Integer.MAX_VALUE;
        double rq = bag == null ? .61 : bag.getQuarkRadiusFm(), rt = bag == null ? .46 : bag.getTubeRadiusFm();
        double rBag = bag == null ? 1.11 : bag.getRadiusFm();
        // Chaque hadron est une membrane calculée (SubRender.membraneBack) : la surface du champ de ses quarks et de ses cordes. Une
        // corde tendue étire le sac en tube ; quand elle casse, la paire naît à l'intérieur du tube et les deux morceaux restent une
        // seule membrane tant que leurs champs se touchent : le col s'amincit à mesure qu'ils s'écartent (à leurs vitesses
        // calculées), puis se pince. Rien n'apparaît : le sac se divise.
        // La profondeur est triée pour toute la scène : l'arrière d'une membrane à la profondeur de sa face arrière, chaque quark et
        // chaque tube à la sienne, le verre à celle de sa face avant.
        List<NucleonCluster> clusters = sample.getClustersList();
        boolean spins = channel().equals("elastic") || channel().equals("delta") || collision == null;
        int struck = nucleon == null ? -1 : nucleon.getStruck();
        int nc = clusters.size();
        int[] owner = new int[at.length];
        java.util.Arrays.fill(owner, -1);
        for (int ci = 0; ci < nc; ci++) for (int id : clusters.get(ci).getQuarksList()) if (id < owner.length) owner[id] = ci;
        double lobe = .5 * rBag, tube = .34, support = lobe / .454;
        // les contacts, en 3D : deux hadrons dont les quarks les plus proches sont à moins de 1 fm l'un de l'autre se touchent —
        // leurs halos fondent en une seule masse (poids 1) ; en s'écartant, le pont qui les relie s'amincit (poids qui décroît) et
        // se rompt à 2,6 fm (poids 0). C'est la distance réelle, en 3D, qui décide : deux hadrons qui se superposent seulement à
        // l'écran (l'un derrière l'autre) ne fusionnent pas.
        double[][] touch = new double[nc][nc];
        int[] group = new int[nc];
        for (int ci = 0; ci < nc; ci++) {
            group[ci] = ci;
            touch[ci][ci] = 1;
        }
        for (int a = 0; a < nc; a++)
            for (int b = a + 1; b < nc; b++) {
                double closest = 1e9;
                for (int qa : clusters.get(a).getQuarksList()) {
                    if (qa >= at.length || sample.getQuarks(qa).getCluster() < 0) continue;
                    for (int qb : clusters.get(b).getQuarksList()) {
                        if (qb >= at.length || sample.getQuarks(qb).getCluster() < 0) continue;
                        closest = Math.min(closest, SubRender.norm(SubRender.sub(at[qa], at[qb])));
                    }
                }
                double u = Math.max(0, Math.min(1, (2.6 - closest) / 1.6));
                touch[a][b] = touch[b][a] = u * u * (3 - 2 * u);
                if (touch[a][b] > .005) group[root(group, a)] = root(group, b);
            }
        List<Layer> layers = new ArrayList<>();
        java.util.Map<Integer, List<Integer>> members = new java.util.LinkedHashMap<>();
        for (int ci = 0; ci < nc; ci++) members.computeIfAbsent(root(group, ci), k -> new ArrayList<>()).add(ci);
        for (List<Integer> member : members.values()) {
            List<SubRender.Blob> blobs = new ArrayList<>();
            List<Color> tints = new ArrayList<>();
            List<SubRender.Capsule> caps = new ArrayList<>();
            List<Integer> blobOwner = new ArrayList<>(), capOwner = new ArrayList<>();
            double heat = 0, far = -1e9, near = 1e9, mostQuarks = -1;
            Color rim = Color.WHITE;
            for (int ci : member) {
                int me = member.indexOf(ci);
                NucleonCluster c = clusters.get(ci);
                double rn = rBag * Math.pow(c.getQuarksCount() / 3.0, .25);
                double excitation = before ? 0 : deltaPhase ? collision.getWMev() - mass() : c.getExcitationMev();
                double ratio = Math.max(0, excitation) / Math.max(100, c.getMassMev());
                heat = Math.max(heat, Math.min(1, ratio * 1.6));
                double wobble = Math.min(.18, .25 * Math.sqrt(ratio));
                Color tint = hadronColour(c.getName());
                if (c.getQuarksCount() > mostQuarks) { mostQuarks = c.getQuarksCount(); rim = tint; }
                double grownAll = 1;
                for (int id : c.getQuarksList()) if (id >= newest) grownAll = Math.min(grownAll, birth);
                double shellAlpha = .4 + .6 * grownAll;
                double[] j = junction(c, at);
                List<Integer> ids = new ArrayList<>(c.getQuarksList());
                for (int id : ids) {
                    if (sample.getQuarks(id).getCluster() < 0) continue;
                    double grow = id >= newest ? birth : 1;
                    blobs.add(new SubRender.Blob(at[id], lobe * Math.pow(c.getQuarksCount() / 3.0, .2) * (.35 + .65 * grow), wobble, id * 1.7));
                    tints.add(tint);
                    blobOwner.add(me);
                    double z = depth(at[id]);
                    far = Math.max(far, z);
                    near = Math.min(near, z);
                }
                // le cœur du sac : là où les cordes se rejoignent (la jonction d'un baryon, le milieu d'un méson) — il arrondit la
                // membrane d'un hadron au repos, que ses trois quarks seuls rendraient bosselée
                blobs.add(new SubRender.Blob(j, lobe * 1.15 * Math.pow(c.getQuarksCount() / 3.0, .2) * (.35 + .65 * grownAll), wobble, ci * 2.3));
                tints.add(tint);
                blobOwner.add(me);
                if (c.getKind().equals("meson") && ids.size() == 2) {
                    double[] a = at[ids.get(0)], b = at[ids.get(1)];
                    double d = SubRender.norm(SubRender.sub(b, a));
                    caps.add(new SubRender.Capsule(a, b, tube));
                    capOwner.add(me);
                    if (d > .9 * rn)
                        layers.add(new Layer(depth(SubRender.lerp(a, b, .5)), () -> render.fluxTube(a, b, rt * .7, d / (1.5 * rn), shellAlpha)));
                } else {
                    for (int id : ids) {
                        double[] end = at[id];
                        double leg = SubRender.norm(SubRender.sub(end, j));
                        if (leg > .3) {
                            caps.add(new SubRender.Capsule(j, end, tube));
                            capOwner.add(me);
                        }
                        if (leg > .9 * rn)
                            layers.add(new Layer(depth(SubRender.lerp(j, end, .5)), () -> render.fluxTube(j, end, rt * .7, leg / (1.5 * rn), shellAlpha)));
                    }
                }
            }
            if (blobs.isEmpty()) continue;
            SubRender.Membrane[] shell = new SubRender.Membrane[1];
            Color rimTint = rim;
            double glow = heat;
            int[] bo = blobOwner.stream().mapToInt(Integer::intValue).toArray(), co = capOwner.stream().mapToInt(Integer::intValue).toArray();
            double[][] w = new double[member.size()][member.size()];
            for (int a = 0; a < member.size(); a++) for (int b = 0; b < member.size(); b++) w[a][b] = touch[member.get(a)][member.get(b)];
            layers.add(new Layer(far + support, () -> shell[0] = render.membraneBack(blobs, tints, bo, caps, co, w, rimTint, glow, 1)));
            layers.add(new Layer(near - support, () -> render.membraneFront(shell[0])));
        }
        for (int ci = 0; ci < nc; ci++) {
            for (int i : clusters.get(ci).getQuarksList()) {
                NucleonQuark q = sample.getQuarks(i);
                if (q.getCluster() < 0) continue;                       // annihilé : il n'existe plus
                double grow = i >= newest ? birth : 1;
                if (grow <= .02) continue;
                double[] here = at[i];
                layers.add(new Layer(depth(here), () -> {
                    render.quark(here, quarkRadius(q) * grow, DocStyle.colour(q.getColor(), q.getAnti()), q.getFlavor() + (q.getAnti() ? "̄" : ""), 1);
                    if (spins && i < 3) spin(here, spinUp(i, beat, now));
                }));
                if (i == struck && beat.equals("strike") && collision != null) {
                    layers.add(new Layer(depth(here) - .01, () -> {
                        render.glow(here, .34, SubRender.photonColour(collision.getNuMev()), Math.max(0, .8 - now[1] * 1.2));
                        // pas de flèche : son sillage, c'est le chemin calculé qu'il a parcouru depuis le choc (t = 0)
                        List<double[]> wake = new ArrayList<>();
                        for (int k = 0; k <= 16; k++) wake.add(position(i, Math.max(0, now[0]) * k / 16.0));
                        render.trail(wake, Color.web("#FFC24A"), .9, 2.5);
                        render.tag(here, quarkRadius(q), q.getFlavor() + " frappé", SmTable.charge(q.getCharge()) + " e · reçoit "
                                + DocStyle.number(collision.getQMev()) + " MeV/c", Color.web("#FFC24A"), 1);
                    }));
                }
            }
        }
        layers.sort((a, b) -> Double.compare(b.depth(), a.depth()));            // du plus loin au plus proche
        for (Layer l : layers) l.draw().run();
        // le reflet des sacs et leurs étiquettes
        for (NucleonCluster c : sample.getClustersList()) {
            double[] m = new double[3];
            double reach = 0;
            for (int id : c.getQuarksList()) for (int k = 0; k < 3; k++) m[k] += at[id][k] / c.getQuarksCount();
            for (int id : c.getQuarksList()) reach = Math.max(reach, SubRender.norm(SubRender.sub(at[id], m)));
            String name = deltaPhase ? (c.getName().startsWith("Δ") ? pretty(c.getName()) : "Δ(1232)")
                    : before && c.getName().startsWith("Δ") ? nucleonName() : pretty(c.getName());
            // avant l'échange, le contenu d'avant (un W peut avoir déjà changé une saveur dans l'image du choc)
            NucleonSample shown = before && !hadronic() && !samples.isEmpty() ? samples.get(0) : sample;
            StringBuilder content = new StringBuilder();
            for (int id : c.getQuarksList()) {
                NucleonQuark q = id < shown.getQuarksCount() ? shown.getQuarks(id) : sample.getQuarks(id);
                content.append(q.getFlavor()).append(q.getAnti() ? "̄" : "");
            }
            double massShown = deltaPhase ? collision.getWMev() : before && c.getName().startsWith("Δ") ? mass() : c.getMassMev();
            double excitation = before ? 0 : deltaPhase ? collision.getWMev() - mass() : c.getExcitationMev();
            String detail = content + (excitation > 5 ? " · E* " + DocStyle.number(excitation) + " MeV" : "")
                    + (c.getMomentumMev() > 5 && !before ? " · p " + DocStyle.number(c.getMomentumMev()) + " MeV/c" : "");
            render.tag(m, reach + rq, name, detail, hadronColour(c.getName()), 1);
        }
        // la corde la plus tendue : sa longueur et son énergie σL
        if (beat.equals("stretch") && nucleon != null) {
            double best = 0;
            double[] a = null, b = null;
            for (NucleonCluster c : sample.getClustersList()) {
                double[] j = junction(c, at);
                for (int id : c.getQuarksList()) {
                    double l = SubRender.norm(SubRender.sub(at[id], j));
                    if (l > best) { best = l; a = j; b = at[id]; }
                }
            }
            if (a != null && best > .4)
                render.tag(SubRender.lerp(a, b, .5), .05, "corde", String.format(I18n.locale(), "%.1f fm · σL = %s MeV", best,
                        DocStyle.number(nucleon.getSigmaMevFm() * best)), DocStyle.GOLD, 1);
        }
    }

    /** Un élément de la scène des hadrons, dessiné à sa profondeur (fm, distance à la caméra). */
    private record Layer(double depth, Runnable draw) {}

    private double depth(double[] p) {
        double[] s = render.project(p);
        return s == null ? -1e9 : s[2];
    }

    /** Un sac : son arrière (gaz et nuage) à la profondeur de sa face arrière, son verre à celle de sa face avant. */
    private void sphere(List<Layer> layers, double[] centre, double radius, Color tint, double heat, double wobble, double phase,
                        double[] axis, double stretch, double alpha) {
        SubRender.Shell[] shell = new SubRender.Shell[1];
        double z = depth(centre);
        layers.add(new Layer(z + radius, () -> shell[0] = render.hadronBack(centre, radius, tint, heat, wobble, phase, axis, stretch, alpha)));
        layers.add(new Layer(z - radius, () -> render.hadronFront(shell[0])));
    }

    private static int root(int[] group, int i) {
        while (group[i] != i) i = group[i] = group[group[i]];
        return i;
    }

    /** Une flèche de spin à côté d'un quark (vers le haut de la vue ou vers le bas). */
    private void spin(double[] p, boolean up) {
        double[] s = render.project(p);
        if (s == null) return;
        double x = s[0] + Math.max(10, .2 * s[3]), y = s[1], d = up ? -1 : 1;
        GraphicsContext g = render.g;
        g.setStroke(Color.color(1, 1, 1, .9));
        g.setFill(Color.color(1, 1, 1, .9));
        g.setLineWidth(1.6);
        g.strokeLine(x, y - 7 * d, x, y + 5 * d);
        g.fillPolygon(new double[]{x - 3.5, x + 3.5, x}, new double[]{y + 4 * d, y + 4 * d, y + 9 * d}, 3);
    }

    private boolean spinUp(int i, String beat, double[] now) {
        boolean delta = channel().equals("delta");
        boolean flipped = delta && (beat.equals("flip") ? now[1] > .45 : !beat.equals("approach") && !beat.equals("photon"));
        return i != 2 || flipped;
    }

    static final double PION_RANGE_FM = 197.327 / 139.570;          // ħ/m_π c : la portée de Yukawa, ≈ 1,41 fm

    /**
     * Le nuage de pions virtuels d'un nucléon : le vide autour de lui n'est pas vide — des paires quark–antiquark (des pions
     * virtuels) y naissent et y meurent sans cesse. Leur portée est celle de Yukawa, λ = ħ/m_π c ≈ 1,4 fm : la densité suit
     * (e^(−r/λ)/r)², donc la distance au bord du sac est tirée selon e^(−2r/λ) (le r² du volume compense le 1/r²). Chaque paire
     * vit le temps permis par l'incertitude, ħ/m_π c² ≈ 1,4 fm/c (≈ 5·10⁻²⁴ s), ralenti comme le reste du film. Positions et
     * apparitions tirées au hasard : seules la portée et la durée sont physiques.
     */
    private void drawPionCloud(NucleonSample sample, double[][] at) {
        double rBag = bag == null ? 1.11 : bag.getRadiusFm();
        for (NucleonCluster c : sample.getClustersList()) {
            if (!c.getKind().equals("baryon") || c.getQuarksCount() != 3) continue;
            double[] centre = new double[3];
            for (int id : c.getQuarksList()) for (int k = 0; k < 3; k++) centre[k] += at[id][k] / 3;
            for (int i = 0; i < 22; i++) {
                double life = .9, phase = ((i * 0.6180339887) % 1.0) * life;
                double t = render.clock + phase;
                long generation = (long) Math.floor(t / life);
                double age = t / life - generation;
                java.util.Random r = new java.util.Random(generation * 7919 + i * 104729 + c.getId() * 31L);
                double radius = rBag * .85 + -Math.log(1 - r.nextDouble()) * PION_RANGE_FM / 2;
                double[] dir = {r.nextGaussian(), r.nextGaussian(), r.nextGaussian()};
                double n = SubRender.norm(dir);
                double[] mid = SubRender.add(centre, SubRender.scale(dir, radius / n));
                double[] split = SubRender.scale(SubRender.perpendicular(dir), .09);
                double alpha = Math.sin(Math.PI * age) * .55;
                render.spark(SubRender.add(mid, split), Color.web("#FDE68A"), alpha * .8);
                render.spark(SubRender.sub(mid, split), Color.web("#FFFFFF"), alpha * .6);
            }
        }
    }

    /** Le contact de deux hadrons : la paire q q̄ qui s'annihile en gluon, ou le gluon qui échange la couleur. */
    private void drawContact(String beat, double[] now) {
        if (collision == null || !hadronic()) return;
        double fade = Math.max(0, 1 - now[1] * 1.4);
        if (beat.equals("annihilate") && collision.getAnnihilationFmCount() == 3) {
            double[] p = {collision.getAnnihilationFm(0), collision.getAnnihilationFm(1), collision.getAnnihilationFm(2)};
            render.glow(p, .2 + .5 * now[1], Color.WHITE, fade);
            render.glow(p, .5 + .6 * now[1], DocStyle.GOLD, fade * .8);
            render.tag(p, .15, "q q̄ → gluon", "annihilation", DocStyle.GOLD, fade);
        } else if (beat.equals("exchange") || beat.equals("contact")) {
            double[] p = vertex();
            render.glow(p, .3 + .6 * now[1], beat.equals("exchange") ? DocStyle.GOLD : Color.web("#BFE3FF"), fade * .9);
        }
    }

    private void drawBreakFlash(String beat, double[] now) {
        if (!beat.startsWith("break")) return;
        int i = Integer.parseInt(beat.substring(5));
        if (i >= breaks.size()) return;
        NucleonBreak b = breaks.get(i);
        double[] p = {b.getPositionFm(0), b.getPositionFm(1), b.getPositionFm(2)};
        double f = Math.min(1, now[2] / Math.max(.2, beats.get(beatIndex()).hold()));
        render.glow(p, .15 + .5 * f, DocStyle.GOLD, Math.max(0, 1 - f * .8));
        render.tag(p, .1, "paire " + pair(b.getFlavor()), "σ·d = " + DocStyle.number(b.getReleasedMev()) + " MeV", DocStyle.GOLD,
                Math.min(1, 2 - f * 1.2));
    }




    private final FusionDashboard dashboard = new FusionDashboard();

    /** Un cas possible, pour la colonne de gauche : nom, probabilité, est-ce celui tiré, est-il possible ici. */
    record ChannelInfo(int index, String name, double probability, boolean chosen) {}

    private Runnable onChannels = () -> {};

    void setOnChannels(Runnable r) { onChannels = r; }

    List<ChannelInfo> channels() {
        List<ChannelInfo> out = new ArrayList<>();
        if (collision == null) return out;
        for (int i = 0; i < collision.getChannelsCount(); i++) {
            CollisionChannel ch = collision.getChannels(i);
            out.add(new ChannelInfo(i, ch.getName(), ch.getProbability(), i == collision.getChosen()));
        }
        return out;
    }

    /** La réaction, comme le titre de la chambre de collision : « e⁻ + p », « π⁺ + n »… */
    private String reactionTitle() {
        if (collision == null) return nucleonName();
        String target = nucleonName().equals("neutron") ? "n" : "p";
        return beamLabel() + "  +  " + target + "   ·   " + (hadronic() ? "p " + DocStyle.number(collision.getBeamMomentumMev()) + " MeV/c"
                : DocStyle.number(collision.getEnergyMev()) + " MeV");
    }

    private double headerHeight(double stage) {
        return 150 + 16 * wrap(currentCaption(), Font.font("Segoe UI", 12.5), Math.min(stage * .5, 520)).size();
    }

    private String currentCaption() {
        return beats.isEmpty() ? "" : beats.get(beatIndex()).caption();
    }

    /** Couper un texte en lignes qui tiennent dans `width`. */
    private static List<String> wrap(String text, Font font, double width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (ReactorView.width(trial, font) > width && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(trial);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    /**
     * L'en-tête de la scène, comme celui de la chambre de collision : la phase en capitales espacées, la réaction, l'échelle
     * réelle, le temps physique et le ralenti, puis le récit de l'étape.
     */
    private void drawHeader(GraphicsContext g, double stage, double[] now) {
        if (beats.isEmpty()) return;
        Beat b = beats.get(beatIndex());
        double x = 20, y = 28;
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10.5));
        g.setFill(Color.web("#36CDF2"));
        I18n.fillText(g, FusionDashboard.spaced(("étape " + (beatIndex() + 1) + "/" + beats.size() + " · " + b.title()).toUpperCase(I18n.locale())), x, y);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 21));
        g.setFill(Color.web("#EEF2FF"));
        I18n.fillText(g, reactionTitle(), x, y + 30);
        // l'échelle réelle
        double[] a = render.project(focus), r = render.camera.right();
        double[] e = render.project(SubRender.add(focus, r));
        if (a != null && e != null) {
            double perFm = Math.hypot(e[0] - a[0], e[1] - a[1]);
            double fm = DocStyle.niceLength(110 / Math.max(1e-6, perFm)), px = fm * perFm, sy = y + 66;
            g.setStroke(Color.web("#E8F0FF", .85));
            g.setLineWidth(1.2);
            g.strokeLine(x, sy, x + px, sy);
            g.strokeLine(x, sy - 5, x, sy + 5);
            g.strokeLine(x + px, sy - 5, x + px, sy + 5);
            g.setFont(Font.font("Segoe UI", 10.5));
            g.setFill(Color.web("#E8F0FF"));
            I18n.fillText(g, (fm < 1 ? String.format(I18n.locale(), "%.1f", fm) : String.format(I18n.locale(), "%.0f", fm)) + " fm · échelle réelle", x, sy - 9);
        }
        // le temps physique et le ralenti
        double seconds = now[0] * 3.33564095e-24;
        double span = Math.max(0, b.t1() - b.t0()), filmSeconds = b.seconds() / Math.max(.05, speed);
        String clock = "t = " + scientific(seconds) + " s";
        if (span > 1e-9) clock += "   ·   ralenti ×10" + superscript((int) Math.round(Math.log10(filmSeconds / (span * 3.33564095e-24))));
        else clock += "   ·   instant figé : l'échange dure ~10⁻²⁵ s";
        g.setFont(Font.font("Segoe UI", 11));
        g.setFill(Color.web("#B9C6D8"));
        I18n.fillText(g, clock, x, y + 84);
        // le récit
        double width = Math.min(stage * .5, 520), line = y + 112;
        Font font = Font.font("Segoe UI", 12.5);
        g.setFont(font);
        g.setFill(Color.web("#D6DEEA"));
        for (String l : wrap(b.caption(), font, width)) {
            I18n.fillText(g, l, x, line);
            line += 16;
        }
        if (!b.detail().isEmpty()) {
            Font small = Font.font("Segoe UI", 11);
            g.setFont(small);
            g.setFill(Color.web("#8EA6C0"));
            for (String l : wrap(b.detail(), small, width)) {
                I18n.fillText(g, l, x, line + 2);
                line += 14;
            }
        }
    }

    private static String scientific(double v) {
        if (v == 0) return "0";
        int exp = (int) Math.floor(Math.log10(Math.abs(v)));
        return String.format(I18n.locale(), "%.2f", v / Math.pow(10, exp)) + "·10" + superscript(exp);
    }

    private static String superscript(int n) {
        String digits = "⁰¹²³⁴⁵⁶⁷⁸⁹";
        StringBuilder out = new StringBuilder(n < 0 ? "⁻" : "");
        for (char ch : Integer.toString(Math.abs(n)).toCharArray()) out.append(digits.charAt(ch - '0'));
        return out.toString();
    }

    /**
     * Le tableau de bord de la chambre de collision : l'étape, le partage de l'énergie, la mesure principale et les autres, la
     * probabilité de chaque cas, et les lois de l'étape avec leurs valeurs.
     */
    private FusionDashboard.Board board(String beat) {
        if (collision == null || beats.isEmpty()) return null;
        Beat b = beats.get(beatIndex());
        List<FusionDashboard.Dial> dials = new ArrayList<>();
        FusionDashboard.Split split;
        Color gold = Color.web("#FFC24A");
        if (hadronic()) {
            double roots = collision.getSqrtSMev(), masses = mass() + collision.getElectronMassMev();
            split = new FusionDashboard.Split("ÉNERGIE  √s", DocStyle.number(roots) + " MeV", List.of(
                    new FusionDashboard.Part("masses m + M", DocStyle.number(masses) + " MeV", masses / roots, Color.web("#8EA4C8")),
                    new FusionDashboard.Part("disponible", DocStyle.number(roots - masses) + " MeV", 1 - masses / roots, gold)));
            dials.add(new FusionDashboard.Dial("roots", "√s", DocStyle.number(roots), "MeV", Math.min(1, (roots - 1000) / 4000)));
            dials.add(new FusionDashboard.Dial("sigma", "σ totale", DocStyle.number(collision.getSigmaTotalCm2() * 1e27), "mb",
                    Math.min(1, collision.getSigmaTotalCm2() * 1e27 / 200)));
        } else {
            double e = collision.getEnergyMev(), ep = collision.getEOutMev(), nu = collision.getNuMev();
            split = new FusionDashboard.Split("ÉNERGIE  E = E′ + ν", DocStyle.number(e) + " MeV", List.of(
                    new FusionDashboard.Part(outLabel() + " sortant", DocStyle.number(ep) + " MeV", ep / e, leptonColour(outLabel())),
                    new FusionDashboard.Part("cédé au nucléon", DocStyle.number(nu) + " MeV", nu / e, gold)));
            dials.add(new FusionDashboard.Dial("q2", "Q²  transfert", DocStyle.number(collision.getQ2Gev2()), "GeV²",
                    Math.min(1, collision.getQ2Gev2() / 5)));
            dials.add(new FusionDashboard.Dial("nu", "ν  énergie cédée", DocStyle.number(nu), "MeV", nu / e));
            dials.add(new FusionDashboard.Dial("w", "W  masse finale", DocStyle.number(collision.getWMev()), "MeV",
                    Math.max(0, Math.min(1, (collision.getWMev() - mass()) / Math.max(1, collision.getWMaxMev() - mass())))));
            dials.add(new FusionDashboard.Dial("lambda", "λ = ħc/Q", DocStyle.number(collision.getWavelengthFm()), "fm",
                    Math.min(1, collision.getWavelengthFm() / 1.5)));
        }
        List<FusionDashboard.Bar> bars = new ArrayList<>();
        for (int i = 0; i < collision.getChannelsCount(); i++) {
            CollisionChannel ch = collision.getChannels(i);
            bars.add(new FusionDashboard.Bar("ch" + i, (i == collision.getChosen() ? "▸ " : "") + ch.getName(),
                    String.format(I18n.locale(), "%.1f %%", 100 * ch.getProbability()), ch.getProbability(), -1, ""));
        }
        List<String> formulas = new ArrayList<>();
        for (int k : b.steps()) {
            CollisionStep step = collision.getSteps(k);
            String values = step.getValues();
            int cut = values.indexOf("  ·  ", values.indexOf("  ·  ") + 1);
            formulas.add(clip(step.getTitle().split("[:(]")[0].trim(), 24) + " = "
                    + clip((cut > 0 ? values.substring(0, cut) : values).replace(" = ", " "), 40 - Math.min(24, step.getTitle().length())));
        }
        return new FusionDashboard.Board(b.title(), split, dials, bars, formulas);
    }

    private void drawPending(GraphicsContext g, double w, double h) {
        double age = render.clock - pendingSince;
        g.setFill(Color.color(.02, .1, .18, .7));
        g.fillRoundRect(w / 2 - 210, h / 2 - 40, 420, 80, 16, 16);
        g.setStroke(DocStyle.RIM);
        g.setLineWidth(3);
        g.strokeArc(w / 2 - 190, h / 2 - 15, 30, 30, -age * 360, 270, javafx.scene.shape.ArcType.OPEN);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
        g.setFill(DocStyle.WHITE);
        I18n.fillText(g, pendingText, w / 2 - 145, h / 2 + 5, 340);
    }
}
