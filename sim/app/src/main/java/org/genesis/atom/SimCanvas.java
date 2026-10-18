package org.genesis.atom;

import org.genesis.app.I18n;
import org.genesis.common.Visuals;

import com.studio.genesis.grpc.EnergyLevel;
import com.studio.genesis.grpc.NuclearStatus;
import com.studio.genesis.grpc.NuclearTerm;
import com.studio.genesis.grpc.Nucleon;
import com.studio.genesis.grpc.ParticleInfo;
import com.studio.genesis.grpc.SimEvent;
import com.studio.genesis.grpc.Subshell;
import com.studio.genesis.grpc.UniverseFrame;

import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * La carte : l'atome à sa vraie échelle, et ce qui lui arrive.
 *
 * Rien n'y est scénarisé : le moteur applique les règles au fil du temps
 * simulé et envoie des ÉVÉNEMENTS. La carte les dessine là où ils arrivent,
 * lentement, et une bulle reliée à l'acteur (l'électron qui saute, le noyau
 * qui se désintègre) en donne le calcul. En mode pas à pas, le temps
 * s'arrête après chaque événement : on prend le temps de lire.
 *
 * Trois échelles réelles, chacune avec son panneau :
 *   - l'atome (~100 pm) : chaque électron dans son orbitale, avec son spin ;
 *     panneau des couches (Klechkowski, Pauli, Hund) ;
 *   - le noyau (~5 fm) : les nucléons en 3D ; panneaux des forces et du
 *     bilan de masse (masses mesurées) ;
 *   - le nucléon (~1 fm) : les quarks, leur couleur, leur spin.
 * Un clic sur une particule ouvre sa fiche (masse, charge, spin...).
 */
public class SimCanvas {

    public enum Scale { ATOME, NOYAU, NUCLEON }

    // --- Palette ---
    static final Color BACKGROUND = Color.web("#050812");
    private static final Color BACKGROUND_GLOW = Color.web("#0C1630");
    private static final Color CARD = Color.web("#0C1324", 0.90);
    private static final Color CARD_BORDER = Color.web("#22304A");
    private static final Color TEXT = Color.web("#E6EDF7");
    private static final Color SOFT = Color.web("#A9B6CB");
    private static final Color MUTED = Color.web("#64748B");
    private static final Color FORMULA = Color.web("#FCD34D");
    private static final Color ELECTRON = Color.web("#7DD3FC");
    private static final Color EXCITED = Color.web("#FDE68A");
    private static final Color ALPHA = Color.web("#FBBF24");
    private static final Color GAMMA = Color.web("#E879F9");
    private static final Color STRONG = Color.web("#34D399");
    private static final Color COULOMB = Color.web("#F87171");
    private static final Color[] COLOR_CHARGE = {
            Color.web("#EF4444"), Color.web("#22C55E"), Color.web("#3B82F6")};
    private static final String[] COLOR_NAMES = {"rouge", "vert", "bleu"};

    private static final String UI = "Segoe UI";
    private static final String MONO = "Consolas";

    private static final double BOHR_PM = 52.917721;
    private static final double NUCLEON_RADIUS_M = 0.84e-15;
    private static final double MIN_LOG_MPP = Math.log10(2e-18);
    private static final double MAX_LOG_MPP = Math.log10(2e-11);
    private static final double PHOTON_FLIGHT_S = 2.0;     // = PHOTON_FLIGHT_REAL_S du moteur
    private static final double PANEL_WIDTH = 300;
    private static final double HEADER = 18;     // l'élément est dans la barre du haut de l'application
    private static final double FOOTER = 58;     // réservé à la barre de vue ; la timeline, dessous, dit ce qui arrive ensuite
    private static final double LOUPE_RADIUS = 58;
    private static final double CALLOUT_WIDTH = 360;
    private static final double IDLE_SPIN = 0.05;          // rad/s : l'atome tourne lentement sur lui-même
    private static final double ROTATE_PER_PX = 0.0075;    // rad par pixel glissé
    private static final double NUCLEON_RADIUS_FM = 0.84;
    private static final Color DROP = Color.web("#A5B4FC");
    private static final Color PION_PLUS = Color.web("#FB923C");
    private static final Color PION_MINUS = Color.web("#60A5FA");
    private static final Color PION_ZERO = Color.web("#FDE68A");

    /** Ordre de Klechkowski (n + l croissant, puis n) jusqu'à 7p. */
    static final int[][] KLECHKOWSKI = {
            {1, 0}, {2, 0}, {2, 1}, {3, 0}, {3, 1}, {4, 0}, {3, 2}, {4, 1}, {5, 0}, {4, 2}, {5, 1},
            {6, 0}, {4, 3}, {5, 2}, {6, 1}, {7, 0}, {5, 3}, {6, 2}, {7, 1}};

    private final Canvas canvas = new Canvas(800, 600);
    private final GraphicsContext gc = canvas.getGraphicsContext2D();
    private final Random random = new Random(5);

    private UniverseFrame frame;
    private String statusMessage = "Connexion au moteur...";
    private final Map<String, ParticleInfo> particles = new HashMap<>();

    // --- Vue ---
    private double viewX;
    private double viewY;
    private double logMpp = -12.4;
    private double targetLogMpp = Double.NaN;
    private double anchorScreenX = Double.NaN;
    private double anchorScreenY;
    private double anchorWorldX;
    private double anchorWorldY;
    private double velocityX;
    private double velocityY;
    private boolean dragging;
    private boolean dragMoved;
    private double dragX;
    private double dragY;
    private long dragNanos;
    private boolean autoFrame = true;         // la vue suit l'atome tant qu'on ne la règle pas soi-même
    private double shrinkAt = Double.NaN;

    // --- Temps d'animation ---
    private long lastNanos;
    private double clock;
    private double delta;
    private double shownRadiusPm = Double.NaN;
    private double nucleusFlash;
    private String nucleusFlashLabel = "";
    private final List<Anim> anims = new ArrayList<>();
    private double stagger;
    private boolean focusedProton = true;

    // --- 3D : caméra, nuage de probabilité, goutte nucléaire ---------------
    private final Camera3D camera = new Camera3D();
    private final OrbitalCloud cloud = new OrbitalCloud();
    private final NuclearDrop drop = new NuclearDrop();
    private final Photon3DLayer photons3d = new Photon3DLayer();
    private final List<Photon3DLayer.View> photonViews = new ArrayList<>();
    private double yawVelocity = IDLE_SPIN;
    private double idleSpin = IDLE_SPIN;
    private double pitchVelocity;
    private boolean rotating;
    private boolean logView;            // le gouffre : noyau et nuage ensemble, en échelle logarithmique
    private boolean diving;             // plongée lente entre l'atome et le noyau
    private boolean panMode;            // glisser déplace la vue au lieu de la tourner
    private double targetYaw = Double.NaN;      // vue choisie (face, dessus, côté…) : la caméra y glisse
    private double targetPitch = Double.NaN;
    private double panGoalX = Double.NaN;       // double-clic : la vue glisse jusqu'au point choisi
    private double panGoalY = Double.NaN;
    private double kickX;               // recul du noyau (direction, px) et son âge
    private double kickY;
    private double kickAge = 99;
    private String transmutation = "";  // « Uranium → Thorium »
    private String transmutationDetail = "";
    private double transmutationAge = 99;
    private String lastElement = "";
    private int lastProtons = -1;
    private double[] impactOffset;      // là où le dernier photon a touché l'orbitale (px)
    private double[] actor;             // l'orbitale qui agit, là où sa probabilité est la plus forte (px, relatif au noyau)
    private Boolean panelWanted;        // panneau ouvert ou replié (null : selon la largeur)
    private boolean shellsExpanded;     // toutes les sous-couches pleines dans la carte
    private double[] panelChip;         // le bouton du panneau replié
    private double[] panelClose;        // le bouton qui replie le panneau
    private double[] shellsToggle;      // la ligne « sous-couches pleines »

    // --- Ce qui vient d'arriver ---
    private SimEvent callout;
    private double calloutAge;
    private Anim calloutAnim;            // la particule en mouvement que la bulle désigne
    private String calloutTarget = "";   // ce que désigne la flèche, en clair
    private double[] valenceActor;       // électron de valence réellement dessiné
    private String valenceLabel = "";
    private String notice = "";
    private double noticeAge = 99;
    private boolean calloutClosed;
    private boolean calloutUnfolded;

    // --- Interaction ---
    private double mouseX = -1;
    private double mouseY = -1;
    private String selectedParticle;
    private final List<Hit> hits = new ArrayList<>();

    /** Une particule dessinée, pour le survol et le clic. */
    private record Hit(double x, double y, double radius, String key, String hint) {
    }

    /** Un objet animé, en pixels relatifs au noyau. */
    private static final class Anim {
        String kind;               // photon, particle, electron, ring
        String key = "";           // fiche de particule associée
        double age;
        double delay;
        double duration;
        double fromX;
        double fromY;
        double toX;
        double toY;
        double bounceAt = -1;
        Color color;
        String label = "";
        double size = 4;
        boolean dashed;
        String spin = "";
        double nm;                    // longueur d'onde d'un photon (0 : inconnue)
        double[] impact;              // point d'impact tiré selon |ψ|² (pm, repère de chimiste)
        boolean measured;             // λ vient du moteur (on l'annonce)
        boolean homing;               // file vers l'orbitale qui agit, même si l'atome tourne
        double kinetic;               // énergie cinétique (eV) d'un électron libéré
        double dirX;                  // direction d'un électron libéré, à l'écran
        double dirY;
        double screenX = Double.NaN;  // position à l'écran, mise à jour à chaque image
        double screenY = Double.NaN;
    }

    private Runnable onZoomOutLimit = () -> {
    };

    /** Dézoomer au-delà de l'atome : on passe à l'échelle de la matière. */
    public void setOnZoomOutLimit(Runnable action) {
        onZoomOutLimit = action;
    }

    public SimCanvas() {
        canvas.setFocusTraversable(false);
        canvas.setOnScroll(event -> {
            logView = false;
            double current = Double.isNaN(targetLogMpp) ? logMpp : targetLogMpp;
            if (event.getDeltaY() < 0 && current >= MAX_LOG_MPP - 1e-3) {
                onZoomOutLimit.run();
                return;
            }
            autoFrame = false;
            zoomAround(event.getDeltaY() > 0 ? -0.15 : 0.15, event.getX(), event.getY());
        });
        canvas.setOnMousePressed(event -> {
            dragging = true;
            dragMoved = false;
            // Glisser tourne l'atome en 3D ; clic droit (ou Maj) glissé déplace la vue.
            rotating = event.isPrimaryButtonDown() && !event.isShiftDown() && !panMode;
            if (rotating) {
                yawVelocity = pitchVelocity = 0;
            }
            anchorScreenX = Double.NaN;
            dragX = event.getX();
            dragY = event.getY();
            dragNanos = System.nanoTime();
            velocityX = velocityY = 0;
        });
        canvas.setOnMouseDragged(event -> {
            double dx = event.getX() - dragX;
            double dy = event.getY() - dragY;
            if (Math.hypot(dx, dy) > 2) {
                dragMoved = true;
                if (!rotating) {
                    autoFrame = false;
                }
                canvas.setCursor(rotating ? Cursor.CLOSED_HAND : Cursor.MOVE);
            }
            long now = System.nanoTime();
            double seconds = Math.max(1e-3, (now - dragNanos) / 1e9);
            if (rotating) {
                camera.yaw += dx * ROTATE_PER_PX;
                camera.pitch -= dy * ROTATE_PER_PX;
                camera.update();
                yawVelocity = 0.6 * yawVelocity + 0.4 * dx * ROTATE_PER_PX / seconds;
                pitchVelocity = 0.6 * pitchVelocity - 0.4 * dy * ROTATE_PER_PX / seconds;
            } else {
                viewX -= dx * mpp();
                viewY -= dy * mpp();
                velocityX = 0.6 * velocityX + 0.4 * dx / seconds;
                velocityY = 0.6 * velocityY + 0.4 * dy / seconds;
            }
            dragX = event.getX();
            dragY = event.getY();
            dragNanos = now;
            mouseX = event.getX();
            mouseY = event.getY();
        });
        canvas.setOnMouseReleased(event -> {
            dragging = false;
            if ((System.nanoTime() - dragNanos) / 1e9 > 0.08) {
                velocityX = velocityY = 0;
                if (rotating) {
                    yawVelocity = pitchVelocity = 0;
                }
            }
            canvas.setCursor(Cursor.DEFAULT);
            if (!dragMoved) {
                if (event.getClickCount() == 2) {
                    centerOn(event.getX(), event.getY());
                } else {
                    onClick(event.getX(), event.getY());
                }
            }
        });
        canvas.setOnMouseMoved(event -> {
            mouseX = event.getX();
            mouseY = event.getY();
            canvas.setCursor(hitAt(mouseX, mouseY) != null || loupeHit(mouseX, mouseY) || inside(panelChip, mouseX, mouseY)
                    || inside(panelClose, mouseX, mouseY) || inside(shellsToggle, mouseX, mouseY) ? Cursor.HAND : Cursor.DEFAULT);
        });
        canvas.setOnMouseExited(event -> mouseX = mouseY = -1);
    }

    public Canvas getNode() {
        return canvas;
    }

    /** La couche 3D des photons, à poser exactement sur le canevas. */
    public javafx.scene.layout.Pane getOverlay() {
        return photons3d.getNode();
    }

    public void setStatusMessage(String message) {
        statusMessage = message;
    }

    public double lastDelta() {
        return delta;
    }

    public void setParticles(List<ParticleInfo> list) {
        particles.clear();
        for (ParticleInfo particle : list) {
            particles.put(particle.getKey(), particle);
        }
    }

    private void onClick(double x, double y) {
        double[] box = calloutBox();
        if (box != null && !calloutClosed && x >= box[0] + box[2] - 26 && x <= box[0] + box[2]
                && y >= box[1] && y <= box[1] + 26) {
            calloutClosed = true;
            return;
        }
        if (box != null && !calloutClosed && x >= box[0] && x <= box[0] + box[2] && y >= box[1]
                && y <= box[1] + box[3]) {
            calloutUnfolded = !calloutUnfolded && scale() != Scale.ATOME;
            return;
        }
        if (selectedParticle != null) {
            double[] card = particleCardBox();
            if (x >= card[0] && x <= card[0] + card[2] && y >= card[1] && y <= card[1] + card[3]) {
                selectedParticle = null;
                return;
            }
        }
        if (inside(panelChip, x, y)) {
            panelWanted = true;
            return;
        }
        if (inside(panelClose, x, y)) {
            panelWanted = false;
            return;
        }
        if (inside(shellsToggle, x, y)) {
            shellsExpanded = !shellsExpanded;
            return;
        }
        if (loupeHit(x, y)) {
            jumpTo(Scale.NOYAU);
            autoFrame = false;
            return;
        }
        Hit hit = hitAt(x, y);
        selectedParticle = hit == null || hit.key().isEmpty() ? null : hit.key();
    }

    // =================================================================
    // Frames et événements
    // =================================================================
    public void applyFrame(UniverseFrame incoming) {
        boolean first = frame == null;
        frame = incoming;
        statusMessage = null;
        boolean sudden = false;
        for (SimEvent event : incoming.getEventsList()) {
            String kind = event.getKind();
            sudden |= kind.equals("absorbed") || kind.equals("emitted") || kind.equals("photo_ionized")
                    || kind.equals("ionized") || kind.equals("captured") || kind.equals("excited");
        }
        cloud.update(cloudShells(), sudden);
        String elementName = incoming.getElement().getName();
        int protons = incoming.getAtom().getProtons();
        boolean decayed = false;
        for (SimEvent event : incoming.getEventsList()) {
            decayed |= event.getKind().equals("decay");
        }
        if (decayed && !lastElement.isEmpty() && protons != lastProtons) {
            transmutation = lastElement + " → " + elementName;
            transmutationDetail = "";
            transmutationAge = 0;
        } else if (decayed) {
            transmutation = incoming.getNucleus().getNuclide();
            transmutationAge = 0;
        }
        if (decayed) {
            // Z change : l'attraction sur tout le cortège change, le nuage se réajuste (rayons de Slater).
            transmutationDetail = (protons != lastProtons && lastProtons >= 0
                    ? "Z : " + lastProtons + " → " + protons + (protons < lastProtons
                    ? " · moins d'attraction, le nuage gonfle" : " · plus d'attraction, le nuage se resserre")
                    : "même élément, autre isotope");
        }
        lastElement = elementName;
        lastProtons = protons;
        drop.sync(incoming.getNucleonsList(), nuclearRadiusFm());
        String action = incoming.getLastAction();
        if (first || (incoming.getStructureChanged()
                && (action.equals("LOAD_ELEMENT") || action.equals("RESET")))) {
            jumpTo(Scale.ATOME);
        }
        if (Double.isNaN(shownRadiusPm)) {
            shownRadiusPm = electronRadiusPm();
        }
        stagger = 0;
        for (SimEvent event : incoming.getEventsList()) {
            onEvent(event);
        }
        if (incoming.getStructureChanged()) {
            refit();
        }
    }

    /** La vue suit la taille de l'atome, tant que l'utilisateur ne l'a pas réglée lui-même. */
    private void refit() {
        if (!autoFrame || logView || scale() != Scale.ATOME) {
            return;
        }
        double span = stageSpan();
        double outer = outerRadiusPx();
        if (outer > 0.3 * span) {
            jumpTo(Scale.ATOME);
            shrinkAt = Double.NaN;
        } else if (outer > 0 && outer < 0.12 * span) {
            shrinkAt = clock + 3.5;    // on laisse d'abord voir la descente de l'électron
        }
    }

