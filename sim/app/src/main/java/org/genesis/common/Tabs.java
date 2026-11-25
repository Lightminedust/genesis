package org.genesis.common;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Des pages dans une colonne, une seule montrée à la fois : la colonne reste courte, on choisit ce qu'on regarde. Le
 * titre des pages est une barre d'onglets soulignés (pour des mesures) ou une commande segmentée (pour un choix de ce
 * qu'on prépare). Chaque page défile seule ; la barre reste en place.
 */
public final class Tabs extends VBox {
    private final ToggleGroup group = new ToggleGroup();
    private final List<ToggleButton> buttons = new ArrayList<>();
    private final List<ScrollPane> pages = new ArrayList<>();
    private final StackPane stack = new StackPane();
    private final HBox bar;
    private IntConsumer onChange = i -> { };
    private int selected = -1;

    /**
     * `segmented` : la barre est une commande segmentée (sinon, des onglets soulignés). `names` et `contents` vont par
     * paires ; chaque contenu est posé dans sa propre page qui défile.
     */
    public Tabs(boolean segmented, String[] names, Node... contents) {
        for (int i = 0; i < names.length; i++) {
            ToggleButton b = new ToggleButton(names[i]);
            b.setToggleGroup(group);
            b.setFocusTraversable(false);
            final int index = i;
            b.setOnAction(e -> select(index));
            buttons.add(b);
            ScrollPane page = new ScrollPane(contents[i]);
            page.setFitToWidth(true);
            page.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            page.getStyleClass().add("tab-page");
            pages.add(page);
            stack.getChildren().add(page);
        }
        if (segmented) {
            bar = Plan.segmented(true, buttons.toArray(new ToggleButton[0]));
            bar.setPadding(new Insets(14, 16, 6, 16));
        } else {
            bar = new HBox(16);
            for (ToggleButton b : buttons) {
                b.getStyleClass().setAll("toggle-button", "plan-tab");
                bar.getChildren().add(b);
            }
            bar.setPadding(new Insets(0, 16, 0, 16));
            bar.getStyleClass().add("plan-tabs");
        }
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(stack, Priority.ALWAYS);
        getChildren().addAll(bar, stack);
        select(0);
    }

    /** Montre la page `index`. */
    public void select(int index) {
        if (index < 0 || index >= pages.size()) return;
        for (int i = 0; i < pages.size(); i++) Plan.show(pages.get(i), i == index);
        buttons.get(index).setSelected(true);
        if (index != selected) {
            selected = index;
            onChange.accept(index);
        }
    }

    public int selected() {
        return selected;
    }

    /** La page `index` (son défilement). */
    public ScrollPane page(int index) {
        return pages.get(index);
    }

    /** La barre des titres (pour le guide). */
    public HBox bar() {
        return bar;
    }

    /** Appelé quand on change de page. */
    public void setOnChange(IntConsumer action) {
        onChange = action == null ? i -> { } : action;
    }

    /** Le bouton de la page `index` (pour lui poser une bulle). */
    public ToggleButton button(int index) {
        return buttons.get(index);
    }
}
