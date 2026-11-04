package org.genesis.app;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * « Statistiques d'utilisation » : la carte du premier lancement, en bas à gauche, qui ne bloque rien. Elle dit ce que
 * Genesis envoie, à qui et pourquoi, et permet de refuser — refusé, plus rien n'est jamais envoyé. Elle reste jusqu'à
 * une réponse ; sans réponse, l'envoi par défaut s'applique à la fermeture du logiciel. Le menu ⚙ la rouvre, pour
 * relire ce qui est envoyé, couper ou rallumer.
 */
final class TelemetryNotice {
    /** Ce qui est envoyé, en clair : la même liste que la documentation (docs/PRIVACY.md). */
    static final String[] SENT = {
            "quand Genesis s'ouvre et se ferme, et combien de temps il a servi (dont au premier plan)",
            "les laboratoires ouverts, et le temps passé dans chacun",
            "la version de Genesis, le système, la langue, l'écran, le nombre de cœurs et la mémoire",
            "un identifiant aléatoire créé à l'installation, sans nom ni e-mail ; l'adresse IP, dont le serveur déduit le pays et la ville sans la garder",
    };

    private final StackPane layer = new StackPane();
    private final VBox card = new VBox(9);
    private final VBox details = new VBox(4);
    private final Label title = new Label();
    private final Label text = new Label();
    /** L'identifiant de l'installation, à donner pour demander ses données ou leur effacement (relecture seulement). */
    private final Label identity = new Label();
    private final Button accept = new Button("D'accord");
    private final Button refuse = new Button("Refuser l'envoi");
    private final Button more = new Button("Ce qui est envoyé ▾");
    private Consumer<Boolean> answer;
    private double shown;
    private boolean wanted;

    TelemetryNotice() {
        title.setStyle("-fx-text-fill: #E6EDF7; -fx-font-size: 13.5px; -fx-font-weight: bold;");
        text.setStyle("-fx-text-fill: #A9B8D0; -fx-font-size: 11.5px;");
        text.setWrapText(true);
        Label badge = new Label("STATISTIQUES D'UTILISATION");
        badge.setStyle("-fx-text-fill: #7DD3FC; -fx-font-size: 9.5px; -fx-font-weight: bold;");
        for (String item : SENT) {
            // La puce à part : la phrase seule est la clé de sa traduction.
            Label bullet = new Label("•"), line = new Label(item);
            bullet.setStyle("-fx-text-fill: #5B6B86; -fx-font-size: 11px;");
            bullet.setMinWidth(Region.USE_PREF_SIZE);
            line.setWrapText(true);
            line.setStyle("-fx-text-fill: #8B9BB8; -fx-font-size: 11px;");
            HBox row = new HBox(6, bullet, line);
            row.setAlignment(Pos.TOP_LEFT);
            details.getChildren().add(row);
        }
        Label never = new Label("Jamais envoyé : ce que tu construis, calcules ou exportes, ni tes fichiers.");
        never.setWrapText(true);
        never.setStyle("-fx-text-fill: #8B9BB8; -fx-font-size: 11px; -fx-font-style: italic;");
        details.getChildren().add(never);
        identity.setWrapText(true);
        identity.setStyle("-fx-text-fill: #6F86A8; -fx-font-size: 10.5px; -fx-font-family: Consolas, monospace;");
        identity.setVisible(false);
        identity.setManaged(false);
        details.getChildren().add(identity);
        details.setVisible(false);
        details.setManaged(false);
        accept.getStyleClass().add("update-primary");
        refuse.getStyleClass().add("dash-button");
        more.getStyleClass().add("update-quiet");
        for (Button b : new Button[]{accept, refuse, more}) {
            b.setFocusTraversable(false);
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        accept.setOnAction(e -> reply(true));
        refuse.setOnAction(e -> reply(false));
        more.setOnAction(e -> {
            boolean open = !details.isVisible();
            details.setVisible(open);
            details.setManaged(open);
            more.setText(I18n.text(open ? "Ce qui est envoyé ▴" : "Ce qui est envoyé ▾"));
        });
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(6, accept, refuse, grow, more);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Label settings = new Label("Modifiable à tout moment : ⚙ en haut à droite.");
        settings.setStyle("-fx-text-fill: #5B6B86; -fx-font-size: 10.5px;");
        card.getChildren().addAll(badge, title, text, details, buttons, settings);
        card.getStyleClass().add("update-card");
        card.setMaxSize(400, Region.USE_PREF_SIZE);
        card.setPrefWidth(400);
        layer.getChildren().add(card);
        StackPane.setAlignment(card, Pos.BOTTOM_LEFT);
        StackPane.setMargin(card, new Insets(0, 0, 222, 20));      // au-dessus de la barre de vue et de la timeline
        layer.setPickOnBounds(false);
        layer.setVisible(false);
    }

    StackPane getNode() {
        return layer;
    }

    boolean isShowing() {
        return wanted;
    }

    /**
     * Montrer la carte. `firstRun` : la question du premier lancement (d'accord / refuser) ; sinon, la relecture depuis
     * le menu, selon l'état (`enabled`) : on peut couper ou rallumer. `answer` reçoit le choix.
     */
    void show(boolean firstRun, boolean enabled, String install, Consumer<Boolean> answer) {
        this.answer = answer;
        identity.setText(I18n.text("Identifiant de cette installation (pour demander tes données ou leur effacement : "
                + "nellawassi@gmail.com) :") + " " + install);
        identity.setVisible(!firstRun);
        identity.setManaged(!firstRun);
        title.setText(I18n.text(firstRun ? "Aider à améliorer Genesis" : enabled ? "L'envoi est actif" : "L'envoi est coupé"));
        text.setText(I18n.text("Genesis envoie au serveur de Gemmie (gemmi-e.com) des mesures d'utilisation : quand il "
                + "s'ouvre, combien de temps il sert, quels laboratoires. Elles servent uniquement à faire évoluer Genesis. "
                + "Refuser ne change rien au logiciel : plus rien ne sera envoyé."));
        accept.setText(I18n.text(firstRun ? "D'accord" : enabled ? "Fermer" : "Réactiver l'envoi"));
        // Relu depuis le menu et déjà coupé : « Fermer » laisse l'envoi coupé.
        refuse.setText(I18n.text(firstRun ? "Refuser l'envoi" : enabled ? "Couper l'envoi" : "Fermer"));
        if (!firstRun) {
            details.setVisible(true);
            details.setManaged(true);
            more.setText(I18n.text("Ce qui est envoyé ▴"));
        }
        wanted = true;
    }

    private void reply(boolean yes) {
        wanted = false;
        if (answer != null) answer.accept(yes);
    }

    void tick(double dt) {
        shown = Math.max(0, Math.min(1, shown + (wanted ? dt / 0.38 : -dt / 0.22)));
        double eased = 1 - Math.pow(1 - shown, 3);
        card.setOpacity(eased);
        card.setTranslateY(18 * (1 - eased));
        layer.setVisible(shown > 0);
    }
}
