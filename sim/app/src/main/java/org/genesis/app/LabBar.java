package org.genesis.app;

import org.genesis.common.Brand;
import org.genesis.common.Icons;
import org.genesis.common.Plan;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;

/**
 * La barre du haut. À gauche, un fil d'Ariane : « Genesis / Atome » — Genesis ramène à l'accueil, le laboratoire ouvert
 * est écrit avec sa pastille de couleur et son échelle (10⁻¹⁰ m) ; puis ses modes. À droite, les outils communs à tous
 * les laboratoires, en icônes : le guide, l'image de la scène, les panneaux, le plein écran, la méthode, les réglages.
 * L'état du moteur n'apparaît que s'il y a quelque chose à en dire (connexion, panne). On ne change pas de laboratoire
 * ici : depuis l'accueil (voir HomeScreen). Sur l'accueil, seule la marque reste.
 */
final class LabBar {
    /** Les laboratoires, dans l'ordre de l'accueil, avec leur couleur (la variante de chacun, voir genesis.css). */
    enum Lab {
        ATOM("Atome", "lab-atom", "#7CC4FF", "10⁻¹⁰ m"),
        FUSION("Fusion", "lab-fusion", "#FF9F66", "10⁻¹⁴ m"),
        MOLECULE("Molécules", "lab-molecule", "#4FD1A9", "10⁻⁹ m"),
        SUBATOMIC("Subatomique", "lab-subatomic", "#B39DFF", "10⁻¹⁵ m");

        final String title, style, accent, scale;

        Lab(String title, String style, String accent, String scale) {
            this.title = title;
            this.style = style;
            this.accent = accent;
            this.scale = scale;
        }
    }

    private final HBox root;
    private final Label crumb = new Label("/"), labName = new Label(), labScale = new Label();
    private final Circle dot = new Circle(3.5);
    private final HBox labBox, modes = new HBox(0);
    private final Label status = new Label();
    private final Button guide = Plan.icon("help", null, "Le guide de ce laboratoire : par où commencer, ce que montre chaque zone · H");
    private final Button capture = Plan.icon("camera", null, "Enregistrer une image de la scène · Ctrl+S");
    private final ToggleButton panels = Plan.iconToggle("panels", "Montrer ou cacher les panneaux, pour laisser toute la place à la scène · Tab");
    private final ToggleButton fullscreen = Plan.iconToggle("expand", "Plein écran · F11");
    private final Button method = Plan.icon("book", "Méthode", "Comment ce laboratoire calcule : le modèle, ses sources, ses limites");
    private Runnable methodAction;

    LabBar(Runnable home, Runnable onGuide, Runnable onCapture, Runnable onPanels, Runnable onFullscreen, Node... trailing) {
        // Le logotype GENESIS_ (assets/brand), à la hauteur d'une capitale de la barre.
        Node logotype = Brand.node(Brand.LOGOTYPE, 13);
        logotype.getStyleClass().add("plan-logotype");
        HBox identity = new HBox(logotype);
        identity.setAlignment(Pos.CENTER_LEFT);
        Button homeButton = new Button();
        homeButton.setGraphic(identity);
        homeButton.getStyleClass().setAll("button", "plan-home");
        homeButton.setFocusTraversable(false);
        homeButton.setTooltip(Plan.tooltip("L'accueil : les laboratoires, Genesis, les liens · Échap deux fois"));
        homeButton.setOnAction(event -> home.run());

        crumb.getStyleClass().add("plan-crumb");
        dot.getStyleClass().add("plan-lab-dot");
        labName.getStyleClass().add("plan-lab");
        labScale.getStyleClass().add("plan-scale");
        labBox = new HBox(7, dot, labName, labScale);
        labBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setMargin(labScale, new Insets(1, 0, 0, 2));
        modes.setAlignment(Pos.CENTER_LEFT);
        modes.setFillHeight(false);
        HBox.setMargin(modes, new Insets(0, 0, 0, 18));

        status.getStyleClass().add("plan-status");
        status.setTextOverrun(OverrunStyle.ELLIPSIS);
        status.setMaxWidth(320);
        Plan.show(status, false);

        guide.setOnAction(e -> onGuide.run());
        capture.setOnAction(e -> onCapture.run());
        panels.setSelected(true);
        panels.setOnAction(e -> onPanels.run());
        fullscreen.setOnAction(e -> onFullscreen.run());
        method.setOnAction(event -> {
            if (methodAction != null) methodAction.run();
        });

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox tools = new HBox(2, guide, capture, panels, fullscreen, rule(), method);
        tools.setAlignment(Pos.CENTER_RIGHT);
        HBox right = new HBox(2, status, tools);
        right.getChildren().addAll(trailing);
        right.setAlignment(Pos.CENTER_RIGHT);
        HBox.setMargin(status, new Insets(0, 10, 0, 0));
        root = new HBox(0, homeButton, crumb, labBox, modes, grow, right);
        HBox.setMargin(crumb, new Insets(0, 10, 0, 4));
        root.setAlignment(Pos.CENTER_LEFT);
        root.setFillHeight(false);
        root.setPadding(new Insets(0, 10, 0, 8));
        root.setMinHeight(46);
        root.setPrefHeight(46);
        root.setMaxHeight(46);
        root.getStyleClass().add("plan-bar");
        tools.getProperties().put("tools", true);
        this.tools = tools;
        showHome();
    }

    private final HBox tools;

    private static Region rule() {
        Region rule = new Region();
        rule.getStyleClass().add("plan-rule");
        rule.setMinHeight(18);
        rule.setMaxHeight(18);
        HBox.setMargin(rule, new Insets(0, 6, 0, 6));
        return rule;
    }

    Region getNode() {
        return root;
    }

    /** L'accueil : la marque et les réglages, rien d'un laboratoire. */
    void showHome() {
        root.getStyleClass().removeIf(style -> style.startsWith("lab-"));
        for (Node node : new Node[]{crumb, labBox, modes, tools}) Plan.show(node, false);
        modes.getChildren().clear();
        methodAction = null;
    }

    /** Un laboratoire : son nom dans sa couleur, ses modes (null : aucun) et sa méthode (null : aucune). */
    void showLab(Lab lab, Node labModes, Runnable labMethod) {
        root.getStyleClass().removeIf(style -> style.startsWith("lab-"));
        root.getStyleClass().add(lab.style);
        labName.setText(I18n.text(lab.title));
        labScale.setText(lab.scale);
        boolean hasModes = labModes != null;
        modes.getChildren().setAll(hasModes ? new Node[]{labModes} : new Node[0]);
        for (Node node : new Node[]{crumb, labBox, tools}) Plan.show(node, true);
        Plan.show(modes, hasModes);
        methodAction = labMethod;
        Plan.show(method, labMethod != null);
    }

    /** L'état du moteur, s'il y a quelque chose à en dire (null : rien, la barre se tait). */
    void status(String text, boolean bad) {
        boolean shown = text != null && !text.isEmpty();
        Plan.show(status, shown);
        if (!shown) return;
        status.setText(text);
        status.getStyleClass().remove("bad");
        if (bad) status.getStyleClass().add("bad");
        if (status.getTooltip() == null) status.setTooltip(Plan.tooltip(text));
        else status.getTooltip().setText(text);
    }

    /** Les panneaux sont-ils montrés (l'icône allumée) ? */
    void panelsShown(boolean shown) {
        panels.setSelected(shown);
    }

    void fullscreen(boolean on) {
        fullscreen.setSelected(on);
        fullscreen.setGraphic(Icons.path(on ? "contract" : "expand", 16));
    }
}
