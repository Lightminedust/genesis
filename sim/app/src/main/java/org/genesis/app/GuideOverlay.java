package org.genesis.app;

import org.genesis.common.Guide;
import org.genesis.common.Icons;
import org.genesis.common.Plan;

import javafx.animation.FadeTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Le guide d'un laboratoire, posé par-dessus l'écran : l'écran s'assombrit, chaque zone (les modes, la colonne de gauche,
 * la scène, les mesures, le temps) est cernée de la couleur du laboratoire et porte une petite fiche numérotée ; au
 * centre de la scène, une fiche dit ce qu'on fait ici, par où commencer, et les touches. Un clic, Échap ou « Compris »
 * le referme. C'est la seule place des explications de l'interface : le reste de l'écran ne garde que l'essentiel.
 */
final class GuideOverlay {
    private final Pane root = new Pane();
    private final Region shade = new Region();
    private Guide shown;
    private String style;
    private boolean laying;

    GuideOverlay() {
        shade.getStyleClass().add("guide-shade");
        // Tout est posé à la main : un Pane redonnerait à chaque enfant sa taille préférée (le voile, zéro).
        shade.setManaged(false);
        root.getChildren().add(shade);
        root.setVisible(false);
        root.setOnMouseClicked(e -> hide());
        root.widthProperty().addListener((o, a, b) -> relayout());
        root.heightProperty().addListener((o, a, b) -> relayout());
    }

    Pane getNode() {
        return root;
    }

    boolean isShowing() {
        return root.isVisible();
    }

    /** Montre le guide `guide` dans la couleur du laboratoire (sa classe de style, « lab-atom »…). */
    void show(Guide guide, String labStyle) {
        shown = guide;
        if (style != null) root.getStyleClass().remove(style);
        style = labStyle;
        if (style != null) root.getStyleClass().add(style);
        root.setVisible(true);
        root.setOpacity(0);
        relayout();
        FadeTransition in = new FadeTransition(Duration.millis(180), root);
        in.setToValue(1);
        in.play();
    }

    void hide() {
        if (!root.isVisible()) return;
        FadeTransition out = new FadeTransition(Duration.millis(140), root);
        out.setToValue(0);
        out.setOnFinished(e -> root.setVisible(false));
        out.play();
    }

    /** Pose les zones et les fiches d'après la place que les nœuds occupent maintenant. */
    private void relayout() {
        if (shown == null || !root.isVisible() || laying) return;
        // Les zones doivent avoir leur place définitive (une barre qui vient de recevoir les modes d'un laboratoire
        // n'a pas encore été mise en page) : une passe de mise en page d'abord.
        laying = true;
        try {
            if (root.getScene() != null) {
                root.getScene().getRoot().applyCss();
                root.getScene().getRoot().layout();
            }
        } finally {
            laying = false;
        }
        double w = root.getWidth(), h = root.getHeight();
        root.getChildren().setAll(shade);
        shade.resizeRelocate(0, 0, w, h);
        List<Bounds> taken = new ArrayList<>();
        // D'abord la place de chaque zone ; la plus grande est la scène (sa fiche se pose en bas, la fiche principale au
        // milieu).
        List<Guide.Spot> spots = new ArrayList<>();
        List<Bounds> zones = new ArrayList<>();
        Bounds stage = null;
        for (Guide.Spot spot : shown.spots()) {
            Node node = spot.node();
            if (node == null || node.getScene() == null || !node.isVisible()) continue;
            Bounds b = root.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            if (b == null || b.getWidth() < 8 || b.getHeight() < 8) continue;
            spots.add(spot);
            zones.add(b);
            if (stage == null || b.getWidth() * b.getHeight() > stage.getWidth() * stage.getHeight()) stage = b;
        }
        // Les fiches des grandes zones d'abord (posées dedans) ; celles des barres ensuite, qui se rangent à côté.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < spots.size(); i++) if (zones.get(i).getHeight() >= 120) order.add(i);
        for (int i = 0; i < spots.size(); i++) if (zones.get(i).getHeight() < 120) order.add(i);
        for (int i : order) {
            Guide.Spot spot = spots.get(i);
            Bounds b = zones.get(i);
            Rectangle ring = new Rectangle(b.getMinX() + 3, b.getMinY() + 3, b.getWidth() - 6, b.getHeight() - 6);
            ring.getStyleClass().add("guide-ring");
            ring.setArcWidth(16);
            ring.setArcHeight(16);
            ring.setMouseTransparent(true);
            ring.setManaged(false);
            root.getChildren().add(ring);
            VBox card = card(i + 1, spot.title(), spot.text());
            double cardWidth = Math.min(268, Math.max(180, b.getWidth() - 28));
            card.setPrefWidth(cardWidth);
            card.setMaxWidth(cardWidth);
            card.setManaged(false);
            root.getChildren().add(card);
            card.applyCss();
            card.layout();
            double cardHeight = card.prefHeight(cardWidth);
            double x = b.getMinX() + 14, y;
            if (b.getHeight() < cardHeight + 40) {
                // Une zone basse (une barre) : la fiche se pose à côté, au-dessus si la barre est en bas.
                y = b.getMaxY() > h - 160 ? b.getMinY() - cardHeight - 12 : b.getMaxY() + 12;
            } else if (b == stage) {
                y = b.getMaxY() - cardHeight - 14;           // la scène : sa fiche en bas, le milieu reste à la fiche principale
            } else {
                y = b.getMinY() + 14;
            }
            x = Math.max(8, Math.min(w - cardWidth - 8, x));
            y = Math.max(8, Math.min(h - cardHeight - 8, y));
            // Pas sur une fiche déjà posée : à sa droite s'il y a la place, sinon dessous.
            for (Bounds other : taken) {
                if (other.intersects(x, y, cardWidth, cardHeight)) {
                    if (other.getMaxX() + 10 + cardWidth < w - 8) x = other.getMaxX() + 10;
                    else y = other.getMaxY() + 10;
                }
            }
            card.resizeRelocate(x, y, cardWidth, cardHeight);
            taken.add(new javafx.geometry.BoundingBox(x, y, cardWidth, cardHeight));
        }
        // La fiche principale : au centre de la plus grande zone (la scène), sans couvrir les autres fiches si possible.
        double leadWidth = Math.min(480, w - 40);
        VBox lead = lead(leadWidth);
        lead.setPrefWidth(leadWidth);
        lead.setManaged(false);
        root.getChildren().add(lead);
        lead.applyCss();
        lead.layout();
        double leadHeight = lead.prefHeight(leadWidth);
        double cx = stage == null ? w / 2 : stage.getMinX() + stage.getWidth() / 2;
        double cy = stage == null ? h / 2 : stage.getMinY() + stage.getHeight() / 2;
        double x = Math.max(12, Math.min(w - leadWidth - 12, cx - leadWidth / 2));
        double y = Math.max(12, Math.min(h - leadHeight - 12, cy - leadHeight / 2 + 20));
        for (Bounds b : taken) {
            if (!b.intersects(x, y, leadWidth, leadHeight)) continue;
            if (b.getMinY() < cy && b.getMaxY() + 12 + leadHeight < h - 12) y = Math.max(y, b.getMaxY() + 14);
            else if (b.getMinY() >= cy) y = Math.max(12, Math.min(y, b.getMinY() - leadHeight - 14));
        }
        lead.resizeRelocate(x, y, leadWidth, leadHeight);
    }

