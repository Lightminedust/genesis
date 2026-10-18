package org.genesis.fusion;

import org.genesis.app.I18n;
import org.genesis.atom.NucleusPanel;
import org.genesis.atom.SimCanvas;
import org.genesis.common.Visuals;

import com.studio.genesis.grpc.StarFusion;
import com.studio.genesis.grpc.StarParticle;
import com.studio.genesis.grpc.StarReaction;
import com.studio.genesis.grpc.StarSpecies;
import com.studio.genesis.grpc.StarState;
import com.studio.genesis.grpc.UniverseFrame;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * Le cœur d'étoile : un plasma où les noyaux fusionnent.
 *
 * Le moteur calcule tout (réseau nucléaire REACLIB, ionisation de Saha,
 * pression de Fermi-Dirac, E = Δm·c²) ; ici on le rend visible. Au centre,
 * une fenêtre de quelques centaines de noyaux, à sa vraie taille physique :
 * chaque noyau a la couleur de son élément, ses électrons sont libres
 * (plasma) ou liés (atome), les charges + se repoussent, et chaque fusion
 * tirée par le moteur s'y produit sous nos yeux. Autour : l'énergie, la
 * pression, la matière créée et détruite, les réactions, et ce que tout cela
 * veut dire.
 */
public class StarView {

    private static final Color BACKGROUND = Color.web("#06070B");
    private static final Color CARD = Color.web("#0D1018", 0.94);
    private static final Color CARD_BORDER = Color.web("#232838");
    private static final Color TEXT = Color.web("#F1F5F9");
    private static final Color SOFT = Color.web("#AAB4C3");
    private static final Color MUTED = Color.web("#64708A");
    private static final Color GOLD = Color.web("#FCD34D");
    private static final Color ELECTRON = Color.web("#67E8F9");
    private static final Color NEUTRINO = Color.web("#A5B4FC");
    private static final Color GAMMA = Color.web("#FDE047");
    private static final Color POSITRON = Color.web("#F472B6");
    private static final String UI = "Segoe UI";
    private static final String MONO = "Consolas";

    private static final double PANEL = 410;
    private static final double HEADER = 74;
    private static final double STORY = 196;
    private static final double FUSE_S = 0.9;        // les réactifs se rejoignent
    private static final double BLAST_S = 3.2;       // l'éclair et son étiquette
    private static final double HYDROGEN_ATOM_M = 106e-12;   // diamètre d'un atome d'hydrogène (2 a₀)

    private final Canvas canvas = new Canvas(900, 600);
    private final GraphicsContext gc = canvas.getGraphicsContext2D();
    private final BorderPane root = new BorderPane();
    private final ComboBox<String> presets = new ComboBox<>();
    private final ComboBox<String> speeds = new ComboBox<>();
    private final Slider temperature = new Slider(3, Math.log10(3e9), Math.log10(1.57e7));
    private final Slider density = new Slider(-10, 7, Math.log10(150));
    private final Label temperatureLabel = new Label();
    private final Label densityLabel = new Label();
    private final Button pause = new Button("⏸");
    private boolean updating;

    private StarState state;
    private final Random random = new Random(7);
    private final Map<Integer, Body> bodies = new HashMap<>();
    private final Map<Integer, double[]> births = new HashMap<>();   // id -> {x, y, délai}
    private final List<Electron> electrons = new ArrayList<>();
    private final List<Blast> blasts = new ArrayList<>();
    private final List<Ray> rays = new ArrayList<>();
    private double clock;

    private static final class Body {
        int id;
        int species;
        double x;
        double y;
        double vx;
        double vy;
        double alpha;
        boolean dying;
        boolean fusing;
        double tx;
        double ty;
        double fuseT;
        double phase;
    }

    private static final class Electron {
        double x;
        double y;
        double vx;
        double vy;
    }

    private static final class Blast {
        double x;
        double y;
        double t;
        String label;
        String chain;
        double q;
        double nu;
        boolean weak;
        boolean positron;
        boolean gamma;
        boolean neutron;
    }

    private static final class Ray {
        double x;
        double y;
        double angle;
        double t;
        int kind;      // 0 neutrino, 1 gamma, 2 positon
    }

    public StarView(Runnable onExit, IntConsumer onPreset, DoubleConsumer onTemperature, DoubleConsumer onDensity,
                    IntConsumer onSpeed, Runnable onPause) {
        Pane holder = new Pane(canvas);
        holder.setStyle("-fx-background-color: #06070B;");
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        root.setCenter(holder);
        root.setBottom(controls(onExit, onPreset, onTemperature, onDensity, onSpeed, onPause));
        root.setStyle("-fx-background-color: #06070B;");
    }

    public Region getNode() {
        return root;
    }

