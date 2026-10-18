package org.genesis.subatomic;

import org.genesis.app.I18n;
import org.genesis.common.LabCamera;

import com.studio.genesis.grpc.SubatomicParticle;
import com.studio.genesis.grpc.SubatomicPair;
import com.studio.genesis.grpc.SubatomicSample;
import com.studio.genesis.grpc.SubatomicState;
import com.studio.genesis.grpc.UniverseFrame;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.effect.BlendMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import java.util.List;
import java.nio.IntBuffer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.Consumer;

/** Vue dédiée aux échantillons intégrés par le moteur Python. Aucune trajectoire locale. */
public final class SubatomicView {
    public record Kick(int particle, int sample, double vx, double vy, double vz) {}
    private static final Color PROTON = Color.web("#FF806B");
    private static final Color NEUTRON = Color.web("#75D5E5");
    private static final Color INK = Color.web("#DCE9FA");
    private final BorderPane root = new BorderPane();
    private final VBox side = new VBox(15);
    private final Canvas scene = new Canvas();
    private final Canvas energyPlot = new Canvas(292, 160);
    private final Canvas potentialPlot = new Canvas(292, 140);
    private final Canvas bindingPlot = new Canvas(292, 45);
    private final LabCamera camera = new LabCamera();
    private final Label counts = new Label();
    private final Label phase = new Label();
    private final Label clock = new Label();
    private final Label diagnostics = new Label();
    private final Label model = new Label();
    private final Label kinetic = new Label();
    private final Label potential = new Label();
    private final Label total = new Label();
    private final Label force = new Label();
    private final Label binding = new Label();
    private final Label electronEnergy = new Label();
    private final Label impulseLabel = new Label();
    private final Slider scrub = new Slider(0, 1, 0);
    private final Slider impulse = new Slider(0, 30, 0);
    private final ToggleButton play = new ToggleButton("Ⅱ Pause");
    private final ToggleButton atomic = new ToggleButton("Échelle électronique");
    private final ToggleButton couplings = new ToggleButton("Couplages calculés");
    private final Consumer<Kick> onKick;
    private SubatomicState state;
    private List<SubatomicSample> samples = List.of();
    private double cursor, previousX, previousY;
    private boolean dragging, updating;
    private WritableImage electronCloud;
    private PixelBuffer<IntBuffer> electronBuffer;
    private int[] electronPixels = new int[0];
    private int cloudCount;
    private double cloudRadiusPm = Double.NaN, cloudSpanPm = Double.NaN;
    private double electronSpanPm = 200;
    private int grabbed = -1;
    private double grabX, grabY, dragX, dragY;

    public SubatomicView(Runnable back, Runnable reset, IntConsumer protons, IntConsumer neutrons,
                  IntConsumer electrons, DoubleConsumer energy, Consumer<Kick> onKick) {
        this.onKick = onKick;
        root.setStyle("-fx-background-color:#060B16;");
        camera.yaw = -.5;
        camera.pitch = .3;
        camera.zoom = 1.1;
        Label title = text("MATIÈRE · LABORATOIRE SUBATOMIQUE", "#8EB8D7", 12);
        Label subtitle = text("Nucléons calculés en 3D · échantillons Python", "#EAF5FF", 21);
        VBox heading = new VBox(4, title, subtitle);
        Button exit = button("← Atome", back);
        HBox top = new HBox(20, heading, new javafx.scene.layout.Region(), exit);
        HBox.setHgrow(top.getChildren().get(1), Priority.ALWAYS);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(18, 24, 17, 24));
        top.setStyle("-fx-background-color:#091425;-fx-border-color:#233A55;-fx-border-width:0 0 1 0;");
        root.setTop(top);

