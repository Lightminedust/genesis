package org.genesis;

import com.studio.genesis.grpc.SimEvent;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Le journal : tout ce qui est arrivé, du plus récent au plus ancien, avec
 * le calcul qui l'explique. Rien ne s'efface : on peut relire à son rythme.
 */
public class EventJournal {

    private static final int MAX_ENTRIES = 200;

    private final VBox entries = new VBox(0);
    private final ScrollPane scroller = new ScrollPane(entries);
    private String lastInfo = "";

    public EventJournal() {
        entries.setStyle("-fx-background-color: #0B1120;");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setStyle("-fx-background: #0B1120; -fx-background-color: #0B1120;");
        Label empty = new Label("Le journal des événements apparaîtra ici.");
        empty.setStyle("-fx-text-fill: #475569; -fx-font-size: 11px;");
        empty.setPadding(new Insets(10, 12, 10, 12));
        entries.getChildren().add(empty);
    }

    public Region getNode() {
        return scroller;
    }

    public void add(SimEvent event) {
        // Le tir d'un photon n'est pas un événement : son sort (absorbé, arraché, traverse) en est un.
        if (event.getKind().equals("photon_fired")) {
            return;
        }
        // Une même information répétée n'apprend rien : une seule ligne.
        if (event.getKind().equals("info") && event.getTitle().equals(lastInfo)) {
            return;
        }
        lastInfo = event.getKind().equals("info") ? event.getTitle() : "";
        if (entries.getChildren().size() == 1 && !(entries.getChildren().get(0) instanceof HBox)) {
            entries.getChildren().clear();
        }
        Color accent = SimCanvas.eventColor(event);
        Circle dot = new Circle(4, accent);

        Label title = label(event.getTitle(), "-fx-text-fill: #E2E8F0; -fx-font-size: 12px; -fx-font-weight: bold;");
        VBox text = new VBox(1, title);
        if (!event.getFormula().isEmpty()) {
            text.getChildren().add(label(event.getFormula(),
                    "-fx-text-fill: #FDE68A; -fx-font-family: Consolas, monospace; -fx-font-size: 11px;"));
        }
        if (!event.getDetail().isEmpty()) {
            text.getChildren().add(label(event.getDetail(), "-fx-text-fill: #94A3B8; -fx-font-size: 11px;"));
        }
        HBox.setHgrow(text, Priority.ALWAYS);

        Label time = new Label("t = " + Visuals.duration(event.getTimeS()));
        time.setStyle("-fx-text-fill: #475569; -fx-font-family: Consolas, monospace; -fx-font-size: 10px;");
        time.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(8, dot, text, time);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(7, 12, 7, 12));
        row.setStyle("-fx-border-color: transparent transparent #1E293B transparent; -fx-border-width: 0 0 1 0;");
        HBox.setMargin(dot, new Insets(5, 0, 0, 0));

        entries.getChildren().add(0, row);
        if (entries.getChildren().size() > MAX_ENTRIES) {
            entries.getChildren().remove(MAX_ENTRIES, entries.getChildren().size());
        }
        scroller.setVvalue(0);
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle(style);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }
}
