package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeAssessment;
import com.studio.genesis.grpc.MoleculeEntry;
import com.studio.genesis.grpc.MoleculeLabState;
import com.studio.genesis.grpc.MoleculeVerdict;
import org.genesis.app.I18n;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.ImageCursor;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * L'assembleur moléculaire : on dessine ses molécules à plat, atome par atome, liaison par liaison, puis on les envoie
 * dans le simulateur 3D. Ce n'est pas un dessin libre :
 * <ul>
 *   <li>les valences : une liaison qui dépasserait ce qu'un atome peut porter (un carbone à cinq liaisons) est refusée,
 *       et dite ; une valence libre (un radical) est marquée d'un point rouge par électron manquant ;</li>
 *   <li>le moteur nomme chaque molécule dès qu'elle change (son dictionnaire reconnaît le graphe dessiné, pas la
 *       formule : éthanol et éther diméthylique), puis, quand le dessin se pose, la relaxe seule par GFN1-xTB et dit si
 *       elle tient, se réarrange ou se défait — les liaisons qui se rompraient sont tracées en rouge ;</li>
 *   <li>à l'envoi, le moteur pose chaque molécule en 3D d'après ses liaisons (longueurs des rayons covalents, angles
 *       d'après les liaisons π), à sa place relative sur le dessin, et la relaxe : tout le simulateur sert ensuite.</li>
 * </ul>
 * Gestes : cliquer dans le vide pose un atome ; tirer d'un atome vers un autre les lie, vers le vide pose un atome lié ;
 * cliquer un atome prolonge la chaîne (ou change son élément) ; cliquer une liaison la fait passer de simple à double
 * à triple. Avant de cliquer, l'écran montre ce qui se ferait (un atome fantôme, une liaison fantôme, rouge si les
 * valences la refusent) et une pastille dit l'atome survolé (ses liaisons sur sa valence, sa charge). L'outil
 * Sélection choisit des atomes (clic, Maj ou Ctrl pour en ajouter, un cadre tiré dans le vide, double-clic : toute la
 * molécule, Ctrl+A : tout) ; une barre au-dessus d'eux change leur charge, complète leurs H, les remet au propre, les
 * duplique ou les efface ; + − 0 et Suppr font de même au clavier. Clic droit : le menu de ce qui est dessous.
 * Molette : zoom ; tirer dans le vide : déplacer la vue (avec la Sélection : le cadre).
 */
final class MoleculeAssembler {
    interface Host {
        void build(double[] vector, int charge, boolean enclosed);
        void assess(int request, double[] vector, boolean deep);
        void close();
        void pickElement();
    }

    static final class Atom {
        int z, charge;
        double x, y;

        Atom(int z, double x, double y) {
            this.z = z;
            this.x = x;
            this.y = y;
        }
    }

    static final class Link {
        final int a, b;
        int order;

        Link(int a, int b, int order) {
            this.a = Math.min(a, b);
            this.b = Math.max(a, b);
            this.order = order;
        }
    }

    enum Tool { DRAW, SELECT, MOVE, ERASE, CHARGE }

    private static final int MAX_ATOMS = 40;
    /** Sous cet écart HOMO–LUMO (eV), une molécule a des couches presque ouvertes : GFN1 sans spin la décrit mal. */
    private static final double SMALL_GAP = .5;
    private static final int[] PALETTE = {1, 6, 7, 8, 9, 15, 16, 17, 35, 53, 5, 14};
    private static final Color BACK = Color.web("#060B15"), GRID = Color.web("#13203A"), LINE = Color.web("#C9D6E8");
    private static final Color GOOD = Color.web("#5BE39A"), WARN = Color.web("#F2C46B"), BAD = Color.web("#FF6F61"),
            GREY = Color.web("#8FA4C2"), TEXT = Color.web("#DCE8F7");

    private final Host host;
    private final BorderPane root = new BorderPane();
    private final Pane holder = new Pane();
    private final Canvas canvas = new Canvas();
    final List<Atom> atoms = new ArrayList<>();
    final List<Link> links = new ArrayList<>();
    private final Deque<String> undo = new ArrayDeque<>();
    private Tool tool = Tool.DRAW;
    private int element = 6, order = 1;
    private double scale = 46, panX = Double.NaN, panY = Double.NaN;
    // Le geste en cours.
    private int pressedAtom = -1, pressedLink = -1, hoverAtom = -1, hoverLink = -1;
    private double pressX, pressY, mouseX = Double.NaN, mouseY = Double.NaN;
    private boolean dragging, panning;
    private final List<double[]> movingStart = new ArrayList<>();
    /** Les atomes choisis (outil Sélection, Ctrl+A) : on les déplace, change leur charge, les efface ensemble. */
    final TreeSet<Integer> selection = new TreeSet<>();
    /** Le cadre de sélection en cours (son coin de départ, à l'écran) et la sélection qu'il complète (Maj, Ctrl). */
    private boolean banding;
    private double bandX, bandY;
    private final TreeSet<Integer> bandBase = new TreeSet<>();
    /** La barre d'actions posée au-dessus de la sélection. */
    private final HBox selectionBar = new HBox(4);
    private final Label selectionCount = MoleculeView.text("", 10.5, "#C6D4E8");
    private final CheckBox numbers = new CheckBox();
    private final Map<String, Cursor> cursors = new HashMap<>();
    private String message = "";
    private Color messageColour = GREY;
    private long messageAt;
    // Le calcul.
    private int revision;
    private MoleculeAssessment assessment;
    private final PauseTransition quickTimer = new PauseTransition(Duration.millis(220)),
            deepTimer = new PauseTransition(Duration.millis(1300));
    // Le dictionnaire, pour les modèles.
    private final List<MoleculeEntry> dictionary = new ArrayList<>();
    private final VBox templateList = new VBox(3), moleculeCards = new VBox(8);
    private final TextField search = new TextField();
    private final Label counter = MoleculeView.text("", 11, "#8FA4C2"), hint = MoleculeView.text("", 10.5, "#8FA4C2");
    private final CheckBox enclosed = new CheckBox();
    private final ToggleGroup elementGroup = new ToggleGroup(), toolGroup = new ToggleGroup(), orderGroup = new ToggleGroup();
    private final FlowPane elementBox = new FlowPane(4, 4);
    private final Button send = MoleculeView.button("Envoyer dans le simulateur 3D  ▶", true);

