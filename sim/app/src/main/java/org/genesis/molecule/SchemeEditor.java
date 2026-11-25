package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeLabState;
import com.studio.genesis.grpc.MoleculeScheme;
import com.studio.genesis.grpc.MoleculeSchemeDrawing;
import com.studio.genesis.grpc.MoleculeSchemeStep;
import org.genesis.app.I18n;
import org.genesis.common.Icons;
import org.genesis.common.Plan;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * L'éditeur de schémas réactionnels : on dessine comme un chimiste (formule squelettique, hydrogènes implicites, cycles,
 * abréviations Me, Ph, OMe…), on écrit les réactifs au-dessus des flèches (MeI, CsF, Co₂(CO)₈…) et on peut tracer les
 * flèches courbes des électrons. Le moteur (moleculelab/scheme.py) compile le dessin : il découpe les étapes, dit quel
 * atome devient quel atome (par les flèches courbes, sinon par la plus grande sous-structure commune), déduit les
 * sous-produits, puis calcule par GFN1-xTB le chemin 3D de chaque étape. Chaque étape se rejoue ensuite dans sa vue
 * 2.5D de lecture (SchemeStage : légende, courbes image par image, liaisons qui changent), d'où l'on peut passer à la
 * scène 3D du laboratoire ; le profil d'énergie de tout le schéma s'affiche ici.
 *
 * <p>Gestes : outil Liaison — tirer d'un atome (ou du vide) pose une liaison de longueur fixe, orientée par pas de
 * 30° ; cliquer un atome lui ajoute un voisin ; cliquer une liaison la fait passer de simple à double à triple. Survoler
 * un atome et taper C, N, O, S, F, P, I, B, H, L (Cl), R (Br) change son élément ; + et − sa charge. Cycles : dans le
 * vide, sur un atome (cycle accroché) ou sur une liaison (cycle accolé). Flèche : tirer de la queue à la pointe, puis
 * écrire au-dessus et au-dessous (double-clic pour réécrire). Flèche courbe : tirer d'un atome (son doublet) ou d'une
 * liaison vers un atome ou une liaison. Clic droit ou molette : déplacer et zoomer la vue ; Suppr : effacer ce qui est
 * survolé ; Ctrl+Z : annuler.
 */
final class SchemeEditor {
    interface Host {
        void compute(int request, String json);
        void smiles(int request, String text);
        void play(int step);
        void close();
    }

    // ------------------------------------------------------------------ le dessin
    static final class Atom {
        int z, charge;
        double x, y;
        String label;

        Atom(int z, double x, double y) {
            this.z = z;
            this.x = x;
            this.y = y;
        }

        Atom copy() {
            Atom a = new Atom(z, x, y);
            a.charge = charge;
            a.label = label;
            return a;
        }
    }

    static final class Bond {
        int a, b, order;

        Bond(int a, int b, int order) {
            this.a = a;
            this.b = b;
            this.order = order;
        }
    }

    static final class Arrow {
        double tx, ty, hx, hy;
        String above = "", below = "";
    }

    static final class Curly {
        int[] from, to;

        Curly(int[] from, int[] to) {
            this.from = from;
            this.to = to;
        }
    }

    static final class Doc {
        final List<Atom> atoms = new ArrayList<>();
        final List<Bond> bonds = new ArrayList<>();
        final List<Arrow> arrows = new ArrayList<>();
        final List<Curly> curly = new ArrayList<>();

        Doc copy() {
            Doc d = new Doc();
            for (Atom a : atoms) d.atoms.add(a.copy());
            for (Bond b : bonds) d.bonds.add(new Bond(b.a, b.b, b.order));
            for (Arrow r : arrows) {
                Arrow c = new Arrow();
                c.tx = r.tx; c.ty = r.ty; c.hx = r.hx; c.hy = r.hy; c.above = r.above; c.below = r.below;
                d.arrows.add(c);
            }
            for (Curly c : curly) d.curly.add(new Curly(c.from.clone(), c.to.clone()));
            return d;
        }
    }

    enum Tool { BOND, RING, ATOM, CHARGE, GROUP, ARROW, CURLY, MOVE, ERASE }

    private static final String[] GROUPS = {"Me", "Et", "iPr", "tBu", "Ph", "Bn", "OMe", "OAc", "TMS", "Ac", "CO2Me",
            "CO2H", "CN", "NO2", "CF3", "NMe2", "Ts", "Boc"};
    private static final int[] ELEMENTS = {6, 7, 8, 16, 15, 9, 17, 35, 53, 14, 5, 1};
    private static final Color BACK = Color.web("#0C0E12"), DOT = Color.web("#1A1E25"), INK = Color.web("#E4E8EE"),
            INK2 = Color.web("#A2ABB8"), INK3 = Color.web("#6B7483"), ACCENT = Color.web("#4FD1A9"),
            CURLY = Color.web("#F2C14E"), GOOD = Color.web("#6CD09A"), BAD = Color.web("#F2766B"),
            REACTANT = Color.web("#7CC4FF"), PRODUCT = Color.web("#4FD1A9");

    private final Host host;
    private final BorderPane root = new BorderPane();
    private final Pane holder = new Pane();
    private final Canvas canvas = new Canvas();
    private Doc doc = new Doc();
    private final Deque<Doc> undo = new ArrayDeque<>();
    private Tool tool = Tool.BOND;
    private int order = 1, element = 7, ring = 6, chargeSign = 1;
    private boolean aromatic = true;
    private String group = "Ph";
    private double scale = 34, panX = Double.NaN, panY = Double.NaN;
    // Le geste.
    private int hoverAtom = -1, hoverBond = -1, hoverArrow = -1, hoverCurly = -1, pressAtom = -1, pressBond = -1,
            pressArrow = -1;
    private int arrowEnd;                        // 0 : le corps, 1 : la queue, 2 : la pointe
    private double pressX, pressY, mouseX = Double.NaN, mouseY = Double.NaN, lastX, lastY;
    private boolean dragging, panning, moved;
    private final List<double[]> moveStart = new ArrayList<>();
    private final List<Integer> moving = new ArrayList<>();
    private String message = "";
    private Color messageColour = INK2;
    // Le calcul.
    private int request, smilesRequest;
    private MoleculeScheme scheme;
    private int highlight = -1;
    private final VBox results = new VBox(12);
    private final Label status = Plan.note("Dessinez un schéma, ou ouvrez un exemple, puis Calculer.");
    private final ProgressBar progress = new ProgressBar(0);
    /** L'écran de calcul en direct (la géométrie en cours, le profil qui se remplit, le journal), sur le dessin. */
    private final SchemeConsole console = new SchemeConsole();
    private final Button journalButton = Plan.quiet("Journal du calcul");
    private boolean wasBusy;
    /** Un calcul est demandé et pas encore fini : le dessin est verrouillé (Arrêter le libère). */
    private boolean computing;
    private final Button clear = Plan.quiet("Effacer tout");
    private ScrollPane palette;
    private Canvas profile;
    private final Button computeButton = Plan.primary("Calculer le schéma");
    private final Button cancel = Plan.quiet("Arrêter");
    private final MenuButton examples = new MenuButton("Exemples");
    private final TextField smiles = new TextField();
    private final VBox editor = new VBox(6);
    private final ToggleGroup tools = new ToggleGroup();
    private int examplesCount;

