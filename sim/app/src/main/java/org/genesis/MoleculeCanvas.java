package org.genesis;

import com.studio.genesis.grpc.BondPoint;
import com.studio.genesis.grpc.ChemistryState;
import com.studio.genesis.grpc.OrbitalLevel;
import com.studio.genesis.grpc.OrbitalSet;
import com.studio.genesis.grpc.SimEvent;
import com.studio.genesis.grpc.UniverseFrame;

import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * L'échelle de la molécule : deux atomes, et ce que font leurs électrons.
 *
 * Tout ce qui est dessiné vient du calcul quantique du moteur (DFT, PySCF) :
 * la densité électronique dans le plan des noyaux, sa différence avec les
 * atomes isolés (où les électrons sont allés), la courbe d'énergie E(R),
 * les orbitales moléculaires et leurs électrons, spin par spin. On attrape
 * un noyau, on le rapproche ou on l'éloigne ; on le lâche, et la vraie force
 * quantique décide : ils vibrent (liaison) ou s'écartent.
 */
public class MoleculeCanvas {

    private static final Color BACKGROUND = Color.web("#050812");
    private static final Color CARD = Color.web("#0C1324", 0.92);
    private static final Color CARD_BORDER = Color.web("#22304A");
    private static final Color TEXT = Color.web("#E6EDF7");
    private static final Color SOFT = Color.web("#A9B6CB");
    private static final Color MUTED = Color.web("#64748B");
    private static final Color FORMULA = Color.web("#FCD34D");
    private static final Color SPIN_UP = Color.web("#FDE68A");
    private static final Color SPIN_DOWN = Color.web("#F9A8D4");
    private static final Color GAIN = Color.web("#3B82F6");
    private static final Color LOSS = Color.web("#EF4444");
    private static final String UI = "Segoe UI";
    private static final String MONO = "Consolas";

    private static final double PANEL_WIDTH = 330;
    private static final double HEADER = 78;
    private static final double FOOTER = 40;

    private final Canvas canvas = new Canvas(800, 600);
    private final GraphicsContext gc = canvas.getGraphicsContext2D();
    private final DoubleConsumer onHold;
    private final Runnable onRelease;

    private ChemistryState state;
    private final List<BondPoint> points = new ArrayList<>();
    private int tableKey = -1;
    private String tablePair = "";
    private boolean showDifference;
    private double shownR = Double.NaN;
    private double targetR = Double.NaN;
    private boolean dragging;
    private long lastHoldSent;
    private SimEvent lastEvent;
    private double eventAge;
    private double clock;
    private double mouseX = -1;
    private double mouseY = -1;

    public MoleculeCanvas(DoubleConsumer onHold, Runnable onRelease) {
        this.onHold = onHold;
        this.onRelease = onRelease;
        canvas.setFocusTraversable(false);
        canvas.setOnMousePressed(event -> {
            if (ready() && nearNucleus(event.getX(), event.getY())) {
                dragging = true;
                canvas.setCursor(Cursor.CLOSED_HAND);
                hold(event.getX());
            } else if (legendHit(event.getX(), event.getY())) {
                showDifference = !showDifference;
            }
        });
        canvas.setOnMouseDragged(event -> {
            if (dragging) {
                hold(event.getX());
            }
            mouseX = event.getX();
            mouseY = event.getY();
        });
        canvas.setOnMouseReleased(event -> {
            if (dragging) {
                dragging = false;
                canvas.setCursor(Cursor.DEFAULT);
                onRelease.run();
            }
        });
        canvas.setOnMouseMoved(event -> {
            mouseX = event.getX();
            mouseY = event.getY();
            canvas.setCursor(ready() && nearNucleus(mouseX, mouseY) ? Cursor.OPEN_HAND : Cursor.DEFAULT);
        });
    }

    public Canvas getNode() {
        return canvas;
    }

    public void setDifference(boolean on) {
        showDifference = on;
    }

    public void toggleDifference() {
        showDifference = !showDifference;
    }

    private boolean ready() {
        return state != null && state.getReady() && !points.isEmpty();
    }

