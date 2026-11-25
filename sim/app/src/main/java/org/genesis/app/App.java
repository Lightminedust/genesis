package org.genesis.app;

import org.genesis.engine.EngineClient;
import org.genesis.engine.BundledEngine;
import org.genesis.fusion.FusionLab;
import org.genesis.mascot.GenesisMascot;
import org.genesis.atom.LightPanel;
import org.genesis.matter.MatterCanvas;
import org.genesis.matter.MoleculeCanvas;
import org.genesis.atom.NucleusPanel;
import org.genesis.subatomic.PartonView;
import org.genesis.atom.PhotonPacket;
import org.genesis.subatomic.QuantumLab;
import org.genesis.atom.QuantumView;
import org.genesis.fusion.ReactorView;
import org.genesis.chain.ChainView;
import org.genesis.molecule.MoleculeView;
import org.genesis.atom.SimCanvas;
import org.genesis.atom.StabilityChart;
import org.genesis.fusion.StarView;
import org.genesis.subatomic.SubatomicView;
import org.genesis.common.Guide;
import org.genesis.common.Icons;
import org.genesis.common.Plan;
import org.genesis.common.Visuals;

import com.studio.genesis.grpc.PeriodicTable;
import com.studio.genesis.grpc.SimEvent;
import com.studio.genesis.grpc.UniverseFrame;
import com.studio.genesis.grpc.UserAction.ActionType;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;

/**
 * Genesis - simulateur atomique.
 *
 * Le moteur Python fait tourner le temps et applique les règles de la
 * physique ; ce qui arrive en découle et revient sous forme d'événements.
 * Ici, on ne fait que trois choses : envoyer les gestes de l'utilisateur
 * (construire, éclairer, régler le temps), dessiner l'atome et ce qui lui
 * arrive, et garder le journal.
 */
public class App extends Application {

    /**
     * Échelles « Matière » (Lennard-Jones) et « Molécule » (chimie quantique) :
     * le code est conservé, mais elles sont désactivées dans l'interface.
     */
    static final boolean ADVANCED_SCALES = false;

    /** Le cœur d'étoile : conservé, mais retiré de l'interface (on se concentre sur la fusion). */
    static final boolean STAR_SCREEN = false;
    /** Le laboratoire subatomique (protons, neutrons, électrons pas à pas) est incomplet : masqué du menu et du clavier, son code reste. */
    static final boolean SUBATOMIC_SCREEN = false;
    /** La vue de Bohr (anneaux, électrons qui tournent) est en travaux : bouton et touche O masqués, son code reste. */
    static final boolean BOHR_VIEW = false;
    /** Le simulateur de masse critique (réaction en chaîne) : mis de côté, son code et son moteur restent. */
    static final boolean CHAIN_SCREEN = false;

    private static final double PREFERRED_WIDTH = 1440;
    private static final double PREFERRED_HEIGHT = 900;
    private static final double SIDEBAR_WIDTH = Plan.RIGHT_WIDTH;

    private SimCanvas simCanvas;
    /** La dernière frame reçue : le noyau visé par « Tirer une α ». */
    private UniverseFrame lastFrame;
    private MatterCanvas matterCanvas;
    private FusionLab fusionView;
    private ReactorView reactorView;
    private ChainView chainView;
    private boolean chainActive;
    private MoleculeView moleculeView;
    private PeriodicTableDialog moleculeDialog;
    private boolean moleculeActive;
    private PeriodicTableDialog chainDialog;
    private SubatomicView subatomicView;
    private boolean subatomicActive;
    private QuantumLab quantumLab;
    private PartonView partonView;
    private boolean partonsActive;
    private boolean nucleonActive;
    private boolean reactorActive;
    private boolean fusionActive;
    private StarView starView;
    private boolean starActive;
    private Region mainScreen;
    private MoleculeCanvas moleculeCanvas;
    private boolean chemistryActive;
    private PeriodicTableDialog partnerDialog;
    private ToggleButton matterToggle;
    private Slider wallSlider;
    private Label wallLabel;
    private boolean updatingWall;
    private boolean matterActive;
    private LightPanel lightPanel;
    private QuantumView quantumView;
    private StabilityChart stabilityChart;
    private NucleusPanel nucleusPanel;
    private IdentityStrip identity;
    private EventJournal journal;
    /** Genesis, la petite guide : elle lit le journal et explique ce que le moteur a calculé. */
    private GenesisMascot genesis;
    private EngineClient client;
    private BundledEngine bundledEngine;
    private boolean engineConnected;
    private boolean stopping;
    private ActionType pendingLabStart;

    /** La barre du laboratoire ouvert (voir LabBar) et l'accueil, qui présente les laboratoires (voir HomeScreen). */
    private LabBar labBar;
    private HomeScreen home;
    private LabBar.Lab shownLab;
    /**
     * Le laboratoire qu'on ouvre (ou l'atome, qu'on rejoint), tant que l'écran d'attente est là : la barre le montre.
     * Le moteur referme d'abord l'ancien ; entre les deux, aucune frame n'annonce de laboratoire actif, et la barre
     * repassait à l'atome pendant le chargement du laboratoire demandé.
     */
    private LabBar.Lab opening;
    /** Les échelles de l'atome (Atome, Noyau, Nucléon, Gouffre) : ses modes, posés dans l'en-tête. */
    private Region atomModes;
    /** Le guide du laboratoire montré (touche H), et le message bref du bas de l'écran. */
    private final GuideOverlay guideOverlay = new GuideOverlay();
    private final Toast toast = new Toast();
    /** Les panneaux des laboratoires sont-ils montrés (Tab les cache tous, pour laisser la scène seule) ? */
    private boolean panelsShown = true;
    /** Un laboratoire a-t-il été ouvert depuis l'accueil (sa carte le dit, Échap y revient) ? */
    private boolean labOpened;
    private Region atomLeft, atomRight, atomScene;
    private Button tableButton;
    private final Label elementSymbol = new Label("?");
    private final Label elementName = new Label("Choisir un élément");
    private final Label elementNuclide = new Label("table périodique");
    private final Label protonCount = new Label("0");
    private final Label neutronCount = new Label("0");
    private final Label electronCount = new Label("0");
    private TimelineBar timeline;
    private final LabLoader loader = new LabLoader();
    /** « Une nouvelle version est disponible », en bas à droite. */
    private final UpdateCard updateCard = new UpdateCard();
    /** L'écran de démarrage : une orbitale calculée, l'étape en cours, le soutien à Genesis (voir StartupScreen). */
    private final StartupScreen startup = new StartupScreen();
    /**
     * Les statistiques d'utilisation (voir Telemetry) et la carte du premier lancement. Rien ne part avant start() :
     * les aperçus construisent l'écran sans jamais rien envoyer.
     */
    private final Telemetry telemetry = Telemetry.create();
    private final TelemetryNotice telemetryNotice = new TelemetryNotice();
    private boolean noticeOffered;
    private Stage window;
    /** Une version plus récente trouvée sur GitHub, proposée dès que l'écran de chargement s'efface. */
    private UpdateChecker.Release pendingUpdate;
    private static final String SKIPPED_VERSION_PREF = "update.skipped";
    private HBox guide;
    private boolean genesisShown;
    /**
     * Genesis est mise de côté : son code reste (GenesisMascot, GenesisBlob, GenesisFlask), mais on ne l'affiche pas. Le journal
     * n'a donc qu'une vue, les logs complets, et la bascule « Journal / Genesis » est cachée. Mettre vrai pour la retrouver.
     */
    private static final boolean GENESIS_ENABLED = false;
    private static final int START_ELEMENT = 92;
    /** Énergie d'une α tirée depuis l'atome, dans le laboratoire (keV) : celle d'une désintégration α typique. */
    private static final double ALPHA_LAB_KEV = 5000;
    private boolean startElementLoaded;
    private final ToggleGroup scaleGroup = new ToggleGroup();
    private final ToggleGroup dragGroup = new ToggleGroup();
    private ToggleButton autoSpin, rotateButton, panButton;
    private ToggleButton valenceOnly;
    private ToggleButton bohrView;
    /**
     * Frames tenues jusqu'à l'impact de la particule qu'elles annoncent (proton, neutron, électron) : l'atome ne change
     * d'identité qu'au moment où la particule le touche. {frame, instant de l'impact sur `clock`}.
     */
    private final java.util.ArrayDeque<Object[]> heldFrames = new java.util.ArrayDeque<>();
    private double clock;
    private Slider laserSlider;
    private Label laserLabel, laserEnergy;
    private Rectangle laserSwatch;
    private double laserNm = 656.28;

    private PeriodicTableDialog tableDialog;
    private PeriodicTable periodicTable;
    private boolean protocolMismatchReported = false;

    private int connectionGeneration = 0;
    private int reconnectAttempts = 0;
    private boolean reconnectPending = false;

    @Override
    public void start(Stage stage) {
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        // la scène tient dans l'écran AVEC la barre de titre et les bordures de la fenêtre (≈ 16 px de large, 40 px de haut)
        boolean small = screen.getWidth() < PREFERRED_WIDTH + 16 || screen.getHeight() < PREFERRED_HEIGHT + 40;
        double width = Math.min(PREFERRED_WIDTH, screen.getWidth() - 16);
        double height = Math.min(PREFERRED_HEIGHT, screen.getHeight() - 40);
        Scene scene = buildScene(width, height);

        stage.setTitle(I18n.text("Genesis - Simulateur atomique"));
        I18n.onChange(() -> stage.setTitle(I18n.text("Genesis - Simulateur atomique")));
        Branding.applyWindowIcons(stage);
        stage.setScene(scene);
        stage.setMinWidth(Math.min(1000, screen.getWidth()));
        stage.setMinHeight(Math.min(640, screen.getHeight()));
        // un écran plus petit que la taille voulue : la fenêtre s'ouvre agrandie, à la taille exacte de l'espace libre
        if (small) stage.setMaximized(true);
        // L'écran de démarrage couvre la fenêtre dès sa première image : l'accueil, construit derrière, ne se voit pas avant.
        startup.show(this::openLink, null);
        stage.show();
        if (small || stage.getWidth() > screen.getWidth() || stage.getHeight() > screen.getHeight()) {
            stage.setMaximized(true);
        } else {
            stage.setX(screen.getMinX() + (screen.getWidth() - stage.getWidth()) / 2);
            stage.setY(screen.getMinY() + (screen.getHeight() - stage.getHeight()) / 2);
        }

        if (Boolean.getBoolean("genesis.bundled")) {
            try {
                bundledEngine = BundledEngine.start();
                setStatus("Chargement du moteur Python embarqué…", "#FBBF24");
                startup.status("Chargement des données et des modèles de simulation…");
                waitForBundledEngine(bundledEngine);
            } catch (IOException error) {
                simCanvas.setStatusMessage(I18n.text("Moteur embarqué impossible à démarrer : ") + error.getMessage());
                setStatus(I18n.text("Moteur embarqué impossible à démarrer : ") + error.getMessage(), "#F87171");
                startup.fail("Démarrage impossible", error.getMessage());
            }
        } else {
            startup.status("Connexion au moteur…");
            connect();
        }

        checkForUpdates();
        window = stage;
        Rectangle2D full = Screen.getPrimary().getBounds();
        telemetry.context("screen", Math.round(full.getWidth()) + "x" + Math.round(full.getHeight()));
        telemetry.context("language", I18n.language() == I18n.Language.FR ? "fr" : "en");
        I18n.onChange(() -> telemetry.context("language", I18n.language() == I18n.Language.FR ? "fr" : "en"));
        telemetry.start();

        new AnimationTimer() {
            private long previous;
            @Override
            public void handle(long now) {
                double delta = previous == 0 ? 0 : Math.min(.1, (now - previous) / 1e9);
                previous = now;
                clock += delta;
                releaseHeldFrames();
                loader.tick(delta);
                startup.tick(delta);
                updateCard.tick(delta);
                telemetryNotice.tick(delta);
                boolean waiting = loader.isShowing() || startup.isShowing() || home.isShowing();
                telemetry.tick(waiting ? null : currentLab(), delta, window.isFocused());
                // La carte des statistiques : une fois, au premier lancement, quand l'écran de démarrage s'est effacé.
                if (!noticeOffered && !waiting && telemetry.noticeNeeded()) {
                    noticeOffered = true;
                    telemetryNotice.show(true, true, telemetry.install(), telemetry::answer);
                }
                if (pendingUpdate != null && !waiting) {
                    UpdateChecker.Release release = pendingUpdate;
                    offerUpdate(release);
                    home.setUpdate(release.version(), () -> offerUpdate(release));
                    pendingUpdate = null;
                }
                if (!waiting && shownLab != null && !guideOverlay.isShowing() && !telemetryNotice.isShowing()
                        && !guideSeen(shownLab)) {
                    showGuide();
                }
                if (chainActive) {
                    chainView.tick(delta);
                }
                if (moleculeActive) {
                    moleculeView.tick(delta);
                }
                if (!reactorActive && !subatomicActive && !nucleonActive && !chainActive && !moleculeActive) {
                    simCanvas.tick(now);
                    timeline.tick();
                    syncViewBar();
                }
                if (matterActive) {
                    matterCanvas.tick(delta);
                }
                if (fusionActive) {
                    fusionView.tick(delta);
                }
                if (reactorActive) {
                    reactorView.tick(delta);
                }
                if (subatomicActive) {
                    subatomicView.tick(delta);
                }
                if (nucleonActive) {
                    if (partonsActive) partonView.tick(delta);
                    else quantumLab.tick(delta);
                }
                if (starActive) {
                    starView.tick(delta);
                }
                if (chemistryActive) {
                    moleculeCanvas.tick(delta);
                }
                if (!reactorActive && !subatomicActive && !nucleonActive && !chainActive && !moleculeActive) {
                    lightPanel.tick(delta);
                    quantumView.tick(delta);
                    stabilityChart.tick(delta);
                }
            }
        }.start();
    }


