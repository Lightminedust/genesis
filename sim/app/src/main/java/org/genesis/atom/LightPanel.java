package org.genesis.atom;

import org.genesis.common.Visuals;

import com.studio.genesis.grpc.EnergyLevel;
import com.studio.genesis.grpc.SimEvent;
import com.studio.genesis.grpc.SpectralLine;
import com.studio.genesis.grpc.UniverseFrame;

import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * La lumière de l'atome, en deux instruments.
 *
 * En haut, l'échelle des niveaux d'énergie : l'électron y est posé, et
 * chaque saut que le moteur rapporte (absorption vers le haut, émission vers
 * le bas) y est tracé avec la couleur du photon.
 *
 * En bas, le spectre (échelle logarithmique, de l'UV à l'IR) : les raies que
 * l'atome émet, celles qu'il peut ABSORBER dans son état actuel (en
 * surbrillance), le seuil d'ionisation, et le réglage du laser. Un clic sur
 * le spectre accorde le laser ; un clic sur une raie absorbable l'accorde
 * exactement dessus.
 */
public class LightPanel {

    public static final double MIN_NM = 50;
    public static final double MAX_NM = 5000;
    private static final Color TEXT = Color.web("#CBD5E1");
    private static final Color MUTED = Color.web("#64748B");
    private static final double HC_EV_NM = 1239.841984;

    private final Canvas canvas;
    private final GraphicsContext gc;
    private final DoubleConsumer onTune;

    private UniverseFrame frame;
    private double laserNm = 656.28;
    private double clock;
    private double mouseX = -1;
    private double mouseY = -1;

    /** Un saut récent : niveau de départ, d'arrivée, couleur, âge. */
    private record Jump(double fromEv, double toEv, double nm, double[] age) {
    }

    private final List<Jump> jumps = new ArrayList<>();
    private final List<double[]> seen = new ArrayList<>();   // {nm, âge} des photons émis récemment

    public LightPanel(double width, double height, DoubleConsumer onTune) {
        canvas = new Canvas(width, height);
        gc = canvas.getGraphicsContext2D();
        this.onTune = onTune;
        canvas.setOnMouseMoved(event -> {
            mouseX = event.getX();
            mouseY = event.getY();
            canvas.setCursor(inSpectrum(mouseY) ? Cursor.HAND : Cursor.DEFAULT);
        });
        canvas.setOnMouseExited(event -> mouseX = mouseY = -1);
        canvas.setOnMouseClicked(event -> {
            if (!inSpectrum(event.getY())) {
                return;
            }
            Double snap = nearestAbsorbable(event.getX());
            double nm = snap != null ? snap : nmAt(event.getX());
            onTune.accept(nm);
        });
    }

    public Canvas getNode() {
        return canvas;
    }

    public void setLaser(double nm) {
        laserNm = nm;
    }

    public void applyFrame(UniverseFrame incoming) {
        frame = incoming;
        for (SimEvent event : incoming.getEventsList()) {
            boolean emitted = event.getKind().equals("emitted");
            boolean absorbed = event.getKind().equals("absorbed");
            if (!emitted && !absorbed) {
                continue;
            }
            Double from = stateEnergy(event.getFromState());
            Double to = stateEnergy(event.getToState());
            // Atome à plusieurs électrons : le niveau excité est au-dessus du
            // fondamental de l'énergie exacte du photon (raie mesurée).
            if (from == null && to != null) {
                from = to + event.getEnergyEv();
            } else if (to == null && from != null) {
                to = from + event.getEnergyEv();
            }
            if (from != null && to != null) {
                jumps.add(new Jump(from, to, event.getWavelengthNm(), new double[]{0}));
            }
            if (emitted) {
                seen.add(new double[]{event.getWavelengthNm(), 0});
                for (float extra : event.getExtraWavelengthsNmList()) {
                    seen.add(new double[]{extra, 0});
                }
            }
        }
        while (jumps.size() > 6) {
            jumps.remove(0);
        }
        while (seen.size() > 40) {
            seen.remove(0);
        }
    }

