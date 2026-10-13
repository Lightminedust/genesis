package org.genesis.app;

import org.genesis.common.Visuals;

import com.studio.genesis.grpc.ChainLink;
import com.studio.genesis.grpc.SimEvent;
import com.studio.genesis.grpc.UniverseFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * La timeline de l'atome : ce qu'il devient au fil du temps.
 *
 * La chaîne de désintégration la plus probable (calculée par le moteur, voie
 * dominante à chaque pas) est dessinée comme une ligne de métro : le passé
 * réellement vécu à gauche (grisé, avec la durée vécue), le noyau actuel avec
 * la probabilité qu'il se soit déjà désintégré, P = 1 − e^(−t/τ), puis les
 * stations à venir jusqu'au noyau stable, avec le temps moyen pour y arriver.
 * Un clic sur une station avance le temps jusqu'à elle, événement par
 * événement. Les désintégrations restent tirées au hasard, comme dans la
 * nature.
 *
 * La vitesse du temps est un curseur dont le libellé dit en clair ce qu'elle
 * veut dire : « 1 s réelle = 1 milliard d'années simulées ». Pour chaque
 * étape, la timeline dit aussi combien de secondes réelles il faudra attendre
 * à cette vitesse.
 */
public final class TimelineBar {

    interface Actions {
        void togglePause();

        void next();

        void speed(int index);

        void stepMode(boolean on);
    }

    private static final String UI = "Segoe UI";
    private static final double YEAR_S = 365.25 * 86400;
    private static final Color AMBER = Color.web("#F2B84B");
    private static final Color TEXT = Color.web("#E6EDF7");
    private static final Color SOFT = Color.web("#8B9BB8");
    private static final Color MUTED = Color.web("#5B6B86");
    private static final Color LINE = Color.web("#2A3956");

    /** Un noyau déjà vécu. */
    private record Past(String nuclide, double lived, String mode) {
    }

    /** Une station dessinée, pour le survol et le clic. */
    private record Station(double x, double y, double r, String nuclide, int chainIndex) {
    }

    private final Actions actions;
    private final VBox root = new VBox();
    private final Button play = new Button("▶");
    private final ToggleButton step = new ToggleButton("Pas à pas");
    private final Slider speed = new Slider(0, 9, 0);
    private final Label speedLabel = new Label();
    private final Label clock = new Label();
    private final Canvas line = new Canvas(800, 104);
    private boolean updating;

    private UniverseFrame frame;
    private long frameNanos;
    private final List<Past> history = new ArrayList<>();
    private String nuclide = "";
    private String element = "";
    private double appearedAt;
    private String target;
    private int targetSteps;
    private double mouseX = -1;
    private double mouseY = -1;
    private final List<Station> stations = new ArrayList<>();

