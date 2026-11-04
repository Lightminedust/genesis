package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeLabState;
import org.genesis.app.I18n;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * La fiche d'une molécule, au clic droit dans la vue 3D : celle que le moteur a reconnue autour de l'atome cliqué.
 * <ul>
 *   <li>son nom (dictionnaire) et sa formule ;</li>
 *   <li>son dessin à plat, tourné comme la vue : les liaisons avec leurs traits, chaque atome teinté par sa charge
 *       (argent neutre, cyan δ+, magenta δ−), et son moment dipolaire en flèche (du δ+ vers le δ−, comme en chimie) ;</li>
 *   <li>sa masse molaire, son nombre d'atomes, sa charge ; les charges atome par atome en barres ; ses liaisons par
 *       sorte, avec leur longueur moyenne ;</li>
 *   <li>et ce qu'on peut lui faire : une étincelle sur l'atome cliqué, le retirer, retirer la molécule, la modifier dans
 *       l'assembleur 2D, voir la carte de l'atome.</li>
 * </ul>
 * Le moment dipolaire est Σ q·(r − centre de masse) + les dipôles atomiques du calcul ; celui d'un ion dépend de
 * l'origine (le centre de masse).
 */
final class MoleculeCard {
    interface Actions {
        void spark(int atom);
        void removeAtom(int atom);
        void removeMolecule(int[] atoms);
        void edit(int[] atoms);
        void select(int atom);
        void close();
        /** La vue se centre sur ces atomes (la molécule) et les suit. */
        default void centre(int[] atoms) {}
    }

    static final double W = 312;
    private static final double DRAW = 150;
    /** Debye par e·Å. */
    static final double DEBYE = 4.80320;
    private static final Color TEXT = Color.web("#DCE8F7"), GREY = Color.web("#8FA4C2"), DIM = Color.web("#5E718E"),
            EDGE = Color.web("#2A3B52"), LINE = Color.web("#C9D6E8");

    private final Actions actions;
    private final VBox root = new VBox(6);
    private final Label title = MoleculeView.text("", 14, "#EAF2FF"), subtitle = MoleculeView.text("", 10.5, "#8FA4C2");
    private final Canvas canvas = new Canvas(W - 20, 200);
    private final FlowPane buttons = new FlowPane(5, 5);
    private int atom = -1;
    private int[] atoms = new int[0];

    MoleculeCard(Actions actions) {
        this.actions = actions;
        title.setStyle(title.getStyle() + "-fx-font-weight:bold;");
        title.setWrapText(true);
        title.setMaxWidth(W - 60);
        Button close = MoleculeView.button("✕", false);
        close.setOnAction(e -> actions.close());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(6, new VBox(1, title, subtitle), gap, close);
        head.setAlignment(Pos.TOP_LEFT);
        buttons.setPrefWrapLength(W - 20);
        root.getChildren().addAll(head, canvas, buttons);
        root.setPadding(new Insets(10));
        root.setPrefWidth(W);
        root.setMaxWidth(W);
        root.setStyle("-fx-background-color:rgba(7,13,24,0.96);-fx-background-radius:12;-fx-border-color:#2A3B52;"
                + "-fx-border-radius:12;-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.55),18,0,0,4);");
        root.setOnMouseClicked(javafx.scene.input.MouseEvent::consume);
        root.setOnMousePressed(javafx.scene.input.MouseEvent::consume);
    }

    Region getNode() {
        return root;
    }

    int atom() {
        return atom;
    }

    int[] atoms() {
        return atoms.clone();
    }

    /** Remplit la fiche pour la molécule de l'atome cliqué. */
    void show(MoleculeLabState s, MoleculeScene scene, int clicked) {
        atom = clicked;
        atoms = scene.members(clicked);
        refresh(s, scene);
        String name = clicked < scene.symbols.length ? scene.symbols[clicked] + (clicked + 1) : "";
        buttons.getChildren().clear();
        Button spark = MoleculeView.button("✦ " + I18n.text("Étincelle sur") + " " + name, false);
        spark.setOnAction(e -> actions.spark(atom));
        Button inspect = MoleculeView.button(I18n.text("Carte de") + " " + name + " (09)", false);
        inspect.setOnAction(e -> actions.select(atom));
        Button edit = MoleculeView.button("✎ " + I18n.text("Modifier dans l'assembleur"), false);
        edit.setTooltip(I18n.tooltip("La molécule redessinée à plat dans l'assembleur 2D, pour la changer puis la renvoyer"));
        edit.setOnAction(e -> actions.edit(atoms.clone()));
        Button remove = MoleculeView.button(I18n.text("Retirer") + " " + name, false);
        remove.setOnAction(e -> actions.removeAtom(atom));
        Button removeAll = MoleculeView.button(I18n.text("Retirer la molécule"), false);
        removeAll.setOnAction(e -> actions.removeMolecule(atoms.clone()));
        Button centre = MoleculeView.button("⌖ " + I18n.text("Centrer la vue sur elle"), false);
        centre.setTooltip(I18n.tooltip("La vue la cadre et la suit pendant la dynamique (⌖ Tout voir, en bas à droite, pour revenir)"));
        centre.setOnAction(e -> actions.centre(atoms.clone()));
        buttons.getChildren().addAll(centre, spark, inspect, edit, remove);
        if (atoms.length > 1) buttons.getChildren().add(removeAll);
    }