    private static VBox card(int number, String title, String text) {
        Label badge = new Label(String.valueOf(number));
        badge.getStyleClass().add("guide-number");
        Label name = new Label(I18n.text(title));
        name.getStyleClass().add("guide-title");
        HBox head = new HBox(8, badge, name);
        head.setAlignment(Pos.CENTER_LEFT);
        Label body = new Label(I18n.text(text));
        body.getStyleClass().add("guide-text");
        body.setWrapText(true);
        VBox card = new VBox(6, head, body);
        card.getStyleClass().add("guide-card");
        card.setMouseTransparent(true);
        return card;
    }

    private VBox lead(double width) {
        Label lab = new Label(I18n.text(shown.lab()));
        lab.getStyleClass().add("guide-headline");
        Label lead = new Label(I18n.text(shown.lead()));
        lead.getStyleClass().add("guide-text");
        lead.setWrapText(true);
        VBox box = new VBox(6, lab, lead);
        if (!shown.steps().isEmpty()) {
            Label title = new Label(I18n.text("Par où commencer"));
            title.getStyleClass().add("guide-title");
            VBox.setMargin(title, new Insets(10, 0, 0, 0));
            box.getChildren().add(title);
            for (String step : shown.steps()) {
                Node arrow = Icons.path("arrow-right", 12);
                Label line = new Label(I18n.text(step));
                line.getStyleClass().add("guide-text");
                line.setWrapText(true);
                HBox row = new HBox(8, arrow, line);
                row.setAlignment(Pos.TOP_LEFT);
                HBox.setMargin(arrow, new Insets(4, 0, 0, 0));
                box.getChildren().add(row);
            }
        }
        // Les touches du laboratoire, puis celles de partout.
        List<Guide.Key> all = new ArrayList<>(shown.keys());
        all.add(new Guide.Key("Tab", "cacher les panneaux"));
        all.add(new Guide.Key("Ctrl+S", "image de la scène"));
        all.add(new Guide.Key("F11", "plein écran"));
        all.add(new Guide.Key("Échap", "accueil"));
        {
            Label title = new Label(I18n.text("Touches"));
            title.getStyleClass().add("guide-title");
            VBox.setMargin(title, new Insets(10, 0, 2, 0));
            GridPane keys = new GridPane();
            keys.setHgap(8);
            keys.setVgap(6);
            for (int k = 0; k < all.size(); k++) {
                Guide.Key key = all.get(k);
                Label cap = new Label(I18n.text(key.key()));
                cap.getStyleClass().add("guide-key");
                cap.setMinWidth(Region.USE_PREF_SIZE);
                Label what = new Label(I18n.text(key.action()));
                what.getStyleClass().add("guide-text");
                what.setWrapText(true);
                what.setMaxWidth((width - 44) / 2 - 56);
                int column = (k % 2) * 2, row = k / 2;
                keys.add(cap, column, row);
                keys.add(what, column + 1, row);
                GridPane.setMargin(cap, new Insets(0, 0, 0, column > 0 ? 14 : 0));
            }
            box.getChildren().addAll(title, keys);
        }
        Button close = Plan.primary(I18n.text("Compris"));
        close.setMaxWidth(Region.USE_PREF_SIZE);
        close.setOnAction(e -> hide());
        Label again = new Label(I18n.text("H rouvre ce guide"));
        again.getStyleClass().add("plan-quiet-note");
        HBox foot = new HBox(14, close, again);
        foot.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(foot, new Insets(12, 0, 0, 0));
        box.getChildren().add(foot);
        box.getStyleClass().addAll("guide-card", "lead");
        box.setOnMouseClicked(e -> e.consume());
        return box;
    }
}