    private void onEvent(SimEvent event) {
        String kind = event.getKind();
        if (kind.equals("info")) {
            // Une information ne chasse pas l'explication du dernier événement physique.
            notice = event.getTitle() + (event.getFormula().isEmpty() ? "" : " · " + event.getFormula());
            noticeAge = 0;
        } else if (!kind.equals("photon_fired")) {
            callout = event;
            calloutAge = 0;
            calloutClosed = false;
            calloutUnfolded = false;
            calloutAnim = null;
            calloutTarget = switch (kind) {
                case "emitted" -> "l'électron qui a émis la lumière";
                case "absorbed" -> "l'électron qui a absorbé le photon";
                case "excited" -> "l'électron excité";
                case "captured" -> "l'électron capturé";
                case "photo_ionized", "ionized" -> "l'électron arraché";
                case "rejected" -> "l'électron repoussé";
                case "decay" -> "le noyau qui se désintègre";
                case "nucleon" -> "le noyau";
                case "passed" -> "le photon, qui continue sa route";
                default -> "";
            };
        }
        double delay = stagger;
        double reach = Math.max(canvas.getWidth(), canvas.getHeight()) * 0.75;
        switch (kind) {
            case "photon_fired" -> {
                // Le photon file vers l'orbitale qui agit, là où sa probabilité est la plus forte.
                double[] target = electronOffset();
                Anim fired = photon(-stageCenterX() - 60, target[1] * 0.4 - 40, target[0], target[1],
                        event.getWavelengthNm(), PHOTON_FLIGHT_S, 0, "photon " + wavelengthText(event.getWavelengthNm()));
                fired.homing = true;
                // Où l'interaction aura lieu : un point tiré selon |ψ|² de l'orbitale qui agit (Monte-Carlo).
                int acting = cloud.actorIndex();
                if (acting >= 0) {
                    fired.impact = new double[3];
                    cloud.layer(acting).sample(random, fired.impact);
                }
                anims.add(fired);
            }
            case "absorbed" -> {
                // Absorbé : le photon disparaît, l'éclair sature la région de l'orbitale, la nouvelle s'illumine.
                flash(impactOrActor(), PhotonPacket.colour(event.getWavelengthNm()), 1.0);
                cloud.pulseActor(1.0);
            }
            case "passed" -> track(photon(0, 0, reach, -60, event.getWavelengthNm(), 4.0, 0,
                    "non absorbé : il continue"), "le photon, qui continue sa route");
            case "emitted" -> {
                double[] origin = electronOffset();
                flash(origin, PhotonPacket.colour(event.getWavelengthNm()), 0.6);
                cloud.pulseActor(0.6);
                double angle = -Math.PI / 2 + (random.nextDouble() - 0.5) * 2.2;
                track(photon(origin[0], origin[1], origin[0] + reach * Math.cos(angle),
                        origin[1] + reach * Math.sin(angle), event.getWavelengthNm(), 5.0, delay,
                        "photon émis · " + wavelengthText(event.getWavelengthNm())), "le photon émis");
                for (float extra : event.getExtraWavelengthsNmList()) {
                    double other = angle + Math.PI * 0.8;
                    anims.add(photon(origin[0], origin[1], origin[0] + reach * Math.cos(other),
                            origin[1] + reach * Math.sin(other), extra, 5.0, delay, "2e photon · " + wavelengthText(extra)));
                }
                stagger += 1.2;
            }
            case "photo_ionized", "ionized" -> {
                // L'orbitale se déchire : l'électron part en paquet d'onde libre, l'ion se contracte (Slater).
                double[] origin = kind.equals("photo_ionized") ? impactOrActor() : electronOffset();
                if (kind.equals("photo_ionized")) {
                    flash(origin, PhotonPacket.colour(event.getWavelengthNm()), 1.0);
                }
                track(photoelectron(origin, Math.max(0.05, event.getKineticEv()), delay, reach),
                        "l'électron arraché");
                cloud.pulseActor(0.7);
            }
            case "captured" -> {
                double angle = random.nextDouble() * 2 * Math.PI;
                double[] target = electronOffset();
                track(electron(reach * Math.cos(angle), reach * Math.sin(angle), target[0], target[1],
                        2.4, delay, "e⁻ capturé", -1), "l'électron capturé");
            }
            case "rejected" -> {
                double angle = random.nextDouble() * 2 * Math.PI;
                double outer = Math.max(36, outerRadiusPx() * 1.2);
                int count = Math.max(1, event.getCount());
                for (int k = 0; k < count; k++) {
                    double a = angle + k * 0.6;
                    Anim pushed = electron(reach * Math.cos(a), reach * Math.sin(a), outer * Math.cos(a),
                            outer * Math.sin(a), 4.0, delay + k * 0.3, "e⁻ repoussé", 0.5);
                    if (k == 0) {
                        track(pushed, "l'électron repoussé");
                    } else {
                        anims.add(pushed);
                    }
                }
            }
            case "nucleon" -> {
                boolean proton = event.getParticle().equals("proton");
                boolean leaving = event.getTitle().contains("quitte");
                double angle = random.nextDouble() * 2 * Math.PI;
                Anim anim = new Anim();
                anim.kind = "particle";
                anim.key = proton ? "proton" : "neutron";
                anim.color = proton ? Visuals.PROTON : Visuals.NEUTRON;
                anim.size = 6;
                anim.duration = 2.2;
                anim.label = proton ? "p⁺" : "n";
                anim.fromX = leaving ? 0 : reach * Math.cos(angle);
                anim.fromY = leaving ? 0 : reach * Math.sin(angle);
                anim.toX = leaving ? reach * Math.cos(angle) : 0;
                anim.toY = leaving ? reach * Math.sin(angle) : 0;
                anims.add(anim);
                nucleusFlash = 0.8;
                nucleusFlashLabel = "";
            }
            case "decay" -> {
                spawnDecay(event, delay);
                transmutationAge = 0;
            }
            default -> {
            }
        }
    }

    /** Ajoute la particule animée et fait pointer la bulle sur elle tant qu'elle vole. */
    private void track(Anim anim, String target) {
        anims.add(anim);
        calloutAnim = anim;
        calloutTarget = target;
    }

    private Anim photon(double fromX, double fromY, double toX, double toY, double nm, double duration,
                        double delay, String label) {
        Anim anim = new Anim();
        anim.kind = "photon";
        anim.key = "photon";
        anim.fromX = fromX;
        anim.fromY = fromY;
        anim.toX = toX;
        anim.toY = toY;
        anim.duration = duration;
        anim.delay = delay;
        anim.color = PhotonPacket.colour(nm);
        anim.nm = nm;
        anim.measured = nm > 0;
        anim.label = label;
        return anim;
    }

    /** Le point où le dernier photon a touché le nuage (tiré selon |ψ|²), sinon l'orbitale qui agit. */
    private double[] impactOrActor() {
        double[] at = impactOffset != null ? impactOffset.clone() : electronOffset();
        impactOffset = null;
        return at;
    }

    /** L'éclair de l'interaction, au point d'impact. */
    private void flash(double[] at, Color color, double strength) {
        Anim anim = new Anim();
        anim.kind = "flash";
        anim.fromX = anim.toX = at[0];
        anim.fromY = anim.toY = at[1];
        anim.duration = 1.1;
        anim.color = color;
        anim.size = strength;
        anims.add(anim);
    }

    /**
     * L'électron libéré : un paquet d'onde libre, pas une bille. Direction
     * tirée selon cos²θ autour du champ E du photon (axe z de l'atome :
     * l'électron part surtout le long de la polarisation), vitesse à l'écran
     * ∝ √K, longueur d'onde de de Broglie λ = h/p = 1,226 nm/√(K/eV).
     */
    private Anim photoelectron(double[] origin, double kineticEv, double delay, double reach) {
        double[] u = new double[3];
        double[] q = new double[3];
        double dx = 1;
        double dy = 0;
        for (int tries = 0; tries < 200; tries++) {
            double z = 2 * random.nextDouble() - 1;
            double phi = 2 * Math.PI * random.nextDouble();
            double ring = Math.sqrt(1 - z * z);
            u[0] = ring * Math.cos(phi);
            u[1] = ring * Math.sin(phi);
            u[2] = z;
            if (random.nextDouble() > z * z) {
                continue;
            }
            camera.fromChemistry(u[0], u[1], u[2], q);
            double length = Math.hypot(q[0], q[1]);
            if (length > 0.3) {
                dx = q[0] / length;
                dy = q[1] / length;
                break;
            }
        }
        double speed = clamp(45 * Math.sqrt(kineticEv), 30, 240);    // ∝ v = √(2K/m), ralenti pour l'analyse
        Anim anim = new Anim();
        anim.kind = "photoelectron";
        anim.key = "electron";
        anim.fromX = origin[0];
        anim.fromY = origin[1];
        anim.dirX = dx;
        anim.dirY = dy;
        anim.toX = origin[0] + dx * reach;
        anim.toY = origin[1] + dy * reach;
        anim.duration = reach / speed;
        anim.delay = delay;
        anim.kinetic = kineticEv;
        anim.color = ELECTRON;
        double lambda = 1.22643 / Math.sqrt(kineticEv);
        anim.label = "e⁻ libre · K = " + Visuals.format(kineticEv) + " eV · λ = h/p = "
                + Visuals.formatLength(lambda * 1e-9);
        return anim;
    }

    private Anim electron(double fromX, double fromY, double toX, double toY, double duration, double delay,
                          String label, double bounceAt) {
        Anim anim = new Anim();
        anim.kind = "electron";
        anim.key = "electron";
        anim.fromX = fromX;
        anim.fromY = fromY;
        anim.toX = toX;
        anim.toY = toY;
        anim.duration = duration;
        anim.delay = delay;
        anim.color = ELECTRON;
        anim.label = label;
        anim.bounceAt = bounceAt;
        anim.spin = random.nextBoolean() ? "↑" : "↓";
        return anim;
    }

    private void ring(double[] at, Color color) {
        Anim anim = new Anim();
        anim.kind = "ring";
        anim.fromX = at[0];
        anim.fromY = at[1];
        anim.duration = 1.6;
        anim.color = color;
        anims.add(anim);
    }

    /**
     * Le recul du noyau, par conservation de l'impulsion : la particule part
     * selon `angle`, le noyau fils recule à l'opposé. Pour une α :
     * p = √(2 m_α E_α), v = p / M. Pour une β, l'antineutrino emporte une
     * part inconnue : direction approchée, p ≈ √(Q² + 2 Q m_e). Le
     * déplacement à l'écran est amorti (le vrai trajet en 1 ns dépasse la
     * scène) ; la vitesse est écrite.
     */
    private void recoil(SimEvent event, double angle) {
        double q = event.getQMev();
        int a = Math.max(1, frame.getAtom().getMassNumber());
        double momentum;     // MeV/c
        if (event.getDecayMode().equals("alpha")) {
            double kinetic = event.getKineticEv() > 0 ? event.getKineticEv() / 1e6 : q * (a - 4.0) / a;
            momentum = Math.sqrt(2 * 3727.379 * Math.max(0, kinetic));
        } else {
            momentum = Math.sqrt(Math.max(0, q * q + 2 * q * 0.511));
        }
        if (momentum <= 0) {
            return;
        }
        double velocity = momentum / (a * 931.494) * 299_792.458;      // km/s
        double push = clamp(4 + 5 * Math.log10(1 + velocity), 3, 24);
        kickX = -Math.cos(angle) * push;
        kickY = -Math.sin(angle) * push;
        kickAge = 0;
        transmutationDetail = (transmutationDetail.isEmpty() ? "" : transmutationDetail + " · ")
                + "recul du noyau : " + Visuals.format(velocity) + " km/s (p = " + Visuals.format(momentum) + " MeV/c)";
    }

    /** L'onde de la désintégration : trois fronts qui s'éloignent du noyau, avec rémanence (visibles à toute vitesse). */
    private void shockwave(String mode) {
        Color color = switch (mode) {
            case "alpha" -> ALPHA;
            case "beta-", "beta+" -> ELECTRON;
            default -> GAMMA;
        };
        for (int k = 0; k < 3; k++) {
            Anim anim = new Anim();
            anim.kind = "shock";
            anim.delay = 0.12 * k;
            anim.duration = 1.5;
            anim.color = color;
            anim.size = 1 - 0.25 * k;
            anims.add(anim);
        }
    }

    /** Ce qui sort du noyau : chaque particule part du noyau, avec sa fiche. */
    private void spawnDecay(SimEvent event, double delay) {
        nucleusFlash = 1.0;
        shockwave(event.getDecayMode());
        nucleusFlashLabel = switch (event.getDecayMode()) {
            case "beta-" -> "un neutron devient proton (d → u)";
            case "beta+" -> "un proton devient neutron (u → d)";
            case "CE" -> "un proton capture un électron";
            case "alpha" -> "2 protons + 2 neutrons éjectés";
            case "IT" -> "le noyau se désexcite";
            case "SF" -> "le noyau se scinde";
            default -> "un nucléon s'échappe";
        };
        double reach = Math.max(canvas.getWidth(), canvas.getHeight()) * 0.8;
        double base = random.nextDouble() * 2 * Math.PI;
        recoil(event, base);
        int index = 0;
        for (String particle : event.getParticlesList()) {
            double angle = base + index * 2.3;
            Anim anim = new Anim();
            anim.kind = "particle";
            anim.delay = delay + 0.5 * index;
            anim.toX = reach * Math.cos(angle);
            anim.toY = reach * Math.sin(angle);
            anim.duration = 6.0;
            if (particle.startsWith("électron")) {
                anim.key = "electron";
                anim.color = ELECTRON;
                anim.label = "e⁻ (β⁻) : créé dans le noyau";
            } else if (particle.startsWith("positon")) {
                anim.key = "positron";
                anim.color = Color.web("#FCA5A5");
                anim.label = "e⁺ (β⁺) : positon";
            } else if (particle.startsWith("particule α")) {
                anim.key = "alpha";
                anim.color = ALPHA;
                anim.size = 8;
                anim.duration = 7.0;
                anim.label = event.getKineticEv() > 0
                        ? "α · " + Visuals.fixed(event.getKineticEv() / 1e6, 2) + " MeV" : "α (2p + 2n)";
            } else if (particle.startsWith("neutrino") || particle.startsWith("antineutrino")) {
                boolean anti = particle.startsWith("anti");
                anim.key = anti ? "antineutrino" : "neutrino";
                anim.color = Color.web("#CBD5E1", 0.7);
                anim.size = 2.5;
                anim.dashed = true;
                anim.duration = 4.0;
                anim.label = anti ? "ν̄ antineutrino : ne touche rien" : "ν neutrino : ne touche rien";
            } else if (particle.startsWith("neutron")) {
                anim.key = "neutron";
                anim.color = Visuals.NEUTRON;
                anim.size = 6;
                anim.label = "n";
            } else if (particle.startsWith("proton")) {
                anim.key = "proton";
                anim.color = Visuals.PROTON;
                anim.size = 6;
                anim.label = "p⁺";
            } else if (particle.startsWith("photon") || particle.startsWith("rayon X")) {
                anim.kind = "photon";
                anim.key = "photon";
                anim.color = GAMMA;
                anim.dashed = true;
                anim.label = particle.startsWith("photon") ? "γ" : "rayon X";
            } else {
                anim.color = ALPHA;
                anim.size = 9;
                anim.label = particle;
            }
            anims.add(anim);
            index++;
        }
    }

    // =================================================================
    // Géométrie
    // =================================================================
    private double mpp() {
        return Math.pow(10, logMpp);
    }

    /** Le panneau de droite : replié par défaut sur les écrans étroits, pour laisser l'atome respirer. */
    private boolean panelShown() {
        if (canvas.getWidth() <= 860) {
            return false;
        }
        return panelWanted != null ? panelWanted : canvas.getWidth() >= 1250;
    }

    private static boolean inside(double[] box, double x, double y) {
        return box != null && x >= box[0] && x <= box[0] + box[2] && y >= box[1] && y <= box[1] + box[3];
    }

    private double stageRight() {
        return canvas.getWidth() - (panelShown() ? PANEL_WIDTH + 16 : 0);
    }

    private double stageCenterX() {
        return stageRight() / 2;
    }

    private double stageCenterY() {
        return (HEADER + canvas.getHeight() - FOOTER) / 2;
    }

    private double stageSpan() {
        return Math.max(200, Math.min(stageRight(), canvas.getHeight() - HEADER - FOOTER));
    }

    private double[] center() {
        // Recul : une impulsion brève (pic à 80 ms), puis retour amorti.
        double u = kickAge / 0.08;
        double shove = u < 12 ? u * Math.exp(1 - u) : 0;
        return new double[]{stageCenterX() - viewX / mpp() + kickX * shove, stageCenterY() - viewY / mpp() + kickY * shove};
    }

    private double pmToPx(double pm) {
        return pm * Visuals.PICOMETRE / mpp();
    }

    private double outerRadiusPm() {
        double radius = 0;
        if (frame != null) {
            for (Subshell subshell : frame.getSubshellsList()) {
                radius = Math.max(radius, subshell.getRadiusPm());
            }
            radius = Math.max(radius, frame.getExcitedRadiusPm());
            if (isOneElectron()) {
                radius = Math.max(radius, electronRadiusPm());
            }
        }
        return radius;
    }

    private double outerRadiusPx() {
        return pmToPx(outerRadiusPm());
    }

    private boolean isOneElectron() {
        return frame != null && frame.getAtom().getElectrons() == 1 && frame.getAtom().getProtons() >= 1;
    }

    /** n de l'état "2p", "10d"... */
    public static int principal(String state) {
        int end = 0;
        while (end < state.length() && Character.isDigit(state.charAt(end))) {
            end++;
        }
        return end == 0 ? 1 : Integer.parseInt(state.substring(0, end));
    }

    /** Rayon de Bohr de l'état : r = n² a₀ / Z. */
    private double electronRadiusPm() {
        if (!isOneElectron()) {
            return 0;
        }
        int n = principal(frame.getElectronState());
        return n * n * BOHR_PM / Math.max(1, frame.getAtom().getProtons());
    }

    /** Position de l'électron qui agit, en pixels relatifs au noyau. */
    private double[] electronOffset() {
        if (actor != null && (logView || scale() == Scale.ATOME)) {
            return actor.clone();
        }
        double radius;
        if (isOneElectron()) {
            radius = radiusPx(Double.isNaN(shownRadiusPm) ? electronRadiusPm() : shownRadiusPm);
        } else if (frame != null && frame.getExcitationLevel() > 0) {
            radius = radiusPx(frame.getExcitedRadiusPm());
        } else {
            radius = radiusPx(outerRadiusPm());
        }
        radius = Math.max(radius, 12);
        double angle = 0.0;
        return new double[]{radius * Math.cos(angle), radius * Math.sin(angle)};
    }

    /** Rayon à l'écran d'une distance au noyau (pm) : vraie échelle, ou celle du gouffre. */
    private double radiusPx(double pm) {
        return logView ? gulfMap().px(pm * 1000) : pmToPx(pm);
    }

    private double nuclearRadiusFm() {
        return frame == null ? 1.2 : Math.max(0.9, frame.getAtom().getNuclearRadiusFm());
    }