    /** Énergie d'un état nommé par le moteur : "3p" (un électron), "excité", "fondamental". */
    private Double stateEnergy(String state) {
        if (frame == null || state.isEmpty()) {
            return null;
        }
        List<EnergyLevel> levels = frame.getLevelsList();
        if (levels.isEmpty()) {
            return null;
        }
        if (Character.isDigit(state.charAt(0))) {
            int n = SimCanvas.principal(state);
            for (EnergyLevel level : levels) {
                if (level.getN() == n) {
                    return (double) level.getEnergyEv();
                }
            }
            return null;
        }
        EnergyLevel valence = valenceLevel();
        if (valence == null) {
            return null;
        }
        if (state.equals("excité")) {
            return null;   // complété par l'énergie du photon dans applyFrame
        }
        return (double) valence.getEnergyEv();
    }

    /** La couche de valence : la plus haute des couches non excitées (même vidée par l'excitation). */
    private EnergyLevel valenceLevel() {
        EnergyLevel valence = null;
        for (EnergyLevel level : frame.getLevelsList()) {
            if (!level.getLabel().contains("excité")
                    && (valence == null || level.getEnergyEv() > valence.getEnergyEv())) {
                valence = level;
            }
        }
        return valence;
    }

    public void tick(double delta) {
        clock += delta;
        for (Jump jump : jumps) {
            jump.age()[0] += delta;
        }
        jumps.removeIf(jump -> jump.age()[0] > 6);
        for (double[] photon : seen) {
            photon[1] += delta;
        }
        seen.removeIf(photon -> photon[1] > 30);
        render();
    }

    // =================================================================
    private double spectrumTop() {
        return canvas.getHeight() - 92;
    }

    private boolean inSpectrum(double y) {
        return y >= spectrumTop() - 4 && y <= spectrumTop() + 56;
    }

    private double xOf(double nm) {
        double left = 12;
        double right = canvas.getWidth() - 12;
        double t = (Math.log10(nm) - Math.log10(MIN_NM)) / (Math.log10(MAX_NM) - Math.log10(MIN_NM));
        return left + Math.max(0, Math.min(1, t)) * (right - left);
    }

    double nmAt(double x) {
        double left = 12;
        double right = canvas.getWidth() - 12;
        double t = Math.max(0, Math.min(1, (x - left) / (right - left)));
        return Math.pow(10, Math.log10(MIN_NM) + t * (Math.log10(MAX_NM) - Math.log10(MIN_NM)));
    }

    private Double nearestAbsorbable(double x) {
        if (frame == null) {
            return null;
        }
        Double best = null;
        double bestGap = 7;
        for (double nm : frame.getAbsorbableNmList()) {
            double gap = Math.abs(xOf(nm) - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = (double) nm;
            }
        }
        return best;
    }

