package org.genesis.matter;

import org.genesis.app.I18n;
import org.genesis.atom.SimCanvas;
import org.genesis.common.Visuals;

import com.studio.genesis.grpc.MatterState;
import com.studio.genesis.grpc.SimEvent;
import com.studio.genesis.grpc.UniverseFrame;

import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * L'échelle de la matière : des centaines d'atomes dans une boîte.
 *
 * Le moteur intègre la dynamique moléculaire (Lennard-Jones) ; on ne fait
 * ici que la montrer. Chaque atome est une sphère de diamètre σ, colorée
 * selon son agitation (sa température propre) ou selon son état mesuré
 * (solide, liquide, gaz). La boîte tourne à la souris, avec inertie ; le
 * mur de droite est un piston qu'on peut tirer ; chaque choc d'un atome sur
 * une paroi s'allume : c'est ça, la pression.
 */
public class MatterCanvas {

    private static final Color BACKGROUND = Color.web("#050812");
    private static final Color CARD = Color.web("#0C1324", 0.92);
    private static final Color CARD_BORDER = Color.web("#22304A");
    private static final Color TEXT = Color.web("#E6EDF7");
    private static final Color SOFT = Color.web("#A9B6CB");
    private static final Color MUTED = Color.web("#64748B");
    private static final Color FORMULA = Color.web("#FCD34D");
    private static final Color SOLID = Color.web("#E0F2FE");
    private static final Color LIQUID = Color.web("#38BDF8");
    private static final Color GAS = Color.web("#A78BFA");
    private static final String UI = "Segoe UI";
    private static final String MONO = "Consolas";

    /** Échelle de chaleur : froid violet sombre, puis rouge, orange, jaune, blanc. */
    private static final Color[] HEAT = {
            Color.web("#1E1B4B"), Color.web("#4C1D95"), Color.web("#9D174D"), Color.web("#DC2626"),
            Color.web("#F97316"), Color.web("#FACC15"), Color.web("#FEF9C3"), Color.WHITE};

    private static final double PANEL_WIDTH = 300;
    private static final double HEADER = 78;
    private static final double FOOTER = 40;
    private static final int TRAIL = 7;

    private final Canvas canvas = new Canvas(800, 600);
    private final GraphicsContext gc = canvas.getGraphicsContext2D();
    private final DoubleConsumer onPiston;
    private final Runnable onZoomIn;

    private MatterState state;
    private float[] previous;            // positions de la frame précédente (x, y, z à la suite)
    private float[] current;
    private double sinceFrame;
    private final List<float[]> history = new ArrayList<>();   // positions passées, pour les traînées
    private final List<double[]> flashes = new ArrayList<>();  // chocs : x, y, z, âge
    private SimEvent lastPhase;
    private double phaseAge;
    private boolean colorByPhase;
    private boolean paused;

    private double yaw = -0.55;
    private double pitch = 0.32;
    private double spinYaw;
    private double spinPitch;
    private double zoom = 1.0;
    private boolean dragging;
    private boolean draggingPiston;
    private double dragX;
    private double dragY;
    private double pistonFraction = Double.NaN;
    private long lastPistonSent;
    private double clock;
    private double mouseX = -1;
    private double mouseY = -1;

