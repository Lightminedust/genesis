package org.genesis;

import com.studio.genesis.grpc.UniverseFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Le monde subatomique : trois ateliers sous un même style documentaire.
 * <ul>
 * <li><b>Collision</b> — un électron frappe un nucléon ; le moteur calcule les cas (élastique, Δ, résonances, inélastique profond),
 * tire un événement et le montre, chaque interaction accompagnée de sa loi et de ses nombres ;</li>
 * <li><b>Atelier de hadrons</b> — le tableau du Modèle Standard, des quarks à assembler, un verdict calculé ;</li>
 * <li><b>Mer de partons</b> — le nucléon à une résolution Q² donnée (DGLAP).</li>
 * </ul>
 */
final class QuantumLab {
    interface Actions {
        void back();
        void collide(int target, double energyMev, double angleDeg, int projectile);
        void resample();
        void test(int spinIndex, double[] triples);
        void decay();
        void clear();
        void partons(boolean open);
        void force(int channel);
    }

    private final BorderPane root = new BorderPane();
    private final StackPane content = new StackPane();
    private final SmTable table = new SmTable();
    private final CollisionScene collision;
    private final HadronBench bench;
    private final Actions actions;
    private final ToggleButton collisionTab = tab("collision"), benchTab = tab("atelier de hadrons"), partonTab = tab("mer de partons");
    private final ToggleButton proton = segment("proton", "left"), neutron = segment("neutron", "right");
    private final Slider energy = new Slider(Math.log10(300), Math.log10(10000), Math.log10(2000)), angle = new Slider(5, 170, 40);
    private final Label energyLabel = new Label(), angleLabel = new Label(), clock = new Label();
    /** Les projectiles, dans l'ordre des codes du moteur (0 e⁻ … 8 π⁻). */
    static final String[] PROJECTILES = {"électron e⁻", "positron e⁺", "muon μ⁻", "antimuon μ⁺", "neutrino ν_μ", "antineutrino ν̄_μ",
            "proton p", "pion π⁺", "pion π⁻"};
    static final String[] FIRE = {"lancer l'électron", "lancer le positron", "lancer le muon", "lancer l'antimuon", "lancer le neutrino",
            "lancer l'antineutrino", "lancer le proton", "lancer le pion", "lancer le pion"};
    /** Les symboles et la fiche de chaque projectile (même ordre). */
    static final String[] SYMBOLS = {"e⁻", "e⁺", "μ⁻", "μ⁺", "ν_μ", "ν̄_μ", "p", "π⁺", "π⁻"};
    static final String[] FACTS = {
            "électron · lepton\nm 0,511 MeV · charge −1 · spin ½\nun photon échangé",
            "positron · antimatière\nm 0,511 MeV · charge +1 · spin ½\nun photon échangé",
            "muon · lepton lourd\nm 105,7 MeV · charge −1 · spin ½\nrayonne 40 000 fois moins que l'électron",
            "antimuon · antimatière\nm 105,7 MeV · charge +1 · spin ½\nun photon échangé",
            "neutrino muonique · invisible\nm < 0,8 eV · charge 0 · hélicité gauche\nun W⁺ : il change la saveur d'un quark",
            "antineutrino muonique\nm < 0,8 eV · charge 0 · hélicité droite\nun W⁻ : il change la saveur d'un quark",
            "proton · hadron (uud)\nm 938,3 MeV · charge +1 · spin ½\nforce forte : diffraction, cordes",
            "pion π⁺ · méson (u d̄)\nm 139,6 MeV · charge +1 · spin 0\nforce forte : il peut former un Δ",
            "pion π⁻ · méson (d ū)\nm 139,6 MeV · charge −1 · spin 0\nforce forte : il peut former un Δ"};
    private final ToggleButton[] beamButtons = new ToggleButton[9];
    private final Label beamCard = new Label(), targetCard = new Label();
    private final VBox casesBox = new VBox(6);
    private Button fireButton;
    /** L'angle tiré par la physique (selon dσ/dΩ) plutôt que choisi au curseur. */
    private final ToggleButton physicsAngle = new ToggleButton("tiré par la physique");
    private final List<Button> presets = new ArrayList<>();
    private final Slider scrub = new Slider(0, 1, 0);
    private final ToggleButton play = new ToggleButton("Ⅱ");
    private final BorderPane collisionPane = new BorderPane(), benchPane = new BorderPane();
    private Node partonNode;
    private boolean updating, partonsActive;

