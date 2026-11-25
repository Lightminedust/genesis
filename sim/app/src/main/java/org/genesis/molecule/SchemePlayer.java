package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeLabState;
import com.studio.genesis.grpc.MoleculeScheme;
import com.studio.genesis.grpc.MoleculeSchemePlay;
import com.studio.genesis.grpc.MoleculeSchemeStep;
import org.genesis.app.I18n;
import org.genesis.common.Icons;
import org.genesis.common.Plan;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Le chemin d'une étape du schéma, joué dans la scène 3D : le moteur pose chaque géométrie du chemin calculé et la
 * recalcule (densité, liaisons, énergie : la scène montre tout comme d'habitude). Cette carte en est le transport —
 * jouer, revenir, passer à l'étape suivante —, le profil d'énergie avec la position courante, et les liaisons qui
 * changent avec leur indice de Mayer à l'instant montré (calculé, de ~0 pour une liaison absente à ~1, 2, 3).
 */
final class SchemePlayer {
    interface Actions {
        void play(int step, double position, boolean running);
        void openEditor();
        /** Le même chemin dans sa vue 2.5D de lecture (SchemeStage). */
        default void openStage() {}
    }

    private final Actions actions;
    private final VBox card = new VBox(8);
    private final Label title = new Label();
    /** Les formules de l'étape (réactifs → produits), indices en vrais chiffres. */
    private final javafx.scene.text.TextFlow what = new javafx.scene.text.TextFlow();
    private final Label verdict = new Label();
    private final Canvas profile = new Canvas(300, 70);
    private final Slider position = Plan.filled(new Slider(0, 1, 0));
    private final Button play = Plan.icon("play", null, "Jouer le chemin calculé");
    private final Button previous = Plan.icon("step-back", null, "Étape précédente");
    private final Button next = Plan.icon("step-forward", null, "Étape suivante");
    private final GridPane changes = new GridPane();
    /** Replié par défaut : la carte ne garde que le transport et le profil, la molécule a la place. */
    private final Button details = Plan.quiet("Détails ▾");
    private MoleculeScheme scheme;
    private int step = -1;
    private boolean running, seeking;
    private int[] numbers = new int[0];

    SchemePlayer(Actions actions) {
        this.actions = actions;
        title.getStyleClass().add("plan-heading");
        verdict.setWrapText(true);
        play.setOnAction(e -> actions.play(step, -1, !running));
        previous.setOnAction(e -> { if (step > 0) actions.play(step - 1, 0, true); });
        next.setOnAction(e -> { if (scheme != null && step + 1 < scheme.getStepsCount()) actions.play(step + 1, 0, true); });
        Button flat = Plan.icon("layers", "Replay", "Le même chemin dans sa vue 2.5D de lecture : légende, courbes image "
                + "par image, liaisons qui changent");
        flat.setOnAction(e -> actions.openStage());
        Button stop = Plan.icon("close", null, "Fermer le chemin : la scène reste, libre (chauffer, relaxer, tirer un atome)");
        stop.setOnAction(e -> actions.play(-1, -1, false));
        position.setOnMousePressed(e -> seeking = true);
        position.setOnMouseReleased(e -> {
            seeking = false;
            actions.play(step, position.getValue(), false);
        });
        HBox.setHgrow(position, Priority.ALWAYS);
        HBox transport = new HBox(4, previous, play, next, position, details);
        transport.setAlignment(Pos.CENTER_LEFT);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        // Le retour à l'éditeur est dans l'en-tête du laboratoire (« Schéma de réaction ») : la carte ne le répète pas.
        details.setOnAction(e -> {
            boolean open = !changes.isVisible();
            changes.setVisible(open);
            changes.setManaged(open);
            verdict.setVisible(open);
            verdict.setManaged(open);
            details.setText(I18n.text(open ? "Détails ▴" : "Détails ▾"));
        });
        changes.setVisible(false);
        changes.setManaged(false);
        verdict.setVisible(false);
        verdict.setManaged(false);
        HBox head = new HBox(6, title, gap, flat, stop);
        head.setAlignment(Pos.CENTER_LEFT);
        changes.setHgap(10);
        changes.setVgap(3);
        card.getChildren().addAll(head, what, profile, transport, changes, verdict);
        card.setPadding(new Insets(12));
        card.getStyleClass().add("plan-fact");
        card.setStyle("-fx-background-color: rgba(17,20,25,0.94); -fx-background-radius: 10;");
        card.setMaxWidth(330);
        card.setPrefWidth(330);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setVisible(false);
        card.setManaged(false);
    }

    Region getNode() {
        return card;
    }

    void update(MoleculeLabState s, MoleculeScheme latest) {
        MoleculeSchemePlay p = s.getPlay();
        boolean shown = s.hasPlay() && p.getStep() >= 0 && latest != null && p.getStep() < latest.getStepsCount();
        card.setVisible(shown);
        card.setManaged(shown);
        if (!shown) {
            step = -1;
            return;
        }
        if (latest != scheme || p.getStep() != step) {
            scheme = latest;
            step = p.getStep();
            MoleculeSchemeStep st = scheme.getSteps(step);
            title.setText(I18n.text("Étape ") + (step + 1) + " / " + scheme.getStepsCount());
            what.getChildren().setAll(MoleculeFormat.formula(MoleculeFormat.names(st.getReactants()) + "  →  "
                    + MoleculeFormat.names(st.getProducts()) + (st.getByproducts().isEmpty() ? ""
                    : "  (+ " + MoleculeFormat.names(st.getByproducts()) + ")"), Plan.sans(12), Plan.INK2).getChildren());
            verdict.setText(st.getVerdict());
            verdict.setStyle("-fx-font-size:11px;-fx-text-fill:" + (st.getConfirmed() ? Plan.GOOD_HEX : Plan.BAD_HEX) + ";");
            previous.setDisable(step == 0);
            next.setDisable(step + 1 >= scheme.getStepsCount());
        }
        numbers = s.getAtomsList().stream().mapToInt(a -> a.getZ()).toArray();
        running = p.getRunning();
        play.setGraphic(Icons.path(running ? "pause" : "play", 16));
        if (!seeking) position.setValue(p.getPosition());
        MoleculeSchemeStep st = scheme.getSteps(step);
        SchemeEditor.paintProfile(profile, st, p.getPosition());
        changes(st, p.getPosition());
    }

    /**
     * Les liaisons qui changent : leur ordre dans le dessin (avant → après), et l'indice de Mayer à la position montrée
     * — calculé aux images, interpolé entre elles comme la géométrie de la scène (il restait sur l'image la plus proche
     * pendant que la scène montrait déjà l'entre-deux).
     */
    private void changes(MoleculeSchemeStep st, double position) {
        changes.getChildren().clear();
        int count = st.getChangesCount() / 4;
        int frames = st.getEnergiesCount();
        double s = Math.max(0, Math.min(1, position)) * Math.max(0, frames - 1);
        int frame = Math.max(0, Math.min(Math.max(0, frames - 2), (int) Math.floor(s)));
        double t = frames > 1 ? s - frame : 0;
        Label h1 = Plan.label("Liaison"), h2 = Plan.label("Dessin"), h3 = Plan.label("Mayer, maintenant");
        Tooltip.install(h2, I18n.tooltip("L'ordre de la liaison dans le dessin, avant → après (un complexe d'alcyne se "
                + "dessine en simple liaison C–C) ; l'indice de Mayer, lui, est calculé"));
        changes.addRow(0, h1, h2, h3);
        int shown = Math.min(count, 7);
        java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int k = 0; k < count; k++) order.add(k);
        order.sort(java.util.Comparator.comparingInt(k -> st.getChanges(4 * k + 2) == 0 || st.getChanges(4 * k + 3) == 0 ? 0 : 1));
        for (int row = 0; row < shown; row++) {
            int k = order.get(row);
            int a = st.getChanges(4 * k), b = st.getChanges(4 * k + 1), before = st.getChanges(4 * k + 2), after = st.getChanges(4 * k + 3);
            String name = symbol(a) + number(st, a) + "–" + symbol(b) + number(st, b);
            String how = before == 0 ? I18n.text("se forme") : after == 0 ? I18n.text("se rompt") : before + " → " + after;
            int index = frame * count + k, nextIndex = Math.min(frames - 1, frame + 1) * count + k;
            double ma = index < st.getOrdersCount() ? st.getOrders(index) : 0;
            double mayer = ma + ((nextIndex < st.getOrdersCount() ? st.getOrders(nextIndex) : ma) - ma) * t;
            Label value = Plan.value(String.format(I18n.locale(), "%.2f", mayer));
            Label kind = Plan.note(how);
            String hex = before == 0 ? "#5FE0B4" : after == 0 ? "#F5A45D" : null;
            if (hex != null) kind.setStyle("-fx-text-fill:" + hex + ";");
            changes.addRow(row + 1, Plan.value(name), kind, value);
        }
        if (count > shown) changes.addRow(shown + 1, Plan.note("+ " + (count - shown)));
    }

    /** Le numéro de l'atome dans tout le schéma (un atome repris d'une étape à l'autre garde le sien). */
    static int number(MoleculeSchemeStep st, int atom) {
        return atom >= 0 && atom < st.getIdsCount() && st.getIds(atom) > 0 ? st.getIds(atom) : atom + 1;
    }

    private String symbol(int atom) {
        return atom < numbers.length ? MoleculeView.symbolOf(numbers[atom]) : "?";
    }
}
