package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeScheme;
import com.studio.genesis.grpc.MoleculeSchemeLine;
import com.studio.genesis.grpc.MoleculeSchemeLive;
import org.genesis.app.I18n;
import org.genesis.common.Plan;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * L'écran de calcul de l'éditeur de schémas : pendant que le moteur calcule, ce qu'il calcule. Sur le dessin (getNode),
 * la géométrie en cours de relaxation — chaque pas de L-BFGS, rejoint en douceur, la vue tournant lentement —, les
 * distances que le chemin imposé tire en tirets jaunes, et le profil qui se remplit palier par palier (à rebours depuis
 * le produit, puis à l'endroit depuis les réactifs). Dans la colonne de droite (journalNode), le journal : chaque calcul
 * fini, sur quelle molécule, le nombre qui en sort. Tout vient du moteur (MoleculeScheme.live, MoleculeScheme.log) : rien
 * n'est simulé ici.
 */
final class SchemeConsole {
    static final double HEIGHT = 228;
    private static final Color INK = Plan.INK, INK2 = Plan.INK2, INK3 = Plan.INK3, ACCENT = Color.web("#4FD1A9"),
            PULL = Color.web("#F2C14E"), BACK = Color.web("#0F1217");
    private static final double ROW = 34;

    private final VBox box = new VBox(8);
    private final Label stats = Plan.note("");
    private final Canvas molecule = new Canvas(270, 176), profile = new Canvas(280, 176),
            journal = new Canvas(Plan.RIGHT_WIDTH - 16, 6 * ROW + 6);
    private final Text probe = new Text();
    private List<MoleculeSchemeLine> lines = List.of();
    private double[] shown = new double[0], target = new double[0], backward = new double[0], forward = new double[0];
    private int[] numbers = new int[0], bonds = new int[0], pulled = new int[0];
    private String caption = "";
    private double energy, yaw = .7, pitch = .32, scale, clock, lineAt = -10, startedAt, doneAt = -1;
    private int calls;
    private boolean busy, forwardLast, wanted;
    /** Le moteur a fini : l'écran reste encore un moment (le bilan se lit), puis se replie. */
    private static final double LINGER = 3.5;

    /** « Le calcul en direct », puis « Le calcul est fini » pendant que l'écran se replie. */
    private final Label title = new Label("Le calcul en direct");

    SchemeConsole() {
        title.getStyleClass().add("plan-heading");
        title.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button close = Plan.icon("close", null, "Replier l'écran de calcul (le calcul continue)");
        close.setOnAction(e -> hide());
        HBox head = new HBox(10, title, gap, stats, close);
        head.setAlignment(Pos.CENTER_LEFT);
        Pane holder = new Pane(profile);
        holder.setMinWidth(160);
        profile.widthProperty().bind(holder.widthProperty());
        HBox.setHgrow(holder, Priority.ALWAYS);
        HBox body = new HBox(14, molecule, holder);
        box.getChildren().addAll(head, body);
        box.setPadding(new Insets(10, 14, 12, 14));
        box.setStyle("-fx-background-color: rgba(15,18,23,0.96); -fx-background-radius: 10; -fx-border-color: rgba(255,255,255,0.09);"
                + " -fx-border-radius: 10;");
        box.setPrefHeight(HEIGHT);
        box.setMaxHeight(HEIGHT);
        box.setVisible(false);
        journal.setVisible(false);
        journal.setManaged(false);
    }

    /** L'écran posé sur le dessin : la géométrie en cours et le profil. */
    Region getNode() {
        return box;
    }

    /** Le journal, pour la colonne de droite (sous l'avancement). */
    Canvas journalNode() {
        return journal;
    }

    /** Montrer ou cacher le journal du dernier calcul (le bouton « Journal du calcul »). */
    void showJournal(boolean shown) {
        journal.setVisible(shown);
        journal.setManaged(shown);
        if (shown) drawJournal();
    }

    boolean journalShown() {
        return journal.isVisible();
    }

    void hide() {
        wanted = false;
        box.setVisible(false);
    }

    boolean hasLog() {
        return !lines.isEmpty();
    }

    /** Un calcul demandé : l'écran s'ouvre, vide, jusqu'aux premières lignes du moteur. */
    void start() {
        lines = List.of();
        shown = target = backward = forward = new double[0];
        numbers = bonds = pulled = new int[0];
        caption = "";
        calls = 0;
        scale = 0;
        busy = true;
        startedAt = clock;
        doneAt = -1;
        wanted = true;
        box.setVisible(true);
        showJournal(true);
        draw();
    }

    /** Ce que le moteur publie (MoleculeScheme de la demande en cours). */
    void update(MoleculeScheme s) {
        boolean was = busy;
        busy = !s.getDone();
        if (was && !busy) doneAt = clock;
        // Une étape non calculée (un dessin refusé : un carbone à cinq liaisons) : l'écran ne dit pas « fini ».
        boolean failed = false;
        for (com.studio.genesis.grpc.MoleculeSchemeStep step : s.getStepsList()) failed |= !step.getComputed();
        title.setText(I18n.text(busy ? "Le calcul en direct" : failed ? "Le calcul s'est arrêté : voir l'étape refusée"
                : "Le calcul est fini"));
        if (s.getLogCount() != lines.size() || (!lines.isEmpty() && s.getLogCount() > 0
                && s.getLog(s.getLogCount() - 1).getTimeS() != lines.get(lines.size() - 1).getTimeS())) {
            lines = new ArrayList<>(s.getLogList());
            lineAt = clock;
        }
        if (s.hasLive() && s.getLive().getNumbersCount() > 0) {
            MoleculeSchemeLive live = s.getLive();
            int[] z = live.getNumbersList().stream().mapToInt(Integer::intValue).toArray();
            double[] x = live.getPositionsList().stream().mapToDouble(Double::doubleValue).toArray();
            if (!Arrays.equals(z, numbers) || x.length != shown.length) {
                numbers = z;
                shown = x.clone();
                scale = 0;
            }
            target = x;
            bonds = live.getBondsList().stream().mapToInt(Integer::intValue).toArray();
            pulled = live.getPulledList().stream().mapToInt(Integer::intValue).toArray();
            caption = live.getCaption();
            energy = live.getEnergyEh();
            calls = live.getCalls();
            double[] b = live.getBackwardList().stream().mapToDouble(Double::doubleValue).toArray();
            double[] f = live.getForwardList().stream().mapToDouble(Double::doubleValue).toArray();
            if (f.length > forward.length) forwardLast = true;
            else if (b.length > backward.length) forwardLast = false;
            backward = b;
            forward = f;
        }
        String elapsed = busy ? MoleculeFormat.decimal(clock - startedAt, 0) + " s" : "";
        String rest = busy && s.getRemainingS() > 1 ? I18n.text(" · encore ≈ ") + Math.round(s.getRemainingS()) + " s" : "";
        stats.setText(busy ? calls + " " + I18n.text("calculs GFN1-xTB") + " · " + elapsed + rest : I18n.text("Calcul terminé"));
        draw();
    }

    /** L'horloge de la vue : la molécule rejoint la dernière géométrie et tourne ; l'écran se replie après le bilan. */
    void tick(double dt) {
        clock += dt;
        if (!box.isVisible()) {
            if (journal.isVisible() && clock - lineAt < 1) drawJournal();
            return;
        }
        // Fini (même si la fin est passée pendant que l'écran était caché) : il se replie après LINGER.
        if (!busy && doneAt < 0 && wanted) doneAt = clock;
        if (doneAt >= 0 && clock - doneAt > LINGER && wanted) {
            doneAt = -1;
            hide();
            return;
        }
        yaw += dt * .28;
        double k = 1 - Math.exp(-dt * 7);
        for (int i = 0; i < shown.length && i < target.length; i++) shown[i] += (target[i] - shown[i]) * k;
        draw();
    }

    private void draw() {
        if (journal.isVisible()) drawJournal();
        if (!box.isVisible()) return;
        drawMolecule();
        drawProfile();
    }

    // ================================================================== la géométrie en cours
    private void drawMolecule() {
        GraphicsContext g = molecule.getGraphicsContext2D();
        double w = molecule.getWidth(), h = molecule.getHeight();
        g.setFill(new RadialGradient(0, 0, w / 2, h / 2, Math.max(w, h) * .7, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#172636")), new Stop(1, BACK)));
        g.fillRoundRect(0, 0, w, h, 8, 8);
        g.setTextBaseline(VPos.BASELINE);
        if (shown.length < 3) {
            g.setFont(Font.font(Plan.SANS, 11.5));
            g.setFill(INK3);
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText(I18n.text(busy ? "Lecture du schéma…" : "Aucun calcul en cours"), w / 2, h / 2);
            return;
        }
        int n = shown.length / 3;
        double reach = 1.2;
        for (int i = 0; i < n; i++) reach = Math.max(reach, Math.hypot(shown[3 * i], Math.hypot(shown[3 * i + 1], shown[3 * i + 2])));
        double fit = (Math.min(w, h - 34) / 2 - 8) / (reach + .5);
        scale = scale <= 0 ? fit : scale + (fit - scale) * .08;
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double[][] p = new double[n][];
        for (int i = 0; i < n; i++) {
            double x = shown[3 * i], y = shown[3 * i + 1], z = shown[3 * i + 2];
            double x1 = cy * x + sy * z, z1 = -sy * x + cy * z;
            double y2 = cp * y - sp * z1, z2 = sp * y + cp * z1;
            p[i] = new double[]{w / 2 + x1 * scale, h / 2 + 4 - y2 * scale, z2};
        }
        double depth = reach + .5;
        // Les liaisons, du fond vers l'avant.
        g.setLineCap(StrokeLineCap.ROUND);
        List<int[]> pairs = new ArrayList<>();
        for (int k = 0; k + 1 < bonds.length; k += 2) if (bonds[k] < n && bonds[k + 1] < n) pairs.add(new int[]{bonds[k], bonds[k + 1]});
        pairs.sort((u, v) -> Double.compare(p[u[0]][2] + p[u[1]][2], p[v[0]][2] + p[v[1]][2]));
        for (int[] pair : pairs) {
            double front = (p[pair[0]][2] + p[pair[1]][2]) / (4 * depth) + .5;
            g.setStroke(Color.web("#C9D6E6", .25 + .55 * front));
            g.setLineWidth(1.6 + 1.4 * front);
            g.strokeLine(p[pair[0]][0], p[pair[0]][1], p[pair[1]][0], p[pair[1]][1]);
        }
        // Les distances que le chemin imposé tire.
        g.setFont(Font.font(Plan.SANS_SEMIBOLD, 10));
        g.setTextAlign(TextAlignment.CENTER);
        for (int k = 0; k + 1 < pulled.length; k += 2) {
            int a = pulled[k], b = pulled[k + 1];
            if (a >= n || b >= n) continue;
            g.setStroke(PULL.deriveColor(0, 1, 1, .85));
            g.setLineWidth(1.3);
            g.setLineDashes(4, 3);
            g.strokeLine(p[a][0], p[a][1], p[b][0], p[b][1]);
            g.setLineDashes();
            double d = Math.sqrt(Math.pow(shown[3 * a] - shown[3 * b], 2) + Math.pow(shown[3 * a + 1] - shown[3 * b + 1], 2)
                    + Math.pow(shown[3 * a + 2] - shown[3 * b + 2], 2));
            if (k == 0) {
                g.setFill(PULL);
                g.fillText(MoleculeFormat.decimal(d, 2) + " Å", (p[a][0] + p[b][0]) / 2, (p[a][1] + p[b][1]) / 2 - 5);
            }
        }
        // Les atomes, du fond vers l'avant : la couleur de l'élément, ombrée.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (u, v) -> Double.compare(p[u][2], p[v][2]));
        for (int i : order) {
            int z = i < numbers.length ? numbers[i] : 6;
            double r = Math.max(2.6, Math.min(11, scale * (z == 1 ? .22 : z <= 10 ? .34 : z <= 18 ? .42 : .48)));
            double front = p[i][2] / (2 * depth) + .5;
            Color c = MoleculeScene.elementColor(z).deriveColor(0, 1, .55 + .45 * front, 1);
            g.setFill(new RadialGradient(0, 0, p[i][0] - r * .35, p[i][1] - r * .35, r * 1.4, false, CycleMethod.NO_CYCLE,
                    new Stop(0, c.interpolate(Color.WHITE, .45)), new Stop(.55, c), new Stop(1, c.darker().darker())));
            g.fillOval(p[i][0] - r, p[i][1] - r, 2 * r, 2 * r);
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font(Plan.SANS, 11));
        g.setFill(INK2);
        g.fillText(fit(subject(caption), w - 16, Font.font(Plan.SANS, 11)), 8, 15);
        g.setFont(Font.font(Plan.SANS_SEMIBOLD, 11));
        g.setFill(INK);
        if (energy != 0) g.fillText("E " + MoleculeFormat.decimal(energy, 5) + " Eh", 8, h - 8);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFont(Font.font(Plan.SANS, 10.5));
        g.setFill(INK3);
        g.fillText(n + " " + I18n.text(n > 1 ? "atomes" : "atome"), w - 8, h - 8);
    }

    // ================================================================== le profil qui se remplit
    private void drawProfile() {
        GraphicsContext g = profile.getGraphicsContext2D();
        double w = profile.getWidth(), h = profile.getHeight();
        g.setFill(BACK);
        g.fillRoundRect(0, 0, w, h, 8, 8);
        g.setTextBaseline(VPos.BASELINE);
        double left = 10, right = w - 10, top = 40, bottom = h - 24;
        g.setFont(Font.font(Plan.SANS, 11));
        g.setFill(INK2);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(I18n.text("Le chemin imposé, palier par palier"), 8, 15);
        g.setFont(Font.font(Plan.SANS, 10));
        g.setFill(INK3);
        g.fillText(I18n.text("réactifs"), left, h - 8);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(I18n.text("produits"), right, h - 8);
        if (backward.length + forward.length < 2) {
            // Aligné sur l'axe, à gauche du graphique : rien ne le colle au nombre d'atomes de l'écran voisin.
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font(Plan.SANS, 11));
            g.fillText(fit(I18n.text("Il se remplit quand le chemin commence"), right - left, Font.font(Plan.SANS, 11)), left,
                    (top + bottom) / 2 + 4);
            return;
        }
        double lo = 0, hi = 0;
        for (double[] list : new double[][]{backward, forward}) {
            for (int k = 1; k < list.length; k += 2) {
                lo = Math.min(lo, list[k]);
                hi = Math.max(hi, list[k]);
            }
        }
        double pad = Math.max(.15, (hi - lo) * .12);
        final double low = lo - pad, high = hi + pad;
        java.util.function.DoubleUnaryOperator X = x -> left + x * (right - left);
        java.util.function.DoubleUnaryOperator Y = v -> bottom - (v - low) / (high - low) * (bottom - top);
        g.setStroke(Color.web("#FFFFFF", .10));
        g.setLineWidth(1);
        g.setLineDashes(3, 4);
        g.strokeLine(left, Y.applyAsDouble(0), right, Y.applyAsDouble(0));
        g.setLineDashes();
        g.setFill(INK3);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(MoleculeFormat.signed(hi, 2) + " eV", left, top - 6);
        series(g, backward, INK2, false, X, Y);
        series(g, forward, ACCENT, true, X, Y);
        double[] last = forwardLast && forward.length >= 2 ? forward : backward;
        if (busy && last.length >= 2) {
            double px = X.applyAsDouble(last[last.length - 2]), py = Y.applyAsDouble(last[last.length - 1]);
            double phase = (clock * 1.3) % 1;
            g.setStroke((forwardLast ? ACCENT : INK2).deriveColor(0, 1, 1, .8 * (1 - phase)));
            g.setLineWidth(1.2);
            double r = 4 + 7 * phase;
            g.strokeOval(px - r, py - r, 2 * r, 2 * r);
        }
        g.setFont(Font.font(Plan.SANS, 10));
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(INK2);
        String after = "● " + I18n.text("à l'endroit");
        g.fillText("○ " + I18n.text("à rebours"), right - width(after, Font.font(Plan.SANS, 10)) - 12, 31);
        g.setFill(ACCENT);
        g.fillText(after, right, 31);
    }

    private static void series(GraphicsContext g, double[] list, Color colour, boolean filled,
                               java.util.function.DoubleUnaryOperator X, java.util.function.DoubleUnaryOperator Y) {
        int m = list.length / 2;
        if (m == 0) return;
        Integer[] order = new Integer[m];
        for (int k = 0; k < m; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> Double.compare(list[2 * a], list[2 * b]));
        double[] xs = new double[m], ys = new double[m];
        for (int k = 0; k < m; k++) {
            xs[k] = X.applyAsDouble(list[2 * order[k]]);
            ys[k] = Y.applyAsDouble(list[2 * order[k] + 1]);
        }
        g.setStroke(colour.deriveColor(0, 1, 1, .55));
        g.setLineWidth(1.3);
        g.strokePolyline(xs, ys, m);
        for (int k = 0; k < m; k++) {
            if (filled) {
                g.setFill(colour);
                g.fillOval(xs[k] - 3, ys[k] - 3, 6, 6);
            } else {
                g.setFill(BACK);
                g.fillOval(xs[k] - 3, ys[k] - 3, 6, 6);
                g.setStroke(colour);
                g.setLineWidth(1.1);
                g.strokeOval(xs[k] - 3, ys[k] - 3, 6, 6);
            }
        }
    }

    // ================================================================== le journal
    private void drawJournal() {
        GraphicsContext g = journal.getGraphicsContext2D();
        double w = journal.getWidth(), h = journal.getHeight();
        g.clearRect(0, 0, w, h);
        g.setFill(BACK);
        g.fillRoundRect(0, 0, w, h, 8, 8);
        g.setTextBaseline(VPos.BASELINE);
        if (lines.isEmpty()) {
            g.setFont(Font.font(Plan.SANS, 11));
            g.setFill(INK3);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(I18n.text("Envoyé au moteur…"), 10, 20);
            return;
        }
        // Chaque ligne sur deux étages : ce qui a été calculé et le nombre qui en sort ; la molécule, l'étape, l'heure.
        int rows = Math.max(1, (int) ((h - 6) / ROW));
        int from = Math.max(0, lines.size() - rows);
        Font small = Font.font(Plan.SANS, 10.5), body = Font.font(Plan.SANS, 11.5), strong = Font.font(Plan.SANS_SEMIBOLD, 11.5);
        double flash = Math.max(0, 1 - (clock - lineAt) / .9);
        for (int i = from; i < lines.size(); i++) {
            MoleculeSchemeLine line = lines.get(i);
            int row = i - from;
            double y = 4 + row * ROW;
            double alpha = .45 + .55 * (row + 1.0) / Math.min(rows, lines.size() - from);
            if (i == lines.size() - 1 && flash > 0) {
                g.setFill(ACCENT.deriveColor(0, 1, 1, .12 * flash));
                g.fillRoundRect(3, y, w - 6, ROW - 2, 6, 6);
            }
            if (row > 0) {
                g.setStroke(Color.web("#FFFFFF", .05));
                g.setLineWidth(1);
                g.strokeLine(10, y, w - 10, y);
            }
            // En haut, ce qui a été calculé et quand ; en bas, sur quelle molécule et le nombre qui en sort.
            boolean summary = line.getStep() < 0 || line.getWhat().startsWith("étape");
            String when = MoleculeFormat.decimal(line.getTimeS(), 1) + " s"
                    + (line.getStep() >= 0 ? " · " + I18n.text("étape") + " " + (line.getStep() + 1) : "");
            g.setFont(small);
            double whenWidth = width(when, small);
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFill(INK3.deriveColor(0, 1, 1, alpha));
            g.fillText(when, w - 9, y + 14);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(body);
            g.setFill(INK2.deriveColor(0, 1, 1, alpha));
            g.fillText(fit(I18n.text(line.getWhat()), w - 18 - whenWidth - 10, body), 9, y + 14);
            String value = value(line.getValue());
            g.setFont(strong);
            String shownValue = fit(value, w - 18, strong);
            double valueWidth = width(shownValue, strong);
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFill((summary ? ACCENT : INK).deriveColor(0, 1, 1, alpha));
            g.fillText(shownValue, w - 9, y + 29);
            if (!line.getSubject().isEmpty() && w - 18 - valueWidth - 12 > 40) {
                g.setTextAlign(TextAlignment.LEFT);
                g.setFont(small);
                g.setFill(INK.deriveColor(0, 1, 1, .8 * alpha));
                g.fillText(fit(subject(line.getSubject()), w - 18 - valueWidth - 12, small), 9, y + 29);
            }
        }
    }

    // ================================================================== le texte
    /** « C₄H₆ · buta-1,3-diène + C₂H₄ · éthylène » : les noms traduits, les formules telles quelles. */
    private static String subject(String text) {
        if (I18n.language() == I18n.Language.FR || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder();
        String[] parts = text.split(" \\+ ");
        for (int k = 0; k < parts.length; k++) {
            if (k > 0) out.append(" + ");
            String[] bits = parts[k].split(" · ");
            for (int j = 0; j < bits.length; j++) {
                if (j > 0) out.append(" · ");
                out.append(I18n.text(bits[j]));
            }
        }
        return out.toString();
    }

    /** Un nombre du moteur (virgule décimale) : traduit, le point décimal en anglais. */
    private static String value(String text) {
        String out = I18n.text(text);
        return I18n.language() == I18n.Language.FR ? out : out.replaceAll("(?<=\\d),(?=\\d)", ".");
    }

    private double width(String text, Font font) {
        probe.setFont(font);
        probe.setText(text);
        return probe.getLayoutBounds().getWidth();
    }

    private String fit(String text, double room, Font font) {
        if (room <= 0) return "";
        if (width(text, font) <= room) return text;
        String cut = text;
        while (cut.length() > 1 && width(cut + "…", font) > room) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }
}