    // =================================================================
    // Les commandes
    // =================================================================
    private Region controls(Runnable onExit, IntConsumer onPreset, DoubleConsumer onTemperature,
                            DoubleConsumer onDensity, IntConsumer onSpeed, Runnable onPause) {
        Button back = button("← Retour à l'atome", "#161B26", "#E2E8F0");
        back.setOnAction(event -> onExit.run());
        presets.setPrefWidth(250);
        presets.setOnAction(event -> {
            int index = presets.getSelectionModel().getSelectedIndex();
            if (!updating && index >= 0) {
                onPreset.accept(index);
            }
        });
        temperature.setPrefWidth(170);
        temperature.setFocusTraversable(false);
        temperature.valueProperty().addListener((obs, old, value) -> {
            double kelvin = Math.pow(10, value.doubleValue());
            temperatureLabel.setText(temperatureText(kelvin));
            if (!updating) {
                onTemperature.accept(kelvin);
            }
        });
        density.setPrefWidth(150);
        density.setFocusTraversable(false);
        density.valueProperty().addListener((obs, old, value) -> {
            double rho = Math.pow(10, value.doubleValue());
            densityLabel.setText(densityText(rho));
            if (!updating) {
                onDensity.accept(rho);
            }
        });
        for (Label label : new Label[]{temperatureLabel, densityLabel}) {
            label.setMinWidth(118);
            label.setStyle("-fx-text-fill: #FCD34D; -fx-font-family: Consolas; -fx-font-size: 12px;");
        }
        speeds.setPrefWidth(200);
        speeds.setOnAction(event -> {
            int index = speeds.getSelectionModel().getSelectedIndex();
            if (!updating && index >= 0) {
                onSpeed.accept(index);
            }
        });
        pause.setStyle(style("#161B26", "#E2E8F0"));
        pause.setFocusTraversable(false);
        pause.setTooltip(I18n.tooltip("pause / reprise du temps de l'étoile"));
        pause.setOnAction(event -> onPause.run());

        FlowPane bar = new FlowPane(18, 8, back, group("LIEU", presets),
                group("TEMPÉRATURE", temperature, temperatureLabel), group("DENSITÉ", density, densityLabel),
                group("TEMPS", pause, speeds));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 16, 10, 16));
        bar.setStyle("-fx-background-color: #0B0E15; -fx-border-color: #232838 transparent transparent transparent;"
                + " -fx-border-width: 1 0 0 0;");
        return bar;
    }

    private static String style(String background, String foreground) {
        return "-fx-background-color: " + background + "; -fx-text-fill: " + foreground + "; -fx-font-size: 12px;"
                + " -fx-font-weight: bold; -fx-background-radius: 6; -fx-padding: 6 10 6 10; -fx-cursor: hand;";
    }

    private static Button button(String text, String background, String foreground) {
        Button button = new Button(text);
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setStyle(style(background, foreground));
        return button;
    }

    private static HBox group(String title, Node... nodes) {
        Label label = new Label(title);
        label.setStyle("-fx-text-fill: #64708A; -fx-font-size: 9px; -fx-font-weight: bold;");
        HBox box = new HBox(6, label);
        box.getChildren().addAll(nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    static String temperatureText(double kelvin) {
        if (kelvin >= 1e9) {
            return Visuals.format(kelvin / 1e9) + " milliards K";
        }
        if (kelvin >= 1e6) {
            return Visuals.format(kelvin / 1e6) + " millions K";
        }
        return Visuals.format(kelvin) + " K";
    }

    static String densityText(double rho) {
        return NucleusPanel.scientific(rho) + " g/cm³";
    }

    // =================================================================
    // Les frames
    // =================================================================
    public void applyFrame(UniverseFrame frame) {
        StarState incoming = frame.getStar();
        if (!incoming.getActive()) {
            state = null;
            bodies.clear();
            births.clear();
            blasts.clear();
            rays.clear();
            return;
        }
        boolean presetChanged = state == null || state.getPreset() != incoming.getPreset();
        state = incoming;
        updating = true;
        if (presets.getItems().size() != incoming.getPresetsCount()) {
            presets.getItems().setAll(incoming.getPresetsList());
        }
        presets.getSelectionModel().select(incoming.getPreset());
        if (speeds.getItems().size() != incoming.getSpeedLabelsCount()) {
            speeds.getItems().setAll(incoming.getSpeedLabelsList());
        }
        speeds.getSelectionModel().select(incoming.getSpeedIndex());
        if (!temperature.isPressed() && !temperature.isValueChanging()) {
            temperature.setValue(Math.log10(incoming.getTemperatureK()));
        }
        if (!density.isPressed() && !density.isValueChanging()) {
            density.setValue(Math.log10(incoming.getDensityGCm3()));
        }
        temperatureLabel.setText(temperatureText(incoming.getTemperatureK()));
        densityLabel.setText(densityText(incoming.getDensityGCm3()));
        pause.setText(incoming.getPaused() ? "▶" : "⏸");
        updating = false;
        if (!incoming.getReady()) {
            return;
        }
        if (presetChanged) {
            bodies.clear();
            births.clear();
            blasts.clear();
            rays.clear();
        }
        for (StarFusion fusion : incoming.getFusionsList()) {
            startFusion(fusion);
        }
        reconcile(incoming);
    }

    /** Une fusion tirée par le moteur : les réactifs se rejoignent, puis l'éclair. */
    private void startFusion(StarFusion fusion) {
        List<Body> reactants = new ArrayList<>();
        double cx = 0;
        double cy = 0;
        int known = 0;
        for (int id : fusion.getConsumedList()) {
            Body body = bodies.get(id);
            if (body != null && !body.fusing) {
                cx += body.x;
                cy += body.y;
                known++;
            }
        }
        if (known == 0) {
            cx = 0.25 + 0.5 * random.nextDouble();
            cy = 0.25 + 0.5 * random.nextDouble();
        } else {
            cx /= known;
            cy /= known;
        }
        for (int id : fusion.getConsumedList()) {
            Body body = bodies.get(id);
            if (body == null || body.fusing) {
                // Venu de l'étoile autour : il entre près du lieu du choc.
                body = newBody(id, speciesOfConsumed(fusion, reactants.size()));
                double angle = random.nextDouble() * Math.PI * 2;
                body.x = cx + 0.18 * Math.cos(angle);
                body.y = cy + 0.18 * Math.sin(angle);
                body.alpha = 0.2;
                bodies.put(id, body);
            }
            body.fusing = true;
            body.fuseT = 0;
            body.tx = cx;
            body.ty = cy;
            reactants.add(body);
        }
        StarReaction reaction = state.getReactions(fusion.getReaction());
        Blast blast = new Blast();
        blast.x = cx;
        blast.y = cy;
        blast.t = -FUSE_S;
        blast.label = reaction.getLabel();
        blast.chain = reaction.getChain();
        blast.q = fusion.getQMev();
        blast.nu = fusion.getNeutrinoMev();
        blast.weak = reaction.getWeak();
        blast.positron = reaction.getLabel().contains("e⁺");
        blast.gamma = reaction.getLabel().contains("γ");
        blast.neutron = false;
        for (int species : reaction.getProductsList()) {
            blast.neutron |= state.getSpecies(species).getZ() == 0;
        }
        blasts.add(blast);
        for (int id : fusion.getCreatedList()) {
            births.put(id, new double[]{cx, cy, FUSE_S});
        }
    }

    private int speciesOfConsumed(StarFusion fusion, int index) {
        StarReaction reaction = state.getReactions(fusion.getReaction());
        return reaction.getReactants(Math.min(index, reaction.getReactantsCount() - 1));
    }

    private Body newBody(int id, int species) {
        Body body = new Body();
        body.id = id;
        body.species = species;
        double speed = baseSpeed() / Math.sqrt(Math.max(1, state.getSpecies(species).getA()));
        double angle = random.nextDouble() * Math.PI * 2;
        body.vx = speed * Math.cos(angle);
        body.vy = speed * Math.sin(angle);
        body.alpha = 0;
        body.phase = random.nextDouble() * Math.PI * 2;
        return body;
    }

    /** Ce que le moteur dit de la fenêtre : qui est là. */
    private void reconcile(StarState incoming) {
        Set<Integer> present = new HashSet<>();
        for (StarParticle particle : incoming.getParticlesList()) {
            present.add(particle.getId());
            Body body = bodies.get(particle.getId());
            if (body == null && !births.containsKey(particle.getId())) {
                body = newBody(particle.getId(), particle.getSpecies());
                body.x = random.nextDouble();
                body.y = random.nextDouble();
                bodies.put(body.id, body);
            } else if (body != null) {
                body.species = particle.getSpecies();
            }
        }
        for (Body body : bodies.values()) {
            if (!present.contains(body.id) && !body.fusing) {
                body.dying = true;
            }
        }
        births.keySet().removeIf(id -> !present.contains(id));
    }

    private double baseSpeed() {
        // Vitesse d'agitation, en échelle log : 1 km/s → lent, 10 000 km/s → vif.
        double v = state == null ? 1e5 : Math.max(1, state.getThermalSpeedMS());
        double t = Math.max(0, Math.min(1, (Math.log10(v) - 3) / 4.5));
        return 0.015 + 0.09 * t;
    }

    // =================================================================
    // L'animation
    // =================================================================
    public void tick(double delta) {
        clock += delta;
        if (state != null && state.getReady()) {
            double dt = state.getPaused() ? 0 : Math.min(delta, 0.05);
            step(dt);
        }
        render();
    }

    private double charge(int species) {
        return state.getSpecies(species).getZ();
    }

    private void step(double dt) {
        // Les naissances : les produits apparaissent au lieu de l'éclair.
        Iterator<Map.Entry<Integer, double[]>> pending = births.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<Integer, double[]> entry = pending.next();
            double[] birth = entry.getValue();
            birth[2] -= dt;
            if (birth[2] <= 0) {
                StarParticle particle = findParticle(entry.getKey());
                if (particle != null) {
                    Body body = newBody(entry.getKey(), particle.getSpecies());
                    body.x = birth[0];
                    body.y = birth[1];
                    body.alpha = 1;
                    double angle = random.nextDouble() * Math.PI * 2;
                    double kick = state.getSpecies(particle.getSpecies()).getZ() == 0 ? 0.9 : 0.35;
                    body.vx = kick * Math.cos(angle);
                    body.vy = kick * Math.sin(angle);
                    bodies.put(body.id, body);
                }
                pending.remove();
            }
        }

        List<Body> list = new ArrayList<>(bodies.values());
        double base = baseSpeed();
        // Répulsion électrique entre noyaux voisins (charges +), adoucie pour l'écran.
        for (int i = 0; i < list.size(); i++) {
            Body a = list.get(i);
            if (a.fusing || a.dying) {
                continue;
            }
            double qa = charge(a.species);
            if (qa == 0) {
                continue;
            }
            for (int j = i + 1; j < list.size(); j++) {
                Body b = list.get(j);
                if (b.fusing || b.dying) {
                    continue;
                }
                double qb = charge(b.species);
                if (qb == 0) {
                    continue;
                }
                double dx = wrap(a.x - b.x);
                double dy = wrap(a.y - b.y);
                double r2 = dx * dx + dy * dy;
                if (r2 > 0.012 || r2 < 1e-8) {
                    continue;
                }
                double force = 2.5e-5 * qa * qb / (r2 * Math.sqrt(r2) + 1e-6);
                double ma = Math.max(1, state.getSpecies(a.species).getA());
                double mb = Math.max(1, state.getSpecies(b.species).getA());
                a.vx += force * dx / ma * dt * 60;
                a.vy += force * dy / ma * dt * 60;
                b.vx -= force * dx / mb * dt * 60;
                b.vy -= force * dy / mb * dt * 60;
            }
        }
        Iterator<Body> iterator = bodies.values().iterator();
        while (iterator.hasNext()) {
            Body body = iterator.next();
            if (body.fusing) {
                body.fuseT += dt;
                double k = Math.min(1, body.fuseT / FUSE_S);
                body.x += (body.tx - body.x) * Math.min(1, dt * 6 * (0.3 + k));
                body.y += (body.ty - body.y) * Math.min(1, dt * 6 * (0.3 + k));
                body.alpha = Math.min(1, body.alpha + dt * 3);
                if (body.fuseT >= FUSE_S) {
                    iterator.remove();
                }
                continue;
            }
            if (body.dying) {
                body.alpha -= dt * 1.5;
                if (body.alpha <= 0) {
                    iterator.remove();
                    continue;
                }
            } else {
                body.alpha = Math.min(1, body.alpha + dt * 1.5);
            }
            // Agitation thermique : la vitesse revient vers celle de la température.
            double target = base / Math.sqrt(Math.max(1, state.getSpecies(body.species).getA()));
            double speed = Math.hypot(body.vx, body.vy);
            double relax = 1 + (target / Math.max(speed, 1e-6) - 1) * Math.min(1, dt * 1.2);
            body.vx = body.vx * relax + (random.nextDouble() - 0.5) * target * dt * 2;
            body.vy = body.vy * relax + (random.nextDouble() - 0.5) * target * dt * 2;
            body.x = mod(body.x + body.vx * dt);
            body.y = mod(body.y + body.vy * dt);
        }

        // Les électrons libres : autant que de charges arrachées dans la fenêtre.
        int wanted = 0;
        for (Body body : bodies.values()) {
            wanted += (int) Math.round(state.getSpecies(body.species).getMeanCharge());
        }
        wanted = Math.min(wanted, 320);
        while (electrons.size() < wanted) {
            Electron e = new Electron();
            e.x = random.nextDouble();
            e.y = random.nextDouble();
            double angle = random.nextDouble() * Math.PI * 2;
            e.vx = Math.cos(angle);
            e.vy = Math.sin(angle);
            electrons.add(e);
        }
        while (electrons.size() > wanted) {
            electrons.remove(electrons.size() - 1);
        }
        double fast = base * 7;
        for (Electron e : electrons) {
            double angle = Math.atan2(e.vy, e.vx) + (random.nextDouble() - 0.5) * 1.2;
            e.vx = Math.cos(angle);
            e.vy = Math.sin(angle);
            e.x = mod(e.x + e.vx * fast * dt);
            e.y = mod(e.y + e.vy * fast * dt);
        }

        for (Blast blast : blasts) {
            double before = blast.t;
            blast.t += dt;
            if (before < 0 && blast.t >= 0) {
                spawnRays(blast);
            }
        }
        blasts.removeIf(b -> b.t > BLAST_S);
        for (Ray ray : rays) {
            ray.t += dt;
        }
        rays.removeIf(r -> r.t > 2.2);
    }

    private void spawnRays(Blast blast) {
        double angle = random.nextDouble() * Math.PI * 2;
        if (blast.nu > 0) {
            rays.add(ray(blast, angle, 0));
        }
        if (blast.gamma) {
            rays.add(ray(blast, angle + Math.PI * 0.8, 1));
        }
        if (blast.positron) {
            rays.add(ray(blast, angle + Math.PI, 2));
        }
    }

    private Ray ray(Blast blast, double angle, int kind) {
        Ray ray = new Ray();
        ray.x = blast.x;
        ray.y = blast.y;
        ray.angle = angle;
        ray.kind = kind;
        return ray;
    }

    private StarParticle findParticle(int id) {
        for (StarParticle particle : state.getParticlesList()) {
            if (particle.getId() == id) {
                return particle;
            }
        }
        return null;
    }

    private static double mod(double v) {
        return v - Math.floor(v);
    }

    private static double wrap(double d) {
        return d - Math.rint(d);
    }

    // =================================================================
    // Le rendu
    // =================================================================
    private double winX;
    private double winY;
    private double winW;
    private double winH;

    private void render() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, width, height);
        if (state == null) {
            return;
        }
        winX = 20;
        winY = HEADER;
        winW = width - PANEL - 44;
        winH = Math.max(160, height - HEADER - STORY - 26);
        drawHeader(width);
        if (!state.getReady()) {
            gc.setFont(Font.font(UI, FontWeight.BOLD, 16));
            gc.setFill(SOFT);
            gc.setTextAlign(TextAlignment.CENTER);
            I18n.fillText(gc, "Lecture de la bibliothèque nucléaire REACLIB…", winX + winW / 2, winY + winH / 2);
            return;
        }
        drawWindow();
        drawStory(winX, winY + winH + 14, winW, height - (winY + winH + 14) - 10);
        double x = width - PANEL - 12;
        double y = 12;
        y = drawEnergy(x, y, PANEL) + 10;
        y = drawPressure(x, y, PANEL) + 10;
        y = drawMatter(x, y, PANEL, (height - y) * 0.6) + 10;
        drawReactions(x, y, PANEL, height - y - 12);
    }

    private void drawHeader(double width) {
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 22));
        gc.setFill(TEXT);
        String name = state.getPresetsCount() > state.getPreset() ? state.getPresets(state.getPreset()) : "";
        I18n.fillText(gc, "Cœur d'étoile · " + name, 22, 12);
        gc.setFont(Font.font(UI, 13));
        gc.setFill(SOFT);
        I18n.fillText(gc, state.getAbout(), 22, 42);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(Font.font(MONO, FontWeight.BOLD, 14));
        gc.setFill(GOLD);
        double right = width - PANEL - 28;
        I18n.fillText(gc, temperatureText(state.getTemperatureK()) + " · " + densityText(state.getDensityGCm3()), right, 14);
        gc.setFont(Font.font(MONO, 12));
        gc.setFill(SOFT);
        String speed = state.getSpeedLabelsCount() > state.getSpeedIndex() ? state.getSpeedLabels(state.getSpeedIndex()) : "";
        I18n.fillText(gc, (state.getPaused() ? "⏸ en pause · " : "▶ " + speed + " · ") + "âge " + Visuals.duration(state.getAgeS()),
                right, 38);
        gc.setTextBaseline(VPos.BASELINE);
    }

    /** La couleur d'un corps chauffé à T (rouge sombre → blanc → bleu), pour teinter le plasma. */
    private static Color glowColor(double kelvin) {
        double t = Math.log10(Math.max(kelvin, 1000));
        if (t < 3.6) {
            return Color.web("#7F1D1D").interpolate(Color.web("#EA580C"), (t - 3) / 0.6);
        }
        if (t < 4.2) {
            return Color.web("#EA580C").interpolate(Color.web("#FDE68A"), (t - 3.6) / 0.6);
        }
        if (t < 7) {
            return Color.web("#FDE68A").interpolate(Color.web("#E0F2FE"), (t - 4.2) / 2.8);
        }
        return Color.web("#E0F2FE").interpolate(Color.web("#A5B4FC"), Math.min(1, (t - 7) / 2.5));
    }

    private void drawWindow() {
        Color glow = glowColor(state.getTemperatureK());
        double heat = Math.max(0, Math.min(1, (Math.log10(state.getTemperatureK()) - 3) / 6));
        gc.setFill(new RadialGradient(0, 0, winX + winW / 2, winY + winH / 2, Math.max(winW, winH) * 0.7, false,
                CycleMethod.NO_CYCLE, new Stop(0, glow.deriveColor(0, 1, 0.35 + 0.25 * heat, 0.55)),
                new Stop(1, Color.web("#06070B"))));
        gc.fillRoundRect(winX, winY, winW, winH, 18, 18);
        gc.setStroke(Color.web("#2B3346"));
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(winX, winY, winW, winH, 18, 18);

        gc.save();
        gc.beginPath();
        gc.rect(winX + 1, winY + 1, winW - 2, winH - 2);
        gc.clip();
        // Électrons libres.
        gc.setFill(ELECTRON.deriveColor(0, 1, 1, 0.75));
        for (Electron e : electrons) {
            gc.fillOval(winX + e.x * winW - 1.6, winY + e.y * winH - 1.6, 3.2, 3.2);
        }
        // Noyaux, et leurs électrons liés s'il en reste.
        for (Body body : bodies.values()) {
            drawBody(body);
        }
        for (Ray ray : rays) {
            drawRay(ray);
        }
        for (Blast blast : blasts) {
            drawBlast(blast);
        }
        gc.restore();
        drawScale();
        drawWindowNote();
    }

    private double radius(int a) {
        double unit = Math.min(winW, winH) / 70;
        return unit * (1.1 + 0.9 * Math.cbrt(Math.max(1, a)));
    }

    private static Color colorOf(int z, int a) {
        return switch (z) {
            case 0 -> Color.web("#CBD5E1");
            case 1 -> a == 1 ? Color.web("#FB923C") : a == 2 ? Color.web("#FDBA74") : Color.web("#FDE68A");
            case 2 -> a == 3 ? Color.web("#7DD3FC") : Color.web("#38BDF8");
            case 3 -> Color.web("#C084FC");
            case 4 -> Color.web("#A78BFA");
            case 5 -> Color.web("#818CF8");
            case 6 -> Color.web("#E7E5E4");
            case 7 -> Color.web("#60A5FA");
            case 8 -> Color.web("#F87171");
            case 9 -> Color.web("#A3E635");
            case 10 -> Color.web("#FACC15");
            case 11 -> Color.web("#F472B6");
            default -> Color.web("#34D399");
        };
    }

    /** « ⁴He », « ¹²C » ; p, D, T, n restent tels quels. */
    private static String shortLabel(StarSpecies species) {
        String label = species.getLabel();
        if (!label.contains("-")) {
            return label;
        }
        String[] parts = label.split("-");
        return Visuals.superscript(Integer.parseInt(parts[1])) + parts[0];
    }

    private void drawBody(Body body) {
        StarSpecies species = state.getSpecies(body.species);
        double x = winX + body.x * winW;
        double y = winY + body.y * winH;
        double r = radius(species.getA());
        Color color = colorOf(species.getZ(), species.getA());
        gc.setGlobalAlpha(Math.max(0, Math.min(1, body.alpha)));
        // Électrons encore liés : l'atome existe. Nuage électronique, orbite, et le noyau au centre.
        int bound = (int) Math.round(species.getZ() - species.getMeanCharge());
        if (bound > 0) {
            double cloud = r * 2.4;
            gc.setFill(new RadialGradient(0, 0, x, y, cloud, false, CycleMethod.NO_CYCLE,
                    new Stop(0, ELECTRON.deriveColor(0, 1, 1, 0.42)), new Stop(0.75, ELECTRON.deriveColor(0, 1, 1, 0.16)),
                    new Stop(1, ELECTRON.deriveColor(0, 1, 1, 0))));
            gc.fillOval(x - cloud, y - cloud, cloud * 2, cloud * 2);
            gc.setStroke(ELECTRON.deriveColor(0, 1, 1, 0.45));
            gc.setLineWidth(0.8);
            gc.strokeOval(x - cloud * 0.7, y - cloud * 0.6, cloud * 1.4, cloud * 1.2);
            gc.setFill(Color.WHITE);
            for (int k = 0; k < Math.min(bound, 8); k++) {
                double angle = clock * 2.2 + body.phase + k * Math.PI * 2 / bound;
                gc.fillOval(x + cloud * 0.7 * Math.cos(angle) - 2, y + cloud * 0.6 * Math.sin(angle) - 2, 4, 4);
            }
            r *= 0.45;                    // le noyau, minuscule au centre de son atome
        }
        gc.setFill(new RadialGradient(0, 0, x, y, r * 2.2, false, CycleMethod.NO_CYCLE,
                new Stop(0, color.deriveColor(0, 1, 1, 0.45)), new Stop(1, color.deriveColor(0, 1, 1, 0))));
        gc.fillOval(x - r * 2.2, y - r * 2.2, r * 4.4, r * 4.4);
        gc.setFill(new RadialGradient(0, 0, x - r * 0.35, y - r * 0.35, r * 1.3, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE), new Stop(0.45, color), new Stop(1, color.deriveColor(0, 1, 0.55, 1))));
        gc.fillOval(x - r, y - r, r * 2, r * 2);
        if (species.getZ() == 0) {
            gc.setStroke(Color.web("#E2E8F0", 0.8));
            gc.setLineWidth(1);
            gc.strokeOval(x - r, y - r, r * 2, r * 2);
        }
        if (bound > 0) {
            // Un atome (neutre ou ion) : on écrit son symbole chimique à côté.
            gc.setFont(Font.font(UI, FontWeight.BOLD, 10));
            gc.setFill(Color.web("#E0F2FE"));
            gc.setTextAlign(TextAlignment.LEFT);
            String charge = bound < species.getZ() ? Visuals.superscript(species.getZ() - bound) + "⁺" : "";
            I18n.fillText(gc, atomSymbol(species) + charge, x + r * 2.6 + 2, y - r * 2.2);
        } else if (r >= 6) {
            gc.setFont(Font.font(UI, FontWeight.BOLD, Math.max(8, Math.min(12, r * 0.95))));
            gc.setFill(Color.web("#0B0E15"));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            I18n.fillText(gc, shortLabel(species), x, y + 0.5);
            gc.setTextBaseline(VPos.BASELINE);
        }
        gc.setGlobalAlpha(1);
    }

    private static String atomSymbol(StarSpecies species) {
        String label = species.getLabel();
        if (label.equals("p") || label.equals("D") || label.equals("T")) {
            return label.equals("p") ? "H" : label;
        }
        return label.contains("-") ? label.substring(0, label.indexOf('-')) : label;
    }

    private void drawBlast(Blast blast) {
        double x = winX + blast.x * winW;
        double y = winY + blast.y * winH;
        if (blast.t < 0) {
            // Les réactifs se rejoignent : la barrière électrique résiste, la lueur monte.
            double k = 1 + blast.t / FUSE_S;
            double r = 10 + 26 * k;
            gc.setStroke(Color.web("#F87171", 0.35 + 0.4 * k));
            gc.setLineWidth(1.4);
            gc.setLineDashes(3, 4);
            gc.strokeOval(x - r, y - r, r * 2, r * 2);
            gc.setLineDashes((double[]) null);
            return;
        }
        double t = blast.t;
        double flash = Math.max(0, 1 - t * 2.5);
        if (flash > 0) {
            double r = 12 + 70 * (1 - flash);
            gc.setFill(new RadialGradient(0, 0, x, y, r, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.web("#FFFFFF", flash)), new Stop(0.4, GOLD.deriveColor(0, 1, 1, 0.7 * flash)),
                    new Stop(1, Color.web("#F97316", 0))));
            gc.fillOval(x - r, y - r, r * 2, r * 2);
        }
        if (blasts.indexOf(blast) < blasts.size() - 2) {
            return;                       // une étiquette à la fois, ou deux : sinon on ne lit rien
        }
        double alpha = Math.min(1, Math.max(0, (BLAST_S - t) / 0.8));
        gc.setGlobalAlpha(alpha);
        String line = blast.label;
        String energy = (blast.q >= 0 ? "+" : "") + Visuals.format(blast.q) + " MeV"
                + (blast.nu > 0 ? "  (ν emporte " + Visuals.format(blast.nu) + ")" : "");
        gc.setFont(Font.font(UI, FontWeight.BOLD, 13));
        double w = Math.max(line.length() * 7.4, energy.length() * 6.8) + 22;
        double bx = Math.max(winX + 6, Math.min(winX + winW - w - 6, x - w / 2));
        double by = y - 64 < winY + 6 ? y + 26 : y - 64;
        gc.setFill(Color.web("#0B0E15", 0.86));
        gc.fillRoundRect(bx, by, w, 42, 10, 10);
        gc.setStroke(GOLD.deriveColor(0, 1, 1, 0.55));
        gc.setLineWidth(1);
        gc.strokeRoundRect(bx, by, w, 42, 10, 10);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFill(TEXT);
        I18n.fillText(gc, line, bx + w / 2, by + 17);
        gc.setFont(Font.font(MONO, FontWeight.BOLD, 12));
        gc.setFill(GOLD);
        I18n.fillText(gc, energy, bx + w / 2, by + 34);
        gc.setGlobalAlpha(1);
    }

    private void drawRay(Ray ray) {
        double x0 = winX + ray.x * winW;
        double y0 = winY + ray.y * winH;
        double speed = ray.kind == 2 ? 70 : 520;
        double head = ray.t * speed;
        double tail = Math.max(0, head - (ray.kind == 0 ? 160 : 90));
        double cos = Math.cos(ray.angle);
        double sin = Math.sin(ray.angle);
        double fade = Math.max(0, 1 - ray.t / 2.2);
        if (ray.kind == 0) {
            gc.setStroke(NEUTRINO.deriveColor(0, 1, 1, 0.8 * fade));
            gc.setLineWidth(1.4);
            gc.setLineDashes(6, 6);
            gc.strokeLine(x0 + tail * cos, y0 + tail * sin, x0 + head * cos, y0 + head * sin);
            gc.setLineDashes((double[]) null);
            label(x0 + head * cos, y0 + head * sin - 8, "ν s'échappe", NEUTRINO, fade);
        } else if (ray.kind == 1) {
            gc.setStroke(GAMMA.deriveColor(0, 1, 1, 0.85 * fade));
            gc.setLineWidth(1.6);
            double px = x0 + tail * cos;
            double py = y0 + tail * sin;
            for (double s = tail; s <= head; s += 4) {
                double wave = Math.sin(s * 0.25) * 4;
                double nx = x0 + s * cos - wave * sin;
                double ny = y0 + s * sin + wave * cos;
                gc.strokeLine(px, py, nx, ny);
                px = nx;
                py = ny;
            }
            label(x0 + head * cos, y0 + head * sin - 8, "γ", GAMMA, fade);
        } else {
            // Le positon rencontre un électron : annihilation, deux photons.
            double d = Math.min(head, 46);
            double px = x0 + d * cos;
            double py = y0 + d * sin;
            if (head < 46) {
                gc.setFill(POSITRON);
                gc.fillOval(px - 3, py - 3, 6, 6);
                label(px, py - 8, "e⁺", POSITRON, 1);
            } else {
                double k = Math.min(1, (head - 46) / 260);
                double flash = Math.max(0, 1 - k * 3);
                gc.setFill(Color.web("#FFFFFF", flash));
                gc.fillOval(px - 8, py - 8, 16, 16);
                gc.setStroke(GAMMA.deriveColor(0, 1, 1, 0.8 * (1 - k)));
                gc.setLineWidth(1.4);
                double reach = 30 + k * 180;
                gc.strokeLine(px, py, px - reach * sin, py + reach * cos);
                gc.strokeLine(px, py, px + reach * sin, py - reach * cos);
                label(px, py - 10, "e⁺ + e⁻ → 2 γ", GAMMA, 1 - k);
            }
        }
    }

    private void label(double x, double y, String text, Color color, double alpha) {
        gc.setGlobalAlpha(Math.max(0, Math.min(1, alpha)));
        gc.setFont(Font.font(UI, FontWeight.BOLD, 11));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFill(color);
        I18n.fillText(gc, text, x, y);
        gc.setGlobalAlpha(1);
    }

    /** L'échelle réelle de la fenêtre, et un atome d'hydrogène pour comparer. */
    private void drawScale() {
        double side = state.getWindowSizeM();
        if (side <= 0) {
            return;
        }
        double perMetre = winH / side;
        double x = winX + 16;
        double y = winY + winH - 16;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(MONO, 11));
        gc.setFill(Color.web("#0B0E15", 0.8));
        gc.fillRoundRect(x - 8, y - 32, 380, 40, 8, 8);
        gc.setFill(SOFT);
        I18n.fillText(gc, "hauteur de la fenêtre : " + Visuals.formatLength(side)
                + " · " + state.getParticlesCount() + " noyaux (agrandis)", x, y - 16);
        double atom = HYDROGEN_ATOM_M * perMetre;
        if (atom < winH * 0.9 && atom > 6) {
            double cx = winX + winW - atom / 2 - 18;
            double cy = winY + atom / 2 + 18;
            gc.setStroke(ELECTRON.deriveColor(0, 1, 1, 0.6));
            gc.setLineDashes(5, 5);
            gc.setLineWidth(1.2);
            gc.strokeOval(cx - atom / 2, cy - atom / 2, atom, atom);
            gc.setLineDashes((double[]) null);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setFill(ELECTRON);
            I18n.fillText(gc, "taille d'un atome d'H", cx, cy + atom / 2 + 14);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFill(SOFT);
            I18n.fillText(gc, "pointillés : un atome d'hydrogène (106 pm) à la même échelle", x, y);
        } else if (atom >= winH * 0.9) {
            gc.setFill(ELECTRON);
            I18n.fillText(gc, "un atome d'H (106 pm) ne tiendrait pas dans la fenêtre", x, y);
        } else {
            I18n.fillText(gc, "un atome d'H (106 pm) : " + Visuals.format(atom) + " px, invisible à cette échelle", x, y);
        }
    }

    private void drawWindowNote() {
        String text;
        if (state.getWindowRate() > 0.05) {
            text = "≈ " + NucleusPanel.scientific(state.getWindowRate()) + " fusions par seconde dans cette fenêtre"
                    + (state.getWindowRate() > 2 ? " · on en montre une par seconde environ" : "");
        } else if (state.getTemperatureK() < 1e6) {
            text = "trop froid : aucune fusion";
        } else {
            text = "les fusions sont rares à cette vitesse : accélère le temps";
        }
        gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(Color.web("#0B0E15", 0.8));
        double w = text.length() * 6.6 + 18;
        gc.fillRoundRect(winX + 10, winY + 10, w, 24, 8, 8);
        gc.setFill(GOLD);
        I18n.fillText(gc, text, winX + 19, winY + 27);
    }

    // =================================================================
    // Ce qui se passe, en mots
    // =================================================================
    private void drawStory(double x, double y, double w, double h) {
        card(x, y, w, h, "CE QUI SE PASSE", GOLD);
        double column = (w - 24) / 2;
        double line = y + 40;
        double left = x + 14;
        double bottom = y + h - 6;
        for (String sentence : state.getStoryList()) {
            String head = "";
            String body = sentence;
            int split = sentence.indexOf(" — ");
            if (split > 0) {
                head = sentence.substring(0, split);
                body = sentence.substring(split + 3);
            }
            List<String> rows = SimCanvas.wrap(body, (int) ((column - 12) / 5.7));
            double needed = 14 + 14 * rows.size();
            if (line + needed > bottom && left == x + 14) {
                left += column + 4;
                line = y + 40;
            } else if (line + needed > bottom) {
                break;
            }
            gc.setTextAlign(TextAlignment.LEFT);
            if (!head.isEmpty()) {
                gc.setFont(Font.font(UI, FontWeight.BOLD, 10));
                gc.setFill(headColor(head));
                I18n.fillText(gc, head, left, line);
                line += 14;
            }
            gc.setFont(Font.font(UI, 11.5));
            gc.setFill(TEXT);
            for (String row : rows) {
                I18n.fillText(gc, row, left, line);
                line += 14;
            }
            line += 6;
        }
    }

    private static Color headColor(String head) {
        return switch (head) {
            case "PLASMA", "GAZ D'ATOMES", "GAZ PARTIELLEMENT IONISÉ" -> ELECTRON;
            case "FUSION", "PAS DE FUSION" -> Color.web("#FB923C");
            case "ÉNERGIE" -> GOLD;
            case "PRESSION" -> Color.web("#A5B4FC");
            case "INTERACTION FAIBLE", "NEUTRON" -> POSITRON;
            default -> SOFT;
        };
    }

    // =================================================================
    // Les panneaux
    // =================================================================
    private void card(double x, double y, double w, double h, String title, Color accent) {
        gc.setFill(CARD);
        gc.fillRoundRect(x, y, w, h, 14, 14);
        gc.setStroke(CARD_BORDER);
        gc.setLineWidth(1);
        gc.strokeRoundRect(x, y, w, h, 14, 14);
        gc.setFill(accent);
        gc.fillRoundRect(x + 14, y + 13, 3, 12, 2, 2);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 11));
        gc.setFill(SOFT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        I18n.fillText(gc, title, x + 24, y + 19);
        gc.setTextBaseline(VPos.BASELINE);
    }

    /** La jauge d'énergie : puissance par kilo, sur une échelle de 10⁻⁶ à 10¹⁴ W/kg. */
    private double drawEnergy(double x, double y, double w) {
        double h = 150;
        card(x, y, w, h, "ÉNERGIE CALCULÉE", GOLD);
        double power = state.getPowerWKg();
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(MONO, FontWeight.BOLD, 22));
        gc.setFill(power > 0 ? GOLD : MUTED);
        I18n.fillText(gc, power > 0 ? NucleusPanel.scientific(power) + " W/kg" : "0 W/kg", x + 16, y + 56);
        gc.setFont(Font.font(UI, 11));
        gc.setFill(SOFT);
        I18n.fillText(gc, "chaleur dégagée par chaque kilo de matière", x + 16, y + 72);

        double left = x + 16;
        double right = x + w - 16;
        double top = y + 84;
        double low = -6;
        double high = 14;
        gc.setFill(new LinearGradient(left, 0, right, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#1E293B")), new Stop(0.5, Color.web("#B45309")), new Stop(1, Color.web("#FDE047"))));
        gc.fillRoundRect(left, top, right - left, 10, 5, 5);
        double[][] marks = {{-2.77, 0}, {0.05, 1}, {12.3, 2}};
        String[] names = {"cœur du Soleil", "corps humain", "réacteur D-T"};
        gc.setFont(Font.font(UI, 10));
        for (double[] mark : marks) {
            double mx = left + (mark[0] - low) / (high - low) * (right - left);
            gc.setStroke(Color.web("#F1F5F9", 0.6));
            gc.setLineWidth(1);
            gc.strokeLine(mx, top - 3, mx, top + 13);
            gc.setFill(MUTED);
            gc.setTextAlign(mark[1] == 0 ? TextAlignment.RIGHT : mark[1] == 1 ? TextAlignment.LEFT
                    : TextAlignment.CENTER);
            I18n.fillText(gc, names[(int) mark[1]], mark[1] == 2 ? Math.min(right - 34, mx) : mx + (mark[1] == 0 ? -3 : 3),
                    top + 26);
        }
        if (power > 0) {
            double mx = left + (Math.log10(power) - low) / (high - low) * (right - left);
            mx = Math.max(left, Math.min(right, mx));
            gc.setFill(Color.WHITE);
            gc.fillPolygon(new double[]{mx - 6, mx + 6, mx}, new double[]{top - 9, top - 9, top - 1}, 3);
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(MONO, 11));
        gc.setFill(TEXT);
        double total = state.getEnergyJKg();
        double nu = state.getNeutrinoWKg();
        String share = power + nu > 0 ? " · neutrinos " + Visuals.format(100 * nu / (power + nu)) + " % (perdus)" : "";
        I18n.fillText(gc, "depuis le début : " + NucleusPanel.scientific(total) + " J/kg" + share, x + 16, y + h - 14);
        return y + h;
    }

    private double drawPressure(double x, double y, double w) {
        double h = 112;
        card(x, y, w, h, "PRESSION CALCULÉE", Color.web("#A5B4FC"));
        double total = state.getPressurePa();
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(MONO, FontWeight.BOLD, 18));
        gc.setFill(TEXT);
        I18n.fillText(gc, NucleusPanel.scientific(total) + " Pa", x + 16, y + 52);
        gc.setFont(Font.font(UI, 11));
        gc.setFill(SOFT);
        I18n.fillText(gc, "= " + NucleusPanel.scientific(total / 101325) + " atmosphères", x + 190, y + 52);
        double left = x + 16;
        double width = w - 32;
        double bar = y + 62;
        double[] parts = {state.getPressureIonsPa(), state.getPressureElectronsPa(), state.getPressureRadiationPa()};
        Color[] colors = {Color.web("#FB923C"), ELECTRON, GAMMA};
        String[] names = {"noyaux", "électrons", "lumière"};
        double cursor = left;
        for (int i = 0; i < 3; i++) {
            double part = total > 0 ? parts[i] / total * width : 0;
            gc.setFill(colors[i]);
            gc.fillRect(cursor, bar, part, 10);
            cursor += part;
        }
        gc.setFont(Font.font(UI, 10));
        double lx = left;
        for (int i = 0; i < 3; i++) {
            gc.setFill(colors[i]);
            gc.fillOval(lx, bar + 17, 7, 7);
            gc.setFill(SOFT);
            String text = names[i] + " " + (total > 0 ? Visuals.format(100 * parts[i] / total) : "0") + " %";
            I18n.fillText(gc, text, lx + 10, bar + 24);
            lx += text.length() * 5.6 + 26;
        }
        List<String> badges = new ArrayList<>();
        if (state.getDegeneracy() > 0) {
            badges.add("électrons dégénérés (Pauli)");
        }
        if (state.getOpticalDepth() <= 1) {
            badges.add("transparent : la lumière s'échappe");
        }
        if (state.getIonizedFraction() < 0.01) {
            badges.add("atomes neutres");
        } else if (state.getIonizedFraction() < 0.99) {
            badges.add(Visuals.format(100 * state.getIonizedFraction()) + " % ionisé");
        } else {
            badges.add("100 % ionisé");
        }
        double bx = left;
        gc.setFont(Font.font(UI, FontWeight.BOLD, 10));
        for (String badge : badges) {
            double bw = badge.length() * 5.5 + 14;
            gc.setFill(Color.web("#1E2433"));
            gc.fillRoundRect(bx, y + h - 22, bw, 16, 8, 8);
            gc.setFill(Color.web("#C7D2FE"));
            I18n.fillText(gc, badge, bx + 7, y + h - 10);
            bx += bw + 6;
        }
        return y + h;
    }

    /** La matière : chaque espèce, sa part de la masse, ce qui a été créé ou détruit. */
    private double drawMatter(double x, double y, double w, double h) {
        card(x, y, w, h, "MATIÈRE · créée ▲ / détruite ▼", Color.web("#34D399"));
        gc.setFont(Font.font(UI, 10));
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        I18n.fillText(gc, "barre : part de la masse (log) · trait : au départ · ± : dans la fenêtre", x + 16, y + 42);
        List<StarSpecies> shown = new ArrayList<>();
        for (StarSpecies species : state.getSpeciesList()) {
            if (species.getMassFraction() > 1e-14 || species.getInitialMassFraction() > 0
                    || species.getBoxCreated() > 0) {
                shown.add(species);
            }
        }
        shown.sort((a, b) -> Double.compare(b.getMassFraction(), a.getMassFraction()));
        double row = y + 60;
        double barLeft = x + 92;
        double barRight = x + w - 130;
        for (StarSpecies species : shown) {
            if (row > y + h - 8) {
                break;
            }
            Color color = colorOf(species.getZ(), species.getA());
            gc.setFill(color);
            gc.fillOval(x + 16, row - 9, 10, 10);
            gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
            gc.setFill(TEXT);
            I18n.fillText(gc, species.getLabel(), x + 32, row);
            gc.setFont(Font.font(MONO, 9));
            gc.setFill(MUTED);
            if (!species.getSpinParity().isEmpty()) {
                I18n.fillText(gc, species.getSpinParity(), x + 66, row);
            }
            double frac = Math.max(-14, Math.log10(Math.max(species.getMassFraction(), 1e-16)));
            double barW = (frac + 14) / 14 * (barRight - barLeft);
            gc.setFill(Color.web("#1A2030"));
            gc.fillRoundRect(barLeft, row - 9, barRight - barLeft, 9, 4, 4);
            gc.setFill(color.deriveColor(0, 1, 0.9, 0.9));
            gc.fillRoundRect(barLeft, row - 9, Math.max(2, barW), 9, 4, 4);
            if (species.getInitialMassFraction() > 0) {
                double ix = barLeft + (Math.log10(species.getInitialMassFraction()) + 14) / 14 * (barRight - barLeft);
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(1.5);
                gc.strokeLine(ix, row - 12, ix, row + 2);
            }
            gc.setFont(Font.font(MONO, 10));
            gc.setFill(SOFT);
            I18n.fillText(gc, fraction(species.getMassFraction()), barRight + 6, row);
            int created = species.getBoxCreated();
            int destroyed = species.getBoxDestroyed();
            if (created > 0 || destroyed > 0) {
                gc.setTextAlign(TextAlignment.RIGHT);
                gc.setFill(created >= destroyed ? Color.web("#34D399") : Color.web("#F87171"));
                I18n.fillText(gc, (created > 0 ? "▲" + created : "") + (destroyed > 0 ? " ▼" + destroyed : ""), x + w - 12, row);
                gc.setTextAlign(TextAlignment.LEFT);
            }
            row += 17;
        }
        return y + h;
    }

    private static String fraction(double x) {
        if (x >= 0.001) {
            return Visuals.format(100 * x) + " %";
        }
        return NucleusPanel.scientific(x);
    }

    private void drawReactions(double x, double y, double w, double h) {
        if (h < 60) {
            return;
        }
        card(x, y, w, h, "RÉACTIONS EN COURS (par kg et par seconde)", Color.web("#FB923C"));
        List<StarReaction> active = new ArrayList<>(state.getReactionsList());
        active.removeIf(r -> r.getPerKgS() <= 0);
        active.sort((a, b) -> Double.compare(b.getPowerWKg(), a.getPowerWKg()));
        double total = Math.max(state.getPowerWKg(), 1e-300);
        double row = y + 44;
        gc.setTextAlign(TextAlignment.LEFT);
        if (active.isEmpty()) {
            gc.setFont(Font.font(UI, 12));
            gc.setFill(MUTED);
            I18n.fillText(gc, "aucune : trop froid pour que les noyaux se touchent", x + 16, row + 4);
            return;
        }
        for (StarReaction reaction : active) {
            if (row > y + h - 10) {
                break;
            }
            gc.setFont(Font.font(UI, FontWeight.BOLD, 11));
            gc.setFill(TEXT);
            I18n.fillText(gc, reaction.getLabel(), x + 16, row);
            gc.setFont(Font.font(MONO, 10));
            gc.setFill(GOLD);
            gc.setTextAlign(TextAlignment.RIGHT);
            I18n.fillText(gc, Visuals.format(100 * Math.max(0, reaction.getPowerWKg()) / total) + " %", x + w - 14, row);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFont(Font.font(UI, 10));
            gc.setFill(MUTED);
            I18n.fillText(gc, reaction.getChain() + " · " + NucleusPanel.scientific(reaction.getPerKgS()) + " /kg/s · Q "
                    + Visuals.format(reaction.getQMev()) + " MeV", x + 16, row + 13);
            row += 30;
        }
    }
}