    public MatterCanvas(DoubleConsumer onPiston, Runnable onZoomIn) {
        this.onPiston = onPiston;
        this.onZoomIn = onZoomIn;
        canvas.setFocusTraversable(false);
        canvas.setOnScroll(event -> {
            if (event.getDeltaY() > 0 && zoom >= 2.4) {
                onZoomIn.run();   // on plonge vers un atome : retour à l'échelle de l'atome
                return;
            }
            zoom = Math.max(0.5, Math.min(2.5, zoom * (event.getDeltaY() > 0 ? 1.12 : 1 / 1.12)));
        });
        canvas.setOnMousePressed(event -> {
            dragX = event.getX();
            dragY = event.getY();
            draggingPiston = state != null && nearPistonHandle(event.getX(), event.getY());
            dragging = !draggingPiston;
            spinYaw = spinPitch = 0;
        });
        canvas.setOnMouseDragged(event -> {
            double dx = event.getX() - dragX;
            double dy = event.getY() - dragY;
            if (draggingPiston) {
                dragPiston(event.getX(), event.getY());
            } else {
                yaw += dx * 0.008;
                pitch = Math.max(-1.3, Math.min(1.3, pitch + dy * 0.008));
                spinYaw = dx * 0.008 * 60;
                spinPitch = dy * 0.008 * 60;
            }
            dragX = event.getX();
            dragY = event.getY();
            mouseX = event.getX();
            mouseY = event.getY();
        });
        canvas.setOnMouseReleased(event -> {
            dragging = false;
            if (draggingPiston) {
                draggingPiston = false;
                onPiston.accept(pistonFraction);
            }
        });
        canvas.setOnMouseMoved(event -> {
            mouseX = event.getX();
            mouseY = event.getY();
            canvas.setCursor(state != null && nearPistonHandle(mouseX, mouseY) ? Cursor.H_RESIZE : Cursor.DEFAULT);
        });
        canvas.setOnMouseClicked(event -> {
            if (event.isStillSincePress() && legendHit(event.getX(), event.getY())) {
                colorByPhase = !colorByPhase;
            }
        });
    }

    public Canvas getNode() {
        return canvas;
    }

    public void toggleColoring() {
        colorByPhase = !colorByPhase;
    }

    public void applyFrame(UniverseFrame frame) {
        MatterState incoming = frame.getMatter();
        paused = frame.getPaused();
        if (!incoming.getActive()) {
            state = null;
            history.clear();
            return;
        }
        int count = incoming.getCount();
        float[] positions = new float[count * 3];
        for (int i = 0; i < count; i++) {
            positions[3 * i] = incoming.getX(i);
            positions[3 * i + 1] = incoming.getY(i);
            positions[3 * i + 2] = incoming.getZ(i);
        }
        boolean sameSystem = state != null && state.getCount() == count && state.getSymbol().equals(incoming.getSymbol());
        previous = sameSystem && current != null ? current : positions;
        current = positions;
        if (!sameSystem) {
            history.clear();
            pistonFraction = Double.NaN;
        }
        history.add(positions);
        while (history.size() > TRAIL) {
            history.remove(0);
        }
        sinceFrame = 0;
        state = incoming;
        if (!draggingPiston) {
            pistonFraction = incoming.getBoxX() / Math.max(1e-6, incoming.getBoxMaxX());
        }
        for (int i = 0; i < incoming.getHitXCount(); i++) {
            flashes.add(new double[]{incoming.getHitX(i), incoming.getHitY(i), incoming.getHitZ(i), 0});
        }
        while (flashes.size() > 220) {
            flashes.remove(0);
        }
        for (SimEvent event : frame.getEventsList()) {
            if (event.getKind().equals("phase") || event.getKind().equals("structure")) {
                lastPhase = event;
                phaseAge = 0;
            }
        }
    }

    // =================================================================
    // Projection
    // =================================================================
    private double stageRight() {
        return canvas.getWidth() - PANEL_WIDTH - 16;
    }

    private double scale() {
        double width = state.getBoxMaxX();
        double extent = Math.hypot(width, Math.hypot(state.getBoxY(), state.getBoxZ()));
        double span = Math.min(stageRight() - 60, canvas.getHeight() - HEADER - FOOTER - 60);
        return span / extent * zoom;
    }

    /** Monde (Å) -> écran : rotation de la boîte, puis projection orthogonale. {x, y, profondeur}. */
    private double[] project(double x, double y, double z) {
        double cx = x - state.getBoxMaxX() / 2;
        double cy = y - state.getBoxY() / 2;
        double cz = z - state.getBoxZ() / 2;
        double x1 = cx * Math.cos(yaw) - cz * Math.sin(yaw);
        double z1 = cx * Math.sin(yaw) + cz * Math.cos(yaw);
        double y2 = cy * Math.cos(pitch) - z1 * Math.sin(pitch);
        double z2 = cy * Math.sin(pitch) + z1 * Math.cos(pitch);
        double s = scale();
        return new double[]{stageRight() / 2 + x1 * s, (HEADER + canvas.getHeight() - FOOTER) / 2 - y2 * s, z2};
    }

