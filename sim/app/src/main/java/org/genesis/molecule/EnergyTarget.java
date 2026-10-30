package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeOrbital;
import org.genesis.app.I18n;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Les niveaux d'énergie des orbitales, en cible. Le rayon suit l'énergie — pas une distance : au centre, les électrons
 * les plus liés ; vers le bord, des électrons de moins en moins retenus, jusqu'au cercle du vide (0 eV). Un niveau
 * occupé est un cercle plein qui porte ses électrons, en points ; le dernier, la HOMO, est orangé — les électrons les
 * plus faciles à arracher. Puis un anneau vide, doré : l'écart HOMO–LUMO, d'autant plus large que la molécule est
 * stable. Les niveaux vides sont en tirets, la LUMO en violet — la première place libre. Le rayon est linéaire en
 * énergie : deux écarts égaux se voient égaux. Les niveaux de même énergie (dégénérés) partagent un cercle.
 *
 * Les étiquettes sont posées sur leur cercle, comme les cotes d'une carte topographique : la HOMO en bas à gauche, la
 * LUMO en haut à droite, l'écart en bas, le vide en haut ; le cercle survolé (ou montré dans la vue ψ) en bas à
 * droite, et sa lecture complète au cœur.
 */
final class EnergyTarget {
    /** Un cercle : les orbitales [first, first + count), leur énergie (eV) et leurs électrons. */
    record Ring(int first, int count, double energy, double electrons, double radius) {}

    static final Color FILLED = Color.web("#6FC3FF"), HOMO = Color.web("#FFB35C"), LUMO = Color.web("#B79CFF"),
            EMPTY = Color.web("#8E86B8"), GAP = Color.web("#F2C46B"), VACUUM = Color.web("#7F90AC");
    /** Le cœur : un disque réservé à la lecture du niveau survolé ou choisi. */
    private static final double CORE = 44;
    /** Les niveaux vides montrés : jusqu'à 6 eV au-dessus de la LUMO (un niveau très haut écraserait les autres). */
    private static final double EMPTY_SPAN = 6;

    private final List<Ring> rings = new ArrayList<>();
    private double cx, cy, rim, vacuum;
    private int homo = -1;

    List<Ring> rings() {
        return rings;
    }

    /** Les cercles des orbitales homo − 5 … homo + 4, groupées quand elles ont la même énergie (à 0,02 eV près). */
    void layout(List<MoleculeOrbital> orbitals, int homo, double w, double h) {
        rings.clear();
        this.homo = homo;
        cx = w / 2;
        cy = h / 2;
        rim = Math.min(w, h) / 2 - 12;
        if (orbitals.isEmpty() || homo < 0 || homo >= orbitals.size()) return;
        int first = Math.max(0, homo - 5), last = Math.min(orbitals.size() - 1, homo + 4);
        if (homo + 1 < orbitals.size()) {
            double ceiling = orbitals.get(homo + 1).getEnergyEv() + EMPTY_SPAN;
            while (last > homo + 1 && orbitals.get(last).getEnergyEv() > ceiling) last--;
        }
        double low = orbitals.get(first).getEnergyEv() - .8;
        double high = Math.max(0, orbitals.get(last).getEnergyEv()) + .8;
        for (int i = first; i <= last; ) {
            double e = orbitals.get(i).getEnergyEv(), electrons = 0;
            int j = i;
            while (j <= last && Math.abs(orbitals.get(j).getEnergyEv() - e) < .02) electrons += orbitals.get(j++).getOccupation();
            rings.add(new Ring(i, j - i, e, electrons, radius(e, low, high)));
            i = j;
        }
        vacuum = radius(0, low, high);
    }

    private double radius(double energy, double low, double high) {
        return CORE + (rim - CORE) * (energy - low) / (high - low);
    }

    /** L'orbitale du cercle sous le pointeur (voir representative), −1 sinon. */
    int pick(double x, double y) {
        double d = Math.hypot(x - cx, y - cy), best = 5;
        int index = -1;
        for (Ring r : rings) {
            if (Math.abs(d - r.radius) < best) {
                best = Math.abs(d - r.radius);
                index = representative(r);
            }
        }
        return index;
    }

    /**
     * L'orbitale qui nomme un cercle de niveaux dégénérés : la HOMO s'il la porte, la LUMO s'il commence par elle ;
     * sinon la plus proche de l'écart (la dernière d'un cercle occupé, la première d'un cercle vide).
     */
    int representative(Ring r) {
        if (holdsHomo(r)) return homo;
        return r.first > homo ? r.first : r.first + r.count - 1;
    }