    QuantumLab(Actions actions) {
        this.actions = actions;
        this.collision = new CollisionScene(actions::force);
        root.getStyleClass().add("doc-lab");
        bench = new HadronBench(table, new HadronBench.Actions() {
            @Override public void test(int spinIndex, double[] triples) { actions.test(spinIndex, triples); }
            @Override public void decay() { actions.decay(); }
            @Override public void clear() { actions.clear(); }
        });

        Label title = new Label(DocStyle.caps("le monde subatomique"));
        title.getStyleClass().add("doc-title");
        ToggleGroup tabs = new ToggleGroup();
        for (ToggleButton t : new ToggleButton[]{collisionTab, benchTab, partonTab}) t.setToggleGroup(tabs);
        collisionTab.setSelected(true);
        collisionTab.setOnAction(e -> show(0));
        benchTab.setOnAction(e -> show(1));
        partonTab.setOnAction(e -> show(2));
        Button back = button("← atome", false);
        back.setOnAction(e -> actions.back());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(26, title, new HBox(4, collisionTab, benchTab, partonTab), gap, back);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(12, 20, 10, 20));
        head.getStyleClass().add("doc-head");
        root.setTop(head);

        // ---- la collision : comme la chambre de collision (fusion) — une colonne de réglages, la scène, la barre de lecture
        root.getStyleClass().add("fusion-lab");
        ToggleGroup targets = new ToggleGroup();
        proton.setToggleGroup(targets);
        neutron.setToggleGroup(targets);
        proton.getStyleClass().setAll("toggle-button", "segment", "left");
        neutron.getStyleClass().setAll("toggle-button", "segment", "right");
        for (ToggleButton t : new ToggleButton[]{proton, neutron}) {
            t.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(t, Priority.ALWAYS);
        }
        proton.setSelected(true);
        proton.setOnAction(e -> { proton.setSelected(true); targetChanged(); });
        neutron.setOnAction(e -> { neutron.setSelected(true); targetChanged(); });
        energy.valueProperty().addListener((o, a, b) -> energyLabel.setText(energyText()));
        angle.valueProperty().addListener((o, a, b) -> angleLabel.setText(angleText()));
        energyLabel.setText(energyText());
        for (Label l : new Label[]{energyLabel, angleLabel}) l.setStyle("-fx-text-fill:#FCD34D;-fx-font-family:Consolas;-fx-font-size:13px;-fx-font-weight:bold;");
        clock.setStyle("-fx-text-fill:#B9C6D8;-fx-font-size:11px;");
        energy.setMaxWidth(Double.MAX_VALUE);
        angle.setMaxWidth(Double.MAX_VALUE);
        // 01 le projectile : neuf boutons, et la fiche de celui choisi
        ToggleGroup beams = new ToggleGroup();
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(5);
        grid.setVgap(5);
        for (int i = 0; i < 9; i++) {
            ToggleButton t = new ToggleButton(SYMBOLS[i]);
            t.getStyleClass().add("glass-toggle");
            t.setToggleGroup(beams);
            t.setFocusTraversable(false);
            t.setMaxWidth(Double.MAX_VALUE);
            t.setPrefWidth(84);
            t.setTooltip(new Tooltip(FACTS[i].replace("\n", " · ")));
            t.setOnAction(e -> { t.setSelected(true); projectileChanged(); });
            javafx.scene.layout.GridPane.setHgrow(t, Priority.ALWAYS);
            grid.add(t, i % 3, i / 3);
            beamButtons[i] = t;
        }
        beamButtons[0].setSelected(true);
        beamCard.setWrapText(true);
        beamCard.setMaxWidth(Double.MAX_VALUE);
        targetCard.setWrapText(true);
        targetCard.setMaxWidth(Double.MAX_VALUE);
        Button fire = new Button("Lancer l'électron  →");
        fire.getStyleClass().add("glass-primary");
        fire.setFocusTraversable(false);
        fire.setMaxWidth(Double.MAX_VALUE);
        fire.setOnAction(e -> fire());
        fireButton = fire;
        Button again = new Button("Autre événement");
        again.getStyleClass().add("glass-button");
        again.setFocusTraversable(false);
        again.setMaxWidth(Double.MAX_VALUE);
        again.setTooltip(new Tooltip("Un nouveau tirage dans le même spectre : le cas peut changer, avec sa probabilité"));
        again.setOnAction(e -> {
            collision.pending("Le moteur tire un autre événement dans le même spectre…");
            actions.resample();
        });
        physicsAngle.getStyleClass().setAll("toggle-button", "glass-toggle");
        physicsAngle.setFocusTraversable(false);
        physicsAngle.setMaxWidth(Double.MAX_VALUE);
        physicsAngle.setSelected(true);
        physicsAngle.setTooltip(new Tooltip("La physique choisit l'angle selon dσ/dΩ (la plupart des électrons dévient à peine) ; "
                + "décoché, c'est le curseur qui le fixe"));
        physicsAngle.setOnAction(e -> {
            angle.setDisable(hadronBeam() || physicsAngle.isSelected());
            angleLabel.setText(angleText());
        });
        angle.setDisable(true);
        angleLabel.setText(angleText());
        FlowPane presetRow = new FlowPane(6, 6, preset("élastique", 1000, 30), preset("résonances", 1500, 60), preset("profond", 6000, 40));
        collision.setOnChannels(this::refreshCases);
        refreshCases();
        VBox sections = new VBox(10,
                section("01", "LE PROJECTILE", grid, beamCard),
                section("02", "LA CIBLE", new HBox(0, proton, neutron), targetCard),
                section("03", "LE TIR", row("Énergie du faisceau", energyLabel), energy, row("Angle de diffusion", angleLabel), angle,
                        physicsAngle, presetRow),
                section("04", "LES CAS POSSIBLES", casesBox));
        sections.setPadding(new Insets(2, 12, 12, 12));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(sections);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background:transparent;-fx-background-color:transparent;-fx-border-color:transparent;");
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(3.5, javafx.scene.paint.Color.web("#36CDF2"));
        dot.setEffect(new javafx.scene.effect.DropShadow(8, javafx.scene.paint.Color.web("#36CDF2")));
        Label brand = text(FusionDashboard.spaced("GENESIS"), 10, "#DCE8F7"), room = text(FusionDashboard.spaced("MONDE SUBATOMIQUE"), 8.5, "#7F98B8");
        brand.setStyle(brand.getStyle() + "-fx-font-weight:bold;");
        HBox named = new HBox(9, dot, new VBox(1, brand, room));
        named.setAlignment(Pos.CENTER_LEFT);
        named.setPadding(new Insets(12, 10, 10, 14));
        VBox dock = new VBox(7, fire, again);
        dock.setPadding(new Insets(10, 12, 12, 12));
        dock.setStyle("-fx-border-color:rgba(150,200,255,0.12) transparent transparent transparent;");
        VBox side = new VBox(named, scroll, dock);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        side.getStyleClass().add("glass-side");
        side.setPrefWidth(300);
        side.setMinWidth(300);
        side.setMaxWidth(300);
        collisionPane.setLeft(side);
        collisionPane.setCenter(collision.getNode());
        // la barre de lecture
        play.setSelected(true);
        play.getStyleClass().setAll("toggle-button", "glass-toggle");
        play.setFocusTraversable(false);
        play.setOnAction(e -> collision.setPlaying(play.isSelected()));
        scrub.valueProperty().addListener((o, a, b) -> {
            if (!updating) {
                collision.seekStory(b.doubleValue() * collision.total());
                collision.setPlaying(false);
                play.setSelected(false);
            }
        });
        Button previous = new Button("⏮ étape"), next = new Button("étape ⏭");
        for (Button bt : new Button[]{previous, next}) {
            bt.getStyleClass().add("glass-button");
            bt.setFocusTraversable(false);
        }
        previous.setTooltip(new Tooltip("Revenir au début de l'étape (ou à la précédente)"));
        next.setTooltip(new Tooltip("Passer à l'étape suivante"));
        previous.setOnAction(e -> collision.jump(-1));
        next.setOnAction(e -> collision.jump(1));
        ToggleGroup speeds = new ToggleGroup();
        ToggleButton slow = new ToggleButton("lent"), normal = new ToggleButton("normal"), fast = new ToggleButton("rapide");
        slow.getStyleClass().addAll("segment", "left");
        normal.getStyleClass().add("segment");
        fast.getStyleClass().addAll("segment", "right");
        double[] rates = {.35, 1, 3};
        ToggleButton[] speedButtons = {slow, normal, fast};
        for (int i = 0; i < 3; i++) {
            final double r = rates[i];
            speedButtons[i].setToggleGroup(speeds);
            speedButtons[i].setFocusTraversable(false);
            speedButtons[i].setOnAction(e -> collision.setSpeed(r));
        }
        normal.setSelected(true);
        HBox playback = new HBox(8, previous, play, next, scrub, clock, new HBox(slow, normal, fast));
        HBox.setHgrow(scrub, Priority.ALWAYS);
        playback.setAlignment(Pos.CENTER_LEFT);
        playback.setPadding(new Insets(7, 14, 7, 14));
        playback.setStyle("-fx-background-color:linear-gradient(to bottom, rgba(10,18,30,0.8), rgba(5,9,16,0.93));"
                + "-fx-border-color:rgba(150,200,255,0.14) transparent transparent transparent;");
        collisionPane.setBottom(playback);
        projectileChanged();
        targetChanged();

