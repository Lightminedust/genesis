package org.genesis.app;

import com.studio.genesis.grpc.UniverseFrame;
import java.util.function.Predicate;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Window;
import org.genesis.common.Brand;
import org.genesis.common.Plan;

/**
 * L'écran d'attente : dès qu'on change de laboratoire, il dit que le moteur
 * prépare ce qu'on a demandé, combien de temps s'est écoulé, et il s'efface
 * quand le moteur a vraiment répondu (la frame annonce le laboratoire actif).
 * Il se pose d'un coup, sur l'image figée de l'écran au moment du clic, qui fond ensuite vers son fond opaque : le
 * moteur referme l'ancien laboratoire et ouvre le nouveau en quelques millisecondes (mesuré : 1 à 50 ms une fois les
 * données chargées), et rien de ce passage — l'ancien laboratoire qui s'éteint, l'atome entre les deux — ne doit se
 * voir. La carte n'apparaît que si l'attente dure : un changement rapide est un simple fondu d'un écran à l'autre.
 * S'il ne répond pas, il dit pourquoi : moteur obsolète, injoignable, ou lent. (Au démarrage du logiciel, c'est
 * StartupScreen qui attend le moteur.)
 */
public final class LabLoader {
    /** L'image figée fond vers le fond en DISSOLVE s ; la carte n'apparaît qu'après CARD_DELAY s d'attente. */
    static final double DISSOLVE = 0.2, CARD_DELAY = 0.3, CARD_FADE = 0.2, FADE_OUT = 0.25;

    private final StackPane root = new StackPane();
    private final ImageView still = new ImageView();
    private final VBox card;
    /** Le G_ qui attend : sa hauteur (px) ; son premier glitch (s), quand la carte a fini de paraître. */
    private static final double MARK_PX = 46, FIRST_GLITCH = CARD_DELAY + CARD_FADE;
    private final Canvas spinner = new Canvas(110, 66);
    private final Label title = new Label();
    private final Label detail = new Label();
    private final Label elapsedLabel = new Label();
    private final Button cancel = new Button("Annuler");
    private Predicate<UniverseFrame> ready;
    private Runnable onCancel;
    private double elapsed;
    private double fade;
    private double frozen;
    private boolean fresh;
    private boolean failed;
    private boolean showing;

    LabLoader() {
        title.getStyleClass().add("plan-heading");
        detail.getStyleClass().add("plan-note");
        detail.setWrapText(true);
        detail.setMaxWidth(380);
        detail.setAlignment(Pos.CENTER);
        detail.setStyle("-fx-text-alignment: center;");
        elapsedLabel.getStyleClass().add("plan-label");
        cancel.getStyleClass().add("dash-button");
        cancel.setFocusTraversable(false);
        cancel.setOnAction(event -> {
            hide();
            if (onCancel != null) {
                onCancel.run();
            }
        });
        card = new VBox(12, spinner, title, detail, elapsedLabel, new HBox(cancel));
        ((HBox) card.getChildren().get(4)).setAlignment(Pos.CENTER);
        card.setAlignment(Pos.CENTER);
        card.setMaxSize(460, VBox.USE_PREF_SIZE);
        card.getStyleClass().add("plan-card");
        card.setStyle("-fx-padding: 26 32 22 32;");
        // L'image figée ne compte pas dans la mise en page : elle recouvre exactement ce qu'elle a photographié.
        still.setManaged(false);
        still.setSmooth(true);
        root.getChildren().addAll(still, card);
        root.setStyle("-fx-background-color: radial-gradient(center 50% 45%, radius 80%, #121722 0%, #0C0E12 55%, #07090C 100%);");
        root.setVisible(false);
        root.setOpacity(0);
    }

    StackPane getNode() {
        return root;
    }

    boolean isShowing() {
        return showing;
    }

    /** L'image figée encore visible (0 à 1) : pour les tests. */
    double frozenForTest() {
        return still.getImage() == null ? 0 : frozen;
    }

    /** L'opacité de la carte : pour les tests. */
    double cardForTest() {
        return card.getOpacity();
    }

    /** Attendre que le moteur ait ouvert (ou fermé) un écran : `ready` dit quand la frame le confirme. */
    void show(String heading, String text, Predicate<UniverseFrame> ready, Runnable cancelAction) {
        this.ready = ready;
        this.onCancel = cancelAction;
        title.setText(heading);
        detail.setText(text);
        failed = false;
        cancel.setText("Annuler");
        if (!showing) {               // déjà posé (un écran en remplace un autre pendant l'attente) : il reste tel quel
            freeze();
            elapsed = 0;
            card.setOpacity(0);
            card.setMouseTransparent(true);
        }
        showing = true;
        fade = 1;                     // tout de suite opaque : on ne voit jamais à travers
        root.setOpacity(1);
        root.setVisible(true);
    }