    // =================================================================
    // Navigation
    // =================================================================
    public void jumpTo(Scale scale) {
        double span = stageSpan();
        double metres = switch (scale) {
            // Le nuage s'étend jusqu'à ~2 fois le rayon le plus probable : on cadre le nuage entier.
            case ATOME -> Math.max(60, outerRadiusPm()) * Visuals.PICOMETRE / (0.21 * span);
            case NOYAU -> nuclearRadiusFm() * Visuals.FEMTOMETRE / (0.30 * span);
            case NUCLEON -> NUCLEON_RADIUS_M / (0.24 * span);
        };
        autoFrame = scale == Scale.ATOME;
        logView = false;
        targetLogMpp = clamp(Math.log10(metres), MIN_LOG_MPP, MAX_LOG_MPP);
        // Plus de 1,5 décade d'écart : une vraie plongée, lente, à travers le vide.
        diving = Math.abs(targetLogMpp - logMpp) > 1.5;
        anchorScreenX = stageCenterX();
        anchorScreenY = stageCenterY();
        anchorWorldX = anchorWorldY = 0;
        velocityX = velocityY = 0;
    }

    /** Le gouffre des échelles : une vue logarithmique où le noyau et le nuage tiennent ensemble. */
    public void toggleLogView() {
        boolean entering = !logView;
        if (!entering) {
            jumpTo(Scale.ATOME);
            return;
        }
        logView = true;
        diving = false;
        targetLogMpp = Double.NaN;
        anchorScreenX = Double.NaN;
        viewX = viewY = velocityX = velocityY = 0;
    }

    public boolean isLogView() {
        return logView;
    }

    /** Glisser déplace la vue (true) ou la tourne en 3D (false). */
    public void setPanMode(boolean pan) {
        panMode = pan;
    }

    public boolean isPanMode() {
        return panMode;
    }

    /** La rotation lente de l'atome sur lui-même. */
    public void setAutoSpin(boolean on) {
        idleSpin = on ? IDLE_SPIN : 0;
        yawVelocity = idleSpin;
    }

    public boolean isAutoSpin() {
        return idleSpin != 0;
    }

    /** Ramène l'atome au centre, à son cadrage, vu de trois quarts. */
    public void recenter() {
        panGoalX = panGoalY = Double.NaN;
        velocityX = velocityY = 0;
        jumpTo(logView ? Scale.ATOME : scale());
        viewPreset("3/4");
    }

    /** Vues toutes faites : « face » (axe z vers le haut), « dessus », « côté », « 3/4 ». */
    public void viewPreset(String name) {
        switch (name) {
            case "face" -> { targetYaw = 0; targetPitch = 0; }
            case "dessus" -> { targetYaw = 0; targetPitch = -Math.PI / 2 + 0.02; }
            case "côté" -> { targetYaw = Math.PI / 2; targetPitch = 0; }
            default -> { targetYaw = 0.55; targetPitch = -0.38; }
        }
        yawVelocity = pitchVelocity = 0;
    }

    /** Double-clic : la vue glisse jusqu'au point choisi et s'en approche. */
    private void centerOn(double x, double y) {
        autoFrame = false;
        logView = false;
        double base = Double.isNaN(panGoalX) ? viewX : panGoalX;
        double baseY = Double.isNaN(panGoalY) ? viewY : panGoalY;
        panGoalX = base + (x - stageCenterX()) * mpp();
        panGoalY = baseY + (y - stageCenterY()) * mpp();
        velocityX = velocityY = 0;
        double current = Double.isNaN(targetLogMpp) ? logMpp : targetLogMpp;
        targetLogMpp = clamp(current - Math.log10(1.6), MIN_LOG_MPP, MAX_LOG_MPP);
        anchorScreenX = Double.NaN;
    }

    /** Pour les aperçus : orienter la caméra (degrés) et figer sa rotation propre. */
    void orient(double yawDegrees, double pitchDegrees, boolean still) {
        camera.yaw = Math.toRadians(yawDegrees);
        camera.pitch = Math.toRadians(pitchDegrees);
        camera.update();
        yawVelocity = still ? 0 : IDLE_SPIN;
        pitchVelocity = 0;
        idleSpin = still ? 0 : IDLE_SPIN;
    }

    public void nudgeZoom(double factor) {
        logView = false;
        autoFrame = false;
        double[] c = center();
        zoomAround(-Math.log10(factor), c[0], c[1]);
    }

    private void zoomAround(double logStep, double screenX, double screenY) {
        double base = Double.isNaN(targetLogMpp) ? logMpp : targetLogMpp;
        targetLogMpp = clamp(base + logStep, MIN_LOG_MPP, MAX_LOG_MPP);
        anchorScreenX = screenX;
        anchorScreenY = screenY;
        anchorWorldX = viewX + (screenX - stageCenterX()) * mpp();
        anchorWorldY = viewY + (screenY - stageCenterY()) * mpp();
    }

    public Scale scale() {
        if (logView) {
            return Scale.ATOME;
        }
        double nucleonPx = NUCLEON_RADIUS_M / mpp();
        double nucleusPx = nuclearRadiusFm() * Visuals.FEMTOMETRE / mpp();
        if (nucleonPx > 0.12 * stageSpan()) {
            return Scale.NUCLEON;
        }
        return nucleusPx > 20 ? Scale.NOYAU : Scale.ATOME;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    // =================================================================
    // Boucle
    // =================================================================
    public void tick(long nowNanos) {
        delta = lastNanos == 0 ? 0 : Math.min(0.05, (nowNanos - lastNanos) / 1e9);
        lastNanos = nowNanos;
        clock += delta;
        advanceView();
        if (!Double.isNaN(shrinkAt) && clock >= shrinkAt) {
            shrinkAt = Double.NaN;
            if (autoFrame && scale() == Scale.ATOME && outerRadiusPx() < 0.12 * stageSpan()) {
                jumpTo(Scale.ATOME);
            }
        }
        if (!Double.isNaN(targetYaw) && !(dragging && rotating)) {
            // Vers la vue choisie, par le plus court chemin.
            double dYaw = Math.IEEEremainder(targetYaw - camera.yaw, 2 * Math.PI);
            double follow = 1 - Math.exp(-5 * delta);
            camera.yaw += dYaw * follow;
            camera.pitch += (targetPitch - camera.pitch) * follow;
            if (Math.abs(dYaw) < 1e-3 && Math.abs(targetPitch - camera.pitch) < 1e-3) {
                targetYaw = targetPitch = Double.NaN;
            }
        } else if (!(dragging && rotating)) {
            // Inertie de la rotation, puis retour à la lente rotation propre.
            camera.yaw += yawVelocity * delta;
            camera.pitch += pitchVelocity * delta;
            double relax = 1 - Math.exp(-1.4 * delta);
            yawVelocity += (idleSpin - yawVelocity) * relax;
            pitchVelocity -= pitchVelocity * relax;
        }
        camera.update();
        kickAge += delta;
        transmutationAge += delta;
        cloud.tick(delta);
        drop.tick(delta);
        nucleusFlash = Math.max(0, nucleusFlash - delta * 0.18);
        calloutAge += delta;
        noticeAge += delta;
        if (isOneElectron()) {
            double target = electronRadiusPm();
            // L'électron change d'orbite en ~1 s : on le voit partir et arriver.
            shownRadiusPm = Double.isNaN(shownRadiusPm) ? target
                    : shownRadiusPm + (target - shownRadiusPm) * (1 - Math.exp(-2.2 * delta));
        } else {
            shownRadiusPm = Double.NaN;
        }
        for (Anim anim : anims) {
            if (anim.delay > 0) {
                anim.delay -= delta;
            } else {
                anim.age += delta;
            }
        }
        anims.removeIf(anim -> anim.age > anim.duration);
        render();
    }

    private void advanceView() {
        if (!Double.isNaN(targetLogMpp)) {
            double gap = targetLogMpp - logMpp;
            if (diving) {
                // Vitesse constante (≤ 1,5 décade par seconde), freinée à l'arrivée.
                double speed = Math.min(1.5, 0.3 + Math.abs(gap) * 1.2);
                logMpp += Math.signum(gap) * Math.min(Math.abs(gap), speed * delta);
            } else {
                logMpp += gap * (1 - Math.exp(-4.5 * delta));
            }
            if (Math.abs(targetLogMpp - logMpp) < 1e-4) {
                logMpp = targetLogMpp;
                targetLogMpp = Double.NaN;
                diving = false;
            }
            if (!Double.isNaN(anchorScreenX) && !dragging) {
                viewX = anchorWorldX - (anchorScreenX - stageCenterX()) * mpp();
                viewY = anchorWorldY - (anchorScreenY - stageCenterY()) * mpp();
            }
            if (Double.isNaN(targetLogMpp)) {
                anchorScreenX = Double.NaN;
            }
        }
        if (!Double.isNaN(panGoalX) && !dragging) {
            double follow = 1 - Math.exp(-6 * delta);
            viewX += (panGoalX - viewX) * follow;
            viewY += (panGoalY - viewY) * follow;
            if (Math.hypot(panGoalX - viewX, panGoalY - viewY) < 0.5 * mpp()) {
                panGoalX = panGoalY = Double.NaN;
            }
        } else if (!dragging) {
            // Inertie : la carte glisse, et ralentit doucement.
            viewX -= velocityX * delta * mpp();
            viewY -= velocityY * delta * mpp();
            double damping = Math.exp(-1.6 * delta);
            velocityX *= damping;
            velocityY *= damping;
            if (Math.hypot(velocityX, velocityY) < 3) {
                velocityX = velocityY = 0;
            }
            // Rappel élastique, sans butée : l'atome ne se perd jamais.
            double limit = Math.max(canvas.getWidth(), canvas.getHeight()) * mpp();
            double distance = Math.hypot(viewX, viewY);
            if (distance > limit) {
                double pull = (1 - limit / distance) * (1 - Math.exp(-2.0 * delta));
                viewX -= viewX * pull;
                viewY -= viewY * pull;
            }
        }
    }

    // =================================================================
    // Rendu
    // =================================================================
    private void render() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        hits.clear();
        photonViews.clear();
        drawBackground(width, height);
        if (frame == null || statusMessage != null) {
            centredText(width / 2, height / 2, statusMessage == null ? "" : statusMessage, 15, SOFT);
            return;
        }
        double[] c = center();
        Scale scale = scale();
        if (logView) {
            drawGulf(c);
        } else if (scale == Scale.ATOME) {
            drawElectrons(c);
            drawNucleusPoint(c);
        } else {
            drawNucleus(c[0], c[1], fmMap(mpp()), scale == Scale.NUCLEON, false);
        }
        drawAnims(c);
        if (loupeVisible()) {
            drawLoupe(c);
        }
        panelChip = null;
        panelClose = null;
        shellsToggle = null;
        if (panelShown()) {
            drawPanels(width, height, scale);
        } else if (width > 860) {
            drawPanelChip(scale);
        }
        if (logView) {
            drawGulfLegend(height);
        } else {
            drawScaleBar(height);
        }
        drawDive();
        drawTransmutation();
        drawCallout(c, scale);
        if (selectedParticle != null) {
            drawParticleCard();
        } else {
            drawTooltip();
        }
        photons3d.update(photonViews, camera);
    }

