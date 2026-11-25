package org.genesis.common;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Le style de Genesis, commun à tous les laboratoires.
 *
 * <p>L'idée : la scène est le sujet, l'interface est le boîtier de l'instrument — un graphite neutre, plus clair que
 * l'espace des scènes et sans teinte à lui, pour que chaque scène garde ses couleurs. Les groupes se lisent par l'espace
 * et par leur titre, pas par des cadres ; le texte visible se limite aux noms et aux valeurs, les explications attendent
 * le survol (voir {@link #explain}) ou le guide. Une seule famille de caractères, IBM Plex Sans, en quatre graisses :
 * légère pour les grands nombres, normale pour le texte, moyenne pour les commandes, demi-grasse pour les titres. Ses
 * chiffres ont tous la même chasse : les valeurs qui changent ne dansent pas.
 *
 * <p>Couleurs : l'encre en trois niveaux, un accent par laboratoire (genesis.css : -accent, réservé à l'action
 * principale, à ce qui est allumé et au focus), une seule couleur d'alerte. Les panneaux dessinés (canevas) prennent
 * ici les mêmes couleurs et les mêmes polices que la feuille de style.
 *
 * <p>Polices : IBM Plex Sans (licence SIL OFL 1.1, voir resources/fonts/OFL.txt), embarquée pour que le dessin soit le
 * même sous Windows, macOS et Linux.
 */
public final class Plan {
    private Plan() { }

    // Les familles. Lire l'une d'elles charge les polices (une constante littérale serait recopiée à la compilation,
    // sans jamais exécuter ce chargement : d'où l'appel de méthode).
    public static final String SANS = loaded("IBM Plex Sans");
    public static final String LIGHT = loaded("IBM Plex Sans Light");
    public static final String MEDIUM = loaded("IBM Plex Sans Medm");
    public static final String SANS_SEMIBOLD = loaded("IBM Plex Sans SmBld");
    /**
     * L'ancienne chasse fixe : désormais la même famille (ses chiffres sont tabulaires). Gardée pour que les canevas qui
     * la demandent suivent le style sans changer chacun.
     */
    public static final String MONO = loaded("IBM Plex Sans");
    public static final String MONO_SEMIBOLD = loaded("IBM Plex Sans SmBld");

    /** Les colonnes, de la même largeur dans chaque laboratoire : à gauche ce qu'on prépare, à droite ce qu'on mesure. */
    public static final double LEFT_WIDTH = 284, RIGHT_WIDTH = 332;

    // Les fonds : les panneaux, l'application (un ton plus bas), derrière les scènes, les cartes et menus (un ton plus haut).
    public static final String PAPER_HEX = "#111419", DEEP_HEX = "#0C0E12", SCENE_HEX = "#07090C", RAISED_HEX = "#181C23";
    // L'encre : le texte principal, le secondaire, les légendes.
    public static final String INK_HEX = "#ECEFF3", INK2_HEX = "#A2ABB8", INK3_HEX = "#6B7483";
    // L'unique couleur d'attention : une consigne, une alerte, ce qu'il faut regarder maintenant.
    public static final String MARK_HEX = "#F2C14E";
    // Ce qui va bien (un verdict qui tient) et ce qui échoue : les deux mêmes partout.
    public static final String GOOD_HEX = "#6CD09A", BAD_HEX = "#F2766B";

    public static final Color PAPER = Color.web(PAPER_HEX), DEEP = Color.web(DEEP_HEX), SCENE = Color.web(SCENE_HEX),
            RAISED = Color.web(RAISED_HEX);
    public static final Color INK = Color.web(INK_HEX), INK2 = Color.web(INK2_HEX), INK3 = Color.web(INK3_HEX);
    public static final Color MARK = Color.web(MARK_HEX), GOOD = Color.web(GOOD_HEX), BAD = Color.web(BAD_HEX);
    /** Les traits : le fin (séparations), le fort (ce qu'on désigne), le très léger (fonds de jauges, quadrillages). */
    public static final Color LINE = Color.web("#FFFFFF", 0.09), STRONG = Color.web("#FFFFFF", 0.30),
            FAINT = Color.web("#FFFFFF", 0.05);
    /** Le rayon des cartes dessinées sur les scènes (le même que celui des menus et des fiches). */
    public static final double RADIUS = 8;

    private static boolean ready;

    private static String loaded(String family) {
        load();
        return family;
    }

    /** Charge les polices embarquées (une fois). */
    public static synchronized void load() {
        if (ready) return;
        ready = true;
        for (String file : new String[]{"IBMPlexSans-Regular", "IBMPlexSans-Light", "IBMPlexSans-Medium",
                "IBMPlexSans-SemiBold", "IBMPlexSans-Bold", "IBMPlexMono-Regular", "IBMPlexMono-SemiBold"}) {
            try (InputStream in = Plan.class.getResourceAsStream("/fonts/" + file + ".ttf")) {
                if (in != null) Font.loadFont(in, 12);
            } catch (IOException ignored) {
                // Sans la police, JavaFX prend celle du système : le dessin reste lisible.
            }
        }
    }

    public static Font sans(double size) {
        return Font.font(SANS, size);
    }

    /** Les grands nombres et les titres d'affiche. */
    public static Font light(double size) {
        return Font.font(LIGHT, size);
    }

    /** Les commandes, les valeurs mises en avant. */
    public static Font medium(double size) {
        return Font.font(MEDIUM, size);
    }

    public static Font semibold(double size) {
        return Font.font(SANS_SEMIBOLD, size);
    }

    public static Font bold(double size) {
        return Font.font(SANS, FontWeight.BOLD, size);
    }

    /** Autrefois la chasse fixe ; la même famille désormais (chiffres tabulaires). */
    public static Font mono(double size) {
        return Font.font(SANS, size);
    }

    /**
     * La couleur d'une énergie (ou d'une puissance) rapportée à sa plage, f de 0 à 1 : bleu glace (faible), vert d'eau, ambre,
     * rouge (forte). La même échelle pour toutes les visées.
     */
    public static Color energy(double f) {
        double t = Math.max(0, Math.min(1, f));
        Color[] stops = {Color.web("#7CC4FF"), Color.web("#4FD1A9"), Color.web("#F2C14E"), Color.web("#F2766B")};
        double x = t * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) Math.floor(x));
        return stops[i].interpolate(stops[i + 1], x - i);
    }

    /** Une couleur en texte CSS, avec son opacité. */
    public static String css(Color c) {
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", Math.round(c.getRed() * 255),
                Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255), c.getOpacity());
    }

    /** Une couleur en #RRGGBB (sans son opacité). */
    public static String hex(Color c) {
        return String.format("#%02X%02X%02X", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255),
                Math.round(c.getBlue() * 255));
    }

    // ------------------------------------------------------------------ les canevas

    /**
     * Une carte dessinée sur une scène ou dans un panneau : son fond (si fill n'est pas null), coins arrondis, et un trait
     * fin tout autour, un peu plus clair en haut (la lumière vient d'en haut, comme sur les menus).
     */
    public static void frame(GraphicsContext g, double x, double y, double w, double h, Color fill) {
        double r = Math.min(RADIUS, Math.min(w, h) / 2);
        if (fill != null) {
            g.setFill(fill);
            g.fillRoundRect(x, y, w, h, 2 * r, 2 * r);
        }
        g.setLineDashes();
        g.setLineWidth(1);
        g.setStroke(LINE);
        g.strokeRoundRect(Math.floor(x) + .5, Math.floor(y) + .5, Math.round(w) - 1, Math.round(h) - 1, 2 * r, 2 * r);
    }

    /** Le fond d'un panneau dessiné. */
    public static void paper(GraphicsContext g, double w, double h) {
        g.setFill(PAPER);
        g.fillRect(0, 0, w, h);
    }

    /** Le quadrillage, très léger. */
    public static void grid(GraphicsContext g, double x, double y, double w, double h) {
        g.setStroke(FAINT);
        g.setLineWidth(1);
        g.setLineDashes();
        for (double gx = Math.ceil(x / 24) * 24; gx < x + w; gx += 24) g.strokeLine(Math.floor(gx) + .5, y, Math.floor(gx) + .5, y + h);
        for (double gy = Math.ceil(y / 24) * 24; gy < y + h; gy += 24) g.strokeLine(x, Math.floor(gy) + .5, x + w, Math.floor(gy) + .5);
    }

    // ------------------------------------------------------------------ les panneaux en nœuds JavaFX

    /** Une section de colonne : un titre court, puis son contenu ; l'espace la sépare de la suivante, pas un cadre. */
    public static VBox section(String title, Node... nodes) {
        Label name = new Label(title);
        name.getStyleClass().add("plan-title");
        name.setMinWidth(Region.USE_PREF_SIZE);
        HBox head = new HBox(8, name);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("plan-section-title");
        VBox box = new VBox(10);
        box.getChildren().add(head);
        box.getChildren().addAll(nodes);
        box.getStyleClass().add("plan-section");
        return box;
    }

    /** L'ancienne forme (un numéro devant le titre) : le numéro n'est plus montré. */
    public static VBox section(String index, String title, Node... nodes) {
        return section(sentence(title), nodes);
    }

    /** Les noms propres et les sigles qu'une phrase en capitales contient : ils gardent leur forme. */
    private static final java.util.Map<String, String> PROPER = new java.util.HashMap<>();
    static {
        for (String name : new String[]{"Coulomb", "Gamow", "Lawson", "Sommerfeld", "Woods–Saxon", "Woods-Saxon", "WKB", "Bohr",
                "Pauli", "Hund", "DGLAP", "QCD", "GFN1-xTB", "Weizsäcker", "MIT", "NIST", "CODATA", "ENDF", "Aston",
                "Breit–Wigner", "Rosenbluth", "Kelly", "Yukawa", "Schwinger", "Dirac", "Fermi", "Einstein", "Planck", "Higgs",
                "Lorentz", "Maxwell", "Boltzmann", "Debye", "Langmuir", "Hartree", "Slater", "Mayer", "Mulliken", "Hirshfeld",
                "Bjorken", "Feynman", "Compton", "Rutherford", "Mott", "Bethe", "TNT", "Hiroshima", "Z", "N", "A", "B", "E", "Q",
                "W", "Δ", "Λ", "Ω", "J/ψ", "CSVR", "NPT", "NVT", "NVE", "HOMO", "LUMO", "SCC", "ADN", "Genesis"}) {
            PROPER.put(name.toUpperCase(Locale.FRANCE), name);
        }
    }

    /**
     * Une phrase écrite en capitales par l'ancien style (« LE MUR À FRANCHIR », « LE PLASMA · LAWSON »), remise en
     * minuscules — sa première lettre, les noms propres, les sigles, les symboles (U, MeV, ²³⁵U) gardés. Une phrase qui
     * a déjà des minuscules est rendue telle quelle.
     */
    public static String sentence(String title) {
        if (title == null || title.isEmpty() || !title.equals(title.toUpperCase(Locale.FRANCE))) return title;
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\p{L}\\p{M}'’/–-]+|[^\\p{L}\\p{M}'’/–-]+").matcher(title);
        while (m.find()) {
            String token = m.group();
            boolean word = Character.isLetter(token.charAt(0));
            String proper = PROPER.get(token);
            if (!word) out.append(token);
            else if (proper != null) out.append(proper);
            else if (token.codePointCount(0, token.length()) == 1 && !"ÀÉÈÙ".contains(token)) out.append(token);
            else out.append(token.toLowerCase(Locale.FRANCE));
        }
        int first = 0;
        while (first < out.length() && !Character.isLetter(out.charAt(first))) first++;
        if (first < out.length()) out.setCharAt(first, Character.toUpperCase(out.charAt(first)));
        return out.toString();
    }

    /** Ajoute au titre d'une section quelque chose à sa droite (une valeur, un bouton discret). */
    public static VBox trailing(VBox section, Node... nodes) {
        HBox head = (HBox) section.getChildren().get(0);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        head.getChildren().add(grow);
        head.getChildren().addAll(nodes);
        return section;
    }

    /**
     * Une section qu'on replie : son titre, un chevron ; repliée, seul le titre reste. Pour ce qui sert moins souvent
     * (le plasma de Lawson, les vibrations…), pour que la colonne reste courte.
     */
    public static VBox foldable(String title, boolean open, Node... nodes) {
        VBox section = section(title, nodes);
        HBox head = (HBox) section.getChildren().get(0);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Node chevron = Icons.path("chevron-down", 12);
        StackPane mark = new StackPane(chevron);
        mark.getStyleClass().add("fold-mark");
        head.getChildren().addAll(grow, mark);
        head.getStyleClass().add("fold-head");
        Runnable apply = () -> {
            boolean shown = section.getProperties().get("open") == Boolean.TRUE;
            for (int k = 1; k < section.getChildren().size(); k++) {
                section.getChildren().get(k).setVisible(shown);
                section.getChildren().get(k).setManaged(shown);
            }
            chevron.setRotate(shown ? 0 : -90);
            if (shown) section.getStyleClass().remove("folded");
            else if (!section.getStyleClass().contains("folded")) section.getStyleClass().add("folded");
        };
        section.getProperties().put("open", open);
        head.setOnMouseClicked(e -> {
            section.getProperties().put("open", section.getProperties().get("open") != Boolean.TRUE);
            apply.run();
        });
        apply.run();
        return section;
    }

    /** Ouvre ou replie une section faite par {@link #foldable}. */
    public static void open(VBox section, boolean open) {
        if (section.getProperties().get("open") == Boolean.valueOf(open)) return;
        HBox head = (HBox) section.getChildren().get(0);
        if (head.getOnMouseClicked() != null) head.getOnMouseClicked().handle(null);
    }

    /** Un intitulé à gauche, une valeur à droite. */
    public static HBox row(String caption, Node value) {
        Label name = label(caption);
        name.setMinWidth(Region.USE_PREF_SIZE);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox row = new HBox(8, name, grow, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Un intitulé (encre secondaire). */
    public static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("plan-label");
        return l;
    }

    /** Une valeur (encre pleine, graisse moyenne). */
    public static Label value(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("plan-value");
        return l;
    }

    /** Une note courte (encre secondaire, sur plusieurs lignes). */
    public static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("plan-note");
        l.setWrapText(true);
        return l;
    }

    /**
     * Une mesure mise en avant : son nom en petit, puis sa valeur en grand (graisse légère) et son unité. La valeur est
     * le Label passé (que l'appelant met à jour).
     */
    public static VBox metric(String caption, Label value, String unit) {
        value.getStyleClass().add("plan-metric");
        Label u = new Label(unit);
        u.getStyleClass().add("plan-unit");
        HBox line = new HBox(5, value, u);
        line.setAlignment(Pos.BASELINE_LEFT);
        VBox box = new VBox(1, label(caption), line);
        box.getStyleClass().add("plan-metric-box");
        return box;
    }

    // ------------------------------------------------------------------ les commandes

    /** Un bouton secondaire. */
    public static Button button(String text) {
        Button b = new Button(text);
        b.setFocusTraversable(false);
        return b;
    }

    /** L'action principale d'une colonne : pleine, dans la couleur du laboratoire (une seule par colonne). */
    public static Button primary(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("plan-primary");
        b.setFocusTraversable(false);
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    /** Un bouton discret (sans fond tant qu'on ne le survole pas). */
    public static Button quiet(String text) {
        Button b = new Button(text);
        b.getStyleClass().setAll("button", "plan-quiet");
        b.setFocusTraversable(false);
        return b;
    }

    /** Un bouton fait d'une icône (et d'un texte, s'il n'est pas vide), avec sa fiche au survol. */
    public static Button icon(String icon, String text, String tip) {
        Button b = new Button(text == null ? "" : text, Icons.path(icon, 16));
        b.getStyleClass().setAll("button", "icon-button");
        if (text == null || text.isEmpty()) b.getStyleClass().add("only");
        b.setFocusTraversable(false);
        if (tip != null) b.setTooltip(tooltip(tip));
        return b;
    }

    /** Une bascule faite d'une icône. */
    public static ToggleButton iconToggle(String icon, String tip) {
        ToggleButton b = new ToggleButton("", Icons.path(icon, 16));
        b.getStyleClass().setAll("toggle-button", "icon-button", "only");
        b.setFocusTraversable(false);
        if (tip != null) b.setTooltip(tooltip(tip));
        return b;
    }

    /**
     * Des boutons en commande segmentée : une piste, le segment choisi en relief. Les boutons gardent leur groupe et
     * leurs actions ; on ne fait que leur donner la forme (et la place égale qu'ils partagent si stretch).
     */
    public static HBox segmented(boolean stretch, ToggleButton... buttons) {
        HBox box = new HBox(0);
        for (int k = 0; k < buttons.length; k++) {
            ToggleButton b = buttons[k];
            b.getStyleClass().removeAll("segment", "left", "middle", "right", "glass-toggle", "view-toggle", "chip");
            b.getStyleClass().addAll("segment", buttons.length == 1 ? "single" : k == 0 ? "left"
                    : k == buttons.length - 1 ? "right" : "middle");
            b.setFocusTraversable(false);
            if (stretch) {
                b.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(b, Priority.ALWAYS);
            }
            box.getChildren().add(b);
        }
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * Un curseur dont la partie déjà parcourue prend la couleur du laboratoire (la piste se remplit jusqu'au bouton).
     * Fonctionne dès que le curseur a sa peau (son dessin) ; se met à jour à chaque valeur.
     */
    public static Slider filled(Slider slider) {
        Runnable paint = () -> {
            Node track = slider.lookup(".track");
            if (track == null) return;
            double span = slider.getMax() - slider.getMin();
            double f = span <= 0 ? 0 : (slider.getValue() - slider.getMin()) / span;
            String pct = String.format(Locale.ROOT, "%.2f%%", 100 * Math.max(0, Math.min(1, f)));
            String toward = slider.getOrientation() == Orientation.VERTICAL ? "to top" : "to right";
            track.setStyle("-fx-background-color: linear-gradient(" + toward + ", -accent 0%, -accent " + pct
                    + ", rgba(255,255,255,0.13) " + pct + ", rgba(255,255,255,0.13) 100%);");
        };
        slider.valueProperty().addListener((o, a, b) -> paint.run());
        slider.minProperty().addListener((o, a, b) -> paint.run());
        slider.maxProperty().addListener((o, a, b) -> paint.run());
        slider.skinProperty().addListener((o, a, b) -> javafx.application.Platform.runLater(paint));
        slider.sceneProperty().addListener((o, a, b) -> javafx.application.Platform.runLater(paint));
        javafx.application.Platform.runLater(paint);
        return slider;
    }

    /** Donne le remplissage de {@link #filled} à tous les curseurs d'un panneau. */
    public static void fillSliders(Node root) {
        for (Node n : root.lookupAll(".slider")) {
            if (n instanceof Slider s && s.getProperties().putIfAbsent("plan-filled", true) == null) filled(s);
        }
    }

    // ------------------------------------------------------------------ les explications

    /**
     * Fait suivre la langue à un texte (posé par l'application : I18n). Les bulles et les fiches ne sont pas dans le
     * graphe de la scène tant qu'elles ne s'ouvrent pas : leur texte doit être suivi dès sa création.
     */
    public static java.util.function.Consumer<javafx.beans.property.Property<String>> language = p -> { };

    /** La largeur d'une ligne de texte dans une police. */
    private static double width(String text, Font font) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(text);
        t.setFont(font);
        return t.getLayoutBounds().getWidth();
    }

    /** Une bulle simple (texte), qui apparaît vite et reste tant que la souris est là ; au-delà de 320 px, elle passe à la ligne. */
    public static Tooltip tooltip(String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(Duration.millis(350));
        t.setShowDuration(Duration.INDEFINITE);
        t.setHideDelay(Duration.millis(80));
        t.setWrapText(true);
        t.setMaxWidth(340);
        language.accept(t.textProperty());
        return t;
    }

    /**
     * Une fiche au survol : un titre, puis une ou deux phrases (et, si donnée, une ligne de formule). C'est là que vont
     * les explications : le panneau ne garde que les noms et les valeurs.
     */
    public static Tooltip explain(String title, String body, String formula) {
        // Des largeurs fixées (texte et bloc) et une hauteur à sa mesure : sinon une fiche dont le texte passe à la ligne
        // se calcule pour une largeur nulle et devient immense (1 800 px pour trois lignes).
        double w = Math.min(296, Math.max(width(title, semibold(12.5)) + 4, Math.max(
                width(body == null ? "" : body, sans(12)), formula == null ? 0 : width(formula, sans(14))) + 4));
        w = Math.max(160, w);
        Label head = new Label(title);
        head.getStyleClass().add("explain-title");
        Label text = new Label(body);
        text.getStyleClass().add("explain-body");
        VBox box = new VBox(5, head, text);
        Label f = null;
        if (formula != null && !formula.isEmpty()) {
            f = new Label(formula);
            f.getStyleClass().add("explain-formula");
            box.getChildren().add(f);
        }
        for (javafx.scene.Node n : box.getChildren()) {
            Label l = (Label) n;
            l.setWrapText(true);
            l.setMinWidth(w);
            l.setPrefWidth(w);
            l.setMaxWidth(w);
            l.setMinHeight(Region.USE_PREF_SIZE);
            language.accept(l.textProperty());
        }
        box.setMinWidth(w + 4);
        box.setPrefWidth(w + 4);
        box.setMaxWidth(w + 4);
        box.setMinHeight(Region.USE_PREF_SIZE);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        box.setPadding(new Insets(2, 2, 2, 2));
        Tooltip t = new Tooltip();
        t.setGraphic(box);
        t.getStyleClass().add("explain");
        t.setShowDelay(Duration.millis(350));
        t.setShowDuration(Duration.INDEFINITE);
        t.setHideDelay(Duration.millis(80));
        return t;
    }

    /** Pose une fiche d'explication sur un nœud. */
    public static <T extends Node> T explained(T node, String title, String body, String formula) {
        Tooltip t = explain(title, body, formula);
        if (node instanceof javafx.scene.control.Control c) c.setTooltip(t);
        else Tooltip.install(node, t);
        return node;
    }

    /** Le petit « ? » posé à côté d'un titre : sa fiche explique. */
    public static Label why(String title, String body, String formula) {
        Label mark = new Label("?");
        mark.getStyleClass().add("why");
        mark.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Tooltip.install(mark, explain(title, body, formula));
        return mark;
    }

    /** Montre ou cache un nœud en libérant (ou en reprenant) sa place. */
    public static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** Le bouton enfoncé ou non selon une condition (sans déclencher son action). */
    public static void select(ButtonBase b, boolean on) {
        if (b instanceof ToggleButton t && t.isSelected() != on) t.setSelected(on);
    }
}