    MoleculeAssembler(Host host) {
        this.host = host;
        root.getStyleClass().addAll("fusion-lab", "molecule-lab");
        root.setStyle("-fx-background-color:#04070D;-fx-font-family:'Segoe UI';");
        holder.getChildren().add(canvas);
        selectionBar();
        holder.getChildren().add(selectionBar);
        holder.setMinSize(100, 100);
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> draw());
        canvas.heightProperty().addListener((o, a, b) -> draw());
        canvas.setFocusTraversable(true);
        canvas.setOnMousePressed(this::press);
        canvas.setOnMouseDragged(this::drag);
        canvas.setOnMouseReleased(this::release);
        canvas.setOnMouseMoved(this::move);
        canvas.setOnMouseExited(e -> {
            hoverAtom = hoverLink = -1;
            mouseX = mouseY = Double.NaN;
            draw();
        });
        canvas.setOnScroll(e -> {
            double factor = Math.exp(e.getDeltaY() / 400);
            double next = Math.max(18, Math.min(110, scale * factor));
            panX = e.getX() - (e.getX() - panX) * next / scale;
            panY = e.getY() - (e.getY() - panY) * next / scale;
            scale = next;
            draw();
        });
        canvas.setOnKeyPressed(e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.Z) {
                undo();
                return;
            }
            if (e.isShortcutDown() && e.getCode() == KeyCode.A) {
                selectAll();
                return;
            }
            if (e.isShortcutDown() && e.getCode() == KeyCode.D) {
                duplicate();
                return;
            }
            if (e.getCode() == KeyCode.ESCAPE) {
                selection.clear();
                banding = false;
                draw();
                return;
            }
            if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                if (!selection.isEmpty()) deleteSelection();
                else if (hoverAtom >= 0) removeAtom(hoverAtom);
                else if (hoverLink >= 0) removeLink(hoverLink);
                return;
            }
            // La charge des atomes choisis (ou de l'atome survolé) : + − 0.
            String typed = e.getText();
            if (typed.equals("+") || e.getCode() == KeyCode.ADD) {
                charge(targets(), 1);
                return;
            }
            if (typed.equals("-") || e.getCode() == KeyCode.SUBTRACT) {
                charge(targets(), -1);
                return;
            }
            if (typed.equals("0") || e.getCode() == KeyCode.NUMPAD0) {
                charge(targets(), 0);
                return;
            }
            String key = typed.toUpperCase(Locale.ROOT);
            if (key.equals("1") || key.equals("2") || key.equals("3")) selectOrder(Integer.parseInt(key));
            int z = switch (key) {
                case "H" -> 1; case "C" -> 6; case "N" -> 7; case "O" -> 8; case "F" -> 9; case "P" -> 15;
                case "S" -> 16; case "L" -> 17; case "B" -> 5; case "I" -> 53; default -> -1;
            };
            if (z > 0) selectElement(z);
        });
        quickTimer.setOnFinished(e -> host.assess(revision, vector(), false));
        deepTimer.setOnFinished(e -> host.assess(revision, vector(), true));
        root.setTop(topBar());
        root.setLeft(scroll(palette(), 236));
        root.setRight(scroll(readings(), 300));
        root.setCenter(holder);
        refreshPanels();
    }

    Region getNode() {
        return root;
    }

    // ================================================================== la mise en page
    private Region topBar() {
        Button back = MoleculeView.button("← Simulateur 3D", false);
        back.setOnAction(e -> host.close());
        Label title = MoleculeView.text("Assembleur moléculaire", 17, "#EAF2FF");
        Label sub = MoleculeView.text("dessiner ses molécules à plat, les vérifier par le calcul, puis les envoyer en 3D", 11.5,
                "#8FD8FF");
        VBox titles = new VBox(1, title, sub);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        send.setOnAction(e -> sendToLab());
        HBox bar = new HBox(14, back, titles, gap, counter, send);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 14, 10, 14));
        bar.setStyle("-fx-background-color:#060A14;");
        return bar;
    }

    private static ScrollPane scroll(VBox content, double width) {
        content.setPadding(new Insets(12, 10, 12, 10));
        content.setPrefWidth(width);
        ScrollPane pane = new ScrollPane(content);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pane.setPrefWidth(width + 14);
        pane.setStyle("-fx-background:#060A14;-fx-background-color:#060A14;");
        return pane;
    }

    private static VBox section(String index, String title, Node... nodes) {
        Label number = MoleculeView.text(index, 9.5, "#36CDF2"), name = MoleculeView.text(
                I18n.text(title).toUpperCase(I18n.locale()), 9.5, "#8FB3D9");
        number.setStyle(number.getStyle() + "-fx-font-weight:bold;");
        name.setStyle(name.getStyle() + "-fx-font-weight:bold;");
        HBox head = new HBox(9, number, name);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(7);
        box.getChildren().add(head);
        box.getChildren().addAll(nodes);
        box.getStyleClass().add("glass-section");
        return box;
    }

    private ToggleButton toggle(String label, ToggleGroup group, String tip) {
        ToggleButton b = new ToggleButton(label);
        b.getStyleClass().add("glass-toggle");
        b.setFocusTraversable(false);
        b.setToggleGroup(group);
        if (tip != null) b.setTooltip(I18n.tooltip(tip));
        return b;
    }

    private VBox palette() {
        FlowPane tools = new FlowPane(5, 5);
        String[][] toolNames = {{"Dessiner", "Cliquer : un atome · tirer d'un atome : une liaison · cliquer un atome : prolonger la chaîne"},
                {"Sélection", "Cliquer un atome le choisit (Maj ou Ctrl : en ajouter) · tirer dans le vide : un cadre · "
                        + "double-clic : toute la molécule · Ctrl+A : tout · tirer la sélection la déplace"},
                {"Glisser", "Tirer un atome le déplace ; tirer une liaison déplace toute la molécule"},
                {"Gomme", "Cliquer un atome ou une liaison l'efface (clic droit aussi, avec tous les outils)"},
                {"Charge ±", "Cliquer un atome : charge formelle 0 → +1 → −1"}};
        for (int k = 0; k < toolNames.length; k++) {
            ToggleButton b = toggle(toolNames[k][0], toolGroup, toolNames[k][1]);
            Tool t = Tool.values()[k];
            b.setOnAction(e -> {
                b.setSelected(true);
                setTool(t);
            });
            if (k == 0) b.setSelected(true);
            tools.getChildren().add(b);
        }
        FlowPane elements = elementBox;
        for (int z : PALETTE) {
            ToggleButton b = toggle(MoleculeView.symbolOf(z), elementGroup, null);
            b.setUserData(z);
            b.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-min-width:38;-fx-text-fill:"
                    + web(MoleculeScene.elementColor(z)) + ";");
            b.setOnAction(e -> selectElement(z));
            if (z == element) b.setSelected(true);
            elements.getChildren().add(b);
        }
        Button table = MoleculeView.button("Tableau périodique…", false);
        table.setOnAction(e -> host.pickElement());
        HBox orders = new HBox(5);
        String[] orderNames = {"— simple", "= double", "≡ triple"};
        for (int k = 0; k < 3; k++) {
            ToggleButton b = toggle(orderNames[k], orderGroup, null);
            int o = k + 1;
            b.setUserData(o);
            b.setOnAction(e -> selectOrder(o));
            if (k == 0) b.setSelected(true);
            orders.getChildren().add(b);
        }
        Button hydrogens = MoleculeView.button("Compléter les H", false);
        hydrogens.setTooltip(I18n.tooltip("Ajoute à chaque atome les hydrogènes de sa valence usuelle (C 4, N 3, O 2…)"));
        hydrogens.setOnAction(e -> completeHydrogens());
        Button tidy = MoleculeView.button("Mettre au propre", false);
        tidy.setTooltip(I18n.tooltip("Redessine chaque molécule comme dans un manuel : liaisons égales, cycles réguliers, 120°"));
        tidy.setOnAction(e -> tidyAll());
        Button cancel = MoleculeView.button("Annuler", false);
        cancel.setOnAction(e -> undo());
        Button wipe = MoleculeView.button("Tout effacer", false);
        wipe.setOnAction(e -> {
            remember();
            atoms.clear();
            links.clear();
            selection.clear();
            changed();
        });
        Button importScene = MoleculeView.button("Reprendre la scène 3D", false);
        importScene.setTooltip(I18n.tooltip("Les molécules du simulateur 3D, redessinées à plat pour les modifier"));
        importScene.setOnAction(e -> importScene());
        FlowPane drawing = new FlowPane(5, 5, hydrogens, tidy, cancel, wipe, importScene);
        numbers.setText(I18n.text("numéros des atomes"));
        numbers.setTooltip(I18n.tooltip("Le numéro de chaque atome, celui qu'il gardera dans le simulateur 3D (C1, O2…)"));
        numbers.setStyle("-fx-text-fill:#C6D4E8;-fx-font-size:11px;");
        numbers.selectedProperty().addListener((o, a, b) -> draw());
        search.setPromptText(I18n.text("chercher : eau, éthanol, benzène…"));
        search.setStyle("-fx-font-size:11.5px;-fx-font-weight:normal;");
        search.textProperty().addListener((o, a, b) -> refreshTemplates());
        hint.setWrapText(true);
        hint.setText(I18n.text("Cliquer dans le vide : un atome. Tirer d'un atome : une liaison. Cliquer une liaison : "
                + "simple, double, triple. Clic droit : le menu (charge, H, effacer). Sélection : un cadre, Maj ou Ctrl, "
                + "Ctrl+A ; + − 0 : la charge ; Suppr : effacer ; Ctrl+D : dupliquer. Molette : zoom. Ctrl+Z : annuler."));
        return new VBox(10,
                section("01", "Outils", tools),
                section("02", "Élément", elements, table),
                section("03", "Liaison", orders),
                section("04", "Dessin", drawing, numbers, hint),
                section("05", "Modèles du dictionnaire", search, templateList));
    }

    private VBox readings() {
        enclosed.setText(I18n.text("dans une enceinte (le réacteur)"));
        enclosed.setStyle("-fx-text-fill:#C6D4E8;-fx-font-size:11px;");
        enclosed.setTooltip(I18n.tooltip("Une sphère autour des molécules : elles restent ensemble et peuvent réagir"));
        Label how = MoleculeView.text("Chaque molécule est posée en 3D d'après ses liaisons (longueurs des rayons covalents, "
                + "angles d'après les liaisons π), à sa place sur le dessin, puis relaxée par GFN1-xTB : toutes les "
                + "options du simulateur servent ensuite.", 10.5, "#8FA4C2");
        how.setWrapText(true);
        return new VBox(10,
                section("06", "Molécules du dessin", moleculeCards),
                section("07", "Envoi", enclosed, how));
    }

    private static String web(Color c) {
        return String.format("#%02X%02X%02X", (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }

    // ================================================================== les règles
    /** La valence usuelle d'un atome (ses liaisons, comptées avec leur ordre), selon sa charge formelle. */
    static int usual(int z, int charge) {
        return switch (z) {
            case 1 -> charge == 0 ? 1 : 0;
            case 5 -> charge < 0 ? 4 : 3;
            case 6 -> charge == 0 ? 4 : 3;
            case 7 -> charge > 0 ? 4 : charge < 0 ? 2 : 3;
            case 8 -> charge > 0 ? 3 : charge < 0 ? 1 : 2;
            case 9, 17, 35, 53 -> charge == 0 ? 1 : charge > 0 ? 2 : 0;
            case 14 -> 4;
            case 15 -> charge > 0 ? 4 : 3;
            case 16 -> charge > 0 ? 3 : charge < 0 ? 1 : 2;
            case 2, 10, 18, 36, 54, 86 -> 0;
            case 3, 11, 19, 37, 55 -> charge == 0 ? 1 : 0;
            case 4, 12, 20, 38, 56 -> charge == 0 ? 2 : 1;
            case 13 -> 3;
            default -> 2;
        };
    }

    /** Ce qu'un atome peut porter au plus (P 5, S 6, I 7 : les hypervalents ; les métaux, largement). */
    static int maximum(int z, int charge) {
        return switch (z) {
            case 1 -> charge == 0 ? 1 : 0;
            case 2, 10 -> 0;
            case 5 -> 4;
            case 6 -> 4;
            case 7 -> 4;
            case 8 -> charge > 0 ? 3 : 2;
            case 9 -> 1;
            case 14 -> 6;
            case 15 -> 5;
            case 16 -> 6;
            case 17, 35 -> 7;
            case 53 -> 7;
            case 18 -> 0;
            case 36 -> 2;
            case 54 -> 8;
            default -> z <= 20 ? Math.max(usual(z, charge), 4) : 8;
        };
    }

    int used(int atom) {
        int sum = 0;
        for (Link l : links) if (l.a == atom || l.b == atom) sum += l.order;
        return sum;
    }

    /** Les électrons qui manquent à un atome pour sa valence usuelle (les points rouges : un radical). */
    int open(int atom) {
        Atom a = atoms.get(atom);
        return Math.max(0, usual(a.z, a.charge) - used(atom));
    }

    private boolean fits(int atom, int extra) {
        Atom a = atoms.get(atom);
        if (used(atom) + extra <= maximum(a.z, a.charge)) return true;
        say(name(atom) + " " + I18n.text("ne peut pas porter une liaison de plus") + " (" + I18n.text("au plus") + " "
                + maximum(a.z, a.charge) + ")", BAD);
        return false;
    }

    private String name(int atom) {
        return MoleculeView.symbolOf(atoms.get(atom).z) + (atom + 1);
    }

    private int linkBetween(int a, int b) {
        for (int k = 0; k < links.size(); k++) {
            Link l = links.get(k);
            if (l.a == Math.min(a, b) && l.b == Math.max(a, b)) return k;
        }
        return -1;
    }

    // ================================================================== les gestes
    private void selectElement(int z) {
        element = z;
        for (javafx.scene.control.Toggle t : elementGroup.getToggles()) {
            ToggleButton b = (ToggleButton) t;
            b.setSelected(Integer.valueOf(z).equals(b.getUserData()));
        }
        if (tool != Tool.DRAW) {
            ((ToggleButton) toolGroup.getToggles().get(0)).setSelected(true);
        }
        setTool(Tool.DRAW);
    }

    /** L'outil, et le curseur qui le montre (l'élément à poser, la gomme, la charge). */
    private void setTool(Tool next) {
        tool = next;
        if (next != Tool.SELECT && next != Tool.MOVE) selection.clear();
        canvas.setCursor(cursorFor(false));
        draw();
    }

    /**
     * Le curseur de l'outil : pour dessiner, un cercle de la couleur de l'élément avec son symbole (là où l'atome se
     * posera) ; la gomme, un cercle rouge barré ; la charge, ± ; glisser, la main ; choisir, la flèche (la main sur un
     * atome).
     */
    private Cursor cursorFor(boolean overAtom) {
        if (tool == Tool.MOVE) return overAtom ? Cursor.OPEN_HAND : Cursor.DEFAULT;
        if (tool == Tool.SELECT) return overAtom ? Cursor.HAND : Cursor.CROSSHAIR;
        String key = tool + ":" + element;
        return cursors.computeIfAbsent(key, k -> {
            try {
                Canvas c = new Canvas(32, 32);
                GraphicsContext g = c.getGraphicsContext2D();
                Color ring = tool == Tool.ERASE ? BAD : tool == Tool.CHARGE ? Color.web("#C9B8FF") : MoleculeScene.elementColor(element);
                g.setFill(Color.web("#060B15", .75));
                g.fillOval(6, 6, 20, 20);
                g.setStroke(ring);
                g.setLineWidth(1.6);
                g.strokeOval(6, 6, 20, 20);
                g.setStroke(Color.web("#FFFFFF", .8));
                g.setLineWidth(1);
                g.strokeLine(16, 0, 16, 4);
                g.strokeLine(16, 28, 16, 32);
                g.strokeLine(0, 16, 4, 16);
                g.strokeLine(28, 16, 32, 16);
                g.setTextAlign(TextAlignment.CENTER);
                g.setFont(Font.font("Segoe UI", FontWeight.BOLD, tool == Tool.DRAW && MoleculeView.symbolOf(element).length() > 1 ? 9 : 11));
                g.setFill(ring.deriveColor(0, .85, 1.3, 1));
                g.fillText(tool == Tool.ERASE ? "×" : tool == Tool.CHARGE ? "±" : MoleculeView.symbolOf(element), 16, 20);
                SnapshotParameters parameters = new SnapshotParameters();
                parameters.setFill(Color.TRANSPARENT);
                WritableImage image = c.snapshot(parameters, null);
                return new ImageCursor(image, 16, 16);
            } catch (RuntimeException failure) {
                return Cursor.CROSSHAIR;                      // sans curseur dessiné, la croix
            }
        });
    }

    /** Un élément choisi dans le tableau périodique. */
    void chooseElement(int z, String symbol) {
        boolean listed = false;
        for (javafx.scene.control.Toggle t : elementGroup.getToggles()) listed |= Integer.valueOf(z).equals(((ToggleButton) t).getUserData());
        if (!listed) {
            ToggleButton b = toggle(symbol, elementGroup, null);
            b.setUserData(z);
            b.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-min-width:38;-fx-text-fill:"
                    + web(MoleculeScene.elementColor(z)) + ";");
            b.setOnAction(e -> selectElement(z));
            elementBox.getChildren().add(b);
        }
        selectElement(z);
    }

    private void selectOrder(int o) {
        order = o;
        for (javafx.scene.control.Toggle t : orderGroup.getToggles()) {
            ToggleButton b = (ToggleButton) t;
            b.setSelected(Integer.valueOf(o).equals(b.getUserData()));
        }
    }

    private double sx(double x) {
        return x * scale + panX;
    }

    private double sy(double y) {
        return y * scale + panY;
    }

    private double wx(double x) {
        return (x - panX) / scale;
    }

    private double wy(double y) {
        return (y - panY) / scale;
    }

    int atomAt(double x, double y) {
        int best = -1;
        double distance = 15;
        for (int k = 0; k < atoms.size(); k++) {
            double d = Math.hypot(sx(atoms.get(k).x) - x, sy(atoms.get(k).y) - y);
            if (d < distance) {
                distance = d;
                best = k;
            }
        }
        return best;
    }

    int linkAt(double x, double y) {
        int best = -1;
        double distance = 7;
        for (int k = 0; k < links.size(); k++) {
            Atom a = atoms.get(links.get(k).a), b = atoms.get(links.get(k).b);
            double ax = sx(a.x), ay = sy(a.y), bx = sx(b.x), by = sy(b.y);
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len2));
            double d = Math.hypot(ax + t * dx - x, ay + t * dy - y);
            if (d < distance && t > .15 && t < .85) {
                distance = d;
                best = k;
            }
        }
        return best;
    }

    private void move(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
        int a = atomAt(mouseX, mouseY), l = a >= 0 ? -1 : linkAt(mouseX, mouseY);
        boolean changed = a != hoverAtom || l != hoverLink;
        hoverAtom = a;
        hoverLink = l;
        if (changed) canvas.setCursor(cursorFor(a >= 0 || l >= 0));
        // Le fantôme suit la souris quand on dessine ; sinon, seul un changement de survol se redessine.
        if (changed || tool == Tool.DRAW) draw();
    }

    private void press(MouseEvent e) {
        canvas.requestFocus();
        pressX = mouseX = e.getX();
        pressY = mouseY = e.getY();
        dragging = panning = false;
        pressedAtom = atomAt(pressX, pressY);
        pressedLink = pressedAtom >= 0 ? -1 : linkAt(pressX, pressY);
        if (e.getButton() == MouseButton.SECONDARY) {
            contextMenu(pressedAtom, pressedLink, e);
            pressedAtom = pressedLink = -1;
            return;
        }
        movingStart.clear();
        if (tool == Tool.SELECT) {
            boolean adding = e.isShiftDown() || e.isShortcutDown();
            if (pressedAtom >= 0 && e.getClickCount() == 2) {
                // Double-clic : toute la molécule de l'atome.
                if (!adding) selection.clear();
                selection.addAll(molecule(pressedAtom));
            } else if (pressedAtom >= 0) {
                if (adding) {
                    if (!selection.remove(pressedAtom)) selection.add(pressedAtom);
                } else if (!selection.contains(pressedAtom)) {
                    selection.clear();
                    selection.add(pressedAtom);
                }
            } else if (pressedLink >= 0) {
                Link l = links.get(pressedLink);
                if (!adding) selection.clear();
                selection.add(l.a);
                selection.add(l.b);
            } else {
                // Dans le vide : un cadre, qui remplace la sélection (ou la complète avec Maj, Ctrl).
                bandBase.clear();
                if (adding) bandBase.addAll(selection);
                else selection.clear();
                banding = true;
                bandX = pressX;
                bandY = pressY;
            }
            if ((pressedAtom >= 0 || pressedLink >= 0) && !selection.isEmpty()) {
                remember();
                for (Atom a : atoms) movingStart.add(new double[]{a.x, a.y});
            }
            draw();
            return;
        }
        if (tool == Tool.MOVE && (pressedAtom >= 0 || pressedLink >= 0)) {
            remember();
            for (Atom a : atoms) movingStart.add(new double[]{a.x, a.y});
        }
    }

    private void drag(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
        if (Math.hypot(mouseX - pressX, mouseY - pressY) > 4) dragging = true;
        if (!dragging) return;
        if (banding) {
            double x0 = Math.min(bandX, mouseX), x1 = Math.max(bandX, mouseX), y0 = Math.min(bandY, mouseY), y1 = Math.max(bandY, mouseY);
            selection.clear();
            selection.addAll(bandBase);
            for (int k = 0; k < atoms.size(); k++) {
                double x = sx(atoms.get(k).x), y = sy(atoms.get(k).y);
                if (x >= x0 && x <= x1 && y >= y0 && y <= y1) selection.add(k);
            }
            draw();
            return;
        }
        if (tool == Tool.SELECT && !movingStart.isEmpty()) {
            double dx = (mouseX - pressX) / scale, dy = (mouseY - pressY) / scale;
            for (int k : selection) {
                atoms.get(k).x = movingStart.get(k)[0] + dx;
                atoms.get(k).y = movingStart.get(k)[1] + dy;
            }
            draw();
            return;
        }
        if (pressedAtom < 0 && pressedLink < 0) {
            // Tirer dans le vide déplace la vue.
            panning = true;
            panX += e.getX() - pressX;
            panY += e.getY() - pressY;
            pressX = e.getX();
            pressY = e.getY();
            draw();
            return;
        }
        if (tool == Tool.MOVE && !movingStart.isEmpty()) {
            double dx = (mouseX - pressX) / scale, dy = (mouseY - pressY) / scale;
            List<Integer> moved = pressedAtom >= 0 ? List.of(pressedAtom) : molecule(links.get(pressedLink).a);
            for (int k : moved) {
                atoms.get(k).x = movingStart.get(k)[0] + dx;
                atoms.get(k).y = movingStart.get(k)[1] + dy;
            }
        }
        draw();
    }

    private void release(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        double x = e.getX(), y = e.getY();
        if (banding) {
            banding = false;
            draw();
            return;
        }
        if (panning) {
            panning = false;
            return;
        }
        if (tool == Tool.SELECT) {
            if (dragging && !movingStart.isEmpty()) changed();
            else if (!movingStart.isEmpty()) undo.pop();               // un simple clic : rien n'a bougé
            // Un clic (sans tirer) sur un atome d'une sélection : lui seul reste choisi.
            if (!dragging && pressedAtom >= 0 && e.getClickCount() < 2 && !(e.isShiftDown() || e.isShortcutDown())) {
                selection.clear();
                selection.add(pressedAtom);
            }
            movingStart.clear();
            draw();
            return;
        }
        if (tool == Tool.MOVE) {
            if (dragging && !movingStart.isEmpty()) changed();
            movingStart.clear();
            return;
        }
        if (tool == Tool.ERASE) {
            if (pressedAtom >= 0) removeAtom(pressedAtom);
            else if (pressedLink >= 0) removeLink(pressedLink);
            return;
        }
        if (tool == Tool.CHARGE) {
            if (pressedAtom >= 0) {
                Atom a = atoms.get(pressedAtom);
                charge(List.of(pressedAtom), nextCharge(a.charge));
            }
            return;
        }
        // Dessiner.
        if (dragging && pressedAtom >= 0) {
            int target = atomAt(x, y);
            if (target == pressedAtom) return;
            if (target >= 0) bond(pressedAtom, target, order);
            else addBonded(pressedAtom, wx(x), wy(y));
            return;
        }
        if (dragging) return;
        if (pressedLink >= 0) {
            cycleOrder(pressedLink);
            return;
        }
        if (pressedAtom >= 0) {
            Atom a = atoms.get(pressedAtom);
            if (a.z != element) {
                if (used(pressedAtom) > maximum(element, a.charge)) {
                    say(MoleculeView.symbolOf(element) + " " + I18n.text("ne peut pas porter les liaisons de") + " "
                            + name(pressedAtom), BAD);
                    return;
                }
                remember();
                a.z = element;
                changed();
            } else {
                double[] free = freeDirection(pressedAtom);
                addBonded(pressedAtom, a.x + free[0], a.y + free[1]);
            }
            return;
        }
        if (atoms.size() >= MAX_ATOMS) {
            say(I18n.text("Au plus 40 atomes : au-delà, le calcul ne suit plus en temps réel."), BAD);
            return;
        }
        remember();
        atoms.add(new Atom(element, wx(x), wy(y)));
        changed();
    }

    private void addBonded(int from, double x, double y) {
        if (atoms.size() >= MAX_ATOMS) {
            say(I18n.text("Au plus 40 atomes : au-delà, le calcul ne suit plus en temps réel."), BAD);
            return;
        }
        if (!fits(from, order)) return;
        if (maximum(element, 0) < order) {
            say(MoleculeView.symbolOf(element) + " " + I18n.text("ne peut pas porter cette liaison"), BAD);
            return;
        }
        remember();
        atoms.add(new Atom(element, x, y));
        links.add(new Link(from, atoms.size() - 1, order));
        changed();
    }

    private void bond(int a, int b, int wanted) {
        int existing = linkBetween(a, b);
        if (existing >= 0) {
            cycleOrder(existing);
            return;
        }
        if (!fits(a, wanted) || !fits(b, wanted)) return;
        remember();
        links.add(new Link(a, b, wanted));
        changed();
    }

    /** Simple → double → triple → simple ; un cran que les valences refusent est sauté (revenir à simple libère). */
    private void cycleOrder(int index) {
        Link l = links.get(index);
        Atom a = atoms.get(l.a), b = atoms.get(l.b);
        for (int step = 1; step <= 2; step++) {
            int next = (l.order - 1 + step) % 3 + 1, extra = next - l.order;
            if (used(l.a) + extra <= maximum(a.z, a.charge) && used(l.b) + extra <= maximum(b.z, b.charge)) {
                remember();
                l.order = next;
                changed();
                return;
            }
        }
        say(I18n.text("pas de liaison plus forte : les valences de") + " " + name(l.a) + " " + I18n.text("et") + " "
                + name(l.b) + " " + I18n.text("sont pleines"), BAD);
    }

    private void removeAtom(int index) {
        remember();
        drop(index);
        hoverAtom = hoverLink = -1;
        changed();
    }

    /** Retire un atome et ses liaisons ; les numéros d'après (liaisons, sélection) descendent d'un cran. */
    private void drop(int index) {
        atoms.remove(index);
        List<Link> kept = new ArrayList<>();
        for (Link l : links) {
            if (l.a == index || l.b == index) continue;
            kept.add(new Link(l.a > index ? l.a - 1 : l.a, l.b > index ? l.b - 1 : l.b, l.order));
        }
        links.clear();
        links.addAll(kept);
        TreeSet<Integer> shifted = new TreeSet<>();
        for (int k : selection) if (k != index) shifted.add(k > index ? k - 1 : k);
        selection.clear();
        selection.addAll(shifted);
    }

    // ================================================================== la sélection
    void selectAll() {
        selection.clear();
        for (int k = 0; k < atoms.size(); k++) selection.add(k);
        if (tool != Tool.SELECT && tool != Tool.MOVE) {
            ((ToggleButton) toolGroup.getToggles().get(1)).setSelected(true);
            tool = Tool.SELECT;
            canvas.setCursor(cursorFor(false));
        }
        draw();
    }

    /** Ce sur quoi agit le clavier : la sélection, sinon l'atome survolé. */
    private List<Integer> targets() {
        if (!selection.isEmpty()) return new ArrayList<>(selection);
        return hoverAtom >= 0 ? List.of(hoverAtom) : List.of();
    }

    /** La charge suivante de l'outil Charge : 0 → +1 → −1 → 0. */
    static int nextCharge(int charge) {
        return charge == 0 ? 1 : charge == 1 ? -1 : 0;
    }

    /**
     * Donne cette charge formelle à ces atomes ; un atome dont les liaisons ne tiendraient plus (un O⁻ à deux liaisons
     * déjà, un N⁻ à quatre) la refuse, et l'écran dit combien.
     */
    void charge(Collection<Integer> which, int charge) {
        if (which.isEmpty()) return;
        remember();
        int refused = 0, done = 0;
        for (int k : which) {
            Atom a = atoms.get(k);
            if (used(k) > maximum(a.z, charge)) {
                refused++;
                continue;
            }
            if (a.charge != charge) done++;
            a.charge = charge;
        }
        if (refused > 0) {
            say(refused == 1 && which.size() == 1 ? I18n.text("cette charge ne laisserait pas la place à ses liaisons")
                    : refused + " " + I18n.text("atomes gardent leur charge : leurs liaisons ne tiendraient plus"), BAD);
        } else if (which.size() > 1) {
            say(done + " " + I18n.text("charges changées"), GREY);
        }
        changed();
    }

    /** Efface les atomes choisis et leurs liaisons. */
    void deleteSelection() {
        if (selection.isEmpty()) return;
        remember();
        for (int k : new ArrayList<>(selection.descendingSet())) drop(k);
        selection.clear();
        hoverAtom = hoverLink = -1;
        changed();
    }

    /** Une copie des atomes choisis (et de leurs liaisons entre eux), un peu à côté ; la copie devient la sélection. */
    void duplicate() {
        if (selection.isEmpty()) return;
        if (atoms.size() + selection.size() > MAX_ATOMS) {
            say(I18n.text("Au plus 40 atomes : au-delà, le calcul ne suit plus en temps réel."), BAD);
            return;
        }
        remember();
        Map<Integer, Integer> copy = new HashMap<>();
        double maxX = -1e9, minX = 1e9;
        for (int k : selection) {
            maxX = Math.max(maxX, atoms.get(k).x);
            minX = Math.min(minX, atoms.get(k).x);
        }
        double shift = maxX - minX + 1.6;
        for (int k : selection) {
            Atom a = atoms.get(k), b = new Atom(a.z, a.x + shift, a.y + .4);
            b.charge = a.charge;
            copy.put(k, atoms.size());
            atoms.add(b);
        }
        for (Link l : new ArrayList<>(links)) {
            if (copy.containsKey(l.a) && copy.containsKey(l.b)) links.add(new Link(copy.get(l.a), copy.get(l.b), l.order));
        }
        selection.clear();
        selection.addAll(copy.values());
        changed();
    }

    /** La barre d'actions de la sélection : charge +1, −1, neutre ; compléter les H ; au propre ; dupliquer ; effacer. */
    private void selectionBar() {
        String[][] items = {{"+1", "Charge +1 pour les atomes choisis (touche +)"}, {"−1", "Charge −1 (touche −)"},
                {"0", "Neutres (touche 0)"}, {"+H", "Compléter leurs hydrogènes"},
                {"Au propre", "Redessiner leurs molécules comme dans un manuel"}, {"Dupliquer", "Une copie à côté (Ctrl+D)"},
                {"Effacer", "Effacer les atomes choisis (Suppr)"}};
        selectionBar.getChildren().add(selectionCount);
        for (int k = 0; k < items.length; k++) {
            Button b = MoleculeView.button(items[k][0], false);
            b.setTooltip(I18n.tooltip(items[k][1]));
            b.setStyle("-fx-font-size:11px;-fx-padding:3 8;");
            final int action = k;
            b.setOnAction(e -> {
                switch (action) {
                    case 0 -> charge(new ArrayList<>(selection), 1);
                    case 1 -> charge(new ArrayList<>(selection), -1);
                    case 2 -> charge(new ArrayList<>(selection), 0);
                    case 3 -> completeHydrogens(new ArrayList<>(selection));
                    case 4 -> tidySelection();
                    case 5 -> duplicate();
                    default -> deleteSelection();
                }
                canvas.requestFocus();
            });
            selectionBar.getChildren().add(b);
        }
        selectionBar.setAlignment(Pos.CENTER_LEFT);
        selectionBar.setPadding(new Insets(4, 6, 4, 8));
        selectionBar.setStyle("-fx-background-color:rgba(8,17,32,0.94);-fx-background-radius:9;-fx-border-color:#3A5070;"
                + "-fx-border-radius:9;");
        selectionBar.setManaged(false);
        selectionBar.setVisible(false);
    }

    /** La barre au-dessus des atomes choisis (au-dessous s'ils touchent le haut), dans le cadre. */
    private void placeBar() {
        boolean show = !selection.isEmpty() && !banding && !(dragging && !movingStart.isEmpty());
        selectionBar.setVisible(show);
        if (!show) return;
        double x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
        for (int k : selection) {
            if (k >= atoms.size()) continue;
            x0 = Math.min(x0, sx(atoms.get(k).x));
            x1 = Math.max(x1, sx(atoms.get(k).x));
            y0 = Math.min(y0, sy(atoms.get(k).y));
            y1 = Math.max(y1, sy(atoms.get(k).y));
        }
        selectionCount.setText(selection.size() + " " + I18n.text(selection.size() > 1 ? "atomes" : "atome"));
        selectionBar.applyCss();
        selectionBar.autosize();
        double w = selectionBar.getWidth(), h = selectionBar.getHeight();
        double x = Math.max(6, Math.min(canvas.getWidth() - w - 6, (x0 + x1) / 2 - w / 2));
        double y = y0 - 30 - h >= 4 ? y0 - 30 - h : Math.min(canvas.getHeight() - h - 6, y1 + 30);
        selectionBar.relocate(x, y);
    }

    /** Les molécules des atomes choisis, remises au propre. */
    private void tidySelection() {
        remember();
        java.util.Set<Integer> done = new java.util.HashSet<>();
        for (int k : selection) {
            if (done.contains(k)) continue;
            List<Integer> group = molecule(k);
            done.addAll(group);
            tidy(group);
        }
        changed();
    }

    /** Le menu du clic droit : ce qu'on peut faire à l'atome (ou à la sélection), à la liaison, ou au dessin. */
    private void contextMenu(int atom, int link, MouseEvent e) {
        List<MenuItem> items = new ArrayList<>();
        if (atom >= 0) {
            List<Integer> which = selection.contains(atom) && selection.size() > 1 ? new ArrayList<>(selection) : List.of(atom);
            String what = which.size() > 1 ? which.size() + " " + I18n.text("atomes") : name(atom);
            items.add(item(I18n.text("Charge +1") + " · " + what, () -> charge(which, 1)));
            items.add(item(I18n.text("Charge −1") + " · " + what, () -> charge(which, -1)));
            items.add(item(I18n.text("Neutre") + " · " + what, () -> charge(which, 0)));
            items.add(new SeparatorMenuItem());
            items.add(item(I18n.text("Compléter ses H"), () -> completeHydrogens(which)));
            items.add(item(I18n.text("Choisir toute la molécule"), () -> {
                selection.clear();
                selection.addAll(molecule(atom));
                if (tool != Tool.SELECT) {
                    ((ToggleButton) toolGroup.getToggles().get(1)).setSelected(true);
                    tool = Tool.SELECT;
                    canvas.setCursor(cursorFor(false));
                }
                draw();
            }));
            items.add(new SeparatorMenuItem());
            items.add(item(I18n.text("Effacer") + " · " + what, () -> {
                if (which.size() > 1) deleteSelection();
                else removeAtom(atom);
            }));
        } else if (link >= 0) {
            String[] names = {"Simple", "Double", "Triple"};
            for (int o = 1; o <= 3; o++) {
                final int order = o;
                items.add(item(I18n.text(names[o - 1]), () -> setOrder(link, order)));
            }
            items.add(new SeparatorMenuItem());
            items.add(item(I18n.text("Effacer la liaison"), () -> removeLink(link)));
        } else {
            items.add(item(I18n.text("Tout sélectionner") + "  (Ctrl+A)", this::selectAll));
            items.add(item(I18n.text("Mettre au propre"), this::tidyAll));
            items.add(item(I18n.text("Compléter les H"), this::completeHydrogens));
            items.add(item(I18n.text("Annuler") + "  (Ctrl+Z)", this::undo));
        }
        ContextMenu menu = new ContextMenu(items.toArray(new MenuItem[0]));
        menu.show(canvas, e.getScreenX(), e.getScreenY());
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }

    /** Une liaison mise à cet ordre, si les valences de ses deux atomes le permettent. */
    private void setOrder(int index, int wanted) {
        Link l = links.get(index);
        int extra = wanted - l.order;
        Atom a = atoms.get(l.a), b = atoms.get(l.b);
        if (used(l.a) + extra > maximum(a.z, a.charge) || used(l.b) + extra > maximum(b.z, b.charge)) {
            say(I18n.text("pas de liaison plus forte : les valences de") + " " + name(l.a) + " " + I18n.text("et") + " "
                    + name(l.b) + " " + I18n.text("sont pleines"), BAD);
            return;
        }
        remember();
        l.order = wanted;
        changed();
    }

    private void removeLink(int index) {
        remember();
        links.remove(index);
        hoverLink = -1;
        changed();
    }

    /** La direction (unité du dessin) d'un nouvel atome lié : en zigzag le long d'une chaîne, sinon le plus grand vide. */
    double[] freeDirection(int atom) {
        Atom a = atoms.get(atom);
        List<double[]> around = new ArrayList<>();
        for (Link l : links) {
            int other = l.a == atom ? l.b : l.b == atom ? l.a : -1;
            if (other >= 0) around.add(new double[]{atoms.get(other).x - a.x, atoms.get(other).y - a.y});
        }
        if (around.isEmpty()) return new double[]{Math.cos(Math.toRadians(-30)), Math.sin(Math.toRadians(-30))};
        if (around.size() == 1) {
            // Une chaîne : 120° de la liaison d'avant, du côté opposé à la liaison d'avant-avant (un zigzag).
            double angle = Math.atan2(around.get(0)[1], around.get(0)[0]);
            double[] left = {Math.cos(angle + 2 * Math.PI / 3), Math.sin(angle + 2 * Math.PI / 3)};
            double[] right = {Math.cos(angle - 2 * Math.PI / 3), Math.sin(angle - 2 * Math.PI / 3)};
            return crowd(atom, left) <= crowd(atom, right) ? left : right;
        }
        double[] angles = around.stream().mapToDouble(d -> Math.atan2(d[1], d[0])).sorted().toArray();
        double from = angles[angles.length - 1], span = angles[0] + 2 * Math.PI - from;
        for (int k = 1; k < angles.length; k++) {
            if (angles[k] - angles[k - 1] > span) {
                span = angles[k] - angles[k - 1];
                from = angles[k - 1];
            }
        }
        return new double[]{Math.cos(from + span / 2), Math.sin(from + span / 2)};
    }

    /** Combien d'atomes sont près de l'endroit où irait un nouvel atome (pour choisir le côté du zigzag). */
    private double crowd(int atom, double[] direction) {
        Atom a = atoms.get(atom);
        double x = a.x + direction[0], y = a.y + direction[1], sum = 0;
        for (Atom other : atoms) sum += 1 / (0.2 + Math.hypot(other.x - x, other.y - y));
        return sum;
    }

    /** Les atomes de la molécule de `atom` (reliés à lui). */
    List<Integer> molecule(int atom) {
        List<Integer> out = new ArrayList<>();
        boolean[] seen = new boolean[atoms.size()];
        Deque<Integer> stack = new ArrayDeque<>(List.of(atom));
        seen[atom] = true;
        while (!stack.isEmpty()) {
            int a = stack.pop();
            out.add(a);
            for (Link l : links) {
                int other = l.a == a ? l.b : l.b == a ? l.a : -1;
                if (other >= 0 && !seen[other]) {
                    seen[other] = true;
                    stack.push(other);
                }
            }
        }
        out.sort(Integer::compare);
        return out;
    }

    /** Les molécules du dessin, chacune triée, dans l'ordre de leur premier atome (comme le moteur). */
    List<List<Integer>> molecules() {
        List<List<Integer>> out = new ArrayList<>();
        boolean[] seen = new boolean[atoms.size()];
        for (int k = 0; k < atoms.size(); k++) {
            if (seen[k]) continue;
            List<Integer> group = molecule(k);
            for (int a : group) seen[a] = true;
            out.add(group);
        }
        return out;
    }

    // ================================================================== l'aide au dessin
    /** Chaque atome reçoit les hydrogènes de sa valence usuelle, dans ses plus grands vides. */
    void completeHydrogens() {
        List<Integer> all = new ArrayList<>();
        for (int k = 0; k < atoms.size(); k++) all.add(k);
        completeHydrogens(all);
    }

    /** Ces atomes (la sélection, un atome du menu) reçoivent les hydrogènes de leur valence usuelle. */
    void completeHydrogens(Collection<Integer> which) {
        remember();
        int added = 0;
        for (int k : new ArrayList<>(which)) {
            if (k >= atoms.size() || atoms.get(k).z == 1) continue;
            int missing = open(k);
            for (int h = 0; h < missing && atoms.size() < MAX_ATOMS; h++) {
                double[] d = freeDirection(k);
                Atom a = atoms.get(k);
                atoms.add(new Atom(1, a.x + .8 * d[0], a.y + .8 * d[1]));
                links.add(new Link(k, atoms.size() - 1, 1));
                added++;
            }
        }
        if (atoms.size() >= MAX_ATOMS) say(I18n.text("Au plus 40 atomes : au-delà, le calcul ne suit plus en temps réel."), BAD);
        else if (added == 0) say(I18n.text("Aucune valence libre : rien à compléter"), GREY);
        else say(added + " " + I18n.text(added == 1 ? "hydrogène ajouté" : "hydrogènes ajoutés"), GREY);
        changed();
    }

    /** Redessine chaque molécule comme dans un manuel, à sa place. */
    void tidyAll() {
        remember();
        for (List<Integer> group : molecules()) tidy(group);
        changed();
    }

    /** Une molécule redessinée : son squelette (atomes lourds) réglé par MoleculeSketch.layout, ses H autour. */
    private void tidy(List<Integer> group) {
        if (group.size() < 2) return;
        double cx = 0, cy = 0;
        for (int a : group) {
            cx += atoms.get(a).x / group.size();
            cy += atoms.get(a).y / group.size();
        }
        Map<Integer, Integer> index = new HashMap<>();
        for (int k = 0; k < group.size(); k++) index.put(group.get(k), k);
        int n = group.size();
        int[] z = new int[n];
        double[][] start = new double[n][];
        for (int k = 0; k < n; k++) {
            Atom a = atoms.get(group.get(k));
            z[k] = a.z;
            start[k] = new double[]{a.x, -a.y};
        }
        List<int[]> bonds = new ArrayList<>();
        for (Link l : links) if (index.containsKey(l.a)) bonds.add(new int[]{index.get(l.a), index.get(l.b), l.order});
        double[][] p = arrange(z, bonds, start);
        double px = 0, py = 0;
        for (double[] q : p) {
            px += q[0] / n;
            py += q[1] / n;
        }
        for (int k = 0; k < n; k++) {
            atoms.get(group.get(k)).x = cx + p[k][0] - px;
            atoms.get(group.get(k)).y = cy - (p[k][1] - py);
        }
    }

    /**
     * Une disposition de manuel (unité : une liaison ; y vers le haut) : le squelette des atomes lourds réglé par
     * contraintes depuis `start` (ou un arbre en zigzag, sans départ), puis chaque hydrogène dans le plus grand vide de
     * son atome. Une molécule à un seul atome lourd : ses H en étoile (deux en V, comme l'eau).
     */
    static double[][] arrange(int[] z, List<int[]> bonds, double[][] start) {
        int n = z.length;
        boolean[] heavy = new boolean[n];
        int heavyCount = 0;
        for (int k = 0; k < n; k++) {
            heavy[k] = z[k] > 1;
            if (heavy[k]) heavyCount++;
        }
        if (heavyCount == 0) for (int k = 0; k < n; k++) heavy[k] = true;
        List<List<Integer>> next = new ArrayList<>();
        for (int k = 0; k < n; k++) next.add(new ArrayList<>());
        for (int[] b : bonds) {
            next.get(b[0]).add(b[1]);
            next.get(b[1]).add(b[0]);
        }
        double[][] p = new double[n][];
        if (start == null) start = tree(n, next, heavy);
        List<int[]> skeleton = new ArrayList<>();
        for (int[] b : bonds) if (heavy[b[0]] && heavy[b[1]]) skeleton.add(new int[]{b[0], b[1], b.length > 2 ? b[2] : 1});
        if (skeleton.isEmpty()) {
            for (int k = 0; k < n; k++) p[k] = heavy[k] ? new double[]{0, 0} : null;
        } else {
            double[][] laid = MoleculeSketch.layout(start, heavy, skeleton);
            for (int k = 0; k < n; k++) p[k] = heavy[k] ? laid[k] : null;
        }
        // Les hydrogènes (ou ce qui reste à placer) autour de leur atome lourd.
        for (int k = 0; k < n; k++) {
            if (!heavy[k]) continue;
            List<Integer> waiting = new ArrayList<>(), placed = new ArrayList<>();
            for (int other : next.get(k)) (p[other] == null ? waiting : placed).add(other);
            if (waiting.isEmpty()) continue;
            final int centre = k;
            double[] angles = placed.stream().mapToDouble(o -> Math.atan2(p[o][1] - p[centre][1], p[o][0] - p[centre][0])).sorted().toArray();
            for (int w = 0; w < waiting.size(); w++) {
                double a;
                if (angles.length == 0) {
                    // Seul atome lourd : deux H en V (l'eau), trois en étoile, quatre en croix.
                    int m = waiting.size();
                    a = m == 2 ? Math.toRadians(-90 + (w == 0 ? -52 : 52)) : -Math.PI / 2 + 2 * Math.PI * w / m;
                } else {
                    double from = angles[angles.length - 1], span = angles[0] + 2 * Math.PI - from;
                    for (int q = 1; q < angles.length; q++) {
                        if (angles[q] - angles[q - 1] > span) {
                            span = angles[q] - angles[q - 1];
                            from = angles[q - 1];
                        }
                    }
                    a = from + span * (w + 1) / (waiting.size() + 1);
                }
                p[waiting.get(w)] = new double[]{p[k][0] + .8 * Math.cos(a), p[k][1] + .8 * Math.sin(a)};
            }
        }
        for (int k = 0; k < n; k++) if (p[k] == null) p[k] = new double[]{k, 0};
        return p;
    }

    /** Un départ en arbre : chaque atome lourd à 60° de part et d'autre de la direction d'où il vient (un zigzag). */
    private static double[][] tree(int n, List<List<Integer>> next, boolean[] heavy) {
        double[][] p = new double[n][];
        for (int root = 0; root < n; root++) {
            if (!heavy[root] || p[root] != null) continue;
            p[root] = new double[]{root * 3.0, 0};
            Deque<int[]> stack = new ArrayDeque<>();
            stack.push(new int[]{root, -1});
            while (!stack.isEmpty()) {
                int[] item = stack.pop();
                int at = item[0], from = item[1];
                double base = from < 0 ? 0 : Math.atan2(p[at][1] - p[from][1], p[at][0] - p[from][0]);
                int k = 0;
                for (int child : next.get(at)) {
                    if (!heavy[child] || p[child] != null) continue;
                    double turn = from < 0 ? 2 * Math.PI * k / Math.max(1, next.get(at).size())
                            : (k % 2 == 0 ? 1 : -1) * Math.PI / 3 * (1 + k / 2);
                    p[child] = new double[]{p[at][0] + Math.cos(base + turn), p[at][1] + Math.sin(base + turn)};
                    stack.push(new int[]{child, at});
                    k++;
                }
            }
        }
        for (int k = 0; k < n; k++) if (p[k] == null) p[k] = new double[]{k * .5, -1};
        return p;
    }

    /** Pose une molécule du dictionnaire dans le dessin, à droite de ce qui y est déjà. */
    void insert(MoleculeEntry entry) {
        int n = entry.getNumbersCount();
        if (atoms.size() + n > MAX_ATOMS) {
            say(I18n.text("Au plus 40 atomes : au-delà, le calcul ne suit plus en temps réel."), BAD);
            return;
        }
        int[] z = new int[n];
        for (int k = 0; k < n; k++) z[k] = entry.getNumbers(k);
        List<int[]> bonds = new ArrayList<>();
        for (int k = 0; k + 2 < entry.getBondsCount(); k += 3) {
            bonds.add(new int[]{entry.getBonds(k), entry.getBonds(k + 1), entry.getBonds(k + 2)});
        }
        double[][] start = null;
        if (entry.getXyCount() == 2 * n) {
            start = new double[n][];
            for (int k = 0; k < n; k++) start[k] = new double[]{entry.getXy(2 * k), entry.getXy(2 * k + 1)};
        }
        double[][] p = arrange(z, bonds, start);
        double minX = 1e9, maxX = -1e9, midY = 0;
        for (double[] q : p) {
            minX = Math.min(minX, q[0]);
            maxX = Math.max(maxX, q[0]);
            midY += q[1] / n;
        }
        double right = -1e9, centreY = 0;
        for (Atom a : atoms) right = Math.max(right, a.x);
        if (atoms.isEmpty()) {
            right = wx(canvas.getWidth() / 2) - (maxX - minX) / 2 - 1.5;
            centreY = wy(canvas.getHeight() / 2);
        } else {
            for (Atom a : atoms) centreY += a.y / atoms.size();
        }
        remember();
        int offset = atoms.size();
        for (int k = 0; k < n; k++) {
            Atom a = new Atom(z[k], right + 1.5 + p[k][0] - minX, centreY - (p[k][1] - midY));
            a.charge = k < entry.getChargesCount() ? entry.getCharges(k) : 0;
            atoms.add(a);
        }
        for (int[] b : bonds) links.add(new Link(b[0] + offset, b[1] + offset, b[2]));
        say(I18n.text(entry.getName()) + " " + I18n.text("ajouté au dessin"), GREY);
        changed();
    }

    /** Les molécules du simulateur 3D, redessinées à plat (vues dans leur plan moyen, puis mises au propre). */
    void importScene(MoleculeScene scene) {
        int[] all = new int[scene.positions.length];
        for (int k = 0; k < all.length; k++) all[k] = k;
        importAtoms(scene, all);
    }

    /** Ces atomes du simulateur 3D (une molécule choisie au clic droit), redessinés à plat à la place du dessin. */
    void importAtoms(MoleculeScene scene, int[] chosen) {
        if (scene.positions.length == 0 || chosen.length == 0) {
            say(I18n.text("le simulateur 3D est vide"), GREY);
            return;
        }
        remember();
        atoms.clear();
        links.clear();
        selection.clear();
        int n = Math.min(chosen.length, MAX_ATOMS);
        double[][] positions = new double[n][];
        Map<Integer, Integer> index = new HashMap<>();
        for (int k = 0; k < n; k++) {
            positions[k] = scene.positions[chosen[k]];
            index.put(chosen[k], k);
        }
        MoleculeSketch flat = new MoleculeSketch();
        flat.plane(positions, "import");
        double[][] xy = flat.flatten(positions);
        for (int k = 0; k < n; k++) atoms.add(new Atom(scene.z[chosen[k]], xy[k][0] / 1.4, -xy[k][1] / 1.4));
        for (MoleculeScene.Bond b : scene.bondedBonds()) {
            Integer a = index.get(b.a()), c = index.get(b.b());
            if (a != null && c != null) links.add(new Link(a, c, MoleculeScene.strokes(b.order())));
        }
        // Un ion garde sa charge : la somme de ses charges de Mulliken, arrondie dès 0,6 e (comme le dictionnaire),
        // posée sur son atome le plus chargé dans ce sens.
        for (List<Integer> group : molecules()) {
            double sum = 0;
            for (int k : group) sum += scene.charges[chosen[k]];
            if (Math.abs(sum) < .6) continue;
            int best = group.get(0);
            for (int k : group) if (Math.signum(sum) * scene.charges[chosen[k]] > Math.signum(sum) * scene.charges[chosen[best]]) best = k;
            atoms.get(best).charge = (int) Math.round(sum);
        }
        for (List<Integer> group : molecules()) tidy(group);
        // Les molécules à l'écart les unes des autres, de gauche à droite.
        double right = -1e9;
        for (List<Integer> group : molecules()) {
            double minX = 1e9;
            for (int a : group) minX = Math.min(minX, atoms.get(a).x);
            double shift = right == -1e9 ? 0 : Math.max(0, right + 1.5 - minX);
            double maxX = -1e9;
            for (int a : group) {
                atoms.get(a).x += shift;
                maxX = Math.max(maxX, atoms.get(a).x);
            }
            right = Math.max(right, maxX);
        }
        recentre();
        changed();
    }

    private Runnable importer = () -> {};

    void onImport(Runnable action) {
        importer = action;
    }

    private void importScene() {
        importer.run();
    }

    private void recentre() {
        if (atoms.isEmpty() || canvas.getWidth() < 2) return;
        double cx = 0, cy = 0;
        for (Atom a : atoms) {
            cx += a.x / atoms.size();
            cy += a.y / atoms.size();
        }
        panX = canvas.getWidth() / 2 - cx * scale;
        panY = canvas.getHeight() / 2 - cy * scale;
    }

    // ================================================================== l'état, l'annulation, le calcul
    private String serial() {
        StringBuilder b = new StringBuilder();
        for (Atom a : atoms) b.append(a.z).append(',').append(a.x).append(',').append(a.y).append(',').append(a.charge).append(';');
        b.append('|');
        for (Link l : links) b.append(l.a).append(',').append(l.b).append(',').append(l.order).append(';');
        return b.toString();
    }

    private void restore(String state) {
        atoms.clear();
        links.clear();
        String[] parts = state.split("\\|", -1);
        for (String s : parts[0].split(";")) {
            if (s.isEmpty()) continue;
            String[] v = s.split(",");
            Atom a = new Atom(Integer.parseInt(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]));
            a.charge = Integer.parseInt(v[3]);
            atoms.add(a);
        }
        if (parts.length > 1) {
            for (String s : parts[1].split(";")) {
                if (s.isEmpty()) continue;
                String[] v = s.split(",");
                links.add(new Link(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2])));
            }
        }
    }

    private void remember() {
        undo.push(serial());
        while (undo.size() > 80) undo.removeLast();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        restore(undo.pop());
        selection.removeIf(k -> k >= atoms.size());
        hoverAtom = hoverLink = -1;
        changed();
    }

    /** Aperçus : un outil, une sélection, l'atome survolé et la souris (à l'écran), comme pendant un geste. */
    void preview(int toolIndex, Collection<Integer> chosen, int hover, double x, double y) {
        ((ToggleButton) toolGroup.getToggles().get(toolIndex)).setSelected(true);
        tool = Tool.values()[toolIndex];
        selection.clear();
        selection.addAll(chosen);
        hoverAtom = hover;
        mouseX = x;
        mouseY = y;
        draw();
    }

    /** L'endroit à l'écran d'un atome du dessin (aperçus). */
    double[] screen(int atom) {
        return new double[]{sx(atoms.get(atom).x), sy(atoms.get(atom).y)};
    }

    /** Le dessin en nombres, pour le moteur : [n, (Z, x, y, charge) × n, m, (a, b, ordre) × m]. */
    double[] vector() {
        double[] v = new double[2 + 4 * atoms.size() + 3 * links.size()];
        int k = 0;
        v[k++] = atoms.size();
        for (Atom a : atoms) {
            v[k++] = a.z;
            v[k++] = a.x;
            v[k++] = a.y;
            v[k++] = a.charge;
        }
        v[k++] = links.size();
        for (Link l : links) {
            v[k++] = l.a;
            v[k++] = l.b;
            v[k++] = l.order;
        }
        return v;
    }

    /** Un dessin reçu tel quel (aperçus, tests) ; `request` : le numéro de la demande dont le verdict lui répond. */
    void load(double[] v, int request) {
        restore("");
        selection.clear();
        int n = (int) v[0];
        for (int k = 0; k < n; k++) {
            Atom a = new Atom((int) v[1 + 4 * k], v[2 + 4 * k], v[3 + 4 * k]);
            a.charge = (int) v[4 + 4 * k];
            atoms.add(a);
        }
        int m = (int) v[1 + 4 * n];
        for (int k = 0; k < m; k++) {
            int at = 2 + 4 * n + 3 * k;
            links.add(new Link((int) v[at], (int) v[at + 1], (int) v[at + 2]));
        }
        revision = request;
        recentre();
        refreshPanels();
        draw();
    }

    private void changed() {
        revision++;
        assessment = null;
        quickTimer.playFromStart();
        deepTimer.playFromStart();
        refreshPanels();
        draw();
    }

    private void say(String text, Color colour) {
        message = text;
        messageColour = colour;
        messageAt = System.nanoTime();
        draw();
    }

    private int totalCharge() {
        int q = 0;
        for (Atom a : atoms) q += a.charge;
        return q;
    }

    private void sendToLab() {
        if (atoms.isEmpty()) {
            say(I18n.text("le dessin est vide"), BAD);
            return;
        }
        if (Math.abs(totalCharge()) > 2) {
            say(I18n.text("le simulateur accepte une charge totale de −2 à +2"), BAD);
            return;
        }
        host.build(vector(), totalCharge(), enclosed.isSelected());
        host.close();
    }

    /** Ce que le moteur envoie : le dictionnaire (une fois), le verdict du dernier dessin. */
    void update(MoleculeLabState s) {
        if (dictionary.isEmpty() && s.getDictionaryCount() > 0) {
            dictionary.addAll(s.getDictionaryList());
            refreshTemplates();
        }
        if (s.hasAssessment()) {
            MoleculeAssessment a = s.getAssessment();
            if (a.getRequest() == revision && (assessment == null || assessment != a && (a.getDeep() || !assessment.getDeep()))) {
                assessment = a;
                refreshPanels();
                draw();
            }
        }
    }

    /** Le verdict de la molécule dont `atom` fait partie, s'il répond au dessin actuel. */
    private MoleculeVerdict verdictOf(int atom) {
        if (assessment == null || assessment.getRequest() != revision) return null;
        for (MoleculeVerdict v : assessment.getMoleculesList()) if (v.getAtomsList().contains(atom)) return v;
        return null;
    }

    // ================================================================== les panneaux
    private void refreshTemplates() {
        templateList.getChildren().clear();
        String query = fold(search.getText());
        int shown = 0;
        for (MoleculeEntry entry : dictionary) {
            // Sans recherche : les molécules d'abord ; les atomes seuls, les ions et les radicaux se cherchent.
            String plain = entry.getName();
            boolean special = entry.getNumbersCount() < 2 || plain.startsWith("ion ") || plain.startsWith("radical ")
                    || plain.startsWith("atome ") || plain.equals("proton");
            if (special && query.isEmpty()) continue;
            String name = I18n.text(entry.getName());
            if (!query.isEmpty() && !fold(name).contains(query) && !fold(entry.getName()).contains(query)
                    && !fold(entry.getFormula()).contains(query)) continue;
            if (shown++ >= (query.isEmpty() ? 24 : 40)) break;
            Button b = MoleculeView.button(entry.getFormula() + "  ·  " + name, false);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setStyle("-fx-font-size:11px;");
            b.setOnAction(e -> insert(entry));
            templateList.getChildren().add(b);
        }
        if (shown == 0 && !dictionary.isEmpty()) templateList.getChildren().add(MoleculeView.text(I18n.text("rien de tel"), 10.5, "#8FA4C2"));
    }

    private static String fold(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace("₀", "0").replace("₁", "1").replace("₂", "2").replace("₃", "3")
                .replace("₄", "4").replace("₅", "5").replace("₆", "6").replace("₇", "7").replace("₈", "8").replace("₉", "9");
    }

    private void refreshPanels() {
        counter.setText(atoms.size() + " / " + MAX_ATOMS + " " + I18n.text("atomes")
                + (totalCharge() != 0 ? "  ·  " + I18n.text("charge") + " " + MoleculeFormat.signed(totalCharge(), 0) : ""));
        send.setDisable(atoms.isEmpty());
        moleculeCards.getChildren().clear();
        List<List<Integer>> groups = molecules();
        if (groups.isEmpty()) {
            Label empty = MoleculeView.text("Le dessin est vide : choisir un élément et cliquer dans le cadre, ou poser un "
                    + "modèle du dictionnaire.", 11, "#8FA4C2");
            empty.setWrapText(true);
            moleculeCards.getChildren().add(empty);
            return;
        }
        for (List<Integer> group : groups) moleculeCards.getChildren().add(card(group));
    }

    /** La fiche d'une molécule : son nom, ses valences libres, puis ce que le calcul en dit. */
    private VBox card(List<Integer> group) {
        MoleculeVerdict v = verdictOf(group.get(0));
        Map<Integer, Integer> counts = new java.util.TreeMap<>();
        for (int a : group) counts.merge(atoms.get(a).z, 1, Integer::sum);
        String formula = v != null ? v.getFormula() : hill(counts);
        String name = v != null && !v.getName().isEmpty() ? I18n.text(v.getName()) : v != null
                ? I18n.text("inconnue du dictionnaire") : "…";
        Label title = MoleculeView.text(formula + "  ·  " + name, 12.5, v != null && !v.getName().isEmpty() ? "#EAF2FF" : "#C6D4E8");
        title.setStyle(title.getStyle() + "-fx-font-weight:bold;");
        title.setWrapText(true);
        VBox box = new VBox(4, title);
        List<String> notes = new ArrayList<>();
        int radicals = 0;
        for (int a : group) {
            int open = open(a);
            if (open > 0) notes.add(name(a) + (I18n.language() == I18n.Language.FR ? " : " : ": ") + open + " "
                    + I18n.text(open > 1 ? "valences libres" : "valence libre"));
            radicals += open;
        }
        if (v != null && v.getUnpaired() == 1) notes.add(I18n.text("nombre impair d'électrons : un radical"));
        for (String note : notes) {
            Label l = MoleculeView.text("• " + note, 10.5, "#F2C46B");
            l.setWrapText(true);
            box.getChildren().add(l);
        }
        if (radicals > 0 && (v == null || v.getVerdict().isEmpty())) {
            Label l = MoleculeView.text(I18n.text("« Compléter les H » donne à chaque atome sa valence usuelle."), 10, "#8FA4C2");
            l.setWrapText(true);
            box.getChildren().add(l);
        }
        String verdict = v == null ? "" : v.getVerdict();
        Label line;
        if (v == null || (!assessment.getDeep() && verdict.isEmpty())) {
            line = MoleculeView.text(I18n.text("calcul GFN1-xTB en cours…"), 10.5, "#8FA4C2");
        } else if (verdict.equals("tient")) {
            line = MoleculeView.text("✔ " + I18n.text("tient : un minimum de GFN1-xTB") + "  ·  " + I18n.text("cohésion") + " "
                    + MoleculeFormat.decimal(v.getCohesionEv(), 2) + " eV  ·  " + I18n.text("écart") + " "
                    + MoleculeFormat.decimal(v.getGapEv(), 2) + " eV", 10.5, web(GOOD));
        } else if (verdict.equals("se réarrange")) {
            line = MoleculeView.text("↻ " + I18n.text("se réarrange à la relaxation") + " : " + pairs(v.getLostList(), "rompue")
                    + pairs(v.getGainedList(), "formée"), 10.5, web(WARN));
        } else if (verdict.equals("se défait")) {
            line = MoleculeView.text("✖ " + I18n.text("se défait à la relaxation, en") + " " + String.join(" + ",
                    v.getPiecesList().stream().map(MoleculeFormat::names).toList()), 10.5, web(BAD));
        } else if (verdict.equals("atome")) {
            line = MoleculeView.text(I18n.text("un atome seul"), 10.5, "#8FA4C2");
        } else {
            line = MoleculeView.text("✖ " + I18n.text("le calcul n'a pas trouvé d'état électronique sensé"), 10.5, web(BAD));
        }
        line.setWrapText(true);
        box.getChildren().add(line);
        if ((verdict.equals("tient") || verdict.equals("se réarrange")) && v.getGapEv() < SMALL_GAP) {
            // Un écart presque nul : des électrons presque célibataires (radical, biradical), ce que GFN1, sans spin,
            // décrit mal — le verdict est moins sûr.
            Label l = MoleculeView.text("⚠ " + I18n.text("écart presque nul : couches presque ouvertes, que GFN1 (sans spin) "
                    + "décrit mal ; le verdict est moins sûr"), 10, web(WARN));
            l.setWrapText(true);
            box.getChildren().add(l);
        }
        box.setStyle("-fx-background-color:rgba(10,22,38,0.7);-fx-background-radius:8;-fx-padding:8;");
        return box;
    }

    private String pairs(List<Integer> flat, String what) {
        if (flat.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (int k = 0; k + 1 < flat.size(); k += 2) {
            if (b.length() > 0) b.append(", ");
            b.append(name(flat.get(k))).append('–').append(name(flat.get(k + 1)));
        }
        return b + " " + I18n.text(what) + "  ";
    }

    static String hill(Map<Integer, Integer> counts) {
        StringBuilder b = new StringBuilder();
        List<Integer> order = new ArrayList<>();
        if (counts.containsKey(6)) {
            order.add(6);
            if (counts.containsKey(1)) order.add(1);
        }
        counts.keySet().stream().filter(z -> !order.contains(z)).sorted((x, y) -> MoleculeView.symbolOf(x)
                .compareTo(MoleculeView.symbolOf(y))).forEach(order::add);
        for (int z : order) {
            b.append(MoleculeView.symbolOf(z));
            int c = counts.get(z);
            if (c > 1) for (char ch : String.valueOf(c).toCharArray()) b.append("₀₁₂₃₄₅₆₇₈₉".charAt(ch - '0'));
        }
        return b.toString();
    }

    // ================================================================== le dessin
    void draw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w < 2 || h < 2) return;
        if (Double.isNaN(panX)) {
            panX = w / 2;
            panY = h / 2;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(new RadialGradient(0, 0, w * .5, h * .45, Math.max(w, h) * .7, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0C1A2E")), new Stop(1, BACK)));
        g.fillRect(0, 0, w, h);
        // La grille : un point par unité de liaison.
        g.setFill(GRID);
        double step = scale;
        for (double x = ((panX % step) + step) % step; x < w; x += step) {
            for (double y = ((panY % step) + step) % step; y < h; y += step) g.fillOval(x - 1, y - 1, 2, 2);
        }
        g.setLineCap(StrokeLineCap.ROUND);
        // Les liaisons ; celles que le calcul romprait, en rouge ; celles qu'il formerait, en tirets verts.
        java.util.Set<Long> lost = new java.util.HashSet<>(), gained = new java.util.HashSet<>();
        if (assessment != null && assessment.getRequest() == revision) {
            for (MoleculeVerdict v : assessment.getMoleculesList()) {
                for (int k = 0; k + 1 < v.getLostCount(); k += 2) lost.add(key(v.getLost(k), v.getLost(k + 1)));
                for (int k = 0; k + 1 < v.getGainedCount(); k += 2) gained.add(key(v.getGained(k), v.getGained(k + 1)));
            }
        }
        for (int k = 0; k < links.size(); k++) {
            Link l = links.get(k);
            Atom a = atoms.get(l.a), b = atoms.get(l.b);
            boolean chosen = selection.contains(l.a) && selection.contains(l.b);
            Color colour = lost.contains(key(l.a, l.b)) ? BAD : k == hoverLink || chosen ? Color.web("#FFE07A") : LINE;
            strokes(g, sx(a.x), sy(a.y), sx(b.x), sy(b.y), l.order, colour, radius(a), radius(b));
        }
        for (long pair : gained) {
            int x = (int) (pair / 10_000), y = (int) (pair % 10_000);
            if (x >= atoms.size() || y >= atoms.size()) continue;
            g.setStroke(GOOD.deriveColor(0, 1, 1, .8));
            g.setLineWidth(1.4);
            g.setLineDashes(4, 4);
            g.strokeLine(sx(atoms.get(x).x), sy(atoms.get(x).y), sx(atoms.get(y).x), sy(atoms.get(y).y));
            g.setLineDashes();
        }
        // La liaison qu'on tire : verte si les valences l'acceptent, rouge sinon ; au bout, l'atome qui naîtrait.
        if (dragging && tool == Tool.DRAW && pressedAtom >= 0) {
            Atom a = atoms.get(pressedAtom);
            int target = atomAt(mouseX, mouseY);
            boolean possible = target >= 0 && target != pressedAtom
                    ? linkBetween(pressedAtom, target) >= 0 || (allows(pressedAtom, order) && allows(target, order))
                    : allows(pressedAtom, order) && maximum(element, 0) >= order;
            g.setStroke((possible ? Color.web("#FFE07A") : BAD).deriveColor(0, 1, 1, .85));
            g.setLineWidth(1.6);
            g.setLineDashes(5, 4);
            g.strokeLine(sx(a.x), sy(a.y), mouseX, mouseY);
            g.setLineDashes();
            if (target < 0) ghost(g, mouseX, mouseY, element, possible);
        }
        // Avant de cliquer : l'atome qui se poserait, la liaison qui partirait de l'atome survolé.
        if (!dragging && tool == Tool.DRAW && !Double.isNaN(mouseX)) {
            if (hoverAtom < 0 && hoverLink < 0) {
                ghost(g, mouseX, mouseY, element, atoms.size() < MAX_ATOMS);
            } else if (hoverAtom >= 0 && atoms.get(hoverAtom).z == element) {
                Atom a = atoms.get(hoverAtom);
                double[] free = freeDirection(hoverAtom);
                boolean possible = allows(hoverAtom, order) && maximum(element, 0) >= order && atoms.size() < MAX_ATOMS;
                double gx = sx(a.x + free[0]), gy = sy(a.y + free[1]);
                g.setLineDashes(4, 4);
                strokes(g, sx(a.x), sy(a.y), gx, gy, order, (possible ? LINE : BAD).deriveColor(0, 1, 1, .45), radius(a), 9);
                g.setLineDashes();
                ghost(g, gx, gy, element, possible);
            }
        }
        // Les atomes.
        for (int k = 0; k < atoms.size(); k++) atom(g, k);
        // Le cadre de sélection.
        if (banding && dragging) {
            double x0 = Math.min(bandX, mouseX), y0 = Math.min(bandY, mouseY);
            g.setFill(Color.web("#FFE07A", .07));
            g.fillRect(x0, y0, Math.abs(mouseX - bandX), Math.abs(mouseY - bandY));
            g.setStroke(Color.web("#FFE07A", .7));
            g.setLineWidth(1);
            g.setLineDashes(5, 4);
            g.strokeRect(x0, y0, Math.abs(mouseX - bandX), Math.abs(mouseY - bandY));
            g.setLineDashes();
        }
        // Le nom de chaque molécule, sous elle.
        for (List<Integer> group : molecules()) caption(g, group);
        // Le message du dernier geste refusé (ou fait), quelques secondes.
        if (!message.isEmpty() && System.nanoTime() - messageAt < 6_000_000_000L) {
            g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12));
            g.setTextAlign(TextAlignment.CENTER);
            g.setFill(Color.web("#081120", .9));
            double width = message.length() * 6.6 + 24;
            g.fillRoundRect(w / 2 - width / 2, h - 44, width, 26, 13, 13);
            g.setFill(messageColour);
            g.fillText(message, w / 2, h - 26);
        }
        if (hoverAtom >= 0 && hoverAtom < atoms.size() && !dragging) chip(g, hoverAtom);
        placeBar();
        if (atoms.isEmpty()) {
            g.setFont(Font.font("Segoe UI", 14));
            g.setTextAlign(TextAlignment.CENTER);
            g.setFill(GREY);
            g.fillText(I18n.text("Choisir un élément à gauche, puis cliquer ici pour poser un atome."), w / 2, h / 2 - 10);
            g.fillText(I18n.text("Tirer d'un atome pour le lier à un autre, ou poser un modèle du dictionnaire."), w / 2, h / 2 + 12);
        }
    }

    private static long key(int a, int b) {
        return Math.min(a, b) * 10_000L + Math.max(a, b);
    }

    private double radius(Atom a) {
        return (a.z == 1 ? 9 : 12.5) * Math.min(1.25, Math.max(.7, scale / 46));
    }

    private void strokes(GraphicsContext g, double x0, double y0, double x1, double y1, int n, Color colour, double cutA, double cutB) {
        double dx = x1 - x0, dy = y1 - y0, len = Math.hypot(dx, dy);
        if (len < 1) return;
        double ux = dx / len, uy = dy / len, gap = 3.6 * Math.min(1.3, scale / 46);
        for (int k = 0; k < n; k++) {
            double offset = n == 1 ? 0 : n == 2 ? (k - .5) * 2 * gap : (k - 1) * 2 * gap;
            g.setStroke(colour);
            g.setLineWidth(1.8);
            g.strokeLine(x0 + ux * cutA - uy * offset, y0 + uy * cutA + ux * offset, x1 - ux * cutB - uy * offset,
                    y1 - uy * cutB + ux * offset);
        }
    }

    /** Les valences d'un atome acceptent-elles `extra` liaisons de plus ? (sans le dire : pour les aperçus) */
    private boolean allows(int atom, int extra) {
        Atom a = atoms.get(atom);
        return used(atom) + extra <= maximum(a.z, a.charge);
    }

    /** Un atome fantôme : là où un clic le poserait ; rouge si les valences le refusent. */
    private void ghost(GraphicsContext g, double x, double y, int z, boolean possible) {
        double r = (z == 1 ? 9 : 12.5) * Math.min(1.25, Math.max(.7, scale / 46));
        Color colour = possible ? MoleculeScene.elementColor(z) : BAD;
        g.setFill(colour.deriveColor(0, 1, 1, .1));
        g.fillOval(x - r, y - r, 2 * r, 2 * r);
        g.setStroke(colour.deriveColor(0, 1, 1, .6));
        g.setLineWidth(1.2);
        g.setLineDashes(3, 3);
        g.strokeOval(x - r, y - r, 2 * r, 2 * r);
        g.setLineDashes();
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, z == 1 ? 11 : 13.5));
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(colour.deriveColor(0, 1, 1, .55));
        g.fillText(possible ? MoleculeView.symbolOf(z) : "×", x, y + (z == 1 ? 4 : 5));
    }

    /**
     * La pastille de l'atome survolé : son nom, ses liaisons sur sa valence usuelle (et le maximum), sa charge, et ce
     * qu'un clic ferait avec l'outil en main.
     */
    private void chip(GraphicsContext g, int k) {
        Atom a = atoms.get(k);
        int used = used(k), usual = usual(a.z, a.charge), most = maximum(a.z, a.charge);
        String charge = a.charge == 0 ? I18n.text("neutre") : I18n.text("charge") + " " + MoleculeFormat.signed(a.charge, 0);
        String head = name(k) + "  ·  " + I18n.text("liaisons") + " " + used + "/" + usual
                + (most > usual ? " (" + I18n.text("au plus") + " " + most + ")" : "") + "  ·  " + charge;
        String next = switch (tool) {
            case DRAW -> a.z != element ? I18n.text("clic : devient") + " " + MoleculeView.symbolOf(element)
                    : allows(k, order) ? I18n.text("clic : lier un nouvel atome") + " " + MoleculeView.symbolOf(element)
                    : I18n.text("valence pleine : plus de liaison");
            case CHARGE -> I18n.text("clic : charge") + " " + MoleculeFormat.signed(nextCharge(a.charge), 0)
                    + (used > maximum(a.z, nextCharge(a.charge)) ? "  (" + I18n.text("refusée") + ")" : "");
            case ERASE -> I18n.text("clic : effacer");
            case SELECT -> I18n.text("double-clic : toute la molécule");
            default -> I18n.text("tirer : le déplacer");
        };
        Font font = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11), small = Font.font("Segoe UI", 10);
        double w = Math.max(MoleculeSketch.width(head, font), MoleculeSketch.width(next, small)) + 20;
        double r = radius(a), x = sx(a.x) + r + 10, y = sy(a.y) - r - 40;
        if (x + w > canvas.getWidth() - 6) x = sx(a.x) - r - 10 - w;
        if (y < 6) y = sy(a.y) + r + 10;
        g.setFill(Color.web("#081120", .93));
        g.fillRoundRect(x, y, w, 34, 9, 9);
        g.setStroke(Color.web("#3A5070"));
        g.setLineWidth(.8);
        g.strokeRoundRect(x, y, w, 34, 9, 9);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(font);
        g.setFill(TEXT);
        g.fillText(head, x + 10, y + 14);
        g.setFont(small);
        g.setFill(tool == Tool.DRAW && a.z == element && !allows(k, order) ? BAD : GREY);
        g.fillText(next, x + 10, y + 28);
    }

    private void atom(GraphicsContext g, int k) {
        Atom a = atoms.get(k);
        double x = sx(a.x), y = sy(a.y), r = radius(a);
        Color element = MoleculeScene.elementColor(a.z);
        if (selection.contains(k)) {
            g.setFill(Color.web("#FFE07A", .14));
            g.fillOval(x - r - 6, y - r - 6, 2 * r + 12, 2 * r + 12);
            g.setStroke(Color.web("#FFE07A", .85));
            g.setLineWidth(1.4);
            g.strokeOval(x - r - 6, y - r - 6, 2 * r + 12, 2 * r + 12);
        }
        g.setFill(new RadialGradient(0, 0, x - r * .3, y - r * .3, r * 1.3, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#1A2C46")), new Stop(1, Color.web("#0A1322"))));
        g.fillOval(x - r, y - r, 2 * r, 2 * r);
        g.setStroke(k == hoverAtom ? Color.web("#FFE07A") : element.deriveColor(0, 1, 1, .9));
        g.setLineWidth(k == hoverAtom ? 2 : 1.4);
        g.strokeOval(x - r, y - r, 2 * r, 2 * r);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, a.z == 1 ? 11 : 13.5));
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(element.deriveColor(0, .85, 1.3, 1));
        g.fillText(MoleculeView.symbolOf(a.z), x, y + (a.z == 1 ? 4 : 5));
        if (a.charge != 0) {
            // La charge formelle : une pastille cyan (+) ou magenta (−) en haut à droite, comme un exposant.
            double bx = x + r * .8, by = y - r * .8, br = 6.5;
            g.setFill(a.charge > 0 ? MoleculeScene.POOR : MoleculeScene.RICH);
            g.fillOval(bx - br, by - br, 2 * br, 2 * br);
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
            g.setFill(Color.web("#06101E"));
            g.fillText((Math.abs(a.charge) > 1 ? String.valueOf(Math.abs(a.charge)) : "") + (a.charge > 0 ? "+" : "−"), bx, by + 3.6);
        }
        if (numbers.isSelected()) {
            g.setFont(Font.font("Segoe UI", 9));
            g.setFill(GREY);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(String.valueOf(k + 1), x + r * .75, y + r + 9);
            g.setTextAlign(TextAlignment.CENTER);
        }
        // Les valences libres : un point rouge par électron qui manque, dans les vides autour de l'atome.
        int open = open(k);
        if (open > 0) {
            double[] d = freeDirection(k);
            double base = Math.atan2(d[1], d[0]);
            for (int q = 0; q < open; q++) {
                double angle = base + (q - (open - 1) / 2.0) * .55;
                double px = x + Math.cos(angle) * (r + 5), py = y + Math.sin(angle) * (r + 5);
                g.setFill(BAD.deriveColor(0, 1, 1, .25));
                g.fillOval(px - 4, py - 4, 8, 8);
                g.setFill(BAD);
                g.fillOval(px - 2, py - 2, 4, 4);
            }
        }
    }

    private void caption(GraphicsContext g, List<Integer> group) {
        double minX = 1e9, maxX = -1e9, maxY = -1e9;
        for (int a : group) {
            minX = Math.min(minX, sx(atoms.get(a).x));
            maxX = Math.max(maxX, sx(atoms.get(a).x));
            maxY = Math.max(maxY, sy(atoms.get(a).y));
        }
        MoleculeVerdict v = verdictOf(group.get(0));
        String text = v == null ? "" : v.getFormula() + (v.getName().isEmpty() ? "" : "  ·  " + I18n.text(v.getName()));
        if (text.isEmpty()) return;
        String verdict = v.getVerdict();
        Color colour = verdict.equals("tient") ? GOOD : verdict.equals("se réarrange") ? WARN
                : verdict.equals("se défait") || verdict.equals("échec") ? BAD : GREY;
        double x = (minX + maxX) / 2, y = maxY + radius(atoms.get(group.get(0))) + 22;
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11.5));
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web("#081120", .85));
        double width = text.length() * 6.4 + 34;
        g.fillRoundRect(x - width / 2, y - 14, width, 20, 10, 10);
        g.setFill(colour);
        g.fillOval(x - width / 2 + 8, y - 7.5, 7, 7);
        g.setFill(TEXT);
        g.fillText(text, x + 6, y);
    }
}