    SchemeEditor(Host host) {
        this.host = host;
        root.getStyleClass().addAll("fusion-lab", "molecule-lab", "plan-scene");
        holder.getChildren().add(canvas);
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
            hoverAtom = hoverBond = hoverArrow = hoverCurly = -1;
            mouseX = mouseY = Double.NaN;
            draw();
        });
        canvas.setOnScroll(e -> {
            double next = Math.max(12, Math.min(90, scale * Math.exp(e.getDeltaY() / 400)));
            panX = e.getX() - (e.getX() - panX) * next / scale;
            panY = e.getY() - (e.getY() - panY) * next / scale;
            scale = next;
            draw();
        });
        canvas.setOnKeyPressed(this::key);
        // Un fichier de ChemDraw ou de MarvinSketch glissé sur le dessin.
        canvas.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && !locked()) e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            e.consume();
        });
        canvas.setOnDragDropped(e -> {
            boolean done = false;
            if (e.getDragboard().hasFiles() && !e.getDragboard().getFiles().isEmpty()) done = importFile(e.getDragboard().getFiles().get(0));
            e.setDropCompleted(done);
            e.consume();
        });
        editor.setVisible(false);
        editor.setManaged(false);
        holder.getChildren().add(editor);
        Region screen = console.getNode();
        screen.setLayoutX(14);
        screen.prefWidthProperty().bind(holder.widthProperty().subtract(28));
        screen.layoutYProperty().bind(holder.heightProperty().subtract(SchemeConsole.HEIGHT + 14));
        holder.getChildren().add(screen);
        root.setTop(topBar());
        palette = side(palette(), true);
        root.setLeft(palette);
        root.setRight(side(readings(), false));
        root.setCenter(holder);
    }

    Region getNode() {
        return root;
    }

    /** L'horloge de la vue (MoleculeView.tick) : l'écran de calcul s'anime. */
    void tick(double dt) {
        console.tick(dt);
    }

    // ================================================================== l'interface
    private Region topBar() {
        Button back = Plan.icon("chevron-left", "Simulateur 3D", "Revenir à la scène 3D du laboratoire");
        back.setOnAction(e -> host.close());
        Label title = new Label("Schéma de réaction");
        title.getStyleClass().add("plan-heading");
        Label sub = Plan.note("Dessiner les étapes comme sur papier : Genesis les calcule et rejoue leur chemin");
        VBox titles = new VBox(1, title, sub);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        examples.getStyleClass().add("secondary");
        clear.setOnAction(e -> {
            remember();
            doc = new Doc();
            scheme = null;
            refreshResults();
            draw();
        });
        Button fit = Plan.quiet("Tout voir");
        fit.setOnAction(e -> fit());
        computeButton.setOnAction(e -> compute());
        cancel.setOnAction(e -> {
            host.compute(-1, "");
            scheme = null;
            computing = false;
            console.hide();
            refreshResults();
        });
        cancel.setVisible(false);
        cancel.setManaged(false);
        HBox bar = new HBox(12, back, titles, gap, examples, fit, clear, cancel, computeButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 14, 10, 14));
        bar.getStyleClass().add("plan-bar");
        return bar;
    }

    private static ScrollPane side(VBox content, boolean left) {
        content.setPadding(new Insets(14, 16, 14, 16));
        ScrollPane pane = new ScrollPane(content);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        double width = left ? Plan.LEFT_WIDTH - 20 : Plan.RIGHT_WIDTH + 20;
        pane.setPrefWidth(width);
        pane.setMinWidth(width);
        pane.setMaxWidth(width);
        pane.getStyleClass().addAll("plan-side", left ? "left" : "right");
        return pane;
    }

    private ToggleButton toolButton(String text, Tool t, String tip) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(tools);
        b.getStyleClass().add("glass-toggle");
        b.setTooltip(I18n.tooltip(tip));
        b.setFocusTraversable(false);
        b.setOnAction(e -> {
            b.setSelected(true);
            tool = t;
            canvas.requestFocus();
            draw();
        });
        b.setUserData(t);
        return b;
    }

    private VBox palette() {
        FlowPane drawTools = new FlowPane(5, 5);
        ToggleButton bondTool = toolButton("Liaison", Tool.BOND, "Tirer d'un atome ou du vide : une liaison ; cliquer une liaison : simple → double → triple");
        bondTool.setSelected(true);
        drawTools.getChildren().addAll(bondTool,
                toolButton("Cycle", Tool.RING, "Dans le vide : un cycle ; sur un atome : accroché ; sur une liaison : accolé"),
                toolButton("Élément", Tool.ATOM, "Cliquer un atome change son élément (ou survoler et taper C, N, O, S…)"),
                toolButton("Charge", Tool.CHARGE, "Clic : +1 ; clic droit : −1 (ou survoler et taper + ou −)"),
                toolButton("Groupe", Tool.GROUP, "Une abréviation (Me, Ph, OMe, TMS…) à la place d'un atome"),
                toolButton("Déplacer", Tool.MOVE, "Tirer une molécule ou une flèche ; Maj : un seul atome"),
                toolButton("Gomme", Tool.ERASE, "Cliquer un atome, une liaison, une flèche"));
        HBox orders = segment(new String[]{"Simple", "Double", "Triple"}, 0, k -> order = k + 1);
        HBox rings = segment(new String[]{"3", "4", "5", "6", "7", "Benzène"}, 5, k -> {
            ring = k == 5 ? 6 : k + 3;
            aromatic = k == 5;
            selectTool(Tool.RING);
        });
        FlowPane elements = new FlowPane(4, 4);
        ToggleGroup elementGroup = new ToggleGroup();
        for (int z : ELEMENTS) {
            ToggleButton b = new ToggleButton(MoleculeView.symbolOf(z));
            b.setToggleGroup(elementGroup);
            b.getStyleClass().add("glass-toggle");
            b.setFocusTraversable(false);
            b.setMinWidth(34);
            if (z == element) b.setSelected(true);
            b.setOnAction(e -> {
                b.setSelected(true);
                element = z;
                selectTool(Tool.ATOM);
            });
            elements.getChildren().add(b);
        }
        FlowPane groups = new FlowPane(4, 4);
        ToggleGroup groupGroup = new ToggleGroup();
        for (String g : GROUPS) {
            ToggleButton b = new ToggleButton(g);
            b.setToggleGroup(groupGroup);
            b.getStyleClass().add("glass-toggle");
            b.setFocusTraversable(false);
            if (g.equals(group)) b.setSelected(true);
            b.setOnAction(e -> {
                b.setSelected(true);
                group = g;
                selectTool(Tool.GROUP);
            });
            groups.getChildren().add(b);
        }
        FlowPane arrowTools = new FlowPane(5, 5,
                toolButton("Flèche", Tool.ARROW, "Tirer de la queue à la pointe, puis écrire les réactifs au-dessus "
                        + "(MeI, CsF, Co2(CO)8, un nom, un SMILES) et les conditions au-dessous"),
                toolButton("Flèche courbe", Tool.CURLY, "Une paire d'électrons : tirer d'un atome (son doublet) ou d'une "
                        + "liaison vers un atome, une liaison, ou l'atome avec lequel une liaison se forme"));
        smiles.setPromptText("SMILES, ex. C=CC(=O)OC");
        smiles.setOnAction(e -> addSmiles());
        Button add = Plan.button("Ajouter");
        add.setOnAction(e -> addSmiles());
        HBox.setHgrow(smiles, Priority.ALWAYS);
        HBox smilesRow = new HBox(6, smiles, add);
        // ChemDraw, MarvinSketch : coller (« Copier comme MOL, SMILES, CDXML… ») ou ouvrir leurs fichiers.
        Button paste = Plan.button("Coller");
        paste.setTooltip(I18n.tooltip("Coller ce que ChemDraw ou MarvinSketch a copié (Ctrl+V sur le dessin) : MOL, SDF, "
                + "RXN, CDXML, MRV, SMILES ou SMILES de réaction"));
        paste.setOnAction(e -> pasteStructures());
        Button open = Plan.button("Ouvrir un fichier…");
        open.setTooltip(I18n.tooltip("Un fichier de ChemDraw ou de MarvinSketch (.cdxml, .mrv, .mol, .sdf, .rxn, .smi) : ses "
                + "molécules et ses flèches s'ajoutent au dessin — on peut aussi le glisser sur le dessin"));
        open.setOnAction(e -> openStructures());
        HBox importRow = new HBox(6, paste, open);
        Label help = Plan.note("Les hydrogènes sont implicites. Survoler un atome puis taper C N O S F P I B H, "
                + "L (Cl), R (Br) ; + et − pour la charge ; Suppr efface ; Ctrl+Z annule. Clic droit tiré : déplacer la vue.");
        help.setWrapText(true);
        return new VBox(16,
                Plan.section("Dessiner", drawTools, orders),
                Plan.section("Cycles", rings),
                Plan.section("Éléments", elements),
                Plan.section("Flèches", arrowTools),
                Plan.foldable("Groupes abrégés", false, groups),
                Plan.section("Depuis un SMILES ou un autre logiciel", smilesRow, importRow),
                help);
    }

    private void selectTool(Tool t) {
        tool = t;
        tools.getToggles().forEach(x -> x.setSelected(x.getUserData() == t));
        canvas.requestFocus();
        draw();
    }

    private static HBox segment(String[] labels, int selected, java.util.function.IntConsumer chosen) {
        ToggleGroup g = new ToggleGroup();
        ToggleButton[] buttons = new ToggleButton[labels.length];
        for (int k = 0; k < labels.length; k++) {
            ToggleButton b = new ToggleButton(labels[k]);
            b.setToggleGroup(g);
            b.setFocusTraversable(false);
            final int index = k;
            b.setOnAction(e -> {
                b.setSelected(true);
                chosen.accept(index);
            });
            buttons[k] = b;
        }
        buttons[selected].setSelected(true);
        return Plan.segmented(true, buttons);
    }

    private VBox readings() {
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);
        status.setWrapText(true);
        // Deux lignes réservées : un état plus long ne pousse pas les cartes sous la souris.
        status.setMinHeight(34);
        status.setPrefHeight(34);
        status.setAlignment(Pos.TOP_LEFT);
        journalButton.setTooltip(I18n.tooltip("Revoir l'écran du calcul : chaque relaxation, chaque palier du chemin, leurs énergies"));
        journalButton.setOnAction(e -> {
            console.showJournal(!console.journalShown());
            journalButton.setText(I18n.text(console.journalShown() ? "Replier le journal" : "Journal du calcul"));
        });
        journalButton.setVisible(false);
        journalButton.setManaged(false);
        return new VBox(14, Plan.section("Le calcul", status, progress, console.journalNode(), journalButton), results);
    }

    // ================================================================== le moteur
    void update(MoleculeLabState s, MoleculeScheme merged) {
        if (s.getSchemeExamplesCount() > examplesCount) {
            examplesCount = s.getSchemeExamplesCount();
            examples.getItems().clear();
            for (MoleculeSchemeDrawing d : s.getSchemeExamplesList()) {
                MenuItem item = new MenuItem(I18n.text(d.getTitle()));
                item.setOnAction(e -> load(d));
                examples.getItems().add(item);
            }
        }
        if (s.hasSmiles() && s.getSmiles().getRequest() == smilesRequest && smilesRequest > 0) {
            smilesRequest = 0;
            if (!s.getSmiles().getError().isEmpty()) {
                say(s.getSmiles().getError(), BAD);
            } else {
                remember();
                double[] box = bounds();
                Doc added = fromDrawing(s.getSmiles().getDrawing(), 0, 0);
                double minX = added.atoms.stream().mapToDouble(a -> a.x).min().orElse(0);
                double dx = box == null ? wx(canvas.getWidth() / 2) : box[2] + 2.5 - minX;
                double dy = box == null ? wy(canvas.getHeight() / 2) : (box[1] + box[3]) / 2;
                int base = doc.atoms.size();
                for (Atom a : added.atoms) {
                    a.x += dx;
                    a.y += dy;
                    doc.atoms.add(a);
                }
                for (Bond b : added.bonds) doc.bonds.add(new Bond(b.a + base, b.b + base, b.order));
                // Un schéma importé (RXN, CDXML, MRV, SMILES de réaction) : ses flèches et leurs textes aussi.
                for (Arrow r : added.arrows) {
                    r.tx += dx;
                    r.hx += dx;
                    r.ty += dy;
                    r.hy += dy;
                    doc.arrows.add(r);
                }
                for (Curly c : added.curly) {
                    int[] from = c.from.clone(), to = c.to.clone();
                    for (int k = 0; k < from.length; k++) from[k] += base;
                    for (int k = 0; k < to.length; k++) to[k] += base;
                    doc.curly.add(new Curly(from, to));
                }
                smiles.clear();
                String kind = s.getSmiles().getDrawing().getTitle();
                String from = kind.isEmpty() || kind.equals("SMILES") ? "" : " · " + kind;
                if (added.arrows.isEmpty()) {
                    say(I18n.text(components(added) > 1 ? "Molécules ajoutées" : "Molécule ajoutée") + from, GOOD);
                } else {
                    say(I18n.text("Schéma importé") + from + " · " + added.arrows.size() + " "
                            + I18n.text(added.arrows.size() > 1 ? "flèches" : "flèche"), GOOD);
                    fit();
                }
                draw();
            }
        }
        if (merged != null && merged.getRequest() == request && request > 0 && request != discarded && merged != scheme) {
            MoleculeScheme before = scheme;
            scheme = merged;
            if (scheme.getDone()) computing = false;
            console.update(scheme);
            refreshStatus();
            // Les cartes (et leurs boutons) ne sont refaites que si les étapes changent : un clic sur « Voir le
            // chemin » ou sur « Limites et remarques » n'est plus perdu par une carte refaite entre l'appui et le
            // relâchement (le moteur publie toutes les 0,15 s pendant le calcul).
            if (before == null || before.getDone() != scheme.getDone() || !sameSteps(before, scheme)
                    || !before.getError().equals(scheme.getError())) {
                refreshCards();
            }
            draw();
        }
    }

    /** Un schéma tout fait remplace le dessin (Ctrl+Z le rend). */
    void load(MoleculeSchemeDrawing d) {
        remember();
        doc = fromDrawing(d, 0, 0);
        discard();
        refreshResults();
        fit();
    }

    private static Doc fromDrawing(MoleculeSchemeDrawing d, double dx, double dy) {
        Doc out = new Doc();
        for (int k = 0; k + 3 < d.getAtomsCount(); k += 4) {
            Atom a = new Atom((int) d.getAtoms(k), d.getAtoms(k + 1) + dx, d.getAtoms(k + 2) + dy);
            a.charge = (int) d.getAtoms(k + 3);
            String label = k / 4 < d.getLabelsCount() ? d.getLabels(k / 4) : "";
            a.label = label.isEmpty() ? null : label;
            out.atoms.add(a);
        }
        for (int k = 0; k + 2 < d.getBondsCount(); k += 3) out.bonds.add(new Bond(d.getBonds(k), d.getBonds(k + 1), d.getBonds(k + 2)));
        for (int k = 0; k + 3 < d.getArrowsCount(); k += 4) {
            Arrow r = new Arrow();
            r.tx = d.getArrows(k) + dx; r.ty = d.getArrows(k + 1) + dy; r.hx = d.getArrows(k + 2) + dx; r.hy = d.getArrows(k + 3) + dy;
            r.above = k / 4 < d.getAboveCount() ? d.getAbove(k / 4) : "";
            r.below = k / 4 < d.getBelowCount() ? d.getBelow(k / 4) : "";
            out.arrows.add(r);
        }
        for (int k = 0; k + 3 < d.getCurlyCount(); k += 4) {
            int[] from = d.getCurly(k + 1) < 0 ? new int[]{d.getCurly(k)} : new int[]{d.getCurly(k), d.getCurly(k + 1)};
            int[] to = d.getCurly(k + 3) < 0 ? new int[]{d.getCurly(k + 2)} : new int[]{d.getCurly(k + 2), d.getCurly(k + 3)};
            out.curly.add(new Curly(from, to));
        }
        return out;
    }

    /** Le dessin en JSON, tel que scheme.py le lit. */
    String json() {
        StringBuilder s = new StringBuilder("{\"atoms\":[");
        for (int k = 0; k < doc.atoms.size(); k++) {
            Atom a = doc.atoms.get(k);
            if (k > 0) s.append(',');
            s.append('[').append(a.z).append(',').append(num(a.x)).append(',').append(num(a.y)).append(',').append(a.charge)
                    .append(',').append(a.label == null ? "null" : quote(a.label)).append(']');
        }
        s.append("],\"bonds\":[");
        for (int k = 0; k < doc.bonds.size(); k++) {
            Bond b = doc.bonds.get(k);
            if (k > 0) s.append(',');
            s.append('[').append(b.a).append(',').append(b.b).append(',').append(b.order).append(']');
        }
        s.append("],\"arrows\":[");
        for (int k = 0; k < doc.arrows.size(); k++) {
            Arrow r = doc.arrows.get(k);
            if (k > 0) s.append(',');
            s.append("{\"tail\":[").append(num(r.tx)).append(',').append(num(r.ty)).append("],\"head\":[")
                    .append(num(r.hx)).append(',').append(num(r.hy)).append("],\"above\":").append(quote(r.above))
                    .append(",\"below\":").append(quote(r.below)).append('}');
        }
        s.append("],\"curly\":[");
        for (int k = 0; k < doc.curly.size(); k++) {
            Curly c = doc.curly.get(k);
            if (k > 0) s.append(',');
            s.append("{\"from\":").append(ints(c.from)).append(",\"to\":").append(ints(c.to)).append('}');
        }
        return s.append("]}").toString();
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String ints(int[] v) {
        StringBuilder s = new StringBuilder("[");
        for (int k = 0; k < v.length; k++) s.append(k > 0 ? "," : "").append(v[k]);
        return s.append(']').toString();
    }

    private static String quote(String text) {
        StringBuilder s = new StringBuilder("\"");
        for (char c : (text == null ? "" : text).toCharArray()) {
            if (c == '"' || c == '\\') s.append('\\').append(c);
            else if (c == '\n') s.append("\\n");
            else if (c < 0x20) s.append(' ');
            else s.append(c);
        }
        return s.append('"').toString();
    }

    private void compute() {
        if (doc.arrows.isEmpty()) {
            say("Tracez au moins une flèche, des réactifs vers les produits (outil Flèche).", BAD);
            return;
        }
        request++;
        scheme = null;
        computing = true;
        host.compute(request, json());
        status.setText(I18n.text("Envoyé au moteur…"));
        console.start();
        fit();
        progress.setVisible(true);
        progress.setManaged(true);
        progress.setProgress(0);
        cancel.setVisible(true);
        cancel.setManaged(true);
        results.getChildren().clear();
    }

    private void addSmiles() {
        String text = smiles.getText().trim();
        if (text.isEmpty()) return;
        importText(text);
    }

    /** Un texte à lire par le moteur (scheme.imported) : SMILES, MOL, SDF, RXN, CDXML, MRV. */
    void importText(String text) {
        if (locked() || text == null || text.isBlank()) return;
        smilesRequest = Math.max(1, smilesRequest) + 1;
        host.smiles(smilesRequest, text);
        say("Lecture…", INK2);
    }

    /**
     * Coller : le texte du presse-papiers (« Copier comme » MOL, SMILES, CDXML de ChemDraw et de MarvinSketch), sinon
     * le format MDL que ChemDraw dépose sous Windows (« MDLCT » : chaque ligne précédée de sa longueur).
     */
    void pasteStructures() {
        javafx.scene.input.Clipboard board = javafx.scene.input.Clipboard.getSystemClipboard();
        String text = board.hasString() ? board.getString() : null;
        if (text == null || text.isBlank()) {
            for (javafx.scene.input.DataFormat format : board.getContentTypes()) {
                String names = String.join(" ", format.getIdentifiers()).toLowerCase(java.util.Locale.ROOT);
                if (!names.contains("mdlct") && !names.contains("molfile") && !names.contains("cdxml")) continue;
                Object content = board.getContent(format);
                if (content instanceof String string) text = string;
                else if (content instanceof java.nio.ByteBuffer buffer) text = mdlct(buffer, names.contains("mdlct"));
                if (text != null && !text.isBlank()) break;
            }
        }
        if (text == null || text.isBlank()) {
            say("Rien à coller : copier une structure dans ChemDraw ou MarvinSketch (Copier comme MOL, SMILES ou CDXML)", BAD);
            return;
        }
        importText(text);
    }

    /** Le format « MDLCT » de ChemDraw : un bloc MOL dont chaque ligne est précédée de sa longueur (un octet). */
    static String mdlct(java.nio.ByteBuffer buffer, boolean prefixed) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        if (!prefixed) return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < bytes.length) {
            int length = bytes[i++] & 0xff;
            if (i + length > bytes.length) break;
            out.append(new String(bytes, i, length, java.nio.charset.StandardCharsets.ISO_8859_1)).append('\n');
            i += length;
        }
        return out.toString();
    }

    private void openStructures() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(I18n.text("Ouvrir une structure ou un schéma"));
        chooser.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter(I18n.text("ChemDraw, MarvinSketch, MDL"), "*.cdxml", "*.mrv", "*.mol",
                        "*.sdf", "*.rxn", "*.smi", "*.txt"),
                new javafx.stage.FileChooser.ExtensionFilter(I18n.text("Tous les fichiers"), "*.*"));
        java.io.File file = chooser.showOpenDialog(canvas.getScene() == null ? null : canvas.getScene().getWindow());
        if (file != null) importFile(file);
    }

    private boolean importFile(java.io.File file) {
        if (file.length() > 8_000_000) {
            say("Fichier trop gros pour un schéma (plus de 8 Mo)", BAD);
            return false;
        }
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".cdx")) {
            say("Un .cdx (ChemDraw binaire) ne se lit pas : l'enregistrer en .cdxml dans ChemDraw", BAD);
            return false;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            String text;
            try {
                text = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException notUtf8) {
                text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            importText(text);
            return true;
        } catch (java.io.IOException error) {
            say(I18n.text("Fichier illisible : ") + error.getMessage(), BAD);
            return false;
        }
    }

    // ================================================================== les résultats
    private static boolean sameSteps(MoleculeScheme a, MoleculeScheme b) {
        if (a.getRequest() != b.getRequest() || a.getStepsCount() != b.getStepsCount()) return false;
        for (int k = 0; k < a.getStepsCount(); k++) if (a.getSteps(k) != b.getSteps(k)) return false;
        return true;
    }

    private void refreshResults() {
        refreshStatus();
        refreshCards();
    }

    /** Le dessin se modifie-t-il ? Pas pendant un calcul (il calcule le dessin envoyé). */
    private boolean locked() {
        return computing && (scheme == null || !scheme.getDone());
    }

    /** Ce qui avance pendant le calcul : l'état, la barre, les boutons du calcul — en place. */
    private void refreshStatus() {
        boolean busy = scheme != null && !scheme.getDone() || computing && scheme == null;
        boolean lock = locked();
        examples.setDisable(lock);
        clear.setDisable(lock);
        computeButton.setDisable(lock);
        if (palette != null) palette.setDisable(lock);
        progress.setVisible(busy);
        progress.setManaged(busy);
        cancel.setVisible(busy);
        cancel.setManaged(busy);
        boolean journal = !busy && console.hasLog();
        journalButton.setVisible(journal);
        journalButton.setManaged(journal);
        if (busy != wasBusy) {
            // Le calcul fini, le journal se replie (les résultats prennent la place) ; le bouton le rouvre.
            if (!busy) console.showJournal(false);
            journalButton.setText(I18n.text("Journal du calcul"));
            wasBusy = busy;
        }
        if (scheme == null) {
            status.setText(I18n.text("Dessinez un schéma, ou ouvrez un exemple, puis Calculer."));
            return;
        }
        if (!scheme.getError().isEmpty()) {
            status.setText(scheme.getError());
            status.setStyle("-fx-text-fill:" + Plan.BAD_HEX + ";");
            return;
        }
        status.setStyle("");
        if (busy) {
            progress.setProgress(scheme.getFraction());
            String rest = scheme.getRemainingS() > 1 ? I18n.text(" · encore ≈ ") + Math.round(scheme.getRemainingS()) + " s" : "";
            status.setText(I18n.text(scheme.getStatus()) + rest);
        } else {
            // Une étape refusée ou non calculée : dite tout de suite, en rouge, avec sa raison.
            int failed = -1;
            for (int k = 0; k < scheme.getStepsCount() && failed < 0; k++) if (!scheme.getSteps(k).getComputed()) failed = k;
            if (failed >= 0) {
                status.setText(I18n.text("Étape ") + (failed + 1) + I18n.text(" non calculée : ")
                        + I18n.text(scheme.getSteps(failed).getVerdict()));
                status.setStyle("-fx-text-fill:" + Plan.BAD_HEX + ";");
            } else {
                status.setText(I18n.text("Calculé : ") + scheme.getStepsCount() + " " + I18n.text(scheme.getStepsCount() > 1 ? "étapes" : "étape")
                        + I18n.text(" · GFN1-xTB"));
            }
        }
    }

    /** Les cartes des étapes et le profil de tout le schéma : refaits quand les étapes changent. */
    private void refreshCards() {
        results.getChildren().clear();
        profile = null;
        if (scheme == null || !scheme.getError().isEmpty()) return;
        if (scheme.getStepsCount() > 0) {
            profile = profileCanvas();
            VBox section = Plan.section("Profil d'énergie", profile);
            int computed = 0;
            for (MoleculeSchemeStep s : scheme.getStepsList()) if (s.getComputed()) computed++;
            if (scheme.getStepsCount() > 1 && computed > 0) {
                // Toutes les étapes à la suite, chacune commençant où la précédente finit, et le bilan du schéma.
                Button whole = Plan.primary("Tout le schéma, à la suite");
                whole.setGraphic(Icons.path("play", 12));
                whole.setTooltip(I18n.tooltip("Les étapes calculées rejouées l'une après l'autre : mêmes atomes, mêmes "
                        + "numéros, l'étape 2 reprend la molécule là où l'étape 1 l'a laissée ; le profil d'énergie de tout "
                        + "le schéma et son bilan"));
                whole.setOnAction(e -> host.play(SchemeStage.WHOLE));
                section.getChildren().add(whole);
            }
            results.getChildren().add(section);
        }
        for (int k = 0; k < scheme.getStepsCount(); k++) results.getChildren().add(card(k, scheme.getSteps(k)));
    }

    /** Le profil de tout le schéma : un palier par étape (Σ ΔE), une bosse par barrière (borne haute du chemin imposé). */
    private Canvas profileCanvas() {
        Canvas c = new Canvas(Plan.RIGHT_WIDTH - 12, 150);
        c.setOnMouseMoved(e -> {
            int k = profileStep(c, e.getX());
            if (k != highlight) {
                highlight = k;
                paintSchemeProfile(c);
                draw();
            }
        });
        c.setOnMouseExited(e -> {
            highlight = -1;
            paintSchemeProfile(c);
            draw();
        });
        paintSchemeProfile(c);
        return c;
    }

    /** L'étape sous le curseur dans le profil du schéma (−1 : aucune). */
    private int profileStep(Canvas c, double x) {
        int n = scheme == null ? 0 : scheme.getStepsCount();
        if (n == 0) return -1;
        double left = 6, right = c.getWidth() - 6, span = (right - left) / (n + 0.5);
        int k = (int) Math.floor((x - left - span * .25) / span);
        return k >= 0 && k < n ? k : -1;
    }

    /**
     * Le profil de tout le schéma : un palier par étape (Σ ΔE), une bosse par barrière (borne haute du chemin imposé) ;
     * l'étape survolée (ici ou sur sa carte) en clair, avec ses valeurs.
     */
    private void paintSchemeProfile(Canvas c) {
        GraphicsContext g = c.getGraphicsContext2D();
        g.clearRect(0, 0, c.getWidth(), c.getHeight());
        int n = scheme.getStepsCount();
        // Les hauteurs de la vue de tout le schéma (SchemeStage.Levels) : chaque palier, les réactifs de l'étape relaxés
        // chacun seul (un changement de milieu compris) ; chaque bosse, la plus haute image de son chemin imposé.
        SchemeStage.Levels levels = new SchemeStage.Levels(scheme);
        double[] level = new double[n + 1];
        double[] peak = new double[n];
        for (int k = 0; k < n; k++) {
            level[k] = levels.reactants[k];
            peak[k] = Math.max(levels.peak[k], levels.reactants[k]);
        }
        level[n] = n > 0 ? levels.products[n - 1] : 0;
        double lo = 0, hi = 0;
        for (double v : level) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        for (double v : peak) hi = Math.max(hi, v);
        double pad = Math.max(.2, (hi - lo) * .12);
        lo -= pad;
        hi += pad;
        double w = c.getWidth(), h = c.getHeight(), left = 6, right = w - 6, top = 10, bottom = h - 20;
        double span = (right - left) / (n + 0.5);
        final double low = lo, high = hi;
        java.util.function.DoubleUnaryOperator Y = v -> bottom - (v - low) / (high - low) * (bottom - top);
        g.setStroke(Color.web("#FFFFFF", .08));
        g.setLineWidth(1);
        g.strokeLine(left, Y.applyAsDouble(0), right, Y.applyAsDouble(0));
        g.setFont(Plan.sans(10));
        for (int k = 0; k <= n; k++) {
            double x0 = left + k * span, x1 = x0 + span * .5;
            g.setStroke(k == 0 || highlight == k - 1 || highlight == k ? INK : INK2);
            g.setLineWidth(2);
            g.strokeLine(x0, Y.applyAsDouble(level[k]), x1, Y.applyAsDouble(level[k]));
            g.setFill(INK2);
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText(kj(level[k]), (x0 + x1) / 2, Y.applyAsDouble(level[k]) + (level[k] > (lo + hi) / 2 ? 13 : -5));
            if (k < n) {
                double xa = x1, xb = left + (k + 1) * span, ya = Y.applyAsDouble(level[k]), yb = Y.applyAsDouble(level[k + 1]);
                double yp = Y.applyAsDouble(peak[k]);
                MoleculeSchemeStep s = scheme.getSteps(k);
                g.setStroke(s.getConfirmed() ? (highlight == k ? ACCENT : ACCENT.deriveColor(0, 1, 1, .7)) : BAD.deriveColor(0, 1, 1, .7));
                g.setLineWidth(1.6);
                g.setLineDashes(s.getComputed() ? null : new double[]{3, 4});
                g.beginPath();
                g.moveTo(xa, ya);
                g.bezierCurveTo((xa + xb) / 2 - (xb - xa) * .1, yp - (ya - yp) * .02, (xa + xb) / 2 - (xb - xa) * .1, yp, (xa + xb) / 2, yp);
                g.bezierCurveTo((xa + xb) / 2 + (xb - xa) * .1, yp, (xa + xb) / 2 + (xb - xa) * .1, yp, xb, yb);
                g.stroke();
                g.setLineDashes(null);
                g.setFill(INK3);
                g.fillText(String.valueOf(k + 1), (xa + xb) / 2, h - 5);
            }
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(INK3);
        g.fillText(I18n.text("kJ/mol, par rapport au départ"), left, top);
        if (highlight >= 0 && highlight < n) {
            MoleculeSchemeStep s = scheme.getSteps(highlight);
            String text = !s.getComputed() ? I18n.text("Étape ") + (highlight + 1) + "  ·  " + I18n.text("non calculée")
                    : I18n.text("Étape ") + (highlight + 1) + "  ·  ΔE " + kj(s.getDeltaEv()) + " kJ/mol  ·  "
                    + I18n.text("barrière") + " " + kj(s.getBarrierEv()).replace("+", "") + " kJ/mol";
            g.setFont(Plan.sans(10.5));
            double tw = text.length() * 5.6 + 16, x = Math.max(left, Math.min(right - tw, left + (highlight + .75) * span - tw / 2));
            g.setFill(Plan.RAISED.deriveColor(0, 1, 1, .96));
            g.fillRoundRect(x, top + 6, tw, 20, 10, 10);
            g.setFill(INK);
            g.fillText(text, x + 8, top + 20);
        }
    }

    private static String kj(double ev) {
        double v = ev * 96.485;
        return String.format(I18n.locale(), "%+.0f", v).replace("+0", "0").replace("-0", "0");
    }

    private VBox card(int index, MoleculeSchemeStep s) {
        Label head = new Label(I18n.text("Étape ") + (index + 1));
        head.getStyleClass().add("plan-heading");
        Label how = Plan.note(s.getMapping().equals("flèches") ? I18n.text("par vos flèches courbes")
                : s.getMapping().isEmpty() ? "" : I18n.text("atomes appariés par le calcul"));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox top = new HBox(8, head, gap, how);
        top.setAlignment(Pos.BASELINE_LEFT);
        VBox box = new VBox(7, top);
        if (!s.getReactants().isEmpty()) {
            box.getChildren().add(MoleculeFormat.formula(MoleculeFormat.names(s.getReactants()) + "  →  "
                    + MoleculeFormat.names(s.getProducts()), Font.font(Plan.MEDIUM, 12.5), INK));
        }
        // Les noms écrits par le moteur (« H₂O · eau ») traduits comme ailleurs : « H₂O · water ». Au-dessus de la
        // flèche, les réactifs ; au-dessous, les conditions.
        if (!s.getReagents().isEmpty()) {
            Label on = Plan.note(I18n.text("Réactifs écrits : ") + MoleculeFormat.names(s.getReagents()));
            on.setWrapText(true);
            box.getChildren().add(on);
        }
        if (!s.getConditions().isEmpty()) {
            Label on = Plan.note(I18n.text("Conditions : ") + MoleculeFormat.names(s.getConditions()));
            on.setWrapText(true);
            box.getChildren().add(on);
        }
        Label medium = Plan.note(s.getSolvent().isEmpty() ? I18n.text("Milieu : le vide (aucun solvant écrit sous la flèche)")
                : I18n.text("Milieu : ") + I18n.text(s.getSolvent()) + I18n.text(" (solvant implicite ALPB)"));
        medium.setWrapText(true);
        box.getChildren().add(medium);
        if (!s.getByproducts().isEmpty()) {
            Label by = Plan.note(I18n.text("Sous-produits déduits : ") + MoleculeFormat.names(s.getByproducts()));
            by.setWrapText(true);
            box.getChildren().add(by);
        }
        if (s.getComputed()) {
            Label delta = Plan.value(String.format(I18n.locale(), "%+.2f eV · %+.0f kJ/mol", s.getDeltaEv(), s.getDeltaEv() * 96.485));
            Label barrier = Plan.value(String.format(I18n.locale(), "≈ %.2f eV · %.0f kJ/mol", s.getBarrierEv(), s.getBarrierEv() * 96.485));
            box.getChildren().addAll(Plan.row("Énergie de réaction ΔE", delta), Plan.row("Barrière du chemin", barrier));
            if (Math.abs(s.getEntropyEv()) > .05) {
                // Ce que ΔE ignore quand des molécules partent : −TΔS, estimé (translations, rotations, 1 bar).
                Label entropy = Plan.value(String.format(I18n.locale(), "%+.2f eV · ΔE − TΔS ≈ %+.2f eV", s.getEntropyEv(),
                        s.getDeltaEv() + s.getEntropyEv()));
                javafx.scene.control.Tooltip.install(entropy, I18n.tooltip("L'entropie de translation et de rotation de chaque molécule relaxée "
                        + "seule (gaz parfait à 1 bar, rotor rigide), à la température de l'étape. Ni vibrations, ni "
                        + "symétrie, ni solvant : un ordre de grandeur"));
                box.getChildren().add(Plan.row("−TΔS estimé", entropy));
            }
            box.getChildren().add(miniProfile(s));
        }
        Label verdict = new Label(s.getVerdict());
        verdict.setWrapText(true);
        verdict.setStyle("-fx-text-fill:" + (s.getConfirmed() ? Plan.GOOD_HEX : Plan.BAD_HEX) + ";-fx-font-size:11.5px;");
        box.getChildren().add(verdict);
        if (s.getNotesCount() > 0) {
            VBox notes = new VBox(4);
            for (String n : s.getNotesList()) {
                Label l = Plan.note("· " + n);
                l.setWrapText(true);
                notes.getChildren().add(l);
            }
            box.getChildren().add(Plan.foldable("Limites et remarques", false, notes));
        }
        if (s.getComputed()) {
            Button play = Plan.primary("Voir le chemin");
            play.setTooltip(I18n.tooltip("Le chemin calculé de cette étape, rejoué en 2.5D : chaque géométrie montrée est "
                    + "calculée ; légende, énergie, liaisons et charges image par image"));
            play.setGraphic(Icons.path("play", 12));
            play.setOnAction(e -> host.play(index));
            box.getChildren().add(play);
        }
        box.setPadding(new Insets(12));
        box.getStyleClass().add("plan-fact");
        box.setOnMouseEntered(e -> {
            highlight = index;
            if (profile != null) paintSchemeProfile(profile);
            draw();
        });
        box.setOnMouseExited(e -> {
            highlight = -1;
            if (profile != null) paintSchemeProfile(profile);
            draw();
        });
        return box;
    }

    /** L'énergie de chaque image du chemin d'une étape (eV, la première à 0) ; le chemin imposé en clair. */
    static Canvas miniProfile(MoleculeSchemeStep s) {
        Canvas c = new Canvas(Plan.RIGHT_WIDTH - 36, 64);
        paintProfile(c, s, -1);
        return c;
    }

    static void paintProfile(Canvas c, MoleculeSchemeStep s, double marker) {
        GraphicsContext g = c.getGraphicsContext2D();
        double w = c.getWidth(), h = c.getHeight();
        g.clearRect(0, 0, w, h);
        int n = s.getEnergiesCount();
        if (n < 2) return;
        double lo = 0, hi = 0;
        for (double v : s.getEnergiesList()) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (hi - lo < .05) hi = lo + .05;
        double top = 6, bottom = h - 6;
        double x0 = w * (s.getScanStart() / (double) (n - 1)), x1 = w * ((s.getScanEnd() - 1) / (double) (n - 1));
        g.setFill(Color.web("#FFFFFF", .04));
        g.fillRect(x0, 0, Math.max(1, x1 - x0), h);
        g.setStroke(Color.web("#FFFFFF", .10));
        double y0 = bottom - (0 - lo) / (hi - lo) * (bottom - top);
        g.strokeLine(0, y0, w, y0);
        g.setStroke(ACCENT);
        g.setLineWidth(1.6);
        g.beginPath();
        for (int k = 0; k < n; k++) {
            double x = w * k / (n - 1), y = bottom - (s.getEnergies(k) - lo) / (hi - lo) * (bottom - top);
            if (k == 0) g.moveTo(x, y); else g.lineTo(x, y);
        }
        g.stroke();
        if (marker >= 0) {
            double f = marker * (n - 1);
            int k = Math.min(n - 2, (int) f);
            double e = s.getEnergies(k) + (s.getEnergies(k + 1) - s.getEnergies(k)) * (f - k);
            double x = w * marker, y = bottom - (e - lo) / (hi - lo) * (bottom - top);
            g.setFill(INK);
            g.fillOval(x - 3.5, y - 3.5, 7, 7);
        }
        g.setFill(INK3);
        g.setFont(Plan.sans(9.5));
        g.fillText(String.format(I18n.locale(), "%+.2f eV", hi), 2, 10);
    }

    // ================================================================== géométrie de l'écran
    private double sx(double x) { return panX + x * scale; }
    private double sy(double y) { return panY + y * scale; }
    private double wx(double x) { return (x - panX) / scale; }
    private double wy(double y) { return (y - panY) / scale; }

    private double[] bounds() {
        if (doc.atoms.isEmpty() && doc.arrows.isEmpty()) return null;
        double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
        for (Atom a : doc.atoms) { minX = Math.min(minX, a.x); maxX = Math.max(maxX, a.x); minY = Math.min(minY, a.y); maxY = Math.max(maxY, a.y); }
        for (Arrow r : doc.arrows) {
            minX = Math.min(minX, Math.min(r.tx, r.hx)); maxX = Math.max(maxX, Math.max(r.tx, r.hx));
            minY = Math.min(minY, Math.min(r.ty, r.hy)); maxY = Math.max(maxY, Math.max(r.ty, r.hy));
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    void fit() {
        double[] b = bounds();
        // L'écran de calcul ouvert couvre le bas du dessin : le schéma se cadre au-dessus.
        double reserve = console.getNode().isVisible() ? SchemeConsole.HEIGHT + 14 : 0;
        double w = Math.max(200, canvas.getWidth()), h = Math.max(200, canvas.getHeight() - reserve);
        if (b == null) {
            scale = 34;
            panX = w / 2;
            panY = h / 2;
        } else {
            double bw = Math.max(4, b[2] - b[0] + 3), bh = Math.max(4, b[3] - b[1] + 3);
            scale = Math.max(12, Math.min(48, Math.min(w / bw, h / bh)));
            panX = w / 2 - (b[0] + b[2]) / 2 * scale;
            panY = h / 2 - (b[1] + b[3]) / 2 * scale;
        }
        draw();
    }

    private int atomAt(double x, double y) {
        int best = -1;
        double bestD = 12;
        for (int k = 0; k < doc.atoms.size(); k++) {
            Atom a = doc.atoms.get(k);
            double d = Math.hypot(sx(a.x) - x, sy(a.y) - y);
            if (d < bestD) { bestD = d; best = k; }
        }
        return best;
    }

    private int bondAt(double x, double y) {
        int best = -1;
        double bestD = 7;
        for (int k = 0; k < doc.bonds.size(); k++) {
            Bond b = doc.bonds.get(k);
            Atom p = doc.atoms.get(b.a), q = doc.atoms.get(b.b);
            double d = segment(x, y, sx(p.x), sy(p.y), sx(q.x), sy(q.y));
            if (d < bestD) { bestD = d; best = k; }
        }
        return best;
    }

    private int arrowAt(double x, double y) {
        for (int k = 0; k < doc.arrows.size(); k++) {
            Arrow r = doc.arrows.get(k);
            if (segment(x, y, sx(r.tx), sy(r.ty), sx(r.hx), sy(r.hy)) < 8) return k;
        }
        return -1;
    }

    private int curlyAt(double x, double y) {
        for (int k = 0; k < doc.curly.size(); k++) {
            double[][] p = curlyPoints(doc.curly.get(k));
            if (p == null) continue;
            for (int i = 0; i <= 20; i++) {
                double[] q = bezier(p, i / 20.0);
                if (Math.hypot(sx(q[0]) - x, sy(q[1]) - y) < 7) return k;
            }
        }
        return -1;
    }

    private static double segment(double x, double y, double x0, double y0, double x1, double y1) {
        double dx = x1 - x0, dy = y1 - y0, l2 = dx * dx + dy * dy;
        double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / l2));
        return Math.hypot(x - x0 - t * dx, y - y0 - t * dy);
    }

    private List<Integer> neighbours(int a) {
        List<Integer> out = new ArrayList<>();
        for (Bond b : doc.bonds) {
            if (b.a == a) out.add(b.b);
            else if (b.b == a) out.add(b.a);
        }
        return out;
    }

    private int bondBetween(int a, int b) {
        for (int k = 0; k < doc.bonds.size(); k++) {
            Bond x = doc.bonds.get(k);
            if (x.a == a && x.b == b || x.a == b && x.b == a) return k;
        }
        return -1;
    }

    private int bonded(int a) {
        int s = 0;
        for (Bond b : doc.bonds) if (b.a == a || b.b == a) s += b.order;
        return s;
    }

    /** La direction libre autour d'un atome (radians, repère du dessin) : à l'opposé de ses voisins, en zigzag. */
    private double freeAngle(int a) {
        List<Integer> n = neighbours(a);
        Atom p = doc.atoms.get(a);
        if (n.isEmpty()) return -Math.PI / 6;
        if (n.size() == 1) {
            Atom q = doc.atoms.get(n.get(0));
            double back = Math.atan2(q.y - p.y, q.x - p.x);
            List<Integer> nn = neighbours(n.get(0));
            nn.remove(Integer.valueOf(a));
            double turn = 2 * Math.PI / 3;
            if (!nn.isEmpty()) {
                Atom r = doc.atoms.get(nn.get(0));
                double side = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x);
                return back + (side > 0 ? -turn : turn);
            }
            return back + turn;
        }
        double sx = 0, sy = 0;
        for (int k : n) {
            Atom q = doc.atoms.get(k);
            double l = Math.hypot(q.x - p.x, q.y - p.y);
            sx += (q.x - p.x) / l;
            sy += (q.y - p.y) / l;
        }
        if (Math.hypot(sx, sy) < 1e-3) {
            Atom q = doc.atoms.get(n.get(0));
            return Math.atan2(q.y - p.y, q.x - p.x) + Math.PI / 2;
        }
        return Math.atan2(-sy, -sx);
    }

    private int atomNear(double x, double y, double radius) {
        for (int k = 0; k < doc.atoms.size(); k++) {
            Atom a = doc.atoms.get(k);
            if (Math.hypot(a.x - x, a.y - y) < radius) return k;
        }
        return -1;
    }

    // ================================================================== modifier le dessin
    private void remember() {
        undo.push(doc.copy());
        while (undo.size() > 60) undo.removeLast();
    }

    private void changed() {
        if (scheme != null && scheme.getDone()) {
            discard();                   // le dessin a changé : les résultats ne lui répondent plus
            refreshResults();
        }
        draw();
    }

    /** Le calcul dont les résultats ne répondent plus au dessin (un exemple chargé, le dessin modifié) : −1, aucun. */
    private int discarded = -1;

    /**
     * Oublie les résultats : le moteur les republie (le même numéro de demande), et l'éditeur les remettait à l'image
     * suivante — les cartes du Diels–Alder et leur « Voir le chemin » revenaient sous le complexe au cobalt fraîchement
     * chargé. Ce calcul-là est désormais ignoré jusqu'au prochain « Calculer ».
     */
    private void discard() {
        if (scheme != null || request > 0) discarded = request;
        scheme = null;
        computing = false;
    }

    /** Les cartes de résultats montrées (tests). */
    int resultCards() {
        return results.getChildren().size();
    }

    private int addAtom(int z, double x, double y) {
        doc.atoms.add(new Atom(z, x, y));
        return doc.atoms.size() - 1;
    }

    private void bond(int a, int b, int wanted) {
        if (a == b) return;
        int k = bondBetween(a, b);
        if (k >= 0) {
            doc.bonds.get(k).order = wanted;
            return;
        }
        doc.bonds.add(new Bond(Math.min(a, b), Math.max(a, b), wanted));
    }

    private void removeAtom(int index) {
        doc.bonds.removeIf(b -> b.a == index || b.b == index);
        for (Bond b : doc.bonds) {
            if (b.a > index) b.a--;
            if (b.b > index) b.b--;
        }
        doc.curly.removeIf(c -> contains(c.from, index) || contains(c.to, index));
        for (Curly c : doc.curly) {
            for (int k = 0; k < c.from.length; k++) if (c.from[k] > index) c.from[k]--;
            for (int k = 0; k < c.to.length; k++) if (c.to[k] > index) c.to[k]--;
        }
        doc.atoms.remove(index);
    }

    private static boolean contains(int[] v, int x) {
        for (int k : v) if (k == x) return true;
        return false;
    }

    private void placeRing(double cx, double cy, int onAtom, int onBond) {
        int n = ring;
        double side = 1, radius = side / (2 * Math.sin(Math.PI / n)), apothem = side / (2 * Math.tan(Math.PI / n));
        List<double[]> points = new ArrayList<>();
        List<Integer> fixed = new ArrayList<>();
        if (onBond >= 0) {
            Bond b = doc.bonds.get(onBond);
            Atom p = doc.atoms.get(b.a), q = doc.atoms.get(b.b);
            double mx = (p.x + q.x) / 2, my = (p.y + q.y) / 2, dx = q.x - p.x, dy = q.y - p.y, l = Math.hypot(dx, dy);
            double nx = -dy / l, ny = dx / l;
            // Le côté le moins encombré.
            double crowd = 0;
            for (Atom a : doc.atoms) crowd += Math.signum((a.x - mx) * nx + (a.y - my) * ny) / Math.max(.5, Math.hypot(a.x - mx, a.y - my));
            if (crowd > 0) { nx = -nx; ny = -ny; }
            double ox = mx + nx * apothem * l, oy = my + ny * apothem * l;
            double start = Math.atan2(p.y - oy, p.x - ox), step = 2 * Math.PI / n;
            double dir = Math.signum(Math.sin(Math.atan2(q.y - oy, q.x - ox) - start));
            for (int k = 0; k < n; k++) points.add(new double[]{ox + radius * l * Math.cos(start + dir * step * k), oy + radius * l * Math.sin(start + dir * step * k)});
            fixed.add(b.a);
            fixed.add(b.b);
        } else if (onAtom >= 0) {
            Atom p = doc.atoms.get(onAtom);
            double t = freeAngle(onAtom);
            double ox = p.x + radius * Math.cos(t), oy = p.y + radius * Math.sin(t);
            double start = t + Math.PI;
            for (int k = 0; k < n; k++) points.add(new double[]{ox + radius * Math.cos(start + 2 * Math.PI * k / n), oy + radius * Math.sin(start + 2 * Math.PI * k / n)});
            fixed.add(onAtom);
        } else {
            double start = -Math.PI / 2 + (n % 2 == 0 ? Math.PI / n : 0);
            for (int k = 0; k < n; k++) points.add(new double[]{cx + radius * Math.cos(start + 2 * Math.PI * k / n), cy + radius * Math.sin(start + 2 * Math.PI * k / n)});
        }
        int[] ids = new int[n];
        for (int k = 0; k < n; k++) {
            if (k < fixed.size()) { ids[k] = fixed.get(k); continue; }
            int near = atomNear(points.get(k)[0], points.get(k)[1], .25);
            ids[k] = near >= 0 ? near : addAtom(6, points.get(k)[0], points.get(k)[1]);
        }
        for (int k = 0; k < n; k++) {
            int a = ids[k], b = ids[(k + 1) % n];
            if (bondBetween(a, b) >= 0) continue;
            boolean dbl = aromatic && k % 2 == (fixed.size() == 2 ? 1 : 0) && hasNoDouble(a) && hasNoDouble(b);
            bond(a, b, dbl ? 2 : 1);
        }
    }

    private boolean hasNoDouble(int a) {
        for (Bond b : doc.bonds) if ((b.a == a || b.b == a) && b.order > 1) return false;
        return true;
    }

    // ================================================================== les gestes
    private void move(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
        hover(e.getX(), e.getY());
        draw();
    }

    private void hover(double x, double y) {
        hoverAtom = atomAt(x, y);
        hoverBond = hoverAtom < 0 ? bondAt(x, y) : -1;
        hoverArrow = hoverAtom < 0 && hoverBond < 0 ? arrowAt(x, y) : -1;
        hoverCurly = hoverAtom < 0 && hoverBond < 0 && hoverArrow < 0 ? curlyAt(x, y) : -1;
        canvas.setCursor(hoverAtom >= 0 || hoverBond >= 0 || hoverArrow >= 0 || hoverCurly >= 0 ? Cursor.HAND : Cursor.CROSSHAIR);
    }

    private void press(MouseEvent e) {
        canvas.requestFocus();
        hideEditor();
        pressX = lastX = e.getX();
        pressY = lastY = e.getY();
        dragging = moved = false;
        hover(e.getX(), e.getY());
        pressAtom = hoverAtom;
        pressBond = hoverBond;
        pressArrow = hoverArrow;
        if (e.getButton() == MouseButton.SECONDARY || e.getButton() == MouseButton.MIDDLE) {
            if (tool == Tool.CHARGE && pressAtom >= 0) return;
            panning = true;
            return;
        }
        panning = false;
        if (locked()) {
            say("Le calcul tourne sur ce dessin : il se modifie une fois le calcul fini (ou arrêté).", INK2);
            return;
        }
        if (e.getClickCount() == 2 && pressArrow >= 0) {
            editArrow(pressArrow);
            return;
        }
        if (tool == Tool.MOVE) {
            moving.clear();
            moveStart.clear();
            arrowEnd = 0;
            if (pressAtom >= 0) {
                remember();
                if (e.isShiftDown()) moving.add(pressAtom);
                else moving.addAll(molecule(pressAtom));
                for (int k : moving) moveStart.add(new double[]{doc.atoms.get(k).x, doc.atoms.get(k).y});
            } else if (pressArrow >= 0) {
                remember();
                Arrow r = doc.arrows.get(pressArrow);
                if (Math.hypot(sx(r.tx) - pressX, sy(r.ty) - pressY) < 10) arrowEnd = 1;
                else if (Math.hypot(sx(r.hx) - pressX, sy(r.hy) - pressY) < 10) arrowEnd = 2;
                moveStart.add(new double[]{r.tx, r.ty, r.hx, r.hy});
            } else {
                panning = true;
            }
        }
    }

    private List<Integer> molecule(int start) {
        List<Integer> out = new ArrayList<>();
        Deque<Integer> todo = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        todo.add(start);
        seen.add(start);
        while (!todo.isEmpty()) {
            int a = todo.poll();
            out.add(a);
            for (int b : neighbours(a)) if (seen.add(b)) todo.add(b);
        }
        return out;
    }

    private void drag(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
        if (Math.hypot(e.getX() - pressX, e.getY() - pressY) > 4) dragging = true;
        if (panning) {
            panX += e.getX() - lastX;
            panY += e.getY() - lastY;
            lastX = e.getX();
            lastY = e.getY();
            draw();
            return;
        }
        lastX = e.getX();
        lastY = e.getY();
        if (tool == Tool.MOVE && dragging) {
            double dx = (e.getX() - pressX) / scale, dy = (e.getY() - pressY) / scale;
            if (!moving.isEmpty()) {
                for (int k = 0; k < moving.size(); k++) {
                    Atom a = doc.atoms.get(moving.get(k));
                    a.x = moveStart.get(k)[0] + dx;
                    a.y = moveStart.get(k)[1] + dy;
                }
                moved = true;
            } else if (pressArrow >= 0 && !moveStart.isEmpty()) {
                Arrow r = doc.arrows.get(pressArrow);
                double[] s = moveStart.get(0);
                if (arrowEnd != 2) { r.tx = s[0] + dx; r.ty = s[1] + dy; }
                if (arrowEnd != 1) { r.hx = s[2] + dx; r.hy = s[3] + dy; }
                moved = true;
            }
        }
        hover(e.getX(), e.getY());
        draw();
    }

    private void release(MouseEvent e) {
        if (panning) {
            panning = false;
            if (!dragging && tool == Tool.CHARGE && pressAtom >= 0) charge(pressAtom, -1);
            return;
        }
        double x = wx(e.getX()), y = wy(e.getY());
        hover(e.getX(), e.getY());
        int target = hoverAtom;
        switch (tool) {
            case BOND -> releaseBond(x, y, target);
            case RING -> {
                remember();
                placeRing(x, y, pressAtom, pressAtom < 0 ? pressBond : -1);
                changed();
            }
            case ATOM -> {
                remember();
                if (pressAtom >= 0) {
                    Atom a = doc.atoms.get(pressAtom);
                    a.z = element;
                    a.label = null;
                } else {
                    addAtom(element, x, y);
                }
                changed();
            }
            case CHARGE -> { if (pressAtom >= 0) charge(pressAtom, +1); }
            case GROUP -> {
                remember();
                int k = pressAtom >= 0 ? pressAtom : addAtom(0, x, y);
                doc.atoms.get(k).z = 0;
                doc.atoms.get(k).label = group;
                doc.atoms.get(k).charge = 0;
                changed();
            }
            case ARROW -> {
                if (dragging) {
                    double tx = wx(pressX), ty = wy(pressY);
                    double dx = x - tx, dy = y - ty, l = Math.hypot(dx, dy);
                    if (l >= 1.2) {
                        double angle = Math.round(Math.atan2(dy, dx) / (Math.PI / 12)) * (Math.PI / 12);
                        remember();
                        Arrow r = new Arrow();
                        r.tx = tx; r.ty = ty; r.hx = tx + l * Math.cos(angle); r.hy = ty + l * Math.sin(angle);
                        doc.arrows.add(r);
                        changed();
                        editArrow(doc.arrows.size() - 1);
                    }
                }
            }
            case CURLY -> releaseCurly(target);
            case MOVE -> {
                if (moved) changed();
                else if (undo.size() > 0 && !dragging) undo.pop();
            }
            case ERASE -> {
                remember();
                if (pressAtom >= 0) removeAtom(pressAtom);
                else if (pressBond >= 0) removeBond(pressBond);
                else if (pressArrow >= 0) doc.arrows.remove(pressArrow);
                else if (hoverCurly >= 0) doc.curly.remove(hoverCurly);
                else undo.pop();
                hoverAtom = hoverBond = hoverArrow = hoverCurly = -1;
                changed();
            }
        }
        dragging = false;
        pressAtom = pressBond = pressArrow = -1;
        draw();
    }

    private void removeBond(int k) {
        Bond b = doc.bonds.get(k);
        doc.curly.removeIf(c -> c.from.length == 2 && (c.from[0] == b.a && c.from[1] == b.b || c.from[0] == b.b && c.from[1] == b.a));
        doc.bonds.remove(k);
    }

    private void charge(int atom, int delta) {
        remember();
        Atom a = doc.atoms.get(atom);
        a.charge = Math.max(-3, Math.min(3, a.charge + delta));
        changed();
    }

    private void releaseBond(double x, double y, int target) {
        remember();
        if (!dragging) {
            if (pressAtom >= 0) {
                double t = freeAngle(pressAtom);
                Atom p = doc.atoms.get(pressAtom);
                double nx = p.x + Math.cos(t), ny = p.y + Math.sin(t);
                int near = atomNear(nx, ny, .3);
                bond(pressAtom, near >= 0 ? near : addAtom(6, nx, ny), order);
            } else if (pressBond >= 0) {
                Bond b = doc.bonds.get(pressBond);
                b.order = b.order >= 3 ? 1 : b.order + 1;
            } else {
                int a = addAtom(6, x - .43, y + .25);
                int b = addAtom(6, x + .43, y - .25);
                bond(a, b, order);
            }
            changed();
            return;
        }
        int from = pressAtom;
        if (from < 0) {
            from = pressBond >= 0 ? -1 : addAtom(6, wx(pressX), wy(pressY));
            if (from < 0) { undo.pop(); return; }
        }
        if (target >= 0 && target != from) {
            bond(from, target, order);
        } else {
            Atom p = doc.atoms.get(from);
            double angle = Math.round(Math.atan2(y - p.y, x - p.x) / (Math.PI / 6)) * (Math.PI / 6);
            double nx = p.x + Math.cos(angle), ny = p.y + Math.sin(angle);
            int near = atomNear(nx, ny, .3);
            bond(from, near >= 0 ? near : addAtom(6, nx, ny), order);
        }
        changed();
    }

    /**
     * La flèche courbe : ses extrémités deviennent la sémantique du moteur. D'un atome vers un atome : la liaison qui se
     * forme entre eux. D'une liaison vers l'un de ses atomes : la paire y devient un doublet ; vers un autre atome : la
     * liaison se forme depuis le bout le plus proche. Vers une liaison : la paire la renforce.
     */
    private void releaseCurly(int target) {
        if (!dragging) return;
        int[] from;
        if (pressAtom >= 0) from = new int[]{pressAtom};
        else if (pressBond >= 0) from = new int[]{doc.bonds.get(pressBond).a, doc.bonds.get(pressBond).b};
        else return;
        int[] to = null;
        int targetBond = target < 0 ? hoverBond : -1;
        if (target >= 0) {
            if (from.length == 1) to = target == from[0] ? null : new int[]{from[0], target};
            else if (target == from[0] || target == from[1]) to = new int[]{target};
            else {
                Atom t = doc.atoms.get(target);
                Atom p = doc.atoms.get(from[0]), q = doc.atoms.get(from[1]);
                int end = Math.hypot(p.x - t.x, p.y - t.y) <= Math.hypot(q.x - t.x, q.y - t.y) ? from[0] : from[1];
                to = new int[]{end, target};
            }
        } else if (targetBond >= 0) {
            Bond b = doc.bonds.get(targetBond);
            to = new int[]{b.a, b.b};
        }
        if (to == null) {
            say("Une flèche courbe va vers un atome ou une liaison.", BAD);
            return;
        }
        remember();
        doc.curly.add(new Curly(from, to));
        changed();
    }

    /** Le nombre de molécules (groupes d'atomes liés) d'un document. */
    static int components(Doc d) {
        int[] parent = new int[d.atoms.size()];
        for (int k = 0; k < parent.length; k++) parent[k] = k;
        java.util.function.IntUnaryOperator find = new java.util.function.IntUnaryOperator() {
            @Override public int applyAsInt(int k) {
                while (parent[k] != k) k = parent[k] = parent[parent[k]];
                return k;
            }
        };
        for (Bond b : d.bonds) parent[find.applyAsInt(b.a)] = find.applyAsInt(b.b);
        int count = 0;
        for (int k = 0; k < parent.length; k++) if (find.applyAsInt(k) == k) count++;
        return count;
    }

    private void key(javafx.scene.input.KeyEvent e) {
        if (locked()) return;
        if (e.isShortcutDown() && e.getCode() == KeyCode.V) {
            pasteStructures();
            e.consume();
            return;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.Z) {
            if (!undo.isEmpty()) {
                doc = undo.pop();
                changed();
            }
            return;
        }
        if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
            remember();
            if (hoverAtom >= 0) removeAtom(hoverAtom);
            else if (hoverBond >= 0) removeBond(hoverBond);
            else if (hoverArrow >= 0) doc.arrows.remove(hoverArrow);
            else if (hoverCurly >= 0) doc.curly.remove(hoverCurly);
            else { undo.pop(); return; }
            hoverAtom = hoverBond = hoverArrow = hoverCurly = -1;
            changed();
            return;
        }
        if (e.getCode() == KeyCode.ESCAPE) {
            hideEditor();
            return;
        }
        String typed = e.getText();
        if (hoverAtom >= 0 && (typed.equals("+") || typed.equals("-"))) {
            charge(hoverAtom, typed.equals("+") ? 1 : -1);
            return;
        }
        if (hoverBond >= 0 && (typed.equals("1") || typed.equals("2") || typed.equals("3"))) {
            remember();
            doc.bonds.get(hoverBond).order = Integer.parseInt(typed);
            changed();
            return;
        }
        int z = switch (typed.toUpperCase(Locale.ROOT)) {
            case "C" -> 6; case "N" -> 7; case "O" -> 8; case "S" -> 16; case "F" -> 9; case "P" -> 15; case "I" -> 53;
            case "B" -> 5; case "H" -> 1; case "L" -> 17; case "R" -> 35;
            default -> -1;
        };
        if (z > 0 && hoverAtom >= 0) {
            remember();
            Atom a = doc.atoms.get(hoverAtom);
            a.z = z;
            a.label = null;
            changed();
        }
    }

    // ------------------------------------------------------------------ le texte d'une flèche
    private int editing = -1;

    private void editArrow(int index) {
        editing = index;
        Arrow r = doc.arrows.get(index);
        TextField above = new TextField(r.above), below = new TextField(r.below);
        above.setPromptText(I18n.text("au-dessus : MeI, CsF, Co2(CO)8, un SMILES…"));
        below.setPromptText(I18n.text("au-dessous : THF, 25 °C…"));
        above.setPrefColumnCount(18);
        below.setPrefColumnCount(18);
        Button ok = Plan.primary("OK");
        Runnable commit = () -> {
            if (editing >= 0 && editing < doc.arrows.size()) {
                remember();
                doc.arrows.get(editing).above = above.getText().trim();
                doc.arrows.get(editing).below = below.getText().trim();
                changed();
            }
            hideEditor();
        };
        ok.setOnAction(e -> commit.run());
        above.setOnAction(e -> commit.run());
        below.setOnAction(e -> commit.run());
        above.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) hideEditor(); });
        below.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) hideEditor(); });
        editor.getChildren().setAll(Plan.label("Réactifs et conditions de la flèche"), above, below, ok);
        editor.getStyleClass().setAll("plan-fact");
        editor.setPadding(new Insets(10));
        editor.setVisible(true);
        double mx = sx((r.tx + r.hx) / 2), my = sy((r.ty + r.hy) / 2);
        editor.relocate(Math.max(8, Math.min(canvas.getWidth() - 260, mx - 120)), Math.max(8, Math.min(canvas.getHeight() - 170, my + 18)));
        above.requestFocus();
    }

    private void hideEditor() {
        editing = -1;
        editor.setVisible(false);
        canvas.requestFocus();
    }

    private void say(String text, Color colour) {
        message = I18n.text(text);
        messageColour = colour;
        draw();
    }

    // ================================================================== le dessin à l'écran
    void draw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w < 2 || h < 2) return;
        if (Double.isNaN(panX)) {
            panX = w / 2;
            panY = h / 2;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(BACK);
        g.fillRect(0, 0, w, h);
        // Une trame de points discrète, au pas d'une liaison.
        g.setFill(DOT);
        double step = scale;
        for (double x = panX % step; x < w; x += step)
            for (double y = panY % step; y < h; y += step) g.fillRect(x - .75, y - .75, 1.5, 1.5);
        Set<Integer> reactants = new HashSet<>(), products = new HashSet<>();
        if (scheme != null && highlight >= 0 && highlight < scheme.getStepsCount()) {
            reactants.addAll(scheme.getSteps(highlight).getReactantAtomsList());
            products.addAll(scheme.getSteps(highlight).getProductAtomsList());
        }
        // Les halos des étapes survolées.
        for (int k = 0; k < doc.atoms.size(); k++) {
            Color c = reactants.contains(k) ? REACTANT : products.contains(k) ? PRODUCT : null;
            if (c == null) continue;
            Atom a = doc.atoms.get(k);
            g.setFill(c.deriveColor(0, 1, 1, .16));
            g.fillOval(sx(a.x) - scale * .45, sy(a.y) - scale * .45, scale * .9, scale * .9);
        }
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        for (int k = 0; k < doc.bonds.size(); k++) drawBond(g, k);
        for (int k = 0; k < doc.atoms.size(); k++) drawAtom(g, k);
        for (int k = 0; k < doc.arrows.size(); k++) drawArrow(g, k);
        for (int k = 0; k < doc.curly.size(); k++) {
            double[][] p = curlyPoints(doc.curly.get(k));
            if (p != null) drawCurly(g, p, k == hoverCurly ? CURLY.brighter() : CURLY);
        }
        preview(g);
        if (doc.atoms.isEmpty() && doc.arrows.isEmpty()) {
            g.setFill(INK3);
            g.setFont(Plan.sans(13));
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText(I18n.text("Tirez des liaisons, posez des cycles, puis une flèche vers le produit — ou ouvrez un exemple."), w / 2, h / 2);
            g.setTextAlign(TextAlignment.LEFT);
        }
        if (!message.isEmpty()) {
            g.setFill(messageColour);
            g.setFont(Plan.sans(12));
            g.fillText(message, 14, h - 14);
        }
    }

    private boolean labelled(int k) {
        Atom a = doc.atoms.get(k);
        return a.z != 6 || a.label != null || a.charge != 0 || neighbours(k).isEmpty();
    }

    private void drawBond(GraphicsContext g, int k) {
        Bond b = doc.bonds.get(k);
        Atom p = doc.atoms.get(b.a), q = doc.atoms.get(b.b);
        double x0 = sx(p.x), y0 = sy(p.y), x1 = sx(q.x), y1 = sy(q.y);
        double dx = x1 - x0, dy = y1 - y0, l = Math.hypot(dx, dy);
        if (l < 1) return;
        double ux = dx / l, uy = dy / l, nx = -uy, ny = ux;
        double cut = scale * .32;
        double a0 = labelled(b.a) ? cut : 0, a1 = labelled(b.b) ? cut : 0;
        double sx0 = x0 + ux * a0, sy0 = y0 + uy * a0, sx1 = x1 - ux * a1, sy1 = y1 - uy * a1;
        Color colour = k == hoverBond ? ACCENT : INK;
        g.setStroke(colour);
        g.setLineWidth(Math.max(1.2, scale * .045));
        double gap = scale * .16;
        if (b.order == 1) {
            g.strokeLine(sx0, sy0, sx1, sy1);
        } else if (b.order == 2) {
            // Le second trait du côté du cycle (ou des voisins) ; centré pour une double liaison isolée.
            double side = 0;
            for (int n : neighbours(b.a)) if (n != b.b) side += Math.signum((sx(doc.atoms.get(n).x) - x0) * nx + (sy(doc.atoms.get(n).y) - y0) * ny);
            for (int n : neighbours(b.b)) if (n != b.a) side += Math.signum((sx(doc.atoms.get(n).x) - x1) * nx + (sy(doc.atoms.get(n).y) - y1) * ny);
            boolean centred = side == 0 || labelled(b.a) && labelled(b.b) && neighbours(b.a).size() == 1 && neighbours(b.b).size() == 1;
            if (centred) {
                g.strokeLine(sx0 + nx * gap / 2, sy0 + ny * gap / 2, sx1 + nx * gap / 2, sy1 + ny * gap / 2);
                g.strokeLine(sx0 - nx * gap / 2, sy0 - ny * gap / 2, sx1 - nx * gap / 2, sy1 - ny * gap / 2);
            } else {
                double s = Math.signum(side);
                g.strokeLine(sx0, sy0, sx1, sy1);
                double shrink = l * .14;
                g.strokeLine(sx0 + nx * gap * s + ux * (a0 > 0 ? 0 : shrink), sy0 + ny * gap * s + uy * (a0 > 0 ? 0 : shrink),
                        sx1 + nx * gap * s - ux * (a1 > 0 ? 0 : shrink), sy1 + ny * gap * s - uy * (a1 > 0 ? 0 : shrink));
            }
        } else {
            g.strokeLine(sx0, sy0, sx1, sy1);
            g.strokeLine(sx0 + nx * gap, sy0 + ny * gap, sx1 + nx * gap, sy1 + ny * gap);
            g.strokeLine(sx0 - nx * gap, sy0 - ny * gap, sx1 - nx * gap, sy1 - ny * gap);
        }
    }

    /** Les hydrogènes implicites d'un atome dessiné (les mêmes règles que scheme.implicit_hydrogens). */
    private int hydrogens(int k) {
        Atom a = doc.atoms.get(k);
        if (a.label != null || a.z == 1) return 0;
        int e = switch (a.z) {
            case 5 -> 3; case 6, 14 -> 4; case 7, 15 -> 5; case 8, 16, 34 -> 6; case 9, 17, 35, 53 -> 7;
            default -> -1;
        };
        if (e < 0) return 0;
        e -= a.charge;
        if (e <= 0 || e >= 8) return 0;
        int v = e <= 4 ? e : 8 - e, bonded = bonded(k);
        if (bonded <= v) return v - bonded;
        if (a.z > 10) {
            while (v < bonded && v < 7) v += 2;
            return Math.max(0, v - bonded);
        }
        return 0;
    }

    private void drawAtom(GraphicsContext g, int k) {
        Atom a = doc.atoms.get(k);
        double x = sx(a.x), y = sy(a.y);
        boolean hovered = k == hoverAtom;
        if (labelled(k)) {
            String symbol = a.label != null ? a.label : MoleculeView.symbolOf(a.z);
            int hs = hydrogens(k);
            String hText = hs == 0 ? "" : hs == 1 ? "H" : "H" + subscript(hs);
            // Les H du côté libre : à gauche si les liaisons partent vers la droite.
            double lean = 0;
            for (int n : neighbours(k)) lean += doc.atoms.get(n).x - a.x;
            String text = lean > .2 && !hText.isEmpty() ? hText + symbol : symbol + hText;
            Font font = Plan.medium(Math.max(9, scale * .42));
            Text probe = new Text(text);
            probe.setFont(font);
            double tw = probe.getLayoutBounds().getWidth();
            Text first = new Text(lean > .2 && !hText.isEmpty() ? hText : "");
            first.setFont(font);
            double fw = first.getLayoutBounds().getWidth();
            Text sym = new Text(symbol);
            sym.setFont(font);
            double symW = sym.getLayoutBounds().getWidth();
            double left = x - fw - symW / 2;
            g.setFill(BACK);
            g.fillRoundRect(left - 2, y - scale * .27, tw + 4, scale * .54, 4, 4);
            Color colour = a.label != null ? INK : a.z == 6 ? INK : MoleculeScene.elementColor(a.z).interpolate(INK, .35);
            g.setFill(hovered ? ACCENT : colour);
            g.setFont(font);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(text, left, y + scale * .15);
            if (a.charge != 0) {
                String c = (Math.abs(a.charge) > 1 ? String.valueOf(Math.abs(a.charge)) : "") + (a.charge > 0 ? "+" : "−");
                g.setFont(Plan.medium(Math.max(8, scale * .3)));
                g.fillText(c, left + tw + 1, y - scale * .12);
            }
        } else if (hovered) {
            g.setFill(ACCENT);
            g.fillOval(x - 3.5, y - 3.5, 7, 7);
        }
        if (hovered) {
            g.setStroke(ACCENT.deriveColor(0, 1, 1, .5));
            g.setLineWidth(1);
            g.strokeOval(x - scale * .36, y - scale * .36, scale * .72, scale * .72);
        }
    }

    private static String subscript(int n) {
        StringBuilder s = new StringBuilder();
        for (char c : String.valueOf(n).toCharArray()) s.append((char) ('₀' + (c - '0')));
        return s.toString();
    }

    private void drawArrow(GraphicsContext g, int k) {
        Arrow r = doc.arrows.get(k);
        double x0 = sx(r.tx), y0 = sy(r.ty), x1 = sx(r.hx), y1 = sy(r.hy);
        double dx = x1 - x0, dy = y1 - y0, l = Math.hypot(dx, dy);
        if (l < 2) return;
        double ux = dx / l, uy = dy / l;
        Color colour = k == hoverArrow ? ACCENT : INK;
        g.setStroke(colour);
        g.setFill(colour);
        g.setLineWidth(Math.max(1.3, scale * .045));
        double head = Math.max(7, scale * .3);
        g.strokeLine(x0, y0, x1 - ux * head * .8, y1 - uy * head * .8);
        g.fillPolygon(new double[]{x1, x1 - ux * head - uy * head * .38, x1 - ux * head + uy * head * .38},
                new double[]{y1, y1 - uy * head + ux * head * .38, y1 - uy * head - ux * head * .38}, 3);
        // Le texte : au-dessus (ou à droite d'une flèche verticale), et au-dessous.
        double nx = uy, ny = -ux;
        if (ny > 0 || Math.abs(ny) < 1e-6 && nx < 0) { nx = -nx; ny = -ny; }
        boolean vertical = Math.abs(uy) > .8;
        double mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
        g.setFont(Plan.sans(Math.max(10, scale * .38)));
        g.setFill(INK);
        g.setTextAlign(vertical ? TextAlignment.LEFT : TextAlignment.CENTER);
        if (!r.above.isEmpty()) g.fillText(chem(r.above), mx + nx * 10 + (vertical ? 4 : 0), my + ny * 10 + (vertical ? 4 : -2));
        g.setFill(INK2);
        if (!r.below.isEmpty()) {
            g.setTextAlign(vertical ? TextAlignment.RIGHT : TextAlignment.CENTER);
            g.fillText(chem(r.below), mx - nx * 10 - (vertical ? 4 : 0), my - ny * 10 + (vertical ? 4 : 14));
        }
        g.setTextAlign(TextAlignment.LEFT);
        // Le numéro de l'étape, une fois calculée.
        if (scheme != null) {
            for (int s = 0; s < scheme.getStepsCount(); s++) {
                MoleculeSchemeStep step = scheme.getSteps(s);
                if (step.getArrow() != k) continue;
                double cx = mx - nx * (r.below.isEmpty() ? 14 : 30), cy = my - ny * (r.below.isEmpty() ? 14 : 30) + (vertical ? 0 : 4);
                Color c = !step.getComputed() ? INK3 : step.getConfirmed() ? GOOD : BAD;
                g.setFill(highlight == s ? c : c.deriveColor(0, 1, 1, .25));
                g.fillOval(cx - 9, cy - 9, 18, 18);
                g.setFill(highlight == s ? BACK : c);
                g.setFont(Plan.semibold(10.5));
                g.setTextAlign(TextAlignment.CENTER);
                g.fillText(String.valueOf(s + 1), cx, cy + 4);
                g.setTextAlign(TextAlignment.LEFT);
            }
        }
    }

    /** Les chiffres d'une formule en indices : Co2(CO)8 → Co₂(CO)₈ (pas ceux d'une température ni d'un nombre seul). */
    static String chem(String text) {
        StringBuilder s = new StringBuilder();
        char[] c = text.toCharArray();
        for (int k = 0; k < c.length; k++) {
            boolean digit = Character.isDigit(c[k]);
            boolean afterLetter = k > 0 && (Character.isLetter(c[k - 1]) || c[k - 1] == ')' || (Character.isDigit(c[k - 1]) && s.length() > 0 && s.charAt(s.length() - 1) >= '₀'));
            if (digit && afterLetter) s.append((char) ('₀' + (c[k] - '0')));
            else s.append(c[k]);
        }
        return s.toString();
    }

    /** Les points de contrôle d'une flèche courbe (repère du dessin), ou null si ses atomes n'existent plus. */
    private double[][] curlyPoints(Curly c) {
        for (int k : c.from) if (k < 0 || k >= doc.atoms.size()) return null;
        for (int k : c.to) if (k < 0 || k >= doc.atoms.size()) return null;
        double[] s = endpoint(c.from), t = endpoint(c.to);
        if (c.from.length == 1) {
            // Le doublet : un peu à l'écart de l'atome, du côté libre.
            double a = freeAngle(c.from[0]);
            s = new double[]{s[0] + .28 * Math.cos(a), s[1] + .28 * Math.sin(a)};
        }
        if (c.to.length == 1) {
            double a = freeAngle(c.to[0]);
            t = new double[]{t[0] + .3 * Math.cos(a), t[1] + .3 * Math.sin(a)};
        }
        double dx = t[0] - s[0], dy = t[1] - s[1], l = Math.max(.3, Math.hypot(dx, dy));
        double nx = -dy / l, ny = dx / l, bow = .35 * l + .25;
        // Le côté de la courbe : à l'écart du centre de la molécule.
        double cx = 0, cy = 0;
        for (int k : c.from) { cx += doc.atoms.get(k).x; cy += doc.atoms.get(k).y; }
        cx /= c.from.length;
        cy /= c.from.length;
        List<Integer> mol = molecule(c.from[0]);
        double mx = 0, my = 0;
        for (int k : mol) { mx += doc.atoms.get(k).x; my += doc.atoms.get(k).y; }
        mx /= mol.size();
        my /= mol.size();
        if ((cx - mx) * nx + (cy - my) * ny < 0) { nx = -nx; ny = -ny; }
        return new double[][]{s, {s[0] + dx * .2 + nx * bow, s[1] + dy * .2 + ny * bow},
                {s[0] + dx * .8 + nx * bow, s[1] + dy * .8 + ny * bow}, t};
    }

    private double[] endpoint(int[] atoms) {
        double x = 0, y = 0;
        for (int k : atoms) { x += doc.atoms.get(k).x; y += doc.atoms.get(k).y; }
        return new double[]{x / atoms.length, y / atoms.length};
    }

    private static double[] bezier(double[][] p, double t) {
        double u = 1 - t;
        return new double[]{u * u * u * p[0][0] + 3 * u * u * t * p[1][0] + 3 * u * t * t * p[2][0] + t * t * t * p[3][0],
                u * u * u * p[0][1] + 3 * u * u * t * p[1][1] + 3 * u * t * t * p[2][1] + t * t * t * p[3][1]};
    }

    private void drawCurly(GraphicsContext g, double[][] p, Color colour) {
        g.setStroke(colour);
        g.setFill(colour);
        g.setLineWidth(Math.max(1.2, scale * .04));
        g.beginPath();
        g.moveTo(sx(p[0][0]), sy(p[0][1]));
        g.bezierCurveTo(sx(p[1][0]), sy(p[1][1]), sx(p[2][0]), sy(p[2][1]), sx(p[3][0]), sy(p[3][1]));
        g.stroke();
        double[] a = bezier(p, .93);
        double x1 = sx(p[3][0]), y1 = sy(p[3][1]), dx = x1 - sx(a[0]), dy = y1 - sy(a[1]), l = Math.max(1e-6, Math.hypot(dx, dy));
        double ux = dx / l, uy = dy / l, head = Math.max(6, scale * .24);
        g.fillPolygon(new double[]{x1, x1 - ux * head - uy * head * .4, x1 - ux * head + uy * head * .4},
                new double[]{y1, y1 - uy * head + ux * head * .4, y1 - uy * head - ux * head * .4}, 3);
    }

    /** Ce que le geste en cours ferait : la liaison, la flèche ou la flèche courbe fantôme. */
    private void preview(GraphicsContext g) {
        if (!dragging || panning || Double.isNaN(mouseX)) return;
        g.setLineDashes(4, 4);
        if (tool == Tool.BOND && (pressAtom >= 0 || pressBond < 0)) {
            double x0 = pressAtom >= 0 ? sx(doc.atoms.get(pressAtom).x) : pressX, y0 = pressAtom >= 0 ? sy(doc.atoms.get(pressAtom).y) : pressY;
            double x1, y1;
            if (hoverAtom >= 0 && hoverAtom != pressAtom) {
                x1 = sx(doc.atoms.get(hoverAtom).x);
                y1 = sy(doc.atoms.get(hoverAtom).y);
            } else {
                double angle = Math.round(Math.atan2(mouseY - y0, mouseX - x0) / (Math.PI / 6)) * (Math.PI / 6);
                x1 = x0 + scale * Math.cos(angle);
                y1 = y0 + scale * Math.sin(angle);
            }
            g.setStroke(ACCENT);
            g.setLineWidth(1.5);
            g.strokeLine(x0, y0, x1, y1);
        } else if (tool == Tool.ARROW) {
            g.setStroke(ACCENT);
            g.setLineWidth(1.5);
            g.strokeLine(pressX, pressY, mouseX, mouseY);
        } else if (tool == Tool.CURLY && (pressAtom >= 0 || pressBond >= 0)) {
            double[] s = pressAtom >= 0 ? new double[]{doc.atoms.get(pressAtom).x, doc.atoms.get(pressAtom).y}
                    : endpoint(new int[]{doc.bonds.get(pressBond).a, doc.bonds.get(pressBond).b});
            double[] t = {wx(mouseX), wy(mouseY)};
            double dx = t[0] - s[0], dy = t[1] - s[1], l = Math.max(.3, Math.hypot(dx, dy));
            double nx = -dy / l * (.35 * l + .25), ny = dx / l * (.35 * l + .25);
            g.setLineDashes(null);
            drawCurly(g, new double[][]{s, {s[0] + dx * .2 + nx, s[1] + dy * .2 + ny}, {s[0] + dx * .8 + nx, s[1] + dy * .8 + ny}, t},
                    CURLY.deriveColor(0, 1, 1, .6));
        }
        g.setLineDashes(null);
    }
}