    private void drawBackground(double width, double height) {
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, width, height);
        double radius = Math.max(width, height) * 0.7;
        gc.setFill(new RadialGradient(0, 0, stageCenterX(), stageCenterY(), radius, false, CycleMethod.NO_CYCLE,
                new Stop(0, BACKGROUND_GLOW), new Stop(1, BACKGROUND)));
        gc.fillRect(0, 0, width, height);
    }

    // --- Électrons : le nuage de probabilité, en 3D ------------------------------
    private void drawElectrons(double[] c) {
        int charge = frame.getAtom().getNetCharge();
        double outer = outerRadiusPx();
        if (charge != 0 && outer > 4) {
            Color halo = charge > 0 ? Color.web("#F87171") : Color.web("#60A5FA");
            double alpha = Math.min(0.2, 0.05 * Math.abs(charge) + 0.05);
            gc.setFill(new RadialGradient(0, 0, c[0], c[1], outer * 1.5, false, CycleMethod.NO_CYCLE,
                    new Stop(0, halo.deriveColor(0, 1, 1, 0)), new Stop(0.75, halo.deriveColor(0, 1, 1, alpha)),
                    new Stop(1, halo.deriveColor(0, 1, 1, 0))));
            gc.fillOval(c[0] - outer * 1.5, c[1] - outer * 1.5, outer * 3, outer * 3);
        }
        OrbitalCloud.Radial map = OrbitalCloud.linear(Visuals.PICOMETRE / mpp());
        // En plongée, la caméra entre dans le nuage : on ne voit plus que la tranche autour du noyau.
        double slab = 1.5 * stageSpan() * mpp() / Visuals.PICOMETRE;
        cloud.draw(gc, camera, c[0], c[1], map, 0, 0, stageRight(), canvas.getHeight() - FOOTER, slab);
        drawCloudLegend();
        drawCloudLabels(c, map);
    }

    /** Les sous-couches occupées, telles que le moteur les envoie, prêtes pour le nuage 3D. */
    private List<OrbitalCloud.Shell> cloudShells() {
        List<OrbitalCloud.Shell> shells = new ArrayList<>();
        if (frame.getAtom().getElectrons() <= 0) {
            return shells;
        }
        if (isOneElectron()) {
            // Hydrogénoïde exact ; le moteur suit (n, l), pas m : mélange isotrope des 2l + 1 orientations.
            String state = frame.getElectronState();
            int n = principal(state);
            int l = Math.min(n - 1, Math.max(0, "spdfghi".indexOf(state.charAt(state.length() - 1))));
            int z = Math.max(1, frame.getAtom().getProtons());
            shells.add(new OrbitalCloud.Shell(n, l, z, n * n * BOHR_PM / z, 1, null, true,
                    n > 1 ? EXCITED : ELECTRON, n + "spdfghi".substring(l, l + 1)));
            return shells;
        }
        for (Subshell subshell : frame.getSubshellsList()) {
            if (subshell.getElectrons() <= 0) {
                continue;
            }
            int[] occupation = subshell.getOccupationList().stream().mapToInt(Integer::intValue).toArray();
            shells.add(new OrbitalCloud.Shell(subshell.getN(), subshell.getL(), subshell.getZEff(),
                    subshell.getRadiusPm(), subshell.getElectrons(), occupation.length == 0 ? null : occupation, false,
                    Visuals.subshellColor(subshell.getL()), subshell.getLabel()));
        }
        if (frame.getExcitationLevel() > 0 && frame.getExcitedRadiusPm() > 0) {
            int n = frame.getExcitationLevel();
            shells.add(new OrbitalCloud.Shell(n, 0, 1, frame.getExcitedRadiusPm(), 1, new int[]{1}, false, EXCITED,
                    n + "s (excité)"));
        }
        return shells;
    }

    private static boolean isExcitedLayer(OrbitalCloud.Layer layer) {
        return layer.shell.label().endsWith("(excité)");
    }

    /** Le nom de chaque sous-couche, posé à son rayon le plus probable, et l'électron que désigne la bulle. */
    private void drawCloudLabels(double[] c, OrbitalCloud.Radial map) {
        valenceActor = null;
        int coreElectrons = 0;
        double coreRadius = 0;
        int valence = -1;
        for (int i = 0; i < cloud.layerCount(); i++) {
            if (!isExcitedLayer(cloud.layer(i))) {
                valence = i;      // dernière sous-couche dans l'ordre de remplissage : la valence
            }
        }
        for (int i = 0; i < cloud.layerCount(); i++) {
            OrbitalCloud.Layer layer = cloud.layer(i);
            OrbitalCloud.Shell shell = layer.shell;
            boolean excited = isExcitedLayer(layer);
            double radius = map.px(layer.peakPm());
            if (radius < 16 && !excited && !shell.exact()) {
                // Trop serrées pour être nommées : les couches internes forment le cœur lumineux.
                coreElectrons += shell.electrons();
                coreRadius = Math.max(coreRadius, radius);
                continue;
            }
            double angle = labelAngle(c, radius, shell.exact() || excited ? -Math.PI / 5
                    : -Math.PI / 4 + shell.l() * 0.32 - (shell.n() % 2) * 0.12);
            if (Double.isNaN(angle)) {
                continue;      // hors de la scène : pas d'étiquette coupée sous l'en-tête
            }
            double x = c[0] + radius * Math.cos(angle);
            double y = c[1] + radius * Math.sin(angle);
            Color colour = shell.colour();
            gc.setFill(colour.deriveColor(0, 1, 1, 0.85));
            gc.fillOval(x - 2, y - 2, 4, 4);
            if (radius > 34 || excited || shell.exact()) {
                smallLabel(x + 6, y - 4, layerLabel(layer), colour.deriveColor(0, 0.8, 1.15, 0.95));
            }
            hits.add(new Hit(x, y, 12, "electron", cloudHint(i)));
        }
        if (coreElectrons > 0) {
            hits.add(new Hit(c[0], c[1], Math.max(9, coreRadius + 4), "electron", coreElectrons
                    + " électrons des couches internes : leur nuage serré fait le cœur lumineux (zoome)"));
        }
        int acting = cloud.actorIndex();
        if (acting >= 0) {
            actor = lobePoint(cloud.layer(acting), map);
        } else {
            actor = null;
        }
        if (valence >= 0) {
            // La bulle désigne la valence là où son nuage est le plus dense (le lobe de la dernière orbitale).
            OrbitalCloud.Layer layer = cloud.layer(valence);
            double[] q = new double[3];
            double peak = layer.peakPm();
            camera.fromChemistry(layer.lobe[0] * peak, layer.lobe[1] * peak, layer.lobe[2] * peak, q);
            double b = Math.hypot(q[0], q[1]);
            if (b < 0.35 * peak) {
                // Lobe tourné vers nous : on prend un point du bord, à la même distance.
                q[0] = 0.8 * peak;
                q[1] = -0.6 * peak;
                b = peak;
            }
            double k = map.px(b) / b;
            valenceActor = new double[]{c[0] + q[0] * k, c[1] + q[1] * k};
            valenceLabel = layer.shell.label();
        }
    }

    /** Le point le plus probable d'une orbitale (son lobe, au rayon le plus probable), en px relatifs au noyau. */
    private double[] lobePoint(OrbitalCloud.Layer layer, OrbitalCloud.Radial map) {
        double[] q = new double[3];
        double peak = layer.peakPm();
        camera.fromChemistry(layer.lobe[0] * peak, layer.lobe[1] * peak, layer.lobe[2] * peak, q);
        double b = Math.hypot(q[0], q[1]);
        if (b < 0.35 * peak) {
            q[0] = 0.8 * peak;
            q[1] = -0.6 * peak;
            b = peak;
        }
        double k = map.px(b) / b;
        return new double[]{q[0] * k, q[1] * k};
    }

    /** Un point du nuage (pm, repère de chimiste) à l'écran, en px relatifs au noyau (vraie échelle ou gouffre). */
    private double[] projectPm(double[] point) {
        double[] q = new double[3];
        camera.fromChemistry(point[0], point[1], point[2], q);
        double b = Math.hypot(q[0], q[1]);
        if (b < 1e-9) {
            return new double[]{0, 0};
        }
        double k = (logView ? gulfMap().px(b * 1000) : pmToPx(b)) / b;
        return new double[]{q[0] * k, q[1] * k};
    }

    /** Un angle où l'étiquette reste dans la scène, loin de l'en-tête et de la légende. */
    private double labelAngle(double[] c, double radius, double preferred) {
        double[] tries = {0, 0.5, -0.5, 1.0, -1.0, 1.6, -1.6, 2.3, -2.3, Math.PI};
        for (double shift : tries) {
            double angle = preferred + shift;
            double x = c[0] + radius * Math.cos(angle);
            double y = c[1] + radius * Math.sin(angle);
            boolean legend = y < 100 && x > stageRight() - 560;
            if (x > 16 && x < stageRight() - 170 && y > HEADER + 24 && y < canvas.getHeight() - FOOTER - 70
                    && !legend) {
                return angle;
            }
        }
        return Double.NaN;
    }

    private String layerLabel(OrbitalCloud.Layer layer) {
        OrbitalCloud.Shell shell = layer.shell;
        if (shell.exact()) {
            return shell.label() + " · spin " + (frame.getElectronSpin().startsWith("+") ? "↑" : "↓");
        }
        if (isExcitedLayer(layer)) {
            return "électron excité · " + shell.label().replace(" (excité)", "");
        }
        int capacity = 2 * (2 * shell.l() + 1);
        StringBuilder text = new StringBuilder(shell.label() + " · " + shell.electrons() + "/" + capacity);
        if (shell.electrons() < capacity && shell.occupation() != null) {
            text.append("  ");
            for (int count : shell.occupation()) {
                text.append(count == 2 ? " ↑↓" : count == 1 ? " ↑" : " ·");
            }
        }
        return text.toString();
    }

    /** Ce que montre le nuage d'une sous-couche, en clair. */
    private String cloudHint(int index) {
        OrbitalCloud.Layer layer = cloud.layer(index);
        OrbitalCloud.Shell shell = layer.shell;
        int nodes = shell.n() - shell.l() - 1;
        String radial = nodes == 0 ? "aucun nœud radial"
                : nodes == 1 ? "1 nœud radial (une coquille vide)" : nodes + " nœuds radiaux (coquilles vides)";
        if (shell.exact()) {
            return "électron " + shell.label() + " : |ψ|² exact (Z = " + frame.getAtom().getProtons()
                    + ") ; m non mesuré, donc toutes les orientations à la fois · " + radial;
        }
        if (isExcitedLayer(layer)) {
            return "électron excité (" + shell.label().replace(" (excité)", "")
                    + ") : il retombera en émettant un photon · " + radial;
        }
        int capacity = 2 * (2 * shell.l() + 1);
        StringBuilder text = new StringBuilder("nuage " + shell.label() + " · " + shell.electrons() + " e⁻ · ");
        if (shell.l() == 0) {
            text.append("orbitale s : sphérique");
        } else if (layer.isotropic) {
            text.append(shell.electrons() == capacity ? "pleine" : "à moitié pleine")
                    .append(" : Σ|Y|² constante, nuage sphérique (Unsöld)");
        } else {
            String[] names = OrbitalCloud.ORBITAL_NAMES[Math.min(3, shell.l())];
            for (int k = 0; k < shell.occupation().length && k < names.length; k++) {
                int count = shell.occupation()[k];
                text.append(k > 0 ? ", " : "").append(names[k]).append(count == 2 ? " ↑↓" : count == 1 ? " ↑" : " vide");
            }
            text.append(" (Hund)");
        }
        return text + " · " + radial;
    }

    /** Ce que montre le brouillard : une pastille « ⓘ », la légende complète au survol. */
    private void drawCloudLegend() {
        double x = stageRight() - 28;
        double y = 26;
        boolean over = Math.hypot(mouseX - x, mouseY - y) < 13;
        gc.setFill(Color.web("#121A2B", over ? 1 : 0.85));
        gc.fillOval(x - 12, y - 12, 24, 24);
        gc.setStroke(over ? ELECTRON : CARD_BORDER);
        gc.setLineWidth(1);
        gc.strokeOval(x - 12, y - 12, 24, 24);
        gc.setFont(Font.font("Georgia", FontWeight.BOLD, 13));
        gc.setFill(over ? TEXT : SOFT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        I18n.fillText(gc, "i", x, y + 1);
        if (over) {
            String[] lines = {
                    "Brouillard : |ψ|², la probabilité de présence des électrons, intégrée le long du regard.",
                    "Une couleur par sous-couche (s bleu, p vert, d orange, f rose), chacune à sa propre échelle.",
                    "Points brefs : positions possibles d'un électron si on le mesurait (tirées selon |ψ|²).",
                    "Glisser : tourner · clic droit ou Maj : déplacer · double-clic : centrer et approcher."};
            double w = 560;
            double bx = x - w + 14;
            double by = y + 18;
            gc.setFill(Color.web("#0E1524", 0.97));
            gc.fillRoundRect(bx, by, w, 18 + lines.length * 18, 10, 10);
            gc.setStroke(CARD_BORDER);
            gc.strokeRoundRect(bx, by, w, 18 + lines.length * 18, 10, 10);
            gc.setFont(Font.font(UI, 11.5));
            gc.setTextAlign(TextAlignment.LEFT);
            for (int i = 0; i < lines.length; i++) {
                gc.setFill(i == 3 ? MUTED : SOFT);
                I18n.fillText(gc, lines[i], bx + 12, by + 18 + i * 18);
            }
        }
        gc.setTextBaseline(VPos.BASELINE);
    }

    /**
     * Un électron libre : une particule ponctuelle (aucune structure, aucun rayon mesuré), dessinée comme un point très
     * brillant dans un halo qui s'éteint vers le vide, pas comme une bille.
     */
    private void drawElectron(double x, double y, Color color, double size, String spin) {
        double halo = size * 3.4;
        gc.setFill(new RadialGradient(0, 0, x, y, halo, false, CycleMethod.NO_CYCLE,
                new Stop(0, color.deriveColor(0, 1, 1.3, 0.75)), new Stop(0.18, color.deriveColor(0, 1, 1, 0.38)),
                new Stop(0.5, color.deriveColor(0, 1, 1, 0.1)), new Stop(1, color.deriveColor(0, 1, 1, 0))));
        gc.fillOval(x - halo, y - halo, 2 * halo, 2 * halo);
        double core = Math.max(1.2, size * 0.42);
        gc.setFill(Color.WHITE.deriveColor(0, 1, 1, color.getOpacity()));
        gc.fillOval(x - core, y - core, 2 * core, 2 * core);
        if (!spin.isEmpty()) {
            // Le spin : une petite flèche, vers le haut (+½) ou le bas (−½).
            boolean up = spin.equals("↑");
            double top = y - size - 14;
            double bottom = y - size - 2;
            gc.setLineCap(StrokeLineCap.ROUND);
            gc.setStroke(Color.web("#050812", 0.8));
            gc.setLineWidth(3.6);
            gc.strokeLine(x, top, x, bottom);
            Color arrowColor = up ? Color.web("#FDE68A") : Color.web("#F9A8D4");
            gc.setStroke(arrowColor);
            gc.setLineWidth(1.8);
            gc.strokeLine(x, top, x, bottom);
            double tip = up ? top : bottom;
            double back = up ? top + 4.5 : bottom - 4.5;
            gc.strokeLine(x, tip, x - 3.5, back);
            gc.strokeLine(x, tip, x + 3.5, back);
        }
    }

    private void drawNucleusPoint(double[] c) {
        if (frame.getAtom().getMassNumber() == 0) {
            return;
        }
        if (nucleusFlash > 0) {
            double r = 10 + 40 * (1 - nucleusFlash);
            gc.setFill(new RadialGradient(0, 0, c[0], c[1], r, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.web("#FDE68A", 0.7 * nucleusFlash)), new Stop(1, Color.web("#FDE68A", 0))));
            gc.fillOval(c[0] - r, c[1] - r, r * 2, r * 2);
        }
        gc.setFill(unstable() ? Color.web("#FB7185") : Color.web("#FCA5A5"));
        gc.fillOval(c[0] - 2.5, c[1] - 2.5, 5, 5);
        hits.add(new Hit(c[0], c[1], 6, "", "noyau " + frame.getNucleus().getNuclide()
                + " : 100 000 fois plus petit que l'atome (voir la loupe)"));
    }

    private boolean unstable() {
        return frame != null && frame.getNucleus().getUnstable();
    }

    // --- Noyau : une goutte de liquide quantique, en 3D ------------------------
    /** Femtomètres → pixels, à l'échelle donnée. */
    private static OrbitalCloud.Radial fmMap(double metresPerPixel) {
        return OrbitalCloud.linear(Visuals.FEMTOMETRE / metresPerPixel);
    }

    /**
     * Le noyau : nucléons placés par le moteur, déformés par les vibrations de
     * surface de la goutte, tournés en 3D, triés en profondeur avec les tubes
     * des pions qu'ils échangent ; la surface de la goutte autour.
     */
    private void drawNucleus(double cx, double cy, OrbitalCloud.Radial map, boolean quarks, boolean inLoupe) {
        int count = Math.min(frame.getNucleonsCount(), drop.count());
        if (count == 0) {
            if (!inLoupe) {
                centredText(cx, cy, "noyau vide", 13, MUTED);
            }
            return;
        }
        double radiusFm = nuclearRadiusFm();
        double radiusPx = map.px(radiusFm);
        double nucleonPx = map.px(NUCLEON_RADIUS_FM);
        if (nucleusFlash > 0) {
            double glow = radiusPx * (1.3 + 1.6 * (1 - nucleusFlash));
            gc.setFill(new RadialGradient(0, 0, cx, cy, glow, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.web("#FDE68A", 0.5 * nucleusFlash)), new Stop(1, Color.web("#FDE68A", 0))));
            gc.fillOval(cx - glow, cy - glow, glow * 2, glow * 2);
        }
        double focal = 5 * radiusFm;
        boolean shaking = unstable();
        double[] p = new double[3];
        double[] q = new double[3];
        double[][] projected = new double[count][];
        for (int i = 0; i < count; i++) {
            drop.position(i, p);
            if (shaking) {
                p[0] += 0.08 * Math.sin(clock * 7 + i * 1.7);
                p[1] += 0.08 * Math.cos(clock * 6.1 + i * 2.3);
            }
            camera.rotate(p[0], p[1], p[2], q);
            double f = Camera3D.perspective(q[2], focal);
            projected[i] = place(cx, cy, q[0] * f, q[1] * f, q[2], NUCLEON_RADIUS_FM * f, map);
        }
        // Du plus loin au plus proche : les nucléons, et les tubes des pions entre eux.
        List<NuclearDrop.Exchange> exchanges = drop.exchanges();
        int items = count + exchanges.size();
        double[] depth = new double[items];
        Integer[] order = new Integer[items];
        for (int i = 0; i < items; i++) {
            order[i] = i;
            if (i < count) {
                depth[i] = projected[i][2];
            } else {
                NuclearDrop.Exchange exchange = exchanges.get(i - count);
                depth[i] = exchange.from < count && exchange.to < count
                        ? (projected[exchange.from][2] + projected[exchange.to][2]) / 2 : -1e9;
            }
        }
        Arrays.sort(order, Comparator.comparingDouble(index -> depth[index]));

        int focused = -1;
        if (quarks) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                double score = Math.hypot(projected[i][0] - stageCenterX(), projected[i][1] - stageCenterY())
                        - projected[i][2] * 4;
                if (score < best) {
                    best = score;
                    focused = i;
                }
            }
        }
        if (!quarks) {
            drawMembrane(cx, cy, map, focal, false);
        }
        double minimum = inLoupe ? 1.2 : 1.6;
        if (!quarks) {
            // Une lueur commune sous les nucléons : leurs bords se fondent, le noyau se lit comme une goutte.
            for (int i = 0; i < count; i++) {
                double[] s = projected[i];
                double r = Math.max(minimum, s[3]) * 1.7;
                Color base = Visuals.NEUTRON.interpolate(Visuals.PROTON, drop.tint(i));
                gc.setFill(new RadialGradient(0, 0, s[0], s[1], r, false, CycleMethod.NO_CYCLE,
                        new Stop(0, base.deriveColor(0, 1, 1, 0.16)), new Stop(1, base.deriveColor(0, 1, 1, 0))));
                gc.fillOval(s[0] - r, s[1] - r, 2 * r, 2 * r);
            }
        }
        for (int index : order) {
            if (index >= count) {
                continue;
            }
            double[] s = projected[index];
            double radius = Math.max(minimum, s[3]);
            if (!inLoupe && (s[0] < -radius * 2 || s[0] > canvas.getWidth() + radius * 2
                    || s[1] < -radius * 2 || s[1] > canvas.getHeight() + radius * 2)) {
                continue;
            }
            double tint = drop.tint(index);
            boolean proton = drop.isProton(index);
            Color base = Visuals.NEUTRON.interpolate(Visuals.PROTON, tint);
            double light = clamp(0.55 + 0.45 * (s[2] / Math.max(1e-9, radiusFm) + 1) / 2, 0.3, 1.1);
            if (radius > 6 && !quarks) {
                drawNucleonBubble(s[0], s[1], radius, base, light, index);
            } else {
                drawNucleonSphere(s[0], s[1], radius, base, light, quarks && index != focused ? 0.16 : 1.0);
            }
            if (!inLoupe && !quarks && radius > 6) {
                hits.add(new Hit(s[0], s[1], radius, proton ? "proton" : "neutron", proton
                        ? "proton (u u d), charge +1, spin ½ · en émettant un π⁺, il devient neutron"
                        : "neutron (u d d), charge 0, spin ½ · en émettant un π⁻, il devient proton"));
            }
        }
        // Les pions par-dessus, en transparence : les nucléons (0,84 fm) se touchent et les cacheraient.
        // Ceux de l'arrière sont plus pâles.
        for (int index : order) {
            if (index < count) {
                continue;
            }
            NuclearDrop.Exchange exchange = exchanges.get(index - count);
            if (exchange.from < count && exchange.to < count) {
                double front = clamp((depth[index] / Math.max(1e-9, radiusFm) + 1) / 2, 0, 1);
                drawPionTube(exchange, projected[exchange.from], projected[exchange.to],
                        (quarks ? 0.25 : 1) * (0.35 + 0.65 * front), !inLoupe && front > 0.4);
            }
        }
        if (!quarks) {
            drawMembrane(cx, cy, map, focal, true);
        }
        if (focused >= 0) {
            double[] s = projected[focused];
            focusedProton = drop.isProton(focused);
            drawNucleonSphere(s[0], s[1], nucleonPx, focusedProton ? Visuals.PROTON : Visuals.NEUTRON, 1.0, 0.25);
            drawQuarks(s[0], s[1], nucleonPx, focusedProton, focused);
        }
        if (!inLoupe && !quarks) {
            smallLabel(cx + radiusPx * 0.72 + 8, cy - radiusPx * 0.72 - 6,
                    "R = 1,2·A^⅓ = " + Visuals.fixed(radiusFm, 2) + " fm", SOFT);
            if (nucleusFlash > 0 && !nucleusFlashLabel.isEmpty()) {
                gc.setFont(Font.font(UI, FontWeight.BOLD, 15));
                gc.setFill(EXCITED.deriveColor(0, 1, 1, Math.min(1, nucleusFlash * 2)));
                gc.setTextAlign(TextAlignment.CENTER);
                I18n.fillText(gc, nucleusFlashLabel, cx, cy - radiusPx - 22);
            }
        }
    }

    /**
     * Un point (fm, déjà tourné et mis en perspective) à l'écran, selon la
     * carte radiale : {x, y, profondeur, rayon d'un nucléon en pixels}.
     */
    private static double[] place(double cx, double cy, double x, double y, double depth, double radiusFm,
                                  OrbitalCloud.Radial map) {
        double b = Math.hypot(x, y);
        double k = b < 1e-6 ? map.px(1e-3) / 1e-3 : map.px(b) / b;
        double radius = b < radiusFm
                ? (map.px(b + radiusFm) - map.px(0)) / (1 + b / radiusFm)
                : (map.px(b + radiusFm) - map.px(b - radiusFm)) / 2;
        return new double[]{cx + x * k, cy + y * k, depth, radius};
    }

    /**
     * La surface de la goutte : la silhouette projetée de R(θ, φ) déformée
     * (vibrations de surface), et sa peau diffuse (Woods-Saxon, a = 0,55 fm :
     * la densité tombe de 90 % à 10 % sur 2,4 fm).
     */
    private void drawMembrane(double cx, double cy, OrbitalCloud.Radial map, double focal, boolean front) {
        int bins = 90;
        double[] reach = new double[bins];
        double[] u = new double[3];
        double[] q = new double[3];
        int samples = 640;
        for (int s = 0; s < samples; s++) {
            OrbitalCloud.fibonacci(s, samples, u);
            double r = drop.surface(u[0], u[1], u[2]);
            camera.rotate(u[0] * r, u[1] * r, u[2] * r, q);
            double f = Camera3D.perspective(q[2], focal);
            double x = q[0] * f;
            double y = q[1] * f;
            int bin = (int) ((Math.atan2(y, x) + Math.PI) / (2 * Math.PI) * bins) % bins;
            reach[bin] = Math.max(reach[bin], Math.hypot(x, y));
        }
        for (int i = 0; i < bins; i++) {
            if (reach[i] <= 0) {
                reach[i] = Math.max(reach[(i + bins - 1) % bins], reach[(i + 1) % bins]);
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            double[] next = new double[bins];
            for (int i = 0; i < bins; i++) {
                next[i] = (reach[(i + bins - 1) % bins] + 2 * reach[i] + reach[(i + 1) % bins]) / 4;
            }
            reach = next;
        }
        double skin = 1.2;
        double mean = 0;
        for (double r : reach) {
            mean += r / bins;
        }
        double[] xs = new double[bins];
        double[] ys = new double[bins];
        double outer = 0;
        for (int i = 0; i < bins; i++) {
            double angle = (i + 0.5) / bins * 2 * Math.PI - Math.PI;
            double px = map.px(front ? reach[i] : reach[i] + skin);
            outer = Math.max(outer, px);
            xs[i] = cx + px * Math.cos(angle);
            ys[i] = cy + px * Math.sin(angle);
        }
        if (outer < 1) {
            return;
        }
        if (!front) {
            double inner = clamp(map.px(Math.max(0, mean - skin)) / outer, 0, 0.98);
            double half = clamp(map.px(mean) / outer, inner, 0.99);
            gc.setFill(new RadialGradient(0, 0, cx, cy, outer, false, CycleMethod.NO_CYCLE,
                    new Stop(0, DROP.deriveColor(0, 1, 1, 0.05)), new Stop(inner, DROP.deriveColor(0, 1, 1, 0.09)),
                    new Stop(half, DROP.deriveColor(0, 1, 1, 0.2)), new Stop(1, DROP.deriveColor(0, 1, 1, 0))));
            gc.fillPolygon(xs, ys, bins);
        } else {
            gc.setStroke(DROP.deriveColor(0, 1, 1.2, 0.34));
            gc.setLineWidth(1.2);
            gc.strokePolygon(xs, ys, bins);
            gc.setStroke(Color.web("#FFFFFF", 0.1));
            gc.setLineWidth(Math.max(1, outer * 0.035));
            gc.strokeArc(cx - outer * 0.84, cy - outer * 0.84, outer * 1.68, outer * 1.68, 105, 55, ArcType.OPEN);
        }
    }

    /** Un pion virtuel en vol : un tube de flux entre deux nucléons, et la perle du pion qui le parcourt. */
    private void drawPionTube(NuclearDrop.Exchange exchange, double[] a, double[] b, double alpha, boolean label) {
        double t = exchange.progress();
        double envelope = clamp(Math.min(t, 1 - t) / 0.18, 0, 1);
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double length = Math.hypot(dx, dy);
        if (envelope <= 0 || length < 1) {
            return;
        }
        double ux = dx / length;
        double uy = dy / length;
        Color colour = exchange.charge > 0 ? PION_PLUS : exchange.charge < 0 ? PION_MINUS : PION_ZERO;
        double width = Math.min(a[3], b[3]) * 0.4 * envelope;
        int segments = 14;
        double[] xs = new double[2 * segments + 2];
        double[] ys = new double[2 * segments + 2];
        for (int s = 0; s <= segments; s++) {
            double f = (double) s / segments;
            double w = width * (1 - 0.5 * Math.sin(Math.PI * f));     // tendu comme une corde : plus fin au milieu
            double x = a[0] + dx * f;
            double y = a[1] + dy * f;
            xs[s] = x - uy * w;
            ys[s] = y + ux * w;
            xs[2 * segments + 1 - s] = x + uy * w;
            ys[2 * segments + 1 - s] = y - ux * w;
        }
        double strength = alpha * envelope;
        gc.setFill(new LinearGradient(a[0], a[1], b[0], b[1], false, CycleMethod.NO_CYCLE,
                new Stop(0, colour.deriveColor(0, 1, 1, 0.75 * strength)),
                new Stop(0.5, colour.deriveColor(0, 1, 1, 0.45 * strength)),
                new Stop(1, colour.deriveColor(0, 1, 1, 0.75 * strength))));
        gc.fillPolygon(xs, ys, xs.length);
        double along = Visuals.ease(t);
        double px = a[0] + dx * along;
        double py = a[1] + dy * along;
        double r = Math.max(1.4, Math.min(a[3], b[3]) * 0.3);
        gc.setFill(new RadialGradient(0, 0, px, py, r * 2.6, false, CycleMethod.NO_CYCLE,
                new Stop(0, colour.deriveColor(0, 1, 1, 0.6 * strength)), new Stop(1, colour.deriveColor(0, 1, 1, 0))));
        gc.fillOval(px - r * 2.6, py - r * 2.6, r * 5.2, r * 5.2);
        gc.setFill(colour.deriveColor(0, 0.7, 1.3, strength));
        gc.fillOval(px - r, py - r, r * 2, r * 2);
        if (label) {
            if (r > 4) {
                smallLabel(px + r + 4, py - r - 2, exchange.name() + "  " + exchange.quarks(), colour);
            }
            hits.add(new Hit(px, py, Math.max(8, r * 1.6), "", exchange.name() + " (" + exchange.quarks()
                    + ") virtuel : portée ħ/m_π·c = 1,41 fm, durée ≈ 4,7·10⁻²⁴ s"
                    + (exchange.charge != 0 ? " · il change l'identité des deux nucléons (p ↔ n)" : "")));
        }
    }

    /**
     * Un nucléon vu de près : une bulle (corps translucide, liseré lumineux, reflet), pas une bille. Dedans, ses trois
     * quarks de valence tournent et échangent leur couleur (rouge, vert, bleu : la somme reste blanche). Sa teinte glisse
     * du rouge (proton) au gris-bleu (neutron) quand un pion change son identité.
     */
    private void drawNucleonBubble(double x, double y, double radius, Color base, double light, int seed) {
        Color body = base.deriveColor(0, 1, light, 1);
        gc.setFill(new RadialGradient(0, 0, x, y, radius, false, CycleMethod.NO_CYCLE,
                new Stop(0, body.deriveColor(0, 0.8, 0.7, 0.35)), new Stop(0.72, body.deriveColor(0, 1, 0.9, 0.55)),
                new Stop(0.93, body.deriveColor(0, 0.7, 1.35, 0.85)), new Stop(1, body.deriveColor(0, 1, 1, 0))));
        gc.fillOval(x - radius, y - radius, 2 * radius, 2 * radius);
        int shift = (int) Math.floor(clock * 0.5 + seed * 0.37) % 3;
        double spin = clock * 0.9 + seed * 1.3;
        double quark = Math.max(1.2, radius * 0.13);
        for (int q = 0; q < 3; q++) {
            double angle = spin + q * 2 * Math.PI / 3;
            double wobble = 0.42 + 0.08 * Math.sin(clock * 1.7 + seed + q);
            double qx = x + radius * wobble * Math.cos(angle);
            double qy = y + radius * wobble * 0.8 * Math.sin(angle);
            Color c = COLOR_CHARGE[(q + shift) % 3];
            gc.setFill(new RadialGradient(0, 0, qx, qy, quark * 2.4, false, CycleMethod.NO_CYCLE,
                    new Stop(0, c.deriveColor(0, 0.6, 1.4, 0.9)), new Stop(1, c.deriveColor(0, 1, 1, 0))));
            gc.fillOval(qx - quark * 2.4, qy - quark * 2.4, quark * 4.8, quark * 4.8);
        }
        gc.setFill(Color.color(1, 1, 1, 0.35));
        gc.fillOval(x - radius * 0.55, y - radius * 0.62, radius * 0.42, radius * 0.24);
    }

    private void drawNucleonSphere(double x, double y, double radius, Color base, double light, double alpha) {
        Color bright = base.deriveColor(0, 0.6, 1.35 * light, alpha);
        Color dark = base.deriveColor(0, 1.1, 0.42 * light, alpha);
        gc.setFill(new RadialGradient(0, 0, x - radius * 0.35, y - radius * 0.4, radius * 1.35, false,
                CycleMethod.NO_CYCLE, new Stop(0, bright), new Stop(1, dark)));
        gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);
    }

    /** Quarks de valence, leur couleur et leur spin : u u d (proton), u d d (neutron). */
    private void drawQuarks(double x, double y, double nucleonPx, boolean proton, int seed) {
        String[] flavours = proton ? new String[]{"u", "u", "d"} : new String[]{"u", "d", "d"};
        String[] spins = {"↑", "↑", "↓"};
        double orbit = nucleonPx * 0.45;
        double quarkRadius = Math.max(3, nucleonPx * 0.15);
        double[][] positions = new double[3][];
        for (int q = 0; q < 3; q++) {
            double angle = clock * 0.35 + seed * 0.7 + q * 2 * Math.PI / 3;
            positions[q] = new double[]{x + orbit * Math.cos(angle), y + orbit * Math.sin(angle)};
        }
        gc.setLineWidth(Math.max(1, nucleonPx * 0.025));
        gc.setStroke(Color.web("#FDE68A", 0.5));
        for (int q = 0; q < 3; q++) {
            drawSpring(positions[q], positions[(q + 1) % 3], nucleonPx * 0.04);
        }
        // La couleur passe d'un quark à l'autre par les gluons : lentement, pour qu'on la suive.
        int shift = (int) Math.floor(clock * 0.4 + seed) % 3;
        for (int q = 0; q < 3; q++) {
            Color colour = COLOR_CHARGE[(q + shift) % 3];
            double[] p = positions[q];
            gc.setFill(new RadialGradient(0, 0, p[0], p[1], quarkRadius * 2, false, CycleMethod.NO_CYCLE,
                    new Stop(0, colour.deriveColor(0, 1, 1, 0.45)), new Stop(1, colour.deriveColor(0, 1, 1, 0))));
            gc.fillOval(p[0] - quarkRadius * 2, p[1] - quarkRadius * 2, quarkRadius * 4, quarkRadius * 4);
            gc.setFill(colour);
            gc.fillOval(p[0] - quarkRadius, p[1] - quarkRadius, quarkRadius * 2, quarkRadius * 2);
            hits.add(new Hit(p[0], p[1], quarkRadius * 1.4, flavours[q].equals("u") ? "up" : "down",
                    "quark " + flavours[q] + " · couleur " + COLOR_NAMES[(q + shift) % 3] + " · spin " + spins[q]));
            if (nucleonPx > 55) {
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font(UI, FontWeight.BOLD, Math.min(18, quarkRadius * 1.1)));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setTextBaseline(VPos.CENTER);
                I18n.fillText(gc, flavours[q] + spins[q], p[0], p[1]);
                gc.setFont(Font.font(MONO, Math.min(12, quarkRadius * 0.75)));
                gc.setFill(TEXT);
                I18n.fillText(gc, flavours[q].equals("u") ? "+2/3" : "−1/3", p[0], p[1] + quarkRadius * 2.1);
                gc.setTextBaseline(VPos.BASELINE);
            }
        }
    }

    private void drawSpring(double[] a, double[] b, double amplitude) {
        double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
        if (length < 2) {
            return;
        }
        double ux = (b[0] - a[0]) / length;
        double uy = (b[1] - a[1]) / length;
        double previousX = a[0];
        double previousY = a[1];
        for (int s = 1; s <= 24; s++) {
            double t = s / 24.0;
            double wave = Math.sin(t * Math.PI * 8 + clock * 4) * amplitude * Math.sin(Math.PI * t);
            double px = a[0] + (b[0] - a[0]) * t - uy * wave;
            double py = a[1] + (b[1] - a[1]) * t + ux * wave;
            gc.strokeLine(previousX, previousY, px, py);
            previousX = px;
            previousY = py;
        }
    }

    // --- Loupe sur le noyau ------------------------------------------------
    private boolean loupeVisible() {
        return frame != null && frame.getAtom().getMassNumber() > 0 && !logView && scale() == Scale.ATOME;
    }

    private double[] loupeCenter() {
        return new double[]{LOUPE_RADIUS + 26, canvas.getHeight() - FOOTER - LOUPE_RADIUS - 52};
    }

    private boolean loupeHit(double x, double y) {
        double[] c = loupeCenter();
        return loupeVisible() && Math.hypot(x - c[0], y - c[1]) <= LOUPE_RADIUS;
    }

    /** Le noyau, grossi : deux traits le relient au point qu'il agrandit. */
    private void drawLoupe(double[] nucleus) {
        double[] c = loupeCenter();
        double dx = nucleus[0] - c[0];
        double dy = nucleus[1] - c[1];
        double distance = Math.max(1, Math.hypot(dx, dy));
        double ux = dx / distance;
        double uy = dy / distance;
        gc.setStroke(Color.web("#94A3B8", 0.22));
        gc.setLineWidth(1);
        gc.strokeLine(c[0] - uy * LOUPE_RADIUS, c[1] + ux * LOUPE_RADIUS, nucleus[0], nucleus[1]);
        gc.strokeLine(c[0] + uy * LOUPE_RADIUS, c[1] - ux * LOUPE_RADIUS, nucleus[0], nucleus[1]);

        gc.setFill(Color.web("#070C1A", 0.95));
        gc.fillOval(c[0] - LOUPE_RADIUS, c[1] - LOUPE_RADIUS, LOUPE_RADIUS * 2, LOUPE_RADIUS * 2);
        double fit = nuclearRadiusFm() * Visuals.FEMTOMETRE / (LOUPE_RADIUS * 0.6);
        drawNucleus(c[0], c[1], fmMap(fit), false, true);
        boolean hovered = loupeHit(mouseX, mouseY);
        gc.setStroke(unstable() ? Color.web("#FB7185", 0.85) : Color.web("#3B4A63"));
        gc.setLineWidth(hovered ? 2.4 : 1.5);
        gc.strokeOval(c[0] - LOUPE_RADIUS, c[1] - LOUPE_RADIUS, LOUPE_RADIUS * 2, LOUPE_RADIUS * 2);

        double magnification = Math.max(1, mpp() / fit);
        double left = c[0] - LOUPE_RADIUS;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
        gc.setFill(TEXT);
        I18n.fillText(gc, "noyau " + frame.getNucleus().getNuclide(), left, c[1] - LOUPE_RADIUS - 10);
        gc.setFont(Font.font(UI, 11));
        gc.setFill(SOFT);
        I18n.fillText(gc, "grossi " + String.format(Locale.ROOT, "%,d", Math.round(magnification)).replace(',', ' ')
                + " fois", left, c[1] + LOUPE_RADIUS + 16);
        gc.setFill(MUTED);
        I18n.fillText(gc, "clic : entrer dans le noyau", left, c[1] + LOUPE_RADIUS + 30);
        if (nucleusFlash > 0 && !nucleusFlashLabel.isEmpty()) {
            gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
            gc.setFill(EXCITED.deriveColor(0, 1, 1, Math.min(1, nucleusFlash * 2)));
            I18n.fillText(gc, nucleusFlashLabel, left, c[1] - LOUPE_RADIUS - 26);
        }
    }

    // --- Objets animés ------------------------------------------------------
    private void drawAnims(double[] c) {
        for (Anim anim : anims) {
            if (anim.delay > 0) {
                continue;
            }
            if (anim.homing && anim.age < anim.duration) {
                double[] goal = anim.impact != null && (logView || scale() == Scale.ATOME) ? projectPm(anim.impact) : actor;
                if (goal != null) {
                    double follow = Math.min(1, 6 * delta);
                    anim.toX += (goal[0] - anim.toX) * follow;
                    anim.toY += (goal[1] - anim.toY) * follow;
                    impactOffset = new double[]{anim.toX, anim.toY};
                }
            }
            double t = clamp(anim.age / anim.duration, 0, 1);
            double travel;
            if (anim.bounceAt > 0) {
                travel = t < anim.bounceAt ? Visuals.ease(t / anim.bounceAt)
                        : 1 - Visuals.ease((t - anim.bounceAt) / (1 - anim.bounceAt));
            } else if (anim.kind.equals("electron")) {
                travel = Visuals.ease(t);
            } else {
                travel = t;
            }
            double x = c[0] + anim.fromX + (anim.toX - anim.fromX) * travel;
            double y = c[1] + anim.fromY + (anim.toY - anim.fromY) * travel;
            anim.screenX = x;
            anim.screenY = y;
            double fade = t > 0.85 && !anim.homing ? (1 - t) / 0.15 : Math.min(1, anim.age / 0.3);
            switch (anim.kind) {
                case "photon" -> drawPhoton(anim, x, y, fade);
                case "flash" -> drawFlash(anim, c, t);
                case "shock" -> {
                    // Front sphérique qui s'éloigne, avec une traînée (rémanence) derrière lui.
                    double radius = 12 + 0.55 * stageSpan() * Visuals.ease(t);
                    for (int trail = 0; trail < 4; trail++) {
                        double r = radius * (1 - 0.06 * trail);
                        double alpha = (1 - t) * (1 - t) * anim.size * (0.7 - 0.15 * trail);
                        gc.setStroke(anim.color.deriveColor(0, 1, 1.2, Math.max(0, alpha)));
                        gc.setLineWidth(trail == 0 ? 3 : 1.5);
                        gc.strokeOval(c[0] - r, c[1] - r, 2 * r, 2 * r);
                    }
                }
                case "photoelectron" -> drawPhotoelectron(anim, c, x, y, t);
                case "ring" -> {
                    double radius = 6 + 50 * t;
                    gc.setStroke(anim.color.deriveColor(0, 1, 1, 1 - t));
                    gc.setLineWidth(2);
                    gc.strokeOval(c[0] + anim.fromX - radius, c[1] + anim.fromY - radius, radius * 2, radius * 2);
                }
                default -> {
                    if (anim.dashed) {
                        gc.setStroke(anim.color.deriveColor(0, 1, 1, fade));
                        gc.setLineDashes(3, 6);
                        gc.setLineWidth(1.2);
                        gc.strokeLine(c[0] + anim.fromX, c[1] + anim.fromY, x, y);
                        gc.setLineDashes((double[]) null);
                    }
                    drawElectron(x, y, anim.color.deriveColor(0, 1, 1, fade), anim.size,
                            anim.kind.equals("electron") ? anim.spin : "");
                    tag(x, y, anim.label, anim.color, fade);
                }
            }
            if (!anim.key.isEmpty()) {
                hits.add(new Hit(x, y, 12, anim.key, anim.label));
            }
        }
    }

    /** Le photon : un paquet d'onde 3D construit à partir de λ (voir PhotonPacket). */
    private void drawPhoton(Anim anim, double x, double y, double fade) {
        double dx = anim.toX - anim.fromX;
        double dy = anim.toY - anim.fromY;
        double length = Math.max(1, Math.hypot(dx, dy));
        double nm = anim.nm > 0 ? anim.nm : 0.00124;      // γ de désintégration : ~1 MeV, ordre de grandeur
        double metres = logView ? Math.max(60, outerRadiusPm()) * Visuals.PICOMETRE / (0.21 * stageSpan()) : mpp();
        PhotonPacket.Look look = PhotonPacket.look(nm, metres, frame.getIonizationNowEv());
        // Le paquet traverse le brouillard : il l'éclaire là où il passe (effet de rendu, pas une diffusion calculée).
        double fog = (logView || scale() == Scale.ATOME) ? cloud.lightAt(x, y) : 0;
        if (fog > 0.02) {
            double r = look.amplitude() * 3.4;
            gc.setFill(new RadialGradient(0, 0, x, y, r, false, CycleMethod.NO_CYCLE,
                    new Stop(0, anim.color.deriveColor(0, 0.8, 1.3, 0.55 * fog * fade)), new Stop(1, anim.color.deriveColor(0, 1, 1, 0))));
            gc.fillOval(x - r, y - r, 2 * r, 2 * r);
        }
        PhotonPacket.drawGlow(gc, x, y, dx / length, dy / length, look, fade, clock);
        photonViews.add(new Photon3DLayer.View(anim, x, y, dx / length, dy / length, look, fade));
        String text = anim.label;
        if (anim.measured) {
            text += " · " + Visuals.format(look.energyEv()) + " eV · " + PhotonPacket.scaleNote(look);
            if (look.ionising() > 0) {
                text += " · ionisant";
            }
        }
        tag(x, y + look.amplitude() + 34, text, anim.color, fade);   // sous le paquet : les rubans restent visibles
    }

    /** L'éclair : une onde lumineuse qui s'étend depuis la région la plus probable de l'orbitale. */
    private void drawFlash(Anim anim, double[] c, double t) {
        double x = c[0] + anim.fromX;
        double y = c[1] + anim.fromY;
        double strength = anim.size;
        double radius = 10 + 70 * Visuals.ease(Math.min(1, t * 1.4));
        double fall = (1 - t) * (1 - t);
        gc.setFill(new RadialGradient(0, 0, x, y, radius, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE.deriveColor(0, 1, 1, 0.9 * fall * strength)),
                new Stop(0.25, anim.color.deriveColor(0, 1, 1.2, 0.7 * fall * strength)),
                new Stop(0.7, anim.color.deriveColor(0, 1, 1, 0.18 * fall * strength)),
                new Stop(1, anim.color.deriveColor(0, 1, 1, 0))));
        gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        double front = 14 + 110 * Visuals.ease(t);
        gc.setStroke(anim.color.deriveColor(0, 0.7, 1.3, 0.35 * (1 - t) * strength));
        gc.setLineWidth(1.5 + 3 * (1 - t));
        gc.strokeOval(x - front, y - front, front * 2, front * 2);
    }

    /**
     * L'électron libéré : un paquet gaussien qui s'étale en avançant (un paquet
     * d'onde libre se disperse), ses crêtes de de Broglie perpendiculaires au
     * mouvement, et la traînée de son départ.
     */
    private void drawPhotoelectron(Anim anim, double[] c, double x, double y, double t) {
        double ox = c[0] + anim.fromX;
        double oy = c[1] + anim.fromY;
        double fade = Math.min(1, anim.age / 0.15) * (t > 0.85 ? (1 - t) / 0.15 : 1);
        gc.setStroke(new LinearGradient(ox, oy, x, y, false, CycleMethod.NO_CYCLE,
                new Stop(0, ELECTRON.deriveColor(0, 1, 1, 0)), new Stop(1, ELECTRON.deriveColor(0, 1, 1, 0.35 * fade))));
        gc.setLineWidth(2);
        gc.strokeLine(ox, oy, x, y);
        double sigma = 9 * Math.sqrt(1 + Math.pow(anim.age / 1.4, 2));
        gc.setFill(new RadialGradient(0, 0, x, y, sigma * 2.6, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE.deriveColor(0, 1, 1, 0.85 * fade)),
                new Stop(0.3, ELECTRON.deriveColor(0, 1, 1, 0.55 * fade)),
                new Stop(1, ELECTRON.deriveColor(0, 1, 1, 0))));
        gc.fillOval(x - sigma * 2.6, y - sigma * 2.6, sigma * 5.2, sigma * 5.2);
        // Crêtes de de Broglie : à l'échelle si elles tiennent à l'écran, sinon resserrées (log).
        double lambdaNm = 1.22643 / Math.sqrt(anim.kinetic);
        double lambdaPx = lambdaNm * 1e-9 / mpp();
        double spacing = lambdaPx >= 4 && lambdaPx <= 60 ? lambdaPx : clamp(10 + 8 * Math.log10(lambdaNm / 0.01), 5, 40);
        double nx = -anim.dirY;
        double ny = anim.dirX;
        gc.setLineWidth(1.3);
        for (int k = -2; k <= 2; k++) {
            double along = k * spacing;
            double weight = Math.exp(-0.5 * along * along / (sigma * sigma));
            if (weight < 0.08) {
                continue;
            }
            double cx = x + anim.dirX * along;
            double cy = y + anim.dirY * along;
            double half = sigma * 1.6 * Math.sqrt(weight);
            gc.setStroke(Color.WHITE.deriveColor(0, 1, 1, 0.55 * weight * fade));
            gc.strokeLine(cx - nx * half, cy - ny * half, cx + nx * half, cy + ny * half);
        }
        tag(x, y - sigma, anim.label, ELECTRON, fade);
    }

    /** Étiquette d'une particule en mouvement. */
    private void tag(double x, double y, String text, Color color, double alpha) {
        if (text == null || text.isEmpty() || alpha <= 0.05) {
            return;
        }
        gc.setFont(Font.font(UI, FontWeight.SEMI_BOLD, 12));
        double width = text.length() * 6.6 + 16;
        gc.setFill(Color.web("#070C1A", 0.82 * alpha));
        gc.fillRoundRect(x + 10, y - 24, width, 20, 10, 10);
        gc.setStroke(color.deriveColor(0, 1, 1, 0.6 * alpha));
        gc.setLineWidth(1);
        gc.strokeRoundRect(x + 10, y - 24, width, 20, 10, 10);
        gc.setFill(color.deriveColor(0, 0.6, 1.35, alpha));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        I18n.fillText(gc, text, x + 18, y - 14);
        gc.setTextBaseline(VPos.BASELINE);
    }

    private void smallLabel(double x, double y, String text, Color color) {
        gc.setFont(Font.font(UI, 11));
        gc.setFill(color);
        gc.setTextAlign(TextAlignment.LEFT);
        I18n.fillText(gc, text, x, y);
    }

    private static String wavelengthText(double nm) {
        String region = Visuals.region(nm);
        String value = nm >= 1000 ? Visuals.format(nm / 1000) + " µm" : Visuals.format(nm) + " nm";
        return region.equals("visible") ? value : value + " (" + region + ", invisible)";
    }

    // =================================================================
    // Habillage
    // =================================================================
    private void drawHeader() {
        String symbol = frame.getElement().getSymbol();
        String name = frame.getElement().getName();
        String nuclide = frame.getNucleus().getNuclide();
        double x = 22;
        if (!symbol.isEmpty()) {
            Color accent = frame.getElement().getColorHex().isEmpty() ? SOFT : Color.web(frame.getElement().getColorHex());
            gc.setFill(accent.deriveColor(0, 1, 1, 0.14));
            gc.fillRoundRect(x, 16, 50, 50, 12, 12);
            gc.setStroke(accent.deriveColor(0, 1, 1, 0.6));
            gc.setLineWidth(1.2);
            gc.strokeRoundRect(x, 16, 50, 50, 12, 12);
            gc.setFont(Font.font(UI, FontWeight.BOLD, 24));
            gc.setFill(accent);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            I18n.fillText(gc, symbol, x + 25, 41);
            x += 64;
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 22));
        gc.setFill(TEXT);
        I18n.fillText(gc, name.isEmpty() ? (nuclide.isEmpty() ? "Chambre vide" : nuclide) : name + "  " + nuclide, x, 14);
        int charge = frame.getAtom().getNetCharge();
        String chargeText = charge == 0 ? "neutre" : charge > 0 ? "ion +" + charge : "ion " + charge;
        gc.setFont(Font.font(UI, 13));
        gc.setFill(SOFT);
        String spin = frame.getNucleus().getSpinParity();
        I18n.fillText(gc, frame.getAtom().getProtons() + " p⁺   " + frame.getAtom().getNeutrons() + " n   "
                + frame.getAtom().getElectrons() + " e⁻   ·   " + chargeText
                + (unstable() ? "   ·   radioactif" : "")
                + (spin.isEmpty() ? "" : "   ·   spin du noyau " + spin), x, 44);

        gc.setTextBaseline(VPos.BASELINE);
    }

    private void drawFooter(double width, double height) {
        double top = height - FOOTER;
        gc.setFill(Color.web("#070C1A", 0.9));
        gc.fillRect(0, top, width, FOOTER);
        gc.setStroke(CARD_BORDER);
        gc.setLineWidth(1);
        gc.strokeLine(0, top, width, top);
        gc.setFont(Font.font(UI, 13));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        String hint = waitingHint();
        if (noticeAge < 5 && !notice.isEmpty()) {
            gc.setFill(TEXT);
            I18n.fillText(gc, "ℹ " + notice, 20, top + FOOTER / 2);
        } else if (frame.getPaused() && frame.getStepMode() && callout != null) {
            gc.setFill(FORMULA);
            I18n.fillText(gc, "⏸ Analyse : le temps est arrêté après l'événement.   ▶ reprendre   ·   ⏭ aller au suivant",
                    20, top + FOOTER / 2);
        } else if (!hint.isEmpty()) {
            gc.setFill(ELECTRON);
            I18n.fillText(gc, "⏳ " + hint, 20, top + FOOTER / 2);
        } else {
            gc.setFill(SOFT);
            I18n.fillText(gc, "Construis un atome, éclaire-le, accélère le temps : les règles décident de ce qui arrive. "
                    + "Glisser : tourner en 3D · clic droit glissé : déplacer · G : le gouffre des échelles.",
                    20, top + FOOTER / 2);
        }
        gc.setTextBaseline(VPos.BASELINE);
    }

    private String waitingHint() {
        double speed = frame.getSpeed();
        if (speed <= 0) {
            return "";
        }
        if (frame.getElectronLifetimeS() > 0) {
            String who = isOneElectron() ? "électron excité (" + frame.getElectronState() + ")" : "atome excité";
            return who + " · durée de vie " + Visuals.duration(frame.getElectronLifetimeS()) + " → "
                    + realWait(frame.getElectronLifetimeS() / speed, frame.getPaused());
        }
        NuclearStatus nucleus = frame.getNucleus();
        if (nucleus.getUnstable() && nucleus.getDecayRate() > 0) {
            return "noyau radioactif · demi-vie " + nucleus.getHalfLife() + " → "
                    + realWait(1 / nucleus.getDecayRate() / speed, frame.getPaused());
        }
        return "";
    }

    private static String realWait(double realSeconds, boolean paused) {
        if (paused) {
            return "le temps est arrêté (▶ pour reprendre)";
        }
        if (realSeconds < 0.5) {
            return "imminent à cette vitesse";
        }
        if (realSeconds < 120) {
            return "≈ " + Visuals.duration(realSeconds) + " d'attente à cette vitesse";
        }
        return "≈ " + Visuals.duration(realSeconds) + " d'attente : accélère le temps ou ⏭";
    }

    private void drawScaleBar(double height) {
        double length = Visuals.niceScaleLength(mpp(), 110);
        double pixels = length / mpp();
        double right = stageRight() - 24;
        double y = height - FOOTER - 26;
        gc.setStroke(Color.web("#CBD5E1", 0.85));
        gc.setLineWidth(1.5);
        gc.strokeLine(right - pixels, y, right, y);
        gc.strokeLine(right - pixels, y - 4, right - pixels, y + 4);
        gc.strokeLine(right, y - 4, right, y + 4);
        gc.setFont(Font.font(MONO, 12));
        gc.setFill(Color.web("#CBD5E1"));
        gc.setTextAlign(TextAlignment.RIGHT);
        I18n.fillText(gc, Visuals.formatLength(length), right, y - 8);
        gc.setFont(Font.font(UI, 11));
        gc.setFill(MUTED);
        I18n.fillText(gc, switch (scale()) {
            case ATOME -> "échelle de l'atome";
            case NOYAU -> "échelle du noyau";
            case NUCLEON -> "échelle du nucléon";
        }, right, y + 16);
    }

    // --- Le gouffre des échelles -------------------------------------------------
    /** Distances au noyau (fm) → pixels, en échelle logarithmique : ρ = K · log₁₀(1 + r / 1 fm). */
    private OrbitalCloud.Radial gulfMap() {
        double outerFm = Math.max(60, outerRadiusPm()) * 1000 * 2.4;
        double k = 0.46 * stageSpan() / Math.log10(1 + outerFm);
        return new OrbitalCloud.Radial() {
            @Override
            public double px(double fm) {
                return k * Math.log10(1 + Math.max(0, fm));
            }

            @Override
            public double length(double px) {
                return Math.pow(10, Math.max(0, px) / k) - 1;
            }
        };
    }

    /**
     * Noyau et nuage ensemble, alors que 10⁵ les séparent : la distance au
     * noyau est comprimée en logarithme (chaque anneau ×10), dans le plan de
     * l'image. Les formes restent calculées ; seules les distances sont tassées.
     */
    private void drawGulf(double[] c) {
        OrbitalCloud.Radial fm = gulfMap();
        OrbitalCloud.Radial pm = new OrbitalCloud.Radial() {
            @Override
            public double px(double value) {
                return fm.px(value * 1000);
            }

            @Override
            public double length(double px) {
                return fm.length(px) / 1000;
            }
        };
        String[] names = {"1 fm", "10 fm", "100 fm", "1 pm", "10 pm", "100 pm", "1 nm", "10 nm"};
        double limit = 0.5 * Math.hypot(stageRight(), canvas.getHeight());
        gc.setLineWidth(1);
        for (int k = 0; k < names.length; k++) {
            double r = fm.px(Math.pow(10, k));
            if (r > limit) {
                break;
            }
            gc.setStroke(Color.web("#94A3B8", k == 3 ? 0.3 : 0.16));
            gc.setLineDashes(3, 7);
            gc.strokeOval(c[0] - r, c[1] - r, 2 * r, 2 * r);
            gc.setLineDashes((double[]) null);
            double angle = -Math.PI * 0.6;
            smallLabel(c[0] + r * Math.cos(angle) + 4, c[1] + r * Math.sin(angle) - 3, names[k], MUTED);
        }
        cloud.draw(gc, camera, c[0], c[1], pm, 0, 0, stageRight(), canvas.getHeight() - FOOTER, Double.POSITIVE_INFINITY);
        drawNucleus(c[0], c[1], fm, false, true);
        double nucleusPx = fm.px(nuclearRadiusFm());
        smallLabel(c[0] + nucleusPx * 0.75 + 6, c[1] + nucleusPx * 0.75 + 14,
                "noyau " + frame.getNucleus().getNuclide() + " · R = " + Visuals.fixed(nuclearRadiusFm(), 2) + " fm", SOFT);
        drawCloudLabels(c, pm);
        if (cloud.layerCount() > 0) {
            // Entre la surface du noyau et le cœur électronique : presque rien. Combien, au juste ?
            double inner = Double.MAX_VALUE;
            for (int i = 0; i < cloud.layerCount(); i++) {
                inner = Math.min(inner, cloud.layer(i).peakPm());
            }
            double edgePm = 0.1 * inner;
            double fromPm = nuclearRadiusFm() / 1000;
            double inside = Math.max(0, cloud.electronsWithin(edgePm) - cloud.electronsWithin(fromPm));
            double middle = (fm.px(nuclearRadiusFm()) + fm.px(edgePm * 1000)) / 2;
            gc.setFont(Font.font(UI, 11));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setFill(FORMULA.deriveColor(0, 1, 1, 0.9));
            I18n.fillText(gc, "presque vide : " + sci(inside) + " électron, en moyenne,", c[0], c[1] + middle + 4);
            I18n.fillText(gc, "entre " + Visuals.formatLength(fromPm * Visuals.PICOMETRE) + " et "
                    + Visuals.formatLength(edgePm * Visuals.PICOMETRE), c[0], c[1] + middle + 18);
        }
        drawGulfCard();
    }

    private void drawGulfCard() {
        double w = 360;
        double h = 112;
        double x = 22;
        double y = canvas.getHeight() - FOOTER - h - 64;
        double top = card(x, y, w, h, "LE GOUFFRE DES ÉCHELLES · vue logarithmique", FORMULA);
        double outerPm = Math.max(1, outerRadiusPm());
        double ratio = outerPm * 1000 / nuclearRadiusFm();
        wrapped(x + 18, top - 4, w - 36, "Chaque anneau est 10 fois plus loin du noyau que le précédent. "
                + "En vrai, le nuage (≈ " + Visuals.formatLength(outerPm * Visuals.PICOMETRE) + ") est "
                + Visuals.significant(ratio) + " fois plus grand que le noyau (" + Visuals.fixed(nuclearRadiusFm(), 1)
                + " fm) : si le noyau était une bille de 1 cm, le nuage s'étendrait sur " + everyday(ratio * 0.01)
                + ". Touche G : retour à la vraie échelle.", 11.5, SOFT, 6);
    }

    /** Une longueur de tous les jours : cm, m ou km. */
    static String everyday(double metres) {
        if (metres >= 1000) {
            return Visuals.format(metres / 1000) + " km";
        }
        if (metres >= 1) {
            return Visuals.format(metres) + " m";
        }
        return Visuals.format(metres * 100) + " cm";
    }

    private void drawGulfLegend(double height) {
        double right = stageRight() - 24;
        double y = height - FOOTER - 26;
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(Font.font(MONO, 12));
        gc.setFill(Color.web("#CBD5E1"));
        I18n.fillText(gc, "×10 par anneau", right, y - 8);
        gc.setFont(Font.font(UI, 11));
        gc.setFill(MUTED);
        I18n.fillText(gc, "échelle logarithmique (le gouffre)", right, y + 16);
    }

    /** Le bandeau de transmutation : « Uranium → Thorium », visible 3 s, même à un milliard d'années par seconde. */
    private void drawTransmutation() {
        if (transmutationAge > 3.2 || transmutation.isEmpty()) {
            return;
        }
        double alpha = Math.min(1, transmutationAge / 0.15) * Math.min(1, (3.2 - transmutationAge) / 0.6);
        double x = stageCenterX();
        double y = canvas.getHeight() - 96;
        gc.setFont(Font.font(UI, FontWeight.BOLD, 22));
        double w = Math.max(transmutation.length() * 12.5, transmutationDetail.length() * 6.6) + 48;
        gc.setFill(Color.web("#0A0F1C", 0.86 * alpha));
        gc.fillRoundRect(x - w / 2, y - 26, w, 58, 14, 14);
        gc.setStroke(ALPHA.deriveColor(0, 1, 1, 0.6 * alpha));
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(x - w / 2, y - 26, w, 58, 14, 14);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFill(TEXT.deriveColor(0, 1, 1, alpha));
        I18n.fillText(gc, transmutation, x, y - 6);
        gc.setFont(Font.font(UI, 11.5));
        gc.setFill(SOFT.deriveColor(0, 1, 1, alpha));
        I18n.fillText(gc, transmutationDetail, x, y + 17);
        gc.setTextBaseline(VPos.BASELINE);
    }

    /** Pendant la plongée : la largeur du champ de vision, et ce qu'il contient vraiment. */
    private void drawDive() {
        if (!diving || logView || frame == null) {
            return;
        }
        double field = stageSpan() * mpp();
        double inside = cloud.electronsWithin(field / 2 / Visuals.PICOMETRE);
        String text = "plongée · champ de vision " + Visuals.formatLength(field) + " · électrons à moins de "
                + Visuals.formatLength(field / 2) + " du noyau : " + sci(inside);
        gc.setFont(Font.font(UI, FontWeight.SEMI_BOLD, 12.5));
        double width = text.length() * 6.7 + 28;
        double x = stageCenterX() - width / 2;
        double y = HEADER + 4;
        gc.setFill(Color.web("#070C1A", 0.88));
        gc.fillRoundRect(x, y, width, 26, 13, 13);
        gc.setStroke(FORMULA.deriveColor(0, 1, 1, 0.55));
        gc.setLineWidth(1);
        gc.strokeRoundRect(x, y, width, 26, 13, 13);
        gc.setFill(FORMULA);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        I18n.fillText(gc, text, stageCenterX(), y + 13);
        gc.setTextBaseline(VPos.BASELINE);
    }

    /** 0,0032 → « 3,2·10⁻³ » ; les nombres ordinaires restent écrits normalement. */
    static String sci(double value) {
        if (value == 0) {
            return "0";
        }
        if (Math.abs(value) >= 0.01 && Math.abs(value) < 1e4) {
            return Visuals.format(value);
        }
        int exponent = (int) Math.floor(Math.log10(Math.abs(value)));
        return Visuals.fixed(value / Math.pow(10, exponent), 1) + "·10" + Visuals.superscript(exponent);
    }

    // --- Panneaux ------------------------------------------------------------
    private double card(double x, double y, double w, double h, String title, Color accent) {
        gc.setFill(CARD);
        gc.fillRoundRect(x, y, w, h, 14, 14);
        gc.setStroke(CARD_BORDER);
        gc.setLineWidth(1);
        gc.strokeRoundRect(x, y, w, h, 14, 14);
        gc.setFill(accent);
        gc.fillRoundRect(x + 14, y + 14, 3, 12, 2, 2);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 11));
        gc.setFill(SOFT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        I18n.fillText(gc, title, x + 24, y + 20);
        gc.setTextBaseline(VPos.BASELINE);
        return y + 44;
    }

    /** Le panneau replié : une pastille qui dit ce qu'il contient ; clic pour l'ouvrir. */
    private void drawPanelChip(Scale scale) {
        String text = switch (scale) {
            case ATOME -> isOneElectron() ? "ÉTAT QUANTIQUE · " + frame.getElectronState()
                    : frame.getAtom().getElectrons() == 0 ? "ÉLECTRONS" : "COUCHES · SPINS · " + openShells();
            case NOYAU -> "FORCES · GOUTTE · MASSE";
            case NUCLEON -> "QUARKS · COULEURS · SPIN";
        };
        gc.setFont(Font.font(UI, FontWeight.BOLD, 11));
        double w = text.length() * 6.9 + 46;
        double x = stageRight() - 52 - w;
        double y = 13;
        boolean hovered = inside(new double[]{x, y, w, 26}, mouseX, mouseY);
        gc.setFill(Color.web("#0C1324", hovered ? 0.98 : 0.86));
        gc.fillRoundRect(x, y, w, 26, 13, 13);
        gc.setStroke(hovered ? ELECTRON.deriveColor(0, 1, 1, 0.7) : CARD_BORDER);
        gc.setLineWidth(1);
        gc.strokeRoundRect(x, y, w, 26, 13, 13);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFill(SOFT);
        I18n.fillText(gc, "▤  " + text + "   ◂", x + 12, y + 13);
        gc.setTextBaseline(VPos.BASELINE);
        panelChip = new double[]{x, y, w, 26};
    }

    /** Les sous-couches incomplètes (5f³ 6d¹), ou la dernière remplie. */
    private String openShells() {
        StringBuilder text = new StringBuilder();
        Subshell last = null;
        for (Subshell subshell : frame.getSubshellsList()) {
            if (subshell.getElectrons() <= 0) {
                continue;
            }
            last = subshell;
            if (subshell.getElectrons() < subshell.getCapacity()) {
                text.append(text.length() > 0 ? " " : "").append(subshell.getLabel())
                        .append(Visuals.superscript(subshell.getElectrons()));
            }
        }
        if (text.length() == 0 && last != null) {
            text.append(last.getLabel()).append(Visuals.superscript(last.getElectrons()));
        }
        return text.toString();
    }

    private void drawPanels(double width, double height, Scale scale) {
        double x = width - PANEL_WIDTH - 14;
        double top = 14;
        double bottom = height - FOOTER - 12;
        // Replier le panneau : l'atome reprend toute la largeur.
        double[] close = {x + PANEL_WIDTH - 30, top + 9, 22, 20};
        boolean hovered = inside(close, mouseX, mouseY);
        switch (scale) {
            case ATOME -> {
                if (frame.getAtom().getElectrons() == 0) {
                    double y = card(x, top, PANEL_WIDTH, 100, "ÉLECTRONS", ELECTRON);
                    wrapped(x + 18, y, PANEL_WIDTH - 36, "Aucun électron. Ajoute-en un : il sera capturé "
                            + "si l'ion qui en résulte existe, repoussé sinon.", 12, SOFT, 4);
                } else if (isOneElectron()) {
                    drawQuantumStateCard(x, top);
                } else {
                    drawShellCard(x, top, bottom);
                }
            }
            case NOYAU -> {
                double y = drawForceCard(x, top);
                y = drawDropCard(x, y + 12, bottom);
                if (y + 12 + 182 <= bottom) {
                    drawMassCard(x, y + 12);
                }
            }
            case NUCLEON -> drawQuarkCard(x, top);
        }
        gc.setFill(hovered ? Color.web("#1E293B") : Color.web("#0C1324", 0));
        gc.fillRoundRect(close[0], close[1], close[2], close[3], 8, 8);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 13));
        gc.setFill(hovered ? TEXT : MUTED);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        I18n.fillText(gc, "»", close[0] + close[2] / 2, close[1] + close[3] / 2);
        gc.setTextBaseline(VPos.BASELINE);
        panelClose = close;
    }

    /** L'électron unique et ses nombres quantiques. */
    private void drawQuantumStateCard(double x, double top) {
        String state = frame.getElectronState();
        int n = principal(state);
        char letter = state.charAt(state.length() - 1);
        int l = Math.max(0, "spdfghi".indexOf(letter));
        String spin = frame.getElectronSpin();
        double y = card(x, top, PANEL_WIDTH, 300, "ÉTAT QUANTIQUE DE L'ÉLECTRON", ELECTRON);
        String[][] rows = {
                {"n = " + n, "couche " + "KLMNOPQ".charAt(Math.min(6, n - 1)) + " : énergie et taille"},
                {"l = " + l, "orbitale " + letter + " : forme du nuage"},
                {"s = 1/2", "spin : l'électron est un fermion"},
                {"m_s = " + spin, spin.startsWith("+") ? "orientation du spin : ↑" : "orientation du spin : ↓"},
        };
        for (String[] row : rows) {
            gc.setFont(Font.font(MONO, FontWeight.BOLD, 14));
            gc.setFill(TEXT);
            gc.setTextAlign(TextAlignment.LEFT);
            I18n.fillText(gc, row[0], x + 18, y + 4);
            gc.setFont(Font.font(UI, 12));
            gc.setFill(SOFT);
            I18n.fillText(gc, row[1], x + 104, y + 4);
            y += 24;
        }
        y += 6;
        double energy = 0;
        for (EnergyLevel level : frame.getLevelsList()) {
            if (level.getN() == n) {
                energy = level.getEnergyEv();
            }
        }
        gc.setFont(Font.font(MONO, 12));
        gc.setFill(FORMULA);
        I18n.fillText(gc, "E = −13,6 · Z² / n² = " + Visuals.format(energy) + " eV", x + 18, y);
        y += 18;
        I18n.fillText(gc, "r = n² · a₀ / Z = " + Visuals.formatLength(electronRadiusPm() * Visuals.PICOMETRE), x + 18, y);
        y += 18;
        double mean = (3.0 * n * n - l * (l + 1)) / 2 * BOHR_PM / Math.max(1, frame.getAtom().getProtons());
        I18n.fillText(gc, "⟨r⟩ = (3n² − l(l+1))·a₀/2Z = " + Visuals.formatLength(mean * Visuals.PICOMETRE), x + 18, y);
        y += 18;
        int nodes = n - l - 1;
        y = wrapped(x + 18, y, PANEL_WIDTH - 36, "nuage : |ψ|² exact, " + (nodes == 0 ? "aucun nœud radial"
                : nodes == 1 ? "1 nœud radial" : nodes + " nœuds radiaux")
                + " ; m non mesuré : les 2l + 1 orientations à la fois, d'où un nuage sphérique.", 11, SOFT, 3) + 6;
        wrapped(x + 18, y, PANEL_WIDTH - 36, frame.getElectronLifetimeS() > 0
                ? "Durée de vie de " + state + " : " + Visuals.duration(frame.getElectronLifetimeS())
                + ". En retombant, il émet un photon (spin 1) : l change de ±1, son spin reste " + spin + "."
                : "État fondamental : il ne peut plus descendre. Seul un photon de la bonne énergie le fera monter.",
                12, SOFT, 4);
    }

    /** Les sous-couches dans l'ordre de remplissage : une case par orbitale, une flèche par spin. */
    private void drawShellCard(double x, double top, double bottom) {
        Map<String, Subshell> present = new HashMap<>();
        for (Subshell subshell : frame.getSubshellsList()) {
            present.put(subshell.getN() + "," + subshell.getL(), subshell);
        }
        List<int[]> rows = new ArrayList<>();
        int[] next = null;
        for (int[] nl : KLECHKOWSKI) {
            Subshell subshell = present.get(nl[0] + "," + nl[1]);
            if (subshell != null) {
                rows.add(nl);
                if (subshell.getElectrons() < subshell.getCapacity() && next == null) {
                    next = nl;
                }
            } else if (next == null) {
                next = nl;
                rows.add(nl);
            }
        }
        // Atomes lourds : les sous-couches pleines du début tiennent sur une ligne (clic : tout voir).
        int hidden = 0;
        boolean foldable = rows.size() > 9;
        if (foldable && !shellsExpanded) {
            while (hidden < rows.size() - 7) {
                Subshell subshell = present.get(rows.get(hidden)[0] + "," + rows.get(hidden)[1]);
                if (subshell == null || subshell.getElectrons() < subshell.getCapacity()) {
                    break;
                }
                hidden++;
            }
            if (hidden < 2) {
                hidden = 0;
            }
        }
        List<int[]> shown = rows.subList(hidden, rows.size());
        int lines = shown.size() + (foldable ? 1 : 0);
        double footer = 100;
        double available = bottom - top - 44 - footer;
        double rowHeight = clamp(available / Math.max(1, lines), 11, 21);
        double height = 44 + lines * rowHeight + footer;
        double y = card(x, top, PANEL_WIDTH, height, "COUCHES · ORDRE DE REMPLISSAGE · SPINS", ELECTRON);
        double box = Math.min(18, rowHeight - 3);
        if (foldable) {
            String text;
            if (hidden > 0) {
                int electrons = 0;
                for (int k = 0; k < hidden; k++) {
                    electrons += present.get(rows.get(k)[0] + "," + rows.get(k)[1]).getElectrons();
                }
                text = "▸ " + label(rows.get(0)) + " → " + label(rows.get(hidden - 1)) + " : " + hidden
                        + " sous-couches pleines, " + electrons + " e⁻";
            } else {
                text = "▾ replier les sous-couches pleines";
            }
            boolean over = inside(new double[]{x + 10, y - 2, PANEL_WIDTH - 20, rowHeight}, mouseX, mouseY);
            gc.setFont(Font.font(UI, Math.min(11, rowHeight - 2)));
            gc.setFill(over ? TEXT : MUTED);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.CENTER);
            I18n.fillText(gc, text, x + 18, y + box / 2);
            gc.setTextBaseline(VPos.BASELINE);
            shellsToggle = new double[]{x + 10, y - 2, PANEL_WIDTH - 20, rowHeight};
            y += rowHeight;
        }
        for (int[] nl : shown) {
            Subshell subshell = present.get(nl[0] + "," + nl[1]);
            boolean isNext = next != null && nl[0] == next[0] && nl[1] == next[1];
            Color color = Visuals.subshellColor(nl[1]);
            gc.setFont(Font.font(MONO, FontWeight.BOLD, Math.min(13, rowHeight - 1)));
            gc.setFill(subshell == null ? MUTED : color);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.CENTER);
            I18n.fillText(gc, nl[0] + "spdf".substring(nl[1], nl[1] + 1), x + 18, y + box / 2);
            int orbitals = 2 * nl[1] + 1;
            for (int k = 0; k < orbitals; k++) {
                double bx = x + 52 + k * (box + 3);
                int count = subshell != null && k < subshell.getOccupationCount() ? subshell.getOccupation(k) : 0;
                gc.setStroke(isNext ? FORMULA.deriveColor(0, 1, 1, 0.8) : color.deriveColor(0, 1, 1, 0.45));
                gc.setLineWidth(1);
                gc.strokeRect(bx, y, box, box);
                gc.setStroke(color);
                gc.setLineWidth(1.4);
                if (count >= 1) {
                    arrow(bx + box * (count == 2 ? 0.32 : 0.5), y + 2, y + box - 2, true);
                }
                if (count == 2) {
                    arrow(bx + box * 0.68, y + 2, y + box - 2, false);
                }
            }
            gc.setFont(Font.font(UI, Math.min(11, rowHeight - 2)));
            gc.setFill(isNext ? FORMULA : MUTED);
            gc.setTextAlign(TextAlignment.RIGHT);
            String count = subshell == null ? "0/" + 2 * orbitals : subshell.getElectrons() + "/" + subshell.getCapacity();
            I18n.fillText(gc, isNext ? "prochain e⁻ · " + count : count, x + PANEL_WIDTH - 16, y + box / 2);
            gc.setTextBaseline(VPos.BASELINE);
            y += rowHeight;
        }
        wrapped(x + 18, y + 14, PANEL_WIDTH - 36,
                "Flèches = spin de chaque électron : ↑ +½ (jaune), ↓ −½ (rose).  "
                        + "Pauli : 2 électrons au plus par case, de spins opposés ↑↓.  Hund : une case chacun "
                        + "d'abord, spins parallèles.  Klechkowski : ordre n + l croissant.", 11, SOFT, 6);
    }

    private static String label(int[] nl) {
        return nl[0] + "spdf".substring(nl[1], nl[1] + 1);
    }

    private void arrow(double x, double top, double bottom, boolean up) {
        gc.strokeLine(x, top, x, bottom);
        double tip = up ? top : bottom;
        double back = up ? top + 3.5 : bottom - 3.5;
        gc.strokeLine(x, tip, x - 2.5, back);
        gc.strokeLine(x, tip, x + 2.5, back);
    }

    /** Le bilan de Weizsäcker : ce qui lie le noyau, ce qui le défait. */
    private double drawForceCard(double x, double top) {
        List<NuclearTerm> terms = frame.getNuclearTermsList();
        double height = 54 + Math.max(1, terms.size()) * 34 + 30;
        double y = card(x, top, PANEL_WIDTH, height, "FORCES DU NOYAU · énergie de liaison", STRONG);
        if (terms.isEmpty()) {
            wrapped(x + 18, y, PANEL_WIDTH - 36, "Un nucléon seul n'a rien à quoi se lier.", 12, SOFT, 3);
            return top + height;
        }
        double scaleMev = 1;
        double total = 0;
        for (NuclearTerm term : terms) {
            scaleMev = Math.max(scaleMev, Math.abs(term.getMev()));
            total += term.getMev();
        }
        double barLeft = x + 120;
        double barWidth = PANEL_WIDTH - 120 - 70;
        for (NuclearTerm term : terms) {
            double value = term.getMev();
            gc.setFont(Font.font(UI, FontWeight.SEMI_BOLD, 12));
            gc.setFill(TEXT);
            gc.setTextAlign(TextAlignment.LEFT);
            I18n.fillText(gc, term.getName(), x + 18, y + 4);
            Color color = value >= 0 ? STRONG : COULOMB;
            double length = Math.abs(value) / scaleMev * barWidth;
            gc.setFill(color.deriveColor(0, 1, 1, 0.75));
            gc.fillRoundRect(barLeft, y - 6, Math.max(2, length), 10, 4, 4);
            gc.setFont(Font.font(MONO, 11));
            gc.setFill(color);
            gc.setTextAlign(TextAlignment.RIGHT);
            I18n.fillText(gc, Visuals.signed(value, 0), x + PANEL_WIDTH - 14, y + 4);
            gc.setFont(Font.font(UI, 10));
            gc.setFill(MUTED);
            gc.setTextAlign(TextAlignment.LEFT);
            I18n.fillText(gc, term.getDescription(), x + 18, y + 18);
            y += 34;
        }
        gc.setFont(Font.font(MONO, FontWeight.BOLD, 12));
        gc.setFill(FORMULA);
        I18n.fillText(gc, "B = " + Visuals.format(total) + " MeV (modèle) · "
                + Visuals.fixed(total / Math.max(1, frame.getAtom().getMassNumber()), 2) + " MeV/nucléon", x + 18, y + 6);
        return top + height;
    }

    /** La goutte quantique : ses vibrations de surface et les pions échangés, calculés. */
    private double drawDropCard(double x, double top, double bottom) {
        NuclearDrop.Mode quadrupole = drop.quadrupole();
        boolean light = drop.outOfModel();
        double height = light ? 234 : 206;
        if (quadrupole == null || top + height > bottom) {
            return top;
        }
        double y = card(x, top, PANEL_WIDTH, height, "GOUTTE QUANTIQUE · vibrations et pions", DROP);
        double period = quadrupole.periodSeconds();
        String[] lines = {
                "ħω₂ = ħ√(C₂/B₂) = " + Visuals.fixed(quadrupole.hbarOmegaMev(), 1) + " MeV",
                "période " + sci(period) + " s",
                "à l'écran : ralenti ×" + sci(NuclearDrop.QUADRUPOLE_SHOWN_S / period),
                "β₂ point zéro = " + Visuals.fixed(Math.min(9.99, quadrupole.betaRms()), 2)
                        + (light ? " (vu " + Visuals.fixed(NuclearDrop.SHOWN_BETA_MAX, 2) + ")" : ""),
                "pions : " + drop.chargedExchanges() + " p↔n, " + drop.neutralExchanges() + " π⁰",
        };
        gc.setFont(Font.font(MONO, 12));
        gc.setTextAlign(TextAlignment.LEFT);
        for (String line : lines) {
            gc.setFill(line.startsWith("pions") || line.startsWith("à l'écran") ? TEXT : FORMULA);
            I18n.fillText(gc, line, x + 18, y + 4);
            y += 19;
        }
        String note = "Les nucléons ne sont pas figés : la surface vibre (Bohr–Mottelson), et chaque pion "
                + "virtuel (portée 1,41 fm) qui passe d'un proton à un neutron les échange. La charge reste "
                + frame.getAtom().getProtons() + ".";
        if (light) {
            note += " Ici la goutte (écoulement irrotationnel) prédit β > 0,3 : hors de son domaine, amplitude bornée.";
        }
        wrapped(x + 18, y + 4, PANEL_WIDTH - 36, note, 11, SOFT, light ? 6 : 4);
        return top + height;
    }

    /** E = Δm c² : le noyau pèse moins que ses morceaux séparés (masses mesurées). */
    private void drawMassCard(double x, double top) {
        NuclearStatus nucleus = frame.getNucleus();
        double y = card(x, top, PANEL_WIDTH, 182, "BILAN DE MASSE · E = Δm·c²", FORMULA);
        if (nucleus.getAtomicMassU() <= 0) {
            wrapped(x + 18, y, PANEL_WIDTH - 36, "Ce noyau n'a jamais été pesé : pas de masse mesurée.", 12, SOFT, 3);
            return;
        }
        int z = frame.getAtom().getProtons();
        int n = frame.getAtom().getNeutrons();
        String[] lines = {
                z + " × m(¹H)  +  " + n + " × m(n)",
                "  = " + Visuals.fixed(nucleus.getNucleonsMassU(), 6) + " u  (séparés)",
                "masse mesurée : " + Visuals.fixed(nucleus.getAtomicMassU(), 6) + " u",
                "Δm = " + Visuals.fixed(nucleus.getNucleonsMassU() - nucleus.getAtomicMassU(), 6) + " u",
                "B = Δm × 931,494 = " + Visuals.format(nucleus.getBindingMeasuredMev()) + " MeV",
        };
        gc.setFont(Font.font(MONO, 12));
        gc.setTextAlign(TextAlignment.LEFT);
        for (int i = 0; i < lines.length; i++) {
            gc.setFill(i == lines.length - 1 ? FORMULA : TEXT);
            I18n.fillText(gc, lines[i], x + 18, y + 4);
            y += 19;
        }
        wrapped(x + 18, y + 4, PANEL_WIDTH - 36, "Cette masse disparue est l'énergie qui tient le noyau "
                + "(masses AME2020).", 11, SOFT, 2);
    }

    /** À l'intérieur d'un nucléon : composition, couleur, spin. */
    private void drawQuarkCard(double x, double top) {
        boolean proton = focusedProton;
        double y = card(x, top, PANEL_WIDTH, 262, proton ? "PROTON · u u d" : "NEUTRON · u d d", COLOR_CHARGE[2]);
        String[] lines = {
                proton ? "charge : +2/3 +2/3 −1/3 = +1" : "charge : +2/3 −1/3 −1/3 = 0",
                "spin : ↑ + ↑ + ↓ = ½",
                "couleurs : rouge + vert + bleu = blanc"};
        gc.setFont(Font.font(MONO, 12));
        gc.setTextAlign(TextAlignment.LEFT);
        for (String line : lines) {
            gc.setFill(TEXT);
            I18n.fillText(gc, line, x + 18, y + 4);
            y += 20;
        }
        ParticleInfo up = particles.get("up");
        ParticleInfo down = particles.get("down");
        ParticleInfo self = particles.get(proton ? "proton" : "neutron");
        if (up != null && down != null && self != null) {
            double quarks = proton ? 2 * up.getMassMev() + down.getMassMev() : up.getMassMev() + 2 * down.getMassMev();
            gc.setFill(FORMULA);
            I18n.fillText(gc, "3 quarks ≈ " + Visuals.format(quarks) + " MeV", x + 18, y + 6);
            y += 20;
            I18n.fillText(gc, (proton ? "proton" : "neutron") + " = " + Visuals.format(self.getMassMev()) + " MeV", x + 18, y + 6);
            y += 28;
        }
        wrapped(x + 18, y, PANEL_WIDTH - 36, "99 % de la masse vient de l'énergie des gluons qui lient "
                + "les quarks (E = mc²). Les gluons échangent sans cesse la couleur : aucun quark n'est "
                + "jamais isolé.", 11, SOFT, 5);
    }

    private double wrapped(double x, double y, double width, String text, double size, Color color, int maxLines) {
        gc.setFont(Font.font(UI, size));
        gc.setFill(color);
        gc.setTextAlign(TextAlignment.LEFT);
        List<String> lines = wrap(text, (int) (width / (size * 0.52)));
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
            I18n.fillText(gc, lines.get(i), x, y);
            y += size + 5;
        }
        return y;
    }

    // --- Bulle de l'événement ---------------------------------------------
    /**
     * Ce que montre la flèche de la bulle : la particule en mouvement tant
     * qu'elle est à l'écran, puis l'acteur resté en place (l'électron, ou le
     * noyau dans la loupe). Retourne {x, y, rayon du cercle} et met à jour
     * l'étiquette. Null s'il n'y a rien à désigner.
     */
    private double[] calloutAnchor(double[] c, Scale scale) {
        String kind = callout.getKind();
        if (calloutAnim != null && anims.contains(calloutAnim) && calloutAnim.delay <= 0
                && !Double.isNaN(calloutAnim.screenX) && onScreen(calloutAnim.screenX, calloutAnim.screenY)) {
            return new double[]{calloutAnim.screenX, calloutAnim.screenY, 16};
        }
        if (kind.equals("decay") || kind.equals("nucleon")) {
            if (logView) {
                calloutTarget = "le noyau";
                return new double[]{c[0], c[1], gulfMap().px(nuclearRadiusFm()) + 10};
            }
            if (scale == Scale.ATOME && loupeVisible()) {
                double[] loupe = loupeCenter();
                calloutTarget = kind.equals("decay") ? "le noyau qui s'est désintégré (agrandi)" : "le noyau (agrandi)";
                return new double[]{loupe[0], loupe[1], LOUPE_RADIUS + 6};
            }
            calloutTarget = "le noyau";
            return new double[]{c[0], c[1], nuclearRadiusFm() * Visuals.FEMTOMETRE / mpp() + 10};
        }
        if (scale != Scale.ATOME || frame.getAtom().getElectrons() == 0
                || kind.equals("passed") || kind.equals("structure")) {
            return null;
        }
        if (isOneElectron() || frame.getExcitationLevel() > 0) {
            double[] e = electronOffset();
            calloutTarget = isOneElectron() ? "l'électron (" + frame.getElectronState() + ")" : "l'électron excité";
            return new double[]{c[0] + e[0], c[1] + e[1], 16};
        }
        if (valenceActor != null) {
            calloutTarget = switch (kind) {
                case "captured" -> "l'électron capturé (" + valenceLabel + ")";
                case "emitted" -> callout.getTitle().startsWith("Photon d'attachement")
                        || callout.getTitle().startsWith("Rayonnement")
                        ? "l'électron qui vient de se lier (" + valenceLabel + ")"
                        : "l'électron retombé (" + valenceLabel + ")";
                default -> "un électron de valence (" + valenceLabel + ")";
            };
            return new double[]{valenceActor[0], valenceActor[1], 16};
        }
        return null;
    }

    private boolean onScreen(double x, double y) {
        return x > 0 && x < stageRight() && y > HEADER && y < canvas.getHeight() - FOOTER;
    }

    private List<String[]> calloutLines() {
        List<String[]> lines = new ArrayList<>();
        for (String line : wrap(callout.getFormula(), (int) ((CALLOUT_WIDTH - 32) / 7.0))) {
            lines.add(new String[]{"f", line});
        }
        for (String line : wrap(callout.getDetail(), (int) ((CALLOUT_WIDTH - 32) / 6.2))) {
            lines.add(new String[]{"d", line});
        }
        return lines;
    }

    /** Aux échelles du noyau et du nucléon, la bulle se replie : elle cacherait ce qu'on regarde. */
    private boolean calloutFolded() {
        return scale() != Scale.ATOME && !calloutUnfolded;
    }

    private double[] calloutBox() {
        if (callout == null) {
            return null;
        }
        double height = calloutFolded() ? 46 : 66 + calloutLines().size() * 17;
        return new double[]{22, HEADER + 6, CALLOUT_WIDTH, height};
    }

    /** L'événement, son calcul, relié à celui qui l'a produit. Reste jusqu'au suivant. */
    private void drawCallout(double[] c, Scale scale) {
        if (callout == null || calloutClosed) {
            return;
        }
        double alpha = Math.min(1, calloutAge / 0.5);
        double[] box = calloutBox();
        double[] anchor = calloutAnchor(c, scale);
        Color accent = eventColor(callout);

        if (anchor != null && !calloutFolded()) {
            drawPointer(box, anchor, accent, alpha);
        }

        gc.setFill(Color.web("#0B1222", 0.95 * alpha));
        gc.fillRoundRect(box[0], box[1], box[2], box[3], 14, 14);
        gc.setStroke(accent.deriveColor(0, 1, 1, 0.7 * alpha));
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(box[0], box[1], box[2], box[3], 14, 14);

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 10));
        gc.setFill(accent.deriveColor(0, 1, 1, alpha));
        I18n.fillText(gc, kindLabel(callout) + "   ·   t = " + Visuals.duration(callout.getTimeS()), box[0] + 16, box[1] + 16);
        gc.setFill(MUTED.deriveColor(0, 1, 1, alpha));
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(Font.font(UI, 13));
        I18n.fillText(gc, "✕", box[0] + box[2] - 12, box[1] + 15);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 15));
        gc.setFill(TEXT.deriveColor(0, 1, 1, alpha));
        I18n.fillText(gc, fit(callout.getTitle(), CALLOUT_WIDTH - 32, 7.3), box[0] + 16, box[1] + 33);
        if (calloutFolded()) {
            gc.setFont(Font.font(UI, 10));
            gc.setFill(MUTED.deriveColor(0, 1, 1, alpha));
            gc.setTextAlign(TextAlignment.RIGHT);
            I18n.fillText(gc, "clic : détails", box[0] + box[2] - 34, box[1] + 15);
            gc.setTextBaseline(VPos.BASELINE);
            return;
        }
        double y = box[1] + 57;
        for (String[] line : calloutLines()) {
            boolean formula = line[0].equals("f");
            gc.setFont(formula ? Font.font(MONO, 12) : Font.font(UI, 12));
            gc.setFill((formula ? FORMULA : SOFT).deriveColor(0, 1, 1, alpha));
            I18n.fillText(gc, line[1], box[0] + 16, y);
            y += 17;
        }
        gc.setTextBaseline(VPos.BASELINE);
    }

    /**
     * La flèche de la bulle : elle part du bord de la bulle, finit par une
     * pointe sur un cercle autour de l'acteur, et dit ce qu'elle montre.
     */
    private void drawPointer(double[] box, double[] anchor, Color accent, double alpha) {
        double radius = anchor[2] + 2 * Math.sin(clock * 3);
        // Départ : le point du bord de la bulle le plus proche de la cible.
        double fromX = Math.max(box[0], Math.min(box[0] + box[2], anchor[0]));
        double fromY = Math.max(box[1], Math.min(box[1] + box[3], anchor[1]));
        double dx = anchor[0] - fromX;
        double dy = anchor[1] - fromY;
        double distance = Math.hypot(dx, dy);
        if (distance < radius + 8) {
            return;
        }
        double ux = dx / distance;
        double uy = dy / distance;
        double toX = anchor[0] - ux * radius;
        double toY = anchor[1] - uy * radius;
        Color line = accent.deriveColor(0, 0.8, 1.2, 0.85 * alpha);
        gc.setStroke(line);
        gc.setLineWidth(1.6);
        gc.strokeLine(fromX, fromY, toX, toY);
        gc.setFill(line);
        gc.fillPolygon(new double[]{toX, toX - ux * 10 - uy * 5, toX - ux * 10 + uy * 5},
                new double[]{toY, toY - uy * 10 + ux * 5, toY - uy * 10 - ux * 5}, 3);
        gc.setStroke(line);
        gc.setLineWidth(2);
        gc.strokeOval(anchor[0] - radius, anchor[1] - radius, radius * 2, radius * 2);
        if (!calloutTarget.isEmpty()) {
            // L'étiquette se place du côté opposé à la bulle, pour ne pas la croiser.
            gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
            double width = calloutTarget.length() * 6.8 + 16;
            double labelX = Math.max(8, Math.min(stageRight() - width - 8, anchor[0] + radius + 8));
            double labelY = anchor[1] + radius + 6;
            if (labelY + 22 > canvas.getHeight() - FOOTER - 64) {
                labelY = anchor[1] - radius - 28;   // au-dessus : en bas, les boutons de navigation
            }
            gc.setFill(Color.web("#070C1A", 0.88 * alpha));
            gc.fillRoundRect(labelX, labelY, width, 22, 10, 10);
            gc.setStroke(line);
            gc.setLineWidth(1);
            gc.strokeRoundRect(labelX, labelY, width, 22, 10, 10);
            gc.setFill(TEXT.deriveColor(0, 1, 1, alpha));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.CENTER);
            I18n.fillText(gc, calloutTarget, labelX + 8, labelY + 11);
            gc.setTextBaseline(VPos.BASELINE);
        }
    }

    private static String kindLabel(SimEvent event) {
        return switch (event.getKind()) {
            case "emitted" -> "ÉMISSION DE LUMIÈRE";
            case "absorbed" -> "ABSORPTION";
            case "passed" -> "PHOTON NON ABSORBÉ";
            case "photo_ionized" -> "EFFET PHOTOÉLECTRIQUE";
            case "ionized" -> "IONISATION";
            case "captured" -> "CAPTURE D'UN ÉLECTRON";
            case "rejected" -> "ÉLECTRON REPOUSSÉ";
            case "excited" -> "EXCITATION";
            case "decay" -> "DÉSINTÉGRATION";
            case "nucleon" -> "NOYAU MODIFIÉ";
            case "structure" -> "ATOME";
            default -> "INFORMATION";
        };
    }

    // --- Fiche de particule ------------------------------------------------
    private double[] particleCardBox() {
        return new double[]{stageRight() - 362, HEADER + 6, 342, 240};
    }

    private void drawParticleCard() {
        ParticleInfo info = particles.get(selectedParticle);
        if (info == null) {
            selectedParticle = null;
            return;
        }
        double[] box = particleCardBox();
        double y = card(box[0], box[1], box[2], box[3], "FICHE · " + info.getName().toUpperCase(Locale.ROOT), ELECTRON);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 26));
        gc.setFill(TEXT);
        gc.setTextAlign(TextAlignment.RIGHT);
        I18n.fillText(gc, info.getSymbol(), box[0] + box[2] - 18, box[1] + 32);
        String mass = info.getMassMev() == 0 ? "0 (ou presque)" : Visuals.significant(info.getMassMev()) + " MeV/c²";
        String[][] rows = {
                {"famille", info.getFamily()}, {"masse", mass}, {"charge", info.getCharge() + " e"},
                {"spin", info.getSpin()}, {"moment magn.", info.getMagnetic()}, {"durée de vie", info.getLifetime()},
                {"ressent", info.getInteractions()}};
        gc.setTextAlign(TextAlignment.LEFT);
        for (String[] row : rows) {
            gc.setFont(Font.font(UI, 11));
            gc.setFill(MUTED);
            I18n.fillText(gc, row[0], box[0] + 18, y);
            gc.setFont(Font.font(UI, FontWeight.SEMI_BOLD, 12));
            gc.setFill(TEXT);
            I18n.fillText(gc, fit(row[1], 222, 6.3), box[0] + 104, y);
            y += 17;
        }
        wrapped(box[0] + 18, y + 4, box[2] - 36, info.getNote(), 11, SOFT, 3);
        gc.setFont(Font.font(UI, 9));
        gc.setFill(MUTED);
        I18n.fillText(gc, "source : " + info.getSource() + " · clic pour fermer", box[0] + 18, box[1] + box[3] - 10);
    }

    // --- Survol ------------------------------------------------------------
    private Hit hitAt(double x, double y) {
        Hit best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Hit hit : hits) {
            double distance = Math.hypot(x - hit.x(), y - hit.y());
            if (distance <= hit.radius() && distance < bestDistance) {
                best = hit;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void drawTooltip() {
        if (mouseX < 0 || dragging) {
            return;
        }
        String text = null;
        if (loupeHit(mouseX, mouseY)) {
            NuclearStatus nucleus = frame.getNucleus();
            text = "Noyau " + nucleus.getNuclide() + (nucleus.getUnstable() ? " · demi-vie " + nucleus.getHalfLife() : " · stable")
                    + " · cliquer pour y entrer";
        } else {
            Hit hit = hitAt(mouseX, mouseY);
            if (hit != null) {
                text = hit.hint() + (hit.key().isEmpty() ? "" : "  ·  clic : fiche");
            } else if (scale() == Scale.ATOME && mouseX < stageRight()) {
                int owner = cloud.ownerAt(mouseX, mouseY);
                if (owner >= 0 && owner < cloud.layerCount()) {
                    text = "ici, surtout : " + cloudHint(owner);
                }
            }
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        gc.setFont(Font.font(UI, 12));
        double width = text.length() * 6.3 + 20;
        double x = Math.min(mouseX + 16, canvas.getWidth() - width - 8);
        double y = Math.min(mouseY + 18, canvas.getHeight() - FOOTER - 30);
        gc.setFill(Color.web("#0F172A", 0.96));
        gc.fillRoundRect(x, y, width, 24, 10, 10);
        gc.setStroke(CARD_BORDER);
        gc.setLineWidth(1);
        gc.strokeRoundRect(x, y, width, 24, 10, 10);
        gc.setFill(TEXT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        I18n.fillText(gc, text, x + 10, y + 12);
        gc.setTextBaseline(VPos.BASELINE);
    }

    // =================================================================
    public static Color eventColor(SimEvent event) {
        return switch (event.getKind()) {
            case "emitted", "absorbed", "passed", "photon_fired" -> Visuals.wavelengthColor(event.getWavelengthNm());
            case "decay" -> Color.web("#FB7185");
            case "captured", "ionized", "photo_ionized" -> ELECTRON;
            case "rejected" -> Color.web("#FB923C");
            case "nucleon" -> Visuals.PROTON;
            default -> Color.web("#94A3B8");
        };
    }

    /** Coupe un texte en lignes d'au plus `width` caractères, aux espaces. */
    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String fit(String text, double width, double charWidth) {
        int max = (int) (width / charWidth);
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    private void centredText(double x, double y, String text, double size, Color color) {
        gc.setFont(Font.font(UI, size));
        gc.setFill(color);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            I18n.fillText(gc, lines[i], x, y + (i - (lines.length - 1) / 2.0) * (size + 6));
        }
        gc.setTextBaseline(VPos.BASELINE);
    }
}