        // ---- l'atelier de hadrons
        FlowPane presets = new FlowPane(6, 6);
        String[][] famous = {{"proton", "uud"}, {"neutron", "udd"}, {"Δ⁺⁺", "uuu"}, {"Λ", "uds"}, {"Ω⁻", "sss"}, {"π⁺", "uD"},
                {"K⁺", "uS"}, {"D⁰", "cU"}, {"B⁺", "uB"}, {"J/ψ", "cC"}, {"Λb", "udb"}, {"top", "tT"}, {"pentaquark", "uudcC"}};
        for (String[] f : famous) {
            Button b = button(f[0], false);
            b.setOnAction(e -> { bench.load(f[1]); bench.test(); });
            presets.getChildren().add(b);
        }
        Button forbidden = button("u u u rouges", false);
        forbidden.setTooltip(new Tooltip("Trois quarks up de la même couleur : la règle de couleur et le principe de Pauli le refusent"));
        forbidden.setOnAction(e -> {
            bench.load("uuu");
            for (int i = 0; i < 3; i++) bench.slots().set(i, new HadronBench.Slot("u", false, 0, i));
            bench.test();
        });
        presets.getChildren().add(forbidden);
        Button test = button("tester la stabilité", true);
        test.setOnAction(e -> bench.test());
        Button neutral = button("couleurs neutres", false);
        neutral.setOnAction(e -> bench.neutralColours());
        Button anti = button("antimatière", false);
        anti.setTooltip(new Tooltip("Remplace chaque quark par son antiquark (et inversement)"));
        anti.setOnAction(e -> bench.conjugate());
        Button decay = button("autre désintégration", false);
        decay.setOnAction(e -> actions.decay());
        Button clear = button("vider", false);
        clear.setOnAction(e -> bench.clear());
        // le moule : baryon (trois emplacements), méson (quark + antiquark), libre (états exotiques)
        ToggleGroup moulds = new ToggleGroup();
        ToggleButton[] mould = new ToggleButton[3];
        String[] mouldNames = {"baryon · 3 quarks", "méson · quark + antiquark", "libre · exotiques"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            mould[i] = new ToggleButton(mouldNames[i]);
            mould[i].setToggleGroup(moulds);
            mould[i].getStyleClass().setAll("toggle-button", "segment", i == 0 ? "left" : i == 2 ? "right" : "middle");
            mould[i].setFocusTraversable(false);
            mould[i].setOnAction(e -> { mould[m].setSelected(true); bench.setMode(m); });
        }
        mould[0].setSelected(true);
        bench.setOnMode(m -> mould[m].setSelected(true));
        Label mouldLabel = new Label("moule");
        mouldLabel.setStyle("-fx-text-fill:#9FB0C8;-fx-font-size:11px;");
        HBox mouldBox = new HBox(0, mould);
        HBox mouldRow = new HBox(8, mouldLabel, mouldBox);
        mouldRow.setAlignment(Pos.CENTER_LEFT);
        FlowPane benchControls = new FlowPane(10, 8, mouldRow, test, neutral, anti, decay, clear);
        VBox benchTop = new VBox(8, benchControls, presets);
        benchTop.setPadding(new Insets(10, 20, 10, 20));
        benchTop.getStyleClass().add("doc-bar");
        benchPane.setTop(benchTop);
        benchPane.setCenter(bench.getNode());