    /** Tout l'écran, sans fenêtre ni réseau : utilisé par start() et par les aperçus. */
    Scene buildScene(double width, double height) {
        Plan.language = I18n::follow;              // les bulles et fiches de common/Plan suivent la langue
        double panelWidth = SIDEBAR_WIDTH - 24;

        simCanvas = new SimCanvas();
        matterCanvas = new MatterCanvas(fraction -> sendAmount(ActionType.SET_PISTON, fraction),
                () -> send(ActionType.MATTER_STOP));
        if (ADVANCED_SCALES) {
            simCanvas.setOnZoomOutLimit(() -> send(ActionType.MATTER_START));
        }
        fusionView = new FusionLab(() -> send(ActionType.FUSION_STOP),
                code -> send(ActionType.SET_NUCLEUS_A, code),
                code -> send(ActionType.SET_NUCLEUS_B, code),
                kev -> sendAmount(ActionType.SET_COLLISION_ENERGY, kev),
                fm -> sendAmount(ActionType.SET_IMPACT, fm),
                untilFusion -> send(ActionType.COLLIDE, untilFusion));
        fusionView.setOnThermal(on -> send(ActionType.SET_THERMAL, on));
        reactorView = new ReactorView(() -> leaveLab(ActionType.REACTOR_STOP, f -> !f.getReactor().getActive()),
                index -> send(ActionType.SET_REACTOR_FUEL, index),
                kev -> sendAmount(ActionType.SET_REACTOR_TEMPERATURE, kev),
                () -> send(ActionType.REACTOR_FIRE));
        reactorView.setOnField(tesla -> sendAmount(ActionType.SET_REACTOR_FIELD, tesla));
        reactorView.setOnForce(() -> send(ActionType.REACTOR_FIRE, 1));
        reactorView.setOnThermal(on -> send(ActionType.SET_REACTOR_THERMAL, on));
        reactorView.setOnDensity(perM3 -> sendAmount(ActionType.SET_REACTOR_DENSITY, perM3));
        reactorView.setOnConfinement(seconds -> sendAmount(ActionType.SET_REACTOR_CONFINEMENT, seconds));
        reactorView.setOnNucleusA(code -> send(ActionType.SET_REACTOR_NUCLEUS_A, code));
        reactorView.setOnNucleusB(code -> send(ActionType.SET_REACTOR_NUCLEUS_B, code));
        reactorView.setOnCollisionEnergy(kev -> sendAmount(ActionType.SET_REACTOR_ENERGY, kev));
        reactorView.setOnImpact(fm -> sendAmount(ActionType.SET_REACTOR_IMPACT, fm));
        chainView = new ChainView(new ChainView.Actions() {
            @Override public void back() { leaveLab(ActionType.CHAIN_STOP, f -> !f.getChain().getActive()); }
            @Override public void core(int index) { send(ActionType.CHAIN_SET_CORE, index); }
            @Override public void radius(double cm) { sendAmount(ActionType.CHAIN_SET_RADIUS, cm); }
            @Override public void reflector(int index, double cm) { sendBoth(ActionType.CHAIN_SET_REFLECTOR, index, cm); }
            @Override public void compression(double factor) { sendAmount(ActionType.CHAIN_SET_COMPRESSION, factor); }
            @Override public void geometry(double cm, double compression) {
                sendAmount(ActionType.CHAIN_SET_COMPRESSION, compression);
                sendAmount(ActionType.CHAIN_SET_RADIUS, cm);
            }
            @Override public void fire(double[] position, int count) { fireChain(count, position); }
            @Override public void mode(int mode) { send(ActionType.CHAIN_SET_MODE, mode); }
            @Override public void nuclide(int code) { send(ActionType.CHAIN_SET_NUCLIDE, code); }
            @Override public void critical() { send(ActionType.CHAIN_CRITICAL); }
            @Override public void pickElement() { openChainTable(); }
        });
        moleculeView = new MoleculeView(new MoleculeView.Actions() {
            @Override public void back() { leaveLab(ActionType.MOLECULE_STOP, f -> !f.getMoleculeLab().getActive()); }
            @Override public void preset(int index) { send(ActionType.MOLECULE_PRESET, index); }
            @Override public void reaction(int index) { send(ActionType.MOLECULE_REACTION, index); }
            @Override public void addAtom(int z, int anchor, double[] position) {
                sendVector(ActionType.MOLECULE_ADD_ATOM, z, anchor, position);
            }
            @Override public void removeAtom(int index) { send(ActionType.MOLECULE_REMOVE_ATOM, index); }
            @Override public void drag(int index, double[] target) { sendVector(ActionType.MOLECULE_DRAG, index, 0, target); }
            @Override public void release() { sendVector(ActionType.MOLECULE_DRAG, -1, 0, null); }
            @Override public void dynamics(int mode, double kelvin) { sendBoth(ActionType.MOLECULE_DYNAMICS, mode, kelvin); }
            @Override public void spark(int index, double ev) { sendBoth(ActionType.MOLECULE_SPARK, index, ev); }
            @Override public void view(int kind, int orbital, boolean potential) {
                sendVector(ActionType.MOLECULE_VIEW, kind, orbital, new double[]{potential ? 1 : 0});
            }
            @Override public void modes() { send(ActionType.MOLECULE_MODES); }
            @Override public void isotope(int target, int massNumber) {
                sendBoth(ActionType.MOLECULE_ISOTOPE, target, massNumber);
            }
            @Override public void excite(int mode, double quanta) { sendBoth(ActionType.MOLECULE_EXCITE, mode, quanta); }
            @Override public void charge(int charge) { send(ActionType.MOLECULE_CHARGE, charge); }
            @Override public void pause(boolean paused) { send(ActionType.MOLECULE_PAUSE, paused ? 1 : 0); }
            @Override public void clear() { send(ActionType.MOLECULE_CLEAR); }
            @Override public void pickElement() { openMoleculeTable(); }
            @Override public void build(double[] vector, int charge, boolean enclosed) {
                sendVector(ActionType.MOLECULE_BUILD, charge, enclosed ? 1 : 0, vector);
            }
            @Override public void assess(int request, double[] vector, boolean deep) {
                sendVector(ActionType.MOLECULE_ASSESS, request, deep ? 1 : 0, vector);
            }
            @Override public void react(int request, double[] vector, double temperature) {
                sendVector(ActionType.MOLECULE_REACT, request, temperature, vector);
            }
            @Override public void pressure(double atm) { sendBoth(ActionType.MOLECULE_PRESSURE, 0, atm); }
            @Override public void scheme(int request, String json) { sendText(ActionType.MOLECULE_SCHEME, request, json); }
            @Override public void schemePlay(int step, double position, boolean running) {
                sendVector(ActionType.MOLECULE_SCHEME_PLAY, step, position, new double[]{running ? 1 : 0});
            }
            @Override public void schemePlay(int step, double position, boolean running, double speed) {
                sendVector(ActionType.MOLECULE_SCHEME_PLAY, step, position, new double[]{running ? 1 : 0, speed});
            }
            @Override public void smiles(int request, String text) { sendText(ActionType.MOLECULE_SMILES, request, text); }
            @Override public void solvent(int index) { send(ActionType.MOLECULE_SOLVENT, index); }
            @Override public void experiment(int index) { send(ActionType.MOLECULE_EXPERIMENT, index); }
        });
        subatomicView = new SubatomicView(() -> leaveLab(ActionType.SUBATOMIC_STOP, f -> !f.getSubatomic().getActive()),
                () -> send(ActionType.SUBATOMIC_RESET),
                value -> send(ActionType.SUBATOMIC_SET_PROTONS, value),
                value -> send(ActionType.SUBATOMIC_SET_NEUTRONS, value),
                value -> send(ActionType.SUBATOMIC_SET_ELECTRONS, value),
                value -> sendAmount(ActionType.SUBATOMIC_SET_IMPULSE, value),
                this::sendSubatomicKick);
        quantumLab = new QuantumLab(new QuantumLab.Actions() {
            @Override public void back() { leaveLab(ActionType.NUCLEON_STOP, f -> !f.getNucleon().getActive()); }
            @Override public void collide(int target, double energyMev, double angleDeg, int projectile) {
                sendCollision(target, energyMev, angleDeg, projectile);
            }
            @Override public void resample() { send(ActionType.COLLISION_RESAMPLE); }
            @Override public void force(int channel) { send(ActionType.COLLISION_FORCE, channel); }
            @Override public void test(int spinIndex, double[] triples) { sendHadron(spinIndex, triples); }
            @Override public void decay() { send(ActionType.HADRON_DECAY); }
            @Override public void clear() { send(ActionType.HADRON_CLEAR); }
            @Override public void partons(boolean open) {
                if (open) sendPartons(true, 0, 1e-3);
                else send(ActionType.PARTONS_STOP);
            }
        });
        partonView = new PartonView(new PartonView.Actions() {
            @Override public void back() { send(ActionType.PARTONS_STOP); }
            @Override public void resolution(double q2, double xMin) { sendPartons(false, q2, xMin); }
            @Override public void lastStrike(double xMin) { sendPartons(true, 0, xMin); }
            @Override public void reset() { send(ActionType.PARTONS_RESET); }
        });
        quantumLab.attachPartons(partonView.getNode());
        starView = new StarView(() -> send(ActionType.STAR_STOP),
                index -> send(ActionType.SET_STAR_PRESET, index),
                kelvin -> sendAmount(ActionType.SET_STAR_TEMPERATURE, kelvin),
                rho -> sendAmount(ActionType.SET_STAR_DENSITY, rho),
                index -> send(ActionType.SET_STAR_SPEED, index),
                () -> send(ActionType.TOGGLE_PAUSE));
        moleculeCanvas = new MoleculeCanvas(r -> sendAmount(ActionType.SET_BOND_LENGTH, r),
                () -> send(ActionType.RELEASE_BOND));
        lightPanel = new LightPanel(panelWidth, 330, this::tuneLaser);
        quantumView = new QuantumView(panelWidth, 360);
        stabilityChart = new StabilityChart(panelWidth, 230);
        nucleusPanel = new NucleusPanel(stabilityChart);
        identity = new IdentityStrip();
        journal = new EventJournal();
        timeline = new TimelineBar(new TimelineBar.Actions() {
            @Override public void togglePause() { send(ActionType.TOGGLE_PAUSE); }
            @Override public void next() { send(ActionType.NEXT_EVENT); }
            @Override public void speed(int index) { send(ActionType.SET_SPEED, index); }
            @Override public void stepMode(boolean on) { send(ActionType.SET_STEP_MODE, on ? 1 : 0); }
        });
        genesis = new GenesisMascot(190, 232);

        // Deux écrans superposés : le simulateur atomique et la chambre de collision.
        mainScreen = buildRoot();
        fusionView.getNode().setVisible(false);
        starView.getNode().setVisible(false);
        reactorView.getNode().setVisible(false);
        chainView.getNode().setVisible(false);
        moleculeView.getNode().setVisible(false);
        subatomicView.getNode().setVisible(false);
        quantumLab.getNode().setVisible(false);
        mainScreen.getStyleClass().add(LabBar.Lab.ATOM.style);
        // L'accueil d'abord : on choisit un laboratoire avant d'y entrer (l'atome se prépare derrière).
        home = new HomeScreen(this::openFromHome, this::openLink);
        home.setVersion(UpdateChecker.currentVersion());
        StackPane laboratories = new StackPane(mainScreen, fusionView.getNode(), starView.getNode(),
                reactorView.getNode(), chainView.getNode(), moleculeView.getNode(), subatomicView.getNode(), quantumLab.getNode(),
                home.getNode(), loader.getNode(),
                updateCard.getNode(), telemetryNotice.getNode());
        // Un laboratoire caché ne compte pas dans la place : sinon le plus large d'entre eux imposerait sa largeur minimale
        // à tous, et l'écran déborderait d'une petite fenêtre.
        for (javafx.scene.Node lab : new javafx.scene.Node[]{mainScreen, fusionView.getNode(), starView.getNode(), reactorView.getNode(),
                chainView.getNode(), moleculeView.getNode(), subatomicView.getNode(), quantumLab.getNode(), home.getNode()}) {
            lab.managedProperty().bind(lab.visibleProperty());
        }
        BorderPane localizedShell = new BorderPane(laboratories);
        labBar = new LabBar(this::showHome, this::showGuide, this::capture, this::togglePanels, this::toggleFullscreen,
                settingsMenu());
        localizedShell.setTop(labBar.getNode());
        syncLab();
        Plan.load();
        // Le message bref et le guide couvrent toute la fenêtre, barre du haut comprise (le guide y nomme les modes).
        Scene scene = new Scene(new StackPane(localizedShell, toast.getNode(), guideOverlay.getNode(), startup.getNode()),
                width, height);
        scene.setFill(Plan.DEEP);
        scene.getStylesheets().add(stylesheet());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);