        StackPane center = new StackPane(scene);
        center.setStyle("-fx-background-color:#050B16;");
        scene.widthProperty().bind(center.widthProperty());
        scene.heightProperty().bind(center.heightProperty());
        scene.widthProperty().addListener((o, a, b) -> draw());
        scene.heightProperty().addListener((o, a, b) -> draw());
        scene.setOnMousePressed(e -> {
            dragging = true;
            previousX = e.getX(); previousY = e.getY();
            grabbed = atomic.isSelected() ? -1 : hitParticle(e.getX(), e.getY());
            if (grabbed >= 0) {
                cursor = Math.round(cursor);
                updating = true;
                scrub.setValue(cursor);
                updating = false;
                grabX = dragX = e.getX();
                grabY = dragY = e.getY();
                play.setSelected(false);
                play.setText("▶ Lecture");
            }
        });
        scene.setOnMouseDragged(e -> {
            if (grabbed >= 0) {
                dragX = e.getX(); dragY = e.getY();
            } else if (!atomic.isSelected()) {
                camera.drag(e.getX() - previousX, e.getY() - previousY);
            }
            previousX = e.getX(); previousY = e.getY();
            draw();
        });
        scene.setOnMouseReleased(e -> {
            dragging = false;
            if (grabbed >= 0) {
                dragX = e.getX(); dragY = e.getY();
                sendGrab();
                grabbed = -1;
                draw();
            }
        });
        scene.setOnScroll(e -> {
            if (atomic.isSelected()) {
                electronSpanPm = Math.max(8, Math.min(600,
                        electronSpanPm * Math.exp(-e.getDeltaY() / 350)));
            } else camera.scroll(e.getDeltaY() / 40);
            draw();
        });
        root.setCenter(center);

        side.setPadding(new Insets(21));
        side.setPrefWidth(335);
        side.setMinWidth(335);
        side.setStyle("-fx-background-color:#0A1424;-fx-border-color:#254059;-fx-border-width:0 0 0 1;");
        side.getChildren().addAll(text("CONSTRUIRE", "#79A2C0", 11),
                counter("Protons", PROTON, () -> state == null ? 0 : state.getProtons(), protons),
                counter("Neutrons", NEUTRON, () -> state == null ? 0 : state.getNeutrons(), neutrons),
                counter("Électrons 1s", Color.web("#D7B7FF"), () -> state == null ? 0 : state.getElectrons(), electrons));
        impulse.setShowTickMarks(true);
        impulse.setMajorTickUnit(5);
        impulse.setBlockIncrement(.5);
        impulse.valueProperty().addListener((o, a, b) -> {
            if (!updating) impulseLabel.setText(String.format("Impulsion initiale  %.2f MeV", b.doubleValue()));
        });
        impulse.setOnMouseReleased(e -> energy.accept(impulse.getValue()));
        impulse.setOnKeyReleased(e -> energy.accept(impulse.getValue()));
        impulseLabel.setText("Impulsion initiale  0 MeV");
        side.getChildren().addAll(text("ÉNERGIE INITIALE", "#79A2C0", 11), impulseLabel, impulse,
                button("Recalculer la paire p + n", reset),
                text("INTERACTIONS", "#79A2C0", 11), couplings,
                text("Saisir un nucléon dans la vue, tirer puis relâcher : Python réintègre l'état choisi avec cette impulsion.", "#9CB6CD", 11),
                text("LECTURE DES GRANDEURS", "#79A2C0", 11),
                phase, counts, kinetic, potential, total, force, electronEnergy, diagnostics,
                text("SEUIL DE SÉPARATION", "#79A2C0", 11), binding, bindingPlot,
                text("POTENTIEL DE PAIRE V(r) · p–n de référence pour plusieurs nucléons", "#79A2C0", 11), potentialPlot,
                text("BILAN D'ÉNERGIE DANS LE TEMPS", "#79A2C0", 11), energyPlot,
                text("MODÈLE ET LIMITES", "#79A2C0", 11), model);
        for (Label label : new Label[]{phase, counts, clock, diagnostics, model, kinetic, potential, binding,
                total, force, electronEnergy, impulseLabel}) {
            label.setTextFill(INK);
            label.setWrapText(true);
        }
        phase.setStyle("-fx-font-size:16px;-fx-font-weight:bold;");
        diagnostics.setStyle("-fx-font-size:11px;-fx-text-fill:#9CB6CD;");
        model.setStyle("-fx-font-size:11px;-fx-text-fill:#9CB6CD;");
        ScrollPane scroll = new ScrollPane(side);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefWidth(335);
        scroll.setStyle("-fx-background:#0A1424;-fx-background-color:#0A1424;");
        root.setRight(scroll);