        content.getChildren().addAll(collisionPane, benchPane);
        benchPane.setVisible(false);
        root.setCenter(content);
    }

    BorderPane getNode() { return root; }

    /** La mer de partons (une vue à part) se range dans le même cadre. */
    void attachPartons(Node node) {
        partonNode = node;
        content.getChildren().add(node);
        node.setVisible(false);
    }

    private void show(int which) {
        collisionPane.setVisible(which == 0);
        benchPane.setVisible(which == 1);
        (which == 0 ? collisionTab : which == 1 ? benchTab : partonTab).setSelected(true);
        if (which == 2 && !partonsActive) actions.partons(true);
        if (which != 2 && partonsActive) actions.partons(false);
        if (partonNode != null) partonNode.setVisible(which == 2 && partonsActive);
    }

    void togglePlayback() {
        play.setSelected(!play.isSelected());
        collision.setPlaying(play.isSelected());
    }

    private void fire() {
        collision.pending("Le moteur calcule les cas possibles et tire un événement…");
        double chosen = physicsAngle.isSelected() && !hadronBeam() ? -1 : angle.getValue();
        actions.collide(neutron.isSelected() ? 1 : 0, Math.pow(10, energy.getValue()), chosen, projectileIndex());
    }

    int projectileIndex() {
        for (int i = 0; i < beamButtons.length; i++) if (beamButtons[i] != null && beamButtons[i].isSelected()) return i;
        return 0;
    }

    /** Une section de la colonne, comme dans la chambre de collision : son numéro, son titre en capitales espacées, son contenu. */
    private static VBox section(String index, String title, Node... nodes) {
        Label number = text(index, 9.5, "#36CDF2"), name = text(FusionDashboard.spaced(title), 9.5, "#8FB3D9");
        number.setStyle(number.getStyle() + "-fx-font-weight:bold;");
        name.setStyle(name.getStyle() + "-fx-font-weight:bold;");
        HBox head = new HBox(9, number, name);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(0, 0, 2, 0));
        VBox box = new VBox(7);
        box.getChildren().add(head);
        box.getChildren().addAll(nodes);
        box.getStyleClass().add("glass-section");
        return box;
    }

    private static Label text(String value, double size, String color) {
        Label l = new Label(value);
        l.setStyle("-fx-text-fill:" + color + ";-fx-font-size:" + size + "px;");
        return l;
    }

    /** Un réglage : son nom à gauche, sa valeur à droite. */
    private static HBox row(String title, Label value) {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(8, text(title, 11, "#8EA6C0"), gap, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Une fiche plate : un trait de couleur à gauche, un fond uni, un filet fin (les cartes de noyaux de la chambre). */
    private static void card(Label label, javafx.scene.paint.Color color) {
        String c = String.format("#%02X%02X%02X", (int) (color.getRed() * 255), (int) (color.getGreen() * 255), (int) (color.getBlue() * 255));
        label.setStyle("-fx-background-color:" + c + ", #0E1420;-fx-background-insets:0, 0 0 0 3;-fx-background-radius:8, 0 8 8 0;"
                + "-fx-border-color:rgba(255,255,255,0.09);-fx-border-radius:8;-fx-padding:9 12 9 13;-fx-text-fill:#C7D2FE;-fx-font-size:11px;");
    }

    private void targetChanged() {
        boolean n = neutron.isSelected();
        targetCard.setText(n ? "neutron · udd\nm 939,6 MeV · charge 0 · spin ½\naucun champ électrique autour" :
                "proton · uud\nm 938,3 MeV · charge +1 · spin ½\nrayon de charge 0,84 fm");
        card(targetCard, CollisionScene.hadronColour(n ? "neutron" : "proton"));
    }

    /** Les cas possibles du dernier tir : une ligne par cas, sa probabilité en barre ; un clic tire un événement de ce cas. */
    private void refreshCases() {
        casesBox.getChildren().clear();
        List<CollisionScene.ChannelInfo> list = collision.channels();
        if (list.isEmpty()) {
            Label none = text("Lance un projectile : le moteur calcule ici la probabilité de chaque cas.", 11, "#8EA6C0");
            none.setWrapText(true);
            casesBox.getChildren().add(none);
            return;
        }
        for (CollisionScene.ChannelInfo ch : list) {
            Label name = text((ch.chosen() ? "▸ " : "") + ch.name(), 11.5, ch.chosen() ? "#EEF2FF" : "#C7D2FE");
            Label pct = text(String.format(Locale.FRANCE, "%.1f %%", 100 * ch.probability()), 11.5, "#FCD34D");
            pct.setStyle(pct.getStyle() + "-fx-font-family:Consolas;-fx-font-weight:bold;");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            Region track = new Region(), fill = new Region();
            track.setStyle("-fx-background-color:rgba(255,255,255,0.08);-fx-background-radius:2;");
            fill.setStyle("-fx-background-color:" + (ch.chosen() ? "#36CDF2" : "#3DD6C6") + ";-fx-background-radius:2;");
            track.setPrefHeight(4);
            fill.setPrefHeight(4);
            fill.setMaxWidth(Math.max(2, 230 * ch.probability()));
            StackPane bar = new StackPane(track, fill);
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            VBox cell = new VBox(5, new HBox(6, name, gap, pct), bar);
            cell.setMaxWidth(Double.MAX_VALUE);
            Button rowButton = new Button();
            rowButton.setGraphic(cell);
            rowButton.setMaxWidth(Double.MAX_VALUE);
            rowButton.getStyleClass().add("glass-button");
            if (ch.chosen()) rowButton.setStyle("-fx-border-color:rgba(54,205,242,0.65);");
            rowButton.setFocusTraversable(false);
            rowButton.setDisable(ch.probability() <= 0);
            rowButton.setTooltip(new Tooltip("Tirer un événement de ce cas (sa probabilité reste affichée)"));
            rowButton.setOnAction(e -> {
                collision.pending("Le moteur tire un événement dans ce cas…");
                actions.force(ch.index());
            });
            casesBox.getChildren().add(rowButton);
        }
    }

    void selectProjectile(int index) {
        beamButtons[index].setSelected(true);
        projectileChanged();
    }

    /** Un hadron : l'impulsion du faisceau (proton dès 1,5 GeV/c, pion dès 0,15 GeV/c pour voir le Δ), pas d'angle choisi. */
    private boolean hadronBeam() {
        return projectileIndex() >= 6;
    }

    private void projectileChanged() {
        int i = projectileIndex();
        String verb = FIRE[i];
        fireButton.setText(Character.toUpperCase(verb.charAt(0)) + verb.substring(1) + "  →");
        beamCard.setText(FACTS[i]);
        card(beamCard, i >= 6 ? CollisionScene.hadronColour(i == 6 ? "proton" : "π") : CollisionScene.leptonColour(SYMBOLS[i]));
        double low = i == 6 ? 1500 : i >= 7 ? 150 : 300;
        double value = Math.max(low, Math.pow(10, energy.getValue()));
        energy.setMin(Math.log10(low));
        energy.setValue(Math.log10(value));
        angle.setDisable(hadronBeam() || physicsAngle.isSelected());
        physicsAngle.setDisable(hadronBeam());
        for (Button b : presets) b.setDisable(hadronBeam());
        energyLabel.setText(energyText());
        angleLabel.setText(angleText());
    }


    private Button preset(String name, double mev, double degrees) {
        Button b = new Button(name);
        b.getStyleClass().add("glass-button");
        b.setFocusTraversable(false);
        presets.add(b);
        b.setTooltip(new Tooltip(String.format(Locale.FRANCE, "%s MeV à %.0f°", DocStyle.number(mev), degrees)));
        b.setOnAction(e -> {
            energy.setValue(Math.log10(mev));
            physicsAngle.setSelected(false);                         // un préréglage fixe l'angle
            angle.setDisable(false);
            angle.setValue(degrees);
            fire();
        });
        return b;
    }

    private String energyText() {
        double mev = Math.pow(10, energy.getValue());
        if (hadronBeam()) return mev >= 1000 ? String.format(Locale.FRANCE, "p %.2f GeV/c", mev / 1000)
                : String.format(Locale.FRANCE, "p %.0f MeV/c", mev);
        return mev >= 1000 ? String.format(Locale.FRANCE, "%.2f GeV", mev / 1000) : String.format(Locale.FRANCE, "%.0f MeV", mev);
    }

    private String angleText() {
        if (hadronBeam()) return "tous";
        if (physicsAngle.isSelected()) return "dσ/dΩ";
        return String.format(Locale.FRANCE, "%.0f°", angle.getValue());
    }

    private static HBox labelled(String name, Slider slider, Label value) {
        Label l = new Label(DocStyle.caps(name));
        l.getStyleClass().add("doc-caption");
        value.setMinWidth(70);
        HBox box = new HBox(8, l, slider, value);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    static Button button(String text, boolean primary) {
        Button b = new Button(primary ? DocStyle.caps(text) : text);
        b.getStyleClass().add(primary ? "doc-primary" : "doc-button");
        b.setFocusTraversable(false);
        return b;
    }

    private static ToggleButton tab(String text) {
        ToggleButton t = new ToggleButton(DocStyle.caps(text));
        t.getStyleClass().add("doc-tab");
        t.setFocusTraversable(false);
        return t;
    }

    private static ToggleButton segment(String text, String side) {
        ToggleButton t = new ToggleButton(text);
        t.getStyleClass().add("doc-segment");
        if (!side.isEmpty()) t.getStyleClass().add(side);
        t.setFocusTraversable(false);
        return t;
    }

    void applyFrame(UniverseFrame frame) {
        if (frame.getStandardModel().getParticlesCount() > 0) table.setParticles(frame.getStandardModel().getParticlesList());
        collision.apply(frame.getNucleon(), frame.getCollision(), frame.getBag());
        bench.apply(frame.getHadronLab());
        boolean partons = frame.getPartons().getActive();
        if (partons != partonsActive) {
            partonsActive = partons;
            if (partonNode != null) partonNode.setVisible(partons);
            if (partons) {
                collisionPane.setVisible(false);
                benchPane.setVisible(false);
                partonTab.setSelected(true);
            } else if (partonTab.isSelected()) {
                show(0);
            }
        }
    }

    void tick(double dt) {
        if (collisionPane.isVisible()) {
            collision.tick(dt);
            updating = true;
            scrub.setValue(collision.story() / collision.total());
            updating = false;
            play.setSelected(collision.playing());
            play.setText(collision.playing() ? "Ⅱ" : "▶");
            clock.setText(collision.beatCount() == 0 ? "" : String.format(Locale.FRANCE, "étape %d / %d", collision.beatIndex() + 1,
                    collision.beatCount()));
        }
        if (benchPane.isVisible()) bench.tick(dt);
    }

    boolean partonsShown() { return partonsActive; }

    // pour les aperçus et les tests
    CollisionScene collisionScene() { return collision; }

    HadronBench bench() { return bench; }

    void showBench() { show(1); }
}