    public void applyFrame(UniverseFrame frame) {
        ChemistryState incoming = frame.getChemistry();
        if (!incoming.getActive()) {
            state = null;
            return;
        }
        String pair = incoming.getZA() + "-" + incoming.getZB();
        if (!pair.equals(tablePair)) {
            points.clear();
            tableKey = -1;
            tablePair = pair;
            shownR = Double.NaN;
        }
        if (incoming.getPointsCount() > 0 && incoming.getTableVersion() != tableKey) {
            points.clear();
            points.addAll(incoming.getPointsList());
            tableKey = incoming.getTableVersion();
        }
        state = incoming;
        if (!dragging && incoming.getR() > 0) {
            targetR = incoming.getR();
            if (Double.isNaN(shownR)) {
                shownR = targetR;
            }
        }
        for (SimEvent event : frame.getEventsList()) {
            if (!event.getKind().equals("info")) {
                lastEvent = event;
                eventAge = 0;
            }
        }
    }

    // =================================================================
    // Géométrie : la coupe calculée occupe la scène, à l'échelle
    // =================================================================
    private double stageRight() {
        return canvas.getWidth() - PANEL_WIDTH - 16;
    }

    private double pixelsPerAngstrom() {
        double width = stageRight() - 40;
        double height = canvas.getHeight() - HEADER - FOOTER - 230;
        return Math.min(width / (2 * state.getExtentX()), height / (2 * state.getExtentY()));
    }

    private double centerX() {
        return stageRight() / 2;
    }

    private double centerY() {
        return HEADER + 62 + state.getExtentY() * pixelsPerAngstrom();
    }

    private boolean nearNucleus(double x, double y) {
        double r = Double.isNaN(shownR) ? 0 : shownR;
        double scale = pixelsPerAngstrom();
        for (double side : new double[]{-1, 1}) {
            if (Math.hypot(x - (centerX() + side * r / 2 * scale), y - centerY()) < 22) {
                return true;
            }
        }
        return false;
    }

    /** Tenir un noyau : la distance est deux fois l'écart au centre. */
    private void hold(double x) {
        double r = Math.max(0.3, 2 * Math.abs(x - centerX()) / pixelsPerAngstrom());
        r = Math.min(r, 2 * state.getExtentX() - 0.4);
        shownR = targetR = r;
        long now = System.currentTimeMillis();
        if (now - lastHoldSent > 40) {
            lastHoldSent = now;
            onHold.accept(r);
        }
    }

    // =================================================================
    // Boucle
    // =================================================================
    public void tick(double delta) {
        clock += delta;
        eventAge += delta;
        if (!Double.isNaN(targetR) && !dragging) {
            shownR += (targetR - shownR) * (1 - Math.exp(-14 * delta));
        }
        render();
    }