    private double[] pistonHandle() {
        return project(state.getBoxX(), state.getBoxY() / 2, state.getBoxZ() / 2);
    }

    private boolean nearPistonHandle(double x, double y) {
        double[] handle = pistonHandle();
        return Math.hypot(x - handle[0], y - handle[1]) < 18;
    }

    /** Tirer la poignée : on la déplace le long de l'axe x projeté à l'écran. */
    private void dragPiston(double x, double y) {
        double[] origin = project(0, state.getBoxY() / 2, state.getBoxZ() / 2);
        double[] end = project(state.getBoxMaxX(), state.getBoxY() / 2, state.getBoxZ() / 2);
        double ax = end[0] - origin[0];
        double ay = end[1] - origin[1];
        double length2 = Math.max(1e-6, ax * ax + ay * ay);
        double t = ((x - origin[0]) * ax + (y - origin[1]) * ay) / length2;
        pistonFraction = Math.max(0.25, Math.min(1.0, t));
        long now = System.currentTimeMillis();
        if (now - lastPistonSent > 80) {
            lastPistonSent = now;
            onPiston.accept(pistonFraction);
        }
    }

    // =================================================================
    // Boucle
    // =================================================================
    public void tick(double delta) {
        clock += delta;
        sinceFrame += delta;
        phaseAge += delta;
        if (!dragging) {
            yaw += spinYaw * delta;
            pitch = Math.max(-1.3, Math.min(1.3, pitch + spinPitch * delta));
            double damping = Math.exp(-2.2 * delta);
            spinYaw *= damping;
            spinPitch *= damping;
        }
        for (double[] flash : flashes) {
            flash[3] += delta;
        }
        flashes.removeIf(flash -> flash[3] > 0.6);
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
        gc.setFill(new RadialGradient(0, 0, stageRight() / 2, height / 2, Math.max(width, height) * 0.7, false,
                CycleMethod.NO_CYCLE, new Stop(0, Color.web("#0C1630")), new Stop(1, BACKGROUND)));
        gc.fillRect(0, 0, width, height);
        if (state == null || current == null) {
            return;
        }
        drawBox(true);
        drawTrails();
        drawAtoms();
        drawFlashes();
        drawBox(false);
        drawPiston();
        drawHeader();
        drawLegend(height);
        drawPanel(width, height);
        drawPhaseCard();
        drawFooter(width, height);
    }

    private double[] atom(int i) {
        double t = Math.min(1, sinceFrame / 0.05);   // frames à 20 Hz, rendu à 60 Hz : on interpole
        return new double[]{
                previous[3 * i] + (current[3 * i] - previous[3 * i]) * t,
                previous[3 * i + 1] + (current[3 * i + 1] - previous[3 * i + 1]) * t,
                previous[3 * i + 2] + (current[3 * i + 2] - previous[3 * i + 2]) * t};
    }

    private Color heat(double kelvin) {
        double top = 3.0 * state.getEpsilonK();
        double t = Math.max(0, Math.min(1, kelvin / top)) * (HEAT.length - 1);
        int index = (int) Math.floor(t);
        if (index >= HEAT.length - 1) {
            return HEAT[HEAT.length - 1];
        }
        return HEAT[index].interpolate(HEAT[index + 1], t - index);
    }

    private Color atomColor(int i) {
        if (colorByPhase) {
            int phase = state.getPhase(i);
            return phase == 2 ? SOLID : phase == 1 ? LIQUID : GAS;
        }
        return heat(state.getAtomTemperatureK(i));
    }

