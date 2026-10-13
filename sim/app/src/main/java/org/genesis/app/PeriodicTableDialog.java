package org.genesis.app;

import com.studio.genesis.grpc.Element;
import com.studio.genesis.grpc.PeriodicTable;

import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Sélecteur d'élément : la table périodique entière, telle qu'on la lit sur
 * une affiche, lanthanides et actinides sortis sur leurs deux lignes.
 *
 * Les positions viennent du moteur (`table_row` / `table_col`) : le client ne
 * connaît pas la chimie, il place ce qu'on lui donne.
 */
public class PeriodicTableDialog {

    private static final double MAX_CELL = 46;
    private static final double MIN_CELL = 26;
    private static final double GAP = 3;
    private static final int COLUMNS = 18;

    private final Stage stage = new Stage();
    private final Label detail = new Label("Survole un élément pour le détailler.");
    private final double cellSize;

    public PeriodicTableDialog(Window owner, PeriodicTable table, IntConsumer onPick) {
        this(owner, table, onPick, "Genesis - Table périodique");
    }

    public PeriodicTableDialog(Window owner, PeriodicTable table, IntConsumer onPick, String windowTitle) {
        // 18 colonnes de 46 px font 880 px de large : sur un écran étroit, la
        // fenêtre sortirait de l'écran. On rétrécit les cases plutôt.
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double usable = screen.getWidth() - 90;
        cellSize = Math.max(MIN_CELL,
                Math.min(MAX_CELL, usable / COLUMNS - GAP));

        GridPane grid = new GridPane();
        grid.setHgap(GAP);
        grid.setVgap(GAP);

        for (Element element : table.getElementsList()) {
            if (element.getTableRow() <= 0 || element.getTableCol() <= 0) {
                continue;
            }
            Region cell = buildCell(element, onPick);
            // La grille est 1-indexée côté moteur, 0-indexée côté JavaFX.
            grid.add(cell, element.getTableCol() - 1, element.getTableRow() - 1);
        }

        // Ligne 8 laissée vide : elle sépare le corps de la table des
        // lanthanides et actinides, comme sur une table imprimée.
        Region spacer = new Region();
        spacer.setMinHeight(14);
        grid.add(spacer, 0, 7);

        Label title = new Label("TABLE PÉRIODIQUE");
        title.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 15px; -fx-font-weight: bold;");

        Label hint = new Label("Clique un élément pour le construire (la table se referme ; Échap pour fermer). "
                + "Les éléments encadrés en pointillés n'ont aucun isotope stable.");
        hint.setStyle("-fx-text-fill: #475569; -fx-font-size: 11px;");

        detail.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 12px;");
        detail.setMinHeight(20);

        VBox root = new VBox(12, title, hint, grid, buildLegend(table), detail);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #070B15;");
        root.setAlignment(Pos.TOP_LEFT);

        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle(windowTitle);
        Scene scene = new Scene(root);
        scene.getStylesheets().add(App.stylesheet());
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                stage.hide();
            }
        });
        stage.setScene(scene);
        stage.setResizable(false);
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    public void close() {
        stage.close();
    }

    private Region buildCell(Element element, IntConsumer onPick) {
        Color accent = element.getColorHex().isEmpty()
                ? Color.web("#64748B")
                : Color.web(element.getColorHex());
        boolean radioactive = element.getStableIsotopesCount() == 0;

        Label symbol = new Label(element.getSymbol());
        symbol.setStyle("-fx-font-size: " + Math.round(cellSize * 0.33) + "px; -fx-font-weight: bold; -fx-text-fill: "
                + toHex(accent) + ";");

        Label number = new Label(String.valueOf(element.getZ()));
        number.setStyle("-fx-font-size: " + Math.max(7, Math.round(cellSize * 0.18)) + "px; -fx-text-fill: #475569;");

        VBox content = new VBox(-2, number, symbol);
        content.setAlignment(Pos.CENTER);

        // Un vrai bouton plutôt qu'un panneau cliquable : navigable au
        // clavier (Tab, Entrée), lisible par un lecteur d'écran, et
        // actionnable par l'automatisation d'interface.
        Button cell = new Button();
        cell.setGraphic(content);
        cell.setAccessibleText(element.getName());
        cell.setPrefSize(cellSize, cellSize);
        cell.setMinSize(cellSize, cellSize);
        cell.setMaxSize(cellSize, cellSize);

        String idle = cellStyle(accent, radioactive, false);
        String hover = cellStyle(accent, radioactive, true);
        cell.setStyle(idle);

        Runnable highlight = () -> {
            cell.setStyle(hover);
            detail.setText(describe(element));
            detail.setStyle("-fx-text-fill: " + toHex(accent) + "; -fx-font-size: 12px;");
        };
        cell.setOnMouseEntered(event -> highlight.run());
        cell.setOnMouseExited(event -> cell.setStyle(idle));
        // Le survol au clavier doit décrire l'élément autant que la souris.
        cell.focusedProperty().addListener((observable, before, focused) -> {
            if (focused) {
                highlight.run();
            } else {
                cell.setStyle(idle);
            }
        });
        cell.setOnAction(event -> {
            onPick.accept(element.getZ());
            stage.hide();       // l'élément est choisi : la table s'efface, l'atome apparaît
        });

        return cell;
    }

    private static String cellStyle(Color accent, boolean radioactive, boolean hovered) {
        String background = hovered ? toHex(accent.deriveColor(0, 1, 0.42, 1)) : "#111C33";
        String border = hovered ? toHex(accent) : toHex(accent) + "55";
        return "-fx-padding: 0; "
                + "-fx-background-color: " + background + "; -fx-background-radius: 5; "
                + "-fx-border-color: " + border + "; -fx-border-radius: 5; -fx-border-width: 1; "
                + (radioactive ? "-fx-border-style: dashed; " : "")
                + "-fx-cursor: hand;";
    }

    private static String describe(Element element) {
        StringBuilder text = new StringBuilder();
        text.append(element.getZ()).append("  ").append(element.getName())
            .append("  ·  ").append(element.getCategory());
        if (element.getStableIsotopesCount() == 0) {
            text.append("  ·  aucun isotope stable, radioactif quoi qu'il arrive");
        } else {
            text.append("  ·  isotopes stables A = ");
            for (int i = 0; i < element.getStableIsotopesCount(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(element.getStableIsotopes(i));
            }
        }
        return text.toString();
    }

    /** Légende des familles, dans l'ordre où elles apparaissent dans la table. */
    private Region buildLegend(PeriodicTable table) {
        Map<String, String> families = new LinkedHashMap<>();
        for (Element element : table.getElementsList()) {
            families.putIfAbsent(element.getCategory(), element.getColorHex());
        }

        FlowPane legend = new FlowPane(12, 6);
        for (Map.Entry<String, String> family : families.entrySet()) {
            Region swatch = new Region();
            swatch.setMinSize(10, 10);
            swatch.setMaxSize(10, 10);
            swatch.setStyle("-fx-background-color: " + family.getValue() + "; -fx-background-radius: 2;");

            Label name = new Label(family.getKey());
            name.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px;");

            HBox item = new HBox(5, swatch, name);
            item.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(item);
        }
        return legend;
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }
}