    private void render() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, width, height);
        if (state == null) {
            return;
        }
        drawHeader();
        if (!ready()) {
            drawProgress(width, height);
            return;
        }
        drawDensity();
        drawNuclei();
        drawLegend();
        drawPanel(width, height);
        drawEvent();
        drawFooter(width, height);
    }

    // --- La densité, interpolée entre les deux distances calculées qui encadrent R ---
    private double[] bracket(double r) {
        int below = 0;
        for (int k = 0; k < points.size(); k++) {
            if (points.get(k).getR() <= r) {
                below = k;
            }
        }
        int above = Math.min(points.size() - 1, below + 1);
        double r0 = points.get(below).getR();
        double r1 = points.get(above).getR();
        double t = r1 > r0 ? Math.max(0, Math.min(1, (r - r0) / (r1 - r0))) : 0;
        return new double[]{below, above, t};
    }

    private void drawDensity() {
        int nx = state.getGridNx();
        int ny = state.getGridNy();
        double[] b = bracket(shownR);
        BondPoint p0 = points.get((int) b[0]);
        BondPoint p1 = points.get((int) b[1]);
        double t = b[2];
        WritableImage image = new WritableImage(nx, ny);
        PixelWriter writer = image.getPixelWriter();
        double maximum = 1e-9;
        double extreme = 1e-9;
        for (int i = 0; i < nx * ny; i++) {
            maximum = Math.max(maximum, p0.getDensity(i));
            extreme = Math.max(extreme, Math.abs(p0.getDifference(i)));
        }
        for (int row = 0; row < ny; row++) {
            for (int column = 0; column < nx; column++) {
                int i = row * nx + column;
                Color color;
                if (showDifference) {
                    double value = p0.getDifference(i) * (1 - t) + p1.getDifference(i) * t;
                    double level = Math.signum(value) * Math.sqrt(Math.min(1, Math.abs(value) / (0.6 * extreme)));
                    color = level >= 0 ? BACKGROUND.interpolate(GAIN, level) : BACKGROUND.interpolate(LOSS, -level);
                } else {
                    double value = p0.getDensity(i) * (1 - t) + p1.getDensity(i) * t;
                    color = QuantumView.ramp(QuantumView.logScale(value / maximum));
                }
                writer.setColor(column, ny - 1 - row, color);
            }
        }
        double scale = pixelsPerAngstrom();
        double w = 2 * state.getExtentX() * scale;
        double h = 2 * state.getExtentY() * scale;
        gc.setImageSmoothing(true);
        gc.drawImage(image, centerX() - w / 2, centerY() - h / 2, w, h);
        gc.setStroke(CARD_BORDER);
        gc.strokeRect(centerX() - w / 2, centerY() - h / 2, w, h);

        // Barre d'échelle : 1 Å
        double barY = centerY() + h / 2 + 18;
        gc.setStroke(Color.web("#CBD5E1"));
        gc.setLineWidth(1.4);
        gc.strokeLine(centerX() + w / 2 - scale, barY, centerX() + w / 2, barY);
        gc.setFont(Font.font(MONO, 11));
        gc.setFill(Color.web("#CBD5E1"));
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("1 Å = 100 pm", centerX() + w / 2, barY + 14);
    }

    private void drawNuclei() {
        double scale = pixelsPerAngstrom();
        BondPoint near = points.get(nearest(shownR));
        double charge = near.getChargeA();
        String[] symbols = {state.getSymbolA(), state.getSymbolB()};
        double[] charges = {charge, -charge};
        for (int k = 0; k < 2; k++) {
            double side = k == 0 ? -1 : 1;
            double x = centerX() + side * shownR / 2 * scale;
            double y = centerY();
            boolean hovered = Math.hypot(mouseX - x, mouseY - y) < 22 || dragging;
            gc.setFill(new RadialGradient(0, 0, x - 4, y - 4, 16, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.WHITE), new Stop(1, Color.web("#FB7185"))));
            gc.fillOval(x - 9, y - 9, 18, 18);
            gc.setStroke(hovered ? FORMULA : Color.web("#FFFFFF", 0.6));
            gc.setLineWidth(hovered ? 2.2 : 1.2);
            gc.strokeOval(x - 12, y - 12, 24, 24);
            gc.setFont(Font.font(UI, FontWeight.BOLD, 15));
            gc.setFill(TEXT);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(symbols[k], x, y - 20);
            gc.setFont(Font.font(MONO, FontWeight.BOLD, 12));
            double q = charges[k];
            gc.setFill(Math.abs(q) < 0.05 ? SOFT : q > 0 ? LOSS.brighter() : GAIN.brighter());
            // Sous 0,05 e, c'est du bruit numérique (deux atomes identiques ne se volent rien).
            gc.fillText(Math.abs(q) < 0.05 ? "charge 0" : "δ" + (q > 0 ? "+" : "−") + Visuals.fixed(Math.abs(q), 2),
                    x, y + 30);
        }
        // La distance, entre les deux noyaux.
        double left = centerX() - shownR / 2 * scale;
        double right = centerX() + shownR / 2 * scale;
        double y = centerY() + 48;
        gc.setStroke(Color.web("#CBD5E1", 0.6));
        gc.setLineWidth(1);
        gc.strokeLine(left, y, right, y);
        gc.strokeLine(left, y - 4, left, y + 4);
        gc.strokeLine(right, y - 4, right, y + 4);
        gc.setFont(Font.font(MONO, 12));
        gc.setFill(TEXT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("R = " + Visuals.fixed(shownR, 3) + " Å", centerX(), y + 16);
    }

    private int nearest(double r) {
        int best = 0;
        for (int k = 0; k < points.size(); k++) {
            if (Math.abs(points.get(k).getR() - r) < Math.abs(points.get(best).getR() - r)) {
                best = k;
            }
        }
        return best;
    }

    private double[] legendBox() {
        return new double[]{22, canvas.getHeight() - FOOTER - 100, 330, 54};
    }

    private boolean legendHit(double x, double y) {
        double[] box = legendBox();
        return x >= box[0] && x <= box[0] + box[2] && y >= box[1] && y <= box[1] + box[3];
    }

    private void drawLegend() {
        double[] box = legendBox();
        gc.setFill(CARD);
        gc.fillRoundRect(box[0], box[1], box[2], box[3], 12, 12);
        gc.setStroke(CARD_BORDER);
        gc.strokeRoundRect(box[0], box[1], box[2], box[3], 12, 12);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 11));
        gc.setFill(SOFT);
        if (showDifference) {
            gc.fillText("OÙ SONT ALLÉS LES ÉLECTRONS : Δρ (clic : densité)", box[0] + 12, box[1] + 18);
            gc.setFont(Font.font(UI, 12));
            gc.setFill(GAIN.brighter());
            gc.fillText("■ électrons gagnés", box[0] + 12, box[1] + 40);
            gc.setFill(LOSS.brighter());
            gc.fillText("■ électrons partis", box[0] + 150, box[1] + 40);
        } else {
            gc.fillText("DENSITÉ ÉLECTRONIQUE ρ, échelle log (clic : Δρ)", box[0] + 12, box[1] + 18);
            gc.setFont(Font.font(UI, 12));
            gc.setFill(SOFT);
            gc.fillText("clair = électrons probables · calculée, pas dessinée", box[0] + 12, box[1] + 40);
        }
    }

    // --- Panneau : la courbe, les électrons, les orbitales ---------------------
    private double card(double x, double y, double w, double h, String title, Color accent) {
        gc.setFill(CARD);
        gc.fillRoundRect(x, y, w, h, 14, 14);
        gc.setStroke(CARD_BORDER);
        gc.setLineWidth(1);
        gc.strokeRoundRect(x, y, w, h, 14, 14);
        gc.setFill(accent);
        gc.fillRoundRect(x + 14, y + 14, 3, 12, 2, 2);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 11));
        gc.setFill(SOFT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(title, x + 24, y + 20);
        gc.setTextBaseline(VPos.BASELINE);
        return y + 42;
    }

    private void drawPanel(double width, double height) {
        double x = width - PANEL_WIDTH - 14;
        double available = height - FOOTER - 28;
        double curveHeight = available * 0.40;
        drawCurve(x, 14, PANEL_WIDTH, curveHeight);
        drawOrbitals(x, 14 + curveHeight + 10, PANEL_WIDTH, available - curveHeight - 10);
    }

    /** E(R) : le puits (ou son absence), où sont les noyaux, et leur énergie totale. */
    private void drawCurve(double x, double y, double w, double h) {
        double top = card(x, y, w, h, "ÉNERGIE E(R) · calculée à " + points.size() + " distances", FORMULA);
        double left = x + 44;
        double right = x + w - 16;
        double bottom = y + h - 54;
        double rMin = points.get(0).getR();
        double rMax = points.get(points.size() - 1).getR();
        double eMin = 0;
        for (BondPoint point : points) {
            eMin = Math.min(eMin, point.getEnergyEv());
        }
        final double eLow = Math.min(eMin * 1.15, -0.5);
        final double eMax = Math.max(1.0, -eLow * 0.6);
        java.util.function.DoubleUnaryOperator px = r -> left + (r - rMin) / (rMax - rMin) * (right - left);
        java.util.function.DoubleUnaryOperator py = e -> top + (eMax - Math.max(eLow, Math.min(eMax, e))) / (eMax - eLow) * (bottom - top);

        gc.setStroke(MUTED);
        gc.setLineWidth(1);
        gc.setLineDashes(4, 4);
        gc.strokeLine(left, py.applyAsDouble(0), right, py.applyAsDouble(0));
        gc.setLineDashes((double[]) null);
        gc.setFont(Font.font(UI, 10));
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("0", left - 4, py.applyAsDouble(0) + 3);
        gc.fillText(Visuals.format(eLow) + " eV", left - 4, bottom);

        gc.setStroke(FORMULA);
        gc.setLineWidth(2);
        for (int k = 1; k < points.size(); k++) {
            gc.strokeLine(px.applyAsDouble(points.get(k - 1).getR()), py.applyAsDouble(points.get(k - 1).getEnergyEv()),
                    px.applyAsDouble(points.get(k).getR()), py.applyAsDouble(points.get(k).getEnergyEv()));
        }
        for (BondPoint point : points) {
            gc.setFill(FORMULA);
            gc.fillOval(px.applyAsDouble(point.getR()) - 2, py.applyAsDouble(point.getEnergyEv()) - 2, 4, 4);
        }

        // Énergie totale : sous 0, les noyaux sont prisonniers du puits.
        double total = state.getEnergyEv() + state.getKineticEv();
        if (!state.getHeld()) {
            gc.setStroke(total < 0 ? Color.web("#34D399") : Color.web("#FB923C"));
            gc.setLineDashes(6, 4);
            gc.setLineWidth(1.4);
            gc.strokeLine(left, py.applyAsDouble(total), right, py.applyAsDouble(total));
            gc.setLineDashes((double[]) null);
        }
        double markerR = Math.max(rMin, Math.min(rMax, shownR));
        double mx = px.applyAsDouble(markerR);
        double my = py.applyAsDouble(state.getEnergyEv());
        gc.setFill(Color.WHITE);
        gc.fillOval(mx - 5, my - 5, 10, 10);

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(MONO, 11));
        gc.setFill(TEXT);
        gc.fillText(state.getBound()
                ? "puits De = " + Visuals.fixed(state.getDeEv(), 2) + " eV à Re = " + Visuals.fixed(state.getReAngstrom(), 3) + " Å"
                : "aucun puits : pas de liaison", x + 18, bottom + 22);
        gc.setFill(state.getHeld() ? SOFT : total < 0 ? Color.web("#34D399") : Color.web("#FB923C"));
        gc.fillText(state.getHeld() ? "noyaux tenus : lâche-les pour voir"
                : "énergie totale " + Visuals.signed(total, 2) + " eV : " + (total < 0 ? "liés, ils vibrent" : "libres"),
                x + 18, bottom + 38);
    }

    /** Un niveau d'énergie : une ou plusieurs orbitales de même énergie (π, p...), et leurs électrons. */
    private record Level(double energy, List<int[]> spins, String label) {
    }

    /**
     * Les niveaux à montrer : les électrons de valence (jusqu'à 14 eV sous le
     * plus haut occupé) et la première orbitale vide. Les orbitales de même
     * énergie sont regroupées en un niveau, côte à côte.
     */
    static List<Level> levels(OrbitalSet set) {
        List<OrbitalLevel> alpha = set.getAlphaList();
        List<OrbitalLevel> beta = set.getBetaList();
        double homo = -Double.MAX_VALUE;
        for (int k = 0; k < alpha.size(); k++) {
            boolean occupied = alpha.get(k).getOccupation() > 0.5 || (k < beta.size() && beta.get(k).getOccupation() > 0.5);
            if (occupied) {
                homo = Math.max(homo, alpha.get(k).getEnergyEv());
            }
        }
        List<Level> result = new ArrayList<>();
        boolean virtualShown = false;
        for (int k = 0; k < alpha.size(); k++) {
            OrbitalLevel level = alpha.get(k);
            int up = level.getOccupation() > 0.5 ? 1 : 0;
            int down = k < beta.size() && beta.get(k).getOccupation() > 0.5 ? 1 : 0;
            boolean empty = up + down == 0;
            if (level.getEnergyEv() < homo - 14) {
                continue;
            }
            if (empty) {
                if (virtualShown && (result.isEmpty() || Math.abs(result.get(result.size() - 1).energy() - level.getEnergyEv()) > 0.15)) {
                    continue;
                }
                virtualShown = true;
            }
            Level last = result.isEmpty() ? null : result.get(result.size() - 1);
            if (last != null && Math.abs(last.energy() - level.getEnergyEv()) < 0.15) {
                last.spins().add(new int[]{up, down});
            } else {
                List<int[]> spins = new ArrayList<>();
                spins.add(new int[]{up, down});
                result.add(new Level(level.getEnergyEv(), spins, level.getLabel()));
            }
        }
        return result;
    }

    /**
     * Le diagramme d'orbitales : les niveaux de l'atome A, ceux de la
     * molécule, ceux de l'atome B, et les électrons qui les occupent, flèche
     * par flèche (↑ spin +½, ↓ spin −½).
     */
    private void drawOrbitals(double x, double y, double w, double h) {
        BondPoint near = points.get(nearest(shownR));
        double top = card(x, y, w, h, "ORBITALES · ↑ spin +½  ↓ spin −½ · R = " + Visuals.fixed(near.getR(), 2) + " Å",
                Color.web("#7DD3FC")) + 6;
        double bottom = y + h - 70;
        List<Level> left = levels(state.getAtomA());
        List<Level> middle = levels(near.getOrbitals());
        List<Level> right = levels(state.getAtomB());
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (List<Level> column : List.of(left, middle, right)) {
            for (Level level : column) {
                low = Math.min(low, level.energy());
                high = Math.max(high, level.energy());
            }
        }
        final double floor = low - 0.8;
        final double ceiling = high + 0.8;
        java.util.function.DoubleUnaryOperator py = e -> top + (ceiling - e) / (ceiling - floor) * (bottom - top);
        double[] columns = {x + 52, x + w / 2 - 4, x + w - 52};
        drawLevels(left, columns[0], 70, py, false);
        drawLevels(middle, columns[1], 96, py, true);
        drawLevels(right, columns[2], 70, py, false);

        gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
        gc.setFill(TEXT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(state.getSymbolA(), columns[0], bottom + 20);
        gc.fillText(state.getSymbolA() + state.getSymbolB(), columns[1], bottom + 20);
        gc.fillText(state.getSymbolB(), columns[2], bottom + 20);
        gc.setFont(Font.font(UI, 11));
        gc.setTextAlign(TextAlignment.LEFT);
        double charge = near.getChargeA();
        gc.setFill(SOFT);
        gc.fillText("liaison (Mayer) : " + Visuals.fixed(near.getBondOrder(), 2) + "   ·   transfert : "
                + Visuals.fixed(Math.abs(charge), 2) + " e " + (charge > 0 ? state.getSymbolA() + " → " + state.getSymbolB()
                : state.getSymbolB() + " → " + state.getSymbolA()), x + 18, bottom + 40);
        gc.setFill(MUTED);
        gc.fillText("Δχ de Pauling = " + Visuals.fixed(Math.abs(state.getChiA() - state.getChiB()), 2)
                + " : la règle de manuel, pour comparer au calcul", x + 18, bottom + 56);
    }

    private void drawLevels(List<Level> levels, double cx, double span, java.util.function.DoubleUnaryOperator py,
                            boolean labels) {
        double previousLabelY = Double.NaN;
        for (Level level : levels) {
            double y = py.applyAsDouble(level.energy());
            int count = level.spins().size();
            double gap = 4;
            double width = Math.min(26, (span - gap * (count - 1)) / count);
            double start = cx - (count * width + (count - 1) * gap) / 2;
            boolean filled = false;
            for (int k = 0; k < count; k++) {
                int[] spin = level.spins().get(k);
                double left = start + k * (width + gap);
                filled |= spin[0] + spin[1] > 0;
                gc.setStroke(spin[0] + spin[1] > 0 ? TEXT : MUTED);
                gc.setLineWidth(1.6);
                gc.strokeLine(left, y, left + width, y);
                if (spin[0] == 1) {
                    arrow(left + width * (spin[1] == 1 ? 0.32 : 0.5), y, true);
                }
                if (spin[1] == 1) {
                    arrow(left + width * 0.68, y, false);
                }
            }
            if (labels && !level.label().isEmpty() && (Double.isNaN(previousLabelY) || Math.abs(y - previousLabelY) > 11)) {
                gc.setFont(Font.font(UI, 10));
                gc.setFill(filled ? SOFT : MUTED);
                gc.setTextAlign(TextAlignment.LEFT);
                gc.fillText(level.label() + (filled ? "" : " (vide)"), cx + span / 2 + 6, y + 3);
                previousLabelY = y;
            }
        }
    }

    private void arrow(double x, double y, boolean up) {
        Color color = up ? SPIN_UP : SPIN_DOWN;
        gc.setStroke(color);
        double top = y - 7;
        double bottom = y + 7;
        gc.strokeLine(x, top, x, bottom);
        double tip = up ? top : bottom;
        double back = up ? top + 3.5 : bottom - 3.5;
        gc.strokeLine(x, tip, x - 2.5, back);
        gc.strokeLine(x, tip, x + 2.5, back);
    }

    // --- Habillage -------------------------------------------------------------
    private void drawHeader() {
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 22));
        gc.setFill(TEXT);
        gc.fillText("Molécule · " + state.getNameA() + " + " + state.getNameB(), 22, 14);
        gc.setFont(Font.font(UI, 13));
        gc.setFill(SOFT);
        gc.fillText(state.getMethod(), 22, 44);
        if (ready()) {
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setFont(Font.font(MONO, FontWeight.BOLD, 20));
            gc.setFill(TEXT);
            gc.fillText("t = " + Visuals.duration(state.getTimeFs() * 1e-15), stageRight() - 18, 14);
            if (state.getPeriodFs() > 0) {
                gc.setFont(Font.font(UI, 13));
                gc.setFill(SOFT);
                gc.fillText("une vibration = " + Visuals.fixed(state.getPeriodFs(), 1) + " fs, montrée en 3 s",
                        stageRight() - 18, 44);
            }
        }
        gc.setTextBaseline(VPos.BASELINE);
    }

    private void drawProgress(double width, double height) {
        double w = 520;
        double h = 150;
        double x = (width - w) / 2;
        double y = (height - h) / 2;
        gc.setFill(CARD);
        gc.fillRoundRect(x, y, w, h, 16, 16);
        gc.setStroke(CARD_BORDER);
        gc.strokeRoundRect(x, y, w, h, 16, 16);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 16));
        gc.setFill(TEXT);
        gc.fillText(state.getFailed().isEmpty() ? "Calcul quantique en cours" : "Le calcul n'a pas abouti", x + 22, y + 34);
        gc.setFont(Font.font(MONO, 12));
        gc.setFill(FORMULA);
        gc.fillText(state.getStatus(), x + 22, y + 58);
        gc.setFill(Color.web("#22304A"));
        gc.fillRoundRect(x + 22, y + 72, w - 44, 8, 4, 4);
        gc.setFill(Color.web("#38BDF8"));
        gc.fillRoundRect(x + 22, y + 72, (w - 44) * state.getProgress(), 8, 4, 4);
        gc.setFont(Font.font(UI, 12));
        gc.setFill(SOFT);
        gc.fillText("À chaque distance, les équations de Kohn-Sham sont résolues pour tous les électrons.",
                x + 22, y + 104);
        gc.fillText("La première fois, de quelques secondes à une minute ; ensuite, le résultat est gardé.",
                x + 22, y + 122);
    }

    /** Le dernier événement, en bandeau au-dessus de la coupe : il ne cache pas les électrons. */
    private void drawEvent() {
        if (lastEvent == null) {
            return;
        }
        double alpha = Math.min(1, eventAge / 0.4);
        double x = 22;
        double y = HEADER - 6;
        double width = stageRight() - 44;
        gc.setFill(Color.web("#0B1222", 0.92 * alpha));
        gc.fillRoundRect(x, y, width, 52, 12, 12);
        gc.setStroke(Color.web("#34D399", 0.7 * alpha));
        gc.strokeRoundRect(x, y, width, 52, 12, 12);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 13));
        gc.setFill(TEXT.deriveColor(0, 1, 1, alpha));
        gc.fillText(lastEvent.getTitle(), x + 14, y + 20);
        gc.setFont(Font.font(MONO, 11));
        gc.setFill(FORMULA.deriveColor(0, 1, 1, alpha));
        List<String> formula = SimCanvas.wrap(lastEvent.getFormula(), (int) ((width - 28) / 6.6));
        gc.fillText(formula.isEmpty() ? "" : formula.get(0), x + 14, y + 40);
    }

    private void drawFooter(double width, double height) {
        double top = height - FOOTER;
        gc.setFill(Color.web("#070C1A", 0.9));
        gc.fillRect(0, top, width, FOOTER);
        gc.setStroke(CARD_BORDER);
        gc.strokeLine(0, top, width, top);
        gc.setFont(Font.font(UI, 13));
        gc.setFill(SOFT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText("Attrape un noyau, rapproche-le ou éloigne-le, puis lâche-le : la vraie force quantique "
                + "décide · D : densité ou Δρ", 20, top + FOOTER / 2);
        gc.setTextBaseline(VPos.BASELINE);
    }
}
