package org.genesis.app;

import org.genesis.common.Plan;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * Un message bref en bas de l'écran (« Image enregistrée », « Aucun noyau à viser ») qui s'efface de lui-même ; avec,
 * s'il le faut, une action (« Ouvrir le dossier »). Il ne prend la souris que sur lui-même.
 */
final class Toast {
    private final StackPane root = new StackPane();
    private final Label text = new Label();
    private final Button action = Plan.quiet("");
    private final HBox box = new HBox(10, text, action);
    private SequentialTransition life;

    Toast() {
        box.getStyleClass().add("toast");
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        text.setWrapText(true);
        text.setMaxWidth(520);
        root.getChildren().add(box);
        StackPane.setAlignment(box, Pos.BOTTOM_CENTER);
        root.setPickOnBounds(false);
        root.setMouseTransparent(false);
        box.setOpacity(0);
        box.setVisible(false);
        root.setPadding(new javafx.geometry.Insets(0, 0, 76, 0));
    }

    StackPane getNode() {
        return root;
    }

    /** Montre `message` quelques secondes ; `label`/`then` : une action proposée (null : aucune). */
    void show(String message, String label, Runnable then) {
        text.setText(I18n.text(message));
        boolean hasAction = label != null && then != null;
        Plan.show(action, hasAction);
        if (hasAction) {
            action.setText(I18n.text(label));
            action.setOnAction(e -> {
                then.run();
                hide();
            });
        }
        if (life != null) life.stop();
        box.setVisible(true);
        FadeTransition in = new FadeTransition(Duration.millis(160), box);
        in.setToValue(1);
        PauseTransition stay = new PauseTransition(Duration.seconds(hasAction ? 6 : 3.5));
        FadeTransition out = new FadeTransition(Duration.millis(300), box);
        out.setToValue(0);
        life = new SequentialTransition(in, stay, out);
        life.setOnFinished(e -> box.setVisible(false));
        life.play();
    }

    void hide() {
        if (life != null) life.stop();
        box.setOpacity(0);
        box.setVisible(false);
    }
}
