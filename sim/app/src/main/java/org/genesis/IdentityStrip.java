package org.genesis;

import com.studio.genesis.grpc.AtomState;
import com.studio.genesis.grpc.Element;
import com.studio.genesis.grpc.Stability;
import com.studio.genesis.grpc.UniverseFrame;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Bandeau d'identité : ce qu'est l'atome, en un coup d'œil. Les explications,
 * elles, vivent sur la carte, là où les règles s'appliquent.
 */
public class IdentityStrip {

    private final Label symbol = new Label("?");
    private final Label name = new Label("En attente du moteur");
    private final Label detail = new Label("");
    private final Label badge = new Label("");
    private final Label counters = new Label("");
    private final HBox root;

    public IdentityStrip() {
        symbol.setMinWidth(58);
        symbol.setStyle("-fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: #64748B;");
        name.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #E2E8F0;");
        detail.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748B;");
        counters.setStyle("-fx-font-family: Consolas, monospace; -fx-font-size: 11px; -fx-text-fill: #94A3B8;");

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox nameRow = new HBox(8, name, grow, badge);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        VBox text = new VBox(2, nameRow, detail, counters);
        HBox.setHgrow(text, Priority.ALWAYS);

        root = new HBox(10, symbol, text);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(12, 14, 12, 14));
        root.setStyle("-fx-background-color: #0B1120; "
                + "-fx-border-color: transparent transparent #1E293B transparent; -fx-border-width: 0 0 1 0;");
    }

    public Region getNode() {
        return root;
    }

    public void update(UniverseFrame frame) {
        Element element = frame.getElement();
        AtomState atom = frame.getAtom();
        String color = element.getColorHex().isEmpty() ? "#64748B" : element.getColorHex();

        symbol.setText(element.getSymbol().isEmpty() ? "?" : element.getSymbol());
        symbol.setStyle("-fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        name.setText(element.getName().isEmpty() ? "Pas encore un élément" : atom.getIsotopeLabel());
        detail.setText(element.getCategory().isEmpty() ? "" : element.getCategory() + "   ·   "
                + (atom.getElectrons() > 0 ? element.getConfiguration() : "aucun électron"));
        counters.setText(String.format("Z %d   N %d   E %d   A %d   q %+d",
                atom.getProtons(), atom.getNeutrons(), atom.getElectrons(),
                atom.getMassNumber(), atom.getNetCharge()));

        String text;
        String style;
        if (atom.getStability() == Stability.STABLE) {
            text = "STABLE";
            style = "-fx-background-color: #064E3B; -fx-text-fill: #6EE7B7;";
        } else if (atom.getStability() == Stability.UNSTABLE) {
            text = "RADIOACTIF";
            style = "-fx-background-color: #4C0519; -fx-text-fill: #FDA4AF;";
        } else {
            text = "—";
            style = "-fx-background-color: #1E293B; -fx-text-fill: #94A3B8;";
        }
        badge.setText(text);
        badge.setStyle(style + " -fx-font-size: 9px; -fx-font-weight: bold; "
                + "-fx-padding: 3 8 3 8; -fx-background-radius: 10;");
    }
}