    /** Redessine avec la frame courante (les charges et les distances bougent avec la dynamique). */
    void refresh(MoleculeLabState s, MoleculeScene scene) {
        if (atoms.length == 0 || atoms[atoms.length - 1] >= scene.positions.length) return;
        String label = "";
        for (MoleculeScene.Species species : scene.species) {
            if (java.util.Arrays.equals(java.util.Arrays.stream(species.atoms()).sorted().toArray(), atoms)) label = species.label();
        }
        String formula = formula(scene, atoms);
        int dot = label.indexOf(" · ");
        String name = dot < 0 ? "" : MoleculeFormat.names(label).substring(dot + 3);
        title.setText(name.isEmpty() ? formula : formula + "  ·  " + name);
        subtitle.setText(name.isEmpty() && atoms.length > 1 ? I18n.text("inconnue du dictionnaire")
                : atoms.length == 1 ? I18n.text("atome seul") : "");
        subtitle.setVisible(!subtitle.getText().isEmpty());
        subtitle.setManaged(!subtitle.getText().isEmpty());
        draw(s, scene);
    }

    private void draw(MoleculeLabState s, MoleculeScene scene) {
        int n = atoms.length;
        // Les liaisons de la molécule, par sorte (O–H simple, C=O double…) : nombre et longueur moyenne.
        Map<String, double[]> kinds = new LinkedHashMap<>();
        List<int[]> edges = new ArrayList<>();
        Map<Integer, Integer> local = new java.util.HashMap<>();
        for (int k = 0; k < n; k++) local.put(atoms[k], k);
        for (MoleculeScene.Bond b : scene.bondedBonds()) {
            Integer a = local.get(b.a()), c = local.get(b.b());
            if (a == null || c == null) continue;
            int strokes = MoleculeScene.strokes(b.order());
            edges.add(new int[]{a, c, strokes, MoleculeScene.partial(b.order()) ? 1 : 0});
            // Écrite comme en chimie : le carbone d'abord, l'hydrogène en dernier (C–H, O–H, C=O, N–H).
            String sa = scene.symbols[b.a()], sb = scene.symbols[b.b()];
            boolean swap = rank(scene.z[b.b()]) < rank(scene.z[b.a()])
                    || (rank(scene.z[b.b()]) == rank(scene.z[b.a()]) && sb.compareTo(sa) < 0);
            String key = (swap ? sb + "|" + sa : sa + "|" + sb) + "|" + strokes;
            double[] kind = kinds.computeIfAbsent(key, x -> new double[2]);
            kind[0]++;
            kind[1] += distance(scene.positions[b.a()], scene.positions[b.b()]);
        }
        int rowsOfBonds = Math.min(4, kinds.size());
        boolean bars = n <= 12;
        double height = (n > 1 ? DRAW + 8 : 0) + 34 + (bars ? 58 : 18) + (rowsOfBonds > 0 ? 16 + rowsOfBonds * 15 : 0) + 4;
        double w = W - 20;
        canvas.setHeight(height);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, height);
        g.setLineCap(StrokeLineCap.ROUND);
        double y = 0;
        double[] masses = new double[n];
        for (int k = 0; k < n; k++) masses[k] = s != null && atoms[k] < s.getAtomsCount() ? s.getAtoms(atoms[k]).getMassU() : 1;
        double[] dipole = dipole(scene, atoms, masses);
        if (n > 1) {
            drawing(g, scene, edges, dipole, w);
            y = DRAW + 8;
        }
        // La masse, le nombre d'atomes, la charge, le moment dipolaire.
        double mass = 0, charge = 0;
        for (int k = 0; k < n; k++) {
            mass += masses[k];
            charge += atoms[k] < scene.charges.length ? scene.charges[atoms[k]] : 0;
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11.5));
        g.setFill(TEXT);
        g.fillText(MoleculeFormat.decimal(mass, 3) + " g/mol  ·  " + n + " " + I18n.text(n > 1 ? "atomes" : "atome")
                + "  ·  " + I18n.text("charge") + " " + MoleculeFormat.signed(Math.round(charge * 100) / 100.0, 2) + " e", 0, y + 12);
        double mu = AtomCard.norm(dipole) * DEBYE;
        g.setFont(Font.font("Segoe UI", 10.5));
        g.setFill(DIPOLE);
        g.fillText(I18n.text("moment dipolaire") + "  " + MoleculeFormat.decimal(mu, 2) + " D"
                + (Math.abs(charge) >= .5 ? "  " + I18n.text("(un ion : par rapport à son centre de masse)") : ""), 0, y + 27);
        y += 34;
        // Les charges, atome par atome (ou par élément, en moyenne, pour une grande molécule).
        if (bars) {
            chargeBars(g, scene, y, w);
            y += 58;
        } else {
            g.setFont(Font.font("Segoe UI", 10));
            g.setFill(GREY);
            g.fillText(extremes(scene), 0, y + 10);
            y += 18;
        }
        if (rowsOfBonds > 0) {
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
            g.setFill(GREY);
            g.fillText(I18n.text("LIAISONS"), 0, y + 9);
            y += 16;
            int shown = 0;
            for (var e : kinds.entrySet()) {
                if (shown++ >= rowsOfBonds) break;
                String[] parts = e.getKey().split("\\|");
                int strokes = Integer.parseInt(parts[2]);
                double[] v = e.getValue();
                g.setStroke(LINE);
                g.setLineWidth(1.2);
                for (int k = 0; k < strokes; k++) {
                    double off = (k - (strokes - 1) / 2.0) * 3;
                    g.strokeLine(2, y + 5 + off, 18, y + 5 + off);
                }
                g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5));
                g.setFill(TEXT);
                String bond = strokes == 1 ? "–" : strokes == 2 ? "=" : "≡";
                g.fillText((v[0] > 1 ? (int) v[0] + " × " : "") + parts[0] + bond + parts[1], 26, y + 9);
                g.setFont(Font.font("Segoe UI", 10));
                g.setFill(GREY);
                g.setTextAlign(TextAlignment.RIGHT);
                g.fillText(I18n.text(strokes == 1 ? "simple" : strokes == 2 ? "double" : "triple") + "  ·  "
                        + MoleculeFormat.decimal(v[1] / v[0], 3) + " Å", w, y + 9);
                g.setTextAlign(TextAlignment.LEFT);
                y += 15;
            }
        }
    }

    private static final Color DIPOLE = Color.web("#FFD27A");

    /**
     * Le dessin à plat de la molécule, tous ses atomes : la disposition du schéma 2D (liaisons égales, angles réguliers)
     * partie de la vue, puis tournée (et retournée s'il le faut) pour coller à la vue 3D — la flèche du dipôle, tracée
     * telle que la caméra la voit, y garde son sens.
     */
    private void drawing(GraphicsContext g, MoleculeScene scene, List<int[]> edges, double[] dipole, double w) {
        int n = atoms.length;
        double[] right = scene.camera.right(), up = scene.camera.up();
        if (AtomCard.norm(right) < .5) {
            right = new double[]{1, 0, 0};
            up = new double[]{0, 1, 0};
        }
        double[][] view = new double[n][];
        for (int k = 0; k < n; k++) {
            double[] p = scene.positions[atoms[k]];
            view[k] = new double[]{dot(p, right), dot(p, up)};
        }
        boolean[] drawn = new boolean[n];
        java.util.Arrays.fill(drawn, true);
        double[][] sheet = edges.isEmpty() ? view : MoleculeSketch.layout(view, drawn, edges);
        double[] fit = MoleculeSketch.fit(sheet, view, drawn);
        double angle = MoleculeSketch.procrustes(sheet, view, drawn, fit[1] > .5)[0];
        boolean mirror = fit[1] > .5;
        double cx = 0, cy = 0;
        for (double[] p : sheet) {
            cx += p[0] / n;
            cy += p[1] / n;
        }
        double radius = .6;
        for (double[] p : sheet) radius = Math.max(radius, Math.hypot(p[0] - cx, p[1] - cy) + .45);
        double scale = Math.min(46, Math.min(w / 2 - 14, DRAW / 2 - 12) / radius);
        double cos = Math.cos(angle), sin = Math.sin(angle), ox = w / 2, oy = DRAW / 2;
        double[][] xy = new double[n][];
        for (int k = 0; k < n; k++) {
            double x = (sheet[k][0] - cx) * (mirror ? -1 : 1), yy = sheet[k][1] - cy;
            xy[k] = new double[]{ox + scale * (cos * x - sin * yy), oy - scale * (sin * x + cos * yy)};
        }
        g.setFill(Color.web("#0A1424"));
        g.fillRoundRect(0, 0, w, DRAW, 10, 10);
        double r = Math.max(6, Math.min(11, scale * .26));
        for (int[] e : edges) {
            double[] p = xy[e[0]], q = xy[e[1]];
            double dx = q[0] - p[0], dy = q[1] - p[1], len = Math.hypot(dx, dy);
            if (len < 1) continue;
            double ux = dx / len, uy = dy / len;
            for (int k = 0; k < e[2]; k++) {
                double off = (k - (e[2] - 1) / 2.0) * 3.4;
                if (e[2] == 2 && k == 1 && e[3] == 1) g.setLineDashes(3, 2.5);
                g.setStroke(LINE.deriveColor(0, 1, 1, .85));
                g.setLineWidth(1.4);
                g.strokeLine(p[0] + ux * r - uy * off, p[1] + uy * r + ux * off, q[0] - ux * r - uy * off, q[1] - uy * r + ux * off);
                g.setLineDashes();
            }
        }
        for (int k = 0; k < n; k++) {
            int a = atoms[k];
            double q = a < scene.charges.length ? scene.charges[a] : 0;
            double rr = scene.z[a] == 1 ? r * .78 : r;
            Color rim = MoleculeScene.rimColour(q);
            g.setFill(rim.deriveColor(0, 1, .35, .35 + .4 * Math.abs(MoleculeScene.chargeTint(q))));
            g.fillOval(xy[k][0] - rr, xy[k][1] - rr, 2 * rr, 2 * rr);
            g.setStroke(a == atom ? Color.web("#FFE07A") : rim);
            g.setLineWidth(a == atom ? 1.8 : 1.1);
            g.strokeOval(xy[k][0] - rr, xy[k][1] - rr, 2 * rr, 2 * rr);
            g.setTextAlign(TextAlignment.CENTER);
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, scene.z[a] == 1 ? 9 : 10.5));
            g.setFill(MoleculeScene.elementColor(scene.z[a]).deriveColor(0, .7, 1.3, 1));
            g.fillText(scene.symbols[a], xy[k][0], xy[k][1] + (scene.z[a] == 1 ? 3.2 : 3.8));
            if (Math.abs(q) >= .25) {
                List<Double> around = new ArrayList<>();
                for (int[] e : edges) {
                    int other = e[0] == k ? e[1] : e[1] == k ? e[0] : -1;
                    if (other >= 0) around.add(Math.atan2(xy[other][1] - xy[k][1], xy[other][0] - xy[k][0]));
                }
                double at = MoleculeSketch.freeAngle(around);
                float[] c = MoleculeScene.chargeColour(q);
                g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 9));
                g.setFill(Color.color(c[0], c[1], c[2]));
                g.fillText(q < 0 ? "δ−" : "δ+", xy[k][0] + Math.cos(at) * (rr + 8), xy[k][1] + Math.sin(at) * (rr + 8) + 3);
            }
        }
        // Le dipôle, du δ+ vers le δ− : l'opposé du vecteur μ (qui va du − vers le +), vu comme la caméra le voit ;
        // dans le coin, à côté du dessin plutôt que sur ses atomes.
        double mu = AtomCard.norm(dipole) * DEBYE;
        if (mu >= .05) {
            double fx = -dot(dipole, right), fy = dot(dipole, up), len = Math.hypot(fx, fy);
            if (len > 1e-9) {
                double size = Math.min(44, 14 + 9 * mu), ax = 34, ay = DRAW - 30;
                double sx = ax - fx / len * size / 2, sy = ay - fy / len * size / 2;
                double ex = ax + fx / len * size / 2, ey = ay + fy / len * size / 2;
                MoleculeScene.arrow(g, sx, sy, ex, ey, DIPOLE.deriveColor(0, 1, 1, .9), 1.8);
                // La croix du δ+, à la queue de la flèche.
                double px = -fy / len * 4, py = fx / len * 4;
                g.setStroke(DIPOLE.deriveColor(0, 1, 1, .9));
                g.setLineWidth(1.6);
                g.strokeLine(sx + fx / len * 5 - px, sy + fy / len * 5 - py, sx + fx / len * 5 + px, sy + fy / len * 5 + py);
                g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10));
                g.setFill(DIPOLE);
                g.setTextAlign(TextAlignment.LEFT);
                g.fillText("μ", ex + 4, ey - 3);
            }
        }
        g.setFont(Font.font("Segoe UI", FontPosture.ITALIC, 8.5));
        g.setFill(DIM);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(I18n.text("tournée comme la vue"), w - 6, DRAW - 6);
    }

    /** Les charges en barres autour du zéro : vers le haut δ+ (cyan), vers le bas δ− (magenta), l'atome dessous. */
    private void chargeBars(GraphicsContext g, MoleculeScene scene, double y, double w) {
        int n = atoms.length;
        double top = y + 4, zero = y + 24, half = 18;
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
        g.setFill(GREY);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(I18n.text("CHARGES"), 0, top + 6);
        double x0 = 58, step = Math.min(22, (w - x0) / Math.max(1, n));
        g.setStroke(EDGE.brighter());
        g.setLineWidth(.8);
        g.strokeLine(x0 - 4, zero, x0 + step * n, zero);
        for (int k = 0; k < n; k++) {
            int a = atoms[k];
            double q = a < scene.charges.length ? scene.charges[a] : 0;
            double h = Math.max(1, Math.min(1, Math.abs(q) / .8) * half);
            float[] c = MoleculeScene.chargeColour(q);
            g.setFill(Color.color(c[0], c[1], c[2], .9));
            double x = x0 + k * step + step * .2;
            g.fillRoundRect(x, q >= 0 ? zero - h : zero, step * .6, h, 3, 3);
            g.setTextAlign(TextAlignment.CENTER);
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
            g.setFill(a == atom ? Color.web("#FFE07A") : MoleculeScene.elementColor(scene.z[a]).deriveColor(0, .8, 1.25, 1));
            g.fillText(scene.symbols[a], x + step * .3, zero + half + 14);
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", 8));
        g.setFill(DIM);
        g.fillText("δ+", 30, zero - 6);
        g.fillText("δ−", 30, zero + 12);
    }

    /** Pour une grande molécule : l'atome le plus δ− et le plus δ+. */
    private String extremes(MoleculeScene scene) {
        int low = atoms[0], high = atoms[0];
        for (int a : atoms) {
            if (scene.charges[a] < scene.charges[low]) low = a;
            if (scene.charges[a] > scene.charges[high]) high = a;
        }
        return I18n.text("le plus δ−") + " " + scene.symbols[low] + (low + 1) + " " + MoleculeFormat.signed(scene.charges[low], 2)
                + "  ·  " + I18n.text("le plus δ+") + " " + scene.symbols[high] + (high + 1) + " "
                + MoleculeFormat.signed(scene.charges[high], 2);
    }

    /** Le moment dipolaire (e·Å) : Σ q·(r − centre de masse) + les dipôles atomiques du calcul. */
    static double[] dipole(MoleculeScene scene, int[] atoms, double[] masses) {
        double[] c = new double[3];
        double total = 0;
        for (int j = 0; j < atoms.length; j++) {
            total += masses[j];
            for (int k = 0; k < 3; k++) c[k] += masses[j] * scene.positions[atoms[j]][k];
        }
        for (int k = 0; k < 3; k++) c[k] /= Math.max(1e-9, total);
        double[] mu = new double[3];
        for (int a : atoms) {
            double q = a < scene.charges.length ? scene.charges[a] : 0;
            double[] p = a < scene.polarization.length && scene.polarization[a] != null ? scene.polarization[a] : new double[3];
            for (int k = 0; k < 3; k++) mu[k] += q * (scene.positions[a][k] - c[k]) + p[k];
        }
        return mu;
    }

    /** La formule d'un groupe d'atomes, dans l'ordre de Hill (C, H, puis l'alphabet) : C₂H₆O, H₂O. */
    static String formula(MoleculeScene scene, int[] atoms) {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (int a : atoms) counts.merge(scene.z[a], 1, Integer::sum);
        return MoleculeAssembler.hill(counts);
    }

    /** L'ordre d'écriture d'une liaison : le carbone, puis les autres éléments, l'hydrogène en dernier. */
    private static int rank(int z) {
        return z == 6 ? 0 : z == 1 ? 2 : 1;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }
}