    private boolean holds(Ring r, int index) {
        return index >= r.first && index < r.first + r.count;
    }

    /** Le nom d'une orbitale : HOMO, LUMO, HOMO−k, LUMO+k. */
    static String name(int index, int homo) {
        int lumo = homo + 1;
        return index == homo ? "HOMO" : index == lumo ? "LUMO" : index < homo ? "HOMO−" + (homo - index) : "LUMO+" + (index - lumo);
    }

    private boolean holdsHomo(Ring r) {
        return r.first <= homo && r.first + r.count > homo;
    }

    private Color colour(Ring r) {
        return holdsHomo(r) ? HOMO : r.electrons > .05 ? FILLED : r.first == homo + 1 ? LUMO : EMPTY;
    }

    /** La cible : `chosen` est l'orbitale montrée dans la vue ψ (−1 : aucune), `hovered` celle sous le pointeur. */
    void draw(GraphicsContext g, double w, double h, List<MoleculeOrbital> orbitals, int chosen, int hovered) {
        g.clearRect(0, 0, w, h);
        if (rings.isEmpty()) return;
        g.setLineCap(StrokeLineCap.ROUND);
        // Le fond : une lueur au cœur, le cercle du vide.
        g.setFill(new RadialGradient(0, 0, cx, cy, rim + 8, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#16314F", .9)), new Stop(.55, Color.web("#0B1A2E", .6)), new Stop(1, Color.web("#070C18", 0))));
        g.fillOval(cx - rim - 8, cy - rim - 8, 2 * rim + 16, 2 * rim + 16);
        g.setStroke(VACUUM.deriveColor(0, 1, 1, .6));
        g.setLineWidth(.9);
        g.setLineDashes(1, 3);
        g.strokeOval(cx - vacuum, cy - vacuum, 2 * vacuum, 2 * vacuum);
        g.setLineDashes();
        // L'écart HOMO–LUMO : un anneau vide, doré, entre le dernier cercle plein et le premier vide.
        Ring top = null, bottom = null;
        for (Ring r : rings) {
            if (holdsHomo(r)) top = r;
            if (r.first == homo + 1) bottom = r;
        }
        if (top != null && bottom != null && bottom.radius - top.radius > 4) {
            double inner = top.radius + 2, outer = bottom.radius - 2, middle = (inner + outer) / 2;
            g.setStroke(GAP.deriveColor(0, 1, 1, .11));
            g.setLineWidth(outer - inner);
            g.strokeOval(cx - middle, cy - middle, 2 * middle, 2 * middle);
        }
        // Les cercles, du cœur vers le vide.
        Ring over = null, shown = null;
        for (Ring r : rings) {
            boolean filled = r.electrons > .05, isLumo = r.first == homo + 1;
            boolean isShown = holds(r, chosen), isOver = hovered >= 0 && holds(r, hovered);
            if (isShown) shown = r;
            if (isOver) over = r;
            Color colour = colour(r);
            double alpha = filled ? .9 : isLumo ? .85 : .45;
            if (isOver || isShown) alpha = 1;
            // Une lueur sous le trait, plus large pour le cercle montré dans la vue ψ.
            g.setStroke(colour.deriveColor(0, 1, 1, (isShown ? .34 : isOver ? .24 : .12) * alpha));
            g.setLineWidth(isShown ? 7 : isOver ? 6 : 4);
            if (!filled) g.setLineDashes(4, 4);
            g.strokeOval(cx - r.radius, cy - r.radius, 2 * r.radius, 2 * r.radius);
            g.setStroke(colour.deriveColor(0, 1, isOver ? 1.25 : 1, alpha));
            g.setLineWidth(filled ? (isShown ? 2.4 : 1.6) : (isShown ? 2 : 1.2));
            g.strokeOval(cx - r.radius, cy - r.radius, 2 * r.radius, 2 * r.radius);
            g.setLineDashes();
            // Les électrons, en points sur leur cercle (un point pâle pour une occupation fractionnaire).
            int dots = (int) Math.ceil(r.electrons - .05);
            for (int k = 0; k < dots; k++) {
                double share = Math.min(1, r.electrons - k);
                double angle = -Math.PI / 2 + (k + .5) * 2 * Math.PI / Math.max(dots, 1) + r.radius * .037;
                double x = cx + r.radius * Math.cos(angle), y = cy + r.radius * Math.sin(angle);
                g.setFill(colour.deriveColor(0, 1, 1, .28 * share));
                g.fillOval(x - 4.2, y - 4.2, 8.4, 8.4);
                g.setFill(Color.web("#FFFFFF", .95 * Math.max(.35, share)));
                g.fillOval(x - 2, y - 2, 4, 4);
            }
        }
        // Les cotes, posées sur leur cercle.
        pill(g, I18n.text("0 eV · le vide"), cx, cy - vacuum, VACUUM, false);
        if (top != null && bottom != null) {
            double middle = (top.radius + bottom.radius) / 2;
            pill(g, I18n.text("écart") + " " + MoleculeFormat.decimal(bottom.energy - top.energy, 2) + " eV", cx, cy + middle, GAP, true);
        }
        double diagonal = Math.sqrt(.5);
        if (top != null) {
            pill(g, "HOMO  " + MoleculeFormat.decimal(top.energy, 2), cx - top.radius * diagonal, cy + top.radius * diagonal, HOMO, true);
        }
        if (bottom != null) {
            pill(g, "LUMO  " + MoleculeFormat.decimal(bottom.energy, 2), cx + bottom.radius * diagonal, cy - bottom.radius * diagonal, LUMO, true);
        }
        Ring marked = over != null ? over : shown;
        if (marked != null && marked != top && marked != bottom) {
            pill(g, name(representative(marked), homo) + "  " + MoleculeFormat.decimal(marked.energy, 2), cx + marked.radius * diagonal,
                    cy + marked.radius * diagonal, colour(marked), true);
        }
        // Le cœur : la lecture du cercle survolé, sinon de celui montré dans la vue ψ, sinon de la HOMO.
        g.setFill(new RadialGradient(0, 0, cx, cy, CORE - 6, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#1B3554", .95)), new Stop(1, Color.web("#0B1626", .95))));
        g.fillOval(cx - CORE + 6, cy - CORE + 6, 2 * CORE - 12, 2 * CORE - 12);
        g.setStroke(Color.web("#2A3B52", .9));
        g.setLineWidth(.8);
        g.strokeOval(cx - CORE + 6, cy - CORE + 6, 2 * CORE - 12, 2 * CORE - 12);
        Ring read = marked != null ? marked : top;
        if (read != null) {
            g.setTextAlign(TextAlignment.CENTER);
            g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5));
            g.setFill(colour(read));
            g.fillText(name(representative(read), homo) + (read.count > 1 ? "  ×" + read.count : ""), cx, cy - 9);
            g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
            g.setFill(Color.web("#EAF2FF"));
            g.fillText(MoleculeFormat.decimal(read.energy, 2) + " eV", cx, cy + 6);
            g.setFont(Font.font("Segoe UI", 9.5));
            g.setFill(Color.web("#A9BCD6"));
            double e = read.electrons;
            String electrons = e <= .05 ? I18n.text("vide") : Math.abs(e - Math.rint(e)) < .05
                    ? MoleculeFormat.decimal(e, 0) + " " + I18n.text(Math.rint(e) >= 2 ? "électrons" : "électron")
                    : MoleculeFormat.decimal(e, 2) + " " + I18n.text(e >= 2 ? "électrons" : "électron");
            g.fillText(electrons, cx, cy + 19);
        }
    }

    /** Une cote : un texte sur fond sombre, centré sur (x, y), posé sur le cercle qu'il nomme. */
    private static void pill(GraphicsContext g, String text, double x, double y, Color colour, boolean strong) {
        Font font = Font.font("Segoe UI", strong ? FontWeight.SEMI_BOLD : FontWeight.NORMAL, 9.5);
        Text probe = new Text(text);
        probe.setFont(font);
        double width = probe.getLayoutBounds().getWidth() + 10, height = 14;
        g.setFill(Color.web("#081120", .92));
        g.fillRoundRect(x - width / 2, y - height / 2, width, height, height, height);
        g.setStroke(colour.deriveColor(0, 1, 1, strong ? .55 : .35));
        g.setLineWidth(.8);
        g.strokeRoundRect(x - width / 2, y - height / 2, width, height, height, height);
        g.setFont(font);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(colour.deriveColor(0, .85, 1.15, 1));
        g.fillText(text, x, y + 3.4);
    }
}