    TimelineBar(Actions actions) {
        this.actions = actions;
        play.getStyleClass().add("tl-play");
        play.setFocusTraversable(false);
        play.setTooltip(new Tooltip("faire couler le temps, ou l'arrêter · Espace"));
        play.setOnAction(event -> actions.togglePause());

        Button next = new Button("⏭  Événement suivant");
        next.getStyleClass().add("dash-button");
        next.setFocusTraversable(false);
        next.setTooltip(new Tooltip("avance le temps jusqu'au prochain événement, tiré au hasard · touche →"));
        next.setOnAction(event -> actions.next());

        step.getStyleClass().add("tl-toggle");
        step.setFocusTraversable(false);
        step.setTooltip(new Tooltip("le temps s'arrête après chaque événement, pour le lire · touche A"));
        step.setOnAction(event -> actions.stepMode(step.isSelected()));

        Label speedTitle = new Label("VITESSE DU TEMPS");
        speedTitle.getStyleClass().add("dash-label");
        speed.getStyleClass().add("tl-speed");
        speed.setPrefWidth(230);
        speed.setMajorTickUnit(1);
        speed.setMinorTickCount(0);
        speed.setSnapToTicks(true);
        speed.setShowTickMarks(true);
        speed.setFocusTraversable(false);
        speed.valueProperty().addListener((obs, old, value) -> {
            if (!updating && !speed.isValueChanging()) {
                actions.speed((int) Math.round(value.doubleValue()));
            }
        });
        speed.valueChangingProperty().addListener((obs, was, now) -> {
            if (!now && !updating) {
                actions.speed((int) Math.round(speed.getValue()));
            }
        });
        speedLabel.getStyleClass().add("dash-value");
        speedLabel.setMinWidth(250);
        VBox speedBox = new VBox(2, speedTitle, new HBox(12, speed, speedLabel));
        ((HBox) speedBox.getChildren().get(1)).setAlignment(Pos.CENTER_LEFT);

        clock.getStyleClass().add("dash-value");
        Label clockTitle = new Label("TEMPS SIMULÉ");
        clockTitle.getStyleClass().add("dash-label");
        VBox clockBox = new VBox(2, clockTitle, clock);
        clockBox.setAlignment(Pos.CENTER_RIGHT);

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox controls = new HBox(12, play, next, step, separator(), speedBox, grow, clockBox);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8, 18, 2, 14));

        Pane holder = new Pane(line);
        line.widthProperty().bind(holder.widthProperty());
        holder.setPrefHeight(104);
        holder.setMinHeight(104);
        line.setOnMouseMoved(event -> {
            mouseX = event.getX();
            mouseY = event.getY();
            line.setCursor(stationAt(mouseX, mouseY) != null ? Cursor.HAND : Cursor.DEFAULT);
        });
        line.setOnMouseExited(event -> mouseX = mouseY = -1);
        line.setOnMouseClicked(event -> {
            Station station = stationAt(event.getX(), event.getY());
            if (station != null && station.chainIndex() > 0) {
                target = station.nuclide();
                targetSteps = 0;
                actions.next();
            }
        });

        root.getChildren().addAll(controls, holder);
        root.getStyleClass().add("dash-foot");
    }

    private static Region separator() {
        Region region = new Region();
        region.getStyleClass().add("view-sep");
        region.setPrefHeight(34);
        return region;
    }

    Region getNode() {
        return root;
    }

    boolean stepMode() {
        return step.isSelected();
    }

    /** L'index de vitesse courant, pour les touches [ et ]. */
    int speedIndex() {
        return frame == null ? 0 : frame.getSpeedIndex();
    }

    int speedCount() {
        return frame == null ? 0 : frame.getSpeedLabelsCount();
    }

    void applyFrame(UniverseFrame incoming) {
        String now = incoming.getNucleus().getNuclide();
        String action = incoming.getLastAction();
        boolean reload = frame == null || incoming.getStructureChanged()
                && (action.equals("LOAD_ELEMENT") || action.equals("RESET"));
        if (reload) {
            history.clear();
            target = null;
            nuclide = now;
            appearedAt = incoming.getSimTimeS();
        } else if (!now.equals(nuclide)) {
            String mode = "modifié";
            for (SimEvent event : incoming.getEventsList()) {
                if (event.getKind().equals("decay")) {
                    mode = modeSymbol(event.getDecayMode());
                }
            }
            if (!nuclide.isEmpty()) {
                history.add(new Past(nuclide, incoming.getSimTimeS() - appearedAt, mode));
                while (history.size() > 6) {
                    history.remove(0);
                }
            }
            nuclide = now;
            appearedAt = incoming.getSimTimeS();
        }
        if (incoming.getNucleus().getChainCount() > 0) {
            element = incoming.getNucleus().getChain(0).getElement();
        }
        frame = incoming;
        frameNanos = System.nanoTime();

        updating = true;
        int count = incoming.getSpeedLabelsCount();
        if (count > 0 && speed.getMax() != count - 1) {
            speed.setMax(count - 1);
        }
        if (!speed.isValueChanging() && !speed.isPressed()) {
            speed.setValue(incoming.getSpeedIndex());
        }
        step.setSelected(incoming.getStepMode());
        updating = false;
        play.setText(incoming.getPaused() ? "▶" : "⏸");
        speedLabel.setText(speedText(incoming));

        // « Avancer jusqu'à » : un événement après l'autre, tant que la station n'est pas atteinte.
        if (target != null) {
            boolean reachable = false;
            for (ChainLink link : incoming.getNucleus().getChainList()) {
                reachable |= link.getNuclide().equals(target);
            }
            if (now.equals(target) || !reachable || ++targetSteps > 150) {
                target = null;
            } else if (action.equals("NEXT_EVENT") && incoming.getEventsCount() > 0) {
                actions.next();
            }
        }
    }

    /** « 1 s réelle = 1 milliard d'années simulées ». */
    static String speedText(UniverseFrame frame) {
        if (frame.getSpeedIndex() >= frame.getSpeedLabelsCount()) {
            return "";
        }
        String label = frame.getSpeedLabels(frame.getSpeedIndex());
        if (label.equals("temps réel")) {
            return "1 s réelle = 1 s simulée";
        }
        String span = label.replace(" / s", "");
        boolean feminine = span.contains("seconde") || span.contains("heure") || span.contains("année");
        boolean plural = span.contains("années") || span.contains("ans");
        return "1 s réelle = " + span + " simulé" + (feminine ? "e" : "") + (plural ? "s" : "");
    }

    /** Le temps simulé, interpolé entre deux frames (il coule à `speed` s simulées par seconde réelle). */
    private double simTime() {
        if (frame == null) {
            return 0;
        }
        double real = (System.nanoTime() - frameNanos) / 1e9;
        return frame.getSimTimeS() + (frame.getPaused() ? 0 : Math.min(real, 1.0) * frame.getSpeed());
    }

    void tick() {
        if (frame == null) {
            return;
        }
        clock.setText(compact(simTime()));
        draw();
    }

    // =================================================================
    // La ligne de métro
    // =================================================================
    private void draw() {
        GraphicsContext g = line.getGraphicsContext2D();
        double w = line.getWidth();
        double h = line.getHeight();
        g.clearRect(0, 0, w, h);
        stations.clear();
        List<ChainLink> chain = frame.getNucleus().getChainList();
        if (chain.isEmpty()) {
            text(g, 18, 40, "Pas de noyau : ajoute un proton ou choisis un élément.", SOFT, 12, false);
            return;
        }
        int pastShown = Math.min(history.size(), chain.size() > 6 ? 2 : 4);
        int total = pastShown + chain.size();
        double left = 46;
        double right = w - 46;
        double spacing = total > 1 ? Math.min(150, (right - left) / (total - 1)) : 0;
        // Une longue chaîne à l'étroit : on garde le début, la fin, et un « … » au milieu.
        int futureShown = chain.size();
        boolean elided = false;
        if (spacing < 58 && total > 4) {
            int fit = Math.max(4, (int) ((right - left) / 58) + 1);
            futureShown = Math.max(3, fit - pastShown);
            elided = futureShown < chain.size();
            total = pastShown + futureShown;
            spacing = (right - left) / Math.max(1, total - 1);
        }
        double y = 30;
        double elapsed = Math.max(0, simTime() - appearedAt);

        // Les rails
        double x0 = left;
        double xNow = left + pastShown * spacing;
        double xEnd = left + (total - 1) * spacing;
        g.setStroke(Color.web("#3A4560"));
        g.setLineWidth(3);
        g.setLineDashes(2, 5);
        g.strokeLine(x0, y, xNow, y);
        g.setLineDashes((double[]) null);
        g.setStroke(LINE);
        g.strokeLine(xNow, y, xEnd, y);

        // Le passé vécu
        for (int i = 0; i < pastShown; i++) {
            Past past = history.get(history.size() - pastShown + i);
            double x = left + i * spacing;
            node(g, x, y, 11, past.nuclide(), Color.web("#334155"), Color.web("#94A3B8"));
            text(g, x, y + 27, past.nuclide(), MUTED, 10.5, true);
            text(g, x, y + 41, "vécu " + compact(past.lived()), MUTED, 10, true);
            text(g, x + spacing / 2, y - 9, past.mode(), MUTED, 11, true);
        }

        // Le présent et l'avenir
        double cumulative = 0;
        double shownCumulative = -1;
        String hover = null;
        for (int k = 0; k < chain.size(); k++) {
            int slot = k;
            if (elided && k >= futureShown - 1) {
                if (k != chain.size() - 1) {
                    if (k < chain.size() - 1) {
                        cumulative += meanLife(chain.get(k));
                    }
                    continue;
                }
                slot = futureShown - 1;
            }
            ChainLink link = chain.get(k);
            double x = left + (pastShown + slot) * spacing;
            Color colour = elementColour(link.getZ());
            boolean current = k == 0;
            double r = current ? 16 : 12;
            Station station = new Station(x, y, r + 4, link.getNuclide(), k);
            stations.add(station);
            boolean hovered = !current && mouseX >= 0 && Math.hypot(mouseX - x, mouseY - y) <= r + 4;
            if (link.getStable()) {
                g.setStroke(Color.web("#E6EDF7", 0.85));
                g.setLineWidth(2);
                g.strokeOval(x - r - 4, y - r - 4, (r + 4) * 2, (r + 4) * 2);
            }
            node(g, x, y, r, link.getNuclide(), colour.deriveColor(0, 1, current ? 1 : 0.75, 1),
                    hovered ? Color.WHITE : TEXT);
            if (current && !link.getStable() && link.getHalfLifeS() > 0) {
                // Probabilité qu'il se soit déjà désintégré : P = 1 − e^(−t/τ).
                double p = 1 - Math.exp(-elapsed / meanLife(link));
                g.setStroke(Color.web("#2A3550"));
                g.setLineWidth(3);
                g.strokeOval(x - r - 5, y - r - 5, (r + 5) * 2, (r + 5) * 2);
                g.setStroke(AMBER);
                g.strokeArc(x - r - 5, y - r - 5, (r + 5) * 2, (r + 5) * 2, 90, -360 * p, ArcType.OPEN);
            }
            String under = link.getNuclide();
            text(g, x, y + 28, under, current ? TEXT : hovered ? Color.WHITE : SOFT, current ? 11.5 : 10.5, true);
            if (current) {
                text(g, x, y + 42, link.getStable() ? "stable" : "maintenant", AMBER, 10, true);
            } else if (link.getStable() || hovered || cumulative > 1.15 * shownCumulative) {
                // Le temps d'arrivée, seulement quand il change vraiment (les noyaux éphémères s'enchaînent).
                text(g, x, y + 42, link.getStable() ? "stable · ≈ +" + compact(cumulative)
                        : "≈ +" + compact(cumulative), hovered ? Color.WHITE : MUTED, 10, true);
                shownCumulative = cumulative;
            }
            if (!link.getStable() && k < chain.size() - 1) {
                // Sur le rail : la voie et la demi-vie du noyau qui part.
                double mid = x + spacing / 2;
                if (!(elided && k == futureShown - 2)) {
                    text(g, mid, y - 15, modeSymbol(link.getMode()), colour.deriveColor(0, 0.6, 1.4, 1), 11.5, true);
                    text(g, mid, y - 3 + 13, compact(link.getHalfLifeS()), MUTED, 9.5, true);
                }
                cumulative += meanLife(link);
            }
            if (!current && !hovered && mouseX >= 0 && k > 0 && Math.abs(mouseY - y) < 18
                    && mouseX > x - spacing && mouseX < x) {
                ChainLink from = chain.get(k - 1);
                hover = from.getNuclide() + " → " + link.getNuclide() + " : " + modeName(from.getMode())
                        + ", demi-vie " + compact(from.getHalfLifeS())
                        + (from.getFraction() < 0.999 ? String.format(Locale.FRANCE, " (voie suivie dans %.1f %% des cas)",
                        100 * from.getFraction()) : "");
            }
            if (hovered) {
                hover = "Clic : avancer le temps jusqu'à " + link.getNuclide() + " (" + link.getElement()
                        + ") · en moyenne dans " + compact(cumulative) + " · les désintégrations restent tirées au hasard";
            }
        }
        if (elided) {
            double x = left + (pastShown + futureShown - 1.5) * spacing;
            text(g, x, y + 4, "…", SOFT, 16, true);
        }
        text(g, 18, h - 9, hover != null ? hover : summary(chain, elapsed), hover != null ? TEXT : SOFT, 11.5, false);
    }

    /** La phrase du bas : la prochaine étape, quand, et combien de secondes réelles à cette vitesse. */
    private String summary(List<ChainLink> chain, double elapsed) {
        ChainLink now = chain.get(0);
        String head = target != null ? "En route vers " + target + " · " : "";
        if (now.getStable()) {
            String text = now.getNuclide() + " est stable : son noyau ne changera plus.";
            if (frame.getElectronLifetimeS() > 0) {
                text += "  L'électron excité retombera en ≈ " + compact(frame.getElectronLifetimeS())
                        + (frame.getSpeed() > 0 ? realWait(frame.getElectronLifetimeS()) : "") + ".";
            }
            return head + text;
        }
        double tau = meanLife(now);
        String next = chain.size() > 1 ? chain.get(1).getNuclide() : "?";
        double p = 1 - Math.exp(-elapsed / tau);
        StringBuilder text = new StringBuilder(head);
        text.append("Prochaine étape : ").append(next).append(" (").append(modeSymbol(now.getMode())).append(")")
                .append(" · demi-vie ").append(compact(now.getHalfLifeS()))
                .append(" · en moyenne dans ").append(compact(tau));
        if (frame.getSpeed() > 0) {
            text.append(realWait(tau));
        }
        text.append(" · déjà fait : ").append(String.format(Locale.FRANCE, "%.0f", 100 * p)).append(" %");
        if (now.getEstimated()) {
            text.append(" · demi-vie estimée");
        }
        return text.toString();
    }

    /** Ce que ça représente en temps réel, à la vitesse choisie. */
    private String realWait(double simulated) {
        double seconds = simulated / Math.max(1e-30, frame.getSpeed());
        if (seconds < 1) {
            return " (moins d'1 s réelle à cette vitesse)";
        }
        if (seconds < 120) {
            return String.format(Locale.FRANCE, " (≈ %.0f s réelles à cette vitesse)", seconds);
        }
        if (seconds < 7200) {
            return String.format(Locale.FRANCE, " (≈ %.0f min réelles à cette vitesse)", seconds / 60);
        }
        return " (trop long à cette vitesse : accélère)";
    }

    private Station stationAt(double x, double y) {
        for (Station station : stations) {
            if (Math.hypot(x - station.x(), y - station.y()) <= station.r()) {
                return station;
            }
        }
        return null;
    }

    private static double meanLife(ChainLink link) {
        return link.getHalfLifeS() / Math.log(2);
    }

    private static void node(GraphicsContext g, double x, double y, double r, String nuclide, Color fill, Color ink) {
        g.setFill(fill);
        g.fillOval(x - r, y - r, r * 2, r * 2);
        g.setStroke(Color.web("#0A0F1C"));
        g.setLineWidth(2);
        g.strokeOval(x - r, y - r, r * 2, r * 2);
        String symbol = nuclide.contains("-") ? nuclide.substring(0, nuclide.indexOf('-')) : nuclide;
        g.setFill(Color.web("#0A0F1C"));
        g.setFont(Font.font(UI, FontWeight.BOLD, r > 13 ? 12 : 10.5));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(symbol, x, y);
        g.setTextBaseline(VPos.BASELINE);
    }

    private static void text(GraphicsContext g, double x, double y, String text, Color colour, double size,
                             boolean centred) {
        g.setFont(Font.font(UI, size));
        g.setFill(colour);
        g.setTextAlign(centred ? TextAlignment.CENTER : TextAlignment.LEFT);
        g.setTextBaseline(VPos.BASELINE);
        g.fillText(text, x, y);
    }

    /** Une couleur par élément : la teinte tourne avec Z (angle d'or), lisible sur fond sombre. */
    static Color elementColour(int z) {
        return Color.hsb((z * 137.508) % 360, 0.45, 0.92);
    }

    static String modeSymbol(String mode) {
        return switch (mode) {
            case "alpha" -> "α";
            case "beta-" -> "β⁻";
            case "beta+" -> "β⁺";
            case "CE", "EC" -> "CE";
            case "IT" -> "γ (IT)";
            case "SF" -> "fission";
            case "n" -> "n";
            case "p" -> "p";
            default -> mode;
        };
    }

    static String modeName(String mode) {
        return switch (mode) {
            case "alpha" -> "désintégration α (perd 2 p + 2 n)";
            case "beta-" -> "désintégration β⁻ (un neutron devient proton)";
            case "beta+" -> "désintégration β⁺ (un proton devient neutron)";
            case "CE", "EC" -> "capture électronique";
            case "IT" -> "désexcitation γ";
            case "SF" -> "fission spontanée";
            case "n" -> "émission d'un neutron";
            case "p" -> "émission d'un proton";
            default -> mode;
        };
    }

    /** Une durée courte : 164 µs, 24,1 j, 4,47 Ga. */
    static String compact(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) {
            return "0 s";
        }
        double[][] units = {{1e-9, 1e-12}, {1e-6, 1e-9}, {1e-3, 1e-6}, {1, 1e-3}, {120, 1}, {7200, 60},
                {2 * 86400, 3600}, {2 * YEAR_S, 86400}, {2e3 * YEAR_S, YEAR_S}, {2e6 * YEAR_S, 1e3 * YEAR_S},
                {2e9 * YEAR_S, 1e6 * YEAR_S}};
        String[] names = {"ps", "ns", "µs", "ms", "s", "min", "h", "j", "ans", "ka", "Ma"};
        for (int i = 0; i < units.length; i++) {
            if (seconds < units[i][0]) {
                return Visuals.format(seconds / units[i][1]) + " " + names[i];
            }
        }
        return Visuals.format(seconds / (1e9 * YEAR_S)) + " Ga";
    }
}
