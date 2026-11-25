package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeEntry;
import com.studio.genesis.grpc.MoleculeOutcome;
import com.studio.genesis.grpc.MoleculeReaction;
import org.genesis.app.I18n;
import org.genesis.common.Plan;

import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import java.util.List;

/**
 * Réactifs → produits : ce que deviennent les molécules du dessin, cherché par le moteur (logic/moleculelab/reaction.py).
 * C'est un « nanoréacteur » (Wang et al., Nat. Chem. 2014) : chaque molécule relaxée seule par GFN1-xTB, toutes
 * rapprochées au contact dans une sphère, agitées à la température choisie, en plusieurs essais ; à la fin de chaque essai
 * le gaz est refroidi et chaque espèce présente relaxée seule. Les issues sont regroupées : combien d'essais y mènent,
 * leur énergie de réaction ΔE, leurs produits dessinés — à reprendre dans le dessin ou à ouvrir en 3D.
 */
final class ReactionPanel {
    interface Actions {
        /** Le dessin, pour le moteur ; null s'il ne s'y prête pas (l'assembleur a dit pourquoi). */
        double[] drawing();

        void react(int request, double[] vector, double temperature);

        /** Les produits d'une issue remplacent le dessin (Ctrl+Z : revenir). */
        void draw(List<MoleculeEntry> products);

        /** Les produits d'une issue dans le simulateur 3D. */
        void open(List<MoleculeEntry> products);
    }

    static final double[] TEMPERATURES = {1500, 2500, 4000};
    /** Ce que le moteur fait (reaction.py : TRIALS, DURATION_FS) : pour dire, avant le calcul, ce qu'il peut franchir. */
    private static final int TRIALS = 3;
    private static final double DURATION_FS = 600, ATTEMPTS_PER_S = 1e13, BOLTZMANN_EV = 8.617333e-5;
    private static final Color GOOD = Color.web("#6CD09A"), BAD = Color.web("#F2766B");
    private static final double SKETCH_WIDTH = 246, SKETCH_HEIGHT = 74;

    private final Actions actions;
    private final VBox root = new VBox(8);
    private final ToggleGroup group = new ToggleGroup();
    private final Button run = MoleculeView.button("Faire réagir  →", false);
    private final Label reach = Plan.note(""), status = Plan.note(""), reactants = Plan.note("");
    private final Region fill = new Region();
    private final Pane bar = new Pane(fill) {
        @Override protected void layoutChildren() {
            fill.resizeRelocate(0, 0, getWidth() * Math.max(0, Math.min(1, fraction)), getHeight());
        }
    };
    private final VBox results = new VBox(8);
    private final Label limits = Plan.note("GFN1-xTB, sans spin : ΔE trop négatif quand des radicaux se lient ; "
            + "une issue absente peut exister.");
    private double temperature = TEMPERATURES[1], fraction;
    private int request;
    private boolean running, stopped;
    private MoleculeReaction last;