    private void render() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        gc.setFill(Color.web("#05070F"));
        gc.fillRect(0, 0, width, height);
        if (frame == null) {
            return;
        }
        drawLadder(width, spectrumTop() - 26);
        drawSpectrum(width, spectrumTop());
    }

    // --- Niveaux d'énergie ---------------------------------------------------
    private void drawLadder(double width, double bottom) {
        List<EnergyLevel> levels = frame.getLevelsList();
        double top = 26;
        gc.setTextBaseline(VPos.CENTER);
        if (levels.isEmpty()) {
            gc.setFont(Font.font("Segoe UI", 12));
            gc.setFill(MUTED);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("aucun électron : aucun niveau occupé", width / 2, (top + bottom) / 2);
            gc.setTextBaseline(VPos.BASELINE);
            return;
        }
        // Échelle logarithmique de |E| : de la couche K (des dizaines de keV pour
        // un atome lourd) à la valence (quelques eV), tout reste lisible.
        double deepest = 0.1;
        double shallowest = Double.MAX_VALUE;
        for (EnergyLevel level : levels) {
            double depth = Math.abs(level.getEnergyEv());
            deepest = Math.max(deepest, depth);
            shallowest = Math.min(shallowest, Math.max(0.05, depth));
        }
        logLow = Math.log10(shallowest * 0.6);
        logHigh = Math.log10(deepest * 1.15);
        double lowest = -deepest;
        double left = 46;
        double right = width - 92;

        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("ÉNERGIE DE L'ÉLECTRON (eV, échelle logarithmique)", 12, 11);

        // 0 eV : l'électron est libre, au-delà l'atome est ionisé.
        double zeroY = top;
        gc.setStroke(Color.web("#475569"));
        gc.setLineDashes(4, 4);
        gc.strokeLine(left, zeroY, right, zeroY);
        gc.setLineDashes((double[]) null);
        gc.setFont(Font.font("Segoe UI", 10));
        gc.setFill(MUTED);
        gc.fillText("0 · électron libre", right + 6, zeroY);

        boolean oneElectron = frame.getAtom().getElectrons() == 1 && frame.getAtom().getProtons() >= 1;
        int current = oneElectron ? SimCanvas.principal(frame.getElectronState()) : 0;
        double previousLabelY = Double.NaN;
        for (EnergyLevel level : levels) {
            double y = yOf(level.getEnergyEv(), lowest, top, bottom);
            boolean occupied = oneElectron ? level.getN() == current : level.getElectrons() > 0;
            gc.setStroke(occupied ? Color.web("#E2E8F0") : Color.web("#334155"));
            gc.setLineWidth(occupied ? 2 : 1);
            gc.strokeLine(left, y, right, y);
            if ((Double.isNaN(previousLabelY) || Math.abs(y - previousLabelY) > 11) && y - zeroY > 10) {
                gc.setFont(Font.font("Consolas", 10));
                gc.setFill(occupied ? TEXT : MUTED);
                gc.setTextAlign(TextAlignment.RIGHT);
                gc.fillText(Visuals.format(level.getEnergyEv()), left - 6, y);
                gc.setTextAlign(TextAlignment.LEFT);
                gc.fillText(level.getLabel(), right + 6, y);
                previousLabelY = y;
            }
            int electrons = oneElectron ? (occupied ? 1 : 0) : level.getElectrons();
            for (int k = 0; k < Math.min(electrons, 14); k++) {
                double x = left + 16 + k * 11;
                gc.setFill(Color.web("#7DD3FC"));
                gc.fillOval(x - 3.5, y - 3.5, 7, 7);
            }
        }

        // Les sauts rapportés par le moteur.
        double x = right - 40;
        for (Jump jump : jumps) {
            double age = jump.age()[0];
            double alpha = age < 4 ? 1 : Math.max(0, (6 - age) / 2);
            double fromY = yOf(jump.fromEv(), lowest, top, bottom);
            double toY = yOf(jump.toEv(), lowest, top, bottom);
            double grow = Math.min(1, age / 0.5);
            double endY = fromY + (toY - fromY) * grow;
            Color color = Visuals.wavelengthColor(jump.nm()).deriveColor(0, 1, 1, alpha);
            gc.setStroke(color);
            gc.setLineWidth(2.4);
            gc.strokeLine(x, fromY, x, endY);
            double head = toY > fromY ? -6 : 6;
            if (grow >= 1) {
                gc.strokeLine(x, toY, x - 4, toY + head);
                gc.strokeLine(x, toY, x + 4, toY + head);
            }
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
            gc.setFill(color);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText(Visuals.format(jump.nm()) + " nm", x - 6, (fromY + toY) / 2);
            x -= 54;
        }
        gc.setTextBaseline(VPos.BASELINE);
    }

    private double logLow = -1;
    private double logHigh = 1;

    /** Position d'une énergie (négative) sur l'échelle logarithmique de |E|. */
    private double yOf(double energy, double lowest, double top, double bottom) {
        double depth = Math.log10(Math.max(0.05, Math.abs(energy)));
        double t = (depth - logLow) / Math.max(1e-6, logHigh - logLow);
        return top + 12 + Math.max(0, Math.min(1, t)) * (bottom - top - 12);
    }

    // --- Spectre -----------------------------------------------------------
    private void drawSpectrum(double width, double top) {
        double height = 34;
        double visibleLeft = xOf(380);
        double visibleRight = xOf(780);

        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("SPECTRE · cliquer pour régler le laser", 12, top - 8);

        gc.setFill(Color.web("#0B1120"));
        gc.fillRect(12, top, width - 24, height);
        gc.setFill(new LinearGradient(visibleLeft, 0, visibleRight, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, Visuals.wavelengthColor(390).deriveColor(0, 1, 0.5, 0.5)),
                new Stop(0.25, Visuals.wavelengthColor(470).deriveColor(0, 1, 0.5, 0.5)),
                new Stop(0.45, Visuals.wavelengthColor(530).deriveColor(0, 1, 0.5, 0.5)),
                new Stop(0.6, Visuals.wavelengthColor(580).deriveColor(0, 1, 0.5, 0.5)),
                new Stop(0.8, Visuals.wavelengthColor(650).deriveColor(0, 1, 0.5, 0.5)),
                new Stop(1, Visuals.wavelengthColor(770).deriveColor(0, 1, 0.5, 0.5))));
        gc.fillRect(visibleLeft, top, visibleRight - visibleLeft, height);

        // Au-delà du seuil, le photon arrache l'électron.
        double ionization = frame.getIonizationNowEv();
        if (ionization > 0) {
            double threshold = HC_EV_NM / ionization;
            double edge = xOf(threshold);
            gc.setFill(Color.web("#38BDF8", 0.12));
            gc.fillRect(12, top, edge - 12, height);
            gc.setStroke(Color.web("#38BDF8", 0.8));
            gc.setLineWidth(1);
            gc.strokeLine(edge, top - 2, edge, top + height + 2);
        }

        // Raies d'émission connues : discrètes.
        for (SpectralLine line : frame.getSpectrumList()) {
            double x = xOf(line.getWavelengthNm());
            gc.setStroke(Visuals.wavelengthColor(line.getWavelengthNm()).deriveColor(0, 1, 1, 0.35));
            gc.setLineWidth(1);
            gc.strokeLine(x, top + 4, x, top + height - 4);
        }
        // Photons réellement émis : ils s'allument puis s'éteignent.
        for (double[] photon : seen) {
            double alpha = Math.max(0.25, 1 - photon[1] / 10);
            double x = xOf(photon[0]);
            gc.setStroke(Visuals.wavelengthColor(photon[0]).deriveColor(0, 1, 1, alpha));
            gc.setLineWidth(2.5);
            gc.strokeLine(x, top, x, top + height);
        }
        // Ce que l'atome peut absorber MAINTENANT.
        double pulse = 0.6 + 0.4 * Math.sin(clock * 4);
        for (double nm : frame.getAbsorbableNmList()) {
            double x = xOf(nm);
            gc.setFill(Color.web("#FDE68A", pulse));
            gc.fillPolygon(new double[]{x - 4, x + 4, x}, new double[]{top + height + 9, top + height + 9,
                    top + height + 2}, 3);
        }
        // Le laser.
        double laserX = xOf(laserNm);
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(1.5);
        gc.strokeLine(laserX, top - 4, laserX, top + height + 4);
        gc.setFill(Color.WHITE);
        gc.fillPolygon(new double[]{laserX - 5, laserX + 5, laserX}, new double[]{top - 9, top - 9, top - 3}, 3);

        gc.setFont(Font.font("Consolas", 10));
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.TOP);
        for (double tick : new double[]{100, 200, 400, 700, 1000, 2000, 4000}) {
            gc.fillText(tick >= 1000 ? Visuals.format(tick / 1000) + " µm" : Visuals.format(tick),
                    xOf(tick), top + height + 11);
        }
        gc.setFill(Color.web("#94A3B8"));
        gc.fillText("UV", xOf(150), top + 12);
        gc.fillText("IR", xOf(1400), top + 12);

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI", 10));
        gc.setFill(Color.web("#FDE68A"));
        gc.fillText("▲ absorbable maintenant", 12, top + height + 24);
        if (ionization > 0) {
            gc.setFill(Color.web("#38BDF8"));
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText("◀ λ < " + Visuals.format(HC_EV_NM / ionization) + " nm arrache l'électron",
                    width - 12, top + height + 24);
        }
        gc.setTextBaseline(VPos.BASELINE);

        if (mouseX >= 0 && inSpectrum(mouseY)) {
            Double snap = nearestAbsorbable(mouseX);
            double nm = snap != null ? snap : nmAt(mouseX);
            String text = Visuals.format(nm) + " nm · " + Visuals.format(HC_EV_NM / nm) + " eV"
                    + (snap != null ? " · absorbable" : "");
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
            double boxWidth = text.length() * 6.2 + 12;
            double boxX = Math.max(4, Math.min(width - boxWidth - 4, mouseX - boxWidth / 2));
            gc.setFill(Color.web("#0F172A", 0.95));
            gc.fillRoundRect(boxX, top - 30, boxWidth, 18, 6, 6);
            gc.setFill(Color.WHITE);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText(text, boxX + 6, top - 17);
        }
    }
}