        atomic.setOnAction(e -> draw());
        couplings.setSelected(true);
        couplings.setOnAction(e -> draw());
        play.setSelected(true);
        play.setOnAction(e -> play.setText(play.isSelected() ? "Ⅱ Pause" : "▶ Lecture"));
        scrub.valueProperty().addListener((o, a, b) -> {
            if (updating) return;
            cursor = b.doubleValue();
            draw();
        });
        HBox bottom = new HBox(13, play, scrub, clock, atomic);
        HBox.setHgrow(scrub, Priority.ALWAYS);
        bottom.setAlignment(Pos.CENTER);
        bottom.setPadding(new Insets(12, 20, 12, 20));
        bottom.setStyle("-fx-background-color:#091425;-fx-border-color:#233A55;-fx-border-width:1 0 0 0;");
        root.setBottom(bottom);
    }

    public BorderPane getNode() { return root; }

    public int protons() { return state == null ? 0 : state.getProtons(); }
    public int neutrons() { return state == null ? 0 : state.getNeutrons(); }
    public int electrons() { return state == null ? 0 : state.getElectrons(); }

    public void togglePlayback() {
        play.setSelected(!play.isSelected());
        play.setText(play.isSelected() ? "Ⅱ Pause" : "▶ Lecture");
    }

    public void zoomBy(double notches) { camera.scroll(notches); }

    private static Label text(String value, String color, int size) {
        Label label = new Label(value);
        label.setStyle("-fx-text-fill:" + color + ";-fx-font-size:" + size + "px;");
        label.setWrapText(true);
        return label;
    }

    private static Button button(String value, Runnable action) {
        Button button = new Button(value);
        button.setStyle("-fx-background-color:#17334B;-fx-text-fill:#E5F4FF;"
                + "-fx-background-radius:9;-fx-padding:9 13;");
        button.setOnAction(e -> action.run());
        return button;
    }

    private HBox counter(String name, Color color, java.util.function.IntSupplier current, IntConsumer update) {
        Label label = text(name, "#" + color.toString().substring(2, 8), 14);
        Label value = text("0", "#F0F7FF", 15);
        value.setMinWidth(28);
        Button minus = button("−", () -> update.accept(Math.max(0, current.getAsInt() - 1)));
        Button plus = button("+", () -> update.accept(current.getAsInt() + 1));
        HBox row = new HBox(7, label, new javafx.scene.layout.Region(), minus, value, plus);
        HBox.setHgrow(row.getChildren().get(1), Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setUserData(value);
        return row;
    }

    public void applyFrame(UniverseFrame frame) {
        SubatomicState incoming = frame.getSubatomic();
        if (!incoming.getActive()) return;
        state = incoming;
        if (incoming.getSamplesCount() > 0) {
            samples = incoming.getSamplesList();
            cursor = 0;
            play.setSelected(true);
            play.setText("Ⅱ Pause");
            scrub.setMax(Math.max(0, samples.size() - 1));
        }
        updating = true;
        impulse.setValue(incoming.getImpulseMev());
        impulseLabel.setText(String.format("Impulsion initiale  %.2f MeV", incoming.getImpulseMev()));
        for (int i = 0; i < 3; i++) {
            HBox row = (HBox) side.getChildren().get(i + 1);
            ((Label) row.getUserData()).setText("" + new int[]{incoming.getProtons(), incoming.getNeutrons(),
                    incoming.getElectrons()}[i]);
        }
        updating = false;
        draw();
    }

    public void tick(double dt) {
        camera.tick(Math.min(.05, dt), dragging, .5, 2.2);
        if (!samples.isEmpty() && play.isSelected()) {
            cursor += Math.min(.1, dt) * 60;
            if (cursor >= samples.size() - 1) {
                cursor = samples.size() - 1;
                play.setSelected(false);
                play.setText("▶ Lecture");
            }
            updating = true;
            scrub.setValue(cursor);
            updating = false;
        }
        draw();
    }

    private int hitParticle(double x, double y) {
        if (samples.isEmpty()) return -1;
        int closest = -1;
        double best = Double.POSITIVE_INFINITY;
        SubatomicSample sample = samples.get(Math.min(samples.size()-1, (int) cursor));
        for (int i=0; i<sample.getParticlesCount(); i++) {
            double[] xyz = interpolatedPosition(i);
            double[] screen = camera.project(xyz[0], xyz[1], xyz[2]);
            if (screen == null) continue;
            double distance = Math.hypot(screen[0]-x, screen[1]-y);
            if (distance < Math.max(18, .84*screen[3]+7) && distance < best) {
                best = distance;
                closest = i;
            }
        }
        return closest;
    }

    private void sendGrab() {
        double dx = dragX-grabX, dy = dragY-grabY;
        if (Math.hypot(dx,dy) < 8) return;
        double[] right = camera.right(), up = camera.up();
        double factor = .00045;
        double vx = factor * (dx*right[0] - dy*up[0]);
        double vy = factor * (dx*right[1] - dy*up[1]);
        double vz = factor * (dx*right[2] - dy*up[2]);
        double speed = Math.sqrt(vx*vx+vy*vy+vz*vz);
        if (speed > .16) { vx *= .16/speed; vy *= .16/speed; vz *= .16/speed; }
        onKick.accept(new Kick(grabbed, (int)cursor, vx, vy, vz));
    }

    private void draw() {
        double w = scene.getWidth(), h = scene.getHeight();
        if (w < 2 || h < 2) return;
        GraphicsContext g = scene.getGraphicsContext2D();
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setFill(new RadialGradient(0, 0, w*.5, h*.45, Math.max(w, h)*.75, false,
                CycleMethod.NO_CYCLE, new Stop(0, Color.web("#14263A")),
                new Stop(.6, Color.web("#081322")), new Stop(1, Color.web("#040913"))));
        g.fillRect(0, 0, w, h);
        if (state == null || samples.isEmpty()) {
            g.setFill(INK);
            I18n.fillText(g, "En attente du calcul Python…", w*.42, h*.5);
            return;
        }
        int index = Math.min(samples.size()-1, (int) cursor);
        SubatomicSample sample = samples.get(index);
        SubatomicSample next = samples.get(Math.min(samples.size()-1, index+1));
        double alpha = cursor-index;
        clock.setText(String.format("t = %.1f fm/c",
                sample.getTimeFmc() + alpha*(next.getTimeFmc()-sample.getTimeFmc())));
        phase.setText(state.getStatus());
        counts.setText(state.getProtons() + " p⁺    " + state.getNeutrons() + " n    "
                + state.getElectrons() + " e⁻");
        double k = sample.getKineticMev() + alpha*(next.getKineticMev()-sample.getKineticMev());
        double strong = sample.getStrongMev() + alpha*(next.getStrongMev()-sample.getStrongMev());
        double coulomb = sample.getCoulombMev() + alpha*(next.getCoulombMev()-sample.getCoulombMev());
        double e = sample.getTotalMev() + alpha*(next.getTotalMev()-sample.getTotalMev());
        kinetic.setText(String.format("Cinétique       %+8.3f MeV", k));
        potential.setText(String.format("Forte / Coulomb  %+7.3f / %+7.3f MeV",
                strong, coulomb));
        total.setText(String.format("Énergie totale  %+8.3f MeV", e));
        if (sample.getParticlesCount() == 2) {
            double radial = sample.getRadialForceMevFm() + alpha*
                    (next.getRadialForceMevFm()-sample.getRadialForceMevFm());
            force.setText(String.format("Force radiale   %+8.3f MeV/fm · %s", radial,
                    radial < 0 ? "attraction" : "répulsion"));
        } else force.setText("Forces : somme vectorielle de chaque paire");
        electronEnergy.setText(state.getElectrons() == 0 ? "Électron : aucun" :
                String.format("Liaison 1s       %.3f eV %s", state.getElectronBindingEv(),
                        state.getElectrons() == 1 ? "(hydrogénoïde)" : "(Slater)"));
        diagnostics.setText(String.format("Erreur max : %.2e MeV · calcul Verlet, %d images",
                state.getEnergyErrorMev(), samples.size()));
        double margin = sample.getBindingMarginMev() + alpha*(next.getBindingMarginMev()-sample.getBindingMarginMev());
        if (sample.getParticlesCount() == 2) {
            binding.setText(String.format("%s · marge %+6.3f MeV",
                    margin > 0 ? "Paire liée dans ce modèle" : "Diffusion dans ce modèle", margin));
        } else if (sample.getParticlesCount() > 2) {
            binding.setText(String.format("%d/%d nucléons localement retenus · marge minimale %+6.3f MeV. Diagnostic instantané, pas une preuve de stabilité.",
                    sample.getLocallyBoundCount(), sample.getParticlesCount(), margin));
        } else binding.setText("Pas de seuil de séparation pour une particule seule.");
        plotBinding(margin);
        model.setText(state.getNote());
        plot();
        plotPotential(sample);
        if (atomic.isSelected()) drawElectronScale(g, w, h);
        else drawNuclearScale(g, w, h, sample, next, alpha);
    }

    private void drawNuclearScale(GraphicsContext g, double w, double h,
                                  SubatomicSample sample, SubatomicSample next, double alpha) {
        if (sample.getParticlesCount() > 0) {
            double mass=0, mx=0, my=0, mz=0;
            for (int i=0;i<sample.getParticlesCount();i++) {
                double m=Math.max(1, sample.getParticles(i).getMassMev());
                double[] p=interpolatedPosition(i);
                mass += m; mx += m*p[0]; my += m*p[1]; mz += m*p[2];
            }
            camera.targetX=mx/mass; camera.targetY=my/mass; camera.targetZ=mz/mass;
        }
        camera.setup(w, h, w*.5, h*.5, camera.distance());
        if (sample.getParticlesCount() == 0) {
            g.setFill(INK);
            I18n.fillText(g, "Ajoute un proton ou un neutron.", w*.4, h*.5);
            return;
        }
        double[] origin = camera.project(0, 0, 0);
        if (origin != null) {
            g.setStroke(Color.web("#31516A", .35));
            g.setLineWidth(1);
            g.strokeLine(origin[0]-8, origin[1], origin[0]+8, origin[1]);
            g.strokeLine(origin[0], origin[1]-8, origin[0], origin[1]+8);
        }
        double[][] projected = new double[sample.getParticlesCount()][];
        Integer[] order = new Integer[sample.getParticlesCount()];
        for (int i = 0; i < order.length; i++) {
            double[] position = interpolatedPosition(i);
            projected[i] = camera.project(position[0], position[1], position[2]);
            order[i] = i;
        }
        if (couplings.isSelected()) drawCouplings(g, sample, next, alpha, projected);
        // Painter's order by actual 3D depth, with one projection per body.
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(i ->
                projected[i] == null ? Double.POSITIVE_INFINITY : -projected[i][2]));
        for (int index : order) {
            SubatomicParticle particle = sample.getParticles(index);
            double[] p = projected[index];
            if (p == null) continue;
            boolean proton = particle.getKind() == SubatomicParticle.Kind.PROTON;
            Color color = proton ? PROTON : NEUTRON;
            double radius = Math.max(5, .84 * p[3]);
            g.setFill(new RadialGradient(-.35, -.35, p[0], p[1], radius, false, CycleMethod.NO_CYCLE,
                    new Stop(0, color.interpolate(Color.WHITE, .75)),
                    new Stop(.42, color), new Stop(1, color.interpolate(Color.BLACK, .68))));
            g.fillOval(p[0]-radius, p[1]-radius, radius*2, radius*2);
            g.setStroke(color.interpolate(Color.WHITE, .45));
            g.setLineWidth(1.2);
            g.strokeOval(p[0]-radius, p[1]-radius, radius*2, radius*2);
            g.setFill(INK);
            I18n.fillText(g, (proton ? "p⁺" : "n") + "  β=" + String.format("%.3f",
                    Math.sqrt(particle.getVelocityCList().stream().mapToDouble(v -> v*v).sum())),
                    p[0]+radius+8, p[1]-radius*.2);
        }
        if (sample.getParticlesCount() == 2) {
            double[] a = interpolatedPosition(0), b = interpolatedPosition(1);
            double r2 = 0;
            for (int i=0;i<3;i++) r2 += Math.pow(a[i]-b[i], 2);
            g.setFill(Color.web("#B6CFE4"));
            I18n.fillText(g, String.format("r = %.3f fm  ·  E = %.3f MeV", Math.sqrt(r2), sample.getTotalMev()), 24, 36);
        }
        if (grabbed >= 0 && grabbed < projected.length && projected[grabbed] != null) {
            double[] p = projected[grabbed];
            g.setStroke(Color.web("#ECF9FF", .9));
            g.setLineWidth(2);
            g.strokeLine(p[0], p[1], dragX, dragY);
            g.setFill(INK);
            I18n.fillText(g, String.format("Δv ≈ %.3f c", Math.min(.16,
                    Math.hypot(dragX-grabX, dragY-grabY)*.00045)), dragX+8, dragY-8);
        }
        if (state.getElectrons() > 0) drawElectronInset(g, w, sample);
        g.setFill(Color.web("#82A7C5"));
        I18n.fillText(g, "ÉCHELLE NUCLÉAIRE · glisser une particule pour lui donner une impulsion · fond pour tourner · molette pour zoomer", 24, h-22);
    }

    private void drawCouplings(GraphicsContext g, SubatomicSample sample, SubatomicSample next,
                               double alpha, double[][] projected) {
        for (int i=0;i<sample.getPairsCount();i++) {
            SubatomicPair pair=sample.getPairs(i);
            double[] a=projected[pair.getFirst()], b=projected[pair.getSecond()];
            if (a==null || b==null) continue;
            double value=pair.getEffectiveForceMevFm();
            if (i<next.getPairsCount()) value += alpha*(next.getPairs(i).getEffectiveForceMevFm()-value);
            double intensity=Math.tanh(Math.abs(value)/18);
            if (intensity < .035) continue;
            Color color=Color.web(value < 0 ? "#7CDDEB" : "#F2B776", .16+.68*intensity);
            g.setStroke(color);
            g.setLineWidth(.7+3.1*intensity);
            g.strokeLine(a[0], a[1], b[0], b[1]);
        }
        g.setFill(Color.web("#A2C2D5"));
        I18n.fillText(g, "F effective par paire · cyan : attraction · ambre : répulsion · épaisseur ∝ |F|", 24, 56);
    }

    /** Hermite entre deux résultats Python ; ses tangentes sont les vitesses intégrées par Verlet. */
    private double[] interpolatedPosition(int particle) {
        int i = Math.min(samples.size()-1, (int) cursor), j = Math.min(samples.size()-1, i+1);
        SubatomicSample left = samples.get(i), right = samples.get(j);
        SubatomicParticle a = left.getParticles(particle), b = right.getParticles(particle);
        double t = cursor-i, t2=t*t, t3=t2*t, dt=right.getTimeFmc()-left.getTimeFmc();
        double[] out = new double[3];
        for (int k=0;k<3;k++) out[k] = (2*t3-3*t2+1)*a.getPositionFm(k)
                + (t3-2*t2+t)*dt*a.getVelocityC(k)
                + (-2*t3+3*t2)*b.getPositionFm(k)
                + (t3-t2)*dt*b.getVelocityC(k);
        return out;
    }

    private void drawElectronScale(GraphicsContext g, double w, double h) {
        if (state.getElectrons() == 0 || state.getProtons() == 0) {
            g.setFill(INK);
            I18n.fillText(g, "Ajoute un électron pour voir sa densité orbitale.", w*.32, h*.5);
            return;
        }
        double cx = w*.5, cy = h*.5;
        electronCloud(w, h, state.getElectrons(), state.getElectronRadiusPm(), electronSpanPm);
        g.drawImage(electronCloud, 0, 0, w, h);
        g.setStroke(Color.web("#FF9F8D"));
        g.setLineWidth(2);
        g.strokeLine(cx-8, cy, cx+8, cy);
        g.strokeLine(cx, cy-8, cx, cy+8);
        g.setFill(INK);
        I18n.fillText(g, String.format("Coupe |ψ₁ₛ|² ∝ exp(−2r/a) · a = %.3f pm · fenêtre ±%.0f pm",
                state.getElectronRadiusPm(), electronSpanPm), 24, 36);
        I18n.fillText(g, "Noyau au centre : taille sous-pixel à cette échelle. Molette : échelle électronique.", 24, h-44);
        I18n.fillText(g, "Nuage stationnaire 1s : aucune orbite ponctuelle ni force de Pauli électron–proton.", 24, h-22);
        drawNuclearInset(g, w, h);
    }

    private void drawElectronInset(GraphicsContext g, double width, SubatomicSample sample) {
        double size=180, x=width-size-22, y=18;
        g.setFill(Color.web("#0B1A2A", .95));
        g.fillRoundRect(x-5,y-5,size+10,size+47,9,9);
        electronCloud(size, size, state.getElectrons(), state.getElectronRadiusPm(), 200);
        g.drawImage(electronCloud,x,y,size,size);
        g.setFill(Color.web("#F5B19E"));
        g.fillOval(x+size/2-2,y+size/2-2,4,4);
        g.setFill(INK);
        I18n.fillText(g, String.format("e⁻ 1s · a = %.1f pm", state.getElectronRadiusPm()),x+3,y+size+17);
        I18n.fillText(g, "Échelle électronique",x+3,y+size+31);
    }

    private void drawNuclearInset(GraphicsContext g, double width, double height) {
        if (samples.isEmpty()) return;
        SubatomicSample sample=samples.get(Math.min(samples.size()-1,(int)cursor));
        double x=width-203,y=height-210,size=178,cx=x+size/2,cy=y+size/2;
        g.setFill(Color.web("#0A1827", .96));
        g.fillRoundRect(x,y,size,203,9,9);
        camera.setup(width,height,width*.5,height*.5,camera.distance());
        double[] center={0,0,0};
        double totalMass=0;
        for (int i=0;i<sample.getParticlesCount();i++) {
            double m=Math.max(1,sample.getParticles(i).getMassMev());
            double[] xyz=interpolatedPosition(i);
            for (int axis=0;axis<3;axis++) center[axis]+=m*xyz[axis];
            totalMass+=m;
        }
        if (totalMass>0) for (int axis=0;axis<3;axis++) center[axis]/=totalMass;
        for (int i=0;i<sample.getParticlesCount();i++) {
            double[] xyz=interpolatedPosition(i);
            double[] view=camera.toView(xyz[0]-center[0],xyz[1]-center[1],xyz[2]-center[2]);
            Color c=sample.getParticles(i).getKind()==SubatomicParticle.Kind.PROTON ? PROTON : NEUTRON;
            g.setFill(c);
            g.fillOval(cx+view[0]*14-6,cy-view[1]*14-6,12,12);
        }
        g.setFill(INK);
        I18n.fillText(g, "Noyau grossi · positions Python",x+8,y+188);
    }

    private void electronCloud(double width, double height, int electrons,
                               double radiusPm, double spanPm) {
        int gw = Math.max(40, (int) Math.ceil(width/4)), gh = Math.max(30, (int) Math.ceil(height/4));
        if (electronCloud != null && (int) electronCloud.getWidth() == gw
                && (int) electronCloud.getHeight() == gh && cloudCount == electrons
                && cloudRadiusPm == radiusPm && cloudSpanPm == spanPm) return;
        cloudCount = electrons;
        cloudRadiusPm = radiusPm;
        cloudSpanPm = spanPm;
        electronPixels = new int[gw*gh];
        electronBuffer = new PixelBuffer<>(gw, gh, IntBuffer.wrap(electronPixels), PixelFormat.getIntArgbPreInstance());
        electronCloud = new WritableImage(electronBuffer);
        double sx=width/gw, sy=height/gh, pmPerPixel=2*spanPm/Math.min(width,height);
        for (int j=0;j<gh;j++)
            for (int i=0;i<gw;i++) {
                double rPm=Math.hypot((i+.5)*sx-width*.5,(j+.5)*sy-height*.5)*pmPerPixel;
                // Coupe de la densité 1s. Les pm par pixel et a viennent du moteur.
                int a=(int)(255*Math.min(.85, (.48+.12*electrons)*Math.exp(-2*rPm/radiusPm)));
                electronPixels[j*gw+i]=a<<24 | (int)(.45*a)<<16 | (int)(.76*a)<<8 | a;
            }
        electronBuffer.updateBuffer(buffer -> null);
    }

    private void plotBinding(double margin) {
        GraphicsContext g=bindingPlot.getGraphicsContext2D();
        double w=bindingPlot.getWidth(), h=bindingPlot.getHeight(), mid=w/2;
        g.setFill(Color.web("#07101F")); g.fillRect(0,0,w,h);
        g.setStroke(Color.web("#91ADC4"));
        g.strokeLine(mid,5,mid,h-15);
        double extent=Math.tanh(margin/10)*(mid-10);
        g.setFill(Color.web(margin>=0 ? "#70D3C4" : "#EAB17B"));
        g.fillRoundRect(extent>=0 ? mid : mid+extent,9,Math.abs(extent),13,4,4);
        g.setFill(Color.web("#A7BED1"));
        I18n.fillText(g, "séparation",7,h-4);
        I18n.fillText(g, "0",mid-3,h-4);
        I18n.fillText(g, "liaison",w-41,h-4);
    }

    private void plot() {
        GraphicsContext g = energyPlot.getGraphicsContext2D();
        double w = energyPlot.getWidth(), h = energyPlot.getHeight();
        g.setFill(Color.web("#07101F"));
        g.fillRect(0, 0, w, h);
        if (samples.size() < 2) return;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (SubatomicSample s : samples) {
            min = Math.min(min, Math.min(s.getStrongMev()+s.getCoulombMev(), s.getTotalMev()));
            max = Math.max(max, Math.max(s.getKineticMev(), s.getTotalMev()));
        }
        double span = Math.max(1, max-min), left=8, top=10, pw=w-16, ph=h-28;
        Color[] colors = {Color.web("#85D9EC"), Color.web("#F7B579"), Color.web("#F1F7FF")};
        for (int channel=0;channel<3;channel++) {
            g.setStroke(colors[channel]);
            g.setLineWidth(channel==2 ? 1.6 : 1.2);
            for (int i=1;i<samples.size();i++) {
                SubatomicSample a=samples.get(i-1), b=samples.get(i);
                double va=channel==0?a.getKineticMev():channel==1?a.getStrongMev()+a.getCoulombMev():a.getTotalMev();
                double vb=channel==0?b.getKineticMev():channel==1?b.getStrongMev()+b.getCoulombMev():b.getTotalMev();
                g.strokeLine(left+pw*(i-1)/(samples.size()-1),top+ph*(max-va)/span,
                        left+pw*i/(samples.size()-1),top+ph*(max-vb)/span);
            }
        }
        g.setStroke(Color.web("#B7D6EA", .6));
        double x=left+pw*cursor/(samples.size()-1);
        g.strokeLine(x, 0, x, h-18);
        g.setFill(Color.web("#93ABC2"));
        I18n.fillText(g, "K cyan · V ambre · E blanc", 8, h-5);
    }

    private void plotPotential(SubatomicSample sample) {
        GraphicsContext g = potentialPlot.getGraphicsContext2D();
        double w = potentialPlot.getWidth(), h = potentialPlot.getHeight();
        g.setFill(Color.web("#07101F"));
        g.fillRect(0, 0, w, h);
        if (state.getRadialFmCount() < 2 || state.getPotentialMevCount() != state.getRadialFmCount()) return;
        double min=Double.POSITIVE_INFINITY, max=Double.NEGATIVE_INFINITY;
        for (double value : state.getPotentialMevList()) {
            double mapped = Math.log(value/5 + Math.sqrt(1 + value*value/25));
            min=Math.min(min,mapped); max=Math.max(max,mapped);
        }
        double left=11, top=10, pw=w-22, ph=h-29, span=Math.max(.01,max-min);
        g.setStroke(Color.web("#426078"));
        double zero=top+ph*max/span;
        g.strokeLine(left,zero,w-left,zero);
        g.setStroke(Color.web("#E9AE79"));
        g.setLineWidth(1.6);
        for (int i=1;i<state.getRadialFmCount();i++) {
            double a=state.getPotentialMev(i-1), b=state.getPotentialMev(i);
            double ya=Math.log(a/5+Math.sqrt(1+a*a/25));
            double yb=Math.log(b/5+Math.sqrt(1+b*b/25));
            g.strokeLine(left+pw*(i-1)/(state.getRadialFmCount()-1), top+ph*(max-ya)/span,
                    left+pw*i/(state.getRadialFmCount()-1), top+ph*(max-yb)/span);
        }
        if (sample.getParticlesCount() == 2) {
            double r=sample.getSeparationFm();
            double r0=state.getRadialFm(0), r1=state.getRadialFm(state.getRadialFmCount()-1);
            double x=left+pw*(r-r0)/(r1-r0);
            if (x>=left && x<=w-left) {
                g.setStroke(Color.web("#DDEFFF", .6));
                g.strokeLine(x,top,x,top+ph);
            }
        }
        g.setFill(Color.web("#93ABC2"));
        I18n.fillText(g, "r : 0,5–7 fm · axe V compressé (asinh)", 8, h-5);
    }
}
