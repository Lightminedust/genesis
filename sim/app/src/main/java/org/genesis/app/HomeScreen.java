package org.genesis.app;

import org.genesis.common.Icons;
import org.genesis.common.Plan;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * L'accueil. En haut, ce qu'est Genesis en une phrase. Au milieu, les quatre laboratoires rangés du plus grand au plus
 * petit — molécules, atome, noyaux, nucléon — chacun relié à sa place sur une règle des tailles (de 10⁻⁸ à 10⁻¹⁶ m) :
 * on y voit d'un coup le vide de l'atome (son noyau est 10 000 à 100 000 fois plus petit). Chaque carte porte un dessin
 * tracé d'après une formule (la liaison du benzène, une orbitale, la barrière de Coulomb, les quarks d'un proton). En bas,
 * Genesis lui-même : ce qu'il est, ses liens (site, code, communauté, contact), sa version et sa langue.
 * Touches 1 à 4 ; Échap revient au laboratoire ouvert, s'il y en a un.
 */
final class HomeScreen {
    /** Une carte : son laboratoire, sa taille (puissance de dix), sa phrase, sa méthode (dans la fiche au survol). */
    private record Card(LabBar.Lab lab, double exponent, String size, String text, String method) { }

    /** Du plus grand au plus petit : on descend dans la matière de gauche à droite, comme sur la règle. */
    private static final Card[] CARDS = {
            new Card(LabBar.Lab.MOLECULE, -9.3, "≈ 0,5 nm · une molécule",
                    "Des atomes qui se lient, se cassent et réagissent, calculés par la chimie quantique.",
                    "Chimie quantique semi-empirique GFN1-xTB, recodée pour Genesis ; dynamique moléculaire."),
            new Card(LabBar.Lab.ATOM, -10, "≈ 0,1 nm · un atome",
                    "Un atome au choix : son nuage d'électrons, la lumière qu'il échange, son noyau qui se désintègre.",
                    "Orbitales de Slater, raies du NIST, désintégrations de NUBASE2020."),
            new Card(LabBar.Lab.FUSION, -14, "≈ 10 fm · deux noyaux",
                    "Deux noyaux lancés l'un vers l'autre : la barrière, l'effet tunnel, la fusion ou le rebond.",
                    "Potentiel de Woods–Saxon, effet tunnel (WKB), critère de Lawson pour le plasma."),
            new Card(LabBar.Lab.SUBATOMIC, -15, "≈ 1 fm · un nucléon",
                    "L'intérieur d'un proton : ses quarks, ses gluons, et ce qu'en voit une particule qui le frappe.",
                    "Facteurs de forme (Rosenbluth), fonctions de structure (DGLAP), modèle des quarks."),
    };
    /** Les liens de Genesis : texte, icône, adresse. */
    static final String[][] LINKS = {
            {"Site de Genesis", "globe", "https://genesis.gemmi-e.com"},
            {"Le code sur GitHub", "star", "https://github.com/Lightminedust/genesis"},
            {"La communauté, r/gemmie", "chat", "https://www.reddit.com/r/gemmie/"},
            {"Écrire à l'auteur", "mail", "mailto:nellawassi@gmail.com"},
    };
    private static final double ART_HEIGHT = 128, AXIS_HEIGHT = 96, FIRST_EXP = -8, LAST_EXP = -16;

    private final StackPane root = new StackPane();
    private final Consumer<LabBar.Lab> open;
    private final HBox cards = new HBox(14);
    private final List<VBox> cardBoxes = new ArrayList<>();
    private final Canvas axis = new Canvas(1000, AXIS_HEIGHT);
    private final Label version = new Label();
    private final Button update = Plan.quiet("");
    private LabBar.Lab current;