    ReactionPanel(Actions actions) {
        this.actions = actions;
        HBox choices = new HBox();
        for (int k = 0; k < TEMPERATURES.length; k++) {
            double t = TEMPERATURES[k];
            ToggleButton b = new ToggleButton((int) t + " K");
            b.getStyleClass().setAll("toggle-button", "segment", k == 0 ? "left" : k == TEMPERATURES.length - 1 ? "right" : "middle");
            b.setFocusTraversable(false);
            b.setToggleGroup(group);
            b.setSelected(t == temperature);
            b.setTooltip(I18n.tooltip(k == 0 ? "Les réactions sans barrière ou presque : des radicaux qui se lient, des ions qui s'attirent"
                    : k == 1 ? "Des barrières de quelques dixièmes d'eV : transferts d'atomes, réarrangements faciles"
                    : "Presque tout se casse : les fragments, et ce qu'ils reforment en se recombinant"));
            b.setOnAction(e -> {
                b.setSelected(true);
                temperature = t;
                render();
            });
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, javafx.scene.layout.Priority.ALWAYS);
            choices.getChildren().add(b);
        }
        run.setOnAction(e -> {
            if (running) stop();
            else start();
        });
        run.setMaxWidth(Double.MAX_VALUE);
        run.setTooltip(I18n.tooltip("Les molécules du dessin, chacune relaxée seule, rapprochées au contact dans une sphère et "
                + "agitées ensemble à la température choisie : 3 essais de 600 fs, puis chaque espèce présente relaxée seule"));
        VBox head = new VBox(6, choices, run);
        bar.setPrefHeight(3);
        bar.setMinHeight(3);
        bar.setMaxHeight(3);
        bar.setStyle("-fx-background-color:rgba(220,232,250,0.08);");
        fill.setStyle("-fx-background-color:-accent;");
        status.setWrapText(true);
        reactants.setWrapText(true);
        reactants.setStyle("-fx-text-fill:" + Plan.INK_HEX + ";");
        limits.setTooltip(I18n.tooltip("Méthode : le nanoréacteur (Wang et al., Nat. Chem. 2014). Les molécules du dessin, chacune "
                + "relaxée seule, sont rapprochées au contact (2,8 Å entre leurs atomes les plus proches) dans une sphère posée "
                + "sur leurs nuages, et agitées à la température choisie (thermostat CSVR) pendant 600 fs, en 3 essais aux "
                + "vitesses de départ différentes ; puis refroidies 120 fs à 300 K, et chaque espèce présente est relaxée seule. "
                + "ΔE = Σ E(produits seuls) − Σ E(réactifs seuls), énergies GFN1-xTB à 0 K, sans énergie de point zéro. "
                + "Limites : pas de polarisation de spin — les liaisons formées entre radicaux sont trop fortes (OH + H → H₂O : "
                + "−8,1 eV calculés, −5,1 eV mesurés, −5,4 eV sans l'énergie de point zéro), O₂ n'a pas de barrière de spin ; quelques centaines de "
                + "femtosecondes à haute température : une issue qui n'apparaît pas peut exister, et la fréquence d'une issue "
                + "n'est ni une vitesse ni un rendement réels."));
        root.getChildren().addAll(head, reach, bar, status, reactants, results, limits);
        I18n.onChange(this::render);
        render();
    }

    Region getNode() {
        return root;
    }

    /** La plus haute barrière qu'un essai franchit à peu près : une fois en TRIALS × DURATION_FS, à 10¹³ tentatives/s. */
    static double reachEv(double kelvin) {
        return BOLTZMANN_EV * kelvin * Math.log(ATTEMPTS_PER_S * TRIALS * DURATION_FS * 1e-15);
    }

    private void start() {
        double[] vector = actions.drawing();
        if (vector == null) return;
        request++;
        running = true;
        stopped = false;
        last = null;
        fraction = 0;
        actions.react(request, vector, temperature);
        render();
    }

    private void stop() {
        actions.react(-1, new double[0], 0);
        request++;                                     // ce que le moteur enverrait encore pour l'ancienne demande est ignoré
        running = false;
        stopped = true;
        render();
    }

    /** Ce que le moteur dit de la demande en cours. */
    void update(MoleculeReaction r) {
        if (!running || r.getRequest() != request || r == last) return;
        last = r;
        fraction = r.getFraction();
        if (r.getDone()) running = false;
        render();
    }

    /** Aperçus : une réaction reçue telle quelle (finie, ou en cours). */
    void preview(MoleculeReaction r) {
        request = r.getRequest();
        running = !r.getDone();
        stopped = false;
        last = r;
        fraction = r.getFraction();
        temperature = r.getTemperature();
        for (var t : group.getToggles()) t.setSelected(((ToggleButton) t).getText().equals((int) temperature + " K"));
        render();
    }

    private void render() {
        run.setText(I18n.text(running ? "Arrêter" : "Faire réagir  →"));
        reach.setText(I18n.text("franchit en " + TRIALS + " × " + (int) DURATION_FS + " fs des barrières jusqu'à ≈ "
                + MoleculeFormat.decimal(reachEv(temperature), 1) + " eV"));
        bar.setVisible(running);
        bar.setManaged(running);
        bar.requestLayout();
        results.getChildren().clear();
        MoleculeReaction r = last;
        status.setStyle("");
        if (r == null) {
            status.setText(running ? I18n.text("les réactifs se relaxent, chacun seul…")
                    : stopped ? I18n.text("arrêtée") : I18n.text("Ce que deviennent les molécules du dessin, chauffées ensemble."));
            show(reactants, false);
            show(limits, false);
            return;
        }
        if (!r.getError().isEmpty()) {
            status.setText(I18n.text(r.getError()));
            status.setStyle("-fx-text-fill:" + web(BAD) + ";");
        } else {
            status.setText(progress(r));
        }
        reactants.setText(MoleculeFormat.names(r.getReactants()) + "  →");
        show(reactants, !r.getReactants().isEmpty());
        for (MoleculeOutcome o : r.getOutcomesList()) results.getChildren().add(card(o, r.getTrials()));
        show(limits, r.getOutcomesCount() > 0);
    }

    private static void show(Region node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private String progress(MoleculeReaction r) {
        if (stopped) return I18n.text("arrêtée");
        String trial = "essai " + r.getTrial() + "/" + r.getTrials();
        return switch (r.getPhase()) {
            case 0 -> I18n.text("les réactifs se relaxent, chacun seul…");
            case 1 -> I18n.text(trial) + "  ·  " + MoleculeFormat.decimal(r.getTimeFs(), 0) + " fs"
                    + (r.getTimeFs() > r.getDurationFs() ? " (" + I18n.text("refroidi à 300 K") + ")" : "")
                    + (r.getCensus().isEmpty() ? "" : "  ·  " + MoleculeFormat.names(r.getCensus()))
                    + (r.getRemainingS() > 0 ? "  ·  " + remaining(r.getRemainingS()) : "");
            case 2 -> I18n.text(trial + " : ses produits se relaxent, chacun seul…");
            default -> I18n.text(r.getTrials() + " essais de " + (int) r.getDurationFs() + " fs à "
                    + (int) r.getTemperature() + " K");
        };
    }

    private static String remaining(double seconds) {
        long s = Math.round(seconds);
        return I18n.text(s < 60 ? "encore ≈ " + s + " s" : "encore ≈ " + s / 60 + " min " + String.format("%02d", s % 60) + " s");
    }

    /** Une issue : ses produits dessinés, leurs noms, combien d'essais, ΔE ; les reprendre ou les ouvrir en 3D. */
    private VBox card(MoleculeOutcome o, int trials) {
        Label name = MoleculeView.text(MoleculeFormat.names(o.getLabel()), 12, Plan.INK_HEX);
        name.setStyle(name.getStyle() + "-fx-font-weight:bold;");
        name.setWrapText(true);
        Label count = Plan.value(o.getCount() + "/" + trials);
        count.setMinWidth(Region.USE_PREF_SIZE);
        count.setTooltip(I18n.tooltip("Combien d'essais mènent à cette issue"));
        Region grow = new Region();
        HBox.setHgrow(grow, javafx.scene.layout.Priority.ALWAYS);
        HBox title = new HBox(8, name, grow, count);
        title.setAlignment(Pos.TOP_LEFT);
        VBox box = new VBox(5);
        if (o.getUnchanged()) {
            // Les réactifs eux-mêmes : ils sont déjà dessinés, leur nom suffit.
            box.getChildren().addAll(title, Plan.note(I18n.text("rien ne réagit : les réactifs restent")));
        } else {
            Canvas sketch = new Canvas(SKETCH_WIDTH, SKETCH_HEIGHT);
            drawProducts(sketch.getGraphicsContext2D(), o.getProductsList(), sketch.getWidth(), sketch.getHeight());
            Label line = Plan.note("ΔE " + MoleculeFormat.signed(o.getDeltaEv(), 2) + " eV" + (o.getFirstFs() > 0
                    ? "  ·  " + I18n.text("premier changement ≈ " + (int) Math.round(o.getFirstFs()) + " fs") : ""));
            line.setStyle("-fx-text-fill:" + web(o.getDeltaEv() < 0 ? GOOD : Plan.INK2) + ";");
            line.setTooltip(I18n.tooltip("ΔE = Σ E(produits seuls) − Σ E(réactifs seuls), GFN1-xTB : négatif, la réaction libère de l'énergie"));
            Button draw = MoleculeView.button("Dessiner", false);
            draw.setTooltip(I18n.tooltip("Les produits remplacent le dessin (Ctrl+Z : revenir)"));
            draw.setOnAction(e -> actions.draw(o.getProductsList()));
            Button open = MoleculeView.button("En 3D  ▶", false);
            open.setTooltip(I18n.tooltip("Les produits dans le simulateur 3D (le dessin ne change pas)"));
            open.setOnAction(e -> actions.open(o.getProductsList()));
            HBox buttons = new HBox(6, draw, open);
            box.getChildren().addAll(sketch, title, line, buttons);
        }
        box.setStyle("-fx-border-color:rgba(220,232,250,0.10);-fx-padding:8;");
        return box;
    }

    /**
     * Les produits côte à côte, séparés par « + », après une flèche ; chacun mis en page comme l'assembleur le dessinera
     * (MoleculeAssembler.arrange : liaisons égales, cycles réguliers, H autour de leur atome), liaisons simples, doubles,
     * triples, symboles des éléments et charges formelles.
     */
    static void drawProducts(GraphicsContext g, List<MoleculeEntry> products, double width, double height) {
        g.clearRect(0, 0, width, height);
        int n = products.size();
        if (n == 0) return;
        double arrow = 22, plus = 18, margin = .5;
        double[][][] laid = new double[n][][];
        double[][] bounds = new double[n][];
        double natural = 0, tallest = 0;
        for (int i = 0; i < n; i++) {
            MoleculeEntry e = products.get(i);
            laid[i] = layout(e);
            double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
            for (double[] q : laid[i]) {
                minX = Math.min(minX, q[0]);
                maxX = Math.max(maxX, q[0]);
                minY = Math.min(minY, -q[1]);
                maxY = Math.max(maxY, -q[1]);
            }
            bounds[i] = new double[]{minX, maxX, minY, maxY};
            natural += maxX - minX + 2 * margin;
            tallest = Math.max(tallest, maxY - minY + 2 * margin);
        }
        double unit = Math.min(34, Math.min((width - arrow - plus * (n - 1)) / natural, height / tallest));
        g.setStroke(Plan.INK3);
        g.setLineWidth(1);
        g.strokeLine(2, height / 2, arrow - 6, height / 2);
        g.strokeLine(arrow - 10, height / 2 - 3.5, arrow - 6, height / 2);
        g.strokeLine(arrow - 10, height / 2 + 3.5, arrow - 6, height / 2);
        double x = arrow + 2;                          // les produits juste après la flèche
        g.setTextAlign(TextAlignment.CENTER);
        for (int i = 0; i < n; i++) {
            MoleculeEntry e = products.get(i);
            double[] b = bounds[i];
            double left = x + margin * unit - b[0] * unit, top = height / 2 - (b[2] + b[3]) / 2 * unit;
            int atoms = e.getNumbersCount();
            double[] px = new double[atoms], py = new double[atoms];
            for (int k = 0; k < atoms; k++) {
                px[k] = left + laid[i][k][0] * unit;
                py[k] = top - laid[i][k][1] * unit;
            }
            for (int k = 0; k + 2 < e.getBondsCount(); k += 3) {
                int a = e.getBonds(k), c = e.getBonds(k + 1), order = e.getBonds(k + 2);
                if (a >= atoms || c >= atoms) continue;
                double dx = px[c] - px[a], dy = py[c] - py[a], len = Math.hypot(dx, dy);
                if (len < 1) continue;
                double ux = -dy / len * 2.2, uy = dx / len * 2.2;
                g.setStroke(Plan.INK2);
                g.setLineWidth(1.1);
                for (int s = 0; s < order; s++) {
                    double off = order == 1 ? 0 : order == 2 ? s - .5 : s - 1;
                    g.strokeLine(px[a] + ux * off, py[a] + uy * off, px[c] + ux * off, py[c] + uy * off);
                }
            }
            for (int k = 0; k < atoms; k++) {
                int z = e.getNumbers(k);
                boolean hydrogen = z == 1;
                double r = hydrogen ? 4.6 : 6.4;
                g.setFill(Plan.PAPER);
                g.fillOval(px[k] - r, py[k] - r, 2 * r, 2 * r);
                g.setFont(hydrogen ? Plan.sans(9.5) : Plan.bold(11));
                g.setFill(hydrogen ? Plan.INK2 : MoleculeScene.elementColor(z).deriveColor(0, .85, 1.3, 1));
                g.fillText(MoleculeView.symbolOf(z), px[k], py[k] + (hydrogen ? 3.3 : 4));
                int q = k < e.getChargesCount() ? e.getCharges(k) : 0;
                if (q != 0) {
                    g.setFont(Plan.bold(9));
                    g.setFill(q > 0 ? MoleculeScene.POOR : MoleculeScene.RICH);
                    g.fillText((Math.abs(q) > 1 ? String.valueOf(Math.abs(q)) : "") + (q > 0 ? "+" : "−"), px[k] + r + 2, py[k] - r + 3);
                }
            }
            x += (b[1] - b[0] + 2 * margin) * unit;
            if (i < n - 1) {
                g.setFont(Plan.sans(13));
                g.setFill(Plan.INK3);
                g.fillText("+", x + plus / 2, height / 2 + 4.5);
                x += plus;
            }
        }
    }

    /** La mise en page d'un produit (une liaison vaut 1, y vers le haut), celle que « Dessiner » posera. */
    static double[][] layout(MoleculeEntry e) {
        int n = e.getNumbersCount();
        int[] z = new int[n];
        for (int k = 0; k < n; k++) z[k] = e.getNumbers(k);
        List<int[]> bonds = new java.util.ArrayList<>();
        for (int k = 0; k + 2 < e.getBondsCount(); k += 3) bonds.add(new int[]{e.getBonds(k), e.getBonds(k + 1), e.getBonds(k + 2)});
        double[][] start = null;
        if (e.getXyCount() == 2 * n) {
            start = new double[n][];
            for (int k = 0; k < n; k++) start[k] = new double[]{e.getXy(2 * k), e.getXy(2 * k + 1)};
        }
        return MoleculeAssembler.arrange(z, bonds, start);
    }

    private static String web(Color c) {
        return String.format("#%02X%02X%02X", (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }
}