        tuneLaser(laserNm);
        I18n.attach(scene);
        return scene;
    }

    /**
     * Les réglages, derrière une icône : la langue, les statistiques d'utilisation (et ce qu'elles contiennent ; absentes
     * d'une version de développement, qui n'envoie rien), le guide et l'accueil.
     */
    private MenuButton settingsMenu() {
        MenuButton settings = new MenuButton("", Icons.path("settings", 16));
        settings.getStyleClass().setAll("menu-button", "icon-button", "only", "plan-settings");
        settings.setFocusTraversable(false);
        settings.setTooltip(Plan.tooltip("Réglages : langue, statistiques d'utilisation"));
        javafx.scene.control.ToggleGroup languages = new javafx.scene.control.ToggleGroup();
        javafx.scene.control.RadioMenuItem french = new javafx.scene.control.RadioMenuItem("Français");
        javafx.scene.control.RadioMenuItem english = new javafx.scene.control.RadioMenuItem("English");
        french.setToggleGroup(languages);
        english.setToggleGroup(languages);
        french.setOnAction(event -> I18n.setLanguage(I18n.Language.FR));
        english.setOnAction(event -> I18n.setLanguage(I18n.Language.EN));
        Runnable refresh = () -> (I18n.language() == I18n.Language.FR ? french : english).setSelected(true);
        I18n.onChange(refresh);
        refresh.run();
        javafx.scene.control.CheckMenuItem send = new javafx.scene.control.CheckMenuItem("Envoyer les statistiques d'utilisation");
        MenuItem what = new MenuItem("Quelles données sont envoyées ?");
        send.setOnAction(event -> telemetry.setEnabled(send.isSelected()));
        what.setOnAction(event -> telemetryNotice.show(false, telemetry.enabled(), telemetry.install(), telemetry::setEnabled));
        MenuItem guideItem = new MenuItem("Guide de ce laboratoire     H");
        guideItem.setOnAction(event -> showGuide());
        MenuItem homeItem = new MenuItem("Accueil et liens de Genesis");
        homeItem.setOnAction(event -> showHome());
        settings.setOnShowing(event -> {
            send.setSelected(telemetry.enabled());
            guideItem.setDisable(home.isShowing());
        });
        settings.getItems().addAll(french, english, new SeparatorMenuItem());
        if (telemetry.available()) settings.getItems().addAll(send, what, new SeparatorMenuItem());
        settings.getItems().addAll(guideItem, homeItem);
        return settings;
    }

    /** Le laboratoire montré, pour le décompte du temps passé dans chacun. */
    private String currentLab() {
        if (moleculeActive) return "molecule";
        if (chainActive) return "chain";
        if (fusionActive) return "fusion";
        if (reactorActive) return "reactor";
        if (starActive) return "star";
        if (subatomicActive) return "subatomic";
        if (nucleonActive) return partonsActive ? "partons" : "nucleon";
        if (matterActive) return "matter";
        if (chemistryActive) return "chemistry";
        return "atom";
    }

    /** Pour les aperçus : une frame du moteur, comme si elle arrivait du réseau. */
    void receive(UniverseFrame frame) {
        onFrame(frame);
    }

    /** Pour les aperçus : une image de l'écran de l'atome. */
    /** Pour les aperçus : choisir la vue du journal, sans toucher à la préférence enregistrée. */
    void previewPanel(String shell) {
        simCanvas.previewPanel(shell);
    }

    void previewJournal(boolean withGenesis) {
        genesisShown = withGenesis;
        guide.setVisible(withGenesis);
        guide.setManaged(withGenesis);
        journal.setCompact(withGenesis);
        genesis.setManual(true);
        SimEvent latest = journal.latest();
        if (withGenesis && latest != null) {
            genesis.explainNow(latest);
        }
    }

    /** Pour les aperçus : l'écran d'attente d'un laboratoire. */
    void previewLoader() {
        loader.show("Démarrage du moteur Python",
                "Chargement des données et des modèles de simulation. La connexion s'ouvrira automatiquement.", f -> false, null);
    }

    /** Pour les aperçus : la carte des statistiques, au premier lancement (firstRun) ou relue depuis ⚙. */
    void previewTelemetryNotice(boolean firstRun) {
        startup.onFrame(null);
        telemetryNotice.show(firstRun, true, "022c26a8-7902-41a2-86c2-92169126fbee", yes -> { });
    }

    /** Pour les aperçus : l'écran de démarrage, en attente du moteur ou (failed) en échec. */
    void previewStartup(boolean failed) {
        if (!startup.isShowing()) startup.show(url -> { }, null);
        startup.status("Chargement des données et des modèles de simulation…");
        if (failed) startup.fail("Démarrage impossible", I18n.text("le moteur Python n'a pas répondu en 180 s."));
    }

    /** Pour les aperçus : valence seule dans la vue 3D, phase de ψ dans le panneau Orbitale. */
    void previewOrbitalModes(boolean valence, boolean phase) {
        valenceOnly.setSelected(valence);
        simCanvas.setValenceOnly(valence);
        quantumView.setPhaseMode(phase);
    }

    /** Pour les aperçus : la vue de Bohr. */
    void previewBohr(boolean on) {
        bohrView.setSelected(on);
        simCanvas.setBohrMode(on);
    }

    /** Pour les aperçus : la carte de mise à jour, avec une version fictive. */
    void previewUpdate() {
        loader.hide();
        startup.onFrame(null);
        updateCard.offer("0.2.0", "0.3.0", true, new UpdateCard.Actions() {
            @Override public void install() { }
            @Override public void openPage() { }
            @Override public void skip() { }
        });
    }

    /** Ouvre une adresse (page web ou mailto:) avec le navigateur ou le client mail du système. */
    private void openLink(String url) {
        try {
            getHostServices().showDocument(url);
        } catch (RuntimeException error) {
            notice(I18n.text("Impossible d'ouvrir ") + url);
        }
    }

    /**
     * Au démarrage, en arrière-plan : la dernière version publiée sur GitHub. Rien depuis les sources (pas de numéro de
     * version), rien hors ligne, rien pour une version que l'utilisateur a choisi d'ignorer.
     */
    private void checkForUpdates() {
        String current = UpdateChecker.currentVersion();
        if (current == null) {
            return;
        }
        String skipped = java.util.prefs.Preferences.userNodeForPackage(App.class).get(SKIPPED_VERSION_PREF, "");
        UpdateChecker.latest(UpdateChecker.client(), current).thenAccept(found -> found
                .filter(release -> UpdateChecker.compare(release.version(), current) > 0)
                .filter(release -> !release.version().equals(skipped))
                .ifPresent(release -> Platform.runLater(() -> pendingUpdate = release)));
    }

    private void offerUpdate(UpdateChecker.Release release) {
        String current = UpdateChecker.currentVersion();
        boolean canInstall = Boolean.getBoolean("genesis.bundled") && release.installer().isPresent();
        updateCard.offer(current, release.version(), canInstall, new UpdateCard.Actions() {
            @Override public void install() { installUpdate(release); }
            @Override public void openPage() { openLink(release.page()); }
            @Override public void skip() {
                java.util.prefs.Preferences.userNodeForPackage(App.class).put(SKIPPED_VERSION_PREF, release.version());
            }
        });
    }

    /** Télécharge l'installateur, vérifie son empreinte, le lance, puis ferme Genesis pour qu'il puisse remplacer les fichiers. */
    private void installUpdate(UpdateChecker.Release release) {
        UpdateChecker.Asset installer = release.installer().orElse(null);
        if (installer == null) {
            openLink(release.page());
            return;
        }
        updateCard.downloading(0);
        Thread worker = new Thread(() -> {
            try {
                java.nio.file.Path folder = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "Genesis-update");
                long[] shown = {0};
                java.nio.file.Path file = UpdateChecker.download(UpdateChecker.client(), release, installer, folder, fraction -> {
                    long percent = Math.round(fraction * 100);
                    if (percent != shown[0]) {
                        shown[0] = percent;
                        Platform.runLater(() -> updateCard.downloading(fraction));
                    }
                });
                Platform.runLater(() -> {
                    updateCard.status("Empreinte vérifiée. Lancement de l'installateur…");
                    try {
                        new ProcessBuilder(file.toString()).start();
                        Platform.exit();
                    } catch (IOException error) {
                        updateCard.failed(error.getMessage());
                    }
                });
            } catch (IOException error) {
                Platform.runLater(() -> updateCard.failed(error.getMessage()));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, "genesis-update");
        worker.setDaemon(true);
        worker.start();
    }

    void tickForPreview(long now) {
        clock += 1 / 60.0;
        releaseHeldFrames();
        loader.tick(1 / 60.0);
        startup.tick(1 / 60.0);
        updateCard.tick(1 / 60.0);
        telemetryNotice.tick(1 / 60.0);
        if (genesisShown) {
            genesis.tick(1 / 60.0);
        }
        lightPanel.tick(1 / 60.0);
        quantumView.tick(1 / 60.0);
        stabilityChart.tick(1 / 60.0);
        simCanvas.tick(now);
        timeline.tick();
        syncViewBar();
    }

    // ------------------------------------------------------------------
    // Disposition
    // ------------------------------------------------------------------
    /**
     * L'écran de l'atome : une barre en haut (l'élément, ce qu'on construit,
     * la lumière, les laboratoires), la scène avec sa barre de vue, la
     * timeline en bas, et à droite les onglets et le journal.
     */
    private Region buildRoot() {
        Pane canvasHolder = new Pane(simCanvas.getNode(), simCanvas.getOverlay(), matterCanvas.getNode(), moleculeCanvas.getNode());
        canvasHolder.getStyleClass().add("plan-scene");
        simCanvas.getNode().widthProperty().bind(canvasHolder.widthProperty());
        simCanvas.getNode().heightProperty().bind(canvasHolder.heightProperty());
        simCanvas.getOverlay().prefWidthProperty().bind(canvasHolder.widthProperty());
        simCanvas.getOverlay().prefHeightProperty().bind(canvasHolder.heightProperty());
        simCanvas.getOverlay().visibleProperty().bind(simCanvas.getNode().visibleProperty());
        matterCanvas.getNode().widthProperty().bind(canvasHolder.widthProperty());
        matterCanvas.getNode().heightProperty().bind(canvasHolder.heightProperty());
        matterCanvas.getNode().setVisible(false);
        moleculeCanvas.getNode().widthProperty().bind(canvasHolder.widthProperty());
        moleculeCanvas.getNode().heightProperty().bind(canvasHolder.heightProperty());
        moleculeCanvas.getNode().setVisible(false);

        Region viewBar = buildViewBar();
        StackPane canvasStack = new StackPane(canvasHolder, viewBar);
        StackPane.setAlignment(viewBar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(viewBar, new Insets(0, 0, 12, 0));

        BorderPane simulation = new BorderPane();
        simulation.setCenter(canvasStack);
        simulation.setBottom(timeline.getNode());
        atomScene = canvasStack;

        BorderPane root = new BorderPane();
        atomLeft = buildAtomControls();
        atomRight = buildSidebar();
        root.setLeft(atomLeft);
        root.setCenter(simulation);
        root.setRight(atomRight);
        return root;
    }

    /**
     * La colonne de gauche de l'atome : ce qu'on prépare. En tête, l'élément (sa case, son nom, son isotope ; un clic ouvre
     * la table) ; puis ce qui le compose, mot à mot ; la lumière, sur une piste aux couleurs du spectre (vives dans le
     * visible, éteintes dans l'ultraviolet et l'infrarouge), et le photon à envoyer ; les autres projectiles.
     */
    private Region buildAtomControls() {
        elementSymbol.setMinSize(50, 50);
        elementSymbol.setMaxSize(50, 50);
        elementSymbol.setAlignment(Pos.CENTER);
        elementSymbol.setStyle(symbolStyle(null));
        elementName.getStyleClass().add("element-name");
        elementNuclide.getStyleClass().add("element-meta");
        javafx.scene.Node chevron = Icons.path("chevron-right", 14);
        Region chipGrow = new Region();
        HBox.setHgrow(chipGrow, Priority.ALWAYS);
        VBox words = new VBox(3, elementName, elementNuclide);
        words.setAlignment(Pos.CENTER_LEFT);
        HBox chipContent = new HBox(12, elementSymbol, words, chipGrow, chevron);
        chipContent.setAlignment(Pos.CENTER_LEFT);
        tableButton = new Button();
        tableButton.setGraphic(chipContent);
        tableButton.getStyleClass().addAll("dash-chip", "element-card");
        tableButton.setFocusTraversable(false);
        tableButton.setMaxWidth(Double.MAX_VALUE);
        tableButton.setAlignment(Pos.CENTER_LEFT);
        tableButton.setTooltip(Plan.tooltip("Changer d'élément : la table périodique · T"));
        tableButton.setOnAction(event -> openPeriodicTable());

        VBox build = new VBox(6,
                stepper("Protons", "p⁺", protonCount, ActionType.ADD_PROTON, ActionType.REMOVE_PROTON,
                        "Les protons font l'élément · P, Maj+P"),
                stepper("Neutrons", "n", neutronCount, ActionType.ADD_NEUTRON, ActionType.REMOVE_NEUTRON,
                        "Les neutrons font l'isotope · N, Maj+N"),
                stepper("Électrons", "e⁻", electronCount, ActionType.ADD_ELECTRON, ActionType.REMOVE_ELECTRON,
                        "Les électrons font l'ion · E, Maj+E"));

        // La lumière : la longueur d'onde du laser, sur une piste aux couleurs du spectre.
        laserSwatch = new Rectangle(10, 10);
        laserSwatch.setArcWidth(10);
        laserSwatch.setArcHeight(10);
        laserLabel = new Label();
        laserLabel.getStyleClass().add("plan-value");
        laserEnergy = new Label();
        laserEnergy.getStyleClass().add("plan-label");
        laserSlider = new Slider(Math.log10(LightPanel.MIN_NM), Math.log10(LightPanel.MAX_NM), Math.log10(laserNm));
        laserSlider.setMaxWidth(Double.MAX_VALUE);
        laserSlider.setFocusTraversable(false);
        laserSlider.getStyleClass().add("spectrum");
        laserSlider.setTooltip(Plan.tooltip("La longueur d'onde du laser. Dans le spectre, à droite, un clic sur une raie l'accorde exactement."));
        laserSlider.valueProperty().addListener((obs, old, value) -> {
            if (!updatingLaser) {
                tuneLaser(Math.pow(10, value.doubleValue()));
            }
        });
        paintSpectrum(laserSlider);
        Region readoutGrow = new Region();
        HBox.setHgrow(readoutGrow, Priority.ALWAYS);
        HBox laser = new HBox(8, laserSwatch, laserLabel, readoutGrow, laserEnergy);
        laser.setAlignment(Pos.CENTER_LEFT);
        Button fire = Plan.primary(I18n.text("Envoyer un photon"));
        fire.setGraphic(Icons.path("arrow-right", 14));
        fire.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        fire.setTooltip(Plan.tooltip("Un photon de cette longueur d'onde vers l'atome. S'il a l'énergie d'une raie, un électron "
                + "change d'orbitale · L"));
        fire.setOnAction(event -> firePhoton());
        Button flame = Plan.button(I18n.text("Chauffer dans une flamme"));
        flame.setMaxWidth(Double.MAX_VALUE);
        flame.setTooltip(Plan.tooltip("Des chocs avec un gaz chaud excitent l'atome, comme dans une flamme ; il se désexcite "
                + "en émettant ses raies · F"));
        flame.setOnAction(event -> send(ActionType.HEAT));

        // Les autres projectiles : un neutron lent, une α (que le laboratoire de fusion calcule).
        Button neutronShot = Plan.button(I18n.text("Neutron lent"));
        neutronShot.setMaxWidth(Double.MAX_VALUE);
        neutronShot.setTooltip(Plan.tooltip("Un neutron thermique (0,0253 eV) vers le noyau : capture, ou fission d'un noyau lourd · B"));
        neutronShot.setOnAction(event -> fireNeutron());
        Button alphaShot = Plan.button(I18n.text("Particule α"));
        alphaShot.setMaxWidth(Double.MAX_VALUE);
        alphaShot.setTooltip(Plan.tooltip("Une particule α de 5 MeV vers le noyau : le laboratoire de fusion calcule la collision"));
        alphaShot.setOnAction(event -> fireAlpha());
        HBox.setHgrow(neutronShot, Priority.ALWAYS);
        HBox.setHgrow(alphaShot, Priority.ALWAYS);
        HBox shots = new HBox(6, neutronShot, alphaShot);

        Region gap = new Region();
        gap.setMinHeight(18);
        VBox column = new VBox(0,
                tableButton, gap,
                Plan.section("Composition", build),
                Plan.section("Lumière", laser, laserSlider, fire, flame),
                Plan.section("Autres projectiles", shots));
        column.setPadding(new Insets(16, 16, 14, 16));
        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().addAll("plan-side", "left");
        scroll.setPrefWidth(Plan.LEFT_WIDTH);
        scroll.setMinWidth(Plan.LEFT_WIDTH);
        scroll.setMaxWidth(Plan.LEFT_WIDTH);
        return scroll;
    }

    /**
     * La piste du curseur de la lumière, aux couleurs des longueurs d'onde qu'elle parcourt (échelle logarithmique, de 50 à
     * 5 000 nm) : vives dans le visible, éteintes au-delà (l'ultraviolet et l'infrarouge ne se voient pas).
     */
    private static void paintSpectrum(Slider slider) {
        StringBuilder stops = new StringBuilder();
        int n = 36;
        double low = Math.log10(LightPanel.MIN_NM), high = Math.log10(LightPanel.MAX_NM);
        for (int k = 0; k <= n; k++) {
            double nm = Math.pow(10, low + (high - low) * k / n);
            Color c = PhotonPacket.colour(nm);
            boolean visible = nm >= 380 && nm <= 780;
            if (!visible) c = c.deriveColor(0, .55, .55, 1);
            if (k > 0) stops.append(", ");
            stops.append(String.format(java.util.Locale.ROOT, "%s %.1f%%", Plan.css(c), 100.0 * k / n));
        }
        String style = "-fx-background-color: linear-gradient(to right, " + stops + ");";
        Runnable paint = () -> {
            javafx.scene.Node track = slider.lookup(".track");
            if (track != null) track.setStyle(style);
        };
        slider.skinProperty().addListener((o, a, b) -> Platform.runLater(paint));
        slider.sceneProperty().addListener((o, a, b) -> Platform.runLater(paint));
    }

    /** La case de l'élément : son symbole dans sa couleur, sur un fond de la même couleur, très léger. */
    private static String symbolStyle(String color) {
        String ink = color == null || color.isEmpty() ? Plan.INK2_HEX : color;
        String hex = ink.length() == 7 ? ink : Plan.INK2_HEX;
        return "-fx-background-color: " + hex + "22; -fx-background-radius: 11; -fx-text-fill: " + hex
                + "; -fx-font-size: 21px; -fx-font-family: 'IBM Plex Sans SmBld';";
    }

    /** Un compteur sur une ligne : son nom (et son symbole, discret), puis − valeur +. */
    private HBox stepper(String name, String symbol, Label value, ActionType add, ActionType remove, String hint) {
        Label caption = new Label(name);
        caption.getStyleClass().add("stepper-name");
        Label mark = new Label(symbol);
        mark.getStyleClass().add("stepper-symbol");
        value.getStyleClass().add("stepper-value");
        value.setMinWidth(40);
        value.setAlignment(Pos.CENTER);
        Button minus = new Button("", Icons.path("minus", 12));
        Button plus = new Button("", Icons.path("plus", 12));
        for (Button button : new Button[]{minus, plus}) {
            button.getStyleClass().setAll("button", "step-button");
            button.setFocusTraversable(false);
        }
        minus.setOnAction(event -> send(remove));
        plus.setOnAction(event -> send(add));
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox box = new HBox(6, caption, mark, grow, minus, value, plus);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("stepper");
        Tooltip.install(box, Plan.tooltip(hint));
        return box;
    }

    /**
     * Le laboratoire qu'on regarde, d'après les frames du moteur. Les écrans mis de côté (étoile, réaction en chaîne,
     * ancien laboratoire de fusion) se rangent sous l'onglet le plus proche.
     */
    private LabBar.Lab visibleLab() {
        if (reactorActive || fusionActive || starActive || chainActive) return LabBar.Lab.FUSION;
        if (moleculeActive) return LabBar.Lab.MOLECULE;
        if (nucleonActive || subatomicActive) return LabBar.Lab.SUBATOMIC;
        return LabBar.Lab.ATOM;
    }

    /** La barre suit le laboratoire montré : son nom, ses modes, sa méthode (rien sur l'accueil). */
    private void syncLab() {
        if (home.isShowing()) return;
        LabBar.Lab lab = opening != null && loader.isShowing() ? opening : visibleLab();
        if (lab != shownLab) configureBar(lab);
    }

    /** La barre d'un laboratoire : son nom, ses modes, sa méthode. */
    private void configureBar(LabBar.Lab lab) {
        shownLab = lab;
        switch (lab) {
            case ATOM -> labBar.showLab(lab, atomModes, null);
            case FUSION -> labBar.showLab(lab, reactorView.modes(), reactorView::showModel);
            case MOLECULE -> labBar.showLab(lab, moleculeView.viewModes(), moleculeView::showModel);
            case SUBATOMIC -> labBar.showLab(lab, quantumLab.modes(), null);
        }
    }

    /** L'accueil : le laboratoire ouvert reste derrière (sa carte le dit), la barre ne garde que la marque. */
    private void showHome() {
        opening = null;
        guideOverlay.hide();
        home.setCurrent(labOpened ? visibleLab() : null);
        home.show();
        shownLab = null;
        labBar.showHome();
    }

    /** Une carte de l'accueil : on quitte l'accueil pour ce laboratoire (en refermant celui qui était ouvert). */
    private void openFromHome(LabBar.Lab target) {
        labOpened = true;
        switchLab(target);            // l'écran d'attente se pose d'abord, sur l'image de l'accueil…
        home.hide();                  // … puis l'accueil s'en va dessous : on ne voit jamais l'ancien laboratoire
        configureBar(target);         // la barre annonce tout de suite le laboratoire demandé, pendant que le moteur l'ouvre
        applyPanels();
    }

    // ------------------------------------------------------------------ les outils communs : guide, image, panneaux

    /** Le guide du laboratoire montré (rien sur l'accueil). */
    private void showGuide() {
        if (home.isShowing() || startup.isShowing()) return;
        LabBar.Lab lab = visibleLab();
        Guide page = switch (lab) {
            case ATOM -> atomGuide();
            case FUSION -> reactorView.guide();
            case MOLECULE -> moleculeView.guide();
            case SUBATOMIC -> quantumLab.guide();
        };
        if (!PREVIEW) java.util.prefs.Preferences.userNodeForPackage(App.class).putBoolean("guide.seen." + lab.name(), true);
        guideOverlay.show(page, lab.style);
    }

    /** Le guide est-il déjà passé pour ce laboratoire (il s'ouvre seul la première fois) ? */
    private boolean guideSeen(LabBar.Lab lab) {
        return PREVIEW || java.util.prefs.Preferences.userNodeForPackage(App.class).getBoolean("guide.seen." + lab.name(), false);
    }

    private Guide atomGuide() {
        return new Guide("Atome", "Un atome au choix : son nuage d'électrons, la lumière qu'il absorbe et émet, son noyau "
                + "qui se désintègre avec le temps.")
                .step("Choisir un élément (la table périodique, touche T) ou le construire proton par proton.")
                .step("Envoyer un photon : s'il a l'énergie d'une raie, un électron change d'orbitale.")
                .step("Accélérer le temps, en bas : un noyau radioactif se transforme, étape par étape.")
                .spot(atomModes, "Les échelles", "L'atome, son noyau, un nucléon ; le gouffre montre les deux ensemble.")
                .spot(atomLeft, "Préparer", "L'élément, ce qui le compose, la lumière et les projectiles.")
                .spot(atomScene, "La scène", "Glisser pour tourner, molette pour zoomer, double-clic pour centrer.")
                .spot(atomRight, "Mesurer", "Le spectre, l'orbitale de l'électron, le noyau ; et le journal de ce qui arrive.")
                .spot(timeline.getNode(), "Le temps", "Sa vitesse, la chaîne des désintégrations ; une station : y aller.")
                .key("T", "table périodique").key("L", "envoyer un photon")
                .key("P N E", "un proton, neutron, électron de plus").key("Maj", "… de moins")
                .key("Espace", "pause").key("→", "événement suivant")
                .key("1 2 3", "échelles").key("G", "le gouffre");
    }

    /** Montre ou cache les panneaux (gauche, droite) du laboratoire montré : la scène prend toute la place. */
    private void togglePanels() {
        panelsShown = !panelsShown;
        applyPanels();
    }

    private void applyPanels() {
        if (labBar != null) labBar.panelsShown(panelsShown);
        if (atomLeft != null) {
            Plan.show(atomLeft, panelsShown);
            Plan.show(atomRight, panelsShown);
        }
        reactorView.showPanels(panelsShown);
        moleculeView.showPanels(panelsShown);
        quantumLab.showPanels(panelsShown);
    }

    private void toggleFullscreen() {
        if (window == null) return;
        window.setFullScreenExitHint("");
        window.setFullScreen(!window.isFullScreen());
    }

    /**
     * Enregistre une image de la scène du laboratoire montré (sans ses panneaux) dans Images/Genesis, et le dit, avec de
     * quoi ouvrir le dossier.
     */
    private void capture() {
        if (home.isShowing()) return;
        LabBar.Lab lab = visibleLab();
        javafx.scene.Node scene = switch (lab) {
            case ATOM -> atomScene;
            case FUSION -> reactorView.sceneNode();
            case MOLECULE -> moleculeView.sceneNode();
            case SUBATOMIC -> quantumLab.sceneNode();
        };
        if (scene == null) return;
        try {
            javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
            parameters.setFill(Plan.SCENE);
            javafx.scene.image.WritableImage image = scene.snapshot(parameters, null);
            java.nio.file.Path folder = picturesFolder();
            java.nio.file.Files.createDirectories(folder);
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"));
            java.nio.file.Path file = folder.resolve("Genesis " + I18n.text(lab.title).toLowerCase(I18n.locale()) + " " + stamp + ".png");
            int w = (int) image.getWidth(), h = (int) image.getHeight();
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] pixels = new int[w * h];
            image.getPixelReader().getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
            out.setRGB(0, 0, w, h, pixels, 0, w);
            javax.imageio.ImageIO.write(out, "png", file.toFile());
            toast.show(I18n.text("Image enregistrée") + " : " + file.getFileName(), "Ouvrir le dossier",
                    () -> openLink(folder.toUri().toString()));
        } catch (IOException | RuntimeException error) {
            notice(I18n.text("Image impossible à enregistrer : ") + error.getMessage());
        }
    }

    /** Le dossier des images : Images/Genesis (Pictures/Genesis), sinon le dossier personnel. */
    private static java.nio.file.Path picturesFolder() {
        java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("user.home"));
        for (String name : new String[]{"Pictures", "Images"}) {
            java.nio.file.Path p = home.resolve(name);
            if (java.nio.file.Files.isDirectory(p)) return p.resolve("Genesis");
        }
        return home.resolve("Genesis");
    }

    /** Pour les aperçus : l'accueil refermé, l'atome montré. */
    void previewWithoutHome() {
        home.hide();
        syncLab();
    }

    /** Pour les aperçus : l'accueil (sans son fondu, que l'aperçu n'attend pas). */
    void previewHome() {
        showHome();
        home.getNode().setOpacity(1);
    }

    /** Pour les aperçus : le guide du laboratoire montré (sans son fondu, sans retenir qu'il a été vu). */
    void previewGuide() {
        LabBar.Lab lab = visibleLab();
        Guide page = switch (lab) {
            case ATOM -> atomGuide();
            case FUSION -> reactorView.guide();
            case MOLECULE -> moleculeView.guide();
            case SUBATOMIC -> quantumLab.guide();
        };
        guideOverlay.show(page, lab.style);
        guideOverlay.getNode().setOpacity(1);
    }

    /** Pour les aperçus : un message bref, montré sans son fondu. */
    void previewToast(String message, String action) {
        toast.show(message, action, action == null ? null : () -> { });
        toast.getNode().getChildren().get(0).setOpacity(1);
    }

    /** Les aperçus (-Dgenesis.preview=true) ne touchent pas aux préférences de l'utilisateur. */
    private static final boolean PREVIEW = Boolean.getBoolean("genesis.preview");

    /** On referme le laboratoire ouvert et on ouvre l'autre. */
    private void switchLab(LabBar.Lab target) {
        LabBar.Lab current = visibleLab();
        if (target == current) return;
        ActionType stop = switch (current) {
            case FUSION -> reactorActive ? ActionType.REACTOR_STOP : fusionActive ? ActionType.FUSION_STOP
                    : chainActive ? ActionType.CHAIN_STOP : ActionType.STAR_STOP;
            case MOLECULE -> ActionType.MOLECULE_STOP;
            case SUBATOMIC -> nucleonActive ? ActionType.NUCLEON_STOP : ActionType.SUBATOMIC_STOP;
            case ATOM -> null;
        };
        if (target == LabBar.Lab.ATOM) {
            leaveLab(stop, f -> !f.getReactor().getActive() && !f.getMoleculeLab().getActive() && !f.getNucleon().getActive()
                    && !f.getLab().getActive() && !f.getChain().getActive() && !f.getSubatomic().getActive());
            return;
        }
        if (stop != null) send(stop);
        switch (target) {
            case FUSION -> openFusion();
            case MOLECULE -> openMolecule();
            case SUBATOMIC -> openNucleon();
            default -> { }
        }
    }

    /**
     * La barre de vue, posée sur la scène : l'échelle, ce que fait le glisser,
     * recentrer, des vues toutes faites, la rotation propre, le zoom.
     */
    private Region buildViewBar() {
        HBox scales = new HBox(0,
                viewToggle("Atome", scaleGroup, "l'atome et son nuage · touche 1", () -> goTo(SimCanvas.Scale.ATOME)),
                viewToggle("Noyau", scaleGroup, "plonger dans le noyau, 100 000 fois plus petit · touche 2",
                        () -> goTo(SimCanvas.Scale.NOYAU)),
                viewToggle("Nucléon", scaleGroup, "un nucléon et ses quarks · touche 3",
                        () -> goTo(SimCanvas.Scale.NUCLEON)),
                viewToggle("Gouffre", scaleGroup, "noyau et nuage ensemble, en échelle logarithmique · touche G",
                        () -> {
                            if (!simCanvas.isLogView()) {
                                toggleGulf();
                            }
                        }));
        // Les échelles sont les modes de l'atome : elles vont dans l'en-tête, comme les modes des autres laboratoires.
        for (int i = 0; i < scales.getChildren().size(); i++) {
            javafx.scene.Node scale = scales.getChildren().get(i);
            scale.getStyleClass().setAll("toggle-button", "segment", i == 0 ? "left" : i == 3 ? "right" : "middle");
        }
        scales.setAlignment(Pos.CENTER_LEFT);
        atomModes = scales;
        // La barre de vue : ce que fait le glisser (tourner, déplacer), recentrer, des vues toutes faites, la rotation propre,
        // la valence seule, le zoom. Des icônes, leur nom au survol.
        ToggleButton rotate = Plan.iconToggle("orbit", "Glisser fait tourner l'atome");
        ToggleButton pan = Plan.iconToggle("move", "Glisser déplace la vue (aussi : clic droit glissé)");
        rotate.setToggleGroup(dragGroup);
        pan.setToggleGroup(dragGroup);
        rotate.setOnAction(event -> { rotate.setSelected(true); simCanvas.setPanMode(false); });
        pan.setOnAction(event -> { pan.setSelected(true); simCanvas.setPanMode(true); });
        rotate.setSelected(true);
        rotateButton = rotate;
        panButton = pan;
        Button recenter = Plan.icon("target", null, "Recentrer : l'atome au milieu, vu de trois quarts · double-clic sur la "
                + "scène : centrer ce point");
        recenter.setOnAction(event -> simCanvas.recenter());
        MenuButton views = new MenuButton(I18n.text("Vue"), Icons.path("eye", 16));
        views.getStyleClass().setAll("menu-button", "icon-button", "plan-settings");
        views.setFocusTraversable(false);
        views.setTooltip(Plan.tooltip("Des vues toutes faites : de face, de dessus, de côté, de trois quarts"));
        String[][] presets = {{"De face (axe z vers le haut)", "face"}, {"De dessus", "dessus"},
                {"De côté", "côté"}, {"Trois-quarts", "3/4"}};
        for (String[] preset : presets) {
            MenuItem item = new MenuItem(preset[0]);
            item.setOnAction(event -> simCanvas.viewPreset(preset[1]));
            views.getItems().add(item);
        }
        autoSpin = new ToggleButton("Rotation");
        autoSpin.getStyleClass().add("view-toggle");
        autoSpin.setSelected(true);
        autoSpin.setFocusTraversable(false);
        autoSpin.setTooltip(Plan.tooltip("L'atome tourne lentement sur lui-même"));
        autoSpin.setOnAction(event -> simCanvas.setAutoSpin(autoSpin.isSelected()));
        valenceOnly = new ToggleButton("Valence");
        valenceOnly.getStyleClass().add("view-toggle");
        valenceOnly.setFocusTraversable(false);
        valenceOnly.setTooltip(I18n.tooltip("ne montrer que les sous-couches ouvertes : les pleines, sphériques (théorème d'Unsöld), "
                + "cachent la forme de la valence · touche V"));
        valenceOnly.setOnAction(event -> simCanvas.setValenceOnly(valenceOnly.isSelected()));
        bohrView = new ToggleButton("Bohr");
        bohrView.getStyleClass().add("view-toggle");
        bohrView.setFocusTraversable(false);
        bohrView.setTooltip(I18n.tooltip("le modèle de Bohr (1913) : couches en anneaux, électrons qui tournent. Image historique, "
                + "pratique pour compter les électrons ; la réalité quantique est le nuage · touche O"));
        bohrView.setOnAction(event -> simCanvas.setBohrMode(bohrView.isSelected()));
        bohrView.setVisible(BOHR_VIEW);
        bohrView.setManaged(BOHR_VIEW);
        Button out = Plan.icon("minus", null, "Dézoomer · touche −");
        out.setOnAction(event -> simCanvas.nudgeZoom(0.6));
        Button in = Plan.icon("plus", null, "Zoomer · touche + (ou la molette)");
        in.setOnAction(event -> simCanvas.nudgeZoom(1.6));
        for (Region r : new Region[]{views, autoSpin, valenceOnly}) r.setMinWidth(Region.USE_PREF_SIZE);     // jamais tronqués
        HBox bar = new HBox(2, rotate, pan, rule(), recenter, views, rule(), autoSpin, valenceOnly, bohrView, rule(), out, in);
        bar.setAlignment(Pos.CENTER);
        bar.getStyleClass().add("view-bar");
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        bar.setPickOnBounds(false);
        return bar;
    }

    private static Region rule() {
        Region rule = new Region();
        rule.getStyleClass().add("view-sep");
        rule.setPrefHeight(18);
        rule.setMaxHeight(18);
        HBox.setMargin(rule, new Insets(0, 4, 0, 4));
        return rule;
    }

    private static ToggleButton viewToggle(String text, ToggleGroup group, String hint, Runnable action) {
        ToggleButton button = new ToggleButton(text);
        button.getStyleClass().add("view-toggle");
        button.setToggleGroup(group);
        button.setFocusTraversable(false);
        button.setTooltip(I18n.tooltip(hint));
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Button viewButton(String text, String hint, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("view-button");
        button.setFocusTraversable(false);
        button.setTooltip(I18n.tooltip(hint));
        button.setOnAction(event -> action.run());
        return button;
    }

    /** La barre de vue suit la scène : l'échelle allumée est celle qu'on regarde. */
    private void syncViewBar() {
        String current = simCanvas.isLogView() ? "Gouffre" : switch (simCanvas.scale()) {
            case ATOME -> "Atome";
            case NOYAU -> "Noyau";
            case NUCLEON -> "Nucléon";
        };
        for (javafx.scene.control.Toggle toggle : scaleGroup.getToggles()) {
            ToggleButton button = (ToggleButton) toggle;
            boolean on = I18n.source(button).equals(current);
            if (button.isSelected() != on) {
                button.setSelected(on);
            }
        }
        for (javafx.scene.control.Toggle toggle : dragGroup.getToggles()) {
            ToggleButton button = (ToggleButton) toggle;
            boolean on = (button == panButton) == simCanvas.isPanMode();
            if (button.isSelected() != on) {
                button.setSelected(on);
            }
        }
    }

    /** La case de l'élément suit l'atome : symbole, nom, isotope et état ; les compteurs. */
    private void updateTopBar(UniverseFrame frame) {
        String symbol = frame.getElement().getSymbol();
        elementSymbol.setText(symbol.isEmpty() ? "?" : symbol);
        String color = frame.getElement().getColorHex();
        elementSymbol.setStyle(symbolStyle(color));
        elementName.setText(frame.getElement().getName().isEmpty() ? I18n.text("Chambre vide") : frame.getElement().getName());
        String nuclide = frame.getNucleus().getNuclide();
        int charge = frame.getAtom().getNetCharge();
        elementNuclide.setText((nuclide.isEmpty() ? I18n.text("aucun noyau") : nuclide)
                + (charge == 0 ? "" : charge > 0 ? " · " + I18n.text("ion") + " +" + charge : " · " + I18n.text("ion") + " " + charge)
                + (frame.getNucleus().getUnstable() ? " · " + I18n.text("radioactif") : ""));
        protonCount.setText(Integer.toString(frame.getAtom().getProtons()));
        neutronCount.setText(Integer.toString(frame.getAtom().getNeutrons()));
        electronCount.setText(Integer.toString(frame.getAtom().getElectrons()));
    }

    private Region buildSidebar() {
        TabPane tabs = new TabPane(
                tab("Spectre", padded(lightPanel.getNode())),
                tab("Orbitale", padded(quantumView.getNode())),
                tab("Noyau", nucleusPanel.getNode()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setMinHeight(260);
        tabs.getSelectionModel().select(1);          // on ouvre sur l'orbitale : la forme du nuage, avant le spectre

        // Deux vues du journal : les logs seuls (complets), ou Genesis qui explique en mots simples, les logs en dessous.
        Label journalTitle = new Label("Journal");
        journalTitle.getStyleClass().add("plan-title");
        ToggleGroup viewGroup = new ToggleGroup();
        ToggleButton logsView = viewToggle("Journal", viewGroup, "les événements, leur calcul et leur explication", () -> showGenesis(false));
        ToggleButton genesisView = viewToggle("Genesis", viewGroup, "Genesis explique chaque événement avec des mots simples", () -> showGenesis(true));
        HBox switcher = new HBox(2, logsView, genesisView);
        switcher.getStyleClass().add("view-bar");
        switcher.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Region headGrow = new Region();
        HBox.setHgrow(headGrow, Priority.ALWAYS);
        HBox journalHead = new HBox(8, journalTitle, headGrow, switcher);
        journalHead.setAlignment(Pos.CENTER_LEFT);
        journalHead.setPadding(new Insets(12, 12, 6, 14));
        // Genesis au-dessus du journal : sa bulle explique le dernier événement, ou celui qu'on clique
        guide = new HBox(6, genesis.getNode(), genesis.speech());
        guide.setAlignment(Pos.TOP_LEFT);
        guide.setPadding(new Insets(0, 10, 8, 4));
        HBox.setHgrow(genesis.speech(), Priority.ALWAYS);
        journal.setOnSelect(event -> genesis.explainNow(event));
        VBox journalBox = new VBox(journalHead, guide, journal.getNode());
        VBox.setVgrow(journal.getNode(), Priority.ALWAYS);
        journalBox.getStyleClass().add("plan-side");
        switcher.setVisible(GENESIS_ENABLED);
        switcher.setManaged(GENESIS_ENABLED);
        genesisShown = GENESIS_ENABLED && java.util.prefs.Preferences.userNodeForPackage(App.class).getBoolean("journal.genesis", false);
        (genesisShown ? genesisView : logsView).setSelected(true);
        viewGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true);       // une vue reste toujours choisie
            }
        });
        showGenesis(genesisShown);

        SplitPane split = new SplitPane(tabs, journalBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.48);

        VBox sidebar = new VBox(split);
        VBox.setVgrow(split, Priority.ALWAYS);
        sidebar.setPrefWidth(SIDEBAR_WIDTH);
        sidebar.setMinWidth(SIDEBAR_WIDTH);
        sidebar.setMaxWidth(SIDEBAR_WIDTH);
        sidebar.getStyleClass().addAll("plan-side", "right");
        return sidebar;
    }

    /** Passe du journal seul (logs complets) à Genesis (elle explique, logs compacts en dessous), et s'en souvient. */
    private void showGenesis(boolean on) {
        boolean changed = on != genesisShown;
        genesisShown = on;
        guide.setVisible(on);
        guide.setManaged(on);
        journal.setCompact(on);
        if (changed) {
            java.util.prefs.Preferences.userNodeForPackage(App.class).putBoolean("journal.genesis", on);
            SimEvent latest = journal.latest();
            if (on && latest != null) {
                genesis.explainNow(latest);
            }
        }
    }

    private static Tab tab(String title, Region content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    /**
     * Le canevas épouse toute la place de l'onglet, en largeur ET en hauteur : quand le journal grandit, le dessin se
     * resserre (la coupe rétrécit) au lieu d'être coupé par le bas.
     */
    private static Region padded(javafx.scene.canvas.Canvas canvas) {
        Pane box = new Pane(canvas);
        box.getStyleClass().add("plan-side");
        box.setMinSize(0, 0);
        box.setPrefSize(canvas.getWidth() + 24, canvas.getHeight() + 20);   // taille voulue, pas une contrainte
        canvas.relocate(12, 10);
        canvas.widthProperty().bind(box.widthProperty().subtract(24));
        canvas.heightProperty().bind(box.heightProperty().subtract(20));
        return box;
    }

    private boolean updatingLaser;

    /** Règle le laser ; appelé par le curseur ou par un clic sur le spectre. */
    private void tuneLaser(double nm) {
        laserNm = Math.max(LightPanel.MIN_NM, Math.min(LightPanel.MAX_NM, nm));
        updatingLaser = true;
        laserSlider.setValue(Math.log10(laserNm));
        updatingLaser = false;
        laserSwatch.setFill(PhotonPacket.colour(laserNm));
        String region = Visuals.region(laserNm);
        laserLabel.setText(Visuals.format(laserNm) + " nm");
        laserEnergy.setText(Visuals.format(1239.841984 / laserNm) + " eV" + (region.equals("visible") ? "" : " · " + region));
        lightPanel.setLaser(laserNm);
    }

    private void firePhoton() {
        if (client != null) {
            try {
                client.firePhoton(laserNm);
            } catch (RuntimeException error) {
                onError(error);
            }
        }
    }

    // ------------------------------------------------------------------
    // Réseau
    // ------------------------------------------------------------------
    private void waitForBundledEngine(BundledEngine engine) {
        Thread boot = new Thread(() -> {
            try {
                engine.awaitReady(180);
                Platform.runLater(() -> {
                    if (!stopping) {
                        setStatus("Moteur Python prêt · connexion en cours…", "#FBBF24");
                        startup.status("Moteur prêt · connexion…");
                        connect();
                    }
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException error) {
                Platform.runLater(() -> {
                    if (!stopping) {
                        loader.fail("Moteur Python indisponible", error.getMessage());
                        startup.fail("Moteur Python indisponible", error.getMessage());
                        simCanvas.setStatusMessage(error.getMessage());
                        setStatus(error.getMessage(), "#F87171");
                    }
                });
            }
        }, "genesis-engine-startup");
        boot.setDaemon(true);
        boot.start();
    }

    private void connect() {
        engineConnected = false;
        String host = bundledEngine == null ? System.getProperty("genesis.host", "localhost") : "127.0.0.1";
        int port = bundledEngine == null ? Integer.getInteger("genesis.port", 50051) : bundledEngine.port();
        simCanvas.setStatusMessage(I18n.text("Connexion au moteur atomique (") + host + ":" + port + ")...");
        int generation = ++connectionGeneration;

        client = new EngineClient(host, port,
                frame -> Platform.runLater(() -> {
                    if (generation == connectionGeneration) {
                        onFrame(frame);
                    }
                }),
                error -> Platform.runLater(() -> {
                    if (generation == connectionGeneration) {
                        onError(error);
                    }
                }),
                () -> Platform.runLater(() -> {
                    if (generation == connectionGeneration) {
                        scheduleReconnect();
                    }
                }));

        client.send(ActionType.HELLO);
        if (periodicTable == null) {
            fetchPeriodicTable();
        }
    }

    private void scheduleReconnect() {
        if (reconnectPending) {
            return;
        }
        reconnectPending = true;
        double delay = Math.min(10.0, 1.5 * Math.pow(1.6, reconnectAttempts));
        reconnectAttempts++;
        setStatus(String.format(I18n.text("Moteur injoignable · nouvel essai dans %.0f s"), delay), "#FBBF24");
        PauseTransition pause = new PauseTransition(Duration.seconds(delay));
        pause.setOnFinished(event -> {
            reconnectPending = false;
            if (client != null) {
                client.abandon();
            }
            connect();
        });
        pause.play();
    }

    private void fetchPeriodicTable() {
        Thread loader = new Thread(() -> {
            try {
                PeriodicTable table = client.fetchPeriodicTable();
                Platform.runLater(() -> {
                    periodicTable = table;
                    stabilityChart.setTable(table);
                    String[] symbols = new String[table.getElementsCount() + 2];
                    for (var element : table.getElementsList()) {
                        if (element.getZ() < symbols.length) symbols[element.getZ()] = element.getSymbol();
                    }
                    chainView.setSymbols(symbols);
                    simCanvas.setParticles(table.getParticlesList());
                    tableButton.setDisable(false);
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> setStatus(I18n.text("Table périodique indisponible : ") + error.getMessage(), "#F87171"));
            }
        }, "periodic-table-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void onFrame(UniverseFrame frame) {
        if (frame.getProtocolVersion() != EngineClient.PROTOCOL_VERSION) {
            reportProtocolMismatch(frame.getProtocolVersion());
            return;
        }
        protocolMismatchReported = false;
        reconnectAttempts = 0;
        engineConnected = true;
        lastFrame = frame;
        if (!startElementLoaded) {
            // Au lancement, un atome d'uranium 238 : son nuage, son noyau, et sa longue chaîne dans la timeline.
            startElementLoaded = true;
            if (client != null && frame.getAtom().getProtons() != START_ELEMENT) {
                client.loadElement(START_ELEMENT);
            }
        }
        if (pendingLabStart != null) {
            ActionType start = pendingLabStart;
            pendingLabStart = null;
            send(start);
        }

        // Une particule arrive (proton, neutron, électron capturé) : elle part maintenant, et l'atome ne change
        // qu'à l'impact. Les frames suivantes attendent derrière, dans l'ordre ; plusieurs vols se font en parallèle.
        double flight = simCanvas.announce(frame);
        if (flight > 0 || !heldFrames.isEmpty()) {
            double previous = heldFrames.isEmpty() ? clock : (double) heldFrames.peekLast()[1];
            heldFrames.add(new Object[]{frame, Math.max(previous, clock + flight)});
            return;
        }
        applyFrame(frame);
    }

    /** Les frames tenues dont la particule a touché l'atome. */
    private void releaseHeldFrames() {
        while (!heldFrames.isEmpty() && (double) heldFrames.peek()[1] <= clock) {
            applyFrame((UniverseFrame) heldFrames.poll()[0]);
        }
    }

    private void applyFrame(UniverseFrame frame) {
        simCanvas.applyFrame(frame);
        updateMatter(frame);
        loader.onFrame(frame);
        if (opening != null && !loader.isShowing()) {
            opening = null;                       // le moteur a confirmé : la barre suit de nouveau les frames
            syncLab();
        }
        startup.onFrame(frame);
        lightPanel.applyFrame(frame);
        nucleusPanel.applyFrame(frame);
        if (frame.getStructureChanged()) {
            quantumView.applyFrame(frame);
            identity.update(frame);
        }
        updateTopBar(frame);
        timeline.applyFrame(frame);
        for (SimEvent event : frame.getEventsList()) {
            journal.add(event);
            if (genesisShown) {
                genesis.explain(event);
            }
        }
        setStatus(I18n.text("Connecté · règles et données servies par le moteur (") + frame.getSource() + ")", "#34D399");
    }

    /** Échelle de la matière : la carte de l'atome cède la place à la boîte d'atomes. */
    private void updateMatter(UniverseFrame frame) {
        boolean fusion = frame.getLab().getActive();
        boolean star = frame.getStar().getActive();
        boolean reactor = frame.getReactor().getActive();
        boolean subatomic = frame.getSubatomic().getActive();
        boolean nucleon = frame.getNucleon().getActive();
        boolean partons = frame.getPartons().getActive();
        boolean chain = frame.getChain().getActive();
        boolean molecule = frame.getMoleculeLab().getActive();
        if (fusion != fusionActive || star != starActive || reactor != reactorActive || subatomic != subatomicActive
                || nucleon != nucleonActive || partons != partonsActive || chain != chainActive
                || molecule != moleculeActive) {
            chainActive = chain;
            chainView.getNode().setVisible(chain);
            moleculeActive = molecule;
            moleculeView.getNode().setVisible(molecule);
            fusionActive = fusion;
            starActive = star;
            reactorActive = reactor;
            subatomicActive = subatomic;
            nucleonActive = nucleon;
            partonsActive = partons;
            mainScreen.setVisible(!fusion && !star && !reactor && !subatomic && !nucleon && !chain && !molecule);
            fusionView.getNode().setVisible(fusion);
            starView.getNode().setVisible(star);
            reactorView.getNode().setVisible(reactor);
            subatomicView.getNode().setVisible(subatomic);
            quantumLab.getNode().setVisible(nucleon);
            syncLab();
        }
        fusionView.applyFrame(frame);
        reactorView.applyFrame(frame);
        chainView.applyFrame(frame);
        moleculeView.applyFrame(frame);
        starView.applyFrame(frame);
        subatomicView.applyFrame(frame);
        quantumLab.applyFrame(frame);
        partonView.applyFrame(frame);
        boolean active = frame.getMatter().getActive();
        boolean chemistry = frame.getChemistry().getActive();
        if (active != matterActive || chemistry != chemistryActive) {
            matterActive = active;
            chemistryActive = chemistry;
            simCanvas.getNode().setVisible(!active && !chemistry);
            matterCanvas.getNode().setVisible(active);
            moleculeCanvas.getNode().setVisible(chemistry);

        }
        moleculeCanvas.applyFrame(frame);
        matterCanvas.applyFrame(frame);

    }

    // ------------------------------------------------------------------
    // Laboratoires : ouvrir, revenir, et le dire pendant que le moteur prépare
    // ------------------------------------------------------------------
    private void openFusion() {
        openLab(ActionType.REACTOR_START, "Ouverture du laboratoire de fusion",
                "Le moteur prépare les noyaux, leurs champs et les données des réactions mesurées.",
                f -> f.getReactor().getActive(), ActionType.REACTOR_STOP);
    }

    private void fireNeutron() {
        send(ActionType.FIRE_NEUTRON);
    }

    private void openMolecule() {
        openLab(ActionType.MOLECULE_START, "Ouverture du laboratoire moléculaire",
                "Le moteur résout les électrons de valence (GFN1-xTB) et relaxe la première molécule.",
                f -> f.getMoleculeLab().getActive() && f.getMoleculeLab().getReady(), ActionType.MOLECULE_STOP);
    }

    /** L'établi moléculaire : n'importe quel élément du tableau (GFN1-xTB calcule de H à Rn). */
    private void openMoleculeTable() {
        if (periodicTable == null) {
            return;
        }
        if (moleculeDialog == null) {
            moleculeDialog = new PeriodicTableDialog(moleculeView.getNode().getScene().getWindow(), periodicTable,
                    atomicNumber -> {
                        String symbol = "Z" + atomicNumber;
                        for (var element : periodicTable.getElementsList()) {
                            if (element.getZ() == atomicNumber) symbol = element.getSymbol();
                        }
                        moleculeView.chooseElement(atomicNumber, symbol);
                    },
                    "Genesis - Choisir un élément pour l'établi moléculaire", moleculeView::supports,
                    "GFN1-xTB n'est paramétré que de l'hydrogène au radon (Z = 1 à 86) ; au-delà, aucun calcul n'est "
                            + "possible.");
        }
        moleculeDialog.show();
    }

    private void openChain() {
        openLab(ActionType.CHAIN_START, "Ouverture du laboratoire de la réaction en chaîne",
                "Le moteur charge les sections efficaces évaluées (ENDF/B-VIII.0) et calcule le transport des neutrons.",
                f -> f.getChain().getActive() && f.getChain().getReady(), ActionType.CHAIN_STOP);
    }

    /** Le bac à sable : n'importe quel élément du tableau, son isotope naturel. */
    private void openChainTable() {
        if (periodicTable == null) {
            return;
        }
        if (chainDialog == null) {
            chainDialog = new PeriodicTableDialog(chainView.getNode().getScene().getWindow(), periodicTable,
                    atomicNumber -> send(ActionType.CHAIN_SET_NUCLIDE, atomicNumber * 1000),
                    "Genesis - Choisir un noyau pour le bac à sable");
        }
        chainDialog.show();
    }

    /**
     * Une particule α de 5 MeV (énergie typique d'une désintégration α) sur le noyau affiché : la chambre de collision
     * s'ouvre avec He-4 contre lui, l'énergie convertie dans le centre de masse.
     */
    private void fireAlpha() {
        if (lastFrame == null || lastFrame.getAtom().getProtons() < 1) {
            notice(I18n.text("Aucun noyau à viser : choisir d'abord un élément."));
            return;
        }
        int z = lastFrame.getAtom().getProtons();
        int a = z + lastFrame.getAtom().getNeutrons();
        openFusion();
        if (!engineConnected || protocolMismatchReported || client == null) {
            return;
        }
        client.send(ActionType.SET_REACTOR_NUCLEUS_A, 2004);
        client.send(ActionType.SET_REACTOR_NUCLEUS_B, z * 1000 + a);
        client.send(ActionType.SET_REACTOR_ENERGY, ALPHA_LAB_KEV * a / (a + 4.0));
    }

    private void openSubatomic() {
        openLab(ActionType.SUBATOMIC_START, "Ouverture du laboratoire subatomique",
                "Le moteur place les protons, neutrons et électrons et calcule leurs potentiels.",
                f -> f.getSubatomic().getActive(), ActionType.SUBATOMIC_STOP);
    }

    private void openNucleon() {
        openLab(ActionType.NUCLEON_START, "Ouverture du monde subatomique",
                "Le moteur prépare le nucléon : quarks, gluons, sections efficaces mesurées.",
                f -> f.getNucleon().getActive(), ActionType.NUCLEON_STOP);
    }

    /** Demande un laboratoire au moteur ; l'écran d'attente reste jusqu'à ce que la frame le confirme. */
    private void openLab(ActionType start, String heading, String detail,
                         java.util.function.Predicate<UniverseFrame> ready, ActionType stop) {
        opening = switch (start) {
            case MOLECULE_START -> LabBar.Lab.MOLECULE;
            case SUBATOMIC_START, NUCLEON_START -> LabBar.Lab.SUBATOMIC;
            default -> LabBar.Lab.FUSION;            // réacteur, fusion, étoile, réaction en chaîne
        };
        loader.show(heading, detail, ready, () -> {
            opening = null;
            pendingLabStart = null;
            send(stop);
            showHome();                   // ouverture annulée : on revient au choix des laboratoires
        });
        if (!engineConnected) {
            if (bundledEngine != null) {
                pendingLabStart = start;
                return;
            }
            loader.fail("Moteur injoignable", "Lance le moteur (docker compose up --build), la connexion reprendra seule.");
            return;
        }
        if (protocolMismatchReported) {
            loader.fail("Moteur obsolète", I18n.text("Le moteur parle une ancienne version du contrat (v")
                    + EngineClient.PROTOCOL_VERSION + I18n.text(" attendue). Arrête-le et relance : docker compose up --build"));
            return;
        }
        send(start);
    }

    /** Retour à l'atome : même attente, plus courte. */
    private void leaveLab(ActionType stop, java.util.function.Predicate<UniverseFrame> ready) {
        opening = LabBar.Lab.ATOM;
        loader.show("Retour à l'atome", "Le moteur referme le laboratoire et reprend l'atome là où il était.",
                ready, null);
        send(stop);
    }

    /** Le gouffre des échelles : on revient d'abord à l'atome si la matière ou la chimie est ouverte. */
    private void toggleGulf() {
        if (matterActive || chemistryActive) {
            goTo(SimCanvas.Scale.ATOME);
        }
        simCanvas.toggleLogView();
    }

    /** Les boutons d'échelle ramènent d'abord de la matière à l'atome. */
    private void goTo(SimCanvas.Scale scale) {
        if (matterActive) {
            send(ActionType.MATTER_STOP);
        }
        if (chemistryActive) {
            send(ActionType.CHEMISTRY_STOP);
        }
        simCanvas.jumpTo(scale);
    }

    private void fireChain(int count, double[] position) {
        if (client == null) {
            return;
        }
        try {
            client.fireChain(count, position);
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    private void sendVector(ActionType action, int value, double amount, double[] vector) {
        if (client == null) {
            return;
        }
        try {
            client.send(action, value, amount, vector);
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    private void sendText(ActionType action, int value, String text) {
        if (client == null) {
            return;
        }
        try {
            client.sendText(action, value, 0, text);
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    private void sendBoth(ActionType action, int value, double amount) {
        if (client == null) {
            return;
        }
        try {
            client.send(action, value, amount);
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    private void sendAmount(ActionType action, double amount) {
        if (client == null) {
            return;
        }
        try {
            client.send(action, amount);
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    private void sendPartons(boolean start, double q2, double xMin) {
        if (client == null) return;
        try {
            client.partonResolution(start, q2, xMin);
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    private void sendCollision(int target, double energyMev, double angleDeg, int projectile) {
        if (client == null) return;
        try {
            client.collide(target, energyMev, angleDeg, projectile);
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    private void sendHadron(int spinIndex, double[] triples) {
        if (client == null) return;
        try {
            client.testHadron(spinIndex, triples);
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    private void sendNucleonStrike(int quark, double momentumMev, double dx, double dy, double dz) {
        if (client == null) return;
        try {
            client.strikeNucleon(quark, momentumMev, dx, dy, dz);
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    private void sendSubatomicKick(SubatomicView.Kick kick) {
        if (client == null) return;
        try {
            client.kickSubatomic(kick.particle(), kick.sample(), kick.vx(), kick.vy(), kick.vz());
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    private void reportProtocolMismatch(int received) {
        if (protocolMismatchReported) {
            return;
        }
        protocolMismatchReported = true;
        String message = I18n.text("Moteur obsolète : protocole v") + received
                + I18n.text(" reçu, v") + EngineClient.PROTOCOL_VERSION + " attendu.\n"
                + I18n.text("Le conteneur tourne encore sur une ancienne image.\n")
                + I18n.text("Arrête-le puis relance :  docker compose up --build");
        simCanvas.setStatusMessage(message);
        setStatus("Version de protocole incompatible - relance docker compose up --build", "#F87171");
        String obsolete = I18n.text("Le moteur envoie le protocole v") + received + I18n.text(", le client attend v")
                + EngineClient.PROTOCOL_VERSION + I18n.text(". Arrête-le et relance : docker compose up --build");
        loader.fail("Moteur obsolète", obsolete);
        startup.fail("Moteur obsolète", obsolete);
    }

    private void onError(Throwable error) {
        engineConnected = false;
        // Au démarrage, le moteur n'écoute pas encore : on réessaie, ce n'est pas un échec.
        startup.status("Connexion au moteur : nouvel essai…");
        if (bundledEngine != null) {
            String detail = I18n.text("Connexion au moteur Python interrompue. Nouvel essai en cours. Journal : ")
                    + bundledEngine.logPath();
            loader.fail("Moteur injoignable", detail);
            simCanvas.setStatusMessage(detail);
        } else {
            loader.fail("Moteur injoignable", "La connexion au moteur est coupée ; elle reprendra seule dès qu'il répondra.");
            simCanvas.setStatusMessage(I18n.text("Moteur injoignable.\nLance : docker compose up --build\n")
                    + "(reconnexion automatique en cours)");
        }
        scheduleReconnect();
    }

    // ------------------------------------------------------------------
    // Contrôles
    // ------------------------------------------------------------------
    /** Le partenaire de l'atome courant : la chimie quantique dira ce qu'ils font ensemble. */
    private void openPartnerTable() {
        if (periodicTable == null) {
            return;
        }
        if (partnerDialog == null) {
            partnerDialog = new PeriodicTableDialog(tableButton.getScene().getWindow(), periodicTable,
                    atomicNumber -> send(ActionType.CHEMISTRY_START, atomicNumber),
                    "Genesis - Choisir le partenaire de la molécule");
        }
        partnerDialog.show();
    }

    private void openPeriodicTable() {
        if (periodicTable == null) {
            notice("La table périodique arrive du moteur, un instant…");
            return;
        }
        try {
            if (tableDialog == null) {
                tableDialog = new PeriodicTableDialog(tableButton.getScene().getWindow(), periodicTable,
                        atomicNumber -> {
                            if (client != null) {
                                client.loadElement(atomicNumber);
                            }
                        });
            }
            tableDialog.show();
        } catch (RuntimeException error) {
            tableDialog = null;
            setStatus(I18n.text("Ouverture de la table impossible : ") + error, "#F87171");
        }
    }

    private Button actionButton(String text, String colorHex, ActionType action, String hint) {
        Button button = new Button(text);
        button.setStyle(buttonStyle("#111C33", colorHex));
        button.setFocusTraversable(false);
        button.setTooltip(I18n.tooltip(hint));
        button.setOnAction(event -> send(action));
        return button;
    }

    private static String buttonStyle(String background, String foreground) {
        return "-fx-background-color: " + background + "; -fx-text-fill: " + foreground + "; "
                + "-fx-font-size: 12px; -fx-font-weight: bold; -fx-background-radius: 6; "
                + "-fx-padding: 6 10 6 10; -fx-cursor: hand;";
    }

    private static Button overlayButton(String text, String hint, Runnable action) {
        Button button = new Button(text);
        button.setFocusTraversable(false);
        button.setTooltip(I18n.tooltip(hint));
        button.setStyle("-fx-background-color: #111C33DD; -fx-text-fill: #CBD5E1; "
                + "-fx-font-size: 11px; -fx-font-weight: bold; -fx-background-radius: 6; "
                + "-fx-min-height: 26; -fx-padding: 3 10 3 10; -fx-cursor: hand;");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Region spacer(double width) {
        Region region = new Region();
        region.setMinWidth(width);
        return region;
    }

    private void onKeyPressed(KeyEvent event) {
        if (startup.isShowing()) return;
        // Le guide ouvert : n'importe quelle touche le referme.
        if (guideOverlay.isShowing()) {
            guideOverlay.hide();
            event.consume();
            return;
        }
        // Sur l'accueil, les touches 1 à 4 ouvrent un laboratoire ; Échap revient à celui qui est ouvert.
        if (home.isShowing()) {
            if (home.key(event.getCode())) event.consume();
            return;
        }
        // Les touches communes à tous les laboratoires (sauf pendant qu'on écrit dans un champ).
        boolean typing = event.getTarget() instanceof javafx.scene.control.TextInputControl;
        if (!typing) {
            if (event.isShortcutDown() && event.getCode() == KeyCode.S) {
                capture();
                event.consume();
                return;
            }
            if (!event.isShortcutDown() && !event.isAltDown()) {
                switch (event.getCode()) {
                    case H, F1 -> { showGuide(); event.consume(); return; }
                    case TAB -> { togglePanels(); event.consume(); return; }
                    case F11 -> { toggleFullscreen(); event.consume(); return; }
                    case ESCAPE -> {
                        if (!moleculeView.assemblerOpen()) { showHome(); event.consume(); return; }
                    }
                    default -> { }
                }
            }
        }
        boolean remove = event.isShiftDown();
        if (partonsActive) {
            switch (event.getCode()) {
                case R -> send(ActionType.PARTONS_RESET);
                case SPACE -> partonView.togglePlayback();
                default -> { }
            }
            return;
        }
        if (chainActive) {
            return;
        }
        if (moleculeActive) {
            return;
        }
        if (nucleonActive) {
            switch (event.getCode()) {
                case R -> send(ActionType.NUCLEON_RESET);
                case SPACE -> quantumLab.togglePlayback();
                default -> { }
            }
            return;
        }
        if (event.getCode() == KeyCode.K) {
            openNucleon();
            return;
        }
        switch (event.getCode()) {
            case P -> {
                if (subatomicActive) send(ActionType.SUBATOMIC_SET_PROTONS,
                        Math.max(0, subatomicView.protons() + (remove ? -1 : 1)));
                else send(remove ? ActionType.REMOVE_PROTON : ActionType.ADD_PROTON);
            }
            case N -> {
                if (subatomicActive) send(ActionType.SUBATOMIC_SET_NEUTRONS,
                        Math.max(0, subatomicView.neutrons() + (remove ? -1 : 1)));
                else send(remove ? ActionType.REMOVE_NEUTRON : ActionType.ADD_NEUTRON);
            }
            case E -> {
                if (subatomicActive) send(ActionType.SUBATOMIC_SET_ELECTRONS,
                        Math.max(0, subatomicView.electrons() + (remove ? -1 : 1)));
                else send(remove ? ActionType.REMOVE_ELECTRON : ActionType.ADD_ELECTRON);
            }
            case L -> firePhoton();
            case F -> send(ActionType.HEAT);
            case A -> send(ActionType.SET_STEP_MODE, timeline.stepMode() ? 0 : 1);
            case SPACE -> {
                if (subatomicActive) subatomicView.togglePlayback();
                else send(ActionType.TOGGLE_PAUSE);
            }
            case RIGHT -> send(ActionType.NEXT_EVENT);
            case OPEN_BRACKET -> shiftSpeed(-1);
            case CLOSE_BRACKET -> shiftSpeed(+1);
            case R -> send(subatomicActive ? ActionType.SUBATOMIC_RESET : ActionType.RESET);
            case S -> {
                if (subatomicActive) leaveLab(ActionType.SUBATOMIC_STOP, f -> !f.getSubatomic().getActive());
                else if (SUBATOMIC_SCREEN) openSubatomic();
            }
            case T -> openPeriodicTable();
            case V -> {
                if (!subatomicActive) {
                    valenceOnly.setSelected(!valenceOnly.isSelected());
                    simCanvas.setValenceOnly(valenceOnly.isSelected());
                }
            }
            case DIGIT1, NUMPAD1 -> { if (!subatomicActive) goTo(SimCanvas.Scale.ATOME); }
            case DIGIT2, NUMPAD2 -> { if (!subatomicActive) goTo(SimCanvas.Scale.NOYAU); }
            case DIGIT3, NUMPAD3 -> { if (!subatomicActive) goTo(SimCanvas.Scale.NUCLEON); }
            case G -> { if (!subatomicActive) toggleGulf(); }
            case M -> {
                if (ADVANCED_SCALES) {
                    send(matterActive ? ActionType.MATTER_STOP : ActionType.MATTER_START);
                }
            }
            case C -> matterCanvas.toggleColoring();
            case D -> moleculeCanvas.toggleDifference();
            case B -> {
                if (ADVANCED_SCALES) {
                    openPartnerTable();
                } else if (!subatomicActive) {
                    fireNeutron();
                }
            }
            case U -> {
                if (reactorActive) leaveLab(ActionType.REACTOR_STOP, f -> !f.getReactor().getActive());
                else openFusion();
            }
            case O -> {
                if (STAR_SCREEN) {
                    send(starActive ? ActionType.STAR_STOP : ActionType.STAR_START);
                } else if (BOHR_VIEW && !subatomicActive) {
                    bohrView.setSelected(!bohrView.isSelected());
                    simCanvas.setBohrMode(bohrView.isSelected());
                }
            }
            case ADD, PLUS, EQUALS -> {
                if (subatomicActive) subatomicView.zoomBy(1);
                else simCanvas.nudgeZoom(1.6);
            }
            case SUBTRACT, MINUS -> {
                if (subatomicActive) subatomicView.zoomBy(-1);
                else simCanvas.nudgeZoom(0.6);
            }
            default -> {
                return;
            }
        }
        event.consume();
    }

    private void shiftSpeed(int step) {
        int index = timeline.speedIndex() + step;
        if (index >= 0 && index < timeline.speedCount()) {
            send(ActionType.SET_SPEED, index);
        }
    }

    private void send(ActionType action) {
        send(action, 0);
    }

    private void send(ActionType action, int value) {
        if (client == null) {
            return;
        }
        try {
            client.send(action, value);
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    /**
     * L'état de la connexion. Connecté (vert) : la barre se tait. Sinon (ambre : en cours, rouge : en panne), une pastille
     * le dit dans la barre du haut, le détail au survol.
     */
    private void setStatus(String message, String colorHex) {
        if (labBar == null) return;
        boolean fine = colorHex.equalsIgnoreCase("#34D399");
        labBar.status(fine ? null : message, colorHex.equalsIgnoreCase("#F87171"));
    }

    /** Un avertissement bref (un geste impossible, un lien qui ne s'ouvre pas) : en bas de l'écran, quelques secondes. */
    private void notice(String message) {
        toast.show(message, null, null);
    }

    @Override
    public void stop() {
        stopping = true;
        telemetry.stop(java.time.Duration.ofSeconds(2));    // la fin de la session ; au plus 2 s, la file attend sinon
        if (tableDialog != null) {
            tableDialog.close();
        }
        if (partnerDialog != null) {
            partnerDialog.close();
        }
        if (client != null) {
            client.shutdown();
        }
        if (bundledEngine != null) {
            bundledEngine.close();
        }
    }

    static String stylesheet() {
        return App.class.getResource("/genesis.css").toExternalForm();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
