package org.genesis.app;

import com.studio.genesis.grpc.UniverseFrame;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;

/**
 * L'écran d'attente : dès qu'on change de laboratoire, il dit que le moteur
 * prépare ce qu'on a demandé, combien de temps s'est écoulé, et il s'efface
 * quand le moteur a vraiment répondu (la frame annonce le laboratoire actif).
 * S'il ne répond pas, il dit pourquoi : moteur obsolète, injoignable, ou lent.
 */
public final class LabLoader {
    private final StackPane root = new StackPane();
    private final Canvas spinner = new Canvas(84, 84);
    private final Label title = new Label();
    private final Label detail = new Label();
    private final Label elapsedLabel = new Label();
    private final Button cancel = new Button("Annuler");
    /** Le petit cadre « Soutenir Genesis », montré seulement pendant le démarrage. */
    private final VBox support = new VBox(8);
    private Consumer<String> opener;
    private Predicate<UniverseFrame> ready;
    private Runnable onCancel;
    private double elapsed;
    private double angle;
    private double fade;
    private boolean failed;
    private boolean showing;

    LabLoader() {
        title.setStyle("-fx-text-fill: #E6EDF7; -fx-font-size: 17px; -fx-font-weight: bold;");
        detail.setStyle("-fx-text-fill: #8B9BB8; -fx-font-size: 12.5px;");
        detail.setWrapText(true);
        detail.setMaxWidth(380);
        detail.setAlignment(Pos.CENTER);
        detail.setStyle(detail.getStyle() + " -fx-text-alignment: center;");
        elapsedLabel.setStyle("-fx-text-fill: #5B6B86; -fx-font-size: 11px; -fx-font-family: Consolas, monospace;");
        cancel.getStyleClass().add("dash-button");
        cancel.setFocusTraversable(false);
        cancel.setOnAction(event -> {
            hide();
            if (onCancel != null) {
                onCancel.run();
            }
        });
        buildSupport();
        VBox card = new VBox(12, spinner, title, detail, elapsedLabel, new HBox(cancel), support);
        ((HBox) card.getChildren().get(4)).setAlignment(Pos.CENTER);
        card.setAlignment(Pos.CENTER);
        card.setMaxSize(460, VBox.USE_PREF_SIZE);
        card.setStyle("-fx-background-color: #0E1524; -fx-background-radius: 16; -fx-border-color: #26344F;"
                + " -fx-border-radius: 16; -fx-padding: 28 34 24 34;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 30, 0.2, 0, 10);");
        root.getChildren().add(card);
        root.setStyle("-fx-background-color: rgba(4,7,14,0.72);");
        root.setVisible(false);
        root.setOpacity(0);
    }

    /** Liens du cadre de soutien : texte affiché, adresse ouverte. */
    static final String[][] SUPPORT_LINKS = {
            {"★  GitHub", "https://github.com/Lightminedust/genesis"},
            {"Site de Genesis", "https://genesis.gemmi-e.com"},
            {"Gemmie", "https://gemmi-e.com/contact"},
            {"r/gemmie", "https://www.reddit.com/r/gemmie/"},
            {"✉  nellawassi@gmail.com", "mailto:nellawassi@gmail.com"},
    };

    /** Discret par construction : un filet, deux lignes de texte, des liens en pastilles sans couleur vive. */
    private void buildSupport() {
        Region rule = new Region();
        rule.setMinHeight(1);
        rule.setMaxHeight(1);
        rule.setStyle("-fx-background-color: #1C2840;");
        Label heading = new Label("SOUTENIR GENESIS");
        heading.setStyle("-fx-text-fill: #6F86A8; -fx-font-size: 10px; -fx-font-weight: bold;");
        Label text = new Label("Genesis est gratuit. Une étoile sur GitHub, un message ou un passage sur r/gemmie l'aide à grandir.");
        text.setWrapText(true);
        text.setMaxWidth(380);
        text.setStyle("-fx-text-fill: #7F8FAB; -fx-font-size: 11.5px; -fx-text-alignment: center;");
        text.setAlignment(Pos.CENTER);
        FlowPane links = new FlowPane(6, 6);
        links.setAlignment(Pos.CENTER);
        links.setPrefWrapLength(390);
        for (String[] link : SUPPORT_LINKS) {
            Button pill = new Button(link[0]);
            pill.getStyleClass().add("support-link");
            pill.setFocusTraversable(false);
            pill.setTooltip(new Tooltip(link[1].replaceFirst("^mailto:", "")));
            pill.setOnAction(event -> {
                if (opener != null) opener.accept(link[1]);
            });
            links.getChildren().add(pill);
        }
        support.getChildren().addAll(rule, heading, text, links);
        support.setAlignment(Pos.CENTER);
        support.setPadding(new javafx.geometry.Insets(8, 0, 0, 0));
        support.setVisible(false);
        support.setManaged(false);
    }

    /** Montrer le cadre de soutien sous l'attente en cours (le démarrage) ; `open` ouvre une adresse. */
    void withSupport(Consumer<String> open) {
        opener = open;
        support.setVisible(true);
        support.setManaged(true);
    }

    StackPane getNode() {
        return root;
    }

    boolean isShowing() {
        return showing;
    }

    /** Attendre que le moteur ait ouvert (ou fermé) un écran : `ready` dit quand la frame le confirme. */
    void show(String heading, String text, Predicate<UniverseFrame> ready, Runnable cancelAction) {
        this.ready = ready;
        this.onCancel = cancelAction;
        title.setText(heading);
        detail.setText(text);
        elapsed = 0;
        failed = false;
        showing = true;
        cancel.setText("Annuler");
        support.setVisible(false);       // seul le démarrage le demande (withSupport)
        support.setManaged(false);
        root.setVisible(true);
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
        fade = Math.max(0, Math.min(1, fade + (showing ? dt / 0.18 : -dt / 0.25)));
        root.setOpacity(fade);
        if (fade <= 0 && !showing) {
            root.setVisible(false);
            return;
        }
        elapsed += dt;
        angle += dt * (failed ? 0.4 : 2.4);
        if (!failed) {
            elapsedLabel.setText(String.format(I18n.locale(), "%.1f s", elapsed)
                    + (elapsed > 20 ? " · le moteur prépare encore les données" : ""));
        } else {
            elapsedLabel.setText("");
        }
        draw();
    }

    /** Un petit atome qui tourne : deux électrons sur leurs orbites autour d'un noyau qui pulse. */
    private void draw() {
        GraphicsContext g = spinner.getGraphicsContext2D();
        double w = spinner.getWidth();
        double c = w / 2;
        g.clearRect(0, 0, w, w);
        Color accent = failed ? Color.web("#F87171") : Color.web("#7DD3FC");
        g.setStroke(Color.web("#22304A"));
        g.setLineWidth(3);
        g.strokeOval(c - 32, c - 32, 64, 64);
        g.setStroke(accent);
        g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        g.strokeArc(c - 32, c - 32, 64, 64, Math.toDegrees(-angle), 90, ArcType.OPEN);
        g.setStroke(Color.web("#F2B84B"));
        g.setLineWidth(2);
        g.strokeArc(c - 22, c - 22, 44, 44, Math.toDegrees(angle * 1.6), 70, ArcType.OPEN);
        double pulse = 6 + 1.5 * Math.sin(angle * 2);
        g.setFill(new RadialGradient(0, 0, c, c, pulse * 2, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#FCA5A5")), new Stop(0.5, Color.web("#F87171", 0.5)),
                new Stop(1, Color.web("#F87171", 0))));
        g.fillOval(c - pulse * 2, c - pulse * 2, pulse * 4, pulse * 4);
    }
}