    private void drawAtoms() {
        int count = state.getCount();
        double[][] screen = new double[count][];
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            double[] p = atom(i);
            screen[i] = project(p[0], p[1], p[2]);
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(index -> screen[index][2]));
        double radius = Math.max(2, state.getSigmaAngstrom() * 0.5 * scale());
        double depthRange = Math.max(1, state.getBoxMaxX());
        for (int i : order) {
            double[] p = screen[i];
            double light = 0.62 + 0.38 * (0.5 - p[2] / depthRange);
            Color base = atomColor(i);
            Color bright = base.deriveColor(0, 0.7, 1.25 * light, 1);
            Color dark = base.deriveColor(0, 1.0, 0.45 * light, 1);
            gc.setFill(new RadialGradient(0, 0, p[0] - radius * 0.35, p[1] - radius * 0.4, radius * 1.3, false,
                    CycleMethod.NO_CYCLE, new Stop(0, bright), new Stop(1, dark)));
            gc.fillOval(p[0] - radius, p[1] - radius, radius * 2, radius * 2);
        }
    }

    /** Les atomes de gaz filent en ligne droite : leur traînée montre leur vitesse. */
    private void drawTrails() {
        if (history.size() < 2) {
            return;
        }
        int count = state.getCount();
        gc.setLineWidth(1.4);
        for (int i = 0; i < count; i++) {
            if (state.getPhase(i) != 0) {
                continue;
            }
            Color color = atomColor(i);
            double[] last = null;
            for (int k = 0; k < history.size(); k++) {
                float[] frame = history.get(k);
                if (3 * i + 2 >= frame.length) {
                    last = null;
                    continue;
                }
                double[] p = project(frame[3 * i], frame[3 * i + 1], frame[3 * i + 2]);
                if (last != null) {
                    gc.setStroke(color.deriveColor(0, 1, 1, 0.08 + 0.4 * k / history.size()));
                    gc.strokeLine(last[0], last[1], p[0], p[1]);
                }
                last = p;
            }
        }
    }

    /** Chaque choc d'un atome sur une paroi : un petit éclair. La pression, c'est leur somme. */
    private void drawFlashes() {
        for (double[] flash : flashes) {
            double[] p = project(flash[0], flash[1], flash[2]);
            double t = flash[3] / 0.6;
            double r = 3 + 12 * t;
            gc.setStroke(Color.web("#FDE68A", 0.75 * (1 - t)));
            gc.setLineWidth(1.4);
            gc.strokeOval(p[0] - r, p[1] - r, r * 2, r * 2);
        }
    }

    /** La boîte : arêtes arrière avant les atomes, arêtes avant après. */
    private void drawBox(boolean back) {
        double bx = state.getBoxX();
        double by = state.getBoxY();
        double bz = state.getBoxZ();
        double[][] corners = new double[8][];
        for (int k = 0; k < 8; k++) {
            corners[k] = project((k & 1) == 0 ? 0 : bx, (k & 2) == 0 ? 0 : by, (k & 4) == 0 ? 0 : bz);
        }
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        double middle = 0;
        for (double[] corner : corners) {
            middle += corner[2] / 8;
        }
        for (int[] edge : edges) {
            double depth = (corners[edge[0]][2] + corners[edge[1]][2]) / 2;
            boolean isBack = depth > middle;
            if (isBack != back) {
                continue;
            }
            gc.setStroke(Color.web("#94A3B8", back ? 0.25 : 0.55));
            gc.setLineWidth(back ? 1 : 1.4);
            gc.strokeLine(corners[edge[0]][0], corners[edge[0]][1], corners[edge[1]][0], corners[edge[1]][1]);
        }
    }

    /** Le mur mobile : sa face, sa poignée, et la place qu'il peut encore prendre. */
    private void drawPiston() {
        double bx = state.getBoxX();
        double by = state.getBoxY();
        double bz = state.getBoxZ();
        double[][] face = {project(bx, 0, 0), project(bx, by, 0), project(bx, by, bz), project(bx, 0, bz)};
        gc.setFill(Color.web("#38BDF8", 0.10));
        gc.fillPolygon(new double[]{face[0][0], face[1][0], face[2][0], face[3][0]},
                new double[]{face[0][1], face[1][1], face[2][1], face[3][1]}, 4);
        gc.setStroke(Color.web("#38BDF8", 0.7));
        gc.setLineWidth(1.6);
        gc.strokePolygon(new double[]{face[0][0], face[1][0], face[2][0], face[3][0]},
                new double[]{face[0][1], face[1][1], face[2][1], face[3][1]}, 4);
        double maxX = state.getBoxMaxX();
        if (maxX > bx + 0.5) {
            gc.setLineDashes(3, 5);
            gc.setStroke(Color.web("#64748B", 0.5));
            gc.setLineWidth(1);
            for (double[] corner : new double[][]{{0, 0}, {by, 0}, {by, bz}, {0, bz}}) {
                double[] a = project(bx, corner[0], corner[1]);
                double[] b = project(maxX, corner[0], corner[1]);
                gc.strokeLine(a[0], a[1], b[0], b[1]);
            }
            gc.setLineDashes((double[]) null);
        }
        double[] handle = pistonHandle();
        boolean hovered = Math.hypot(mouseX - handle[0], mouseY - handle[1]) < 18 || draggingPiston;
        gc.setFill(Color.web("#0C1324", 0.9));
        gc.fillOval(handle[0] - 12, handle[1] - 12, 24, 24);
        gc.setStroke(Color.web("#38BDF8", hovered ? 1 : 0.8));
        gc.setLineWidth(hovered ? 2.4 : 1.6);
        gc.strokeOval(handle[0] - 12, handle[1] - 12, 24, 24);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
        gc.setFill(Color.web("#38BDF8"));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        I18n.fillText(gc, "↔", handle[0], handle[1]);
        gc.setFont(Font.font(UI, 11));
        I18n.fillText(gc, "piston", handle[0], handle[1] + 22);
        gc.setTextBaseline(VPos.BASELINE);
    }

    // =================================================================
    // Habillage
    // =================================================================
    private void drawHeader() {
        gc.setFill(Color.web("#38BDF8", 0.14));
        gc.fillRoundRect(22, 16, 50, 50, 12, 12);
        gc.setStroke(Color.web("#38BDF8", 0.6));
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(22, 16, 50, 50, 12, 12);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 24));
        gc.setFill(Color.web("#38BDF8"));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        I18n.fillText(gc, state.getSymbol(), 47, 41);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 22));
        gc.setFill(TEXT);
        I18n.fillText(gc, "Matière · " + state.getCount() + " atomes de " + state.getName().toLowerCase(), 86, 14);
        gc.setFont(Font.font(UI, 13));
        gc.setFill(SOFT);
        I18n.fillText(gc, "sphère = un atome (σ = " + Visuals.fixed(state.getSigmaAngstrom(), 2) + " Å) · boîte "
                + Math.round(state.getBoxX()) + " × " + Math.round(state.getBoxY()) + " × "
                + Math.round(state.getBoxZ()) + " Å", 86, 44);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(Font.font(MONO, FontWeight.BOLD, 20));
        gc.setFill(TEXT);
        I18n.fillText(gc, "t = " + Visuals.duration(state.getTimePs() * 1e-12), stageRight() - 18, 14);
        gc.setFont(Font.font(UI, 13));
        gc.setFill(paused ? FORMULA : Color.web("#34D399"));
        I18n.fillText(gc, paused ? "⏸ arrêté · ▶ reprendre" : "▶ en cours", stageRight() - 18, 44);
        gc.setTextBaseline(VPos.BASELINE);
    }

    private double[] legendBox() {
        return new double[]{22, canvas.getHeight() - FOOTER - 126, 280, 66};
    }

    private boolean legendHit(double x, double y) {
        double[] box = legendBox();
        return x >= box[0] && x <= box[0] + box[2] && y >= box[1] && y <= box[1] + box[3];
    }

    /** La légende des couleurs, avec les vraies températures de fusion et d'ébullition dessus. */
    private void drawLegend(double height) {
        double[] box = legendBox();
        gc.setFill(CARD);
        gc.fillRoundRect(box[0], box[1], box[2], box[3], 12, 12);
        gc.setStroke(CARD_BORDER);
        gc.setLineWidth(1);
        gc.strokeRoundRect(box[0], box[1], box[2], box[3], 12, 12);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 11));
        gc.setFill(SOFT);
        gc.setTextAlign(TextAlignment.LEFT);
        I18n.fillText(gc, colorByPhase ? "COULEUR = ÉTAT MESURÉ (clic : température)"
                : "COULEUR = AGITATION DE L'ATOME (clic : état)", box[0] + 12, box[1] + 15);
        double left = box[0] + 12;
        double width = box[2] - 24;
        double y = box[1] + 32;
        if (colorByPhase) {
            String[] names = {"solide", "liquide", "gaz"};
            Color[] colors = {SOLID, LIQUID, GAS};
            for (int k = 0; k < 3; k++) {
                gc.setFill(colors[k]);
                gc.fillOval(left + k * 88, y + 2, 12, 12);
                gc.setFill(TEXT);
                gc.setFont(Font.font(UI, 12));
                I18n.fillText(gc, names[k], left + k * 88 + 18, y + 13);
            }
            return;
        }
        Stop[] stops = new Stop[HEAT.length];
        for (int k = 0; k < HEAT.length; k++) {
            stops[k] = new Stop((double) k / (HEAT.length - 1), HEAT[k]);
        }
        gc.setFill(new LinearGradient(left, 0, left + width, 0, false, CycleMethod.NO_CYCLE, stops));
        gc.fillRoundRect(left, y, width, 10, 5, 5);
        double top = 3.0 * state.getEpsilonK();
        gc.setFont(Font.font(MONO, 10));
        gc.setFill(MUTED);
        I18n.fillText(gc, "0 K", left, y + 25);
        gc.setTextAlign(TextAlignment.RIGHT);
        I18n.fillText(gc, Visuals.format(top) + " K", left + width, y + 25);
        gc.setTextAlign(TextAlignment.CENTER);
        for (double[] mark : new double[][]{{state.getMeltingK(), 0}, {state.getBoilingK(), 1}}) {
            if (mark[0] <= 0 || mark[0] > top) {
                continue;
            }
            double x = left + mark[0] / top * width;
            gc.setStroke(TEXT);
            gc.setLineWidth(1.2);
            gc.strokeLine(x, y - 3, x, y + 13);
            gc.setFill(TEXT);
            if (mark[1] == 0) {
                I18n.fillText(gc, "fusion " + Visuals.format(mark[0]) + " K", x, y + 25);
            } else {
                I18n.fillText(gc, "ébullition " + Visuals.format(mark[0]) + " K", x, y - 6);
            }
        }
    }

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
        I18n.fillText(gc, title, x + 24, y + 20);
        gc.setTextBaseline(VPos.BASELINE);
        return y + 44;
    }

    /** Les mesures : rien n'est décidé, tout est compté sur les atomes. */
    private void drawPanel(double width, double height) {
        double x = width - PANEL_WIDTH - 14;
        double y = card(x, 14, PANEL_WIDTH, height - FOOTER - 28,
                "MESURES · " + state.getName().toUpperCase() + " · LENNARD-JONES", Color.web("#38BDF8"));
        String[][] rows = {
                {"température", Visuals.format(state.getTemperatureK()) + " K"},
                {"parois", Visuals.format(state.getWallTemperatureK()) + " K (consigne)"},
                {"pression", Visuals.format(state.getPressureBar()) + " bar"},
                {"densité", Visuals.format(state.getDensityKgM3()) + " kg/m³"},
        };
        for (String[] row : rows) {
            gc.setFont(Font.font(UI, 12));
            gc.setFill(MUTED);
            gc.setTextAlign(TextAlignment.LEFT);
            I18n.fillText(gc, row[0], x + 18, y);
            gc.setFont(Font.font(MONO, FontWeight.BOLD, 13));
            gc.setFill(TEXT);
            I18n.fillText(gc, row[1], x + 112, y);
            y += 20;
        }

        // États : la part des atomes dans chaque état, comptée atome par atome.
        y += 6;
        double barWidth = PANEL_WIDTH - 36;
        boolean supercritical = state.getSupercritical();
        double[] parts = supercritical
                ? new double[]{state.getSolid(), state.getLiquid() + state.getGas(), 0}
                : new double[]{state.getSolid(), state.getLiquid(), state.getGas()};
        Color[] colors = {SOLID, supercritical ? Color.web("#F472B6") : LIQUID, GAS};
        String[] names = supercritical ? new String[]{"solide", "fluide supercritique", ""}
                : new String[]{"solide", "liquide", "gaz"};
        double start = x + 18;
        for (int k = 0; k < 3; k++) {
            double w = parts[k] * barWidth;
            gc.setFill(colors[k].deriveColor(0, 1, 1, 0.85));
            gc.fillRect(start, y, w, 12);
            start += w;
        }
        y += 28;
        for (int k = 0; k < 3; k++) {
            if (names[k].isEmpty()) {
                continue;
            }
            gc.setFill(colors[k]);
            gc.setFont(Font.font(UI, FontWeight.BOLD, 12));
            I18n.fillText(gc, names[k] + " " + Math.round(parts[k] * 100) + " %", x + 18 + k * 92, y);
        }
        y += 12;
        y = wrapped(x + 18, y + 8, PANEL_WIDTH - 36, supercritical
                ? "Au-delà du point critique du modèle (" + Visuals.format(state.getModelCriticalK())
                + " K) : liquide et gaz ne se distinguent plus, il n'y a qu'un fluide."
                : "Solide : l'atome garde ses voisins. Liquide : entouré, mais ses voisins changent. "
                + "Gaz : presque seul.", 11, SOFT, 3);

        // Le réel, pour comparer.
        y += 6;
        gc.setFont(Font.font(UI, FontWeight.BOLD, 11));
        gc.setFill(SOFT);
        I18n.fillText(gc, state.getName().toUpperCase() + " RÉEL (1 bar, mesures)", x + 18, y);
        y += 18;
        gc.setFont(Font.font(MONO, 12));
        gc.setFill(TEXT);
        if (state.getMeltingK() > 0) {
            I18n.fillText(gc, "fond à " + Visuals.format(state.getMeltingK()) + " K · bout à "
                    + Visuals.format(state.getBoilingK()) + " K", x + 18, y);
            y += 17;
        }
        if (state.getCriticalK() > 0) {
            I18n.fillText(gc, "point critique : " + Visuals.format(state.getCriticalK()) + " K (modèle : "
                    + Visuals.format(state.getModelCriticalK()) + " K)", x + 18, y);
            y += 17;
        }
        y += 8;
        drawPotential(x + 18, y, PANEL_WIDTH - 36, 92);
        y += 110;
        y = wrapped(x + 18, y, PANEL_WIDTH - 36, "ε/k = " + Visuals.format(state.getEpsilonK()) + " K, σ = "
                + Visuals.fixed(state.getSigmaAngstrom(), 2) + " Å · " + state.getSource(), 10, MUTED, 4);
        wrapped(x + 18, y + 4, PANEL_WIDTH - 36, "Pas de gravité : à cette échelle, elle est des milliards de "
                + "fois plus faible que l'agitation thermique. Un liquide forme une goutte qui flotte.", 10, MUTED, 3);
    }

    /** La courbe V(r) : le puits où les atomes se tiennent, le mur où ils se repoussent. */
    private void drawPotential(double x, double y, double w, double h) {
        gc.setFont(Font.font(MONO, 11));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(FORMULA);
        I18n.fillText(gc, "V(r) = 4ε[(σ/r)¹² − (σ/r)⁶]", x, y);
        y += 10;
        gc.setStroke(CARD_BORDER);
        gc.setLineWidth(1);
        double zeroY = y + h * 0.35;
        gc.strokeLine(x, zeroY, x + w, zeroY);
        double rMin = 0.9;
        double rMax = 2.6;
        gc.setStroke(FORMULA);
        gc.setLineWidth(1.8);
        double previousX = Double.NaN;
        double previousY = Double.NaN;
        for (int k = 0; k <= 80; k++) {
            double r = rMin + (rMax - rMin) * k / 80.0;
            double v = 4 * (Math.pow(r, -12) - Math.pow(r, -6));       // en unités de ε
            double py = zeroY - Math.max(-1.0, Math.min(0.6, v)) * (h * 0.55);
            double px = x + (r - rMin) / (rMax - rMin) * w;
            if (!Double.isNaN(previousX)) {
                gc.strokeLine(previousX, previousY, px, py);
            }
            previousX = px;
            previousY = py;
        }
        double wellX = x + (Math.pow(2, 1 / 6.0) - rMin) / (rMax - rMin) * w;
        double wellY = zeroY + h * 0.55;
        gc.setFill(FORMULA);
        gc.fillOval(wellX - 3, wellY - 3, 6, 6);
        gc.setFill(SOFT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, 10));
        I18n.fillText(gc, "puits −ε à r = 2^⅙ σ = " + Visuals.fixed(Math.pow(2, 1 / 6.0) * state.getSigmaAngstrom(), 2)
                + " Å", wellX + 8, wellY + 4);
        I18n.fillText(gc, "répulsion ↑", x + 16, y + 8);
        gc.setTextAlign(TextAlignment.RIGHT);
        I18n.fillText(gc, "attraction faible →", x + w, zeroY - 4);
    }

    private void drawPhaseCard() {
        if (lastPhase == null) {
            return;
        }
        double alpha = Math.min(1, phaseAge / 0.5);
        List<String> formula = SimCanvas.wrap(lastPhase.getFormula(), 46);
        List<String> detail = SimCanvas.wrap(lastPhase.getDetail(), 54);
        double height = 52 + formula.size() * 17 + Math.min(4, detail.size()) * 16;
        double x = 22;
        double y = HEADER + 6;
        gc.setFill(Color.web("#0B1222", 0.94 * alpha));
        gc.fillRoundRect(x, y, 360, height, 14, 14);
        gc.setStroke(Color.web("#38BDF8", 0.7 * alpha));
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(x, y, 360, height, 14, 14);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 10));
        gc.setFill(Color.web("#38BDF8", alpha));
        I18n.fillText(gc, (lastPhase.getKind().equals("phase") ? "CHANGEMENT D'ÉTAT" : "MATIÈRE") + "   ·   t = "
                + Visuals.duration(lastPhase.getTimeS()), x + 16, y + 20);
        gc.setFont(Font.font(UI, FontWeight.BOLD, 15));
        gc.setFill(TEXT.deriveColor(0, 1, 1, alpha));
        List<String> title = SimCanvas.wrap(lastPhase.getTitle(), 44);
        I18n.fillText(gc, title.isEmpty() ? "" : title.get(0), x + 16, y + 40);
        double line = y + 60;
        gc.setFont(Font.font(MONO, 12));
        gc.setFill(FORMULA.deriveColor(0, 1, 1, alpha));
        for (String text : formula) {
            I18n.fillText(gc, text, x + 16, line);
            line += 17;
        }
        gc.setFont(Font.font(UI, 12));
        gc.setFill(SOFT.deriveColor(0, 1, 1, alpha));
        for (int k = 0; k < Math.min(4, detail.size()); k++) {
            I18n.fillText(gc, detail.get(k), x + 16, line);
            line += 16;
        }
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
        I18n.fillText(gc, "Glisser : tourner la boîte · molette : zoom (vers l'avant : retour à l'atome) · poignée ↔ : "
                + "piston · température des parois : en bas", 20, top + FOOTER / 2);
        gc.setTextBaseline(VPos.BASELINE);
    }

    private double wrapped(double x, double y, double width, String text, double size, Color color, int maxLines) {
        gc.setFont(Font.font(UI, size));
        gc.setFill(color);
        gc.setTextAlign(TextAlignment.LEFT);
        List<String> lines = SimCanvas.wrap(text, (int) (width / (size * 0.52)));
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
            I18n.fillText(gc, lines.get(i), x, y);
            y += size + 5;
        }
        return y;
    }
}
