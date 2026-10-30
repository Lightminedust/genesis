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
import javafx.stage.Popup;
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
    private static final double SIDEBAR_WIDTH = 400;

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

    private final Circle statusDot = new Circle(4.5, Color.web("#64748B"));
    private final Tooltip statusTip = I18n.tooltip("Démarrage…");
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
    private Button lightButton;
    private Popup lightPopup;
    private final ToggleGroup scaleGroup = new ToggleGroup();
    private final ToggleGroup dragGroup = new ToggleGroup();
    private ToggleButton autoSpin;
    private ToggleButton valenceOnly;
    private ToggleButton bohrView;
    /**
     * Frames tenues jusqu'à l'impact de la particule qu'elles annoncent (proton, neutron, électron) : l'atome ne change
     * d'identité qu'au moment où la particule le touche. {frame, instant de l'impact sur `clock`}.
     */
    private final java.util.ArrayDeque<Object[]> heldFrames = new java.util.ArrayDeque<>();
    private double clock;
    private Slider laserSlider;
    private Label laserLabel;
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
        stage.show();
        if (small || stage.getWidth() > screen.getWidth() || stage.getHeight() > screen.getHeight()) {
            stage.setMaximized(true);
        } else {
            stage.setX(screen.getMinX() + (screen.getWidth() - stage.getWidth()) / 2);
            stage.setY(screen.getMinY() + (screen.getHeight() - stage.getHeight()) / 2);
        }

        if (Boolean.getBoolean("genesis.bundled")) {
            loader.show("Démarrage du moteur Python",
                    "Chargement des données et des modèles de simulation. La connexion s'ouvrira automatiquement.",
                    frame -> true, null);
            loader.withSupport(this::openLink);
            try {
                bundledEngine = BundledEngine.start();
                setStatus("Chargement du moteur Python embarqué…", "#FBBF24");
                waitForBundledEngine(bundledEngine);
            } catch (IOException error) {
                simCanvas.setStatusMessage(I18n.text("Moteur embarqué impossible à démarrer : ") + error.getMessage());
                setStatus(I18n.text("Moteur embarqué impossible à démarrer : ") + error.getMessage(), "#F87171");
                loader.fail("Démarrage impossible", error.getMessage());
            }
        } else {
            connect();
        }

        checkForUpdates();

        new AnimationTimer() {
            private long previous;
            @Override
            public void handle(long now) {
                double delta = previous == 0 ? 0 : Math.min(.1, (now - previous) / 1e9);
                previous = now;
                clock += delta;
                releaseHeldFrames();
                loader.tick(delta);
                updateCard.tick(delta);
                if (pendingUpdate != null && !loader.isShowing()) {
                    offerUpdate(pendingUpdate);
                    pendingUpdate = null;
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
        StackPane laboratories = new StackPane(mainScreen, fusionView.getNode(), starView.getNode(),
                reactorView.getNode(), chainView.getNode(), moleculeView.getNode(), subatomicView.getNode(), quantumLab.getNode(), loader.getNode(),
                updateCard.getNode());
        BorderPane localizedShell = new BorderPane(laboratories);
        localizedShell.setTop(buildLanguageBar());
        Scene scene = new Scene(localizedShell,
                width, height);
        scene.setFill(Color.web("#05070E"));
        scene.getStylesheets().add(stylesheet());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);

        tuneLaser(laserNm);
        I18n.attach(scene);
        return scene;
    }

    /** Language selection belongs to the client shell, so it stays available in every laboratory. */
    private Region buildLanguageBar() {
        MenuButton choice = new MenuButton();
        choice.setFocusTraversable(false);
        choice.setStyle("-fx-background-color: #142139; -fx-text-fill: #E6EDF7; "
                + "-fx-font-size: 11px; -fx-font-weight: bold; -fx-background-radius: 7; -fx-padding: 2 7;");
        MenuItem french = new MenuItem("Français");
        MenuItem english = new MenuItem("English");
        french.setOnAction(event -> I18n.setLanguage(I18n.Language.FR));
        english.setOnAction(event -> I18n.setLanguage(I18n.Language.EN));
        choice.getItems().addAll(french, english);
        Runnable refresh = () -> {
            choice.setText(I18n.language() == I18n.Language.FR ? "FR" : "EN");
            french.setDisable(I18n.language() == I18n.Language.FR);
            english.setDisable(I18n.language() == I18n.Language.EN);
        };
        I18n.onChange(refresh);
        refresh.run();
        HBox bar = new HBox(choice);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(3, 12, 3, 12));
        bar.setStyle("-fx-background-color: #070D19; -fx-border-color: #1A2941; -fx-border-width: 0 0 1 0;");
        return bar;
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
        loader.withSupport(url -> { });
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
            setStatus(I18n.text("Impossible d'ouvrir ") + url, "#FBBF24");
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
        updateCard.tick(1 / 60.0);
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
        canvasHolder.setStyle("-fx-background-color: #05070E;");
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

        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());
        root.setCenter(simulation);
        root.setRight(buildSidebar());
        return root;
    }

    /** La barre du haut : l'élément (clic : la table), p⁺ n e⁻, la lumière, les laboratoires. */
    private Region buildTopBar() {
        Label brand = new Label("GENESIS");
        brand.getStyleClass().add("dash-brand");
        ImageView logo = new ImageView(Branding.logo());
        logo.setFitWidth(42);
        logo.setFitHeight(42);
        logo.setPreserveRatio(true);
        StackPane logoBadge = new StackPane(logo);
        logoBadge.getStyleClass().add("dash-logo");
        logoBadge.setMinSize(38, 38);
        logoBadge.setMaxSize(38, 38);
        HBox identity = new HBox(8, logoBadge, brand);
        identity.setAlignment(Pos.CENTER_LEFT);

        elementSymbol.setMinSize(36, 36);
        elementSymbol.setMaxSize(36, 36);
        elementSymbol.setAlignment(Pos.CENTER);
        elementSymbol.setStyle("-fx-background-color: #1B2740; -fx-background-radius: 8; -fx-text-fill: #E6EDF7;"
                + " -fx-font-size: 16px; -fx-font-weight: bold;");
        elementName.setStyle("-fx-text-fill: #E6EDF7; -fx-font-size: 13px; -fx-font-weight: bold;");
        elementNuclide.setStyle("-fx-text-fill: #8B9BB8; -fx-font-size: 11px;");
        Label caret = new Label("▾");
        caret.setStyle("-fx-text-fill: #8B9BB8; -fx-font-size: 11px;");
        HBox chipContent = new HBox(9, elementSymbol, new VBox(0, elementName, elementNuclide), caret);
        chipContent.setAlignment(Pos.CENTER_LEFT);
        tableButton = new Button();
        tableButton.setGraphic(chipContent);
        tableButton.getStyleClass().add("dash-chip");
        tableButton.setFocusTraversable(false);
        tableButton.setDisable(true);
        tableButton.setTooltip(I18n.tooltip("changer d'élément : la table périodique · touche T"));
        tableButton.setOnAction(event -> openPeriodicTable());

        HBox build = new HBox(8,
                stepper("p⁺", "#F87171", protonCount, ActionType.ADD_PROTON, ActionType.REMOVE_PROTON,
                        "protons : ils font l'élément · P / Maj+P"),
                stepper("n", "#94A3B8", neutronCount, ActionType.ADD_NEUTRON, ActionType.REMOVE_NEUTRON,
                        "neutrons : ils font l'isotope · N / Maj+N"),
                stepper("e⁻", "#7DD3FC", electronCount, ActionType.ADD_ELECTRON, ActionType.REMOVE_ELECTRON,
                        "électrons : ils font l'ion · E / Maj+E"));
        build.setAlignment(Pos.CENTER_LEFT);

        // La lumière : un réglage (dans une petite fenêtre) et une action.
        laserSwatch = new Rectangle(12, 12);
        laserSwatch.setArcWidth(4);
        laserSwatch.setArcHeight(4);
        laserLabel = new Label();
        laserLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-family: Consolas, monospace; -fx-font-size: 12px;");
        laserSlider = new Slider(Math.log10(LightPanel.MIN_NM), Math.log10(LightPanel.MAX_NM), Math.log10(laserNm));
        laserSlider.setPrefWidth(280);
        laserSlider.setFocusTraversable(false);
        laserSlider.getStyleClass().add("tl-speed");
        laserSlider.valueProperty().addListener((obs, old, value) -> {
            if (!updatingLaser) {
                tuneLaser(Math.pow(10, value.doubleValue()));
            }
        });
        lightButton = new Button();
        lightButton.getStyleClass().add("dash-button");
        lightButton.setFocusTraversable(false);
        lightButton.setTooltip(I18n.tooltip("régler la longueur d'onde du laser"));
        lightButton.setOnAction(event -> toggleLightPopup());
        Button fire = new Button("✦  Tirer un photon");
        fire.getStyleClass().add("dash-fire");
        fire.setFocusTraversable(false);
        fire.setTooltip(I18n.tooltip("un photon de cette longueur d'onde vers l'atome · touche L"));
        fire.setOnAction(event -> firePhoton());
        // Tirer sur le noyau : un neutron lent (calculé ici), ou une α (la chambre de collision la calcule).
        MenuButton shoot = new MenuButton("☢  Tirer sur le noyau");
        shoot.getStyleClass().add("dash-menu");
        shoot.setFocusTraversable(false);
        shoot.setTooltip(I18n.tooltip("un projectile vers le noyau : capture, fission, transmutation · touche B : un neutron"));
        MenuItem neutronShot = new MenuItem("n   ·  un neutron lent (0,0253 eV)");
        neutronShot.setOnAction(event -> fireNeutron());
        MenuItem alphaShot = new MenuItem("α   ·  une particule α de 5 MeV → chambre de collision");
        alphaShot.setOnAction(event -> fireAlpha());
        shoot.getItems().addAll(neutronShot, alphaShot);
        HBox light = new HBox(6, lightButton, fire, shoot);
        light.setAlignment(Pos.CENTER_LEFT);

        MenuButton labs = new MenuButton("Laboratoires");
        labs.getStyleClass().add("dash-menu");
        labs.setFocusTraversable(false);
        MenuItem fusion = new MenuItem("⚛   Fusion · fission · transmutation");
        fusion.setOnAction(event -> openFusion());
        MenuItem chain = new MenuItem("☢   Réaction en chaîne · masse critique");
        chain.setOnAction(event -> openChain());
        MenuItem molecule = new MenuItem("⌬   Laboratoire moléculaire");
        molecule.setOnAction(event -> openMolecule());
        MenuItem subatomic = new MenuItem("◈   Laboratoire subatomique");
        subatomic.setOnAction(event -> openSubatomic());
        MenuItem nucleon = new MenuItem("◉   Le monde subatomique (quarks, hadrons, partons)");
        nucleon.setOnAction(event -> openNucleon());
        labs.getItems().add(fusion);
        labs.getItems().add(molecule);
        if (CHAIN_SCREEN) labs.getItems().add(chain);
        if (SUBATOMIC_SCREEN) labs.getItems().add(subatomic);
        labs.getItems().add(nucleon);
        if (STAR_SCREEN) {
            MenuItem star = new MenuItem("★   Cœur d'étoile");
            star.setOnAction(event -> send(ActionType.STAR_START));
            labs.getItems().addAll(new SeparatorMenuItem(), star);
        }

        Tooltip.install(statusDot, statusTip);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox bar = new HBox(16, identity, tableButton, build, verticalRule(), light, grow, labs, statusDot);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.getStyleClass().add("dash-bar");
        return bar;
    }

    /** Un compteur : − valeur +, pour une sorte de particule. */
    private HBox stepper(String symbol, String colorHex, Label value, ActionType add, ActionType remove, String hint) {
        Label label = new Label(symbol);
        label.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-size: 12px; -fx-font-weight: bold;");
        label.setMinWidth(18);
        value.getStyleClass().add("dash-value");
        value.setMinWidth(30);
        value.setAlignment(Pos.CENTER);
        Button minus = new Button("−");
        Button plus = new Button("+");
        for (Button button : new Button[]{minus, plus}) {
            button.getStyleClass().add("step-button");
            button.setFocusTraversable(false);
        }
        minus.setOnAction(event -> send(remove));
        plus.setOnAction(event -> send(add));
        HBox box = new HBox(2, label, minus, value, plus);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("stepper");
        box.setPadding(new Insets(2, 4, 2, 9));
        Tooltip.install(box, I18n.tooltip(hint));
        return box;
    }

    private static Region verticalRule() {
        Region rule = new Region();
        rule.getStyleClass().add("view-sep");
        rule.setPrefHeight(26);
        rule.setMaxHeight(26);
        return rule;
    }

    /** Le réglage du laser, dans une petite fenêtre qui se ferme toute seule. */
    private void toggleLightPopup() {
        if (lightPopup == null) {
            Label title = new Label("LUMIÈRE · laser monochromatique");
            title.getStyleClass().add("dash-label");
            HBox row = new HBox(10, laserSwatch, laserSlider, laserLabel);
            row.setAlignment(Pos.CENTER_LEFT);
            Label hint = new Label("Astuce : dans l'onglet Lumière, un clic sur une raie accorde le laser exactement.");
            hint.getStyleClass().add("dash-note");
            Button flame = new Button("Chauffer dans une flamme");
            flame.getStyleClass().add("dash-button");
            flame.setFocusTraversable(false);
            flame.setTooltip(I18n.tooltip("une collision thermique excite l'atome, comme dans une flamme · touche F"));
            flame.setOnAction(event -> send(ActionType.HEAT));
            VBox content = new VBox(10, title, row, hint, flame);
            content.getStyleClass().add("light-popup");
            content.getStylesheets().add(stylesheet());
            lightPopup = new Popup();
            lightPopup.setAutoHide(true);
            lightPopup.getContent().add(content);
        }
        if (lightPopup.isShowing()) {
            lightPopup.hide();
            return;
        }
        javafx.geometry.Bounds bounds = lightButton.localToScreen(lightButton.getBoundsInLocal());
        lightPopup.show(lightButton, bounds.getMinX(), bounds.getMaxY() + 6);
    }

    /**
     * La barre de vue, posée sur la scène : l'échelle, ce que fait le glisser,
     * recentrer, des vues toutes faites, la rotation propre, le zoom.
     */
    private Region buildViewBar() {
        HBox scales = new HBox(2,
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
        ToggleButton rotate = viewToggle("⟳ Tourner", dragGroup, "glisser fait tourner l'atome en 3D",
                () -> simCanvas.setPanMode(false));
        ToggleButton pan = viewToggle("✥ Déplacer", dragGroup, "glisser déplace la vue (aussi : clic droit glissé)",
                () -> simCanvas.setPanMode(true));
        rotate.setSelected(true);
        Button recenter = viewButton("⌖ Recentrer", "ramène l'atome au centre, vu de trois quarts · double-clic sur la scène : centrer ce point",
                () -> simCanvas.recenter());
        MenuButton views = new MenuButton("Vue");
        views.getStyleClass().add("view-button");
        views.setFocusTraversable(false);
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
        autoSpin.setTooltip(I18n.tooltip("l'atome tourne lentement sur lui-même"));
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
        Button out = viewButton("−", "dézoomer · touche −", () -> simCanvas.nudgeZoom(0.6));
        Button in = viewButton("+", "zoomer · touche + (ou la molette)", () -> simCanvas.nudgeZoom(1.6));
        HBox bar = new HBox(4, scales, rule(), rotate, pan, rule(), recenter, views, autoSpin, valenceOnly, bohrView, rule(), out, in);
        bar.setAlignment(Pos.CENTER);
        bar.getStyleClass().add("view-bar");
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        bar.setPickOnBounds(false);
        return bar;
    }

    private static Region rule() {
        Region rule = new Region();
        rule.getStyleClass().add("view-sep");
        rule.setPrefHeight(22);
        rule.setMaxHeight(22);
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
            boolean on = I18n.source(button).contains("Déplacer") == simCanvas.isPanMode();
            if (button.isSelected() != on) {
                button.setSelected(on);
            }
        }
    }

    /** La barre du haut suit l'atome : symbole, nom, nuclide, compteurs. */
    private void updateTopBar(UniverseFrame frame) {
        String symbol = frame.getElement().getSymbol();
        elementSymbol.setText(symbol.isEmpty() ? "?" : symbol);
        String color = frame.getElement().getColorHex();
        elementSymbol.setStyle("-fx-background-color: " + (color.isEmpty() ? "#1B2740" : color + "33")
                + "; -fx-background-radius: 8; -fx-text-fill: " + (color.isEmpty() ? "#E6EDF7" : color)
                + "; -fx-font-size: 16px; -fx-font-weight: bold;");
        elementName.setText(frame.getElement().getName().isEmpty() ? "Chambre vide" : frame.getElement().getName());
        String nuclide = frame.getNucleus().getNuclide();
        int charge = frame.getAtom().getNetCharge();
        elementNuclide.setText((nuclide.isEmpty() ? "aucun noyau" : nuclide)
                + (charge == 0 ? "" : charge > 0 ? " · ion +" + charge : " · ion " + charge)
                + (frame.getNucleus().getUnstable() ? " · radioactif" : ""));
        protonCount.setText(Integer.toString(frame.getAtom().getProtons()));
        neutronCount.setText(Integer.toString(frame.getAtom().getNeutrons()));
        electronCount.setText(Integer.toString(frame.getAtom().getElectrons()));
    }

    private Region buildSidebar() {
        TabPane tabs = new TabPane(
                tab("Lumière", padded(lightPanel.getNode())),
                tab("Orbitale", padded(quantumView.getNode())),
                tab("Noyau", nucleusPanel.getNode()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setMinHeight(260);
        tabs.getSelectionModel().select(1);          // on ouvre sur l'orbitale : la forme du nuage, avant le spectre

        // Deux vues du journal : les logs seuls (complets), ou Genesis qui explique en mots simples, les logs en dessous.
        Label journalTitle = new Label("JOURNAL");
        journalTitle.getStyleClass().add("dash-label");
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
        journalHead.setPadding(new Insets(8, 10, 6, 12));
        // Genesis au-dessus du journal : sa bulle explique le dernier événement, ou celui qu'on clique
        guide = new HBox(6, genesis.getNode(), genesis.speech());
        guide.setAlignment(Pos.TOP_LEFT);
        guide.setPadding(new Insets(0, 10, 8, 4));
        HBox.setHgrow(genesis.speech(), Priority.ALWAYS);
        journal.setOnSelect(event -> genesis.explainNow(event));
        VBox journalBox = new VBox(journalHead, guide, journal.getNode());
        VBox.setVgrow(journal.getNode(), Priority.ALWAYS);
        journalBox.setStyle("-fx-background-color: #0B1120;");
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
        sidebar.setMinWidth(340);
        sidebar.setStyle("-fx-background-color: #0B1120;");
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
        box.setStyle("-fx-background-color: #05070F;");
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
        laserLabel.setText(Visuals.format(laserNm) + " nm · " + Visuals.format(1239.841984 / laserNm) + " eV"
                + (region.equals("visible") ? "" : " · " + region));
        Rectangle swatch = new Rectangle(12, 12, PhotonPacket.colour(laserNm));
        swatch.setArcWidth(4);
        swatch.setArcHeight(4);
        lightButton.setGraphic(swatch);
        lightButton.setText(Visuals.format(laserNm) + " nm" + (region.equals("visible") ? "" : " · " + region) + "  ▾");
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
        Thread startup = new Thread(() -> {
            try {
                engine.awaitReady(180);
                Platform.runLater(() -> {
                    if (!stopping) {
                        setStatus("Moteur Python prêt · connexion en cours…", "#FBBF24");
                        connect();
                    }
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException error) {
                Platform.runLater(() -> {
                    if (!stopping) {
                        loader.fail("Moteur Python indisponible", error.getMessage());
                        simCanvas.setStatusMessage(error.getMessage());
                        setStatus(error.getMessage(), "#F87171");
                    }
                });
            }
        }, "genesis-engine-startup");
        startup.setDaemon(true);
        startup.start();
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
            setStatus(I18n.text("Aucun noyau à viser : choisir d'abord un élément."), "#FBBF24");
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
        loader.show(heading, detail, ready, () -> {
            pendingLabStart = null;
            send(stop);
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
        loader.fail("Moteur obsolète", I18n.text("Le moteur envoie le protocole v") + received + I18n.text(", le client attend v")
                + EngineClient.PROTOCOL_VERSION + I18n.text(". Arrête-le et relance : docker compose up --build"));
    }

    private void onError(Throwable error) {
        engineConnected = false;
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
        boolean remove = event.isShiftDown();
        if (partonsActive) {
            switch (event.getCode()) {
                case ESCAPE -> send(ActionType.PARTONS_STOP);
                case R -> send(ActionType.PARTONS_RESET);
                case SPACE -> partonView.togglePlayback();
                default -> { }
            }
            return;
        }
        if (chainActive) {
            if (event.getCode() == KeyCode.ESCAPE) leaveLab(ActionType.CHAIN_STOP, f -> !f.getChain().getActive());
            return;
        }
        if (moleculeActive) {
            if (event.getCode() == KeyCode.ESCAPE) leaveLab(ActionType.MOLECULE_STOP, f -> !f.getMoleculeLab().getActive());
            return;
        }
        if (nucleonActive) {
            switch (event.getCode()) {
                case K, ESCAPE -> leaveLab(ActionType.NUCLEON_STOP, f -> !f.getNucleon().getActive());
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

    /** L'état de la connexion : un point de couleur dans la barre du haut, le détail au survol. */
    private void setStatus(String message, String colorHex) {
        statusTip.setText(message);
        statusDot.setFill(Color.web(colorHex));
    }

    @Override
    public void stop() {
        stopping = true;
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
