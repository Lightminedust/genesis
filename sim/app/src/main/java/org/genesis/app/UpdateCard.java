package org.genesis.app;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * « Une nouvelle version est disponible » : une petite carte en bas à droite, qui glisse en place sans bloquer l'écran.
 * Elle propose d'installer (Windows : l'installateur vérifié), d'ouvrir la page de la version, de remettre à plus tard
 * ou d'ignorer cette version.
 */
final class UpdateCard {
    /** Ce que la carte demande à l'application. */
    interface Actions {
        void install();

        void openPage();

        void skip();
    }

    private final StackPane layer = new StackPane();
    private final VBox card = new VBox(8);
    private final Label title = new Label();
    private final Label detail = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final Button install = new Button("Installer maintenant");
    private final Button page = new Button("Voir la version");
    private final Button later = new Button("Plus tard");
    private final Button skip = new Button("Ignorer cette version");
    private Actions actions;
    /** Apparition, de 0 à 1 ; `wanted` : la carte doit-elle être là. Animée par tick, comme l'écran d'attente. */
    private double shown;
    private boolean wanted;

    UpdateCard() {
        title.setStyle("-fx-text-fill: #E6EDF7; -fx-font-size: 13.5px; -fx-font-weight: bold;");
        detail.setStyle("-fx-text-fill: #8B9BB8; -fx-font-size: 11.5px;");
        detail.setWrapText(true);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("update-progress");
        progress.setVisible(false);
        progress.setManaged(false);
        install.getStyleClass().add("update-primary");
        page.getStyleClass().add("dash-button");
        later.getStyleClass().add("update-quiet");
        skip.getStyleClass().add("update-quiet");
        for (Button button : new Button[]{install, page, later, skip}) {
            button.setFocusTraversable(false);
            button.setMinWidth(Region.USE_PREF_SIZE);          // jamais tronqué, même en français
        }
        install.setOnAction(event -> actions.install());
        page.setOnAction(event -> actions.openPage());
        later.setOnAction(event -> hide());
        skip.setOnAction(event -> {
            actions.skip();
            hide();
        });
        HBox buttons = new HBox(6, install, page);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox quiet = new HBox(10, later, grow, skip);
        quiet.setAlignment(Pos.CENTER_LEFT);

        Label badge = new Label("MISE À JOUR");
        badge.setStyle("-fx-text-fill: #7DD3FC; -fx-font-size: 9.5px; -fx-font-weight: bold;");
        card.getChildren().addAll(badge, title, detail, progress, buttons, quiet);
        card.getStyleClass().add("update-card");
        card.setMaxSize(360, Region.USE_PREF_SIZE);
        card.setPrefWidth(360);

        layer.getChildren().add(card);
        StackPane.setAlignment(card, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(card, new Insets(0, 20, 150, 0));     // au-dessus de la timeline
        layer.setPickOnBounds(false);                              // le reste de l'écran reste cliquable
        layer.setVisible(false);
    }

    StackPane getNode() {
        return layer;
    }

    boolean isShowing() {
        return wanted;
    }

    /** Glisse en place (0,38 s) et s'efface (0,22 s), en douceur. */
    void tick(double dt) {
        shown = Math.max(0, Math.min(1, shown + (wanted ? dt / 0.38 : -dt / 0.22)));
        double eased = 1 - Math.pow(1 - shown, 3);
        card.setOpacity(eased);
        card.setTranslateY(18 * (1 - eased));
        layer.setVisible(shown > 0);
    }

    /** Proposer la version `latest` ; `canInstall` : un installateur vérifiable existe pour ce système. */
    void offer(String current, String latest, boolean canInstall, Actions actions) {
        this.actions = actions;
        title.setText(I18n.text("Genesis ") + latest + I18n.text(" est disponible"));
        detail.setText(I18n.text("Tu utilises la version ") + current + I18n.text(". ")
                + I18n.text(canInstall ? "L'installateur est vérifié (SHA-256) avant d'être lancé ; Genesis se ferme pendant l'installation."
                : "Le téléchargement s'ouvre dans ton navigateur."));
        install.setVisible(canInstall);
        install.setManaged(canInstall);
        install.setDisable(false);
        page.setText(I18n.text(canInstall ? "Voir la version" : "Télécharger"));
        progress.setVisible(false);
        progress.setManaged(false);
        wanted = true;
    }

    /** Le téléchargement avance : `fraction` de 0 à 1. */
    void downloading(double fraction) {
        install.setDisable(true);
        progress.setVisible(true);
        progress.setManaged(true);
        progress.setProgress(fraction);
        detail.setText(I18n.text("Téléchargement… ") + Math.round(fraction * 100) + " %");
    }

    void status(String text) {
        detail.setText(I18n.text(text));
    }

    void failed(String reason) {
        install.setDisable(false);
        progress.setVisible(false);
        progress.setManaged(false);
        detail.setText(I18n.text("Installation impossible : ") + I18n.text(reason));
    }

    void hide() {
        wanted = false;
    }
}