    /**
     * L'écran tel qu'il est au clic (l'accueil, ou le laboratoire qu'on quitte), à la résolution de l'écran : l'attente
     * se pose dessus sans que rien ne bouge, puis fond vers son fond. Sans fenêtre (aperçus), pas d'image : le fond seul.
     */
    private void freeze() {
        still.setImage(null);
        frozen = 0;
        Parent behind = root.getParent();
        Window window = behind == null || behind.getScene() == null ? null : behind.getScene().getWindow();
        if (window == null || !window.isShowing()) return;
        boolean visible = root.isVisible();
        root.setVisible(false);       // la trace d'un fondu de sortie encore en cours n'entre pas dans l'image
        try {
            Bounds bounds = behind.getBoundsInLocal();
            if (bounds.getWidth() >= 1 && bounds.getHeight() >= 1) {
                SnapshotParameters parameters = new SnapshotParameters();
                parameters.setFill(Plan.DEEP);
                parameters.setTransform(Transform.scale(window.getOutputScaleX(), window.getOutputScaleY()));
                still.setImage(behind.snapshot(parameters, null));
                still.relocate(bounds.getMinX(), bounds.getMinY());
                still.setFitWidth(bounds.getWidth());
                still.setFitHeight(bounds.getHeight());
                frozen = 1;
                fresh = true;
                // La première image affichée est l'image entière : le pas qui suit le clic (une capture, un
                // laboratoire qui se prépare) peut durer jusqu'à 0,1 s et assombrirait l'écran d'un coup.
            }
        } catch (RuntimeException error) {
            still.setImage(null);     // pas d'image : l'attente se pose directement sur son fond
        } finally {
            root.setVisible(visible);
        }
        still.setOpacity(frozen);
    }

    /** Une frame est arrivée : si elle confirme l'écran demandé, l'attente est finie. */
    void onFrame(UniverseFrame frame) {
        if (showing && ready != null && ready.test(frame)) {
            hide();
        }
    }

    /** Le moteur ne pourra pas répondre : on le dit, au lieu d'attendre pour rien. */
    void fail(String heading, String text) {
        if (!showing) {
            return;
        }
        failed = true;
        title.setText(heading);
        detail.setText(text);
        cancel.setText("Fermer");
    }

    void hide() {
        showing = false;
    }

    void tick(double dt) {
        if (!root.isVisible()) {
            return;
        }
        fade = showing ? 1 : Math.max(0, fade - dt / FADE_OUT);
        frozen = fresh ? frozen : Math.max(0, frozen - dt / DISSOLVE);
        fresh = false;
        root.setOpacity(fade);
        still.setOpacity(frozen);
        if (frozen <= 0 && still.getImage() != null) {
            still.setImage(null);     // fondue : la texture est rendue
        }
        if (fade <= 0) {
            root.setVisible(false);
            return;
        }
        elapsed += dt;
        if (showing) {                // en sortie, la carte s'efface avec tout l'écran, sans repartir de zéro
            double shown = Math.max(0, Math.min(1, (elapsed - CARD_DELAY) / CARD_FADE));
            card.setOpacity(shown);
            card.setMouseTransparent(shown < 0.5);
        }
        if (!failed) {
            elapsedLabel.setText(String.format(I18n.locale(), "%.1f s", elapsed)
                    + (elapsed > 20 ? " · le moteur prépare encore les données" : ""));
        } else {
            elapsedLabel.setText("");
        }
        draw();
    }

    /**
     * Le G_ de la marque (assets/brand) qui attend : son curseur clignote comme celui d'un terminal, et il glitche par
     * bouffées (Brand.glitchAt), la première quand la carte vient de paraître : ses traits sautent ailleurs, une tranche se
     * déchire, puis tout revient en place. Si le moteur ne répondra pas, plus de glitch : le curseur s'arrête, en rouge.
     */
    private void draw() {
        GraphicsContext g = spinner.getGraphicsContext2D();
        double w = spinner.getWidth(), h = spinner.getHeight();
        g.clearRect(0, 0, w, h);
        Brand.Art art = Brand.MARK;
        double s = MARK_PX / art.height();
        g.save();
        g.translate((w - art.width() * s) / 2 - art.minX() * s, (h - art.height() * s) / 2 - art.minY() * s);
        g.scale(s, s);
        int cursor = art.slots() - 1;
        boolean on = failed || Brand.cursorOn(elapsed);
        Brand.draw(g, art, slot -> slot == cursor && !on ? 0 : 1,
                failed ? null : Brand.glitchAt(art, elapsed - FIRST_GLITCH), failed ? Color.web("#F2766B") : null);
        g.restore();
    }
}
