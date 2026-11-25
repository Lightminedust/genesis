package org.genesis.subatomic;

import org.genesis.common.Plan;

import org.genesis.app.I18n;
import org.genesis.fusion.FusionDashboard;

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
public final class QuantumLab {
    public interface Actions {
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
    private final HBox modes = new HBox(0);
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

    public QuantumLab(Actions actions) {
        this.actions = actions;
        this.collision = new CollisionScene(actions::force);
        root.getStyleClass().addAll("doc-lab", "lab-subatomic");     // lab-subatomic : sa couleur (genesis.css)
        bench = new HadronBench(table, new HadronBench.Actions() {
            @Override public void test(int spinIndex, double[] triples) { actions.test(spinIndex, triples); }
            @Override public void decay() { actions.decay(); }
            @Override public void clear() { actions.clear(); }
        });

        ToggleGroup tabs = new ToggleGroup();
        for (ToggleButton t : new ToggleButton[]{collisionTab, benchTab, partonTab}) t.setToggleGroup(tabs);
        collisionTab.setSelected(true);
        collisionTab.setOnAction(e -> show(0));
        benchTab.setOnAction(e -> show(1));
        partonTab.setOnAction(e -> show(2));
        // Les trois écrans sont les modes du laboratoire : ils vont dans l'en-tête commun (voir modes()).
        collisionTab.getStyleClass().add("left");
        benchTab.getStyleClass().add("middle");
        partonTab.getStyleClass().add("right");
        modes.getChildren().setAll(collisionTab, benchTab, partonTab);

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
        energy.valueProperty().addListener((o, a, b) -> { energyLabel.setText(energyText()); aim(); });
        angle.valueProperty().addListener((o, a, b) -> { angleLabel.setText(angleText()); aim(); });
        energyLabel.setText(energyText());
        for (Label l : new Label[]{energyLabel, angleLabel}) l.getStyleClass().add("plan-value");
        clock.getStyleClass().add("plan-label");
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
            t.setTooltip(I18n.tooltip(FACTS[i].replace("\n", " · ")));
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
        again.setTooltip(I18n.tooltip("Un nouveau tirage dans le même spectre : le cas peut changer, avec sa probabilité"));
        again.setOnAction(e -> {
            collision.pending("Le moteur tire un autre événement dans le même spectre…");
            actions.resample();
        });
        physicsAngle.getStyleClass().setAll("toggle-button", "glass-toggle");
        physicsAngle.setFocusTraversable(false);
        physicsAngle.setMaxWidth(Double.MAX_VALUE);
        physicsAngle.setSelected(true);
        physicsAngle.setTooltip(I18n.tooltip("La physique choisit l'angle selon dσ/dΩ (la plupart des électrons dévient à peine) ; "
                + I18n.text("décoché, c'est le curseur qui le fixe")));
        physicsAngle.setOnAction(e -> {
            angle.setDisable(hadronBeam() || physicsAngle.isSelected());
            angleLabel.setText(angleText());
            aim();
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
        sections.setPadding(new Insets(14, 16, 12, 16));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(sections);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background:transparent;-fx-background-color:transparent;-fx-border-color:transparent;");
        VBox dock = new VBox(7, fire, again);
        dock.getStyleClass().add("plan-dock");
        VBox side = new VBox(scroll, dock);
        this.side = side;
        VBox.setVgrow(scroll, Priority.ALWAYS);
        side.getStyleClass().addAll("plan-side", "left");
        side.setPrefWidth(Plan.LEFT_WIDTH);
        side.setMinWidth(Plan.LEFT_WIDTH);
        side.setMaxWidth(Plan.LEFT_WIDTH);
        collisionPane.setLeft(side);
        collisionPane.setCenter(collision.getNode());
        // la barre de lecture
        play.setSelected(true);
        play.getStyleClass().setAll("toggle-button", "tl-play");
        play.setText("");
        play.setGraphic(org.genesis.common.Icons.path("pause", 13));
        play.selectedProperty().addListener((o, was, now) -> play.setGraphic(org.genesis.common.Icons.path(now ? "pause" : "play", 13)));
        play.setTooltip(Plan.tooltip("Lire ou arrêter l'histoire · Espace"));
        play.setFocusTraversable(false);
        play.setOnAction(e -> collision.setPlaying(play.isSelected()));
        scrub.valueProperty().addListener((o, a, b) -> {
            if (!updating) {
                collision.seekStory(b.doubleValue() * collision.total());
                collision.setPlaying(false);
                play.setSelected(false);
            }
        });
        Button previous = Plan.icon("step-back", null, "Revenir au début de l'étape (ou à la précédente)");
        Button next = Plan.icon("step-forward", null, "Passer à l'étape suivante");
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
        Plan.filled(scrub);
        HBox playback = new HBox(8, previous, play, next, scrub, clock, Plan.segmented(false, slow, normal, fast));
        this.playback = playback;
        HBox.setHgrow(scrub, Priority.ALWAYS);
        playback.setAlignment(Pos.CENTER_LEFT);
        playback.setPadding(new Insets(7, 14, 7, 14));
        playback.getStyleClass().add("plan-foot");
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
        forbidden.setTooltip(I18n.tooltip("Trois quarks up de la même couleur : la règle de couleur et le principe de Pauli le refusent"));
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
        anti.setTooltip(I18n.tooltip("Remplace chaque quark par son antiquark (et inversement)"));
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
        Label mouldLabel = Plan.label("moule");
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

    public BorderPane getNode() { return root; }

    private VBox side;
    private HBox playback;

    /** Le guide du laboratoire (touche H) : ce qu'on y fait, par où commencer, ce que montre chaque zone. */
    public org.genesis.common.Guide guide() {
        org.genesis.common.Guide guide = new org.genesis.common.Guide("Subatomique", "Un proton ou un neutron vu de "
                + "l'intérieur. On le frappe avec une particule, et le moteur calcule ce qui peut arriver, cas par cas.");
        if (benchPane.isVisible()) {
            return guide.step("Poser des quarks dans le moule : trois pour un baryon, un quark et un antiquark pour un méson.")
                    .step("Tester : le moteur dit si l'objet existe, sa masse, sa charge, son spin, et comment il se désintègre.")
                    .spot(modes, "Trois ateliers", "La collision, l'atelier de hadrons, la mer de partons.")
                    .spot(benchPane.getTop(), "Composer", "Le moule, les essais, les hadrons célèbres en un clic.")
                    .spot(benchPane.getCenter(), "L'établi", "Tirer un quark pour étirer son tube de gluons ; il casse en créant une paire.");
        }
        return guide.step("Choisir le projectile (un électron au départ) et la cible.")
                .step("Régler l'énergie : basse, le nucléon rebondit entier ; haute, on voit ses quarks.")
                .step("Lancer, puis suivre l'histoire étape par étape, en bas.")
                .spot(modes, "Trois ateliers", "La collision, l'atelier de hadrons (composer des quarks), la mer de partons.")
                .spot(side, "Préparer", "Le projectile, la cible, l'énergie et l'angle ; les cas possibles et leurs probabilités.")
                .spot(collision.getNode(), "La scène", "Glisser : tourner · molette : zoomer · survoler un objet ou une trace : sa fiche.")
                .spot(playback, "L'histoire", "Les étapes de l'événement, au ralenti : revenir, avancer, changer la vitesse.");
    }

    /** La colonne de réglages : cachée, la scène prend toute la place. */
    public void showPanels(boolean shown) {
        if (side != null) Plan.show(side, shown);
    }

    /** La scène de l'écran montré, pour en enregistrer une image. */
    public Node sceneNode() {
        if (benchPane.isVisible()) return bench.getNode();
        if (partonNode != null && partonNode.isVisible()) return partonNode;
        return collision.getNode();
    }

    /** Les trois écrans du laboratoire (collision, atelier, mer de partons), posés dans l'en-tête commun. */
    public Region modes() { return modes; }

    /** La mer de partons (une vue à part) se range dans le même cadre. */
    public void attachPartons(Node node) {
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

    public void togglePlayback() {
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

    /** Une section de la colonne : le même dessin dans tous les laboratoires (voir Plan.section). */
    private static VBox section(String index, String title, Node... nodes) {
        return Plan.section(index, title, nodes);
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
        HBox row = new HBox(8, Plan.label(title), gap, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** La fiche du projectile ou de la cible : une surface douce, ses caractéristiques en encre secondaire. */
    private static void card(Label label, javafx.scene.paint.Color color) {
        label.setStyle("");
        if (!label.getStyleClass().contains("plan-fact")) label.getStyleClass().add("plan-fact");
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
            Label pct = new Label(String.format(I18n.locale(), "%.1f %%", 100 * ch.probability()));
            pct.getStyleClass().add("plan-value");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            Region track = new Region(), fill = new Region();
            track.setStyle("-fx-background-color:" + Plan.css(Plan.FAINT) + ";");
            fill.setStyle("-fx-background-color:" + (ch.chosen() ? Plan.INK_HEX : Plan.INK3_HEX) + ";");
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
            if (ch.chosen()) rowButton.setStyle("-fx-border-color:-accent;");
            rowButton.setFocusTraversable(false);
            rowButton.setDisable(ch.probability() <= 0);
            rowButton.setTooltip(I18n.tooltip("Tirer un événement de ce cas (sa probabilité reste affichée)"));
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
        aim();
    }

    /**
     * La visée dans la scène : dès qu'un réglage change, le prochain tir se dessine (le faisceau, la sortie demandée), dans la
     * couleur de son énergie ; elle s'efface quand le moteur rend la nouvelle collision.
     */
    private void aim() {
        if (updating || collision == null) return;
        double power = (energy.getValue() - Math.log10(150)) / (Math.log10(10000) - Math.log10(150));
        collision.aim(projectileIndex(), Math.pow(10, energy.getValue()), hadronBeam() || physicsAngle.isSelected() ? Double.NaN
                : angle.getValue(), power);
    }


    private Button preset(String name, double mev, double degrees) {
        Button b = new Button(name);
        b.getStyleClass().add("glass-button");
        b.setFocusTraversable(false);
        presets.add(b);
        b.setTooltip(I18n.tooltip(String.format(I18n.locale(), I18n.text("%s MeV à %.0f°"), DocStyle.number(mev), degrees)));
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
        if (hadronBeam()) return mev >= 1000 ? String.format(I18n.locale(), "p %.2f GeV/c", mev / 1000)
                : String.format(I18n.locale(), "p %.0f MeV/c", mev);
        return mev >= 1000 ? String.format(I18n.locale(), "%.2f GeV", mev / 1000) : String.format(I18n.locale(), "%.0f MeV", mev);
    }

    private String angleText() {
        if (hadronBeam()) return "tous";
        if (physicsAngle.isSelected()) return "dσ/dΩ";
        return String.format(I18n.locale(), "%.0f°", angle.getValue());
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
        ToggleButton t = new ToggleButton(Character.toUpperCase(text.charAt(0)) + text.substring(1));
        t.getStyleClass().add("segment");
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

    public void applyFrame(UniverseFrame frame) {
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

    public void tick(double dt) {
        if (collisionPane.isVisible()) {
            collision.tick(dt);
            updating = true;
            scrub.setValue(collision.story() / collision.total());
            updating = false;
            play.setSelected(collision.playing());
            // l'icône suit l'état (voir le constructeur) : ‖ en lecture, ▶ à l'arrêt
            clock.setText(collision.beatCount() == 0 ? "" : String.format(I18n.locale(), I18n.text("étape %d / %d"), collision.beatIndex() + 1,
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