    HomeScreen(Consumer<LabBar.Lab> open, Consumer<String> openLink) {
        this.open = open;
        Label headline = new Label("La matière, calculée.");
        headline.getStyleClass().add("home-headline");
        Label lead = new Label("Quatre laboratoires, de la molécule au cœur du proton. Chaque image y est un calcul : rien "
                + "n'est animé à la main.");
        lead.getStyleClass().add("home-lead");
        lead.setWrapText(true);
        lead.setMaxWidth(720);

        for (int i = 0; i < CARDS.length; i++) {
            VBox box = card(CARDS[i], i + 1);
            cardBoxes.add(box);
            cards.getChildren().add(box);
        }
        cards.setAlignment(Pos.TOP_LEFT);
        axis.widthProperty().bind(cards.widthProperty());
        axis.widthProperty().addListener((o, a, b) -> drawAxis());
        cards.layoutBoundsProperty().addListener((o, a, b) -> drawAxis());
        for (VBox box : cardBoxes) box.boundsInParentProperty().addListener((o, a, b) -> drawAxis());
        VBox labs = new VBox(0, cards, axis);

        Region foot = footer(openLink);
        VBox content = new VBox(0, headline, gap(8), lead, gap(34), labs, gap(26), foot);
        content.setMaxWidth(1220);
        content.setPadding(new Insets(44, 56, 34, 56));
        StackPane centred = new StackPane(content);
        StackPane.setAlignment(content, Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(centred);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        root.getChildren().add(scroll);
        root.getStyleClass().add("home");
        drawAxis();
    }

    private static Region gap(double height) {
        Region gap = new Region();
        gap.setMinHeight(height);
        gap.setPrefHeight(height);
        return gap;
    }

    // ================================================================== les cartes
    private VBox card(Card card, int key) {
        Canvas art = new Canvas(250, ART_HEIGHT);
        Label name = new Label(card.lab().title);
        name.getStyleClass().add("home-name");
        Label badge = new Label("ouvert");
        badge.getStyleClass().add("home-badge");
        Plan.show(badge, false);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Label keyCap = new Label(String.valueOf(key));
        keyCap.getStyleClass().add("home-key");
        HBox title = new HBox(8, name, badge, grow, keyCap);
        title.setAlignment(Pos.CENTER_LEFT);
        Label text = new Label(card.text());
        text.getStyleClass().add("home-text");
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        Label size = new Label(card.size());
        size.getStyleClass().add("home-scale");
        // Au survol, la taille laisse la place à la méthode de calcul, au même endroit (pas de bulle qui couvre les cartes).
        Label method = new Label(card.method());
        method.getStyleClass().add("home-meta");
        method.setWrapText(true);
        method.setOpacity(0);
        StackPane foot = new StackPane(size, method);
        foot.setAlignment(Pos.TOP_LEFT);
        StackPane.setAlignment(size, Pos.TOP_LEFT);
        StackPane.setAlignment(method, Pos.TOP_LEFT);
        VBox box = new VBox(10, art, title, text, foot);
        box.setPadding(new Insets(14, 16, 16, 16));
        box.getStyleClass().addAll("home-card", card.lab().style);
        box.setPrefWidth(270);
        box.setMinWidth(190);
        box.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        art.widthProperty().bind(box.widthProperty().subtract(32));
        art.widthProperty().addListener((o, a, b) -> draw(art, card.lab()));
        draw(art, card.lab());
        box.setOnMouseClicked(event -> open.accept(card.lab()));
        javafx.animation.Timeline toMethod = fade(size, method, true), toSize = fade(size, method, false);
        box.setOnMouseEntered(event -> { toSize.stop(); toMethod.playFromStart(); });
        box.setOnMouseExited(event -> { toMethod.stop(); toSize.playFromStart(); });
        box.getProperties().put("badge", badge);
        box.getProperties().put("lab", card.lab());
        return box;
    }

    /** Le passage de la taille à la méthode (ou l'inverse), en 0,18 s. */
    private static javafx.animation.Timeline fade(Label size, Label method, boolean toMethod) {
        return new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.millis(180),
                new javafx.animation.KeyValue(size.opacityProperty(), toMethod ? 0 : 1, javafx.animation.Interpolator.EASE_BOTH),
                new javafx.animation.KeyValue(method.opacityProperty(), toMethod ? 1 : 0, javafx.animation.Interpolator.EASE_BOTH)));
    }

    // ================================================================== le pied : Genesis, ses liens, sa version
    private Region footer(Consumer<String> openLink) {
        // Le logotype GENESIS_ (assets/brand) tient lieu de titre.
        Node aboutTitle = org.genesis.common.Brand.node(org.genesis.common.Brand.LOGOTYPE, 12);
        Label about = new Label("Un simulateur libre et gratuit de la matière. Chaque laboratoire calcule ce qu'il montre "
                + "— mécanique quantique, physique nucléaire, chimie — et dit sa méthode et ses limites (bouton Méthode). "
                + "Le guide de chaque laboratoire s'ouvre avec H.");
        about.getStyleClass().add("home-about");
        about.setWrapText(true);
        VBox aboutBox = new VBox(8, aboutTitle, about);
        aboutBox.setPrefWidth(420);
        HBox.setHgrow(aboutBox, Priority.ALWAYS);

        Label linksTitle = new Label("Liens");
        linksTitle.getStyleClass().add("home-heading");
        VBox links = new VBox(2, linksTitle);
        for (String[] link : LINKS) {
            Button b = new Button(link[0], Icons.path(link[1], 14));
            b.getStyleClass().setAll("button", "home-link");
            if (link[1].equals("star")) b.getStyleClass().add("star");
            b.setFocusTraversable(false);
            b.setTooltip(Plan.tooltip(link[2].replaceFirst("^mailto:", "")));
            b.setOnAction(e -> openLink.accept(link[2]));
            links.getChildren().add(b);
        }
        links.setMinWidth(Region.USE_PREF_SIZE);
        VBox.setMargin(links.getChildren().get(1), new Insets(4, 0, 0, 0));

        Label appTitle = new Label("Application");
        appTitle.getStyleClass().add("home-heading");
        ToggleGroup languages = new ToggleGroup();
        ToggleButton french = new ToggleButton("Français"), english = new ToggleButton("English");
        french.setToggleGroup(languages);
        english.setToggleGroup(languages);
        french.setOnAction(e -> { french.setSelected(true); I18n.setLanguage(I18n.Language.FR); });
        english.setOnAction(e -> { english.setSelected(true); I18n.setLanguage(I18n.Language.EN); });
        Runnable sync = () -> (I18n.language() == I18n.Language.FR ? french : english).setSelected(true);
        I18n.onChange(sync);
        sync.run();
        HBox language = Plan.segmented(false, french, english);
        version.getStyleClass().add("home-version");
        Plan.show(update, false);
        HBox versionLine = new HBox(6, version, update);
        versionLine.setAlignment(Pos.CENTER_LEFT);
        Label keys = new Label("1 à 4 : ouvrir · H : guide · F11 : plein écran");
        keys.getStyleClass().add("home-version");
        VBox app = new VBox(8, appTitle, language, versionLine, keys);
        app.setMinWidth(Region.USE_PREF_SIZE);
        setVersion(null);

        HBox foot = new HBox(56, aboutBox, links, app);
        foot.setPadding(new Insets(24, 0, 0, 0));
        foot.getStyleClass().add("home-foot");
        return foot;
    }

    /** La version qui tourne (null : une version de développement, lancée depuis les sources). */
    void setVersion(String current) {
        version.setText(current == null ? I18n.text("Version de développement") : I18n.text("Version") + " " + current);
    }

    /** Une version plus récente existe : un lien pour la proposer. */
    void setUpdate(String newer, Runnable offer) {
        Plan.show(update, newer != null);
        if (newer == null) return;
        update.setText(newer + " " + I18n.text("disponible"));
        update.setOnAction(e -> offer.run());
    }

    // ================================================================== état
    Region getNode() {
        return root;
    }

    boolean isShowing() {
        return root.isVisible();
    }

    /** Le laboratoire ouvert derrière l'accueil (null : aucun) : sa carte le dit, Échap y revient. */
    void setCurrent(LabBar.Lab lab) {
        current = lab;
        for (VBox box : cardBoxes) {
            boolean on = box.getProperties().get("lab") == lab;
            Plan.show((Node) box.getProperties().get("badge"), on);
            box.getStyleClass().remove("current");
            if (on) box.getStyleClass().add("current");
        }
    }

    void show() {
        root.setVisible(true);
        root.setOpacity(0);
        javafx.animation.FadeTransition in = new javafx.animation.FadeTransition(javafx.util.Duration.millis(200), root);
        in.setToValue(1);
        in.play();
        drawAxis();
    }

    void hide() {
        root.setVisible(false);
    }

    /** Les touches 1 à 4 ouvrent un laboratoire (dans l'ordre des cartes), Échap revient au laboratoire ouvert. */
    boolean key(KeyCode code) {
        if (code == KeyCode.ESCAPE && current != null) {
            open.accept(current);
            return true;
        }
        int index = switch (code) {
            case DIGIT1, NUMPAD1 -> 0;
            case DIGIT2, NUMPAD2 -> 1;
            case DIGIT3, NUMPAD3 -> 2;
            case DIGIT4, NUMPAD4 -> 3;
            default -> -1;
        };
        if (index < 0) return false;
        open.accept(CARDS[index].lab());
        return true;
    }

    // ================================================================== la règle des tailles
    /**
     * La règle : une puissance de dix par graduation, de 10⁻⁸ m (à gauche) à 10⁻¹⁶ m (à droite), les unités usuelles
     * dessous (nm, Å, pm, fm). Chaque carte y descend par un trait jusqu'à sa taille. Entre l'atome et les noyaux, la
     * place vide dit le vide de l'atome.
     */
    private void drawAxis() {
        GraphicsContext g = axis.getGraphicsContext2D();
        double w = axis.getWidth(), h = axis.getHeight();
        g.clearRect(0, 0, w, h);
        if (w < 50 || cardBoxes.isEmpty()) return;
        double left = 18, right = w - 18, y = 46;
        // L'axe et ses graduations.
        g.setStroke(Plan.LINE);
        g.setLineWidth(1);
        g.strokeLine(left, y + .5, right, y + .5);
        g.setTextAlign(TextAlignment.CENTER);
        String[] units = {"", "1 nm", "1 Å", "", "1 pm", "", "", "1 fm", ""};
        for (int e = (int) FIRST_EXP; e >= (int) LAST_EXP; e--) {
            double x = xOf(e, left, right);
            g.setStroke(Plan.STRONG);
            g.strokeLine(Math.round(x) + .5, y - 3, Math.round(x) + .5, y + 4);
            g.setFill(Plan.INK3);
            g.setFont(Plan.sans(11));
            g.fillText("10" + superscript(e), x, y + 20);
            String unit = units[(int) (FIRST_EXP - e)];
            if (!unit.isEmpty()) {
                g.setFill(Color.web("#4B525D"));
                g.setFont(Plan.sans(10.5));
                g.fillText(unit, x, y + 34);
            }
        }
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(Plan.INK3);
        g.setFont(Plan.sans(10.5));
        g.fillText(I18n.text("plus petit") + "  →", right, y - 10);
        // Le vide de l'atome : entre l'atome (10⁻¹⁰ m) et les noyaux (10⁻¹⁴ m), ce que l'atome a de vide autour de son noyau.
        double a = xOf(-10.25, left, right), b = xOf(-13.75, left, right);
        g.setStroke(Plan.LINE);
        g.setLineDashes(2, 4);
        g.strokeLine(a, y - 12, b, y - 12);
        g.setLineDashes();
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Plan.INK3);
        g.setFont(Plan.sans(10.5));
        g.fillText(I18n.text("le noyau : 10 000 à 100 000 fois plus petit que son atome"), (a + b) / 2, y - 18);
        // Le trait de chaque carte jusqu'à sa taille.
        for (int i = 0; i < CARDS.length; i++) {
            VBox box = cardBoxes.get(i);
            Bounds bounds = box.getBoundsInParent();
            if (bounds.getWidth() < 1) continue;
            double from = bounds.getMinX() + bounds.getWidth() / 2, to = xOf(CARDS[i].exponent(), left, right);
            Color accent = Color.web(CARDS[i].lab().accent);
            g.setStroke(accent.deriveColor(0, 1, 1, .55));
            g.setLineWidth(1.2);
            g.setLineCap(StrokeLineCap.ROUND);
            g.beginPath();
            g.moveTo(from, 0);
            g.bezierCurveTo(from, y * .55, to, y * .45, to, y - 5);
            g.stroke();
            g.setFill(accent);
            g.fillOval(to - 3.5, y - 3, 7, 7);
        }
    }

    private static double xOf(double exponent, double left, double right) {
        return left + (FIRST_EXP - exponent) / (FIRST_EXP - LAST_EXP) * (right - left);
    }

    private static String superscript(int exponent) {
        String digits = "⁰¹²³⁴⁵⁶⁷⁸⁹";
        StringBuilder b = new StringBuilder(exponent < 0 ? "⁻" : "");
        for (char c : String.valueOf(Math.abs(exponent)).toCharArray()) b.append(digits.charAt(c - '0'));
        return b.toString();
    }

    // ================================================================== les dessins
    private static void draw(Canvas canvas, LabBar.Lab lab) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        g.setFill(Color.web("#0C0E12"));
        g.fillRoundRect(0, 0, w, h, 14, 14);
        g.setLineCap(StrokeLineCap.ROUND);
        Color accent = Color.web(lab.accent);
        switch (lab) {
            case ATOM -> atom(g, w, h, accent);
            case FUSION -> fusion(g, w, h, accent);
            case MOLECULE -> molecule(g, w, h, accent);
            case SUBATOMIC -> nucleon(g, w, h, accent);
        }
    }

    /** Une orbitale 3d_z² : la courbe r = R |3 cos²θ − 1| / 2 (sa partie angulaire), et les couches s en pointillé. */
    private static void atom(GraphicsContext g, double w, double h, Color accent) {
        double cx = w / 2, cy = h / 2, scale = h * .42;
        g.setLineWidth(1);
        g.setStroke(accent.deriveColor(0, 1, 1, .25));
        g.setLineDashes(2, 4);
        for (int n = 1; n <= 3; n++) {
            double r = scale * n * n / 9.0;
            g.strokeOval(cx - r, cy - r, 2 * r, 2 * r);
        }
        g.setLineDashes();
        g.setStroke(accent);
        g.setLineWidth(1.4);
        g.beginPath();
        for (int k = 0; k <= 720; k++) {
            double t = 2 * Math.PI * k / 720, c = Math.cos(t);
            double r = scale * Math.abs(3 * c * c - 1) / 2;
            double x = cx + r * Math.sin(t), y = cy - r * c;
            if (k == 0) g.moveTo(x, y);
            else g.lineTo(x, y);
        }
        g.stroke();
        g.setFill(accent);
        g.fillOval(cx - 2.5, cy - 2.5, 5, 5);
    }

    /**
     * La barrière de Coulomb : V(r) = k Z₁Z₂ e² / r au-delà du contact, un puits nucléaire en deçà ; une énergie sous le
     * sommet (le trait pointillé) ne passe que par l'effet tunnel.
     */
    private static void fusion(GraphicsContext g, double w, double h, Color accent) {
        double left = 20, right = w - 16, top = 16, base = h * .64, contact = left + (right - left) * .22;
        double peak = base - (h * .5), well = base + h * .22;
        g.setLineWidth(1);
        g.setStroke(Plan.LINE);
        g.strokeLine(left, base, right, base);
        g.strokeLine(left, top - 4, left, h - 10);
        g.setStroke(accent);
        g.setLineWidth(1.5);
        g.beginPath();
        g.moveTo(left, well);
        g.lineTo(contact, well);
        g.lineTo(contact, peak);
        for (double x = contact; x <= right; x += 1) {
            double y = base - (base - peak) * (contact - left) / (x - left);
            g.lineTo(x, y);
        }
        g.stroke();
        // Une énergie sous le sommet : on ne franchit le mur qu'en le traversant (pointillé dans la barrière).
        double energy = base - (base - peak) * .35;
        double turning = left + (contact - left) / .35;
        g.setLineWidth(1);
        g.setStroke(accent.deriveColor(0, 1, 1, .55));
        g.strokeLine(turning, energy, right, energy);
        g.setLineDashes(2, 3);
        g.strokeLine(contact, energy, turning, energy);
        g.setLineDashes();
        g.setFill(Plan.INK3);
        g.setFont(Font.font(Plan.SANS, 10.5));
        g.fillText("V(r)", left + 6, top + 4);
        g.fillText("r", right - 8, base - 5);
    }

    /** Le benzène : six carbones à 1,40 Å, une liaison sur deux doublée vers l'intérieur, six hydrogènes à 1,09 Å. */
    private static void molecule(GraphicsContext g, double w, double h, Color accent) {
        double cx = w / 2, cy = h / 2, scale = h * .185;           // px par Å : les hydrogènes tiennent dans le cadre
        double[][] c = new double[6][2], hy = new double[6][2];
        for (int i = 0; i < 6; i++) {
            double t = Math.PI / 6 + i * Math.PI / 3;
            c[i] = new double[]{cx + 1.40 * scale * Math.cos(t), cy + 1.40 * scale * Math.sin(t)};
            hy[i] = new double[]{cx + 2.49 * scale * Math.cos(t), cy + 2.49 * scale * Math.sin(t)};
        }
        g.setStroke(accent.deriveColor(0, 1, 1, .45));
        g.setLineWidth(1);
        for (int i = 0; i < 6; i++) g.strokeLine(c[i][0], c[i][1], hy[i][0], hy[i][1]);
        g.setStroke(accent);
        g.setLineWidth(1.5);
        for (int i = 0; i < 6; i++) {
            double[] a = c[i], b = c[(i + 1) % 6];
            g.strokeLine(a[0], a[1], b[0], b[1]);
            if (i % 2 == 0) {
                double ax = cx + (a[0] - cx) * .78, ay = cy + (a[1] - cy) * .78, bx = cx + (b[0] - cx) * .78, by = cy + (b[1] - cy) * .78;
                g.strokeLine(ax + (bx - ax) * .12, ay + (by - ay) * .12, ax + (bx - ax) * .88, ay + (by - ay) * .88);
            }
        }
        g.setFill(Color.web("#0C0E12"));
        for (double[] p : hy) {
            g.fillOval(p[0] - 3.5, p[1] - 3.5, 7, 7);
            g.strokeOval(p[0] - 3.5, p[1] - 3.5, 7, 7);
        }
    }

    /** Un proton : le sac, trois quarks de couleurs différentes (leur somme est neutre), le tube de gluons en Y. */
    private static void nucleon(GraphicsContext g, double w, double h, Color accent) {
        double cx = w / 2, cy = h / 2 + 2, bag = h * .4, arm = bag * .55;
        g.setStroke(accent.deriveColor(0, 1, 1, .5));
        g.setLineWidth(1);
        g.strokeOval(cx - bag, cy - bag, 2 * bag, 2 * bag);
        Color[] colours = {Color.web("#FF6B8B"), Color.web("#7CF29A"), Color.web("#6CB8FF")};
        g.setStroke(accent);
        g.setLineWidth(1.2);
        for (int i = 0; i < 3; i++) {
            double t = -Math.PI / 2 + i * 2 * Math.PI / 3, qx = cx + arm * Math.cos(t), qy = cy + arm * Math.sin(t);
            // Le gluon : un ressort du quark au centre (quatre spires).
            double dx = cx - qx, dy = cy - qy, len = Math.hypot(dx, dy), nx = -dy / len, ny = dx / len;
            g.beginPath();
            for (int k = 0; k <= 80; k++) {
                double s = k / 80.0, wave = 3.2 * Math.sin(s * 8 * Math.PI);
                double x = qx + dx * s + nx * wave, y = qy + dy * s + ny * wave;
                if (k == 0) g.moveTo(x, y);
                else g.lineTo(x, y);
            }
            g.stroke();
        }
        for (int i = 0; i < 3; i++) {
            double t = -Math.PI / 2 + i * 2 * Math.PI / 3, qx = cx + arm * Math.cos(t), qy = cy + arm * Math.sin(t);
            g.setFill(colours[i]);
            g.fillOval(qx - 5, qy - 5, 10, 10);
        }
    }
}
